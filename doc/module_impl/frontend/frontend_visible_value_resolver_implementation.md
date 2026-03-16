# FrontendVisibleValueResolver 行为、结果模型与接入计划

> 本文档定义 `FrontendVisibleValueResolver` 的职责边界、结果模型、有效域/无效域、可见性规则，以及它与 `FrontendVariableAnalyzer` / shared `Scope` 协议的分工。本文在重写后不再把 resolver 描述成一个只返回 `ScopeLookupResult<ScopeValue>` 的窄包装层，而是把它冻结为 frontend binder 专用的可见性事实源。

## 文档状态

- 性质：frontend binder 辅助设计 / 行为事实源 / 实施计划
- 更新时间：2026-03-16
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`

---

## 1. 背景

当前 frontend 已经具备并计划继续具备两类事实：

- `FrontendScopeAnalyzer` 负责发布 lexical/container scope graph
- `FrontendVariableAnalyzer` 负责把 parameter / local declaration inventory 写进 `CallableScope` / `BlockScope`

但现有 shared `Scope` 协议只表达：

- 哪一层“拥有这个名字”
- restriction 下当前层命中是否 allowed / blocked

它不表达：

- 这个 declaration 从源码哪个位置开始可见
- `var x = x` 右侧是否能看到正在声明的 `x`
- “当前先命中 outer/class/global，下面会被 local declaration 遮蔽”的中间事实

最后一类信息不是可有可无。Godot 当前 warning 体系里已经有两类直接依赖它的 warning：

- `CONFUSABLE_LOCAL_DECLARATION`
- `CONFUSABLE_LOCAL_USAGE`

相关事实源至少包括：

- `modules/gdscript/gdscript_warning.h`
- `modules/gdscript/tests/scripts/analyzer/warnings/confusable_local_usage.gd`
- `modules/gdscript/tests/scripts/analyzer/warnings/confusable_local_usage_initializer.gd`
- `modules/gdscript/tests/scripts/analyzer/warnings/confusable_local_usage_loop.gd`

这些测试说明，frontend 需要的不只是“最后解析到谁”，还需要“有哪些 declaration-order 过滤掉的中间命中”。因此本文明确不再推荐把 resolver 的外部合同收窄成单一 `ScopeLookupResult<ScopeValue>`。

---

## 2. 设计目标

`FrontendVisibleValueResolver` 的目标只有一个：

- 在不修改 shared `Scope` 协议的前提下，为 frontend use-site 解析补上 declaration-order 可见性语义，并保留后续 warning/diagnostic 所需的中间 provenance。

它不负责：

- 构建 scope graph
- 往 scope 中写 parameter/local binding
- 生产 diagnostics
- 写入 `symbolBindings()`
- 表达式类型推断
- member/call 解析
- function namespace 或 type-meta namespace 的解析

---

## 3. 与 no-shadowing 规则的关系

当前 frontend 额外冻结了一条约束：

- 同一 `FunctionDeclaration` / `ConstructorDeclaration` 内，不允许变量遮蔽同一 callable 中更早可见的 parameter / local / future capture。

这条规则由 `FrontendVariableAnalyzer` 负责检查、诊断并跳过非法 declaration。

因此 `FrontendVisibleValueResolver` 的前提是：

- scope 中已经只保留“合法写入”的 parameter/local inventory
- 它不需要再额外承担“识别非法 same-callable variable shadowing declaration”的职责

但即便如此，它仍然有必要存在。原因是：

- no-shadowing 只能消除“同一 callable 的变量遮蔽”
- 它不能解决“首次声明在 use-site 之后”的 declaration-order 问题
- 它也不能解决 initializer 内自引用问题
- 它更不能替 binder 保留“future local declaration 会在后面遮蔽当前绑定”这类 warning provenance

---

## 4. 有效域与明确无效域

本节是本次重写后最关键的新增内容之一。

### 4.1 当前有效域

当前 resolver 只对以下 use-site 保证有效：

- function body
- constructor body
- 普通嵌套 `Block`
- `if` / `elif` / `else` body
- `while` body

更准确地说：

- use-site 必须位于 variable inventory 已完成的 executable subtree 中
- use-site 自身必须能通过 `FrontendAnalysisData.scopesByAst()` 找到当前 lexical scope
- 若解析链最终回落到 `ClassScope` / `ClassRegistry`，这部分共享 lookup 仍然有效
- 就 resolver 的请求模型而言，上述位置当前都归入 `EXECUTABLE_BODY` 这一语义域
- current scope 自身必须属于“已发布 variable inventory 的 block-scope kind”集合，而不是只要求祖先链上某处存在 supported block

### 4.2 当前明确无效域

以下位置当前不得把本 resolver 当作“正常可用的绑定器”调用；若调用，必须返回 frontend-only 的 `DEFERRED_UNSUPPORTED` 结果，而不是静默回退到 outer scope 并假装成功：

- parameter default-value expression
- lambda parameter default subtree
- lambda body
- block-local `const` initializer subtree
- `for` iterator declaration / iterable / body
- `match` pattern / guard / section body
- 任何前序 phase 已跳过、当前没有稳定 scope 记录的 subtree

之所以要把这些位置写成显式无效域，是因为当前代码库已经有 scope graph，但还没有对应 binding inventory。单看 `scopesByAst` 会让这些位置“看起来像正常区域”，这是最危险的静默误判来源。

### 4.3 `ClassScope` / `ClassRegistry` 不是无效域

需要区分：

- “callable-local declaration-order 不完整”属于无效域问题
- `ClassScope` / `ClassRegistry` 的 shared lookup 仍是当前正式协议的一部分

也就是说：

- resolver 到达 `ClassScope` / `ClassRegistry` 后可以继续委托 shared `resolveValue(...)`
- 但这一步不会帮当前 deferred subtree 自动变得“可用”

---

## 5. 结果模型

### 5.1 不再使用窄 `ScopeLookupResult<ScopeValue>` 作为最终外部合同

本文冻结的设计结论是：

- `FrontendVisibleValueResolver` 的外部结果必须是 frontend-only richer result
- 不能再直接把 `ScopeLookupResult<ScopeValue>` 暴露成最终合同

原因：

- `ScopeLookupResult` 只能表达最终 allowed / blocked / not found
- 它无法表达 declaration-order 过滤掉的命中
- 它无法表达当前处在 deferred/unsupported 域
- 它无法给 future warning 生成保留 provenance

### 5.2 推荐结果对象

推荐结果对象至少承载以下信息：

```java
public record FrontendVisibleValueResolution(
        @NotNull FrontendVisibleValueStatus status,
        @Nullable ScopeValue visibleValue,
        @NotNull List<FrontendFilteredValueHit> filteredHits,
        @Nullable FrontendVisibleValueDeferredBoundary deferredBoundary
) {
    /// 当前 executable-body 消费者通常只关心最近的一条 filtered 命中。
    /// 该辅助视图不应被解释成“结果永远至多只有一条 filtered hit”。
    public @Nullable FrontendFilteredValueHit primaryFilteredHit() {
        return filteredHits.isEmpty() ? null : filteredHits.getFirst();
    }
}
```

其中：

- `filteredHits` 是外部合同；它表达“有哪些命中被 declaration-order 或 publish-boundary 过滤掉了”
- `primaryFilteredHit()` 只是当前消费者的便捷视图，不是长期唯一性承诺
- `deferredBoundary` 用于表达当前 use-site 落在哪个语义域、因何被封口；没有 deferred 时应为 `null`

推荐 `status` 至少包括：

- `FOUND_ALLOWED`
- `FOUND_BLOCKED`
- `NOT_FOUND`
- `DEFERRED_UNSUPPORTED`

这里的语义是：

- `FOUND_ALLOWED`：找到了当前真正可见的 binding
- `FOUND_BLOCKED`：找到了当前层 binding，但它在 shared restriction 下非法；这不是 miss
- `NOT_FOUND`：整个有效解析链都没有可见 binding，也没有 deferred/unsupported 域阻断
- `DEFERRED_UNSUPPORTED`：当前 use-site 位于本文明确声明的无效域，resolver 拒绝给出“看似成功”的绑定结果

### 5.3 `filteredHits` 的最小要求

`filteredHits` 至少应满足：

- 稳定顺序，推荐按 resolver 实际扫描到的近到远顺序保存，便于当前消费者把第一条视为 primary
- 被 declaration-order 过滤掉的 `ScopeValue`
- 命中的 lexical scope 层级
- 过滤原因

推荐原因枚举最少覆盖：

- `DECLARATION_AFTER_USE_SITE`
- `SELF_REFERENCE_IN_INITIALIZER`

这里不再把“只保留第一条 filtered hit”写成外部合同，原因是：

- 当前 executable-body MVP 在大多数场景下通常只会消费第一条 filtered 命中
- 但 future `for` / `match` / parameter default / lambda-capture 支持可能需要同时保留多条“被过滤但仍有语义价值”的候选
- 若今天把结果对象冻结成单字段，后续只能通过破坏性升级或额外旁路字段来补救

因此本文改为冻结：

- 当前实现可以只让 binder 读取 `primaryFilteredHit()`
- 但结果模型本身不能把唯一性编码成不变量

### 5.4 `DEFERRED_UNSUPPORTED` 的边界表达

`DEFERRED_UNSUPPORTED` 不应只返回一个裸 `deferredReason` 枚举，而应保留“语义域 + 原因”的组合。

推荐至少表达：

```java
public record FrontendVisibleValueDeferredBoundary(
        @NotNull FrontendVisibleValueDomain domain,
        @NotNull FrontendVisibleValueDeferredReason reason
) {
}
```

其中 `domain` 表达 use-site 所属语义域，当前至少应能区分：

- `EXECUTABLE_BODY`
- `PARAMETER_DEFAULT`
- `LAMBDA_SUBTREE`
- `BLOCK_LOCAL_CONST_SUBTREE`
- `FOR_SUBTREE`
- `MATCH_SUBTREE`
- `UNKNOWN_OR_SKIPPED_SUBTREE`

`reason` 则表达为什么当前不能给出正常绑定结果，推荐至少区分：

- `UNSUPPORTED_DOMAIN`
- `MISSING_SCOPE_OR_SKIPPED_SUBTREE`
- `VARIABLE_INVENTORY_NOT_PUBLISHED`

这一步的目标不是给用户直接出诊断，而是防止 binder 在无效域上把“当前不支持”误当成“正常 miss”，并避免未来只能从 AST 形状反推“这次解析原本想按哪个语义域工作”。

---

## 6. 冻结行为

### 6.1 请求对象与 `domain`

推荐把 resolver 的输入冻结为 request object，而不是继续把 `useSite` / `name` / future flags 以零散参数堆进 `resolve(...)`。

请求至少应包含：

- use-site AST
- 待解析名称
- 调用方已经知道的语义域 `domain`

当前阶段：

- binder 只需要为已支持的 executable body use-site 传入 `EXECUTABLE_BODY`
- 这不要求本阶段立即实现 `PARAMETER_DEFAULT` / `FOR_SUBTREE` / `MATCH_SUBTREE` 的正常绑定逻辑
- 但它可以避免未来为这些场景补支持时，再从 AST 位置和父节点形状里反推出“这次解析原本想按哪个域工作”

### 6.2 总体规则

`FrontendVisibleValueResolver.resolve(request)` 的行为冻结为：

1. 先读取 `request.domain`
2. 若 `request.domain != EXECUTABLE_BODY`，返回 `DEFERRED_UNSUPPORTED`，并填写 `deferredBoundary`
3. 若 `request.domain == EXECUTABLE_BODY`，再判断当前 use-site 是否位于本文定义的有效域
4. 若不在有效域，返回 `DEFERRED_UNSUPPORTED`
5. 若在有效域，从 `analysisData.scopesByAst().get(useSite)` 获取当前 lexical scope
6. 若 use-site 缺少 current scope 记录，返回 `DEFERRED_UNSUPPORTED`
7. 若 current scope 自身不是已发布 inventory 的合法 executable scope，也返回 `DEFERRED_UNSUPPORTED`
8. 这条 current-scope gate 必须独立存在，不能把正确性完全押注在 AST 边界识别永远完整
9. 只有在 AST 边界与 current-scope gate 都放行后，才对 `BlockScope` / `CallableScope` 使用“逐层 value lookup + declaration-order 过滤”
10. 对 `ClassScope` / `ClassRegistry` 使用现有 shared `Scope` 语义
11. 委托到 shared `Scope` 时若抛 `ScopeLookupException`，原样传播，不得降级成 `NOT_FOUND`

### 6.3 当前层命中的处理

对于 `BlockScope` / `CallableScope` 当前层命中的 `ScopeValue`：

- 若结果是 `FOUND_BLOCKED`：
  - 直接返回 `FOUND_BLOCKED`
  - 保留已有 `filteredHits` 信息，但不能把 blocked hit 降级成 `NOT_FOUND`
- 若结果是 `FOUND_ALLOWED`：
  - 若 declaration 当前已可见，则返回 `FOUND_ALLOWED`
  - 若 declaration 当前尚不可见：
    - 追加一条 `filteredHit` 到 `filteredHits`
    - 继续向 lexical parent 查找
- 若结果是 `NOT_FOUND`：
  - 继续向 lexical parent 查找

### 6.4 declaration 可见性规则

#### parameter

- `Parameter` 只在 function / constructor executable body 内按“始终可见”处理
- 参数默认值表达式不适用这条规则；那属于当前明确 deferred 的无效域

#### ordinary local `var`

- `VariableDeclaration` 只有在 declaration 结束位置早于 use-site 起始位置时才视为可见
- 推荐比较基准继续使用 `gdparser` 的 `Range`：
  - declaration -> `range().endByte()`
  - use-site -> `range().startByte()`

#### class/global bindings

- class property / signal / class const / singleton / global enum 等非 callable-local binding 不受 statement-order 过滤影响
- 它们继续按 shared `Scope` 的当前协议工作

### 6.5 initializer 自引用

对于：

```gdscript
func ping():
    var x = x
