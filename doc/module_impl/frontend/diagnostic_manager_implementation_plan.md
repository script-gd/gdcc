# Frontend DiagnosticManager 实施计划

> 本文档定义 frontend 从“脆弱的 `List<FrontendDiagnostic>` 传递”迁移到统一 `DiagnosticManager` 的冻结实施方案。
> 本文档是后续 parser、skeleton、semantic analyzer、binder 接入诊断收集时的执行依据。

## 文档状态

- 状态：Phase 0 / Phase 1 / Phase 2 / Phase 3 / Phase 4 已完成，Phase 5+ 待实施
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

## 1. 背景

当前 frontend 已经有统一的 `FrontendDiagnostic` 记录类型，但没有统一的诊断收集器。

现状问题集中在两点：

1. parse phase 与 sema phase 各自维护诊断列表，阶段边界不清晰。
2. `FrontendClassSkeletonBuilder` 通过 `List<FrontendDiagnostic>` 沿多层 private helper 传递构建状态，接口噪声高，后续 binder/body phase 扩展会快速恶化。

这类“把可变列表当作上下文”的写法在当前 skeleton 阶段尚可勉强工作，但在后续接入以下能力时会成为结构性阻碍：

- interface/body phase 拆分
- binder 的 context-sensitive diagnostics
- deferred / unsupported feature diagnostics
- signal / type-meta / static-context 的统一错误分流
- 基于 `FrontendAnalysisData` 的多 side-table 分析结果构建

因此，本次实施的目标不是再引入一种新的 diagnostic 数据模型，而是引入一个统一的、显式拥有生命周期的 `DiagnosticManager`，并把 frontend 诊断流程重构为：

- 过程态：`DiagnosticManager`
- 产物态：`FrontendSourceUnit` / `FrontendModuleSkeleton` / `FrontendAnalysisData` / `FrontendSemanticException`

---

## 2. 当前代码问题清单

### 2.1 parser 只有诊断映射，没有长期收集器

`GdScriptParserService` 当前会把 tolerant lowering diagnostics 映射为 `FrontendDiagnostic`，但只以临时列表形式返回给 `FrontendSourceUnit`，没有显式“谁拥有这批诊断”的过程对象。

这意味着：

- parse phase 只能返回一份快照，不能和后续 sema phase 共享统一的诊断生命周期。
- 若后续 analyzer 再把 `parseDiagnostics()` 手工导入另一份列表，就会形成重复收集风险。

### 2.2 skeleton builder 靠裸 `List` 下传构建状态

`FrontendClassSkeletonBuilder` 当前在 `build(...)` 中新建一个 `ArrayList<FrontendDiagnostic>`，然后把这份列表继续传给：

- `buildClassCandidate(...)`
- `toLirSignal(...)`
- `toLirProperty(...)`
- `toLirFunction(...)`
- `resolveTypeOrVariant(...)`
- `detectInheritanceCycles(...)`
- `detectInheritanceCyclesDfs(...)`
- `buildInheritanceCycleException(...)`
- `duplicateClassException(...)`

这会带来以下问题：

- helper 签名充斥样板参数，真实职责不清晰
- 诊断状态与其他构建环境信息分离，后续扩展时参数数目会继续膨胀
- 异常与普通路径都依赖同一份外部列表，状态来源隐式

### 2.3 结果对象已经隐含“两层语义”，但没有统一抽象

`FrontendSourceUnit` 仍以不可变 `List<FrontendDiagnostic>` 暴露 parse-phase 局部快照，而
`FrontendModuleSkeleton`、`FrontendAnalysisData`、`FrontendSemanticException` 已收敛为显式的
`DiagnosticSnapshot` 结果包装。

这说明当前代码已经事实性区分了：

- “可变收集过程”
- “不可变结果快照”

但仓库中还没有一个一等公民对象明确承载“可变收集过程”。

### 2.4 文档已冻结 frontend/binder 的职责边界

现有文档已经明确：

- `Scope` / shared resolver 只返回 lookup / metadata 事实
- frontend binder 负责把这些事实翻译成用户可见 diagnostics

因此 `DiagnosticManager` 的放置边界应当冻结为：

- 放在 `frontend` phase 内部
- 不下沉到 `scope` / shared resolver
- 不让 resolver 直接接收 manager

---

## 3. 冻结设计结论

本节是本计划的核心约束，后续实现不得随意偏离。

