# Frontend Signal / Callable 值引用实现说明

> 本文档作为 GdScript `signal` 一等值、`Signal.emit/connect/disconnect` 与方法/utility 值引用 → `Callable`（不含 `await signal` 协程）在 frontend、LIR、C backend 与 runtime helper 全链路的长期事实源。本文档替代 `frontend_signal_support_plan.md` 中已落地的实施记录，不保留分步骤进度或验收流水账。未实现边界仍由计划文档维护。

## 文档状态

- 状态：事实源维护中（compile gate、signal 值物化、ClassDB 注册/engine 冲突守卫、`.emit` vararg、`connect`/`disconnect`、Object/self / 非 Dictionary builtin 实例 / GDCC·engine 静态 / utility Callable、Variant 边界与 test_suite 锚点均已纳入当前实现）
- 更新时间：2026-08-14
- Godot 对齐基线：runtime / GDExtension ABI / generated bindings 固定为 **4.5.1**（`GodotVersion.V451`、`src/main/resources/extension_api_451.json`）
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/main/c/codegen/template_451/entry.c.ftl`
  - `src/main/c/codegen/include_451/gdcc/gdcc_callable.h`
  - 对应的 frontend、LIR、backend 与 `test_suite` 测试
- 关联文档：
  - `frontend_signal_support_plan.md`（仅未实现 / 降级边界）
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `diagnostic_manager.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_lowering_(un)pack_implementation.md`
  - `scope_architecture_refactor_plan.md` §4.5
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/module_impl/backend/godot_binding_implementation.md`
- 明确非目标：
  - `await signal` 及任何协程挂起/恢复状态机
  - lambda / capture；`ConstructCallableInsn` / `construct_standalone_callable` 都不承接 lambda
  - builtin type-meta 方法当值（`Vector2.abs`、`Vector2.from_angle`）
  - 构造器当值（`Node.new`、`Inner.new`）
  - `CALL_STATIC_METHOD` 完整 backend（`CallStaticMethodInsn` → CInsnGen）
  - Callable `bind` / `unbind` / RPC callable 的新 lowering；已物化的 `Callable` 走既有 builtin `CallMethodInsn`
  - 按 signal 声明签名对 `emit` 做静态 arity/type 拒绝
  - 自定义 `signal` 作为 type annotation 扩展

---

## 1. 当前支持面

源码形态与运行时结果：

| 源码 | 结果 | LIR |
| --- | --- | --- |
| `signal foo(...)` | 当前类新声明，注册到 ClassDB | `LirSignalDef` |
| bare / receiver 读取：`foo`、`self.foo`、`obj.foo` | `godot_Signal` 一等值 | `construct_signal` |
| `sig.emit(...)` | 任意数量/类型 Variant 实参 | `CallMethodInsn` + vararg pack |
| `sig.connect(cb[, flags])` / `sig.disconnect(cb)` | `connect` 返回 `int`，`disconnect` 返回 void | 既有 builtin `CallMethodInsn` |
| Object/self 实例方法引用：`_handler`、`obj._handler` | `Callable(ObjectID, name)` | `construct_callable` |
| 非 Dictionary builtin 实例方法：`vec.abs`、`array.clear` | `VariantCallable`（值拷贝 receiver） | 同一 `construct_callable` |
| GDCC / engine 静态：`Worker.build`、`JSON.parse_string` | custom trampoline | `construct_standalone_callable` |
| utility：`print`、`lerp` | custom trampoline | `construct_standalone_callable` |

仍拒绝：`dict.clear` 当方法引用、`Vector2.abs` / `Vector2.from_angle` type-meta、`Node.new` 当值、lambda、`await signal`。inherited GDCC 同名 signal 允许 nearest-child shadow；GDCC 不得覆盖 inherited engine/native signal。

---

## 2. Godot 4.5.1 语义基线

- `Signal` 是 builtin variant，承载 `(ObjectID, StringName)`。读取 `obj.foo` 产生**新的** Signal 值，不是已存储字段。
- `Signal` / `Callable` 只保存 **ObjectID（非 owning）**，不保活 receiver。`godot_Signal_destroy` / `godot_Callable_destroy` 只销毁 value storage。
- `Signal.emit` 是真正 vararg；声明参数只作 ClassDB / 编辑器元数据。frontend 不得在调用点按声明签名拒绝多余或异型实参。
- `connect` 返回 `int`（错误码），`disconnect` 返回 void。`flags` 支持 `Object.CONNECT_DEFERRED` / `Object.CONNECT_ONE_SHOT`，可省略（默认 0）。bare `CONNECT_*` 不在当前 scope 模型内。
- 只注册当前类**新声明** signal；继承 signal 不重复注册。engine/native signal 只读、不由 GDCC 注册。
- engine 静态方法在 Godot 里 `is_valid()` 常为 false。GDCC 自定义 trampoline **报告 valid**（编译期已知目标）；这是有意偏离，不得拿官方 `JSON.parse_string.is_valid()` 当回归金标准。
- `Array` / `Dictionary` 作为 `VariantCallable` receiver 遵循 Godot Variant 共享语义，不是深拷贝。`array.clear` 作为 Callable 调用会清空原数组。

