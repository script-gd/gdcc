# Frontend Segmented Type Resolution Pipeline Execution Summary

本文总结 `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md` 所述前端分析流水线的目标架构形态。内容只描述目标架构、执行顺序与不变量，不展开旧 whole-module 流水线或过渡实现资产。

## 1. 总体形态

计划完成后，frontend shared semantic pipeline 分为四个层次：

1. 基础结构层：建立 module skeleton、scope graph 与 baseline inventory。
2. Interface 层：建立 body 解析所需的 declaration index、typed lexical baseline 与 suite entry roots，并通过不可变 structural policy 描述支持面。
3. Body 层：通过 `SuiteResolver` 按源码顺序解析 supported body suite，并使用 typed overlay 表达前缀事实可见性。
4. Diagnostics-only 层：在 body facts 完全收敛并导出为 stable facts 后运行 annotation usage、virtual override、type check、loop control 与 compile-only final gate。

核心目标是把 body typed resolution 改为 source-order suite 解析：当前 statement 产生的 typed facts 可以被同一 statement 的后续 owner 子过程和后续 statement 读取，但在 suite export 之前不会污染 `FrontendAnalysisData` stable side tables 或 `BlockScope` stable slot。

## 2. 基础结构层

基础结构层负责建立后续所有语义阶段共享的不可或缺结构：

- `FrontendModuleSkeleton`。
- `scopesByAst()`。
- callable parameter inventory。
- supported parameter、iterator 与 ordinary local inventory。
- skipped / deferred subtree 的硬边界。

这一层不做 body expression typing，也不发布 `resolvedMembers()`、`resolvedCalls()`、`expressionTypes()` 或最终 `slotTypes()`。它只保证后续 resolver 能基于完整 lexical inventory 做 declaration-order 与 self-reference 过滤。

完整 inventory 是 resolver filtered-hit 模型的前提。即使 body typed resolution 按 source order 运行，普通 local declaration 的 inventory 仍必须先完整发布，不能让 resolver 在 use-site 时临时扫描 AST 补找声明。

## 3. Interface 层

Interface 层在基础结构层之后运行，负责准备 body `SuiteResolver` 需要的 interface surface。

输入包括：

- `FrontendModuleSkeleton`。
- `scopesByAst()`。
- baseline parameter / ordinary local inventory。
- 当前 diagnostics snapshot。
- `ClassRegistry`。

输出包括：

- `FrontendBodyDeclarationIndex`：记录每个 supported block 的完整 body-local declaration 列表与 source order；ordinary `var` 使用 `VariableDeclaration` identity，for iterator 使用 owning `ForStatement` identity。对 `FOR_BODY`，inventory 契约是恰好一个 iterator entry 位于列表头部且 `sourceOrder == 0`，ordinary body local 仅能使用连续 `sourceOrder >= 1`。生产 resolver 用 declaration identity 验证 scope 命中的 local 属于已发布 inventory，但不替代 declaration-order / self-reference filtered-hit。
- `FrontendTypedLexicalBaseline`：记录参数、显式 typed local 与 interface 层可静态确定的 source-facing slot baseline；`TypedLexicalEnvironment` 在 overlay、已发布 slot fact 与 parent environment 都没有事实时读取该冻结 fallback。
- `FrontendSuiteEntryRoots`：列出 body layer 可进入的 callable、property initializer 与 supported block roots。

`FrontendBodySemanticSupportPolicy` 是 AST/scope kind 支持面的唯一事实源；它不读取 typed fact、diagnostic 或 compile state。`FrontendBodyStructuralCompleteness` 在 SuiteResolver 进入 root/child body 前验证 scope identity、suite entry、declaration index 与 typed baseline 完整；完整性对 published body inventory 是双向的（index entry ↔ body `BlockScope` 中每条 `LOCAL`），`sourceOrder` 必须与 AST start-byte 序一致，且 `FOR_BODY` inventory 必须恰好包含一个位于列表头部、`sourceOrder == 0` 的 iterator entry（Interface 层先发布 iterator 再 walk body 的形状）。这两个合同分别表达“该结构受支持”和“本次 interface surface 确实完整”，不能合并为 typed-dependent readiness。

Interface 层不得发布 body typed facts。特别是不得发布 `expressionTypes()`、`resolvedMembers()` 或 `resolvedCalls()`，也不得把 `GdCompilerType` 写入 source-facing lexical baseline。

