# `@onready` 类实现说明

> 本文档是 `@onready` ready-time 执行模型在 GDCC 编译链中的长期事实源。
> 它只记录当前代码库已落地的合同、边界与工程结论，不保留实施步骤、完成进度或验收流水账。
> 本文档由 `onready_lowering_implementation.md` 与 `onready_class_lookup_design.md` 合并归档而来。

## 文档状态

- 状态：Implemented / Maintained
- 更新时间：2026-10-24
- 适用范围：
  - `src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/COnreadyPlan.java`
  - `src/main/java/gd/script/gdcc/lir/LirPropertyDef.java`（`initializerSynthesizedByBackend` provenance 标记）
  - `src/main/c/codegen/template_451/entry.c.ftl`
  - `src/main/c/codegen/template_451/entry.h.ftl`
  - `src/main/c/codegen/template_451/onready_lookup.ftl`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendAnnotationUsageAnalyzer.java`
  - `src/main/java/gd/script/gdcc/frontend/sema/FrontendSyntheticPropertyHelperSupport.java`
- 关联文档：
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`（§5 `@onready` usage contract）
  - `doc/module_impl/frontend/frontend_property_init_lowering_implementation.md`（apply 双入口合同）
  - `doc/module_impl/frontend/frontend_node_literal_implementation.md`（`$`/`%` DEFERRED 边界）
  - `doc/module_impl/frontend/frontend_engine_virtual_override_implementation.md`
  - `doc/module_impl/backend/cbodybuilder_implementation.md`
  - `doc/module_impl/backend/virtual_override_vtable_implementation.md`（`_ready` 虚拟分发接入）
  - `doc/module_impl/backend/hot_reload_implementation.md`（recreate / init_fields 路径）
  - `doc/module_impl/backend/explicit_c_inheritance_layout_contract.md`（wrapper 根段布局合同）
  - `doc/gdcc_low_ir.md`、`doc/gdcc_c_backend.md`、`doc/gdcc_ownership_lifecycle_spec.md`

## 1. 语义基线与实现总览

### 1.1 Godot 语义事实源（`godotengine/godot` 源码证据）

- 合法性（`gdscript_parser.cpp` `onready_annotation`）：仅 class 成员 `var`；owner 类必须派生自 `Node`；禁止 `static`；同一变量重复注解报错。
- 分流（`gdscript_compiler.cpp` `@implicit_ready` / `@implicit_new`）：`@implicit_ready` 只按声明顺序赋值本类 onready 且有 initializer 的字段；`@implicit_new` 只处理非 onready 字段；无 initializer 的 onready 字段在 ready 阶段不产生赋值。
- 构造期默认值：typed onready 字段在 ready 前持有类型默认值（对象 `null`、无类型 `nil`），`_init()` 观察到的就是这些默认值。
- 触发点（`GDScriptInstance::callp` + `Node::_notification(NOTIFICATION_READY)`）：`callp("_ready")` 先经 `_call_implicit_ready_recursively` 按基类→派生类执行整条脚本链的 `@implicit_ready`，再执行最派生类用户 `_ready`；无 `_ready` 定义时 `@implicit_ready` 仍执行。
- 顺序承诺：同类内按声明顺序；基类先于派生类；onready 赋值完成后才进入用户 `_ready` 体；`super._ready()` 不重放 onready（super 绕过 `callp`）。
- 重放语义：`callp("_ready")` 无只执行一次守卫；`request_ready()` 后再次入树会重放 onready；仅移除再入树不重放；按名手动调用同样重放。
- 与 `@export` 交互：场景实例化先写 export 值，ready 时 onready 覆盖；Godot 对该组合发 `ONREADY_WITH_EXPORT` 诊断（本实现不含，见 §8）。

### 1.2 现行实现路线

**组合 impl（引擎入口）+ 类级查询表 + `_ready` 入口按需初始化（非引擎入口）**，两条入口共享同一 frozen 计划与同一组链/apply helper：

