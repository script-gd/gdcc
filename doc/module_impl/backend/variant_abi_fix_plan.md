# Variant 外部 ABI 修复计划

> 目标：修复 `gdcc` 当前 GDExtension 出口 ABI 对 source-level `Variant` 的错误编码，使方法参数、返回值和属性在 Godot 侧都表现为真正的 `Variant`，而不是 `Nil` / `void` / `null-like` 槽位。
>
> 本文档是本次修复工作的实施计划文档，保留分步骤任务、验收细则、风险与跟进范围。
>
> 校对基线：2026-04-11（以当前代码库为准）

---

## 1. 背景与问题定义

### 1.1 当前观测到的错误

当前代码库中，source-level `Variant` 在 frontend 内部 ordinary boundary 上已经有明确语义：

- ordinary typed boundary 的兼容判定由
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendVariantBoundaryCompatibility.java`
  共同约束
- `Variant -> concrete` / `concrete -> Variant` 的语义已经通过 `PackVariantInsn` / `UnpackVariantInsn` 建模

因此，这次故障不在 frontend ordinary boundary，而在 backend 导出的 GDExtension ABI。

`doc/test_error/frontend_variant_and_typed_dictionary_abi.md` 已确认以下现实问题：

1. `Variant` 参数通过 `target.call(...)` 传入非空值时，会在 generated call wrapper 中被当成 `Nil` 精确类型位点拒绝。
2. `Variant` 返回值和 `Variant` 属性当前没有用 Godot 约定的 metadata 表示为真正的 `Variant` surface。
3. typed dictionary 也存在 ABI metadata fidelity 问题，但它是另一条独立修复线，不应混入本次 `Variant` patch。

### 1.2 当前故障链路

基于当前实现，故障链路如下：

1. backend 绑定模板把 `GdVariantType` 的 outward metadata type 发射为 `GDEXTENSION_VARIANT_TYPE_NIL`。
2. `entry.h.ftl` 生成的 `call_*` wrapper 对所有参数一视同仁，执行：
   - `actual_type != declared_type` 则报 `GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT`
3. 对 `Variant` 参数来说，这意味着 wrapper 会执行：
   - `actual_type != GDEXTENSION_VARIANT_TYPE_NIL`
4. 于是任何非空 `Variant` payload，例如 `PackedInt32Array`、`Dictionary`、`Color`，都会在 native body 之前被拒绝。
5. Godot 最终把该错误格式化为：
   - `Cannot convert argument ... to Nil`

此外：

- 参数位 metadata 目前没有补 `PROPERTY_USAGE_NIL_IS_VARIANT`
- 返回值位 metadata 也没有补该 flag
- 属性注册路径同样没有补该 flag

因此问题不是单点 bug，而是：

- outward metadata 编码不完整
- dynamic `call_func` wrapper 的 runtime gate 与 Godot `Variant` 约定不一致

---

## 2. 语义锚点与修复边界

### 2.1 Godot 对 `Variant` outward metadata 的约定

根据 Godot upstream 实现，本次修复应对齐以下 contract：

- source-level `Variant` outward slot 采用：
  - `type = Variant::NIL`
  - `usage |= PROPERTY_USAGE_NIL_IS_VARIANT`
- 参数位、返回值位、属性位都可以通过该约定恢复为真正的 `Variant`

可对齐的上游锚点包括：

- `modules/gdscript/gdscript_parser.cpp`
- `modules/gdscript/gdscript_analyzer.cpp`
- `modules/gdscript/gdscript_utility_functions.cpp`
- `modules/gdscript/gdscript_vm.cpp`

### 2.2 本次修复范围

本次 patch 只修复 **Variant outward ABI**，覆盖：

- extension method 参数 metadata
- extension method 返回值 metadata
- extension property metadata
- generated `call_func` wrapper 对 `Variant` 参数的 runtime gate

### 2.3 明确非目标

本次 patch 不做：

- 修改 frontend ordinary boundary compatibility 规则
- 修改 `PackVariantInsn` / `UnpackVariantInsn` 语义
- 为 LIR / frontend IR 新增通用 hint / usage / class_name 元数据字段
- 在同一 patch 中同时修复 typed dictionary ABI
- 放宽非 `Variant` 参数的 runtime 精确类型检查
- 改动 `ptrcall` 的物理 C ABI 形状

### 2.4 typed dictionary 的处理原则

typed dictionary 问题必须后续单独修复，原因如下：

- 它依赖 `PROPERTY_HINT_DICTIONARY_TYPE` 与 `hint_string`
- 它涉及 outward metadata fidelity，而不是 `Variant` wrapper gate
- 它需要单独的 focused regression，不应污染本次 `Variant` 验收归因

本次 patch 允许为后续 typed container metadata 整理 helper 触点，但不在同一次提交里完成 typed dictionary 语义闭环。

---

## 3. 当前实现落点

本次修复的主要实现触点预计集中在：

- method binding template：
  - `src/main/c/codegen/template_451/entry.h.ftl`
- property registration template：
  - `src/main/c/codegen/template_451/entry.c.ftl`
- property helper：
  - `src/main/c/codegen/include_451/gdcc/gdcc_bind.h`
- backend metadata / type rendering helper：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
- 现有调查与边界说明：
  - `doc/test_error/frontend_variant_and_typed_dictionary_abi.md`
  - `doc/gdcc_c_backend.md`

本次修复原则上不应改动：

- `src/main/java/dev/superice/gdcc/frontend/**`
- `src/main/java/dev/superice/gdcc/lir/**`

除非只是补充文档注释说明“frontend ordinary boundary 不拥有 outward ABI 语义”。

---

## 4. 设计决策（实施前先定）

### 4.1 outward `Variant` slot 的统一编码

对于 outward-facing `Variant` slot，统一采用：

- outward type enum：`GDEXTENSION_VARIANT_TYPE_NIL`
- outward usage：
  - 参数：`godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT`
  - 返回值：`godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT`
  - 属性：在当前 base usage 上额外 OR `godot_PROPERTY_USAGE_NIL_IS_VARIANT`

注意：

- “type 是 `NIL`”是 metadata 编码，不代表 runtime value 只能是 nil
- “真正表示这是 Variant”依赖 `PROPERTY_USAGE_NIL_IS_VARIANT`

### 4.2 `call_func` wrapper 的 `Variant` gate

generated `call_func` wrapper 必须满足：

- 非 `Variant` 参数：
  - 继续保持当前精确 `GDExtensionVariantType` 检查
- `Variant` 参数：
  - 不能执行 `actual_type == NIL` 的精确比较
  - 允许任何 Godot `Variant` payload 进入 wrapper，再按 `godot_new_Variant_with_Variant(...)` 路径拷贝出本地值

这条决策只影响 `call_func`，不影响 `ptrcall` 的物理 ABI。

### 4.3 helper 层的放置策略

为了避免把 backend metadata 问题扩散到 frontend / LIR，本次修复的 metadata 推导应收口到 backend helper 层。

推荐策略：

- 在 `CGenHelper` 中增加少量 outward ABI helper
- 只根据 `GdType` 计算：
  - outward property type enum
  - outward usage expr
  - 未来可扩展的 hint / hint_string / class_name

不建议本次引入：

- 新的公共抽象接口
- 单独的 metadata 大对象层
- 贯穿 `ParameterDef` / `PropertyDef` / `LirParameterDef` 的新字段

### 4.4 本次 patch 的正确完成标准

修复完成后，必须同时满足：

1. `target.call("accept_variant", PackedInt32Array(...))` 能进入 native body。
2. direct method call 的 `Variant` 返回值能被 Godot 正确当成 `Variant` 消费。
3. direct property get/set 的 `Variant` surface 能被 Godot 正确当成 `Variant` 消费。
4. 非 `Variant` 参数仍保持现有严格 runtime gate，不被误放宽。

如果只满足其中一部分，说明 patch 只修了 metadata 或只修了 wrapper，仍不完整。

---

## 5. 分步骤实施计划（含验收细则）

### Phase A：收口 outward ABI metadata helper

**A1. 在 `CGenHelper` 增加 outward metadata helper**

- 修改：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
- 建议新增的 helper 方向：
  - 单一 `renderBoundMetadata(GdType, String baseUsageExpr)` 入口
  - 统一产出：
    - outward type enum
    - outward usage
    - 当前阶段默认 hint enum / hint string / class_name
- 规则：
  - `GdVariantType` -> outward type enum 为 `GDEXTENSION_VARIANT_TYPE_NIL`
  - 其他类型保持当前 `gdExtensionType`
  - `GdVariantType` outward usage 额外 OR `godot_PROPERTY_USAGE_NIL_IS_VARIANT`

状态（2026-04-11）：

- 已完成。
- `CGenHelper` 已改为通过单一 `renderBoundMetadata(...)` 入口统一产出 outward type / usage / 默认 hint / class_name 字面量，避免把同一组字符串拼接拆成多个散落 helper。
- helper 规则仍只依赖 `GdType`，未向 frontend / LIR 扩散 metadata 字段；无 outward metadata 的 `void` 现在会 fail-fast，避免模板层静默拼接出无意义的 enum 字面量。

验收：

- helper 的输出规则只依赖 `GdType`，不引入 frontend / LIR 新字段
- 非 `Variant` 类型生成结果与当前行为保持一致
- `renderGdTypeInC` / `renderGdTypeRefInC` / pack / unpack helper 行为不发生回归性变化

**A2. 明确 property base usage 与 Variant 额外 flag 的组合规则**

- 修改：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
- 当前 `renderPropertyUsageEnum(...)` 只返回：
  - `godot_PROPERTY_USAGE_DEFAULT`
  - `godot_PROPERTY_USAGE_NO_EDITOR`
- 本次要改成：
  - base usage 仍由 export / non-export 决定
  - 但 `Variant` property 需要在 base usage 上再 OR `godot_PROPERTY_USAGE_NIL_IS_VARIANT`

状态（2026-04-11）：

- 已完成。
- `renderPropertyUsageEnum(...)` 现在先保留 export / non-export 的 base usage，再统一通过 `renderBoundMetadata(...)` 为 `Variant` property 追加 `godot_PROPERTY_USAGE_NIL_IS_VARIANT`。
- 单元测试已覆盖 `export/non-export × Variant/non-Variant` 组合，固定“追加 flag 而不改写既有可见性语义”的 contract。

验收：

- 非 `Variant` property usage 保持原样
- `Variant` property usage 变成 `base_usage | godot_PROPERTY_USAGE_NIL_IS_VARIANT`

---

### Phase B：修复 method binding metadata 与 `call_func` wrapper

**B1. 修 `args_info[]` 的 outward metadata 发射**

- 修改：
  - `src/main/c/codegen/template_451/entry.h.ftl`
- 当前模板为每个参数硬编码：
  - `PROPERTY_HINT_NONE`
  - 空 `hint_string`
  - `PROPERTY_USAGE_DEFAULT`
- 本次改为走 `CGenHelper` helper 计算的：
  - outward type enum
  - usage
  - 当前阶段 hint / hint_string / class_name 仍可先保持默认

状态（2026-04-11）：

- 已完成。
- `entry.h.ftl` 的 `args_info[]` 现在统一通过 `renderBoundMetadata(...)` 发射 outward usage / hint / class_name，`Variant` 参数会稳定带出 `godot_PROPERTY_USAGE_NIL_IS_VARIANT`。
- 非 `Variant` 参数仍保持默认 metadata 形态；本阶段没有把 typed-container hint/class_name 逻辑提前混入 method binding。

验收：

- `Variant` 参数的 `args_info[]` 使用 `NIL` + `PROPERTY_USAGE_NIL_IS_VARIANT`
- 非 `Variant` 参数仍保持原 metadata 形态

**B2. 修 `call_func` 对 `Variant` 参数的错误精确检查**

- 修改：
  - `src/main/c/codegen/template_451/entry.h.ftl`
- 当前行为：
  - 所有参数都做 `actual_type != declared_type` 检查
- 本次改为：
  - 仅对非 `Variant` 参数发射该检查
  - `Variant` 参数跳过该检查，直接进入 unpack / copy 流程

状态（2026-04-11）：

- 已完成。
- `entry.h.ftl` 的 `call_*` wrapper 现在只对非 `Variant` 参数保留 `actual_type != declared_type` gate；`Variant` outward slot 不再把 `NIL` 当作 runtime payload 类型去做精确比较。
- 回归测试同时固定了反向约束：`int` 等非 `Variant` 参数仍继续发射严格 gate，避免本 patch 误放宽全部参数检查。

验收：

- 生成的 `entry.h` 中，`Variant` 参数不再出现：
  - `if (type != GDEXTENSION_VARIANT_TYPE_NIL) { ... }`
- 非 `Variant` 参数的精确检查分支仍保留
- `call_func` 的参数数量检查不变

**B3. 修 `return_value_info` 的 outward metadata**

- 修改：
  - `src/main/c/codegen/template_451/entry.h.ftl`
  - 如有必要：
    - `src/main/c/codegen/include_451/gdcc/gdcc_bind.h`
- 当前 return info 走 `gdcc_make_property(...)`，其 usage 固定为 `PROPERTY_USAGE_DEFAULT`
- 本次要让 `Variant` return 也走：
  - `type = NIL`
  - `usage |= PROPERTY_USAGE_NIL_IS_VARIANT`

状态（2026-04-11）：

- 已完成。
- `return_value_info` 已改为走 `gdcc_make_property_full(...) + renderBoundMetadata(...)`，因此 `Variant` 返回值会稳定发布 `NIL + PROPERTY_USAGE_NIL_IS_VARIANT`，而非继续停留在 plain `PROPERTY_USAGE_DEFAULT`。
- `gdcc_bind.h` 在本阶段无需改动；现有 `gdcc_make_property_full(...)` 已足够承载 method return surface 所需 metadata。

验收：

- `Variant` 返回值的 `return_value_info` 不再是 plain `PROPERTY_USAGE_DEFAULT`
- 非 `Variant` 返回值形态不变

---

### Phase C：修复 property registration surface

**C1. 为 property registration 提供完整 metadata 入口**

- 修改：
  - `src/main/c/codegen/include_451/gdcc/gdcc_bind.h`
  - `src/main/c/codegen/template_451/entry.c.ftl`
- 当前 `gdcc_bind_property(...)` 只接收：
  - `type`
  - `usage_flags`
- 对 `Variant` property 来说，这不够表达完整 outward metadata
- 建议：
  - 新增一个 full 版本 property bind helper，允许显式传入：
    - type
    - hint
    - hint_string
    - class_name
    - usage
  - `entry.c.ftl` 改为调用 full 版本

状态（2026-04-11）：

- 已完成。
- `gdcc_bind.h` 已新增 `gdcc_bind_property_full(...)`，property registration 不再受旧 helper 仅接受 `type + usage` 的入口限制。
- `entry.c.ftl` 已切到 `gdcc_bind_property_full(...) + helper.renderPropertyMetadata(property)`，因此 `Variant` property 可以稳定发布 `NIL + PROPERTY_USAGE_NIL_IS_VARIANT`。
- property-class slot 当前仍显式传入 owner `class_name`，避免本 patch 顺手漂移 object/typed property 的既有 `class_name` surface。

验收：

- `Variant` property 注册时 outward type 为 `NIL`
- `Variant` property usage 带 `PROPERTY_USAGE_NIL_IS_VARIANT`
- 非 `Variant` property 行为不回退

**C2. 保持当前 export / non-export 行为不漂移**

- 修改：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
  - `src/main/c/codegen/template_451/entry.c.ftl`
- 目标：
  - `@export` property 仍保留 editor 可见性
  - non-export property 仍保持 `NO_EDITOR`
  - `Variant` 额外 flag 只作为 additive change，不覆盖既有 usage 语义

状态（2026-04-11）：

- 已完成。
- `renderPropertyMetadata(...)` 现在复用 `renderBoundMetadata(...) + renderPropertyBaseUsageEnum(...)`，继续沿用原来的 export / non-export 可见性分流。
- 新增的 `NIL_IS_VARIANT` 只在 `Variant` property 上做 additive 叠加，不会覆盖 `DEFAULT` / `NO_EDITOR` 的既有语义。
- backend 单测与 property runtime integration test 已共同覆盖 `export/non-export × Variant/non-Variant` 组合，防止回归时只修 Variant path 却漂移普通 property surface。

验收：

- export `Variant` property 的 usage 形态为：
  - `DEFAULT | NIL_IS_VARIANT`
- non-export `Variant` property 的 usage 形态为：
  - `NO_EDITOR | NIL_IS_VARIANT`

---

### Phase D：补齐代码生成回归测试

**D1. 增加 `Variant` 参数 wrapper 的 codegen 锚点**

- 修改/新增测试：
  - `src/test/java/dev/superice/gdcc/backend/c/build/FrontendLoweringToCProjectBuilderIntegrationTest.java`
  - 如需要也可新增专用 backend codegen test
- 建议断言：
  - 生成的 `entry.h` 中，`Variant` 参数没有 `!= GDEXTENSION_VARIANT_TYPE_NIL` 分支
  - 同一个文件中，`int` / `Color` / `Vector3` 参数仍保留原有精确检查

状态（2026-04-11）：

- 已完成。
- `CCodegenTest.generatesVariantMethodBindingMetadataAndKeepsNonVariantGate()` 已固定 method wrapper contract：
  - `Variant` 参数 metadata 使用 `NIL + PROPERTY_USAGE_NIL_IS_VARIANT`
  - wrapper 不再发布 `expected = GDEXTENSION_VARIANT_TYPE_NIL`
  - 非 `Variant` 参数仍保留精确 gate
- 断言直接锚定 generated `entry.h` 结构，不依赖 Godot 运行时报错文案。

验收：

- 测试能稳定识别“Variant gate 已放宽，但非 Variant gate 未放宽”
- 断言基于生成代码结构，而不是依赖 Godot 报错文案

**D2. 增加 `Variant` return/property metadata 的 codegen 锚点**

- 建议断言：
  - `Variant` return info 含 `PROPERTY_USAGE_NIL_IS_VARIANT`
  - `Variant` property registration 使用 `PROPERTY_USAGE_NIL_IS_VARIANT`
  - `Variant` outward type 为 `GDEXTENSION_VARIANT_TYPE_NIL`

状态（2026-04-11）：

- 已完成。
- `CGenHelperTest` 已补 helper 级断言，固定 `Variant` property metadata 必须编码为 `NIL + PROPERTY_USAGE_NIL_IS_VARIANT`，同时保持非 `Variant` property enum 不漂移。
- `CCodegenTest.generatesVariantPropertyBindingMetadataAndKeepsNonVariantPropertyShape()` 已补 property registration 的 codegen 锚点，覆盖 hidden/exported × Variant/non-Variant 组合。
- `FrontendLoweringToCProjectBuilderIntegrationTest.lowerFrontendVariantPropertyAbiBuildNativeLibraryAndRunInGodot()` 已进一步证明 direct property set/get 的 runtime surface 也遵守同一 contract，而不是只在字符串层面看起来正确。

验收：

- codegen regression 能防止未来有人只修 wrapper、忘记修 return/property surface

---

### Phase E：补齐 focused runtime integration tests

**E1. dynamic `call()` 入口的 `Variant` 参数测试**

- 建议新增 fixture：
  - `func accept_variant(value: Variant) -> int`
- 测试脚本：
  - `target.call("accept_variant", PackedInt32Array([1, 2]))`
- native body 建议行为：
  - 对 `value` 做最小可观察消费，例如 `size()` / `typeof` / 路由判断，返回确定值

状态（2026-04-11）：

- 已完成。
- `FrontendLoweringToCProjectBuilderIntegrationTest.lowerFrontendVariantMethodAbiBuildNativeLibraryAndRunInGodot()` 已覆盖 `target.call("accept_variant", PackedInt32Array(...))` 的正向入口。
- 运行时断言固定了两件事：
  - native body 确实被执行，而不是在 wrapper 前置 gate 被拦下
  - `read_variant_calls()` 的 side effect 与返回值一起证明 payload 按真实 `Variant` 进入了 native body
- 同一用例还显式断言运行输出中不再出现 `expected = GDEXTENSION_VARIANT_TYPE_NIL` 对应的 `Nil` gate 回归。

验收：

- native body 确实被执行
- 返回值与脚本端断言一致
- 不再出现 `Cannot convert argument ... to Nil`

**E2. direct method call 的 `Variant` 返回值测试**

- 建议新增 fixture：
  - `func echo_variant(value: Variant) -> Variant`
- 测试脚本必须使用 direct call，而不是 `call(...)`
- 建议断言：
  - 返回值可以赋给脚本变量
  - `typeof(...)` 正确
  - payload 可继续被脚本消费

状态（2026-04-11）：

- 已完成。
- 同一集成用例中的 `echo_variant(PackedInt32Array(...))` direct call 已证明：
  - 返回值不会被当作 `void`
  - GDScript 端看到的仍是 `TYPE_PACKED_INT32_ARRAY`
  - payload 内容未在 return surface 上丢失
- 这条覆盖与 dynamic `call()` 路径分离，避免只修 wrapper 却遗漏 return metadata surface。

验收：

- direct call 不把该返回值当作 `void`
- 非空 payload 通过返回路径不丢失

**E3. direct property `Variant` surface 测试**

- 建议新增 fixture：
  - `var payload: Variant`
- 测试脚本：
  - `target.payload = PackedInt32Array([4, 5])`
  - 再直接读取 `target.payload`
- 建议断言：
  - 类型正确
  - 值正确

状态（2026-04-11）：

- 已完成。
- `FrontendLoweringToCProjectBuilderIntegrationTest.lowerFrontendVariantPropertyAbiBuildNativeLibraryAndRunInGodot()` 已固定 direct property set/get 的运行时语义：
  - hidden `Variant` property 与 exported `Variant` property 都能发布并读回非空 payload
  - `read_payload_size()` / `read_visible_payload_size()` 进一步证明 native body 内部读取看到的仍是同一 `Variant` payload
- 同一用例还保留了非 `Variant` property 的正向检查，防止 property metadata 改动顺手漂移普通 property surface。

验收：

- property set/get 两侧都以真正的 `Variant` 语义工作
- 不依赖 setter/getter 的额外绕路把问题掩盖掉

**E4. negative guard**

- 必须保留至少一个非 `Variant` 参数的 negative integration 或 codegen guard：
  - 例如 `int` 参数仍然拒绝 `PackedInt32Array`
- 目的是证明本次 patch 没把全部 runtime gate 一并撤掉

状态（2026-04-11）：

- 已完成。
- codegen 层由 `CCodegenTest.generatesVariantMethodBindingMetadataAndKeepsNonVariantGate()` 持续锚定 `expected = GDEXTENSION_VARIANT_TYPE_INT` 仍存在。
- runtime 层由 `FrontendLoweringToCProjectBuilderIntegrationTest.lowerFrontendNonVariantMethodGuardRejectsPackedArrayAtRuntime()` 继续验证：
  - `target.call("accept_int", PackedInt32Array(...))` 仍然失败
  - Godot 输出保持 `Cannot convert argument 2 from PackedInt32Array to int.`
  - 失败不会退化成 `... to Nil`，从而证明 negative path 仍归因于非 `Variant` 精确 gate，而不是 `Variant` ABI regression

验收：

- 负例仍能稳定失败
- 失败原因仍归因于非 `Variant` 参数严格检查，而不是其他路径

---

### Phase F：文档同步与后续边界说明

**F1. 更新 ABI 调查文档**

- 修改：
  - `doc/test_error/frontend_variant_and_typed_dictionary_abi.md`
- 更新方向：
  - 把 `Variant` 部分从“调查结论”升级成“已明确的修复 contract”
  - 明确说明正确编码：
    - `type = NIL`
    - `usage |= NIL_IS_VARIANT`
    - `call_func` 不得对 Variant slot 做 NIL 精确 gate

状态（2026-04-11）：

- 已完成。
- `doc/test_error/frontend_variant_and_typed_dictionary_abi.md` 现已改写为：
  - 当前 ordinary method/property `Variant` surface 已修复
  - 修复 contract 与运行时回归覆盖已明确写出
  - 剩余 open item 只保留 typed dictionary boundary fidelity
- 文档中的“未来修复方向”也已收束成“已修复的 Variant contract + 独立的 typed dictionary follow-up”，避免继续按过期故障状态推进工程。

验收：

- 文档不再只描述现状 bug，也明确记录修复目标和判定标准

**F2. 更新 backend 总体文档**

- 修改：
  - `doc/gdcc_c_backend.md`
- 建议补充：
  - `Variant outward ABI` 是 GDExtension metadata / wrapper contract
  - 不属于 frontend ordinary lowering boundary
  - typed dictionary ABI fidelity 是单独问题

状态（2026-04-11）：

- 已完成。
- `doc/gdcc_c_backend.md` 已新增 `Variant Outward ABI Contract` 段落，明确：
  - outward `Variant` slot 的 `NIL + PROPERTY_USAGE_NIL_IS_VARIANT` 约定
  - `call_func` 只对 `Variant` 参数跳过 exact type gate，`ptrcall` ABI 不变
  - 该语义是 backend metadata / wrapper contract，不应上移到 frontend / LIR ordinary lowering 规则
- 文档同时把 typed dictionary ABI 单列为后续问题，避免后续工程在错误层级上“顺手修 ABI”。

验收：

- 文档能阻止后续工程去错误修改 frontend boundary helper 来“修 ABI”

**F3. 记录 typed dictionary follow-up 边界**

- 可在本计划或 ABI 调查文档中补一句：
  - 后续 typed dictionary patch 应复用本次整理出的 helper 触点
  - 但测试、验收、风险分析必须独立进行

状态（2026-04-11）：

- 已完成。
- 计划文档、ABI 调查文档与 backend 总体文档现在都统一说明：
  - typed dictionary patch 应复用本次整理出的 backend helper 触点
  - 但它必须拥有独立的测试夹具、验收矩阵与风险归因
- 这样后续 typed dictionary 工程不会再被混入当前 `Variant` ABI regression surface。

验收：

- 后续工程不会把 typed dictionary 再次混入本次 `Variant` 回归面

---

## 6. 最终验收矩阵

实现完成后，至少要满足以下最终验收：

1. generated `entry.h` 中：
   - `Variant` 参数无 `!= GDEXTENSION_VARIANT_TYPE_NIL` gate
   - 非 `Variant` 参数仍有精确 type gate
2. generated metadata 中：
   - `Variant` 参数带 `PROPERTY_USAGE_NIL_IS_VARIANT`
   - `Variant` return 带 `PROPERTY_USAGE_NIL_IS_VARIANT`
   - `Variant` property 带 `PROPERTY_USAGE_NIL_IS_VARIANT`
3. runtime integration 中：
   - `target.call(...)` 传非空 `Variant` 参数可进入 native body
   - direct method call 的 `Variant` 返回值可被脚本继续消费
   - direct property get/set 的 `Variant` surface 可用
4. 回归保护中：
   - 非 `Variant` 参数未被误放宽
   - frontend ordinary boundary 规则无意外漂移

---

## 7. 建议测试命令

实现过程中建议使用定向测试，不跑全量：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendVariantBoundaryCompatibilityTest,FrontendLoweringToCProjectBuilderIntegrationTest
```

如果需要补 backend codegen 专项测试，再增加对应测试类，例如：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendLoweringToCProjectBuilderIntegrationTest,CConstructInsnGenEngineTest
```

若后续把 `Variant` ABI codegen 断言拆成独立测试类，应以该独立测试类替换上面的重型集成测试组合。

---

## 8. 长期风险与维护提醒

1. 最容易出现的半修复是“只补 `PROPERTY_USAGE_NIL_IS_VARIANT`，但忘了去掉 `call_func` 对 `NIL` 的精确 gate”。这种情况下 direct metadata surface 看起来更像 `Variant`，但 `target.call(...)` 仍然会在进入 native body 前失败。
2. 另一种半修复是“只改 wrapper，不改 return/property metadata”。这样动态参数调用会恢复，但 direct method call return / direct property surface 仍然会偏离 Godot 语义。
3. 不要为了修 metadata fidelity 把 hint / usage / class_name 元数据字段一路灌进 frontend / LIR。那会把 backend ABI 问题错误上移，并显著增加后续 typed container patch 的维护成本。
4. typed dictionary 的修复不能顺手混入本次 patch。否则一旦 runtime 崩溃或行为异常，很难区分问题是出在：
   - Variant wrapper gate
   - typed dictionary metadata
   - typed dictionary reconstruction
   - nested writable-route 继续链
5. future patch 若要修 typed array / typed dictionary outward metadata，必须沿用本次形成的 helper 触点，而不是在模板里再次散落硬编码分支。

---

## 9. 计划完成标准

当且仅当以下条件全部满足时，本计划可视为完成：

- `Variant` 参数 / 返回值 / 属性三条 outward surface 都已修复
- 代码生成与 runtime integration 都有稳定回归测试
- backend 文档与 ABI 调查文档已同步
- typed dictionary 被明确保留为后续独立修复项，而非遗留在“顺手以后再看”的模糊状态
