# Frontend Singleton Receiver Lowering Plan

> Target: GitHub issue #36, `Frontend lowering cannot materialize singleton receivers into LIR`.
>
> Scope: frontend semantic facts are already mostly present. This plan focuses on making a
> `SINGLETON` binding lower into an object receiver slot through the existing `load_static`
> `@GlobalScope` path, then reusing the existing `CallMethodInsn` route for
> `Singleton.method(...)`.

## 1. 调研结论

`Engine.get_frames_drawn()` / `Input.is_*()` 这类调用当前不是 name-resolution 缺口，而是 body lowering
和 `load_static` 后端语义缺口。

Godot 文档和源码文档都把 engine singletons 建模在 `@GlobalScope` 上：

- Godot docs 的 `@GlobalScope` 页面在 Properties 表中列出 `Engine`、`Input`、`OS` 等 singleton。
- Godot 源码 `doc/classes/@GlobalScope.xml` 说明 singletons 因为可从任意位置访问而记录在该类下。
- 因此本计划采用 `load_static "@GlobalScope" "<singleton_name>"` 表达 singleton property read。
  旧 `load_singleton` / `LoadSingletonInsn` 方案已确认弃用；本文所有 receiver materialization、测试和验收都以
  `LoadStaticInsn(resultId, "@GlobalScope", singletonName)` 为唯一目标。历史记录中提到的 singleton receiver
  materialization 风险，都应按这个 `LoadStaticInsn` surface 重新解释。

已有链路如下：

- `ClassRegistry.resolveValueHere(...)` 会把 extension API singleton 暴露为 `ScopeValueKind.SINGLETON`。
- `FrontendTopBindingAnalyzer` 会把它发布为 `FrontendBindingKind.SINGLETON`。
- chain-head 语义路径已经把 `SINGLETON` 当作 ordinary value receiver，而不是 type-meta 或 global function。
- `FrontendCfgGraphBuilder` 对 attribute base 会先 `buildValue(...)`，裸 `IdentifierExpression` 会变成
  `OpaqueExprValueItem`。
- `FrontendIdentifierOpaqueExprInsnLoweringProcessor` 目前只支持 `LOCAL_VAR`、`PARAMETER`、`CAPTURE`、
  `PROPERTY` 和部分 `CONSTANT`，所以 `SINGLETON` 最终落入 unsupported 分支。
- `FrontendCallInsnLoweringProcessor` 已经能把 materialized receiver slot 发给 `CallMethodInsn`，
  这条路径不需要重写。

现有 `load_static` 链路如下：

- `LoadStaticInsn(resultId, className, staticName)` 已经能承载两个字符串 operand。
- `doc/gdcc_low_ir.md` 已定义 `$<result_id> = load_static "<class_name>" "<static_name>"`，
  且 `<class_name>` 可为 `@GlobalScope`。
- `LoadStaticInsnGen` 当前已有 `@GlobalScope` 分支，但只按 global constant 处理，并先校验目标为 `int`。
  这会错误拒绝 `load_static "@GlobalScope" "Engine"` 这类 object singleton property read。
- `ModuleLocalGodotBinding.Singleton` 与 `engine_method_binds.h.ftl` 已有
  `godot_global_get_singleton(...)` wrapper 生成路径，但当前 `LoadStaticInsnGen` 不会使用它。
  现有 singleton factory / template 仍把 wrapper owner 当作 lookup string 使用；实现本计划时必须把
  lookup name 与 declared return type name 的数据模型彻底拆开。
- fixed Godot bindings 已经提供一组 runtime-provided wrapper，其中包括
  `godot_Engine_singleton()`、`godot_ClassDB_singleton()`、`godot_Object_call()` 和部分 fixed constants。
  module-local singleton wrapper 也会按 `godot_<lookupName>_singleton()` 形成 C function name；因此
  `Engine`、`ClassDB` 这类既是 `@GlobalScope` singleton、又已有 fixed wrapper 的名字，不能等到最终 C
  编译/验收才发现重复声明或重复定义。实现前必须先把 provided symbol 过滤、module-local C-name 冲突检查和
  usage session commit 语义作为 Step 1 / Step 4 的前置合同固定下来。

需要特别固定的负路径事实：

- `ExtensionSingleton.name()` 是 singleton lookup name，`ExtensionSingleton.type()` 是 declared object type，
  两者在 metadata 模型中是独立字段。
- `ClassRegistry.findSingletonType(singletonName)` 当前只在 singleton metadata 命中且 `type` 字段非空时返回
  `GdObjectType`；找不到 singleton 或 `type` 为空时返回 `null`。它不会校验这个 type string 是否能被 strict type
  namespace / class registry 解析。
- `ClassRegistry.resolveValueHere(...)` 只有在 `findSingletonType(...)` 非空时才发布
  `ScopeValueKind.SINGLETON`。因此“metadata 声明了 singleton lookup name，但 `type` 字段缺失/为空”不会自然流入
  `FrontendTopBindingAnalyzer`、chain binding、CFG 或 body lowering；而“`type` 字符串存在但不可严格解析”当前会继续包装成
  object type 并把失败延后。
- 这两类 metadata / declared-type 不一致的 owner 固定为 `ClassRegistry` 的 singleton metadata validation：
  - `ExtensionSingleton.type()` 缺失、为空或 strict declared-type 解析失败，都必须由 registry 侧在发布
    `ScopeValueKind.SINGLETON` / 供 backend 查询 `findSingletonType(...)` 前拦住。
  - `ScopeTypeResolver` 只提供 strict declared-type 解析能力，不拥有 singleton metadata diagnostic，也不能把 unknown type
    invent 成 `GdObjectType`。
  - `FrontendCompileCheckAnalyzer` 只消费已发布的 compile-surface facts；它可以作为最终 lowering gate 阻断
    `expressionTypes()` / `resolvedMembers()` / `resolvedCalls()` / `slotTypes()` 中已经发布的 blocker 状态，但不是
    singleton metadata validator，也不是 `findSingletonType(...)` 的替代实现。
  - body lowering 和 backend 只能保留 invariant fail-fast，不能把看不见的 unknown identifier 重新解释成 singleton，
    也不能把错误归属漂移到 generic unsupported identifier materialization。

关键约束：

- `doc/gdcc_low_ir.md` 已有 `load_static` surface，不需要新增 parser / serializer / DOM LIR surface。
- `doc/module_impl/backend/load_static_implementation.md` 当前把 `@GlobalScope` 限定为 global constants；实现本计划时必须同步扩展该文档的长期语义边界，把 `@GlobalScope` singleton properties 纳入 `load_static` 支持面。
- `doc/module_impl/frontend/frontend_rules.md` 要求 body lowering 消费已发布的 `resolvedCalls()` /
  `expressionTypes()` / `symbolBindings()`，不得重跑 scope lookup 或 callable signature 推导。
- `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md` 冻结了 instance-style runtime receiver
  继续复用 `CallItem` + `CallMethodInsn`。
- `doc/module_impl/backend/godot_binding_implementation.md` 已定义 module-local singleton getter，但现有说明仍按 lookup == type
  描述；实现本计划时必须同步更新为 `godot_<lookupName>_singleton()` lookup wrapper。
- `CallGlobalInsnGen` 当前只接受 utility function / backend helper，不会自动登记 `godot_<lookupName>_singleton`
  module-local binding；因此不能简单让 frontend 生成 `CallGlobalInsn("godot_Engine_singleton")`。
- `CBodyBuilder.callAssign(...)` 不是中性的“调用并赋值”工具。它的 object result 分支经由
  `emitCallResultAssignment(...)` 进入 `emitObjectSlotWrite(..., OwnershipKind.OWNED)`，语义上把返回值当作 fresh
  object producer 直接 consume。
- Godot `global_get_singleton` 返回的是 engine registry 中已存在的 singleton object pointer。本仓库的
  `godot_<lookupName>_singleton()` module-local wrapper 只做 lookup/cache/fail-fast；它不是
  `classdb_construct_object*`，也不向调用方转移对象所有权。

## 2. 设计决策

复用现有 LIR static-load surface：

```text
$<result_id> = load_static "@GlobalScope" "<singleton_name>"
```

这不是 singleton-call instruction。`Engine.get_frames_drawn()` 的最终调用仍必须是：

```text
$<receiver> = load_static "@GlobalScope" "Engine"
$<result> = call_method "get_frames_drawn" $<receiver>
```

不采用以下方案：

- 旧 `load_singleton` opcode / `LoadSingletonInsn` / `LoadSingletonInsnGen` 方案已弃用，不再作为实现目标或测试期望。
  Godot 已把 singleton 建模为 `@GlobalScope` property，本仓库现有 `LoadStaticInsn` 结构也足以表达 owner/member
  两段路径。
- 不把 singleton method call 降成 `CallGlobalInsn`，否则会把 instance call 语义混入 global utility surface。
- 不扩展 `CallGlobalInsn` 成任意 `godot_*` C symbol escape hatch；这会绕开 backend provided/module-local binding 合同。
- 不新增 singleton-specific call route；receiver 物化之后继续走现有 `CallMethodInsn`。
- 不把 `load_static "@GlobalScope" "<singleton>"` backend lowering 实现为 `CBodyBuilder.callAssign(...)` 到 object slot。
  `callAssign(...)` 的对象返回值合同是 fresh `OWNED` producer；singleton getter 是 `BORROWED` producer。混用会跳过
  borrowed-source slot retain，后续 managed-slot cleanup 可能释放从未取得过的引用。

`load_static` 的 `staticName` 使用 singleton lookup name，例如 `"Engine"`。result slot 类型来自
`ClassRegistry.findSingletonType(singletonName)` 返回的 declared object type，而不是从名字二次猜测。

module-local singleton binding 的数据模型必须显式区分四类事实：

- `lookupName`：来自 `ExtensionSingleton.name()` / `LoadStaticInsn.staticName()`，唯一用途是 Godot singleton registry lookup
  与 lookup diagnostic。`engine_method_binds.h.ftl` 必须把它用于
  `godot_global_get_singleton(GD_STATIC_SN(u8"<lookupName>"))`，不能通过 `symbol.owner()` 或 declared type 反推。
- `returnTypeName`：来自 `ExtensionSingleton.type()` / `ClassRegistry.findSingletonType(lookupName)`，用于生成 C return type、
  cast type、result slot assignability check 和 diagnostic `context.type`。例如 lookup name 为 `"GameSingleton"`、
  declared type 为 `"Node"` 时，返回类型必须是 `godot_Node *`。
- C symbol identity：由 `GodotBindingSymbol` 承载。singleton symbol 必须使用
  `family = SINGLETON`、`owner = "@GlobalScope"`、`name = lookupName`、
  `cFunctionName = "godot_<lookupName>_singleton"`。这样两个不同 singleton 即使 declared type 相同也不会共享
  wrapper/cache。
- ABI signature：由 `GodotBindingSymbol.signatureKey()` 承载。singleton symbol 必须使用
  `returnType = "godot_<returnTypeName> *"`、空参数列表、`vararg = false`；module-local usage session 的
  canonical key / C-name conflict check 继续用它阻断不兼容合并。

