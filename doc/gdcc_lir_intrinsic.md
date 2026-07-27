# GDCC LIR Intrinsic

> 本文档是 LIR `call_intrinsic` surface 与 backend-owned intrinsic catalog 的事实源。
> 单个 feature 的实现文档可以保留该 feature 的上下文、语义边界与维护规则，但通用
> intrinsic 形态、注册规则和已知 intrinsic 清单应维护在本文档中。

## 文档状态

- 状态：Maintained
- 范围：
  - LIR `call_intrinsic` 指令文本形态
  - backend-owned intrinsic registry 合同
  - 当前已实现 intrinsic catalog
- 关联文档：
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/implicit_conversion_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`

## LIR Surface

`call_intrinsic` 调用一个由 backend 显式注册的 intrinsic function：

```text
$<result_id>? = call_intrinsic "<intrinsic_name>" $<arg1_id> $<arg2_id> ...
```

通用合同：

- intrinsic name 必须是字符串操作数。
- argument 必须是变量操作数，不接受 literal。
- result 是否允许为空由具体 intrinsic 自己定义。
- parser / serializer 只负责保留 textual shape，不负责校验 intrinsic 是否存在。
- backend codegen 必须通过 registry 查找 intrinsic；unknown intrinsic 必须 fail-fast。
- 每个 intrinsic 必须定义自己的 result / argument 类型合同。

## Backend Registry

C backend 当前通过以下类承接 intrinsic：

- `CIntrinsicManager`
  - backend-owned intrinsic 白名单。
  - `call_intrinsic` 的 name 是数据，不是任意 C symbol escape hatch。
- `CIntrinsicFunction`
  - narrow codegen interface。
  - 接收 `CallIntrinsicInsnGen` 已解析好的 nullable result slot 与 argument slots。
- `CallIntrinsicInsnGen`
  - 注册 `GdInstruction.CALL_INTRINSIC`。
  - 解析 result 与 variable arguments。
  - unknown intrinsic 与坏 IR 使用 backend invalid-insn 路径报错。

实现规则：

- intrinsic 实现只做自身窄合同校验和 codegen。
- 不要在 intrinsic 中复制通用 slot 查找、operand parsing 或 registry lookup。
- 不要手写目标槽位生命周期逻辑；优先复用 `CBodyBuilder.assignVar(...)`、
  `CBodyBuilder.callAssign(...)` 等统一写入 API。
- destroyable 或 object result 的新 intrinsic 必须单独审计 ownership，不得照抄 scalar
  cast 的 direct expression route。
- 新增 intrinsic 必须同步更新本文档 catalog、parser / serializer focused tests，以及
  backend registry / codegen focused tests。

## Catalog

### `c_int_to_float`

状态：Implemented

形态：

```text
$<float_result> = call_intrinsic "c_int_to_float" $<int_source>;
```

合同：

- result 必须存在。
- result 必须是非 `ref` 的 `float` slot。
- exactly one argument。
- argument 必须是 `int` slot。

C backend 语义：

```c
$target = (godot_float)$source;
```

长期事实源：

- `doc/module_impl/backend/implicit_conversion_implementation.md`

### `c_vector2i_to_vector2`

状态：Implemented

形态：

```text
$<Vector2_result> = call_intrinsic "c_vector2i_to_vector2" $<Vector2i_source>;
```

合同：

- result 必须存在。
- result 必须是非 `ref` 的 `Vector2` slot。
- exactly one argument。
- argument 必须是 `Vector2i` slot。

C backend 语义：

```c
$target = godot_new_Vector2_with_Vector2i(&$source);
```

长期事实源：

- `doc/module_impl/backend/implicit_conversion_implementation.md`

### `c_vector3i_to_vector3`

状态：Implemented

形态：

```text
$<Vector3_result> = call_intrinsic "c_vector3i_to_vector3" $<Vector3i_source>;
```

合同：

- result 必须存在。
- result 必须是非 `ref` 的 `Vector3` slot。
- exactly one argument。
- argument 必须是 `Vector3i` slot。

C backend 语义：

```c
$target = godot_new_Vector3_with_Vector3i(&$source);
```

长期事实源：

- `doc/module_impl/backend/implicit_conversion_implementation.md`

### `c_vector4i_to_vector4`

状态：Implemented

形态：

```text
$<Vector4_result> = call_intrinsic "c_vector4i_to_vector4" $<Vector4i_source>;
```

合同：

- result 必须存在。
- result 必须是非 `ref` 的 `Vector4` slot。
- exactly one argument。
- argument 必须是 `Vector4i` slot。

C backend 语义：

```c
$target = godot_new_Vector4_with_Vector4i(&$source);
```

长期事实源：

- `doc/module_impl/backend/implicit_conversion_implementation.md`

### `gdcc.for_range_iter.init`

状态：Implemented

形态：

```text
$<iter_result> = call_intrinsic "gdcc.for_range_iter.init" $<start> $<end> $<step>;
```

合同：

- result 必须存在。
- result 必须是非 `ref` 的 `compiler::GdccForRangeIter` slot。
- exactly three arguments。
- arguments 必须依次是 `int`、`int`、`int` slot，分别表示已归一化的 `start`、`end`、`step`。

C backend 语义：

```c
$target = gdcc_for_range_iter_from_bounds($start, $end, $step);
```

`step == 0` 策略：

- `gdcc_for_range_iter_from_bounds(...)` 原样保存 `start`、`end` 与 `step`。
- `gdcc_for_range_iter_should_continue(...)` 对 `step == 0` 直接返回 `false`；专用 `for ... in range(...)`
  route 因此零次迭代且不产生诊断，与 Godot 4.5.1 的 optimized range loop 一致。
- `next` 只可在 `should_continue` 已返回 `true` 后调用，因此 zero-step state 不会进入 `next`。

Lifecycle / ownership：

- `compiler::GdccForRangeIter` 是 destroyable non-object value。
- helper 返回按值 iterator state；slot 写入仍通过 `CBodyBuilder.callAssign(...)` 处理旧值 destroy 与 direct struct assignment。
- 不产生 Godot object ownership，也不参与 Variant pack / unpack。

长期事实源：

- `doc/module_impl/frontend/frontend_gdcompiler_type_implementation.md`

### `gdcc.for_range_iter.should_continue`

状态：Implemented

形态：

```text
$<bool_result> = call_intrinsic "gdcc.for_range_iter.should_continue" $<iter>;
```

合同：

- result 必须存在。
- result 必须是非 `ref` 的 `bool` slot。
- exactly one argument。
- argument 必须是 `compiler::GdccForRangeIter` slot。

C backend 语义：

```c
$target = gdcc_for_range_iter_should_continue(&$iter);
```

边界方向语义：

- 零步长（literal 或 dynamic）直接返回 `false`，产生零次迭代，不产生诊断。
- 正步长使用 `current < end`（end 为排他上界）：若 `start >= end`（含 `start == end`），首次即返回 `false`。
- 负步长使用 `current > end`（end 为排他下界）：若 `start <= end`（含 `start == end`），首次即返回 `false`。
- 因此：正 step 配逆向边界（`start > end`）和负 step 配正向边界（`start < end`）均为零次迭代，
  与 Godot 4.5.1 `OPCODE_ITERATE_BEGIN_RANGE` 的方向兼容性检查一致。

Lifecycle / ownership：

- 只读 iterator state，不销毁或转移 iterator ownership。

长期事实源：

- `doc/module_impl/frontend/frontend_gdcompiler_type_implementation.md`

### `gdcc.for_range_iter.next`

状态：Implemented

形态：

```text
$<next_iter_result> = call_intrinsic "gdcc.for_range_iter.next" $<iter>;
```

合同：

- result 必须存在。
- result 必须是非 `ref` 的 `compiler::GdccForRangeIter` slot。
- exactly one argument。
- argument 必须是 `compiler::GdccForRangeIter` slot。

C backend 语义：

```c
$target = gdcc_for_range_iter_next(&$iter);
```

溢出策略：

- `gdcc_for_range_iter_next(...)` 使用无保护的 `int64_t` 加法（`current + step`），与 Godot 4.5.1
  `gdscript_vm.cpp` 中 `OPCODE_ITERATE_RANGE` 的 `*count += step` 行为完全一致。
- 若加法溢出（wrap past `INT64_MAX` 或 below `INT64_MIN`），`should_continue` 的方向比较可能永远
  不满足终止条件，导致无限循环。这与 Godot 上游行为相同——GDCC 选择兼容性而非引入额外的安全检查。
- 不插入 saturating arithmetic、overflow builtin 或运行时 trap。
- 注意：C 标准中 `int64_t` 有符号溢出为未定义行为；此处依赖主流编译器（GCC/Clang/Zig CC）
  在无 `-ftrapv` 时的 de facto wrapping 行为，与 Godot 上游 C++ 代码的风险假设一致。

Lifecycle / ownership：

- 输入 iterator 只读。
- helper 返回新的按值 iterator state，语义为 `current + step`，保留原 `end` 与 `step`。
- slot 写入仍通过 `CBodyBuilder.callAssign(...)` 处理旧值 destroy 与 direct struct assignment。

长期事实源：

- `doc/module_impl/frontend/frontend_gdcompiler_type_implementation.md`

### `gdcc.for_range_iter.get`

状态：Implemented

形态：

```text
$<int_result> = call_intrinsic "gdcc.for_range_iter.get" $<iter>;
```

合同：

- result 必须存在。
- result 必须是非 `ref` 的 `int` slot。
- exactly one argument。
- argument 必须是 `compiler::GdccForRangeIter` slot。

C backend 语义：

```c
$target = gdcc_for_range_iter_get(&$iter);
```

Lifecycle / ownership：

- 只读 iterator state，不销毁或转移 iterator ownership。
- 返回当前迭代值，不推进 state。

长期事实源：

- `doc/module_impl/frontend/frontend_gdcompiler_type_implementation.md`

---

### `gdcc.for_variant_iter.init`

状态：Implemented

形态（LIR surface）：

```
$<iter_result> = call_intrinsic "gdcc.for_variant_iter.init" $<source>;
```

合同：

- result 必须存在，非 `ref`，slot 类型为 `compiler::GdccForVariantIter`。
- argument 恰好 1 个，类型为 `Variant`（要迭代的源表达式）。

C backend 语义：

```c
$target = gdcc_for_variant_iter_from_variant(&$source);
```

Lifecycle / ownership：

- result 是 destroyable non-object value（`compiler::GdccForVariantIter`），包含两个 `godot_Variant` 字段。
- 不可直接 struct 赋值；需要 `gdcc_for_variant_iter_copy` 深拷贝。
- `callAssign` 路径在写入前自动 destroy 旧值。

---

### `gdcc.for_variant_iter.should_continue`

状态：Implemented

形态（LIR surface）：

```
$<bool_result> = call_intrinsic "gdcc.for_variant_iter.should_continue" $<iter>;
```

合同：

- result 必须存在，非 `ref`，slot 类型为 `bool`。
- argument 恰好 1 个，类型为 `compiler::GdccForVariantIter`。

C backend 语义：

```c
$target = gdcc_for_variant_iter_should_continue(&$iter);
```

边界语义：

- 返回 `valid && has_element`。不可迭代值在 init 时已 print error 并设 `has_element = false`。
- 只读，不销毁或转移 ownership。

---

### `gdcc.for_variant_iter.next`

状态：Implemented

形态（LIR surface）：

```
$<next_iter_result> = call_intrinsic "gdcc.for_variant_iter.next" $<iter>;
```

合同：

- result 必须存在，非 `ref`，slot 类型为 `compiler::GdccForVariantIter`。
- argument 恰好 1 个，类型为 `compiler::GdccForVariantIter`。

C backend 语义：

```c
$target = gdcc_for_variant_iter_next(&$iter);
```

Lifecycle / ownership：

- 输入只读；返回新 state（深拷贝 source + iter 后调用 `variant_iter_next`）。
- lowering 使用 temp-then-commit：先写 distinct next temp，再 `AssignInsn` 回 state slot。

---

### `gdcc.for_variant_iter.get`

状态：Implemented

形态（LIR surface）：

```
$<variant_result> = call_intrinsic "gdcc.for_variant_iter.get" $<iter>;
```

合同：

- result 必须存在，非 `ref`，slot 类型为 `Variant`。
- argument 恰好 1 个，类型为 `compiler::GdccForVariantIter`。

C backend 语义：

```c
$target = gdcc_for_variant_iter_get(&$iter);
```

边界语义：

- 只读 iterator state，不销毁或转移 iterator ownership。
- 返回当前元素的 Variant 拷贝，不推进 state。
- 若 `variant_iter_get` 报告 invalid，print error 并返回 nil Variant。
- lowering 的 `ForLoopGetItem` 经 `materializeFrontendBoundaryValue` 将 `Variant` → `exposedIteratorType`。

长期事实源：

- `doc/module_impl/frontend/frontend_gdcompiler_type_implementation.md`

### `gdcc.for_string_iter.init`

状态：已冻结

LIR 形态：

```
$<iter_result> = call_intrinsic "gdcc.for_string_iter.init" $<source>;
```

合同：

- result 必须存在、非 ref、类型为 `compiler::GdccForStringIter`。
- 恰好 1 个 argument，类型为 `String`。

C backend 语义：

```c
$target = gdcc_for_string_iter_from_string(&$source);
```

边界语义：

- 深拷贝 source String，缓存 length，index 初始化为 0。
- 不可直接 struct 赋值；需要 `gdcc_for_string_iter_copy` 深拷贝。

### `gdcc.for_string_iter.should_continue`

状态：已冻结

LIR 形态：

```
$<bool_result> = call_intrinsic "gdcc.for_string_iter.should_continue" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `bool`。
- 恰好 1 个 argument，类型为 `compiler::GdccForStringIter`。

