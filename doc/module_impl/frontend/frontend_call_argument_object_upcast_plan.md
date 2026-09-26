# Frontend 调用参数对象上转型物化实施计划

> 本文档记录 `Callable(customInstance, &"method")` 等 builtin 构造调用在实参为 object 子类时被 C 后端拒绝这一缺陷的修复计划。修复手段是在**调用参数边界**物化纯表示的对象 upcast（目标类型临时槽 + `assign`），保持后端 builtin 构造器精确匹配合同不变。

## 文档状态

- 状态：计划待实施（成因链路已闭环，最小复现测试已存在；方案与范围已拍板，评审修订第 1 轮完成，尚未改代码）
- 更新时间：2026-09-27
- 适用范围：
  - `doc/module_impl/frontend/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/test/java/gd/script/gdcc/frontend/lowering/**`
  - `src/test/java/gd/script/gdcc/backend/c/**`
- 关联文档：
  - `frontend_implicit_conversion_matrix.md`（typed-boundary 兼容性唯一真源，须先改）
  - `frontend_lowering_(un)pack_implementation.md`（boundary materialization 单一入口合同，须同步改）
  - `frontend_rules.md`（slot 命名合同，须同步补一条）
  - `doc/module_impl/backend/builtin_builder_implementation.md`（后端 exact-match 合同，本次不修改）
  - `doc/module_impl/backend/object_value_fat_pointer_implementation.md`（upcast 表示转换合同）
  - `doc/gdcc_ownership_lifecycle_spec.md`（槽写入 / managed local 清理规则）
  - `frontend_signal_support.md`（Callable 不保活接收者条款）
  - `doc/gdcc_low_ir.md`（`construct_callable` / `assign` 合同）

---

## 1. 背景与成因链路

最小复现为 `src/test/java/gd/script/gdcc/backend/c/build/CustomReceiverCallableGapTest.java`，覆盖两种写法。

### 1.1 显式构造 `Callable(token, &"bump")` —— 本计划要修的缺陷

1. 前端按 builtin type-meta 构造解析，元数据确有 `Callable(Object, StringName)`（`extension_api_451.json` constructor index 2）。
2. 参数边界 `Token -> Object` 经 `ClassRegistry.checkAssignable` 判为可赋值，`FrontendVariantBoundaryCompatibility` 返回 `ALLOW_DIRECT`；lowering 在 `materializeFrontendBoundaryValue` 的 `ALLOW_DIRECT` 分支原样复用**子类类型槽**（`FrontendBodyLoweringSession.java` `case ALLOW_DIRECT -> sourceSlot`）。
3. `materializeCallArguments` 把该槽写入 `ConstructBuiltinInsn`，实参静态类型为 `RuntimeCallableGapProbe__sub__Token`。
4. 后端 `CBuiltinBuilder.constructRegularBuiltin(...)` 先以 `hasConstructor()` 将两侧渲染为 GD 类型名后做**严格字符串相等**：`RuntimeCallableGapProbe__sub__Token != "Object"`；未命中且无 helper-shim 后在 `:404-406` 抛出 `Builtin constructor validation failed: 'Callable' with args [RuntimeCallableGapProbe__sub__Token, StringName] is not defined in ExtensionBuiltinClass`。

根因：前端"继承可赋值"与后端 builtin 构造器"精确名匹配"两个边界规则之间缺少一次纯表示 upcast 物化。职责划分上，`builtin_builder_implementation.md` 明确"需要 widening 时由上游 lowering 或 intrinsic 显式物化"，因此修复点在前端 lowering，后端匹配器保持 exact。

注意范围：该精确匹配只存在于 builtin **构造器**路径。builtin/engine **方法**调用的后端实参处理已是 `checkAssignable` 校验 + 子类实参自动 upcast（`CallMethodInsnGen.java:518-533`，`valueOfCastedVar`），不存在同类失败；本计划对方法调用参数统一物化形态只是为了调用参数边界的不变量一致，不是为修复方法调用。

### 1.2 方法引用 `token.bump` 跨作用域失效 —— 已定性为 Godot 一致行为（B1），不在本计划修复范围

