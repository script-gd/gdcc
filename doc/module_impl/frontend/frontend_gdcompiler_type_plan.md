# Frontend GdCompilerType Plan

> 本文档记录 `GdCompilerType` 的实施计划、跨模块边界与验收细则。
> `GdCompilerType` 只用于 GDCC compiler / lowering / LIR / backend 内部 runtime storage typing，
> 不属于 GDScript source-facing 类型系统。

## 文档状态

- 状态：计划维护中
- 更新时间：2026-06-29
- 适用范围：
  - `src/main/java/gd/script/gdcc/type/**`
  - `src/main/java/gd/script/gdcc/scope/**`
  - `src/main/java/gd/script/gdcc/frontend/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
- 关联文档：
  - `doc/analysis/gdcompiler_type_design_risk_analysis.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_lir_intrinsic.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md`
  - Godot docs：`tutorials/scripting/gdscript/gdscript_advanced.rst` 的 `range(...)` / custom iterator 说明

---

## 0. 维护合同

- 本文档是 `GdCompilerType` 实施顺序、禁止边界和验收细则的计划事实源。
- `frontend_implicit_conversion_matrix.md` 仍是 ordinary typed-boundary compatibility 的唯一真源；本文档不得维护第二份 source/target conversion 矩阵。
- `gdcc_lir_intrinsic.md` 仍是 `call_intrinsic` surface、backend registry 和 intrinsic catalog 的事实源；本文档只规定 compiler-only 类型必须通过该通道操作。
- `gdcc_low_ir.md` 仍是 LIR XML surface 的事实源；本文档的 LIR XML 计划落地后必须同步更新该文档。
- 实现时不得用 `Variant`、`DYNAMIC`、`TYPE_META` 或 Godot object metadata 伪装 compiler-only storage。
- 若实现过程中发现某个计划项需要改变 source-facing typing、ordinary boundary、public ABI 或 runtime helper 命名，必须先更新对应事实源文档，再修改代码与测试。

---

## 1. 目标边界

`GdCompilerType` 的设计目标：

- 表示 compiler/backend 为运行时实现需要保存的 C storage 类型。
- 允许作为 LIR 内部 local/temp variable 的类型。
- 允许作为 backend-owned intrinsic 的 operand / result type。
- 每个具体 compiler-only 类型必须提供：
  - C storage type name。
  - init helper function name。
  - destroy helper function name。
- compiler-only 类型按值传递且不可变，这是设计前提，不在实现中额外证明。

`GdCompilerType` 明确不允许进入：

- source-facing declared type parser。
- `ScopeTypeMeta` / type-meta namespace。
- 用户可见 `expressionTypes()` 语义事实。
- 用户 ordinary `slotTypes()`，包括 local / parameter / property / return slot。
- 自定义函数公开签名。
- property / signal / callable outward ABI。
- `Variant` pack / unpack。
- engine method / utility / global / property / index / operator ABI。
- generated `call_func` wrapper metadata、argument unpack 或 cleanup surface。

Godot upstream 的对齐依据是：`GDScriptFunctionState` 注册为 internal class，脚本 analyzer 不把它作为可声明标识符；for-loop iterator state 也停留在 bytecode / VM stack slot / opcode operand 层。GDCC 的 iterator、function state 等 C runtime storage 同样应保持在 IR / backend / runtime support 层，而不是扩展成 GDScript source-facing 类型。

首个 concrete compiler-only type 固定为 `GdccForRangeIterType`，服务未来 GDScript `for i in range(...)` lowering。Godot 文档说明 `range` 支持 `range(n)`、`range(b, n)`、`range(b, n, s)` 三种形态，起点 inclusive、终点 exclusive，默认起点为 `0`、默认步长为 `1`，负步长用于反向迭代；custom iterator 合同也把初始化、推进、取值拆成 `_iter_init()`、`_iter_next()`、`_iter_get()` 三类动作。当前阶段不实现 `for` lowering，只用 `GdccForRangeIterType` 与对应 intrinsics 验证 compiler-only type 的 LIR/backend 内部通路。

---

## 2. 当前冲突面

当前 `GdType` 被多个层面共同消费。新增 compiler-only 分支时，以下默认路径会静默误处理：

- `GdType` 当前 sealed permits 不包含 compiler-only 分支。
- `ClassRegistry.tryParseStrictTextType(...)` 和 `ScopeTypeResolver.tryResolveDeclaredType(...)` 是 source-facing declared type 入口，不能认识 compiler-only 名称。
- `ClassRegistry.findType(...)` 当前被 `DomLirParser` 用于 LIR XML 类型文本解析；若 LIR XML 需要 compiler-only 类型，不能把它塞进普通 declared type parser。
- `ClassRegistry.checkAssignable(...)` 第一条规则是 `getTypeName()` 同名即 assignable，会让 compiler-only 类型穿过 ordinary typed boundary。
- `FrontendVariantBoundaryCompatibility` 当前会允许 stable type -> `Variant` pack、`Variant` -> concrete unpack，并在 default 分支回退 `checkAssignable(...)`。
- `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 会把 accepted boundary 物化为 `PackVariantInsn`、`UnpackVariantInsn`、`CallIntrinsicInsn` 或 `ConstructBuiltinInsn`。
- condition lowering 对非 `bool` / `Variant` stable type 当前可能走 `pack_variant -> unpack_variant(bool)`。
- dynamic call backend path 会把非 `Variant` 参数 pack 成 `Variant`，也可能把 dynamic result unpack 到 target type。
- `DomLirSerializer` 直接写 `GdType.getTypeName()`；`DomLirParser` 直接用 `ClassRegistry.findType(...)` 读回类型文本。
- `CGenHelper.renderGdTypeInC(...)` / `renderGdTypeRefInC(...)` default 会生成 `godot_<Type>` / `godot_<Type>*`。
- `CGenHelper.renderPackFunctionName(...)` / `renderUnpackFunctionName(...)` default 会生成 `godot_new_Variant_with_<Type>` / `godot_new_<Type>_with_Variant`。
- `CGenHelper.renderCopyAssignFunctionName(...)` / `renderDestroyFunctionName(...)` default 会生成 `godot_new_<Type>_with_<Type>` / `godot_<Type>_destroy`。
- `CCodegen.generateFunctionPrepareBlock(...)` 对未知非 void local default 到 `ConstructBuiltinInsn`。
- `CBodyBuilder.renderDefaultValueExpr(...)` 对未知 type default 到 `godot_new_<Type>()`。
- `DestructInsnGen` 当前只显式处理 Godot value/object/meta/container family；compiler-only destroyable type 不能落到 no-op。
- `CGenHelper.renderBoundMetadata(...)` 会通过 `getGdExtensionType() == null` late fail，但这不是足够早的 public ABI 边界。
- `func.ftl` 对函数返回和参数直接调用 `renderGdTypeInC(...)` / `renderGdTypeRefInC(...)`，public 函数签名若混入 compiler-only type 会生成错误 C ABI。