```

右侧 `x` 不应视为已经可见的当前 local declaration。

因此本文冻结：

- local declaration 的“开始可见点”不能取 declaration 的起始位置
- 必须至少晚于 declaration/value 初始化表达式内部的 use-site
- 用 declaration 结束位置做可见性判断可以满足这一要求

---

## 7. 与 shared `Scope` 协议的边界

### 7.1 shared `Scope` 继续负责什么

shared `Scope` 继续负责：

- lexical inventory / namespace / restriction
- `FOUND_ALLOWED` / `FOUND_BLOCKED` / `NOT_FOUND`
- `ClassScope` / `ClassRegistry` 上的共享 lookup 事实

### 7.2 `FrontendVisibleValueResolver` 额外负责什么

`FrontendVisibleValueResolver` 额外负责：

- statement-order 可见性
- initializer 自引用过滤
- future local / filtered declaration provenance 保留
- deferred/unsupported 域显式封口

### 7.3 本文明确不建议的方向

当前仍不建议：

- 给 `Scope.resolveValue(...)` 增加 `index`
- 给 `Scope.resolveValue(...)` 增加 frontend-specific `VisibilityFilter`

原因不是这些方案一定做不成，而是它们会把 frontend use-site 位置语义推进 shared scope protocol。

---

## 8. 异常传播与恢复边界

### 8.1 `ClassScope` / `ClassRegistry` 委托可能抛异常

当前代码库已经显式冻结了一个重要事实：

- `ClassScope.resolveValue(...)` / `resolveFunctions(...)` 在 missing super metadata 和 inheritance cycle 上会直接抛 `ScopeLookupException`

相关实现与测试至少包括：

- `src/main/java/dev/superice/gdcc/frontend/scope/ClassScope.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/ClassScopeResolutionTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/ClassScopeSignalResolutionTest.java`

### 8.2 resolver 不得吞掉 shared-scope 异常

这条行为必须显式写死：

- 当 `FrontendVisibleValueResolver` 委托到 `ClassScope` / `ClassRegistry` 时，若 shared scope 抛出 `ScopeLookupException`，resolver 必须原样传播
- 不得把它转换成 `NOT_FOUND`
- 不得把它转换成 `DEFERRED_UNSUPPORTED`

原因：

- 这类异常不是普通 frontend 用户错误
- 它们表示共享 metadata / inheritance walk 不变量已经损坏
- 把它们降级成 miss 会破坏当前仓库已经存在的 fail-fast 合同

### 8.3 缺少 use-site scope 记录时的处理

若 `analysisData.scopesByAst().get(useSite)` 缺失：

- 当前推荐返回 `DEFERRED_UNSUPPORTED`，并填写 `deferredBoundary(domain = EXECUTABLE_BODY, reason = MISSING_SCOPE_OR_SKIPPED_SUBTREE)`
- 不把它降级成 `NOT_FOUND`

原因：

- 在 frontend recovery 体系下，缺 scope 记录通常表示前序 phase 已跳过该 subtree
- 这里最危险的行为不是“报错”，而是“静默当作没有名字”

---

## 9. 场景示例

### 9.1 首次声明位于 use-site 之后

源码：

```gdscript
func ping():
    print(count)
    var count = 1
