# construct_array 实现说明（Array + Packed*Array）

> 本文档作为 `construct_array` 在 C Backend 的长期维护说明。
> 只保留已完成实现、当前状态和后续工程仍有价值的约定与反思。

## 文档状态

- 状态：Implemented / Maintained
- 范围：`construct_array` 在 C Backend 的语义、路由与校验
- 更新时间：2026-03-03
- 关联基线：
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/builtin_builder_implementation.md`
  - `doc/module_impl/call_method_implementation.md`

## 当前最终状态（与代码对齐）

### 覆盖范围

- 指令生成入口：`src/main/java/gd/script/gdcc/backend/c/gen/insn/ConstructInsnGen.java`
- Builtin 构造分发：`src/main/java/gd/script/gdcc/backend/c/gen/CBuiltinBuilder.java`
- 自动注入路径：`src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`
- 共享类型解析：`src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
- LIR 指令定义：`src/main/java/gd/script/gdcc/lir/insn/ConstructArrayInsn.java`

### 已实现语义

- `construct_array` 对 `GdArrayType`：
  - `class_name` 可选。
  - 缺省时表示 generic `Array[Variant]`。
  - 提供时必须与结果变量元素类型一致，否则 fail-fast。
- `construct_array` 对 `GdPackedArrayType`：
  - 不接受 `class_name`。
  - 构造目标完全由结果变量类型决定（如 `PackedInt32Array`、`PackedVector3Array`）。
  - 若传入 `class_name`（包含空白字符串），直接 fail-fast。
  - 生成物为 Variant-backed packed 值：经 `gdcc_packed_<slug>_new_empty()` 产出空数组 `godot_Variant`
    （引用语义存储，严禁 nil Variant）；默认值表达式路径（`__prepare__`、默认参数、协程默认值）同样走
    `new_empty`。

### 已实现自动注入路径

- `CCodegen.generateFunctionPrepareBlock()`：`GdPackedArrayType` 变量注入 `new ConstructArrayInsn(varId, null)`。
- `CCodegen.generateDefaultGetterSetterInitialization()`：`GdPackedArrayType` 变量注入 `new ConstructArrayInsn(varId, null)`。
- `default -> new ConstructBuiltinInsn(...)` 保持不变，仅覆盖非容器 builtin 路径。

### 已实现共享类型解析

- `MethodCallResolver#parseExtensionType` 已下沉到 `CGenHelper.parseExtensionType(...)`。
- `ConstructInsnGen` 的 `construct_array` / `construct_dictionary` hint 解析现在走 `ClassRegistry.findType(...)`。
- 这意味着 backend container hint 会复用 registry 的 shared strict core，同时保留 compatibility fallback：
  - 已知 builtin / engine / gdcc / strict container 文本继续按 strict 规则解析
  - unknown object leaf 仍可保留 `Array[FutureItem]` / `Dictionary[String, FutureItem]` 这类 external/manual LIR hint
  - singleton / global enum / utility function 这类非 type 名称不会再被当作容器 hint 类型
- 解析规则：
  - 空/空白 -> `GdVoidType.VOID`
  - `enum::` / `bitfield::` -> `GdIntType.INT`
  - `typedarray::Packed*Array` -> 对应 `GdPacked*ArrayType`
  - `typedarray::T` -> `new GdArrayType(T)`
  - 无法识别 -> 抛出明确异常

## 长期约定（必须保持）

### 1. Packed*Array 构造约束

- `construct_array` 在构造 `Packed*Array` 时，仅根据结果变量类型构造。
- 当结果变量类型是 `Packed*Array` 时，`class_name` 操作数不允许出现；出现即视为 IR 错误并 fail-fast。
- `ConstructArrayInsn` 与带实参的 `ConstructBuiltinInsn` 对 packed 共用 `constructPackedArray` 专用路径
  （`ConstructInsnGen` 统一经 `CBuiltinBuilder.constructBuiltin(...)` 进入），发射形状以
  `gdcc_packed_ref.h` 白名单 helper 为准；不再存在 packed 的 regular-builtin 构造路径。

### 2. 下游代码路由

- `ConstructInsnGen` 的 `construct_array` 分支中：
  - `GdArrayType` 与 `GdPackedArrayType` 都会在完成指令级校验后调用 `builtinBuilder.constructBuiltin(bodyBuilder, target, List.of())`。
- `CBuiltinBuilder.constructBuiltin(...)` 的实际分发为：
  - `GdArrayType` -> `constructArray(...)`（容器专用 typed 路径）
  - `GdDictionaryType` -> `constructDictionary(...)`（容器专用 typed 路径）
  - `GdPackedArrayType` -> `constructPackedArray(...)`（packed 专用路径，白名单 helper）
  - 其他类型 -> `constructRegularBuiltin(...)`（通用 builtin 路径）
- `constructPackedArray(...)` 按实参选择 `gdcc_packed_ref.h` 白名单 helper（结果落入 Variant 槽）：
  - 零参 -> `gdcc_packed_<slug>_new_empty()`（empty-Variant，白名单 (b)）
  - 同型实参（如 `PackedInt32Array(other)`）-> `gdcc_packed_<slug>_new_copy(...)`（独立新数组，白名单 (d)，与 `duplicate()` 等价）
  - `Array` 实参（如 `PackedInt32Array([1, 2])`）-> `gdcc_packed_<slug>_new_from_array(...)`（跨类型转换，白名单 (d)）
  - 其他实参组合经 `hasConstructor` 元数据校验 fail-closed（Godot 4.5 packed 构造器恰好是上述三种）。