---

## 3. 实施原则

- 先定义边界，再放开路径。任何默认兼容、默认 C helper 命名、默认 metadata 生成都必须对 compiler-only type 显式处理。
- 实现应从 `GdccForRangeIterType` 和一组 range iterator intrinsic 闭环开始。不要在只有一个实现时额外制造一层抽象；如果后续已经有两个以上具体 compiler-only 类型，才引入 `GdCompilerType` sealed interface。
- compiler-only 类型不参与 ordinary frontend typed-boundary matrix。内部 LIR slot 的 direct assignment 由 backend/LIR 合同处理，不由 source-facing semantic compatibility 扩面。
- helper 命名使用 `gdcc_*`，不得伪造 `godot_*` generated binding helper。
- lifecycle 走非对象 destroyable value 路径，不走 object ownership。
- parser、serializer、backend 和 frontend boundary 的错误信息应明确说明 `compiler-only type leaked into ...`，避免让使用者看到晚期 `getGdExtensionType()==null` 或 C symbol 缺失。

---

## 4. LIR XML 策略

MVP 采用以下策略：

- 允许 compiler-only 类型出现在 LIR XML 的 function `<variables>` 中。
- 类型文本使用 LIR-only grammar：`compiler::<Name>`；本阶段唯一合法实例是 `compiler::GdccForRangeIter`。
- 该 grammar 只由 LIR parser / serializer 识别，不进入 `ScopeTypeResolver`、`ClassRegistry.tryParseStrictTextType(...)` 或 source-facing type-meta namespace。
- compiler-only 类型禁止出现在以下 LIR XML surface：
  - function `<parameters>`。
  - function `<return_type>`。
  - `<properties>`。
  - `<signals>` parameter。
  - lambda `<captures>`。