因此 backend 必须提供并使用 `ModuleLocalGodotBinding.singleton(lookupName, returnTypeName)` 这一窄入口。
该入口构造的 `Singleton` 数据形态固定为
`record Singleton(GodotBindingSymbol symbol, String lookupName, String returnTypeName)`（字段顺序可按项目风格调整，但三者必须
都是显式字段）。`returnTypeName` 不能只隐含在 `symbol.returnType()` 字符串里。所有从 metadata / `LoadStaticInsnGen`
来的 production singleton binding 都必须调用 two-name 入口；保留的 single-name test shorthand 只能 delegate 到
`singleton(name, name)`。`Singleton.cacheName()` 必须从 `cFunctionName` 或 `lookupName` 派生，不能只从 `returnTypeName`
派生，避免多个 singleton 声明同一 return type 时共用缓存。

fixed wrapper 与 module-local wrapper 的边界是 backend binding 层的前置合同，不是最终验收补救项：

- fixed wrapper 继续由 `FixedGodotBindings` / `Godot451FixedBindings` 生成到 `godot_fixed_binding.h/.c`，
  并通过 `GodotBindingProvidedSymbols.forRegistry(...)` 进入 runtime-provided C function name set。不要把
  `godot_Engine_singleton()`、`godot_ClassDB_singleton()` 这类 fixed singleton wrapper 复制进
  `ModuleLocalGodotBinding` 的 module header 输出。
- module-local wrapper 只补 runtime 未提供的 singleton / class constant surface，并且只通过
  `GodotBindingUsageSession` 的 buffer -> commit -> snapshot 流程进入 `engine_method_binds.h.ftl`。
  `engine_method_binds.h.ftl` 不能自行发现或补写 `godot_*` wrapper，也不能接收 provided C symbol。
- 对 `LoadStaticInsnGen` 来说，记录 `ModuleLocalGodotBinding.singleton("Engine", "Engine")` 是允许的窄入口调用，
  但 usage buffer / session 必须因为 `godot_Engine_singleton` 已在 provided set 中而忽略这条 module-local binding；
  后续 `recordCall("godot_Engine_singleton")` 只校验 provided symbol 可用，不产生 module-local snapshot。
- 对 `GameSingleton -> Node` 这类 runtime 未提供的 singleton，session 必须提交
  `godot_GameSingleton_singleton()` module-local binding，且 canonical key / merge compatibility 必须同时覆盖
  `family`、`owner="@GlobalScope"`、`name=lookupName`、`cFunctionName`、`signatureKey()`、`lookupName`
  和显式 `returnTypeName`。同一 C function name 对应不同 lookup / return ABI 时必须 fail-fast。

前端仍保留 `SINGLETON` 作为普通 value binding。`TYPE_META` 继续服务 class / builtin / enum 的静态 route，不把 singleton
硬塞进 type-meta 命名空间。body lowering 只是在已发布 `SINGLETON` binding 进入 opaque identifier materialization 时发射
`LoadStaticInsn(result, "@GlobalScope", binding.symbolName())`。

## 3. 分步实施

### Step 1: 先补回归测试骨架

状态（2026-06-24）：已完成。新增/扩展了 frontend body、property-init preparation、CFG build、
pass-manager end-to-end、registry metadata validation、backend `load_static`、binding usage/template 和
codegen snapshot 测试，覆盖 positive / negative path 以及 fixed-provided vs module-local singleton wrapper 边界。

目标是在任何实现前先钉住当前失败和期望形态。

改动范围：

- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`
- property-init pipeline 回归还必须覆盖现有 property initializer focused tests，优先考虑：
  - `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringFunctionPreparationPassTest.java`
  - `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBuildCfgPassTest.java`
- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringPassManagerTest.java`
- 如需单独验证 CFG publication，可补 `src/test/java/gd/script/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilderTest.java`
- singleton metadata validation 负路径需要单独补 focused tests，首责在 registry 侧；compile gate 只补“published
  fact 已经是 blocker 时能阻断 lowering”的最终封口测试，优先考虑：
  - `src/test/java/gd/script/gdcc/scope/ClassRegistryTest.java`
  - `src/test/java/gd/script/gdcc/scope/ClassRegistryScopeTest.java`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`
  - 必要时补 `src/test/java/gd/script/gdcc/frontend/sema/FrontendSemanticAnalyzerFrameworkTest.java`
- backend `load_static` singleton property tests 应补到
  `src/test/java/gd/script/gdcc/backend/c/gen/CLoadStaticInsnGenTest.java`，不要新增 `CLoadSingletonInsnGenTest`。
- binding usage / template 的前置回归必须在 Step 1 一起补，不等最终验收：
  - `src/test/java/gd/script/gdcc/backend/c/gen/binding/FixedGodotBindingsTest.java`
  - `src/test/java/gd/script/gdcc/backend/c/gen/binding/usage/GodotBindingUsageSessionTest.java`
  - `src/test/java/gd/script/gdcc/backend/c/gen/binding/ModuleLocalGodotBindingTemplateTest.java`
  - `src/test/java/gd/script/gdcc/backend/c/gen/CCodegenEngineMethodUsageSessionTest.java`

测试 fixture：

- 优先用 deterministic `ExtensionAPI`，显式包含 `ExtensionSingleton("GameSingleton", "Node")` 这类
  `lookupName != returnTypeName` fixture；`ExtensionSingleton("Engine", "Engine")` 只能作为相等场景的补充。
- 如果方法解析需要真实 engine method metadata，则基于 `ExtensionApiLoader.loadDefault()` 复制 default API 并替换/补充
  `singletons`，避免依赖外部 Godot API 列表的偶然内容。
- 不修改 `src/test/resources/extension_api_metadata_fixture.json`，除非多个测试都需要共享 singleton fixture。

需要新增的 frontend lowering 测试：

- expression position：`return Engine.get_frames_drawn()` 降成一个 `LoadStaticInsn("@GlobalScope", "Engine")`
  加一个 `CallMethodInsn`，`CallMethodInsn.objectId()` 等于 `LoadStaticInsn.resultId()`。
- statement position void call：`Engine.set_*()` 或等价 void singleton instance call 不发布 standalone call result slot，
  但仍先生成 singleton receiver slot。
- argument preservation：`Input.is_action_pressed("ui_accept")` 或等价带参数 singleton call，参数 slot 顺序与 existing call
  materialization 保持一致。
- property initializer expression：`var frames: int = Engine.get_frames_drawn()` 或等价 singleton-backed initializer
  必须进入 `FunctionLoweringContext.Kind.PROPERTY_INIT`，并在 `_field_init_<property>` helper 的真实 LIR body 中生成一个
  `LoadStaticInsn("@GlobalScope", "Engine")` 加一个 `CallMethodInsn`；`CallMethodInsn.objectId()` 必须等于
  `LoadStaticInsn.resultId()`，`ReturnInsn.returnValueId()` 必须指向 call result。
  这是历史 “property init helper 里 materialize singleton receiver” 风险的当前方案表述；不得把期望写回
  已弃用的 `LoadSingletonInsn` surface。
- property-init pipeline shape：同一个 singleton-backed initializer 至少要覆盖 preparation、CFG publication 和 pass-manager
  end-to-end 三层事实：
  - preparation 阶段只发布 hidden `_field_init_<property>` shell，`sourceOwner` 是 property declaration，
    `loweringRoot` 是 initializer expression。
  - CFG build 阶段发布 expression-rooted `PROPERTY_INIT` graph，缺失 published fact 时仍 fail-fast。
  - 默认 pass manager 终态下 helper 拥有真实 `LirBasicBlock`、有效 `entryBlockId` 和真实 `ReturnInsn`，不能停在
    shell-only 中间态。
- negative path 分层：
  - registry/scope test 钉住 `ExtensionSingleton("GameSingleton", "Node")` 按 lookup name 解析 value binding，
    `findSingletonType("GameSingleton").getTypeName() == "Node"`，且 `resolveTypeMeta("GameSingleton")` /
    `findType("GameSingleton")` 不把 singleton lookup name 当成 type name。
  - registry/scope test 钉住 `findSingletonType(...) == null` 时 `resolveValueHere(...)` 不发布
    `ScopeValueKind.SINGLETON` 的现有事实，防止后续误以为 body lowering 还能看见该 singleton。
  - registry validation test 钉住 metadata 有 lookup name 但 `type` 字段缺失/为空时，`ClassRegistry` 不发布
    `ScopeValueKind.SINGLETON`，并暴露 registry-owned validation fact；用户可见 diagnostic 由 frontend compile/lowering
    入口翻译该 fact。
  - registry validation test 钉住 `type` 字符串存在但不可通过 `ClassRegistry.tryResolveDeclaredType(...)` strict resolve
    时，`ClassRegistry` 不得只返回 `new GdObjectType(typeText)`，也不得把该 singleton 放行到 backend 首次失败。
  - compile gate test 只覆盖最终封口：当上游已经发布 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 的
    compile-surface fact 时，`FrontendCompileCheckAnalyzer` 阻止进入 lowering；不要把它写成 singleton metadata
    首个 validator。
  - lowering test 只保留漏放后的 invariant 断言：坏状态不能被重新解释成 unknown identifier 或 generic unsupported
    identifier materialization。

前端 shape 断言：

- 必须存在 `CallMethodInsn`，且 receiver slot 非空。
- singleton method call 不能被表达为 `CallGlobalInsn`。
- singleton receiver 必须被表达为 `LoadStaticInsn("@GlobalScope", "<singleton_name>")`。
- 不新增、不期望旧 `LoadSingletonInsn`、`LOAD_SINGLETON` 或 `load_singleton` 文本 surface。
- `resolvedCalls()` 的 selected exact/dynamic route 不被 body lowering 重算。
- 上述 shape 断言必须同时覆盖 `EXECUTABLE_BODY` 和 `PROPERTY_INIT`。property initializer 不是 executable body 的旁路；
  它进入同一个 CFG/body lowering session，最终 helper body 中的 receiver materialization 也必须使用同一套
  `LoadStaticInsn` -> `CallMethodInsn` 形态。

后端 shape 断言：

- `new LoadStaticInsn("receiver", "@GlobalScope", "Engine")` 对 object result slot 生成
  `godot_Engine_singleton()` wrapper 调用；因为该 C symbol 是 fixed runtime-provided wrapper，usage session 必须接受调用但
  不把它提交到 module-local snapshot，也不能让 `engine_method_binds.h.ftl` 输出同名 `static inline` wrapper。
- `new LoadStaticInsn("receiver", "@GlobalScope", "ClassDB")` 也必须走同一 provided-symbol 过滤合同，防止
  `godot_ClassDB_singleton()` 同时出现在 fixed binding header 和 module-local header。
- `new LoadStaticInsn("receiver", "@GlobalScope", "GameSingleton")` 在 declared type 为 `Node` 时生成
  `godot_GameSingleton_singleton()` 调用，登记的 `ModuleLocalGodotBinding.Singleton` 同时保留
  `lookupName == "GameSingleton"` 与 `returnTypeName == "Node"`。
- `ModuleLocalGodotBindingTemplateTest` 必须使用 `lookupName != returnTypeName` fixture，断言：
  - wrapper signature / cast / cache 类型使用 `godot_<returnTypeName> *`
  - `godot_global_get_singleton(...)` 使用 `lookupName`
  - `context.lookup_name` 使用 `lookupName`
  - `context.owner` 使用 `"@GlobalScope"`
  - `context.type` 使用 `returnTypeName`
  - C function name / cache identity 不从 `returnTypeName` 反推 lookup
- `GodotBindingUsageSessionTest` 必须钉住三类 session 行为：
  - provided fixed singleton C names（至少 `godot_Engine_singleton` / `godot_ClassDB_singleton`）被
    `recordGodotCall(...)` 接受，但不会进入 `moduleLocalBindings()` / `moduleLocalCFunctionNames()`。
  - non-provided singleton `GameSingleton -> Node` 通过 two-name binding 被提交，C name 是
    `godot_GameSingleton_singleton`，return ABI 是 `godot_Node *`。
  - 相同 C name 对应不同 `lookupName` / `returnTypeName` / `signatureKey()` 时，在 buffer 或 committed session
    层 fail-fast，而不是合并成一个 usage entry。
- `CCodegenEngineMethodUsageSessionTest` 或等价 codegen snapshot test 必须确认使用 `Engine` / `ClassDB` singleton
  时，最终 `engine_method_binds.h` 不包含同名 module-local wrapper；使用 `GameSingleton -> Node` 时才包含
  `godot_GameSingleton_singleton()`。
- `load_static "@GlobalScope" "<global_constant>"` 的原有 integer global constant 行为不回归。
- `@GlobalScope` singleton property 和 global constant 的分支顺序必须让 object singleton 不先落入
  global-constant-only 的 `int` 校验。

### Step 2: 固定 `ClassRegistry` 的 singleton metadata validation owner

状态（2026-06-24）：已完成。`ClassRegistry` 现在预计算 valid singleton object type cache 与
registry-owned invalid metadata facts；`findSingletonType(...)` 只返回 strict validated object type。

目标是把 invalid singleton metadata 的责任落到一个地方，而不是继续保留多个候选 owner 的开放表述。

改动范围：

- `src/main/java/gd/script/gdcc/scope/ClassRegistry.java`
- `src/test/java/gd/script/gdcc/scope/ClassRegistryTest.java`
- `src/test/java/gd/script/gdcc/scope/ClassRegistryScopeTest.java`
- 如需把 registry-owned invalid facts 翻译成 frontend diagnostic，再补：
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
  - 或一个窄的 frontend analyzer/pass 文件，但不要把它并入 `FrontendCompileCheckAnalyzer`
  - `src/test/java/gd/script/gdcc/frontend/sema/FrontendSemanticAnalyzerFrameworkTest.java`

实现细则：

- `ClassRegistry` 是 singleton metadata 的唯一 owner：`ExtensionSingleton.name()` / `type()` 的关系、strict declared-type
  解析、以及有效 singleton type cache 都必须在 registry 侧维护。
- 不新增独立 public validator service。若需要表达 invalid fact，优先在 `ClassRegistry` 内部使用 nested record / enum，
  例如记录 `lookupName`、raw `type` 文本和 reason；对外只暴露最小只读查询。
- 不把 `DiagnosticManager` 注入 `ClassRegistry`、`Scope` 或 `ScopeTypeResolver`。`diagnostic_manager.md` 已冻结
  `DiagnosticManager` 不进入 `scope` / shared resolver；registry 只暴露事实，frontend compile/lowering 入口负责把这些事实
  翻译成诊断。
- `ClassRegistry` 构造完成所有 API map 后，应预计算：
  - valid singleton declared type：`lookupName -> GdObjectType`
  - invalid singleton metadata：`lookupName -> invalid fact`
- `ExtensionSingleton.type()` 为 `null`、blank，或通过 `ClassRegistry.tryResolveDeclaredType(typeText)` strict resolve 失败时，
  该 singleton 必须进入 invalid fact 表，不能进入 valid type cache。
- strict resolve 成功但结果不是 `GdObjectType` 时，同样视为 invalid singleton metadata；engine singleton receiver 必须是 object
  receiver，不能把 builtin / enum / container type 当成 singleton object receiver。
- `findSingletonType(lookupName)` 必须只返回已验证的 valid type cache；不得再直接
  `new GdObjectType(ExtensionSingleton.type())`。
- `resolveValueHere(...)` 继续只在 `findSingletonType(...) != null` 时发布 `ScopeValueKind.SINGLETON`。invalid metadata
  不能被发布成普通 unknown identifier，也不能用 guessed object type 绕过 strict validation。
- `resolveTypeMetaHere(...)` / `findType(...)` 仍不能把 singleton lookup name 当作 type-meta route。singleton 是普通
  value binding，`@GlobalScope` 只是 LIR/backend owner string。
- frontend compile/lowering 入口若发现 registry 暴露 invalid singleton metadata fact，必须在进入 lowering 前发清晰诊断并停止；
  这不是 `FrontendCompileCheckAnalyzer` 的 published-fact scan 职责。compile gate 仍只扫描 supported executable body /
  supported property initializer island 上已经发布的 `expressionTypes()`、`resolvedMembers()`、`resolvedCalls()`、`slotTypes()` 状态。
- body lowering 只保留协议不变量 fail-fast：若已经拿到 `FrontendBindingKind.SINGLETON` 却无法从 registry 取得已验证 declared
  type，说明上游发布合同被破坏。

验收：

- `ExtensionSingleton("GameSingleton", "Node")` 预计算为 valid singleton type，`findSingletonType("GameSingleton")`
  返回 `GdObjectType("Node")`。
- `ExtensionSingleton("BadSingleton", null)` / blank type / unknown type / non-object type 进入 invalid metadata fact，
  `findSingletonType("BadSingleton") == null`，`resolveValueHere("BadSingleton", ...)` 不发布 `SINGLETON`。
- strict declared-type 解析必须复用 `ClassRegistry.tryResolveDeclaredType(...)` / `ScopeTypeResolver`，但 diagnostic owner 不在
  `ScopeTypeResolver`。
- compile/lowering 入口对 invalid singleton metadata 的用户可见失败发生在 lowering 前；不能表现为
  `FrontendIdentifierOpaqueExprInsnLoweringProcessor` 的 generic unsupported identifier，也不能等 backend `LoadStaticInsnGen` 首次发现。

### Step 3: 修复 top binding resolved value 与 expression/chain receiver 解析漂移

状态（2026-06-25）：已完成。当前 `symbolBindings()` 发布的 resolved value 是稳定的 use-site fact，但后续
expression type 与 chain receiver 解析只读取 `FrontendBindingKind` 后，会在 ordinary value receiver 路径重新执行
`currentScope.resolveValue(...)`。这会绕过 `FrontendVisibleValueResolver` 的 declaration-order、自引用过滤和
deferred boundary 合同，导致同一个 bare identifier 在 top binding 与后续语义阶段恢复出不同 value。

实施清单：

- [x] 扩展 `FrontendBinding`，显式记录 top binding 阶段的 resolved value 与 allowed/blocked 状态。
- [x] 更新 `FrontendTopBindingAnalyzer` 的 ordinary value 发布路径，让 `FOUND_ALLOWED` / `FOUND_BLOCKED`
  写入同一份 resolved value payload。
- [x] 更新 expression type 与 chain head receiver 消费路径，禁止 value-kind binding 回退到
  `currentScope.resolveValue(...)` 重新竞争。
- [x] 增加 later-local、initializer self-reference、missing resolved value、lowering alignment 回归测试。
- [x] 参考 `doc/test_suite.md` 增加端到端资源，覆盖 Step 3 的 later-local 与 initializer self-reference drift
  场景经过 frontend lowering、C backend、Godot runtime 后仍按 singleton receiver 执行。
- [x] 运行 targeted tests 并记录验证结果。

问题背景：

- `FrontendVariableAnalyzer` 会在 callable-local inventory 阶段提前把 supported ordinary local 写入 `BlockScope`；
  这保证后续 phase 看到稳定 scope graph，但也意味着 shared `Scope.resolveValue(...)` 本身不表达 statement-order。
- `FrontendTopBindingAnalyzer` 在 executable body 中通过 `FrontendVisibleValueResolver` 解析 bare value name。
  resolver 会过滤 declaration-after-use local、自引用 initializer local，并在 filtered hit 之后继续向 outer/class/global
  scope 查找；因此 `Engine.get_frames_drawn()` 前方若存在 later local `var Engine`，top binding 仍可正确发布
  `FrontendBindingKind.SINGLETON`。
- `FrontendExpressionSemanticSupport.resolveValueIdentifierExpressionType(...)` 与
  `FrontendChainHeadReceiverSupport.resolveValueReceiver(...)` 当前会重新调用 `currentScope.resolveValue(...)`。
  shared scope lookup 命中当前 `BlockScope` 的同名 local 后立即停止，无法知道该 local 对 use-site 来说其实是 future
  declaration 或 initializer self-reference。
- CFG / body lowering 侧又按 `symbolBindings()` materialize identifier：`SINGLETON` 会降成
  `load_static "@GlobalScope" "Engine"`。如果 chain semantic 已经按 later local 类型解析 member/call，最终会出现
  `resolvedCalls()` / `expressionTypes()` 与实际 receiver materialization 不一致。

成因链路摘要：

```text
variable inventory
  -> BlockScope already contains later local Engine
top binding
  -> FrontendVisibleValueResolver filters later/self local
  -> symbolBindings()[Engine use-site] = SINGLETON
expression type / chain receiver
  -> reads binding.kind() only
  -> currentScope.resolveValue("Engine") returns local Engine
  -> receiver type/member/call facts drift away from top binding resolved value
body lowering
  -> requireBinding(Engine use-site) still materializes @GlobalScope.Engine
```

修复方向：

- `FrontendBinding` 必须记录 top binding 阶段已经解析出的 resolved value，而不是只记录 `symbolName`、`kind` 与
  `declarationSite`。
- 对 ordinary value binding（`PARAMETER`、`LOCAL_VAR`、`CAPTURE`、`PROPERTY`、`SIGNAL`、`CONSTANT`、
  `SINGLETON`、`GLOBAL_ENUM`），`FrontendTopBindingAnalyzer` 在 `publishScopeValueBinding(...)` /
  `publishValueResolution(...)` 中把 `ScopeValue resolvedValue` 与 allowed/blocked 状态写进 `FrontendBinding`。
  这可以是 `@Nullable ScopeValue resolvedValue` 加一个直接的 access flag；不要新增一层只有一个实现的 resolver
  abstraction，也不要让后续阶段从 `declarationSite` 反推类型。
- `FrontendExpressionSemanticSupport.resolveValueIdentifierExpressionType(...)` 必须优先消费 binding 中的
  `resolvedValue.type()` 与 access 状态。若 value-kind binding 缺少 resolved value，说明上游 publication contract 被破坏，
  应发布 `FAILED` / fail-fast detail，而不是回退到 `currentScope.resolveValue(...)`。
- `FrontendChainHeadReceiverSupport.resolveValueReceiver(...)` 必须同样消费 binding 中的 exact resolved value 来构造
  `ReceiverState.resolvedInstance(...)` / `blockedFrom(...)`。该 helper 只允许使用 `scopesByAst` 判断 skipped subtree
  这类缺失上下文；不能再把 scope lookup 当成 resolved value 恢复机制。
- `FrontendBinding.declarationSite()` 继续保留给诊断和测试断言使用，但 value type / receiver type 的真源必须是
  top binding 发布的 resolved value。这样 later local、initializer self-reference local、outer fallback、singleton/global enum
  等路径都复用同一份 resolved value。
- local `:=` slot 类型稳定化与 expression type fallback backfill 若改写 `BlockScope` 中的 local `ScopeValue`，必须按
  declaration identity 刷新已发布 binding payload；这不是重新解析 use-site，而是让同一个 resolved value slot 的类型与
  后续 slot writeback 保持同步。
  需要这一步的原因是 `BlockScope.resetLocalType(...)` 会替换 immutable `ScopeValue`；Step 3 之后
  expression type / chain receiver 不再用 `currentScope.resolveValue(...)` 重新读取当前 slot，而是直接消费
  `FrontendBinding.resolvedValue()`。如果 fallback backfill 不调用
  `FrontendExprTypeAnalyzer.refreshPublishedLocalValues(...)`，已发布 use-site binding 会继续指向 backfill 前的
  `Variant` payload，导致 `BlockScope` 中的 local slot 已经变窄、但后续语义仍按旧 payload 分析。
- body lowering 仍按 `symbolBindings()` materialize runtime receiver，不新增 singleton-specific call route，也不重跑
  callable signature 推导。

改动范围：

- `src/main/java/gd/script/gdcc/frontend/sema/FrontendBinding.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendTopBindingAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendLocalTypeStabilizationAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendChainHeadReceiverSupport.java`
- focused tests:
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzerTest.java`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendChainBindingAnalyzerTest.java`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendChainHeadReceiverSupportTest.java`
  - 必要时补 `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`

