# Frontend Dynamic Call Lowering Implementation

> Updated: 2026-04-11
>
> 本文档是 frontend `DYNAMIC` method-call lowering 的事实源。
> 不再记录阶段性步骤、完成进度或实施流水账；若合同变化，应直接改写当前状态。

## 1. 维护合同

- 本文档覆盖 shared semantic、compile gate、CFG builder、body lowering 与 backend 之间关于 dynamic method call 的长期合同。
- 本文档只描述“当前已冻结并由代码实现承担”的事实，不描述历史修复步骤。
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_rules.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - 与 call / chain / compile-check / typed-boundary 直接相关的代码注释

---

## 2. 当前支持面

frontend 当前正式支持的 dynamic call surface 是：

- `FrontendResolvedCall(status = DYNAMIC, callKind = DYNAMIC_FALLBACK)`
- receiver route 必须是 instance-style runtime value
- call lowering 继续复用现有 `CallItem` 与 `CallMethodInsn`
- 这条复用合同只冻结 call instruction surface，不再把 receiver 语义冻结成“永久 direct pass-through”

这条路由覆盖的典型来源包括：

- 未声明类型或显式 `Variant` receiver 上的方法调用
- runtime-open object receiver 上的方法调用
- `PackedInt32Array`、`Array`、`Dictionary` 等运行时值先流入无类型变量，再调用成员方法

典型示例：

```gdscript
func ping(v):
    return v.size()
```

```gdscript
func ping(worker):
    return worker.ping()
```

以下内容不属于当前合同：

- type-meta / static / global dynamic fallback
- callable-value invocation
- 新增 dynamic-call 专用 LIR 指令

---

## 3. Published Fact 合同

### 3.1 Call route 真源

`analysisData.resolvedCalls()` 发布的是 callable route fact，而不是 call result type 的唯一真源。

对 dynamic call 来说，这张表冻结的是：

- 该调用已经被 frontend 接受为 runtime-open route
- callable name 已冻结
- receiver family 已冻结

它不承担以下职责：

- 为 `DYNAMIC` call 提供 exact callable signature
- 作为 `DYNAMIC` call result type 的唯一真源

### 3.2 Call result type 真源

call result type 的正式真源是 call anchor 对应的 `analysisData.expressionTypes()`。

因此：

- `RESOLVED` call 继续发布 exact result type
- `DYNAMIC` call 继续发布 `Variant`
- body lowering 不得回退为从 `resolvedCall.returnType()` 为 `DYNAMIC` call 二次推导结果类型

### 3.3 Receiver writable access-chain 真源

本文件冻结的是 dynamic call 的 route / result-type / argument-boundary 合同，但不再把 receiver 一侧硬编码成“总是扁平 slot 直通”。

当某个 dynamic instance call 还同时参与 mutating receiver writeback 时：

