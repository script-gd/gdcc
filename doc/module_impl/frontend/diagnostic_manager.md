# Frontend DiagnosticManager 设计与约定

> 本文档作为 `dev.superice.gdcc.frontend` 诊断基础设施的长期事实源，定义当前已冻结的 frontend 诊断流、阶段边界、共享分析数据形态，以及后续
> binder/body phase 必须遵守的约束。

## 文档状态

- 状态：事实源维护中（parser / skeleton / analyzer / exception 诊断链路已落地，binder / body phase 待扩展）
- 更新时间：2026-03-10
- 适用范围：
    - `src/main/java/dev/superice/gdcc/frontend/diagnostic/**`
    - `src/main/java/dev/superice/gdcc/frontend/parse/**`
    - `src/main/java/dev/superice/gdcc/frontend/sema/**`
    - `src/main/java/dev/superice/gdcc/exception/FrontendSemanticException.java`
- 关联文档：
    - `doc/module_impl/common_rules.md`
    - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
    - `doc/analysis/frontend_semantic_analyzer_research_report.md`

---

## 1. 背景与目标

frontend 曾长期依赖脆弱的 `List<FrontendDiagnostic>` 沿调用链下传诊断状态。这种写法在 parser 与 skeleton 的早期实现中尚可工作，但一旦接入
interface/body phase、binder diagnostics、unsupported feature 归类和多 side-table 分析结果，就会迅速退化为高噪声、低可维护性的隐式协议。

当前 frontend 诊断流的冻结目标不是再引入一套新的诊断数据模型，而是明确区分两类对象：

- 过程态：`DiagnosticManager`
- 产物态：`FrontendSourceUnit` / `FrontendModuleSkeleton` / `FrontendAnalysisData` / `FrontendSemanticException`

这意味着 frontend 后续工程都必须围绕“共享 manager 收集 + 阶段边界快照发布”推进，而不是重新回到局部 list 透传。

---

## 2. 当前已冻结的架构事实

### 2.1 `DiagnosticManager` 是 frontend 内部的过程对象

`DiagnosticManager` 当前是 frontend 专用诊断收集器，其职责固定为：

- 追加单条诊断
- 导入一批诊断
- 查询当前是否存在错误
- 导出不可变 `DiagnosticSnapshot`

它不负责：

- AST lookup
- scope 解析
- 类型推断
- 去重、排序、分组或汇总策略
- exception policy

这条边界对后续工程保持冻结。若未来 binder/body phase 需要更多诊断出口，应扩展 diagnostics 的生产位置，而不是把推理逻辑塞进
manager。

### 2.2 `DiagnosticSnapshot` 是统一结果快照包装

`DiagnosticManager.snapshot()` 现在返回 `DiagnosticSnapshot`，而不是直接返回 `List<FrontendDiagnostic>`。

`DiagnosticSnapshot` 当前稳定提供：

- `asList()`
- `isEmpty()`
- `size()`
- `hasErrors()`
- `getFirst()`
- `getLast()`

其语义是：某一阶段边界上的不可变诊断视图。后续 manager 的追加不会回写已经发布的 snapshot。

### 2.3 阶段边界对象的诊断语义

frontend 当前已经冻结的诊断承载方式如下：

- `FrontendSourceUnit`
    - 只保留 source text 与 AST
    - 不再持有任何 parse diagnostics
    - parser diagnostics 只通过 shared `DiagnosticManager` 与其 `DiagnosticSnapshot` 暴露
- `FrontendModuleSkeleton.diagnostics`
    - 使用 `DiagnosticSnapshot`
    - 表达 skeleton 阶段完成时的边界快照
- `FrontendAnalysisData.diagnostics`
    - 使用 `DiagnosticSnapshot`
    - 表达 analyze 阶段完成时的边界快照
- `FrontendSemanticException.diagnostics`
    - 使用 `DiagnosticSnapshot`
    - 表达 fail-fast 抛出瞬间的边界快照

因此，frontend 主链路中的诊断真源已经完全收敛到 shared manager 与阶段边界 snapshot。

### 2.4 parser / skeleton / analyzer 的共享 manager 规则

当前主干链路已经冻结以下规则：

