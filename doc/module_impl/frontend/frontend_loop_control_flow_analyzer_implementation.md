# FrontendLoopControlFlowAnalyzer 实施记录

> 本文档记录 `FrontendLoopControlFlowAnalyzer` 的调研结论、实施计划、阶段状态与最终合同。它既是本次任务的执行清单，也是后续维护该 analyzer 时需要持续对齐的事实源。

## 文档状态

- 状态：已完成，事实源维护中
- 最后更新：2026-07-20
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `src/test/java/gd/script/gdcc/frontend/sema/**`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `doc/module_impl/frontend/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
- 明确非目标：
  - 不在这里实现 `break` / `continue` 的 lowering
  - 不在这里修改 compile-only `FrontendCompileCheckAnalyzer` 的职责边界
  - 不在这里实现 for iteration planning 或 lowering；已记录 lambda 是 callable boundary，本 analyzer 只重置 loop depth。`match` 已穿透 walk 且不重置 loop depth（body 内 `break` / `continue` 隶属外层循环）
  - 不在这里新增 side table 或改写已有 side table 结构

---

## 1. 问题背景

当前 shared semantic pipeline 没有专门检查 `break` / `continue` 的使用位置是否合法。

这带来两个直接问题：

- 源码中的非法 `break` / `continue` 可能在 frontend shared analyze 阶段完全静默
- 这类非法节点会继续进入 compile-only 路径，并在 lowering/CFG builder 中以 invariant fail-fast 的形式暴露

`frontend_lowering_cfg_pass_implementation.md` 已明确记录当前 lowering 合同：

- `continue` 必须跳回当前 loop 的 `conditionEntryId`
- `break` 必须跳到当前 loop 的 `exitId`
- 没有 active loop frame 时遇到 `break` / `continue`，builder 必须 fail-fast

这说明“非法 loop control 的首个用户可见错误”应该留在语义分析阶段，而不是等 lowering 抛 `IllegalStateException`。

---

## 2. 调研结论

### 2.1 当前主链路现状

`FrontendSemanticAnalyzer` 当前 shared analyze 顺序为：

1. skeleton
2. scope
3. variable
4. top-binding
5. chain-binding
6. expr-type
7. annotation-usage
8. type-check
9. compile-only `FrontendCompileCheckAnalyzer` 仅在 `analyzeForCompile(...)` 追加

结论：

- 新 analyzer 必须挂在 shared `analyze(...)` 主链路上，而不是 compile-only 路径
- 它必须位于 `FrontendCompileCheckAnalyzer` 之前
- 它不需要依赖类型系统、member/call 解析结果或 compile surface

### 2.2 AST 与现有遍历能力

本仓库依赖的 `gdparser` 已提供：

- `BreakStatement`
- `ContinueStatement`
- `ASTNodeHandler.handleBreakStatement(...)`
- `ASTNodeHandler.handleContinueStatement(...)`

同时，`FrontendScopeAnalyzer` 已能稳定识别以下结构并发布 scope：

- `while`
- `for`
- `if` / `elif` / `else`
- `lambda`
- `match`

这意味着新 analyzer 不需要扩展 parser 或 scope phase，只需要基于已发布 AST / scope 事实做 diagnostics-only 遍历。

### 2.3 lowering 侧现有 fail-fast 事实

`FrontendCfgGraphBuilder` 当前存在显式 guard rail：

- `Loop control statement requires an active loop frame`

这不是普通源码错误应当采用的用户可见路径，而是实现不变量保护。新增 analyzer 的目标正是把这类源码错误前移到 shared semantic。

### 2.4 诊断 owner 与恢复边界

根据 `frontend_rules.md` 与 `diagnostic_manager.md`：

- frontend 普通源码错误应优先通过 `DiagnosticManager` 发布
- 新 analyzer 应保持单一 diagnostic owner
- 不应为 loop-control legality 新增 side table
- 对前序 phase 已跳过、未发布 scope 的 subtree，应继续静默跳过，避免重复噪声

本次拟新增 category：

- `sema.loop_control_flow`

owner 固定为：

- `FrontendLoopControlFlowAnalyzer`

### 2.5 语言语义与参考实现

本次额外参考了 Godot 源码搜索结果。GitHub code search 显示 `godotengine/godot` 的 `modules/gdscript/gdscript_parser.cpp` 中存在针对 `break` / `continue` 非法位置的专门检查。

这里的结论是推断性的，但足以支持当前实现方向：

- `break` / `continue` 的合法性属于 source-level loop-control contract
- 它应当在 frontend 早于 lowering 的阶段被报告

### 2.6 设计取舍

本次实现采用以下边界：

- `while` 与 `for` 都视为 loop boundary
  - `for` body 已进入 shared semantic；loop-control legality 仍是独立 diagnostics-only source fact，不读取 iterator type、iteration route 或 compile readiness
- `function` / `constructor` / `lambda` 视为新的 callable boundary
  - 外层 loop 不得跨 callable 泄漏到内层 callable
- `if` / `elif` / `else` / `match` / 普通 block` 不重置 loop depth`
  - 原因：这些结构共享同一个 loop control 域
- analyzer 只发诊断，不改 side table

---

## 3. 分阶段实施方案

### 阶段 A：建立事实基线与计划

目标：

- 固化问题背景、现有 pipeline、diagnostic owner 与实现边界
- 明确新增 analyzer 的 phase 位置、诊断分类与测试范围

产出：

- 本文档初版

### 阶段 B：实现 analyzer 核心

目标：

- 新增 `FrontendLoopControlFlowAnalyzer`
- 以 diagnostics-only 方式遍历 accepted source file
- 对非法 `break` / `continue` 发出 `sema.loop_control_flow`

拟定规则：

- `loopDepth == 0` 时遇到 `break` -> error
- `loopDepth == 0` 时遇到 `continue` -> error
- 进入 `while` / `for` body 前 `loopDepth + 1`
- 进入 `function` / `constructor` / `lambda` body 时重置 loop depth
- 缺少已发布 scope 的 subtree 直接跳过

### 阶段 C：接入 shared semantic pipeline

目标：

- 将新 analyzer 接入 `FrontendSemanticAnalyzer.analyze(...)`
- 保证它运行在 `FrontendCompileCheckAnalyzer` 之前
- 为 framework test 增加 phase 顺序探针

建议落点：

- 放在 `FrontendTypeCheckAnalyzer` 之后、`analyze(...)` 返回之前

原因：

- 它不依赖类型分析结果，但也不影响 type-check 的 typed contract
- 作为 diagnostics-only phase，放在 type-check 之后最少扰动现有 value/type 相关 phase 排序
- compile-only gate 继续保持在所有 shared semantic diagnostics 之后

### 阶段 D：补充单元测试并回归

目标：

- 新增 analyzer 专属测试
- 更新 framework phase-order test
- 跑定向测试并修复问题

测试必须覆盖：

- `break` 在循环之外报错
- `continue` 在循环之外报错
- `while` 中合法 `break` / `continue` 不报错
- 嵌套 `if` 中合法 `break` / `continue` 不报错
- `lambda` / nested callable 会切断外层 loop depth
- `for` 中合法 `break` / `continue` 不报 loop-control error
- shared `analyze(...)` 已能产出该类错误
- compile-only `analyzeForCompile(...)` 仍保持 compile gate 在 loop-control analyzer 之后

---

## 4. 验收准则

实现完成后，必须同时满足以下条件：

1. `FrontendSemanticAnalyzer.analyze(...)` 对非法 `break` / `continue` 直接产出 `sema.loop_control_flow` error。
2. `FrontendSemanticAnalyzer.analyzeForCompile(...)` 不再依赖 lowering fail-fast 才暴露这类源码错误。
3. 合法 `while` / `for` loop 中的 `break` / `continue` 不得误报。
4. 外层 loop 不得穿透 `function` / `constructor` / `lambda` 边界。
5. analyzer 不新增 side table、不改写现有 side table。
6. `frontend_rules.md` 与 `diagnostic_manager.md` 已同步 owner/category/phase 合同。
7. 单元测试同时覆盖 happy path 与 negative path，并通过定向执行。

---

## 5. 任务状态

- [x] 任务 1：完成文档与代码库调研，形成实施计划与验收准则。
- [x] 任务 2：实现 `FrontendLoopControlFlowAnalyzer` 核心遍历与诊断逻辑。
- [x] 任务 3：将 analyzer 接入 shared semantic pipeline，并补齐 framework phase 顺序测试。
- [x] 任务 4：补充 analyzer 专属单元测试，覆盖正反两类行为锚点。
- [x] 任务 5：同步更新相关事实源文档并跑定向测试回归。

---

## 6. 实施日志

- 2026-04-01
  - 已完成调研，确认当前缺口位于 shared semantic 与 lowering 之间。
  - 已确认 `gdparser` AST/visitor 已提供 `BreakStatement`、`ContinueStatement` 与专门 handler。
  - 已确认 lowering 侧已有 `Loop control statement requires an active loop frame` fail-fast guard rail。
  - 已确定新增 analyzer 采用 diagnostics-only 模式，不引入新 side table。
  - 已新增 `FrontendLoopControlFlowAnalyzer`，实现 `while` / `for` loop depth 跟踪，并在 `function` / `constructor` / `lambda` 边界重置 loop depth。
  - 已将 analyzer 接入 `FrontendSemanticAnalyzer.analyze(...)`，当前位于 `FrontendTypeCheckAnalyzer` 之后、`FrontendCompileCheckAnalyzer` 之前。
  - 已补充专属单元测试与 framework phase probe，分别锚定 shared analyze 行为与 compile gate 顺序。
  - 已同步更新 `frontend_rules.md` 与 `diagnostic_manager.md`，补齐 `sema.loop_control_flow` owner/category/phase 合同。
  - 已执行定向测试：
    - `FrontendLoopControlFlowAnalyzerTest`
    - `FrontendSemanticAnalyzerFrameworkTest`
    - `FrontendCompileCheckAnalyzerTest`
  - 上述定向测试已全部通过。
