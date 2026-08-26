# Frontend Void-Return Call Behavior

> 本文档作为 frontend `void-return` ordinary call 在 CFG build、body lowering 与相邻 backend guard rail 上的长期事实源。  
> 它吸收并取代旧计划文档的已完成内容，只保留当前实现、稳定合同、测试锚点与后续工程仍需遵守的边界。

## 文档状态

- 状态：Implemented / Maintained
- 更新时间：2026-04-13
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/cfg/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/test/java/gd/script/gdcc/frontend/lowering/**`
  - `src/test/java/gd/script/gdcc/backend/c/build/**`
- 关联事实源：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md`
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/test_error/frontend_array_constructor_void_lowering.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`

若以下任一合同发生变化，至少要同步更新：

- 本文档
- `frontend_rules.md`
- `frontend_lowering_cfg_pass_implementation.md`
- `call_method_implementation.md`
- `frontend_array_constructor_void_lowering.md`
- 与 discarded-void call / result slot / boundary materialization 直接相关的源码注释

## 当前最终状态

- 当前问题的准确表述不是“`Array()` constructor 被 lower 成 `void`”，而是 `void-return` ordinary call 的 result-slot 合同曾在 frontend CFG、body lowering 与 backend 之间漂移。
- 当前唯一允许“不发布 result value / 不声明独立 `cfg_tmp_*`”的 call surface 是：
  - statement-position
  - ordinary call
  - 已稳定解析为 `RESOLVED(void)`
- 其它 value-required call path 仍必须发布 result value；若 `RESOLVED(void)` call 漏进 value-required path，CFG builder 与 body-lowering boundary/materialization 都必须 fail-fast。
- body lowering 当前会把 exact resolved-void call 统一发成 `resultId = null`：
  - `CallMethodInsn(null, ...)`
  - `CallGlobalInsn(null, ...)`
  - `CallStaticMethodInsn(null, ...)`
- backend 当前对以下 surface 提供正式 codegen 与 guard rail：
  - `CallMethodInsn`
  - `CallGlobalInsn`
  - `CallStaticMethodInsn`（`CallStaticMethodInsnGen`）
- backend `__prepare__` 当前会跳过 `GdVoidType` 变量；这条行为只服务于避免误导性 `construct_builtin(void)` 偏航，不代表 backend 接受“void result slot 仍存在”的坏 IR。
- backend 对坏 IR 的 guard rail 继续保留：
  - void method/global call 若仍携带 `resultId`，必须 fail-fast

## 1. 问题重述

历史最小复现是：

```gdscript
func compute() -> int:
    var plain: Array = Array()
    plain.push_back(1)
    return plain.size()
```

这里真正出错的不是 `Array()` 构造本身，而是：

1. metadata / resolver 把 `Array.push_back(...)` 识别为 `void`
2. 旧实现仍为该 call 发布 result value / temp slot
3. body lowering 与 backend 又把这个“像普通值一样存在的 void result”继续向下消费

因此当前长期结论是：

- `Array()` constructor 本身不是根因
- indexed store/load 不是这条问题的主描述
- `push_back + helper` 只是同一根因的伴随路径

## 2. 语义与类型事实

### 2.1 `void` 真源

- `extension_api_451.json` 中 `Array.push_back` 缺失 `return_type`
- `ScopeTypeParsers.parseExtensionTypeMetadata(...)` 对空白返回类型的共享合同是：
  - 缺失 / 空白 metadata -> `GdVoidType.VOID`
- 这与 Godot API 文档一致：
  - `Array.push_back(value: Variant) -> void`
- `PackedByteArray.push_back` 等其它 API 仍可能拥有非 `void` 返回值；不得把“名字相同”的方法一概归入 discarded-void 规则

### 2.2 frontend 不负责“自动兼容” value-required void call

以下形状仍属于 compile surface 之外的坏路径：

- `var x = print(1)`
- `return values.push_back(1)`
- `take_i(print(1))`

当前合同不是“让 lowering 宽容处理这些 path”，而是：

- 上游 type-check / compile gate 应先挡住它们
- 若它们仍漏进 lowering，lowering 必须立即 fail-fast
- 不得继续发布 `cfg_tmp_* : void`
- 不得出现未初始化读或“看起来还能跑”的漂移行为

## 3. CFG Build 合同

### 3.1 `CallItem` 的 no-result 例外面

当前 `CallItem` 允许 `resultValueIdOrNull() == null`，但这条 surface 必须严格收口为：

- statement-position ordinary call
- `resolvedCalls()` 已稳定发布为 `RESOLVED(void)`

除此以外：

- bare call value
- attribute call value
- type-meta head call value
- 任何嵌套在更大表达式中的 ordinary call

都必须继续发布 result value id。

### 3.2 CFG builder 的 fail-fast 职责

`FrontendCfgGraphBuilder` 当前必须同时守住两条边界：

- discarded resolved-void call 走 dedicated no-result build path
- value-required call path 禁止 quietly 复用这条例外

也就是说，CFG builder 不是在“探测是否能继续兼容”，而是在验证 published semantic fact 是否仍满足 lowering-ready 合同。

## 4. Body Lowering 与 Boundary Materialization 合同

### 4.1 value materialization

`FrontendBodyLoweringSupport.collectCfgValueMaterializations(...)` 当前的稳定行为是：

- 只为真正发布了 result value id 的 `ValueOpItem` 建立 materialization
- `CallItem.resultValueIdOrNull() == null` 时自然跳过
- 因此 statement-position discarded void call 不再拥有独立 `cfg_tmp_*`

### 4.2 exact call emission

`FrontendSequenceItemInsnLoweringProcessors` 当前对 exact resolved-void call 的长期合同是：

- call instruction 继续照常发射
- 但 emitted `resultId` 必须为 `null`
- non-void exact route 若缺少 published result id，仍属于 invariant violation

### 4.3 ordinary boundary helper

`FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 当前必须拒绝两类坏状态：

- source type 为 `void`
- target type 为 `void`

这条 guard rail 的职责是：

- 防止 value-required void call 继续漂移成 concrete slot materialization
- 防止 frontend 内部 boundary helper 被误用成“为 void 构造普通值”

## 5. Backend 交界面

### 5.1 `__prepare__`

`CCodegen.generateFunctionPrepareBlock()` 当前会跳过 `GdVoidType` 变量。  
这条行为的意义只有一个：

- 避免旧的 `construct_builtin(void)` 抢先掩盖真实问题

它不是 backend 对“void result slot 仍存在”形态的兼容承诺。

### 5.2 call generator guard rail

当前 backend 必须继续保持：

- `CallMethodInsnGen`：void method 禁止提供 `resultId`
- `CallGlobalInsnGen`：void global/utility call 禁止提供 `resultId`

frontend 的修复不应放松这些防线；它们的职责是拦截未来坏 LIR，而不是消费当前正常 path。

### 5.3 static type-meta head 边界

当前 frontend / LIR / backend 侧已经遵守：

- static resolved-void call 仍 emitted 为 `resultId = null`
- `CALL_STATIC_METHOD` 已由 `CallStaticMethodInsnGen` 正式生成（第十步）：static type-meta head
  `Node.print_orphan_nodes()` 从 negative build baseline 翻转为正向构建锚点
  （`FrontendVoidReturnCallIntegrationTest.staticVoidReturnTypeMetaHeadBuildsThroughEngineStaticHelper`）
- constructor boundary `Node.new()` 是当前已支持、已回归覆盖的正向 surface

## 6. 回归测试分层

### 6.1 frontend lowering / guard rail

以下测试负责锚定 frontend 内部合同：

- `FrontendCfgGraphBuilderTest`
- `FrontendBodyLoweringSupportTest`
- `FrontendBodyLoweringSessionTest`
- `FrontendLoweringBodyInsnPassTest`

这些测试应继续覆盖：

- statement-position resolved-void call 不发布 result value / slot
- value-required void call 漏进 lowering 时 fail-fast
- exact/global resolved-void call emitted `resultId == null`

### 6.2 former Array-only fake-build 回归

`FrontendArrayVoidReturnCallRegressionTest` 继续保留为窄回归锚点，用来锁定：

- pure indexed non-regression
- `push_back + size()`
- `push_back + dynamic helper`
- fake build 产物中不再出现旧的 `construct_builtin(void)` 偏航

### 6.3 broader end-to-end 合同

`FrontendVoidReturnCallIntegrationTest` 负责更宽的端到端覆盖：

- discarded global `print(...)`
- non-bare attribute void call
- property-backed `Array.push_back(...)` writable-route writeback
- `Node.new()` constructor boundary
- static type-meta head `Node.print_orphan_nodes()` 正向构建锚点（`CALL_STATIC_METHOD` 由 `CallStaticMethodInsnGen` 生成，第十步翻转）

这组测试的职责是防止回归再次只锁在 `Array.push_back` 单一路径上。

## 7. 后续工程必须保持的约定

- 不要重新把 discarded resolved-void call 当成普通 temp-backed value surface
- 不要为了“兼容更多坏 path”放宽 value-required void call 的 fail-fast
- 不要把 `CALL_STATIC_METHOD` 已支持面写回 backend gap（第十步已落地 `CallStaticMethodInsnGen`）
- 若 future compile surface 放行新的 ordinary call route，必须先明确：
  - 它是否属于 statement-position resolved-void no-result 例外
  - 它在 CFG、body lowering 与 backend 上分别如何体现
  - 对应测试锚点落在哪一层
