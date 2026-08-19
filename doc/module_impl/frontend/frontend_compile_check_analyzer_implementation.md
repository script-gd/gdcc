# FrontendCompileCheckAnalyzer 实现说明

> 本文档作为 `FrontendCompileCheckAnalyzer` 的长期事实源，定义 compile-only final gate 的入口合同、compile surface、显式 AST 封口清单、generic published-fact blocker、diagnostic owner、去重规则，以及 compile / inspection / 未来 LSP 的分流边界。本文档替代此前的规划性记录，不保留阶段流水账或已完成任务日志。

## 文档状态

- 状态：事实源维护中（compile-only final gate、for route-aware compile policy、显式 AST 封口、generic published-fact blocker、signal/method-reference feature-specific RESOLVED blocker、shared/compile 分流边界与 SuiteResolver stable facts 已落地；lambda compile gate 已按 published plan 解封）
- 更新时间：2026-08-19
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/**`
  - `src/test/java/gd/script/gdcc/frontend/sema/**`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `doc/module_impl/frontend/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `diagnostic_manager.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_signal_support.md`
  - `frontend_lambda_implementation.md`
  - `frontend_unary_binary_expr_semantic_implementation.md`
  - `frontend_type_check_analyzer_implementation.md`
  - `frontend_analysis_inspection_tool_implementation.md`
  - `doc/gdcc_low_ir.md`
- 明确非目标：
  - 不在这里实现 frontend -> LIR lowering
  - 不在这里实现 `assert` 的 lowering 或 backend 语义
  - 不在这里为 `ConditionalExpression`、`PreloadExpression`、`GetNodeExpression` 补 lowering（`ArrayExpression` / `DictionaryExpression` 已 compile-ready，不由本 gate 拦截）
  - 不在这里把 compile-only blocker 反向回灌到 shared semantic / inspection / 未来 LSP 路径
  - 不在这里改写上游 analyzer 的 diagnostic owner，也不新增新的 semantic side table

---

## 1. 角色与入口合同

### 1.1 当前入口分工

当前 `FrontendSemanticAnalyzer` 冻结为两个入口：

1. `analyze(...)`
   - 共享 frontend 语义入口
   - 负责发布 skeleton/scope/variable、interface/body suite、shared semantic publication 与 diagnostics-only phase 的 frontend facts / diagnostics snapshots
   - body semantic facts 只来自 `FrontendSuiteResolver` 的 per-owner patch transaction；shared analyzer 不再提供 legacy whole-phase body publication bypass
   - 不保证 lowering-ready
2. `analyzeForCompile(...)`
   - compile-only 入口
   - 先运行共享 semantic pipeline
   - 再运行 `FrontendCompileCheckAnalyzer`
   - 最后刷新最终 diagnostics snapshot

inspection 与未来 LSP 必须继续消费共享 `analyze(...)`，而不是隐式继承 compile-only gate。

### 1.2 当前职责

`FrontendCompileCheckAnalyzer` 当前只负责 diagnostics-only final gate：

- 读取已经发布的 frontend 事实
- 读取 compile-gate 入口处冻结的 live `DiagnosticManager` snapshot 作为 upstream duplicate-suppression 输入
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

这里的 “error” 不只包含 `sema.compile_check`：

- upstream shared semantic 已发布的 source error（例如 `sema.variable_binding`）同样必须阻止进入 lowering
- compile gate 一般不应为了已有 source error 再补第二条 `sema.compile_check`
- lowering-only fact 缺洞中的“warning 也要阻断编译”不再写死在单个分支里；compile gate 必须通过静态 category 配置判断：
  - 哪些 non-error diagnostic 仍然属于 compile blocker
  - 哪些 upstream category 与 `sema.compile_check` 不冲突，因此不参与去重抑制

共享 `analyze(...)` 的结果不能直接视为 lowering-ready。

---

## 2. Compile Surface

### 2.1 当前允许扫描的 surface

compile gate 当前只扫描未来 lowering 会实际消费的 surface：

- supported executable body
- supported property initializer island

compile gate 可以沿 callable body 和支持岛 property initializer 继续递归表达式子树，并据此建立 compile anchor。对 property initializer 而言，这条 compile surface 的 downstream 已经固定为 `PROPERTY_INIT` CFG/body lowering 与真实 `init_func` helper materialization，而不是停留在 shell-only scaffold。

### 2.2 当前显式跳过的区域

以下区域继续保留 upstream owner，不被 compile gate 重新深入：

- parameter default
- 未记录 lambda（property initializer / parameter default / skipped subtree 中缺 published `FrontendLambdaPlan` 的 `LambdaExpression`）
- `match` subtree
- block-local `const`
- missing-scope / skipped subtree

这条边界的目的不是“少报错”，而是避免 compile gate 把已经被上游明确封口的恢复域重新打平成 lowering surface。`ForStatement` 不属于该跳过集合：它已进入 shared semantic，compile gate 会按 route-aware policy 处理——已注册 lowering contract 的 route 会命中 statement root 并进入 body 重扫 facts，未注册 contract 的 route 则在 statement root 发 route-not-ready blocker。

**已记录 lambda**（supported executable body 内、`lambdaPlans()` 已发布且 body 已发布的 `LambdaExpression`）同样不属于跳过集合：compile gate 会把它放上 compile surface 并像普通 executable block 一样递归扫描其 body facts。缺 published plan / body 的 lambda 保持 fail-closed（见 §3.3）。

---

## 3. 显式 AST Compile-Block 清单

### 3.1 statement 级封口

`AssertStatement` 当前由 compile gate 显式拦截，并直接发出 `sema.compile_check` `error`。

这里需要同时保持两条事实：

- frontend 已经识别并正常遍历 `assert`
- shared type-check 继续把 `assert` condition 当成普通 source condition 处理

因此，`assert` 的 compile-only block 只表达“lowering/backend 尚未接通”，而不是 source contract 已被收紧。

`ForStatement` 使用 route-aware compile policy（不再使用无条件 for root blocker）：

- compile gate 读取已发布的 `forIterationPlans()`，并以 `ForLoweringContractRegistry.get(plan.route())` 判定 route 是否 compile-ready。
- contract 非 null 的 route（当前为 `RANGE_CALL` / `INT_SHORTHAND`）放行：mark owning `ForStatement`（同时即 iterator declaration key 与 iteration plan fact 的 side-table key）、walk `plan.sourceOperands()`（iterable / range arguments）并进入 body 重扫 published facts。
- contract 为 null 的 route（当前为 `GENERIC_VARIANT` 及其余保留 route）在 owning `ForStatement` root 发 route-not-ready `sema.compile_check` `error`，说明缺少已注册的 lowering route，而不是 `FOR_SUBTREE` unsupported；不进入 body。
- 缺失 iteration plan 属于上游 phase 边界被破坏，fail-fast 抛异常，不伪装成普通 compile block。
- route-not-ready blocker 复用统一去重合同：同一 `ForStatement` anchor 已有 upstream error 时不再补发同级 `sema.compile_check`。
- shared `analyze(...)` 不包含该 policy；只有 `analyzeForCompile(...)` 会按 route readiness 决定 for 是否进入 lowering。
- 该 policy 只消费已发布的 iteration plan 与 lowering contract registry，不控制 iterator inventory、completeness certificate 或 child-suite dispatch。
- hard non-iterable 的 shared `sema.type_check` 诊断不改变 compile policy：compile gate 仍只按 `plan.route()` 查询 lowering contract。若同一 `ForStatement` 已有 upstream `sema.type_check` error，route-not-ready blocker 继续按统一去重合同省略。

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
- `PreloadExpression`
- `GetNodeExpression`

`ArrayExpression` / `DictionaryExpression` 不属于当前显式 compile-block 列表：shared semantic 发布 `FrontendContainerLiteralPlan`，CFG/body 经 `ContainerLiteralItem` 发射 `construct_container_literal`，backend `ContainerLiteralInsnGen` 已闭环（见 `frontend_container_literal_implementation.md`）。

`LambdaExpression` 不再无条件形态级封口，而是按 published plan 分流：

- **已记录 lambda**（`lambdaPlans()` 含该节点且 body 已发布）：放上 compile surface 并递归扫描 body facts（与普通 executable block 相同）。`construct_lambda` lowering 合同、合成 `_lambda_<k>` 函数与 C backend 均已落地（见 `frontend_lambda_implementation.md`）。
- **未记录 lambda**（property initializer / parameter default / skipped subtree，缺 published plan）：保持形态级 `sema.compile_check` blocker，不静默放行；若上游已在同一 exact range 发布 unsupported 诊断，则按统一去重合同省略补发。
- lambda body 内的 `match` 仍走 `handleMatchStatement` 跳过：compile 失败由上游 `sema.unsupported_binding_subtree` owner 持有，compile gate 不再额外包一层 `sema.compile_check`。

`TypeTestExpression` 不属于当前显式 compile-block 列表：shared semantic 发布 `RESOLVED(bool)` + `typeTestTargets()`，body lowering 发射统一 `is_instance_of` / 常量 bool，backend `IsInstanceOfInsnGen` 分派 + runtime helpers 已落地。

`CastExpression` 同样不属于当前显式 compile-block 列表：shared semantic 发布 `RESOLVED(targetType)` 与 `sema.unsafe_cast` / `sema.type_check` diagnostic，body lowering 按 `ExplicitCastDecision` 发射 assign / pack / `builtin_cast` / `object_cast`，backend generator 与 runtime helpers 已闭环（见 `frontend_cast_expression_implementation.md`）。

对仍在显式 intercept 列表中的表达式：

- lowering 尚未就绪
- 当前不能继续进入编译

其中 `ConditionalExpression` 还带有一条更具体的当前事实：

- 它的 lowering 需要依赖 frontend CFG graph / condition-evaluation-region 合同冻结；`FrontendLoweringBuildCfgPass` 已能建图，但 value-merge / branch-result materialization 仍未接通，因此仍不足以支撑解封
- 因此在 CFG 入口尚未定型前，compile gate 必须先把它挡在编译管线外

short-circuit `BinaryExpression(and/or/&&/||)` 当前已经从显式 compile-block 列表中移除：

- shared semantic 路径继续稳定发布 `and/or` 的 typed fact
- dedicated frontend CFG short-circuit lowering 已能同时覆盖 condition-context 与 value-context
- compile gate 现在只要求这类表达式的 published facts 处于 lowering-ready 状态，而不再额外按 AST root 封口

compound assignment 现在已经离开 compile-only blocker 列表：

- shared semantic、frontend CFG read-modify-write shape 与 executable-body body lowering 都已接通
- compile mode 不再因为 AST root 是 compound assignment 而额外报 `sema.compile_check`
- 这类 route 现在与 ordinary supported expression 一样，只依赖 published fact 是否 lowering-ready

constructor route 当前也不再属于 compile-only blocker：

- `.new(...)` 与 bare direct builtin constructor 的 shared semantic publication 已稳定进入 `resolvedCalls()`
- body lowering 与 backend `construct_object` 已闭合，engine / builtin / gdcc zero-arg constructor 都可继续下沉
- compile gate 继续只按 published fact 状态判定，但额外保留一条 regression guard：若 gdcc 带参 constructor 被错误重新发布为 `RESOLVED`，compile mode 仍会在这里硬挡住

这些错误不表示：

- parser 不支持该语法
- source grammar 非法
- shared semantic 路径必须把它们改判成 `unsupported_expression_route`

### 3.4 当前消息语义

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
- `symbolBindings()`（仅用于 Phase 0 起的 feature-specific bare value-reference blocker；不改变 generic status scan）
- `slotTypes()`

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

generic status scan 之外，compile gate 还保留一组 **RESOLVED feature-specific blocker**（结构同 `shouldBlockParameterizedGdccConstructor`，必须放在 `isCompileBlocking` 短路之前）：

- `resolvedMembers()` 中 RESOLVED 的 Dictionary 实例 method-reference（`METHOD && BUILTIN && receiverType instanceof GdDictionaryType`）以及 builtin type-meta static method-reference（`STATIC_METHOD && ownerKind == BUILTIN`）
- 已放行：signal 值读取、`.emit` / `.connect` / `.disconnect`、Object/self `METHOD`、非 Dictionary builtin instance、GDCC/engine static、bare utility 值读取。作为 surface `CallExpression.callee()` 的 identifier 必须排除，以免误伤合法 bare method / static / utility 调用
- static-context / type-meta signal 仍由 generic `UNSUPPORTED`/`BLOCKED` scan 拦截
- 这些 blocker 只发 `sema.compile_check`，不改写 shared `analyze(...)` / inspection 已发布的 RESOLVED facts

`symbolBindings()` 本身还键 `LiteralExpression` / `SelfExpression`；bare blocker 只消费 `IdentifierExpression`，不得按 `GdSignalType` / `GdCallableType` 猜测局部变量。

显式 `self` assignment-target prefix 的 published fact 也属于 generic scan 的正式输入：

- 合法 `self.<property> = value` 中，prefix `SelfExpression` 应已发布为 `RESOLVED(current class object type)`，compile gate 不产生额外 diagnostic。
- static context、property initializer boundary 等非法上下文中，prefix `SelfExpression` 可能发布为 `BLOCKED` / `FAILED`；compile gate 可以消费该 fact，但不得把已有 upstream source error 改写成 assignment-root 级 generic blocker。
- 如果未来出现没有 upstream owner 的 non-lowering-ready `SelfExpression` fact，compile gate 的兜底 anchor 必须保持在具体 `SelfExpression`，而不是外层 assignment root。

这条 blocker 合同当前对 unary / binary 已经产生直接效果：

- 已稳定发布的 eager `UnaryExpression` / `BinaryExpression` 不会再因为“表达式家族尚未实现”被 compile gate 误封口
- `RESOLVED` eager unary / binary 与 `DYNAMIC` eager unary / binary 一样，都不会命中 generic compile blocker
- object/nil equality 与 object identity equality 在 shared semantic 发布 `RESOLVED(bool)` 后，compile gate 不得再把它们当作 not lowering-ready
- object/object ordering 继续由上游 `sema.expression_resolution` 发布 `FAILED`，compile gate 消费该 fact，不新增独立 diagnostic 类别
- `and/or` 虽然也会在 shared semantic 路径发布稳定 typed fact，但它们属于独立的显式 AST compile-block，而不是 generic published-fact blocker
- `not in` 仍会因为 upstream 发布的是显式 `UNSUPPORTED` 而被 compile gate 阻断
- `ConditionalExpression` 继续依赖显式 AST compile-block，而不是借 unary/binary 的转正被顺带放行

### 4.3 当前 compile anchor 规则

当前 compile anchor 规则冻结为：

1. member/call published fact 直接锚定到对应 step
2. expression published fact 默认锚定到 expression 自身
3. `AttributeSubscriptStep` keyed `expressionTypes()` 直接锚定到 step 自身
4. 若 expression 是 `AttributeExpression`，且 final member/call step 已在 compile surface 上发布，则优先回退到 final step

这条规则的目标是让 compile-only blocker 尽量贴近未来 lowering 的消费点，并避免：

- outer `AttributeExpression`
- terminal member/call step

在同一个 lowering anchor 上各报一条 generic blocker。

### 4.4 当前 fail-fast 边界

compile gate 当前对 shared publication 不变量保持 fail-fast：

- `expressionTypes()` 必须以 `Expression` / `AttributePropertyStep` / `AttributeCallStep` /
  `AttributeSubscriptStep` 为 key
- `resolvedMembers()` 必须以 `AttributePropertyStep` 为 key
- `resolvedCalls()` 必须以 `AttributeCallStep` 或 bare `CallExpression` 为 key
- compile gate 启动前，对每个 source file 都必须已经发布 scope graph

`expressionTypes()` 的这条合同还有一个容易被忽视但已经冻结的含义：

- 下游消费者不得再假定它是 “只以 `Expression` 为 key 的 side table”
- `entrySet()` 既不是全部 `Expression` 的全集，也不是 expression-only 视图
- 任何 generic scan / debug dump / helper API 若要处理 `expressionTypes()`，都必须显式接受
  `AttributePropertyStep` / `AttributeCallStep` / `AttributeSubscriptStep`

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
5. 若某个 upstream diagnostic category 被登记为“不冲突无需去重”，则该 category 触发的 compile blocker 允许与 `sema.compile_check` 共存
6. `handledAnchors` 按 node identity 去重

这里的重点不是“让 compile gate 完全安静”，而是：

- 保留 upstream owner
- 避免 compile-only route 在同一 source point 上制造无意义双报
- 对被静态配置登记的 non-error blocker，仍然把 warning 级事实缺洞升级成真正的 compile blocker

当前静态配置至少包含：

- non-conflicting upstream category map
  - `sema.variable_slot_publication`
- non-error blocking category set
  - `sema.variable_slot_publication`

对 declaration-level static property compile-block 还额外保持一条子树边界：

- declaration 一旦命中 static-property explicit block，不再递归其 initializer subtree

这样可以避免同一条 `static var value = [1]` 在 compile-only 路径上同时收到
“static property blocked” 与 “array literal blocked” 两条 `sema.compile_check`。

对 direct explicit-self assignment target 还额外保持一条窄去重规则：

- 若 `self.<property> = value` 的 assignment root non-lowering-ready fact 只是传播左侧 prefix `SelfExpression` 的同一 blocking status，且该 `SelfExpression` exact range 已经有 upstream blocking diagnostic，generic scan 必须跳过 assignment-root 级 `sema.compile_check`。
- 这条去重只覆盖 plain `=`、单步 `AttributePropertyStep`、base 为显式 `SelfExpression` 的 direct property assignment；assignment value type incompatibility、value-required assignment、以及真正 root-owned failure 仍由 root 自身负责。
- 该规则不改变 `resolvedMembers()` / `resolvedCalls()` 的通用扫描合同，也不把 property-initializer 普通 member/call blocker 静默吞掉。

对 `CastExpression` / `TypeTestExpression` 还额外保持一条 value-operand 传播去重规则：

- 若 cast / type-test root 的 non-lowering-ready fact 只是传播其 `value` operand 的同一 blocking status，generic scan 必须跳过 root 级 `sema.compile_check`。
- 判定条件：operand exact range 已有 upstream blocking diagnostic；或 operand 在 compile surface 上携带相同 `status + detailReason` fact（自身会在 operand anchor 被扫描）。
- 该规则覆盖 `missing as int`、`missing is int` 与链式 `(missing as int) as float` 等 dependency-propagated 场景；真正 root-owned cast/type-test failure（如未知 target type / `is null`）仍由 root 自己的 upstream diagnostic 负责，exact-range 去重继续生效。
- 该规则不创建新 side table，不改写上游 semantic ownership，也不扩展到 binary/unary/call 等其它 propagated root。
- **Invariant / debt：** 当前仅 cast / type-test 在 body owner 中记录 `rootOwnsOutcome=false` 并跳过 root 重发诊断；binary / unary / call 仍在 root range 重持有 diagnostic，故 exact-range 去重足够。未来若更多 kind 改为非 root-owned 发布，必须同步推广 ownership 信号给 compile gate（Option A），而不是继续按 AST kind 硬编码增长。

### 5.3 当前 published-error 匹配方式

当前“已有 upstream error”按以下条件判定：

- upstream 诊断集合来自 compile gate 入口处冻结的 live `DiagnosticManager.snapshot()`，而不是之后会被本 gate 继续追加的 mutable manager
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
- `ForStatement`（route-aware compile policy：`ForLoweringContractRegistry` 中已注册的 route 放行并进入 body 重扫；未注册 route 在 statement root 拦截；已注册 route 的 CFG/body lowering 已落地，见 `frontend_for_range_loop_implementation.md`）
- `ConditionalExpression`
- `PreloadExpression`
- `GetNodeExpression`

`TypeTestExpression` 已从显式 compile-block 列表移除（见 `frontend_is_type_test_implementation.md`）。
`CastExpression` 已从显式 compile-block 列表移除（见 `frontend_cast_expression_implementation.md`）。
`ArrayExpression` / `DictionaryExpression` 已从显式 compile-block 列表移除（见 `frontend_container_literal_implementation.md`）。
`LambdaExpression`（已记录、published plan + body）已从无条件形态级 compile-block 移除并纳入 compile surface；未记录 lambda 仍 fail-closed（见 `frontend_lambda_implementation.md`）。

在满足这些条件之前，它们都必须继续由 compile-only gate 拦截，而不是因为“frontend 已识别”就提前放行。

---

## 8. 测试与回归基线

当前 compile gate 的关键行为由以下 targeted tests 锁定：

- `FrontendCompileCheckAnalyzerTest`
  - 显式 AST compile-block（当前 3 类：Conditional / Preload / GetNode；Array / Dictionary / Cast / TypeTest 已离开 intercept）
  - short-circuit binary 不再被 compile gate 误封口
  - object/nil equality 与 object identity equality 不再触发 compile blocker
  - object/object ordering 继续由上游 `sema.expression_resolution` 阻断，不新增 `sema.compile_check`
  - signal 值读取、`.emit`、`.connect/.disconnect`、Object/self `METHOD`、非 Dictionary builtin 实例、GDCC/engine 静态与 bare utility 值读取已放行；仍拦截 Dictionary 实例 method-ref 与 builtin type-meta static method-ref；callee-exclusion 与 `Signal`/`Callable` 局部变量不被类型猜测误伤
  - generic side-table blocker
  - property initializer island 上的 generic blocker
  - shared-anchor 去重
  - surface 外 subtree 跳过
  - `DYNAMIC` 不误判为 blocker
  - `ConditionalExpression` 只在 compile-only 路径被拦截，不污染 shared analyze
  - `assert` 继续保持 shared condition contract，只在 compile-only 路径被拦截
  - cast / type-test value-operand 传播去重：`missing as int` / `missing is int` / 链式 `(missing as int) as float` 不在 root 补 `sema.compile_check`
  - cast / type-test root-owned target failure 仍由 `sema.expression_resolution` 持有，exact-range 去重后无 root `compile_check`
  - for route-aware compile policy：`RANGE_CALL` / `INT_SHORTHAND` 凭已注册 contract 放行（无 `sema.compile_check`，且释放后进入 body 扫描其中 `assert` 等封口节点）；`GENERIC_VARIANT` 在 statement root 发 route-not-ready blocker 且说明缺少 lowering route；同一 ForStatement anchor 已有 upstream error 时不补发同级 `sema.compile_check`
  - lambda compile gate：`sig.connect(func(): ...)` 直接实参放行（无 `compile_check`/unsupported）；已记录 lambda body 内 `preload`/`$Node`/`assert` 会被递归扫描并各自发 blocker；property-initializer / parameter-default 未记录 lambda 保持 fail-closed（上游 unsupported owner 持有，不补发 `compile_check`）；lambda body 内 `match` 仍由上游 `unsupported_binding_subtree` 持有，不被 `compile_check` 重复包一层
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
- `assert` 与 3 类显式拦截表达式（`ConditionalExpression`、`PreloadExpression`、`GetNodeExpression`）的真正 lowering/backend 支持仍待后续补齐；`ArrayExpression` / `DictionaryExpression`、`TypeTestExpression` 与 `CastExpression` 已完成 shared semantic、CFG/body lowering 与 backend 闭环；`for` 已注册 route 的 CFG/lowering 已落地，compile gate 为 route-aware policy（registry 已注册 route 放行，`OBJECT_CUSTOM` 等未注册 route 发 route-not-ready blocker）；已记录 `LambdaExpression` 的 shared semantic、`construct_lambda` lowering 与 C backend 已闭环，compile gate 按 published plan 放行并递归扫描 body

若未来需要为 LSP 单独呈现 compile-only blocker，正确方向仍是：

- 继续保留 shared `analyze(...)`
- 由 compile caller 额外选择是否运行 compile gate

而不是把 compile-only 阻断逻辑重新分散回上游每个 analyzer。
