# FrontendCompileCheckAnalyzer 实现说明

> 本文档作为 `FrontendCompileCheckAnalyzer` 的长期事实源，定义 compile-only final gate 的入口合同、compile surface、显式 AST 封口清单、generic published-fact blocker、diagnostic owner、去重规则，以及 compile / inspection / 未来 LSP 的分流边界。本文档替代此前的规划性记录，不保留阶段流水账或已完成任务日志。

## 文档状态

- 状态：事实源维护中（compile-only final gate、显式 AST 封口、generic published-fact blocker、shared/compile 分流边界、unary/binary 非 blocker 合同已落地）
- 更新时间：2026-03-20
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `doc/module_impl/frontend/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `diagnostic_manager.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_unary_binary_expr_semantic_implementation.md`
  - `frontend_type_check_analyzer_implementation.md`
  - `frontend_analysis_inspection_tool_implementation.md`
  - `doc/gdcc_low_ir.md`
- 明确非目标：
  - 不在这里实现 frontend -> LIR lowering
  - 不在这里实现 `assert` 的 lowering 或 backend 语义
  - 不在这里为 `ConditionalExpression`、`ArrayExpression`、`DictionaryExpression`、`PreloadExpression`、`GetNodeExpression`、`CastExpression`、`TypeTestExpression` 补 lowering
  - 不在这里把 compile-only blocker 反向回灌到 shared semantic / inspection / 未来 LSP 路径
  - 不在这里改写上游 analyzer 的 diagnostic owner，也不新增新的 semantic side table

---

## 1. 角色与入口合同

### 1.1 当前入口分工

当前 `FrontendSemanticAnalyzer` 冻结为两个入口：

1. `analyze(...)`
   - 共享 frontend 语义入口
   - 负责发布 8 个稳定 frontend phase 的 semantic facts
   - 不保证 lowering-ready
2. `analyzeForCompile(...)`
   - compile-only 入口
   - 先运行共享 8 phase
   - 再运行 `FrontendCompileCheckAnalyzer`
   - 最后刷新最终 diagnostics snapshot

inspection 与未来 LSP 必须继续消费共享 `analyze(...)`，而不是隐式继承 compile-only gate。

### 1.2 当前职责

`FrontendCompileCheckAnalyzer` 当前只负责 diagnostics-only final gate：

- 读取已经发布的 frontend 事实
- 对 compile mode 仍不可接受的 surface 发出 `sema.compile_check`
- 不创建新的 side table
- 不改写已有 side table
- 不改变 upstream diagnostic owner
- 不承担 lowering、runtime 或 codegen 语义

它的角色是“进入 lowering 前的最终封口”，不是新的 body analyzer。

### 1.3 lowering 前置条件

未来 frontend -> LIR lowering 的合法前置条件固定为：

1. 调用 `analyzeForCompile(...)`
2. 检查 `DiagnosticManager.hasErrors()` 或 `FrontendAnalysisData.diagnostics().hasErrors() == false`
3. 仅在没有 error 的前提下继续进入 lowering

共享 `analyze(...)` 的结果不能直接视为 lowering-ready。

---

## 2. Compile Surface

### 2.1 当前允许扫描的 surface

compile gate 当前只扫描未来 lowering 会实际消费的 surface：

- supported executable body
- supported property initializer island

compile gate 可以沿 callable body 和支持岛 property initializer 继续递归表达式子树，并据此建立 compile anchor。

### 2.2 当前显式跳过的区域

以下区域继续保留 upstream owner，不被 compile gate 重新深入：

- parameter default
- lambda subtree
- `for` subtree
- `match` subtree
- block-local `const`
- missing-scope / skipped subtree

这条边界的目的不是“少报错”，而是避免 compile gate 把已经被上游明确封口的恢复域重新打平成 lowering surface。

---

## 3. 显式 AST Compile-Block 清单

### 3.1 statement 级封口

`AssertStatement` 当前由 compile gate 显式拦截，并直接发出 `sema.compile_check` `error`。

这里需要同时保持两条事实：

- frontend 已经识别并正常遍历 `assert`
- shared type-check 继续把 `assert` condition 当成普通 source condition 处理

因此，`assert` 的 compile-only block 只表达“lowering/backend 尚未接通”，而不是 source contract 已被收紧。

### 3.2 declaration 级封口

脚本类 `static var` declaration 当前同样由 compile gate 显式拦截，并直接发出
`sema.compile_check` `error`。

这里的边界是 declaration-level，而不是 initializer-level：

- blocker 锚定到 `VariableDeclaration`
- 不要求 property 一定带 initializer
- 一旦命中，不再继续递归该 initializer subtree

这条规则对应当前 backend 的稳定事实：

- frontend/shared semantic 仍可识别并发布 static property metadata
- 但当前 backend 会在 property definition 层面 fail-fast 拒绝脚本静态字段

因此 compile gate 需要在进入 lowering/codegen 前把这类 declaration 提前封口，而不是
等 backend 抛异常。

### 3.3 expression 级封口

以下表达式当前同样由 compile gate 显式拦截：

- `ConditionalExpression`
- `ArrayExpression`
- `DictionaryExpression`
- `PreloadExpression`
- `GetNodeExpression`
- `CastExpression`
- `TypeTestExpression`

这些节点在 shared semantic 路径中仍是 frontend 已识别的语法/语义形态。compile gate 现在发出的错误只表示：

- lowering 尚未就绪
- 当前不能继续进入编译

其中 `ConditionalExpression` 还带有一条更具体的当前事实：

- 它的 lowering 需要依赖 control-flow / CFG 侧合同冻结
- 因此在 CFG 入口尚未定型前，compile gate 必须先把它挡在编译管线外

这些错误不表示：

- parser 不支持该语法
- source grammar 非法
- shared semantic 路径必须把它们改判成 `unsupported_expression_route`

### 3.3 当前消息语义

显式 AST compile-block 的消息必须显式表达：

- frontend 已识别该构造
- 当前是 compile-only 临时封口
- 解除条件是 lowering/backend ready

这样调用方和维护者才能区分：

- source-level 不支持
- semantic contract 失败
- compile-only 临时阻断

---

## 4. Generic Published-Fact Compile Gate

### 4.1 当前扫描的 side table

compile gate 当前会在 compile surface 上扫描以下已发布事实：

- `expressionTypes()`
- `resolvedMembers()`
- `resolvedCalls()`

### 4.2 当前 blocker 状态

以下状态当前一律视为 compile blocker：

- `BLOCKED`
- `DEFERRED`
- `FAILED`
- `UNSUPPORTED`

以下状态当前显式跳过：

- `RESOLVED`
- `DYNAMIC`

`DYNAMIC` 继续保留为 frontend 已接受的 runtime-open 事实，而不是 lowering 尚未实现的缺口。

这条 blocker 合同当前对 unary / binary 已经产生直接效果：

- 已稳定发布的 `UnaryExpression` / `BinaryExpression` 不会再因为“表达式家族尚未实现”被 compile gate 误封口
- `RESOLVED` unary / binary 与 `DYNAMIC` unary / binary 一样，都不会命中 generic compile blocker
- `not in` 仍会因为 upstream 发布的是显式 `UNSUPPORTED` 而被 compile gate 阻断
- `ConditionalExpression` 继续依赖显式 AST compile-block，而不是借 unary/binary 的转正被顺带放行

### 4.3 当前 compile anchor 规则

当前 compile anchor 规则冻结为：

1. member/call published fact 直接锚定到对应 step
2. expression published fact 默认锚定到 expression 自身
3. 若 expression 是 `AttributeExpression`，且 final member/call step 已在 compile surface 上发布，则优先回退到 final step

这条规则的目标是让 compile-only blocker 尽量贴近未来 lowering 的消费点，并避免：

- outer `AttributeExpression`
- terminal member/call step

在同一个 lowering anchor 上各报一条 generic blocker。

### 4.4 当前 fail-fast 边界

compile gate 当前对 shared publication 不变量保持 fail-fast：

- `expressionTypes()` 必须以 `Expression` 为 key
- `resolvedMembers()` 必须以 `AttributePropertyStep` 为 key
- `resolvedCalls()` 必须以 `AttributeCallStep` 为 key
- compile gate 启动前，对每个 source file 都必须已经发布 scope graph

这些 guard rail 属于实现协议损坏，不属于普通源码错误。

---

## 5. Diagnostic Owner 与去重规则

### 5.1 当前 category

compile gate 当前统一使用：

- `sema.compile_check`

其语义固定为：

- owner：`FrontendCompileCheckAnalyzer`
- severity：`error`
- 作用：阻止不 lowering-ready 的 frontend surface 继续进入编译

### 5.2 当前最小必要去重

当前去重规则冻结为：

1. 先做显式 AST compile-block
2. 再做 generic published-fact scan
3. 同一 anchor 若已被显式 pass 处理，generic pass 直接跳过
4. 同一 anchor 若已有 upstream `error`，compile gate 不再补第二条 generic `sema.compile_check`
5. `handledAnchors` 按 node identity 去重

这里的重点不是“让 compile gate 完全安静”，而是：

- 保留 upstream owner
- 避免 compile-only route 在同一 source point 上制造无意义双报
- 仍然把 warning 级 deferred route 升级成真正的 compile blocker

对 declaration-level static property compile-block 还额外保持一条子树边界：

- declaration 一旦命中 static-property explicit block，不再递归其 initializer subtree

这样可以避免同一条 `static var value = [1]` 在 compile-only 路径上同时收到
“static property blocked” 与 “array literal blocked” 两条 `sema.compile_check`。

### 5.3 当前 published-error 匹配方式

当前“已有 upstream error”按以下条件判定：

- 同一 `sourcePath`
- 同一 `FrontendRange`
- severity 为 `ERROR`

只要满足这三点，compile gate 就认为该 anchor 已经有上游错误，不再补 generic `sema.compile_check`。

---

## 6. compile / inspection / LSP 分流

当前已经冻结的分流边界是：

- compile-only error 不会进入默认共享 `analyze(...)`
- `FrontendCompileCheckAnalyzer` 可以在 inspection / 未来 LSP 路径中完全不启用
- compile mode 与 inspection/LSP mode 至少在“是否追加最终编译闸门”这一点上已经可分离

本模块当前不解决 shared semantic 里其他 `error` 级 diagnostics 在未来 LSP 中是否需要进一步降级的问题。那是独立工程，不属于这里的职责范围。

---

## 7. 解除 compile-only 封口的前提

只有在以下条件全部满足后，才允许把某个节点从显式 compile-block 清单中移除：

1. frontend -> LIR lowering 已实现
2. 目标节点对应的 lowering / control-flow / ownership 合同已在文档中冻结
3. backend 已能消费该 lowering 结果
4. targeted tests 已覆盖：
   - happy path
   - failure path
   - diagnostics / owner / lifecycle 边界（若相关）
5. 本文档、`frontend_rules.md`、`diagnostic_manager.md` 已同步更新

这条规则同样适用于：

- `assert`
- `ConditionalExpression`
- `ArrayExpression`
- `DictionaryExpression`
- `PreloadExpression`
- `GetNodeExpression`
- `CastExpression`
- `TypeTestExpression`

在满足这些条件之前，它们都必须继续由 compile-only gate 拦截，而不是因为“frontend 已识别”就提前放行。

---

## 8. 测试与回归基线

当前 compile gate 的关键行为由以下 targeted tests 锁定：

- `FrontendCompileCheckAnalyzerTest`
  - 显式 AST compile-block
  - generic side-table blocker
  - property initializer island 上的 generic blocker
  - shared-anchor 去重
  - surface 外 subtree 跳过
  - `DYNAMIC` 不误判为 blocker
  - `ConditionalExpression` 只在 compile-only 路径被拦截，不污染 shared analyze
  - `assert` 继续保持 shared condition contract，只在 compile-only 路径被拦截
- `FrontendSemanticAnalyzerFrameworkTest`
  - `analyze(...)` 与 `analyzeForCompile(...)` 的分离
  - compile gate 在 type-check 之后执行
  - `analyzeForCompile(...)` 的最终 diagnostics snapshot 会包含 compile-only blocker
- `FrontendAnalysisInspectionToolTest`
  - inspection 继续走共享 `analyze(...)`
  - inspection report 不会混入 `sema.compile_check`

这些测试的目标不是覆盖所有上游 analyzer，而是把 compile-only gate 的入口边界、阻断范围和 shared/compile 分流写死在仓库里。

---

## 9. 当前局限

当前实现仍明确依赖以下后续工程：

- frontend -> LIR lowering 入口必须强制使用 `analyzeForCompile(...)`
- lowering 在继续前必须检查 `diagnostics().hasErrors() == false`
- `assert` 与 7 类显式拦截表达式的真正 lowering/backend 支持仍待后续阶段补齐

若未来需要为 LSP 单独呈现 compile-only blocker，正确方向仍是：

- 继续保留 shared `analyze(...)`
- 由 compile caller 额外选择是否运行 compile gate

而不是把 compile-only 阻断逻辑重新分散回上游每个 analyzer。