C backend 语义：

```c
$target = gdcc_for_string_iter_should_continue(&$iter);
```

边界语义：

- `index < length` 时返回 true。

### `gdcc.for_string_iter.next`

状态：已冻结

LIR 形态：

```
$<next_iter_result> = call_intrinsic "gdcc.for_string_iter.next" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `compiler::GdccForStringIter`。
- 恰好 1 个 argument，类型为 `compiler::GdccForStringIter`。

C backend 语义：

```c
$target = gdcc_for_string_iter_next(&$iter);
```

边界语义：

- 深拷贝 source String，index + 1，length 不变。返回新 state 值。

### `gdcc.for_string_iter.get`

状态：已冻结

LIR 形态：

```
$<string_result> = call_intrinsic "gdcc.for_string_iter.get" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `String`。
- 恰好 1 个 argument，类型为 `compiler::GdccForStringIter`。

C backend 语义：

```c
$target = gdcc_for_string_iter_get(&$iter);
```

边界语义：

- 返回 `godot_String_substr(&source, index, 1)`，即单字符 String，匹配 Godot `Variant::iter_get` 语义。

### `gdcc.for_array_iter.init`

状态：已冻结

LIR 形态：

```
$<iter_result> = call_intrinsic "gdcc.for_array_iter.init" $<source>;
```

