# Frontend GetNode 与 NodePath 字面量实现说明

> 本文档作为 `get_node` 简写（`$` / `%`，AST 为 `GetNodeExpression`）与 NodePath 字面量（`^"..."`）从 semantics 到 C codegen 的长期事实源。本文档替代原 `frontend_get_node_node_path_plan.md` 与 `frontend_onready_node_literal_implementation_plan.md`，不保留分步骤实施计划、阶段状态、完成清单、验收流水账或进度记录。

## 文档状态

- 状态：事实源维护中（`^"..."` 端到端闭环；Node 派生类非 static 函数体与已记录 lambda 体内的 `$` / `%` 经隐式 `self` capture 端到端闭环；合法 `@onready` 属性初始化器内的 `$` / `%` 及显式表达式 receiver 实例成员后缀端到端闭环；普通 / `static` property initializer 与 parameter default 中的 `$` / `%` 仍 DEFERRED / fail-closed）
- 更新时间：2026-10-27
- 适用范围：
  - `src/main/java/gd/script/gdcc/util/StringUtil.java`
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/test/java/gd/script/gdcc/**`
  - `src/test/test_suite/unit_test/**/scene/get_node_*.gd`
  - `src/test/test_suite/unit_test/{script,validation}/runtime/virtual/onready_node_literal_*.gd`
- 关联文档：
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_property_init_lowering_implementation.md`
  - `frontend_lambda_implementation.md`
  - `frontend_parameter_default_implementation.md`
  - `frontend_signal_support.md`
  - `frontend_annotation_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_implicit_conversion_matrix.md`
  - `frontend_container_literal_implementation.md`
  - `diagnostic_manager.md`
  - `doc/gdcc_low_ir.md`
  - `doc/module_impl/backend/builtin_builder_implementation.md`
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/module_impl/backend/onready_class_implementation.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`
- 明确非目标：
  - 不开放 `String ↔ NodePath` 隐式转换（matrix 保持 GDCC `N`）
  - 不在普通（非 `@onready`）/ `static` property initializer 与 parameter default 中开放 `$` / `%`
  - 不开放 property initializer 内的未记录 lambda 与 `await`
  - 不做基于场景文件的节点类型推导：`$Camera` 静态类型固定 `Node`，不自动收窄为 `Camera3D` 等子类，也不引入隐式 downcast
  - 不为 `$Foo = x` 提供 writable-target 语义
  - 不为 `get_node_or_null` / `has_node` 做专用 lowering（普通 `CallExpression → CallMethodInsn`）
  - 不对显式表达式 receiver 做运行时 identity 隔离或 alias 分析：间接 self（`$"."`、`get_parent()` 回 self）的防御由脚本使用者负责（§3.3、§4 D11）
  - 不改 `CBuiltinBuilder.isQuotedNodePathLiteral` 的 API-dump `$"..."` 遗留分支

---

## 1. 覆盖范围与当前合同

当前 compile-ready 支持面：

1. NodePath 字面量 `^"path"`：sema → CFG → body lowering → LIR `literal_node_path` → C codegen 全链路可用。可用位置包括函数体、property initializer、static var initializer、引擎调用实参与容器字面量 value 元素。
2. `get_node` 简写：
   - `$Child`、`$A/B`、`$"Path With Spaces"`
   - `%UniqueName`、`%"Unique Name"`
   - 允许位置：Node 派生类的**非 static 函数体**（含构造函数体）、这些函数体内**已记录 lambda 体**（经隐式 leading `self` capture），以及**合法 `@onready` class `var` 初始化器**（`var`、非 static、declaring class 派生自 `Node`、注解语法完整；见 §3.3 能力谓词）
3. `$` / `%` 作为链头（`$Foo.bar()`、`%Foo.call()`）经既有 `fallbackExpressionReceiverResolver` 获得 `Node` receiver，不新增专用链头规则。
4. 合法 `@onready` 岛内，**显式表达式 receiver**（GetNode 结果、实例方法调用结果、显式 static helper / 构造对象结果、cast / 条件包装结果）上的实例成员后缀放行：property read、instance-method call、method-reference `→ Callable`、signal `→ Signal` 四条路由均解析，即使成员 owner 是 `Node` 或当前类祖先（句法 direct-self 边界，见 §3.3 与 §4 D10）。示例：`$Child.name`、`%Unique.get_name()`、`$Child.get_name`、`$Child.renamed`、`$Child.get_parent().get_name()`、`$".".get_name()`、`[$Child, %Unique]`、`$Child if cond else %Unique`。

边界矩阵：

| 场景 | `$` / `%` | `^"..."` |
|---|---|---|
| Node 派生类非 static 函数体 / 构造函数体 | `RESOLVED(Node)`，compile-ready | compile-ready |
| 上述函数体内的已记录 lambda | `RESOLVED(Node)`，leading `self` capture | compile-ready |
| 合法 `@onready` class `var` 初始化器（含容器 / cast / 条件等组合位置与显式 receiver 后缀链） | `RESOLVED(Node)`，compile-ready；由 ready-time apply 求值 | compile-ready |
| static 函数及其 lambda | `FAILED`（`sema.expression_resolution`） | compile-ready |
| 非 Node 派生类及其 lambda | `FAILED`（`sema.expression_resolution`） | compile-ready |
| 普通 / `static var` / 不合格 `@onready`（static 字段、非 Node 类、损坏注解）property initializer | `DEFERRED`；compile 模式升级为 `sema.compile_check` | compile-ready |
| parameter default 中的直接 `$` / `%` | 不进入 GetNode typing；随 parameter-default 子树整体 fail-closed | 随 default 工作流，当前未接通 |
| property initializer / parameter default 中的未记录 lambda | 整体 fail-closed；body 剪枝，不单独分析其中的 `$` / `%` | 不适用 GetNode lambda 路径 |

`@onready` 岛内的 direct-self 边界（句法层判定，与 receiver 运行时身份无关）：

| 形式 | 结果 |
|---|---|
| 显式 `self` / `super`（含 cast / 条件 / 括号包装与实参位置） | 由 binding / head owner 封死 |
| 隐式 self 裸成员（字段 / 方法调用 / 方法引用 / 信号，含继承成员） | 静默 `BLOCKED` |
| type-meta receiver（static load / static method 判定） | 原边界不变 |
| 显式表达式 receiver 的实例成员后缀 | 放行（即使成员 owner 属当前实例层级） |

---

## 2. Godot 语义基线与 GDCC 对齐

以 godotengine/godot 4.x 为准：

- parser 把 `$Foo` / `$"A B"` / `%Foo` / `%"A B"` 拼入 `full_path`；`%` 前缀保留，unique-name 查询由运行时处理。
- analyzer：当前类不是 `Node` 子类或位于 static 函数 → 报错；合法时表达式类型固定为 native `Node`，**不做基于场景文件的具体类型推导**。
- compiler desugar 为 `self.get_node(NodePath(full_path))`。这是 `get_node`（缺失节点时报引擎错误），不是 `get_node_or_null`。
- `@onready` 变量在 Godot 中等价于把赋值推迟到 ready 时执行：此时对象已入树、children 可见，构造期字段先持默认值。
- runtime：相对路径从当前节点找，`/` 开头走场景树根，`.` 当前节点，`..` 父节点，`%Name` 走 owner 的 `owned_unique_nodes`。
- `^"..."` 按 GDScript 字符串规则转义解码后构造 `NodePath`。
- 引擎绑定层存在 `String → NodePath` 严格转换，所以解释版 `get_node("Foo")` 合法；GDCC 选择不支持该隐式转换。

GDCC 对齐：

- `$` / `%` 静态类型固定为 `GdObjectType("Node")`。
- static 函数与非 Node 派生类中的 `$` / `%` 是**源码错误**（sema `FAILED`），不是 compile-only 限制。
- 已记录 lambda 体经 capture 规划获得与函数体一致的边界：GetNode 视为隐式 `self` 用法。
- 合法 `@onready` initializer 复用 property-initializer 支持岛与 backend ready-time apply 链：sema 发布 `RESOLVED(Node)`，helper 在 ready 时对 live 实例求值，因此可以访问已入树的 children——与 Godot 的 ready-time 语义一致。
- 其余 property initializer（普通、`static var`、不合格 `@onready`）中的 `$` / `%` 保持 `DEFERRED`：shared analyze 发 `sema.deferred_expression_resolution` warning；compile 模式由 generic published-fact scan 升级为 `sema.compile_check` error，消息形态为 `Expression remains deferred at compile surface and is not lowering-ready in compile mode: <detailReason>`，detailReason 以 `Get-node expression` 开头。

---

## 3. 端到端实现

### 3.1 Parser（外部 gdparser，不可修改）

- `GetNodeExpression(String sourceText, Range)`：`sourceText` 保留完整原始文本（含 `$` / `%` 前缀与可选引号）。
- `LiteralExpression(kind="node_path", sourceText, Range)`：`sourceText` 保留完整 `^"..."` lexeme。

### 3.2 解码合同（`StringUtil`）

遵循 `common_rules.md` 字符串处理约定，解码集中于 `StringUtil`：

- `decodeNodePathLexeme`：剥 `^` 前缀后按 GDScript 引号规则解码；非 `^"..."` 形态抛 `IllegalArgumentException`。
- `decodeGetNodePathLexeme`：
  - `$"..."` → 字符串解码；`$X`（bare）→ `X` 原样（含 `/root/...` 的 `/` 前缀）
  - `%"..."` → `%` + 字符串解码；`%X`（bare）→ `%X`（**保留 `%` 前缀**）
  - 其它形态抛 `IllegalArgumentException`
- `FrontendContainerLiteralSemanticSupport.tryDecodeNodePathLexeme` 复用 `decodeNodePathLexeme`，保持 null-on-miss。

### 3.3 Sema

- `FrontendChainHeadReceiverSupport.resolveLiteralType` 把 `node_path` 解析为 `GdNodePathType.NODE_PATH`。
- `FrontendBodyOwnerProcedures.resolveGetNodeExpressionType` 按序判定：
  1. property initializer（含 class-level `static var` 初始化器）→ 先查能力谓词 `FrontendPropertyInitializerSupport.checkNodeDerivedOnreadyInitializer(context, analysisData)`：合格 → `RESOLVED(Node)`；不合格 → 保持 `DEFERRED`（detailReason 不变）
  2. lambda body → 消费 overlay-aware `typedEnvironment.lambdaPlan(...)`：
     - plan 缺失属 published-fact 协议破坏，fail-fast
     - `capturesSelf` 且 leading `self` capture 可赋值到 `Node` → `RESOLVED(Node)`
     - 否则 `FAILED`（文案复用函数体：static enclosing / 非 Node 派生）
  3. static 函数 → `FAILED`
  4. 当前类不可赋值到 `Node` → `FAILED`
  5. 否则 `RESOLVED(Node)`
- 不发布 `FrontendResolvedCall`（resolved-call key space 冻结于 `CallExpression` / `AttributeCallStep`）。
- 链头走同一 expression typing 管线，自动获得 Node receiver。

**`@onready` 能力谓词**（`FrontendPropertyInitializerSupport.checkNodeDerivedOnreadyInitializer`）：

- `context == null`、声明为 `static`、或没有语法完整的 `onready` 注解 → `false`；注解完整性过滤与 usage analyzer 一致（`sourceStatement()` 不属于 `skippedSubtreeRoots()`）。
- 合格判定为 `context.declaringClass()` 经 `ClassRegistry.checkAssignable(..., Node)`——使用的是 initializer 的 declaring class，不依赖文件顶层 owner 或 `class_name` 拼写；间接 Node 基类（如 `extends Camera3D`）与 inner Node class 同样合格。
- 该谓词只是“此 initializer 拥有 ready-time 实例接收者”的执行时机闸门：注解 placement、重复等合法性仍由 `FrontendAnnotationUsageAnalyzer` 持有诊断。重复 `@onready` 可授予能力，但 usage error 阻止进入 lowering；不合格岛不发 GetNode 专属错误，沿用原 `DEFERRED` / deferred-warning 路径；parser 损坏的声明随 skipped-subtree 恢复整体跳过，不伪造 fact。

**`@onready` 岛内显式 receiver 后缀放行**（句法 direct-self 边界）：

- `FrontendChainReductionHelper` 的四处 suffix boundary——`reducePropertyStep` 内 resolved property / resolved signal / resolved method reference 三处与 `reduceInstanceMethodStep` 内 resolved method 一处——在 object receiver 分支先取同一谓词；合格岛跳过该步的 ownerClass boundary，不合格走原判定。`ReductionRequest` 已携带 `analysisData()` 与 `propertyInitializerContext()`，调用点不需要新增字段。
- 放行仅限 suffix 步骤：显式 `self` / `super`（`bindSelf` / `bindSuper` 与 chain head support）、隐式 self 裸成员（`detailForBindingBoundary`，字段 / 方法调用 / 方法引用 / 信号，含继承成员，含 cast / 条件 / 括号包装与实参位置）继续由 binding / head owner 封死；type-meta receiver 的 static load / static method 检查不受影响。
- 不做 receiver provenance / identity 分析：`$".".get_name()`、`$Child.get_parent().get_name()` 属合法放行形态（前者必为 self、后者可能为 self）；成员存在性、类型兼容、调用解析与运行时对象存活检查照常，不因责任划分削弱。

读取 lambda plan 必须 overlay-aware：`putLambdaPlan` 只写当前 typed environment overlay，stable `analysisData.lambdaPlans()` 要等 lambda body 完成后 `exportBatch.applyTo` 才可见。body typing 期间直接读 stable 侧表必缺 plan。禁止为绕过时序而提前直写 stable side table。

### 3.4 Capture

`FrontendVariableAnalyzer.LambdaCaptureSourceScanner.handleGetNodeExpression` 把 `$` / `%` 视为与显式 `SelfExpression` 等价的 enclosing-instance 需求，置 `usesExplicitSelf`。嵌套 lambda 经既有 `childPlan.capturesSelf()` 向上传递 leading `self`。static enclosing 不合成非法 capture，源码诊断由 sema `FAILED` 承担。

lambda shell 合成时 `LirFunctionDef.addCapture` 把 leading `self` capture 注册为同名 function variable；body lowering 的 `requireSelfSlot()` 命中该 captured 本地槽。

### 3.5 Compile gate

`GetNodeExpression` 无表达式级显式 intercept。generic published-fact scan 接管：

- 函数体、已记录 lambda 与合格 `@onready` initializer 内的 `RESOLVED(Node)` → 放行
- 其余 property initializer 的 `DEFERRED` → 升级为 `sema.compile_check`
- 上游 `FAILED` → 不重复发 `sema.compile_check`
- 合格岛内 leaf 放行不豁免同 initializer 中其他不稳定子树：`[$Child, payload]` 中 `$Child` leaf `RESOLVED`，但裸成员元素仍由 generic scan 阻断；重复 `@onready` 的 usage error 同样阻止进入 lowering。

### 3.6 CFG / body lowering

- CFG：`GetNodeExpression` 是无 child、无 operand 的 opaque leaf（`OpaqueExpressionRoute.empty()`）。
- sequence 分类器：`GetNodeExpression` → `HANDLE_NOW`。
- `FrontendGetNodeOpaqueExprInsnLoweringProcessor` 固定三指令：

```text
literal_node_path(pathTmp, decodedPath)
assign(receiverTmp, self)       // receiverTmp 静态类型 Node
call_method(result, "get_node", receiverTmp, [pathTmp])
```

- `^"..."` 走 `FrontendLiteralOpaqueExprInsnLoweringProcessor` 的 `kind == "node_path"` 分支，产出 `LiteralNodePathInsn`；未知 literal kind 仍 fail-fast。
- opaque item 必须零 operand；必须存在 self slot（lambda 仅在 capture 规划合成 leading `self` 时才有）。
- GetNode lowering 对 property initializer **无任何特判**：同一 opaque processor 在 `PROPERTY_INIT` helper（hidden、non-static、首参 owning `self`、返回 property type，见 `FrontendLoweringFunctionPreparationPass.createPropertyInitShell`）内发射相同三指令；后缀指令（`LoadPropertyInsn` / `CallMethodInsn` / `ConstructCallableInsn` / `ConstructSignalInsn`）沿用既有 chain reduction 产物在 `get_node` 调用之后发射，alias 形态走完全相同管线、无 provenance 分支；条件表达式中的链保持惰性（未选分支块不含 lookup / suffix 指令），容器元素按源码顺序逐个求值；untyped（Variant）属性的 Node 结果经 `PackVariantInsn` 回 helper return。

**receiver 必须上溯到 `Node`**：`BackendMethodCallResolver` 从 receiver 静态类型开始收集候选。`Node.get_node` 非 virtual；若直接用 GDCC 类类型的 `self` 槽，子类同名 `get_node(NodePath)` 会 shadow 引擎方法。Godot compiler 对 `$` 固定走 `ClassDB.get_method("Node", "get_node")`。receiver 槽钉住 `Node` 后，GDCC 子类方法不可见，ENGINE route 与 Godot 一致。显式 `self.get_node(...)` 仍走普通方法解析，脚本 override 可以生效。

运行时语义对齐 Godot compiler：用 `get_node`，不提供 `get_node_or_null` 变体。

### 3.7 LIR

- `GdInstruction.LITERAL_NODE_PATH`：`REQUIRED` + 恰好一个 `STRING` operand。
- `LiteralNodePathInsn(@Nullable String resultId, @NotNull String value)`：`value` 为**已解码 payload**（不含 `^` 与引号）。
- backend **不做 raw-lexeme 形状拒绝**：合法源码 `^"^\"foo\""` 的解码结果就是 `^"foo"`，形状检查会误杀。payload-only 合同由 frontend 解码与 operand 约束保证。
- 缺 result 的拒绝点在 backend，不改变通用 LIR parser 合同（parser 不按 `ReturnKind.REQUIRED` 校验 result）。

否决方案：`literal_string` + `construct_builtin` 复用 `NodePath(String)`。多一个 String 临时槽，且违背 literal 直接物化的指令族语义。

### 3.8 C backend

`NewDataInsnGen.emitNodePathLiteral`：

- 结果槽类型必须是 `GdNodePathType`，否则 `invalidInsn`
- 缺 result → `invalidInsn`
- 非 ref：`godot_new_NodePath_with_utf8_chars`
- ref：`invalidInsn`（GDExtension 无 NodePath 就地 utf8 构造器；frontend 字面量结果槽永远是非 ref 临时槽，该分支为防御）

生命周期：`godot_new_NodePath_with_utf8_chars` 返回 owned value，按普通 value-semantic builtin 回收。`get_node` 返回的 `Node` 为非 RefCounted 引擎对象，沿用既有 CALL_METHOD fat-pointer 合同。backend 零新增：ENGINE 在 `Node` 上解析 `get_node(NodePath) -> Node`。

`@onready` 字段复用既有 ready-time apply 链（`doc/module_impl/backend/onready_class_implementation.md`）：构造期由 backend 合成的 `_onready_field_default_<prop>` helper 写类型默认值（**不调用** source initFunc）；ready 时 `class_apply_onready_init_*` 调用 source initFunc 并以 OWNED 结果 overwrite 字段、释放旧 default；`request_ready()` 后的再入树重新求值。本功能不新增 opcode、runtime binding 或 backend 的 AST / GetNode 特判。

`CBuiltinBuilder.materializeLiteralValue` 服务 API dump 默认值物化，与 LIR 字面量指令无关。4.5.1 dump 中 NodePath 默认值实际编码为 `NodePath("")` constructor 形式；`$"` 分支是 latent defensive code。

