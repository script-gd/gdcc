# Frontend `String` / `StringName` 隐式转换实施计划

> 创建时间：2026-05-28
>
> 本文档记录在现有 `int -> float` 与同维度 `Vector*i -> Vector*` 隐式转换基础上，
> 增加 `String` 与 `StringName` 双向隐式转换的调研结论、推荐设计、分阶段实施步骤与验收细则。
> 二次调研结论：这两条转换可以通过现有 `construct_builtin` LIR 与 Godot generated builtin constructor wrapper
> 解决，不需要新增 backend intrinsic；落地后的长期事实源仍应回写到对应矩阵、lowering 与 backend 文档。

## 1. 范围

本计划的完成条件包含两条必须同步落地的支持面，不能把其中一条单独标记为完成。

第一条是 source-level ordinary typed boundary 上的两条转换：

- `String -> StringName`：推荐 lowering 为 `$<tmp> = construct_builtin $<string_source>`，tmp 类型为 `StringName`。
- `StringName -> String`：推荐 lowering 为 `$<tmp> = construct_builtin $<string_name_source>`，tmp 类型为 `String`。

第二条是 GDExtension `call_func` 入站 wrapper parity：

- 目标参数为 `StringName` 时，入站 `Variant(STRING_NAME)` 继续 exact unpack，`Variant(STRING)` 先 unpack 为
  `String`，再通过 `StringName(String)` constructor materialization 成目标值。
- 目标参数为 `String` 时，入站 `Variant(STRING)` 继续 exact unpack，`Variant(STRING_NAME)` 先 unpack 为
  `StringName`，再通过 `String(StringName)` constructor materialization 成目标值。

这两条支持面都属于同一个 `String` / `StringName` 隐式转换 feature gate。source-level compile path 与
Godot dynamic call 入站 wrapper 任何一侧缺失，都不满足本计划的完成标准。
本文档中的 “parity” 只表示这两条支持面之间的完成度对齐：

- source-level ordinary typed boundary
- GDExtension `call_func` inbound wrapper

它不表示 GDCC 已经与 Godot 的所有 string-family 行为对齐。以下事实必须单独保持：

- operator 行为不是本 feature 的 parity 范围；`String` / `StringName` 的 `+`、`==`、`!=`
  等由 unary/binary operator metadata 与 operator lowering 合同决定，不由 ordinary typed boundary
  或 inbound wrapper 放宽。
- explicit cast 不是本 feature 的 parity 范围；`CastExpression` / `CastItem` 仍按现有 lowering
  unsupported 合同处理。
- `NodePath -> String` 不是本 feature 的 parity 范围；`String -> NodePath`、`NodePath -> String`
  等相邻 Godot strict conversion 仍保持 GDCC 当前不支持状态。

这些边界通过现有 shared frontend boundary helper 统一进入以下 consumer：

- local initializer
- class property initializer
- ordinary assignment / property store
- fixed call argument
- return slot
- typed container subscript key/index 与 value write boundary
- constructor / method overload ranking 中已经复用 frontend boundary rank 的路径

本计划不包含：

- `String <-> NodePath`
- `NodePath -> String`
- `String -> Color`、`int -> Color` 等其它 Godot strict conversion
- 显式 `cast` / `as` / `is` lowering
- builtin keyed metadata access 的新支持面，例如 `vector["x"]`
- unary / binary operator 语义扩面
- 修改 `ClassRegistry.checkAssignable(...)`

## 2. 现状结论

### 2.1 已有类型与字面量能力

`String` 与 `StringName` 已经是一等 `GdType`：

- `GdStringType`
- `GdStringNameType`
- `GdStringLikeType`
- `ClassRegistry.tryParseStrictTextType(...)` 中的 `String` / `StringName` 解析
- string literal 与 string-name literal 已经分别发布为 `GdStringType.STRING` 与 `GdStringNameType.STRING_NAME`

因此本任务不是新增类型建模，而是在 ordinary typed boundary 上补双向非 direct conversion。

### 2.2 当前兼容性入口

现有 `int -> float` 与 `Vector*i -> Vector*` 的统一入口是：

- `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)`
- `FrontendVariantBoundaryCompatibility.isFrontendBoundaryCompatible(...)`
- `FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(...)`

该 helper 当前只把以下非 direct boundary 放行：

- stable type -> `Variant`
- stable `Variant` -> concrete target
- `Nil -> object`
- `int -> float`
- 同维度 `Vector*i -> Vector*`

其它 concrete-slot 边界最终回退 `ClassRegistry.checkAssignable(...)`。`ClassRegistry.checkAssignable(...)`
必须继续保持 strict assignability，不承载 frontend-only widening。

### 2.3 当前 lowering 入口

普通边界物化已经集中在：

- `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`
- `FrontendBodyLoweringSession.requireIntrinsicCastName(...)`

该路径已经被 local init、assignment、call argument、return、subscript key/index 等 consumer 复用。
新增 `String <-> StringName` 不应在各 consumer 里单独写分支，而应继续扩展这一条公共路径。

`requireIntrinsicCastName(...)` 只适合继续承载 `int -> float` 与 `Vector*i -> Vector*` 这类
`CallIntrinsicInsn` 路线。`String <-> StringName` 应在同一个 materialization 入口中新增一条
constructor materialization 分支，而不是塞进 intrinsic cast name 表。

### 2.4 当前 constructor backend 入口

C backend 已经有普通 builtin constructor 调用链：

```text
ConstructBuiltinInsn
  -> ConstructInsnGen
  -> CBuiltinBuilder.constructBuiltin(...)
  -> generated godot_new_<Target>_with_<Source>(...)
```

`construct_builtin` 的 result type 来自目标变量，参数必须是变量 operand。`CBuiltinBuilder.constructBuiltin(...)`
继续按 target type 与参数 `ValueRef.type()` 做 exact constructor metadata 匹配；本任务不在 backend constructor
matcher 中加入任何隐式转换规则。

这意味着 `String <-> StringName` 可以复用 `ConstructBuiltinInsn` 与现有 generated wrapper，但不能复用
显式 constructor call resolver 的语义，也不能通过放宽 `CBuiltinBuilder` 来让 backend 自己猜 widening。

### 2.5 当前入站 wrapper 入口

GDExtension `call_func` wrapper 的生成入口集中在：

- `src/main/c/codegen/template_451/entry.h.ftl`
- `CGenHelper.renderCallWrapperVariantTypeGate(...)`
- `CGenHelper.renderCallWrapperUnpackExpr(...)`
- wrapper-local helper `gdcc_new_Vector*_from_call_arg_variant(...)`

模板当前顺序是：先校验参数数量，再缓存 `argN_type`，再用 `renderCallWrapperVariantTypeGate(...)`
做 runtime type gate，typed-container preflight 通过后才用 `renderCallWrapperUnpackExpr(...)`
物化 wrapper-local 参数值。错误边界属于 generated wrapper gate；helper 只是 gate 之后的 materializer。

现有 `Vector*i -> Vector*` 入站 parity 不是简单依赖 `godot_new_Vector*_with_Variant(...)`。
`gdcc_new_Vector*_from_call_arg_variant(...)` 会先检查缓存的 runtime tag：若 payload 是 `VECTOR*I`，
先 unpack 为 `Vector*i`，再调用 `Vector*(Vector*i)` constructor；否则才走目标 `Vector*` 的
ordinary `Variant` unpack。`String <-> StringName` 入站 parity 必须沿用这个形态，不能只把
runtime gate 改成 `STRING_NAME || STRING`。

当前 `String` / `StringName` 参数仍只走 exact gate 与 target-type `Variant` unpack：

- `StringName` 参数只接受 `Variant(STRING_NAME)`。
- `String` 参数只接受 `Variant(STRING)`。
- `godot_new_StringName_with_Variant(...)` 与 `godot_new_String_with_Variant(...)` 虽然存在，但它们只表达
  “按目标类型从 `Variant` unpack”，不能替代 wrapper 侧对 `STRING` / `STRING_NAME` runtime tag 的分流。

## 3. Godot 依据

已确认的 Godot 侧依据：

- `core/variant/variant.cpp`
  - `Variant::can_convert_strict(...)` 中 `STRING` target 接受 `STRING_NAME`
  - `STRING_NAME` target 接受 `STRING`
- `core/variant/variant_construct.cpp`
  - 注册 `VariantConstructor<String, StringName>`
  - 注册 `VariantConstructor<StringName, String>`
- `core/string/string_name.h`
  - `StringName(const String &p_name, bool p_static = false)`
  - `StringName::operator String() const`