```

推荐结果：

- `status = NOT_FOUND`
- `visibleValue = null`
- `filteredHits` 包含 block 内的 local `count`，当前 `primaryFilteredHit()` 指向它

这样 binder 后续既能知道“现在没有可见 local”，也能知道“后面会出现 future local declaration”。

### 9.2 initializer 内不能看到正在声明的 local

源码：

```gdscript
func ping():
    var node = node
```

推荐结果：

- 右侧 `node` 不命中当前 declaration
- `primaryFilteredHit().reason = SELF_REFERENCE_IN_INITIALIZER`
- 若外层没有同名 binding，则 `status = NOT_FOUND`

### 9.3 future local 会在后面遮蔽 class property

源码：

```gdscript
var a = 1

func test():
    print(a)
    var a = 2
```

推荐结果：

- `status = FOUND_ALLOWED`
- `visibleValue` 指向 class property `a`
- `filteredHits` 包含后面的 local `a`，当前 `primaryFilteredHit()` 指向它

这正是后续还原 Godot `CONFUSABLE_LOCAL_USAGE` 一类 warning 所需要的最小 provenance。

### 9.4 parameter default subtree 当前必须封口

源码：

```gdscript
func ping(value, alias = value):
    return alias
```

对于 `alias = value` 中的 `value`：

- 当前不应套用 executable body 的参数可见性规则
- resolver 推荐直接返回 `DEFERRED_UNSUPPORTED`，并填写 `deferredBoundary(domain = PARAMETER_DEFAULT, reason = VARIABLE_INVENTORY_NOT_PUBLISHED)`

### 9.5 `for` / `match` / lambda / block-local `const` subtree 当前不得静默成功

源码：

```gdscript
func ping(values):
    for item in values:
        print(item)
