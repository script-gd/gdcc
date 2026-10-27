# `@onready` 初始化表达式中的节点字面量实施计划

> 状态：待实施的设计与验收计划，不是已实现功能的事实源。
>
> 调研基线：2026-10-26 的当前工作树。本文中的“应当”“新增”“验收”均指未来实施；本次只新增计划文档，不修改功能代码。
>
> 修订记录：同日应用户关于 guard 边界的可行性提问，将“显式表达式 receiver 的实例成员后缀”纳入推荐范围（§3.2、§7.1）；随后用户明确决定：间接 self 别名/回调返回 self 的防御由脚本使用者负责（已确认，见 §3.2、§7.1）。本请求仅授权文档修订，功能整体仍待实施。原“链式访问需要 receiver 来源追踪/架构扩展”的论断被句法 direct-self 边界取代。本次仍为文档修订，不修改功能代码。
>
> 目标：在既有 property-initializer 支持岛中，允许合法 `@onready` 属性的初始化表达式使用 `$` / `%` 节点查找简写及其显式表达式 receiver 上的实例成员后缀，复用既有 ready-time 执行链。

## 1. 规则、术语与范围

- 遵循仓库 `AGENTS.md`、`doc/module_impl/common_rules.md` 和 `doc/module_impl/frontend/frontend_rules.md`。`AGENTS.md` 提及的 `common_rule.md` 实际不存在，通用规则文件为复数 `common_rules.md`；frontend 规则位于 `frontend/` 子目录。
- “节点字面量”在本计划专指 parser 的 `GetNodeExpression`：`$Child`、`$A/B`、`$"Name With Space"`、`%Unique`、`%"Unique Name"`。`^"A/B"` 是产生 `NodePath` 值的字面量，已经支持属性初始化，不是本次新增功能。
- 一期仅调整 GetNode 在合法 onready 属性初始化岛内的 typing，并放宽同一岛内显式表达式 receiver 上的实例成员后缀。允许 GetNode 作为直接 RHS、处于本来已支持的 cast、容器 value、条件表达式等组合位置，以及作为带后缀链的显式链头；组合表达式的其他子树仍必须满足原有规则。
- 不开放普通属性或 `static var` 初始化器中的 `$` / `%`，不开放参数默认值中的 `$` / `%`。
- 边界采用句法 direct-self，不做运行时 identity 隔离：仍拒绝显式 `self`、`super`，以及隐式 self 的裸成员访问（字段、方法调用、方法引用、信号，含继承成员）；对显式表达式 receiver（GetNode、实例方法调用结果、显式 static helper/构造对象结果等）上的实例成员后缀放行，即使成员 owner 是 `Node` 或当前类祖先（§3.2）。
- 不开放初始化器内未记录的 lambda、`await`，不实现 property `:=` 类型推导，不调整隐式转换矩阵；其余普通/static/property/default/lambda/await/type-meta 规则全部不变。
- 不推导场景文件中节点的具体类型，不把结果自动视为 `Camera3D` / `Label` 等子类；不把 `$` 改为 `get_node_or_null`，不支持 `$Child = value`。
- 间接 self 防御由脚本使用者负责（已确认）：`$"."` 返回 self、`$Child.get_parent()` 可能返回 self，此类链在句法规则下被有意放行；自有状态、声明依赖与重入的正确性由用户保证，编译器不做 alias 分析或 identity 过滤，正常运行时检查（对象存活/类型/成员/调用/所有权）照常适用（§7.1）。
- 不调整 backend 的 ready/replay、热重载、混合 GDCC/GDScript 继承或跨模块继承合同。

以下是一期应当支持的完整脚本示例；节点由使用方的场景在 ready 前提供：

```gdscript
class_name OnreadyNodeLiteralExample
extends Node

@onready var child: Node = $Child
@onready var nested: Node = $Branch/Leaf
@onready var spaced: Node = $"Name With Space"
@onready var unique: Node = %Unique
@onready var unique_spaced: Node = %"Unique Name"
@onready var candidates: Array[Node] = [$Child, %Unique]
@onready var selected: Node = $Child if true else %Unique
@onready var camera: Camera3D = $Camera as Camera3D
@onready var child_name: StringName = $Child.name
@onready var unique_name: StringName = %Unique.get_name()
@onready var renamed_signal: Signal = $Child.renamed
@onready var get_name_callable: Callable = $Child.get_name
```

cast 一项复用显式 object cast；不是根据路径自动推导，也不保证运行时节点确实是 `Camera3D`。运行时验收必须创建实际匹配的节点。

末四项演示 §3.2 的句法 direct-self 规则：receiver 是显式 GetNode 表达式，因此即使 `Node.name` / `Node.get_name` / `Node.renamed` 的 owner 属于当前实例层级也放行；裸写的 `name` / `get_name()` / `renamed` 仍由 binding/head owner 拒绝。

## 2. 已核实的现状与阻断链路

### 2.1 文档依据

| 文档 | 直接相关章节与事实 |
| --- | --- |
| `doc/module_impl/frontend/frontend_node_literal_implementation.md` | §1、§3.3–3.8、§6：函数体及已记录 lambda 的 GetNode 已贯通；所有 property initializer 仍 DEFERRED；解码、Node receiver、三指令合同已存在 |
| `doc/module_impl/frontend/frontend_property_init_lowering_implementation.md` | §2–4、§6：属性表达式根使用 `PROPERTY_INIT`；hidden helper 有真实 CFG/LIR；非 static helper 有 owner `self`；onready 在 apply 侧分流 |
| `doc/module_impl/frontend/frontend_annotation_implementation.md` | annotation attachment / retention 与 usage validation 分离 |
| `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md` | §1–3、published-fact scan：compile gate 只消费事实；不应恢复 GetNode 专用 intercept |
| `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md` | §4–5：仅对稳定 initializer fact 做 slot compatibility / type hint；onready placement 有独立 owner |
| `doc/module_impl/frontend/frontend_lambda_implementation.md` | §1、compile surface 边界：属性初始化器内 lambda 不记录，不能借本次功能合成 capture |
| `doc/module_impl/frontend/frontend_parameter_default_implementation.md` | 默认值有独立 island；不能因为同样是 expression-root helper 就放开 GetNode |
| `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md` | `PROPERTY_INIT` expression root 与 GetNode opaque leaf，及当前属性 GetNode gate |
| `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md` | §4.1/4.2：bare/self 依赖由 top-binding owner 封死并向 expr typing 传播 `BLOCKED`；chain suffix 与 current-instance type-meta route 的首个非法依赖由 chain-binding owner 封死并传播 `UNSUPPORTED`；§4.6：GetNode 在 property initializer 分类为 DEFERRED |
| `doc/module_impl/frontend/frontend_signal_support.md` | §5：property initializer 中的非静态 signal 访问走 `detailForResolvedSignalBoundary` 拦截；method-reference / signal 构造出口已存在 |
| `doc/module_impl/frontend/frontend_lowering_skeleton_pre_pass_implementation.md` | property helper/context 的 preparation，不代表绕过 compile-ready 分析 |
| `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`、`frontend_resolution_pipeline_implementation.md` | scope/inventory、suite resolution、typed facts 的先后顺序；property initializer 不属于 lambda executable inventory |
| `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`、`doc/gdcc_type_system.md` | typed boundary 与 object 上转；不开放 `String ↔ NodePath` 隐式转换 |
| `doc/module_impl/backend/onready_class_implementation.md` | §1–8：构造期默认值、ready-time apply、继承链、双入口及已知偏差；明确 `$` / `%` gate 尚未解除 |
| `doc/gdcc_low_ir.md`、`doc/gdcc_c_backend.md`、`doc/gdcc_runtime_lib.md` | 既有 NodePath / call-method / init-function / fat-pointer 基础设施，不需要新 opcode 或固定 runtime binding |
| `doc/gdcc_ownership_lifecycle_spec.md`、`doc/module_impl/backend/backend_ownership_lifecycle_contract.md` | first-write 与 overwrite 的区别；临时值和返回值沿用统一所有权处理 |
| `doc/module_impl/backend/call_method_implementation.md`、`virtual_override_vtable_implementation.md` | ENGINE 调用路由与 `_ready` 分发边界 |
| `doc/test_suite.md`、`doc/test_error/test_suite_engine_integration_known_limits.md` | source/validation 成对 fixture、pass marker、已有 GetNode scene 覆盖及属性初始化限制 |

表中 frontend/backend 第二个省略目录的文件均与同格首个文件位于同一目录。当前事实源仍保持原状；只有功能实现并完成对应验收后才能同步为新支持面。

