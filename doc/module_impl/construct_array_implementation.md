# construct_array 重构实施方案（Array + Packed*Array）

## 文档状态

- 状态：Completed（阶段 0-6 已完成）
- 更新时间：2026-03-03
- 范围：`construct_array` 在 C Backend 的语义、实现与验证
- 关联模块：Low IR / Type System / backend.c / module_impl

## 执行记录

- 2026-03-03 阶段 0 完成：
  - 基线 `HEAD`：`f2f4103aa2baa709701f776272e585432a1b89cc`
  - 基线测试：`./gradlew test --tests CConstructInsnGenTest --no-daemon --info --console=plain` 通过。
- 2026-03-03 阶段 1 完成：
  - 已更新 `gdcc_low_ir.md`、`gdcc_type_system.md`、`gdcc_c_backend.md` 的语义文档。
  - 已同步 module_impl 引用：`c_builtin_builder_refactor.md`、`call_method_implementation.md`。
- 2026-03-03 阶段 2 完成：
  - `MethodCallResolver` 已移除私有 `parseExtensionType`，改为复用 `CGenHelper.parseExtensionType(...)`。
  - 新增 helper 级解析测试并通过：`./gradlew test --tests CGenHelperTest --no-daemon --info --console=plain`。
  - 回归通过：`./gradlew test --tests CallMethodInsnGenTest --no-daemon --info --console=plain`。
- 2026-03-03 阶段 3 完成：
  - `ConstructInsnGen` 的 `construct_array` 已支持 `GdArrayType` + `GdPackedArrayType` 分流。
  - `GdPackedArrayType` 场景新增 `class_name` 非法校验并 fail-fast。
- 2026-03-03 阶段 4 完成：
  - `CCodegen` 两处自动初始化分支已为 `GdPackedArrayType` 注入 `new ConstructArrayInsn(varId, null)`。
  - 回归通过：`./gradlew test --tests CConstructInsnGenTest --no-daemon --info --console=plain`。
- 2026-03-03 阶段 5 完成：
  - 新增额外引擎集成测试：`constructPackedArrayInsnsShouldRunInRealGodot`（覆盖 `PackedInt32Array` 的 explicit/prepare 两条路径）。
  - 回归通过：`./gradlew test --tests CConstructInsnGenEngineTest --no-daemon --info --console=plain`。
- 2026-03-03 阶段 6 完成：
  - 回归通过：`./gradlew test --tests CConstructInsnGenTest --no-daemon --info --console=plain`。
  - 回归通过：`./gradlew test --tests CConstructInsnGenEngineTest --no-daemon --info --console=plain`。
  - 回归通过：`./gradlew test --tests CallMethodInsnGenTest --no-daemon --info --console=plain`。
  - 回归通过：`./gradlew test --tests CPhaseAControlFlowAndFinallyTest --no-daemon --info --console=plain`。
  - 计划命令 `CGenHelperUtilityResolutionTest` 无匹配测试类（`No tests found for given includes`），已以现有测试类 `CGenHelperTest` 完成等价回归：
    `./gradlew test --tests CGenHelperTest --no-daemon --info --console=plain`。
  - 回归通过：`./gradlew classes --no-daemon --info --console=plain`。

## 合并后的硬性约束（最终口径）

- `construct_array` 在构造 `Packed*Array` 时，仅根据结果变量类型构造。
- 当结果变量类型是 `Packed*Array` 时，`construct_array` 的 `class_name` 操作数不允许出现；出现即视为 IR 错误并 fail-fast。
- `ConstructBuiltinInsn` 保持现有行为，不做语义或实现改动。
- `MethodCallResolver#parseExtensionType` 下沉到 `CGenHelper`，由共享 helper 提供扩展类型解析能力。

## 现状与问题

- Low IR 文档把 `construct_array` 描述为“TypedArray/generic Array 构造”，但未明确 `Packed*Array` 路径与 `class_name` 约束。
- `ConstructInsnGen` 当前 `construct_array` 分支只接受 `GdArrayType` 结果变量，不接受 `GdPackedArrayType`。
- `CCodegen` 在 `__prepare__` 与默认字段初始化路径中，对 `Packed*Array` 变量走了 `construct_builtin`，未统一到 `construct_array`。
- `MethodCallResolver` 内部持有 `parseExtensionType` 私有实现，导致扩展类型解析规则分散，和 `CGenHelper` 的职责边界不一致。

