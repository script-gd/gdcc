# Frontend Segmented Type Resolution Pipeline Plan

## 1. 文档状态

- 性质：实施计划
- 目标模块：`src/main/java/gd/script/gdcc/frontend/sema/**`
- 直接动机：让 frontend 可以按源码顺序分段发布局部类型事实，使 `var limit := 3; for i in limit:` 这类依赖前缀 typed fact 的语义支持成为可实现目标。
- 非目标：本文不直接实现 `for-range` lowering，不改变 Godot range runtime 语义，不把 `lambda` / `match` / block-local `const` 一并转正。

关联文档：

- `doc/module_impl/frontend/frontend_rules.md`
- `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
- `doc/module_impl/frontend/frontend_local_type_stabilization_implementation.md`
- `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
- `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_lowering_plan.md`
- `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
- `doc/module_impl/frontend/frontend_for_range_loop_implementation_plan.md`
- `doc/analysis/frontend_semantic_analyzer_research_report.md`

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

这条顺序让每个 phase 都能消费前一个 phase 的完整 module 事实，但也造成一个硬时序问题：

- variable inventory 需要在 phase 3 决定某个 subtree 是否可以发布 local inventory。
- 依赖 typed fact 的语义，例如 `for i in limit:` 是否是 int shorthand，需要 phase 5-7 之后才可能知道。
- 如果 phase 3 不发布 `FOR_BODY` inventory，后续 resolver / binding / expr typing 会按 `FOR_SUBTREE` fail-closed。
- 如果 phase 3 盲目发布 `FOR_BODY` inventory，又会把 unsupported `for` body 误纳入普通 executable body。

因此需要一个 source-order segmented pipeline：先为当前 block 做完整 lexical inventory，再按 statement window 逐步发布前缀 typed facts，遇到依赖 typed fact 的 feature gate 时再决定是否解封其子树 inventory。

## 3. 不变量

### 3.1 仍然保留的 phase owner

分段不是允许任意 analyzer 写任意表。owner 边界保持不变：

- `FrontendVariableAnalyzer` 仍拥有 parameter / local inventory publication。
- `FrontendTopBindingAnalyzer` 仍拥有 `symbolBindings()`。
- `FrontendLocalTypeStabilizationAnalyzer` 仍只拥有 local `:=` slot rewrite，不拥有 diagnostics，不发布 `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` / `slotTypes()`。
- `FrontendChainBindingAnalyzer` 仍拥有 `resolvedMembers()` 与 chain-owned `resolvedCalls()`。
- `FrontendExprTypeAnalyzer` 仍拥有 `expressionTypes()` 与 bare-call `resolvedCalls()`。
- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 在 segmented runner 中不得继续作为第二个 slot mutation owner；它必须收紧为严格 no-op / guard-only 路径。
- `FrontendVarTypePostAnalyzer` 仍拥有 `slotTypes()`。
- `FrontendTypeCheckAnalyzer`、`FrontendLoopControlFlowAnalyzer` 与 `FrontendCompileCheckAnalyzer` 仍是 diagnostics-only consumer。

### 3.2 完整 lexical inventory 必须先于分段 resolver

`FrontendVisibleValueResolver.filterInvisibleCurrentLayerHit(...)` 只有在 `Scope.resolveValueHere(...)` 能看到同层 binding 时，才能把后续声明记录为 `DECLARATION_AFTER_USE_SITE` filtered hit。

因此分段方案不得只把“当前 segment 之前的 local”放入 `BlockScope`。正确模型是：

- 对一个已支持的 block，先扫描并发布该 block 的完整 local inventory。
- local `:=` 初始类型仍是 `Variant`。
- 分段只负责逐步稳定类型和发布 use-site facts。
- 若某个 child feature gate 后续转正，例如 supported `for` body，必须先对该 child body 做完整 inventory，再分析 body 内 statement segments。

这保证 `var x := y; var y := 1` 仍能产生 declaration-after-use filtered hit，而不是把 `y` 误判成普通 miss 或外层 fallback。

### 3.3 `FrontendAnalysisData` 稳定引用合同保持不变

现有测试要求 `FrontendAnalysisData.updateXxx(...)` 保留 side table 对象引用，并通过 clear + putAll 清理 stale entry。分段重构不能破坏这个外部合同。

新增增量能力时必须做到：

- 保留现有 `updateXxx(...)` whole-table publication API 和测试。
- 新增 segment patch / merge API，不复用 `updateXxx(...)` 表达部分提交。
- 同一个 side table 的 stable reference 仍不替换。
- 增量 merge 必须能检测冲突，不能静默覆盖不兼容 fact。

### 3.4 skipped / deferred subtree 合同保持不变

- skeleton 发布的 `skippedSubtreeRoots()` 仍是后续 phase 的硬边界。
- unsupported feature boundary 不能降级为 `NOT_FOUND`。
- `DEFERRED` / `BLOCKED` / `DYNAMIC` / `FAILED` / `UNSUPPORTED` status 不能被压扁。
- compile gate 仍只在 `analyzeForCompile(...)` 运行，且只消费最终 published facts。

### 3.5 compiler-only type 隔离

任何分段 patch merge 都必须拒绝将 `GdCompilerType` 写入用户可见 facts：

- `expressionTypes()` 的 `publishedType()`
- source-facing local / parameter / iterator 的 `slotTypes()`
- ordinary local `ScopeValue.type()`，除非该 scope value 明确是 compiler-owned hidden storage，且不会被 resolver 暴露给源码

`GdccForRangeIterType` 只能作为 hidden iterator state contract 被 lowering 消费，不能通过普通 expression typing 或 local slot publication 泄漏。

## 4. 核心设计

### 4.1 两层 pipeline

重构后的 shared semantic pipeline 分成两层。

基础 whole-module 层：

1. skeleton
2. scope
3. baseline variable inventory

基础层的职责是建立全局 class / callable / block scope 图，并对当前无需 typed fact 即可支持的 block 发布完整 local inventory。

分段 semantic 层：

1. top binding segment
2. local type stabilization segment
3. chain binding segment
4. expr typing segment
5. slot type post segment

分段层以源码顺序处理 supported executable block 的 statement window。每个 window 内仍按上述阶段顺序运行，不能把 expr typing 提前到 chain binding 之前，也不能让 local stabilization 越过 top binding。

诊断-only whole-module 层：

1. annotation usage
2. virtual override
3. type check
4. loop control
5. compile-only final gate

这些阶段应在分段 semantic 层完全收敛后运行，继续消费最终 facts。

### 4.2 Segment 的粒度

第一版 segment 粒度定义为 `FrontendSemanticWindow`：

```java
record FrontendSemanticWindow(
        @NotNull Node owner,
        @NotNull Scope currentScope,
        @NotNull List<? extends Node> roots,
        @NotNull FrontendSemanticWindowKind kind
) {}
```

建议的 window kind：

- `PROPERTY_INITIALIZER`
- `CALLABLE_STATEMENT`
- `BLOCK_STATEMENT`
- `CONTROL_HEADER`
- `FEATURE_GATE_HEADER`
- `FEATURE_GATE_BODY`

第一版实现可以先做到“每个 statement 一个 window”，不要在开始时做复杂 batching。性能问题等行为稳定后再通过相邻 safe window 合并优化。

### 4.3 Source-order scheduler

新增 `FrontendSegmentedSemanticScheduler` 或等价 coordinator。它不拥有具体语义，只负责按源码顺序调度 analyzer segment。

调度规则：

1. 对 accepted source file 按 class member 顺序进入。
2. 对 function / constructor body，要求 callable parameters 和 body block baseline inventory 已发布。
3. 对 supported block，先保证该 block 的完整 local inventory 已发布。
4. 按 `block.statements()` 顺序处理 statement window。
5. 每个 statement window 完成 top binding -> local stabilization -> chain binding -> expr typing -> var type post，并把 patch merge 回 `FrontendAnalysisData`。
6. 遇到 `if` / `elif` / `else` / `while` 等已支持 control body 时，header window 先完成，再递归处理 body block。
7. 遇到 typed-dependent feature gate，例如 future supported `for`，先分析 gate header 所需前置 facts，再运行 classifier。
8. classifier 若转正，只能把 gate 状态推进到 `SUPPORTED`；此时 body inventory 仍必须保持 `NOT_PUBLISHED`，resolver / binder 继续 fail-closed。
9. scheduler 随后运行专门的 child body inventory publication window。只有该 window 成功提交 iterator binding 与 body 完整 local inventory 后，才能把 body readiness 推进到 `PUBLISHED`。
10. 只有 `status == SUPPORTED && bodyInventoryReadiness == PUBLISHED` 的 body 可以递归处理 child body semantic windows。
11. classifier 若未转正，保留现有 deferred / unsupported boundary，不进入 child body segment，body readiness 也必须保持 `NOT_PUBLISHED`。

### 4.4 Feature gate

新增 `FrontendInventoryGate` 记录 typed-dependent subtree 的待决状态。第一版至少应能表达：

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

`FrontendVariableAnalyzer` 的 baseline pass 不应在需要 typed fact 的 gate 上做最终决定。它应：

- 对已支持且不依赖 typed fact 的 block 发布完整 local inventory。
- 对 typed-dependent subtree 记录 pending gate。
- 对当前明确不会被本计划解封的 subtree 继续发 unsupported/deferred diagnostic。

后续 `FrontendSegmentedSemanticScheduler` 在 header facts 就绪后调用对应 classifier，并决定是否发布 body inventory。

### 4.4.1 Body inventory readiness

`status == SUPPORTED` 与 `bodyInventoryReadiness == PUBLISHED` 是两件事，不能互相替代：

- `SUPPORTED` 只表示 gate classifier 已确认该 subtree 可以被本轮 pipeline 解封。
- `PUBLISHED` 表示对应 body scope 的 iterator binding 与完整 local inventory 已经通过 publication owner 写入，并且提交点已经成功完成。
- `BlockScopeKind.FOR_BODY` scope 存在不表示 inventory 已发布；`FrontendScopeAnalyzer` 会无条件为 `ForStatement.body()` 建 scope，这只是 lexical graph 事实。
- `bodyInventoryReadiness` 是 gate body inventory readiness 的单一真源。任何 analyzer、resolver、compile gate 或测试 helper 都不得用“scope 存在”、“gate 为 `SUPPORTED`”或“`BlockScopeKind.FOR_BODY`”推导 body 已可解析。
- `PUBLISHING` 只允许表达 scheduler 正在发布 body inventory 的内部过渡状态。对 resolver / binder / downstream semantic windows 来说，它与 `NOT_PUBLISHED` 一样必须 fail-closed。

生命周期必须固定为：

1. baseline pass 记录 typed-dependent gate：`status = PENDING`，`bodyInventoryReadiness = NOT_PUBLISHED`。
2. classifier 判定 unsupported：`status = UNSUPPORTED`，`bodyInventoryReadiness = NOT_PUBLISHED`，保留 deferred / unsupported boundary。
3. classifier 判定 supported：`status = SUPPORTED`，`bodyInventoryReadiness = NOT_PUBLISHED`，此时 body 仍不可解析。
4. body inventory window 开始时可临时进入 `PUBLISHING`，但不得让 resolver 正常 lookup。
5. body inventory window 成功提交后，原子推进为 `PUBLISHED`。
6. body inventory window 失败或被丢弃时，必须回到 `NOT_PUBLISHED` 或直接 fail-fast；不得留下 `SUPPORTED + PUBLISHING` 的稳定 public 状态。

需要提供一个共享查询作为唯一入口，例如 `FrontendExecutableInventorySupport.isCallableLocalValueInventoryReady(BlockScope scope, Node useSite, FrontendAnalysisData data)` 或等价命名。它必须：

- 对无条件支持的 block kind 继续返回 true。
- 对 `FOR_BODY` 这类 gate body，只在能找到 owning gate，且 `status == SUPPORTED && bodyInventoryReadiness == PUBLISHED` 时返回 true。
- 对缺失 gate、`PENDING`、`SUPPORTED + NOT_PUBLISHED`、`SUPPORTED + PUBLISHING`、`UNSUPPORTED`、合成但无 owning `ForStatement` 的 `FOR_BODY` 返回 false。
- 被 `FrontendVariableAnalyzer`、`FrontendVisibleValueResolver`、`FrontendLocalTypeStabilizationAnalyzer`、`FrontendVarTypePostAnalyzer`、`FrontendCompileCheckAnalyzer` 等所有 callable-local inventory 消费者共同使用。

现有 `FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(BlockScopeKind)` 只能继续表达无条件支持的 block kind，不得成为 `FOR_BODY` readiness 的事实源，也不得通过把 `FOR_BODY` 加进 switch 来解封 gate body。

### 4.5 Side-table patch

新增 transient patch 类型，例如 `FrontendAnalysisPatch`：

```java
record FrontendAnalysisPatch(
        @NotNull FrontendSemanticStage stage,
        @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
        @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
        @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
        @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
        @NotNull FrontendAstSideTable<GdType> slotTypes,
        @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates
) {}
```

`FrontendAnalysisData` 新增 `applyPatch(...)` 或分表 merge 方法，规则如下：

- 新 key 直接写入 stable side table。
- 旧 key + 相同 value 视为 idempotent，允许。
- 旧 key + 不同 value 默认 fail-fast。
- `symbolBindings()` 允许因 `FrontendLocalSlotTypeUpdate` 刷新同一 declaration 的 `resolvedValue` payload，但必须保持 binding kind、name、declaration identity 不变。
- `resolvedCalls()` 中 chain-owned call 与 bare-call 由 stage 标识区分，不能相互覆盖。
- `expressionTypes()` 不允许从 `RESOLVED(int)` 变成 `RESOLVED(float)`，也不允许从 terminal negative status 改成 success；需要 retry 的场景不得先发布 provisional public fact。
- `slotTypes()` 不允许同一 source slot 被不同类型覆盖；同类型 idempotent 允许。
- merge 前统一检查 compiler-only type 不泄漏。

现有 `updateXxx(...)` 继续表达 whole-table snapshot publication；`applyPatch(...)` 只用于 segmented semantic layer。

### 4.5.1 Window-local publication surface

`FrontendAnalysisPatch` 只表达 window 结束时提交到 stable side table 的增量，不表达 window 内部的即时可见性。分段实现必须额外引入 window-local publication surface，例如 `FrontendWindowPublicationSurface` 或等价对象。

window-local surface 的核心合同为 scratch-over-stable：

- 每个 `FrontendSemanticWindow` 开始时创建一组空的 scratch side table，与当前 `FrontendAnalysisData` stable side table 组成 effective view。
- window 内所有 stage 读取 semantic fact 时必须通过 effective view：先查 window scratch，再查 stable side table。
- window 内所有新增或刷新事实只写入 scratch，不直接写入 `FrontendAnalysisData` stable side table。
- window 成功完成后，scratch 被封装为 `FrontendAnalysisPatch`，再通过 `applyPatch(...)` 原子合并到 stable side table。
- window 失败或被 classifier 判为 unsupported 时，scratch 直接丢弃，不得污染 stable side table。

这个 surface 不是 public publication。只有 `applyPatch(...)` 成功后，事实才成为后续 window、diagnostics-only 阶段、lowering 可消费的 stable published fact。

`ExprType` 的特殊要求必须明确保留：

- `FrontendExprTypeAnalyzer` 当前依赖读己写语义，nested chain reduction 会读取同一 window 内刚发布的 expression fact。
- 迁移后该读己写只能发生在 window scratch 内，不能通过提前写 stable side table 实现。
- `FrontendChainReductionHelper`、`FrontendChainReductionFacade`、`FrontendExpressionSemanticSupport` 等 shared support 不得直接读取 `analysisData.expressionTypes()` 来判断当前 window 内 fact 是否已存在，必须通过 effective view 或由 window-aware resolver 封装该查找顺序。
- `finalizeWindow=true` 表示“当前 bounded retry window 的最后一次补全尝试”。若补全得到稳定结果，只能写入当前 window scratch；是否成为 public fact 仍由 window 结束时的 `applyPatch(...)` 决定。
- 需要 retry 的路径不得发布 provisional public fact；如果只能得到 `DEFERRED` / `FAILED` / `UNSUPPORTED` 等 terminal 或 unstable 结果，也应先停留在 scratch，按 `expressionTypes()` merge 规则在 window commit 时统一判定。

同一规则也适用于 window 内 stage 间依赖：

- top binding segment 写入的 `symbolBindings()` 必须在同一 window 的 local stabilization、chain binding、expr typing 中可见。
- chain binding segment 写入的 `resolvedMembers()` 与 chain-owned `resolvedCalls()` 必须在同一 window 的 expr typing 中可见。
- expr typing segment 补充的 bare-call `resolvedCalls()` 与 `expressionTypes()` 必须在同一 window 的 var type post 中可见。
- `publishAttributeStepExpressionTypes(...)` 这类 duplicate guard 必须同时检查 scratch 与 stable，允许同值 idempotent，拒绝不同值覆盖。

因此，window-capable analyzer API 不应只接收 `FrontendAnalysisData`。它应接收一个只暴露 effective reads 与 scratch writes 的上下文，例如 `FrontendWindowAnalysisContext`：

```java
record FrontendWindowAnalysisContext(
        @NotNull FrontendAnalysisData stableData,
        @NotNull FrontendWindowPublicationSurface publications
) {}
```

具体命名可调整，但必须避免 analyzer 或 shared helper 绕过 window surface 直接写 stable side table。

### 4.6 Scope slot mutation

`BlockScope.resetLocalType(...)` 不是 side-table 写入，但它会影响后续 resolver 和 binding payload。分段方案必须显式建模这类 mutation。

`FrontendLocalSlotTypeUpdate` 至少记录：

```java
record FrontendLocalSlotTypeUpdate(
        @NotNull BlockScope scope,
        @NotNull String name,
        @NotNull Object declaration,
        @NotNull GdType type
) {}
```

应用规则：

- `FrontendLocalTypeStabilizationAnalyzer` 是唯一允许产生 `FrontendLocalSlotTypeUpdate` 的 analyzer。
- 只允许 `Variant -> exact` 或 exact same-type no-op。
- 不允许 exact A -> exact B。
- 不允许写入 `GdVoidType`。
- 不允许写入 `GdCompilerType` 到 source-facing local。
- 应用后必须刷新已发布且指向同一 declaration 的 `symbolBindings()` payload。
- 刷新应优先使用 declaration identity index，第一版若继续全表扫描也必须被测试锁住语义正确性。

现存 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 是必须显式收口的历史路径。它当前能在 expr phase 中直接调用 `BlockScope.resetLocalType(...)`，并通过 `refreshPublishedLocalValues(...)` 刷新已发布的 `symbolBindings()` payload；这两者都会绕过 `FrontendLocalSlotTypeUpdate` 的 owner 边界。

在 segmented runner 中，该路径只能保留为 guard-only 检查：

- 不得调用 `BlockScope.resetLocalType(...)`。
- 不得调用或复制 `refreshPublishedLocalValues(...)` 的 side-channel binding payload refresh。
- 不得向 window surface 或 patch 追加 `FrontendLocalSlotTypeUpdate`。
- 若 initializer fact 是 terminal negative、`DYNAMIC`、`void`、缺失或当前 slot 仍是 inventory-seeded `Variant`，直接 no-op；expr phase 不能补做 local stabilization 没有完成的 narrowing。
- 若当前 slot 已是非 `Variant` 且 initializer exact type 一致，no-op。
- 若当前 slot 已是非 `Variant` 但 initializer exact type 不一致，fail-fast 暴露内部阶段协议错误。
- 若 initializer fact 试图把 `GdCompilerType` 作为 source-facing local 类型观测到，fail-fast。

如果后续发现某类 initializer 需要更强的 `:=` narrowing，必须扩展 `FrontendLocalTypeStabilizationAnalyzer` 或它的 window-local resolver，而不是恢复 expr-phase backfill mutation。

### 4.7 Resolver 复用规则

`FrontendVisibleValueResolver` 可以继续一次性索引完整 source AST，但它必须读取已经发布的完整 inventory。

重构时不得让 resolver 自己扫描 AST 补找普通 `var`。原因是这样会复制 `FrontendVariableAnalyzer` 的职责，并容易与 duplicate、shadowing、unsupported boundary、scope kind gate 漂移。

需要新增的 resolver 能力是 context-aware inventory readiness 判断：

- 现有 `FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(BlockScopeKind)` 保留给无条件支持的 block kind。
- 新增 AST-aware readiness 查询，例如 `FrontendExecutableInventorySupport.isCallableLocalValueInventoryReady(BlockScope scope, Node useSite, FrontendAnalysisData data)`。
- 对 `FOR_BODY` 这类 gate body，只有共享 readiness 查询返回 true 时才放行；它内部必须同时检查对应 gate `status == SUPPORTED` 与 `bodyInventoryReadiness == PUBLISHED`。
- `FrontendVisibleValueResolver.detectDeferredBoundary(...)` 的 `ForStatement.body()` edge 与 `classifyUnsupportedCurrentBlockScopeBoundary(...)` 的 `FOR_BODY` current-scope check 必须调用同一 readiness 查询，不能一个按 `SUPPORTED` 放行、另一个仍按 `FOR_BODY` 封口。
- `ForStatement.iteratorType()` / `iterable()` 仍属于 gate header 语义，不得因为 body readiness 为 `PUBLISHED` 自动获得 body-local visibility。
- 非 supported 或 readiness 未发布的 gate body 继续返回 `DEFERRED_UNSUPPORTED + FOR_SUBTREE` 或对应 domain。

## 5. 分步骤实施

### 阶段 A：补齐 side-table patch 基础设施

实施内容：

- 新增 `FrontendSemanticStage`，枚举 `TOP_BINDING`、`LOCAL_TYPE_STABILIZATION`、`CHAIN_BINDING`、`EXPR_TYPE`、`VAR_TYPE_POST`。
- 新增 `FrontendAnalysisPatch` 与 `FrontendLocalSlotTypeUpdate`。
- 在 `FrontendAnalysisData` 中新增 patch merge API，保留现有 `updateXxx(...)`。
- 为 merge 加入冲突检测、idempotent 规则、compiler-only type 泄漏检查。
- 为 `symbolBindings()` 的 local slot refresh 建立显式 helper，替代 analyzer 内到处分散的 `entry.setValue(...)`。

当前状态（2026-07-05）：

- [x] A1 新增 `FrontendSemanticStage`，并固定五个 segmented semantic stage 常量。
- [x] A2 新增 `FrontendAnalysisPatch` 与 `FrontendLocalSlotTypeUpdate`，其中 patch 在创建时复制 side table，避免 drain 后被 scratch 二次污染。
- [x] A3 在 `FrontendAnalysisData` 中新增 `applyPatch(...)`，并继续保留现有 `updateXxx(...)` whole-table publication API。
- [x] A4 为 merge 加入冲突检测、idempotent 规则、`LOCAL_TYPE_STABILIZATION` owner 校验，以及 `expressionTypes()` / `slotTypes()` / local slot update 的 compiler-only type 泄漏检查。
- [x] A5 为 `symbolBindings()` 的 local slot refresh 建立显式 helper，并让 `FrontendLocalTypeStabilizationAnalyzer` 与 `FrontendExprTypeAnalyzer` 复用该 helper，保持现有 analyzer 对外行为不变。

验收细则：

- `FrontendAnalysisDataTest` 继续通过现有 stable-reference / stale-clear 测试。
- 新增测试覆盖 patch 写入新 key、idempotent merge、冲突 fail-fast。
- 新增测试覆盖 `symbolBindings()` 仅允许同 declaration 的 local slot payload refresh。
- 新增测试覆盖 local slot payload refresh 只由 `FrontendLocalSlotTypeUpdate` 应用触发；no-op update 与 expr-phase backfill guard 都不得刷新 binding payload。
- 新增测试覆盖 `expressionTypes()` / `slotTypes()` 拒绝 `GdCompilerType` 泄漏。
- 不修改任何 analyzer 行为时，现有 frontend semantic tests 输出不变。

### 阶段 B：抽出 window-local publication surface

实施内容：

- 新增 `FrontendWindowPublicationSurface` 或等价类型，封装每个 window 的 scratch side table。
- 新增 `FrontendWindowAnalysisContext` 或等价类型，统一携带 stable `FrontendAnalysisData` 与 window-local publication surface。
- 为 `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、`expressionTypes()`、`slotTypes()` 提供 scratch-over-stable effective read API。
- 为上述 side table 提供 scratch-only write API；所有 write 都不得直接落到 stable side table。
- 提供 `toPatch(...)` / `drainPatch(...)` 或等价方法，只把 scratch 中由当前 window 产生的 entries 转成 `FrontendAnalysisPatch`。
- 提供 discard 语义：window 未成功完成、classifier 判定 unsupported、或测试主动丢弃 surface 时，scratch 内容不会影响 stable side table。
- 将 `finalizeWindow=true` 的 window 内含义固定为“bounded retry 的最后一次补全尝试，稳定结果写入 scratch”，不得在 surface 层写穿 stable。
- 在 surface 层或 patch merge 层复用同一套 conflict / idempotent / compiler-only type guard，避免 scratch shadow stable 后把冲突延迟成静默覆盖。

