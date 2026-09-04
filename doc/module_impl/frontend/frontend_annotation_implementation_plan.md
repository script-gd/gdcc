# Frontend Annotation（`@tool` / `@export*`）实施计划

> 本文档是 GDScript 注解在 gdcc 中的分期实施计划，本期范围固定为 `@tool` 与 `@export*` 家族。文档同时记录已冻结的现状事实、目标合同、分步骤实施顺序与验收细则；落地完成后，稳定事实应回流到 `diagnostic_manager.md`、`frontend_rules.md` 与 `gdcc_low_ir.md`，本文档再转为事实源或归档。

## 文档状态

- 状态：计划待实施（已经过多轮审阅修订并获批；Godot 行为以 `4.5.1-stable` 为准逐一核对）
- 更新时间：2026-09-04
- 当前事实源：
    - `frontend_rules.md`
    - `diagnostic_manager.md`
    - `frontend_type_check_analyzer_implementation.md`
    - `frontend_static_var_implementation.md`
    - `scope_analyzer_implementation.md`
    - `doc/gdcc_low_ir.md`
    - `doc/gdcc_c_backend.md`

---

## 1. 目标与非目标

### 1.1 本期目标

1. `@tool` 全链路贯通：parse（已具备）→ skeleton 写入 `LirClassDef.isTool` 与 class annotation → annotation-usage 校验 → LIR XML roundtrip（承载能力已具备，含既有缺陷修复）→ **backend 帧循环 gate**（§3.6）。
2. `@export*` 家族贯通：skeleton 将参数编译期求值为 Godot `hint_string` 并写入 property annotation metadata，annotation-usage 校验参数与挂载位置，backend 渲染对应 `PROPERTY_HINT_*` / `hint_string` / `usage`。
3. 保持既有诊断 owner 边界：skeleton 只负责 retention 与 `sema.unsupported_annotation` 边界；`sema.annotation_usage` 统一由 `FrontendAnnotationUsageAnalyzer` 发出。

### 1.2 本期非目标（继续走 `sema.unsupported_annotation` 或静默 retention，不得提前实现）

- `@export_category` / `@export_group` / `@export_subgroup`：它们不是 property annotation，需要 LIR 表达“分组条目与 property 的相对顺序”并接入 `classdb_register_extension_class_property_group/subgroup`，属于 LIR 模型 + backend 注册面的较大变更，本期不实施。
- `@export_storage`、`@export_custom`、`@export_tool_button`：storage 与 custom 需要 usage flag 自由组合合同，tool_button 依赖 Callable property 与 `@tool` 编辑器执行语义，均单独延后。
- `@rpc` lowering、`@warning_ignore*`（现有忽略行为不变）、`@icon`（仅 retention，本期不做任何校验或消费）。
- `@onready` 的 runtime `_ready()` 语义（维持现有 retention + usage validation 合同）。
- 函数参数、setter/getter、match pattern 上的注解（gdparser AST 当前不承载这些挂载点）。
- `@tool` 的完整编辑器运行语义：`GDExtensionClassCreationInfo5` 没有 `is_tool` 字段（`entry.c.ftl:61-65`），Godot 官方也未在 GDExtension 注册面提供 tool flag，GDScript 在编辑器内的完整抑制靠 `PlaceHolderScriptInstance`（不创建真脚本实例，一切回调不执行，§2.6）。gdcc 本期只对齐其中的**帧循环**部分（`_process` / `_physics_process`，§3.6）；`_ready` / `_notification` / `_input` 等非帧循环回调在编辑器内的抑制不实施（已知差异，§5）。
- 容器元素导出（Godot 的 Array peel 行为，如 `@export_range(0, 10) var xs: Array[int]`、`@export_enum("A") var xs: Array[int]`、`@export_node_path var ns: Array[NodePath]`、packed 数组变体）：需要 `PROPERTY_HINT_TYPE_STRING` 包装编码，超出 `Map<String, String>` value 的表达能力。本期对容器声明类型（`Array[T]` / packed array / `Dictionary`）上的**所有** export variant（含 `export_enum` / `export_multiline` / layer flags）一律发 `sema.annotation_usage`；裸 `@export` 不受限。
- initializer 推断类型进入导出 metadata（如 `@export var count = 1` 在 Godot 中导出为 `INT`）：需要结构化 export metadata 模型，属架构级议题，保留待决策（§5 第 7 条）。

---

## 2. 现状事实（已核实）

### 2.1 parser 与 collector

- parser 为外部依赖 `com.github.SuperIceCN:gdparser:0.5.3`（Tree-sitter），注解解析为独立 `AnnotationStatement(name, arguments, range)`，参数保留为 AST `Expression`；同行与前一行两种写法在 AST 层同构。字面量节点为 `LiteralExpression(kind, sourceText, range)`，`kind` 为 Tree-sitter 节点类型（`integer` / `float` / `string` / `string_name` / `true` / `false` / `null` / `node_path`）；负数等是一元表达式 `UnaryExpression(operator, operand, range)`。
- **实测的 AST 形态**（gdparser 0.5.3 探针验证）：
    - 字符串字面量接受 `"..."`、`'...'`、`"""..."""`、`'''...'''`、`r"..."`、`r'...'`，统一为 `LiteralExpression(kind="string", sourceText=<原始 lexeme>)`。
    - 注释产生 `CommentStatement`，混入 statement list。
    - `@tool` + `@export var hp` 连续出现时，AST 为两个 `AnnotationStatement` 后跟 `VariableDeclaration`。
    - 尾随注解（list 末尾的 `AnnotationStatement`）在 AST 中保留。
- `FrontendAnnotationCollector`（`frontend/sema/analyzer/FrontendAnnotationCollector.java:29-72`）按 statement list 顺序挂载注解：`tool` / `icon` 为 owner-scoped，位于 list 开头时挂到 `SourceFile` / `ClassDeclaration`；否则挂到紧随的 statement；`warning_ignore_start/restore` 被忽略。`FrontendGdAnnotation` 保留原始参数表达式。
- **collector 既有缺陷**（本期随 Step 1 一并修复）：
    1. 只在遇到非 annotation statement 时 flush pending 队列，**list 以注解结尾时尾随注解被静默丢弃**（`FrontendAnnotationCollector.java:51-72` 无 list 结束 flush）。
    2. flush 时对**整个 pending 批次**做 `allMatch(owner-scoped)` 判定：`@tool` + `@export var hp` 会把合法 script 级 `@tool` 误挂到 property 上。
    3. `CommentStatement` 是普通 statement，会把 `atListStart` 置 false：文件头注释后的合法顶层 `@tool` 会被误挂到紧随的 statement。
