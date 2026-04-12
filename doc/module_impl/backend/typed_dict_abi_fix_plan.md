# TypedDictionary 外部 ABI 修复计划

> 目标：基于当前 backend helper/template 结构，为 source-level `Dictionary[K, V]`
> 在 ordinary method/property outward ABI 上补齐与 Godot 对齐的 typed-dictionary
> metadata 与 runtime gate，修复“typed dictionary 经过 GDExtension 边界后退化成 plain
> `Dictionary`”的问题。
>
> 状态：Phase A Completed / Phase B-G Planned
>
> 校对基线：2026-04-11（以当前代码库和仓库内 Godot 研究副本为准）

---

## 1. 背景与语义锚点

### 1.1 当前确认的问题不是“容器构造失败”，而是“外部 ABI 失真”

当前代码库里，typed dictionary 的**内部值构造**已经存在：

- `CBuiltinBuilder.constructDictionary(...)` 已能生成：
  - `godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant(...)`
- `gdextension-lite` / Godot 侧也已经提供：
  - `godot_dictionary_set_typed(...)`
  - `godot_Dictionary_is_same_typed(...)`
  - `godot_Dictionary_get_typed_*...`

因此当前 open issue 的核心不是：

- 不能在 native body 内部得到 typed dictionary 值

而是：

- method arg / return / property metadata 没有把 typed dictionary 语义发布到 outward ABI
- `call_func` wrapper 只做 `type == DICTIONARY` 的粗粒度 gate，没有验证 typedness

结果是：

1. GDScript analyzer / editor / runtime 外部边界只能看到 plain `Dictionary`
2. 错误类型的字典可能静默穿透到 wrapper 之后才暴露为更模糊的问题
3. 现有 end-to-end 测试若把 typed dictionary 放在外部边界上，会把 writable-route
   语义和 ABI fidelity 混在一起

### 1.2 当前代码中的问题触点

基于当前代码库，外部 ABI 失真集中在以下位置：

1. `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
   - `renderGdTypeName(...)` 对所有 `GdDictionaryType` 都归一到 `"Dictionary"`
   - `renderBoundMetadata(...)` 当前统一返回：
     - `hint = godot_PROPERTY_HINT_NONE`
     - `hint_string = GD_STATIC_S(u8"")`
     - 只有 `Variant` 会特判 `usage`
2. `src/main/c/codegen/template_451/entry.h.ftl`
   - method binding metadata 直接使用 `renderBoundMetadata(...)`
   - `call_func` wrapper 只保留 `type == GDEXTENSION_VARIANT_TYPE_DICTIONARY` 的 exact gate
   - typed dictionary 参数不会继续校验 key/value typedness
3. `src/main/c/codegen/template_451/entry.c.ftl`
   - property registration 同样复用 `renderPropertyMetadata(...)`
   - 目前 typed dictionary property outward metadata 仍是 plain `Dictionary`
4. `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
   - compiler-owned `_field_getter_*` / `_field_setter_*` 是 ordinary bound methods
   - 因此 property setter 参数如果是 typed dictionary，method wrapper runtime gate
     可以直接覆盖到 property set 路径

### 1.3 与现有 `Variant` ABI 修复的边界关系

本问题与已经落地的 `Variant` outward ABI 修复是**并列关系**，但不应混成同一个合同：

- `Variant` ABI 修的是：
  - `type = NIL`
  - `PROPERTY_USAGE_NIL_IS_VARIANT`
  - `call_func` 对 `Variant` 取消 `type == NIL` exact gate
- TypedDictionary ABI 要修的是：
  - `type = DICTIONARY`
  - `hint = PROPERTY_HINT_DICTIONARY_TYPE`
  - `hint_string = "<key>;<value>"`
  - `call_func` 在 `type == DICTIONARY` 之后继续验证 typedness

这两类问题共用 backend 触点，但语义不同，测试面也必须分开。

---

## 2. Godot / gdextension-lite 对齐事实

### 2.1 Godot 对 typed dictionary 的 outward metadata 约定

仓库内 Godot 研究副本显示，`GDScriptParser::DataType::to_property_info(...)` 对
 typed dictionary 的发布方式是：

- `type = Variant::DICTIONARY`
- `hint = PROPERTY_HINT_DICTIONARY_TYPE`
- `hint_string = key_hint + ";" + value_hint`

关键参考：

- `tmp/godot-upstream/modules/gdscript/gdscript_parser.cpp:5421-5486`

其中：

- builtin leaf 用 `Variant::get_type_name(...)`
- native / class / script leaf 用 class name / global class name
- `Variant` leaf在 hint string 中写成 `"Variant"`
- 两侧都为 `Variant` 时，不再发布 typed dictionary hint

### 2.2 Godot analyzer 依赖 `hint_string` 重建 typed dictionary

`GDScriptAnalyzer` 在读取 outward property info 时，会在：

- `type == Variant::DICTIONARY`
- `hint == PROPERTY_HINT_DICTIONARY_TYPE`

的前提下，从 `hint_string` 的 `key;value` 两段恢复 key/value 类型。

关键参考：

- `tmp/godot-upstream/modules/gdscript/gdscript_analyzer.cpp:5831-5884`

结论：

- 只发布 `type = DICTIONARY` 不足以恢复 typed dictionary
- 必须同时发布 `PROPERTY_HINT_DICTIONARY_TYPE + hint_string`

### 2.3 Godot runtime 有专门的 typed dictionary mismatch 错误家族

`GDScript VM` 对：

- `expected == Variant::DICTIONARY`
- `actual.get_type() == Variant::DICTIONARY`

的 invalid argument，会输出专门的 typed dictionary mismatch 文案：

- `The dictionary of argument ... does not have the same element type as the expected typed dictionary argument.`

关键参考：

- `tmp/godot-upstream/modules/gdscript/gdscript_vm.cpp:172-177`