- `GdScriptParserService.parseUnit(...)` 必须显式接收 `DiagnosticManager`
- `FrontendClassSkeletonBuilder.build(...)` 必须显式接收 `DiagnosticManager`
- `FrontendSemanticAnalyzer.analyze(...)` 必须显式接收 `DiagnosticManager`
- 不保留无 manager 的兼容方法

同一条 shared pipeline 中，parse diagnostics 只允许导入一次。具体规则是：

- parse phase 已经把本次 parse diagnostics 写入 shared manager
- `FrontendSourceUnit` 不再提供第二份 parse diagnostics 存储
- skeleton / analyzer 只能消费 shared manager 中已有的 parse diagnostics 状态
- 若调用方手工构造 `FrontendSourceUnit`，则也必须自行决定 shared manager 是否需要预先拥有相应 parse diagnostics

这条规则不能被后续工程悄悄放松，否则 diagnostics 会重新出现重复收集。

### 2.5 `FrontendAnalysisData` 是统一的 frontend 分析数据载体

`FrontendAnalysisData` 取代了旧的 `FrontendAnalysisResult`，其定位已经冻结为：

- analyzer 及后续 phase 之间共享的前端分析数据对象
- 内部统一拥有完整 side-table 集合
- 不再通过构造流程单独传各个 side-table
- 通过显式 `updateXXX(...)` 方法更新共享分析数据字段
    - `updateModuleSkeleton(...)`
    - `updateDiagnostics(...)`
    - side-table 更新方法保持各自独立

当前稳定拥有的 side-table 拓扑包括：

- `annotationsByAst`
- `scopesByAst`
- `symbolBindings`
- `expressionTypes`
- `resolvedMembers`
- `resolvedCalls`

其中除 annotation 与边界 diagnostics 外，其余表目前仍主要为后续 binder/body phase 预留。

### 2.6 `SkeletonBuildContext` 只服务于 skeleton phase

`FrontendClassSkeletonBuilder` 当前通过私有 `SkeletonBuildContext` 收束稳定构建环境。当前上下文字段为：

- `ClassRegistry`
- `DiagnosticManager`
- `Path sourcePath`
- `FrontendAnalysisData`

其中 `FrontendAnalysisData` 使 skeleton phase 可以直接读写共享 side-table，而不再只持有单独的 `annotationsByAst`。

需要继续冻结的约束是：

- `SkeletonBuildContext` 只服务于 skeleton phase
- 若未来 interface/body phase 需要独立上下文，应新建类型，不复用 skeleton context
- 不允许把它演化成跨所有阶段的“万能上下文对象”

### 2.7 `DiagnosticManager` 不进入 `scope` / shared resolver

`scope` 与 shared resolver 的职责仍然只限于返回 lookup / metadata 事实。

`DiagnosticManager` 不得注入以下层：

- `Scope`
- `ResolveRestriction`
- `ScopeMethodResolver`
- `ScopePropertyResolver`
- `ScopeSignalResolver`

frontend binder 负责把这些事实翻译成用户可见
diagnostics。这条分层关系与 [scope_architecture_refactor_plan.md](scope_architecture_refactor_plan.md)
保持一致。

### 2.8 feature-boundary diagnostics 的统一出口

deferred / unsupported diagnostics 一律通过 `DiagnosticManager` 发布。

当前已经落地并可作为后续基线的 category 包括：

- `parse.lowering`
- `parse.internal`
- `sema.class_skeleton`
- `sema.inheritance_cycle`
- `sema.type_resolution`
- `sema.unsupported_annotation`

其中 `FrontendClassSkeletonBuilder` 当前对 property annotation 的行为已经冻结为：

- `export` / `onready`：映射到 property skeleton annotations
- region annotations：忽略，不制造噪声诊断
- 已识别但当前 skeleton phase 尚未支持的 property annotation：
    - 发出 `sema.unsupported_annotation` warning
    - 同时保留 annotation 事实在 `FrontendAnalysisData.annotationsByAst()`

---

## 3. 当前已落地状态

### 3.1 parser

- `GdScriptParserService` 已通过 shared `DiagnosticManager` 发布 diagnostics
- malformed script 的常规失败路径映射为 `parse.lowering`
- parser facade / mapper 的真正运行时失败映射为 `parse.internal`
- `FrontendSourceUnit` 已收口为纯 source + AST 容器，不再重复保存 parse diagnostics

### 3.2 skeleton