- `is_hidden=true` 函数在 MVP 中也不允许 compiler-only parameter / return type。若未来 backend-owned hidden helper 需要 compiler-only ABI，必须先单独更新本文档和 `gdcc_low_ir.md`，并证明不会生成 outward binding metadata 或 call wrapper。

验收细则：

- happy path：
  - `<variable id="..." type="compiler::GdccForRangeIter">` 可解析为 `GdccForRangeIterType`。
  - `DomLirSerializer` 对该 compiler-only local 输出稳定的 `compiler::GdccForRangeIter`。
  - parser / serializer round-trip 不通过 ordinary source-facing resolver。
- negative path：
  - 用户 declared type resolver 不能解析 `compiler::GdccForRangeIter` 或 bare `GdccForRangeIter`。
  - property / signal / parameter / capture / return type 使用 `compiler::GdccForRangeIter` 时 fail-fast。
  - malformed `compiler::` grammar 报错清晰，不退化成 `GdObjectType("compiler::...")`。

---

## 5. 分步骤实施计划

### 5.1 阶段一：类型协议与 `GdccForRangeIterType`

目标：

- 让 `GdType` 能承载 compiler-only storage type，但不把它暴露给用户类型系统。
- 锁定 `GdccForRangeIterType` 的 C storage、init、destroy 协议。
- 为未来 `for i in range(...)` lowering 准备内部状态类型；本阶段不实现 `for` 语义 lowering。

建议实施内容：

- 更新 `GdType` sealed permits。
- 新增首个具体 compiler-only type：`GdccForRangeIterType`。在只有这一种具体类型时，优先让它直接进入 `GdType` sealed hierarchy；不要为了一个实现先制造单独 `GdCompilerType` 抽象层。
- 类型协议至少覆盖：
  - `getTypeName()`：建议稳定为 `GdccForRangeIter`，用于内部 identity，不作为 source-facing declared type text。
  - LIR-only text：`compiler::GdccForRangeIter`。
  - C storage type name：建议为 `gdcc_for_range_iter`。
  - init helper function name：建议为 `gdcc_for_range_iter_init`。
  - destroy helper function name：建议为 `gdcc_for_range_iter_destroy`。
  - `isNullable() == false`。
  - `getGdExtensionType() == null`。
  - `isDestroyable()` 根据 destroy helper 策略返回 true。
- `GdccForRangeIterType` 代表按值保存的 range iterator state，不表示 GDScript `range(...)` 调用返回的 `Array`。
- 如需 direct C struct assignment，明确该类型不使用 `renderCopyAssignFunctionName(...)` 的 `godot_new_*_with_*` 路径。

验收细则：

- happy path：
  - type unit test 覆盖 `GdccForRangeIterType` 的 stable internal name、LIR-only text、C type name、init helper、destroy helper、nullability、extension metadata。
  - Java sealed switch 编译强制覆盖新增分支。
- negative path：
  - `GdccForRangeIterType` 不被当作 `GdPrimitiveType`、`GdObjectType`、`GdVariantType` 或 `GdMetaType`。
  - 不出现 `godot_GdccForRangeIter`、`godot_new_GdccForRangeIter...`、`godot_GdccForRangeIter_destroy` 形式的默认 helper。
  - 不新增任何 `for` parser / analyzer / lowering 行为。

测试锚点：

- 新增 `src/test/java/gd/script/gdcc/type/GdccForRangeIterTypeTest.java` 或同等具体 type test。
- 更新需要覆盖 sealed switch 的现有 type/backend tests。

### 5.2 阶段二：source-facing resolver 禁止与 LIR-only parser

目标：

- source-facing type namespace 完全看不到 compiler-only type。
- LIR XML 只在 `<variables>` 中通过 `compiler::<Name>` grammar 承载 compiler-only type。

建议实施内容：