这意味着 backend 如果在 wrapper 中把：

- `r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT`
- `r_error->expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY`

用于 typed-dictionary mismatch，那么 Godot 会自动落到专用错误文案，而不是 generic
`Cannot convert argument ... to Dictionary`

### 2.4 gdextension-lite 已经提供 typed-dictionary runtime 支撑

仓库内 gdextension-lite 研究副本显示，当前可直接复用以下 API：

- `godot_Dictionary_is_same_typed(...)`
- `godot_Dictionary_is_typed_key(...)`
- `godot_Dictionary_is_typed_value(...)`
- `godot_Dictionary_get_typed_key_builtin(...)`
- `godot_Dictionary_get_typed_value_builtin(...)`
- `godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant(...)`

关键参考：

- `tmp/inspect_gdlite/generated/variant/dictionary.h:55-65`
- `tmp/inspect_gdlite/generated/variant/dictionary.h:16`
- `tmp/godot-src-scan/core__extension__gdextension_interface.cpp:1319-1325`

结论：

- backend 无需自己发明 typed-dictionary 状态机
- runtime gate 应尽量复用 Godot / gdextension-lite 已有比较能力，而不是手写一套 key/value
  builtin/class/script 对比矩阵

---

## 3. 设计决策（先定边界，避免过度实现）

### 3.1 只修 outward ABI，不改物理 C ABI 形状

TypedDictionary ABI 修复**不应**尝试把 C 侧 ABI 形状拆成新的实体类型。

保持不变的部分：

- `godot_TypedDictionary(key, value)` 继续只是 `godot_Dictionary` 的宏别名
- `renderGdTypeName(GdDictionaryType)` 继续返回 `"Dictionary"` 用于 pack/unpack/copy/destroy
- `renderPackFunctionName(...)` / `renderUnpackFunctionName(...)` 继续走：
  - `godot_new_Dictionary_with_Variant`
  - `godot_new_Variant_with_Dictionary`

原因：

1. runtime value 本身的 typedness 已经存储在 Godot `Dictionary` 实例内部
2. 从 `Variant` 复制出 `Dictionary` 并不会天然丢失 typedness
3. 当前问题是 metadata 发布与 wrapper gate，不是值包装函数名错误

### 3.2 不引入新的“metadata strategy 抽象层”

遵循当前仓库和 `AGENTS.md` 约束，本修复不应引入额外接口/策略类。

推荐做法：

- 继续以 `CGenHelper.renderBoundMetadata(...)` 为唯一 outward metadata 收口点
- 只在 `CGenHelper` 内增加**窄而私有**的辅助方法，例如：
  - `renderTypedDictionaryHintString(...)`
  - `renderOutwardHintAtom(...)`

不建议：

- 新增 `BoundMetadataStrategy` / `ContainerAbiMetadataStrategy` 之类只有一个实现的抽象层

### 3.3 typed dictionary metadata 与 typed dictionary runtime gate 必须一起落地

只补 `PROPERTY_HINT_DICTIONARY_TYPE + hint_string` 仍然不够：

- GDScript analyzer / property list 会恢复 typed dictionary
- 但 `call_func` wrapper 仍可能接受 wrong-typed / plain dictionary 进入 native body

只补 runtime gate 也不够：

- direct call / property outward surface 仍然是 plain `Dictionary`

因此最小完整修复面必须同时包括：

1. outward metadata
2. `call_func` typed gate
3. 对应的单测与 runtime 集成测试

### 3.4 `Variant` leaf 的 metadata 编码与 runtime-gate 编码要区分

对于 `Dictionary[Variant, T]` / `Dictionary[T, Variant]`：

- outward `hint_string` 的 leaf 文本必须是 `"Variant"`
- runtime preflight gate 的“期望 leaf”编码里，对应 side 仍应使用：
  - `GDEXTENSION_VARIANT_TYPE_NIL`
  - empty class name
  - `NULL` script

原因：

- Godot outward metadata 用 `"Variant"` 作为 typed-container leaf 的文本编码
- 但 `Dictionary::set_typed(...)` / `is_same_typed(...)` 的 runtime 内部约定里，
  untyped / Variant side 仍由 `Variant::NIL` 表示

这两条编码不能混淆。

### 3.5 只允许 outward 可表达的 leaf 类型进入 hint renderer

当前 frontend 源语法本来就不正式支持 nested structured container leaf，例如：

- `Dictionary[String, Array[int]]`
- `Dictionary[String, Dictionary[int, String]]`

因此本修复应明确：

- 以下 leaf 允许进入 typed-dictionary outward hint renderer：
  - primitive / builtin / packed array / `Variant`
  - engine object
  - GDCC class object
  - plain `Array`
  - plain `Dictionary`
- 以下 leaf 不允许静默降级发布：
  - non-generic `Array[T]`
  - non-generic `Dictionary[K, V]`
  - `void`
  - 缺失 outward metadata 的未知 leaf

### 3.6 `call_func` typed gate 必须前移为 preflight validation phase

当前生成的 `call_func` wrapper 是：

1. argument count gate
2. base exact type gate
3. 参数 local 物化
4. native call
5. wrapper-local cleanup epilogue

这条结构与 typed-dictionary gate 的相容方式只有一种最稳妥的方案：

- 在 base `type == DICTIONARY` gate 之后、任何 wrapper-owned 参数 local 物化之前，
  完成 typed-dictionary preflight validation
- mismatch 直接写 `r_error` 后 `return`
- 只有所有 typed-dictionary 参数都通过 preflight，才进入现有参数解包和成功路径 cleanup

这样做的原因是：

- 当前 wrapper cleanup contract 只覆盖成功路径上已经物化的 wrapper local
- 若把 typed gate 放在参数解包之后，再试图复用统一 cleanup label，需要额外引入：
  - partial initialization tracking
  - `argN` / `r` / `ret` 活跃位
  - 更复杂的 failure cleanup 结构
