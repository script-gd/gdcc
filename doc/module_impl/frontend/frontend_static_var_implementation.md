# Frontend Static Var Implementation Plan

> Updated: 2026-08-27
>
> 本文档是脚本类 `static var` 的**实施计划**（尚未进入事实源阶段）。
> 它记录目标语义、现状差距、分层设计与分阶段验收细则；每个阶段完成后勾选对应 checklist，
> 全部阶段完成后本文档必须改写为“长期事实源”风格（参考 `frontend_property_init_lowering_implementation.md` 的维护合同），
> 并同步更新 `frontend_rules.md:54` / `frontend_rules.md:116` 等相关条款。
>
> 本文档已经过一轮 review-expert-c 审阅，审阅结论已并入 §2-§5 的修正（见 §8.4）。

## 1. 目标与范围

### 1.1 目标

让脚本类 class-level `static var` 从“frontend 已识别但 compile target 明确拒绝”
（`frontend_rules.md:54` / `frontend_rules.md:116`、
`FrontendCompileCheckAnalyzer.isStaticClassPropertyDeclaration`）升级为
compile-ready MVP 支持面，覆盖声明、初始化、读取、写入与 C backend 存储闭环。

### 1.2 语义基线（对齐 Godot 4）

以 Godot `gdscript_compiler.cpp` / `gdscript.cpp` 的实际行为为基线：

- 静态存储属于**声明类本身**；子类访问基类 static var 时共享同一份基类存储，不得按子类复制。
- 初始化分两个全局阶段：先把**所有类**的 static var 置为类型默认值，再应用显式 initializer
  （对应 Godot 合成的 `@static_initializer`；全局分段的理由见 §3.2）。
- static initializer 允许非恒定表达式与函数调用，但运行在 static context：
  可访问 static var / static func / 全局常量 / singleton / utility；不得访问 `self`、
  同类 non-static property / method / signal（与既有 property initializer MVP 边界一致，
  见 `FrontendPropertyInitializerSupport`）。
- 读取：类内裸标识符、`ClassName.name` 限定访问；写入：同样的两种左值形态（含复合赋值）。
- **instance receiver 访问**（`self.x` / `obj.x`，2026-08-27 用户决策，见 §8.2）：允许，
  操作声明类共享存储，与 Godot 行为一致；同时发一条 warning（不阻断编译），
  提示应改为 `ClassName.name` 访问。规则与验收见 §3.4 / §3.5。
- 子类重复声明同名 static var：**现有代码不会拒绝**（复核结论：子类 direct value 直接遮蔽继承成员，
  `ClassScope.defineDirectValue` 只查同类重复，`LirClassDef.addProperty` 无校验）。
  因此本计划**新增**一条 skeleton 层校验：source static var 与继承链上同名成员冲突时发
  `sema.class_skeleton` 并跳过该 member subtree（仅约束新增 static var 声明面，
  不改变既有 non-static property 的遮蔽行为，见 §3.3.2）。
- `static var x := value`：与实例 property `:=` 完全同构——不做类型推导、metadata 落 `Variant`、
  type-check 发 `sema.type_hint` 提示补显式类型（`frontend_rules.md:119-121`）。
  不拒绝、不推导；这是 MVP 冻结规则。

### 1.3 明确排除（保持 fail-closed 或 deferred）

- `@export` 等 per-instance annotation 用于 static var（阶段 0 已核查，规则见 §3.3.1：
  `@export` 与 `@onready` 均在 `sema.annotation_usage` 显式拒绝，对齐 Godot parser 行为；
  其余 annotation 走既有 `sema.unsupported_annotation` 路径）。
- static var 的 setter/getter（setget）语义。
- `_static_init()` 用户函数的自动调用（Godot 会在 initializer 之后调用；MVP 先不特殊化，
  保持为普通 static func，见 §9 后续项）。
- class-level `const`（继续遵守 `frontend_rules.md:73` 的 MVP 排除）。
- `@static_unload` 注解。
- static var 的 GDExtension ClassDB property 注册（static var 不注册为 engine property，
  模板现有 `<#if !property.static>` 跳过逻辑保持不变）。

## 2. 现状盘点（代码锚点）

### 2.1 已就绪的部分

- **Parser/AST**：外部 `gdparser` 已产出 `VariableDeclaration.isStatic()`；无需改动 parser。
- **Skeleton/metadata**：`FrontendClassSkeletonBuilder`（约 :239-275、:378-393）已把 static flag
  写入 `LirPropertyDef.isStatic`（`lir/LirPropertyDef.java:12-74`）。
- **Scope/静态上下文**：`ClassScope` 静态上下文解析、`ScopeValue.staticMember` 已就绪并有
  `FrontendStaticContextValueRestrictionTest` / `FrontendStaticContextFunctionRestrictionTest` 锚定。
- **Shared semantic initializer 分析**：`FrontendPropertyInitializerSupport.restrictionFor(...)`
  已对 static property initializer 使用 `ResolveRestriction.staticContext()`；
  `isSupportedPropertyInitializer(...)` 不排除 static。
- **CFG writable-route publication**：`FrontendCfgGraphBuilder.buildIdentifierOpaqueRoute`
  （:3373-3413）已用**正确的** `isStaticPropertyBinding`（:4161-4165，检查
  `declarationSite() instanceof PropertyDef && isStatic()`）为裸 static property 发布
  `STATIC_CONTEXT` root + `PROPERTY` leaf。
- **Writable route leaf 消费**：`FrontendWritableRouteSupport.StaticPropertyLeaf` 的读
  （`LoadStaticInsn`，:128-135）与写（`StoreStaticInsn`，:187-194）分支已存在。
- **Lowering pre-pass shell**：`FrontendLoweringFunctionPreparationPass.createPropertyInitShell`
  （:613-632）与 `requireCompatiblePropertyInitShell`（:654-710）已支持 static 形态（无 self 参数）。
- **模板（部分）**：`entry.h.ftl` 实例 struct（:41-45）与 `entry.c.ftl` ClassDB property 注册
  （:125-134）已跳过 static property。

### 2.2 已确认的缺陷与硬阻断点（本计划要修复/拆除）

