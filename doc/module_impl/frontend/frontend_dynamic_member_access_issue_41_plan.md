# Frontend Dynamic Member Access Issue #41 实施计划

- 日期：2026-06-21
- 范围：修复 issue #41，允许 frontend 已发布为 `DYNAMIC member` 的成员读写进入 body lowering，并把所有动态成员读写统一降低为 `Variant` named get/set 路径。
- 状态：实施计划与调查结论；本文件只更新文档合同，代码实现需按本文后续步骤执行。

---

## 1. 背景与最新结论

旧计划要求 `DYNAMIC member` 在 body lowering 中明确区分 Object 与 Variant 两条路径：

- Object / object-derived receiver 走 `LoadPropertyInsn` / `StorePropertyInsn`，由后端生成 `godot_Object_get(...)` / `godot_Object_set(...)`。
- Variant receiver 或无法稳定解析为 Object route 的 receiver 走 `VariantGetNamedInsn` / `VariantSetNamedInsn`。

这个分流合同现在需要废弃。原因是 Object fallback 后端目前没有可观察的成功/失败结果：`godot_Object_get(...)` / `godot_Object_set(...)` wrapper 没有暴露 `r_valid`，body/backend 无法判断动态属性访问是否真正成功。相对地，`Variant` named get/set 路径已经通过 `godot_variant_get_named(...)` / `godot_variant_set_named(...)` 持有 valid flag，可以在失败时进入统一的 runtime error/cleanup 路径。

因此最新决策是：

- 所有 `FrontendResolvedMember.status() == DYNAMIC` 的成员读写，无论 receiver 静态类型是 `Variant`、`Object` 还是 object-derived metadata unknown，都统一使用 `VariantGetNamedInsn` / `VariantSetNamedInsn`。
- `LoadPropertyInsn` / `StorePropertyInsn` 仍保留给 `RESOLVED member` 的普通对象属性访问，不再承担 `DYNAMIC member` 的 runtime-open 路径。
- `DYNAMIC member` 的值类型仍然只来自 `expressionTypes()`，发布为 `Variant`；`resolvedMembers()` 只表达 route/provenance/member name/receiver family，不提供 `resultType`。
- frontend 不需要为非 `Variant` receiver/value 手工新增一套 dynamic Object materialize 路径；现有 Variant named backend 已有 operand materialization，能把非 `Variant` operand 转成临时 `Variant` 再调用 named API。

边界必须保持清楚：本计划只修改 `FrontendResolvedMember.status() == DYNAMIC` 的 member access。任何 `RESOLVED member`，包括普通 Object / object-derived receiver 上已经静态解析成功的属性读写，都必须继续使用现有 `LoadPropertyInsn` / `StorePropertyInsn` 或 static load/store 路径；不得扩大成“所有 Object member access 都走 Variant named route”。

---

## 2. 已确认事实

### 2.1 Frontend 语义事实

- `DYNAMIC` 是 frontend 已认可的 runtime-open fact，不是 compile gate blocker。
- `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` 有固定 owner：chain binding 发布 member/call facts，expr type analyzer 发布 expression facts，后续 lowering 只能消费 frozen facts。
- dynamic call 的已定合同仍然成立：call route 来自 `resolvedCalls()`，call result type 真源来自 `expressionTypes()`。
- dynamic member 与 dynamic call 的相同点是 route/result type 分离；不同点是 dynamic member 不能复用 Object property fallback，因为 Object property fallback 没有成功判定。
- `FrontendResolvedMember.DYNAMIC.resultType() == null` 是正常模型，不是缺字段。
- `FrontendExpressionType.DYNAMIC` 会把 `DYNAMIC member` 的可消费值类型发布为 `GdVariantType.VARIANT`。

### 2.2 当前代码路径

- `FrontendChainReductionHelper.reducePropertyStep(...)` 会在两类情况下发布 `FrontendResolvedMember.dynamic(...)`：
  - receiver 是 `GdVariantType`。
  - receiver 是 `GdObjectType`，但 `ScopePropertyResolver` 或 `ScopeSignalResolver` 返回 `MetadataUnknown`。
- `FrontendChainStatusBridge.toPublishedExpressionType(FrontendResolvedMember)` 会把 `DYNAMIC member` 桥接为 `FrontendExpressionType.dynamic(...)`，其 published type 固定为 `GdVariantType.VARIANT`。
- `FrontendCfgGraphBuilder.requireLoweringReadyMember(...)` 已接受 `RESOLVED | DYNAMIC`。
- `FrontendCfgGraphBuilder.applyAttributeStep(...)` 对 `AttributePropertyStep` 仍生成普通 `MemberLoadItem`；动态语义由 `analysisData.resolvedMembers().get(step).status()` 表达，不需要新增 dynamic CFG item。
- 当前 downstream 合同漂移点仍在 body lowering/materialization：
  - `FrontendBodyLoweringSupport.requireMemberResultType(...)` 仍可能从 `resolvedMembers().resultType()` 读取成员结果类型。
  - `FrontendBodyLoweringSession.requireResolvedMember(...)` 仍只接受 `RESOLVED`。
  - `FrontendMemberLoadInsnLoweringProcessor` 当前只按 exact `INSTANCE` / `TYPE_META` member read 生成 `LoadPropertyInsn` / `LoadStaticInsn`。
  - writable route 的 instance property leaf/commit 当前默认生成 `LoadPropertyInsn` / `StorePropertyInsn`。

### 2.3 后端事实

- `LoadPropertyInsn` / `StorePropertyInsn` 的 Object fallback 生成 `godot_Object_get(...)` / `godot_Object_set(...)`，但该 wrapper 没有 `r_valid` 或其他成功判定结果。
- `VariantGetNamedInsn` / `VariantSetNamedInsn` 的 C backend 生成 `godot_variant_get_named(...)` / `godot_variant_set_named(...)`，并已有 valid flag 检查、runtime error、cleanup 语义。
- `IndexLoadInsnGen` / `IndexStoreInsnGen` 使用的 `InsnGenSupport.materializeVariantOperand(...)` 已能在 backend 侧把非 `Variant` receiver/key/value operand materialize 成临时 `Variant`。
- 因此统一发 `VariantGetNamedInsn` / `VariantSetNamedInsn` 后，非 `Variant` receiver/value 不需要 frontend 先插入一套专门 Object 动态属性访问。

