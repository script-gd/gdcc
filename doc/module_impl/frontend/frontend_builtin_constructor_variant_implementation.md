# Frontend Builtin Constructor Variant-Argument Implementation

> Updated: 2026-04-14
>
> 本文档是 frontend builtin constructor 单参数 stable `Variant` shortcut 的事实源，定义其与 ordinary typed-boundary、cast / `as`、engine constructor、gdcc constructor 之间的合同边界，以及 body lowering / backend 的实际物化方式。
> 本文档不再记录阶段性步骤、完成进度或实施流水账；若长期合同变化，应直接改写当前状态。

## 1. 维护合同

- 本文档覆盖 `int(variant)`、`String(variant)`、`Array(variant)`、`Dictionary(variant)`、`String.new(variant)` 等单参数 builtin constructor + stable `Variant` 实参 的 long-term 合同。
- 本文档不描述历史修复步骤，只描述已冻结的当前事实。
- 本文所覆盖的特殊 route 不是 ordinary typed boundary 扩面，也不是 cast / `as` 路径；它是一条独立的 constructor 合同。
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_rules.md`
  - `frontend_implicit_conversion_matrix.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_lowering_(un)pack_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `FrontendConstructorResolutionSupport`
  - `FrontendSequenceItemInsnLoweringProcessors`
  - `FrontendExprTypeAnalyzer`
  - `CBuiltinBuilder`（`constructRegularBuiltin(...)`）

---

## 2. 问题背景

最小关注形状是：

```gdscript
func take_first_as_int(plain: Array) -> int:
    return int(plain[0])
```

其中：

- `plain: Array` 在当前 GDCC 中被解析为 `Array[Variant]`
- `plain[0]` 的静态类型因此是 `Variant`
- `int(plain[0])` 是 bare builtin direct constructor，不是 `as` cast

Godot 对该路径的处理链路已经明确：

- `int(...)` 走 `CallNode` / `reduce_call(...)`，不是 `as` cast
- 当 builtin constructor 满足：
  - receiver 是 builtin type
  - 参数个数是 1
  - 参数静态类型是 `Variant` 或 weak type
- Godot 不再继续做“多 constructor overload 的最具体排序”，而是走专门的 unsafe constructor 路径：
  - 接受该调用
  - 标记 unsafe
  - 结果类型直接设为目标 builtin type
  - 运行时通过 `Variant::construct(...)` / `godot_new_*_with_Variant` 等价路径完成真正转换

Godot 自带 analyzer 测试（`modules/gdscript/tests/scripts/analyzer/warnings/unsafe_call_argument.gd`）已经锚定 `print(int(variant))`、`print(String(variant))`、`print(Vector2(variant))`、`print(Dictionary(variant))` 应得到 `UNSAFE_CALL_ARGUMENT` warning，而不是 ambiguous constructor error。

---

## 3. 当前支持面

### 3.1 支持的 shortcut 形状

- bare builtin direct constructor + 单参数 stable `Variant` 实参
  - `int(variant)`、`float(variant)`、`bool(variant)`、`String(variant)`、`StringName(variant)`、`Array(variant)`、`Dictionary(variant)`、`Vector2(variant)`、`Vector2i(variant)` 等所有 builtin type
- `.new(variant)` builtin route（与 bare direct constructor 共享同一语义结果）
  - `String.new(variant)` 等

### 3.2 命中条件

- receiver 是 builtin `TYPE_META`（即 receiverKind = TYPE_META 且 ownerKind = BUILTIN）
- 参数个数恰好为 1
- 该参数静态类型已经稳定发布为 `Variant` carrier（`GdVariantType`）

### 3.3 命中后的 published fact

- `resolvedCalls()` 发布 `FrontendResolvedCall(status = RESOLVED, callKind = CONSTRUCTOR, receiverKind = TYPE_META, ownerKind = BUILTIN)`
- declaration site 锚定到 builtin owner 本身（`ExtensionBuiltinClass`），不指向某个具体 constructor overload
- 返回类型固定为 receiver builtin instance type
- expr analyzer 在该 published shape 上额外发出 `sema.unsafe_call_argument` warning；warning 不改变 `RESOLVED` 状态