- 保持 `ClassRegistry.tryParseStrictTextType(...)` 不识别 compiler-only type。
- 保持 `ScopeTypeResolver.tryResolveDeclaredType(...)` 不识别 compiler-only type。
- 不向 `ClassRegistry.resolveTypeMetaHere(...)` 注册 compiler-only `ScopeTypeMeta`。
- 给 `DomLirParser` 增加 LIR-only type parser，并带 use-site 参数区分 public ABI surface 和 variable surface。
- 给 `DomLirSerializer` 增加 compiler-only type text renderer。
- 在 parser 层提前禁止 public ABI surface 出现 compiler-only type。

验收细则：

- happy path：
  - local variable 的 `compiler::<Name>` XML round-trip 稳定。
  - ordinary builtin / object / container type XML 行为保持不变。
- negative path：
  - `ScopeTypeResolverTest` 证明 declared type 文本无法解析 compiler-only type。
  - `ClassRegistryTypeMetaTest` 证明 registry 不发布 compiler-only type-meta。
  - `DomLirParserTest` 证明 parameter / return / property / signal / capture surface 拒绝 compiler-only type。
  - `ClassRegistry.findType(...)` 不把 `compiler::<Name>` 猜成 object。

测试锚点：

- `ScopeTypeResolverTest`
- `ScopeTypeParsersTest`
- `ClassRegistryTypeMetaTest`
- `FrontendDeclaredTypeSupportTest`
- `DomLirParserTest`
- `DomLirSerializerTest`

### 5.3 阶段三：frontend semantic 与 lowering 边界封堵

目标：

- compiler-only type 不进入用户 semantic facts。
- ordinary typed boundary 不接受 compiler-only source/target。
- lowering 不生成 pack/unpack/construct_builtin 来处理 compiler-only type。

建议实施内容：

- 在 `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)` 最前面拒绝 compiler-only source 或 target。
- 明确 `ClassRegistry.checkAssignable(...)` 的 source-facing consumer 不得把 compiler-only 同名视作 ordinary boundary success；可以通过 frontend helper 前置拒绝实现，不必污染 strict backend assignability 基线。
- 在 `FrontendLocalTypeStabilizationAnalyzer` 和 `FrontendExprTypeAnalyzer` 的 local backfill 风险点增加 fail-closed / fail-fast 策略，防止 compiler-only published expression type 写回 ordinary local slot。
- 在 condition lowering 的非 bool / non Variant pack path 前显式拒绝 compiler-only type。
- 在 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 增加 invariant guard，作为 shared helper 的二次防线。
- 在 call materialization 中，fixed args、vararg tail、dynamic route 均不得接受 compiler-only value。

验收细则：

- happy path：
  - 现有 ordinary boundary：`Variant` pack/unpack、`int -> float`、`Vector*i -> Vector*`、`String <-> StringName` 测试保持通过。
- negative path：
  - compiler-only -> `Variant` reject，不生成 `PackVariantInsn`。
  - `Variant` -> compiler-only reject，不生成 `UnpackVariantInsn`。
  - compiler-only -> compiler-only 不通过 ordinary user boundary。
  - compiler-only condition 不生成 `pack_variant -> unpack_variant(bool)`。
  - fixed call argument、vararg tail、return slot、property store、subscript key/index 都不能 materialize compiler-only boundary。
  - artificial published compiler-only expression type 不能写回 user local slot。

测试锚点：

- `FrontendVariantBoundaryCompatibilityTest`
- `FrontendTypeCheckAnalyzerTest`
- `FrontendLocalTypeStabilizationAnalyzerTest`
- `FrontendBodyLoweringSupportTest`
- `FrontendLoweringBodyInsnPassTest`
- `FrontendWritableTypeWritebackSupportTest`

### 5.4 阶段四：LIR public ABI validator

目标：

- compiler-only type 即使由手写 LIR 或 parser 注入，也不能流入 public ABI。

建议实施内容：

- 增加 LIR validator 或在现有 validation/codegen 前置流程中增加 pass。
- 校验范围至少覆盖：
  - class property type。
  - signal parameter type。
  - public and hidden function parameter type。
  - public and hidden function return type。
  - lambda capture type。
  - generated call wrapper / binding data collection surface。
- MVP 允许 compiler-only type 只出现在 function variables。
- 错误信息使用 `compiler-only type leaked into public ABI` 或具体 surface 名称。

验收细则：

- happy path：
  - 含 compiler-only local variable 和 intrinsic 的 LIR module 可以进入 backend codegen。
