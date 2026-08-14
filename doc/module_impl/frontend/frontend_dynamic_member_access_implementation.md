# Frontend Dynamic Member Access Implementation

> Updated: 2026-06-22
>
> 本文档是 frontend dynamic member access 的事实源。
> 不再记录阶段性步骤、完成进度或实施流水账；若合同变化，应直接改写当前状态。

## 1. 维护合同

- 本文档覆盖 frontend semantic、compile gate、CFG builder、body lowering、writable route、backend codegen 与 runtime resource 之间关于 dynamic member access 的长期合同。
- 本文档只描述已经冻结并由代码实现承担的事实，不描述历史修复步骤。
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_rules.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_dynamic_call_lowering_implementation.md`
  - 与 dynamic member publication / lowering / codegen 直接相关的代码注释

---

## 2. 当前支持面

frontend 当前正式支持的 dynamic member surface 是：

- `FrontendResolvedMember.status() == DYNAMIC` 的 instance member read
- `FrontendResolvedMember.status() == DYNAMIC` 的 instance member write
- `FrontendResolvedMember.status() == DYNAMIC` 的 compound assignment current read / final write
- `FrontendResolvedMember.status() == DYNAMIC` 的 writable-chain reverse commit
- `Variant` receiver 上的 dynamic member access
- metadata unknown `GdObjectType` receiver 上的 dynamic member access

典型来源包括：

- 未标注参数或显式 `Variant` 参数上的 `host.marker`
- `GdObjectType` receiver 在 resolver 返回 `MetadataUnknown` 后的 `worker.marker`
- dynamic member 作为中间 owner 的 `dynamic_host.box.value = rhs`

以下内容不属于当前合同：

- `TYPE_META` dynamic member route
- 非 `Variant` 且非 `GdObjectType` receiver 的 dynamic member lowering
- 把 ordinary Object property access 放宽成 dynamic named route
- 新增 dynamic-member 专用 public abstraction 或 route-family enum

---

## 3. Published Fact 合同

### 3.1 Member route 真源

`analysisData.resolvedMembers()` 发布的是 member route fact，而不是 member value type 的唯一真源。

对 dynamic member 来说，这张表当前冻结的是：

- 该 member access 已被 frontend 接受为 runtime-open route
- member name 已冻结
- receiver kind 已冻结
- receiver family / provenance 已冻结

它不承担以下职责：

- 为 `DYNAMIC` member 提供 exact `resultType`
- 作为 downstream body materialization 的值类型来源

### 3.2 Member value type 真源

dynamic member 的可消费值类型正式来自 member anchor 对应的 `analysisData.expressionTypes()`。

因此：

- `FrontendResolvedMember.DYNAMIC.resultType() == null` 是正常模型
- `FrontendExpressionType.DYNAMIC` 当前把 dynamic member 的 published type 固定为 `GdVariantType.VARIANT`
- body lowering 不得回退为从 `FrontendResolvedMember.resultType()` 推导 dynamic member 结果类型
- 如果 `DYNAMIC` member 缺少对应 `expressionTypes()` fact，这是 publication drift，应 fail-fast

### 3.3 Receiver family 与 metadata unknown 合同

`FrontendChainReductionHelper.reducePropertyStep(...)` 当前会在两类情况下发布 `FrontendResolvedMember.dynamic(...)`：

- receiver 是 `GdVariantType`
- receiver 是 `GdObjectType`，且 property/signal resolver 返回 `MetadataUnknown`

这里的 `GdObjectType` 只表达“已冻结 dynamic fact 的 receiver family”，不是 dynamic route selector。硬性合同是：

```text
RESOLVED member -> ordinary property/static route
DYNAMIC member  -> Variant named route
```

因此：

- metadata known 且成员已解析成功的 Object property access 继续是 `RESOLVED`
- metadata known 但成员不存在的 Object property access 继续发布 `FAILED` 或等价失败事实
- 不允许因为 receiver 看起来是 `Object`/`GdObjectType` 就直接改走 dynamic named route

---

## 4. Lowering 合同

### 4.1 Dynamic read

所有 `DYNAMIC` member read 当前统一 lower 为：

- `LiteralStringNameInsn`
- `VariantGetNamedInsn`

具体规则是：

- `Variant` receiver 直接复用已发布 receiver value slot
- metadata unknown `GdObjectType` receiver 先 materialize/pack 成临时 `Variant` carrier，再执行 named get
- 不为 `DYNAMIC` member read 生成 `LoadPropertyInsn`
- `RESOLVED` member read 继续保持 `LoadPropertyInsn` / `LoadStaticInsn` ordinary route

### 4.2 Dynamic write 与 reverse commit

所有 `DYNAMIC` member write/reverse commit 当前统一 lower 为：

- `LiteralStringNameInsn`
- `VariantSetNamedInsn`

具体规则是：

- `Variant` receiver 直接复用已发布 receiver value slot
- metadata unknown `GdObjectType` receiver 先 materialize/pack 成临时 `Variant` carrier，再执行 named set
- dynamic member 作为 writable chain 中间 owner 时，外层 reverse commit 仍然使用 `VariantSetNamedInsn`
- 不为 `DYNAMIC` member write/reverse commit 生成 `StorePropertyInsn`
- `RESOLVED` member write 继续保持 `StorePropertyInsn` / `StoreStaticInsn` ordinary route

### 4.3 Compound assignment 与 continuation

dynamic compound assignment 当前冻结为单次求值、统一 named route：

- current read 走 `VariantGetNamedInsn`
- binary compute 继续复用既有 `BinaryOpInsn` 路径
- final write 走 `VariantSetNamedInsn`
- target receiver / prefix / key / call receiver 必须保持 CFG 已冻结的 single-evaluation 合同

当 dynamic member 作为中间 owner，或 reverse commit 需要 runtime gate 时：

- writable route 仍然只消费 frozen payload
- reverse commit 可以插入 `apply / skip / continue` synthetic blocks
- assignment lowering 必须返回 active continuation block
- 后续 sequence item 必须继续接到 returned continuation block，而不是原 lexical block

---

## 5. Backend 与 Runtime 合同

### 5.1 Object fallback 不属于 dynamic member route

`LoadPropertyInsn` / `StorePropertyInsn` 的 unknown object fallback 当前仍是普通 backend property 能力，但它不属于 dynamic member route。

原因已经冻结为：

- `godot_Object_get(...)` / `godot_Object_set(...)` 薄包装没有 `r_valid` 或等价成功判定
- `godot_variant_get_named(...)` / `godot_variant_set_named(...)` 持有 valid flag，并进入统一的 runtime error / cleanup 路径

因此：

- Object fallback 继续保留给 ordinary property/backend fallback 语境
- dynamic member runtime-open 语义统一依赖 Variant named get/set

### 5.2 Codegen 入口事实

当前 named get/set/call method 的主体生成点在 Java 生成器，不在 `.ftl` 模板：

- `IndexLoadInsnGen` 生成 `VariantGetNamedInsn`
- `IndexStoreInsnGen` 生成 `VariantSetNamedInsn`
- `CallMethodInsnGen` 生成 dynamic call 的 `CallMethodInsn`

`entry.c.ftl`、`entry.h.ftl`、`engine_method_binds.h.ftl` 只负责最终 C 产物装配或 engine bind 表，不是 dynamic member named access 的主体生成点。

### 5.3 Runtime resource 锚点

runtime resource 当前只用于锚定真实 Godot runtime 的 named access 行为，不用于重新定义 lowering route：

- compiled source 应通过未标注参数或显式 `Variant` 参数稳定触发 `DYNAMIC` member
- validation 侧可以传入真实 `Object` / `RefCounted` 派生实例作为 `Variant` payload
- `host: Object` 不是本合同的主触发面

---

## 6. Fail-Closed 边界

以下状态当前必须 fail-fast，而不是静默回退：

- `TYPE_META + DYNAMIC` member publication
- `DYNAMIC` member 缺少对应 `expressionTypes()` fact
- `DYNAMIC` member 缺少 receiver value id
- 明确非 `Variant` 且非 `GdObjectType` receiver 被发布成 `DYNAMIC` member

错误语义当前固定为 implementation invariant / publication contract drift，而不是普通源码语义错误。`FrontendCompileCheckAnalyzer` 继续只把 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 视为普通 compile gate blocker；`DYNAMIC` 本身不是 compile blocker。

---

## 7. 回归锚点

dynamic member 的核心回归锚点当前分布在：

- semantic / fact publication
  - `FrontendResolvedMemberTest`
  - `FrontendChainReductionHelperTest`
  - `FrontendExprTypeAnalyzerTest`
- compile gate / CFG boundary
  - `FrontendCfgGraphBuilderTest`
  - `FrontendCompileCheckAnalyzerTest`
- body lowering / writable route
  - `FrontendBodyLoweringSupportTest`
  - `FrontendBodyLoweringSessionTest`
  - `FrontendLoweringBodyInsnPassTest`
  - `FrontendWritableRouteSupportTest`
- backend Java generator
  - `IndexLoadInsnGenTest`
  - `IndexStoreInsnGenTest`
  - `CLoadPropertyInsnGenTest`
  - `CStorePropertyInsnGenTest`
- runtime resource
  - `src/test/test_suite/unit_test/script/runtime/dynamic_member_variant_named_access.gd`
  - `src/test/test_suite/unit_test/script/runtime/dynamic_member_variant_named_access_missing.gd`
  - `src/test/test_suite/unit_test/script/runtime/dynamic_member_variant_signal_read.gd`

这些测试当前共同固定以下行为：

- `DYNAMIC` member 的值类型真源是 `expressionTypes()`
- dynamic read/write/compound/reverse commit 都走 Variant named route
- metadata unknown `GdObjectType` receiver 会先 pack 成 `Variant`
- resolved Object property route 不回归为 dynamic named route
- runtime failure 沿 Variant named valid-flag/error 路径可观察

---

## 8. 维护结论

1. `DYNAMIC` member 的第一层分流条件永远是 member status，不是 receiver family。
2. `resolvedMembers()` 负责 route fact，`expressionTypes()` 负责 value type fact；lowering 只能消费 frozen facts，不能重新推导。
3. metadata unknown `GdObjectType` 只是 dynamic route 下的 receiver materialization 分支，不是放宽 ordinary Object property access 的入口。
4. Object fallback 继续存在，但它是普通 backend fallback，不再属于 dynamic member 语义合同。
5. 若 future change 改动 named get/set 的 runtime 成功判定、receiver publication surface 或 writable-route continuation shape，必须同步更新本文档与相关代码注释。
