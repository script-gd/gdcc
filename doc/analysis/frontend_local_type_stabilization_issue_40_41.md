# frontend local 类型稳定化与 issue #40/#41 调研记录

- 日期：2026-06-17
- 范围：本文只记录本次针对 issue #40 与 issue #41 的文档阅读、代码调研、临时最小复现和方案讨论结论。
- 状态：调研结论与方案评估文档；尚未实施代码修复。

---

## 1. 执行摘要

issue #40 与 issue #41 是同一条问题链上的两个层面：

1. issue #40 的根因是 frontend phase 顺序导致的 local `:=` 类型稳定时机过晚。`FrontendChainBindingAnalyzer` 在 `FrontendExprTypeAnalyzer` 回填 inferred local 类型之前运行，因此 `var tail := make_point()` 之后的 `tail.next` 在 chain binding 阶段看到的 `tail` 仍是初始 `Variant`，于是成员事实被发布为 `DYNAMIC`。
2. 本次最小探针确认：`make_point()` 本身已经解析为 `RESOLVED`，返回类型为 canonical inner class `TypedLocalPropertyWrite__sub__Point`；initializer expression 也是 exact `Point`。因此 #40 不是 inner class declared type 解析失败，也不是函数返回类型丢失，而是 chain member publication 消费了回填前的 stale local slot type。
3. issue #41 是 downstream contract 不一致。当前 CFG builder 接受 `RESOLVED | DYNAMIC` member fact 作为 lowering-ready，但 body lowering 与 value materialization 仍把 member 路径当作 `RESOLVED + resultType`。由于 `DYNAMIC` member 按模型不携带 `resultType`，所以后续会在不同边界失败。
4. 简单多跑 `chainBindingAnalyzer` 与 `exprTypeAnalyzer` 可以修 #40 的直接症状，但必须处理 diagnostics 泄漏与 fixed-point 收敛，否则会把 provisional 诊断和最终 facts 混在一起。
5. 若要同时支持链式 local alias 推断，以及复杂链式 initializer，推荐新增一个职责明确的 local type stabilization 阶段：它只拥有 inferred local slot type backfill，不拥有 `resolvedMembers()`、`resolvedCalls()`、`expressionTypes()` 或最终 diagnostics。

---

## 2. 相关事实源

### 2.1 文档事实

本次调研重点阅读并交叉确认了以下文档约束：

- `doc/module_impl/frontend/frontend_rules.md`
- `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
- `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
- `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
- `doc/module_impl/frontend/frontend_builtin_property_access_implementation.md`
- `doc/module_impl/frontend/frontend_complex_writable_target_implementation.md`
- `doc/module_impl/frontend/inner_class_implementation.md`
- `doc/module_impl/frontend/scope_type_resolver_implementation.md`
- `doc/module_impl/backend/operator_insn_implementation.md`
- `doc/module_impl/backend/load_store_property_implementation.md`
- `doc/module_impl/backend/variant_abi_contract.md`
- `doc/gdcc_type_system.md`
- `doc/gdcc_low_ir.md`
- `doc/gdcc_c_backend.md`

其中最重要的合同是：

- `DYNAMIC` 是 frontend 已认可的 runtime-open fact，不是 compile gate blocker。
- side-table facts 有固定 owner：`resolvedMembers()` / `resolvedCalls()` 由 chain binding 发布，`expressionTypes()` 由 expression type analyzer 发布，后续阶段应消费而不是随意重写。
- CFG 与 body lowering 必须消费 frozen facts，不应回 AST 重跑第二套推断。
- inner class 使用 source-facing `sourceName` 参与 lexical type namespace，离开 type namespace 后进入 canonical instance type。

### 2.2 代码事实

本次调研重点确认了以下实现位置：

- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendChainBindingAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendChainHeadReceiverSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendChainReductionHelper.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendChainStatusBridge.java`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendResolvedMember.java`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendResolvedCall.java`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendExpressionType.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilder.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/FrontendBodyLoweringSupport.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendBodyLoweringSession.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`

---

## 3. issue #40 成因链路

### 3.1 现有 phase 顺序

当前 shared semantic pipeline 的关键顺序是：

1. `FrontendScopeAnalyzer`
2. `FrontendVariableAnalyzer`
3. `FrontendTopBindingAnalyzer`
4. `FrontendChainBindingAnalyzer`
5. `FrontendExprTypeAnalyzer`
6. `FrontendVarTypePostAnalyzer`
7. diagnostics-only analyzers

这个顺序的直接后果是：

- `FrontendVariableAnalyzer` 会先把 `:=` local 作为 `Variant` 放入 `BlockScope`。
- `FrontendChainBindingAnalyzer` 随后解析 member/call chain，此时 inferred local 尚未根据 initializer 回填。
- `FrontendExprTypeAnalyzer` 后运行，才根据 initializer `expressionTypes()` 调 `BlockScope.resetLocalType(...)`。
- 已经发布的 `resolvedMembers()` 不会被 `FrontendExprTypeAnalyzer` 重写。

### 3.2 `:=` local 回填不是声明时完成

`FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 的行为是：

- 只处理 `FrontendDeclaredTypeSupport.isInferredTypeRef(...)`。
- 只在 declaration scope 是 `BlockScope` 时回填。
- initializer 类型状态为 `RESOLVED` 或 `DYNAMIC` 时取 `publishedType()`。
- 调用 `blockScope.resetLocalType(name, declaration, backfilledType)`。

这说明 inferred local 的准确类型不在 `symbolBindings()` 中，也不是 declaration AST 上的属性，而是 late backfill 到真实 block scope 的 slot type。

