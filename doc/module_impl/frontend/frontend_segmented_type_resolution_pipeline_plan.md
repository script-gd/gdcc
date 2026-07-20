# Frontend Segmented Type Resolution Pipeline Plan

## 1. 文档状态

- 性质：重启后的实施计划。
- 目标模块：`src/main/java/gd/script/gdcc/frontend/sema/**`，并影响 `frontend/scope/**` 与 frontend lowering 的 analysis contract。
- 直接动机：用 Godot 风格的 interface/body 分层与 source-order suite 解析替代原先 statement-window segmented runner 路线，使 `var limit := 3; for i in limit:` 这类依赖前缀 typed fact 的语义支持拥有稳定架构基础。阶段 I 后，旧 runner 与 shared analyzer legacy whole-phase bypass 已从代码中删除。
- 主体方案：以 interface phase + body `SuiteResolver` 为主线，即先建立 class/callable/block 的 lexical 与 signature/interface 事实，再按 body suite 源码顺序解析 statement。
- 辅助方案：引入 `TypedLexicalEnvironment` overlay，使当前 statement / 当前 suite 内的 local slot typed fact 能被后续 semantic step 读取，而不提前污染最终 stable side table。
- 实施策略：允许把阶段 A-D 的已有实现视为“可保留资产 / 可重写参考 / 可回退资产”。本文不假设当前 segmented scheduler 已经是新路线的可继续扩展基础，也不把现有 `analyzeInWindow(...)` 当作可抽取的 statement runner。
- 非目标：本文不直接完成 `for-range` lowering，不改变 Godot range runtime 语义，不一次性转正 `lambda` / `match` / block-local `const`。

关联文档：

- `doc/module_impl/frontend/frontend_rules.md`
- `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
- `doc/module_impl/frontend/frontend_local_type_stabilization_implementation.md`
- `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
- `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_lowering_plan.md`
- `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
- `doc/module_impl/frontend/frontend_for_range_loop_implementation_plan.md`
- `doc/analysis/frontend_semantic_analyzer_research_report.md`

### 1.1 架构决定：无条件结构性 inventory 与禁止 typed-dependent body gate

本决定优先于本文后续章节中所有与之冲突的旧 gate 设计，也约束所有后续新增或更新的
frontend feature 实施文档。

- 所有待支持的 AST 节点必须在 body typed resolution 前无条件建立完整的结构性 lexical
  inventory。该 inventory 包括 body `BlockScope`、parameter、iterator、ordinary local 等
  source-facing binding、`FrontendBodyDeclarationIndex` entry 与 typed baseline；不得等待
  header 或 initializer 的 typed fact，也不得把 inventory publication 作为 typed fact 的
  副作用。
- 类型事实只能用于 source-facing type refinement、semantic route 分类和 lowering / compile
  readiness。它们不得决定 body inventory 是否发布，也不得决定 `SuiteResolver` 或
  `FrontendVisibleValueResolver` 是否进入一个已支持 body。
- 后续 feature 不得新增 typed-dependent body entry gate。一个 AST 节点在其结构性
  inventory path 尚未实现前可以整体保持 unsupported / deferred；一旦转正，其 body 必须
  无条件进入 shared semantic，不能通过 `PENDING`、`SUPPORTED`、`PUBLISHED` 或等价 typed
  readiness 状态延迟解封。
- 新 feature 的实施顺序固定为：scope graph -> 完整 lexical inventory -> declaration index /
  baseline -> `SuiteResolver` body entry -> typed resolution -> type refinement / lowering route。
  compile gate 的 route readiness 属于 lowering 边界，不是 semantic body entry gate。

第 4.5 节、第 4.8 节和阶段 F 中的 `FrontendInventoryGate` / readiness 设计是当前遗留实现
的记录与清理参照，不能作为后续 feature 的实施方案。现有 typed-dependent gate registry、
resolver readiness fallback、pending-gate 注册与 synthetic classifier 必须在其不再承担当前
unsupported compatibility 行为后删除或重写为纯结构性支持判断；不得为它们补充新的 feature
consumer 或 publication protocol。

## 2. 背景与问题

当前 `FrontendSemanticAnalyzer.analyze(...)` 是 whole-module phase pipeline：

1. skeleton
2. scope
3. variable inventory
4. top binding
5. local type stabilization
6. chain binding
7. expr typing
8. var type post
9. annotation usage
10. virtual override
11. type check
12. loop control
13. compile-only gate，仅 `analyzeForCompile(...)`

这条顺序让每个 phase 都能消费前一个 phase 的完整 module 事实，但它无法自然表达 Godot 的 body 解析模型：

- Godot parser 只建立 AST / suite / local name 结构，不做类型解析。
- Godot analyzer 在 `resolve_body()` 中进入 `resolve_suite()`，按源码顺序逐个 statement 调用 `resolve_node(...)`。
- `var x := expr` 的类型稳定、initializer expression reduce、assignment compatibility check 都在同一个 source-order 语句解析链中完成。
- `for i in iterable:` 先 reduce iterable expression，再决定 iterator 类型，最后才解析 loop body suite。

这里的 Godot 对照只用于说明 interface/body 边界与 `resolve_suite()` 的 source-order 解析形状，不用于把“完整 local inventory”与“增量/source-order analysis”对立起来。Godot parser/suite local registry 与 analyzer source-order 解析可以同时成立；GDCC 的完整 inventory 要求来自自己的 resolver filtered-hit 模型，见第 3.2 节。

原 statement-window segmented runner 方案试图在现有 phase pipeline 上模拟 source-order，但实际暴露出两个结构性阻塞：

- 已提取的 `analyzeInWindow(...)` 只改变发布表面，不限制 analyzer 遍历范围，仍偏 whole-module。
- `FrontendLocalTypeStabilizationAnalyzer` 的 window 路径把 slot update 延迟到 patch commit，整模块运行时会让后续 `var b := a` 读不到前序 `a` 的稳定类型。
- `FrontendVarTypePostAnalyzer.analyzeInWindow(...)` 不是纯 scratch-over-stable：它取得 stable `analysisData.slotTypes()` 引用、直接 `clear()` 并把该引用传给 `SlotTypePublisher` 写入，然后才复制到 `window.publications().slotTypes()`。因此现有 window publication 路径已经能在 patch commit / discard 前污染 stable side table。

现有 analyzer 不是“window runner”。它们的 `analyze(...)` / `analyzeInWindow(...)` 入口仍以 `moduleSkeleton.sourceClassRelations()` 为根，创建内部 `AstWalker...` / visitor 并遍历完整 `SourceFile` AST。`FrontendWindowAnalysisContext` 只把发布目标换成 scratch surface；它没有把遍历 root 限制到当前 statement、当前 suite 或当前 body。因此，新的 body `SuiteResolver` 不能通过简单迁移这些入口获得。它需要在现有语义规则与 owner 合同之上，重写每个 owner 的 statement-local 调度、显式上下文状态和发布逻辑。

这也是一次执行框架重写，而不是 statement-window 方案的续作：`FrontendSegmentedSemanticScheduler` 曾只作为证明 patch merge / stable reference 行为的过渡资产，阶段 I 后已删除。最终 pipeline 不再保留 shared analyzer 级别的 legacy whole-phase body semantic bypass。

因此，`FrontendWindowPublicationSurface` / `FrontendWindowAnalysisContext` 不能作为新 overlay/export 的正确性参考。类型本身的 API 试图表达 scratch view，但现有 analyzer 使用方式已经破坏该承诺；计划只能把它们作为 legacy comparison / targeted regression 的输入，不能把它们当作可复用设计。

因此新计划改为 interface/body 双层结构：

- interface 层基于基础结构层已发布的 lexical inventory 建立 declaration index、signature/interface facts 与 typed baseline。
- body 层用 `SuiteResolver` 按源码顺序解析 supported body statements。
- `TypedLexicalEnvironment` overlay 在 body 层提供 Godot 风格的“当前语句已知 typed fact 对后续语义立即可见”能力，同时保留 GDCC 的 side-table owner、patch conflict 与 compiler-only type 隔离。
- Suite 收敛后的 stable export 采用按 owner 有序的 patch transaction：base owner facts 按 top binding -> local stabilization -> chain binding -> expr typing -> var type post 顺序提交；已实现的 feature-specific semantic route owner 可以在 expr typing 与对应 var type post 之间插入独立 patch，例如后续 `FOR_ITERATION_PLANNING`。不能把这些 owner 合并成一个多 owner `FrontendAnalysisPatch`。
- Patch 相关类型统一迁入 `gd.script.gdcc.frontend.sema.patch` 包，包括旧 `FrontendAnalysisPatch`、`FrontendLocalSlotTypeUpdate` 与新建 per-owner patch / transaction 类型；window publication 类型若保留，只能作为 legacy shim。

## 3. 不变量

### 3.1 Phase owner 边界仍然保留

重启计划不是允许任意 resolver 写任意表。owner 边界保持不变：

- `FrontendVariableAnalyzer` 仍拥有 parameter / ordinary local inventory publication。
- `FrontendTopBindingAnalyzer` 仍拥有 `symbolBindings()`。
- `FrontendLocalTypeStabilizationAnalyzer` 仍只拥有 source-facing local `:=` slot rewrite，不拥有 diagnostics，不发布 `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` / `slotTypes()`。
- `FrontendChainBindingAnalyzer` 仍拥有 `resolvedMembers()` 与 chain-owned `resolvedCalls()`。
- `FrontendExprTypeAnalyzer` 仍拥有 `expressionTypes()` 与 bare-call `resolvedCalls()`。
- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 不得恢复为第二个 slot mutation owner；它必须保持 strict no-op / guard-only。
- `FrontendVarTypePostAnalyzer` 仍拥有 `slotTypes()`。
- `FrontendTypeCheckAnalyzer`、`FrontendLoopControlFlowAnalyzer`、`FrontendCompileCheckAnalyzer` 仍是 diagnostics-only consumer。

上述 owner 边界是语义合同，不是当前 analyzer 遍历代码可直接复用的证明。当前 top binding、local stabilization、chain binding、expr typing、var type post analyzer 都把遍历控制、隐式状态和 side-table 发布耦合在 whole-module AST walker 内。SuiteResolver 下的 owner 子过程必须重新实现为 statement-local procedure：由外层 statement dispatcher 提供 suite context、block context、restriction/static/property initializer context 与 typed lexical environment，而不是让每个 analyzer 自己再次从 module root walk。

### 3.2 完整 lexical inventory 先于 body typed resolution

GDCC 的完整 lexical inventory 要求不是因为 Godot 缺少前向 local 检测，也不是把 complete inventory 与 source-order analysis 当成二选一。Godot parser / suite local registry 先让 analyzer 阶段能查询完整 local set，`resolve_suite()` 再按 source order 解析；前向 local usage 以 `CONFUSABLE_LOCAL_USAGE` warning 表达。GDCC 与 Godot 的真实差异在诊断级别和实现模型：GDCC 把 future declaration 编码为 resolver filtered hit，并把它作为 binding / diagnostics provenance 的一部分。

在 GDCC 当前模型中，已支持 executable block 的 ordinary local inventory 由 `FrontendVariableAnalyzer` + scope graph 发布。`BlockScope.resolveValueHere(...)` 对当前层只做无 source-order 过滤的 map lookup；`FrontendVisibleValueResolver.resolve(...)` 在拿到当前层 hit 后，再由 `filterInvisibleCurrentLayerHit(...)` 按 source byte order 过滤。

因此 `Scope.resolveValueHere(...)` 必须能看到同层 future declaration：

- declaration 已结束于 use-site 前：直接可见。
- declaration 位于 use-site 后：记录 `DECLARATION_AFTER_USE_SITE` filtered hit。
- use-site 位于同一 declaration initializer 内：记录 `SELF_REFERENCE_IN_INITIALIZER` filtered hit。

若把 local inventory 裁剪成只包含当前 statement 前缀，resolver 就看不到 future declaration，`var x := y; var y := 1` 会被误判为普通 miss 或外层 fallback，并丢失 declaration-after-use provenance。

正确模型是：

- 对已支持的 block，基础结构层沿用现有 `FrontendVariableAnalyzer` + scope graph 发布完整 ordinary local inventory；interface 层建立 body declaration index / typed baseline view，而不是另起一套重复发布通道。生产 resolver 仍由 scope 完成按名称查找，并以 index 的 declaration identity 验证命中的 ordinary local 属于已发布 inventory；现有 source byte range filter 继续负责 declaration-order 与 initializer self-reference 语义。
- local `:=` 初始类型仍是 `Variant`。
- body `SuiteResolver` 只负责按源码顺序稳定类型并发布 use-site facts；它不得通过 typed fact 决定 child body 的 inventory 或 entry readiness。
- 若某个 child feature 后续转正，必须在 interface/inventory 阶段无条件发布该 child body 的结构性 binding 与完整 local inventory，再解析 body suite；header typed fact 只能影响后续 type refinement、semantic route 或 lowering。

这保证 `var x := y; var y := 1` 仍能产生 declaration-after-use filtered hit，而不是把 `y` 误判成普通 miss 或外层 fallback。

### 3.3 `FrontendAnalysisData` 稳定引用合同保持不变

现有测试要求 `FrontendAnalysisData.updateXxx(...)` 保留 side table 对象引用，并通过 clear + putAll 清理 stale entry。新计划不能破坏这个外部合同。

必须做到：

- 保留现有 `updateXxx(...)` whole-table publication API 和测试。
- 保留 `applyPatch(...)` 或等价 merge API 表达部分提交，但其输入必须是单一 owner patch；suite export 通过有序 patch transaction 依次调用 merge API，不能把跨 owner facts 塞进同一个 patch。
- 同一个 side table 的 stable reference 仍不替换。
- 增量 merge 必须检测冲突，不能静默覆盖不兼容 fact。
- `TypedLexicalEnvironment` 的 overlay fact 只有在 owner 合法、冲突校验和 compiler-only guard 通过后，才能封装为对应 owner patch 并导出到 stable side table。
- 保留 `updateXxx(...)` 不等于允许它继续作为 source-facing typed publication 的自由入口。只要某个 `updateXxx(...)` 仍会接收 `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、`expressionTypes()`、`slotTypes()` 这类用户可见 typed facts，它就必须先复用与 overlay write / patch merge 相同的 compiler-only guard；否则它只能继续作为 legacy whole-table publication API 存在，不能出现在 production SuiteResolver path。
- 所有 production patch carrier、patch transaction 与 local slot update carrier 都位于 `gd.script.gdcc.frontend.sema.patch` 包；`FrontendAnalysisData` 保留 stable data ownership，只暴露 merge entrypoint。Window publication 类型若迁入该包，也必须标记为 legacy shim。

### 3.4 skipped / deferred subtree 合同保持不变

- skeleton 发布的 `skippedSubtreeRoots()` 仍是后续 phase 的硬边界。
- unsupported feature boundary 不能降级为 `NOT_FOUND`。
- `DEFERRED` / `BLOCKED` / `DYNAMIC` / `FAILED` / `UNSUPPORTED` status 不能被压扁。
- compile gate 仍只在 `analyzeForCompile(...)` 运行，且只消费最终 published facts。

### 3.5 compiler-only type 隔离

任何 stable publication 或 overlay export 都必须拒绝将 `GdCompilerType` 写入用户可见 facts：

- `expressionTypes()` 的 `publishedType()`
- source-facing local / parameter / iterator 的 `slotTypes()`
- ordinary local `ScopeValue.type()`
- `symbolBindings()` 中 source-facing `resolvedValue.type()`
- `resolvedMembers()` 中 user-visible `receiverType` / `resultType`
- `resolvedCalls()` 中 user-visible `receiverType` / `returnType` / `argumentTypes` / callable boundary parameter types

Feature-specific `GdCompilerType` 只能作为 hidden compiler state contract 被对应 feature 的 lowering 消费，不能通过普通 expression typing、ordinary local slot publication 或 user-visible binding payload 泄漏。

当前实现的 `FrontendAnalysisData.checkPatchDoesNotLeakCompilerOnlyTypes(...)` 只扫描 `expressionTypes()` 与 `slotTypes()`；`refreshPublishedLocalBindingPayloads(...)` 只覆盖 local slot update 后刷新 binding payload 的路径。因此本节是不变量目标，不是现有 `applyPatch` guard 已经完整覆盖的事实。阶段 C 必须把 patch-commit guard 与新 typed overlay guard 扩展到上面的所有 type-bearing publication surfaces，至少先关闭 `symbolBindings().resolvedValue.type()` 的直接 patch / overlay bypass；不得用 legacy window publication 行为证明该 guard 完整。

这里的“统一 guard”不是一句抽象约束，而是同一个 type-bearing field walker / validator 合同：

- patch commit、overlay pending write、overlay flush、任何仍保留的 source-facing `updateXxx(...)` whole-table publish，都必须复用同一套 field walker，而不是各自挑几张表做局部检查。
- walker 至少要能递归访问 `FrontendBinding.resolvedValue().type()`、`FrontendResolvedMember.receiverType()` / `resultType()`、`FrontendResolvedCall.receiverType()` / `returnType()` / `argumentTypes()` / exact callable boundary parameter types、`FrontendExpressionType.publishedType()`、`slotTypes()` value、`FrontendLocalSlotTypeUpdate.type()`。
- 只检查顶层 side table name 不足以证明安全；必须检查 fact payload 中所有 user-visible `GdType` 字段。
- 任何依赖 `FrontendAnalysisData.symbolBindings()` / `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` / `slotTypes()` 返回的可变 stable 引用来直接 `put()` / `clear()` / `putAll()` 的路径，都不能被视为满足本节不变量的 publication path。
- Godot 在这里没有等价的 compiler-only publication guard 或 overlay export 机制；本节约束完全由 GDCC 自己的 side-table / lowering 边界不变量驱动，不能靠 Godot 对照“类比证明”。

### 3.6 Diagnostics owner 与去重合同保持不变

- interface phase 与 body phase 都必须通过既有 `DiagnosticManager` 发布 diagnostics。
- upstream 已有同 anchor error 时，下游 analyzer 不能补同级重复错误。
- `sema.binding` / `sema.member_resolution` / `sema.expression_resolution` / `sema.type_check` / `sema.compile_check` 的 owner 边界不能漂移。

### 3.7 已实施资产可回退但不能静默改变语义

阶段 A-D 已有代码可以按新计划保留、移动、作为重写参考、废弃或回退，但任何回退必须保持：

- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` guard-only 合同。
- `FrontendAnalysisData` stable reference 合同。
- patch merge 的冲突检测与 compiler-only guard。
- unsupported / deferred subtree fail-closed 合同。

## 4. 核心设计

### 4.1 新的三层 pipeline

重启后的 shared semantic pipeline 分成三层。

基础结构层：

1. skeleton
2. scope graph
3. baseline inventory

基础结构层职责是建立 `FrontendModuleSkeleton`、`scopesByAst()`、callable parameter inventory、supported ordinary local inventory，以及 skipped/deferred subtree 的硬边界。它不做 body expression typing。

Interface 层：

1. class / callable / property signature interface
2. per-callable body declaration index
3. source-order local typed baseline

Interface 层借鉴 Godot `resolve_interface()` 与 `resolve_body()` 之间的边界：它不直接 lowering body，也不发布 compile-ready body facts，但必须准备 body `SuiteResolver` 所需的 typed lexical baseline。

Body 层：

1. `SuiteResolver` 按源码顺序进入 supported body suite
2. statement resolver 驱动 top binding / local stabilization / chain binding / expr typing / slot post 的 owner 子过程
3. `TypedLexicalEnvironment` 为当前 statement 和当前 suite 提供 effective typed lookup
4. body suite 收敛后导出 stable side tables

诊断-only 层仍在 body facts 完全收敛后运行：

1. annotation usage
2. virtual override
3. type check
4. loop control
5. compile-only final gate

### 4.2 Interface phase

新增 `FrontendInterfacePhase` 或等价 coordinator。它不取代 skeleton/scope/variable analyzer，而是在它们之后建立 body 解析所需的 interface surface。

Interface phase 输入：

- `FrontendModuleSkeleton`
- `scopesByAst()`
- baseline parameter / ordinary local inventory
- current diagnostics snapshot
- `ClassRegistry`

Interface phase 输出：

- `FrontendBodyDeclarationIndex`：每个 supported block 的完整 declaration 列表与 source order；production resolver 用它验证 scope local 的 published-inventory identity。
- `FrontendTypedLexicalBaseline`：参数、显式 typed local、已可静态确定的 interface-level source-facing slot baseline；production `TypedLexicalEnvironment` 将其作为冻结 fallback。
- `FrontendSuiteEntryRoots`：body layer 可进入的 callable/property initializer/supported block 根列表。

Interface phase 不得：

- 发布 `expressionTypes()`。
- 发布 `resolvedMembers()` / `resolvedCalls()`。
- 将已转正 AST 节点的 body inventory 延迟到 typed fact 可用后；每个转正节点必须在本阶段无条件发布其 body scope、完整 declaration index 与 baseline。
- 将 `GdCompilerType` 写入 source-facing lexical baseline。

### 4.3 Body `SuiteResolver`

新增 `FrontendSuiteResolver` 或等价 body coordinator。它按 Godot `resolve_suite()` 的形状处理 body：

```text
resolveCallableOwner(context, callableOwner):
  exportBatch = new FrontendCallableExportBatch()
  context = newSuiteContext(callableOwner, exportBatch)
  runCallableEntryVarTypePost(context, callableOwner)
  resolveSuite(context, callableOwner.body)
  exportBatch.applyTo(context.analysisData())

runCallableEntryVarTypePost(context, callableOwner):
  for parameter in callableOwner.parameters():
      putSlotType(VAR_TYPE_POST, parameter, baselineType)
  context.typedEnvironment().flushPendingFacts()

resolveSuite(context, block):
  for statement in block.statements():
      resolveStatement(context, statement)
  context.exportBatch().accumulate(context.typedEnvironment().exportPatchTransaction())
```

参数是 callable-entry `VAR_TYPE_POST` facts。它们不是 statement root，因而必须在进入
statement 循环前写入 pending overlay 并 flush 到 current-suite committed overlay。该步骤与
statement-local var type post procedure 互补而非绕过：二者共享 `VAR_TYPE_POST` owner stage、
冲突检查、compiler-only guard 与 callable-scoped export batch；此前不得写 stable `slotTypes()`。

`resolveStatement(...)` 不是新的 owner。它只负责按 statement 结构调用 body-aware owner 子过程：

- identifier / route head 绑定仍由 top binding owner 产出。
- local `:=` slot rewrite 仍由 local stabilization owner 产出。
- chain member / chain call 仍由 chain binding owner 产出。
- expression type / bare call 仍由 expr typing owner 产出。
- source slot final publication 仍由 var type post owner 产出。

这些 owner 子过程是新实现，不是现有 `analyzeInWindow(...)` 的改名。实现时可以抽取纯 helper（例如 binding 分类、chain reduction、expression semantic support），但不能复用 whole-module `AstWalker...walk(sourceFile)` 入口作为 production SuiteResolver procedure。当前 analyzer 内部的大型 visitor 状态机必须被拆成显式 context + statement-local dispatch；否则 source-order prefix facts、pending overlay 与 per-owner patch transaction 都无法保证。

`resolveStatement(...)` 内部的 owner 子过程顺序是新的硬不变量，必须保持 legacy whole-phase 的可见性顺序：

0. Callable-entry var type post pre-publication：在进入 statement 循环前，为每个 parameter 发布 `VAR_TYPE_POST` slot fact，并调用 `flushPendingFacts()` 使其进入 current-suite committed overlay。它不是 statement-local procedure 的例外。
1. Top binding runner：为当前 statement 内的 bare identifier / chain head use-site 写入 binding overlay。
2. Local stabilization runner：在 top binding overlay、当前 suite 已提交 typed fact、stable lexical inventory 之上解析 eligible `:=` initializer，并写入当前 statement 的 local slot pending overlay。
3. Chain binding runner：消费 top binding overlay 与 local stabilization pending / committed slot fact，发布 `resolvedMembers()` 与 chain-owned `resolvedCalls()` overlay。
4. Expr typing runner：消费 binding、member、call 与 local slot overlay，发布 `expressionTypes()` 与 bare-call `resolvedCalls()` overlay；`backfillInferredLocalType(...)` 仍只做 guard-only 检查。
5. Feature-specific semantic route planning：仅在该 statement kind 已有 concrete owner 时运行，例如后续 `FOR_ITERATION_PLANNING`；消费 header expression facts，发布 dedicated route fact / source-facing refinement，但不能控制 body entry。普通 statement 省略本步。
6. Var type post procedure：消费 expression type、feature-specific refinement 与 source-facing local slot overlay，发布 final `slotTypes()` overlay。
7. Pending fact flush：把当前 statement pending overlay 转入 current-suite committed overlay，供后续 statement 读取；不得在这一步写 stable side table。

`SuiteResolver` 必须把 interface surface 的 declaration index 交给 visible-value resolver，以检查 scope 选中的 ordinary local 的 declaration identity 已由 baseline inventory 发布；这不是重新扫描普通 `var`，也不改变 resolver 现有 filtered-hit 规则。它还必须把 typed lexical baseline 传给 root 与 child typed environment，使参数和 ordinary local 在没有更新 overlay / stable slot fact 时仍有 immutable source-facing 初始类型。

Suite 收敛后，不能把 current-suite committed overlay 整体打包为单个多 owner `FrontendAnalysisPatch`。它必须构造按 owner 有序的 patch transaction。嵌套 suite 只把该 transaction 追加到同一 callable-scoped export batch，不得在 child suite 边界直接 apply；根 callable suite 收敛后才由 batch 按追加顺序 apply 各 transaction。每个 transaction 内按 top binding -> local stabilization -> chain binding -> expr typing -> optional feature-specific semantic route planning -> var type post 顺序 apply owner patch；缺少 feature-specific owner 的 statement 直接跳过该 stage。这样既保留 source-order suite 的前缀可见性，又不破坏 `FrontendAnalysisPatch` 现有单 stage / 单 owner 约束。

当前阶段的 transaction / batch 只表达分组、owner 顺序与推迟 stable publication，不表达原子提交。`FrontendPatchTransaction.applyTo(...)` 逐 patch 立即提交；后续 patch 失败时，先前 patch 已写入的 stable side table、`BlockScope` slot 与 binding payload 不回滚。`FrontendCallableExportBatch.applyTo(...)` 同样逐 transaction 提交，且不在 apply 前预检 queued transaction 之间的冲突。由于 child / sibling suite overlay 彼此隔离，已加入 batch 但尚未 apply 的 fact 不参与另一个 transaction 的 overlay conflict check；若错误 traversal、重复 publication 或未来 feature 使两个 transaction 对同一 key 发布不兼容 fact，冲突可能延迟到后一个 transaction apply 时才暴露。该 corner case 本阶段明确保留、不做 prepare/commit 修复；任何 apply 异常后，当前 `FrontendAnalysisData` 及其 stable scope 状态必须整体丢弃，不能继续进入 diagnostics-only phase、compile gate、lowering 或增量复用。

不得重排 1-6，且 feature-specific stage 只能插在 expr typing 与对应 var type post 之间。尤其是 chain binding 读取 receiver local slot 时，必须先看到 local stabilization 对前序 statement 或当前 statement 前序子过程写入的 exact slot fact；否则会重新打开 receiver 被误读成 `Variant` 的历史回归。

Nested chain / argument retry 只能发生在当前 owner 子过程内部的非导出 transient cache 中。当前实现由 `FrontendBodyOwnerProcedures.BodyExpressionResolver` 的 `expressionTypes` / `finalizedExpressionTypes` / `resolvedCalls` 缓存、`FrontendChainReductionFacade.reducedChains` 以及 `FrontendChainReductionHelper` 的 bounded `finalizeWindow` retry 共同承担。Retry 可读取本次 reduction 已经推导出的 receiver / argument / step 临时事实，也可读取当前 statement 之前 owner 子过程已发布到 pending / committed overlay 的事实；但 retry 产生的中间 facts 不写入 `expressionTypes()` overlay、statement pending overlay、current-suite committed overlay 或 stable side table，也不能被 `TypedLexicalEnvironment` 的普通 lookup 读取。它们在当前 owner 子过程结束时丢弃。

`FrontendExprTypeAnalyzer` 对同一 expression / step key 只能在完成 retry、选定最终 `FrontendExpressionType` 后写入一次 expression type overlay。若同一 key 需要先得到 `DEFERRED`、暂定 `Variant` 或其他非最终状态，再得到 exact result，必须把中间值保存在 owner-local transient cache 或专用非导出状态里，而不是发布为 `expressionTypes()` 后再 narrowing / status upgrade。

Feature-specific semantic route planning 若需要插入 statement owner 顺序，只能位于其 header 所需的 top binding、local stabilization、chain binding 与 expr typing 之后，并位于对应 var type post / statement flush 之前。它可以读取当前 statement pending overlay 与 current-suite committed overlay，但不能读取后续 statement facts，也不能控制 body inventory 或 child-suite entry。

第一版 body statement 支持面：

- `VariableDeclaration`，仅 ordinary local `var` 与 supported property initializer。
- `ExpressionStatement`。
- `ReturnStatement`。
- `AssertStatement`，保持现有 compile gate blocker。
- `IfStatement` / `ElifClause` / `else`，header 先解析，body 后递归。
- `WhileStatement`，condition 先解析，body 后递归。
- `ForStatement` 由 for-range 阶段 B/D0 转为 structural supported：header-first，body 通过普通 child-suite path 进入；iteration planning 与 compile readiness 仍由后续 for-range 阶段独立处理。
- `MatchStatement`、`LambdaExpression`、block-local `const` 继续 deferred / unsupported，除非后续阶段显式转正。

### 4.4 `TypedLexicalEnvironment` overlay

新增 `FrontendTypedLexicalEnvironment`，作为 body 层所有 value/type lookup 的 effective view。它不替换 `Scope`，而是包装 `Scope` 与当前 suite 的 typed overlay。

这不是给现有 resolver 增加一个可选参数那么简单。当前 `FrontendVisibleValueResolver`、`FrontendChainReductionFacade`、`FrontendExpressionSemanticSupport` 与各 analyzer 内部回调都默认读取 stable `FrontendAnalysisData` / `Scope` / analyzer-local state。SuiteResolver 路线必须为这些 lookup 建立新的 effective view 入口，使 identifier binding、receiver type、argument type、call/member result 与 local slot type 读取都能先看 pending / committed overlay，再回退到完整 lexical inventory 与 stable side table。

读取顺序：

1. 当前 statement pending overlay。
2. 当前 suite committed overlay。
3. 已发布 stable slot facts。
4. parent typed lexical environment。
5. interface typed baseline 提供的冻结 source-facing fallback；`BlockScope` / `CallableScope` 继续提供 lexical inventory 名称查找。
6. class/global/singleton/type-meta lookup。

Pending overlay 只对当前 statement 内后续 owner 子过程可见。`flushPendingFacts()` 后，它才变成 current-suite committed overlay，并对后续 statement / feature-specific semantic route planning 可见。Committed overlay 仍不是 stable publication。

Parent environment 只能沿 parent 链读取上层 overlay，不能读取 child environment 的 pending 或 committed facts。Child suite 保持独立 overlay；其 facts 仅以 per-owner patch transaction 追加到 shared callable export batch，并在根 callable 完成前保持不在 stable side table 中。

写入规则：

- 只有 `FrontendLocalTypeStabilizationAnalyzer` 可写 source-facing local slot overlay。
- 只有 `FrontendTopBindingAnalyzer` 可写 binding overlay。
- 只有 `FrontendChainBindingAnalyzer` 可写 member / chain-call overlay。
- 只有 `FrontendExprTypeAnalyzer` 可写 expression type / bare-call overlay。
- 只有 var type post owner 可写 source-facing slot type overlay；它包括 callable-entry 参数预发布与 statement-local local-`var` publication，两者都必须使用 `VAR_TYPE_POST` stage。
- overlay fact 必须带 owner metadata，并在导出到 stable side table 前执行冲突检测、idempotent 检查和 compiler-only guard。
- compiler-only guard 必须检查该 fact 可达的每个 user-visible `GdType` payload；不能只检查 `expressionTypes()` / `slotTypes()` 两个表。
- compiler-only guard 必须在 pending overlay write 时就 fail-fast，而不是等 suite export 时才补救。任何 scratch 写入如果命中 `GdCompilerType`，必须在 write API 返回前拒绝该 fact，不能先写入 pending / committed overlay 再在 export 时回滚。
- `expressionTypes()` overlay 只接受当前 statement 内每个 AST key 的最终 publication fact。retry 中间计算（包括首 pass 的 `DEFERRED`、暂定 `Variant`、临时 status / detailReason）必须留在 owner procedure 的非导出 transient cache，不得作为 overlay fact 写入。
- `expressionTypes()` overlay 不提供 `Variant -> exact`、parent -> child 或 terminal status -> success 的 narrowing 例外。这不是 overlay 的局部规则，而是对 `FrontendAnalysisData.sameExpressionType` 严格判据（status + publishedType + detailReason 全等，见第 4.6 节）的直接引用。需要 narrowing 的 local slot 变化必须走 `FrontendLocalSlotTypeUpdate`，不得绕道 `expressionTypes()` republish。

四层事实可见性模型固定为：

- Owner procedure transient cache：owner 子过程私有，只给当前 chain / expr reduction 的 retry 回调读取；不属于 `TypedLexicalEnvironment`，不参与 flush / export，owner 子过程结束即丢弃。
- 当前 statement pending overlay：只给当前 statement 后续 owner 子过程读取；只接受每个 AST key 的最终 publication fact。
- current-suite committed overlay：由 pending fact flush 合并而来，给后续 statement 与 feature-specific semantic route planning 读取；仍不是 stable publication。
- `FrontendAnalysisData` stable side tables / `BlockScope` stable slot：只在 suite export 的 per-owner patch apply / stable export helper 后更新，供 diagnostics-only phase、compile gate 与 lowering 使用。

Overlay 的目标是模拟 Godot “当前 statement 已解析出的类型可被后续 statement 使用”的效果，同时避免提前污染 `FrontendAnalysisData` stable tables。

写入与导出时机：

- Statement owner runner 只能写当前 statement pending overlay；callable-entry var type post 在 statement 循环前写参数 pending overlay。
- `flushPendingFacts(...)` 只把 pending overlay 合并到 current-suite committed overlay，并执行 owner、conflict、idempotent、exact-type 与 compiler-only guard。该 guard 必须与 suite export 使用同一 type-bearing field walker，避免 scratch 层接受 stable merge 层会拒绝的 compiler-only payload。
- `flushPendingFacts(...)` 不得通过 stable side table 的临时 whole-table publish 再“读回 overlay”。如果当前实现为了复用旧 analyzer helper 需要 `updateXxx(...)` / stable side table 作为中转，则该 helper 必须先被改写或隔离为 legacy path，不能把中转污染当作阶段性可接受状态。
- Suite 收敛时，current-suite committed overlay 只能导出为按 owner 有序的 patch transaction，不能导出为一个跨 owner `FrontendAnalysisPatch`。
- 嵌套 suite 的 transaction 必须追加到 root callable 共享的 `FrontendCallableExportBatch`，不得在 child suite 收敛时独立 apply。根 callable suite 返回后，batch 才按追加顺序逐个 apply 已累积的 transaction；该顺序提交不构成原子批次，也不提供跨 queued transaction 预检或失败回滚。
- 每个 patch transaction 固定按 top binding -> local stabilization -> chain binding -> expr typing -> optional feature-specific semantic route planning -> var type post apply；每个 step 只包含该 owner 的 facts。
- Stable side table 与 `BlockScope.resetLocalType(...)` 只能在 callable export batch 的 per-owner patch apply / stable export helper 中更新。
- Diagnostics-only phase、compile gate 与 lowering 只能读取 suite export 后的 stable facts。
- Nested supported suite 收敛后，必须把其 per-owner patch transaction 追加到外层 callable export batch；lexical visibility 仍由 scope graph 与 resolver filter 决定，不能因为 transaction 合并放宽 local 可见性。

### 4.5 已废弃：Feature gate 与 body readiness

本节描述当前代码中仍存在的 typed-dependent gate scaffolding，仅用于界定遗留清理范围。
它已被第 1.1 节的无条件结构性 inventory 决定取代，不得用于新增 feature；后续实现应删除
本节所述的 registry/readiness body-entry 路径，而不是补全其 publication protocol。

新增 `FrontendInventoryGate` 记录 typed-dependent subtree 的待决状态。第一版至少表达：

```java
enum FrontendInventoryGateStatus {
    PENDING,
    SUPPORTED,
    UNSUPPORTED
}

enum FrontendBodyInventoryReadiness {
    NOT_PUBLISHED,
    PUBLISHING,
    PUBLISHED
}

