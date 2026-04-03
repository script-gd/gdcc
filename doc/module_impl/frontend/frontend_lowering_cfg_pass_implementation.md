# Frontend Lowering CFG Pass 实现说明

> 本文档作为 frontend lowering 中 CFG build / body materialization 工程的长期事实源，记录当前已经落地的 frontend-only CFG graph、`FrontendLoweringBuildCfgPass` 与 `FrontendLoweringBodyInsnPass` 合同、published-fact 消费边界、legacy block-bundle 迁移结论，以及对后续 property initializer / parameter default / condition-value feature 仍然有效的架构约束。本文档吸收并取代原 `frontend_lowering_cfg_graph_plan.md` 与 `frontend_lowering_cfg_pass_plan.md` 的已完成内容，不再保留步骤编号、验收流水账或阶段进度记录。

## 文档状态

- 状态：事实源维护中（executable-body CFG build / body lowering 已落地；property initializer 与 parameter default 仍保持 staged）
- 更新时间：2026-04-03
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
  - `returnValueIdOrNull`

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

- `FrontendBodyLoweringSupport` 可以直接读取 subscript step 的 published expression type
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
- 将 graph node id materialize 为 `LirBasicBlock.id`
- 将 `SequenceNode` lower 成 instruction 序列
- 将 `BranchNode` lower 成 bool-only branch 终结
- 将 `StopNode` lower 成 `ReturnInsn`

当前 executable-body 已接通的 lowering surface包括：

- identifier / literal / self
- eager unary
- 非短路 eager binary
- bare/global/static/instance method call
- member/property access
- subscript
- assignment
- source anchor / local declaration / merge result

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
- constructor route
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
