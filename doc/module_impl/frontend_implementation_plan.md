# Frontend（AST 语义分析 + LIR 生成）实施计划

> 本文档定义 GDCC 前端模块的目标架构与分步骤落地计划。  
> 重点：在 **AST 级别**完成足够的语义分析（类型、调用点决议、符号绑定、诊断），并提供一套稳定 API 将 AST 下降为 `Low IR (LIR)`，供现有 `backend.c` 直接消费。

## 文档状态

- 状态：In Progress（M0-M1 Completed）
- 更新时间：2026-03-05
- 里程碑进度：
  - [x] M0：依赖接入与最小可编译骨架
  - [x] M1：模块扫描与 Class Skeleton 构建
  - [ ] M2-M7：未开始
- 适用范围：
  - 未来新增包：`src/main/java/dev/superice/gdcc/frontend/**`
  - 依赖 AST：`SuperIceCN/gdparser`（本地副本：`E:/Projects/gdparser`）
  - 输出 IR：`src/main/java/dev/superice/gdcc/lir/**`
- 关联事实源：
  - LIR 设计：`doc/gdcc_low_ir.md`
  - 类型系统：`doc/gdcc_type_system.md`
  - 生命周期/所有权：`doc/gdcc_ownership_lifecycle_spec.md`
  - 后端生成器模式参考：
    - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/ConstructInsnGen.java`
    - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/MethodCallResolver.java`
    - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
    - `doc/module_impl/call_method_implementation.md`
    - `doc/module_impl/load_store_property_implementation.md`
    - `doc/module_impl/index_insn_implementation.md`
    - `doc/module_impl/operator_insn_implementation.md`
- Godot 参考实现（仅用于语义/阶段划分对齐）：
  - `godotengine/godot`：`modules/gdscript/gdscript_parser.*`、`modules/gdscript/gdscript_analyzer.*`
  - `godotengine/godot-docs`：`tutorials/scripting/gdscript/static_typing.rst`

---

## 0. 已确认工程策略（2026-03-04）

1. 已知对象类型的未知属性/方法：**发出 warning 并允许动态回退**（而不是 fail-fast）。
2. `LIR <class_def name="...">`：**当前阶段不允许空**；无 `class_name` 时必须从源文件名派生稳定类名。
3. `null` 在无期望类型时：默认下降为 `literal_nil`（Variant Nil），而不是 `literal_null`（Object null）。
4. 第一阶段不支持 `preload("...")` 作为类型来源；仅支持“用户给定的源文件集合”与内置/引擎/本模块类型元数据。

---

## 1. 背景与目标

### 1.1 我们要解决什么

GDCC 当前已有：

- **类型系统**（`dev.superice.gdcc.type`）
- **作用域/元数据模型**（`dev.superice.gdcc.scope` + `dev.superice.gdcc.gdextension` + `ClassRegistry`）
- **Low IR (LIR)** 结构与解析/序列化（`dev.superice.gdcc.lir`）
- **C 后端**（`dev.superice.gdcc.backend.c`）可从 LIR 生成 C 并编译

但缺少 “从 GDScript 源码到 LIR” 的前端。用户计划使用 `gdparser` 解析 GDScript，因此前端需要：

1. **AST 语义分析**：为每个表达式建立类型、为调用建立明确的候选决议（或明确声明动态回退原因）、为标识符建立绑定（局部/参数/成员/全局/类型名/单例等）。
2. **诊断系统**：统一来自 `gdparser` 的 lowering diagnostics 与 GDCC 语义诊断（错误/警告），并具备可定位的源代码 `Range`。
3. **AST -> LIR 下降 API**：提供稳定 API 遍历 AST 并生成 LIR（类/信号/属性/函数/基本块/指令），让后端可以直接消费。

### 1.2 “足够多的信息”的硬性清单（验收驱动）

前端语义分析结果必须至少能回答：

1. **每个 Expression 的 `GdType`**（允许 `Variant` 作为保守兜底，但必须可解释来源）
2. **每个 Call site 的决议信息**：
   - global / method / static / ctor / lambda / dynamic fallback
   - 若能静态决议：owner（类/内建类型）、被选中的签名、默认参数补全策略
   - 若不能静态决议：为什么不能（元数据缺失/歧义/Variant receiver 等）