| # | 层 | 位置 | 现状 |
| --- | --- | --- | --- |
| B1 | body lowering | `FrontendBodyLoweringSession.isStaticPropertyBinding` :1223-1226 | **坏的 predicate**：property binding 的 `declarationSite` 是 `PropertyDef`（`ClassScope.toPropertyScopeValue` :323-332 → `FrontendBodyOwnerProcedures` :920-930），该 predicate 却查 parser `VariableDeclaration`，永远 false；裸 static 读会误入 `LoadPropertyInsn` + self（`FrontendOpaqueExprInsnLoweringProcessors` :84-95），裸写会在 `STATIC_CONTEXT` root 上炸 receiver 解析（`FrontendBodyLoweringSession` :497-507, :537-553） |
| B2 | compile gate | `FrontendCompileCheckAnalyzer.handleVariableDeclaration` :483-489 + `isStaticClassPropertyDeclaration` :1266-1271 | 任何 class-level static `var`（含无 initializer）一律 `sema.compile_check` |
| B3 | chain binding | `FrontendChainReductionHelper.reduceGdccStaticLoad` :853-888 | GDCC class 的 static load 除 static method reference 外一律 UNSUPPORTED |
| B4 | backend 默认 helper | `CCodegen.generateDefaultGetterSetterInitialization` :125-134 | static property 直接 `IllegalStateException` |
| B5 | backend init 校验 | `CCodegen.validatePropertyInitFunctionSignature` :389-449 | 强制“恰好一个 self 参数”，无 static 分支 |
| B6 | backend load/store | `LoadStaticInsnGen` :129-172 末尾 unsupported；`StoreStaticInsnGen` :17-22 整指令 unsupported | 无 GDCC script class 分支 |
| B7 | 模板实例路径 | `entry.c.ftl` :187-192（apply-helper 生成）、:214-223（constructor 逐个调用）、:232-246（destructor 销毁 destroyable） | **三处都没有 static guard**：static property 会引用实例 struct 中不存在的字段（`self->name`），且 apply 会按实例重复执行；`CCodegen.generatePropertyInitApplyBody`（:565-596）固定写 `self->` 并给 init helper 传 `self` |
| B8 | 继承冲突 | `LirClassDef.addProperty` :126-132、`ClassScope.defineDirectValue` :241-247 | 无跨层级 member 重名拒绝；子类同名声明静默遮蔽继承成员 |
| B9 | scope 查询 | `ClassRegistry` | 只有 `findStaticFunctionInHierarchy`，**没有** static property 的 hierarchy 查询，需要新增 |

### 2.3 读取路径的 owner 解析要求

裸标识符读取/写入当前以 `session.currentClassName()` 作为起始类
（`FrontendOpaqueExprInsnLoweringProcessors.java:86-91`、`FrontendBodyLoweringSession.java:555-566`）。
对“子类内访问基类 static var”，必须由 **backend/共享 helper 沿 hierarchy 定位声明 owner**
（与 `requireDeclaringStaticOwnerName` 的函数版模式一致，:1233-1242），
frontend 只发布起始类名；这样继承共享存储自然成立。

## 3. 分层设计

### 3.1 存储模型与符号命名（C backend）

- 每个脚本类 static var 对应一个 **C 文件级 backing 变量**，C 类型复用
  `helper.renderGdTypeInC(property.type)`；声明/定义集中在 `entry.h.ftl` / `entry.c.ftl`。
- 命名沿用存量 **raw 拼接约定**（2026-08-27 设计决策，见 §8.7），不引入新的编码方案：
  1. class identity 分量直接使用**原始 canonical name**（与 Godot/identity surface 一致，
     参照既有 `Outer__sub__Inner_object_ptr` 形态），不走 `cIdentifier()` 有损清洗；
     property 分量直接用源码属性名（与实例 struct 字段 `entry.h.ftl:43` 同约定）；
  2. backing 变量公式固定为 `gdcc_static_<canonicalClassName>_<propName>`；
  3. 符号名公式集中在 `CGenHelper` 单点发布，load/store/init/deinit 四处共享，禁止各处自行拼名。
- per-class static-init 函数属于 compiler-owned 符号，命名不得落在用户函数命名空间
  `${classDef.name}_${function.name}`（`func.ftl:2-12`）内：统一使用
  `gdcc_static_defaults_<canonicalClassName>` / `gdcc_static_initializers_<canonicalClassName>`
  这类 class-name 不在前缀位的形态（双入口的理由见 §3.2）。
- **全模块符号冲突校验是主防线**（不再是防御性 guard）：`CCodegen` 在 prepare 阶段收集全模块
  file-scope 符号（backing 变量、static-init 双入口、既有 `${class}_${func}`、模板固定符号
  `<Class>_class_create_instance` 等）统一查重，任何冲突 fail-fast 并报出冲突双方符号与来源，
  提示用户重命名。已知的理论碰撞面（如 `A_B.c` vs `A.B_c` 拼接歧义、用户函数恰好名为
  `gdcc_static_defaults_...`）由该校验承接——这与存量实例成员命名（`${class}_${func}` 同样
  无单射保证、`addFunction` 不查重）的风险水位一致；对整个命名表面（含存量实例成员）的
  严格化/单射编码留作未来统一加固，不在本计划内。

### 3.2 初始化模型（全局两段式）

- 每个含 static var 的类生成**两个无参数入口**（二轮复核：单个无参数入口无法被全局两段式
  分别调用）：`gdcc_static_defaults_<canonicalClassName>(void)` 承载默认值段，
  `gdcc_static_initializers_<canonicalClassName>(void)` 承载 initializer 段。
  两者都在 `initialize()` 中、全部类注册完成之后调用；`deinitialize()` 对称清理（见下）。
- **全局分段**（review 发现 5）：`initialize()` 先按类间顺序调用所有类的
  `gdcc_static_defaults_*`，再按同一顺序调用所有类的 `gdcc_static_initializers_*`；
  两段各自的类间顺序固定为 base-before-derived（按继承拓扑；无继承关系时按 module class 顺序）。
  理由：static initializer 可以通过 `ClassName.name` 读其他类的 static var，若默认值与
  initializer 混在同一个 per-class 函数里，module 顺序（源文件 lexical 序，非依赖序）可能让
  早初始化的类读到晚初始化类尚未物化的 managed 默认值（`String`/`Array`/object）。
  分段后任何 initializer 读到的至少是类型默认值，
  与 Godot“先全部默认、再按序 initializer”的语义基线一致。
  - 跨类 static 引用读取“对方 initializer 是否已执行”在 Godot 也不保证顺序，本计划同样不保证，
    文档注明即可（§5 风险表）。
- **默认值段**：对**所有** static var（无论有无 source initializer）按声明顺序物化类型默认值
  写入 backing 变量（复用实例 property 默认值物化路径；Variant 默认为 nil）。
- **initializer 段**：仅对**有 source initializer** 的 static var 按声明顺序调用其
  hidden static `_field_init_<name>()`，得临时值后按 destroyable 语义先销毁旧值再 move 写入。
- **initFunc 语义冻结**（review 发现 9）：`LirPropertyDef.initFunc != null` 当且仅当
  source 存在显式 initializer。backend **不得**为无 initializer 的 static property 合成
  `_field_init_` 默认值 helper（与实例侧 `CCodegen` :164+ 的默认合成行为分叉，仅限 static 分支）；
  默认值只由默认值段内联物化。这样 initializer 段的成员判定不需要额外 side fact。
- **deinitialize()**：按初始化逆序销毁 destroyable static backing 变量
  （对齐 `@static_unload` 的“卸载即清空”效果，但不引入该注解本身）。

### 3.3 Frontend semantic 变更（gate 保持关闭，见 §4 阶段序）