- 这会让本修复从 ABI fidelity 问题膨胀成 wrapper 生命周期框架重构

因此当前计划明确：

- **不**为 typed-dictionary gate 引入统一 cleanup label / fail path
- **不**在 typed-dictionary gate 这一阶段改写现有 wrapper 成“带活跃标记的状态机”
- 只有未来确实出现“必须在 wrapper-owned local 物化之后才能判断”的 gate，
  才再单独设计 liveness-tracked cleanup framework

### 3.7 preflight gate 的开销边界与避免 `expected` 分配的优先路线

preflight validation 至少会引入一个“探针 dictionary”本地值：

- `probeN = godot_new_Dictionary_with_Variant(...)`

这一步无法避免，因为 gdextension-lite 对 typed metadata 的 getter 都挂在 `godot_Dictionary`
上，而不是直接挂在 `Variant` 上。

但“再额外构造一个 `expectedN` dictionary 做 `is_same_typed(...)` 对比”是可选的。

当前仓库内 Godot 研究副本显示：

- `Dictionary()` 会 `memnew(DictionaryPrivate)`：
  - `tmp/inspect_godot/dictionary.cpp:769-772`
- `Dictionary(base, typed...)` 也会 `memnew(DictionaryPrivate)`：
  - `tmp/inspect_godot/dictionary.cpp:757-762`
- Godot VM 在多处 typed-dictionary 校验路径里，直接比较：
  - `get_typed_key_builtin/class_name/script`
  - `get_typed_value_builtin/class_name/script`
  而不是先构造一个“expected dictionary”：
  - `tmp/godot-upstream/modules/gdscript/gdscript_vm.cpp:917-920`
  - `tmp/godot-upstream/modules/gdscript/gdscript_vm.cpp:1511-1514`
  - `tmp/godot-upstream/modules/gdscript/gdscript_vm.cpp:2900-2903`

据此，本计划将实现优先级明确为：

1. **首选**：preflight 中解包 `probeN` 后，直接用 typed metadata getter 与“编译期期望 leaf”
   做精确比较
2. **备选**：若实现期发现 getter 路径无法覆盖当前支持语义，再退回
   `expectedN + godot_Dictionary_is_same_typed(...)`

对当前修复范围，getter 路径是更合适的默认方案，因为：

- 它去掉了每个 typed-dictionary 参数上一笔确定发生的 `DictionaryPrivate` 分配/释放
- 仍然能与 Godot 现有 typed metadata 比较模型对齐
- 当前计划本来就**不处理 script leaf outward metadata**，因此比较矩阵仍然足够窄：
  - builtin / packed array / plain `Array` / plain `Dictionary`
  - `Variant`（`NIL`）
  - engine object / GDCC class object（`OBJECT + class_name + NIL script`）

实施约定：

- 不要把 getter 比较逻辑散落在模板里到处拼接
- 若采用 getter 路线，应收口到单一 helper/模板片段，保持比较字段与 destroy 责任清晰
- 对非 `OBJECT` 期望 leaf，不读取 `class_name` / `script` getter，避免无意义临时值
- 对 `OBJECT` 期望 leaf：
  - 比较 builtin 是否为 `OBJECT`
  - 比较 `class_name`
  - 断言 returned script 为 `NIL`

### 3.8 该额外开销在当前路径上总体可接受

即使最终首版仍临时采用 `expectedN + is_same_typed(...)`，这笔开销在当前路径上仍属可接受，
原因是：

- 发生位置仅限 ordinary dynamic `call_func` wrapper
- `ptrcall` ABI 及其热路径完全不受影响
- `call_func` 本来就已经承担：
  - argument count 检查
  - exact runtime type gate
  - `Variant -> wrapper` unpack
  - return pack/copy
- typed-dictionary 参数在 ordinary bound method 中属于较窄子集，不是通用参数热路径

因此这里的正确性优先级高于“零额外检查成本”，但在不损失语义对齐的前提下，
仍应优先去掉 `expected` 分配这一笔确定成本。

一旦出现不支持的 leaf：

- backend codegen 必须 fail-fast
- 错误信息需要明确指出“TypedDictionary outward metadata cannot encode nested structured leaf ...”

这样可以避免把不可表达的类型静默发布成错误 ABI。

### 3.6 继续维持 property `class_name` 非目标

当前 property registration 仍把 `class_name` 槽位保留在现有 owner-class 形态。

本计划不扩展到：

- 全面规范 property `class_name`
- object property 非 container metadata 统一化

TypedDictionary ABI 当前只修：

- `type`
- `hint`
- `hint_string`
- method/property setter runtime gate

避免把 scope 扩到与本问题无关的 property metadata 重构。

---

## 4. 分步骤实施计划（含验收细则）

### Phase A：收口 TypedDictionary outward metadata 生成

**A1. 扩展 `renderBoundMetadata(...)` 的 typed-dictionary 分支**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
- 目标：
  - 当 `type instanceof GdDictionaryType` 且不是 generic `Dictionary[Variant, Variant]` 时：
    - `typeEnumLiteral = GDEXTENSION_VARIANT_TYPE_DICTIONARY`
    - `hintEnumLiteral = godot_PROPERTY_HINT_DICTIONARY_TYPE`
    - `hintStringExpr = GD_STATIC_S(u8"<key>;<value>")`
    - `usageExpr` 继续沿用 caller 传入的 base usage
- 当前结果：
  - `renderBoundMetadata(...)` 已对非 generic typed dictionary 发布 `PROPERTY_HINT_DICTIONARY_TYPE`
  - `hint_string` 现由 helper 生成 `GD_STATIC_S(u8"key;value")`
  - generic `Dictionary[Variant, Variant]` 继续保持 `PROPERTY_HINT_NONE + ""`
