# GdCompilerType 设计风险调研报告

- 日期：2026-06-28
- 范围：本报告只做设计与风险调研，不实现源码改动。事实来源包括当前仓库文档、当前仓库源码、并行子代理调研摘要，以及上游 Godot 公开仓库中与 GDScript 内部运行时状态相关的实现线索。

---

## 1. 执行摘要

计划新增的 `GdCompilerType` 需求可以成立，但它不能被当作普通 GDScript/Godot 类型接入现有 `GdType` 流水线。当前代码库里 `GdType` 同时承担了用户可见语义类型、LIR XML 文本类型、C 类型渲染、Variant pack/unpack helper 命名、binding metadata 和生命周期判断等职责。新增 compiler-only 分支时，最大风险不是 sealed interface 上多一个 permits，而是它被这些默认路径静默当作 Godot builtin 或普通 `Variant` 可转换类型处理。

推荐的设计边界是：

1. `GdCompilerType` 只允许出现在 compiler/lowering/LIR/backend 内部变量或 intrinsic operand/result 上。
2. 它不能进入 source-facing type namespace、用户声明类型解析、`expressionTypes()` 用户语义事实、function/property/signal/callable outward metadata、implicit conversion matrix、`Variant` pack/unpack、engine method/utility/global call ABI。
3. 每个具体 compiler-only 类型必须显式给出 C 类型名、初始化 helper 名和销毁 helper 名。helper 应使用 `gdcc_*` 命名空间，不应复用或伪造 `godot_*` generated binding surface。
4. 如果 compiler-only 类型是 destroyable 非对象值，生命周期必须走非对象 destroyable 路径；如果它按设计是不可变按值传递，代码仍需要避免现有 value-wrapper 复制路径自动拼 `godot_new_<Type>_with_<Type>`。
5. 实现时应优先在边界入口 fail-fast，而不是依赖 `getGdExtensionType() == null` 被后续 metadata helper 偶然拦住。

上游 Godot 的实现也支持这个边界：`GDScriptFunctionState`、iterator state、VM stack slots 属于 runtime/VM/codegen 内部机制，并没有扩展为 `GDScriptDataType` 的公开类型分类。GDCC 如果需要 C runtime 的迭代器、函数状态等结构，应把它们保持在 IR/backend/runtime 私有模型中。

---

## 2. 已复核事实源

### 2.1 文档事实源

- `AGENTS.md`
- `doc/gdcc_type_system.md`
- `doc/gdcc_low_ir.md`
- `doc/gdcc_lir_intrinsic.md`
- `doc/gdcc_c_backend.md`
- `doc/gdcc_runtime_lib.md`
- `doc/gdcc_ownership_lifecycle_spec.md`
- `doc/module_impl/common_rules.md`
- `doc/module_impl/backend/backend_ownership_lifecycle_contract.md`
- `doc/module_impl/backend/lifecycle_instruction_restriction.md`
- `doc/module_impl/backend/variant_abi_contract.md`
- `doc/module_impl/backend/implicit_conversion_implementation.md`
- `doc/module_impl/backend/builtin_builder_implementation.md`
- `doc/module_impl/frontend/frontend_rules.md`
- `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
- `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md`
- `doc/module_impl/frontend/frontend_builtin_constructor_variant_implementation.md`
- `doc/module_impl/frontend/frontend_builtin_property_access_implementation.md`
- `doc/module_impl/frontend/frontend_lowering_plan.md`
- `doc/module_impl/frontend/frontend_lowering_func_pre_pass_implementation.md`
- `doc/module_impl/frontend/scope_type_resolver_implementation.md`
- `doc/module_impl/frontend/scope_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
- `doc/module_impl/frontend/frontend_exact_call_extension_metadata_contract.md`
- `doc/module_impl/frontend/runtime_name_mapping_implementation.md`

备注：`AGENTS.md` 中提到 `doc/module_impl/common_rule.md`，实际仓库文件名是 `doc/module_impl/common_rules.md`。

### 2.2 源码事实源

