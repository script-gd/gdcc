# FrontendChainBinding / ExprType 实现说明

> 本文档作为 `FrontendChainBindingAnalyzer`、`FrontendExprTypeAnalyzer` 及其共享 body-phase support 的长期事实源，定义当前 phase 顺序、side-table / diagnostic owner 边界、局部 chain reduction 架构、已冻结的 published contract，以及后续工程必须遵守的 fail-closed 边界。本文档替代旧的规划性文档与验收流水账，不再保留进度记录或阶段日志。

## 文档状态

- 状态：事实源维护中（`resolvedMembers()` / `resolvedCalls()` / `expressionTypes()`、shared expression semantic support、class property initializer support island、subscript / assignment typed contract、`:=` 最小回填与 expr-owned diagnostics 已落地）
- 更新时间：2026-03-19
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `diagnostic_manager.md`
  - `frontend_top_binding_analyzer_implementation.md`
  - `frontend_variable_analyzer_implementation.md`
  - `frontend_visible_value_resolver_implementation.md`
  - `scope_analyzer_implementation.md`
  - `scope_type_resolver_implementation.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/gdcc_type_system.md`
- 明确非目标：
  - 不引入 whole-module fixpoint，不把 body 语义改造成多轮全局收敛
  - 不新增新的全局 side table，也不让已有 side table 互相越权
  - 不把 `FrontendBinding` 重塑为 usage-aware 模型
  - 不在这里转正 parameter default、lambda、`for`、`match`、block-local `const`、class constant 的正式 body 语义
  - 不在这里扩张 keyed builtin、numeric promotion、`StringName` / `String` 互转、`null -> Object` 等更宽隐式兼容

---

## 1. 当前职责与集成位置

### 1.1 主链路位置

当前 `FrontendSemanticAnalyzer` 的稳定顺序是：

1. `FrontendClassSkeletonBuilder.build(...)`
2. `analysisData.updateModuleSkeleton(...)`
3. `analysisData.updateDiagnostics(...)`
4. `FrontendScopeAnalyzer.analyze(...)`
5. `analysisData.updateDiagnostics(...)`
6. `FrontendVariableAnalyzer.analyze(...)`
7. `analysisData.updateDiagnostics(...)`
8. `FrontendTopBindingAnalyzer.analyze(...)`
9. `analysisData.updateDiagnostics(...)`
10. `FrontendChainBindingAnalyzer.analyze(...)`
11. `analysisData.updateDiagnostics(...)`
12. `FrontendExprTypeAnalyzer.analyze(...)`
13. `analysisData.updateDiagnostics(...)`

这意味着：

- `FrontendChainBindingAnalyzer` 只运行在 skeleton、scope graph、variable inventory 与 `symbolBindings()` 已发布之后
- `FrontendExprTypeAnalyzer` 只运行在 `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()` 已发布之后
- body phase 仍保持“先发布 member/call，再发布 expression type”的顶层边界，不回头重开更早 phase

### 1.2 当前 owner 边界

当前 frontend body-phase 的 owner 边界已经冻结为：

- `FrontendTopBindingAnalyzer`
  - 只发布 `symbolBindings()`
  - 负责 bare `TYPE_META` ordinary-value misuse 的首条 `sema.binding`
  - 负责 bare identifier 在 value namespace / function namespace / type-meta namespace 之间的最外层分流
- `FrontendChainBindingAnalyzer`
  - 只发布 `resolvedMembers()` 与 `resolvedCalls()`
  - 负责 `sema.member_resolution`、`sema.call_resolution`、`sema.deferred_chain_resolution`、`sema.unsupported_chain_route`
  - 负责链式 route 级别的恢复与 suffix 封口
- `FrontendExprTypeAnalyzer`
  - 只发布 `expressionTypes()`
  - 负责 `sema.expression_resolution`、`sema.deferred_expression_resolution`、`sema.unsupported_expression_route`、`sema.discarded_expression`
  - 负责 expression-only 路径的恢复、statement warning 与 `:=` 最小回填

单一 owner 约束同样冻结为：

- 若同一根源错误已经由 top binding 或 chain binding 发出 diagnostic，expr analyzer 只能保留 published status，不得再补第二条同级错误

---

## 2. 当前共享架构

### 2.1 整体分层、局部交替

当前 body-phase 架构保持以下两层规则：

- 顶层继续 phase 化，不做 whole-module 多轮迭代
- 单条链式表达式内部按从左到右局部交替推进 binding 与类型

局部 reduction 的稳定流程为：