- 验收：
  - `Dictionary[StringName, Node]` -> `StringName;Node`
  - `Dictionary[StringName, Variant]` -> `StringName;Variant`
  - `Dictionary[Variant, Variant]` 仍保持 `PROPERTY_HINT_NONE + ""`

**A2. 新增窄辅助方法，禁止继续拿 `renderGdTypeName(...)` 拼 `hint_string`**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
- 新增私有 helper 建议：
  - `renderTypedDictionaryHintString(GdDictionaryType type)`
  - `renderOutwardHintAtom(GdType type, String useSite)`
- 规则：
  - builtin / packed / `Variant` -> 对应 outward 文本名
  - engine / GDCC object -> class name
  - generic `Array` / generic `Dictionary` -> `"Array"` / `"Dictionary"`
  - typed nested structured container -> fail-fast
- 当前结果：
  - `renderTypedDictionaryHintString(...)` 与 `renderOutwardHintAtom(...)` 已落地
  - `renderGdTypeName(...)` 继续只服务内部符号命名，不再参与 typed-dictionary outward `hint_string`
  - typed nested `Array[...]` / `Dictionary[..., ...]` 与缺 metadata leaf 现在统一 fail-fast，并带稳定的 leaf/use-site 错误信息
- 验收：
  - 不允许复用 `renderGdTypeName(...)` 作为 typed-dictionary `hint_string` 的实现基础
  - 错误消息能明确指出不支持的 leaf 与 use-site

**A3. 为 metadata helper 补齐正反向单测**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CGenHelperTest.java`
- 建议新增用例：
  - positive：
    - `Dictionary[StringName, Node]`
    - `Dictionary[StringName, Variant]`
    - `Dictionary[Variant, PackedInt32Array]`
  - negative：
    - `Dictionary[String, Array[int]]` outward hint fail-fast
    - `Dictionary[String, Dictionary[int, String]]` outward hint fail-fast
    - `Dictionary[void, int]` / 缺 metadata leaf fail-fast
- 当前结果：
  - `CGenHelperTest` 已覆盖 typed dictionary positive / generic / negative 分支
  - fail-fast message 通过稳定字符串断言锚定 leaf 类型、use-site 与失败原因
- 验收：
  - generic 与 typed 分支都被覆盖
  - fail-fast message 稳定且可识别

### Phase B：让 method / return / property outward surface 真实发布 typed dictionary metadata

**B1. method argument / return metadata 自动吃到 typed-dictionary 新 helper**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/main/c/codegen/template_451/entry.h.ftl`
- 说明：
  - `entry.h.ftl` 已经通过 `helper.renderBoundMetadata(...)` 生成 args/return metadata
  - 这里的主要工作是校对生成结果和必要注释，不应再散落新的硬编码逻辑
- 当前结果：
  - method argument 与 return metadata 继续统一收口到 `renderBoundMetadata(...)`
  - `entry.h.ftl` 现在显式注释 typed dictionary outward metadata 也走同一 helper，不再把模板误导成 `Variant`-only 合同
  - 生成路径保持无新增硬编码分支，typed dictionary 仍通过 helper 发布 `type/hint/hint_string`
- 验收：
  - typed dictionary method arg / return 都出现：
    - `GDEXTENSION_VARIANT_TYPE_DICTIONARY`
    - `godot_PROPERTY_HINT_DICTIONARY_TYPE`
    - 正确 `hint_string`

**B2. property registration 自动吃到 typed-dictionary 新 helper**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/main/c/codegen/template_451/entry.c.ftl`
- 说明：
  - property registration 已经走 `renderPropertyMetadata(...)`
  - 这里主要是确认模板注释和生成结果与新合同一致
- 当前结果：
  - property registration 继续统一收口到 `renderPropertyMetadata(...)`
  - `entry.c.ftl` 注释已更新为 typed dictionary / Variant 共用 metadata helper 合同
  - property `class_name` 仍保持当前 owner-class 槽位，避免把 typed dictionary leaf 语义误放进顶层 `class_name`
- 验收：
  - typed dictionary property bind 调用包含：
    - `GDEXTENSION_VARIANT_TYPE_DICTIONARY`
    - `godot_PROPERTY_HINT_DICTIONARY_TYPE`
    - 正确 `hint_string`

**B3. 为 codegen 输出补齐结构感知测试**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`
- 建议新增覆盖：
  - method arg metadata
  - method return metadata
  - property metadata
  - generic `Dictionary` 不应误发 `PROPERTY_HINT_DICTIONARY_TYPE`
  - typed dictionary mixed `Variant` side 的 `hint_string` 要正确
- 当前结果：
  - `CCodegenTest` 新增 method/property typed dictionary outward metadata 结构感知测试
  - method 侧分别锚定了：
    - `entry.c.ftl` bind 调用传入 `GDEXTENSION_VARIANT_TYPE_DICTIONARY`
    - `entry.h.ftl` helper body 发布 `PROPERTY_HINT_DICTIONARY_TYPE + hint_string`
    - generic `Dictionary` 继续保持 plain metadata，不误发 typed hint
  - property 侧分别锚定了：
    - `Dictionary[StringName, Node]`
    - `Dictionary[Variant, PackedInt32Array]`
    - generic `Dictionary[Variant, Variant]`
  - mixed-`Variant` hint string 已覆盖：
    - `StringName;Variant`
    - `Variant;PackedInt32Array`
- 已验证：
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest`
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CConstructInsnGenTest,CConstructInsnGenEngineTest`
- 验收：
  - 测试应使用已有 `assertContainsAll(...)` / 定位 helper，避免整段长文本脆弱匹配

### Phase C：为 typed-dictionary 参数补上 `call_func` runtime gate

