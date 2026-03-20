# FrontendCompileCheckAnalyzer 实施计划

> 本文档是 `FrontendCompileCheckAnalyzer` 的实施计划，不是长期事实源。它的目标是在 frontend 语义分析与未来 frontend -> LIR lowering 之间，补上一道仅在编译模式下启用的最终闸门，确保任何仍处于 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 的 frontend 产物不会静默流入后续编译阶段。

## 文档状态

- 性质：实施计划
- 更新时间：2026-03-20
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/diagnostic/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_top_binding_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_type_system.md`
- 额外事实源：
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/AssertStatement.java`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/ArrayExpression.java`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/DictionaryExpression.java`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/PreloadExpression.java`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/GetNodeExpression.java`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/CastExpression.java`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/TypeTestExpression.java`
  - `godotengine/godot-docs` 中的 `tutorials/scripting/gdscript/gdscript_basics.rst`
  - `godotengine/godot-docs` 中的 `tutorials/scripting/gdscript/static_typing.rst`
- 明确非目标：
  - 不在本阶段实现 frontend -> LIR lowering
  - 不在本阶段实现 `assert` 的 lowering 或 backend 行为
  - 不在本阶段为 `ArrayExpression`、`DictionaryExpression`、`PreloadExpression`、`GetNodeExpression`、`CastExpression`、`TypeTestExpression` 补 lowering
  - 不在本阶段重构全部上游 analyzer 的错误级别或 owner 归属
  - 不在本阶段做完整的 LSP 诊断分层改造

---

## 1. 背景与问题定义

### 1.1 当前 frontend 主链路已经具备稳定的 8 个 phase

当前 `FrontendSemanticAnalyzer` 的共享语义主链路是：

1. skeleton
2. scope
3. variable inventory
4. top binding
5. chain binding
6. expression typing
7. annotation usage
8. type check

这条主链路已经能稳定发布：

- `moduleSkeleton`
- `scopesByAst()`
- `symbolBindings()`
- `resolvedMembers()`
- `resolvedCalls()`
- `expressionTypes()`
- `diagnostics()`

但它目前仍是“frontend 事实发布链”，不是“可安全进入 lowering 的最终编译链”。

### 1.2 当前代码库已经显式保留多种“尚不能进入 lowering”的状态

现有 frontend 语义模型明确允许以下非稳定状态继续存在于 side table 中：

- `FrontendExpressionTypeStatus`
  - `BLOCKED`
  - `DEFERRED`
  - `FAILED`
  - `UNSUPPORTED`
- `FrontendMemberResolutionStatus`
  - `BLOCKED`
  - `DEFERRED`
  - `FAILED`
  - `UNSUPPORTED`
- `FrontendCallResolutionStatus`
  - `BLOCKED`
  - `DEFERRED`
  - `FAILED`
  - `UNSUPPORTED`

这套设计对当前语义分析是合理的，因为：

- 上游 analyzer 需要在 fail-closed 的同时继续发布恢复性事实
- `type-check` 明确只消费稳定表达式事实，不负责把 upstream 非稳定状态重写成自己的错误
- 未来 LSP / inspection 模式需要看到这些中间状态，而不是被编译期逻辑提前抹平

但对编译模式来说，仅保留这些状态还不够，因为 lowering 不能消费它们。

### 1.3 现有 diagnostics 不能替代“最终 compile gate”

当前已有 diagnostics owner 侧重的是“谁先发现问题，谁负责报告”：

- `top binding` 负责 bare binding 错误与 unsupported subtree 边界
- `chain binding` 负责 member/call/chain deferred/unsupported 边界
- `expr typing` 负责 expression-only failed/deferred/unsupported route
- `type-check` 只负责 typed contract

这套 owner 划分应继续保留，但它不能替代最终 compile gate，原因有三点：

1. 这些 diagnostics 的目标是源码恢复与语义边界说明，不是“保证 lowering 入口只接收稳定事实”。
2. 某些非稳定状态当前只有 warning，没有 error；对编译模式而言，warning 不能阻止错误代码进入后续阶段。
3. 即便当前某些路径已经会报 error，也不能把“上游恰好有 error”当成 lowering 的唯一保护线；最终 compile gate 仍必须按 side table 事实 fail-closed。