- 引擎 READY 分发经 `<C>__gdcc_onready_ready` 组合 impl：构造守卫 → 置位 DONE → 链 apply → 转发用户 `_ready`；每次 READY 重放（`request_ready()` 由此获得重放语义）。
- 非引擎路径（内部 vtable/`call_method`、super、外部 `call("_ready")`、coroutine start thunk）在 `_ready` 入口注入根 helper 调用，按**实例实际最派生类型**经类级查询表分发一次完整链（DONE 守卫）。
- 已被否决的机制不得复活：构造期直接初始化、`super._ready()` 裸重放、LIR 层注入、bridge token、实例函数字段/类标签/查询缓存、ptrcall 转发组合 impl、setter route 写字段。

### 1.3 生成符号清单

命名统一由 `CGenHelper` 渲染（`render*` helper），全部文件级符号显式登记进 `CCodegen.validateFileScopeSymbolsDisjoint`：

| 符号 | 形态 | 职责 |
| --- | --- | --- |
| `_onready_field_default_<prop>` | hidden LIR helper | 产出 onready 字段的类型默认值 |
| `<C>_class_apply_property_default_<prop>` | C 函数 | 构造期/recreate first-write 默认值到字段 |
| `<C>_class_apply_onready_init_<prop>` | C 函数 | ready-time overwrite apply（真实 initializer） |
| `<C>_class_apply_onready_inits` | C 函数 | 本类链函数：先递归父链，再按声明序调本类 apply |
| `<C>__gdcc_onready_apply_root` | C 函数 | 根签名适配器 `(<R>* root)`，表行引用 |
| `<C>__gdcc_onready_ready` | C 函数 | 引擎 `_ready` 组合 impl |
| `<C>__gdcc_onready_class_name` | C 函数 | 表行 key getter，返回固定 `GD_STATIC_SN` |
| `gdcc_<R>_onready_lookup_entry` / `_table` | typedef / `static const` 数组 | 每物理根查询表 |
| `<R>__gdcc_onready_initialize_if_needed` | C 函数 | 非引擎入口根初始化 helper（全部判优算法） |
| `__gdcc_onready_root` | 注入局部变量 | `_ready` 入口物化的物理根 wrapper 指针 |

`_onready_field_default_` 是 frontend 保留前缀（`FrontendSyntheticPropertyHelperSupport` 拒绝用户同名成员）；`__gdcc_onready_*` 落在 `_gdcc_` 保留前缀下。跨类/跨段引用的符号（链函数、根适配器、组合 impl、各 apply helper）统一在 `entry.c` 段首发射 `static` 原型，容忍 derived-first 模块输入；无链事实的类不生成链函数/适配器/组合 impl，整个模块无有效 onready 时不生成表、key getter 与根 helper。

## 2. 与 Godot 的顺序对照

| 时刻 | Godot | 本实现 |
| --- | --- | --- |
| 对象创建 | typed 字段填类型默认值；非 onready 字段按声明序初始化 | constructor：非 onready 走现状 apply；onready-with-init 走默认值 apply；constructor 链结束后由 notification 置位 `CTOR_COMPLETE` |
| `_init()` | 观察到 onready 字段默认值 | 一致 |
| 构造期内手动/虚调用 `_ready()`，或 `_init` 内显式 `notification(NOTIFICATION_READY)` | 两阶段构造下字段已有类型默认值，implicit ready 安全执行 | **fail-closed 跳过** onready 赋值（双入口均受 `CTOR_COMPLETE` 守卫；DONE 不置位）。不清引擎 `ready_first`，之后真正入树时引擎仍发 READY 并正常 apply；用户 `_ready` 体仍执行 |
| 构造期内 `add_child` 进已在树上且已 ready 的父节点 | 同上 | 经 `_propagate_ready` 同步发 READY 且先清 `ready_first`——唯一一次自然 READY，守卫跳过 apply 后**不自动补执行**，onready 保持默认值直到 `request_ready()` 再次入树 |
| ready 通知 | 基类→派生类 `@implicit_ready`，再最派生用户 `_ready` | 组合 impl：`CTOR_COMPLETE` 通过 → 置位 DONE → `<C>_class_apply_onready_inits`（不看 DONE）→ 转发用户 `_ready`（其入口按需初始化被 DONE 拦截跳过） |
| 无用户 `_ready` | `@implicit_ready` 仍执行 | 组合 impl 仍执行链函数（省略转发） |
| `super._ready()` | 不重放 onready | super 直调父类 `_ready` 入口，入口按需初始化被 DONE 拦截 |
| 移除再入树（无 `request_ready()`） | 不重放 | 一致（引擎不再分发 `_ready`） |
| `request_ready()` 后再 ready | 重放 onready | READY 实际进入组合 impl 的路径重放 overwrite，旧值被销毁（混合继承 super 路径除外，见 §7） |
| 构造完成后脚本内手动 `node._ready()` | 经 `callp` 重放 onready | 首次经入口按需初始化按实例实际类型执行一次；重复调用被 DONE 拦截 |
| 热重载 recreate | 引擎回放持久化状态 | `class_init_fields` 对 onready-with-init 属性只回放类型默认值；非 onready 维持现状；flags 置 `CTOR_COMPLETE|ONREADY_DONE(+HAS)`，手调不补执行，`request_ready()` 仍重放（限 READY 实际进入组合 impl 的路径） |