测试要求：

- later local 遮蔽 singleton：在同一 block 中先使用 `Engine.get_frames_drawn()`，再声明 `var Engine: String = ""`
  或等价非 Engine receiver。断言 chain head binding 是 `SINGLETON`，base expression type 是 registry 中的 singleton
  declared object type，`resolvedCalls()` 按 `Engine.get_frames_drawn()` 发布，而不是按 local `String` / `Variant`
  receiver 失败或转动态。
- initializer 自引用 local：`var Engine: String = Engine` 或等价场景中，右侧 `Engine` 的 binding resolved value 必须是
  `SINGLETON`，expression type 必须来自 singleton resolved value，而不是当前 local slot 的 declared type。
- chain head support focused test：手工构造一个 published `SINGLETON` binding，同时在当前 `BlockScope` 中放入同名
  local，`resolveHeadReceiver(...)` 仍必须使用 binding 携带的 singleton `ScopeValue.type()`。
- negative contract test：若 value-kind `FrontendBinding` 缺少 resolved value，expression/chain semantic 必须产生精确
  `FAILED` detail；不得悄悄退回 `currentScope.resolveValue(...)`。
- lowering 回归可以复用现有 singleton receiver shape 断言，只需补一个 later-local 版本证明 semantic fact 与
  `LoadStaticInsn("@GlobalScope", "Engine")` materialization 对齐。

验收：

- 后续 semantic 阶段不再因为 later local、initializer self-reference local 或同名 current-scope value 重新解析 resolved value。
- `FrontendVisibleValueResolver` 仍是 executable body bare value 可见性合同的唯一 owner；shared `Scope.resolveValue(...)`
  继续只表达 lexical inventory lookup。
- `symbolBindings()`、`expressionTypes()`、`resolvedMembers()`、`resolvedCalls()` 与 CFG/body lowering 使用同一个
  top binding resolved value。
- 不新增独立 public binding-resolution service，不新增 singleton-specific semantic route，不改变 `TYPE_META` 静态 route。

验证（2026-06-25）：

- `script/run-gradle-targeted-tests.sh --tests FrontendTopBindingAnalyzerTest,FrontendExpressionSemanticSupportTest,FrontendChainHeadReceiverSupportTest,FrontendChainReductionFacadeTest,FrontendChainBindingAnalyzerTest,FrontendExprTypeAnalyzerTest,FrontendLoweringBodyInsnPassTest`
  通过。
- `script/run-gradle-targeted-tests.sh --tests GdScriptUnitTestCompileRunnerTest.listsExpectedBundledUnitScripts`
  通过，确认新增 `runtime/singleton_receiver_binding_drift.gd` 被 test-suite 资源清单收录。
- `script/run-gradle-targeted-tests.sh --tests GdScriptUnitTestCompileRunnerTest.compilesAndValidatesRuntimeScripts`
  通过，确认新增 drift fixture 可完整经过 frontend lowering、C backend build 与 Godot runtime validation。

### Step 4: 扩展 `load_static` 的 `@GlobalScope` 语义

状态（2026-06-24）：已完成。`LoadStaticInsnGen` 先处理 `@GlobalScope` singleton property，再回退
global constant；singleton getter 通过 `ModuleLocalGodotBinding.singleton(lookupName, returnTypeName)` 登记，
并按 borrowed object source 写入 receiver slot。

改动范围：

- `src/main/java/gd/script/gdcc/backend/c/gen/insn/LoadStaticInsnGen.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/ModuleLocalGodotBinding.java`
- `src/main/c/codegen/template_451/engine_method_binds.h.ftl`
- `doc/gdcc_low_ir.md`
- `doc/module_impl/backend/load_static_implementation.md`
- `doc/module_impl/backend/godot_binding_implementation.md`
- `src/test/java/gd/script/gdcc/backend/c/gen/CLoadStaticInsnGenTest.java`
- `src/test/java/gd/script/gdcc/backend/c/gen/binding/ModuleLocalGodotBindingTemplateTest.java`
- `src/test/java/gd/script/gdcc/backend/c/gen/binding/usage/GodotBindingUsageSessionTest.java`

实现细则：

- 先更新 backend binding / usage session 合同，再接 `LoadStaticInsnGen`：
  - `GodotBindingProvidedSymbols.forRegistry(...)` 必须继续把 `FixedGodotBindings.symbols(...)` 纳入 provided set，
    并有 targeted test 钉住 `godot_Engine_singleton`、`godot_ClassDB_singleton` 等 fixed names。
  - `ModuleLocalGodotBindingUsageBuffer.record(...)` 与 `ModuleLocalGodotBindingUsageSession.putFromBuffer(...)`
    必须在提交前过滤 provided C function name；过滤发生在 module-local snapshot 之前，不能推迟到模板渲染阶段。
  - `recordCall(...)` / `recordUsedGodotBindingCall(...)` 只能验证“provided 或已显式登记”，不能从函数名反推并创建
    module-local binding。
  - `entry.h` 的 include 顺序下，fixed declarations 会先经 `godot_binding.h` 暴露，module-local header 后包含；
    因此 provided filtering 是防止同名 fixed declaration 与 module-local `static inline` definition 冲突的必要条件。
- 不改 `GdInstruction`、`LoadStaticInsn`、`ParsedLirInstruction`、simple parser、simple serializer 或 DOM parser/serializer。
- `LoadStaticInsnGen` 的 `@GlobalScope` 分支应同时支持：
  - singleton properties：`load_static "@GlobalScope" "Engine"` 等 object result
  - 既有 top-level global constants：`load_static "@GlobalScope" "OK"` 等 `int` result
- singleton property 分支通过 `bodyBuilder.classRegistry().findSingletonType(staticName)` 获取已通过 registry validation 的
  declared object type；该方法返回 `null` 表示不存在 valid singleton type，backend 不能再从 raw
  `ExtensionSingleton.type()` 重新包装或猜测 return type。
- 先按 singleton metadata 判断是否为 `@GlobalScope` property，再保留既有 global constant fallback；这样 object singleton
  不会被现有 `GdIntType.INT` global constant 校验提前拒绝。
- 校验 declared singleton type 可赋给 result variable type。
- 发射调用前记录对应 `ModuleLocalGodotBinding.singleton(staticName, declaredType.getTypeName())`。provided fixed symbol
  会被 usage session 过滤，只接受调用；runtime 未提供的 singleton 才进入 generated module header，输出
  `godot_<lookupName>_singleton()` wrapper，且 wrapper 返回 / cast 为 `godot_<returnTypeName> *`。
- 发射 singleton getter wrapper 调用时，必须把 call expression 建模为 `BORROWED` object value。
  推荐路径是使用 `bodyBuilder.assignVar(...)` / `assignExpr(...)` 加 `valueOfExpr(..., PtrKind.GODOT_PTR)` 这类 borrowed
  `ValueRef`，或新增同等窄 helper；不要给 `callAssign(...)` 增加 boolean/flag 分支。
- 明确禁止在 singleton branch 中调用 `bodyBuilder.callAssign(...)`、`valueOfOwnedExpr(...)` 或任何会把
  `godot_<lookupName>_singleton()` 结果标成 `OwnershipKind.OWNED` 的路径。
- 如果 target 是 managed object slot，slot write 必须按普通 borrowed-source 规则处理：必要时为新 slot acquire/retain 自己的引用，
  并由后续 cleanup 释放这一次 acquire。getter 本身不转移 ownership；ptr conversion 也不得改变 `BORROWED` provenance。
- `assignExpr(...)` 不会自动登记 `godot_*` wrapper；singleton branch 必须在发射调用前显式
  `recordModuleLocalGodotBinding(...)`。`recordCall(...)` / `recordUsedGodotBindingCall(...)` 只能作为
  “已登记或 runtime-provided”校验，不负责生成 module-local wrapper。
- `ModuleLocalGodotBinding.Singleton` 的 merge compatibility 必须同时比较 `lookupName`、`returnTypeName`、
  C function name 与 `signatureKey()`；同一 C function name 对应不同 return ABI 必须继续 fail-fast。
- `ModuleLocalGodotBinding.singleton(lookupName, returnTypeName)` 生成的 C function name 仍是
  `godot_<lookupName>_singleton()`，但“生成名字”不等于“一定输出 module-local wrapper”。`Engine` / `ClassDB`
  这类 fixed-provided singleton 只应输出调用，module-local binding 记录会被 provided set 吃掉；`GameSingleton -> Node`
  这类 non-provided singleton 才会进入 `usedModuleLocalBindings`。
- `engine_method_binds.h.ftl` 的 singleton branch 必须使用：
  - `${binding.returnType()}` / `${binding.returnTypeName()}` 渲染 signature、cache、cast 与 `context.type`
  - `${binding.escapedLookupName()}` 渲染 `godot_global_get_singleton(GD_STATIC_SN(...))` 与 `context.lookup_name`
  - `"@GlobalScope"` 或等价常量渲染 `context.owner`
  - `${binding.cFunctionName()}` 渲染 wrapper function identity
  不允许再用 `${binding.escapedOwner()}` 作为 singleton lookup string。

验收：

- 不新增任何 LIR opcode、record、parser branch 或 serializer branch。
- `load_static "@GlobalScope" "Engine"` 能 materialize object singleton receiver。
- `load_static "@GlobalScope" "Engine"` / `"ClassDB"` 使用 fixed runtime-provided wrapper；session snapshot 与
  `engine_method_binds.h` 不包含同名 module-local wrapper。
- `load_static "@GlobalScope" "GameSingleton"` 在 metadata 声明 `GameSingleton -> Node` 时能 materialize
  `Node` object receiver，并生成按 `GameSingleton` lookup、按 `Node` 返回的 module-local wrapper。
- provided fixed symbol 过滤、non-provided module-local singleton 提交、same-C-name 不兼容 fail-fast 三组测试必须在
  `GodotBindingUsageSessionTest` 中先通过，不能只靠最终 generated C 编译暴露冲突。