1. 解析链头 receiver
2. 基于当前 receiver type 解析当前 step 的 member / call / static access
3. 产出当前 step 的 published member / call 结果与下一层 receiver state
4. 若后缀仍需要 exact receiver，则继续下一 step
5. 若命中 `BLOCKED` / `DEFERRED` / `DYNAMIC` / `FAILED` / `UNSUPPORTED`，按对应合同停止 exact suffix 或切换到动态/封口路径

当前只允许局部有界重试：

- `finalizeWindow` 只服务当前 step 自己的依赖补全
- downstream suffix 不能反向触发 upstream step 重新求值
- 正式落定后的 `DEFERRED` / `FAILED` / `UNSUPPORTED` 结果不会被后续节点倒逼改写

### 2.2 共享 helper 分工

当前 shared body-phase support 已冻结为：

- `FrontendChainReductionFacade`
  - 负责 analyzer 侧的 attribute-chain 缓存、cache hit/miss 协调与 `FrontendChainReductionHelper` 调用编排
- `FrontendChainReductionHelper`
  - 负责单条链的 reduction trace、route 分流、step 级状态计算与 suffix 传播
- `FrontendChainHeadReceiverSupport`
  - 负责 `self`、literal、identifier、nested attribute、callable head 等链头 receiver 解析
- `FrontendChainStatusBridge`
  - 负责 `ReceiverState`、`ExpressionTypeResult`、`FrontendExpressionType`、`FrontendResolvedMember`、`FrontendResolvedCall` 之间的状态对象转换
  - 当前这是唯一允许维护 status/published-type 映射的桥接点
- `FrontendExpressionSemanticSupport`
  - 负责 analyzer-neutral 的局部表达式语义
  - 当前覆盖 literal、`self`、identifier、bare call、assignment、subscript、剩余显式 deferred 节点
  - 自身不发布 side table、不发 diagnostic、不遍历 statement tree
- `FrontendSubscriptSemanticSupport`
  - 负责 shared subscript/container typing contract
- `FrontendAssignmentSemanticSupport`
  - 负责 assignment-target 解析、writable / assignability 判定、statement-root / value-required 分流
- `PropertyDefAccessSupport`
  - 负责 scope publication 与 assignment typing 共用的 property writable 元数据解释

当前工程反思已经收敛成一条硬约束：

- analyzer 自己只保留 publication、diagnostic owner 与 statement policy
- 局部表达式语义与状态桥接必须留在 shared support 中作为单一真源，不能再在 chain / expr analyzer 内各自维护一套漂移实现

---

## 3. Published Contract

### 3.1 已发布 side table

当前 frontend body 语义稳定发布以下三张表：

- `resolvedMembers()`
- `resolvedCalls()`
- `expressionTypes()`

配套约束为：

- `FrontendChainBindingAnalyzer` 不写 `expressionTypes()`
- `FrontendExprTypeAnalyzer` 不改写 `resolvedMembers()` / `resolvedCalls()`
- `FrontendTopBindingAnalyzer` 继续不解析尾部成员、尾部调用或 assignment target
- class property `var` initializer subtree 已进入这三张表的正式 published support surface；class constant initializer 仍不属于该支持面
- 但这张 published support surface 的稳定含义只保证 subtree facts 可发布，不自动承诺同 class 非静态依赖已属于 MVP 正式语义面

`FrontendResolvedMember` 当前至少稳定承载以下维度：

- member 名称
- `FrontendBindingKind`
- `FrontendMemberResolutionStatus`
- `FrontendReceiverKind`
- owner / receiver type / result type / declaration metadata / detail reason

`FrontendResolvedCall` 当前至少稳定承载以下维度：

- callable 名称
- `FrontendCallResolutionKind`
- `FrontendCallResolutionStatus`
- `FrontendReceiverKind`
- owner / receiver type / return type / argument type snapshot / declaration metadata / detail reason

### 3.2 状态语义

当前 body-phase 的 published status 固定为：

- `RESOLVED`
  - 已命中稳定 target
  - 有稳定 published type，可继续 exact downstream typing
- `BLOCKED`
  - 命中真实 winner，但当前上下文禁止消费
  - 仍保留 winner 与 shadowing 语义，不能退化成普通 miss
- `DEFERRED`
  - route 属于正式支持面，但当前缺少冻结好的前置事实
  - 不是语义失败，也不是 feature boundary
- `DYNAMIC`
  - analyzer 已明确决定走 runtime-dynamic 路由
  - 不是暂缓，也不是普通失败
- `FAILED`
  - 支持域内已得到稳定负结论
- `UNSUPPORTED`
  - analyzer 已识别出 route/source，但它在当前合同外，必须 fail-closed

禁止事项同样冻结为：