```

对 loop body 中 `item` 的 use-site：

- 当前不能靠“outer lookup miss”或“假装没找到”给出正常结果
- 应返回 `DEFERRED_UNSUPPORTED`，并填写 `deferredBoundary(domain = FOR_SUBTREE, reason = VARIABLE_INVENTORY_NOT_PUBLISHED)`

`match` / lambda / block-local `const` 同理。

对 block-local `const` 还要额外防止另一种静默误判：

- 若当前 block 中前面已经出现同名 `const`，后续 use-site 也不能回退成普通 `NOT_FOUND`
- 更不能继续落到 outer local / class property / global 上并假装解析成功
- 这类 use-site 仍应返回 `DEFERRED_UNSUPPORTED`，因为它依赖的 local-const inventory 当前并未发布

这条规则还必须再补一层 current-scope fail-closed 保护：

- `LAMBDA_BODY` / `FOR_BODY` / `MATCH_SECTION_BODY` 这些 scope kind 已由 scope phase 正式发布
- 即使某个 AST boundary edge 分类未来漏掉了一个节点，resolver 也不能因为祖先链上存在 `FUNCTION_BODY` / `WHILE_BODY` / `IF_BODY` 就继续 lookup
- 只要 current scope 自己就是这些未发布 inventory 的 kind，resolver 就必须直接返回 `DEFERRED_UNSUPPORTED`

---

## 10. 建议测试锚点

若后续为 `FrontendVisibleValueResolver` 新增单测，建议至少覆盖：

### 10.1 基础可见性

- 首次 local 声明在 use-site 之后 -> `NOT_FOUND + filteredHits`
- 首次 outer-block local 声明在 inner use-site 之后 -> `NOT_FOUND + filteredHits`
- `var x = x` -> 右侧不命中当前 declaration，且 `primaryFilteredHit().reason = SELF_REFERENCE_IN_INITIALIZER`
- parameter 在 executable callable body 内始终可见

### 10.2 provenance 保留

- class property 先可见、后面 local 同名 -> `FOUND_ALLOWED + filteredHits`
- 若未来需要 class/global warning parity，可在此基础上扩展 `CONFUSABLE_LOCAL_USAGE` 类诊断消费测试

### 10.3 deferred/unsupported 边界

- parameter default subtree -> `DEFERRED_UNSUPPORTED + deferredBoundary(domain = PARAMETER_DEFAULT, ...)`
- lambda subtree -> `DEFERRED_UNSUPPORTED + deferredBoundary(domain = LAMBDA_SUBTREE, ...)`
- block-local `const` initializer subtree -> `DEFERRED_UNSUPPORTED + deferredBoundary(domain = BLOCK_LOCAL_CONST_SUBTREE, ...)`
- `for` subtree -> `DEFERRED_UNSUPPORTED + deferredBoundary(domain = FOR_SUBTREE, ...)`
- `match` subtree -> `DEFERRED_UNSUPPORTED + deferredBoundary(domain = MATCH_SUBTREE, ...)`
- skipped subtree / missing use-site scope -> `DEFERRED_UNSUPPORTED + deferredBoundary(domain = EXECUTABLE_BODY or UNKNOWN_OR_SKIPPED_SUBTREE, ...)`
- synthetic current-scope remap to `LAMBDA_BODY` / `FOR_BODY` / `MATCH_SECTION_BODY` 也必须返回 `DEFERRED_UNSUPPORTED`

### 10.4 shared-scope 异常传播

- missing-super exception 传播
- inheritance-cycle exception 传播
- `FOUND_BLOCKED` 的 class-member 命中不会被 resolver 错误降级成 `NOT_FOUND`

---

## 11. 建议触达文件

若后续实现本文档对应行为，预计会触达：

- `src/main/java/dev/superice/gdcc/frontend/sema/resolver/FrontendVisibleValueResolver.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/resolver/FrontendVisibleValueResolveRequest.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/resolver/FrontendVisibleValueResolution.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/resolver/FrontendFilteredValueHit.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/resolver/FrontendVisibleValueDeferredBoundary.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/resolver/FrontendVisibleValueResolverTest.java`
- 视 binder 接线位置，可能还需要：
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/FrontendSemanticAnalyzerFrameworkTest.java`

