# Frontend GetNode 与 NodePath 字面量实施计划

> 本文档记录 `get_node` 简写（`$` / `%`，AST 层为 `GetNodeExpression`）与 NodePath 字面量（`^"..."`）从 semantics 到 C codegen 的端到端实施计划。`^"..."` 已端到端落地（步骤 1-4）；`$`/`%` 仍被 compile gate / lowering 阻断，本文档保留尚未实现的计划内容，全部落地后应改写为 implementation 文档。

## 文档状态

- 状态：实施中（步骤 1-4 已完成，步骤 5-13 待实施）
- 更新时间：2026-09-01
- 当前事实源：
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`（§3.3 explicit intercept、§7 解除前提）
  - `frontend_lowering_plan.md`（§6 blocker 解除顺序）
  - `frontend_implicit_conversion_matrix.md`（String/NodePath 转换约定）
  - `frontend_container_literal_implementation.md`（专用 item + plan 的 literal 落地模板）
  - `diagnostic_manager.md`（§2.8 feature-boundary diagnostics）
  - `doc/gdcc_low_ir.md`、`doc/gdcc_lir_intrinsic.md`
  - `doc/module_impl/backend/builtin_builder_implementation.md`
  - `doc/module_impl/backend/call_method_implementation.md`

---

## 1. 目标与范围

### 1.1 本期目标

1. NodePath 字面量 `^"path"` 端到端可用（sema → lowering → LIR → C codegen）。
2. `get_node` 简写端到端可用：
   - `$Child`、`$A/B`、`$"Path With Spaces"`
   - `%UniqueName`、`%"Unique Name"`
   - 允许位置：Node 派生类的**非 static 函数体**（含构造函数体），以及这些函数体内的 **lambda 体**（经隐式 `self` capture，步骤 9-12）。
3. `$` / `%` 作为链头（如 `$Foo.bar()`、`%Foo.call()`）经既有 chain fallback 自动获益，不新增专用链头规则。

### 1.2 明确非目标（保持 fail-closed）

| 语法/场景 | 决策 | 理由 |
|---|---|---|
| `String -> NodePath` / `NodePath -> String` 隐式转换 | 维持 GDCC `N`，matrix 不变 | `frontend_implicit_conversion_matrix.md` 已冻结；`get_node("Foo")` 依然类型错误，用户写 `get_node(^"Foo")` 或 `get_node(NodePath("Foo"))` |
| property initializer 中的 `$` / `%`（含 `@onready` 场景） | fail-closed（DEFERRED） | `@onready` 目前仅 skeleton 保留注解，无 deferred-init 语义；构造期调 `get_node` 在 Godot 中也是运行时失败 |
| ~~lambda 函数体中的 `$` / `%`~~ | **计划由步骤 9-12 转为支持**：capture 规划把 GetNode 视为隐式 `self` 用法 | 见 D4 lambda 分支与 §8 backlog 更新；static enclosing callable 与非 Node 派生类中仍 FAILED |
| `$Foo = x` 赋值目标 | 拒绝 | Godot 同样不允许；GetNode 不属于 writable-target family |
| `get_node_or_null` / `has_node` 专用 lowering | 不需要 | 它们是普通 Node 引擎方法，现有 `CallExpression → CallMethodInsn → CALL_METHOD` 路径已稳定（`nested_node_refcounted_scene.gd` 已覆盖） |
| `CBuiltinBuilder.isQuotedNodePathLiteral` 的 `$"..."` 识别 | 本计划不改动（可选清理见 §8） | 该方法服务 extension API dump 默认值物化；4.5.1 dump 中 NodePath 默认值实际编码为 `NodePath("")` constructor 形式，`$"` 分支是 latent defensive code，与 GDScript 源码字面量无关 |

---

## 2. Godot 官方语义基线

以 godotengine/godot 4.x 源码为准：

- parser（`modules/gdscript/gdscript_parser.cpp` `parse_get_node`）产生专用 `GetNodeNode`，把 `$Foo`、`$"A B"`、`%Foo`、`%"A B"` 的路径片段拼入 `full_path`；`%` 前缀保留在路径中（unique-name 查询由运行时处理）。
- analyzer（`gdscript_analyzer.cpp` `reduce_get_node`）：
  - 当前类不是 `Node` 子类 → 报错；
  - static 函数中使用 → 报错；
  - 合法时表达式类型固定为 native `Node`，**不做基于场景文件的具体类型推导**（要具体类型须 `as` cast 或显式声明）。
- compiler（`gdscript_compiler.cpp`）：desugar 为 `self.get_node(NodePath(full_path))` —— 先构造 NodePath 常量，再对 SELF 调 `Node.get_node` method bind。**注意是 `get_node`（缺失节点时报引擎错误），不是 `get_node_or_null`。**
- runtime（`scene/main/node.cpp` `get_node_or_null`）：相对路径从当前节点找，`/` 开头走场景树根，`.` 当前节点，`..` 父节点，`%Name` 走 owner 的 `owned_unique_nodes`；`get_node` = `get_node_or_null` + 失败时报错；`has_node` = `get_node_or_null() != null`。
- `^"..."`（`gdscript_tokenizer.cpp` 的 `STRING_NODEPATH`）：内容按 GDScript 字符串规则转义解码后构造 `NodePath`，路径分隔符 `/`，`:` 起 property subname。
- 引擎方法绑定层面 `String → NodePath` 转换真实存在（`variant.cpp` `can_convert_strict(STRING, NODE_PATH) == true`），所以解释版 `get_node("Foo")` 合法；GDCC 选择不支持该隐式转换（见 §1.2）。

GDCC 语义对齐决策：

- `$` / `%` 表达式静态类型固定为 `Node`（`GdObjectType("Node")`），与 Godot analyzer 一致。
- static 函数与非 Node 派生类中的 `$` / `%` 是**源码错误**（sema FAILED + error 诊断），不是 compile-only 限制。
- lambda 体中的 `$` / `%` 经 capture 规划扩展获得支持（步骤 9-12 落地后）：GetNode 被视为隐式 `self` 用法，lambda capture leading `self`，sema 边界与函数体一致（static enclosing / 非 Node 派生 → FAILED）。
- property initializer 中的 `$` / `%` 是 **GDCC 当前能力缺口**（sema DEFERRED）。诊断形态沿用既有管线：shared analyze 发布一条 `sema.deferred_expression_resolution` **warning**（无 error，见 `FrontendBodyOwnerProcedures.reportExpressionDiagnostic` :1524-1529）；compile 模式由 generic published-fact scan 升级为 `sema.compile_check` error，消息形态为 `Expression remains deferred at compile surface and is not lowering-ready in compile mode: <detailReason>`（`publishedCompileBlockedMessage` :245-258）。detailReason 以 "Get-node expression" 开头，保持与既有诊断断言的连续性。