当前 named get/set/call method 的主体 C 代码生成点是 Java 生成器，不是独立 `.ftl` 模板。实施和审查时应直接看这些路径：

- `src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`：注册 `IndexLoadInsnGen`、`IndexStoreInsnGen`、`CallMethodInsnGen`。
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/IndexLoadInsnGen.java`：`VariantGetNamedInsn` / `variant_get_named` 的实际生成点，核心分支是 `emitVariantGetNamed(...)`。
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/IndexStoreInsnGen.java`：`VariantSetNamedInsn` / `variant_set_named` 的实际生成点，核心分支是 `emitVariantSetNamed(...)`。
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`：`CallMethodInsn` / `call_method` 的实际生成点；`BackendMethodCallResolver` 负责分派/签名解析。
- `src/main/c/codegen/include_451/godot/godot_interface.h` 提供 `godot_variant_get_named(...)` / `godot_variant_set_named(...)` 等 Godot runtime binding wrapper。
- `src/main/c/codegen/template_451/entry.c.ftl`、`entry.h.ftl`、`engine_method_binds.h.ftl` 只负责最终 C 产物装配或 engine bind 表，不是 `variant_get_named`、`variant_set_named`、`call_method` 指令主体生成点。

不要让实现者去寻找不存在的 `src/main/c/codegen/variant_get_named.ftl`、`variant_set_named.ftl` 或 `call_method.ftl`。

---

## 3. 非 Variant 类型触发 DYNAMIC member 的调查结论

### 3.1 触发条件

非 `Variant` receiver 触发 `DYNAMIC member` 的已确认条件是：

1. receiver 的 frontend 类型是 `GdObjectType`。
2. 编译器尝试用 `ScopePropertyResolver.resolveObjectProperty(...)` 或 `ScopeSignalResolver.resolveInstanceSignal(...)` 解析成员。
3. `ClassRegistry.getClassDef(receiverType)` 返回 `null`，即该 object type 的 root metadata 不可用。
4. resolver 返回 `MetadataUnknown`。
5. `FrontendChainReductionHelper.reducePropertyStep(...)` 把 `MetadataUnknown` 转成 `Status.DYNAMIC`，并发布 `FrontendResolvedMember.dynamic(...)`。

这条路径和 unannotated parameter 的 `Variant` dynamic 不同。未标注参数通常直接被发布为 `Variant`，例如 `func read(host): return host.marker`，这不是“非 Variant 类型触发”，而是普通 Variant receiver 动态成员访问。

也要注意：不是所有 `Object` 注解都会触发这条路径。如果 receiver 类型在 registry 中有 metadata，且成员明确不存在，resolver 会返回 `Failed`，不会因为“是 Object 类型”就无条件转为 `DYNAMIC member`。非 Variant 的 dynamic member 关键条件是 object family 已知，但对应 metadata 缺失。

### 3.2 示例代码

语义层面的最小示例：

```gdscript
class_name WorkerUser
extends RefCounted

func read_marker(worker: MissingWorker):
    return worker.marker

func write_marker(worker: MissingWorker, value):
    worker.marker = value