- 挂载顺序已验证：`build(...)` 先运行 collector 合并进 `annotationsByAst()`（`FrontendClassSkeletonBuilder.java:51-56`），之后才 `createClassShell(...)`（`FrontendClassSkeletonBuilder.java:117`），因此 shell 创建时注解事实已可读。

### 2.2 skeleton 与 LIR

- `applyPropertyAnnotations`（`FrontendClassSkeletonBuilder.java:412-424`）当前只把 `export` / `onready` 映射为 `LirPropertyDef.getAnnotations()` 的空串 value；其余 property 注解发 `sema.unsupported_annotation` 并保留 side-table 事实。
- class 级注解当前没有任何 skeleton 消费：`createClassShell`（`FrontendClassSkeletonBuilder.java:231-239`）不读 `annotationsByAst`，`LirClassDef.isTool` 恒为 `false`。
- class 关系模型：`FrontendSourceClassRelation.astOwner()` **恒为顶层 `SourceFile`**（`FrontendSourceClassRelation.java:62-65`）；inner class 的 owner 是 `FrontendInnerClassRelation.astOwner()`（`ClassDeclaration`）。跨顶层/inner 的按 AST owner 查询应使用 `FrontendSourceClassRelation.findRelation(node)`（identity 匹配，`FrontendSourceClassRelation.java:82-94`）或遍历 `FrontendOwnedClassRelation`。
- `LirClassDef` 已具备 `isTool` / `setTool` / `setAnnotation`（`lir/LirClassDef.java:82-108`）；`DomLirSerializer` 已序列化 `is_tool` 与 class/property `<annotation key value/>`（`DomLirSerializer.java:40-49,77-82`）。
- **既有 bug**：`DomLirParser` 读回 class 级 annotation 使用 `getElementsByTagName("annotation")`（`DomLirParser.java:56-63`），会把后代 property/function 的 annotation 递归收进 class map。property 级读回正常（`DomLirParser.java:100-106`）。本期随 Step 1 修复为只读直接子元素。

### 2.3 annotation-usage analyzer

- `FrontendAnnotationUsageAnalyzer`（`frontend/sema/analyzer/FrontendAnnotationUsageAnalyzer.java`）当前只校验：`@onready`（var / non-static / Node 派生）与 `@export × static`；phase 位置在 var-type-post 之后、virtual override 之前（`diagnostic_manager.md:388`），因此 `expressionTypes()` 等 typed facts 已可读。
- 当前遍历不经过 `SourceFile` 容器节点自身（`walkSourceFile` 只对 statement 调 `walkNode`），挂在 `SourceFile` 上的 `@tool` 不会被校验；inner `ClassDeclaration` 与 `AnnotationStatement` 节点会被 `walkNode` 访问（尾随注解锚定到自身 `AnnotationStatement` 后无需新增遍历入口）。
- 当前不存在：export 家族其它成员的任何校验、非 property 挂载点校验、参数校验、`@tool` 校验。
- local `var` 上的注解可被 collector + visitor 走到（local var 的 scope 是 `BlockScope` 而非 `ClassScope`），placement 校验无需新增 scope 设施。
- analyzer 已持有 `classRegistry`，可复用 `checkAssignable(GdObjectType, GdObjectType)` 判断 Resource/Node 派生（现有 `isNodeDerived` 模式）。

### 2.4 backend

- `CGenHelper.renderPropertyMetadata`（`backend/c/gen/CGenHelper.java:1450-1461`）当前仅按 `export` key 切换 `PROPERTY_USAGE_DEFAULT` / `NO_EDITOR`；`renderBoundMetadata`（`CGenHelper.java:1416-1439`）已具备 hint/hint_string 输出通道（typed Array → `PROPERTY_HINT_ARRAY_TYPE`、typed Dictionary → `PROPERTY_HINT_DICTIONARY_TYPE`），但 `classNameExpr` 恒为空。
- `CGenHelper` 经 `context.classRegistry()` 可访问 `checkAssignable(...)` / `getRefCountedStatus(...)` 等 class 元数据（如 `CGenHelper.java:727,1695`），backend 判断 Object 类型的 Resource/Node 派生无需新增设施。
- `entry.c.ftl:196-205` 的 `gdcc_bind_property_full(...)` 已完整接收 hint/hint_string/class_name，但**当前把 owner 类名固定传给 property 的 class_name 槽**（`entry.c.ftl:203` 传 `class_name` 而非 `propertyMetadata.classNameExpr`），Object 类型导出时需要修正。
- `LirPropertyDef.annotations` 底层是 `HashMap`，迭代顺序不稳定；backend 多 key 选择必须显式定序（§3.5）。
- backend 侧测试锚点：`CGenHelperTest`、`CCodegenTest`。

### 2.5 工具与类型事实

- 字符串参数解码：`LiteralExpression.sourceText()` 是原始源码 lexeme（含引号/前缀）。公共入口 `StringUtil.decodeGdStringLexeme(...)`（`util/StringUtil.java:106-115`）当前**只覆盖 `"..."` 与 `&"..."`**，不覆盖 §2.1 实测存在的单引号、三引号、raw 形式；`unescapeQuoted`（`StringUtil.java:151-190`）的转义集也缺 `\a` `\b` `\f` `\v` `\'`（Godot 4.5.1 tokenizer 的完整转义集为 `\a \b \f \n \r \t \v \' \" \\ \u \U`）。解码器扩展是 Step 3 的前置任务（§3.2）。
- `GdNodePathType`、`GdColorType` 均已在类型系统注册，`@export_node_path` / `@export_color_no_alpha` 具备类型前提。
- 现有消息模板风格：`@export cannot be used on static property '<name>'`、`@onready can only be used on class properties declared with 'var'`。

### 2.6 Godot 官方合同（`godotengine/godot` `modules/gdscript/gdscript_parser.cpp` 等，已按 `4.5.1-stable` 逐一核对注册签名与校验分支）

