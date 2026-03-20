# FrontendAnalysisInspectionTool 实现说明

> 本文档作为 `FrontendAnalysisInspectionTool` 的长期事实源，定义当前工具定位、输入输出合同、报告格式、展示层与 frontend 主语义链的边界，以及后续工程必须遵守的约束。本文档替代原实施计划文档，不再保留分步骤进度、阶段性验收流水账或已完成任务记录。

## 文档状态

- 状态：事实源维护中（单脚本 inspection tool、UTF-8 安全切片、表达式/调用/诊断文本报告与定向测试已落地）
- 更新时间：2026-03-19
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/parse/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/debug/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_top_binding_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/gdcc_type_system.md`
- 明确非目标：
  - 不新增 frontend 语义 phase，不改变 `FrontendSemanticAnalyzer` 主链顺序
  - 不修改 `FrontendAnalysisData` 的 side-table 拓扑，不为展示层新增新的 published side table
  - 不把报告设计成机器优先的 JSON、XML 或 AST dump
  - 不借 inspection tool 补实现新的 binding / member / call / expression typing 语义
  - 不把当前 analyzer 未发布的语义事实伪装成“已解析成功”
  - 不在当前实现中提供 multi-file / whole-module 聚合报告

---

## 1. 目标与定位

当前 frontend 已经存在稳定主链：

1. `GdScriptParserService.parseUnit(...)`
2. `FrontendSemanticAnalyzer.analyze(...)`
3. 从 `FrontendAnalysisData` 读取 side table 与 diagnostics

`FrontendAnalysisInspectionTool` 的职责是作为这条主链之上的只读展示层，把：

- AST
- 源码文本
- `FrontendAnalysisData` 中已发布的 side-table 事实
- 最终 `DiagnosticSnapshot`

组合成一份“便于逐项对照源码确认”的文本报告。

该工具不是新的语义真源，也不是新的分析 phase。它只解释当前已发布事实，并把 display-only 派生信息显式标注出来。

---

## 2. 当前集成位置

### 2.1 公共类与包

当前公共入口固定为：

- 包：`dev.superice.gdcc.frontend.sema.debug`
- 类：`FrontendAnalysisInspectionTool`

其定位是 frontend semantic data 的只读 inspection/debug helper，不应混入 analyzer 核心实现。

### 2.2 当前公共 API

当前对外合同固定为：

```java
public final class FrontendAnalysisInspectionTool {
    public FrontendAnalysisInspectionTool(@NotNull ClassRegistry classRegistry);

    public FrontendAnalysisInspectionTool(
            @NotNull GdScriptParserService parserService,
            @NotNull FrontendSemanticAnalyzer semanticAnalyzer,
            @NotNull ClassRegistry classRegistry
    );

    public @NotNull InspectionResult inspectSingleScript(
            @NotNull String moduleName,
            @NotNull Path sourcePath,
            @NotNull String source
    );

    public @NotNull String renderSingleUnitReport(
            @NotNull String moduleName,
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData
    );