- 不得把 `BLOCKED` / `DEFERRED` / `DYNAMIC` / `FAILED` / `UNSUPPORTED` 重新压扁成 `unresolved`
- 不得把 deferred/unsupported/boundary 命中伪装成普通 `NOT_FOUND`
- 不得让 suffix 在上游已失效时继续制造级联 miss 诊断

### 3.3 `expressionTypes()` 的发布规则

`FrontendExpressionType` 的当前语义是“downstream 可消费的 published expression fact”，规则固定为：

- `RESOLVED`
  - 发布稳定 concrete type
- `BLOCKED`
  - 默认保留 winner type 与 blocked provenance
  - 但若当前节点只是 echo 上游 blocked suffix，则必须保留 `BLOCKED` 并移除伪造的精确 published type
- `DYNAMIC`
  - 发布 `Variant`
- `DEFERRED` / `FAILED` / `UNSUPPORTED`
  - 不发布 concrete type，只保留状态与 detail reason

`rootOwnsOutcome` 也是正式合同的一部分：

- 它区分“当前 root 自己产生的非成功结果”和“从依赖上传播上来的结果”
- expr analyzer 仅对 root-owned 的 deferred/failed/unsupported 结果负责 expr-owned diagnostic

### 3.4 bare callable 与 bare `TYPE_META`

当前 bare identifier 的稳定合同为：

- value-position 分流固定为：
  - ordinary value winner
  - function namespace winner
  - bare `TYPE_META` ordinary-value misuse
  - generic unknown miss
- bare function-like symbol 在 value position 会 materialize 为 `Callable`
- bare `TYPE_META` ordinary-value misuse 会保留真实 `TYPE_META` binding，并在 expression typing 中保留 `FAILED` provenance

当前 route-head-only `TYPE_META` 规则同样冻结为：

- 作为合法 static-route head 使用的 bare `TYPE_META`，例如 `Worker.build()`、`EnumType.VALUE`
- 只参与当前 chain reduction，不作为 ordinary value expression 写入 `expressionTypes()`
- 这样可以避免 static-route head 污染普通 `expressionTypes()` 消费者与 `:=` backfill

---

## 4. 当前稳定支持面

### 4.1 链式 member / call 语义

当前正式支持的链式 route 至少包括：

- instance receiver property
- instance receiver signal
- instance receiver method call
- `TYPE_META` receiver static method call
- constructor route
- static load route：
  - `EnumType.VALUE`
  - builtin constant
  - engine integer constant
- method-as-value route：
  - `obj.method`
  - `Type.static_func`
- callable chain head：
  - `foo.call(...)`
  - `foo.bind(...)`
  - `Type.func.bind(...)`

这些 route 当前不仅可出现在 executable body，也可出现在 class property `var` initializer subtree；该支持岛会继承 property 自身的 static/instance restriction，但不会把整个 class body 打开成 executable region。

需要补充的稳定 MVP 合同是：

- property initializer subtree 的 published support 主要服务于可发布事实，而不是宣称“当前类实例成员初始化语义已完成”
- 同 class non-static property / method / signal / `self` 依赖不属于当前 MVP 的正式支持面
- 当前已由 T0.5 把这类依赖收口为 explicit boundary：
  - bare identifier / bare callee / `self` 由 top-binding owner 封口并向 expr typing 传播 `BLOCKED`
  - chain suffix 与 same-class type-meta route 中首个非法依赖由 chain-binding owner 封口并向 expr typing 传播 `UNSUPPORTED`

静态访问与构造器的分流原则已经冻结为：

- static method、constructor、static load 是三条独立 route
- 不能把它们伪装成普通 instance property / instance method lookup
- instance receiver 命中 static method 时，必须保留 resolved call，并单独发调用方式 diagnostic
- property initializer 若在 static restriction 下命中 instance-only member / method，首个依赖 step 仍需发布带精确 receiver metadata 的 `BLOCKED` member/call；只有更后面的 suffix 才降级为 upstream-blocked 传播
- 对 instance property initializer 中“命中当前 class 非静态成员”的情况，不应把上述 static restriction 规则误读成合法支持面的对称扩张；该路径在 MVP 中需要单独 fail-closed

### 4.2 表达式类型

当前 `FrontendExprTypeAnalyzer` 已正式覆盖：

- literal
- `self`
- identifier
- bare call
- attribute chain
- plain subscript
- attribute-subscript
- assignment

上述表达式类型当前同时覆盖：

- executable body 中的正式支持 subtree
- class property `var` initializer subtree

但 property initializer subtree 的稳定边界补充为：

