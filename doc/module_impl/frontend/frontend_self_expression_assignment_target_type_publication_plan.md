# 显式 self 赋值目标类型发布实施计划

> 本文档记录 direction 1 的具体实施计划：在 frontend semantic 阶段为赋值目标前缀中的显式
> `SelfExpression` 发布 expression type fact，使后续 CFG / body lowering 继续只消费已冻结的
> published facts，而不是在 lowering 侧补做语义推断。
>
> 本文档是待实施计划，不是已落地事实源。实现完成后，应把最终稳定合同同步到对应事实源文档，并移除或改写本文中的阶段性内容。

## 文档状态

- 状态：3.1 已实施并通过 targeted validation（事实源迁移仍待后续处理）
- 更新时间：2026-04-25
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzer.java`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendAssignmentSemanticSupport.java`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilder.java`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/FrontendBodyLoweringSupport.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/test/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/test/java/dev/superice/gdcc/backend/c/build/**`
- 关联事实源：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_complex_writable_target_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
- 明确非目标：
  - 不放宽 compile gate 对 unsupported syntax 的拦截。
  - 不在 lowering 侧新增“缺什么 fact 就临时推什么”的语义 fallback。
  - 不把 `IdentifierExpression + SELF` 当成显式 `SelfExpression` 的合法替代。
  - 不改变 assignment root 语义：statement-position 成功赋值仍发布 `void`。
  - 除非后续实现证明存在独立 backend 缺陷，否则不修改 backend codegen。

---

## 1. 问题背景

`build/libs/rotating_camera.gd` 当前能通过 parse 与 shared frontend diagnostics，但在 frontend lowering 阶段失败：

```text
IllegalStateException: Missing published expression type for SelfExpression
```

最小触发形态是：

```gdscript
self.position = vec
```

以下内容不是本问题主因：

- `class_name RotatingCamera extends Camera3D`
- `@export var ...: float = ...`
- `_process(delta: float) -> void`
- `Vector3(...)`
- `Vector3.BACK` / `Vector3.UP` / `Vector3.ZERO`
- `vec.rotated(...)`
- `self.look_at_from_position(...)`

真正的问题是：显式 `self` 出现在 assignment-target attribute chain 的 base 位置时，assignment target
语义能成功证明 `self.position` 是可写属性，但没有为这个具体 `SelfExpression` AST node 发布 ordinary
`expressionTypes()` fact。后续 CFG builder 又会把这个 target prefix materialize 成 value，body lowering
在收集 value materialization type 时找不到对应类型事实，于是 fail-fast。

当前失败链路固定为：

1. `FrontendExprTypeAnalyzer` 发布 statement-position assignment root。
2. `FrontendAssignmentSemanticSupport` 通过 writable-target model 解析左侧。
3. writable-target model 成功证明 `self.position` 是 writable property。
4. 作为 attribute base 的显式 `SelfExpression` 没有进入 ordinary `expressionTypes()`。
5. `FrontendCfgGraphBuilder.buildAssignmentTargetValue(...)` 发现 assignment-target prefix 没有
   lowering-ready type，于是为 `SelfExpression` 发出 `OpaqueExprValueItem`。
6. `FrontendBodyLoweringSupport.collectCfgValueMaterializations(...)` 需要为这个 produced value id
   确认 `GdType`。
7. `requireOpaqueValueType(...)` 无法从 `expressionTypes()`、declaration-site `slotTypes()` 或
   `PropertyDef` 恢复类型，最终抛出 `Missing published expression type for SelfExpression`。

本文档选择的修复方向是 direction 1：在 semantic 阶段补齐 assignment-target prefix 中显式
`SelfExpression` 的 type fact，让 lowering 继续保持 published-fact consumer 的边界。

---

## 2. 设计原则

本次修复的核心目标是补齐 compile-ready surface 的 semantic side table，而不是扩大 lowering 的语义职责。

必须保持以下原则：