1. **annotation 边界**（阶段 0 结论已冻结，见 §8.1）：`FrontendAnnotationUsageAnalyzer`
   新增 `@export` × static property 校验，发 `sema.annotation_usage` error，消息模板对齐既有
   `@onready` 风格：`@export cannot be used on static property '<name>'`
   （Godot 原文为 parser 级 `Annotation "%s" cannot be applied to a static variable.`；
   gdcc 没有 parser 层注解校验，统一由 annotation-usage owner 承担，不新增诊断 category）。
   `@onready` × static 的既有拒绝保持不动；其余 annotation 已由 skeleton 的
   `sema.unsupported_annotation` 路径 fail-closed，无需新增规则。
2. **继承冲突校验（新增）**：skeleton/共享语义阶段对 source static var 声明沿继承链查同名成员，
   冲突发 `sema.class_skeleton` 并跳过该 member subtree（diagnostic + skip subtree 恢复合同，
   `frontend_rules.md:13-14`）。只约束 static var 声明面，不回溯既有 non-static 遮蔽行为。
3. **赋值/读取语义修复**：修正 B1 predicate（`FrontendBodyLoweringSession.isStaticPropertyBinding`
   改为检查 `PropertyDef`，与 `FrontendCfgGraphBuilder` :4161-4165 对齐），并核查
   `FrontendAssignmentSemanticSupport` 对 static property 不命中 constant read-only 拒绝路径
   （:450-476 / :630-674）。compound assignment（`+=` 等）一并覆盖。
4. **type-check 复用**：static initializer 的类型兼容检查、`sema.type_hint` 提示与实例 property
   完全同构；static `:=` 按 §1.2 冻结规则，不为 static 分叉第二套规则。

### 3.4 Chain binding 变更

- `reduceGdccStaticLoad` 新增 static property 分支：用新增的 `ClassRegistry`
  static-property hierarchy 查询（B9），命中产出 RESOLVED static load route，
  结果类型为 property 类型，declarationSite 指向 `PropertyDef`。
- **必须新增专用 trace / 显式参数化 binding kind**（review 发现 8）：
  `resolvedStaticLoadTrace`（:890-920）当前硬编码 `FrontendBindingKind.CONSTANT`，
  被 global enum / builtin constant / engine constant 等只读 route 共享，**不得**整体改成 `PROPERTY`；
  新分支使用 `FrontendBindingKind.PROPERTY`，既有 call site 全部保持 `CONSTANT`。
  验收需含 engine/builtin/global 常量赋值的 negative 测试（仍被拒）与脚本 static var 写 positive 测试。
- 非 static property 经 `ClassName.name` 访问维持现状 fail-closed，不在本计划放宽。
- **instance receiver × static property**（`self.x` / `obj.x`，§1.2）：`reducePropertyStep`
  在 resolved instance receiver（含 explicit `self`）上命中 static property 时，不再按现状
  走实例 property route 或 UNSUPPORTED，而是产出 RESOLVED static load/store route
  （`receiverType` 保留 receiver 的静态类型即起始类，declarationSite 为命中链上声明 owner 的
  `PropertyDef`；frontend/LIR 不预计算 owner，声明 owner 由 backend 沿 hierarchy 解析——
  与裸标识符路径的约定一致），
  并由 chain binding analyzer（member resolution 诊断 owner）在 access anchor 上发一条
  **warning**：category 固定为 `sema.static_access_via_instance`，消息模板对齐 Godot
  `STATIC_CALLED_ON_INSTANCE` 风格：
  `The property '<name>' is static but was accessed from an instance. Instead, it should be accessed from the type: '<OwnerClass>.<name>'.`
  warning 不阻断 compile gate（与既有 `sema.unsafe_call_argument` warning 共存策略一致）；
  新增 category 必须同步 `diagnostic_manager.md` 与 `frontend_rules.md` 诊断 owner 条款。
  读取结果的表达式类型为 property 类型，与 `ClassName.name` 路径一致。

### 3.5 Body lowering 变更

- 修复 B1 后，裸读自然落到既有 `LoadStaticInsn` 分支；把起始类名修正为声明 owner 的工作
  由 backend hierarchy 解析承担（§2.3），frontend 不再新增 owner 计算。
- 裸写 / 链式写：B1 修复 + 阶段 1 语义核查后，`StaticPropertyLeaf` 既有分支直接消费。
- instance receiver 路径（§3.4）：receiver 表达式**仍须正常 lower 并求值**
  （可能含副作用，如 `get_obj().x`），leaf 读写重定向到 static 存储；
  生成的 LIR 只允许出现 `LoadStaticInsn` / `StoreStaticInsn`（起始类名，声明 owner 由
  backend 沿 hierarchy 解析，与裸标识符约定一致），不得残留指向实例字段的
  `LoadPropertyInsn` / `StorePropertyInsn`。直接 leaf 读/写/复合赋值已在阶段 1 review 闭环中
  提前落地（见阶段 1.5 记录）。
- **（已解决，阶段 2.2）**：static 容器 property 的 attribute-subscript 路径（`obj.values[i]`
  读/写/复合赋值）曾经落入 `VariantGetNamedInsn` / `VariantSetNamedInsn`；现已实现 static
  外层容器专用路由：`LoadStaticInsn` 到容器临时值 → 下标读写 → 写回经
  `appendNamedBaseWriteback` / terminal `StaticContainerSubscriptCommitStep` 发
  `StoreStaticInsn`（reference carrier 按既定规则编译期跳过写回）。实现与测试明细见阶段 2.2。
- static `PROPERTY_INIT` context：验证 `FrontendLoweringBuildCfgPass` 与 body pass 对无 self 的
  static init shell 全链路可用（当前证据不足，作为阶段 2 首个验证项）；
  发现 self 假设则按 `requireCompatiblePropertyInitShell` 的 static 分支对齐。

### 3.6 C backend 变更

- `CCodegen.generateDefaultGetterSetterInitialization`：static property **整体跳过**
  getter/setter/initFunc 合成（B4 + §3.2 initFunc 冻结），不再 throw。
- `validatePropertyInitFunctionSignature`：增加 static 分支（0 参数、hidden、返回类型匹配），
  与 frontend `requireCompatiblePropertyInitShell` 合同镜像（B5）。
- **模板 guard（B7）**：`entry.c.ftl` 的 apply-helper 生成（:187-192）、constructor 调用
  （:214-223）、destructor 销毁（:232-246）三处加 `<#if !property.static>`；
  static 的初始化/清理只走 §3.2 的模块生命周期路径。
  验收必须含生成 C 的字符串断言：全模块不出现 `self-><static_name>`。
- `LoadStaticInsnGen` / `StoreStaticInsnGen`：新增 GDCC script class 分支（B6）——
  沿 hierarchy 定位声明 owner，按实例 property load/store 相同的 copy/ownership 语义读写
  backing 变量；engine/builtin/@GlobalScope 既有分支不动，非 GDCC receiver 维持显式拒绝。
- **对象生命周期**（review 发现 10）：static 存储生命周期超过任何实例，三条写路径必须分别满足
  `gdcc_ownership_lifecycle_spec.md`：默认值首写（zero-init 后直接物化）、initializer 覆盖写
  （先销毁旧值再 move）、运行时覆盖写（同实例 property store 的 release-then-store）。
  存储读出按 `BORROWED` 处理，需要 retain 的场景与实例 property load 一致。
  OBJECT 类型 static 使用与实例 property 相同的 fat-pointer 存储形态；
  所有生命周期 C 代码必须经 `CBodyBuilder` slot-write API 生成，禁止手写生命周期片段。

