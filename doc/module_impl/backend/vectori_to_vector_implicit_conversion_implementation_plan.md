# Vector*i 到 Vector* 隐式转换实施计划

> 本文档是 `Vector2i -> Vector2`、`Vector3i -> Vector3`、`Vector4i -> Vector4`
> 隐式转换的具体实施计划。当前状态为计划，不代表功能已经落地。

## 1. 背景与目标

GDCC 当前已经支持 source-level ordinary typed boundary 上的 `int -> float` 隐式转换，
并通过 `ALLOW_WITH_INTRINSIC_CAST`、`call_intrinsic "c_int_to_float"`、C backend intrinsic
以及入站 `call_func` wrapper 窄兼容规则闭合。

本次计划要支持同一类 ordinary typed boundary 上的 int-vector 到 float-vector widening：

- `Vector2i -> Vector2`
- `Vector3i -> Vector3`
- `Vector4i -> Vector4`

目标边界与 `int -> float` 保持一致：

- local initializer
- class property initializer
- assignment
- fixed call argument
- vararg 中目标 slot 为具体 `Vector*` 的 boundary
- return
- subscript key/value 中 ordinary boundary 已经存在的消费路径
- 入站 `call_func` wrapper 的 runtime Variant 参数兼容

## 2. 明确非目标

本计划不支持以下能力：

- `Vector2 -> Vector2i`、`Vector3 -> Vector3i`、`Vector4 -> Vector4i`
- `Rect2i -> Rect2` 或 `Rect2 -> Rect2i`
- `Vector*i` 与 `Vector*` 之间维度不一致的转换
- `PackedVector*iArray -> PackedVector*Array`
- `Array[Vector*i] -> Array[Vector*]`
- `Dictionary[Vector*i, V] -> Dictionary[Vector*, V]` 这类容器类型递归协变
- operator numeric promotion，例如 `Vector3i + Vector3`
- 放宽 backend/global `ClassRegistry.checkAssignable(...)`

Godot 的 `Variant::can_convert_strict(...)` 同时支持反向 `Vector* -> Vector*i` 与
`Rect2i <-> Rect2` 等转换；GDCC 本次只纳入用户明确要求的 `Vector*i -> Vector*`。
这些其它 strict conversion 后续应按独立扩面处理。

## 3. 已确认事实

### 3.1 文档事实

- `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md` 当前把
  `Vector2i -> Vector2`、`Vector3i -> Vector3`、`Vector4i -> Vector4` 标记为 Godot 支持、
  GDCC 不支持。
- `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md` 要求 ordinary typed
  boundary 的允许范围先由 `frontend_implicit_conversion_matrix.md` 维护，再由 shared helper
  与 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 统一物化。
- `doc/module_impl/backend/int_to_float_implicit_conversion_implementation.md` 已经固定了当前
  `int -> float` 的分层模型：frontend decision、lowering materialization、LIR intrinsic、
  C backend intrinsic、入站 `call_func` wrapper 兼容规则。
- `doc/gdcc_type_system.md` 明确 `ClassRegistry#checkAssignable` 不承载 numeric promotion；
  其它 implicit promotion 必须由专门 lowering / instruction 语义处理。
- `doc/gdcc_c_backend.md` 要求非 primitive builtin value 的 C 形态遵守 ref 规则，非 `ref`
  value-semantic builtin 是 struct value，调用需要按 helper 规则传地址。

### 3.2 Godot 与 gdextension-lite 事实

- Godot `core/variant/variant.cpp` 的 `Variant::can_convert_strict(...)` 中，`VECTOR2`、
  `VECTOR3`、`VECTOR4` 目标分别接受 `VECTOR2I`、`VECTOR3I`、`VECTOR4I` source。
  参考：
  `https://github.com/godotengine/godot/blob/1078619908a1314f46c0439920b82fc187de92e4/core/variant/variant.cpp`
- `src/main/resources/extension_api_451.json` 中，`Vector2` / `Vector3` / `Vector4` 的 builtin
  constructor metadata 已包含对应 `Vector2i` / `Vector3i` / `Vector4i` 单参数构造函数。
- 本地生成过的 gdextension-lite 头/实现中存在：
  - `godot_new_Vector2_with_Vector2i(const godot_Vector2i *from)`
  - `godot_new_Vector3_with_Vector3i(const godot_Vector3i *from)`
  - `godot_new_Vector4_with_Vector4i(const godot_Vector4i *from)`

