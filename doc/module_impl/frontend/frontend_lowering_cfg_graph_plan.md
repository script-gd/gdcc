# Frontend Lowering Frontend CFG Graph 实施计划

> 本文档是 frontend lowering 第二阶段的当前主计划，定义“frontend-only CFG graph”及其 condition-evaluation-region 合同。它替代旧的 `frontend_lowering_cfg_pass_plan.md` 作为当前实施依据。新 CFG 必须在 `frontend.lowering.cfg` 包中独立实现，只由 `FrontendLoweringBuildCfgPass` 构建；现有 `FrontendLoweringCfgPass` 与 `FunctionLoweringContext.cfgNodeBlocks` 只应视为废弃中的过渡实现，尚未完成最终 CFG 架构，后续必须迁移并删除。

## 文档状态

- 状态：实施中（第 1、2、3、4、5、6、7 阶段已完成；显式 `and` / `or` 短路构图与 body lowering 仍待迁移）
- 更新时间：2026-04-02
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
  - `frontend_lowering_cfg_pass_plan.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `diagnostic_manager.md`
  - `doc/gdcc_low_ir.md`
- 本轮调研额外参考：
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/ASTWalker.java`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/ASTNodeHandler.java`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/BreakStatement.java`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/ContinueStatement.java`
  - `godotengine/godot: modules/gdscript/gdscript_analyzer.cpp`
  - `godotengine/godot: modules/gdscript/gdscript_byte_codegen.h`
- 明确非目标：
  - 当前计划不直接引入 high-level IR / sea-of-nodes
  - 当前计划不在本轮同时完成完整 statement / expression lowering
  - 当前计划不在 frontend CFG graph 落稳前解除 `ConditionalExpression` 的 compile-only block
  - 当前计划不在第一轮把 property initializer / parameter default init 接入 frontend CFG materialization

---

## 1. 问题定义与当前事实

### 1.1 当前 pipeline 的稳定起点

当前默认 frontend lowering pipeline 固定为：

1. `FrontendLoweringAnalysisPass`
2. `FrontendLoweringClassSkeletonPass`
3. `FrontendLoweringFunctionPreparationPass`
4. `FrontendLoweringBuildCfgPass`
5. `FrontendLoweringCfgPass`（legacy，待删除）

当前稳定输入与边界：

- lowering 入口仍然固定为 `FrontendModule`
- compile-ready 语义事实统一来自 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
- function-shaped lowering 单元统一经由 `FunctionLoweringContext`
- public lowering 返回值仍然必须是 shell-only `LirModule`

这条链路当前已经稳定，后续 CFG 工程应在其上增量推进，而不是重新发明新的 lowering 入口。

### 1.2 当前 `FrontendLoweringCfgPass` 的真实状态

当前代码中的 `FrontendLoweringCfgPass` 已经存在，但它不是最终想要的 frontend CFG。

当前 pass 的真实能力只有：

- 读取 compile-ready `EXECUTABLE_BODY` context
- 校验 target function 仍保持 shell-only
- 在 `FunctionLoweringContext` 上发布 AST identity keyed 的 `CfgNodeBlocks`
- 用 `LirBasicBlock` 充当 metadata-only block skeleton

它当前还不能正确表达：

- `if` / `elif` 的显式 condition-entry region
- truthiness / condition normalization 的前置求值区域
- `and` / `or` / `not` 的短路控制流
- `while` 中 `break` / `continue` 的语义跳转
- `ConditionalExpression` 所需的条件求值与值合流入口

因此，仓库内不应再把它称为“minimal CFG lowering 已完成”。更准确的表述是：

- 当前 pass 已落地一个 legacy metadata-only skeleton
- 该 skeleton 为后续迁移提供了一部分稳定约束
- 但它仍然不是可消费的 source-level frontend CFG

### 1.3 当前语义合同对 CFG 层提出的要求

frontend 与 LIR 当前已经冻结了两条必须同时满足的事实：

- source-level `if` / `elif` / `while` / `assert` condition 仍然采用 Godot-compatible truthy contract
- backend / LIR 的 control-flow 仍然保持 bool-only 边界

这意味着 frontend CFG 层必须能表达：

- 条件值的求值过程
- 从“任意 stable typed value”到“最终 bool-only branch”的过渡
- 短路导致的多段前置条件区域

如果 CFG 层仍然只会发布“某个 AST 节点对应几个 block”，这个合同就无法闭合。

### 1.4 Godot 对 `and` / `or` 的真实语义

对照 Godot 当前 GDScript 实现，可以确认：

- `and` / `or` 不只是 condition context 下短路
- 它们对任意操作数类型都成立
- 它们始终返回 `bool`
- 即使在非 condition 的 value context 中，也通过跳转与结果写入来实现短路

当前调研依据包括：

- `gdscript_analyzer.cpp`
  - `OP_AND` / `OP_OR` “always return a boolean”
  - “don't use the Variant operator since they have short-circuit semantics”
- `gdscript_byte_codegen.cpp`
  - `write_and_left_operand(...)`
  - `write_and_right_operand(...)`
  - `write_end_and(target)`
  - `write_or_left_operand(...)`
  - `write_or_right_operand(...)`
  - `write_end_or(target)`

`write_end_and(target)` / `write_end_or(target)` 这组接口尤其关键，因为它们说明：

- `and` / `or` 即使作为普通表达式值被消费
- 也不是“先算左右值再做普通二元运算”
- 而是“控制流短路 + 向目标结果槽写 true/false”

因此，本项目的 frontend CFG 计划不能只在 `buildCondition(...)` 中对 `and` / `or` 特判；`buildValue(...)` 也必须把它们展开为多序列节点和分支节点。

### 1.5 compile-only gate 当前仍需保留的封口

`ConditionalExpression` 当前继续 compile-block，不是因为 parser 或 shared semantic 不支持，而是因为它的 lowering 依赖更强的 control-flow 表达层。

在 frontend CFG graph 尚未稳定前，不得提前解除：

- `ConditionalExpression`
- 任何依赖 condition-evaluation-region 的 future feature

---

## 2. 目标模型

### 2.1 总体方向

推荐新增一套与 legacy `FrontendLoweringCfgPass` 解耦的 frontend-only CFG graph。

这层 graph 的职责固定为：

- 只在 frontend/lowering 内部存在
- 代码组织上放在独立的 `dev.superice.gdcc.frontend.lowering.cfg` 包下
- 先表达 source-level control-flow 与 condition-evaluation-region
- 在真正写 LIR 前，把 source 语义整理成一个更稳健的中间层

这层 graph 不直接替代 future HIR，也不直接取代 LIR。它的定位是：

- 比当前 `CfgNodeBlocks` 更强，能表达 source 控制流
- 比 future sea-of-nodes 更小，先服务于 frontend -> LIR lowering

### 2.2 推荐 node 形状

第一轮保持 3 个 node kind 即可：

- `SequenceNode`
- `BranchNode`
- `StopNode`

推荐最小形状：

- `SequenceNode`
  - `id`
  - `items`
  - `nextId`
- `BranchNode`
  - `id`
  - `conditionRoot`
    - 表示当前 branch 直接测试的 condition fragment root
    - 必须与 `conditionValueId` 的直接 producer subtree 对齐
    - 不要求等于外围 source-level condition 的最外层 root
  - `conditionValueId`
  - `trueTargetId`
  - `falseTargetId`
- `StopNode`
  - `id`
  - `returnValueIdOrNull`

其中 `BranchNode.conditionValueId` 当前不要求已经是 `bool`。frontend CFG -> LIR lowering 阶段再完成 truthiness / condition normalization。

### 2.3 `SequenceNode` 必须承载线性求值步骤

`SequenceNode` 不能只保存 statement AST list。若仍然只保存 statement，短路与条件前置区域就无法表达。

推荐把 `items` 定义成最小线性求值单元列表，例如：

- `StatementItem(statement)`
- `EvalExprItem(expression, resultValueId)`

第一轮不需要为了可扩展性提前制造复杂抽象，但必须保留一个事实：

- `SequenceNode.items` 表达的是“线性执行内容”
- 它不等价于“若干 source statement”

### 2.4 AST-keyed region side table

保留 AST identity keyed side table 的方向，但 side table 的 value 需要从 `CfgNodeBlocks` 迁移为 frontend CFG region。

推荐的最小 region 形状：

- `BlockRegion(entryId)`
- `FrontendIfRegion(conditionEntryId, thenEntryId, elseOrNextClauseEntryId, mergeId)`
- `FrontendElifRegion(conditionEntryId, bodyEntryId, nextClauseOrMergeId)`
- `FrontendWhileRegion(conditionEntryId, bodyEntryId, exitId)`

这一步有两个直接收益：

- `if` / `elif` 首次拥有显式 `conditionEntryId`
- `continue` 可以稳定回跳到 `FrontendWhileRegion.conditionEntryId`

### 2.5 包与 pass 的职责边界

新 frontend CFG 必须与旧 `FrontendLoweringCfgPass` 解耦。

推荐固定边界：

- `dev.superice.gdcc.frontend.lowering.cfg`
  - 保存 frontend CFG graph model
  - 保存 region model
  - 保存 builder / helper / naming / loop-frame 等实现
- `dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass`
  - 只负责读取 `FunctionLoweringContext`
  - 调用 `frontend.lowering.cfg` 下的 builder 构图
  - 把 graph/region 发布回 lowering context
- 现有 `FrontendLoweringCfgPass`
  - 立即标注废弃
  - 迁移完成后删除

这样可以避免新 CFG 的抽象、测试和后续 body lowering 输入继续受制于 legacy block-bundle 设计。

### 2.6 构图 API 必须区分 value context 与 condition context

frontend CFG builder 需要显式拆成两类构图入口：

- `buildValue(expr, currentSequence)`
- `buildCondition(expr, trueTargetId, falseTargetId)`

这是升级的核心。没有这两个分工，就无法正确处理：

- `and` / `or` / `not`
- 非 `bool` condition
- 条件表达式 future lowering

但这里需要额外固定一条约束：

- `and` / `or` 不能只在 `buildCondition(...)` 中展开
- `buildValue(...)` 遇到 `and` / `or` 时，也必须走控制流展开
- 也就是说，`and` / `or` 永远不是普通的线性 `EvalExprItem`

推荐做法：

- `buildCondition(...)`
  - 直接展开短路控制流
- `buildValue(and/or, currentSequence)`
  - 分配结果 value id
  - 构建短路分支区域
  - 在 success/fail 路径的后继 sequence 中写入 `true` / `false`
  - 最后汇合到统一 continuation

这意味着无论 `and` / `or` 出现在：

- `if a and b`
- `while a or b`
- `var x = a and b`
- `return a or b`
- `call(a and b)`

它们都应当生成多个 `SequenceNode` 与 `BranchNode`，而不是退化成单个线性求值节点。

### 2.7 loop stack

frontend CFG builder 需要显式维护 loop stack。

推荐最小 frame：

- `continueTargetId`
- `breakTargetId`

语义固定为：

- `continue` 跳回当前 loop 的 `conditionEntryId`
- `break` 跳到当前 loop 的 `exitId`

---

## 3. 这套模型如何解决当前已知问题

### 3.1 非 `bool` 条件

当前 `BranchNode` 不要求条件值已经是 bool，因此可以先保留 source contract：

- 先在 `SequenceNode` 中求出条件值
- 再在 `BranchNode` 中保留分支点
- 等 frontend CFG -> LIR lowering 时补 bool normalization

这样不会反向把 frontend 收紧成 strict-bool dialect。

### 3.2 `and` / `or` 短路

基于 Godot 当前实现，`and` / `or` 的短路语义对 value context 与 condition context 一视同仁。

因此：

- `buildCondition(...)` 可以直接把：

- `a and b`
- `a or b`
- `not a`

展开成一段条件求值区域
- `buildValue(...)` 遇到 `and` / `or` 时，也必须复用控制流展开，再把结果写回统一的 value id

而不是先 eager lower 两边值再做普通二元运算。

这意味着：

- 即使最外层条件静态类型已经是 `bool`，仍然允许存在多段前置节点
- 即使表达式处于普通 value context，也仍然必须生成多个序列节点和分支节点

### 3.3 `while` 的 `break` / `continue`

当前 block-bundle metadata 无法可靠表达 loop-control edge。引入 `FrontendWhileRegion` 与 loop stack 后：

- `continue` 总是回到条件入口
- `break` 总是离开当前 loop
- nested loop 的目标也能稳定区分

### 3.4 `ConditionalExpression`

`ConditionalExpression` 仍然不应在本轮提前放行，但 frontend CFG graph 已经为其 future lowering 提供必要前提：

- 条件求值区域
- 分支后的值合流入口

后续只需再明确 value-merge / phi-like contract，才能安全解封。

---

## 4. 分步骤实施计划

### 4.1 第一步：冻结 legacy CFG pass 的过渡定位

实施内容：

- 在代码注释与实施文档中明确标注：
  - 当前 `FrontendLoweringCfgPass` 是 legacy metadata-only skeleton
  - 当前 `FunctionLoweringContext.cfgNodeBlocks` 是迁移期 side table，并已显式标注弃用
  - 现有实现尚未完成 frontend CFG 工程
- 在代码中把 `FrontendLoweringCfgPass` 明确标注为 deprecated / for-removal
- `frontend_lowering_cfg_pass_plan.md` 改为归档/迁移说明，不再作为当前实施主计划
- `frontend_lowering_plan.md` 改为把第二阶段定义为 frontend CFG graph 迁移，而不是“最小 CFG 已基本完成”

验收细则：

- 仓库内不存在把当前 `FrontendLoweringCfgPass` 描述成“最终 CFG lowering”的文档冲突
- 新旧文档的职责边界清晰：
  - 新文档是当前主计划
  - 旧文档仅保留 legacy 迁移背景

当前状态（2026-03-29）：

- 已完成。
- `FrontendLoweringCfgPass` 已明确标注为 deprecated 的 legacy metadata-only skeleton。
- `frontend_lowering_cfg_pass_plan.md` 已归档为迁移说明；相关 frontend 文档已对齐到“frontend CFG graph 尚未落地完成”的现状。

### 4.2 第二步：在 `FunctionLoweringContext` 中引入 frontend CFG graph carrier

实施内容：

- 在 `dev.superice.gdcc.frontend.lowering.cfg` 包中新增 frontend CFG graph model、region model 与 builder 支撑类型
- 在 `FunctionLoweringContext` 中新增 frontend CFG graph carrier
- 同时新增 AST-keyed frontend CFG region side table
- `CfgNodeBlocks` 先保留为迁移期兼容物；新代码不得再把它当最终模型继续扩张

验收细则：

- happy path：
  - 每个 compile-ready executable context 都可以持有独立 frontend CFG graph
  - `if` / `elif` / `while` 都能按 AST identity 读回对应 region
- negative path：
  - duplicate publish 继续 fail-fast
  - 不属于该函数的 AST 节点不得误命中
  - graph 与 region side table 不得共享到别的 function context

当前状态（2026-03-29）：

- 已完成。
- `frontend.lowering.cfg` 包已新增独立的 `FrontendCfgGraph` 与 `FrontendCfgRegion` 模型，固定了 frontend-only graph / region 的最小发布合同。
- 结构化 region 已固定为 top-level 类型：
  - `FrontendIfRegion`
  - `FrontendElifRegion`
  - `FrontendWhileRegion`
- `FunctionLoweringContext` 已新增：
  - per-function `frontendCfgGraph` carrier
  - AST identity keyed `frontendCfgRegions` side table
- 当前默认 pipeline 已开始通过 `FrontendLoweringBuildCfgPass` 为 compile-ready executable body 发布 frontend CFG graph；legacy `cfgNodeBlocks` 当前只保留为过渡期 metadata overlay。
- 已有单元测试锚定：
  - duplicate graph / region publish fail-fast
  - region lookup 继续按 AST identity 工作
  - graph / region 不会跨 function context 泄漏
  - default pipeline 会为 compile-ready executable body 发布新 graph，而 legacy CFG pass 继续不会误发布新 graph

### 4.3 第三步：把 `SequenceNode.items` 升级为 frontend 线性 value-op 层

实施内容：

- 迁移前的 `StatementItem(statement)` / `EvalExprItem(expression, resultValueId)` 只能表达“原 AST 片段 + 整体求值占位”，不足以支撑 nested call/member/subscript lowering
- 将 `SequenceNode.items` 升级为 AST-anchored、ANF-like 的最小线性执行项集合
- 第一批至少显式区分：
  - 纯 source anchor / `pass`
  - local declaration commit，消费已算好的 initializer value id
  - assignment/store commit，消费已算好的 RHS value id
  - member load
  - subscript load
  - call
  - future `CastExpression` / `TypeTestExpression` 的预留 op slot
  - leaf/simple expression eval
- 每个执行项都必须显式声明：
  - result value id
  - operand value ids / receiver value id / base value id / argument value ids
  - 对应的 source AST anchor
- `StatementItem` 若继续保留，只能承担非执行语义的 source anchor；不得再承载会触发二次 lower 的真实执行语义
- 当前 local `var` initializer 的 `EvalExprItem + StatementItem(variableDeclaration)` 只是过渡形状，后续必须迁移为显式 declaration-commit item

验收细则：

- happy path：
  - local declaration 与 future assignment 不再依赖 `EvalExprItem + StatementItem` 的隐式配对来表达执行语义
  - nested operand consumer 可以只依赖 item 上的 operand value id 读取前序结果
  - 后续 body lowering consumer 无需再从 declaration / assignment statement AST 回溯已 lower 的子表达式
- negative path：
  - 不允许同一 initializer / RHS 既作为独立 eval item 产出，又在 statement item 中再次作为“待递归 lower”的 subtree 被消费
  - 不允许把 call / member / subscript 的真实执行语义继续藏在 generic statement passthrough 中

当前状态（2026-03-31）：

- 已完成。
- `FrontendCfgGraph.SequenceNode.items` 已冻结为：
  - `SourceAnchorItem`
  - `ValueOpItem`
- 当前已发布的 `ValueOpItem` 子类包括：
  - `OpaqueExprValueItem`
  - `LocalDeclarationItem`
  - `AssignmentItem`
  - `MemberLoadItem`
  - `SubscriptLoadItem`
  - `CallItem`
  - `CastItem`
  - `TypeTestItem`
- 当前线性 builder 已不再依赖 `EvalExprItem + StatementItem` 的隐式配对来表达 local `var` 的执行语义：
  - `pass` 只保留为纯 `SourceAnchorItem`
  - discarded expression / return value 通过 `OpaqueExprValueItem` 保留 generic expression root，同时显式记录已 lower child operand ids
  - local declaration commit 通过 `LocalDeclarationItem` 显式消费 initializer value id
- 对于没有 initializer 的 local `var`，builder 仍会发布显式 declaration-commit item，而不会退回 generic statement passthrough。
- 已有单元测试锚定：
  - `FrontendCfgGraphTest`
    - value-op anchor / operand / result-value contract
    - blank value id negative path
  - `FrontendCfgGraphBuilderTest`
    - declaration-derived initializer ids
    - declaration-without-initializer 仍发布显式 commit item
    - reachable unsupported statement fail-fast
  - `FrontendLoweringBuildCfgPassTest` / `FrontendLoweringPassManagerTest`
    - default pipeline 已对齐到“显式 value-op operand/result id + frontend CFG graph” 的当前 contract

### 4.4 第四步：补齐 `resolvedCalls()` 的 bare `CallExpression` published 面

实施内容：

- 迁移前 `resolvedCalls()` 的主要 published surface 仍是 `AttributeCallStep`；bare `CallExpression` 主要只有 `expressionTypes()` 的返回类型结果，缺少 lowering-ready 的正式 call fact
- 修复语义分析发布面，使 bare `CallExpression` 与 attribute call 一样拥有可供 lowering 直接消费的 `resolvedCall`
- 同步更新 compile-check / inspection / lowering 文档合同，明确：
  - 哪些 AST key 可以出现在 `resolvedCalls()`
  - bare call 不再只作为 display-only / derived 信息存在
- 继续区分 concrete route kind：
  - bare identifier call
  - attribute/member call
  - future callable-value call
- lowering 不得自己重新选择 bare call overload；必须消费语义阶段已发布的 call fact

验收细则：

- happy path：
  - bare `CallExpression` 可稳定读回 `callKind`、`receiverKind`、`argumentTypes`、`returnType` 与 `declarationSite`
  - chain call 与 bare call 共享统一的 published call fact contract
  - inspection tool 不再需要仅为 bare call 生成 `derived` 说明才能展示调用结果
- negative path：
  - 不允许 lowering 仅凭 `expressionTypes()` 重新猜测 bare call route
  - 不允许 bare call 与 attribute call 在 `resolvedCalls()` 中长期维持两套不对称 contract
  - compile gate / inspection / lowering 文档不得继续保留“`resolvedCalls()` 只能以 `AttributeCallStep` 为 key”的过时描述

当前状态（2026-03-30）：

- 已完成。
- `FrontendExpressionSemanticSupport.ExpressionSemanticResult` 已新增 `publishedCallOrNull`，用于把 bare `CallExpression` 的正式 call fact 从 shared expression semantic support 传回 analyzer owner。
- `FrontendExprTypeAnalyzer` 现已在发布 bare call expression type 的同时，把正式 `FrontendResolvedCall` 写入 `analysisData.resolvedCalls()`。
- `resolvedCalls()` 的当前 AST key 合同已对齐为：
  - `AttributeCallStep`
  - bare `CallExpression`
- `FrontendCompileCheckAnalyzer` 已接受 bare `CallExpression` 作为 `resolvedCalls()` key，并能对 call-expression anchor 正常执行 compile-surface blocker 扫描。
- `FrontendAnalysisInspectionTool` 已改为：
  - 优先读取 bare `CallExpression` 的 published call fact
  - 仅在仍无 published fact 的 `CallExpression` 上保留 derived 兼容路径
  - 保持未发布 `AttributeCallStep` 的 display-only `UNPUBLISHED_CALL_FACT`
- 已有单元测试锚定：
  - `FrontendExpressionSemanticSupportTest`
    - bare call resolved / blocked / unsupported 三分流
    - `publishedCallOrNull` 的正反路径
  - `FrontendChainBindingAnalyzerTest`
    - bare call 在完整语义流水线后会稳定出现在 `resolvedCalls()`
    - bare call 作为 chain argument 时仍能保留 published call fact
  - `FrontendAnalysisInspectionToolTest`
    - bare `CallExpression` published 展示不会退化成 derived
    - direct callable invocation 继续只走 derived fallback
  - `FrontendCompileCheckAnalyzerTest`
    - bare `CallExpression` keyed `resolvedCalls()` 不会破坏 compile-check，并可稳定生成 call-anchor blocker

### 4.5 第五步：实现递归 `buildValue(...)` 与显式 operand 展开

实施内容：

- `FrontendCfgGraphBuilder` 不再把整个复杂表达式打包成单个 `EvalExprItem`
- `buildValue(...)` 必须递归构图，并返回“当前 continuation + result value id”一类的 builder 内部状态
- 优先覆盖：
  - bare/global call
  - attribute/member property access
  - attribute/member call
  - plain subscript
  - attribute-subscript
  - assignment expression
  - future `CastExpression` / `TypeTestExpression` 的前置接线位
- `AttributeExpression` 按 `base + steps` 逐步展开，而不是留给 body lowering 再次做 chain reduction
- 函数参数、subscript 下标、assignment RHS 都必须先 lower child，再由父操作 item 显式消费 child value ids
- declaration / assignment 必须显式区分：
  - 求值阶段
  - commit/store 阶段

验收细则：

- happy path：
  - `foo(bar(), baz(qux()))`
  - `arr[idx(a())]`
  - `obj.a().b[c()].d(e())`
  - `x = foo(arr[idx()])`
    都能在 frontend CFG 中展开成显式 value-op 序列，而不是遗留成需要未来二次 AST 递归的黑盒表达式项
  - builder 可以稳定表达嵌套参数、嵌套下标、链式 member/call/subscript 的 evaluation order
- negative path：
  - 不允许 child 表达式已经产生 value id，但父 item 仍缺少显式 operand 引用
  - 不允许 declaration / assignment 继续依赖 raw statement AST 才能恢复真实执行顺序
  - 不得因为引入 value-op 层而破坏 AST-keyed region side table 的 contract

当前状态（2026-03-30）：

- 已完成。
- `FrontendCfgGraphBuilder` 已升级为递归线性 value builder，并直接消费 compile-ready `analysisData`：
  - bare/global `CallExpression`
  - `AttributeExpression` 的 property / call / attribute-subscript step
  - plain `SubscriptExpression`
  - statement-position `AssignmentExpression`
  - `CastExpression` / `TypeTestExpression` 的预留 item 接线位
- generic `IdentifierExpression` / `LiteralExpression` / `SelfExpression` / unary / eager binary 当前继续落为 `OpaqueExprValueItem`，但已不再是“整棵子树黑盒”：
  - item 现在显式保存 `operandValueIds`
  - nested special op child 会先发布 value id，再由 generic root item 显式消费
- `and` / `or` 已从普通 eager `BinaryExpression` 路由中显式移出：
  - compile gate 现在会对 compile surface 上的 short-circuit binary root 直接报 `sema.compile_check`
  - builder 内部已保留 dedicated short-circuit 入口，并在当前阶段 fail-fast 标注“未实现”，避免回退成单个线性 binary item
- 当前 item contract 已同步增强为：
  - `OpaqueExprValueItem`：显式 child operand ids
  - `CallItem`：`callAnchor + callableName + receiver/argument value ids`
  - `MemberLoadItem`：`memberAnchor + memberName + base/result value ids`
  - `SubscriptLoadItem`：`subscriptAnchor + optional memberName + base/argument/result value ids`
  - `AssignmentItem`：`targetOperandValueIds + rhsValueId + optional resultValueIdOrNull`
- assignment target prefix 已单独走 target-specific value path：
  - 因为左值前缀并不总是出现在 ordinary `expressionTypes()` published 面中
  - builder 现在会在 assignment target lowering 中显式求值 receiver/index 前缀，而不会错误要求所有左值前缀都已有 ordinary expression fact
- `FrontendLoweringBuildCfgPass` 已改为把 per-function `analysisData` 传入 builder，使 build pass 与 compile-ready published fact contract 对齐。
- 已有单元测试锚定：
  - `FrontendCompileCheckAnalyzerTest`
    - short-circuit binary 在 compile-only 路径被显式封口，但不污染 shared analyze
  - `FrontendCfgGraphTest`
    - expanded item anchor / operand / result contract
  - `FrontendCfgGraphBuilderTest`
    - nested bare-call + attribute-subscript + attribute-call + property-read expansion
    - explicit declaration commit / assignment commit shape
    - declaration-without-initializer explicit commit
    - missing published call fact fail-fast
    - short-circuit special path 在 builder 内部 fail-fast，而不是 eager lower child
    - reachable unsupported statement fail-fast
  - `FrontendLoweringBuildCfgPassTest`
    - default pipeline 发布 phase-5 递归 value-op graph 形状并保持 shell-only LIR
  - `FrontendLoweringAnalysisPassTest`
    - compile-ready lowering pipeline 会在 analysis pass 因 short-circuit compile blocker 提前停止
  - `FrontendLoweringPassManagerTest`
    - manager 默认 pipeline 已对齐到显式 operand/result id 的 frontend CFG graph contract

### 4.6 第六步：在显式 value-op 层上完成结构控制流与短路

实施内容：

- 为 `if` / `elif` / `while` 发布稳定 region：
  - `conditionEntryId`
  - branch body entry
  - merge / exit
- 正式拆分：
  - `buildValue(...)`
  - `buildCondition(...)`
- `buildCondition(...)` 对普通 condition 先产出必要的前置 value-op / sequence，再连接 `BranchNode`
- `BranchNode` 保留 source condition value，不提前强制 bool 化；truthiness normalization 延后到 frontend CFG -> LIR lowering
- 引入 loop stack，正式支持：
  - `BreakStatement`
  - `ContinueStatement`
- `ConditionalExpression` 在 value-merge contract 冻结前继续 compile-block

验收细则：

- happy path：
  - `if`
  - `if / else`
  - `if / elif / else`
  - `while`
  - nested `if` / `while`
    都有稳定 graph shape 与 region 映射
  - `if payload:` / `while payload:` 存在显式条件前置区域
  - `continue` 稳定回到当前 loop 的 condition entry；`break` 稳定跳到当前 loop exit
- negative path：
  - empty branch body 不得漏建 merge / exit
  - fully-terminating branch chain 后不得错误挂接 lexical remainder
  - 没有 loop frame 时遇到 `break` / `continue` 必须 fail-fast
  - 不得因为 frontend CFG 已能表达分支就提前解封 `ConditionalExpression`

当前状态（2026-03-31）：

- 已完成。
- `FrontendCfgGraphBuilder` 已从 phase-5 的递归 value builder 升级为 executable-body CFG builder：
  - `buildExecutableBody(...)` 统一构建 straight-line 与 structured executable body
  - `buildCondition(...)` 负责显式 condition-evaluation-region，并把前置 value-op / `SequenceNode` 接到后续 `BranchNode`
- `FrontendLoweringBuildCfgPass` 现已对 compile-ready executable body 一律发布 frontend CFG graph，而不再把 structured body 留给 legacy path 单独兜底。
- 当前 structured CFG contract 已落稳：
  - `if` / `elif` / `while` 都会按 AST identity 发布 region
  - empty branch body 会把 body entry alias 到 merge / exit，而不会漏建目标 continuation
  - fully-terminating `if` chain 不再把 lexical remainder 错挂到可达路径
  - loop stack 已固定 `continue -> conditionEntryId`、`break -> exitId`
- `break` / `continue` 在没有 active loop frame 时继续按 invariant fail-fast，而不是 silent recovery。
- `ConditionalExpression` 继续 compile-block；第六步不会因为已能表达 branch region 就提前解封它。
- 第八步边界继续保持不变：
  - `and` / `or` 仍由 compile-only gate 封口
  - builder 内部仍保留 dedicated short-circuit entry，并在当前阶段 fail-fast 标注“未实现”
- 已有单元测试锚定：
  - `FrontendCfgGraphBuilderTest`
    - `if / elif / else` region / merge / lexical remainder contract
    - `while` condition-entry、`break` / `continue` loop target
    - break/continue-without-loop-frame negative path
    - short-circuit binary 继续 fail-fast，而不会 eager lower child
  - `FrontendLoweringBuildCfgPassTest`
    - build pass 会同时为 linear 与 structured executable body 发布 frontend CFG graph，并保持 shell-only LIR
  - `FrontendLoweringPassManagerTest`
    - 默认 pipeline 同时保留新 frontend CFG graph 与 legacy `cfgNodeBlocks` metadata overlay

### 4.7 第七步：提取共享表达式构图内核并冻结 value / condition / merge 合同

实施内容：

- 在 `FrontendCfgGraphBuilder` 内部提取共享表达式构图内核；保持它是 builder 私有实现，不新增独立 public pass、public API 或“只有一个实现”的额外抽象层。
- `conditionEntryId` 的合同必须升级为“condition subgraph 的稳定入口节点”，而不是“唯一前置 `SequenceNode`”；后续 consumer 与测试都不得再假设 `conditionEntryId -> SequenceNode.nextId() -> BranchNode`。
- `buildCondition(...)` 必须改为 entry-oriented contract：
  - 返回“条件子图入口”一类的内部状态
  - 不再把“1 个 `SequenceNode` 紧跟 1 个 `BranchNode`”编码进 `ConditionBuild`
- `buildValue(...)` 必须从“只向当前 `ArrayList<SequenceItem>` 追加 item 并返回 `resultValueId`”升级为 continuation-aware contract；最小 builder 内部状态至少需要能同时承载：
  - 当前可写 continuation / current sequence
  - 表达式最终 result value id
- 建议的最小私有内部形状：
  - `BuildCursor` / 等价私有状态：持有当前可写 `OpenSequence`
  - `ValueBuild` / 等价私有状态：返回 continuation + `resultValueId`
  - `ConditionBuild` / 等价私有状态：返回 condition subgraph `entryId`
  - 这些都应保持为 `FrontendCfgGraphBuilder` 私有细节，而不是扩成新的跨文件公共模型
- value context 与 condition context 必须共用同一套递归表达式构图规则，而不是维护两套会继续漂移的 special-case 树：
  - ordinary eager expression 继续走线性 value-op
  - `not` 复用条件翻转
  - `and` / `or` 复用短路控制流展开
  - future `ConditionalExpression` 复用同一套 condition-entry + branch-result-merge 基础设施
- 引入显式 branch-result write / merge item（命名可按实现最终定稿），用于：
  - value-context `and` / `or` 在短路路径与继续求值路径上把 `true` / `false` 写入共享结果 value id
  - future `ConditionalExpression` 在 true-arm / false-arm 上把 arm result 写入同一个共享结果 value id
- `call(...)`、member、subscript、assignment 等父级 consumer 必须继续保持单向 contract：
  - child 先发布 value id
  - parent 只消费 child value id
  - 不允许 parent item 直接回看 child AST 以恢复控制流或值合流语义
- 现有 structured CFG 测试必须同步改成“锚定 region / reachability / published value contract”，而不是锚定固定两节点形状：
  - 允许 condition region 内存在多个 `SequenceNode` / `BranchNode`
  - 继续要求 `conditionEntryId`、`mergeId`、`exitId`、`then/bodyEntryId` 稳定可达
- 在这一步完成前继续维持 compile gate：
  - `and` / `or` 继续 compile-block
  - `ConditionalExpression` 继续 compile-block
  - 共享表达式构图内核先完成重构，不得通过 eager fallback 提前偷跑新语义

验收细则：

- happy path：
  - `buildCondition(...)` 与 `buildValue(...)` 共享同一套表达式递归入口，不再维护两套独立 special-case 逻辑
  - `conditionEntryId` 可以稳定代表多节点 condition subgraph 的入口，而不是固定两节点模板
  - value builder 能在不丢失 continuation 的前提下，为 future `and` / `or` / `ConditionalExpression` 预留统一结果 slot 与 merge continuation
  - nested value context 中，父 item 始终只消费 child result value id：
    - `call(a and b)`
    - `arr[a or b]`
    - `obj.m(flag ? x : y)`
    - nested member / call / subscript / assignment 参数
    都不会退回“直接抓 child AST”的旧路由
- negative path：
  - 不允许测试继续把 `conditionEntryId` 固定断言为 `SequenceNode`，且 `nextId()` 必然是 `BranchNode`
  - 不允许继续把 `ConditionBuild`、region 注释或 build helper 编码成“condition region = 1 个 sequence + 1 个 branch”
  - 不允许 `buildValue(...)` 在需要控制流的表达式上继续依赖“只追加到当前 `items` 容器”的线性假设
  - 不允许 future `ConditionalExpression` 因共享内核缺失而再次引入第三套表达式构图路径

当前状态（2026-04-02）：

- 已完成。
- 本步产出已经落地到 `FrontendCfgGraphBuilder` 私有内部合同：
  - 引入 `BuildCursor(entryId, currentSequence)`、`ValueBuild`、`ConditionBuild` 与 `ValueListBuild`
  - `buildValue(...)` 不再只返回 `resultValueId`，而是显式返回 continuation-aware 内部状态
  - `buildCondition(...)` 不再编码固定“1 个 `SequenceNode` + 1 个 `BranchNode`”，只发布 condition subgraph `entryId`
- 共享表达式递归入口已经统一：
  - value-context 与 condition-context 共用同一套递归 builder 骨架
  - condition-context `not` 已改为 target flip，而不是先产出 eager unary bool item 再 branch
  - `and` / `or` 仍统一走 dedicated short-circuit entry 并 fail-fast，避免回退为 eager child lowering
  - `ConditionalExpression` 仍保留 compile gate / builder fail-fast 边界，未提前解封
- 为 future branch-result merge 预留了显式 `MergeValueItem`，但在第八步 short-circuit 与后续 `ConditionalExpression` 真正接通前不会提前发布伪语义 item。
- 文档与注释已同步升级：
  - `FrontendCfgGraph.BranchNode`、`FrontendIfRegion`、`FrontendElifRegion`、`FrontendWhileRegion` 已改为“condition subgraph stable entry / fragment root”表述
  - `frontend_rules.md` 与 `frontend_lowering_plan.md` 已同步 condition-entry 合同与 shared-expression-core 当前事实
- `BranchNode.conditionRoot` 的严格合同已冻结为“当前 branch 直接测试的 condition fragment root”，具体规则如下：
  - 它必须与 `conditionValueId` 的直接 producer subtree 对齐，而不是仅仅记录外围 `if` / `while` 的原始 condition root
  - `conditionEntryId` 继续负责整个 condition subgraph 入口；`conditionRoot` 只负责当前这个 branch 的 immediate tested fragment
  - `not x` 若通过 target flip 实现，则 branch 必须保留 `x` 作为 `conditionRoot`，而不是 `not x`
  - future `a and b` / `a or b` / `a or (b and c)` 中，多个 `BranchNode` 必须分别持有 `a`、`b`、`c` 这类被实际测试的 fragment，而不是都重复外层复合表达式 root
- 例子：
  - `if flag:` 的 branch 保留 `flag`
  - `if not helper(seed):` 的 branch 保留 `helper(seed)`，同时通过 true/false target 交换表达逻辑翻转
  - future `if a and b:` 的第一个 branch 保留 `a`，第二个 branch 保留 `b`
  - future `if a or (b and c):` 的分支序列应分别保留 `a`、`b`、`c`
- 现有 structured CFG 测试已改成锚定 reachable branch / region contract，而不是锚定 `conditionEntryId -> SequenceNode.nextId() -> BranchNode` 固定模板。
- 已新增并运行的回归重点：
  - happy path：condition-context `not` 使用 target flip，且不会再先发布 unary `OpaqueExprValueItem`
  - negative path：short-circuit binary 无论出现在 value context 还是 condition context，都继续在 eager child lowering 之前 fail-fast
  - 已跑通过：
    - `FrontendCfgGraphBuilderTest`
    - `FrontendLoweringBuildCfgPassTest`

### 4.8 第八步：显式实现 `and` / `or` 的短路构图步骤

实施内容：

- 将 `and` / `or` 从“普通二元 value-op”中彻底移出，固定为专门的控制流构图路径
- `buildCondition(...)` 遇到：
  - `a and b`
  - `a or b`
  - `not a`
  必须直接展开为多段 sequence + branch 的短路区域，不得退化成单个线性 item
- `buildValue(...)` 遇到 `and` / `or` 时，必须显式：
  - 分配最终结果 value id
  - 生成左操作数求值节点
  - 生成短路分支节点
  - 在短路路径与继续求右操作数路径上分别写入 `true` / `false`
  - 汇合到统一 continuation，并把结果 value id 交还父级调用者
- 这一步必须覆盖所有 value context 消费点，而不是只覆盖顶层赋值/返回：
  - `var x = a and b`
  - `return a or b`
  - `call(a and b)`
  - `arr[a or b]`
  - `obj.m(not a or b)`
- 若 `and` / `or` 嵌套在 member/call/subscript/assignment 参数中，仍必须遵守“child 先产出 value id，parent 只消费 value id”的单向 contract
- 结果写入建议继续用显式 value-op item 建模；不得回退为“靠 body lowering 看到 AST 后再补写 true/false”的隐式协议

验收细则：

- happy path：
  - `if a and b`
  - `while a or b`
  - `var x = a and b`
  - `return a or b`
  - `call(a and b)`
  - `arr[a or b]`
    都会稳定生成多 `SequenceNode` / `BranchNode` 的短路图形，而不是 eager 求值左右操作数
  - `buildValue(...)` 返回给父节点的始终是统一结果 value id，而不是“左右操作数 AST 仍待后处理”的半成品
  - `not` 在 condition context 下也复用分支翻转/重接线逻辑，而不是单独走 eager bool 运算
- negative path：
  - 不允许 `and` / `or` 继续落成单个普通 binary value-op item
  - 不允许 value context 下只因为静态类型是 `bool` 就跳过短路区域构建
  - 不允许 parent item 直接引用 `and` / `or` 的左右 child AST，而绕过中间结果 value id
  - 不允许 body lowering 成为第一个理解 `and` / `or` 短路语义的阶段

当前状态（2026-03-31）：

- 尚未实现真正的短路 CFG 构图。
- 但当前仓库已经显式冻结了过渡边界：
  - `FrontendCompileCheckAnalyzer` 会把 compile surface 上的 `and` / `or` 直接作为 compile-only blocker 报错
  - `FrontendCfgGraphBuilder` 不再把它们归入普通 eager `BinaryExpression` 路由，而是走 dedicated short-circuit entry 并 fail-fast 标注未实现
- 这条过渡边界的目的不是“长期禁止 `and` / `or`”，而是在第八步真正落地前，防止线性 value-op 层继续错误地把 short-circuit 语义塌缩成单个 eager binary item。

### 4.9 第九步：实现 `FrontendLoweringBodyInsnPass`，只消费 frontend CFG 与 published facts

实施内容：

- 引入 `FrontendLoweringBodyInsnPass`
- body lowering 只允许消费：
  - `frontendCfgGraph`
  - `frontendCfgRegions`
  - `analysisData` 中已发布的 `symbolBindings()` / `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()`
- body lowering 不得重新做：
  - chain reduction
  - bare call overload 选择
  - AST 级“哪些 child 先求值”的第二套推导
- 先打通 MVP surface：
  - identifier / literal / unary / binary
  - bare/global call
  - member/property access
  - member call
  - subscript
  - assignment
- 在这个阶段之前，`LirModule` 继续保持 shell-only；在这个阶段之后才允许把 real block / instruction materialize 到 `LirFunctionDef`

验收细则：

- happy path：
  - body lowering 可以直接按 sequence item 的 operand/result id 生成 instruction，而不需要重走已 lower 的 child AST
  - bare call 与 attribute call 都直接消费 published `resolvedCall`
  - 输出 block / terminator / instruction 与 frontend CFG control-flow shape 一致
- negative path：
  - 不允许 `FrontendLoweringBodyInsnPass` 遇到 declaration / assignment / call item 时再回到原 statement/expression AST 做第二套递归 lower
  - 不允许 lowering 自己补做语义路由推导来绕过缺失的 published fact
  - 在 body lowering 未闭环前，不得偷偷向 `LirFunctionDef` 写半成品 block

### 4.10 第十步：移除 legacy block-bundle 与过渡 pass

实施内容：

- 当 frontend CFG graph、显式 value-op 层与 body lowering 已全部接通后，删除：
  - `CfgNodeBlocks`
  - `FrontendLoweringCfgPass`
  - 以 `LirBasicBlock` 作为 metadata skeleton 的 legacy side table
- `FrontendLoweringBuildCfgPass` 成为唯一 CFG 构图入口
- 若迁移期确实需要兼容层，也只能是短期桥接，不得继续扩展其职责

验收细则：

- happy path：
  - `FrontendLoweringBuildCfgPassTest`、`FrontendLoweringPassManagerTest` 与 future `FrontendLoweringBodyInsnPassTest` 全部锚定新 graph + value-op contract
- negative path：
  - 不允许 graph 与 legacy side table 长期双写并各自漂移
  - 仓库内不再保留 `FrontendLoweringCfgPass`

---

## 5. 推荐测试矩阵

建议至少覆盖：

- `FrontendLoweringBuildCfgPassTest`
  - 直线型 executable body 的显式 value-op item 形状
  - local declaration 不再使用“initializer eval + statement passthrough”隐式配对表达执行语义
  - nested call / member / subscript 的 value-op 展开顺序
  - `if`
  - `if / else`
  - `if / elif / else`
  - `while`
  - nested `if` / `while`
  - 非 `bool` condition 的 condition-evaluation-region
  - condition context 下 `and` / `or` / `not` 的短路区域
  - value context 下 `and` / `or` 的短路区域
  - `break` / `continue`
  - fully-terminating branch 后的 lexical remainder
  - compile-blocked module 不进入 cfg pass
- `FrontendChainBindingAnalyzerTest`
  - bare `CallExpression` 发布正式 `resolvedCall`
  - bare call 与 attribute call 的 `resolvedCalls()` contract 对齐
- `FrontendAnalysisInspectionToolTest`
  - bare call 不再仅依赖 display-only `derived` 结果
  - `resolvedCalls()` 新 published 面与展示逻辑保持一致
- `FrontendCompileCheckAnalyzerTest`
  - `resolvedCalls()` 的 AST key contract 与 compile gate 描述保持一致
  - `ConditionalExpression` 继续 compile-block
- `FrontendLoweringBodyInsnPassTest`
  - body lowering 只消费 graph + published facts
  - declaration / assignment / call 不会触发二次 AST 递归 lower
  - operand/result value id 与生成 instruction 的顺序严格一致
- `FrontendLoweringPassManagerTest`
  - 默认 pipeline 发布 frontend CFG graph
  - body lowering 闭环前 `LirModule` 继续保持 shell-only
  - property initializer context 继续不进入 executable-body graph materialization

测试重点应优先覆盖：

- AST identity keyed region lookup
- deterministic node id / graph shape
- 明确的 operand/result value id contract
- 不重复 lower 同一 expression subtree
- nested argument / nested index 的 evaluation order
- bare call published call fact 的完整性
- condition context 与 value context 的分工
- short-circuit 只展开必要路径
- loop stack target 的正确性
- shell-only LIR 合同在 body lowering 闭环前未被破坏

---

## 6. 主要风险与缓解

### 6.1 迁移期双模型漂移

风险：

- legacy `CfgNodeBlocks` 与新 graph/value-op 层并存时，容易出现两套结构不同步

缓解：

- 明确 graph/region + value-op 是新主模型
- legacy block-bundle 只允许短期兼容，不得继续加需求

### 6.2 `SequenceItem` 语义仍然过粗导致重复处理

风险：

- 若 declaration / assignment / call 的真实执行语义仍由 raw statement/expression AST 承担，future body lowering 很容易在 `EvalExprItem` 之外再次递归同一子树

缓解：

- 尽早把执行语义迁移到显式 value-op item
- 保持“child 先产出 value id，parent item 只消费 value id”的单向 contract

### 6.3 bare `CallExpression` 的 call fact 发布面继续缺口化

风险：

- 若 bare call 继续只有 `expressionTypes()` 而没有正式 `resolvedCall`，lowering 就会被迫自行猜测 route / overload 结果

缓解：

- 把 bare `CallExpression` 纳入正式 `resolvedCalls()` published 面
- 同步更新 compile gate、inspection tool 与 lowering 文档，避免 contract 漂移

### 6.4 用 ASTWalker 直接实现控制流构图

风险：

- control-flow builder 需要显式携带：
  - lexical continuation
  - true/false target
  - loop stack
  - 当前 sequence/value-op 写入位置
- 通用 ASTWalker 更适合遍历，不适合承担这套有向构图协议

缓解：

- 保持显式状态机构图器
- 允许局部复用 AST 访问帮助函数，但不要把核心构图逻辑改写成 generic walker callback

### 6.5 过早解除 `ConditionalExpression`

风险：

- 若只看 graph 有了分支节点就解封，很容易遗漏 value merge / ownership / evaluation-order 语义

缓解：

- 继续维持 compile gate
- 直到 condition region、value-op contract 与 value merge 合同一起冻结后再解封

### 6.6 property initializer / parameter default 过早接入

风险：

- 它们当前仍不是完整 executable body
- 若强行复用 executable graph builder，容易伪造错误的 return / ordering 语义

缓解：

- 第一轮只处理 `EXECUTABLE_BODY`
- 等 expression/body lowering 与 synthetic init function contract 一起闭合后再复用

---

## 7. 文档同步要求

只要 frontend CFG graph 的合同发生变化，至少要同步更新：

- `frontend_rules.md`
- `frontend_compile_check_analyzer_implementation.md`
- `frontend_chain_binding_expr_type_implementation.md`
- `frontend_analysis_inspection_tool_implementation.md`
- `diagnostic_manager.md`
- `frontend_lowering_plan.md`
- `frontend_lowering_func_pre_pass_implementation.md`
- 本文档

其中有一条必须长期保持一致：

- 新 frontend CFG 实现在 `frontend.lowering.cfg` 包中独立维护
- `FrontendLoweringBuildCfgPass` 是唯一的构图 pass
- 当前 `FrontendLoweringCfgPass` 是已废弃、待删除的过渡实现