---

## 3. 现状事实与缺口

### 3.1 Parser（外部 gdparser 0.5.3，不可修改）

- `GetNodeExpression(String sourceText, Range range)`：`sourceText` 保留完整原始文本（含 `$`/`%` 前缀与可选引号），四种形态统一。
- `LiteralExpression(String kind, String sourceText, Range range)`：`^"..."` 映射为 `kind == "node_path"`，`sourceText` 保留完整 lexeme（含 `^` 与引号）。

### 3.2 Sema

- `FrontendChainHeadReceiverSupport.resolveLiteralType`（:433-447）已把 `node_path` 解析为 `GdNodePathType.NODE_PATH`；容器 constant key 经 `FrontendContainerLiteralSemanticSupport.tryDecodeNodePathLexeme` 复用共享的 `StringUtil.decodeNodePathLexeme`（步骤 1 已落地，null-on-miss）。
- `GetNodeExpression` 在 `FrontendExpressionSemanticSupport.resolveRemainingExplicitExpressionType`（:899-905）固定返回 DEFERRED。
- `GetNodeExpression` 作为调用实参时链绑定保持 deferred（`FrontendBodyOwnerProceduresChainBindingTest` :1895-1924 锁定现状）。

### 3.3 Compile gate

- `FrontendCompileCheckAnalyzer.walkExpression`（:675-678）对 `GetNodeExpression` 发显式 `sema.compile_check` 阻断，消息为 `expressionCompileBlockedMessage("Get-node expression")`（:162-166）。
- 该 gate 同时覆盖函数体与 supported property initializer island；lambda 体经 `walkLambdaExpression` 递归覆盖。
- `^"..."` 字面量未被 explicit intercept 拦截，generic published-fact scan 对 RESOLVED 字面量放行；body lowering 已于步骤 4 打通，`^"..."` 端到端可用。

### 3.4 CFG / body lowering

- `FrontendCfgGraphBuilder.buildValue`（:2089-2097）的 leaf family 为 `Identifier / Literal / Self / Preload`；`GetNodeExpression` 落入 `default -> throw unsupportedReachableExpression(...)`。
- `FrontendSequenceItemInsnLoweringProcessors.classifyOpaqueExpression`（:691-694）对 `GetNodeExpression` 返回 DEFER。
- `FrontendLiteralOpaqueExprInsnLoweringProcessor.lower` 已有 `node_path` 分支（步骤 4），产出 `LiteralNodePathInsn`；未知 kind 仍落入 default fail-fast。
- opaque processor 注册表（:47-58）无 GetNode processor。
- Preload 是最接近的模板：`FrontendPreloadOpaqueExprInsnLoweringProcessor`（:347-390）从 AST 直读 literal、`allocateGdScriptLanguageFunctionTemp` 分配临时槽、发 `LiteralStringInsn` + `LoadStaticInsn` + `CallMethodInsn` 指令对，且不发布 `resolvedCalls`。

### 3.5 LIR

- 现有字面量指令：`literal_bool/int/float/string/string_name/node_path/null/nil`（`GdInstruction` :12-19），string 系为 `REQUIRED` + 恰好一个 `STRING` operand，`value` 为解码后 payload；`literal_node_path` 已于步骤 2 落地（`LiteralNodePathInsn`）。
- `ParsedLirInstruction.toConcrete()` 逐 opcode 构造 record（含 `LITERAL_NODE_PATH` 分支）；serializer（`SimpleLirBlockInsnSerializer`）走通用路径，新 opcode 无需 serializer 分支。

### 3.6 C backend / runtime

- `NewDataInsnGen`：按 record 类型 pattern switch 分派；string/string_name 支持非 ref（`godot_new_*_with_utf8_chars` 返回值）与 ref（`godot_string*_new_with_utf8_chars` 就地初始化）；node_path 仅支持非 ref（`godot_new_NodePath_with_utf8_chars`），ref 结果槽 fail-closed（步骤 3 已落地）；StringName 拒绝 raw `&"..."` lexeme（NodePath 刻意不做形状拒绝，见 D1/D6）；结果槽类型严格校验。
- NodePath 构造 helper 已存在于生成绑定：`godot_new_NodePath_with_utf8_chars` 等（`godot_builtin.h` :1447-1462，`godot_builtin.c` :10734-10739，返回值语义，内部经临时 String + `godot_new_NodePath_with_String`）。
- **GDExtension 接口没有 NodePath 的就地 utf8 构造器**（只有 string/string_name 有 `*_new_with_utf8_chars` 初始化未初始化存储的变体），故 ref 结果槽无可复用 helper。
- `CBuiltinBuilder.materializeLiteralValue`（:232-258）服务 API dump 默认值/静态字面量物化：target 为 `GdNodePathType` 的普通 `"..."` 与 `$"..."` 都生成 `godot_new_NodePath_with_utf8_chars(u8"...")`；该路径与 LIR 字面量指令无关，本计划不改其行为。
- `CALL_METHOD` ENGINE 路径完整：receiver fat pointer、引擎方法沿 superclass 链解析（`self.get_node_or_null(...)` 已在 scene 集成测试中稳定工作），NodePath 参数以 `godot_NodePath*` 传递。

---

## 4. 总体设计决策

### D1 — NodePath 字面量走新 LIR 指令 `literal_node_path`

新增 `LiteralNodePathInsn(@Nullable String resultId, @NotNull String value) implements NewDataInstruction`，opcode 合同与 `literal_string_name` 完全对齐：

- `GdInstruction.LITERAL_NODE_PATH("literal_node_path", ReturnKind.REQUIRED, List.of(OperandKind.STRING), 1, 1)`
- `value` 为**已解码 payload**（不含 `^` 与引号）。**backend 不做 raw-lexeme 形状拒绝**：normalized payload 与 raw lexeme 在内容上不可可靠区分（合法源码 `^"^\"foo\""` 的解码结果就是 `^"foo"`，形状检查会误杀），payload-only 合同由 frontend 解码单测与本指令的 operand 约束保证——与 `LiteralStringInsn` 刻意不做形状检查（`NewDataInsnGen` :80-83）一致，不复制 StringName 的启发式防线。
- `ParsedLirInstruction.toConcrete()` 增加构造分支；serializer 走通用路径。
- `NewDataInstruction` 增加 `getAsLiteralNodePathInsn()` accessor（与现有 `getAsLiteralStringInsn()` 同风格）。
- 缺 result 的拒绝点在 backend（`NewDataInsnGen.resolveResultVariable` :143-147），与现有 LIR parser 合同一致：parser 不按 `ReturnKind.REQUIRED` 校验 result（`ConstructContainerLiteralInsnContractTest` :218-228 锁定该行为），本计划不改变通用 parser 合同。