### 3.3 现有代码事实

- `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)` 已把
  `GdIntType -> GdFloatType` 与同维度 `GdIntVectorType -> GdFloatVectorType` 判定为
  `ALLOW_WITH_INTRINSIC_CAST`。
- `FrontendBodyLoweringSession.materializePrimitiveCast(...)` 当前 fail-fast 限定
  `int -> float`，并生成 `call_intrinsic "c_int_to_float"`。
- `CIntrinsicManager` 当前只注册 `CIntToFloatIntrinsic`。
- `CBodyBuilder.valueOfCastedVar(...)` 对非 object 类型使用 C cast 形态，例如
  `(godot_Vector3)$source`；这不适合 `Vector3i -> Vector3`，因为它是不同 struct 类型之间的
  constructor conversion，不是 C 标量 cast。
- `CBodyBuilder.callAssign(...)` 与 `renderArgument(...)` 已经能把非 primitive value-semantic
  参数按地址传递，所以 intrinsic 可以复用 existing constructor helper，而不是手写成员拆解。
- `CGenHelper.renderCallWrapperVariantTypeGate(...)` 当前唯一 wrapper widening 是
  `Variant(INT) -> float parameter`。
- `CGenHelper.renderCallWrapperUnpackExpr(...)` 当前为 float 参数单独处理 `Variant(INT)` payload；
  vector 参数仍只会调用 `godot_new_VectorN_with_Variant(...)`。

## 4. 设计决策

### 4.1 Conversion decision 命名

source-level boundary 显式物化统一使用以下 decision：

```text
ALLOW_WITH_INTRINSIC_CAST
```

该 decision 表达“source-level boundary 合法，但 lowering 必须生成 backend-owned intrinsic
materialization”。它覆盖：

- `int -> float`
- `Vector2i -> Vector2`
- `Vector3i -> Vector3`
- `Vector4i -> Vector4`

这是内部 enum 重命名，不保留旧名兼容层。所有调用点、测试名和文档引用同步更新。

Specificity rank 保持现有排序语义：

| decision | rank |
| --- | ---: |
| `ALLOW_DIRECT` | 4 |
| `ALLOW_WITH_LITERAL_NULL` | 3 |
| `ALLOW_WITH_INTRINSIC_CAST` | 2 |
| `ALLOW_WITH_PACK` / `ALLOW_WITH_UNPACK` | 1 |
| `REJECT` | 0 |

验收重点：

- `take(Vector3i)` 优先于 `take(Vector3)`。
- `take(Vector3)` 优先于 `take(Variant)`。
- 多个非支配候选继续 ambiguous，不借本次改动扩大 overload tie-break 规则。

### 4.2 LIR intrinsic 形态

新增三个 backend-owned intrinsic：

```text
$<Vector2_result> = call_intrinsic "c_vector2i_to_vector2" $<Vector2i_source>;
$<Vector3_result> = call_intrinsic "c_vector3i_to_vector3" $<Vector3i_source>;
$<Vector4_result> = call_intrinsic "c_vector4i_to_vector4" $<Vector4i_source>;
```

`c_int_to_float` 保持现名，避免无关 churn。

每个 vector intrinsic 的合同：

- result 必须存在。
- result 必须是非 `ref` 的对应 `GdFloatVectorType`，维度必须匹配 intrinsic 名称。
- exactly one argument。
- argument 必须是对应 `GdIntVectorType`，维度必须匹配 intrinsic 名称。
- argument 必须是变量操作数，不接受 literal。
- unknown intrinsic 继续 fail-fast。

### 4.3 C backend 生成方式

不要使用 `CBodyBuilder.valueOfCastedVar(...)` 实现 vector conversion。正确生成应复用
gdextension-lite constructor：

```c
$target = godot_new_Vector3_with_Vector3i(&$source);
```

生成路径建议：

- 新增 `CVectorIToVectorIntrinsic implements CIntrinsicFunction`。
- 该类可用一个内部 `record Spec(String name, GdIntVectorType sourceType, GdFloatVectorType targetType)`
  保存三个 intrinsic 的固定映射。
- `generateCCode(...)` 校验 result / argument 后调用：
  - `bodyBuilder.helper().builtinBuilder().validateConstructor(targetType, List.of(sourceType), false)`
  - `bodyBuilder.callAssign(bodyBuilder.targetOfVar(resultVar), ctorName, targetType, List.of(bodyBuilder.valueOfVar(sourceVar)))`
