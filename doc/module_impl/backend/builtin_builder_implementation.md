# CBuiltinBuilder 实现说明

> 本文整合并取代此前关于 C builtin builder 重构与 builtin constructor 命名审计的历史记录。
>
> 只保留当前代码已经落地的实现事实、长期合同、测试锚点与风险边界；
> 不记录重构过程、阶段进度、历史审阅编号或已完成问题清单。

## 文档状态

- 状态：Implemented / Maintained
- 更新时间：2026-05-27
- 适用范围：
  - `src/main/java/gd/script/gdcc/backend/c/gen/CBuiltinBuilder.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/insn/ConstructInsnGen.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/binding/GodotBuiltinGenerator.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/binding/GodotUtilityGenerator.java`
- 关联文档：
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/backend/construct_array_implementation.md`
  - `doc/module_impl/backend/typed_array_abi_contract.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`
  - `doc/module_impl/backend/cbodybuilder_implementation.md`
  - `doc/module_impl/backend/call_global_implementation.md`
  - `doc/module_impl/backend/load_static_implementation.md`
  - `doc/module_impl/backend/implicit_conversion_implementation.md`

## 1. 当前职责边界

`CBuiltinBuilder` 是 C backend 内置类型构造策略的集中入口。它负责：

1. 生成 `godot_new_<BuiltinType>` 与 `godot_new_<BuiltinType>_with_<arg_types>` 形式的构造函数符号名。
2. 通过 `ExtensionBuiltinClass` metadata 校验普通 builtin constructor 是否存在。
3. 为 `construct_builtin`、`construct_array`、`construct_dictionary` 提供统一构造分发。
4. 物化 utility default literal 与 builtin static constant literal。
5. 处理 typed `Array` / typed `Dictionary` 的 runtime type tag 与 script carrier 参数。
6. 处理当前由 GDCC helper shim 提供的特殊 builtin constructor。

它不负责：

1. 解析 LIR 指令操作数。指令级 result/operand 校验由 `ConstructInsnGen` 负责。
2. 管理变量槽位生命周期。写槽、destroy、own/release、stable carrier 由 `CBodyBuilder` 负责。
3. 生成 builtin wrapper 本体。wrapper 符号与 ABI 由 `GodotBuiltinGenerator`、`GodotUtilityGenerator`
   和 `src/main/c/codegen/include_451/godot/godot_builtin.*` 维护。
4. 承载 frontend overload 或 implicit conversion 规则。普通构造匹配保持 exact type；需要 widening 的场景必须在上游 lowering 或 intrinsic 中显式物化。

`CGenHelper` 持有并发布 `builtinBuilder()`，同时继续负责通用 C 渲染、registry/context 访问、类型名渲染和 binding 数据装配。

## 2. 构造函数命名合同

### 2.1 符号规则

当前 builtin constructor wrapper 命名规则固定为：

| 场景 | 规则 | 示例 |
|---|---|---|
| 无参构造 | `godot_new_<TypeName>()` | `godot_new_Vector3()` |
| 有参构造 | `godot_new_<TypeName>_with_<arg1_type>_<arg2_type>...(...)` | `godot_new_Vector3_with_float_float_float(x, y, z)` |
| 非类型后缀 token | `godot_new_<TypeName>_with_<token>...(...)` | `godot_new_NodePath_with_utf8_chars(u8"...")` |

带参数的构造函数必须包含 `_with_` 后缀，并且后缀必须覆盖所有参数。
后缀 token 的来源有两类：

1. `renderConstructorFunctionNameByTypes(...)` 使用 `CGenHelper.renderGdTypeName(...)` 渲染 `GdType`。
2. `renderConstructorFunctionName(...)` 接收显式 token，例如 `utf8_chars`。

空白或 `null` 后缀 token 是代码生成错误，必须 fail-fast。

### 2.2 Metadata 校验

普通 builtin constructor 必须通过 `hasConstructor(...)` / `validateConstructor(...)` 校验。
校验流程按 `CGenHelper.renderGdTypeName(...)` 归一化目标类型和参数类型，再和 `ExtensionBuiltinClass.constructors()` 中的参数列表做 exact match。

metadata 中的参数类型通过 `ScopeTypeParsers.parseExtensionTypeMetadata(...)` 解析。
无法解析的 constructor metadata 不参与匹配，不应被当作隐式兼容候选。

helper shim constructor 是显式白名单例外，可以跳过 API metadata 校验，但仍要求实参类型精确匹配白名单签名。

## 3. 构造分发

### 3.1 指令入口

`ConstructInsnGen` 注册并处理以下 opcode：

| LIR 指令 | 结果要求 | 分发 |
|---|---|---|
| `construct_builtin` | non-ref result | 解析 variable operands 后调用 `CBuiltinBuilder.constructBuiltin(...)` |
| `construct_array` | `Array` 或 `Packed*Array` result | 校验 hint 后调用 `constructBuiltin(..., List.of())` |
| `construct_dictionary` | `Dictionary` result | 校验 key/value hint 后调用 `constructBuiltin(..., List.of())` |
| `construct_object` | Object result | 由 `ConstructInsnGen` 自己处理对象构造与 ownership |

`CCodegen.generateFunctionPrepareBlock()` 与 `generateDefaultGetterSetterInitialization()` 会自动为非 primitive / 非 literal 默认值路径注入构造指令：

- `GdArrayType` -> `ConstructArrayInsn(result, elementTypeName)`
- `GdPackedArrayType` -> `ConstructArrayInsn(result, null)`
- `GdDictionaryType` -> `ConstructDictionaryInsn(result, keyTypeName, valueTypeName)`
- 其他 builtin -> `ConstructBuiltinInsn(result, List.of())`

### 3.2 `constructBuiltin(...)` 分支

`CBuiltinBuilder.constructBuiltin(...)` 先按 target type 分支：

1. `GdArrayType` -> `constructArray(...)`
2. `GdDictionaryType` -> `constructDictionary(...)`
3. 其他 builtin，包括 `Packed*Array` -> `constructRegularBuiltin(...)`

这个路由不对称是当前设计事实：

- `Array` / `Dictionary` 有 typed container runtime metadata，需要专用构造路径。
- `Packed*Array` 是普通 builtin，由 `ExtensionBuiltinClass` 构造签名驱动。

### 3.3 普通 builtin

普通 builtin constructor 匹配规则：

1. 根据 `ValueRef.type()` 形成参数类型列表。
2. 优先查找 API metadata 中 exact constructor。
3. API metadata 未命中时，仅尝试 helper shim 白名单。
4. 两者均未命中时抛出 constructor validation 错误。

当前不在 `constructBuiltin(...)` 中做 implicit conversion。
例如 `Variant` 参数不会自动匹配 `int`，`int` 参数也不会自动匹配 `float` constructor。
需要 widening 的路径必须在 frontend/lowering 或 intrinsic 侧生成目标类型 temp。
`String <-> StringName` ordinary boundary 也是同一原则：frontend 必须先显式生成 target-typed
`ConstructBuiltinInsn`，backend constructor matcher 只看到 `StringName(String)` 或
`String(StringName)` 的 exact constructor metadata，不自行推断 implicit conversion。

### 3.4 Helper Shim 白名单

当前 GDCC helper shim constructor 只覆盖以下签名：

| target | 参数签名 | 目标符号 |
|---|---|---|
| `Transform2D` | 6 x `float` | `godot_new_Transform2D_with_float_float_float_float_float_float` |
| `Transform3D` | 12 x `float` | `godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float` |
| `Basis` | 9 x `float` | `godot_new_Basis_with_float_float_float_float_float_float_float_float_float` |
| `Projection` | 16 x `float` | `godot_new_Projection_with_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float` |

不要为普通 numeric constructor 继续扩展硬编码表。
可维护路径是优先使用 API metadata；helper shim 只用于 Godot metadata 不提供但 GDCC runtime 明确提供的版本化兼容函数。

## 4. Typed Container 构造

### 4.1 Typed Array

`Array[Variant]` 走普通 `godot_new_Array()` 或 API-backed copy constructor 路径。

`Array[T]` 且 `T != Variant` 时，`constructArray(...)` 使用：

```c
godot_new_Array_with_Array_int_StringName_Variant(
    &base_array,
    (godot_int)GDEXTENSION_VARIANT_TYPE_<T>,
    GD_STATIC_SN(u8"<class_name_or_empty>"),
    &script_variant
)
```

当前约束：

1. runtime 参数最多一个。
2. 无 runtime 参数时，先构造 generic `Array[Variant]` base temp。
3. 有 runtime 参数时，该参数必须是可赋值到 generic `Array[Variant]` 的 `Array`。
4. script 参数必须是真实 `Variant nil` temp，不传 `NULL`。
5. element type 为 object 时，`class_name` 使用 object type name；其他类型使用空 `StringName`。

### 4.2 Typed Dictionary

`Dictionary[Variant, Variant]` 走普通 `godot_new_Dictionary()` 或 API-backed copy constructor 路径。

`Dictionary[K, V]` 只要 key 或 value 不是 `Variant`，就使用：

```c
godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant(
    &base_dictionary,
    (godot_int)GDEXTENSION_VARIANT_TYPE_<K>,
    GD_STATIC_SN(u8"<key_class_name_or_empty>"),
    &key_script_variant,
    (godot_int)GDEXTENSION_VARIANT_TYPE_<V>,
    GD_STATIC_SN(u8"<value_class_name_or_empty>"),
    &value_script_variant
)
```

当前约束：

1. runtime 参数最多一个。
2. 无 runtime 参数时，先构造 generic `Dictionary[Variant, Variant]` base temp。
3. 有 runtime 参数时，该参数必须是可赋值到 generic dictionary 的 `Dictionary`。
4. key/value script 参数都必须是真实 `Variant nil` temp。
5. object key/value 使用 object type name；非 object 使用空 `StringName`。

## 5. Literal Materialization

### 5.1 入口

`CBuiltinBuilder` 提供两个公开 literal 物化入口：

1. `materializeUtilityDefaultValue(...)`
   - 用于 utility function default argument。
   - 错误信息必须带 utility name 与 1-based parameter index。
2. `materializeStaticLiteralValue(...)`
   - 用于 builtin static constant literal。
   - 错误信息必须带 class name 与 constant name。

两个入口共享 `materializeLiteralValue(...)`，最终通过 `CBodyBuilder` 写入 caller 提供的 writable target。
target 生命周期由调用方控制。

### 5.2 支持的 literal 形态

当前支持：

| literal | 条件 | 生成策略 |
|---|---|---|
| `null` | `Variant` target | `godot_new_Variant_nil()` |
| `null` | Object target | `NULL` Godot pointer |
| bool/int/float | 对应 primitive target | 直接写入，float 支持 `inf` / `+inf` / `-inf` 映射 |
| `"..."` | `NodePath` target | `godot_new_NodePath_with_utf8_chars(u8"...")` |
| `"..."` | 其他 string-like target | `valueOfStringPtrLiteral(...)` |
| `&"..."` | `StringName` | `valueOfStringNamePtrLiteral(...)` |
| `$"..."` | `NodePath` | `godot_new_NodePath_with_utf8_chars(u8"...")` |
| `[]` | `Array` target | `constructBuiltin(..., List.of())` |
| `{}` | `Dictionary` target | `constructBuiltin(..., List.of())` |
| `<Type>(...)` | constructor target | registry resolve + constructor materialization |

空 literal、类型不匹配 literal、未知 constructor type、unsupported argument literal 都必须 fail-fast，
不能生成半正确 C 代码。

### 5.3 Constructor Literal

constructor literal 流程：

1. 解析 `<Type>(...)` 的 type name，并通过 `ClassRegistry.tryResolveDeclaredType(...)` 获取 constructor type。
2. 校验 constructor type 可赋值给 target type。
3. 使用括号/字符串感知的参数切分逻辑拆分参数。
4. 从 API metadata candidate 与 helper shim candidate 中选择可 materialize 的签名。
5. 将参数 literal 物化为 `ValueRef`。
6. 调用 `constructBuiltin(...)` 写入 target；若 constructor type 和 target type 不同，先写入临时变量再 `assignVar(...)` 到 target。

参数 materialization 支持 bool、int、float、string、StringName、`[]`、`{}`。
它不会递归物化嵌套 constructor 参数；若后续需要支持，必须同步扩展解析、类型选择和测试。

## 6. 长期约定

1. `CBuiltinBuilder` 是 builtin constructor 命名、校验和 typed container 构造策略的唯一 backend 事实源。
   新增构造路径应优先复用它，而不是在生成器中另写符号拼接。
2. `construct_*` 指令与 default literal materialization 必须复用同一构造策略，避免 prepare/init 路径与 call/default 路径漂移。
3. 普通 constructor 匹配保持 exact type。不要在 backend constructor matcher 中偷偷加入 implicit conversion。
4. typed container 的 script 参数必须使用真实 nil `Variant` temp，不回退为 `NULL`。
5. `Array` / `Dictionary` typed 构造依赖 `GDExtensionVariantType` enum；没有 variant type 的元素类型必须 fail-fast。
6. object leaf 的 typed container class name 使用 `GdObjectType.getTypeName()`；非 object leaf 使用空 `StringName`。
7. `Packed*Array` 继续走普通 builtin constructor 路径。若未来改为专用路径，需要同步更新
   `construct_array_implementation.md`、本文档和测试。
8. 与模块语义无关的纯字符串处理优先复用 `StringUtil`。constructor 参数切分保留在 builder 内部，
   因为它直接承载 default literal 语义。
9. 新增 helper shim 时必须同时更新 runtime helper、binding usage tracking、本文档和正反向测试。

## 7. 回归测试基线

### 7.1 单元生成测试

- `src/test/java/gd/script/gdcc/backend/c/gen/CConstructInsnGenTest.java`
  - `construct_builtin` helper shim 路径
  - 非 variable operand fail-fast
  - exact metadata matching 与 Variant operand 拒绝
  - typed `Array` / typed `Dictionary` 构造
  - generic container 不生成 typed constructor
  - `Packed*Array` 构造与 hint 拒绝
  - unknown object leaf container hint 兼容
  - `__prepare__` 自动注入构造指令
- `src/test/java/gd/script/gdcc/backend/c/gen/UtilityDefaultLiteralMaterializationTest.java`
  - Godot 4.5.1 API 中已知 default literal 覆盖
  - typed array default metadata 解析
  - `Array[Array]([])` 默认值
- `src/test/java/gd/script/gdcc/backend/c/gen/CallGlobalInsnGenTest.java`
  - utility default 参数补全与 `CBuiltinBuilder.materializeUtilityDefaultValue(...)` 协作
- `src/test/java/gd/script/gdcc/backend/c/gen/CLoadStaticInsnGenTest.java`
  - builtin static constant literal 物化入口
- `src/test/java/gd/script/gdcc/backend/c/gen/CGenHelperTest.java`
  - extension type 文本解析与 container hint 兼容规则
- `src/test/java/gd/script/gdcc/backend/c/gen/binding/usage/GodotBindingUsageSessionTest.java`
  - helper shim constructor 被 binding usage session 覆盖
- `src/test/java/gd/script/gdcc/backend/c/gen/binding/GodotBuiltinGeneratorTest.java`
  - generated builtin wrapper contract
- `src/test/java/gd/script/gdcc/backend/c/gen/binding/GodotUtilityGeneratorTest.java`
  - utility wrapper default argument 与 return carrier 生成规则

### 7.2 引擎集成测试

- `src/test/java/gd/script/gdcc/backend/c/gen/CConstructInsnGenEngineTest.java`
  - real Godot 下 typed container、generic container、helper-shim builtin、packed array、object constructor 路径
- `src/test/java/gd/script/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java`
  - `constructor/int_to_float_builtin_constructor.gd`
  - `constructor/builtin_variant_scalar_roundtrip.gd`
  - `constructor/builtin_variant_container_roundtrip.gd`

建议命令：

```bash
script/run-gradle-targeted-tests.sh --tests CConstructInsnGenTest
script/run-gradle-targeted-tests.sh --tests UtilityDefaultLiteralMaterializationTest
script/run-gradle-targeted-tests.sh --tests CallGlobalInsnGenTest,CLoadStaticInsnGenTest,CGenHelperTest
script/run-gradle-targeted-tests.sh --tests GodotBindingUsageSessionTest,GodotBuiltinGeneratorTest,GodotUtilityGeneratorTest
script/run-gradle-targeted-tests.sh --tests CConstructInsnGenEngineTest
script/run-gradle-targeted-tests.sh --tests GdScriptUnitTestCompileRunnerTest
./gradlew classes --no-daemon --info --console=plain
```

## 8. 风险与防线

1. **构造符号拼接漂移**：如果生成器直接拼 `godot_new_*`，很容易漏掉 `_with_<types>` 后缀或特殊 token。
   防线是所有构造符号都走 `renderConstructorBaseName(...)`、`renderConstructorFunctionName(...)`
   或 `renderConstructorFunctionNameByTypes(...)`。
2. **typed container 半修复**：只设置 type enum 但不给真实 script `Variant` carrier，或传 `NULL`，会让运行时 ABI 和
   Godot typed container constructor 预期不一致。防线是保留对真实 nil `Variant` temp 的生成断言。
3. **backend 隐式放宽**：在 constructor matcher 中引入 `int -> float` 等放宽会绕过 frontend lowering 的显式 materialization，
   也会破坏 overload / constructor route 的一致性。防线是 exact matching + 负向测试。
4. **metadata 解析漂移**：constructor metadata、container hint、method metadata 若使用不同 parser，会让同一类型文本在不同路径下产生不同结果。
   防线是复用 `ScopeTypeParsers.parseExtensionTypeMetadata(...)` 与 `CGenHelper.parseExtensionType(...)`。
5. **helper shim 白名单扩大失控**：helper shim 是版本化 runtime contract，不是普通 constructor 的替代机制。
   防线是新增 shim 必须同时有 runtime wrapper、usage tracking 和生成测试。