- `src/main/java/gd/script/gdcc/type/GdType.java`
- `src/main/java/gd/script/gdcc/type/GdVariantType.java`
- `src/main/java/gd/script/gdcc/type/GdVoidType.java`
- `src/main/java/gd/script/gdcc/type/GdExtensionTypeEnum.java`
- `src/main/java/gd/script/gdcc/scope/ClassRegistry.java`
- `src/main/java/gd/script/gdcc/scope/resolver/ScopeTypeResolver.java`
- `src/main/java/gd/script/gdcc/lir/LirVariable.java`
- `src/main/java/gd/script/gdcc/lir/LirFunctionDef.java`
- `src/main/java/gd/script/gdcc/lir/LirParameterDef.java`
- `src/main/java/gd/script/gdcc/lir/parser/DomLirParser.java`
- `src/main/java/gd/script/gdcc/lir/parser/DomLirSerializer.java`
- `src/main/java/gd/script/gdcc/lir/validation/LifecycleInstructionRestrictionValidator.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendVariantBoundaryCompatibility.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendBodyLoweringSession.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/FrontendSubscriptAccessSupport.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/FrontendWritableTypeWritebackSupport.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/CIntrinsicManager.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/intrinsic/CIntToFloatIntrinsic.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/CallIntrinsicInsnGen.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/PackUnpackVariantInsnGen.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- `src/main/c/codegen/template_451/func.ftl`

### 2.3 上游 Godot 参考线索

并行调研覆盖了 `godotengine/godot` 的以下路径：

- `modules/gdscript/gdscript_function.h`
- `modules/gdscript/register_types.cpp`
- `modules/gdscript/gdscript_byte_codegen.cpp`
- `modules/gdscript/gdscript_vm.cpp`
- `modules/gdscript/gdscript_analyzer.cpp`
- `core/variant/variant_setget.cpp`
- `modules/gdscript/tests/scripts/analyzer/errors/invalid_identifier.out`

结论是：Godot 的 iterator/function-state 机制停留在 VM/runtime 层。`GDScriptFunctionState` 是 internal registered class，脚本中不可直接命名；for-loop iterator state 是 codegen/VM 临时槽位组合，不是公开 `GDScriptDataType`。

---

## 3. 当前 GdType 的耦合面

### 3.1 sealed 根接口

`GdType` 当前是 sealed interface，permits 列表只允许 container/meta/nil/object/primitive/rid/string-like/variant/vector/void。新增 `GdCompilerType` 必须改 permits，否则无法实现根接口。

`GdType` 的协议方法很少：

- `getTypeName()`
- `isNullable()`
- `getGdExtensionType()`
- `isDestroyable()`

问题在于 `getTypeName()` 已经被多个层当作不同语义使用：诊断名、LIR XML 文本、C symbol 片段、Godot helper 名称片段、typed container hint 片段。`GdCompilerType` 如果只返回一个普通名字，就会被许多默认路径自动拼成不存在的 Godot wrapper。

### 3.2 ClassRegistry 解析与 assignability

`ClassRegistry.findType(...)` 是 LIR XML 反序列化和兼容解析入口。它先走 strict declared-type resolver，再对兼容模式中的未知 bare identifier 猜成 object。`tryParseStrictTextType(...)` 列出了所有用户/严格声明可识别类型。`ScopeTypeResolver` 也是 source-facing type namespace 的入口。

因此：

- compiler-only 类型不应加入 strict declared type 解析，否则用户声明位置可见。
- 如果 compiler-only 类型需要参与 LIR XML 往返，应新增 LIR-only 解析策略，不能把它注册进普通 type namespace。
- `ClassRegistry.checkAssignable(...)` 当前第一条规则是 `from.getTypeName().equals(to.getTypeName())` 即 assignable。具体 compiler-only 子类型的 `getTypeName()` 必须稳定且唯一；如果未来有参数化内部类型，只靠字符串同名可能过弱。

### 3.3 LIR 类型承载面

`LirVariable`、`LirFunctionDef`、`LirParameterDef` 都直接持有 `GdType`。这使 compiler-only 类型一旦进入 LIR，就天然可能进入：

- 函数参数和返回类型。
- 变量 prepare block。
- return flow 和 `_return_val`。
- C 函数签名模板。
- binding data collection。
- LIR XML serializer/parser。

需求明确“不会出现在自定义函数签名中”。这不等于“不会进入 LIR”；它更准确地要求：若 compiler-only 类型进入 LIR，只能作为内部 local/temp 或 backend-owned hidden surface，不能进入公开 class/function/property/signal ABI。

---

## 4. 前端与用户可见边界

### 4.1 不进入 source-facing 类型解析

frontend/scope 文档都强调 source-facing lookup、canonical identity、human display 和 lowering/private facts 的分层。`ScopeTypeResolver` 只处理源码声明类型文本和 type-meta namespace，`ScopeTypeMeta` 是用户 lexical type namespace 的产物。

`GdCompilerType` 不应出现在：

- `ScopeTypeMeta`
- `ClassRegistry.resolveTypeMetaHere(...)`
- `ScopeTypeResolver.tryResolveDeclaredType(...)`
- frontend skeleton declared type
- extension metadata parser 的普通输出
- source-facing runtime name mapping

如果这些入口能返回 `GdCompilerType`，用户代码就可能声明、引用、诊断展示或通过 `TYPE_META` 路由观察到它。

### 4.2 不进入 expressionTypes 和 ordinary typed boundary

`FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)` 目前有两处会误接纳 compiler-only 类型：

1. target 是 `Variant` 时，除 source 本身是 `Variant` 外，一律 `ALLOW_WITH_PACK`。
2. default 分支回到 `ClassRegistry.checkAssignable(...)`，同名 compiler-only 类型会 `ALLOW_DIRECT`。

`FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 会把这些 decision 物化成 `PackVariantInsn`、`UnpackVariantInsn`、`CallIntrinsicInsn` 或 `ConstructBuiltinInsn`。所以 `GdCompilerType` 不能进入 ordinary frontend typed boundary 的 source/target。若实现层允许某些内部 lowering 生成 compiler-only LIR slot，应绕开用户 typed boundary matrix，而不是给 matrix 加普通兼容规则。

