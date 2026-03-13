# GDCC 前端语义分析器调研报告（按当前代码库校对）

- 日期：2026-03-12
- 校对范围：本报告只依据当前仓库中的文档、源码、测试，以及当前工作区已存在的代码文件进行修订；不再把仓库外 `E:/Projects/gdparser` 本地副本或旧的 GitHub 快照当作本报告的直接事实源。

---

## 1. 执行摘要

基于当前代码库，旧版报告里最需要修正的结论有 8 点：

1. **`gdparser` 版本已经明确升级到 `0.5.1`。** 当前事实源是 `build.gradle.kts`，scope analyzer 也已经直接复用库内置 `ASTWalker`，而不是继续维护本地 walker 封装。
2. **GDCC frontend 已不再只是“parse + 少量 skeleton 测试”。** 目前除了解析和类骨架构建，还已经落地了 shared `DiagnosticManager` + 阶段边界 snapshot、`Scope` 协议、`ClassScope` / `CallableScope` / `BlockScope`、真实的 `FrontendScopeAnalyzer` lexical graph、restriction-aware lookup、signal 的 unqualified scope 语义，以及一批 frontend/scope/shared-resolver 测试。
3. **但 frontend 仍然没有真正的 body-level semantic analyzer。** 当前仍缺 binder、assignable analyzer、表达式类型推断、调用/成员访问分析结果、统一 `AnalysisResult`、AST body lowering。
4. **`FrontendBindingKind` 的旧结论已经过时。** 当前代码里已经有 `SIGNAL` 和 `TYPE_META`，旧报告中“缺少 `TYPE_META`”“signal 还未补位”的说法不成立。
5. **`ClassRegistry` 现在同时承载“宽松旧接口”和“严格新协议”。** `findType(...)` 仍是宽松兼容入口；真正适合未来 binder/type namespace 的，是严格的 `resolveTypeMeta(...)` 与 `Scope` 协议。
6. **`FrontendClassSkeletonBuilder` 与 `FrontendModuleSkeleton` 的现状应描述得更准确。** 它们不只是收集 `class_name / extends / signal / var / func`；现在还会通过 `FrontendSourceClassRelation` 与 `FrontendInnerClassRelation` 显式记录每个 `FrontendSourceUnit` 对一个顶层类和多个同源 inner `ClassDeclaration -> skeleton` pair 的归属，并保留 `classDefs()` 作为仅顶层类的兼容视图。
7. **signal 相关状态比旧报告更前进。** `ClassScope` 的 unqualified signal lookup 已经落地并有测试；当前工作区还出现了 `ScopeSignalResolver` / `ScopeResolvedSignal` 及对应测试，说明 receiver-based signal metadata lookup 已经开始落代码，虽然 frontend binder 仍未接上。
8. **旧报告里大量“按外部 `gdparser` AST 全量节点覆盖面下结论”的段落，应当降级或删除。** 当前仓库能直接证明的是：frontend 已依赖 `gdparser:0.5.1`，并消费了 AST 通用模型与少量声明节点；至于 `gdparser` 全量 AST 形态，若要继续做跨仓库调研，应单独写附录，而不应混进这份“按当前代码库校对”的报告里。

---

## 2. 本次校对依据

### 2.1 代码事实源

- `build.gradle.kts`
- `src/main/java/dev/superice/gdcc/frontend/parse/GdScriptParserService.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendBindingKind.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendModuleSkeleton.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendSourceClassRelation.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendScopeAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/AbstractFrontendScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/ClassScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/CallableScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/BlockScope.java`
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
- `src/main/java/dev/superice/gdcc/scope/ResolveRestriction.java`
- `src/main/java/dev/superice/gdcc/scope/Scope.java`
- `src/main/java/dev/superice/gdcc/scope/ScopeLookupResult.java`
- `src/main/java/dev/superice/gdcc/scope/ScopeLookupStatus.java`
- `src/main/java/dev/superice/gdcc/scope/ScopeValueKind.java`
- `src/main/java/dev/superice/gdcc/scope/ScopeTypeMetaKind.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendPropertyAccessResolver.java`
- `src/main/java/dev/superice/gdcc/scope/resolver/ScopeMethodResolver.java`
- `src/main/java/dev/superice/gdcc/scope/resolver/ScopePropertyResolver.java`
- 当前工作区新增文件：`src/main/java/dev/superice/gdcc/scope/resolver/ScopeSignalResolver.java`、`src/main/java/dev/superice/gdcc/scope/resolver/ScopeResolvedSignal.java`