---

## 12. 任务计划与验收标准

当前实施状态：

- [x] request/result 相关数据结构已落地：`FrontendVisibleValueResolveRequest`、`FrontendVisibleValueResolution`、`FrontendFilteredValueHit` 及配套 domain/status/reason 枚举已添加到 `frontend.sema.resolver`
- [x] `EXECUTABLE_BODY` 域的 resolver 主逻辑已落地，当前通过 `FrontendVisibleValueResolver` 在 `frontend.sema.resolver` 内独立提供
- [x] declaration-order 过滤、initializer 自引用过滤、shared-scope 委托/传播逻辑已在 resolver 中实现
- [x] deferred boundary 封口已实现：parameter default、lambda body、block-local `const`、`for`、`match`、missing-scope 均返回 `DEFERRED_UNSUPPORTED`
- [x] current-scope fail-closed hardening 已实现：`FOR_BODY` / `MATCH_SECTION_BODY` / `LAMBDA_BODY` 等未发布 inventory 的当前 scope 不再仅因祖先链存在 supported block 而放行
- [x] resolver 单元测试已补齐：覆盖 parameter/local/class-property 正向解析、future local / self-reference 过滤、blocked class-member、parameter default / lambda / block-local `const` / `for` / `match` deferred、missing-scope、shared `ScopeLookupException` 传播