- 既有 `@GlobalScope` global constant、global enum、builtin constant、engine class integer constant tests 不回归。
- parser / serializer 只需沿用现有 `LoadStaticInsn` 覆盖；补一条
  `$engine = load_static "@GlobalScope" "Engine";` round-trip，证明 `@GlobalScope` string operand 继续走通用
  `LoadStaticInsn` surface，但不得为 singleton 新增专用 parser negative cases。
- `doc/gdcc_low_ir.md` 和 `doc/module_impl/backend/load_static_implementation.md` 明确 `@GlobalScope` owner 同时覆盖 global
  constants 与 singleton properties。

### Step 5: 接入 frontend body lowering

状态（2026-06-24）：已完成。`FrontendBindingKind.SINGLETON` opaque identifier materialization 现在发射
`LoadStaticInsn(result, "@GlobalScope", binding.symbolName())`，并复用既有 `CallMethodInsn` receiver 路径。

改动范围：

- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendOpaqueExprInsnLoweringProcessors.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendBodyLoweringSession.java`

实现细则：

- 在 `FrontendIdentifierOpaqueExprInsnLoweringProcessor.lower(...)` 的 `switch(binding.kind())` 中新增 `SINGLETON`。
- `SINGLETON` 分支只消费 `FrontendBinding` 与 `ClassRegistry` 已发布事实，不做 scope lookup。
- `SINGLETON` 分支发射 `new LoadStaticInsn(resultSlotId, "@GlobalScope", binding.symbolName())`。
- 在 `FrontendBodyLoweringSession` 增加窄 helper，例如 `emitLoadGlobalScopeSingleton(...)` 或
  `requireSingletonType(binding)`，只消费已验证的 declared type，并在缺失时作为协议不变量失真 fail-fast。
- target 使用 `session.resultSlotId(item)`，保持 `cfg_tmp_<valueId>` materialization 命名合同。
- 保持 `FrontendCfgGraphBuilder`、`FrontendCallInsnLoweringProcessor` 和 `materializeCallReceiverLeaf(...)` 不变，除非新增测试证明
  publication invariant 不足。

验收：

- `IdentifierExpression + SINGLETON` ordinary read 能 materialize 为 `LoadStaticInsn("@GlobalScope", singletonName)`。
- attribute call base 的 singleton receiver slot 被后续 `CallMethodInsn.objectId()` 复用。
- `PROPERTY_INIT` context 中的 singleton receiver 使用同一 materialization 路径，最终写入真实 `_field_init_<property>`
  helper body，而不是只存在于 executable function body 的测试覆盖里。
- singleton-backed property-init helper 在 body pass 之后必须有真实 basic block、有效 `entryBlockId` 和真实 `ReturnInsn`；
  其 return value 来自 singleton method call result。
- implicit self fallback、direct-slot alias、writable-route payload 逻辑不被修改。
- `SELF` contract violation 仍保持 fail-fast，不能因为新增 `SINGLETON` 分支而放宽。

### Step 6: 保持 semantic / CFG 边界稳定

状态（2026-06-24）：已完成。semantic / receiver kind 边界保持不变；CFG builder 仅补齐 singleton
opaque value publication 的窄缺口，没有新增 singleton-specific call route 或 type-meta route。

补充（2026-06-25）：Step 3 会修改 semantic fact 的 payload，让后续 receiver/type 恢复消费 top binding resolved value。
本阶段的“保持边界稳定”仍指不新增 singleton-specific route、不让 CFG builder 重跑 chain reduction；不再表示
semantic analyzer 完全不需要改动。

改动范围：

- 除 Step 3 的 `FrontendBinding` resolved value payload 修复外，默认不修改其他 semantic analyzer。
- 默认不修改 CFG builder。
- 仅在测试显示缺口时补 focused semantic/CFG tests。

验证点：

- `ClassRegistry.resolveValueHere(...)` 继续把 singleton 发布为 immutable non-writable value。
- `FrontendTopBindingAnalyzer` 继续发布 `FrontendBindingKind.SINGLETON`。
- `FrontendChainHeadReceiverSupport` 继续把 singleton 当 instance-style value receiver。
- `FrontendVisibleValueResolver` 的 declaration-order 规则不变：singleton 不受 local statement order 过滤。
- `TYPE_META` 静态 route 继续用于 class / builtin / global enum 等静态 member，不因为 singleton receiver materialization 而改变。

如果发现 metadata 有 singleton lookup name 但 `type` 字段缺失/为空，不能等待 `FrontendTopBindingAnalyzer`、CFG 或 body lowering
“发现”它：当前 `resolveValueHere(...)` 在 `findSingletonType(...) == null` 时不会发布 `ScopeValueKind.SINGLETON`，后续阶段根本看不到
singleton 事实。如果 `type` 字符串存在但不能被 strict type namespace / class registry 解析，也必须在进入 lowering 前由
`ClassRegistry` registry-side validation 暴露 invalid metadata fact，并由 frontend compile/lowering 入口报告后停止，不能让
backend/lowering 成为首个用户可见失败点。body lowering 只保留“已发布 `SINGLETON` binding 却无法取得已验证 declared type”
这一协议不变量的 fail-fast。

### Step 7: 文档和测试收尾

状态（2026-06-24）：已完成。已同步 `doc/gdcc_low_ir.md`、
`doc/module_impl/backend/load_static_implementation.md` 和
`doc/module_impl/backend/godot_binding_implementation.md` 的长期语义。

状态（2026-06-25）：已补充 test-suite 端到端资源 `runtime/singleton_receiver_calls.gd`，覆盖
singleton-backed property initializer、`Engine.get_frames_drawn()` 返回值调用、`Engine.set_time_scale(...)`
statement-position void 调用，以及 `Input.is_action_pressed(...)` 带参调用；同时补充 body-lowering focused
negative test，钉住“已发布 `SINGLETON` binding 但 registry metadata 缺失”时必须在 `requireSingletonType(...)`
边界 fail-fast，不能漂移成 unknown/unsupported identifier。

补充（2026-06-25）：已按 `doc/test_suite.md` 的资源配对规则新增
`runtime/singleton_receiver_binding_drift.gd` 端到端用例，专门锚定 Step 3 的 resolved value payload 合同：
同一方法内先调用 `Engine.get_frames_drawn()` 再声明同名 local，以及 `var Engine := Engine.get_frames_drawn()`
initializer 自引用场景，都必须在完整编译运行链路中继续消费 top binding 发布的 singleton receiver。

验证（2026-06-24）：frontend singleton lowering / property-init pipeline / LIR round-trip targeted
tests、registry/backend/binding targeted tests、`git diff --check` 与 `./gradlew classes --no-daemon --info --console=plain`
均已通过。

需要同步更新：

- `doc/gdcc_low_ir.md`：更新 `load_static` 描述，说明 `@GlobalScope` owner 可表示 top-level global constants 与 singleton properties。
- `doc/module_impl/backend/load_static_implementation.md`：把长期支持面从四类读取扩展为包含 `@GlobalScope` singleton properties，并记录
  singleton branch 的 borrowed ownership 合同。
- `doc/module_impl/frontend/frontend_singleton_receiver_lowering_plan.md`：保持 backend、frontend lowering、文档三条链路同步，避免只描述
  frontend materialization。
- 更新 `doc/module_impl/backend/godot_binding_implementation.md` 对 singleton getter lookup/return type、C symbol identity
  与 ABI signature 的说明。
- 不更新 `doc/gdcc_lir_intrinsic.md`；本计划不使用 intrinsic。

推荐 targeted test 命令：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendLoweringBodyInsnPassTest.runLowersSingletonValueReceiverAsInstanceReceiverInExecutableBody
script/run-gradle-targeted-tests.sh --tests FrontendLoweringBodyInsnPassTest.runLowersSingletonReceiverPropertyInitializerIntoExecutableInitFunction
script/run-gradle-targeted-tests.sh --tests FrontendLoweringFunctionPreparationPassTest.runPublishesSingletonBackedPropertyInitContextAndKeepsShellOnly
script/run-gradle-targeted-tests.sh --tests FrontendLoweringBuildCfgPassTest.runPublishesSingletonBackedPropertyInitCfgGraph
script/run-gradle-targeted-tests.sh --tests FrontendLoweringPassManagerTest.lowerToContextHandlesSingletonBackedPropertyInitializerEndToEnd
script/run-gradle-targeted-tests.sh --tests SimpleLirBlockInsnParserTest.parse_loadStaticGlobalScopeSingletonSurfaceRoundTripsThroughSerializer
script/run-gradle-targeted-tests.sh --tests ClassRegistryTest,ClassRegistryScopeTest
script/run-gradle-targeted-tests.sh --tests CLoadStaticInsnGenTest
script/run-gradle-targeted-tests.sh --tests FixedGodotBindingsTest,GodotBindingUsageSessionTest,ModuleLocalGodotBindingTemplateTest,CCodegenEngineMethodUsageSessionTest
```

如果测试命名在实现时调整，最小覆盖面仍必须包含：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendLoweringBodyInsnPassTest,FrontendLoweringFunctionPreparationPassTest,FrontendLoweringBuildCfgPassTest,FrontendLoweringPassManagerTest
```

如果实际改动触及 frontend lowering、backend codegen 和文档，最终再运行：

```bash
./gradlew classes --no-daemon --info --console=plain
```

### Step 8: 明确 dual-role 名称的 value / type-meta route 风险

状态（2026-06-26）：问题陈述已确认，route 策略由 Step 9 窄化方案落地。本 Step 保留成因链路和影响分类
作为 Step 9 的前置背景；Godot 引擎行为已在实现前通过 `godotengine/godot` 源码确认（见下文补充）。

Godot 引擎行为确认（2026-06-26）：

- `GDScriptLanguage::init()` 先注册所有 ClassDB 类为 `GDScriptNativeClass`，再用 singleton 对象指针覆盖同名条目。
  因此全局映射中 `Input` 最终指向 singleton 对象指针，而非 `GDScriptNativeClass`。
- `GDScriptAnalyzer::reduce_identifier()` 对存在 `class_exists(name)` 的名称一律标记为 `NATIVE_CLASS`（`is_meta_type = true`），
  即分析器把 `Input` 当作类引用而非 singleton 值。
- singleton 方法通过 `get_function_signature()` 被假装标记为 `METHOD_FLAG_STATIC`，使 `Input.is_action_pressed()`
  在 meta type 上通过静态检查。
- `Input.new()` / `Engine.new()` 在 `reduce_call()` 中被明确拒绝：若 `Engine::get_singleton()->has_singleton(base_type.native_type)`
  为真，直接报 "Cannot construct native class because it is an engine singleton"。
- `Input.MOUSE_MODE_VISIBLE` 等常量/枚举值通过 `ClassDB::get_integer_constant()` 在分析时解析为整数常量。
- 编译器对 `NATIVE_CLASS` 标识符回退到全局数组中的 singleton 对象指针作为常量嵌入。

结论：Godot 的实际行为是"分析器始终按 meta type 处理 dual-role 名称，运行时回退到 singleton 对象"。
本仓库 Step 9 选择了不同模型：instance call 保持 `SINGLETON`（instance receiver），仅 static/constant/constructor
route 切换到 `TYPE_META`。该选择已在 Step 9 的 fail-closed 规则中固定，不追求与 Godot 分析器逐字对齐。

问题背景：

- Godot extension API 中存在 dual-role 名称：同一个 source-facing 名称既是 `singletons` 里的 top-level value，
  又是 `classes` 里的 engine class / type-meta。例如 `Engine`、`Input`。
- `Engine.get_frames_drawn()`、`Input.is_action_pressed(...)` 这类调用应继续按 singleton instance receiver 处理：
  receiver 先 materialize 为 `load_static "@GlobalScope" "Engine"` / `load_static "@GlobalScope" "Input"`，
  再走普通 `CallMethodInsn`。
- 同一个名称在其他使用形态下可能需要 class/type-meta route，例如 engine class enum/int constant、static method
  或 constructor-like `.new()`。这些 route 与 singleton receiver route 消费的是同一个 chain head 文本。
- 当前 `FrontendBinding` / chain-head binding 仍以单一 winner 发布 use-site fact。若同名 value winner 先命中，
  后续阶段通常只能看到 `SINGLETON` / ordinary value receiver，而不是同名 `TYPE_META`。

成因链路摘要：

```text
extension_api
  -> "Engine" / "Input" 同时存在于 classes 与 singletons
