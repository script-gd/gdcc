# Frontend runtimeName / canonicalName 映射实施计划

> 本文档是“前端类名映射进入 class identity”这项工作的实施计划，不是事实源。它描述当前代码库下建议采用的改动顺序、改动边界、风险控制与验收细则，供后续落地实现时逐步执行。
>
> 说明：文件名沿用历史命名，但本计划的当前目标模型已经收缩为“双名模型 + 派生 displayName”，不再以持久化 `runtimeName` 作为后续实施方向。

## 文档状态

- 状态：实施计划拟定
- 更新时间：2026-03-25
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

- frontend steady-state class identity 只持久化 `sourceName` 与 `canonicalName`。
- 用户可见展示名若需要稳定暴露，应由 `canonicalName` 派生，不单独持久化第三层 `runtimeName`。
- backend 继续只接受 `canonicalName`，包括顶层类和内部类。
- 在开始正式 identity 改造之前，frontend 必须先引入统一的模块输入载体。
- 外部提供的类名映射只在编译或解析开始时一次性注入。
- 外部映射只允许映射顶级 gdcc 类。
- 分析过程中不得新增、删除或修改映射条目。
- frontend 仍需解析并保留 `sourceName`。
- parse 结束后，skeleton 必须可以直接使用映射后的身份冻结类名。
- 如果某个顶层类存在映射，则它的内部类 `canonicalName` 前缀也必须随之变化。
- 用户可见诊断允许直接展示映射后的名称，但该展示名应从 `canonicalName` 派生。
- 其他 semantic analyzer 的既有职责边界不能被破坏。
- 继承链查找、属性解析、方法解析必须继续正常工作。
- skeleton 产出的 `LirClassDef.getName()` 必须始终写入映射后的 `canonicalName`。

---

## 2. 当前代码库事实与主要改动面

### 2.1 现有实现里已经写死的旧假设

以下旧假设中，前 3 条已在第 3 步落地时拆除；后 2 条仍是当前待处理事实：

- `FrontendSourceClassRelation` 已不再把顶层类压成单一 `name`，而是显式保留 `sourceName + canonicalName`；当前 relation 仍维持双名模型。
- `FrontendClassSkeletonBuilder.discoverTopLevelHeader(...)` 已在 discovery 阶段直接冻结顶层 `sourceName / canonicalName` 双名，不再保留 header 层 `runtimeName` 中间态。
- `FrontendClassSkeletonBuilder.buildSourceClassRelationShell(...)` 现在会为顶层 shell 创建映射后的 canonical `LirClassDef`，relation 也已提供派生 `displayName()`。
- `ClassRegistry.gdccClassSourceNameByCanonicalName` 现在已覆盖任意 `sourceName != canonicalName` 的 gdcc class，而不再只服务 inner class。
- `ScopeTypeMeta` 现在已经显式区分：
  - `sourceName` 作为 lexical type namespace lookup key
  - `displayName()` 作为由 `canonicalName` 派生的用户可见展示名
- `ScopeMethodResolver`、`FrontendChainReductionHelper` 等主要展示路径已切到 `displayName()`。

### 2.2 已确认受影响的核心代码路径

这项工作的改动不会只停留在 skeleton，而会贯穿以下链路：

- `FrontendSemanticAnalyzer.analyze(...)` / `analyzeForCompile(...)`
  - 需要新增外部映射入口，并把映射向 skeleton 阶段下传。
- `FrontendClassSkeletonBuilder`
  - 需要在 header discovery / accepted freeze 阶段引入映射后的 identity。
  - 顶层类和内部类的 `canonicalName` 生成规则都要改。
  - top-level duplicate、canonical conflict、inheritance-cycle、unsupported-superclass diagnostic 都会受到影响。
- `FrontendSourceClassRelation` / `FrontendInnerClassRelation` / `FrontendOwnedClassRelation`
  - 需要维持清晰的 `sourceName / canonicalName` 双名协议，并为后续派生 `displayName` 预留统一消费面。