- `FrontendBodyLoweringSupport` 继续只消费 frozen `FrontendAnalysisData`，不得重跑 receiver type 推断。
- 新增发布面必须收口到 assignment target lowering 已经会 materialize 的 prefix。
- 显式 `SelfExpression` 与 `IdentifierExpression + SELF` 的边界继续保持：前者是合法 source surface，后者仍是 invariant violation。
- static context、property initializer boundary 等 `self` 访问限制继续由现有 semantic helper 决定。
- 发布 `SelfExpression` type fact 时复用 `FrontendExpressionSemanticSupport.resolveSelfExpressionType(...)`，不得另写一套 self type 推导。
- assignment root 的成功结果仍是 `RESOLVED(void)`，不得因为 target prefix 发布类型而变成 value-producing expression。

在合法 instance context 中，显式 `self` 的预期 published fact 是：

- status：`RESOLVED`
- type：当前 class object type

若当前上下文禁止 `self`，则应该发布现有 resolver 给出的 `BLOCKED` / `FAILED` fact，使 compile
gate 能够在 lowering 前确认该 fact 不是 lowering-ready。这里的 primary diagnostic owner 仍然由
upstream analyzer 决定；若同一 `SelfExpression` range 已有 upstream error，compile-check 不得再补
generic `sema.compile_check`，只能在没有 upstream owner 的异常场景中作为兜底 hard stop。

---

## 3. 实施步骤

### 3.1 在 assignment expression 语义处理中加入窄发布 hook

实施状态：已完成代码落地并通过 targeted tests。

修改目标：

- `FrontendExprTypeAnalyzer.resolveExpressionType(...)`
- 其中处理 `AssignmentExpression` 并调用
  `FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(...)` 的分支

新增一个 private helper，用于在解析 assignment expression 时发布左侧 target prefix 所需的 expression facts。
建议命名围绕具体职责，例如：

```java
publishAssignmentTargetPrefixTypes(...)
```

实现要求：

- helper 保持在 `FrontendExprTypeAnalyzer` 内部，不新增公共 visitor 或跨包抽象。
- 只遍历 assignment left side。
- helper 必须在调用 `FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(...)` 之前执行，作为
  publication-only pre-pass；不得在 assignment root resolution 之后根据 root 结果“补救式”发布。
- 这个“之前”只限定在 `FrontendExprTypeAnalyzer` 的当前 `AssignmentExpression` 分支内：scope、top binding、
  chain binding 等前置 analyzer 已经完成并冻结可消费 facts；helper 随后发布 target prefix 中已复现缺口的
  `SelfExpression` fact；最后才调用 assignment root resolution。
- helper 不得调用 writable-target reduction，也不得根据 property writability、RHS type 或 assignment root
  success / failure 决定是否发布 `SelfExpression` fact。
- 复用既有 `publishExpressionType(...)` / `publishResolvedExpressionType(...)` 路径。
- 遇到已经发布过的 AST node 时直接复用，不覆盖已有 fact。
- 不直接写 `analysisData.expressionTypes()`，避免绕过现有 publication 规则。
- helper 本身不得 emit diagnostics；所有诊断继续由既有 owner 负责。

本步骤验收：

- `self.position = vec` 会为左侧 base 的具体 `SelfExpression` 发布 expression type。
- `position = vec` 行为不变，不制造 synthetic `SelfExpression` fact。
- `time += delta` 行为不变。
- statement-position assignment root 仍发布 `RESOLVED(void)`。
- publication 时机可通过测试或代码审查确认：`SelfExpression` fact 不依赖 assignment root 先解析成功。

实现产出注释：

- `FrontendExprTypeAnalyzer` 在 `AssignmentExpression` 分支调用
  `FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(...)` 之前触发
  `publishAssignmentTargetPrefixTypes(...)`。
- helper 只识别 `operator == "="`、左侧是单步 `AttributePropertyStep`、base 是显式
  `SelfExpression` 的 plain direct property assignment；不下探 call/subscript arguments，也不处理 nested
  property、container mutation 或 compound assignment。
- helper 复用 `publishExpressionType(selfExpression)`，因此 static context 与 property-initializer boundary 仍沿用
  现有 `resolveSelfExpressionType(...)` / top-binding diagnostics，不新增诊断 owner。

### 3.2 将发布范围收缩到已复现的 explicit `SelfExpression`

实施状态：已完成代码落地与聚焦测试补强，并通过本轮 targeted validation。

