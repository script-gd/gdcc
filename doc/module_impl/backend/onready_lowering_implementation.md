# `@onready` Ready-Time Lowering 实施方案

> 本文档是 `@onready` 从「frontend retention + usage validation」走向「Godot 语义一致的 ready-time 执行模型」的实施计划与后续长期维护说明。
> 当前状态为方案评审定稿，实施完成后本文档转为实现事实源。

## 文档状态

- 状态：Step 1-3 Implemented / Verified / Reviewed；Step 4-10 待实施
- 部分状态说明：Step 2-3 完成后 constructor / init_fields 已对 eligible 属性写入类型默认值，且 `<C>_class_apply_onready_init_<prop>` ready apply helper 已生成；但两类 ready 辅助路径尚未接入生命周期分发（链函数 / 适配器 / 组合 impl / prologue 属 Step 4-7），即 onready 真实 initializer 目前不会在运行期被调用，不得据此宣称 Godot 兼容语义已达成。
- 更新时间：2026-10-23
- 适用范围：
  - `src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
  - `src/main/c/codegen/template_451/entry.c.ftl`
  - `src/main/c/codegen/template_451/entry.h.ftl`（wrapper flags 位域与 onready apply 函数字段声明）
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendAnnotationUsageAnalyzer.java`
  - `src/main/java/gd/script/gdcc/frontend/sema/FrontendSyntheticPropertyHelperSupport.java`