验收细则：

- 新增测试覆盖 effective read 顺序：同一 identity key 同时存在 stable 与 scratch 时，读取返回 scratch 值；scratch 缺失时回落 stable。
- 新增测试覆盖 scratch-only write：写入 `expressionTypes()` / `resolvedCalls()` / `symbolBindings()` 等 scratch 表后，`FrontendAnalysisData` stable side table 在 `applyPatch(...)` 前保持不变。
- 新增测试覆盖 `toPatch(...)` 只包含 scratch entries，不把 stable fallback entries 误复制进 patch。
- 新增测试覆盖 window surface 不把 expr-phase `backfillInferredLocalType(...)` 观察到的 initializer type 转换为 `FrontendLocalSlotTypeUpdate`；slot update collector 只接受 local stabilization owner。
- 新增测试覆盖 discard：丢弃 surface 后 stable side table、diagnostics snapshot、scope slot 均不被修改。
- 新增测试覆盖 same-key idempotent：scratch 写入与 stable 相同 value 可通过，最终 `applyPatch(...)` 为 no-op 或 idempotent merge。
- 新增测试覆盖 same-key conflict：scratch 写入或 patch merge 不允许把 stable `RESOLVED(int)` shadow 成 `RESOLVED(float)`，也不允许 terminal negative status shadow 成 success。
- 新增测试覆盖 `finalizeWindow=true`：retry 产出的稳定 `ExprType` 在同一 surface 内可立即读到，但 stable `analysisData.expressionTypes()` 在 commit 前仍读不到。
- 新增测试覆盖 attribute step key：`AttributePropertyStep` / `AttributeCallStep` / `AttributeSubscriptStep` 作为 key 时仍保持 identity lookup、scratch 优先、duplicate guard 生效。
- 新增测试覆盖 compiler-only guard：`GdCompilerType` 不能写入 source-facing scratch `expressionTypes()` / `slotTypes()`，即使还未进入 `applyPatch(...)`。
- 新增测试覆盖 local slot update isolation：surface 收集的 `FrontendLocalSlotTypeUpdate` 在 commit 前不调用 `BlockScope.resetLocalType(...)`，commit 后才按 4.6 规则应用并刷新 binding payload。

