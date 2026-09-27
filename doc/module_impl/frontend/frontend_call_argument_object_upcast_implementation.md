# Frontend 调用参数对象上转型物化实现说明

> Updated: 2026-09-27
>
> 本文档是 fixed call argument 边界上"严格 object 子类 -> 祖先"实参物化例外（target-typed temp + `AssignInsn`）的长期事实源：记录该形态的存在理由、唯一实现点、生效与排除范围、ownership 合同与回归锚点。
> 本文档替代已归档的实施计划（`frontend_call_argument_object_upcast_plan.md`），不保留分步骤实施、阶段状态、验收清单或评审记录；当前合同以本文与所引事实源为准。

- 适用范围：
  - `doc/module_impl/frontend/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/test/java/gd/script/gdcc/frontend/lowering/**`
  - `src/test/java/gd/script/gdcc/backend/c/**`
- 关联文档：
  - `frontend_implicit_conversion_matrix.md`（typed-boundary 兼容性唯一真源）
  - `frontend_lowering_(un)pack_implementation.md`（boundary materialization 单一入口合同，§4.2/§4.3 登记本例外）
  - `frontend_rules.md`（`cfg_boundary_<use>_<op>_<n>` 命名合同）
  - `doc/module_impl/backend/builtin_builder_implementation.md`（后端构造器 exact-match 合同）
  - `doc/module_impl/backend/object_value_fat_pointer_implementation.md`（upcast 表示转换合同）
  - `doc/gdcc_ownership_lifecycle_spec.md`（§3.6 boundary temp 生命周期条款）
  - `frontend_signal_support.md`（Callable/Signal 不保活接收者条款）
  - `doc/gdcc_low_ir.md`（`construct_callable` / `assign` 合同）

## 1. 背景与成因

`Callable(token, &"bump")`（`token` 为自定义 `class Token extends RefCounted`）这类显式构造曾无法通过 C 后端 codegen，成因链路：

1. 前端按 builtin type-meta 构造解析，元数据确有 `Callable(Object, StringName)`（`extension_api_451.json` constructor index 2；`Signal(Object, StringName)` 同形）。
2. 参数边界 `Token -> Object` 经 `ClassRegistry.checkAssignable` 判为可赋值，`FrontendVariantBoundaryCompatibility` 返回 `ALLOW_DIRECT`；`materializeFrontendBoundaryValue` 的 `ALLOW_DIRECT` 分支原样复用**子类类型槽**。
3. `ConstructBuiltinInsn` 实参静态类型因此是子类名（如 `RuntimeCallableGapProbe__sub__Token`）。
4. 后端 `CBuiltinBuilder.constructRegularBuiltin(...)` 对构造器实参做**严格类型名相等**匹配：子类名 != `Object`，抛出 `Builtin constructor validation failed: ... is not defined in ExtensionBuiltinClass`。

根因：前端"继承可赋值"与后端 builtin 构造器"精确名匹配"之间缺少一次纯表示 upcast 物化。按 `builtin_builder_implementation.md` 的职责划分——需要 widening 时由上游 lowering 显式物化——修复落点在前端 lowering，后端匹配器保持 exact 不变。

范围说明：精确匹配只存在于 builtin **构造器**路径。builtin/engine **方法**调用的后端实参处理本已支持子类实参（`CallMethodInsnGen` 的 `checkAssignable` + `valueOfCastedVar`）；方法调用共享本物化形态只是为了让 fixed call argument 边界保持同一实参类型不变量，不是为了修复方法调用。

## 2. 当前合同

### 2.1 物化形态与唯一实现点

唯一实现点：`FrontendBodyLoweringSession.materializeCallArgumentBoundaryValue(...)`，由 `materializeCallArguments` 的 fixed-parameter 循环统一调用（construct / method / callable / super / static 各 route 共享）。

