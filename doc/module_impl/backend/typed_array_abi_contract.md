# Typed Array 外部 ABI 合同

> 本文档作为 backend typed-array outward ABI 实现的长期事实源。  
> 只保留当前代码已经落地的合同、边界、测试锚点与长期风险，不记录阶段性试错流水账。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - `src/main/java/gd/script/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
  - `src/test/java/gd/script/gdcc/backend/c/**`
- 更新时间：2026-04-13
- 上游对齐基线：
  - Godot 4.x 对 source-level `Array[T]` outward slot 的合同：
    - `type = ARRAY`
    - `hint = PROPERTY_HINT_ARRAY_TYPE`
    - `hint_string = "<element_atom>"`
- 关联文档：
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/construct_array_implementation.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`

## 当前最终状态

> 重要限制：当前 typed-array outward ABI **不支持 script leaf**。  
> 任何需要依赖非 nil `typed_script` 才能表达真实 element identity 的 leaf，都不应进入 backend typed-array metadata / runtime helper。frontend / lowering 必须提前拦住；backend 在这里只承接已解析好的 engine object / GDCC object leaf，不会静默降级成 class-name-only script leaf，也不会额外做 registry 重验。

### 核心实现落点

- outward metadata helper：
  - `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
    - `renderBoundMetadata(...)`
    - `renderPropertyMetadata(...)`
- typed-array wrapper preflight helper：
  - `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
    - `needsTypedArrayCallGuard(...)`
    - `renderTypedArrayGuardBuiltinTypeLiteral(...)`
    - `isTypedArrayGuardObjectLeaf(...)`
    - `renderTypedArrayGuardClassNameExpr(...)`
- typed-array runtime gate：
  - `src/main/c/codegen/template_451/entry.h.ftl`
- property registration metadata：
  - `src/main/c/codegen/template_451/entry.c.ftl`
- typed-array reconstruction path：
  - `src/main/java/gd/script/gdcc/backend/c/gen/CBuiltinBuilder.java`
    - `constructArray(...)`

### 当前已锁定的实现结论

- ordinary method / return / property outward metadata 已能发布 non-generic typed array：
  - `type = GDEXTENSION_VARIANT_TYPE_ARRAY`
  - `hint = godot_PROPERTY_HINT_ARRAY_TYPE`
  - `hint_string = "<element_atom>"`
- 上述 outward metadata 合同当前只覆盖 backend 可稳定表达的 element leaf：
  - primitive / builtin（不含 `Variant`）
  - packed array
  - engine object / GDCC object
  - plain `Array`
  - plain `Dictionary`
  - **不包含 script leaf**
- generic `Array[Variant]` 继续保持 plain `Array` outward surface：
  - 不发布 typed-array hint
  - 不进入 typed-array runtime preflight
- generated `call_func` wrapper 会先保留原有 base `ARRAY` type gate，再对 non-generic typed array 执行 typedness preflight。
- object leaf 的 `script` metadata 不能按 `TYPE_NIL` 理解：
  - Godot 返回的是“语义为 null 的 OBJECT Variant”
  - backend 通过 `godot_variant_evaluate(... == nil)` 判断 null-object script metadata
- 函数体内部的 typed-array 本地重建不能继续把 script 位传原始 `NULL`：
  - `CBuiltinBuilder.constructArray(...)` 现在会为 typed array materialize 真实 nil `Variant` temp
  - 再把该 temp 传给 `godot_new_Array_with_Array_int_StringName_Variant(...)`
  - 这条合同同时覆盖：
    - 显式 `construct_array`
    - `__prepare__` 自动注入
    - property default helper / utility default materialization
- property setter 通过 auto-generated `_field_setter_*` 进入 ordinary method wrapper：
  - 因此 typed-array property set 的 runtime gate 与 method parameter 共享同一套 preflight 合同

## 长期合同

### 1. 语义边界合同

- typed array outward ABI 是 backend 合同，不是 frontend / LIR 合同：
  - frontend ordinary boundary 继续只负责 source-level 类型与 ordinary lowering
  - outward `type / hint / hint_string / runtime gate` 由 backend helper/template 独占
- ordinary container construction 正确，不代表 outward ABI 自动正确：
  - method argument metadata
  - method return metadata
  - property registration metadata
  - generated `call_func` runtime gate
  - 函数体里的 typed-array 本地重建路径
  以上五者必须一起成立
- typed array ABI 与 typed dictionary ABI 是并列合同：
  - 可以共享最小的 private leaf 解析 helper
  - 但不能把 array 的单 atom grammar 与 dictionary 的双侧 grammar 强行统一成一个大 abstraction

### 2. outward metadata 编码合同

- non-generic `Array[T]` outward slot 的统一编码必须保持：
  - `type = GDEXTENSION_VARIANT_TYPE_ARRAY`
  - `hint = godot_PROPERTY_HINT_ARRAY_TYPE`
  - `hint_string = "<element_atom>"`
- 该编码同时适用于：
  - method argument metadata
  - method return metadata
  - property registration metadata
- generic `Array[Variant]` 不是 typed array outward slot：
  - 保持 `PROPERTY_HINT_NONE`
  - 不发布 typed `hint_string`
  - 不进入 typed-array leaf hint renderer
- top-level `class_name` 不承载 typed-array element identity：
  - object leaf 身份在 `hint_string` 与 runtime preflight 中表达
  - property bind 的 `class_name` 槽只对裸 `@export` 的 Object property 填 property 类型类名（export 注解合同），其余 property 为空串
- 当前 outward hint renderer 只允许 Godot outward surface 可直接表达的 element leaf：
  - primitive / builtin（不含 `Variant`）
  - packed array
  - engine object / GDCC object
  - plain `Array`
  - plain `Dictionary`
- object leaf 直接发布 class-name atom：
  - backend 依赖 frontend / lowering 保证它在到达这里前已经是稳定的 engine object / GDCC object
  - backend 不重复做 registry 重验
- `script leaf` 当前不是受支持的 outward leaf：
  - 原因不是 class-name 字符串缺失，而是它需要 **非 nil `typed_script`** 才能表达真实 element identity
  - frontend / lowering 必须在进入 backend helper 前拒绝这类 leaf
- 以下 leaf 必须 fail-fast，不能静默降级：
  - nested typed `Array[T]`
  - nested typed `Dictionary[K, V]`
  - `void`
  - 缺失 outward metadata 的未知 leaf

### 3. `call_func` runtime gate 合同

- non-`Variant` typed-array 参数必须继续保留 base `ARRAY` type gate。
- 只有 non-generic typed array 参数会进入 typed-array preflight。
- generic `Array[Variant]` 继续只保留 plain `ARRAY` gate：
  - 不会误生成 typed-array preflight
  - 不会误被当成 typed leaf 再比较 element metadata
- preflight 必须发生在 wrapper-owned 参数 local 物化之前：
  - mismatch 可以直接返回
  - 不引入 partial wrapper-local cleanup 分支
- builtin leaf 比较规则：
  - 使用 `godot_Array_get_typed_builtin(...)`
- object leaf 额外比较规则：
  - `godot_Array_get_typed_class_name(...)`
  - `godot_Array_get_typed_script(...)`
  - `script` 通过 `godot_variant_evaluate(... == nil)` 判断 null-object 语义
- typed-array preflight 明确**不使用** `godot_Array_is_same_typed(...)`：
  - 当前 wrapper 已经直接拿到 incoming array 的 `typed_builtin / typed_class_name / typed_script` 三元组
  - 直接比较字段比额外再走一层 helper 调用更便宜
  - 也避免围绕“为什么不用 `is_same_typed`”反复扩大实现边界
  - 除非后续有明确 profiling 证据证明它更优，否则这条约束保持不变
- mismatch 行为保持粗粒度 Godot invalid-argument 合同：
  - `r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT`
  - `r_error->expected = GDEXTENSION_VARIANT_TYPE_ARRAY`
  - `r_error->argument = <index>`
- property negative path 的 Godot 文案不必与 method call 完全一致：
  - method call 常见为 `Invalid type in function`
  - property set 常见为 `Invalid assignment of property or key`
  - 回归测试应锚定“稳定失败类别 + 控制流被截断”，而不是整句文案

### 4. typed-array 本地重建合同

- typed-array 本地重建继续使用 `godot_Array` 作为物理 carrier：
  - typedness 由 constructor metadata 表达
  - 这不是 ABI bug，本身不需要改物理 C 形状
- 当 backend 需要在函数体、`__prepare__` 或 field default helper 中构造 typed array 时：
  - builtin type 按编译期 leaf 发布
  - object leaf class name 按编译期 leaf 发布
  - script 位必须传真实 nil `Variant`
- generic `Array[Variant]` 保持 plain constructor 路径：
  - 不创建 typed-array script temp
  - 不走 typed-array constructor 分支
- 不允许再把 object leaf script 位直接传 `NULL`：
  - 这会偏离 Godot typed-array constructor 的实际契约
  - 并可能在 exact typed-array positive path 上重新打开 crash 风险

### 5. helper / template 收口合同

- 复杂的 typed-array leaf 判定与 fail-fast 继续放在 helper：
  - typed-array outward hint atom
  - typed-array runtime leaf builtin type
  - object leaf class-name expr
  - 是否需要 typed-array guard
- 大段 wrapper C 代码结构应优先放在模板：
  - `entry.h.ftl` 负责 preflight block 的整体形状
  - helper 不应重新退化成模板字符串生成器
- 模板调用顺序保持：
  1. 先保留原有 base `ARRAY` type gate
  2. 再判定 `needsTypedArrayCallGuard(...)`
  3. 只有命中的参数才展开 typed-array guard
  4. typed-array preflight 之后才物化 wrapper-owned unpack locals
- 不允许在 `CGenHelper` 中重新塞回大段 typed-array C 字符串拼接。
- 不允许在其他 generator/template 中再次散落 typed-array ABI 的局部硬编码。

## 与 Godot 的对齐点

### 1. outward typed array 不是 plain `Array`

Godot 对 source-level `Array[T]` outward slot 的约定是：

- outward type 发布为 `ARRAY`
- 通过 `PROPERTY_HINT_ARRAY_TYPE + hint_string` 恢复真实 typed surface

因此 backend 不能只发布 `type = ARRAY`，也不能继续把 typed array 静默压平为 plain `Array`。

### 2. object leaf script metadata 是 null-object，不是 `TYPE_NIL`

Godot 对 object leaf 的 `get_typed_script()` 结果可能同时满足：

- `== null`
- `typeof(...) == TYPE_OBJECT`
- `is Object == false`

因此 backend 不能再把“script 为 null”误写成“runtime 类型必须是 `NIL`”。

### 3. typed-array constructor 需要真实 nil `Variant`

Godot 的 typed-array constructor 合同要求 script carrier 是 `Variant*`。

因此在纯 C backend 中：

- `NULL` 指针不是安全替代
- 需要显式 materialize nil `Variant` 再传入 constructor

## 回归测试基线

- helper-level：
  - `src/test/java/gd/script/gdcc/backend/c/gen/CGenHelperTest.java`
    - typed-array metadata 正例
    - generic array 不误走 typed hint / typed guard
    - object leaf 不做 backend registry 重验
    - nested typed leaf / 缺失 metadata leaf fail-fast
- codegen-level：
  - `src/test/java/gd/script/gdcc/backend/c/gen/CCodegenTest.java`
    - method arg / return / property metadata
    - typed-array wrapper preflight 结构
    - preflight 位于 unpack 之前
    - 不生成 `godot_Array_is_same_typed(...)`
  - `src/test/java/gd/script/gdcc/backend/c/gen/CConstructInsnGenTest.java`
    - typed-array constructor codegen
    - nil `Variant` script carrier
  - `src/test/java/gd/script/gdcc/backend/c/gen/UtilityDefaultLiteralMaterializationTest.java`
    - registry-backed typed-array default literal
    - `Array[Array]([])` 默认值路径
- integration-level：
  - `src/test/java/gd/script/gdcc/backend/c/build/FrontendLoweringToCTypedArrayAbiIntegrationTest.java`
    - method parameter positive / plain negative / wrong-typed negative
    - method return outward fidelity
    - property direct get/set positive
    - property plain / wrong-typed negative
- 调查基线：
  - `src/test/java/gd/script/gdcc/backend/c/build/FrontendLoweringToCTypedArrayAbiInvestigationTest.java`
    - 仅保留轻量 post-fix probe 角色
    - 不再承担主回归职责

建议命令：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest,CConstructInsnGenTest,UtilityDefaultLiteralMaterializationTest
rtk .\gradlew.bat test --tests "gd.script.gdcc.backend.c.build.FrontendLoweringToCTypedArrayAbiIntegrationTest" --no-daemon --info --console=plain
```

## 长期风险与维护提醒

1. 最危险的半修复仍然是“只补 metadata，不补 runtime gate”。这种情况下 analyzer / property list 看起来正确，但 plain / wrong-typed array 仍会在动态调用边界被错误放行。
2. 另一种半修复是“只补 constructor，不补 runtime gate”。这样 exact 正例也许能跑通，但错误 typedness 仍会漏进 native body。
3. 再次把 object leaf script metadata 按 `TYPE_NIL` 处理，会重新误拒 exact typed array。
4. 再次把 script 位直接传 `NULL` 给 typed-array constructor，会重新打开函数体读取路径和 field default 路径的 crash 风险。
5. 若有人把 typed-array preflight 退化回 `godot_Array_is_same_typed(...)` 或其他额外包装层，会重新引入不必要的热路径开销和维护争议。
6. 若把 typed-array 大段 C 模板结构重新塞回 helper，或者把 typed-array / typed-dictionary 强行统一成同一套 grammar/template，会显著降低可维护性。

## 非目标

- 修改 frontend ordinary boundary compatibility 规则
- 修改 `Array` 的物理 C ABI 形状
- 引入脚本类型专用 outward leaf 模型
  - 也就是说，当前合同明确**不支持 script leaf**
- 支持 nested typed container 作为 typed-array element atom
- 把 generic `Array[Variant]` 改成 typed outward surface