当前没有明确 reproducer 能证明以下节点也存在同类 missing published type 缺口：

- `IdentifierExpression`
- 一般 attribute prefix
- subscript key / index argument
- `CallExpression` 形式的 target prefix

因此本计划不得把 3.1 的 hook 泛化成“发布整个 assignment-target lowering surface”。当前实现范围应直接收缩为：

- 只识别 plain attribute-property assignment left side 中作为最外层 receiver 出现的显式 `SelfExpression`
- 只为该具体 `SelfExpression` AST node 触发现有 `resolveSelfExpressionType(...)` 并发布 expression type fact
- 不主动为普通 `IdentifierExpression`、attribute step、subscript key / index argument、call prefix 或更深层 writable-route carrier 补发 expression type
- 若后续出现新的独立 reproducer，再为对应 surface 单独建计划或扩展本文档

当前必须覆盖的形态只限于已复现的 plain direct property assignment：

```gdscript
self.position = value
```

以下形态只能作为未来扩面时的回归候选，不能作为本次修复的最低覆盖：

```gdscript
self.transform.origin = value
self.payloads[i] = value
self.position += delta
```

原因是它们已经超出“显式 `self` 缺少 type fact”这一单点缺口：

- `self.transform.origin = value` 涉及深层 property owner route 与 reverse commit。
- `self.payloads[i] = value` 涉及 container mutation、subscript access family 冻结与 carrier 线程化。
- `self.position += delta` 涉及 compound assignment 的 current-value read 与 read-modify-write 形态。

这些语义已经由 `frontend_complex_writable_target_implementation.md` 冻结为独立硬合同。若后续要把它们纳入本
feature 的验收，需要先提供独立 reproducer，证明失败仍然是 explicit `SelfExpression` missing type fact，
而不是 nested writable route / container mutation / compound assignment 的其它边界问题。

本步骤验收：

- assignment target 下的显式 `self` 每个 AST identity 只发布一次。
- `IdentifierExpression`、一般 attribute prefix、subscript key / index argument 没有因为本计划新增发布行为。
- nested property、container mutation、compound assignment 没有因为本计划被隐式纳入 compile-ready 验收。
- 已存在的 expression fact 不被覆盖。
- unsupported assignment target form 仍通过原有 semantic / compile-check 路线失败。
- 已合法的 assignment target 不新增重复诊断。

实现产出注释：

- `FrontendExprTypeAnalyzerTest.analyzeKeepsExplicitSelfAssignmentTargetPrefixPublicationNarrow` 已把同一
  callable 中的 `hp = delta`、`holder.hp = delta`、`self.hp = delta`、`self.hp += delta`、
  `self.payloads[index] = delta` 放在一起断言：只有 plain direct `self.hp = delta` 的 base
  `SelfExpression` 获得新增 expression type fact。
- attribute-subscript target 的 index 参数仍按既有 assignment target dependency 规则发布类型；本步骤只保证
  这次 helper 不把 subscript key / index argument 当成新的补发 surface，也不补发该 target 的
  explicit-`self` receiver。

### 3.3 保持 self 边界语义不变

实施状态：已完成代码落地与 property-initializer 回归补强，并通过本轮 targeted validation。

新增 hook 必须在 assignment expression 当前 analyzer context 中运行，沿用：

- 当前 `ResolveRestriction`
- 当前 static-context flag
- 当前 property-initializer context

不得在新 helper 中手写：

- 当前类类型如何构造
- static context 如何报错
- property initializer 如何封口

这些判断已经由 `FrontendExpressionSemanticSupport.resolveSelfExpressionType(...)` 与
`FrontendPropertyInitializerSupport` 承担。新 hook 只负责触发同一套 resolver 并发布结果。

本步骤验收：

- static function 中的 `self.position = vec` 仍报告既有 static-context 诊断。
- property initializer 中使用 `self` 仍报告既有 initializer-boundary 诊断。
- compile mode 能看到 blocked published fact；有既有 upstream error 时由该 error 阻断 lowering，且不新增
  assignment-root 级重复 blocker。

实现产出注释：