### 3.4 命中的 lowering-ready surface

- body lowering 直接发出 `UnpackVariantInsn(resultSlotId, variantArgSlotId)`
- 不再为该路径发 `ConstructBuiltinInsn`
- 不再调用 ordinary callable-signature materializer 合成 synthetic constructor `FunctionDef`

### 3.5 不属于本合同的形状

- non-builtin constructor receiver（包括 engine class `.new(...)` 与 gdcc class `.new(...)`）
  - 这类 receiver 不命中 builtin-only shortcut
  - `Node(variant)` 仍 fail-closed
- multi-arg builtin constructor
  - 继续走既有 exact-overload 规则与 `FrontendVariantBoundaryCompatibility`
  - synthetic multi-arg ambiguity 仍保持 fail-closed
- `cast` / `as` / `is` 路径
  - 仍由独立 deferred route 处理，不被本 shortcut 劫持
- ordinary call、ordinary fixed-parameter method/global call
  - 仍由 ordinary `checkAssignmentCompatible(...)` 路径处理

---

## 4. Sema 合同

### 4.1 builtin-only shortcut

`FrontendConstructorResolutionSupport.resolveBuiltinConstructor(...)` 在进入 generic `chooseConstructor(...)` 之前先检查：

- argumentTypes.size() == 1
- argumentTypes.getFirst() instanceof GdVariantType

命中后直接返回 `resolved(defaultDeclarationSite(receiverTypeMeta, builtinClass), ScopeOwnerKind.BUILTIN)`，不再继续参与通用 overload ranking。

这条 shortcut 必须只用于 builtin constructor，不得外溢到：

- engine class `.new(...)`
- gdcc class `.new(...)`
- 普通 method/global call
- multi-arg builtin constructor

### 4.2 declaration site 形状

declaration site 故意锚定到 builtin owner 本身（`ExtensionBuiltinClass`），而不是某个具体 constructor metadata entry：

- 这样避免在 overload ranking 中伪造一个“不存在的 winner”
- downstream lowering 可据此直接分流
- expr typing 可据此识别该 route 并附加 Godot-parity warning

### 4.3 owner-anchored publication 的副作用

- `FrontendExprTypeAnalyzer.isUnsafeBuiltinVariantConstructorRoute(...)` 用 `declarationSite instanceof ExtensionBuiltinClass` 作为 `sema.unsafe_call_argument` warning 的判定条件之一
- exact builtin constructor winner（即 `declarationSite` 指向某个具体 constructor）不会触发该 warning
- warning 只命中 special route，不外溢到 `String("seed")`、`Vector3i(1, 2, 3)` 这类精确命中

### 4.4 与 ordinary matrix 的边界

`frontend_implicit_conversion_matrix.md` 仍然是 ordinary typed boundary 真源，builtin 单参数 stable `Variant` constructor shortcut 是 **parallel contract**：

- 命中条件只在 builtin constructor resolution 入口判断
- 不修改 `FrontendVariantBoundaryCompatibility` 本身的 ranking 行为
- 不把 `int(variant)` 解释为 widened conversion 扩面

---

## 5. Lowering 合同

### 5.1 special route 分流点

`FrontendSequenceItemInsnLoweringProcessors.lowerConstructorCall(...)` 在进入 ordinary callable-signature materialization 之前先判断：

- `resolvedCall.ownerKind() == ScopeOwnerKind.BUILTIN`
- `resolvedCall.argumentTypes().size() == 1`
- `resolvedCall.argumentTypes().getFirst() instanceof GdVariantType`

命中后：