- `@tool` 注册为 `AnnotationInfo::SCRIPT`（`@icon` 同为 SCRIPT），**不是** CLASS：inner class / member 上的 `@tool` 在 Godot 中直接报错（"must be at the top of the script"）。gdcc 必须对齐，不得把 inner class `@tool` 做成合法 per-class 标记。
- 编辑器内抑制机制（4.5.1 源码链路）：编辑器编辑场景时 `ScriptServer::set_scripting_enabled(false)`；非 tool 脚本 `GDScript::can_instantiate()` 返回 false（`gdscript.cpp:255`）；`Object::set_script` 因此创建 `PlaceHolderScriptInstance` 而非真脚本实例（`object.cpp:1017-1022`）——非 `@tool` 类在编辑器内**不执行任何脚本回调**（`_ready` / `_process` / `_notification` 等全部）。同时 `GDScriptCompiler._prepare_compilation` 对 main class 与**全部 inner class** 都执行 `p_script->tool = parser->is_tool()`（`gdscript_compiler.cpp:2765,2987,3326`）：`@tool` 是 script 级语义，但会传播到该脚本编译出的每个 class。GDExtension 类不经过上述机制（无 placeholder、无 `is_tool` flag，虚函数总会被引擎调用），因此 gdcc 必须在生成代码中自行 gate（§3.6）。
- 注册签名（`MethodInfo` + 默认值 + vararg；min arity = 参数个数 − 默认值个数）：
    - `@export_range(float min, float max, float step, String extra_hints...)`，默认 `(1.0, "")` → ≥2 个数值参数，第 3 个数值可选，其后 0..N 个字符串。
    - `@export_enum(String names...)`、 `@export_flags(String names...)`：1 形参、无默认、vararg → **≥1 个字符串**（0 参非法）。
    - `@export_file(String filter...)` / `@export_file_path(String filter...)` / `@export_global_file(String filter...)` / `@export_node_path(String type...)` / `@export_exp_easing(String hints...)`：1 形参、默认 `""`、vararg → 0..N 个字符串。
    - `@export_multiline`：**0 形参、无 vararg** → 恰好 0 个参数（带 hint 的 vararg 是 4.5.1 之后版本才加入的，不得按新签名实现）。
    - `@export_placeholder(String placeholder)`：恰好 1 个字符串。
    - `@export_dir` / `@export_global_dir` / `@export_color_no_alpha` / `@export_flags_*`（layer 系列）：0 参数。
- 类型校验（4.5.1 实测分支）：
    - `export_enum`：自定义检查，仅允许 `int` / `String` / `Variant`（**不含 `StringName`**）。
    - `export_multiline`：自定义检查，允许 `String` / `Dictionary`。
    - 裸 `@export`：无类型标注且无 initializer → 硬错误（"type can't be inferred"）；类型无法推断 → 硬错误；Object 家族只接受 builtin / Resource / Node / enum（其余如裸 `RefCounted` 报 "Export type can only be built-in, a resource, a node, or an enum."）；导出 Node 类型时 owner class 必须派生自 `Node`（`gdscript_parser.cpp:4734-4737`）；Resource → `PROPERTY_HINT_RESOURCE_TYPE`、Node → `PROPERTY_HINT_NODE_TYPE`，hint_string/class_name 取 property 类型类名。
    - range/flags/file 家族/placeholder/color/node_path/exp_easing 等走模板默认检查：要求 builtin 类型匹配（int/float 声明类型互通），检查前先回退 initializer 推断类型；**回退后仍未定型的 `Variant` 才跳过该检查**（即 `@export_range(0,1) var x := "hi"` 非法、`@export_range(0,1) var x` 合法）。注意 Godot 此时会把 `export_info.type` 改写为注解模板默认（range → `float`）；gdcc 本期不改写导出类型（§5 第 7 条保留议题）。
    - declared 为 Variant 时 Godot 回退读 initializer 的推断类型（`gdscript_parser.cpp:4617-4621`）；`Array[T]` / packed array 做元素 peel（gdcc 本期不做 peel，§1.2）。
- Godot 对同一 property 上的第二个 export 注解报硬错误；对非 `@export_placeholder` 的空字符串参数报错。gdcc 对前者刻意不诊断（§3.3），对后者对齐（§3.2）。

---

## 3. 目标合同

### 3.1 LIR annotation value 编码合同

`LirPropertyDef.annotations` 维持 `Map<String, String>` 不变，不引入新的 LIR 模型类型。value 编码固定为 **Godot `hint_string` 原文**（不带引号、不含注解名前缀），空 hint_string 存 `""`（表顺序即 §3.5 的 backend 选择优先级顺序）：

| key | value 示例 | backend hint | property 类型约束（4.5.1） |
| --- | --- | --- | --- |
| `export_range` | `"0,20,0.5"` / `"0,100,1,or_greater"` | `PROPERTY_HINT_RANGE` | `int` / `float` |
| `export_enum` | `"Warrior,Mage"`（`"Name:value"` 原样透传） | `PROPERTY_HINT_ENUM` | `int` / `String` / `Variant` |
| `export_flags` | `"Fire,Water,Earth"` | `PROPERTY_HINT_FLAGS` | `int`（与 `float` 互通，§3.3 规则 4） |
| `export_flags_2d_render/2d_physics/2d_navigation/3d_render/3d_physics/3d_navigation/avoidance` | `""` | 对应 `PROPERTY_HINT_LAYERS_*` | `int`（与 `float` 互通，§3.3 规则 4） |
| `export_file` | `"*.png"` 或 `""` | `PROPERTY_HINT_FILE` | `String` |
| `export_file_path` | 同 `export_file` | `PROPERTY_HINT_FILE_PATH` | `String` |
| `export_dir` | `""` | `PROPERTY_HINT_DIR` | `String` |
| `export_global_file` | 同 `export_file` | `PROPERTY_HINT_GLOBAL_FILE` | `String` |
| `export_global_dir` | `""` | `PROPERTY_HINT_GLOBAL_DIR` | `String` |
| `export_multiline` | `""`（4.5.1 无参数） | `PROPERTY_HINT_MULTILINE_TEXT` | `String`（`Dictionary` 为刻意收窄，§5） |
| `export_placeholder` | `"Enter name..."` | `PROPERTY_HINT_PLACEHOLDER_TEXT` | `String` |
| `export_exp_easing` | `""` / `"attenuation"` / `"attenuation,positive_only"` | `PROPERTY_HINT_EXP_EASING` | `float`（与 `int` 互通，§3.3 规则 4） |
| `export_color_no_alpha` | `""` | `PROPERTY_HINT_COLOR_NO_ALPHA` | `Color` |
| `export_node_path` | `"Node2D,Sprite2D"`（0..N 个独立字符串参数 join） | `PROPERTY_HINT_NODE_PATH_VALID_TYPES` | `NodePath` |
| `export`（裸） | `""` | 类型派生（含 Object → Resource/Node hint，§3.5） | 可定型 + Object 家族限制（§3.3 规则 6/7） |

