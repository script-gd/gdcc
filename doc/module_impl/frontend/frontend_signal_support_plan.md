# Frontend Signal 支持实施计划（不含 `await signal` 协程）

> 本文档是 GdScript `signal` 一等值支持（不含 `await signal` 协程）的实施计划。它冻结支持范围、Godot 语义基准、当前已落地事实、关键缺口、设计决策与分阶段实施/验收细则。实施完成后，本文档中“已落地”部分应迁移为独立 `frontend_signal_implementation.md` 事实源，本文档只保留未实现内容。
>
> 本版本已根据三轮 expert review 修订。`review-expert-b` 第一轮（needs-rework）：修正 compile gate 前提、补齐 CFG 崩溃点、`SignalLoadItem`/`CallableLoadItem` 管线接入、`construct_callable` 合同迁移、Variant 边界、receiver liveness 与 ObjectID 非持有语义。`review-expert-b` 第二轮（复核）：冻结 compile gate bare-identifier `symbolBindings` 输入、收窄 D8 至 engine/native、冻结 `construct_callable` 迁移方案与旧语法处理、冻结 D4 `class_name` 与 D7 null/freed 行为、明确 G4 动态 args 生成与 opcode 注册、补 Variant→Signal unpack 测试。`review-expert-a` 第三轮（交叉审阅，SOUND-WITH-MINOR-FIXES）：D6/Phase 0 bare `METHOD` blocker 增加 `CallExpression.callee` 排除并把崩溃面扩至 bare `STATIC_METHOD`/`UTILITY_FUNCTION` 值读取、明确 RESOLVED blocker 的 scan 结构重组、冻结 Phase 3 `GodotBindingTool generate-builtin` 重新生成入口、澄清 G4 `argc==0` 不照抄 utility VLA、补 `connect` 返回 `int` 与 D4 usage-flag / D8 engine-signal helper 细节。第四轮（实施前补强）：冻结 G4 零长度 VLA 的具体 C 渲染、澄清「writable route」术语（lowering `FrontendWritableRoutePayload` vs sema `RouteKind`）、修正 §3 `CallMethodInsnGen`/`renderGdTypeInC` 行号与兜底分支表述、明确 `.emit/.connect` 的 `AttributeCallStep` 无需改动、修正 G1 位置 B 为诊断恢复、引用 `shouldBlockParameterizedGdccConstructor` 作为 D6 重组先例、补 engine signal 参数类型来源链路。
>
> 2026-08-14 增补 **Phase 4.1**（已实施）：builtin 实例方法引用并入已有 `construct_callable`（扩展 `$receiver` 类型分派，operand schema 不变）；无 receiver 的 static/utility 另增 `construct_standalone_callable`。不得把 static/utility 塞回 `construct_callable`，也不得再拆 `construct_callable_from_variant`。Godot 4.5.1 基线见 §2.4；设计冻结见 D9–D14。

## 文档状态

- 状态：计划维护中（Phase 0–4.1 已落地：compile gate + signal 值物化 + ClassDB 注册/冲突守卫 + `.emit` vararg + `connect`/`disconnect` + Object/self / 非 Dictionary builtin 实例 / GDCC·engine 静态 / utility Callable；Phase 5 未实施）
- 更新时间：2026-08-14
- 相关事实源 / 规则：
  - `frontend_rules.md`
  - `scope_architecture_refactor_plan.md`（signal scope/resolver 冻结合同，§4.5）
  - `frontend_lowering_plan.md`（lowering 只消费 published facts、compile gate 解除条件）
  - `frontend_chain_binding_expr_type_implementation.md`（chain binding / expression type 发布规则）
  - `frontend_compile_check_analyzer_implementation.md`（compile-only final gate）
  - `diagnostic_manager.md`（诊断边界与恢复策略）
  - `doc/gdcc_low_ir.md`（`<signals>` LIR grammar 与 instruction syntax）
  - `doc/module_impl/backend/godot_binding_implementation.md`（vararg C ABI 约定）

---

## 1. 支持范围与非目标

### 1.1 本期支持（in scope）

- `signal foo(...)` 声明（已落地，仅需回归保护 + 冲突守卫，见 G9）。
- signal 一等值读取：
  - bare 读取：`foo`（当前类/继承链内 unqualified 标识符）。
  - receiver 限定读取：`obj.foo`、`self.foo`、`other_node.foo`。
- signal 值物化为 runtime `godot_Signal`（`(ObjectID, StringName)` 一等值）。
- signal 跨 Variant 边界的 pack/unpack（见 G8）。
- signal 在 ClassDB 的注册（`godot_classdb_register_extension_class_signal`）。
- `Signal.emit(...)` vararg 调用闭环。
- `Signal.connect(callable, flags=0)` / `Signal.disconnect(callable)` 闭环（Phase 4：Object/self receiver method-reference → `Callable`，见 D5）。
- Phase 4.1：下列值引用直接物化为 `Callable`（见 D9–D14）：
  - builtin **实例**方法引用：`vec.abs`、`array.clear`（并入 `construct_callable`，按 `$receiver` 类型换 C 出口）。
  - GDCC / engine **静态**方法引用：`Worker.build`、bare `static_func`、`JSON.parse_string`（`construct_standalone_callable`）。
  - utility 函数引用：bare `print`、`lerp`（`construct_standalone_callable`）。
- 与之相关的 diagnostics、inheritance、engine signal、negative 用例与端到端测试。

### 1.2 明确非目标（out of scope）

- `await signal` 及任何协程挂起/恢复状态机。
- lambda / capture 语义扩展（`ConstructCallableInsn` / Phase 4.1 新 opcode 均不承接 lambda）。
- **builtin type-meta 方法当值**（`Vector2.abs`、`Vector2.from_angle`）：Godot 4.5.1 analyzer 在 builtin meta 上只解析 constant/enum，不把方法当值；Phase 4.1 **继续拒绝**，与基线对齐。
- **构造器当值**（`Node.new`、`Inner.new`）：Godot 自身历史脆弱（release-build / class-object `new`），本期不实施。
- `CALL_STATIC_METHOD` 完整 backend（`CallStaticMethodInsn` → CInsnGen）不在 Phase 4.1 范围；静态方法 **调用** 与静态方法 **引用** 分开，后者走自定义 Callable trampoline（D12）。
- Callable `bind` / `unbind` / RPC callable 的新 lowering；已物化的 `Callable` 值走既有 builtin `CallMethodInsn`（`.call` / `.bind`）即可。
- signal 声明参数签名对 `emit` 的静态强制检查（见 §2.3）。
- 自定义 `signal` 作为 type annotation 的扩展（`Signal` 类型已存在，不扩展类型系统）。

### 1.3 compile gate 现状与策略（已修正）

> **重要修正**：review 确认当前 compile gate **并没有**任何 signal-specific blocker。`FrontendCompileCheckAnalyzer` 只拦截 `BLOCKED/DEFERRED/FAILED/UNSUPPORTED` 状态（`FrontendCompileCheckAnalyzer.java:193-212`），而 receiver 限定的 `resolvedSignalTrace(...)` / `resolvedMethodReferenceTrace(...)` 与 bare identifier 的 `resolveValueIdentifierExpressionType(...)`（`FrontendExpressionSemanticSupport`）都发布 `RESOLVED`，因此 signal 值读取、`.emit`、`.connect`、method-reference 目前都会**通过 gate**，随后在 CFG 构建（bare）崩溃、或被误 lowering 成 property（receiver）、或在 C 编译阶段失败。

- 因此 **Phase 0 必须先补一组 feature-specific compile gate blocker**，把上述 surface 挡在 lowering 之外，保证编译器安全；之后每个阶段只解除该阶段对应 surface 的 blocker。
- **blocker 输入合同（已修正）**：gate 当前只接收 `expressionTypes/resolvedMembers/resolvedCalls/slotTypes/forIterationPlans`（`FrontendCompileCheckAnalyzer.java:121-132`），**没有**接收 `symbolBindings()`。因此：
  - **member/call blocker**（receiver 限定 signal 读取、`.emit/.connect/.disconnect`）可继续用现有 `resolvedMembers()`/`resolvedCalls()`（`bindingKind==SIGNAL`、receiver 为 `GdSignalType`）。
  - **bare identifier blocker**（bare signal / bare method-reference）**不能**只按 `GdSignalType`/`GdCallableType` 类型猜测——那会误伤合法的 `Signal`/`Callable` 局部变量与参数。**冻结方案**：把现有 `analysisData.symbolBindings()`（`FrontendAnalysisData.java:284-286`，携带 `FrontendBinding.kind()`）传入 `AstWalkerCompileCheckVisitor`，按 `binding.kind()` 定位；复用既有 fact，不新增 bare-binding route fact。
  - **callee 排除（冻结，防误伤）**：bare `METHOD` binding 同时被“bare method **调用** 的 callee”与“method-**reference** 值”共用。只按 `kind==METHOD` 拦截会误伤合法 bare method 调用——baseline `FrontendCompileCheckAnalyzerTest.analyzeForCompileLeavesShortCircuitBinaryExpressionsOnCompileSurface`（L661-683）断言 bare `helper(right)` 零 `sema.compile_check`。因此 bare blocker 必须**排除**作为某个 surface `CallExpression.callee()` 的 bare identifier（在 walk 时构建 callee 排除集合）；只拦截作为**值**使用的 bare method-reference。
  - **崩溃面扩展（冻结）**：同一 `buildIdentifierOpaqueRoute` `default` 崩溃点也覆盖 bare `STATIC_METHOD` / `UTILITY_FUNCTION` 值读取（`FrontendCfgGraphBuilder.java:2003-2006`）。Phase 0 的 bare blocker 必须一并覆盖这三类（`METHOD`/`STATIC_METHOD`/`UTILITY_FUNCTION` 的**值**引用，均带 callee 排除），而不只是 `SIGNAL`/`METHOD`，否则 Phase 0 的“崩溃安全”目标不完整。
- 每个 blocker 都要有可定位 anchor 与 `FrontendCompileCheckAnalyzerTest` anchor 测试。
- 不得连带解除其它 feature 的 blocker。

---

## 2. Godot 语义基准（已核实，基线为 4.5.1）

> **版本修正**：本项目 extension API、generated bindings 与 backend 合同均以 **4.5.1** 为基线（`src/main/resources/extension_api_451.json`、`doc/module_impl/backend/godot_binding_implementation.md`）。以下语义以 4.5.1 本地 metadata 与源码为权威；若需兼容 4.4，必须逐项验证差异，不得笼统引用 4.4。

### 2.1 Signal 是一等值

- `Signal` 是 builtin variant 类型，承载 `(ObjectID, StringName)`。
- 读取 `obj.foo`（`foo` 为 signal）产生一个**新的** `Signal` 值，而不是返回某个已存储的字段。
- `Signal`/`Callable` 只对 receiver 持有 **ObjectID（非 owning 引用）**，不保活对象；`godot_Signal_destroy` 只销毁 value storage（见 §9 风险）。

### 2.2 `Signal.emit(...)` 是真正的 vararg

- `emit` 接受任意数量、任意类型的 Variant 参数（`extension_api_451.json` 中 `Signal.emit` `is_vararg=true` 且无固定参数）。
- Godot **不按** signal 声明的参数数量/类型在调用点强制检查 `emit` 实参；声明参数主要是元数据（用于编辑器与 ClassDB 注册）。
- 参数数量/类型与已连接 handler 的匹配由运行时在分发阶段处理。

### 2.3 声明参数仅作为元数据

- `signal foo(a: int, b: String)` 中的参数类型用于 ClassDB 注册（`GDExtensionPropertyInfo` 数组）与编辑器提示。
- frontend 已对 signal 参数做 ABI 校验（`LirPublicAbiValidator`），但**不得**在 `emit` 调用点按声明签名做静态 arity/type 拒绝。