- `FrontendClassSkeletonBuilder` 已不再使用裸 `List<FrontendDiagnostic>` 透传
- duplicate class / inheritance cycle 会先写入 manager，再以 snapshot 构造 `FrontendSemanticException`
- skeleton 会把 annotation side-table 写入共享 `FrontendAnalysisData`
- builder 不会创造或重复导入第二份 parse diagnostics

### 3.3 analyzer

- `FrontendSemanticAnalyzer` 当前返回 `FrontendAnalysisData`
- analyze 流程围绕同一份共享分析数据推进
- analyze 阶段结束时通过显式字段更新方法写回：
    - `updateModuleSkeleton(...)`
    - `updateDiagnostics(...)`

### 3.4 exception

- `FrontendSemanticException` 已统一持有 `DiagnosticSnapshot`
- 异常对象只表达抛出瞬间的 diagnostics 快照
- 异常对象不持有 `DiagnosticManager`

---

## 4. 验证基线

当前已通过的 targeted tests 覆盖以下主干行为：

- `DiagnosticManagerTest`
    - snapshot 不可变
    - 插入顺序稳定
    - `hasErrors()` / `reportAll(...)` 语义稳定
- `GdScriptParserServiceDiagnosticManagerTest`
    - parse diagnostics 正确镜像到 manager
    - per-unit parse snapshot 不被后续 parse 改写
    - `parse.internal` 仅在真正运行时失败路径出现
- `FrontendClassSkeletonTest`
    - skeleton 共享 parse diagnostics 但不重复导入
    - registry 注入、继承环 fail-fast 和 snapshot 边界稳定
- `FrontendClassSkeletonAnnotationTest`
    - `export` / `onready` 语义稳定
    - unsupported property annotation 会发 warning，且仍保留 side-table 事实
- `FrontendSemanticAnalyzerFrameworkTest`
    - analyzer 返回共享 `FrontendAnalysisData`
    - parse->analyze shared pipeline 不重复导入 parse diagnostics
    - `FrontendAnalysisData` / `FrontendModuleSkeleton` 的 snapshot 在阶段后保持稳定
- `FrontendInheritanceCycleTest`
    - 异常快照与 manager 边界一致
- `FrontendParseSmokeTest`
- `FrontendAnnotationParseBehaviorTest`
- `FrontendAnalysisDataTest`

最近一次通过的定向测试命令为：

```powershell
powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendAnalysisDataTest,FrontendSemanticAnalyzerFrameworkTest,FrontendClassSkeletonTest,FrontendClassSkeletonAnnotationTest,FrontendInheritanceCycleTest,DiagnosticManagerTest,GdScriptParserServiceDiagnosticManagerTest,FrontendParseSmokeTest,FrontendAnnotationParseBehaviorTest
```

---

## 5. 对后续工程的约定

1. interface/body/binder phase 必须继续沿用 shared `DiagnosticManager`，不要重新发明局部 diagnostics list 协议。
2. 后续 phase 若需要共享 side-table，必须优先通过 `FrontendAnalysisData` 承载，而不是在 analyzer 外单独追加参数。
3. 若调用方手工构造 `FrontendSourceUnit`，必须明确决定 shared manager 是否需要预先拥有对应 parse diagnostics；analyzer
   不会代劳。
4. binder 只消费 `scope` / shared resolver 返回的语义事实，并在 frontend 层归类 diagnostics；不要把 manager 下沉到
   `scope`。
5. 新增 unsupported / deferred feature 时，应继续复用 `DiagnosticManager` 和既有 category 规范，避免出现局部 TODO、局部
   list 或无分类 warning。
6. 若未来需要单独暴露 parse-phase 局部诊断视图，应新增明确的边界对象或 helper，而不是重新把 diagnostics 塞回
   `FrontendSourceUnit`。

---

## 6. 后续优先方向

frontend 诊断基础设施已经能够支撑后续 binder/body phase。当前更高优先级的后续工程是：

1. 在 `FrontendAnalysisData` 中真正填充 `symbolBindings`、`expressionTypes`、`resolvedMembers`、`resolvedCalls`、
   `scopesByAst`
2. 将 signal、type-meta、static-context、member/call 解析失败统一接入 frontend binder diagnostics
3. 为更完整的 unsupported / deferred feature 建立稳定 category 与测试矩阵
4. 将 AST body -> LIR lowering 接到已有 skeleton / analysis data / diagnostics 主干上
