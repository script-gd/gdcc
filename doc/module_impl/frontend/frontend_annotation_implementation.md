# Frontend Annotation 实现约定

> 本文档作为 GDScript annotation（`@tool` 与 `@export*` 家族）在 frontend、LIR 与 backend 之间的长期事实源，定义当前支持面、attachment 合同、diagnostic owner、metadata 编码、backend 渲染与已知边界。本文档取代原实施计划文档，不再保留分步骤实施、进度记录与验收流水账；合同变化时直接改写当前状态。

## 文档状态

- 状态：事实源维护中
- 更新时间：2026-09-05
- 适用范围：
    - `src/main/java/gd/script/gdcc/frontend/`（parse、sema、analyzer）
    - `src/main/java/gd/script/gdcc/lir/`（`LirClassDef` / `LirPropertyDef`、XML 序列化）
    - `src/main/java/gd/script/gdcc/backend/c/` 与 `src/main/c/codegen/`（property metadata、virtual dispatch）
- 关联文档：
    - `frontend_rules.md`
    - `diagnostic_manager.md`
    - `frontend_type_check_analyzer_implementation.md`（`@onready` 合同不在本文档重复）
    - `frontend_static_var_implementation.md`
    - `doc/gdcc_low_ir.md`
    - `doc/gdcc_c_backend.md`

---

## 1. 维护合同

- 本文档覆盖 annotation 的 attachment、skeleton/LIR 承载、usage 校验与 backend 渲染合同；通用的 phase owner 划分、恢复约定与诊断 category 规范以 `frontend_rules.md` 与 `diagnostic_manager.md` 为准。
- 合同变化时必须同步：本文档、`FrontendAnnotationCollector` / `FrontendExportAnnotationSupport` / `FrontendAnnotationUsageAnalyzer` / `CGenHelper` 的类级 `///` 注释，以及 §6 的测试锚点。
- Godot 行为基线固定为 `4.5.1-stable`（与 `template_451` / `include_451` 对齐）；新版本 Godot 的注解行为（如 `@export_multiline` 的 hint 参数）不得提前引入。

## 2. 当前支持面

- `@tool`：script 级标记（Godot `AnnotationInfo::SCRIPT` 对齐），贯通 parse → skeleton → LIR → backend 帧循环 gate。
- `@export` 家族：`export`、`export_range`、`export_enum`、`export_flags`、`export_flags_2d_render/2d_physics/2d_navigation/3d_render/3d_physics/3d_navigation/avoidance`、`export_file`、`export_file_path`、`export_dir`、`export_global_file`、`export_global_dir`、`export_multiline`、`export_placeholder`、`export_exp_easing`、`export_color_no_alpha`、`export_node_path`。
- 当前不支持（保留 side-table 事实并走既有边界）：
    - `@export_category` / `@export_group` / `@export_subgroup`：需要 LIR 表达分组条目与 property 的相对顺序并接入 ClassDB group 注册 API，属较大变更。
    - `@export_storage`、`@export_custom`、`@export_tool_button`：依赖 usage flag 自由组合 / Callable property / `@tool` 完整编辑器语义。
    - `@rpc` lowering、`@icon`、函数参数 / setter/getter / match pattern 上的注解（gdparser AST 当前不承载这些挂载点）。
    - `@warning_ignore_start` / `@warning_ignore_restore`：collector 直接忽略；其余 `@warning_ignore` 按未知注解 retention。

## 3. 架构与集成位置

### 3.1 Parser 与 attachment