### 3.1 `DiagnosticManager` 只做 frontend 专用，不做全局泛型框架

本阶段只引入 `FrontendDiagnostic` 专用的 `DiagnosticManager`。

理由：

- 当前仓库中真正稳定、已被测试锚定的前端诊断模型只有 `FrontendDiagnostic`
- 现在抽成泛型会把简单问题复杂化
- backend / lir parser / logger 目前没有统一到同一套 diagnostic 基础设施的必要性

### 3.2 `DiagnosticManager` 是过程对象，不是结果对象

`DiagnosticManager` 的职责固定为：

- 追加单条诊断
- 导入一批诊断
- 查询当前是否存在错误
- 导出不可变快照

`DiagnosticManager` 不负责：

- AST lookup
- scope 解析
- 类型推断
- 诊断去重策略
- 自动排序重写
- exception policy

### 3.3 无 manager 的方法不保留

这条约束必须严格执行：

- **不要保留无 manager 的兼容方法**
- **不要增加“临时重载 + 内部 new DiagnosticManager()”的双入口**
- **不要让 `DiagnosticManager` 迁移留下长期 API 噪声**

原因：

- 当前 frontend 仍处于基础设施重构阶段，不存在稳定对外 API 兼容压力
- 保留旧入口会让调用方继续偷懒，导致迁移长期悬而不决
- 后续 binder/body analyzer 接入时，需要所有 phase 统一接受显式 manager

因此本次迁移应视为一次有意的内部 API 收口。

### 3.4 `FrontendClassSkeletonBuilder` 必须改用 `SkeletonBuildContext`

`FrontendClassSkeletonBuilder` 不应只是把 `List<FrontendDiagnostic>` 替换成 `DiagnosticManager` 参数。

冻结决策如下：

- 引入 `SkeletonBuildContext`
- 由该上下文统一承载 skeleton 阶段需要的构建环境信息
- private helper 之间不再裸传 `List<FrontendDiagnostic>`

推荐的最小上下文字段：

- `ClassRegistry classRegistry`
- `DiagnosticManager diagnostics`
- `Path sourcePath`
- `FrontendAnalysisData analysisData`

若后续 skeleton/interface phase 需要扩展更多环境信息，应继续追加到 `SkeletonBuildContext`，而不是恢复散装参数传递。

### 3.5 `FrontendSourceUnit.parseDiagnostics` 保留为 parse-phase 快照

统一收集不等于取消阶段结果。

冻结约束如下：

- `FrontendSourceUnit` 继续保留 `parseDiagnostics`
- 该字段表达“parse phase 输出快照”
- 它不是全流程总诊断真源
- 它不应被后续 phase 当作默认可变容器继续传递

### 3.6 `DiagnosticManager` 不进入 `scope` / resolver

不得把 `DiagnosticManager` 注入以下层：

- `Scope`
- `ResolveRestriction`
- `ScopeMethodResolver`
- `ScopePropertyResolver`
- `ScopeSignalResolver`

这些层只负责返回语义事实，不负责用户可见诊断归类。

---

## 4. 目标结构

实施完成后，frontend 诊断流应收敛为以下形态：

1. parser / sema / binder 等 phase 显式接收同一个 `DiagnosticManager`
2. 各 phase 只通过 manager 追加诊断
3. 阶段结果对象在边界上导出快照
4. 异常对象只持有抛出时的诊断快照
5. `FrontendAnalysisData` 持有最终分析阶段的 `DiagnosticSnapshot`

推荐接口轮廓如下：

```java
public final class DiagnosticManager {
    public void report(@NotNull FrontendDiagnostic diagnostic);
    public void reportAll(@NotNull Collection<FrontendDiagnostic> diagnostics);
    public void warning(@NotNull String category, @NotNull String message, @Nullable Path sourcePath, @Nullable FrontendRange range);
    public void error(@NotNull String category, @NotNull String message, @Nullable Path sourcePath, @Nullable FrontendRange range);
    public boolean hasErrors();
    public boolean isEmpty();
    public @NotNull DiagnosticSnapshot snapshot();
}
```

推荐 skeleton context 轮廓如下：

```java
private record SkeletonBuildContext(
        @NotNull ClassRegistry classRegistry,
        @NotNull DiagnosticManager diagnostics,
        @NotNull Path sourcePath,
        @NotNull FrontendAnalysisData analysisData
) {
}
```

---

