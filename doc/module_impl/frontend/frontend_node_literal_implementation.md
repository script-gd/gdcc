# Frontend GetNode 与 NodePath 字面量实现说明

> 本文档作为 `get_node` 简写（`$` / `%`，AST 为 `GetNodeExpression`）与 NodePath 字面量（`^"..."`）从 semantics 到 C codegen 的长期事实源。本文档替代原 `frontend_get_node_node_path_plan.md`，不再保留分步骤实施计划、阶段状态、完成清单、验收流水账或进度记录。

## 文档状态

- 状态：事实源维护中（`^"..."` 端到端闭环；Node 派生类非 static 函数体与已记录 lambda 体内的 `$` / `%` 经隐式 `self` capture 端到端闭环；property initializer 中的 `$` / `%` 仍 DEFERRED）
- 更新时间：2026-09-02
- 适用范围：
  - `src/main/java/gd/script/gdcc/util/StringUtil.java`
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/test/java/gd/script/gdcc/**`
  - `src/test/test_suite/unit_test/**/scene/get_node_*.gd`
- 关联文档：
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_lambda_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_implicit_conversion_matrix.md`
  - `frontend_container_literal_implementation.md`
  - `diagnostic_manager.md`
  - `doc/gdcc_low_ir.md`
  - `doc/module_impl/backend/builtin_builder_implementation.md`
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`
- 明确非目标：
  - 不开放 `String ↔ NodePath` 隐式转换（matrix 保持 GDCC `N`）
  - 不在 property initializer / `@onready` / parameter default 中开放 `$` / `%`
  - 不为 `$Foo = x` 提供 writable-target 语义
  - 不为 `get_node_or_null` / `has_node` 做专用 lowering（普通 `CallExpression → CallMethodInsn`）
  - 不改 `CBuiltinBuilder.isQuotedNodePathLiteral` 的 API-dump `$"..."` 遗留分支

---

## 1. 覆盖范围与当前合同

当前 compile-ready 支持面：

1. NodePath 字面量 `^"path"`：sema → CFG → body lowering → LIR `literal_node_path` → C codegen 全链路可用。可用位置包括函数体、property initializer、static var initializer、引擎调用实参与容器字面量 value 元素。
2. `get_node` 简写：
   - `$Child`、`$A/B`、`$"Path With Spaces"`
   - `%UniqueName`、`%"Unique Name"`
   - 允许位置：Node 派生类的**非 static 函数体**（含构造函数体），以及这些函数体内**已记录 lambda 体**（经隐式 leading `self` capture）
3. `$` / `%` 作为链头（`$Foo.bar()`、`%Foo.call()`）经既有 `fallbackExpressionReceiverResolver` 获得 `Node` receiver，不新增专用链头规则。

边界矩阵：

| 场景 | `$` / `%` | `^"..."` |
|---|---|---|
| Node 派生类非 static 函数体 / 构造函数体 | `RESOLVED(Node)`，compile-ready | compile-ready |
| 上述函数体内的已记录 lambda | `RESOLVED(Node)`，leading `self` capture | compile-ready |
| static 函数及其 lambda | `FAILED`（`sema.expression_resolution`） | compile-ready |
| 非 Node 派生类及其 lambda | `FAILED`（`sema.expression_resolution`） | compile-ready |
| property initializer（含 class-level `static var` 初始化器） | `DEFERRED`；compile 模式升级为 `sema.compile_check` | compile-ready |
| parameter default 中的直接 `$` / `%` | 不进入 GetNode typing；随 parameter-default 子树整体 fail-closed | 随 default 工作流，当前未接通 |
| property initializer / parameter default 中的未记录 lambda | 整体 fail-closed；body 剪枝，不单独分析其中的 `$` / `%` | 不适用 GetNode lambda 路径 |

---

## 2. Godot 语义基线与 GDCC 对齐

以 godotengine/godot 4.x 为准：

- parser 把 `$Foo` / `$"A B"` / `%Foo` / `%"A B"` 拼入 `full_path`；`%` 前缀保留，unique-name 查询由运行时处理。
- analyzer：当前类不是 `Node` 子类或位于 static 函数 → 报错；合法时表达式类型固定为 native `Node`，**不做基于场景文件的具体类型推导**。
- compiler desugar 为 `self.get_node(NodePath(full_path))`。这是 `get_node`（缺失节点时报引擎错误），不是 `get_node_or_null`。
- runtime：相对路径从当前节点找，`/` 开头走场景树根，`.` 当前节点，`..` 父节点，`%Name` 走 owner 的 `owned_unique_nodes`。
- `^"..."` 按 GDScript 字符串规则转义解码后构造 `NodePath`。
- 引擎绑定层存在 `String → NodePath` 严格转换，所以解释版 `get_node("Foo")` 合法；GDCC 选择不支持该隐式转换。

GDCC 对齐：

- `$` / `%` 静态类型固定为 `GdObjectType("Node")`。
- static 函数与非 Node 派生类中的 `$` / `%` 是**源码错误**（sema `FAILED`），不是 compile-only 限制。
- 已记录 lambda 体经 capture 规划获得与函数体一致的边界：GetNode 视为隐式 `self` 用法。
- property initializer 中的 `$` / `%` 是当前能力缺口（sema `DEFERRED`）：shared analyze 发 `sema.deferred_expression_resolution` warning；compile 模式由 generic published-fact scan 升级为 `sema.compile_check` error，消息形态为 `Expression remains deferred at compile surface and is not lowering-ready in compile mode: <detailReason>`，detailReason 以 `Get-node expression` 开头。

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
  1. property initializer（含 class-level `static var` 初始化器；static 只影响函数体，不把 class-level initializer 判成 static-function `FAILED`）→ `DEFERRED`
  2. lambda body → 消费 overlay-aware `typedEnvironment.lambdaPlan(...)`：
     - plan 缺失属 published-fact 协议破坏，fail-fast
     - `capturesSelf` 且 leading `self` capture 可赋值到 `Node` → `RESOLVED(Node)`
     - 否则 `FAILED`（文案复用函数体：static enclosing / 非 Node 派生）
  3. static 函数 → `FAILED`
  4. 当前类不可赋值到 `Node` → `FAILED`
  5. 否则 `RESOLVED(Node)`
- 不发布 `FrontendResolvedCall`（resolved-call key space 冻结于 `CallExpression` / `AttributeCallStep`）。
- 链头走同一 expression typing 管线，自动获得 Node receiver。

读取 lambda plan 必须 overlay-aware：`putLambdaPlan` 只写当前 typed environment overlay，stable `analysisData.lambdaPlans()` 要等 lambda body 完成后 `exportBatch.applyTo` 才可见。body typing 期间直接读 stable 侧表必缺 plan。禁止为绕过时序而提前直写 stable side table。

### 3.4 Capture

`FrontendVariableAnalyzer.LambdaCaptureSourceScanner.handleGetNodeExpression` 把 `$` / `%` 视为与显式 `SelfExpression` 等价的 enclosing-instance 需求，置 `usesExplicitSelf`。嵌套 lambda 经既有 `childPlan.capturesSelf()` 向上传递 leading `self`。static enclosing 不合成非法 capture，源码诊断由 sema `FAILED` 承担。

lambda shell 合成时 `LirFunctionDef.addCapture` 把 leading `self` capture 注册为同名 function variable；body lowering 的 `requireSelfSlot()` 命中该 captured 本地槽。

### 3.5 Compile gate

`GetNodeExpression` 无表达式级显式 intercept。generic published-fact scan 接管：

- 函数体与已记录 lambda 体内 `RESOLVED(Node)` → 放行
- property initializer `DEFERRED` → 升级为 `sema.compile_check`
- 上游 `FAILED` → 不重复发 `sema.compile_check`

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

---

## 5. 测试与回归锚点

| 层 | 测试类 | 关注点 |
|---|---|---|
| util | `StringUtilTest` | `decodeNodePathLexeme` / `decodeGetNodePathLexeme` 正反用例 |
| LIR | `LiteralNodePathInsnContractTest` | opcode 形状、round-trip、非法 operand |
| backend | `CNewDataInsnGenTest` | 非 ref 物化、转义、缺 result / 错类型 / ref 三类 `invalidInsn` |
| sema | `FrontendExpressionSemanticSupportTest`、`FrontendBodyOwnerProceduresChainBindingTest` | 函数体 / lambda `RESOLVED(Node)`，static / 非 Node `FAILED`，property-init `DEFERRED`，链头 fallback |
| capture | `FrontendVariableAnalyzerTest`、`FrontendLambdaSuiteResolutionTest` | GetNode 触发 leading `self` capture、嵌套传递、static enclosing 不合成 |
| compile gate | `FrontendCompileCheckAnalyzerTest`、`ApiCompileDiagnosticsTest` | 函数体与已记录 lambda 放行；property-init 仍阻断；上游 FAILED 不去重失败 |
| lowering | `FrontendLoweringBodyInsnPassTest`、`FrontendLambdaLoweringTest` | 三指令序列、payload 解码、`%` 前缀、override 钉住、lambda captured `self` 槽 |
| e2e | `GdScriptUnitTestCompileRunnerTest` scene fixtures | `get_node_shorthand_scene.gd`、`get_node_lambda_flow_scene.gd`、`get_node_lambda_await_scene.gd` |

---

## 6. 已知限制与后续范围

1. **property initializer 与 `@onready`**：Godot 的 `@onready var c = $Child` 依赖 deferred-init 语义；gdcc 的 `@onready` 当前仅 skeleton 保留注解。待 `@onready` 真正实现后重新评估。property initializer 内的未记录 lambda 由既有 root 诊断持有，其中的 `$` / `%` 随之 fail-closed。
2. **`isQuotedNodePathLiteral` 的 `$"` 分支**：与 4.5.1 dump 实际编码（`NodePath("")`）不符的 latent defensive code，可独立清理，不在本事实源关键路径。
3. **`get_node` 缺失节点的运行时行为**：GDCC 忠实转发引擎错误，不额外包装。e2e 用 `get_node_or_null` 覆盖缺失路径，避免环境差异。
4. **ref 结果槽的 `literal_node_path`**：当前 fail-closed。若未来出现真实需求，需在 gdcc-owned runtime 增加就地初始化 helper。