复现结论：同作用域调用成功（构造路径完全正确），跨作用域失败（`fire()` 返回 -1）。生成的 C 证实 `arm()` 返回时三个 RefCounted 槽按 managed-locals 规则全部 release，Token 归零析构；而 Godot 标准 `Callable` 内存布局只有 `ObjectID + StringName`，结构上不可能 retain 接收者。官方 GDScript 写同样代码行为一致。规范条款见 `frontend_signal_support.md`（Callable/Signal 只保存非 owning ObjectID）与 `doc/gdcc_low_ir.md` `construct_callable`（不保活接收者）。

已拍板（方向 B1）：保持 Godot 语义，把 sugar 用例从"gap"重新定性为 Godot 一致性验收，仅更新测试表述与补充文档说明，不改运行行为。

---

## 2. 目标与非目标

### 2.1 目标

- `Callable(customInstance, &"method")`、`Signal(customInstance, &"signal")` 及其他"声明参数为具名 object 祖先类型、实参为其子类"的 builtin **构造器**调用（元数据 `Callable` / `Signal` constructor index 2 均为 `(Object, StringName)`）能通过前端 lowering 与 C 后端 codegen，运行时行为与 Godot 一致。
- 后端 `CBuiltinBuilder` exact-match 合同与 LIR 指令集零改动。
- sugar 用例按 B1 重新定性为一致性验收。

### 2.2 非目标

- 不改变赋值、返回、property store、merge 写入等非调用参数边界的 `ALLOW_DIRECT` 物化行为（这些消费者已有 C 级 upcast，见 `CBodyBuilder.convertObjectValueIfNeeded`，不依赖精确类型）。
- 不改变 Callable 不保活接收者的语义；不引入 `CallableCustom` retain 机制。
- 不引入新的 LIR 指令、新的 boundary `Decision` 枚举值或后端 assignability 匹配。
- 以下两条**绕过调用参数物化**的已知路径为非目标，实现时不得"顺手"修改：
  - builtin 单 `Variant` 实参构造特判（`FrontendSequenceItemInsnLoweringProcessors.java:946-970`）：发射 `UnpackVariantInsn`，不经过 `materializeCallArguments`，也不产生 `ConstructBuiltinInsn`；`Variant` 内装子类再构造的同类失败不在本计划范围。
  - `materializeBuiltinConstructorBoundary(...)`（`FrontendBodyLoweringSession.java:1265-1278`）：产生 `ConstructBuiltinInsn` 但不经过 `materializeCallArguments`，`String <-> StringName` 专用，实参类型恒为字符串家族；若未来某条 object 边界被标为 `ALLOW_WITH_BUILTIN_CONSTRUCTOR`，须另行立项处理。
- engine/builtin 方法调用后端已有可赋值性处理（§1.1），其细节调整不在本计划内。

---

## 3. 已拍板的设计决策

1. **修复手段**：方案 A——前端在调用参数边界物化目标类型 upcast temp（`AssignInsn`），不放宽后端匹配器。
2. **生效范围**：仅调用参数边界（`FrontendBodyLoweringSession.materializeCallArguments` 的 fixed-parameter 循环），不扩散到全部 typed boundary。
3. **问题一（跨作用域失效）**：方向 B1，保持 Godot 语义。
4. **文档顺序**：遵守 `frontend_implicit_conversion_matrix.md` 维护合同与 materialization 单一入口合同，先改文档（矩阵 + `(un)pack` + `frontend_rules.md` 命名条款），再改代码与测试。

---

## 4. 技术设计

### 4.1 改动位置

唯一代码改动点：`FrontendBodyLoweringSession.materializeCallArguments(...)` 的 fixed-parameter 循环（当前对每个实参直接调用 `materializeFrontendBoundaryValue` 非冻结重载）。新增一个调用参数专用的物化入口，vararg 尾段（目标恒为 `Variant`，走 pack）与 `DYNAMIC` 调用（按合同绕过签名边界）保持不变。

### 4.2 新 helper 形态（实现时以最终代码为准）