## 3. 编译期计划（`COnreadyPlan`）

- 事实源唯一：`propertyDef.getAnnotations().containsKey("onready")`；frontend 侧只做 retention + usage validation（`sema.annotation_usage`），不改写该事实。
- **快照时点**：`CCodegen.prepare(...)` 在 `CGenHelper` 构造之后、`generateDefaultGetterSetterInitialization()` 之前强制构建不可变计划；后者会为无 initializer 的 property 回填 `initFunc`，之后 `initFunc != null` 不再区分「源码 initializer」与「backend 合成默认值」。模板渲染期禁止反读 `initFunc != null` 做语义判断。
- **provenance 标记**：默认 helper 回填时经 `setBackendSynthesizedInitFunc` 置 `LirPropertyDef.initializerSynthesizedByBackend`（transient 非序列化；常规 `setInitFunc` 清除），保证同一 `LirModule` 被第二个 codegen 会话 re-prepare 时事实一致。
- **计划内容**（每类 `ClassPlan`）：有序 onready-with-source-initializer property 列表、链事实、后代事实、链上最派生且经 `checkVirtualMethod` 的用户 `_ready`（owner 名与函数对象同有同无）。`classPlan(未知类名)` 返回共享空计划。
- **`CGenHelper` 判定入口**（`checkXxx` 约定）：`checkOnreadyProperty`（裸注解判定，不细分 static/initializer）；`checkClassChainHasOnreadyInitializer`、`checkClassOrDescendantHasOnreadyInitializer`（读冻结计划）；`resolveChainUserReadyFunctionOrNull`。链/后代遍历带 visited set 防御环，engine/未知父类终止；`_ready` 解析前对 owner 做 registry 祖先环 preflight。
- **`RootLookupPlan`**（`COnreadyPlan` 持有，`CGenHelper` 只查询渲染）：物理根经 `resolveGdccRootClassName(LirClassDef)` 已知对象重载解析——从类对象出发只沿 `registry.isGdccClass(superName)` 为真的已注册 GDCC 父边上行、visited 检环，engine/空/未知父名终止于当前类；起始类不要求已注册（字符串重载对未知起始名的抛错合同不变）；未注册父边截断，物理根可落在注册表中的 module 外祖先（仅描述布局，不构成跨模块支持）。`classNames` 只收当前 module 中物理根相同且链事实为真的类，按 module 原顺序 `List.copyOf` 冻结；含 inherited-only/abstract 中间类，排除 engine 类、coroutine 状态类、其他模块类与链事实为假的类。

## 4. 实例布局与 `_gdcc_flags`

根 wrapper 字段顺序冻结为 `_object`、可选 `_vtable`、`uint8_t _gdcc_flags`，然后才是本类属性；onready 不占用额外表字段，派生类仍以 `_super` 为首成员。三个 onready flag 以**匿名 enum** 常量发射（`entry.h.ftl`）而非 `#define`——宏会文本改写同名用户标识符；enum 常量占普通文件作用域，由 `validateFileScopeSymbolsDisjoint` 登记后 fail-fast，用户成员同名仍合法。