---

## 3. 数据流

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

lowering 只消费 published facts。缺失/冲突 fact 必须 fail-fast，禁止重新 binder。

---

## 4. Semantic / scope 合同

- 无 receiver 的 `foo` 走 `ClassScope` value lookup；static context 返回 `FOUND_BLOCKED`。
- 显式 receiver 的 `obj.foo` 走 `ScopeSignalResolver.resolveInstanceSignal(...)`。metadata 缺失时不得猜测 dynamic signal。
- `FrontendChainReductionHelper.resolvedSignalTrace(...)` 发布 `FrontendResolvedMember(bindingKind=SIGNAL, resultType=GdSignalType)`。engine signal 的 `parameterTypes` 来自 `ExtensionGdClass.getSignals()`。
- `resolvedMethodReferenceTrace(...)` 把方法引用发布为 `GdCallableType`。
- `FrontendAssignmentSemanticSupport` 对 signal 赋值报 read-only。
- `.emit/.connect/.disconnect` 经 `reduceInstanceMethodStep` → builtin `Signal` 方法解析；`AttributeCallStep` 无需专用承载。

---

## 5. Compile gate

`FrontendCompileCheckAnalyzer` 只挂在 `analyzeForCompile(...)`。当前 **RESOLVED feature-specific blocker** 仅剩：

- Dictionary 实例 method-reference：`METHOD && BUILTIN && receiverType instanceof GdDictionaryType`
- builtin type-meta static method-reference：`STATIC_METHOD && ownerKind == BUILTIN`

已放行：signal 值读取、`.emit`、`.connect` / `.disconnect`、Object/self `METHOD` 值读取、非 Dictionary builtin 实例、GDCC/engine 静态、bare utility 值读取。

约束：

- blocker 放在 `isCompileBlocking` 短路之前（与 `shouldBlockParameterizedGdccConstructor` 同结构）。
- bare `METHOD` / `STATIC_METHOD` / `UTILITY_FUNCTION` 必须排除 `CallExpression.callee()`。`helper(right)` / `build(x)` / `print(x)` / `lerp(...)` 零 `sema.compile_check`。
- 不得按 `GdSignalType` / `GdCallableType` 猜测局部变量。
- `DYNAMIC` 永远不是 compile blocker。
- lambda / `await` / `Node.new` 当值继续走既有 deferred / unsupported / FAILED 路径。

---

## 6. CFG / lowering

- bare signal / bare Object method / bare static / bare utility 走 identifier opaque 路径；payload 可为 `null`（参照 `CONSTANT/SINGLETON`）。
- receiver signal → `SignalLoadItem`；Object/self 与 builtin 实例方法引用 → `CallableLoadItem`；qualified GDCC/engine static → `StandaloneCallableLoadItem`。
- 上述 item **不得**携带 `FrontendWritableRoutePayload`（lowering writable route，不是 sema `RouteKind`）。
- `FrontendMemberLoadInsnLoweringProcessor` 对到达 `MemberLoadItem` 的 RESOLVED `SIGNAL` / `METHOD` / `STATIC_METHOD` 显式 fail-fast。
- `DYNAMIC` 成员继续走 `MemberLoadItem`。
- Variant receiver 上的 `host.pinged` 不得发布 RESOLVED `SIGNAL`；它走 `VariantGetNamedInsn` / `godot_variant_get_named`。Godot `Variant::get_named` 对 `OBJECT` 落到 `Object::get`，`ClassDB::get_property` 若在 class signal map 命中则构造 `Signal(object, name)`。因此 **ClassDB 已注册** 的 GDCC / engine signal 在运行时仍可能读成 `TYPE_SIGNAL`；这是引擎 named-get 副作用，不是 frontend 把 Variant 成员猜成 signal。解释脚本自定义 signal 不在 native ClassDB map 里，是否能读取决于 `script_instance->get` / `Object.get`，由 `runtime/dynamic_member_variant_signal_read.gd` 对照 `host.custom_pinged` 与 `host.get("custom_pinged")`。
- liveness（D7）：复用 `emitAssertObjectLiveIfNeeded`。`self` / RefCounted 不发 guard；其它 Object receiver 发 `AssertObjectLiveInsn` hard-fail。builtin / standalone 不发 Object guard。

---

## 7. LIR / backend

### 7.1 `construct_signal`

`$result = construct_signal $receiver "<name>"`，operand `(VARIABLE, STRING)`。bare 固定 `self`。C：`godot_new_Signal_with_Object_StringName(live_ptr, GD_STATIC_SN("name"))`。结果是 destroyable builtin。

### 7.2 `construct_callable`