理由：XML 序列化格式（`<annotation key value/>`）与 LIR 模型零变更；Godot hint_string 本身即是引擎侧规范编码；backend 无需重新理解参数语义。future `@export_custom` / group 若需要结构化表达，再单独演进 LIR 模型。

### 3.2 参数编译期求值合同

- 本期只接受字面量参数：`int` / `float` / `String` 字面量，以及数值前的 unary `+` / `-`。常量引用、枚举成员、表达式求值一律视为非法参数，发 `sema.annotation_usage`。
- arity 合同严格按 §2.6 的 4.5.1 注册签名实现（`export_enum` / `export_flags` ≥1；`export_multiline` =0 等）。`@export_range` 的 extra hints 必须是第 4 个及以后的独立字符串字面量（`@export_range(0, 100, 1, "or_greater")`），编码时依次 append 进 hint_string。
- 会被 join 进列表型 hint_string 的字符串参数（enum / flags / node_path / file filter / range extra hints / exp_easing hints）不得包含 `,`，否则 hint_string 会被引擎错误切分；`@export_placeholder` 不受此限（其 hint_string 不是列表）。
- 非 `@export_placeholder` 的字符串参数不得为空串（Godot 对齐）。
- 字符串字面量解码以前置任务扩展 `StringUtil.decodeGdStringLexeme(...)` 后统一调用：覆盖 `"..."` / `'...'` / `"""..."""` / `'''...'''` / `r"..."` / `r'...'` / `r"""..."""` / `r'''...'''`；转义集补齐 Godot 4.5.1 全集（`\a \b \f \n \r \t \v \' \" \\ \u \U`），其中 **`\u` 恰好 4 位 hex、`\U` 恰好 6 位 hex**（Godot 4.5.1 tokenizer 实测；当前实现读 8 位，需修正），非法 hex / 未知转义 / 非法 Unicode 序列一律报错（Godot 对齐，不得静默吞掉反斜杠）；raw 形式按 Godot 语义仅对 `\<quote>` 与 `\\` 保留反斜杠，其余反斜杠原样保留。**解码抛出的 `IllegalArgumentException` 必须被 helper 捕获并转为 `MALFORMED`**，不得作为异常穿越 analyzer（恢复约定见 `frontend_rules.md:5-8`）。
- 数值取 `sourceText()` 文本，unary `+` / `-` 作用于数值字面量时合法。
- 数值编码采用最短无损格式：整数值渲染为 `0`、`20`，浮点保留必要小数（`0.5`）。hint_string 只 join **显式写出的参数**（Godot 4.5.1 `resolve_annotation` 不填充默认值）：`@export_range(0, 100)` 编码为 `"0,100"` 而不是 `"0,100,1"`；extra hints 仍必须从第 4 个参数开始（`@export_range(0, 100, "or_greater")` 因第 3 形参是 float 而非法）。
- 同一套“结构解析 + 求值 + hint_string 编码”逻辑必须收口在单个共享 helper（新增 `FrontendExportAnnotationSupport`，放 `frontend/sema/analyzer/support`），skeleton 映射与 usage 校验都消费它，禁止两处各写一份规则。
- helper 输出三态结果：`SUPPORTED(hintKey, hintStringValue)` / `MALFORMED(reason)` / `NOT_EXPORT_FAMILY`。

### 3.3 诊断 owner 与消息模板

- skeleton（`applyPropertyAnnotations`）行为调整：
    - `export` / `onready` / 本期 export 家族且参数 well-formed → 写 property annotation（value 按 §3.1 编码）。
    - 本期 export 家族但参数 malformed → **只 retention 不写 metadata，不发诊断**（参数诊断归 usage analyzer，见 `diagnostic_manager.md:336-340` 的 owner 边界；不违反 skeleton 规则）。
    - `tool` / `icon` 等 class 级注解即使被 collector 挂到 member 或尾随锚点上，skeleton 也只做 retention，**不发** `sema.unsupported_annotation`；`tool` 的 placement 诊断统一归 usage analyzer（单一 owner）。`@icon` 本期连 placement 校验也不做（§1.2）。
    - 其余 property 注解 → 维持 `sema.unsupported_annotation` 现状。
- `FrontendAnnotationUsageAnalyzer` 新增校验（全部 `sema.annotation_usage` error，锚定注解 range）：

| 规则 | 消息模板 |
| --- | --- |
| export 家族挂在非 class-property（function、local var、signal、class 等） | `@<name> can only be used on class properties declared with 'var'` |
| export 家族 × static property | `@<name> cannot be used on static property '<prop>'` |
| 参数个数不符 | `@<name> expects <spec>, but got <n> argument(s)`（`<spec>` 如 `at least 1 argument` / `exactly 1 argument` / `no arguments`） |
| 参数非字面量 / 类型不符 / 解码失败 | `@<name> argument <i> must be a <expected> literal` |
| 列表型字符串参数含 `,` 或为空串 | `@<name> argument <i> must not contain ','` / `@<name> argument <i> must not be empty` |
| property 类型不兼容 | `@<name> can only be used on properties of type <types>, but '<prop>' is <type>` |
| 裸 `@export` 无法定型 | `@export requires a determinable property type, but '<prop>' has neither a type annotation nor an inferable initializer` |
| 裸 `@export` 非可导出 Object 家族 | `@export can only export built-in, Resource, Node, or enum types, but '<prop>' is <type>` |
| 裸 `@export` Node 类型但 owner 非 Node 派生 | `@export of Node type is only supported in Node-derived classes, but '<prop>' is declared in '<owner>'` |
| `@tool` 挂在非顶层位置（inner class、member、尾随 dangling；Godot 对齐） | `@tool can only be used at the top of the script, before "extends" and "class_name"` |
| `@tool` 带参数 | `@tool does not accept any arguments` |