备选方案（`literal_string` + `construct_builtin` 复用 `NodePath(String)`）被否决：多一个 String 临时槽与其生命周期开销，且违背 "literal 直接物化" 的现有指令族语义。

### D2 — 字符串解码集中于 `StringUtil`

遵循 `common_rules.md` 字符串处理约定：

- 新增 `StringUtil.decodeNodePathLexeme(String)`：剥 `^` 前缀后按 GDScript 引号规则解码；非 `^"..."` 形态抛 `IllegalArgumentException`。
- 新增 `StringUtil.decodeGetNodePathLexeme(String)`：
  - `$"..."` → 字符串解码；`$X`（bare）→ `X` 原样（含 `/root/...` 绝对路径的 `/` 前缀）；
  - `%"..."` → `%` + 字符串解码；`%X`（bare）→ `%X`（**保留 `%` 前缀**，unique-name 查询由运行时 Node 实现处理）；
  - 其它形态抛 `IllegalArgumentException`。
- `FrontendContainerLiteralSemanticSupport.tryDecodeNodePathLexeme`（:491-501）重构为调用 `decodeNodePathLexeme`，消除重复解码实现。

### D3 — `^"..."` lowering 走既有 literal processor 分支

`FrontendLiteralOpaqueExprInsnLoweringProcessor` 增加 `case "node_path" -> LiteralNodePathInsn(resultSlotId, StringUtil.decodeNodePathLexeme(sourceText))`。

收益面（无需额外工作）：函数体、property initializer、static var initializer、容器字面量元素（constant key 之外的 value 位置）——所有复用普通 body lowering 的位置自动打通。

### D4 — GetNode sema：RESOLVED(Node) + 边界 fail-closed，不发布 resolvedCalls

分层沿用 await 先例（纯分类在 `FrontendExpressionSemanticSupport`，owner 副作用 hook 在 `FrontendBodyOwnerProcedures`）：

1. owner hook `resolveGetNodeExpressionType(...)` 按序判定：
   - `context.propertyInitializerContext() != null` → DEFERRED（detailReason 以 "Get-node expression" 开头，如 "Get-node expression is not supported inside property initializers"）；
   - callable owner 为 lambda body → 消费该 lambda 已发布的 `FrontendLambdaPlan`：`capturesSelf == true` 且 leading `self` capture 类型可赋值到 `GdObjectType("Node")` → RESOLVED(`GdObjectType("Node")`)；否则 FAILED（enclosing self-source 为 static 时文案同 static 规则；类非 Node 派生时文案同类检查规则）。plan 缺失属 published-fact 协议破坏，fail-fast 而非源码诊断。**读取路径必须 overlay-aware**：`fillAndPublishLambdaPlan` 经 `putLambdaPlan` 只写入当前 typed environment 的 pending/committed overlay（`FrontendTypedLexicalEnvironment` :298-317），stable `analysisData.lambdaPlans()` 要等 lambda body 完成后 `exportBatch.applyTo` 才可见（`FrontendSuiteResolver` :193-200）——body typing 期间直接读 stable 侧表必缺 plan。为此在 `FrontendTypedLexicalEnvironment` 新增 `lambdaPlan(Node)` 读取方法，完全镜像既有 `matchPlan(...)`（:124-131）的 pending → committed → stable → parent 查询链，hook 经 `context.typedEnvironment().lambdaPlan(callableOwner)` 读取。分步落地：步骤 5 先以 DEFERRED 落地该分支，步骤 9-10 翻转为本最终语义；
   - 当前函数为 static → FAILED（"cannot use get-node shorthand in a static function"，Godot parity）；
   - 当前类类型不可赋值到 `GdObjectType("Node")`（`ClassRegistry.checkAssignable`）→ FAILED（Godot parity）；
   - 否则 RESOLVED(`GdObjectType("Node")`)。
2. DEFERRED 的诊断语义遵循既有管线：shared analyze 产生一条 `sema.deferred_expression_resolution` warning（非 error）；compile 模式由 generic scan 升级为 `sema.compile_check` error。FAILED 走 `sema.expression_resolution` error，compile gate 按既有规则不重复发。
3. 不发布 `FrontendResolvedCall`（preload 先例：保持 resolved-call key space 冻结于 `CallExpression`/`AttributeCallStep`）。
4. 链头场景经 `FrontendChainHeadReceiverSupport` 的 `fallbackExpressionReceiverResolver` 走同一 expression typing 管线，自动获得 Node receiver 与同一套边界检查；不新增链头专用 case。实施时必须用一个 `$Foo.bar()` 用例实证该 fallback 确实拾取已发布类型，否则退回为链头显式 case。

### D5 — GetNode lowering：literal_node_path + call_method，receiver 固定上溯到 `Node`

1. CFG：`GetNodeExpression` 加入 `buildValue` leaf family（:2092 case 列表），`routePayloadForOpaqueExpression` 走 default `OpaqueExpressionRoute.empty()`（无 writable route，与 Preload 一致）。
2. sequence 分类器：`GetNodeExpression` → HANDLE_NOW。
3. 新增 `FrontendGetNodeOpaqueExprInsnLoweringProcessor` 并注册：
   - `requireOpaqueOperandCount(item, 0)`；
   - `session.requireSelfSlot()`（self 为 canonical 槽，`emitAssertObjectLiveIfNeeded` 对 `"self"` 本就 no-op，无需调用）；
   - 分配 `GdNodePathType.NODE_PATH` 临时槽 `pathTmp`，发 `LiteralNodePathInsn(pathTmp, StringUtil.decodeGetNodePathLexeme(node.sourceText()))`；
   - **receiver 必须以静态类型 `Node` 发出**，而不是直接使用 GDCC 类类型的 `self` 槽：分配 `GdObjectType("Node")` 临时槽 `receiverTmp`，发 `AssignInsn(receiverTmp, "self")`，再发 `CallMethodInsn(resultSlotId(item), "get_node", receiverTmp, List.of(VariableOperand(pathTmp)))`。