### 2.4 Callable 值引用（Phase 4.1 基线，4.5.1 / 本地 `tmp/godot-src` + `godotengine/godot`）

> 下列为 Godot 官方 runtime 行为。GDCC 只对齐 **可稳定复现且有 GDExtension 出口** 的部分；与 Godot 历史 quirk 对齐处必须写进验收，不得“修得比引擎更严/更松”而不记录。

| 源码形态                                                        | Godot 是否允许             | 运行时表示                                                                                              | GDExtension / GDCC 已有出口                                                                                                                       |
| --------------------------------------------------------------- | -------------------------- | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| Object/self 实例方法：`obj._handler`、`_handler`                | 是                         | 标准 `Callable(Object*, StringName)`                                                                    | `godot_new_Callable_with_Object_StringName`（Phase 4 已用）                                                                                       |
| builtin **实例**方法：`array.clear`、`vec.abs`                  | 是                         | 自定义 `VariantCallable`：receiver **按值拷贝**进 userdata，`call` 走 `Variant::callp`                  | `godot_Callable_create(self, variant*, method*)`（即 builtin 静态方法 `Callable.create`，`godot_builtin.h:1504`）                                 |
| `Dictionary.clear` 等 keyed builtin 成员                        | **否**（当 key，不是方法） | 必须显式 `Callable.create(dict, &"clear")`                                                              | 同上；Phase 4.1 **不**把 `dict.clear` 当方法引用                                                                                                  |
| builtin **type-meta** 方法：`Vector2.abs`、`Vector2.from_angle` | **否**                     | analyzer 在 builtin meta 上只认 constant/enum，硬类型 miss 报错                                         | 无；Phase 4.1 继续拒绝                                                                                                                            |
| GDCC/script 静态方法：`Worker.build`、bare `static_func`        | 是                         | 标准 `Callable(script_obj, name)`；`is_valid()` 通常为 true                                             | GDCC 没有 script object 作 receiver。必须用 `godot_callable_custom_create2` 直接 trampoline 到已生成静态函数                                      |
| engine/native 静态方法：`JSON.parse_string`                     | 是                         | 标准 `Callable(GDScriptNativeClass*, name)`；官方测试 `.out` 确认 **`is_valid() == false`** 仍可 `call` | 没有稳定的 NativeClass 对象出口。同样走 `callable_custom_create2` + method-bind/`gdcc_engine_call_static_*` trampoline，**不得**依赖 `is_valid()` |
| utility：`print`、`lerp`                                        | 是                         | 自定义 `GDScriptUtilityCallable(name)`；analyzer **编译期常量折叠**                                     | 无专用 `callable_custom_create_utility`。用 `godot_callable_custom_create2`，`call_func` 转发已缓存的 `gdcc_utility_<name>`                       |
| 构造器当值：`Node.new`                                          | 是（但历史脆弱）           | `Callable(class_obj, "new")`                                                                            | 本期不实施                                                                                                                                        |

关键实现锚点（只读，实施时对照，勿抄 Godot 模块私有类）：

- `core/variant/callable.cpp` `Callable::create`：`OBJECT → Callable(ObjectID, method)`，其它 → `VariantCallable`。
- `core/variant/variant_setget.cpp` `Variant::get_named`：非 Dictionary 的 builtin 方法 → `VariantCallable(*this, member)`。
- `modules/gdscript/gdscript.cpp` `GDScriptNativeClass::_get` / `GDScript::_get`：静态方法 → 标准 Callable。
- `modules/gdscript/gdscript_analyzer.cpp:4625+`：utility 标识符常量折叠为 `GDScriptUtilityCallable`。
- `modules/gdscript/gdscript_analyzer.cpp:4055-4099`：builtin **meta** 只处理 constant/enum，**没有**方法当值分支。
- 官方测试：`builtin_method_as_callable.gd`、`static_method_as_callable.gd`、`native_static_method_as_callable.gd`。

**对 GDCC 的直接推论**：

1. **禁止**把 static/utility 或伪造 Object 塞进 `construct_callable`。该 opcode 的 **operand schema**（`(VARIABLE, STRING)` min/max=2）保持 D5 冻结；Phase 4.1 只扩展 **`$receiver` 静态类型分派**（Object vs 非 Object builtin）。
2. builtin 实例方法引用与 Object 实例方法引用共用 `construct_callable`。backend 按 slot 类型选择出口：Object → `godot_new_Callable_with_Object_StringName`；非 Object builtin → `godot_Callable_create`（`VariantCallable`，值拷贝 receiver）。`Variant` receiver 拒绝。
3. utility 与 GDCC/engine 静态方法引用没有 instance receiver，走新 opcode `construct_standalone_callable` + `godot_callable_custom_create2`，不伪造 class-meta Object。
4. engine 静态方法引用的 `is_valid()` 在 Godot 里就是 false；GDCC 自定义 trampoline **应报告 valid**（编译期已知目标），这是有意偏离，必须在测试里写明，不得拿官方 `JSON.parse_string.is_valid()` 当回归金标准。

### 2.4 注册与继承

- signal 通过 `classdb_register_extension_class_signal(library, class_name, signal_name, argument_info, argument_count)` 注册。
- 继承自父类的 signal **不需要**在子类重复注册；只注册当前类**新声明**的 signal。
- engine/native signal（来自原生类）只读、不由 GDCC 注册。

### 2.5 `connect` / `disconnect` 需要 Callable

- `Signal.connect(callable: Callable, flags: int = 0)`、`Signal.disconnect(callable: Callable)`；`flags` 支持 `CONNECT_DEFERRED`、`CONNECT_ONE_SHOT` 等，且可省略（默认 0）。
- **返回类型（4.5.1）**：`connect` 返回 `int`（错误码，`extension_api_451.json:19978`），`disconnect` 返回 void。因此 `var err = sig.connect(...)` 必须接受 `int`，void-context 丢弃也必须工作；测试需覆盖两种用法。
- GDScript 中 bare method 名作为值（如 `foo.connect(_handler)` 的 `_handler`）产生绑定到 `self` 的 `Callable`。
- `Callable` 可由 `(Object, StringName method)` 构造（constructor #2，`godot_new_Callable_with_Object_StringName`）。

---

## 3. 当前已落地事实（无需重做，仅需回归保护）

以下能力已在仓库中存在，实施时**不得回退**：

1. **声明收集**：`FrontendClassSkeletonBuilder` 收集 `signal` 声明并经 `toLirSignal(...)` 产出 `LirSignalDef`。
2. **LIR 模型**：`LirClassDef.signals`、`LirSignalDef`、`<signals>` XML 序列化/解析（`DomLirParser`）。
3. **ABI 校验**：`LirPublicAbiValidator` 校验 signal 参数类型。
4. **scope 查询**：`ClassScope` direct/inherited signal value lookup（`ClassScope.java:122-130,335-350`）；static context 下返回 `FOUND_BLOCKED`。
5. **receiver 解析**：`ScopeSignalResolver.resolveInstanceSignal(...)` → `ScopeResolvedSignal(ownerKind, ownerClass, signal)`。
6. **chain reduction**：`FrontendChainReductionHelper.resolvedSignalTrace(...)`（L1118-1147）发布
   `FrontendResolvedMember(bindingKind=SIGNAL, receiverKind=INSTANCE, resultType=GdSignalType)`，route 为 `RouteKind.INSTANCE_SIGNAL`（sema 层 `RouteKind`，仅用于链归约，不进 CFG/lowering 的 writable-route 机制，见 D2 术语澄清）。
   - **engine signal 参数类型来源**：`resolvedSignalTrace` 经 `GdSignalType.from(signal.signal())` 从 `SignalDef` 派生 `parameterTypes`；engine/native signal 的 `SignalDef` 来自 `extension_api_451.json` 的 `ExtensionGdClass.getSignals()`。Phase 5 的 e2e（`Node.ready`/`Button.pressed`）依赖该链路，Phase 1 前需确认 engine signal 参数类型已正确落进 `ClassRegistry`。
7. **类型**：`GdSignalType`（`getTypeName()=="Signal"`、`getGdExtensionType()==SIGNAL`，`GdSignalType.java:18-51`）；`GdCallableType` 已存在。
8. **只读约束**：`FrontendAssignmentSemanticSupport`（L462-466、L656-659）对 signal 赋值报 read-only。
9. **method-reference 语义**：`resolvedMethodReferenceTrace(...)`（L990-1018）已把 method 引用发布为 `GdCallableType`。Phase 4 只物化 Object/self 实例引用；builtin instance / static / utility 仍由 compile gate 拦截（G10 / Phase 4.1）。
10. **`.emit`/`.connect` 方法解析**：`reduceInstanceMethodStep` → `ScopeMethodResolver.resolveBuiltinInstanceMethod`（L446-488）→ `findBuiltinClass("Signal")`，candidate 选择已支持 vararg（L773-798）。
11. **call lowering**：`lowerExactInstanceCall(...)`（`FrontendSequenceItemInsnLoweringProcessors.java:419-436`）→ `CallMethodInsn(result, name, receiverSlot, args)`，并已先发射 `emitAssertObjectLiveIfNeeded`。
12. **backend call**：`CallMethodInsnGen.generateCCode`（L38-52）按 `BackendMethodCallResolver` 的 mode 分派（含 `BUILTIN`），`emitKnownSignatureCall`（L193-231）做 fixed + varargs 拆分；`BackendMethodCallResolver` 渲染 `godot_<owner>_<method>`。
13. **C runtime wrappers（已存在）**：
    - `godot_new_Signal_with_Object_StringName(godot_Object*, const godot_StringName*)`（`godot_builtin.c:11290`）
    - `godot_classdb_register_extension_class_signal(...)`（`godot_interface.h:869`）
    - `godot_Signal_emit(const godot_Signal*, const godot_Variant **argv, godot_int argc)`（Phase 3 / G4 已闭环）
    - `godot_new_Callable_with_Object_StringName(godot_Object*, const godot_StringName*)`（`godot_builtin.c:11057`）
    - `godot_Callable_create(godot_Callable *self, const godot_Variant*, const godot_StringName*)`（`godot_builtin.h:1504`，Phase 4.1 builtin 实例路径）
    - `godot_callable_custom_create2(...)`（`godot_interface.h:793-802`，Phase 4.1 static/utility 与既有 `construct_lambda`）
    - Variant pack/unpack：`godot_new_Variant_with_Signal` / `godot_new_Signal_with_Variant`（`godot_builtin.h:70-71`）、`godot_Signal_destroy`（`godot_builtin.c:11300`）。
14. **C 类型渲染**：`CGenHelper.renderGdTypeInC(GdSignalType)` → `godot_Signal`、`renderGdTypeName` → `Signal`，二者均命中 `default` 兜底分支（`"godot_" + getTypeName()` / `getTypeName()`，`CGenHelper.java:343,784`），无显式 `GdSignalType` case。属隐式契约；Phase 1 实施时可补显式 `case GdSignalType` 以消除隐式依赖。
15. **raw object 指针提取**：`CBodyBuilder.renderLiveGodotObjectPtr(fatPtr, objType)`（L611-615），合同为“validated live pointer”。
16. **vararg C 约定**：`(fixed..., const godot_Variant **argv, godot_int argc)`；`CBodyBuilder.renderVarargArgv(...)`（L937-955）负责 pack。

---

## 4. 关键缺口（已定位，含 review 新增）

> 每条缺口都给出精确文件/符号位置。实施必须逐条闭环，不得遗漏。G1–G5 为原始缺口（已细化），G6–G9 为 review 新增，G10 为 Phase 4.1。

### G1 — bare signal 读取：CFG 阶段先崩溃，lowering 也缺 case