record FrontendInventoryGate(
        @NotNull Node owner,
        @NotNull Node headerRoot,
        @NotNull Node bodyRoot,
        @NotNull FrontendVisibleValueDomain deferredDomain,
        @NotNull FrontendInventoryGateStatus status,
        @NotNull FrontendBodyInventoryReadiness bodyInventoryReadiness
) {}
```

生命周期固定为：

1. Interface phase 发现 typed-dependent gate：`PENDING + NOT_PUBLISHED`。
2. Body suite 解析 gate header 所需 expression facts。
3. classifier 判定 unsupported：`UNSUPPORTED + NOT_PUBLISHED`，保留 deferred / unsupported boundary。
4. classifier 判定 supported：`SUPPORTED + NOT_PUBLISHED`，此时 body 仍不可解析。
5. body inventory publication 开始：临时 `PUBLISHING`，resolver / binder 仍 fail-closed。
6. gate-owned body binding 与 body 完整 local inventory 成功发布后：`SUPPORTED + PUBLISHED`。
7. 只有 `SUPPORTED + PUBLISHED` 的 body 可以进入 `SuiteResolver`。

需要提供共享 readiness policy 作为唯一入口，例如 `FrontendExecutableInventorySupport.isCallableLocalValueInventoryReady(BlockScope scope, Node useSite, FrontendAnalysisData data)`、`FrontendInventoryGateRegistry.isResolverGateReady(...)` 或等价命名。它不能只回答 `BlockScopeKind`，还必须能回答 owner/body/domain 级别的问题。它必须：

- 对无条件支持的 block kind 继续返回 true。
- 对 gate body，只在能找到 owning gate，且 `status == SUPPORTED && bodyInventoryReadiness == PUBLISHED` 时返回 true。
- 对缺失 gate、`PENDING`、`SUPPORTED + NOT_PUBLISHED`、`SUPPORTED + PUBLISHING`、`UNSUPPORTED`、合成但无 owning gate 的 body 返回 false。
- 为 resolver request-domain、AST boundary edge、current-scope fail-closed 三处使用同一 readiness 事实，避免三处各自判断 `BlockScopeKind.FOR_BODY` 或 deferred domain。
- 被 `FrontendVariableAnalyzer`、`FrontendVisibleValueResolver`、`FrontendLocalTypeStabilizationAnalyzer`、`FrontendVarTypePostAnalyzer`、`FrontendCompileCheckAnalyzer` 等所有 callable-local inventory 消费者共同使用。

现有 `FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(BlockScopeKind)` 只能继续表达无条件支持的 block kind，不得成为 typed-dependent body readiness 的事实源。

### 4.6 Stable export 与 per-owner patch transaction

`TypedLexicalEnvironment` 不是 public publication。只有导出并通过 stable merge 后，事实才成为 diagnostics-only phase、compile gate 和 lowering 可消费的事实。

新增 `gd.script.gdcc.frontend.sema.patch` 包承载 patch 相关类型：

- `FrontendOwnerPatch` 或等价 sealed interface，表达“单一 semantic owner 的 publication delta”。这里的 owner 是 analyzer / publication owner，不是 `FrontendResolvedMember.ownerKind()` / `FrontendResolvedCall.ownerKind()` 中的 `ScopeOwnerKind`。
- `FrontendTopBindingPatch`：只包含 `symbolBindings()` delta。
- `FrontendLocalTypeStabilizationPatch`：只包含 `FrontendLocalSlotTypeUpdate` delta，不直接携带刷新后的 `symbolBindings()` entry。
- `FrontendChainBindingPatch`：只包含 `resolvedMembers()` 与 chain-owned `resolvedCalls()` delta。
- `FrontendExprTypePatch`：只包含 `expressionTypes()` 与 bare-call `resolvedCalls()` delta。
- `FrontendVarTypePostPatch`：只包含 `slotTypes()` delta。
- `FrontendPatchTransaction` 或等价 ordered patch carrier，保存上述 patch 的有序列表并负责按固定 owner 顺序 apply；`Transaction` 在这里不表示原子提交，类型不提供跨 patch 预检或失败回滚。
- 旧 `FrontendAnalysisPatch`、`FrontendLocalSlotTypeUpdate` 迁入同一 package。旧 `FrontendAnalysisPatch` 只能作为 legacy single-stage patch / 测试兼容层或被拆解；suite export 生产路径不得再用它承载多 owner facts。`FrontendWindowPublicationSurface`、`FrontendWindowAnalysisContext` 若迁入该包，必须单独标记为 legacy shim，不属于 production overlay/export 参考资产。

保留 `FrontendLocalSlotTypeUpdate` / `FrontendAnalysisData.applyPatch(...)` 的核心规则，但 `applyPatch` 的输入必须是单一 owner patch。以下规则适用于每个 per-owner patch 的 merge：

- 新 key 直接写入 stable side table。
- 旧 key + 相同 value 视为 idempotent，允许。
- 旧 key + 不同 value 默认 fail-fast。
- `symbolBindings()` 允许因 `FrontendLocalSlotTypeUpdate` 的 commit helper 刷新同一 declaration 的 `resolvedValue` payload，但 local stabilization patch 本身不得携带独立的 `symbolBindings()` delta。刷新必须由 `BlockScope.resetLocalType(...)` 与 binding payload refresh 的同一个 helper 派生完成，并保持 binding kind、name、declaration identity 不变。
- `resolvedCalls()` 中 chain-owned call 与 bare-call 由 semantic owner patch 与 key-space contract 区分，不能相互覆盖；不得把 `ScopeOwnerKind` 当成 semantic publication owner。
- `expressionTypes()` 的同 key republish 只有 `FrontendAnalysisData.sameExpressionType(...)` 判定为同值时允许；stable 判据保持 status + publishedType + detailReason 严格相等。
- `expressionTypes()` 不允许同一 key 出现 status change、same-status + different publishedType、same-status + different detailReason，也不允许从发布 `Variant` 的 fact 变成 exact `int` fact。
- `FrontendExprTypePatch.expressionTypes()` 必须是 expr typing owner overlay 收敛后的 per-key final view。Patch 内同一 key 不得出现不同 logical value，suite export 也不得把 retry 中间事实导出为 stable fact 后再 narrowing / status upgrade。
- `slotTypes()` 不允许同一 source slot 被不同类型覆盖；同类型 idempotent 允许。
- merge 前统一检查 compiler-only type 不泄漏。当前 `FrontendAnalysisData.checkPatchDoesNotLeakCompilerOnlyTypes(...)` 只覆盖 `expressionTypes()` 与 `slotTypes()`，阶段 C 必须把该检查扩展到 `symbolBindings().resolvedValue.type()`、`resolvedMembers()` 的 `receiverType` / `resultType`、`resolvedCalls()` 的 `receiverType` / `returnType` / `argumentTypes` / callable boundary parameter types。否则不能宣称 overlay export 已复用完整 compiler-only guard。

上述 conflict、idempotent、local-slot 与 compiler-only 校验只约束当前 per-owner patch。`FrontendAnalysisData.applyPatch(...)` 在当前 patch 的 merge 前完成这些检查，但连续调用多个 patch 不会形成新的原子边界；fail-fast 只中止当前 apply 链路，不撤销同一 transaction 内已提交的 patch，也不撤销同一 callable batch 内已提交的 transaction。

新增或扩展统一 helper，例如 `checkNoCompilerOnlyLeakInPublishedFact(...)` / `FrontendPublishedFactTypeGuard`，用于 patch commit 与 typed overlay 写入两处。它必须遍历 `FrontendBinding`、`FrontendResolvedMember`、`FrontendResolvedCall`、`FrontendExpressionType`、`slotTypes()` value 与 `FrontendLocalSlotTypeUpdate` 中所有 user-visible `GdType`，并复用同一错误策略。Legacy window publication surface 不能作为该 helper 的正确性基线。

阶段 C 实施前，至少要明确并锁定下面的 guard 扩展 checklist：

- `FrontendAnalysisData.checkPatchDoesNotLeakCompilerOnlyTypes(...)` 从只看 expression/slot 扩展为调用 shared walker，覆盖 binding/member/call payload。
- `FrontendWindowPublicationSurface.WindowSideTableView` 中 `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()` 若仍保留，必须接入同一 walker；不能继续用 `null` guard 只保护 expression/slot。
- `FrontendAnalysisData.updateSymbolBindings(...)`、`updateResolvedMembers(...)`、`updateResolvedCalls(...)`、`updateExpressionTypes(...)`、`updateSlotTypes(...)` 若还保留 source-facing publication 语义，必须先经过 shared walker；否则文档上必须把它们限定为 legacy whole-table path，并从 production SuiteResolver path 排除。
- `refreshPublishedLocalBindingPayloads(...)` 只是一条 local slot commit helper 派生路径，不能被误写成“symbolBindings 已完整受保护”的证明。
- 任何仍暴露可变 stable 引用的 API，都只能依赖调用方 contract 保证“不直接写 stable table”；计划的完成标准必须通过 rewrite/inspection/tests 确保 production body path 不再走这些 bypass。

可调整的是通信路径：body layer 可以先写 `TypedLexicalEnvironment` overlay，再在 suite / callable / module 边界导出 per-owner patch transaction。不得用 `updateXxx(...)` 表达部分提交，也不得用一个 single-stage patch 表达多 owner suite export。

这意味着 Stage E 的 “nested chain / argument retry 读己写” 不是 stable side-table rewrite 能力。Retry 的读己写发生在 chain/expr owner 的 procedure-local transient cache 与当前 statement effective view 中；stable `expressionTypes()` 仍只看到每个 key 的最终一次 publication。

### 4.7 Scope slot mutation

`BlockScope.resetLocalType(...)` 不是 side-table 写入，但它会影响后续 resolver 和 binding payload。新计划必须把这类 mutation 建模为 owner-controlled slot update。

`FrontendLocalSlotTypeUpdate` 至少记录：

```java
record FrontendLocalSlotTypeUpdate(
        @NotNull BlockScope scope,
        @NotNull String name,
        @NotNull Object declaration,
        @NotNull GdType type
) {}
```

`FrontendLocalSlotTypeUpdate` 迁入 `gd.script.gdcc.frontend.sema.patch` 包，并由 `FrontendLocalTypeStabilizationPatch` 携带。它仍然是 local stabilization owner 的专用 carrier，不得被 expr typing 或 var type post patch 复用。

应用规则：

- `FrontendLocalTypeStabilizationAnalyzer` 是唯一允许产生 source-facing `FrontendLocalSlotTypeUpdate` 的 analyzer。
- 只允许 `Variant -> exact` 或 exact same-type no-op。
- 不允许 exact A -> exact B。
- 不允许写入 `GdVoidType`。
- 不允许写入 `GdCompilerType` 到 source-facing local。
- 应用后必须刷新已发布且指向同一 declaration 的 `symbolBindings()` payload。

Overlay 可在导出前提供 effective type，但最终 stable `BlockScope.resetLocalType(...)` 和 binding payload refresh 仍必须走同一个 commit helper，避免出现第二条 slot mutation side channel。

`FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 必须继续保持 guard-only：

- 不得调用 `BlockScope.resetLocalType(...)`。
- 不得刷新 `symbolBindings()` payload。
- 不得产生 `FrontendLocalSlotTypeUpdate`。
- 已稳定同类型 no-op，已稳定异类型 fail-fast。
- compiler-only initializer fact fail-fast。

### 4.8 Resolver 复用规则

第 1.1 节已废弃本节后文的 typed-dependent readiness 规则。保留这些文字仅为识别当前
`FrontendVisibleValueResolver` 的遗留 gate 分支；新增或转正 AST 节点必须以完整结构性
inventory 和纯结构性 supported-body 判断进入 resolver，不得查询 typed readiness。

`FrontendVisibleValueResolver` 继续一次性索引完整 source AST，并继续读取已经发布的完整 lexical inventory。完整 inventory 是 `DECLARATION_AFTER_USE_SITE` filtered hit 的前提条件；重构时不得让 resolver 自己扫描 AST 补找普通 `var`，也不得把 supported block inventory 缩成只包含当前 source-order 前缀的 incremental view。

新增 resolver 能力：

- 接受 `TypedLexicalEnvironment` 或等价 effective lexical view，用于读取当前 statement / current suite 的 typed overlay。
- 继续通过 declaration-order filter 处理 future local 与 initializer self-reference。
- 对 gate header / gate body 使用共享 readiness policy，不允许只靠 `BlockScopeKind.FOR_BODY`、`FrontendVisibleValueDomain.FOR_SUBTREE` 或 scope 存在放行。
- domain gate、AST boundary 检测与 current-scope fail-closed 三道封口都必须保留，并作为同一个 gate readiness contract 同步条件化。

三道封口的 gate 化规则：

1. Request-domain gate：gate body use-site 的 `FrontendVisibleValueResolveRequest` 不能由各 analyzer 手写 domain。`SuiteResolver` / `FrontendSuiteContext` / resolver request factory 必须根据 owning gate readiness 统一决定 domain。`SUPPORTED + PUBLISHED` 的 body lookup 才能作为 `EXECUTABLE_BODY` 进入 resolver；未发布、unsupported、缺失 owning gate 的 body lookup 继续返回 deferred domain 或不进入 resolver。若过渡实现仍允许传入 `FOR_SUBTREE` 等 deferred domain，domain gate 必须先通过同一个 readiness policy 做 owner/body 归属校验与 normalization，不能在裸 `domain != EXECUTABLE_BODY` 判断处提前拒止一个已经 `PUBLISHED` 的 owning body。
2. AST boundary gate：`classifyBoundaryEdge(...)` 不能只按 `ForStatement.body() == childNode` 恒定返回 `FOR_SUBTREE`。对 gate owner 的 body edge，只有 owning gate `SUPPORTED + PUBLISHED` 时才返回 `null` 并允许继续 normal lookup；所有非 published 状态继续返回原 deferred boundary。对 gate header edge，只有当前正在解析该 owner 的 header/classifier 时才允许读取前缀 executable facts；header edge 放行不代表 body edge 已发布。非 gate 或 unsupported edge 保持 fail-closed。
3. Current-scope gate：`BlockScopeKind.FOR_BODY`、`MATCH_SECTION_BODY`、`LAMBDA_BODY` 等 current-scope 兜底仍保留。对 typed-dependent gate body，resolver 必须从 use-site scope 找到 owning body/gate，并且只有 `SUPPORTED + PUBLISHED` 返回 `null`；找不到 owner、gate 缺失、`PENDING`、`SUPPORTED + NOT_PUBLISHED`、`PUBLISHING`、`UNSUPPORTED` 都继续返回对应 deferred boundary。

Header/body edge 需要显式区分。`FrontendInventoryGate.headerRoot` 表示 gate-owned header region；如果某个 feature 有多个 header child root，实施时可以把它扩展为 immutable roots 列表或使用等价的 edge classifier，但必须能判断“当前 use-site 是 header 解析”还是“当前 use-site 是 body 解析”。Body readiness 为 `PUBLISHED` 只打开 body lookup，不自动赋予 header feature-specific state 或 body-local visibility。

Overlay 不得绕过 resolver filter：

- Resolver 先按 request-domain gate、AST boundary、current-scope gate、declaration order 与 initializer self-reference 过滤候选 declaration，再从 `TypedLexicalEnvironment` 读取该候选的 effective type / binding payload。
- 当前 statement pending slot fact 可被同一 statement 后续 owner 子过程消费，但不能让 `var x := x` 的右侧 `x` 通过 self-reference 过滤。
- 跨 statement committed fact 可被后续 statement 消费，但 future declaration 仍必须报告 `DECLARATION_AFTER_USE_SITE` filtered hit。
- Gate classifier 使用 resolver 时也只能读取 header use-site 当前可见的 overlay fact，不能扫描后续 suite statement 弥补 typed fact。

Feature-specific header state 仍属于 gate header 语义，不得因为 body readiness 为 `PUBLISHED` 自动获得 body-local visibility。非 supported 或 readiness 未发布的 gate body 继续返回 `DEFERRED_UNSUPPORTED` 或对应 deferred domain。

### 4.9 已实施资产分类

允许代码回退或重新开始实施，但已有资产必须按类别处理。分类标准必须区分“可保留的语义规则 / helper / 测试”与“不可复用的 whole-module traversal 入口”。

保留资产：

- `FrontendSemanticStage`
- `FrontendAnalysisData.applyPatch(...)`，但要泛化为消费 `FrontendOwnerPatch` 或等价 per-owner patch carrier。
- `FrontendAnalysisData.refreshPublishedLocalBindingPayloads(...)`
- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 的 guard-only 收口
- `FrontendAnalysisDataTest` 中关于 stable reference、conflict、idempotent、compiler-only guard 的测试
- analyzer 内部可抽取的纯语义 helper，如 binding 分类、type compatibility、chain reduction 与 expression support 的无遍历部分；只能在去除 whole-module AST walker 依赖后复用

新增资产：

- `gd.script.gdcc.frontend.sema.patch` 包。
- `FrontendOwnerPatch` 或等价 sealed interface。
- `FrontendPatchTransaction` 或等价有序 transaction 类型。
- `FrontendCallableExportBatch`，累积单个 callable root 及其 nested suite 的 transaction，并只在 root suite 收敛后按追加顺序逐个 apply；当前不做跨 queued transaction 预检，也不提供 batch 原子性或回滚。
- `FrontendTopBindingPatch`、`FrontendLocalTypeStabilizationPatch`、`FrontendChainBindingPatch`、`FrontendExprTypePatch`、`FrontendVarTypePostPatch` 等 per-owner patch carrier。
- patch package 内的 shared merge helper / type-bearing compiler-only guard / owner-field validator。
- `FrontendSuiteResolver` / `FrontendStatementResolver` 的 root-bounded statement dispatch 子框架。
- statement-local owner procedure interface 或等价显式调用约定，接收 `FrontendSuiteContext`、当前 statement root 与 `FrontendTypedLexicalEnvironment`。
- 每个 owner 的显式上下文状态 record / stack，例如 restriction、static context、property initializer context、block scope stack、diagnostic suppression state、procedure-local transient retry cache。

移动 / 兼容资产：

- `FrontendAnalysisPatch` 迁入 patch package；若保留，它只能是 legacy single-stage patch / 测试兼容层，不能作为 suite export 生产路径的 multi-owner carrier。
- `FrontendLocalSlotTypeUpdate` 迁入 patch package，并只由 `FrontendLocalTypeStabilizationPatch` 携带。
- `FrontendWindowPublicationSurface` 与 `FrontendWindowAnalysisContext` 若迁入 patch package，也只能作为 legacy comparison shim。它们不再是 overlay/export 参考实现；若最终删除这两个类型，必须把仍然有效的 surface API tests 拆到 overlay 与 per-owner patch transaction 测试，并新增 VarTypePost window contamination regression test 记录旧路径问题。
- 已删除的 `FrontendSemanticAnalyzer.analyzeWithLegacySharedSemanticPublication(...)` package-private 测试旁路。阶段 I 后不再通过 shared analyzer 进入 legacy whole-phase baseline；需要 owner-level legacy 行为时只能在 focused legacy shim tests 中直接使用对应 analyzer/window API。

重写参考资产：

- `FrontendTopBindingAnalyzer.AstWalkerTopBindingBinder`、`FrontendLocalTypeStabilizationAnalyzer.AstWalkerLocalTypeStabilizer`、`FrontendChainBindingAnalyzer.AstWalkerChainBinder`、`FrontendExprTypeAnalyzer.AstWalkerExprTypePublisher`、`FrontendVarTypePostAnalyzer.SlotTypePublisher` 等内部 walker 只能作为语义规则参考。它们的 whole-module `walk(sourceFile)` 入口、隐式字段状态与整表发布策略不得作为 production SuiteResolver procedure 复用。
- 各 analyzer 的 `analyzeInWindow(...)` 方法名称具有误导性：它只改变 publication surface，不改变 traversal root。它可以保留为 legacy / comparison test path，但不能被归类为可迁移 runner。
- `FrontendVarTypePostAnalyzer.analyzeInWindow(...)` 更不能作为参考：它直接清空并写入 stable `slotTypes()`，再事后复制到 window scratch。新 var type post procedure 必须从这个实现重新写起，而不是修饰调用路径。
- `FrontendChainReductionFacade` 与 `FrontendExpressionSemanticSupport` 可保留算法价值，但它们当前通过 analyzer-local 回调读取 dependency type；SuiteResolver 下必须改为从 `FrontendTypedLexicalEnvironment` 与 owner-local transient cache 读取。