## 目标与非目标

### 目标

- 让 `construct_array` 支持 `GdArrayType` 与 `GdPackedArrayType` 两类结果变量。
- 固化 `Packed*Array` 的无操作数语义：仅由结果类型决定构造目标。
- 对 `Packed*Array + class_name` 输入进行显式拒绝并给出可定位错误信息。
- 将扩展类型字符串解析规则收敛到 `CGenHelper`，供 `MethodCallResolver` 复用。
- 补齐文档、单测、集成测试和验收命令，确保行为可回归。

### 非目标

- 不修改 `GdInstruction.CONSTRUCT_ARRAY` 的 opcode/操作数数量定义。
- 不修改 `ConstructBuiltinInsn` 的行为与调用路径。
- 不改变 `construct_dictionary` 现有语义。
- 不改动 build 脚本或工程配置。

## 统一语义定义（实施后）

- `construct_array` 对 `GdArrayType`：
  - `class_name` 可选。
  - 缺省时表示 generic `Array[Variant]`。
  - 提供时必须与结果变量元素类型一致，否则 fail-fast。
- `construct_array` 对 `GdPackedArrayType`：
  - 不接受 `class_name`。
  - 构造目标完全由结果变量类型决定（如 `PackedInt32Array`、`PackedVector3Array`）。
  - 若传入 `class_name`（包含空白字符串），直接 fail-fast。

## 下游代码路由说明（显式记录）

- `ConstructInsnGen` 的 `construct_array` 分支中：
  - `GdArrayType` 与 `GdPackedArrayType` 都会在完成指令级校验后调用 `builtinBuilder.constructBuiltin(bodyBuilder, target, List.of())`。
- `CBuiltinBuilder.constructBuiltin(...)` 的实际分发为：
  - `GdArrayType` -> `constructArray(...)`（容器专用 typed 路径）
  - `GdDictionaryType` -> `constructDictionary(...)`（容器专用 typed 路径）
  - 其他类型（包含 `GdPackedArrayType`）-> `constructRegularBuiltin(...)`（通用 builtin 路径）
- 因此，`Packed*Array` 与 `Array/Dictionary` 存在“路由不对称”是当前设计中的显式事实，不是遗漏：
  - `Packed*Array` 的构造函数解析依赖 `ExtensionBuiltinClass` 元数据中的构造签名（当前为零参构造）。
  - 当元数据缺失或签名不匹配时，按既有策略 fail-fast，抛出 builtin constructor validation 错误。
- 维护约束：
  - 若后续为 `Packed*Array` 增加专用构造路径（类似 `constructArray`），必须同步更新本文档“语义定义/路由说明/风险与防线/验收清单”四处内容，并补充回归测试。

## 需要改动的文档（必须同步）

- `doc/gdcc_low_ir.md`
  - 更新 `construct_array` 章节：显式区分 `Array` 与 `Packed*Array` 语义。
  - 新增 `Packed*Array` 示例：`$x = construct_array`（无 `class_name`）。
  - 新增错误约束说明：`Packed*Array` 场景出现 `class_name` 非法。
- `doc/gdcc_type_system.md`
  - 补充 `GdArrayType` 与 `GdPackedArrayType` 的语义边界。
  - 明确 `typedarray::Packed*Array -> GdPacked*ArrayType`、`typedarray::T -> GdArrayType(T)` 的解析规则。
- `doc/gdcc_c_backend.md`
  - 更新 `__prepare__` 与字段默认初始化策略：`Packed*Array` 注入 `construct_array`，不走 `construct_builtin`。
  - 增加 `construct_array` 对 packed 的 fail-fast 约束（`class_name` 禁止）。
- `doc/module_impl/c_builtin_builder_refactor.md`
  - 同步说明：本次不变更 `ConstructBuiltinInsn`，仅调整 `construct_array` 路由与校验。
- `doc/module_impl/call_method_implementation.md`
  - 将“扩展类型规范化约束”中的实现归属更新为 `CGenHelper.parseExtensionType(...)`。
- `doc/module_impl/construct_array_implementation.md`
  - 保留本文作为实施与验收主文档。

## 需要改动的代码（必须同步）

- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/ConstructInsnGen.java`
  - `ConstructArrayInsn` 分支从“仅 `GdArrayType`”扩展为“`GdArrayType` 或 `GdPackedArrayType`”。
  - 对 `GdPackedArrayType` 增加 `class_name` 非法检查。
  - 对 `GdArrayType` 保持现有 typed hint 校验语义。
  - 错误信息需包含指令名与冲突原因，便于定位。
- `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
  - `generateDefaultGetterSetterInitialization()` 中新增 `GdPackedArrayType` 分支，注入 `new ConstructArrayInsn(varId, null)`。
  - `generateFunctionPrepareBlock()` 中新增 `GdPackedArrayType` 分支，注入 `new ConstructArrayInsn(varId, null)`。
  - `default -> new ConstructBuiltinInsn(... )` 保持不变，仅覆盖非容器 builtin 路径。
- `src/main/java/dev/superice/gdcc/backend/c/gen/CBuiltinBuilder.java`
  - 记录并确认当前路由：`GdPackedArrayType` 通过 `constructBuiltin(...)` 的 `default` 分支进入 `constructRegularBuiltin(...)`，不走 `constructArray(...)` 容器专用分支。
  - 本次不改变该路由行为，仅将其文档化并纳入验收约束。
- `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
  - 新增共享扩展类型解析方法（建议命名：`parseExtensionType`）。
  - 规则与现有 resolver 保持一致：
    - 空/空白 -> `GdVoidType.VOID`
    - `enum::` / `bitfield::` -> `GdIntType.INT`
    - `typedarray::Packed*Array` -> 对应 `GdPacked*ArrayType`
    - `typedarray::T` -> `new GdArrayType(T)`
    - 无法识别 -> 抛出明确异常
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/MethodCallResolver.java`
  - 删除私有 `parseExtensionType` 实现。
  - 改为调用 `bodyBuilder.helper().parseExtensionType(...)`（或等效共享 API）。
  - 保持现有诊断文本与失败策略（fail-fast）语义一致。

## 测试改动清单

- `src/test/java/dev/superice/gdcc/backend/c/gen/CConstructInsnGenTest.java`
  - 新增：`construct_array` 构造 `Packed*Array` 成功用例（无 `class_name`）。
  - 新增：`Packed*Array` 场景传入 `class_name` 报错用例。
  - 新增：`__prepare__` 注入 `Packed*Array` 时应生成 `ConstructArrayInsn(..., null)` 的断言。
  - 新增：`generateFuncBody` 输出 packed 构造调用的断言（如 `godot_new_PackedInt32Array()`）。
- `src/test/java/dev/superice/gdcc/backend/c/gen/CConstructInsnGenEngineTest.java`
  - 新增显式 packed 构造函数（手写 `ConstructArrayInsn(var, null)`）。
  - 新增 prepare packed 构造函数（依赖自动注入）。
  - 测试脚本新增 packed 类型断言（`typeof` / 容器属性检查）。
  - 新增独立引擎集成测试 `constructPackedArrayInsnsShouldRunInRealGodot`，专项验证 `PackedInt32Array` 的 explicit/prepare 路径。
- `src/test/java/dev/superice/gdcc/backend/c/gen/CallMethodInsnGenTest.java`
  - 回归验证 `typedarray::Packed*Array` 与 `typedarray::T` 解析语义未回退。
- `src/test/java/dev/superice/gdcc/backend/c/gen/CGenHelperTest.java`
  - 新增/迁移 `parseExtensionType` 的正反向测试，覆盖 malformed/unsupported 输入。

## 分阶段执行与验收计划

### 阶段 0：基线冻结

- 执行：记录当前 HEAD 与目标测试基线，确认工作区无脏改动。
- 验收：
  - `git status --short` 为空或仅存在本任务文档改动。
  - `CConstructInsnGenTest` 基线通过。

### 阶段 1：规范文档先行

- 执行：先更新 `gdcc_low_ir.md`、`gdcc_type_system.md`、`gdcc_c_backend.md` 的语义文本，再更新 module_impl 文档引用。
- 验收：
  - 文档明确写出 `Packed*Array` 禁止 `class_name`。
  - 文档明确 `ConstructBuiltinInsn` 非本次改动对象。
  - 文档明确 `parseExtensionType` 归属 `CGenHelper`。