## 4. Body `SuiteResolver`

Body 层由 `FrontendSuiteResolver` 或等价 coordinator 驱动。它按源码顺序进入 supported suite，形态如下：

```text
resolveCallableOwner(context, callableOwner):
  exportBatch = new FrontendCallableExportBatch()
  context = newSuiteContext(callableOwner, exportBatch)
  requireStructurallyCompleteBody(callableOwner.body)
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

参数是 callable-entry `VAR_TYPE_POST` facts，不是 statement root。因此预发布是与
statement-local var type post procedure 互补的正规步骤，而不是绕过或例外：两条路径都使用
同一 owner stage、typed overlay 与 callable-scoped export batch。参数 facts 在进入 statement
循环前 flush 到 committed overlay，使第一个 statement 可读取它们，同时 stable side table
仍保持不变。

每个 nested suite 收敛时只把自己的 per-owner patch transaction 追加到同一 callable-scoped
export batch，不得直接 apply stable side table。根 callable suite 及其所有 nested suite 完成后，
batch 才按追加顺序 apply；child pending/committed overlay 不合并到 parent overlay。

这里的 transaction / batch 只保证顺序与 apply 时机，不提供原子提交。Batch 不在 apply 前预检 queued transaction 之间的冲突；隔离的 child / sibling overlay 也看不到彼此已经导出但尚未进入 stable state 的 fact。因此跨 transaction 的重复、不兼容 publication 可能直到后一个 transaction apply 时才 fail-fast，此前已提交的 patch / transaction 不回滚。当前实现明确保留这一 corner case；apply 异常后必须丢弃整个 `FrontendAnalysisData`，不能继续消费其中的 stable facts。

`resolveStatement(...)` 不是新的 semantic owner。它只按 statement 结构编排已有 owner 的 body-aware 子过程：top binding、local stabilization、chain binding、expr typing 与 var type post。

Body procedure 必须是 root-bounded、statement-local 的实现。每个 owner 子过程只处理 `SuiteResolver` 传入的 statement、header 或 expression root 及其允许的子表达式。实现可以复用纯语义 helper，但 owner 子过程不能依赖 whole-module traversal 建立隐式上下文。

生产 `SuiteResolver` 将 interface surface 的 `FrontendBodyDeclarationIndex` 传给 `FrontendVisibleValueResolver`，以确认 scope 选中的 ordinary local 已在 interface phase inventory 中发布；名称查找仍由 scope 完成，source byte range 仍负责 declaration-order 与 initializer self-reference filter。它同时将 `FrontendTypedLexicalBaseline` 传给每个 `FrontendTypedLexicalEnvironment`，使尚未被 body overlay 或 stable publication 覆盖的 source-facing slot 能读取 interface-level 初始类型。

第一版 body statement 支持面包括：

- ordinary local `VariableDeclaration`。
- supported property initializer。
- `ExpressionStatement`。
- `ReturnStatement`。
- `AssertStatement`。
- `IfStatement` / `ElifClause` / `else`。
- `WhileStatement`。
- `ForStatement`：header-only statement boundary 完成后，通过普通 `resolveChildSuite(...)` 进入结构完整的 `FOR_BODY`。

`MatchStatement`、`LambdaExpression` 与 block-local `const` 仍保持结构性 deferred / unsupported；它们不创建 lifecycle state，也不能因 typed result 改变 body entry。

## 5. Statement 内 Owner 顺序

每个 statement 内的 owner 子过程顺序是硬不变量：

1. Top binding runner。
2. Local stabilization runner。
3. Chain binding runner。
4. Expr typing runner。
5. Var type post procedure。
6. Pending fact flush。

在上述 statement-local 顺序之前，callable-entry var type post 会为每个参数发布
`VAR_TYPE_POST` slot fact 并 flush 到 committed overlay。它不属于第 1-6 步的 statement
procedure，但使用相同的 owner stage 和发布协议。

Top binding runner 为当前 statement 内的 bare identifier 与 chain head use-site 写入 binding overlay。

Local stabilization runner 在 top binding overlay、current-suite committed typed facts 与 stable lexical inventory 之上解析 eligible `:=` initializer，并写入当前 statement 的 local slot pending overlay。

Chain binding runner 消费 top binding overlay 与 local stabilization pending / committed slot fact，发布 `resolvedMembers()` 与 chain-owned `resolvedCalls()` overlay。

Expr typing runner 消费 binding、member、call 与 local slot overlay，发布 `expressionTypes()` 与 bare-call `resolvedCalls()` overlay。`backfillInferredLocalType(...)` 在目标架构中仍只保留 guard-only 协议检查。

Var type post procedure 消费 expression type 与 source-facing local slot overlay，发布 final `slotTypes()` overlay。

Pending fact flush 把当前 statement pending overlay 转入 current-suite committed overlay，供后续 statement 读取。Flush 不得写入 stable side table 或 stable `BlockScope` slot。

这个顺序不能重排。尤其 chain binding 读取 receiver local slot 时，必须先看到 local stabilization 对前序 statement 或当前 statement 前序子过程写入的 exact slot fact。

## 6. `TypedLexicalEnvironment`

`FrontendTypedLexicalEnvironment` 是 body 层所有 value/type lookup 的 effective view。它包装 `Scope` 与 suite-local typed overlay，但不替换 `Scope` 本身。

读取顺序固定为：

1. 当前 statement pending overlay。
2. 当前 suite committed overlay。
3. 已发布 stable slot facts。
4. parent typed lexical environment。
5. `FrontendTypedLexicalBaseline` 提供的冻结 source-facing fallback；`BlockScope` / `CallableScope` 仍负责 lexical inventory 名称查找。
6. class / global / singleton / type-meta lookup。

Pending overlay 只对当前 statement 后续 owner 子过程可见。`flushPendingFacts()` 后，pending facts 才进入 current-suite committed overlay，并对后续 statement 可见。Committed overlay 仍不是 stable publication。

`TypedLexicalEnvironment` 的目标是模拟 source-order body resolution 中“前缀 statement 已解析出的类型可被后续 statement 使用”的行为，同时避免提前污染 stable semantic facts。

Parent environment 只能读取 parent 链上的 overlay，不能读取 child environment 的 pending 或
committed facts。Child facts 只能通过 callable-scoped export batch 在 root callable 完成后进入
stable side tables。

## 7. Fact 生命周期

目标架构固定四层事实可见性模型：

1. Owner procedure transient cache：当前由 `BodyExpressionResolver` 的 expression / finalized-expression / call caches、`FrontendChainReductionFacade.reducedChains` 与 helper bounded retry 承担；只给当前 chain / expr reduction 的 retry 回调读取，owner 子过程结束即丢弃。
2. Current statement pending overlay：当前 statement 后续 owner 子过程可读，只接受每个 AST key 的最终 publication fact。
3. Current-suite committed overlay：由 pending fact flush 合并而来，后续 statement 可读，但仍不是 stable publication。
4. `FrontendAnalysisData` stable side tables / `BlockScope` stable slot：只在 root callable export batch 的 per-owner patch apply / stable export helper 后更新。

Nested chain / argument retry 的中间事实只能存在于 owner-local transient cache 中。Retry 中出现的临时 `DEFERRED`、暂定 `Variant`、中间 status 或 detailReason 不得写入 pending overlay、committed overlay 或 stable side table。

`expressionTypes()` 对同一 key 只能发布最终 fact 一次。如果一个 expression 在 reduction 过程中需要先得到临时 fact 再得到 exact result，中间状态必须留在 owner-local transient cache 或专用非导出状态中。

## 8. Overlay 写入、Flush 与 Export

Statement owner runner 只能写当前 statement pending overlay；callable-entry var type post 在 statement 循环前写参数 pending overlay。Overlay write 必须携带 owner metadata，并在写入时执行 owner、conflict、idempotent、exact-type 与 compiler-only guard。

`flushPendingFacts(...)` 只把 pending overlay 合并到 current-suite committed overlay。Flush 必须复用 suite export 使用的同一个 type-bearing field walker，不能在 scratch 层接受 export 层会拒绝的 payload。

Suite 收敛后，current-suite committed overlay 只能导出为按 owner 有序的 patch transaction，不能导出为一个跨 owner patch。Nested suite transaction 必须追加到 root callable 的 `FrontendCallableExportBatch`，不得在 child suite 边界独立 apply。Stable side table 与 `BlockScope.resetLocalType(...)` 只能在 root callable batch 的 per-owner patch apply 或 stable export helper 中更新；该集中 apply 只定义时机和顺序，不构成原子 commit boundary。

Diagnostics-only phase、compile gate 与 lowering 只能读取 suite export 后的 stable facts。

## 9. Per-owner Patch Transaction

目标架构使用 `gd.script.gdcc.frontend.sema.patch` 包承载 patch 相关类型。核心类型包括：

- `FrontendOwnerPatch` 或等价 sealed interface。
- `FrontendTopBindingPatch`。
- `FrontendLocalTypeStabilizationPatch`。
- `FrontendChainBindingPatch`。
- `FrontendExprTypePatch`。
- `FrontendVarTypePostPatch`。
- `FrontendPatchTransaction`。
- `FrontendCallableExportBatch`。
- `FrontendLocalSlotTypeUpdate`。

每个 per-owner patch 只能携带该 owner 允许发布的 payload：

- `FrontendTopBindingPatch`：`symbolBindings()` delta。
- `FrontendLocalTypeStabilizationPatch`：`FrontendLocalSlotTypeUpdate` delta。
- `FrontendChainBindingPatch`：`resolvedMembers()` + chain-owned `resolvedCalls()` delta。
- `FrontendExprTypePatch`：`expressionTypes()` + bare-call `resolvedCalls()` delta。
- `FrontendVarTypePostPatch`：`slotTypes()` delta。

`FrontendPatchTransaction` 按固定 owner 顺序 apply：top binding -> local stabilization -> chain binding -> expr typing -> var type post。`Transaction` 在这里不表示原子提交；后续 patch 失败时，先前 patch 已产生的 stable mutation 不回滚。

`FrontendCallableExportBatch` 按 suite 收敛顺序保存 root callable 与 nested suite 的 transaction，且仅在 root callable suite 返回后依次 apply。它不预检 queued transaction 之间的冲突，也不提供跨 transaction 原子性或回滚。Property initializer 是独立 root，不加入 callable-scoped batch。

Merge 规则如下：

- 新 key 直接写入 stable side table。
- 旧 key + 相同 value 允许，视为 idempotent。
- 旧 key + 不同 value 默认 fail-fast。
- `symbolBindings()` 允许由 local slot commit helper 派生刷新同 declaration 的 resolved value payload。
- `FrontendLocalTypeStabilizationPatch` 本身不得携带独立 `symbolBindings()` delta。
- `resolvedCalls()` 中 chain-owned call 与 bare-call 由 semantic owner patch 与 key-space contract 区分，不能相互覆盖。
- `expressionTypes()` 同 key republish 只有 status、publishedType 与 detailReason 全等时允许。
- `expressionTypes()` 不允许 `Variant -> exact`、parent -> child、status upgrade 或 detailReason change。
- `slotTypes()` 不允许同一 source slot 被不同类型覆盖，同类型 no-op 允许。

这些 merge 规则只保证当前 per-owner patch 的 conflict check。Fail-fast 不撤销同一 transaction 内更早的 patch，也不撤销 callable batch 内更早的 transaction；跨 queued transaction 的冲突盲区是当前已知且暂不修复的限制。

## 10. Scope Slot Mutation

`BlockScope.resetLocalType(...)` 不是 side-table 写入，但它影响后续 resolver 与 published binding payload。目标架构把这类 mutation 建模为 owner-controlled slot update。

`FrontendLocalSlotTypeUpdate` 至少记录：

- `BlockScope scope`。
- `String name`。
- `Object declaration`。
- `GdType type`。

应用规则如下：

- 只有 local stabilization owner 可以产生 source-facing local slot update。
- 只允许 `Variant -> exact` 或 exact same-type no-op。
- 不允许 exact A -> exact B。
- 不允许写入 `GdVoidType`。
- 不允许写入 `GdCompilerType`。
- 应用后必须刷新已发布且指向同一 declaration 的 `symbolBindings()` payload。

Overlay 可在 export 前提供 effective type，但最终 stable `BlockScope.resetLocalType(...)` 与 binding payload refresh 必须通过同一个 commit helper 执行，避免出现第二条 slot mutation side channel。

## 11. Compiler-only Guard

任何 source-facing typed publication surface 都不得泄漏 `GdCompilerType`。目标架构要求使用同一个 shared type-bearing field walker，覆盖 overlay pending write、statement flush、suite export 与任何保留 source-facing publication 语义的 whole-table publication API。

Shared walker 至少覆盖：

- `FrontendBinding.resolvedValue().type()`。
- `FrontendResolvedMember.receiverType()`。
- `FrontendResolvedMember.resultType()`。
- `FrontendResolvedCall.receiverType()`。
- `FrontendResolvedCall.returnType()`。
- `FrontendResolvedCall.argumentTypes()`。
- `FrontendResolvedCall.ExactCallableBoundary.fixedParameterTypes()`。
- `FrontendExpressionType.publishedType()`。
- `slotTypes()` value。
- `FrontendLocalSlotTypeUpdate.type()`。

Guard 必须在 pending overlay write 时 fail-fast，不能等 suite export 时再补救。只要一个 fact 命中 compiler-only payload，write API 就必须拒绝该 fact，不能先写入 pending / committed overlay 再回滚。

## 12. Resolver 与结构边界

`FrontendVisibleValueResolver` 继续依赖完整 source inventory 与 declaration-order filter。重构后的 resolver 能读取 `TypedLexicalEnvironment` effective view，但不能让 overlay 绕过 resolver filter。

基本规则如下：

- Resolver 先按 request domain、AST boundary、current scope、declaration order 与 initializer self-reference 过滤候选 declaration。
- 过滤通过后，才从 `TypedLexicalEnvironment` 读取该候选的 effective type 或 binding payload。
- 当前 statement pending slot fact 可被同一 statement 后续 owner 子过程消费。
- pending slot fact 不能让 `var x := x` 的右侧 `x` 绕过 self-reference 过滤。
- committed fact 可被后续 statement 消费。
- future declaration 仍必须报告 `DECLARATION_AFTER_USE_SITE` filtered hit。

Resolver 保留三类彼此独立的结构检查：

1. Request-domain hard boundary：只有 `EXECUTABLE_BODY` 进入 ordinary lookup。
2. AST boundary：parameter default、lambda、match 与 block-local `const` 返回精确 deferred domain；for header/body edge 已转正。
3. Current-scope backstop：lambda callable/body 与 match section body 即使 AST edge 缺失也继续 fail-closed；`FOR_BODY` 是 supported executable scope。

这些检查不读取 typed overlay 以决定结构支持，也没有 pending/published lifecycle。`FrontendSuiteContext` 根据 structural policy 创建 request；`FrontendSuiteResolver` 在进入 root/child body前使用 completeness certificate 验证 interface facts。Typed overlay 只改变过滤通过后的 effective type/binding payload。

## 13. Diagnostics 与 Compile Gate

Diagnostics-only phase 在 suite export 后运行，消费 stable facts，不发布新的 semantic side table。

Diagnostic owner 保持单一：

- top binding 负责 `sema.binding`。
- chain binding 负责 `sema.member_resolution` / `sema.call_resolution`。
- expr analyzer 负责 expression resolution 相关 diagnostics。
- var-type-post analyzer 负责 `sema.variable_slot_publication`。
- annotation usage analyzer 负责 `sema.annotation_usage`。
- virtual override analyzer 负责 `sema.virtual_override`。
- type-check analyzer 负责 `sema.type_check` / `sema.type_hint`。
- loop-control analyzer 负责 `sema.loop_control_flow`。
- compile-only analyzer 负责 `sema.compile_check`。

如果同一根源错误已经有 upstream diagnostic，下游 analyzer 只能保留 side-table status，不得补第二条同级错误。

Diagnostics snapshot 在 interface/body path 中有明确层级：同 statement 内 owner procedure 产生的 upstream diagnostic 立即进入 live `DiagnosticManager`；statement boundary flush typed facts 后同步刷新 `FrontendAnalysisData.diagnostics()`，使后一 statement 可读取 current-suite snapshot；suite export 在 patch transaction 应用到 stable side table 后保留最终 body snapshot；interface/body hand-off 与 diagnostics-only phases 继续在各自 phase boundary 刷新 snapshot。

`FrontendCompileCheckAnalyzer` 只运行在 compile-only 入口。默认 shared semantic `analyze(...)`、inspection 与未来 LSP 入口不得隐式运行 compile-only gate。Lowering 只能以 `analyzeForCompile(...)` 且 diagnostics 无 error 的结果作为最低前置条件。Compile gate 在入口仍要求 stable diagnostics boundary 已发布，但 upstream duplicate suppression 使用当时 live `DiagnosticManager` 的冻结 snapshot，以覆盖 interface/body path 中尚未被调用方再次复制到 `FrontendAnalysisData` 的 upstream diagnostics。

`ForStatement` 已进入 shared semantic，但 compile route 尚未就绪。当前 compile-only analyzer 在 `ForStatement` root 发布单一临时 `sema.compile_check` blocker，不读取 iterable type/iteration plan，也不进入 body；后续 route-aware compile policy 只能替换该 lowering boundary，不能反向控制 semantic body entry。

Compile gate 的 generic published-fact blocker 仍基于 final stable facts：`BLOCKED`、`DEFERRED`、`FAILED`、`UNSUPPORTED` 阻断编译，`DYNAMIC` 是 frontend 已认可的 runtime-open fact，不得误判为 blocker。

## 14. 典型行为

对于 source-order local alias：

```gdscript
func f():
    var a := typed_value
    var b := a
    var c := b
