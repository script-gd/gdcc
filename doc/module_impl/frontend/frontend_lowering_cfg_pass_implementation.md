# Frontend Lowering CFG Pass 实现说明

> 本文档作为 frontend lowering 中 CFG build / executable-body materialization 的长期事实源，记录当前已经稳定落地的 frontend-only CFG graph、`FrontendLoweringBuildCfgPass` 与 `FrontendLoweringBodyInsnPass` 的职责边界、published-fact 消费合同、constructor materialization 与 compound assignment 的当前实现，以及对后续扩面仍然有效的约定。本文档吸收并取代原 `frontend_lowering_cfg_graph_plan.md` 与 `frontend_lowering_cfg_pass_plan.md` 的已完成内容，不再保留实施步骤、阶段状态或验收流水账。

## 文档状态

- 状态：事实源维护中（executable-body CFG build / body lowering、property-initializer CFG/body lowering、constructor materialization、compound assignment、explicit self assignment-target prefix consumption、dynamic receiver runtime-gated writeback、`StopNode.kind` 空-return 图修复、`for-in` CFG build（`FrontendForRegion` / 四个 `ForLoop*Item` / source-slot / hidden-state registry / build-artifact 跨表验证）与 `for-in` range route body lowering（hidden-state / source-slot 预声明 + 四个 `ForLoop*Item` processor 生成 `gdcc.for_range_iter.*` intrinsic 与 temp-then-commit assign）均已落地；parameter default 仍未接通）
- 更新时间：2026-07-25
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/cfg/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/pass/**`
  - `src/test/java/gd/script/gdcc/frontend/lowering/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `frontend_void_call_result_behavior.md`
  - `frontend_lowering_plan.md`
  - `frontend_lowering_func_pre_pass_implementation.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_analysis_inspection_tool_implementation.md`
  - `frontend_loop_control_flow_analyzer_implementation.md`
  - `frontend_complex_writable_target_implementation.md`
  - `diagnostic_manager.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
- 明确非目标：
  - 不在这里引入 high-level IR / sea-of-nodes
  - `ConditionalExpression` 的 compile-ready 全链路（compile gate 放行、body lowering 端到端与 e2e）不由本文档管辖，见 `frontend_conditional_expression_implementation.md`；本文档只冻结其相关的 CFG 构图事实与 merge 合同
  - 不在这里把 parameter default 接到 body pass
  - 不在这里让 lowering 重跑 chain reduction、call route 选择或表达式求值顺序推导

---

## 1. 当前定位

当前默认 frontend lowering pipeline 固定为：

1. `FrontendLoweringAnalysisPass`
2. `FrontendLoweringClassSkeletonPass`
3. `FrontendLoweringFunctionPreparationPass`
4. `FrontendLoweringBuildCfgPass`
5. `FrontendLoweringBodyInsnPass`

这条链路的稳定入口和前置条件是：

- lowering 入口固定为 `FrontendModule`
- compile-ready 语义事实统一来自 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
- 只有在 `analysisData.diagnostics().hasErrors() == false` 时 lowering 才继续
- function-shaped lowering 单元统一经由 `FunctionLoweringContext`
- public lowering 返回值是“executable body 与 compile-ready property-init helper 都已 materialize”为真实函数体的 `LirModule`

当前 pipeline 的产物边界固定为：

- `EXECUTABLE_BODY`
  - 发布 frontend CFG graph
  - 发布 frontend CFG regions
  - materialize real `LirBasicBlock` / terminator / instruction
- `PROPERTY_INIT`
  - 发布 expression-rooted frontend CFG graph
  - 不伪造 `Block` 或 property-init-only node kind
  - 复用同一套 body lowering session materialize `LirBasicBlock` / instruction
- `LAMBDA_BODY`
  - 复用 executable-block 构图与共享 body session materialize lambda body
  - 合成 shell 的 capture 变量已由 `addCapture` 预登记，body 内 CAPTURE 读取走 opaque 符号路由
- `PARAMETER_DEFAULT_INIT`
  - 只保留 context kind 与模型槽位，不接入默认 pipeline

---

## 2. Legacy 迁移结论

旧的 `FrontendLoweringCfgPass` 与 `FunctionLoweringContext.cfgNodeBlocks` 已经从默认 pipeline 与代码中移除。它们只表达过 metadata-only block bundle，不再是当前实现的一部分。

这次迁移里真正保留下来的稳定结论只有三条：

- frontend CFG 必须继续以 `FunctionLoweringContext` 为函数级 carrier，而不是发明第二套 lowering 入口
- AST identity keyed side table 方向是正确的，region / semantic anchor 继续沿用 parser 节点 identity
- compile gate 负责拦截 non-lowering-ready surface；lowering 不重复扫描源码去补第二套编译阻断

旧 block-bundle 方案已经证明不足以承担当前 lowering：

- 不能表达显式 `conditionEntryId`
- 不能表达 source truthy condition 到 bool-only branch 的 normalization 过渡
- 不能表达 `and` / `or` 的 condition/value 双路径短路
- 不能表达 `while` 中 `break` / `continue` 的稳定跳转语义
- 不能为 `ConditionalExpression` 预留 branch-result merge 入口

因此当前长期约束是：

- 新增 lowering 需求只能落在 `frontend.lowering.cfg` + `FrontendLoweringBuildCfgPass` + `FrontendLoweringBodyInsnPass`
- 不得恢复或重建 `CfgNodeBlocks` 风格的过渡 side table
- 不得让 graph 与 legacy block bundle 双写并长期漂移

---

## 3. Frontend CFG 模型

frontend CFG 是 frontend-only 中间层，位于真实语义事实与 LIR basic block 之间。它只负责整理 source-level control flow、value flow 与 condition-evaluation-region，不承担读写 runtime instruction 的最终责任。

### 3.1 Node 形状

当前 `FrontendCfgGraph` 固定为三种 node：

- `SequenceNode`
  - `id`
  - `items`
  - `nextId`
- `BranchNode`
  - `id`
  - `conditionRoot`
  - `conditionValueId`
  - `trueTargetId`
  - `falseTargetId`
- `StopNode`
  - `id`
  - `kind`
  - `returnValueIdOrNull`

`StopNode.kind` 当前固定为两类：

- `RETURN`
  - 真实 callable exit
  - `returnValueIdOrNull` 可为空，表示 bare `return` 或 `nil`-equivalent return 路径
- `TERMINAL_MERGE`
  - 仅用于 frontend CFG 内部表达“该结构化分支链已全部终止”的 synthetic anchor
  - 不代表真实源码 `return`
  - 不得携带 `returnValueIdOrNull`
  - 不得作为 graph entry，也不得作为任何 executable edge 的 target；它只允许通过 region `mergeId` 被结构化 side table 引用

fully-terminated 的 `if` / `elif` / `else` 允许把 region `mergeId` 指向 `StopNode(kind = TERMINAL_MERGE)`，以保留结构化 region 事实；但该 node 只服务于 frontend graph/测试观察，不允许在 body lowering 中翻译成真实 `ReturnInsn`。

`BranchNode.conditionRoot` 的稳定含义是：

- 它必须是直接产出 `conditionValueId` 的 condition fragment root
- 它不要求等于外围 `if` / `elif` / `while` 的最外层 source condition
- `not expr` 路径允许保留 `expr` 作为 root，再通过 target inversion 表达取反
- `and` / `or` 的每个短路 split 都必须绑定各自的 fragment root，而不是重复悬挂外层 shell

`BranchNode.conditionValueId` 当前允许是非 `bool` source value。bool-only normalization 固定在 frontend CFG -> LIR lowering 阶段完成，而不是在 graph publication 阶段抢先收紧 source contract。

### 3.2 Region side table

`FunctionLoweringContext` 当前会为 executable body 同时发布 AST identity keyed `frontendCfgRegions`。稳定 region 形状包括：

- `BlockRegion`
- `FrontendIfRegion`
- `FrontendElifRegion`
- `FrontendWhileRegion`
- `FrontendForRegion`
- `FrontendMatchRegion`

`FrontendMatchRegion` 记录 `match` 的 header（subject 单次求值）、每 section 的 `testEntryId` / `bodyEntryId`，以及 `mergeId`（全终止时为 `TERMINAL_MERGE`，不得作为 `goto` 目标）。所有 bind（顶层 `var x` 与解构嵌套 bind）走独立 `frontendMatchBindSlots` registry（key = `PatternBindingExpression`，slot id = 源码名，类型来自 `slotTypes()`），且在 section 建图前一次性预分配——与 Godot 在 pattern 编译前创建 bind local 一致，静态折叠的容器 pattern 也保留槽位供不可达 body 读取，只是不提交 `MatchBindItem`（跨表验证按 `foldedMatchBindDeclarations` 集合豁免）。同名 bind 的 exposed type 分歧（顶层精化类型 vs 嵌套恒 `Variant`）在 sema 的 `MATCH_PATTERN_RESOLUTION` 即预统一——同一 match 内分歧名字组整组保留 `Variant` 库存基线（Godot match bind 本无运行时类型）；跨 match 同名分歧只在 CFG `finishBuild` 统一为 `Variant`，被重定型的 bind 若是某 lambda 的非 `Variant` capture 来源则 fail-fast。bind 读取一律观察共享存储类型。顶层 `MatchBindItem` 挂在 section body 入口序列头部（guard 之前）；嵌套 bind 的 `MatchBindItem` 直接挂在取到元素的测试片段内，guard 同样可读。LITERAL / EXPRESSION 测试用 `MatchEqualItem` / `GetVariantTypeItem` / `IntConstantItem` / `VariantIsNilItem` / `BoolConstantItem`，多 pattern OR 与 String/StringName 交叉一律用 `BranchNode` 短路，禁止 value-context `BinaryOp(OR)`。ARRAY / DICTIONARY 解构按 route-A 分解为 `MatchContainerMaterializeItem`（typeof gate 后单次物化到静态容器槽）→ `MatchLengthCheckItem`（无 `..` 为 `==`，有 `..` 为 `>=`）→ 逐元素/entry 的 `MatchHasKeyItem`（仅字典）与 `MatchElementFetchItem` + 递归子测试；subject 静态族不兼容时整个容器 pattern 在分派处折叠到 miss 目标（无测试片段、不物化子表达式，子 pattern 表达式在运行时 typeof gate 失败时本就不会求值，折叠保持可观察行为）。

`FrontendForRegion` 记录 `for-in` 循环的五个结构锚点与两个独立 iterator slot：`initEntryId`（即 `entryId()`，init 子图入口，物化 source operands 并运行 init operation 写入 hidden state slot）、`conditionEntryId`（should-continue 条件子图入口）、`bodyEntryId`（body 入口，运行 get operation 提交 source-facing iterator local 后再执行 body statements）、`updateEntryId`（运行 next operation 并跳回 condition，是 `continue` target）、`exitId`（`break` target）、`sourceIteratorSlotId` 与 `iteratorStateSlotId`。后两者是 slot reference，不是 frontend CFG node id，分别经由 source-slot registry 与 hidden-state registry 解析，且不共享 id/type/lifecycle。

除 `frontendCfgRegions` 外，build pass 还会为 compile-ready `for-in` 发布两张 AST identity keyed registry：

- `frontendForSourceIteratorSlots`（`FrontendForSourceIteratorSlot`：`ForStatement` identity、source iterator name、来自 `slotTypes()[ForStatement]` 的 ordinary exposed type；该 slot type 由 suite 在 **`FOR_BODY` scope 身份** 上精化后经 `VAR_TYPE_POST` 发布，见 `scope_analyzer_implementation.md` §6.1）
- `frontendForIteratorStateSlots`（`FrontendForIteratorStateSlot`：`ForStatement` identity、`cfg_for_iter_<n>` state slot、`cfg_for_iter_next_<n>` next temp slot、来自 `FrontendForLoweringContract.iteratorStateType()` 的 compiler-only state type）

这两张 registry 与 graph/regions 一起在 `ExecutableBodyBuild` 构造时接受跨表验证（slot owner/type/uniqueness、item-slot 引用一致、source slot 与 hidden slot 分离、hidden slot id 不进入 ordinary value-id surface），随后发布到 `FunctionLoweringContext` 供 body lowering 预声明对应 LIR local。

这张 side table 当前承担两类职责：

- 让 CFG/测试能够按 AST identity 回读结构化入口
- 给 loop-control 与 future condition-value feature 提供稳定的结构锚点

### 3.3 构图状态机

`FrontendCfgGraphBuilder` 继续采用显式状态机构图，而不是 generic AST walker callback 主导。当前必须显式维护的状态包括：

- lexical continuation
- currently writable sequence
- loop stack
- value-context / condition-context 分工

这是当前实现固定的设计结论，不属于可随意替换的风格偏好。

---

## 4. Sequence Item 与 Value Id 合同

`SequenceNode.items` 当前已经冻结为“线性执行内容”，而不是 statement passthrough。block-local
`CommentStatement` 虽然允许出现在 compile-ready executable body 中，但会在 CFG build 时作为 lexical no-op
直接跳过，不发布 `SequenceItem` 或 value id。

稳定 item 分层为：

- `SourceAnchorItem`
- `ValueOpItem`
- `AssertItem`（statement 形状、不发布 value id，直接实现 `SequenceItem` 而非 `ValueOpItem`，因此不进入 produced-value materialization 分派。body lowering 由注册表中的 `FrontendAssertInsnLoweringProcessor` 消费：condition value 先经共享 truthiness helper 归一化为 bool slot，再追加非终结 `AssertInsn`）

当前已落地的 `ValueOpItem` 子类包括：

- `OpaqueExprValueItem`
- `BoolConstantItem`
- `LocalDeclarationItem`
- `AssignmentItem`
- `CompoundAssignmentBinaryOpItem`
- `MemberLoadItem`
- `SignalLoadItem`
- `CallableLoadItem`
- `StandaloneCallableLoadItem`
- `LambdaConstructItem`
- `SubscriptLoadItem`
- `CallItem`
- `MergeValueItem`
- `CastItem`
- `TypeTestItem`
- `ForLoopInitItem`
- `ForLoopShouldContinueItem`
- `ForLoopGetItem`
- `ForLoopNextItem`

四个 `ForLoop*Item` 服务于 `for-in` 循环且都实现 `ValueOpItem`，其 hidden iterator state 访问通过独立 `iteratorStateSlotId` 字段表达，不进入 ordinary result/operand value-id surface：

- `ForLoopInitItem`：消费 source operand value ids，初始化 hidden state slot，`resultValueIdOrNull() == null` 且 `hasStandaloneMaterializationSlot() == false`
- `ForLoopShouldContinueItem`：读取 hidden state slot，发布 ordinary `bool` condition result（被 condition branch 消费）
- `ForLoopGetItem`：读取 hidden state slot，发布 ordinary raw element result（独立 `cfg_tmp_*`），并经 `sourceIteratorSlotId` 提交 source-facing iterator local
- `ForLoopNextItem`：读取并经独立 `nextTempSlotId` 更新 hidden state slot，`resultValueIdOrNull() == null` 且 `hasStandaloneMaterializationSlot() == false`

item 携带的 operation descriptor 直接来自 `FrontendForLoweringContract`，lowering 不重新查询 route 或硬编码 intrinsic 名称。

`LambdaConstructItem` 表示外层 body 中一处已 record lambda 的构造：

- 持有 `lambdaAnchor`（`LambdaExpression`，materialization 结果类型取其 `RESOLVED(GdCallableType)`）、合成函数名、有序 `CaptureOperand` 列表与 `resultValueId`
- capture operand 是外层 frame 的直接 slot 读，不经 value-id 数据流，故 `operandValueIds()` 恒为空：`VariableSlotOperand(slotId)` 按名读外层 `LOCAL_VAR` / `PARAMETER` / 外层 `CAPTURE` slot（slot id == capture 条目名）；leading `self` capture 用 `SelfSlotOperand.SELF_SLOT` 专用 descriptor，禁止伪造 `IdentifierExpression + SELF`
- body lowering 发射 `ConstructLambdaInsn` 前在 owning class 上找回合成 shell 并端到端校验：shell 存在且 `is_lambda`、capture 数量一致、名序一致（operand slotId 按构造即 capture 名）；任一漂移 fail-fast
- lambda body 本身的 CFG/insn 仍由 `LAMBDA_BODY` context 承担，本 item 只承载外层构造点

这里的核心合同是：

- child subtree 先产出 value id
- parent item 只消费 operand value ids
- body lowering 不允许回到原 AST 子树重做第二套递归 lower
- opaque value publication 中的 writable-route helper 返回非空 carrier，但 carrier 内部的
  `writableRoutePayloadOrNull` 仍可为空；这表示该 ordinary value 没有可写回来源，而不是 publication
  helper 没有运行。identifier/self 的 `bindingOrNull` 只服务 CFG builder 内部 alias / fail-fast 判断，
  不扩散到 `CallItem` / `AssignmentItem` 的 public payload surface。

plain assignment、compound assignment 与 constructor materialization 当前各自冻结为：

- `AssignmentItem`
  - 只表示最终 store commit
  - target receiver/index operand 与将要写回的 RHS value id 都必须预先冻结
  - 对所有 lowering-ready assignment target，mandatory `writableRoutePayload` 都必须同时冻结；body lowering 不再接受缺失 payload 的 assignment commit
- `CompoundAssignmentBinaryOpItem`
  - 只表示 compound assignment 的当前值读取结果与 RHS 之间的 binary op
  - 不承载最终写回
  - 其 result value id 必须再由后续 `AssignmentItem` 提交到 target
- `CallItem`
  - 同时承载 ordinary call 与 constructor call
  - runtime-open `DYNAMIC_FALLBACK` instance call 继续复用同一个 item，不新增 dynamic-call 专用 CFG item
  - constructor route 不新增专用 CFG item
  - CFG 继续负责冻结 operand 顺序、anchor，以及“发布 standalone result value / 明确不发布 result slot”的 call 合同
  - value-required call path 仍必须发布 result value id；当前唯一允许 `resultValueIdOrNull() == null` 的 call item 是 statement-position、且已稳定解析为 `RESOLVED(void)` 的 discarded call
  - 若 `RESOLVED(void)` call 仍出现在 value-required path，CFG builder 必须立刻 fail-fast，而不是继续发布一个假想 result value id；这是 compile gate / type-check regression 的 guard rail，不是兼容路径
  - 若某个 call site 后续需要 mutating receiver writeback，则同一个 `CallItem` 还必须承载单个 writable receiver access-chain payload
  - 这条 chain payload 必须以“整条 route”的形式冻结；CFG 不得为同一个 call receiver 再发布一串额外 step item 让 body lowering 事后拼装
  - 对 property/subscript receiver call，payload 的 `reverseCommitSteps` 还必须包含“当前 leaf 提升后的第一层 commit step”；否则 body lowering 只有 receiver provenance，却没有真正可执行的 post-call writeback plan
  - call result runtime type 的真源是 call anchor 对应的 `expressionTypes()`；`resolvedCalls()` 只负责 route fact，不是 `DYNAMIC` call result type 的唯一来源

当前 body lowering 侧已经把 writable-route 的 leaf read / leaf write / reverse commit 共用逻辑收敛到 package-private
`FrontendWritableRouteSupport`。current-carrier family 的静态 writeback matrix 则收口到 public
`FrontendWritableTypeWritebackSupport`，避免 assignment lowering、runtime gate 与后续测试各自复制一份 family
表。当前 CFG 已经能通过 `FrontendWritableRoutePayload` 在 `CallItem` / `AssignmentItem` 上冻结整条 writable route，graph
publication 也会校验这类 payload 的本地 value-id 引用顺序，并额外拒绝 non-terminal static property commit step。
assignment final-store lowering 已经切到 payload-only route；legacy `targetOperandValueIds` 只继续保留给 source-order
sequencing 与 compound current-target read。exact instance-call receiver 则优先复用 CFG 已发布的 receiver value slot，
payload 只继续承载 post-call reverse commit；payload-backed call 若缺失 dedicated receiver value slot 现在会在
graph publication / body-lowering invariant 处直接失败，不再静默回退成 leaf 重读；direct-slot mutating receiver 的
“真实源 slot”现在也不再由 body lowering 特判回推，而是通过 alias-backed receiver value 直接表达。
dynamic instance-call receiver 现也冻结为同一套 payload consumer：

- `FrontendCallMutabilitySupport` 会把 `DYNAMIC_FALLBACK + INSTANCE` 视为 conservative may-mutate route
- 因而已发布 writable receiver payload 的 dynamic call 也会在 call 之后进入 shared reverse-commit path
- 若 current carrier 静态可判定，则继续复用 `FrontendWritableTypeWritebackSupport` 的 fast-path/fast-skip
- 只有 runtime-open `Variant` carrier 才会发 `CallGlobalInsn("gdcc_variant_requires_writeback", ...) + GoIfInsn`
- `SequenceNode` 线程化的 continuation block 现在就是 dynamic receiver runtime-gated writeback 的正式承载面；后续 item 不得再假设“继续挂回原 entry block”
- 对 `box.payloads.push_back(seed)` 这类“direct-slot owner + property leaf + dynamic mutating call”：
  - ordinary read 侧仍是 `OpaqueExprValueItem(box)` -> `MemberLoadItem(payloads, baseValueId = box_value_id)` -> `CallItem`
  - 但同一个 `CallItem.writableRoutePayloadOrNull` 会把 root 冻结为 direct-slot `box`，并把 property leaf 提升到
    `reverseCommitSteps`
  - 因此后续 body lowering 允许出现“entry property read 经过 `cfg_tmp_*`，runtime-gated property store 写回 `box`”
    的双轨形状；这是 publication 合同，不是 body lowering 临时修补

当前 direct-slot alias publication 合同已经冻结为：

- direct-slot mutating receiver 已改为发布 alias-backed receiver value，而不是继续依赖 body lowering 额外解释“synthetic temp -> source slot”
- 这条 direct-slot publication surface 只包含 explicit `SelfExpression`、`IdentifierExpression + LOCAL_VAR`、`IdentifierExpression + PARAMETER`
- `IdentifierExpression + CAPTURE` 当前不在 alias publication surface 内。lambda/capture lowering 与 storage semantics 已落地（`construct_lambda` + capture block），但 capture-backed live-slot alias 仍未开放，不能提前把它视为 alias root
- `IdentifierExpression + FrontendBindingKind.SELF` 在当前代码库中不是独立 source category：`FrontendTopBindingAnalyzer` 只会对 `SelfExpression` 发布 `SELF`，因此一旦这种 surface 泄漏到 builder 或 body lowering，二者都必须把它当作 contract violation 直接 fail-fast，而不是恢复成 `"self"`
- `receiverValueIdOrNull == null` 时 fallback 到 `self` 的 implicit self receiver 不属于 payload-backed receiver publication，也不属于 direct-slot alias root
- explicit `SelfExpression` 的 alias 安全性来自 `self` slot 不可被用户代码重绑定，因此不需要额外 argument no-rebinding 分类
- `IdentifierExpression + LOCAL_VAR/PARAMETER` 只有在后续 arguments 全部落在 proven no-rebinding 子集时才允许 alias publication
- `IdentifierExpression + CAPTURE` 在当前实现中必须 fail-fast；capture-backed live-slot alias eligibility 仍未开放
- 当前 CFG builder 已明确把 nested `CallExpression`、`AttributeCallStep` 和其它尚未证明 no-rebinding 的 effect-open expression kind 视为 snapshot fallback trigger：遇到这些参数时继续保留 ordinary `OpaqueExprValueItem(identifier)`，不再发布 live-slot alias

其中 compound assignment 的 source-order 合同固定为：

1. 先冻结 assignment target 所需的 receiver/index/prefix operand
2. 再基于这些 frozen operand 读取当前 target value
3. 再求值 RHS
4. 再发 `CompoundAssignmentBinaryOpItem`
5. 最后用 `AssignmentItem` 走 payload-backed writable-route store/commit 提交结果

value id 当前“基本单一定义”，但有一条刻意保留的窄例外：

- 同一 outward-facing result value id 可以被多个 `MergeValueItem` 沿互斥路径写入（它们的 `mergeAnchor` 必须是同一个已发布 `RESOLVED/DYNAMIC` 的表达式节点，否则 `collectCfgValueMaterializations` fail-fast）
- `MergeValueItem` 的合并槽类型由 `mergeAnchor` 的 `expressionTypes` 事实决定，而非单个 `sourceValueId` 的临时类型；因此同一 `resultValueId` 的多生产者已天然无类型冲突（同一 anchor 同一类型）
- 正常情况下 `MergeValueItem.sourceValueId` 必须来自同一个 `SequenceNode` 中更早出现的某个 `ValueOpItem.resultValueId`
- 窄例外：若某 `sourceValueId` 在全图范围内至少存在 1 个生产者且**全部**为 `MergeValueItem`（merge-of-merge），则允许作为跨 sequence 的 merge 源——该形状服务于嵌套三元的臂（内层 `resultValueId` 本身由双 `MergeValueItem` 产生）与 value 语境 `and/or` 臂；任何其它跨 sequence 源（opaque / call / 常量等）仍属非法；悬空 source（全图无生产者）仍 fail-fast
- `MergeValueItem` 的写入由 `FrontendMergeValueInsnLoweringProcessor` 经统一 `materializeFrontendBoundaryValue(..., "merge_write")` 物化后 `AssignInsn(cfg_merge_*, materialized)`：`merge_write` 是 re-derive consumer（现场重查 ordinary typed-boundary 矩阵），与 assignment / return / local-init 同类；`bool→bool` 仍为 `ALLOW_DIRECT`，故 value 语境 `and/or` 的 LIR 形状保持 `LiteralBoolInsn` + `AssignInsn(cfg_merge_*, cfg_tmp_*)` 无额外 pack/unpack/intrinsic
- graph publication 必须在发布时验证上述“同 sequence、先 producer 后 merge”及 merge-of-merge 窄例外；type collection / body lowering 不负责为其它跨 sequence 或逆序 merge source 做补救

因此所有 consumer 都必须接受：

- merge result 的读取真源是 merge slot
- 不能假设“一个 value id 只有一个 producer”
- 不能把 merge value 再压平成唯一 SSA 定义

---

## 5. 条件与短路合同

frontend 与 LIR 当前同时冻结了两条事实：

- source-level condition 继续采用 Godot-compatible truthy contract
- backend / LIR control flow 继续保持 bool-only branch 边界

frontend CFG / body lowering 当前用以下方式闭合这组约束。

### 5.1 Condition context

`buildCondition(...)` 当前固定行为：

- `not expr`
  - 不额外生成无意义值
  - 通过 true/false target inversion 表达
- `and` / `or`
  - 直接展开短路分支
  - 每个 split 都绑定自己的 fragment-local `conditionValueId`
- `ConditionalExpression`
  - 纯控制流展开，不产生任何 merge 值：先测 `condition`，再对被选中臂做 truthiness 分支（两臂经 `buildCondition` 递归分发，外层 true/false target 原样透传）
  - 因此 `if (a if c else b):` 的图中每个 `BranchNode.conditionValueId` 仍为 branch-local temp 槽，truthiness 归一化（由共享 helper `FrontendBodyLoweringSupport.materializeTruthinessToBool` 承接）零改动；嵌套三元臂与 `and/or` 臂继续递归纯展开
- 其他 condition
  - 先经由 `buildValue(...)` 求出 source value
  - 再发布 `BranchNode`

### 5.2 Value context

`and` / `or` 在 value context 中当前绝不允许退化成 eager binary。

当前固定形态是：

- branch
- path-local `BoolConstantItem`
- `MergeValueItem`
- merge continuation

对应 LIR 产物也已冻结为：

- `GoIfInsn`
- branch-local `LiteralBoolInsn`
- 写入 `cfg_merge_<valueId>` 的 `AssignInsn`

当前明确禁止出现：

- `BinaryOpInsn(AND)`
- `BinaryOpInsn(OR)`

`ConditionalExpression` 在 value context 中复用同一 branch-result merge 基建，固定形态是：

- condition 子图（复用 `buildCondition`，`not` / `and/or` / 嵌套三元均走现有 condition 机制）
- 两臂各自独立 `OpenSequence`，臂内以 `buildValue(..., null)` 求值（臂保留私有 temp）
- 每臂末尾追加 `MergeValueItem(conditional, armResultId, sharedResultId)` 后发布，双臂合流到同一 merge continuation
- `mergeAnchor` 为整条 `ConditionalExpression`，合并槽类型即 sema 发布的合并类型（§4 anchor 化合同）
- 嵌套三元臂 / value 语境 `and/or` 臂返回未发布的内层 merge sequence，外层 merge 写入追加在同一 sequence 末尾（merge-of-merge 窄例外的服务对象）
- `preferredResultValueId` 直接成为 `sharedResultId`（如 `var x = 三元` 的 `x_<n>` 槽），不额外产生中转 id

### 5.3 Bool-only normalization

frontend CFG -> LIR body lowering 当前统一复用以下 normalization 规则：

- `bool` source
  - 直接 `GoIfInsn`
- `Variant` source
  - 直接 `UnpackVariantInsn -> bool temp -> GoIfInsn`
- 非 `bool` 且非 `Variant` source
  - `PackVariantInsn -> UnpackVariantInsn -> bool temp -> GoIfInsn`

条件临时槽位命名固定为：

- `cfg_cond_variant_<valueId>`
- `cfg_cond_bool_<valueId>`

---

## 6. Slot Naming 与 published fact 真源

当前 body lowering 只允许消费 published facts，不能再透视 scope 私有状态或临时重建语义。

### 6.1 三类局部槽位

当前固定的命名与类型来源为：

- CFG temp value
  - 命名：`cfg_tmp_<valueId>`
  - 类型来源：对应 CFG producer item 消费的 published fact
- merge result
  - 命名：`cfg_merge_<valueId>`
  - 类型来源：merged result value id 的 published type（即 `mergeAnchor` 的 `expressionTypes` 事实）
  - 生命周期：merge 槽是普通 LIR 变量，backend 不做 `cfg_merge_` 特判；destroyable 合并类型（`String` / `Array` / object 等）与 source-local / `cfg_tmp_*` 同策略——函数头声明并在 `__prepare__` 默认构造，每条互斥 merge 写入先 destroy 旧值再覆写，`__finally__` 作用域退出统一销毁（已由 `ternary/destroyable_arms` e2e 的 C 产物验收确认）
- source-level local variable
  - 命名：沿用源码名
  - 类型来源：`analysisData.slotTypes()`

此外，instance executable function 当前会在必要时补出 `self` local slot，供以下路径统一复用：

- bare instance call
- bare property access
- `self` expression

### 6.2 允许读取的 published facts

当前 body lowering 只允许读取：

- `analysisData.symbolBindings()`
- `analysisData.resolvedMembers()`
- `analysisData.resolvedCalls()`
- `analysisData.expressionTypes()`
- `analysisData.slotTypes()`

其中 explicit `SelfExpression` 的 assignment-target prefix fact 是正式输入，不是 lowering fallback 的提示：

- 对 compile-ready `self.<property> = value`，CFG builder 与 body lowering 只能消费 sema 已发布到 `expressionTypes()` 的具体 `SelfExpression` fact。
- `FrontendCfgGraphBuilder` 可以据此把 writable route root 冻结为 explicit `SelfExpression` direct-slot root；`FrontendLoweringBodyInsnPass` 不得为缺失 fact 的 `SelfExpression` 临时重跑 receiver type 推断。
- 若绕过 compile gate 后仍缺少该 fact，body lowering 的异常属于协议不变量保护；正常 compile 路径应由 semantic publication 与 compile-check blocker 在 lowering 前闭合。

当前明确禁止：

- 重跑 chain reduction
- 重选 bare call overload / route
- 为 local slot type 重新透视 `scopesByAst()` / `BlockScope`
- 重新推断哪些 child 先求值

### 6.3 字符串字面量 source / payload 合同

字符串字面量当前也已经冻结为 body lowering 的正式 published-fact 合同：

- parser / AST 继续把 `LiteralExpression.sourceText()` 视为源码 lexeme
- body lowering 在把 `string` / `string_name` opaque literal materialize 成 LIR 时，必须先做 lexeme -> runtime payload 归一化
- 因而 downstream `LiteralStringInsn.value()` / `LiteralStringNameInsn.value()` 只承载 normalized payload，不再保留外围引号、`&` 前缀或原始转义写法

这条 split contract 是长期事实，不属于临时兼容行为：

- frontend 不得再直接把 `sourceText()` 透传给 string-like LIR literal
- backend 也不得再把 string-like LIR literal 当作 raw GDScript lexeme 二次猜测

### 6.4 `expressionTypes()` 的 key-space

`analysisData.expressionTypes()` 当前明确不是 `Expression`-only key-space。正式 published key 包括：

- ordinary expression root
- `AttributePropertyStep`
- `AttributeCallStep`
- `AttributeSubscriptStep`

因此 lowering helper 若扫描或读取该表，必须把“step keyed published fact”视为正式输入，而不是兼容例外。

`AttributeSubscriptStep` 现在已经是 compile-ready lowering 的正式 key。直接结果是：

- body-lowering session / processor 可以直接读取 subscript step 的 published expression type
- compile-check 可以把 step 本身作为 compile anchor
- lowering 不再保留“step type 尚未发布”的临时分支

若有人绕过 compile gate，step fact 仍停留在 `FAILED` / `UNSUPPORTED` / 其他无 `publishedType` 状态，body lowering 会抛出包含以下信息的异常：

- step 名称
- published status
- detail reason
- “本应由 `FrontendCompileCheckAnalyzer` 先行拦截”的提示

这条异常是绕过 compile gate 时的保底定位信息，不是正常 compile 路径的主要 owner。

---

## 7. Pass 职责边界

### 7.1 `FrontendLoweringBuildCfgPass`

当前只负责：

- 消费 compile-ready `EXECUTABLE_BODY` / `PROPERTY_INIT` / `LAMBDA_BODY` context
- 调用 `frontend.lowering.cfg` 下的 builder
- 发布 `frontendCfgGraph`
- 为 executable body 发布 `frontendCfgRegions`
- 为 compile-ready `for-in` 发布 `frontendForSourceIteratorSlots` 与 `frontendForIteratorStateSlots` registry
- 对 property initializer 校验 `sourceOwner == property declaration`、`loweringRoot == initializer expression`
- 对 lambda body 校验 `sourceOwner instanceof LambdaExpression`、`loweringRoot instanceof Block`，随后与 `EXECUTABLE_BODY` 共享同一 `publishExecutableBlockGraph`（`buildExecutableBody` + graph/region/for-slot 发布）；外层 body 表达式树中的已 record `LambdaExpression` 由 CFG builder 建 `LambdaConstructItem`（缺已发布 plan 仍 fail-fast）

当前不负责：

- 写 `LirBasicBlock`
- 设置 `entryBlockId`
- materialize instruction
- 处理 parameter default
- 为 property initializer 发布伪造的 block/loop region

### 7.2 `FrontendLoweringBodyInsnPass`

当前只负责：

- 消费 frontend CFG graph/region 与 published facts
- 声明 lowering local/temp/merge slots
- 将可执行 graph node id materialize 为 `LirBasicBlock.id`
- 将 `SequenceNode` lower 成 instruction 序列
- 将 `BranchNode` lower 成 bool-only branch terminator
- 将 `StopNode(kind = RETURN)` lower 成 `ReturnInsn`
  - `returnValueIdOrNull` 非空：经 `materializeFrontendBoundaryValue(...)` 物化后生成带值 `ReturnInsn`
  - `returnValueIdOrNull` 为空（隐式 fallthrough 或显式裸 `return`）：按目标函数返回类型分流——void 生成无值 `ReturnInsn(null)`；Variant 先向 `cfg_return_nil_<n>` temp（`FrontendBodyLoweringSession.allocateReturnNilTemp`）追加 `LiteralNilInsn` 再生成带值 `ReturnInsn`；其它已声明非 void 类型 fail-fast（type-check 尚未实现 missing-return 分析，lowering 对 typed non-Variant fallthrough 保持 fail-closed，而非生成 backend 必拒的 terminator）
- 保留 `StopNode(kind = TERMINAL_MERGE)` 在 frontend CFG 中，但不为其创建 LIR basic block
- `FrontendBodyLoweringSupport.collectCfgValueMaterializations(...)` 只按 graph 已发布的 node/item 顺序收集 materialization facts；它依赖 graph publication 已经验证 merge source 的本地先后合同，而不是自己跨 sequence 回溯 producer
- 对 `hasStandaloneMaterializationSlot() == false` 的 item，body lowering 必须跳过独立 temp slot 声明；当前稳定用例包括 statement-position resolved-void `CallItem`

当前内部组织也已经冻结为以下形状：

- `FrontendLoweringBodyInsnPass` 本体只保留 compile-ready function context 调度；当前默认 pipeline 覆盖 executable-body、property-init 与 lambda-body（并入共享 session 分支；合成 shell 的 `setStatic(true)` 保证 `declareSelfSlotIfNeeded` 不产生 stray `self`），parameter-default 继续显式 fail-fast
- 真实的 per-function lowering state 收口到 `frontend.lowering.pass.body.FrontendBodyLoweringSession`
- CFG node、`SequenceItem`、opaque expression root、assignment target / attribute step 都通过 `FrontendInsnLoweringProcessor` 注册表按“当前节点实际类型”动态分派
- `FrontendInsnLoweringProcessor` 现在还显式返回 lowering 结束后的当前 continuation block；`SequenceNode` 会把这个 block 一路传给后续 item，因此 writable-route runtime gate 生成的 synthetic `apply/skip/continue` blocks 不会把后续 lowering 误挂回原始 node entry block
- `FrontendBodyLoweringSupport` 保留 slot naming、condition temp naming、published type collection 与共享 truthiness 归一化（`materializeTruthinessToBool`，同时服务 branch 与 assert lowering）这类跨节点通用 helper；branch/subscript/opaque-expression 等节点专属逻辑都留在各自 processor 邻域

### 7.3 owner 边界

当前 owner 划分已经冻结为：

- shared semantic / type-check
  - 不把 upstream `FAILED` / `UNSUPPORTED` / `BLOCKED` / `DEFERRED` 改写成新的 type-check error
- `FrontendCompileCheckAnalyzer`
  - 负责把 non-lowering-ready compile surface 在进入 lowering 前拦截下来
- body lowering
  - 只保留 invariant guard rail 与绕过 compile gate 时的高质量异常

---

## 8. 当前 compile-ready executable surface

当前 compile-ready executable surface 已经稳定支持：

- straight-line executable body
- block-local `CommentStatement`（transparent no-op；允许出现在 executable body，但不参与 CFG item publication）
- `if` / `elif` / `else`
- `while`
- loop-local `break` / `continue`
- value-context 与 condition-context 的 `and` / `or` / `not`
- identifier / literal / `self`
- eager unary 与非短路 eager binary
- bare/global/static/instance method call（含 runtime-open dynamic instance call）
- member/property access
- subscript
- plain assignment
- compound assignment
- constructor materialization
- `TypeTestExpression` (`is` / `is not`) with unified `is_instance_of` or folded bool lowering
- callable-local slot type published contract
- 已 record lambda 的外层构造（`LambdaConstructItem` → `construct_lambda`；C backend `CONSTRUCT_LAMBDA` 已落地）
- `match` 全六 route（WILDCARD / BINDING / LITERAL / EXPRESSION / ARRAY / DICTIONARY；合同见 §3.2 与 `frontend_match_statement_implementation.md`）

plain assignment 的 compile-ready surface 明确包含 direct explicit-self property assignment：

- `self.<property> = value` 必须在 CFG 中发布 `AssignmentItem.writableRoutePayload`，root 为 explicit `SelfExpression` direct-slot，leaf 为 property。
- 该支持面不隐式扩张到 nested property、container mutation 或 compound assignment；这些形态继续遵守复杂 writable-route 与 compound assignment 各自的合同。

### 8.1 Ordinary `Variant` boundary materialization

ordinary `Variant` boundary materialization 现在已经冻结为 executable-body body pass 的正式合同：

- local initializer / bare assignment / attribute-property assignment
  - target slot 是 `Variant` 时显式插入 `PackVariantInsn`
  - stable `Variant` source 流向 concrete target 时显式插入 `UnpackVariantInsn`
- fixed call arguments / vararg tail
  - fixed parameter 按 selected callable signature 的 parameter type 做 ordinary boundary materialization
  - vararg tail 统一按 `Variant` tail 处理，concrete extra arg 先 pack，stable `Variant` extra arg direct
- `DYNAMIC_FALLBACK` instance call
  - frontend 不读取 callable signature，也不做 fixed-parameter boundary materialization
  - 已求值的 argument slot 直接透传给 `CallMethodInsn`
  - receiver 若已由 CFG 发布为 writable access-chain payload，则必须走独立 receiver-side writable-route core；这不属于 ordinary argument boundary 合同
  - backend dynamic dispatch 继续承担 runtime-open call 的实际分派与直接 `Variant` 结果发布
  - 该 `Variant` 结果若随后跨越 ordinary typed boundary，再由 frontend ordinary boundary helper 做后续 `(un)pack`
- return
  - stop-node lowering 按当前函数 return slot type 做同一套 boundary materialization
- `String <-> StringName`
  - 普通 `"..."` 仍先 lower 为 `String` literal；流入 `StringName` slot 时再由 ordinary boundary helper 物化为 target-typed `ConstructBuiltinInsn`
  - `&"..."` 是 direct `StringName` literal route，不应通过普通 string literal 路径伪装成 direct assignment

这条 ordinary-boundary helper 与 condition normalization 是两条并列但不同的合同：

- condition 只在 `FrontendCfgNodeInsnLoweringProcessors` 中做 bool-only branch normalization
- local / assignment / call / return 统一走 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`
- `materializeFrontendBoundaryValue(...)` 只负责物化 `frontend_implicit_conversion_matrix.md` 已允许、且已由 shared semantic helper 放行的 ordinary boundary；不得在 lowering 侧独立新增 conversion
- ordinary boundary consumer/materialization 的长合同以 `frontend_lowering_(un)pack_implementation.md` 为准

### 8.2 Constructor materialization 合同

constructor route 当前已经闭合为 compile-ready executable surface 的正式部分。

semantic / published-fact 合同：

- object constructor 语法入口使用 type-meta `.new(...)`
  - 例如 `Node.new()`、`Worker.new()`
- builtin direct constructor 使用 bare call route
  - 例如 `Array(...)`、`Vector3i(1, 2, 3)`、`Color(...)`
- 以上两类入口统一发布为 `FrontendResolvedCall(callKind = CONSTRUCTOR)`
- downstream 不得再通过 syntax shape 区分 constructor route kind

CFG / body-lowering 合同：

- constructor 继续复用现有 `CallItem`
- type-meta chain head 不 materialize 头部 identifier 为运行时值；第一个 lowering step 直接从已发布的 type-meta fact 进入：
  - static member load 产出 `MemberLoadItem(..., null, ...)`
  - static/constructor call 产出 `CallItem(..., null, ...)`
- 因而 `Vector3.ZERO`、`Color.RED`、`ClassName.SOME_CONST` 以及它们后续继续链式访问的写法都属于当前 compile-ready surface
- builtin instance property read 同样已经闭环为 compile-ready surface：
  - `vector.x`、`Color(...).r`、`Basis.IDENTITY.x` 在 CFG 中继续只是 ordinary `MemberLoadItem`
  - body lowering 对其统一发出 `LoadPropertyInsn`
  - 这条 contract 不会放宽 builtin keyed access；`vector["x"]` 仍不走这里
- builtin instance property write 继续复用 ordinary assignment route：
  - `vector.x = 1.0`、`color.a = 0.5` 在 CFG 中继续只是 ordinary `AssignmentItem`
  - body lowering 对其统一发出 `StorePropertyInsn`
  - builtin member writable / missing-property policy 仍以上游 published member fact 与 shared writable helper 为真源
- body lowering 依据已发布的 constructor route shape 与 result type 选择 LIR：
  - builtin 单参数 stable `Variant` constructor -> `UnpackVariantInsn`
  - 其它 builtin/container constructor -> `ConstructBuiltinInsn`
  - object constructor -> `ConstructObjectInsn`
- constructor 不伪装成 static/instance method
- body lowering 不重跑 overload 选择，不回退成语义分析

语义封口与 runtime 约束：

- gdcc 自定义类的带参 `_init(...)` 在语义阶段直接报错
- compile gate 继续保留对 parameterized gdcc constructor route 的兜底拦截，防止上游错误重新发布
- 零参数 `_init(self)` 由 runtime postinitialize / class constructor path 触发；frontend lowering 不追加 follow-up `_init(...)` 调用

overload 选择合同：

- builtin constructor 不再采用“多个 applicable 就直接歧义”策略
- 当前选择顺序固定为：
  - 先做 applicability 过滤
  - 再比较逐参数转换质量与目标类型具体度
  - 若仍无法区分，则以语义诊断 fail-closed，而不是抛异常把问题下沉到 lowering/backend

backend 相关的当前事实：

- engine class object construction 仍经过 GDCC 自有 runtime support 发布的 `godot_new_XXX()` wrapper 边界
- gdcc class object construction 复用 `XXX_class_create_instance(...)`
- 对显式 C 构造的 gdcc `RefCounted` 对象：
  - 先调用 `XXX_class_create_instance(NULL, false)`
  - 再调用 `gdcc_ref_counted_init_raw(..., true)`
- `*_class_create_instance(...)` 本体保持 shared create/bind helper，不内嵌 `gdcc_ref_counted_init_raw(...)`
- 通过 Godot 引擎函数或 GDScript 创建继承 `RefCounted` 的 gdcc 类时，引用计数初始化继续由 Godot 自身创建路径负责

### 8.3 Compound assignment 合同

compound assignment 当前已经闭合为 compile-ready executable surface 的正式部分。

语义合同：

- 已支持的闭合集合为：
  - `+=`
  - `-=`
  - `*=`
  - `/=`
  - `%=`
  - `**=`
  - `>>=`
  - `<<=`
  - `&=`
  - `^=`
  - `|=`
- success root 继续发布 `RESOLVED(void)`
- statement-position 的 compound assignment success root 不发 discarded warning
- value-required 的 nested compound assignment 继续 fail-closed
- compound operator 的类型判定复用 ordinary binary operator 语义，不另造一套 operator typing 规则

target 支持面：

- bare identifier
- attribute property
- plain subscript
- attribute-subscript

CFG 合同：

- `AssignmentItem` 继续只表示最终 store commit
- lowering-ready assignment target 必须同时发布 mandatory `AssignmentItem.writableRoutePayload`
- `CompoundAssignmentBinaryOpItem` 显式冻结：
  - assignment anchor
  - binary operator lexeme
  - current-target-value id
  - rhs value id
  - computed-result value id
- builder 不得把 compound assignment 机械改写成 `lhs = lhs <op> rhs`
- target receiver/index/prefix 必须只求值一次

body-lowering 合同：

- `CompoundAssignmentBinaryOpItem` 直接 lower 为 `BinaryOpInsn`
- compound-op processor 只消费 CFG 已冻结的 binary lexeme 与 operand value ids
- compound-op processor 本身不插入额外的 assignment-boundary `(un)pack`
- 最终写回统一走 `FrontendAssignmentTargetInsnLoweringProcessors` 的 payload-only writable-route path
- compound temp slot 的类型必须是“真实 binary 结果类型”，而不是最终 assignment target 类型
- `Variant` pack/unpack 只允许保留在最终 assignment/store boundary，不能前移到 `BinaryOpInsn`

---

## 9. Remaining shell-only / unsupported surface

当前仍保持 shell-only、compile-block 或 fail-fast 的部分包括：

- `PARAMETER_DEFAULT_INIT` CFG / body lowering
- callable-value invocation
- multi-key subscript lowering
- `for`（compile gate 为 route-aware：registry 已注册 route 放行，`OBJECT_CUSTOM` 等未注册 route 发 route-not-ready blocker；`FrontendForRegion`、四个 `ForLoop*Item`、source/hidden slot registry、跨表验证与 body lowering（`declareForLoopSlots()` + init/should_continue/get/next processors、temp-then-commit）均已落地；完整合同见 `frontend_for_range_loop_implementation.md`）

`GetNodeExpression` 已进入 compile-ready body lowering 合同（Node 派生类非 static 函数体及已记录 lambda sema 发布 `RESOLVED(Node)`；CFG 按 opaque leaf 建 `OpaqueExprValueItem`；body lowering 由专用 processor 改写为 `literal_node_path` + `assign`（`self` 上溯 `Node`）+ `call_method "get_node"` 三指令序列，backend ENGINE dispatch 闭环），不再属于 shell-only / temporary compile-block surface；property initializer 中的 `$`/`%` 仍为 DEFERRED 边界，由 generic published-fact scan 封口（见 `frontend_node_literal_implementation.md`）。

`PreloadExpression` 已进入 compile-ready body lowering 合同（sema 要求字符串字面量路径并发布 `RESOLVED(Resource)`；CFG 按 opaque leaf 建 `OpaqueExprValueItem`；body lowering 由专用 processor 改写为 `load_static "@GlobalScope" "ResourceLoader"` + `call_method "load"` 指令对，与合成语言函数 `load` 的改写共享同一指令对），不再属于 shell-only / temporary compile-block surface；`const X = preload(...)` 仍随 class-constant 工作流整体拦截。

`ArrayExpression` / `DictionaryExpression` / `ContainerLiteralItem` 已进入 compile-ready body lowering 合同（plan → `construct_container_literal`，见 `frontend_container_literal_implementation.md`），不再属于 shell-only / temporary compile-block surface；opaque 路径对 Array/Dictionary 仍 `REJECT`（dedicated-item-only）。

`CastExpression` / `CastItem` 已进入 compile-ready body lowering 合同（decision→LIR 映射见 `frontend_cast_expression_implementation.md`），不再属于 shell-only / temporary fail-fast surface。

`ConditionalExpression` 已进入 compile-ready body lowering 合同，不再属于 shell-only / temporary compile-block surface：

- CFG 构图两种语境（value 语境 merge / condition 语境纯控制流展开）与 merge 槽合同见 §5.1/§5.2
- compile gate 已解封（`walkExpression` 落入 default 递归），body lowering 经 `merge_write` boundary 物化，e2e（`ternary/` 用例对）已闭环；见 `frontend_conditional_expression_implementation.md`

当前 body lowering 明确保留 fail-fast 的路径包括：

- multi-key subscript lowering
- 缺失 published fact 的 call/member/value type 路径

---

## 10. 回归锚点

涉及本文档合同的改动，至少要继续覆盖以下回归锚点：

- `FrontendLoweringBuildCfgPassTest`
  - deterministic graph shape / node id
  - AST identity keyed region lookup
  - value-op operand/result id contract
  - nested call/member/subscript evaluation order
  - non-bool condition region
  - condition/value short-circuit subgraph
  - `break` / `continue`
  - compound assignment read-modify-write graph shape
  - constructor route graph publication
- `FrontendLoweringBodyInsnPassTest`
  - body lowering 只消费 graph + published facts
  - declaration / assignment / call 不做第二套 AST 递归 lower
  - value-context `and` / `or` 的 LIR 形态
  - subscript-step published type consumption
  - fully-terminated `if` chain 的 synthetic terminal-merge stop 不得产出 `ReturnInsn(null)`
  - constructor route lower 为 `UnpackVariantInsn` / `ConstructBuiltinInsn` / `ConstructObjectInsn`
  - compound assignment 只在最终 store boundary 做 `(un)pack Variant`
  - compile gate 绕过时的 lowering exception 质量
- `FrontendLoweringPassManagerTest`
  - 默认 pipeline 顺序
  - executable body materialization
  - property initializer CFG publication + executable LIR body boundary
- `FrontendCompileCheckAnalyzerTest`
  - step-level / expression-level compile anchor
  - `AttributeSubscriptStep` published fact 的 compile blocker 行为
  - `ConditionalExpression` 已放行（支持面三元零 compile_check；FAILED/UNSUPPORTED 三元经 upstream owner + exact-range 去重阻断）
  - parameterized gdcc constructor route 的 compile-only 兜底
- `FrontendVarTypePostAnalyzerTest`
  - parameter / typed local / `:=` local 的 slot type publication
  - duplicate / shadowing local 的 fail-closed 行为
- engine / integration tests
  - builtin direct constructor
  - engine `.new()`
  - gdcc zero-arg `.new()`
  - compound assignment 的 runtime smoke

---

## 11. 架构反思与后续要求

当前实现已经沉淀出以下长期有效的工程结论：

- frontend CFG 是 frontend-only 中间层，不是 legacy block bundle 的增强版，也不是 future HIR 的缩写版
- `FrontendLoweringBuildCfgPass` 是唯一的 CFG 构图入口
- `FrontendLoweringBodyInsnPass` 只消费 graph + published facts，不能回退成第二套语义分析器
- compile gate 负责“能否进入 lowering”，body lowering 负责“如何 materialize 已允许的 surface”
- merge result 必须继续以 merge slot 为中心建模，而不是强行恢复唯一 producer 幻觉
- constructor 与 compound assignment 都已经证明：应先冻结 published fact / graph item / owner 边界，再接 body lowering，最后再解除 compile gate

后续若继续扩张 lowering surface，必须优先遵守以下顺序：

1. 先冻结 published fact / compile gate / graph item 合同
2. 再接 `FrontendLoweringBuildCfgPass`
3. 最后接 `FrontendLoweringBodyInsnPass`

不得反过来用 body lowering 的局部实现去倒逼 semantic side table 或 compile gate 临时补洞。

---

## 12. 文档同步要求

只要 CFG graph、body lowering 或 compile-ready surface 合同发生变化，至少要同步更新：

- `frontend_lowering_plan.md`
- `frontend_lowering_func_pre_pass_implementation.md`
- `frontend_compile_check_analyzer_implementation.md`
- `frontend_chain_binding_expr_type_implementation.md`
- `frontend_analysis_inspection_tool_implementation.md`
- `frontend_loop_control_flow_analyzer_implementation.md`
- `diagnostic_manager.md`
- 本文档

若变化同时影响 constructor lowering 或 object construction backend 行为，还需同步：

- `doc/gdcc_low_ir.md`
- `doc/gdcc_c_backend.md`
- backend implementation docs