- `ScopeTypeMeta`
  - 需要把“lookup key”和“展示名”拆开，但展示名应从 `canonicalName` 派生，而不是新增持久化 `runtimeName` 字段。
- `AbstractFrontendScope`
  - 当前 `defineTypeMeta(...)` 以 `typeMeta.sourceName()` 为 key；这个行为本身仍可保留，但需要配合新的字段语义。
- `ClassRegistry`
  - 仍然只按 canonical 注册 gdcc class。
  - 但需要允许顶层映射类也携带 source override，而不再只服务 inner class。
- `FrontendModuleSkeleton`
  - 后续 analyzer 现在只稳定持有 skeleton，不再直接持有 `FrontendModule`。
  - 若要落实“caller-side remap 后再查找”的统一规则，必须把冻结后的 `topLevelCanonicalNameMap` 保留到 `FrontendModuleSkeleton`，避免每条 analyzer 路径各自重新拼装 mapping。
- `ClassScope`
  - 继承链、属性解析、方法解析依赖 `ClassDef#getName()/getSuperName()` 的 canonical 合同。
  - 只要 canonical 生成正确，`walkInheritedClasses(...)` 的主逻辑可以保持不变。
- `ScopeMethodResolver` / `FrontendChainReductionHelper` / 其他使用 `ScopeTypeMeta.sourceName()` 组消息的路径
  - 需要切换到新的 `displayName()` 消费面，避免继续展示源码名。
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

### 3.1 两种持久化名称与一个派生展示名

建议把 frontend class identity 明确收敛成两种持久化名称，加一个不存储的展示视图：

- `sourceName`
  - 源码里的声明名字。
  - 继续作为当前 module lexical type namespace 的 lookup key。
  - 继续服务 `extends Shared` 这类 source-facing header 绑定。
- `canonicalName`
  - registry、`ClassDef` / `LirClassDef`、LIR、backend、继承链查找统一使用的稳定身份。
  - 对顶层类，它来自外部映射后的发布名。
  - 对内部类，它由映射后的顶层 canonical 前缀派生。
- `displayName`
  - 用户诊断、frontend debug/inspection、static receiver 文案展示名。
  - 不单独存储，统一由 `canonicalName` 派生。

### 3.2 建议冻结的名称生成规则

- 顶层类：
  - `sourceName = 源码 class_name 或文件名推导结果`
  - `canonicalName = externalMapping.getOrDefault(sourceName, sourceName)`
  - `displayName = canonicalName`
- 内部类：
  - `sourceName = 源码 inner class 名`
  - `canonicalName = parentCanonicalName + "$" + sourceName`
  - `displayName = canonicalName`

这个规则满足用户提出的全部硬约束：

- 内部类 canonical 前缀会自动跟随“映射后的顶层 canonicalName”
- backend 只需继续读取 canonical
- source-facing lookup 仍然可以基于 `sourceName`
- 用户可见展示名始终能稳定反映映射后的发布结果

### 3.3 外部映射对象的建议形态

不要把它做成可变 service，也不要让 `ClassRegistry` 自己维护一份“逐步增长的 alias 表”。

建议在 frontend 入口引入一个一次性冻结的不可变值对象，例如：

- `FrontendTopLevelCanonicalNameMap`
- `FrontendClassNameMapping`

它的行为应尽量简单：

- 输入 key 只允许顶层 gdcc `sourceName`
- value 只允许目标顶层 `canonicalName`
- 构造时完成基本合法性检查
- 运行过程中只读

### 3.4 模块输入载体的建议形态

在开始正式改“顶层类名映射进入 frontend identity”的主链路之前，建议先在 `frontend.parse` 包新增一个统一的模块输入对象：

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

- 在 header discovery 阶段就明确冻结 `sourceName` 与映射后的 `canonicalName`。
- 顶层 identity 从 discovery 开始即使用映射后的 canonical。

建议改动：