```

这里的关键前提不是 `MissingWorker` 这个名字本身，而是 frontend 已把 `worker` 的 slot type 发布为 `GdObjectType("MissingWorker")`，但当前 `ClassRegistry` 里没有 `MissingWorker` 的 class metadata。此时：

- `worker` 不是 `Variant`。
- `worker.marker` 仍不能被静态解析为 `RESOLVED member`。
- resolver 返回 `MetadataUnknown`。
- chain reduction 发布 `DYNAMIC member`。
- 按最新合同，body lowering 必须先把 `GdObjectType` receiver materialize/pack 成 `Variant` carrier，再把读写都降低为 Variant named 路径。

对应的目标 LIR 形态应是：

```text
PackVariantInsn(worker_variant_slot, worker_slot)
LiteralStringNameInsn(name_slot, "marker")
VariantGetNamedInsn(result_slot, worker_variant_slot, name_slot)
```

写入时应是：

```text
PackVariantInsn(worker_variant_slot, worker_slot)
LiteralStringNameInsn(name_slot, "marker")
VariantSetNamedInsn(worker_variant_slot, name_slot, value_slot)
```

当前已有语义单测可作为调查依据：`FrontendChainReductionHelperTest.reducePublishesDynamicMemberWhenObjectReceiverMetadataIsUnknown()` 使用 `new GdObjectType("MissingWorker")` 与空/缺 metadata registry，验证 object metadata unknown 会发布 dynamic member。

---

## 4. 已定语义合同

### 4.1 Result type 合同

`DYNAMIC member` 的 read result type 固定来自 `expressionTypes()`，并发布为 `Variant`。

因此：

- `resolvedMembers()` 不为 `DYNAMIC` 提供 `resultType`。
- body lowering 不得从 `FrontendResolvedMember.resultType()` 推导 dynamic member 结果。
- 后续 typed boundary 继续复用 ordinary boundary helper，例如 `Variant -> int` 使用现有 `UnpackVariantInsn`。
- 如果 `DYNAMIC member` 缺少 `expressionTypes()`，这是 semantic/CFG publication 漏洞，应 fail-fast，错误信息应指出 dynamic member value type 必须来自 `expressionTypes()`。

### 4.2 Read/write route 合同

所有 `DYNAMIC member` 统一使用 Variant named route：

- dynamic read：`LiteralStringNameInsn + VariantGetNamedInsn`。
- dynamic write：`LiteralStringNameInsn + VariantSetNamedInsn`。
- dynamic reverse commit 中写回 `receiver.property`：同样使用 `VariantSetNamedInsn`。
- receiver 静态类型是 `GdObjectType` 时，必须先 materialize/pack 成临时 `Variant` carrier，再把该 carrier 作为 `VariantGetNamedInsn` / `VariantSetNamedInsn` 的 receiver。
- receiver/value 静态类型已经是 `Variant` 时，直接使用已有 Variant slot，不插入重复 pack。
- value 静态类型不是 `Variant` 时，由 ordinary boundary 或 Variant named 后端 materialization 负责生成临时 Variant value。
- `RESOLVED member` 保持现有路径：
  - instance property：`LoadPropertyInsn` / `StorePropertyInsn`。
  - static property：`LoadStaticInsn` / `StoreStaticInsn`。

不再存在 “Object dynamic route” 与 “Variant fallback route” 两套 dynamic member lowering 合同。Object property fallback 只属于普通 `RESOLVED` / backend fallback 语境，不作为 `DYNAMIC member` 的目标路径。

实现时的第一层判断必须是 member status，而不是 receiver family：

```text
RESOLVED member -> ordinary property/static route
DYNAMIC member  -> Variant named route
```

也就是说，`GdObjectType` receiver 只有在对应 member fact 是 `DYNAMIC` 时才会先 pack 成 `Variant` 再走 named get/set；如果同一个 receiver 的 member 已经是 `RESOLVED`，仍必须走 ordinary Object property route。

后续实现与测试都必须把这条边界当作硬性合同：本计划不支持、也不允许借 issue #41 把“Object receiver”整体改写成 Variant named route。`Object` 在本文中的唯一动态含义是：当 semantic/CFG 已冻结 `FrontendResolvedMember.status() == DYNAMIC`，且 receiver 静态类型为 `GdObjectType` 时，lowering 需要先 pack 成 `Variant` carrier；它不是 route selector。任何测试 fixture 如果使用真实 Godot `Object` 实例作为 runtime 参数，也只能验证“该参数在 compiled source 里已经通过未标注参数或显式 `Variant` 参数进入 `DYNAMIC member` surface”的行为，不能把 `host: Object` 写成 dynamic route 的主锚点。

### 4.3 Receiver family 合同

- `DYNAMIC member` 的 receiver family 仍然保留在 `FrontendResolvedMember` 中，用于 provenance、diagnostics 和 invariant 检查。
- lowering 不再根据 receiver family 选择 Object 或 Variant 两条路径。
- lowering 只接受 `Variant` receiver 与 `GdObjectType` receiver 进入 dynamic member Variant named 路径；`GdObjectType` receiver 必须先 pack 成 `Variant`。
- `TYPE_META + DYNAMIC` 仍不是合法 dynamic member surface，应 fail-fast；静态成员必须解析为 `RESOLVED` 后走 static load/store。
- 明确非 `Variant` 且非 `GdObjectType` 的 stable receiver 不得进入 dynamic member lowering；如果这种 receiver 被发布成 `DYNAMIC member`，应在 CFG/body boundary fail-fast，并报告 upstream contract drift。

---

## 5. 分步骤实施计划

### Step 1：冻结 dynamic member result type 真源

状态：已完成（2026-06-21）。

修改目标：

- `FrontendBodyLoweringSupport`

实施内容：

- 将 `MemberLoadItem` materialization 的 dynamic member 类型来源从 `resolvedMembers().resultType()` 改为 anchor 对应的 `expressionTypes()`。
- 对 `AttributePropertyStep` anchor 调用既有 lowering-ready expression type helper，允许 `RESOLVED` 与 `DYNAMIC`。
- 对 `DYNAMIC member` 缺失 expression fact 的情况 fail-fast，错误信息说明 `DYNAMIC member` 的值类型必须来自 `expressionTypes()`。
- `RESOLVED member` 仍可使用 `FrontendResolvedMember.resultType()` 作为兼容 fallback。

验收细则：

- `dynamic_host.marker` 的 `MemberLoadItem` result materialization 类型为 `Variant`。
- `RESOLVED member` 仍 materialize 为 exact result type。
- `DYNAMIC member` 不再因 `resultType == null` 在 body lowering 阶段崩溃。

产出说明：

- `FrontendBodyLoweringSupport.requireMemberResultType(...)` 已改为优先消费 member anchor 对应的 `expressionTypes()`；这是 `DYNAMIC member` 的唯一值类型真源，也会沿用既有 lowering-ready expression type helper 的状态检查。
- 如果 member fact 是 `DYNAMIC` 但缺少对应 expression fact，现在会在 body materialization 边界 fail-fast，错误信息明确指出 dynamic member value type 必须来自 `expressionTypes()`。
- `RESOLVED member` 仍保留 `FrontendResolvedMember.resultType()` 兼容 fallback，避免旧式 exact member fact publication 被误降级为 `Variant`。
- `FrontendBodyLoweringSupportTest` 已覆盖 dynamic member 正向 materialization、resolved member exact fallback、dynamic member 缺 expression fact 的负向 fail-fast 三类行为。

### Step 2：统一 body lowering-ready member surface

修改目标：

- `FrontendBodyLoweringSession`

实施内容：

- 将 `requireResolvedMember(...)` 的可接受状态扩展为 `RESOLVED | DYNAMIC`。
- 更新注释，使其明确：`DYNAMIC member` route fact 与 value type fact 分离，body lowering 只能消费 frozen facts。
- 若状态为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`，继续作为 invariant violation fail-fast。

验收细则：

- body lowering 不再在 `DYNAMIC member` 入口处拒绝。
- 非 lowering-ready member 状态仍被拒绝。
- dynamic call 相关行为不变。

### Step 3：实现 dynamic member read 的统一 Variant named lowering

修改目标：

- `FrontendSequenceItemInsnLoweringProcessors.FrontendMemberLoadInsnLoweringProcessor`
- 必要时复用或扩展 `FrontendWritableRouteSupport` 的小范围 helper

实施内容：

- 当 `resolvedMember.status() == DYNAMIC` 时，只接受 `receiverKind == INSTANCE` 且 `baseValueIdOrNull != null`。
- 如果 receiver value type 是 `GdObjectType`，先 materialize/pack receiver slot 到临时 `Variant` carrier。
- 如果 receiver value type 是 `Variant`，直接复用 receiver slot。
- 如果 receiver value type 既不是 `Variant` 也不是 `GdObjectType`，fail-fast。
- materialize `MemberLoadItem.memberName()` 为 `StringName`。
- 生成 `VariantGetNamedInsn(resultSlotId, receiverVariantSlotId, nameSlotId)`。
- 不按 receiver type 生成 `LoadPropertyInsn`。
- `RESOLVED` member read 保持现有 `LoadPropertyInsn` / `LoadStaticInsn` 路径。
- 不允许把 “receiver 是 `GdObjectType`” 当成 dynamic read 的触发条件；`GdObjectType` 只在已确认 `DYNAMIC member` 后决定是否需要 pack。