- `FrontendExprTypeAnalyzerTest.analyzePublishesBlockedExplicitSelfAssignmentTargetPrefixInStaticContext`
  锚定 static context：新 helper 发布 `SelfExpression` 的 `BLOCKED` fact，但主诊断仍来自既有
  `sema.binding`。
- `FrontendExprTypeAnalyzerTest.analyzeKeepsExplicitSelfPropertyInitializerBoundaryUnchanged`
  锚定 property-initializer boundary：`var blocked := self.hp` 中的 explicit `SelfExpression` 复用
  `resolveSelfExpressionType(...)` 得到 blocked fact，诊断仍由 top-binding/property-initializer 规则拥有。
- 调研确认当前 parser 不接受 property initializer value 中的 assignment expression 形态，例如
  `var blocked := (self.hp = 1)` 会产生 parse lowering diagnostic。因此 3.3 的 property-initializer
  验收锚定为“显式 `self` 边界语义不变”，不把不可解析的 assignment-target 形态纳入本阶段测试。

### 3.4 固定 diagnostic owner、anchor 与去重策略

实施状态：已补充结构化 assignment-root 去重逻辑，并通过本轮 targeted validation。

补齐 blocked / failed `SelfExpression` fact 后，`FrontendCompileCheckAnalyzer` 可能开始看到更多
expression-type fact。这里必须先固定 diagnostic ownership，避免把一个 prefix 级错误漂移成 assignment
root 级 generic compile blocker。

代码调研结论：

- `FrontendExpressionSemanticSupport.resolveSelfExpressionType(...)` 是纯 resolver：只返回
  `FrontendExpressionType` / `rootOwnsOutcome`，不发布 side table，也不 emit diagnostics。
- `FrontendTopBindingAnalyzer.visitSelf(...)` 当前已经在 `SelfExpression` range 上拥有 static-context 与
  property-initializer boundary 的主诊断。
- `FrontendChainHeadReceiverSupport.resolveSelfReceiver(...)` 会把 static context / property initializer 转成
  blocked receiver 状态，但同样不 emit diagnostics。
- `FrontendCompileCheckAnalyzer.scanExpressionTypeCompileBlocks(...)` 消费 published expression fact；
  `compileAnchorForExpressionType(...)` 对 ordinary `SelfExpression` 不做重锚定。
- `FrontendCompileCheckAnalyzer.reportCompileBlock(...)` 先用
  `hasPublishedConflictingDiagnosticAt(...)` 按 exact range 避免与 upstream error 重复，再用
  `handledAnchors` 按 AST identity 去重。

当前最佳方案固定为：

- 合法 `self.position = value`
  - 不产生任何新增 diagnostic。
  - `SelfExpression` fact 只服务于 lowering materialization type。
- static context 中的 `self.position = value`
  - 主 diagnostic owner 仍是 `FrontendTopBindingAnalyzer`。
  - 主 anchor 是 prefix 的 `SelfExpression` range。
  - 新 helper 只发布该 `SelfExpression` 的 blocked fact，不报告错误。
- property initializer value 中出现的 `SelfExpression`
  - 主 diagnostic owner 仍是 `FrontendTopBindingAnalyzer` 的 property-initializer boundary 诊断。
  - 主 anchor 仍是 prefix 的 `SelfExpression` range。
  - 新 helper 只发布该 `SelfExpression` 的 blocked fact，不报告错误。
- 没有 upstream error 但 `SelfExpression` fact 仍为 non-lowering-ready 的未来异常场景
  - compile-check 可以成为兜底 owner。
  - compile-check anchor 必须是具体 `SelfExpression`，不是 assignment root。
  - 兜底 diagnostic 的 source range 应来自 `compileAnchorForExpressionType(SelfExpression)`，当前实现对
    `SelfExpression` 不做 attribute-terminal remap，因此 anchor 会保持在 prefix 节点本身。

去重策略必须遵守现有 compile-check 合同：

- `SelfExpression` fact 若与 upstream error 拥有同一 exact range，`hasPublishedConflictingDiagnosticAt(...)`
  必须让 generic `sema.compile_check` 静默跳过。