```java
/// Materializes one fixed call argument. Ordinary typed boundaries reuse the source slot for
/// ALLOW_DIRECT, but a fixed call operand must carry the resolved signature's declared parameter
/// type: the backend builtin CONSTRUCTOR matcher validates metadata by exact type name
/// (CBuiltinBuilder.constructRegularBuiltin). A proven object upcast is therefore materialized as
/// a target-typed temp here instead of reusing the subclass slot. Method calls already tolerate
/// subclass args backend-side (CallMethodInsnGen checkAssignable + valueOfCastedVar); they share
/// this uniform shape without behavior change.
///
/// Note: `CallArgumentBoundaryPlan` currently carries only `fixedParameterTypes` + `isVararg`
/// (no published `Decision`), so this helper re-derives the matrix decision exactly like the
/// existing fixed-parameter loop. Once the plan starts publishing a frozen `Decision`, this
/// helper MUST switch to the frozen-decision overload and must not re-query the matrix.
private @NotNull String materializeCallArgumentBoundaryValue(
        @NotNull LirBasicBlock block,
        @NotNull String sourceSlotId,
        @NotNull GdType sourceType,
        @NotNull GdType targetType,
        @NotNull String boundaryUse
) {
    var decision = FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
            classRegistry, sourceType, targetType
    );
    if (decision == FrontendVariantBoundaryCompatibility.Decision.ALLOW_DIRECT
            && sourceType instanceof GdObjectType
            && targetType instanceof GdObjectType
            && !sourceType.equals(targetType)) {
        var upcastSlotId = nextBoundaryMaterializationSlotId(boundaryUse, "upcast");
        ensureVariable(upcastSlotId, targetType);
        block.appendNonTerminatorInstruction(new AssignInsn(upcastSlotId, sourceSlotId));
        return upcastSlotId;
    }
    return materializeFrontendBoundaryValue(block, sourceSlotId, sourceType, targetType, decision, boundaryUse);
}
```

要点：

- 同类型、非对象类型、`Variant` 目标、`LiteralNull`、pack/unpack/intrinsic/builtin-constructor 等其余 decision 全部走既有路径，零行为变化。
- 可赋值性已由矩阵决策（`ALLOW_DIRECT`）保证，helper 不再重复继承判断。
- 形态复刻显式 cast 的 `OBJECT_UPCAST` 分支（`emitExplicitCast` 中 `new AssignInsn(resultSlotId, sourceSlotId)`），不引入新指令。
- 冻结/非冻结重载无歧义：冻结版多一个 `Decision` 形参；fallback 调用按六参数唯一解析。
- `ensureVariable` 对同 id 同类型幂等；temp id 含单调递增 `boundaryMaterializationCounter`，同一实参值使用两次不会产生槽冲突。

### 4.3 LIR 前后对比（以 `Callable(token, &"bump")` 为例）

```text
# 修复前（后端拒绝）
$cb = construct_builtin Callable [$token: RuntimeCallableGapProbe__sub__Token, $sn: StringName]

# 修复后
$cfg_boundary_call_fixed_0_upcast_0: Object = assign $token
$cb = construct_builtin Callable [$cfg_boundary_call_fixed_0_upcast_0: Object, $sn: StringName]
```

### 4.4 生成 C 形态（全部复用现有机制）

```c
gdcc_Object_fat_ptr $cfg_boundary_call_fixed_0_upcast_0;
// __prepare__ 先行 null 初始化（CCodegen: object 槽 LiteralNullInsn），随后被 assign 覆写；
// 槽写入对捕获到的旧 null 做一次 try_release_object(NULL, 0)，为 no-op
$cfg_boundary_call_fixed_0_upcast_0 = (gdcc_Object_fat_ptr){ 0 };
...
// upcast helper：ownership-neutral，保留 instance_id，仅转换 ptr 表示
gdcc_Object_fat_ptr __gdcc_tmp_old_obj_N = $cfg_boundary_call_fixed_0_upcast_0;
$cfg_boundary_call_fixed_0_upcast_0 =
        gdcc_RuntimeCallableGapProbe_sub_Token_fat_ptr_upcast_to_Object($token);
// 槽写入规则：借用 RHS 写入 owning 槽 -> own；精确 Object 的 RefCountedStatus 为 UNKNOWN -> 双参数 try_ 变体
try_own_object(gdcc_Object_fat_ptr_live_object($cfg_boundary_call_fixed_0_upcast_0),
        $cfg_boundary_call_fixed_0_upcast_0.instance_id);
try_release_object(gdcc_Object_fat_ptr_live_object(__gdcc_tmp_old_obj_N), __gdcc_tmp_old_obj_N.instance_id);
// construct_builtin 实参必须是变量操作数（ConstructInsnGen.resolveConstructorArguments）：
// Object 实参经 live_object 取裸指针；StringName 字面量由 LiteralStringNameInsn 先物化到槽、按地址传参
godot_Callable __gdcc_tmp_callable_N = godot_new_Callable_with_Object_StringName(
        gdcc_Object_fat_ptr_live_object($cfg_boundary_call_fixed_0_upcast_0),
        &$sn);
// ... __finally__: managed local 自动清理（UNKNOWN -> 双参数 try_release），与 own 一对一平衡
try_release_object(gdcc_Object_fat_ptr_live_object($cfg_boundary_call_fixed_0_upcast_0),
        $cfg_boundary_call_fixed_0_upcast_0.instance_id);
```