## 4. 分阶段实施与验收细则

**阶段序冻结**（review 发现 3）：遵守 `frontend_lowering_plan.md` §6——compile gate blocker 只能在
lowering 就绪、backend 就绪、文档与测试同步之后移除。因此 B2 的 gate 拆除固定在阶段 4，
阶段 1-3 的一切工作都在 gate 仍拒绝 compile 的前提下进行（shared `analyze(...)` 与 LIR/backend
单测不受 gate 影响，可独立验收）。

每阶段完成后运行所列 targeted tests（使用 `pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests ...`），
全部通过后才进入下一阶段。任何测试失败必须先查实现根因，不得改测试迎合（`AGENTS.md` 防御性条款）。

### 阶段 0：语义核查与设计冻结

- [x] 0.1 核查 Godot 对 static var 上 `@export` 的行为（结论：parser 层拒绝），
      gdcc 诊断决策见 §8.1。
- [x] 0.2 核查 Godot 是否允许 `self.static_var` / instance receiver 访问（结论：静默允许；
      gdcc 决策为允许 + warning，见 §8.2 / §8.8）。
- [x] 0.3 子类同名行为核查（review 已结论：现有代码静默遮蔽，无拒绝规则 → 新增 §3.3.2 校验）。
- [x] 0.4 本文档经 review-expert-c 审阅，中/高风险问题已并入修正（§8.4）。
- 验收：0.1 / 0.2 结论追加到 §8；无代码改动。

### 阶段 1：shared semantic 修复（gate 保持关闭）—— 已完成（2026-08-27）

- [x] 1.1 修复 B1 predicate（§3.3.3）；predicate 回归断言加入既有
      `FrontendLoweringBodyInsnPassTest`（裸读/裸写/复合赋值不再误走 `LoadPropertyInsn`），
      完整 LIR 形态断言留在阶段 2.2。
  - 实现：`isStaticPropertyBinding` 改为 `kind() == PROPERTY && declarationSite() instanceof PropertyDef && isStatic()`。
  - 测试：`runLowersBareStaticPropertyAccessThroughStaticInstructions`；因 compile gate 仍关闭，
    测试用 shared-semantic harness（参照 cast integration 测试形状）绕过 gate 直接驱动 lowering passes。
- [x] 1.2 继承冲突校验（§3.3.2）：top-level 与 inner class 各覆盖 happy/negative path。
  - 实现：`ClassRegistry.findPropertyInHierarchy` + skeleton **post-fill 校验 pass**
    （成员填充按源文件顺序而非继承拓扑顺序，父类 property 在子类填充时未必已入 registry，
    故冲突校验统一推迟到全部 shell 填充完成后执行）；恢复合同为 `sema.class_skeleton` +
    `removeProperty` + `markSkippedSubtreeRoots`。
  - 范围收窄（实现确认并记录）：仅检查继承链上的 **property** 同名冲突（GDCC/engine 均覆盖）；
    跨 kind（static var × 继承 function/signal）冲突留作后续加固（§9），与
    `rejectEngineNativeSignalShadow` 只查 engine signal 的既有收窄先例一致。
  - 测试：`FrontendClassSkeletonTest` 4 用例（子类先于父类填充的顺序鲁棒性、`Node2D.position`
    engine 冲突、inner class 冲突、正向不同名 + 实例遮蔽行为不变）。
- [x] 1.3 annotation 边界（§3.3.1）：`FrontendAnnotationUsageAnalyzer` 新增 `@export` × static
      property 的 `sema.annotation_usage` 拒绝；`@onready` 既有拒绝回归不动。
  - 实现：`validateExportUsage` + `findAnnotationByName`（泛化自 `findOnreadyAnnotation`）。
  - 测试：`analyzeReportsExportOnStaticPropertyWhileAllowingInstanceExport`。
- [x] 1.4 `reduceGdccStaticLoad` static property 分支 + 专用 PROPERTY trace（§3.4）；
      `ClassRegistry` 新增 static-property hierarchy 查询（B9）。
  - 实现：`findStaticPropertyInHierarchy`（static-only，返回 `ClassPropertyLookup`）；
    `resolvedStaticLoadTrace` 显式参数化 `bindingKind`，5 个既有常量 call site 显式传 `CONSTANT`；
    `reduceGdccStaticLoad` 在 method-reference 之后插入 static property 分支。
  - 测试：`ClassRegistryTest` 2 用例 + chain test `analyzeResolvesStaticPropertyThroughClassNameAccess`
    （含继承共享同一 declaring `PropertyDef`、`Worker.shared += 1` 写路径）。
- [x] 1.5 instance receiver × static property 路由（§3.4）：`self.x` / `obj.x` 解析为
      static route + `sema.static_access_via_instance` warning；覆盖读/写/复合赋值与
      `get_obj().x` 副作用 receiver 场景；同步 `diagnostic_manager.md` 新 category。
  - 实现：`resolvedInstanceStaticPropertyTrace`（`RouteKind.STATIC_LOAD` + `receiverKind=INSTANCE`
    保留语法事实）；`ReductionNote` 新增 `category` 字段（默认 `sema.call_resolution`），
    `publishReduction` 改用 `note.category()`；`reduceSubscriptStep` 透传 notes。
    `diagnostic_manager.md` 与 `frontend_rules.md` owner 条款已同步。
  - 测试：`analyzeWarnsWhenInstanceSyntaxAccessesStaticProperty`（复合赋值/`self.x`/return 读
    共 3 条 warning + 实例 property 对照）、`analyzeKeepsInstancePropertyAccessViaClassNameFailClosed`。
  - review 闭环补强（review-expert-b 发现，提前落地阶段 2.2 的 instance-receiver LIR 部分）：
    仅修语义 route 会让 lowering 仍走实例字段指令，因此同步修复两处消费端——
    `FrontendSequenceItemInsnLoweringProcessors` 的 `MemberLoadItem` INSTANCE 分支识别 static
    `PropertyDef` 改发 `LoadStaticInsn`（receiver 已在 CFG 序中求值，副作用保留），
    `FrontendBodyLoweringSession.isStaticWritablePropertyRoute` 的 AttributePropertyStep 分支
    增加 static `PropertyDef` 判定（写/复合赋值走 `StaticPropertyLeaf`/`StaticPropertyCommitStep`）。
    测试：`runLowersInstanceStyleStaticAccessThroughStaticInstructions`（call-result 副作用
    receiver + `self.x` 读写，断言零实例字段指令、副作用 call 保留、warning 存在且无 error）。
- [x] 1.6 static `:=` 冻结规则测试（Variant metadata + `sema.type_hint`，§1.2）。
  - 测试：`analyzeWarnsForStaticPropertyInferredTypeHintsWithoutRewritingMetadata`。