### 2.2 文档事实源

- `doc/module_impl/common_rules.md`
- `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
- `doc/gdcc_type_system.md`
- `doc/gdcc_low_ir.md`
- `doc/gdcc_c_backend.md`
- `doc/gdcc_ownership_lifecycle_spec.md`
- `doc/module_impl/backend/load_static_implementation.md`

### 2.3 测试事实源

- `src/test/java/dev/superice/gdcc/frontend/parse/FrontendParseSmokeTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendInheritanceCycleTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/ClassScopeResolutionTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/ClassScopeSignalResolutionTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/FrontendStaticContextValueRestrictionTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/FrontendStaticContextFunctionRestrictionTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/FrontendStaticContextShadowingTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/FrontendInnerClassScopeIsolationTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/FrontendNestedInnerClassScopeIsolationTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/ScopeCaptureShapeTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/ScopeChainTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/ScopeTypeMetaChainTest.java`
- `src/test/java/dev/superice/gdcc/scope/ScopeProtocolTest.java`
- `src/test/java/dev/superice/gdcc/scope/ClassRegistryTypeMetaTest.java`
- `src/test/java/dev/superice/gdcc/scope/resolver/ScopeMethodResolverTest.java`
- `src/test/java/dev/superice/gdcc/scope/resolver/ScopePropertyResolverTest.java`
- 当前工作区测试：`src/test/java/dev/superice/gdcc/scope/resolver/ScopeSignalResolverTest.java`

### 2.4 明确移除的旧依据

以下内容不再作为本报告的直接事实源：

- 仓库外 `E:/Projects/gdparser/**` 的文件路径罗列
- “某天手工核对 GitHub 上游 Godot/Godot Docs 原始文件”的逐项结论
- 仓库中并不存在的 `doc/module_impl/frontend_implementation_plan.md`

这些信息如果未来仍然需要，应以单独“跨仓库调研附录”维护，而不是混入当前仓库状态报告。

---

## 3. 当前 GDCC frontend 的真实状态

## 3.1 解析层已经稳定接入 `gdparser:0.5.1`

当前 `build.gradle.kts` 明确声明：

- `implementation("com.github.SuperIceCN:gdparser:0.5.1")`

`GdScriptParserService` 当前职责非常清晰：

1. 调 `GdParserFacade` 解析 CST
2. 调 `CstToAstMapper` 生成 AST
3. 把 lowering diagnostics 映射为 `FrontendDiagnostic`
4. 出错时返回空 `SourceFile` 与 `parse.internal` 诊断，而不是直接把异常抛给调用方

因此，当前 parse phase 的准确描述应当是：

- **已经有稳定的 tolerant parse + diagnostic mapping 服务**
- **frontend 对 `gdparser` 的依赖点目前主要是 parse service 与 AST record 使用**
- **报告不应再把“具体有哪些 AST 节点已在仓库外副本中出现”当作这份文档的中心结论**

## 3.2 类骨架阶段已经超出“只收集声明名字”的程度

`FrontendClassSkeletonBuilder` 与 `FrontendModuleSkeleton` 现在已经完成这些事情：

- 从 `class_name` 或文件名推导类名
- 从 `class_name extends` 或顶层 `extends` 推导父类名；未显式 `extends` 时按 Godot 当前语义默认收口到 `RefCounted`
- 收集 `signal`、`var`、`func` 并注入 `LirClassDef`
- 通过 `FrontendSourceClassRelation` / `FrontendInnerClassRelation` 显式记录每个 source file 的顶层 skeleton 与同源 inner `ClassDeclaration -> skeleton` pair
- 已在成员填充前把 accepted top-level / inner class shell 一并注册进 `ClassRegistry`；inner class 继续通过 relation 显式保存 `lexicalOwner`、`sourceName`、`canonicalName`，其 `LirClassDef#getName()` 冻结为 canonical name
- 检查重复类名
- 检查继承环，并以 diagnostics 形式拒绝 cyclic class subtree
- 用 strict frontend declared-type 路径解析类型提示：先查 lexical gdcc 可见类型，再查 `ClassRegistry` strict type-meta；无法解析时降级到 `Variant` 并发出 `sema.type_resolution` warning
- 对 `export_variable_statement` / `onready_variable_statement` 做有限注解保留

所以，旧报告把它描述成“非常浅的 declaration collector”并不完全准确。更准确的说法是：

- **它仍然只是 skeleton/interface 之前的浅层阶段，但已经不再依赖“每文件一个 classDef、靠平行列表索引配对”的脆弱协议**
- **它已经包含一部分容错、诊断、注解保留、继承合法性检查，以及带 lexical owner / dual-name 语义的 inner class skeleton ownership 记录**
- **其隐式继承语义也必须与上游 Godot 保持一致：无 `extends` 的脚本类默认基类是 `RefCounted`，而不是 `Object`**
- **对普通源码错误的恢复策略也已经开始收口：skeleton phase 更倾向于发 diagnostic 并跳过坏 subtree，而不是直接抛 frontend 异常打断整条 pipeline**

与之对应，`FrontendScopeAnalyzer` 当前也不再通过 `moduleSkeleton.units()` 和 `moduleSkeleton.classDefs()` 的索引对齐来恢复来源关系，而是直接消费 `sourceClassRelations()` 中显式发布的顶层类和 inner `ClassDeclaration -> skeleton` pair。inner class 的独立 lexical boundary 现已在 analyzer 阶段被真正物化；当某个 inner class subtree 没有已发布 relation 时，analyzer 会局部跳过该 subtree，而不是扩大成整条 source 的失败。

与此同时，frontend 诊断主链也已经收敛到 shared `DiagnosticManager` + 边界 `DiagnosticSnapshot`：parser、skeleton 与 analyzer 都显式接收同一 manager，`FrontendSemanticAnalyzer` 在 skeleton 之后与 scope phase 之后各发布一次 diagnostics snapshot，而不是通过局部 list 或异常对象透传普通源码错误。

## 3.3 frontend scope 架构已经从“计划”变成了已落地基础设施

当前仓库里已经有完整的 frontend lexical scope 实现：

- `ClassScope`
- `CallableScope`
- `BlockScope`
- 公共基类 `AbstractFrontendScope`

以及 shared `Scope` 协议：

- 独立的 value / function / type-meta 三套 namespace
- `ScopeLookupResult<T>` + `ScopeLookupStatus` 的 tri-state 结果
- `FOUND_ALLOWED` / `FOUND_BLOCKED` / `NOT_FOUND`
- `ResolveRestriction.unrestricted()` / `instanceContext()` / `staticContext()`

这意味着旧报告里这些说法已经不成立：

- “scope tree 还没有真正实现”
- “static-context shadowing 还只是设计设想”
- “type-meta restriction 语义还没有冻结”

当前更准确的状态是：

- **协议层已经冻结并有测试锚定**
- **frontend binder 还没实现，但它未来应直接消费现有 scope/resolver 基础设施，而不是重造一套名字解析逻辑**

## 3.4 `FrontendBindingKind` 与 `ScopeValueKind` 的旧结论需要修正

当前代码里：

- `FrontendBindingKind` 已经包含 `SIGNAL` 与 `TYPE_META`
- `ScopeValueKind` 也已经包含 `SIGNAL` 与 `TYPE_META`

因此，旧报告中“binding kind 仍缺 `TYPE_META`”“signal 还没有稳定分类”的说法已经过期。

更准确的描述应当是：

- **枚举层面的语义占位已经补齐**
- **但这些 binding kind 还没有被真正的 frontend binder 消费，因为 binder 本身尚未落地**

## 3.5 `ClassRegistry` 现在是“宽松兼容接口 + 严格新协议”的并存体

当前 `ClassRegistry` 既保留了旧接口，也已经提供严格语义入口：

### 3.5.1 宽松入口：`findType(...)`

它仍适合：

- 旧调用方的兼容需求
- 与 frontend declared type 无关的历史宽松调用点

它不适合：

- frontend declared type 的最终解析
- identifier binder 的最终 type-meta 判定
- 静态语义中的严格 namespace 决议

### 3.5.2 严格入口：`resolveTypeMeta(...)`

当前测试已经明确覆盖：

- builtin 类型
- engine class
- gdcc class
- global enum
- 严格容器类型，如 `Array[T]`、`Dictionary[K, V]`
- singleton / utility function / unknown name 不应误判为 type-meta

因此这份报告应把重心放在：

- **未来 interface/body phase 应优先走 `resolveTypeMeta(...)` 与 `Scope` 协议**
- **frontend declared type 已经切到 strict resolver；`findType(...)` 只应保留给旧兼容入口，不应再回流进 frontend binder 或 skeleton type hint**

## 3.6 backend 与 shared resolver 仍然是 frontend 的语义事实源

这点旧报告总体方向是对的，但需要按当前仓库状态写得更精确。

### 3.6.1 方法调用

`BackendMethodCallResolver` 仍然体现出当前 lowering 事实：

- 支持 `GDCC` / `ENGINE` / `BUILTIN`
- 支持 `OBJECT_DYNAMIC` / `VARIANT_DYNAMIC`
- 实例方法解析实际委托给 `ScopeMethodResolver`

因此，frontend 将来做 call analysis 时：

- **不应另起一套与 backend 脱节的 dispatch 语义**
- **已知对象 method miss 允许进入 `OBJECT_DYNAMIC` 路径，这一点仍然成立**

### 3.6.2 属性访问

`BackendPropertyAccessResolver` 仍然体现出当前 lowering 事实：

- 已知对象 property 沿继承链静态查找
- metadata 缺失 / inheritance cycle / property miss / owner 非法时 fail-fast
- builtin 与 object property 路径分离

因此，frontend 若将来保留“宽松属性动态回退”策略，就必须在 frontend 侧显式改写到动态路径，而不是把一个静态 miss 直接交给 backend。

## 3.7 signal 状态比旧报告更前进，但仍未形成完整前端闭环

按照当前代码与文档的交集，可以把 signal 状态描述为三层：

### 3.7.1 已落地：unqualified signal scope 语义

当前 `ClassScope` 已经支持：

- direct signal 进入 value namespace
- inherited signal 查找
- static context 下 signal 命中后返回 `FOUND_BLOCKED`
- blocked hit 继续构成 shadowing，不回退 global 同名绑定

这一点已被 `ClassScopeSignalResolutionTest` 等测试锚定。

### 3.7.2 当前工作区已出现：receiver-based signal metadata resolver

当前工作区代码中已经有：

- `ScopeSignalResolver`
- `ScopeResolvedSignal`
- `ScopeSignalResolverTest`

这说明相对 `scope_architecture_refactor_plan.md` 中仍写作 S2/S3 边界的文字描述，代码侧已经开始落地 receiver-based signal metadata lookup。

更准确的结论应写成：

- **shared signal resolver 已在当前工作区出现并有测试**
- **frontend binder 接入 signal binding 仍未落地**

### 3.7.3 仍未落地：frontend binder 消费 signal 事实

当前仍然没有：

- `my_signal` / `self.my_signal` / `obj.some_signal` 的统一 frontend binding result
- signal member access 与 property/member access 的统一分析结果表
- signal 相关 lowering / `.emit(...)` 语义闭环

---

## 4. 旧版报告中不再符合事实的部分

下面这些结论应从旧版报告中移除或改写。

## 4.1 “frontend 只有 parse 服务、统一诊断、class skeleton builder，以及少量 parse/skeleton 测试”不再成立

当前代码库至少还已经有：

- frontend scope 三层实现
- shared `Scope` 协议与 tri-state lookup
- static/instance restriction 语义
- inner class lexical isolation 测试
- signal scope 解析测试
- shared resolver 级别的方法/属性/signal 测试

更准确的说法是：

- **真正的 body analyzer 还没有**
- **但它所依赖的 scope/protocol/resolver 底座已经形成**

## 4.2 “`FrontendBindingKind` 缺少 `TYPE_META` / `SIGNAL`”不再成立

当前 `FrontendBindingKind` 已经有：

- `LOCAL_VAR`
- `PARAMETER`
- `CAPTURE`
- `PROPERTY`
- `SIGNAL`
- `UTILITY_FUNCTION`
- `CONSTANT`
- `SINGLETON`
- `GLOBAL_ENUM`
- `TYPE_META`
- `UNKNOWN`

因此，旧版相关段落应删除，而不是继续把它当成待办事项。

## 4.3 “scope tree / static-context shadowing / type-meta restriction 还只是设计草案”不再成立

当前：

- `ResolveRestriction` 已有稳定 API
- `ScopeProtocolTest` 已验证 blocked hit shadowing 行为
- `ScopeTypeMeta` lookup 的 restriction-invariant 语义已有测试

因此这些内容应从“建议”改写为“当前已落地协议”。

## 4.4 “signal 仍只停留在计划文字里”不再成立

当前准确状态是：

- S0/S1：已落地
- receiver-based resolver：当前工作区已出现实现与测试
- binder：仍未落地

也就是说，signal 现在不应再被写成“纯计划”，而应写成“部分落地、尚未闭环”。

## 4.5 “仓库内存在 `doc/module_impl/frontend_implementation_plan.md`”不成立

当前仓库里并没有这个文件。frontend 相关的事实源文档应以：

- `doc/module_impl/frontend/scope_architecture_refactor_plan.md`

为主。

## 4.6 基于仓库外 `gdparser` 副本的全量 AST 节点结论，应从主报告移除

旧报告里大量段落围绕：

- 某些 `gdparser` AST 节点是否存在
- 是否接入 lowering
- 与 Godot 原生 parser 的结构差异

这些内容在“跨仓库调研”里可以有价值，但它们不属于“按当前代码库校对”的主报告事实源。

当前更稳妥的写法应当是：

- `gdcc` 已依赖 `gdparser:0.5.1`
- 当前 frontend 已稳定消费 AST 通用结构与浅层声明节点
- 未来若要展开 `gdparser` AST 形态评估，应另开附录或单独报告

## 4.7 旧版末尾 signal 附录存在编码损坏，应修正

原文件末尾 signal 附录出现乱码，不能再作为可维护内容保留。本次修订已直接删除乱码表述，并把相关事实合并回正文的 signal 状态章节。

---

## 5. 当前仍然缺失的关键语义能力

尽管基础设施比旧报告描述得更成熟，但真正的 semantic analyzer 仍未落地。当前缺口主要有：

1. **没有独立的 frontend interface phase。** 当前只有 skeleton builder，还没有统一的 declaration/interface analysis 结果层。
2. **没有 frontend body phase。** 当前没有 AST 级 binder、表达式类型推断、assignable analyzer、return/suite merge、lambda capture 分析器。
3. **虽然已有统一的 `FrontendAnalysisData`，但真正的 body/interface 分析产物仍未落地。** 当前 side-table 容器已经存在，并稳定承载 annotation / scope / binding / type / resolved member / resolved call 的统一拓扑；其中 annotation、diagnostics 与 lexical `scopesByAst` 已经 live，binding / type / resolved member / resolved call 仍主要等待后续 binder/body phase 正式填充。
4. **没有 frontend binder 对现有 scope/resolver 的正式接线。** `FrontendBindingKind`、`ClassScope`、`ResolveRestriction`、shared resolver 仍主要停留在基础设施层。
5. **没有 AST body -> LIR lowering。** 当前 `FrontendClassSkeletonBuilder` 只产生 `LirClassDef` 的声明骨架，并不生成函数体 LIR。
6. **没有前端级 feature boundary 诊断框架。** 对 `await`、更完整 annotation 语义、base-call/self/cast/type-test 等节点，以及 constructor 的更细粒度语义错误，还没有统一的“recognized but unsupported / deferred” 诊断策略。

---

## 6. 修订后的建议路线

基于当前仓库状态，后续实现路径建议这样表述。

## 6.1 阶段顺序建议

建议将 frontend 的近期阶段写成：

1. `Parse`
2. `Skeleton / Inheritance`
3. `Interface`
4. `Body`
5. `Lowering`

这一定义的理由不再需要依赖外部 Godot 快照，而是直接来自当前仓库自己的分层现实：

- parse 已稳定
- skeleton 已存在
- scope/resolver 基础设施已具备
- body analyzer 与 lowering 之间仍然有明显语义断层

## 6.2 `ClassRegistry.findType(...)` 不应再回流进 frontend declared type

`FrontendClassSkeletonBuilder` 的 declared type 解析现在已经切到 strict frontend 路径：

- lexical gdcc 类型先按 source-local relation + `sourceName` 解析
- builtin / engine / global enum / strict container 再通过 strict registry/type-meta 落地
- unknown type 继续 warning + `Variant` 恢复，但不再猜测为任意 object type

从 interface/body phase 开始，仍建议继续沿用：

- `resolveTypeMeta(...)`
- `Scope.resolveTypeMeta(...)`

不要再把宽松 `findType(...)` 直接带入未来 binder。

## 6.3 未来 binder 应直接接现有 scope / resolver，而不是重写一套规则

当前仓库已经有：

- `ClassScope` / `CallableScope` / `BlockScope`
- `ResolveRestriction`
- `ScopeMethodResolver`
- `ScopePropertyResolver`
- 当前工作区的 `ScopeSignalResolver`

所以未来 binder 最合适的职责是：

- 根据 AST 语法上下文选择正确 namespace / resolver
- 产出 binding、type、call、member side-table
- 负责用户可见诊断

而不是重新发明：

- 名字查找顺序
- static-context blocking 规则
- dynamic fallback 边界

## 6.4 signal 的下一步应是“接线”，不是“重做模型”

signal 相关模型和 scope 语义已经有明显进展，因此下一步重点应当是：

1. 把 `ScopeSignalResolver` 正式纳入 shared resolver 事实源
2. 把 signal binding 接入 frontend binder 结果
3. 明确 `my_signal` / `self.my_signal` / `obj.some_signal` 的分析分流
4. 保持当前 static-context blocked-hit shadowing 语义不回退

不建议再回到“是否要把 signal 先伪装成 property/function”的旧思路。

## 6.5 建议新增的测试重心也应随之调整

当前最值得补的测试，不再是 scope 协议本身，而是基础设施接线后的 frontend 语义测试：

- binder 对 `Scope` / resolver 的接线测试
- strict `TYPE_META` 消费测试
- signal binding 与 signal member access 测试
- constructor / base-call 分析测试
- annotation attachment / annotation target 测试
- recognized-but-unsupported feature 诊断测试

---

## 7. 最终结论

如果只按当前仓库与当前工作区代码来下结论，那么最准确的描述应当是：

> GDCC frontend 已经完成了 `gdparser:0.5.1` 接入、容错解析、类骨架构建、严格/宽松 type lookup 共存、restriction-aware scope 协议、signal 的 unqualified scope 语义，以及一套较完整的 scope/shared-resolver 基础设施；但真正的 frontend semantic analyzer 仍未落地，当前最大的工程任务已经不再是“AST 还缺什么”，而是“如何把现有 scope、type-meta、method/property/signal resolver 正式接入 interface/body 分析结果，并形成稳定的 AST -> LIR 语义闭环”。

---

## 8. 参考资料

### 8.1 当前仓库中的文档

- `doc/module_impl/common_rules.md`
- `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
- `doc/gdcc_type_system.md`
- `doc/gdcc_low_ir.md`
- `doc/gdcc_c_backend.md`
- `doc/gdcc_ownership_lifecycle_spec.md`
- `doc/module_impl/backend/load_static_implementation.md`

### 8.2 当前仓库中的实现

- `build.gradle.kts`
- `src/main/java/dev/superice/gdcc/frontend/parse/GdScriptParserService.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendBindingKind.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/ClassScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/CallableScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/BlockScope.java`
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
- `src/main/java/dev/superice/gdcc/scope/ResolveRestriction.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendPropertyAccessResolver.java`
- `src/main/java/dev/superice/gdcc/scope/resolver/ScopeMethodResolver.java`
- `src/main/java/dev/superice/gdcc/scope/resolver/ScopePropertyResolver.java`
- 当前工作区新增实现：`src/main/java/dev/superice/gdcc/scope/resolver/ScopeSignalResolver.java`、`src/main/java/dev/superice/gdcc/scope/resolver/ScopeResolvedSignal.java`

### 8.3 当前仓库中的测试

- `src/test/java/dev/superice/gdcc/frontend/parse/FrontendParseSmokeTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendInheritanceCycleTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/ClassScopeSignalResolutionTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/FrontendStaticContextShadowingTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/ScopeCaptureShapeTest.java`
- `src/test/java/dev/superice/gdcc/scope/ScopeProtocolTest.java`
- `src/test/java/dev/superice/gdcc/scope/ClassRegistryTypeMetaTest.java`
- `src/test/java/dev/superice/gdcc/scope/resolver/ScopeMethodResolverTest.java`
- `src/test/java/dev/superice/gdcc/scope/resolver/ScopePropertyResolverTest.java`
- 当前工作区测试：`src/test/java/dev/superice/gdcc/scope/resolver/ScopeSignalResolverTest.java`