| 位 | 名称 | 定义 |
| --- | --- | --- |
| bit0 / 1 | 析构守卫 | 仍以 `& 1` 读取、`|= 1` 写入，只改 bit0 |
| bit1 / 2 | `GDCC_FLAG_ONREADY_DONE` | 入口初始化**已尝试**或已经由引擎应用；不是成功证明 |
| bit2 / 4 | `GDCC_FLAG_CTOR_COMPLETE` | 完整构造链已返回，或 recreate 视为构造完成 |
| bit3 / 8 | `GDCC_FLAG_HAS_ONREADY` | 实例**实际类**的 frozen `chainHasOnreadyInitializer`（含「本类无、祖先有」；仅无源码 initializer 的注解不算） |

HAS 是类型事实，DONE 是执行事实，不能互相替代或由一次查询失败改写。

| 路径 | flags 写入 |
| --- | --- |
| create | 链事实为真写 `HAS_ONREADY`，否则写 `0`；绑定实例及通知之前完成（`godot_mem_alloc` 不置零） |
| POSTINITIALIZE | 完整 constructor 返回后 `|= CTOR_COMPLETE` |
| recreate | `CTOR_COMPLETE | ONREADY_DONE | (本代链事实 ? HAS_ONREADY : 0)`；不查类名、不执行初始化器、不拷贝旧 flags |
| 入口尝试 / 引擎组合 impl | `|= ONREADY_DONE`；不改其余位 |
| destructor | `|= 1`；不清其他位 |

所有 flags 访问必须经 `CGenHelper` 根段 helper（`renderGdccFlagsAccessExpr` / `renderDestructedFlagAccessExpr`，起始表达式参数化）沿 `_super` 链渲染；模板与派生类禁止硬编码 `self->_gdcc_flags`。同一基类的干净旁支因 HAS=0 直接退出：不查类名、不扫表、不调 key getter。

## 5. 运行期行为合同

### 5.1 构造期分流

`class_constructor` 与 `class_init_fields` 两处 property 循环共用同一判定（`renderPropertyConstructionApplyHelperName`）：

- 非 onready property：维持 first-write apply 真实 initializer 或默认值；
- onready 且无源码 initializer：维持现状（backend 默认 helper 恰为 Godot 构造期默认值语义）；
- onready 且有源码 initializer：改调 `<C>_class_apply_property_default_<prop>` 写类型默认值，真实 initializer 推迟到 ready。

类型默认值表保持单源（`createPropertyDefaultInitializer`）：object→`null`、Variant/无类型→`nil`、bool→`false`、数值→`0`、String/StringName→空、typed 容器保留元素元数据、packed→`ConstructArrayInsn(null)`、compiler-only→fail。

`_onready_field_default_<prop>` 的占用边界：缺失时合成；已有同名占用必须唯一，经共享 signature/body/entry 校验后复用——重名冲突、shell、错型、无效 entry 一律在控制流 graft 前 fail，不修复、不顶替；任何属性的 `initFunc` 不得指向该保留名（self/sibling 别名拒绝）；eligible onready 属性必须持非空且未标记 backend-synthesized 的源码 `initFunc`。

### 5.2 ready-time apply（overwrite）

`<C>_class_apply_onready_init_<prop>(<C>* self)` body 由 `CCodegen.generateOnreadyApplyBody` 生成：调用真实 `_field_init_<prop>(self_fat)` 并经 `CBodyBuilder.moveOwnedCallIntoSlot(...)` destroy-then-move overwrite。不变量：

- target 固定经 `targetOfExpr("self->" + propertyName, propertyType)`；
- 承载 builder 起独立空 builder、保持 position-less——`__prepare__` 会抑制 destroy/release，进入它会吃掉 overwrite 语义；
- 不得退回 `applyPropertyInitializerFirstWrite`：执行时字段必已完成构造期 first-write，first-write 会泄漏可析构默认值。

### 5.3 链函数与根适配器

`<C>_class_apply_onready_inits(<C>* self)`：先递归同模块 GDCC 父类链函数（父链事实为真时），再按声明顺序调本类各 per-property apply。「本类无 initializer 但祖先有」的中间类也必须生成转发函数。链函数只做字段初始化，不转发用户 `_ready`。`<C>__gdcc_onready_apply_root(<R>* root)` 是各链事实类的根签名适配器（offset-0 受控数据指针转换，与 vtable trampoline 同一豁免先例），经表行完成实例类型分发；链函数本体签名 `(<C>* self)` 永远不经表调用。