验收细则：

- Variant receiver 的 `dynamic_host.marker` 生成 `VariantGetNamedInsn`。
- object metadata unknown receiver 的 `worker.marker` 先 pack receiver，再生成 `VariantGetNamedInsn`。
- 已静态解析成功的 Object property read 仍生成 ordinary property route，不因 receiver 是 Object 而生成 `VariantGetNamedInsn`。
- dynamic read result temp slot 是 `Variant`，不是从 `FrontendResolvedMember.resultType()` 获取。
- 不出现 `DYNAMIC member` 下的 `LoadPropertyInsn`。
- 非 `Variant` 且非 `GdObjectType` receiver 的 `DYNAMIC member` fail-fast。

### Step 4：实现 dynamic member write / reverse commit 的统一 Variant named lowering

修改目标：

- `FrontendBodyLoweringSession.requireWritableAccessChain(...)`
- `FrontendBodyLoweringSession.materializeWritableLeaf(...)`
- `FrontendBodyLoweringSession.materializeWritableCommitStep(...)`
- `FrontendWritableRouteSupport`
- 必要时扩展 `FrontendWritableRoutePayload`
- `FrontendAssignmentTargetInsnLoweringProcessors` 只作为 frozen payload 消费方参与验证，不作为 route-shape 决策点
- `FrontendAssignmentTargetInsnLoweringProcessors.lowerPublishedWritableRoute(...)` 的返回 block/continuation 合同
- `FrontendSequenceItemInsnLoweringProcessors` 中调用 assignment lowering 的 sequence item threading

实施内容：

- 确认 assignment target 的 `DYNAMIC member` 已发布完整 `FrontendWritableRoutePayload`。
- 当前 `FrontendAssignmentTargetInsnLoweringProcessors.lowerPublishedWritableRoute(...)` 只消费 frozen payload：调用 `session.requireWritableAccessChain(payload)`、`FrontendWritableRouteSupport.writeLeaf(...)`、`FrontendWritableRouteSupport.reverseCommit(...)`。它不应负责重新判断 property leaf/commit 是否 dynamic。
- 核心改动必须前移到 `FrontendBodyLoweringSession.requireWritableAccessChain(...)` 及其 materialization helper：在 `PROPERTY` leaf/step 被还原成 concrete writable chain 时读取对应 anchor 的 `FrontendResolvedMember.status()`。
- `materializeWritableLeaf(...)` 不能把 `DYNAMIC member` 还原成普通 `InstancePropertyLeaf` 后再让 `writeLeaf(...)` 临时猜测；它必须构造显式 dynamic property leaf，或让 `InstancePropertyLeaf` 显式携带 resolved/dynamic route fact。
- `materializeWritableCommitStep(...)` 同样不能把 `DYNAMIC member` reverse commit step 还原成普通 `InstancePropertyCommitStep`；commit step 必须显式携带 dynamic route fact，避免 reverse commit 漏走 `VariantSetNamedInsn`。
- 如果当前 `FrontendWritableRoutePayload.StepDescriptor` / `LeafDescriptor` 无法稳定携带该信息，应扩展 payload descriptor，在 CFG publication 时冻结 resolved vs dynamic route 信息，而不是在 writeback 末端重查或猜测。
- 当 writable leaf 或 reverse commit step 对应的 frozen route fact 是 `DYNAMIC` 时，生成 `LiteralStringNameInsn + VariantSetNamedInsn`。
- 如果 receiver slot type 是 `GdObjectType`，先 materialize/pack receiver slot 到临时 `Variant` carrier。
- 如果 receiver slot type 是 `Variant`，直接复用 receiver slot。
- 如果 receiver slot type 既不是 `Variant` 也不是 `GdObjectType`，fail-fast。
- 不为 Object receiver 生成 `StorePropertyInsn`。
- 上一条只适用于已冻结为 `DYNAMIC member` 的 write/reverse commit；`RESOLVED member` 的 Object receiver 仍必须生成普通 property write，不允许仅因 receiver 是 Object 就改走 `VariantSetNamedInsn`。
- RHS 按 ordinary boundary helper materialize 到目标 dynamic surface 所需类型；dynamic member surface 的最终 runtime value 走 Variant named backend materialization。
- compound assignment 的 current read 与 final write 都必须走同一条 dynamic Variant named 合同。
- `FrontendWritableRouteSupport.writeLeaf(...)` / `reverseCommit(...)` 可以负责最终发指令，但它们应消费已经显式标记为 dynamic 的 leaf/step，不应成为判断 dynamic route 的唯一位置。
- dynamic member 不只可能是最终 leaf，也可能是 writable chain 的中间 owner；例如 `dynamic_host.box.value = rhs` 中，`dynamic_host.box` 是后续 `.value` 的 owner carrier，最终 reverse commit 还必须把 mutated `box` 写回 `dynamic_host.box`。
- 一旦 dynamic member 作为中间 owner，writeback 是否需要 runtime gate 不能只靠静态 family 处理；assignment lowering 必须像 dynamic receiver writeback 一样允许 reverse commit 插入 `apply / skip / continue` block。
- 因此 `FrontendAssignmentTargetInsnLoweringProcessors.lowerPublishedWritableRoute(...)` 不能继续只返回 `void` 或隐含“后续仍挂在原 block”；它应返回 active continuation `LirBasicBlock`，并让 sequence item lowering 把后续 item 继续接到返回 block 上。

#### Step 4.1：compound assignment 的 read/compute/write 合同

形如 `dynamic_host.count += 1` 的 compound assignment 必须作为 issue #41 的正式支持面，不允许只支持普通 `=`。

实现要求：