- 验收：`run-gradle-targeted-tests.ps1 -Tests FrontendStaticContextValueRestrictionTest,FrontendAnnotationUsageAnalyzerTest,FrontendTypeCheckAnalyzerTest,FrontendBodyOwnerProceduresChainBindingTest,FrontendBodyOwnerProceduresExprTypeTest,FrontendClassSkeletonTest,FrontendLoweringBodyInsnPassTest` 全绿；
  shared `analyze(...)` 对合法 static var 零新诊断，对非法形态 diagnostic category 正确且兄弟 subtree 继续工作；
  `FrontendCompileCheckAnalyzerTest` / `ApiCompileDiagnosticsTest` 中 static var 被拒用例**保持不动**（gate 仍关闭）。

### 阶段 2：lowering 链路（gate 保持关闭）（✅ 已完成，2026-08-27）

- [x] 2.1 验证/修复 static `PROPERTY_INIT`（无 self）CFG build + body lowering 全链路（§3.5）。
  - 结论：链路**已就绪无需生产修复**——`createPropertyInitShell` 已有 static 分支
    （hidden + static + 0 参数，不加 `self`），`requireCompatiblePropertyInitShell` 已有
    0 参数 static 合同校验，CFG/body pass 无 static 拒绝，body session 对 static 函数不声明
    `self` 槽；唯一阻断是 compile gate（按阶段冻结决定保持关闭）。
  - 顺带修复文档矛盾：`gdcc_low_ir.md` 示例把实例 property 的 init helper 误标
    `is_static="true"`（property 表为 `is_static="false"`），已改回。
  - 测试（shared-semantic harness 绕过 gate）：
    `runPublishesStaticPropertyInitContextAsZeroParamStaticHiddenShell`（两个 static + 实例对照，
    锚定 hidden/static/0 参数/返回类型/initFunc 元数据/shell-only）、
    `runReusesPreassignedHiddenZeroParamStaticPropertyInitShell`（复用正向）、
    `runFailsFastWhenExistingStaticPropertyInitShellDeclaresParameters`（带参 shell 负向 fail-fast）、
    `runPublishesStaticPropertyInitCfgGraph`（seq_0 → RETURN stop，literal 与 sibling 引用两形态）、
    `runLowersStaticPropertyInitializersIntoZeroParamStaticInitFunctions`（LiteralIntInsn + ReturnInsn；
    sibling static 引用 `base + 41` 在 init 函数内发 `LoadStaticInsn`；0 残留实例字段指令）。
- [x] 2.2 裸读/裸写/复合赋值/`ClassName.name` 读写的 LIR 形态定型：
      `LoadStaticInsn(起始类, name)` / `StoreStaticInsn(起始类, name, value)`，含继承场景；
      instance receiver 路径额外断言：receiver 子表达式正常求值、leaf 只出现
      `LoadStaticInsn` / `StoreStaticInsn`、无残留实例字段指令（§3.5）。
  - 直接 leaf 读/写/复合赋值与 `ClassName.name` 已在阶段 1 review 闭环完成（见 1.5 记录）。
  - 本阶段落地 static 容器 property 的 attribute-subscript 路径（`obj.values[i]` /
    `self.values[i]` 读/写/复合赋值）：
    - sema：`reduceSubscriptStep` 把内部属性解析出的 RESOLVED 容器 property member
      （实例或 static）**重锚定发布到真实 subscript step**（合成 property step 不在 AST 上，
      side table 无法检索）；实例成员事实当前仅作容器类型 provenance（供 `containerSourceType`
      与阶段 6 typed 优化），static 成员事实额外驱动静态存储路由；
      `resolvePublishedAttributeStepType` 对 SUBSCRIPT step 跳过 member 派生类型，
      保持发布元素类型（否则 leafType 会被容器类型劫持，实测引发 `store_subscript` 边界错误）。
    - compile gate：`resolvedMembers` 锚点校验放宽为 AttributePropertyStep |
      AttributeSubscriptStep（RESOLVED 容器 property 事实使用后一种锚点，阻塞扫描不受影响）。
    - lowering：`SubscriptLeaf` 携带 `containerSourceType`（三条路径恒填充：裸下标为容器
      类型、resolved named 容器为发布的 property 类型、dynamic named 容器为 Variant）与
      `staticOwnerNameOrNull`（仅 static 容器非空）；由 session / SubscriptLoadItem processor
      在构造时经 `resolveSubscriptContainerFacts(memberName, receiverType, leaf/item anchor)`
      解析——**不能用 route anchor**：赋值 payload 会被 `withRouteAnchor(assignmentExpression)`
      重锚定；named scratch 静态分支发 `LoadStaticInsn`，写回经 `appendNamedBaseWriteback` 发
      `StoreStaticInsn`；新增 `StaticContainerSubscriptCommitStep`（terminal-only，与
      `StaticPropertyCommitStep` 同规则）承载深层链（`obj.values[i].x = v`）的反向提交。
    - 写回跳过语义与实例路径对齐：reference carrier（Array/Dictionary/Object/primitive）的
      终端 static commit 编译期跳过（容器原位 mutation），value-semantic carrier 仍内联应用；
      因此裸 `values[i]` 复合赋值无 `StoreStaticInsn`、引用元素深链无容器写回——均与实例
      bare/named 路由的既定行为一致。
    - 已知边界（预存在，非本计划引入）：type-meta head 首步为 subscript（`ClassName.values[i]`）
      在 CFG build 抛 `IllegalStateException`（对所有 type-meta 下标链一致），归阶段 5。
  - review 闭环补强（review-expert-b 发现，均已修复）：
    - **BLOCKING：static typed 容器的 key 物化丢失容器类型**。named 路由的 key 物化强制按
      Variant 处理（`materializeSubscriptKey` 对 `memberNameOrNull != null` 降级为 Variant），
      会把 `Dictionary[float, V]` 的 `int` key 错走 GENERIC/INDEXED。修复：provenance 携带
      容器类型（`resolvedMember.resultType()`；现为 `SubscriptLeaf.containerSourceType` /
      `StaticContainerSubscriptCommitStep.containerType`），key 物化与
      access-kind 选择按 typed 容器语义（同裸下标）。测试：`write_typed_key`（integration，
      `self.typed_table[1] = v` 锚定 KEYED + 无 named/通用 set）与 commit step 单测的
      float-key 转换断言。
    - **WARNING：嵌套 receiver 下 static commit 非 terminal 崩溃**。`holder.child.values[i] = v`
      这类链的 route promotion 会在 static 边界外保留实例 commit step，触发
      `StaticContainerSubscriptCommitStep` 的 terminal fail-fast。修复：session 在
      `requireWritableAccessChain` 物化 commit steps 后做 static 存储边界截断
      （`truncateAtStaticStorageBoundary`）——static commit 完全终止 mutation 链，外侧 step 只是
      未变 reference carrier 的冗余写回；receiver/key 表达式的副作用求值不受影响。该截断同时
      修复 `a.b.static_prop[k] = v` 形态下 `StaticPropertyCommitStep` 的同类预存在崩溃。
      测试：`runTruncatesOuterInstanceStepsAtStaticContainerBoundary`（值语义 Vector2 元素强制
      写回实际应用，锚定 LoadStatic/SetIndexed/StoreStatic + 无 `child` 写回 + receiver 链
      仍求值）。
    - **review 复审二次发现：截断必须保留最内层 static 边界**。双 static 边界场景
      （`static_holder.vectors[i].x = v`，外层 static property + 内层 static 容器）下，取第一个
      static 边界会让内层 commit 以 non-terminal 执行；外层 static property 的存储引用从不因
      内层元素/容器 mutation 改变，故截断改为丢弃最内层 static 边界之前的全部 step。
      测试：`runKeepsOnlyInnermostStaticBoundaryInReverseCommit`（锚定 vectors 的
      LoadStatic/SetIndexed/StoreStatic、无 `holder` 的 StoreStatic、`holder` receiver 仍被求值）。
  - 测试：`analyzePublishesStaticContainerMemberOnAttributeSubscriptStep`（发布 + 元素类型 +
    实例容器同形态 provenance）、`analyzePublishesStaticContainerMemberAcrossReceiverShapes`（typed
    local / self / call receiver 三形态）、
    `runLowersStaticContainerSubscriptThroughStaticStorageRoute`（Array 读 / self 写 /
    Dictionary 写 / call receiver 复合 + 实例 named 路由对照）、
    `runLowersBareStaticContainerSubscriptThroughStaticStorageRoute`（bare 回归锚点，
    写回跳过语义）、`runSkipsStaticContainerWritebackForReferenceElementMutations`（深链引用
    元素）、`reverseCommitAppliesStaticContainerSubscriptThroughStaticStorageRoute` +
    `reverseCommitFailsFastWhenStaticContainerSubscriptCommitIsNotTerminal`（commit step 单测）。