### 5.4 组合 impl（引擎入口）

链事实为真的类生成 `static void <C>__gdcc_onready_ready(<C>* self)`，函数体固定形态：

```c
if ((<根段 flags 访问> & GDCC_FLAG_CTOR_COMPLETE) == 0) {
    <转发用户 _ready（若有）>;
    return;
}
<根段 flags 访问> |= GDCC_FLAG_ONREADY_DONE;   // 先置位：防重入 + 入口按需初始化去重
<C>_class_apply_onready_inits(self);             // 不看 DONE：保证 request_ready() 重放
<转发用户 _ready（若有）>;
```

- 构造守卫失败的语义：不置 DONE、不 apply，但用户 `_ready` 仍转发；此时派生字段槽尚未 first-write，无条件 overwrite 会销毁未初始化值（UB）。
- 转发规则：声明类符号直调；声明类不同先 `(Decl*)self` offset-0 转换，再 `renderOwnerFatSelfFromWrapperPtr` 渲染 fat self；coroutine `_ready` 必须转发同步 shell（shell 内部自转 start thunk 并负责 `release_object`；直转 thunk 会泄漏 state object）；链上无用户 `_ready` 时省略转发。
- `get_virtual_with_data`：链事实为真时 `_ready` 特判在 own-function 循环**之前**返回 `&<C>__gdcc_onready_ready`——真实函数地址，仍属既有 impl 地址类 userdata，不新增种类。
- `call_virtual_with_data`：own-function 分支**之前**匹配组合 impl 地址，dispatcher 直接调用 `<C>__gdcc_onready_ready((<C>*)p_instance); return;`——**不走 ptrcall**（ptrcall wrapper 把 userdata 当 fat 函数指针调用，与组合 impl 的 wrapper 签名 ABI 不符）。

### 5.5 非引擎 `_ready` 入口按需初始化

对「声明了用户 `_ready`（普通或 coroutine）且 `checkClassOrDescendantHasOnreadyInitializer` 为真」的每个类（含抽象类——其 `_ready` 可经 super 到达），在 `_ready` 入口注入：

```c
{
    <R>* __gdcc_onready_root = (<R>*)$self.ptr;   // offset-0 受控转换物化根 wrapper
    <R>__gdcc_onready_initialize_if_needed(__gdcc_onready_root);
}
```

- 注入点：普通 `_ready` 在 `generateFuncBody` 的 `goto entry` 前（排除 `func.isCoroutine()`）；coroutine `_ready` 在 **start thunk 最前、首次 `mco_resume` 之前**——内部 direct/vtable/super 调用全部直进 start thunk，只注同步 shell 会漏掉全部内部路径。
- 入口侧只物化根指针 + 调用 helper，以 appendRaw 发射，不经 CBodyBuilder slot-write 体系；全部判优算法集中在 `onready_lookup.ftl` 的根 helper 内。
- 注入条件是「本类或**后代**链事实」：`_ready` 可能被有 onready 的后代继承；「无链事实不生成」只约束链函数/适配器/组合 impl/get_virtual/call_virtual 段，不约束用户 `_ready` 函数体。

### 5.6 根 helper 算法（`onready_lookup.ftl`）

`<R>__gdcc_onready_initialize_if_needed(<R>* root)` 对有效、存活的 `<R>*` 严格按序执行：

1. DONE 已置位 → 返回。
2. `CTOR_COMPLETE` 未置位 → 返回，DONE 不置位。
3. `HAS_ONREADY` 未置位 → 返回，flags 不变。
4. 先 `|= ONREADY_DONE`——本次尝试包括动态类型解析；重入与重复手调不再查询。
5. 经 `<R>_object_ptr(root)` 取 raw Godot 对象；NULL 报内部运行期错误并返回，不调查询 API。
6. `godot_object_get_class_name(raw, class_library, &actual_name)` 恰好一次；library-aware 接口，不用 `Object.call("get_class")`、不用 `object_cast_to`/class tag 替代。
7. 查询失败：报内部运行期错误并返回；不销毁未成功发布的输出。
8. 查询成功：线性表内以 `godot_StringName_op_equal_StringName` 逐行精确比较，命中即停。
9. 命中：先销毁 `actual_name` 再 `apply(root)`；只调用一条完整链，不回退祖先。
10. 未命中：销毁 `actual_name`，报「有 onready 的实例在本根表中无精确类名行」内部错误并返回。