可回退或废弃资产：

- 已删除的 `FrontendSegmentedSemanticScheduler` 过渡实现。阶段 I 后它不再作为代码资产存在。
- 已删除的 `FrontendSemanticAnalyzer.segmentedSemanticRunner` 生产路径开关与 `analyzeWithLegacySharedSemanticPublication(...)` 测试旁路。默认入口只运行 interface/body pipeline。
- 把 `analyzeInWindow(...)` 作为 production body procedure 的任何调用路径。

## 5. 分步骤实施

### 阶段 A：资产盘点与回退边界冻结

实施内容：

- 明确阶段 A-D 已实施代码的处理方式：保留、移动、重写参考、废弃或回退。
- 将 `FrontendSegmentedSemanticScheduler` 标记为过渡实现并在最终阶段删除，不再作为新路线的主体。
- 保留 `FrontendAnalysisPatch`、`FrontendLocalSlotTypeUpdate`、`FrontendAnalysisData.applyPatch(...)` 的测试价值。`FrontendWindowPublicationSurface` 只保留 API-level / legacy comparison 测试价值，不能作为 overlay 隔离参考；patch 相关类型是否迁入 `gd.script.gdcc.frontend.sema.patch` 包需与其 legacy shim 定位一致。
- 明确 per-owner patch 类型与 `FrontendPatchTransaction` 命名方案，旧 `FrontendAnalysisPatch` 不再作为 suite export 生产路径的 multi-owner carrier。
- 明确 `FrontendSemanticAnalyzer.segmentedSemanticRunner` 生产路径开关最终要移除；阶段 I 后该开关及 shared analyzer legacy bypass 均已删除。
- 明确 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` guard-only 合同不可回退。
- 为五个 owner analyzer 建立 walker-state inventory：列出当前内部 visitor 的 traversal root、隐式字段状态、读取的 stable side table、写入的 side table、diagnostic emission 与可抽取 helper。该 inventory 是重写输入，不是迁移完成标志。
- 为 compiler-only guard 建立 payload matrix：逐项记录 `expressionTypes()`、`slotTypes()`、`localSlotTypeUpdates()`、`symbolBindings()`、`resolvedMembers()`、`resolvedCalls()` 当前由谁检查、谁未检查、哪些 API 仍可直接绕过 guard。该 matrix 必须与阶段 C shared walker 设计一起冻结。

当前状态（2026-07-09）：

- [x] A1 在文档中完成资产分类。
- [x] A2 在代码中将 `FrontendSegmentedSemanticScheduler` 标记为 deprecated 或 legacy comparison entry。
- [x] A3 保留 `FrontendAnalysisDataTest`，并将 `FrontendWindowPublicationSurfaceTest` 降级为 API-level / legacy contamination regression 测试，不再作为新 overlay 正确性的参考测试。
- [x] A4 移除或隔离 `segmentedSemanticRunner` 对生产路径的影响。
- [x] A5 确定 patch package 迁移计划、per-owner patch 命名与 transaction apply 顺序。
- [x] A6 完成 whole-module walker state inventory，并标记 `analyzeInWindow(...)` 只允许作为 legacy comparison path。
- [x] A7 记录 `FrontendVarTypePostAnalyzer.analyzeInWindow(...)` 的 stable `slotTypes()` 污染路径，并决定修复、隔离或删除该 legacy path。
- [x] A8 完成 compiler-only guard payload matrix，并明确 `updateXxx(...)` / direct stable side-table 引用哪些是 legacy-only，哪些必须在后续阶段接入 shared walker。

阶段 A 完成记录：

- A1：第 4.9 节的资产分类冻结为阶段 A 基线。`FrontendSemanticStage`、`FrontendAnalysisData.applyPatch(...)`、`refreshPublishedLocalBindingPayloads(...)`、backfill guard 与 `FrontendAnalysisDataTest` 保留；patch carrier 与 transaction 类型进入 `gd.script.gdcc.frontend.sema.patch` 迁移计划；现有 analyzer walker 只作为语义 helper / rewrite reference；`FrontendSegmentedSemanticScheduler`、`segmentedSemanticRunner` 和 production `analyzeInWindow(...)` 调用路径归类为可回退或废弃资产。
- A2/I2：`FrontendSegmentedSemanticScheduler` 在阶段 A 被标记为 `@Deprecated` legacy comparison entry；阶段 I 已删除该过渡实现，SuiteResolver 成为唯一 shared body publication path。
- A3：`FrontendAnalysisDataTest` 继续作为 stable reference、conflict、idempotent 与 compiler-only guard 的 focused tests。`FrontendWindowPublicationSurfaceTest` 的类级注释已降级其解释范围：它只证明 legacy shim API 的 scratch write 隔离与 guard，不证明所有 legacy `analyzeInWindow(...)` 都 scratch-safe。
- A4/H3/I1：阶段 A 先隔离 `segmentedSemanticRunner` 的生产影响；阶段 H 已删除该字段、构造参数和 `withSegmentedSemanticRunnerForTesting()` 工厂；阶段 I 已删除 shared analyzer 的 package-private legacy whole-phase bypass 与 test-source bridge。
- A5：patch package 迁移计划冻结为：新建 `gd.script.gdcc.frontend.sema.patch`；以 `FrontendOwnerPatch` 或等价 sealed interface 作为单 owner patch 根类型；新增 `FrontendTopBindingPatch`、`FrontendLocalTypeStabilizationPatch`、`FrontendChainBindingPatch`、`FrontendExprTypePatch`、`FrontendVarTypePostPatch`；新增 `FrontendPatchTransaction` 按 top binding -> local stabilization -> chain binding -> expr typing -> var type post 顺序 apply。旧 `FrontendAnalysisPatch` 若迁移则只能作为 legacy single-stage compatibility carrier，不能继续作为 SuiteResolver export 的 multi-owner carrier；`FrontendLocalSlotTypeUpdate` 随 local stabilization patch 迁入 patch 包。
- A6：whole-module walker state inventory 见下表。所有当前 `analyzeInWindow(...)` 都只允许作为 legacy comparison path；其 whole-module traversal root、隐式 visitor 字段和整表发布策略都不得直接复用为 SuiteResolver statement-local owner procedure。
- A7：`FrontendVarTypePostAnalyzer.analyzeInWindow(...)` 的 legacy 污染路径为：读取 `analysisData.slotTypes()` 稳定表 -> `clear()` -> 将稳定表传入 `SlotTypePublisher` 逐项写入 -> 再复制到 `window.publications().slotTypes()`。阶段 A 决定是隔离而非修补该 legacy path：保留测试比较价值，但新 var type post procedure 必须从 statement-local scratch/overlay 写入重新实现。
- A8：compiler-only guard payload matrix 见下表。阶段 C 的 shared type-bearing field walker 必须成为 overlay write、patch merge、overlay flush 与任何保留 source-facing `updateXxx(...)` 的共同 guard 基线；不能先写 overlay / stable 再在 export 时补查。

Whole-module walker state inventory：

| Owner analyzer | 当前 traversal root | 隐式 walker state | 读取的 stable side table / state | 写入 side table / patch payload | Diagnostics owner | 可抽取 helper / 不可复用部分 |
| --- | --- | --- | --- | --- | --- | --- |
| `FrontendTopBindingAnalyzer` | 每个 `FrontendSourceClassRelation.unit().ast()` 的 `SourceFile` whole-module walk | `sourcePath`、`moduleSkeleton`、`visibleValueResolver`、`classRegistry`、`reportedUnsupportedRoots`、`ASTWalker` | `moduleSkeleton()`、`scopesByAst()`、parse/skeleton diagnostics snapshot、class registry | `symbolBindings()` | `sema.binding` 与 unsupported binding routes | binding 分类与 visible resolver provenance 可抽取；whole-module walk 和整表 `updateSymbolBindings(...)` 不可作为 SuiteResolver procedure |
| `FrontendLocalTypeStabilizationAnalyzer` | 每个 source file whole-module walk；按 visitor 状态维护 source-order probe | `SilentExpressionResolver`、`probes`、`writeBackStableSlots`、可选 `FrontendWindowAnalysisContext`、probe-local expression memo | `scopesByAst()`、`symbolBindings()`、`resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` 已发布事实、当前 scope slot state | legacy path 直接更新 local `ScopeValue.type()`；window path 产生 `FrontendLocalSlotTypeUpdate` | 不拥有 source-facing diagnostics；只在写回边界 fail-fast 拒绝非法 slot type | initializer probe、compatibility 与 slot rewrite guard 可抽取；whole-module delayed probe 收集和直接 stable scope mutation 不可复用 |
| `FrontendChainBindingAnalyzer` | 每个 source file whole-module walk | `chainReduction`、assignment / expression semantic support、`reportedDeferredRoots`、`reportedUnsupportedRoots`、`ASTWalker` | `scopesByAst()`、`symbolBindings()`、stable slot types、已发布 member/call facts、class registry | `resolvedMembers()`、chain-owned `resolvedCalls()` | `sema.member_resolution`、`sema.call_resolution`、deferred / unsupported routes | chain reduction 与 call/member classification 可抽取；通过 analyzer-local callback 读取 dependency type 与 whole-module transient retry cache 不可复用 |
| `FrontendExprTypeAnalyzer` | 每个 source file whole-module walk | `chainReduction`、assignment / expression semantic support、`parentByNode`、reported expression/deferred/unsupported/discarded roots | `scopesByAst()`、`symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、slot types、class registry | `expressionTypes()`、bare-call `resolvedCalls()`；`backfillInferredLocalType(...)` 只能 guard-only | `sema.expression_resolution`、`sema.deferred_expression_resolution`、`sema.unsupported_expression_route`、`sema.discarded_expression` | expression support、type compatibility 与 discarded-expression rules 可抽取；backfill 不得重新成为 slot mutation owner，whole-module expression walk 不可复用 |
| `FrontendVarTypePostAnalyzer` | 每个 source file whole-module walk | `sourcePath`、`blockScopeStack`、`currentCallableOwner`、`supportedExecutableBlockDepth`、`ASTWalker` | `moduleSkeleton()`、`scopesByAst()`、callable/block scope inventory、current scope slot types | `slotTypes()` final callable-local snapshot | `sema.variable_slot_publication` fail-fast / invariant violations | slot publication eligibility rules 可抽取；legacy `analyzeInWindow(...)` 的 stable `slotTypes()` clear/write 与 whole-table snapshot 不可复用 |

Compiler-only guard payload matrix：

| Publication surface | User-visible type-bearing payload | 当前 guard 覆盖 | 当前绕过点 / legacy-only API | 后续 shared walker 要求 |
| --- | --- | --- | --- | --- |
| `expressionTypes()` | `FrontendExpressionType.publishedType()` | 阶段 C 起由 `FrontendPublishedFactTypeGuard` 统一覆盖 overlay write / flush / export、`applyPatch(...)`、legacy window put 与 `updateExpressionTypes(...)` | direct stable table mutation 仍是 legacy-only 可变引用 bypass，production SuiteResolver path 不得使用 | 新增 typed overlay 与 patch transaction 测试锚定 same walker 覆盖 |
| `slotTypes()` | local / parameter / iterator slot `GdType` value | 阶段 C 起由 `FrontendPublishedFactTypeGuard` 覆盖 overlay write / flush / export、owner patch、legacy patch、window put 与 `updateSlotTypes(...)`；local slot update 仍额外拒绝 void | `analysisData.slotTypes().put/clear` direct mutation 与 VarTypePost legacy contamination path 是 legacy-only bypass | VarTypePost 新 procedure 后续只能写 overlay，不得复用旧 `analyzeInWindow(...)` contamination path |
| `localSlotTypeUpdates()` | `FrontendLocalSlotTypeUpdate.type()` | 阶段 C 起迁入 `gd.script.gdcc.frontend.sema.patch`，由 `FrontendPublishedFactTypeGuard` 覆盖 overlay / owner patch / legacy patch；`FrontendAnalysisData` 继续负责 void / exact rewrite / declaration conflict | legacy `FrontendAnalysisPatch` 仍保留为 compatibility carrier，但 production suite export 使用 `FrontendLocalTypeStabilizationPatch` | `FrontendLocalTypeStabilizationPatch` 独占携带，并在 transaction apply 前后复用 shared guard |
| `symbolBindings()` | `FrontendBinding.resolvedValue().type()` | 阶段 C 起由 `FrontendPublishedFactTypeGuard` 覆盖 overlay write / flush / export、owner patch、legacy patch、window put 与 `updateSymbolBindings(...)`；local slot refresh 仍有额外 payload guard | `analysisData.symbolBindings().put` direct mutation 是 legacy-only 可变引用 bypass，production SuiteResolver path 不得使用 | `FrontendTopBindingPatch` 是 production suite export 的唯一 top-binding carrier |
| `resolvedMembers()` | `FrontendResolvedMember.receiverType()` / `resultType()` | 阶段 C 起由 `FrontendPublishedFactTypeGuard` 覆盖 overlay write / flush / export、owner patch、legacy patch、window put 与 `updateResolvedMembers(...)` | direct stable table mutation 仍是 legacy-only 可变引用 bypass，production SuiteResolver path 不得使用 | `FrontendChainBindingPatch` 是 production suite export 的 member carrier |
| `resolvedCalls()` | `FrontendResolvedCall.receiverType()` / `returnType()` / `argumentTypes()` / callable boundary parameter types | 阶段 C 起由 `FrontendPublishedFactTypeGuard` 覆盖 overlay write / flush / export、owner patch、legacy patch、window put 与 `updateResolvedCalls(...)` | direct stable table mutation 仍是 legacy-only 可变引用 bypass，production SuiteResolver path 不得使用 | chain-owned call 与 bare-call 由 `FrontendChainBindingPatch` / `FrontendExprTypePatch` 分 owner 携带 |

验收细则：

- 已实施 A-D 资产在文档中均有归类。
- 所有 patch 相关类型都被归类为保留、移动、新增或可删除资产。
- 不执行 destructive git 回退也能清晰说明哪些代码可删除，哪些代码需保留。
- 现有 patch/backfill guard focused tests 继续通过；surface tests 不再被解释为“所有 analyzer window path 都 scratch-safe”。
- 没有任何阶段把 `analyzeInWindow(...)` 声明为 production SuiteResolver procedure。
- 文档明确 `FrontendWindowPublicationSurface` 的 API scratch contract 与 VarTypePost legacy 调用行为不一致。
- 文档明确 shared walker 是 overlay write、patch merge 与保留 whole-table publish 的共同 guard 基线，不存在“先写 overlay / stable，export 时再补查”的宽松解释。

### 阶段 B：建立 Interface phase 数据结构

状态同步（2026-07-07）：

- [x] B1 新增 `FrontendInterfacePhase` coordinator；它在 skeleton / scope / variable inventory 之后构建独立 `FrontendInterfaceSurface`，不写入 stable typed side table，也不改变 legacy analyzer 顺序。
- [x] B2 新增 `FrontendBodyDeclarationIndex`，按 supported body root 记录已发布 ordinary local declaration 与 body-local source order；该 index 是 baseline inventory 的只读 view，不重新发布 local。
- [x] B3 新增 `FrontendInventoryGateRegistry`，记录 typed-dependent subtree 与 body readiness；Phase B gate 固定从 `PENDING + NOT_PUBLISHED` 起步，非 `SUPPORTED + PUBLISHED` 查询必须 fail-closed。
- [x] B4 新增 `FrontendTypedLexicalBaseline`，记录 parameter / ordinary local source-facing slot baseline，并在写入时 fail-fast 拒绝 `GdCompilerType`。
- [x] B5 新增 `FrontendSuiteEntryRoots`，列出 body layer 可进入的 callable / property initializer / supported block roots，并明确 typed-dependent body 在 gate 发布前不进入 entry roots。
- [x] B6 保持 skeleton / scope / variable analyzer public contract 不变；新增 `FrontendInterfacePhaseTest` 只调用已发布基础结构层并断言 Interface phase 不写 typed stable side table，现有 phase-boundary / variable inventory focused tests 继续作为回归锚点。

实施内容：

- 新增 `FrontendInterfacePhase` 或等价 coordinator。
- 新增 `FrontendBodyDeclarationIndex`，按 callable/block 记录完整 ordinary local declaration 与 source order。
- 新增 `FrontendInventoryGateRegistry`，记录 typed-dependent subtree 与 body readiness。
- 新增 `FrontendTypedLexicalBaseline`，记录 interface 层可确定的 source-facing typed baseline。
- 新增 `FrontendSuiteEntryRoots`，列出 body layer 可进入的 callable/property initializer/supported block roots。
- 保持 skeleton/scope/variable analyzer 的 public contract 不变。
- 阶段 B 的 `FrontendInterfaceSurface` 暂时仍是可手动构建的数据结构，不是 `FrontendSemanticAnalyzer.analyze()` 主 pipeline 的真实产物；阶段 D 引入 `SuiteResolver` 时必须补齐主流程集成。

验收细则：

- `FrontendSemanticAnalyzerFrameworkTest` 继续证明 skeleton/scope/variable phase boundary 未漂移。
- `FrontendVariableAnalyzerTest` 继续证明 supported block 的完整 local inventory 先发布。
- `var x := y; var y := 1` 仍能通过 resolver 看到 future declaration 并过滤为 `DECLARATION_AFTER_USE_SITE`。
- `for` / `match` / lambda / block-local `const` 仍默认 deferred / unsupported。

### 阶段 C：引入 `TypedLexicalEnvironment` overlay

状态同步（2026-07-07）：

- [x] C1 新增 `FrontendTypedLexicalEnvironment`，包装当前 `Scope`、stable `FrontendAnalysisData`、parent environment、statement pending overlay 与 current-suite committed overlay；pending write / flush / export 均不提前修改 stable side table 或 `BlockScope`。
- [x] C2 新增 `gd.script.gdcc.frontend.sema.patch` 包，迁入 legacy `FrontendAnalysisPatch` / `FrontendLocalSlotTypeUpdate`，并新增 `FrontendOwnerPatch`、`FrontendTopBindingPatch`、`FrontendLocalTypeStabilizationPatch`、`FrontendChainBindingPatch`、`FrontendExprTypePatch`、`FrontendVarTypePostPatch` 与 `FrontendPatchTransaction`。
- [x] C3 新增 `FrontendPublishedFactTypeGuard` shared walker，覆盖 binding/member/call/expression/slot/update 六类 source-facing typed payload；`FrontendAnalysisData.applyPatch(...)`、owner patch、legacy window scratch put 与保留的 `updateXxx(...)` whole-table publish 均接入该 guard。
- [x] C4 Overlay 写入 API 必须显式传入 `FrontendSemanticStage` owner metadata；错误 owner fail-fast，local slot overlay 继续执行 `Variant -> exact` / exact-same-only / no-void / no-compiler-only 规则。
- [x] C5 `flushPendingFacts()` 只把 pending overlay 合并到 committed overlay；`exportPatchTransaction()` 只导出 fixed-order per-owner patch transaction，并由 `FrontendPatchTransaction` 拒绝 owner 顺序回退或重复 owner。
- [x] C6 移除未接入生产路径的 `FrontendOwnerRetryMemo`，并把合同明确为 owner procedure 内部非导出 transient cache：`BodyExpressionResolver` 双 expression cache / call cache、`FrontendChainReductionFacade.reducedChains` 与 helper bounded retry 均不属于 typed lexical environment，不参与 flush / export。
- [x] C7 `FrontendVisibleValueResolver` 新增 overlay-aware `resolve(request, environment)` overload；它只在 declaration-order / self-reference filter 之后替换 returned local 的 effective type，不绕过 future-local filtered hit。
- [ ] C8 Expression semantic support 与 chain reduction facade 的 dependency-type callback 尚未替换为 explicit environment lookup；这是阶段 E owner procedure 重写的一部分，本阶段只提供可接入入口。