- 类型事实源规则（替代单纯的 skeleton 声明类型）：
    1. property 声明了非 `Variant` 显式类型 → 以 `LirPropertyDef.getType()` 为准。
    2. 声明为 `Variant`（未标注或 `:=`）→ 读 initializer 根表达式已发布的稳定 `expressionTypes()` 事实（本 analyzer 运行在 expr typing 之后）；得到具体类型则按该类型校验（含 Object 家族判定）。
    3. 仍无法确定 → 裸 `@export` 发 `sema.annotation_usage`（Godot 4.5.1 对齐）；`export_multiline` 发 `sema.annotation_usage`（Godot 自定义检查不含 Variant，且 gdcc 已收窄掉 Dictionary）；`export_enum` 与走默认检查的其余 variant（range / flags / layer flags / file 家族 / dir / global / placeholder / color / node_path / exp_easing）放行（Godot 默认类型检查对仍未定型的 `Variant` 跳过；backend 按 `Variant` property 渲染对应 hint）。**上游抑制**：initializer 子树已存在上游阻断性诊断（binding/expression resolution error 等）时，不再补发 determinability 诊断，避免同一根源双 owner 报错（`frontend_rules.md:27`）。
    4. 默认检查族（range / flags / layer flags / exp_easing）的 `int` 与 `float` 声明类型互通（Godot 4.5.1 对齐）：`@export_flags("A") var x: float`、`@export_exp_easing var x: int` 均合法。
    5. 声明类型为容器（`Array[T]` / packed array / `Dictionary`）时，**§3.1 表中全部 export variant**（含 `export_enum` / `export_multiline` / layer flags）一律报类型不兼容（§1.2 的 peel 收窄）；裸 `export` 不受限。
    6. 裸 `@export` 的 Object 家族限制（有效类型按规则 1/2 判定后）：`Resource` 派生或 `Node` 派生放行；其余 Object 类型（如裸 `RefCounted`）报错；builtin / `Variant` 不受此限。脚本枚举类型本期尚未进入 MVP，enum 导出不实施。
    7. 裸 `@export` 导出 `Node` 派生类型时，owner class 必须派生自 `Node`（复用 analyzer 现有 `isNodeDerived` 模式），否则报错。
- usage analyzer 对 side-table 中**每一处** `tool` 注解（无论挂在 `SourceFile`、inner `ClassDeclaration`、member 还是尾随的 `AnnotationStatement` 自身上）做 placement/arity 校验；仅 `annotationsByAst()[SourceFile]` 上的零参 `@tool` 合法。当前遍历不经过 `SourceFile` 自身，需补该校验入口。同一节点同时违反多条 `@tool` 规则时，至少发出一条 placement error，不承诺恰好一条。
- static 检查从精确名 `"export"` 扩展为整个 export 家族（含裸 `export`），消息中的 `@<name>` 用实际注解名。
- 同一 property 携带多个 export 家族注解（如 `@export` + `@export_range`）**不发诊断**（与 Godot 硬错误的刻意差异，记录在案）；**同名 variant 重复出现冻结为 last-wins**（`Map.put` 自然语义，如两个 `@export_range` 后者覆盖前者）；不同 key 时 backend 按 §3.5 的全序选择渲染。
- compile-only gate 不新增注解扫描：`sema.annotation_usage` error 已足以经 `hasErrors()` 阻断 lowering/backend；compile gate 不得把既有注解诊断重复包装成 `sema.compile_check`。

### 3.4 `@tool` skeleton 映射合同（Godot 对齐：script 级）

- 只有挂在顶层 `SourceFile` 上的**零参** `tool` 被消费：`LirClassDef.isTool` 的语义固定为 **script 级 flag**，落地方式与 Godot 对齐——该 `SourceFile` 对应 module 中的顶层 `LirClassDef` 与**全部 inner class `LirClassDef`** 都 `setTool(true)`（Godot `_prepare_compilation` 对每个编译出的 class 都执行 `p_script->tool = parser->is_tool()`），顶层 class 额外 `setAnnotation("tool", "")`。
- 带参数的 `@tool(...)`、inner class 上的 `@tool`、member/尾随锚点上的 `@tool` 一律不写 `isTool`，仅 retention + usage 诊断。同一 `SourceFile` 上合法与非法 `@tool` 并存时，合法实例仍按上一条传播置位。
- 查询方式：对顶层关系直接用 `annotationsByAst()[SourceFile]`；跨 owner 的通用查询用 `FrontendSourceClassRelation.findRelation(node)`（identity 匹配）。不得假设 `FrontendSourceClassRelation.astOwner()` 会返回 inner 节点。
- 映射发生在 `createClassShell(...)` 之后、成员填充之前的同一 skeleton phase 内。
- 重复零参 `@tool` 不诊断，幂等置位（与 Godot DEBUG 警告的刻意差异，列入已知限制）。
- `@icon` 本期只 retention：不消费、不写 metadata、不做 placement 校验；误挂到 member 时 skeleton 也不发 `sema.unsupported_annotation`（与 `tool` 同走 retention-only 路径）。

### 3.5 backend 渲染合同

- `renderPropertyMetadata` / `renderPropertyBaseUsageEnum` 扩展为：
    1. 按 **§3.1 表自上而下的固定全序**查找 export variant key（`export_range` 最前，裸 `export` 垫底），命中第一个 variant 则 hint/hint_string 完全由该注解 value 驱动；不得依赖 `Map` 迭代顺序；
    2. 仅命中裸 `export` 时，维持并扩展现有类型派生 hint 行为：typed Array/Dictionary hint 不变；**`GdObjectType` 经 `context.classRegistry()` 判定 Resource 派生 → `PROPERTY_HINT_RESOURCE_TYPE`、Node 派生 → `PROPERTY_HINT_NODE_TYPE`，hint_string 取 property 类型类名**；
    3. **任一 export 家族 key（含裸 `export` 与所有 variant）存在 → base usage 为 `godot_PROPERTY_USAGE_DEFAULT`**；都无则 `NO_EDITOR`；
    4. `Variant` 类型的 `| godot_PROPERTY_USAGE_NIL_IS_VARIANT` 叠加规则维持现状。
