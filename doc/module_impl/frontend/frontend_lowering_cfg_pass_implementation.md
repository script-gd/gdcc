# Frontend Lowering CFG Pass 实现说明

> 本文档作为 frontend lowering 中 CFG build / body materialization 工程的长期事实源，记录当前已经落地的 frontend-only CFG graph、`FrontendLoweringBuildCfgPass` 与 `FrontendLoweringBodyInsnPass` 合同、published-fact 消费边界、legacy block-bundle 迁移结论，以及对后续 property initializer / parameter default / condition-value feature 仍然有效的架构约束。本文档吸收并取代原 `frontend_lowering_cfg_graph_plan.md` 与 `frontend_lowering_cfg_pass_plan.md` 的已完成内容，不再保留步骤编号、验收流水账或阶段进度记录。

## 文档状态

- 状态：事实源维护中（executable-body CFG build / body lowering 已落地；`StopNode.kind` 空-return 图修复已落地；property initializer 与 parameter default 仍保持 staged）
- 更新时间：2026-04-05
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/cfg/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/pass/**`
  - `src/test/java/dev/superice/gdcc/frontend/lowering/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `frontend_lowering_plan.md`
  - `frontend_lowering_func_pre_pass_implementation.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_analysis_inspection_tool_implementation.md`
  - `frontend_loop_control_flow_analyzer_implementation.md`
  - `diagnostic_manager.md`
  - `doc/gdcc_low_ir.md`
- 明确非目标：
  - 不在这里引入 high-level IR / sea-of-nodes
  - 不在这里放行 `ConditionalExpression`
  - 不在这里把 property initializer / parameter default 接到 executable-body CFG 或 body pass
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

- lowering 入口仍然固定为 `FrontendModule`
- compile-ready 语义事实统一来自 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
- 只有在 `analysisData.diagnostics().hasErrors() == false` 时 lowering 才会继续
- function-shaped lowering 单元统一经由 `FunctionLoweringContext`
- public lowering 返回值现在是“executable body 已 materialize、property init shell 仍保留”的 `LirModule`

当前 pipeline 的产物边界固定为：

- `EXECUTABLE_BODY`
  - 发布 frontend CFG graph
  - 发布 frontend CFG regions
  - materialize real `LirBasicBlock` / terminator / instruction
- `PROPERTY_INIT`
  - 仍只保留 function shell，不进入 CFG build / body lowering
- `PARAMETER_DEFAULT_INIT`
  - 仍只保留 context kind 与模型槽位，不接入默认 pipeline

---

## 2. Legacy 迁移结论

旧的 `FrontendLoweringCfgPass` 与 `FunctionLoweringContext.cfgNodeBlocks` 已经从默认 pipeline 与代码中移除。它们只曾经表达过 metadata-only block bundle，不再是当前实现的一部分。

这次迁移里真正保留下来的稳定结论只有三条：

- frontend CFG 必须继续以 `FunctionLoweringContext` 为函数级 carrier，而不是发明第二套 lowering 入口
- AST identity keyed side table 方向是正确的，region / semantic anchor 都继续沿用 parser 节点 identity
- compile gate 负责拦截 non-lowering-ready surface；lowering 不重复扫描源码去补第二套编译阻断

旧 block-bundle 方案已经证明不足以承担当前 lowering：

- 它不能表达显式 `conditionEntryId`
- 它不能表达 source truthy condition 到 bool-only branch 的 normalization 过渡
- 它不能表达 `and` / `or` 的 condition/value 双路径短路
- 它不能表达 `while` 中 `break` / `continue` 的稳定跳转语义
- 它不能为 `ConditionalExpression` 预留 branch-result merge 入口

因此，当前长期约束是：

- 新增 lowering 需求只能落在 `frontend.lowering.cfg` + `FrontendLoweringBuildCfgPass` + `FrontendLoweringBodyInsnPass`
- 不得恢复或重建 `CfgNodeBlocks` 风格的过渡 side table
- 不得让 graph 与 legacy block bundle 双写并长期漂移

---

## 3. Frontend CFG 模型

frontend CFG 现在是 frontend-only 中间层，位于真实语义事实与 LIR basic block 之间。它只负责整理 source-level control flow、value flow 和 condition-evaluation-region，不承担读写 runtime instruction 的最终责任。

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
  - `returnValueIdOrNull` 可为空，表示 `return` / `return nil`-equivalent 路径
- `TERMINAL_MERGE`
  - 仅用于 frontend CFG 内部表达“该结构化分支链已全部终止”的 synthetic anchor
  - 不代表真实源码 `return`
  - 不得携带 `returnValueIdOrNull`

fully-terminated 的 `if` / `elif` / `else` 当前继续允许把 region `mergeId` 指向 `StopNode(kind = TERMINAL_MERGE)`，以保留结构化 region 事实；但该 node 只服务于 frontend graph/测试观察，不允许在 body lowering 中被翻译成真实 `ReturnInsn`。

`BranchNode.conditionRoot` 的稳定含义是：

- 它必须是直接产出 `conditionValueId` 的 condition fragment root
- 它不要求等于外围 `if` / `elif` / `while` 的最外层 source condition
- `not expr` 路径允许保留 `expr` 作为 root，再通过 target inversion 表达取反
- `and/or` 的每个短路 split 都必须绑定各自的 fragment root，而不是重复悬挂外层 shell

`BranchNode.conditionValueId` 当前允许是非 `bool` source value。bool-only normalization 固定在 frontend CFG -> LIR lowering 阶段完成，而不是在 graph publication 阶段抢先收紧 source contract。

### 3.2 Region side table

`FunctionLoweringContext` 当前会为 executable body 同时发布 AST identity keyed `frontendCfgRegions`。稳定 region 形状包括：

- `BlockRegion`
- `FrontendIfRegion`
- `FrontendElifRegion`
- `FrontendWhileRegion`

这张 side table 当前承担两类职责：

- 让 CFG/测试能够按 AST identity 回读结构化入口
- 给 loop-control / future condition-value feature 提供稳定的结构锚点

### 3.3 构图状态机

`FrontendCfgGraphBuilder` 继续采用显式状态机构图，而不是 generic AST walker callback 主导。当前必须显式维护的状态包括：

- lexical continuation
- currently writable sequence
- loop stack
- value-context / condition-context 分工

这是当前实现固定的设计结论，不属于“可随意替换的风格偏好”。

---

## 4. Sequence Item 与 Value Id 合同

`SequenceNode.items` 当前已经冻结为“线性执行内容”，而不是 statement passthrough。

稳定 item 分层为：

- `SourceAnchorItem`
- `ValueOpItem`

当前已落地的 `ValueOpItem` 子类包括：

- `OpaqueExprValueItem`
- `BoolConstantItem`
- `LocalDeclarationItem`
- `AssignmentItem`
- `MemberLoadItem`
- `SubscriptLoadItem`
- `CallItem`
- `MergeValueItem`
- `CastItem`
- `TypeTestItem`

这里的核心合同是：

- child subtree 先产出 value id
- parent item 只消费 operand value ids
- body lowering 不允许回到原 AST 子树重做第二套递归 lower

value id 目前“基本单一定义”，但有一条刻意保留的窄例外：

- 同一 outward-facing result value id 可以被多个 `MergeValueItem` 沿互斥路径写入

因此后续所有 consumer 都必须接受：

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

## 6. Slot Naming 与类型真源

当前 body lowering 只允许消费 published facts，不能再透视 scope 私有状态或临时重建语义。

### 6.1 三类局部槽位

当前固定的命名与类型来源为：

- CFG temp value
  - 命名：`cfg_tmp_<valueId>`
  - 类型来源：对应 CFG producer item 消费的 published fact
- merge result
  - 命名：`cfg_merge_<valueId>`
  - 类型来源：merged result value id 的 published type
- source-level local variable
  - 命名：沿用源码名
  - 类型来源：`analysisData.slotTypes()`

此外，instance executable function 当前会在必要时补出 `self` local slot，供：

- bare instance call
- bare property access
- `self` expression

统一复用。

### 6.2 Published semantic facts

当前 body lowering 只允许读取：

- `analysisData.symbolBindings()`
- `analysisData.resolvedMembers()`
- `analysisData.resolvedCalls()`
- `analysisData.expressionTypes()`
- `analysisData.slotTypes()`

其中 `analysisData.expressionTypes()` 的 key-space 当前明确不是 `Expression`-only：

- ordinary expression root
- `AttributePropertyStep`
- `AttributeCallStep`
- `AttributeSubscriptStep`

因此 lowering helper 若扫描或读取该表，必须把 “step keyed published fact” 视为正式输入，而不是兼容例外。

当前明确禁止：

- 重跑 chain reduction
- 重选 bare call overload / route
- 为 local slot type 重新透视 `scopesByAst()` / `BlockScope`
- 重新推断“哪些 child 先求值”

### 6.3 AttributeSubscriptStep 合同

`AttributeSubscriptStep` 现在是 `analysisData.expressionTypes()` 的正式 key。

这条合同的直接结果是：

- `frontend.lowering.pass.body` 中的 body-lowering session / processor 可以直接读取 subscript step 的 published expression type
- compile-check 可以把 step 本身作为 compile anchor
- lowering 不再保留“step type 尚未发布”的临时分支

若有人绕过 compile gate，step fact 仍停留在 `FAILED` / `UNSUPPORTED` / 其他无 `publishedType` 状态，body lowering 当前会抛出包含以下信息的异常：

- step 名称
- published status
- detail reason
- “本应由 `FrontendCompileCheckAnalyzer` 先行拦截”的提示

这条异常是绕过 compile gate 时的保底定位信息，不是正常 compile 路径的主要 owner。

---

## 7. Pass 职责边界

### 7.1 `FrontendLoweringBuildCfgPass`

当前只负责：

- 消费 compile-ready `EXECUTABLE_BODY` context
- 调用 `frontend.lowering.cfg` 下的 builder
- 发布 `frontendCfgGraph`
- 发布 `frontendCfgRegions`

当前不负责：

- 写 `LirBasicBlock`
- 设置 `entryBlockId`
- materialize instruction
- 处理 property initializer / parameter default

### 7.2 `FrontendLoweringBodyInsnPass`

当前只负责：

- 消费 frontend CFG graph/region 与 published facts
- 声明 lowering local/temp/merge slots
- 将可执行 graph node id materialize 为 `LirBasicBlock.id`
- 将 `SequenceNode` lower 成 instruction 序列
- 将 `BranchNode` lower 成 bool-only branch 终结
- 将 `StopNode(kind = RETURN)` lower 成 `ReturnInsn`
- 保留 `StopNode(kind = TERMINAL_MERGE)` 在 frontend CFG 中，但不为其创建 LIR basic block

当前内部组织也已经冻结为以下形状：

- `FrontendLoweringBodyInsnPass` 本体只保留 executable-body context 调度与 shell-only guard rail
- 真实的 per-function lowering state 收口到 `frontend.lowering.pass.body.FrontendBodyLoweringSession`
- CFG node、`SequenceItem`、opaque expression root、assignment target / attribute step 都通过 `FrontendInsnLoweringProcessor` 注册表按“当前节点实际类型”动态分派
- `FrontendBodyLoweringSupport` 现在只保留 slot naming、condition temp naming、published type collection 这类跨节点通用 helper；branch/subscript/opaque-expression 等节点专属逻辑已迁到各自 processor 邻域

当前 executable-body 已接通的 lowering surface包括：

- identifier / literal / self
- eager unary
- 非短路 eager binary
- bare/global/static/instance method call
- member/property access
- subscript
- plain assignment
- source anchor / local declaration / merge result

ordinary `Variant` boundary materialization 现在也已经冻结为 executable-body body pass 的正式合同：

- local initializer / bare assignment / attribute-property assignment
  - target slot 是 `Variant` 时显式插入 `PackVariantInsn`
  - stable `Variant` source 流向 concrete target 时显式插入 `UnpackVariantInsn`
- fixed call arguments / vararg tail
  - fixed parameter 按 selected callable signature 的 parameter type 做 ordinary boundary materialization
  - vararg tail 统一按 `Variant` tail 处理，concrete extra arg 先 pack，stable `Variant` extra arg direct
- return
  - stop-node lowering 按当前函数 return slot type 做同一套 boundary materialization

这条 ordinary-boundary helper 与 condition normalization 是两条并列但不同的合同：

- condition 继续只在 `FrontendCfgNodeInsnLoweringProcessors` 中做 bool-only branch normalization
- local / assignment / call / return 统一走 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`

当前 subscript lowering 的指令选择规则为：

- key type = `int`
  - 优先发 `variant_get_indexed` / `variant_set_indexed`
- key type = `StringName`
  - 优先发 `variant_get_named` / `variant_set_named`
- key type 为其他已知非 `Variant` 类型，且 receiver 落在 keyed 支持面
  - 优先发 `variant_get_keyed` / `variant_set_keyed`
- key kind 仍只有 `Variant` 级别信息
  - 保留 `variant_get` / `variant_set`

这条规则只允许消费已发布的 receiver/key type；body lowering 不得为此重跑 subscript 语义或额外推断 key route。

当前明确保留 fail-fast 的路径包括：

- `CastItem`
- `TypeTestItem`
- constructor materialization
- multi-key subscript lowering
- 缺失 published fact 的 call/member/value type 路径

### 7.3 Compile gate 与 lowering 的 owner 边界

当前 owner 划分已经冻结为：

- shared semantic / type-check
  - 不把 upstream `FAILED` / `UNSUPPORTED` / `BLOCKED` / `DEFERRED` 改写成新的 type-check error
- `FrontendCompileCheckAnalyzer`
  - 负责把 non-lowering-ready compile surface 在进入 lowering 前拦截下来
- body lowering
  - 只保留 invariant guard rail 与绕过 compile gate 时的高质量异常

---

## 8. 当前支持面与保留封口

当前 compile-ready executable surface 已经稳定支持：

- straight-line executable body
- `if` / `elif` / `else`
- `while`
- loop-local `break` / `continue`
- value-context 与 condition-context 的 `and` / `or` / `not`
- callable-local slot type published contract
- bare `CallExpression` 的正式 `resolvedCalls()` published 面

需要额外澄清的一点是：

- semantic surface 已经正式发布 attribute-step `ClassName.new(...)` 的 `FrontendResolvedCall(callKind = CONSTRUCTOR)` 事实，因此 compile gate 当前并不会把这条 route 作为“尚未识别的 surface”拦住
- 当前真正未闭合的是 constructor materialization：
  - body lowering 仍对 `CONSTRUCTOR` fail-fast
  - bare direct constructor（例如 `Array(...)`、`Vector3i(1, 2, 3)`）还没有进入同一条 published call surface
- 因此“constructor route 当前阶段完全不支持”并不是准确的事实描述；更准确的表述应当是“constructor 语义路由已就绪，但 executable-body lowering / backend 闭合仍待补齐”

当前仍然保持封口或 staged 的部分包括：

- `PROPERTY_INIT` CFG / body lowering
- `PARAMETER_DEFAULT_INIT` CFG / body lowering
- `ConditionalExpression`
- `CastExpression`
- `TypeTestExpression`
- `ArrayExpression`
- `DictionaryExpression`
- `PreloadExpression`
- `GetNodeExpression`
- compound assignment
- callable-value invocation

`ConditionalExpression` 当前继续 compile-block 的原因已经固定：

- graph 虽已具备分支区域与 merge slot 基础设施
- 但 branch-result merge、ownership、evaluation-order 语义还没有单独冻结

---

## 9. 回归锚点

涉及本文档合同的改动，至少要继续覆盖以下回归锚点：

- `FrontendLoweringBuildCfgPassTest`
  - deterministic graph shape / node id
  - AST identity keyed region lookup
  - value-op operand/result id contract
  - nested call/member/subscript evaluation order
  - non-bool condition region
  - condition/value short-circuit subgraph
  - `break` / `continue`
- `FrontendLoweringBodyInsnPassTest`
  - body lowering 只消费 graph + published facts
  - declaration / assignment / call 不做第二套 AST 递归 lower
  - value-context `and/or` 的 LIR 形态
  - subscript-step published type consumption
  - fully-terminated `if` chain 的 synthetic terminal-merge stop 不得产出 `ReturnInsn(null)`
  - compile gate 绕过时的 lowering exception 质量
- `FrontendLoweringPassManagerTest`
  - 默认 pipeline 顺序
  - executable body materialization
  - property initializer shell-only 边界
- `FrontendCompileCheckAnalyzerTest`
  - step-level / expression-level compile anchor
  - `AttributeSubscriptStep` published fact 的 compile blocker 行为
  - `ConditionalExpression` 继续 compile-block
- `FrontendVarTypePostAnalyzerTest`
  - parameter / typed local / `:=` local 的 slot type publication
  - duplicate / shadowing local 的 fail-closed 行为

---

## 10. 架构反思与后续要求

当前实现已经沉淀出以下长期有效的工程结论：

- frontend CFG 是 frontend-only 中间层，不是 legacy block bundle 的增强版，也不是 future HIR 的缩写版
- `FrontendLoweringBuildCfgPass` 是唯一的 CFG 构图入口
- `FrontendLoweringBodyInsnPass` 只消费 graph + published facts，不能回退成第二套语义分析器
- compile gate 负责“能否进入 lowering”，body lowering 负责“如何 materialize 已允许的 surface”
- merge result 必须继续以 merge slot 为中心建模，而不是强行恢复唯一 producer 幻觉

后续若继续扩张 lowering surface，必须优先遵守以下顺序：

1. 先冻结 published fact / compile gate / graph item 合同
2. 再接 `FrontendLoweringBuildCfgPass`
3. 最后接 `FrontendLoweringBodyInsnPass`

不得反过来用 body lowering 的局部实现去倒逼 semantic side table 或 compile gate 临时补洞。

---

## 11. 文档同步要求

只要 CFG graph、body lowering 或 compile-ready surface 合同发生变化，至少要同步更新：

- `frontend_lowering_plan.md`
- `frontend_lowering_func_pre_pass_implementation.md`
- `frontend_compile_check_analyzer_implementation.md`
- `frontend_chain_binding_expr_type_implementation.md`
- `frontend_analysis_inspection_tool_implementation.md`
- `frontend_loop_control_flow_analyzer_implementation.md`
- `diagnostic_manager.md`
- 本文档

---

## 12. Constructor Lowering 实施计划

本节用于收口当前 constructor 支持面的不一致：compile-ready semantic surface 已经允许 `.new(...)` constructor fact 进入 lowering，但 body lowering 仍显式拒绝；同时 direct builtin constructor 还没有进入正式 published call surface。目标是把 executable body 中的 constructor 调用闭合为一条完整链路，而不是继续让 compile gate、CFG、body lowering、backend 各自维护半套事实。

### 12.1 背景与问题拆分

当前缺口实际上分成两类：

- attribute constructor route
  - 例如 `Worker.new(...)`、`Node.new()`
  - `FrontendChainReductionHelper` 已能发布 `FrontendResolvedCall(callKind = CONSTRUCTOR)`，但 `FrontendSequenceItemInsnLoweringProcessors.FrontendCallInsnLoweringProcessor` 仍直接抛出 “constructor route lowering is not implemented yet”
- bare direct constructor route
  - 例如 `Array(...)`、`Dictionary(...)`、`Vector3i(1, 2, 3)`、`Color(1, 0, 0, 1)`
  - 当前 bare call 语义仍按 ordinary function overload 处理，没有把 bare `TYPE_META` callee 视为 constructor head，因此这类写法尚未进入与 `.new(...)` 相同的 published `resolvedCalls()` surface

此外，constructor lowering 还跨越 frontend/backend 边界：

- LIR 已有 `ConstructBuiltinInsn` 与 `ConstructObjectInsn`
- C backend 已能消费 `ConstructBuiltinInsn`
- `ConstructObjectInsn` 虽然存在于 LIR opcode 面与文档中，但当前 `ConstructInsnGen` 尚未接通 `CONSTRUCT_OBJECT`

因此，本计划必须同时定义 semantic publication、CFG/body lowering materialization 与 backend 闭合，不能只修一个 `switch` 分支。

### 12.2 目标合同

本轮完成后，executable body 中的 constructor 调用应统一满足以下合同：

- `.new(...)` 与 bare direct constructor 都通过正式 `FrontendResolvedCall` surface 进入 lowering
- 两种语法入口都继续复用现有 `CallItem`
  - 不新增专用 constructor CFG item
  - CFG 只负责冻结 operand 顺序、anchor 与 result value id
- body lowering 根据已发布的 constructor metadata 直接选择 LIR 指令族
  - builtin target -> `ConstructBuiltinInsn`
  - object target -> `ConstructObjectInsn`
  - gdcc custom class constructor route 只表达“构造 object target”这一 lowering 意图，不在 lowering 中追加 `_init(...)` 调用
  - 零参数 `_init(self)` 若存在，由 runtime postinitialize / class constructor path 自动触发；带参 `_init(...)` 必须在语义阶段与 compile gate 双重 fail-closed
- ordinary call 的 owner 边界保持不变
  - constructor 不伪装成 static/instance method
  - body lowering 不重跑 overload 选择，不反推语义

本轮 scope 仅覆盖 compile-ready `EXECUTABLE_BODY`。`PROPERTY_INIT` / `PARAMETER_DEFAULT_INIT` 仍按现有 staged 计划保留，不借 constructor 支持顺带打开。

### 12.3 第一步：统一 published constructor call surface

- 状态：已完成（2026-04-04）
- 已落地产出：
  - bare callee binding 现在允许在函数查找失败后回退到 `TYPE_META`，使 `Array(...)`、`Vector3i(...)` 等 builtin direct constructor 能进入正式 constructor route
  - `.new(...)` 与 bare builtin direct constructor 统一复用 `FrontendConstructorResolutionSupport` 做 constructor overload 选择与 detail reason 生成
  - published constructor metadata 继续收口在现有 `FrontendResolvedCall` / `declarationSite` 合同上，zero-arg engine/default constructor 不再强制伪装成 `FunctionDef`

需要完成的工作：

1. 扩展 bare direct constructor 的语义入口
   - 在 bare `CallExpression` 语义处理中，把 bare callee 绑定为 `TYPE_META` 的情况识别为 constructor route，而不是继续走 ordinary function lookup
   - `Array(...)`、`Vector3i(...)`、`Color(...)` 等 builtin direct constructor 与 object-type bare constructor 都要复用同一套 constructor resolution，而不是新增一套 bare-only overload 规则
2. 保持 `.new(...)` 与 bare direct constructor 使用统一的 `FrontendResolvedCall.callKind() == CONSTRUCTOR`
   - 两条语法入口允许使用不同 anchor：
     - `.new(...)` -> `AttributeCallStep`
     - bare direct constructor -> `CallExpression`
   - 但 downstream 不得再把 syntax shape 当成 route kind
3. 冻结 constructor published metadata 的最小必需面
   - lowering 至少需要稳定拿到：
     - constructed target type
     - fixed parameter types
     - declaration site / owner metadata
     - 是否属于 compile-ready 的 zero-arg object creation route
   - 不允许再依赖“declarationSite 一定是 `FunctionDef`”这种只对部分 constructor 成立的前提

实施要求：

- 优先在现有 `FrontendResolvedCall` / `declarationSite` contract 上补齐 constructor 元数据消费能力，避免为了 constructor 单独复制一套 call side table
- 若现有 `declarationSite` 不能无歧义表达 builtin / engine / gdcc constructor 所需信息，应补一个面向 lowering 的最小 metadata carrier，但必须保持私有且贴近 lowering 邻域，不能在 public model 层引入只服务一个消费者的空泛抽象

验收细则：

- `FrontendExpressionSemanticSupportTest`
  - direct builtin constructor 正向样本：
    - `Array()`
    - `Vector3i(1, 2, 3)`
  - object `.new(...)` 正向样本：
    - `Node.new()`
    - gdcc `Worker.new()`
- `FrontendChainBindingAnalyzerTest`
  - bare direct constructor 与 `.new(...)` 都发布 `callKind = CONSTRUCTOR`
  - builtin constructor 会先按 applicability 过滤，再按“更具体者优先”排序；若仍无法稳定区分，则继续以语义诊断 fail-closed，而不是异常下沉
  - non-instantiable engine class 继续失败并保留精确 detail reason

### 12.4 第二步：补齐 body lowering 的 constructor materialization

- 状态：已完成（2026-04-04）
- 已落地产出：
  - `FrontendCfgGraphBuilder` 已接通 type-meta chain head call materialization，`Node.new()` / `Worker.new()` 不再把 type-meta base 误当运行时值去求值
  - `FrontendLoweringBodyInsnPass` 已根据 published constructor metadata 正式发出 `ConstructBuiltinInsn` / `ConstructObjectInsn`
  - builtin direct constructor 继续通过 callable signature metadata 做 fixed-parameter boundary materialization；gdcc custom class constructor 则收紧为零参数实例创建，不再发 `_init(...)` follow-up
  - 缺失 callable signature metadata 的带参 builtin constructor route 现在会以明确的 publication-contract 错误 fail-fast

需要完成的工作：

1. 让 `FrontendCallInsnLoweringProcessor` 正式接通 `CONSTRUCTOR`
   - 继续通过 `session.materializeCallArguments(...)` 做 fixed-parameter boundary materialization
   - constructor route 不得绕过现有 `(un)pack` 规则
2. 在 `FrontendBodyLoweringSession` 中补 constructor-aware signature consumption
   - 现有 `requireResolvedCallableSignature(...)` 只能处理 `FunctionDef`，需要替换为 constructor-aware 的 metadata 读取路径
   - 该路径必须能统一覆盖：
     - builtin constructor metadata
      - engine object zero-arg constructor
3. 按 target family 选择实际 LIR：
   - builtin target：
     - 发 `ConstructBuiltinInsn(result, args)`
     - 不再伪装成 `CallGlobalInsn` 或 `CallStaticMethodInsn`
   - object target：
      - 先发 `ConstructObjectInsn(result, className)`
      - engine object 与 gdcc custom object 的零参数创建都只发 `ConstructObjectInsn`
      - gdcc 带参 constructor route 在 compile gate 前就必须 fail-closed，不能下沉到 lowering

需要特别校准的点：

- gdcc class `.new(...)` 是否可以直接把 class name 交给 `construct_object`
  - 若引擎能通过已注册的 extension class 正常回调到 `create_instance`，则直接沿用 `ConstructObjectInsn(className)`
  - 若不能，则需要在 backend 引入 dedicated gdcc-object construction helper，但这不改变 frontend lowering contract；frontend 仍只发布“构造一个 object target”的统一 LIR 意图
- builtin `Array` / `Dictionary` direct constructor 不应退化为 zero-arg 专用路径
  - direct source-level constructor 必须允许带 runtime argument
  - typed container 的具体 C 构造策略继续留给 backend `CBuiltinBuilder` 按 result type 决定

验收细则：

- `FrontendLoweringBodyInsnPassTest`
  - `Vector3i(1, 2, 3)` lower 为 `ConstructBuiltinInsn`
  - `Array(source)` lower 为 `ConstructBuiltinInsn`
  - `Node.new()` lower 为 `ConstructObjectInsn`
  - gdcc `Worker.new()` lower 为 `ConstructObjectInsn`
  - gdcc custom object constructor 不再追加 `_init` method call
- negative path
  - 缺失 constructor metadata 时继续 fail-fast，异常消息必须指出是 constructor publication contract 缺失，而不是笼统 “call route is not lowering-ready”

### 12.5 第三步：补齐 backend `CONSTRUCT_OBJECT`

- 状态：已完成（2026-04-04）
- 已落地产出：
  - `ConstructInsnGen` 已注册并实现 `CONSTRUCT_OBJECT`，对 engine class 直调 `godot_new_XXX()`；对非 `RefCounted` gdcc class 直调 `XXX_class_create_instance(NULL, true)`
  - 对确定继承 `RefCounted` 的 gdcc class，外部 C constructor call site 现在改为 `XXX_class_create_instance(NULL, false)` 后再执行 `gdcc_ref_counted_init_raw(..., true)`；`*_class_create_instance(...)` 自身保持原始 native object create/bind helper，不内嵌这一步
  - 通过引擎函数或 GDScript 创建继承 `RefCounted` 的 gdcc class 时，引用计数初始化继续由 Godot 自身创建路径负责；frontend/backend 不在共享的 `*_class_create_instance(...)` helper 中重复执行该初始化
  - backend 继续复用既有 object slot write / ptr conversion / ownership consume 语义，不单独分叉 gdcc vs engine object lifecycle
  - frontend executable function shell 现在会在 preparation pass 注入前置 `self` 参数，使 frontend-lowered GDCC instance method / constructor 与 backend 既有调用约定重新对齐
  - runtime class constructor 只会自动触发零参数 `_init(self)`；带参 custom constructor route 已在 frontend compile gate 回退为不支持

需要完成的工作：

1. 在 `ConstructInsnGen` 中注册并实现 `CONSTRUCT_OBJECT`
   - 根据 `ConstructObjectInsn.className` 生成 object construction 代码
   - 复用现有 object ownership / refcount 规则，不新增另一套 lifecycle 约定
2. 验证 gdcc class 与 native/engine class 的 object construction 行为
   - `RefCounted` 派生对象的 owned contract 必须保持正确
   - 非 instantiable class 必须在更早阶段被 frontend semantic 拦下，不把 backend 变成语义兜底
3. 若 gdcc class object construction 需要 special helper 才能正确走 extension registration callback，应把 helper 收口在 backend，不把这层差异泄漏回 frontend lowering

验收细则：

- `CConstructInsnGenTest`
  - 新增 `ConstructObjectInsn` unit tests
  - 覆盖 engine/native object class 与 refcounted object class
- `FrontendLoweringToCProjectBuilderIntegrationTest`
  - 新增 frontend 全链路 compile + runtime test，验证 builtin direct constructor、engine constructor、gdcc zero-arg custom constructor、`Object`-typed custom object local route 都可在真实引擎中运行
- Godot engine integration test
  - 参考现有 `GodotGdextensionTestRunner` / engine test 形态，至少覆盖：
    - `Vector3i(1, 2, 3)` 在引擎内返回分量正确
    - `Node.new()` 在引擎内可被真实构造并可观测到 `get_class() == "Node"`
    - gdcc `Worker.new()` 调用后可观测到零参数 `_init` / class constructor 初始化结果
    - custom object 构造结果可进入 `Object` typed route 并继续执行 engine-side instance method

### 12.6 第四步：同步 compile-ready surface 与事实源描述

- 状态：已完成（2026-04-04）
- 已落地产出：
  - constructor route 的事实源已收口为：builtin direct constructor、engine zero-arg `.new()`、gdcc zero-arg `.new()` 均闭合；gdcc 带参 constructor 则在 semantic / compile gate 双重 fail-closed
  - frontend factsource 已明确：`.new(...)` 与 bare builtin direct constructor 共享 `FrontendResolvedCall(callKind = CONSTRUCTOR)` surface，body lowering 负责选 `ConstructBuiltinInsn` / `ConstructObjectInsn`，backend 负责消费 object construction
  - 相关事实源已同步到 chain-binding / compile-check / LIR / C backend 文档，避免继续出现 “compile-ready 已放行但 backend 仍整体 unsupported” 的互相矛盾描述

需要完成的工作：

1. 更新本文档中的“当前支持面与保留封口”
   - constructor 不再以“整体 route 不支持”的表述存在
   - 改为区分：
     - published constructor semantic surface
     - direct builtin constructor publication
     - body/backend materialization 是否闭合
2. 同步相关事实源
   - `frontend_lowering_plan.md`
   - `frontend_chain_binding_expr_type_implementation.md`
   - `frontend_compile_check_analyzer_implementation.md`
   - 若 backend 实现发生变化，还需同步 `gdcc_low_ir.md` / `gdcc_c_backend.md` 与 backend implementation docs

验收细则：

- 文档中的 compile-ready surface、fail-fast gap、测试锚点三处描述必须彼此一致
- 不再出现“一处文档宣称 constructor 已 compile-ready，另一处仍写当前阶段整体 unsupported”的冲突

### 12.7 2026-04-05 后续硬化与一致性收口

- 状态：已完成（2026-04-05）
- 调查结论：
  - `FrontendCompileCheckAnalyzer` 仍保留“gdcc 带参 constructor route 专用 compile gate 兜底”，但这只能阻止 compile mode 下沉，不能替代 declaration-level 语义错误
  - 当前 `12.2 目标合同` 仍保留“gdcc class 且命中显式 `_init(...)` 时补 follow-up call”的旧表述，已与 `12.4`、测试和实际实现冲突
  - 当前 backend 对 gdcc `RefCounted` 自定义类的显式 C 构造虽然已使用外部 `gdcc_ref_counted_init_raw(...)`，但若 shared create helper 仍先发 `POSTINITIALIZE`，就会形成“先跑 class constructor，再初始化 refcount”的隐藏时序问题
  - `FrontendConstructorResolutionSupport.chooseConstructor(...)` 目前仍是“applicable 过滤后，`size() > 1` 直接报歧义”的 fail-closed 基线，尚未具备“更具体者优先”的排序能力

已落地产出：

1. 语义阶段显式拒绝 gdcc 带参 `_init(...)`
   - 在共享语义分析阶段，对 gdcc 自定义类中声明了一个或多个参数的 `_init` 直接发错误诊断
   - 诊断锚点前移到 declaration 本身，不再依赖 `.new(...)` 调用站点或 compile gate 才暴露
   - compile gate 对 parameterized constructor route 的兜底继续保留，用于防回归
2. 修正 12.2 / 12.4 的合同一致性
   - 删除了“lowering 阶段补 `_init(...)` follow-up call”的旧描述
   - constructor lowering contract 已明确收口为 `ConstructBuiltinInsn` / `ConstructObjectInsn`
   - 零参数 `_init(self)` 属于 runtime postinitialize 路径；带参 `_init(...)` 属于 semantic / compile gate 拒绝路径
3. 修复 gdcc `RefCounted` 自定义类的显式 C 构造时序
   - 对“生成的 C 代码外部显式创建 gdcc `RefCounted` 对象”的 call site，已改为：
     - `XXX_class_create_instance(NULL, false)`
     - `gdcc_ref_counted_init_raw(..., true)`
   - `*_class_create_instance(...)` 继续保持 shared create/bind helper，不内嵌 `gdcc_ref_counted_init_raw(...)`
   - 非 `RefCounted` gdcc 对象继续走 `XXX_class_create_instance(NULL, true)`
   - 通过 Godot 引擎函数或 GDScript 创建继承 `RefCounted` 的 gdcc 类时，引用计数初始化继续由 Godot 自身创建路径负责
4. 落地 builtin constructor overload 排序策略
   - `FrontendConstructorResolutionSupport` 不再把“多个 applicable candidate”直接视为歧义
   - 现在的选择流程为：
     - 第一步：按参数个数、默认参数可省略性、vararg 资格与 frontend boundary compatibility 做 applicability 过滤
     - 第二步：在 applicable pool 内比较逐参数转换质量与目标类型具体度，优先更具体的 candidate
     - 第三步：若仍存在多个 equally-specific candidate，则继续以语义失败 + 诊断信息 fail-closed，而不是抛异常让 lowering/backend 再 fail-fast
   - 当前转换质量基线与 method-call 选择思路保持一致：
     - exact/direct 优先于 pack/unpack/null-literal 边界
     - 更窄的目标类型优先于更宽的目标类型
     - 非 vararg、较少依赖 trailing default 的 candidate 优先

验收细则：

- 语义阶段
  - `FrontendTypeCheckAnalyzerTest` 新增 declaration-level negative test，验证带参 gdcc `_init(...)` 即使未被调用也会产生明确错误诊断
  - 保留正向样本，验证零参数 `_init()` 不会被误报
- compile gate
  - `FrontendCompileCheckAnalyzerTest` 继续保留 regression guard，确认即使上游错误地把 route 重新发布为 resolved，compile gate 仍会阻止其进入 lowering
- backend/codegen
  - `CConstructInsnGenTest`、`CConstructInsnGenEngineTest`、`FrontendLoweringToCProjectBuilderIntegrationTest` 锚定：
    - gdcc `RefCounted` 显式 C 构造使用 `create_instance(..., false)` + `gdcc_ref_counted_init_raw(..., true)`
    - 非 `RefCounted` gdcc 显式构造继续使用 `create_instance(..., true)`
    - shared `*_class_create_instance(...)` 本体不内嵌 `gdcc_ref_counted_init_raw(...)`
- 文档
  - `12.2`、`12.4` 与本章节之间不再出现“lowering 会补 `_init(...)`”和“实际已 fail-closed”并存的冲突
  - constructor overload 排序策略已被明确记录为当前合同，remaining ambiguity 继续走语义诊断而不是异常 fail-fast

### 12.7 建议提交顺序

为了避免再次出现“上游已放行、下游仍拒绝”的半开状态，建议按以下顺序落地：

1. semantic publication contract
2. body lowering constructor branch
3. backend `CONSTRUCT_OBJECT`
4. integration / engine tests
5. factsource 文档统一收口

其中第 1 步与第 2 步之间不应产生可进入默认 compile pipeline 但必定在 lowering 中抛错的中间提交；如果需要分提交，compile gate 或测试选择必须保证每个提交都处于自洽状态。

---

## 13. 空 Return 图修复状态

2026-04-05 本轮已同步完成以下收口：

- [x] `FrontendCfgGraph.StopNode` 增加 `kind`，显式区分真实 `RETURN` 与 synthetic `TERMINAL_MERGE`
- [x] `FrontendCfgGraphBuilder` 对 fully-terminated `if` 链发布 `TERMINAL_MERGE`，不再与真实 bare `return` 共享同一种 stop 语义
- [x] `FrontendBodyLoweringSession` / `FrontendStopNodeInsnLoweringProcessor` 不再为 `TERMINAL_MERGE` 创建 basic block 或发出 `ReturnInsn`
- [x] 回归测试已覆盖 graph shape、CFG builder 与 body lowering，锚定“fully-terminated branch chain 不再错误地产出空 `ReturnInsn`”

---

## 14. Compound Assignment 实施计划

本节用于收口当前 `+=` / `-=` / `*=` 等 compound assignment 的支持缺口。当前 parser/gdparser 已经把这些语法稳定映射为 `AssignmentExpression.operator()` 的一部分，但 frontend 共享语义仍将其整体冻结为 `UNSUPPORTED`；同时 CFG/body lowering 的现有 `AssignmentItem` 只表达“把已算出的 RHS 提交到 target”，并不表达 read-modify-write。因此这个缺口不能只靠放宽 analyzer 解决，必须同时冻结语义合同、CFG 求值顺序和 body lowering materialization。

### 14.1 背景与现状缺口

当前事实已经可以明确拆成四条：

- 语言/语法层
  - gdparser grammar 已识别完整的 compound assignment token 集：`+=`、`-=`、`*=`、`/=`、`%=`、`**=`、`>>=`、`<<=`、`&=`、`^=`、`|=`
  - 这些 token 最终都会落到同一类 `AssignmentExpression`，operator lexeme 已可直接从 AST 读取
- 共享语义层
  - `FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(...)` 当前对所有 `operator != "="` 的 assignment 直接返回 `UNSUPPORTED`
  - `FrontendExprTypeAnalyzer` 因而会发布 `sema.unsupported_expression_route`
- CFG build 层
  - `FrontendCfgGraphBuilder.buildAssignmentCommit(...)` 当前不区分 operator，只会构造“target operands + rhs + AssignmentItem”的 plain-store 路径
  - 如果简单取消上游 `UNSUPPORTED`，当前 CFG 会把 `a += b` 错误地降成 `a = b`
- body lowering 层
  - `AssignmentTargetInsnLoweringProcessors` 只负责最终 store
  - `BinaryOpInsn` / `GodotOperator` / `MemberLoadItem` / `SubscriptLoadItem` 已存在，可以复用，但当前没有一个已冻结的 CFG item/顺序去表达 compound assignment 所需的“先读旧值，再做运算，再回写”

因此，compound assignment 当前不是“只差一个 case 分支”，而是一个 read-modify-write 合同缺口。

### 14.2 目标合同

本轮实施后，frontend 对 compound assignment 必须满足以下冻结合同：

- 支持面
  - 先按 parser 已识别的闭合集合实现：`+=`、`-=`、`*=`、`/=`、`%=`、`**=`、`>>=`、`<<=`、`&=`、`^=`、`|=`
  - 不在本轮顺带扩张到新的 source syntax family
- 表达式合同
  - success root 继续发布 `RESOLVED(void)`
  - statement-position 的 compound assignment success root 继续不发 discarded warning
  - value-required 的 nested compound assignment 继续 fail-closed，不把 assignment 当 ordinary value
- target 支持面
  - 与 plain assignment 保持一致：bare identifier、attribute property、plain subscript、attribute-subscript
  - 不借 compound assignment 顺带打开新的 assignment target family
- 求值顺序
  - 不允许通过“把 AST 机械改写成 `lhs = lhs <op> rhs`”实现
  - target receiver/index/prefix 必须只求值一次
  - 冻结顺序为：
    1. 先求值 assignment target 需要冻结的 receiver/index/prefix operand
    2. 基于这些已冻结 operand 读取 target 当前值
    3. 再求值 RHS
    4. 对“当前值”和 RHS 应用对应 binary operator
    5. 最后沿现有 assignment store route 回写
- 类型与边界合同
  - compound operator 的类型判定复用现有 binary operator 语义，而不是另造一套 operator typing 规则
  - 最终写回 target 时，继续复用现有 assignment compatibility 与 ordinary `(un)pack Variant` boundary
  - 不允许为了 compound assignment 在 lowering 中临时重跑 member/call/subscript 语义

### 14.3 第一步：冻结共享语义合同

- 状态：已完成（2026-04-05）

- 已落地产出：
  - `FrontendAssignmentSemanticSupport` 不再把 compound assignment 一律判成 `UNSUPPORTED`；当前已接通 parser 已识别的闭合集合：`+=`、`-=`、`*=`、`/=`、`%=`、`**=`、`>>=`、`<<=`、`&=`、`^=`、`|=`
  - compound assignment 现在复用 `FrontendExpressionSemanticSupport` 抽出的 shared binary-operator typing 入口，不再依赖 synthetic `BinaryExpression` AST 回灌 analyzer
  - operator 对 operand 不成立时，现在与普通 binary expression 一样走 `sema.expression_resolution`；未知 compound token 仍保持 `sema.unsupported_expression_route`
  - 为避免在 `14.4` / `14.5` 落地前出现“shared semantic 已放行、CFG/body lowering 仍未实现”的半开状态，`FrontendCompileCheckAnalyzer` 已新增 temporary compile-only block，继续把 compound assignment 挡在 lowering 入口外

需要完成的工作：

1. 提取 compound operator 映射
   - 新增一个贴近 assignment semantic / lowering 邻域的私有 helper，把 `+=` -> `+`、`**=` -> `**`、`<<=` -> `<<` 这类 lexeme 映射冻结下来
   - 这份映射必须是 closed-set；遇到未知 compound lexeme 时返回明确的源码诊断，不允许把问题下沉到 lowering exception
2. 抽取可复用的 binary operator 类型判定入口
   - 当前 `FrontendExpressionSemanticSupport.resolveBinaryExpressionType(...)` 把“解析 operator”“special return type”“runtime-open fallback”“exact return type”写死在 AST 路径里
   - 需要抽出一个可被 assignment 复用的、基于 `operator + leftType + rightType` 的 shared helper
   - compound assignment 必须复用这套 helper，而不是构造 synthetic `BinaryExpression` AST 再递归回 analyzer
3. 在 `FrontendAssignmentSemanticSupport` 中接通 compound assignment
   - target 解析、writable 判定、final assignment compatibility 继续沿用现有 helper
   - compound route 新增中间步骤：
     - 取 target 当前值类型
     - 解析 compound binary result type
     - 校验该 result type 是否可写回 target slot
   - 成功时仍返回 `RESOLVED(void)`；中间 binary 结果即使是 runtime-open，也不改变 assignment root 的 `void` 合同
4. 保持 owner 边界与诊断语义稳定
   - operator 不受支持或仍显式冻结的情况继续走 `sema.unsupported_expression_route`
   - operator 对给定 operand type 不成立、或运算结果不可写回 target 的情况走 `sema.expression_resolution`
   - 在 `14.4` / `14.5` 落地前，compile-only gate 继续保留 explicit temporary block，避免 shared semantic 已放行但 lowering 尚未闭合

需要特别校准的点：

- compound assignment 需要“既可读又可写”的 target 语义
  - 不能只因为某个 route 可写，就跳过“读取当前值”的合法性约束
  - 对 attribute property / subscript route，要继续复用已发布的 member/subscript typed fact，而不是假设 writable 等于 readable
- `Variant` / `DYNAMIC` operand
  - compound route 的 operator typing 必须保持与 ordinary binary expression 一致
  - 不允许 assignment helper 私自引入第二套“compound 专属动态规则”

验收细则：

- `FrontendAssignmentSemanticSupportTest`
  - 正向：
    - typed local `count += 1`
    - typed property `self.hp -= 1`
    - typed subscript `items[i] |= mask`
    - `Variant` target `slot += 1` 走 runtime-open route 但 assignment root 仍是 `void`
  - 反向：
    - readonly target `locked += 1`
    - unsupported target kind 继续 fail-closed
    - value-required nested compound assignment 继续失败
    - operator result 不可写回 target，例如 `flag &= 1.5` 一类不兼容样本
- `FrontendExprTypeAnalyzerTest`
  - 正向样本不再产生 `sema.unsupported_expression_route`
  - 负向样本继续验证 diagnostic category、severity 与 detail reason

### 14.4 第二步：冻结 CFG build 的 read-modify-write 形状

- 状态：未开始（2026-04-05）

需要完成的工作：

1. 保持 `AssignmentItem` 只表示“最终 store commit”
   - 不把 `AssignmentItem` 扩展成既读旧值又做运算又回写的混合节点
   - compound assignment 需要的“读取旧值”和“应用 binary operator”应由前置 value item 显式表达
2. 新增 compound assignment 专用 value item
   - 该 item 至少要冻结：
     - assignment anchor
     - compound operator
     - current-target-value id
     - rhs value id
     - computed-result value id
   - 目标是让 body lowering 能直接 materialize `BinaryOpInsn`，而不必重新理解 assignment AST
3. 新增“基于已冻结 target operands 读取当前值”的 builder helper
   - 不能直接对原始 lhs 再调用 `buildValue(...)` 或 `buildAssignmentTargetValue(...)`
   - 因为这会重复求值 target receiver/index/prefix，破坏 compound assignment 的单次求值合同
   - 新 helper 需要消费 `buildAssignmentTargetOperands(...)` 的产物：
     - bare identifier：复用最窄的 identifier read route
     - attribute property：发 `MemberLoadItem`
     - plain subscript：发 `SubscriptLoadItem`
     - attribute-subscript：复用现有 named-member + keyed load 形状
4. 新增 `buildCompoundAssignmentCommit(...)`
   - 顺序固定为：
     1. `buildAssignmentTargetOperands(...)`
     2. `emit/read current target value from frozen operands`
     3. `buildValue(rhs, ...)`
     4. 发 compound-op value item
     5. 发 `AssignmentItem(..., computedResultValueId, null)`

需要特别校准的点：

- compound route 不得退化成“target operands -> rhs -> store”
  - 这会丢失旧值读取
- compound route 也不得退化成“重新 build lhs read path”
  - 这会重复执行 prefix call / subscript index / attribute chain side effect
- 现有 plain assignment graph shape 不能被破坏
  - `=` 路径继续保持当前 `AssignmentItem` 最小形状

验收细则：

- `FrontendCfgGraphBuilderTest`
  - identifier target：`count += step()` 产出“load current -> rhs -> compound-op -> assignment commit”
  - attribute property target：`self.hp += heal()` 产出单次 receiver 求值
  - subscript target：`items[next_index()] += produce()` 中 `next_index()` 只能出现一次
  - attribute-subscript target：`holder.payloads[i] += delta()` 继续保持 named-member + keyed route
- 负向锚点
  - compile gate 绕过时，如缺失 compound target read 所需 published fact，builder 报错必须指向 compound-assignment publication contract 缺口，而不是误报普通 assignment

### 14.5 第三步：接通 body lowering materialization

- 状态：未开始（2026-04-05）

需要完成的工作：

1. 在 `FrontendSequenceItemInsnLoweringProcessors` 注册 compound-op value item processor
   - 直接把它 lower 为 `BinaryOpInsn`
   - operator 通过第 1 步冻结的 compound-operator 映射得到底层 `GodotOperator`
2. 保持 store 路径继续由现有 assignment target processors 负责
   - compound route 的最终写回仍然经过 `FrontendAssignmentTargetInsnLoweringProcessors`
   - ordinary boundary materialization 继续只在“最终写回 target”处统一处理
3. 明确不新增新的 LIR opcode
   - 现有 `BinaryOpInsn` + `AssignInsn` / `StorePropertyInsn` / `variant_set_*` 足以覆盖 compound assignment
   - compound support 的核心改动应留在 frontend semantic + CFG item shape，不把需求错误地下推到 LIR 层

需要特别校准的点：

- subscript/property compound store 的 write-back 合同不能回退
  - 例如 property-backed container base 的 write-back 仍必须保留
- `Variant` boundary 只处理 target/store contract
  - 不要在 compound-op processor 中临时插入另一套与 ordinary binary lowering 不一致的 pack/unpack

验收细则：

- `FrontendLoweringBodyInsnPassTest`
  - typed local `count += 1` lower 为 current-load + `BinaryOpInsn(ADD)` + `AssignInsn`
  - typed property `self.hp -= 1` lower 为 member/property read + `BinaryOpInsn(SUBTRACT)` + store
  - subscript `items[i] <<= 1` lower 为 current-load + `BinaryOpInsn(SHIFT_LEFT)` + keyed/indexed store
  - `Variant` target / concrete RHS 继续锚定最终 store 前的 ordinary boundary materialization
- 负向锚点
  - 若 compile gate 被绕过且 compound item 缺少 lowering-ready metadata，body lowering 的异常必须明确指出是 compound-assignment contract 缺失，而不是笼统 “unsupported sequence item”

### 14.6 第四步：同步 compile-ready surface、文档与端到端测试

- 状态：未开始（2026-04-05）

需要完成的工作：

1. 同步事实源文档
   - `frontend_chain_binding_expr_type_implementation.md`
   - `frontend_compile_check_analyzer_implementation.md`
   - `frontend_rules.md`
   - 如诊断类别/owner 描述发生变化，再同步 `diagnostic_manager.md`
2. 更新本文档中的“当前支持面与保留封口”
   - compound assignment 不再列为 staged/unsupported
   - plain assignment 与 compound assignment 的合同差异要写清楚，避免以后再次把它们混成一条 store-only 路径
3. 添加端到端回归
   - 至少保留一个 compile + run smoke case，验证 while/if 里的高频 `+=` / `-=` 用法在真实引擎端可运行并返回正确结果

验收细则：

- 文档
  - 本文档、assignment contract 文档、compile gate 文档之间不再出现“一处已支持、另一处仍写 unsupported”的冲突
- 单元测试
  - semantic / CFG / body lowering 三层都同时覆盖 happy path 与 negative path
- 端到端测试
  - 参考现有 engine/integration test 形态，至少锚定：
    - accumulator while-loop 中的 `+=`
    - property route 中的 `+=`
    - subscript route 中的 `+=` 或 bitwise compound operator

### 14.7 建议落地顺序

为避免再次出现“上游已放行、下游仍误编译成 plain assignment”的半开状态，建议按以下顺序提交：

1. 先冻结共享语义与诊断合同
2. 再引入 CFG compound item 与单次求值顺序
3. 再接 body lowering
4. 最后统一文档与端到端测试

第 1 步和第 2 步之间不应留下“compile gate 能放行，但 CFG 仍把 compound assignment 编错”的中间提交；如需拆分提交，必须通过 compile gate 或测试选择保证每个提交都处于自洽状态。