- gdparser 把注解解析为独立 `AnnotationStatement(name, arguments, range)`，参数保留为 AST `Expression`；同行与前一行写法在 AST 层同构。字面量为 `LiteralExpression(kind, sourceText)`（`kind` 是 Tree-sitter 节点类型），负数是一元表达式 `UnaryExpression`。
- `FrontendAnnotationCollector` 把注解写入共享 `annotationsByAst()` side-table，挂载规则（Godot `parse_program` 对齐）：
    - 每个 statement list 维护 owner preamble：preamble 开放时 script-scoped 注解（`tool` / `icon`）挂到 list owner（顶层为 `SourceFile`，inner class 为 `ClassDeclaration`）。
    - member-target 注解（export 家族、`onready`、`rpc`）挂到紧随的 statement，并立即关闭 preamble——`@export` 之后再写 `@tool` 属于非法 placement。
    - 其余注解（`abstract`、`warning_ignore`、未知注解）挂到紧随的 statement，但不关闭 preamble——`@abstract` / `@warning_ignore` 之后的 `@tool` 仍合法。
    - `CommentStatement` 与 preamble 开放期内的字符串字面量 `ExpressionStatement`（docstring，任意 statement list 的前导位置均生效，含 class body；preamble 关闭后的字符串表达式是普通 statement）是透明 trivia：不触发 flush、不关闭 preamble。
    - list 末尾仍 pending 的注解：preamble 开放且为 script-scoped（`tool` / `icon`）→ 挂到 list owner（合法顶层 `@tool` 的路径之一）；否则锚定到其自身 `AnnotationStatement`，作为 placement 诊断的稳定锚点，skeleton 不会当成 member 注解消费。同批中 member-target 注解会在 list-end flush 内关闭 preamble（`@export` 之后且无后续 statement 的 `@tool` 因此自锚定并报错）。
- skeleton build 先运行 collector 再创建 class shell，shell 创建时注解事实已可读。

### 3.2 Skeleton 与 LIR

- `@tool`：挂在顶层 `SourceFile` 上的零参 `tool` 被消费为 script 级 flag——该 `SourceFile` 的顶层与全部 inner `LirClassDef` 都 `setTool(true)`（Godot 对每个编译出的 class 都复制 `parser->is_tool()`），仅顶层额外 `setAnnotation("tool", "")`。
- property 注解经 `applyPropertyAnnotations` 分流：
    - `export` / `onready` / 已支持的 export 家族且参数 well-formed → 写入 `LirPropertyDef.getAnnotations()`（value 编码见 §5.2）。
    - 已支持家族但参数 malformed → 只 retention 不写 metadata、不发诊断（参数诊断归 usage analyzer）。
    - `tool` / `icon` 即使挂在 member 或尾随锚点上也只 retention，不发 `sema.unsupported_annotation`。
    - 其余 property 注解 → `sema.unsupported_annotation` error + retention。
- LIR 承载：`LirClassDef.isTool` 与 class/property `<annotation key value/>` 经 `DomLirSerializer` / `DomLirParser` roundtrip；class 级 annotation 读回只取 `class_def` 的直接子元素（不会混入后代 property/function 注解）。

### 3.3 Annotation-usage phase

- `FrontendAnnotationUsageAnalyzer` 位于 var-type-post 之后、virtual override 之前（`FrontendSemanticAnalyzer` 主链路），只发诊断、不改写 metadata，可消费 `expressionTypes()` 等 typed facts。
- 校验范围：`@tool` placement（仅顶层 `SourceFile`）与零参；export 家族的 placement / static / 参数 / 类型兼容 / Object 家族规则（§5.3）；`@onready` 合同见 `frontend_type_check_analyzer_implementation.md`。
- side-table 中每一处 `tool`（`SourceFile`、inner `ClassDeclaration`、member、尾随 `AnnotationStatement` 锚点）都被校验；仅 `annotationsByAst()[SourceFile]` 上的零参 `@tool` 合法。

### 3.4 Backend 消费

- `CGenHelper.renderPropertyMetadata` 是 property outward metadata 的唯一入口：annotation 驱动的 hint/hint_string/usage（§5.5）。
- `entry.c.ftl` 的 property 注册经 `gdcc_bind_property_full(...)` 传递 `propertyMetadata.classNameExpr`（不再是 owner 类名）。
- `@tool` 帧循环 gate 生成在 per-class `call_virtual_with_data` 的 `_process` / `_physics_process` 分派分支内（§5.6）。