## 5. 分阶段执行清单

## Phase 0. 文档冻结与迁移边界确认

### 目标

在写代码前，先把这次重构的边界、禁止项、阶段产物和验收口径冻结，避免迁移过程中反复改方向。

### 执行清单

- 新增本文档，作为 `DiagnosticManager` 实施计划的事实源
- 明确记录以下硬约束：
  - 不保留无 manager 方法
  - `FrontendClassSkeletonBuilder` 改用 `SkeletonBuildContext`
  - `DiagnosticManager` 不进入 `scope` / resolver
  - `FrontendSourceUnit.parseDiagnostics` 保留为 parse 快照
- 标注本计划与 `scope_architecture_refactor_plan.md` 的分层关系

### 验收标准

- 本文档已落库
- 约束清晰、无互相冲突条目
- 后续实现可以直接按本文档拆阶段推进，不需要再补口头规则

### 当前状态（2026-03-10）

- [x] 已完成：本文档已落库并作为 `DiagnosticManager` 迁移事实源
- [x] 已完成：明确冻结“不保留无 manager 兼容方法”“`FrontendClassSkeletonBuilder` 后续必须迁移到 `SkeletonBuildContext`”“manager 不进入 `scope` / resolver” 等边界
- [x] 已完成：将迁移分解为 Phase 0-7，并给出逐阶段执行清单、验收标准、风险控制与最终验收总表

---

## Phase 1. 引入 `DiagnosticManager` 与基础测试

### 目标

先建立统一的 frontend 诊断收集器契约，并用 targeted tests 固定其基本行为。

### 执行清单

- 在 `src/main/java/dev/superice/gdcc/frontend/diagnostic/` 下新增 `DiagnosticManager`
- 内部使用 `ArrayList<FrontendDiagnostic>` 保存插入顺序
- 提供以下最小能力：
  - `report(...)`
  - `reportAll(...)`
  - `warning(...)`
  - `error(...)`
  - `snapshot()`
  - `hasErrors()`
  - `isEmpty()`
- 保证 `snapshot()` 返回不可变列表
- 在 `src/test/java/dev/superice/gdcc/frontend/diagnostic/` 下新增 manager 单测

### 验收标准

- manager 的单元测试覆盖以下行为：
  - 保持插入顺序
  - `snapshot()` 为不可变视图
  - `reportAll(...)` 追加顺序稳定
  - `hasErrors()` 只在存在 `ERROR` 时为真
  - 空 manager 的 `snapshot()` 为稳定空列表
- `DiagnosticManager` 不依赖 parser、sema、scope 包内业务逻辑

### 当前状态（2026-03-10）

- [x] 已完成：新增 `src/main/java/dev/superice/gdcc/frontend/diagnostic/DiagnosticManager.java`
- [x] 已完成：`DiagnosticManager` 提供 `report(...)`、`reportAll(...)`、`warning(...)`、`error(...)`、`hasErrors()`、`isEmpty()`、`snapshot()`
- [x] 已完成：`reportAll(...)` 已实现“先完整校验，再一次性追加”的原子批量导入语义，避免 null 元素导致半提交状态污染后续快照
- [x] 已完成：新增 `src/test/java/dev/superice/gdcc/frontend/diagnostic/DiagnosticManagerTest.java`
- [x] 已完成：单测已锚定以下行为：
  - 插入顺序稳定
  - 早期快照不会被后续追加改写
  - `snapshot()` 不可变
  - `hasErrors()` 只在出现 `ERROR` 后返回 true
  - `reportAll(...)` 对 null collection / null element fail-fast，且不会留下部分提交状态
  - `warning(...)` / `error(...)` 的 metadata 保持稳定

---

## Phase 2. parser phase 接入 `DiagnosticManager`

### 目标

让 parse phase 成为 manager 驱动的第一层生产者，消除 parser 自己临时构造列表、外层再导入的双重语义。

### 执行清单

- 修改 `GdScriptParserService.parseUnit(...)` 签名，显式接收 `DiagnosticManager`
- 删除无 manager 版本，不保留兼容入口
- 在 parse 正常路径中：
  - 将 lowering diagnostics 映射为 `FrontendDiagnostic`
  - 先写入 manager
  - 再把本轮 parse diagnostics 的快照写入 `FrontendSourceUnit`
- 在 parse 异常路径中：
  - 通过 manager 写入 `parse.internal`
  - 返回带对应快照的 `FrontendSourceUnit`
