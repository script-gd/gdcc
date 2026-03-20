# FrontendVisibleValueResolver 实现说明

> 本文档作为 `FrontendVisibleValueResolver` 的长期事实源，定义当前已冻结的职责边界、支持域、结果合同、shared `Scope` / variable inventory 分工，以及后续工程必须遵守的 guard rail。本文档替代旧的实施计划与进度记录，不再保留阶段清单、验收流水账或已完成任务日志。

## 文档状态

- 状态：事实源维护中（request/result 合同、declaration-order 过滤、initializer 自引用过滤、deferred boundary 封口、current-scope fail-closed hardening、shared support matrix 与核心单元测试已落地）
- 更新时间：2026-03-16
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `frontend_rules.md`
  - `frontend_variable_analyzer_implementation.md`
  - `frontend_top_binding_analyzer_implementation.md`
  - `scope_analyzer_implementation.md`
  - `scope_architecture_refactor_plan.md`
  - `diagnostic_manager.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
- 明确非目标：
  - 不修改 shared `Scope.resolveValue(...)` 协议
  - 不让 unsupported 子域伪装成正常 `NOT_FOUND`
  - 不在 resolver 内直接生产 diagnostics 或写入 `symbolBindings()`
  - 不在当前 frontend 中为 parameter default、lambda、`for`、`match`、block-local `const` 提供正式 local inventory 解析

---

## 1. 目标与职责边界

`FrontendVisibleValueResolver` 当前职责已冻结为：

- 为 bare-name value use-site 提供 frontend-visible lookup
- 在 shared `Scope` 的 allowed / blocked / not-found 语义之上补充 declaration-order 可见性
- 抑制 local initializer 自引用
- 保留被过滤命中的 provenance，供 binder / diagnostics 使用
- 对 variable inventory 尚未发布的语义域返回显式 `DEFERRED_UNSUPPORTED`

它不负责：

- 构建 scope graph
- 向 `CallableScope` / `BlockScope` 发布 parameter 或 local inventory
- 生产 diagnostics
- 写入 `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`
- 成员访问、函数命名空间或类型命名空间解析
- 表达式类型推断

保留这个 resolver 的原因已经冻结：shared `Scope` 只能表达“哪一层命中”和“restriction 下是否 allowed / blocked”，不能表达 declaration-order、initializer 自引用，以及“后面会出现同名 local declaration”的 provenance。Godot 的 `CONFUSABLE_LOCAL_USAGE` 一类语义依赖这些中间事实，因此 resolver 不能退化成一个只回传 `ScopeLookupResult<ScopeValue>` 的薄包装层。

---

## 2. 上下游分工

### 2.1 与 `FrontendVariableAnalyzer` 的分工

- `FrontendVariableAnalyzer` 负责把 parameter / supported ordinary local inventory 发布到 `CallableScope` / `BlockScope`
- inventory 发布范围由 `FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(...)` 统一定义
- 当前允许发布 callable-local value inventory 的 `BlockScopeKind` 只有：
  - `FUNCTION_BODY`
  - `CONSTRUCTOR_BODY`
  - `BLOCK_STATEMENT`
  - `IF_BODY`
  - `ELIF_BODY`
  - `ELSE_BODY`
  - `WHILE_BODY`
- resolver 与 variable analyzer 必须共用同一 support matrix，不能各自维护名单

### 2.2 与 shared `Scope` 的分工

shared `Scope` 继续负责：

- lexical inventory
- restriction-aware 的 `FOUND_ALLOWED` / `FOUND_BLOCKED` / `NOT_FOUND`
- `ClassScope` / `ClassRegistry` 上的共享 lookup 事实
- shared metadata 失真时抛出的 `ScopeLookupException`

`FrontendVisibleValueResolver` 额外负责：

- declaration-order 过滤
- initializer 自引用过滤
- filtered hit provenance 保留
- deferred / unsupported 域显式封口

---

## 3. 支持域与封口域

### 3.1 当前正常解析域

resolver 当前只对 `EXECUTABLE_BODY` 域提供正常 lookup，并要求同时满足：

- request 的 `domain == EXECUTABLE_BODY`
- use-site 能在 `analysisData.scopesByAst()` 中找到 current scope
- current scope 自身属于已发布 callable-local value inventory 的 executable scope

这里的最后一条是硬约束，不允许再退回“祖先链上存在某个 supported block 就放行”的宽松判定。

### 3.2 当前显式封口域

以下位置当前都必须返回 `DEFERRED_UNSUPPORTED + deferredBoundary(...)`：

- parameter default-value expression
- lambda body
- block-local `const` initializer subtree
- `for` iterator type / iterable / body
- `match` pattern / guard / section body
- 任何缺少稳定 scope 记录的 subtree
- 任何 current scope 自身就是未发布 inventory 的 scope，例如：
  - `BlockScopeKind.FOR_BODY`
  - `BlockScopeKind.MATCH_SECTION_BODY`
  - `BlockScopeKind.LAMBDA_BODY`
  - `CallableScopeKind.LAMBDA_EXPRESSION`

这些域当前不能静默回退到 outer local / class property / global，也不能伪装成普通 `NOT_FOUND`。

### 3.3 `ClassScope` / `ClassRegistry` 不是封口域

需要区分：

- callable-local declaration-order / publish-boundary 不完整，属于 resolver 的封口问题
- `ClassScope` / `ClassRegistry` 仍是 shared lookup 的正式协议

因此 resolver 只在离开 callable-local 层之后继续委托 shared `resolveValue(...)`；但这一步不会让 deferred 子域自动变成“正常可解析域”。

---

## 4. 冻结输入与结果合同

### 4.1 请求对象

`FrontendVisibleValueResolveRequest` 当前冻结为：

- `name`
- `useSite`
- `restriction`
- `domain`

`domain` 必须由调用方显式指定，不能要求 resolver 仅从 AST 形状猜测“这次 lookup 原本想工作在哪个语义域”。

### 4.2 结果对象

`FrontendVisibleValueResolution` 当前冻结为：

- `status`
- `visibleValue`
- `filteredHits`
- `deferredBoundary`

`status` 只表达四种结果：

- `FOUND_ALLOWED`
- `FOUND_BLOCKED`
- `NOT_FOUND`
- `DEFERRED_UNSUPPORTED`

### 4.3 `filteredHits`

`filteredHits` 是外部合同的一部分，表达“哪些同名命中存在于 lookup 链上，但由于 frontend 可见性规则被过滤掉了”。

每条 `FrontendFilteredValueHit` 至少保留：

- 被过滤的 `ScopeValue`
- 原始命中的 lexical `Scope`
- 过滤原因

当前原因最少包括：

- `DECLARATION_AFTER_USE_SITE`
- `SELF_REFERENCE_IN_INITIALIZER`

`primaryFilteredHit()` 只是当前消费者的便捷视图，不是“结果永远只有一条 filtered hit”的长期承诺。后续若某些 deferred 域转正，结果模型仍必须允许同时保留多条有语义价值的 filtered 命中。

### 4.4 `deferredBoundary`

`DEFERRED_UNSUPPORTED` 必须携带 `FrontendVisibleValueDeferredBoundary(domain, reason)`。

当前 `domain` 至少区分：

- `EXECUTABLE_BODY`
- `PARAMETER_DEFAULT`
- `LAMBDA_SUBTREE`
- `BLOCK_LOCAL_CONST_SUBTREE`
- `FOR_SUBTREE`
- `MATCH_SUBTREE`
- `UNKNOWN_OR_SKIPPED_SUBTREE`

当前 `reason` 至少区分：

- `UNSUPPORTED_DOMAIN`
- `MISSING_SCOPE_OR_SKIPPED_SUBTREE`
- `VARIABLE_INVENTORY_NOT_PUBLISHED`

---

## 5. 冻结解析规则

`FrontendVisibleValueResolver.resolve(request)` 当前行为冻结为：

1. 若 `request.domain != EXECUTABLE_BODY`，直接返回 `DEFERRED_UNSUPPORTED(domain = request.domain, reason = UNSUPPORTED_DOMAIN)`
2. 先做 AST boundary 检测，识别 parameter default、lambda body、block-local `const` initializer、`for`、`match` 等共享外层 scope 的 unsupported 子树
3. 若 use-site 缺少 current scope 记录，返回 `DEFERRED_UNSUPPORTED`
4. 对 current scope 执行 fail-closed 校验；若 current scope 自身没有已发布 inventory，也返回 `DEFERRED_UNSUPPORTED`
5. 在继续 outer fallback 之前，检查 enclosing supported block 中是否已经出现同名且当前可见的 block-local `const`；若存在，也必须封口为 `BLOCK_LOCAL_CONST_SUBTREE`
6. 只有 AST boundary 与 current-scope gate 都放行后，才对 `BlockScope` / `CallableScope` 做逐层 lookup
7. 当前层若 `FOUND_BLOCKED`，直接返回 `FOUND_BLOCKED`，不能降级成 `NOT_FOUND`
8. 当前层若 `FOUND_ALLOWED` 但 declaration 当前不可见，则追加 `filteredHit` 后继续向外层查找
9. 当前层若 `NOT_FOUND`，继续向 lexical parent 查找
10. 离开 callable-local 层后，委托 shared `Scope.resolveValue(...)`
11. 若 shared lookup 抛出 `ScopeLookupException`，必须原样传播

### 5.1 declaration-order 规则

- parameter 只在 executable callable body 内按“始终可见”处理
- ordinary local `var` 只有在 declaration 结束位置早于 use-site 起始位置时才视为可见
- class property / signal / class const / global enum / singleton 等 non-callable-local binding 不受 statement-order 过滤影响

### 5.2 initializer 自引用

对 `var x = x` 右侧 use-site：

- 当前 declaration 不能视为可见
- 结果必须把该命中记入 `filteredHits`
- 过滤原因必须是 `SELF_REFERENCE_IN_INITIALIZER`

---

## 6. 工程 guard rail 与反思

### 6.1 正确性不能押注 AST 边界永远完整

这次 hardening 之后，resolver 的正确性冻结为“双重封口”：

- AST boundary 检测负责拦截共享外层 supported scope 的 unsupported 片段
- current-scope fail-closed 负责拦截已经由 scope phase 正式发布、但 inventory 仍未发布的 unsupported scope

后续工程不得删除任意一层并把正确性完全押给另一层。

### 6.2 support matrix 必须共享

`FrontendVariableAnalyzer` 发布 inventory 的范围，与 resolver 允许正常 lookup 的范围，本质上是同一份事实。该事实现在由 `FrontendExecutableInventorySupport` 统一承载；后续若扩展支持域，必须同时评估：

- variable inventory 是否真的发布
- resolver 是否真的能在该域内给出不误导的绑定结果
- 对应测试是否已补齐

### 6.3 缺 scope 与 deferred 域都不能降级成 miss

以下情况都不能再被解释成普通 `NOT_FOUND`：

- current scope 缺失
- unsupported subtree 被 AST boundary 命中
- unsupported current scope 被 fail-closed 命中
- 已有同名 block-local `const` 可见，但当前 inventory 尚未发布

这是为了避免 binder 把“当前不支持”误消费成“当前没有这个名字”。

---

## 7. 测试锚点

后续若调整 resolver 行为，至少要继续锚定以下事实：

- parameter / ordinary local / class property 三类基本路径的正向解析
- future local / initializer self-reference 会进入 `filteredHits`，且不会误命中当前 declaration
- `FOUND_BLOCKED` 不会被错误降级成 `NOT_FOUND`
- parameter default、lambda、block-local `const`、`for`、`match`、missing-scope 都返回 `DEFERRED_UNSUPPORTED`
- 真实 AST deferred 场景与 synthetic current-scope remap 场景都已覆盖，证明即使 AST boundary 漏判，resolver 仍按未发布 inventory fail-closed
- shared `ClassScope` / `ClassRegistry` 抛出的 `ScopeLookupException` 会原样传播

---

## 8. 当前实现状态

当前 `FrontendVisibleValueResolver` 已在 `frontend.sema.resolver` 中独立落地，binder 后续若需要消费 bare-name value 可见性，必须显式调用本合同，而不是绕过 resolver 直接在 shared `Scope` 上推断 declaration-order 语义。