- 验收：`run-gradle-targeted-tests.ps1 -Tests FrontendLoweringFunctionPreparationPassTest,FrontendLoweringBuildCfgPassTest,FrontendLoweringAnalysisPassTest,FrontendLoweringBodyInsnPassTest,FrontendLoweringPassManagerTest` 全绿（另含
  FrontendWritableRouteSupportTest / FrontendChainReductionHelperTest /
  FrontendBodyOwnerProceduresChainBindingTest / FrontendCompileCheckAnalyzerTest /
  ApiCompileDiagnosticsTest / FrontendMatchSupportTest 回归，共 446 项）；
  新增 LIR 断言覆盖各指令形态与 static init helper（0 参数、hidden、返回类型匹配、0 残留
  实例字段指令）。

### 阶段 3：C backend（gate 保持关闭）

- [ ] 3.1 存储符号公式入 `CGenHelper`（§3.1 raw 拼接约定）+ `entry.h.ftl` / `entry.c.ftl`
      backing 变量发射 + 全模块符号冲突校验 fail-fast。
- [ ] 3.2 模板三处 static guard（B7）+ `CCodegen` static 分支（§3.6 前两条）。
- [ ] 3.3 全局两段式 static init + `initialize()` / `deinitialize()` 接线（§3.2，
      base-before-derived 排序）。
- [ ] 3.4 `LoadStaticInsnGen` / `StoreStaticInsnGen` GDCC 分支（§3.6），
      含 OBJECT fat-pointer 与 RefCounted YES / UNKNOWN / NO 三态。
- 验收：`run-gradle-targeted-tests.ps1 -Tests CLoadStaticInsnGenTest,CStoreStaticInsnGenTest,CCodegenTest,CBodyBuilderPhaseCTest,FrontendLoweringToCProjectBuilderIntegrationTest,ObjectValueLifecycleCharacterizationTest` 全绿；
  新增 codegen 断言覆盖：backing 变量命名（含冲突用例）、两段式 static_init、
  生成 C 中无 `self-><static_name>`、三条写路径的 ownership 形态、deinitialize 清理。

### 阶段 4：拆除 compile gate + 端到端 + 文档收口（原子变更）

**本阶段是不可拆分的原子变更**（二轮复核）：gate 移除、测试反转与文档同步必须落在同一个
变更单元内提交，任何中间态（gate 已拆但测试/文档未同步）都不得入库。
`frontend_lowering_plan.md` §6 的“lowering / backend 就绪”前提由阶段 2-3 满足，
文档与测试同步即本阶段内容，不得以“先拆 gate 后补文档”的方式拉长窗口。

- [ ] 4.1 移除 B2（`handleVariableDeclaration` static 分支、`isStaticClassPropertyDeclaration`、
      `staticPropertyCompileBlockedMessage`），static property initializer 走既有
      `markCompileSurfaceNode` + `walkExpression` 路径；**同一变更单元内**反转
      `FrontendCompileCheckAnalyzerTest`（约 :496-570）与 `ApiCompileDiagnosticsTest`
      （约 :52-108）的 static var 用例为放行，保留 unsupported 形态 negative path。
- [ ] 4.2 `src/test/test_suite` 新增 static var 正向用例：默认值、显式 initializer、跨方法读写、
      static func 内读写、`ClassName.name` 访问、`self.x` / `obj.x` instance 访问
      （含多实例共享同一存储、warning 不阻断运行）、继承共享同一份存储、inner class static var、
      destroyable 类型（`String`/`Array`/`Dictionary`/object）、复合赋值。
- [ ] 4.3 e2e 运行验证（zig 可用时；不可用时按环境感知跳过并记录）。
- [ ] 4.4 文档同步（review 发现 11）：`frontend_rules.md:54` / `frontend_rules.md:116` 改写为
      已支持口径；`frontend_property_init_lowering_implementation.md`、
      `frontend_compile_check_analyzer_implementation.md`、
      `frontend_singleton_implementation.md`、
      `frontend_lowering_skeleton_pre_pass_implementation.md`（:270-288 的 static 条款）、
      `backend/load_static_implementation.md`（:35-48、:219-223 的 store_static 不支持条款）同步；
      `gdcc_facing_class_name_contract.md`（:154-186）与 `superclass_canonical_name_contract.md`
      （:194）补充条款：static backing/init 符号沿用 raw canonical 拼接约定，
      由全模块符号冲突校验兜底（该条款同时记录存量 `${class}_${func}` 命名表面的已知碰撞面）；
      本文档改写为事实源风格并移除 checklist。
- 验收：`run-gradle-targeted-tests.ps1 -Tests FrontendCompileCheckAnalyzerTest,ApiCompileDiagnosticsTest,ApiCompileTaskFailureStageTest` 全绿；
  `./gradlew clean build --no-daemon --info --console=plain` 全绿。

### 阶段 5：type-meta head 首步 attribute-subscript 支持（`ClassName.values[i]`，待实施）

预存在的通用边界（对所有 type-meta 下标链一致，含常量容器，非 static var 特有）：
`FrontendCfgGraphBuilder.buildTypeMetaHeadAttributeExpressionValue` 只接受首步为
`AttributePropertyStep` / `AttributeCallStep`，遇到 `AttributeSubscriptStep` 首步直接
fail-fast `IllegalStateException`。整条下标机制（`SubscriptLoadItem.baseValueId` 非空、
writable root 枚举、`SubscriptLeaf.baseOrReceiverSlotId` 非空）都建立在“receiver 有运行时值”
的假设上，而 type-meta head 从不物化运行时值。

