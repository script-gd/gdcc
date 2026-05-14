# 隐式转换实现说明

> 本文档是 GDCC 已落地隐式转换实现的长期事实源。  
> 只保留当前语义边界、实现合同、测试锚点与后续维护规则；不记录阶段性实施步骤、执行进度或验收流水账。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - frontend ordinary typed boundary
  - frontend constructor / callable overload ranking 中复用的 boundary specificity
  - frontend lowering materialization
  - LIR `call_intrinsic` surface
  - C backend intrinsic codegen
  - GDExtension 入站 `call_func` wrapper 的窄兼容规则
- 当前支持的非 direct implicit conversion：
  - `int -> float`
  - `Vector2i -> Vector2`
  - `Vector3i -> Vector3`
  - `Vector4i -> Vector4`
- 关联文档：
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_lir_intrinsic.md`
  - `doc/gdcc_c_backend.md`
  - `doc/test_suite.md`

## 当前支持范围

GDCC 在 source-level ordinary typed boundary 上支持以下 widening：

| source | target | materialization |
|---|---|---|
| `int` | `float` | `call_intrinsic "c_int_to_float"` |
| `Vector2i` | `Vector2` | `call_intrinsic "c_vector2i_to_vector2"` |
| `Vector3i` | `Vector3` | `call_intrinsic "c_vector3i_to_vector3"` |
| `Vector4i` | `Vector4` | `call_intrinsic "c_vector4i_to_vector4"` |

这些规则通过 shared frontend boundary helper 统一生效。assignment、call、return、constructor、
subscript 等 consumer 不得各自维护局部 widened conversion 表。

典型入口包括：

- local initializer
- class property initializer
- assignment
- return
- fixed call argument
- vararg 中目标 slot 为具体 widened target 的 boundary
- builtin constructor 参数，例如 `Vector3(1, 2.5, 3)` 或 constructor overload 中的 `Vector3i -> Vector3`
- typed subscript key/value boundary：
  - `Dictionary[float, V]` key 接收 `int`
  - `Dictionary[Vector3, V]` key 接收 `Vector3i`
  - `Dictionary[K, Vector3]` value 写入接收 `Vector3i`
  - `Array[T]` / packed array 使用 `Variant` index 时先 ordinary-unpack 到 `int`

## 明确不支持

以下转换继续禁止：

- `float -> int`
- `Vector2 -> Vector2i`、`Vector3 -> Vector3i`、`Vector4 -> Vector4i`
- `Vector*i` 与 `Vector*` 之间维度不一致的转换
- `Rect2i -> Rect2` 或 `Rect2 -> Rect2i`
- `bool -> int`、`bool -> float`、`int -> bool`、`float -> bool`
- `String <-> StringName`、`String -> int`、`String -> float`
- unary / binary operator numeric promotion，例如 `int + float` 或 `Vector3i + Vector3`
- `PackedVector*iArray -> PackedVector*Array`
- `Array[int] -> Array[float]`
- `Array[Vector*i] -> Array[Vector*]`
- `Dictionary[int, V] -> Dictionary[float, V]`
- `Dictionary[Vector*i, V] -> Dictionary[Vector*, V]`
- `Dictionary[K, int] -> Dictionary[K, float]`
- `Dictionary[K, Vector*i] -> Dictionary[K, Vector*]`
- `Array` / packed array 的 `float` index 自动转 `int`

Godot 的 `Variant::can_convert_strict(...)` 支持更多 pair，例如反向 `Vector* -> Vector*i` 与
`Rect2i <-> Rect2`。GDCC 不因为 Godot strict conversion 支持某个 pair，就自动把它放进 frontend、
backend、wrapper 和 metadata。新增 pair 必须按本文档的后续扩展规则单独成文和测试。

`ClassRegistry.checkAssignable(...)` 仍是 strict assignability 真源，不承载 numeric promotion 或
value-type widening。

## Frontend Semantic Gate

入口：

- `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)`
- `FrontendVariantBoundaryCompatibility.isFrontendBoundaryCompatible(...)`
- `FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(...)`

当前 non-direct widening 返回：

```java
ALLOW_WITH_INTRINSIC_CAST
```

该 decision 表示 source-level boundary 合法，但 source slot 不能直接流入 target slot。lowering 必须显式生成 target-typed temp。

当前 decision rank 固定为：

| decision | rank |
|---|---:|
| `ALLOW_DIRECT` | 4 |
| `ALLOW_WITH_LITERAL_NULL` | 3 |
| `ALLOW_WITH_INTRINSIC_CAST` | 2 |
| `ALLOW_WITH_PACK` / `ALLOW_WITH_UNPACK` | 1 |
| `REJECT` | 0 |

该排序用于 bare call、constructor resolver 和 `ScopeMethodResolver` frontend path 的 overload specificity。关键约束：

- exact match 高于 intrinsic cast
- intrinsic cast 高于 `Variant` pack/unpack
- rejected pair 不参与 successful overload selection
- 多个非支配候选继续 ambiguous

示例：

- `take(1)` 在 `take(int)` 与 `take(float)` 同时存在时选择 `take(int)`
- `take(1)` 在 `take(float)` 与 `take(Variant)` 同时存在时选择 `take(float)`
- `take(Vector3i(...))` 在 `take(Vector3i)` 与 `take(Vector3)` 同时存在时选择 `take(Vector3i)`
- `take(Vector3i(...))` 在 `take(Vector3)` 与 `take(Variant)` 同时存在时选择 `take(Vector3)`
- 只有 `take(Vector3i)` 时，`take(Vector3(...))` 不反向匹配

## Constructor Route 合同

Builtin constructor resolution 复用同一 shared boundary compatibility 与 specificity rank。它不是独立的
constructor-only widening 表。

当前合同：

- exact constructor argument type 高于 widened constructor argument type
- widened constructor argument type 高于 `Variant` constructor fallback
- `Vector*i -> Vector*` 只允许同维度 2/3/4
- `Vector* -> Vector*i` 与错维度 `Vector*i -> Vector*` 必须拒绝
- unary stable `Variant` builtin constructor 仍是独立 route：
  - sema 发布 resolved constructor route
  - body lowering 直接 lower 为 `UnpackVariantInsn`
  - 不属于本文档的 `ALLOW_WITH_INTRINSIC_CAST` ordinary widening

## Frontend Lowering Materialization

入口：`FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`

`ALLOW_WITH_INTRINSIC_CAST` 必须分配新的 target-typed temp，并追加对应 `CallIntrinsicInsn`：

```text
$<float_temp> = call_intrinsic "c_int_to_float" $<int_source>;
$<Vector2_temp> = call_intrinsic "c_vector2i_to_vector2" $<Vector2i_source>;
$<Vector3_temp> = call_intrinsic "c_vector3i_to_vector3" $<Vector3i_source>;
$<Vector4_temp> = call_intrinsic "c_vector4i_to_vector4" $<Vector4i_source>;
```

后续 consumer 必须消费该 target-typed temp，而不是继续读取原始 source slot。

Subscript key/index lowering 也必须走同一 materialization 路径：

- `Dictionary[float, V]` 的 `int` key 先 cast 到 `float` temp，再发 keyed access
- `Dictionary[Vector3, V]` 的 `Vector3i` key 先 cast 到 `Vector3` temp，再发 keyed access
- `Dictionary[K, Vector3]` 的 `Vector3i` value 写入先 cast 到 `Vector3` temp
- `Array[T]` / packed array 的 `Variant` index 先 unpack 到 `int` temp，再发 indexed access
- access-kind selection 必须基于 materialized key/index type
- writable-route reverse commit 必须复用同一 frozen access chain 内已经 materialized 的 key/index slot

## LIR Surface

`CALL_INTRINSIC` 的 simple 文本形态保持：

```text
$<result_id> = call_intrinsic "<intrinsic_name>" $<arg1_id> ...
```

通用合同与 intrinsic catalog 由 `doc/gdcc_lir_intrinsic.md` 维护。本文档只记录 implicit conversion
如何消费这些 intrinsic。

当前 implicit conversion intrinsic 合同：

- result 必须存在
- result 必须是非 `ref` 的 target slot
- exactly one argument
- argument 必须是 source slot
- intrinsic argument 必须是变量操作数，不接受 literal
- unknown intrinsic 必须由 backend registry fail-fast

## C Backend Intrinsic

实现落点：

- `CIntrinsicManager`
  - backend-owned intrinsic 白名单
  - 当前注册 `c_int_to_float` 与三个 `c_vector*i_to_vector*` intrinsic
- `CIntrinsicFunction`
  - narrow intrinsic codegen interface
  - 接收已解析的 nullable result slot 与 argument slots
- `CIntToFloatIntrinsic`
  - 校验 result / argument 合同
  - 通过 `CBodyBuilder.valueOfCastedVar(source, GdFloatType.FLOAT)` 与 `assignVar(...)` 发射
- `CVectorIToVectorIntrinsic`
  - 校验 result / argument 合同
  - 通过 `CBuiltinBuilder.validateConstructor(...)` 与 `renderConstructorFunctionNameByTypes(...)` 选择 gdextension-lite constructor
  - 通过 `CBodyBuilder.callAssign(...)` 写入 target slot
- `CallIntrinsicInsnGen`
  - 注册 `GdInstruction.CALL_INTRINSIC`
  - 负责解析 result 与 variable arguments
  - unknown intrinsic 与坏 IR 使用 backend invalid-insn 路径报错

`int -> float` 生成的 C 代码语义等价于：

```c
$target = (godot_float)$source;
```

`Vector*i -> Vector*` 必须使用 gdextension-lite constructor conversion，不得使用 C cast：

```c
$target = godot_new_Vector3_with_Vector3i(&$source);
```

source 是非 `ref` local 时参数形态应取地址；source 是 `ref` parameter 时参数形态应直接传 pointer。
该 pointer shape 由 `CBodyBuilder.renderArgument(...)` / `callAssign(...)` 负责，intrinsic 不手写。

## 入站 `call_func` Wrapper 兼容规则

GDExtension 入站 `call_func` wrapper 是独立边界。它处理 Godot 通过 `Object.call(...)` 等 Variant-call
surface 调用 GDCC 生成方法的路径，不经过 frontend lowering，因此不会生成 `call_intrinsic`。

当前 wrapper 规则：

- 非 `Variant` 参数默认保持 exact `GDExtensionVariantType` gate
- 窄兼容例外只包括：
  - `float` 参数额外接受 `Variant(INT)`
  - `Vector2` 参数额外接受 `Variant(VECTOR2I)`
  - `Vector3` 参数额外接受 `Variant(VECTOR3I)`
  - `Vector4` 参数额外接受 `Variant(VECTOR4I)`
- metadata 与 `r_error->expected` 仍发布 target 参数类型
- `int` 参数不接受 `Variant(FLOAT)`
- `Vector*i` 参数不接受 `Variant(VECTOR*)`
- `Rect2` 参数不接受 `Variant(RECT2I)`
- ptrcall ABI 不变
- ordinary `Variant -> concrete` unpack 不变

实现落点：

- `CGenHelper.renderCallWrapperVariantTypeGate(...)`
  - 只维护入站 wrapper runtime gate
- `CGenHelper.renderCallWrapperUnpackExpr(...)`
  - 只维护入站 wrapper-local materialization
  - 优先消费前置 gate 已缓存的 runtime type expression
  - nullable `typeExpr` fallback 只用于没有 cached type expression 的调用点，并会重新生成 `godot_variant_get_type(...)`
- `src/main/c/codegen/template_451/entry.h.ftl`
  - 为非 `Variant` 参数生成 `argN_type`
  - gate 与 unpack 复用同一个 `argN_type`
- `src/main/c/codegen/include_451/gdcc/gdcc_intrinsic.h`
  - 提供 `gdcc_new_Vector2_from_call_arg_variant(...)`
  - 提供 `gdcc_new_Vector3_from_call_arg_variant(...)`
  - 提供 `gdcc_new_Vector4_from_call_arg_variant(...)`

wrapper-only vector helper 不是独立校验边界：

- helper 只根据已经通过 gate 的 cached runtime type 选择 exact `Vector*` unpack 或同维
  `Vector*i -> Vector*` constructor materialization
- helper 本身不重新验证 runtime type，也不负责设置 `r_error`
- generated `call_func` 模板必须先计算并缓存 `argN_type`，执行 `renderCallWrapperVariantTypeGate(...)`
  失败分支和 typed-container preflight，之后才允许调用 `renderCallWrapperUnpackExpr(...)`
- 后续新增 wrapper-only helper 时也必须遵守同一顺序：runtime gate 是唯一错误边界，helper 只能是 gate 之后的 materializer

不得通过修改 `renderUnpackFunctionName(...)` 实现 wrapper-only 规则。该 helper 同时服务
`UnpackVariantInsn`、property/index/operator 等全局路径。

## 长期合同

### 1. 语义边界

- 当前 implicit conversion 只属于 frontend ordinary typed boundary 与入站 `call_func` wrapper 的窄兼容例外
- frontend ordinary boundary 由 `FrontendVariantBoundaryCompatibility` 统一判断
- backend/global strict assignability 不放宽
- C backend fixed-argument validation 不接受未物化的 widening
- method/property outward metadata 不改成 `Variant` 来绕过 runtime gate

### 2. Materialization

- 所有 accepted non-direct conversion 都必须显式物化
- `ALLOW_WITH_INTRINSIC_CAST` 不得作为 `ALLOW_DIRECT` 的别名
- frontend ordinary boundary 必须生成对应 `call_intrinsic`
- source slot 不能直接流入 target slot，除非 decision 是真正 direct route

### 3. Container 与 subscript

- 不支持递归 primitive / vector covariance
- typed dictionary key/value 的 widening 是对应 key/value ordinary boundary 的结果
- `Array[T]` / packed array 的 expected index type 是 `int`
- `float` index 仍禁止
- `Variant` index 可通过 ordinary `Variant -> int` boundary 物化后进入 indexed access

### 4. Backend intrinsic

- `CIntrinsicManager` 是 backend-owned intrinsic 白名单
- unknown intrinsic 必须 fail-fast
- 新增 intrinsic 必须定义自己的 result / argument 合同
- destroyable/object result 的 intrinsic 必须单独审计 ownership，不得照抄 scalar cast 的 direct expression route

## 测试锚点

关键 focused tests：

- `FrontendVariantBoundaryCompatibilityTest`
- `FrontendAssignmentSemanticSupportTest`
- `FrontendTypeCheckAnalyzerTest`
- `FrontendExpressionSemanticSupportTest`
- `FrontendConstructorResolutionSupportTest`
- `FrontendSubscriptSemanticSupportTest`
- `ScopeMethodResolverTest`
- `ClassRegistryTest`
- `FrontendBodyLoweringSessionTest`
- `FrontendLoweringBodyInsnPassTest`
- `FrontendWritableRouteSupportTest`
- `SimpleLirBlockInsnParserTest`
- `SimpleLirBlockInsnSerializerTest`
- `CIntrinsicManagerTest`
- `CIntToFloatIntrinsicTest`
- `CVectorIToVectorIntrinsicTest`
- `CallIntrinsicInsnGenTest`
- `CGenHelperTest`
- `CCodegenTest`
- `FrontendLoweringToCProjectBuilderIntegrationTest`

`test_suite` runtime anchors 记录在 `doc/test_suite.md`，覆盖：

- local initializer / assignment / call argument / return
- property initializer / property assignment
- builtin constructor argument
- engine class float property assignment
- typed dictionary key/value roundtrip
- 入站 dynamic-call wrapper 中 `Variant(INT) -> float parameter`
- 入站 dynamic-call wrapper 中 `Variant(Vector*i) -> Vector* parameter`
- 反向 / 错维 / 相邻 Godot strict conversion guard

维护测试时至少保留：

- happy path
- 反方向 conversion 负例
- 相邻 scalar / vector / container / `Variant` conversion 负例
- overload specificity 或 ambiguity 负例
- runtime anchor，如果规则跨越 Godot / GDExtension 边界

## 后续扩展规则

新增或调整隐式转换前，必须先明确规则属于哪条边界：

1. Frontend ordinary typed boundary
   - 更新 `frontend_implicit_conversion_matrix.md`
   - 更新 `FrontendVariantBoundaryCompatibility`
   - 必要时扩展 `Decision` 与 decision rank
   - 更新 lowering materialization 与 tests
2. GDExtension 入站 `call_func` wrapper boundary
   - 更新 `gdcc_c_backend.md` 与 `variant_abi_contract.md`
   - 只通过 `CGenHelper` helper 暴露给模板
   - 保持 metadata、`r_error->expected` 与 ptrcall ABI 的明确合同
3. Backend strict call / assignability boundary
   - 默认不应修改
   - 若确实要放宽，必须单独成文说明风险与 focused backend tests

新增 `ALLOW_WITH_INTRINSIC_CAST` pair 时，还必须：

- 更新 `doc/gdcc_lir_intrinsic.md` catalog
- 增加 parser / serializer focused tests，确认 textual shape 稳定
- 增加 intrinsic implementation tests，覆盖成功路径和坏 result / argument / arity
- 增加 `CallIntrinsicInsnGenTest` 或 registry tests，覆盖 unknown intrinsic 与 operand 解析边界
- 若需要入站 wrapper 兼容，明确 helper 是否只是 gate 后 materializer，不得让 helper 成为第二套错误边界