- 复用已求值的 `Variant` 实参 slot
- 直接发出 `UnpackVariantInsn(resultSlotId, variantArgSlotId)`
- 不再调用 `materializeCallArguments(...)`
- 不再发 `ConstructBuiltinInsn` 或 `ConstructObjectInsn`

### 5.2 其它 constructor 路径保持现状

- zero-arg Array / Dictionary / Vector 等 → `ConstructBuiltinInsn`
- `Vector3i(1, 2, 3)` 这类 exact builtin constructor → `ConstructBuiltinInsn`
- `Array(source: Array)` 这类非-`Variant` builtin constructor → 现有参数 materialization + `ConstructBuiltinInsn`
- `Node.new()` / `Worker.new()` 等 object constructor → `ConstructObjectInsn`
- gdcc 带参 constructor → 保持 fail-closed

### 5.3 防御性不变量

special route 必须保持：

- `node.argumentValueIds().size() == 1`
- `session.requireValueType(argumentValueId) instanceof GdVariantType`

否则视为协议违例，由 `IllegalStateException` 报错；不得在不一致状态下继续生成 LIR。

### 5.4 与 `materializeFrontendBoundaryValue(...)` 的边界

- ordinary boundary 的 `(un)pack` 物化仍由 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 统一处理
- constructor special route 是 lowering 的另一条独立入口，不走 ordinary boundary materialization
- 两者共享 `UnpackVariantInsn` 这条 LIR 物化 surface，但 selection 责任互不重叠

---

## 6. Backend 合同

### 6.1 C codegen 路径

`UnpackVariantInsn` 由 `CPackUnpackVariantInsnGen` 处理，对每个 builtin target 生成：

- `godot_new_int_with_Variant`
- `godot_new_String_with_Variant`
- `godot_new_StringName_with_Variant`
- `godot_new_Array_with_Variant`
- `godot_new_Dictionary_with_Variant`
- object family 对应的 `godot_new_Object_with_Variant`
- 其它 builtin type 对应的 `godot_new_<Type>_with_Variant`

### 6.2 backend 负向 contract

`CBuiltinBuilder.constructRegularBuiltin(...)`（对应 `ConstructBuiltinInsn`）必须继续保持 API metadata 精确匹配：

- 精确接受 API 元数据中定义的 constructor 参数类型
- 不接受伪造的 `[Variant]` constructor
- 故意 fail-closed，迫使 constructor special route 必须走 `UnpackVariantInsn`

这条负向 contract 由 `CConstructInsnGenTest` 锚定。

### 6.3 端到端 ABI

runtime 转换通过 `gdextension-lite` 暴露的 `godot_new_*_with_Variant` 函数完成，对应 Godot `Variant::construct(...)` 等价路径。

---

## 7. 诊断合同

### 7.1 `sema.unsafe_call_argument` warning

- warning category：`sema.unsafe_call_argument`
- warning severity：WARNING
- warning 触发条件：published call 同时满足
  - `status == RESOLVED`
  - `callKind == CONSTRUCTOR`
  - `receiverKind == TYPE_META`
  - `ownerKind == BUILTIN`
  - `argumentTypes.size() == 1` 且首参数为 `GdVariantType`
  - `declarationSite instanceof ExtensionBuiltinClass`
- warning 信息包含：unsafe call argument、constructor 名称、source `Variant`、target builtin type

### 7.2 warning 与 published fact 的关系

- warning 不改变 `resolvedCalls()` / `expressionTypes()` 中的 stable resolved 事实
- lowering 与 compile-check 继续把该 route 视为 `RESOLVED` 并继续生成 LIR
- warning 仅作为额外 diagnostic 发出，user 可以在 `unsafe_call_argument` 类别下关掉

### 7.3 边界

- `String("seed")`、`Vector3i(1, 2, 3)` 这类 exact builtin constructor 不发该 warning
- object `.new(...)` route 不发该 warning
- `cast` / `as` 路径不发该 warning（cast 路径有独立的诊断类别）

---

## 8. 回归锚点

