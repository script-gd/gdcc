# Frontend Complex Writable Target Implementation

> 本文档作为 frontend “复杂可写目标 / mutating receiver writeback” 实现的长期事实源。  
> 只保留当前代码已经落地的合同、约束、测试锚点与长期风险，不记录阶段性实施流水账。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/cfg/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/pass/body/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/**`
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
- 更新时间：2026-04-26
- 关联事实源：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_c_backend.md`

若以下任一合同发生变化，至少要同步更新：

- 本文档
- `frontend_rules.md`
- `frontend_dynamic_call_lowering_implementation.md`
- `frontend_lowering_cfg_pass_implementation.md`
- `gdcc_type_system.md`
- `gdcc_c_backend.md`
- 与 writable route / alias / runtime gate 直接相关的源码注释

## 当前最终状态

### 核心实现落点

- CFG frozen surface：
  - `FrontendWritableRoutePayload`
  - `AssignmentItem`
  - `CallItem`
  - `DirectSlotAliasValueItem`
- body lowering shared support：
  - `FrontendWritableRouteSupport`
  - `FrontendAssignmentTargetInsnLoweringProcessors`
  - `FrontendSequenceItemInsnLoweringProcessors`
  - `FrontendBodyLoweringSession`
- shared family / mutability support：
  - `FrontendWritableTypeWritebackSupport`
  - `FrontendCallMutabilitySupport`
- backend / runtime support：
  - `CBodyBuilderAliasSafetySupport`
  - `LoadPropertyInsnGen`
  - `StorePropertyInsnGen`
  - `gdcc_variant_requires_writeback(...)`

### 已锁定的实现结论

- ordinary CFG value 仍以 `valueId -> slotId` 为主路径；complex writable route 是并列合同，不是第二套求值器。
- `AssignmentItem` 与 `CallItem` 当前都可以承载单个 frozen writable-route payload。
- assignment final store 已经只消费 payload；legacy `targetOperandValueIds` 只保留 source-order sequencing 与 compound current-value read。
- exact / dynamic instance mutating call 共享同一套 writable-route consumer。
- direct-slot mutating receiver 当前允许发布 alias-backed receiver value，但 surface 保持严格收口，不泛化为普通 identifier/self read。
- dynamic / `Variant` receiver writeback 已经接通 per-layer runtime gate，并要求后续 lowering 显式线程化 continuation block。
- backend 对 destroyable non-object overwrite 已经区分 `PROVEN_NO_ALIAS` 与 `MAY_ALIAS`，`MAY_ALIAS` 路径使用 stable carrier，避免提前销毁。

## 1. 模型边界

### 1.1 writable route 的职责

writable-route payload 只负责冻结“如何把 leaf mutation 写回 owner chain”，不负责表达式求值本身。职责边界必须保持为：

- CFG items 表达 source-order 求值、值发布与普通数据流。
- writable-route payload 表达 root / leaf / reverse-commit route。
- body lowering 只能消费已冻结 payload，不得回退为 AST replay。
- backend 不负责重建 frontend owner provenance。

这条边界不能被打破。若 payload 开始承载求值解释，或 lowering 重新解释 AST target / receiver，最终一定会重新形成双账本漂移。

### 1.2 payload shape

当前 payload 冻结为：

- `routeAnchor`
- `root = RootDescriptor(kind, anchor, valueIdOrNull)`
- `leaf = LeafDescriptor(kind, anchor, containerValueIdOrNull, operandValueIds, memberNameOrNull, subscriptAccessKindOrNull)`
- `reverseCommitSteps = List<StepDescriptor(kind, anchor, containerValueIdOrNull, operandValueIds, memberNameOrNull, subscriptAccessKindOrNull)>`

当前允许的 kind 集合为：

- `RootDescriptor`
  - `DIRECT_SLOT`
  - `SELF_CONTEXT`
  - `STATIC_CONTEXT`
  - `VALUE_ID`
- `LeafDescriptor`
  - `DIRECT_SLOT`
  - `PROPERTY`
  - `SUBSCRIPT`
- `StepDescriptor`
  - `PROPERTY`
  - `SUBSCRIPT`

### 1.3 payload 不变量

- payload 只能引用同一 `SequenceNode` 中更早已发布的 value id。
- payload 不能表达 AST fallback、备用求值路径或“需要时再重读”。
- `SUBSCRIPT` leaf/step 当前固定只支持一个 key operand。
- `AttributeSubscriptStep` 的 access family 必须在 publication 阶段冻结，不能在 body lowering 按 prefix 类型事后猜。
- `StaticPropertyCommitStep` 只能作为 terminal step；non-terminal static step 必须 fail-fast。

## 2. Assignment 与 compound assignment 合同

### 2.1 final store 只消费 payload

assignment / compound assignment 的 final store 必须只消费 `AssignmentItem.writableRoutePayload`。当前已明确禁止：

- 再从 target AST 重放 tail-step
- 再从 `targetOperandValueIds` 重建 store route
- 与旧的 ad-hoc writeback helper 并行存在

`targetOperandValueIds` 的剩余职责只有两个：

- 保持 target 子表达式的 source-order 求值
- 支撑 compound assignment 的 current-value read

### 2.2 compound assignment 的固定形状

compound assignment 继续保持如下 read-modify-write 结构：

1. 读取 current leaf value
2. 求值 RHS
3. 计算 compound binary result
4. 对同一条 route 执行 leaf write
5. 执行 reverse commit

不得新增：

- 额外 getter / subscript get
- 额外 `VariantGet*`
- 提前或重复的 pack/unpack

### 2.3 reverse commit 的 carrier-threaded 语义

reverse commit 不是“把同一个 written-back value 重复 store 到所有 outer owner”。当前必须保持逐层 carrier 语义：

- `writeLeaf(...)` 返回的是最内层 outer owner 的 current carrier。
- reverse commit 按从内到外的顺序消费 `reverseCommitSteps`。
- 每一步都必须返回“下一层 outer owner 的 current carrier”。
- 外层 step 不能复用 leaf 阶段的 carrier。

示例 `self.items[i].x += 1` 的正确传播顺序是：

1. leaf write：把 `x` 写进 `element`，carrier = `element`
2. step 1：把 `element` 写回 `items[i]`，carrier = `items`
3. step 2：把 `items` 写回 `self.items`

### 2.4 gate 语义

gate 只回答“当前 carrier 是否需要写回当前这一层”，因此必须绑定 current carrier，而不是绑定 leaf 初值或 step 文本。

必须保持：

- gate 观察的是当前层的 current carrier slot。
- 若 gate 返回 `false`，只跳过当前层 writeback。
- 跳过当前层时仍要把 carrier 提升到下一层 outer owner。
- 更外层 reverse commit 仍继续执行。

## 3. Mutating receiver call 合同

### 3.1 published surface

complex writable target 不引入新的 call item 或新的 call instruction。当前冻结为：

- `CallItem` 继续承载 ordinary call surface
- 若 receiver 还需要 post-call writeback，则同一个 `CallItem` 额外挂载一个 writable-route payload
- `CallMethodInsn` 继续是 exact / dynamic instance route 的唯一 call instruction surface

### 3.2 receiver leaf materialization

对 payload-backed call，`receiverValueIdOrNull` 当前是 dedicated receiver leaf value 的强合同：

- exact route 优先直接复用已发布的 receiver value slot
- payload-backed call 若缺失 dedicated `receiverValueIdOrNull`，视为 invariant violation
- body lowering 不会再按 payload 临时重读 leaf

也就是说，若某条 mutating receiver 需要“真实源 slot”而不是普通 temp，它必须在 CFG publication 阶段就作为 dedicated value surface 发布出来。

### 3.3 exact 与 dynamic 的共同点与差异

共同点：

- 二者都使用同一个 payload consumer
- 二者都必须先发普通 `CallMethodInsn`
- 二者都在 call 之后接 shared writable-route reverse commit

差异：

- exact route 的 post-call writeback 只使用静态 gate
- dynamic route 先走同一套静态 family fast-path / fast-skip
- 只有 current carrier 静态为 `Variant` 时，dynamic route 才发 `gdcc_variant_requires_writeback(...) + GoIfInsn`

### 3.4 continuation block 合同

runtime-gated reverse commit 可能插入 `apply / skip / continue` block，因此 body lowering 的 processor / registry / sequence-item 调度面必须显式线程化当前 block。

当前冻结为：

- `reverseCommitWithRuntimeGate(...)` 返回 active continuation block
- 后续 sequence item 必须继续附着到该 returned block
- call lowering 不得假设“所有后续 lowering 永远挂在原 lexical block 上”

## 4. Direct-slot alias publication 合同

### 4.1 允许的 alias root

当前 direct-slot mutating receiver alias 只允许以下 surface：

- `SelfExpression`
- `IdentifierExpression + LOCAL_VAR`
- `IdentifierExpression + PARAMETER`

以下 surface 当前明确不在 alias root 支持面：

- `IdentifierExpression + CAPTURE`
- `IdentifierExpression + SELF`
- `receiverValueIdOrNull == null` 时由 call execution fallback 提供的 implicit self receiver

它们的语义必须区分：

- `SelfExpression`
  - 是显式语法节点
  - 可作为 alias root
  - 安全性来自 `self` slot 不可被用户代码重绑定
  - 若它作为 supported assignment target 的 receiver prefix，semantic 阶段必须已经发布该具体 `SelfExpression` 的 `expressionTypes()` fact，CFG / body lowering 只消费该 frozen fact
- `IdentifierExpression + SELF`
  - 在当前代码库中不是合法 published surface
  - analyzer 只会对显式 `SelfExpression` 发布 `SELF`
  - 若它泄漏到 builder 或 body lowering，必须 fail-fast
- implicit self receiver
  - 只发生在 call execution fallback
  - 没有 dedicated receiver value id
  - 不属于 alias publication surface

### 4.2 ordinary read 与 alias read 的分层

当前有意保持以下分层：

- ordinary identifier/self read 继续发布为普通 value surface
- direct-slot mutating receiver 才允许发布 `DirectSlotAliasValueItem`
- `DirectSlotAliasValueItem` 不是“通用 direct-slot read”，而是“保留 live source slot 绑定的专用 receiver value”

这保证 alias 不会静默渗透到普通 read 路线。

显式 `self` 的 assignment-target prefix 只把已经复现并落地的 direct property route 纳入合同：

- `self.<property> = value` 可以形成 `RootDescriptor(kind = DIRECT_SLOT, anchor = SelfExpression)` 与 `LeafDescriptor(kind = PROPERTY, memberName = <property>)`。
- 该 route 依赖 sema 已发布的 `SelfExpression` type fact；writable-route payload 不负责补推 receiver type，也不负责恢复 `IdentifierExpression + SELF`。
- `self.transform.origin = value`、`self.payloads[i] = value`、`self.<property> += value` 分别属于 nested owner route、container mutation、compound read-modify-write 合同；它们不能因为 direct `self.<property> = value` 已闭合而被隐式纳入同一验收面。

### 4.3 identifier-backed alias 的 no-rebinding 约束

`LOCAL_VAR` / `PARAMETER` root 只有在“后续 arguments 全部停留在 proven no-rebinding 子集”时才允许 alias publication。

当前分类原则是：

- proven safe：
  - `IdentifierExpression`
  - `LiteralExpression`
  - `SelfExpression`
  - 由上述节点递归组成且没有 effect-open surface 的 unary / binary / cast / type-test / conditional / property / subscript read
- fail-closed snapshot fallback：
  - `AssignmentExpression`
  - `CallExpression`
  - `AttributeCallStep`
  - 任何当前尚未被显式证明 safe 的 future/unknown form

这条合同的重点不是“显式扫描到了哪些节点名”，而是：

- 只有已经被 builder 明确证明不会重绑定同一 direct-slot root 的参数子树，才允许 live-slot alias 穿过参数求值阶段
- 一旦未来新增 rebinding form，若它没有进入 safe 分类，就必须默认回退 snapshot，而不是静默穿透 alias

### 4.4 CAPTURE 的当前结论

`CAPTURE` 当前保留在绑定/作用域模型里，但 lambda/capture lowering 与 capture storage semantics 仍未冻结，因此：

- `CAPTURE` 不参与 alias eligibility
- 一旦 capture-backed identifier 进入 alias path，当前实现必须 fail-fast
- future lambda 工程若要开放 capture alias，必须先建立独立的 storage / rebinding / alias-safety 证明链

## 5. writeback family 与 runtime helper 合同

### 5.1 静态 family matrix

`FrontendWritableTypeWritebackSupport` 是“哪些 statically known carrier family 需要 reverse commit”的 frontend 共享真源。

当前固定为：

- `false`
  - `Array`
  - `Dictionary`
  - `Object`
  - primitive family
- `true`
  - value-semantic builtin family
  - `Variant`

`Variant` 在静态 helper 中返回 `true` 的含义不是“必定写回”，而是“静态阶段还不能证明该 carrier 属于 shared/reference family，需要交给 runtime helper 继续细分”。

### 5.2 runtime helper

`gdcc_variant_requires_writeback(...)` 是 backend-owned runtime helper，职责只限于回答“当前 `Variant` carrier 这一层是否需要 outer-owner writeback”。

当前矩阵固定为：

- 返回 `false`
  - `NIL`
  - `BOOL`
  - `INT`
  - `FLOAT`
  - `ARRAY`
  - `DICTIONARY`
  - `OBJECT`
- 返回 `true`
  - `String`
  - `StringName`
  - `NodePath`
  - `Vector*`
  - `Rect*`
  - `Plane`
  - `Quaternion`
  - `AABB`
  - `Basis`
  - `Transform*`
  - `Projection`
  - `Color`
  - `RID`
  - `Callable`
  - `Signal`
  - `Packed*Array`
- 对未列举 future `Variant` kind 默认返回 `true`

### 5.3 helper 的边界

runtime helper 不参与：

- callable resolution
- receiver provenance 重建
- owner route 解释

它只回答当前 `Variant` carrier family 的 writeback gate。property leaf / method result / ordinary local 的 provenance 仍由 frozen writable-route payload 决定。

## 6. Backend alias-safety 合同

### 6.1 `PROVEN_NO_ALIAS` 与 `MAY_ALIAS`

backend 对 destroyable non-object slot write 已集中收口到 `CBodyBuilderAliasSafetySupport.classifyNonObjectSlotWriteAliasSafety(...)`。

当前原则是：

- `PROVEN_NO_ALIAS`
  - 只覆盖当前代码库能直接证明的窄集合
  - 允许继续使用 direct-copy-to-slot 快路
- `MAY_ALIAS`
  - 任何无法稳定证明 no-alias 的组合都必须落到这里
  - 包括 direct self-assign、`ref=true` parameter 间接别名、address-bearing source、`ExprTargetRef` 等 open provenance surface

### 6.2 `MAY_ALIAS` 路径的强制顺序

`MAY_ALIAS` 路径至少必须满足：

1. 先从 `source_ptr` 复制出 stable carrier
2. stable carrier 完成后才允许 destroy old target
3. destroy old target 后再把 stable carrier 写入 target
4. stable carrier 被 target consume 后不得再按普通 temp destroy 一次

这里的 stable carrier 必须是真独立 owner，不能是浅层 struct copy。

### 6.3 当前已明确防住的错误

当前实现已经明确防止以下错误重新出现：

- `destroy(target); target = copy(&target);`
- “先 copy 到普通 temp，再在 target 接管后 destroy temp”导致的二次销毁
- backing-field getter / self-setter 场景重新退化为 shallow temp materialization

## 7. Godot 对齐边界

### 7.1 `Packed*Array` provenance 比 family 更重要

是否需要 writeback，不能只看“当前 carrier 是不是 `Packed*Array`”。当前必须继续区分：

- property route
  - 例如 `poly.polygon.push_back(...)`
  - 需要 property writeback
- method result route
  - 例如 `curve.get_baked_points().push_back(...)`
  - 不得写回到原 receiver
- ordinary local route
  - 例如 `var pts = poly.polygon; pts.push_back(...)`
  - 只修改 local，不反向写回 property

同一个 runtime family 若 provenance 不同，Godot 语义就可能不同。runtime gate 只能判断当前层 carrier family，不能替代 provenance。

### 7.2 property-backed dynamic `Variant` receiver

对显式 `Variant` property / field 上的 mutating receiver call，当前已经冻结为 property-backed writeback，而不是 plain snapshot。

示例：

- `self.payloads.push_back(seed)`
- `box.payloads.push_back(seed)`

当前允许出现这样的分层：

- ordinary property read 仍走普通 value surface
- writable-route payload 单独冻结真正的 owner / leaf / reverse-commit route
- 因此 entry read 与 commit owner 可以不同

`box.payloads.push_back(seed)` 的当前语义是：

- ordinary `MemberLoadItem` 可以先通过 `box` 的 ordinary published value 做 property read
- 但 payload root 仍保留 direct-slot owner `box`
- 因而 runtime-gated property store 最终写回 `box`

这不是偶发差异，而是当前 CFG / body-lowering 的刻意分层。

## 8. 长期风险与后续工程提醒

### 8.1 accessor / setter 重入风险

当前 backend 已经切断最短直接自递归：当默认 getter/setter helper 在“当前 accessor + 当前实例 + 当前同 property”场景下再次读写同 property 时，backend 会直接走 backing field fast path，避免立即无限递归。

但这只是 safety net，不是完整 accessor 语义。未来 frontend 支持 getter/setter 时，必须把以下语义前移为显式合同：

- `current accessor + current instance + current property -> backing field`
- `other.prop`
- `self.other_prop`
- `super.prop`
- 跨 property / 跨 helper / 间接循环

这些路线不能继续依赖 backend 仅凭函数名和 owner 类型去猜。

### 8.2 payload 双账本风险

payload 若重新承载求值解释，或 body lowering 恢复 AST replay，就会重新制造“ordinary items 一套、payload 一套”的双账本漂移。这条边界必须持续防守。

### 8.3 runtime helper 覆盖面风险

`gdcc_variant_requires_writeback(...)` 对未列举 future `Variant` kind 的默认策略必须保持保守 `true`。若改成保守 `false`，新增 value-semantic family 会 silent false-negative。

### 8.4 性能与 code size 风险

dynamic / `Variant` route 的 runtime gate 会增加分支和 block 数量。当前的开销控制手段必须保持：

- 只对 runtime-open `Variant` carrier 发 gate
- statically shared/reference carrier 直接 fast-skip
- statically value-semantic carrier 直接 inline writeback
- 不为 exact route、shared carrier 或 ordinary read-only call 平白增加 scaffold

## 9. 回归测试基线

至少要继续覆盖以下测试面：

- frontend CFG / body lowering
  - frozen writable-route payload shape
  - assignment payload-only final store
  - carrier-threaded reverse commit
  - static gate 只看 current carrier type
  - dynamic receiver runtime gate + continuation block threading
  - direct-slot alias publication 的 happy / negative path
  - `IdentifierExpression + SELF` fail-fast
  - assignment / call 不得重复 getter / subscript get / pack / unpack
- backend / codegen
  - `gdcc_variant_requires_writeback(...)` helper emission
  - value-semantic / shared family matrix
  - non-object slot write `PROVEN_NO_ALIAS` / `MAY_ALIAS`
  - getter-self / setter-self backing-field fast path
- integration
  - typed property-backed mutating receiver
  - dynamic `Variant` property-backed mutating receiver
  - `PackedInt32Array` 与 `Array` 两条 runtime helper 路线
  - key/index side effect route

当前代码库中已被明确用作锚点的测试类包括：

- `FrontendWritableRouteSupportTest`
- `FrontendLoweringBodyInsnPassTest`
- `FrontendBodyLoweringSessionTest`
- `FrontendCfgGraphTest`
- `FrontendLoweringToCProjectBuilderIntegrationTest`
- `CBodyBuilderPhaseCTest`
- `CAssignInsnGenTest`
- `CLoadPropertyInsnGenTest`
- `CStorePropertyInsnGenTest`
- `CGenHelperTest`

## 10. 非目标

当前实现明确不做：

- 把 writable route 提升成公共 LIR place/reference model
- 为 dynamic call 新增专用 LIR instruction
- 让 backend 从 call/property/index instruction 反推 frontend owner route
- 提前把 lambda/capture surface 并入 direct-slot alias publication
- 用阶段性计划文档继续充当长期事实源

## 工程反思

1. complex writable target 的核心不是“多几个 store 指令”，而是把 value evaluation 与 owner-route writeback 明确拆成两层合同。
2. direct-slot alias publication 必须保持极窄 surface；一旦把它泛化成普通 identifier/self read，就会很快引入难以证明的 rebinding 与 alias 漂移。
3. runtime gate 只能回答 family，不能回答 provenance。property route、method result route、ordinary local route 的区分必须继续由 payload 冻结。
4. backend alias-safety 不能只修 direct self-assign；只有把 `PROVEN_NO_ALIAS` / `MAY_ALIAS` 明确收口到 shared support，才能防住后续更隐蔽的间接别名回归。
5. 实现文档必须只保留当前事实、长期约束与风险边界；实施步骤、完成记录与临时验收应留在提交历史，而不是继续污染事实源。