ClassRegistry.resolveValueHere("Engine")
  -> singleton 命中，发布 ScopeValueKind.SINGLETON
ClassRegistry.resolveTypeMetaHere("Engine")
  -> engine class type-meta 也可命中，但在另一命名空间
FrontendTopBindingAnalyzer.bindTopLevelTypeMetaCandidate(...)
  -> 先 resolveVisibleValue(...)
  -> 再 resolveSourceFacingTypeMeta(...)
  -> 只有 GLOBAL_ENUM value 有 shouldPreferGlobalEnumTypeMeta(...) 特例
  -> SINGLETON value 命中后 publishValueResolution(...) 并 return
FrontendChainHeadReceiverSupport
  -> FrontendBindingKind.SINGLETON 被当作 ordinary value receiver
  -> receiverKind = INSTANCE
FrontendChainReductionHelper
  -> TYPE_META 才能进入 static load / constructor primary route
  -> INSTANCE 进入 instance property / instance method route
```

影响分类：

- 正向且必须保留：`Engine.get_frames_drawn()` / `Input.is_action_pressed(...)` 这类 singleton instance method call
  被 value route 吃掉是期望行为。它们不应变成 `CallGlobalInsn`，也不应被强行塞入 `TYPE_META`。
- 高风险：engine class static constant / enum value access 可能不可达。例如 `Input.MOUSE_MODE_VISIBLE`、
  `Input.CURSOR_ARROW` 这类本应消费 `Input` type-meta 的 static load，如果 chain head 先发布为 `SINGLETON`，
  后续会进入 instance property route；而 `ScopePropertyResolver` 明确不处理 enum item、builtin constant
  或 engine integer constant 这类 type-meta static access。
- 高风险：constructor-like `.new()` route 可能不可达。`FrontendChainReductionHelper` 只有在
  `receiverKind == TYPE_META && step.name().equals("new")` 时进入 constructor route；若同名 singleton value
  先赢，`Engine.new()` 这类表达式不会进入 constructor primary route。
- 中风险：static method route 可能丢失原始 type-meta provenance，但当前 instance-call 解析中存在
  “instance-style syntax resolved to static method” 的回退路径；因此部分 static method 可能最终仍能 lowering
  为 `CallStaticMethodInsn`。这条可用性依赖 method resolver 是否能从 singleton declared type 找到 static method，
  不等同于 type-meta route 本身可达。
- 已有特例不覆盖本问题：global enum 在 top-level chain head 上有 `GLOBAL_ENUM` value 优先转 `TYPE_META`
  的特例；`SINGLETON` 没有对应特例，因此 global enum 的安全性不能外推到 `Engine` / `Input`。

当前覆盖缺口：

- 已有测试覆盖了 `Engine.get_frames_drawn()` / `Input.is_action_pressed(...)` 作为 singleton receiver lowering。
- 已有测试覆盖了 later-local / initializer self-reference drift，即已发布 `SINGLETON` binding 后必须稳定消费 top binding
  的 resolved value payload。
- Step 9 已覆盖 dual-role 名称下的 static constant / enum value、static method、constructor-like `.new()` 分流边界。
- Step 9 已补充测试明确 `Input.MOUSE_MODE_VISIBLE`、`Engine.new()` 这类表达式以 `TYPE_META` head 进入 static/constructor route，
  而 `Engine.get_frames_drawn()` 保持 `SINGLETON`。

### Step 9: 固化 TopBinding 内 dual-role chain-head route bias 方案

状态（2026-06-26）：已实现。`FrontendTopBindingAnalyzer` 现在在 walk `AttributeExpression` base
之前完成 dual-role 判断；当 base 是裸 `IdentifierExpression`、value namespace 命中 `SINGLETON`、
type-meta namespace 命中 `ENGINE_CLASS`，且 first suffix 只在 type-meta static namespace 可达时，
head 发布 `TYPE_META` 并跳过普通 identifier binding 路径。fail-closed 规则保证 suffix 同时命中
singleton instance 与 type-meta static 时保持 `SINGLETON`。该方案只解决 Step 8 中 dual-role
singleton/type-meta 名称的 chain-head 分流缺口，不把 `FrontendTopBindingAnalyzer` 扩展为完整 chain analyzer。

方案决策：

- 不新增独立 semantic pass。dual-role 名称的 head-level route bias 合并进 `FrontendTopBindingAnalyzer`，
  使 `symbolBindings()` 在首次发布时就给出后续 pass 应消费的 chain-head namespace。
- 新增或扩展 `AttributeExpression` 相关 AST node handler，在 walk chain head base 之前识别：
  base 是裸 `IdentifierExpression`、该名称按 value namespace 可解析为 `SINGLETON`，并且同名 source-facing
  type-meta 也可解析。
- 该 handler 只做 head-level namespace bias：决定当前 chain head use-site 发布 `SINGLETON` 还是 `TYPE_META`。
  它不发布 `resolvedMembers()`、`resolvedCalls()`、`expressionTypes()`，也不接管 suffix 诊断 owner。
- 小规模复制 chain analyzer 功能的边界限定为“第一个 suffix 是否明确要求 type-meta primary route”。
  不复制 overload 选择、参数类型匹配、deferred/dynamic/unsupported 状态传播、writable target 规则或完整 chain
  reduction。

发布规则：

```text
AttributeExpression(base = IdentifierExpression(name), firstStep = step)
  -> resolveVisibleValue(name)
  -> 若 value 不是 SINGLETON：保持现有 TopBinding 规则
  -> 若 value 是 SINGLETON：再尝试 resolveSourceFacingTypeMeta(name)
  -> 若 type-meta 不存在：保持 SINGLETON
  -> 若 firstStep 明确只应走 type-meta static/constructor route：发布 TYPE_META
  -> 否则保持 SINGLETON