3. **每个 Identifier 的绑定来源**：
   - local variable / parameter / capture / class property / class constant / singleton / global enum / type meta
4. **所有字段/参数/返回值的类型来源**：
   - 显式注解、`:=` 推断、赋值推断、还是降级为 `Variant`

---

## 2. 总体架构（分层 + 分阶段）

参考 Godot 的 `GDScriptAnalyzer`（inheritance / interface / body 三阶段），GDCC 前端建议采取同样的 **多阶段、可缓存、可部分失败** 的结构。

### 2.1 分层概览

```text
Source text
  -> (gdparser) CST -> AST + lowering diagnostics
  -> (GDCC frontend.sema) 语义分析：符号表 + 类型 + 调用决议 + 诊断
  -> (GDCC frontend.lir) AST 下降为 LIR（Low IR）
  -> (backend.c) LIR -> C -> zig 编译
```

建议引入三个主要子包（命名可调整，但职责边界不建议混淆）：

1. `dev.superice.gdcc.frontend.parse`
   - 封装 `gdparser`（`GdParserFacade` + `CstToAstMapper`）
   - 负责 “从源码到 AST + parse/lowering 诊断”
2. `dev.superice.gdcc.frontend.sema`
   - 负责 **语义分析**（symbol binding、type inference、call resolution、diagnostics）
   - 输出 `AnalysisResult`
3. `dev.superice.gdcc.frontend.lir`
   - 负责 **LIR 生成**（基于 `AnalysisResult` 的 typed AST）
   - 输出 `LirModule`

### 2.2 核心数据结构（建议）

> AST 来自 `gdparser`，不可在 node 上直接挂字段，因此使用 side-table（`IdentityHashMap`）存储语义信息。

- `FrontendSourceUnit`
  - `Path path`
  - `String source`
  - `dev.superice.gdparser.frontend.ast.SourceFile ast`
  - `List<AstDiagnostic> parseDiagnostics`
- `FrontendModule`
  - `String moduleName`
  - `List<FrontendSourceUnit> units`
  - `ClassRegistry classRegistry`（engine/builtin/utility metadata + 后续注入 gdcc classes）
- `AnalysisResult`
  - `List<FrontendDiagnostic> diagnostics`
  - `IdentityHashMap<Expression, GdType> expressionTypes`
  - `IdentityHashMap<Node, ResolvedCall>`（对 `CallExpression` 与 attribute call step）
  - `IdentityHashMap<IdentifierExpression, ResolvedSymbol> symbols`
  - `ClassModel`（本模块声明的 class/property/function skeletons）

### 2.3 语义分析分阶段（与 Godot 对齐）

1. **Parse**：CST -> AST（由 gdparser 完成），收集 lowering diagnostics（结构错误、未知节点等）
2. **Resolve Inheritance**：解析 `class_name`、`extends`，补全匿名类名策略，检查继承环
3. **Resolve Interface**：只处理 “签名级信息”
   - properties / signals / const / functions：收集声明与显式类型
   - 将本模块的 class skeleton 注入 `ClassRegistry`（使跨脚本引用可见）
4. **Resolve Body**：处理 “表达式级信息”
   - 构建函数体作用域树
   - 类型推断（含控制流合流）
   - 调用点决议（含 overload 选择、默认参数补全规则）
   - 形成可用于 LIR 下降的最终 typed view

---

## 3. 语义分析设计细则

### 3.1 名称绑定（Identifier Binding）

GDCC 前端建议采用 **显式的 BindingKind**（类似后端 resolver 的 “dispatch mode” 分层），避免把 “字符串名字” 当成唯一真相：

- `LOCAL_VAR`
- `PARAMETER`
- `CAPTURE`（lambda）
- `PROPERTY`（instance/static）
- `CONSTANT`（const）
- `SINGLETON`（来自 `ClassRegistry.findSingletonType`）
- `GLOBAL_ENUM`（来自 `ClassRegistry.findGlobalEnum`）
- `UTILITY_FUNCTION`（来自 `ClassRegistry.findUtilityFunctionSignature`）
- `TYPE_META`（用于 `ClassName` / preload class / enum type）
- `UNKNOWN`（保留：只在 tolerant/compat 模式允许）

