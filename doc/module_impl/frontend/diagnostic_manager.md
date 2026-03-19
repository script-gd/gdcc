# Frontend DiagnosticManager 设计与约定

> 本文档作为 `dev.superice.gdcc.frontend` 诊断基础设施的长期事实源，定义当前已冻结的 frontend 诊断流、阶段边界、共享分析数据形态，以及后续
> binder/body phase 必须遵守的约束。

## 文档状态

- 状态：事实源维护中（parser / skeleton / scope / variable / top-binding / chain-binding / expr-typing / type-check / exception 诊断链路已落地）
- 更新时间：2026-03-19
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
- 产物态：`FrontendSourceUnit` / `FrontendModuleSkeleton` / `FrontendAnalysisData`
- 保护性异常：`FrontendSemanticException`（仅用于不可恢复 frontend guard rail，不作为普通源码错误的主路径）

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
    - `FrontendModuleSkeleton` 当前还通过 `sourceClassRelations` 显式承载 source-owned class skeleton facts，而不是再靠 `units` / `classDefs` 的平行列表协议
- `FrontendAnalysisData.diagnostics`
    - 使用 `DiagnosticSnapshot`
    - 表达 analyze 阶段完成时的边界快照
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

其中：

- `annotationsByAst` 与 `scopesByAst` 已在生产链路中稳定填充
- `symbolBindings` 已由 `FrontendTopBindingAnalyzer` 稳定发布
- `resolvedMembers` / `resolvedCalls` 已由 `FrontendChainBindingAnalyzer` MVP 发布
- `expressionTypes` 已由 `FrontendExprTypeAnalyzer` 稳定发布，并承担 expression-only 恢复状态的 side-table 真源

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
- `sema.binding`
- `sema.unsupported_binding_subtree`
- `sema.member_resolution`
- `sema.call_resolution`
- `sema.deferred_chain_resolution`
- `sema.unsupported_chain_route`
- `sema.expression_resolution`
- `sema.deferred_expression_resolution`
- `sema.unsupported_expression_route`
- `sema.discarded_expression`
- `sema.type_check`
- `sema.type_hint`
- `sema.unsupported_annotation`

其中 body/binding phase 新增 category 的语义固定为：

- `sema.binding`
  - top binding 命中的 blocked / unknown / shadowing 诊断
- `sema.unsupported_binding_subtree`
  - top binding 对 parameter default、lambda、`for`、`match`、block-local `const` 等明确 unsupported subtree 的边界 error
  - top binding 对 missing-scope / skipped subtree 的恢复诊断继续允许使用 warning
- `sema.member_resolution`
  - chain binding 中 blocked / failed member step 的语义错误
- `sema.call_resolution`
  - chain binding 中 blocked / failed call step 的语义错误
  - 以及“实例语法命中 static method”这类 route note/warning
- `sema.deferred_chain_resolution`
  - chain binding 的 deferred subtree warning
  - 以及首个 deferred chain recovery root 的恢复诊断
- `sema.unsupported_chain_route`
  - chain binding 对当前 MVP 明确认定 unsupported 的 static / constructor / suffix route 边界 error
- `sema.expression_resolution`
  - expr analyzer 对 bare call 与其他 expression-only 路径的 failed recovery error
- `sema.deferred_expression_resolution`
  - expr analyzer 对 assignment / subscript / generic deferred expression 等 expression-only deferred root 的 warning
- `sema.unsupported_expression_route`
  - expr analyzer 对当前明确不支持的 direct-callable-invocation 等 expression route 的 error
- `sema.discarded_expression`
  - expr analyzer 对 bare expression statement 中被丢弃的非 `void` 结果发出的 warning
- `sema.type_check`
  - type-check analyzer 对 ordinary local / class property / return typed contract 不兼容发出的 error
- `sema.type_hint`
  - type-check analyzer 对 property `:=` / 未声明显式类型 property 发出的手动显式类型提醒 warning
  - 该 warning 只提示建议的显式类型，不表示 property metadata 已被推导或回写

其中 `FrontendClassSkeletonBuilder` 当前对 property annotation 的行为已经冻结为：

- `export` / `onready`：映射到 property skeleton annotations
- region annotations：忽略，不制造噪声诊断
- 已识别但当前 skeleton phase 尚未支持的 property annotation：
    - 发出 `sema.unsupported_annotation` error
    - 同时保留 annotation 事实在 `FrontendAnalysisData.annotationsByAst()`

与 `@onready` 相关的后续合同也已冻结为：

- `sema.unsupported_annotation`
  - 只用于“annotation 已识别，但当前 frontend 尚未实现该 annotation”
- `sema.annotation_usage`
  - 预留给“annotation 本身已支持，但挂载位置/owner class/staticness 非法”的用法验证错误
  - `@onready` 的 Node-only / non-static 约束属于这一类，而不是 `sema.unsupported_annotation`

这里还需要保持一条 owner 边界：

- skeleton phase 只负责 annotation retention 与 unsupported-annotation boundary
- `@onready` 的合法用法验证不属于 skeleton phase
- 后续独立的 annotation-usage phase 将消费 retained annotation + class metadata，并使用 `sema.annotation_usage` 报告 static / non-Node misuse