4. **为什么必须上溯 receiver（关键决策）**：`BackendMethodCallResolver.resolve`（:178-189）把 receiver 静态类型交给 `ScopeMethodResolver.resolveInstanceMethod`，后者从 receiver 实际类开始收集候选并选择最近声明 owner（`ScopeMethodResolver` :490-566）；`Node.get_node` 是非 virtual 引擎方法，frontend 不阻止 GDCC 子类声明同签名 `get_node(NodePath)`。若直接用 GDCC 类类型的 `self` 槽，子类 override 会 shadow 引擎方法；而 Godot compiler 对 `$` 固定走 `ClassDB.get_method("Node", "get_node")` 的原生 method bind，绕过任何脚本 override。receiver 槽静态类型上溯为 `Node` 后，候选收集从引擎类 `Node` 开始，GDCC 子类方法不可见，从而钉住 ENGINE route，与 Godot 行为一致。object upcast assign 属于既有基线能力。
5. backend 侧零新增：`call_method` 的 ENGINE 解析在 `Node` 上找到 `get_node(NodePath) -> Node`（extension API hash 2734337346），fat pointer、参数指针传递、返回值包装全部复用现有基础设施。
6. 运行时语义对齐 Godot compiler：用 `get_node`（缺失节点时引擎报错），不提供 `get_node_or_null` 变体。
7. lambda body 上下文零改动：lambda shell 合成时 `LirFunctionDef.addCapture`（:267-273）把 leading `self` capture 注册为同名 function variable，`requireSelfSlot()`（`FrontendBodyLoweringSession` :881-887）直接命中该 captured 本地槽；三指令序列原样适用，`AssignInsn` 从 enclosing-class 类型上溯 `Node` 为既有基线（`CAssignInsnGenTest` :239-264）。前提是 capture 规划已把 GetNode 计为隐式 `self` 用法（步骤 9），否则 `requireSelfSlot` fail-fast 保持防御。

### D6 — backend `literal_node_path`：非 ref 物化，ref fail-closed

`NewDataInsnGen` 注册 `LITERAL_NODE_PATH` 并新增 `emitNodePathLiteral`：

- 结果槽类型必须是 `GdNodePathType`，否则 `invalidInsn`；
- 结果槽缺 result → `invalidInsn`（既有 `resolveResultVariable` 防线）；
- 非 ref：`callAssign(targetOfVar, "godot_new_NodePath_with_utf8_chars", GdNodePathType.NODE_PATH, List.of(u8"..."))`；
- ref：`invalidInsn`（GDExtension 无 NodePath 就地构造器；对齐 `ConstructInsnGen` :196-198 的 fail-closed 先例。frontend lowering 产生的字面量结果槽永远是非 ref 临时槽，该分支仅为防御）。

生命周期：`godot_new_NodePath_with_utf8_chars` 返回 owned value，按普通 value-semantic builtin 由既有 destruct 基础设施回收；`get_node` 返回的 `Node` 为非 RefCounted 引擎对象，fat-pointer 基础设施按既有 CALL_METHOD 合同处理（无新增 ownership 规则）。

### D7 — compile gate：移除显式拦截，generic fact scan 接管

删除 `FrontendCompileCheckAnalyzer.walkExpression` 的 `case GetNodeExpression` 显式阻断（:675-678），改为 `default` 分支的 `markCompileSurfaceNode` + published-fact scan：

- 函数体内 RESOLVED(Node) → 放行；
- property initializer 中的 DEFERRED → generic scan 发 `sema.compile_check`（diagnostic owner 与去重规则不变）；
- lambda 体内：Node 派生 instance 上下文 RESOLVED → 放行；static / 非 Node 派生上下文 FAILED → 上游 sema 诊断已报错，gate 去重不重复发（lambda 分支的 DEFERRED 仅为步骤 7 至步骤 10 之间的过渡态）；
- static / 非 Node 类的 FAILED → 上游 sema 诊断已报错，gate 去重不重复发。

解除前提核对（`frontend_compile_check_analyzer_implementation.md` §7）：lowering 完成（D5）、backend 可消费（既有 CALL_METHOD + D6）、happy/failure/diagnostic/生命周期测试（§5/§6）、文档同步（§7）——全部满足后才允许合入。

### D8 — NodePath 与 String/StringName 的转换矩阵不变

`String ↔ NodePath` 维持 GDCC `N`；`NodePath` 不加入 `call_func` inbound 兼容转换的 String/StringName 家族。container literal 的 NodePath constant key 等价类不变。`String as NodePath` 继续走既有 explicit `builtin_cast` 路径。

---

## 5. 分步骤实施与验收细则

每一步都可单独编译、单独回归、单独提交。测试命令统一使用 `script/run-gradle-targeted-tests.sh --tests <Class>`（多类逗号分隔）。

### 步骤 1：`StringUtil` 解码 helper 集中化

> 状态：已完成（2026-09-01）。`StringUtil.decodeNodePathLexeme` / `decodeGetNodePathLexeme` 落地，`tryDecodeNodePathLexeme` 重构为共享解码 + null-on-miss；测试 `StringUtilTest`、`FrontendContainerLiteralSemanticSupportTest`（新增 NodePath 解码等价/畸形 key 用例）及容器字面量回归全绿。

改动点：

- `src/main/java/gd/script/gdcc/util/StringUtil.java`：新增 `decodeNodePathLexeme` 与 `decodeGetNodePathLexeme`（含各自的 malformed fail-fast）。
- `FrontendContainerLiteralSemanticSupport.tryDecodeNodePathLexeme` 重构为复用 `decodeNodePathLexeme`（保持其 `null`-on-miss 契约：包一层 try/catch 或提供 try 变体）。

验收细则：

- happy path：`^"a/b"` → `a/b`；`^"a\"b"` 转义解码；`$Camera3D` → `Camera3D`；`$"A B"` → `A B`；`$/root/X` → `/root/X`；`%Foo` → `%Foo`；`%"A B"` → `%A B`。
- negative path：非 `^"` 开头的 `decodeNodePathLexeme` 输入、非 `$`/`%` 开头的 `decodeGetNodePathLexeme` 输入抛 `IllegalArgumentException`；容器重复 key 检测行为不变（现有 container literal 测试全绿）。
- 测试：`StringUtil` 既有/新增单测 + `FrontendContainerLiteral*Test` 回归。

### 步骤 2：LIR `literal_node_path` 指令