绑定规则（优先级建议）：

1. 局部作用域（local var / for iterator / match bind 等）
2. 参数（含 `self`）
3. capture（lambda）
4. 当前类成员（property / const / function name 作为 callable 的策略可延后）
5. 单例 / utility / global enum
6. 类型名（builtin / engine / gdcc class）

### 3.2 类型推断（Type Inference）

目标不是实现完整的 Godot 静态类型系统，而是达到：

- 能为 LIR 选择正确指令形态（construct/load/store/call/operator/indexing）
- 能在编译期 fail-fast 明确的类型错误
- 在不确定时 **可控降级** 到 `Variant`，并记录诊断或 “dynamic fallback reason”

关键输入来源：

- 显式 type hint（`TypeRef`）
- `:=` 推断（`var x := expr` / `const X := expr`）
- 字面量类型
- 调用签名（utility/builtin/engine/gdcc）
- 赋值约束（`x = expr`）
- 控制流合流（if/while/return）

建议引入 `TypeConstraint` 的最小实现（不要求完整求解器，但要可扩展）：

- `Exact(GdType)`
- `AssignableTo(GdType)`
- `Union(List<GdType>)`（最终可降级成 `Variant`）

控制流合流策略（MVP）：

- 两条分支推断到同一类型：保持该类型
- 两条分支互相可赋值：选择更具体的一侧（或按“目标类型已知”取目标）
- 不兼容：降级 `Variant` 并发 warning（或 strict 模式 error）

### 3.3 调用点决议（Call Resolution）

GDCC 的 LIR 指令集只有 `call_global` / `call_method` / `call_static_method` / `call_super_method` / `call_intrinsic`，并且后端 resolver 已实现：

- overload 选择（见 `MethodCallResolver`）
- object/variant 动态回退路径（OBJECT_DYNAMIC / VARIANT_DYNAMIC）
- 默认参数补全（literal + `default_value_func`）

前端调用决议应做到：

1. **能静态决议时尽量静态决议**（提升类型推断与早期报错能力）
2. **不能静态决议时显式记录原因并降级**（由后端动态路径兜底）
3. **对确定非法的调用 fail-fast**（不要用动态兜底掩盖错误）

建议采用与后端一致的候选筛选/排序规则（避免两套语义漂移）：

- 先过滤：参数数量/默认参数/vararg 兼容 + `ClassRegistry.checkAssignable` 类型兼容
- 再按 owner 距离优先（继承链最近命中）
- 同组内实例优先（static 仅在无实例候选时允许）
- non-vararg 优先于 vararg
- 若仍并列：
  - receiver 为 `GdObjectType`：允许回退到动态（记录为 dynamic fallback: ambiguous）
  - 其他：编译期报 `ambiguous overload`

### 3.4 属性与索引语义

对照后端实现（`PropertyAccessResolver`、`IndexLoadInsnGen/IndexStoreInsnGen`），前端建议在语义层区分三类访问：

1. **对象属性访问（typed）**：`load_property` / `store_property`
2. **动态成员访问**：`variant_get_named` / `variant_set_named`（key 为 `StringName` 变量）
3. **索引访问**：
   - `[]`：`variant_get_indexed` / `variant_set_indexed`（index 为 `int` 变量）
   - `{}` keyed：`variant_get_keyed` / `variant_set_keyed`（key 为 `Variant`）
   - 通用：`variant_get` / `variant_set`

建议提供编译选项（严格度）：

- `strictMemberAccess=true`：已知对象类型访问不存在的 property/method -> error
- `strictMemberAccess=false`：已知对象类型访问不存在 -> property 降级为 `variant_get_named`，method 保留 `call_method` 动态路径（known object -> `OBJECT_DYNAMIC`，Variant receiver -> `VARIANT_DYNAMIC`），并发 warning

> 注：当前阶段默认按“允许动态回退 + warning”实现（见“已确认工程策略”）。
> 对于 **method 动态回退**，若 receiver 的静态类型是已知对象类型，可直接保留 object receiver 并下降为 `call_method`，
> 让后端走 `OBJECT_DYNAMIC` 路径；只有 receiver 本身已经是 `Variant` 时，才走 `VARIANT_DYNAMIC`。

---