```

`a` 的 exact type 在第一个 statement 中写入 pending overlay，flush 后进入 current-suite committed overlay。第二个 statement 解析 `b := a` 时，resolver 先确认 `a` 对 use-site 可见，再从 typed environment 读取 `a` 的 exact slot fact。`b` flush 后，第三个 statement 可以同样读取 `b` 的 exact fact。Stable side tables 与 stable `BlockScope` 只在 suite export 后更新。

对于 receiver-dependent chain：

```gdscript
func f():
    var receiver := make_exact_receiver()
    var x := receiver.member
```

Top binding 先绑定 `receiver` use-site。Local stabilization 随后把 `receiver` 写入 exact local slot overlay。Chain binding 再解析 `receiver.member`，必须消费 exact receiver slot fact，而不是 interface baseline `Variant`。Expr typing 最后发布该 expression 的最终 type fact。

## 15. 完成后的核心不变量

计划完成后应满足以下不变量：

- shared semantic 默认使用 interface/body pipeline。
- shared analyzer 不再提供 legacy whole-phase body publication bypass；`FrontendSegmentedSemanticScheduler` 不再是代码资产。
- top binding、local stabilization、chain binding、expression typing 与 var type post 只由
  `FrontendBodyOwnerProcedures` 的 root-bounded owner procedure 承载；对应 whole-module analyzer
  类已删除。
- `FrontendWindowAnalysisContext`、`FrontendWindowPublicationSurface` 与 legacy
  `FrontendAnalysisPatch` 已删除；body facts 只能通过 per-owner patches 和
  `FrontendPatchTransaction` 导出。
- body typed resolution 按 source order 运行。
- 每个 statement 内 owner 顺序固定为 top binding -> local stabilization -> chain binding -> expr typing -> var type post。
- pending overlay 只对当前 statement 后续 owner 可见。
- statement flush 只更新 current-suite committed overlay。
- suite export 只通过 per-owner patch transaction 更新 stable side tables 与 stable slot。
- patch transaction 与 callable export batch 是 non-atomic ordered apply；batch 不做跨 queued transaction 预检，apply 失败后当前 analysis state 必须整体丢弃。
- `expressionTypes()` 每个 key 只导出最终 fact 一次。
- retry 中间 facts 不进入 pending overlay、committed overlay 或 stable side table。
- source-facing local slot mutation 只有 local stabilization owner 可以产生。
- `backfillInferredLocalType(...)` 保持 guard-only。
- shared compiler-only walker 覆盖所有 user-visible type-bearing publication surfaces。
- resolver 的 declaration-order 与 self-reference filter 不能被 overlay 绕过。
- structural policy 与 completeness certificate 分别控制支持面和 interface 完整性；certificate 对 body inventory 做 index↔scope 双向完备校验；二者都不读取 typed fact 或 compile readiness。
- resolver 的 request-domain、AST boundary 与 current-scope backstop 保持结构性 fail-closed，且 `FOR_BODY` 直接进入 ordinary lookup。
- diagnostics-only phase、compile gate 与 lowering 只能读取 suite export 后的 stable facts。