- 关联文档：
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`（§5 现有 `@onready` usage contract）
  - `doc/module_impl/frontend/frontend_property_init_lowering_implementation.md`（constructor-time apply 合同）
  - `doc/module_impl/frontend/frontend_annotation_implementation.md`
  - `doc/module_impl/frontend/frontend_node_literal_implementation.md`（`$`/`%` DEFERRED 边界）
  - `doc/module_impl/backend/cbodybuilder_implementation.md`
  - `doc/module_impl/backend/virtual_override_vtable_implementation.md`（§5 合同需同步改写）
  - `doc/module_impl/backend/hot_reload_implementation.md`（recreate / init_fields 路径）
  - `doc/module_impl/backend/explicit_c_inheritance_layout_contract.md`（wrapper 根段布局合同）
  - `doc/module_impl/frontend/frontend_engine_virtual_override_implementation.md`
  - `doc/gdcc_low_ir.md`、`doc/gdcc_c_backend.md`、`doc/gdcc_ownership_lifecycle_spec.md`

---

## 1. 背景与问题

### 1.1 现状链路（问题现场）

`@onready` 目前的完整处理链路为：

1. parser 将 `@onready` 解析为 statement 级 `AnnotationStatement`；
2. `FrontendAnnotationCollector`（`src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendAnnotationCollector.java:52`）将其归类为 member-target 注解，投影进 `annotationsByAst` side table；
3. `FrontendClassSkeletonBuilder.applyPropertyAnnotations(...)`（`FrontendClassSkeletonBuilder.java:819`）把 `"onready" -> ""` 写入 `LirPropertyDef.getAnnotations()`，并随 DOM LIR 序列化 roundtrip；
4. `FrontendAnnotationUsageAnalyzer.validateOnreadyUsage(...)`（`FrontendAnnotationUsageAnalyzer.java:143-171`）做三条 placement 校验（class `var` property / 非 static / owner 派生自 `Node`）。

在此之后**没有任何 pass 消费该语义**：

- lowering 各 pass（`FrontendLoweringFunctionPreparationPass` / `FrontendLoweringBuildCfgPass` / `FrontendLoweringBodyInsnPass`）不读取 property annotations；
- `FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(...)` 不检查注解，`@onready var x = expr` 与普通 `var x = expr` 走同一条 `PROPERTY_INIT` lowering；
- backend 在 `<C>_class_constructor`（`entry.c.ftl:491-510`）中对所有 non-static property 无差别调用 `${Class}_class_apply_property_init_<prop>(self)`。

### 1.2 由此产生的语义偏差

- `@onready var x = expr` 的初始化表达式在**构造期**（POSTINITIALIZE → constructor）求值，而不是 Godot 语义中的 ready 时（`NOTIFICATION_READY` → `_ready` 分发前）；`_init()` 内观察到的已是初始化后的值，与 Godot 相反。
- Godot 中最典型的 `@onready var c = $Child` 因 node literal 在 property initializer 中被 compile gate 封口而无法编译（`frontend_node_literal_implementation.md` 的 DEFERRED 边界），本方案不解除该 gate（见 §7.3）。

### 1.3 Godot 语义事实源（godotengine/godot master 源码证据）

以 `godotengine/godot`（GDScript 实现）为准，`@onready` 的精确语义为：

1. **合法性**（`modules/gdscript/gdscript_parser.cpp` `onready_annotation`）：仅 class 成员 `var`；owner 类必须继承 `Node`（按本类自身基类判定，与外部类无关）；禁止 `static var`；同一变量重复注解报错；局部变量级别不接受该注解。
2. **分流 lowering**（`modules/gdscript/gdscript_compiler.cpp` `_parse_function`）：编译器为每个含 onready 字段的类生成特殊函数 `@implicit_ready()`，其中**只**按声明顺序赋值本类 `onready == true` 且有 initializer 的字段；构造期的 `@implicit_new` 只处理非 onready 字段。无 initializer 的 onready 字段在 ready 阶段**不生成任何赋值**。
3. **构造期默认值**（同文件 `@implicit_new` 段）：typed onready 字段在构造后、ready 前持有**类型默认值**（`int`→0、`Array`→空、对象→`null`）；无类型字段为 `nil`；`_init()` 中观察到的就是这些默认值。
4. **触发点**（`modules/gdscript/gdscript.cpp` `GDScriptInstance::callp` + `scene/main/node.cpp` `Node::_notification(NOTIFICATION_READY)`）：节点入树收到 ready 通知时，引擎对 `_ready` 做虚调用；`callp("_ready")` 先经 `_call_implicit_ready_recursively` **按基类→派生类**执行整条脚本继承链的 `@implicit_ready`，再执行最派生类找到的用户 `_ready`。**即使没有任何类定义 `_ready`，`@implicit_ready` 也照常执行。**
5. **顺序承诺**：同类内按声明顺序；基类脚本先于派生类脚本；所有 `@onready` 赋值完成后才进入用户 `_ready` 函数体；基类 `_ready` 仅经 `super._ready()` 执行，且 `super._ready()` **不会**重放 `@onready`（super 调用绕过 `callp`）。
6. **重放语义**：`callp("_ready")` 无「只执行一次」守卫；`Node::request_ready()` 后再次入树会重放 `@onready`；仅移除再入树而不调用 `request_ready()` 时不重放（`_ready` 本身只触发一次）。经 `callp` 的按名调用（含脚本内手动 `node._ready()`）同样重放。
7. **与 `@export` 交互**：场景实例化先写 export 值，ready 时 onready 初始化再覆盖；Godot 对此组合发 `ONREADY_WITH_EXPORT` 警告（默认 ERROR 级）。

---

## 2. 目标与非目标

### 2.1 目标

- `@onready var x = expr` 的 `expr` 求值与写回发生在引擎 `_ready` 虚分发时刻、用户 `_ready` 函数体之前；
- 继承链顺序：GDCC 基类 → 派生类逐段执行 onready 赋值；类内按声明顺序；
- 构造期行为对齐 Godot：typed onready 字段为类型默认值，无类型字段为 `nil`，对象类型字段为 `null`；`_init()` 观察到默认值；
- 无用户 `_ready` 时 onready 赋值仍执行；
- `super._ready()` 不重放 onready 赋值；
- `request_ready()` 驱动的再次 ready 重放 onready 赋值，旧值按 ownership 规则释放（overwrite 语义），不泄漏、不 double-free；
- 非引擎分发路径（gdcc 内部 vtable/`call_method`、super 调用、外部 `call("_ready")`、coroutine thunk 入口）对 `_ready` 的**首次**调用，按**实例实际最派生 GDCC 类**的完整链补执行一次 onready 赋值（flag 守卫）；
- 构造期内（`_init` 链完成前）的 `_ready` 触发（手动/虚调用经 prologue、READY 通知经组合 impl）**fail-closed 跳过** onready 赋值，杜绝销毁未初始化字段槽的 UB（见 §3.2-6/§3.2-7）；
- 热重载 recreate 路径行为与构造期一致（只回放默认值，不回放 ready 初始化器）；
- 无 onready 链的类不生成链函数、组合 impl 等 onready 专属分发代码（flags 位域迁移是全局统一变化，见 §3.2-5）；prologue 注入由「本类或后代链事实」单独控制（`_ready` 可能被有 onready 的后代继承，见 §3.2-7），「无 onready 链不生成」的验收只约束链函数/组合 impl/get_virtual/call_virtual 段，不约束用户 `_ready` 函数体。

### 2.2 非目标（本方案明确不做）

- 不解除 `$` / `%` node literal 在 property initializer / `@onready` initializer 中的 compile gate（独立后续工作，见 §7.3）；因此本方案落地后能延迟执行的初始化表达式仍以当前 property-init 支持面为限（字面量、全局函数调用、`Node.new()` 等不依赖同类实例成员的表达式）；
- 不支持跨模块 GDCC 父类的 onready 链（与 vtable 跨模块限制同级，见 §7.2）；
- 非引擎路径的 `_ready` **重放**不触发 onready 重放（首次调用会执行一次；重放仅发生在引擎 ready 分发路径，见 §7.1）；
- 构造期内手动 `_ready()` 不执行 onready（fail-closed，与 Godot 两阶段构造下的行为差异见 §7.1）；
- 混合 GDCC/GDScript 继承中 GDScript 子类不调 `super._ready()` 的情形不闭合（架构级保留项，见 §7.1/§7.3）；
- 不改变 `@onready` 既有 placement 校验与 `sema.annotation_usage` owner 归属；
- 不改变 vtable planner / vtable 布局（onready 的实例类型分发走 wrapper 根段函数字段，见 §3.2-6）；
- 不引入新的 LIR 指令、不改变 LIR 序列化格式；允许在 `LirPropertyDef` 上增加 transient 非序列化 provenance 标记（§3.2-1）；`"onready"` 注解继续由既有 `annotations` map 承载。

---

## 3. 总体设计

### 3.1 路线选择

候选路线与结论：

| 路线 | 结论 | 原因 |
| --- | --- | --- |
| A. 构造期初始化（现状沿用） | 否决 | 与 Godot ready-time 语义根本冲突，`_init` 可见性相反 |
| B. 裸 `_ready` 函数体 prologue（无守卫） | 否决 | `super._ready()` 会重放父类 onready，违反 Godot「super 调用不重放」语义 |
| C. LIR 层向 `_ready` 注入 prologue block | 否决 | direct backing-field write 是 backend 所有权边界，LIR `store_property` 会走 setter，语义不等价 |
| D. 纯 `_ready` 引擎分发桥接（bridge token） | 否决 | 引入第三种 userdata 种类；非引擎路径完全不执行 onready，与 Godot `callp` 差异过大 |
| B+flag 无实例类型分发的 prologue | 否决 | vtable slot 只指向 final overrider 的函数，声明类 prologue 只能跑声明类链，派生类 onready 在继承 `_ready` 场景下漏执行；且构造期虚调用会销毁未初始化槽 |
| **E. 组合 impl（引擎入口）+ wrapper 函数字段分发 + flag/构造守卫 prologue（非引擎入口）** | **采纳** | 引擎路径保持「ready 时、用户体之前」的精确时点与 request_ready 重放；非引擎路径经 wrapper 根段函数字段按**实例实际类型**分发完整链，flag 守卫首调、构造完成守卫防 UB；super 路径被 flag 拦截不重放；`get_virtual_with_data` 返回真实函数指针，不新增 userdata 种类；不改 vtable 布局 |

### 3.2 设计要点（路线 E）

1. **onready 事实判定与计划快照**：
   - backend 以 `propertyDef.getAnnotations().containsKey("onready")` 为唯一事实源；
   - **「源码 initializer 是否存在」必须在默认值合成之前快照**：`CCodegen.generate()` 现阶段先调 `generateDefaultGetterSetterInitialization()`（`CCodegen.java:937`），它会为无 initializer 的 property 回填 `initFunc`（`CCodegen.java:182-186`），之后 `initFunc != null` 不再能区分「源码有 initializer」与「backend 合成默认值」。因此在 `CCodegen.prepare(...)`（`CCodegen.java:1044`）阶段形成不可变 onready 计划（每类：有序 onready-with-source-initializer property 列表、链事实、用户 `_ready` 解析结果）；快照落点在 prepare 内 `CGenHelper` 构造（`CCodegen.java:1054` 附近）**之后**（`_ready` 解析依赖 `checkVirtualMethod`）；计划由 `CGenHelper` 唯一持有，`CCodegen.prepare` 仅强制构建、包级 `onreadyPlan()` 仅委托访问，时点不变。后续默认值合成、constructor/init_fields 分流、ready apply、链函数与组合 impl 判定统一消费该计划，模板渲染时不得反向依赖 `initFunc != null` 做语义判断；
   - **快照必须对 re-prepare / 模块复用鲁棒**：同一 `LirModule` 可能被第二个 codegen 会话再次 prepare（`CClassMetadataSupport.java:68-73` 明确要求 re-prepare 幂等；`CCodegenEngineMethodBindHeaderTest.java:237-247` 已有同模块双 codegen 用法），此时 `initFunc` 已被上一次 backend 合成回填，会话内快照读到的是污染后事实。因此规定：`generateDefaultGetterSetterInitialization` 在回填 `initFunc` 时必须**在变更当场留下持久 provenance 标记**（已落地为 `LirPropertyDef.initializerSynthesizedByBackend` transient 非序列化标记；不以 annotations map 承载，该 map 是用户面向且参与序列化 roundtrip），onready 计划只从「frontend 写入的 initFunc + 该标记」推导源码 initializer 事实，任何会话（含第二次 prepare）读到一致结果；
   - `CGenHelper` 新增判定 helper（命名遵循 `checkXxx` 约定）：`checkOnreadyProperty(LirPropertyDef)`（只读 property annotations，不需要 class 参数）、`checkClassChainHasOnreadyInitializer(LirClassDef)`（沿 `context.classRegistry()` 解析同模块 GDCC superName 链，**带 visited set 防御环**，遇 engine/未知父类终止）、`checkClassOrDescendantHasOnreadyInitializer(LirClassDef)`（本类或任意同模块**后代**类链事实为真；用于 prologue 注入条件——后代可能继承本类声明的 `_ready`，见 §3.2-7）、`resolveChainUserReadyFunctionOrNull(LirClassDef)`（返回链上最派生且通过 `checkVirtualMethod` 的 `_ready` 函数本体；无则 `null`；声明 owner 类名存于计划 `ClassPlan.userReadyOwnerClassName`）。
2. **构造期分流**（`entry.c.ftl` `class_constructor` 与 `class_init_fields` 两处 property 循环共用同一判定 helper）：
   - 非 onready property：维持现状（first-write apply 真实 initializer 或默认值）；
   - onready **无**源码 initializer property：维持现状（`initFunc == null` 时 backend 合成的默认值 `_field_init_<prop>` 恰为 Godot 构造期默认值语义，无需改动）；
   - onready **有**源码 initializer property：改调**默认值写回**（新增 `_onready_field_default_<prop>` hidden LIR helper 合成默认值 + 对应 `<C>_class_apply_property_default_<prop>` first-write apply），真实 initializer 推迟到 ready 时；
   - 默认值生成复用 `CCodegen.java:192-212` 的同一张类型默认值表——应把该 switch 抽成共享私有方法供两处调用，**不得复制一份会漂移的表**；对象类型 `null`、Variant/无类型 `nil`，与现表一致。
3. **ready-time apply**：为每个「onready 且有源码 initializer」的 non-static property 生成 `<C>_class_apply_onready_init_<prop>(<C>* self)`，body 由 `CCodegen` 新方法 `generateOnreadyApplyBody(...)` 生成：调用真实 `_field_init_<prop>(self_fat)` 并复用 `CBodyBuilder.moveOwnedCallIntoSlot(...)`（`CBodyBuilder.java:514-562`）实现 destroy-then-move overwrite。实现必须满足以下不变量（写入验收）：
   - target 固定经既有 `targetOfExpr("self->" + propertyName, propertyType)`（`CBodyBuilder.java:413-414`）构造，不为字段新增第二套 slot-write 逻辑；
   - 承载 body 的 `CBodyBuilder` 照 `generateStaticInitializersBody` 的模式起独立空 builder（`CCodegen.java:749-764`），只借用 init helper 函数上下文，**不得**经 `beginBasicBlock` / `setCurrentPosition` 进入 `__prepare__`——`checkInPrepareBlock()` 必须为 false（新建 builder 初始 `currentBlock == null` 即满足），否则 `__prepare__` 抑制 destroy/release（`CBodyBuilder.java:1493`），吃掉 overwrite 语义；
   - 不得滑回 `applyPropertyInitializerFirstWrite`：ready 路径执行时字段必已完成构造期 first-write（由 §3.2-6 构造守卫保证），first-write 会泄漏可析构默认值。
4. **onready 链函数与发射顺序**：为每个「链事实为真」的类生成 `<C>_class_apply_onready_inits(<C>* self)`：先递归调用同模块 GDCC 父类的链函数（父链事实为真时），再按声明顺序调本类各 `<C>_class_apply_onready_init_<prop>`。「链事实为真」包含**本类无 initializer 但祖先有**的中间类——此类也必须生成转发函数，否则最派生实例的链无法覆盖祖先赋值。链函数保持单一职责（只做字段 init，**不含** `_ready` 转发），供组合 impl 与 prologue 两个入口共用。发射顺序采用**段首统一 `static` 原型**（在 entry.c 前部发射前置声明），原型清单至少包括：`<C>_class_apply_onready_inits`、`<C>_class_apply_onready_init_<prop>`、`<C>__gdcc_onready_apply_root`（§3.2-5 根适配器）、`<C>__gdcc_onready_ready`（§3.2-6 组合 impl）——这些符号被跨类或跨段引用（子类链递归 / create_instance 初始化 / `get_virtual` 取地址），`module.classDefs` 源文件序允许 derived-first（正确性坑先例见 `virtual_override_vtable_implementation.md:141`），原型先行可同时满足「声明先于使用」与定义位置自由。
5. **wrapper 根段状态位与 onready apply 函数字段**（`entry.h.ftl` 根 wrapper struct + `CGenHelper` 访问 helper）：
   - `_gdcc_destructed` 改造为 `uint8_t _gdcc_flags` 位域（`GDExtensionBool` 本就是 `uint8_t`，尺寸不变）：bit0 保持 destructed 守卫语义，新增 bit1 `GDCC_FLAG_ONREADY_DONE`、bit2 `GDCC_FLAG_CTOR_COMPLETE`。destructed 的全部读写必须改为位操作（现 `entry.c.ftl:549` 的 `= true`、`:485`/`:546` 的整字节真值判断若原样保留会吃掉新增位）；
   - 根 wrapper 新增函数指针字段 `void (*_gdcc_onready_apply)(<Root>*)`（`<Root>` 为本继承树的 GDCC 根类，需新增 `CGenHelper` 根类解析 helper：沿 superName 链走到 GDCC 根，带 visited set）：prologue 经它按**实例实际最派生 GDCC 类**分发完整链（vtable slot 只指向 final overrider 的用户 `_ready` 函数，无法承担「实例类型分发」职责；本字段以 wrapper 布局扩展替代 vtable planner 变更，**不改 vtable**）；
   - **ABI 适配合同**：字段存的是**根签名适配器**而非链函数本体——链函数签名是 `void (<C>* self)`，与字段类型在非根类上不兼容，**禁止裸函数指针强转**（强转消除诊断但保留不兼容间接调用 UB）。为每个链事实为真的类生成 `static void <C>__gdcc_onready_apply_root(<Root>* root) { <C>_class_apply_onready_inits((<C>*)root); }`（offset-0 受控转换，与 vtable trampoline 同一豁免先例），create/recreate 写入适配器地址；适配器进段首原型清单与符号登记；
   - 初始化点：`create_instance` 写 `_gdcc_flags = 0`、`_gdcc_onready_apply = <链事实为真时本类根适配器地址，否则 NULL>`；`class_notification` 在 POSTINITIALIZE 的 constructor 调用**之后**置位 `GDCC_FLAG_CTOR_COMPLETE`（notification 回调只被引擎调到最派生类，故置位恰好在完整构造链结束后）；`recreate_instance` 写 `_gdcc_flags = GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_ONREADY_DONE`（字段已含有效默认值/恢复值，构造视为完成；DONE 置位表示继承前代「ready 已发生」的事实，防止 reload 后手调覆盖引擎恢复的 storage 值），`_gdcc_onready_apply` 按链事实重写（为真写本代根适配器地址，否则 NULL——避免 `dlclose` 后旧代函数指针悬空残留）；
   - **字段顺序冻结**：根段字段顺序为 `_object`、`_vtable`（若有）、`uint8_t _gdcc_flags`、`void (*_gdcc_onready_apply)(<Root>*)`——不得插入 `_object` 与 `_vtable` 之间（`_vtable` 紧跟 `_object` 是已冻结布局，`virtual_override_vtable_implementation.md:56`）；
   - 所有字段访问经 `CGenHelper` helper 收口（现状 `renderDestructedFlagAccessExpr` 已收口 destructed，新增 flags/函数字段访问 helper；派生类拼写必须走根段路径——`self->_super....`，禁止直接 `$self.ptr->_gdcc_flags`；**组合 impl 与 prologue 内同样必须经 helper 渲染根段访问，禁止字面 `self->_gdcc_flags`**）；
   - **DONE 的语义是「已尝试执行」而非「成功完成」**：先置位是重入去重策略，initializer 失败不清位、普通手调不重试；DONE 不阻止组合 impl 的重放 apply（引擎路径重放不受 DONE 影响，但受 CTOR_COMPLETE 影响，见 §3.2-6）。
6. **组合 impl `<C>__gdcc_onready_ready`（引擎分发入口）**：
   - 生成条件：链事实为真；形态 `static void <C>__gdcc_onready_ready(<C>* self)`；`static` 且只被本类 `get_virtual` / `call_virtual` 段引用（同类段内、段首原型覆盖），无跨类 static 引用问题；
   - 函数体固定形态（构造守卫 + 三段式）：

     ```c
     static void <C>__gdcc_onready_ready(<C>* self) {
         // 构造守卫：构造期 READY 通知可绕过 prologue 直达本入口（`_init` 内
         // `notification(NOTIFICATION_READY)` 或 add_child 进已在树上的父节点均可同步触发），
         // 此时派生字段槽尚未 first-write，无条件 overwrite 会销毁垃圾值（UB）。
         // 守卫失败时不置 DONE、不 apply，但用户 `_ready` 仍转发（与普通调用语义一致）。
         if ((<helper 渲染的根段 flags 访问> & GDCC_FLAG_CTOR_COMPLETE) == 0) {
             <转发用户 _ready（若有）>;
             return;
         }
         <helper 渲染的根段 flags 访问> |= GDCC_FLAG_ONREADY_DONE;  // 先置位：防 initializer 重入 + prologue 去重
         <C>_class_apply_onready_inits(self);                      // 不看 DONE：保证 request_ready() 重放
         <转发用户 _ready（若有）>;
     }
     ```

     转发规则：声明类符号直调；声明类不同于本类时先 `(Decl*)self` 偏移 0 转换，再用 `renderOwnerFatSelfFromWrapperPtr` 按声明类渲染 fat self——该 helper 参数类型是 `Decl*`（`CGenHelper.java:2346-2351`），同类先例 `entry.c.ftl:504-509`；链上无用户 `_ready` 时省略转发；coroutine `_ready` 必须转发到其**同步引擎入口 shell** `Decl__ready`（shell 内部自转 start thunk 并负责 `release_object`，`entry.c.ftl:870-887`；直转 thunk 会泄漏 state object）。flags 访问必须经 §3.2-5 的根段 helper 渲染（`_gdcc_flags` 只在根段，非根类字面 `self->_gdcc_flags` 编译失败）；
   - `get_virtual_with_data`：链事实为真时，`_ready` 特判在 own-function 循环**之前**返回 `(void*)&<C>__gdcc_onready_ready`——返回**真实函数指针**，仍属既有 userdata 协议的 impl 地址类，不新增种类（`virtual_override_vtable_implementation.md:87` 只需一句澄清而非例外条款）；
   - `call_virtual_with_data`：own-function 分支**之前**匹配 `&<C>__gdcc_onready_ready`，**dispatcher 直接调用**：`<C>__gdcc_onready_ready((<C>*)p_instance); (void)p_args; (void)r_ret; return;`（`p_instance` 本就是 wrapper 指针、偏移 0 转换合法，先例 `entry.c.ftl:614`；`_ready` 为 void+无参，ptrcall 层对其无实际工作；**不走 ptrcall**——ptrcall wrapper 会把 userdata 当 fat 函数指针调用（`entry.h.ftl:796`），与组合 impl 的 wrapper 签名 ABI 不符）；
   - 命名挂 `_gdcc_` 保留前缀（`FrontendSyntheticPropertyHelperSupport` 已拒用户 `_gdcc_*` 成员），无用户撞名路径；符号仍显式登记（见 §3.2-8）。
7. **非引擎入口 prologue（flag + 构造守卫 + 实例类型分发）**：对「声明了用户 `_ready`（普通或 coroutine）且 `checkClassOrDescendantHasOnreadyInitializer` 为真」的每个类（含抽象类——其 `_ready` 可经 super 到达），在其 `_ready` 入口注入：

   ```c
   // 伪代码（拼写以实施时 trampoline/helper 实际形态为准）
   <Root>* __w = <root wrapper from $self>;          // 物化 wrapper：与 vtable trampoline 同一拼写；
                                                     // 用户方法的 C 参数是按值 fat `$self`，没有现成 wrapper 变量
   if (!(__w->_gdcc_flags & GDCC_FLAG_ONREADY_DONE)
           && (__w->_gdcc_flags & GDCC_FLAG_CTOR_COMPLETE)
           && __w->_gdcc_onready_apply != NULL) {
       __w->_gdcc_flags |= GDCC_FLAG_ONREADY_DONE;
       __w->_gdcc_onready_apply(__w);                // 按实例实际最派生 GDCC 类的完整链
   }
   ```

   - **构造守卫**：`CTOR_COMPLETE` 未置位（`_init` 链完成前）时跳过且**不置位 DONE**——构造顺序是「父 constructor（含父 `_init`）→ 本类 property apply → 本类 `_init`」（`entry.c.ftl:491-509`），vtable 又是最派生表（`entry.c.ftl:408-411`），父 `_init` 里的虚调用 `_ready` 会带着未 first-write 的派生字段槽进入 overwrite，销毁 `godot_mem_alloc` 的垃圾值（UB）并让后续构造 first-write 泄漏。fail-closed 跳过同时保证引擎路径之后仍能正常执行（DONE 未置位）；
   - **实例类型分发**：链函数经 wrapper 函数字段（存根签名适配器地址，见 §3.2-5）调用，覆盖「`_ready` 只在祖先声明、派生类新增 onready」场景——内部 `self._ready()` / 外部 `call("_ready")` / 混合 super 调用进入的是祖先的 `_ready` 函数，但应用的是实例最派生类的完整链；这与 Godot `callp` 的 implicit ready 覆盖全链一致；
   - **普通函数注入点**：`CCodegen.generateFuncBody` 在 `goto entry` 前注入（先例 `emitLambdaCapturePrologue`，`CCodegen.java:222-262`、`:594-614`；该位置在 `__prepare__` 之前、builder 未设 currentBlock，无销毁抑制问题），注入条件必须排除 `func.isCoroutine()`（`generateFuncBody` 与 `__coro_body` 共用）；
   - **coroutine `_ready` 注入点**：**start thunk 的同步前段、首次 `mco_resume` 之前**（`entry.c.ftl:822-863` 段）——内部 direct/vtable/super 调用全部直进 start thunk（`CallMethodInsnGen.java:211-220`、`CallSuperMethodInsnGen.java:52-62`、vtable 协程槽指向 `__coro_start`），同步 shell 只是 thunk 的调用者之一，只注 shell 会漏掉全部内部路径；thunk 内同样先物化 wrapper（从 `$self` fat）再按上式执行；
   - prologue 只做 flag 检查与函数指针调用，以 appendRaw 形态发射，不经 CBodyBuilder 的 slot-write 体系。**helper 落点**：wrapper 一旦物化为 `<Root>* __w`，根段字段访问就是该指针上的直接字段访问（如上式）；现有 `renderRootWrapperFieldAccessExpr` 从字面 `self` 起步下钻 `_super`，对本形态不适用——实施时新写「从给定起始表达式渲染根段访问」的 helper（起始表达式参数化），不得复用只认识 `self` 的现有函数，也不得把伪代码中的字面 `__w->_gdcc_flags` 与 §3.2-5 的 helper 收口要求读成矛盾（物化后的直接访问即为收口形态）。
8. **符号登记与保留前缀**：所有新增文件级符号都必须在 `validateFileScopeSymbolsDisjoint`（`CCodegen.java:858-910`）中**显式登记**——该检查只自动登记 LIR 函数（`:883-885`），模板侧 helper 均为手工登记（先例 `:899-903`）。需新增登记：`<C>_class_apply_property_default_<prop>`、`<C>_class_apply_onready_init_<prop>`、`<C>_class_apply_onready_inits`、`<C>__gdcc_onready_apply_root`、`<C>__gdcc_onready_ready`；`_onready_field_default_<prop>` 是 LIR 函数会随 `className + "_" + function.getName()` 自动登记。保留前缀落点是 `FrontendSyntheticPropertyHelperSupport`（`:14-39` 的 `RESERVED_PREFIXES` 与 `:54-95` 的诊断文案分支），新增 `_onready_field_default_` 常量并补诊断文案分支（default 分支只列举 `_field_init_/_field_getter_/_field_setter_`，不加分支会报出错误说明）。
9. **前端补充诊断**（`FrontendAnnotationUsageAnalyzer`，owner 仍为 `sema.annotation_usage`）：同一 property 重复 `@onready` 报错，对齐 Godot「can only be used once per variable」。实现必须遍历该 property 上的全部同名注解，不能再 `findFirst`；skeleton 侧目前重复注解 last-wins（`FrontendClassSkeletonBuilder.java:809-819`），诊断先行即可，无需改 skeleton。

### 3.3 与 Godot 的顺序对照

| 时刻 | Godot | 本方案 |
| --- | --- | --- |
| 对象创建 | typed 字段填类型默认值；非 onready 字段按声明序初始化 | constructor：非 onready 走现状 apply；onready-with-init 走默认值 apply（在各自声明位置，因 gdcc MVP 禁止 initializer 访问同类实例成员，顺序不可观测）；constructor 链结束后由 notification 置位 `CTOR_COMPLETE` |
| `_init()` | 观察到 onready 字段默认值 | 一致 |
| 构造期内手动/虚调用 `_ready()`，或 `_init` 内显式 `notification(NOTIFICATION_READY)` | 两阶段构造下字段已有类型默认值，implicit ready 安全执行 | **fail-closed 跳过** onready 赋值（prologue 与组合 impl 双入口均受 CTOR_COMPLETE 守卫；DONE 不置位）。这两条路径不修改引擎 `ready_first`，之后真正入树时引擎仍会发 READY，届时正常 apply。用户 `_ready` 体仍执行（差异见 §7.1） |
| 构造期内 `add_child` 进已在树上且已 ready 的父节点 | 同上 | 这条路径经 `_propagate_ready` 同步发 READY 且**先清 `ready_first`**——它是唯一一次自然 READY，构造完成后引擎不会自动补发。守卫仍必须跳过 apply（派生槽未 first-write）；接受的差异：onready 保持构造期默认值，直到 `request_ready()` 后再次入树（差异见 §7.1） |
| ready 通知 | 基类→派生类 `@implicit_ready`，再最派生用户 `_ready` | 组合 impl：CTOR_COMPLETE 检查通过 → 置位 DONE → `<C>_class_apply_onready_inits`（基类→派生类、类内声明序，不看 DONE）→ 转发用户 `_ready`（声明类符号直调；其 prologue 被 DONE 拦截跳过） |
| 无用户 `_ready` | `@implicit_ready` 仍执行 | 组合 impl 仍返回并执行链函数（省略转发） |
| `super._ready()` | 不重放 onready | super 直调父类 `_ready` 入口 → prologue 被 DONE 拦截（引擎路径组合 impl 已置位），不重放 |
| 移除再入树（无 `request_ready()`） | 不重放 | 一致（引擎不再分发 `_ready`） |
| `request_ready()` 后再 ready | 重放 onready | READY 实际进入组合 impl 的路径：引擎再次虚分发 → 组合 impl 不看 DONE 重放 overwrite，旧值被销毁（混合继承 super 路径除外，见 §7.1） |
| 构造完成后脚本内手动 `node._ready()` | 经 `callp` 重放 onready | 首次调用经 prologue 按实例实际类型执行一次；重复调用被 DONE 拦截（差异见 §7.1） |
| 热重载 recreate | 引擎回放持久化状态 | `class_init_fields` 对 onready 且有源码 initializer 的属性只回放类型默认值（真实 initializer 仍推迟到 ready）；非 onready 属性维持现状回放；随后引擎经 setter 恢复 storage 属性；flags 置 `CTOR_COMPLETE|ONREADY_DONE`，手调不再补执行，`request_ready()` 仍重放（新代码，限 READY 实际进入组合 impl 的路径） |

---

## 4. 分步骤实施与验收细则

> 每一步完成后必须：编译通过（`./gradlew classes --no-daemon --info --console=plain`）+ 该步列出的目标测试通过（`script/run-gradle-targeted-tests.sh --tests <Class>`）。任何一步导致既有测试失败时，先查实现根因，不得改测试吞掉语义漂移。

### Step 1：onready 计划快照与判定 helper

**改动**：
- [x] `CCodegen.prepare(...)`：在 `CGenHelper` 构造之后、`generateDefaultGetterSetterInitialization()` 之前强制构建不可变 onready 计划（每类：有序 onready-with-source-initializer property 列表、链事实、后代事实、用户 `_ready` 解析结果含声明 owner 类名）；计划由 `CGenHelper` 唯一持有，`CCodegen` 不另存会话状态、包级 `onreadyPlan()` 仅委托访问，构建时点不变；
- [x] `generateDefaultGetterSetterInitialization()`：回填 `initFunc` 时经 `setBackendDefaultInitFunc` 置 `LirPropertyDef.initializerSynthesizedByBackend` transient 非序列化标记，保证第二个 codegen 会话复用同一模块时仍能区分 frontend 写入与 backend 合成；
- [x] `CGenHelper.java`：新增 `checkOnreadyProperty(LirPropertyDef)`、`checkClassChainHasOnreadyInitializer(LirClassDef)`（带 visited set）、`checkClassOrDescendantHasOnreadyInitializer(LirClassDef)`、`resolveChainUserReadyFunctionOrNull(LirClassDef)`；链/后代遍历经 `ClassRegistry` 与模块类表既有入口，不新建私有索引；判定数据源均为计划快照，模板渲染期不读 `initFunc != null` 做语义判断。

**实施记录**：
- 新类 `COnreadyPlan`（`backend.c.gen`，`public final`）：不可变 `Map<String, ClassPlan>` 事实表；扁平 record `ClassPlan(onreadyInitializers, chainHasOnreadyInitializer, classOrDescendantHasOnreadyInitializer, userReadyOwnerClassName, userReadyFunction)`（`_ready` owner 名与函数对象同有同无，构造器校验）；`classPlan(name)` 对未知类名返回共享的不可变空计划；包级静态工厂 `create(CGenHelper, List<? extends ClassDef>)`，`_ready` 解析经 `createClassPlan` 直接产出扁平事实。
- `CGenHelper`：构造器借用（borrow，不拷贝）调用方传入的 `List<? extends ClassDef>` 为 `moduleClassDefs`——它是唯一权威模块枚举，计划构建时读取一次后即不再读：生产路径由 prepare 在 helper 构造后立即触发捕获，直接构造的 fixture 在首次 onready 查询时按当时的列表内容捕获；已捕获的计划不受后续来源 list 变更影响，且不支持构建期并发修改。四个查询中 `checkOnreadyProperty` 仅判定 `annotations.containsKey("onready")`——不过滤 static/类型/initializer 来源，这些细分事实只属于计划（计划侧会额外排除 static property）；其余三个读取冻结的 `ClassPlan`。
- `CCodegen.prepare`：`new CGenHelper(...)` 之后立即调用 `helper.onreadyPlan()` 强制构建（计划由 `CGenHelper` 唯一持有，`CCodegen` 不另存）；包级 `onreadyPlan()` 访问器直接委托 helper，未 prepare 时抛 `IllegalStateException`。
- `LirPropertyDef`：新增 `transient boolean initializerSynthesizedByBackend`、`isInitializerSynthesizedByBackend()`、`setBackendDefaultInitFunc(name)`（同一次调用内完成赋值与置标，非并发原子语义；setter 名保留因它特指发布 backend 默认值 helper）；常规 `setInitFunc` 清除标记（显式重指即重新视为源码 initializer）。
- `generateDefaultGetterSetterInitialization`：默认值 helper 的 body 构建完成并 `addFunction` 之后才 `setBackendDefaultInitFunc`，构建失败不会发布半成品 synthetic init；instance property 的 `initFunc != null` 自此不再等价于「源码 initializer」，须结合 `isInitializerSynthesizedByBackend()` 判定。
- 边界与环防御：父边仅当 superName 非空、存在于模块类表、且 registry 中注册为 GDCC 类时成立（与 `CVtablePlanner.resolveGdccParents` 同一双条件；模块归属按模块类表线性扫描判定，registered-but-outside-module 不参与）；模块链遍历经 visited set 防御，命中环抛 `IllegalStateException`；`_ready` 解析取链上最派生且经 `checkVirtualMethod` 通过的声明（owner 记录实际声明类名），签名不匹配的声明跳过并继续向上；调用 `checkVirtualMethod` 前对 owner 做 registry 祖先环 preflight（镜像 `findEngineVirtualMethod` 的 engine→GDCC 解析顺序），防止 registered-but-outside-module 环输入在 registry 递归中爆栈。
- 冻结粒度：计划冻结成员关系、有序 property 引用列表、链/后代布尔与 `_ready` owner；被引用的 `LirPropertyDef`/`LirFunctionDef` 对象不深拷贝，backend body 准备阶段对它们的后续合法变更仍对 plan 消费者可见。
- 实现形态（整改后）：`create` 只保留一张构建中的 `HashMap<String, ClassPlan>` 事实表——工厂私有构建后将所有权移交给构造器，构造器以 `Collections.unmodifiableMap` 包装（无可变别名逃逸，无需拷贝）；不建 moduleByName/chainByClass 等私有索引——pass1 写入每类自有 onready initializer 列表（`ClassPlan` 内 `List.copyOf` 防御性拷贝成员选择，LIR property/function 引用仍共享），pass2 对每类现算 leaf→root 链（`collectModuleChain` 遍历模块类表 + `findModuleLirClass` 低频线性归属判定）、得出链事实并收集链成员名集合，`replaceAll` 回填后代事实；`_ready` 解析直接对链上 `LirClassDef` 对象执行。
- 验证：`./gradlew classes --no-daemon --info --console=plain` 通过；`script/run-gradle-targeted-tests.sh --tests CGenHelperTest,CCodegenOnreadyTest,DomLirSerializerTest,CCodegenTest,CCodegenEngineMethodBindHeaderTest,CVtablePlannerTest` 首轮通过（258 tests，0 failures，0 skipped；日志 `tmp/onready-step1/tests.log`）。整改后复跑 `classes` 与 `--tests CGenHelperTest,CCodegenOnreadyTest,DomLirSerializerTest` 通过（128 tests，0 failures，0 skipped；日志 `tmp/onready-step1/rework-*.log`，结果 `tmp/onready-step1/results-rework/`）。新增 `CCodegenOnreadyTest.serializedFrontendModuleShouldSurviveParseIntoPrepareAndGenerate` 覆盖 XML→parse→prepare→generate→二次会话复用的可执行回归。拷贝精简整改后复跑 `classes` 与 `--tests CGenHelperTest,CCodegenOnreadyTest` 通过（112 tests，0 failures，0 skipped——CGenHelperTest 107、CCodegenOnreadyTest 5；日志 `tmp/onready-step1/copy-refinement-*.log`，结果 `tmp/onready-step1/results-copy-refinement/`），新增借用语义、首次查询捕获与 `ClassPlan` 防御拷贝用例。字段重命名/计划归属单一化/`UserReadyFunction` 扁平化重构后复跑 `classes` 与 `--tests CGenHelperTest,CCodegenOnreadyTest,DomLirSerializerTest,CCodegenTest` 通过（226 tests，0 failures，0 skipped——CGenHelperTest 108、CCodegenOnreadyTest 5、DomLirSerializerTest 18、CCodegenTest 95；日志 `tmp/onready-step1/ownership-inline-*.log`，结果 `tmp/onready-step1/results-ownership-inline/`），新增 `ClassPlan` owner/function 同有同无不变量用例。
- review 记录：`review-expert-a` 首审指出两处验收缺口，均已复核并整改：(1) 补齐带真实 initializer 函数体的 XML→parse→prepare→generate→二次会话回归；(2) §5 总体验收的双会话 C 分流项保持 `[ ]`，仅在 Step 1 节内标记计划事实/provenance 与既有默认 helper 幂等验收完成。同步移除私有索引、精简注释、补充 `setInitFunc(null)` 清标记与 helper 身份断言。整改后再次由 `review-expert-a` 审阅，结论 `APPROVE`，两项问题确认关闭，未发现新的中高风险或阻塞问题。其后拷贝精简整改（借用模块类表、`unmodifiableMap` 包装、`HashMap` 事实表、防御性拷贝边界与首次查询捕获语义）亦经 `review-expert-a` 增量复审，结论 `APPROVE`，0 findings。再后续字段重命名（`initializerSynthesizedByBackend`）、onready 计划归属收拢至 `CGenHelper`（`CCodegen` 仅委托）、`UserReadyFunction` 扁平化入 `ClassPlan` 已实施并通过定向验证，随后 `review-expert-a` 增量复审结论 `APPROVE`，0 findings。

**验收**：
- [x] `CGenHelperTest` 新增用例：onready 判定 true/false；无源码 initializer 的 onready 不计入链事实；链解析覆盖「父类有、本类无」「本类有、父类无」「三级链中间类有」「engine 父类终止」；后代事实覆盖「本类无、后代有」「本类有、后代无」「全无」；`_ready` 解析命中最派生定义者并返回正确声明 owner；环状 superName 输入不死循环；
- [x] 双 codegen 会话回归（形态对照 `CCodegenEngineMethodBindHeaderTest.java:237-247`）：同一输入 `LirModule` 经两个先后创建的 codegen 各生成一次——无源码 initializer 的 onready 两次均不进入 ready 赋值计划；有源码 initializer 的两次分流一致；默认 helper 不重复合成；
- [x] `script/run-gradle-targeted-tests.sh --tests CGenHelperTest` 通过。

### Step 2：构造期默认值分流

**改动**：
- [x] `CCodegen.java`：把 `CCodegen.java:192-212` 的类型默认值 switch 抽成共享私有方法；为「onready 且有源码 initializer」的 non-static property 合成 hidden 默认值 helper `_onready_field_default_<prop>`（签名与 `_field_init_` 相同）；新增 `generatePropertyDefaultApplyBody(...)`（first-write 语义）；
- [x] `entry.c.ftl`：`class_constructor`（`entry.c.ftl:498-503`）与 `class_init_fields`（`entry.c.ftl:436-442`）两处循环调同一判定 helper 分流：onready-with-source-init property 调 `<C>_class_apply_property_default_<prop>`，其余维持现状；
- [x] `validateFileScopeSymbolsDisjoint` 显式登记新 apply helper 符号。

**实施记录**：
- `CGenHelper` 新增 API（不含新缓存/索引/状态）：`checkOnreadyInitializer(clazz, property)` 仅查冻结计划 `onreadyInitializers` 列表成员（身份相等，不重读 live annotation / `initFunc`）；`renderOnreadyFieldDefaultFunctionName` → `_onready_field_default_<prop>`；`renderPropertyDefaultApplyHelperName` → `<C>_class_apply_property_default_<prop>`；`renderOnreadyApplyHelperName` → `<C>_class_apply_onready_init_<prop>`；`renderPropertyConstructionApplyHelperName` 统一分流（eligible→default apply，否则原 init apply），constructor 与 init_fields 同走此 chooser。
- `CCodegen` 把原默认值 helper 构建体抽为 `createPropertyDefaultInitializer(clazz, property, name)`，类型默认值表保持单源（object→null、Variant/nil→nil、bool→false、int→0、float→0、String/StringName→空、typed Array/Dictionary 保留元素元数据、packed→`ConstructArrayInsn(null)`、其余 builtin→`ConstructBuiltinInsn`、compiler-only→fail）；普通 `initFunc == null` 回填维持原样调用并在 `addFunction` 后 `setBackendSynthesizedInitFunc`。
- `generateDefaultGetterSetterInitialization` 的 onready 循环对每个 eligible 属性保证 `_onready_field_default_<prop>` 存在：缺失则经共享工厂合成并 `addFunction`；已有同名占用时经 `requireOnreadyFieldDefaultFunction` 解析（恰一个匹配，单引用+计数扫描）并复用共享 `validatePropertyInitFunctionReadyForCodegen(clazz, property, function)` 校验（hidden、coroutine/lambda/abstract/vararg/非空 captures、精确返回类型、非 static 单 owner-self 参数、真实 body、有效 entry——该守卫经 `validatePropertyInitFunctionSignature` 对所有 init 路由统一生效，普通 instance init helper 的 static 签名亦一并收紧拒绝）；复用/合成前另经 `requireOnreadyDefaultNameUnaliased` 拒绝任何属性 `initFunc` 指向保留名（self/sibling 别名），防止源码被构造期默认 helper 顶替求值；重名冲突、shell、错型、无效 entry 等一律 fail，不修复不顶替；合法占用即跨会话复用。源码 `initFunc` 不回指、不打合成标记。
- `generatePropertyDefaultApplyBody(clazz, property, session)`（buffer 私有重载 + session 包级包装）：先校验冻结成员资格（ineligible 抛 `IllegalStateException`），再解析/校验 `_onready_field_default_` helper，经 `emitPropertyConstructionFirstWrite` 与原 init apply 共用 first-write 发射（fat-self 转换改用 `renderOwnerFatSelfFromWrapperPtr`，行为等价），不写 setter、不销毁旧值。
- `GenerateRenderFacade` 新增 `generatePropertyDefaultApplyBody` / `generateOnreadyApplyBody` 回调（两个构造器同形扩参，无兼容重载残留）；`entry.c.ftl` 对 eligible property 改发 default apply（ordinary 分支不变），真实 `_field_init_<prop>` 函数仍由底部函数循环照常发射。

**验收**：
- [x] codegen golden（新增 `CCodegenOnreadyTest` 或挂 `CCodegenTest`）：**只在 constructor / init_fields 函数体内**断言 onready-with-init property 调 default apply、不调 `<C>_class_apply_property_init_<prop>`；真实 `_field_init_<prop>` 函数仍正常发射（全文不禁出现该名字，ready apply 必须调用它）；非 onready property 生成不变——`generateShouldRouteOnreadySourceInitThroughDefaultApplyAtConstruction`、prepare 后改 annotation 不翻转路由（`constructionRoutingShouldStayFrozenAcrossPostPrepareAnnotationMutation`）、跨会话 helper 复用与 body 逐字节一致（`repeatedSessionsShouldReuseOnreadyDefaultHelperAndEmitIdenticalApplyBodies`）、XML roundtrip 断言扩展；
- [x] `script/run-gradle-targeted-tests.sh --tests CCodegenTest,CBodyBuilderPhaseCTest` 通过（property-init 体系回归）。

### Step 3：ready-time apply helper（overwrite 语义）

**改动**：
- [x] `CCodegen.java`：新增 `generateOnreadyApplyBody(...)`，满足 §3.2-3 的三条不变量（`targetOfExpr` 构造 target、不进入 `__prepare__`、不用 first-write）；
- [x] `entry.c.ftl`：为每个 onready-with-source-init non-static property 生成 `static inline void <C>_class_apply_onready_init_<prop>(<C>* self)`；符号显式登记。

**实施记录**：
- `generateOnreadyApplyBody(clazz, property, session)`（buffer 私有重载 + session 包级包装）：先经 `requireOnreadySourceInitFunction` 解析真实源码 init helper（冻结成员资格 + `initFunc` 非空且未打 backend 标记的 provenance 守卫 + 共享 readiness 校验，构造期回填/覆写前先于其它变更失败）；builder 上下文持 sourceInit 但**不** `beginBasicBlock`/`setCurrentPosition`——`__prepare__` 的销毁抑制依赖当前块位置，字段已在构造期 first-write，ready 期覆写必须销毁旧值；经 `targetOfExpr("self->"+name, type)` + `moveOwnedCallIntoSlot` 走 overwrite/move 语义：先物化调用结果、再 capture old、assign、按 refcount 状态 release/try-release（`NO` 不 release），不再 own 新值、不销毁 carrier；非 destroyable 类型走直接赋值。setter 同样被绕过（apply 直写字段）。
- `entry.c.ftl` eligible 分支在同一段同时发射 default apply 与 ready apply 两个 `static inline` 定义；`validateFileScopeSymbolsDisjoint` 对 ready apply 符号按条件显式登记，与用户函数碰撞即报 file-scope 冲突。
- 已知边界（记录待 Step 10 收口，不视为已达成 Godot 兼容）：constructor/init_fields 现已对 eligible 属性写默认值，但 ready apply helper 尚无调用方——`<C>_class_apply_onready_inits` 链函数、根适配器、wrapper flags、组合 impl/引擎分发、非引擎 prologue 属 Step 4-7；构造期/重建期旧 wording 中「onready 随构造赋值」语义待 Step 4+ 接通，source-of-truth 文档改写仍归 Step 10。

**验证与 review**：
- 编译：`./gradlew classes --no-daemon --info --console=plain` 通过（`tmp/onready-step23/compile.log`）。
- 首轮 targeted gate：`script/run-gradle-targeted-tests.sh --tests CCodegenOnreadyTest,CCodegenTest,CBodyBuilderPhaseCTest,CCodegenEngineMethodUsageSessionTest,CGenHelperTest,GenerateRenderFacadeTest` 通过（348 tests，0 failures，0 skipped——CCodegenOnreadyTest 26、CCodegenTest 95、CBodyBuilderPhaseCTest 109、CCodegenEngineMethodUsageSessionTest 7、CGenHelperTest 109、GenerateRenderFacadeTest 2；日志 `tmp/onready-step23/tests.log`，结果 `tmp/onready-step23/results/`）。
- review 处置：`review-expert-a` 初审 `REQUEST_CHANGES`（2 medium + 2 low），另附一处复核检查发现的保留名别名缺陷，均已整改确认——(1) 特殊函数形态守卫上移共享 `validatePropertyInitFunctionSignature`：coroutine/lambda/abstract/vararg/非空 captures 对所有 init 路由（实例普通/source-onready/default slot/静态 source）统一拒绝，分支特有 static 检查保留；(2) 新增 `requireOnreadySourceInitFunction`：冻结成员资格 + `initFunc` 非空且未被 backend 标记的 provenance 守卫，eligible 属性先于 `_field_init_` 回填校验，防止 initFunc 清空/标记污染被静默回填；(3) `generatePropertyInitApplyBody` 直接入口拒绝 eligible 属性绕过分流，并对普通 init 补 readiness 校验；(4) 跨会话 golden 扩充 constructor/init_fields/hidden default helper 函数体逐字节相等与 onready_missing 普通路由断言，malformed 用例补 wrong self type/name/arity 及「失败前无 `__prepare__`/`__finally__` 嫁接、shell 0 块」断言；(5) 保留名别名守卫 `requireOnreadyDefaultNameUnaliased`：复用前扫描类属性表，拒绝任何 `initFunc` 指向保留 `_onready_field_default_` 名（self/sibling 别名，防止源码在构造期被求值），占用解析改为单引用+计数。
- 整改验证：`--tests CCodegenOnreadyTest,CCodegenTest,CCodegenEngineMethodUsageSessionTest` 通过（144 tests，0 failures，0 skipped——CCodegenOnreadyTest 42、CCodegenTest 95、CCodegenEngineMethodUsageSessionTest 7；日志 `tmp/onready-step23/rework-compile.log`、`rework-tests.log`，结果 `tmp/onready-step23/results-rework/`，整改 diff `tmp/onready-step23/rework.diff`）。
- 复审结论：`review-expert-a` 增量复审 `APPROVE`：4 项初审发现与保留名别名守卫均确认修复，无遗留 medium/high/blocking/low/suggestion 问题。

**验收**：
- [x] golden 断言三类形态：destroyable 值类型（String/Array/Dictionary/Variant/packed）为 destroy-then-move；对象类型为「先物化调用结果→capture old→assign→release old」（`RefCounted`→`release_object`、`Object`→`try_release_object`、`Node`→无 release、不 own 不毁 carrier）；非 destroyable 类型为直接赋值；body 中不出现 first-write 路径——`readyApplyShouldDestroyThenMoveForDestroyableValueTypes`、`readyApplyShouldCaptureAssignReleaseObjectFieldsPerRefcountStatus`、routing 用例内标量直赋值断言；
- [x] binding usage 登记无遗漏（`GodotBindingUsageBuffer` 会话与现有 apply body 一致）——`onreadyApplyRenderersShouldReuseSharedSessionWithoutInventingBindings`：真实源码 helper body 的 constructor/method 经 `generateFuncBody` 恰登记一次、wrapper 重复渲染不新增不丢失、失败路由不污染 session、`engine_method_binds.h` 含所需绑定；
- [x] `script/run-gradle-targeted-tests.sh --tests CCodegenTest,CCodegenEngineMethodUsageSessionTest` 通过。

### Step 4：onready 链函数与根适配器（段首原型 + 定义）

**改动**：
- `entry.c.ftl`：段首统一发射 `static` 原型，清单：`<C>_class_apply_onready_inits`、`<C>_class_apply_onready_init_<prop>`、`<C>__gdcc_onready_apply_root`、`<C>__gdcc_onready_ready`；
- 为链事实为真的类生成 `<C>_class_apply_onready_inits` 定义（父链事实为真时先递归 `<Parent>_class_apply_onready_inits(&self->_super)`，再按声明顺序调本类 ready apply）；
- 为链事实为真的类生成根签名适配器 `<C>__gdcc_onready_apply_root(<Root>* root)`（内部 offset-0 转换后调本类链函数；**禁止**把链函数地址裸强转存入函数字段）；
- `CGenHelper` 新增根类解析 helper（沿 superName 链走到 GDCC 根，带 visited set）供字段类型与适配器签名渲染；
- 符号显式登记（链函数、适配器均登记）。

**验收**：
- golden 断言：段首原型清单完整；适配器签名与字段类型一致；基类→派生类递归顺序；类内声明顺序；**仅当本类及全部同模块 GDCC 祖先均无 onready initializer 时才不生成**链函数与适配器；本类无 initializer 但祖先有的中间类生成转发函数与适配器；
- A→B→C 形态（A 有、B 无、C 有）同时验证中间转发、初始化顺序与组合 impl 所属类；
- derived-first 模块输入（类在源文件中先子后父）下生成 C 通过 zig 编译 smoke（对照既有 `CVtableCodegenTest` derived-first 用例形态）；
- `script/run-gradle-targeted-tests.sh --tests CCodegenTest` 通过。

### Step 5：wrapper flags 位域与 onready apply 函数字段

**改动**：
- `entry.h.ftl` 根 wrapper struct：字段顺序固定为 `_object`、`_vtable`（若有）、`uint8_t _gdcc_flags`、`void (*_gdcc_onready_apply)(<Root>*)`——不得插入 `_object` 与 `_vtable` 之间（冻结布局 `virtual_override_vtable_implementation.md:56`）；`_gdcc_destructed` 并入 `_gdcc_flags` bit0；新增 `GDCC_FLAG_ONREADY_DONE` / `GDCC_FLAG_CTOR_COMPLETE` 常量；
- `CGenHelper`：`renderDestructedFlagAccessExpr` 改为位操作拼写，新增 flags / 函数字段访问 helper 与根类解析 helper（根段路径，派生类经 `_super` 链）；
- `entry.c.ftl`：`create_instance` 写 `_gdcc_flags = 0` 与函数字段（链事实为真取本类**根适配器**地址，否则 NULL——段首原型保证可见）；`class_notification` 在 constructor 调用之后置位 `CTOR_COMPLETE`；`recreate_instance`（`entry.c.ftl:472` 附近）写 `_gdcc_flags = CTOR_COMPLETE | ONREADY_DONE` 并按链事实重写函数字段（为真写本代根适配器，否则 NULL）；destructor 路径（`:485`、`:546-549`）全部改为位操作。

**验收**：
- golden 断言：字段顺序（`_vtable` 仍紧跟 `_object`）、位域声明、destructed 读写的位操作形态（置位 `|= 1`、测试 `& 1`）、三个初始化点、函数字段写适配器地址而非链函数地址；
- 既有析构 guard 相关测试与 `script/run-gradle-targeted-tests.sh --tests CCodegenTest,CGenHelperTest` 通过（destructed 行为零变化）。

### Step 6：组合 impl 与引擎分发接入

**改动**：
- `entry.c.ftl`：为链事实为真的类生成 `static void <C>__gdcc_onready_ready(<C>* self)`（体严格按 §3.2-6 三段式：置位 DONE → 无条件链函数 → 转发）；符号显式登记；
- `get_virtual_with_data`：链事实为真时插入 `_ready` 特判分支（own-function 循环之前），返回 `(void*)&<C>__gdcc_onready_ready`；
- `call_virtual_with_data`：插入匹配分支（own-function 分支之前），按 §3.2-6 的直接调用形态发射（含 `(void)p_args; (void)r_ret;` 与 `return` 收尾；转发目标为父类声明时先 `(Decl*)` 转换再渲染 fat self）；coroutine `_ready` 转发到同步引擎入口 shell。

**验收**：
- golden 断言：无 onready 链的类 get_virtual/call_virtual 分发段 diff 为空（prologue 由后代事实单独控制，不约束用户函数体）；组合 impl 体内顺序为「CTOR_COMPLETE 检查 → 置位 DONE → apply → 转发」；CTOR_COMPLETE 未置位时不置 DONE、不 apply、仅转发用户 `_ready` 并 return（无用户 `_ready` 时两个分支均省略转发调用）；flags 访问经根段 helper 渲染而非字面 `self->_gdcc_flags`；`get_virtual` 返回组合 impl 真实函数指针；`call_virtual` 分支 `return` 收尾且不走 ptrcall；转发目标为**声明类**符号（`_ready` 仅在父类定义的场景专项覆盖，含 `(Decl*)` 转换）；coroutine `_ready` 转发到 shell 而非 thunk；
- `script/run-gradle-targeted-tests.sh --tests CVtableCodegenTest,CCodegenTest` 通过（virtual/vtable 回归）。

### Step 7：非引擎入口 prologue 注入

**改动**：
- `CCodegen.generateFuncBody`：对「声明普通 `_ready` 且 `checkClassOrDescendantHasOnreadyInitializer` 为真」的函数，在 `goto entry` 前按 §3.2-7 伪代码注入 prologue（排除 `func.isCoroutine()`）；
- `entry.c.ftl`：对「声明 coroutine `_ready` 且后代/本类链事实为真」的类，在 start thunk 同步前段、首次 `mco_resume` 之前注入同构 prologue（`entry.c.ftl:822-863` 段）。

**验收**：
- golden 断言：prologue 的 flag 检查顺序（DONE 测试 → CTOR_COMPLETE 测试 → 函数字段非空 → 置位 DONE → 调用）；普通函数注入位置在用户代码之前且 coroutine 体函数不注入；thunk 注入位置在首次 `mco_resume` 之前；声明类无链事实但后代有（`_ready` 被继承场景）也注入；
- `script/run-gradle-targeted-tests.sh --tests CCodegenTest` 通过。

### Step 8：前端补充诊断与保留前缀

**改动**：
- `FrontendAnnotationUsageAnalyzer.java`：重复 `@onready` 注解报错（category `sema.annotation_usage`），遍历全部同名注解而非 `findFirst`；
- `FrontendSyntheticPropertyHelperSupport.java`：新增 `_onready_field_default_` 前缀常量、加入 `RESERVED_PREFIXES`、补充对应诊断文案分支。

**验收**：
- `FrontendAnnotationUsageAnalyzerTest` 新增重复注解用例；既有 onready 三规则用例不变；
- skeleton 保留前缀用例覆盖新前缀（用户使用 `_onready_field_default_x` 成员被拒且文案正确）；
- `script/run-gradle-targeted-tests.sh --tests FrontendAnnotationUsageAnalyzerTest,FrontendClassSkeletonAnnotationTest` 通过。

### Step 9：runtime 测试锚点

**前置约束**：property initializer 当前禁止访问 `self`/同类 non-static 成员，`$`/`%` 仍 DEFERRED；所有锚点的初始化表达式必须落在当前 property-init 支持面内（字面量、全局函数调用、`Node.new()` 等），顺序观察用静态计数器实现。

**改动**（`src/test/test_suite/unit_test/script/runtime/` 新增 anchor，同步登记 `GdScriptUnitTestCompileRunnerTest.EXPECTED_SCRIPT_PATHS`、镜像 validation 文件、以及 `GdScriptEngineVirtualOverrideRuntimeTest.SCRIPT_RESOURCE_PATHS`）：
- `onready_ready_time_init.gd`：`@onready var x = <支持面内 expr>` 在 `_init` 中为类型默认值、在 `_ready` 体中为真实值；
- `onready_base_before_subclass.gd`：基类 onready 先于子类 onready、均先于最派生用户 `_ready`（对齐 Godot 同名测试）；
- `onready_without_user_ready.gd`：无 `_ready` 定义时 onready 仍生效；
- `onready_super_no_replay.gd`：`super._ready()` 不重放父类 onready；
- `onready_replay_with_request_ready.gd`：移除再入树不重放；`request_ready()` 后再入树重放；重放前字段已持 RefCounted/容器值时旧值正确释放（以可达性/计数观察，不断言内部实现）；
- `onready_internal_first_call.gd`：构造完成后、入树前的非引擎路径（如方法内 `self._ready()`）首次调用按实例实际类型执行一次 onready，引擎 ready 分发时再经组合 impl overwrite 修正（对齐 Godot `callp` 双次求值形态）；
- `onready_inherited_ready_derived_onready.gd`：用户 `_ready` 仅在父类声明、子类新增 onready——内部调用经 wrapper 函数字段应用**子类**完整链（回归 §3.2-7 的实例类型分发）；
- `onready_ctor_window_fail_closed.gd`：构造期 fail-closed 三子场景——(a) 基类 `_init` 内虚调用 `_ready`、(b) `_init` 内显式 `notification(NOTIFICATION_READY)`：onready 跳过、DONE 不置位、之后真正入树时正常应用；(c) `_init` 内 `add_child` 进已 ready 的父节点：onready 跳过且**构造完成后不自动补执行**（字段保持默认值），`request_ready()` 后再次入树才应用 initializer 值。锚点的用户 `_ready`/`_init` 函数体只允许读已完成 first-write 的祖先槽，不得读派生 onready 字段（该路径上派生槽未初始化，「不崩溃」承诺不覆盖任意用户函数体）；
- coroutine 锚点：coroutine `_ready` 经内部 direct 调用与 super 调用时 onready 在首次 await 前完成（thunk prologue 覆盖）；
- `onready_export_override.gd`（如 export 支持面允许）：ready 前外部写入的值在 ready 后被 initializer 覆盖；
- 热重载场景：`GodotEditorHotReloadIntegrationTest` 不读 test_suite `.gd`，锚点必须是在该类内新增的 `SourceFileSpec` scenario（约 `:71` 起），分别覆盖：仅 recreate 不做调用（保持默认值 + 恢复值）；recreate 后手动 `_ready()`（DONE 已置位，不补执行、不覆盖恢复值）；recreate 后 `request_ready()`（组合 impl 按新代码重放）；如基础设施允许，混合 super × request_ready/recreate 场景验收「GDCC 链不重放」的差异行为（差异合同见 §7.1）。

**验收**：
- 上述 anchor 编译通过并产出一致预期输出；engine 相关验收**不得以环境 skip 代替通过**（无 Zig/`GODOT_BIN` 时在验收记录中显式标注为未执行而非通过）；
- `script/run-gradle-targeted-tests.sh --tests GdScriptUnitTestCompileRunnerTest,GdScriptEngineVirtualOverrideRuntimeTest` 通过。

### Step 10：文档收口

**改动**（注意：以下既有合同条款与新行为**相反**，必须**改写**原句而不是只追加补充，否则同一份文档内部自相矛盾）：
- `frontend_type_check_analyzer_implementation.md` §5 与 §7：将「backend/lowering 不消费 ready-time 语义」与未完成项中的 `@onready` runtime lowering 改写为已实现合同，并指向本文档；
- `frontend_property_init_lowering_implementation.md` §2/§7：把 `@onready` 从「不属于支持面」改为「constructor-time 分流 + ready-time apply」；同步改写 `:171-181` 的 constructor apply 合同（first-write 语义现在仅覆盖构造期默认值与现状路径，ready 路径为 overwrite）并指向本文档；
- `frontend_rules.md:131-132`：更新 MVP 合同表述；
- `frontend_node_literal_implementation.md`（约 `:208`）：把「onready 仅 retention」的表述更新为「ready-time 已实现，`$`/`%` gate 仍关闭」；
- `frontend_engine_virtual_override_implementation.md:261`：「`_ready` 只触发一次」改写为含 `request_ready()` 重放例外的表述；
- `virtual_override_vtable_implementation.md` §5 需同步改写：
  - `:59`「trampoline 是唯一受控下行转换例外」：改写为「trampoline 与 onready 根适配器（§3.2-5）两处受控例外」；
  - `:84`「本类按名匹配之后才父类转发」与 `:85-86` 的 fall-through 描述：改写为含 `_ready` 特判例外——链事实为真时 `_ready` 查询在 own-function 循环之前由组合 impl 分支接管并 `return`，不再转发；
  - `:87`「userdata 地址即方法身份（impl 地址或 per-method default-ud），ClassDB 与 virtual 共享同一协议」：只需**澄清**而非例外——onready 链类的 `_ready` 返回合成组合 impl 地址，仍属 impl 地址类（本方案不新增 userdata 种类）；
  - `:88` 双通道一致性：改写为——onready 链类上两个通道都含「onready 链 + 用户 `_ready`」语义（引擎通道经组合 impl、内部通道经 prologue 首调补执行），差异仅在重放：引擎通道每次分发重放，内部通道由 DONE 守卫只执行一次；
- `explicit_c_inheritance_layout_contract.md`：`:59` 的 GDCC→GDCC 可证明上行限制补充指向上述两处受控例外；`:70`「两个根段共享字段」改写为三个（`_vtable`、`_gdcc_flags`、`_gdcc_onready_apply`）；改写 `:72`、`:104`、`:174-176` 的 `_gdcc_destructed` 字段声明/读写描述为 `_gdcc_flags` 位域合同；`:115` 的 `_gdcc_destructed = false` 改写为「`_gdcc_flags = GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_ONREADY_DONE`，并按链事实重写 `_gdcc_onready_apply`（为真写本代根适配器地址，否则 NULL）」；`:117` **保留**「recreate 专用、base-first 递归父段、不调 `_init`、constructor 不复用」等现行从句，只把「本类非 static 属性一律 init apply」改为「非 onready 属性维持真实 initializer apply，onready-with-init 属性只走默认值 apply」；
- `hot_reload_implementation.md`：`:173` 改两处——flags 赋值改为 `_gdcc_flags = GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_ONREADY_DONE` 并按链事实重写函数字段（为真写本代根适配器、否则 NULL），括号内「base-first 重放非 static 属性 initializer」从句改写为「base-first：非 onready 属性重放真实 initializer，onready-with-init 属性只走默认值 apply」；`:182`、`:188` 只承担 destructed 位域迁移，且必须区分两个初始化点：create 写 `_gdcc_flags = 0`，**不得**套用 recreate 的 `CTOR_COMPLETE | ONREADY_DONE`；`:372` **保留**「随后引擎 setter 覆盖保存值」与「非 onready initializer 不得依赖 exactly-once 副作用」从句，只把 onready-with-init 的分流写入（真实 initializer 留到 READY 实际进入组合 impl 的路径；混合 super 路径不重放，见 `onready_lowering_implementation.md` §7.1——注意引用本文档章节号，不得写成无文档限定的「§7.1」）；
- `cbodybuilder_implementation.md:57-61`、`:220`：「单一 constructor apply helper」表述改写为「构造期 first-write apply 与 ready 时 overwrite apply 双入口」；
- `gdcc_ownership_lifecycle_spec.md:102-106`：constructor apply 的 first-write 定义补充 ready apply 的 overwrite 语义边界；
- `gdcc_c_backend.md:690`：「`initFunc == null` 才允许 backend 合成默认 helper」改写为含 onready 默认值 helper 的例外；
- 本文档状态改为 `Implemented / Maintained`。

**验收**：所改各文档内部无互相矛盾的合同表述（自查 + review）。

---

## 5. 总体验收清单

- [ ] Godot 顺序对照表（§3.3）中每一行均有对应测试或 golden 断言；
- [ ] `script/run-gradle-targeted-tests.sh --tests CCodegenTest,CGenHelperTest,CVtableCodegenTest,CBodyBuilderPhaseCTest,CCodegenEngineMethodUsageSessionTest,FrontendAnnotationUsageAnalyzerTest,FrontendClassSkeletonAnnotationTest,GdScriptUnitTestCompileRunnerTest,GdScriptEngineVirtualOverrideRuntimeTest` 全量通过（engine 项不得 skip 充过）；
- [ ] 无用户 `_ready` / 用户 `_ready` 仅在父类 / coroutine `_ready`（引擎与内部双路径）/ 三级继承链（A 有、B 无、C 有）/ 无 initializer onready / derived-first 模块输入 六种形态各有至少一个断言；
- [ ] 交叉场景：构造期 fail-closed 三子场景（基类 `_init` 虚调用 `_ready` / `_init` 内显式 READY 通知 / `_init` 内 add_child 进已 ready 父节点）、继承用户 `_ready` × 派生独有 onready（wrapper 函数字段分发到派生链）、coroutine × 非引擎入口（thunk prologue）、recreate × 手调（不补执行）× request_ready（重放）、混合 super × request_ready/recreate（不重放，差异验收）；
- [ ] re-ready 双形态：移除再入树不重放、`request_ready()` 重放且旧值（含 RefCounted/容器）正确释放；
- [ ] 双入口一致性：引擎路径（组合 impl）与非引擎首调路径（prologue）各自只执行一次 onready，super 调用不触发第三次；
- [ ] frontend LIR → XML → parse → C codegen 完整链路回归：onready 分流在序列化 roundtrip 后保持不变（annotation map 保真 + 分流判定来自计划快照）；
- [x] 双 codegen 会话回归：同一 `LirModule` 两次生成，onready 分流与默认 helper 合成结果一致（Step 1 已验证计划事实与既有默认 helper 幂等；Step 2-3 已验证 C 分流与 `_onready_field_default_` helper 跨会话恰一份且 apply body 逐字节一致）；
- [ ] 无 onready 链的类：不生成链函数/适配器/组合 impl，get_virtual/call_virtual 分发段 golden diff 为空（prologue 注入由本类或后代链事实单独控制，不纳入本条）；flags 位域迁移属全局统一变化，单独断言位运算形态与 destructed 语义不变；
- [ ] 热重载 recreate 三 scenario（仅 recreate / recreate+手调 / recreate+request_ready）通过（`GodotEditorHotReloadIntegrationTest` 类内锚点）。

---

## 6. 风险与控制

| 风险 | 等级 | 控制 |
| --- | --- | --- |
| 构造期（`_init` 链完成前）`_ready` 被触发，对未 first-write 的派生字段槽做 overwrite → 销毁垃圾值 UB + 后续构造 first-write 泄漏。双入口：内部虚调用走 prologue；`_init` 内显式 `notification(NOTIFICATION_READY)` / add_child 进已 ready 的父节点走组合 impl（引擎 READY 分发不检查构造状态） | 高 | prologue 与组合 impl **双入口**均受 `CTOR_COMPLETE` 守卫（组合 impl 守卫失败时仅转发用户 `_ready`，不置 DONE、不 apply）；`CTOR_COMPLETE` 由最派生类 notification 在 constructor 链结束后置位；`onready_ctor_window_fail_closed.gd` 三子场景锚点（含 add_child 路径的「不自动补执行」断言）；守卫条件写入 Step 6/7 golden |
| 函数字段 ABI 不兼容：链函数签名 `void (<C>*)` 与字段 `void (*)(<Root>*)` 在非根类上不兼容，裸强转消除诊断但保留间接调用 UB | 高 | 每类根签名适配器 `<C>__gdcc_onready_apply_root`（受控 offset-0 转换，与 trampoline 同一豁免）；禁止裸强转写入验收；适配器进段首原型与符号登记 |
| 非引擎入口按声明类分发导致派生类链漏执行 | 高 | wrapper 根段 `_gdcc_onready_apply` 函数字段按实例实际类型分发（不改 vtable）；prologue 注入条件为本类或后代链事实；`onready_inherited_ready_derived_onready.gd` 锚点；Step 7 golden 覆盖「声明类无链事实但后代有」 |
| coroutine `_ready` 内部路径（direct/vtable/super）绕过同步 shell 导致 prologue 漏执行 | 高 | prologue 注入 start thunk 首次 `mco_resume` 之前（全内部路径共同入口）；组合 impl 仍转发 shell 保 `release_object` 语义；coroutine 双路径锚点 |
| 组合 impl 转发目标解析错误（声明类 vs 最派生类）、漏转发或漏 `(Decl*)` 转换 | 高 | Step 1 resolve helper 返回声明 owner 并专项单测；Step 6 golden 覆盖「`_ready` 仅在父类」与「无用户 `_ready`」场景 |
| 组合 impl 误走 ptrcall（userdata 被当 fat 函数指针）或 flags 字面访问在非根类编译失败 | 高 | §3.2-6 明确 dispatcher 直接调用形态（含 `(void)p_args/r_ret`）与根段 helper 渲染；Step 6 golden 断言 |
| 组合 impl 置位/apply/转发顺序错误（重入递归或 prologue 失效） | 中 | §3.2-6 固定形态（CTOR_COMPLETE 守卫 → 置位 DONE → apply → 转发）写入验收；golden 断言指令顺序 |
| ready-time overwrite 与构造期 first-write 混用导致泄漏/double-free | 高 | ready 路径统一 `moveOwnedCallIntoSlot` + §3.2-3 三条不变量；三类形态 golden 断言；`CTOR_COMPLETE` 保证执行时字段已初始化 |
| 「源码 initializer 是否存在」事实被默认值合成覆盖（含同一模块被第二个 codegen 会话 re-prepare 的污染路径） | 高 | Step 1 计划快照先于 `generateDefaultGetterSetterInitialization` 且位于 `CGenHelper` 构造之后；回填当场留下持久 provenance 标记；模板渲染期禁止反读 `initFunc != null`；双 codegen 会话回归 |
| 跨类 static 引用（链函数/适配器被组合 impl / create_instance 引用）在 derived-first 模块输入下「先用后声明」编不过 | 高 | §3.2-4 段首统一 `static` 原型合同（含适配器与组合 impl）+ derived-first 编译 smoke |
| flags 位域改动破坏 destructed 守卫（原整字节赋值/真值判断吃掉新增位）；字段插入 `_object` 与 `_vtable` 之间破坏冻结布局 | 中 | `|= 1` / `& 1` 位操作改造与字段顺序（`_vtable` 之后）写入 Step 5 验收；访问收口 helper；既有析构测试回归 |
| prologue 与组合 impl 双入口重复执行 | 中 | DONE 置位先于 apply；§5 双入口一致性断言（引擎分发 + super 调用总计只执行一次） |
| DONE 语义被误读为「成功完成」（initializer 失败后的重试预期） | 低 | §3.2-5 明确 DONE = 已尝试；失败不清位不重试；DONE 不阻止组合 impl 重放 apply |
| recreate 后 flags 状态错误（手调覆盖已恢复 storage 值 / request_ready 不重放新代码 / 旧代适配器地址悬空） | 中 | recreate 置 `CTOR_COMPLETE|ONREADY_DONE` 且按链事实重写函数字段（为真写本代适配器、否则 NULL）；热重载三 scenario 锚点 |
| 新符号与用户成员/既有 synthetic 撞名 | 中 | 全部新符号显式 `registerFileScopeSymbol`；组合 impl 与适配器挂 `_gdcc_` 保留前缀；保留前缀 `_onready_field_default_` 落点 `FrontendSyntheticPropertyHelperSupport` |
| 链解析依赖 `ClassRegistry` 的 GDCC 类判定，path-based / 跨模块父类形态误判或环状输入死循环 | 中 | helper 显式终止于 engine/未知父类并带 visited set（含根类解析 helper）；单元测试覆盖四种链形态与环输入 |

---

## 7. 已知差异与后续工作

### 7.1 与 Godot 的已知行为差异（本方案接受并记录）

- **非引擎分发路径的 `_ready` 重放不重放 onready**：gdcc 内部 vtable/`call_method`、纯 GDCC 无 ScriptInstance 对象上的外部 `Object.call("_ready")` / `Callable` 调用，**首次**经 prologue 按实例实际类型执行一次 onready（DONE 守卫），之后重复调用不再重放；Godot 经 `GDScriptInstance::callp` 的同名调用每次都重放。`request_ready()` 的重放承诺**仅适用于 READY 实际进入组合 impl 的路径**（见下行混合继承的例外）。（对象上挂有 GDScript 时，`Object.call` 会经 `GDScriptInstance::callp` 重放 GDScript 自己的 `@implicit_ready`，GDCC 侧仍按上述规则。）
- **构造期内的 `_ready` 触发不执行 onready**（分三条路径记录）：(a) 手动/虚调用 `_ready()`、(b) `_init` 内显式 `notification(NOTIFICATION_READY)`：双入口 fail-closed 跳过，DONE 不置位，这两条路径不清引擎 `ready_first`，之后真正入树时引擎仍发 READY 并正常应用；(c) `_init` 内 `add_child` 进已 ready 的父节点：经 `_propagate_ready` 同步发 READY 且先清 `ready_first`，是唯一一次自然 READY——守卫跳过 apply 后**构造完成不自动补执行**，onready 字段保持构造期默认值，直到 `request_ready()` 后再次入树才应用 initializer 值。Godot 的两阶段构造（全部字段默认值先于任何 `_init`）使这些路径都能安全执行 implicit ready；gdcc constructor 的交错结构使守卫成为必需。另注：守卫失败路径上转发的用户 `_ready` 只允许读已完成 first-write 的祖先槽，读派生字段仍是未初始化访问（这是构造期触发 `_ready` 的固有风险，与 onready 无关）。
- **混合 GDCC/GDScript 继承**：GDScript 子类覆写 `_ready` 且调用 `super._ready()` 时，super 经 MethodBind 进入 GDCC 侧 `_ready` 入口，prologue 在 super 调用点**晚执行**实例的完整 GDCC 链（经 wrapper 函数字段分发；执行时点晚于脚本子类 onready 与脚本 `_ready` 体开头，顺序与 Godot 纯脚本链不一致，但优于不执行）。**该路径没有重放保证**：脚本 `_ready` 经 `callp` 成功处理后引擎短路、不再 fallback 到 extension virtual，组合 impl 不执行——重复 READY（`request_ready()`）及 recreate 后的 READY 经 super 入口时 DONE 已置位，GDCC 链不会重放（recreate 后亦不补执行新 initializer）。GDScript 子类不调 `super._ready()` 时 GDCC 链上的 onready **不执行**；GDScript 子类只有 onready、无 `_ready` 时，执行顺序为「派生脚本 onready 先于 GDCC 基类 onready」，与 Godot 纯脚本链相反。完整对齐需要把 ClassDB 按名入口与 ScriptInstance ready 生命周期纳入统一设计，属架构级变更，本方案保留为待确认后续项（见 §7.3）。
- **热重载后首调**：recreate 置位 DONE（继承前代「ready 已发生」事实），reload 后手动 `_ready()` 不再补执行 onready，已恢复的 storage 值不被覆盖；纯 GDCC 对象的 `request_ready()` 仍经组合 impl 按新代码重放（混合继承 super 路径除外，见上条）。
- `@onready` + `@export`：行为自然对齐 Godot（export 先写、ready 覆盖），但 Godot 的 `ONREADY_WITH_EXPORT`（默认 ERROR）诊断本方案不实现，不得据此声称诊断层面兼容。

### 7.2 边界

- 跨模块 GDCC 父类的 onready 链不支持（与 vtable 跨模块限制同级）；父类为 engine 类时链递归终止，engine 类无 gdcc onready 概念。
- `is_node_ready()` 在 onready initializer 执行期间为 `true`（Node 先发 READY 前已更新状态），本方案组合 impl 时点与之兼容，但该行为以引擎为准，gdcc 不做额外承诺。

### 7.3 后续工作（不在本方案范围）

- **混合 GDCC/GDScript 继承的完整 onready 对齐**（架构级，待确认后另行立项）：需统一 engine virtual、ClassDB 按名入口与 ScriptInstance ready 生命周期；不能简单搬到 extension `NOTIFICATION_READY` 回调（该回调发生在 Node 内部 `_ready` 分发之后，时点太晚）。
- 解除 `@onready` initializer 中 `$` / `%` node literal 的 compile gate：onready ready-time 求值恰好提供了 Godot 语义要求的 deferred 上下文，解除后 `@onready var c = $Child` 才可用；需与 `frontend_node_literal_implementation.md` 的 DEFERRED 边界设计协调，另行立项。
- 视需要放宽 onready initializer 对同类实例成员/`self` 的访问限制（现 MVP fail-closed 规则在 ready-time 上下文中语义上已可成立）。
- `@onready` + `@export` 的 `ONREADY_WITH_EXPORT` 诊断对齐。
