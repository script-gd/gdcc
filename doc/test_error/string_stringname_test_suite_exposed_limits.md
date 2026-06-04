# String/StringName Test Suite 暴露问题记录

## 背景

本记录只收纳本轮为 `String <-> StringName` 隐式转换补充 `test_suite` 端到端资源时暴露出的
fixture 与实现限制。长期支持范围仍以 `doc/module_impl/frontend/frontend_string_stringname_implicit_conversion_plan.md`
与 `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md` 为准。

本轮新增 runtime anchors：

- `src/test/test_suite/unit_test/script/initializer/local/string_to_stringname_boundaries.gd`
- `src/test/test_suite/unit_test/script/initializer/local/stringname_to_string_boundaries.gd`
- `src/test/test_suite/unit_test/script/runtime/string_stringname_inbound_dynamic_call.gd`
- `src/test/test_suite/unit_test/script/subscript/string_stringname_dictionary_key_roundtrip.gd`

## 1. typed Dictionary literal 在 compiled source 内仍被 compile-check 阻断

首次编写 `subscript/string_stringname_dictionary_key_roundtrip.gd` 时，compiled source 使用了：

- `var values: Dictionary[StringName, int] = {}`
- `var values: Dictionary[String, int] = {}`

targeted run 在 frontend compile-check 阶段失败，错误为：

- `Dictionary literal is recognized by the frontend but is temporarily blocked in compile mode until lowering support lands`

处理结论：

- 这不是 `String <-> StringName` boundary 的失败，而是既有 Dictionary literal lowering gap。
- 当前 resource 改为由 Godot validation script 构造 typed Dictionary，再传入 compiled method。
- compiled method 只负责验证 key materialization、access route 与 writable writeback。

## 2. typed Dictionary 泛型签名不会进入 generated wrapper helper 名称

第二次 targeted run 暴露出 C backend helper 命名碰撞：同一个 compiled class 内存在多个只在
typed Dictionary 泛型实参上不同、但 arity 与返回类型相同的方法时，generated `call_func` /
`ptrcall` helper 会统一退化成 `Dictionary` 签名，导致 C redefinition。

典型失败形态：

- `call_1_arg_Dictionary_ret_int` redefinition
- `ptrcall_1_arg_Dictionary_ret_int` redefinition
- `gdcc_bind_method_1_arg_Dictionary_ret_int` redefinition

处理结论：

- 这不是 engine integration 合同，也不是 `String <-> StringName` key materialization 的语义失败。
- 当前 `subscript/string_stringname_dictionary_key_roundtrip.gd` 合并为一个唯一签名方法，避免 wrapper
  helper 命名限制遮挡目标行为。
- 若后续要支持同 arity typed Dictionary overload / sibling method helper，应在 backend codegen
  层修复 helper 命名策略，而不是继续扩展 fixture workaround。

## 3. caller-visible mutation 必须显式断言

独立审查指出，subscript resource 只看 callee 返回的汇总值不足以证明 Dictionary mutation 在
`call_func` 边界之后仍对调用方可见。

处理结论：

- validation script 现在除 `summary` 外，还读取 `named_values`、`keyed_values`、`named_payloads`
  与 `keyed_payloads` 的调用后内容。
- 这同时锚定 typed Dictionary 参数 ABI、key materialization 与 writable route writeback 的外部可见效果。

## 4. runtime 负例输出约束保持精确

`runtime/string_stringname_inbound_dynamic_call.gd` 只把 `NodePath -> StringName` 作为相邻负例。
该测试不证明 `NodePath` family 支持任何正向 conversion，也不覆盖 operator / explicit cast。

处理结论：

- validation script 使用精确 `output_contains=Cannot convert argument 2 from NodePath to StringName.`
  而不是宽泛 fallback。
- pass marker 仍在故意失败调用之前打印，后续不可达输出由 `output_not_contains` 约束。