需要显式保持非法的地方：

- `expressionTypes()` 用户语义结果。
- `slotTypes()` 用户 declared/local/property/return slot。
- fixed call arguments。
- return contract。
- property store。
- subscript key/index。
- constructor resolution。
- member/property/call route facts。

### 4.3 不复用 Variant/DYNAMIC/TYPE_META

`Variant` 是用户可见 runtime-open carrier，`DYNAMIC` 是 frontend 对 runtime-open route 的语义事实，`TYPE_META` 是源码层面的类型元值路由。`GdCompilerType` 不是三者中的任何一个。

错误复用的后果：

- 复用 `Variant` 会触发 pack/unpack 和 outward ABI。
- 复用 `DYNAMIC` 会让 dynamic call 结果固定通过 `Variant` 流动。
- 复用 `TYPE_META` 会让源码可见 binding/member/static route 看到 compiler-only 类型。

---

## 5. LIR 与 intrinsic 边界

### 5.1 call_intrinsic 是正确操作通道

`doc/gdcc_lir_intrinsic.md` 和源码 `CIntrinsicManager` / `CallIntrinsicInsnGen` 已经把 `call_intrinsic` 定义为 backend-owned 白名单机制。intrinsic name 是数据，不是任意 C symbol escape hatch；每个 intrinsic 自己校验 result/argument 类型合同。

这正好匹配需求中的“只通过 intrinsic 进行操作”。后续新增涉及 compiler-only 类型的 intrinsic 时，应遵守现有规则：

- 更新 intrinsic catalog。
- 在 `CIntrinsicManager` 注册白名单。
- 每个 intrinsic 显式检查 result/argument arity、ref、类型。
- 不在 intrinsic 中复制变量解析、registry lookup 或 slot lifecycle。
- 使用 `CBodyBuilder.assignVar(...)` / `callAssign(...)` 等统一写入 API，除非该 compiler-only 类型需要专门的值语义分支。
- destroyable 或 object-like result 必须单独审计 ownership/lifecycle，不能照抄 primitive cast。

### 5.2 pack_variant / unpack_variant 必须拒绝

`PackUnpackVariantInsnGen` 当前只校验 unpack 的 source 是 `Variant`，然后直接调用：

- `helper.renderUnpackFunctionName(resultVar.type())`
- `InsnGenSupport.packVariantAssign(...)`

