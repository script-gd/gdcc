# Typed Array ABI 修复实施计划

> 本文档记录 `Array[T]` outward ABI 问题的调查结论、修复顺序、实施步骤与验收细则。  
> 它是当前阶段的计划文档，不是最终合同文档；当实现稳定后，应收敛为独立的 typed-array ABI contract。

## 文档状态

- 状态：Investigated / Planned
- 范围：
  - `src/main/java/dev/superice/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
  - `src/test/java/dev/superice/gdcc/backend/c/**`
- 更新时间：2026-04-12
- 关联文档：
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/backend/construct_array_implementation.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`
  - `doc/test_error/frontend_variant_and_typed_dictionary_abi.md`

## 结论摘要

- `typed array` 确实存在与此前 `typed dictionary` 同类的 ordinary outward ABI 问题，而且当前暴露面更差。
- 当前不是单点缺陷，而是两个相互叠加的问题面：
  - outward typedness 丢失：metadata 和 runtime gate 都没有发布 `Array[T]` 的 element-type 语义；
  - local typed-array reconstruction 契约错误：typed-array constructor 仍把 script carrier 传成原始 `NULL`。
- 这两个问题必须按顺序修：
  - 先切断 exact positive path 的 crash；
  - 再补 outward metadata；
  - 最后补 wrapper runtime preflight 和正式回归测试。
- `typed array` 与 `typed dictionary` 共享 `typed_builtin / typed_class_name / typed_script` 三元组语义，但 outward hint grammar 与模板结构不同。
  - 不应把两者重新揉成一个“泛 typed-container 大 abstraction”。
  - 可以复用少量 leaf 解析 helper，但 metadata grammar、guard 触发条件、模板展开形状应保持并列实现。

## 已确认的问题链路

### 问题链 A：outward metadata / runtime gate 缺失

- `CGenHelper.renderBoundMetadata(...)` 当前只 special-case 了：
  - `Variant`
  - non-generic `Dictionary[K, V]`
- `Array[T]` 当前仍统一走 plain `Array` outward metadata：
  - `type = ARRAY`
  - `hint = PROPERTY_HINT_NONE`
  - `hint_string = ""`
- `entry.h.ftl` 当前也没有 typed-array preflight：
  - 只有 base `ARRAY` type gate
  - 没有 `godot_Array_get_typed_builtin(...)`
  - 没有 `godot_Array_get_typed_class_name(...)`
  - 没有 `godot_Array_get_typed_script(...)`
- 结果：
  - plain `Array`
  - wrong-typed `Array`
  - exact `Array[T]`
  在 outward ABI 上被压平为同一类 surface。

### 问题链 B：typed-array 本地重建 script carrier 契约错误

- `CBuiltinBuilder.constructArray(...)` 当前 typed-array 路径调用：
  - `godot_new_Array_with_Array_int_StringName_Variant(...)`
- 第四个参数仍直接传：
  - `NULL`
- 这与 Godot typed-array constructor 的真实契约不一致：
  - script carrier 位置要求的是 `Variant*`
  - 即使语义上是“无脚本”，也应传真实 nil `Variant`
- 当前 crash 不是只出现在“错误 typedness 被放过”之后。
  - exact typed-array positive path 也会崩；
  - property 路径甚至会在 node/class construction 阶段更早崩。

## 当前调查基线

### 临时测试

- 调查探针：
  - `src/test/java/dev/superice/gdcc/backend/c/build/FrontendLoweringToCTypedArrayAbiInvestigationTest.java`
- 已确认的现象：
  - metadata 探针：生成物中不存在 `PROPERTY_HINT_ARRAY_TYPE`
  - method exact positive probe：在 ABI 边界上触发 `signal 11`
  - property exact positive probe：在 validation script 到达第一条 marker 前就崩溃
  - property init probe：生成代码中仍出现
    `godot_new_Array_with_Array_int_StringName_Variant(..., NULL)`

### 上游 Godot 证据

- `tmp/godot-src/modules/gdscript/gdscript_parser.cpp`
  - `Array[T]` outward metadata 使用 `PROPERTY_HINT_ARRAY_TYPE`
  - `hint_string` 按 leaf 类型编码为单个 atom，而不是 dictionary 那样的 `key;value`
- `tmp/godot-src/modules/gdscript/gdscript_vm.cpp`
  - typed-array mismatch 文案为：
    `does not have the same element type as the expected typed array argument`
  - method argument rebuild 对 typed array 使用：
    `Array array(..., arg_type.script_type)`
  - runtime typedness 对比使用：
    `get_typed_builtin()`
    `get_typed_class_name()`
    `get_typed_script()`
- `tmp/test/c_build/include/gdextension-lite/generated/variant/array.h`
  - 已有 `is_typed`
  - 已有 `get_typed_builtin`
  - 已有 `get_typed_class_name`
  - 已有 `get_typed_script`

### `gdparser` 副本核对结果

- `E:/Projects/gdparser` 中能看到 `Array[int]`、`Array[String]` 等 source-level 示例。
- 但未发现 backend outward ABI、property hint 生成、runtime guard 相关实现。
- 结论：
  - `gdparser` 对语法层 sample 有参考价值；
  - 对本次 backend ABI 修复计划帮助有限，不作为主要实现依据。

## 修复总原则

### 1. 先止血，再补 fidelity

- 先修 typed-array constructor 的 nil-script carrier。
- 原因：
  - 当前 exact positive path 已经会崩；
  - 如果先写 negative ABI guard 测试，失败很容易被更早的 constructor crash 污染，无法归因。

### 2. helper 保持判定逻辑，模板承担大段 C 结构

- `CGenHelper` 适合承载：
  - 是否需要 guard
  - leaf builtin type literal
  - object leaf class-name literal
  - outward hint atom 渲染与 fail-fast 判定
- `entry.h.ftl` 适合承载：
  - typed-array preflight block 的整体结构
  - 临时变量生命周期
  - error return 分支
- 不在 `CGenHelper` 中回退成大段字符串拼接器。

### 3. 保持与 typed dictionary 并列，而不是强行统一

- 可以复用 leaf-level 的小 helper。
- 不应引入一个只有一处调用、但把 array 与 dictionary 两种 grammar 全塞进去的“统一 typed container renderer”。
- 需要分别维护：
  - typed-array outward hint grammar
  - typed-array template preflight shape
  - typed-dictionary outward hint grammar
  - typed-dictionary template preflight shape

### 4. 当前阶段明确不承诺 script leaf 支持

- Godot 上游支持 script leaf。
- 但当前 backend 刚完成 typed-dictionary ABI 修复，script leaf 仍被明确排除在合同外。
- 为了保持两类 typed container 的边界一致，typed-array 首轮修复也应：
  - 明确写出 script leaf 不支持；
  - 在 helper 处 fail-fast；
  - 不能静默降级为 plain object leaf 或 plain `Array`。
- script leaf fail-fast 的错误契约必须具体，而不是只写一句“不支持”：
  - 至少包含 element type 名称；
  - 至少包含 use-site 标签：
    - `method arg`
    - `method return`
    - `property`
  - 至少包含拒绝原因：
    - 当前 typed-array ABI 不支持 script leaf，因为这要求发布非 nil 的 `typed_script` 身份。
- 这条错误契约同样适用于其他当前明确拒绝的 typed-array leaf。
  - 只是 script leaf 的原因文本必须特别指出 `typed_script` 非 nil 这一点。

### 5. typed-array preflight 不使用 `is_same_typed`

- `gdextension-lite` 虽然提供了 `godot_Array_is_same_typed(...)`，但本计划明确不把它作为 wrapper preflight 的实现基线。
- 原因不是“功能上做不到”，而是性能与维护边界都不合适：
  - 当前 wrapper 已经直接拿到 incoming array 的 typed metadata；
  - expected typedness 也是编译期已知的 `builtin / class_name / script` 三元组；
  - 直接比较 `get_typed_*` 字段比再走一次 `is_same_typed(...)` 更便宜；
  - 也避免为了比较而额外构造临时 probe array 或引入额外 helper 调用。
- 这条约束要明确写入计划，避免后续围绕“为什么不用 `is_same_typed`”反复扯皮。
- 除非后续有明确 profiling 证据证明 `is_same_typed(...)` 更优，否则 typed-array preflight 保持字段级直接比较。

## 分阶段实施计划

### Phase A：修复 typed-array constructor 的 nil-script carrier

### 目标

- 切断 exact typed-array positive path 的 crash。
- 让 typed-array 的本地重建与 Godot 的 constructor 契约对齐。

### 执行状态

- [x] A1. `CBuiltinBuilder.constructArray(...)` 已改为为 typed array 显式 materialize nil `Variant` script carrier，并在调用后销毁 temp。
- [x] A2. 已补齐 Phase A 单元测试，覆盖显式构造、`__prepare__` 自动注入、property default helper 自动注入，以及 generic array 保持原路径。
- [x] A3. 已运行 targeted tests，并确认 Phase A 止血目标成立。

### 当前已完成结果

- typed-array constructor 现在与 typed-dictionary constructor 保持同一条 nil-script carrier 契约：
  - typed `Array[T]` 不再把 script 位传原始 `NULL`
  - 改为传真实 nil `Variant`
- 这条修复已经由三条代码路径共同锚定：
  - 显式 `construct_array`
  - `__prepare__` 自动注入
  - property default helper 自动注入
- generic `Array[Variant]` 保持原 plain constructor 路径：
  - 不会误生成 typed-array script temp
  - 不会误走 typed-array constructor
- 当前 targeted validation 结果：
  - `CConstructInsnGenTest` 通过
  - `FrontendLoweringToCTypedArrayAbiInvestigationTest` 通过
- Phase A 新确认的事实：
  - exact typed-array method/property 正例已不再因 constructor crash 提前中断
  - outward metadata 仍然缺失 `PROPERTY_HINT_ARRAY_TYPE`
  - wrapper typed-array preflight 仍然缺失
  - 因此 Phase B / Phase C 仍然是必要的，不是可选清理项

### 实施步骤

1. 修改 `CBuiltinBuilder.constructArray(...)`：
   - typed `Array[T]` 路径不再把第四个参数写成 `NULL`
   - 改为显式 materialize 一个真实 nil `Variant` temp
   - 将该 temp 传给 `godot_new_Array_with_Array_int_StringName_Variant(...)`
   - 构造完成后按现有 temp 生命周期规则销毁该 temp
2. 保持 generic `Array[Variant]` 现有普通构造路径不变。
3. 不为此引入新的全局 abstraction。
   - 若需要 helper，只保留最小的 nil-script temp 物化逻辑。
4. 确认该修复自动覆盖以下路径：
   - `construct_array` 指令显式构造
   - `__prepare__` 中 `GdArrayType` 自动注入
   - property default helper 中 `GdArrayType` 自动注入

### 验收细则

- codegen 层：
  - 生成的 typed-array constructor 不再包含 `..., NULL)`
  - 生成代码中存在真实 nil `Variant` temp 的创建与销毁
- integration 层：
  - exact typed-array method call 不再因 constructor crash 提前中断
  - exact typed-array property 场景不再在 node construction 阶段崩溃
- 本阶段暂不要求：
  - outward metadata 已发布 typed-array hint
  - plain/wrong-typed array 已被 wrapper 正确拦截

### Phase B：建立 typed-array outward metadata 合同

### 目标

- 为 non-generic `Array[T]` 发布与 Godot 一致的 outward metadata。
- 保持 generic `Array[Variant]` outward surface 仍为 plain `Array`。

### 实施步骤

1. 在 `CGenHelper.renderBoundMetadata(...)` 中增加 typed-array 分支：
   - non-generic `Array[T]`：
     - `type = GDEXTENSION_VARIANT_TYPE_ARRAY`
     - `hint = godot_PROPERTY_HINT_ARRAY_TYPE`
     - `hint_string = "<elem>"`
   - generic `Array[Variant]`：
     - 继续保持 `PROPERTY_HINT_NONE`
     - 不发布 typed hint
2. 新增 typed-array outward hint atom 渲染 helper。
   - 只负责单个 leaf atom
   - 不在 helper 中拼整段 C 模板字符串
   - helper 需要接收明确的 use-site 标签，至少覆盖：
     - `method arg`
     - `method return`
     - `property`
   - helper fail-fast 时，错误信息至少包含：
     - element type 名称
     - use-site
     - unsupported 原因
3. 明确 typed-array 首轮支持的 outward leaf：
   - primitive / builtin（不含 `Variant`）
   - packed array
   - engine object / GDCC object
   - plain `Array`
   - plain `Dictionary`
   - `Array[Variant]` 不属于这里的 typed leaf 支持列表。
     - 它按 generic `Array` 处理；
     - 不进入 typed-array hint atom 渲染；
     - 不发布 typed-array hint
4. 明确首轮 fail-fast 的 leaf：
   - script leaf
   - nested typed `Array[T]`
   - nested typed `Dictionary[K, V]`
   - `void`
   - 缺失 outward metadata 的未知 leaf
5. 复用 `renderPropertyMetadata(...)`，让 method arg / return / property registration 共享同一套 typed-array metadata 合同。

### 验收细则

- helper/codegen 层：
  - non-generic `Array[StringName]`、`Array[Node]`、`Array[Array]` 会生成 `PROPERTY_HINT_ARRAY_TYPE`
  - generic `Array[Variant]` 不会误生成 typed hint
  - nested typed leaf / script leaf 会 fail-fast，而不是静默压平
  - script leaf fail-fast 错误会显式带出：
    - element type 名称
    - use-site
    - `requires non-nil typed_script` 这一拒绝原因
- integration 层：
  - return / property outward metadata 能被 Godot 识别为 typed array surface
- 文档层：
  - 明确写出“script leaf 不支持”

### Phase C：建立 typed-array wrapper runtime preflight

### 目标

- 让 non-generic `Array[T]` 参数在 `call_func` wrapper 中拥有与 Godot 对齐的 typedness 校验。
- 保持 generic `Array[Variant]` 仍只做 base `ARRAY` gate。

### 实施步骤

1. 在 `CGenHelper` 增加 typed-array guard 所需的小 helper：
   - `needsTypedArrayCallGuard(...)`
   - `renderTypedArrayGuardBuiltinTypeLiteral(...)`
   - `isTypedArrayGuardObjectLeaf(...)`
   - `renderTypedArrayGuardClassNameExpr(...)`
2. 在 `entry.h.ftl` 中新增 typed-array preflight block。
   - 先保留原有 base `ARRAY` type gate
   - 再仅对 `needsTypedArrayCallGuard(...) == true` 的参数展开 typed-array guard
   - 不要无条件先调用 typed-array guard helper 再判断参数类型
3. typed-array preflight 的比较语义对齐 Godot：
   - builtin leaf：比较 `godot_Array_get_typed_builtin(...)`
   - object leaf：继续比较
     - `godot_Array_get_typed_class_name(...)`
     - `godot_Array_get_typed_script(...)`
   - null script 语义沿用 typed-dictionary 已验证的处理方式：
     - 用 `Variant == nil` 判断 null-object script metadata
     - 不能把它误写成 `TYPE_NIL`
   - 明确不使用 `godot_Array_is_same_typed(...)`：
     - 这里直接比较 `get_typed_*` 三元组即可；
     - 避免额外 helper 调用和不必要的热路径开销
4. mismatch 行为保持现有 wrapper invalid-argument 合同：
   - `r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT`
   - `r_error->expected = GDEXTENSION_VARIANT_TYPE_ARRAY`
   - `r_error->argument = <index>`
5. preflight 必须发生在 wrapper-owned unpack locals 物化之前。
   - 避免引入 partially materialized locals 的第二套 cleanup 规则。

### 验收细则

- codegen 层：
  - non-generic typed array 参数会生成 `godot_Array_get_typed_*` preflight
  - typed-array preflight 不会退化成 `godot_Array_is_same_typed(...)`
  - generic `Array[Variant]` 不会误生成 typed-array preflight
  - typed-array preflight 仍位于 unpack locals 之前
- integration 层：
  - exact `Array[T]` method call 可以通过
  - plain `Array` 传入 typed `Array[T]` 会在 ABI 边界被拒绝
  - wrong-typed `Array[U]` 传入 typed `Array[T]` 会在 ABI 边界被拒绝
  - property setter 通过 auto-generated setter wrapper 共享同一套 guard 语义

### Phase D：建立正式回归测试并回收调查探针

### 目标

- 用稳定、可归因的 regression surface 接管当前临时调查测试。
- 保证 method / return / property / prepare / default-init 五个触点都有正反向锚点。

### 实施步骤

1. helper-level 测试：
   - `CGenHelperTest`
   - 覆盖 typed-array metadata 正例
   - 覆盖 generic array 不误走 typed hint / typed guard
   - 覆盖 nested typed leaf / script leaf fail-fast
   - 覆盖 unsupported 错误文本至少包含：
     - element type 名称
     - use-site
     - `requires non-nil typed_script`
2. codegen-level 测试：
   - `CCodegenTest`
   - 断言 method arg / return / property metadata
   - 断言 typed-array wrapper preflight 结构
   - 断言 preflight 位于 unpack 之前
   - `CConstructInsnGenTest`
   - 断言 typed-array constructor 使用 nil `Variant` script carrier，而不是 `NULL`
3. integration-level 测试：
   - 新建正式 typed-array ABI integration test
   - 覆盖：
     - method parameter exact positive
     - method parameter plain negative
     - method parameter wrong-typed negative
     - return outward fidelity
     - property get/set exact positive
     - property plain negative
     - property wrong-typed negative
4. 临时测试处理策略：
   - `FrontendLoweringToCTypedArrayAbiInvestigationTest` 在正式回归落地后不应继续承担主回归职责
   - 可选择删除，或仅保留最小调查用途并从常规 targeted test 组合中移出

### 验收细则

- regression surface 能清晰区分：
  - metadata fidelity 失败
  - runtime guard 失败
  - constructor/local reconstruction 失败
- 不再依赖“崩溃前有没有打印 marker”作为唯一验证手段。
- 正例与反例都能稳定锚定行为：
  - 正例证明 exact typed-array 可达且可用
  - 反例证明 plain / wrong-typed array 会被 ABI 边界拒绝

## 建议测试顺序

### 现状调查确认

> 当前调查测试的方法名表达的是“现状仍然有缺陷”的事实，  
> 它们只适合在修复前确认基线，不应作为修复后的长期回归面。

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendLoweringToCTypedArrayAbiInvestigationTest
```

### Phase A - Phase C 开发期

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CConstructInsnGenTest
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest,CConstructInsnGenTest
```

### Phase D 完成时

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest,CConstructInsnGenTest
rtk .\gradlew.bat test --tests "dev.superice.gdcc.backend.c.build.FrontendLoweringToCTypedArrayAbiIntegrationTest" --no-daemon --info --console=plain
```

## 风险与止损顺序

1. 最危险的半修复是“只补 metadata，不修 constructor crash”。
   - 表面上 analyzer/property list 可能看起来正确；
   - 但 exact positive path 仍会在更早阶段崩溃。
2. 第二危险的半修复是“只补 constructor，不补 runtime guard”。
   - 这样 exact 正例也许能跑通；
   - 但 plain / wrong-typed array 仍会被错误放行。
3. 如果把 typed-array 大段 C 模板拼接重新塞回 helper，会迅速降低模板可读性，并重演此前 typed-dictionary 修复过程中已经出现过的维护问题。
4. 如果把 typed-array 与 typed-dictionary 强行抽象成统一 grammar，很容易把：
   - array 的单 atom hint_string
   - dictionary 的双侧 `key;value` hint_string
   - array 的单侧 guard
   - dictionary 的双侧 guard
   混成一个难以维护的大分支。

## 最终交付定义

当以下条件同时满足时，本计划可视为完成并转入 contract 文档收敛阶段：

- exact typed-array method/property 正例不再崩溃；
- non-generic `Array[T]` 的 method arg / return / property metadata 已发布 `PROPERTY_HINT_ARRAY_TYPE`；
- `call_func` wrapper 对 non-generic `Array[T]` 已执行 typed-array preflight；
- generic `Array[Variant]` 仍保持 plain `Array` outward surface；
- script leaf / nested typed leaf 的边界已被明确写入文档并通过测试锚定；
- 正反向 targeted tests 均稳定通过；
- 临时调查测试已被正式 regression surface 接管。

## 实施后的文档收敛目标

- 本计划完成后，应新增独立的长期合同文档，例如：
  - `doc/module_impl/backend/typed_array_abi_contract.md`
- 合同文档只保留：
  - 当前实现事实
  - 长期边界
  - 测试锚点
  - 风险提醒
- 本计划文档届时应保留为阶段记录，不再继续承载最新事实源。