- 保持 `FrontendSourceUnit.parseDiagnostics` 语义不变

### 验收标准

- `GdScriptParserService` 不再存在无 manager 入口
- parse smoke tests 继续通过
- 新增或更新测试验证：
  - manager 会收到 parse diagnostics
  - `FrontendSourceUnit.parseDiagnostics` 与本次 parse 产生的 diagnostics 一致
  - parse 异常路径同样会写入 manager
- parser 内部不再依赖“先构造列表，再把列表交给外层”的隐式协议

### 当前状态（2026-03-10）

- [x] 已完成：`GdScriptParserService.parseUnit(...)` 已改为显式接收 `DiagnosticManager`
- [x] 已完成：无 manager 的 `parseUnit(...)` 入口已删除，未保留兼容重载
- [x] 已完成：parse 正常路径现在会：
  - 先将 `gdparser` lowering diagnostics 映射为 `FrontendDiagnostic`
  - 再通过 `DiagnosticManager.reportAll(...)` 汇入共享收集器
  - 最后把本次 parse 的局部快照写入 `FrontendSourceUnit.parseDiagnostics`
- [x] 已完成：parse 异常路径现在会：
  - 产出 `parse.internal` error diagnostic
  - 写入 `DiagnosticManager`
  - 返回带空 `SourceFile` 与该局部快照的 `FrontendSourceUnit`
- [x] 已完成：新增 `src/test/java/dev/superice/gdcc/frontend/parse/GdScriptParserServiceDiagnosticManagerTest.java`
- [x] 已完成：更新现有 parse/sema 测试调用点，全部显式传入 `DiagnosticManager`
- [x] 已完成：通过测试和本地 `gdparser:0.4.0` 源码核对，已冻结以下实际行为：
  - malformed script 的常规失败路径来自 `gdparser` lowering diagnostics，应映射为 `parse.lowering`
  - `gdparser` lowering diagnostics 保留 `severity/message/nodeType/range` 语义，其中结构错误消息形如 `CST structural issue: ...`
  - `parse.internal` 只在 `parserFacade.parseCstRoot(...)` 或 `cstToAstMapper.map(...)` 真正抛出 `RuntimeException` 时出现，不能把普通语法错误误判为 internal failure
  - 同一 `DiagnosticManager` 可跨多个 `parseUnit(...)` 调用累积 diagnostics，但每个 `FrontendSourceUnit.parseDiagnostics` 必须保持该次 parse 的独立快照
- [x] 已完成：当前测试已覆盖以下 parser 行为锚点：
  - well-formed script 不产生多余 diagnostics，manager 仍为空
  - malformed script 会生成 `parse.lowering` error，并同步写入 manager
  - 复用同一 manager 多次 parse 时，累计行为正确，旧的 `FrontendSourceUnit.parseDiagnostics` 不会被后续 parse 改写
  - parserFacade 运行时失败会包装为 `parse.internal`，并返回空 AST body 的 `FrontendSourceUnit`

---

## Phase 3. `FrontendClassSkeletonBuilder` 改造为 `SkeletonBuildContext`

### 目标

彻底移除 skeleton 阶段对裸 `List<FrontendDiagnostic>` 的传递，把构建环境收敛到显式上下文对象。

### 执行清单

- 在 `FrontendClassSkeletonBuilder` 内新增 `SkeletonBuildContext`
- `build(...)` 签名改为显式接收 `DiagnosticManager`
- 删除无 manager 版本，不保留兼容方法
- `build(...)` 内不再新建 `ArrayList<FrontendDiagnostic>`
- 所有 helper 从传 `List<FrontendDiagnostic>` 改为传：
  - `SkeletonBuildContext`
  - 或更窄的业务对象，但不再单独传 diagnostics 列表
- 重点改造以下方法：
  - `buildClassCandidate(...)`
  - `toLirSignal(...)`
  - `toLirProperty(...)`
  - `toLirFunction(...)`
  - `resolveTypeOrVariant(...)`
  - `detectInheritanceCycles(...)`
  - `detectInheritanceCyclesDfs(...)`
  - `buildInheritanceCycleException(...)`
  - `duplicateClassException(...)`
- unsupported annotation 的 TODO 保留，但后续补诊断时必须走 manager

### 验收标准