### 1.4 `assert` 与 6 类表达式需要单独列为 MVP 编译拦截项

现状已经明确：

- `AssertStatement` 在 `gdparser` 中是 `Statement`，不是 `Expression`
- `FrontendTypeCheckAnalyzer` 当前把 `assert` condition 与 `if` / `elif` / `while` 一样，只要求 condition root 已发布稳定 typed fact
- 这条 source-level 合同应保持不变，不能为了 backend 未就绪而反向把 frontend 收紧成 strict-bool 方言

与此同时，当前 backend / lowering 还没有准备好编译 `assert`。因此：

- `assert` 必须由新的 compile-only 最终检查器显式拦截
- 这是一条“编译阶段临时封口”，不是 source contract 变更

另外，以下表达式当前虽然已经被 frontend 识别，但 expression typing 仍明确把它们发布为 deferred：

- `ArrayExpression`
- `DictionaryExpression`
- `PreloadExpression`
- `GetNodeExpression`
- `CastExpression`
- `TypeTestExpression`

这些节点的 lowering 不属于本阶段任务，但在 MVP 中必须保证它们不会漏入后续编译阶段。

---

## 2. 冻结设计结论

### 2.1 compile-only gate 必须与共享语义主链解耦

本计划冻结以下集成策略：

- `FrontendSemanticAnalyzer.analyze(...)` 保持为共享语义主链，不自动附带 compile-only 行为
- 新增 compile-only 入口，推荐形态为：
  - `FrontendSemanticAnalyzer.analyzeForCompile(...)`
  - 或等价的 compile wrapper / compile pipeline
- `FrontendCompileCheckAnalyzer` 只在编译模式中被调用
- `FrontendAnalysisInspectionTool`、未来 LSP 工具、以及所有仅需要共享语义事实的入口，都继续使用不带 compile check 的共享分析入口

这样做的理由是：

- compile-only 逻辑不应污染共享语义 phase 的职责
- 未来 LSP 模式可以直接绕开 compile check，而不需要在每个上游 analyzer 里增加 mode 分支
- 当前已有测试大量直接使用 `new FrontendSemanticAnalyzer().analyze(...)`；保持默认入口不变，可以把本次改动的影响面控制在 compile-only 路径

### 2.2 `FrontendCompileCheckAnalyzer` 是 diagnostics-only phase

该 analyzer 的职责冻结为：

- 读取已经发布的 frontend 事实
- 对 compile mode 仍不可接受的状态和节点发出最终 error diagnostic
- 不创建新的 side table
- 不改写已有 side table
- 不改变上游 phase 的 owner 归属
- 不做 lowering 语义、runtime 语义或 codegen 语义

换言之，它的角色是“最终编译闸门”，不是“第九个会重新解释 frontend 语义的 body analyzer”。

### 2.3 新增 compile-only category：`sema.compile_check`

本计划建议新增统一的 compile-only category：

- `sema.compile_check`

其冻结语义如下：