### 3.3 member fact 在 backfill 前已经发布

`FrontendChainBindingAnalyzer` 处理 attribute expression 时会调用 chain reduction 并发布：

- `resolvedMembers.put(trace.step(), trace.suggestedMember())`
- `resolvedCalls.put(trace.step(), trace.suggestedCall())`

当 `tail` 的 `ScopeValue.type()` 仍为 `Variant` 时，`FrontendChainReductionHelper.reducePropertyStep(...)` 命中 `receiverType instanceof GdVariantType`，发布：

- `FrontendResolvedMember.status() == DYNAMIC`
- `receiverType == Variant`
- `resultType == null`
- detail reason 为 runtime-dynamic property access

之后即使 `FrontendExprTypeAnalyzer` 已经把 `tail` 回填为 exact `Point`，旧的 `resolvedMembers()` 仍保留第一次 chain binding 的 `DYNAMIC` 结果。

### 3.4 临时最小复现结论

本次使用临时 JUnit probe 验证了 issue #40 的核心脚本：

```gdscript
class_name TypedLocalPropertyWrite
extends RefCounted

class Point:
    var next: Point = null
    var marker: int = -1

func make_point() -> Point:
    return Point.new()

func write_path(point: Point) -> void:
    var tail := make_point()
    tail.next = point

func read_path() -> bool:
    var point := make_point()
    return point.marker != -1
```

观察结果：

- `make_point()` 的 `resolvedCalls()` 是 `RESOLVED`。
- `make_point()` 的 return type 是 `TypedLocalPropertyWrite__sub__Point`。
- initializer expression 的 `expressionTypes()` 是 `RESOLVED(Point)`。
- `tail.next` 的 `resolvedMembers()` 是 `DYNAMIC`，receiver type 是 `Variant`。
- `point.marker` 的 `resolvedMembers()` 也是 `DYNAMIC`，receiver type 是 `Variant`。
- diagnostics 为空。

这证明 #40 的根因不是 inner class 类型解析失败，而是 chain member fact 使用了回填前的 local slot type。

---

## 4. issue #41 成因链路

### 4.1 `DYNAMIC member` 的数据模型

`FrontendResolvedMember` 允许 `status == DYNAMIC`，但按模型约束：

- `resultType == null`
- `bindingKind == UNKNOWN`
- `detailReason` 必须存在

同时 `FrontendExpressionType` 对 `DYNAMIC` 的表达是：

- `status == DYNAMIC`
- `publishedType == Variant`

这意味着 dynamic member 的 runtime type surface 实际在 `expressionTypes()`，而不是 `resolvedMembers().resultType()`。

### 4.2 CFG builder 与 body lowering 的契约不一致

当前 CFG builder 的 `requireLoweringReadyMember(...)` 接受：

- `FrontendMemberResolutionStatus.RESOLVED`
- `FrontendMemberResolutionStatus.DYNAMIC`

但 body lowering 的 `FrontendBodyLoweringSession.requireResolvedMember(...)` 只接受：

- `FrontendMemberResolutionStatus.RESOLVED`

与此同时，`FrontendBodyLoweringSupport.collectCfgValueMaterializations(...)` 对 `MemberLoadItem` 调用 `requireMemberResultType(...)`，后者要求 `resolvedMembers().get(anchor).resultType() != null`。

因此，只要 dynamic member 进入 CFG，后续至少有两个失败点：

1. value materialization 因缺少 member result type 失败。
2. 如果绕过 materialization，body lowering 也会因 `requireResolvedMember(...)` 拒绝 `DYNAMIC` 失败。

### 4.3 临时最小复现结论

本次使用临时 JUnit probe 验证了 issue #41 的最小脚本：

```gdscript
class_name DynamicMemberLoweringProbe
extends RefCounted

func read_path(dynamic_host) -> bool:
    return dynamic_host.marker != -1

func write_path(dynamic_host, value) -> void:
    dynamic_host.next = value
```

观察结果：

- `dynamic_host.marker` 发布为 `DYNAMIC` member。
- `dynamic_host.marker` 的 expression type 是 `DYNAMIC(Variant)`。
- diagnostics 为空。
- 进入 `FrontendLoweringBodyInsnPass` 后，在 `FrontendBodyLoweringSupport.requireMemberResultType(...)` 抛出 `Missing published member result type for AttributePropertyStep`。

这证明 #41 是明确的 downstream contract drift：上游已经认可 dynamic member，CFG 也放行，但 body/materialization 尚未统一消费 dynamic member 的 runtime-open surface。

---

## 5. 方案评估

### 5.1 多遍 `chainBindingAnalyzer` / `exprTypeAnalyzer`

多遍执行可以直接修 #40 的表象：

1. 第一遍 chain binding 在 local 仍为 `Variant` 时可能发布 stale `DYNAMIC`。
2. 第一遍 expression typing 回填 local exact type。
3. 第二遍 chain binding 看到 backfilled local type，重新发布 `RESOLVED` member。
4. 第二遍 expression typing 基于最终 member/call facts 重建 expression types。

这个方案技术上可行，因为：

- `FrontendChainBindingAnalyzer.analyze(...)` 每次会创建新的 `resolvedMembers` 与 `resolvedCalls`，再 update 到 `analysisData`。
- `FrontendExprTypeAnalyzer.analyze(...)` 会 clear 并重建 `expressionTypes()`。

但它有两个风险：

