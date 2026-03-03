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
  - `doc/module_impl/c_builtin_builder_refactor.md`
  - `doc/module_impl/call_method_implementation.md`

## 当前最终状态（与代码对齐）

### 覆盖范围

- 指令生成入口：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/ConstructInsnGen.java`
- Builtin 构造分发：`src/main/java/dev/superice/gdcc/backend/c/gen/CBuiltinBuilder.java`
- 自动注入路径：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- 共享类型解析：`src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
- LIR 指令定义：`src/main/java/dev/superice/gdcc/lir/insn/ConstructArrayInsn.java`

### 已实现语义

- `construct_array` 对 `GdArrayType`：
  - `class_name` 可选。
  - 缺省时表示 generic `Array[Variant]`。
  - 提供时必须与结果变量元素类型一致，否则 fail-fast。
- `construct_array` 对 `GdPackedArrayType`：
  - 不接受 `class_name`。
  - 构造目标完全由结果变量类型决定（如 `PackedInt32Array`、`PackedVector3Array`）。
  - 若传入 `class_name`（包含空白字符串），直接 fail-fast。

### 已实现自动注入路径

- `CCodegen.generateFunctionPrepareBlock()`：`GdPackedArrayType` 变量注入 `new ConstructArrayInsn(varId, null)`。
- `CCodegen.generateDefaultGetterSetterInitialization()`：`GdPackedArrayType` 变量注入 `new ConstructArrayInsn(varId, null)`。
- `default -> new ConstructBuiltinInsn(...)` 保持不变，仅覆盖非容器 builtin 路径。

### 已实现共享类型解析

- `MethodCallResolver#parseExtensionType` 已下沉到 `CGenHelper.parseExtensionType(...)`。
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
- `ConstructBuiltinInsn` 保持现有行为，不做语义或实现改动。

### 2. 下游代码路由

- `ConstructInsnGen` 的 `construct_array` 分支中：
  - `GdArrayType` 与 `GdPackedArrayType` 都会在完成指令级校验后调用 `builtinBuilder.constructBuiltin(bodyBuilder, target, List.of())`。
- `CBuiltinBuilder.constructBuiltin(...)` 的实际分发为：
  - `GdArrayType` -> `constructArray(...)`（容器专用 typed 路径）
  - `GdDictionaryType` -> `constructDictionary(...)`（容器专用 typed 路径）
  - 其他类型（包含 `GdPackedArrayType`）-> `constructRegularBuiltin(...)`（通用 builtin 路径）
- `Packed*Array` 与 `Array/Dictionary` 存在"路由不对称"是当前设计中的显式事实，不是遗漏：
  - `Packed*Array` 的构造函数解析依赖 `ExtensionBuiltinClass` 元数据中的构造签名（当前为零参构造）。
  - 当元数据缺失或签名不匹配时，按既有策略 fail-fast，抛出 builtin constructor validation 错误。

### 3. 维护约束

- 若后续为 `Packed*Array` 增加专用构造路径（类似 `constructArray`），必须同步更新本文档"语义定义 / 路由说明 / 风险与防线 / 回归测试基线"四处内容，并补充回归测试。
- `MethodCallResolver` 必须复用 `CGenHelper.parseExtensionType(...)`，不再维护私有解析实现。

## 风险与防线

- 风险：已有 IR 可能向 packed `construct_array` 传入 `class_name`，改造后会 fail-fast。
  - 防线：明确错误文案，尽早暴露上游生成问题。
- 风险：`parseExtensionType` 抽取后出现行为漂移。
  - 防线：在 helper 级与 call-method 级同时加测试，覆盖 typedarray/enum/bitfield/非法输入。
- 风险：自动注入路径切换影响 `__prepare__` 既有顺序。
  - 防线：保持仅分支替换，不改变注入顺序与 `appendInsnIfAbsent` 语义。
- 风险：`Packed*Array` 依赖 `constructRegularBuiltin` 的元数据校验路径，若 API 元数据构造签名缺失/变更会导致构造失败。
  - 防线：保留 fail-fast 文案并通过引擎集成测试覆盖 explicit/prepare 路径；升级 Godot API 版本时优先执行该测试集。

## 回归测试基线

- `src/test/java/dev/superice/gdcc/backend/c/gen/CConstructInsnGenTest.java`
  - `construct_array` 构造 `Packed*Array` 成功用例（无 `class_name`）
  - `Packed*Array` 场景传入 `class_name` 报错用例
  - `__prepare__` 注入 `Packed*Array` 时生成 `ConstructArrayInsn(..., null)` 的断言
  - `generateFuncBody` 输出 packed 构造调用的断言（如 `godot_new_PackedInt32Array()`）
- `src/test/java/dev/superice/gdcc/backend/c/gen/CConstructInsnGenEngineTest.java`
  - 显式 packed 构造函数与 prepare packed 构造函数的引擎集成测试
  - 覆盖 `PackedInt32Array` 等类型的 explicit/prepare 路径
- `src/test/java/dev/superice/gdcc/backend/c/gen/CallMethodInsnGenTest.java`
  - `typedarray::Packed*Array` 与 `typedarray::T` 解析语义回归
- `src/test/java/dev/superice/gdcc/backend/c/gen/CGenHelperTest.java`
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

1. `Packed*Array` 与 `Array` 虽然在 GDScript 层面都是"数组"，但在 GDExtension 层面构造策略完全不同（typed container vs regular builtin）；路由不对称是合理设计选择，而非遗漏，但必须显式文档化。
2. 扩展类型文本解析（`parseExtensionType`）分散在各 resolver 中会导致规则漂移；抽取到共享 helper 后，由单一实现承载是可维护路径。
3. 自动注入路径（`__prepare__` / default init）中类型分支的新增必须与指令生成器的语义保持对齐，否则会出现"注入 A 指令但生成器只认 B"的断层。
4. 文档应只保留当前事实与长期约束；阶段推进记录应留在提交历史，不应长期污染实现文档。

## 非目标（当前不做）

- 不修改 `GdInstruction.CONSTRUCT_ARRAY` 的 opcode/操作数数量定义。
- 不修改 `ConstructBuiltinInsn` 的行为与调用路径。
- 不改变 `construct_dictionary` 现有语义。