查询失败/缺行不清 DONE、不重试，不代表初始化成功；用户 `_ready` 继续执行，后续引擎 READY 仍不看 DONE。`actual_name` 成功路径恰销毁一次；key getter 的 `GD_STATIC_SN` key 归 TU-local StringName registry，借用者不销毁。**禁止在循环内调用 `GD_STATIC_SN(row.class_name)`**——该宏按展开点缓存，循环内不同名字会共享错误缓存点。

### 5.7 查询表结构与查询次数

每非空物理根 `R` 发射 `gdcc_<R>_onready_lookup_entry` typedef（恰两成员：`const godot_StringName* (*class_name)(void)`、`void (*apply)(<R>* root)`）与 `static const gdcc_<R>_onready_lookup_entry gdcc_<R>_onready_lookup_table[]`。key getter `<C>__gdcc_onready_class_name(void)` 体内只返回 `GD_STATIC_SN(u8"<canonical name>")`（每 getter 独立展开点）；类名使用与 `godot_classdb_register_extension_class5` 完全相同的 canonical name，不用源码别名或 C identifier 转义名。`apply` 列直接填 `<C>__gdcc_onready_apply_root` 地址——typed 指针零强转，不存链函数指针、不转函数地址为对象地址。线性查找复杂度 O(本根有效类数)；不新增哈希表、动态注册、每实例缓存、class tag 或 ClassDB 继承遍历。

| 场景 | 类名查询次数 |
| --- | --- |
| 干净 Base，只有某个后代有 onready | 0（HAS=0） |
| 干净旁支继承 Base 的 `_ready` | 0（HAS=0） |
| 注解无源码 initializer、全链无有效 initializer | 0（HAS=0） |
| 仅继承祖先 onready 的派生实例 | 首次合格非引擎入口 1 次，命中派生类自己的行 |
| `_init` 中手调 `_ready` 或显式发 READY | 0（构造未完成；组合 impl 同样跳过 apply） |
| 正常引擎 READY 与 `request_ready()` 重放 | 0（组合 impl 直接调本类链） |
| 第一次引擎 READY 之前手调 `_ready` | 最多 1 次，后续 DONE 跳过 |
| lookup 失败或缺行后再次手调 | 第一次 1 次，后续 0 |
| recreate 后手调或经混合 GDScript super 进入 | 0（DONE 已置位） |

`onready_lookup.ftl` 由 `entry.c.ftl` include，仍属同一 entry TU；位于根适配器段首原型之后、`initialize` 之前。该片段同时供 native 测试 probe 直接渲染、编译并执行——测试必须复用同一份生成逻辑，不得手写相似算法替代。

## 6. 热重载合同

- 表只含本映像 getter/适配器地址；实例、binding、HRX hub、协程帧不保存表行或查询结果，不新增对象级清理。key getter 的固定 `GD_STATIC_SN` 展开点经既有 registry generation 重建，不新增 init-once 门控。
- recreate 按**本代实际类的冻结链事实**重算 HAS，写 `CTOR_COMPLETE | ONREADY_DONE`，不拷贝旧 flags、不查类名、不运行真实 initializer；随后 `class_init_fields` 与引擎 storage setter 恢复流程不变。

| 两代变化 | 重建后普通 `_ready` | `request_ready()` 后重放 |
| --- | --- | --- |
| 有 onready，initializer 改变 | DONE 阻止覆盖恢复值，不查询 | 新组合 impl 跑新 initializer |
| 从无到有 onready | HAS=1、DONE=1，不补执行 | 新组合 impl 应用新链 |
| 从有到无（全链变空） | HAS=0、DONE=1 | 无本类组合入口，仅用户 `_ready` 行为 |
| 去掉本类 onready、祖先仍有 | HAS=1、DONE=1 | 直接应用本代祖先链 |
| 只恢复 storage、不再进入 READY | 不覆盖已恢复 storage | 不自动补发 READY |