- diagnostics 泄漏：第一遍 provisional 诊断如果已经写入 shared `DiagnosticManager`，第二遍 facts 修正后无法自动撤销。
- 收敛不透明：固定两遍可能不足以表达未来更复杂的类型传播，固定多遍也缺少清晰停止条件。

如果采用多遍，必须设计为 bounded fixed-point 或 provisional/final 两阶段，并明确第一阶段 diagnostics 不外泄。

### 5.2 简单 pre-chain inference pass

一个只在 chain binding 前扫描 `var x := initializer` 的简单 pass 可以修 `var x := make_point(); x.member` 这种直接场景。

但如果需求包括：

```gdscript
var a := complex.chain().value
var b := a
var c := b
c.member
```

简单 pass 不够。它必须同时具备：

- 按 source order 更新真实 `BlockScope`。
- 能推断复杂链式 initializer 的结果类型。
- 能让 `b` 立即读取已回填的 `a`，让 `c` 立即读取已回填的 `b`。

因此，真正需要的是 local type stabilization，而不是只看 initializer 表面形态的 pre-pass。

### 5.3 推荐方向：local type stabilization pass

推荐新增 `FrontendLocalTypeStabilizationPass` 或同等职责的 analyzer phase，放在：

```text
FrontendTopBindingAnalyzer
FrontendLocalTypeStabilizationPass
FrontendChainBindingAnalyzer
FrontendExprTypeAnalyzer
FrontendVarTypePostAnalyzer
```

其唯一持久副作用应是：

```text
BlockScope.resetLocalType(...)
```

它不应发布：

- `resolvedMembers()`
- `resolvedCalls()`
- `expressionTypes()`
- lowering-ready facts

它也不应发布最终 diagnostics。复杂 initializer 可以通过 provisional chain reduction 和 expression result 计算来获得类型，但这些结果只用于当前 pass 的 local slot backfill，不能进入正式 side table。

### 5.4 职责边界

推荐职责划分：

- `FrontendLocalTypeStabilizationPass`
  - owner of inferred local slot type backfill
  - 按 supported executable body 的 source order 处理 `var x := initializer`
  - 支持 local alias 链式传播
  - 支持复杂链式 initializer 的 provisional type calculation
  - 在 slot 写回边界显式拒绝 bare `TYPE_META` ordinary-value initializer，不能依赖 expression helper 返回 `FAILED` 来间接维持该合同
  - 不发布正式 facts
  - 不发最终 diagnostics

- `FrontendChainBindingAnalyzer`
  - owner of `resolvedMembers()` 与 `resolvedCalls()`
  - 在 local 类型已经稳定后发布最终 member/call facts
  - 发布最终 member/call diagnostics

- `FrontendExprTypeAnalyzer`
  - owner of `expressionTypes()`
  - 在 member/call facts 稳定后发布最终 expression facts
  - 发布最终 expression diagnostics
  - 不再作为 inferred local type 的 primary owner

若短期为了减少改动保留 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)`，它应降级为一致性检查或兜底：

- 同类型则 no-op。
- 不同类型应 fail-fast 或明确诊断。
- 不应静默覆盖 local stabilization pass 已经确定的类型。

---

## 6. 支持复杂链式 initializer 的设计要点

如果 initializer 是复杂 chain，例如：

```gdscript
var a := factory.make_point().next
var b := a
var c := b
c.marker
```

local type stabilization pass 需要有一个 provisional expression evaluator。它可以复用现有 helper，但必须保持临时性质。

### 6.1 必须按 source order 更新 scope

同一 block 内：

```gdscript
var a := make_point()
var b := a
var c := b
c.member
```

pass 应当按顺序执行：

1. 推断 `a` 为 `Point`，立即 reset local type。
2. 推断 `b` 时读取当前 scope 中的 `a: Point`，回填 `b: Point`。
3. 推断 `c` 时读取当前 scope 中的 `b: Point`，回填 `c: Point`。
4. 后续正式 chain binding 解析 `c.member` 时看到 `c: Point`。

### 6.2 必须复用现有 chain reduction 规则

不要重新实现一套 member/call resolver。可以考虑复用：

- `FrontendChainReductionFacade`
- `FrontendChainReductionHelper`
- `FrontendChainHeadReceiverSupport`
- `FrontendExpressionSemanticSupport` 中可复用的局部表达式规则

但 provisional reduction 的结果只能返回给 stabilization pass 自己，不写入 `analysisData.resolvedMembers()` / `resolvedCalls()`。

### 6.3 必须处理 diagnostics 事务

provisional pass 不应把中间诊断写入最终 `DiagnosticManager`。可选方式：

- 使用 scratch `DiagnosticManager` 并丢弃。
- 给 helper 提供 no-op diagnostic sink。
- 将本 pass 的失败统一表达为“无法稳定推断，保持 Variant”，由最终 analyzer 负责正式诊断。

### 6.4 必须 fail-closed

遇到以下情况时，第一版应保持 `Variant`，不要猜：

- lambda / capture 相关表达式
- `for` / `match` 等当前 frontend MVP 外结构
- unsupported subtree
- value-required `void`
- 无法稳定解析的 call/member/subscript
- 依赖 control-flow merge 的 local 类型
- initializer 有 assignment side effect 且当前没有明确合同

### 6.5 block 与 control flow 边界

建议第一版只承诺：

- 同一 supported executable block 内 source-order local inference。
- 子 block 可以读取父 block 已稳定 local 类型。
- 子 block 的 local 推断不反写父 block。
- 不做跨 `if` / `while` 的类型 merge。

如果未来要支持 control-flow aware local type refinement，应另起 CFG/dataflow 合同，不要塞进该 pass 的 MVP。

---

## 7. 与现有 analyzer 的冲突风险

### 7.1 与 `FrontendChainBindingAnalyzer` 的冲突

冲突风险来自复杂 initializer 的 provisional chain reduction。

如果 stabilization pass 发布 `resolvedMembers()` 或 `resolvedCalls()`，就会和 `FrontendChainBindingAnalyzer` 抢 side-table owner。

规避方式：

- stabilization pass 只做 provisional reduction。
- 不写 `analysisData.resolvedMembers()`。
- 不写 `analysisData.resolvedCalls()`。
- 不发 `sema.member_resolution` / `sema.call_resolution` 最终 diagnostics。
- 最终 side table 仍由 `FrontendChainBindingAnalyzer` 在 local type 稳定后统一发布。

### 7.2 与 `FrontendExprTypeAnalyzer` 的冲突

当前 `FrontendExprTypeAnalyzer` 同时承担 expression type publication 与 inferred local backfill。新增 stabilization pass 后，这会变成双 owner。

规避方式：

- 将 inferred local backfill 的 primary ownership 迁移到 stabilization pass。
- `FrontendExprTypeAnalyzer` 只发布 `expressionTypes()`。
- 短期保留旧 backfill 时，应改为 no-op/一致性检查，而非静默覆盖。

### 7.3 与 diagnostics 的冲突

provisional pass 如果发诊断，会造成：

- 重复 diagnostics。
- 第一遍基于未稳定 facts 的错误在最终 facts 修正后仍残留。
- compile gate 可能被 provisional error 错误阻断。

规避方式：

- provisional pass 默认不发最终 diagnostics。
- 所有正式 diagnostics 仍由 chain binding、expression typing、type check、compile check 发布。

### 7.4 与 #41 的冲突

即使 #40 修复后，#41 仍应单独处理，因为真实 dynamic receiver 仍可能合法存在：

```gdscript
func read_path(dynamic_host):
    return dynamic_host.marker