- Godot class docs
  - `String(StringName)`
  - `StringName(String)`
  - `StringName` 文档说明 `String` 实参通常可自动转换为 `StringName`

GDCC 本地生成的 Godot builtin wrapper 也已经具备可复用 C 函数：

- `godot_new_StringName_with_String(const godot_String * from)`
- `godot_new_String_with_StringName(const godot_StringName * from)`

因此 source-level backend 物化无需新增 backend intrinsic，优先复用现有 `ConstructBuiltinInsn` 与
generated builtin constructor wrapper。GDExtension 入站 wrapper parity 则需要一个窄的 wrapper-local
materializer helper 或 `renderCallWrapperUnpackExpr(...)` 专用分支，以便在 gate 之后按 runtime tag
选择 exact unpack 或跨家族 constructor。

## 4. 设计决策

### 4.1 用独立 constructor materialization decision 表达

推荐把两条新边界建模为：

```java
Decision.ALLOW_WITH_BUILTIN_CONSTRUCTOR
```

理由：

- 它们不是 direct flow，source slot 不能直接写入 target slot。
- 它们不是 `Variant` pack/unpack。
- 它们与已有 `int -> float`、`Vector*i -> Vector*` 一样，需要生成 target-typed temp。
- 它们最终调用 Godot generated builtin constructor wrapper，现有 LIR 已经有 `ConstructBuiltinInsn` 表达这一点。
- 它们不需要新增 backend-owned intrinsic，也不应复用 `ALLOW_WITH_INTRINSIC_CAST` 这个名字去表达 constructor lowering。
- `decisionSpecificityRank(...)` 应把 constructor materialization 与 intrinsic cast 放在同一级：direct 之后、
  `Variant` pack/unpack 之前。

预期 overload 行为：

- `take(String)` 与 `take(StringName)` 同时存在时，source exact type 赢。
- 只有 `take(StringName)` 时，`String` source 可匹配。
- 只有 `take(String)` 时，`StringName` source 可匹配。
- `take(StringName)` 与 `take(Variant)` 同时存在时，`String` source 选择 `StringName`。
- stable `Variant` source 遇到 `String` / `StringName` 候选时不得被本任务强行消歧，但不同 call surface
  的既有行为不同，必须分别冻结：
  - bare call / global-like overload：若 `String` 与 `StringName` 候选都只通过 `Variant -> concrete`
    route 适用且互不支配，继续发布 ambiguous bare call，而不是固定选 `String` 或 `StringName`
  - builtin unary stable-`Variant` constructor：继续走独立 shortcut，不参与普通 constructor overload ranking；
    例如 `String(variant)` / `StringName(variant)` 应发布 owner-anchored resolved constructor route 与既有 warning
  - object instance method resolver：若 ambiguous object overload 满足现有 dynamic fallback 条件，可继续发布
    `DYNAMIC_FALLBACK`；不要把 bare/builtin 的 ambiguous 规则套到 object method path，也不要反过来把 object
    dynamic fallback 套到 bare/builtin overload

### 4.2 不修改 `ClassRegistry.checkAssignable(...)`

`ClassRegistry.checkAssignable(...)` 继续只表示 strict assignability：

- same type
- object inheritance upcast
- container covariance

把 `String <-> StringName` 放进这里会让 backend validation 与 frontend source boundary 混层，
并把应显式物化的转换误判成 direct assignment。

### 4.3 使用 `ConstructBuiltinInsn`，不新增 backend intrinsic

推荐的 ordinary boundary lowering 形态：

```text
$<StringName_temp> = construct_builtin $<String_source>;
$<String_temp> = construct_builtin $<StringName_source>;
```

该方案复用现有 C backend 路径：

- `ConstructInsnGen` 校验 result 存在且非 `ref`。
- `ConstructInsnGen` 解析 variable operands，并拒绝非变量参数。
- `CBuiltinBuilder.constructBuiltin(...)` 以 result target type 和 argument type 做 exact constructor metadata 匹配。
- `String -> StringName` 最终生成 `godot_new_StringName_with_String(...)`。
- `StringName -> String` 最终生成 `godot_new_String_with_StringName(...)`。
- `CBodyBuilder.callAssign(...)` 统一处理 pointer shape、copy/ownership 与 target write。

`String` 与 `StringName` 都是 destroyable value-semantic type。实现不得使用裸 C cast，也不得绕过
`construct_builtin` / `callAssign(...)` 自己拼写目标生命周期逻辑。

### 4.4 不复用显式 constructor call 语义

`StringName(text)` 与 `String(name)` 这种显式 constructor call 已经由 constructor resolver 发布
`FrontendResolvedCall`，再由 `FrontendSequenceItemInsnLoweringProcessors.lowerConstructorCall(...)`
生成 `ConstructBuiltinInsn`。ordinary typed boundary 不应把 assignment / call argument / return 重新解释成
显式 constructor call route。

本计划只在 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 这个 ordinary boundary 入口中
直接物化 `ConstructBuiltinInsn`。这样既复用现有 LIR/backend constructor 能力，又保持 source semantic、
overload ranking 与 explicit constructor syntax 的职责边界清晰。

### 4.5 不实现显式 `CastItem` lowering

`CastExpression` 当前在 body lowering 中仍是明确 unsupported。`String <-> StringName`
的隐式转换不需要打开这条语法面。

如果后续要支持显式 cast，应单独冻结 cast 语义与 diagnostics，不要把本计划混入 `CastItem`
的 fail-fast 边界。

### 4.6 入站 wrapper parity 是必做完成条件

GDExtension `call_func` wrapper parity 与 source-level ordinary boundary 属于同一个 feature gate。
它不是 Phase 之后的可选补丁，也不能被“source-level compile path 已完成”替代。

推荐实现形态：

- `CGenHelper.renderCallWrapperVariantTypeGate(...)`
  - `StringName` target 接受 `STRING_NAME || STRING`
  - `String` target 接受 `STRING || STRING_NAME`
  - `r_error->expected` 继续写 target 参数类型，不根据 payload tag 改写
- `CGenHelper.renderCallWrapperUnpackExpr(...)`
  - 对 `StringName` target 调用 `gdcc_new_StringName_from_call_arg_variant(value, type)`
  - 对 `String` target 调用 `gdcc_new_String_from_call_arg_variant(value, type)`
  - 其它类型继续走现有 exact unpack、float cast 或 vector helper 路径
- wrapper-local helper 只做 gate 之后的 materialization，不负责设置 `r_error`，也不重新定义错误边界

helper 行为必须按 runtime tag 分流：

- `Variant(STRING_NAME) -> StringName`：直接 `godot_new_StringName_with_Variant(...)`
- `Variant(STRING) -> StringName`：先 `godot_new_String_with_Variant(...)`，再
  `godot_new_StringName_with_String(...)`，最后销毁中间 `String`
- `Variant(STRING) -> String`：直接 `godot_new_String_with_Variant(...)`
- `Variant(STRING_NAME) -> String`：先 `godot_new_StringName_with_Variant(...)`，再
  `godot_new_String_with_StringName(...)`，最后销毁中间 `StringName`

明确禁止：

- 只把 gate 写成 `STRING_NAME || STRING`，但仍统一调用 target-type `godot_new_<Target>_with_Variant(...)`
- 把 `renderUnpackFunctionName(...)` 改成 string-family widened conversion 的入口
- 让 helper 承担第二套 runtime validation 或自行设置 `r_error`
- 修改 exact `ptrcall` ABI 或 exact engine method-bind helper

## 5. 分阶段实施

### Phase 0：文档与注释同步闸门

本阶段是后续语义、lowering、backend 与 wrapper 实施的前置闸门。目标不是让阶段计划长期替代事实源，
而是在进入代码改动前先统一会写出相反结论的长期文档、源码注释和测试锚点，避免实现落地后仍残留
“`String <-> StringName` 不支持”或“只支持 float/vector wrapper 例外”的旧合同。

#### Phase 0 执行状态

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具使用要求。
- [x] 使用 MCP `list_directory_tree` 以 depth >= 3 列出 `doc` 与 `doc/module_impl`。
- [x] 使用并行子代理完成相关 frontend 文档、frontend 代码/测试、backend wrapper 文档/代码调研并关闭子代理。
- [x] 同步长期事实源文档：
  - frontend matrix / lowering / rules / chain-binding / type-check / constructor-special-route / unary-binary / CFG lowering
  - backend implicit-conversion / builtin-builder / variant ABI / C backend / runtime helper / known-limits