- negative path：
  - function parameter / return / property / signal / capture 使用 compiler-only type fail-fast。
  - hidden function parameter / return 在 MVP 中同样 fail-fast。
  - failure 早于 `CGenHelper.renderBoundMetadata(...)` 的 `getGdExtensionType()==null`。

测试锚点：

- 新增或扩展 LIR validation tests。
- `DomLirParserTest`
- `CGenHelperTest`
- backend integration shape tests 中加入 public ABI negative cases。

### 5.5 阶段五：C 后端类型渲染、初始化、赋值与销毁

目标：

- compiler-only storage 在 C 中使用显式 `gdcc_*` helper 和 C type。
- 封堵所有 `godot_*` 默认 helper 路径。

建议实施内容：

- `CGenHelper.renderGdTypeInC(...)` 对 compiler-only type 使用其 C storage type name。
- `CGenHelper.renderGdTypeRefInC(...)` 仅在允许的 internal function / intrinsic helper surface 使用明确策略；public ABI 已由 validator 禁止。
- `renderPackFunctionName(...)` / `renderUnpackFunctionName(...)` 对 compiler-only type fail-fast。
- `renderCopyAssignFunctionName(...)` 对 compiler-only type 不返回 `godot_new_*_with_*`。若所有 compiler-only type 允许 C struct assignment，返回空字符串并让 assignment 走 direct path；若未来需要 deep copy，先扩展 copy helper 协议。
- `renderDestroyFunctionName(...)` 对 compiler-only type 返回其 `gdcc_*_destroy` helper。
- `CCodegen.generateFunctionPrepareBlock(...)` 对 compiler-only local 生成专用初始化路径，不生成 `ConstructBuiltinInsn`。
- `CBodyBuilder.renderDefaultValueExpr(...)` 对 compiler-only type fail-fast，除非该 use-site 明确是 internal helper 且能调用 init helper。
- `CBodyBuilder.needsAddressOf(...)`、`prepareRhsValue(...)`、`prepareReturnValue(...)`、`emitDestroy(...)` 明确处理 compiler-only direct assignment 与 destroy。
- `DestructInsnGen` 对 compiler-only destroyable type 调用 `gdcc_*_destroy`，不能 default no-op。

验收细则：

- happy path：
  - compiler-only local declaration 使用 C storage type name。
  - prepare block 调用 `gdcc_*_init` 或等价专用 init instruction/code path。
  - overwrite、scope cleanup、discarded destroyable value 调用 `gdcc_*_destroy`。
  - direct assignment 不调用 `godot_new_*_with_*`。
- negative path：
  - 生成结果中不出现 `godot_<CompilerType>`。
  - 不出现 `godot_new_<CompilerType>()`。
  - 不出现 `godot_new_Variant_with_<CompilerType>`。
  - 不出现 `godot_new_<CompilerType>_with_Variant`。
  - 不出现 `godot_new_<CompilerType>_with_<CompilerType>`。
  - 不出现 `godot_<CompilerType>_destroy`。

测试锚点：

- `CGenHelperTest`
- `CConstructInsnGenTest`
- `CDestructInsnGenTest`
- `CAssignInsnGenTest`
- `CBodyBuilderPhaseBTest`
- `CBodyBuilderPhaseCTest`

### 5.6 阶段六：`GdccForRangeIterType` intrinsic 最小闭环

目标：

- compiler-only type 只通过 backend-owned intrinsic 操作。
- 每个 intrinsic 的类型合同窄而明确。
- 用 range iterator 的初始化、继续判断、推进、取值闭环验证 compiler-only type 可以支撑未来 `for i in range(...)` lowering。

建议实施内容：

- 为 `GdccForRangeIterType` 新增一组最小 intrinsic。推荐命名如下，最终名称可按现有 intrinsic 命名风格调整，但语义必须保持：
  - `gdcc.for_range_iter.init`：根据 `start`、`end`、`step` 初始化 iterator state。
  - `gdcc.for_range_iter.should_continue`：读取当前 state，返回是否还有当前值可用。
  - `gdcc.for_range_iter.next`：推进到下一个值，并返回推进后的新 iterator state。
  - `gdcc.for_range_iter.get`：读取当前迭代值。