## 4. 输入输出与 diagnostic owner

### 4.1 输入事实

- `annotationsByAst()`（collector 发布的 attachment 事实）
- skeleton 发布的 `LirClassDef` / `LirPropertyDef`（含声明类型与 annotations）
- `expressionTypes()`（Variant property 的 initializer 回退判定）
- `scopesByAst()`（class property 与 local var 的 `ClassScope` / `BlockScope` 区分）
- `ClassRegistry`（Resource/Node 派生判定）

### 4.2 输出事实

- class 级 `isTool` 与 class annotation；property annotation metadata（key → hint_string）
- `sema.annotation_usage` / `sema.unsupported_annotation` 诊断
- backend property metadata（hint、hint_string、usage、class_name）与帧循环 gate 代码

### 4.3 Diagnostic owner

- `sema.unsupported_annotation`：annotation 已识别但 frontend 尚未支持（skeleton 持有）。
- `sema.annotation_usage`：annotation 已支持但 placement / staticness / 参数 / 类型非法（`FrontendAnnotationUsageAnalyzer` 持有）。
- 消息模板（锚定注解 range）：

| 规则 | 消息模板 |
| --- | --- |
| export 家族挂在非 class-property | `@<name> can only be used on class properties declared with 'var'` |
| export 家族 × static property | `@<name> cannot be used on static property '<prop>'` |
| 参数个数 / 字面量 / 类型不符 | `@<name> argument <i> ...` 或 `expects ...`（由 `FrontendExportAnnotationSupport` 给出原因） |
| property 类型不兼容 | `@<name> can only be used on properties of type <types>, but '<prop>' is <type>` |
| 裸 `@export` 无法定型 | `@export requires a determinable property type, but '<prop>' has neither a type annotation nor an inferable initializer` |
| 裸 `@export` 非可导出 Object 家族 | `@export can only export built-in, Resource, Node, or enum types, but '<prop>' is <type>` |
| 裸 `@export` Node 类型但 owner 非 Node 派生 | `@export of Node type is only supported in Node-derived classes, but '<prop>' is declared in '<owner>'` |
| `@tool` 非顶层挂载 | `@tool can only be used at the top of the script, before "extends" and "class_name"` |
| `@tool` 带参数 | `@tool does not accept any arguments` |

### 4.4 Compile gate 边界

- compile-only gate 不做注解扫描：`sema.annotation_usage` error 经 `hasErrors()` 阻断 lowering/backend；compile gate 不把既有注解诊断重复包装成 `sema.compile_check`。

## 5. 当前冻结合同

### 5.1 Export 参数求值合同

- 参数解析、校验与 hint_string 编码的唯一实现是 `FrontendExportAnnotationSupport`（skeleton 映射与 usage 校验共用，三态结果 `Supported` / `Malformed` / `NotExportFamily`）。
- 只接受字面量参数：`int` / `float` / `String` 字面量与数值前的 unary `+` / `-`；常量引用、枚举成员、运算表达式一律 `sema.annotation_usage`（Godot 接受任意常量表达式，此为刻意收窄，§7）。
- arity 合同（Godot 4.5.1 注册签名）：`export_range` ≥2 个数值参数 + 可选第 3 个数值 + 其后 0..N 个字符串；`export_enum` / `export_flags` ≥1 个字符串；`export_file` / `export_file_path` / `export_global_file` / `export_node_path` / `export_exp_easing` 0..N 个字符串；`export` / `export_multiline` / `export_dir` / `export_global_dir` / `export_color_no_alpha` / layer flags 恰好 0 个；`export_placeholder` 恰好 1 个。
- 列表型 hint_string 的字符串参数（enum / flags / node_path / file filter / range extra hints / exp_easing hints）不得包含 `,`；非 `export_placeholder` 的字符串参数不得为空串。
- 字符串解码统一走 `StringUtil.decodeGdStringLexeme(...)`：覆盖 `"..."` / `'...'` / 三引号 / raw（`r` 前缀）各形式；转义集为 Godot 4.5.1 全集（`\a \b \f \n \r \t \v \' \" \\ \u \U`），`\u` 恰好 4 位 hex、`\U` 恰好 6 位 hex；raw 形式仅对 `\<quote>` 与 `\\` 保留反斜杠。解码失败转为 `Malformed`，不作为异常穿越 analyzer。
- 数值编码采用最短无损格式（`1.0` → `1`）；hint_string 只 join **显式写出的参数**（Godot 的 `resolve_annotation` 不填充默认值）：`@export_range(0, 100)` 编码为 `"0,100"`；extra hints 必须从第 4 个参数开始。