---

## 4. 冻结设计决策

这些决策是后续工程必须遵守的合同，不是实施进度：

- **D1**：NodePath 字面量走独立 `literal_node_path`；payload 已解码；backend 不做 raw-lexeme 形状拒绝。
- **D2**：解码集中于 `StringUtil`；容器 constant key 复用同一解码并保持 null-on-miss。
- **D3**：`^"..."` lowering 走既有 literal processor 分支，函数体 / property-init / static var / 容器 value 自动打通。
- **D4**：GetNode sema 发布 `RESOLVED(Node)` 或边界 `FAILED` / `DEFERRED`；不发布 `resolvedCalls`；lambda 必须 overlay-aware 消费 `FrontendLambdaPlan`。
- **D5**：GetNode lowering 固定三指令；receiver 静态类型钉住 `Node`；调用 `get_node` 而非 `get_node_or_null`。
- **D6**：backend 仅物化非 ref 结果槽；ref 结果槽 fail-closed。
- **D7**：不恢复 GetNode 专用 explicit intercept；generic published-fact scan 按 published outcome 放行或升级。
- **D8**：`String ↔ NodePath` 维持 GDCC `N`；`NodePath` 不加入 `call_func` inbound String/StringName 家族；`String as NodePath` 继续走显式 `builtin_cast`。因此 `get_node("Foo")` 仍类型错误，应写 `get_node(^"Foo")` 或 `get_node(NodePath("Foo"))`。
- **D9**：`@onready` 能力判定集中于 `checkNodeDerivedOnreadyInitializer`：非 static 声明 + 语法完整 `onready` 注解（`skippedSubtreeRoots` 过滤，与 usage analyzer 同规则）+ declaring class `checkAssignable(Node)`。它只是执行时机闸门——placement / 重复诊断归 usage analyzer，损坏声明走既有 skipped-subtree 恢复，不新增 annotation-validation phase、能力 side table 或 runtime 标记。
- **D10**：`@onready` 岛内成员边界采用句法 direct-self：显式表达式 receiver 的实例成员后缀放行（property / instance-method / method-reference / signal 四路由，即使成员 owner 属当前实例层级）；显式 `self` / `super` 与隐式 self 裸成员在任何语法位置（含 cast / 条件 / 括号包装与实参）仍由 binding / head owner 封死；不引入 `ReceiverState` provenance、alias 追踪、identity 过滤或共享 receiver 模型重设计；type-meta 检查不变。
- **D11**：间接 self 防御归脚本使用者：`$"."`、`$Child.get_parent()` 回 self 属有意放行形态；编译器不承诺 alias 安全、不保留任何 alias-proof 待办或专用诊断；自有状态、声明依赖与重入正确性由用户保证。
- **D12**：`$` / `%` 结果类型固定 `Node`，不做场景文件类型推导与隐式 downcast；子类化仅经显式 `as` cast。
- **D13**：ready-time 语义无新增执行路径——复用 `PROPERTY_INIT` helper 与 backend default / apply 分工；GetNode lowering 无 initializer 特判；同名脚本 `get_node(NodePath)` override 不参与 shorthand 解析。