### 2.2 代码证据与阶段顺序

以下源码均位于 `src/main/java/gd/script/gdcc/`，行号只作为本次调研定位，实施时按方法名重新读取：

| 位置 | 当前行为与实施意义 |
| --- | --- |
| `frontend/sema/FrontendClassSkeletonBuilder.java`：`build`、`applyPropertyAnnotations` | 在 body typing 之前发布 `annotationsByAst()`；property skeleton 另保留 `"onready"` key。重复注解在 AST side table 中不丢失 |
| `frontend/sema/analyzer/FrontendSemanticAnalyzer.java`：共享 pipeline、`analyzeForCompile` | skeleton/scope/inventory → interface/suite resolution → await/annotation usage/virtual/type-check 等检查 → compile-only gate。usage validation 晚于 initializer typing |
| `frontend/sema/analyzer/FrontendSuiteResolver.java`：`resolvePropertyInitializer`，约 533–585 行 | `callableOwner` 为属性声明，current scope 为 `ClassScope`，context 带 declaration/declaring class；未设置 nested-lambda resolve trigger |
| `frontend/sema/analyzer/support/FrontendPropertyInitializerSupport.java`：`PropertyInitializerContext`、`contextOrNull`、`detailForBindingBoundary` 与 resolved 成员 boundary 方法族 | 支持 class `var` 且有 initializer；提供声明 identity、staticness 和 declaring class。binding 边界按 kind 封死 SELF/SUPER/PROPERTY/SIGNAL/METHOD；resolved 边界按成员 owner 是否属于当前实例层级判定。是否 onready 不影响该岛的建立 |
| `frontend/sema/analyzer/FrontendBodyOwnerProcedures.java`：`resolveGetNodeExpressionType`，约 2167–2225 行 | 第一个分支遇到 property context 即 DEFERRED，先于 static、Node 继承与 lambda 判定；这就是当前直接阻断点 |
| `frontend/sema/analyzer/FrontendBodyOwnerProcedures.java`：`bindSelf` / `bindSuper`，约 936–990 行 | 显式 `self` / `super` 在 binding 层封死；句法边界下保持不变 |
| `frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java` 与 `FrontendChainHeadReceiverSupport.java`：约 271/293 与 210、391/424 行 | 隐式 self 裸成员的 `detailForBindingBoundary` 调用点；`self` / `super` 链头由 `selfBoundaryDetail` / `superBoundaryDetail` 封死。这些 direct-self 入口不受 suffix 放行影响 |
| `frontend/sema/analyzer/support/FrontendChainReductionHelper.java`：约 765/792/836/1967 行 | property（`detailForResolvedPropertyBoundary`）、signal（`detailForResolvedSignalBoundary`）、method-reference（`detailForMethodReferenceBoundary`）、instance-method（`detailForResolvedMethodBoundary`）四处按成员 owner 判定的 suffix boundary（下称“四处后缀 boundary”），是显式 receiver 链的条件性第二阻断点；约 940/1741 行的 type-meta 检查（static load/static method 的 current-instance-hierarchy 判定）不属于本次调整 |
| `frontend/sema/analyzer/FrontendAnnotationUsageAnalyzer.java`：`validateOnreadyUsage` / `validateFirstOnreadyAnnotation` | 独立诊断 class var、非 static、Node 派生 owner 与重复注解；过滤 parser-damaged annotation。不是 onready 标记的生产者 |
| `frontend/sema/analyzer/FrontendTypeCheckAnalyzer.java`：`visitPropertyInitializer` | 稳定 RHS 与已发布 property type 比较；未标显式类型的 property 保持 Variant，只发 type hint，不回写类型 |
| `frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`：`scanExpressionTypeCompileBlocks` | RESOLVED/DYNAMIC 不阻断，DEFERRED 等状态阻断；已有错误按精确 source range 去重，不能假定 annotation range 会覆盖 initializer range |
| `frontend/lowering/pass/FrontendLoweringFunctionPreparationPass.java`：`createPropertyInitShell` | non-static helper 首参是 owning-class `self`，return type 等于 property type |
| `frontend/lowering/pass/body/FrontendOpaqueExprInsnLoweringProcessors.java`：GetNode processor，约 417–465 行 | `literal_node_path` → `assign(Node receiver, self)` → `call_method get_node`；没有 initializer 专属拒绝条件 |
| `backend/c/gen/COnreadyPlan.java`：`create`，约 111–127 行 | 冻结本类非 static、带 onready、有源码 initFunc、非 backend synthesized 的字段 |
| `backend/c/gen/CCodegen.java` 与 `src/main/c/codegen/template_451/entry.c.ftl` | 构造时 default first-write；ready 时调用 source initFunc 并 overwrite；按基类→派生类和声明顺序 apply |

当前失败链：`@onready var child: Node = $Child` → initializer typing 仍 DEFERRED → shared analyze 发 deferred warning → compile scan 升级为 `sema.compile_check` → lowering analysis 停止，既有 helper/GetNode/backend 链路没有机会组合执行。带后缀的 `$Child.name` 在当前基线上的实际失败路径同样是 leaf DEFERRED 沿链传播；四处后缀 boundary 是条件性第二阻断点，仅在 leaf 放行（§4 步骤 1）后才成为执行路径——resolved-head 输入（如 `Node.new().name` / `build().payload`）现在即可命中它，由 `ReceiverState`/resolved member UNSUPPORTED 阻断 compile。

因此“下游分别具备能力”是代码事实，但“组合后已可编译运行”尚无正向证据，必须由 §4 的新增测试闭环。

## 3. 设计决策

### 3.1 最小语义改动

只修改 `FrontendBodyOwnerProcedures.resolveGetNodeExpressionType()` 的 property-initializer 分支，不删除整个分支，也不让 initializer 无条件落入普通函数体规则。

在该分支使用现有 context 和 AST annotation side table 判断：

1. 取 `propertyContext.declaration()` 对应的 `annotationsByAst()`；查找 `name().equals("onready")` 的 annotation。
2. 忽略其 `sourceStatement()` 已属于 `skippedSubtreeRoots()` 的损坏注解，采用与 usage analyzer 一致的过滤规则；不以字符串扫描源码，也不以尚未执行的 usage analyzer 的诊断为条件。
3. 若存在该标记、declaration 非 static，且 `propertyContext.declaringClass()` 经现有 `ClassRegistry.checkAssignable(ownerType, Node)` 判为 Node 派生，直接返回 `FrontendExpressionType.resolved(new GdObjectType("Node"))`。
4. 其余 property context 保持原 DEFERRED 结果和原 detailReason；参数默认值、普通函数和 lambda 分支保持原顺序与合同。

§3.2 的四处后缀 boundary 复用使共享 predicate 成为必需，拟在 `FrontendPropertyInitializerSupport` 新增接口（设计表述，非实现代码）：`public static boolean checkNodeDerivedOnreadyInitializer(@Nullable PropertyInitializerContext context, @NotNull FrontendAnalysisData analysisData)`。命名用 `check...`，不使用表达“获取并保证存在”的 `require...`。

- `context == null`、`context.declaration().isStatic()`、或 `analysisData.annotationsByAst()` 中没有未损坏的 `onready` annotation（`sourceStatement()` 不属于 `skippedSubtreeRoots()`，过滤规则与 usage analyzer 一致）时返回 `false`。
- 否则以 `context.declaringClassScope().getClassRegistry().checkAssignable(new GdObjectType(context.declaringClass().getName()), new GdObjectType("Node"))` 判定 declaring class 为 Node 派生；`ReductionRequest` 已携带 `analysisData()` 与 `propertyInitializerContext()`，调用方不需要新增共享 record 字段。
- `resolveGetNodeExpressionType` 的 property 分支复用它替代手写判定；不新增 annotation-validation phase、能力 side table 或 runtime 标记。

该判定是“此表达式拥有 ready-time 实例接收者”的能力判定，不是完整注解合法性证明：

- 重复 onready 在 Node 实例属性上可以产生稳定 Node fact，但重复注解仍由 usage analyzer 报错并阻止 lowering。
- static / 非 Node onready 仍未获得能力，继续 DEFERRED，同时由 annotation usage 报 placement 错误。既有 deferred warning / compile blocker 可能与不同 range 的 annotation error 并存；本次不承诺跨 range 去重，也不新增 GetNode `FAILED` 来重复解释 placement。
- parser 损坏的声明继续整体跳过；不能为缺失的 published scope/owner 伪造默认 Node 类型。共享事实协议破坏仍按既有 guard fail-fast，普通源码错误仍走 diagnostics。