实施内容：

- 新增 `FrontendTypedLexicalEnvironment`，包装 `Scope`、suite-local typed facts、pending local slot updates。
- 为 visible value resolver、expression semantic support、chain reduction facade 提供 effective local type 读取入口；现有 stable-data / analyzer-local callback 读取路径必须逐步替换为 explicit environment lookup。
- Overlay 写入必须带 owner metadata。
- Overlay export 必须先按 owner 拆成 per-owner patch，再复用 `FrontendAnalysisData.applyPatch(...)` 的冲突检测；compiler-only guard 必须先扩展为第 4.6 节的全 type-bearing fact guard 后才能复用。
- 为 overlay scratch 写入补同等 compiler-only guard，覆盖 `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、`expressionTypes()`、`slotTypes()` 与 `localSlotTypeUpdates()`。
- Overlay write / flush / export 必须复用同一个 shared walker；测试要能证明 pending write fail-fast 与 export-time fail-fast 的覆盖面完全一致，而不是两套各自演化的 guard 名单。
- 不以 `FrontendWindowPublicationSurface` 作为 overlay 实现参考。它的 API 形状可作为反例/legacy comparison，但 Stage C overlay 必须独立实现并独立证明：写入 pending / committed overlay 时 stable side table 与 `BlockScope` 保持不变。

验收细则：

- 当前 statement pending local slot update 对同一 statement 后续 semantic step 可见。
- 前一 statement committed typed fact 对后一 statement 可见。
- Overlay 不修改 stable side table，直到 export / apply patch。
- Overlay isolation tests 不能复用 VarTypePost window path 作为等价 oracle；必须直接断言 stable `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、`expressionTypes()`、`slotTypes()` 与 local slot backing scope 在 overlay 生命周期内不变。
- Overlay export 不允许跨 owner 混合在同一 patch 中；suite 收敛后必须按固定顺序 apply per-owner patches。
- Overlay 不允许 exact A -> exact B。
- Overlay 不允许 source-facing `GdCompilerType`，测试必须覆盖 binding/member/call/expression/slot/update 六类写入面。
- 若保留 `updateXxx(...)` whole-table publish 参与 legacy flow，必须额外证明这些入口对六类 source-facing typed payload 使用与 overlay/export 相同的 walker；否则它们只能被标记为 non-production compatibility API。

### 阶段 D：实现 body `SuiteResolver` 骨架

状态同步（2026-07-08）：

- [x] D1 新增 `FrontendSuiteResolver` skeleton；默认 owner procedures 为 no-op，只从 `FrontendInterfaceSurface.suiteEntryRoots()` 进入 callable / property initializer / supported child block roots，并在 suite 收敛后通过 `FrontendTypedLexicalEnvironment.exportPatchTransaction()` apply stable facts。
- [x] D2 `FrontendSemanticAnalyzer.analyze()` 已在 skeleton / scope / variable inventory 之后构建真实 `FrontendInterfaceSurface`，并在 legacy body analyzers 之前传入 `FrontendSuiteResolver`；legacy analyzer 顺序保留，SuiteResolver 目前是 shadow no-op body path。
- [x] D3 新增 `FrontendSuiteContext`，显式携带 source path、callable owner、current block scope / scope、restriction、static context、property initializer context、interface surface、typed lexical environment、analysis data、diagnostic manager 与 class registry。
- [x] D4 新增 `FrontendStatementResolver` dispatcher；supported roots 按 top binding -> local stabilization -> chain binding -> expr typing -> var type post 固定顺序调用 injected owner hooks，并在每个 statement/header boundary flush pending overlay。
- [x] D5 `if` / `elif` / `else` / `while` 的 Phase D traversal 已建立 header-first / child-suite-after-header 形状；`for` / `match` / block-local `const` 只触发 fail-closed unsupported hook，不进入 body。
- [x] D6 新增并运行 `FrontendSuiteResolverTest` 及相关 targeted regressions，覆盖 source-order、owner order、header-before-body、unsupported body not entered、overlay export boundary、main pipeline surface hand-off；`FrontendInterfacePhaseTest`、`FrontendTypedLexicalEnvironmentTest`、`FrontendVisibleValueResolverTest`、`FrontendAnalysisDataTest`、`FrontendSemanticAnalyzerFrameworkTest`、`FrontendVariableAnalyzerTest`、`FrontendLocalTypeStabilizationAnalyzerTest`、`FrontendVarTypePostAnalyzerTest`、`FrontendChainBindingAnalyzerTest`、`FrontendExprTypeAnalyzerTest` 均通过。

实施内容：

- 新增 `FrontendSuiteResolver`。
- 在 `FrontendSemanticAnalyzer.analyze()` 主 pipeline 中，于 skeleton / scope / variable inventory 之后构建 `FrontendInterfaceSurface`，并把它作为 `FrontendSuiteResolver` 的输入；不能继续只依赖测试或手动 fixture 构造 interface surface。
- 新增 `FrontendSuiteContext`，携带 source path、callable owner、current block scope、restriction、static context、property initializer context、gate registry、typed lexical environment。
- 新增 `FrontendStatementResolver` 或等价 statement dispatcher。
- 新增 owner procedure registry / dispatch contract，但第一版只接线 no-op 或 fail-closed hook，不复用 whole-module `analyzeInWindow(...)`。
- `FrontendSuiteContext` / owner procedure registry 必须把 `FrontendTypedLexicalEnvironment` 作为显式依赖暴露给后续 owner procedure；阶段 C8 的 expression semantic support 与 chain reduction facade 接入点由阶段 E 真正替换，阶段 D 只建立可传递该依赖的骨架。
- 第一版只遍历当前已支持 body 结构：ordinary statements、`if` / `elif` / `else`、`while`、property initializer。
- `for` / `match` / lambda / block-local `const` 继续 fail-closed。
- 暂不删除 legacy whole-phase analyzer wrappers。

阶段 D 是新 analyzer 子框架的骨架阶段，不是把现有 analyzer 包一层调度器。它必须建立 root-bounded statement traversal：外层 SuiteResolver 决定进入哪个 statement / header / child suite，owner procedure 只能处理传入 root 及其允许的子表达式，不能重新从 `SourceFile` root walk。Godot 的 `resolve_suite()` / `resolve_node()` 只作为 dispatch 形状参考；GDCC 仍必须保留自己的完整 inventory、filtered-hit resolver 与 side-table publication contracts。

验收细则：

- `SuiteResolver` 只进入 `FrontendSuiteEntryRoots` 标记为可进入的 body。
- `FrontendSemanticAnalyzer.analyze()` 必须发布并传递真实的 `FrontendInterfaceSurface` 给 `SuiteResolver`；targeted test 必须证明 interface surface 是主 pipeline 中 skeleton / scope / variable inventory 之后、body suite 解析之前产生的。
- source-order traversal 与 AST statement order 一致。
- child block 递归顺序为 header 先解析，body 后解析。
- `FrontendVisibleValueResolver` 的 declaration-after-use 与 self-reference 测试继续通过。
- Body-aware resolver 调用必须传入当前 `FrontendSuiteContext` 的 `TypedLexicalEnvironment`。
- Statement flush 前 stable side table 与 `BlockScope` 不变；flush 后仅 current-suite committed overlay 可见。
- Suite 收敛后，stable side table 只能通过按序 apply per-owner patch transaction 更新。
- Production SuiteResolver path 不调用任何 analyzer 的 `analyzeInWindow(...)`，也不调用内部 `AstWalker...walk(sourceFile)`。

### 阶段 E：重写 owner 子过程到 suite/body 上下文

实施内容：

- 为 top binding、local stabilization、chain binding、expr typing、var type post 重写 statement-local owner procedure。
- Owner procedure 接收 `FrontendSuiteContext`、当前 statement/header/expression root 与 `FrontendTypedLexicalEnvironment`，不再依赖 whole-module AST traversal 建立隐式上下文。
- `FrontendStatementResolver` 必须按 top binding -> local stabilization -> chain binding -> expr typing -> var type post 的顺序调用 owner procedure。
- 保留现有 analyzer class 名称和 owner 边界。
- `FrontendLocalTypeStabilizationAnalyzer` 不再通过整模块 legacy direct phase 表达 source-order 行为。
- 完成阶段 C8：`FrontendExpressionSemanticSupport`、`FrontendChainReductionFacade` 与相关 chain-head / dependency-type callback 必须改为显式读取 `FrontendTypedLexicalEnvironment`，替换 stable-data / analyzer-local callback 的 dependency type 读取路径。
- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 继续 guard-only。
- Chain / argument retry 的中间 expression facts 必须保存在 owner procedure 内部非导出 transient cache，不得写入 `expressionTypes()` overlay 后再以 narrowing / status upgrade 方式覆盖。

每个 owner 的重写范围必须显式记录：

- Top binding：把 `AstWalkerTopBindingBinder` 的 use-site binding 规则拆为 statement-local binding procedure，并把 restriction、static context、property initializer context 由 `FrontendSuiteContext` 显式传入。
- Local stabilization：把 `AstWalkerLocalTypeStabilizer` 的 eligible `:=` initializer 解析改为立即写 pending overlay，使同 statement 后续 owner 与后续 statement 可按 flush 规则读取；禁止继续依赖整模块 direct phase 更新 `BlockScope`。
- Chain binding：把 chain reduction 的 dependency type 回调改为读取 `FrontendTypedLexicalEnvironment` 与 owner-local transient cache，而不是 analyzer-local stable side table snapshot。
- Expr typing：把 expression fact 发布改为 statement-local final fact publication；父索引、duplicate-report state、retry state 都必须显式化，不能藏在 whole-module walker 字段里。
- Expression semantic support / chain reduction facade：identifier binding、receiver type、argument type、bare-call callee type 与 nested dependency type 读取必须先走 owner-local transient cache / pending overlay / committed overlay 的 effective view，再回退 stable side table；不得继续把 `FrontendAnalysisData.symbolBindings()` / `expressionTypes()` 作为 body procedure 的第一读取源。
- Var type post：把 slot type publication 改为消费当前 statement / current-suite typed facts 的 statement-local publication，不再通过整表 `updateSlotTypes(...)` 表达 body 结果，也不得复用旧 `analyzeInWindow(...)` 的“stable `slotTypes()` clear/write 后再复制到 window”模式。
- Resolver request：request-domain gate、AST boundary gate 与 current-scope gate 的创建必须由 `FrontendSuiteContext` 统一完成，不能继续由各 analyzer 手写 deferred domain。

阶段 E 状态同步：

- [x] E0 重新读取 `AGENTS.md`，用 MCP 列出 `doc` 与 `doc/module_impl`，并并行子代理调研阶段 E 相关文档、owner analyzer 代码与测试基线。
- [x] E1 完成 C8 overlay-aware dependency lookup：`FrontendExpressionSemanticSupport` / `FrontendChainReductionFacade` / chain-head receiver 可通过注入 lookup 读取 `FrontendTypedLexicalEnvironment` 的 effective binding 与 exact local slot fact；`FrontendChainReductionHelper` 的 argument dependency lookup 不再先读 stable `expressionTypes()`，而是委托注入 resolver 保持 overlay-first 合同；旧构造器继续保持 stable-table 兼容。
- [x] E2 新增 `FrontendBodyOwnerProcedures`，以 root-bounded DFS 实现 top binding、local stabilization、chain binding、expr typing、var type post 的核心 statement-local publication，不调用 whole-module analyzer entrypoint。
- [x] E3 `FrontendSuiteResolver` 默认接入真实 owner procedure；阶段 I 后 `FrontendSemanticAnalyzer` 默认无条件运行真实 SuiteResolver，不再保留 legacy-compatible no-op / whole-phase fallback 分支。
- [x] E4 新增正反向 targeted tests，锚定 source-order alias、child-prefix visibility、chain receiver exactness、transient cache isolation、var type post export boundary、C8 overlay lookup 与 framework-level real owner path hand-off。
- [x] E5 已运行格式化、IDE 增量编译与问题检查、`FrontendSuiteResolverTest`、`FrontendExpressionSemanticSupportTest`、`FrontendChainReductionFacadeTest`、`FrontendChainReductionHelperTest`、阶段 E 相关 targeted regression batch，以及 `git diff --check`。
- [x] E6 原通过显式注入真实 `FrontendBodyOwnerProcedures` 的 `FrontendSuiteResolver` 证明 D/E body owner path 在 framework hand-off 中先于 legacy whole-phase publication 执行；阶段 I 后该证据已收口为 `FrontendSemanticAnalyzerFrameworkTest` 中的默认 SuiteResolver body publication、nested source-order facts 与 unsupported fail-closed tests，且不再依赖 legacy baseline。

验收细则：

- `var a := typed_value; var b := a; var c := b` 在 body resolver 下稳定为同一 exact type。
- child block 可读取 parent 前缀 stable local。
- 对 `var b := a`，`b` 的 local stabilization 必须读取前一 statement 已 committed 的 `a` exact slot fact。
- 对 `var x := receiver.member`，chain binding 必须在 local stabilization 子过程之后运行，并消费已稳定的 `receiver` slot fact；不能直接读取 interface baseline `Variant`。
- rejected shadow declaration 不污染 parent slot。
- nested chain / argument retry 保持读己写能力，但这个能力只存在于 owner-local transient cache；同一 expression / step key 在 statement flush 和 suite export 中只产生一条最终 `expressionTypes()` fact。
- retry 过程中出现的任何非最终 expression fact（含 `DEFERRED` 代理类型、暂定 `Variant`、中间 status / detailReason）不得先发布到 pending overlay、committed overlay 或 stable side table 再被最终 fact 覆盖。
- C8 回归测试必须证明：当 stable side table / baseline 仍是 `Variant` 而 pending 或 committed overlay 已有 exact local slot fact 时，`FrontendExpressionSemanticSupport` 与 `FrontendChainReductionFacade` 的 dependency-type callback 消费 overlay exact type，而不是 stable `Variant`。
- C8 negative path 必须证明：support / facade 不能直接读取 owner-local transient cache 以外的非最终 expression fact，也不能绕过 `FrontendTypedLexicalEnvironment` 直接读取 stable-only side table 后发布 stale receiver / argument / bare-call result。
- owner 以外的 procedure 不能写对应 side table 或 slot update。
- 每个 owner 子过程的 suite export 以独立 per-owner patch 出现在 transaction 中；transaction coordinator 按 top binding -> local stabilization -> chain binding -> expr typing -> var type post 顺序 apply。
- Var type post procedure 在 statement flush 前不得改变 stable `slotTypes()`；targeted test 必须在 procedure 运行、flush、suite export 三个点分别断言 stable table 只在 export/apply patch 后变化。

### 阶段 F：接入 generic typed-dependent gate readiness

本阶段只验收 gate registry、readiness 查询与 fail-closed 生命周期，不使用 for-range 作为验收目标。`frontend_for_range_loop_implementation_plan.md` 是该基础设施的后续真实消费者，不是本阶段的前置或完成条件。

实施内容：

- [x] F1 建立 typed-dependent inventory gate 的注册、lookup、status update 与 readiness update API。当前实现以 `FrontendInventoryGate` 的 immutable state transition helper、`FrontendInventoryGateRegistry` 的 mutable lifecycle API，以及 `FrontendExecutableInventorySupport.isCallableLocalValueInventoryReady(...)` 作为 shared readiness 入口；非 `SUPPORTED + PUBLISHED` 的 gate 仍统一 fail-closed。
- [x] F2 使用合成 fixture 或最小测试 gate 验证 `PENDING + NOT_PUBLISHED`、`SUPPORTED + NOT_PUBLISHED`、`PUBLISHING`、`SUPPORTED + PUBLISHED`、`UNSUPPORTED` 的转换。`FrontendInventoryGateRegistryTest.registryTransitionsOnlySupportedPublishedGateToReady` 锚定所有有效生命周期状态，invalid-state tests 锚定 pending / unsupported 不得携带 published readiness。
- [x] F3 支持由前缀 statement typed fact 驱动的测试 classifier，但 classifier 只服务于 gate lifecycle，不实现任何 for-range 规则。`FrontendStatementResolver.OwnerProcedures.runGateClassifier(...)` 接在 top/local/chain/expr 后与 flush 前，`FrontendSuiteResolverTest.classifierReadsPrefixOverlayAndDoesNotOpenUnpublishedBody` 证明 classifier 可读取前缀 `:=` local exact overlay 与 expr typing 已最终发布的 expression overlay；同测例证明 local stabilization 的 transient expression cache 不会提前进入 overlay，并且 classifier 只推进指定 synthetic gate。
- [x] F4 `SUPPORTED` 只表示 header/classifier 通过，不能使 body resolver / binder 放行。`FrontendVisibleValueResolverTest.resolveKeepsSupportedButUnpublishedGateBodyFailClosed` 覆盖 `SUPPORTED + NOT_PUBLISHED` / `SUPPORTED + PUBLISHING`，`FrontendSuiteResolverTest.classifierReadsPrefixOverlayAndDoesNotOpenUnpublishedBody` 覆盖 binder 不进入未发布 body。
- [x] F5 body inventory publication 成功后才原子推进为 `PUBLISHED`。`FrontendInventoryGateRegistry.markBodyInventoryPublished(...)` 是唯一 readiness published transition，resolver / suite entry 只接受 `SUPPORTED + PUBLISHED`；`FrontendSuiteResolverTest.publishedGateBodyIsResolvedBySuiteResolver` 证明 published 后 body facts 可发布。
- [x] F6 建立 resolver gate readiness policy，并接入 request-domain gate、AST boundary gate、current-scope gate 三处判断。`FrontendVisibleValueResolver` 三处封口、`FrontendSuiteResolver` body entry 与 body-local stabilization 均复用 `FrontendExecutableInventorySupport.isCallableLocalValueInventoryReady(...)` / `FrontendInventoryGateRegistry` readiness；`resolvePublishedGateBodyPassesRequestAstAndCurrentScopeGates` 使用 deferred request domain 证明任一旧常量封口未接入都会失败。
- [x] F7 统一 resolver request 创建路径：gate header / gate body lookup 都必须由 `SuiteResolver` / `FrontendSuiteContext` 构造，不能由 analyzer 直接决定 deferred domain。body owner path 已改为通过 `FrontendSuiteContext.visibleValueResolveRequest(...)` 创建请求，并随 current body readiness 选择 `EXECUTABLE_BODY` 或 gate deferred domain；`missingOwningGateBodyContextUsesDeferredDomainAndStaysFailClosed` 锚定缺失 gate 的 typed-dependent body 不回退为 executable lookup。
- [x] F8 unsupported gate 继续保留对应 deferred / unsupported boundary。`FrontendVisibleValueResolverTest.resolveUnsupportedGateBodyDoesNotFallBackToOuterBinding` 证明 `UNSUPPORTED` body lookup 不回退到外层同名 binding。
- 明确非目标：不实现 `range(...)` AST shape 识别、不实现 `INT_SHORTHAND`、不发布 iterator binding、不解封 supported for-range body、不调整 for-range compile gate。

