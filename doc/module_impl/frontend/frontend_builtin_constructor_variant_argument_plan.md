# Frontend Builtin Constructor Variant-Argument Fix Plan

> 本文档记录 `int(plain[0])` 这类 “stable `Variant` 驱动 builtin constructor” 问题的调查结论、推荐修复路线与分阶段验收细则。  
> 当前已落地事实以 `frontend_implicit_conversion_matrix.md`、`frontend_lowering_(un)pack_implementation.md`、`frontend_chain_binding_expr_type_implementation.md` 为准；本文档只保留后续实施仍需执行的计划内容。

## 文档状态

 - 状态：Phase A-D 已完成，Phase E 待实施
 - 更新时间：2026-04-14
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/**`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/backend/c/**`
- 当前相关事实源：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/gdcc_type_system.md`
- 主要外部事实来源：
  - Godot `modules/gdscript/gdscript_parser.cpp`
  - Godot `modules/gdscript/gdscript_analyzer.cpp`
  - Godot `modules/gdscript/gdscript_byte_codegen.cpp`
  - Godot `modules/gdscript/gdscript_vm.cpp`
  - Godot `modules/gdscript/tests/scripts/analyzer/warnings/unsafe_call_argument.gd`

---

## 1. 问题定义

当前最小关注形状是：

```gdscript
func take_first_as_int(plain: Array) -> int:
    return int(plain[0])
```

在当前 GDCC 中，这条路径会被错误地收敛为 “对 `Variant` 的歧义构造调用”，而不是像 Godot 那样按 builtin constructor 的 runtime-open 路径接受并在运行时做转换。

本问题的准确表述不是：

- “`Array` 下标类型推导错误”
- “`int(...)` 被误识别成 cast”
- “backend 缺少某个构造函数”

而是：

1. frontend 当前把 bare builtin constructor route 和 ordinary typed-boundary compatibility 直接复用到同一套 overload applicability/ranking；
2. 当单参数实参静态类型是 stable `Variant` 时，多个一参 builtin constructor 会同时变成 applicable；
3. 当前 ranking 无法在这些候选之间选出唯一 winner，于是错误地落到 ambiguous overload；
4. 即使仅在 frontend 把它改成“解析成功”，当前 backend 的 `ConstructBuiltinInsn` 也仍会因为只接受 API 元数据中的精确 constructor 参数类型而拒绝 `[Variant]`。

---

## 2. 调查结论

### 2.1 Godot 的当前行为

Godot 当前对这类场景的处理链路已经明确：

- `int(...)` 是 builtin constructor call，不是 `as` cast。
- `as` 才会进入 `CastNode` / `reduce_cast(...)`；普通 `identifier(...)` 会进入 `CallNode` / `reduce_call(...)`。
- `plain: Array` 的下标结果若没有更强 element type，会在 analyzer 中得到 `Variant`。
- 当 builtin constructor 满足：
  - receiver 是 builtin type
  - 参数个数是 1
  - 参数静态类型是 `Variant` 或 weak type

  Godot 不再继续做“多个 constructor overload 的最具体排序”，而是直接走一条专门的 unsafe constructor 路径：

  - 接受该调用
  - 标记 unsafe
  - 结果类型直接设为目标 builtin type
  - 运行时再通过 `Variant::construct(...)` / `godot_new_*_with_Variant` 等价路径完成真正转换

Godot 自带 analyzer 测试已经锚定：

- `print(Dictionary(variant))`
- `print(Vector2(variant))`
- `print(int(variant))`

这些形状应得到 `UNSAFE_CALL_ARGUMENT` warning，而不是 ambiguous constructor error。

### 2.2 GDCC 的当前行为

GDCC 当前在前两步与 Godot 一致：

- `Array` 会被解析成 `Array[Variant]`
- `plain[0]` 的静态类型因此是 `Variant`
- `int(...)` 会进入 bare builtin direct constructor route

真正偏离 Godot 的点在 constructor applicability：

- `FrontendConstructorResolutionSupport.matchesCallableArguments(...)` 当前直接复用 `FrontendVariantBoundaryCompatibility`
- `FrontendVariantBoundaryCompatibility` 对 `stable Variant -> concrete target` 返回 `ALLOW_WITH_UNPACK`
- 因此 `int.new(int)`、`int.new(float)`、`int.new(bool)`、`int.new(String)` 都会同时变成 applicable
- 之后的 “most specific” 比较无法区分这些候选，最终返回 ambiguous overload

同时还存在一个容易被忽略的后续约束：

- `FrontendSequenceItemInsnLoweringProcessors.lowerConstructorCall(...)` 当前会把 builtin constructor 统一 lower 成 `ConstructBuiltinInsn`
- 但 backend `CBuiltinBuilder.constructRegularBuiltin(...)` 只接受 API 元数据中定义的精确 constructor 参数类型
- 因此 `ConstructBuiltinInsn(result: int, args = [Variant])` 现在并不是 backend-ready surface

这意味着修复不能只停在 sema “不再报歧义”；必须把 lowering/backend 的落地产物一起设计清楚。

### 2.3 与现有事实源的边界

这次修复必须明确区分三条合同：

- ordinary typed-boundary implicit conversion
- builtin constructor resolution
- `cast` / `as` / `is` 的专用语义

当前文档事实已经说明：

- `frontend_implicit_conversion_matrix.md` 维护的是 typed boundary compatibility 真源
- `frontend_lowering_(un)pack_implementation.md` 维护的是 ordinary boundary `(un)pack` materialization
- `cast` / `as` / `is` 当前不属于 ordinary boundary 合同

因此本问题不应通过“偷偷扩写 ordinary matrix”来修，而应新增一条 **builtin constructor 的专用单参数 `Variant` 路径**。

---

## 3. 目标与非目标

### 3.1 本轮修复目标

- 让 `int(plain[0])`、`String(plain[0])`、`Array(variant_value)`、`Dictionary(variant_value)` 等 **单参数 builtin constructor + stable `Variant` 实参** 不再错误落入 ambiguous overload。
- 保持这条语义与 Godot 一致：
  - 它是 builtin constructor route
  - 不是 cast route
  - 不是普通 fixed-parameter boundary 的简单重用
- 让 lowering/backend 对该路径产出 compile-ready 结果，不新增“前端成功、后端再炸”的漂移状态。
- 保持其它 constructor route、ordinary call route、ordinary boundary matrix 不被顺手放宽。

### 3.2 明确非目标

本计划不包含以下扩面：

- 把所有 multi-arg builtin constructor 都改成 runtime-open
- 借机支持 `int -> float`、`String <-> StringName` 等新的 ordinary implicit conversion
- 把 `int(...)` 改写成 `cast` / `as`
- 修改 `gdparser` 语法层；当前问题不在 parser lowering
- 用 backend 放宽 constructor 元数据匹配来“掩盖” frontend 语义分歧

### 3.3 可选后续目标

Godot 还会对这条路径发 `UNSAFE_CALL_ARGUMENT` warning。  
GDCC 当前若要实现这层 parity，需要补一条“resolved but unsafe” 的稳定发布/诊断合同；这可以作为后续增强，但不应阻塞本轮的功能性修复。

---

## 4. 推荐设计

### 4.1 语义层：builtin-only 单参数 `Variant` shortcut

推荐在 `FrontendConstructorResolutionSupport` 内部新增 builtin-only 的专用 shortcut，而不是继续让这条路径参与通用 overload ranking。

触发条件固定为：

- receiver 是 builtin `TYPE_META`
- 参数个数恰好为 1
- 参数静态类型已经稳定发布为 `Variant`

命中后应直接把 constructor route 视为成功解析，结果类型固定为 receiver builtin instance type，并绕开：

- 通用 applicability 多候选集合
- `ALLOW_WITH_UNPACK` 的 candidate-vs-baseline ranking
- “multiple equally specific overloads remain” 报错路径

这条 shortcut 必须只用于 builtin constructors，不得外溢到：

- engine class `.new(...)`
- gdcc class `.new(...)`
- 普通 method/global call
- multi-arg builtin constructor

### 4.2 lowering 层：改写为 `UnpackVariantInsn`

推荐不要把这条路径继续 lower 成 `ConstructBuiltinInsn(result, [variant])`，而应在 body lowering 中直接改写为：

- `UnpackVariantInsn(result, variantArgSlot)`

原因：

1. backend 已有成熟的 `renderUnpackFunctionName(...)` / `PackUnpackVariantInsnGen`
2. `UnpackVariantInsn` 已能稳定生成：
   - `godot_new_int_with_Variant`
   - `godot_new_String_with_Variant`
   - `godot_new_Array_with_Variant`
   - `godot_new_Dictionary_with_Variant`
   - object family 对应的 `godot_new_Object_with_Variant`
3. 这条路径天然对应 “runtime-open Variant -> concrete conversion”
4. 不需要把 backend `construct_builtin` 的 API-metadata 精确匹配逻辑扭曲成“额外接受伪造的 `[Variant]` constructor”

也就是说，这次修复虽然发生在 constructor route，但 lower 后的 LIR 更接近已有的 `Variant -> concrete` runtime conversion surface，而不是“新增一类 backend 构造元数据例外”。

### 4.3 文档层：这是 constructor 特例，不是 ordinary matrix 扩面

实施时必须同步把文档边界写清楚：

- `frontend_implicit_conversion_matrix.md` 仍然是 ordinary typed boundary 真源
- 单参数 builtin constructor 的 stable `Variant` shortcut 是 **parallel contract**
- `frontend_lowering_(un)pack_implementation.md` 需要说明：
  - ordinary boundary helper 本身不拥有 constructor overload selection
  - 但 constructor special route 最终会复用同一条 `UnpackVariantInsn` 物化路径

### 4.4 为什么不选其它方案

不推荐以下替代方案：

- 继续使用 `FrontendVariantBoundaryCompatibility` 参与 builtin constructor ranking  
  原因：这正是当前 ambiguous overload 的直接成因。

- 仅在 backend `CBuiltinBuilder` 中把 `[Variant]` 当成伪 constructor overload 接受  
  原因：这会把 frontend 语义分歧偷偷下沉到 backend，并让 `construct_builtin` 的 API metadata 校验不再单一可信。

- 把 `int(variant)` 当作 cast 语义重写  
  原因：Godot parser/analyzer 当前明确不是这样建模；而且这会把 bare builtin constructor 与 `as` 路径混为一谈。

---

## 5. 分阶段实施计划

### Phase A. 建立回归锚点并冻结目标行为

当前状态（2026-04-14）：

- 已补齐 `FrontendExpressionSemanticSupportTest`、`FrontendChainReductionHelperTest`、`FrontendChainBindingAnalyzerTest` 的 focused 锚点。
- happy-path 锚点现已直接覆盖：
  - `int(plain[0])`
  - `String(plain[0])`
  - `Array(seed: Variant)`
  - `Dictionary(seed: Variant)`
  - `String.new(seed: Variant)` 这条与 bare builtin direct constructor 共享的 `.new(...)` route
- negative-path 锚点现已保留：
  - bare object constructor 仍要求 `Node.new(...)`
  - `CastExpression` 仍留在独立 deferred route
  - synthetic multi-arg builtin constructor ambiguity 仍保持 fail-closed，用于防止后续把 single-arg special case 误扩成 generic overload 放宽
- 当前实现缺口也已被这些锚点稳定暴露：`int` / `String` 的单参数 stable-`Variant` constructor 仍会在 sema constructor ranking 处偏离目标行为；这正是 Phase B 需要收口的改动面。

目标：

- 先用 focused 单元测试把当前 bug 与目标行为钉死
- 避免后续修改时把 ordinary call、engine constructor 或 cast 路径一起误改

建议修改点：

- `FrontendExpressionSemanticSupportTest`
- `FrontendChainReductionHelperTest`
- `FrontendChainBindingAnalyzerTest`

建议新增/扩充的案例：

- happy path
  - `var plain: Array = Array(); plain.push_back(1); return int(plain[0])`
  - `return String(plain[0])`
  - `var v: Variant = []; return Array(v)`
  - `var v: Variant = {}; return Dictionary(v)`
- negative path
  - engine class bare constructor 仍不接受 `Node(variant)`
  - multi-arg builtin constructor 仍走既有 exact-overload 规则
  - `variant as int` / `variant as Node` 仍走 cast 路径，不被这次修复劫持
  - 原有“人为构造的 ambiguous builtin constructor”测试仍保留，用来确认 generic overload ranking 没被删坏

验收细则：

- happy path：
  - 目标 builtin constructor call 的 `FrontendExpressionType` 为 `RESOLVED`
  - `FrontendResolvedCall` 仍发布 `callKind = CONSTRUCTOR`
  - `receiverKind = TYPE_META` 保持不变
- negative path：
  - 上述非目标案例的现有失败/unsupported 语义不漂移
  - 现有 ambiguous-string-constructor 回归继续成立

---

### Phase B. 在 frontend sema 中拆出 builtin-variant constructor shortcut

当前状态（2026-04-14）：

- 已在 `FrontendConstructorResolutionSupport` 中落地 builtin-only 的单参数 `Variant` shortcut。
- 命中条件现已收口为：
  - receiver 是 builtin type-meta
  - 实参数量恰好为 1
  - 传入 constructor resolver 的规范化参数类型为 `Variant`
- 命中后直接发布 resolved constructor route，并把 declaration site 锚定到 builtin owner；不再伪造某个具体 constructor overload 作为赢家。
- 现有 generic overload ranking 继续保留给 multi-arg builtin constructor 与其它 constructor 路径，synthetic multi-arg ambiguity 的 fail-closed 测试也已补到 resolver 层。
- 追加了 `FrontendConstructorResolutionSupportTest`，专门锚定：
  - unary `Variant` builtin shortcut 会先于 generic ranking 命中
  - multi-arg builtin constructor 仍保持 ambiguous fail-closed

目标：

- 让 builtin constructor 对单参数 stable `Variant` 的处理不再经过通用 overload ranking

建议修改点：

- `FrontendConstructorResolutionSupport`
- `FrontendExpressionSemanticSupport`
- `FrontendChainReductionHelper`

实施要点：

1. 在 builtin constructor resolution 入口前增加一个 builtin-only shortcut 检查。
2. 该 shortcut 只观察：
   - receiver 是否 builtin
   - 参数是否恰好 1 个
   - 参数类型是否为已发布到 resolver 的 `Variant` carrier
3. 命中后直接返回 resolved constructor route，而不是继续进入 `chooseConstructor(...)`。
4. declaration site 可指向 builtin owner 本身；不要伪造某个具体 overload declaration，避免误导 downstream。
5. 其它 constructor 路径继续保持原样：
   - zero-arg default constructor
   - exact same-type/copy constructor
   - ordinary overload ranking
   - engine/gdcc constructor fail-closed

验收细则：

- happy path：
  - `int(variant)` / `String(variant)` / `Array(variant)` / `Dictionary(variant)` 不再发布 ambiguous failure
  - bare builtin direct constructor 与 `.new(...)` builtin route 都共享同一语义结果
- negative path：
  - non-builtin constructor receiver 不命中 shortcut
  - multi-arg builtin constructor 不命中 shortcut
  - 现有 ordinary fixed-parameter method/global call 的 `Variant` boundary 规则不受影响

---

### Phase C. 在 lowering 中把该 special route 落成 `UnpackVariantInsn`

当前状态（2026-04-14）：

- 已在 `FrontendSequenceItemInsnLoweringProcessors.lowerConstructorCall(...)` 中落地 builtin unary-`Variant` constructor special route。
- 该 route 现在会在进入 ordinary callable-signature materialization 之前直接分流：
  - 复用已求值的 `Variant` 实参 slot
  - 直接发出 `UnpackVariantInsn(resultSlotId, variantArgSlotId)`
- 之所以必须先分流，是因为 Phase B 的 sema shortcut 会把 declaration site 故意锚定到 builtin owner；这条 route 不能再要求 synthetic constructor `FunctionDef`。
- 其它 constructor 路径保持原状：
  - `Vector3i(1, 2, 3)` 等 exact builtin constructor 继续 lower 为 `ConstructBuiltinInsn`
  - `Array(source: Array)` 这类非-`Variant` builtin constructor 继续走现有参数 materialization + `ConstructBuiltinInsn`
  - `Node.new()` / `Worker.new()` 等 object constructor 继续 lower 为 `ConstructObjectInsn`
- 已补齐 `FrontendLoweringBodyInsnPassTest` 锚点，直接覆盖：
  - `int(seed: Variant)`
  - `String(seed: Variant)`
  - `Array(seed: Variant)`
  - `Dictionary(seed: Variant)`
  - 并保留既有 exact builtin / object constructor negative coverage

目标：

- 消除 “frontend 成功，但 backend `ConstructBuiltinInsn([Variant])` 仍拒绝” 的漂移

建议修改点：

- `FrontendSequenceItemInsnLoweringProcessors.lowerConstructorCall(...)`
- 如有必要，补充一个小型 helper，但不要新建多余抽象层

实施要点：

1. 识别 “builtin constructor + 单参数 stable `Variant` 实参” 的 lowering-ready 形状。
2. 对该形状直接发：
   - `UnpackVariantInsn(resultSlotId, variantArgSlotId)`
3. 不再为该路径发 `ConstructBuiltinInsn`
4. 其它 constructor route 保持现状：
   - zero-arg Array/Dictionary/Vector 等继续用 `ConstructBuiltinInsn`
   - object `.new()` 继续用 `ConstructObjectInsn`
   - 非 Variant 实参的 builtin constructor 继续走现有 `ConstructBuiltinInsn`

验收细则：

- happy path：
  - `int(plain[0])` lowering 后出现 `UnpackVariantInsn`
  - 对应函数内不再出现 `ConstructBuiltinInsn(result=int, args=[variant])`
  - `String(variant)` / `Array(variant)` / `Dictionary(variant)` 也走同一条 `UnpackVariantInsn`
- negative path：
  - `Vector3i(1, 2, 3)` 这类 exact builtin constructor 仍继续产出 `ConstructBuiltinInsn`
  - `Node.new()` / `Worker.new()` 等 object constructor 路径不变

---

### Phase D. 补齐 backend/集成回归，并同步事实源文档

当前状态（2026-04-14）：

- 已补齐 backend/cgen 锚点：
  - `CPackUnpackVariantInsnGenTest` 现直接覆盖 `godot_new_int_with_Variant`
  - 同文件继续覆盖 `godot_new_String_with_Variant`
  - 新增 typed `Array` 的 `godot_new_Array_with_Variant`
  - 既有 typed `Dictionary` 的 `godot_new_Dictionary_with_Variant` 继续保留
- 已补齐 resource-driven e2e compile/link/run 锚点：
  - `src/test/test_suite/unit_test/script/constructor/builtin_variant_scalar_roundtrip.gd`
  - `src/test/test_suite/unit_test/script/constructor/builtin_variant_container_roundtrip.gd`
  - 两个 case 都由 `GdScriptUnitTestCompileRunner` 走完整的 frontend lowering -> C codegen -> native build -> Godot runtime 验证
  - 并新增 `GdScriptBuiltinConstructorVariantCompileRunnerTest` 作为 targeted 入口，避免日常迭代时重跑整个 resource suite
- 已补齐 backend negative coverage：
  - `CConstructInsnGenTest` 新增 `ConstructBuiltinInsn(result=int, args=[Variant])` 仍必须 fail-closed
  - 这条测试直接锚定 backend `construct_builtin` 继续保持 exact constructor metadata contract，不接受伪造的 `[Variant]` constructor
- 已同步更新事实源文档：
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_lowering_(un)pack_implementation.md`
  - `frontend_implicit_conversion_matrix.md`
  - `frontend_rules.md`
  - `frontend_lowering_cfg_pass_implementation.md`
- 当前文档合同已经统一到同一结论：
  - builtin 单参数 stable `Variant` constructor 是 constructor special case
  - 它不属于 ordinary typed-boundary matrix 扩面
  - route 选定后由 body lowering 显式落成 `UnpackVariantInsn`
  - backend `ConstructBuiltinInsn` 仍保持 exact metadata 校验，不为这次修复放宽

目标：

- 让最终 compile-ready surface、代码生成和文档合同一致

建议修改点：

- 测试：
  - `FrontendLoweringBodyInsnPassTest`
  - 适合时新增一个 backend/cgen 或 compile integration 测试，直接检查生成代码包含 `godot_new_int_with_Variant`
- 文档：
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_lowering_(un)pack_implementation.md`
  - `frontend_implicit_conversion_matrix.md`
  - `frontend_rules.md`

文档同步要点：

- constructor special case 不属于 ordinary matrix 的“新增 widened conversion”
- 但它最终复用 `UnpackVariantInsn`
- `int(...)` 与 `as int` 仍是两条不同合同

验收细则：

- happy path：
  - targeted integration / cgen 测试可直接看到：
    - `godot_new_int_with_Variant`
    - `godot_new_String_with_Variant`
    - `godot_new_Array_with_Variant`
    - `godot_new_Dictionary_with_Variant`
  - 相关事实源文档对“谁负责 selection、谁负责 materialization”的描述一致
- negative path：
  - backend `construct_builtin` 的 API metadata 精确匹配逻辑保持收口，不被这次修复偷偷放宽
  - 文档中不出现“这是 ordinary implicit conversion 扩面”之类的误导表述

---

## 6. 推荐测试清单

实施时建议只跑 targeted tests，不做全量回归：

- `FrontendExpressionSemanticSupportTest`
- `FrontendChainReductionHelperTest`
- `FrontendChainBindingAnalyzerTest`
- `FrontendConstructorResolutionSupportTest`
- `FrontendLoweringBodyInsnPassTest`
- 视最终落点补一个 backend/cgen 或 compile integration test

每个测试层的职责应固定为：

- sema tests：验证不再 ambiguous、route kind 仍是 constructor
- lowering tests：验证 special route 变成 `UnpackVariantInsn`
- backend/cgen tests：验证最终调用 `godot_new_*_with_Variant`

---

## 7. 风险与防线

主要风险有三类：

### 7.1 误把 ordinary boundary 合同扩写成 constructor 合同

防线：

- builtin-only shortcut 必须收口在 `FrontendConstructorResolutionSupport`
- `FrontendVariantBoundaryCompatibility` 本身不应因为这次修复而改成“帮 constructor ranking 特判”

### 7.2 frontend 修好后 backend 继续拒绝

防线：

- Phase C 必须落到 `UnpackVariantInsn`
- 不要停在 sema success

### 7.3 误伤 `as` / `cast` / exact constructor / engine constructor

防线：

- 测试中必须显式保留 negative coverage
- 文档必须反复强调：
  - 这不是 cast 修复
  - 这不是 ordinary implicit conversion 扩面
  - 这不是 engine/gdcc constructor 放宽

---

## 8. 最终建议

推荐按 **A -> B -> C -> D** 顺序实施，先锁行为，再改 sema，再改 lowering，最后补 backend/文档回归。  
Phase E 的 warning parity 可以后置，不应阻塞本轮功能修复。

最重要的工程取舍是：

1. **不要**继续让 stable `Variant` builtin constructor 参与 generic overload ranking。
2. **不要**把 backend `construct_builtin` 放宽成接受伪造的 `[Variant]` constructor 元数据。
3. **要**把这条 special route 在 lowering 中显式落为 `UnpackVariantInsn`，复用现有 `godot_new_*_with_Variant` runtime conversion surface。