与 `FrontendTypeCheckAnalyzer` 相关的当前合同已冻结为：

- `sema.type_check`
  - 用于 local / class property / return typed contract 的真实不兼容错误
  - severity 固定为 `error`
- `sema.type_hint`
  - 用于 property `:=` / 未声明显式类型 property 的手动显式类型提醒
  - severity 固定为 `warning`
  - 该 warning 不表示 frontend 已完成 property 类型推导；它只提示用户手动补写推荐的显式类型
- condition root 当前只要求 stable typed fact；非 `bool` 的 Godot-compatible source condition 不再由 type-check analyzer 发 `sema.type_check`

---

## 3. 当前已落地状态

### 3.1 parser

- `GdScriptParserService` 已通过 shared `DiagnosticManager` 发布 diagnostics
- malformed script 的常规失败路径映射为 `parse.lowering`
- parser facade / mapper 的真正运行时失败映射为 `parse.internal`
- `FrontendSourceUnit` 已收口为纯 source + AST 容器，不再重复保存 parse diagnostics

### 3.2 skeleton

- `FrontendClassSkeletonBuilder` 已不再使用裸 `List<FrontendDiagnostic>` 透传
- skeleton 现先进行 module 级 class header discovery；duplicate class、inner class source-name/canonical-name 冲突、inheritance cycle、malformed nested class 都统一先写入 manager，再跳过受影响 subtree，而不是直接中断整个 module skeleton 过程
- skeleton 会把 annotation side-table 写入共享 `FrontendAnalysisData`
- builder 不会创造或重复导入第二份 parse diagnostics

### 3.3 analyzer

- `FrontendSemanticAnalyzer` 当前返回 `FrontendAnalysisData`
- analyze 流程围绕同一份共享分析数据推进
- analyze 现在已经具备独立的多 phase 主链路：
    - skeleton 结束后先发布 `updateModuleSkeleton(...)`
    - 再发布一次 pre-scope `updateDiagnostics(...)`
    - 调用 `FrontendScopeAnalyzer.analyze(...)`
    - scope phase 重建并发布真实的 `scopesByAst`
    - 调用 `FrontendVariableAnalyzer.analyze(...)`
    - 调用 `FrontendTopBindingAnalyzer.analyze(...)` 发布 `symbolBindings`
    - 调用 `FrontendChainBindingAnalyzer.analyze(...)` 发布 `resolvedMembers()` / `resolvedCalls()`
    - 调用 `FrontendExprTypeAnalyzer.analyze(...)` 发布 `expressionTypes()` 并补齐 expression-only diagnostics / discarded-expression warning
    - 调用 `FrontendTypeCheckAnalyzer.analyze(...)` 对 ordinary local / class property / return typed contract 发出 `sema.type_check`，并对 property hint 发出 `sema.type_hint`
    - 每个 phase 结束后都再次 `updateDiagnostics(...)`，把阶段边界快照刷新到最新 shared manager 状态

### 3.4 exception

- `FrontendSemanticException` 仍统一持有 `DiagnosticSnapshot`
- 但它现在只保留给不可恢复 frontend guard rail，不再是 parser / skeleton / analyzer 主干处理普通源码错误的推荐出口
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
    - duplicate / cycle diagnostics 会跳过坏 subtree，但不打断同一 module 的其余 skeleton
    - registry 注入和 snapshot 边界稳定
- `FrontendClassSkeletonAnnotationTest`
    - `export` / `onready` retention 语义稳定
    - unsupported property annotation 会发 error，且仍保留 side-table 事实
- `FrontendSemanticAnalyzerFrameworkTest`
    - analyzer 返回共享 `FrontendAnalysisData`
    - parse->analyze shared pipeline 不重复导入 parse diagnostics
    - `FrontendAnalysisData` / `FrontendModuleSkeleton` 的 snapshot 在阶段后保持稳定
- `FrontendScopeAnalyzerTest`
    - scope phase 会发布真实 `scopesByAst`，而不是空 side-table
    - inner class / nested lambda / multi-source-unit module 的 lexical scope graph 稳定
    - missing inner-class relation 只跳过坏 subtree，不扩大成整条 module 失败
- `FrontendInheritanceCycleTest`
    - inheritance cycle diagnostics 会跳过 cyclic class 与其依赖者
    - 其他合法 class 仍可继续发布到 module skeleton / registry
- `FrontendParseSmokeTest`
- `FrontendAnnotationParseBehaviorTest`
- `FrontendAnalysisDataTest`

最近一次通过的 frontend 相关定向测试命令为：

```powershell
powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendScopeAnalyzerTest,FrontendClassSkeletonTest,FrontendSemanticAnalyzerFrameworkTest,FrontendAnalysisDataTest,FrontendParseSmokeTest,FrontendAnnotationParseBehaviorTest,FrontendClassSkeletonAnnotationTest,FrontendInheritanceCycleTest,GdScriptParserServiceDiagnosticManagerTest
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
7. 对普通源码错误，frontend phase 应优先采用“发诊断 + 跳过当前 subtree”的恢复策略；只有不可恢复 guard rail 才允许抛异常。