- 严格 object 子类 -> 祖先（双边 `GdObjectType`、类型名不同、`classRegistry.checkAssignable(source, target)` 成立）时：分配 `cfg_boundary_call_fixed_<index>_upcast_<n>` 目标类型 temp，`ensureVariable` 声明，追加 `AssignInsn(temp, sourceSlot)`，实参消费该 temp。
- 其余全部情形委托共享入口 `materializeFrontendBoundaryValue(...)`，decision 与物化行为不变。

形态复刻显式 cast 的 `OBJECT_UPCAST` 分支（同为 `AssignInsn`），不引入新 LIR 指令或 `Decision` 枚举值。

### 2.2 生效范围与排除路径

- 只发生在 fixed-parameter 循环；vararg 尾段目标恒为 `Variant`（走既有 pack 路径），`DYNAMIC` 调用按合同原样透传实参槽，二者均不触发本形态。
- 两条**绕过调用参数物化**的既有路径不受本形态影响，维护时不得"顺手"改入：
  - builtin 单 `Variant` 实参构造特判（`FrontendSequenceItemInsnLoweringProcessors`）：发射 `UnpackVariantInsn`，不经过 `materializeCallArguments`。
  - `materializeBuiltinConstructorBoundary(...)`：`String <-> StringName` 专用的既有 boundary 构造路径，实参类型恒为字符串家族；若未来某条 object 边界被标为 `ALLOW_WITH_BUILTIN_CONSTRUCTOR`，须另行立项处理。

### 2.3 LIR / C 形态（以 `Callable(token, &"bump")` 为例）

```text
$cfg_boundary_call_fixed_0_upcast_0: Object = assign $token
$cb = construct_builtin Callable [$cfg_boundary_call_fixed_0_upcast_0: Object, $sn: StringName]
```

生成 C（全部复用现有机制）：

```c
gdcc_Object_fat_ptr $cfg_boundary_call_fixed_0_upcast_0;
// __prepare__ 先行 null 初始化，随后被 assign 覆写
$cfg_boundary_call_fixed_0_upcast_0 = (gdcc_Object_fat_ptr){ 0 };
...
// upcast helper：ownership-neutral，保留 instance_id，仅转换 ptr 表示
$cfg_boundary_call_fixed_0_upcast_0 =
        gdcc_RuntimeCallableGapProbe_sub_Token_fat_ptr_upcast_to_Object($token);
// 槽写入规则：借用 RHS 写入 owning 槽 -> own；精确 Object 的 RefCountedStatus 为 UNKNOWN -> 双参数 try_ 变体
try_own_object(gdcc_Object_fat_ptr_live_object($cfg_boundary_call_fixed_0_upcast_0),
        $cfg_boundary_call_fixed_0_upcast_0.instance_id);
// Object 实参经 live_object 取裸指针；StringName 实参为槽地址
$cb = godot_new_Callable_with_Object_StringName(
        gdcc_Object_fat_ptr_live_object($cfg_boundary_call_fixed_0_upcast_0),
        &$sn);
// __finally__: managed local 自动清理，与 own 一对一平衡
try_release_object(gdcc_Object_fat_ptr_live_object($cfg_boundary_call_fixed_0_upcast_0),
        $cfg_boundary_call_fixed_0_upcast_0.instance_id);
```

注意：`GD_STATIC_SN(...)` 只出现在 `construct_callable` 方法引用路径；builtin 构造器的 `StringName` 实参是槽地址，二者不得混淆。

### 2.4 Ownership 与生命周期

- temp 按标准槽写入规则在物化写入处 own、由函数 `__finally__` managed-locals 自动清理释放，own/release 一对一平衡，无泄漏、无 double-free。
- 持有期覆盖整个函数而非单次调用（函数作用域持有是既定设计，见 `doc/gdcc_ownership_lifecycle_spec.md` §3.6 boundary temp 条款）：实参求值 temp 在所有调用路径上本就同粒度持有，未证实可观察的 Godot 分歧；如需调用粒度释放须另行修订该生命周期合同。
- 目标类型三态：精确 `Object` -> UNKNOWN（`try_own_object` / `try_release_object` 双参数变体）；非 RefCounted 祖先（如 `Node`）-> NO（不发射 own/release，temp 实为借用别名）；RefCounted 具名祖先（如 `Resource`）-> YES（`own_object` / `release_object`）。
- upcast helper 保留 `instance_id`，构造出的 Callable 记录的 ObjectID 与源对象 `get_instance_id()` 一致。