- [ ] 5.1 CFG 增加 type-meta subscript 首步分支：头部成员 step 沿用既有 TYPE_META 分支出
      `MemberLoadItem(baseValueIdOrNull=null)`（直接发 `LoadStaticInsn` 产出容器值 id），
      后续下标按**普通下标**（`memberNameOrNull=null`，base 指向容器临时值）构造——
      与裸标识符形态（`values[i]`，STATIC_CONTEXT root + 终端 `StaticPropertyCommitStep`）
      结构同构；`SubscriptLeaf.baseOrReceiverSlotId` 保持 `@NotNull`（base 恒为
      `LoadStaticInsn` 结果槽位），不需要为“无 receiver”放宽可空性。
- [ ] 5.2 sema 确认/放行 TYPE_META receiver 的容器成员事实发布（`reduceSubscriptStep`
      对 type-meta incoming receiver 的合成属性解析已覆盖 static property；验证
      `requireStaticReceiverName(resolvedMember.receiverType())` 在 TYPE_META 下取类名）。
- [ ] 5.3 终端写回沿用提升的 `StaticPropertyCommitStep`；读形态 `ClassName.values[i]`
      与写形态 `ClassName.values[i] = v` 均需正/负测试（含继承起始类 `Sub.values[i]`）。
- 验收：`FrontendCfgGraphBuilderTest` / `FrontendLoweringBodyInsnPassTest` 新增
  type-meta subscript 用例全绿；既有 type-meta head 负向断言相应更新。

### 阶段 6：可解析实例 named subscript 的 typed 优化（`obj.items[i]`，待实施，依赖阶段 5 之后）

现状：attribute-subscript named 路由（`receiver.member[key]`）对所有实例 receiver 一律走
Variant named 路径（`PackVariantInsn` → `VariantGetNamedInsn` → `Variant(Set/Get)IndexedInsn`
→ `VariantSetNamedInsn`），即使 `member` 是完全解析、静态类型明确的实例 property
（如 `var items: Array[int]`）。这是“指令契约（VariantGetNamedInsn 返回 Variant）+ 运行时
分发兜底正确”的既定公共分母，正确性无问题，但放弃了编译期已知的容器类型。

阶段 2.2 起已铺平数据通路：chain binding 把 RESOLVED 实例容器成员事实发布到 subscript
锚点，`SubscriptLeaf.containerSourceType` 恒填充声明容器类型（dynamic 容器为 Variant）。
本阶段消费这些数据：

- [ ] 6.1 resolved 实例容器（`containerSourceType` 为非 Variant 且非 static owner）的 named
      scratch 改为 `LoadPropertyInsn` + typed 下标（key 转换与 access-kind 按
      `containerSourceType`，与裸下标一致）+ `StorePropertyInsn` 写回，与裸形态
      （`items[i]` 经 `InstancePropertyCommitStep`）拉齐；`containerSourceType` 为 Variant
      的 dynamic 容器保持既有 Variant named 路由不变。
- [ ] 6.2 反向提交 `SubscriptCommitStep` 同步增加容器 provenance 消费（深链
      `obj.items[i].x = v` 的 named 层写回同样 typed 化）。
- [ ] 6.3 行为基线迁移：既有 named 路由测试锚定 Variant 指令形态，需按 receiver 是否
      resolved 实例容器分组更新；engine 对象 property 容器（非 GDCC `PropertyDef`）保持
      Variant 路由（`LoadPropertyInsn`/`StorePropertyInsn` 仅 GDCC 实例存储可用）。
- 验收：`FrontendWritableRouteSupportTest` 按新契约重写 named 用例 + integration 正/负测试
  （typed key 转换、无 Variant named 指令残留、dynamic 容器回归）全绿。

## 5. 风险登记

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| B1 predicate 修复影响面（所有 property binding 消费方） | 高 | 与 `FrontendCfgGraphBuilder` :4161-4165 对齐为唯一真源；阶段 1 targeted frontend 回归，阶段 4 `clean build` 全量回归 |
| 模板实例路径漏加 guard 导致生成 C 编译失败 | 高 | 阶段 3 验收强制“生成 C 无 `self-><static_name>`”字符串断言 + 集成测试 |
| gate 提前拆除导致 static var 带着未就绪 backend 进入 compile | 高 | 阶段序冻结（§4 开头）；gate 拆除固定在阶段 4 |
| 符号命名冲突（raw 拼接歧义如 `A_B.c` vs `A.B_c`、用户函数同名、canonical 非法字符） | 中 | §3.1 公式集中于 `CGenHelper` + 全模块符号冲突校验 fail-fast（与存量实例成员命名风险水位一致；严格化留作未来统一加固） |
| 继承共享存储被误实现为按类复制 | 高 | 读取/写入统一沿 hierarchy 定位声明 owner（§2.3）；e2e 继承用例 |
| 跨类 static 引用读到未物化默认值 | 中 | §3.2 全局两段式（base-before-derived）+ 跨类默认值测试 |
| instance receiver 访问 static：receiver 副作用被吞或 leaf 误入实例存储 | 中 | §3.5 强制 receiver 正常求值 + LIR 断言无 `LoadPropertyInsn`/`StorePropertyInsn` 残留；`sema.static_access_via_instance` warning 提示用户改用类型访问 |
| binding kind（CONSTANT vs PROPERTY）选错导致 static var 被当只读，或只读常量被当可写 | 中 | §3.4 专用 trace + 正/负双向测试 |
| static initializer 时点对 engine API 可用性的假设（SCENE level） | 中 | 阶段 4 e2e 用例覆盖 singleton/utility/object 构造 initializer |
| destroyable static 默认值被 initializer 覆盖导致泄漏 | 中 | §3.2 先销毁再 move + 阶段 3 lifecycle 测试锚定 |
| OBJECT static 生命周期（fat-pointer、RefCounted 三态、deinitialize） | 中 | §3.6 末条 + YES/UNKNOWN/NO 分类测试 |
| static `PROPERTY_INIT` 无 self 链路存在未知 self 假设 | 中 | 阶段 2 首项验证，失败先修再继续 |
| 跨类 static initializer 执行顺序（对方 initializer 是否已跑） | 低 | 与 Godot 一致不保证；文档注明 |

## 6. 受影响文件清单（预估）

- frontend：`FrontendBodyLoweringSession.java`（B1）、`FrontendClassSkeletonBuilder.java` 或独立
  skeleton 校验（§3.3.2）、`FrontendChainReductionHelper.java`、`ClassRegistry.java`（B9）、
  （按需）`FrontendAssignmentSemanticSupport.java` / `FrontendAnnotationUsageAnalyzer.java`、
  `FrontendCompileCheckAnalyzer.java`（阶段 4）。
- backend：`CCodegen.java`、`CGenHelper.java`、`LoadStaticInsnGen.java`、`StoreStaticInsnGen.java`、
  `entry.h.ftl`、`entry.c.ftl`。