涉及本文档合同的修改，至少要继续覆盖以下回归锚点：

- `FrontendConstructorResolutionSupportTest`
  - `resolveConstructorUsesBuiltinUnaryVariantShortcutBeforeGenericRanking`
  - `resolveConstructorKeepsMultiArgumentBuiltinRankingFailClosed`
  - 保留至少一个 synthetic multi-arg ambiguity 作为 fail-closed 锚点
- `FrontendLoweringBodyInsnPassTest`
  - `runLowersUnaryVariantBuiltinConstructorsIntoUnpackVariantInsn`
  - 覆盖 `int(seed)` / `String(seed)` / `Array(seed)` / `Dictionary(seed)` 四种形状
  - 保留既有 exact builtin / object constructor negative coverage
- `FrontendExpressionSemanticSupportTest`
  - `resolveCallExpressionTypeTargetsSingleArgVariantBuiltinConstructorsWithoutHijackingObjectOrCastRoutes`
- `FrontendChainBindingAnalyzerTest`
  - `analyzeTargetsSingleArgVariantBuiltinConstructorsWithoutRelaxingBareObjectRoutes`
  - 锚定 4 条 `sema.unsafe_call_argument` warning
  - 保留 exact constructor negative coverage
- `CPackUnpackVariantInsnGenTest`
  - `unpackVariantToStringShouldUseAssignmentSemantics`
  - `unpackVariantToIntShouldUseNumericVariantHelper`
  - `unpackVariantToTypedArrayShouldUseNormalizedArraySymbol`
  - `unpackVariantToTypedDictionaryShouldUseNormalizedDictionarySymbol`
- `CConstructInsnGenTest`
  - `constructBuiltinShouldRejectVariantOperandWithoutExactMetadata`
- e2e scripts
  - `src/test/test_suite/unit_test/script/constructor/builtin_variant_scalar_roundtrip.gd`
  - `src/test/test_suite/unit_test/script/constructor/builtin_variant_container_roundtrip.gd`
  - 由 `GdScriptUnitTestCompileRunnerTest` 走完整 frontend lowering -> C codegen -> native build -> Godot runtime 验证

这些回归锚点的职责分工当前固定为：

- sema tests：验证不再 ambiguous、route kind 仍是 `CONSTRUCTOR`、warning 计数正确
- lowering tests：验证 special route 变成 `UnpackVariantInsn`，不出现 `ConstructBuiltinInsn([Variant])`
- backend/cgen tests：验证最终调用 `godot_new_*_with_Variant`
- e2e tests：验证 runtime conversion 端到端 ABI

---

## 9. 架构反思

这个区域当前已经沉淀出的长期结论是：

- constructor selection 与 materialization 必须分层建模：sema 决定 route 是否被接受，body lowering 决定 LIR 形态，backend 决定运行时符号
- 单参数 stable `Variant` builtin constructor 的核心是“在 constructor resolution 入口特判”，而不是把 `FrontendVariantBoundaryCompatibility` 改造成参与 constructor ranking
- declaration site 锚定到 builtin owner 本身（而不是某个具体 constructor metadata）是“避免 overload ranking 伪造 winner”的关键设计
- 这条 special route 必须落到 `UnpackVariantInsn`，复用现有 `godot_new_*_with_Variant` runtime surface；不得为它放宽 `construct_builtin` 的 API metadata 严格匹配
- `int(variant)` 与 `variant as int` 是两条不同合同：前者是 builtin constructor route，后者是 cast route；两者结果类型、诊断类别、lowering 路径都不同
- warning parity（`sema.unsafe_call_argument`）必须与 published `RESOLVED` 状态并存：warning 是 user 可见的额外信号，但不阻断 lowering/codegen

后续若继续扩展 builtin constructor surface，顺序必须保持为：

1. 先冻结本合同的命中条件与 published fact
2. 再冻结 lowering-ready surface
3. 最后再扩张 backend/codegen/runtime coverage
