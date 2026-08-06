# Frontend `as`（CastExpression）实施计划

> 本文档是 GDScript `as` 显式转换在 GDCC 中的实施计划。计划覆盖 parser 现状确认、shared semantic、type-check、CFG/body lowering、LIR、C backend、runtime helper、ownership、diagnostic、compile-only gate 与 test-suite。实施完成后，应将稳定合同整理为 `frontend_cast_expression_implementation.md`，并删除本计划中的阶段状态与任务清单。

## 文档状态

- 状态：Phase 0 已完成；Phase 1+ 尚未实施
- 调研基线：2026-08-05
- Phase 0 完成：2026-08-06
- Godot 对齐基线：`godotengine/godot` tag `4.7.1-stable`
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/main/c/codegen/**`
  - 对应 frontend、LIR、backend 与 test-suite 测试
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_is_type_test_implementation.md`
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/module_impl/backend/backend_ownership_lifecycle_contract.md`
  - `doc/module_impl/backend/object_value_fat_pointer_implementation.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/module_impl/backend/typed_array_abi_contract.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`

## 1. 目标与非目标

### 1.1 目标

实施以下源码形态：

```gdscript
value as TargetType
```

完成后的稳定数据流应为：

```text
CastExpression
  -> shared semantic: resolve value + target type, publish RESOLVED(target type)
  -> type-check: validate explicit-cast compatibility and unsafe runtime cast warning
  -> compile-only final gate
  -> CastItem
  -> frontend body lowering:
       identity / assign
       pack_variant
       builtin_cast
       object_cast
  -> C backend:
       Variant construction for builtin targets
       ClassDB/runtime-name object check for object targets
  -> typed result slot
```

最终支持面包括：

- `as Variant` 的恒等/pack 语义。
- builtin、packed array、裸容器和参数化 `Array[T]` / `Dictionary[K, V]` 的 Godot-compatible 显式转换。
- Engine Object，以及已通过当前 runtime registration 暴露 canonical class/inheritance identity 的 GDCC Object 的同型、upcast 和 runtime downcast。
- `Variant` / `Nil` 到 Object 目标的 runtime cast；失败返回 canonical null。
- `Variant` 到 builtin 目标的 unsafe runtime cast；失败进入稳定 runtime error 与函数 cleanup。
- cast 结果参与 local initializer、assignment、return、call argument、condition normalization、member chain 与 subscript/call 后续消费。
- parser 已支持的低优先级与左结合行为通过 GDCC characterization test 冻结。

### 1.2 明确非目标

- 不修改 `gdparser` 语法或 AST；当前依赖已提供 `CastExpression(Expression value, TypeRef targetType, Range range)`。
- 不把 `as` 规则加入 `frontend_implicit_conversion_matrix.md`。该文档只覆盖 ordinary typed-boundary implicit conversion，`as` 必须拥有独立的显式转换分类真源。
- 不把 `as` lowering 展开为任意组合的 constructor resolution、method overload resolution 或 implicit boundary materialization。
- 不支持 `as void`、`as null`、未知类型名、malformed structured type text 或 compiler-only 类型。
- 不新增独立 HIR pass。
- 不支持依赖 GDScript script-instance inheritance metadata 的 external/path-based script resource、autoload script type、global-script-class 等当前 scope/runtime 尚未正式支持的类型来源；若 GDCC 生成类未在 runtime registration 中暴露可查询的继承 identity，也必须保持 unsupported，而不是静默按 ClassDB 猜测。
- 参数化容器 cast 对齐 Godot 的 base-builtin runtime 行为；cast 本身不验证、不补写也不转换 typed metadata，详见 §5.5。

## 2. 调研结论

### 2.1 当前 GDCC 现状

当前代码已经具备部分骨架，但完整编译链路仍被明确封口：

- `FrontendExpressionSemanticSupport.resolveRemainingExplicitExpressionType(...)` 将 `CastExpression` 发布为 `DEFERRED`。
- `FrontendCompileCheckAnalyzer` 对 `CastExpression` 发出显式 `sema.compile_check` blocker。
- `FrontendCfgGraphBuilder.buildCastValue(...)` 已按“先 operand、后单一 result item”发布 `CastItem`。
- `FrontendBodyLoweringSupport` 已把 `CastItem` 定义为 target-typed `TEMP_SLOT` producer。
- `FrontendCastInsnLoweringProcessor` 当前只 fail-fast：`cast lowering is not implemented yet`。
- `ObjectCastInsn`、`GdInstruction.OBJECT_CAST` 和 LIR parser/serializer 已存在。
- `doc/gdcc_low_ir.md` 已规定 `object_cast` 失败返回 null。
- `CCodegen` 尚未注册 `OBJECT_CAST` generator，当前 backend 无法消费该指令。
- 现有 `UnpackVariantInsn` 的 Object helper 只做 Variant-to-fat-pointer 表示读取，不验证目标 class，不能代替 runtime downcast。
- 现有 `construct_builtin` 面向精确 builtin constructor metadata，不等价于 Godot `Variant::construct` 的 `as` 宽松转换语义。

### 2.2 Godot 4.7.1 行为基线

上游实现锚点：

- parser/token/precedence：
  - `modules/gdscript/gdscript_tokenizer.cpp`
  - `modules/gdscript/gdscript_parser.h`
  - `modules/gdscript/gdscript_parser.cpp`
- analyzer：`GDScriptAnalyzer::reduce_cast(...)`，位于 `modules/gdscript/gdscript_analyzer.cpp`
- compiler/bytecode：
  - `modules/gdscript/gdscript_compiler.cpp`
  - `modules/gdscript/gdscript_byte_codegen.cpp`
- VM：`modules/gdscript/gdscript_vm.cpp`
- builtin conversion：`core/variant/variant.cpp`、`core/variant/variant_construct.cpp`
- 官方文档：`godotengine/godot-docs` 4.7 分支 `tutorials/scripting/gdscript/gdscript_basics.rst`

冻结的上游语义：

- `as` 在 Godot 中是低优先级 infix operator，仅高于 assignment；所有 binary operator 左结合。当前 GDCC 依赖 gdparser 0.5.2 的实际优先级见 Phase 0 验收说明。
- RHS 是 type specifier，不是普通 expression。
- `value as T` 的静态结果类型是 hard `T`；GDScript 类型系统不额外表达 nullable。
- target 为 `Variant` 时直接透传，不发 cast opcode。
- hard builtin 转换使用 `Variant::can_convert(...)`，不是 `can_convert_strict(...)`。
- hard Object 类型只允许同一继承链上的双向转换；upcast 成功，downcast 运行时决定。
- Object/native/script cast 失败返回 `null`。
- builtin runtime cast 失败产生 runtime error。
- `Variant` 或非 hard operand 的 cast 是 unsafe runtime cast；Godot 对应 `UNSAFE_CAST` warning 默认可忽略。
- builtin、native Object 和 script Object 使用不同 VM opcode；`is` 与 `as` 不共享一条 opcode，只共享 type resolution 和继承关系辅助逻辑。

### 2.3 代表性 parity 用例

- `1 as Variant`：值保持 `1`。
- `1 as float`：结果为 `1.0`。
- `5 as bool`：结果为 `true`。
- `"123" as int`：结果为 `123`。
- `Vector2() as int`：静态 hard 类型下 compile error。
- `variant_value as int`：允许，发 unsafe warning，runtime 转换。
- `Node.new() as Node`：保持同一对象。
- `Node.new() as Node2D`：失败结果为 `null`。
- `null as Node`：结果为 `null`。
- `Child.new() as Parent`：保持同一对象。
- `Parent.new() as Child`：runtime 成功或 `null`。
- `a + b as float`：Godot 4.7.1 解析为 `(a + b) as float`；当前 gdparser 0.5.2 解析为 `a + (b as float)`（Phase 0 以依赖实际 AST 为准，可用 `(a + b) as float` 恢复 Godot 形状）。
- `x as int as float`：解析为 `(x as int) as float`。

## 3. 总体设计决策

### 3.1 不新增 `castTargets` side-table

`TypeTestExpression` 的结果恒为 `bool`，因此必须另行发布 RHS target；`CastExpression` 的结果类型本身就是 RHS target。实施时应直接发布：

```text
expressionTypes()[castExpression] = RESOLVED(targetType)
```

lowering 通过 `requireExpressionType(castExpression)` 读取 target type，通过 operand value id 读取 source type。不得新增与结果类型重复的 `FrontendCastTarget`、patch table 或 overlay fact。

这样可以避免：

- `FrontendAnalysisData` / `FrontendExprTypePatch` / `FrontendTypedLexicalEnvironment` 的冗余扩展。
- target fact 与 expression result type 不一致的双真源风险。
- 不必要的 published-fact conflict 与 compiler-only guard 分支。

### 3.2 新增统一显式转换分类器

新增 frontend/backend 可共享的纯语义分类器，建议命名：

```text
gd.script.gdcc.util.type.ExplicitCastSupport
```

建议返回稳定 decision：

```text
IDENTITY
PACK_TO_VARIANT
BUILTIN_RUNTIME_CAST
OBJECT_UPCAST
OBJECT_RUNTIME_CAST
INVALID
```

分类输入至少包括：

- `ClassRegistry`
- source `GdType`
- target `GdType`

是否发 unsafe warning 由 source 是否为 `Variant` / `DYNAMIC` 独立决定，不与 lowering decision 混合。

分类器是以下边界的唯一真源：

- static hard cast 是否允许。
- frontend type-check 的 error/warning 决策。
- body lowering 的 LIR 路由。
- backend 对手写/遗留 LIR 的防御性复核。

不得复用 `FrontendVariantBoundaryCompatibility` 或 `ClassRegistry.checkAssignable(...)` 作为完整 `as` 规则：前者只覆盖 implicit boundary，后者只覆盖 assignment-compatible type relation。

### 3.3 新增 `builtin_cast` LIR

新增统一 builtin cast 指令：

```text
$<result_id:target_type> = builtin_cast "<target_type_name>" $<value_id>
```

建议模型：

```text
BuiltinCastInsn(resultId, targetTypeName, valueId)
```

固定合同：

- result required。
- target 必须解析为非 Object、非 Variant、非 Nil、非 compiler-only 的 runtime builtin 类型；`Array[T]` / `Dictionary[K, V]` 保留完整 target text，但 runtime construct 使用 base ARRAY/DICTIONARY enum。
- source 保持 ordinary typed value；backend 统一按需 pack 成 temporary Variant。
- backend 调用 `godot_variant_construct(...)`，检查 `GDExtensionCallError`，成功后 unpack 到 target-typed result slot。
- target `Variant` 不生成 `builtin_cast`，使用 identity 或 `PackVariantInsn`。
- exact same type 不生成 `builtin_cast`，使用 `AssignInsn`。
- `builtin_cast` 不承担 Object class check。

不能用 `construct_builtin` 代替：`construct_builtin` 使用 exact constructor metadata，而 `as` 使用 Godot `Variant::can_convert` / `Variant::construct` 的显式转换表，尤其需要覆盖 `Variant` operand 和宽松 builtin pair。

### 3.4 完善 `object_cast` LIR

保持现有文本 opcode：

```text
$<result_id:TargetObject> = object_cast "<class_name>" $<value_id>
```

实施时将 Java 模型字段 `objectId` 重命名为 `valueId`，并把公共合同明确为：

- target class name 必须是 canonical / Godot-facing runtime name。
- result type 必须是与 class name 一致的 `GdObjectType`。
- source 允许 `GdObjectType`、`GdVariantType` 或 `GdNilType`。
- non-object Variant payload、null、freed object、class mismatch 均返回 canonical null。
- cast success 返回同一实例的 target-typed fat pointer；不实例化、不克隆、不改变 ownership。表示转换必须保留 source value 的 ownership category（OWNED 保持 OWNED，BORROWED 保持 BORROWED）；失败结果是 canonical null，不得把整个指令硬编码为 BORROWED producer。
- source 静态证明同型/upcast 时 frontend 优先发 `AssignInsn`，但 backend 对手写 `object_cast` 仍需正确处理。
- `resultId == null` 保持现有 optional-return LIR 兼容：generator 完成 target/source/type-text 校验后作为无副作用 no-op，不执行 runtime cast。
- `CBodyBuilder.valueOfCastedVar(...)` 只允许 backend 已证明 assignable 的 layout/representation upcast；它不执行 runtime class check，也不提供 mismatch -> null 语义，禁止用它替代 `ObjectCastInsnGen`。

### 3.5 compile-only gate 最后解除

`FrontendCompileCheckAnalyzer` 中的 Cast blocker 必须保留到以下条件全部满足：

1. shared semantic 已发布 stable target result type。
2. static invalid cast 与 unsafe cast diagnostic 已落地。
3. `builtin_cast` LIR、parser/serializer 和 backend generator 已落地。
4. `object_cast` backend generator 与 ownership/null/freed 语义已落地。
5. `FrontendCastInsnLoweringProcessor` 已覆盖全部正式支持 decision。
6. frontend、LIR、backend targeted tests 全部通过。
7. 至少一组 builtin 与一组 Object test-suite 端到端用例通过。
8. `frontend_rules.md`、compile-check 文档、diagnostic 文档与 LIR/runtime 文档已同步。

禁止在 Phase 1 仅因 expression type 从 `DEFERRED` 变为 `RESOLVED` 就提前移除 blocker。

## 4. Shared Semantic 与 Diagnostic 合同

### 4.1 RHS 类型解析

在 `FrontendExpressionSemanticSupport` 新增 `resolveCastExpressionType(...)`，解析流程与 type test 共享 declared-type resolver，但使用更严格的 miss policy：

1. 先解析 `castExpression.value()`；不稳定 dependency 继续 propagated，不由 cast root 重复发错。
2. trim `targetType.sourceText()`。
3. 空文本、`null`、`void` 直接 `FAILED`。
4. scope 选择顺序：cast root scope -> value scope -> class registry root。
5. 调用 `FrontendModuleSkeleton.tryResolveSourceFacingDeclaredType(...)`。
6. structured type text 解析失败时直接 `FAILED`。
7. 合法但未知的 bare identifier 也直接 `FAILED`；不得像 `is` 一样降级为 unresolved Object runtime target。
8. compiler-only target 由 published type guard fail-fast；正常 source error 不得构造 compiler-only result。
9. 成功时发布 `RESOLVED(targetType)`。

### 4.2 类型兼容性与诊断 owner

诊断划分固定为：

- target type 为空、非法、未知或不支持：expr analyzer，`sema.expression_resolution` / error。
- source dependency `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`：沿用 upstream owner，不在 cast root 重复发错。
- hard source 与 target 静态确定不可转换：type-check analyzer，`sema.type_check` / error。
- source 为 `Variant` 或 expression status 为 `DYNAMIC`，target 非 `Variant`：expr analyzer 发布 `sema.unsafe_cast` / warning；warning 与 `RESOLVED(targetType)` 并存，不阻断 compile。
- lowering/backend 若收到已被 semantic 禁止的组合：fail-fast guard rail，不转换为普通 source diagnostic。

`sema.unsafe_cast` 需同步登记到 `diagnostic_manager.md`，owner 与 `sema.unsafe_call_argument` 一样属于 shared expr publication，不归 compile-only gate。

建议消息：

```text
Invalid cast. Cannot convert from "<Source>" to "<Target>".
Casting "Variant" to "<Target>" is unsafe.
Cast target type '<text>' cannot be resolved in the current scope.
```

### 4.3 chain 与后续 typed consumer

cast root 发布 `RESOLVED(targetType)` 后，现有 fallback expression receiver 应自然支持：

```gdscript
(value as Node).name
(value as Array)[0]
```

实施时优先增加回归测试，不新增 `CastExpression` 专用 chain-head 分支。只有现有 `FrontendChainStatusBridge` 无法消费 stable cast fact 时，才最小修改 fallback adapter。

type-check 应验证 cast 结果自动进入：

- local/property initializer boundary
- assignment RHS
- return boundary
- fixed call argument boundary
- subscript base/key/value 后续边界

这些 consumer 不得重新执行 explicit-cast compatibility；它们只消费 target-typed result。

## 5. 显式转换支持矩阵

### 5.1 `Variant` target

- source 为 `Variant`：`AssignInsn`。
- source 为任意非 compiler-only runtime type：`PackVariantInsn`。
- 不发 unsafe warning。

### 5.2 builtin target

hard source/target 使用 Godot 4.7.1 `Variant::can_convert(...)` 表，而不是 implicit matrix。实现时应将完整 pair 冻结为 parameterized test，至少覆盖：

- `bool` / `int` / `float` / `String` / `Nil` 的 Godot 显式转换组合。
- `String -> StringName`、`String -> NodePath`、`String -> Color`。
- `int -> Color`。
- `Object -> RID`（Godot `can_convert` 允许）；`Nil -> RID` 不允许（仅 `Nil -> Object`）。
- `Vector2 <-> Vector2i`、`Rect2 <-> Rect2i`、`Vector3 <-> Vector3i`、`Vector4 <-> Vector4i`。
- `Basis <-> Quaternion`。
- `Transform2D -> Transform3D`。
- `Projection <-> Transform3D` 及 Godot 允许的 `Transform3D` 目标组合。
- generic `Array` 与 packed-array family 的双向转换。
- Godot 明确拒绝的 unrelated pair。

source 为 `Variant` / `DYNAMIC` 时不做 static pair rejection，统一 `BUILTIN_RUNTIME_CAST` + unsafe warning。

runtime 失败必须稳定打印 cast error、销毁 temporary Variant，并走 `CBodyBuilder.returnDefault()`；不要复制 Godot release build 中结果槽可能未定义的行为。

### 5.3 Object target

分类规则：

- source `Nil`：`OBJECT_RUNTIME_CAST`，结果 canonical null。
- source `Variant` / `DYNAMIC`：`OBJECT_RUNTIME_CAST` + unsafe warning。
- source Object 与 target 精确同型：`IDENTITY`（与其它 exact same type 共用路径），使用 `AssignInsn`。
- source Object 是 target 的真子类（strict assignment-upcast）：`OBJECT_UPCAST`，使用 `AssignInsn`。
- source Object 与 target 在同一继承链上、target 是 source 的子类：`OBJECT_RUNTIME_CAST`。
- source/target 是静态已知不相关 Object class，或任一侧 class 未在 registry 注册导致链关系无法证明：`INVALID`，`sema.type_check` error。
- source 是确定的 non-object hard type：`INVALID`，除非 target 实际是 `RID` 等 builtin 路径。

class relation 与 canonical name 必须复用 `ClassRegistry`、`FrontendClassNameContract`、runtime name mapping 和 superclass canonical-name 合同；lowering/backend 不得拼接 source alias。

### 5.4 Object runtime 与 ownership

`ObjectCastInsnGen` 必须：

- 使用 instance ID 验证 live raw pointer；不得只检查 cached `ptr`。
- 使用 ClassDB/runtime registered class name 检查 inheritance；不得依赖 C struct cast。
- 对 GDScript script-instance-only identity，ClassDB 不是充分信息。首轮只接受 runtime registration 已证明可查询的类；其它 target 在 shared semantic/compile gate 明确 unsupported，或在扩面前先增加独立 script/GDCC runtime metadata 合同。
- success 时保留原 `instance_id`。
- failure/null/freed 时生成 `{ptr = NULL, instance_id = 0}`。
- GDCC target pointer 通过已验证的 raw Godot object 获取 GDCC instance pointer。
- Engine target pointer 使用已验证的 raw pointer。
- helper 与 cast 指令本身 ownership-neutral；成功结果的 value provenance 保留 source value category，失败 canonical null 不携带 ownership。result slot write 继续由统一 object assignment 路径决定 retain/consume。
- result 写槽使用统一 object slot write/assignment 路径，由 destination storage 决定 retain/consume。
- 不使用 `godot_object_cast_to`，因为当前 LIR 提供 runtime class name 而不是 class tag。

建议新增共享 runtime query helper，只返回 validated cast raw pointer：

```text
gdcc_object_cast_raw_and_id(raw, instance_id, expected_class_name)
gdcc_object_cast_variant(value, expected_class_name)
```

helper 本身 ownership-neutral；fat pointer 构造仍由 target-aware generated code 完成。

### 5.5 参数化容器 target

本轮明确对齐 Godot：runtime `as Array[T]` / `as Dictionary[K, V]` 只执行 `ARRAY` / `DICTIONARY` base builtin cast。target 的参数文本只决定 frontend/LIR 的静态结果类型，不参与 runtime element/key/value typed metadata 校验或转换。

固定合同：

- cast expression 的 published result type 保持完整 target：`Array[T]` 或 `Dictionary[K, V]`。
- `ExplicitCastSupport` 判断 builtin compatibility 时使用 target 的 base extension type `ARRAY` / `DICTIONARY`，不比较 target type arguments。
- source 与 target 完全相同时仍可走 `IDENTITY`。
- parameterized source cast 到 generic `Array` / `Dictionary` 可走 identity-compatible `AssignInsn`。
- generic、不同参数化或 `Variant` source cast 到 parameterized target 均允许，走 `BUILTIN_RUNTIME_CAST`。
- `BuiltinCastInsn.targetTypeName` 保留完整 declared type text；backend 解析完整 target 后只向 `godot_variant_construct(...)` 传 base `GDEXTENSION_VARIANT_TYPE_ARRAY` / `DICTIONARY`。
- backend 成功后将 base container value 写入静态 target-typed result slot，不调用 typed-array/dictionary metadata guard，不重新构造 typed container，也不复制/转换元素。
- runtime value 原有 typed metadata 保持原样：已有 metadata 不被改写，plain container 不因 `as Array[T]` / `Dictionary[K, V]` 获得 metadata。
- `Variant as Array[T]` / `Dictionary[K, V]` 仍属于 unsafe runtime cast，发布 `sema.unsafe_cast` warning。
- nested structured container 仍受当前 declared-type resolver 支持面约束；cast 不为 parser/type-system 尚不接受的 target 扩面。
- script-leaf 或其它 target leaf 若已被 shared declared-type resolver 接受，cast 本身不额外拒绝；其后跨越 outward ABI/property/call-wrapper 边界时，继续由 typed-array/dictionary ABI 合同独立校验。

这一行为会允许静态参数化类型与 runtime typed metadata 不一致，属于有意的 Godot parity，而不是 lowering bug。任何依赖 typed metadata 的 runtime operation 必须读取实际容器 metadata，不得因为 cast result 的静态 `GdArrayType` / `GdDictionaryType` 就假设 runtime metadata 已被转换。

## 6. 分阶段实施计划

### Phase 0：冻结 parser 与 explicit-cast 合同

状态：**已完成**（2026-08-06）

实施内容：

- [x] 独立 `FrontendCastParseBehaviorTest` 冻结 `CastExpression` AST。
- [x] 覆盖 gdparser 实际二元优先级、左结合、RHS `TypeRef` 与缺失 type specifier 的 parse diagnostic。
- [x] 新增 `ExplicitCastSupport` 与 `ExplicitCastDecision` enum。
- [x] 新增 `ExplicitCastSupportTest`，按 source/target type parameterized matrix 覆盖 Godot `Variant::can_convert` 表与 Object relation。
- [x] 冻结参数化容器 base-only cast parity（generic/different-parameter/Variant source positive cases）。metadata-not-rewritten 属于 backend runtime 断言，延后到 Phase 3/5。

产出：

- `src/main/java/gd/script/gdcc/util/type/ExplicitCastDecision.java`
- `src/main/java/gd/script/gdcc/util/type/ExplicitCastSupport.java`
- `src/test/java/gd/script/gdcc/util/type/ExplicitCastSupportTest.java`
- `src/test/java/gd/script/gdcc/frontend/parse/FrontendCastParseBehaviorTest.java`

验收细则：

- [x] **gdparser 0.5.2 实际行为**：`a + b as float` 解析为 `a + (b as float)`（外层 `BinaryExpression`，右侧为 `CastExpression`）。Godot 4.7.1 将 `as` 置于二元算符之下；`(a + b) as float` 可恢复 Godot 形状。本阶段只冻结依赖 AST，不修改 gdparser。
- [x] `x as int as float` 外层 Cast operand 是内层 CastExpression（左结合）。
- [x] target text 保持 parser 提供的 source-facing `TypeRef`。
- [x] classifier 不调用 implicit boundary helper；测试锚定 `String→NodePath` / `int→Color` / `Object→RID` / `float→int` 等 strict-matrix 不会误拒的 pair。
- [x] classifier 对 compiler-only type fail-fast。
- [x] 完整 matrix test 能在单个失败中显示 source/target/expected decision。
- [x] **`Nil→RID` = INVALID**（Godot `can_convert` 仅允许 `Nil→Object`；计划 §5.2 原文 “Object / Nil -> RID” 中 Nil 侧以本条 Godot 源码为准）。
- [x] Object 精确同型冻结为 `IDENTITY`（严格 upcast 才是 `OBJECT_UPCAST`）；未知 Object 类名 fail-closed 有测试锚定。
- [x] `review-expert-a` 审阅：无 high 级问题；medium/low（文档对齐、unknown class、`*→String` 边）已修复并复测通过。

### Phase 1：shared semantic 与 diagnostic

实施内容：

- `FrontendExpressionSemanticSupport`：新增 `resolveCastExpressionType(...)`，替换 Cast deferred 分支。
- `FrontendBodyOwnerProcedures`：发布 `RESOLVED(targetType)`；对 runtime-open source 发布 `sema.unsafe_cast` warning。
- `FrontendTypeCheckAnalyzer`：新增 CastExpression static compatibility 检查。
- `FrontendCompileCheckAnalyzer`：暂不解除 blocker。
- 更新 `diagnostic_manager.md`、`frontend_rules.md` 中的 shared diagnostic 描述，但保留 temporary compile intercept。

验收细则：

- known builtin/Object target 发布 exact target type。
- unknown type、`null`、`void`、malformed structured target 发布 `FAILED` 并发单一 error。
- operand upstream failure 不在 cast root 重复发错。
- hard invalid cast 发 `sema.type_check` error。
- `Variant as int` 发布 `RESOLVED(int)` 与一条 `sema.unsafe_cast` warning。
- `value as Variant` 无 unsafe warning。
- compile-only 入口仍对 CastExpression 发 blocker，证明尚未提前放行。
- `FrontendAnalysisData` 无新增 cast target side-table。

### Phase 2：LIR 合同与 parser/serializer

实施内容：

- 新增 `GdInstruction.BUILTIN_CAST`。
- 新增 `BuiltinCastInsn`。
- 扩展 `ParsedLirInstruction`。
- 将 `ObjectCastInsn.objectId` 重命名为 `valueId`，同步 parser/serializer/tests。
- 更新 `doc/gdcc_low_ir.md` 中 `builtin_cast` 与 `object_cast` 合同。
- 新增 `BuiltinCastInsnContractTest`、`ObjectCastInsnContractTest`。

验收细则：

- 两条指令均完成 serialize/parse round-trip。
- `builtin_cast` result required；missing result/operand/invalid operand kind 解析失败。
- `object_cast` 保持现有文本兼容，Java API 使用 `valueId`。
- `object_cast` 的 `className` 固定为 runtime canonical/Godot-facing class name；`builtin_cast` 接受 builtin 的稳定 `GdType.getTypeName()` 文本，参数化 `Array[T]` / `Dictionary[K, V]` 必须保留完整 declared type text。
- parser 不重解析或重写指令类型文本；backend 对 `object_cast` 以 `ClassRegistry`/runtime name contract 做防御性校验，对 `builtin_cast` 以 builtin target 校验，失败均为 `invalidInsn`。
- frontend published-fact guard 拒绝 compiler-only cast result；backend instruction generator 对 compiler-only locals/source/result 做 fail-fast。现有 `LirPublicAbiValidator` 继续只负责其已有 public ABI surfaces，不扩展为 instruction/local walker 的假合同。

### Phase 3：C backend 与 runtime helper

实施内容：

- 新增 `BuiltinCastInsnGen` 并注册到 `CCodegen`。
- 新增 `ObjectCastInsnGen` 并注册到 `CCodegen`。
- 在 `InsnGenSupport` 中提取可复用的 Variant construction/unpack cleanup 小 helper，避免 generator 重复手写 temp 生命周期。
- 在 `gdcc_helper.h` 或 object fat-pointer template 中增加 ownership-neutral object cast query helper。
- 更新 `doc/gdcc_runtime_lib.md`、object fat-pointer 与 ownership 文档。
- 新增 `BuiltinCastInsnGenTest`、`ObjectCastInsnGenTest`。

`BuiltinCastInsnGen` 验收细则：

- non-Variant source 只 pack 一次。
- 调用 `godot_variant_construct`，target enum 与 result type 一致。
- 参数化 `Array[T]` / `Dictionary[K, V]` 使用 base ARRAY/DICTIONARY enum，且不调用 typed metadata guard/constructor。
- 检查 `GDExtensionCallError.error == GDEXTENSION_CALL_OK`。
- 成功后 exact unpack 到 target result。
- 失败路径打印稳定 runtime error，销毁全部 initialized temp，走 default-return cleanup。
- exact/identity/Variant target LIR 若误入 generator，明确 invalid-instruction fail-fast。

`ObjectCastInsnGen` 验收细则：

- Engine object success/downcast-fail/null/freed。
- 已通过 runtime registration 暴露 canonical class/inheritance identity 的 GDCC Object success/downcast-fail；GDScript script-instance-only 或未注册继承关系的类必须明确走 unsupported/blocked 合同，不得把“ClassDB 名字存在”当成完整 script inheritance 支持。
- Variant OBJECT/non-OBJECT/NIL payload。
- success 保留 instance ID，failure 归一化为 ID 0。
- 不从未经验证的 raw pointer 恢复 ID。
- 不调用 `gdcc_check_variant_type_object` 或 plain `_fat_ptr_from_variant` 代替 class check。
- success result 保留 source value provenance，failure 使用 canonical null；统一 result slot write 不产生多余 own/release。
- hand-written invalid target/result mismatch fail-fast。

### Phase 4：frontend body lowering

实施内容：

- `FrontendBodyLoweringSession` 增加 cast-specific materialization helper，但不复用 implicit boundary decision。
- 实现 `FrontendCastInsnLoweringProcessor`。
- 保持 `FrontendCfgGraphBuilder.buildCastValue(...)` 的现有 item 形状，仅更新过时注释。
- 新增 `FrontendCastInsnLoweringTest`。
- 扩展 `FrontendLoweringBodyInsnPassTest` 与 CFG builder tests。

decision 到 LIR 的固定映射：

- `IDENTITY` -> `AssignInsn`。
- `PACK_TO_VARIANT` -> `PackVariantInsn`。
- `BUILTIN_RUNTIME_CAST` -> `BuiltinCastInsn`。
- `OBJECT_UPCAST` -> `AssignInsn`。
- `OBJECT_RUNTIME_CAST` -> `ObjectCastInsn`。
- `INVALID` -> fail-fast；正常 compile 路径应已被 type-check error 阻断。

验收细则：

- operand 只求值一次，保持 source order。
- result 始终写入 `cfg_tmp_<resultValueId>` target-typed slot。
- `as Variant` 对 concrete source 发 `PackVariantInsn`，Variant source 直接 assign。
- builtin hard/dynamic source 发单一 `BuiltinCastInsn`。
- generic/different-parameter/Variant source cast 到 parameterized container 发单一 `BuiltinCastInsn`；result slot 保持完整 parameterized static type。
- Object upcast 直接 assign，runtime downcast 发单一 `ObjectCastInsn`。
- `Nil as Object` 固定发 `ObjectCastInsn`，由统一 object-cast failure contract 生成 canonical null；lowering 不得旁路为另一条可选 literal-null 路径。
- cast 作为 condition 时继续走既有 condition normalization。
- cast 作为 chain head 时 CFG 先生成 CastItem，再生成 member/call/subscript item。
- lowering 不重新解析 `TypeRef.sourceText()`。

### Phase 5：解除 compile gate 与端到端验证

实施内容：

- 从 `FrontendCompileCheckAnalyzer.walkExpression(...)` 移除 CastExpression 显式 blocker，进入 default compile surface recursion。
- 更新 `frontend_rules.md`，从 temporary compile intercept 列表移除 CastExpression。
- 更新 `frontend_compile_check_analyzer_implementation.md`、`diagnostic_manager.md`。
- 新增成对资源 `src/test/test_suite/unit_test/script/cast/*.gd` 与 `src/test/test_suite/unit_test/validation/cast/*.gd`；两者保持相同的相对文件名。
- 核对 `GdScriptUnitTestCompileRunner` 的 discovery 规则；只有现有 runner 明确使用 allowlist 时才在 `GdScriptUnitTestCompileRunnerTest` 注册 cast cases。
- 完成 targeted tests、相关 integration tests 与 full build。

端到端用例至少包括：

- builtin identity 与 numeric/string conversion。
- `Variant -> builtin` success。
- `Variant -> builtin` runtime failure，验证 error/cleanup。
- Engine Object upcast/downcast success/downcast null。
- 已注册并可由 runtime 查询继承 identity 的 GDCC Object upcast/downcast success/downcast null；未注册 script-instance-only target 使用 compile diagnostic 锚定当前不支持面。
- null/freed object cast。
- cast 结果 member access。
- cast 结果 local/return/call argument。
- cast 结果 condition normalization。
- parameterized container base-only cast success，并验证 runtime typed metadata 不会因 cast target 被补写或转换。
- unrelated hard types compile diagnostic。

解除 gate 的验收细则：

- supported CastExpression 不再产生 `sema.compile_check`。
- invalid cast 只保留 shared semantic/type-check error，不被 compile gate 重复包装。
- shared `analyze(...)`、inspection 与 compile-only `analyzeForCompile(...)` 的 diagnostic owner 保持分离。
- 其它 temporary intercept 仍被正确封口。

### Phase 6：事实源收敛

实施内容：

- 将稳定合同写入 `frontend_cast_expression_implementation.md`。
- 删除本计划中的阶段状态、checkbox 与实施日志后，用事实源文档替代本文件。
- 更新 `frontend_is_type_test_implementation.md` 的“明确非目标”说明，仅保留“`as` 由独立合同管理”。
- 更新 `gdcc_low_ir.md`、`gdcc_runtime_lib.md`、ownership、compile gate 与 frontend rules 的交叉引用。

验收细则：

- 文档不再把 CastExpression 描述为 deferred 或 compile-blocked。
- explicit cast matrix 只有一个长期真源。
- LIR、backend helper、diagnostic category 与 test anchor 均能从事实源直接定位。

## 7. 预计修改文件

### 7.1 Frontend semantic

- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendBodyOwnerProcedures.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendTypeCheckAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`
- `src/main/java/gd/script/gdcc/util/type/ExplicitCastSupport.java`（新增）
- `src/main/java/gd/script/gdcc/util/type/ExplicitCastDecision.java`（新增 public enum，frontend 与 backend 必须共享；不得复制 private 平行 decision）

### 7.2 Frontend lowering

- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilder.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendBodyLoweringSession.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`

`CastItem.java` 当前形状预计无需修改。

### 7.3 LIR

- `src/main/java/gd/script/gdcc/enums/GdInstruction.java`
- `src/main/java/gd/script/gdcc/lir/insn/BuiltinCastInsn.java`（新增）
- `src/main/java/gd/script/gdcc/lir/insn/ObjectCastInsn.java`
- `src/main/java/gd/script/gdcc/lir/parser/ParsedLirInstruction.java`

### 7.4 Backend/runtime

- `src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/BuiltinCastInsnGen.java`（新增）
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/ObjectCastInsnGen.java`（新增）
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/InsnGenSupport.java`
- `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
- 必要时修改 `src/main/c/codegen/template_451/object_fat_ptr_types.h.ftl`

不预计修改 Gradle/build 配置。

## 8. 测试清单

建议新增：

- `FrontendCastParseBehaviorTest`
- `ExplicitCastSupportTest`
- `FrontendCastInsnLoweringTest`
- `BuiltinCastInsnContractTest`
- `ObjectCastInsnContractTest`
- `BuiltinCastInsnGenTest`
- `ObjectCastInsnGenTest`

建议扩展：

- `FrontendExpressionSemanticSupportTest`
- `FrontendBodyOwnerProceduresExprTypeTest`
- `FrontendTypeCheckAnalyzerTest`
- `FrontendCompileCheckAnalyzerTest`
- `FrontendCfgGraphBuilderTest`
- `FrontendLoweringBuildCfgPassTest`
- `FrontendLoweringBodyInsnPassTest`
- `FrontendChainHeadReceiverSupportTest` 或对应 chain integration test
- `GdScriptUnitTestCompileRunnerTest`（仅当 runner/test 现有结构需要显式 allowlist）

必须反转的旧断言：

- CastExpression 从 `DEFERRED` 改为 `RESOLVED(targetType)`。
- feature 完成后 CastExpression 不再被 compile-check 显式封口。
- Cast body lowering 不再抛 `cast lowering is not implemented yet`。

## 9. 验证命令

迭代时只运行相关 targeted tests：

```text
pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendCastParseBehaviorTest,ExplicitCastSupportTest,FrontendExpressionSemanticSupportTest,FrontendTypeCheckAnalyzerTest,FrontendCompileCheckAnalyzerTest
```

```text
pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests BuiltinCastInsnContractTest,ObjectCastInsnContractTest,FrontendCastInsnLoweringTest,FrontendLoweringBodyInsnPassTest
```

```text
pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests BuiltinCastInsnGenTest,ObjectCastInsnGenTest,GdScriptUnitTestCompileRunnerTest
```

最终验证：

```text
.\gradlew.bat clean build --no-daemon --info --console=plain
```

## 10. 完成定义

只有同时满足以下条件，`as` 才算实施完成：

- parser behavior characterization 已冻结。
- CastExpression shared semantic 不再 deferred。
- static invalid cast 与 unsafe runtime cast diagnostic 已闭环。
- explicit cast matrix 与 Godot 4.7.1 基线有 exhaustive unit test。
- `builtin_cast` 与 `object_cast` LIR 合同、parser/serializer、backend 全部可用。
- builtin failure path 有稳定 runtime error 与 cleanup。
- Object success/null/freed/mismatch 保持 canonical fat-pointer，并遵守“成功保留 source ownership category、失败 canonical null”的 ownership 合同。
- CastItem body lowering 覆盖所有正式 decision。
- cast 可作为普通 typed expression 被 chain、return、call、assignment、condition 等 consumer 使用。
- CastExpression compile blocker 已移除，且无 duplicate diagnostic。
- 参数化容器已按 Godot base-only cast 对齐，并通过测试明确静态 target 与 runtime typed metadata 可不一致。
- targeted tests、test-suite 与 full build 全部通过。
- 所有相关事实源文档已同步，计划已收敛为长期实现说明。

## 11. 主要风险与防护

- 风险：误用 implicit conversion matrix，导致 `as` 被错误收紧。
  - 防护：独立 `ExplicitCastSupport` + exhaustive Godot matrix test。
- 风险：用 `construct_builtin` 代替 Variant cast，漏掉 dynamic/宽松 pair。
  - 防护：独立 `builtin_cast` + `godot_variant_construct`。
- 风险：用 `_fat_ptr_from_variant` 直接完成 Object downcast，未检查 class。
  - 防护：独立 runtime class check helper + negative backend tests。
- 风险：cast 结果被硬编码为 OWNED 或 BORROWED，破坏表示转换保留 source ownership category 的合同。
  - 防护：ownership-neutral helper、source provenance preservation 与 lifecycle characterization/integration tests。
- 风险：freed object 只按 cached ptr 判断，产生悬空 target pointer。
  - 防护：instance ID live lookup；success 才保留 ID，failure canonical null。
- 风险：parameterized container cast 的静态类型与 runtime typed metadata 不一致，后续 consumer 错把静态 target 当成已转换 metadata。
  - 防护：backend 只执行 base cast；增加 metadata-not-rewritten parity tests；所有 metadata-sensitive operation 必须查询实际 runtime metadata。
- 风险：提前解除 compile gate，把未实现路径泄漏到 lowering/backend。
  - 防护：严格执行 §3.5 的最终 gate 条件。
- 风险：source-facing inner class name 与 runtime canonical name 漂移，或 inner/script class 没有 ClassDB 可查询的继承 identity。
  - 防护：复用 class-name contracts；只对 runtime registration 已证明的类开放，并分别增加 registered-inner-class positive test 与 script-instance-only unsupported test。

## 12. 调研来源

- Godot parser：<https://github.com/godotengine/godot/blob/4.7.1-stable/modules/gdscript/gdscript_parser.cpp>
- Godot analyzer：<https://github.com/godotengine/godot/blob/4.7.1-stable/modules/gdscript/gdscript_analyzer.cpp>
- Godot bytecode compiler：<https://github.com/godotengine/godot/blob/4.7.1-stable/modules/gdscript/gdscript_byte_codegen.cpp>
- Godot VM：<https://github.com/godotengine/godot/blob/4.7.1-stable/modules/gdscript/gdscript_vm.cpp>
- Godot Variant conversion：<https://github.com/godotengine/godot/blob/4.7.1-stable/core/variant/variant.cpp>
- Godot Variant construction：<https://github.com/godotengine/godot/blob/4.7.1-stable/core/variant/variant_construct.cpp>
- Godot GDScript reference：<https://github.com/godotengine/godot-docs/blob/4.7/tutorials/scripting/gdscript/gdscript_basics.rst>