- 同一 `SelfExpression` anchor 的多次扫描继续由 `handledAnchors` 按 node identity 去重。
- 若 assignment root 也因为同一 prefix failure 发布 propagated non-success fact，compile-check 不得再补一条
  assignment-root 级 `sema.compile_check`。如果当前实现会产生这种重复，必须先补充 compile-check
  去重/anchor 逻辑和回归测试，再接受本 feature。
- assignment root 只有在错误确实属于 root 自身时才作为 diagnostic owner，例如 assignment value type
  incompatible、assignment expression 出现在 value-required 位置等 root-owned failure。

需要检查的测试面：

- expression-type compile blocker
- assignment target facts
- property initializer facts
- static context / skipped subtree
- assignment root propagated failure 与 prefix failure 的去重

调整测试断言时必须先确认：

- 新 anchor 更精确，且没有把 prefix-owned 错误漂移到 assignment root。
- 没有重复诊断。
- compile-check 没有因为新 fact 放宽 unsupported surface。

本步骤验收：

- 有效的 explicit-self assignment 不产生 compile diagnostics。
- 无效的 explicit-self assignment 在 lowering 前被拦截，但主诊断仍归属于既有 upstream owner；只有无
  upstream owner 的未来 non-lowering-ready self fact 才由 compile-check 兜底。
- 同一路非法 `self` assignment 不出现重复错误。
- static context 的 `self.position = value`、property initializer value 中的 explicit `self` 不新增 assignment-root 级
  `sema.compile_check`。

测试发现：

- `FrontendCompileCheckAnalyzerTest.analyzeForCompileKeepsStaticSelfAssignmentTargetDiagnosticAtSelfAnchor` 首次验证时复现
  root 级重复诊断：`SelfExpression` range 已有 `sema.binding`，但 assignment root range 仍收到 generic
  `sema.compile_check`。
- 修正方向应保持窄范围：当 assignment root 的 non-lowering-ready fact 可追溯到左侧 direct
  explicit-`SelfExpression` prefix，且该 prefix range 已有 upstream blocking diagnostic 时，compile-check 应跳过
  root 级 generic blocker；其它 root-owned assignment failure 仍由 root 自身负责。
- 实现产出保持同一窄范围：assignment-root 去重仅在 `operator == "="`、左侧是单步
  `AttributePropertyStep`、base 是显式 `SelfExpression`，root 与 prefix 的 published status 相同且均为
  compile-blocking，并且 prefix range 已有 upstream blocking diagnostic 时触发。该判断不依赖
  `detailReason` 文本包含关系，避免诊断文案调整导致去重漂移。
- 本轮曾尝试把同一去重推广到普通 property-initializer `self.hp` 的 member step fact，但 targeted
  test 证明这会改变既有 compile-check 合同：property initializer 的 published member/call facts 本来
  就由 compile-check 兜底报告。因此 3.4 只收敛 assignment-root propagated failure，不改变
  `resolvedMembers()` / `resolvedCalls()` 的通用扫描规则。

### 3.5 增加聚焦 semantic 测试

实施状态：已补充测试用例并通过 targeted tests。

建议扩展以下测试类：

- `FrontendExprTypeAnalyzerTest`
- `FrontendCompileCheckAnalyzerTest`

必须覆盖：

- instance method 中 `self.position = value` 为具体 `SelfExpression` 发布 `RESOLVED` expression type。
- 同一条 `self.position = value` 的 assignment root 仍是 `RESOLVED(void)`。
- static context 中 `self.position = value` 的 prefix `SelfExpression` 发布 blocked fact，但主诊断仍是
  `FrontendTopBindingAnalyzer` 在 `SelfExpression` range 上的 upstream error。
- property initializer 中使用 `self` 的 prefix `SelfExpression` 发布 blocked fact，但主诊断仍是
  property-initializer boundary upstream error。
- `position = value` 不发布 synthetic `SelfExpression` fact。
- 若测试中构造 synthetic `IdentifierExpression + SELF` lowering alias surface，负例仍保持 fail-fast。

测试要求：

- 通过 AST identity 找到具体 `SelfExpression` node。
- 断言 status 与 type，不只断言没有 diagnostics。
- static context 与 property initializer 的既有诊断必须保留，且不得新增 assignment-root 级重复诊断。