涉及现有机制：`CBodyBuilder` assign 路径的 `convertObjectValueIfNeeded`（`checkAssignable` + 生成 upcast helper）、模板生成的 `*_upcast_to_*` helper（保留 `instance_id`）、`ownOrTryOwn` / `releaseOrTryRelease`（`try_*` 均为 `(live_ptr, fat_ptr.instance_id)` 双参数，`gdcc_helper.h`）、managed-local finally 清理（`CCodegen`）。注意 `GD_STATIC_SN(...)` 只出现在 `construct_callable` 方法引用路径，builtin 构造器的 `StringName` 实参是槽地址，二者不得混淆。

### 4.5 Ownership 平衡论证

- 临时槽按普通 `ensureVariable` 声明，走标准槽写入规则：`__prepare__` 先 null 初始化；assign 覆写时 own 新值一次、release 旧值（null，no-op）一次；函数出口按 managed-locals 规则再 release/try_release 一次。真正对象的 own/release 一对一平衡，无泄漏、无 double-free。
- 目标为非 RefCounted 祖先（如 `Node`）时：RefCountedStatus `NO`，own/release 均不发射，temp 实为借用别名；实参先求值、随后在同一直线代码段内完成物化与调用发射，源槽存活覆盖整个区间，与现状（直接传源槽）安全性等价。
- 目标为 RefCounted 派生具名祖先（如 `Resource`）时：status `YES`，`own_object` / `release_object` 标准配对。
- 目标为精确 `Object` 时：status `UNKNOWN`，`try_own_object` / `try_release_object` 双参数变体，运行时按 ObjectID reference bit 判断。
- upcast helper 保留 `instance_id`，构造出的 Callable 记录的 ObjectID 与源对象 `get_instance_id()` 完全一致，`get_object()` / 信号连接比较语义与 Godot 原生逐比特一致。

---

## 5. 分步骤实施与验收细则

每一步都保持可编译、可回归、可单独提交。测试命令统一使用 `script/run-gradle-targeted-tests.sh`。

### 步骤 0：文档先行（单独提交，三处同改）

1. `frontend_implicit_conversion_matrix.md`
   - 全局规则"任意 object subclass -> object superclass"行备注：兼容性维持 Y，物化形态加引用说明"fixed call argument 边界的物化形态例外见 `frontend_lowering_(un)pack_implementation.md` §4.2/§4.3"。
   - §9.1 `materializeFrontendBoundaryValue` 锚点处补一句：fixed call argument 的 object upcast 例外与该单一入口同层登记，不视为 consumer 私设局部分支。
   - 更新"文档状态"的更新时间与本计划文档链接。
2. `frontend_lowering_(un)pack_implementation.md`
   - §4.2 "helper 的当前合同"：direct 默认仍"直接返回原 slot id"；补唯一例外——fixed call argument 边界上严格 object 子类 -> 祖先，物化为 target-typed temp + `AssignInsn`。
   - §4.3 "call boundary 合同"：写明该例外只发生在 `materializeCallArguments` 的 fixed 循环；vararg 与 `DYNAMIC` 不变。
3. `frontend_rules.md`
   - slot 命名条款（`:98`）补一条：boundary materialization temp（pack/unpack/null/intrinsic/builtin-constructor/call-arg upcast）使用 `cfg_boundary_<use>_<op>_<n>`；它们不是 CFG value id，不适用 `cfg_tmp_<valueId>` 规则。