## 4. AST -> LIR 下降设计（核心 API）

### 4.1 两阶段 LIR 生成（建议）

1. **Class Skeleton Lowering（声明层）**
   - 生成 `LirClassDef`：name/super/is_tool/annotations/sourceFile
   - 生成 `LirSignalDef`：参数类型（缺失 -> Variant）
   - 生成 `LirPropertyDef`：
     - `init_func`：对 `var foo = <expr>` 生成隐藏初始化函数（见 4.2）
     - annotations（例如 `@export`）
2. **Function Body Lowering（指令层）**
   - 对每个 `FunctionDeclaration` 生成 `LirFunctionDef` + `basic_blocks`
   - 对每个 statement/expression 下降成 LIR 指令序列

两阶段的收益：

- 可以在生成函数体之前把类/函数签名注入 `ClassRegistry`，从而支持跨脚本调用决议。
- 可以在 body lowering 中依赖已稳定的签名与属性元数据，减少 “边生成边补丁” 的复杂度。

### 4.2 默认参数与字段初始化函数生成规则

LIR 已支持 `default_value_func` 与 property 的 `init_func`，前端需要系统化生成隐藏函数：

- 函数默认参数：
  - `func f(x: int = 1)` 或 `func f(x := 1)`：
    - 生成隐藏静态函数：`_default_f$x`（命名规则可调整，但必须稳定、可反向定位）
    - signature：无参数（或按未来设计携带 `self`）
    - body：计算默认表达式并 `return`
    - 在 `LirParameterDef.defaultValueFunc` 写入该函数名
- 字段初始化：
  - `var foo: float = 45`：
    - 生成隐藏函数：`_field_init_foo`
    - 若是实例字段：第一个参数必须是 `self:Object`（见 `doc/gdcc_low_ir.md` property 约束）
    - return type 为字段类型

验收点：以 `doc/gdcc_low_ir.md` demo 的 `RotatingCamera` 为标准样式之一（不要求完全同名，但要求语义一致）。

### 4.3 LIR Function Builder（建议抽象）

建议新增一个只面向前端的 builder（不要复用后端 `CBodyBuilder`，避免依赖倒置）：

- `LirFunctionBuilder`
  - basic block 管理：`newBlockId()`、`beginBlock(id)`、`endWithGoto/GoIf/Return`
  - 变量管理：
    - `declareUserVar(name, type)` -> `LirVariable(id=name, ref=false)`
    - `declareTemp(type)` -> `func.createAndAddTmpVariable(type)`（numeric id）
  - 指令发射：`emit(LirInstruction)`，并负责 `line_number` 插入策略
  - 临时值生命周期：
    - `TempScope`：记录本 scope 内创建的 destroyable temp 变量，scope 结束或分支跳转前发射 `destruct $tmp "INTERNAL"`

> 生命周期约束必须遵守 `doc/gdcc_low_ir.md` 的 provenance 限制：  
> - temp/internal/numeric id -> `INTERNAL`  
> - 禁止对用户命名变量发射 `INTERNAL` provenance 的生命周期指令

### 4.4 语句/表达式下降规则（MVP 子集）

MVP 建议先覆盖能跑通大多数脚本的子集（与后端当前指令覆盖最大交集对齐）：

- statements：
  - `VariableDeclaration`（局部 var/const）
  - `ExpressionStatement`
  - `IfStatement`
  - `WhileStatement`
  - `ForStatement`（可先延后）
  - `ReturnStatement`
- expressions：
  - `LiteralExpression` -> `literal_*`
  - `IdentifierExpression` -> 变量引用或成员访问（最终降为 `load_property` / `variant_get_named`）
  - `BinaryExpression` / `UnaryExpression` -> `binary_op` / `unary_op`
  - `CallExpression` -> `call_global` / `call_method` / `call_static_method`
  - `AttributeExpression`：
    - `.prop` -> `load_property`（typed）或 `variant_get_named`（dynamic）
    - `.method(...)`：
      - 若静态可决议：直接下降为 `call_method`
      - 若“已知对象类型但 method 不存在”：warning + 直接下降为 `call_method`（保留 object receiver，触发 `OBJECT_DYNAMIC`）
  - `SubscriptExpression` -> `variant_get_*` / `variant_set_*`