```

修 #40 只能减少误降级的 dynamic member；不能定义真正 dynamic member 的 lowering contract。

---

## 8. issue #41 的解决方向

对于真实 `DYNAMIC member`，必须选择一个一致合同。

### 8.1 方案 A：允许 lowering dynamic member

如果维持文档中 `DYNAMIC` 不是 blocker 的方向，则应统一支持 dynamic member lowering：

- value materialization 对 `MemberLoadItem` 从 member anchor 的 `expressionTypes()` 读取 `Variant`。
- body lowering 对 dynamic member read 生成 runtime named get surface，例如 `VariantGetNamedInsn` 或等价 route。
- dynamic member write 生成 runtime named set surface，例如 `VariantSetNamedInsn` 或等价 route。
- `resolvedMembers().resultType()` 只作为 exact `RESOLVED` member 的静态结果类型，不再是 dynamic member 的类型真源。

优点：

- 与 dynamic call 的既有合同更一致。
- 与 `FrontendExpressionType.dynamic(...)` 的 runtime-open surface 更一致。
- 符合 compile gate 不把 `DYNAMIC` 当 blocker 的文档约束。

风险：

- 需要明确 `VariantGetNamedInsn` / `VariantSetNamedInsn` 在 LIR 与 backend 的 ownership、packing、runtime error 行为。
- property write / writable route / reverse commit 可能需要额外路径。

### 8.2 方案 B：不允许 lowering dynamic member

如果暂时不希望支持 dynamic named member access，则应在早期统一拒绝：

- compile gate 或 CFG builder 对 `DYNAMIC member` 明确 fail-fast。
- 错误消息应指向同一边界，而不是让 body/materialization/backend 分别失败。
- 文档需要同步修改，说明 `DYNAMIC member` 与 `DYNAMIC call` 支持面不同。

优点：

- 实施面小。
- 可避免尚未稳定的 runtime named access 路径。

风险：

- 与当前 `DYNAMIC` 不是 compile blocker 的总合同冲突。
- 真实 dynamic GDScript surface 会继续无法 lowering。
- 后续仍要补 runtime-open member access。

### 8.3 当前建议

建议优先修 #40，减少错误 dynamic member 的产生；随后为 #41 单独建立 dynamic member lowering contract。

不要用 #41 的 downstream workaround 掩盖 #40。否则 typed local alias precision loss 仍会让本应静态的路径走 runtime dynamic，造成性能、类型和 backend 行为偏差。

---

## 9. 建议测试

### 9.1 #40 frontend facts 测试

新增 focused frontend semantic tests，覆盖：

```gdscript
class_name TypedLocalPropertyWrite
extends RefCounted

class Point:
    var next: Point = null

func make_point() -> Point:
    return Point.new()

func ping(point: Point) -> void:
    var tail := make_point()
    tail.next = point
```

断言：

- initializer call 是 `RESOLVED`。
- initializer expression 是 exact `Point`。
- `tail.next` 的 `resolvedMembers()` 是 `RESOLVED`。
- `tail.next` 的 receiver type 是 canonical `Point`。
- 不产生 member/expression diagnostics。

### 9.2 #40 read path 测试

覆盖：

```gdscript
class Point:
    var marker: int = -1

func make_point() -> Point:
    return Point.new()

func ping() -> bool:
    var point := make_point()
    return point.marker != -1
```

断言：

- `point.marker` 的 `resolvedMembers()` 是 `RESOLVED`。
- `point.marker` 的 expression type 是 `int`。
- binary comparison result 是 `bool`，不是经由 `Variant` 漂移。

### 9.3 链式 local alias 测试

覆盖：

```gdscript
func ping() -> int:
    var a := make_point()
    var b := a
    var c := b
    return c.marker