`CGenHelper.renderPackFunctionName(...)` 对非 nil/object 默认拼 `godot_new_Variant_with_<Type>`；`renderUnpackFunctionName(...)` 对非 object 默认拼 `godot_new_<Type>_with_Variant`。这会为 compiler-only 类型生成不存在且语义错误的 helper。

因此应在 pack/unpack instruction generator 或更早的 LIR validator 中显式拒绝 `GdCompilerType`。

### 5.3 call_global / call_method / call_static_method 边界

需求要求 compiler-only 类型“不传入引擎函数中”。当前风险点包括：

- `BackendMethodCallResolver.resolve(...)` 只拒绝 void/nil receiver，其他 receiver 会进入普通 method resolver。
- `CallMethodInsnGen` 的动态参数路径会把非 `Variant` 参数 pack 成 `Variant`。
- known signature call 通过 `checkAssignable(...)` 验证参数和返回。
- `CallGlobalInsnGen` / utility/global helper 路径也依赖 `checkAssignable(...)`、`renderGdTypeInC(...)` 和 pack helper。

因此，compiler-only 类型应在普通 call instruction 边界 fail-fast：

- 不能作为 engine/builtin/object method receiver。
- 不能作为普通 method/global/static call argument。
- 不能作为普通 method/global/static call return target。
- 只能作为特定 intrinsic 的 operand/result，或未来明确标注的 backend-owned hidden helper route。

---

## 6. C 后端冲突点

### 6.1 C 类型名渲染默认危险

`CGenHelper.renderGdTypeInC(...)` 当前 default 返回 `godot_` + `getTypeName()`。`renderGdTypeRefInC(...)` 当前 default 返回 `godot_` + `getTypeName()` + `*`。模板 `func.ftl` 直接用这两个 helper 渲染函数返回值和参数。

如果 `GdCompilerType` 进入这些路径且没有专门分支，就会生成类似 `godot_<CompilerType>` 的伪 Godot 类型。

建议：

- `GdCompilerType` 提供明确 `cTypeName()` 或类似协议。
- `CGenHelper.renderGdTypeInC(...)` 对 `GdCompilerType` 使用该 C 类型名，或在公开 ABI 上 fail-fast。
- `renderGdTypeRefInC(...)` 不能默认沿用 value-wrapper 指针规则；按“按值传递不可变”的设计，compiler-only 类型参数是否需要指针必须由 compiler-only 类型或 intrinsic 合同显式决定。

### 6.2 默认初始化会误走 ConstructBuiltinInsn

`CCodegen.generateFunctionPrepareBlock(...)` 对非参数、非 ref、非 void 变量生成默认初始化。未知类型 default 到 `new ConstructBuiltinInsn(variable.id(), List.of())`。如果 compiler-only local/temp 进入变量表，它会被当作 Godot builtin 构造。

建议：

- 对 `GdCompilerType` 使用其 initialization helper 生成专门 init instruction/code path。
- 如果某个 compiler-only 类型不允许普通变量 prepare，则在 prepare block 生成前 fail-fast。
- 不要把 compiler-only init 塞进 `CBuiltinBuilder` 普通 constructor matcher；这会污染 Godot builtin constructor 合同。

### 6.3 copy/destroy 默认会拼 Godot helper

`CGenHelper.renderCopyAssignFunctionName(...)` 对非 object/primitive/void/nil 默认拼 `godot_new_<Type>_with_<Type>`。`renderDestroyFunctionName(...)` 对 destroyable 非 object 默认拼 `godot_<Type>_destroy`。`CBodyBuilder.prepareRhsValue(...)` 和 `emitDestroy(...)` 会消费这些 helper。

这与需求“每个继承自 `GdCompilerType` 的类型返回初始化函数名和销毁函数名，都是后端 helper 中手动编写的函数”不匹配。应避免 compiler-only 类型落入 `godot_*` 默认命名。

建议：