验收：文档 diff 只涉及上述说明；无代码改动。

### 步骤 1：前端 helper 实现

改动：`FrontendBodyLoweringSession.java`

- 新增 `materializeCallArgumentBoundaryValue(...)`（§4.2 形态，含注释中的 frozen-decision 后续约束）。
- `materializeCallArguments` fixed-parameter 循环改调新 helper；vararg 循环与 `DYNAMIC` 分支不变。

验收：`./gradlew classes --no-daemon --info --console=plain` 编译通过。

### 步骤 2：前端 lowering 单测

改动：`FrontendLoweringBodyInsnPassTest`（或同包合适的测试类，实现时确认）。用例必须构造真实的自定义类 hierarchy（如 `Token extends RefCounted`），不能只喂两个孤立 `GdType`。

新增用例：

- `Callable(token, &"bump")`（自定义子类实参）→ 断言三点：block 内出现 `AssignInsn(upcastTemp, tokenSlot)`；`ConstructBuiltinInsn` 第一个实参为该 temp；该 temp 经 `ensureVariable` 声明的类型为精确 `Object`。
- 同类型实参（变量声明类型即 `Object`）→ 断言**不**出现 upcast temp，`ConstructBuiltinInsn` 直接引用源槽（零开销路径回归锚点）。
- 非对象 boundary 回归抽样：`String -> StringName` 仍走 `ALLOW_WITH_BUILTIN_CONSTRUCTOR`，`int -> float` 仍走 intrinsic cast（防止 helper 改动污染其他 decision）。

验收：`script/run-gradle-targeted-tests.sh --tests 'FrontendLoweringBodyInsnPassTest'` 全绿。

### 步骤 3：后端 codegen 单测（只加测试，不改后端代码）

改动：`CConstructInsnGenTest`，以及一个"前端实际 lowering 产物 -> codegen"的链路测试（可挂在现有 lowering-codegen 混合测试类上，实现时确认位置）。

- 后端单元锚点（手工 LIR，不经过前端 helper，措辞不得暗示端到端）：`ConstructBuiltinInsn(Callable, [Object fat-ptr arg, StringName slot])` → 断言发射 `godot_new_Callable_with_Object_StringName(gdcc_Object_fat_ptr_live_object(...), &$sn)`，Object 实参为 live raw pointer、StringName 实参为槽地址；字面量槽初始化单独断言。自定义类 fat-ptr 需把多个 class 放进 module（参照文件内现有多 class 用例）。
- 所有权三态锚点（对前端实际 lowering 所得 LIR 跑 codegen）：
  - 子类 -> 精确 `Object`（UNKNOWN）：槽写入处出现**双参数** `try_own_object(live, slot.instance_id)`，`__finally__` 出现配对 `try_release_object(live, slot.instance_id)`。
  - 子类 -> `Node`（NO）：upcast 槽周围不出现 own/release。
  - 子类 -> `Resource`（YES）：出现精确 `own_object` / `release_object` 配对。
  - 另选一条普通方法调用（子类实参）覆盖同一 fixed 参数入口，确认方法路径功能等价（后端原有 `valueOfCastedVar` upcast 退化为同型直传）。

验收：`script/run-gradle-targeted-tests.sh --tests 'CConstructInsnGenTest'` 及所加链路测试类全绿。

### 步骤 4：翻转 `CustomReceiverCallableGapTest` 并按 B1 重定性 sugar 用例

改动：`CustomReceiverCallableGapTest.java`、`frontend_signal_support.md`

- `explicitCallableConstructionFromCustomInstanceFailsCodegen` 翻转为成功路径，**保持 `fakeCompiler()` 结构**（该路径本就不依赖 Zig/Godot）：断言 `buildProject` 不再抛出、且不再出现 `ExtensionBuiltinClass` 失败链；测试改名（如 `explicitCallableConstructionFromCustomInstancePassesCodegen`）。
- 运行时段（`probe_immediate == 1`、`fire() == -1`）如需覆盖显式构造，复用 sugar 用例的门闩结构（Zig 缺失时 `Assumptions.abort`，Godot 缺失由 `runner.run(true)` 内部处理），不得合成"无 Zig 则只断言 codegen"的新模式。
- `methodReferenceOnCustomInstanceDoesNotRetainReceiver` 重命名并改写注释：从"gap 待修"改为"Godot 一致性验收"；断言（同作用域通过、跨作用域 `result=-1`）保持不变。
- `frontend_signal_support.md` 在不保活条款处补一句实证说明（复现测试名 + 结论），不改动合同本身。