- [x] 同步源码注释与 wrapper helper 注释。
- [x] 添加 Phase 0 静态一致性测试。
- [x] 运行 targeted tests 并记录结果：`script/run-gradle-targeted-tests.sh --tests FrontendStringStringNamePhase0DocumentationTest`，通过。

#### 必须同步的长期事实源

修改：

- `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - 将 `String -> StringName` 与 `StringName -> String` 从 GDCC `N` 改为 `Y`。
  - 同步“当前 GDCC 统一基线”、标量 / 字符串矩阵、结论摘要和维护合同。
  - 继续把 `String <-> NodePath`、`String -> Color` 等相邻 Godot strict conversion 标为本轮不支持，
    不得把本任务描述成 `GdStringLikeType` family 的整体放宽。
- `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - 将 ordinary typed boundary 支持面扩展到 `String -> StringName` 与 `StringName -> String`。
  - 增加 constructor materialization decision 的说明：它是 ordinary boundary decision，不是 pack/unpack，
    lowering 生成 target-typed `ConstructBuiltinInsn`，不是 direct assignment，也不是 `CallIntrinsicInsn`。
  - 删除或改写“`StringName` / `String` 互转不在合同内”的旧非目标文本。
  - 保持 subscript key/index 必须先 materialize，再基于物化后 key type 选择 access kind 的合同。
- `doc/module_impl/frontend/frontend_rules.md`
  - 更新 MVP subscript / typed-boundary 规则中对 `String` / `StringName` 互通的旧负面描述。
  - 明确这两条边界仍必须通过 `frontend_implicit_conversion_matrix.md`、shared helper 与显式 lowering
    materialization 生效，不得在 consumer 中手写局部分支。
- `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - 同步 subscript / container typing、assignment 和 call argument 相关段落。
  - 明确链绑定只消费 shared helper 发布的 compatibility fact，不维护第二份 string-family widening 清单。
  - 若文档仍把 `String <-> StringName` 作为非目标，必须改写为“由本 feature gate 独立支持”。
- `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - 改写“不在这里放宽 `StringName` / `String`”的旧语句。
  - 明确 type-check analyzer 仍只消费 matrix + `FrontendVariantBoundaryCompatibility`，
    不拥有独立 conversion 规则，也不改变 diagnostics owner。
- `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - 同步 CFG/body lowering 对 ordinary boundary materialization 的说明。
  - 明确普通 `"..."` 仍先 lower 为 `String` literal；流入 `StringName` slot 时再通过
    `ConstructBuiltinInsn` 物化，只有 `&"..."` 才是 direct `StringName` literal route。
- `doc/module_impl/frontend/frontend_builtin_constructor_variant_argument_plan.md`
  - 删除把 `String <-> StringName` 作为永久非目标的旧描述。
  - 改写为：builtin unary stable-`Variant` constructor special route 与本计划的 ordinary boundary
    constructor materialization 是两条独立 feature gate，不互相替代。
- `doc/module_impl/backend/implicit_conversion_implementation.md`
  - 将当前支持的非 direct conversion 扩展到 `String -> StringName` 与 `StringName -> String`。
  - 更新 explicit unsupported 清单、decision/rank 表、constructor route、lowering materialization、
    subscript 和入站 `call_func` wrapper 兼容规则。
  - 说明这两条 source-level boundary 通过 `ConstructBuiltinInsn` 物化，wrapper parity 通过
    gate-first / materializer-second helper 物化。
- `doc/module_impl/backend/builtin_builder_implementation.md`
  - 说明 `CBuiltinBuilder.constructBuiltin(...)` 仍保持 exact constructor metadata matching。
  - 补充 `String <-> StringName` ordinary boundary 由 frontend 显式生成 target-typed
    `ConstructBuiltinInsn` 后才会进入 backend constructor path；backend matcher 不新增 implicit conversion。
- `doc/module_impl/backend/variant_abi_contract.md`
  - 将 `call_func` inbound narrow exceptions 从 float/vector 扩展到 `String` / `StringName` 双向 runtime tag。
  - 同步 wrapper-local helper 列表、gate-first 顺序、`r_error->expected` 不变、ptrcall ABI 不变、
    cross-case 中间 `String` / `StringName` cleanup。
- `doc/gdcc_c_backend.md`
  - 更新顶层 `call_func` runtime gate 与 wrapper-only inbound helper 说明。
  - 明确变更只影响 generated `call_func` wrapper 入站边界，不影响 exact `ptrcall` ABI、
    exact engine method-bind helper 或 outward metadata target type。
- `doc/gdcc_runtime_lib.md`
  - 更新 `gdcc_intrinsic.h` 的职责说明：它承载 wrapper-only inbound materialization helper，
    不再只描述 vector helper，也包括 string-family helper。
  - 继续区分 generated `godot_*` builtin wrapper surface 与 GDCC-owned `gdcc_*` helper。
- `doc/test_error/test_suite_engine_integration_known_limits.md`
  - 删除或改写 “`String` / `StringName` 互通仍是 gap” 的旧 known-limit 文本。
  - 若新增 runtime/test-suite anchor，必须把相应条目从 known-limit 迁移到正向或已修复说明。

#### 按实现结果确认是否同步

以下文档只在存在直接冲突、增加 runtime anchor、或新增说明价值明确时更新；不得为了“看起来同步”制造噪音：

- `doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md`
  - 若仍写着不要引入 `StringName` / `String` 更宽隐式转换，应改成 unary/binary 语义不拥有这条 widening；
    ordinary typed boundary 由 shared helper 独立管理。
- `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md`
  - 仅在需要说明 dynamic call 发布的 stable `Variant` 结果后续跨 typed boundary 时更新；
    不把 wrapper parity 写成 frontend dynamic call lowering 的职责。
- `doc/module_impl/frontend/frontend_lowering_plan.md`
  - 仅在总路线图需要显式记录该 feature 时更新。
- `doc/gdcc_low_ir.md`
  - LIR 语法无需变更；可选补充 `construct_builtin` 示例，但不得写成新的 opcode 或 intrinsic。
- `doc/test_suite.md`
  - 只有新增 `test_suite` 资源、runtime anchor 或 category 时才同步。
- `doc/gdcc_type_system.md` 与 `doc/gdcc_ownership_lifecycle_spec.md`
  - 类型家族和 ownership 总合同不变；只有现有文字与 wrapper-local destroyable value cleanup 直接冲突时才改。

#### 明确不应同步的文档

- `doc/gdcc_lir_intrinsic.md`
  - 本任务不新增 backend intrinsic，也不扩展 `call_intrinsic` catalog。
  - 只需在相关文档中说明无需更新该 catalog；不要把 `String <-> StringName` 记成 intrinsic。
- `doc/gdcc_backend_todo.md`
  - 没有直接相关 TODO 时不新增阶段噪音。
- `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
- `doc/module_impl/frontend/frontend_exact_call_extension_metadata_contract.md`
- `doc/module_impl/frontend/frontend_complex_writable_target_implementation.md`
- `doc/module_impl/backend/call_method_implementation.md`
- `doc/module_impl/backend/call_global_implementation.md`
- `doc/module_impl/backend/index_insn_implementation.md`
- `doc/module_impl/backend/load_store_property_implementation.md`
  - 这些文档不是 typed-boundary compatibility 或 wrapper parity 的事实源；只有 `rg` 发现直接冲突文本时才做最小改写。

#### 源码注释与测试命名同步

同步原则：

- 不在代码注释里维护第二份 source/target conversion 矩阵。
- 关键注释只说明本入口的职责、引用长期事实源，并列出 lowering 形态；不要把所有 Godot strict conversion 复制进源码。
- 测试方法名、`@DisplayName` 和 fixture 注释若写到旧行为，必须随行为变更同步。

必须检查的源码注释 / Javadoc：

- `FrontendVariantBoundaryCompatibility`
  - class Javadoc、`determineFrontendBoundaryDecision(...)` Javadoc、`decisionSpecificityRank(...)` Javadoc。
  - 增加 constructor materialization decision 后，注释中的 decision 清单、rank 描述和文档引用必须同步。
- `FrontendBodyLoweringSession`
  - `materializeFrontendBoundaryValue(...)`、`materializeIntrinsicCast(...)`、`materializeSubscriptKey(...)`、
    `materializeCallArguments(...)` 周边注释。
  - 注释要把 constructor materialization 与 intrinsic cast 分开；`materializeIntrinsicCast(...)` 继续只描述
    `int -> float` 与 `Vector*i -> Vector*`。