- 位置 A（**更早的崩溃点**）：`FrontendCfgGraphBuilder.buildIdentifierOpaqueRoute(...)`
  （`src/main/java/gd/script/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilder.java:1959-2009`）。`switch (binding.kind())` 只处理 `LOCAL_VAR/PARAMETER/CAPTURE`、`SELF`、`PROPERTY`、`CONSTANT/SINGLETON`，`SIGNAL` 落入 `default`（L2004-2006）抛 `IllegalStateException`。
- 位置 B（**软防护，非硬崩溃**）：`FrontendIdentifierOpaqueExprInsnLoweringProcessor.lower(...)`（`FrontendOpaqueExprInsnLoweringProcessors.java`，`switch (binding.kind())` 约 L73）也缺 `case SIGNAL`，落入 `default`（L107-110）抛 `session.unsupportedSequenceItem(...)`——走 `DiagnosticManager` 的“诊断 + skip subtree”恢复，而非裸异常。故 bare signal 的**硬崩溃点仅在位置 A**；位置 B 当前以 unsupported 诊断兜底，Phase 0 blocker 的紧迫性来自位置 A。
- 结论：**只改位置 B 不够**，bare `foo` 仍会在 CFG 构建阶段崩溃。两处都必须修复：CFG 为 signal read 发布“无 writable route、保留 binding”的事实（payload 可为 `null`，参照 `CONSTANT/SINGLETON`），再由 opaque processor 发射 `ConstructSignalInsn`。赋值仍由 semantic layer（`FrontendAssignmentSemanticSupport`）拦截。

### G2 — receiver 限定 signal 读取被发布成 `MemberLoadItem` 并误走 property

- 位置：`FrontendCfgGraphBuilder.applyAttributeStep(...)`（L1757-1776）对**所有** `AttributePropertyStep` 无条件发布 `MemberLoadItem` 并附加 property writable route；随后 `FrontendMemberLoadInsnLoweringProcessor.lower(...)`（`FrontendSequenceItemInsnLoweringProcessors.java:654-683`）只按 `receiverKind()` 分派，`bindingKind==SIGNAL` 落入 `INSTANCE` → `InstancePropertyLeaf` → `LoadPropertyInsn`（错误）。
- 需要：CFG 层对 `bindingKind==SIGNAL` 的成员读取改发 `SignalLoadItem`（见 D2），不附加 property writable route；lowering 侧由专用 processor 发射 `ConstructSignalInsn`，并在 `FrontendMemberLoadInsnLoweringProcessor` 对 SIGNAL 显式 fail-fast。

### G3 — C entry 缺少 signal 注册（Phase 2 已闭环）

- 位置：`src/main/c/codegen/template_451/entry.c.ftl` 的 `${classDef.name}_class_bind_methods()`。
- 落地：`// Signals` 段调用 `godot_classdb_register_extension_class_signal(...)`；0 参传 `NULL, 0`；有参经 `renderSignalParameterMetadata` + `gdcc_make_property_full`，注册后 `gdcc_destruct_property`。

### G4 — vararg builtin 方法的 C wrapper 仅生成 0 参（Phase 3 已闭环）

- 位置：`GodotBuiltinGenerator.BuiltinRenderer.appendMethodDefinition(...)`；declaration 与 definition 共用 `renderMethodParameters(...)`。
- 落地：vararg builtin 生成 `(self, ..., const godot_Variant **argv, godot_int argc)`；体内 `args[fixed + (argc > 0 ? argc : 1)]`，再以 `(fixed + argc == 0) ? NULL : args` 调用现有 `GDCC_BUILTIN_METHOD_VOID/RETURN`。`GodotUtilityGenerator` 现已采用同一 VLA 防护。`godot_builtin.h/.c` 已按 `GodotBindingTool generate-builtin --gde 4.5.1 --out src/main/c/codegen/include_451/godot` 重新生成。
- **self const 性（冻结）**：`Signal.emit` metadata `is_const=true`，wrapper 保留 `const godot_Signal *self`。

### G5 — method-reference → Callable 未闭环（`connect`/`disconnect` 前置；Phase 4 已闭环）

- 位置：
  - bare method reference：与 G1 同一 `buildIdentifierOpaqueRoute(...)` `default` 崩溃点（`FrontendCfgGraphBuilder.java:1963-2006`）。
  - receiver method reference：`applyAttributeStep(...)`（L1758-1776）把它发布为 `MemberLoadItem`，随后被当 property 处理；semantic 层已发布 `GdCallableType`（`FrontendChainReductionHelper.java:990-1018`）。
  - LIR：`ConstructCallableInsn`（`src/main/java/gd/script/gdcc/lir/insn/ConstructCallableInsn.java`）只携带 `functionName`，缺 receiver；opcode 合同为单 STRING operand、min/max=1（`GdInstruction.java:28`）。
  - backend：`ConstructInsnGen.generateCCode(...)`（L43-105）无 `ConstructCallableInsn` case。
- 需要：为 method reference 增加独立承载 item（`CallableLoadItem`）或扩展 member item 显式携带 binding route；补 bare/qualified 两条 CFG 路径；扩展 `construct_callable` 合同携带 receiver（见 D5 合同迁移）；补 backend consumer 渲染 `godot_new_Callable_with_Object_StringName(recv_live_object, "method")`。

### G6 —（新增）compile gate 当前没有 signal blocker

- 位置：`FrontendCompileCheckAnalyzer`（`FrontendCompileCheckAnalyzer.java:193-212` 拦截条件、L547-635 扫描逻辑）。
- 现状：无任何 signal-specific 处理；signal 值读取 / `.emit` / `.connect` / method-reference 均为 `RESOLVED` 并通过 gate。
- 需要：Phase 0 增加基于已发布 facts 的 feature-specific blocker（见 §1.3 与 D6），并配 anchor 测试；否则后续阶段“解除 blocker”无从谈起，且当前会崩溃/误 lowering。

### G7 —（新增）新增 CFG item 必须接入全套管线合同

- 位置：
  - `ValueOpItem`（`src/main/java/gd/script/gdcc/frontend/lowering/cfg/item/ValueOpItem.java:24-27`）是 `sealed interface`，`permits` 列表必须加入 `SignalLoadItem` / `CallableLoadItem`。
  - `FrontendBodyLoweringSupport`（L185-262）的 exhaustive `switch (item)` 必须为新 item 产出 `CfgValueMaterialization`。
  - `FrontendSequenceItemInsnLoweringProcessors.createRegistry()`（L105-126）必须注册新 processor。
  - CFG publisher 位于 `FrontendCfgGraphBuilder`（L1757-1776 等）。
- 结论：只新增 record/CFG publisher 会编译失败或 lowering registry 找不到 processor。同一阶段必须一次性接入 permits、materialization collector、processor registry、imports 与 producer/round-trip 测试，并验证 signal/callable item **不携带** property writable route。

### G8 —（新增）Signal 跨 Variant 边界需显式 pack，而非 assignability

- 位置：`FrontendVariantBoundaryCompatibility`（L61-85，含 `ALLOW_WITH_UNPACK` 路径）要求 frontend 在 Variant 边界 pack/unpack；backend pack 名 `godot_new_Variant_with_Signal`（`CGenHelper.java:920-935`）。
- 现状：测试矩阵无对应用例；且 `ClassRegistry.checkAssignable(...)` 对 `Signal→Variant` **并不直接返回 true**（`ClassRegistry.java:843-854`），不能依赖它触发 pack。
- 需要：显式覆盖并断言 LIR 中出现 `PackVariantInsn` / `UnpackVariantInsn`（而非依赖 assignability）；用例见 §8。
- **backend 强约束**：`CBodyBuilder.renderVarargArgv(...)`（`CBodyBuilder.java:945-947`）会拒绝未先 pack 的 Signal，因此测试必须验证 frontend 先产生 `PackVariantInsn`，否则 vararg emit 会在 backend fail-fast。

### G9 —（新增）engine/native signal 同名重声明无实现守卫（范围已收窄；Phase 2 已闭环）

- 位置：`FrontendClassSkeletonBuilder.fillClassMembers` 的 `SignalStatement` 分支，现先走 reserved-prefix 再走 `rejectEngineNativeSignalShadow`。
- **范围收窄（冻结）**：现有 baseline `ScopeSignalResolverTest.resolveObjectSignalShouldPickNearestGdccOwner`（`src/test/java/gd/script/gdcc/scope/resolver/ScopeSignalResolverTest.java:45-62`）明确要求 parent/child 同名 **GDCC** signal 由 nearest-child 优先（shadowing 合法）。因此 G9/D8 **只限定为**：GDCC signal **不得覆盖 inherited engine/native signal**。inherited **GDCC** signal 的 nearest-child 阴影保持允许，不改 scope contract、不改该 baseline 测试。
- 落地：`ClassRegistry.findEngineSignalInHierarchy` 只匹配 `ExtensionGdClass` signal；skeleton 对命中发 `sema.class_skeleton` 并跳过该 `SignalStatement`。

### G10 — builtin / static / utility 值引用无法物化为 Callable（Phase 4.1，已关闭）

- 已落地：
  - compile gate：只继续拦截 Dictionary 实例 METHOD 与 builtin type-meta `STATIC_METHOD`。bare `STATIC_METHOD` / `UTILITY_FUNCTION` 值读已解除；callee 排除保持。
  - CFG：builtin instance → `CallableLoadItem`；qualified GDCC/engine static → `StandaloneCallableLoadItem`；bare static/utility 走 opaque。
  - lowering / backend：Object/builtin 共用 `construct_callable` 类型分派；static/utility 走 `construct_standalone_callable` + `gdcc_new_standalone_callable`。
- 仍拒绝：`dict.clear` 当方法引用、`Vector2.from_angle` / `Vector2.abs` type-meta、`Node.new`、lambda。`CALL_STATIC_METHOD` 仍无 CInsnGen。

---

## 5. 设计决策（需冻结）

> 以下为推荐方案。若实施中发现冲突，必须先回到本节修订并记录理由，再继续编码。

### D1 — signal 值物化指令形态：新增 `construct_signal`

- 新增 `ConstructSignalInsn(resultId, receiverVarId, signalName)`：
  - `receiverVarId`：承载 receiver 对象的 variable operand（bare 读取固定为 `self`）。
  - `signalName`：编译期字符串 operand。
- 新增 opcode `GdInstruction.CONSTRUCT_SIGNAL`，operand schema 遵循 `GdInstruction` 校验合同，并保证 `DomLirParser` round-trip。
- backend 由 `ConstructInsnGen` 新增 case，渲染：
  `godot_new_Signal_with_Object_StringName(<renderLiveGodotObjectPtr(recv)>, GD_STATIC_SN("signalName"))`。
- **backend 注册（必做）**：新 opcode 必须加入 `ConstructInsnGen.getInsnOpcodes()`（`ConstructInsnGen.java:43-50`），否则 `CCodegen`（`CCodegen.java:38-66`）按 opcode→generator 映射不会路由到该 generator——即使 switch 有 case 也不生效。需补 CCodegen dispatch 测试。
- 理由：与既有 `construct_*` 家族一致，复用 `ConstructInsnGen` 的 result/ownership 校验；signal 名是编译期常量、receiver 是运行期值，显式入指令符合 defensive programming。
- 备选（不推荐）：复用 `construct_builtin` 并先物化 Object/StringName 两个实参——步骤更多、边界更糊，放弃。
- **liveness**：构造前必须套用与 call 相同的 receiver liveness/null 策略（见 D7）。

### D2 — CFG 层 signal 读取承载：新增 `SignalLoadItem`，不复用 `MemberLoadItem`