- 扩展 `MutableClassHeader` / `AcceptedClassHeader` / `RejectedClassHeader`，显式保存：
  - `sourceName`
  - `canonicalName`
- `discoverTopLevelHeader(...)` 先解析 `sourceName`，再根据外部 mapping 计算 `canonicalName`。
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
- “两个不同顶层类映射到同一 canonicalName” 会被 canonical conflict 明确拒绝。

第 3 步执行状态（2026-03-24）：

- [x] 3.1 扩展 header discovery 名字载体
  - `MutableClassHeader`、`AcceptedClassHeader`、`RejectedClassHeader` 最终已收敛为 `sourceName / canonicalName` 双名。
  - `describeClassHeaderOrigin(...)` 等辅助路径已切到 source/canonical 事实，mapped top-level conflict 诊断现在能同时带出 source/canonical 信息。
- [x] 3.2 在 top-level discovery 阶段应用模块 runtime-name mapping
  - `discoverTopLevelHeader(...)` 已从 `FrontendModule.topLevelCanonicalNameMap()` 计算顶层 canonicalName。
  - `discoverInnerClassHeaders(...)` 已统一基于“冻结后的 parent canonicalName”派生内部类 canonical 前缀。
- [x] 3.3 保留 source 冲突与 canonical 冲突双轨校验，并让 shell 保持一致
  - top-level duplicate 检查仍按 `sourceName` 工作，duplicate diagnostic 也改为显式描述 source-facing 冲突。
  - canonical conflict 现在能正确拒绝“不同 top-level sourceName 映射到同一 runtime/canonicalName”。
  - 为保持 step3 后 skeleton contract 自洽，`FrontendSourceClassRelation` 已最小联动升级为 `sourceName + canonicalName` 双名模型，`buildSourceClassRelationShell(...)` 产出的 top-level `LirClassDef.getName()` 现在同步写入 mapped canonical。
  - header discovery 已不再保留 `runtimeName` 中间态，后续步骤直接建立在“双名模型 + 派生 `displayName()`”上推进。
- [x] 3.4 补充 targeted tests 并记录验收结果
  - 已新增/更新正向测试，覆盖 mapped top-level canonical、mapped inner canonical 前缀、mapped superclass source lookup -> canonical publication。
  - 已新增负向测试，覆盖两个不同 top-level sourceName 映射到同一 runtime/canonicalName 时的 canonical conflict。
  - 已通过：`FrontendClassSkeletonTest`、`FrontendClassHeaderDiscoveryTest`、`FrontendScopeAnalyzerTest`、`FrontendVariableAnalyzerTest`、`FrontendSemanticAnalyzerFrameworkTest`

### 第 4 步：把后续方向收敛为“双名模型 + 派生 displayName”

目标：

- 在 step3 已完成 top-level `sourceName + canonicalName` 拆分的基础上，明确放弃三名模型。
- 保持 `sourceName / canonicalName` 为唯一持久化名字层，并为用户可见展示统一引入“不存储的 `displayName`”。

建议改动：

- `FrontendOwnedClassRelation` 保持双名协议，不新增 `runtimeName()`。
- 在 relation 层提供 `displayName()`，直接返回 `canonicalName()`。
- 不再把 top-level relation 升级成持久化三名模型，也不再让 inner relation 承载单独 `runtimeName` 字段。
- `FrontendOwnedClassRelation.validateOwnedRelation(...)` 继续只校验：
  - relation `canonicalName == classDef.getName()`
  - `superClassRef.canonicalName == classDef.getSuperName()`

优先涉及文件：

- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendOwnedClassRelation.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendSourceClassRelation.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendInnerClassRelation.java`

验收细则：

- top-level relation 继续只保留源码名与映射后的 canonical 名。
- inner relation 继续只保留局部源码名与派生 canonical 名。
- 若新增 `displayName()`，其返回值始终等于 `canonicalName()`。

第 4 步执行状态（2026-03-25）：

- [x] 4.1 `FrontendOwnedClassRelation` 收敛为双名协议
  - relation 层已不再保留 `runtimeName`，统一通过默认 `displayName()` 返回 `canonicalName()`。
- [x] 4.2 top-level / inner relation 共享同一展示语义
  - `FrontendSourceClassRelation`、`FrontendInnerClassRelation` 都继续只持久化 `sourceName + canonicalName`，展示名不单独存储。

### 第 5 步：把 `ScopeTypeMeta` 从“lookup key + 展示名”双重职责中拆开，但展示名不单独存储

目标：

- 保留 `sourceName` 作为 lexical type namespace 的 lookup key。
- 为用户诊断和 frontend debug 暴露统一的展示名消费面，但该展示名从 `canonicalName` 派生。

建议改动：

- 不在 `ScopeTypeMeta` 中新增 `runtimeName` 字段。
- 在 `ScopeTypeMeta` 中新增 `displayName()`，实现直接返回 `canonicalName()`。
- 语义建议冻结为：
  - `canonicalName`：registry/backend identity，同时也是当前展示名的来源
  - `sourceName`：当前 lexical namespace lookup key
  - `displayName()`：用户诊断、frontend static receiver 呈现、frontend identity 展示；由 `canonicalName` 派生
- 对 builtin / engine / global enum：
  - `sourceName == canonicalName`
  - `displayName() == canonicalName`
- 对顶层映射 gdcc 类：
  - `sourceName = 源码名`
  - `canonicalName = 映射名`
  - `displayName() = canonicalName`
- 对内部类：
  - `sourceName = 局部类名`
  - `canonicalName = 映射前缀后的完整名`
  - `displayName() = canonicalName`

优先涉及文件：

- `src/main/java/dev/superice/gdcc/scope/ScopeTypeMeta.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendOwnedClassRelation.java`
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
- 所有直接 `new ScopeTypeMeta(...)` 的调用点和测试 helper

验收细则：

- `AbstractFrontendScope.defineTypeMeta(...)` 继续按 `sourceName` 建本地 type namespace。
- `ScopeTypeMeta` 可以同时表达“源码 lookup 名”和“派生展示名”。
- 现有 type-meta lookup 协议保持 `FOUND_ALLOWED / NOT_FOUND` 合同不变。

第 5 步执行状态（2026-03-25）：

- [x] 5.1 `ScopeTypeMeta` 新增派生 `displayName()`
  - `displayName()` 已落地，直接返回 `canonicalName()`；未引入新的持久化名字字段。
- [x] 5.2 type-meta 双重职责已拆分
  - `sourceName` 继续只承担 lexical type namespace lookup key，展示路径开始消费 `displayName()`。

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
  - `displayName() = canonicalName`

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
- 对映射后的顶层类，registry 返回的 `ScopeTypeMeta.displayName()` 与 `canonicalName()` 为映射名。

第 6 步执行状态（2026-03-25）：

- [x] 6.1 registry source override 已扩展到任意 `sourceName != canonicalName` 的 gdcc class
  - `publishClassShells(...)` 现在会为 mapped top-level class 也写入 source override。
- [x] 6.2 global registry 仍保持 canonical-only 注册键
  - `findGdccClass(...)` / `resolveTypeMetaHere(...)` 的 global lookup 仍只接受 canonical，不额外暴露 source alias。

### 第 7 步：落实统一 caller-side remap 规则，并修复 scope / analyzer / resolver 的消费点

目标：

- 把“调用经过映射的顶层类的 frontend 调用者，应先按源码字面名做正常 lexical 查找，只有 miss 时才按 mapping 重试 canonical”冻结为统一规则。
- 不再通过 `ClassScope` 特判去发布当前顶层类自身的 source-facing type-meta。
- 让后续 analyzer 能从一个稳定的 skeleton 快照拿到映射表，而不是各自手搓 remap。
- 让用户诊断和 analyzer debug 路径统一展示映射后的名字。
- 同时不破坏 lexical shadowing 与 source-facing lookup 规则。

建议改动：

- 在 `FrontendModuleSkeleton` 中保留冻结后的 `topLevelCanonicalNameMap`。
- 为 `FrontendModuleSkeleton` 添加配套工具方法，至少覆盖：
  - `findMappedTopLevelCanonicalName(sourceName)`
  - `remapTopLevelCanonicalName(sourceName)`
  - 如有必要，再补一个“只在存在 override 时返回 canonical”的窄接口，避免调用方到处手写 `map.getOrDefault(...)`
- 明确统一 remap 协议：
  - 先按 AST/source 中的原始字面名执行正常 lexical `resolveTypeMeta(...)` / strict declared-type 查找
  - 只有在该路径 miss 后，且名字命中 top-level mapping 时，才以映射后的 canonical 名重试
  - 禁止任何调用点在 lexical 查找前无条件把源码字面名直接改写成 canonical
- 下列源码名消费点统一接入上述 helper，而不是各自散落手写 remap：
  - `FrontendDeclaredTypeSupport`
  - `FrontendTopBindingAnalyzer`
  - `FrontendChainHeadReceiverSupport`
  - `FrontendAssignmentSemanticSupport`
  - 其他直接把源码字面名传给 `resolveTypeMeta(...)` / `tryResolveDeclaredType(...)` 的 frontend 路径
- 继续把展示文案从 `receiverTypeMeta.sourceName()` 切换为 `receiverTypeMeta.displayName()`：
- 以下路径从 `receiverTypeMeta.sourceName()` 切换为 `receiverTypeMeta.displayName()`：
  - `ScopeMethodResolver`
  - `FrontendChainReductionHelper`
  - property initializer 相关 helper
  - 其他把 type-meta 名字直接拼进错误消息、unsupported message、debug string 的路径
- header/skeleton 诊断在“说明当前类是谁”时，优先展示 `displayName()/canonicalName`。
- header/skeleton 诊断在“说明用户写了什么 raw text”时，继续展示源码里的原始 `extends` 文本。

重点注意：

- 诊断消息里的 receiver/type 名字变更会打到大量断言，测试需要整体同步。
- lookup 逻辑本身不要跟着改成按 `displayName` 查找。
- 不要重新引入“source-file `ClassScope` 直接发布 mapped top-level 自身 source-facing type-meta”的 scope 级特判；mapped top-level self/cross-file 可见性应由 caller-side remap 规则负责。

优先涉及文件：

- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendModuleSkeleton.java`
- `src/main/java/dev/superice/gdcc/scope/resolver/ScopeMethodResolver.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendDeclaredTypeSupport.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendTopBindingAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendChainHeadReceiverSupport.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendAssignmentSemanticSupport.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendChainReductionHelper.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- 其他 `ScopeTypeMeta.sourceName()` 直接用于消息拼接的路径

验收细则：

- caller-side remap 统一保持“lexical lookup first, remap-on-miss second”，不会破坏 local/inner type-meta 的 shadowing。
- mapped top-level 自身 declared type、同模块跨文件 top-level static route、compile gate 相关 source-facing 用法都能在 remap-on-miss 规则下恢复工作。
- `FrontendModuleSkeleton` 已成为后续 analyzer 读取 module mapping 的唯一稳定快照来源。
- 再次确认顶层 `ClassScope` 不直接发布 mapped top-level 自身 source-facing type-meta。
- static load / constructor / method resolution 失败消息在 mapped 类上展示映射后的名字。
- source-facing lexical lookup 仍按源码名工作。
- top binding / chain binding / expr type / compile check 的 side-table 发布不出现结构性退化。

第 7 步执行状态（2026-03-25）：

- [x] 7.1 shared/static-receiver 诊断已切到 `displayName()`
  - `ScopeMethodResolver`、`FrontendChainReductionHelper` 已改为展示 `displayName()`，不再默认回显 `sourceName()`。
- [x] 7.2 property initializer 边界消息与 constructor/static-method 失败消息已同步
  - mapped gdcc type-meta 的用户可见消息现在稳定展示 canonical 派生名。
- [ ] 7.3 `FrontendModuleSkeleton` 保留 mapping 与 caller-side remap helper 仍待落地
  - 当前 analyzer 侧还没有统一的 remap-on-miss helper。
  - mapped top-level source-facing self/cross-file type lookup 仍不能视为已完成能力。

### 第 7A 步：全面迁移 frontend 类型名 / `TYPE_META` 解析到 caller-side remap 规则

目标：

- 对现有 frontend 中所有直接消费源码类型名字面量、`TYPE_META` 标识符和静态路由头的路径做一次完整盘点。
- 把这些路径统一迁移到“先 lexical lookup，miss 后按 `topLevelCanonicalNameMap` remap 到 canonical 再重试”的规则上。
- 用充分的单元测试把正反行为钉死，避免后续又通过 scope 特判或局部 alias 回退。

建议改动：

- 先完成一轮代码盘点，明确列出所有会直接把源码字面名传入以下 API 的 frontend 路径：
  - `Scope#resolveTypeMeta(...)`
  - `ScopeTypeResolver.tryResolveDeclaredType(...)`
  - `ClassRegistry.tryResolveDeclaredType(...)`
  - 以及任何把 `TYPE_META` 作为 chain/static receiver 重新确认的 helper