- CFG publication 必须保持 target single-evaluation：receiver/prefix/key/call receiver 等 target operands 只求值一次，并被后续 current read 与 final write 共同消费。
- current-value read 仍通过冻结的 `MemberLoadItem` 完成；当其 anchor 对应 `DYNAMIC member` 时，按 Step 3 生成 `VariantGetNamedInsn`。
- compound binary 计算仍走现有 `CompoundAssignmentBinaryOpItem` / `BinaryOpInsn` 路径；该阶段不负责重新读取 target，也不插入最终 store boundary。
- final write 仍通过 `AssignmentItem.writableRoutePayload()` 进入 `FrontendAssignmentTargetInsnLoweringProcessors.lowerPublishedWritableRoute(...)`，再由 `session.requireWritableAccessChain(payload)` 还原 dynamic-aware leaf/commit。
- final write 的 leaf/commit 必须与 current read 消费同一个 member anchor 或同一份 frozen dynamic route fact，不能 current read 走 `VariantGetNamedInsn`、final write 却因普通 `InstancePropertyLeaf` / `InstancePropertyCommitStep` 漏到 `StorePropertyInsn`。
- `GdObjectType` receiver 的 compound assignment 需要在 current read 与 final write 各自的 dynamic named access 前 materialize/pack receiver 到 `Variant` carrier；pack 可以在两段分别物化，但不能重复求值 receiver 表达式。

目标 LIR 形态示意：

```text
# target receiver already evaluated once into dynamic_host_slot
LiteralStringNameInsn(count_name_for_read, "count")
VariantGetNamedInsn(current_value_slot, dynamic_host_slot, count_name_for_read)
BinaryOpInsn(compound_value_slot, current_value_slot, literal_one_slot, ADD)
LiteralStringNameInsn(count_name_for_write, "count")
VariantSetNamedInsn(dynamic_host_slot, count_name_for_write, compound_value_slot)
```

`GdObjectType` receiver 时，read/write 两个 named access 使用 pack 后的 carrier：

```text
# worker expression evaluated once into worker_slot
PackVariantInsn(worker_read_variant_slot, worker_slot)
LiteralStringNameInsn(count_name_for_read, "count")
VariantGetNamedInsn(current_value_slot, worker_read_variant_slot, count_name_for_read)
BinaryOpInsn(compound_value_slot, current_value_slot, literal_one_slot, ADD)
PackVariantInsn(worker_write_variant_slot, worker_slot)
LiteralStringNameInsn(count_name_for_write, "count")
VariantSetNamedInsn(worker_write_variant_slot, count_name_for_write, compound_value_slot)
```

#### Step 4.2：dynamic member 作为中间 owner 的 writable chain

本 issue 还必须覆盖 dynamic member 位于 writable chain 中间层的情况，而不只是 direct read/write：

```gdscript
func write_nested(dynamic_host, value):
    dynamic_host.box.value = value
```

语义上 `dynamic_host.box` 是 runtime-open member read，结果是 `Variant` carrier；`.value = value` 修改的是这个 carrier 内部的 leaf。修改完成后，reverse commit 必须把 mutated `box` carrier 写回 `dynamic_host.box`。目标形态是：

```text
# read intermediate owner
LiteralStringNameInsn(box_name_for_read, "box")
VariantGetNamedInsn(box_carrier_slot, dynamic_host_slot, box_name_for_read)

# mutate inner leaf, exact form depends on box/value route
...

# write mutated owner back into dynamic_host.box
LiteralStringNameInsn(box_name_for_write, "box")
VariantSetNamedInsn(dynamic_host_slot, box_name_for_write, mutated_box_carrier_slot)
```

`GdObjectType` receiver 时，中间 owner 的 outer receiver 同样先 pack：

```text
PackVariantInsn(worker_variant_slot, worker_slot)
LiteralStringNameInsn(box_name_for_read, "box")
VariantGetNamedInsn(box_carrier_slot, worker_variant_slot, box_name_for_read)
...
PackVariantInsn(worker_variant_slot_for_write, worker_slot)
LiteralStringNameInsn(box_name_for_write, "box")
VariantSetNamedInsn(worker_variant_slot_for_write, box_name_for_write, mutated_box_carrier_slot)
```

如果中间 owner carrier 是 `Variant`，reverse commit 可能需要 runtime gate 判断该 carrier 是否需要写回外层 owner。这个形状已经存在于 dynamic receiver writeback：`FrontendWritableRouteSupport.reverseCommitWithRuntimeGate(...)` 会返回 active continuation block。assignment lowering 需要纳入同一合同：

- `lowerPublishedWritableRoute(...)` 应返回 `LirBasicBlock`，表示 writeback 后的 active continuation。
- 对只需要静态 gate 的 direct/simple assignment，可返回原 block。
- 对 dynamic member 中间 owner 或其他 runtime-gated writeback，必须返回 `reverseCommitWithRuntimeGate(...)` 的 continuation block。
- 调用 assignment lowering 的 sequence item processor 必须使用返回 block 继续 lower 后续 item，不得把后续 instruction 继续追加到原 lexical block。

否则会出现 synthetic `apply/skip/continue` blocks 已经插入，但后续 lowering 仍写入旧 block 的 CFG 断裂。

验收细则：

- `dynamic_host.marker = value` 生成 `VariantSetNamedInsn`。
- object metadata unknown receiver 的 `worker.marker = value` 先 pack receiver，再生成 `VariantSetNamedInsn`。
- `dynamic_host.count += 1` 的 current read 是 `VariantGetNamedInsn`，final write 是 `VariantSetNamedInsn`。
- `worker.count += 1` 在 `worker` 为 metadata unknown `GdObjectType` receiver 时，current read 与 final write 都先 pack receiver，再走 Variant named get/set。
- compound assignment 不重新求值 target receiver；测试应断言 receiver-producing call 或 prefix side effect 只出现一次。
- compound assignment 的 final write 不允许出现 `StorePropertyInsn`。
- 已静态解析成功的 Object property compound assignment 仍保持 ordinary current read / final write route，不因 Object receiver 被误纳入 dynamic named route。
- `dynamic_host.box.value = rhs` 这类 dynamic member 中间 owner route 必须读出 `box` carrier、修改 inner leaf、再用 `VariantSetNamedInsn` 写回 `dynamic_host.box`。
- dynamic member 中间 owner route 如需 runtime-gated reverse commit，assignment lowering 必须返回 continuation block，后续 sequence item 继续接到该 block。
- `FrontendBodyLoweringSession.requireWritableAccessChain(...)` 生成的 property leaf/commit chain 能明确区分 `RESOLVED` 与 `DYNAMIC`，测试不只检查末端指令。
- 不出现 `DYNAMIC member` 下的 `StorePropertyInsn`。
- dynamic property reverse commit 不会因为被 materialize 成普通 `InstancePropertyCommitStep` 而漏发 `VariantSetNamedInsn`。
- 非 `Variant` 且非 `GdObjectType` receiver 的 `DYNAMIC member` write fail-fast。