- `SignalLoadItem(memberAnchor, signalName, receiverValueId, resultValueId)`。
- `FrontendCfgGraphBuilder` 在成员读取步骤命中 `bindingKind==SIGNAL` 时发布 `SignalLoadItem`，**不附加 property writable route**。
- body lowering 新增 `FrontendSignalLoadInsnLoweringProcessor` 消费该 item，发射 `ConstructSignalInsn`。
- **必须同步接入**（G7）：`ValueOpItem` `permits`、`FrontendBodyLoweringSupport` materialization switch、processor registry、imports、producer/round-trip 测试。
- 理由：`MemberLoadItem` 合同明确是 property/member read；独立 item 让 G1/G2 修复互不干扰，也避免污染 writable-route 假设。
- 注意：bare signal（G1）走 identifier opaque-expr 路径，不经过 `SignalLoadItem`；两条路径都发射同一 `ConstructSignalInsn`。
- **术语澄清（冻结）**：本文「property writable route」指 lowering 层的 `FrontendWritableRoutePayload`（`FrontendCfgGraphBuilder.appendPropertyWritableRoute(...)` 生成，携带 `RootKind`/`LeafKind`/`StepKind`），**不是** sema 链归约层的 `RouteKind` enum（`FrontendChainReductionHelper.java:78-91`）。§3 第 6 条的 `RouteKind.INSTANCE_SIGNAL` 仅在 sema 层产生、不进 CFG/lowering；`SignalLoadItem`/`CallableLoadItem` **不得**携带 `FrontendWritableRoutePayload`。

### D3 — `.emit(...)` 走既有 builtin vararg call，不改调用结构

- frontend：`signal.emit(args)` 经 `lowerExactInstanceCall` → `CallMethodInsn`，receiver 为已物化的 `godot_Signal` 值；vararg tail 由 `FrontendBodyLoweringSession`（L1043-1095）pack 成 Variant。
- backend：`CallMethodInsnGen` BUILTIN vararg 路径已就绪，生成 `argv/argc`。
- **CFG 层 call step 无需改动**：`foo.emit(...)` 在链归约中先经 `AttributePropertyStep` 读取 signal（G2/D2 的 `SignalLoadItem`），再经 `AttributeCallStep` 发起调用；`FrontendCfgGraphBuilder.applyAttributeStep` 的 `AttributeCallStep` case（约 L1778-1807）无需新增承载，仅依赖 receiver 已被物化为 `godot_Signal`。G2 只覆盖 property step，不涉及 call step。
- 唯一改动是 G4（C wrapper 生成 vararg 版本）。
- 禁止：为 `.emit` 单独造一条新指令或绕开 `CallMethodInsn`。

### D4 — signal 注册元数据渲染与所有权合同

- 复用现有 method/property metadata 合同：`gdcc_make_property_full()` 分配 `name/hint_string/class_name` 字符串，注册后调用 `gdcc_destruct_property()` 释放（参照 `entry.h.ftl:358-420`、`gdcc_bind.h:53-90`）。
- 已冻结细节：
  - 参数名使用声明参数名。
  - **无参数 signal 必须传 `NULL, 0`，不得生成零长度数组**。
  - 注册后释放 `name/hint_string/class_name`。
  - **Object 类型 signal 参数的 `class_name`（冻结）**：signal 参数**完全复用** `CGenHelper.renderBoundMetadata(...)`（`CGenHelper.java:1052-1080`）的现有输出，`class_name` 保持其现有空默认 `GD_STATIC_SN(u8"")`（与 method/property metadata 一致）；不新增专用 Object class-name renderer。若未来需要为 Object signal 参数填真实 class_name，属于影响 method/property 的更大 metadata 改动，需另立项。Variant / typed container 参数的 type/hint 也由 `renderBoundMetadata` 现有逻辑产出。结果用 snapshot 固定。
- 只注册当前类**新声明**的 signal（`classDef.signals`），跳过继承 signal。
- 在 `CGenHelper` 新增 `renderSignalParameterMetadata(...)`，内部委托 `renderBoundMetadata(...)`。
- **base usage flag（冻结）**：调用 `renderBoundMetadata(type, baseUsageExpr, useSite)` 时，signal 参数的 `baseUsageExpr` 必须使用 **method-arg** 的 usage（与 method 参数一致），**不得**误用 export property 的 usage；用 snapshot 固定。

### D5 — `connect`/`disconnect` 的 Callable 构造边界 + `construct_callable` 合同迁移

- **Receiver 边界（Phase 4 落地）**：Phase 4 只物化 **Object/self receiver**（`foo.connect(_handler)`、`foo.connect(obj._handler)`）。`godot_new_Callable_with_Object_StringName` 只接受 `godot_Object*`，因此 Phase 4 **不得**把 builtin 强转 Object，也不得把 static/utility 塞进本 opcode。
- **Phase 4.1 修订（operand schema 仍冻结，类型分派扩展）**：`construct_callable` 保持 `(VARIABLE, STRING)` min/max=2。`$receiver` 允许 `GdObjectType` 或非 Object builtin；`Variant` / static / utility 仍禁止。builtin 实例走同一 opcode 的另一 C 出口（D9/D11）；无 receiver 的函数引用走 `construct_standalone_callable`（D12）。4.1 解封前 compile gate 继续拦截后两类以及 builtin type-meta。
- **`construct_callable` 合同迁移（已冻结：扩展现有 opcode）**：
  - **决定**：扩展现有 `construct_callable` 携带 `(receiver_var, method_name)` 两个 operand；**不**新增独立 opcode。
  - **现状澄清**：该 opcode 当前**无 frontend producer、无 backend C consumer、无 serialized fixture/测试**；但 `ParsedLirInstruction.java:70-73` 已是现有 parser consumer。仓库内无任何旧 1-operand 形式的 fixture，因此 in-repo 迁移风险低。
  - **旧语法兼容（冻结）**：旧 1-operand 形式迁移后**不再合法**，由 `GdInstruction` operand count 校验直接拒绝；因仓库内无 producer/fixture，不提供向后兼容 shim。
  - **touch-point 清单（冻结）**：
    - `GdInstruction.CONSTRUCT_CALLABLE` operand schema（`GdInstruction.java:28`）改为 `(VARIABLE, STRING)`、min/max=2。
    - `ConstructCallableInsn` 增加 `receiverVarId` 字段并更新 `operands()`。
    - `ParsedLirInstruction`（L70-73）解析两个 operand。
    - `doc/gdcc_low_ir.md`（L182-186）更新语法。
    - 新增 parse/round-trip 测试。
    - **无需改**：`SimpleLirBlockInsnParser`（enum-driven 泛化 count/type 校验，`SimpleLirBlockInsnParser.java:103-150`）与 `DomLirSerializer`（委托 `SimpleLirBlockInsnSerializer`，`DomLirSerializer.java:33,152-157`）。
  - **backend 注册（必做）**：迁移后 opcode 需加入 `ConstructInsnGen.getInsnOpcodes()`（见 D1 同理），并补 CCodegen dispatch 测试。
- lambda / 字面量 Callable 不在本期范围；遇到时 compile gate 继续拦截并给 unsupported 诊断。
- 若 method-reference lowering 与 writable-route / dynamic 路径冲突不可调和，允许把 `connect`/`disconnect` 拆为后续阶段，但必须在此显式记录降级边界，不得伪装已支持。

### D6 —（新增）compile gate 策略：先加 blocker，再分阶段解除

- Phase 0 基于已发布 facts 增加 feature-specific blocker：
  - **receiver 限定** signal 值读取（`resolvedMembers()` 中 `bindingKind==SIGNAL`）；
  - receiver 类型为 `GdSignalType` 的 `.emit/.connect/.disconnect`（`resolvedCalls()`）；
  - **bare** signal / method-reference / static-method / utility-function **值**读取：把 `analysisData.symbolBindings()` 传入 `AstWalkerCompileCheckVisitor`（当前未传，见 §1.3），按 `binding.kind()` ∈ {`SIGNAL`,`METHOD`,`STATIC_METHOD`,`UTILITY_FUNCTION`} 定位；**不得**按类型猜测。
- **callee 排除（冻结）**：bare `METHOD`/`STATIC_METHOD`/`UTILITY_FUNCTION` blocker 必须排除作为 surface `CallExpression.callee()` 的 bare identifier（构建 callee 排除集合），否则会误伤合法 bare method 调用（baseline `FrontendCompileCheckAnalyzerTest.java:661-683` 要求 bare `helper(right)` 零 compile_check）。只拦截作为值使用的 method-reference。
- **scan 结构重组（冻结）**：现有 `scanResolvedMemberCompileBlocks`（`FrontendCompileCheckAnalyzer.java:588-606`）与 `scanResolvedCallCompileBlocks`（L611-635）都在 `!isCompileBlocking(status)` 时**提前 continue**，`RESOLVED` 的 SIGNAL member / `GdSignalType` receiver call 永远进不了 blocker。因此必须把 feature-specific RESOLVED blocker 放在 status 短路**之前或并列**的独立分支，而不能在 status 短路之后加分支（那是 no-op）。**现成先例**：`scanResolvedCallCompileBlocks` 的 `shouldBlockParameterizedGdccConstructor(...)`（`FrontendCompileCheckAnalyzer.java:615-622`）正是“在 `isCompileBlocking` 检查之前先跑、命中即 `reportCompileBlock`”的模式，新 blocker 复用同一结构即可。需配注入 RESOLVED SIGNAL member / `Signal.emit` call 并断言 `sema.compile_check` 的测试。
- 每个 blocker 都要有可定位 anchor 与 `FrontendCompileCheckAnalyzerTest` 测试。
- 后续阶段只解除对应 blocker；解除前后都要有对比测试（feature-specific anchor，不只断言总数）。

### D7 —（新增）receiver liveness / null 策略（已冻结）

- **冻结行为**：signal / callable 构造前**原样复用** `emitAssertObjectLiveIfNeeded(...)`（`FrontendBodyLoweringSession.java:697-705`），与普通 instance call 完全一致，不扩展：
  - `self` receiver：不发 guard（方法执行期间必然存活）。
  - RefCounted receiver：不发 guard（fat pointer 保活）。
  - 其它 Object receiver：发 `AssertObjectLiveInsn`，null/freed 时运行时 hard-fail。
- 因此构造阶段不引入额外诊断；null/freed 非 self/非 RefCounted receiver 的可观察行为就是 `AssertObjectLiveInsn` 的 hard-fail，与普通 call 对齐。补 null/freed receiver 测试验证该行为。
- 明确 `Signal`/`Callable` 不持有 receiver（ObjectID 非 owning，见 §9）：构造后 receiver 被释放，value 仍在但失效，`.emit/.connect` 的失效行为由 Godot 运行时 ObjectDB 查决定，GDCC 不额外保活。

### D8 —（新增）engine/native signal 冲突守卫落点（范围已收窄；Phase 2 已落地）

- **范围（冻结）**：只拒绝 **GDCC signal 覆盖 inherited engine/native signal**；inherited **GDCC** signal 的 nearest-child 阴影保持允许（baseline `ScopeSignalResolverTest.java:45-62`，不得回退）。
- **落点（冻结）**：在 skeleton/semantic phase（`FrontendClassSkeletonBuilder` 收集 signal 时）检查当前类新声明 signal 是否与 inherited engine/native signal 同名，命中即发 compile-time diagnostic 拒绝；**不**依赖 runtime ClassDB 兜底。纳入 negative test（见 G9）。
- **落地**：`ClassRegistry.findEngineSignalInHierarchy(className, signalName)` 从给定类（含其自身）沿 super 链查找，只消费 `ExtensionGdClass`；skeleton 用 `classDef.getSuperName()` 起查，避免把当前类自己的新声明误判为 inherited engine signal。