**C1. 保留当前 base type gate，不改变 generic `Dictionary` 行为**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/main/c/codegen/template_451/entry.h.ftl`
- 规则：
  - 所有非 `Variant` 参数仍先做现有 `type == <gdExtensionType>` gate
  - `Dictionary[...]` 先保留：
    - `type == GDEXTENSION_VARIANT_TYPE_DICTIONARY`
- 当前结果：
  - `call_func` 仍先保留原有 base `DICTIONARY` gate
  - generic `Dictionary[Variant, Variant]` 继续只做 base gate，不进入 typed-dictionary 二段校验
- 验收：
  - non-dictionary 参数行为不回归
  - generic `Dictionary` 仍只有 base `DICTIONARY` gate

**C2. 对 typed dictionary 参数增加 typedness 二段 gate**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/main/c/codegen/template_451/entry.h.ftl`
  - 如需要，少量 helper 可加到 `CGenHelper`
- 推荐实现：
  1. 在现有 base `type == GDEXTENSION_VARIANT_TYPE_DICTIONARY` gate 之后，
     但在真正 `argN` 参数 local 物化之前，进入 typed-dictionary preflight block
  2. 仅对静态类型是 typed dictionary 的参数：
     - 临时解包出 `godot_Dictionary probeN`
       - 继续使用 `godot_new_Dictionary_with_Variant(...)`
     - 使用 getter 路线比较其 typed metadata 与编译期期望 leaf：
       - `godot_Dictionary_get_typed_key_builtin(...)`
       - `godot_Dictionary_get_typed_value_builtin(...)`
       - 对 `OBJECT` leaf 再比较：
         - `godot_Dictionary_get_typed_*_class_name(...)`
         - `godot_Dictionary_get_typed_*_script(...)`
     - 比较完成后立即 destroy preflight 临时值
  3. 若 mismatch：
     - `r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT`
     - `r_error->expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY`
     - `r_error->argument = <index>`
     - 直接 `return`
  4. 仅当所有 typed-dictionary 参数都通过 preflight，才开始现有 `argN` 参数 local 物化

- 关键设计点：
  - getter 比较逻辑允许存在，但必须收口在单一 helper/模板片段，而不是散落成多份局部拼接
  - getter 比较字段应对齐 Godot VM 当前做法，而不是另 invent 一套 widened rule
  - `expectedN + godot_Dictionary_is_same_typed(...)` 保留为 fallback，不是首选默认实现
- 当前调查结论：
  - getter 直比路线已经实装并实际跑过一次整类 runtime 集成验证，但会把来自 GDScript 的 exact `Dictionary[StringName, Node]`
    误判为 mismatch，因此不能作为最终实现
  - 随后切换到 `expectedN + godot_Dictionary_is_same_typed(...)` fallback，并补上真实 nil script `Variant`
    实参；该路线在 codegen / 单测层稳定，但在 Godot 4.5.1 的 ordinary `call_func` 入口首次运行时会统一触发
    crash，而不是返回 mismatch
  - 当前高价值结论不是“fallback 已可用”，而是：
    1. exact typed dictionary 的 runtime fidelity 问题真实存在
    2. `is_same_typed(...)` fallback 在当前 ordinary wrapper 环境里仍需进一步定向探针确认
    3. 后续验证必须改为 targeted unit test / 临时 probe，避免继续整类重跑
  - 新增最小 GDScript probe 结论（2026-04-12）：
    - 命令：
      - `rtk C:\Application\Godot\4.5.1\Godot_v4.5.1-stable_win64.exe --path E:\Projects\gdcc\tmp\test\typed_dict_metadata_probe --headless --quit-after 10`
    - 对 exact 与 `Variant` roundtrip 后的 `Dictionary[StringName, Node]`，`get_typed_*_script()` 同时满足：
      - `== null`
      - `typeof(...) == 24`
      - `is Object == false`
    - 这把根因从“typed metadata 本身退化”进一步收敛为：
      - script getter 返回的是**语义上为 null 的 OBJECT Variant**
      - 旧实现里把 script null 错判成“必须 `godot_variant_get_type(...) == NIL`”会误拒 exact typed dictionary
  - 新增 targeted runtime probe 结论（2026-04-12）：
    - `Dictionary[StringName, int]` 的 method exact/plain probe 与 `Dictionary[StringName, Node]` 一样，都会在 exact 正例的首个 `target.call(...)`
      上 crash；因此 crash **不是 object leaf 专有问题**
    - 新增 no-touch probe：
      - method 参数仍是 `Dictionary[StringName, int]`
      - body 完全不读取 `payloads`，只直接返回常量
      - 该 probe 通过，证明：
        1. wrapper typed preflight 本身可以放行 exact typed dictionary
        2. wrapper 参数 materialize / wrapper cleanup 也不是当前 crash 的直接来源
        3. crash 更靠近“函数体第一次真正读取 typed Dictionary 值”这一段
    - 查看生成的 crashing `entry.c` 后，函数体在 `payloads.size()` 前会先构造/重绑定一个 typed-dictionary 本地临时：
      - `godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant(...)`
      - 当前仍给 key/value script 位置传原始 `NULL`
    - 这把阻塞点进一步收敛为：
      - `CBuiltinBuilder` 里的 typed-dictionary 构造路径此前仍沿用 `NULL` script 指针
      - 而前面的 runtime 调查已经表明，这条构造/typed metadata 路径应改成真实 nil `Variant`
- 当前实现进展：
  - `CBuiltinBuilder.constructDictionary(...)` 已改为：
    - 先声明 key/value script 对应的 wrapper-local nil `Variant` temp
    - 再把这两个 temp 作为 typed dictionary constructor 的 script 实参传入
  - 这一步的目标不是改变 outward ABI，而是消除“函数体首次读取 typed Dictionary 前，本地重建 typed dictionary 时仍沿用旧 `NULL` contract”这一实现偏差