- `FrontendClassSkeletonBuilder` 中不再出现 `new ArrayList<FrontendDiagnostic>()`
- `FrontendClassSkeletonBuilder` 的 private helper 不再裸传 `List<FrontendDiagnostic>`
- `SkeletonBuildContext` 至少包含：
  - `ClassRegistry`
  - `DiagnosticManager`
  - `Path sourcePath`
  - `FrontendAnalysisData`（从而携带完整 side-table 集合）
- skeleton 相关测试继续通过：
  - `FrontendClassSkeletonTest`
  - `FrontendClassSkeletonAnnotationTest`
- `FrontendInheritanceCycleTest`
- 类型降级 warning、重复类名 error、继承环 error 都由 manager 收集

### 当前状态（2026-03-10）

- [x] 已完成：`FrontendClassSkeletonBuilder.build(...)` 已改为显式接收 `DiagnosticManager`，无 manager 兼容入口已删除
- [x] 已完成：builder 顶层不再创建局部 `ArrayList<FrontendDiagnostic>`，正常路径与 fail-fast 路径统一写入共享 manager
- [x] 已完成：新增私有 `SkeletonBuildContext`，统一封装 `ClassRegistry`、`DiagnosticManager`、`Path sourcePath`、`FrontendAnalysisData`
- [x] 已完成：`SkeletonBuildContext` 现在通过 `FrontendAnalysisData` 携带完整 side-table 集合，skeleton phase 不再只看到局部 annotation table
- [x] 已完成：`buildClassCandidate(...)`、`toLirSignal(...)`、`toLirProperty(...)`、`toLirFunction(...)`、`resolveTypeOrVariant(...)` 等 helper 已迁移为基于上下文对象工作，不再裸传 diagnostics list
- [x] 已完成：duplicate class 与 inheritance cycle 路径会先写入 manager，再以 `diagnosticManager.snapshot()` 构造 `FrontendSemanticException`
- [x] 已完成：`FrontendModuleSkeleton` 现在持有 skeleton 阶段边界的 `DiagnosticSnapshot`；builder 不会重新导入 `FrontendSourceUnit.parseDiagnostics()`
- [x] 已完成：测试已锚定以下 Phase 3 行为：
  - builder 可在共享 parse->skeleton pipeline 中保留 parse diagnostics，且不会重复导入
  - 手工构造 `FrontendSourceUnit` 且未向 manager 导入 `parseDiagnostics` 时，builder 不会擅自补导入
  - builder 会把 annotation side-table 写入共享 `FrontendAnalysisData`，供后续 phase 继续复用
  - registry 注入、注解收集、继承环 fail-fast 语义保持稳定

---

## Phase 4. `FrontendSemanticAnalyzer` 与结果对象接入统一快照

### 目标

把 analyzer 入口也切换到显式 manager 驱动，让 `FrontendAnalysisData` 成为 manager 的结果快照，而不是隐式拼装产物。

### 执行清单

- 修改 `FrontendSemanticAnalyzer.analyze(...)` 签名，显式接收 `DiagnosticManager`
- 删除无 manager 版本，不保留兼容方法
- analyzer 内部不再自行决定“是否导入 parseDiagnostics”
- 冻结 parse diagnostics 导入规则：
  - parse 与 analyze 处于同一 manager 流程时，不重复导入
  - 若调用方手工构造 `FrontendSourceUnit`，则必须在进入 analyzer 前自行决定是否导入其 parse diagnostics
- 调整 `FrontendAnalysisData` 构造流程，使其先建立完整 side-table 拓扑，再通过 phase-boundary publish API 接收 manager 快照
- `FrontendModuleSkeleton` 仍保存 skeleton 阶段的诊断快照
- `FrontendAnalysisData` 保存 analyze 阶段完成时的诊断快照

### 验收标准

- `FrontendSemanticAnalyzer` 不再存在无 manager 入口
- `FrontendAnalysisData.diagnostics()` 与 analyze 完成时的 manager 快照一致
- `FrontendSemanticAnalyzerFrameworkTest` 继续通过，并补充验证：
  - manager 中的诊断与 result 中一致
  - analyzer 不会重复导入 parse diagnostics

### 当前状态（2026-03-10）