### 5.2 LIR annotation value 编码合同

`LirPropertyDef.annotations` 为 `Map<String, String>`，value 固定为 **Godot `hint_string` 原文**（不带引号、不含注解名前缀），空 hint_string 存 `""`。表顺序即 backend 的 variant 选择优先级（§5.5）：

| key | value 示例 | backend hint | property 类型约束 |
| --- | --- | --- | --- |
| `export_range` | `"0,20,0.5"` / `"0,100,1,or_greater"` | `PROPERTY_HINT_RANGE` | `int` / `float` |
| `export_enum` | `"Warrior,Mage"`（`"Name:value"` 原样透传） | `PROPERTY_HINT_ENUM` | `int` / `String` / `Variant` |
| `export_flags` | `"Fire,Water,Earth"` | `PROPERTY_HINT_FLAGS` | `int`（与 `float` 互通） |
| `export_flags_*`（layer 系列） | `""` | 对应 `PROPERTY_HINT_LAYERS_*` | `int`（与 `float` 互通） |
| `export_file` | `"*.png"` 或 `""` | `PROPERTY_HINT_FILE` | `String` |
| `export_file_path` | 同 `export_file` | `PROPERTY_HINT_FILE_PATH` | `String` |
| `export_dir` | `""` | `PROPERTY_HINT_DIR` | `String` |
| `export_global_file` | 同 `export_file` | `PROPERTY_HINT_GLOBAL_FILE` | `String` |
| `export_global_dir` | `""` | `PROPERTY_HINT_GLOBAL_DIR` | `String` |
| `export_multiline` | `""` | `PROPERTY_HINT_MULTILINE_TEXT` | `String` |
| `export_placeholder` | `"Enter name..."` | `PROPERTY_HINT_PLACEHOLDER_TEXT` | `String` |
| `export_exp_easing` | `""` / `"attenuation,positive_only"` | `PROPERTY_HINT_EXP_EASING` | `float`（与 `int` 互通） |
| `export_color_no_alpha` | `""` | `PROPERTY_HINT_COLOR_NO_ALPHA` | `Color` |
| `export_node_path` | `"Node2D,Sprite2D"` | `PROPERTY_HINT_NODE_PATH_VALID_TYPES` | `NodePath` |
| `export`（裸） | `""` | 类型派生（含 Object → Resource/Node hint） | 可定型 + Object 家族限制（§5.3） |

layer 系列内部的固定全序为 `2d_render → 2d_physics → 2d_navigation → 3d_render → 3d_physics → 3d_navigation → avoidance`（与 backend 优先级列表一致；压缩行不影响单 key 场景的选择）。

### 5.3 类型事实源与兼容规则

usage analyzer 的有效类型判定为四态（`resolveEffectiveExportType`）：