明确延后（不阻塞第一阶段交付）：

- `MatchStatement`（需要 pattern lowering 与 CFG）
- `LambdaExpression`（需要 captures 与 `construct_lambda`）
- `await/yield`（涉及 coroutine）
- 完整注解系统（先支持 `@export` / `@onready` 等最小子集）

---

## 5. 分步骤实施计划（Milestones + 验收细则）

> 每个 milestone 都必须有：可运行的 targeted tests + 清晰的失败文案（fail-fast）。

### M0：依赖接入与最小可编译骨架

**目标**

- 以 JitPack 引入 `gdparser`，并在 GDCC 中新增 `frontend.parse` 骨架（不引入任何后端依赖）

**状态（2026-03-05）**

- ✅ 已完成
- 已落地：
  - `dev.superice.gdcc.frontend.parse.GdScriptParserService`
  - `dev.superice.gdcc.frontend.parse.FrontendSourceUnit`
  - 统一诊断模型：`dev.superice.gdcc.frontend.diagnostic.*`
- 说明：
  - 运行 `gradle` 时当前仓库仍需为 `extra-java-module-info` 补充 `gdparser` 与 `tree-sitter` 的 module 映射（见本次实现验证命令）。

**实现步骤**

1. 新增 `dev.superice.gdcc.frontend.parse.GdScriptParserService`
   - `parseUnit(Path, String)` -> `FrontendSourceUnit`
2. 将 `gdparser` 的 tolerant diagnostics 转换为 GDCC 前端统一诊断结构

**验收**

- `./gradlew.bat classes --no-daemon --info --console=plain` 通过
- 单测：
  - `FrontendParseSmokeTest`：给定一段脚本，能拿到 AST 与 diagnostics（无 crash）

### M1：模块扫描与 Class Skeleton 构建

**目标**

- 从 AST 提取 class/signal/property/function 的声明信息，构建本模块的 class skeleton，并注入 `ClassRegistry`。

**状态（2026-03-05）**

- ✅ 已完成
- 已落地：
  - `dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder`
  - `dev.superice.gdcc.frontend.sema.FrontendModuleSkeleton`
  - `dev.superice.gdcc.frontend.sema.FrontendSemanticException`
- 已实现能力：
  - `class_name` / `extends` 解析（`ClassNameStatement` 优先）
  - 无 `class_name` 时按文件名派生稳定类名（不允许空名）
  - 提取 `signal/property/function` 声明并生成 `LirClassDef` skeleton
  - 注入 `ClassRegistry.addGdccClass(...)`
  - 继承环检测（fail-fast，错误信息含链路）

**实现步骤**

1. 解析 `class_name` / `extends`：
   - `ClassNameStatement` 优先
   - 否则使用文件名派生匿名类名（当前阶段不允许空名）
2. 收集 properties/signals/functions 的声明（不生成 body）
3. 注入 `ClassRegistry.addGdccClass(...)`

**验收**

- 单测：
  - `FrontendClassSkeletonTest`：验证 `extends` 与成员列表可见
  - `FrontendInheritanceCycleTest`：继承环 fail-fast 且错误带链路

### M2：签名级语义（参数/返回/默认参数/字段 init）

**目标**

- 在不分析函数体的前提下，完成函数签名与默认值函数/字段 init 函数的 LIR 结构生成。

**实现步骤**

1. `TypeRef` -> `GdType` 解析：
   - 复用 `ClassRegistry.tryParseTextType(...)` / `ClassRegistry.findType(...)`
2. 生成 `_default_*` / `_field_init_*` 的隐藏函数声明（body 在 M3/M4 完成）
3. 将 `defaultValueFunc` / `initFunc` 写入 `LirParameterDef` / `LirPropertyDef`

**验收**

- 单测：
  - `FrontendDefaultArgSkeletonTest`：生成 `default_value_func` 并引用正确
  - `FrontendFieldInitSkeletonTest`：属性 init_func 存在且签名满足 LIR 规范

### M3：表达式类型推断（局部）+ 最小语句下降（直线代码）

**目标**

- 支持无分支函数体：literal/identifier/binary/unary/call_global/load/store_property/return 的 LIR 生成。

**实现步骤**