## 7. 与 Godot 的已知差异（接受并记录）

- **非引擎分发路径的 `_ready` 重放不重放 onready**：gdcc 内部 vtable/`call_method`、纯 GDCC 无 ScriptInstance 对象上的 `call("_ready")`/Callable 调用，首次经入口按需初始化按实例实际类型执行一次（DONE 守卫），之后不再重放；Godot `callp` 每次都重放。`request_ready()` 重放承诺仅适用于 READY 实际进入组合 impl 的路径。（对象挂有 GDScript 时 `Object.call` 会经 `GDScriptInstance::callp` 重放 GDScript 自身的 `@implicit_ready`，GDCC 侧仍按上述规则。）
- **构造期内的 `_ready` 触发不执行 onready**：(a) 手动/虚调用 `_ready()`、(b) `_init` 内显式 `notification(NOTIFICATION_READY)`——双入口 fail-closed 跳过、DONE 不置位、不清 `ready_first`，之后入树正常 apply；(c) `_init` 内 `add_child` 进已 ready 父节点——唯一一次自然 READY，守卫跳过后不自动补执行，字段保持默认值直到 `request_ready()`。Godot 两阶段构造使这些路径天然安全；gdcc constructor 交错结构使守卫必需。守卫失败路径转发的用户 `_ready` 只允许读已完成 first-write 的祖先槽，读派生字段仍是未初始化访问（构造期触发 `_ready` 的固有风险，与 onready 无关）。
- **混合 GDCC/GDScript 继承**：GDScript 子类覆写 `_ready` 且调 `super._ready()` 时，super 经 MethodBind 进入 GDCC 侧 `_ready` 入口，入口按需初始化在 super 调用点晚执行实例的完整 GDCC 链（顺序与 Godot 纯脚本链不一致，但优于不执行）。该路径无重放保证：脚本 `_ready` 经 `callp` 成功后引擎短路，组合 impl 不执行——重复 READY 与 recreate 后 READY 经 super 入口时 DONE 已置位，GDCC 链不重放。GDScript 子类不调 `super._ready()` 时 GDCC 链 onready 不执行；GDScript 子类只有 onready、无 `_ready` 时顺序为「派生脚本 onready 先于 GDCC 基类 onready」，与 Godot 纯脚本链相反。完整对齐属架构级后续项（§8）。
- **热重载后首调**：recreate 置位 DONE，reload 后手调 `_ready()` 不补执行 onready（保护已恢复 storage 值）；`request_ready()` 仍经组合 impl 按新代码重放（混合 super 路径除外）。
- **`@onready` + `@export`**：行为自然对齐 Godot（export 先写、ready 覆盖），但 Godot 的 `ONREADY_WITH_EXPORT` 诊断未实现，不得声称诊断兼容。

## 8. 边界与后续工作

### 8.1 边界

- 跨模块 GDCC 父类的 onready 链不支持（与 vtable 跨模块限制同级）；父类为 engine 类时链递归终止。
- `$`/`%` node literal 在 property initializer / onready initializer 中的 compile gate 未解除，`@onready var c = $Child` 尚不可编译；onready initializer 可书写的表达式支持面等于当前 property-init 支持面。
- `is_node_ready()` 在 onready initializer 执行期间为 `true`（以引擎为准，gdcc 不做额外承诺）。
- library-aware 类名查询必须取得本库对应的 GDCC wrapper 类名；该行为需真实 Godot 环境验收，不能假设脚本 `class_name`、动态方法返回值或任意祖先名可替代。未知扩展后代与跨模块链 fail-closed，不回退父类假装支持。

### 8.2 后续工作（另行立项，不在本实现范围）

- 混合 GDCC/GDScript 继承的完整 onready 对齐（需统一 engine virtual、ClassDB 按名入口与 ScriptInstance ready 生命周期）。
- 解除 `@onready` initializer 中 `$`/`%` 的 compile gate（需与 `frontend_node_literal_implementation.md` DEFERRED 边界协调）。
- 视需要放宽 onready initializer 对同类实例成员/`self` 的访问限制。
- `@onready` + `@export` 的 `ONREADY_WITH_EXPORT` 诊断对齐。

## 9. 回归测试基线