- CFG builder 必须继续复用同一个 `CallItem`
- writable receiver access chain 必须作为同一个 `CallItem` 上的单个 frozen payload 发布
- body lowering 必须整体消费这条 frozen chain 做 leaf selection 与 post-call commit
- body lowering 不得把同一条 receiver chain 再拆成额外的 per-step item，也不得回头重跑 AST receiver 解释
- 当前实现已经支持 `CallItem` 直接发布 writable receiver payload
- exact `RESOLVED` instance route 现已冻结为：
  - `CallMethodInsn.objectId` 优先复用 CFG 已发布的 receiver value slot
  - payload-backed call 若缺失 dedicated `receiverValueIdOrNull`，属于 publication invariant violation；body lowering 不会再回退成按 payload 临时重读 receiver leaf
  - 同一个 payload 仅负责 exact route 的 post-call reverse commit
  - Step 6 现已落地：direct-slot mutating receiver 会直接发布 alias-backed receiver value，因此 exact route 继续只消费 dedicated `receiverValueIdOrNull`，而不是再由 call lowering 额外解释“synthetic CFG temp -> 真实源 slot”
  - 与之对应，non-mutating / runtime-open 的 direct-slot receiver 继续停留在 ordinary temp-backed value surface；frontend 不会把 alias publication 泛化到所有 identifier/self ordinary read
  - 这里的 direct-slot publication surface 只包含 explicit `SelfExpression` 与 `IdentifierExpression + LOCAL_VAR/PARAMETER`；`CAPTURE` 目前仍留在 deferred lambda/capture 语义范围内，`receiverValueIdOrNull == null` 时由 `resolveInstanceCallReceiver(...)` fallback 到 `self` 的 implicit self receiver 仍属于 call execution fallback，不属于 alias publication
  - `IdentifierExpression + SELF` 不是合法的 published receiver surface：当前 analyzer 只会对 explicit `SelfExpression` 发布 `SELF`，所以 builder 与 body lowering 遇到它都必须 fail-fast，而不是再把 identifier 静默恢复成 `"self"`
  - 对 identifier-backed direct-slot alias，builder 现在额外要求：后续 arguments 必须停留在 proven no-rebinding 子集；若参数包含 nested `CallExpression` / `AttributeCallStep` 或其它当前尚未证明安全的 effect-open surface，则回退 ordinary temp snapshot，而不是继续发布 live-slot alias
  - explicit `SelfExpression` 不受这条参数分类限制，因为它的稳定性来自 `self` slot 本身不可被用户代码重绑定
  - `CAPTURE` 当前也不参与这条 identifier-backed alias 分类：在 lambda/capture lowering 真正落地前，这类 binding 若意外进入 alias path 必须直接 fail-fast
- mutating dynamic instance route 现在也正式纳入 receiver-side writeback 合同：
  - `FrontendCallMutabilitySupport` 对 `DYNAMIC_FALLBACK + INSTANCE` 保守返回 may-mutate
  - 因此 direct-slot dynamic receiver 现在与 exact mutating route 一样，可以在满足 Step 6 alias eligibility 时直接发布 alias-backed receiver value，而不是退回 dead temp snapshot
  - property/subscript receiver 继续通过同一个 payload 提供 leaf provenance 与 reverse-commit step
- 对“显式声明为 `Variant` 的实例属性/字段值继续链式调用 mutating method”这一条路线，
  当前行为已经冻结为 property-backed dynamic receiver writeback，而不是 plain snapshot：
   - 示例：
     - `self.payloads.push_back(seed)`
     - `box.payloads.push_back(seed)`
   - CFG builder 先发布 property leaf route，再把同一个 payload 挂到 `CallItem`
    - body lowering 形状固定为：
      - ordinary property read 先发 `LoadPropertyInsn(payloads, read_owner)`
      - `CallMethodInsn(push_back, loaded_variant_slot, ...)`
      - `CallGlobalInsn("gdcc_variant_requires_writeback", loaded_variant_slot)`
      - `GoIfInsn`
      - apply 分支上的 `StorePropertyInsn(payloads, commit_owner, loaded_variant_slot)`
      - continuation block
    - 其中 `read_owner` 与 `commit_owner` 当前不必相同：
      - `self.payloads.push_back(seed)`：二者都为 `self`
      - `box.payloads.push_back(seed)`：ordinary `MemberLoadItem` 仍先通过 `box` 的已发布 receiver value 做 property read；
        若该 base value 还是 temp-backed ordinary read，则 entry `LoadPropertyInsn` 会读 `cfg_tmp_*`，但 payload root
        仍保留 direct-slot owner `box`，所以 runtime-gated `StorePropertyInsn` 会写回 `box`
    - 这条差异是当前 CFG/body-lowering 的刻意分层，而不是偶发漂移：
      - ordinary property read 继续由 `MemberLoadItem(baseValueId)` 表达
      - writable receiver payload 只冻结 reverse-commit owner/leaf 语义，不反向改写前面的 ordinary read surface
    - 这里不会额外插入 ordinary typed-boundary `PackVariantInsn` / `UnpackVariantInsn`，
      因为 property carrier 本身就已经发布为 `Variant`
    - runtime gate 只回答当前 `Variant` carrier 这一层是否需要 writeback；
      若 outer owner 仍有更外层 route，则外层是否继续 writeback 仍由 shared writable-route support
      按静态 family / 下一层 carrier 继续判断