- 最终结果：
  - `entry.h.ftl` 已在 base `DICTIONARY` gate 之后、参数 local 物化之前插入 typed-dictionary preflight
  - `CGenHelper.renderTypedDictionaryCallGuard(...)` 已统一生成 getter 路线比较逻辑：
    - builtin leaf 比较 `get_typed_*_builtin()`
    - object leaf 继续比较 `get_typed_*_class_name()`
    - object leaf script 通过 `godot_variant_evaluate(... == nil)` 识别 Godot 返回的 null-object script metadata
  - `CBuiltinBuilder` 的 nil-`Variant` script 实参修复已打通“函数体首次读取 typed Dictionary 值”路径，不再复现先前 exact 正例 crash
- 已验证：
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryScalarMethodAbiNoTouchRuntimeProbe" --no-daemon --info --console=plain`
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryScalarMethodAbiAcceptsExactAndRejectsPlainDictionaryAtRuntime" --no-daemon --info --console=plain`
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryMethodAbiAcceptsExactAndRejectsPlainDictionaryAtRuntime" --no-daemon --info --console=plain`

**C3. 保持现有 wrapper cleanup 合同不变**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/main/c/codegen/template_451/entry.h.ftl`
  - 如需要，更新相关注释文档
- 原因：
  - typed gate 已前移到 wrapper-owned 参数 local 物化之前
  - 因此 mismatch 允许直接 `return`，不会绕过任何已有 wrapper-local cleanup
- 推荐实现：
  - 明确保留现有成功路径 cleanup 顺序：
    1. `ret`
    2. `r`
    3. reverse-order `argN`
  - preflight block 自己负责销毁它临时创建的：
    - `probeN`
    - `class_name` / `script` getter 返回值（如果该比较路径创建了本地 wrapper）
  - 不引入统一 cleanup label / fail path
- 当前结果：
  - typed-dictionary preflight 仍被固定在 wrapper-owned 参数 local 物化之前
  - success path 的 `ret -> r -> reverse argN` cleanup 顺序未改
  - object-leaf 比较中引入的 `class_name` / `script` / `nil` / `result` 临时值都在 preflight block 内自清理，
    没有把现有 wrapper cleanup 扩展成活跃位状态机
- 验收：
  - typed-gate negative path 不会新增 wrapper-local 泄漏
  - 现有成功路径 cleanup 顺序相关测试无需因本修复而改写为更复杂结构

**C4. 为 wrapper gate 补齐 codegen 单测**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`
- 建议新增用例：
  - typed dictionary arg emits:
    - preflight `probeN = godot_new_Dictionary_with_Variant(...)`
    - typed metadata getter comparison
    - mismatch -> `expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY`
    - mismatch path occurs before real `argN` materialization
  - generic `Dictionary` arg does **not** emit typed gate
  - typed-gate negative path does **not** require cleanup label
- 验收：
  - wrapper 结构被结构感知断言锚定，而不是靠整函数长文本匹配
- 当前结果：
  - `CCodegenTest` 已锚定：
    - typed dictionary 参数 preflight 位于真实 `argN` 物化之前
    - generic `Dictionary` 不会误生成 typed gate
    - 当前 getter 路线中的 builtin/class/script 比较片段与 cleanup 片段
  - 相关既有 typed dictionary ctor codegen 测试保持通过
- 已验证：
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest,CConstructInsnGenTest,CConstructInsnGenEngineTest`

### Phase D：用独立 runtime 集成测试锚定 ABI 合同

**D1. method parameter positive / negative**

- 状态：已完成（2026-04-12）
- 修改：
  - `src/test/java/dev/superice/gdcc/backend/c/build/FrontendLoweringToCProjectBuilderIntegrationTest.java`
    或新增同目录独立 integration test class
- 建议正例：
  - `accept_exact_typed_dict(payloads: Dictionary[StringName, Node])`
  - GDScript 传入同 typedness 的字典，native body 可达
- 建议反例：
  - 传入 plain `Dictionary`
  - 传入 key/value typedness 不一致的 typed dictionary
- 验收：
  - 正例通过
  - 反例以**行为锚点**验收，而不是依赖 Godot 具体错误文案：
    - native body 内的显式 side-effect marker 不出现，证明 wrapper 在调用边界拒绝了参数
    - 调用后的显式 `after bad call` marker 不出现，证明脚本控制流没有把错误调用当成成功返回继续执行
    - 集成测试 harness 观察到运行失败通道（例如 Godot 进程/脚本执行结果被标记为失败），而不是正常成功结束
  - 若需要检查日志，只允许匹配非常粗粒度且长期稳定的类别信号，例如：
    - `Invalid type in function`
    - 或等价的 invalid-argument 分类提示
    不要把完整 typed-dictionary 专用报错句子写进断言
- 当前调查结论：
  - 独立 integration test class 已经建立，并分别覆盖 exact / plain / wrong-typed 三类 method 参数情形
  - `Dictionary[StringName, Node]` 与 `Dictionary[StringName, int]` 的 exact 正例现在都能抵达 native body
  - plain `Dictionary` 与 wrong-typed `Dictionary[StringName, RefCounted]` 都会在 guard 处被拒绝，并在 `after bad call`
    之前截断脚本控制流
  - 负例断言已收敛为：
    - 失败点前 marker 存在
    - 失败点后 marker 不存在
    - 输出中存在稳定的 Godot 类型拒绝类别信号
- 已验证：
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryMethodAbiAcceptsExactAndRejectsPlainDictionaryAtRuntime" --no-daemon --info --console=plain`
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryMethodAbiRejectsWrongTypedDictionaryAtRuntime" --no-daemon --info --console=plain`
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryScalarMethodAbiAcceptsExactAndRejectsPlainDictionaryAtRuntime" --no-daemon --info --console=plain`

**D2. method return outward fidelity**

- 状态：已完成（2026-04-12）
- 建议正例：
  - extension method 返回 `Dictionary[StringName, Node]`
  - GDScript 侧将其接入 typed 变量或用 typedness 可观测接口验证
