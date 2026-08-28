# Frontend Singleton Receiver Implementation

> Updated: 2026-06-27
>
> 本文档是 frontend engine singleton receiver materialization 的事实源。
> 不再记录阶段性步骤、完成进度或实施流水账；若合同变化，应直接改写当前状态。

## 1. 维护合同

- 本文档覆盖 shared metadata、frontend semantic、CFG / body lowering、backend codegen 与 runtime ABI 之间关于 engine singleton 的长期合同。
- 本文档同时冻结 dual-role（singleton / engine class 同名）chain head route bias 与 inherited static member 解析的当前语义边界。
- 本文档只描述已经冻结并由代码实现承担的事实，不描述历史修复步骤。
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_rules.md`
  - `frontend_top_binding_analyzer_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_lowering_func_pre_pass_implementation.md`
  - `doc/gdcc_low_ir.md`
  - `doc/module_impl/backend/load_static_implementation.md`
  - `doc/module_impl/backend/godot_binding_implementation.md`
  - 与 singleton metadata validation、binding publication、chain head bias、body lowering、`LoadStaticInsnGen`、
    `ModuleLocalGodotBinding.Singleton`、`engine_method_binds.h.ftl` 直接相关的代码注释

---

## 2. 当前支持面

frontend 当前正式支持的 engine singleton surface 包括：

### 2.1 Singleton instance call

- 裸 `Engine` / `Input` 等 engine singleton 作为 ordinary value receiver
- singleton method call 必须是 instance-style，receiver slot 由 `LoadStaticInsn("@GlobalScope", "<singleton_name>")` 物化
- singleton method call 继续使用既有 `CallMethodInsn`，不引入 singleton-specific call route，也不降级成 `CallGlobalInsn`
- statement-position `RESOLVED(void)` singleton call 不生成 standalone void temp slot
- 带参数 singleton call 保持参数 materialization 顺序与 exact callable boundary

### 2.2 Singleton-backed property initializer

- `var frames: int = Engine.get_frames_drawn()` 这类 singleton-backed property initializer
- 进入 `FunctionLoweringContext.Kind.PROPERTY_INIT` 上下文
- 通过隐藏的 `_field_init_<property>` helper 物化真实 init func body
- helper body 中 receiver 物化同样使用 `LoadStaticInsn("@GlobalScope", "<singleton_name>")` + `CallMethodInsn`
- 真实 `LirBasicBlock`、有效 `entryBlockId`、真实 `ReturnInsn` 由 body pass 物化，不是 shell-only 中间态
- static var initializer 同样可以使用 singleton / utility / global / type-meta 等已支持 route（如
  `static var frames: int = Engine.get_frames_drawn()`）：receiver 物化路径不变，但 init helper 结果由
  module 级两段式全局 static 初始化写回共享 backing 存储，而不是实例 constructor-time apply

### 2.3 Dual-role chain head route bias

- 同名 source-facing 名称同时存在于 `singletons` 与 `classes` / `builtin_classes` 时（如 `Engine` / `Input` / `IP` / `ResourceUID`），
  `FrontendTopBindingAnalyzer` 在 walk `AttributeExpression` base 之前完成 dual-role 判断
- 普通 singleton instance call 保持 `SINGLETON`（`receiverKind = INSTANCE`）
- engine class constant / class enum value / static method 切换为 `TYPE_META`（进入 static load / static method / constructor primary route）
- constructor-like `.new()` 优先 `TYPE_META`，但需通过 fail-closed 检查

### 2.4 Inherited static member resolution

- `Node2D.NOTIFICATION_*` 这类继承自父类（`Node`）的 static constant / enum value 能被解析
- engine metadata 通过 `inherits` 链查找 constant / enum value 并返回实际 owner class
- builtin metadata 当前无 superclass edge，因此保留 direct-only 同形状入口
- dual-role bias、chain reduction、`LoadStaticInsnGen` 三层统一消费同一份继承查询语义

### 2.5 Provided vs module-local binding boundary

- `Engine` / `ClassDB` 等 fixed runtime-provided singleton wrapper（`godot_Engine_singleton()` / `godot_ClassDB_singleton()`）由
  `FixedGodotBindings` 生成到 `godot_fixed_binding.h/.c`，并通过 `GodotBindingProvidedSymbols.forRegistry(...)` 进入
  runtime-provided C function name set
- provided fixed singleton wrapper 不重复出现在 `engine_method_binds.h` 的 module-local `static inline` 定义中
- 过滤发生在 `ModuleLocalGodotBindingUsageSession` 的 buffer -> commit -> snapshot 流程内，
  不能推迟到模板渲染阶段
- runtime 未提供的 singleton（`GameSingleton` 等）才进入 module-local binding，输出
  `godot_<lookupName>_singleton()` wrapper

### 2.6 当前覆盖的典型示例

- `Engine.get_frames_drawn()`、`Engine.set_time_scale(...)`、`Input.is_action_pressed("ui_accept")` 作为 singleton instance call
- `var frames: int = Engine.get_frames_drawn()` 作为 singleton-backed property initializer
- `IP.RESOLVER_MAX_QUERIES`、`IP.RESOLVER_INVALID_ID`、`ResourceUID.INVALID_ID`、`DisplayServer.MAIN_WINDOW_ID`
  作为 dual-role type-meta static load
- `Input.MOUSE_MODE_VISIBLE` 作为 dual-role class enum value static load
- `ResourceUID.path_to_uid(...)` 作为 dual-role static method call
- `Engine.new()` 作为 dual-role constructor-like route（受 fail-closed 约束）
- `Node2D.NOTIFICATION_ENTER_TREE` 作为 inherited static constant

以下内容不属于当前合同：

- source-level `@GlobalScope` / `GlobalScope` type-meta receiver
- autoload singleton 作为 first-class binding
- GDCC 脚本类 class-level `const` / `enum` 继承解析（仍属于后续边界）
- 把 singleton method call 表达成 `CallGlobalInsn` 或旧 `LoadSingletonInsn` / `load_singleton` surface
- 把 singleton getter 结果建模为 `OWNED` object producer

---

## 3. Singleton Metadata Contract

### 3.1 `ClassRegistry` 作为 singleton metadata 唯一 owner

`ClassRegistry` 是 singleton metadata 的唯一 owner：

- `ExtensionSingleton.name()` / `type()` 的关系
- strict declared-type 解析
- 有效 singleton type cache
- invalid singleton metadata fact

`ScopeTypeResolver` 只提供 strict declared-type 解析能力，不拥有 singleton metadata diagnostic。
`FrontendCompileCheckAnalyzer` 只消费已发布的 compile-surface facts，不充当 singleton metadata validator。
`DiagnosticManager` 不进入 `ClassRegistry` / `Scope` / `ScopeTypeResolver`，registry 只暴露事实，
frontend compile / lowering 入口负责把这些事实翻译成诊断。

### 3.2 预计算与 validation

`ClassRegistry` 构造完成所有 API map 后应预计算：

- `singletonTypeByName: Map<String, GdObjectType>` — 有效 singleton declared type
- `invalidSingletonMetadataByName: Map<String, InvalidSingletonMetadata>` — invalid metadata fact

`InvalidSingletonMetadata` 记录三类失败原因：

- `MISSING_TYPE` — `ExtensionSingleton.type()` 为 `null` 或 blank
- `UNRESOLVED_TYPE` — 通过 `ClassRegistry.tryResolveDeclaredType(...)` strict resolve 失败
- `NON_OBJECT_TYPE` — strict resolve 成功但结果不是 `GdObjectType`（singleton receiver 必须是 object receiver，
  builtin / enum / container type 不能作为 singleton object receiver）

### 3.3 公开查询合同

- `findSingletonType(lookupName)` 必须只返回已验证的 valid type cache；不得直接
  `new GdObjectType(ExtensionSingleton.type())`。
- `findInvalidSingletonMetadata(lookupName)` 暴露 registry-owned invalid fact。
- `resolveValueHere(...)` 继续只在 `findSingletonType(...) != null` 时发布 `ScopeValueKind.SINGLETON`。
  invalid metadata 不能被发布成普通 unknown identifier，也不能用 guessed object type 绕过 strict validation。
- `resolveTypeMetaHere(...)` / `findType(...)` 仍不能把 singleton lookup name 当作 type-meta route。
  singleton 是普通 value binding，`@GlobalScope` 只是 LIR / backend owner string。

### 3.4 用户可见失败点

frontend compile / lowering 入口若发现 registry 暴露 invalid singleton metadata fact，
必须在进入 lowering 前发清晰诊断并停止。这不是 `FrontendCompileCheckAnalyzer` 的 published-fact scan 职责。
compile gate 仍只扫描 supported executable body / supported property initializer island 上已经发布的
`expressionTypes()` / `resolvedMembers()` / `resolvedCalls()` / `slotTypes()` 状态。

body lowering 只保留协议不变量 fail-fast：若已经拿到 `FrontendBindingKind.SINGLETON` 却无法从 registry
取得已验证 declared type，说明上游发布合同被破坏，由 `FrontendBodyLoweringSession.requireSingletonType(...)`
立即 fail-fast。

---

## 4. Top Binding / SymbolBindings Contract

### 4.1 `FrontendBinding` resolved value payload

`FrontendBinding` 必须在 top binding 阶段就把 resolved value 写入 payload，而不是只记录 `symbolName` / `kind` /
`declarationSite`：

```text
record FrontendBinding(
    String symbolName,
    FrontendBindingKind kind,
    @Nullable Object declarationSite,
    @Nullable ScopeValue resolvedValue,
    @Nullable ScopeLookupStatus valueAccessStatus
)
```

不变量：

- `resolvedValue` 与 `valueAccessStatus` 必须同时为 `null` 或同时非 `null`
- `valueAccessStatus` 不能是 `NOT_FOUND`
- helper `withResolvedValue(ScopeValue)` 返回更新后的新 record

对 ordinary value binding（`PARAMETER` / `LOCAL_VAR` / `CAPTURE` / `PROPERTY` / `SIGNAL` / `CONSTANT` /
`SINGLETON` / `GLOBAL_ENUM`），`FrontendTopBindingAnalyzer` 在 `publishScopeValueBinding(...)` /
`publishValueResolution(...)` 中把 `ScopeValue resolvedValue` 与 allowed / blocked 状态写进 `FrontendBinding`。

`declarationSite()` 继续保留给诊断和测试断言使用，但 value type / receiver type 的真源必须是 top binding
发布的 resolved value。这样 later local、initializer self-reference local、outer fallback、singleton / global enum
等路径都复用同一份 resolved value。

### 4.2 后续阶段消费合同

`FrontendExpressionSemanticSupport.resolveValueIdentifierExpressionType(...)` 必须优先消费 binding 中的
`resolvedValue.type()` 与 access 状态。若 value-kind binding 缺少 resolved value，说明上游 publication contract
被破坏，应发布 `FAILED` / fail-fast detail，而不是回退到 `currentScope.resolveValue(...)` 重新竞争。

`FrontendChainHeadReceiverSupport.resolveValueReceiver(...)` 必须同样消费 binding 中的 exact resolved value
来构造 `ReceiverState.resolvedInstance(...)` / `blockedFrom(...)`。该 helper 只允许使用 `scopesByAst` 判断
skipped subtree 这类缺失上下文；不能再把 scope lookup 当成 resolved value 恢复机制。

CFG / body lowering 侧按 `symbolBindings()` materialize identifier：
`SINGLETON` 降成 `load_static "@GlobalScope" "Engine"` / `load_static "@GlobalScope" "Input"`。
如果 chain semantic 已经按 later local 类型解析 member / call，最终会出现 `resolvedCalls()` / `expressionTypes()`
与实际 receiver materialization 不一致；本消费合同从根上消除这种漂移。

### 4.3 Local type stabilization writeback

local `:=` slot 类型稳定化与 expression type fallback backfill 若改写 `BlockScope` 中的 local `ScopeValue`，
必须按 declaration identity 刷新已发布 binding payload（`FrontendExprTypeAnalyzer.refreshPublishedLocalValues(...)`）；
这不是重新解析 use-site，而是让同一个 resolved value slot 的类型与后续 slot writeback 保持同步。

需要这一步的原因是 `BlockScope.resetLocalType(...)` 会替换 immutable `ScopeValue`；expression type /
chain receiver 不再用 `currentScope.resolveValue(...)` 重新读取当前 slot，而是直接消费
`FrontendBinding.resolvedValue()`。如果 fallback backfill 不调用 `refreshPublishedLocalValues(...)`，
已发布 use-site binding 会继续指向 backfill 前的 `Variant` payload，导致 `BlockScope` 中的 local slot 已经变窄、
但后续语义仍按旧 payload 分析。

### 4.4 later-local / initializer self-reference 稳定性

`FrontendVisibleValueResolver` 仍是 executable body bare value 可见性合同的唯一 owner：
- 过滤 declaration-after-use local
- 过滤自引用 initializer local
- 在 filtered hit 之后继续向 outer / class / global scope 查找

shared `Scope.resolveValue(...)` 继续只表达 lexical inventory lookup，不表达 statement-order。
因此 `Engine.get_frames_drawn()` 前方若存在 later local `var Engine`，top binding 仍可正确发布
`FrontendBindingKind.SINGLETON`；后续 expression type / chain receiver 也必须继续消费该 SINGLETON payload，
不得被 later local / initializer self-reference local 漂移。

---

## 5. Chain Head & Reduction

### 5.1 Dual-role 名称识别

`FrontendTopBindingAnalyzer.tryApplyDualRoleTypeMetaBias(...)` 在 walk `AttributeExpression` base 之前识别 dual-role
名称。识别规则：

1. base 是裸 `IdentifierExpression` 且至少一个 step
2. 通过 `resolveVisibleValue(...)`（value-winner 权威）解析后是 `FOUND_ALLOWED` 且 `ScopeValueKind.SINGLETON`
3. 同名 source-facing type-meta 通过 `resolveSourceFacingTypeMeta(...)` 可解析为 `ENGINE_CLASS` 或 `GDCC_CLASS`
4. first suffix 在 type-meta static namespace 命中（inherited constant / enum value / static method）
5. first suffix 不在 singleton instance namespace 命中（instance method / instance property / signal）

满足上述条件时，head 发布 `TYPE_META` 并跳过普通 identifier binding 路径；否则保持 `SINGLETON`。

### 5.2 Value-winner 权威

dual-role bias handler 必须先调用 `resolveVisibleValue(...)` 判定 value winner，与
`bindTopLevelTypeMetaCandidate(...)` 的关键合同一致。

只有当 value resolution 状态为 `FOUND_ALLOWED` 且 `ScopeValueKind.SINGLETON` 时才继续 bias 判断。
若 value winner 是 local / parameter / property / capture / signal / constant / global_enum，
或状态为 `FOUND_BLOCKED` / `DEFERRED_UNSUPPORTED` / `NOT_FOUND`，bias 必须 return false 并回退到
普通 `bindTopLevelTypeMetaCandidate(...)` 流程，由该流程通过 `publishValueResolution(...)` 固化 value winner
并报告遮蔽诊断。不得用 `classRegistry.isSingleton(name)` 绕过 visible-value 解析。

### 5.3 Fail-closed 规则

`firstStep` 的判定必须 fail-closed：

- `Input.MOUSE_MODE_VISIBLE` / `Input.CURSOR_ARROW` 这类 static constant / enum value access：
  若只在 type-meta static namespace 命中，head 发布 `TYPE_META`。
- `Engine.new()` / `Input.new()` 这类 constructor-like route：head 发布 `TYPE_META`，构造合法性、
  不可构造诊断仍由后续 chain / type-check route 决定。但 `.new()` 同样必须遵守 fail-closed：
  若 singleton declared type 上存在名为 `new` 的实例方法或 property，suffix 在 singleton instance namespace
  命中，head 保持 `SINGLETON`。
- `Engine.get_frames_drawn()` / `Input.is_action_pressed(...)` 这类 singleton instance call：保持 `SINGLETON`。
- 若 singleton instance route 与 type-meta static route 对同一 suffix 都可命中，
  不能静默改判为 `TYPE_META`；当前保持 `SINGLETON` 或后续专门诊断。
  "singleton instance route" 覆盖 instance method、instance property 和 signal 三类成员；
  signal 当前虽不在 chain access 支持语法中，但 fail-closed 检查已预防性覆盖。
- 裸 `Engine` / `Input` 不属于 `AttributeExpression` chain head，不受 dual-role bias 影响，
  仍发布 `SINGLETON` value binding。

### 5.4 Namespace 查询语义

- `resolvesInTypeMetaStaticNamespace(typeMeta, stepName)`：通过
  `ClassRegistry.findEngineClassConstantInHierarchy(...)` /
  `findEngineClassEnumValueInHierarchy(...)` / `findBuiltinClassConstantInHierarchy(...)` /
  `findBuiltinClassEnumValueInHierarchy(...)` 与 hierarchy-aware static method 查找判断。
- `resolvesInSingletonInstanceNamespace(singletonType, stepName)`：沿 singleton declared type 继承链查找
  instance method（`hasInstanceMethodInHierarchy`）/ instance property（`hasInstancePropertyInHierarchy`）/
  signal（`hasSignalInHierarchy`）。

engine 查询 direct-first 走 `inherits` 链并返回实际 owner；builtin metadata 当前没有 superclass edge，
因此同形状入口保持 direct-only，避免虚构继承关系。

### 5.5 后续 pass 预期

- `FrontendChainHeadReceiverSupport` 继续只消费已发布的 `symbolBindings()`：
  - `SINGLETON` -> ordinary value receiver / `receiverKind = INSTANCE`
  - `TYPE_META` -> type-meta receiver / static load、static method、constructor primary route
- `FrontendCfgGraphBuilder.isTypeMetaHeadAttributeExpression(...)` 继续可通过 head binding 判断 type-meta CFG 形状
- 不引入独立 final route fact；若 dual-role 规则继续扩展到更复杂语法，再重新评估是否拆出一等 use-site route fact

---

## 6. Body Lowering

### 6.1 Singleton identifier materialization

`FrontendIdentifierOpaqueExprInsnLoweringProcessor.lower(...)` 在 `switch(binding.kind())` 中处理 `SINGLETON`：

- `SINGLETON` 分支只消费 `FrontendBinding` 与 `ClassRegistry` 已发布事实，不做 scope lookup
- 调用 `session.requireSingletonType(binding)` 取回 `GdObjectType`
- 发射 `new LoadStaticInsn(resultSlotId, "@GlobalScope", binding.symbolName())`
- target 使用 `session.resultSlotId(item)`，保持 `cfg_tmp_<valueId>` materialization 命名合同

`FrontendBodyLoweringSession.requireSingletonType(binding)` 是窄 helper，只消费已验证的 declared type，
并在缺失时作为协议不变量失真 fail-fast。

### 6.2 `PROPERTY_INIT` 上下文

attribute call base 的 singleton receiver slot 必须被后续 `CallMethodInsn.objectId()` 复用，
覆盖 `EXECUTABLE_BODY` 与 `PROPERTY_INIT` 两种上下文。property initializer 不是 executable body 的旁路；
它进入同一个 CFG / body lowering session，最终 helper body 中的 receiver materialization 也必须使用同一套
`LoadStaticInsn` -> `CallMethodInsn` 形态。

singleton-backed property-init helper 在 body pass 之后必须有真实 basic block、有效 `entryBlockId` 和真实
`ReturnInsn`；其 return value 来自 singleton method call result。

### 6.3 保持不变的部分

- `FrontendCfgGraphBuilder`：SINGLETON binding 继续产生 `OpaqueExprValueItem`，且因 singleton 是 immutable / read-only，
  不发布 writable-route payload（`null`）
- `FrontendCallInsnLoweringProcessor` 和 `materializeCallReceiverLeaf(...)`：不变
- implicit self fallback、direct-slot alias、writable-route payload 逻辑：不变
- `SELF` contract violation：仍保持 fail-fast

---

## 7. Backend `load_static` `@GlobalScope` 语义

### 7.1 `LoadStaticInsn` 表面

`$ <result_id> = load_static "<class_name>" "<static_name>"` 中 `<class_name>` 可为 `@GlobalScope`。
`@GlobalScope` owner 同时覆盖两类读取：

- top-level global constants（如 `OK`、`PI` 这类 `int` result）
- singleton properties（如 `Engine` / `Input` / `IP` 这类 object result）

不新增任何 LIR opcode、record、parser branch 或 serializer branch；LIR 文本 parser / serializer 继续通过现有
`LoadStaticInsn` 通用路径处理 `$receiver = load_static "@GlobalScope" "<singleton_name>";`。

### 7.2 分支顺序

`LoadStaticInsnGen` 必须先按 singleton metadata 判断是否为 `@GlobalScope` property，再回退 global constant。
这样 object singleton 不会被现有 `int`-only global constant 校验提前拒绝，既有 global constant 也不会被
singleton branch 抢走。

分支优先级：

1. 若 `classRegistry().findSingletonType(staticName) != null`：进入 singleton property branch
2. 否则进入既有 top-level global constant branch

### 7.3 Singleton property branch 实现细则

- 通过 `bodyBuilder.classRegistry().findSingletonType(staticName)` 获取已通过 registry validation 的 declared
  object type；该方法返回 `null` 表示不存在 valid singleton type，backend 不能再从 raw
  `ExtensionSingleton.type()` 重新包装或猜测 return type
- 校验 declared singleton type 可赋给 result variable type
- 发射调用前记录 `ModuleLocalGodotBinding.singleton(staticName, declaredType.getTypeName())`
- 发射调用时把 call expression 建模为 `BORROWED` object value，使用 `bodyBuilder.assignVar(...)` /
  `assignExpr(...)` 加 `valueOfExpr(..., PtrKind.GODOT_PTR)` 这类 borrowed `ValueRef`
- 不得调用 `bodyBuilder.callAssign(...)` / `valueOfOwnedExpr(...)` 或任何把 `godot_<lookupName>_singleton()`
  结果标成 `OwnershipKind.OWNED` 的路径
- 不得为 `callAssign(...)` 增加 boolean / flag 分支
- target 是 managed object slot 时，slot write 按普通 borrowed-source 规则处理：必要时为新 slot acquire / retain
  自己的引用，并由后续 cleanup 释放这一次 acquire

### 7.4 Global constant branch

- 沿用既有 `int` global constant 校验与 materialization 路径
- 不回归 `@GlobalScope` global constant、global enum、builtin constant、engine class integer constant

---

## 8. Module-Local Singleton Binding Contract

### 8.1 数据模型

`ModuleLocalGodotBinding.Singleton` 显式区分三类事实：

- `lookupName`：来自 `ExtensionSingleton.name()` / `LoadStaticInsn.staticName()`，唯一用途是 Godot singleton
  registry lookup 与 lookup diagnostic。`engine_method_binds.h.ftl` 必须把它用于
  `godot_global_get_singleton(GD_STATIC_SN(u8"<lookupName>"))`，不能通过 `symbol.owner()` 或 declared type 反推。
- `returnTypeName`：来自 `ExtensionSingleton.type()` / `ClassRegistry.findSingletonType(lookupName)`，
  用于生成 C return type、cast type、result slot assignability check 和 diagnostic `context.type`。
  例如 lookup name 为 `"GameSingleton"`、declared type 为 `"Node"` 时，返回类型必须是 `godot_Node *`。
- C symbol identity：由 `GodotBindingSymbol` 承载，固定为
  `family = SINGLETON`、`owner = "@GlobalScope"`、`name = lookupName`、
  `cFunctionName = "godot_<lookupName>_singleton"`。这样两个不同 singleton 即使 declared type 相同也不会
  共享 wrapper / cache。
- ABI signature：由 `GodotBindingSymbol.signatureKey()` 承载，固定为
  `returnType = "godot_<returnTypeName> *"`、空参数列表、`vararg = false`。
  module-local usage session 的 canonical key / C-name conflict check 继续用它阻断不兼容合并。

### 8.2 `singleton(lookupName, returnTypeName)` 窄入口

backend 必须提供并使用 `ModuleLocalGodotBinding.singleton(lookupName, returnTypeName)` 这一窄入口：

- 该入口构造的 `Singleton` 数据形态固定为 `record Singleton(GodotBindingSymbol symbol, String lookupName, String returnTypeName)`
- `returnTypeName` 不能只隐含在 `symbol.returnType()` 字符串里
- 所有从 metadata / `LoadStaticInsnGen` 来的 production singleton binding 都必须调用 two-name 入口
- 保留的 single-name test shorthand 只能 delegate 到 `singleton(name, name)`
- `Singleton.cacheName()` 必须从 `cFunctionName` 或 `lookupName` 派生，不能只从 `returnTypeName` 派生，
  避免多个 singleton 声明同一 return type 时共用缓存

### 8.3 Merge compatibility

`ModuleLocalGodotBinding.Singleton` 的 merge compatibility 必须同时比较：

- `family`、`owner="@GlobalScope"`、`name=lookupName`、`cFunctionName`
- `signatureKey()`
- 显式 `lookupName`
- 显式 `returnTypeName`

同一 C function name 对应不同 lookup / return ABI 时必须 fail-fast。

### 8.4 Provided vs module-local 边界

- fixed wrapper 继续由 `FixedGodotBindings` / `Godot451FixedBindings` 生成到 `godot_fixed_binding.h/.c`，
  并通过 `GodotBindingProvidedSymbols.forRegistry(...)` 进入 runtime-provided C function name set
- 不要把 `godot_Engine_singleton()` / `godot_ClassDB_singleton()` 这类 fixed singleton wrapper 复制进
  `ModuleLocalGodotBinding` 的 module header 输出
- module-local wrapper 只补 runtime 未提供的 singleton / class constant surface，并且只通过
  `GodotBindingUsageSession` 的 buffer -> commit -> snapshot 流程进入 `engine_method_binds.h.ftl`
- `engine_method_binds.h.ftl` 不能自行发现或补写 `godot_*` wrapper，也不能接收 provided C symbol

`entry.h` 的 include 顺序下，fixed declarations 会先经 `godot_binding.h` 暴露，module-local header 后包含；
因此 provided filtering 是防止同名 fixed declaration 与 module-local `static inline` definition 冲突的必要条件。

### 8.5 Usage session 行为

- `recordCall(...)` / `recordUsedGodotBindingCall(...)` 只能验证 "provided 或已显式登记"，不能从函数名反推并创建
  module-local binding
- provided fixed singleton C names（`godot_Engine_singleton` / `godot_ClassDB_singleton` 等）被
  `recordGodotCall(...)` 接受，但不会进入 `moduleLocalBindings()` / `moduleLocalCFunctionNames()`
- non-provided singleton（`GameSingleton -> Node`）通过 two-name binding 被提交，C name 是
  `godot_GameSingleton_singleton`，return ABI 是 `godot_Node *`
- 相同 C name 对应不同 `lookupName` / `returnTypeName` / `signatureKey()` 时，在 buffer 或 committed session
  层 fail-fast

### 8.6 模板渲染合同

`engine_method_binds.h.ftl` 的 singleton branch 必须使用：

- `${binding.returnType()}` / `${binding.returnTypeName()}` 渲染 signature、cache、cast 与 `context.type`
- `${binding.escapedLookupName()}` 渲染 `godot_global_get_singleton(GD_STATIC_SN(...))` 与 `context.lookup_name`
- `"@GlobalScope"` 或等价常量渲染 `context.owner`
- `${binding.cFunctionName()}` 渲染 wrapper function identity

不再使用 `${binding.escapedOwner()}` 作为 singleton lookup string。

---

## 9. Ownership 合同

`godot_<lookupName>_singleton()` module-local wrapper 只做 lookup / cache / fail-fast，不是
`classdb_construct_object*`，也不向调用方转移对象所有权。`godot_global_get_singleton` 返回的是 engine registry
中已存在的 singleton object pointer；后续 cleanup 不得释放从未取得过的引用。

因此：

- singleton getter 在 LIR 中必须以 `BORROWED` object value 进入 receiver slot
- 不得使用 `CBodyBuilder.callAssign(...)` 这类把返回值建模为 fresh `OWNED` producer 的路径
- ptr conversion 也必须保持 ownership-neutral
- 若 target 是 managed object slot，slot write 按普通 borrowed-source 规则处理：必要时为新 slot acquire / retain
  自己的引用，并由后续 cleanup 释放这一次 acquire

---

## 10. 风险与边界

### 10.1 当前已声明的非目标

- 不引入 source-level `@GlobalScope` / `GlobalScope` type-meta receiver；`@GlobalScope` 仍只作为
  LIR / backend owner string
- 不实现 autoload singleton 作为 first-class binding；若未来 autoload 出现，应单独设计 resolver 与
  materialization surface，不要复用 `SINGLETON` 名义偷偷扩大语义
- 不改变 exact-call resolver；`FrontendResolvedCall.exactCallableBoundary()` 仍是参数边界真源
- 不在 lowering 阶段重跑 chain reduction、call route 选择或表达式求值顺序推导
- 不新增 intrinsic；本计划不使用 `doc/gdcc_lir_intrinsic.md` 任何能力

### 10.2 GDCC 脚本类 class-level 成员继承（后续边界）

`ClassScope.resolveInheritedValueMember(...)` 当前只继承 property / signal，未把父类 class-level `const` 纳入
value lookup；父类 `enum` / `enum value` 的可见性合同也尚未在 scope / type-meta 路线中冻结。

若当前 AST / skeleton 已能表达 class enum 或 enum value，按 Godot 语义将父类 enum type / value 作为 class members
纳入同一继承合同；若 enum declaration 尚未完整进入 scope model，应在文档和测试中明确留下受阻边界，不把它
伪装成已支持。

### 10.3 共享命名空间风险

fixed singleton wrapper 与 module-local singleton wrapper 共享 `godot_<lookupName>_singleton()` 命名空间；
风险点不在 C linker，而在 header 生成前的 provided / module-local 边界。任何绕过 `GodotBindingUsageSession` snapshot、
直接把 provided fixed symbol 塞进 `engine_method_binds.h.ftl` 的实现都应视为计划外。

### 10.4 后续 surface 维护提示

- `load_static` 的 `@GlobalScope` 分支从 constant-only 扩展为 property-aware 后，分支顺序必须谨慎：
  singleton object property 不能先被 global-constant-only `int` 校验拒绝，既有 global constant 也不能被
  singleton branch 抢走
- singleton lookup name 与 declared type name 的关系由 metadata validation 决定；实现不得把 frontend binding
  name 直接当成 return type，也不得把 declared type name 当成 `godot_global_get_singleton(...)` 的 lookup string
- module-local singleton wrapper 的 cache / C function identity 必须能区分两个 lookup name 不同但 declared type
  相同的 singleton；不能只用 `returnTypeName` 派生 identity

---

## 11. Test Coverage

### 11.1 单元测试入口

- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`
  - `runLowersSingletonValueReceiverAsInstanceReceiverInExecutableBody`
  - `runLowersSingletonReceiverPropertyInitializerIntoExecutableInitFunction`
  - `runLowersSingletonReceiverBeforeLaterLocalShadowAsGlobalScopeLoad`
  - `runFailsFastWhenPublishedSingletonBindingLosesRegistryMetadata`
- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringFunctionPreparationPassTest.java`
  - `runPublishesSingletonBackedPropertyInitContextAndKeepsShellOnly`
- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBuildCfgPassTest.java`
  - `runPublishesSingletonBackedPropertyInitCfgGraph`
- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringPassManagerTest.java`
  - `lowerToContextHandlesSingletonBackedPropertyInitializerEndToEnd`
- `src/test/java/gd/script/gdcc/scope/ClassRegistryTest.java`
  - `singletonMetadataShouldResolveLookupNameToDeclaredObjectType`
  - `invalidSingletonMetadataShouldNotPublishSingletonValue`
  - `findTypeDoesNotReturnForSingletonEnumOrFunction`
- `src/test/java/gd/script/gdcc/scope/ClassRegistryScopeTest.java`
  - `resolveValueAndFunctionsExposeGlobalBindings`
  - `restrictionAwareLookupKeepsGlobalBindingsAllowed`
  - `sameNameCanResolveIndependentlyInValueAndTypeNamespaces`
- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendTopBindingAnalyzerTest.java`
  - dual-role 全部覆盖：singleton instance call、`.new()` fail-closed、signal fail-closed、
    engine class constant、class enum value、static method、混用场景、fail-closed 双 namespace、
    non-dual-role engine class static、property initializer、prior-declared local 遮蔽、
    parameter 遮蔽、later-local 不遮蔽、inherited static member
- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendChainBindingAnalyzerTest.java`
  - dual-role downstream route：INSTANCE_METHOD / TYPE_META static load / STATIC_METHOD / class enum value
  - inherited engine static load
- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendChainReductionHelperTest.java`
  - `reduceResolvesInheritedEngineClassConstantAndEnumValue`
  - `reduceDirectEngineClassStaticMemberWinsOverInheritedStaticMember`
  - `reduceFailsWhenInheritedEngineStaticMemberMissingAcrossHierarchy`
  - `reduceRejectsInheritedEngineClassNonIntegerConstant`
- `src/test/java/gd/script/gdcc/backend/c/gen/CLoadStaticInsnGenTest.java`
  - singleton property、inherited constant / enum value、missing、incompatible target type、
    non-integer inherited constant 负路径
- `src/test/java/gd/script/gdcc/backend/c/gen/binding/usage/GodotBindingUsageSessionTest.java`
  - `providedFixedSingletonsShouldBeAcceptedButNotCommitted`
  - `nonProvidedSingletonShouldCommitLookupAndReturnTypeSeparately`
  - `sameSingletonCNameWithDifferentReturnTypeShouldFailFast`
- `src/test/java/gd/script/gdcc/backend/c/gen/binding/ModuleLocalGodotBindingTemplateTest.java`
  - `singletonWrapperShouldRenderOnlySingletonLookupHelperWithoutDesignatedInitializer`
  - `singletonWrapperShouldKeepLookupNameSeparateFromReturnTypeName`