```

断言：

- `a` / `b` / `c` 对应 local slot type 都稳定为 `Point`。
- `c.marker` 是 `RESOLVED` member。

### 9.4 复杂 initializer 测试

覆盖：

```gdscript
class Box:
    var point: Point

func make_box() -> Box:
    return Box.new()

func ping() -> int:
    var p := make_box().point
    var alias := p
    return alias.marker
```

断言：

- provisional stabilization 能从复杂 chain initializer 推断 `p: Point`。
- `alias.marker` 在最终 chain binding 中是 `RESOLVED`。

### 9.5 #41 dynamic member contract 测试

如果选择支持 dynamic member lowering，应覆盖：

```gdscript
func read_path(dynamic_host) -> bool:
    return dynamic_host.marker != -1

func write_path(dynamic_host, value) -> void:
    dynamic_host.next = value
```

断言取决于最终 contract：

- 支持 lowering：应生成 runtime named get/set 相关 LIR，并在 comparison 处保持明确 `Variant -> bool` 或 runtime evaluation contract。
- 不支持 lowering：compile gate 应在同一边界给出明确 diagnostic，不应进入 body lowering 后抛内部异常。

---

## 10. 当前建议落地顺序

建议分两步实施：

1. 实施 local type stabilization，先解决 #40 与链式 local alias 推断。
2. 单独修 #41，统一 dynamic member 的 lowering-ready contract。

第一步的最小成功标准：

- `:=` local alias 的 exact initializer 类型在正式 chain binding 前稳定到 `BlockScope`。
- source-order local alias 链能传播 exact type。
- 复杂 chain initializer 可以在 provisional 模式下计算 stable type。
- 不引入重复 diagnostics。
- `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` 的最终 owner 不改变。

第二步的最小成功标准：

- CFG、value materialization、body lowering、backend 对 `DYNAMIC member` 采用同一个合同。
- 不再出现 CFG 放行但 body/materialization/backend 各自用不同错误失败的情况。

---

## 11. `FrontendLocalTypeStabilizationPass` 深入调查：API 复用、依赖边界与职责重分配

本节记录 2026-06-17 追加调查结论，重点回答三个问题：

1. 若新增 `FrontendLocalTypeStabilizationPass`，实现不动点迭代 local 类型分析时，现有 API 是否可复用。
2. 复杂 initializer 中的 chain/call、调用参数类型、local alias 链是否需要后续 pass 已发布 facts。
3. 新 pass 如何避免与 `FrontendChainBindingAnalyzer`、`FrontendExprTypeAnalyzer` 产生职责冲突。

### 11.1 当前 phase 事实与冲突来源

当前 `FrontendSemanticAnalyzer.analyze(...)` 的 shared semantic 顺序仍是：

```text
FrontendScopeAnalyzer
FrontendVariableAnalyzer
FrontendTopBindingAnalyzer
FrontendChainBindingAnalyzer
FrontendExprTypeAnalyzer
FrontendVarTypePostAnalyzer
diagnostics-only analyzers
```

其中每个阶段后都会执行：

```java
analysisData.updateDiagnostics(diagnosticManager.snapshot());
```

这意味着任何 analyzer 在 provisional 过程中写入共享 `DiagnosticManager` 的诊断，都会被固化成正式 frontend 事实，后续阶段即使修正 side table 也无法自然撤回。这个事实决定了新 pass 不能通过“先跑一遍正式 analyzer，再覆盖结果”的方式实现。

当前各阶段的事实所有权如下：

- `FrontendVariableAnalyzer`：负责把 parameter/local declaration 放入 scope inventory。对 `:=` local，`bindLocal(...)` 通过 `FrontendDeclaredTypeSupport.resolveTypeOrVariant(...)` 先把 slot 类型种成 `Variant`。
- `FrontendTopBindingAnalyzer`：负责 `symbolBindings()` 的 use-site 绑定分类，不解析 member/call chain。
- `FrontendChainBindingAnalyzer`：负责发布 `resolvedMembers()` 和 chain-step `resolvedCalls()`。其 `analyze(...)` 创建新 side table 后通过 `analysisData.updateResolvedMembers(...)` 与 `analysisData.updateResolvedCalls(...)` 整表发布；`publishReduction(...)` 会写 `resolvedMembers.put(...)` / `resolvedCalls.put(...)` 并发 member/call diagnostics。
- `FrontendExprTypeAnalyzer`：负责发布 `expressionTypes()`，并对 bare `CallExpression` 补写 `resolvedCalls()`。但它当前还在 `backfillInferredLocalType(...)` 中调用 `BlockScope.resetLocalType(...)`，这就是“expression type owner 顺带拥有 local slot backfill”的职责混杂点。
- `FrontendVarTypePostAnalyzer`：不做新推断，只把当前 `CallableScope` / `BlockScope` 中已经稳定的 slot 类型重新发布为 `slotTypes()`，供 lowering 消费。

因此 #40 的实际冲突链路是：

```text
VariableAnalyzer: var tail := make_point() -> tail slot = Variant
ChainBindingAnalyzer: tail.next sees tail: Variant -> resolvedMembers[tail.next] = DYNAMIC
ExprTypeAnalyzer: make_point() expression = Point -> resetLocalType(tail, Point)
later consumers: resolvedMembers[tail.next] remains stale DYNAMIC
```

新增 `FrontendLocalTypeStabilizationPass` 后，真正要解决的不是“多算一次类型”，而是把 `BlockScope local slot` 的最终 backfill 从 `FrontendExprTypeAnalyzer` 中迁出，让 `FrontendChainBindingAnalyzer` 在第一次正式发布 member/call facts 时就消费稳定后的 local slot。

### 11.2 不动点 pass 需要的能力与现有 API 可复用性

`FrontendLocalTypeStabilizationPass` 若要支持 local alias 链与复杂 initializer，至少需要这些能力：

- 枚举 supported executable block 内的 local `var` declaration。
- 判断 declaration 是否为 `:=` inferred local。
- 读取 initializer AST。
- 对 initializer 做 silent expression type evaluation。
- 对 initializer 中的 attribute chain 做 provisional chain reduction。
- 对 chain/call 中的 argument expression 求类型，并用于 overload selection。
- 按 source order 或 fixed-point 结果写回 `BlockScope` local slot。

现有代码中可复用的 API 分三类。

第一类是可以直接复用的窄副作用 API：

- `BlockScope.resolveValueHere(...)`：读取当前 block layer 的 local slot，无发布副作用。
- `BlockScope.resetLocalType(String name, Object declaration, GdType type)`：重写同一 declaration identity 的已有 `LOCAL` slot。它只改 `BlockScope.valuesByName`，不写 `analysisData`，不发 diagnostics。
- `FrontendDeclaredTypeSupport.isInferredTypeRef(...)`：判断 `VariableDeclaration.type()` 是否是 `:=` inferred 类型。
- `FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(...)`：复用当前 supported executable block surface，避免新 pass 打开未承诺的 lambda/for/match 等区域。

第二类是可以复用的纯计算语义内核：

- `FrontendExpressionSemanticSupport`：文件注释明确说明它返回纯 semantic results，不发布 side table，不发 diagnostics，并把 nested expression resolution 委托给调用方。其 `resolveCallExpressionType(...)`、`resolveSubscriptExpressionType(...)`、`resolveUnaryExpressionType(...)`、`resolveBinaryExpressionType(...)` 等可作为 silent evaluator 的基础。
- `FrontendChainReductionHelper.reduce(...)`：通过 `ReductionRequest` 接收 `ExpressionTypeResolver` 与 `NoteSink`，返回 `ReductionResult` / `StepTrace` / suggested member/call。helper 本身不写 `analysisData`，是否发诊断由调用方处理 notes 和 traces 决定。
- `FrontendChainReductionFacade.reduce(...)`：可作为本 pass 内部 cache 壳使用；它只缓存 reduction result，不发布正式 side table。
- `FrontendVariantBoundaryCompatibility` 与 `FrontendCallableOverloadRankingSupport`：通过 `FrontendExpressionSemanticSupport` 的 overload selection 路径间接复用，避免新建第二套调用匹配规则。

第三类是只能借鉴、不能直接调用的 phase 实现：

- `FrontendChainBindingAnalyzer.analyze(...)`：会整表发布 `resolvedMembers()` / `resolvedCalls()`，并发 `sema.member_resolution` / `sema.call_resolution` 等正式 diagnostics。
- `FrontendChainBindingAnalyzer.reduceAttributeExpression(...)` / `publishReduction(...)`：cache miss 时会发布 suggested member/call，并报告 trace/recovery diagnostics。
- `FrontendExprTypeAnalyzer.analyze(...)` / `publishExpressionType(...)` / `publishResolvedExpressionType(...)`：会清空并重建 `expressionTypes()`。
- `FrontendExprTypeAnalyzer.publishBareResolvedCall(...)`：会写正式 `resolvedCalls()`。
- `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)`：当前会写 `BlockScope`，但它和 `expressionTypes()` publication 紧耦合，新增 pass 后应迁移或弱化。
- `FrontendVarTypePostAnalyzer.publishLocalSlotType(...)`：只是 republish 当前 scope slot 到 `slotTypes()`，不是推断 API。

因此可行实现应是：新增一个 silent local-stabilization walker，复用上述纯计算 support 与 `BlockScope.resetLocalType(...)`，但不复用任何 analyzer 的 `analyze(...)` / `publish*` / `report*` 入口。

### 11.3 复杂 initializer 与调用参数的可得事实

复杂 initializer 可以分成两层事实：

1. initializer 表达式本身能不能被当前 semantic helper 求出类型。
2. initializer 求出的类型能不能在正式 chain binding 前写入 local slot，供后续 local use-site 消费。

第一层大部分能力已经存在。比如：

```gdscript
var p := factory.make_point(arg).next
```

求型需要的链路是：

```text
factory receiver type
-> make_point(...) attribute call reduction
-> argument `arg` expression type
-> overload selection
-> call return type
-> `.next` property member result type
-> initializer expression type
```

`FrontendChainReductionHelper.resolveArgumentTypes(...)` 已支持 call/subscript argument 求型：它先查 `analysisData.expressionTypes()`，若没有已发布结果，则调用 `ReductionRequest.expressionTypeResolver()`；遇到 deferred argument 时，还允许一次 `finalizeWindow` retry。`FrontendExpressionSemanticSupport.resolveCallExpressionType(...)` 也已经通过 nested resolver 获取 argument `publishedType()`，并用 `selectCallableOverload(...)` 选择 overload。

这说明新 pass 不需要等 `FrontendExprTypeAnalyzer` 发布 `expressionTypes()` 才能求 initializer 类型；它可以提供自己的 silent nested resolver。关键是该 resolver 不能把中间结果写入 `analysisData.expressionTypes()`，只能在本 pass 内部缓存。

第二层是当前缺失的能力。比如：

```gdscript
var p := factory.make_point(arg).next
var q := p
q.member
```

即使 `factory.make_point(arg).next` 的 provisional type 可以算出 `Point`，当前 pipeline 也不会在 `FrontendChainBindingAnalyzer` 前把 `p: Point` 写入 `BlockScope`。因此 `q := p` 仍可能读到 `p: Variant`，`q.member` 继续被发布为 stale dynamic member。

新 pass 的核心价值就是把第一层 initializer type result 立即转成第二层 local slot fact：

```text
infer p initializer -> resetLocalType(p, Point)
infer q initializer -> resolve q RHS identifier p -> reads p: Point -> resetLocalType(q, Point)
final ChainBindingAnalyzer -> q.member sees q: Point -> RESOLVED member
```

### 11.4 三类示例链路

可以稳定推断的场景：

```gdscript
func ping(value: Point) -> int:
    var a := value
    return a.marker
