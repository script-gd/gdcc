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

- `gdcc_for_range_iter_from_bounds(...)` 显式调用 runtime error helper（`godot_print_error(...)`）并返回不可无限循环的 fallback state。
- 前端未来 lowering 仍应避免生成零步长；手写 LIR 不会静默获得无限循环语义。

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

Lifecycle / ownership：

- 只读 iterator state，不销毁或转移 iterator ownership。
- 正步长使用 `current < end`，负步长使用 `current > end`，end 为排他边界。

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

## 新增 Intrinsic Checklist

新增 intrinsic 时按以下顺序维护：

1. 更新本文档的 catalog，记录 intrinsic 名称、状态、LIR 形态、result / argument 合同和 C backend 语义。
2. 在 feature-specific 文档中只保留该 feature 的当前语义边界、实现合同、测试锚点与维护规则，并链接回本文档。
3. 更新 lowering materialization，确保 accepted non-direct boundary 显式生成对应 `CallIntrinsicInsn`。
4. 更新 `CIntrinsicManager` registry。
5. 增加 parser / serializer focused tests，确认 textual shape 稳定。
6. 增加 intrinsic implementation tests，覆盖成功路径和坏 result / argument / arity。
7. 增加 `CallIntrinsicInsnGenTest` 或 registry tests，覆盖 unknown intrinsic 与 operand 解析边界。