合同：

- result 必须存在、非 ref、类型为 `compiler::GdccForArrayIter`。
- 恰好 1 个 argument，类型为任何 `GdArrayType`（接受 typed 和 untyped Array）。

C backend 语义：

```c
$target = gdcc_for_array_iter_from_array(&$source);
```

边界语义：

- 共享 source Array 句柄（引用语义，refcount +1），缓存 size，index 初始化为 0。
- 不可直接 struct 赋值；需要 `gdcc_for_array_iter_copy` 深拷贝句柄。

### `gdcc.for_array_iter.should_continue`

状态：已冻结

LIR 形态：

```
$<bool_result> = call_intrinsic "gdcc.for_array_iter.should_continue" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `bool`。
- 恰好 1 个 argument，类型为 `compiler::GdccForArrayIter`。

C backend 语义：

```c
$target = gdcc_for_array_iter_should_continue(&$iter);
```

### `gdcc.for_array_iter.next`

状态：已冻结

LIR 形态：

```
$<next_iter_result> = call_intrinsic "gdcc.for_array_iter.next" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `compiler::GdccForArrayIter`。
- 恰好 1 个 argument，类型为 `compiler::GdccForArrayIter`。

C backend 语义：

```c
$target = gdcc_for_array_iter_next(&$iter);
```