### 阶段 C：抽出 window-capable analyzer API

实施内容：

- 为 top binding、chain binding、expr typing、var type post 提取 window-level runner。
- window-level runner 接收 window analysis context，不能直接向 stable side table 发布 facts。
- 现有 whole-module `analyze(...)` 先改成构造一个覆盖全 module 的 window 列表，再调用 window runner，最后用 `updateXxx(...)` 发布 whole-table snapshot。
- local type stabilization 提取 window-level runner，返回 `FrontendLocalSlotTypeUpdate`，由 caller 统一应用。
- 将 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 改造成 4.6 定义的 guard-only 检查，移除它对 `BlockScope.resetLocalType(...)` 与 `refreshPublishedLocalValues(...)` 的生产路径依赖。
- 改造 `FrontendChainReductionHelper` / `FrontendChainReductionFacade` 的 expression type 查找入口，使其通过 window effective view 或 window-aware resolver 读取 `expressionTypes()`。
- 保持现有 analyzer class 名称和 public `analyze(...)` 方法，避免一次性改动所有调用点。

验收细则：

- `FrontendSemanticAnalyzerFrameworkTest.analyzePublishesPhaseBoundariesThroughVirtualOverridePhaseAndRefreshesDiagnosticsAfterEachPhase` 继续通过，证明 public phase boundary 未漂移。
- top binding / chain binding / expr typing / var type post 的 focused tests 输出不变；例外是历史 backfill mutation 测试必须改为 guard-only 语义，不能继续期待 expr phase 改写 slot。
- local type stabilization 的 probe 测试继续证明 probe 不写 shared side tables、不发 final diagnostics。
- expr typing 的 nested chain / argument retry 场景继续保留读己写能力，但读写发生在 window scratch 内。
- expr typing 的 local `:=` backfill 场景只做 guard：inventory-seeded `Variant` 保持不变，已稳定同类型 no-op，已稳定异类型 fail-fast，且不会刷新 `symbolBindings()` payload。
- window runner 在单 window 与 whole-module wrapper 下产出的 side-table 内容一致。