---

## 5. 测试与回归锚点

| 层 | 测试类 | 关注点 |
|---|---|---|
| util | `StringUtilTest` | `decodeNodePathLexeme` / `decodeGetNodePathLexeme` 正反用例 |
| LIR | `LiteralNodePathInsnContractTest` | opcode 形状、round-trip、非法 operand |
| backend | `CNewDataInsnGenTest` | 非 ref 物化、转义、缺 result / 错类型 / ref 三类 `invalidInsn` |
| sema | `FrontendExpressionSemanticSupportTest`、`FrontendBodyOwnerProceduresChainBindingTest`、`FrontendBodyOwnerProceduresExprTypeTest` | 函数体 / lambda `RESOLVED(Node)`，static / 非 Node `FAILED`，普通 property-init `DEFERRED`；`@onready` leaf 五形态、间接 Node 基类、inner class、损坏注解 / 损坏 initializer 恢复与合法 sibling；四路由 suffix 放行、不合格岛封死、missing member 照常 `member_resolution` / dynamic fallback；direct-self 全域拒绝 |
| capture | `FrontendVariableAnalyzerTest`、`FrontendLambdaSuiteResolutionTest` | GetNode 触发 leading `self` capture、嵌套传递、static enclosing 不合成 |
| compile gate | `FrontendCompileCheckAnalyzerTest`、`ApiCompileDiagnosticsTest` | 函数体 / 已记录 lambda / 合格 `@onready` 放行；普通 init 阻断；坏 sibling 不被豁免；重复注解错误归 usage owner；上游 FAILED 不去重 |
| type-check / annotation | `FrontendTypeCheckAnalyzerTest`、`FrontendAnnotationUsageAnalyzerTest`、`FrontendParameterDefaultMetadataOwnerTest` | declared slot 兼容与 `Object` 上转、untyped Variant type hint；`@onready const` placement；parameter default 中 `$` / `%` 仍拒 |
| lowering | `FrontendLoweringBodyInsnPassTest`、`FrontendLambdaLoweringTest` | 三指令序列、payload 解码、`%` 前缀、override 钉住、lambda captured `self` 槽；`@onready` 五形态 helper 合同（hidden / non-static / owning `self` / 返回 property type）、四路由 suffix 指令序与 `AssertObjectLiveInsn` 接线、容器源码顺序、条件两臂惰性、untyped Variant `PackVariantInsn`、alias 同管线 |
| e2e | `GdScriptUnitTestCompileRunnerTest` scene fixtures、`FrontendLoweringToCProjectBuilderIntegrationTest` | `get_node_shorthand_scene.gd`、`get_node_lambda_flow_scene.gd`、`get_node_lambda_await_scene.gd`；`lowerFrontendOnreadyNodeLiteralGeneratesC`（环境无关 C 文本锚定）；`lowerFrontendOnreadyNodeLiteralBuildsNativeLibraryAndRunsInGodot`（真实引擎：构造期默认 → READY 后 identity → suffix 精确语义 → alias 名 → 无 `_ready` 仍 apply → `request_ready()` replay）；成对 fixture `runtime/virtual/onready_node_literal_lookup.gd` / `onready_node_literal_replay.gd` |