验收细则：

- 合成 gate classifier 可读取前缀 `:=` local 的 typed fact，并只推进该合成 gate 的 lifecycle。
- classifier 已返回 supported 但 body readiness 仍为 `NOT_PUBLISHED` / `PUBLISHING` 时，body lookup 仍必须是 `DEFERRED_UNSUPPORTED` 或对应 deferred domain。
- Classifier 只能在 header statement 的 top binding、local stabilization、chain binding 与 expr typing 子过程完成后读取 overlay。
- Classifier 可读取前序 statement committed typed fact，但不能读取后续 statement 或未运行子过程的 fact。
- Classifier 只能读取 expr typing 已最终发布到 overlay 的 expression facts，不能读取 owner-local transient cache 中未导出的中间 expression type。
- body inventory publication 成功后，readiness 原子推进为 `PUBLISHED`，resolver 才允许进入该合成 body。
- 合成 gate 的 body use-site 在 `PUBLISHED` 后必须同时通过 request-domain gate、AST boundary gate 与 current-scope gate；任一 gate 仍按旧常量逻辑封口都应有测试失败。
- 合成 gate 的 header use-site 可在 header/classifier 上下文读取前缀 overlay fact，但 header 放行不能让 body lookup 提前通过。
- `UNSUPPORTED` gate body lookup 不能 fallback 到外层并制造误导 binding。
- 缺失 owning gate 的合成 body 即使已有 scope，也必须返回 readiness false。
- source-facing facts 仍拒绝所有 feature-specific `GdCompilerType`。

### 阶段 G：收敛 diagnostics 与 compile gate

实施内容：

- [x] G1 确定 interface phase、body suite statement、suite export、diagnostics-only phase 的 diagnostics snapshot 刷新点。`FrontendStatementResolver.flushStatementBoundary(...)` 在每个 body statement flush typed facts 后同步刷新 diagnostics snapshot；`FrontendSuiteResolver` 保留 suite export snapshot；`FrontendSemanticAnalyzer` 保留 interface/suite hand-off 后、annotation/virtual/type/loop diagnostics-only phase 后、compile-only gate 后的 snapshot。
- [x] G2 重新定义 diagnostics 可见性：body statement 解析期间的诊断写入仍进入 `DiagnosticManager`，但 duplicate suppression 必须能区分 statement-local upstream、current-suite upstream 与稳定 phase upstream；不能继续假设每个 whole-module analyzer 结束后才刷新一次 snapshot。`FrontendSuiteResolverTest.statementBoundaryPublishesDiagnosticsSnapshotForLaterStatements` 锚定同 statement 内只能通过 live manager snapshot 读取 statement-local upstream，后一 statement 可通过 `analysisData.diagnostics()` 读取 current-suite snapshot。
- [x] G3 保持 compile gate 只在 `analyzeForCompile(...)` 运行。代码路径未把 `FrontendCompileCheckAnalyzer` 接入 shared `analyze(...)`、suite resolver 或 inspection；既有 `FrontendCompileCheckAnalyzerTest` / `FrontendSemanticAnalyzerFrameworkTest` / `FrontendAnalysisInspectionToolTest` split 覆盖继续作为锚点。
- [x] G4 检查 compile gate 的 duplicate suppression 是否仍能识别 interface/body upstream diagnostics。`FrontendCompileCheckAnalyzer` 在 gate 入口先要求 stable diagnostics boundary 已发布，再冻结 live `DiagnosticManager.snapshot()` 作为 upstream 去重输入；`FrontendCompileCheckAnalyzerTest.analyzeDeduplicatesAgainstLiveManagerSnapshotWhenAnalysisDataSnapshotIsStale` 锚定 manager 中存在但 stable snapshot 尚未刷新的 upstream error 仍会抑制同 anchor `sema.compile_check`。
- [x] G5 对缺失 `slotTypes()`、`DEFERRED`、`FAILED`、`UNSUPPORTED` 的 final facts 保持现有 compile blocking 规则。compile gate 仅调整 upstream snapshot 来源，generic published-fact blocker 与 non-error slot publication blocker 逻辑未改变；`FrontendCompileCheckAnalyzerTest` 中 slot publication、deferred/failed/unsupported/dynamic 覆盖继续通过。

验收细则：

- `FrontendCompileCheckAnalyzerTest` 中已有 compile gate 去重测试继续通过。
- upstream diagnostic 已存在时，下游 phase 不补同级重复错误。
- `analyze(...)` 不运行 compile gate。
- `analyzeForCompile(...)` 在 interface + body facts 完成后运行 compile gate。
- parse / skeleton / scope diagnostics 的顺序与可见性不变。
- 同一 statement 内 owner procedure 产生 upstream diagnostic 后，后续 owner procedure 不再补同 anchor / 同类别重复错误；后一 statement 仍能读取 current-suite diagnostics snapshot 进行去重。

### 阶段 H：切换默认 shared semantic pipeline

实施内容：

- [x] H1 `FrontendSemanticAnalyzer.analyze(...)` 默认运行新 interface/body pipeline。当前生产 `analyze(...)` 在 interface phase 后只调用真实 `FrontendSuiteResolver`，不再追加 legacy whole-phase owner publication，避免 body facts 双重发布。
- [x] H2 legacy whole-phase runner 在阶段 H 只保留为 package-private 测试旁路；阶段 I 已删除该旁路与 test bridge。
- [x] H3 移除 `segmentedSemanticRunner` 生产路径开关。当前已删除 `segmentedSemanticRunner` 字段、构造参数与 `withSegmentedSemanticRunnerForTesting()` 工厂，生产路径不再能切到 deprecated segmented scheduler。
- [x] H4 新增 legacy vs interface/body pipeline 等价测试，等价基线以 guard-only backfill 合同为准；阶段 I 后这些测试已迁移为默认 interface/body pipeline 的 body fact、nested source-order 与 unsupported fail-closed 合同测试。
- [x] H5 broad regression parity follow-up：body owner top-binding 现在与 legacy 共享 dual-role singleton/type-meta 路偏和 global enum type-meta preference，body expression publication 补齐 builtin Variant constructor unsafe-call warning，missing / blocked binding diagnostics 与 legacy binding owner 对齐；阶段 I 后 owner-specific `FrontendExprTypeAnalyzerTest` 使用测试内显式 owner-analyzer baseline helper，不再通过 shared analyzer test bridge 进入 legacy whole-phase baseline。

验收细则：