### 3.2 onready 岛内显式表达式 receiver 的句法边界（间接 self 责任已由用户确认）

本节源于用户关于 guard 可否只禁显式/隐式 self 的可行性提问；用户随后明确决定间接 self 防御归脚本使用者负责，作为已确认的规划约束（仅适用于既有计划的合法 onready 岛）：合法 onready 岛的成员边界采用句法 direct-self，不做运行时 identity 隔离，也不需要 `ReceiverState` provenance 字段、alias 追踪或共享 receiver 模型重设计。

- **放行**：合格岛内显式表达式 receiver 上的实例成员后缀——`FrontendChainReductionHelper` 约 765（property）、792（signal）、836（method reference）、1967（instance method）四处后缀 boundary——即使成员 owner 是 `Node` 或当前类祖先。覆盖 `$Child.name`、`%Unique.get_name()`、`$Child.get_name`（Callable）、`$Child.renamed`（Signal），以及显式 static helper/构造对象结果上的同类后缀。
- **实现**：在四处后缀 boundary 调用点先判 `checkNodeDerivedOnreadyInitializer(request.propertyInitializerContext(), request.analysisData())`；返回 `true` 时跳过该步的 ownerClass boundary，否则走原判定。`ReductionRequest.propertyInitializerContext` 在其他路径原样保留，不得将整个 reduction context 置 null，也不放宽 head/binding/self/super/type-meta 检查。
- **保持拒绝**：显式 `self` / `super`（`bindSelf`/`bindSuper` 与 head support 约 391/424 行）、隐式 self 的裸成员（`detailForBindingBoundary` 在 ExpressionSemanticSupport 约 271/293 与 chain head 约 210 行，覆盖字段/方法调用/方法引用/信号及继承成员）继续由 binding/head owner 封死。suffix 放行只作用于非 self 链头之后的步骤，因此直接边界天然不受影响。
- **间接 self 责任（已确认）**：句法规则不查 receiver 的运行时身份。`$".".get_name()` 与 `$Child.get_parent().get_name()` 是有意的正向句法用例：前者等价于 self，后者可能返回 self，均允许。间接 self 别名/回调返回 self 的防御由脚本使用者负责：自有状态、声明依赖与重入正确性由用户保证。编译器不引入 alias 分析、来源追踪、identity 过滤或专用诊断/恢复/重试；对象存活、类型、成员、调用与所有权检查照常适用，不因责任划分削弱。
- **重复/非法注解**：与 §3.1 一致——重复 onready 仍授予 receiver 能力，但 usage 错误阻止 lowering；static / 非 Node 岛不满足 predicate，suffix boundary 与原 deferred 行为照旧。
- **范围外不变**：普通 property、static、`super`、type-meta（约 940/1741 行）、parameter default、lambda、await 与隐式转换规则均不调整；非 onready 岛内的同类链（如同类/继承 receiver 的 `build().payload` 既有负向用例）保持 UNSUPPORTED。

### 3.3 复用而非另造链路

- 继续由 expression typing 发布 Node fact，不为 GetNode 发布 `resolvedCalls()`。
- 通用 compile scan 自然放行稳定 fact，不新增“有 onready 就忽略整个 initializer 错误”的例外；其他不稳定子树仍阻断。
- helper 仍为 `PROPERTY_INIT`，保留 `self: OwnerClass`，不伪装普通用户函数、不在 `_ready` 的 LIR body 插入第二份赋值。
- GetNode processor 固定 receiver 静态类型为 native `Node`，防止脚本同名 `get_node(NodePath)` 抢占引擎调用。
- 后缀步骤沿用既有 chain reduction 产物：`get_node` 调用之后照常发射 property read / call_method / method-reference / signal 指令，不为 onready 另造 suffix 路径；条件表达式中的链保持惰性，未选分支不提前求值。
- 解码只复用 `StringUtil.decodeGetNodePathLexeme`，`%` 必须保留；NodePath 临时值与 Node 返回值沿用现有生命周期，不引入额外 retain/release。
- backend 不增加 AST 感知或 GetNode 特判；构造仍只写默认值，真实初始化由 ready-time apply 调用相同 source initFunc。

## 4. 分步骤实施与验收

每一步只触及其必要文件，单批 patch 不超过 5 个文件。先并行探索相关文档/代码，等待所有探索结束再修改；测试执行可并行分组，但避免多个 Gradle 进程竞争同一输出目录。

### 步骤 0：固定基线与新增失败用例

**改动**：先在现有 sema/compile-gate 测试类新增合法 `@onready var child: Node = $Child` 的正向断言，使其在当前实现上失败；同时新增 `$Child.name` / `%Unique.get_name()` 等显式 receiver 后缀的 onready 正向用例——注意当前基线上它们的失败来自 leaf DEFERRED 沿链传播，并非四处后缀 boundary，后者要到步骤 1 放行 leaf 后才会真正执行；可另加 resolved-head 基线（如非 onready 的 `Node.new().name` 或 static `build().payload`）让 suffix boundary 在当前实现上立即可见。同批加入间接 self 别名正向用例（`$".".get_name()`、`$Child.get_parent().get_name()`）——当前失败同样只应来自既有能力边界，不要求出现 alias 专用拒绝；并固定 direct-self 负向用例（见 §5 N7）。保留普通 property 的原有 deferred 测试与非 onready 后缀负向测试，不能把它们改写成 onready 正向用例。

**验收**：确认失败来自 GetNode DEFERRED 传播 / compile blocker 或（resolved-head 基线的）既有 suffix boundary，不是 parse、annotation attachment 或测试元数据缺失。记录命令和日志；不改 build/config，不运行 clean 或全套测试。

**实施状态（2026-10-27）：已完成。** 新增 12 个测试方法，分布在 5 个既有测试类中（未新增测试文件）：`FrontendExpressionSemanticSupportTest`（`analyzeResolvesGetNodeLeafsInsideOnreadyPropertyInitializer`、`analyzeDefersGetNodeInsideStaticAndNonNodeOnreadyInitializers`、`analyzeResolvesOnreadyGetNodeFromDeclaringClassInsideInnerClass`、`analyzeResolvesDuplicateOnreadyGetNodeLeafWhileUsageKeepsDuplicateError`、审阅后补入的 `analyzeDefersGetNodeLeafBehindDamagedOnreadyAnnotation`）、`FrontendBodyOwnerProceduresExprTypeTest`（`analyzeResolvesOnreadyGetNodeHeadsWhileSuffixOwnerBoundariesStaySealed`、`analyzeKeepsDirectSelfBoundariesInsideOnreadyPropertyInitializer`，并为 GetNode 补一条 import）、`FrontendBodyOwnerProceduresChainBindingTest`（`analyzeSealsOnreadySuffixMembersAtOwnerBoundaryWithResolvedGetNodeHeads`）、`FrontendCompileCheckAnalyzerTest`（`analyzeForCompileReleasesOnreadyGetNodeLeafsWhileNonOnreadyStaysBlocked`、`analyzeForCompileKeepsNonEligibleAndSealedOnreadyBoundariesBlocked`、`analyzeForCompileKeepsOnreadySealedBoundariesOwnedByUpstreamDiagnostics`、`analyzeForCompileKeepsGetNodeInsideUnrecordedOnreadyInitializerLambdaSilent`）、`FrontendTypeCheckAnalyzerTest`（`analyzeChecksOnreadyGetNodeInitializerAgainstDeclaredSlotType`）。

其中 7 个用例在当前基线上失败，失败原因均为 leaf `DEFERRED` 沿链传播或由其引起的 deferred warning / compile blocker——例如 `expected: <RESOLVED> but was: <DEFERRED>`（leaf fact）、`expected: <1> but was: <4>`（3 个 onready leaf 各自产生 deferred warning）及 `arg_self` 根 `DEFERRED`（blocked `self` 实参与 deferred 链头并存时链头状态取胜）；未出现 parse、annotation attachment 或测试元数据类失败。后缀用例断言“leaf RESOLVED ＋ 既有 owner boundary 仍封口”的双层合同（含 resolved-head 基线 `build().payload` 与间接 self 别名 `$".".get_name()`、`$Child.get_parent().get_name()`），其在步骤 2 前的失败是预期记录。4 个负向/锚定用例（static、非 Node、direct-self 边界、未记录 lambda）在基线上即通过，证明这些既有边界不被本阶段诉求触碰。运行命令：`script/run-gradle-targeted-tests.sh --tests <上述 12 个方法选择器>`（两次调用；首次运行发现一个 selector 与方法名 `...WhileSuffixOwnerBoundariesStaySealed` 不一致导致该用例未执行，已更正重跑；未改 build/config，未跑全套）。