### D9 —（Phase 4.1）扩展 `construct_callable` 类型分派；只新增 `construct_standalone_callable`

- **operand schema 仍冻结**：`construct_callable` 保持 `(VARIABLE, STRING)` min/max=2。旧 1-operand 仍非法。禁止加 kind 枚举 / optional operand / 兼容 shim。
- **`$receiver` 类型分派（Phase 4.1 修订 D5）**：

  | `$receiver` 静态类型                         | C 出口                                                                                                            | liveness                                          |
  | -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
  | `GdObjectType`                               | 维持 Phase 4：`godot_new_Callable_with_Object_StringName`                                                         | D7：`self`/RefCounted 跳过，其它 Object hard-fail |
  | 非 Object builtin（`Vector2` / `Array` / …） | `ConstructInsnGen` 内 pack 临时 Variant → `godot_Callable_create(NULL, &tmp, name)`，立刻 `godot_Variant_destroy` | **不**发 Object guard                             |
  | `Variant`                                    | **拒绝**（盒内是 Object 还是 builtin 编译期未知，会绕过 D7）                                                      | —                                                 |
  | 无 receiver（static / utility）              | **禁止**走本 opcode                                                                                               | —                                                 |

- **只新增一条 opcode**：`construct_standalone_callable`（与 `construct_signal` / `construct_lambda` 并列，走 `ConstructInsnGen` 注册）：
  `$<result> = construct_standalone_callable "<kind>" "<owner_or_empty>" "<name>"`，`kind ∈ {utility, static_gdcc, static_engine}`。
- **不**新增 `construct_callable_from_variant`：builtin 实例与 Object 共用 `construct_callable`，backend 按 slot 类型换出口。
- **不**新增 `construct_utility_callable` / `construct_static_callable`：无 receiver 的两类共用 standalone + kind。
- **不**复用 `CONSTRUCT_LAMBDA`：lambda 携带 capture 与函数体；本阶段没有 capture。
- standalone 的 touch-point：`GdInstruction` schema、insn record、`ParsedLirInstruction`、`doc/gdcc_low_ir.md`、round-trip 测试、`ConstructInsnGen.getInsnOpcodes()` + CCodegen dispatch 测试。`SimpleLirBlockInsnParser` / `DomLirSerializer` 仍靠 enum-driven 泛化，无需手改。
- `construct_callable` 本身 **不改** operand schema；只需改 `ConstructInsnGen` 的 receiver 解析（把 `resolveObjectReceiverVariable` 换成类型 switch）并更新 `gdcc_low_ir.md` 的类型分派说明。
- 新 opcode 必须加入 `ConstructInsnGen.getInsnOpcodes()`，否则 `CCodegen` 按 opcode 映射不会路由到 switch case。

### D10 —（Phase 4.1）CFG item：扩展 `CallableLoadItem` 谓词 + 新增 `StandaloneCallableLoadItem`

- **builtin 实例方法引用**（`vec.abs`、`array.clear`）：仍走 **qualified** `AttributePropertyStep`。把 `isResolvedObjectMethodReference` 旁再开 `isResolvedBuiltinInstanceMethodReference`：`RESOLVED && METHOD && INSTANCE && ownerKind==BUILTIN`。命中则发已有 `CallableLoadItem`（仍不带 writable route）。**不要**为 builtin 再造第三个 load item——receiver value id + method name 形状与 Object 路径相同，分流放在 backend。
- **`CallableLoadItem` 谓词扩展（冻结）**：
  - Object/self：`METHOD + INSTANCE + ownerKind != BUILTIN` → lowering 发 `ConstructCallableInsn` + D7。
  - builtin instance：`METHOD + INSTANCE + ownerKind == BUILTIN` → lowering **同样**发 `ConstructCallableInsn`，**不**发 Object liveness guard。
  - 其它 RESOLVED `METHOD`/`STATIC_METHOD` **不得**落入 `CallableLoadItem`。
- **static / utility 值引用** 形状不同（无 instance receiver）：新增 `StandaloneCallableLoadItem(anchor, kind, ownerName, callableName, resultValueId)`。
  - qualified static：`Type.static_func` 在 `buildTypeMetaHeadMemberStep` 命中 `RESOLVED STATIC_METHOD` 时发此 item，**不再** fail-fast，也 **不得** 落到 `MemberLoadItem`。
  - bare static / utility：扩展 `buildIdentifierOpaqueRoute`，不要再走 `default` 崩溃。**冻结选择**：与 bare `METHOD` 对齐——bare 留在 `OpaqueExprValueItem`，由 `FrontendOpaqueExprInsnLoweringProcessors` 发射 `construct_standalone_callable`；qualified static 才用 `StandaloneCallableLoadItem`。这样 G7 只需为该 item 接一次管线。
- G7 同步清单对 `StandaloneCallableLoadItem` 全套生效：`ValueOpItem.permits`、`FrontendBodyLoweringSupport` materialization、processor registry、imports、producer 测试；**不得**携带 `FrontendWritableRoutePayload`。
- `FrontendMemberLoadInsnLoweringProcessor` 对 RESOLVED `METHOD`/`STATIC_METHOD` 的 fail-fast **保留**（defense-in-depth）。放行后这两类不应再到达 `MemberLoadItem`；到达即 invariant 违规。
- `DYNAMIC` 成员继续走 `MemberLoadItem`，不得被新谓词误收。

### D11 —（Phase 4.1）builtin 实例方法引用并入 `construct_callable`

- lowering：`FrontendCallableLoadInsnLoweringProcessor` 对 Object/self 与 builtin instance **都**发射 `ConstructCallableInsn(result, receiver, method)`。差别只在 guard：Object 走 D7；builtin **不**发 `AssertObjectLiveInsn`。
- backend：`ConstructInsnGen` 按 `$receiver` 静态类型分派（D9 表）。builtin 分支在 generator 内 pack 临时 Variant，立刻 destroy，**不**另插 `PackVariantInsn`。
  - `godot_Callable_create` 的 `self` 是 Godot static builtin method 的 unused receiver；传 `NULL`（与 generated wrapper 一致）。
  - 结果写入 `GdCallableType` destroyable slot，走既有 `emitNonObjectSlotWrite` / alias-safety。
- receiver 语义对齐 `VariantCallable`：
  - 普通 builtin（`Vector2`）按值拷贝，之后改 `vec` 不影响已构造 Callable。
  - `Array` / `Dictionary` 按 Godot Variant 共享语义拷贝（底层 buffer 共享）；`array.clear` 作为 Callable 调用会清空原数组。必须有 runtime 锚点，不得按“深拷贝”写测试。
- `Dictionary` / keyed 容器上的 `dict.clear` **不是**方法引用（Godot 当 key）。核实结论：`FrontendChainReductionHelper.resolveInstanceMethodReference` 仍把裸 `dict.clear` 发布为 `RESOLVED METHOD + INSTANCE + BUILTIN + Dictionary`。compile gate / CFG 必须按 `receiverType instanceof GdDictionaryType` 拒绝，**不得**误发 `construct_callable` 的 builtin 分支。`dict.clear()` 调用面仍走 builtin method call。
- 禁止把 builtin receiver 传给 `godot_new_Callable_with_Object_StringName` 或伪造 Object。

### D12 —（Phase 4.1）static / utility → `construct_standalone_callable` + custom trampoline

- **不**复用 `Callable(class_meta_object, name)`：GDCC 没有 `GDScript` / `GDScriptNativeClass` 对象可当 receiver；engine 路径上 Godot 自己的 `is_valid()` 还是 false。
- backend 为每种 kind 生成/复用一个 `gdcc_*` helper（登记进 `GodotBindingProvidedSymbols` / module-local family，遵守 `gdcc_runtime_lib.md` 注册规则）：
  - `utility`：userdata 指向编译期 utility 名或已缓存的 `gdcc_utility_<name>`；`call_func` 转发 `(r_ret, argv, argc)`。`is_valid_func` 恒 true。`hash/equal/less` 按 name。`get_argument_count_func` 用 utility metadata（vararg → 与 `GDScriptUtilityCallable` 的 Variant 分支一致，取 `get_utility_function_argument_count`）。无堆分配则 `free_func` 可为 no-op。
  - `static_gdcc`：userdata 持有已生成的 GDCC 静态函数指针 + ABI 描述；`call_func` 按已发布的 `FrontendResolvedCall` / function metadata 做 Variant unpack → 调静态函数 → pack 返回值。目标必须是当前编译单元或已链接 GDCC 类的 `static func`。
  - `static_engine`：userdata 持有 owner + method + `EngineMethodBindSpec`（或直接调已有 `gdcc_engine_call_static_<owner>_<method>_<symbolId>`）。`call_func` 走 method-bind，`NULL` receiver。`is_valid_func` 在 bind 可解析时为 true（有意不同于 Godot native-class `is_valid()==false`）。
- **builtin 静态方法引用**（`Vector2.from_angle`）按 §1.2 / §2.4 **拒绝**，不进入 `static_engine` / `static_gdcc`。
- frontend 必须在 lowering 时写入正确 kind + owner：
  - bare utility → `utility` / owner 空 / name=`print`。
  - bare GDCC static / `Worker.build` → `static_gdcc` / owner=`Worker`（或实际声明 owner）/ name。
  - `JSON.parse_string` → `static_engine` / owner=`JSON` / name=`parse_string`。
- `CALL_STATIC_METHOD` backend 仍保持非目标。本阶段只让 **值引用** 变成可 `Callable.call` 的对象；`Worker.build()` 调用面维持现状。
- custom callable 必须填 `GDExtensionCallableCustomInfo2`（不要用已弃用的 `callable_custom_create`）。`token` 使用与 `construct_lambda` 文档相同的 GDExtension token。`object_id` 填 0。

### D13 —（Phase 4.1）compile gate 解除顺序与 callee 排除

- 仍遵守 compile-check 文档 §7 五条件：lowering 落地、合同写入本文与 `gdcc_low_ir.md`、backend 能消费、正反测试、同步 `frontend_rules.md` / `frontend_compile_check_analyzer_implementation.md` / `frontend_lowering_cfg_pass_implementation.md` / `diagnostic_manager.md`（若诊断文案变了）。
- **分步解除，禁止一次拆光**：
  1. 先解除 RESOLVED **builtin instance** method-ref blocker（`METHOD && BUILTIN && INSTANCE`）。
  2. 再解除 RESOLVED **GDCC/engine** `STATIC_METHOD`（qualified）与 bare `STATIC_METHOD` 值读。
  3. 最后解除 bare `UTILITY_FUNCTION` 值读。
- **继续拦截**：
  - builtin type-meta 方法当值（若 sema 能发布；不能发布则保持 miss/UNSUPPORTED，不要新造 RESOLVED）。
  - lambda / capture。
  - `Node.new` 当值。
  - 作为 `CallExpression.callee()` 的 identifier **不得**被值引用 blocker 误伤（D6 callee 排除保持；`helper(right)` / `print(x)` / `build(x)` 零 `sema.compile_check`）。
- 诊断文案从“only Object/self can materialize”改为：builtin 实例指向 `construct_callable` 类型分派；static/utility 指向 `construct_standalone_callable`。旧文案测试必须一起改。
- `DYNAMIC` 仍不得升为 compile blocker。

### D14 —（Phase 4.1）所有权 / 生命周期 / 失败边界