- 对同一输入，legacy 与新 pipeline 的 `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、`expressionTypes()`、`slotTypes()` 等价，或文档明确接受的 diagnostics 顺序差异有测试锚定。
- unsupported `for` / `match` / lambda / block-local `const` 行为不变，除非对应 gate 已在本阶段显式转正。
- `FrontendVisibleValueResolver` declaration-after-use 与 initializer self-reference 测试继续通过。

### 阶段 I：移除 legacy whole-phase 旁路并更新相关文档

实施内容：

- [x] I1 删除 legacy whole-phase body semantic 旁路。`FrontendSemanticAnalyzer.analyze(...)` 现在无条件执行 interface phase + `FrontendSuiteResolver`；`analyzeWithLegacySharedSemanticPublication(...)` 与 test bridge 已删除。
- [x] I2 删除 `FrontendSegmentedSemanticScheduler` 过渡实现。代码中不再存在 scheduler 文件或 runner 开关。
- [x] I3 保留 patch/overlay/export 基础设施，并移除 single-stage `FrontendAnalysisPatch` 作为 suite export 生产路径的用途。Suite export 继续通过 `FrontendTypedLexicalEnvironment.exportPatchTransaction()` 产生 per-owner transaction；`FrontendAnalysisPatch` 仅保留为 legacy shim / focused tests 兼容载体。
- [x] I4 更新 variable analyzer、visible resolver、local stabilization、chain/expr typing、compile check、for-range plan，明确最终 production shared analyzer 只通过 interface/body + SuiteResolver 发布 body facts。
- [x] I5 更新 `doc/analysis/frontend_segmented_type_resolution_pipeline_execution_summary.md`，记录 Phase I 删除项、final owner 顺序、fact 生命周期、per-owner patch/export、compiler-only guard 与 diagnostics / compile gate 边界。
- 更新 variable analyzer、visible resolver、local stabilization、chain/expr typing、compile check、for-range plan。
- 更新 `doc/analysis/frontend_segmented_type_resolution_pipeline_execution_summary.md`，使其反映最终实现的层级职责、owner 顺序、fact 生命周期、patch/export 合同、compiler-only guard 与 diagnostics / compile gate 边界，并继续保持为目标架构摘要而非旧流水线或过渡资产说明。

验收细则：

- 所有 frontend semantic focused tests 通过。
- 新 pipeline 测试覆盖 per-owner patch merge、patch transaction 顺序、typed overlay、backfill guard-only、source-order typed fact、pending gate、resolver filtered hit、diagnostic dedup、compile gate。
- `doc/analysis/frontend_segmented_type_resolution_pipeline_execution_summary.md` 已与最终代码行为和本计划完成定义同步，且未把 legacy whole-phase 或 window / scheduler 过渡资产写成目标流水线的一部分。
- `./gradlew classes --no-daemon --info --console=plain` 通过。
- 相关 targeted tests 使用 `script/run-gradle-targeted-tests.sh --tests ...` 通过。

### 阶段 J：收口 `FrontendSemanticAnalyzer` 的 legacy-owner constructor 注入

阶段 I 已删除 shared pipeline 的 legacy whole-phase bypass，但不包含无效 constructor
注入的物理删除。本阶段负责删除 `FrontendSemanticAnalyzer` 中所有 legacy-owner
constructor overload，清理 constructor chaining 中被丢弃的 analyzer 实例，并将
framework tests 从“断言 legacy analyzer 未运行”改为验证默认
`FrontendSuiteResolver` 的 body fact publication。

- [x] J1 删除所有接收 `FrontendTopBindingAnalyzer`、
  `FrontendLocalTypeStabilizationAnalyzer`、`FrontendChainBindingAnalyzer`、
  `FrontendExprTypeAnalyzer` 或 `FrontendVarTypePostAnalyzer` 的 public constructor
  overload。这五类参数此前只作 null-check，既不保存为 field，也不进入 production
  pipeline。现有 public API 只保留 default、active phase、interface/body resolver 与
  skeleton/scope/variable 的注入边界。
- [x] J2 清理其余 constructor chaining 中无意义的上述 analyzer `new` 操作；默认与
  active-dependency 注入入口不再构造被丢弃的 legacy owner analyzer。
- [x] J3 将 framework tests 从“注入 probe 后断言 legacy analyzer 未运行”改为验证默认
  `FrontendSuiteResolver` 的 body fact publication；继续保留对 skeleton、scope、variable
  inventory、diagnostics-only phase 与 compile-only gate 的 active dependency probes。
  `activeDependencyConstructorExcludesLegacyBodyOwnerParameters` 正向锚定 active-only
  constructor，并反向检查全部 public constructor 均不含 legacy owner 参数；
  `activeDependencyConstructorUsesDefaultSuiteResolverAndPreservesPhaseBoundaries` 保留
  diagnostics boundary probes 与 callable-entry slot publication，既有 default
  SuiteResolver body-fact / unsupported-subtree tests 继续锚定 export 与 fail-closed 合同。

验收细则：

- `FrontendSemanticAnalyzer` 不再暴露或构造被丢弃的 legacy owner analyzer；其 production
  pipeline 仍只通过 interface/body + `FrontendSuiteResolver` 发布 body facts。
- `./gradlew classes --no-daemon --info --console=plain` 通过。
- 相关 targeted tests 使用 `script/run-gradle-targeted-tests.sh --tests ...` 通过。

### 阶段 K：删除 legacy whole-phase 比较路径并迁移测试基线

本阶段在前一阶段删除 legacy-owner constructor 注入的基础上，继续删除 legacy
whole-phase analyzer comparison path 及 window/patch compatibility shim。必须先完成测试
基线迁移与常量收口再执行 K3-K4 的物理删除，不能因为 production 路径已切换就跳过这些
前置条件。

- [x] K1 迁移或删除五个 legacy owner analyzer 的 standalone/cross-analyzer tests，使新
  `FrontendBodyOwnerProcedures`、overlay 与 per-owner patch transaction 覆盖仍有效的
  semantic contracts；不得仅因 production 无调用者就删除这些测试。chain、expression
  与 var-post 基线已迁入 `FrontendBodyOwnerProcedures*Test` 并通过默认 segmented pipeline
  或真实 root-bounded owner procedure 运行；纯 whole-module boundary/probe/整表清空断言已
  删除。local stabilization 的 source-order/parent-prefix 合同继续由 suite/framework/expr
  tests 锚定，overlay 另补错误 owner、stable conflict 与无副作用负向覆盖。
- [x] K2 将 `FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY` 迁移到
  非 legacy analyzer 的语义常量位置，并更新 `FrontendBodyOwnerProcedures` 与
  `FrontendCompileCheckAnalyzer` 的消费者。常量现由实际发布该诊断的
  `FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY` 持有；compile gate、
  lowering gate 与 focused tests 均不再依赖 legacy analyzer 类型。
- [x] K3 删除 `FrontendTopBindingAnalyzer`、`FrontendLocalTypeStabilizationAnalyzer`、
  `FrontendChainBindingAnalyzer`、`FrontendExprTypeAnalyzer` 与
  `FrontendVarTypePostAnalyzer` 的 whole-module `analyze(...)` /
  `analyzeInWindow(...)` entrypoints、内部 `AstWalker...walk(sourceFile)` traversal
  与 legacy stable-table publication path。仍有效的 owner 语义边界必须由
  `FrontendBodyOwnerProcedures` 保持。五个 legacy 类已物理删除；active expression owner
  保留 discarded-result diagnostic 与 inferred-local consistency guard，但该 guard 只检查
  local stabilization 结果，不写 slot、不刷新 binding payload。framework negative test 同时
  锚定这些类不再出现在 constructor API 或 runtime classpath。
- [x] K4 在 K3 后删除 `FrontendWindowAnalysisContext`、
  `FrontendWindowPublicationSurface`、legacy `FrontendAnalysisPatch`、
  `FrontendAnalysisData.applyPatch(FrontendAnalysisPatch)` 与对应的
  `FrontendPublishedFactTypeGuard.checkAnalysisPatch(...)` compatibility path；
  `FrontendLocalSlotTypeUpdate`、`FrontendOwnerPatch`、`FrontendPatchTransaction` 与
  `FrontendAnalysisPatchException` 仍是 active patch infrastructure，不得删除。window 与
  multi-owner shim 及其 direct API tests 已删除；`FrontendAnalysisDataTest` 现只构造
  owner-specific patches，并补充 binding/member/call conflict 无副作用测试。framework
  negative regression 同时验证三个 shim 类和 legacy `applyPatch` overload 均不存在。

验收细则：

- 不存在任何 production 或 focused test 对 legacy whole-module `analyze(...)` /
  `analyzeInWindow(...)`、window shim 或 `FrontendAnalysisPatch` 的引用。
- per-owner patches 与 `FrontendPatchTransaction` 是唯一的 body-fact export path，且
  `FrontendVarTypePostAnalyzer` 删除前完成 category 常量迁移。
- `./gradlew classes --no-daemon --info --console=plain` 通过。
- 相关 targeted tests 使用 `script/run-gradle-targeted-tests.sh --tests ...` 通过。

### 阶段 L 系列：删除 typed-dependent body gate 历史架构债务

第 1.1 节已确定：body 是否进入 shared semantic 只能由结构性支持面和完整 lexical
inventory 决定，typed fact 只能影响后续 refinement、semantic route 与 lowering。本阶段系列
删除阶段 F 为 synthetic fixture 建立、但从未成为 production feature consumer 的
typed-dependent gate scaffolding；不得将其扩展为 publication protocol、failure state 或新的
feature integration API。

阶段 L 系列只依赖 `frontend_for_range_loop_implementation_plan.md` 的阶段 B 与阶段 D0：

- 阶段 B 已为 `FOR_BODY` 发布 iterator、完整 ordinary local inventory、declaration index 与
  source-facing baseline。
- 阶段 D0 已建立 header-only dispatch，并在 header facts flush 后通过普通
  `resolveChildSuite(...)` 进入 body；iterator 此时允许保持 `Variant` 或显式 declared baseline。
- 阶段 B 在移除 shared semantic unsupported diagnostic 前，已经安装临时、无条件的
  `ForStatement` compile blocker，确保 for-range 阶段 F/G 尚未完成时不会进入 CFG/lowering。

阶段 L 系列**不依赖** for-range 阶段 C、D1、type-check、route-aware compile 解封、CFG 或
lowering。iteration planning 与 iterator slot refinement 可以晚于 gate 删除；它们只能改变 typed
fact 和 lowering route，不能改变阶段 D0 已建立的 body entry。

对于阶段 L 时仍未转正的 lambda、match、block-local `const`、parameter default 等节点，必须
保持纯结构性 unsupported / deferred boundary：不创建 `PENDING` gate、不查询 typed readiness、
不因前缀或 header expression 的类型变化而改变 body entry。这里的“跳过 inventory”必须按
feature-owned surface 理解：lambda 包括 parameter/capture/body，match 包括 pattern binding、guard
与 section body，block-local `const` 包括 declaration/initializer boundary；不能把它们错误归一化
成只有 `BlockScopeKind` 的普通 block body。

实施规则：

- 每个子阶段结束时保持 main/test source 可编译；不得先物理删除类型，再等待后续阶段清理消费者。
- 每个 patch batch 最多修改三个文件；一个子阶段可以拆为多个 batch，并在 behavior batch 后运行
  focused tests、在子阶段结束时运行 compile check。
- “structural support”只表示某种 AST/scope kind 的 inventory path 已实现；“structural
  completeness”表示本次 interface surface 确实发布了完整事实。二者不得合并为一个 boolean。
- compile readiness 不得进入 structural semantic support matrix。

#### 阶段 L0：冻结 for 前置与 compile safety bridge

- [ ] 确认 for-range 阶段 B 与 D0 已完成，且 `FOR_BODY` 不再查询 gate registry。
- [ ] 确认临时 `FrontendCompileCheckAnalyzer.handleForStatement(...)` blocker 只锚定
  `ForStatement`、不进入 body、不读取 iterable type / iteration plan / route。
- [ ] 增加 for-only characterization：shared `analyze(...)` 无 `FOR_SUBTREE` unsupported；
  `analyzeForCompile(...)` 有明确 `sema.compile_check` error，lowering pipeline 在 CFG 前停止。

验收细则：

- `for item in values: var copy := item` 已进入 shared semantic，且即使 `values` 仍是 `Variant`
  也不改变 body entry。
- `FrontendCfgGraphBuilder` 尚无 `ForStatement` 分支时，任何 `for-in` 都不能从 compile-only
  boundary 泄漏到 CFG。

#### 阶段 L1：建立不可变 structural semantic support matrix

- [ ] 新增 `FrontendBodySemanticSupportPolicy` 或等价不可变 helper，统一表达当前结构支持面；
  `FrontendExecutableInventorySupport` 必须委托该事实源或被其替代，不能保留平行 allowlist。
- [ ] policy 只能由 AST/scope kind 决定，不能读取 `GdType`、expression type、typed overlay、
  iteration plan、diagnostic state、compile surface 或 gate lifecycle。
- [ ] policy 必须覆盖下列结构位置，且每一行都有 focused test。

结构支持矩阵最低合同：

- function/constructor/if/elif/else/while/ordinary block body：发布 lexical inventory，进入
  `SuiteResolver`，不使用 deferred domain。
- `for` header：使用外层 scope，只运行 header-only dispatch，不使用 deferred domain。
- `FOR_BODY`：发布 lexical inventory，进入 `SuiteResolver`，不使用 deferred domain。
- lambda parameter/capture/body：不发布 feature-owned inventory，不进入 `SuiteResolver`，domain 为
  `LAMBDA_SUBTREE`。
- match pattern/guard/section body：不发布 feature-owned inventory，不进入 `SuiteResolver`，domain 为
  `MATCH_SUBTREE`。
- block-local `const` declaration/initializer：不发布 local-const inventory，不进入
  `SuiteResolver`，domain 为 `BLOCK_LOCAL_CONST_SUBTREE`。
- parameter default：不进入 body pipeline，domain 为 `PARAMETER_DEFAULT`。
- 未知或 skipped structure：不发布 inventory，不进入 `SuiteResolver`，domain 为
  `UNKNOWN_OR_SKIPPED_SUBTREE`。

- [ ] enum switch 必须 exhaustive；新增 `BlockScopeKind` / `CallableScopeKind` 时必须显式选择
  policy，不能通过 `default -> EXECUTABLE_BODY` 自动放行。

验收细则：

- `FOR_BODY` 的 policy 是 structural supported；lambda/match 不因 for 解封而改变。
- policy 类型和测试中不存在 `PENDING`、`PUBLISHED`、readiness、route 或 compile-ready 状态。

#### 阶段 L2：增加 structural completeness fail-fast certificate

- [ ] 在 `FrontendSuiteResolver` 进入 root/child body 前，通过
  `requireStructurallyCompleteBody(...)` 或等价 helper 验证：
  - `scopesByAst()` 中 body root 映射到预期 identity / kind 的 `BlockScope`。
  - `FrontendSuiteEntryRoots` 明确包含该 supported body。
  - `FrontendBodyDeclarationIndex.containsBodyRoot(body)` 为 true，即使 body 没有 ordinary local。
  - 每个 index entry 的 declaration/binding/scope identity 一致，且 typed baseline 包含该
    source-facing declaration；`FOR_BODY` 的 iterator `Node` identity 也必须覆盖。
- [ ] certificate 只读取 scope graph、suite entry roots、declaration index 与 baseline；不得读取
  pending/committed overlay、expression type、slot refinement 或 iteration plan。
- [ ] 结构事实缺洞属于 phase protocol / programmer error，必须 fail-fast，不得伪装成源码
  diagnostic，也不得静默跳过 body。

验收细则：

- scope 存在但 suite-entry、body-index、iterator entry 或 baseline 任一缺失时 focused test
  fail-fast。
- header resolved type 变化不会改变 certificate 结果。

#### 阶段 L3：迁移 SuiteResolver 与 body-local consumer

- [ ] `FrontendSuiteResolver`、`FrontendSuiteContext` 与
  `FrontendBodyOwnerProcedures.eligibleInferredLocalScope(...)` 改为消费 structural policy 与
  completeness certificate，不再用 gate readiness 决定 suite entry / ordinary local
  stabilization。
- [ ] `FrontendSuiteContext.visibleValueDomainForCurrentBody()` 对 supported body 直接生成
  `EXECUTABLE_BODY`；unsupported body 根据 policy 返回精确 deferred domain，未知 kind 不得
  fallback 为 `EXECUTABLE_BODY`。
- [ ] 本阶段允许 `FrontendInterfaceSurface` 暂时继续携带 registry 供尚未迁移的 resolver 使用；
  不得为了提前删除字段形成不可编译的中间状态。

#### 阶段 L4：迁移 FrontendVisibleValueResolver 的结构封口

- [ ] 删除 resolver 的 gate registry constructor dependency、request-domain readiness
  normalization、`gateBodyBoundary(...)`、`isNearestOwningGateReady(...)`、
  `nearestOwningGate(...)` 及所有 `SUPPORTED + PUBLISHED` 分支。
- [ ] 保留 request-domain hard boundary、AST boundary 与 current-scope fail-closed。删除的是 typed
  readiness 条件，不是这三类结构检查本身；AST boundary 与 current-scope 两层结构封口都不得
  删除。
- [ ] `ForStatement.body()`、for header edge 与 `FOR_BODY` current scope 直接允许 normal lookup；
  lambda/match/const/parameter-default 根据 structural policy 直接返回 deferred boundary。
- [ ] 保留 declaration-order、initializer self-reference、skipped subtree 以及 visible block-local
  `const` 不得 fallback 到 outer same-name binding 的合同。
- [ ] 同批更新 `FrontendBodyOwnerProcedures` 的 resolver 构造点和 focused resolver tests。

#### 阶段 L5：删除 synthetic statement classifier

- [ ] 删除 `FrontendStatementResolver.OwnerProcedures.runGateClassifier(...)`、
  `resolveSupportedRoot(...)` 中的调用及 synthetic classifier fixtures。
- [ ] 该删除只针对 body-entry classifier。for-range 阶段 D1 后续可以在 header expr typing 后、
  iterator var type post 前新增 concrete `runForIterationPlanning(...)`；不得复用 classifier 名称、
  lifecycle 或 readiness 语义。
- [ ] 真实 feature typed result 只能驱动 refinement、semantic route、type diagnostic 或 lowering
  route，不能决定是否调用 `resolveChildSuite(...)`。

#### 阶段 L6：删除 interface gate producer 与 surface dependency

- [ ] 移除 `FrontendInterfacePhase` 的 `FrontendInventoryGateRegistry.Builder`、
  `addPendingGate(...)`、match registration 及 lambda/match/block-local `const` 调用点。
- [ ] 已转正 `for` 继续使用阶段 B/D0 的 structural path；未转正节点只跳过其 feature-owned
  inventory，并由 structural policy / AST boundary 表达限制。
- [ ] `FrontendInterfaceSurface`、`FrontendSuiteEntryRoots` 的字段、构造器与文档不再产出、持有
  或描述 gate registry。

#### 阶段 L7：迁移测试合同

- [ ] 重写 `FrontendInterfacePhaseTest`、`FrontendSuiteResolverTest`、
  `FrontendVisibleValueResolverTest` 中 pending/published gate、registry 与 classifier fixture。
- [ ] 新增/保留以下结构合同：
  - `FOR_BODY` 在 typed resolution 前已有 iterator、ordinary local、index、baseline 与 suite entry。
  - supported kind 但 certificate 任一事实缺失时 fail-fast。
  - iterable 分别为 exact、`Variant`、error fact 时，body inventory 与 entry 保持不变。
  - nested for 中 lambda/match/const 仍保持 structural deferred。
  - visible block-local `const` 不 fallback；future declaration 与 initializer self-reference filtered
    hit 不退化。
  - for-only compile path 在 CFG 未支持时命中临时 blocker。
- [ ] 删除手工推进 gate lifecycle 的 fixture 与 `FrontendInventoryGateRegistryTest`。

#### 阶段 L8：物理删除 gate lifecycle 类型

- [ ] 仅在 L3-L7 的生产/测试消费者全部清除后，物理删除
  `FrontendInventoryGate`、`FrontendInventoryGateRegistry`、
  `FrontendInventoryGateStatus`、`FrontendBodyInventoryReadiness`。
- [ ] 不保留 compatibility shim、empty registry、反射检查或公开
  `markSupported(...)` / `markBodyInventoryPublished(...)` setter。
- [ ] `src/main/java` 与 `src/test/java` 中不存在上述类型、`runGateClassifier`、
  `markBodyInventoryPublished` 或 `isBodyInventoryReady` 引用。

#### 阶段 L9：同步文档、风险与完成定义

- [ ] 删除或改写本文第 4.5、4.8、阶段 F 中把 `SUPPORTED + PUBLISHED` 作为 body entry 条件的
  历史设计；复核第 6/7/8 节不再保留 readiness registry 不变量。
- [ ] 更新 `frontend_rules.md`：`for` 已进入 shared semantic，但在 route-aware compile gate 落地前
  由临时 blocker 阻断；compile gate 不得把该 blocker 反向变成 semantic body gate。
- [ ] 更新 `frontend_segmented_type_resolution_pipeline_execution_summary.md`、visible-value
  resolver、interface phase 与 for-range 文档，明确 structural policy、completeness certificate、
  semantic body entry 与 compile route readiness 四者分离。

阶段 L 系列完成验收：

- `FrontendInterfaceSurface`、`FrontendSuiteContext`、`FrontendSuiteResolver`、
  `FrontendBodyOwnerProcedures` 与 `FrontendVisibleValueResolver` 均不接收、持有或查询 gate
  registry；不存在带 body root / registry 参数的 readiness API。
- 已转正 body 的 resolver/suite entry 不依赖 typed fact；改变 header expression 的 resolved type
  不会改变 scope inventory、declaration index、completeness certificate 或 child-suite dispatch。
- `for i in values: var local := i` 在 iterable typing 前已有 `i` baseline 与 `local` 完整 inventory；
  两者都由 declaration index 覆盖，body 解析后 `local` 通过既有 owner/export 路径发布 slot type。
- 未转正节点保持 fail-closed，但没有 `PENDING` / `PUBLISHING` / `PUBLISHED` lifecycle；其
  deferred diagnostic/domain 只由 AST/scope 的结构性 unsupported boundary 决定。
- 相关 focused tests 与 `./gradlew classes --no-daemon --info --console=plain` 通过。

## 6. 必须新增或调整的测试

基础设施测试：

- `FrontendAnalysisDataTest`：patch merge 新 key / idempotent / conflict / stable reference。
- `FrontendAnalysisDataTest`：`FrontendOwnerPatch` / per-owner patch merge 时只能携带该 owner 允许的 side table 或 local slot update；跨 owner payload fail-fast。
- `FrontendAnalysisDataTest`：`FrontendLocalTypeStabilizationPatch` 是唯一允许携带 `FrontendLocalSlotTypeUpdate` 的 patch，且不能同时携带独立 `symbolBindings()` delta。
- `FrontendAnalysisDataTest`：`FrontendPatchTransaction` 按 top binding -> local stabilization -> chain binding -> expr typing -> optional feature-specific semantic route planning -> var type post 顺序 apply；乱序或重复 owner patch fail-fast。
- `FrontendAnalysisDataTest`：`expressionTypes()` patch 对同一 stable key 的 same-status different publishedType、status change、Variant-published fact -> exact fact 仍 fail-fast，证明没有 slot-style narrowing 例外。
- `FrontendAnalysisDataTest`：compiler-only type 泄漏 guard 覆盖 `expressionTypes()`、`slotTypes()`、`localSlotTypeUpdates()`。
- `FrontendAnalysisDataTest`：`applyPatch` 拒绝 `symbolBindings()` 中 `resolvedValue.type()` 为 `GdCompilerType` 的 patch entry。
- `FrontendAnalysisDataTest`：`applyPatch` 拒绝 `resolvedMembers()` 中 `receiverType` / `resultType` 为 `GdCompilerType` 的 patch entry。
- `FrontendAnalysisDataTest`：`applyPatch` 拒绝 `resolvedCalls()` 中 `receiverType` / `returnType` / `argumentTypes` / callable boundary parameter types 含 `GdCompilerType` 的 patch entry。
- `FrontendAnalysisDataTest`：shared walker 对 `FrontendBinding` / `FrontendResolvedMember` / `FrontendResolvedCall` / `FrontendExpressionType` / `FrontendLocalSlotTypeUpdate` 的 type-bearing field coverage 有明确 regression tests，防止未来新增字段后 guard 漏扫。
- `FrontendAnalysisDataTest`：若 `updateSymbolBindings()` / `updateResolvedMembers()` / `updateResolvedCalls()` / `updateExpressionTypes()` / `updateSlotTypes()` 仍保留 source-facing publication 语义，它们必须拒绝 compiler-only payload；若不做该保护，则测试与文档必须把这些入口显式限定为 legacy non-production path。
- `FrontendAstSideTableTest`：identity key 语义不变。
- `FrontendTypedLexicalEnvironmentTest`：overlay read order、owner metadata、source-facing compiler-only guard、exact type conflict、export 前 stable 不变。
- `FrontendTypedLexicalEnvironmentTest`：pending overlay 只对当前 statement 后续子过程可见，flush 后才进入 current-suite committed overlay。
- `FrontendTypedLexicalEnvironmentTest`：suite export 前 stable side table 与 `BlockScope` 不变，export 后只通过 patch apply 更新。
- `FrontendTypedLexicalEnvironmentTest`：suite export 生成 per-owner patch transaction，而不是单个 multi-owner patch。
- `FrontendTypedLexicalEnvironmentTest`：`expressionTypes()` overlay 同 key 同值幂等，不同值 fail-fast；retry 中间 fact 不能作为可导出的 expression type fact 留存。
- `FrontendSuiteResolverTest` / `FrontendChainReductionHelperTest`：owner procedure transient caches 与 bounded `finalizeWindow` retry 中的临时 facts 不会进入 pending overlay、committed overlay 或 stable side table；只有 owner 显式 publication 后才进入 typed overlay。
- `FrontendTypedLexicalEnvironmentTest`：overlay 写入拒绝 `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()` 中所有 type-bearing payload 的 `GdCompilerType`；不得用 `FrontendWindowPublicationSurfaceTest` 代替该覆盖。
- `FrontendTypedLexicalEnvironmentTest`：pending write、statement flush、suite export 三个时点对 compiler-only payload 的拒绝集合一致；不能出现 pending 接受、export 才拒绝的 coverage drift。
- `FrontendWindowPublicationSurfaceTest`：保留为 API-level / legacy shim 测试，只验证 surface 自身 direct API 的 scratch / discard / conflict 语义；不得声明所有 `analyzeInWindow(...)` caller 都满足 scratch-over-stable。
- `FrontendVarTypePostAnalyzerTest` 或 dedicated legacy regression：直接调用旧 `analyzeInWindow(...)` 后，即使调用 `window.discard()`，stable `slotTypes()` 已被 clear/write 污染；该测试用于记录旧路径不可作为 overlay 参考，而不是把污染行为转正为新 pipeline 行为。
- `FrontendExprTypeAnalyzerTest` 或 dedicated legacy regression：旧 whole-module flow 中 `updateExpressionTypes(...)` 先于 `applyPatch(...)` 的 whole-table replace 行为只能作为 legacy snapshot path 记录，不得被新 overlay/export 方案复用为“先写 stable 再校验”先例。
- Patch package 迁移测试：`FrontendAnalysisPatch`、`FrontendLocalSlotTypeUpdate` 的引用改为 `gd.script.gdcc.frontend.sema.patch`，旧 package 不保留同名生产类型；`FrontendWindowPublicationSurface` / `FrontendWindowAnalysisContext` 若保留或迁入，也必须标记为 legacy shim，不得被 production overlay 引用。
- Analyzer rewrite inventory test / inspection：五个 owner analyzer 的 production SuiteResolver path 不调用 `analyzeInWindow(...)`，不从 `SourceFile` root 调用内部 `AstWalker...walk(...)`。

Interface phase 测试：

- `FrontendInterfacePhaseTest.buildsSupportedBodyDeclarationIndexTypedBaselineAndSuiteEntryRoots`：构建 `FrontendBodyDeclarationIndex`，完整记录 supported block local declaration source order，同时锚定 typed baseline 与 suite entry roots 不写 stable typed side table。
- `FrontendInterfacePhaseTest.keepsFutureDeclarationVisibleToResolverThroughCompleteBodyIndex`：`var first := second; var second := 1` 仍通过完整 inventory 被 resolver 过滤为 `DECLARATION_AFTER_USE_SITE`。
- `FrontendInterfacePhaseTest.recordsStructuralSupportWithoutTypedBodyGates`：`FOR_BODY` 已进入
  suite entry / declaration index / baseline；lambda、match、block-local `const` 仍不发布其
  feature-owned inventory，且 interface surface 不产出 gate registry。
- `FrontendInterfacePhaseTest.typedLexicalBaselineRejectsCompilerOnlySourceFacingTypes`：source-facing typed baseline 写入 `GdCompilerType` 时 fail-fast。
- `FrontendSemanticAnalyzerFrameworkTest`：skeleton / scope / variable diagnostics snapshot boundary 不漂移。

Suite/body pipeline 测试：

- `FrontendSuiteResolverTest`：source-order statement traversal 与 AST order 一致。
- `FrontendSuiteResolverTest`：fake owner procedure 只收到当前 statement/header/expression root，不能拿到 module `SourceFile` root 重新 whole-module walk。
- `FrontendSuiteResolverTest`：普通 statement 的 owner procedure 顺序固定为 top binding -> local stabilization -> chain binding -> expr typing -> var type post；for-range D1 完成后，`FOR_ITERATION_PLANNING` 只能插在 header expr typing 与 iterator var type post 之间。
- `FrontendSuiteResolverTest`：chain binding 消费 receiver local 时看到 local stabilization 已写入的 exact slot fact。
- `FrontendSuiteResolverTest`：nested chain / argument retry 可读取 owner-local transient facts，但这些 facts 不对其他 owner procedure 或 `TypedLexicalEnvironment` 普通 lookup 可见。
- `FrontendSuiteResolverTest`：retry 后 final result 只写入同一 key 的最终 fact，不会把 earlier intermediate fact 写入 stable 或 committed overlay。
- `FrontendSuiteResolverTest`：suite export 后 stable side table 状态等同于按固定顺序 apply 对应 per-owner patches 的结果。
- `FrontendSuiteResolverTest`：local stabilization patch apply 后由 commit helper 派生刷新同 declaration 的 `symbolBindings()` payload，而不是通过同一个 patch 携带 binding delta。
- `FrontendSuiteResolverTest`：`if` / `elif` / `else` / `while` header 先解析，body 后递归。
- `FrontendSuiteResolverTest`：unsupported body 不进入 resolver。
- `FrontendSemanticAnalyzerFrameworkTest`：默认 interface/body pipeline 发布 body facts、nested source-order facts，`for` 进入 shared semantic，match / lambda / block-local `const` 继续 structural fail-closed；测试不再通过 shared analyzer legacy whole-phase baseline。
- `FrontendExprTypeAnalyzerTest`：owner-specific baseline 只能在测试内显式串联 focused owner analyzers；不得恢复 shared analyzer legacy bridge 或旧 expr-phase slot mutation。

Resolver 测试：

- 同 block future local：`var x := y; var y := 1`，必须得到 `DECLARATION_AFTER_USE_SITE` filtered hit。
- initializer self-reference：`var x := x`，必须得到 `SELF_REFERENCE_IN_INITIALIZER` filtered hit。
- Structural request-domain 测试：supported body request 直接使用 `EXECUTABLE_BODY`；lambda、match、
  block-local `const`、parameter default 与 unknown/skipped request 使用精确 deferred domain，不存在
  readiness normalization。
- AST boundary 测试：`ForStatement.body()` 与 for header edge 不再封口；lambda body、match
  pattern/guard/body、block-local `const` initializer 与 parameter default 继续 structural
  fail-closed。
- Current-scope 测试：真实 `FOR_BODY` current scope 允许 normal lookup；synthetic
  `LAMBDA_BODY`、`MATCH_SECTION_BODY` 与 lambda callable scope 即使缺少 AST edge 也继续
  fail-closed。
- 双重结构封口测试：unsupported feature 同时覆盖 AST boundary 与 current-scope backstop，防止
  只改一处导致 outer binding fallback。
- Structural completeness 测试：supported body 的 scope、suite entry、body index、baseline 或
  iterator identity 任一缺失时 fail-fast，不能返回普通 `NOT_FOUND` 或静默跳过 suite。

Local stabilization 测试：

- source-order alias chain 在 interface/body pipeline 下保持稳定。
- child block 读取 parent 前缀稳定 local。
- exact type 不允许被后续 statement / overlay export 改写为另一个 exact type。
- 同一 statement 内，local stabilization pending slot 对后续 chain binding / expr typing 可见，但不允许 initializer self-reference 借此通过过滤。
- assignment initializer / bare `TYPE_META` / dynamic fallback 保持 `Variant`。
- `FrontendExprTypeAnalyzerTest` 继续覆盖 backfill guard：inventory-seeded `Variant` 不被 expr phase narrowing，已稳定同类型 no-op，已稳定异类型 fail-fast，compiler-only initializer fact fail-fast。

Structural body support 测试：

- structural semantic support matrix 不读取 typed fact、overlay、iteration plan、diagnostic state 或
  compile readiness。
- `FOR_BODY` 是 structural supported；lambda/match/const/parameter default 的 policy 保持精确
  unsupported/deferred domain，新增 scope kind 没有 allow-by-default。
- 前缀 `:=` local 稳定后，后续 for header planning 可以读取 typed fact，但该事实不会改变 body
  inventory、completeness certificate 或 suite entry。
- for iterator 与 ordinary local 都有 declaration-index entry 和 source-facing baseline；缺失任一
  entry 时 fail-fast。
- feature-specific `GdCompilerType` 不出现在 `expressionTypes()` / source-facing `slotTypes()` /
  ordinary `ScopeValue.type()`，也不进入 structural policy/certificate。

Compile gate 测试：

- interface/body facts 中残留 `DEFERRED` / `FAILED` / `UNSUPPORTED` 时仍被 compile gate 阻断。
- for-range route-aware compile policy 尚未落地时，for-only source 由临时 statement-root
  `sema.compile_check` blocker 阻断，不能进入 `FrontendCfgGraphBuilder`。
- upstream diagnostic 去重跨 interface/body phase 生效。
- `analyze(...)` 与 `analyzeForCompile(...)` split 不变。

## 7. 风险与缓解

### R1：side-table 冲突被静默覆盖

缓解：overlay export 必须通过 per-owner patch merge API；默认不同 value fail-fast。需要覆盖 overlay shadow stable、idempotent、conflict tests 与 patch transaction 顺序 tests。这里的 fail-fast 只阻止当前冲突 patch 覆盖既有 value，不意味着 transaction 或 callable batch 失败后无部分提交；非原子失败语义见第 4.3、4.6 节与 R18。

### R2：resolver 看不到未来声明

缓解：interface phase 必须基于基础结构层已发布的 inventory 为 supported suite 建立完整 local declaration index。禁止 resolver 自己扫描普通 `var` 弥补缺口；resolver 通过 scope 的已发布 inventory 完成名称查找，并读取 index 与 structural completeness certificate 验证该命中属于可解析 body。现有 source byte range filter 继续产出 declaration-after-use 与 initializer self-reference provenance；仅有 local declaration `sourceOrder` 不能替代任意 use-site 的位置模型。参见第 3.2 节：完整 inventory 是 resolver filtered-hit 模型的前提，不是为了与 Godot source-order analysis 对立。

### R3：scope slot mutation 与 published binding payload 脱节

缓解：local slot rewrite 通过 `FrontendLocalTypeStabilizationPatch` 携带的 `FrontendLocalSlotTypeUpdate` 统一应用，并由同一个 commit helper 派生刷新同 declaration 的 `symbolBindings()`。禁止 local stabilization patch 同时携带独立 `symbolBindings()` delta。

### R4：历史 backfill 路径恢复第二个 slot mutation owner

缓解：`FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 必须是 strict no-op / guard-only；测试同时锁住“不调用 `BlockScope.resetLocalType(...)`”、“不刷新 `symbolBindings()` payload”、“不生成 `FrontendLocalSlotTypeUpdate`”。需要新的 narrowing 能力时只能扩展 local stabilization。