> 状态：已完成（2026-09-01）。`GdInstruction.LITERAL_NODE_PATH`、`LiteralNodePathInsn`、`NewDataInstruction.getAsLiteralNodePathInsn()`、`ParsedLirInstruction.toConcrete()` 分支落地；`doc/gdcc_low_ir.md` 补合同（decoded payload、NodePath 结果类型、backend 拒绝 ref 结果槽）。测试 `LiteralNodePathInsnContractTest` 全绿。

改动点：

- `GdInstruction`：新增 `LITERAL_NODE_PATH`（REQUIRED，1×STRING）。
- 新增 `lir/insn/LiteralNodePathInsn.java` record。
- `NewDataInstruction`：新增 `getAsLiteralNodePathInsn()`。
- `ParsedLirInstruction.toConcrete()`：新增分支。
- `doc/gdcc_low_ir.md`：字面量指令小节补 `literal_node_path` 合同（payload 语义、结果类型、ref 限制见 D6）。

验收细则：

- happy path：`$r = literal_node_path "a/b"` parse → `LiteralNodePathInsn("r", "a/b")`；serialize/parse round-trip 保持 payload（含引号、空格、UTF-8）。
- negative path：operand 非 STRING、operand 数量错误在 parse/contract 层拒绝；缺 result 时 parse 放行（通用 parser 不按 `ReturnKind.REQUIRED` 校验 result，见 D1），由 backend `NewDataInsnGen` 拒绝。
- 测试：仿 `ConstructContainerLiteralInsnContractTest` 新增 contract 测试；`--tests "*LiteralNodePath*"`。

### 步骤 3：backend `literal_node_path` codegen

> 状态：已完成（2026-09-01）。`NewDataInsnGen` 注册 opcode、新增 `emitNodePathLiteral`（非 ref → `godot_new_NodePath_with_utf8_chars`；ref → fail-closed）与 `validateResultType` NodePath 校验；`builtin_builder_implementation.md` §5.2 补 `^"..."` 行并标注 `$"..."` 为 API-default 遗留分支。测试 `CNewDataInsnGenTest` 全绿。

改动点：

- `NewDataInsnGen`：`getInsnOpcodes()` 注册；`generateCCode` 新增 `LiteralNodePathInsn` case；`validateResultType` 新增 NodePath 校验；ref 结果槽拒绝。**不新增** raw-lexeme 形状防线（理由见 D1）。
- `doc/module_impl/backend/builtin_builder_implementation.md`：字面量物化表补充说明 `^"..."` 经 LIR 指令路径物化、`$"..."` 行标注为 API-default 遗留分支（与实际 dump 的 `NodePath("")` 编码区分）。

验收细则：

- happy path：`literal_node_path` 结果生成 `godot_new_NodePath_with_utf8_chars(u8"...")`；payload 中的引号/反斜杠/非 ASCII 正确转义为 C 字面量；payload `^"foo"`（合法解码结果）正常物化不被误杀。
- negative path：结果槽类型非 NodePath、缺 result、ref 结果槽三种均 `invalidInsn`。
- 测试：仿 `CNewDataInsnGenTest` StringName 段新增断言；`--tests CNewDataInsnGenTest`。

### 步骤 4：`^"..."` frontend lowering 打通

> 状态：已完成（2026-09-01）。`FrontendLiteralOpaqueExprInsnLoweringProcessor` 新增 `case "node_path"`，经 `StringUtil.decodeNodePathLexeme` 产出 `LiteralNodePathInsn`。`FrontendLoweringBodyInsnPassTest` 新增 9 用例（函数体/property-init/static-var 三上下文、`get_node_or_null`/`has_node` 实参、Dictionary value 元素、String 目标类型错误不变、畸形 lexeme 与未知 literal kind 双 fail-fast），全类 195 测试全绿。

改动点：

- `FrontendLiteralOpaqueExprInsnLoweringProcessor`：新增 `case "node_path"`。
- 无需动 compile gate（字面量本就未被 explicit intercept）。

验收细则：

- happy path：函数体 / property initializer / static var 中的 `^"a/b"` 产出 `LiteralNodePathInsn`，结果槽类型 `NodePath`；作为 `get_node_or_null(^"SceneChild")`、`has_node(^"...")` 实参与 typed Dictionary key 之外的 value 元素均可用。
- negative path：hand-built 非法 literal kind 仍走 default 报错；`var s: String = ^"a"` 维持类型错误（matrix 不变）。
- 测试：lowering 断言仿 `FrontendLoweringBodyInsnPassTest` 字面量段；`--tests FrontendLoweringBodyInsnPassTest`。

### 步骤 5：GetNode sema

改动点：

- `FrontendExpressionSemanticSupport`：`GetNodeExpression` 从 deferred 枚举移出，接入新 `resolveGetNodeExpressionType`（纯分类部分）。
- `FrontendBodyOwnerProcedures`：新增 owner hook，提供 property-init / lambda / static / 类继承判定上下文。
- 诊断沿用既有 expression-outcome → diagnostic 管线（FAILED → `sema.expression_resolution` error；DEFERRED → `sema.deferred_expression_resolution` warning，见 §2 的诊断形态说明）。

验收细则：

- happy path：`extends Node` 类的 instance 函数中 `$Camera3D`、`%Foo` 类型为 RESOLVED(Node)；`$Foo.bar()` 链头经 fallback 解析为 Node receiver（必须用独立用例实证，见 D4.4）。
- negative path：static 函数中 → FAILED；`extends RefCounted` 类中 → FAILED；property initializer 中 → DEFERRED（analyze：无 error、恰好一条 deferred warning）；lambda 体中 → DEFERRED（同形态；**过渡态**，步骤 9-10 将其翻转为 D4 lambda 分支的最终语义）。
- 测试更新：
  - `FrontendExpressionSemanticSupportTest` :1517-1559 的 deferred 枚举移除 GetNode case，新增 dedicated 正/反用例。
  - `FrontendBodyOwnerProceduresChainBindingTest` :1895-1924 的现有 fixture（`extends RefCounted` + `build(value: int)` + `self.build($Camera3D).length`）在新规则下必然 FAILED（RefCounted 非 Node 派生；即使改成 Node，`build(int)` 也不接受 Node 实参），**不能**直接改断言为 RESOLVED。迁移方式：
    1. 正向实参用例：fixture 改为 `extends Node` + `func build(value: Node)`，断言链解析为 RESOLVED；
    2. 保留 `extends RefCounted` 的负向用例，断言 GetNode FAILED 与上游 `sema.expression_resolution` error；
    3. GetNode 作为链头（`$Foo.bar()`）与作为实参分开覆盖。
  - `FrontendCompileCheckAnalyzerTest` :1697-1727（`analyzeForCompileUpgradesDeferredWarningsIntoCompileBlockingErrors`）使用同一 RefCounted fixture 锁定 deferred 升级行为，也必须同步迁移：RefCounted fixture 下 GetNode 变为 FAILED（shared analyze 直接出 error，不再是 deferred warning 升级路径），该用例需改写为新语义或换用其它仍可产生 DEFERRED 的 fixture。
  - `--tests FrontendExpressionSemanticSupportTest,FrontendBodyOwnerProceduresChainBindingTest,FrontendCompileCheckAnalyzerTest`。

