# Frontend Signal Support

> Updated: 2026-08-15
>
> 本文档是 GdScript `signal` 一等值、`Signal.emit/connect/disconnect` 与方法 / utility 值引用 → `Callable`（不含 `await signal` 协程）在 frontend、LIR、C backend 与 runtime helper 全链路的事实源。
> 不再记录阶段性步骤、完成进度或实施流水账；若合同变化，应直接改写当前状态。

## 1. 维护合同

- 本文档覆盖 shared scope / semantic、compile gate、CFG / body lowering、LIR、C backend 与 runtime helper 之间关于 signal 与 Callable 值引用的长期合同。
- 本文档只描述已经冻结并由代码实现承担的事实，以及仍明确拒绝的边界。
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `diagnostic_manager.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_lowering_(un)pack_implementation.md`
  - `scope_architecture_refactor_plan.md` §4.5
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/module_impl/backend/godot_binding_implementation.md`
  - 与 signal / Callable 物化、ClassDB 注册、compile gate、liveness 直接相关的代码注释

Godot 对齐基线：runtime / GDExtension ABI / generated bindings 固定为 **4.5.1**（`GodotVersion.V451`、`src/main/resources/extension_api_451.json`）。

适用范围：

- `src/main/java/gd/script/gdcc/frontend/sema/**`
- `src/main/java/gd/script/gdcc/frontend/lowering/**`
- `src/main/java/gd/script/gdcc/scope/**`
- `src/main/java/gd/script/gdcc/type/GdSignalType.java`
- `src/main/java/gd/script/gdcc/lir/**`
- `src/main/java/gd/script/gdcc/backend/c/gen/**`
- `src/main/c/codegen/template_451/entry.c.ftl`
- `src/main/c/codegen/include_451/gdcc/gdcc_callable.h`
- 对应的 frontend、LIR、backend 与 `test_suite` 测试

---

## 2. 当前支持面

| 源码 | 结果 | LIR |
| --- | --- | --- |
| `signal foo(...)` | 当前类新声明，注册到 ClassDB | `LirSignalDef` |
| bare / receiver 读取：`foo`、`self.foo`、`obj.foo` | `godot_Signal` 一等值 | `construct_signal` |
| `sig.emit(...)` | 任意数量 / 类型 Variant 实参 | `CallMethodInsn` + vararg pack |
| `sig.connect(cb[, flags])` / `sig.disconnect(cb)` | `connect` 返回 `int`，`disconnect` 返回 void | 既有 builtin `CallMethodInsn` |
| Object / self 实例方法引用：`_handler`、`obj._handler` | `Callable(ObjectID, name)` | `construct_callable` |
| 非 Dictionary builtin 实例方法：`vec.abs`、`array.clear` | `VariantCallable`（值拷贝 receiver） | 同一 `construct_callable` |
| GDCC / engine 静态：`Worker.build`、`JSON.parse_string`、子类读取继承静态 | custom trampoline；owner 为声明类 | `construct_standalone_callable` |
| utility：`print`、`lerp` | custom trampoline | `construct_standalone_callable` |

仍拒绝：

- `await signal` 及任何协程挂起 / 恢复状态机
- lambda / capture；`ConstructCallableInsn` / `construct_standalone_callable` 都不承接 lambda
- builtin type-meta 方法当值（`Vector2.abs`、`Vector2.from_angle`）
- 构造器当值（`Node.new`、`Inner.new`）
- `dict.clear` 当方法引用（Godot 把它当 Dictionary key）
- `CALL_STATIC_METHOD` 完整 backend（`CallStaticMethodInsn` → CInsnGen）；静态方法 **调用** 与静态方法 **引用** 分开
- Callable `bind` / `unbind` / RPC callable 的新 lowering；已物化的 `Callable` 走既有 builtin `CallMethodInsn`
- 按 signal 声明签名对 `emit` 做静态 arity / type 拒绝
- 自定义 `signal` 作为 type annotation 扩展
- bare `CONNECT_*`；flags 只用已支持的限定形式 `Object.CONNECT_*`

inherited GDCC 同名 signal 允许 nearest-child shadow；GDCC 不得覆盖 inherited engine / native signal。

---

## 3. Godot 4.5.1 语义基线

- `Signal` 是 builtin variant，承载 `(ObjectID, StringName)`。读取 `obj.foo` 产生**新的** Signal 值，不是已存储字段。
- `Signal` / `Callable` 只保存 **ObjectID（非 owning）**，不保活 receiver。`godot_Signal_destroy` / `godot_Callable_destroy` 只销毁 value storage。构造后 receiver 被释放，value 仍在但失效；`.emit` / `.connect` 的失效行为由 Godot ObjectDB 决定。
- `Signal.emit` 是真正 vararg（`extension_api_451.json` 中 `is_vararg=true` 且无固定参数）。声明参数只作 ClassDB / 编辑器元数据。frontend 不得在调用点按声明签名拒绝多余或异型实参。
- `connect` 返回 `int`（错误码），`disconnect` 返回 void。`flags` 支持 `Object.CONNECT_DEFERRED` / `Object.CONNECT_ONE_SHOT`，可省略（默认 0）。
- 只注册当前类**新声明** signal；继承 signal 不重复注册。engine / native signal 只读、不由 GDCC 注册。
- engine 静态方法在 Godot 里 `is_valid()` 常为 false。GDCC 自定义 trampoline **报告 valid**（编译期已知目标）；这是有意偏离，不得拿官方 `JSON.parse_string.is_valid()` 当回归金标准。
- `Array` / `Dictionary` 作为 `VariantCallable` receiver 遵循 Godot Variant 共享语义，不是深拷贝。`array.clear` 作为 Callable 调用会清空原数组。

### 3.1 Callable 源码形态与出口

| 源码形态 | Godot | 运行时表示 | GDCC 出口 |
| --- | --- | --- | --- |
| Object / self 实例方法 | 允许 | `Callable(Object*, StringName)` | `godot_new_Callable_with_Object_StringName` |
| builtin 实例方法 | 允许 | `VariantCallable`，receiver 按值拷进 userdata | `godot_Callable_create(NULL, &tmp, name)` |
| `Dictionary.clear` 等 keyed 成员 | 否（当 key） | 必须显式 `Callable.create(dict, &"clear")` | 继续拒绝 `dict.clear` 当方法引用 |
| builtin type-meta 方法 | 否 | analyzer 在 builtin meta 上只认 constant / enum | 继续拒绝 |
| GDCC / script 静态方法 | 允许 | `Callable(script_obj, name)` | `godot_callable_custom_create2` trampoline |
| engine / native 静态方法 | 允许 | `Callable(GDScriptNativeClass*, name)`，官方 `is_valid()==false` 仍可 `call` | 同上 trampoline，**报告 valid** |
| utility | 允许 | `GDScriptUtilityCallable` | 同上 trampoline |
| 构造器当值 | 允许但历史脆弱 | `Callable(class_obj, "new")` | 继续拒绝 |

对照锚点（只读，勿抄 Godot 模块私有类）：

- `core/variant/callable.cpp` `Callable::create`：`OBJECT → Callable(ObjectID, method)`，其它 → `VariantCallable`
- `core/variant/variant_setget.cpp` `Variant::get_named`：非 Dictionary 的 builtin 方法 → `VariantCallable(*this, member)`
- `modules/gdscript/gdscript.cpp` `GDScriptNativeClass::_get` / `GDScript::_get`：静态方法 → 标准 Callable
- `modules/gdscript/gdscript_analyzer.cpp`：utility 标识符常量折叠为 `GDScriptUtilityCallable`；builtin **meta** 只处理 constant / enum
- 官方测试：`builtin_method_as_callable.gd`、`static_method_as_callable.gd`、`native_static_method_as_callable.gd`

直接推论：

1. 禁止把 static / utility 或伪造 Object 塞进 `construct_callable`。该 opcode operand schema 冻结为 `(VARIABLE, STRING)` min/max=2。
2. builtin 实例与 Object 实例共用 `construct_callable`；backend 按 `$receiver` 静态类型换 C 出口。`Variant` receiver 拒绝。
3. utility 与 GDCC / engine 静态没有 instance receiver，走 `construct_standalone_callable` + `godot_callable_custom_create2`。
4. 不新增 `construct_callable_from_variant` / `construct_utility_callable` / `construct_static_callable`，也不复用 `CONSTRUCT_LAMBDA`。

---

## 4. 数据流

```text
signal 声明
  -> FrontendClassSkeletonBuilder / toLirSignal
  -> LirClassDef.signals
  -> entry.c.ftl ClassDB 注册

signal / method-ref 值读取
  -> scope / chain reduction 发布 RESOLVED(GdSignalType | GdCallableType)
  -> compile gate（只拦剩余拒绝面）
  -> CFG: opaque (bare) / SignalLoadItem / CallableLoadItem / StandaloneCallableLoadItem
  -> body lowering: ConstructSignalInsn / ConstructCallableInsn / ConstructStandaloneCallableInsn
  -> ConstructInsnGen
  -> godot_new_Signal_with_Object_StringName
     / godot_new_Callable_with_Object_StringName
     / godot_Callable_create
     / gdcc_new_standalone_callable

.emit / .connect / .disconnect
  -> 先物化 Signal 值，再 lowerExactInstanceCall -> CallMethodInsn
  -> CallMethodInsnGen BUILTIN（emit 走 vararg argv/argc）
```

lowering 只消费 published facts。缺失 / 冲突 fact 必须 fail-fast，禁止重新 binder。

---

## 5. Semantic / scope 合同

- 无 receiver 的 `foo` 走 `ClassScope` value lookup；static context 返回 `FOUND_BLOCKED`。
- `ClassScope.toSignalScopeValue` 把 signal 发布为 `ScopeValue(kind=SIGNAL, type=GdSignalType, constant=true, writable=false, staticMember=false)`。`ScopeValue` 禁止 signal 可写。
- 显式 receiver 的 `obj.foo` 走 `ScopeSignalResolver.resolveInstanceSignal(...)`：
  - `GdObjectType` → 继承链查找（cycle-guarded，canonical super names）
  - 非 Object（含 Variant / builtin）→ `Failed(UNSUPPORTED_RECEIVER_KIND)`
  - 根 metadata 缺失 → `MetadataUnknown`
  - 层级 metadata 损坏 / 确认缺失 → `Failed`
- metadata 缺失时不得猜测 dynamic signal。
- `FrontendChainReductionHelper.resolvedSignalTrace(...)` 发布 `FrontendResolvedMember(bindingKind=SIGNAL, resultType=GdSignalType)`。engine signal 的 `parameterTypes` 来自 `ExtensionGdClass.getSignals()`，再经 `GdSignalType.from(SignalDef)`。
- `resolvedMethodReferenceTrace(...)` 把方法引用发布为 `GdCallableType`。
- `FrontendAssignmentSemanticSupport` 对 signal 赋值报 read-only。
- `.emit` / `.connect` / `.disconnect` 经 `reduceInstanceMethodStep` → builtin `Signal` 方法解析；`AttributeCallStep` 无需专用承载。
- property initializer 中的非静态 signal 访问走 `detailForResolvedSignalBoundary` 拦截。

`GdSignalType` 只承载参数签名；声明本体留在 `ScopeValue.declaration()` 的 `SignalDef`。`ClassRegistry` 把类型文本 `"Signal"` 解析成无参 `GdSignalType()`。`EngineMethodAbiCodec` 映射 `GdSignalType` ↔ ABI `'G'`。

---

## 6. Compile gate

`FrontendCompileCheckAnalyzer` 只挂在 `analyzeForCompile(...)`。当前 **RESOLVED feature-specific blocker** 仅剩：

- Dictionary 实例 method-reference：`METHOD && BUILTIN && receiverType instanceof GdDictionaryType`
- builtin type-meta static method-reference：`STATIC_METHOD && ownerKind == BUILTIN`

已放行：signal 值读取、`.emit`、`.connect` / `.disconnect`、Object / self `METHOD` 值读取、非 Dictionary builtin 实例、GDCC / engine 静态、bare utility 值读取。static-context / type-meta signal 仍由 generic `UNSUPPORTED` / `BLOCKED` scan 拦截。

约束：

- blocker 放在 `isCompileBlocking` 短路之前（与 `shouldBlockParameterizedGdccConstructor` 同结构）。谓词必须要求 `status == RESOLVED`。
- bare `METHOD` / `STATIC_METHOD` / `UTILITY_FUNCTION` 必须排除 `CallExpression.callee()`。`helper(right)` / `build(x)` / `print(x)` / `lerp(...)` 零 `sema.compile_check`。
- `BARE_VALUE_REFERENCE_BINDING_KINDS` 当前为空，扫描保留为 callee-exclusion hook；不得按 `GdSignalType` / `GdCallableType` 猜测局部变量。
- `symbolBindings()` 同时键 `IdentifierExpression` / `LiteralExpression` / `SelfExpression`；bare blocker 只消费 identifier。
- `DYNAMIC` 永远不是 compile blocker。
- lambda / `await` / `Node.new` 当值继续走既有 deferred / unsupported / FAILED 路径。

---

## 7. CFG / lowering

- bare signal / bare Object method / bare static / bare utility 走 identifier opaque 路径；payload 可为 `null`（参照 `CONSTANT` / `SINGLETON`）。
- receiver signal → `SignalLoadItem`。
- Object / self 与 builtin 实例方法引用 → `CallableLoadItem`：
  - Object / self：`METHOD + INSTANCE + ownerKind != BUILTIN`，发 `ConstructCallableInsn` + Object liveness
  - builtin instance：`METHOD + INSTANCE + ownerKind == BUILTIN`，同样发 `ConstructCallableInsn`，**不**发 Object guard
- qualified GDCC / engine static → `StandaloneCallableLoadItem`。
- 上述 item **不得**携带 `FrontendWritableRoutePayload`（lowering writable route，不是 sema `RouteKind`）。sema 层 `RouteKind.INSTANCE_SIGNAL` 只用于链归约。
- `FrontendMemberLoadInsnLoweringProcessor` 对到达 `MemberLoadItem` 的 RESOLVED `SIGNAL` / `METHOD` / `STATIC_METHOD` 显式 fail-fast。
- `DYNAMIC` 成员继续走 `MemberLoadItem`。
- Variant receiver 上的 `host.pinged` 不得发布 RESOLVED `SIGNAL`；它走 `VariantGetNamedInsn` / `godot_variant_get_named`。Godot `Variant::get_named` 对 `OBJECT` 落到 `Object::get`，`ClassDB::get_property` 若在 class signal map 命中则构造 `Signal(object, name)`。因此 **ClassDB 已注册** 的 GDCC / engine signal 在运行时仍可能读成 `TYPE_SIGNAL`；这是引擎 named-get 副作用，不是 frontend 把 Variant 成员猜成 signal。解释脚本自定义 signal 不在 native ClassDB map 里，是否能读取决于 `script_instance->get` / `Object.get`，由 `runtime/dynamic_member_variant_signal_read.gd` 对照。

### 7.1 Receiver liveness

复用 `emitAssertObjectLiveIfNeeded`，与普通 instance call 一致：

- `self` / RefCounted 不发 guard
- 其它 Object receiver 发 `AssertObjectLiveInsn` hard-fail
- builtin / standalone 不发 Object guard

`requireLiveObjectReceiverSlotId`：CFG receiver 是 `SelfExpression` 时必须回到 canonical `self` slot。Opaque self 读取会拷进 temp，但仍按 always-live `self` 处理，不得发 `AssertObjectLiveInsn`。

---

## 8. LIR / backend

### 8.1 `construct_signal`

`$result = construct_signal $receiver "<name>"`，operand `(VARIABLE, STRING)`。bare 固定 `self`。C：`godot_new_Signal_with_Object_StringName(live_ptr, GD_STATIC_SN("name"))`。结果是 destroyable builtin。

### 8.2 `construct_callable`

operand 冻结为 `(VARIABLE, STRING)` min/max=2。旧 1-operand 非法，由 operand-count 校验拒绝，无兼容 shim。backend 按 `$receiver` 静态类型分派：

| `$receiver` | C 出口 | liveness |
| --- | --- | --- |
| `GdObjectType` | `godot_new_Callable_with_Object_StringName` | Object liveness |
| 非 Object builtin | 临时 Variant + `godot_Callable_create(NULL, &tmp, name)`，立刻 destroy | 无 Object guard |
| `Variant` / 无 receiver | **拒绝** | — |

`godot_Callable_create` 的 `self` 是 unused static-method receiver，必须传 `NULL`。禁止把 static / utility 或伪造 Object 塞进本 opcode。builtin 分支在 generator 内 pack 临时 Variant，不另插 `PackVariantInsn`。

`FrontendChainReductionHelper.resolveInstanceMethodReference` 仍把裸 `dict.clear` 发布为 `RESOLVED METHOD + INSTANCE + BUILTIN + Dictionary`。compile gate / CFG 必须按 `receiverType instanceof GdDictionaryType` 拒绝，不得误发 builtin 分支。`dict.clear()` 调用面仍走 builtin method call。

### 8.3 `construct_standalone_callable`

`$result = construct_standalone_callable "<kind>" "<owner_or_empty>" "<name>"`，`kind ∈ {utility, static_gdcc, static_engine}`。utility 的 owner 必须为空。C：`gdcc_new_standalone_callable` → `godot_callable_custom_create2`。

- `static_gdcc` / `static_engine` 的 owner 必须是**声明该方法的类**；子类限定名或 bare 继承读取经 `ClassRegistry.findStaticFunctionInHierarchy` 解析到声明类后再写入 LIR。
- `static_engine` 复用 `gdcc_engine_call_static_*` / method-bind，`NULL` receiver。
- custom callable 填 `GDExtensionCallableCustomInfo2`（不要用已弃用的 `callable_custom_create`）。`token` 与 `construct_lambda` 相同，`object_id` 填 0。
- 构造失败（未知 utility、engine bind 缺失、GDCC 静态符号未生成）必须 compile / codegen fail-fast，不得生成第一次 `call` 才崩的空 Callable。

`gdcc_standalone_callable_spec_of` 按 `(kind, owner, name)` 线性去重；每条 spec 单独 `godot_mem_alloc`，指针数组倍增。`deinitialize` 调 `gdcc_standalone_callable_registry_destroy_all()`。`free_func` 是 no-op（多份 Callable 共享同一 spec）。OOM 返回空 Callable。

standalone trampoline 通过 `ClassDB.class_call_static` 调 GDCC / engine 静态方法，不实现 `CALL_STATIC_METHOD` CInsnGen。

### 8.4 注册

三个 opcode 都必须在 `ConstructInsnGen.getInsnOpcodes()` 中，否则 `CCodegen` 按 opcode 映射会抛 `Unsupported instruction opcode`。`CALL_STATIC_METHOD` 故意未注册。

### 8.5 emit / connect

- emit 走既有 BUILTIN vararg：`(const godot_Signal *self, const godot_Variant **argv, godot_int argc)`。零参渲染 `args[fixed + (argc > 0 ? argc : 1)]` + `(fixed + argc == 0) ? NULL : args`，避免零长度 VLA。`Signal.emit` 保留 `const self`。
- `CBodyBuilder.renderVarargArgv` 拒绝未 pack 的 Signal；frontend 必须先发 `PackVariantInsn`。
- `connect(vec.abs)` / `connect(print)` / `connect(Worker.build)` 物化成功后继续走既有 `Signal.connect` builtin 路径，不另造 connect 指令。
- Callable 值上的 `.call(...)` / `.bind(...)` 仍是 builtin `CallMethodInsn`。不实施“对未物化 method-ref 直接 `.bind`”的捷径。
- `var unused: int = sig.emit()` 继续由 `sema.type_check` 按 void→int 拒绝。

### 8.6 ClassDB 注册

`entry.c.ftl` 的 `// Signals` 只迭代 `classDef.signals`。

- 0 参传 `NULL, 0`，不得生成零长度数组
- 有参经 `CGenHelper.renderSignalParameterMetadata` → `renderBoundMetadata(..., "godot_PROPERTY_USAGE_DEFAULT", "signal parameter")`（method-arg usage，不得误用 export-property usage）
- 注册后 `gdcc_destruct_property` 释放 `name` / `hint_string` / `class_name`
- Object 参数 `class_name` 保持空默认 `GD_STATIC_SN(u8"")`
- engine / native 同名守卫：`ClassRegistry.findEngineSignalInHierarchy` 只匹配 `ExtensionGdClass`；skeleton 用 `classDef.getSuperName()` 起查，命中发 `sema.class_skeleton` 并跳过该 `SignalStatement`

`CGenHelper.renderGdTypeInC(GdSignalType)` → `godot_Signal` 目前走 `default` 兜底（`"godot_" + getTypeName()`），无显式 `case GdSignalType`。

---

## 9. Variant 边界

`ClassRegistry.checkAssignable(Signal, Variant)` **不**返回 true。跨 Variant / generic `Array` / `Dictionary` / Variant named / indexed store / 另一个 `emit` 实参，必须走 `FrontendVariantBoundaryCompatibility` 的 `ALLOW_WITH_PACK` / `ALLOW_WITH_UNPACK`，并在 LIR 出现 `PackVariantInsn` / `UnpackVariantInsn`。

---

## 10. 失败边界与不变量

- lowering 只消费 published facts；缺失 / 冲突 fact 必须 fail-fast，禁止重新 binder / type inference。
- signal / callable CFG item 不得带 property writable route。
- builtin 实例只许 `construct_callable` 非 Object 分支；static / utility 只许 `construct_standalone_callable`。
- 到达 `MemberLoadItem` 的 RESOLVED `SIGNAL` / `METHOD` / `STATIC_METHOD` 是 invariant 违规。
- `Variant` receiver 的 `construct_callable` 必须 fail-fast。
- metadata 缺失时禁止猜测 dynamic signal；保持 `MetadataUnknown` → dynamic / unsupported 边界。
- 诊断必须走 `DiagnosticManager` + skip subtree；普通源码错误不得当异常控制流。
- 继承 signal 不得重复注册；engine signal 只读、不注册、冲突被拒。
- 新 `gdcc_*` helper 必须按 `gdcc_runtime_lib.md` 登记。
- 不要顺便实现 `CALL_STATIC_METHOD` backend；那是独立缺口。

---

## 11. 回归锚点

- scope / semantic：`ClassScopeSignalResolutionTest`、`ScopeSignalResolverTest`、`ClassRegistryEngineSignalLookupTest`、`FrontendClassSkeletonTest`、`FrontendAssignmentSemanticSupportTest`、`FrontendBodyOwnerProceduresExprTypeTest.analyzePublishesGdSignalTypeForBareAndReceiverSignalValueReads`、`FrontendChainReductionHelperTest`
- LIR：`ConstructSignalInsnContractTest`、`ConstructCallableInsnContractTest`、`ConstructStandaloneCallableInsnContractTest`
- backend：`CConstructInsnGenTest`（含 inherited GDCC static 解析到声明 owner、未注册 opcode 负例）、`CCodegenSignalRegistrationTest`、`GodotBuiltinGeneratorTest`、`CallMethodInsnGenTest`
- inherited static：`ClassRegistryTest.findStaticFunctionInHierarchyShouldPreferNearestDeclaringOwner`、`FrontendLoweringBodyInsnPassTest.runLowersInheritedStaticMethodReferencesThroughDeclaringOwner`
- Variant：`FrontendVariantBoundaryCompatibilityTest.signalVariantBoundaryUsesExplicitPackUnpackNotAssignability`、`FrontendLoweringBodyInsnPassTest.runPacksAndUnpacksSignalAcrossExplicitVariantBoundaries`、`ClassRegistryTest.checkAssignableRejectsFrontendOnlyBoundaryConversions`
- compile gate：`FrontendCompileCheckAnalyzerTest`（放行、Dictionary / type-meta 拒绝、callee exclusion、`lerp` 值读、`Node.new` / `await` 拒绝）
- e2e（Zig + `GODOT_BIN` 可用时跑，否则 assumption skip）：
  - `member/signal_value_read.gd`
  - `member/signal_emit_connect.gd`
  - `member/signal_inherited_and_engine.gd`
  - `member/signal_null_receiver.gd`
  - `member/callable_value_refs.gd`
  - `member/signal_interop_compiled_to_interpreted.gd`
  - `member/signal_interop_interpreted_to_compiled.gd`
  - `member/signal_interop_bidirectional.gd`
  - `member/signal_interop_engine_crossing.gd`
  - `runtime/dynamic_member_variant_signal_read.gd`