- `entry.c.ftl` 的 property 注册改为传 `${propertyMetadata.classNameExpr}`，不再把 owner 类名固定填入 property class_name 槽；`renderBoundMetadata` 对 Object 类型导出填充 `classNameExpr`（property 类型类名），其余维持空串默认。
- backend 不重复校验参数合法性（frontend compile 路径保证 diagnostics 无 error 才进入 lowering/backend）；遇到未知 key 静默忽略，不新增 backend 诊断面。

### 3.6 `@tool` backend 帧循环 gate 合同

- 目标语义：`isTool==false` 的类在编辑器内（`Engine.is_editor_hint()` 为 true）不执行帧循环虚函数 `_process` / `_physics_process`；`isTool==true` 的类（含 `@tool` 文件经 script 级传播置位的 inner class）在编辑器内照常执行；运行时（非编辑器）两类都照常执行。
- gate 插入点：生成的 per-class `<Class>_class_call_virtual_with_data(...)`（`entry.c.ftl:357-372`）中，`_process` / `_physics_process` 的 **userdata 匹配分支内部、`ptrcall...` 调用之前**（不得放在函数入口统一拦截，否则会误闸 `_ready` 等非帧循环虚函数；分支按 `function.name` 的 userdata 匹配定位；gate 直接 `return`，不写 `r_ret`——两者均为 void 虚函数，等价于空实现，安全）。
- gate 条件在**编译期**按 `LirClassDef.isTool` 决定：tool 类不生成任何检查（零开销）；非 tool 类在这两个虚函数的分派分支内生成 `if (gdcc_is_editor_hint()) { return; }`。
- 运行时检测复用现有 `gdcc_is_editor_hint()`（`include_451/gdcc/gdcc_helper.h:76`；`godot_Engine_singleton()` 由 `gdcc_init()` 在 initialize 时缓存），不得新增引擎访问通道，也不得再包 lazy init。
- 隐藏 coroutine state class（`is_runtime=true`、非脚本暴露）不涉及帧循环虚函数，不生成 gate。
- 非帧循环虚函数（`_ready` / `_notification` / `_input` 等）本期**不 gate**：Godot 的 placeholder 机制会抑制全部脚本回调，gdcc 只对其中的帧循环部分对齐（用户明确范围），其余差异记录在 §5。

---

## 4. 分步骤实施

每一步都必须可独立编译、可独立回归、可独立提交。**backend 渲染（Step 2）必须先于 frontend 启用（Step 3）**：否则 frontend 接受新注解后、backend 仍按 `NO_EDITOR` 渲染，会产生可编译但错误的中间提交。

### Step 1：`@tool` frontend 贯通

改动点：

- `FrontendAnnotationCollector` 挂载算法修订：
    - trivia 透明化：`CommentStatement` 与**顶层前导的字符串字面量 `ExpressionStatement`**（docstring，实测映射为 `ExpressionStatement(LiteralExpression(kind="string"))`）不触发 pending flush，也不把 `atListStart` 置 false；不得泛化为任意表达式语句。
    - flush 时**按单个注解分类**（替代批次 `allMatch`），并按源码顺序维护 `ownerPreambleOpen` 状态（初值 = `atListStart`）：`ownerPreambleOpen` 且 script-scoped（`tool` / `icon`）→ 挂 owner；否则挂紧随的 statement。关闭条件按注解目标角色区分（Godot 对齐：`parse_program` 中 class/root 兼容注解进入待决栈，不终结 script 注解识别）：
        - **member-target 注解**（export 家族、`onready`、`rpc`）→ 挂紧随的 statement，并**立即关闭** `ownerPreambleOpen`（`@export` 之后再写 `@tool` 非法）；
        - **其余注解**（`abstract`、`warning_ignore`、未知注解等 class/root 兼容或未识别者）→ 挂紧随的 statement，但**不关闭** `ownerPreambleOpen`（`@abstract` / `@warning_ignore` 之后的 `@tool` 仍合法）。
    - list 结束补 flush：`ownerPreambleOpen`（list 仅含 owner-scoped 注解/trivia）时挂 owner；否则**挂到每个尾随注解自身的 `AnnotationStatement`**，作为 usage placement error 的稳定锚点。**禁止**把成员之后的尾随 `@tool` flush 到 `SourceFile`（会误置 `isTool`），也禁止挂到前一个 statement（会被 skeleton 当成合法 property 注解消费）。
- `FrontendClassSkeletonBuilder`：全部 class shell 创建后，按 `annotationsByAst()[SourceFile]` 的零参 `tool` 一次性传播置位——该 `SourceFile` 的顶层与全部 inner `LirClassDef` 都 `setTool(true)`，仅顶层额外 `setAnnotation("tool", "")`（§3.4）；inner/member/尾随/带参 `@tool` 不在此处置位。`tool` / `icon` 从 property unsupported 路径排除（误挂 member/锚点时只 retention）。
- `FrontendAnnotationUsageAnalyzer`：补 `SourceFile` 容器节点自身的校验入口；实现 `@tool` placement（仅顶层）与零参数校验。
- `DomLirParser`：修复 class 级 annotation 递归读取 bug，只读 `class_def` 的直接子 `annotation` 元素。
- 测试：`FrontendClassSkeletonAnnotationTest`、`FrontendAnnotationUsageAnalyzerTest`、`DomLirSerializerTest` / `DomLirParserTest`。

验收：