- 至少覆盖以下已知主干：
  - `FrontendDeclaredTypeSupport`
  - `FrontendTopBindingAnalyzer`
  - `FrontendChainHeadReceiverSupport`
  - `FrontendAssignmentSemanticSupport`
  - `FrontendExpressionSemanticSupport` 中任何重新解析显式类型名的路径
  - 其他 analyzer/support 中直接按源码字面名探测 type-meta 的路径
- 对每条路径统一实施同一策略：
  - 先用当前 lexical scope / 当前 helper 的原始协议查找源码字面名
  - 仅在 miss 时，再通过 `FrontendModuleSkeleton` 上冻结的 mapping/helper 做 canonical retry
  - 若第一次已命中 local/inner type-meta，则禁止继续 remap，确保 lexical shadowing 不被破坏
- 严禁保留以下“局部补洞”方案：
  - 在某个 analyzer 中直接手写 `map.getOrDefault(...)` 作为私有规则
  - 在 scope graph 中重新发布 mapped top-level 自身 source-facing type-meta
  - 在 `ClassRegistry` 中为 mapped top-level 额外开放 source alias 的全局 lookup
- 为 remap 结果补必要注释，重点解释：
  - 为什么必须是 remap-on-miss，而不是 unconditional remap
  - 为什么 caller-side remap 只适用于 top-level mapping，而不适用于 inner class canonical 派生