- owner：`FrontendCompileCheckAnalyzer`
- severity：固定为 `error`
- 适用场景：
  - compile surface 上仍残留的 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`
  - `assert` 语句的临时 compile block
  - 本阶段明确要求临时封口、但尚未进入 lowering 的表达式节点

这里不建议在首版里把 category 再拆成 `sema.compile_check.assert`、`sema.compile_check.deferred` 等多个子类，理由是：

- 当前目标是先建立一条稳定、可测试、可关闭的 compile-only 闸门
- 过早拆分类别会把实现重点从“是否安全阻断 lowering”转移到“如何命名 compile-only 变体”
- 后续若确实需要更细分类别，可在 compile gate 稳定后再演进

### 2.4 compile check 的扫描对象必须是“未来 lowering 实际会消费的 surface”

compile check 不应无条件扫描整个 AST。它应只覆盖未来 lowering 真正会接手的 surface：

- supported executable body
- 当前已定义的 supported property-initializer island

它不应主动深入以下已由上游独立封口的语义域：

- parameter default
- lambda subtree
- `for` subtree
- `match` subtree
- block-local `const`
- missing-scope / skipped subtree

原因是这些区域当前本来就不是 lowering surface；若 compile check再次深入，只会制造重复噪声，而不会提升编译安全性。

### 2.5 compile check 采用“双通道扫描”，而不是只扫 side table

为了完整覆盖本次需求，compile check 必须同时做两类扫描：

#### A. 显式 AST 节点拦截

这条通道覆盖“即使没有额外 status，也必须阻断编译”的构造：

- `AssertStatement`
- `ArrayExpression`
- `DictionaryExpression`
- `PreloadExpression`
- `GetNodeExpression`
- `CastExpression`
- `TypeTestExpression`

#### B. 已发布 frontend 事实扫描

这条通道覆盖 side table 中仍残留的非稳定状态：

- `expressionTypes()`
- `resolvedMembers()`
- `resolvedCalls()`

对每个条目，只要状态属于以下集合，就必须视为 compile blocker：

- `BLOCKED`
- `DEFERRED`
- `FAILED`
- `UNSUPPORTED`

明确不纳入 compile blocker 的状态：

- `RESOLVED`
- `DYNAMIC`

`DYNAMIC` 不应被 `FrontendCompileCheckAnalyzer` 拦截，因为它代表的是当前 frontend 已认可的 runtime-open 语义，而不是“下游完全无法消费”的未实现状态。

### 2.6 compile check 必须尊重上游 owner，并做最小必要去重

compile check 的目标不是复制上游 diagnostics，而是为 compile mode 补上“最终阻断 lowering 的 error”。

因此去重策略冻结为：

1. 先做显式 AST 节点拦截。
2. 再做 side table 非稳定状态扫描。
3. 若某个 compile anchor 上游已经存在 error diagnostic，则 compile check 默认不再为同一 anchor 追加第二条 generic `sema.compile_check`。
4. 若某个 compile anchor 只有 warning，没有 error，则 compile check 需要补一条 `sema.compile_check` error。
5. 显式 AST 拦截产生的 `sema.compile_check` 优先级高于 generic status pass；generic pass 遇到已处理 anchor 直接跳过。

这条规则的直接目标是：

- 保留当前 owner 体系
- 避免“upstream 已经 error，compile check 再报一次同位置 generic error”的噪声
- 仍然保证 deferred warning 等非阻断诊断在 compile mode 下被最终升级为真正的 compile blocker

### 2.7 `assert` 与临时拦截表达式的诊断文本必须显式说明“当前是 compile-only 封口”

这类 message 必须清楚表达：

- frontend 已识别该语法
- 当前阻断的原因是 compile/lowering/backend 尚未完成
- 这不是 source-level parse 错误，也不是 type-check contract 错误

建议 message 模板方向如下：

- `assert`：
  - `assert statement is recognized by the frontend but is temporarily blocked in compile mode until lowering/backend support lands`
- 临时拦截表达式：
  - `Array literal is recognized by the frontend but is temporarily blocked in compile mode until lowering support lands`

这里的重点不是英文措辞本身，而是必须让用户和后续维护者一眼看出：

- 这是 compile-only block
- 解除条件是 lowering/backend ready
- 不应把它误认为 parser/semantic/type-check 的长期失败

---

## 3. 分阶段实施步骤

### Phase 1：建立 compile-only 调用入口与 analyzer 骨架

#### 目标

在不改变共享 `analyze(...)` 行为的前提下，把 compile-only 最终检查器接入 frontend 主链路末尾。

#### 任务

1. 新增 `FrontendCompileCheckAnalyzer` 类。
2. 为 `FrontendSemanticAnalyzer` 增加 compile-only 入口，推荐：
   - 保留现有 `analyze(...)`
   - 新增 `analyzeForCompile(...)`
3. `analyzeForCompile(...)` 的执行顺序固定为：
   - 先运行现有 8 phase 的共享语义主链
   - 再运行 `FrontendCompileCheckAnalyzer`
   - 最后再次 `analysisData.updateDiagnostics(diagnosticManager.snapshot())`
4. `FrontendAnalysisInspectionTool` 与现有只依赖共享语义事实的调用方继续走 `analyze(...)`

#### 当前状态（2026-03-20）

- [x] 已新增 `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java` 作为 compile-only final gate 骨架
- [x] `FrontendSemanticAnalyzer` 已新增 `analyzeForCompile(...)`，默认 `analyze(...)` 保持共享语义链不变
- [x] `analyzeForCompile(...)` 已固定为“共享 8 phase -> compile check -> 刷新最终 diagnostics snapshot”
- [x] `FrontendSemanticAnalyzerFrameworkTest` 已补 compile-only phase probe，锁定 `analyze(...)` 不触发 compile check、`analyzeForCompile(...)` 在 type-check 之后触发 compile check
- [x] Phase 1 targeted tests 已通过，默认 `analyze(...)` 与 compile-only `analyzeForCompile(...)` 的入口分离已由 `FrontendSemanticAnalyzerFrameworkTest` 锚定

#### 验收标准

- [x] 现有 `FrontendSemanticAnalyzerFrameworkTest` 对默认 `analyze(...)` 的断言不需要因 compile-only 新 phase 而改写
- [x] compile-only 入口新增后，默认语义主链的 diagnostics snapshot 行为保持不变
- [x] compile-only 入口运行结束后，`analysisData.diagnostics()` 等于最新 `diagnosticManager.snapshot()`

### Phase 2：实现显式 AST compile-block 清单

#### 目标

先把“即使不看 side table 也必须阻断编译”的语法节点显式封口。

#### 任务

1. 在 compile surface 内扫描 `AssertStatement`
2. 在 compile surface 内扫描以下表达式：
   - `ArrayExpression`
   - `DictionaryExpression`
   - `PreloadExpression`
   - `GetNodeExpression`
   - `CastExpression`
   - `TypeTestExpression`
3. 为这些节点统一发 `sema.compile_check` error
4. `assert` 必须明确作为 statement 级规则处理，不能依赖 `expressionTypes()` 间接推断
5. 对已经处于上游 unsupported subtree 的区域不重复补 compile-only error

#### 当前状态（2026-03-20）

- [x] `FrontendCompileCheckAnalyzer` 已按 compile surface 扫描 `AssertStatement`
- [x] `FrontendCompileCheckAnalyzer` 已显式扫描 `ArrayExpression`、`DictionaryExpression`、`PreloadExpression`、`GetNodeExpression`、`CastExpression`、`TypeTestExpression`
- [x] 上述节点现在统一发出 `sema.compile_check` `error`
- [x] `assert` 已明确按 statement 级规则处理，不依赖 `expressionTypes()` 的 side-table 状态推断
- [x] compile gate 已显式跳过 parameter default、lambda、`for`、`match`、block-local `const` 与 skipped subtree，对这些上游 unsupported/deferred 边界不重复追加 compile-only error
- [x] 已新增 `FrontendCompileCheckAnalyzerTest`，覆盖 compile-only 入口正向阻断、默认 `analyze(...)` 不受影响、skipped subtree 不重复报错、以及同锚点已有 upstream error 时的去重行为
- [x] 已补充并通过 targeted tests：
  - `FrontendCompileCheckAnalyzerTest`
  - `FrontendSemanticAnalyzerFrameworkTest`
  - `FrontendAnalysisInspectionToolTest`
  - `FrontendExprTypeAnalyzerTest`
  - `FrontendTypeCheckAnalyzerTest`
  - `FrontendAnnotationUsageAnalyzerTest`

#### 验收标准

- [x] `assert` 出现在 supported executable body 中时，compile-only 路径会新增 `sema.compile_check` error
- [x] `assert` 在默认 `analyze(...)` 路径中不新增 compile-only error
- [x] 上述 6 类表达式只要出现在 compile surface 中，compile-only 路径都会被拦截
- [x] 这些节点当前的 source-level typed contract 不发生反向收紧

### Phase 3：实现 side table 非稳定状态的最终 compile gate

#### 目标

让 compile-only 路径对所有仍残留的 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 做最终 fail-closed。

#### 任务

1. 扫描 `expressionTypes()`
2. 扫描 `resolvedMembers()`
3. 扫描 `resolvedCalls()`
4. 为每个非稳定状态找到 compile anchor，并在没有已有 error 的前提下补 `sema.compile_check`
5. 跳过：
   - `RESOLVED`
   - `DYNAMIC`
   - 已被显式 AST compile-block pass 处理的 anchor

#### 编译锚点规则

建议按以下顺序选取 compile anchor：

1. 若当前条目本身对应一个可报告的 lowering surface 节点，则锚定到该节点
2. 若条目是 member/call step，则优先锚定到该 step
3. 若 step 不适合作为 compile anchor，则回退到拥有它的 enclosing expression
4. `AssertStatement` 单独锚定到 statement 自身

这条规则的目标是：

- 让 compile-only error 尽量贴近未来 lowering 的消费点
- 不把 compile gate 的消息锚到过大的 source range
- 不为了“统一锚点”而把 statement 与 expression 的边界打平

#### 当前状态（2026-03-20）

- [x] `FrontendCompileCheckAnalyzer` 已追加 compile-surface side-table 扫描，覆盖 `expressionTypes()`、`resolvedMembers()`、`resolvedCalls()`
- [x] generic pass 现在只把 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 视为 compile blocker；`RESOLVED` 与 `DYNAMIC` 被显式跳过
- [x] compile anchor 现按“节点自身优先、attribute expression 回退到 final member/call step”冻结，避免 attribute expression typed fact 与 terminal step fact 在同一 lowering 消费点上重复报错
- [x] generic pass 继续尊重 compile surface 边界；parameter default、lambda、`for`、`match`、block-local `const` 与 skipped subtree 中的 synthetic/published unstable fact 不会被 compile gate 重新拉回 surface
- [x] 同锚点去重现已覆盖：
  - 已有 upstream `error` 时不再补 generic `sema.compile_check`
  - 显式 AST compile-block 先占用 anchor，generic pass 直接跳过
  - attribute expression 与 final step 共用 anchor 时只保留一条 generic compile blocker
- [x] `FrontendCompileCheckAnalyzerTest` 已补 generic side-table compile gate、shared-anchor 去重、surface 外跳过、`DYNAMIC` 跳过、以及真实 deferred warning 在 compile-only 入口升级为 blocker 的测试
- [x] 本阶段 targeted tests 已通过：
  - `FrontendCompileCheckAnalyzerTest`
  - `FrontendSemanticAnalyzerFrameworkTest`
  - `FrontendAnalysisInspectionToolTest`
  - `FrontendChainBindingAnalyzerTest`
  - `FrontendExprTypeAnalyzerTest`

#### 验收标准

- [x] deferred warning 在 compile mode 下会被补成真正的 compile blocker
- [x] blocked/failed/unsupported 即使未来某条上游 owner 规则回归，也不会静默漏过 compile gate
- [x] compile check 不会因为同一 root 上有多个非稳定条目而制造大量重复 error

### Phase 4：把 compile gate 真正接到“进入 lowering 前”的调用约束上

#### 目标

不是只“多报一条 error”，而是把 compile gate 明确建成 lowering 的前置条件。

#### 任务

1. 约定 frontend -> LIR lowering 的未来调用方只能使用 compile-only 分析入口
2. 在 lowering 入口处显式检查：
   - `diagnosticManager.hasErrors()`
   - 或 `analysisData.diagnostics().hasErrors()`
3. 任何 `sema.compile_check` 出现时，都不得继续进入 lowering
4. 当前 lowering 尚不存在时，先以测试和文档冻结该约束

#### 当前状态（2026-03-20）

- [x] `FrontendSemanticAnalyzer` 的入口注释已明确冻结：
  - 默认共享 `analyze(...)` 只负责 frontend 事实发布，不保证 lowering-ready
  - 未来 lowering 调用方必须使用 `analyzeForCompile(...)`
  - 并在继续编译前检查 `analysisData.diagnostics().hasErrors() == false`
- [x] `FrontendSemanticAnalyzerFrameworkTest` 已新增 lowering-readiness boundary 测试，锁定共享 `analyze(...)` 不产生 compile gate error、`analyzeForCompile(...)` 会把 compile-only blocker 写入最终 diagnostics snapshot
- [x] `diagnostic_manager.md` 与 `frontend_rules.md` 已作为当前事实源写明：
  - compile-only gate 是 lowering 的前置条件
  - 共享 `analyze(...)` 结果不能直接视为 lowering-ready
  - inspection / 未来 LSP 入口继续绕开 compile-only gate

#### 验收标准

- [x] compile-only 分析入口可以作为未来 lowering 的唯一合法前置入口
- [x] 文档中明确写出“共享 `analyze(...)` 结果不能直接视为 lowering-ready”
- [x] compile gate 的存在不会改变共享语义事实，只改变“是否允许继续编译”

### Phase 5：文档与测试同步

#### 目标

把 compile-only gate 的职责、category、边界和 MVP 拦截清单同步进文档与测试，避免后续实现与文档分叉。

#### 任务

1. 新增 `FrontendCompileCheckAnalyzerTest`
2. 为 compile-only 入口补 framework 级测试
3. 更新以下文档：
   - `diagnostic_manager.md`
   - `frontend_rules.md`
   - 未来若实现落地，再新增或吸收为 `frontend_compile_check_analyzer_implementation.md`
4. 在文档中明确写出：
   - `assert` 目前只是 compile-only blocked，不改变 type-check contract
   - 6 类表达式目前只是 temporary compile intercept，不代表 source grammar 不支持

#### 验收标准

- 文档与测试都能说明 compile-only gate 的存在与用途
- `sema.compile_check` 被纳入诊断类别清单
- compile/LSP 分流边界在文档中是显式的，而不是靠代码推断

---

## 4. 测试计划

### 4.1 新增单元测试建议

建议新增 `FrontendCompileCheckAnalyzerTest`，至少覆盖以下场景：

1. `assert` 在 supported executable body 中触发 compile-only error
2. `ArrayExpression` 触发 compile-only error
3. `DictionaryExpression` 触发 compile-only error
4. `PreloadExpression` 触发 compile-only error
5. `GetNodeExpression` 触发 compile-only error
6. `CastExpression` 触发 compile-only error
7. `TypeTestExpression` 触发 compile-only error
8. deferred warning 在 compile mode 下被补成 `sema.compile_check`
9. 已有 upstream error 的同一 anchor 不再重复补 generic compile-check
10. `DYNAMIC` 路径不会被误拦截

### 4.2 framework 级测试建议

在 `FrontendSemanticAnalyzerFrameworkTest` 中新增 compile-only 入口测试，至少覆盖：

1. 默认 `analyze(...)` 仍然只发布共享语义事实
2. compile-only 入口在第 8 个 phase 之后附加 compile check
3. compile-only 入口会刷新 `analysisData.diagnostics()`
4. `FrontendAnalysisInspectionTool` 不调用 compile-only 入口

### 4.3 回归测试重点

本计划实施时，必须重点防止以下回归：

- 默认共享语义入口被 compile-only 错误污染
- `assert` condition 的 Godot-compatible source contract 被误改回 strict bool
- property initializer 支持岛内外的边界被 compile check 重新打平
- 上游 analyzer 已有 error 的场景出现 compile-only 双重报错
- unsupported/deferred subtree 被 compile check 继续深入，导致额外噪声

---

## 5. MVP 暂时拦截清单

以下项在本计划中明确冻结为“先拦截，后实现 lowering”：

### 5.1 statement

- `AssertStatement`

### 5.2 expression

- `ArrayExpression`
- `DictionaryExpression`
- `PreloadExpression`
- `GetNodeExpression`
- `CastExpression`
- `TypeTestExpression`

### 5.3 解除拦截条件

只有在以下条件全部满足后，才允许把对应项从 compile-only block 清单中移除：

1. frontend -> LIR lowering 已实现
2. 目标节点对应的 LIR 设计已在文档中冻结
3. backend 已能消费该 lowering 结果
4. targeted tests 覆盖：
   - happy path
   - failure path
   - diagnostics / ownership / lifecycle（若相关）
5. 文档同步更新

这条规则尤其适用于：

- `assert`
- `ArrayExpression`
- `DictionaryExpression`
- `PreloadExpression`
- `GetNodeExpression`
- `CastExpression`
- `TypeTestExpression`

在满足以上条件之前，它们都应继续由 compile-only gate 拦截，而不是“因为 frontend 已经识别”就提前放行。

---

## 6. 与未来 LSP 模式的关系

本计划刻意把 compile-only gate 设计成独立 phase，目的就是为未来 LSP 模式留出明确分叉点。

### 6.1 本计划已经保证的部分

- compile-only error 不会进入默认共享 `analyze(...)`
- `FrontendCompileCheckAnalyzer` 可以在 LSP / inspection 路径中完全不启用
- compile mode 与 LSP mode 至少在“是否追加最终编译闸门”这一点上已经可分离

### 6.2 本计划暂不解决的部分

当前共享语义 phase 里仍然存在若干 `error` 级 diagnostics，例如：

- `sema.binding`
- `sema.member_resolution`
- `sema.call_resolution`
- `sema.unsupported_*`

如果未来 LSP 模式希望把其中一部分进一步降级为非阻断诊断，这将是独立后续工程，不属于本计划直接实施范围。

本计划的结论是：

- 先建立 compile-only 最终闸门，保证未来 lowering 安全
- 再在后续 LSP 工程里逐步重整共享 phase 的严重级别策略

不要把这两个目标绑在同一个实现任务中，否则会同时扰动：

- 共享语义 owner 体系
- diagnostic category 契约
- compile / inspection 调用入口

---

## 7. 实施顺序建议

建议严格按以下顺序实施：

1. 新增 `FrontendCompileCheckAnalyzer` 骨架与 compile-only 入口
2. 先完成 `assert` 与 6 类表达式的显式 AST 拦截
3. 再补 side table 非稳定状态的 generic compile gate
4. 最后补 framework 测试、文档同步与 lowering 前置约束

不建议倒序实施，原因是：

- 若先做 generic status scan，很容易在没有显式节点清单时把 `assert` 漏掉
- 若先把 compile gate 接到 lowering 入口，但没有去重与 category 设计，会制造高噪声 diagnostics
- 若先尝试重整全部 LSP/compile severity，则会把本来清晰的“最终 compile 闸门”目标稀释掉

---

## 8. 最终验收清单

- [x] `FrontendSemanticAnalyzer.analyze(...)` 仍保持共享语义入口，不附带 compile-only phase
- [x] compile-only 分析入口在 type check 之后追加 `FrontendCompileCheckAnalyzer`
- [x] `FrontendCompileCheckAnalyzer` 为 diagnostics-only，不新增也不改写 side table
- [x] `sema.compile_check` 被正式引入并固定为 `error`
- [x] `AssertStatement` 在 compile mode 中被显式拦截
- [x] `ArrayExpression`、`DictionaryExpression`、`PreloadExpression`、`GetNodeExpression`、`CastExpression`、`TypeTestExpression` 在 compile mode 中被显式拦截
- [x] 所有已发布的 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 在 compile mode 中都不能静默漏过
- [x] `DYNAMIC` 不被 compile check 误判为 blocker
- [x] compile check 不会对已有 upstream error 的同一 anchor 重复补 generic error
- [x] diagnostics snapshot 在 compile-only 入口结束后保持一致
- [x] 文档明确写出 `assert` 与 6 类表达式只是 temporary compile intercept，不代表 source contract 已改变
- [x] 文档明确写出该 analyzer 未来不进入 LSP 路径

---

## 9. 结论

本计划的核心不是“再加一个会报错的 analyzer”，而是把 frontend 从“会发布语义事实”推进到“能对 lowering 输入做最终 fail-closed 保证”。

冻结后的工程结论是：

- 共享 `FrontendSemanticAnalyzer` 继续负责语义事实发布
- `FrontendCompileCheckAnalyzer` 只在 compile mode 下追加
- `assert` 与 6 类表达式先临时拦截，不在本阶段抢做 lowering
- 所有残留的 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 都必须在进入 lowering 前被最终封口

这条路径最符合当前仓库状态，也最利于后续把 compile 模式与 LSP 模式真正分开，而不把 compile-only 约束散落回上游每一个 analyzer 中。