### 步骤 1：定向放行 GetNode leaf typing

**改动**：实现 §3.1：在 `FrontendPropertyInitializerSupport` 新增 `checkNodeDerivedOnreadyInitializer`，`resolveGetNodeExpressionType` 的 property 分支复用它。更新原“所有 initializer 都在入树前执行”的过宽注释，说明普通初始化与 ready-time initializer 的区别。

**验收**：shared `analyze(...)` 中合法 onready 的每个 GetNode leaf fact 是 `RESOLVED(Node)`，没有对应 deferred warning；`analyzeForCompile(...)` 无该 GetNode blocker。`$Child.name` 此时仍可因 suffix boundary 失败，由步骤 2 解决。普通 initializer 仍 shared warning / compile error，普通函数及已记录 lambda 行为不变。

**实施状态（2026-10-27）：已完成。** 生产改动共 3 个文件：`FrontendPropertyInitializerSupport` 新增 `checkNodeDerivedOnreadyInitializer(context, analysisData)` 谓词（非 static 声明 ＋ 经 parser-damage 过滤的 `onready` 注解 ＋ declaring class `checkAssignable(Node)`；仅作执行时机闸门，placement/重复诊断仍归 usage analyzer）及 `FrontendAnalysisData`/`FrontendGdAnnotation`/`java.util.List` 三条 import；`FrontendBodyOwnerProcedures.resolveGetNodeExpressionType` 的 property 分支在谓词为真时返回 `RESOLVED(Node)`，否则保留原 `DEFERRED` 路径，并把“所有 initializer 都在入树前执行”的过宽注释改写为区分 ready-time 与普通初始化；`FrontendCompileCheckAnalyzer` 中“property-initializer uses stay DEFERRED”注释更新为“eligible `@onready` initializers resolve，其余仍由 published-fact scan 门控”。无 parser、lowering、runtime 或新诊断类别改动。

**验收结果**：定向批次 `script/run-gradle-targeted-tests.sh --tests <18 个选择器>`（步骤 0 新增 12 个用例 ＋ 既有锚定 `analyzeDefersGetNodeExpressionInsidePropertyInitializer`、`analyzeResolvesGetNodeExpressionToNodeInsideNodeDerivedInstanceFunction`、`analyzeBlocksGetNodeExpressionInsideUnrecordedLambdaWithinRecordedPropertyInitializer`、`analyzeSealsPropertyInitializerSuffixesOnCurrentInstanceHierarchyBoundaries`、`analyzeForCompileUpgradesDeferredWarningsIntoCompileBlockingErrors` 及 `FrontendAnnotationUsageAnalyzerTest` 整类）全部 `BUILD SUCCESSFUL`；随后以 6 个测试类整类重跑作回归网亦全部通过。证据要点：合格 onready leaf 发布 `RESOLVED(Node)` 且无 `sema.deferred_expression_resolution`/`sema.compile_check`；普通、static、非 Node 岛仍 `DEFERRED`/compile-blocked；`$Child.name` 等后缀仍由 `sema.unsupported_chain_route` 封口（leaf 已 RESOLVED）；direct self/super 各产 1 条 `sema.unsupported_binding_subtree`、裸成员按既有合同静默 `BLOCKED`（`sema.binding` 为空）；`analyzeChecksOnreadyGetNodeInitializerAgainstDeclaredSlotType` 确认 `Node` 标注与 leaf 类型相容且不引入额外 type_check 诊断。

**过程修正（测试侧，不改合同）**：① 后缀用例 leaf 计数由 8 更正为 7（`$"."` 计 1 个 GetNodeExpression）；② 两个用例的裸成员边界断言从 `unsupported_binding_subtree`/`sema.binding` 计数修正为与既有合同一致——裸成员边界仅静默 `BLOCKED`（无诊断），`self`/`super` 才是 subtree 诊断来源（5 条）。

### 步骤 2：放行 onready 岛显式 receiver 后缀

**改动**：实现 §3.2：在 `FrontendChainReductionHelper` 约 765/792/836/1967 四处后缀 boundary 调用点接入同一 predicate，仅对合格岛跳过该步的 ownerClass boundary；binding/head/self/super/type-meta 检查不动。

**验收**：onready 岛内 property / instance-method / method-reference / signal 四条 suffix 路由在 `Node` owner 成员上解析成功并发布正确 fact，含嵌套链、cast 与条件表达式包装；间接 self 别名表达式（`$".".get_name()`、`$Child.get_parent().get_name()`）作为合法显式表达式被接受，不产生 alias 专用诊断，也不做运行时 identity 拒绝；显式 `self.` / `super.` / 隐式 self 裸成员（字段/方法/方法引用/信号，含继承）以及以 cast/条件/括号包装或作实参的 `self` 仍由原 boundary owner 拒绝——在 `FrontendBodyOwnerProceduresChainBindingTest` 与 `FrontendBodyOwnerProceduresExprTypeTest` 中按方法归属断言 boundary owner 或 generic compile diagnostic，以实际发布事实为准，不假定裸成员错误一律出现 top-binding 诊断；非 onready 的同类/继承 receiver 链（既有 `build().payload` / `read()` / `changed` 负向用例）保持 UNSUPPORTED。

**实施状态（2026-10-27）：已完成。** 生产改动仅 1 个文件：`FrontendChainReductionHelper.reducePropertyStep` 在 object-receiver 分支计算一次 `onreadyInitializer`（复用 `checkNodeDerivedOnreadyInitializer`），其 property/signal/method-reference 三处 boundary 以 `onreadyInitializer ? null : detailFor...` 短路；`reduceInstanceMethodStep` 在 `propertyInitializerContext` 局部变量旁同样预取并短路 `detailForResolvedMethodBoundary`。两处 type-meta 检查（static load/static method）与 head/binding 边界均未触碰；同包调用无需 import。

**测试更新与新增（3 个既有测试类）**：

- `FrontendBodyOwnerProceduresChainBindingTest`：`analyzeSealsOnreadySuffixMembersAtOwnerBoundaryWithResolvedGetNodeHeads` 重写为 `analyzeResolvesOnreadySuffixMembersAcrossExplicitReceiverRoutes`——fixture 扩至 8 个 GetNode leaf（含 `build().payload` helper 结果 receiver、`($Child as Node).name`、条件两臂各带后缀、`$Child.get_parent().get_name()` 嵌套链），断言 property/`Signal`/method-reference(`Callable`) 三条 `resolvedMembers` 与 instance-method `resolvedCalls` 全部 `RESOLVED` 且 binding/resultType 正确，`sema.unsupported_chain_route` 等类别为空、`hasErrors` 为 false；新增 `analyzeKeepsSuffixBoundariesSealedInsideIneligibleOnreadyIslands`——static 与非 Node 岛的 `build().payload` 保持 `UNSUPPORTED` 且 placement 错误归 annotation usage owner。
- `FrontendBodyOwnerProceduresExprTypeTest`：`analyzeResolvesOnreadyGetNodeHeadsWhileSuffixOwnerBoundariesStaySealed` 重写为 `analyzeResolvesOnreadyGetNodeHeadsAndExplicitReceiverSuffixes`——10 个 leaf `RESOLVED(Node)`、9 条链根 `RESOLVED` 且 `publishedType` 匹配（StringName/Callable/Signal/int），`mixed` 中裸 `payload` 元素与容器根保持静默 `BLOCKED`、全诊断集无 error；`analyzeKeepsDirectSelfBoundariesInsideOnreadyPropertyInitializer` 扩入 `(self if true else $Child).name`、`$Child.set_name(name)` 及审阅后补入的裸方法引用 `get_name`、裸信号 `renamed`——11 个根全部 `BLOCKED`、subtree 诊断 5→6（裸成员/裸成员实参均静默）。
- `FrontendCompileCheckAnalyzerTest`：`analyzeForCompileKeepsOnreadySealedBoundariesOwnedByUpstreamDiagnostics` 改写为 `analyzeForCompileReleasesOnreadySuffixRoutesWhileDirectSelfStaysBlocked`——`$Child.name` 与 `%Unique.get_name()` 的 leaf/member/call fact 均 `RESOLVED`、无 `sema.unsupported_chain_route`/`sema.compile_check`、无 error；`self.payload` 半段仍断言 `unsupported_binding_subtree`。