- `FrontendSubscriptAccessSupport`
  - 只描述 access-kind truth source。不得把 “NAMED 接受 `StringName` key” 写成“semantic 已接受 `String` key”。
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/ConstructInsnGen.java`
  - 注意实际文件在 `backend/c/gen/insn/`，不是 `backend/c/gen/` 根目录。
  - 注释只需表达指令分发；不要把 implicit conversion 责任写到这里。
- `CBuiltinBuilder`
  - `constructBuiltin(...)` 与普通 constructor matching 注释必须继续强调 exact metadata matching。
  - literal / constructor argument materialization 注释必须继续区分普通 `"..."`、`&"..."` 与 `NodePath`。
- `CGenHelper`
  - `renderCallWrapperVariantTypeGate(...)` 与 `renderCallWrapperUnpackExpr(...)` 注释要同步 string-family wrapper parity。
  - `renderUnpackFunctionName(...)` 不应被描述为 string-family widened conversion 入口。
- `src/main/c/codegen/template_451/entry.h.ftl`
  - gate、typed-container preflight、wrapper-local unpack 的顺序注释必须保持 gate-first / materializer-second。
- `src/main/c/codegen/include_451/gdcc/gdcc_intrinsic.h`
  - helper 注释应描述 wrapper-only inbound materialization，不应暗示 helper 是第二套 runtime validation。
- `LiteralStringNameInsn`、`NewDataInsnGen`、`StringUtil.decodeGdStringLexeme(...)`
  - 若触及字面量文档，继续保持 raw lexeme 与 normalized payload 的边界。

#### 测试锚点同步

验收：

- 每个规范性文档改动都必须能指向至少一个 focused test 或 runtime anchor；流程性说明不需要单独测试。
- `frontend_implicit_conversion_matrix.md` 中所有改动过的 `Y/N`、rank、materialization 说明必须对应测试锚点。
- 正向支持锚点至少覆盖：
  - `FrontendVariantBoundaryCompatibilityTest`
  - `FrontendAssignmentSemanticSupportTest`
  - `FrontendTypeCheckAnalyzerTest`
  - `FrontendExpressionSemanticSupportTest`
  - `FrontendConstructorResolutionSupportTest`
  - `ScopeMethodResolverTest`
  - `FrontendSubscriptSemanticSupportTest`
  - `FrontendBodyLoweringSessionTest`
  - `FrontendLoweringBodyInsnPassTest`
  - `CConstructInsnGenTest`
  - `CGenHelperTest`
  - `CCodegenTest`
- 负例锚点至少覆盖：
  - `String <-> NodePath`
  - `String -> Color`
  - `String -> int` / `StringName -> int`
  - stable `Variant` 在 bare call、builtin unary constructor、object method fallback 三条 surface 上的既有行为
- literal 合同必须由 lowering 测试锚定：
  - 普通 `"text"` 流入 `StringName` slot：`LiteralStringInsn` + target-typed `ConstructBuiltinInsn`
  - `&"text"` 流入 `StringName` slot：direct `LiteralStringNameInsn`
- overload ambiguity、subscript lowering 和 wrapper parity 的文档句子必须分别对应 resolver、lowering、`CGenHelperTest` /
  rendered `entry.h` 测试。
- 文档中不再出现互相冲突的支持状态；若 `rg` 仍搜到 `String <-> StringName` unsupported 相关文字，
  必须能明确解释它是在描述历史、已修复 known-limit，或是另一个不由本 feature 覆盖的非 ordinary boundary。
- 所有关联文档都明确 source-level boundary 与 GDExtension 入站 wrapper 是两条不同路径，但必须同步完成。
- 仍明确 `String <-> NodePath`、`String -> Color` 等其它 Godot strict conversion 不在本轮支持面。
- 仍明确 builtin keyed metadata route 不因本任务自动打开。

### Phase 1：前端边界兼容性

#### Phase 1 执行状态

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具使用要求。
- [x] 使用 MCP `list_directory_tree` 以 depth >= 3 列出 `doc` 与 `doc/module_impl`。
- [x] 使用并行子代理完成 Phase 1 相关文档、compatibility 代码、测试结构调研并关闭子代理。
- [x] 在 `FrontendVariantBoundaryCompatibility` 中新增 `ALLOW_WITH_BUILTIN_CONSTRUCTOR` decision。
- [x] 精确支持 `String -> StringName` 与 `StringName -> String`，不使用宽泛 `GdStringLikeType` family 规则。
- [x] 保持 `ClassRegistry.checkAssignable(...)` strict assignability 不变，并补充反向锚点测试。
- [x] 将 constructor materialization decision 的 specificity rank 固定为 2，与 intrinsic cast 同级。
- [x] 为后续 Phase 3 lowering switch 增加显式 fail-fast 分支，避免提前实现 `ConstructBuiltinInsn` 物化。
- [x] 添加 `FrontendVariantBoundaryCompatibilityTest` 正例、邻近负例与 rank 测试。
- [x] 运行 targeted tests 并记录结果：
  `script/run-gradle-targeted-tests.sh --tests FrontendVariantBoundaryCompatibilityTest,ClassRegistryTest`，通过。
- [x] 运行 IDE file rebuild 检查改过的 Java 文件，`build_project` 通过。

修改：

- `FrontendVariantBoundaryCompatibility`

实施：

- 增加精确的 `String -> StringName` 分支。
- 增加精确的 `StringName -> String` 分支。
- 新增并返回 `ALLOW_WITH_BUILTIN_CONSTRUCTOR` 或等价命名的 constructor materialization decision。
- 不使用宽泛 `GdStringLikeType -> GdStringLikeType`，避免误放 `NodePath`。
- 不修改 `ClassRegistry.checkAssignable(...)`。
- `decisionSpecificityRank(...)` 赋予该 decision rank 2，与 intrinsic cast 同级。

验收：

- `FrontendVariantBoundaryCompatibilityTest` 覆盖：
  - `String -> StringName` 返回 constructor materialization decision
  - `StringName -> String` 返回 constructor materialization decision
  - `String -> NodePath` 仍拒绝
  - `NodePath -> String` 仍拒绝
  - `String -> int`、`StringName -> int` 仍拒绝
  - constructor materialization rank 为 2，direct rank 仍高于它，pack/unpack rank 仍低于它

### Phase 2：语义 consumer 与 overload 排序测试

#### Phase 2 执行状态

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具使用要求。
- [x] 使用 MCP `list_directory_tree` 以 depth >= 3 列出 `doc` 与 `doc/module_impl`。
- [x] 使用并行子代理完成 Phase 2 相关文档、frontend semantic consumer、method resolver 与基线测试调研并关闭子代理。
- [x] 确认 semantic consumer 与 method resolver 已经复用 `FrontendVariantBoundaryCompatibility` / frontend rank helper，不需要新增生产代码分支。
- [x] 在 `FrontendAssignmentSemanticSupportTest` 补充 assignment-facing `String <-> StringName` 正例与邻近负例。
- [x] 在 `FrontendTypeCheckAnalyzerTest` 补充 local / property initializer、assignment 表达式发布、return slot 的 type-check 锚点与 unsupported route 负例。
- [x] 在 `FrontendExpressionSemanticSupportTest` 补充 bare-call / callable overload 的 exact、constructor materialization、Variant pack 与逐参数歧义锚点。
- [x] 在 `FrontendConstructorResolutionSupportTest` 补充 builtin constructor 排序、多参数歧义、stable-`Variant` shortcut 与显式 `StringName(String)` / `String(StringName)` route 锚点。
- [x] 在 `ScopeMethodResolverTest` 补充 instance/static method frontend-rank、跨参数歧义与 object dynamic fallback 锚点。
- [x] 运行 targeted tests 并记录结果：
  `script/run-gradle-targeted-tests.sh --tests FrontendAssignmentSemanticSupportTest,FrontendTypeCheckAnalyzerTest,FrontendExpressionSemanticSupportTest,FrontendConstructorResolutionSupportTest,ScopeMethodResolverTest`，通过。

预期大多数 consumer 不需要改代码，因为它们已经复用 shared boundary helper。

重点补测试：

- `FrontendAssignmentSemanticSupportTest`
  - `StringName` slot 接受 `String`
  - `String` slot 接受 `StringName`
  - 错误方向外的邻近负例仍失败，例如 `String -> int`
- `FrontendTypeCheckAnalyzerTest`
  - local / property initializer、assignment、return 的 type-check 不再报错
  - unrelated unsupported routes 不被顺手放开
- `FrontendExpressionSemanticSupportTest`
  - `String` exact overload 赢过 `StringName` widening
  - `StringName` exact overload 赢过 `String` widening
  - widening 赢过 `Variant` pack
  - 多参数歧义锚点：source argument types 为 `(int, String)`，候选为 `(float, String)` 与
    `(int, StringName)` 时必须保持 ambiguous；前者第二个参数 exact、后者第一个参数 exact，二者分别在一个参数上更具体
  - 上述多参数锚点不得因 `ALLOW_WITH_INTRINSIC_CAST` 与 `ALLOW_WITH_BUILTIN_CONSTRUCTOR` 同为 rank 2
    而用“rank 总分”或固定偏好误选任一候选
  - bare call stable-`Variant` 锚点：source 为 stable `Variant`，候选为 `take(String)` 与
    `take(StringName)` 时，若二者都只通过 `Variant -> concrete` route 适用且互不支配，继续发布
    ambiguous bare call，不得固定偏向 `String` 或 `StringName`
- `FrontendConstructorResolutionSupportTest`
  - builtin constructor 参数排序保持 exact > constructor materialization / intrinsic cast > Variant
  - constructor overload 也覆盖同类多参数歧义：`(int, String)` 面对 `(float, String)` 与 `(int, StringName)`
    不能因 rank 加权或 stable tie-break 偏向某一边
  - builtin unary stable-`Variant` constructor special route 保持独立 shortcut：
    `String(variant)` / `StringName(variant)` 不进入普通 constructor overload ranking，不因本任务新增
    `String <-> StringName` constructor materialization decision 而变成 ambiguous 或被某个 metadata overload 抢走
  - 显式 `StringName(String)` 可解析，并继续走 constructor call route
  - 显式 `String(StringName)` 可解析，并继续走 constructor call route
- `ScopeMethodResolverTest`
  - instance/static method resolution 的 frontend rank 路径覆盖 `String` / `StringName`
  - method resolver 的多参数 specificity 也保持逐参数支配语义，不把 rank 2 conversion 累加成总分
  - object instance method resolver stable-`Variant` 锚点：source 为 stable `Variant`，object candidates 为
    `method(String)` 与 `method(StringName)` 且二者同等最佳时，保持现有 object dynamic fallback 行为；
    不得改成 bare-call-style hard ambiguous，也不得静态偏向某个 string-family overload

验收：

- 所有已存在的 `int -> float` 与 `Vector*i -> Vector*` ranking 测试继续通过。
- 新增 `String` / `StringName` 测试不要求修改 call resolver 架构。
- overload specificity 必须保持逐参数支配关系：候选 A 与 B 若各自在不同参数上更具体，则保持 ambiguous；
  不得把 per-argument rank 相加、加权或引入 constructor materialization vs intrinsic cast 的稳定偏好。
- stable `Variant` 与 `String` / `StringName` overload 的结果必须按 call surface 分别验收：
  - bare call：保留 ambiguous diagnostic
  - builtin unary constructor：保留 independent stable-`Variant` shortcut
  - object instance method resolver：在既有条件下保留 dynamic fallback
- concrete `String` / `StringName` source 必须可按 specificity 选出预期候选，不能借 stable-`Variant`
  的特殊路径扩大 ambiguity。

### Phase 3：前端 lowering 物化

#### Phase 3 执行状态

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具使用要求。
- [x] 使用 MCP `list_directory_tree` 以 depth >= 3 列出 `doc` 与 `doc/module_impl`。
- [x] 使用并行子代理完成 Phase 3 相关文档、前端 lowering 生产代码与 lowering 测试结构调研并关闭子代理。
- [x] 在 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 中接通
  `ALLOW_WITH_BUILTIN_CONSTRUCTOR`，通过 target-typed `ConstructBuiltinInsn` 物化 ordinary
  `String <-> StringName` 边界。
- [x] 保持 `requireIntrinsicCastName(...)` 只覆盖 `int -> float` 与同维度 `Vector*i -> Vector*`，
  未在 local init、assignment、call、return 或 subscript consumer 中新增局部分支。
- [x] 在 `FrontendBodyLoweringSessionTest` 补充 helper 级 `String -> StringName` /
  `StringName -> String` constructor materialization 正例与 `String -> NodePath` /
  `StringName -> int` 邻近负例。
- [x] 在 `FrontendLoweringBodyInsnPassTest` 补充 local initializer、assignment/property store、
  fixed call argument、return slot、property initializer、普通 `"..."` 与 `&"..."`
  literal 分流的 lowering 锚点。
- [x] 运行 targeted tests 并记录结果：
  `script/run-gradle-targeted-tests.sh --tests FrontendBodyLoweringSessionTest,FrontendLoweringBodyInsnPassTest`，通过。

修改：

- `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`
- 相关注释中的 supported routes 清单

实施：

- 新增 `materializeBuiltinConstructorBoundary(...)` 或等价私有 helper。
- `String -> StringName` 分配 `StringName` temp 并追加 `ConstructBuiltinInsn(temp, List.of(sourceSlot))`。
- `StringName -> String` 分配 `String` temp 并追加 `ConstructBuiltinInsn(temp, List.of(sourceSlot))`。
- 普通 `"text"` 字面量仍先发布为 `String` / `LiteralStringInsn`；当它流入 `StringName` slot 时，
  这是本任务新增的 ordinary boundary，必须再生成 target-typed `ConstructBuiltinInsn`。
- 只有 `&"text"` 字面量发布为 `StringName` / `LiteralStringNameInsn`，才能作为 `StringName` slot 的 direct
  literal route；它不得被拿来替代普通 `"text"` 的 `String -> StringName` boundary materialization。
- 保持 `requireIntrinsicCastName(...)` 只覆盖 `int -> float` 与 `Vector*i -> Vector*`。
- 不在 local init、assignment、call、return、subscript consumer 中新增局部分支。

验收：

- `FrontendLoweringBodyInsnPassTest` 覆盖：
  - local initializer：`var name: StringName = text`
  - local initializer literal：`var name: StringName = "text"` 必须先生成 `LiteralStringInsn`，再生成
    target-typed `ConstructBuiltinInsn`；不能生成 direct `LiteralStringNameInsn`，也不能 direct assignment
  - direct string-name literal：`var name: StringName = &"text"` 继续生成 `LiteralStringNameInsn`，不需要
    `ConstructBuiltinInsn`
  - assignment/property store：`name = text` 与 `text = name`
  - assignment literal：`name = "text"` 必须通过 `LiteralStringInsn` + `ConstructBuiltinInsn` 写入
    `StringName` target；`name = &"text"` 才能 direct 使用 `StringName` literal source
  - fixed call argument：`take_name(text)` / `take_text(name)`
  - fixed call literal：`take_name("text")` 必须物化 `String -> StringName` constructor boundary；
    `take_name(&"text")` 才是 direct `StringName` argument
  - return：`func f(text: String) -> StringName: return text`
  - return literal：`func f() -> StringName: return "text"` 必须通过 `LiteralStringInsn` +
    `ConstructBuiltinInsn`；`return &"text"` 才能走 direct `StringName` literal route
  - property initializer：普通 `"text"` 与 `&"text"` 的 lowering 差异与 local initializer 一致
- 每条 `String <-> StringName` 非 direct boundary 都生成 target-typed `ConstructBuiltinInsn`。
- 普通 `"..."` 字面量流入 `StringName` slot 计入 `String -> StringName` 非 direct boundary；不得通过 backend
  literal materialization 或 direct assignment 绕过 constructor materialization。
- `&"..."` 字面量流入 `StringName` slot 是 direct typed literal route，不应额外生成 constructor。
- consumer 使用 constructor result slot，而不是原始 source slot。
- 不生成 `CallIntrinsicInsn`、`PackVariantInsn` 或 `UnpackVariantInsn` 来替代 string-family conversion。

### Phase 4：subscript key/value 边界

#### Phase 4 执行状态

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具使用要求。
- [x] 使用 MCP `list_directory_tree` 以 depth >= 3 列出 `doc` 与 `doc/module_impl`。
- [x] 使用并行子代理完成 Phase 4 相关文档、前端 subscript 生产代码与测试结构调研并关闭子代理。
- [x] 确认 subscript semantic 已复用 `FrontendVariantBoundaryCompatibility`，未新增 subscript 专用
  `String` / `StringName` widening 规则。
- [x] 在 `FrontendSubscriptSemanticSupportTest` 补充 `Dictionary[StringName, int]` 接受
  `String` key、`Dictionary[String, int]` 接受 `StringName` key 的 shared-boundary 正例。
- [x] 在 `FrontendLoweringBodyInsnPassTest` 补充 `String` / `StringName` key 先经
  target-typed `ConstructBuiltinInsn` 物化，再分别选择 named / keyed subscript route 的锚点。
- [x] 在 `FrontendLoweringBodyInsnPassTest` 补充 `Dictionary[int, StringName]` value write 接受
  `String`、`Dictionary[int, String]` value write 接受 `StringName` 的物化锚点，确认 constructor
  result slot 进入最终 `VariantSetIndexedInsn.valueId()`。
- [x] 追加记录 typed Dictionary subscript 的长期不变量：`Dictionary[StringName, V]` 用 `String`
  key 之所以能选择 named route，是因为 body lowering 先通过
  `materializeFrontendBoundaryValue(...)` 把 key 物化成 container key type，再基于物化后的
  `StringName` key type 判定 access kind；access-kind truth source 本身只把 `GdStringNameType`
  视为 named key。
- [x] 在 `FrontendBodyLoweringSessionTest` 追加 helper 级回归测试，直接锚定
  `materializeSubscriptKey(...)` 返回的 materialized slot/type/access-kind 必须成对消费，并用原始
  `String` / `StringName` key type 的相反 route 作为负向漂移锚点。
- [x] 在 `FrontendWritableRouteSupportTest` 追加 writable-route 回归测试，确认 leaf read 与
  reverse commit 复用同一个 materialized key，并分别锚定 `Dictionary[StringName, V]` +
  `String` key 的 named route 与 `Dictionary[String, V]` + `StringName` key 的 keyed route。
- [x] 运行 targeted tests 并记录结果：
  `script/run-gradle-targeted-tests.sh --tests FrontendSubscriptSemanticSupportTest,FrontendLoweringBodyInsnPassTest`，通过。
- [x] 追加补强后运行 targeted tests 并记录结果：
  `script/run-gradle-targeted-tests.sh --tests FrontendBodyLoweringSessionTest,FrontendLoweringBodyInsnPassTest,FrontendSubscriptSemanticSupportTest`，通过。
- [x] 追加 writable-route 补强后运行 targeted tests 并记录结果：
  `script/run-gradle-targeted-tests.sh --tests FrontendBodyLoweringSessionTest,FrontendWritableRouteSupportTest,FrontendLoweringBodyInsnPassTest,FrontendSubscriptSemanticSupportTest`，通过。

不新增 subscript 专用规则，只消费 Phase 1/3 的 ordinary boundary。

长期不变量：

- subscript lowering 必须把 `MaterializedSubscriptKey.slotId()`、`type()` 与 `accessKind()` 作为同一组结果消费；
  任何 direct load/store、writable-route leaf read 或 reverse commit 都不得丢弃该结果后用原始 source key type
  重新调用 `FrontendSubscriptAccessSupport.determineAccessKind(...)`。
- 对 `Dictionary[StringName, V]` + `String` key，原始 `String` key 若直接判 access kind 会落到 keyed route；
  正确 route 必须来自物化后的 `StringName` key，因此最终是 named route。
- 对 `Dictionary[String, V]` + `StringName` key，原始 `StringName` key 若直接判 access kind 会落到 named route；
  正确 route 必须来自物化后的 `String` key，因此最终是 keyed route。

重点测试：

- `Dictionary[StringName, int]` key 接受 `String`
  - set/get key 先物化成 `StringName`
  - access kind 基于 materialized key type 选择
- `Dictionary[String, int]` key 接受 `StringName`
  - set/get key 先物化成 `String`
  - 不误选 named route
- `materializeSubscriptKey(...)` helper 级覆盖
  - `Dictionary[StringName, V]` + 原始 `String` key 返回 materialized `StringName` key 与 `NAMED`
  - `Dictionary[String, V]` + 原始 `StringName` key 返回 materialized `String` key 与 `KEYED`
  - 同时断言若直接用原始 key type 判 access kind，会分别落入相反的 keyed / named route
- writable-route 覆盖
  - leaf read 与 reverse commit 只 materialize 一次 `String -> StringName` / `StringName -> String` key
  - `VariantGetNamedInsn.nameId()` / `VariantSetNamedInsn.nameId()` 与
    `VariantGetKeyedInsn.keyId()` / `VariantSetKeyedInsn.keyId()` 都消费同一个 constructor result
  - 双向负向断言分别不生成相反的 keyed / named 指令
- `Dictionary[int, StringName]` value write 接受 `String`
- `Dictionary[int, String]` value write 接受 `StringName`

验收：

- `FrontendSubscriptSemanticSupportTest` 证明 key/index compatibility 放行来自 shared helper。
- `FrontendLoweringBodyInsnPassTest` 证明 key/value materialization 发生在 final `VariantGet*` / `VariantSet*` 之前。
- `FrontendBodyLoweringSessionTest` 与 `FrontendWritableRouteSupportTest` 证明 helper 与 writable-route caller
  都按 materialized key/type/access-kind 同组结果消费。
- `String` receiver、`Vector` receiver 等 builtin keyed access 仍保持现有 unsupported 合同；本任务只影响 typed container boundary。

### Phase 5：C backend constructor 验证

#### Phase 5 执行状态

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具使用要求。
- [x] 使用 MCP `list_directory_tree` 以 depth >= 3 列出 `doc` 与 `doc/module_impl`。
- [x] 使用并行子代理完成 Phase 5 相关文档、C backend constructor 生产代码与测试结构调研并关闭子代理。
- [x] 确认 `ConstructInsnGen` / `CBuiltinBuilder` 已按 exact constructor metadata matching 消费
  `ConstructBuiltinInsn`，无需新增 backend intrinsic 或放宽 backend matcher。
- [x] 确认 bundled API / generated wrapper 已包含 `godot_new_StringName_with_String(...)` 与
  `godot_new_String_with_StringName(...)`。
- [x] 在 `CConstructInsnGenTest` 补充 `String -> StringName` non-ref source 取地址传参的 C 输出锚点。
- [x] 在 `CConstructInsnGenTest` 补充 `StringName -> String` ref source 直接传 pointer 的 C 输出锚点。
- [x] 在 `CConstructInsnGenTest` 补充 string-family constructor 的 non-variable operand 拒绝锚点。
- [x] 在 `CConstructInsnGenTest` 补充缺失 `StringName(String)` metadata 时继续 fail-fast 的锚点。
- [x] 未修改 `ConstructInsnGen` / `CBuiltinBuilder` / `CIntrinsicManager`，避免引入 backend implicit-conversion surface。
- [x] 运行 targeted tests 并记录结果：
  `script/run-gradle-targeted-tests.sh --tests CConstructInsnGenTest`，通过。

修改：

- 原则上不需要修改 `ConstructInsnGen` / `CBuiltinBuilder`。
- 若测试发现 API metadata 解析未覆盖这两个 constructor，再修正 metadata 解析或 generated wrapper 数据源，而不是新增 intrinsic。
- 必要时补充 `CConstructInsnGenTest` 或 engine integration anchor。

实施建议：

- `construct_builtin` 的 result 为 `StringName`、argument 为 `String` 时，C 输出应调用 `godot_new_StringName_with_String`。
- `construct_builtin` 的 result 为 `String`、argument 为 `StringName` 时，C 输出应调用 `godot_new_String_with_StringName`。
- non-ref source 由 `CBodyBuilder.renderArgument(...)` 自动取地址传参。
- ref source 直接以 pointer 传参。
- 目标写入继续由 `CBuiltinBuilder.constructBuiltin(...)` -> `CBodyBuilder.callAssign(...)` 处理。

验收：

- `CConstructInsnGenTest` 覆盖：
  - 两个方向的成功 C 输出
  - non-ref source 自动以地址传参
  - ref source 直接以 pointer 传参
  - non-variable operand 继续拒绝
  - 缺失 constructor metadata 继续 fail-fast
- `CIntrinsicManagerTest` 不需要新增 string-family case，避免误引入 backend intrinsic surface。
- `CallIntrinsicInsnGenTest` 只需保持现有 unknown intrinsic fail-fast，不因本任务扩面。
- `CNewDataInsnGenTest` 中 string 与 string-name literal 仍走 UTF-8 literal constructor，不被 boundary constructor 改动影响。

### Phase 6：GDExtension 入站 wrapper parity

这是 source-level frontend boundary 之外的运行时入站边界，但它是本 feature 的必做完成条件。
实现不得声明“只完成 source-level compile path”。`int -> float` 与 `Vector*i -> Vector*` 的完整支持面已经包含
GDExtension `call_func` wrapper 入站 parity；`String <-> StringName` 必须保持同等级闭环。

#### Phase 6 执行状态

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具使用要求。
- [x] 使用 MCP `list_directory_tree` 以 depth >= 3 列出 `doc` 与 `doc/module_impl`。
- [x] 使用并行子代理完成 Phase 6 相关文档、生产代码与测试结构调研并关闭子代理。
- [x] 在 `CGenHelper.renderCallWrapperVariantTypeGate(...)` / `renderCallWrapperUnpackExpr(...)` 中接通
  `String` / `StringName` 入站 wrapper gate 与 materializer。
- [x] 在 `gdcc_intrinsic.h` 中补充 wrapper-only string-family inbound helper，并销毁 cross-case 中间值。
- [x] 在 `CGenHelperTest` 补充 gate / unpack 正反锚点。
- [x] 在 `CCodegenTest` 补充 rendered `entry.h` wrapper body 锚点。
- [x] 运行 targeted tests 并记录结果：
  `script/run-gradle-targeted-tests.sh --tests CGenHelperTest,CCodegenTest`，通过。
- [x] 运行 C header compile smoke 并记录结果：
  `script/run-gradle-targeted-tests.sh --tests GodotAbiHeaderCompileTest`，通过。
- [x] 运行 IDE file rebuild 检查改过的 Java 文件，`build_project` 通过。

修改：

- `CGenHelper.renderCallWrapperVariantTypeGate(...)`
- `CGenHelper.renderCallWrapperUnpackExpr(...)`
- `src/main/c/codegen/include_451/gdcc/gdcc_intrinsic.h` 或等价 wrapper-local helper 所在位置
- `CGenHelperTest`
- `CCodegenTest` 或覆盖 rendered `entry.h` wrapper body 的同级测试

实施：

- GDCC 方法参数 target 为 `StringName` 时，`call_func` wrapper 除 `Variant(STRING_NAME)` 外也接受 `Variant(STRING)`。
- GDCC 方法参数 target 为 `String` 时，`call_func` wrapper 除 `Variant(STRING)` 外也接受 `Variant(STRING_NAME)`。
- `r_error->expected` 仍发布 target 参数类型。
- `ptrcall` ABI 不变；exact engine method-bind helper 不参与这条入站 wrapper 宽化。
- ordinary source-level lowering 仍使用 `ConstructBuiltinInsn`，不经过 wrapper-local helper。
- 不修改 `renderUnpackFunctionName(...)`，避免影响 ordinary unpack、property、index、operator 等其它 `Variant` 路径。
- 若新增 helper，命名应表达 wrapper 入站语义，例如：
  - `gdcc_new_StringName_from_call_arg_variant(...)`
  - `gdcc_new_String_from_call_arg_variant(...)`

materialization 细则：

- `StringName` target：
  - runtime tag 为 `STRING_NAME` 时，直接调用 `godot_new_StringName_with_Variant(...)`
  - runtime tag 为 `STRING` 时，先调用 `godot_new_String_with_Variant(...)`，再调用
    `godot_new_StringName_with_String(...)`，最后销毁中间 `String`
- `String` target：
  - runtime tag 为 `STRING` 时，直接调用 `godot_new_String_with_Variant(...)`
  - runtime tag 为 `STRING_NAME` 时，先调用 `godot_new_StringName_with_Variant(...)`，再调用
    `godot_new_String_with_StringName(...)`，最后销毁中间 `StringName`
- helper 不重复执行 runtime error reporting；generated wrapper 的 gate 顺序仍然是唯一 `r_error` 边界。

验收：

- `CGenHelperTest` 覆盖：
  - `StringName` target 的 gate 为 `STRING_NAME || STRING`
  - `String` target 的 gate 为 `STRING || STRING_NAME`
  - `StringName` target 的 unpack expr 调用 string-family wrapper-local materializer
  - `String` target 的 unpack expr 调用 string-family wrapper-local materializer
  - `String` / `StringName` 以外的类型不受这两个 materializer 影响
- `CCodegenTest` 或同级 rendered-wrapper 测试覆盖：
  - generated `entry.h` 中 `StringName` 参数接受 `Variant(STRING)` 与 `Variant(STRING_NAME)`
  - generated `entry.h` 中 `String` 参数接受 `Variant(STRING)` 与 `Variant(STRING_NAME)`
  - wrapper body 只缓存一次 `godot_variant_get_type(p_args[N])`
  - `r_error->expected` 对 `StringName` 参数仍是 `GDEXTENSION_VARIANT_TYPE_STRING_NAME`
  - `r_error->expected` 对 `String` 参数仍是 `GDEXTENSION_VARIANT_TYPE_STRING`
- wrapper-local helper 或 rendered C body 证明 cross-case 不是单纯调用 target-type
  `godot_new_StringName_with_Variant(...)` / `godot_new_String_with_Variant(...)`：
  - `Variant(STRING) -> StringName` 必须出现 `godot_new_StringName_with_String(...)`
  - `Variant(STRING_NAME) -> String` 必须出现 `godot_new_String_with_StringName(...)`
- helper 销毁 cross-case 的中间 `String` / `StringName`，避免 wrapper-local value-semantic leak。
- 入站 wrapper 的新增兼容不影响 exact `String` / `StringName` ptrcall。
- 若 runtime integration 环境可用，增加一个 `Object.call(...)` 或 Godot engine integration anchor。

### Phase 7：文档与注释同步复核

本阶段在代码与测试补齐后执行，不再引入新行为。它只做 drift check：确认 Phase 0 列出的文档、
源码注释、测试命名与当前实现一致，并确认每条规范性合同都能落到具体测试证据。

#### Phase 7 执行状态

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具使用要求。
- [x] 使用 MCP `list_directory_tree` 以 depth >= 3 列出 `doc` 与 `doc/module_impl`。
- [x] 使用并行子代理完成前端文档、后端文档、代码/测试锚点与独立复核调研并关闭子代理。
- [x] 使用 `rg` 检查 `doc/`、`src/main/java/`、`src/main/c/`、`src/test/java/` 中
  `String` / `StringName`、`unsupported` / `不支持` / `gap`、`call_func` wrapper 相关旧表述。
- [x] 同步长期事实源文档中的完成态状态行与职责描述。
- [x] 修正 `frontend_rules.md` 中把已正式支持的 `StringName` / `String` 互通误列为局部放宽风险的表述。
- [x] 补充入站 `call_func` wrapper runtime 锚点，覆盖双向正例与相邻 `NodePath` 负例。
- [x] 运行 targeted tests、IDE 检查与 diff 检查并记录结果：
  - `script/run-gradle-targeted-tests.sh --tests FrontendLoweringToCProjectBuilderIntegrationTest.lowerStringFamilyInboundCallWrapperBuildNativeLibraryAndRunInGodot`，通过。
  - `script/run-gradle-targeted-tests.sh --tests FrontendVariantBoundaryCompatibilityTest,FrontendBodyLoweringSessionTest,FrontendAssignmentSemanticSupportTest,FrontendTypeCheckAnalyzerTest`，通过。
  - `script/run-gradle-targeted-tests.sh --tests FrontendExpressionSemanticSupportTest,FrontendConstructorResolutionSupportTest,ScopeMethodResolverTest,FrontendSubscriptSemanticSupportTest,FrontendLoweringBodyInsnPassTest`，通过。
  - `script/run-gradle-targeted-tests.sh --tests CConstructInsnGenTest,CNewDataInsnGenTest,CGenHelperTest,CCodegenTest`，通过。
  - IDE rebuild `FrontendLoweringToCProjectBuilderIntegrationTest.java`，通过。
  - `git diff --check`，通过。
  - `rg` 旧口径扫描：完成态状态行、`gdcc_intrinsic.h` numeric/vector 误述与当前支持面的冲突表述均已清理；剩余命中只属于本计划的历史问题描述。

复核项：

- Phase 0 已修改的长期事实源文档必须逐项重新查验，确认它们与 Phase 1-6 的最终实现、测试锚点和 runtime wrapper 行为一致：
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_builtin_constructor_variant_argument_plan.md`
  - `doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md`
  - `doc/module_impl/backend/implicit_conversion_implementation.md`
  - `doc/module_impl/backend/builtin_builder_implementation.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`
  - `doc/module_impl/frontend/frontend_string_stringname_implicit_conversion_plan.md`
