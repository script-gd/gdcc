# Typed Dictionary 外部 ABI 合同

> 本文档作为 backend typed-dictionary outward ABI 实现的长期事实源。  
> 只保留当前代码已经落地的合同、边界、测试锚点与长期风险，不记录阶段性试错流水账。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - `src/main/java/dev/superice/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
  - `src/test/java/dev/superice/gdcc/backend/c/**`
- 更新时间：2026-04-12
- 上游对齐基线：
  - Godot 4.x 对 source-level `Dictionary[K, V]` outward slot 的合同：
    - `type = DICTIONARY`
    - `hint = PROPERTY_HINT_DICTIONARY_TYPE`
    - `hint_string = "<key_type>;<value_type>"`
- 关联文档：
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/module_impl/backend/typed_dict_abi_fix_plan.md`
  - `doc/test_error/frontend_variant_and_typed_dictionary_abi.md`

## 当前最终状态

### 核心实现落点

- outward metadata helper：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
    - `renderBoundMetadata(...)`
    - `renderPropertyMetadata(...)`
- typed-dictionary wrapper preflight helper：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
    - `needsTypedDictionaryCallGuard(...)`
    - `renderTypedDictionaryGuardBuiltinTypeLiteral(...)`
    - `isTypedDictionaryGuardObjectLeaf(...)`
    - `renderTypedDictionaryGuardClassNameExpr(...)`
- typed-dictionary runtime gate：
  - `src/main/c/codegen/template_451/entry.h.ftl`
- property registration metadata：
  - `src/main/c/codegen/template_451/entry.c.ftl`
- typed-dictionary reconstruction path：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CBuiltinBuilder.java`
    - `constructDictionary(...)`

### 当前已锁定的实现结论

- ordinary method / return / property outward metadata 已能发布 non-generic typed dictionary：
  - `type = GDEXTENSION_VARIANT_TYPE_DICTIONARY`
  - `hint = godot_PROPERTY_HINT_DICTIONARY_TYPE`
  - `hint_string = "<key>;<value>"`
- generic `Dictionary[Variant, Variant]` 继续保持 plain `Dictionary` outward surface：
  - 不发布 typed-dictionary hint
  - 不进入 typed-dictionary runtime preflight
- generated `call_func` wrapper 会先保留原有 base `DICTIONARY` type gate，再对 non-generic typed dictionary 执行二段 typedness 校验
- object leaf 的 `script` metadata 不能按 `TYPE_NIL` 理解：
  - Godot 返回的是“语义为 null 的 OBJECT Variant”
  - backend 通过 `godot_variant_evaluate(... == nil)` 判断 null-object script metadata
- 函数体内部的 typed-dictionary 本地重建不能继续把 script 位传原始 `NULL`
  - `CBuiltinBuilder.constructDictionary(...)` 现在会为 key/value script 构造真实 nil `Variant` temp
  - 再把这两个 temp 传给 `godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant(...)`
- property setter 通过 auto-generated `_field_setter_*` 进入 ordinary method wrapper
  - 因此 typed-dictionary property set 的 runtime gate 与 method parameter 共享同一套 preflight 合同

## 长期合同

### 1. 语义边界合同

- typed dictionary outward ABI 是 backend 合同，不是 frontend / LIR 合同：
  - frontend ordinary boundary 继续只负责 source-level 类型与 ordinary lowering
  - outward `type / hint / hint_string / runtime gate` 由 backend helper/template 独占
- ordinary container construction 正确，不代表 outward ABI 自动正确：
  - method argument metadata
  - method return metadata
  - property registration metadata
  - generated `call_func` runtime gate
  - 函数体里的 typed-dictionary 本地重建路径
  以上五者必须一起成立
- typed dictionary ABI 与 ordinary `Variant` ABI 是并列合同：
  - 可以共享 backend touchpoints
  - 但不能混成同一个“顺手修复”回归面

### 2. outward metadata 编码合同

- non-generic `Dictionary[K, V]` outward slot 的统一编码必须保持：
  - `type = GDEXTENSION_VARIANT_TYPE_DICTIONARY`
  - `hint = godot_PROPERTY_HINT_DICTIONARY_TYPE`
  - `hint_string = "<key>;<value>"`
- 该编码同时适用于：
  - method argument metadata
  - method return metadata
  - property registration metadata
- generic `Dictionary[Variant, Variant]` 不是 typed dictionary outward slot：
  - 保持 `PROPERTY_HINT_NONE`
  - 不发布 typed `hint_string`
- top-level `class_name` 不承载 typed-dictionary leaf identity：
  - object leaf 身份在 `hint_string` 与 runtime preflight 中表达
  - property bind 继续保留当前 owner-class `class_name` 槽位形态
- 当前 outward hint renderer 只允许 Godot outward surface 可直接表达的 leaf：
  - primitive / builtin / packed array / `Variant`
  - engine object / GDCC object
  - plain `Array`
  - plain `Dictionary`
- 以下 leaf 必须 fail-fast，不能静默降级：
  - nested typed `Array[T]`
  - nested typed `Dictionary[K, V]`
  - `void`
  - 缺失 outward metadata 的未知 leaf

### 3. `call_func` runtime gate 合同

- non-`Variant` typed-dictionary 参数必须继续保留 base `DICTIONARY` type gate
- 只有 non-generic typed dictionary 参数会进入 typed-dictionary preflight
- preflight 必须发生在 wrapper-owned 参数 local 物化之前：
  - mismatch 可以直接返回
  - 不引入 partial wrapper-local cleanup 分支
- builtin leaf 比较规则：
  - 使用 `godot_Dictionary_get_typed_key_builtin(...)`
  - 使用 `godot_Dictionary_get_typed_value_builtin(...)`
- object leaf 额外比较规则：
  - `godot_Dictionary_get_typed_*_class_name(...)`
  - `godot_Dictionary_get_typed_*_script(...)`
  - `script` 通过 `godot_variant_evaluate(... == nil)` 判断 null-object 语义
- mismatch 行为保持粗粒度 Godot invalid-argument 合同：
  - `r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT`
  - `r_error->expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY`
  - `r_error->argument = <index>`
- property negative path 的 Godot 文案不必与 method call 完全一致：
  - method call 常见为 `Invalid type in function`
  - property set 常见为 `Invalid assignment of property or key`
  - 回归测试应锚定“稳定失败类别 + 控制流被截断”，而不是整句文案

### 4. typed-dictionary 本地重建合同

- typed-dictionary 本地重建继续使用 `godot_Dictionary` 作为物理 carrier：
  - `godot_TypedDictionary(...)` 仍只是宏别名
  - 这不是 ABI bug，本身不需要改物理 C 形状
- 当 backend 需要在函数体或 `__prepare__` 中构造 typed dictionary 时：
  - key/value builtin type 按编译期 leaf 发布
  - object leaf class name 按编译期 leaf 发布
  - key/value script 位必须传真实 nil `Variant`
- 不允许再把 object leaf script 位直接传 `NULL`
  - 这会偏离 Godot typed-dictionary constructor 的实际契约
  - 并可能在首次真实读取 typed dictionary 时触发 runtime crash

### 5. helper / template 收口合同

- 复杂的 typed-dictionary leaf 判定与 fail-fast 继续放在 helper：
  - leaf builtin type
  - 是否 object leaf
  - object leaf class name
  - 是否需要 typed-dictionary guard
- 大段 wrapper C 代码结构应优先放在模板：
  - `entry.h.ftl` 负责 preflight block 的整体形状
  - helper 不应重新退化成模板字符串生成器
- 模板调用顺序保持：
  1. 先判定 `needsTypedDictionaryCallGuard(...)`
  2. 再按 key/value 两侧展开 guard
- 不允许在其他 generator/template 中再次散落 typed-dictionary ABI 的局部硬编码

## 与 Godot 的对齐点

### 1. outward typed dictionary 不是 plain `Dictionary`

Godot 对 source-level `Dictionary[K, V]` outward slot 的约定是：

- outward type 发布为 `DICTIONARY`
- 通过 `PROPERTY_HINT_DICTIONARY_TYPE + hint_string` 恢复真实 typed surface

因此 backend 不能只发布 `type = DICTIONARY`，也不能继续把 typed dictionary 静默压平为 plain `Dictionary`。

### 2. object leaf script metadata 是 null-object，不是 `TYPE_NIL`

Godot 对 object leaf 的 `get_typed_*_script()` 结果可能同时满足：

- `== null`
- `typeof(...) == TYPE_OBJECT`
- `is Object == false`

因此 backend 不能再把“script 为 null”误写成“runtime 类型必须是 `NIL`”。

### 3. typed-dictionary constructor 需要真实 nil `Variant`

Godot 的 typed-dictionary constructor 合同要求 script carriers 是 `Variant*`。

因此在纯 C backend 中：

- `NULL` 指针不是安全替代
- 需要显式 materialize nil `Variant` 再传入 constructor

## 回归测试基线

- helper-level：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CGenHelperTest.java`
    - typed-dictionary metadata 正例
    - generic dictionary 不误走 typed guard
    - helper misuse fail-fast
- codegen-level：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`
    - method arg / return / property metadata
    - typed-dictionary wrapper guard 结构
    - generic dictionary 不误生成 preflight
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CConstructInsnGenTest.java`
    - typed-dictionary constructor codegen
    - nil `Variant` script carrier
- integration-level：
  - `src/test/java/dev/superice/gdcc/backend/c/build/FrontendLoweringToCTypedDictionaryAbiIntegrationTest.java`
    - method parameter positive / plain negative / wrong-typed negative
    - method return outward fidelity
    - property direct get/set positive
    - property plain / wrong-typed negative
- 调查基线：
  - `doc/test_error/frontend_variant_and_typed_dictionary_abi.md`

建议命令：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest,CConstructInsnGenTest
rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryMethodAbiAcceptsExactAndRejectsPlainDictionaryAtRuntime" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryMethodAbiRejectsWrongTypedDictionaryAtRuntime" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryReturnAbiBuildNativeLibraryAndRunInGodot" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiBuildNativeLibraryAndRunInGodot" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiRejectsPlainDictionarySetAtRuntime" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiRejectsWrongTypedDictionarySetAtRuntime" --no-daemon --info --console=plain
```

## 长期风险与维护提醒

1. 最危险的半修复仍然是“只补 metadata，不补 runtime gate”。这种情况下 analyzer / property list 看起来正确，但 `call()` / property set 仍会放行 wrong-typed / plain dictionary，或者在更晚的阶段报出模糊错误。
2. 另一种半修复是“只补 wrapper gate，不补 return/property metadata”。这样动态调用路径可能恢复，但 outward surface 仍然是 plain `Dictionary`。
3. 再次把 object leaf script metadata 按 `TYPE_NIL` 处理，会重新误拒 exact typed dictionary。
4. 再次把 script 位直接传 `NULL` 给 typed-dictionary constructor，会重新打开函数体读取路径的 crash 风险。
5. 若把 typed-dictionary C 代码结构重新塞回 helper 大段字符串拼接，会让模板与 helper 的职责边界再次漂移，并降低后续维护可读性。

## 非目标

- 修改 frontend ordinary boundary compatibility 规则
- 修改 `Dictionary` / `Array` 的物理 C ABI 形状
- 引入脚本类型专用 outward leaf 模型
- 支持 nested typed container 作为 outward hint leaf
- 把 typed dictionary 与 writable-route continuation 测试重新混成同一个 acceptance fixture
