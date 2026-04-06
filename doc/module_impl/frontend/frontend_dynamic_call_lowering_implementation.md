# Frontend Dynamic Call Lowering Implementation

> Updated: 2026-04-06
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

---

## 4. Lowering 合同

### 4.1 Lowering-ready call 状态

compile gate、CFG builder 与 body lowering 对 call 的 lowering-ready surface 必须保持一致：

- `RESOLVED` -> lowering-ready
- `DYNAMIC` -> lowering-ready
- `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` -> 不得进入 executable-body lowering

若这些非 lowering-ready 状态仍流入 body lowering，应作为 invariant violation fail-fast。

### 4.2 `DYNAMIC_FALLBACK -> CallMethodInsn`

dynamic route 的 lowering 当前冻结为：

- `DYNAMIC_FALLBACK` 只能 lower 为普通 `CallMethodInsn`
- receiver 必须是 `FrontendReceiverKind.INSTANCE`
- `methodName` 继续使用已发布的 `callableName`
- 不得把该路由改写成 `CallStaticMethodInsn`
- 不得把该路由改写成 `CallGlobalInsn`
- 不得新增 `CallDynamicMethodInsn` 一类的新指令来承接它

### 4.3 参数与结果边界

exact route 与 dynamic route 的参数物化边界必须继续分离：

- exact `RESOLVED` route
  - body lowering 可以读取 selected callable signature
  - fixed parameter 与 vararg tail 继续走 ordinary boundary materialization
- `DYNAMIC_FALLBACK` route
  - body lowering 不读取 exact callable signature
  - body lowering 不为参数臆造 fixed parameter type
  - 已求值的 operand slot 直接透传给 `CallMethodInsn`

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

frontend 重新介入的位置只有一个：

- 当 dynamic call 已发布的 `Variant` 结果继续跨越 ordinary typed boundary 时，frontend 负责插入对应的普通 boundary `(un)pack`

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
  - `runFailsFastWhenSyntheticDynamicFallbackDoesNotUseInstanceReceiverRoute`
- `FrontendLoweringToCProjectBuilderIntegrationTest`
  - `lowerFrontendDynamicInstanceCallRoutesBuildNativeLibraryAndRunInGodot`
- `FrontendCompileCheckAnalyzerTest`
  - `DYNAMIC` call 不得被误判为 compile blocker

---

## 8. 架构反思

本区域已经沉淀出的长期结论是：

- dynamic call 的关键不是新增更多 lowering 分支，而是冻结 upstream published fact 合同
- body lowering 只消费 graph + published semantic facts，不能回退成第二套 call resolver
- route fact 与 result type fact 必须分离建模：`resolvedCalls()` 管 route，`expressionTypes()` 管 result type
- ordinary typed-boundary conversion 与 runtime-open dynamic dispatch 是两条并列合同，不能混写成同一层责任

后续若要扩张 dynamic call surface，必须继续按以下顺序推进：

1. 先冻结 shared semantic / compile gate / published fact 合同
2. 再冻结 CFG item surface
3. 最后接 body lowering 与 backend

不得反过来用 lowering 侧的局部实现去倒逼 semantic side table 临时补洞。