1. 显式非 `Variant` 声明类型 → `Determined`（以 `LirPropertyDef.getType()` 为准）。
2. LIR 类型为 `Variant`（显式 `: Variant`、未标注或 `:=`）且无 initializer：存在非 inferred 类型标注（`: Variant`）→ `VariantTyped`；无类型标注 → `Undetermined`。
3. 有 initializer → 读根表达式 `expressionTypes()`：具体非 `Variant` → `Determined`；`RESOLVED(Variant)` / `DYNAMIC` → `VariantTyped`；`BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` → `UpstreamBlocked`（不补发 determinability 诊断，上游 owner 已持有根因）。
4. `VariantTyped`：裸 `@export` 与默认检查族放行，仅 `export_multiline` 报类型不兼容。`Undetermined`：裸 `@export` 与 `export_multiline` 报错；`export_enum` 与其余走默认检查的 variant 放行（Godot 默认类型检查对未定型的 `Variant` 跳过）。

以下规则只作用于 `Determined`：

5. 默认检查族（range / flags / layer flags / exp_easing）的 `int` 与 `float` 声明类型互通。
6. 容器声明类型（`Array[T]` / packed array / `Dictionary`）上的全部 export variant 一律报类型不兼容（不做 Godot 的 Array peel，§7）；裸 `export` 不受限。
7. 裸 `@export` 的 Object 家族限制：`Resource` 派生或 `Node` 派生放行；其余 Object 类型（如裸 `RefCounted`）报错；脚本 enum 导出当前不支持。
8. 裸 `@export` 导出 `Node` 派生类型时，owner class 必须派生自 `Node`。

### 5.4 `@tool` 合同

- 合法形态只有“顶层 `SourceFile` 上的零参 `@tool`”；inner class / member / 尾随锚点 / 带参实例一律 retention + `sema.annotation_usage`，不置位任何 `isTool`。
- 合法实例把该 `SourceFile` 的顶层与全部 inner `LirClassDef` 置为 tool（script 级传播），仅顶层写 class annotation。
- 重复零参 `@tool` 幂等置位，不诊断（与 Godot DEBUG 警告的刻意差异）。

### 5.5 Backend 渲染合同

- variant 选择按 §5.2 表自上而下的固定全序（裸 `export` 垫底），不依赖 `HashMap` 迭代顺序；同名 variant 重复出现为 last-wins，不发诊断（与 Godot 硬错误的刻意差异）。
- 命中 variant → hint/hint_string 完全由注解 value 驱动；仅命中裸 `export` → 类型派生 hint（typed Array/Dictionary 维持 `PROPERTY_HINT_ARRAY_TYPE` / `DICTIONARY_TYPE`；`GdObjectType` 经 `ClassRegistry` 判定 Resource 派生 → `PROPERTY_HINT_RESOURCE_TYPE`、Node 派生 → `PROPERTY_HINT_NODE_TYPE`，hint_string/class_name 取 property 类型类名）。
- 任一 export 家族 key 存在 → base usage 为 `PROPERTY_USAGE_DEFAULT`；否则 `NO_EDITOR`；`Variant` 叠加 `PROPERTY_USAGE_NIL_IS_VARIANT`。
- backend 不重复校验参数合法性；未知 key 静默忽略。

### 5.6 `@tool` 帧循环 gate 合同

- 语义：`isTool==false` 的类在编辑器内（`Engine.is_editor_hint()` 为 true）不执行 `_process` / `_physics_process`；`isTool==true` 的类（含 `@tool` 文件传播置位的 inner class）照常执行；运行时两类都执行。
- 插入点：per-class `call_virtual_with_data` 中 `_process` / `_physics_process` 的 userdata 匹配分支**内部、`ptrcall` 之前**；gate 直接 `return`、不写 `r_ret`（两者为 void 虚函数）。不得放在函数入口统一拦截（会误闸 `_ready` 等非帧循环虚函数）。
- 编译期按 `LirClassDef.isTool` 决定是否生成：tool 类零开销不生成检查。
- 运行时检测复用 `gdcc_is_editor_hint()`（`godot_Engine_singleton()` 由 `gdcc_init()` 在 initialize 时缓存），不新增引擎访问通道。
- Godot 的完整抑制靠 `PlaceHolderScriptInstance`（非 tool 脚本在编辑器内不创建真实例、一切回调不执行）；GDExtension 无此机制，gdcc 只 gate 帧循环，`_ready` / `_notification` / `_input` 等不 gate（§7）。