```

参数 `value: Point` 在 `FrontendVariableAnalyzer.bindParameter(...)` 后已经进入 `CallableScope`，`ScopeValue.type()` 稳定为 `Point`。新 pass 的 initializer resolver 对 `value` use-site 求型时，可以通过现有 symbol binding 与 scope lookup 得到 `Point`，然后 `resetLocalType(a, Point)`。正式 chain binding 处理 `a.marker` 时就不再看到 `a: Variant`。

`2026-06-20`：仓库已为这条 parameter alias 链路补齐 focused tests，分别覆盖 local stabilization
write-back 与 shared semantic pipeline 下的 member / return type 闭环。

需要 source-order 或 fixed-point 稳定的场景：

```gdscript
func ping() -> int:
    var a := make_point()
    var b := a
    var c := b
    return c.marker
```

`a` 的 initializer 可能通过 bare call overload selection 得到 `Point`。`b` 的 initializer 依赖 `a` 的 slot 已回填；`c` 的 initializer 依赖 `b` 的 slot 已回填。对这个单 block 且无前向引用的例子，source-order 单遍已经足够；若未来支持更复杂嵌套、deferred retry 或同一 block 内需要多次 revisit 的表达式，则应使用 bounded fixed-point，但仍只写 `BlockScope`。

复杂 initializer 且有参数调用的场景：

```gdscript
func ping(factory: Factory, arg: BuildArg) -> int:
    var p := factory.make_point(arg).next
    var q := p
    return q.marker