### 阶段 D：引入 segment scheduler，但先保持行为等价

实施内容：

- 新增 `FrontendSegmentedSemanticScheduler`，第一版只生成现有支持面的 statement windows。
- scheduler 运行时仍不解封任何 typed-dependent gate。
- 对每个 window 依次运行 top binding、local type stabilization、chain binding、expr typing、var type post，并用 `applyPatch(...)` 合并。
- 每个 window 创建独立 publication surface；只有 window 成功完成后才把 surface 转为 patch 并合并。
- `FrontendSemanticAnalyzer` 增加内部开关或 package-private 构造路径，用于测试 segmented runner 与 legacy whole-phase runner 的等价性。
- 默认生产路径可以在阶段 D 末切换到 segmented runner，但必须先完成等价测试。

验收细则：

- 对同一输入，legacy whole-phase runner 与 segmented runner 的 `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、`expressionTypes()`、`slotTypes()` 等价。
- 等价基线以阶段 C 后的 guard-only backfill 合同为准，不以旧的 expr-phase slot mutation 作为兼容目标。
- 验证同一 statement window 内 `ExprType` 可读到自己刚发布的 scratch fact，且下一个 window 只能读到已经 merge 的 stable fact。
- diagnostics category、range、顺序保持等价，或文档明确接受的 phase-boundary probe 差异已有新测试锚定。
- `FrontendVisibleValueResolver` 的 declaration-after-use 与 initializer self-reference 测试继续通过。
- `for`、`match`、lambda、block-local `const` 的 existing deferred / unsupported 行为不变。

### 阶段 E：baseline inventory 与 pending gate 分离

实施内容：

- 调整 `FrontendVariableAnalyzer`，把“发布普通 local inventory”和“报告/记录 feature boundary”拆开。
- 对现有无条件支持的 block，仍发布完整 local inventory。
- 对 typed-dependent subtree，记录 `FrontendInventoryGate(PENDING, NOT_PUBLISHED)`，但不发布 body inventory。
- 对明确不在本计划转正范围内的 subtree，继续按现有 owner 发 diagnostic。
- 在 `FrontendAnalysisData` 中加入 gate side table 或专用 registry；若 gate 不需要长期暴露给 lowering，可先保持 package-private data structure，但必须可被 resolver / scheduler 查询。
- gate registry 必须按 gate owner / body root identity 提供 body readiness update 和 lookup API，作为 4.4.1 定义的单一真源。
- `FrontendVariableAnalyzer`、`FrontendLocalTypeStabilizationAnalyzer`、`FrontendVarTypePostAnalyzer`、`FrontendCompileCheckAnalyzer` 的 callable-local inventory 判断必须迁移到共享 readiness 查询；纯 `BlockScopeKind` 查询只能处理无条件支持的 block kind。

验收细则：

- 普通 block 中未来声明仍在 scope 中可被 resolver 看到，并按 `DECLARATION_AFTER_USE_SITE` 过滤。
- pending gate body 内 lookup 仍返回 `DEFERRED_UNSUPPORTED`，不能 fallback 到外层同名 local。
- `SUPPORTED + NOT_PUBLISHED` 与 `SUPPORTED + PUBLISHING` 的 gate body lookup 仍返回 `DEFERRED_UNSUPPORTED`，不能 fallback 到外层同名 local。
- 合成 `FOR_BODY` scope 或缺失 owning gate 的 body readiness 查询返回 false，即使 `scopesByAst()` 中已有 scope 记录。
- 旧的 unsupported `for` / `match` / lambda tests 继续通过，除非某个 gate 在后续阶段显式转正。
- duplicate / shadowing diagnostics owner 不变。

### 阶段 F：source-order typed fact 解锁

实施内容：

- scheduler 在每个 block 内按源码顺序提交 statement patches。
- 当前 statement 的 expr facts 提交后，后续 statement classifier 可以读取这些 facts。
- 对 `var limit := 3; <typed-dependent gate uses limit>` 建立测试用 synthetic gate 或选用 `for` range classifier 作为第一个真实 consumer。
- local `:=` stabilization 必须在同一 statement window 内早于后续 statement 的 binding / classifier 消费。
- child block 的完整 inventory 必须在 child body 第一个 semantic window 前发布，且共享 readiness 查询必须已经返回 true。

验收细则：

- `var limit := 3; var next := limit` 在 segmented runner 下仍稳定为 `int`。
- `var x := y; var y := 1` 仍不把 `y` 解析成可见 local；filtered hit reason 为 `DECLARATION_AFTER_USE_SITE`。
- `var x := x` 仍记录 `SELF_REFERENCE_IN_INITIALIZER`。
- 一个 statement 的 published `expressionTypes()` 能被后续 gate classifier 读取。
- 不允许同一 expression key 被后续 window 重新发布成不同状态或类型。

### 阶段 G：接入第一个真实 typed-dependent feature gate

建议以 `frontend_for_range_loop_implementation_plan.md` 中的 int shorthand `for` 作为验收用例，但只接语义解封，不在本阶段强制完成 lowering。

实施内容：

- 将 `ForStatement` 注册为 typed-dependent inventory gate。
- `range(...)` call 仍可由 AST shape 早期识别；`INT_SHORTHAND` 必须等 iterable expression typed fact 就绪后再判定。
- `var limit := 3; for i in limit:` 中，scheduler 先完成 `limit` statement window，再分类 `for` gate。
- supported gate 先推进为 `SUPPORTED + NOT_PUBLISHED`，再由 body inventory publication window 发布 iterator binding 和 body 完整 local inventory，最后原子推进为 `PUBLISHED`。
- unsupported gate 继续保留 `FOR_SUBTREE` deferred / unsupported 行为。

验收细则：

- `for i in range(3):` 的 body lookup 可以看到 `i : int`。
- classifier 已返回 supported 但 body readiness 仍为 `NOT_PUBLISHED` / `PUBLISHING` 时，body lookup 仍必须是 `DEFERRED_UNSUPPORTED + FOR_SUBTREE`。
- `var limit := 3; for i in limit:` 在 `limit` 已稳定为 `int` 后可被 classifier 判定为 int shorthand。
- `for i in limit: var local := i` 中 body local inventory 在 body 分段前完整发布。
- `for i in limit: var local := i` 只有在 body readiness 为 `PUBLISHED` 后，resolver、local stabilization、var type post、compile check 才能把 `FOR_BODY` 当作 ready inventory domain。
- `for i in values:` 仍不会让 body 内 bare identifier fallback 到外层并制造误导 binding。
- `GdccForRangeIterType` 不出现在 `expressionTypes()` 或 source-facing `slotTypes()`。

### 阶段 H：收敛 diagnostics 与 compile gate

实施内容：

- 确定 segmented runner 的 diagnostics snapshot 发布点：每个 stage patch 应用后刷新 `analysisData.updateDiagnostics(...)`，保证后续 stage 看到稳定 upstream diagnostics。
- compile gate 继续只在 shared segmented pipeline 完成后运行。
- 检查 compile gate 的 duplicate suppression 是否仍能识别跨 segment upstream diagnostics。
- 对缺失 `slotTypes()`、`DEFERRED`、`FAILED`、`UNSUPPORTED` 的 final facts 保持现有 compile blocking 规则。

验收细则：

- `FrontendCompileCheckAnalyzerTest` 中已有 compile gate 去重测试继续通过。
- upstream diagnostic 已存在时，下游 segment 不补同级重复错误。
- `analyze(...)` 不运行 compile gate；`analyzeForCompile(...)` 在 segmented facts 完成后运行 compile gate。
- segmented runner 不改变 parse / skeleton / scope diagnostics 的顺序与可见性。

### 阶段 I：移除 legacy whole-phase 旁路

实施内容：

- 等阶段 D-H 的等价与新增支持测试稳定后，删除仅用于迁移的 legacy whole-phase runner 旁路。
- 保留 window-capable analyzer API，whole-module analyzer wrapper 只作为测试或调试入口时存在。
- 更新相关文档，尤其是 variable analyzer、visible resolver、local type stabilization、chain/expr typing、compile check 与 for-range plan。

验收细则：

- 所有 frontend semantic focused tests 通过。
- 针对新增 segmented pipeline 的测试覆盖 patch merge、window-local surface、backfill guard-only、source-order typed fact、pending gate、resolver filtered hit、diagnostic dedup、compile gate。
- `./gradlew classes --no-daemon --info --console=plain` 通过。
- 相关 targeted tests 使用 `script/run-gradle-targeted-tests.sh --tests ...` 通过。

## 6. 必须新增或调整的测试

基础设施测试：

- `FrontendAnalysisDataTest`：patch merge 新 key / idempotent / conflict / stable reference。
- `FrontendAnalysisDataTest`：compiler-only type 泄漏 guard。
- `FrontendWindowPublicationSurfaceTest` 或等价测试：scratch-over-stable read、scratch-only write、discard、`toPatch(...)` 不复制 stable fallback。
- `FrontendWindowPublicationSurfaceTest` 或等价测试：`finalizeWindow=true` 产物只进入 scratch，commit 前 stable 不可见。
- `FrontendAstSideTableTest`：若新增 patch view 或 owner metadata，确认 identity key 语义不变。

Pipeline 等价测试：

- `FrontendSemanticAnalyzerFrameworkTest`：legacy runner 与 segmented runner side-table 等价。
- `FrontendSemanticAnalyzerFrameworkTest`：等价基线使用 guard-only backfill 合同，不允许旧 expr-phase slot mutation 作为兼容路径回归。
- `FrontendSemanticAnalyzerFrameworkTest`：phase diagnostics snapshot 在 segmented stage 后仍稳定。

Resolver 测试：

- 同 block future local：`var x := y; var y := 1`，必须得到 `DECLARATION_AFTER_USE_SITE` filtered hit。
- initializer self-reference：`var x := x`，必须得到 `SELF_REFERENCE_IN_INITIALIZER` filtered hit。
- pending gate body：lookup 必须是 `DEFERRED_UNSUPPORTED`，不能 fallback。
- `FrontendVisibleValueResolverTest`：`FOR_BODY` scope 已存在但 gate 缺失、`PENDING`、`SUPPORTED + NOT_PUBLISHED`、`SUPPORTED + PUBLISHING`、`UNSUPPORTED` 时，body lookup 都必须是 `DEFERRED_UNSUPPORTED + FOR_SUBTREE`。
- `FrontendVisibleValueResolverTest`：同一 `FOR_BODY` 在 `SUPPORTED + PUBLISHED` 后，body 内 iterator 和 body local lookup 返回 `FOUND_ALLOWED`，并继续保留 declaration-after-use filtered hit。

Local stabilization 测试：

- source-order alias chain 在 segmented runner 下保持稳定。
- child block 读取 parent 前缀稳定 local。
- exact type 不允许被后续 segment 改写为另一个 exact type。
- `FrontendExprTypeAnalyzerTest` 中旧 backfill mutation / refresh 期望必须调整为 guard-only：inventory-seeded `Variant` 不被 expr phase narrowing，`symbolBindings()` payload 不刷新。
- `FrontendExprTypeAnalyzerTest` 继续覆盖 guard：已稳定同类型 no-op，已稳定异类型 fail-fast，compiler-only initializer fact fail-fast。

Typed-dependent gate 测试：

- 前缀 `:=` local 稳定后，后续 gate classifier 能读取 typed fact。
- gate 转正只产生 `SUPPORTED + NOT_PUBLISHED`，不得使 body resolver / binder 放行。
- body inventory publication window 成功提交后，readiness 原子推进为 `PUBLISHED`，未来声明仍能被 filtered hit 捕获。
- `FrontendExecutableInventorySupport` 或等价 readiness 测试：所有 callable-local inventory 消费者对 `FOR_BODY` 使用同一 readiness 查询，不允许各自直接判断 `BlockScopeKind.FOR_BODY`。
- gate 未转正时旧 deferred / unsupported 行为保持。
- `FrontendVariableAnalyzerTest` / `FrontendLocalTypeStabilizationAnalyzerTest` / `FrontendVarTypePostAnalyzerTest` / `FrontendCompileCheckAnalyzerTest`：`SUPPORTED + NOT_PUBLISHED` 的 `FOR_BODY` 不发布 local、slot type 或 lowering-ready fact；`SUPPORTED + PUBLISHED` 后才发布。

Compile gate 测试：

- segmented facts 中残留 `DEFERRED` / `FAILED` / `UNSUPPORTED` 时仍被 compile gate 阻断。
- upstream diagnostic 去重跨 segment 生效。
- `analyze(...)` 与 `analyzeForCompile(...)` split 不变。

## 7. 风险与缓解

### R1：side-table 冲突被静默覆盖

缓解：window 内 partial publication 只能写入 window-local scratch，window 结束后必须通过 patch merge；默认不同 value fail-fast。需要覆盖 scratch shadow stable、idempotent 与 conflict tests。

### R2：resolver 看不到未来声明

缓解：分段前必须为 supported block 发布完整 local inventory。禁止 resolver 自己扫描普通 `var` 弥补缺口。

### R3：scope slot mutation 与 published binding payload 脱节

缓解：local slot rewrite 通过 `FrontendLocalSlotTypeUpdate` 统一应用，并同步刷新同 declaration 的 `symbolBindings()`。

### R4：历史 backfill 路径恢复第二个 slot mutation owner

缓解：`FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 必须是 strict no-op / guard-only；测试同时锁住“不调用 `BlockScope.resetLocalType(...)`”、“不刷新 `symbolBindings()` payload”、“不生成 `FrontendLocalSlotTypeUpdate`”。需要新的 narrowing 能力时只能扩展 local stabilization。