- `ctorName` 通过 `CBuiltinBuilder.renderConstructorFunctionNameByTypes(targetType, List.of(sourceType))`
  生成，避免硬编码 constructor symbol 拼接。

这样可以同时处理 source 是 local value 与 `ref` parameter 的情况，因为 `CBodyBuilder.renderArgument(...)`
已经负责 `&` / direct pointer 形态。

### 4.4 入站 `call_func` wrapper

`call_func` wrapper 是不经过 frontend lowering 的独立入口。若只实现 frontend ordinary boundary，
下面路径仍会失败：

```gdscript
target.call("take_vector3", Vector3i(1, 2, 3))
```

因此 wrapper 需要和 `int -> float` 一样增加窄兼容规则：

- `Vector2` 参数额外接受 runtime `Variant(VECTOR2I)`
- `Vector3` 参数额外接受 runtime `Variant(VECTOR3I)`
- `Vector4` 参数额外接受 runtime `Variant(VECTOR4I)`
- 参数 metadata 与 `r_error->expected` 仍发布目标 `Vector*`
- `Vector*i` 参数不接受 `Variant(VECTOR*)`
- `Rect2` 参数不接受 `Variant(RECT2I)`
- 其它 Godot strict conversions 继续拒绝

实现建议：

- 在 `CGenHelper` 中新增两个私有 helper：
  - `vectorWideningSourceVariantType(GdType targetType)`
  - `renderVectorWideningCallWrapperExpr(GdType targetType, String variantPtrExpr, String typeExpr)`
- `renderCallWrapperVariantTypeGate(...)` 对 `GdFloatVectorType` 且维度为 2/3/4 时，追加对应
  `GDEXTENSION_VARIANT_TYPE_VECTORNI`。
- `renderCallWrapperUnpackExpr(...)` 对命中 `Vector*i` runtime type 的情况，调用新的 C helper：

```c
gdcc_new_Vector3_from_call_arg_variant((GDExtensionVariantPtr)p_args[0], arg0_type)
```

- 在 `src/main/c/codegen/include_451/gdcc/gdcc_helper.h` 新增三个小 helper：
  - `gdcc_new_Vector2_from_call_arg_variant(...)`
  - `gdcc_new_Vector3_from_call_arg_variant(...)`
  - `gdcc_new_Vector4_from_call_arg_variant(...)`

helper 内部逻辑固定为：

1. 如果 runtime type 是 `VECTORNI`，先 `godot_new_VectorNi_with_Variant(value)` 得到局部 source。
2. 再调用 `godot_new_VectorN_with_VectorNi(&source)`。
3. 否则走 `godot_new_VectorN_with_Variant(value)`。

这样避免在 FreeMarker 模板中为了拿临时变量地址而扩展参数 materialization block。

## 5. 分步骤实施

### 步骤 1：更新事实源文档

执行状态（2026-05-13）：已完成。

- `frontend_implicit_conversion_matrix.md` 已把同维度 `Vector*i -> Vector*` 标为 GDCC 支持，并保留反向、错维度、`Rect2i -> Rect2`、容器递归 widening 与 operator promotion 为非目标。
- `frontend_lowering_(un)pack_implementation.md` 与 `frontend_rules.md` 已同步到 ordinary typed-boundary intrinsic materialization 表述。
- `int_to_float_implicit_conversion_implementation.md` 已把 shared decision 名称同步为 `ALLOW_WITH_INTRINSIC_CAST`，并继续只维护 `int -> float` 自身合同。
- `gdcc_lir_intrinsic.md` 已作为 intrinsic catalog 事实源保留 vector intrinsic 规划状态；在 backend registry 尚未实施前，catalog 不提前标记为 `Implemented`，以保持与实际 registry 一致。

修改：

- `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
- `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
- `doc/module_impl/frontend/frontend_rules.md`
- `doc/module_impl/backend/int_to_float_implicit_conversion_implementation.md`
- `doc/gdcc_lir_intrinsic.md`

要求：

- 将 `Vector2i -> Vector2`、`Vector3i -> Vector3`、`Vector4i -> Vector4` 的 GDCC 列改为 `Y`。
- 明确它们是 ordinary typed-boundary intrinsic materialization，不是 constructor special route。
- 明确反向 `Vector* -> Vector*i` 与 `Rect2i -> Rect2` 仍是 `N`。
- 把 intrinsic materialization decision 的文档表述统一为 `ALLOW_WITH_INTRINSIC_CAST`。
- 在 `doc/gdcc_lir_intrinsic.md` 中登记新增 vector intrinsic 的最终状态。