    public record InspectionResult(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull String report
    ) {}
}
```

合同约束：

- `inspectSingleScript(...)` 是 convenience API，内部按既有 parse/analyze 主链直接产出 `InspectionResult`
- `renderSingleUnitReport(...)` 是对已有 `FrontendSourceUnit + FrontendAnalysisData` 的纯渲染入口
- 工具类不缓存跨脚本 AST、analysis data 或报告状态

---

## 3. 展示层边界

### 3.1 `FrontendAstSideTable` 的 identity 约束

`FrontendAstSideTable` 通过 `IdentityHashMap` 以 AST node identity 为 key。inspection tool 因此必须：

- 使用 analyzer 同一批 AST 节点对象
- 在同一次 parse/analyze 生命周期内完成渲染
- 不得重新 parse 再尝试用结构相同的新 AST 去查 published side table

### 3.2 `expressionTypes()` 不是表达式全集

`FrontendAnalysisData.expressionTypes()` 只承载已发布的表达式类型事实，不覆盖所有 AST `Expression` 节点。当前至少存在以下缺席来源：

- unsupported / deferred subtree
- route-head `TYPE_META`
- 当前 phase 明确不发布结果的中间表达式

因此 inspection tool 必须遍历 AST 中的全部 `Expression`，而不是只遍历 `expressionTypes().entrySet()`。

### 3.3 `resolvedCalls()` 的 published 面

当前 `resolvedCalls()` 的主要 published surface 是 `AttributeCallStep`。因此调用展示必须区分：

1. `published`：直接来自 `resolvedCalls().get(attributeCallStep)`
2. `derived`：针对 bare `CallExpression` 的展示层派生说明
3. `display`：对链式 `AttributeCallStep` 缺少 published 条目时的 `UNPUBLISHED_CALL_FACT`

展示层不得把 bare call 或未发布 attribute-step 的 display-only 说明伪装成 analyzer 已发布事实。

### 3.4 diagnostics 真源

diagnostics 的唯一真源是 `FrontendAnalysisData.diagnostics()`。inspection tool 必须：

- 展示 snapshot 中的全部 diagnostics
- 不重新计算 severity / category / message
- 不改变 diagnostic owner

### 3.5 UTF-8 安全切片

AST `Range` / `FrontendRange` 使用 byte offset，不是 Java `String` 的 char index。inspection tool 必须通过专用源码索引器完成：

- byte offset -> char index 映射
- range -> 行列文本
- range -> snippet

不能直接用 `String.substring(startByte, endByte)`。

---

## 4. 报告格式合同

### 4.1 格式名

当前文本格式名固定为：

- `frontend-analysis-text-v1`

报告头部必须显式打印：

```text
FORMAT frontend-analysis-text-v1
```

### 4.2 头部摘要

报告头部固定包含：

- `FORMAT`
- `FILE`
- `MODULE`
- `SUMMARY`

`SUMMARY` 当前至少统计：

- diagnostics 总数
- expressions 总数
- 有 published expression fact 的表达式数
- call site 总数
- 有 published call fact 的调用数
- derived bare call 数

### 4.3 全局诊断区

全局诊断区标题固定为：

```text
DIAGNOSTICS
```

每条诊断必须展示：

- 稳定诊断 ID，例如 `D0001`
- severity
- category
- range 文本
- message

排序规则固定为：

1. `range.startByte`
2. `range.endByte`
3. severity，`ERROR` 在前
4. category
5. message

无 range 的 diagnostic 排在最后，显示为 `<no-range>`。

### 4.4 主体区

主体区标题固定为：

```text
SOURCE
```

当前主体按以下层级组织：

1. `source file`
2. `class`
3. `callable`
4. `statement anchor`
5. `expression`
6. `call`

当前容器标题合同为：

- `== source file ==`
- `== class Foo ==`
- `== func ping(seed) ==`
- `== ctor _init(...) ==`
- `== class body property initializers ==`

### 4.5 statement anchor

statement anchor 只服务阅读辅助，不是语义事实。当前规则：

- 单行锚点：`[L10] source text`
- 多行锚点：`[L10-L12]` + `| ` 前缀 block

### 4.6 表达式块

每个表达式块当前至少包含：

- 表达式 ID，例如 `E0001`
- range
- AST 类型名
- snippet
- `type.source`
- `type.status`
- `type.value`
- `type.reason`

其中：

- `type.source = published | display`
- `type.status` 若来自 published fact，直接镜像 `FrontendExpressionType.status()`
- 若无 published fact，当前只允许显示 `UNPUBLISHED`

### 4.7 调用块

每个调用块当前至少包含：

- 调用 ID，例如 `C0001`
- range
- snippet
- `call.source`
- `status`
- `callKind`
- `receiverKind`
- `calleeBinding`
- `receiverType`
- `argumentTypes`
- `returnType`
- `declarationSite`
- `detailReason`

其中：

- `call.source = published` 只用于 `resolvedCalls()` 中真实存在的结果
- `call.source = derived` 只用于 bare `CallExpression`
- `call.source = display` 当前只用于未发布的 `AttributeCallStep`
- `callKind = BARE_CALL_DERIVED` 只属于展示格式，不写回生产模型
- `callKind = UNPUBLISHED_CALL_FACT` 只属于 display-only 未发布链式调用块

### 4.8 诊断引用

表达式块、调用块、容器块都允许显示：

```text
diagnostics = [D0003, D0004]
```

规则固定为：

- diagnostic 全文只在全局区完整打印一次
- 内联块只引用稳定 ID
- 同一 diagnostic 在同一祖先链上不得重复引用

---

## 5. 当前内部结构

当前实现集中在 `FrontendAnalysisInspectionTool` 内部，主要由以下只读 helper 组成：

- `ReportRenderer`
  - 负责索引 AST、构建 display fact、附着 diagnostics、渲染文本
- `SourceTextIndex`
  - 负责 UTF-8 byte offset 到 Java char index 的安全映射与 snippet 提取
- `ExpressionDisplayFact`
  - 表达一个表达式块的展示事实
- `CallDisplayFact`
  - 表达一个调用块的展示事实
- `DiagnosticDisplayFact`
  - 表达全局诊断区条目

当前渲染流程固定为：

1. parse / analyze 组装
2. AST parent / visit-order / expression / call-site 索引
3. expression display fact 构建
4. call display fact 构建
5. diagnostic ID 分配与主附着点计算
6. 文本渲染

这些结构都是 display helper，不进入 `FrontendAnalysisData`。

---

## 6. 诊断附着合同

当前 diagnostic 附着策略固定为单一主附着点：

1. 所有 diagnostics 先进入全局列表
2. 无 range 的 diagnostic 挂到 `source file`
3. range 精确匹配 call site 时，挂到该 call
4. 否则若精确匹配 expression range，挂到该 expression
5. 否则挂到最小包含该 range 的容器，优先级：
   - statement
   - parameter default anchor
   - callable
   - class
   - source file

约束：

- 附着是展示行为，不改变 diagnostic owner
- 一条 diagnostic 只能有一个主附着点
- 但它始终保留在全局诊断区完整展示

---

## 7. declaration site 渲染

`FrontendBinding.declarationSite()`、`FrontendResolvedMember.declarationSite()`、`FrontendResolvedCall.declarationSite()` 当前都是开放的 `Object` 元数据。inspection tool 当前按以下优先级格式化：

1. `String`：直接输出
2. frontend AST declaration：尽量输出 `Class.member`、`Class._init` 或 `callable.parameter`
3. `ClassDef` / `FunctionDef` / `PropertyDef` / `ParameterDef`：输出声明名
4. 其他未知对象：输出其类名

该格式化器的目标是给出稳定可读的声明来源，而不是把所有 metadata 统一成新协议。

---

## 8. 测试锚点

当前定向测试文件为：

- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendAnalysisInspectionToolTest.java`

当前已锚定的行为包括：

- `inspectSingleScript(...)` 与 `renderSingleUnitReport(...)` 的一致性
- published chain call 展示
- bare `CallExpression` 的 `derived` 展示
- route-head `TYPE_META` 的 `UNPUBLISHED` 展示
- const initializer 中未发布 `AttributeCallStep` 的 `UNPUBLISHED_CALL_FACT`
- diagnostics 的全局区与内联回挂
- UTF-8 表达式 snippet 安全切片

当前定向验收命令为：

```powershell
powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendAnalysisInspectionToolTest
```

后续若调整该工具，至少要继续锚定：

- 全量表达式枚举不会退化成只看 `expressionTypes()`
- bare call 不会被误渲染成 published call fact
- 未发布 expression/call fact 不会被 silent omission
- diagnostics 全局区和主附着点保持稳定
- UTF-8 / 多行 snippet 渲染不发生切片错误

---

## 9. 后续工程约定

后续若继续演进 inspection tool，必须遵守以下边界：

1. 不把 inspection tool 发展成新的语义真源
2. bare call 的派生说明必须保持保守；宁可 `UNPUBLISHED/UNKNOWN`，也不要猜测不存在的 route
3. `UNPUBLISHED` 只表示“当前没有 published fact”，不自动等价于语义失败
4. 调整报告格式时必须显式版本化，例如未来升级到 `frontend-analysis-text-v2`
5. 若将来 bare `CallExpression` 获得正式 published call table，inspection tool 可以把 `derived` 降级为兼容兜底路径，但不得在过渡期混淆 `published` 与 `derived`

当前实现结论：

- `FrontendAnalysisInspectionTool` 已进入长期维护状态
- 后续工程应以本文档记录的边界、格式合同与测试锚点为准，而不是恢复旧的计划态分步骤描述