测试产出注释：

- `FrontendExprTypeAnalyzerTest` 已在 statement assignment 用例中断言 `self.hp = 2` 的左侧 base
  `SelfExpression` 发布为 `RESOLVED(current class type)`，且 assignment root 仍为 `RESOLVED(void)`。
- `FrontendExprTypeAnalyzerTest` 已增加 compound assignment 与 attribute-subscript assignment 负例，确认本次 helper
  不为 `self.hp += delta` / `self.payloads[0] = delta` 发布 `SelfExpression` fact。
- `FrontendExprTypeAnalyzerTest` 已增加 static context 负例，确认 prefix `SelfExpression` 发布 `BLOCKED` fact，主诊断仍来自
  `sema.binding`。

### 3.6 增加 CFG / body lowering 回归测试

实施状态：已补充 body-lowering 回归用例并通过 targeted tests。

建议扩展以下测试类：

- `FrontendCfgGraphBuilderTest`
- `FrontendLoweringBodyInsnPassTest`
- 只有当前端-only 测试无法覆盖端到端 lowering crash 时，才考虑
  `FrontendLoweringToCProjectBuilderIntegrationTest`

必须覆盖：

- `self.position = vec` 不再触发 `Missing published expression type for SelfExpression`。
- 生成的 LIR 中存在预期的最终 `position` property store。
- explicit `self` receiver 不导致 body lowering 重跑 property / method resolution。
- 不把 nested property、container mutation 或 compound assignment 当成本次修复的通过条件。

本步骤验收：

- 实现前测试能复现旧 crash，实现后通过。
- 测试检查关键 instruction shape，而不是只检查“不抛异常”。
- 既有 `IdentifierExpression + SELF` direct-slot alias 负例保持不变。

测试产出注释：

- `FrontendLoweringBodyInsnPassTest` 已增加 `self.hp = seed` 回归，验证 body lowering 能生成 `hp` 的
  `StorePropertyInsn`，object id 为 `self`，且不引入 Variant pack/unpack 边界。
- `FrontendCompileCheckAnalyzerTest` 已增加 static explicit-self assignment target 去重回归，确认已有
  `SelfExpression` range 上的 upstream binding error 时不新增 assignment-root 级 `sema.compile_check`。

### 3.7 增加 rotating-camera 形态 smoke

新增一个最小化 rotating-camera 形态回归，避免把无关 native build 问题混入 frontend 验收。

推荐源码：

```gdscript
class_name RotatingCameraSelfAssignmentSmoke extends Camera3D

func _process(delta: float) -> void:
    var vec = Vector3(1.0, 0.0, 0.0)
    self.position = vec
```

可选扩展源码：

```gdscript
func _process(delta: float) -> void:
    var vec = Vector3(1.0, 0.0, 0.0)
    vec = vec.rotated(Vector3.BACK, deg_to_rad(delta))
    self.position = vec
    self.look_at_from_position(vec, Vector3.ZERO)
```

本步骤验收：

- 最小 smoke 能通过 frontend lowering。
- 若使用 C-project integration test，native build 失败不能作为 frontend 验收信号。
- 测试命名必须体现 explicit self assignment target，而不是只写 rotating camera。

### 3.8 执行 targeted validation

实施状态：targeted tests 通过；rotating-camera CLI smoke 已越过 frontend lowering，后续 native build path 失败另行归类。

先运行聚焦测试：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendExprTypeAnalyzerTest,FrontendCompileCheckAnalyzerTest,FrontendCfgGraphBuilderTest,FrontendLoweringBodyInsnPassTest
```

若新增 integration 测试，再单独运行相关类或方法：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendLoweringToCProjectBuilderIntegrationTest
```

最后用原脚本执行 CLI smoke：

```powershell
rtk java -jar build/libs/gdcc-1.0-SNAPSHOT.jar -o tmp/rotating_camera_compile -vv build/libs/rotating_camera.gd
```

本步骤验收：

- 不再出现 frontend lowering crash：`Missing published expression type for SelfExpression`。
- 若仍失败，必须按真实 phase 归类。例如 native build path 失败应另行跟踪，不能归入本 frontend 问题。
- 测试输出能区分 semantic publication、compile gate、CFG shape、body lowering 是否分别通过。