验收：

- 文档中不存在“`Vector*i -> Vector*` 当前不支持”的残留事实。
- 文档没有把容器递归 widening、operator promotion 或 `ClassRegistry.checkAssignable(...)` 放宽写成本次范围。
- `doc/gdcc_lir_intrinsic.md` 的 catalog 与实际 `CIntrinsicManager` registry 一致。

### 步骤 2：建立并维护 LIR intrinsic 事实源

执行状态（2026-05-13）：已完成。

- `doc/gdcc_lir_intrinsic.md` 已保持为 `call_intrinsic` surface、registry 合同和 catalog 的统一事实源。
- `doc/gdcc_low_ir.md` 的 `call_intrinsic` 段落已保持为 textual shape + 链接，不维护重复 catalog。
- `c_int_to_float` 当前为 `Implemented`；三个 vector intrinsic 在 backend registry 尚未实施前保持 `Planned`，以避免 catalog 与 `CIntrinsicManager` 不一致。

修改：

- `doc/gdcc_lir_intrinsic.md`
- `doc/gdcc_low_ir.md`

要求：

- `doc/gdcc_lir_intrinsic.md` 作为 LIR intrinsic surface、registry 合同与 intrinsic catalog 的统一事实源。
- `doc/gdcc_low_ir.md` 的 `call_intrinsic` 段落只保留 textual shape 与指向该事实源的链接。
- 在 catalog 中记录：
  - `c_int_to_float` 当前为 `Implemented`
  - `c_vector2i_to_vector2`、`c_vector3i_to_vector3`、`c_vector4i_to_vector4` 在本功能实施前为 `Planned`
  - 本功能落地后将三个 vector intrinsic 更新为 `Implemented`
- 除 `doc/module_impl/backend/int_to_float_implicit_conversion_implementation.md` 与本文档外，其它文档不再维护平行 intrinsic 清单；
  需要说明 intrinsic surface 时链接 `doc/gdcc_lir_intrinsic.md`。

验收：

- 新文档记录每个 intrinsic 的名称、状态、LIR 形态、result / argument 合同、C backend 语义与事实源链接。
- `doc/gdcc_low_ir.md` 不再重复维护 backend registry / intrinsic catalog。
- 后续新增 intrinsic 时有明确 checklist，避免散落在 feature 文档中形成多份清单。

### 步骤 3：扩展 frontend boundary decision

执行状态（2026-05-13）：已完成。

- `FrontendVariantBoundaryCompatibility.Decision` 已使用 `ALLOW_WITH_INTRINSIC_CAST`。
- `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)` 已接受同维度且维度为 2/3/4 的 `GdIntVectorType -> GdFloatVectorType`。
- 反向、错维度、非法维度、`Variant` pack/unpack 与 global `ClassRegistry.checkAssignable(...)` strict 边界均已用 focused tests 锚定。
- 已运行 `script/run-gradle-targeted-tests.sh --tests FrontendVariantBoundaryCompatibilityTest,ClassRegistryTest,ScopeMethodResolverTest`。

修改：

- `FrontendVariantBoundaryCompatibility`
- 依赖 decision enum 名称的测试与调用点

要求：

- 使用 `ALLOW_WITH_INTRINSIC_CAST` 表达所有 backend-owned intrinsic materialization boundary。
- 新增判定：
  - source 是 `GdIntVectorType`，target 是 `GdFloatVectorType`
  - source / target 维度相同
  - 维度只接受 2、3、4
- 保持 `ClassRegistry.checkAssignable(...)` strict，不在 registry 中加入该规则。

验收：

- `Vector3i -> Vector3` 返回 `ALLOW_WITH_INTRINSIC_CAST`。
- `Vector3 -> Vector3i` 返回 `REJECT`。
- `Vector2i -> Vector3` 返回 `REJECT`。
- `Vector3i -> Variant` 仍返回 `ALLOW_WITH_PACK`。
- `Variant -> Vector3` 仍返回 `ALLOW_WITH_UNPACK`。
- `ClassRegistryTest` 继续证明 vector widening 不属于 global assignability。

### 步骤 4：扩展 frontend lowering materialization