- `GdCompilerType` 或其具体子类型提供 `initFunctionName()`、`destroyFunctionName()`，必要时还需要 `copyFunctionName()` 或明确“按值浅拷贝合法”。
- 如果类型是不可变按值数据且浅拷贝合法，赋值路径应是 primitive-like direct assignment，而不是 Godot value-wrapper copy ctor。
- 如果类型有 destroy helper，`isDestroyable()` 应为 true，并由 `renderDestroyFunctionName(...)` 走 `gdcc_*` helper。
- 若类型需要 init 但不需要 destroy，也要单独覆盖 prepare block，不能因为 `isDestroyable()==false` 就跳过初始化。

### 6.4 outward metadata 不能接收 compiler-only 类型

`CGenHelper.renderBoundMetadata(...)` 会调用 `requireBoundMetadataType(...)`，该 helper 在 `getGdExtensionType() == null` 时 fail-fast。虽然这可以阻止 compiler-only 类型生成 metadata，但这是较晚的失败点。

更好的防线是在以下更早边界禁止：

- 函数参数/返回。
- property type。
- signal parameter。
- callable outward ABI。
- generated `call_func` wrapper。

否则错误会表现为 backend metadata 生成失败，而不是清晰的 “compiler-only type leaked into public ABI”。

### 6.5 wrapper cleanup 与普通 slot lifecycle 分离

`variant_abi_contract.md` 明确 `call_func` wrapper cleanup 由模板和 `renderCallWrapperDestroyStmt(...)` 管理，普通函数体 slot lifecycle 由 `CBodyBuilder` 管理。compiler-only 类型不应出现在 wrapper local 中；若误出现且 `isDestroyable()==true`，`renderCallWrapperDestroyStmt(...)` 会尝试调用 destroy helper。

这再次说明：仅设置 `getGdExtensionType()==null` 不足以表达 compiler-only。需要 public ABI 前置禁止。

---

## 7. 生命周期与按值不可变设计

用户已经明确说明 compiler-only 子类型都是按值传递的不可变数据，这一点无需检查。实现上仍要处理三个问题：

1. 按值传递不等于无需初始化。prepare block 或 intrinsic result materialization 需要调用 init helper，避免未初始化 C storage。
2. 不可变不等于无需销毁。若具体类型持有 runtime resource，discard、overwrite、scope cleanup 仍必须调用 destroy helper。
3. 按值浅拷贝是否合法需要成为设计事实。如果所有 compiler-only 类型都支持 C struct assignment，`assignVar` 可 direct assign；如果某些类型未来需要引用计数或 deep copy，就需要 copy helper，而不是当前 `godot_new_*_with_*` 默认。

当前 lifecycle 文档对非对象 destroyable 的规则是：

- prepare/copy RHS。
- 必要时 destroy old value。
- 再 assign。
- discard destroyable non-object return value 时立即 destroy。

compiler-only 类型应沿用非对象生命周期，而不是对象 ownership。不要通过 `try_own_object` / `try_release_object` 表达它的生命周期。

如果需要显式 `destruct`，`LifecycleInstructionRestrictionValidator` 的 `INTERNAL` provenance 只允许 compiler internal/temp variable。compiler-only lifecycle 指令应使用 internal/temp 路径，不能暴露成用户变量生命周期操作。

---

## 8. Godot 对齐结论

上游 Godot 的 GDScript 类型层没有 `Iterator`、`FunctionState` 这类公开类型分类。相关机制位于执行层：

- `GDScriptFunctionState` / `CallState` 保存 await/coroutine 恢复状态。
- for-loop 通过 `@counter_pos`、`@container_pos`、`@iterator_temp` 等内部槽位和 VM opcode 实现。
- `Variant::iter_init` / `iter_next` / `iter_get` 是 Variant 层通用迭代 API。
- object iteration 通过 `_iter_init`、`_iter_next`、`_iter_get` 进入对象协议。
- analyzer 只关心用户可见 loop variable 的元素类型，而不是 iterator state 的内部表示。

这给 GDCC 的启发是边界划分，而不是具体类名迁移：

- GDScript 语义类型系统只表达用户能声明、观察、检查、转换的类型。
- iterator/function state 是 backend/runtime 私有状态。
- 如果需要 `GdCompilerType` 表达 C runtime storage，它应被明确标记为 compiler-only，且不得注册进用户 type namespace。

---

## 9. 建议设计形态

### 9.1 类型层

可以新增一个 sealed interface 或 abstract class：