### 步骤 6：GetNode CFG + body lowering

改动点：

- `FrontendCfgGraphBuilder.buildValue` leaf family 加入 `GetNodeExpression`。
- `FrontendSequenceItemInsnLoweringProcessors.classifyOpaqueExpression`：GetNode → HANDLE_NOW。
- `FrontendOpaqueExprInsnLoweringProcessors`：新增并注册 `FrontendGetNodeOpaqueExprInsnLoweringProcessor`（D5.3）。

验收细则：

- happy path：`$Camera3D` 的 LIR 恰为三条指令——`LiteralNodePathInsn(pathTmp, "Camera3D")`、`AssignInsn(receiverTmp, "self")`（receiverTmp 静态类型 `Node`）、`CallMethodInsn(result, "get_node", receiverTmp, [pathTmp])`；结果槽类型 Node；`%` 形态的 path payload 以 `%` 开头。
- override 钉住回归：GDCC 子类声明 `func get_node(p: NodePath)` 时，`$Child` 仍必须解析到 ENGINE `Node.get_node`（receiver 槽静态类型为 `Node`）；同时源码中显式的 `self.get_node(...)` 调用保持普通解析（允许子类 override 生效），两者行为差异须有测试锁定。
- negative path：hand-built 携带 operand 的 GetNode opaque item → `requireOpaqueOperandCount` fail-fast；无 `self` 槽的 context → `requireSelfSlot` fail-fast（防御分支，正常管线不可达）。
- 测试：lowering 断言仿 preload 段（`FrontendGdScriptLanguageFunctionLoweringTest` 的 load 用例风格）；`--tests "*Lowering*"` 相关类。

### 步骤 7：compile gate 解除与既有阻断测试迁移

改动点：

- 删除 `FrontendCompileCheckAnalyzer` 的 `case GetNodeExpression` 显式阻断；`expressionCompileBlockedMessage("Get-node expression")` 若不再有调用方则一并清理。
- 既有测试迁移：
  - `FrontendCompileCheckAnalyzerTest` :104-153：函数体内 `$Camera3D` 不再产生 blocker；:568-593 property-init 用例保持阻断，诊断变为 generic-scan 形态（`Expression remains deferred at compile surface and is not lowering-ready in compile mode: Get-node expression is not supported inside property initializers`）；:937-1028 的 match/for 内 GetNode 用例（`extends Node` 的 instance 函数体，GetNode 已 RESOLVED）翻转为放行、断言无 `sema.compile_check`；:2123-2162 的 lambda 内 GetNode 用例按本步骤的过渡边界（lambda 内 DEFERRED → generic-scan 阻断）更新，步骤 12 再翻转为最终边界；:1697-1727 的迁移方式见步骤 5。
  - `ApiCompileDiagnosticsTest` :52-81、:113-138：`var camera = $Camera3D` 是 property initializer → 仍被 generic scan 阻断，category 保持 `sema.compile_check`；detailReason 以 "Get-node expression" 开头（D4.1），因此既有 `contains("Get-node expression")` 消息断言可以继续通过，实施时核验而非想当然；补充函数体内 `$Camera3D` 编译成功（RecordingCompiler 被调用）的正例。

验收细则：

- happy path：Node 派生类 instance 函数体中的 `$`/`%`/`^"..."` 全链路 compile 通过（RecordingCompiler 计数 > 0）。
- negative path：property-init / lambda 中的 `$` 仍为 `sema.compile_check` error（generic 文本形态；lambda 分支为过渡态，步骤 12 翻转），且 shared analyze 侧仅一条 deferred warning 无 error；static/非 Node 中的 `$` 为上游 `sema.expression_resolution` error 且 gate 不重复发 `sema.compile_check`。
- 测试：`--tests FrontendCompileCheckAnalyzerTest,ApiCompileDiagnosticsTest`。

### 步骤 8：e2e test_suite fixture

改动点：

- 新增 `src/test/test_suite/unit_test/script/scene/get_node_shorthand_scene.gd` + 同名 validation 脚本：
  - `add_child` 挂载普通子节点与带空格名的子节点；
  - unique-name 子节点必须按顺序建立关系（运行时用代码创建的节点默认没有 `owner`，不登记进 owner 的 unique-node map；缺少 `owner` 时设置 `unique_name_in_owner` 不会生效）：`add_child(unique_child)` → `unique_child.owner = self` → `unique_child.unique_name_in_owner = true`；带空格的 unique-name 节点同样设置 owner，或让 `%"..."` 与 `%Name` 引用同一已登记节点；
  - 断言 `$Child`、`$"Name With Space"`、`%Unique`、`%"Unique Name"` 返回非空且类型为 Node；
  - 断言 `get_node_or_null(^"Missing") == null`、`has_node(^"Child") == true`；
  - 断言 `$Child.name` 链头属性读取。
- `GdScriptUnitTestCompileRunnerTest.EXPECTED_SCRIPT_PATHS` 登记新 fixture（缺失会导致资源集合测试失败）。
- `doc/test_error/test_suite_engine_integration_known_limits.md` 补记该 fixture 锚点。

验收细则：

- happy path：Zig + `GODOT_BIN` 可用时 `GdScriptUnitTestCompileRunnerTest` scene category 动态测试通过（validation 断言全绿）；环境缺失时按既有 Assumptions 机制跳过。
- negative path：validation 对缺失节点路径断言 `get_node_or_null` 返回 null 而非引擎报错崩溃（`get_node_or_null` 路径）；`$` 缺失路径的引擎报错行为不在 validation 中触发（避免环境差异）。
- 测试：`--tests GdScriptUnitTestCompileRunnerTest`。

### 步骤 9：capture discovery 把 GetNodeExpression 视为隐式 `self` 用法

改动点：