修改：

- `FrontendBodyLoweringSession`

要求：

- 将 `materializePrimitiveCast(...)` 调整为 `materializeIntrinsicCast(...)`。
- 保留 `int -> float` 的 `c_int_to_float` 输出。
- 新增 vector intrinsic 名称选择：
  - `Vector2i -> Vector2`：`c_vector2i_to_vector2`
  - `Vector3i -> Vector3`：`c_vector3i_to_vector3`
  - `Vector4i -> Vector4`：`c_vector4i_to_vector4`
- 继续由 `materializeFrontendBoundaryValue(...)` 唯一入口分配 target-typed temp。

验收：

- local initializer、assignment、return、fixed call argument、subscript key/value 相关 lowering 测试中，
  `Vector*i -> Vector*` 都生成 target-typed temp 与对应 `CallIntrinsicInsn`。
- source slot 不直接流入 target consumer。
- 不在各 consumer 里出现局部 `Vector3i -> Vector3` 判断。

### 步骤 5：扩展 LIR parser / serializer 测试

实现本身无需修改 parser / serializer 数据结构，因为 `call_intrinsic` 名称是字符串。

要求：

- 增加至少一个 vector intrinsic 的 simple text parser 测试。
- 增加至少一个 vector intrinsic 的 serializer 测试。
- 保留坏 operand 测试，确认 literal argument 仍被 parser 或 backend 拒绝。

验收：

- `$v = call_intrinsic "c_vector3i_to_vector3" $vi;` roundtrip 稳定。
- unknown intrinsic 的失败仍发生在 backend intrinsic registry，而不是 parser 抢先接受成其它指令。

### 步骤 6：实现 C backend intrinsic

修改：

- `src/main/java/gd/script/gdcc/backend/c/gen/intrinsic/CVectorIToVectorIntrinsic.java`
- `CIntrinsicManager`
- `CallIntrinsicInsnGenTest`
- 新增或扩展 `CVectorIToVectorIntrinsicTest`
- `CIntrinsicManagerTest`

要求：

- `CIntrinsicManager` 注册三个 vector intrinsic。
- intrinsic 校验 result / argument 类型、维度、ref、参数数量。
- intrinsic 使用 `CBuiltinBuilder.renderConstructorFunctionNameByTypes(...)` 和 `CBodyBuilder.callAssign(...)`。
- 不使用 `valueOfCastedVar(...)`。
- 不手写目标槽位生命周期逻辑。

验收：

- `Vector3i -> Vector3` 生成 `godot_new_Vector3_with_Vector3i(...)`。
- source 是非 `ref` local 时参数形态包含 `&$source`。
- source 是 `ref` parameter 时参数形态直接传 `$source`。
- result 是 `ref`、result 类型错误、argument 类型错误、维度错误、参数数量错误都 fail-fast。
- unknown intrinsic 继续由 `CallIntrinsicInsnGen` 报 registry miss。

### 步骤 7：扩展入站 wrapper

修改：