### `gdcc.for_array_iter.get`

状态：已冻结

LIR 形态：

```
$<variant_result> = call_intrinsic "gdcc.for_array_iter.get" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `Variant`。
- 恰好 1 个 argument，类型为 `compiler::GdccForArrayIter`。

C backend 语义：

```c
$target = gdcc_for_array_iter_get(&$iter);
```

边界语义：

- 每次 get 用 `godot_array_operator_index_const(&source, index)` 取元素地址并 `godot_new_Variant_with_Variant` 返回 owned 拷贝（避免 `godot_Array_get` 方法分发；不缓存裸基址，因 Array 为引用语义）。
- lowering 的 `ForLoopGetItem` 经 `materializeFrontendBoundaryValue` 将 `Variant` → `exposedIteratorType`。

### `gdcc.for_dictionary_iter.init`

状态：已冻结

LIR 形态：

```
$<iter_result> = call_intrinsic "gdcc.for_dictionary_iter.init" $<source>;
```

合同：

- result 必须存在、非 ref、类型为 `compiler::GdccForDictionaryIter`。
- 恰好 1 个 argument，类型为任何 `GdDictionaryType`（接受 typed 和 untyped Dictionary）。

C backend 语义：

```c
$target = gdcc_for_dictionary_iter_from_dictionary(&$source);
```

边界语义：

- 调用 `godot_Dictionary_keys(source)` 将 keys 快照到堆分配 box（`gdcc_for_dictionary_iter_box`，非原子 refcount=1），缓存 size/index，并缓存 keys 连续 `Variant` 基址指针。
- Godot Dictionary 迭代返回 key，不是 value。
- 不可直接 struct 赋值；需要 `gdcc_for_dictionary_iter_copy` 共享 box（refcount+1）。for-iter 变量不跨线程。

### `gdcc.for_dictionary_iter.should_continue`

状态：已冻结

LIR 形态：

```
$<bool_result> = call_intrinsic "gdcc.for_dictionary_iter.should_continue" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `bool`。
- 恰好 1 个 argument，类型为 `compiler::GdccForDictionaryIter`。