| 层 | 测试 | 覆盖 |
| --- | --- | --- |
| 计划/命名 | `CGenHelperTest` | 冻结成员资格、链/后代事实、环防御、`_ready` owner 解析、derived-first、命名渲染、入口注入条件 |
| 计划/序列化 | `CCodegenOnreadyTest`、`DomLirSerializerTest` | 快照时点、provenance 标记、XML roundtrip、双 codegen 会话一致性 |
| 构造分流/apply | `CCodegenOnreadyTest`、`CCodegenEngineMethodUsageSessionTest`、`GenerateRenderFacadeTest` | 默认值 helper、first-write vs overwrite、ownership 释放、setter bypass、渲染入口分派 |
| 链/组合 impl/查询表 | `CCodegenOnreadyTest` | 链顺序、根适配器、flags 布局、组合 impl 转发、coroutine shell、入口注入、表形态、符号冲突 fail-fast |
| 析构回归 | `CCodegenTest` | flags 字节共享后析构 exactly-once 不变 |
| frontend | `FrontendAnnotationUsageAnalyzerTest`、`FrontendClassSkeletonTest` | placement/重复注解诊断、`_onready_field_default_` 保留前缀 |
| native probe | `GdccOnreadyLookupRuntimeSmokeTest` + `onready_lookup_probe.c` | 同一生成片段的 flags 门控、精确行分发、重入、失败诊断、key 重建 |
| 端到端 | `GdScriptUnitTestCompileRunnerTest` / `GdScriptEngineVirtualOverrideRuntimeTest`（`virtual/onready_*` 场景对）、`FrontendLoweringToCProjectBuilderIntegrationTest`、`GodotEditorHotReloadIntegrationTest` | ready 时序、继承顺序、重放、内部首调、构造期 fail-closed 三子场景、coroutine、export、混合 super、六种热重载场景 |

尚未闭合的验收缺口（后续补测试，不是实现缺口）：§2 顺序对照表未逐行映射到具名测试；「无 onready 链的类生成零差异」缺独立 golden 断言；frontend LIR→XML→parse→C 全链路已有 parse→generate 覆盖，但完整 frontend 起点链路未单独锚定。

## 10. 风险与维护提醒

1. **构造窗口 UB**：`_init` 链完成前 `_ready` 被触发时 overwrite 会销毁未初始化槽——双入口的 `CTOR_COMPLETE` 守卫是必需项，不得因「看起来还能跑」移除；`CTOR_COMPLETE` 只能由最派生类 notification 在完整 constructor 链后置位。
2. **DONE ≠ 成功**：DONE 表示「已尝试」；initializer/查询失败不清位不重试，但也不阻止组合 impl 重放。改动 DONE 语义会同时破坏重入去重与 `request_ready()` 重放。
3. **HAS vs DONE**：HAS 是实例实际类的类型事实，DONE 是执行事实，不可互换或由查询结果改写。
4. **first-write vs overwrite**：构造期 apply 不销毁旧值，ready apply 必须销毁旧值——两条路径复用错误会造成泄漏或 double-free；ready body 必须保持 position-less（`__prepare__` 抑制 destroy）。
5. **provenance 污染**：默认值回填会改变 `initFunc != null` 的含义；任何新的「源码 initializer 是否存在」判定必须走冻结计划/`initializerSynthesizedByBackend`，不得在渲染期反读 LIR 状态。
6. **发射顺序**：跨类引用的 helper 依赖段首统一 `static` 原型；删除原型或调整段序会在 derived-first 输入下「先用后声明」。
7. **位操作纪律**：`_gdcc_flags` 与析构守卫共享字节——create/recreate 按 §4 整字节写入初值；初始化之后的状态变更必须按位置位、按位读取，不得用整字节覆盖或整字节真值判断代替单个位操作。
8. **表行 ABI**：`apply` 列只填根签名适配器；禁止函数指针强转、禁止在循环内展开 `GD_STATIC_SN`。
9. **super / replay 边界**：`super._ready()` 不重放靠 DONE 拦截（组合 impl 先置位）；`request_ready()` 重放靠组合 impl 不看 DONE——两个方向的正确性共用同一置位顺序，改动时需同时验证双入口。