- published `expressionTypes()` 只说明该表达式树被访问并获得了可下游消费的状态
- 它不代表同 class 非静态依赖已经被认定为合法的 member-initializer expression contract
- property `:=` 也不会因为这里能发布 RHS type，就自动获得 property-side inference/backfill

同时保持以下边界不变：

- class constant initializer 仍不进入正式 body-phase 支持面
- property initializer 只开放表达式子树，不引入 class-body `:=` backfill 或新的 executable scope
- 同 class 非静态依赖在 MVP 中仍需显式封口；不要把 property initializer 的 published subtree support 误写成完整实例初始化语义
- 这里的 published support surface 只表示 side-table 可发布；它不自动承诺同 class non-static property / method / signal / `self` 已在 property initializer 中具备稳定语义
- 当前 MVP 已通过 T0.5 显式收口：property initializer 不支持访问同 class 下的 non-static 内容；这些路径必须 fail-closed，而不是继续假装存在 declaration-order / default-state / cycle-aware 语义

当前 expression-only 恢复出口也已冻结：

- generic side-table-only 状态已被清理掉
- bare call、assignment、subscript、generic deferred expression 至少会走“expr-owned diagnostic”或“明确复用 upstream diagnostic”二选一
- discarded-expression policy 已区分 ordinary discarded value、resolved `void` call、assignment-success root

### 4.3 subscript / container typing

当前 shared subscript contract 只正式支持 container family：

- `Array[T]`
- `Dictionary[K, V]`
- packed array family

稳定规则为：

- receiver 为上述 `GdContainerType`
  - 使用 `ClassRegistry.checkAssignable(...)` 做 strict key/index 校验
  - 成功后发布 element/value type
- receiver 为 `Variant`
  - 发布 `DYNAMIC(Variant)`
- receiver 为 keyed builtin 或其他 keyed metadata，但不属于当前 container-family contract
  - 显式 `UNSUPPORTED`
- receiver 不是 container
  - 显式 `FAILED`

`AttributeSubscriptStep` 当前已经进入真实 chain reduction：

- 它不再 hardcode unsupported
- 可以继续 exact suffix
- 也能作为 chain-local dependency 的一等节点支撑 outer call exact resolution

### 4.4 assignment

当前 assignment contract 已冻结为：

- assignment-success root 发布 `RESOLVED(void)`
- statement-position 的 assignment-success root 不发 discarded warning
- value-required 的 nested assignment 会 fail-closed
- compound assignment 当前显式 `UNSUPPORTED`

当前正式支持的 assignment target 为：

- bare identifier
- attribute property
- plain subscript
- attribute-subscript

writable / compatibility 规则为：

- bare identifier property 与 attribute-property 必须共用同一 property writable 解释
- `ScopeValue.writable` 只表达 bare identifier direct-write contract
- `PropertyDefAccessSupport` 是唯一允许解释 engine/builtin property writable 元数据的 shared helper
- `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)` 只负责 concrete slot compatibility
- exact `Variant` slot 允许任意来源类型
- `DYNAMIC` target 的 runtime-open 语义只允许保留在 assignment helper 内部
- 其余 compatibility 继续回退 `ClassRegistry.checkAssignable(...)`

当前仍需明确保留的一条边界是：

- `obj.prop[i] = value` 目前按“property value 上的 element mutation”建模
- getter-only property 是否额外阻断该 aliasing route 尚未转正，后续若收口 property/container mutation 语义，必须统一处理，不能在 assignment helper 内临时加特判

### 4.5 `:=` 最小回填

当前 `:=` 的稳定合同为：