## 6. 测试锚点

- Parser：`FrontendAnnotationParseBehaviorTest`（注解 AST 形态与参数保留）。
- Collector / skeleton：`FrontendClassSkeletonAnnotationTest`（`@tool` 置位与传播、property 映射、malformed retention、unsupported 边界）。
- Usage：`FrontendAnnotationUsageAnalyzerTest`（placement / static / arity / 类型兼容 / Object 家族 / 上游抑制 / `@tool`）。
- 参数 helper：`FrontendExportAnnotationSupportTest`（arity、字面量、编码）。
- 字符串解码：`StringUtilTest`（各 lexeme 形式与转义全集，含 6 位 `\U`）。
- LIR roundtrip：`DomLirSerializerTest` / `DomLirParserTest`（`is_tool`、class annotation 直接子元素边界）。
- Backend：`CGenHelperTest`（variant hint/usage/classNameExpr 渲染）、`CCodegenTest`（注册参数与帧循环 gate 的有序代码生成断言）。
- 端到端：`GdScriptUnitTestCompileRunnerTest` 的 annotation 脚本（如 `tool_process_runtime.gd`，只验证非 editor 路径可执行）。

## 7. 已知限制与 Godot 刻意差异

- 同 property 多 export 注解：Godot 硬错误，gdcc 不诊断；同名 variant last-wins，不同 key 按 §5.2 表序取第一个。
- 重复 `@tool`：Godot DEBUG 警告，gdcc 幂等不诊断。
- 容器声明类型上的 export variant（Godot 的 Array peel）：gdcc 一律报 `sema.annotation_usage`。
- `@export_multiline` 在 Godot 4.5.1 允许 `String` / `Dictionary`，gdcc 只允许 `String`。
- 注解参数只接受字面量；Godot 接受任意常量表达式。
- `@export_node_path` 的类名存在性与 `Node` 派生检查（Godot 查 ClassDB）：gdcc 不做。
- `@export_flags` 的条目名校验、显式整数值（`"Name:2"`）与 32 位上限检查：gdcc 不做。
- 非 tool 类在编辑器内仍执行 `_ready` / `_notification` / `_input` 等非帧循环回调（Godot placeholder 机制抑制全部回调）。
- initializer 推断类型不进入导出 metadata（已确认接受的差异）：显式标注类型时导出类型=声明类型；未标注/`:=` 时保持 `Variant` + `NIL_IS_VARIANT`，类型回退只用于诊断。对齐 Godot 的推断导出需要引入结构化 export metadata（导出 type / hint / hint_string / class_name / usage）并贯通 LIR 模型、XML serializer/parser 与 backend——`Map<String, String>` 无法承载，属架构级变更。

## 8. 长期风险与维护提醒

- **gdparser 依赖**：参数 AST 形态（`LiteralExpression(kind, sourceText)`、unary 数值、字符串 lexeme 形式集）依赖 gdparser 的 AST 结构；升级 gdparser 时必须保持这些合同并回归 §6 测试。
- **`Map<String, String>` 编码上限**：无法表达 group 顺序、容器元素 hint 包装（`PROPERTY_HINT_TYPE_STRING`）与结构化 metadata；扩张这些能力前必须先演进 LIR 模型。
- **Godot 版本基线**：以 `4.5.1-stable` 为准；不得提前引入新版本行为。
- **owner 边界**：参数/位置诊断不得回流 skeleton（保持 `sema.annotation_usage` 单一 owner）；compile gate 不得重复包装注解诊断。