**验收结果**：`script/run-gradle-targeted-tests.sh --tests <5 个新增/改写用例 ＋ 4 个非 onready 负向锚点>` 全部通过；随后 6 个测试类整类回归 `BUILD SUCCESSFUL`。间接 self 别名用例（`$".".get_name()`、`$Child.get_parent().get_name()`）作为正向解析，无 alias 专用诊断；blocked `self` 实参使 `$Child.is_ancestor_of(self)`/`set_name(name)` 在后缀 boundary 之前即整体 `BLOCKED`，证明放行不波及 direct-self 边界。

### 步骤 3：类型、注解与恢复边界回归

**改动**：在 `FrontendExpressionSemanticSupportTest`、`FrontendCompileCheckAnalyzerTest`、`FrontendTypeCheckAnalyzerTest`、`FrontendAnnotationUsageAnalyzerTest`、`FrontendBodyOwnerProceduresChainBindingTest`、`FrontendBodyOwnerProceduresExprTypeTest` 中按职责添加 §5 的 focused cases。

**验收**：typed `Node`、object 上转、无类型 Variant/type hint、显式 cast、无隐式下转均符合既有合同；static/非 Node/重复/错误位置仍有正确 owner 的错误；损坏注解与未记录 lambda 不制造新的事实；同 module 的合法 sibling 仍解析成功；alias 路径上的对象存活/空值/类型检查照常发布，不因间接 self 责任划分而削弱。

### 步骤 4：锁定 PROPERTY_INIT 的 CFG/LIR 组合

**改动**：主要在 `FrontendLoweringBodyInsnPassTest` 新增使用真实 compile analysis 的 onready cases；如需要更精确 shell/graph 断言，再补 `FrontendLoweringFunctionPreparationPassTest` / `FrontendLoweringBuildCfgPassTest`，不复制 lowering 实现。

**验收**：真实 property `initFunc` 指向 hidden、non-static、带 owner `self` 的 helper；helper 有 entry/CFG/ReturnInsn；每次查找都有 decoded NodePath、Node receiver assign 和 engine `get_node` 调用，返回值经既有 typed boundary 写入 helper return。

容器中的两个 GetNode 按源码顺序各求值一次；条件表达式只执行选中分支，不能把两侧查找或 suffix 指令都提前到前驱 block。带后缀的链在 native `get_node` 调用之后发射对应 suffix 指令（property read / call_method / method-reference / signal），结果经 typed boundary 写回。间接 self 别名用例（`$".".get_name()`、`$Child.get_parent().get_name()`）走完全相同的 NodePath / `get_node` / suffix 管线，不新增 provenance 或 identity-guard 分支；既有 `AssertObjectLiveInsn` 等存活检查照常发射。`%` 与 quoted forms payload 正确；同名脚本方法不参与 shorthand 解析。保留缺 fact / 缺 self 的既有 fail-fast 测试。

### 步骤 5：生成 C 与真实 Godot 生命周期验收

**改动**：在 `FrontendLoweringToCProjectBuilderIntegrationTest` 新增两个具名、可单独筛选的测试（均为拟新增方法名）：

- `lowerFrontendOnreadyNodeLiteralGeneratesC`：环境无关的 frontend→LIR→C 文本断言（指令顺序、调用形态、helper 接线），不依赖 Zig/Godot；环境缺失时本 gate 照常执行，不得跳过。
- `lowerFrontendOnreadyNodeLiteralBuildsNativeLibraryAndRunsInGodot`：在既有 assumptions 下编译 native 库并由真实 Godot 运行；Zig/Godot 缺失时仅该引擎验收记为未完成，不影响 C gate。

具名 native/Godot 集成测试采用现有同步生命周期测试的空场景节点模式：`ProjectSetup` 的 `sceneNodes` 传 `List.of()`，由 validation 脚本通过已注册的顶层 runtime class `.new()` 创建未入树实例，检查默认值并准备 children/unique owner 后才入树；不让 runner 预先挂载被测实例。此路径与下述成对 fixture 的 harness/probe 构造不同。

新增 `runtime/virtual/onready_node_literal_lookup.gd` 与 `onready_node_literal_replay.gd` 的 source/validation 配对 fixture；lookup fixture 应包含 StringName/Callable/Signal 后缀字段以覆盖 §3.2 的四条路由。runner 会将成对 fixture 的首个外层类编译产物以 `ProjectSetup(artifacts, List.of(SceneNodeSpec(TARGET_NODE_NAME, runtimeClassName, ".", Map.of())), TestScriptSpec(validation))` 形式自动挂载为空 TargetNode（`GdScriptUnitTestCompileRunner` 约 138–149 行），因此构造方案必须区分 harness 与 probe：

- 外层类只作 harness：不携带任何依赖查找的 `@onready` 字段，仅提供工厂方法（如 `func create_probe() -> Node` 返回未入树的 inner 实例）；inner `Probe` / `Base` / `Sub` 才承载 `@onready` 字段，模式参照既有 `unit_test/script/member/signal_inherited_and_engine.gd` 的 harness+factory。
- validation 是独立的 GDScript 验证脚本，经预挂载 harness 的 `target.call("create_probe")` 取得未入树的 probe，先验证字段默认值，再创建真实 children：`add_child` → `child.owner = probe` → `child.unique_name_in_owner = true`，最后把 probe 挂入已在树上的父节点触发真实 READY。不得在 validation 中写 `Outer.Inner.new()`（ClassDB 的 inner 名与源码层级不同）。lookup 与 replay 两个 fixture 都遵守同一 harness/probe 分工。
- 登记到 `GdScriptUnitTestCompileRunnerTest` 的显式 fixture 清单及 `GdScriptEngineVirtualOverrideRuntimeTest.SCRIPT_RESOURCE_PATHS`；不能只写 `.gd` 文件就声称运行覆盖。使用既有 runner，不改构建脚本、runner 或 `test_project`。

**日志与 marker**：source/validation 成对存在，但只有 validation 在全部断言通过后打印 `__UNIT_TEST_PASS_MARKER__`；source 不发生 placeholder 替换。validation 顶部以 `# gdcc-test:` 指令声明输出约束（内联表述，非代码片段）：`output_not_contains=ERROR`、`output_not_contains=engine method call failed`、`output_not_contains=assert_object_live failed`、`output_not_contains=Node not found`。具名集成测试对 `combinedOutput` 做对应的 `assertFalse` 包含断言，并断言 stop/success 标记。

**验收**：

1. `_init` 和入树前读取 typed Node 字段为 null、无类型字段为 nil；即便 child 在构造后、READY 前才加入，READY 后也能找到，不产生构造期 get-node 错误。
2. `$Child`、嵌套路径、quoted `$`、两种 `%` 都与 validation 持有的实际节点做对象 identity 比较；不是只测非空或同名。后缀字段按精确语义断言：`StringName` 字段与实际节点成员等值；Signal 字段满足 `get_object() == child` 且 `get_name() == StringName("renamed")`；Callable 字段满足 `get_object() == child`、`get_method() == StringName("get_name")` 且 `call() == child.name`。用户 `_ready` 首次读取时字段已初始化。
3. 无用户 `_ready` 的类仍初始化；同模块继承中父/子 onready 依次完成且子字段可见于最终用户 `_ready`；`super._ready()` 不重放。
4. 从树中移除 probe，移走但暂不释放旧 child，并换入同路径的新 child/新 unique 注册；不调用 `request_ready()` 再入树时字段仍指旧对象；再移除、`request_ready()`、再入树后字段指向新对象，证明查找被重新求值而不是缓存第一次结果。
5. 旧节点在 identity 验证完成前保持存活，避免悬空 Node 指针影响断言；验证后分别释放，不能用 Node（非 RefCounted）存活来推断 retain/release 次数。
6. 构造 default apply 不调用 source helper；ready overwrite apply 调用它且不走 setter。生成 C/既有 ownership 回归共同证明没有另造 first-write、释放路径或重复求值。
7. probe 首次入树前由 validation 命名（如 `probe.name = "ProbeName"`）并把 Child 挂到 probe 下；`$".".get_name()` 与 `$Child.get_parent().get_name()` 的初始化结果都等于 probe 的实际 name——仅证明合法查找/调用成功，不保证任意 alias 字段依赖安全（间接 self 防御由用户负责）；不构造 unsafe/null/freed 用例，也不把运行错误包装成成功。

Zig/Godot 缺失时 native/Godot 具名测试按现有 assumption 跳过并明确记为“未完成引擎验收”，不能计作通过；`lowerFrontendOnreadyNodeLiteralGeneratesC` 不因环境跳过。仅 validation 保留 pass marker；合法路径配置失败时不得容忍 engine method call failed 等日志。