- Phase 1 修改过的文档必须在本阶段重新查验：
  - `doc/module_impl/frontend/frontend_string_stringname_implicit_conversion_plan.md`
- `rg` 检查 `doc/`、`src/main/java/`、`src/main/c/`、`src/test/java/` 中的旧说法：
  - `String <-> StringName`
  - `StringName / String`
  - `StringName` 与 `String` 同段落出现的 `不支持` / `unsupported` / `gap`
  - `call_func` wrapper 只支持 `float` / `Vector*i` 入站例外的旧表述
- 所有仍保留的负面表述必须属于以下之一：
  - `String <-> NodePath`、`String -> Color` 等本轮明确不支持的相邻 conversion
  - unary/binary operator、explicit cast、builtin keyed metadata 等非 ordinary typed boundary
  - 历史 known-limit 的已修复说明
- `FrontendVariantBoundaryCompatibility` 注释与 `frontend_implicit_conversion_matrix.md` 一致：
  - decision 清单包含 constructor materialization decision
  - rank 表与 resolver 测试一致
  - 不把 `GdStringLikeType` family 写成整体放宽
- `FrontendBodyLoweringSession` 注释与 lowering 测试一致：
  - `String <-> StringName` 走 `ConstructBuiltinInsn`
  - `materializeIntrinsicCast(...)` 仍只描述 intrinsic cast pair
  - 普通 `"..."` 与 `&"..."` 的 literal 路线不混写