```java
public sealed interface GdCompilerType extends GdType
        permits GdIteratorStateType, GdFunctionStateType {
    @NotNull String getCTypeName();

    @NotNull String getInitFunctionName();

    @NotNull String getDestroyFunctionName();

    @Override
    default boolean isNullable() {
        return false;
    }

    @Override
    default @Nullable GdExtensionTypeEnum getGdExtensionType() {
        return null;
    }
}
```

当前实施计划已经把 `GdCompilerType` 抽象层纳入后续阶段，因此这里应视为目标协议而不是是否抽象的开放问题。需要注意：

- 如果所有具体类型都必须 init/destroy，那么协议方法放在 `GdCompilerType` 上是合理的。
- 如果某些 compiler-only 类型没有 destroy 语义，需要决定是返回 no-op helper，还是允许 `destroyFunctionName()` 为空。当前需求说“返回初始化函数名和销毁函数名”，报告按必有 helper 处理。

计划文档已要求在首个 concrete compiler-only type 落地后补入该抽象层，因此后续实现应以 `GdCompilerType` 为准，不再沿用“只有一个实现时暂不抽象”的旧策略。

### 9.2 解析层

建议拆分：

- 用户 declared type：继续使用 `ScopeTypeResolver` / `ClassRegistry.tryParseStrictTextType(...)`，不支持 compiler-only。
- LIR/internal type：如确实要序列化 compiler-only 类型，新增明确前缀或 grammar，例如 `compiler::<Name>`，并只在 LIR parser 的 internal type parser 中识别。

不要把 compiler-only 名称作为普通 bare identifier 加入 `tryParseStrictTextType(...)`。

### 9.3 后端 helper 命名

根据 `doc/gdcc_runtime_lib.md`，手写 backend helper 应保持 `gdcc_*` / local namespace，除非明确扩展 runtime-provided `godot_*` surface。compiler-only init/destroy helper 应使用 `gdcc_*`，例如：

- `gdcc_iterator_state_init`
- `gdcc_iterator_state_destroy`
- `gdcc_function_state_init`
- `gdcc_function_state_destroy`

不建议使用 `godot_new_<Type>` / `godot_<Type>_destroy`，否则会和 generated Godot binding symbol ownership 混淆。

### 9.4 禁止边界

实现时建议集中增加 fail-fast 检查，错误信息明确使用 “compiler-only type leaked into ...” 语义。至少应覆盖：

- `FrontendVariantBoundaryCompatibility`
- `PackUnpackVariantInsnGen`
- `CallMethodInsnGen` / `BackendMethodCallResolver`
- `CallGlobalInsnGen`
- `OperatorInsnGen`
- `IndexLoadInsnGen` / `IndexStoreInsnGen`
- `LoadPropertyInsnGen` / `StorePropertyInsnGen`
- `CGenHelper.renderBoundMetadata(...)`
- `CCodegen.generateFunctionPrepareBlock(...)`
- LIR parser/serializer public ABI sections

---

## 10. 实现前检查清单

### 10.1 必须先决定的问题

1. `GdCompilerType` 是否允许进入 LIR XML？如果允许，需要独立 type text grammar；如果不允许，parser/serializer 应 fail-fast。
2. compiler-only 类型是否允许作为普通 local variable？如果允许，prepare block 应调用 init helper；如果只允许 intrinsic result temp，应限制变量来源。
3. compiler-only 类型赋值是否一律 C struct assignment？如果不是，需要 copy helper 协议。
4. destroy helper 是否必定存在且非空？如果是，`isDestroyable()` 应为 true；如果不是，需要 no-op policy。
5. hidden/internal function 是否允许使用 compiler-only 参数/返回？如果允许，必须和 public custom function ABI 明确区分。

### 10.2 代码同步点