`runtime/virtual` 成对 fixture 构造约定：runner 把 source fixture 首个外层类的编译产物挂为 TargetNode，故外层类只作 harness（`create_probe()` 工厂返回未入树 inner 实例），`@onready` 字段由 inner 承载；validation 经 `target.call("create_probe")` 取得 probe，自建 children（`add_child` → `child.owner = probe` → `unique_name_in_owner = true`）后把 probe 挂树触发真实 READY；仅 validation 在全部断言通过后打印 `__UNIT_TEST_PASS_MARKER__`，并以 `output_not_contains` 指令拒绝引擎错误输出；禁止 `Outer.Inner.new()`（ClassDB 的 inner 名与源码层级不同）。

---

## 6. 已知限制与后续范围

1. **普通 / `static` property initializer 与 parameter default** 中的 `$` / `%` 边界不变（`DEFERRED` / fail-closed）；property initializer 内未记录 lambda 由既有 root 诊断持有，其中的 `$` / `%` 随之 fail-closed；initializer 内 `await` 亦保持原边界。
2. **direct-self 边界不变**：显式 `self` / `super` 与隐式 self 裸成员在任何语法位置（含实参、cast / 条件包装）仍拒绝；type-meta receiver 检查不变；非 `@onready` 岛的同类 / 继承 receiver 后缀保持 `UNSUPPORTED`。
3. **间接 self 无静态保证**：`$"."`、`get_parent()` 回 self 属有意放行；alias 安全性（自有状态、声明依赖、重入）由脚本使用者负责，不是编译器待办。
4. **`isQuotedNodePathLiteral` 的 `$"` 分支**：与 4.5.1 dump 实际编码（`NodePath("")`）不符的 latent defensive code，可独立清理，不在本事实源关键路径。
5. **`get_node` 缺失节点的运行时行为**：GDCC 忠实转发引擎错误，不额外包装。e2e 用 `get_node_or_null` 覆盖缺失路径，避免环境差异。
6. **ref 结果槽的 `literal_node_path`**：当前 fail-closed。若未来出现真实需求，需在 gdcc-owned runtime 增加就地初始化 helper。
7. **继承偏差**：混合 GDScript/GDCC 继承与跨模块父链的 `_ready` 生命周期限制属既有 backend 合同（见 `doc/module_impl/backend/onready_class_implementation.md`），与本功能无关，间接 self 的用户责任划分不改变、也不豁免这些偏差。