建议新增/更新的测试面：

- declared type：
  - mapped top-level 自身 property / parameter / return type 在 caller-side remap 下恢复工作
  - 同模块跨文件 top-level declared type 在 caller-side remap 下恢复工作
  - local/inner type-meta 与 mapped top-level 同名时，lexical 命中优先，不能被 remap 覆盖
- top binding / chain binding / expr type：
  - mapped top-level static route `MappedWorker.build()` 正向通过
  - 同模块跨文件调用另一个 mapped top-level 静态方法正向通过
  - 非 mapped 名字、unknown 名字、shadowed 名字分别保持原有失败或遮蔽行为
- compile-only gate：
  - mapped top-level 路由不会因 remap 误报 compile blocker
  - 真正的 compile blocker 仍会被保留，不会被 remap 吞掉
- 负向与边界：
  - unconditional remap 会破坏 lexical shadowing 的场景必须有回归测试，防止实现走偏
  - inner class 继续只按 lexical `sourceName` 解析，不通过 top-level mapping helper 补 canonical

优先涉及文件：

- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendDeclaredTypeSupport.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendTopBindingAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendChainHeadReceiverSupport.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendAssignmentSemanticSupport.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
- 相关 analyzer/support 测试

验收细则：

- frontend 现有类型名 / `TYPE_META` 解析路径已经完成盘点，不存在已知漏网的源码字面名直接查 canonical-only registry 的路径。
- mapped top-level 自身与跨文件 top-level 的 source-facing 用法，都通过 caller-side remap-on-miss 恢复工作。
- local/inner type-meta 的 lexical 优先级不被破坏；相关负向测试能够证明实现没有走成 unconditional remap。
- 新增测试不只覆盖 happy path，还覆盖 shadowing、unknown name、compile blocker 保留等反向场景。