验收：`script/run-gradle-targeted-tests.sh --tests 'CustomReceiverCallableGapTest'` 全绿。

### 步骤 5：受影响面回归

- 先以文本搜索定位可能受精确 LIR 序列断言影响的测试（搜索 `ConstructBuiltinInsn`、`construct_builtin`、call 实参槽同一性断言、`materializeCallArguments` 相关断言），逐一运行核对；不要默认某个测试类会变红。
- 若 characterization 测试因调用参数新增 upcast temp 而失败：逐一核对失败点是否确为预期形态变化，禁止为通过测试而回退实现；确属预期的按新形态更新测试并在提交说明中列出清单。
- 最后 `./gradlew clean build --no-daemon --info --console=plain` 全量验证。

验收：上述命令全部通过；失败项均有明确归因（预期形态变化 or 真实回归），真实回归必须修复。

---

## 6. 边界情况清单（实现与评审时逐条核对）

| 场景 | 预期行为 |
| --- | --- |
| source 与 target 同名（如 `Object -> Object`） | 复用源槽，无 upcast temp |
| target 为 `Variant`（vararg 或 fixed Variant 参数） | 走既有 pack 路径，不触发 upcast 分支 |
| source 为 `Variant`、target 为对象类型 | 走既有 unpack 路径 |
| null 字面量 -> 对象参数 | 走既有 `ALLOW_WITH_LITERAL_NULL` 路径 |
| `String -> StringName` 等其他 decision | 走既有路径，零变化 |
| `DYNAMIC` 调用 | 按合同绕过签名边界，实参槽原样透传 |
| engine/builtin 方法调用（如 `add_child(sprite)`） | 同样物化 upcast temp（范围内统一形态）；后端原有 `checkAssignable` + `valueOfCastedVar` 退化为同型直传，功能等价，多一条 `assign` + own 对 |
| lambda capture / 参数 / merge 值作为实参 | 同一 helper 处理，ownership 由标准槽写入规则承接 |
| 目标为 GDCC 自定义祖先类 | 后端 upcast helper 经 `_super` 链转换 ptr 表示，`instance_id` 保留 |
| 单 `Variant` 实参构造 / `String <-> StringName` 边界构造 | 不经过本 helper，保持现状（已知非目标，§2.2） |

---

## 7. 风险与回滚

- R1（中）：characterization 测试因调用参数新增 temp 出现精确序列失败 → 预期内，按步骤 5 的搜索驱动流程核对更新。
- R2（低）：upcast temp ownership 不平衡 → 由 §4.5 论证 + 步骤 3 三态锚点 + 步骤 4 运行时测试三重兜底；`AssignInsn` 复用现有槽写入语义，无新机制。
- R3（低）：helper 误伤其他 decision 路径 → 步骤 2 的回归抽样锚定。
- R4（低）：`boundaryUse` 命名冲突 → temp 名含 `boundaryUse` 与自增计数，与现有 pack/unpack temp 同机制；命名合同在步骤 0 补齐。
- 回滚：代码改动集中于单个 helper 与一处调用点，直接 revert 对应提交即可；文档改动随提交一并 revert。

---

## 8. 总体验收清单（DoD）

- [ ] 三处文档（矩阵、`(un)pack`、`frontend_rules.md` 命名条款）已先于代码更新
- [ ] `Callable(token, &"bump")`、`Signal(token, &"s")` 等子类实参 builtin 构造调用全链路通过
- [ ] 后端 `CBuiltinBuilder`、LIR 指令集、`Decision` 枚举零改动
- [ ] 同类型 / 非对象 / `Variant` 目标的既有路径零行为变化（有测试锚点）
- [ ] ownership 三态（UNKNOWN/NO/YES）均有 codegen 锚点
- [ ] sugar 用例按 B1 重新定性，`frontend_signal_support.md` 补充实证说明
- [ ] 步骤 0-5 的验收命令全部通过
