# Frontend Resolution Pipeline 实现说明

> 本文档是 frontend shared semantic resolution pipeline 的长期事实源，定义 interface/body 双层 pipeline 的架构合同、不变量、核心设计与风险边界。不再保留阶段流水账或已完成任务日志。

## 文档状态

- 状态：已完成，事实源维护中
- 更新时间：2026-07-23
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/scope/**`
  - `src/test/java/gd/script/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_local_type_stabilization_implementation.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_plan.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_for_range_loop_implementation.md`
  - `doc/analysis/frontend_segmented_type_resolution_pipeline_execution_summary.md`
- 明确非目标：
  - 不在这里定义 `for-range` lowering 或 Godot range runtime 语义
  - 不在这里转正 `match` / block-local `const`；已记录 `lambda` 的合同见 `frontend_lambda_implementation.md`
  - 不在这里定义 backend codegen 或 LIR intrinsic 合同

---

## 1. 背景与动机

原 `FrontendSemanticAnalyzer.analyze(...)` 是 whole-module phase pipeline（skeleton → scope → variable → top binding → local stabilization → chain binding → expr typing → var type post → diagnostics-only phases → compile gate）。该顺序让每个 phase 消费前一个 phase 的完整 module 事实，但无法自然表达 Godot 的 body 解析模型：

- Godot analyzer 在 `resolve_body()` 中进入 `resolve_suite()`，按源码顺序逐个 statement 调用 `resolve_node(...)`。
- `var x := expr` 的类型稳定、initializer expression reduce、assignment compatibility check 都在同一个 source-order 语句解析链中完成。
- `for i in iterable:` 先 reduce iterable expression，再决定 iterator 类型，最后才解析 loop body suite。

原 statement-window segmented runner 方案试图在现有 phase pipeline 上模拟 source-order，但暴露出结构性阻塞：`analyzeInWindow(...)` 只改变发布表面不限制遍历范围；`FrontendVarTypePostAnalyzer.analyzeInWindow(...)` 直接 clear/write stable `slotTypes()` 再复制到 window scratch，破坏 scratch-over-stable 承诺。

因此 pipeline 改为 interface/body 双层结构：

- Interface 层基于基础结构层已发布的 lexical inventory 建立 declaration index、signature/interface facts 与 typed baseline。
- Body 层用 `FrontendSuiteResolver` 按源码顺序解析 supported body statements。
- `FrontendTypedLexicalEnvironment` overlay 在 body 层提供"当前语句已知 typed fact 对后续语义立即可见"能力，同时保留 side-table owner、patch conflict 与 compiler-only type 隔离。
- Suite 收敛后的 stable export 采用按 owner 有序的 patch transaction。

---

## 2. 架构决定：无条件结构性 inventory 与禁止 typed-dependent body gate

本决定优先于本文后续章节中所有与之冲突的旧 gate 设计，也约束所有后续新增或更新的 frontend feature 实施文档。

- 所有待支持的 AST 节点必须在 body typed resolution 前无条件建立完整的结构性 lexical inventory。该 inventory 包括 body `BlockScope`、parameter、iterator、ordinary local 等 source-facing binding、`FrontendBodyDeclarationIndex` entry 与 typed baseline；不得等待 header 或 initializer 的 typed fact，也不得把 inventory publication 作为 typed fact 的副作用。
- 类型事实只能用于 source-facing type refinement、semantic route 分类和 lowering / compile readiness。它们不得决定 body inventory 是否发布，也不得决定 `SuiteResolver` 或 `FrontendVisibleValueResolver` 是否进入一个已支持 body。
- 后续 feature 不得新增 typed-dependent body entry gate。一个 AST 节点在其结构性 inventory path 尚未实现前可以整体保持 unsupported / deferred；一旦转正，其 body 必须无条件进入 shared semantic，不能通过 `PENDING`、`SUPPORTED`、`PUBLISHED` 或等价 typed readiness 状态延迟解封。
- 新 feature 的实施顺序固定为：scope graph → 完整 lexical inventory → declaration index / baseline → `SuiteResolver` body entry → typed resolution → type refinement / lowering route。compile gate 的 route readiness 属于 lowering 边界，不是 semantic body entry gate。

生产代码不存在 registry、readiness fallback、pending-gate 注册或 synthetic classifier；后续 feature 不得恢复等价 lifecycle 或 publication protocol。

---

## 3. 不变量

### 3.1 Phase owner 边界

Owner 边界是语义合同：

- `FrontendVariableAnalyzer` 拥有 parameter / ordinary local inventory publication。
- `FrontendTopBindingAnalyzer` 拥有 `symbolBindings()`。
- `FrontendLocalTypeStabilizationAnalyzer` 只拥有 source-facing local `:=` slot rewrite，不拥有 diagnostics，不发布 `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` / `slotTypes()`。
- `FrontendChainBindingAnalyzer` 拥有 `resolvedMembers()` 与 chain-owned `resolvedCalls()`。
- `FrontendExprTypeAnalyzer` 拥有 `expressionTypes()` 与 bare-call `resolvedCalls()`。
- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 不得恢复为第二个 slot mutation owner；必须保持 strict no-op / guard-only。
- `FrontendVarTypePostAnalyzer` 拥有 `slotTypes()`。
- `FrontendTypeCheckAnalyzer`、`FrontendLoopControlFlowAnalyzer`、`FrontendCompileCheckAnalyzer` 是 diagnostics-only consumer。

SuiteResolver 下的 owner 子过程是 statement-local procedure：由外层 statement dispatcher 提供 suite context、block context、restriction/static/property initializer context 与 typed lexical environment，而不是让每个 analyzer 自己从 module root walk。

### 3.2 完整 lexical inventory 先于 body typed resolution

GDCC 的完整 lexical inventory 要求来自自己的 resolver filtered-hit 模型。`BlockScope.resolveValueHere(...)` 对当前层只做无 source-order 过滤的 map lookup；`FrontendVisibleValueResolver.resolve(...)` 在拿到当前层 hit 后，再由 `filterInvisibleCurrentLayerHit(...)` 按 source byte order 过滤。

因此 `Scope.resolveValueHere(...)` 必须能看到同层 future declaration：

- declaration 已结束于 use-site 前：直接可见。
- declaration 位于 use-site 后：记录 `DECLARATION_AFTER_USE_SITE` filtered hit。
- use-site 位于同一 declaration initializer 内：记录 `SELF_REFERENCE_IN_INITIALIZER` filtered hit。

若把 local inventory 裁剪成只包含当前 statement 前缀，resolver 就看不到 future declaration，`var x := y; var y := 1` 会被误判为普通 miss 或外层 fallback，并丢失 declaration-after-use provenance。

正确模型：

- 基础结构层沿用 `FrontendVariableAnalyzer` + scope graph 发布完整 ordinary local inventory；interface 层建立 body declaration index / typed baseline view。
- local `:=` 初始类型仍是 `Variant`。
- body `SuiteResolver` 只负责按源码顺序稳定类型并发布 use-site facts；不得通过 typed fact 决定 child body 的 inventory 或 entry readiness。
- 若某个 child feature 后续转正，必须在 interface/inventory 阶段无条件发布该 child body 的结构性 binding 与完整 local inventory，再解析 body suite。

### 3.3 `FrontendAnalysisData` 稳定引用合同

- 保留 `updateXxx(...)` whole-table publication API 和测试。
- 保留 `applyPatch(...)` 或等价 merge API 表达部分提交，但其输入必须是单一 owner patch。
- 同一个 side table 的 stable reference 不替换。
- 增量 merge 必须检测冲突，不能静默覆盖不兼容 fact。
- `TypedLexicalEnvironment` 的 overlay fact 只有在 owner 合法、冲突校验和 compiler-only guard 通过后，才能封装为对应 owner patch 并导出到 stable side table。
- 所有 production patch carrier、patch transaction 与 local slot update carrier 都位于 `gd.script.gdcc.frontend.sema.patch` 包。

### 3.4 skipped / deferred subtree 合同

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

Feature-specific `GdCompilerType` 只能作为 hidden compiler state contract 被对应 feature 的 lowering 消费。

统一 guard 合同：

- patch commit、overlay pending write、overlay flush、任何仍保留的 source-facing `updateXxx(...)` whole-table publish，都必须复用同一套 type-bearing field walker（`FrontendPublishedFactTypeGuard`）。
- walker 至少递归访问 `FrontendBinding.resolvedValue().type()`、`FrontendResolvedMember.receiverType()` / `resultType()`、`FrontendResolvedCall.receiverType()` / `returnType()` / `argumentTypes()` / exact callable boundary parameter types、`FrontendExpressionType.publishedType()`、`slotTypes()` value、`FrontendLocalSlotTypeUpdate.type()`。
- compiler-only guard 必须在 pending overlay write 时就 fail-fast，而不是等 suite export 时才补救。

### 3.6 Diagnostics owner 与去重合同

- interface phase 与 body phase 都必须通过既有 `DiagnosticManager` 发布 diagnostics。
- upstream 已有同 anchor error 时，下游 analyzer 不能补同级重复错误。
- `sema.binding` / `sema.member_resolution` / `sema.expression_resolution` / `sema.type_check` / `sema.compile_check` 的 owner 边界不能漂移。

### 3.7 已实施资产可回退但不能静默改变语义

任何代码回退必须保持：

- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` guard-only 合同。
- `FrontendAnalysisData` stable reference 合同。
- patch merge 的冲突检测与 compiler-only guard。
- unsupported / deferred subtree fail-closed 合同。

---

## 4. 核心设计

### 4.1 三层 pipeline

Shared semantic pipeline 分成三层加一个 diagnostics-only 层。

基础结构层：

1. skeleton
2. scope graph
3. baseline inventory

职责是建立 `FrontendModuleSkeleton`、`scopesByAst()`、callable parameter inventory、supported ordinary local inventory，以及 skipped/deferred subtree 的硬边界。不做 body expression typing。

Interface 层：

1. class / callable / property signature interface
2. per-callable body declaration index
3. source-order local typed baseline

借鉴 Godot `resolve_interface()` 与 `resolve_body()` 之间的边界：不直接 lowering body，也不发布 compile-ready body facts，但必须准备 body `SuiteResolver` 所需的 typed lexical baseline。

Body 层：

1. `SuiteResolver` 按源码顺序进入 supported body suite
2. statement resolver 驱动 top binding / local stabilization / chain binding / expr typing / slot post 的 owner 子过程
3. `TypedLexicalEnvironment` 为当前 statement 和当前 suite 提供 effective typed lookup
4. body suite 收敛后导出 stable side tables

诊断-only 层在 body facts 完全收敛后运行：

1. annotation usage
2. virtual override
3. type check
4. loop control
5. compile-only final gate

### 4.2 Interface phase

`FrontendInterfacePhase` 在 skeleton/scope/variable analyzer 之后建立 body 解析所需的 interface surface。

输入：

- `FrontendModuleSkeleton`
- `scopesByAst()`
- baseline parameter / ordinary local inventory
- current diagnostics snapshot
- `ClassRegistry`

输出：

- `FrontendBodyDeclarationIndex`：每个 supported block 的完整 declaration 列表与 source order；production resolver 用它验证 scope local 的 published-inventory identity。
- `FrontendTypedLexicalBaseline`：参数、显式 typed local、已可静态确定的 interface-level source-facing slot baseline；production `TypedLexicalEnvironment` 将其作为冻结 fallback。
- `FrontendSuiteEntryRoots`：body layer 可进入的 callable/property initializer/supported block 根列表。

禁止：

- 发布 `expressionTypes()`、`resolvedMembers()` / `resolvedCalls()`。
- 将已转正 AST 节点的 body inventory 延迟到 typed fact 可用后。
- 将 `GdCompilerType` 写入 source-facing lexical baseline。

### 4.3 Body `SuiteResolver`

`FrontendSuiteResolver` 按 Godot `resolve_suite()` 的形状处理 body：

```text
resolveCallableOwner(context, callableOwner):
  exportBatch = new FrontendCallableExportBatch()
  context = newSuiteContext(callableOwner, exportBatch)
  runCallableEntryVarTypePost(context, callableOwner)
  resolveSuite(context, callableOwner.body)
  exportBatch.applyTo(context.analysisData())

resolveSuite(context, block):
  for statement in block.statements():
      resolveStatement(context, statement)
  context.exportBatch().accumulate(context.typedEnvironment().exportPatchTransaction())
```

参数是 callable-entry `VAR_TYPE_POST` facts，在进入 statement 循环前写入 pending overlay 并 flush 到 current-suite committed overlay。

`resolveStatement(...)` 内部的 owner 子过程顺序是硬不变量：

0. Callable-entry var type post pre-publication（仅 callable 入口）。
1. Top binding runner。
2. Local stabilization runner。
3. Chain binding runner。
4. Expr typing runner。
5. Feature-specific semantic route planning（仅在该 statement kind 已有 concrete owner 时运行）。
6. Var type post procedure。
7. Pending fact flush。

不得重排 1-6，且 feature-specific stage 只能插在 expr typing 与对应 var type post 之间。

Suite 收敛后，committed overlay 构造为按 owner 有序的 patch transaction。嵌套 suite 只把 transaction 追加到同一 callable-scoped export batch，根 callable suite 收敛后才由 batch 按追加顺序 apply。Transaction / batch 不表达原子提交；失败后当前 `FrontendAnalysisData` 必须整体丢弃。

第一版 body statement 支持面：

- `VariableDeclaration`（ordinary local `var` 与 supported property initializer）
- `ExpressionStatement`、`ReturnStatement`、`AssertStatement`
- `IfStatement` / `ElifClause` / `else`
- `WhileStatement`
- `ForStatement`（structural supported，header-first，body 通过 child-suite path 进入）
- `MatchStatement`、block-local `const` 继续 deferred / unsupported
- 已记录 `LambdaExpression`（supported executable body 内）已转正：nested suite resolution 发布 `FrontendLambdaPlan`；未记录 lambda 继续 deferred / unsupported

### 4.4 `TypedLexicalEnvironment` overlay

`FrontendTypedLexicalEnvironment` 是 body 层所有 value/type lookup 的 effective view，包装 `Scope` 与当前 suite 的 typed overlay。

读取顺序：

1. 当前 statement pending overlay
2. 当前 suite committed overlay
3. 已发布 stable slot facts
4. parent typed lexical environment
5. interface typed baseline 冻结 source-facing fallback
6. class/global/singleton/type-meta lookup

四层事实可见性模型：

- Owner procedure transient cache：owner 子过程私有，结束即丢弃。
- 当前 statement pending overlay：只给当前 statement 后续 owner 子过程读取。
- current-suite committed overlay：由 pending fact flush 合并而来，给后续 statement 读取；仍不是 stable publication。
- `FrontendAnalysisData` stable side tables / `BlockScope` stable slot：只在 suite export 的 per-owner patch apply 后更新。

写入规则：

- 每个 owner 只能写自己对应的 overlay。
- overlay fact 必须带 owner metadata，并在导出前执行冲突检测、idempotent 检查和 compiler-only guard。
- `expressionTypes()` overlay 只接受每个 AST key 的最终 publication fact。retry 中间计算留在 owner-local transient cache。
- `expressionTypes()` overlay 不提供 `Variant → exact`、parent → child 或 terminal status → success 的 narrowing 例外。

写入与导出时机：

- Statement owner runner 只能写当前 statement pending overlay。
- `flushPendingFacts(...)` 把 pending overlay 合并到 current-suite committed overlay。
- Suite 收敛时，committed overlay 导出为按 owner 有序的 patch transaction。
- 嵌套 suite 的 transaction 追加到 root callable 共享的 `FrontendCallableExportBatch`。
- Stable side table 与 `BlockScope.resetLocalType(...)` 只能在 callable export batch 的 per-owner patch apply 中更新。
- Diagnostics-only phase、compile gate 与 lowering 只能读取 suite export 后的 stable facts。

### 4.5 Structural support 与 completeness certificate

Body entry 由两个互不替代的结构事实决定：

- `FrontendBodySemanticSupportPolicy` 只按 AST/scope kind 回答某类位置是否发布 lexical inventory、是否进入 `FrontendSuiteResolver`，以及 unsupported 位置使用哪个精确 deferred domain。
- `FrontendBodyStructuralCompleteness` 对本次 Interface surface 做 fail-fast 验证：scope identity、suite entry、body declaration index、declaration/binding/scope identity 与 source-facing typed baseline 必须同时完整；完整性对 published body inventory 是双向的。

Policy 不读取 expression type、typed overlay、iteration plan、diagnostic state 或 compile surface；certificate 不读取 pending/committed overlay、slot refinement 或 semantic/lowering route。

### 4.6 Stable export 与 per-owner patch transaction

`gd.script.gdcc.frontend.sema.patch` 包承载 patch 相关类型：

- `FrontendOwnerPatch`（sealed interface）：`FrontendTopBindingPatch`、`FrontendLocalTypeStabilizationPatch`、`FrontendChainBindingPatch`、`FrontendExprTypePatch`、`FrontendVarTypePostPatch`。
- `FrontendPatchTransaction`：按固定 owner 顺序 apply per-owner patches。
- `FrontendCallableExportBatch`：累积单个 callable root 及其 nested suite 的 transaction，root suite 收敛后按追加顺序逐个 apply。
- `FrontendLocalSlotTypeUpdate`：local stabilization owner 的专用 carrier。

Per-owner patch merge 规则：

- 新 key 直接写入。
- 旧 key + 相同 value 视为 idempotent。
- 旧 key + 不同 value 默认 fail-fast。
- `expressionTypes()` 同 key republish 只有 `sameExpressionType(...)` 判定为同值时允许（status + publishedType + detailReason 严格相等）。
- `slotTypes()` 不允许同一 source slot 被不同类型覆盖。
- merge 前统一检查 compiler-only type 不泄漏。

### 4.7 Scope slot mutation

`FrontendLocalSlotTypeUpdate` 应用规则：

- `FrontendLocalTypeStabilizationAnalyzer` 是唯一允许产生 source-facing slot update 的 analyzer（ordinary `var :=`）。
- `FOR_ITERATION_RESOLUTION` 另有 for-iterator 精化 carrier（同一 `FrontendLocalSlotTypeUpdate` 类型，独立 list）。
- 只允许 `Variant → exact` 或 exact same-type no-op。
- 不允许 exact A → exact B、`GdVoidType`、`GdCompilerType`。
- 应用后必须刷新已发布且指向同一 declaration 的 `symbolBindings()` payload。
- **For-iterator**：update 的 `scope` 必须是 `scopesByAst[forStatement.body()]` 的 **`FOR_BODY` 对象身份**；读路径经 `owningScopeForDeclaration` 对齐。详见 `scope_analyzer_implementation.md` §6.1。

`FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 必须保持 guard-only：不调用 `BlockScope.resetLocalType(...)`、不刷新 `symbolBindings()` payload、不产生 `FrontendLocalSlotTypeUpdate`。

### 4.8 Resolver 复用规则

`FrontendVisibleValueResolver` 继续一次性索引完整 source AST，继续读取已发布的完整 lexical inventory。

三道结构检查：

1. Request-domain hard boundary：`EXECUTABLE_BODY` 才进入 ordinary lookup。
2. AST boundary：parameter default、match pattern/guard/body 与 block-local `const` initializer 直接返回 structural deferred boundary；`ForStatement.body()`、iterator type 与 iterable edge 不封口。已记录 lambda 的 AST 边不再封口。
3. Current-scope backstop：`MATCH_SECTION_BODY` 继续 fail-closed；`FOR_BODY` 与已记录 lambda 的 `LAMBDA_BODY` / `LAMBDA_EXPRESSION` 是 supported executable scope。未记录 lambda 保持 fail-closed。

Overlay 不得绕过 resolver filter：resolver 先按 request-domain gate、AST boundary、current-scope gate、declaration order 与 initializer self-reference 过滤候选 declaration，再从 `TypedLexicalEnvironment` 读取该候选的 effective type / binding payload。

---

## 5. 风险与缓解

### R1：side-table 冲突被静默覆盖

overlay export 必须通过 per-owner patch merge API；默认不同 value fail-fast。fail-fast 只阻止当前冲突 patch 覆盖既有 value，不意味着 transaction 或 callable batch 失败后无部分提交。

### R2：resolver 看不到未来声明

interface phase 必须基于基础结构层已发布的 inventory 为 supported suite 建立完整 local declaration index。禁止 resolver 自己扫描普通 `var` 弥补缺口。

### R3：scope slot mutation 与 published binding payload 脱节

local slot rewrite 通过 `FrontendLocalSlotTypeUpdate` 统一应用，并由同一个 commit helper 派生刷新同 declaration 的 `symbolBindings()`。

### R4：历史 backfill 路径恢复第二个 slot mutation owner

`backfillInferredLocalType(...)` 必须是 strict no-op / guard-only。需要新的 narrowing 能力时只能扩展 local stabilization。

### R5：structural capability 与实际 inventory completeness 被混为一谈

immutable structural support matrix 只回答某种 AST/scope kind 的 inventory path 是否已实现；completeness certificate 对 published body inventory 做双向校验。缺失结构事实必须 fail-fast，不能静默跳过 body，也不能重新引入 `PENDING` / `PUBLISHED` lifecycle。

### R6：`SuiteResolver` 绕过 phase owner 边界

`SuiteResolver` 只编排 owner 子过程，不直接写 owner side table。`FrontendSuiteContext` 校验当前 runner identity 与目标 overlay owner 匹配。

### R7：diagnostics 重复或顺序漂移

interface phase、body statement、suite export、diagnostics-only phase 都有明确 diagnostics snapshot 边界。

### R8：unsupported subtree 被过早打开

support matrix 对 match、block-local `const`、parameter default 和 unknown/skipped structure 显式返回各自 deferred domain；已记录 lambda 走 `EXECUTABLE_BODY` policy。新增 scope/AST kind 必须通过 exhaustive mapping 显式选择 policy。

### R9：compiler-only type 泄漏

`FrontendPublishedFactTypeGuard` shared walker 覆盖 binding/member/call/expression/slot/update 六类 source-facing typed payload，并接入 patch、overlay 与保留的 whole-table publication API。新增 type-bearing payload 时必须同步扩展 shared walker 与 regression tests。

### R10：statement 内 owner 顺序或 overlay 导出时机漂移

base owner procedure 顺序不可重排；pending → committed → stable export 时机不可折叠。

### R11：resolver structural domain、AST edge 与 current scope 漂移

supported `FOR_BODY` 的 request domain、AST edge 与 current scope 必须同时允许 ordinary executable lookup；unsupported feature 由同一 structural policy 返回精确 deferred domain。

### R12：retry 中间 expression type 被导出导致 patch 冲突

chain / argument retry 的中间 facts 只能存放在 owner procedure 内部非导出 transient cache；overlay 与 stable table 都只能包含每 key 最终单条 fact。

### R13：single-stage patch 被误用为 multi-owner suite export

suite export 生产路径不得构造跨 owner `FrontendAnalysisPatch`。`FrontendPatchTransaction` 按固定 owner 顺序 apply per-owner patches，拒绝乱序、重复 owner 或单一 patch 内跨 owner payload。

### R14：ordered transaction / callable batch 被误认为原子提交

`FrontendPatchTransaction` 只保证 owner 顺序，`FrontendCallableExportBatch` 只保证延迟到 root callable 返回后按追加顺序 apply。二者都不做整体 prepare、不提供失败回滚。production path 必须传播 apply 异常并丢弃整个 `FrontendAnalysisData`。

---

## 6. 核心不变量清单

- frontend shared semantic 默认使用 interface/body pipeline。
- interface phase 建立完整 local declaration index、typed baseline 与 suite entry roots，但不做 body typed resolution。
- `SuiteResolver` 按 source order 解析 supported body，并在 child body 前验证 structural completeness certificate；typed refinement 不参与 body-entry 决策。
- base statement owner 顺序固定为 top binding → local stabilization → chain binding → expr typing → var type post；feature-specific semantic route owner 只能插在 expr typing 与对应 var type post 之间。
- Production SuiteResolver path 不调用 `analyzeInWindow(...)`、不从 module `SourceFile` root 启动内部 walker，也不通过整表 `updateXxx(...)` 表达 body typed result。
- typed overlay 区分 current statement pending facts 与 current suite committed facts，export 前不污染 stable side table 或 `BlockScope`。
- `FrontendAnalysisData` 支持带 conflict / guard 校验的 per-owner patch merge，并保持 stable reference 合同。
- Suite export 使用按固定 owner 顺序 apply 的 `FrontendPatchTransaction`；生产路径不使用 single-stage `FrontendAnalysisPatch` 承载多 owner facts。
- 所有 production patch 相关类型位于 `gd.script.gdcc.frontend.sema.patch` 包。
- compiler-only guard 覆盖所有 user-visible type-bearing publication surfaces，由 shared walker 统一执行。
- production body path 不通过可变 stable 引用直接 `put()` / `clear()` / `putAll()`。
- `backfillInferredLocalType(...)` 保持 guard-only。
- 完整 local inventory 先于 body typed resolution，declaration-after-use filtered hit 行为不退化。
- chain binding 消费 receiver local slot 时，必须看到 local stabilization 已发布到 overlay 的 exact type。
- immutable structural support matrix 是 AST/scope kind 支持面的单一事实源；不读取 typed fact、compile readiness 或 lifecycle state。
- resolver 的三道结构检查不读取 registry；supported `FOR_BODY` 三处均放行，unsupported feature 继续 structural fail-closed。
- nested chain / argument retry 不产生 stable `expressionTypes()` narrowing rewrite；每个 key 最多一个最终 fact。
- unsupported subtree 通过 structural policy、AST boundary 与 current-scope backstop fail-closed。
- compile gate、lowering-ready fact 边界和 compiler-only type 隔离不变。