```

这里 `factory.make_point(arg).next` 需要 provisional chain reduction 和 argument type resolution。现有 `FrontendChainReductionHelper` 与 `FrontendExpressionSemanticSupport` 足以复用 resolver 内核；新 pass 需要补的是 silent evaluator 的调用组织、缓存和 fail-closed 策略。

不应在 pre-chain 稳定推断的场景：

```gdscript
func ping(dynamic_host):
    var p := dynamic_host.next
    return p.member
```

`dynamic_host` 本身是真实 `Variant` / dynamic receiver。新 pass 最多把 `p` 维持为 `Variant`，不应猜测 `.next` 的静态类型。正式 chain binding 仍应发布 `DYNAMIC` member，后续由 #41 的 dynamic member lowering contract 处理。

另一个不应强行推断的场景是当前 frontend MVP 外结构：

```gdscript
func ping(items):
    for item in items:
        var p := item.next
        return p.marker
```

`for` / `match` / lambda / capture / unsupported subtree / control-flow merge 都不应被 local stabilization MVP 打开。遇到这些边界应保持 `Variant` 或跳过，让最终 analyzer 继续按既有规则发 diagnostics。

### 11.5 是否存在必须等待后续 pass 的事实

需要区分“正式已发布事实”和“可 provisional 计算事实”。

不需要等待后续 pass 的事实：

- parameter/local/property use-site 的 symbol binding：`FrontendTopBindingAnalyzer` 后已发布。
- parameter type：`FrontendVariableAnalyzer` 后已在 `CallableScope`。
- 显式 local type：`FrontendVariableAnalyzer` 后已在 `BlockScope`。
- 函数 overload set：可通过当前 scope 的 `resolveFunctions(...)` 查询。
- 大部分 literal、identifier、unary/binary、bare call、attribute chain initializer 类型：可用 `FrontendExpressionSemanticSupport` 与 `FrontendChainReductionHelper` provisional 计算。

必须等后续 pass 才有的正式发布事实：

- `resolvedMembers()` / chain-step `resolvedCalls()`：正式 owner 是 `FrontendChainBindingAnalyzer`。
- `expressionTypes()`：正式 owner 是 `FrontendExprTypeAnalyzer`。
- bare `CallExpression` 的正式 `resolvedCalls()`：当前由 `FrontendExprTypeAnalyzer.publishBareResolvedCall(...)` 补写。
- lowering-ready `slotTypes()`：由 `FrontendVarTypePostAnalyzer` 在 local slot 稳定后 republish。

这并不阻止新 pass 求 initializer type。它只说明新 pass 不能依赖这些后续 side table 已存在，也不能提前发布它们。新 pass 应拥有自己的 transient expression cache / chain reduction cache，并把失败统一降级为“本轮无法稳定该 local，保留现有 slot type”。

### 11.6 与 `chainBindingAnalyzer`、`exprTypeAnalyzer` 的冲突解决

推荐职责重分配如下：

```text
FrontendVariableAnalyzer
  owner: declaration inventory seed
  write: CallableScope / BlockScope seed slot
  note: inferred local seed remains Variant