- `@tool class_name A extends Node` 的 module skeleton 中顶层 `isTool()==true` 且 `hasAnnotation("tool")`；XML roundtrip 后保持一致（含带 property annotation 的 class 不被 class 级读回污染）。**同一 `@tool` 文件中的 inner class 同样 `isTool()==true`**（script 级传播，Godot 对齐）。
- 前导 trivia 透明：`# comment` 或 `"""script documentation"""` 之后的顶层 `@tool` 仍置位。
- 混合批次拆分：`@tool` + `@export var hp: int` 中 `tool` 挂 `SourceFile`（置位、无诊断），`export` 挂 property。
- 顺序边界：`@export` 之后再写 `@tool`（如 `@export\n@tool\nvar hp: int`，或 list 仅含 `@export` + `@tool`）时 `@tool` 报 placement error 且不置位；`@tool` 在前 `@export` 在后保持合法；class/root 兼容注解不关闭前导区——`@abstract\n@tool\nextends Node` 与 `@warning_ignore("unused_variable")\n@tool\nextends Node` 中 `@tool` 仍合法置位。
- inner class 上的 `@tool`、非 list 开头 member 上的 `@tool`、list 末尾尾随 `@tool` 各发 `sema.annotation_usage`，且不因该非法注解置位任何 `isTool`；同一 module 的合法 member 继续正常 skeleton。
- `@tool(1)` 至少报一条诊断且不置位；合法 `@tool` 与 `@tool(1)` 并存时仍置位。
- `script/run-gradle-targeted-tests.sh --tests FrontendClassSkeletonAnnotationTest,FrontendAnnotationUsageAnalyzerTest,DomLirSerializerTest,DomLirParserTest` 通过。

### Step 2：backend hint 渲染与 `@tool` 帧循环 gate（含 Object 导出与模板修正）

改动点：

- `CGenHelper.renderPropertyMetadata` / `renderPropertyBaseUsageEnum` 按 §3.5 重写（含显式全序选 key、Object → Resource/Node hint 分支、`classNameExpr` 填充）；hint_string 经现有 `GD_STATIC_S(u8"...")` + `escapeStringLiteral` 通道输出。
- `entry.c.ftl` property 注册改传 `${propertyMetadata.classNameExpr}`。
- `entry.c.ftl` 的 `<Class>_class_call_virtual_with_data` 按 §3.6 生成帧循环 gate：非 tool 类在 `_process` / `_physics_process` 的 **userdata 匹配分支内部、`ptrcall...` 之前**生成 `if (gdcc_is_editor_hint()) { return; }`（不得放函数入口或分支外）；tool 类不生成检查。

验收：

- `CGenHelperTest` 新增：每个 variant key 的 hint 枚举字面量、hint_string 渲染断言，以及 **usage 必须为 `PROPERTY_USAGE_DEFAULT` 而非 `NO_EDITOR`** 的断言；未定型 Variant + variant 的完整形态（如 `export_range -> "0,1"` 的 property → type=`NIL`、usage=`DEFAULT | NIL_IS_VARIANT`、hint=`RANGE`、hint_string=`"0,1"`）；裸 `export` + typed `Array[int]` 仍渲染 `PROPERTY_HINT_ARRAY_TYPE`（回归）；`@export` + `@export_range` 共存时 `export_range` 优先；`@export_range` + `@export_enum` 共存时按 §3.1 表序 `export_range` 优先（确定性，不依赖 `HashMap` 迭代）；裸 `export` + `Texture2D` → `RESOURCE_TYPE` 且 hint_string/class_name 为 `Texture2D`；裸 `export` + `Node2D` → `NODE_TYPE`。
- `CCodegenTest` 层面断言 `gdcc_bind_property_full` 调用携带新 hint 参数且 class_name 槽来自 `propertyMetadata.classNameExpr`；gate 代码生成用**有序断言**锁住位置：非 tool 类 dispatch body 中 `_process` / `_physics_process` 各自满足 `userdata 匹配 → if (gdcc_is_editor_hint()) { return; } → ptrcall...` 顺序（userdata 以 `function.name` 定位，带默认槽时比较符号为 default userdata 实例）；`_ready` 分支含 `ptrcall` 且该分支内不得出现 `gdcc_is_editor_hint`；tool 类（含 `@tool` 文件的 inner class）整个 dispatch body 不得出现 `gdcc_is_editor_hint`。编辑器内实际抑制行为依赖引擎环境，不进 focused test。
- `script/run-gradle-targeted-tests.sh --tests CGenHelperTest,CCodegenTest` 通过。

### Step 3：export 家族参数求值 helper + skeleton 映射 + usage 校验（同一提交）

> helper、skeleton 映射与 usage 校验必须同一提交：若只落地 skeleton 映射，malformed 参数会从当前的 `sema.unsupported_annotation` 退化为静默，制造诊断黑洞。

改动点：

- 前置：扩展 `StringUtil.decodeGdStringLexeme(...)` 覆盖 §3.2 的字符串形式与转义全集（保持既有 `"..."` / `&"..."` 行为不变）。
- 新增 `FrontendExportAnnotationSupport`（`frontend/sema/analyzer/support`）：注解名分类、arity/字面量/逗号/空串/解码结构校验、hint_string 编码（§3.1/§3.2 的唯一实现），三态结果。
- `applyPropertyAnnotations` 按 §3.3 重写分流。
- `FrontendAnnotationUsageAnalyzer` 按 §3.3 表实现 export 家族 placement / static / arity / 字面量 / 类型兼容 / Object 家族校验；类型事实源按 §3.3 的七级规则（含 Variant → initializer `expressionTypes()` 回退、无法定型差异行为与上游抑制）。

验收（negative path 必须覆盖：正确 category、坏 subtree 不影响同 module 其它 subtree，见 `frontend_rules.md:58-62`）：