- 验收：
  - outward return metadata 足以让 GDScript 侧观察到 typed dictionary，而不是 plain `Dictionary`
- 当前调查结论：
  - return metadata codegen 已就位，并在 `entry.h` 结构断言中固定为
    `PROPERTY_HINT_DICTIONARY_TYPE + "StringName;Node"`
  - runtime 正例已通过：
    - 返回值在 GDScript 侧仍保持 `Dictionary[StringName, Node]` 的 typed metadata
    - direct typed binding 与 runtime class 观测都通过
- 已验证：
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryReturnAbiBuildNativeLibraryAndRunInGodot" --no-daemon --info --console=plain`

**D3. property get/set outward fidelity**

- 状态：已完成（2026-04-12）
- 建议正例：
  - typed dictionary property `var payloads: Dictionary[StringName, Node]`
  - direct property get/set 都能保持 typedness
- 建议反例：
  - property set 传入 wrong-typed / plain dictionary
- 说明：
  - auto-generated `_field_setter_*` 是 ordinary bound method，因此 method wrapper typed gate
    应自动覆盖 property set 路径
  - property registration metadata 仍需要单独正确发布，确保 analyzer / editor / direct property
    outward surface 不退化成 plain `Dictionary`
- 验收：
  - property 正反例都通过
  - writable-route 相关测试不需要再承担 typed-dictionary ABI 的回归职责
- 当前调查结论：
  - property metadata codegen 已就位，并在 `entry.c` 结构断言中固定为
    `PROPERTY_HINT_DICTIONARY_TYPE + "StringName;Node"`
  - property set 通过 auto-generated setter 进入同一 ordinary method wrapper，因此其 typed gate 仍复用 method 路径
  - direct property get/set 正例已通过：
    - typed metadata 保持为 `StringName;Node`
    - direct get 与 `read_payload_size()` 两条观测都通过
  - 新增 targeted property plain-set probe 结论（2026-04-12）：
    - 负例在 `before bad set` 之后、`after bad set` 之前中止，说明 setter 路径的拒绝行为已经生效
    - 但 Godot 对该路径输出的粗粒度错误类别是
      `Invalid assignment of property or key`
      而不是 method call 更常见的
      `Invalid type in function`
    - 因此 property 负例测试应锚定“稳定的类型拒绝类别 + 控制流被截断”，而不是强绑 method-call 专用文案
- 已验证：
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiBuildNativeLibraryAndRunInGodot" --no-daemon --info --console=plain`
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiRejectsPlainDictionarySetAtRuntime" --no-daemon --info --console=plain`
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiRejectsWrongTypedDictionarySetAtRuntime" --no-daemon --info --console=plain`

**D4. 当前运行时调查策略**

- 状态：已完成并归档（2026-04-12）
- 规则：
  - 不再继续整类重跑 `FrontendLoweringToCTypedDictionaryAbiIntegrationTest`
  - 后续优先：
    - 添加最小临时 probe
    - 或拆成单个 targeted test method
    来确认 ordinary wrapper crash 的最小触发点
- 已知失败命令（仅记录一次，避免重复消耗）：
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendLoweringToCTypedDictionaryAbiIntegrationTest`
- 已新增调查事实：
  - `tmp/test/typed_dict_metadata_probe/test_script.gd` 已确认 `get_typed_*_script()` 的 null 语义不是 `TYPE_NIL`，
    而是 `TYPE_OBJECT` 的 null object；后续 targeted runtime 验证应围绕这一点，而不是继续扩散到整类 crash 面
  - no-touch probe 已确认当前 crash 发生在函数体首次真实读取 typed Dictionary 值时，而不是 wrapper preflight 本身
  - 不要并行启动多个 Gradle `test` 任务来跑这些 probe：
    - `build/test-results/test/binary/output.bin` 会发生锁冲突
    - 后续应继续按单个 test method 串行执行
- 最终结果：
  - “先文档化调查结论，再跑单个 targeted probe”的策略足以完成本轮 Phase C / D 收口
  - 不需要再回到整类 integration test 重跑

### Phase E：文档与回归面归档

**E1. 更新长期事实源与 backend 提醒文档**

- 状态：已完成（2026-04-12）
- 修改：
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
- 目标：
  - 明确 typed dictionary outward ABI 合同已从“后续工程”转入“已实现事实”
  - 说明 `Variant` ABI 与 typed dictionary ABI 的边界
- 当前结果：
  - `doc/gdcc_c_backend.md` 已新增 typed-dictionary outward ABI 提醒段，明确：
    - metadata 合同
    - runtime preflight 合同
    - nil `Variant` script carrier 合同
    - 与 `Variant` ABI 的边界
  - 新增 `doc/module_impl/backend/typed_dictionary_abi_contract.md` 作为长期事实源，收口：
    - outward metadata 编码
    - `call_func` runtime gate
    - typed-dictionary 本地重建
    - helper / template 分工
    - regression 基线与非目标
  - `doc/module_impl/backend/variant_abi_contract.md` 已改为引用独立 typed-dictionary 合同，避免继续把 typed dictionary 写成“后续工程”
- 已验证：
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest,CConstructInsnGenTest`
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryMethodAbiAcceptsExactAndRejectsPlainDictionaryAtRuntime" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryMethodAbiRejectsWrongTypedDictionaryAtRuntime" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryReturnAbiBuildNativeLibraryAndRunInGodot" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiBuildNativeLibraryAndRunInGodot" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiRejectsPlainDictionarySetAtRuntime" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiRejectsWrongTypedDictionarySetAtRuntime" --no-daemon --info --console=plain`

**E2. 从调查文档回填结论**

- 状态：已完成（2026-04-12）
- 修改：
  - `doc/test_error/frontend_variant_and_typed_dictionary_abi.md`
- 目标：
  - 把 typed dictionary 部分从“待调查问题”更新为“问题链路 + 已落地修复点 + 剩余非目标”
- 当前结果：
  - 文档顶部状态已更新为：
    - ordinary typed-dictionary method / return / property ABI 已修复
    - writable-route fixture 仍应避免把 typed-dictionary ABI acceptance 混入同一用例
  - 历史问题段已明确区分：
    - fix 前 exported shape
    - fix 后 current backend shape
    - 为什么 writable-route fixture 仍保持 plain `Dictionary`
  - 文档已补充当前 dedicated regression 面：
    - `CConstructInsnGenEngineTest`
    - `FrontendLoweringToCTypedDictionaryAbiIntegrationTest`
- 已验证：
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest,CConstructInsnGenTest`
  - `rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryMethodAbiAcceptsExactAndRejectsPlainDictionaryAtRuntime" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryMethodAbiRejectsWrongTypedDictionaryAtRuntime" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryReturnAbiBuildNativeLibraryAndRunInGodot" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiBuildNativeLibraryAndRunInGodot" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiRejectsPlainDictionarySetAtRuntime" --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedDictionaryAbiIntegrationTest.lowerFrontendTypedDictionaryPropertyAbiRejectsWrongTypedDictionarySetAtRuntime" --no-daemon --info --console=plain`