- `FrontendVariableAnalyzer.LambdaCaptureSourceScanner`（:995-1066）：新增 `handleGetNodeExpression` override（`ASTNodeHandler` :166 提供默认实现），置 self-need 标志——直接复用 `usesExplicitSelf` 字段（语义即"body 需要 enclosing instance receiver"，与显式 `self` 表达式完全等价；可选改名为 `needsSelfCapture`，非必须）。`GetNodeExpression` 是无子表达式的叶子（gdparser 定义为 `record GetNodeExpression(String sourceText, Range)`），返回 `CONTINUE`。
- 嵌套传递零改动：内层 lambda 因 GetNode 获得 leading `self` capture 后，既有 `childPlan.capturesSelf()` 传播逻辑（`FrontendVariableAnalyzer` :412-414）自动让外层 lambda 补 leading self。
- `buildSelfCaptureEntry` 零改动：enclosing self-source 为 static（或 owning class 不可判定）时返回 null → 不合成非法 capture，源码诊断留给步骤 10 的 sema FAILED 分支承担（§3.9 "静态上下文捕获 self" 既有分工）。

验收细则：

- happy path：instance 函数内 `var cb := func(): return $Child` 的 capture plan 恰为 leading `self`（类型为 enclosing class 的 `GdObjectType`），`capturesSelf == true`；lambda 同时含显式 `self` 或其它隐式实例成员使用时仍只有一个 leading `self`（按名去重）；嵌套 `func(): return func(): return $Child` 两层 plan 均为 leading self。
- negative path：static 函数内 lambda 含 `$Child` → plan 无 self capture（不合成非法 capture）；不含 GetNode 的 lambda plan 完全不变（无回归）。
- 测试：`FrontendVariableAnalyzer` capture 相关测试类新增用例 + `FrontendLambdaLoweringTest` 的 shell 断言风格（:139-166）；`--tests "*LambdaCapture*","FrontendLambdaLoweringTest"`。
- 落地顺序说明：本步骤单独合入是 inert 的——lambda 内 GetNode 仍是 sema DEFERRED（步骤 5 落地态），compile 被 generic scan 阻断，capture plan 变化不影响任何可编译程序；必须在步骤 10 之后才允许 lambda 内 GetNode 通过 compile。

### 步骤 10：GetNode sema lambda 分支解除 DEFERRED

改动点：

- `FrontendTypedLexicalEnvironment`：新增 `lambdaPlan(@NotNull Node astNode)` 读取方法，镜像 `matchPlan(...)`（:124-131）的 pending → committed → stable → parent 查询链。这是 body typing 期间唯一正确的 plan 读取路径：`putLambdaPlan` 只写 overlay（:298-317），stable `analysisData.lambdaPlans()` 要等 lambda body 完成后的 `exportBatch.applyTo`（`FrontendSuiteResolver` :197-200）才可见；直接读 stable 侧表会把合法 lambda GetNode 误判为 plan 缺失。**禁止**为绕过时序而提前直写 stable side table——那会破坏 callable export-batch 合同。
- `FrontendBodyOwnerProcedures` 的 `resolveGetNodeExpressionType` hook：lambda body 分支从 DEFERRED 翻转为 D4 所述最终语义——经 `context.typedEnvironment().lambdaPlan(callableOwner)` 消费已发布的 `FrontendLambdaPlan`：
  - plan 缺失 → published-fact 协议破坏，fail-fast（非源码诊断）；
  - `capturesSelf == true` 且 leading `self` capture 类型可赋值到 `GdObjectType("Node")`（`ClassRegistry.checkAssignable`）→ RESOLVED(Node)；
  - 否则 FAILED；诊断文案按 enclosing self-source 形态区分（`plan.enclosingCallable()` 可判定 static），与函数体规则复用同一套文案（static enclosing → 同 static 规则；类非 Node 派生 → 同类检查规则），不为 lambda 单开平行语义。leading-self 不变量由 `FrontendLambdaCapturePlan` 构造器（:23-43）强制，无需重复校验顺序。
- 消费 plan 而非重新判定 enclosing callable 的理由：capture plan 是 inventory 阶段对"self 是否可得"的单一事实源（`buildSelfCaptureEntry` 已封装 static/owning-class 判定），sema 重复推导会产生两处真相漂移风险。

验收细则：

- happy path：`extends Node` 的 instance 函数内 lambda 中 `$Child` → RESOLVED(Node)，analyze 无 error 且无 deferred warning；链头 `$Foo.bar()` 与实参 `foo($Child)` 位置同函数体规则。
- negative path：static 函数内 lambda → FAILED（`sema.expression_resolution` error）；`extends RefCounted` 类 instance 函数内 lambda → FAILED；property initializer 中的 lambda 属 unrecorded 子树，由既有 unrecorded-lambda root 诊断持有（`sema.unsupported_binding_subtree` / lambda root 表达式 typing 的 `sema.unsupported_expression_route`）；body 遍历在该子树剪枝，不进入 lambda body，因此其中的 `$`/`%` 不产生嵌套 GetNode 诊断，也不追加 `sema.compile_check`。
- 测试：`FrontendExpressionSemanticSupportTest` 的 lambda GetNode 用例从 DEFERRED 断言翻转为正/反断言；`--tests FrontendExpressionSemanticSupportTest`。

### 步骤 11：lambda 内 GetNode lowering 与防御回归

无新处理器代码（机制论证见 D5.7）。本步骤只做测试与防御断言钉住：

- lowering 测试：含 `$Child` 的 lambda 的全 pipeline LIR——外层函数 `construct_lambda` 带 leading `$self` operand；lambda body 内恰为 D5 三指令序列且 `assign` 源槽为 `"self"`（captured 本地槽）；结果槽类型 Node。
- 嵌套 lambda：两层 `<captures>` 均 leading self，外层 `construct_lambda` operand 序与 plan 一致。
- coroutine lambda（body 含 `await` + `$`）：走 lambda 文档 §3.8 协程帧 capture 路径（`_coro_capture_self` 首位），frontend LIR 与同步形态一致（读本地 `self` 槽），补一条回归测试锁定。
- 防御分支：hand-built 缺 self capture 的 lambda plan + 含 GetNode 的 body → body lowering `requireSelfSlot` fail-fast（协议破坏路径，非源码诊断）。
- override 钉住的 lambda 变体：GDCC 子类声明 `func get_node(p: NodePath)` 时，lambda 内 `$Child` 仍解析到 ENGINE `Node.get_node`（receiver 槽静态类型为 `Node`，机制同步骤 6）。

验收细则：

- happy path：上述 LIR 结构断言全中。
- negative path：防御 fail-fast 用例抛出 `IllegalStateException`。
- 测试：`FrontendLambdaLoweringTest` 及 `*Lowering*` 相关类；`--tests FrontendLambdaLoweringTest`。