第 7A 步执行状态（2026-03-25）：

- [ ] 7A.1 frontend 类型名 / `TYPE_META` 解析路径盘点待完成
- [ ] 7A.2 caller-side remap 统一迁移待完成
- [ ] 7A.3 正反两方面单元测试待补齐
- [ ] 7A.4 targeted tests 与事实源同步待完成

### 第 8 步：确认继承链、属性解析和方法解析只继续依赖 canonical

目标：

- 保证 canonical 映射进入前端 identity 后，不破坏 `ClassScope` / shared resolver 的既有成员解析。

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

第 8 步执行状态（2026-03-25）：

- [x] 8.1 canonical-only 继承链主逻辑保持不变
  - 本轮未重写 `ClassScope` / registry 的 canonical walk 逻辑，只回归确认映射后的 canonical 身份继续可用。
- [x] 8.2 mapped top-level / inner class 的 canonical 合同已由回归测试继续锚定
  - 既有 targeted tests 已覆盖 relation/skeleton、构造调用与 source-override 恢复。
  - 本轮补充 `ClassRegistryGdccTest`，显式锚定 mapped top-level canonical superclass 对 `checkAssignable(...)` / `getRefCountedStatus(...)` 的正反行为。
  - 本轮补充 `ScopeMethodResolverTest`、`ScopePropertyResolverTest`，显式锚定 mapped canonical inner superclass 在 shared resolver 中的正反回归。
  - 本轮补充 `MethodResolverParityTest`、`PropertyResolverParityTest`，显式锚定 backend adapter 继续只消费 canonical owner name，而不会被 source override 重新带偏。

### 第 9 步：补齐测试和文档更新

目标：

- 用测试把“source lookup 不变、canonical 已映射、displayName 从 canonical 派生、backend 仍只吃 canonical”同时钉死。

建议新增或更新的测试面：

- skeleton/header：
  - 顶层映射后 `FrontendSourceClassRelation` 的双名模型
  - 顶层映射后内部类 canonical 前缀变化
  - `LirClassDef.getName()` 已写入 mapped canonical
  - 两个顶层 sourceName 不同但 mapping 后 canonical 相同的冲突
- scope/type-meta：
  - top-level mapped class 在 lexical scope 中仍用源码名解析
  - 命中后 `ScopeTypeMeta.sourceName()` 为源码名
  - 命中后 `ScopeTypeMeta.displayName()/canonicalName()` 为映射名
- analyzer：
  - top binding / chain binding / expr type 在 mapped 类环境中通过 caller-side remap-on-miss 继续工作
  - compile-only gate 不因为名字映射而误报或漏报
  - `FrontendModuleSkeleton` 持有的 mapping/helper 能被上述 analyzer 统一复用，而不是各写一套 remap
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

- 新增测试能区分 `sourceName`、`displayName()`、`canonicalName`。
- 旧测试中凡是写死“顶层 `sourceName == canonicalName`”的断言都已按新合同改写。
- backend / LIR 相关测试继续证明 canonical-only contract 没被破坏。

第 9 步执行状态（2026-03-25）：

- [x] 9.1 已补充 source/display/canonical 三者分工断言
  - `ClassRegistryTypeMetaTest`、`ClassRegistryGdccTest`、`FrontendClassSkeletonTest` 等已新增 `displayName()` 与 mapped top-level source override 断言。