- 带实参的 packed 构造也可经 `ConstructBuiltinInsn` 进入同一 `constructPackedArray` 分支；两种指令入口共享同一 helper 选择逻辑。

### 3. 维护约束

- `Packed*Array` 专用构造路径（`constructPackedArray` + `gdcc_packed_ref.h` 白名单 helper）已落地；若后续新增
  构造形态（新实参类型组合），必须同步更新本文档"语义定义 / 路由说明 / 风险与防线 / 回归测试基线"四处内容，
  并补充回归测试。白名单 helper 集合的单一事实源是 `gdcc_packed_ref.h` 与 `PackedRefCNames`。
- `MethodCallResolver` 必须复用 `CGenHelper.parseExtensionType(...)`，不再维护私有解析实现。

## 风险与防线

- 风险：已有 IR 可能向 packed `construct_array` 传入 `class_name`，改造后会 fail-fast。
  - 防线：明确错误文案，尽早暴露上游生成问题。
- 风险：container hint 若直接切到 strict declared-type 解析，会误伤 external/manual LIR 中依赖 unknown object leaf 的兼容输入。
  - 防线：`ConstructInsnGen` 暂时继续走 `ClassRegistry.findType(...)`，只复用 strict core，不切断 compatibility fallback。
- 风险：`parseExtensionType` 抽取后出现行为漂移。
  - 防线：在 helper 级与 call-method 级同时加测试，覆盖 typedarray/enum/bitfield/非法输入。
- 风险：自动注入路径切换影响 `__prepare__` 既有顺序。
  - 防线：保持仅分支替换，不改变注入顺序与 `appendInsnIfAbsent` 语义。
- 风险：`Packed*Array` 专用构造路径若绕过 `hasConstructor` 元数据校验，可能接受 Godot 不支持的构造组合。
  - 防线：helper 选择前先经 `hasConstructor` fail-closed 校验；生成代码禁令扫描（`CCodegenTest`）禁止白名单外的 `godot_new_Packed*` 符号；升级 Godot API 版本时优先执行引擎集成测试集。

## 回归测试基线

- `src/test/java/gd/script/gdcc/backend/c/gen/CConstructInsnGenTest.java`
  - `construct_array` 构造 `Packed*Array` 成功用例（无 `class_name`）
  - `Packed*Array` 场景传入 `class_name` 报错用例
  - unknown object leaf container hint 兼容用例
  - singleton / global enum / utility function hint 拒绝用例
  - `__prepare__` 注入 `Packed*Array` 时生成 `ConstructArrayInsn(..., null)` 的断言
  - `generateFuncBody` 输出白名单 helper 的断言（零参 -> `gdcc_packed_int32_array_new_empty()`；同型实参 ->
    `new_copy`；`Array` 实参 -> `new_from_array`），并负向锚定不出现裸 `godot_new_Packed*()` 调用
- `src/test/java/gd/script/gdcc/backend/c/gen/CConstructInsnGenEngineTest.java`
  - 显式 packed 构造函数与 prepare packed 构造函数的引擎集成测试
  - 覆盖 `PackedInt32Array` 等类型的 explicit/prepare 路径
- `src/test/java/gd/script/gdcc/backend/c/gen/CallMethodInsnGenTest.java`
  - `typedarray::Packed*Array` 与 `typedarray::T` 解析语义回归
- `src/test/java/gd/script/gdcc/backend/c/gen/CGenHelperTest.java`
  - `parseExtensionType` 正反向测试，覆盖 malformed/unsupported 输入

建议命令：

```bash
./gradlew test --tests CConstructInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CConstructInsnGenEngineTest --no-daemon --info --console=plain
./gradlew test --tests CallMethodInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CGenHelperTest --no-daemon --info --console=plain
./gradlew test --tests CPhaseAControlFlowAndFinallyTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

## 工程反思（保留长期价值）

1. `Packed*Array` 与 `Array` 虽然在 GDScript 层面都是"数组"，但存储语义不同：`Array` 是 typed container 路径，
   `Packed*Array` 走专用 `constructPackedArray` 白名单 helper 路径并落入 Variant-backed 引用语义存储；
   路由差异是合理设计选择，而非遗漏，但必须显式文档化。
2. 扩展类型文本解析（`parseExtensionType`）分散在各 resolver 中会导致规则漂移；抽取到共享 helper 后，由单一实现承载是可维护路径。
3. 自动注入路径（`__prepare__` / default init）中类型分支的新增必须与指令生成器的语义保持对齐，否则会出现"注入 A 指令但生成器只认 B"的断层。
4. 文档应只保留当前事实与长期约束；阶段推进记录应留在提交历史，不应长期污染实现文档。

## 非目标（当前不做）

- 不修改 `GdInstruction.CONSTRUCT_ARRAY` 的 opcode/操作数数量定义。
- 不为 packed 增加白名单之外的构造形态（新实参组合须先扩展 `gdcc_packed_ref.h` 与元数据校验）。
- 不改变 `construct_dictionary` 现有语义。