### 步骤 12：lambda GetNode compile gate 放行与 e2e

改动点：

- compile gate 零代码改动：lambda 内 GetNode 经步骤 10 发布 RESOLVED，generic published-fact scan 不再命中 DEFERRED，自动放行；FAILED 形态由上游 `sema.expression_resolution` 承担，gate 去重不重复发。
- 既有测试二次迁移：仅 `FrontendCompileCheckAnalyzerTest` :2123-2162 的 lambda 内 GetNode 用例需要从过渡阻断翻转为放行（match/for 用例 :937-1028 在步骤 7 已随函数体放行，不属于本步骤）。同时新增 static 函数内 lambda 与 `extends RefCounted` 类内 lambda 两条 negative fixture：断言上游 `sema.expression_resolution` error 且无 `sema.compile_check` 重复。若步骤 9-12 与步骤 5-8 同批合入，:2123-2162 可直接按最终边界书写以避免二次翻转。
- `ApiCompileDiagnosticsTest`：补 lambda 内 `$Camera3D` 编译成功正例（RecordingCompiler 计数 > 0）。
- e2e：`get_node_shorthand_scene.gd` 增加 lambda 段——`var cb := func(): return $Child` 与 `func(): return %Unique`，`cb.call()` 断言返回非空且类型为 Node。

验收细则：

- happy path：lambda 内 `$`/`%` 全链路 compile 通过且 e2e 断言正确。
- negative path：static 函数内 lambda 的 `$` 为上游 sema error，无 `sema.compile_check` 重复；`extends RefCounted` 类内 lambda 同。
- 测试：`--tests FrontendCompileCheckAnalyzerTest,ApiCompileDiagnosticsTest,GdScriptUnitTestCompileRunnerTest`。

### 步骤 13：文档同步

- 本文档改写为 implementation 文档（或标注已落地段落）。
- `frontend_compile_check_analyzer_implementation.md`：§3.3 explicit intercept 列表移除 GetNode、§7/§8/§9 同步。
- `frontend_rules.md`：移除 GetNode temporary compile intercept 表述。
- `diagnostic_manager.md` §2.8：更新“当前唯一 expression-level temporary intercept”表述。
- `frontend_lowering_plan.md` §6：blocker 解除顺序中 GetNode 标注完成。
- `frontend_lambda_implementation.md` §3.4/§3.5：补记 GetNodeExpression 是隐式 `self` 用法来源之一（capture discovery 的 `handleGetNodeExpression`），lambda body 中 `$`/`%` 的支持边界（static enclosing / 非 Node 派生 → 上游 sema FAILED）与 lowering 机制（captured `self` 本地槽 + 上溯 assign）。
- `doc/gdcc_low_ir.md`：`literal_node_path` 合同（步骤 2 已做，此处复核）。

---

## 6. 回归测试锚点总表

| 层 | 测试类 | 关注点 |
|---|---|---|
| util | `StringUtilTest`（或既有位置） | 两个 decode helper 正反用例 |
| LIR | 新增 `LiteralNodePathInsnContractTest` | opcode 形状、round-trip、非法拒绝 |
| sema | `FrontendExpressionSemanticSupportTest`、`FrontendBodyOwnerProceduresChainBindingTest` | RESOLVED(Node)、边界 FAILED/DEFERRED、链头/实参、lambda 分支 |
| capture | `FrontendVariableAnalyzer` capture 测试类、`FrontendLambdaLoweringTest` | GetNode 触发 leading `self` capture、嵌套传递、static enclosing 不合成 |
| compile gate | `FrontendCompileCheckAnalyzerTest`、`ApiCompileDiagnosticsTest` | 函数体与 lambda 放行、property-init 仍阻断、去重 |
| lowering | `FrontendLoweringBodyInsnPassTest`、preload 所在测试类、`FrontendLambdaLoweringTest` | 指令对、槽类型、payload 解码、lambda 内三指令序列与 captured `self` 槽 |
| backend | `CNewDataInsnGenTest` | C 调用形态、转义、三类 invalidInsn |
| e2e | `GdScriptUnitTestCompileRunnerTest`（scene） | Godot 运行时行为 |

---

## 7. 文档同步清单

合入前必须全部完成（对应 compile gate 解除前提）：

1. `frontend_compile_check_analyzer_implementation.md`
2. `frontend_rules.md`
3. `diagnostic_manager.md`
4. `frontend_lowering_plan.md`
5. `frontend_lambda_implementation.md`（§3.4/§3.5 补记 GetNodeExpression 为隐式 `self` 用法来源及 lambda 内 `$`/`%` 边界）
6. `doc/gdcc_low_ir.md`
7. `doc/module_impl/backend/builtin_builder_implementation.md`
8. `doc/test_error/test_suite_engine_integration_known_limits.md`
9. 本计划文档

---

## 8. 风险、开放问题与后续 backlog

1. ~~**lambda 体内的 `$` / `%`**~~：计划由步骤 9-12 解决（capture discovery 把 GetNodeExpression 计为隐式 `self` 用法，leading `self` capture 不变量沿用 §3.5）。
2. **property initializer 与 `@onready`**：Godot 的 `@onready var c = $Child` 依赖 deferred-init 语义；gdcc 的 `@onready` 当前仅 skeleton 保留注解。待 `@onready` 真正实现后重新评估。注意 property initializer 内的 lambda 属 unrecorded 子树（由既有 root 诊断 `sema.unsupported_binding_subtree` / `sema.unsupported_expression_route` 持有，body 剪枝不进入），其中的 `$`/`%` 也随之保持 fail-closed，不在步骤 9-12 范围。
3. **`isQuotedNodePathLiteral` 的 `$"` 分支**：与 4.5.1 dump 实际编码（`NodePath("")`）不符的 latent defensive code。可在独立小 PR 中移除或对齐，不在本计划关键路径。
4. **`get_node` 缺失节点的运行时行为**：Godot 的 `get_node` 对缺失路径报引擎错误并返回 null（解释版随后可能 null deref）。GDCC 忠实转发引擎行为，不额外包装；若未来需要更友好的编译期诊断，可在 engine integration 层单独立项。
5. **ref 结果槽的 `literal_node_path`**：当前 fail-closed。若未来出现真实需求（如 capture 槽直写），可在 gdcc-owned runtime header 增加 `gdcc_node_path_new_with_utf8_chars(godot_NodePath *out, const char *contents)` 就地初始化 helper。
