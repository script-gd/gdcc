# Void-Return Call Result Slot 修复实施计划

> 本文档记录 `frontend_array_constructor_void_lowering` 当前真实问题的调查结论、修复顺序、实施步骤与验收细则。  
> 它是当前阶段的计划文档，不是最终合同文档；问题修复并稳定后，应把长期约束分别收敛回 frontend/backend 实现文档。

## 文档状态

- 状态：Phase A-B Completed / Phase C-D Planned
- 范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/**`
  - `src/test/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/test/java/dev/superice/gdcc/backend/c/**`
- 更新时间：2026-04-13
- 关联文档：
  - `doc/test_error/frontend_array_constructor_void_lowering.md`
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md`
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/module_impl/backend/cbodybuilder_implementation.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`

## 结论摘要

- 当前问题已经不应再理解成“`Array()` constructor 被 lower 成 `void`”。
- 原始缺陷已经在 Phase A-B 闭环：
  - exact `Array` 上的 void-return method call
  - 当前已确认最小复现为 `Array.push_back(...)`
  - frontend/body lowering 与 backend/codegen 对这类 void call 的 result slot 合同不一致
  - 这条 emitted-call 合同漂移现已修复，不再阻断 `push_back + size()` / `push_back + dynamic helper` 构建
- 这条缺陷的 staged 修复状态如下：
  - Phase A 已完成：backend 已停止在 `__prepare__` 为 `GdVoidType` 变量自动注入默认初始化
  - Phase B 已完成：frontend 已把 exact instance / utility-global 的 void-return call lowering 成 `resultId = null`
- 若要从根源上消除这条缺陷，仍需新增一条后续 phase：
  - 让 `CallItem.resultValueId` 可空化
  - 让 statement-position void call 不再发布 CFG value / slot
  - 这条 phase 会触及 CFG builder、`ValueBuild`、body lowering 与多组测试，因此必须作为单独阶段实施，而不是混进 Phase A-B 的最小合同修复里
- `E:/Projects/gdparser` 对这条问题帮助有限。
  - 快速检索仅看到 source-level typed-array 示例
  - 未发现与 backend/LIR/`__prepare__`/call insn 合同直接相关的实现

## 已确认的问题链路

### 问题链 A：`Array.push_back` 在 metadata / resolver 中本来就是 `void`

- `src/main/resources/extension_api_451.json`
  - `Array.push_back` 缺失 `return_type`
  - `PackedByteArray.push_back` 明确带有 `return_type = "bool"`
- `ScopeTypeParsers.parseExtensionTypeMetadata(...)` 对空白 type metadata 的共享合同是：
  - 缺失/空白 -> `GdVoidType.VOID`
- 上游 Godot 文档也与本地 metadata 一致：
  - `Array.push_back(value: Variant) -> void`
  - 参考：https://docs.godotengine.org/en/stable/classes/class_array.html

### 问题链 B：Phase B 前，frontend 会为 void call 发布 temp slot，并把它传给 call insn

- `FrontendBodyLoweringSupport.collectCfgValueMaterializations(...)` 当前对 `CallItem` 统一发布：
  - `CfgValueMaterializationKind.TEMP_SLOT`
  - 类型来自 call anchor 的 `expressionTypes()`
- 对 `Array.push_back` 来说，这个 temp slot 的类型就是 `GdVoidType`
- `FrontendSequenceItemInsnLoweringProcessors.FrontendCallInsnLoweringProcessor`
  在 Phase B 前会无条件把 `resultSlotId` 传给 exact instance/global call insn
- 于是 LIR 中形成了非法组合：
  - `CallMethodInsn.resultId() != null`
  - 但其对应变量类型为 `GdVoidType`
- 这条 emitted-call 合同漂移已在 Phase B 修复：
  - exact instance void call 现在发出 `CallMethodInsn(null, ...)`
  - utility/global void call 现在发出 `CallGlobalInsn(null, ...)`
  - 但 CFG/materialization 侧仍保留 void temp publication，等待 Phase C 收口

### 问题链 C：Phase A 前 backend 先在 `__prepare__` 炸，再在 call generator 处炸

- `CCodegen.generateFunctionPrepareBlock()` 会为所有非参数、非 ref 变量自动注入默认初始化
- `GdVoidType` 当前没有 special-case，于是会落入默认分支：
  - `new ConstructBuiltinInsn(variable.id(), List.of())`
- 这会在 `ConstructInsnGen` / `CBuiltinBuilder` 处先触发：
  - `Builtin constructor validation failed: 'void' with args [] is not defined in ExtensionBuiltinClass`
- 但即使只修掉 `__prepare__`，backend 仍有第二层 guard rail：
  - `CallMethodInsnGen` 对 void method 明确要求 `resultId == null`
  - `CallGlobalInsnGen` 对 void utility/global call 也有同样约束
- Phase A-B 完成后，当前状态变为：
  - `__prepare__` 不再为 `GdVoidType` 变量注入伪构造
  - frontend 也不再向 exact/global void call 传递 `resultId`
  - backend 现有 invalid-IR guard rail 继续仅服务于坏 LIR fail-fast

## 调查基线（Phase A 前）

### 临时调查测试

- 探针文件：
  - `src/test/java/dev/superice/gdcc/backend/c/build/FrontendArrayConstructorVoidInvestigationTest.java`
- 已确认现象：
  - pure indexed flow 已恢复
  - `push_back + size()` 仍复现
  - `push_back + dynamic helper` 仍复现
  - `construct_builtin` 结果变量仍是 `Array`，不是 `void`
  - 当前失败先落在 `__prepare__ -> construct_builtin(void)`

### 相邻已有测试锚点

- frontend lowering：
  - `FrontendLoweringBodyInsnPassTest`
  - `FrontendBodyLoweringSupportTest`
- backend generator：
  - `CallMethodInsnGenTest`
  - `CallGlobalInsnGenTest`
  - `CPhaseAControlFlowAndFinallyTest`
  - `CConstructInsnGenTest`

这些现有测试已经覆盖了不少相邻合同：

- dynamic instance call 继续以 `Variant` 结果槽发布
- void method / void utility 若带 `resultId`，backend 必须 fail-fast
- `__prepare__` 的 first-write 语义与 typed container 自动注入路径已经有独立测试

因此本次修复优先复用这些测试基线，而不是重新发明一套新 harness。

### `CallItem` / value-root 联动调查

围绕“让 void call 连 CFG value 都不再发布”的额外调查结果如下：

- `ValueOpItem` 总体模型已经允许 nullable result：
  - `resultValueIdOrNull()` 本身就是可空接口
  - `AssignmentItem`、`LocalDeclarationItem` 已经在使用这条 surface
- `FrontendCfgGraph` 的多处 graph validation 已经对 `null` result 做了跳过处理：
  - `validateValueProducerContracts(...)`
  - `validateMergeSourceContracts(...)`
  - `validateWritableRouteContracts(...)`
  - 这说明 graph 核心并不要求“所有 `ValueOpItem` 都必须有 result value id”
- 当前真正阻止 `CallItem` 进入 nullable surface 的关键点不是 graph core，而是 call/value 构图路径：
  - `CallItem` record 本身仍要求 mandatory `resultValueId`
  - `CallItem.resultValueIdOrNull()` 当前返回 `@NotNull`
  - `ValueBuild` record 仍要求 mandatory `resultValueId`
  - `FrontendCfgGraphBuilder.processExpressionStatement(...)` 统一走 `buildValue(...)`
  - bare / attribute / chained call builder 当前都会无条件 `chooseResultValueId(...)`
- body lowering 侧还有两类直接联动：
  - `FrontendBodyLoweringSupport.collectProducedValueMaterialization(...)`
    已能在 `resultValueIdOrNull() == null` 时自然跳过，但 `CallItem` 目前还进不了这条分支
  - `FrontendSequenceItemInsnLoweringProcessors.FrontendCallInsnLoweringProcessor`
    当前仍直接读取 `node.resultValueId()` 并推导 `cfg_tmp_*`
- 当前需要同步更新的测试面至少包括：
  - `FrontendCfgGraphTest`
  - `FrontendCfgGraphBuilderTest`
  - `FrontendLoweringBuildCfgPassTest`
  - `FrontendBodyLoweringSupportTest`
  - `FrontendLoweringBodyInsnPassTest`
  - `FrontendWritableRouteSupportTest`
- 语义边界上，这条 root-fix 只应影响 statement-position 的 `void` call：
  - `FrontendExprTypeAnalyzer` 目前对 discarded `void` expression 本来就走 quiet path
  - `frontend_type_check_analyzer_implementation.md` 已明确：
    - `print(...)` 等 bare utility call 可以稳定发布 `RESOLVED(void)`
    - value-required site 会收到普通 typed diagnostic
  - 因此：
    - `return print(...)`
    - `take_i(print(...))`
    - `var x = print(...)`
    这类 value-required site 仍应继续被 type-check / compile gate 拦截，不属于本 phase 要“自动兼容”的范围

## 修复总原则

### 1. 先切断误导性炸点，再修正 frontend 发射合同

- frontend 当前生成的 IR 已经不合法
- 但最先暴露出来的错误却是 `construct_builtin(void)`，它会误导调查方向
- 因此应先让 backend 不再为 `void` slot 注入伪构造，再让 frontend 停止发出携带 `resultId` 的 void call

### 2. 保持 backend 的 invalid-IR 防线，不要因为 frontend 修复而放松

- `CallMethodInsnGen` / `CallGlobalInsnGen` 现有“void 但带 resultId 就报错”的测试必须保留
- 这些测试的职责是：
  - 守住 backend IR 契约
  - 防止未来其它前端/手工 LIR 再次产出同类坏形态

### 3. `CallItem` / `ValueBuild` 根因修复单独 staged rollout

- graph core 已经部分支持 nullable `resultValueIdOrNull()`
- 但 call/value 构图、`ValueBuild` 与大量测试仍默认“call 一定产值”
- 因此新增的 root-fix phase 必须显式覆盖：
  - `CallItem` record nullable 化
  - `ValueBuild` nullable 化或 statement-expression 专用 discarded-call build surface
  - CFG builder 对 statement-position void call 的特殊构图入口
  - body lowering / materialization 对“无 resultId 的 call item”继续自然消费
- 在该 phase 真正完成前，Phase A / Phase B 仍允许保留“void call CFG temp 仍存在，但 emitted call insn 已不再携带 resultId”的中间态

### 4. 以 targeted tests 为主，不跑全量

- 这条修复是窄合同修正，不需要全量回归才能判断方向是否正确
- 每个 phase 都只运行紧邻的 targeted tests
- 临时探针可以继续使用，但在问题修复后应转成正式回归或被更具体的正式测试取代

### 5. 当前计划不扩展到 static call surface

- 当前直接参与问题链、且已有 backend generator 的 surface 是：
  - `CallMethodInsn`
  - `CallGlobalInsn`
- `FrontendSequenceItemInsnLoweringProcessors` 虽然具备 `CallStaticMethodInsn` lowering 分支，但当前代码库中未见相邻 backend generator 基线
- 因此本计划先把 exact instance / global void-call 合同修正到位
- 若未来 static call compile surface 正式启用，应复用同一条 `void -> resultId null` 合同，不再另起一套规则

## 分阶段实施计划

### Phase A：backend 跳过 `GdVoidType` 的 `__prepare__` 自动初始化

### 目标

- 切断当前最早、最误导的 `construct_builtin(void)` 炸点
- 让 backend 对“void temp 变量存在但尚未被 frontend 修正”的中间态保持可诊断，而不是先在构造器校验处偏航

### 执行状态

- [x] A1. `CCodegen.generateFunctionPrepareBlock()` 已跳过 `GdVoidType` 变量，不再为它们注入默认初始化。
- [x] A2. 已补齐 / 更新 targeted tests：
  - `CPhaseAControlFlowAndFinallyTest` 新增 `__prepare__` skip-void 断言。
  - `FrontendArrayConstructorVoidInvestigationTest` 已把中间态失败预期更新为 backend call generator guard rail。
- [x] A3. 已运行 targeted tests，并确认 Phase A 止血目标与相邻 guard rail 继续成立：
  - `CPhaseAControlFlowAndFinallyTest`
  - `CConstructInsnGenTest`
  - `CallMethodInsnGenTest`
  - `CallGlobalInsnGenTest`
  - `FrontendArrayConstructorVoidInvestigationTest`

### 当前已完成结果

- `__prepare__` 不再为 `GdVoidType` 变量生成初始化指令，也不再触发 `construct_builtin(void)`。
- 临时探针的当前失败点已从 `__prepare__` 后移到 `CallMethodInsnGen`：
  - `push_back` 仍因 `void + resultId` 合同不一致而失败。
  - 这说明误导性炸点已被切断，后续 Phase B 需要继续修 frontend 发射合同。
- 现有 typed container 自动注入与 backend invalid-IR guard rail 继续保持：
  - `Array` / `Dictionary` / packed array 仍走原有 `__prepare__` 注入路径。
  - `CallMethodInsnGen` / `CallGlobalInsnGen` 对 `void + resultId` 仍 fail-fast。
- 当前 targeted validation 结果：
  - `CPhaseAControlFlowAndFinallyTest` 通过。
  - `CConstructInsnGenTest` 通过。
  - `CallMethodInsnGenTest` 与 `CallGlobalInsnGenTest` 通过。
  - `FrontendArrayConstructorVoidInvestigationTest` 通过，并确认失败点已后移到 call generator guard rail。

### 实施步骤

1. 修改 `CCodegen.generateFunctionPrepareBlock()`：
   - 遇到 `GdVoidType` 变量时直接跳过
   - 不生成任何默认初始化指令
2. 保持现有其它类型的 `__prepare__` 注入逻辑不变：
   - object -> `literal_null`
   - `Array` / `Dictionary` -> typed construct 路径
   - packed array -> `construct_array(null)`
3. 增加 targeted unit tests：
   - `CPhaseAControlFlowAndFinallyTest` 或 `CCodegenTest`
     - 新增“`__prepare__` should skip void variables during initialization”
   - 保留/复用 `CConstructInsnGenTest`
     - 确认 array/dictionary/packed array 的注入路径未被回归
4. 临时探针在只完成 Phase A 时的中间期预期需要同步更新：
   - 不再断言 `__prepare__ -> construct_builtin(void)` 失败
   - 若 frontend 尚未修复，则失败应后移到 call generator 的 “has no return value but resultId is provided”

### 验收细则

- `__prepare__` 中不再出现针对 `GdVoidType` 变量的初始化指令
- `CPhaseAControlFlowAndFinallyTest` / `CCodegenTest` 新增断言通过
- `CConstructInsnGenTest` 的 typed array / dictionary / packed array 自动注入断言继续通过
- `CallMethodInsnGenTest` 与 `CallGlobalInsnGenTest` 现有“void + resultId -> fail-fast”测试继续通过

### Phase B：frontend 将 exact void-return call lowering 为 `resultId = null`

### 目标

- 让 frontend 发出的 call instruction 与 backend 现有合同重新对齐
- 修复 exact `Array.push_back(...)` 这类 void-return method call 的核心问题

### 执行状态

- [x] B1. `FrontendSequenceItemInsnLoweringProcessors.FrontendCallInsnLoweringProcessor` 已改为：
  - exact instance route 遇到 `GdVoidType` 时发出 `CallMethodInsn(null, ...)`
  - utility/global route 遇到 `GdVoidType` 时发出 `CallGlobalInsn(null, ...)`
  - `CallStaticMethodInsn` 与 `DYNAMIC_FALLBACK` 合同保持不变
- [x] B2. 已补齐 / 更新 targeted tests：
  - `FrontendLoweringBodyInsnPassTest`
    - exact `Array[int].push_back(...)` direct-slot / nested-call route 现在断言 `resultId == null`
    - `print(seed)` / `print(box)` 等 void global call 现在断言 `resultId == null`
    - `PackedInt32Array.push_back(...)` 继续断言保留 non-null result slot，避免误伤非 void route
  - `FrontendBodyLoweringSupportTest`
    - 新增说明性测试，明确 Phase B 后 CFG/materialization 仍保留 exact void call 的 `TEMP_SLOT`
  - `FrontendArrayConstructorVoidInvestigationTest`
    - 已从 Phase A 中间态失败探针切换为当前成功回归
- [x] B3. 已运行 targeted tests，并确认 frontend 修复与 backend guard rail 同时成立：
  - `FrontendBodyLoweringSupportTest`
  - `FrontendLoweringBodyInsnPassTest`
  - `CallMethodInsnGenTest`
  - `CallGlobalInsnGenTest`
  - `FrontendArrayConstructorVoidInvestigationTest`

### 当前已完成结果

- exact instance void call 的 emitted `CallMethodInsn.resultId()` 现在为 `null`
- utility/global void call 的 emitted `CallGlobalInsn.resultId()` 现在为 `null`
- `push_back + size()` 与 `push_back + dynamic helper` 已恢复为成功构建路径
- non-void exact call 与 dynamic call 的既有结果槽合同保持不变：
  - `PackedInt32Array.push_back(...)` 继续保留 non-null result slot
  - `DYNAMIC_FALLBACK` 继续以 `Variant` 结果槽发布
- 当前仍刻意保留的中间态仅剩一条：
  - `CallItem.resultValueId`
  - `collectCfgValueMaterializations(...)`
  - `declareCfgValueSlots()`
  这条 CFG-side void temp publication 将在 Phase C 再统一收口

### 实施步骤

1. 修改 `FrontendSequenceItemInsnLoweringProcessors.FrontendCallInsnLoweringProcessor`：
   - 对 exact instance route：
     - 若 `resolvedCall.returnType()` 为 `GdVoidType`
     - 发出 `CallMethodInsn(null, ...)`
   - 对 global/utility route：
     - 若 `resolvedCall.returnType()` 为 `GdVoidType`
     - 发出 `CallGlobalInsn(null, ...)`
2. 保持以下既有合同不变：
   - non-void exact call 继续带 result slot
   - `DYNAMIC_FALLBACK` 继续以 `Variant` 结果槽发布
   - mutating receiver writeback 的 leaf selection / reverse commit 顺序不改变
3. 明确不在本 phase 修改：
   - `CallItem.resultValueId`
   - `collectCfgValueMaterializations(...)` 的 `CallItem -> TEMP_SLOT` 发布策略
   - CFG builder / value-root build / alias publication 合同
4. 增加 targeted frontend lowering tests：
   - `FrontendLoweringBodyInsnPassTest`
     - exact `Array.push_back` call 现在 `resultId == null`
     - existing direct-slot / property / nested mutating receiver call tests需要继续断言：
       - receiver side writeback 不变
       - call 本体不再携带 result slot
     - `print(seed)` / `print(box)` 等 void global call 现在 `resultId == null`
   - 如有必要，在 `FrontendBodyLoweringSupportTest` 增加说明性测试：
     - 当前仍允许 CFG/materialization 层保留 void temp slot
     - 但 body pass 不再把它传播到 emitted call instruction

### 验收细则

- `Array.push_back(...)` 对应的 emitted `CallMethodInsn.resultId()` 为 `null`
- void utility/global call 对应的 emitted `CallGlobalInsn.resultId()` 为 `null`
- non-void exact call 与 dynamic call 的现有结果槽行为保持不变
- mutating receiver writeback 相关测试继续通过
- backend 不再因 frontend 产出的 exact/global void call 报 “has no return value but resultId is provided”

### Phase C：`CallItem.resultValueId` 可空化，并让 statement-position void call 不再发布 slot

### 目标

- 从 CFG/materialization 源头切断 void call temp slot
- 让 statement-position void call 不再产生死 `cfg_tmp_*`
- 把当前“Phase B 仅修 emitted call insn”的中间态收敛成更干净的一致合同

### 实施步骤

1. 修改 `CallItem`：
   - `resultValueId` 改为 `@Nullable`
   - `resultValueIdOrNull()` 直接返回可空值
   - `hasStandaloneMaterializationSlot()` 对 `resultValueId == null` 返回 `false`
2. 修改 `FrontendCfgGraphBuilder` 的 call/value 构图入口：
   - 对 bare / attribute / chained call，保留现有“value-required call 一定产值”的 path
   - 为 statement-position discarded void call 增加单独构图入口
   - 该入口只在 call anchor 已稳定发布为 `RESOLVED(void)` 时使用
   - 不得把这条 nullable surface 扩散到 property initializer、return value、argument、assignment RHS 等 value-required site
3. 处理 `ValueBuild` 这一层阻塞点，二选一：
   - 方案 A：让 `ValueBuild.resultValueId` 可空，并明确哪些 build path 允许空
   - 方案 B：保留 `ValueBuild` 的 value-only 合同，但新增 statement-expression 专用 discarded-call builder
   - 当前更推荐方案 B：
     - 影响面更窄
     - 不会把“可空 value root”扩散到所有 expression build path
     - 更符合现有 `processExpressionStatement(...)` 与 `buildValue(...)` 的职责分离
4. 修改 `processExpressionStatement(...)`：
   - statement-position 的 ordinary void call 不再强制经过“必须返回 `ValueBuild`”的 value path
   - 仅需保证 source-order operand build 与 sequence item publication 正常完成
5. 修改 `FrontendBodyLoweringSupport.collectCfgValueMaterializations(...)` / `FrontendBodyLoweringSession.declareCfgValueSlots()` 的验收预期：
   - void `CallItem.resultValueIdOrNull() == null` 时，不再生成 materialization entry
   - 不再声明对应 `cfg_tmp_*`
6. 修改 `FrontendSequenceItemInsnLoweringProcessors.FrontendCallInsnLoweringProcessor`：
   - `resultSlotId` 改为按 `node.resultValueIdOrNull()` 可空推导
   - 对 non-void value-required exact/global call，若结果槽意外缺失，应继续 fail-fast
   - 对 statement-position void call，直接发 `resultId = null`
7. 同步测试计划：
   - `FrontendCfgGraphTest`
     - `CallItem.resultValueIdOrNull()` 允许为 `null`
     - `hasStandaloneMaterializationSlot()` 对 void statement call 为 `false`
   - `FrontendCfgGraphBuilderTest`
     - `values.push_back(seed)` 这类 statement call 不再断言存在 call result value id
     - payload-backed mutating receiver call 仍必须保留 `receiverValueIdOrNull`
   - `FrontendLoweringBuildCfgPassTest`
     - 仅 value-required call 继续锚定 stop-node return value
   - `FrontendBodyLoweringSupportTest`
     - void statement call 不再进入 cfg value materialization 映射
   - `FrontendLoweringBodyInsnPassTest`
     - 不再声明 void call 对应的 `cfg_tmp_*`
     - dynamic non-void call 与 exact non-void call 的现有行为保持不变
   - `FrontendWritableRouteSupportTest`
     - payload-backed mutating call 的校验继续只依赖 receiver slot，不依赖 call result slot

### 验收细则

- statement-position exact/global void call 的 `CallItem.resultValueIdOrNull()` 为 `null`
- 对应 `CallItem.hasStandaloneMaterializationSlot()` 为 `false`
- `collectCfgValueMaterializations(...)` 不再为这类 call 发布 `TEMP_SLOT`
- `declareCfgValueSlots()` 不再声明对应 `cfg_tmp_*`
- body lowering 发出的 call instruction 继续保持 `resultId = null`
- payload-backed mutating receiver writeback 不受影响
- value-required site 若仍把 `void` call 漏进 CFG value path，应继续 fail-fast，而不是 silent fallback

### Phase D：把调查探针转成正式回归并同步文档

### 目标

- 把“当前问题存在性探针”转成“修复后长期回归锚点”
- 清理旧问题描述，避免后续继续按“constructor lowered to void”误判

### 实施步骤

1. 处理 `FrontendArrayConstructorVoidInvestigationTest`：
   - Phase B 已先把断言切到成功路径，以免继续锁定中间态失败
   - 若保留该文件：
     - 进一步改名或收敛成正式 regression test
   - 若拆分：
     - 用更明确命名的正式 regression test 替代
2. 正式锚定三条最小回归形状：
   - pure indexed flow：继续成功
   - `push_back + size()`：成功
   - `push_back + dynamic helper`：成功
3. 同步文档：
   - `doc/test_error/frontend_array_constructor_void_lowering.md`
   - 本计划文档的执行状态
   - 若修复稳定，再把长期约束收回 `frontend_rules.md` / `call_method_implementation.md` 等事实源

### 验收细则

- 不再保留依赖旧错误文案 `construct_builtin(void)` 的成功判定
- 三条最小回归脚本都能完成 targeted build / fake build
- 错误记录文档不再把 indexed flow 写成当前仍失败的 reproducer
- 计划文档状态与实际代码一致

## 建议的 targeted tests

按推荐实施顺序运行：

1. `FrontendCfgGraphTest`
2. `FrontendCfgGraphBuilderTest`
3. `FrontendLoweringBuildCfgPassTest`
4. `FrontendBodyLoweringSupportTest`
5. `FrontendWritableRouteSupportTest`
6. `CPhaseAControlFlowAndFinallyTest`
7. `CConstructInsnGenTest`
8. `CallMethodInsnGenTest`
9. `CallGlobalInsnGenTest`
10. `FrontendLoweringBodyInsnPassTest`
11. `FrontendArrayConstructorVoidInvestigationTest`

推荐命令：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendCfgGraphTest,FrontendCfgGraphBuilderTest,FrontendLoweringBuildCfgPassTest,FrontendBodyLoweringSupportTest,FrontendWritableRouteSupportTest,CPhaseAControlFlowAndFinallyTest,CConstructInsnGenTest,CallMethodInsnGenTest,CallGlobalInsnGenTest,FrontendLoweringBodyInsnPassTest,FrontendArrayConstructorVoidInvestigationTest
```

若某个 phase 只改动一侧代码，则优先只跑该侧相邻测试，不必一次性跑完整串联。

## 非目标与延期项

- 不在本次修复中扩大到新的 static-call backend surface
- 不把 nullable `resultValueId` 扩散成“所有 `ValueBuild` / 所有 expression path 都可空”的全面重构
- 不放宽 value-required site 对 void call 的 typed diagnostic / fail-fast 约束
- 不在本次修复中跑全量测试或端到端全量 Godot suite

这些都可能成为后续清理项，但都不是当前问题闭环所必需的最小修复。

## 完成标准

本计划可以视为完成，当且仅当以下条件同时成立：

1. `__prepare__` 不再为 `GdVoidType` 变量注入默认初始化。
2. frontend 发出的 exact instance/global void call 不再携带 `resultId`。
3. backend 现有“void + resultId -> fail-fast”防线仍完整保留。
4. statement-position void call 不再发布 `CallItem.resultValueIdOrNull()`，也不再声明对应 CFG slot。
5. `Array.push_back(...)` 的最小复现与 helper 复现都转为稳定成功回归。
6. 错误记录文档、计划文档与测试断言全部对齐到“真实问题是 void-return call result slot drift”，而不是旧的 constructor 叙述。