### 阶段 2：共享类型解析抽取（MethodCallResolver -> CGenHelper）

- 执行：在 `CGenHelper` 增加解析 API，替换 `MethodCallResolver` 私有实现与调用点。
- 验收：
  - `MethodCallResolver` 不再定义 `parseExtensionType` 私有方法。
  - `CallMethodInsnGenTest` 通过，`typedarray::Packed*Array` 解析结果不变。
  - 新增 helper 级测试覆盖 malformed 与 unsupported 分支。

### 阶段 3：construct_array 支持 Packed*Array

- 执行：修改 `ConstructInsnGen`，按结果类型分流 `GdArrayType` 与 `GdPackedArrayType`。
- 验收：
  - `GdPackedArrayType + class_name == null` 成功生成 packed 构造调用。
  - `GdPackedArrayType + class_name != null` 抛 `InvalidInsnException`。
  - 原有 `GdArrayType` typed hint 校验行为不变。

### 阶段 4：自动注入路径切换到 construct_array

- 执行：修改 `CCodegen` 两处初始化 switch，将 `GdPackedArrayType` 显式分支改为 `ConstructArrayInsn(..., null)`。
- 验收：
  - `__prepare__` 中 packed 变量注入 `ConstructArrayInsn` 而非 `ConstructBuiltinInsn`。
  - 字段默认初始化函数对 packed 返回值同样注入 `ConstructArrayInsn(..., null)`。
  - 相关用例生成 C 文本包含 `godot_new_Packed*Array()`。

### 阶段 5：测试补齐与引擎回归

- 执行：补单测与引擎集成测试，覆盖 explicit/prepare 两条 packed 路径。
- 验收：
  - `CConstructInsnGenTest` 全绿，包含新增失败路径断言。
  - `CConstructInsnGenEngineTest` 在 Zig 可用时通过；Zig 不可用时按现有策略 skip。

### 阶段 6：整体验收与收口

- 执行：跑目标回归命令并整理结果，更新本文档状态与风险结论。
- 验收命令：

```bash
./gradlew test --tests CConstructInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CConstructInsnGenEngineTest --no-daemon --info --console=plain
./gradlew test --tests CallMethodInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CPhaseAControlFlowAndFinallyTest --no-daemon --info --console=plain
./gradlew test --tests CGenHelperTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

- 收口标准：
  - 文档、实现、测试三者口径一致。
  - `construct_array` 对 packed 的限制与行为可由测试稳定证明。
  - 无对 `ConstructBuiltinInsn` 的行为回归。

## 风险与防线

- 风险：已有 IR 可能向 packed `construct_array` 传入 `class_name`，改造后会 fail-fast。
  - 防线：明确错误文案，尽早暴露上游生成问题。
- 风险：`parseExtensionType` 抽取后出现行为漂移。
  - 防线：在 helper 级与 call-method 级同时加测试，覆盖 typedarray/enum/bitfield/非法输入。
- 风险：自动注入路径切换影响 `__prepare__` 既有顺序。
  - 防线：保持仅分支替换，不改变注入顺序与 `appendInsnIfAbsent` 语义。
- 风险：`Packed*Array` 依赖 `constructRegularBuiltin` 的元数据校验路径，若 API 元数据构造签名缺失/变更会导致构造失败。
  - 防线：保留 fail-fast 文案并通过引擎集成测试覆盖 explicit/prepare 路径；升级 Godot API 版本时优先执行该测试集。

## 最终验收清单（逐项打勾）

- [x] `construct_array` 已支持 `GdPackedArrayType`（仅依赖结果类型）。
- [x] `Packed*Array` 场景出现 `class_name` 会 fail-fast。
- [x] `ConstructBuiltinInsn` 无行为改动。
- [x] `MethodCallResolver#parseExtensionType` 已抽取到 `CGenHelper`。
- [x] `CCodegen` 两处自动初始化对 packed 改为注入 `ConstructArrayInsn(..., null)`。
- [x] 文档同步完成：`gdcc_low_ir`、`gdcc_type_system`、`gdcc_c_backend`、module_impl。
- [x] 已在实施文档显式记录 `Packed*Array` 的 `CBuiltinBuilder` 路由不对称性（`constructRegularBuiltin` 路径）与维护约束。
- [x] 目标测试命令全部通过（或按规则 skip）。