当前验收状态：

- [x] `NOT_FOUND + filteredHits`、`FOUND_ALLOWED + filteredHits`、`FOUND_BLOCKED`、`SELF_REFERENCE_IN_INITIALIZER`、missing-scope -> `DEFERRED_UNSUPPORTED` 均已由 `FrontendVisibleValueResolverTest` 锚定
- [x] `EXECUTABLE_BODY` 内 parameter / ordinary local / class property 三类基本路径已覆盖
- [x] `ClassScope` / `ClassRegistry` 侧的 `ScopeLookupException` 会原样传播
- [x] 真实 AST deferred 场景与 synthetic current-scope fallback 场景均已覆盖，证明即使 AST boundary 漏判，resolver 仍按未发布 inventory fail-closed
- [x] 本阶段仍未接入 binder，resolver 以独立可调用组件形态落地，符合阶段目标

### 12.1 当前修复任务：supported executable scope hardening（2026-03-16）

- [x] 提取 published variable-inventory block kind 的共享 support matrix，避免 `FrontendVariableAnalyzer` 与 resolver 分别维护一套名单
- [x] 将 resolver 从“祖先链存在 supported block 即放行”改为“当前 scope 自己必须属于已发布 inventory 域，否则 fail-closed”
- [x] 补齐单元测试：既覆盖真实 AST deferred 场景，也覆盖 synthetic current-scope fallback 场景，证明即使 AST 边界识别漏掉也不会静默成功