### R5：gate supported 与 body inventory readiness 漂移

缓解：`bodyInventoryReadiness` 是唯一可查询事实；`SUPPORTED` 只是 classifier 结果。resolver、variable analyzer、local stabilization、var type post、compile check 都必须通过共享 readiness 查询，测试覆盖 `SUPPORTED + NOT_PUBLISHED` 与 `SUPPORTED + PUBLISHING` 继续 fail-closed。

### R6：diagnostics 重复或顺序漂移

缓解：stage patch 应用后刷新 diagnostics snapshot；新增跨 segment duplicate suppression tests。若顺序需要调整，必须更新 framework probe tests 与文档。

### R7：unsupported subtree 被过早打开

缓解：pending gate 默认 fail-closed；只有 classifier 明确返回 supported 且 `bodyInventoryReadiness == PUBLISHED`，resolver 才能把对应 body 当普通 executable body。

### R8：compiler-only type 泄漏

缓解：window-local surface、patch merge 与 local slot update 都做 `GdCompilerType` guard；for iterator state 只能通过专用 contract 给 CFG/lowering。

### R9：实现一次性改动过大

缓解：先做 patch infra，再独立落地 window-local surface，再做 window runner 等价改造，之后切 scheduler，最后接 typed-dependent gate。每阶段都应有 targeted tests。

## 8. 完成定义

本计划完成时应满足：

- frontend shared semantic 默认使用 segmented runner，且现有 supported surface 行为等价。
- `FrontendAnalysisData` 同时支持 whole-table publication 与安全 partial patch merge。
- window 内 semantic fact 通过 scratch-over-stable effective view 即时可见，且 commit 前不会污染 stable side table。
- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 不再改写 `BlockScope`、不刷新 `symbolBindings()` payload、不产生 slot update，只保留 guard-only 协议检查。
- supported block 的完整 local inventory 先于分段 resolver，declaration-after-use filtered hit 行为不退化。
- local `:=` 的 source-order type stabilization 可被后续 statement / gate classifier 消费。
- pending feature gate 能在 typed fact 就绪后安全转正，但 child body 只有在 `bodyInventoryReadiness == PUBLISHED` 后才可解析。
- `FOR_BODY` body inventory readiness 由单一 registry/query 表达；scope 存在或 `SUPPORTED` 状态都不能被当作 readiness 替代品。
- unsupported gate 仍 fail-closed，不能 fallback 或误发布 body facts。
- compile gate、lowering-ready fact 边界和 compiler-only type 隔离不变。