- [x] 已完成：`FrontendSemanticAnalyzer.analyze(...)` 已改为显式接收 `DiagnosticManager`，无 manager 版本已删除
- [x] 已完成：analyzer 已冻结“不自动导入 `FrontendSourceUnit.parseDiagnostics()`”规则，手工构造 unit 的调用方必须自行决定是否向 manager 导入 parse diagnostics
- [x] 已完成：analyzer 现在返回共享 `FrontendAnalysisData`，并在整个 analyze 流程中围绕同一份完整分析数据对象推进，而不是在阶段末单独拼装 side-table
- [x] 已完成：已将 `FrontendAnalysisResult` 重命名为 `FrontendAnalysisData`，并将其语义收口为共享 frontend 分析数据载体
- [x] 已完成：`FrontendAnalysisData` 不再通过构造流程单独传递各个 side-table，而是在对象内部统一拥有完整 side-table 集合
- [x] 已完成：`FrontendAnalysisData.bootstrap()` 现在只负责建立完整 side-table 拓扑，`publishPhaseBoundary(...)` 负责在阶段边界发布 `moduleSkeleton` 与 diagnostics snapshot
- [x] 已完成：`FrontendAnalysisData.diagnostics()` 与 analyze 完成时的 manager 快照一致，`FrontendModuleSkeleton` 继续保留 skeleton 阶段 `DiagnosticSnapshot`
- [x] 已完成：测试已锚定以下 Phase 4 行为：
  - 共享 manager 的 parse->analyze 流程不会重复导入 parse diagnostics
  - 手工构造带 `parseDiagnostics` 的 unit 且未导入 manager 时，analyzer 不会擅自补导入
  - result/moduleSkeleton 的 diagnostics 快照在分析结束后保持稳定，不会被后续 manager 追加改写

---

## Phase 5. `FrontendSemanticException` 与 fail-fast 语义收口

### 目标

让异常与正常结果都基于同一套 manager 快照语义，避免异常路径与普通路径各用一套诊断状态模型。

### 执行清单

- 保持 `FrontendSemanticException` 构造参数为不可变 `DiagnosticSnapshot`
- 所有抛出 `FrontendSemanticException` 的路径改为传入 `manager.snapshot()`
- 明确异常语义：
  - 异常对象只表示“抛出瞬间”的诊断快照
  - 异常对象不持有 `DiagnosticManager`
- 复核以下路径：
  - duplicate class
  - inheritance cycle
  - 后续 skeleton / interface / binder fail-fast 路径

### 验收标准

- `FrontendSemanticException` 不依赖 `DiagnosticManager`
- `FrontendSemanticException.diagnostics()` 返回抛出瞬间的 `DiagnosticSnapshot`
- 抛出异常时，异常中的 diagnostics 与当时 manager 快照一致
- `FrontendInheritanceCycleTest` 保持通过
- 未来新增 fail-fast 场景时，不需要重新发明“异常附带诊断”的机制

---

## Phase 6. deferred / unsupported diagnostics 统一出口

### 目标

为后续 binder/body phase 准备统一的 feature-boundary 诊断出口，避免再次出现“局部列表 + 局部 TODO”的散乱模式。

### 执行清单

- 约定 deferred / unsupported diagnostics 一律经由 `DiagnosticManager`
- 优先清理当前已知出口：
  - unsupported annotation
  - 未完整接入的 type-meta 来源
  - recognized but unsupported 的 frontend 节点
- 在文档中固定 category 命名规范，避免后续随意漂移

### 验收标准

- 新增 feature-boundary diagnostics 时，不再需要新增独立的诊断列表
- category 命名在 parser/sema/binder 内部可预测、可测试
- 文档中列出的 deferred 场景能落到统一 manager 通道

### 当前状态（2026-03-10）

- [x] 已完成：`FrontendClassSkeletonBuilder` 中原先的 unsupported annotation TODO 已替换为正式的 manager warning 输出
- [x] 已完成：property 上出现 skeleton phase 已识别但尚未支持的 annotation 时，现在会产出 `sema.unsupported_annotation` warning
- [x] 已完成：unsupported annotation 在发出 warning 的同时，仍然保留在共享 `FrontendAnalysisData.annotationsByAst()` 中，供后续 phase 决定是否继续消费
- [x] 已完成：测试已锚定以下行为：
  - `export` / `onready` 仍正常映射到 property skeleton annotations
  - region annotations 仍被忽略，不会制造噪声诊断
  - unsupported property annotation 会产生结构化 warning，且 diagnostic metadata 与 side-table 保持稳定

---

## Phase 7. 清理、测试与文档同步

### 目标

