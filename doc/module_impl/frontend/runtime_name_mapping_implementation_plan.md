# Frontend runtimeName / canonicalName 映射实施计划

> 本文档是“前端类名映射进入 class identity”这项工作的实施计划，不是事实源。它描述当前代码库下建议采用的改动顺序、改动边界、风险控制与验收细则，供后续落地实现时逐步执行。

## 文档状态

- 状态：实施计划拟定
- 更新时间：2026-03-24
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/resolver/**`
  - `src/main/java/dev/superice/gdcc/lir/**`
  - `src/main/java/dev/superice/gdcc/backend/c/**`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/scope/**`
- 关联文档：
  - `frontend_rules.md`
  - `inner_class_implementation.md`
  - `scope_analyzer_implementation.md`
  - `scope_type_resolver_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `scope_architecture_refactor_plan.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
- 明确非目标：
  - 不把 backend 改造成理解 `sourceName`
  - 不在本轮引入 backend-side 可变 alias 表
  - 不在本轮通过 `ProjectSettings` 写入运行时类名转换规则
  - 不在本轮支持“解析途中动态新增映射”
  - 不在本轮放宽 header `extends` 的既有 MVP 支持面

---

## 1. 固定前提

本计划以下列前提为硬约束，实施时不得改写：

- `runtimeName` 必须成为 frontend class identity 的一部分。
- `canonicalName` 必须从映射后的 `runtimeName` 生成。
- 顶层类必须满足 `runtimeName == canonicalName`。
- backend 继续只接受 `canonicalName`，包括顶层类和内部类。
- 在开始正式 identity 改造之前，frontend 必须先引入统一的模块输入载体。
- 外部提供的类名映射只在编译或解析开始时一次性注入。
- 外部映射只允许映射顶级 gdcc 类。
- 分析过程中不得新增、删除或修改映射条目。
- frontend 仍需解析并保留 `sourceName`。
- parse 结束后，skeleton 必须可以直接使用映射后的身份冻结类名。
- 如果某个顶层类存在映射，则它的内部类 `canonicalName` 前缀也必须随之变化。
- 用户可见诊断允许直接展示映射后的名称。
- 其他 semantic analyzer 的既有职责边界不能被破坏。
- 继承链查找、属性解析、方法解析必须继续正常工作。
- skeleton 产出的 `LirClassDef.getName()` 必须始终写入映射后的 `canonicalName`。

---

## 2. 当前代码库事实与主要改动面

### 2.1 现有实现里已经写死的旧假设

以下旧假设必须在实施中显式拆除：

- `FrontendSourceClassRelation` 仍假定顶层类只有一个 `name`，并且 `sourceName == canonicalName`。
- `FrontendClassSkeletonBuilder.discoverTopLevelHeader(...)` 直接把顶层 `sourceName` 同时写成 `canonicalName`。
- `FrontendClassSkeletonBuilder.buildSourceClassRelationShell(...)` 为顶层 shell 创建 `LirClassDef` 时，直接使用未映射的顶层名。
- `ClassRegistry.gdccClassSourceNameByCanonicalName` 当前文档和实现都偏向“只给 inner class 存 source override”。
- `ScopeTypeMeta.sourceName` 当前同时承担两种职责：
  - lexical type namespace lookup key
  - 用户可见类型名/诊断展示名
- 多处 frontend 诊断和 helper 默认把 `sourceName()` 当作展示名使用。

### 2.2 已确认受影响的核心代码路径

这项工作的改动不会只停留在 skeleton，而会贯穿以下链路：

- `FrontendSemanticAnalyzer.analyze(...)` / `analyzeForCompile(...)`
  - 需要新增外部映射入口，并把映射向 skeleton 阶段下传。
- `FrontendClassSkeletonBuilder`
  - 需要在 header discovery / accepted freeze 阶段引入映射后的 identity。
  - 顶层类和内部类的 `canonicalName` 生成规则都要改。
  - top-level duplicate、canonical conflict、inheritance-cycle、unsupported-superclass diagnostic 都会受到影响。
- `FrontendSourceClassRelation` / `FrontendInnerClassRelation` / `FrontendOwnedClassRelation`
  - 需要承载 `runtimeName`，并删除“顶层类只有一个 name 就够了”的假设。
- `ScopeTypeMeta`
  - 需要把“lookup key”和“展示名/runtime identity”拆开。
- `AbstractFrontendScope`
  - 当前 `defineTypeMeta(...)` 以 `typeMeta.sourceName()` 为 key；这个行为本身仍可保留，但需要配合新的字段语义。
- `ClassRegistry`
  - 仍然只按 canonical 注册 gdcc class。
  - 但需要允许顶层映射类也携带 source override，而不再只服务 inner class。
- `ClassScope`
  - 继承链、属性解析、方法解析依赖 `ClassDef#getName()/getSuperName()` 的 canonical 合同。
  - 只要 canonical 生成正确，`walkInheritedClasses(...)` 的主逻辑可以保持不变。
- `ScopeMethodResolver` / `FrontendChainReductionHelper` / 其他使用 `ScopeTypeMeta.sourceName()` 组消息的路径
  - 需要切换到新的 runtime/display 字段，避免继续展示源码名。
- backend C codegen / template
  - 当前已直接使用 `classDef.name` / `classDef.superName`。
  - 这正好符合“backend 只吃 canonicalName”的目标，原则上不需要额外引入 mapping 逻辑。

### 2.3 已确认可以保持不动的主干

以下主干不需要被设计性重写：

- `LirClassDef` / `ClassDef` 仍保持 canonical-only 身份。
- backend 模板仍继续使用 `classDef.name` / `classDef.superName`。
- `ClassScope` 的继承查找继续依赖 canonical superclass 名称。
- value namespace / function namespace / type-meta namespace 的三路 scope 协议不需要重构。
- header `extends` 仍保持“source-facing frontend 协议，不等同于 shared declared-type resolver”的边界。

---

## 3. 目标模型

### 3.1 三种名称的职责划分

建议把 frontend class identity 明确拆成三层：

- `sourceName`
  - 源码里的声明名字。
  - 继续作为当前 module lexical type namespace 的 lookup key。
  - 继续服务 `extends Shared` 这类 source-facing header 绑定。
- `runtimeName`
  - frontend steady-state 中用于展示、调试、诊断和 identity 比较的“运行时名称”。
  - 对顶层类，它来自外部映射后的顶层名。
  - 对内部类，它由映射后的顶层 canonical 前缀派生。
- `canonicalName`
  - registry、`ClassDef` / `LirClassDef`、LIR、backend、继承链查找统一使用的稳定身份。
  - 当前合同下建议继续令 `canonicalName == runtimeName`。

### 3.2 建议冻结的名称生成规则

- 顶层类：
  - `sourceName = 源码 class_name 或文件名推导结果`
  - `runtimeName = externalMapping.getOrDefault(sourceName, sourceName)`
  - `canonicalName = runtimeName`
- 内部类：
  - `sourceName = 源码 inner class 名`
  - `runtimeName = parentCanonicalName + "$" + sourceName`
  - `canonicalName = runtimeName`

这个规则满足用户提出的全部硬约束：

- 顶层类 `runtimeName == canonicalName`
- 内部类 canonical 前缀会自动跟随“映射后的顶层 canonicalName”
- backend 只需继续读取 canonical
- source-facing lookup 仍然可以基于 `sourceName`

### 3.3 外部映射对象的建议形态

不要把它做成可变 service，也不要让 `ClassRegistry` 自己维护一份“逐步增长的 alias 表”。

建议在 frontend 入口引入一个一次性冻结的不可变值对象，例如：

- `FrontendTopLevelRuntimeNameMap`
- `FrontendClassNameMapping`

它的行为应尽量简单：

- 输入 key 只允许顶层 gdcc `sourceName`
- value 只允许目标顶层 `runtimeName`
- 构造时完成基本合法性检查
- 运行过程中只读

### 3.4 模块输入载体的建议形态

在开始正式改 `runtimeName / canonicalName` 之前，建议先在 `frontend.parse` 包新增一个统一的模块输入对象：

- `FrontendModule`

建议它至少承载以下字段：

- `moduleName`
- `List<FrontendSourceUnit> units`
- 顶层类名映射表

建议职责保持克制：

- 它只是 frontend 当前阶段的输入载体。
- 它不负责 parse。
- 它不负责 diagnostics。
- 它不负责 registry 发布。

这样做的主要收益是：

- 后续 mapping 不必再在 `FrontendSemanticAnalyzer`、`FrontendClassSkeletonBuilder`、inspection/test helper 之间以额外参数平行传递。
- “模块名 + source units + top-level mapping” 可以在 API 层被视为一个不可分割的输入快照。
- 后续如果还要给 frontend 入口增加别的 module-level 配置，也不需要继续膨胀方法参数列表。

---

## 4. 分步骤实施计划

### 第 1 步：先引入 `FrontendModule`，统一模块级输入

目标：

- 在 `frontend.parse` 包新增 `FrontendModule`。
- 让 frontend 主链路不再单独平行传递 `moduleName`、`List<FrontendSourceUnit>` 和后续要加入的 mapping。
- 为后续的 runtime/canonical identity 改造提供稳定输入边界。

建议改动：

- 在 `src/main/java/dev/superice/gdcc/frontend/parse/` 下新增 `FrontendModule`。
- 该类型建议至少保存：
  - `moduleName`
  - `List<FrontendSourceUnit> units`
  - 顶层类名映射表
- 修改仍然单独传递模块名和 source units 的 frontend 入口，使其改为接收 `FrontendModule`。
- 对外兼容策略建议采用两阶段：
  - 先新增接收 `FrontendModule` 的主入口
  - 再让旧入口委托到 `FrontendModule` 版本

优先涉及文件：

- `src/main/java/dev/superice/gdcc/frontend/parse/FrontendModule.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/debug/FrontendAnalysisInspectionTool.java`
- frontend 相关测试 helper

验收细则：

- frontend 主入口已经可以只接收一个 `FrontendModule`。
- `FrontendModule` 中的 `units` 顺序仍与当前语义保持一致。
- 在尚未启用任何映射时，改造前后的行为完全一致。

第 1 步执行状态（2026-03-24）：

- [x] 1.1 新增 `frontend.parse.FrontendModule`
  - 已新增统一模块输入快照，冻结 `moduleName`、`List<FrontendSourceUnit>` 与顶层类名映射表。
  - 当前阶段映射表仅承载输入，不提前参与 skeleton identity 计算。
- [x] 1.2 切换 `FrontendClassSkeletonBuilder`、`FrontendSemanticAnalyzer`、`FrontendAnalysisInspectionTool` 主入口到 `FrontendModule`
  - 已新增 `FrontendModule` 版本主入口，并完成主链路内部迁移。
  - inspection 单文件路径已改为内部先构造 `FrontendModule`，不再在实现内部平行传递 `moduleName + unit`。
- [x] 1.3 补充并更新单元测试，覆盖正反行为与兼容入口
  - 已新增 `FrontendModuleTest`，锚定输入快照冻结、单文件工厂与边界空值校验。
  - 已在 skeleton/analyzer/inspection 测试中补充 `FrontendModule` 主入口与 compile-only 分流断言。
- [x] 1.4 运行 targeted tests 并记录验收结果
  - 已通过：`FrontendModuleTest`、`FrontendParseSmokeTest`、`FrontendAnnotationParseBehaviorTest`、`GdScriptParserServiceDiagnosticManagerTest`
  - 已通过：`FrontendClassSkeletonTest`、`FrontendClassSkeletonAnnotationTest`、`FrontendInheritanceCycleTest`
  - 已通过：`FrontendSemanticAnalyzerFrameworkTest`、`FrontendAnalysisInspectionToolTest`、`FrontendCompileCheckAnalyzerTest`、`FrontendScopeAnalyzerTest`

### 第 2 步：新增外部映射入口，并把映射固定在 `FrontendModule`

目标：

- 让 `FrontendModule` 成为 runtime 名映射的唯一模块级输入来源。
- 让 `FrontendSemanticAnalyzer` 和 `FrontendClassSkeletonBuilder` 不再各自额外接收一份平行 mapping 参数。
- 让 skeleton 在第一次发现 top-level header 前就能从 `FrontendModule` 读取映射。

建议改动：

- 让 `FrontendModule` 持有不可变的顶层类名映射。
- `FrontendSemanticAnalyzer.analyze(...)` / `analyzeForCompile(...)` 改为接收 `FrontendModule`。
- `FrontendClassSkeletonBuilder.build(...)` 改为接收 `FrontendModule`。
- inspection/debug/test helper 一并切到 `FrontendModule` 入口。
- 若需要兼容旧 API，可保留过渡重载，但统一在内部构造 `FrontendModule` 后再委托。

优先涉及文件：

- `src/main/java/dev/superice/gdcc/frontend/parse/FrontendModule.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/debug/FrontendAnalysisInspectionTool.java`
- 相关测试 helper

验收细则：

- `FrontendModule` 中的 mapping 进入 skeleton 前已经冻结，后续 phase 不再自行修改它。
- frontend 主链路不再需要把 `moduleName`、`units`、mapping 三者分开平行传递。
- 旧调用方如果仍存在兼容重载，在不传 mapping 时行为不变。

第 2 步执行状态（2026-03-24）：

- [x] 2.1 收紧 `FrontendModule` 的映射边界
  - 已把顶层 runtime-name map 的冻结与合法性校验收口到 `FrontendModule` 构造边界。
  - 已新增带 mapping 的 `singleUnit(...)` 工厂，避免 inspection/test helper 重新平行传递模块级输入。
- [x] 2.2 把 skeleton header discovery 切到 `FrontendModule` 快照
  - `FrontendClassSkeletonBuilder.discoverModuleClassHeaders(...)` 已改为直接接收 `FrontendModule`。
  - 这一步只把冻结的模块输入带到 discovery 边界，不提前实施第 3 步的 runtime/canonical identity 改造。
- [x] 2.3 删除残留兼容入口并统一迁移调用方
  - 已删除 `FrontendSemanticAnalyzer` 中 `moduleName + units` 的旧重载。
  - 已删除 `FrontendAnalysisInspectionTool.renderSingleUnitReport(String, ...)` 的旧入口，并完成测试侧迁移。
- [x] 2.4 补充 targeted tests 并记录验收结果
  - 已补充 `FrontendModule` 的 mapping 工厂与 blank-entry 边界测试，锚定冻结语义与 public boundary 校验。
  - 已通过：`FrontendModuleTest`、`FrontendClassSkeletonTest`、`FrontendScopeAnalyzerTest`、`FrontendSemanticAnalyzerFrameworkTest`
  - 已通过：`FrontendAnalysisInspectionToolTest`、`FrontendAnnotationUsageAnalyzerTest`、`FrontendChainBindingAnalyzerTest`、`FrontendCompileCheckAnalyzerTest`
  - 已通过：`FrontendExprTypeAnalyzerTest`、`FrontendVisibleValueResolverTest`、`FrontendExpressionSemanticSupportTest`、`FrontendAssignmentSemanticSupportTest`

### 第 3 步：重构 header identity，先让 top-level header 摆脱“sourceName == canonicalName”

目标：

- 在 header discovery 阶段就同时记录 `sourceName`、`runtimeName`、`canonicalName`。
- 顶层 identity 从 discovery 开始即使用映射后的 runtime/canonical。

建议改动：

- 扩展 `MutableClassHeader` / `AcceptedClassHeader` / `RejectedClassHeader`，显式保存：
  - `sourceName`
  - `runtimeName`
  - `canonicalName`
- `discoverTopLevelHeader(...)` 先解析 `sourceName`，再根据外部 mapping 计算 `runtimeName/canonicalName`。
- `discoverInnerClassHeaders(...)` 不再只传 `parentCanonicalName` 的“未映射旧含义”，而是明确使用“已经冻结好的 parent canonicalName”。
- `freezeAcceptedHeader(...)`、`toRejectedClassHeader(...)`、`describeClassHeaderOrigin(...)` 等辅助路径一并切换到新字段。

重点注意：

- top-level `sourceName` 冲突检查仍然要保留，因为这是 source-facing contract。
- canonical conflict 检查也必须保留，并且现在要能正确拦截“不同顶层 sourceName 映射到同一个 runtime/canonicalName”的情况。
- 这两个冲突检查是互补关系，不能相互替代。

优先涉及文件：

- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`

验收细则：

- 无映射时，顶层类 header 产物与当前行为一致。
- 有映射时，顶层 `AcceptedClassHeader.canonicalName()` 已经是映射后的名字。
- 有映射时，内部类 `canonicalName` 自动使用映射后的顶层前缀。
- “两个不同顶层类映射到同一 runtimeName” 会被 canonical conflict 明确拒绝。

### 第 4 步：把 top-level relation 升级成真正的三名模型

目标：

- 删除 `FrontendSourceClassRelation` 当前“只有一个 `name` 字段”的旧约束。
- 让 top-level relation 与 inner relation 共享同一 identity 语义。

建议改动：

- `FrontendOwnedClassRelation` 增加 `runtimeName()`。
- `FrontendSourceClassRelation` 改为显式保存：
  - `sourceName`
  - `runtimeName`
  - `canonicalName`
- `FrontendInnerClassRelation` 也增加 `runtimeName` 字段。
- `FrontendOwnedClassRelation.validateOwnedRelation(...)` 改为同时校验：
  - relation `canonicalName == classDef.getName()`
  - `superClassRef.canonicalName == classDef.getSuperName()`
  - 对顶层类 `runtimeName == canonicalName`

建议不要再保留 `FrontendSourceClassRelation.name()` 这种含混接口。

优先涉及文件：

- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendOwnedClassRelation.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendSourceClassRelation.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendInnerClassRelation.java`

验收细则：

- top-level relation 能同时返回源码名和映射后的 runtime/canonical 名。
- inner relation 的 `runtimeName/canonicalName` 前缀随顶层映射变化。
- `buildSourceClassRelationShell(...)` 产出的顶层 `LirClassDef.getName()` 已是映射后的 canonicalName。

### 第 5 步：把 `ScopeTypeMeta` 从“lookup key + 展示名”双重职责中拆开

目标：

- 保留 `sourceName` 作为 lexical type namespace 的 lookup key。
- 新增一个明确的 frontend 展示/identity 字段用于诊断与 runtime-facing 呈现。

建议改动：

- 在 `ScopeTypeMeta` 中新增 `runtimeName`，不要复用 `sourceName` 承担展示职责。
- 语义建议冻结为：
  - `canonicalName`：registry/backend identity
  - `sourceName`：当前 lexical namespace lookup key
  - `runtimeName`：用户诊断、frontend static receiver 呈现、frontend identity 展示
- 对 builtin / engine / global enum：
  - `sourceName == runtimeName == canonicalName`
- 对顶层映射 gdcc 类：
  - `sourceName = 源码名`
  - `runtimeName = canonicalName = 映射名`
- 对内部类：
  - `sourceName = 局部类名`
  - `runtimeName = canonicalName = 映射前缀后的完整名`

优先涉及文件：

- `src/main/java/dev/superice/gdcc/scope/ScopeTypeMeta.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendOwnedClassRelation.java`
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
- 所有直接 `new ScopeTypeMeta(...)` 的调用点和测试 helper

验收细则：

- `AbstractFrontendScope.defineTypeMeta(...)` 继续按 `sourceName` 建本地 type namespace。
- `ScopeTypeMeta` 可以同时表达“源码 lookup 名”和“映射后的展示名”。
- 现有 type-meta lookup 协议保持 `FOUND_ALLOWED / NOT_FOUND` 合同不变。

### 第 6 步：调整 registry publication，让顶层映射类也拥有 source override

目标：

- 让 `ClassRegistry` 继续只按 canonical 注册 gdcc class。
- 但 registry 返回 `ScopeTypeMeta` 时，能恢复顶层映射类的 `sourceName`。

建议改动：

- 保留 `gdccClassByName` 的 canonical key 模型，不新开第二套全局名称空间。
- 调整 `addGdccClass(...)` 的 source override 语义：
  - 不再只给 inner class 用
  - 任何 `sourceName != canonicalName` 的 gdcc class 都可以记录 override
- `resolveTypeMetaHere(...)` 为 gdcc class 构建 `ScopeTypeMeta` 时：
  - `canonicalName = registry key`
  - `sourceName = override or canonicalName`
  - `runtimeName = canonicalName`

重点注意：

- global namespace 仍不应该让 inner class 通过 `sourceName` 被全局查到。
- 本轮也不建议让顶层映射类通过 `sourceName` 在 global root 额外暴露第二个 alias。
- source-facing type lookup 仍主要依赖 current-module lexical type namespace，而不是依赖 global root alias。

优先涉及文件：

- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
- `src/test/java/dev/superice/gdcc/scope/ClassRegistryGdccTest.java`
- `src/test/java/dev/superice/gdcc/scope/ClassRegistryTypeMetaTest.java`

验收细则：

- registry 中 gdcc class 仍只能通过 canonical 查到 classDef。
- 对映射后的顶层类，registry 返回的 `ScopeTypeMeta.sourceName()` 仍是源码名。
- 对映射后的顶层类，registry 返回的 `ScopeTypeMeta.runtimeName()` 和 `canonicalName()` 为映射名。

### 第 7 步：修复 scope / analyzer / resolver 的展示名消费点

目标：

- 让用户诊断和 analyzer debug 路径统一展示映射后的名字。
- 同时不破坏 source-facing lookup 规则。

建议改动：

- 以下路径从 `receiverTypeMeta.sourceName()` 切换为 `receiverTypeMeta.runtimeName()`：
  - `ScopeMethodResolver`
  - `FrontendChainReductionHelper`
  - property initializer 相关 helper
  - 其他把 type-meta 名字直接拼进错误消息、unsupported message、debug string 的路径
- header/skeleton 诊断在“说明当前类是谁”时，优先展示 `runtimeName/canonicalName`。
- header/skeleton 诊断在“说明用户写了什么 raw text”时，继续展示源码里的原始 `extends` 文本。

重点注意：

- 诊断消息里的 receiver/type 名字变更会打到大量断言，测试需要整体同步。
- lookup 逻辑本身不要跟着改成按 `runtimeName` 查找。

优先涉及文件：

- `src/main/java/dev/superice/gdcc/scope/resolver/ScopeMethodResolver.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendChainReductionHelper.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- 其他 `ScopeTypeMeta.sourceName()` 直接用于消息拼接的路径

验收细则：

- static load / constructor / method resolution 失败消息在 mapped 类上展示映射后的名字。
- source-facing lexical lookup 仍按源码名工作。
- top binding / chain binding / expr type / compile check 的 side-table 发布不出现结构性退化。

### 第 8 步：确认继承链、属性解析和方法解析只继续依赖 canonical

目标：

- 保证 runtime 映射进入前端 identity 后，不破坏 `ClassScope` / shared resolver 的既有成员解析。

建议改动：

- 不改 `ClassScope.walkInheritedClasses(...)` 的 canonical-walk 主逻辑。
- 只保证所有 gdcc `ClassDef#getName()` 和 `getSuperName()` 都已经是映射后的 canonical。
- 重点回归以下路径：
  - `ClassScope` 的 inherited property lookup
  - `ClassScope` 的 inherited function lookup
  - `ClassRegistry.checkAssignable(...)`
  - `ClassRegistry.getRefCountedStatus(...)`
  - backend 侧 `BackendMethodCallResolver` / `BackendPropertyAccessResolver` 对 owner class 的 canonical 查找

优先涉及文件：

- `src/main/java/dev/superice/gdcc/frontend/scope/ClassScope.java`
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendPropertyAccessResolver.java`

验收细则：

- 内部类继承 mapped 顶层前缀后的 superclass 时，继承链查找仍能命中。
- property/method resolution 对 mapped 顶层类和其内部类都正常。
- `checkAssignable(...)`、`getRefCountedStatus(...)` 对映射后的 canonical 结果保持正确。

### 第 9 步：补齐测试和文档更新

目标：

- 用测试把“source lookup 不变、runtime/canonical 已映射、backend 仍只吃 canonical”同时钉死。

建议新增或更新的测试面：

- skeleton/header：
  - 顶层映射后 `FrontendSourceClassRelation` 的三名模型
  - 顶层映射后内部类 canonical 前缀变化
  - `LirClassDef.getName()` 已写入 mapped canonical
  - 两个顶层 sourceName 不同但 mapping 后 canonical 相同的冲突
- scope/type-meta：
  - top-level mapped class 在 lexical scope 中仍用源码名解析
  - 命中后 `ScopeTypeMeta.sourceName()` 为源码名
  - 命中后 `ScopeTypeMeta.runtimeName()/canonicalName()` 为映射名
- analyzer：
  - top binding / chain binding / expr type 在 mapped 类环境中继续工作
  - compile-only gate 不因为名字映射而误报或漏报
- inheritance/member resolution：
  - mapped 顶层类及其内部类的继承链
  - property lookup
  - method lookup
- backend / LIR：
  - DOM/LIR serializer 与 parser 仍围绕 canonical 名工作
  - C template 生成使用的是映射后的 `classDef.name`

建议更新的文档：

- `inner_class_implementation.md`
- `scope_analyzer_implementation.md`
- `scope_type_resolver_implementation.md`
- `superclass_canonical_name_contract.md`
- `doc/analysis/frontend_semantic_analyzer_research_report.md`

验收细则：

- 新增测试能区分 `sourceName`、`runtimeName`、`canonicalName`。
- 旧测试中凡是写死“顶层 `sourceName == canonicalName`”的断言都已按新合同改写。
- backend / LIR 相关测试继续证明 canonical-only contract 没被破坏。

---

## 5. 建议的实施顺序

建议严格按下面顺序推进，避免中间态把整个 frontend 打碎：

1. 先引入 `FrontendModule`，统一模块级输入边界。
2. 再把 mapping 收口到 `FrontendModule`，停止平行参数传递。
3. 再改 header identity 和 skeleton freeze。
4. 再改 relation / `ScopeTypeMeta` 数据模型。
5. 再改 registry publication。
6. 再改 analyzer / resolver 的展示名消费点。
7. 最后集中修测试和更新事实源文档。

不建议先从 `ClassRegistry` 开始硬塞 alias 表，也不建议在引入统一模块输入载体之前就把 mapping 散着塞进多个 analyzer/build 方法参数里，因为那会把“identity 冻结点”和“模块输入边界”同时推迟，最终还得回头重做 skeleton、relation 和入口 API。

---

## 6. 风险与应对

### 风险 1：`sourceName` 和展示名混用导致 analyzer 行为悄悄变化

表现：

- 本来只想改诊断文本，结果把 lexical lookup key 也改成了 runtimeName。

应对：

- 强制把 `ScopeTypeMeta` 的 lookup key 和展示字段拆开。
- `AbstractFrontendScope.defineTypeMeta(...)` 继续只用 `sourceName` 建 key。

### 风险 2：canonical conflict 与 source-level duplicate 诊断顺序混乱

表现：

- 两个顶层类 sourceName 不同，但 mapping 后冲突，最终 diagnostic 不稳定或被错误归类。

应对：

- 明确保留两类检查：
  - source-facing duplicate top-level name
  - canonical conflict after mapping
- 测试中分别锚定两条 diagnostic。

### 风险 3：继承链因 superclass canonical 未同步映射而断裂

表现：

- `ClassScope.walkInheritedClasses(...)` 或 `ClassRegistry.checkAssignable(...)` 在 mapped inner class 上失效。

应对：

- 要求 accepted skeleton freeze 阶段一次性把所有 `classDef.name/superName` canonicalize 完整。
- 不允许后续 phase 再去“补改 superclass 名称”。

### 风险 4：现有测试大量依赖 `sourceName()` 的旧展示语义

表现：

- 功能没坏，但消息断言和 helper 假设大量失败。

应对：

- 在测试修订时显式区分三类断言：
  - lookup key 断言看 `sourceName`
  - 用户可见消息断言看 `runtimeName`
  - backend/LIR/registry 断言看 `canonicalName`

### 风险 5：inspection/debug 工具继续打印源码名，造成调试错觉

表现：

- 用户看到的 inspection report 和实际 backend canonical 不一致。

应对：

- debug/inspection 输出同步切到 runtimeName 或 canonicalName。
- 如果某处需要展示源码名，显式标注为 sourceName。

---

## 7. 完成标准

这项工作完成时，应同时满足以下标准：

- 顶层类可以在 frontend 入口接收外部 mapping，并在 skeleton 阶段立即冻结为映射后的 runtime/canonical identity。
- 内部类 `canonicalName` 会自动跟随映射后的顶层 canonical 前缀变化。
- `FrontendSourceClassRelation` / `FrontendInnerClassRelation` / `ScopeTypeMeta` 已能同时表达 source lookup 名和 runtime/canonical 身份。
- `LirClassDef.getName()`、`getSuperName()` 以及 backend 模板消费到的类名全部是映射后的 canonical。
- `ClassRegistry`、`ClassScope`、`ScopeMethodResolver`、property/method/inheritance lookup 对 mapped 类不回归。
- 其他 semantic analyzer 的 side-table 发布不出现结构性破坏。
- compile-only gate 行为不因名字映射被意外改变。
- 事实源文档已同步删除“顶层类恒有 `sourceName == canonicalName`”的旧描述。

---

## 8. 最小验收测试矩阵

落地实现后，至少需要针对性跑通以下测试集，并按需要补充新用例：

- `FrontendClassSkeletonTest`
- `FrontendClassSkeletonAnnotationTest`
- `FrontendInheritanceCycleTest`
- `FrontendScopeAnalyzerTest`
- `FrontendSemanticAnalyzerFrameworkTest`
- `FrontendAnalysisDataTest`
- `FrontendAstSideTableTest`
- `ClassRegistryGdccTest`
- `ClassRegistryTypeMetaTest`
- `ClassScopeResolutionTest`
- `ClassScopeSignalResolutionTest`
- `FrontendInnerClassScopeIsolationTest`
- `FrontendNestedInnerClassScopeIsolationTest`
- `FrontendStaticContextValueRestrictionTest`
- `FrontendStaticContextFunctionRestrictionTest`
- `FrontendStaticContextShadowingTest`
- `ScopeTypeResolverTest`
- `ScopeMethodResolverTest`
- `ScopePropertyResolverTest`
- `ScopeSignalResolverTest`

如果本轮实现新增了“映射后的顶层类 + 内部类”专项测试，建议单独增加一组 targeted tests，避免只依赖旧用例间接覆盖。
