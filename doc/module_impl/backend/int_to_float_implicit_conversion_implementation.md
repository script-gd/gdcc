# Int To Float 隐式转换实现说明

> 本文档作为 `int -> float` 隐式转换实现的长期事实源。  
> 只保留当前已经落地的语义边界、实现合同、测试锚点与后续维护规则，不记录阶段性实施流水账。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - frontend ordinary typed boundary
  - frontend lowering materialization
  - LIR `call_intrinsic` surface
  - C backend intrinsic codegen
  - GDExtension 入站 `call_func` wrapper 的窄兼容规则
- 更新时间：2026-05-12
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

## 当前最终状态

### 支持范围

GDCC 现在支持 source-level ordinary typed boundary 上的 `int -> float` 隐式转换。典型入口包括：

- local initializer：`var x: float = 1`
- assignment：`x: float; x = 1`
- return：`func f() -> float: return 1`
- fixed call argument：`take_float(1)`
- vararg argument 中目标 slot 为 `float` 的 boundary
- builtin constructor 的 float-component 参数，例如 `Vector3(1, 2.5, 3)`
- typed subscript key/index boundary：
  - `Dictionary[float, V]` key 接收 `int`
  - `Array[T]` / packed array 使用 `Variant` index 时先 ordinary-unpack 到 `int`

该规则必须通过 shared frontend boundary helper 统一生效。assignment、call、return、constructor、subscript 等 consumer 不得各自硬编码局部 `int -> float` 表。

### 明确不支持

以下转换继续禁止：

- `float -> int`
- `bool -> int`、`bool -> float`、`int -> bool`、`float -> bool`
- `String <-> StringName`、`String -> int`、`String -> float`
- unary / binary operator numeric promotion，例如 `int + float`
- `Array[int] -> Array[float]`
- `Dictionary[int, V] -> Dictionary[float, V]`
- `Dictionary[K, int] -> Dictionary[K, float]`
- `Array` / packed array 的 `float` index 自动转 `int`

`ClassRegistry.checkAssignable(...)` 仍是 strict assignability 真源，不承载 numeric promotion。

## 实现落点

### Frontend semantic gate

- 入口：`FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)`
- `int -> float` 返回：

```java
ALLOW_WITH_INTRINSIC_CAST
```

该 decision 表示 source-level boundary 合法，但 source slot 不能直接流入 target slot。lowering 必须显式生成 target-typed temp。
本文档只维护 `int -> float` 这一成员；其它 intrinsic materialization 的语义由对应 feature 文档与
`doc/gdcc_lir_intrinsic.md` 维护。

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

### Frontend lowering materialization

- 入口：`FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`
- `ALLOW_WITH_INTRINSIC_CAST` 在本文档范围内只描述 `GdIntType.INT -> GdFloatType.FLOAT`
- lowering 生成新的 `float` temp，并追加：

```text
$<float_temp> = call_intrinsic "c_int_to_float" $<int_source>;
```

后续 consumer 必须消费该 `float` temp，而不是继续读取原始 `int` source slot。

Subscript key/index lowering 也必须走同一 materialization 路径：

- `Dictionary[float, V]` 的 `int` key 先 cast 到 `float` temp，再发 keyed access
- `Array[T]` / packed array 的 `Variant` index 先 unpack 到 `int` temp，再发 indexed access
- access-kind selection 必须基于 materialized key/index type
- writable-route reverse commit 必须复用同一 frozen access chain 内已经 materialized 的 key/index slot

### LIR surface

`CALL_INTRINSIC` 的 simple 文本形态保持：

```text
$<result_id> = call_intrinsic "<intrinsic_name>" $<arg1_id> ...
```

`c_int_to_float` 的合法形态固定为：

```text
$<float_result> = call_intrinsic "c_int_to_float" $<int_source>;
```

合同：

- result 必须存在
- result 必须是非 `ref` 的 `float` slot
- exactly one argument
- argument 必须是 `int` slot
- intrinsic argument 必须是变量操作数，不接受 literal
- unknown intrinsic 必须 fail-fast

### C backend intrinsic codegen

实现落点：

- `CIntrinsicManager`
  - backend-owned intrinsic 白名单
  - 当前注册 `c_int_to_float`
- `CIntrinsicFunction`
  - narrow intrinsic codegen interface
  - 接收已解析的 nullable result slot 与 argument slots