- intrinsic 类型合同：
  - `init` 的 result 必须是非 ref 的 `compiler::GdccForRangeIter`；arguments 为三个 `int` 值，对应 normalized `start`、`end`、`step`。
  - `should_continue` 的 result 必须是非 ref 的 `bool`；argument 为一个 `compiler::GdccForRangeIter`。
  - `next` 的 result 必须是非 ref 的 `compiler::GdccForRangeIter`；argument 为一个 `compiler::GdccForRangeIter`，返回推进后的新 iterator state。
  - `get` 的 result 必须是非 ref 的 `int`；argument 为一个 `compiler::GdccForRangeIter`。
- 未来 lowering 的基本形状应为：`iter = init(start,end,step)`，每轮先 `should_continue(iter)`，再 `value = get(iter)`，循环体结束后 `iter = next(iter)`。这保证 `GdccForRangeIterType` 仍按不可变值语义传递，不需要 ref mutation。
- `range(n)` / `range(b, n)` / `range(b, n, s)` 的参数归一化属于未来 frontend lowering：本阶段只要求 intrinsic 能接受已归一化的三个 `int` 参数。
- `step == 0` 的处理策略必须在 intrinsic catalog 中写清。建议 fail-fast 或 runtime error helper，不允许生成无限循环语义。
- 负步长必须作为合法输入进入 helper 语义：`should_continue` / `next` 对正 step 使用 `< end`，对负 step 使用 `> end`。
- 更新 `doc/gdcc_lir_intrinsic.md` catalog，记录：
  - intrinsic name。
  - LIR textual shape。
  - result 是否必须存在。
  - result 是否允许 ref。
  - argument 数量与类型。
  - C backend 语义。
  - lifecycle / ownership 说明。
- 在 `CIntrinsicManager` 注册白名单。
- 每个 `CIntrinsicFunction` 自己校验 result / arg / arity / ref。
- 成功路径优先复用 `CBodyBuilder.assignVar(...)` 或 `callAssign(...)`，除非该 compiler-only type 需要更窄的写入策略。

验收细则：

- happy path：
  - parser / serializer 保留 `call_intrinsic` textual shape。
  - backend registry 能找到 `gdcc.for_range_iter.init`、`gdcc.for_range_iter.should_continue`、`gdcc.for_range_iter.next`、`gdcc.for_range_iter.get`。
  - 成功 codegen 使用 `gdcc_*` helper，不绕过 slot lifecycle。
  - `range(10)` 归一化后的 `start=0,end=10,step=1`、`range(5,10)` 归一化后的 `start=5,end=10,step=1`、`range(10,0,-1)` 归一化后的 `start=10,end=0,step=-1` 都能用手写 LIR intrinsic 序列生成预期 C helper 调用形状。
- negative path：
  - unknown intrinsic fail-fast。
  - bad arity fail-fast。
  - missing result / unexpected result fail-fast。
  - result ref 不符合合同 fail-fast。
  - result type 错误 fail-fast。
  - argument type 错误 fail-fast。
  - literal operand fail-fast。
  - `step == 0` 的手写 LIR 用例按 catalog 约定 fail-fast 或进入明确 runtime error helper，不允许静默生成无限循环 helper 调用。
  - `GdccForRangeIterType` 不能被传给非上述四个 range iterator intrinsic。

测试锚点：

- `SimpleLirBlockInsnParserTest`
- `SimpleLirBlockInsnSerializerTest`
- `CIntrinsicManagerTest`
- `CallIntrinsicInsnGenTest`
- 新增 `GdccForRangeIter` intrinsic tests，覆盖正步长、负步长、exclusive end、zero step 策略和类型错误。

### 5.7 阶段七：普通 Godot / Variant / engine 路径封堵

目标：

- 即使手写 LIR 绕过 frontend，compiler-only type 也不能进入普通 Godot runtime ABI。

建议实施内容：

- `PackUnpackVariantInsnGen` 显式拒绝 compiler-only pack / unpack。
- `CallMethodInsnGen`、`BackendMethodCallResolver`、`CallGlobalInsnGen`、static call path 显式拒绝 compiler-only receiver / argument / return target。
- operator / index / property instruction generators 显式拒绝 compiler-only operand / receiver / value。
- typed array / dictionary metadata 和 runtime guard leaf 渲染拒绝 compiler-only leaf。
- generated `call_func` wrapper argument gate、unpack expression、destroy stmt 不接受 compiler-only type。