### 步骤 6：事实源同步、注释与最终门槛

**改动**：在功能和测试落地后，分批更新 §6 列出的文档及受影响注释，不能在当前计划阶段把支持状态改成“已完成”。

**验收**：源码和文档不再把所有 property GetNode 一概描述为 DEFERRED，同时明确普通/static/parameter-default/lambda、direct-self 裸成员与 type-meta 边界仍在，链式后缀只对合格 onready 岛的显式表达式 receiver 放行。间接 self 防御写作用户责任表述，不留编译器 alias-proof 待办。新增文档型代码注释用 `///`，仅解释语义或不变量；不引用本计划、阶段编号、待办或“以后实现”。不在 record 字段上写注释，必要说明放 record 顶部 `@param`。

收集每项测试的 executed / skipped / failed 和日志位置；只有 sema、compile gate、LIR、C、真实 Godot 五层相关验收完成后，才能宣称一期 compile-ready。未通过的层留明确阻塞项，不靠修改测试期望掩盖实现错误。

## 5. 测试矩阵与运行方式

| 分组 | 新增/保留的输入或状态 | 必须断言 |
| --- | --- | --- |
| S1 | 合法 Node 派生类的 `$` / `%` 五种形态 | 每个 leaf `RESOLVED(Node)`；无 GetNode resolved-call fact；compile 无 blocker |
| S2 | 间接 Node 基类、inner Node class | 用 initializer 的 declaring class 而非文件顶层 owner；不依赖 class_name 拼写 |
| S3 | `Node` / `Object` / 无显式类型 / `:=` 属性 | Node 稳定类型、合法上转；未显式类型 metadata 仍 Variant，type hint 由原 owner 发出 |
| S4 | `Camera3D = $Camera` 与显式 `as Camera3D` 对照 | 不凭路径隐式下转；前者按原兼容规则拒绝，后者复用既有 cast |
| S5 | array value、条件、cast | leaf 可定型但 root 仍须稳定且类型兼容；另一个坏子树不能被 onready 标记豁免 |
| S6 | onready 内四种显式 receiver 后缀：`$Child.name`、`%Unique.get_name()`、`$Child.get_name`、`$Child.renamed` | property / instance-method / method-reference / signal 四条路由均解析；成员 owner 为 `Node` 也放行；发布 `StringName` / `Callable` / `Signal` 类型 fact |
| S7 | 嵌套链、cast、条件包装的后缀：`$Child.get_parent().get_name()`、`$".".get_name()`、`($Child as Node).name`、条件两臂各带后缀 | 每步 suffix 单独定型且共享 leaf fact；`$".".get_name()` 与 `$Child.get_parent().get_name()` 是有意正向句法用例，即使可能返回 self 也放行；sema/compile 与运行时安全样本双层覆盖（运行时见步骤 5 验收 7） |
| P1 | 间接 self 责任划分（已确认） | alias 用例（`$"."`、`get_parent()` 回 self）放行且无 alias 专用诊断；direct self 在任何语法位置仍拒绝；对象存活/类型/成员/调用/所有权检查照常；不设“所有 alias 均静态安全”的验收 |
| S8 | onready 内显式 static helper / 构造对象结果上的实例后缀 | receiver 为显式表达式即放行，即使成员 owner 是当前类或其祖先 |
| N1 | 无 onready 的 instance/static property | 原 DEFERRED 与 shared warning / compile blocker 保留 |
| N2 | static / 非 Node onready | annotation usage 错误保留；不授予 RESOLVED(Node)，也不授予 suffix 放行；不误用“static function”错误解释属性 |
| N3 | 重复注解、local/const 等不合法位置 | usage owner 与重复数/range 正确；重复 Node 属性即使 RHS 稳定仍不能进入 lowering |
| N4 | 损坏 annotation / 损坏 initializer 与合法 sibling | 现有 parse/skip 恢复，无伪造 fact；合法 sibling 可继续 |
| N5 | onready initializer 内 lambda 返回 `$Child` | lambda 不记录、body 不深入、内层 GetNode 不发布 fact；root owner 持有错误 |
| N6 | parameter default 中直接 `$` / `%`、其中 lambda | 保持原 island fail-closed，不因同样存在 self helper 而放开 |
| N7 | onready 中显式 `self.x` / `super.m()`、隐式 self 裸字段/方法调用/方法引用/信号（含继承成员）、await；`self` 以 cast/条件/括号包装或作实参：`(self as Node).name`、`(self if true else $Child).name`、`$Child.is_ancestor_of(self)`、`$Child.set_name(name)` | direct self 在任何语法位置（含实参）仍由原 binding/head boundary owner 拒绝；按实际发布事实断言 boundary owner 或 generic compile diagnostic，compile 仍失败 |
| N8 | 非 onready 的同类/继承 receiver 链（`build().payload` / `read()` / `changed`）与 static/non-Node 岛的同类后缀 | 原 ownerClass boundary 与 deferred 行为不变；既有负向测试不改写 |
| L1 | PROPERTY_INIT 五种路径形式及 typed/untyped 返回 | self/signature/entry/return、decoded payload、Node receiver、typed materialization 完整 |
| L2 | `[$A, $B]` 与 `$A if true else $Missing` | 顺序与次数；未选分支无调用，不能因 opaque leaf 而 eager 执行 |
| L3 | 脚本同名 `get_node(NodePath)` | shorthand 的 receiver 仍 native Node；生成 C 走 ENGINE route |
| L4 | 四条 suffix 路由各自的指令顺序与条件包装：`$Child.name`、`$Child.get_name()`、`$Child.get_name`、`$Child.renamed`、`$Child.name if true else %Unique.get_name()` | `LoadPropertyInsn` / `CallMethodInsn` / `ConstructCallableInsn` / `ConstructSignalInsn` 四类 suffix 指令各在 native `get_node` 之后发射；literal 条件的两臂链不 eager 求值且不引入隐式 self；不使用不存在的 `Node.m()` / `n()` 伪方法；suffix 结果经 typed boundary 写回 |
| R1 | 真实 READY 前后及无用户 `_ready` | 默认值 → 正确 identity；用户 `_ready` 看到已初始化字段 |
| R2 | 同模块继承 / super | 父→子与声明顺序，super 不重复；不能用手调 `_ready` 替代引擎 READY |
| R3 | 替换节点后有/无 request_ready 的再入树 | 不请求不重放，请求后查找新对象；保持旧 Node 存活直至比较完成；identity 断言证明查找/调用返回正确对象，不构成 alias 隔离保证 |

现有测试锚点（保留，不转换其语义）：

- `FrontendExpressionSemanticSupportTest.analyzeDefersGetNodeExpressionInsidePropertyInitializer`
- `FrontendCompileCheckAnalyzerTest.analyzeForCompileUpgradesDeferredWarningsIntoCompileBlockingErrors`
- `FrontendCompileCheckAnalyzerTest.analyzeForCompileKeepsGetNodeInsideUnrecordedPropertyInitializerLambdaSilent`
- `FrontendLoweringBodyInsnPassTest.runLowersNodePathLiteralPropertyInitializer`
- `FrontendLoweringBodyInsnPassTest.runLowersGetNodeShorthandAsEngineGetNodeCallTriple`
- `FrontendLoweringBodyInsnPassTest.runLowersGetNodeUniqueNameAndQuotedForms`
- `FrontendLoweringToCProjectBuilderIntegrationTest.lowerFrontendOnreadySyncLifecycleBuildsNativeLibraryAndRunsInGodot`
- `FrontendBodyOwnerProceduresChainBindingTest.analyzeSealsSameClassInstanceSuffixRoutesInPropertyInitializerAsUnsupported`（非 onready 同类 receiver 后缀，保持负向）
- `FrontendBodyOwnerProceduresChainBindingTest.analyzeSealsInheritedInstanceSuffixRoutesInPropertyInitializerAsUnsupported`（非 onready 继承 receiver 后缀，保持负向）
- `FrontendBodyOwnerProceduresExprTypeTest.analyzePropagatesUnsupportedSameClassSuffixPropertyInitializerRoutesWithoutExprOwnedDuplicateErrors`（同类 receiver 后缀的 expr-typing 传播负向）
- `FrontendBodyOwnerProceduresExprTypeTest.analyzePropagatesUnsupportedInheritedSuffixPropertyInitializerRoutesWithoutExprOwnedDuplicateErrors`（继承 receiver 后缀的 expr-typing 传播负向）