FrontendLocalTypeStabilizationPass
  owner: inferred local final slot backfill before chain binding
  read: scopesByAst, symbolBindings, moduleSkeleton, classRegistry, current scopes
  write: BlockScope.resetLocalType(...)
  no write: resolvedMembers, resolvedCalls, expressionTypes, slotTypes
  no final diagnostics

FrontendChainBindingAnalyzer
  owner: resolvedMembers and chain-step resolvedCalls
  read: already stabilized local slots
  write: resolvedMembers, resolvedCalls
  diagnostics: member/call/chain diagnostics

FrontendExprTypeAnalyzer
  owner: expressionTypes and bare-call resolvedCalls
  read: final member/call facts
  write: expressionTypes, bare-call resolvedCalls
  no longer primary owner: inferred local backfill

FrontendVarTypePostAnalyzer
  owner: slotTypes republish
  read: final CallableScope / BlockScope slots
  write: slotTypes
```

核心原则是：允许重复计算，不允许重复写事实。

新 pass、chain binding、expr typing 都可以复用同一套 pure semantic kernel，也都可以在内部临时计算表达式类型；但只有一个阶段能发布某张 side table。具体边界是：

- `FrontendLocalTypeStabilizationPass` 只能把 stable initializer result 转成 `BlockScope.resetLocalType(...)`。
- `FrontendChainBindingAnalyzer` 只能把正式 chain reduction result 转成 `resolvedMembers()` / chain-step `resolvedCalls()`。
- `FrontendExprTypeAnalyzer` 只能把正式 expression result 转成 `expressionTypes()`，并继续拥有 bare-call `resolvedCalls()`。
- `FrontendVarTypePostAnalyzer` 只能把最终 scope slot republish 成 `slotTypes()`。

`FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 应被迁移或弱化：

- 推荐做法：删除该 backfill 写入职责，把逻辑迁移到新 pass。
- 过渡做法：保留为一致性检查或 no-op fallback；如果发现 initializer final type 与已稳定 slot type 不一致，应 fail-fast 或发明确 diagnostic，而不是静默覆盖。
- 不推荐做法：让 expr analyzer 继续无条件 `resetLocalType(...)`，这会形成双 owner，并可能在正式 chain facts 发布后再次改变 local slot。

### 11.7 不动点实现建议与收敛边界

第一版可以采用“source-order + bounded fixed-point”的保守结构：

1. 遍历 supported executable block，收集 `DeclarationKind.VAR` 且 `type()` 为 inferred 的 `VariableDeclaration`。
2. 按 block 内 source order 尝试求 initializer type。
3. 若 initializer type 为 `RESOLVED`，且不是 `void` / unsupported / failed，则写回 local slot。
4. 若本轮任一 local 从 `Variant` 变成更精确类型，则继续下一轮。
5. 达到无变化或固定上限后停止。

收敛规则应保持单调：

- 只允许从 `Variant` 种子收敛到更稳定的 exact type。
- 不建议在 MVP 中允许 exact type 之间来回覆盖。
- `DYNAMIC(Variant)` 不应被当作“更精确”，除非现有 slot 不是 `Variant` 且有明确一致性合同；第一版建议保持 `Variant`。
- `FAILED` / `UNSUPPORTED` / `DEFERRED` / `BLOCKED` 不写回。

这个规则会牺牲少量激进推断，但能避免非单调 fixed-point 和 provisional diagnostics 泄漏。

### 11.8 与 #41 的关系

`FrontendLocalTypeStabilizationPass` 只能解决“假 dynamic”：

```gdscript
var p := make_point()
return p.marker
```

在修复后，`p` 应在正式 chain binding 前稳定为 `Point`，`p.marker` 应发布为 `RESOLVED` member。

它不能解决“真 dynamic”：

```gdscript
func read_path(dynamic_host) -> bool:
    return dynamic_host.marker != -1
```

这类 receiver 本来就是 `Variant` / dynamic。正式 chain binding 发布 `DYNAMIC member` 是合理结果。当前 #41 的根因仍在 downstream contract：

- `FrontendCfgGraphBuilder.requireLoweringReadyMember(...)` 接受 `RESOLVED | DYNAMIC`。
- `FrontendBodyLoweringSession.requireResolvedMember(...)` 仍只接受 `RESOLVED`。
- `FrontendBodyLoweringSupport.requireMemberResultType(...)` 仍从 `resolvedMembers().resultType()` 取类型，而 `FrontendResolvedMember.DYNAMIC` 按模型没有 `resultType`。

因此 #41 后续仍应单独修。建议 member lowering 向 call lowering 已采用的合同靠齐：

- route / provenance 由 `resolvedMembers()` 表达。
- value result type 由 `expressionTypes()` 表达。
- dynamic member read/write 通过 runtime named get/set route lowering。
- 若短期不支持 dynamic member lowering，则 compile gate 或 CFG boundary 必须统一拒绝，不能让 CFG 放行后在 body/materialization 抛内部异常。

### 11.9 更新后的结论

新增 `FrontendLocalTypeStabilizationPass` 不必与 `FrontendChainBindingAnalyzer` / `FrontendExprTypeAnalyzer` 冲突，前提是严格重分配职责：

- 它不是“提前跑一遍 chain binding”。
- 它不是“提前发布 expression types”。
- 它是一个只服务于 `BlockScope` local slot 的 silent pre-chain inference phase。

在这个边界下，复杂 initializer、调用参数、local alias 链都可以通过现有纯语义 helper 复用实现；需要新增的是 pass 自身的 source-order/fixed-point walker、transient cache 和 fail-closed 策略，而不是第二套 member/call/type resolver。