- 新构造的 Callable 都是 **destroyable builtin value**，走 `godot_destroy_Callable`。
- Object 实例：仍不保活 receiver（ObjectID 非 owning，D7）。
- builtin 实例：receiver 已拷进 `VariantCallable`；LIR 层不保活原 slot。`Array`/`Dictionary` 共享语义见 D11。
- static / utility：无 receiver，不发 D7 guard。
- 构造失败（未知 utility 名、engine bind 缺失、GDCC 静态符号未生成）必须 **compile-time / codegen fail-fast**，不得生成会在第一次 `call` 才崩的空 Callable。
- `connect(vec.abs)` / `connect(print)` / `connect(Worker.build)` 一旦物化成功，继续走既有 `Signal.connect` builtin 路径；不为本阶段新造 connect 指令。
- Callable 值上的 `.call(...)` / `.bind(...)` 仍是 builtin `CallMethodInsn`。本阶段不实施“对未物化 method-ref 直接 `.bind`”的捷径，除非它先物化成 `GdCallableType`。

---

## 6. 分阶段实施计划

> 每个阶段都必须保持“可编译、可回归、可单独提交”。阶段间有依赖的，必须按序推进。

### Phase 0 — 范围冻结、回归基线与 compile gate 安全 blocker（G6 + D6）

- 目标：锁定范围；把当前会崩溃/误 lowering 的 signal surface 用 compile gate 挡住，保证编译器安全。
- 依赖：无。
- 状态：**已完成**（2026-08-13）。
- 动作：
  1. ~~运行既有 signal 相关测试，确认全绿（§8 baseline）。~~
  2. ~~把 `analysisData.symbolBindings()` 传入 `AstWalkerCompileCheckVisitor`（`FrontendCompileCheckAnalyzer.java:121-132`），供 bare identifier blocker 使用。~~
  3. ~~增加 feature-specific blocker：receiver 限定 signal 读取（`resolvedMembers` `bindingKind==SIGNAL`）、`.emit/.connect/.disconnect`（`resolvedCalls` receiver 为 `GdSignalType`）、bare signal / bare method-reference / bare static-method / bare utility-function **值**读取（`symbolBindings` `binding.kind()` ∈ {SIGNAL, METHOD, STATIC_METHOD, UTILITY_FUNCTION}，带 callee 排除）。~~
  4. ~~按 D6 重组 `scanResolvedMemberCompileBlocks`/`scanResolvedCallCompileBlocks`，让 RESOLVED SIGNAL member / `GdSignalType` receiver call 进入 blocker（放在 status 短路之前/并列）。~~
  5. ~~为每个 blocker 增加 `FrontendCompileCheckAnalyzerTest` anchor 测试。~~
- 落地注记：
  - `symbolBindings()` 同时键 `IdentifierExpression` / `LiteralExpression` / `SelfExpression`；bare blocker 只消费 identifier 项，不 fail-fast 其它 key。
  - callee 排除集合在 walk 时从 surface `CallExpression.callee()` 收集；合法 `helper(right)` / `print(x)` / `make_static(x)` 不被误伤。
  - RESOLVED SIGNAL member / `Signal.emit|connect|disconnect` 谓词放在 `isCompileBlocking` 短路之前，复用 parameterized GDCC constructor 结构；谓词必须要求 `status == RESOLVED`，不得把 `DYNAMIC` 升格为 compile blocker。
- 验收：
  - baseline 测试全绿。
  - bare `foo`、`obj.foo`、`foo.emit(...)`、`foo.connect(_handler)`、`var c = _handler`、bare static/utility 值读取在 compile 模式被明确拦截（不再进入 lowering 崩溃）。
  - **回归保护**：合法 bare method 调用（如 `helper(right)`）仍零 `sema.compile_check`，不被 callee-exclusion 之外的拦截误伤（`FrontendCompileCheckAnalyzerTest.java:661-683` 保持绿）。
  - inspection 模式不受影响（shared `analyze(...)` 仍发布 RESOLVED facts）。

### Phase 1 — signal 值物化（G1 + G2 + G7 + D1 + D2 + D7）

- 目标：bare 与 receiver 限定 signal 读取都能物化为 `godot_Signal`。
- 依赖：Phase 0。
- 状态：**已完成**（2026-08-13）。
- 动作（按序）：
  1. ~~LIR：新增 `ConstructSignalInsn` 与 `GdInstruction.CONSTRUCT_SIGNAL`；补 `DomLirParser`/`ParsedLirInstruction` 序列化/解析与 round-trip。~~
  2. ~~CFG（bare）：`buildIdentifierOpaqueRoute(...)` 为 `SIGNAL` 发布“无 writable route、保留 binding”事实（payload 可 `null`）。~~
  3. ~~CFG（receiver）：新增 `SignalLoadItem`；`applyAttributeStep(...)` 对 `bindingKind==SIGNAL` 改发该 item，不附加 property writable route。~~
  4. ~~管线接入（G7）：`ValueOpItem` `permits`、`FrontendBodyLoweringSupport` materialization switch、processor registry、imports。~~
  5. ~~body lowering：新增 `FrontendSignalLoadInsnLoweringProcessor` 发射 `ConstructSignalInsn`；bare 路径在 `FrontendIdentifierOpaqueExprInsnLoweringProcessor` 增加 `case SIGNAL`（receiver 固定 `self`）；`FrontendMemberLoadInsnLoweringProcessor` 对 SIGNAL 显式 fail-fast。~~
  6. ~~liveness：构造前套用 D7 guard。~~
  7. ~~backend：`ConstructInsnGen` 新增 `ConstructSignalInsn` case，渲染 `godot_new_Signal_with_Object_StringName(...)`；result 为 `godot_Signal`，按 builtin 值生命周期处理（destroy）。**同时把 `CONSTRUCT_SIGNAL` 加入 `ConstructInsnGen.getInsnOpcodes()`**，并补 CCodegen dispatch 测试。~~
  8. ~~compile gate：解除“signal 值读取” blocker（仅值读取，不含 `.emit`/`.connect`）。~~
- 落地注记：
  - `ConstructSignalInsn(resultId, receiverVarId, signalName)` operand 为 `(VARIABLE, STRING)`；bare 路径固定 `self`。
  - `SignalLoadItem` 只承接 `RESOLVED` + `bindingKind==SIGNAL` 的 receiver 读取；`DYNAMIC` 仍走普通 `MemberLoadItem`。
  - compile gate 只放开 signal **值读取**；`.emit/.connect/.disconnect` 与 bare method/static/utility 值读取仍拦截。
  - D7：`self` / RefCounted 不发 `AssertObjectLiveInsn`；其它 Object receiver 发 hard-fail guard。
- 验收：
  - `var s = foo` / `var s = obj.foo` / `var s = self.foo` 均能编译并物化 `godot_Signal`。
  - `foo = x` 仍报 read-only；static context 读取仍被拦截。
  - 生成 C 中 signal 值正确 destroy，无泄漏/双重释放；null/freed receiver 行为符合 D7。
  - 对应 targeted 测试 + 至少一个端到端 fixture 通过。

### Phase 2 — signal 注册（G3 + G9 + D4 + D8）

- 目标：GDCC 类的新声明 signal 注册到 ClassDB；冲突有守卫。
- 依赖：Phase 0（不依赖 Phase 1，但建议在其后以便端到端验证）。
- 状态：**已完成**（2026-08-13）。
- 动作：
  1. ~~按 D8 落点实现 engine/inherited signal 冲突守卫 + negative test。~~
  2. ~~`CGenHelper` 新增 signal 参数 metadata 渲染（复用 `gdcc_make_property_full`/`gdcc_destruct_property` 合同）。~~
  3. ~~`entry.c.ftl` `_class_bind_methods()` 增加 `// Signals` 段，调用 `godot_classdb_register_extension_class_signal(...)`；无参数传 `NULL, 0`。~~
  4. ~~仅注册 `classDef.signals`（当前类新声明），跳过继承 signal。~~
- 落地注记：
  - `ClassRegistry.findEngineSignalInHierarchy(className, signalName)` 从给定类（含其自身）沿 super 链查找；只匹配 `ExtensionGdClass` 上的 signal，GDCC 父类同名 signal 不计入。skeleton 用 `classDef.getSuperName()` 起查，因此当前类自己的新声明不会被误判为 inherited engine signal。
  - 冲突诊断类别为 `sema.class_skeleton`：跳过该 `SignalStatement` 并写入 `skippedSubtreeRoots`；同一类的其它 member 继续收集。
  - `CGenHelper.renderSignalParameterMetadata(type)` 固定委托 `renderBoundMetadata(type, "godot_PROPERTY_USAGE_DEFAULT", "signal parameter")`；Object `class_name` 保持空默认。
  - `entry.c.ftl` 的 `// Signals` 只迭代 `classDef.signals`；0 参走 `NULL, 0`，有参走栈上 `signal_args[]` + 注册后 `gdcc_destruct_property`。
- 验收：
  - 生成 C 中出现且仅出现当前类新声明 signal 的注册调用。
  - 参数 `GDExtensionPropertyInfo` 类型/数量/名称与声明一致；无参数 signal 传 `NULL, 0`；注册后字符串被释放。
  - Object/Variant/typed-container 参数 metadata 正确。
  - 继承场景不重复注册；engine signal 冲突被拒。
  - 对应 codegen snapshot / targeted 测试通过。

### Phase 3 — `.emit(...)` vararg 闭环（G4 + D3）

- 目标：`foo.emit(a, b, ...)` 任意参数数量/类型可编译并正确分发。
- 依赖：Phase 1（receiver 已是 `godot_Signal` 值）。
- 状态：**已完成**（2026-08-13）。
- 动作：
  1. ~~`GodotBuiltinGenerator.appendMethodDefinition(...)` 为 vararg builtin 方法生成 `(self, const godot_Variant **argv, godot_int argc)` wrapper。~~
  2. ~~用 `GodotBindingTool generate-builtin --gde 4.5.1 --out src/main/c/codegen/include_451/godot` 重新生成 `godot_builtin.h` 与 `godot_builtin.c`。~~
  3. ~~回归全部 6 个 vararg builtin：`Signal.emit`、`Callable.call/call_deferred/rpc/rpc_id/bind`。~~
  4. ~~确认 `CallMethodInsnGen` BUILTIN vararg 路径对 `Signal.emit` 生成正确调用。~~
  5. ~~compile gate：解除 `.emit` blocker；同步更新 `frontend_rules.md`。~~
- 落地注记：
  - 仍走既有 `lowerExactInstanceCall` → `CallMethodInsn`；未新增 emit 指令。
  - vararg wrapper 用 `args[fixed + (argc > 0 ? argc : 1)]` + `(fixed + argc == 0) ? NULL : args`；`rpc_id` 的 1 个 fixed 参数先填入 `args[0]`。
  - compile gate 已放行 `.emit` / `.connect` / `.disconnect`；声明签名仍不静态拒绝 emit arity/type。
  - `var unused: int = sig.emit()` 继续由 `sema.type_check` 按 void→int 拒绝。
- 验收：
  - `foo.emit()`、`foo.emit(1)`、`foo.emit(1, "x", vec)` 均可编译；`emit()` 零参数生成 C 合法（无零长度 VLA）。
  - frontend **不**按声明签名拒绝多余/异型实参（§2.2）。
  - 生成 C 正确 pack vararg 并传 `argc`；`godot_builtin.h/.c` 一致。
  - 既有 vararg builtin 无回归。
  - 对应 targeted + 端到端测试通过。

### Phase 4 — `connect` / `disconnect` + Callable（G5 + D5 + D7）