Linux 必须使用过滤噪声的 wrapper。新增方法实现后按其实际名称执行 focused selector；以下类名命令用于相应阶段的相关类回归，不代表本次已经运行：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendExpressionSemanticSupportTest,FrontendCompileCheckAnalyzerTest,FrontendAnnotationUsageAnalyzerTest,FrontendTypeCheckAnalyzerTest
script/run-gradle-targeted-tests.sh --tests FrontendLoweringBodyInsnPassTest,FrontendLoweringFunctionPreparationPassTest,FrontendLoweringBuildCfgPassTest
script/run-gradle-targeted-tests.sh --tests FrontendBodyOwnerProceduresChainBindingTest.analyzeSealsSameClassInstanceSuffixRoutesInPropertyInitializerAsUnsupported,FrontendBodyOwnerProceduresChainBindingTest.analyzeSealsInheritedInstanceSuffixRoutesInPropertyInitializerAsUnsupported,FrontendBodyOwnerProceduresExprTypeTest.analyzePropagatesUnsupportedSameClassSuffixPropertyInitializerRoutesWithoutExprOwnedDuplicateErrors,FrontendBodyOwnerProceduresExprTypeTest.analyzePropagatesUnsupportedInheritedSuffixPropertyInitializerRoutesWithoutExprOwnedDuplicateErrors
script/run-gradle-targeted-tests.sh --tests FrontendLoweringToCProjectBuilderIntegrationTest.lowerFrontendOnreadyNodeLiteralGeneratesC
script/run-gradle-targeted-tests.sh --tests FrontendLoweringToCProjectBuilderIntegrationTest.lowerFrontendOnreadyNodeLiteralBuildsNativeLibraryAndRunsInGodot
script/run-gradle-targeted-tests.sh --tests CCodegenOnreadyTest
script/run-gradle-targeted-tests.sh --tests GdScriptUnitTestCompileRunnerTest.listsExpectedBundledUnitScripts,GdScriptEngineVirtualOverrideRuntimeTest.virtualRuntimeFixturesRelyOnEngineDrivenProcessingOnly
script/run-gradle-targeted-tests.sh --tests GdScriptEngineVirtualOverrideRuntimeTest.virtualRuntimeFixturesRemainBundledAndPaired,GdScriptEngineVirtualOverrideRuntimeTest.compilesAndValidatesVirtualRuntimeScripts
```

wrapper 已添加 `--no-daemon --info --console=plain`。迭代时优先具体新增方法；最后一条动态 factory 会覆盖其 virtual runtime 清单，并非精确筛选单个 `.gd`。不运行 `clean build`，不为了本任务修改 wrapper、Gradle 或 CI。

## 6. 实施后的文档同步清单

必须更新：`frontend_node_literal_implementation.md` 的支持状态/矩阵/sema/gate/限制，`frontend_rules.md` 的 GetNode 与 initializer 边界，`frontend_property_init_lowering_implementation.md` 的支持面，`frontend_compile_check_analyzer_implementation.md` 的事实扫描示例，`frontend_lowering_cfg_pass_implementation.md` 的 GetNode 适用 owner，`frontend_chain_binding_expr_type_implementation.md` 的 §4.1/4.2 边界表述与 §4.6 GetNode 分类，`frontend_signal_support.md` §5 的 signal boundary 表述，以及 `doc/module_impl/backend/onready_class_implementation.md` 的 gate 边界/后续工作。

initializer 合同变化仅限于合格 onready 岛的显式表达式实例 receiver 后缀：凡是把链式成员边界一概描述为“按成员 owner 拒绝”的文档（同上 `frontend_rules.md`、`frontend_property_init_lowering_implementation.md`、`frontend_chain_binding_expr_type_implementation.md`、`frontend_signal_support.md`、node literal 文档的限制段等）都要改成句法 direct-self 表述，只加 onready 显式 receiver 例外、不整段删除边界；binding/head/self/super/type-meta 边界与普通/default/lambda 岛的描述不变。同步时间接 self 防御写作用户责任（已确认），不留编译器 alias-proof 待办或 deferred 任务。本次任务不修改这些事实源。

同时核对：`frontend_type_check_analyzer_implementation.md` 的稳定 fact 与 annotation 职责表述，`frontend_lowering_skeleton_pre_pass_implementation.md` 与 `frontend_lowering_func_pre_pass_implementation.md` 的 helper 支持描述，`doc/test_error/test_suite_engine_integration_known_limits.md` 的属性限制，以及 `doc/test_suite.md` 的覆盖清单。前述未带目录文件均在 `doc/module_impl/frontend/`。只修改确已过时的句子，不顺带改旧有边界。

`frontend_annotation_implementation.md`、lambda/default/转换矩阵、LIR/backend/runtime/ownership 规范原则上合同不变；核对引用和测试锚点即可。任何实际合同变化必须单独说明证据，不能从本计划反向推写事实源。

## 7. 责任划分与架构保留事项

### 7.1 节点链头与 initializer 的 receiver 边界：句法 direct-self 与已确认的责任划分

应用户关于 guard 可否只禁显式/隐式 self 的可行性提问，原版“链式访问需要 receiver 来源追踪/共享 receiver 模型扩展”的论断先被细化为句法 direct-self 边界；随后用户明确决定：间接 self 别名/回调返回 self 的防御由脚本使用者负责，成为已确认的规划约束——不是编译器能力缺口，也不是架构任务或待决事项（仅限既有计划的合法 onready 岛）。合法 onready 岛不做运行时 identity 隔离，也不需要 `ReceiverState` provenance 字段、alias 追踪或模型重设计。

完整例子：

```gdscript
class_name OnreadyNodeChainBoundary
extends Node

