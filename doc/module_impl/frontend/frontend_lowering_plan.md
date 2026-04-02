# Frontend Lowering 后续计划

> 本文档记录 frontend lowering 在当前 pre-pass 实现之后的后续工程路线图。当前已落地事实以 `frontend_lowering_skeleton_pre_pass_implementation.md` 与 `frontend_lowering_func_pre_pass_implementation.md` 为准；本文档只保留尚未实现或尚未冻结的计划内容。

## 文档状态

- 状态：计划维护中（function pre-pass scaffold、frontend CFG graph shared-expression core 与显式 short-circuit CFG 已落地；body lowering 仍待迁移）
- 更新时间：2026-04-02
- 当前事实源：
  - `frontend_lowering_skeleton_pre_pass_implementation.md`
  - `frontend_lowering_func_pre_pass_implementation.md`
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `runtime_name_mapping_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `doc/gdcc_low_ir.md`

---

## 1. 当前起点

当前仓库已经具备的 lowering 起点：

- public lowering 输入固定为 `FrontendModule`
- lowering 内部统一走 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
- default pipeline 已稳定产出 `LirModule(skeleton/shell-only)`、function lowering context scaffold，以及带显式 operand/result value-op、structured region 与 loop-control edge 的 executable body frontend CFG graph
- compile-only gate 仍负责拦截当前未打通 lowering/backend 的 surface；`and` / `or` 已不再停留在 compile-only blocker，而是作为 compile-ready surface 进入 dedicated short-circuit CFG lowering
- `FrontendCfgGraphBuilder` 内部已冻结共享表达式构图内核：`buildValue(...)` 现在返回 continuation-aware 内部状态，`buildCondition(...)` 现在只承诺发布 condition subgraph 的稳定入口，而不再编码固定 `SequenceNode -> BranchNode` 形状
- `FrontendCfgGraph.BranchNode.conditionRoot` 现在固定表示“当前 branch 直接测试的 condition fragment root”；它必须对齐 `conditionValueId` 的直接 producer subtree，而不是笼统复用外围 source-level condition root，也不能再把 `conditionValueId` 当成可全图反推唯一 producer item 的句柄
- short-circuit lowering 现在要求每个 `BranchNode.conditionValueId` 都保持为该 fragment 的 branch-local 独立 value id；value-context `and` / `or` 的 outward-facing merged result value id 已单独建模，不能反向复用为 branch condition id
- frontend CFG value id 现在额外冻结了 merge-slot 合同：一个 value id 若出现多个 producer，则所有 producer 都必须是 `MergeValueItem`；future producer collection 与 body lowering 不得把这类 merged result 当成唯一 SSA expression definition
- callable-local slot type 现在也已进入 published fact 面：`FrontendVarTypePostAnalyzer` 会把 parameter / supported local `var` 的最终 slot type 写入 `FrontendAnalysisData.slotTypes()`，供 future body lowering 直接消费
- condition-context `not` 已切到 target-flip 路径；`and` / `or` 已正式接通 shared-expression-core + branch-result merge 路径，`ConditionalExpression` 仍保留 compile gate + builder fail-fast 边界

后续工程应在这条稳定链路之上继续推进，不要回退到“先手工做一份分析结果再喂 lowering”的分叉入口。

---

## 2. 后续实施顺序

建议按以下顺序推进，并保持每一步都是可运行、可回归、可单独提交的状态：

1. function lowering context / preparation
2. frontend CFG graph / condition-evaluation-region
3. frontend CFG -> LIR body lowering
4. compile gate blocker 按顺序解除
5. post-MVP backlog 单独立项

---

## 3. 第一步：建立 per-function lowering scaffold

本步骤当前已落地的事实与后续仍有效的架构约束由独立文档维护：

- `frontend_lowering_func_pre_pass_implementation.md`

目标：

- 在不生成真实 CFG 的前提下，为所有未来会形成 `LirFunctionDef` 的 lowering 单元建立统一入口
- 引入统一的 `FunctionLoweringContext`，但保持结构简单，不制造多余抽象
- 明确 executable body、property initializer function 与 future parameter default initializer function 共用同一套函数级 scaffold

建议实施内容：

- 增加 `FrontendLoweringFunctionPreparationPass`
- 建立统一的 function lowering context/worklist，最少覆盖：
  - `EXECUTABLE_BODY`
  - `PROPERTY_INIT`
  - `PARAMETER_DEFAULT_INIT`
- 为每个 compile-ready lowering 单元收集：
  - owning class
  - source AST owner / lowering root
  - target `LirFunctionDef`
  - 已发布的 scope / binding / member / call / expression-type facts
- executable callable 复用 skeleton phase 已发布的 `LirFunctionDef`
- supported property initializer 应补 hidden synthetic init function shell，并回写 `LirPropertyDef.initFunc`
- parameter default 当前仍不收集真实 context，但架构必须保留 future `default_value_func` 接线位置

验收细则：

- happy path：
  - 所有 top-level / inner class executable callable 都可进入 `EXECUTABLE_BODY` context
  - supported property initializer 可进入 `PROPERTY_INIT` context，并稳定映射到 hidden synthetic init function shell
  - `_init`、普通 instance function、static function 与 property init function 都能被区分
  - 当前 `FunctionLoweringContext` 形状无需修改即可容纳 future `PARAMETER_DEFAULT_INIT`
- negative path：
  - compile gate 已挡下的 subtree 不进入 function lowering context 集合
  - 对缺失的 published fact 或 metadata 采用 invariant fail-fast，而不是 silent skip
  - preparation 阶段不得伪造 basic block / `entryBlockId`

---

## 4. 第二步：迁移到 frontend CFG graph

本步骤的详细实施计划由独立文档维护：

- `frontend_lowering_cfg_graph_plan.md`
- `frontend_lowering_cfg_pass_plan.md`（legacy 迁移说明）

目标：

- 在真正写 LIR 前，先建立 frontend-only CFG graph
- 让 CFG 层能够表达 condition-evaluation-region，而不只是 metadata-only block skeleton
- 新 CFG graph 必须在独立的 `frontend.lowering.cfg` 包中实现，并只由 `FrontendLoweringBuildCfgPass` 构建
- 当前实际落地范围先覆盖 executable callable；property initializer function 在对应 expression/body lowering 接通后复用同一套入口

第一批建议只支持：

- 空函数
- `pass`
- 直线型 `return`
- `if / elif / else`
- `while`

当前明确不纳入：

- `for`
- `match`
- `lambda`
- `assert`
- `ConditionalExpression`

建议实施内容：

- 在 `frontend.lowering.cfg` 包中独立实现 frontend CFG graph model 与 builder
- 引入 `FrontendLoweringBuildCfgPass` 作为唯一的 CFG 构图入口
- 现有 `FrontendLoweringCfgPass` 立即标注废弃，并在迁移完成后删除
- 在 `FunctionLoweringContext` 上增加 frontend CFG graph 与 AST-keyed region side table
- 明确区分：
  - `buildValue(...)`
  - `buildCondition(...)`
- `and` / `or` 无论处于 value context 还是 condition context，都必须按短路控制流展开；不得把 value-context `and/or` 回退成单个线性二元表达式节点
- `BranchNode` 当前允许持有非 `bool` condition value，truthiness / condition normalization 延后到 frontend CFG -> LIR lowering
- 在 frontend CFG graph 稳定前，`LirModule` 继续保持 shell-only

验收细则：

- happy path：
  - executable callable 拥有稳定、可预测的 frontend CFG graph shape
  - `if / elif / else` 与 `while` 都拥有显式 condition-entry region
  - 非 `bool` condition 与 `and` / `or` 的前置求值区域有回归测试锚定
- negative path：
  - 未实现结构不被 silent drop
  - compile gate 尚未解除时，对应脚本仍在 analysis pass 被挡下
  - 不出现“frontend CFG 尚未闭环却提前把半成品 block 写进 LIR”的中间态

---

## 5. 第三步：实现 frontend CFG -> LIR body lowering

目标：

- 在 frontend CFG graph 已冻结的前提下，打通当前 frontend 已稳定发布事实的 MVP surface
- 让 executable callable body 与 supported property initializer function 都通过同一套函数级 lowering 管线进入 instruction 生成

建议优先顺序：

1. identifier / literal / unary / binary
2. bare/global call
3. member call / property access
4. assignment
5. supported property initializer function body

建议实施内容：

- 引入 `FrontendLoweringBodyInsnPass`
- 让 body lowering 以 `frontend.lowering.cfg` 中发布的 frontend CFG graph 为直接输入，而不是重新从 AST + `CfgNodeBlocks` 推断控制流
- 严格消费已发布的：
  - `symbolBindings`
  - `resolvedMembers`
  - `resolvedCalls`
  - `expressionTypes`
  - `slotTypes`
- 不在 lowering 中重新推导语义
- 对同一个 result value id 的 producer 查询必须支持多出处；若 value id 由多个 `MergeValueItem` 共同写入，body lowering 必须把它视为“merge slot / 变量槽写入”，而不是单个 SSA 表达式节点
- property initializer 的 expression lowering 应通过其 `PROPERTY_INIT` context 驱动，并最终写入对应 `init_func` 的函数体，而不是作为 callable body lowering 之外的常驻特例

验收细则：

- happy path：
  - lowering 只依赖 frontend 已发布 facts，不再做第二套 binder
  - 输出 instruction 的类型与 owner 路径和 frontend 已发布事实一致
  - merged result value id 会按 merge slot 写入/读取语义进入 lowering，而不是被错误压平成单个 SSA expression producer
  - supported property initializer 通过 `init_func` 产生产物，而不是内联伪装进别的函数体
- negative path：
  - 对缺失或冲突的 published fact 采用 invariant fail-fast
  - 不允许 lowering 通过“value id 只能有一个 producer”的假设绕过 multi-merge producer contract
  - 不因为 backend 已有某条指令就跳过 frontend 事实校验

---

## 6. 第四步：按实现进度解除 compile gate blocker

只有在以下三个条件同时满足后，才允许从 compile-only gate 中移除某一类 blocker：

1. lowering 已稳定产生产物
2. backend 已能消费该产物
3. 文档与正反测试都已同步更新

建议解除顺序：

1. `ConditionalExpression`
2. `assert`
3. `ArrayExpression` / `DictionaryExpression`
4. `CastExpression` / `TypeTestExpression`
5. `GetNodeExpression` / `PreloadExpression`
6. 脚本类 `static var`

说明：

- `ConditionalExpression` 依赖 frontend CFG graph / condition-evaluation-region 合同先稳定
- `assert` 依赖 lowering/backend 的 statement 语义
- container / cast / runtime integration / static field 相关 blocker 均应在对应 lowering/backend 设计闭环后再解除

---

## 7. Post-MVP Backlog

以下内容即使 body lowering 初步落地，也继续保持 post-MVP，不应混入已有 lowering pass 的局部放行：

- `for`
- `match`
- `lambda`
- 参数默认值语义本身
- block-local `const`
- signal coroutine use-site（`await` / `.emit(...)`）
- property initializer 中依赖实例状态的完整初始化时序语义

其中参数默认值需要额外明确：

- 当前 deferred 的是 parameter default 的语义支持面，不是其架构位置
- 后续实现时应把每个 supported default expression lowering 成 hidden synthetic function，并写入 `LirParameterDef.defaultValueFunc`
- 不应把 parameter default 设计成仅靠调用点 inline 补全的唯一路径

这些内容需要单独立项，并附带专门的文档、测试与回归边界。

---

## 8. 测试规则

后续 lowering 路线图实施时，应继续遵守以下规则：

- 每个新 pass 至少覆盖 happy path 与 negative path
- negative path 至少锚定：
  - diagnostics category
  - pipeline 是否 stop
  - 是否禁止继续产生产物
- 一旦开始生成 basic block，必须同时测试：
  - `entryBlockId`
  - terminator 完整性
  - serializer / parser round-trip
- 凡是解除 compile gate blocker 的提交，都必须同步更新：
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_lowering_skeleton_pre_pass_implementation.md`
  - `frontend_lowering_cfg_graph_plan.md`
  - 本文档
  - 对应 lowering / backend 测试