- `CGenHelper`、`entry.h.ftl`、`gdcc_intrinsic.h` 注释与 wrapper 测试一致：
  - runtime gate 是唯一错误边界
  - helper 只做 gate 之后的 materialization
  - cross-case 必须走 `StringName(String)` / `String(StringName)` constructor 并销毁中间值
- `CBuiltinBuilder` / `ConstructInsnGen` 注释不暗示 backend constructor matcher 支持 implicit conversion；
  只能说 frontend 已显式生成的 `ConstructBuiltinInsn` 会被 exact constructor path 消费。
- 测试方法名、fixture 注释、`@DisplayName` 不再把本 feature 的正向路径描述成 expected failure。

验收：

- Phase 0 的“必须同步”文档已经全部修改或有明确的无需修改说明。
- 所有规范性支持/拒绝/排序/物化句子都能指向 focused test 或 runtime anchor。
- `doc/gdcc_lir_intrinsic.md` 未新增 string-family intrinsic catalog 项。
- `doc/test_error/test_suite_engine_integration_known_limits.md` 不再把 `String` / `StringName` 互通列为当前剩余 gap。
- 最终验证命令执行前，计划文档、长期事实源、代码注释与测试锚点之间没有相互冲突的事实。

### Phase 8：最终验证

#### Phase 8 执行状态

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具使用要求。
- [x] 使用 MCP `list_directory_tree` 以 depth >= 3 列出 `doc` 与 `doc/module_impl`。
- [x] 使用并行子代理完成 Phase 8 文档复查、代码/测试锚点复查、targeted test 执行与独立审查，并关闭子代理。
- [x] 复查长期事实源、源码注释与测试锚点，确认 `String <-> StringName` 完成态仍是
  source-level `construct_builtin` materialization 与 `call_func` inbound wrapper parity，不新增 backend intrinsic。