C backend 语义：

```c
$target = gdcc_for_dictionary_iter_should_continue(&$iter);
```

### `gdcc.for_dictionary_iter.next`

状态：已冻结

LIR 形态：

```
$<next_iter_result> = call_intrinsic "gdcc.for_dictionary_iter.next" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `compiler::GdccForDictionaryIter`。
- 恰好 1 个 argument，类型为 `compiler::GdccForDictionaryIter`。

C backend 语义：

```c
$target = gdcc_for_dictionary_iter_next(&$iter);
```

### `gdcc.for_dictionary_iter.get`

状态：已冻结

LIR 形态：

```
$<variant_result> = call_intrinsic "gdcc.for_dictionary_iter.get" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `Variant`。
- 恰好 1 个 argument，类型为 `compiler::GdccForDictionaryIter`。

C backend 语义：

```c
$target = gdcc_for_dictionary_iter_get(&$iter);
```

边界语义：

- 通过缓存的 `Variant*` 基址指针做 `ptr[index]`，再返回 owned Variant key 拷贝（不再每元素 `godot_Array_get`）。
- lowering 的 `ForLoopGetItem` 经 `materializeFrontendBoundaryValue` 将 `Variant` → `exposedIteratorType`。

### `gdcc.for_packed_<family>_iter.*`（Packed*Array 总则）

状态：已冻结（per-family 专有 state/intrinsic）

命名模板（`<family>` 为 snake_case family slug）：

| Packed* 源类型 | family slug | state LIR 类型 |
|---|---|---|
| PackedByteArray | `byte_array` | `compiler::GdccForPackedByteArrayIter` |
| PackedInt32Array | `int32_array` | `compiler::GdccForPackedInt32ArrayIter` |
| PackedInt64Array | `int64_array` | `compiler::GdccForPackedInt64ArrayIter` |
| PackedFloat32Array | `float32_array` | `compiler::GdccForPackedFloat32ArrayIter` |
| PackedFloat64Array | `float64_array` | `compiler::GdccForPackedFloat64ArrayIter` |
| PackedStringArray | `string_array` | `compiler::GdccForPackedStringArrayIter` |
| PackedVector2Array | `vector2_array` | `compiler::GdccForPackedVector2ArrayIter` |
| PackedVector3Array | `vector3_array` | `compiler::GdccForPackedVector3ArrayIter` |
| PackedVector4Array | `vector4_array` | `compiler::GdccForPackedVector4ArrayIter` |
| PackedColorArray | `color_array` | `compiler::GdccForPackedColorArrayIter` |

每个 family 注册独立的 4 个 intrinsic（**不**再共享单一 `gdcc.for_packed_array_iter.*`）：

```
$<iter_result> = call_intrinsic "gdcc.for_packed_<family>_iter.init" $<source>;
$<bool_result> = call_intrinsic "gdcc.for_packed_<family>_iter.should_continue" $<iter>;
$<next_iter_result> = call_intrinsic "gdcc.for_packed_<family>_iter.next" $<iter>;
$<element_result> = call_intrinsic "gdcc.for_packed_<family>_iter.get" $<iter>;
```

合同（对每个 family 统一）：