- shared writable-route support 现已同时提供：
  - 静态 gate 入口 `reverseCommit(..., ReverseCommitGateHook)`
  - 动态 gate 入口 `reverseCommitWithRuntimeGate(...)`
  dynamic call path 现在已经正式复用后者；若后续扩面，仍不得自建第二套 per-layer branch 拼装逻辑
- 当前 dynamic mutating call 的 post-call commit 合同固定为：
  - 先发普通 `CallMethodInsn`
  - 若 receiver route 未发布 writable payload，lowering 到此结束
  - 若 receiver route 已发布 writable payload，则 shared support 先对静态已知 family 走 fast-path/fast-skip
  - 仅当 current carrier 是 runtime-open `Variant` 时，body lowering 才追加 `CallGlobalInsn("gdcc_variant_requires_writeback", ...) + GoIfInsn`
  - 后续 sequence item 必须继续附着到 `reverseCommitWithRuntimeGate(...)` 返回的 continuation block，而不是原 lexical block
- 为承接这条 continuation-block 合同，body lowering 的 processor / registry / sequence-item 调度面也必须显式 thread 当前 block；call lowering 不得再假设“所有后续 instruction 永远继续附着在原始 sequence block 上”

call-route / dispatch 的长合同仍以本文档为准；receiver-side writable chain / writeback 的长合同由 `frontend_complex_writable_target_plan.md` 约束。

---

## 4. Lowering 合同

### 4.1 Lowering-ready call 状态

compile gate、CFG builder 与 body lowering 对 call 的 lowering-ready surface 必须保持一致：

- `RESOLVED` -> lowering-ready
- `DYNAMIC` -> lowering-ready
- `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` -> 不得进入 executable-body lowering

若这些非 lowering-ready 状态仍流入 body lowering，应作为 invariant violation fail-fast。

### 4.2 `DYNAMIC_FALLBACK` 继续复用 `CallMethodInsn` surface

dynamic route 的 lowering 当前冻结为：

- `DYNAMIC_FALLBACK` 的 call emission 继续 lower 为普通 `CallMethodInsn`
- receiver 必须是 `FrontendReceiverKind.INSTANCE`
- `methodName` 继续使用已发布的 `callableName`
- 不得把该路由改写成 `CallStaticMethodInsn`
- 不得把该路由改写成 `CallGlobalInsn`
- 不得新增 `CallDynamicMethodInsn` 一类的新指令来承接它
- 这条合同只冻结“调用指令长什么样”；若同一 call site 已发布 writable receiver access-chain payload，receiver 侧仍可在同一条 `CallMethodInsn` 前后附着 leaf selection / post-call commit

### 4.3 参数与结果边界

exact route 与 dynamic route 的参数物化边界必须继续分离：

- exact `RESOLVED` route
  - body lowering 可以读取 selected callable signature
  - fixed parameter 与 vararg tail 继续走 ordinary boundary materialization
- `DYNAMIC_FALLBACK` route
  - body lowering 不读取 exact callable signature
  - body lowering 不为参数臆造 fixed parameter type
  - 已求值的 argument slot 直接透传给 `CallMethodInsn`
  - receiver 侧若已被 CFG 发布为 writable access-chain payload，则由独立 writable-route logic 处理，不属于 ordinary argument boundary 合同

dynamic call 的 published result slot 继续固定为 `Variant`。

若这个 `Variant` 结果随后流入强类型 assignment / fixed call parameter / return boundary：

- frontend 继续复用 ordinary boundary helper 做后续 `UnpackVariantInsn`
- 允许的 typed-boundary conversion 仍以 `frontend_implicit_conversion_matrix.md` 为唯一真源
- call instruction emission 本身不得内嵌这类后续 typed-boundary unpack