- `src/test/java/gd/script/gdcc/backend/c/gen/binding/FixedGodotBindingsTest.java`
  - `fixedSymbolsShouldBeValidatedVersionedSourceList`
  - `renderFixedSupportShouldUseFixedRuntimeWrappersWithoutEngineConstructors`
- `src/test/java/gd/script/gdcc/backend/c/gen/CCodegenEngineMethodUsageSessionTest.java`
  - `generateShouldFilterFixedSingletonWrappersAndRenderOnlyNonProvidedSingletonWrappers`

### 11.2 端到端 test-suite 资源

按 `doc/test_suite.md` 的资源配对规则新增的端到端 fixture：

- `src/test/test_suite/unit_test/validation/runtime/singleton_receiver_calls.gd`
  - 覆盖 singleton-backed property initializer、`Engine.get_frames_drawn()` 返回值调用、
    `Engine.set_time_scale(...)` statement-position void 调用、`Input.is_action_pressed(...)` 带参调用
- `src/test/test_suite/unit_test/validation/runtime/singleton_receiver_binding_drift.gd`
  - 覆盖 later-local 与 initializer self-reference drift 场景经过 frontend lowering、
    C backend、Godot runtime 后仍按 singleton receiver 执行
- `src/test/test_suite/unit_test/validation/runtime/dual_role_singleton_static_constant.gd`
  - 覆盖 dual-role TYPE_META static load route：`IP.RESOLVER_MAX_QUERIES`、`IP.RESOLVER_INVALID_ID`、
    `ResourceUID.INVALID_ID`、`DisplayServer.MAIN_WINDOW_ID` 以及 property initializer
- `src/test/test_suite/unit_test/validation/runtime/dual_role_singleton_mixed_use_sites.gd`
  - 覆盖同一函数体内 `SINGLETON` 与 `TYPE_META` route 互不污染