- `init`：result 为对应 `compiler::GdccForPacked*Iter`；arg0 为对应具体 `Packed*Array` 源类型（不再用 Variant wildcard）。
- `should_continue`：result `bool`；arg0 为对应 state。
- `next`：result/arg0 均为对应 state。
- `get`：result 为 **typed element**（`int` / `float` / `String` / `Vector*` / `Color`），不是 `Variant`；arg0 为对应 state。
- state **不可**直接 struct 赋值；`copy` helper 为 `gdcc_for_packed_<family>_iter_copy`（COW 句柄 + 共享 typed base pointer）。

C backend 语义：

```c
$target = gdcc_for_packed_<family>_iter_from(&$source);
$target = gdcc_for_packed_<family>_iter_should_continue(&$iter);
$target = gdcc_for_packed_<family>_iter_next(&$iter);
$target = gdcc_for_packed_<family>_iter_get(&$iter);
```

边界语义：

- `from` 深拷贝 typed Packed*Array（COW），缓存 size/index，并缓存 typed 元素基址指针；snapshot 只读，故缓存基址安全。
- `get` 对 typed 基址做指针算术并返回 **typed element**（无 kind switch，无 per-element Variant 装箱）。
- lowering 的 `ForLoopGetItem` 在 element 与 `exposedIteratorType` 兼容时可直接赋值，通常无需 `UnpackVariant`。

### `gdcc.for_float_iter.init`

状态：已冻结

LIR 形态：

```
$<iter_result> = call_intrinsic "gdcc.for_float_iter.init" $<end>;
```

合同：

- result 必须存在、非 ref、类型为 `compiler::GdccForFloatIter`。
- 恰好 1 个 argument，类型为 `float`。

C backend 语义：

```c
$target = gdcc_for_float_iter_from_end($end);
```

边界语义：

- 匹配 Godot `Variant::iter_init` FLOAT：`current = 0.0`，`end = n`。
- POD 状态，允许 direct struct assignment。

### `gdcc.for_float_iter.should_continue`

状态：已冻结

LIR 形态：

```
$<bool_result> = call_intrinsic "gdcc.for_float_iter.should_continue" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `bool`。
- 恰好 1 个 argument，类型为 `compiler::GdccForFloatIter`。

C backend 语义：

```c
$target = gdcc_for_float_iter_should_continue(&$iter);
```

边界语义：

- 返回 `current < end`。因此 `n <= 0.0` 零次迭代；`3.5` 产生 `0.0, 1.0, 2.0, 3.0`。

### `gdcc.for_float_iter.next`

状态：已冻结

LIR 形态：

```
$<next_iter_result> = call_intrinsic "gdcc.for_float_iter.next" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `compiler::GdccForFloatIter`。
- 恰好 1 个 argument，类型为 `compiler::GdccForFloatIter`。

C backend 语义：

```c
$target = gdcc_for_float_iter_next(&$iter);
```

边界语义：

- `current = current + 1.0`，匹配 Godot `Variant::iter_next` FLOAT。

### `gdcc.for_float_iter.get`

状态：已冻结

LIR 形态：

```
$<float_result> = call_intrinsic "gdcc.for_float_iter.get" $<iter>;
```

合同：

- result 必须存在、非 ref、类型为 `float`。
- 恰好 1 个 argument，类型为 `compiler::GdccForFloatIter`。

C backend 语义：

```c
$target = gdcc_for_float_iter_get(&$iter);
```

边界语义：

- 返回当前 float 计数器；exposed iterator type 已是 `float` 时无需 Variant unpack。

## 新增 Intrinsic Checklist

新增 intrinsic 时按以下顺序维护：

1. 更新本文档的 catalog，记录 intrinsic 名称、状态、LIR 形态、result / argument 合同和 C backend 语义。
2. 在 feature-specific 文档中只保留该 feature 的当前语义边界、实现合同、测试锚点与维护规则，并链接回本文档。
3. 更新 lowering materialization，确保 accepted non-direct boundary 显式生成对应 `CallIntrinsicInsn`。
4. 更新 `CIntrinsicManager` registry。
5. 增加 parser / serializer focused tests，确认 textual shape 稳定。
6. 增加 intrinsic implementation tests，覆盖成功路径和坏 result / argument / arity。
7. 增加 `CallIntrinsicInsnGenTest` 或 registry tests，覆盖 unknown intrinsic 与 operand 解析边界。