- `@export_range(0, 20, 0.5) var x: float` → property annotation 为 `export_range -> "0,20,0.5"`，无诊断。
- `@export_range(0, 1) var x := 0.5`（Variant + initializer 回退）合法；`@export_range(0, 1) var x := "hi"`（回退类型 String 不兼容）报 `sema.annotation_usage`；`@export_range(0, 1) var x`（合法 arity、无法定型）放行且 hint_string 为 `"0,1"`（不补默认 step），且 `LirPropertyDef.getType()` 仍为 `Variant`（不得改写为 `float`）；`@export_placeholder("x") var x`（无法定型）放行；裸 `@export var x`（无法定型）与 `@export_multiline var x`（无法定型）报 `sema.annotation_usage`。
- 字符串形式：`@export_enum('A', 'B')`、`@export_file(r"res://a.txt")`、raw 三引号参数解码正确；`\t` 等转义解码正确；`\U01F600`（6 位）解码正确；`\q` 未知转义与非法 hex 转 `sema.annotation_usage` 而非异常。
- 每条规则至少一个 positive + 一个 negative 用例：arity（`@export_range(0)`、`@export_enum`、`@export_multiline("x")`）、非字面量参数（`@export_range(MIN, 1)`）、逗号（`@export_enum("A,B")`）、空串（`@export_file("")` 报错 vs `@export_placeholder("")` 放行）、static（`@export_range(0,1) static var x: int`，消息含 `export_range`）、local var、function 挂载、int/float 互通正例（`@export_flags("A") var x: float`、`@export_exp_easing var x: int`）、非容器类型不兼容（`@export_range(0, 1) var x: String`）、容器收窄（`@export_range(0,1) var xs: Array[int]`、`@export_enum("A") var xs: Array[int]`、`@export_multiline var d: Dictionary` 均报错）。
- Object 家族：`@export var tex: Texture2D`（Resource）与 `@export var n: Node2D`（owner 为 Node 派生）合法；`@export var r: RefCounted` 报 `sema.annotation_usage`；非 Node owner 中的 `@export var n: Node2D` 报 `sema.annotation_usage`。
- 上游抑制：`@export var x = missing_symbol` 只保留上游 binding/expression 诊断，不追加 determinability 诊断。
- 同名重复：两个 `@export_range` 时 last-wins，不发诊断。
- 未知 property 注解仍发 `sema.unsupported_annotation`（回归不破）。
- `script/run-gradle-targeted-tests.sh --tests FrontendAnnotationUsageAnalyzerTest,FrontendClassSkeletonAnnotationTest,FrontendAnnotationParseBehaviorTest,FrontendSemanticAnalyzerFrameworkTest,FrontendCompileCheckAnalyzerTest,StringUtilTest` 通过（`StringUtilTest` 现存，其中沿用 8 位 `\UXXXXXXXX` 的旧断言需随 Godot 6 位合同一并修正）。

### Step 4：文档同步

- `diagnostic_manager.md`：更新 skeleton annotation 行为段落（320-340 行附近）与 category 表，明确 export 家族已从 `sema.unsupported_annotation` 移入支持面。
- `frontend_rules.md`：MVP 约定补充 `@tool` / `@export*` 合同条目。
- `gdcc_low_ir.md`：annotation 段补充 value = hint_string 编码说明。
- 本文档状态从“计划待实施”转为“事实源”。

验收：上述文档与代码行为一致；全量 `./gradlew clean build --no-daemon --info --console=plain` 通过。

---

## 5. 风险与已知限制

1. **gdparser 依赖**：参数 AST 形态（`LiteralExpression(kind, sourceText)`、负数为一元表达式、字符串 lexeme 形式集）依赖 gdparser 0.5.3；升级依赖时必须回归 Step 1/Step 3 测试。
2. **Map 编码上限**：`Map<String, String>` + hint_string 编码无法表达 group 顺序、容器元素 hint 包装（`PROPERTY_HINT_TYPE_STRING`）与自定义结构化 metadata；这是刻意选择，§1.2 已把相关能力排除在本期之外。
3. **`@tool` 的 backend 效果仅限帧循环 gate**：GDExtension 注册面无 tool flag（Godot 对非 tool 脚本的完整抑制靠 `PlaceHolderScriptInstance`），gdcc 本期只在生成的 `call_virtual_with_data` 中对 `_process` / `_physics_process` 做 editor-hint gate（§3.6）。用户可见效果：非 `@tool` 类在编辑器内不执行帧循环，其余回调（`_ready` / `_notification` 等）在编辑器内仍会执行（与 Godot 的差异，见第 4 条）。这一点需要在后续 release note / 用户文档中显式说明。
4. **与 Godot 4.5.1 的刻意差异**（均已记录在 §3.3/§3.4，不得在实现中“悄悄对齐”）：
    - 同 property 多 export 注解：Godot 硬错误，gdcc 不诊断；同名 variant last-wins，不同 key 时 backend 按 §3.1 表序取第一个 variant。
    - 重复 `@tool`：Godot DEBUG 警告，gdcc 幂等不诊断。
    - 非 tool 类的编辑器内回调：Godot 经 placeholder 机制抑制**全部**脚本回调（`_ready` / `_notification` / `_input` 等），gdcc 本期只 gate 帧循环（`_process` / `_physics_process`），非 tool 类在编辑器内仍会执行其余回调。
    - 容器声明类型上的 export variant（Array peel）：Godot 允许并 peel 元素类型，gdcc 本期一律报 `sema.annotation_usage`。
    - `@export_multiline` 在 Godot 4.5.1 允许 `String` / `Dictionary`，gdcc 本期只允许 `String`。
    - 注解参数只接受字面量（含数值 unary `+/-`）：Godot 接受任意常量表达式（`is_constant`），gdcc 本期对常量引用 / 枚举成员 / 运算表达式一律发 `sema.annotation_usage`。
    - `@export_node_path` 的类名存在性与 `Node` 派生检查（Godot 查 ClassDB）：gdcc 本期不做，列为后续增强。
    - `@export_flags` 的条目名校验、显式整数值（`"Name:2"`）与 32 位上限检查（Godot 在 `export_annotations` 内）：gdcc 本期不做，列为后续增强。
5. **Godot 版本基线**：本期合同以 `4.5.1-stable` 为准（与 `template_451` / `include_451` 对齐）；`@export_multiline` 的 hint 参数等新版本行为不得提前引入。
6. **`@icon` 与未知 class 级注解**：维持静默 retention，不发 `sema.unsupported_annotation`、不做 placement 校验（与现状一致）；是否补齐 class 级 unsupported 边界留待后续单独决策。
7. **【已确认·接受差异】initializer 推断类型不进入导出 metadata**：Godot 会把 initializer 推断类型写入 `export_info.type`（如 `@export var count = 1` 导出为 `INT`，`@export var tex = preload(...)` 导出为 `OBJECT + RESOURCE_TYPE`）。gdcc 已确认接受的行為：显式标注类型时导出类型=声明类型（如 `@export var count: int = 1` 导出 `INT`）；未标注/`:=` 时保持 `Variant` + `NIL_IS_VARIANT`，类型回退只用于诊断。若未来需要对齐 Godot 的推断导出，需立项引入结构化 export metadata（导出 type / hint / hint_string / class_name / usage）并贯通 LIR 模型、XML serializer/parser 与 backend——`Map<String, String>` 无法承载，属架构级变更。