### R5：structural capability 与实际 inventory completeness 被混为一谈

缓解：immutable structural semantic support matrix 只回答某种 AST/scope kind 的 inventory path
是否已实现；`FrontendSuiteEntryRoots`、`FrontendBodyDeclarationIndex`、typed baseline 与 scope
identity 共同证明本次 interface surface 的 structural completeness。`canPublish...` 或等价
allowlist 不能单独充当 body-entry certificate。缺失结构事实必须 fail-fast，不能静默跳过 body，
也不能重新引入 `PENDING` / `PUBLISHED` lifecycle。

### R6：`SuiteResolver` 绕过 phase owner 边界

缓解：`SuiteResolver` 只编排 owner 子过程，不直接写 owner side table。每个 side table 的写入 API 应保留 owner 意图，`FrontendSuiteContext` 必须校验当前 runner identity 与目标 overlay owner 匹配，per-owner patch 类型也必须编码 semantic owner identity 并在 merge-time 校验，不能复用 `ScopeOwnerKind`，测试覆盖错误 owner 写入 fail-fast 或不可达。

### R7：diagnostics 重复或顺序漂移

缓解：interface phase、body statement、suite export、diagnostics-only phase 都有明确 diagnostics snapshot 边界；新增跨 phase duplicate suppression tests。若顺序需要调整，必须更新 framework probe tests 与文档。

### R8：unsupported subtree 被 structural support matrix 过早打开

缓解：support matrix 对 lambda parameter/capture/body、match pattern/guard/body、block-local
`const` initializer、parameter default 和 unknown/skipped structure 显式返回各自 deferred domain；
新增 scope/AST kind 必须通过 exhaustive mapping 显式选择 policy。AST boundary 与 current-scope
backstop 同时保留，不能依赖 `default -> EXECUTABLE_BODY`。

### R9：compiler-only type 泄漏

缓解：阶段 C3 已通过 `FrontendPublishedFactTypeGuard` shared walker 覆盖 binding/member/call/expression/slot/update 六类 source-facing typed payload，并接入 patch、overlay 与保留的 whole-table publication API。feature-specific compiler state 仍只能通过专用 contract 给 CFG/lowering；新增 type-bearing payload 时必须同步扩展 shared walker 与 regression tests，否则本风险重新打开。`FrontendWindowPublicationSurface` 不能作为 guard 完整性的基线，因为历史 VarTypePost window caller 曾在 surface guard 生效前直接写入 stable `slotTypes()`。

补充：如果保留 `updateXxx(...)` whole-table publish 或可变 stable side-table 引用，它们也必须被纳入同一风险面。只要 production body path 还能通过 `analysisData.xxx().put()/clear()/putAll()`、`updateXxx(...)` whole-table replace 或 window caller 中转直接触达 stable typed table，就不能宣称 overlay export 安全性已经被证明。

### R10：已实施过渡资产继续扩大影响面

缓解：阶段 A 必须冻结资产分类。`FrontendSegmentedSemanticScheduler` 与 `segmentedSemanticRunner` 只能作为迁移测试旁路或删除对象，不能继续承载新功能。

### R11：实现一次性改动过大

缓解：先冻结资产边界，再落地 interface 数据结构、overlay、suite skeleton、owner 子过程、diagnostics 与默认切换；历史 typed-dependent gate 按 L0-L9 的 compile-safe 小阶段迁移并最后删除类型。每个子阶段都应有 targeted tests，单个 patch batch 不超过三个文件。

### R12：statement 内 owner 顺序或 overlay 导出时机漂移

缓解：第 4.3 节的 base owner procedure 顺序不可重排；feature-specific semantic route owner 只能在 expr typing 与对应 var type post 之间插入。第 4.4 节的 pending -> committed -> stable export 时机不可折叠。Statement flush 不写 stable side table，suite export 只能通过按 owner 有序的 patch transaction 更新 stable facts，suite export 后 diagnostics-only phase 才能运行。测试必须覆盖 chain binding 读取 receiver local 时已经看到 local stabilization 的 exact slot fact、for planning 读取 header expr fact，以及 suite export 前后 stable side table / `BlockScope` 状态。

### R13：resolver structural domain、AST edge 与 current scope 漂移

缓解：supported `FOR_BODY` 的 request domain、`ForStatement.body()`/header AST edge 与 current
scope 必须同时允许 ordinary executable lookup；unsupported lambda/match/const/parameter-default
则由同一 structural policy 返回精确 deferred domain。request-domain hard boundary、AST boundary
与 current-scope backstop 仍是三类独立检查，但不再读取 readiness registry。测试必须同时覆盖
supported for 的三处放行与 unsupported feature 的 AST/current-scope 双重封口。

### R14：retry 中间 expression type 被导出导致 patch 冲突

缓解：`expressionTypes()` stable merge 保持严格 `sameExpressionType` 判据，不增加 `Variant -> exact`、parent -> child 或 status upgrade 例外。Chain / argument retry 的中间 expression facts 只能存放在 owner procedure 内部非导出 transient cache；statement pending overlay、current-suite committed overlay 与 `FrontendExprTypePatch.expressionTypes()` 都只能包含每 key 最终单条 fact。测试必须覆盖 same key different value fail-fast，以及 retry 后 stable / committed table 不含 stale intermediate fact。

### R15：single-stage patch 被误用为 multi-owner suite export

缓解：suite export 生产路径不得构造跨 owner `FrontendAnalysisPatch`。旧 `FrontendAnalysisPatch` 迁入 patch package 后只能作为 legacy single-stage patch / 测试兼容层或被拆解；`FrontendPatchTransaction` 必须按固定 owner 顺序 apply per-owner patches，并拒绝乱序、重复 owner 或单一 patch 内跨 owner 的 payload。该 per-patch owner 校验不构成 callable batch 的跨 transaction 预检。

### R16：把 whole-module analyzer 包装成 body runner

缓解：阶段 A 必须完成 walker-state inventory，阶段 D 必须建立不调用 `analyzeInWindow(...)` 的 root-bounded SuiteResolver skeleton，阶段 E 才能接入真实 owner procedure。任何 production SuiteResolver path 若调用 `analyzeInWindow(...)`、内部 `AstWalker...walk(sourceFile)` 或整表 `updateXxx(...)` 来表达 body result，都视为计划违约而非阶段性完成。

### R17：`FrontendWindowPublicationSurface` 被误认为纯 scratch 参考

缓解：`FrontendWindowPublicationSurface` 的 direct API 可以表达 scratch-over-stable，但现有 analyzer caller 已经违反该模型：`FrontendVarTypePostAnalyzer.analyzeInWindow(...)` 直接 clear/write stable `slotTypes()`，再复制到 window scratch。阶段 A 必须把该类型降级为 legacy shim；阶段 C overlay 必须独立实现和验证；阶段 E var type post 重写不得复用旧 window path。任何以 `FrontendWindowPublicationSurfaceTest` 代替 overlay isolation test 的验收都无效。

### R18：ordered transaction / callable batch 被误认为原子提交

当前限制：`FrontendPatchTransaction` 只保证 owner 顺序，`FrontendCallableExportBatch` 只保证延迟到 root callable 返回后按追加顺序 apply。二者都不做整体 prepare、不提供失败回滚；batch 也看不到 queued transaction 之间尚未进入 stable state 的冲突。后续 patch / transaction 失败时，之前已经 apply 的 stable side table、local slot 与 binding payload 会保留。本阶段不修复该 corner case；代码与文档必须持续声明 non-atomic 合同，production path 必须传播 apply 异常并丢弃整个 `FrontendAnalysisData`。未来若引入增量复用、可恢复 diagnostics 或继续分析语义，必须先落地覆盖整个 callable batch 的两阶段 prepare/commit，而不能只给单个 patch 增加预检。

## 8. 完成定义

本计划完成时应满足：

- frontend shared semantic 默认使用 interface/body pipeline，且现有 supported surface 行为等价。
- 过渡用 `FrontendSegmentedSemanticScheduler` 与 `segmentedSemanticRunner` 已删除，或只作为明确隔离的测试辅助存在。
- interface phase 建立完整 local declaration index、typed baseline 与 suite entry roots，但不做 body typed resolution，也不产出 gate registry。
- `SuiteResolver` 按 source order 解析 supported body，并在 child body 前验证 structural completeness certificate；typed refinement 不参与 body-entry 决策。
- `SuiteResolver` 的 base statement owner 顺序固定为 top binding -> local stabilization -> chain binding -> expr typing -> var type post；concrete feature-specific semantic route owner 只能插在 expr typing 与对应 var type post 之间。
- Production SuiteResolver path 不调用 `analyzeInWindow(...)`、不从 module `SourceFile` root 启动内部 `AstWalker...walk(...)`，也不通过整表 `updateXxx(...)` 表达 body typed result。
- 五个 owner analyzer 的 whole-module walker state inventory 已关闭，并已用 statement-local rewrite 替代 production body path；现有 walker 只可作为 legacy comparison path 或删除对象存在。
- typed overlay 能区分 current statement pending facts 与 current suite committed facts，export 前不污染 stable side table 或 `BlockScope`；该标准明确排除旧 `FrontendVarTypePostAnalyzer.analyzeInWindow(...)` 的 stable `slotTypes()` clear/write 模式。
- `FrontendAnalysisData` 支持带 conflict / guard 校验的 per-owner patch merge，并保持 stable reference 合同；这里的安全边界只覆盖当前 patch，不包含跨 patch 原子性。
- Suite export 使用按 top binding -> local stabilization -> chain binding -> expr typing -> optional feature-specific semantic route planning -> var type post 顺序 apply 的 `FrontendPatchTransaction` 或等价机制；生产路径不使用 single-stage `FrontendAnalysisPatch` 承载多 owner facts。该 transaction 与 callable batch 明确为 non-atomic ordered apply，失败后必须丢弃当前 analysis state。
- 所有 production patch 相关类型位于 `gd.script.gdcc.frontend.sema.patch` 包，包括旧 `FrontendAnalysisPatch`、`FrontendLocalSlotTypeUpdate`、新建 per-owner patch 类型、transaction 与 shared merge / guard helper。`FrontendWindowPublicationSurface` / `FrontendWindowAnalysisContext` 若保留或迁入该包，也必须标记为 legacy shim，不能作为 production overlay/export 参考。
- patch commit 与 typed overlay 写入的 compiler-only guard 覆盖所有 user-visible type-bearing publication surfaces：`symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、`expressionTypes()`、`slotTypes()`、`localSlotTypeUpdates()`；guard 完整性不得以 `FrontendWindowPublicationSurface` 行为作为证明。
- shared compiler-only walker 同时用于 patch commit、overlay pending write、overlay flush，以及任何保留的 source-facing whole-table publication API；新增 type-bearing payload 时必须同步更新该 walker 与对应 regression tests。
- production body path 不通过 `FrontendAnalysisData.symbolBindings()/resolvedMembers()/resolvedCalls()/expressionTypes()/slotTypes()` 返回的可变 stable 引用直接 `put()` / `clear()` / `putAll()`，也不通过 `updateXxx(...)` whole-table replace 把 body typed facts 中转到 stable side table 后再校验。
- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 不改写 `BlockScope`、不刷新 `symbolBindings()` payload、不产生 slot update，只保留 guard-only 协议检查。
- supported suite 的完整 local inventory 先于 body typed resolution，production resolver 使用 declaration index 验证 scope local identity，且 declaration-after-use filtered hit 行为不退化。
- local `:=` 的 source-order type stabilization 可被后续 statement 与 feature-specific semantic route planning 消费，但不能改变 structural body entry。
- chain binding 消费 receiver local slot 时，必须看到 local stabilization 已发布到 overlay 的 exact type，而不是 interface baseline `Variant`。
- immutable structural semantic support matrix 是 AST/scope kind 支持面的单一事实源；它不读取 typed fact、compile readiness 或 lifecycle state。
- supported body 只有在 scope identity、suite entry root、body declaration index 与 typed baseline 全部满足 structural completeness certificate 后才进入 `SuiteResolver`；缺洞 fail-fast。
- resolver 的 request-domain hard boundary、AST boundary 与 current-scope backstop 不读取 registry；supported `FOR_BODY` 三处均放行，unsupported lambda/match/const/parameter-default 继续 structural fail-closed。
- for header 与 body edge 可区分：header typed resolution 可读取前缀 facts并驱动后续 iteration planning，但 body entry 已由无条件 structural inventory 与 D0 child-suite path 决定。
- nested chain / argument retry 不会产生 stable `expressionTypes()` narrowing rewrite 或 status upgrade；每个 expression / step key 在 overlay export 与 stable table 中最多有一个最终 fact。
- `applyPatch` 对 `FrontendExprTypePatch.expressionTypes()` 的 conflict 检测保持 status + publishedType + detailReason 严格相等，只有 local slot update 保留 `Variant -> exact` 例外；如未来需要表达受控 expression narrowing，必须新增显式 merge/upgrade 机制，而不是复用当前 republish 路径。
- unsupported subtree 仍通过 structural policy、AST boundary 与 current-scope backstop fail-closed，不能 fallback 或误发布 body facts。
- compile gate、lowering-ready fact 边界和 compiler-only type 隔离不变。
- `doc/analysis/frontend_segmented_type_resolution_pipeline_execution_summary.md` 已根据最终实现同步更新，用作目标架构摘要，并与本完成定义中的 pipeline 层级、owner 顺序、overlay 生命周期、patch/export 合同、compiler-only guard 与 diagnostics / compile gate 边界保持一致。
