# Frontend Static Var Implementation

> Updated: 2026-08-29
>
> 本文档是脚本类 `static var` 的长期事实源。
> 只描述当前已实现的语义、合同、边界和测试锚点。
> 不再记录阶段性步骤、完成进度或实施流水账；若合同变化，应直接改写当前状态。

## 文档状态

- 状态：事实源维护中（declaration / initializer / 读写 / 继承共享存储 / 两段式全局初始化 / C backend 闭环已落地）
- 更新时间：2026-08-29
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/**`
  - `src/main/java/gd/script/gdcc/scope/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/main/c/codegen/template_451/**`
  - `src/test/java/gd/script/gdcc/**`
  - `src/test/test_suite/unit_test/script/member/static_var_*.gd`
- 关联文档：
  - `frontend_rules.md`
  - `diagnostic_manager.md`
  - `frontend_property_init_lowering_implementation.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_top_binding_analyzer_implementation.md`
  - `frontend_complex_writable_target_implementation.md`
  - `frontend_lowering_func_pre_pass_implementation.md`
  - `frontend_lowering_skeleton_pre_pass_implementation.md`
  - `gdcc_facing_class_name_contract.md`
  - `superclass_canonical_name_contract.md`
  - `runtime_name_mapping_implementation.md`
  - `doc/module_impl/backend/load_static_implementation.md`
  - `doc/module_impl/backend/backend_ownership_lifecycle_contract.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
- 明确非目标：见 §7

---

## 1. 维护合同

- 本文档覆盖脚本类 `static var` 在 shared semantic、CFG / body lowering、LIR、C backend 与 runtime ABI 之间的长期合同。
- 本文档只描述已经由代码承担的当前事实，不描述历史修复步骤。
- 通用 ownership / retain-release 规则以 `backend_ownership_lifecycle_contract.md` 与 `gdcc_ownership_lifecycle_spec.md` 为准，本文只记录 static 存储特有的生命周期分叉。
- 通用 writable-route / named-subscript 规则以 `frontend_complex_writable_target_implementation.md` 为准，本文只记录 static 存储路由与其相关 provenance。
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_rules.md`
  - `diagnostic_manager.md`
  - `frontend_property_init_lowering_implementation.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `gdcc_facing_class_name_contract.md`
  - `doc/module_impl/backend/load_static_implementation.md`
  - 与 static property skeleton、binding、lowering、`LoadStaticInsnGen` / `StoreStaticInsnGen`、`CCodegen` 生命周期入口、`entry.c.ftl` / `entry.h.ftl` 直接相关的代码注释

---

## 2. 当前支持面

脚本类 `static var` 已进入 compile-ready 支持面。当前正式支持：

- 无 initializer 的类型默认值，以及显式 initializer
- 类内裸标识符读写（含复合赋值）
- `ClassName.name` 限定读写（含复合赋值）
- `self.x` / `obj.x` 实例语法访问：操作声明类共享存储，发 `sema.static_access_via_instance` warning，不阻断编译
- 子类访问基类 static var 时共享声明类同一份存储，不得按子类复制
- inner class static var（canonical identity 下的成员；backend 只消费 canonical name）
- static initializer 中访问 static var / static func、全局常量、singleton、utility、已支持 type-meta route
- static 容器 subscript 读写与写回（`values[i]` / `obj.values[i]` / `ClassName.values[i]`）
- static 容器上的 const 方法调用与引用载体原位 mutation（如 `names.size()` / `names.append(...)`）
- destroyable 类型（`String` / `Array` / `Dictionary` / object）的 backing 存储、覆盖写与 `deinitialize()` 清理

当前覆盖的典型示例：

- `static var count: int`
- `static var label: String = "s"`
- `static var frames: int = Engine.get_frames_drawn()`
- `Worker.shared`、`self.shared`、`obj.shared`
- `SubWorker.values[i]`（起始类为子类，存储仍落在声明 owner）
- `static var names: Array[String] = []` 上的 `names.append(...)`

以下内容不属于当前合同：

- static setter / getter（setget）
- `_static_init()` 用户函数的自动调用
- `@static_unload`
- class-level `const`
- static var 的 GDExtension ClassDB property 注册
- `@export` / `@onready` 用于 static var
- 值语义 / `Variant` 载体上 mutating 调用的 write-back 完整路径（当前 fail-fast）

---

## 3. Shared Semantic Contract

### 3.1 存储归属与继承

- 静态存储属于**声明类本身**。子类通过裸标识符、`ClassName.name` 或实例语法访问基类 static var 时，必须命中同一份基类 backing，不得按子类复制。
- frontend / LIR 只发布访问起始类名（当前类、receiver 静态类型或 type-meta canonical name）。
- 声明 owner 由 `ClassRegistry.findStaticPropertyInHierarchy(...)` 沿继承链解析；C backend load/store 必须消费该查询，不得在 frontend canonicalize 成 owner class。
- 子类重新声明继承链上的同名 property（static 或 instance、GDCC 或 engine）时，skeleton 发 `sema.class_skeleton`，从 class skeleton 删除该 property，并跳过该 member subtree。只约束新增 static 声明面，不改变既有 non-static property 的遮蔽行为。
- 跨 kind（static var × 继承 function / signal）冲突当前不检查。

### 3.2 Initializer 与类型

- static property initializer 使用 `ResolveRestriction.staticContext()`。
- 可访问 static var / static func / 全局常量 / singleton / utility / 已支持 type-meta route。
- 不得访问 `self`、同类 non-static property / method / signal。即使 shared lookup 找到这些成员，也必须 fail-closed（见 `FrontendPropertyInitializerSupport`）。
- `static var x := value` 与实例 property `:=` 同构：不做类型推导、metadata 落 `Variant`、type-check 发 `sema.type_hint`。不拒绝、不回写 typed slot。
- `LirPropertyDef.initFunc != null` 当且仅当源码存在显式 initializer。backend 不得为无 initializer 的 static property 合成 `_field_init_` helper。

### 3.3 Binding 与 predicate

- static property 必须通过 `PropertyDef.isStatic()` 识别。CFG / body lowering 的 `isStaticPropertyBinding` 只能检查 `declarationSite() instanceof PropertyDef && isStatic()`，不得回退到 parser `VariableDeclaration`。
- `ClassName.name` 命中 GDCC static property 时，chain binding 发布 `FrontendBindingKind.PROPERTY` 的 RESOLVED static load/store route。
- 既有只读常量 trace（engine / builtin / global constant / enum）必须继续使用 `FrontendBindingKind.CONSTANT`。不得把共享 `resolvedStaticLoadTrace` 整体改成 `PROPERTY`。
- 非 static property 经 `ClassName.name` 访问继续 fail-closed。
- class property（含 static）不属于 `FrontendVariableAnalyzer` 的 callable-local inventory。

### 3.4 Instance receiver 访问

- `self.x` / `obj.x` 命中 static property 时允许访问，操作声明类共享存储，与 Godot 行为一致。
- chain binding 在 access anchor 上发 warning：
  - category：`sema.static_access_via_instance`
  - owner：chain binding（与 `sema.member_resolution` / `sema.call_resolution` 同层）
  - 消息模板：`The property '<name>' is static but was accessed from an instance. Instead, it should be accessed from the type: '<OwnerClass>.<name>'.`
- warning 与 `resolvedMembers()` 中的 `RESOLVED(PROPERTY)` 并存，不阻断 compile gate。
- 裸标识符与 `ClassName.name` 路径不发此 warning。
- Godot 对 static **func** 经实例调用有 `STATIC_CALLED_ON_INSTANCE` warning，对 static **var** 目前没有对应 warning（godot#106364）。gdcc 主动补齐 var 版 warning：比 Godot 更严格，但不改变共享存储行为。

### 3.5 Annotation

- `@export` 家族 × static property：`FrontendAnnotationUsageAnalyzer` 发 `sema.annotation_usage` error，消息 `@<name> cannot be used on static property '<name>'`（`@<name>` 为实际注解名，覆盖裸 `export` 与全部 export variant）。
- `@onready` × static property：既有拒绝保持不变，消息 `@onready cannot be used on static property '<name>'`。
- gdcc 没有 parser 层注解校验；Godot 在 parser 层拒绝 export / onready 用于 static var。gdcc 统一由 annotation-usage owner 承担，不新增诊断 category。
- 尚未实现的 export 成员（`export_category` / `export_group` / `export_subgroup` / `export_storage` / `export_custom` / `export_tool_button`）与其他未识别 annotation 继续走 `sema.unsupported_annotation`。
- 背景：`@export` 只影响实例 property 的 ClassDB 注册（usage / hint / hint_string / class_name），而 static property 本就不注册 ClassDB，拒绝语义与 Godot 一致且无副作用。

---

## 4. CFG / Lowering Contract

### 4.1 读写形态

静态存储读写必须生成：

- 读：`LoadStaticInsn(startClass, name)`
- 写：`StoreStaticInsn(startClass, name, value)`

不变量：

- frontend 只发布起始类名；声明 owner 由 backend hierarchy lookup 解析。
- instance receiver 表达式仍须正常 lower 并求值（可能含副作用，如 `get_obj().x`）；leaf 读写重定向到 static 存储。
- static receiver leaf 不得残留 `LoadPropertyInsn` / `StorePropertyInsn`。
- `StaticPropertyCommitStep` 只能作为 terminal step；non-terminal static step 必须 fail-fast。

### 4.2 Property initializer helper

- 显式 static initializer 进入 `FunctionLoweringContext.Kind.PROPERTY_INIT`。
- helper 必须 hidden、static、零参数，不注入 `self`，返回类型等于 property 类型。
- helper 结果由 module 级两段式全局 static 初始化写回共享 backing，不走实例 constructor-time apply。
- pre-pass 只建 shell；CFG / body pass 物化真实 helper body、`entryBlockId` 与 `ReturnInsn`。

### 4.3 Static 容器与 subscript

- chain binding 把 RESOLVED 容器 property 事实发布到 `AttributeSubscriptStep` 锚点，作为 body lowering 的 container provenance。subscript 步骤自身的表达式类型仍是元素类型，不得被容器类型劫持。
- `containerSourceType` 填充声明容器类型；dynamic 容器为 `Variant`。
- static 容器路由：

  ```text
  LoadStaticInsn(startClass, containerName)
    → typed / generic subscript
    → 必要时 StoreStaticInsn 写回
  ```

- named-base scratch 必须按已发布的 `containerSourceType` 分配；未类型化 static（`Variant`）保持 Variant 槽。
- 引用载体按既定规则可在编译期跳过写回；值语义 / `Variant` 载体的 mutating 路径不得静默丢写回。
- type-meta head `ClassName.values[i]`：
  - 头部成员以 `MemberLoadItem(baseValueIdOrNull=null)` 进入，body lowering 发一条 `LoadStaticInsn` 得到容器值
  - 后续下标按普通 `base[key]` 构造（`memberNameOrNull=null`）
  - writable route 与裸 `values[i]` 同构：`STATIC_CONTEXT` root + 终端 `PROPERTY` commit step
  - `SubscriptLeaf.baseOrReceiverSlotId` 保持非空，由 `LoadStaticInsn` 结果槽位 backing
  - 常量容器等非静态 type-meta 下标成员继续 fail-fast
- `ClassName.shared = v` / `ClassName.shared += v` 把 type-meta route head 标记为 route-head-only，不发布失败的普通值类型事实。

### 4.4 Static 容器方法调用

- `FrontendCfgGraphBuilder.appendCallReceiverCommitSteps` 对 `STATIC_CONTEXT` root + 无容器槽 `PROPERTY` leaf：
  - const 调用（`mayMutateReceiver == false`）或引用载体 receiver（原位变更，写回为 no-op）时跳过 leaf promotion；leaf 自身即终端存储边界
  - 值语义 / `Variant` 载体的 mutating 调用保持 promotion，走既有 fail-fast，而不是静默丢写回

### 4.5 相关 typed 实例 named-subscript

已解析的非 static GDCC 实例容器（`containerSourceType` 非 `Variant`）走 `LoadPropertyInsn` + typed 下标 + 无条件 `StorePropertyInsn` 写回。engine property 与 dynamic 成员保持 Variant named 路由。该合同以 `frontend_complex_writable_target_implementation.md` 为准，不在本文重复。

---

## 5. C Backend And Lifecycle Contract

### 5.1 Backing 存储

- 每个脚本类 static var 对应一个 **C 文件级 backing 变量**，C 类型复用 `helper.renderGdTypeInC(property.type)`。
- 定义只出现在 `entry.c.ftl`；`entry.h.ftl` **不**声明 backing `extern`。`entry.h.ftl` 仅负责从实例 struct 排除 static property。
- 命名公式集中在 `CGenHelper`，load/store/init/deinit/冲突校验必须共享，禁止各处自行拼名：
  - backing：`gdcc_static_<canonicalClassName>_<propName>`
  - 默认值入口：`gdcc_static_defaults_<canonicalClassName>`
  - initializer 入口：`gdcc_static_initializers_<canonicalClassName>`
- class identity 分量使用**原始 canonical name**（与 `<Class>_object_ptr` 同 surface），不走 `cIdentifier()`。property 分量直接用源码属性名。
- 该 surface 不提供全局单射保证。理论碰撞（如 `A_B.c` vs `A.B_c`、用户函数恰好同名）由 `CCodegen.validateFileScopeSymbolsDisjoint` 兜底：冲突时报告双方符号与来源并 fail-fast。
- coroutine / lambda helper 符号族不在本次冲突检查范围。
- caller helper 命名 `<class>_<argc>_arg_ret_<type>` 不含方法名；static init helper 与用户方法同 arity / 同返回类型时仍可能碰撞。严格化命名属于未来统一加固，不在本文合同内修补。

### 5.2 两段式全局初始化

每个含 static var 的类生成两个无参数入口。`initialize()` 在**全部类注册完成之后**（含隐藏协程状态类）调用它们：

1. 先按类间顺序调用所有 `gdcc_static_defaults_*`
2. 再按同一顺序调用所有 `gdcc_static_initializers_*`

类间顺序固定为 base-before-derived（按继承拓扑；无继承关系时按 module class 顺序）。初始化级别为 `GDEXTENSION_INITIALIZATION_SCENE`。

分段理由：static initializer 可以通过 `ClassName.name` 读其他类的 static var。若默认值与 initializer 混在同一个 per-class 函数里，module 词法序可能让早初始化的类读到尚未物化的 managed 默认值。分段后任何 initializer 读到的至少是类型默认值。

不保证跨类 initializer 的相对先后；Godot 同样不保证“对方 initializer 是否已执行”。

- **默认值段**：对所有 static var（无论有无 source initializer）按声明顺序物化类型默认值。backing 是 zero-initialized 文件级存储，这些写入不得销毁旧值。typed `Array` / `Dictionary` 必须走 builtin constructor，以保留元素类型 metadata。
- **initializer 段**：仅对 `initFunc != null` 的 static var 按声明顺序调用 hidden static `_field_init_<name>()`，destroy-then-move 覆盖已物化的默认值。
- **`deinitialize()`**：按初始化逆序、在 runtime registry teardown 之前销毁 destroyable backing。对齐“卸载即清空”效果，但不引入 `@static_unload` 注解。

### 5.3 与实例路径隔离

static property 必须整体跳过：

- 实例 struct 字段
- getter / setter / 默认 `_field_init_` 合成
- constructor-time apply helper
- destructor 实例字段清理
- ClassDB property 注册

实例路径生成的 C 不得出现 `self-><static_name>`。

### 5.4 Load / store 与生命周期

- `LoadStaticInsnGen` / `StoreStaticInsnGen` 对 GDCC script class 沿 hierarchy 定位声明 owner，按 `gdcc_static_<owner>_<name>` 读写。
- load 按 `BORROWED` 存储读处理；需要 retain 的场景与实例 property load 一致。
- store 走统一 slot-write：旧值 release/destroy，BORROWED source retain。backing 生命周期长于任意实例，没有 first-write 捷径。
- OBJECT static 使用与实例 property 相同的 fat-pointer 存储形态。
- 三条写路径都必须满足 `gdcc_ownership_lifecycle_spec.md`：
  - 默认值首写（zero-init 后直接物化）
  - initializer 覆盖写（先销毁旧值再 move）
  - 运行时覆盖写（同实例 property store 的 release-then-store）
- 所有生命周期 C 代码必须经 `CBodyBuilder` slot-write API 生成，禁止手写生命周期片段。
- engine / builtin / `@GlobalScope` 既有 load 分支不动；非 GDCC receiver 的 store 维持显式拒绝。

---

## 6. Diagnostic And Compile Gate Contract

- 脚本类 `static var` declaration 不再属于显式 compile blocker。
- 无 initializer 的 declaration 不产生 compile-surface 节点；有 supported initializer 时走统一 property-initializer 路径：锚定 `VariableDeclaration` 并递归扫描 initializer subtree。
- initializer 内仍未就绪的 blocker 继续阻断该 subtree，不得被 declaration 放行连带放行。
- `sema.static_access_via_instance` 是 warning，不属于 compile-only gate。
- `@export` / `@onready` × static 由 annotation-usage owner 报错，compile-only 不得再包装成 `sema.compile_check`。
- 继承冲突由 skeleton 发 `sema.class_skeleton` 并 skip subtree；后续 analyzer 只能沿用 skipped-subtree 合同。

---

## 7. Known Limitations And Non-Goals

当前保持 fail-closed 或 deferred：

- `_static_init()` 用户函数的自动调用（Godot 会在 initializer 之后调用；MVP 中它仍是普通 static func）
- static var setter / getter（setget）
- `@static_unload`
- class-level `const`
- static var 的 ClassDB property 注册
- 继承链上 static var 与 function / signal 的跨 kind 冲突检查
- 全模块 C 符号单射命名加固（含 caller helper 命名碰撞）
- coroutine / lambda helper 符号族纳入 file-scope 冲突检查
- 值语义 / `Variant` 载体上 static 容器 mutating 调用的完整 write-back
- property initializer 中访问同类 non-static property / method / signal / `self`
- 跨类 initializer 的具体执行先后（只保证默认值先于所有 initializer）

这些是有意保持的 MVP 边界，不是遗漏。

---

## 8. Regression Anchors

按功能分组，不记录执行历史。

### 8.1 Semantic / scope

- `FrontendClassSkeletonTest`：继承冲突、engine property 冲突、inner class 冲突
- `ClassRegistryTest`：`findStaticPropertyInHierarchy` / `findPropertyInHierarchy`
- `FrontendAnnotationUsageAnalyzerTest`：`@onready` / `@export` × static 拒绝
- `FrontendTypeCheckAnalyzerTest`：static initializer 类型检查、`:=` type hint、static-context negative path
- `FrontendBodyOwnerProceduresChainBindingTest`：`ClassName.staticVar`、继承、instance receiver warning、static 容器 subscript provenance、type-meta static 容器
- `FrontendStaticContextValueRestrictionTest` / `FrontendStaticContextFunctionRestrictionTest`：static context 可见性

### 8.2 Compile gate

- `FrontendCompileCheckAnalyzerTest`：有 / 无 initializer 的 static declaration 放行；initializer 内 blocker 仍阻断
- `ApiCompileDiagnosticsTest`：static property API compile 成功
- `ApiCompileTaskFailureStageTest`：static var 不再进入 frontend compile-blocker stage

### 8.3 CFG / lowering

- `FrontendLoweringFunctionPreparationPassTest`：static init shell 为 hidden / static / 零参数
- `FrontendLoweringBuildCfgPassTest`：static initializer CFG；static 容器 commit boundary
- `FrontendLoweringBodyInsnPassTest`：裸读写、instance-syntax、initializer、static 容器 subscript、type-meta head、reverse commit、容器方法调用
- `FrontendCfgGraphBuilderTest`：type-meta subscript 首步、冻结 access kind
- `FrontendWritableRouteSupportTest`：static / typed-instance named-subscript 对照

### 8.4 Backend / lifecycle

- `CLoadStaticInsnGenTest` / `CStoreStaticInsnGenTest`：GDCC static load/store、继承 owner、destroyable / object 生命周期、错误路径
- `CCodegenTest`：两段式初始化、协程 class registration 之后才跑 static init、符号冲突、helper signature、生成 C 无 `self-><static_name>`
- `CProjectBuilderIntegrationTest`：含 static 的模块真实 Zig 编译冒烟（Zig 不可用时跳过）

### 8.5 End-to-end fixtures

- `src/test/test_suite/unit_test/script/member/static_var_basic.gd`
- `src/test/test_suite/unit_test/script/member/static_var_instance_access.gd`
- `src/test/test_suite/unit_test/script/member/static_var_inheritance.gd`
- `src/test/test_suite/unit_test/script/member/static_var_destroyable.gd`

---

## 9. 长期风险与维护提醒

- raw canonical 拼接不是单射。全模块冲突检查是主防线，不是可删的防御性 guard。
- frontend 若把起始类名提前改成声明 owner，会破坏“IR 保留源码 receiver class”合同，并与 engine constant 的 inherited static lookup 形态分叉。
- `CONSTANT` 与 `PROPERTY` binding kind 选错会把 static var 当只读，或把只读常量当可写。新增 static load 分支必须显式参数化 binding kind。
- static 容器方法调用不得把 terminal static leaf promotion 成 non-terminal commit step。
- typed instance named-subscript 写回不得按载体家族省略；那是另一条合同，不能回退污染 static 引用载体的可省略写回规则。
- 若未来要自动调用 `_static_init()`，必须插在全部 initializer 段之后，且不得破坏当前两段式默认值合同。
