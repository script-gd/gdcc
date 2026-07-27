# FrontendLocalTypeStabilization 实现说明

> 本文档作为 `FrontendBodyOwnerProcedures` 中 local-stabilization owner 及其相邻 `:=`
> 局部类型稳定化合同的长期事实源，定义当前 phase 位置、输入输出边界、slot owner、
> 稳定化规则、fail-closed 边界与测试锚点。

## 文档状态

- 状态：事实源维护中（source-order local `:=` slot stabilization、for body ordinary local、parameter/local alias 传播、复杂 initializer 求型、fail-closed 边界与 SuiteResolver overlay/export 路径已落地）
- 更新时间：2026-07-20
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/**`
  - `src/main/java/gd/script/gdcc/frontend/scope/**`
  - `src/test/java/gd/script/gdcc/frontend/sema/**`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `frontend_variable_analyzer_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_type_check_analyzer_implementation.md`
  - `frontend_visible_value_resolver_implementation.md`
  - `scope_analyzer_implementation.md`
  - `scope_type_resolver_implementation.md`
  - `doc/gdcc_type_system.md`
- 明确非目标：
  - 不在这里实现 dynamic member lowering 或 runtime named get/set
  - 不在这里做 property `:=` metadata backfill
  - 不在这里做 whole-module fixed-point
  - 不在这里做 CFG / control-flow merge aware local type refinement
  - 不在这里转正 parameter default、lambda、capture、`match`、block-local `const`、class `const`
  - 不在这里实现 for iteration planning 或 iterator slot refinement
  - 不在这里新增公共 frontend API；如需 helper，优先保持在 analyzer/support 包内且不拥有 phase facts

---

## 1. 当前职责与集成位置

### 1.1 主链路位置

当前 production `FrontendSemanticAnalyzer` 的稳定顺序是：

1. skeleton
2. scope
3. variable inventory
4. interface phase
5. SuiteResolver body publication，内部 owner 顺序固定为 top binding -> local type stabilization -> chain binding -> expr typing -> callable-local slot-type republish
6. annotation usage
7. virtual override
8. type check
9. loop-control legality
10. compile-only final gate（仅 `analyzeForCompile(...)`）

每个 shared phase 结束后，`FrontendSemanticAnalyzer` 都会调用 `analysisData.updateDiagnostics(...)` 刷新共享诊断快照。SuiteResolver body path 还会在 statement boundary 刷新快照，让后一 statement 读取 current-suite upstream diagnostics。

生产 body path 中，local stabilization 作为 `FrontendBodyOwnerProcedures` 的 statement-local
owner procedure 运行在 top binding 之后、chain binding 之前。standalone whole-module analyzer
与 window shim 已删除；focused coverage 通过 SuiteResolver、typed overlay 和
per-owner patch transaction 锚定，不再存在第二条发布路径。

### 1.2 当前职责

local stabilization 当前只负责一件事：在已发布的 callable executable body 中，按源码顺序稳定符合条件的 local `var := initializer` slot type。

它当前稳定负责：

- 遍历 accepted source file 中已发布的 callable executable body
- 只处理 block-local、推断型、带 initializer 的 `DeclarationKind.VAR`
- 通过内部 silent resolver 求 initializer 的暂态表达式类型
- 仅在结果足够稳定时调用 `BlockScope.resetLocalType(...)`
- 允许后续 alias 读取前序 local 的最新 exact type
- 允许 child block 读取 parent block 已稳定的 local slot

### 1.3 当前不负责

本 phase 当前明确不负责：

- 发布 `resolvedMembers()`、`resolvedCalls()`、`expressionTypes()`、`slotTypes()`
- 发布最终 diagnostics
- 回写 property metadata 或 callable metadata
- 打开 whole-module / cross-callable 收敛
- 猜测 true dynamic receiver 的静态类型

---

## 2. 输入、输出与 owner 边界

### 2.1 依赖的已发布事实

当前 local type stabilization 依赖以下输入：

- `analysisData.moduleSkeleton()`
- `analysisData.scopesByAst()`
- `analysisData.symbolBindings()`
- `ClassRegistry`
- 当前已发布的 `CallableScope` / `BlockScope`

若 accepted source file 尚未发布顶层 `SourceFile -> Scope` 记录，则这是 framework guard rail 破坏，analyzer 直接抛异常，而不是把它当作可恢复源码错误。

### 2.2 当前输出

当前 phase 的唯一持久副作用是：

```java
BlockScope.resetLocalType(...)
```

除此之外，它不拥有任何新的 side table，也不发布 lowering-ready facts。

### 2.3 当前 owner 边界

与 local `:=` 相关的 owner 合同已经冻结为：

- `FrontendVariableAnalyzer`
  - 负责参数与局部变量清单的初始登记
  - `:=` local 先以 `Variant` 写入 `BlockScope`
- `FrontendTopBindingAnalyzer`
  - 负责 `symbolBindings()`
  - 负责 bare `TYPE_META` ordinary-value misuse 的首条 `sema.binding`
- `FrontendLocalTypeStabilizationAnalyzer`
  - 只通过 `BlockScope.resetLocalType(...)` 稳定 eligible local slot
  - 不拥有 diagnostics
  - 不发布任何 side table
- `FrontendChainBindingAnalyzer`
  - 仍是 `resolvedMembers()` 与 chain-owned `resolvedCalls()` 的唯一 owner
- `FrontendExprTypeAnalyzer`
  - 仍是 `expressionTypes()` 与 bare-call `resolvedCalls()` 的 owner
  - `backfillInferredLocalType(...)` 只允许保留为受限兜底或一致性检查
- `FrontendVarTypePostAnalyzer`
  - 仍是 `slotTypes()` republish 的唯一 owner

因此本 phase 不得调用：

- `analysisData.updateResolvedMembers(...)`
- `analysisData.updateResolvedCalls(...)`
- `analysisData.updateExpressionTypes(...)`
- `analysisData.updateSlotTypes(...)`

---

## 3. 当前稳定化规则

### 3.1 Eligible declaration

一个 declaration 只有同时满足以下条件才参与稳定化：

- AST 节点是 `VariableDeclaration`
- `kind() == DeclarationKind.VAR`
- `value() != null`
- `FrontendDeclaredTypeSupport.isInferredTypeRef(type()) == true`
- declaration 所在 scope 是 `BlockScope`
- `FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind()) == true`

这条边界继承 structural callable-local inventory 合同。`FOR_BODY` 中的 ordinary `VariableDeclaration` 可以参与稳定化；iterator identity 是 `ForStatement`，不是 eligible declaration，由后续 iteration planning（`FOR_ITERATION_RESOLUTION`）负责精化。该精化写入的 slot update 使用 **`FOR_BODY` `BlockScope` 对象身份**，与 ordinary `var` 的 owning-scope 查找不同；见 `scope_analyzer_implementation.md` §6.1。

### 3.2 Source-order 单遍

当前算法固定为 source-order 单遍：

```text
for statement in block.statements:
  if statement is eligible local := declaration:
    inferred = resolveInitializer(statement.value)
    if inferred is exact and stable:
      blockScope.resetLocalType(...)
```

这足以覆盖当前目标场景：

- 直接 initializer：`var point := make_point()`
- 参数 alias：`var alias := typed_parameter`
- local alias 链：`var a := ...; var b := a; var c := b`
- 复杂 initializer：`var p := factory.make_point(arg).next`

当前不默认引入 bounded retry / fixed-point。原因是当前支持面内的 local-to-local 依赖仍受 declaration-order 可见性约束；同 block 前序 local 一旦能稳定，后序 alias 在同一单遍里就能读到最新 exact type。

### 3.3 可写回结果

当前只允许以下结果触发 slot 写回：

- `FrontendExpressionType.status() == RESOLVED`
- `publishedType() != null`
- `publishedType()` 不是 value-required `void`

写回方向保持单调：

- 允许 `Variant -> exact type`
- 不允许 exact type 之间来回覆盖

`FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 因此只能保留为受限兜底：

- 若当前 slot 仍是 untouched `Variant`，可按既有兜底逻辑补写
- 若当前 slot 已是非 `Variant` 且与 initializer final type 一致，则 no-op
- 若当前 slot 已是非 `Variant` 但与 initializer final type 不一致，则必须 fail-fast 或暴露内部协议错误，不能静默覆盖

### 3.4 Parent / child block 边界

当前 parent / child block 合同是：

- child block 可以读取 parent block 已稳定的 local slot
- child block 中的 declaration 只允许改写自己的 `BlockScope`
- child-local writeback 绝不能回写 parent scope
- 被 variable inventory 拒绝的 same-callable shadow declaration 继续由 `FrontendVariableAnalyzer` 拥有首条 `sema.variable_binding`，local stabilization 不补新 diagnostics，也不借此改写父级 slot

---

## 4. Silent resolver 合同

### 4.1 角色与边界

silent resolver 是 local stabilization phase 内部的 provisional evaluator。它的职责是帮助判断 initializer 是否足够稳定，可以作为 local slot 的 exact type 来源；它不是新的公开语义入口。

当前实现优先保持为 analyzer 内部编排器；只有在共享逻辑确实扩大时，才考虑提取 package-private support helper。

### 4.2 当前允许复用的 shared helper

silent resolver 当前允许复用以下 shared helper：

- `FrontendExpressionSemanticSupport`
- `FrontendChainReductionFacade`
- `FrontendChainReductionHelper`
- `FrontendChainHeadReceiverSupport`
- `FrontendChainStatusBridge`
- `FrontendExecutableInventorySupport`
- `FrontendDeclaredTypeSupport`

它可以处理的表达式类型包括：

- literal
- `self`
- identifier
- attribute chain
- bare call
- subscript
- unary / binary
- 其余当前已支持的 explicit expression

### 4.3 当前禁止的副作用

silent resolver 不能：

- 写 `analysisData.expressionTypes()`
- 写 `analysisData.resolvedMembers()`
- 写 `analysisData.resolvedCalls()`
- 发最终 diagnostics

package-private `probe(...)` 仅作为测试观察窗口存在，用来运行与 `analyze(...)` 相同的 walker / resolver 路径并返回暂态 initializer typing 结果；它必须保持 side tables、shared diagnostics 与 scope slots 不变。

---

## 5. Fail-closed 边界与非目标

### 5.1 显式 fail-closed 边界

以下场景当前保持 `Variant`，不做猜测：

- `DYNAMIC(Variant)` / true dynamic receiver
- `BLOCKED`
- `DEFERRED`
- `FAILED`
- `UNSUPPORTED`
- `publishedType() == null`
- value-required `void`
- bare `TYPE_META` ordinary-value initializer
- assignment expression ordinary-value initializer
- route-head-only `TYPE_META`
- initializer 中无法稳定解析的 call / member / subscript

其中两条边界是显式 slot-writeback 合同，而不是依赖间接失败结果：

- bare `TYPE_META` ordinary-value initializer 由 local stabilization 直接拒绝；首条 `sema.binding` 仍由 top binding 拥有
- assignment expression initializer 由 local stabilization 直接拒绝；不能只靠 assignment helper 的 value-required 失败来“碰巧”维持 `Variant`

### 5.2 当前不打开的 subtree

以下输入当前仍不进入 local stabilization 的正式支持面：

- parameter default
- lambda / capture
- `match`
- block-local `const`
- class `const`
- unsupported subtree
- control-flow merge / join

这些边界若未来要打开，必须与 variable inventory、visible-value resolution、chain binding、expr typing 和 type check 一起收口，不能在本 phase 单独偷开支持面。For body 已经通过普通 SuiteResolver path 进入本 owner，但 iterator refinement 仍不属于 local `var :=` stabilization。

---

## 6. 稳定测试锚点

后续调整本模块时，至少应继续锚定以下测试：

- `FrontendLocalTypeStabilizationAnalyzerTest`
  - source-order alias
  - 复杂 initializer
  - parameter alias
  - true dynamic fail-closed
  - unsupported subtree 不泄漏 side table / diagnostics
- `FrontendChainBindingAnalyzerTest`
  - typed local write/read path 不再错误降级为 `DYNAMIC`
  - alias 链与复杂 initializer 的 receiver member route 保持 `RESOLVED`
- `FrontendExprTypeAnalyzerTest`
  - `backfillInferredLocalType(...)` 只对 untouched `Variant` 做受限兜底
  - 已稳定 slot 与 final type 冲突时不能静默覆盖
- `FrontendVarTypePostAnalyzerTest`
  - `slotTypes()` 只 republish 已 settle 的 lexical inventory
  - true dynamic local 最终仍发布为 `Variant`
- `FrontendSemanticAnalyzerFrameworkTest`
  - phase 顺序
  - diagnostics snapshot boundary
  - local stabilization 不发布 member/call/expression/slot side tables

focused case 至少要继续覆盖：

- `var tail := make_point(); tail.next = point`
- `var point := make_point(); return point.marker != -1`
- `var a := typed_parameter; return a.marker`
- `var a := make_point(); var b := a; var c := b`
- `var p := factory.make_point(arg).next; var q := p`
- child block 读取 parent block 已稳定 local
- rejected shadow declaration 不污染 parent slot

---

## 7. 维护结论

`FrontendLocalTypeStabilizationAnalyzer` 当前已经是 block-local inferred slot stabilization 的 primary owner。后续工程若继续扩张表达式支持面或 local refinement 能力，必须继续遵守本文档记录的 owner 边界、source-order 单遍前提与 fail-closed 合同，而不是恢复“先发布 chain/member facts、再晚回填 local slot”的旧时序。

只有在以下能力真正进入正式支持面时，才应重新评估 bounded retry、fixed-point 或更完整的数据流分析：

- forward local reference
- assignment-based local type refinement
- CFG branch / loop merge aware refinement
- `match` / lambda / capture local inventory
- for iterator route-aware refinement
- property `:=` metadata backfill
- 跨 callable 或 whole-module 的类型收敛