---

## 5. 推荐测试矩阵

### 5.1 helper / codegen 层

- `CGenHelperTest`
  - typed dictionary metadata 正例
  - generic dictionary 不误标 typed
  - nested structured leaf fail-fast
- `CCodegenTest`
  - method arg / return / property metadata
  - typed dictionary wrapper gate
  - cleanup path

### 5.2 runtime / integration 层

- `FrontendLoweringToCProjectBuilderIntegrationTest`
  - method parameter positive / negative
  - method return typedness
  - property get/set positive / negative

### 5.3 现有测试基线不应回归

- `CConstructInsnGenEngineTest`
  - typed dictionary construction path
- `CConstructInsnGenTest`
  - typed dictionary ctor codegen

推荐命令：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest,CConstructInsnGenTest,CConstructInsnGenEngineTest,FrontendLoweringToCProjectBuilderIntegrationTest
```

---

## 6. 风险与注意事项

### 6.1 最危险的半修复：只补 metadata，不补 wrapper gate

这种情况下：

- analyzer / property list 看起来已恢复 typed dictionary
- 但 dynamic `call_func` 仍接受 wrong-typed / plain dictionary 穿透

这会制造“静态 surface 正确、runtime gate 仍偏离 Godot”的隐蔽回归。

### 6.2 另一个危险的半修复：只补 wrapper gate，不补 return/property metadata

这种情况下：

- dynamic call 可能恢复
- 但 direct method/property outward surface 仍然是 plain `Dictionary`

同样不符合 Godot 语义。

### 6.3 不要把 typed gate 放到 wrapper-owned 参数 local 物化之后

当前 `call_func` wrapper 的 cleanup 合同是围绕成功路径线性结构建立的。

只要 typed gate 放在参数解包之后，就会立刻引入：

- partial initialization
- failure path destroy 范围与 success epilogue 不一致
- 为 `argN` / `r` / `ret` 引入活跃位或分段 cleanup 的压力

因此本计划把 typed gate 固定为 preflight validation phase。

只有在未来确实出现“必须依赖已物化 wrapper local 才能判断”的 gate 时，
才允许另起设计，显式引入 liveness-tracked cleanup framework。

### 6.4 不要把 `renderGdTypeName()` 当成 outward typed metadata 的来源

`renderGdTypeName(GdDictionaryType)` 的职责是：

- pack / unpack / copy / destroy 的符号归一

它的设计目标从来不是：

- 保留 strict typed container outward ABI 细节

继续拿它拼 `hint_string` 会再次把 typed dictionary 静默压平成 `"Dictionary"`。

### 6.5 当前计划不处理 script leaf metadata

Godot typed container 的完整模型还支持：

- `class_name`
- `script`

而 GDCC 当前 `GdType` 模型没有单独区分“脚本类型 leaf”与“类名 object leaf”的 outward ABI
发布需求。

本计划先覆盖：

- builtin
- engine object
- GDCC class object
- `Variant`
- plain `Array` / `Dictionary`

### 6.6 `expected` dictionary 分配不是语义必需项

若采用：

- `probeN + expectedN + godot_Dictionary_is_same_typed(...)`

则 `expectedN` 的构造会额外引入一笔确定发生的 `DictionaryPrivate` 分配/释放。

这条路径在语义上可行，但不是必须的，因为 Godot VM 自己已有直接比较 typed metadata
字段的现成模式。

因此实现时应优先：

- 解包 `probeN`
- 直接比较 builtin / class_name / script metadata

而不是默认先创建 `expectedN`。

如果未来 frontend 引入更细粒度的 script leaf 类型，需要再扩展 typed-container ABI 合同。

---

## 7. 非目标

- 顺手修 typed array outward ABI
  - 两者可以复用部分 leaf-hint 渲染逻辑，但本计划不把 typed array 一并并入
- 修改 frontend ordinary boundary / LIR shape
- 发明新的物理 C ABI 类型来替代 `godot_Dictionary`
- 全面重构 property `class_name` 槽位
- 把 writable-route continuation 测试重新改回 typed-dictionary 外部边界夹杂式验收

---

## 8. 一句话落地原则

TypedDictionary ABI 修复的正确方向是：

- **保持 value copy/pack/unpack 继续用 plain `godot_Dictionary` 路径**
- **把 strict typedness 放回 metadata 与 wrapper runtime gate**
- **让错误再次沿 Godot 既有 typed-dictionary mismatch 文案暴露出来**

而不是把问题误判成“需要新的 typed dictionary C 物理类型”。