@onready var child: Node = $Child
@onready var child_name: StringName = $Child.name
@onready var child_name_from_call: StringName = %Unique.get_name()
@onready var renamed_signal: Signal = $Child.renamed
@onready var get_name_callable: Callable = $Child.get_name
@onready var parent_name: StringName = $Child.get_parent().get_name()
@onready var self_name_via_dot: StringName = $".".get_name()
```

七项全部放行：suffix 的 receiver 是显式表达式即满足边界，即使成员 owner（`Node.name` / `Node.get_name` / `Node.renamed`）属于当前实例层级。`$".".get_name()` 与 `$Child.get_parent().get_name()` 是有意的正向句法用例——`$"."` 实际返回 self、`get_parent()` 可能返回 self——在句法规则下仍允许；间接 self 的自有状态、声明依赖与重入正确性由用户负责（已确认）；成员存在性、类型兼容与运行时错误检查照常适用。

仍被拒绝的仅 direct-self 表面形式：显式 `self` / `super` 与隐式 self 裸成员（字段/方法调用/方法引用/信号，含继承）在任何语法位置——包括 cast/条件/括号包装和实参——都继续由 binding/head owner 封死。合法显式表达式则可能有意地间接 alias self（`$"."`、`$Child.get_parent()` 返回 self 均属被允许的已知形态），句法规则不拦截，防御责任在用户。非 onready 岛的同类链（`build().payload` 等既有负向用例）与 type-meta 检查不变。

间接 self 别名/回调返回 self 的防御已确认为用户责任：编译器不引入 alias 分析、provenance、identity 过滤或专用诊断/恢复/重试，也不以此为名义保留任何 alias-proof 待办。此责任划分不构成对编译器自身生命周期缺陷的豁免（见 §7.2）。

### 7.2 继承与其他既有架构限制

混合 GDScript 子类覆写 `_ready` 时可能绕过 GDCC 组合 impl；调用 super 的时点决定 GDCC 链何时执行，且 DONE 会限制非引擎入口重放。不调用 super 时 GDCC 基类 onready 可能不执行。跨模块 GDCC 父链也不支持。这些是既有 backend 文档记录的限制，不是本次 Node 查找引入的新回归。

例如 GDScript 子类先读 GDCC 基类的 onready 字段、随后才 `super._ready()`，前一次读取可能仍是默认值。完整修复需要统一 ScriptInstance、ClassDB 按名入口与 engine virtual 生命周期，不在本次 gate 调整中解决；不得以复制赋值到子类 `_ready` 的方式绕过。真实验收采用同模块纯 GDCC 继承，不把混合继承结果包装为完全 Godot 兼容。间接 self 的用户责任划分不改变、也不豁免这些既有生命周期偏差。

## 8. 本次调研与计划验收记录

- 已并行完成五组只读探索：前端规范、生命周期/下游规范、sema、lowering/backend、测试与 lambda/default/CFG 扩展规范，并由主代理精读相关代码复核关键结论。
- 当前环境 MCP 服务列表为空，无法使用 IntelliJ `list_directory_tree` / `get_file_problems`；已用全量递归文件列表及源码阅读替代目录/内容检查。未修改代码，因此无 reformat 操作。
- 六个既有 focused test method、两条非 onready 后缀负向锚点（`FrontendBodyOwnerProceduresChainBindingTest` 的 same-class / inherited suffix-route 测试）及 runner/integration test 类的路径已机械核对；本次为文档变更，未执行 Gradle、Zig 或 Godot，不能据此声称 baseline 测试通过。
- 2026-10-26 修订：应用户可行性提问，§7.1 原“链式访问需要 receiver 来源追踪/共享 receiver 模型扩展”的论断细化为句法 direct-self 边界并纳入推荐范围；同步核对四处后缀 boundary（约 765/792/836/1967 行）、binding/head 封死点、`ReductionRequest.analysisData()` 已存在及其调用点清单等代码事实。本次仅改本文档，未执行测试，未核对上游。
- 2026-10-26 第二轮修订：按 lead 汇总的两份审阅报告（A:P1–P4、C:ONR-01–05）及 lead 补充发现逐条修订，映射见 §9；全部修订经 lead 独立核实，新增 selector、fixture 分工、Insn 类名与文档路径均经机械核对。本次仅改本文档，未执行 Gradle、Zig 或 Godot，未核对上游。
- 未联网；Godot 语义依据仓库现有文档中记录的源码结论及本地实现，不宣称本次重新核对上游版本。若实施中遇到其未覆盖的兼容分歧，再经批准读取固定版本上游并记录路径/版本。
- 两位原审阅者（`review-expert-a`、`review-expert-c`）已完成对第二轮修订版本的审阅并复核通过（见 §9.1 历史轮次）；该批准仅针对当时版本，不覆盖本轮用户决定修订，也不代表功能、测试或实施批准。
- 2026-10-26 第三轮修订：用户明确决定将间接 self 别名/回调返回 self 的防御归为脚本使用者责任（已确认的规划约束，仅限既有计划的合法 onready 岛），本轮同步 §1/§3.2/§4/§5/§6/§7 全文表述；alias-proof 不再是任何待决或架构事项，仅混合继承等继承架构限制保持 deferred。本次仅改本文档，未执行测试，未核对上游；两位原审阅者随后复核通过本轮修订（A e8ccffe6、C dd814cea，APPROVE，见 §9.2），批准仅限第三轮文档，不代表代码、测试或实施批准。

## 9. 审阅问题跟踪

### 9.1 历史轮次（第二轮修订版，已复核通过）

lead 已对两份审阅报告的全部发现（含 low 级措辞修正）对照源码独立核实后采纳；两位原审阅者随后完成复核并全部通过（A e8ccffe6、C dd814cea），未发现新的 medium/high/blocking 问题。以下批准仅针对第二轮修订版本，不覆盖第三轮用户决定修订。

| 编号 | 已接受的修订 | 状态 |
| --- | --- | --- |
| A:P1 | 步骤 5 分开具名集成测试的空 sceneNodes 与成对 fixture 的 harness/probe 构造；仅 validation 打印 marker 并携带输出拒绝指令，集成测试断言 `combinedOutput` | 已修订，原审阅者 A 复核通过（Resolved） |
| A:P2 | 步骤 0 与 §2 修正执行路径：`$Child.name` 当前基线失败来自 leaf DEFERRED 沿链传播而非四处后缀 boundary；补充 resolved-head 基线、具名负向测试类/方法与既有负向 selector | 已修订，原审阅者 A 复核通过（Resolved） |
| A:P3 | 全文措辞改为“应可行性提问形成的推荐范围、尚待用户最终批准实施”；§7.1 删除 alias 通道的过度承诺并明确 direct self 全域禁止 | 已修订，原审阅者 A 复核通过（Resolved） |
| A:P4 | API 签名改为内联接口设计表述；`detailForResolved*Boundary` 伞称改为一次性实名枚举、其余位置称“四处后缀 boundary”；chain-binding / signal 文档补入 §2 依据与 §6 同步清单 | 已修订，原审阅者 A 复核通过（Resolved） |
| C:ONR-01 | 步骤 5 harness/probe 构造拆分：外层挂载 TargetNode 只作 harness，inner 承载 `@onready`，validation 经工厂取得 inner 实例，禁用 `Outer.Inner.new()` | 已修订，原审阅者 C 复核通过（Resolved） |
| C:ONR-02 | 同 A:P3：推荐范围与批准状态表述 | 已修订，原审阅者 C 复核通过（Resolved） |
| C:ONR-03 | 同 A:P1 的日志部分：source/validation 分工、pass marker 与输出指令 | 已修订，原审阅者 C 复核通过（Resolved） |
| C:ONR-04 | 同 A:P4：新增同步文档限定为 onready 显式 receiver 例外、不整段删除边界 | 已修订，原审阅者 C 复核通过（Resolved） |
| C:ONR-05 | 补入总资源清单 `listsExpectedBundledUnitScripts` 与原 same-class/inherited suffix 负向 selector；新增四路 onready 方法落地后登记真实 selector | 已修订，原审阅者 C 复核通过（Resolved） |
| lead 补充 | C 文本断言拆分为环境无关的 `lowerFrontendOnreadyNodeLiteralGeneratesC`；Zig/Godot 缺失时 C gate 照常执行，仅引擎验收 pending | 已修订，两位审阅者均确认可行 |

最终验收记录（2026-10-26）：两位原审阅者复核通过全部接受项，无未解决的 medium/high/blocking 问题；本轮修订未执行 Gradle、Zig 或 Godot，无运行时测试证据，不能据此声称 baseline 或引擎验收通过；间接 self 别名防御现确认为用户责任（§7.1），仅混合继承等继承架构事项保持 deferred。

### 9.2 当前轮次（第三轮：间接 self 责任用户决定）

本轮修订按用户明确决定同步全文表述，lead 已完成全量 diff 审阅并交由两位原审阅者复核，A（e8ccffe6）与 C（dd814cea）均 APPROVE、无新增问题；该批准仅覆盖第三轮文档 diff，不以 §9.1 历史批准替代本轮复核，也不代表功能、测试或实施批准。

| 审阅者 | 复核范围 | 状态 |
| --- | --- | --- |
| A | 第三轮全量 diff | 复核通过（APPROVE） |
| C | 第三轮全量 diff | 复核通过（APPROVE） |

第三轮验收记录（2026-10-26）：两位原审阅者复核通过本轮全部修订，无未解决问题；本轮未执行 Gradle、Zig 或 Godot，无运行时测试证据，不能据此声称 baseline 或引擎验收通过；间接 self 防御确认为用户责任（§7.1），功能整体仍待实施。

### 9.3 实施轮次（步骤 0＋1 代码实现，2026-10-27）

`review-expert-c` 对步骤 0/1 的代码 diff 完成审阅，结论 APPROVE（仅限步骤 0/1 范围）：未发现 blocker/high/medium 问题，谓词保守性、诊断归属与 leaf/suffix 分离均获确认。唯一 low 级建议——为“损坏 `@onready` 投影不授予能力”补直接覆盖——已采纳并落地为 `analyzeDefersGetNodeLeafBehindDamagedOnreadyAnnotation`（断言 skipped-subtree 成员关系、leaf `DEFERRED`、完好 sibling `RESOLVED`、parser 独占诊断、无 `sema.annotation_usage`），运行通过；修复后全部 6 个相关测试类整类回归 `BUILD SUCCESSFUL`。本轮执行了 Gradle 定向测试，未运行 Zig 或 Godot；步骤 2–6 仍为计划未实施部分。

### 9.4 实施轮次（步骤 2 代码实现，2026-10-27）

`review-expert-c` 对步骤 2 的代码 diff 完成审阅，结论 APPROVE（仅限步骤 2 范围）：未发现 blocker/high/medium 问题，四处接入完整性、谓词短路仅限 ownerClass boundary、type-meta/head/binding 边界不受影响均获确认。唯一 low 级建议——为 N7 补裸方法引用与裸信号的负向锚点——已采纳并落地为 `analyzeKeepsDirectSelfBoundariesInsideOnreadyPropertyInitializer` 中的 `bare_method_ref`/`bare_signal` 用例（11 根 `BLOCKED`、subtree 诊断仍为 6），运行通过；复核再次 APPROVE，无剩余问题。本轮执行了 Gradle 定向测试（9 个用例选择器 ＋ 6 个测试类整类回归），未运行 Zig 或 Godot；步骤 3–6 仍为计划未实施部分。