1. 实现 `frontend.sema`：
   - 绑定规则（局部/参数/self/property/utility）
   - 局部类型推断：沿语句顺序推断与校验
2. 实现 `frontend.lir` 的直线 lowering：
   - `line_number` 插入策略：按 statement 起始行号插入
   - temp scope + destruct（INTERNAL）在语句边界释放

**验收**

- 单测：
  - `FrontendLoweringPrintTest`：`print("x")` 生成 `literal_string` + `pack_variant` + `call_global` + `destruct`
  - `FrontendLoweringPropertyAccessTest`：`self.foo = 1` 生成 `store_property`
  - `FrontendLoweringBinaryOpTest`：`a + b` 生成 `binary_op` 且 result type 合理

补充验收点（对齐“已确认工程策略”）：

- 已知对象类型访问未知 property：warning + 下降为 `variant_get_named`（而不是 fail-fast）
- 已知对象类型调用未知 method：warning + 直接下降为 `call_method`（保留 object receiver，触发 `OBJECT_DYNAMIC`）

### M4：控制流（if/while）+ 类型合流

**目标**

- 支持 `if/elif/else` 与 `while` 的 CFG 生成：basic blocks + `go_if` + `goto`，并在类型层面处理合流。

**实现步骤**

1. `LirFunctionBuilder` 支持多 block 与 terminator 约束（每 block 最终必须 terminator）
2. 类型合流策略（MVP）：不兼容则 `Variant` 或 strict error
3. 分支跳转前 temp scope cleanup（避免 loop 中泄漏）

**验收**

- 单测：
  - `FrontendIfLoweringTest`：验证 block 结构与 `go_if/goto`
  - `FrontendWhileLoweringTest`：验证 loop 结构

### M5：调用点决议（method/static/ctor）+ overload 选择对齐

**目标**

- 支持 method/static/constructor 的语义分析与 LIR 生成，并与后端 resolver 的候选选择规则保持一致（或共享规则实现）。

**实现步骤**

1. 引入 `frontend.sema.call`：
   - global utility -> `FunctionSignature`
   - builtin/engine/gdcc method -> candidates + selection
   - dynamic fallback reason 记录
2. LIR 选择：
   - `call_method` / `call_static_method`
   - builtin ctor -> `construct_builtin`
   - object ctor -> `construct_object`

**验收**

- 单测：
  - `FrontendCallResolutionTest`：overload 选择一致性 + ambiguous 处理
  - `FrontendConstructorLoweringTest`：`Vector3(...)` / `MyNode.new()` 的 lowering

### M6：索引与赋值（[]/keyed/named）+ 复合赋值

**目标**

- 支持 `a[i]`、`a[key]`、`a.name` 的读写下降，复用后端 indexing/property 的运行时兜底能力。

**实现步骤**

1. subscript lowering：
   - indexed 与 keyed 的选择规则
2. 复合赋值（`+=` 等）：
   - 展开为 load + op + store（注意值语义与 temp destruct）

**验收**

- 单测：
  - `FrontendIndexGetSetLoweringTest`：生成 `variant_get_*` / `variant_set_*`
  - `FrontendCompoundAssignTest`：`x += 1` 的 lowering 正确

### M7：match / lambda / captures（可选延后）

**目标**

- match：pattern lowering + CFG
- lambda：生成隐藏 lambda function + captures + `construct_lambda`

**验收**

- 单测：
  - `FrontendMatchLoweringTest`
  - `FrontendLambdaLoweringTest`

---

## 6. 质量门禁（全阶段通用）

1. **Fail-fast**：能确定的语义错误必须编译期报错，不允许静默降级。
2. **可定位**：错误必须带 `Path + Range`（行列）与稳定错误分类（建议）。
3. **后端对齐**：所有 lowering 到 LIR 的指令必须满足 `doc/gdcc_low_ir.md` 的操作数与类型约束。
4. **生命周期 provenance**：任何 lifecycle 指令必须带明确 provenance，并满足限制矩阵。
5. **测试策略**：迭代期只跑 targeted tests；每个 milestone 完成后补 `classes` 验证。

---

## 7. 关键未决问题（需要你确认后再锁定实现）

本节在 `2026-03-04` 已全部确认，详见文首“已确认工程策略（2026-03-04）”。