验收细则：

- negative path：
  - `PACK_VARIANT` source 是 compiler-only type fail-fast。
  - `UNPACK_VARIANT` result 是 compiler-only type fail-fast。
  - dynamic call argument 是 compiler-only type 不被 pack 成 `Variant`。
  - dynamic result 不可 unpack 到 compiler-only type。
  - method/global/static/operator/index/property path 遇到 compiler-only type fail-fast。
  - `Array[compiler::<Name>]` / `Dictionary[String, compiler::<Name>]` 不能生成 outward metadata。

测试锚点：

- `CPackUnpackVariantInsnGenTest`
- `CallMethodInsnGenTest`
- `CallGlobalInsnGenTest`
- `COperatorInsnGenTest`
- `IndexLoadInsnGenTest`
- `IndexStoreInsnGenTest`
- `CLoadPropertyInsnGenTest`
- `CStorePropertyInsnGenTest`
- `CGenHelperTest`

### 5.8 阶段八：文档同步与回归收口

目标：

- 实现后所有事实源一致。
- 回归覆盖证明 compiler-only type 是内部 storage typing，而不是 source-facing type。

建议实施内容：

- 更新 `doc/gdcc_type_system.md`：
  - 增加 compiler-only type 的定位。
  - 明确它不属于 GDScript source-facing type set。
  - 明确 ordinary compatibility matrix 不接受它。
- 更新 `doc/gdcc_low_ir.md`：
  - 记录 `compiler::<Name>` LIR-only type grammar。
  - 记录只允许 function variables 的 MVP 约束。
- 更新 `doc/gdcc_lir_intrinsic.md`：
  - 增加 `GdccForRangeIterType` 的四个 intrinsic catalog：init、should_continue、next、get。
- 更新 `doc/gdcc_c_backend.md`：
  - 记录 `gdcc_for_range_iter` C storage type、init/destroy helper、禁止 `godot_*` default helper。
- 更新 `doc/gdcc_runtime_lib.md`：
  - 记录新增 `gdcc_for_range_iter_*` helper 声明/实现边界。
- 如 lifecycle 行为扩面，更新 `doc/gdcc_ownership_lifecycle_spec.md` 或 backend lifecycle contract。

验收细则：

- 所有新增文档不维护第二份 frontend conversion matrix。
- 所有新增 intrinsic 均能从 `gdcc_lir_intrinsic.md` 找到 catalog 条目。
- 计划文档、类型系统、Low IR、C backend、runtime helper 文档中的边界措辞一致。

---

## 6. 非目标

当前计划不覆盖：

- 把 compiler-only type 暴露给 GDScript 用户声明。
- 让 `GdCompilerType` 参与 ordinary implicit conversion。
- 让 compiler-only storage 和 `Variant` 相互转换。
- 把 compiler-only type 传给 engine method、utility function、global function、property、index 或 operator。
- 支持 `Array[CompilerOnly]` / `Dictionary[K, CompilerOnly]` 作为 typed container ABI。
- 支持 public 或 hidden function 使用 compiler-only parameter / return type。
- 把 Godot internal class，例如 `GDScriptFunctionState`，建模为 source-facing GDCC type。
- 一次性实现完整 iterator / async lowering。
- 在本阶段实现 `for` parser / analyzer / lowering，或把 GDScript `range(...)` ordinary call 改写为内部 iterator；当前只实现 `GdccForRangeIterType` 与四个 range iterator intrinsics，作为未来任务的实现与验收锚点。

---

## 7. 建议 targeted test 命令

优先从纯单元测试开始：

```bash
script/run-gradle-targeted-tests.sh --tests "gd.script.gdcc.frontend.sema.analyzer.support.FrontendVariantBoundaryCompatibilityTest"
```

```bash
script/run-gradle-targeted-tests.sh --tests "gd.script.gdcc.scope.resolver.ScopeTypeResolverTest,gd.script.gdcc.scope.resolver.ScopeTypeParsersTest,gd.script.gdcc.scope.ClassRegistryTypeMetaTest,gd.script.gdcc.frontend.sema.FrontendDeclaredTypeSupportTest"
```

