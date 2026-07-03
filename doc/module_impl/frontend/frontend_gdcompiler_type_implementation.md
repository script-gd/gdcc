# Frontend GdCompilerType 实现说明

> 本文档作为 `GdCompilerType` 及其 frontend / LIR / backend compiler-only storage 合同的长期事实源，记录当前定位、边界、协议、intrinsic 约束与维护规则。本文档替代旧的计划文档与阶段流水账，不再保留实施顺序、已完成阶段列表或进度记录。

## 文档状态

- 状态：事实源维护中（`GdCompilerType` sealed 抽象层、`GdccForRangeIterType`、LIR-only grammar、frontend/backend leak guards、public ABI validator、range iterator intrinsic 已落地）
- 更新时间：2026-07-03
- 适用范围：
  - `src/main/java/gd/script/gdcc/type/**`
  - `src/main/java/gd/script/gdcc/frontend/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
  - `src/test/java/gd/script/gdcc/type/**`
  - `src/test/java/gd/script/gdcc/frontend/**`
  - `src/test/java/gd/script/gdcc/lir/**`
  - `src/test/java/gd/script/gdcc/backend/c/**`
- 关联文档：
  - `doc/analysis/gdcompiler_type_design_risk_analysis.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_lir_intrinsic.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_local_type_stabilization_implementation.md`
  - `frontend_type_check_analyzer_implementation.md`
  - `frontend_implicit_conversion_matrix.md`
- 明确非目标：
  - 不把 compiler-only type 暴露为 GDScript source-facing declared type
  - 不把 compiler-only type 纳入 ordinary implicit conversion matrix
  - 不让 compiler-only type 进入 public / hidden function parameter 或 return ABI
  - 不让 compiler-only type 进入 property、signal、capture、typed container outward metadata
  - 不让 compiler-only type 参与 `Variant` pack / unpack、engine method、utility、global、operator、index、property 普通 Godot runtime 路径
  - 不在这里实现 `for` parser / analyzer / lowering 或 async / function-state 全量 lowering

---

## 1. 当前定位与边界

### 1.1 当前定位

`GdCompilerType` 当前表示 GDCC compiler / lowering / LIR / backend 自己拥有的 runtime storage type。它的作用是：

- 承载内部 local / temp variable 的静态类型
- 作为 backend-owned intrinsic 的 operand / result type
- 为 C backend 提供稳定的 storage / init / copy / destroy 合同

它不是以下任何一种东西：

- 用户可声明的 GDScript 类型
- `Variant` 的特例或伪装载体
- `TYPE_META` / `ScopeTypeMeta` 路由的一部分
- Godot object / builtin metadata 的补充分支

当前首个 concrete type 固定为 `GdccForRangeIterType`，表示 `for i in range(...)` lowering 所需的 compiler-owned iterator state storage。它当前已经作为 compiler-only 类型体系的事实锚点存在，但这并不等于 frontend 已开放 `for` lowering 支持面。

### 1.2 禁止边界

`GdCompilerType` 当前明确禁止进入：

- source-facing declared type parser / resolver
- `ScopeTypeMeta` namespace 与普通类型元值路由
- ordinary `expressionTypes()` published fact
- ordinary local / parameter / property / return `slotTypes()`
- property / signal / callable outward ABI
- generated binding metadata 与 `call_func` wrapper surface
- ordinary typed boundary、`Variant` pack / unpack、dynamic call result unpack
- engine method / utility / global / operator / index / property 普通调用路径

当前工程语义固定为：compiler-only 类型只能在内部 storage typing 路径显式流动，任何越界都必须 fail-fast，而不是退化成默认 `Variant`、默认 `godot_*` helper 或 object guess。

---

## 2. 当前类型协议

### 2.1 抽象层合同

当前 `GdType` sealed hierarchy 通过 `GdCompilerType` 挂入 compiler-only 分支，而不是直接把某个 concrete type 挂在根层。`GdCompilerType` 当前稳定提供以下协议：

- `getLirTypeText()`：LIR-only type text
- `getCStorageTypeName()`：C storage type 名称
- `getCInitHelperName()`：prepare/init helper 名称
- `getCDestroyHelperName()`：destroy helper 名称
- `isPassedByPointerInC()`：C helper 传参形状
- `getCCopyHelperName()`：deep-copy helper 名称；当前空字符串仅表示“没有专用 copy helper”
- `isDirectStructAssignmentSafe()`：是否允许 direct struct assignment
- `validateCStorageContract()`：用于 consumer 侧 fail-fast 的一致性校验入口

共享默认合同已经冻结为：

- 无 GDExtension metadata
- 非 nullable
- destroyable non-object value
- 默认按地址传给 C helper

consumer 必须优先读取显式语义方法，而不是继续通过 `getTypeName()`、空 helper 名或某个 concrete type 的 `instanceof` 约定去猜测赋值/传参行为。

### 2.2 `GdccForRangeIterType` 当前事实

`GdccForRangeIterType` 当前是唯一 concrete `GdCompilerType`，其合同固定为：

- internal name：`GdccForRangeIter`
- LIR text：`compiler::GdccForRangeIter`
- C storage type：`gdcc_for_range_iter`
- init helper：`gdcc_for_range_iter_init`
- destroy helper：`gdcc_for_range_iter_destroy`
- 传参：按地址传入 C helper
- copy 语义：direct struct assignment

当前代码库没有第二个 compiler-only type，因此所有扩展规则都必须以 `GdCompilerType` 抽象层为准，而不是继续复制 `GdccForRangeIterType` 的散点特判。

---

## 3. Frontend、LIR 与 ABI 合同

### 3.1 Frontend 侧边界

frontend 当前已经冻结以下 compiler-only 边界：

- source-facing declared type resolver 不能解析 `compiler::GdccForRangeIter` 或 bare `GdccForRangeIter`
- ordinary typed-boundary compatibility 不接受 compiler-only source 或 target
- local `:=` stabilization、condition typing、expression type publication、writeback 与 lowering boundary materialization 都显式拒绝 compiler-only 泄漏

这意味着：

- compiler-only type 不会作为 ordinary published expression fact 进入下游用户语义消费
- compiler-only type 不会被 ordinary local/property/return 类型门禁当作普通 `GdType` 兼容结果静默放行
- lowering 不会把 compiler-only value 物化成 `PackVariantInsn`、`UnpackVariantInsn`、`ConstructBuiltinInsn` 或普通 frontend boundary cast 路径

### 3.2 LIR XML 合同

当前 LIR-only grammar 已冻结为：

- 文本形态：`compiler::<Name>`
- 当前唯一合法实例：`compiler::GdccForRangeIter`
- 当前只允许出现在 function `<variables>`

以下 surface 明确禁止 compiler-only type：

- function `<parameters>`
- function `<return_type>`
- `<properties>`
- `<signals>` parameter
- lambda `<captures>`
- hidden function parameter / return

`DomLirParser` / `DomLirSerializer` 当前都按这条合同工作：local variable surface 可 round-trip；其余 ABI-like surface 必须 fail-fast，且不得退化成普通 object type 解析。

### 3.3 Public ABI validator

`LirPublicAbiValidator` 当前是 compiler-only public ABI 边界的统一最终防线。它在 backend codegen 前工作，负责拒绝：

- property type
- signal parameter type
- function parameter type
- function return type
- lambda capture type

当前 hidden function 不享有 compiler-only ABI 豁免。若未来要开放 backend-owned hidden helper ABI，必须先同步更新本文档、`doc/gdcc_low_ir.md` 与对应 backend contract，再改实现。

---

## 4. Backend 与 Intrinsic 合同

### 4.1 C backend 当前合同

backend 当前对 compiler-only 类型的稳定合同是：

- C type 渲染使用 `getCStorageTypeName()`，不走默认 `godot_<Type>` 路径
- prepare block 的 compiler-only local 初始化走专用 intrinsic / helper 路径，不走 `ConstructBuiltinInsn`
- 赋值与返回遵守 `GdCompilerType` 显式 copy / direct-assignment 合同
- destroy 走 `gdcc_*_destroy` helper，不落入默认 no-op
- public metadata、typed container leaf、wrapper unpack/destroy、普通 Godot runtime 路径都显式拒绝 compiler-only type

当前 `GdccForRangeIterType` 的成功路径固定使用：

- `gdcc_for_range_iter` storage
- `gdcc_for_range_iter_init` prepare helper
- `gdcc_for_range_iter_destroy` cleanup helper
- direct struct assignment

因此当前生成结果中不应出现：

- `godot_GdccForRangeIter`
- `godot_new_GdccForRangeIter...`
- `godot_new_Variant_with_GdccForRangeIter`
- `godot_new_GdccForRangeIter_with_Variant`
- `godot_GdccForRangeIter_destroy`

### 4.2 Range iterator intrinsic 当前合同

当前 backend-owned range iterator intrinsic 已冻结为四个：

- `gdcc.for_range_iter.init`
- `gdcc.for_range_iter.should_continue`
- `gdcc.for_range_iter.next`
- `gdcc.for_range_iter.get`

它们的当前类型合同是：

- `init(start: int, end: int, step: int) -> compiler::GdccForRangeIter`
- `should_continue(iter: compiler::GdccForRangeIter) -> bool`
- `next(iter: compiler::GdccForRangeIter) -> compiler::GdccForRangeIter`
- `get(iter: compiler::GdccForRangeIter) -> int`

当前 runtime / lifecycle 合同同步冻结为：

- helper 只操作 compiler-owned iterator state，不引入 Godot object ownership
- `init` 使用 `gdcc_for_range_iter_from_bounds(...)` 建立按值 iterator state
- `step == 0` 通过明确 runtime error helper 路径处理，不允许静默形成无限循环语义
- `should_continue` / `next` 对正负步长的边界处理以 runtime helper 语义为准

prepare block 额外保留 `gdcc_for_range_iter_init()` 作为 local 默认初始化 helper，它服务的是 slot 生命周期初始化，而不是 `range(...)` 语义层面的 bounds 归一化。

---

## 5. 工程约束与维护规则

### 5.1 单一真源约束

以下事实源分工继续保持冻结：

- `frontend_implicit_conversion_matrix.md`：ordinary typed-boundary conversion 的唯一真源
- `doc/gdcc_low_ir.md`：LIR XML surface 与 `compiler::<Name>` grammar 的唯一真源
- `doc/gdcc_lir_intrinsic.md`：intrinsic catalog 与 textual shape 的唯一真源
- `doc/gdcc_c_backend.md` / `doc/gdcc_runtime_lib.md`：C helper / runtime helper 命名与行为边界的唯一真源

本文档不维护第二份 ordinary conversion matrix，也不重复 intrinsic catalog 的逐条实现细节；这里只记录 compiler-only 类型这条 feature 的边界与接线合同。

### 5.2 扩展规则

未来若新增第二个及以上 `GdCompilerType`，必须同时满足：

- 先在本文档补充其定位与边界，再改代码
- 明确声明 LIR text、C storage、init/copy/destroy 合同
- 明确它是否允许 direct struct assignment、是否按地址传参
- 明确它是否需要新的 intrinsic catalog 与 runtime helper
- 同步补齐 frontend leak guard、LIR parser/serializer、public ABI validator、backend helper 与 targeted tests

任何扩展都不得通过下列方式偷渡：

- 复用 `Variant` 路径伪装 compiler-only carrier
- 复用默认 `godot_*` helper 命名
- 让 parser / resolver / metadata generator 通过普通 `GdType` 分支静默接受新类型
- 把 hidden function 当作绕过 ABI validator 的后门

### 5.3 错误信息与恢复约定

compiler-only 泄漏的错误信息应继续保持明确语义，例如 `compiler-only type leaked into ...`。目标是让故障停在最早的合同边界，而不是把问题下沉成：

- `getGdExtensionType() == null`
- 缺失 `godot_*` symbol
- object / builtin / `Variant` 默认路径的晚期异常

---

## 6. 稳定测试锚点

当前与 compiler-only type 合同直接相关的稳定测试锚点包括：

- 类型协议：`GdCompilerTypeTest`、`GdccForRangeIterTypeTest`
- frontend 边界：`FrontendVariantBoundaryCompatibilityTest`、`FrontendLocalTypeStabilizationAnalyzerTest`、`FrontendTypeCheckAnalyzerTest`、`FrontendBodyLoweringSessionTest`、`FrontendLoweringBodyInsnPassTest`、`FrontendWritableTypeWritebackSupportTest`
- LIR parser / serializer / validator：`DomLirParserTest`、`DomLirSerializerTest`、`LirPublicAbiValidatorTest`、`SimpleLirBlockInsnParserTest`、`SimpleLirBlockInsnSerializerTest`
- backend / intrinsic：`CGenHelperTest`、`CBodyBuilderPhaseBTest`、`CBodyBuilderPhaseCTest`、`CDestructInsnGenTest`、`CBodyBuilderAliasSafetySupportTest`、`CCodegenTest`、`CIntrinsicManagerTest`、`CallIntrinsicInsnGenTest`、`GdccForRangeIterIntrinsicTest`

后续工程若改动 compiler-only 行为，应优先用这些 targeted tests 锚定回归，而不是只依赖全量 build 或手工检查生成 C 代码。

---

## 7. 当前结论

当前代码库已经把 `GdCompilerType` 固定为“GDCC 内部 runtime storage typing”这一角色，而不是 source-facing type system 的一部分。后续工程若继续扩展 compiler-only 类型，必须保持这三个总原则不变：

- 先封边界，再开成功路径
- 先补事实源，再扩实现
- 所有跨层 consumer 都必须面向 `GdCompilerType` 显式合同，而不是回退到默认 `GdType` / `Variant` / `godot_*` 路径