### 2.5 Callable / Signal 不保活与 Godot 一致性

`Signal` / `Callable` 只保存非 owning ObjectID，不保活 receiver（合同见 `frontend_signal_support.md` §3）。`token.bump` 方法引用 sugar 跨作用域失效与官方 GDScript 行为一致，属既定语义，不得"修复"成 retain。

## 3. 边界情况清单

| 场景 | 行为 |
| --- | --- |
| source 与 target 同名（如 `Object -> Object`） | 复用源槽，无 upcast temp |
| target 为 `Variant`（vararg 或 fixed Variant 参数） | 走既有 pack 路径 |
| source 为 `Variant`、target 为对象类型 | 走既有 unpack 路径 |
| null 字面量 -> 对象参数 | 走既有 `ALLOW_WITH_LITERAL_NULL` 路径 |
| `String -> StringName` 等其他 decision | 走既有路径，零变化 |
| `DYNAMIC` 调用 | 绕过签名边界，实参槽原样透传 |
| engine/builtin 方法调用（如 `add_child(sprite)`） | 同样物化 upcast temp；后端 `checkAssignable` + `valueOfCastedVar` 退化为同型直传，功能等价 |
| lambda capture / 参数 / merge 值作为实参 | 同一 helper 处理，ownership 由标准槽写入规则承接 |
| 目标为 GDCC 自定义祖先类 | 后端 upcast helper 经 `_super` 链转换 ptr 表示，`instance_id` 保留 |

## 4. 维护约束

- 后端 builtin 构造器 exact-match 合同不得放宽；widening 一律由上游 lowering 显式物化。
- 不得为本形态新增 LIR 指令或 `FrontendVariantBoundaryCompatibility.Decision` 枚举值。
- `CallArgumentBoundaryPlan` 目前只携带 `fixedParameterTypes` + `isVararg`（不发布冻结 `Decision`），helper 因此在 lowering 时重新推导判定；一旦 call-argument plan 开始发布冻结 `Decision`，该 helper 必须改消费冻结 decision，不得在 lowering 重复查询矩阵。
- 修改本形态时须同步更新：`frontend_implicit_conversion_matrix.md` 全局规则行备注与 §9.1、`frontend_lowering_(un)pack_implementation.md` §4.2/§4.3、`frontend_rules.md` 命名条款。

## 5. 回归锚点

- 前端 LIR 形态：`FrontendLoweringBodyInsnPassTest.runMaterializesObjectSubclassCallArgumentsThroughUpcastTemp`（自定义 `Token`、engine `Sprite2D -> Node` 方法、GDCC 自定义祖先 `SpecialToken -> Token` 三条正例）、`runKeepsExactObjectCallArgumentsOnDirectSlots`（同类型零开销负例）、`runKeepsNonObjectCallArgumentBoundariesOnExistingPaths`（`String -> StringName` / `int -> float` / object->`Variant` / `Variant`->Object / null->Object 回归抽样）。
- 后端手写 LIR 锚点：`CConstructInsnGenTest.constructBuiltinShouldEmitCallableFromExactObjectArgument`（精确 `Object` 实参发射）、`constructBuiltinShouldKeepRejectingSubclassTypedCallableArgument`（子类实参仍被拒，exact-match 合同不变）。
- lowering -> codegen 链路：`CallArgumentObjectUpcastCodegenTest.fixedCallArgumentUpcastTempOwnershipMatchesTargetRefCountedStatus`（三态 ownership + `Signal` 同入口，按函数体切片断言）。
- sugar 非保活锚点：`CConstructInsnGenTest.constructCallableShouldEmitCallableFromReceiverAndDestroyResult`（构造体无 retain）、`FrontendLoweringBodyInsnPassTest.runLowersBareAndReceiverMethodReferencesIntoConstructCallableInsn`（`construct_callable` 形态）。