- [x] 运行旧口径 drift scan；长期事实源未再出现 Phase 0-only 实现待闭合状态、
  `gdcc_intrinsic.h` 旧 numeric/vector 误述或当前支持面冲突表述。
- [x] 运行 Phase 8 targeted tests 并记录结果：
  - `script/run-gradle-targeted-tests.sh --tests FrontendVariantBoundaryCompatibilityTest,FrontendAssignmentSemanticSupportTest,FrontendTypeCheckAnalyzerTest`，通过。
  - `script/run-gradle-targeted-tests.sh --tests FrontendExpressionSemanticSupportTest,FrontendConstructorResolutionSupportTest,ScopeMethodResolverTest`，通过。
  - `script/run-gradle-targeted-tests.sh --tests FrontendSubscriptSemanticSupportTest,FrontendLoweringBodyInsnPassTest`，通过。
  - `script/run-gradle-targeted-tests.sh --tests CConstructInsnGenTest,CNewDataInsnGenTest`，通过。
  - `script/run-gradle-targeted-tests.sh --tests CGenHelperTest,CCodegenTest`，通过。
- [x] 独立审查确认新增 runtime integration anchor 覆盖双向正例与相邻 `NodePath` 负例；未发现 Phase 8 阻塞问题。

建议 targeted test 命令：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendVariantBoundaryCompatibilityTest,FrontendAssignmentSemanticSupportTest,FrontendTypeCheckAnalyzerTest
script/run-gradle-targeted-tests.sh --tests FrontendExpressionSemanticSupportTest,FrontendConstructorResolutionSupportTest,ScopeMethodResolverTest
script/run-gradle-targeted-tests.sh --tests FrontendSubscriptSemanticSupportTest,FrontendLoweringBodyInsnPassTest
script/run-gradle-targeted-tests.sh --tests CConstructInsnGenTest,CNewDataInsnGenTest
script/run-gradle-targeted-tests.sh --tests CGenHelperTest,CCodegenTest
```

PR 前再按项目要求决定是否运行完整 `clean build`。

## 6. 风险点

- **ownership / destructor 风险**：`String` 与 `StringName` 都是 destroyable value-semantic type，backend constructor path 必须继续通过 `construct_builtin` / `callAssign(...)`，不要走 scalar cast 的裸表达式路径。
- **subscript access kind 漂移**：`Dictionary[StringName, V]` 用 `String` key 后，应基于 materialized `StringName` key 选择 access kind；不得用原始 `String` key 提前选 route。
- **overload ambiguity**：新增 rank 会改变 `String` / `StringName` / `Variant` 混合候选的选择。concrete source 应稳定选 exact 或 constructor materialization；`Variant` source 的同 rank ambiguity 不应被本任务掩盖。
- **文档漂移**：历史事实源曾明确写着 `String <-> StringName` 不支持。实现闭合后仍需 drift check，避免旧合同重新进入长期文档、注释或测试命名。
- **显式 cast 混入**：`CastItem` lowering 当前未实现。不要为了让 `StringName(text)` 或 typed boundary 通过而顺手打开 `cast` / `as`。
- **入站 wrapper 边界**：source-level 隐式转换和 Godot dynamic call 入站 wrapper 是两条路径，但本 feature 要求二者同步完成。不得在缺少入站 wrapper parity 时把实现标记为完成。
- **入站 materializer 退化**：`String <-> StringName` 入站 wrapper 不能只放宽 runtime gate 后统一调用 target-type `Variant` constructor；cross-case 必须先 unpack payload 实际类型，再走 `StringName(String)` / `String(StringName)` constructor，并销毁中间值。
- **backend matcher 放宽风险**：`CBuiltinBuilder.constructBuiltin(...)` 必须继续 exact constructor matching。只允许 frontend 为这两条边界显式生成 `ConstructBuiltinInsn`，不要让 backend 自行接受其它 widened constructor 参数。

## 7. 完成标准

本任务完成时应同时满足：

- `frontend_implicit_conversion_matrix.md` 与实现一致。
- `FrontendVariantBoundaryCompatibility` 对两条边界返回 constructor materialization decision。
- 所有 ordinary typed boundary consumer 通过 shared helper 自动接受双向转换。
- body lowering 为两条边界生成 target-typed temp 与对应 `ConstructBuiltinInsn`。
- C backend 通过现有 `ConstructInsnGen` / `CBuiltinBuilder` 生成 Godot constructor conversion，不新增 `CIntrinsicManager` registry 项。
- GDExtension `call_func` wrapper runtime gate 与 wrapper-local materializer 覆盖双向 string-family runtime tag。
- 入站 wrapper cross-case 使用 `godot_new_StringName_with_String(...)` / `godot_new_String_with_StringName(...)`，并处理中间 value-semantic wrapper cleanup。
- Phase 0 列出的必须同步文档、源码注释和测试命名已经与最终实现一致；Phase 7 drift check 不再发现冲突事实。
- 仍明确无需更新 `doc/gdcc_lir_intrinsic.md` catalog，因为本 feature 没有新增 backend intrinsic。
- `ClassRegistry.checkAssignable(...)` 仍拒绝 `String <-> StringName` 的 strict direct assignability。
- focused tests 覆盖 semantic、ranking、lowering、backend constructor、subscript key/value、`CGenHelper` gate/unpack 与 rendered `entry.h` wrapper body。