---

## 5. Backend 边界

backend dynamic dispatch 继续承担 runtime-open 调用的运行时职责：

- 运行时方法查找与分派
- 为 dynamic call route 生成实际调用代码
- 以 `Variant` 形式发布 dynamic call 的直接结果

frontend body lowering 不承担以下职责：

- 为 `DYNAMIC` call 重新做 overload 选择
- 为 `DYNAMIC` call 恢复 fixed callable signature
- 在 call emission 阶段把 dynamic route 重新收紧成 exact route

frontend 重新介入的位置只有两个，而且都必须受已发布事实约束：

- 当 dynamic call 已发布的 `Variant` 结果继续跨越 ordinary typed boundary 时，frontend 负责插入对应的普通 boundary `(un)pack`
- 当同一个 dynamic instance call 已发布 writable receiver access-chain payload 时，frontend 负责在同一条 `CallMethodInsn` 前后执行 leaf selection / runtime-gated post-call commit；frontend 不得为此重建 dynamic dispatch 或重做 callable resolution

---

## 6. Fail-closed / Fail-fast 合同

以下情况必须保持明确失败，而不是 silent fallback：

- call anchor 缺失已发布的 `FrontendResolvedCall`
- `DYNAMIC_FALLBACK` 不使用 instance receiver route
- call anchor 缺失已发布的 `expressionTypes()` result fact
- compile gate 本应拦截的非 lowering-ready call 状态仍流入 body lowering

换言之，dynamic call 支持的是“明确发布为 runtime-open 的 instance method route”，不是“body lowering 临场猜一个能跑的回退路径”。

---

## 7. 回归锚点

涉及本文档合同的修改，至少要继续覆盖以下回归锚点：

- `FrontendLoweringBodyInsnPassTest`
  - `runLowersDynamicInstanceCallsIntoCallMethodInsnWithVariantResultSlot`
  - `runLetsDynamicCallResultsCrossTypedCallBoundariesThroughOrdinaryUnpack`
  - `runEmitsRuntimeGatedPropertyWritebackForDynamicReceiverAndThreadsContinuationBlock`
  - `runEmitsRuntimeGatedWritebackForExplicitVariantPropertyOnObjectReceiver`
  - `runFailsFastWhenSyntheticDynamicFallbackDoesNotUseInstanceReceiverRoute`
  - mutating dynamic receiver route 一旦发布 writable access-chain payload，body lowering 必须整体消费该 payload，而不是重新拆分 receiver chain
- `FrontendLoweringToCProjectBuilderIntegrationTest`
  - `lowerFrontendDynamicInstanceCallRoutesBuildNativeLibraryAndRunInGodot`
  - `lowerFrontendDynamicVariantReceiverWritebackBuildNativeLibraryAndRunInGodot`
- `FrontendCompileCheckAnalyzerTest`
  - `DYNAMIC` call 不得被误判为 compile blocker

---

## 8. 架构反思

本区域已经沉淀出的长期结论是：

- dynamic call 的关键不是新增更多 lowering 分支，而是冻结 upstream published fact 合同
- body lowering 只消费 graph + published semantic facts，不能回退成第二套 call resolver
- route fact 与 result type fact 必须分离建模：`resolvedCalls()` 管 route，`expressionTypes()` 管 result type
- ordinary typed-boundary conversion 与 runtime-open dynamic dispatch 是两条并列合同，不能混写成同一层责任
- dynamic route 继续复用 `CallMethodInsn` surface，但这不等于 receiver side 永远只能是 direct pass-through；mutating receiver writeback 应作为并列的 receiver-side 合同叠加其上

后续若要扩张 dynamic call surface，必须继续按以下顺序推进：

1. 先冻结 shared semantic / compile gate / published fact 合同
2. 再冻结 CFG item surface
3. 最后接 body lowering 与 backend

不得反过来用 lowering 侧的局部实现去倒逼 semantic side table 临时补洞。