- `CGenHelper`
- `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
- `src/main/c/codegen/template_451/entry.h.ftl` 只在 helper API 不足时修改；优先不改模板结构
- `CGenHelperTest`
- 相关 C codegen / runtime integration tests

要求：

- `renderCallWrapperVariantTypeGate(...)` 对 `Vector*` 参数接受 exact `VECTOR*` 与 widening source `VECTOR*I`。
- `renderCallWrapperUnpackExpr(...)` 对 vector target 使用 `gdcc_new_VectorN_from_call_arg_variant(...)`。
- helper 对 exact runtime target type 仍走 `godot_new_VectorN_with_Variant(...)`。
- helper 对 widening source 走 `godot_new_VectorNi_with_Variant(...)` 后再走 `godot_new_VectorN_with_VectorNi(...)`。
- `r_error->expected` 与 metadata 不改，仍是目标 `GDEXTENSION_VARIANT_TYPE_VECTORN`。

验收：

- 生成的 wrapper gate 包含目标 `VECTOR3` 与 source `VECTOR3I`。
- 生成的 wrapper unpack 表达式调用 `gdcc_new_Vector3_from_call_arg_variant(...)`。
- `Vector3` 参数接受 `Object.call(..., Vector3i(...))`。
- `Vector3i` 参数不接受 `Object.call(..., Vector3(...))`。
- `Rect2` 参数不接受 `Object.call(..., Rect2i(...))`。

### 步骤 8：补齐 frontend sema 与 overload 测试

修改测试：

- `FrontendVariantBoundaryCompatibilityTest`
- `FrontendAssignmentSemanticSupportTest`
- `FrontendTypeCheckAnalyzerTest`
- `FrontendExpressionSemanticSupportTest`
- `ScopeMethodResolverTest`
- 如 constructor route 受影响，补充 `FrontendConstructorResolutionSupport` 相关覆盖

要求：

- 覆盖 local / assignment / return / fixed call argument 的接受。
- 覆盖反向与错维度拒绝。
- 覆盖 overload specificity。

验收：

- `take(Vector3i)` 与 `take(Vector3)` 同时存在时，`take(Vector3i(...))` 选择 exact `Vector3i`。
- `take(Vector3)` 与 `take(Variant)` 同时存在时，`take(Vector3i(...))` 选择 `Vector3`。
- 只有 `take(Vector3i)` 时，`take(Vector3(...))` 不因本次改动反向匹配。

### 步骤 9：补齐 lowering 与 C 集成测试

修改测试：

- `FrontendBodyLoweringSessionTest`
- `FrontendLoweringBodyInsnPassTest`
- `FrontendWritableRouteSupportTest`，仅当 subscript route 覆盖需要
- `CCodegenTest` 或现有 backend build integration test
- `doc/test_suite.md` 中添加 runtime anchor

要求：

- 编译链路覆盖普通 source-level boundary。
- runtime 覆盖实际 Godot 可观察结果。

验收：

- `func f(v: Vector3i) -> Vector3: return v` 生成 intrinsic 并在 runtime 得到 `Vector3(1, 2, 3)`。
- `var v: Vector3 = Vector3i(1, 2, 3)` runtime 正确。
- `take_vector(Vector3i(1, 2, 3))` 走 fixed argument intrinsic。
- 入站 `target.call("take_vector", Vector3i(1, 2, 3))` 成功。
- 负例 `func f(v: Vector3) -> Vector3i: return v` compile 阶段拒绝。

## 6. 推荐测试命令

开发迭代时按从小到大运行：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendVariantBoundaryCompatibilityTest
script/run-gradle-targeted-tests.sh --tests FrontendBodyLoweringSessionTest
script/run-gradle-targeted-tests.sh --tests CallIntrinsicInsnGenTest,CIntrinsicManagerTest,CVectorIToVectorIntrinsicTest
script/run-gradle-targeted-tests.sh --tests CGenHelperTest
script/run-gradle-targeted-tests.sh --tests FrontendLoweringBodyInsnPassTest
```

涉及 runtime anchor 后，再运行对应 backend build / runtime focused test。PR 前按仓库要求再做完整
`clean build`。

## 7. 风险与注意事项

- 不要把 `Vector*i -> Vector*` 写进 `ClassRegistry.checkAssignable(...)`。否则 backend fixed
  argument validation、container covariance、诊断行为都会被隐式放宽。
- 不要用 `(godot_Vector3)$source` 这类 C cast；它不是合法的 GDExtension builtin conversion。
- 不要复用 `c_int_to_float` 的 `valueOfCastedVar(...)` 路径实现 vector conversion。
- 不要把 wrapper 的 vector widening 写进 `renderUnpackFunctionName(...)`；该 helper 同时服务全局
  `UnpackVariantInsn`，会扩大 `Variant -> Vector*` 的普通 runtime 语义。
- 不要把反向 `Vector* -> Vector*i` 顺手加入。Godot 支持不等于本次 GDCC 扩面必须一次性跟进。
- 如果将来继续支持 `Rect2i -> Rect2`，应复用本计划的 intrinsic/wrapper 分层，但作为单独扩面更新矩阵和测试。

## 8. 完成定义

本功能完成时必须同时满足：

- 文档矩阵、frontend lowering 文档、backend intrinsic 文档一致。
- shared semantic 接受且只接受同维度 `Vector*i -> Vector*`。
- lowering 对所有 ordinary boundary 统一生成对应 `call_intrinsic`。
- C backend intrinsic 使用 gdextension-lite constructor 生成合法 C。
- 入站 `call_func` wrapper 接受 `Variant(Vector*i)` 到 `Vector*` 参数。
- 反向、错维度、容器递归 widening、operator promotion 均保持拒绝。
- focused 单元测试和至少一个 runtime anchor 通过。