- 测试：见 §4 各阶段验收。
- 文档：见 §4.4。

## 7. 维护约束

- 实施期间若设计事实变化（存储命名、初始化时点、binding kind、阶段序等），先更新本文档再改代码。
- 每个阶段的代码注释遵循 `common_rules.md` 命名约定（`requireXxx` / `checkXxx` / `validateXxx`）。
- 诊断新增必须同步 `diagnostic_manager.md` 与受影响模块文档（`frontend_rules.md:15`）。

## 8. 阶段 0 结论

- 8.1 `@export` × static var（2026-08-27 核查 `godotengine/godot` master）：
  Godot 在 **parser 层**拒绝所有 export 系注解（`@export` / `@export_storage` /
  `@export_custom` / `@export_tool_button`）用于 static var，错误模板
  `Annotation "%s" cannot be applied to a static variable.`（`gdscript_parser.cpp:4658`、
  `:4999`、`:5021`、`:5049`）；`@onready` 同样在 parser 层拒绝
  （`"@onready" annotation cannot be applied to a static variable.`，`:4525-4537`）。
  gdcc 决策：`@export` × static property 由 `FrontendAnnotationUsageAnalyzer` 发
  `sema.annotation_usage` error（gdcc 无 parser 层注解校验；`frontend_rules.md:22` 指定
  annotation-usage 为唯一 owner）。消息模板 `@export cannot be used on static property '<name>'`，
  与既有 `@onready cannot be used on static property '<name>'` 同构。
  背景：gdcc 的 `@export` 当前仅影响实例 property 的 ClassDB `PROPERTY_USAGE`
  （`CGenHelper.java:1358-1371`），而 static property 本就不注册 ClassDB（模板已跳过），
  拒绝语义与 Godot 一致且无副作用。
- 8.2 `self.static_var` / instance receiver 访问（2026-08-27 核查）：Godot **静默允许**
  经实例读写 static var，操作的是声明类共享存储（`gdscript_analyzer.cpp:4297-4303`、
  `gdscript_compiler.cpp` 的 `write_get_static_variable` / `write_set_static_variable`；
  PR #77129 的 `static_access_via_instance` 测试锚定多实例共享同一值）。
  对比：static **func** 经实例调用有 `STATIC_CALLED_ON_INSTANCE` warning
  （`gdscript_analyzer.cpp:3769-3773`），static **var** 无对应 warning（issue #106364 仍 open）。
  gdcc 决策（2026-08-27 用户确认）：**允许** instance receiver 访问并与 Godot 对齐
  （操作声明类共享存储），同时发 `sema.static_access_via_instance` warning 提示改用
  `ClassName.name`（Godot 对 static var 无此 warning——issue #106364；gdcc 主动补齐，
  比 Godot 更严格但不改变行为）。规则与验收见 §3.4 / §3.5 / 阶段 1.5 / 2.2 / 4.2。
- 8.3 子类同名 static var：现有代码**无**继承层冲突拒绝（`LirClassDef.addProperty` :126-132 无校验、
  `ClassScope.defineDirectValue` :241-247 只查同类、direct 优先于继承 :156-174），
  已由 review 确认 → 新增 §3.3.2 校验（static 声明面 scoped，不动 non-static 遮蔽行为）。
- 8.4 review 闭环记录（review-expert-c，2026-08-27）：11 条发现全部复核属实并并入——
  B1 predicate 缺陷、模板三处缺 guard、阶段序冻结（gate 拆除移至阶段 4）、继承冲突校验新增、
  全局两段式初始化、符号命名三约束 + 冲突校验、`:=` 冻结规则、专用 PROPERTY trace、
  initFunc 语义冻结（无 initializer 不合成 `_field_init_`）、OBJECT 生命周期分类测试、
  测试/文档清单补全（`FrontendLoweringBuildCfgPassTest` / `CCodegenTest` /
  `FrontendLoweringToCProjectBuilderIntegrationTest` / `backend/load_static_implementation.md` /
  `frontend_lowering_skeleton_pre_pass_implementation.md`）。
- 8.5 二轮复核闭环（review-expert-c，2026-08-27）：首轮 11 条中 8 项确认 RESOLVED；
  3 项 PARTIALLY 与 4 个新警告已全部修复——static init 拆双入口
  （`gdcc_static_defaults_*` / `gdcc_static_initializers_*`）、命名改为对原始 canonical identity 的
  可逆 `cSafeEncode(...)`（折叠型 `cIdentifier` 不满足单射）、阶段 4 标记为原子变更
  （gate 移除 + 测试反转 + 文档同步同单元入库）、阶段 1 验收命令补 `FrontendLoweringBodyInsnPassTest`、
  `§54/§116` 误写统一改为行锚点 `:54`/`:116`。
- 8.6 三轮复核闭环（review-expert-c，2026-08-27）：二轮 5 项全部确认 RESOLVED；
  剩余 1 个 WARNING 与 2 个 SUGGESTION 已修复——§4.4 文档同步清单补
  `gdcc_facing_class_name_contract.md` / `superclass_canonical_name_contract.md`
  （声明 `cSafeEncode` 与既有 `cIdentifier()` 表面的分工）、§6 交叉引用 `§4.5` 改 `§4.4`、
  风险表 B1 缓解措辞改为“阶段 1 targeted 回归 + 阶段 4 全量回归”。
- 8.7 命名方案变更（2026-08-27，用户决策）：符号命名从“可逆 `cSafeEncode` 单射编码”改为
  **沿用存量 raw 拼接约定 + 全模块符号冲突校验 fail-fast**。理由：存量实例成员命名
  （`${class}_${func}`、struct 字段裸名）本就承受同水位碰撞风险（`addFunction` 不查重、
  仅有 member 级保留前缀与 canonical 保留序列两层防线），新表面不必单独从严；
  对整个命名表面的单射化加固留作未来统一工程。冲突校验由防御性 guard 升级为**主防线**，
  需报出冲突双方符号与来源并提示重命名。
- 8.8 instance receiver 访问放宽（2026-08-27，用户决策）：`self.static_var` / `obj.static_var`
  从“fail-closed 排除项（§1.3）”改为**允许 + warning**，行为与 Godot 一致（共享声明类存储），
  并主动补 Godot 缺失的 static-var 版 warning（`sema.static_access_via_instance`）。
  同步改动：§1.2 语义基线、§1.3 移除排除项、§3.4 路由与 warning owner、
  §3.5 receiver 副作用与 LIR 形态约束、阶段 1.5 / 2.2 / 4.2 验收、§5 风险表、§9 移除后续项。

## 9. 后续项（MVP 之外）

- `_static_init()` 用户函数的自动调用（Godot parity）。
- static var setget。
- `@static_unload` 注解。
- type-meta head 首步 attribute-subscript（`ClassName.values[i]`，含 static 容器与常量
  容器）：已升级为阶段 5（预存在的通用边界，非 static var 特有）。
- 可解析实例 named subscript 的 typed 优化（`obj.items[i]` 跳过 Variant named 路由）：
  已升级为阶段 6。