- 目标：`foo.connect(_handler)`、`foo.disconnect(_handler)`、`foo.connect(obj._handler)` 闭环。
- 依赖：Phase 1（`connect`/`disconnect` 的 receiver 需为已物化 `godot_Signal` 值）。`connect`/`disconnect` 本身是 fixed-arg builtin call，**技术上不依赖 Phase 3**；保留“建议 Phase 3 之后”仅为做 connect+emit 的完整 e2e 运行时验证。
- 状态：**已完成**（2026-08-14）。
- 动作：
  1. ~~按 D5 冻结方案扩展 `construct_callable` 合同，同步 `GdInstruction`/`ConstructCallableInsn`/`ParsedLirInstruction`/`gdcc_low_ir.md`/测试（`SimpleLirBlockInsnParser`、`DomLirSerializer` 无需改）。~~
  2. ~~CFG：为 bare 与 receiver method reference 增加承载（`CallableLoadItem`），修复 `buildIdentifierOpaqueRoute` bare `METHOD` 崩溃点；不共享 property writable-route。~~
  3. ~~body lowering：method-reference 读取发射扩展后的 `ConstructCallableInsn`（bare receiver 固定 `self`）；对 builtin/static/utility method reference 发 unsupported。~~
  4. ~~backend：`ConstructInsnGen` 新增 `ConstructCallableInsn` case，渲染 `godot_new_Callable_with_Object_StringName(recv_live_object, "method")`；套用 D7 liveness；opcode 保持在 `getInsnOpcodes()` 中。~~
  5. ~~`Signal.connect(callable, flags)` / `Signal.disconnect(callable)` 经既有 builtin call 路径消费 `Callable`（含 flags 省略/显式）。~~
  6. ~~compile gate：解除 `.connect` / `.disconnect` blocker；lambda/字面量 Callable 保持拦截。~~
- 落地注记：
  - `ConstructCallableInsn(resultId, receiverVarId, methodName)` operand 为 `(VARIABLE, STRING)`；旧 1-operand 形式由 operand-count 校验拒绝。
  - `CallableLoadItem` 只承接 `RESOLVED` + `bindingKind==METHOD` + instance + 非 builtin owner 的 receiver 读取；bare `METHOD` 留在 opaque 路径并固定 `self`。
  - builtin/static method-reference 不得落入 `MemberLoadItem`：CFG 在 instance 与 type-meta head 两条路径 fail-fast；lowering 对任何到达 `MemberLoadItem` 的 RESOLVED `METHOD`/`STATIC_METHOD` 再 fail-fast。
  - compile gate 放行 `.connect` / `.disconnect` 与 bare Object/self `METHOD` 值读取；builtin instance / static / utility method-reference 与 lambda 仍拦截。
  - flags 使用已支持的限定形式 `Object.CONNECT_*`；bare `CONNECT_DEFERRED` 不在本期 scope 模型内。
  - D7：`self` / RefCounted 不发 `AssertObjectLiveInsn`；其它 Object receiver 发 hard-fail guard。
- 验收：
  - `foo.connect(_handler)` / `foo.disconnect(_handler)` / `foo.connect(obj._handler)` 可编译；`Callable` 正确绑定 receiver 与 method 名。
  - `connect` flags 省略与显式（`CONNECT_DEFERRED`/`CONNECT_ONE_SHOT`）均可编译。
  - lambda method reference 被明确拒绝；builtin/static/utility 值引用改由 Phase 4.1 承接。
  - 对应 targeted + 端到端测试通过（含真实触发 handler 的运行时验证，若 Zig 环境可用）。

### Phase 4.1 — builtin 实例 / GDCC·engine 静态 / utility → Callable（G10 + D9–D14）

- 目标：`var c = vec.abs`、`var c = Worker.build`、`var c = JSON.parse_string`、`var c = print` 以及它们作为 `Signal.connect(...)` 实参时可编译并物化为可 `call` 的 `godot_Callable`。
- 依赖：Phase 4（D5 Object/self 路径保持冻结且绿）。不依赖 Phase 5。
- 状态：**已完成**（2026-08-14 实施 4.1a–d）。
- 实施顺序冻结为四个可单独提交的子阶段。每个子阶段结束必须：相关 targeted tests 绿、`frontend_rules.md` / 本计划落地注记同步、compile gate 只解除该子阶段 surface。

#### Phase 4.1a — LIR / backend 合同先行（不放行 compile gate）

- 动作：
  1. 按 D9 扩展 `ConstructInsnGen` 的 `construct_callable` 类型分派：Object 保持原出口；非 Object builtin 渲染 `godot_Callable_create` + 临时 Variant destroy；`Variant` receiver fail-fast。更新 `gdcc_low_ir.md` 说明，**不改** operand schema。
  2. 新增 `CONSTRUCT_STANDALONE_CALLABLE`：`GdInstruction` schema、insn record、`ParsedLirInstruction`、`gdcc_low_ir.md`、round-trip / 非法 operand 负例。
  3. `ConstructInsnGen` 增加 standalone case 并注册 `getInsnOpcodes()`；补 CCodegen dispatch 正反例。
  4. standalone 渲染 `godot_callable_custom_create2`；先落地 `utility` trampoline（`gdcc_utility_*` 已存在，风险最低）。`static_gdcc` / `static_engine` helper 可在本子阶段只留 fail-fast stub，但 opcode 与 kind 字符串校验必须齐。
  5. 按 `gdcc_runtime_lib.md` 登记任何新 `gdcc_*` helper。
- 验收：
  - 手写 LIR：Object `construct_callable` 仍 round-trip；builtin receiver 的同一 opcode 走 `godot_Callable_create`；standalone fixture 能 parse / serialize。
  - `CConstructInsnGenTest` 的 Object 正例无回归；新增 builtin / `Variant` 负例。
  - 未知 `kind`、缺 owner 的 `static_*`、空 method name 在 parser 或 codegen fail-fast。
  - compile gate **仍拦截** builtin instance / static / utility 源码形态（本子阶段不得解封）。

#### Phase 4.1b — builtin 实例方法引用

- 动作：
  1. 扩展 `isResolvedBuiltinInstanceMethodReference`；`applyAttributeStep` 对 builtin instance `METHOD` 发 `CallableLoadItem`，不再 fail-fast。
  2. `FrontendCallableLoadInsnLoweringProcessor` 对 builtin instance 同样发 `ConstructCallableInsn`；不发 Object liveness guard。分流留给 backend 类型分派。
  3. 先用现有 chain-reduction 测试确认 `Dictionary.clear` / keyed 容器 published fact；若是 key 不是 `METHOD`，写进 D11 并加负例。
  4. 满足五条件后，只解除 compile gate 的 builtin instance method-ref blocker。
  5. type-meta `Vector2.abs` / `Vector2.from_angle` 保持拒绝（§1.2）。
- 验收：
  - [N] CFG：`vec.abs` / `array.clear` 发布 `CallableLoadItem`，无 writable route。
  - [N] lowering：发射 `construct_callable`（与 Object 路径同一 opcode），**不**另造 from-variant 指令。
  - [N] backend：C 含 `godot_Callable_create`；临时 Variant 被 destroy；**不含** `godot_new_Callable_with_Object_StringName`。
  - [N] compile gate：`var c = vec.abs` 零 `sema.compile_check`；`Vector2.from_angle` / `Vector2.abs` 仍被拒或保持 UNSUPPORTED。
  - [N] 负例：`dict.clear` 不走 variant-callable（按 D11 核实后的 fact）。
  - [N] 既有 `vec.abs()` **调用**、Object `_handler` 路径无回归。
  - [N] e2e（Zig 可用）：`array.clear` 作为 Callable 调用后原数组被清空（共享语义）。
  - [B] `FrontendCfgGraphBuilderTest.buildExecutableBodyRejectsBuiltinAndStaticMethodReferencesAsPropertyLoads` 拆成：builtin instance 放行 + static 仍拒绝（直到 4.1c）。

#### Phase 4.1c — GDCC / engine 静态方法引用

- 动作：
  1. `buildTypeMetaHeadMemberStep`：`RESOLVED STATIC_METHOD` 且 owner 为 GDCC/ENGINE 时发 `StandaloneCallableLoadItem`；BUILTIN static 继续 fail-fast / compile 拒绝。
  2. bare `STATIC_METHOD`：opaque 路径发射 `construct_standalone_callable "static_gdcc" ...`（当前类 owner）。
  3. 落地 `static_gdcc` 与 `static_engine` trampoline（不再是 stub）。engine 路径复用 `gdcc_engine_call_static_*` / method-bind，`NULL` receiver。
  4. 解除 qualified + bare `STATIC_METHOD` 值读 blocker；callee 排除保持。
  5. **不**实现 `CallStaticMethodInsn` 的 CInsnGen。
- 验收：
  - [N] `var c = Worker.build` / `var c = static_func` / `var c = JSON.parse_string` 可编译。
  - [N] CFG/lowering：qualified → `StandaloneCallableLoadItem` → `construct_standalone_callable`；bare → opaque → 同一 opcode。
  - [N] `Worker.build()` / `JSON.parse_string(...)` **调用**不被值引用路径误伤。
  - [N] e2e：`c.call(...)` 返回与直接调用相同的结果。
  - [N] `c.is_valid()` 对 GDCC/engine trampoline 为 true（相对 Godot native-class quirk 的有意偏离，测试注释必须写明）。
  - [N] 未知 engine 静态方法 / 未生成的 GDCC 静态符号：compile 或 codegen fail-fast。
  - [B] `FrontendVoidReturnCallIntegrationTest` 对 `call_static_method` 无 generator 的负例保持，除非该测试只覆盖值引用。

#### Phase 4.1d — utility 函数引用 + connect 回归

- 动作：
  1. bare `UTILITY_FUNCTION` opaque 路径发射 `construct_standalone_callable "utility" "" "print"`。
  2. 解除 bare utility 值读 blocker；`print(x)` callee 排除保持。
  3. 补 `sig.connect(vec.abs)` / `sig.connect(print)` / `sig.connect(Worker.build)` 回归（消费 Phase 3/4 已有 connect 路径）。
  4. 同步 §8.6：standalone `var c = _handler` 保持放行；builtin instance / GDCC·engine static / utility 值读改为支持；type-meta builtin / lambda / `Node.new` 仍拒绝。
- 验收：
  - [N] `var c = print` / `var c = lerp` 可编译；`c.call(...)` e2e 与直接调用一致（`print` 无返回值、`lerp` 有返回值各一条）。
  - [N] `print(x)` 零 `sema.compile_check`。
  - [N] `connect` 能接受这三类 Callable 并在 emit 时触发（Zig 可用时）。
  - [N] lambda / `Node.new` / `Vector2.from_angle` 仍 unsupported。
  - [B] Phase 4 Object/self 全部锚点保持绿。

- 落地注记：
  - 冻结合同：`construct_callable` schema 不变，backend 按 `$receiver` 类型分派；只新增 `construct_standalone_callable "<kind>" "<owner>" "<name>"`。`dict.clear` 仍是 published METHOD，但 compile gate / CFG 按 Dictionary receiver 拒绝。
  - 实际落点：`ConstructInsnGen.emitConstructCallable` / `emitConstructStandaloneCallable`；runtime helper `gdcc_new_standalone_callable`（`gdcc_callable.h`，经 `gdcc_helper.h` 聚合引入）；CFG `isResolvedBuiltinInstanceMethodReference` + `StandaloneCallableLoadItem`；opaque `STATIC_METHOD`/`UTILITY_FUNCTION`；compile gate 只拦 Dictionary / builtin type-meta。
  - 测试：`ConstructStandaloneCallableInsnContractTest`、`CConstructInsnGenTest` builtin/standalone 正反例、`FrontendCfgGraphBuilderTest` builtin/static/dict 拆分、`FrontendLoweringBodyInsnPassTest` builtin/static/utility lowering、`FrontendCompileCheckAnalyzerTest` 放行与 Dictionary 负例。
  - 已知边界：e2e Zig（`array.clear` 共享语义、`c.call` / `is_valid` / connect 三类）未在本轮落地，留给 Phase 5。standalone trampoline 通过 `ClassDB.class_call_static` 调 GDCC/engine 静态方法，不实现 `CALL_STATIC_METHOD` CInsnGen。
  - intern 表：`gdcc_standalone_callable_spec_of` 按 `(kind, owner, name)` 线性去重；每条 spec 单独 `godot_mem_alloc`，指针数组按 `gdcc_string_name.h` 方式倍增。`deinitialize` 调 `gdcc_standalone_callable_registry_destroy_all()`。`free_func` 仍是 no-op（多份 Callable 共享同一 spec）。OOM 仍返回空 Callable。