- variable inventory 阶段仍先按 `Variant` 写入局部变量类型
- `FrontendExprTypeAnalyzer` 只在 RHS 发布结果为 `RESOLVED` 或 `DYNAMIC` 时执行局部变量类型回填
- `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 不会回填声明类型
- backfill 只消费已发布的 RHS typing，不回头修改 earlier phase 的规则

同时明确：

- 这条 `:=` 回填合同当前只覆盖 block-local slot
- class property metadata 不会因为 expr-typing 而变成 inferred property type
- 因此任何后续 property compatibility phase 若直接消费 skeleton/property metadata，都必须先收口到 explicit declared property；否则 inferred property 会因为 target 仍是 exact `Variant` 而让 typed gate 退化成空操作

### 4.6 显式 deferred / unsupported 边界

当前仍显式封口的 body-phase 边界包括：

- parameter default
- lambda subtree
- `for` subtree
- `match` subtree
- block-local `const`
- class constant
- scope-local 手动 `type-meta`

当前 remaining explicit-deferred expression set 固定为：

- `BinaryExpression`
- `UnaryExpression`
- `ConditionalExpression`
- `ArrayExpression`
- `DictionaryExpression`
- `AwaitExpression`
- `PreloadExpression`
- `GetNodeExpression`
- `CastExpression`
- `TypeTestExpression`
- `PatternBindingExpression`

额外规则为：

- 每类 remaining deferred node 都必须带单独命名的 deferred reason
- `UnknownExpression` 当前显式视为 parser-recovery `UNSUPPORTED`，不再伪装成 generic deferred fallback

---

## 5. 诊断与恢复合同

当前 body phase 继续遵守 frontend 统一恢复约定：

- 对普通源码错误优先发 diagnostic
- 对无法稳定产生产物的 subtree 采用 skip / deferred / unsupported sealing
- 不因单个坏表达式打断整个 module

链式 suffix 的恢复规则已冻结为：

- `BLOCKED`
  - 保留 winner，并由 owner 发 restriction diagnostic
  - 若 blocked head 仍保留 exact receiver metadata，首个受影响 step 必须继续发布 concrete blocked member/call，而不是立刻压扁成 generic upstream miss
- `DEFERRED`
  - 优先锚定到第一个无法继续 exact 解析的 suffix root
  - 同一条 deferred suffix 当前只发 1 条恢复 warning
- `DYNAMIC`
  - 后续若继续分析，只能沿 runtime-dynamic / `Variant` 路由传播
- `FAILED`
  - 阻断后续 exact suffix，不再伪装成普通 miss
- `UNSUPPORTED`
  - 优先锚定 feature-boundary root，一次性封口
  - 当前 unsupported feature boundary 统一发 error，而不是 warning

expr-owned diagnostics 的硬约束是：

- 只有当前 root 自己拥有结果时，expr analyzer 才补 expr-owned diagnostic
- 若问题已由 top binding / chain binding 报出，下游只保留 status，不补第二条同级 error

---

## 6. 工程约束与反思

当前已经验证有效、并要求后续继续遵守的工程反思包括：

- `FrontendBinding` 仍是 usage-agnostic 模型，assignment target 解析必须继续走 dedicated model，而不是从 `bindingKind` 反推写语义
- status/published-type 映射必须集中在 `FrontendChainStatusBridge`，不能让 chain / expr analyzer 再各自复制一套桥接逻辑
- 局部表达式语义必须集中在 `FrontendExpressionSemanticSupport`、`FrontendSubscriptSemanticSupport`、`FrontendAssignmentSemanticSupport`，不能回退到 analyzer 内各写一份局部特判
- route-head-only `TYPE_META` 继续只作为 static-route base 使用，不进入 ordinary expression publication
- keyed builtin subscript、wide implicit assignment conversion、class constant、scope-local type alias 等边界必须继续显式 `UNSUPPORTED` / deferred；不要为了“继续分析”把 feature boundary 静默降级成普通 miss
- property writable metadata 必须继续统一复用 `PropertyDefAccessSupport`；不要在 scope publication、assignment helper、其他 frontend 路径里各写一套 engine/builtin property writable 解释

---

## 7. 测试锚点

后续若调整本模块，至少要继续锚定以下测试事实：

- `FrontendAnalysisData` 对 `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` 的 stable-reference 与 stale-entry-clearing 合同
- `FrontendSemanticAnalyzerFrameworkTest` 对 phase 顺序、diagnostic boundary 与 side-table handoff 的覆盖
- `FrontendChainHeadReceiverSupportTest`
- `FrontendChainReductionHelperTest`
- `FrontendChainStatusBridgeTest`
- `FrontendExpressionSemanticSupportTest`
- `FrontendSubscriptSemanticSupportTest`
- `FrontendAssignmentSemanticSupportTest`
- `FrontendChainBindingAnalyzerTest`
- `FrontendExprTypeAnalyzerTest`

focused case 至少要继续覆盖：

- instance property / signal / method
- static method / constructor / static load
- bare callable value / bare call / callable chain head
- bare `TYPE_META` misuse 与合法 static-route head 分流
- subscript container happy path、`Variant` dynamic、keyed builtin unsupported、non-container failure
- assignment-success `RESOLVED(void)`、illegal lvalue、readonly property、exact `Variant` slot、value-required nested assignment fail-closed
- blocked / deferred / dynamic / failed / unsupported 的 suffix 传播与 diagnostic 去重

---

## 8. 当前实现结论

当前 frontend chain binding / expr type 工程已进入长期维护状态，后续工程若继续扩张表达式覆盖面，应以本文档记录的 owner 边界、shared helper 真源、published status 合同与 fail-closed 边界为准，而不是恢复旧的阶段性实现假设。