### Step 5：保留 Object property fallback，但移出 dynamic member 合同

修改目标：

- 文档与测试断言
- 如需补充，可在 backend focused tests 中记录现有 Object fallback 行为；backend 审查入口应指向 Java `CInsnGen`，不是 `.ftl`

实施内容：

- 明确 `LoadPropertyInsn` / `StorePropertyInsn` 的 Object fallback 仍是普通 property backend 能力，不删除。
- 明确该 fallback 没有 `r_valid`，不能作为 `DYNAMIC member` 的 runtime-open 成功判定路径。
- 新增/调整测试时，不应断言 dynamic Object receiver 会 lower 到 `LoadPropertyInsn` / `StorePropertyInsn`。
- 可补 backend 文档说明：Object fallback 是无成功判定的薄包装；Variant named get/set 是 dynamic member 的可观察失败路径。
- 如需审查或补测 named get/set 生成逻辑，直接看 `IndexLoadInsnGen.emitVariantGetNamed(...)` 与 `IndexStoreInsnGen.emitVariantSetNamed(...)`；不要在 `src/main/c/codegen` 下新增或寻找 `variant_get_named.ftl` / `variant_set_named.ftl`。
- 如需对照 dynamic call / `call_method` 的既有 backend 合同，直接看 `CallMethodInsnGen` 与 `BackendMethodCallResolver`；`engine_method_binds.h.ftl` 只是 engine bind 表模板，不是 `CallMethodInsn` 的主体生成器。

验收细则：

- 文档中不再把 Object fallback 描述为 issue #41 的 dynamic route。
- `RESOLVED member` 的 object property load/store 不回归。
- Variant named get/set backend focused tests 继续覆盖 Java 生成器中的 valid flag 路径。
- 计划和相关文档不引用不存在的 `variant_get_named.ftl`、`variant_set_named.ftl`、`call_method.ftl` 作为实现点。

### Step 6：补 compile gate / CFG 边界保护

修改目标：

- `FrontendCompileCheckAnalyzer`
- `FrontendCfgGraphBuilder`

实施内容：

- `DYNAMIC` 本身仍不是 compile gate blocker。
- `TYPE_META + DYNAMIC`、明确非 `GdObjectType`/`Variant` receiver 却被发布成 `DYNAMIC member` 等状态应在 CFG/body boundary fail-fast。
- 错误信息应说明是 frontend publication contract drift，而不是普通源码语义错误。

验收细则：

- unsupported dynamic member route 不再进入 body lowering 后才因 `resultType == null` 崩溃。
- object metadata unknown receiver 作为合法 dynamic member 进入 Variant named lowering。

---

## 6. 测试计划

建议优先补在 `FrontendLoweringBodyInsnPassTest`：

1. `runLowersVariantDynamicMemberReadIntoVariantNamedGet`
   - 输入：`func read_path(dynamic_host): return dynamic_host.marker`
   - 断言：semantic member fact 为 `DYNAMIC`，body lowering 生成 `LiteralStringNameInsn + VariantGetNamedInsn`，result slot 类型为 `Variant`。

2. `runLowersVariantDynamicMemberAssignmentIntoVariantSetNamed`
   - 输入：`func write_path(dynamic_host, value): dynamic_host.marker = value`
   - 断言：body lowering 生成 `VariantSetNamedInsn`，不生成 `StorePropertyInsn`。

3. `runLetsDynamicMemberReadCrossTypedReturnBoundary`
   - 输入：`func read_marker(dynamic_host) -> int: return dynamic_host.marker`
   - 断言：dynamic member read result 先是 `Variant`，return boundary 使用 `UnpackVariantInsn`，不读取 `FrontendResolvedMember.resultType()`。

4. `runLowersDynamicMemberCompoundAssignmentThroughVariantNamedRoute`
   - 输入：`func write_path(dynamic_host): dynamic_host.count += 1`
   - 断言：current read 走 `VariantGetNamedInsn`，compound result 走 `BinaryOpInsn`，final write 走 `VariantSetNamedInsn`，且不生成 `StorePropertyInsn`。

5. `runLowersObjectDynamicMemberCompoundAssignmentThroughPackedVariantNamedRoute`
   - 输入：`func write_path(worker: MissingWorker): worker.count += 1`，测试 fixture 让 `MissingWorker` 对应 `GdObjectType` 且 metadata unknown。
   - 断言：current read 与 final write 都先 pack receiver，再分别走 `VariantGetNamedInsn` / `VariantSetNamedInsn`；`worker` source expression 只求值一次。

6. `runKeepsDynamicCompoundAssignmentTargetSingleEvaluation`
   - 输入：`func write_path(): make_host().count += 1`
   - 断言：`make_host()` 对应 receiver-producing call 只 lower/emit 一次，current read 与 final write 复用冻结 receiver value id；不能退化成 `make_host().count = make_host().count + 1`。

7. `runLowersDynamicMemberIntermediateOwnerWriteback`
   - 输入：`func write_path(dynamic_host, value): dynamic_host.box.value = value`
   - 断言：先用 `VariantGetNamedInsn` 读出 `dynamic_host.box` carrier，修改 inner leaf 后，再用 `VariantSetNamedInsn` 写回 `dynamic_host.box`；outer dynamic member writeback 不生成 `StorePropertyInsn`。

8. `runThreadsContinuationBlockAfterRuntimeGatedDynamicOwnerWriteback`
   - 输入：`func write_path(dynamic_host, value): dynamic_host.box.value = value; after_write()`
   - 断言：如果 dynamic owner reverse commit 插入 runtime gate synthetic blocks，`after_write()` 的 lowering 必须接在 returned continuation block 上，而不是原 lexical block。

建议补在 semantic / chain reduction tests：

1. 保留或新增 `GdObjectType("MissingWorker")` + 缺 metadata registry 的测试，断言 `worker.marker` 发布为 `DYNAMIC member`。
2. 断言该 dynamic member 的 expression type 是 `DYNAMIC(Variant)`。
3. 增加或保留已知 Object metadata 存在但成员不存在的负向测试，断言该场景发布为 `FAILED` 而不是 `DYNAMIC`。
4. 明确该 Object metadata unknown 场景是 focused test 锚点，不要求、也不允许通过 `test_suite` runtime resource 以 `host: Object` 作为主锚点复现。