- 若某子阶段与 writable-route / dynamic / `CALL_STATIC_METHOD` 冲突不可调和：只允许把 **该子阶段** 降级，必须在此记录降级边界，不得把“规划中”改成“已完成”。

### Phase 5 — 测试矩阵补全、文档收口、compile gate 终检

- 目标：补齐 Variant 边界 / native / inherited / flags / 生命周期 / negative 用例，收口文档。
- 依赖：Phase 1–4；Phase 4.1 完成后把 §8.6 的“值读取拦截”条改成支持断言，再做终检。4.1 未完成前 Phase 5 不得把 builtin/static/utility 值引用写成已支持。
- 动作：
  1. 补齐 §8 测试矩阵所有用例（尤其 G8 Variant 边界、native/inherited signal、connect flags、null/freed receiver、Phase 4.1 正反例）。
  2. 将本文档“已落地”部分迁移为 `frontend_signal_implementation.md` 事实源；本文档保留未实现/降级边界；把 4.5.1 语义基线写入事实源。
  3. 同步更新所有仍写 `.emit`/signal 未闭环的旧文档：`frontend_rules.md`、`scope_architecture_refactor_plan.md`、`frontend_lowering_plan.md`、`frontend_lowering_skeleton_pre_pass_implementation.md`。
  4. 全量 `clean build`；确认 compile gate 终检无遗漏 blocker、无误放行。
- 验收：
  - §8 所有测试通过。
  - 文档状态更新，事实源拆分完成，上述旧文档表述与之一致。
  - `./gradlew clean build` 全绿。

---

## 7. 失败边界与 fail-fast 规则

- lowering 只消费 published facts；遇到缺失/冲突 fact 必须 fail-fast，禁止重新 binder/type inference。
- `FrontendMemberLoadInsnLoweringProcessor` 对 `bindingKind==SIGNAL` 必须显式 fail-fast（`SignalLoadItem` 路径生效后不应再命中；命中即 invariant 违规）。
- signal/callable item 不得携带 property writable route。
- metadata 缺失时禁止猜测 dynamic signal；保持 `MetadataUnknown` → dynamic/unsupported 边界。
- diagnostics 必须走 `DiagnosticManager` + “diagnostic + skip subtree” 恢复策略；普通源码错误不得作为异常控制流。
- 继承 signal 不得重复注册；engine signal 只读、不注册、冲突被拒（D8）。
- Phase 4.1：builtin 实例必须走 `construct_callable` 非 Object 分支；static/utility 只许 `construct_standalone_callable`。`Variant` receiver 与 Dictionary key 必须 fail-fast。
- Phase 4.1 解封后：builtin 实例必须走 `construct_callable` 的非 Object 分支（`godot_Callable_create`），不得伪造 Object；static/utility 只许 `construct_standalone_callable`，不得塞回 `construct_callable`。到达 `MemberLoadItem` 的 RESOLVED `METHOD`/`STATIC_METHOD` 仍 fail-fast。`Variant` receiver 的 `construct_callable` 必须 fail-fast。
- builtin type-meta 方法当值、构造器当值、lambda 仍必须 unsupported / fail-closed。

---

## 8. 测试矩阵

> baseline（既有，需保持绿）标注为 [B]。新增用例标注为 [N]。

### 8.1 scope / semantic（targeted）

- [B] `ClassScopeSignalResolutionTest`：direct / inherited / static restriction。
- [B] `ScopeSignalResolverTest`：receiver-based resolver。
- [B] `FrontendClassSkeletonTest`：signal skeleton 收集。
- [B] `FrontendAssignmentSemanticSupportTest`：signal read-only。
- [N] signal 值读取 expression type 发布为 `GdSignalType`（bare + receiver）。
- [N] method-reference 发布为 `GdCallableType`（connect 前置）。
- [N] engine/inherited signal 同名重声明被拒（G9/D8）。

### 8.2 LIR（targeted）

- [B] `DomLirParserTest`：`<signals>` round-trip。
- [B] `LirPublicAbiValidatorTest`：signal 参数 ABI。
- [N] `construct_signal` 指令 round-trip 与 operand 校验。
- [N] `construct_callable`（迁移后 `(receiver_var, method_name)`）round-trip；旧 1-operand 形式被 operand-count 校验拒绝。
- [N] Phase 4.1：`construct_callable` 对 Object / builtin receiver 的同一 schema round-trip；`construct_standalone_callable` round-trip；非法 kind / 缺 operand / `Variant` receiver 被拒。

### 8.3 backend / codegen（targeted）

- [N] `ConstructInsnGen`：`construct_signal` → `godot_new_Signal_with_Object_StringName`。
- [N] `ConstructInsnGen`：`construct_callable` → `godot_new_Callable_with_Object_StringName`。
- [N] Phase 4.1：`construct_callable`（builtin receiver）→ `godot_Callable_create`；`construct_standalone_callable` → `godot_callable_custom_create2`。
- [N] CCodegen dispatch：`CONSTRUCT_SIGNAL` / `CONSTRUCT_CALLABLE` / `CONSTRUCT_STANDALONE_CALLABLE` 已注册进 `getInsnOpcodes()` 并被路由（opcode 未注册的负例）。
- [N] `GodotBuiltinGenerator`：vararg builtin 方法生成 vararg wrapper（`Signal.emit` 为锚点 + 全部 vararg builtin 回归 + `argc==0` + `const self`）。
- [N] `entry.c.ftl` signal 注册（拆分为独立 anchor test）：
  - 无参 signal 传 `NULL, 0`；
  - 有参 signal 的 `GDExtensionPropertyInfo` 类型/数量/参数名；
  - Object/Variant/typed-container 参数 metadata（`class_name` 空默认）；
  - 继承不重复注册；
  - 注册后 `name/hint_string/class_name` 释放。

### 8.4 Variant 边界（targeted + e2e，G8）

> 说明：以下 pack/unpack 用例都**不依赖** `ClassRegistry.checkAssignable(Signal→Variant)`（其不直接返回 true），而依赖 `FrontendVariantBoundaryCompatibility` 的显式 pack/unpack 合同。

- [N] pack：signal 传给 `Variant` 参数；断言 LIR 出现 `PackVariantInsn`。
- [N] pack：signal 存入 generic `Array` / `Dictionary`。
- [N] pack：signal 经 Variant indexed/named store。
- [N] pack：signal 作为另一个 vararg `emit` 的参数（验证 `renderVarargArgv` 不 reject 已 pack 的 Signal）。
- [N] unpack：Variant 参数/容器取值赋给 `Signal` 变量；断言 LIR 出现 `UnpackVariantInsn`。

### 8.5 端到端（test_suite，Zig 可用时）

- [N] 声明 + emit（无参 / 有参 / 异型实参）。
- [N] connect + emit 触发 handler（运行时验证）。
- [N] connect flags：省略默认 / `CONNECT_DEFERRED` / `CONNECT_ONE_SHOT`。
- [N] `connect` 返回 `int`：`var err = sig.connect(...)` 接受 int；void-context 丢弃也可编译。
- [N] disconnect 后 emit 不再触发。
- [N] 继承 signal 在 subclass instance 上 emit。
- [N] engine/native signal 读取（如 `Button.pressed`、`Node.ready`）与 inherited native signal 读取。
- [N] null / freed receiver 的 signal 读取 / emit / connect 行为。
- [N] negative：signal 赋值、static signal 读取、lambda / builtin type-meta / `Node.new` 当 Callable、`await signal`（应报 unsupported）。
- [N] Phase 4.1 e2e：`vec.abs` / `array.clear` / `Worker.build` / `JSON.parse_string` / `print` 作为 Callable 被 `.call` 或 `connect` 消费。

### 8.6 compile gate

- [N] Phase 0 blocker anchor 测试（`FrontendCompileCheckAnalyzerTest`）。
- [N] 各阶段 blocker 解除前后对比测试（feature-specific anchor，不只断言总数）。
- [N] standalone `var c = _handler`（Phase 4 已放行）。
- [N] Phase 4.1 支持边界：`var c = vec.abs`、`var c = Worker.build`、`var c = JSON.parse_string`、`var c = print` / `lerp`。
- [N] Phase 4.1 拒绝边界：`Vector2.abs` / `Vector2.from_angle`、`Node.new`、lambda、`dict.clear` 方法误认（按 D11）。
- [N] **callee-exclusion 回归**：合法 bare method / static / utility **调用** `helper(right)` / `build(x)` / `print(x)` 保持零 `sema.compile_check`（`FrontendCompileCheckAnalyzerTest.java:661-683` 锚点）。
- [N] 4.1 完成前：bare static/utility **值**读取仍拦截；4.1c/4.1d 解封后该条改为“值读取放行、调用不被误伤”。

---

## 9. 风险与备注

- **vararg builtin wrapper 是通用改动**（G4）：影响所有 vararg builtin 方法生成，需在 Phase 3 做足回归；`godot_builtin.h/.c` 必须同步重新生成。
- **零长度 VLA 工具链风险（已闭环）**：`GodotUtilityGenerator` 与 `GodotBuiltinGenerator` 均已用 `args[fixed + (argc > 0 ? argc : 1)]` + `(fixed + argc == 0) ? NULL : args` 避免零长度 VLA。`Signal.emit()` 仍须作为**真实生成 C** 的回归，而非只测字符串片段。
- **ObjectID 非 owning 引用**（D7/§2.1）：`Signal`/`Callable` 只保存 ObjectID，不保活 receiver；receiver 被释放后 value 仍存在但已失效。`godot_Signal_destroy` 只销毁 value storage。必须明确：构造/调用的诊断与 guard、freed receiver 后的行为、connection 在 callable object 被释放后的行为，并补测试。
- **method-reference lowering（Phase 4）风险最高**：可能与 writable-route / dynamic receiver 路径交织；若冲突过大，按 D5 降级为后续阶段并显式记录。
- **Phase 4.1 新风险**：
  - `godot_Callable_create` 的 `self` 参数易被误当成结果槽；必须按 generated wrapper 传 `NULL` 并把返回值写入 dest。
  - `Array`/`Dictionary` Variant 共享语义写错测试会把实现锁死成深拷贝。
  - `static_engine` trampoline 若误走 NativeClass `is_valid()` 语义，会与“编译期已知 bind”冲突。
  - 一次解封三类 compile gate 会让失败面不可定位；必须按 4.1b→4.1c→4.1d 解封。
  - 不要顺便实现 `CALL_STATIC_METHOD` backend；那是独立缺口，混进本阶段会拖垮验收。
- **engine signal**：原生类 signal 只读、不注册、同名冲突被拒（D8）；需在 negative 用例覆盖。
- **文档漂移**：`frontend_rules.md:68` 仍写 `.emit(...)` use-site 未闭环；`scope_architecture_refactor_plan.md`、`frontend_lowering_plan.md`、`frontend_lowering_skeleton_pre_pass_implementation.md` 也有同类旧表述。各阶段解封时必须同步更新对应文档，并把 4.5.1 语义基线写入事实源。
- **符号名**：本计划统一使用 `ConstructCallableInsn`（仓库不存在 `ConnectCallableInsn`，避免实现者搜索错误符号）。
- 所有 C 模板 / wrapper 改动必须同步 `doc/module_impl/backend/godot_binding_implementation.md` 的 ABI 约定描述。