```bash
script/run-gradle-targeted-tests.sh --tests "gd.script.gdcc.lir.parser.DomLirParserTest,gd.script.gdcc.lir.parser.DomLirSerializerTest,gd.script.gdcc.lir.parser.SimpleLirBlockInsnParserTest,gd.script.gdcc.lir.parser.SimpleLirBlockInsnSerializerTest"
```

```bash
script/run-gradle-targeted-tests.sh --tests "gd.script.gdcc.backend.c.gen.CGenHelperTest,gd.script.gdcc.backend.c.gen.CPackUnpackVariantInsnGenTest,gd.script.gdcc.backend.c.gen.CIntrinsicManagerTest,gd.script.gdcc.backend.c.gen.CallIntrinsicInsnGenTest"
```

`GdccForRangeIterType` 与四个 intrinsic 实现后补充运行：

```bash
script/run-gradle-targeted-tests.sh --tests "gd.script.gdcc.type.GdccForRangeIterTypeTest,gd.script.gdcc.backend.c.gen.GdccForRangeIterIntrinsicTest"
```

生命周期和 C body shape 实现后再跑：

```bash
script/run-gradle-targeted-tests.sh --tests "gd.script.gdcc.backend.c.gen.CConstructInsnGenTest,gd.script.gdcc.backend.c.gen.CDestructInsnGenTest,gd.script.gdcc.backend.c.gen.CAssignInsnGenTest,gd.script.gdcc.backend.c.gen.CBodyBuilderPhaseBTest,gd.script.gdcc.backend.c.gen.CBodyBuilderPhaseCTest"
```

普通 Godot/backend path 封堵实现后再跑：

```bash
script/run-gradle-targeted-tests.sh --tests "gd.script.gdcc.backend.c.gen.CallMethodInsnGenTest,gd.script.gdcc.backend.c.gen.CallGlobalInsnGenTest,gd.script.gdcc.backend.c.gen.COperatorInsnGenTest,gd.script.gdcc.backend.c.gen.IndexLoadInsnGenTest,gd.script.gdcc.backend.c.gen.IndexStoreInsnGenTest,gd.script.gdcc.backend.c.gen.CLoadPropertyInsnGenTest,gd.script.gdcc.backend.c.gen.CStorePropertyInsnGenTest"
```

frontend lowering shape 实现后再跑：

```bash
script/run-gradle-targeted-tests.sh --tests "gd.script.gdcc.frontend.lowering.FrontendBodyLoweringSupportTest,gd.script.gdcc.frontend.lowering.FrontendLoweringBodyInsnPassTest,gd.script.gdcc.frontend.lowering.FrontendLoweringFunctionPreparationPassTest"
```

最终可以用一个较保守的非 runtime fixture 组合验证边界闭环：

```bash
script/run-gradle-targeted-tests.sh --tests "gd.script.gdcc.frontend.sema.analyzer.support.FrontendVariantBoundaryCompatibilityTest,gd.script.gdcc.scope.resolver.ScopeTypeResolverTest,gd.script.gdcc.lir.parser.DomLirParserTest,gd.script.gdcc.lir.parser.SimpleLirBlockInsnParserTest,gd.script.gdcc.backend.c.gen.CGenHelperTest,gd.script.gdcc.backend.c.gen.CPackUnpackVariantInsnGenTest,gd.script.gdcc.backend.c.gen.CallIntrinsicInsnGenTest"
```

---

## 8. 推进顺序

建议按以下顺序实施并提交，每一步都保持可编译、可回归：

1. 类型协议与 `GdccForRangeIterType`。
2. LIR-only parser / serializer grammar，并先冻结 source-facing resolver 禁止。
3. frontend ordinary typed-boundary、local stabilization、condition lowering、call materialization 的封堵。
4. LIR public ABI validator。
5. C 后端 C type / init / destroy / assignment / pack-unpack / metadata 封堵。
6. `GdccForRangeIterType` 的 init / should_continue / next / get intrinsic 和 runtime `gdcc_for_range_iter_*` helper。
7. 普通 method / global / operator / index / property / wrapper path 负例补齐。
8. 文档同步和 targeted regression。

这条顺序的核心约束是：先让 compiler-only type 不能从用户世界进入，再允许它在 LIR/backend 内部出现；先封掉默认 `Variant` / `godot_*` 路径，再接入具体 intrinsic 成功路径。