建议补在 writable route focused tests：

1. 手工注入 `FrontendResolvedMember.dynamic(...)` 的 instance property leaf read 生成 `VariantGetNamedInsn`，不生成 `LoadPropertyInsn`。
2. 手工注入 `FrontendResolvedMember.dynamic(...)` 的 instance property leaf write/reverse commit 生成 `VariantSetNamedInsn`，不生成 `StorePropertyInsn`。
3. 覆盖 `GdObjectType` receiver 先 pack 成 `Variant` 再走 named get/set；该行为适合在 lowering/codegen focused tests 中断言，不作为 runtime resource 的主触发面。
4. 覆盖 `RESOLVED member` + `GdObjectType` receiver 的 read/write/compound write 仍走 ordinary property route；该 guard test 用来防止实现把 “Object receiver” 误当成 dynamic route selector。

建议补在 backend Java generator focused tests：

1. `IndexLoadInsnGenTest` 覆盖 `VariantGetNamedInsn` 生成 `godot_variant_get_named(...)`、`r_valid` 检查和 runtime error/cleanup。
2. `IndexStoreInsnGenTest` 覆盖 `VariantSetNamedInsn` 生成 `godot_variant_set_named(...)`、`r_valid` 检查和 runtime error/cleanup。
3. 若需要对照 dynamic call 行为，使用 `CallMethodInsnGenTest` / `CallMethodInsnGenEngineTest`；审查入口是 `CallMethodInsnGen.java`，不是 `call_method.ftl`。

### 6.1 Engine/runtime 行为锚点

仅有 frontend lowering 单测不足以冻结本 issue 的真实行为。统一走 Variant named route 的动机来自 runtime 成功/失败语义，因此必须增加 `test_suite` 资源级锚点，覆盖 compile -> native build -> Godot runtime validation 的完整链路。

runtime resource 必须选择能稳定命中 issue #41 真实触发面的 source surface。不要用 `host: Object` 作为 runtime resource 主锚点：当前完整 pipeline 下，`Object` 注解是否进入 `DYNAMIC member` 取决于 registry metadata 与 object resolver 结果，容易变成 `RESOLVED`、`FAILED` 或其他 compile-surface 行为，不能稳定代表 #41 的 dynamic member lowering。更重要的是，`host: Object` 这种 fixture 容易把实现者引向错误语义面：为了让 runtime case 通过而把所有 Object member access 都改成 Variant named route。`GdObjectType` metadata unknown 场景应保留在 semantic / CFG / body focused tests 中用 synthetic registry 或直接注入 facts 覆盖。

建议新增 `src/test/test_suite/unit_test/script/runtime/dynamic_member_variant_named_access.gd` 与配套 validation script：

- compiled source 暴露 `read_marker(host) -> Variant`，函数体执行 `return host.marker`；未标注参数稳定按 `Variant` receiver 进入 `DYNAMIC member`。
- compiled source 暴露 `write_marker(host, value: Variant) -> void`，函数体执行 `host.marker = value`。
- 如果为了可读性显式标注参数，只允许标注为 `Variant`，不要标注为 `Object`。
- validation script 在 Godot 端构造一个真实 `Object`/`RefCounted` 派生实例，预置可动态访问的 `marker` 属性，并作为 `Variant` 参数传给 compiled source：
  - `read_marker(...)` 应读到 Godot 端设置的值。
  - `write_marker(...)` 应能被 Godot 端再读回。
  - validation 通过时打印 `__UNIT_TEST_PASS_MARKER__`。

这条 resource case 的作用不是重复断言 LIR 指令名，而是锚定 “frontend 发布为 `Variant` receiver 的 `DYNAMIC member` 后，`godot_variant_get_named/set_named` 在真实引擎里能按 Godot 规则访问对象属性”。这里传入真实 Object 实例只是 Godot runtime 的 Variant payload，不是 lowering route selector；`GdObjectType` receiver pack 成 `Variant` 的行为由 focused lowering/codegen tests 覆盖，不作为 runtime resource 的主触发面。

还应补一个负向 runtime 锚点，建议使用 `test_suite` 的 runner output directive：

```gdscript
# gdcc-test: output_contains_any=Variant named member access failed || Invalid get index || Invalid set index
# gdcc-test: output_not_contains=Reached after invalid dynamic member access.
```

负向 case 可以让 compiled source 对 `Variant` receiver 访问不存在的成员，例如：

```gdscript
func read_missing(host) -> Variant:
    return host.missing_marker
```

validation script 在调用失败前打印 pass marker，再让 runner 断言 Godot/gdcc runtime 输出包含动态 named access 失败信号，且不可到达的 marker 没有出现。这样可以固定本次从 Object fallback 迁移到 Variant named route 的核心价值：失败应沿 Variant named valid flag/runtime error 路径可观察，而不是静默走无 `r_valid` 的 Object fallback。

### 6.2 回归命令