- [x] 9.2 已补充展示消息回归
  - `ScopeMethodResolverTest` 与 `FrontendChainReductionHelperTest` 现覆盖 mapped gdcc type-meta 在失败消息中展示 canonical 派生名。
- [ ] 9.3 analyzer 与 compile-only gate 的 mapped-module source-facing 回归仍待 caller-side remap 方案落地
  - 当前实现已回退“source-file `ClassScope` 直接发布 mapped top-level 自身 source-facing type-meta”的试探性做法。
  - 后续应先完成 `FrontendModuleSkeleton` 对 `topLevelCanonicalNameMap` 的保留及 remap helper，再恢复 analyzer/compile-only 正向回归。
  - `FrontendTopBindingAnalyzerTest`、`FrontendChainBindingAnalyzerTest`、`FrontendExprTypeAnalyzerTest`、`FrontendCompileCheckAnalyzerTest` 的 source-facing mapped top-level 正向回归已一并撤回，避免把未实现语义写成既成事实。
- [x] 9.4 已补充 LIR/backend canonical-only 回归并同步事实源文档
  - `DomLirSerializerTest`、`DomLirParserTest` 已显式锚定 mapped top-level canonical `name/super` 的 parser/serializer 合同。
  - `CCodegenTest` 已显式锚定 C 模板生成继续直接使用 canonical `classDef.name`，不会重新回显 source alias。
  - `inner_class_implementation.md`、`scope_analyzer_implementation.md`、`scope_type_resolver_implementation.md`、`superclass_canonical_name_contract.md`、`doc/analysis/frontend_semantic_analyzer_research_report.md` 已同步到当前双名模型与 canonical-only downstream 事实。

---

## 5. 建议的实施顺序

建议严格按下面顺序推进，避免中间态把整个 frontend 打碎：

1. 先引入 `FrontendModule`，统一模块级输入边界。
2. 再把 mapping 收口到 `FrontendModule`，停止平行参数传递。
3. 再改 header identity 和 skeleton freeze。
4. 再把后续模型收敛成“双名 + 派生 displayName”。
5. 再改 registry publication。
6. 再把冻结后的 `topLevelCanonicalNameMap` 保留到 `FrontendModuleSkeleton`，补统一 caller-side remap helper。
7. 再改 analyzer / resolver 的展示名与 remap 消费点。
8. 再全面盘点 frontend 类型名 / `TYPE_META` 解析并迁移到 caller-side remap 规则，同时补齐正反测试。
9. 最后集中修测试和更新事实源文档。

不建议先从 `ClassRegistry` 开始硬塞 alias 表，也不建议在引入统一模块输入载体之前就把 mapping 散着塞进多个 analyzer/build 方法参数里，因为那会把“identity 冻结点”和“模块输入边界”同时推迟，最终还得回头重做 skeleton、relation 和入口 API。

---

## 6. 风险与应对

### 风险 1：`sourceName` 和展示名混用导致 analyzer 行为悄悄变化

表现：

- 本来只想改诊断文本，结果把 lexical lookup key 也改成了 displayName 或 canonicalName。

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
  - 用户可见消息断言看 `displayName()`
  - backend/LIR/registry 断言看 `canonicalName`

### 风险 5：inspection/debug 工具继续打印源码名，造成调试错觉

表现：

- 用户看到的 inspection report 和实际 backend canonical 不一致。

应对：

- debug/inspection 输出同步切到 `displayName()` 或 canonicalName。
- 如果某处需要展示源码名，显式标注为 sourceName。

---

## 7. 完成标准

这项工作完成时，应同时满足以下标准：

- 顶层类可以在 frontend 入口接收外部 mapping，并在 skeleton 阶段立即冻结为映射后的 canonical identity。
- 内部类 `canonicalName` 会自动跟随映射后的顶层 canonical 前缀变化。
- `FrontendSourceClassRelation` / `FrontendInnerClassRelation` / `ScopeTypeMeta` 已能同时表达 source lookup 名与 canonical 身份，并提供从 canonical 派生的展示名消费面。
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