```

`firstStep` 的判定必须 fail-closed：

- `Input.MOUSE_MODE_VISIBLE` / `Input.CURSOR_ARROW` 这类 static constant / enum value access：
  若只在 type-meta static namespace 命中，head 发布 `TYPE_META`。
- `Engine.new()` / `Input.new()` 这类 constructor-like route：head 发布 `TYPE_META`，构造合法性、不可构造诊断
  仍由后续 chain/type-check route 决定。但 `.new()` 同样必须遵守 fail-closed：若 singleton declared type 上存在
  名为 `new` 的实例方法或 property，suffix 在 singleton instance namespace 命中，head 保持 `SINGLETON`。
- `Engine.get_frames_drawn()` / `Input.is_action_pressed(...)` 这类 singleton instance call：保持 `SINGLETON`。
- 若 singleton instance route 与 type-meta static route 对同一 suffix 都可命中，不能静默改判为 `TYPE_META`。
  初始实现应保持 `SINGLETON` 或报告专门歧义诊断；具体诊断 owner 若未冻结，先 fail-closed 保持既有路径。
  "singleton instance route" 覆盖 instance method、instance property 和 signal 三类成员；signal 当前虽不在
  chain access 支持语法中，但 fail-closed 检查已预防性覆盖，避免未来 signal 进入支持范围时遗漏。
- 裸 `Engine` / `Input` 不属于 `AttributeExpression` chain head，不受本 Step 影响，仍发布 `SINGLETON` value binding。

实现边界：

- 不允许先让普通 identifier handler 发布 `SINGLETON`，再由 `AttributeExpression` handler 覆盖为 `TYPE_META`。
  handler 应在 walk base 前完成 dual-role 判断；若决定发布 `TYPE_META`，应跳过该 base 的普通 identifier binding 路径，
  只继续 walk step arguments。
- **value-winner 权威**：dual-role bias handler 必须先调用 `resolveVisibleValue(...)` 判定 value winner，与
  `bindTopLevelTypeMetaCandidate(...)` 的关键合同一致。只有当 value resolution 状态为 `FOUND_ALLOWED` 且
  `ScopeValueKind.SINGLETON` 时才继续 bias 判断。若 value winner 是 local / parameter / property / capture /
  signal / constant / global_enum，或状态为 `FOUND_BLOCKED` / `DEFERRED_UNSUPPORTED` / `NOT_FOUND`，bias 必须
  return false 并回退到普通 `bindTopLevelTypeMetaCandidate(...)` 流程，由该流程通过 `publishValueResolution(...)`
  固化 value winner 并报告遮蔽诊断。不得用 `classRegistry.isSingleton(name)` 绕过 visible-value 解析。
- 不改变 `GLOBAL_ENUM` 现有 prefer-type-meta 特例。该特例仍是 value/type-meta 竞争中的独立规则，不能外推为
  所有 singleton 都 type-meta 优先。
- 不改变 later-local / initializer self-reference drift 合同。非 `AttributeExpression` 的 singleton use-site 仍按
  ordinary value binding 稳定消费 top binding 的 `resolvedValue` payload。later-local 被
  `FrontendVisibleValueResolver` 过滤后 value winner 仍为 `SINGLETON`，bias 正常适用。
- 不新增 source-level `@GlobalScope` / `GlobalScope` type-meta receiver。`@GlobalScope` 仍只作为 LIR/backend owner string。
- 若该 handler 需要查询 static constant / enum / static method 的存在性，应使用现有 registry / type metadata 入口，
  并把查询结果限定为 head bias 输入；完整成员绑定仍由 `FrontendChainBindingAnalyzer` 负责。

后续 pass 预期：

- `FrontendChainHeadReceiverSupport` 继续只消费已发布的 `symbolBindings()`：
  - `SINGLETON` -> ordinary value receiver / `receiverKind = INSTANCE`
  - `TYPE_META` -> type-meta receiver / static load、static method、constructor primary route
- `FrontendCfgGraphBuilder.isTypeMetaHeadAttributeExpression(...)` 继续可通过 head binding 判断 type-meta CFG 形状；
  本 Step 的目标正是让 dual-role static route 在进入 CFG 前已经具备一致的 `TYPE_META` head fact。
- 本 Step 不引入独立 final route fact。若后续 dual-role 规则继续扩展到更复杂语法，再重新评估是否拆出一等
  use-site route fact。

测试与验收：

- 保留并继续要求：`Engine.get_frames_drawn()`、`Input.is_action_pressed(...)` 作为 singleton instance receiver lowering
  不回归。
- 新增 dual-role static load 覆盖：`Input.MOUSE_MODE_VISIBLE`、`Input.CURSOR_ARROW` 或等价 engine class constant/enum
  访问应以 `TYPE_META` head 进入 static load route，不应 materialize singleton receiver。
- 新增 constructor-like route 覆盖：`Engine.new()` 或可构造 dual-role engine class `.new()` 应以 `TYPE_META` head
  进入 constructor primary route；若 Godot metadata 标记不可构造，诊断应来自 constructor/type-check route，而不是
  被 singleton instance method route 吃掉。
- 新增混用覆盖：同一函数内同时出现裸 `Input`、`Input.is_action_pressed(...)`、`Input.MOUSE_MODE_VISIBLE` 时，
  三个 use-site 的 binding 互不污染。
- 新增 property initializer 覆盖：dual-role static constant / enum route 在 property initializer 中也应与 executable body
  保持一致。
- 若存在同名 suffix 同时命中 singleton instance 与 type-meta static 的 fixture，应覆盖 fail-closed 行为，避免依赖
  registry 遍历顺序静默选路。

补充（2026-06-27）：已按 `doc/test_suite.md` 的资源配对规则新增两组端到端用例，覆盖 Step 9 中
runtime 可达的 dual-role route bias 行为经过 frontend lowering、C backend build 与 Godot runtime
validation 后仍正确执行：

- `runtime/dual_role_singleton_static_constant.gd`：覆盖 dual-role TYPE_META static load route。
  包含 `IP.RESOLVER_MAX_QUERIES`（= 256）、`IP.RESOLVER_INVALID_ID`（= -1）、
  `ResourceUID.INVALID_ID`（= -1）、`DisplayServer.MAIN_WINDOW_ID`（= 0）四组 engine class constant
  访问，以及 property initializer `var startup_resolver_queries: int = IP.RESOLVER_MAX_QUERIES`。
  这些 dual-role 名称同时存在于 `singletons` 与 `classes` 中；constant 只在 type-meta static namespace
  可达，因此 chain head 必须发布 `TYPE_META`，不应 materialize singleton receiver。
  `Input.MOUSE_MODE_VISIBLE`（class enum value）的 chain reduction 与 backend enum-value 分支已于
  2026-06-27 补齐（见下方“已知边界”更新），可 runtime 到达；当前仍留在 focused 单元测试中分层覆盖
  （top binding / chain reduction / C backend）。
- `runtime/dual_role_singleton_mixed_use_sites.gd`：覆盖同一函数体内 dual-role 名称的
  SINGLETON 与 TYPE_META route 互不污染。`Input.is_action_pressed(...)` 保持 `SINGLETON` instance call
  （`CallMethodInsn`），`IP.RESOLVER_MAX_QUERIES` 切换为 `TYPE_META` static load，两者在同一 body 中
  同时 lower 并返回正确值。

验证（2026-06-27）：

- `script/run-gradle-targeted-tests.sh --tests GdScriptUnitTestCompileRunnerTest.compilesAndValidatesRuntimeScripts`
  全部通过（32 个 runtime 动态测试，0 failures），确认两组新 fixture 可完整经过 frontend lowering、
  C backend build 与 Godot runtime validation。

### Step 10: 补齐继承静态成员解析

状态（2026-06-27）：已完成 engine/builtin metadata static namespace 路线，并通过 targeted tests 与 IDE build 验证。该问题经文档、代码与 Godot 行为核对后属实：Godot/GDScript 将
class-level `const`、`enum`、`enum value` 视为类成员，子类应能通过继承链解析这些名字；脚本
autoload/singleton 按其脚本类继承关系处理，引擎原生 singleton 则按 native class metadata / ClassDB
处理。当前 gdcc 的 engine/builtin metadata static 路线已修复，脚本类 class-level 成员继承仍是后续边界：

- 脚本类作用域：`ClassScope.resolveInheritedValueMember(...)` 只继承 property / signal，未把父类
  class-level `const` 纳入 value lookup；父类 `enum` / `enum value` 的可见性合同也尚未在 scope/type-meta
  路线中冻结。
- engine/builtin type-meta static 路线：历史上 `hasEngineClassConstant(...)`、
  `findEngineClassEnumValue(...)`、`findBuiltinClassEnumValue(...)` 只查直接类；`resolvesInTypeMetaStaticNamespace(...)`、
  `reduceEngineStaticLoad(...)` / `reduceBuiltinStaticLoad(...)` 和 `LoadStaticInsnGen` 也跟随该直接类语义。本 Step
  已对 engine metadata 继承链补齐。builtin metadata 当前无 superclass edge，因此通过同形状 direct-only 入口保持一致边界。

实现同步（2026-06-27，已验证）：

- 已在 `ClassRegistry` 增加 `findEngineClassConstantInHierarchy(...)`、
  `findEngineClassEnumValueInHierarchy(...)`、`findBuiltinClassConstantInHierarchy(...)` 与
  `findBuiltinClassEnumValueInHierarchy(...)`。engine 查询按 direct-first 走 `inherits` 链并返回实际 owner；
  builtin metadata 当前没有 superclass edge，因此同形状入口保持 direct-only，避免虚构继承关系。
- 已将 `FrontendTopBindingAnalyzer.resolvesInTypeMetaStaticNamespace(...)` 接到 registry 继承查询，dual-role
  singleton/type-meta 的 first suffix 可因父类 static constant / enum value 命中而发布 `TYPE_META`，同时保留
  Step 9 的 singleton instance namespace fail-closed 规则。
- 已将 `FrontendChainReductionHelper.reduceEngineStaticLoad(...)` / `reduceBuiltinStaticLoad(...)` 与
  `LoadStaticInsnGen` 接到同一查询语义。`LoadStaticInsn` 继续保留 source receiver class，由 backend 沿继承链查找
  实际 owner 并 materialize literal。
- 暂未打开 GDCC 脚本类 static load / class-level `const` 继承边界：现有 `ClassDef` 尚不承载 class constants/enums
  inventory，`FrontendChainBindingAnalyzerTest.analyzeSealsUnsupportedGdccStaticLoadAtBoundary` 仍锚定该路线为
  unsupported。本轮只修复 engine/builtin metadata static namespace 的继承一致性。

实现分层：

1. 冻结语义合同：
   - 明确继承查找只发生在 class member / type-meta static namespace 内，不把父类 `const` / `enum` / `enum value`
     提升为全局名字。
   - 保留 local / parameter / block-local `const` 的可见性优先级；`FrontendVisibleValueResolver` 仍是
     declaration-order 和 deferred boundary 的真源。
   - 脚本 autoload/singleton 未来进入 first-class binding 时按脚本类处理；当前 engine singleton 不套用
     GDScript 脚本类继承规则。

2. 集中继承静态成员查询：
   - [已完成] 在 `ClassRegistry` 中补一个共享查询入口，沿 class metadata 的 superclass / `inherits` 链查找 static constant、
     class enum value，并返回 owner class 与成员 payload；避免 top binding、chain reduction、backend 各自手写遍历。
   - [已完成] 直接类优先，父类按近到远顺序查找；缺失 superclass metadata 与循环继承必须 fail closed，不得静默掉到全局或 dynamic route。
   - [已完成] engine 与 builtin 保持薄入口；builtin 因 metadata 无父类边只保留 direct-only 同形状入口。

3. 接入 top binding route bias：
   - [已完成] `resolvesInTypeMetaStaticNamespace(...)` 判断 dual-role singleton/type-meta first suffix 时，必须使用同一个继承静态成员查询入口。
   - [已完成] 若 first suffix 只在 type-meta static namespace 的父类 static constant / enum value 中命中，head 发布 `TYPE_META`。
   - [已完成] 若 singleton instance namespace 同名成员也命中，继续沿用 Step 9 fail-closed 行为，保持 `SINGLETON` 或后续专门诊断，不因继承静态成员而静默改路由。

4. 接入 chain reduction 与 backend：
   - [已完成] `FrontendChainReductionHelper.reduceEngineStaticLoad(...)` / `reduceBuiltinStaticLoad(...)` 使用共享查询结果 materialize
     inherited static constant / enum value，trace detail 中保留实际 owner class，便于诊断。
   - [已完成] `LoadStaticInsnGen` 使用同一查询语义生成 inherited static constant / enum value；constant 的类型检查和 literal materialization
     继续复用直接类路径，enum value 继续以 `GdIntType.INT` 与十进制 literal 输出。
   - [已完成] 不改变 `@GlobalScope` singleton materialization，不让 inherited static load 走 singleton receiver 路线。

5. 补脚本类 class-level 成员继承：
   - [后续边界] `ClassScope.resolveInheritedValueMember(...)` 增加父类 class-level `const` 查找，并保持 direct member shadow inherited member、
     inherited member shadow outer/global binding 的既有顺序。
   - [后续边界] 如果当前 AST / skeleton 已能表达 class enum 或 enum value，按 Godot 语义将父类 enum type/value 作为 class members
     纳入同一继承合同；若 enum declaration 尚未完整进入 scope model，本 Step 应在文档和测试中明确留下受阻边界，
     不把它伪装成已支持。

测试与验收：

- [后续边界] `ClassScopeResolutionTest` 增加脚本类继承测试：子类 method/property initializer 中裸名访问父类 `const` 命中父类 class member；
  同名 local / parameter / block-local `const` 仍按现有可见性规则遮蔽或 deferred。
- [后续边界] 若 class enum 已在 frontend scope model 中可表达，增加 `E.X`、裸 enum value、`A.E.X` 或等价可支持语法的继承测试；
  若暂不可表达，新增 pending 说明并把缺口写入相关 frontend enum/scope 文档。
- `FrontendTopBindingAnalyzerTest` 增加 dual-role 继承静态成员测试：例如 `Node2D.NOTIFICATION_*` 或等价 fixture
  在 first suffix 只命中父类 static constant / enum value 时发布 `TYPE_META`；同名 singleton instance member 场景继续 fail closed。
  已新增 `analyzePublishesTypeMetaForDualRoleInheritedEngineStaticMembers` 与
  `analyzeKeepsSingletonWhenInheritedInstanceMemberConflictsWithInheritedStaticMember`。
- `FrontendChainBindingAnalyzerTest` / chain reduction focused test 增加 inherited engine/builtin static constant 与 enum value route，
  要求 member resolution 为 `RESOLVED`，并能报告实际 owner class。已新增
  `analyzePublishesInheritedEngineStaticLoadFacts`、
  `reduceResolvesInheritedEngineClassConstantAndEnumValue`、
  `reduceDirectEngineClassStaticMemberWinsOverInheritedStaticMember`、
  `reduceFailsWhenInheritedEngineStaticMemberMissingAcrossHierarchy` 与
  `reduceRejectsInheritedEngineClassNonIntegerConstant`。
- `CLoadStaticInsnGenTest` 增加 inherited static constant / enum value backend literal 输出，覆盖 engine 与 builtin 可用的最小组合。
  已新增 inherited engine constant/enum 成功路径，以及 missing、incompatible target type、non-integer inherited constant 失败路径。
- 端到端 runtime fixture 至少覆盖一个 engine class inherited static member；如果 Godot metadata 中找不到稳定 builtin inherited
  constant/enum fixture，builtin 可停留在 focused 单元测试。
- 回归测试必须保留 Step 9 的 dual-role 混用场景：裸 `Input` / singleton instance call / type-meta static load 互不污染，
  `@GlobalScope` global constant 与 singleton receiver lowering 不回归。

验证（2026-06-27）：

- `script/run-gradle-targeted-tests.sh --tests FrontendTopBindingAnalyzerTest,FrontendChainReductionHelperTest,FrontendChainBindingAnalyzerTest,CLoadStaticInsnGenTest`
  通过（`BUILD SUCCESSFUL in 9s`），覆盖 top binding route bias、chain reduction 成功/失败、direct shadow inherited、
  inherited non-integer constant 拒绝，以及 backend inherited constant / enum literal 输出与负路径。
- IntelliJ `build_project` 通过（`isSuccess: true`），确认当前实现无编译错误。
- 本 Step 不声称 GDCC 脚本类 static load / class-level `const` 继承已完成；该能力需要先让 `ClassDef` / scope model
  承载 class constants/enums inventory，并继续保留现有 unsupported 测试作为边界锚点。

## 4. 总体验收细则

issue #36 可关闭的条件：

- `Engine.get_frames_drawn()` 或等价 singleton exact instance call 能完成 frontend body lowering。
- 生成的 LIR 中 singleton receiver 先通过 `load_static "@GlobalScope" "<singleton_name>"` materialize 到 object slot。
- 同一个调用点的 method call 是普通 `CallMethodInsn`，`objectId` 指向 singleton receiver slot。
- 该调用点没有用 `CallGlobalInsn` 表达 singleton method call，也没有回到旧 `LoadSingletonInsn` / `load_singleton`
  surface。
- `var frames: int = Engine.get_frames_drawn()` 或等价 singleton-backed property initializer 也能完成 lowering；
  对应 `_field_init_<property>` helper 进入真实 init func body，包含 `load_static "@GlobalScope" "<singleton_name>"`
  与后续 `CallMethodInsn`，并通过真实 `ReturnInsn` 返回 call result。
- singleton receiver lowering 的测试验收不得只覆盖 executable body；必须覆盖至少一个 `PROPERTY_INIT` helper 通过
  preparation、CFG publication 和 body materialization 的真实 pipeline。可以用 pass-manager end-to-end 测试合并验证整条链路，
  但不能只保留 executable body 的 `return Engine.get_frames_drawn()` / statement-position call 覆盖。
- LIR 文本 parser / serializer 继续通过现有 `LoadStaticInsn` 通用路径处理
  `$receiver = load_static "@GlobalScope" "<singleton_name>";`。
- statement-position `RESOLVED(void)` singleton call 不生成 standalone void temp slot。
- 带参数 singleton call 保持参数 materialization 顺序与 exact callable boundary。
- C backend 能为 `load_static "@GlobalScope" "<singleton_name>"` 生成 `godot_<lookupName>_singleton()` 调用，并按
  provided/module-local 合同登记 usage：fixed-provided singleton 只接受调用，non-provided singleton 才提交
  module-local binding。
- C backend 对 `ExtensionSingleton("GameSingleton", "Node")` 这类 lookup/type 分离 metadata 必须生成：
  `godot_GameSingleton_singleton()` lookup wrapper、`godot_global_get_singleton(... "GameSingleton" ...)` lookup、
  `godot_Node *` return/cast/cache type，以及包含 `Node` 的 lookup failure `context.type`。
- C backend 把 singleton getter 结果作为 `BORROWED` source materialize 到 receiver slot，不走
  `CBodyBuilder.callAssign(...)` / `OwnershipKind.OWNED` / `valueOfOwnedExpr(...)` 的 fresh-result 合同。
- 对 runtime-provided fixed singleton wrapper，C backend 接受 provided symbol，但不把它重复提交到 module-local header；
  `godot_Engine_singleton()` / `godot_ClassDB_singleton()` 这类 C name 的去重必须由 Step 1/Step 4 的
  usage-session tests 与 provided-symbol filtering 提前保证，不能只作为最终生成物验收项。
- `@GlobalScope` global constant 的既有 integer literal lowering 不回归。
- metadata 有 singleton lookup name 但 `type` 字段缺失/为空，或 `type` 字符串存在但不可严格解析时，`ClassRegistry`
  必须暴露 invalid singleton metadata fact，并由 frontend compile/lowering 入口在进入 lowering 前报告并停止；它不再表现为
  opaque identifier unsupported、unknown identifier 或 body-lowering 归属漂移。
- 现有 local/property/global constant/type-meta lowering tests 不回归。

验证（2026-06-26）：

- `script/run-gradle-targeted-tests.sh --tests FrontendTopBindingAnalyzerTest,FrontendChainBindingAnalyzerTest,FrontendLoweringBodyInsnPassTest,FrontendLoweringPassManagerTest,FrontendLoweringBuildCfgPassTest,FrontendLoweringFunctionPreparationPassTest,FrontendExprTypeAnalyzerTest,FrontendSemanticAnalyzerFrameworkTest,FrontendChainHeadReceiverSupportTest`
  全部通过。
- `script/run-gradle-targeted-tests.sh --tests FrontendVirtualOverrideAnalyzerTest,FrontendVarTypePostAnalyzerTest,FrontendLocalTypeStabilizationAnalyzerTest,FrontendCompileCheckAnalyzerTest,FrontendTypeCheckAnalyzerTest`
  全部通过，确认 `FrontendTopBindingAnalyzer.analyze(...)` 签名扩展未引入回归。
- `FrontendTopBindingAnalyzerTest` 新增 10 个 dual-role 测试覆盖：
  - 正向：singleton instance call 保持 `SINGLETON`（`Engine.get_frames_drawn()`、`Input.is_action_pressed(...)`）。
  - 正向：constructor-like `.new()` 发布 `TYPE_META`（`Engine.new()`）。
  - 负向（`.new()` fail-closed）：当 singleton declared type 存在名为 `new` 的实例方法时，`.new()` 保持 `SINGLETON`，
    不被无条件切为 `TYPE_META`。
  - 负向（signal fail-closed）：当 singleton declared type 存在与 type-meta static member 同名的 signal 时，
    head 保持 `SINGLETON`，不被切为 `TYPE_META`。signal 当前虽不在 chain access 支持语法中，但 fail-closed
    检查已预防性覆盖。
  - 正向：engine class constant 发布 `TYPE_META`（`IP.RESOLVER_MAX_QUERIES`）。
  - 正向：class enum value 发布 `TYPE_META`（`Input.MOUSE_MODE_VISIBLE`）。
  - 正向：static method 发布 `TYPE_META`（`ResourceUID.path_to_uid(...)`）。
  - 正向：混用场景下三个 use-site 互不污染（bare `Input`、`Input.is_action_pressed(...)`、`Input.MOUSE_MODE_VISIBLE`）。
  - 负向：fail-closed — suffix 同时命中 singleton instance 与 type-meta static 时保持 `SINGLETON`。
  - 正向：非 dual-role engine class static 不受影响（`Node.NOTIFICATION_ENTER_TREE`）。
  - 正向：property initializer 中 dual-role static constant 也发布 `TYPE_META`。
  - 负向（value-winner 遮蔽）：prior-declared local 遮蔽 dual-role singleton 时，head 保持 `LOCAL_VAR`，
    不被 bias 覆盖为 `SINGLETON` 或 `TYPE_META`。覆盖 instance call、static constant、constructor `.new()` 三类 suffix。
  - 负向（value-winner 遮蔽）：parameter 遮蔽 dual-role singleton 时，head 保持 `PARAMETER`。
  - 正向（later-local 不遮蔽）：later-declared local 不影响 bias — `resolveVisibleValue` 过滤 later local 后
    value winner 仍为 `SINGLETON`，instance call 保持 `SINGLETON`，static constant 切换为 `TYPE_META`。
- `FrontendChainBindingAnalyzerTest` 新增 4 个 downstream 测试确认：
  - singleton instance call 进入 `INSTANCE_METHOD` route。
  - engine class constant 进入 `TYPE_META` static load member resolution。
  - static method 进入 `STATIC_METHOD` route。
  - class enum value head binding 为 `TYPE_META`，member resolution 现经 chain reduction enum-value 分支解析为 `RESOLVED`。
- 已知边界（已于 2026-06-27 修复）：`reduceEngineStaticLoad` / `reduceBuiltinStaticLoad` 原先只处理 `constants()`，
  不处理 class enum values，导致 `Input.MOUSE_MODE_VISIBLE` 的 head binding 虽正确切换为 `TYPE_META`，但 member
  resolution 状态为 `FAILED`。现已通过 `ClassRegistry.findEngineClassEnumValue` / `findBuiltinClassEnumValue`
  在 chain reduction 与 `LoadStaticInsnGen` 两层补齐 enum-value 回退分支，enum value 以 `GdIntType.INT` 解析，
  backend 以 `Long.toString(value)` 输出十进制 literal，与 global enum value 路径对齐。
- 历史边界（已于 2026-06-27 由 Step 10 修复）：`findEngineClassEnumValue` /
  `findBuiltinClassEnumValue` 原先只查直接类，不遍历 `inherits` 链，导致子类访问父类定义的 enum value
  （如 `Node2D.NOTIFICATION_*` 继承自 `Node`）会 FAILED。Step 10 已通过
  `findEngineClassEnumValueInHierarchy` / `findBuiltinClassEnumValueInHierarchy` 统一补齐继承遍历，
  并同步 `resolvesInTypeMetaStaticNamespace` 的 dual-role bias 与 `LoadStaticInsnGen`，
  constant + enum + bias 三层现已一致。

## 5. 风险与边界

- `load_static` 的 `@GlobalScope` 分支从 constant-only 扩展为 property-aware 后，分支顺序必须谨慎：singleton object property 不能先被
  global-constant-only `int` 校验拒绝，既有 global constant 也不能被 singleton branch 抢走。
- singleton lookup name 与 declared type name 的关系需要用 metadata 验证。实现不得把 frontend binding name 直接当成 return type，
  也不得把 declared type name 当成 `godot_global_get_singleton(...)` 的 lookup string。
- module-local singleton wrapper 的 cache / C function identity 必须能区分两个 lookup name 不同但 declared type 相同的 singleton；
  不能只用 `returnTypeName` 派生 identity。
- fixed singleton wrapper 与 module-local singleton wrapper 共享 `godot_<lookupName>_singleton()` 命名空间；风险点不是
  C linker，而是 header 生成前的 provided/module-local 边界。任何绕过 `GodotBindingUsageSession` snapshot、直接把
  provided fixed symbol 塞进 `engine_method_binds.h.ftl` 的实现都应视为计划外。
- backend ownership 是硬约束：singleton getter 是 engine-owned / borrowed object producer，不是 constructor、method-return fresh producer 或
  property-init helper。实现必须显式避开 `callAssign(...)` 这条 owned-result 路径，并把任何 ptr conversion 保持为 ownership-neutral。
- module-local singleton wrapper 需要显式记录；`recordCall(...)` 只校验非 provided `godot_*` 调用是否已有 binding，不会从 C 文本或函数名反推出
  wrapper。
- 当前计划不引入 source-level `GlobalScope` / `@GlobalScope` type-meta receiver。裸 `Engine` 仍通过 `SINGLETON` value binding materialize；
  `@GlobalScope` 只是 LIR/backend owner string。
- 当前计划不实现 autoload。若未来 autoload 作为 first-class binding 出现，应单独设计 resolver 与 materialization surface，不要复用
  `SINGLETON` 名义偷偷扩大语义。
- 当前计划不改变 exact-call resolver。`FrontendResolvedCall.exactCallableBoundary()` 仍是参数边界真源。