验证产出注释：

- `FrontendExprTypeAnalyzerTest,FrontendCompileCheckAnalyzerTest` targeted tests 已通过。
- `FrontendLoweringBodyInsnPassTest,FrontendCfgGraphBuilderTest` targeted tests 已通过。
- 重建 `build/libs/gdcc-1.0-SNAPSHOT.jar` 后执行 `build/libs/rotating_camera.gd` CLI smoke，frontend lowering 阶段通过；
  当前失败发生在 `BUILDING_NATIVE`，错误为 `IllegalArgumentException: Path component should be '/'`，不再属于本次
  `SelfExpression` missing published type 缺口。

---

## 4. 详细验收矩阵

- instance method 中 `self.position = vec`
  - 预期 semantic fact：`SelfExpression -> RESOLVED(current class type)`
  - 预期编译行为：通过 frontend lowering
- instance method 中 `position = vec`
  - 预期 semantic fact：不制造 synthetic `SelfExpression` fact
  - 预期编译行为：行为不变
- static function 中 `self.position = vec`
  - 预期 semantic fact：`SelfExpression -> BLOCKED`，主诊断仍归属 upstream binding owner
  - 预期编译行为：frontend diagnostic，不能新增 assignment-root 级重复 blocker
- ordinary expression 中 `var x = self.position`
  - 预期 semantic fact：继续走既有 ordinary expression route
  - 预期编译行为：行为不变
- property initializer 中使用 `self`
  - 预期 semantic fact：`SelfExpression -> BLOCKED`，主诊断仍归属 property-initializer boundary owner
  - 预期编译行为：frontend diagnostic，不能新增 assignment-root 级重复 blocker
- synthetic `IdentifierExpression + SELF` lowering alias
  - 预期 semantic fact：继续 fail-fast
  - 预期编译行为：既有负例不变
- `self.transform.origin = vec`
  - 预期 semantic fact：本计划不新增承诺
  - 预期编译行为：未来扩面候选，需要独立 reproducer
- `self.payloads[i] = value`
  - 预期 semantic fact：本计划不新增承诺
  - 预期编译行为：未来扩面候选，需要独立 reproducer
- `self.position += delta`
  - 预期 semantic fact：本计划不新增承诺
  - 预期编译行为：未来扩面候选，需要独立 reproducer

---

## 5. 风险与边界

- 发布过宽会让 unsupported syntax 看起来像 compile-ready surface。遍历范围必须绑定已复现的 plain
  explicit-self property assignment，不得顺手覆盖 nested property、container mutation 或 compound assignment。
- 发布过晚无效，因为 `FrontendCompileCheckAnalyzer` 与 body lowering 都消费冻结后的 `FrontendAnalysisData`。
- lowering-side fallback 会隐藏 semantic fact 缺失，破坏当前架构分层；那属于 direction 2，不属于本文计划。
- `SelfExpression` type publication 不能改变 assignment root 的 void 语义。
- 不保留兼容 shim。既然 compile-ready lowering 已经需要该 fact，就应由 semantic analyzer 直接发布。

---

## 6. 完成标准

全部满足以下条件后，本计划才算完成：

1. `FrontendExprTypeAnalyzer` 会为支持的 assignment target prefix 发布显式 `SelfExpression` type fact。
2. static context 与 property initializer 中的既有 `self` boundary diagnostics 保持不变。
3. `FrontendCompileCheckAnalyzer` 能识别 invalid published self fact：有 upstream owner 时不新增重复
   `sema.compile_check`，无 upstream owner 时兜底阻断 lowering。
4. `FrontendCfgGraphBuilder` 与 `FrontendBodyLoweringSupport` 不再在合法 explicit-self assignment target
   上看到 missing type fact。
5. semantic 与 lowering 聚焦测试覆盖 `self.position = vec`，并明确不把 nested property、container mutation
   或 compound assignment 作为本次最低完成标准。
6. 原始 `build/libs/rotating_camera.gd` 不再因
   `Missing published expression type for SelfExpression` 在 frontend lowering 阶段失败。