- `GdType` permits。
- 新增 compiler-only type 类。
- `ClassRegistry`：普通 declared type 禁止，LIR-only 解析策略另设。
- `DomLirParser` / `DomLirSerializer`：如果 LIR XML 支持内部类型，需要 focused 往返测试。
- `FrontendVariantBoundaryCompatibility`：显式拒绝 compiler-only typed boundary，尤其 target `Variant` pack 分支。
- `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`：禁止 ordinary boundary materialization。
- `CGenHelper`：C 类型名、ref 类型名、pack/unpack、copy、destroy、metadata helper。
- `CCodegen.generateFunctionPrepareBlock(...)`：compiler-only init helper。
- `CBodyBuilder`：needs-address、RHS prepare、non-object assignment、discard、return、temp destroy。
- `CIntrinsicManager` 与具体 `CIntrinsicFunction`：注册和 narrow type contract。
- 普通 call/property/operator/index instruction generators：禁止 compiler-only 类型进入 Godot/Variant/engine path。
- runtime `src/main/c/codegen/include_451/gdcc/**`：新增手写 helper 声明/实现。
- `doc/gdcc_lir_intrinsic.md`：新增 intrinsic catalog。
- `doc/gdcc_type_system.md`、`doc/gdcc_low_ir.md`、`doc/gdcc_c_backend.md`、`doc/gdcc_runtime_lib.md`：同步 compiler-only 边界。

### 10.3 测试建议

重点不是测“能创建一个类型类”，而是测边界不会泄漏：

- `GdCompilerType` 不能由用户 declared type resolver 解析。
- `GdCompilerType` 不能进入 `Variant` pack/unpack。
- `GdCompilerType` 不能作为 public function/property/signal metadata。
- `GdCompilerType` 不能传给 call_method/call_global/operator/index/property engine path。
- compiler-only local 初始化调用 `gdcc_*_init`。
- compiler-only destroyable local cleanup 调用 `gdcc_*_destroy`。
- intrinsic 成功路径和坏 arity/result/ref/arg 类型都 fail-fast。
- LIR XML 若支持 internal type，往返稳定；若不支持，错误清晰。

---

## 11. 主要风险列表

1. `target Variant` 自动 pack：`FrontendVariantBoundaryCompatibility` 会直接允许 source -> `Variant`，必须显式排除 compiler-only。
2. 同名赋值：`ClassRegistry.checkAssignable(...)` 以 `getTypeName()` 同名为第一优先，可能让 compiler-only 类型参与 ordinary boundary。
3. C 类型渲染 default：`CGenHelper.renderGdTypeInC(...)` / `renderGdTypeRefInC(...)` 会拼 `godot_*`。
4. prepare block default：未知类型会变成 `ConstructBuiltinInsn`。
5. copy/destroy helper default：会拼 `godot_new_*_with_*` 和 `godot_*_destroy`。
6. pack/unpack helper default：会拼 `godot_new_Variant_with_*` 和 `godot_new_*_with_Variant`。
7. public ABI late failure：`getGdExtensionType()==null` 只会在 metadata helper 较晚失败，不足以表达设计边界。
8. dynamic call 参数打包：普通动态 dispatch 会把非 `Variant` 参数 pack 成 `Variant`。
9. typed container hint：如果 compiler-only 类型进入 `Array[T]` / `Dictionary[K,V]`，typed container metadata 需要 GDExtension type/hint，会失败或误导。
10. wrapper cleanup：如果 compiler-only destroyable 类型进入 generated `call_func` wrapper，模板可能生成不应存在的 cleanup。
11. `DestructInsnGen` 现有分类只显式处理 object/variant/string-like/meta/container，其他类型默认 no-op；compiler-only destroyable 需要新增分支或走统一 destroy helper。
12. Godot surface 混淆：helper 使用 `godot_*` 会和 generated binding/provided symbol 机制混淆。

---

## 12. 结论

`GdCompilerType` 的正确定位应是“GDCC backend/lowering 内部 runtime storage 类型”，不是 GDScript 用户语义类型。它可以作为 `GdType` 的子分支来复用 LIR variable typing，但必须同步收紧所有用户可见和 Godot ABI 边界。实现时最关键的工作是封住默认路径：Variant pack/unpack、C helper 命名、prepare block builtin construction、metadata generation、engine call、普通 frontend typed boundary。

如果后续实现目标只是 for-loop iterator 或 async function state，建议先从一个具体 compiler-only type 和一组 intrinsic 做最小闭环：定义 C type/init/destroy、LIR internal local、intrinsic 操作、C backend init/destroy/assign 策略，并用 focused tests 证明它不会流入用户签名、Variant、engine call 和 outward metadata。