- `CIntToFloatIntrinsic`
  - 校验 result / argument 合同
  - 通过 `CBodyBuilder.valueOfCastedVar(source, GdFloatType.FLOAT)` 与 `assignVar(...)` 发射
- `CallIntrinsicInsnGen`
  - 注册 `GdInstruction.CALL_INTRINSIC`
  - 负责解析 result 与 variable arguments
  - unknown intrinsic 与坏 IR 使用 backend invalid-insn 路径报错

生成的 C 代码语义等价于：

```c
$target = (godot_float)$source;
```

不要在 intrinsic 实现中手写目标槽位写入或生命周期逻辑；必须继续复用 `CBodyBuilder` API。

## 入站 `call_func` wrapper 兼容规则

GDExtension 入站 `call_func` wrapper 是独立边界。它处理的是 Godot 通过 `Object.call(...)` 等 Variant-call surface 调用 GDCC 生成方法的路径，不经过 frontend lowering，因此不会生成 `call_intrinsic "c_int_to_float"`。

当前 wrapper 规则：

- 非 `Variant` 参数默认保持 exact `GDExtensionVariantType` gate
- 唯一例外是 `float` 参数额外接受 `Variant(INT)`
- `Variant(INT)` payload 在 wrapper-local materialization 中显式 cast 为 `godot_float`
- `float` 参数 metadata 仍发布 `GDEXTENSION_VARIANT_TYPE_FLOAT`
- `r_error->expected` 仍保持 `GDEXTENSION_VARIANT_TYPE_FLOAT`
- `int` 参数不接受 `Variant(FLOAT)`
- `float` 参数不接受 `Variant(BOOL)`
- ptrcall ABI 不变
- ordinary `Variant -> float` unpack 不变

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

不得通过修改 `renderUnpackFunctionName(...)` 实现 wrapper-only 规则。该 helper 同时服务 `UnpackVariantInsn`、property/index/operator 等全局路径。

## 长期合同

### 1. 语义边界

- `int -> float` 只属于 frontend ordinary typed boundary 与入站 `call_func` wrapper 的窄兼容例外
- frontend ordinary boundary 由 `FrontendVariantBoundaryCompatibility` 统一判断
- backend/global strict assignability 不放宽
- C backend fixed-argument validation 不接受未物化的 numeric widening
- method/property outward metadata 不改成 `Variant` 来绕过 runtime gate

### 2. Materialization

- 所有 accepted non-direct conversion 都必须显式物化
- `ALLOW_WITH_INTRINSIC_CAST` 不得作为 `ALLOW_DIRECT` 的别名
- `int -> float` ordinary boundary 必须生成 `call_intrinsic "c_int_to_float"`
- source slot 不能直接流入 target slot，除非 decision 是真正 direct route

### 3. Overload specificity

- callable overload resolution 必须使用 shared decision rank
- `take(1)` 在 `take(int)` 与 `take(float)` 同时存在时选择 `take(int)`
- `take(1)` 在 `take(float)` 与 `take(Variant)` 同时存在时选择 `take(float)`
- 多个非支配候选仍保持 ambiguous

### 4. Container 与 subscript

- 不支持递归 primitive covariance
- `Dictionary[float, V]` 接收 `int` key 是 ordinary key boundary 的结果
- `Array[T]` / packed array 的 expected index type 是 `int`
- `float` index 仍禁止
- `Variant` index 可通过 ordinary `Variant -> int` boundary 物化后进入 indexed access

### 5. Backend intrinsic

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
- `CallIntrinsicInsnGenTest`
- `CGenHelperTest`
- `CCodegenTest`

`test_suite` runtime anchors 记录在 `doc/test_suite.md`，覆盖：

- local initializer / assignment / call argument / return
- property initializer / property assignment
- builtin constructor float-component argument
- engine class float property assignment
- `Dictionary[float, V]` key roundtrip
- 入站 dynamic-call wrapper 中 `Variant(INT) -> float parameter`

维护测试时至少保留：

- happy path
- 反方向 conversion 负例
- 相邻 scalar / container / `Variant` conversion 负例
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

不要只因为 Godot `Variant::can_convert_strict(...)` 支持某个 pair，就把它同时放进 frontend、backend、wrapper 和 metadata。