operand 冻结为 `(VARIABLE, STRING)` min/max=2。旧 1-operand 非法。backend 按 `$receiver` 静态类型分派：

| `$receiver` | C 出口 | liveness |
| --- | --- | --- |
| `GdObjectType` | `godot_new_Callable_with_Object_StringName` | D7 |
| 非 Object builtin | 临时 Variant + `godot_Callable_create(NULL, &tmp, name)`，立刻 destroy | 无 Object guard |
| `Variant` / 无 receiver | **拒绝** | — |

禁止把 static/utility 或伪造 Object 塞进本 opcode。`godot_Callable_create` 的 `self` 是 unused static-method receiver，必须传 `NULL`。

### 7.3 `construct_standalone_callable`

`$result = construct_standalone_callable "<kind>" "<owner_or_empty>" "<name>"`，`kind ∈ {utility, static_gdcc, static_engine}`。utility 的 owner 必须为空。C：`gdcc_new_standalone_callable` → `godot_callable_custom_create2`。构造失败（未知 utility、engine bind 缺失、GDCC 静态符号未生成）必须 compile/codegen fail-fast。

### 7.4 注册

三个 opcode 都必须在 `ConstructInsnGen.getInsnOpcodes()` 中，否则 `CCodegen` 按 opcode 映射会抛 `Unsupported instruction opcode`。`CALL_STATIC_METHOD` 故意未注册。

### 7.5 emit / connect

- emit 走既有 BUILTIN vararg：`(const godot_Signal *self, const godot_Variant **argv, godot_int argc)`。零参渲染 `args[fixed + (argc > 0 ? argc : 1)]` + `(fixed + argc == 0) ? NULL : args`。`Signal.emit` 保留 `const self`。
- `CBodyBuilder.renderVarargArgv` 拒绝未 pack 的 Signal；frontend 必须先发 `PackVariantInsn`。

### 7.6 ClassDB 注册

`entry.c.ftl` 的 `// Signals` 只迭代 `classDef.signals`。0 参传 `NULL, 0`；有参经 `CGenHelper.renderSignalParameterMetadata` → `renderBoundMetadata(..., "godot_PROPERTY_USAGE_DEFAULT", "signal parameter")`，注册后 `gdcc_destruct_property`。Object 参数 `class_name` 保持空默认。engine/native 同名守卫：`ClassRegistry.findEngineSignalInHierarchy` + skeleton `sema.class_skeleton`，跳过该 `SignalStatement`。

---

## 8. Variant 边界（G8）

`ClassRegistry.checkAssignable(Signal, Variant)` **不**返回 true。跨 Variant / generic `Array` / `Dictionary` / Variant named/indexed store / 另一个 `emit` 实参，必须走 `FrontendVariantBoundaryCompatibility` 的 `ALLOW_WITH_PACK` / `ALLOW_WITH_UNPACK`，并在 LIR 出现 `PackVariantInsn` / `UnpackVariantInsn`。

---

## 9. 回归锚点

- scope / semantic：`ClassScopeSignalResolutionTest`、`ScopeSignalResolverTest`、`FrontendClassSkeletonTest`、`FrontendAssignmentSemanticSupportTest`、`FrontendBodyOwnerProceduresExprTypeTest.analyzePublishesGdSignalTypeForBareAndReceiverSignalValueReads`、`FrontendChainReductionHelperTest`
- LIR：`ConstructSignalInsnContractTest`、`ConstructCallableInsnContractTest`、`ConstructStandaloneCallableInsnContractTest`
- backend：`CConstructInsnGenTest`、`CCodegenSignalRegistrationTest`、`GodotBuiltinGeneratorTest`、`CallMethodInsnGenTest`
- Variant：`FrontendVariantBoundaryCompatibilityTest.signalVariantBoundaryUsesExplicitPackUnpackNotAssignability`、`FrontendLoweringBodyInsnPassTest.runPacksAndUnpacksSignalAcrossExplicitVariantBoundaries`、`ClassRegistryTest.checkAssignableRejectsFrontendOnlyBoundaryConversions`
- compile gate：`FrontendCompileCheckAnalyzerTest`（放行、Dictionary / type-meta 拒绝、callee exclusion、`lerp` 值读、`Node.new` / `await` 拒绝）
- e2e（Zig + `GODOT_BIN` 可用时跳过否则 assumption skip）：
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

---

## 10. 长期不变量

- signal / callable CFG item 不得带 property writable route。
- builtin 实例只许 `construct_callable` 非 Object 分支；static/utility 只许 `construct_standalone_callable`。
- 到达 `MemberLoadItem` 的 RESOLVED `SIGNAL`/`METHOD`/`STATIC_METHOD` 是 invariant 违规。
- 诊断必须走 `DiagnosticManager` + skip subtree；普通源码错误不得当异常控制流。
- 新 `gdcc_*` helper 必须按 `gdcc_runtime_lib.md` 登记。