完成迁移收尾，清理旧调用模式，并同步更新相关文档和测试说明。

### 执行清单

- 全局搜索并清理 frontend 内部所有“裸传 `List<FrontendDiagnostic>`”路径
- 清理不再需要的局部 `ArrayList<FrontendDiagnostic>`
- 更新或新增 targeted tests
- 同步更新相关文档中的过时描述：
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - 如有必要，补充 `scope_architecture_refactor_plan.md` 中对 frontend diagnostic 流的引用

### 验收标准

- frontend 主干路径中不存在新的裸 `List<FrontendDiagnostic>` 收集协议
- parser / skeleton / analyzer / exception 四个边界全部完成统一快照语义
- 文档与代码状态一致
- 后续 binder 接入时可直接复用 manager，不需要重新做诊断基础设施重构

---

## 6. 各阶段建议提交边界

为降低回归风险，建议按以下提交边界推进，而不是把所有改动挤成一个大补丁。

1. `feat(frontend): introduce diagnostic manager contract and tests`
2. `refactor(frontend): wire parser diagnostics through manager`
3. `refactor(frontend): migrate skeleton builder to skeleton build context`
4. `refactor(frontend): route analyzer and results through manager snapshots`
5. `refactor(frontend): align semantic exceptions with diagnostic manager snapshots`
6. `docs(frontend): sync diagnostic manager migration status`

如果实际实现阶段希望压缩提交数量，也至少应保证“引入 manager 契约”和“skeleton context 重构”分开，方便回滚与 review。

---

## 7. 风险与控制策略

### 7.1 重复导入 parse diagnostics

风险：

- parse 已写入 manager
- analyzer 又从 `FrontendSourceUnit.parseDiagnostics` 再导入一次
- 结果中出现重复 diagnostics

控制策略：

- 冻结“同一 manager 流程中只导入一次 parse diagnostics”的规则
- 在 analyzer 层单测覆盖该行为

### 7.2 manager 误下沉到 scope / resolver

风险：

- 把用户可见诊断逻辑混入 lookup / metadata 层
- 破坏现有 scope 架构文档冻结边界

控制策略：

- 在 code review 中把这类改动视为架构违规
- 本文档明确禁止

### 7.3 `SkeletonBuildContext` 退化成新的“万能上下文对象”

风险：

- 上下文被无限塞字段
- helper 失去真实依赖边界

控制策略：

- `SkeletonBuildContext` 只服务于 skeleton phase
- 字段只加入该 phase 的稳定构建环境
- binder/body phase 若需要独立上下文，应另建类型，不复用 skeleton context

### 7.4 过度设计去重、排序、过滤策略

风险：

- 在基础设施迁移阶段引入无谓复杂度
- 拖慢主线改造

控制策略：

- 第一轮仅实现“追加 + 合并 + 快照 + hasErrors”
- 去重、按 phase 过滤、按 severity 汇总都留待真实需求出现后再做

---

## 8. 最终验收总表

当以下条件全部成立时，本计划可视为完成：

1. frontend 已存在稳定的 `DiagnosticManager`
2. `GdScriptParserService`、`FrontendClassSkeletonBuilder`、`FrontendSemanticAnalyzer` 全部显式接收 manager
3. frontend 内部不再保留无 manager 的兼容方法
4. `FrontendClassSkeletonBuilder` 已使用 `SkeletonBuildContext`，不再裸传 `List<FrontendDiagnostic>`
5. `FrontendSourceUnit` 暴露 parse-phase 不可变 `List` 快照，其余 frontend 主结果对象暴露 `DiagnosticSnapshot`
6. parser / skeleton / analyzer / exception 都有对应 targeted tests
7. `DiagnosticManager` 未渗入 `scope` / shared resolver
8. 文档与代码状态同步

---

## 9. 后续扩展建议

完成本计划后，frontend 诊断基础设施就能支撑后续 binder/body phase 的工作。下一步最合适的扩展方向是：

1. 在 interface/body phase 中继续沿用 `DiagnosticManager`
2. 为 deferred / unsupported feature 建立稳定 category 约定
3. 把 signal、type-meta、static-context、member/call 解析失败统一接入 frontend binder diagnostics
4. 在 `FrontendAnalysisData` 中补齐真正的 binding/type/member/call side-table 产物

届时 `DiagnosticManager` 的职责仍应保持稳定：

- 只做收集
- 不做推理
- 不越权进入 scope / resolver
