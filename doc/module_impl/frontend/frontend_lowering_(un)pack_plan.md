# Frontend Lowering `Variant` `(un)pack` 修复计划

> 本文档记录 frontend 在 CFG -> LIR body lowering 阶段补齐 `Variant` 边界装箱/拆箱语义的实施计划。当前问题不只包含 `T -> Variant` 的 boxing，也包含 stable `Variant` source 流向 concrete 参数 / 变量 / 返回槽位时缺少显式 `unpack_variant`。这里的 stable `Variant` source 包括 `RESOLVED(Variant)` 与 `DYNAMIC(Variant)`。

## 文档状态

- 状态：实施中（第 1 / 2 / 3 / 4 / 5 / 6 步已完成）
- 更新时间：2026-04-04
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/scope/resolver/ScopeMethodResolver.java`
  - `src/test/java/dev/superice/gdcc/frontend/**`
- 关联事实源：
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_type_system.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/backend/cbodybuilder_implementation.md`
  - `doc/module_impl/backend/assign_insn_implementation.md`
- 关键实现锚点：
  - `FrontendDeclaredTypeSupport`
  - `FrontendExpressionType`
  - `FrontendAssignmentSemanticSupport`
  - `FrontendExpressionSemanticSupport`
  - `FrontendChainReductionHelper`
  - `ScopeMethodResolver`
  - `FrontendBodyLoweringSession`
  - `FrontendSequenceItemInsnLoweringProcessors`
  - `FrontendAssignmentTargetInsnLoweringProcessors`
  - `FrontendCfgNodeInsnLoweringProcessors`

---

## 1. 背景

### 1.1 Source 语义背景

GDScript 的 source-level 语义允许两类以 `Variant` 为边界的值流动：

- concrete 值写入 `Variant` 槽位
  - 例如 `var box: Variant = 1`
  - 例如 `func take(v: Variant): pass` 调用 `take(1)`
- `Variant` 值流入 concrete 槽位
  - 例如未声明类型、因此默认视作 `Variant` 的局部变量 / 参数 / 返回值
  - 例如 `var any = 1; var x: int = any`
  - 例如 `func id(v) -> int: return v` 其中 `v` 未声明类型，默认是 `Variant`
  - 例如动态 receiver / 动态 member / 动态 call 路径继续产生 `Variant` 结果，再流入 concrete 参数或返回槽位

这里的关键点不是“`Variant` 在 backend assignability 里与所有类型互相兼容”，而是：

- source 层允许这些边界
- runtime 需要在边界显式做 container/value conversion
- LIR 已经用 `pack_variant` / `unpack_variant` 为这种边界提供了正式指令

因此 frontend 的责任不是“宽松地放过类型检查”，而是把 source 允许的边界显式降成 IR instruction。

### 1.2 当前仓库已经发布的事实

当前仓库已经冻结了几条和本问题直接相关的事实：

1. `FrontendDeclaredTypeSupport.resolveTypeOrVariant(...)` 会把以下声明发布为 `Variant`：
   - 缺失类型声明
   - 空类型文本
   - `:=` 推导声明的初始 inventory 形态
2. supported local `:=` 会在 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 后回填具体类型。
3. `FrontendExpressionType` 明确区分：
   - `RESOLVED(Variant)`：稳定发布为 `Variant`
   - `DYNAMIC(Variant)`：runtime-dynamic 语义，但 `publishedType` 同样是 `Variant`
4. assignment / return 当前只承认一条单向规则：
   - target 是 exact `Variant` 时接受任意已解析 source
   - 其余回退 `ClassRegistry.checkAssignable(...)`
5. condition lowering 已经冻结为：
   - `bool` source：直接 branch
   - `Variant` source：`unpack_variant -> bool`
   - 非 `bool` 且非 `Variant` source：`pack_variant -> unpack_variant -> bool`

这说明当前架构已经接受下面这件事：

- source-level compatibility 可以宽于 backend strict assignability
- 宽出来的那部分必须在 lowering 侧显式补 instruction

只是目前这条思想只在 condition normalization 上落地，还没有完整覆盖 assignment / call / return。

### 1.3 本次 stable `Variant` source 的范围

本次计划不再只覆盖 exact `Variant` source，而是覆盖任意 stable `Variant` source：

- `RESOLVED(Variant)`
- `DYNAMIC(Variant)`

这样做的原因有三点：

1. 这类写法在 GDScript 中是高频常见路径，尤其是动态 receiver / 动态 member / 动态 call 之后再参与 typed boundary 的写法。
2. 允许 stable `Variant` source 自动拆箱到 concrete target，可以显著减少 frontend 为“保留 source provenance”而引入的额外改动面。
3. runtime 安全不再强压给 frontend 静态证明；后续由 backend 在 `PackUnpackVariantInsnGen` 路径为 `unpack_variant` 补充真实类型校验与错误处理。

因此，本次 plan 的边界改为：

- frontend 对所有 stable `Variant` source 都允许流向 concrete target
- lowering 统一插入 `UnpackVariantInsn`
- runtime tag/type correctness 留给后续 backend `(un)pack` 指令实现闭合

---

## 2. 问题定义

### 2.1 当前缺口一：`T -> Variant` 只被“承认”，没有被“物化”

当前 body lowering 中，以下路径仍然直接传递 source slot，而不在 target `Variant` 边界插入 `PackVariantInsn`：

- local initializer
- ordinary assignment
- property assignment
- fixed call arguments
- vararg tail arguments
- non-void return

这会导致：

- frontend 语义层允许 `T -> Variant`
- 但生成的 LIR 仍把 concrete slot 直接喂给 strict backend contract

### 2.2 当前缺口二：stable `Variant` source -> concrete target 没有被系统建模

当前对于 stable `Variant` source 流向 concrete target，还存在两类缺口：

- sema 层仍大量直接使用 `ClassRegistry.checkAssignable(...)`
- lowering 层没有“source 是 `Variant`，target 是 concrete 时插入 `UnpackVariantInsn`”的共享基础设施

这意味着以下路径都会出问题：

- `var any = 1; take_i(any)` 在 sema 阶段可能被判成 no applicable overload
- `var any = 1; var x: int = any` 在 type-check 阶段被拒绝
- `func id(v) -> int: return v` 在 return contract 阶段被拒绝
- `var v: Variant = get_anything(); take_i(v)`
- `var d = some_variant.foo(); take_i(d)` 其中 `d` 是 `DYNAMIC(Variant)`

### 2.3 当前缺口三：chain / method / call 路径与 condition route 对 `Variant` 的处理不一致

当前 frontend 已经在 condition normalization 上承认：

- 只要 source 是 `Variant`，就可以在 lowering 时走 `unpack_variant -> bool`

但 assignment / fixed call / return 还没有把这条思想扩展到其他 concrete target。

结果就是：

- condition route 已经能接受 stable `Variant` source
- assignment / call / return 仍把 stable `Variant` source 当成 strict assignability failure

### 2.4 当前前后端不一致的表现

当前系统会出现这样的不一致：

1. source contract 实际需要 `pack_variant` / `unpack_variant`
2. frontend sema 对边界只做了“允许/拒绝”的粗略判断
3. frontend body lowering 没有把允许边界系统性物化
4. backend 仍严格按 LIR type contract 校验

结果是：

- 用户看到的是“本来应该合法的 GDScript 在 frontend/backend 边界爆类型错误”
- 但根因不在 backend，而在 frontend 对 `Variant` 边界的 materialization 不完整

---

## 3. 根因分析

### 3.1 根因一：当前 concrete-slot compatibility 只有 target-side `Variant`

当前共享规则只表达了：

- target 是 exact `Variant` -> 接受任意 source
- 否则 -> `ClassRegistry.checkAssignable(source, target)`

它没有表达 source-side stable `Variant`。

因此对于：

- `Variant -> int`
- `Variant -> String`
- `Variant -> Object`

只要目标不是 `Variant`，frontend 就会直接掉回 strict assignability，并把本应由 runtime `unpack_variant` 负责的边界提前当作不兼容。

### 3.2 根因二：call applicability 仍沿用 strict assignability 基线

以下路径当前都还在用 strict assignability 思维：

- `FrontendExpressionSemanticSupport.matchesCallableArguments(...)`
- `FrontendExpressionSemanticSupport.buildCallableMismatchReason(...)`
- `FrontendChainReductionHelper.selectConstructorOverload(...)`
- `ScopeMethodResolver` 内部 candidate selection

这会让 stable `Variant` source 在 fixed parameter 场景中被过早拒绝。

### 3.3 根因三：body lowering 目前只有 truthiness normalization 的 `(un)pack` 基础设施

当前 `FrontendCfgNodeInsnLoweringProcessors` 只在 condition route 中使用：

- `PackVariantInsn`
- `UnpackVariantInsn`

而 `FrontendSequenceItemInsnLoweringProcessors` / `FrontendAssignmentTargetInsnLoweringProcessors` / stop-node lowering 仍按“source slot 已经天然满足 target slot”来工作。

这意味着即便 sema 承认 stable `Variant` source -> concrete target，lowering 也没有现成的共享物化入口。

### 3.4 根因四：backend strict contract 本来就不会替 frontend 补洞

backend 当前多个位置都严格要求 source/target 已经满足 LIR contract：

- `AssignInsnGen`
- `CallGlobalInsnGen`
- `CallMethodInsnGen`
- `StorePropertyInsnGen`
- `CBodyBuilder.returnValue(...)`

虽然 backend 自己也会在 engine/property/dynamic-call 边界使用 pack/unpack helper，但那是后端消费 engine API 的 contract，不是 frontend source boundary 的兜底机制。

frontend 若不显式 materialize 自己的 `Variant` 边界，backend 不应该也不能代替它猜。

---

## 4. 为什么不应采用其他修法

### 4.1 不应把双向 `Variant` 兼容直接塞进 `ClassRegistry.checkAssignable(...)`

不建议把 `ClassRegistry.checkAssignable(...)` 放宽成：

- target 是 `Variant` -> true
- 或 source 是 `Variant` -> true

原因：

1. `ClassRegistry.checkAssignable(...)` 当前被文档明确定位为 backend/global strict assignability 基线。
2. backend、C codegen、shared resolver、container covariance 等大量路径都依赖它的 strict 语义。
3. 本次需要的是“允许 + 显式 materialize”，不是“允许但语义不落地”。

如果直接把它放宽，frontend 反而更难知道哪里应该插 `pack_variant` / `unpack_variant`。

### 4.2 不应让 frontend 继续把 runtime 安全假装成 compile-time strict rejection

本次修改后的预期模型是：

- frontend 负责承认 source-level `Variant` boundary
- frontend lowering 负责显式插入 `PackVariantInsn` / `UnpackVariantInsn`
- backend 后续在 `PackUnpackVariantInsnGen` 路径补上真实 unpack 前的类型校验与错误处理

因此，不应继续用 frontend strict assignability 去提前拒绝 stable `Variant` source -> concrete target。这会让语言层高频写法无谓失败，也会迫使 frontend 维持一套与运行时行为不一致的伪静态规则。

### 4.3 不应把本次工作扩大成一般性 implicit conversion 工程

本次计划虽然扩大为 stable `Variant` source 都可自动拆箱到 concrete target，但仍不解决：

- `int -> float`
- `StringName <-> String`
- 其他当前仓库文档明确未支持的隐式转换

文档和测试必须继续按 strict contract 锚定，避免把一次局部修复升级成类型系统重写。

---

## 5. 设计原则

本次实施必须遵守以下原则：

1. compatibility 与 lowering 必须分层
   - sema 决定某条 source 路径是否允许
   - lowering 只负责把允许但非 strict-LIR 的边界显式物化
2. stable `Variant` boundary 统一处理
   - target 是 `Variant` 时自动 pack
   - source 是 stable `Variant` 时自动 unpack
3. `(un)pack` 插入必须集中
   - 通过 session/helper 统一收口
   - 不允许每个 processor 手写各自的 `if (sourceType instanceof GdVariantType)` 分支
4. shared strict resolver contract 尽量保持
   - frontend 若需要更宽 compatibility，应通过 frontend-facing helper 或 predicate-aware 入口接入
   - backend 继续可以保留 strict 默认路径
5. 不改变 CFG published facts 模型
   - 当前 `slotTypes()`、`expressionTypes()`、`resolvedCalls()`、`resolvedMembers()` 足够承载修复
6. runtime 安全后移到 backend `(un)pack` 实现
   - frontend 不再试图对 stable `Variant` source 做过强静态证明
   - 后续由 backend 在 unpack 路径完成类型校验与错误处理

---

## 6. 建议的语义模型

### 6.1 将 concrete boundary 决策统一成一条对称规则

建议把 concrete target/source 边界统一收口成一个方向敏感的 decision helper。它不是简单 boolean，而是“兼容性 + 需要的 lowering 动作”的组合。

建议的决策矩阵如下：

1. target 是 exact `Variant`
   - source 不是 `Variant` -> `ALLOW_WITH_PACK`
   - source 已是 `Variant` -> `ALLOW_DIRECT`
2. source 是 stable `Variant`，target 不是 `Variant`
   - `ALLOW_WITH_UNPACK`
3. 其余情况
   - 若 `ClassRegistry.checkAssignable(sourceType, targetType)` -> `ALLOW_DIRECT`
   - 否则 -> `REJECT`

这里的 stable `Variant` source 指：

- `RESOLVED(Variant)`
- `DYNAMIC(Variant)`
- slot type 明确是 `Variant` 的 stable identifier / local / parameter / property read

### 6.2 sema 层只负责承认 boundary，不负责证明运行时 unpack 一定成功

在这套模型中，frontend sema 的职责是：

- 判断 stable `Variant` source 流向 concrete target 是 source-level 允许的
- 不再把这种路径当成 strict assignability failure

frontend sema 不负责：

- 判断 runtime `Variant` 当前真实 tag 是否一定能解出目标 concrete type
- 生成 unpack 失败时的 runtime handling

这些留给后续 backend `PackUnpackVariantInsnGen` 改造闭合。

### 6.3 lowering 侧只看“需要 pack / unpack / direct”

进入 body lowering 后，不再重复判断 source-level 合法性。lowering 只消费：

- source slot id
- source type
- target type
- sema 已经允许该 boundary 的事实

建议统一成 session helper：

- target 是 `Variant` 且 source 不是 `Variant` -> 新 temp + `PackVariantInsn`
- source 是 `Variant` 且 target 不是 `Variant` -> 新 temp + `UnpackVariantInsn`
- 否则直接返回原 slot id

换言之：

- sema 负责“哪些 `Variant -> concrete` 是合法的”
- lowering 负责“合法之后怎么显式变成 LIR”

---

## 7. 目标修复面

### 7.1 语义层修复面

必须统一接通以下 consumer：

- ordinary local initializer 的 typed gate
- class property initializer 的 typed gate
- return typed gate
- bare/global/static call 的 fixed-parameter applicability
- chain instance/static method call 的 applicability
- constructor overload 选择
- mismatch reason builder

### 7.2 lowering 层修复面

必须显式 materialize 以下边界：

- local initializer
- ordinary assignment target store
- property / attribute-property assignment
- fixed call arguments
- vararg tail arguments 的 boxing
- return slot

### 7.3 本次 stable-`Variant` source 的正向范围

本次至少覆盖以下 stable-`Variant` source：

- 未声明类型而默认 `Variant` 的 parameter / local / property / return
- 显式声明 `Variant` 的 parameter / local / property / return
- 任何稳定发布为 `RESOLVED(Variant)` 的 expression root
- 任何稳定发布为 `DYNAMIC(Variant)` 的 expression root

### 7.4 当前明确不纳入本次范围的点

当前不把以下内容自动纳入：

- `int -> float`
- container element 的一般性 widening
- cast / `as` / `is` 路径
- string family implicit conversion
- object nullability widening

---

## 8. 分步骤实施计划

## 8.1 第一步：抽出对称的 `Variant` boundary compatibility shared helper

### 目标

把 frontend 里“target `Variant` 需要 pack，stable `Variant` source 需要 unpack，其余回退 strict assignability”的规则统一收口。

### 当前状态

- 状态：已完成（2026-04-04）
- 已落地产出：
  - 新增 `FrontendVariantBoundaryCompatibility`，以 `Decision` 枚举统一表达 `ALLOW_DIRECT` / `ALLOW_WITH_PACK` / `ALLOW_WITH_UNPACK` / `REJECT`
  - `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)` 已改为复用该 helper，`DYNAMIC` target 仍保留在 assignment helper 内部处理
  - `FrontendExpressionSemanticSupport` 的 bare/global/static fixed-parameter 匹配与 mismatch reason 已切到同一规则
  - `FrontendChainReductionHelper` 的 constructor fixed-parameter 匹配与 mismatch reason 已切到同一规则
- 已补测试锚点：
  - `FrontendVariantBoundaryCompatibilityTest`
  - `FrontendAssignmentSemanticSupportTest`
  - `FrontendExpressionSemanticSupportTest`
  - `FrontendTypeCheckAnalyzerTest`
- 本步确认仍保持 strict 的负向约束：
  - `int -> float` 不放宽
  - 非 `Variant` 边界的普通类型不匹配仍失败
  - backend/global 的 `ClassRegistry.checkAssignable(...)` 没有被修改

### 建议实施内容

- 在 frontend sema 邻域新增一个共享 helper，职责严格限定为：
  - 输入：`sourceType`, `targetType`
  - 输出：boundary decision
  - 行为：
    - target 为 exact `Variant` -> `ALLOW_WITH_PACK` / `ALLOW_DIRECT`
    - source 为 `Variant` -> `ALLOW_WITH_UNPACK` / `ALLOW_DIRECT`
    - 其他 -> 回退 `classRegistry.checkAssignable(...)`
- 让以下路径改用该 helper：
  - `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)`
  - `FrontendExpressionSemanticSupport.matchesCallableArguments(...)`
  - `FrontendExpressionSemanticSupport.buildCallableMismatchReason(...)`
  - `FrontendChainReductionHelper.selectConstructorOverload(...)`
  - `ScopeMethodResolver` 的 frontend-facing applicability path

### 关键设计点

- helper 只表达 boundary compatibility，不直接发 diagnostic。
- `DYNAMIC` target 的 runtime-open 处理继续留在 assignment helper 内部。
- 对 stable `Variant` source 的放宽以 `sourceType instanceof GdVariantType` 为边界，不再要求 frontend 保留更细的 provenance 才能走通。

### 验收细则

- happy path：
  - `func take_i(v: int): pass` 可以接受 `var any = 1; take_i(any)`
  - `func take_i(v: int): pass` 可以接受来自 `DYNAMIC(Variant)` 的 stable call result
  - `var x: int = any_variant` 可以通过 typed gate
  - `func id(v) -> int: return v` 可以通过 return typed gate
  - `func take(v: Variant): pass` 继续接受 concrete source
- negative path：
  - `int -> float` 仍不因此变为可接受
  - `StringName <-> String` 仍不因此放宽
  - `null -> int` / `null -> float` 等非 object target 仍保持拒绝

---

## 8.2 第二步：修 fixed call / constructor applicability

### 目标

让 bare/global/static/instance/constructor 的 fixed-parameter 匹配都承认 stable `Variant` source -> concrete target。

### 当前状态

- 状态：已完成（2026-04-04）
- 已落地产出：
  - bare/global/static call 现在直接走第一步 shared helper
  - `ScopeMethodResolver` 新增 predicate-aware 的 fixed-parameter applicability 入口；原 strict 默认入口保持不变
  - frontend chain instance/static method 路径现在向 `ScopeMethodResolver` 传入 `Variant` boundary-aware predicate
  - constructor overload 选择已复用同一 shared helper
- 已补测试锚点：
  - `ScopeMethodResolverTest` 明确锚定“frontend 新入口可接受 stable Variant source，而默认 strict 入口不变”
  - `FrontendChainReductionHelperTest` 覆盖 instance/static/constructor 的 stable `Variant` source 正向样本，以及 constructor ambiguity 的 fail-closed
  - `FrontendChainBindingAnalyzerTest` 覆盖 analyzer 级别的 instance/static/constructor 集成路径
- 本步确认的负向约束：
  - stable `Variant` source 导致多个 overload 同时可接受时，仍按 ambiguity / fail-closed 处理
  - backend 继续使用 strict resolver 默认路径，不会因为 frontend 放宽而被动改变

### 建议实施内容

- bare/global/static call：
  - 直接复用新的 shared helper
- constructor 选择：
  - 复用同一 helper
- chain instance/static method：
  - 保持 `ScopeMethodResolver` strict 默认入口不动
  - 新增 frontend-facing predicate-aware 入口，或增加只供 frontend 使用的 candidate selection path
  - frontend 传入 `Variant` boundary-aware compatibility，backend 仍继续传 strict `classRegistry.checkAssignable(...)`

### 原因

如果只修 assignment / return，不修 fixed call applicability，很多高频源码还是会在更早阶段被拒绝。

### 验收细则

- happy path：
  - bare call、instance method call、static method call、constructor overload 都能接受 stable `Variant` source 实参
  - mismatch message 仍能指出真实 parameter index / name / type
- negative path：
  - backend 使用的 strict resolver 默认路径不变
  - overload 选择若因多个 candidate 都接受 stable `Variant` source 而变得不唯一，仍必须保持 fail-closed / ambiguous，而不是任意挑一个

---

## 8.3 第三步：在 body lowering session 中引入统一 boundary materialization helper

### 目标

把 pack / unpack 的插入统一收口到 `FrontendBodyLoweringSession` 内部，而不是散落在各个 processor 中。

### 当前状态

- 状态：已完成（2026-04-04）
- 已落地产出：
  - `FrontendBodyLoweringSession` 新增 ordinary boundary helper `materializeFrontendBoundaryValue(...)`
  - helper 统一承载四类动作：direct / `PackVariantInsn` / `UnpackVariantInsn` / object-typed `LiteralNullInsn`
  - helper 的 temp 命名已收口在 session 内部递增计数器，不把命名细节暴露成下游 contract
- 已补测试锚点：
  - `FrontendBodyLoweringSessionTest`
- 本步确认的边界：
  - condition normalization 继续保留在 `FrontendCfgNodeInsnLoweringProcessors`，没有和 ordinary boundary helper 机械合并
  - helper 当前只负责 materialization，不重复承担 sema legality 或 strict assignability 的判定

### 建议实施内容

- 在 `FrontendBodyLoweringSession` 中新增一个 helper，职责类似：
  - 输入：
    - `LirBasicBlock block`
    - `String sourceSlotId`
    - `GdType sourceType`
    - `GdType targetType`
    - 一段短用途标识
  - 输出：
    - 可安全写入 target boundary 的最终 slot id
  - 行为：
    - target `Variant` 且 source 非 `Variant` -> temp + `PackVariantInsn`
    - source `Variant` 且 target 非 `Variant` -> temp + `UnpackVariantInsn`
    - 其余 -> 原 slot

### 关键设计点

- lowering 不再区分 `RESOLVED(Variant)` 与 `DYNAMIC(Variant)`；只要 sema 允许，stable `Variant` source 一律通过 `UnpackVariantInsn` 物化。
- temp 名称不应成为测试 contract。
- condition normalization 继续保留自己的命名与分支逻辑；不要把 truthiness helper 和 ordinary boundary helper 机械合并。

### 验收细则

- happy path：
  - source concrete -> target `Variant` 时稳定插入一个 `PackVariantInsn`
  - source `Variant` -> target concrete 时稳定插入一个 `UnpackVariantInsn`
  - source / target 同为 `Variant` 时不重复包裹
  - strict direct-assignable concrete -> concrete 时不引入多余指令
- negative path：
  - 不要求 temp 名称对外稳定
  - 不让各个 processor 自己拼装重复逻辑

---

## 8.4 第四步：接通 local initializer 与 ordinary/property assignment

### 目标

让 assignment family 同时支持：

- concrete -> `Variant` 的 pack
- stable `Variant` -> concrete 的 unpack

### 当前状态

- 状态：已完成（2026-04-04）
- 已落地产出：
  - `FrontendLocalDeclarationInsnLoweringProcessor` 现在会按 local slot type 对 initializer 统一走 session helper，再发最终 `AssignInsn`
  - `FrontendIdentifierAssignmentInsnLoweringProcessor` 现在会针对 local / parameter / capture / property target 先物化 ordinary `Variant` boundary，再发 `AssignInsn` / `StorePropertyInsn` / `StoreStaticInsn`
  - `FrontendAttributePropertyAssignmentInsnLoweringProcessor` 现在会直接复用 published member result type 做 target-side boundary materialization
- 已补测试锚点：
  - `FrontendLoweringBodyInsnPassTest.runMaterializesVariantBoundariesForLocalInitializersAndOrdinaryPropertyAssignments`
  - `FrontendLoweringBodyInsnPassTest.runKeepsDirectLocalPropertyAndReturnRoutesInstructionFreeWhenNoVariantBoundaryExists`
- 本步确认仍保持的边界：
  - `SubscriptExpression` / `AttributeSubscriptStep` store 路径未被顺手扩大成 typed element conversion 工程
  - 非 `Variant` boundary 的 direct local/property store 继续不引入多余指令

### 建议实施内容

- `FrontendLocalDeclarationInsnLoweringProcessor`
  - 读取 local target slot type
  - 对 initializer source 做 boundary materialization
  - 再发 `AssignInsn`
- `FrontendAssignmentInsnLoweringProcessor`
  - 不能再把 `rhsSlotId` 原样交给 target lowering
  - 必须按 target slot type 先 materialize
- `FrontendAssignmentTargetInsnLoweringProcessors`
  - bare identifier 写 local/parameter/capture：按 target slot type materialize
  - bare identifier 写 property：按 property declared type materialize
  - attribute-property assignment：按 resolved member/property type materialize

### 当前建议范围

本步优先覆盖：

- ordinary local / parameter / capture / property 这类明确有 slot type 的 target

本步不要求顺手扩大到：

- typed subscript element assignment
- 其他尚未冻结 element contract 的容器写路径

### 验收细则

- happy path：
  - `var box: Variant = 1` -> `PackVariantInsn + AssignInsn`
  - `var any = 1; var x: int = any` -> `UnpackVariantInsn + AssignInsn`
  - `var v: Variant = get_anything(); var x: int = v` -> 同样走 unpack
  - `var d = some_variant.foo(); payload_int = d` -> `StorePropertyInsn` 前有 `UnpackVariantInsn`
  - `payload_variant = 1` -> `StorePropertyInsn` 前有 `PackVariantInsn`
- negative path：
  - `var x: int = "x"` 仍继续失败
  - 非 `Variant` boundary 不新增额外转换

---

## 8.5 第五步：接通 fixed call arguments 与 vararg tail

### 目标

让 call applicability 与 call lowering 同时支持双向 stable-`Variant` 边界。

### 当前状态

- 状态：已完成（2026-04-04）
- 已落地产出：
  - `FrontendBodyLoweringSession` 新增 call-scoped helper，按 selected callable signature 统一处理 fixed parameter 与 vararg tail 的 boundary materialization
  - `FrontendCallInsnLoweringProcessor` 现在不再直接透传 `argumentValueIds`，而是先按形参类型物化 `PackVariantInsn` / `UnpackVariantInsn`
  - vararg tail 继续固定视为 `Variant`，concrete extra arg 会 pack，stable `Variant` extra arg 保持 direct
  - helper 对 instance call 做了 receiver-separated normalization：若 signature metadata 仍暴露前置 `self` 形参，会在 argument materialization 前剥离该隐式 receiver
- 已补测试锚点：
  - `FrontendLoweringBodyInsnPassTest.runMaterializesVariantBoundariesForFixedCallsAndVarargTailArguments`
- 本步确认仍保持的边界：
  - lowering 只消费已选中的 callable signature，不重做 overload 选择
  - stable `Variant` vararg tail 不会被重复 pack

### 建议实施内容

- fixed parameter sema：
  - bare/global/static/instance/constructor 统一基于新的 helper 判断 applicability
- fixed parameter lowering：
  - 从 selected callable 的 parameter types 读取 target types
  - concrete -> `Variant` 时插入 `PackVariantInsn`
  - stable `Variant` -> concrete 时插入 `UnpackVariantInsn`
- vararg tail：
  - 继续统一视为 `Variant` tail
  - concrete extra arg -> pack
  - `Variant` extra arg -> direct

### 关键提醒

- `FrontendResolvedCall.argumentTypes()` 表示实参发布类型，不表示形参类型。
- lowering 判定 target parameter type 时，必须继续基于：
  - selected `FunctionDef` / declaration site
  - 或 utility signature metadata
  - 或 resolved method metadata

### 验收细则

- happy path：
  - `func take_i(v: int): pass` 调用 stable `Variant` 实参时 sema 通过，lowering 插入 `UnpackVariantInsn`
  - `func take_any(v): pass` / `func take_any(v: Variant): pass` 调用 concrete 实参时 sema 通过，lowering 插入 `PackVariantInsn`
  - vararg tail 继续统一装箱为 `Variant`
- negative path：
  - overload 若因为 stable `Variant` source 让多个 candidate 都适配，仍需稳定给出 ambiguity，而不是静默偏选
  - mismatch diagnostic 仍能报告真实 parameter type

---

## 8.6 第六步：接通 return boundary

### 目标

让 return slot 同时支持：

- concrete -> `Variant` 的 pack
- stable `Variant` -> concrete 的 unpack

### 当前状态

- 状态：已完成（2026-04-04）
- 已落地产出：
  - `FrontendStopNodeInsnLoweringProcessor` 现在会按当前函数 return slot type 对 published return value 统一走 ordinary boundary helper，再发最终 `ReturnInsn`
  - `return expr` 的 pack/unpack 逻辑现已与 local/assignment/call 共用同一 session helper，不再保留 stop-node 专属分支
- 已补测试锚点：
  - `FrontendLoweringBodyInsnPassTest.runMaterializesVariantBoundariesAtReturnSlots`
  - `FrontendLoweringBodyInsnPassTest.runKeepsDirectLocalPropertyAndReturnRoutesInstructionFreeWhenNoVariantBoundaryExists`
- 本步确认仍保持的边界：
  - direct concrete -> concrete return 继续 instruction-free
  - `ReturnInsn(null)` 的 void 路径未被这次改动扰动

### 建议实施内容

- stop-node lowering 不再直接把 `returnValueIdOrNull` 原样写进 `ReturnInsn`
- 根据当前函数 return slot type 做 boundary materialization：
  - target 是 `Variant` 且 source concrete -> `PackVariantInsn`
  - source 是 `Variant` 且 target concrete -> `UnpackVariantInsn`
  - 其余 direct

### 原因

当前 `FrontendTypeCheckAnalyzer` 已经把 return contract 统一接到 assignment compatibility 上。  
只修 sema 不修 stop-node lowering，会导致：

- type-check 允许
- `ReturnInsn` 仍直接带错类型 slot 进入 backend

### 验收细则

- happy path：
  - `func ret_any() -> Variant: return 1` -> `PackVariantInsn + ReturnInsn`
  - `func ret_i(v) -> int: return v` -> `UnpackVariantInsn + ReturnInsn`
  - `func ret_i(v: Variant) -> int: return v` -> 同样走 unpack
  - `func ret_i(v): return v` 若 stable result 进入 typed return，也按同一边界处理
  - source / target 同为 `Variant` 时无额外指令
- negative path：
  - `void` function 仍不能 `return expr`
  - 非 `Variant` boundary 不新增额外隐式转换

---

## 8.7 第七步：同步文档与 targeted tests

### 目标

把新的 stable-`Variant` boundary contract 固化到文档与测试里，避免未来回归。

### 建议实施内容

- 更新文档：
  - `frontend_rules.md`
  - `frontend_type_check_analyzer_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - 如有必要，补充 chain/call 相关实现文档
- 新增或扩展 tests：
  - sema acceptance
  - body lowering instruction-shape
  - 如有必要的 backend targeted regression

### 文档必须明确说明

1. target `Variant` 接受任意已解析 source，并由 lowering 显式 pack
2. stable `Variant` source 可以流向 concrete target，并由 lowering 显式 unpack
3. `DYNAMIC(Variant)` 也纳入本次自动拆箱范围
4. runtime unpack 成功与否由后续 backend `PackUnpackVariantInsnGen` 负责做类型校验与错误处理
5. 本次不引入 `int -> float` 等其他隐式转换；`Nil -> object` 已作为单独冻结的 frontend boundary contract 接通

---

## 9. 推荐测试清单

建议至少补以下 focused cases：

1. stable `Variant` local -> concrete local
   - `var any = 1`
   - `var x: int = any`
   - 断言 sema 通过，lowering 有 `UnpackVariantInsn + AssignInsn`
2. explicit `Variant` local -> concrete property
   - `var payload: int`
   - `var any: Variant = 1`
   - `payload = any`
   - 断言 `StorePropertyInsn` 前有 `UnpackVariantInsn`
3. dynamic `Variant` expression -> concrete fixed parameter
   - `var v: Variant = get_anything()`
   - `take_i(v.foo())`
   - 断言 sema 通过，lowering 有 `UnpackVariantInsn`
4. stable `Variant` parameter -> concrete return
   - `func id(v) -> int: return v`
   - 断言 return 前有 `UnpackVariantInsn`
5. explicit `Variant` parameter -> concrete return
   - `func id(v: Variant) -> int: return v`
   - 断言与未声明参数一致
6. concrete -> `Variant` fixed parameter
   - `func take_any(v): pass`
   - `take_any(1)`
   - 断言 lowering 有 `PackVariantInsn`
7. concrete -> `Variant` return
   - `func ret_any() -> Variant: return 1`
   - 断言 return 前有 `PackVariantInsn`
8. vararg tail
   - `print(1)` 或等价 compile-ready vararg route
   - 断言 extra arg 已 pack 成 `Variant`
9. ambiguity regression
   - 构造 stable `Variant` source 能匹配多个 overload 的 case
   - 断言仍为 ambiguity / fail-closed，而不是静默偏选
10. negative regression
   - `int -> float`
   - `String -> int`
   - `null -> int`
   - 都应继续失败

---

## 10. 实施顺序建议

建议按以下最小可提交顺序推进：

1. 对称 `Variant` boundary compatibility helper
2. bare/global/static call applicability
3. chain method / constructor applicability
4. body lowering session materialization helper
5. local init + ordinary/property assignment
6. fixed call arg + vararg tail
7. return
8. docs + tests + targeted backend regression

理由：

- 先统一 sema compatibility，可以避免后面做 lowering 时遇到“source 根本进不到 lowering”的假阳性阻塞。
- 再引入 lowering helper，可以让 assignment / call / return 三条路径复用同一物化逻辑。
- return 放后面最自然，因为它复用前面同一套 boundary helper 即可。

---

## 11. 最终验收标准

实现完成后，以下条件必须同时成立：

1. frontend sema 与 body lowering 对 stable `Variant` boundary 的语义一致
2. concrete -> `Variant` 的边界由显式 `PackVariantInsn` 物化
3. stable `Variant` -> concrete 的边界由显式 `UnpackVariantInsn` 物化
4. bare/global/static/instance/constructor 的 fixed-parameter applicability 都已接通
5. `ClassRegistry.checkAssignable(...)` 与 backend strict contract 不被放宽成 source-level 兼容大杂烩
6. runtime unpack 校验责任已在计划中明确后移到 backend `(un)pack` 路径
7. 文档与 targeted tests 同步更新并能稳定锚定回归

---

## 12. 明确非目标

本计划明确不包含：

- `int -> float` 等宽隐式数值提升
- `StringName` / `String` 互转
- 尚未冻结 contract 的 container element widening
- backend 自动帮 frontend 猜 `(un)pack` 点

若后续要支持这些能力，必须单独立项，不得在本计划实施过程中顺手混入。