任务范围：

- 新增 request/result 相关数据结构，至少包括 `FrontendVisibleValueResolveRequest`、`FrontendVisibleValueResolution`、`FrontendFilteredValueHit`
- 把 `filteredHits`、`primaryFilteredHit()`、`deferredBoundary`、`domain` 这些本文已冻结的合同先落地到代码
- 实现 `EXECUTABLE_BODY` 域的 resolver 主逻辑
- 对 `BlockScope` / `CallableScope` 加入 declaration-order 过滤与 initializer 自引用过滤
- 保留 shared `Scope` 的 `FOUND_ALLOWED` / `FOUND_BLOCKED` / `NOT_FOUND` 语义与异常传播行为
- 对 parameter default / lambda / block-local `const` / `for` / `match` / missing-scope 等位置统一返回 `DEFERRED_UNSUPPORTED + deferredBoundary`

本阶段不做：

- 不改 binder 主链路
- 不直接产出新的 warning/diagnostic
- 不把 deferred 域改成真实可解析域

验收标准：

- 新增 resolver 单测，覆盖 `NOT_FOUND + filteredHits`、`FOUND_ALLOWED + filteredHits`、`FOUND_BLOCKED` 不降级、`SELF_REFERENCE_IN_INITIALIZER`、missing-scope -> `DEFERRED_UNSUPPORTED`
- `EXECUTABLE_BODY` 内的 parameter / ordinary local / class property 三类基本路径均有测试
- `ClassScope` / `ClassRegistry` 抛出的 `ScopeLookupException` 会原样传播
- 本阶段不要求 binder 接线，但 resolver 自身已可被独立调用并返回稳定结果合同

## 13 当前明确不进入计划的范围

根据 `doc/module_impl/frontend/frontend_rules.md` 的 MVP 支持约定，以下内容不属于当前 frontend 计划，不应再作为后续实施阶段出现：

- function parameter default-value binding / visibility
- lambda parameter / local / capture binding
- `for` iterator binding 与 loop-body local binding
- `match` pattern binding / guard / section-body binding

对这些域，当前正确策略仍然是：

- 继续通过 `DEFERRED_UNSUPPORTED + deferredBoundary(...)` 显式封口
- 保持 `FrontendVisibleValueResolver` 的 request/result 合同稳定
- 不把“未来可能支持”写成当前计划中的默认后续阶段