回归命令：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendLoweringBodyInsnPassTest --tests FrontendBodyLoweringSupportTest --tests FrontendWritableRouteSupportTest --tests FrontendChainReductionHelperTest --tests FrontendCompileCheckAnalyzerTest
```

如果补 named get/set backend Java generator focused tests，再追加：

```bash
script/run-gradle-targeted-tests.sh --tests IndexLoadInsnGenTest --tests IndexStoreInsnGenTest
```

如果同时对照 dynamic call / `call_method` backend 行为，再追加：

```bash
script/run-gradle-targeted-tests.sh --tests CallMethodInsnGenTest --tests CallMethodInsnGenEngineTest
```

新增或调整 `test_suite` resource 后，还必须运行：

```bash
script/run-gradle-targeted-tests.sh --tests GdScriptUnitTestCompileRunnerTest
```

---

## 7. 验收清单

功能验收：

- `DYNAMIC member` read 不再因 `Missing published member result type` 失败。
- `DYNAMIC member` read result slot 是 `Variant`。
- 所有 `DYNAMIC member` read 都生成 `VariantGetNamedInsn`。
- 所有 `DYNAMIC member` write / reverse commit 都生成 `VariantSetNamedInsn`。
- Object / object-derived metadata unknown receiver 不再走 Object dynamic property path，而是先 pack 成 `Variant` 后统一走 Variant named path。
- Object / object-derived metadata known 且成员已解析为 `RESOLVED` 时，仍走 ordinary property/static route；测试和实现不得因为 receiver family 是 Object 而改走 Variant named route。
- 非 `Object` 且非 `Variant` receiver 的 `DYNAMIC member` fail-fast。
- `RESOLVED member` read/write 行为不回归。
- dynamic call receiver writeback 行为不回归。

合同验收：

- `resolvedMembers()` 不承担 dynamic result type 真源职责。
- `expressionTypes()` 是 member value result type 真源。
- CFG builder 与 body lowering 对 `DYNAMIC member` 的 lowering-ready surface 一致。
- 实现和测试都必须先按 `FrontendResolvedMember.status()` 区分 `RESOLVED` 与 `DYNAMIC`；receiver 是 `Object` 不能单独成为改走 Variant named route 的理由。
- runtime resource 使用真实 Object 实例时，只能作为 `Variant` receiver dynamic member 的 payload 验证；不能把 `host: Object` 当作 issue #41 的稳定触发面。
- 文档中不再保留 “Object dynamic route 与 Variant fallback route 必须显式区分” 的旧结论。
- Object property fallback 被记录为普通 backend fallback，不作为 issue #41 dynamic member route。

测试验收：

- 新增 positive tests 覆盖 dynamic read、dynamic write、typed boundary、compound assignment。
- compound assignment 测试必须覆盖 `Variant` receiver 与 metadata unknown `GdObjectType` receiver，并验证 current read / final write 都走 Variant named route。
- compound assignment 测试必须覆盖 target single-evaluation，防止 receiver/prefix 被重复求值。
- writable chain 测试必须覆盖 dynamic member 作为中间 owner，例如 `dynamic_host.box.value = rhs`，并验证 mutated owner carrier 被写回外层 dynamic member。
- continuation block 测试必须覆盖 runtime-gated reverse commit 后的后续 sequence item，防止后续 lowering 错挂回原 lexical block。
- 新增/保留调查测试覆盖 object metadata unknown -> `DYNAMIC member`。
- 新增 engine/runtime resource 锚点，使用未标注参数或显式 `Variant` 参数稳定触发 `DYNAMIC member`，验证真实 Godot runtime 的 named get/set 正向行为可用。
- engine/runtime resource 不使用 `host: Object` 作为主锚点；若 validation 侧传入真实 Object 实例，compiled source 的 receiver surface 仍必须是未标注参数或显式 `Variant`。
- `GdObjectType` metadata unknown / pack-to-Variant 行为由 focused semantic/lowering/codegen tests 验证，不以 `host: Object` runtime resource 作为主锚点。
- 新增 engine/runtime 负向锚点，验证不存在成员访问沿 Variant named valid flag/error 路径可观察失败。
- 相关 targeted tests 全部通过。

---

## 8. 实现风险提示

1. 不要把 `DYNAMIC member` 的 receiver family 重新拿来做 Object/Variant 分流；receiver family 只保留 provenance 和 invariant 检查价值。
2. 不要让 `DYNAMIC member` 回退读取 `FrontendResolvedMember.resultType()`；其值类型真源必须是 `expressionTypes()`。
3. 不要把 `dynamic_host.count += 1` 改写成重复求值的 AST 级读写；target receiver、attribute prefix、subscript key、call prefix 必须保持 CFG 已冻结的 single-evaluation 合同。
4. 不要删除或破坏 `RESOLVED member` 的 `LoadPropertyInsn` / `StorePropertyInsn` 路径；本次只改变 `DYNAMIC member`。
5. 不要因为 runtime resource 传入真实 Godot Object，就把普通 Object member access 全部改成 Variant named route；runtime resource 只能验证 compiled source 已发布为 `DYNAMIC member` 的 Variant named runtime 行为。
6. `GdObjectType` receiver 进入 dynamic member route 时必须先 pack 成 `Variant`，不能直接把 Object slot 当作 Variant named receiver 使用。
7. Object fallback 没有 `r_valid` 是选择统一 Variant named route 的原因；如果未来新增可观察成功判定的 Object runtime helper，再重新评估是否需要分流。
8. 不要把 named get/set/call method 的主体生成点写成 `.ftl`；当前实际实现点是 `IndexLoadInsnGen.java`、`IndexStoreInsnGen.java`、`CallMethodInsnGen.java` 等 Java generator，`.ftl` 只属于最终 C 产物装配或 engine bind 表。

---

## 9. 已定决策

1. `DYNAMIC member` read/write/reverse commit 统一降低为 `VariantGetNamedInsn` / `VariantSetNamedInsn`。
2. `Object` / object-derived metadata unknown receiver 虽然不是 `Variant`，但只要 chain reduction 发布了 `DYNAMIC member`，lowering 就先 pack receiver 成 `Variant`，再走 Variant named route。
3. `LoadPropertyInsn` / `StorePropertyInsn` 不再作为 `DYNAMIC member` 的实现目标，只保留给 `RESOLVED member` 与普通 backend property access。
4. 非 Variant 触发路径的核心条件是 `GdObjectType` receiver 的 class metadata 缺失，resolver 返回 `MetadataUnknown`。
5. 非 `Object` 且非 `Variant` receiver 的 `DYNAMIC member` 是非法 publication surface，必须 fail-fast。
6. 本计划不引入新的 public abstraction 或 route-family enum；用 published dynamic member fact 触发统一 Variant named helper 即可。

---

## 10. 建议实施顺序

1. 先做 Step 1 与 Step 2，消除 `resultType()` 与 `RESOLVED-only` 的合同漂移。
2. 再做 Step 3，让 dynamic member read 统一生成 `LiteralStringNameInsn + VariantGetNamedInsn`。
3. 做 Step 4，让 dynamic member write 和 reverse commit 统一生成 `LiteralStringNameInsn + VariantSetNamedInsn`。
4. 补 Step 6 的边界保护，确保非法 dynamic surface fail-fast。
5. 按测试计划补 focused tests，尤其覆盖 object metadata unknown -> `DYNAMIC member` -> Variant named route。
6. 最后运行 targeted tests，并同步更新相关 frontend/backend 文档中关于 dynamic member route 的表述。
