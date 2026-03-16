# FrontendTopBindingAnalyzer 实施说明

> 本文档作为 `FrontendTopBindingAnalyzer` 的长期事实源，定义当前 MVP 的职责边界、冻结语义前提、分阶段落地步骤、测试验收口径与支持矩阵。当前阶段的目标是先把 frontend 上“链式访问/调用最外层 segment 的符号类别绑定”这件事做对、做稳，而不是一次性补齐成员访问、链式调用、读写语义、调用语义与表达式类型。

## 文档状态

- 性质：长期事实源 / MVP 实施计划
- 最后校对：2026-03-16
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `frontend_variable_analyzer_implementation.md`
  - `frontend_visible_value_resolver_implementation.md`
  - `scope_analyzer_implementation.md`
  - `scope_architecture_refactor_plan.md`
  - `scope_type_resolver_implementation.md`
  - `diagnostic_manager.md`
  - `frontend_rules.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
- 明确非目标：
  - 不在本阶段实现 read / write / assignable / lvalue 语义
  - 不在本阶段实现 call legality、overload ranking、`resolvedCalls()` 发布
  - 不在本阶段实现 `resolvedMembers()` 发布
  - 不在本阶段实现完整 `expressionTypes()` / `FrontendExprTypeAnalyzer`
  - 不在本阶段实现 `a.xxx` 中 `xxx` 的成员绑定
  - 不在本阶段实现 parameter default、`for`、`match`、lambda capture 的正式 binding
  - 不把 `DiagnosticManager` 注入 shared `Scope` / shared resolver

---

## 1. MVP 目标与冻结前提

### 1.1 当前 MVP 只发布什么

当前 MVP 只发布一张 frontend side-table：

- `symbolBindings()`

当前 MVP 只为以下 AST use-site 写入 `symbolBindings()`：

- supported executable subtree 中、处于 value position 的 `IdentifierExpression`
- supported executable subtree 中、作为 bare callee 的 `IdentifierExpression`
- supported executable subtree 中、作为 qualified static access / call chain 最外层 segment 的 `IdentifierExpression`
- supported executable subtree 中的 `SelfExpression`
- supported executable subtree 中的 `LiteralExpression`

当前 MVP 明确不发布：

- `resolvedMembers()`
- `resolvedCalls()`
- `expressionTypes()`

这意味着当前 phase 的最小职责是：

- 判断一个 use-site 绑定到哪一类符号
- 必要时保留 declaration site
- 产出 blocked / unknown / deferred 诊断

当前 phase 不负责：

- 判断该 use-site 是读取、写入还是调用
- 判断链式访问/调用后续 segment 的类别
- 判断 `foo()` 的重载、实参匹配与最终调用目标

### 1.2 当前 MVP 冻结采用的外部语义前提

本阶段以以下前提为冻结事实：

- `self` 必须进入 `symbolBindings()`，并新增独立的 `FrontendBindingKind.SELF`
- `self` 在 static context 中仍然绑定为 `SELF`，同时发出 frontend error diagnostic
- `foo()` 这类 bare callee 的名称解析必须走 function namespace，即 `Scope.resolveFunctions(...)`
- GDScript 语义保证 `foo()` 里的 `foo` 不会和 callable value 混淆；callable value 调用必须写成 `.call(...)`
- `ClassName.build()`、`ClassName.VALUE` 这类 qualified static access / call chain 中，最外层 `ClassName` 允许绑定为 `TYPE_META`
- `TYPE_META` 顶层绑定在 MVP 阶段只支持：
  - 已注册到 `ClassRegistry` 的 class-like 类型
  - 当前 lexical scope 可见的 inner class
- 上下文中定义的其他 type-meta 当前不支持：
  - preload alias
  - const-based type alias
  - local enum
  - 其他未来可能通过 `defineTypeMeta(...)` 接入、但当前 top-binding phase 尚未正式消费的来源
- `a.xxx`、`a.foo()` 这类显式 receiver 形态中，本阶段只继续分析最前面的 `a` 子表达式，不解析尾部成员/调用步骤
- `a[index]` 这类容器索引读取中，本阶段继续分析 `a` 和所有 index argument expression，但不为 subscript operation 本身写 binding
- ordinary local `var` initializer expression subtree 属于当前 MVP 支持面，不能再被排除
- parameter default、`for` iterator、`match` pattern、lambda capture 继续 deferred

这些前提直接约束了 binder 的设计：

- bare callee 不能误走 value namespace
- qualified static access / call chain 的最外层 segment 需要允许走 type-meta namespace
- 显式 receiver 的尾部成员不能误写入 `symbolBindings()` / `resolvedMembers()`
- 容器索引读取中的 index expression 必须继续递归分析
- ordinary local initializer 内的标识符解析必须复用 `FrontendVisibleValueResolver` 的既有合同，而不是额外封口

### 1.3 当前代码库已经具备的基础设施

当前代码库已经具备以下可直接复用的事实源：

- `FrontendAnalysisData` 已稳定承载：
  - `annotationsByAst`
  - `scopesByAst`
  - `symbolBindings`
  - `expressionTypes`
  - `resolvedMembers`
  - `resolvedCalls`
- `FrontendSemanticAnalyzer` 已稳定串联：
  - skeleton
  - scope analyzer
  - variable analyzer
- `FrontendScopeAnalyzer` 已为 body 节点发布 `scopesByAst()`
- `FrontendVariableAnalyzer` 已把 parameter / supported ordinary local inventory 写入 `CallableScope` / `BlockScope`
- `FrontendVisibleValueResolver` 已为 ordinary local initializer 提供稳定支持，包括：
  - declaration-order 可见性修正
  - initializer 自引用过滤
  - blocked-hit shadowing 保留
  - deferred unsupported 域封口
- `FrontendVisibleValueResolverTest` 已有 `ordinary_local_initializer_supported.gd` 用例，证明 ordinary local initializer 不是 deferred 域
- shared `Scope` 已稳定区分三套 namespace：
  - `resolveValue(...)`
  - `resolveFunctions(...)`
  - `resolveTypeMeta(...)`
- `ClassScope.resolveFunctions(...)` 已能处理未指定类名的 bare 方法 / static function 查找
- `ClassRegistry.resolveFunctionsHere(...)` 已能处理 global utility function 查找
- `Scope.resolveTypeMeta(...)` 已能通过 lexical type namespace + `ClassRegistry` 解析 strict type-meta
- `AbstractFrontendScope.defineTypeMeta(...)` / `FrontendScopeAnalyzer` 已为 lexical 可见 inner class 发布 local type-meta
- `FunctionDef.isStatic()` 已稳定存在，binder 可据此区分 `METHOD` 与 `STATIC_METHOD`
- `gdparser` 已提供统一的 `LiteralExpression(kind, sourceText, range)` 与 `SubscriptExpression(base, arguments, range)`，满足 literal binding 与 index-argument 递归的 AST 前提

### 1.4 当前代码库明确尚未补齐的模型缺口

当前仍缺、且本计划必须显式面对的事实包括：

- `FrontendBindingKind` 还没有 `SELF`
- `FrontendBindingKind` 还没有 `METHOD`
- `FrontendBindingKind` 还没有 `STATIC_METHOD`
- `FrontendBindingKind` 还没有 `LITERAL`
- `FrontendBindingKind.UTILITY_FUNCTION` 只能覆盖 global utility function，不能单独代表 class method/static method
- `FrontendResolvedMember` 与 `FrontendResolvedCall` 当前都还处于占位状态，但本阶段不需要补它们
- `CallableScope` 仍不把 `self` 建模成隐式 `ScopeValue`
- `FrontendSemanticAnalyzerFrameworkTest` 与部分旧测试仍把 binding side-table 视为“默认空表”

当前推荐冻结的最小模型补位是：

- 在 `FrontendBindingKind` 中新增 `SELF`
- 在 `FrontendBindingKind` 中新增 `METHOD`
- 在 `FrontendBindingKind` 中新增 `STATIC_METHOD`
- 在 `FrontendBindingKind` 中新增 `LITERAL`
- 保留 `UTILITY_FUNCTION` 专门表示 global utility function

当前关于新增枚举的冻结口径是：

- `METHOD`
  - 表示 bare callee 命中了实例方法 overload set
- `STATIC_METHOD`
  - 表示 bare callee 命中了静态方法 overload set
- `LITERAL`
  - 表示字面量表达式节点自身的 binding kind
- `UTILITY_FUNCTION`
  - 继续只表示 global utility function

---

## 2. 当前 MVP 的职责边界

### 2.1 `FrontendTopBindingAnalyzer` 当前负责什么

`FrontendTopBindingAnalyzer` 当前冻结负责：

- 在 `FrontendVariableAnalyzer` 之后运行
- 在 supported subtree 中识别 use-site 所处的 namespace
- 将 bare value identifier 的类别绑定写入 `symbolBindings()`
- 将 bare callee identifier 的类别绑定写入 `symbolBindings()`
- 将 qualified static access / call chain 最外层 type-meta identifier 的类别绑定写入 `symbolBindings()`
- 将 `self` 的类别绑定写入 `symbolBindings()`
- 将字面量表达式的类别绑定写入 `symbolBindings()`
- 将 shared `Scope` / shared resolver 的 lookup 事实翻译成 frontend diagnostics
- 对当前明确 deferred 的子域发出显式 unsupported / deferred diagnostics

### 2.2 `FrontendTopBindingAnalyzer` 当前不负责什么

`FrontendTopBindingAnalyzer` 当前明确不负责：

- 把 `self` 注入 `CallableScope`
- 改写 shared `Scope` 协议
- 发布 `resolvedMembers()` / `resolvedCalls()`
- 解析 `a.xxx` 中 `xxx` 的属性、signal、method 或 call step
- 计算属性读写、assignable、callability
- 做一般 type position binding，或解析 qualified static access / call chain 的后续 member/call step
- 在 unsupported 子域上伪装成正常 `NOT_FOUND`

---

## 3. 当前 MVP 的支持面

### 3.1 支持的 executable subtree

当前 MVP 的 supported executable subtree 冻结为：

- function body
- constructor body
- 普通嵌套 `Block`
- `if` / `elif` / `else` body
- `while` body
- ordinary local `var` initializer expression subtree

这里故意把 ordinary local initializer 单独列出来，是为了冻结两条事实：

- 它属于当前 MVP 支持面
- 它的名称可见性必须沿用 `FrontendVisibleValueResolver` 的现有合同，而不是另起一套“binder 先排除”的规则

### 3.2 支持的 symbol category

当前阶段 `symbolBindings()` 的 MVP 类别应至少覆盖：

- `SELF`
- `LITERAL`
- `LOCAL_VAR`
- `PARAMETER`
- `PROPERTY`
- `SIGNAL`
- `CONSTANT`
- `SINGLETON`
- `GLOBAL_ENUM`
- `TYPE_META`
- `METHOD`
- `STATIC_METHOD`
- `UTILITY_FUNCTION`
- `UNKNOWN`

以下类别当前不作为 MVP 的正式验收项：

- `CAPTURE`
  - lambda capture 仍 deferred，本阶段通常不会正式产出

其中 `TYPE_META` 的当前 MVP 口径冻结为：

- 只用于 qualified static access / call chain 的最外层 segment
- 只支持：
  - `ClassRegistry` 已注册的 class-like 类型
  - 当前 lexical scope 可见的 inner class
- 不扩展到一般 type position
- 不扩展到上下文中定义的其他 type-meta 来源

### 3.3 支持的 use-site 形态

当前 MVP 支持以下 use-site：

- value position 的 `IdentifierExpression`
  - 例如 `print(hp)` 中的 `hp`
  - 例如 `var answer = seed` 中的 `seed`
  - 例如 `player.hp` 中 receiver base 的 `player`
- bare callee 的 `IdentifierExpression`
  - 例如 `foo()` 中的 `foo`
  - 例如 `foo().bar` 中 base call 的 bare callee `foo`
- qualified static access / call chain 最外层的 `IdentifierExpression`
  - 例如 `ClassName.build()` 中的 `ClassName`
  - 例如 `Inner.build()` 中的 `Inner`
- `SelfExpression`
  - 例如 `self`
  - 例如 `self.hp` 中的 `self`
- `LiteralExpression`
  - 例如 `0`
  - 例如 `"hello"`
  - 例如 `true`

当前 MVP 对显式 receiver 形态的冻结规则是：

- `a.xxx`
  - 只解析 `a`
  - 不解析 `xxx`
- `a.foo()`
  - 只解析 `a`
  - 不解析 `foo`
- `ClassName.build()`
  - 解析 `ClassName`
  - 不解析 `build`
- `a[0]`
  - 解析 `a`
  - 解析每个 index argument expression，例如 `0`
  - 不解析下标访问 operation 本身

### 3.4 当前明确 deferred / unsupported 的 use-site

以下位置当前一律不能伪装成正常 binding：

- parameter default subtree
- lambda subtree
- `for` iterator / iterable / body
- `match` pattern / guard / section body
- block-local `const` initializer subtree
- 任何 `scopesByAst()` 中缺失、或前序 phase 已跳过的 subtree

当前 binder 对这些位置的合同是：

- 发明确的 deferred / unsupported diagnostic
- 不写入误导性的 `symbolBindings()`
- 不把其结果降级成 `NOT_FOUND`

### 3.5 当前明确不支持的绑定结果

以下结果当前不属于本阶段产物：

- `resolvedMembers()` 中的任何成员绑定
- `resolvedCalls()` 中的任何调用绑定
- qualified static access / call chain 的尾部成员或调用步骤，例如 `ClassName.build()` 中的 `build`
- 显式 receiver 尾部成员，例如 `player.hp` 中的 `hp`
- `obj.foo()` 中的 `foo`
- `obj.changed` 中的 `changed`
- subscript operation 本身的绑定结果，例如 `arr[i]` 这一步“索引读取”的 operation kind

---

## 4. 名称空间分流与遍历策略

### 4.1 value position、literal position 与 top-level type-meta position

对 value position 的 `IdentifierExpression`：

- 必须调用 `FrontendVisibleValueResolver`
- 继续复用其 declaration-order、initializer 自引用、blocked-hit 与 deferred-boundary 合同
- 当前 ordinary local initializer 内的 name lookup 也必须走这条路径

对 `LiteralExpression`：

- 不需要调用 `FrontendVisibleValueResolver`
- 直接写入 `FrontendBindingKind.LITERAL`
- `FrontendBinding.symbolName` 当前可直接复用 literal 的 `sourceText`
- `declarationSite` 继续保持 `null`

对 qualified static access / call chain 最外层的 `IdentifierExpression`：

- 必须优先尝试 `Scope.resolveTypeMeta(name, restriction)`
- 当前只接受以下 `TYPE_META` 来源：
  - `ClassRegistry` 已注册的 class-like 类型
  - 当前 lexical scope 可见的 inner class
- 命中后写入 `FrontendBindingKind.TYPE_META`
- 当前不继续解析尾部 member / call step
- 若命中的是当前 MVP 不支持的其他 type-meta 来源
  - 发 `sema.unsupported_binding_subtree` 或 `sema.binding` diagnostic
  - 不写入误导性的 `TYPE_META`

当前明确不纳入 top-level `TYPE_META` 支持面的来源包括：

- preload alias
- const-based type alias
- local enum
- 其他未来通过 `defineTypeMeta(...)` 进入 scope、但当前尚未冻结消费合同的来源

`ScopeValueKind -> FrontendBindingKind` 的当前推荐映射：

- `LOCAL` -> `LOCAL_VAR`
- `PARAMETER` -> `PARAMETER`
- `PROPERTY` -> `PROPERTY`
- `SIGNAL` -> `SIGNAL`
- `CONSTANT` -> `CONSTANT`
- `SINGLETON` -> `SINGLETON`
- `GLOBAL_ENUM` -> `GLOBAL_ENUM`

当前 `TYPE_META` 的消费边界冻结为：

- value position 不应顺手消费 type namespace
- 一般 type position 不属于 `FrontendTopBindingAnalyzer` 的 MVP 支持面
- 允许在 qualified static access / call chain 顶层 segment 上发布 `TYPE_META`
- 但尾部 member / call step 继续留给后续 phase

### 4.2 bare callee position

对 `foo()` 这类 bare callee：

- 必须从当前 scope 调用 `resolveFunctions(name, restriction)`
- 不能退回 `resolveValue(...)`
- 不能因为当前阶段不发布 `resolvedCalls()`，就跳过这类绑定

当前 bare callee 的发布口径冻结为：

- 命中 `ClassScope` 提供的 unqualified instance method overload set：
  - 绑定为 `FrontendBindingKind.METHOD`
- 命中 `ClassScope` 提供的 unqualified static method overload set：
  - 绑定为 `FrontendBindingKind.STATIC_METHOD`
- 命中 `ClassRegistry` 提供的 global utility function：
  - 绑定为 `FrontendBindingKind.UTILITY_FUNCTION`
- `FOUND_BLOCKED`：
  - 当前语义上只能是 instance method 被 static context 阻止
  - 仍写入 `METHOD`
  - 同时发 `sema.binding` error
- `NOT_FOUND`：
  - 写入 `UNKNOWN`
  - 同时发 `sema.binding` error

当前 bare callee 的 method/static-method 区分规则冻结为：

- 若返回的有效 overload set 全部 `isStatic() == false`
  - 绑定为 `METHOD`
- 若返回的有效 overload set 全部 `isStatic() == true`
  - 绑定为 `STATIC_METHOD`
- 若未来出现同一个 surviving overload set 同时混有 static 与 non-static
  - 当前 binder 应 fail-closed
  - 发 `sema.binding` diagnostic
  - 不写入误导性的 method kind

这里要明确区分三件事：

- “解析并绑定 global function”：
  - 当前指 `ClassRegistry` 中的 utility function
  - 它们必须作为 bare callee 命中并写入 `UTILITY_FUNCTION`
- “解析并绑定未指定类名的裸方法”：
  - 当前指 class scope 中的实例方法
  - 它们必须通过 `Scope.resolveFunctions(...)` 命中并写入 `METHOD`
- “解析并绑定未指定类名的静态函数”：
  - 当前同样通过 `Scope.resolveFunctions(...)` 命中
  - 它们在当前阶段写入 `STATIC_METHOD`
  - call legality、overload ranking 与最终 dispatch 仍留给后续 call phase

### 4.3 `self`

`self` 当前不走 `Scope.resolveValue(...)`，而由 binder 直接处理。

当前冻结行为：

- instance context：
  - 绑定为 `FrontendBindingKind.SELF`
  - 写入 `symbolBindings()`
  - 不产出 diagnostic
- static context：
  - 仍写入 `FrontendBindingKind.SELF`
  - 同时产出 `sema.binding` error

这样做的原因是：

- `self` 不是 unknown name
- static context 下的问题是“上下文非法”，不是“名字不存在”
- 后续 phase 不应通过“看起来像 unknown”来反推 `self`

### 4.4 显式 receiver 的遍历策略

当前 binder 对显式 receiver 形态只做“前导子表达式”递归分析，不做尾部成员绑定。

推荐冻结为以下遍历规则：

- `AttributeExpression`
  - 继续访问 base expression
  - 若 base expression 是 qualified static chain 最外层 bare identifier，则允许其绑定为 `TYPE_META`
  - 不为 member step 写 binding
- `SubscriptExpression`
  - 继续访问 base expression
  - 继续访问每个 index argument expression
  - index argument 中出现的 identifier / self / literal 仍按各自规则写 binding
  - 不为 subscript operation 本身写 binding
- `CallExpression`
  - 若 callee 是 bare `IdentifierExpression`，按 bare callee 规则绑定
  - 若 callee 是 `AttributeExpression`，只继续分析其 base expression
  - 因此 `ClassName.build()` 当前允许 `ClassName -> TYPE_META`
  - 不为 attribute call step 写 binding

这条规则的直接效果是：

- `player.hp`
  - `player` 会绑定
  - `hp` 不会绑定
- `get_player().hp`
  - `get_player` 会绑定为 `METHOD` / `UTILITY_FUNCTION` / `UNKNOWN`
  - `hp` 不会绑定
- `self.hp`
  - `self` 会绑定为 `SELF`
  - `hp` 不会绑定
- `ClassName.build()`
  - `ClassName` 会绑定为 `TYPE_META`
  - `build` 不会绑定
- `arr[i + 1]`
  - `arr` 会绑定
  - `i` 会绑定
  - `1` 会绑定为 `LITERAL`
  - subscript operation 本身不会绑定

---

## 5. 诊断合同

### 5.1 推荐 category

当前建议冻结以下 frontend binder categories：

- `sema.binding`
  - unknown identifier
  - unknown bare callee
  - static context 下非法使用 `self`
  - static context 下命中 blocked property / signal / method
- `sema.unsupported_binding_subtree`
  - parameter default
  - lambda subtree
  - `for` subtree
  - `match` subtree
  - block-local `const` initializer subtree
  - missing-scope / skipped subtree

### 5.2 blocked-hit 处理

只要当前层命中的是 blocked binding，就必须：

- 保留该 binding 作为最终解析结论
- 产出 frontend diagnostic
- 停止继续回退 outer/global

不得：

- 把 blocked hit 降级成 `NOT_FOUND`
- 为了“找到一个能用的名字”继续回退 outer/global

这条规则当前适用于：

- static context 下的实例 property
- static context 下的实例 signal
- static context 下的实例 method
- static context 下的 `self`

### 5.3 ordinary local initializer 的诊断约定

ordinary local initializer 当前不属于 deferred 域。

因此：

- `var answer = seed`
  - `seed` 必须正常绑定
- `var node = node`
  - 当前 declaration 必须被视为 initializer 自引用并过滤
  - 若外层存在 class property / outer visible binding，则允许 fallback 到该绑定
  - 若无其他可见绑定，则按 `UNKNOWN + sema.binding` 处理

这部分行为应完全复用 `FrontendVisibleValueResolver` 的合同，不允许 binder 自己发明第二套“initializer 特判”。

---

## 6. 分阶段实施步骤

### 6.1 阶段 B0：模型补位与合同冻结

**目标**：先把当前 MVP 需要的 binding kind、side-table 口径与诊断合同冻结下来，避免后续实现过程中反复回改模型。

**实施步骤**：

1. 在 `FrontendBindingKind` 中新增 `SELF`
2. 在 `FrontendBindingKind` 中新增 `LITERAL`
3. 在 `FrontendBindingKind` 中新增 `METHOD`
4. 在 `FrontendBindingKind` 中新增 `STATIC_METHOD`
5. 明确 `UTILITY_FUNCTION` 继续只表示 global utility function
6. 明确 bare callee 通过 `FunctionDef.isStatic()` 区分 `METHOD` 与 `STATIC_METHOD`
7. 明确 top-level `TYPE_META` 只支持 registry-registered class-like 类型与 lexical 可见 inner class
8. 冻结 `symbolBindings()` 是本阶段唯一正式发布口径
9. 明确 `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` 仍不属于本阶段产物
10. 明确 ordinary local initializer subtree 属于支持面
11. 明确 `a.xxx` 只解析 `a`，不解析 `xxx`
12. 明确 `a[index]` 解析 `a` 与全部 index argument expression，但不解析 subscript operation 本身

**验收清单**：

- [x] `FrontendBindingKind.SELF` 已冻结
- [x] `FrontendBindingKind.LITERAL` 已冻结
- [x] `FrontendBindingKind.METHOD` 已冻结
- [x] `FrontendBindingKind.STATIC_METHOD` 已冻结
- [x] 文档已明确 `UTILITY_FUNCTION` / `METHOD` / `STATIC_METHOD` 的分工
- [x] 文档已明确 `TYPE_META` 的 MVP 支持来源
- [x] 文档已明确 ordinary local initializer subtree 属于支持面
- [x] 文档已明确 subscript index argument expression 属于支持面
- [x] 文档已明确 `resolvedMembers()` / `resolvedCalls()` 不属于本阶段

### 6.2 阶段 B1：主链路接线与遍历骨架

**目标**：把 `FrontendTopBindingAnalyzer` 正式接入 `FrontendSemanticAnalyzer` 主链路，并建立只发布 `symbolBindings()` 的 walker 骨架。

**实施步骤**：

1. 新增 `FrontendTopBindingAnalyzer`
2. 在 `FrontendSemanticAnalyzer` 中将其接到 `FrontendVariableAnalyzer` 之后
3. phase 输入继续只使用：
  - `FrontendAnalysisData`
  - `DiagnosticManager`
4. phase 内构建新的 `FrontendAstSideTable<FrontendBinding>`
5. phase 返回前通过 `analysisData.updateSymbolBindings(...)` 一次性发布
6. `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` 当前完全不触碰
7. walker 必须能区分：
  - value position
  - bare callee position
  - top-level `TYPE_META` position
  - `SelfExpression`
  - `LiteralExpression`
  - deferred-boundary subtree
8. walker 必须对显式 receiver 节点遵守：
  - `AttributeExpression` 只递归前导 base expression
  - `SubscriptExpression` 递归 base expression 与全部 index argument expression
  - tail member / subscript operation 本身不写 binding

**验收清单**：

- [x] `FrontendSemanticAnalyzer` 已包含 binding phase
- [x] `FrontendAnalysisData.symbolBindings()` 已有正式发布点
- [x] 旧测试中“binding side-table 恒为空”的断言已按新 phase 改造
- [x] `resolvedMembers()` / `resolvedCalls()` 仍保持空表合同

### 6.3 阶段 B2：value-position symbol binding

**目标**：先闭合 bare value identifier、literal、top-level `TYPE_META` 与 `self` 的类别绑定。

**实施步骤**：

1. 处理处于 value position 的 `IdentifierExpression`
2. 处理 `LiteralExpression`
3. 处理 qualified static access / call chain 最外层的 `IdentifierExpression`
4. 只在 supported executable subtree 中为 value-identifier 调用 `FrontendVisibleValueResolver`
5. 对 top-level `TYPE_META` candidate 调用 `Scope.resolveTypeMeta(name, restriction)`
6. 只接受当前 MVP 支持的 type-meta 来源：
  - `ClassRegistry` 已注册的 class-like 类型
  - 当前 lexical scope 可见的 inner class
7. 传入正确的 `ResolveRestriction`：
  - static function body -> `ResolveRestriction.staticContext()`
  - constructor body / non-static function body -> `ResolveRestriction.instanceContext()`
8. 根据 `FrontendVisibleValueResolution` 结果分流：
  - `FOUND_ALLOWED`
    - 映射到对应的 `FrontendBindingKind`
    - 写入 `symbolBindings()`
  - `FOUND_BLOCKED`
    - 仍写入 `symbolBindings()`
    - 同时发 `sema.binding` error
  - `NOT_FOUND`
    - 写入 `UNKNOWN`
    - 同时发 `sema.binding` error
  - `DEFERRED_UNSUPPORTED`
    - 不写入 binding
    - 由 deferred reporter 发 `sema.unsupported_binding_subtree`
9. 对 `LiteralExpression`
  - 直接写入 `LITERAL`
10. 对 top-level `TYPE_META`
  - 命中支持来源时写入 `TYPE_META`
  - 命中不支持来源时 fail-closed，并发 diagnostic
11. 处理 `SelfExpression`
  - 直接发布 `SELF`
  - static context 同时发 `sema.binding` error

**验收清单**：

- [ ] bare local / parameter / property / signal / constant / singleton / global enum 可发布 symbol binding
- [ ] literal expression 可发布 `LITERAL`
- [ ] `ClassName.build()` / `Inner.build()` 这类链头 `ClassName` / `Inner` 可发布 `TYPE_META`
- [ ] top-level `TYPE_META` 只接受 registry-registered class-like 类型与 lexical 可见 inner class
- [ ] preload alias / const alias / local enum 等其他 context-defined type-meta 不会被误发布为 `TYPE_META`
- [ ] ordinary local initializer 内的 identifier 可正常绑定
- [ ] `arr[i + 1]` 这类索引表达式中的 `i` 与 `1` 可正常进入 binding
- [ ] `var x = x` 的 initializer 自引用行为与 resolver 合同一致
- [ ] blocked property / signal 在 static context 下不会回退 outer/global
- [ ] `self` 在 instance/static context 下的发布与诊断行为均已锚定

### 6.4 阶段 B3：bare callee function binding

**目标**：闭合 `foo()` 这种未指定类名的函数 namespace 绑定，但仍不发布调用语义。

**实施步骤**：

1. 在 `CallExpression` 中识别 bare callee `IdentifierExpression`
2. 从当前 scope 调用 `resolveFunctions(name, restriction)`
3. 根据 lookup 结果分流：
  - 当前层命中 class instance method
    - 发布 `METHOD`
  - 当前层命中 class static method
    - 发布 `STATIC_METHOD`
  - global utility function 命中
    - 发布 `UTILITY_FUNCTION`
  - `FOUND_BLOCKED`
    - 仍发布 `METHOD`
    - 同时发 `sema.binding` error
  - `NOT_FOUND`
    - 发布 `UNKNOWN`
    - 同时发 `sema.binding` error
4. 不写入 `resolvedCalls()`
5. 若 surviving overload set 混有 static 与 non-static
  - fail-closed
  - 发 `sema.binding` diagnostic
  - 不写入误导性的 method kind
6. 不做 overload ranking、arg matching、call legality

**验收清单**：

- [ ] bare 实例方法可绑定为 `METHOD`
- [ ] bare 静态函数可绑定为 `STATIC_METHOD`
- [ ] bare global utility function 可绑定为 `UTILITY_FUNCTION`
- [ ] static context 下被 restriction 阻止的 bare 实例方法不会回退 global utility function
- [ ] mixed static/non-static surviving overload set 会 fail-closed，而不是写入误导性类别
- [ ] `resolvedCalls()` 仍保持空表合同

### 6.5 阶段 B4：显式 receiver 遍历收口、诊断与测试矩阵

**目标**：冻结“只解析前导子表达式、不解析尾部成员”的行为，并把当前 MVP 的支持/不支持边界锚定到测试层。

**实施步骤**：

1. 为 `AttributeExpression` / `SubscriptExpression` / attribute-call 形态补齐遍历策略
2. 确保：
  - `player.hp` 只绑定 `player`
  - `self.hp` 只绑定 `self`
  - `get_player().hp` 只绑定 `get_player`
  - `arr[i + 1]` 绑定 `arr`、`i` 与 `1`，但不绑定 subscript operation
3. 对 deferred subtree 统一发 `sema.unsupported_binding_subtree`
4. 补齐 framework 与 analyzer 测试

**推荐测试集合**：

- `FrontendTopBindingAnalyzerTest`
  - bare local / parameter / property / signal / singleton / global enum
  - `self`
  - literal binding
  - top-level `TYPE_META` binding
  - ordinary local initializer supported
  - initializer 自引用过滤
  - bare method / bare static function / global utility function
  - subscript index argument binding
  - static context blocked binding
  - unknown identifier / unknown bare callee
  - explicit receiver 只绑定前导子表达式
  - deferred subtree warning
- `FrontendSemanticAnalyzerFrameworkTest`
  - 主链路更新为 `skeleton -> scope -> variable -> binding`
  - `symbolBindings()` 在 binding phase 后正式发布
  - `resolvedMembers()` / `resolvedCalls()` 当前仍为空

**验收清单**：

- [ ] binding phase 新增测试已覆盖正向/负向/deferred 场景
- [ ] `FrontendSemanticAnalyzerFrameworkTest` 已更新为四阶段主链路
- [ ] `player.hp` / `self.hp` / `obj.foo()` 不会错误为尾部成员写 binding
- [ ] `arr[i + 1]` 会绑定 `arr`、`i` 与 `1`，但不会为 subscript operation 写 binding
- [ ] 现有 scope / resolver 行为没有被 binder 回写破坏

---

## 7. 最小闭环定义

当以下条件全部满足时，可认为 `FrontendTopBindingAnalyzer` 的当前 MVP 已闭环：

- `FrontendSemanticAnalyzer` 主链路已包含 binding phase
- `FrontendBindingKind.SELF`、`FrontendBindingKind.LITERAL`、`FrontendBindingKind.TYPE_META`、`FrontendBindingKind.METHOD`、`FrontendBindingKind.STATIC_METHOD` 已正式落位
- supported executable subtree 中的 bare value identifier、literal 与 top-level `TYPE_META` 已正式发布到 `symbolBindings()`
- ordinary local initializer subtree 中的 identifier 已正式纳入支持面
- bare callee 的 global utility function / bare method / bare static function 已正式发布到 `symbolBindings()`
- static context 下的 `self`、instance property、instance signal、instance method 都能稳定出 frontend diagnostic
- `a.xxx` / `a.foo()` 只绑定前导子表达式，不再误写尾部成员
- `ClassName.build()` / `Inner.build()` 这类链头 type-meta 可只发布最外层 `TYPE_META`
- `a[index]` 会绑定 `a` 与 index argument expression，但不会为 subscript operation 本身写 binding
- `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` 仍保持未接线，不妨碍以上行为稳定运行

---

## 8. 当前阶段之后的直接增量工作

在当前 MVP 闭环之后，下一批自然增量工作应是：

1. `resolvedCalls()` 的最小接线
2. `resolvedMembers()` 的正式接线
3. qualified static access / `TYPE_META` 的尾部消费与更完整来源接线
4. parameter default 的正式接线
5. `for` iterator / `match` pattern / lambda capture
6. `expressionTypes()` 与 assignable analyzer
7. `.call(...)` / `.emit(...)` / `await` 等更完整 body semantics

这里故意不把 ordinary local initializer 再列为“未来补做项”，因为它已经属于当前 MVP 支持面。

---

## 9. 支持与不支持示例

### 9.1 当前支持的绑定情形

```gdscript
func ping(seed: int):
    var answer = seed
```

- `seed` 绑定为 `PARAMETER`

```gdscript
var hp := 1

func ping():
    print(hp)
```

- `hp` 绑定为 `PROPERTY`

```gdscript
signal changed

func ping():
    print(changed)
```

- `changed` 绑定为 `SIGNAL`

```gdscript
func move():
    pass

func ping():
    move()
```

- `move` 作为 bare callee 绑定为 `METHOD`

```gdscript
static func build():
    pass

func ping():
    build()
```

- `build` 作为 bare callee 绑定为 `STATIC_METHOD`

```gdscript
func ping():
    print(abs(-1))
```

- `abs` 作为 bare callee 绑定为 `UTILITY_FUNCTION`

```gdscript
var node = 7

func ping():
    var node = node
```

- initializer 右侧的 `node` 不会命中当前 local declaration
- 若 class property `node` 可见，则该 use-site 绑定为 `PROPERTY`

```gdscript
func ping():
    print("hello")
```

- `"hello"` 绑定为 `LITERAL`

```gdscript
func ping():
    ClassName.build()
```

- `ClassName` 绑定为 `TYPE_META`
- `build` 当前不绑定

```gdscript
class Outer:
    class Inner:
        static func build():
            pass

    func ping():
        Inner.build()
```

- `Inner` 作为 lexical 可见 inner class 绑定为 `TYPE_META`
- `build` 当前不绑定

```gdscript
func ping(player):
    print(player.hp)
```

- `player` 绑定为 `PARAMETER`
- `hp` 当前不绑定

```gdscript
func ping(arr, i):
    print(arr[i + 1])
```

- `arr` 绑定为 `PARAMETER`
- `i` 绑定为 `PARAMETER`
- `1` 绑定为 `LITERAL`
- subscript operation 本身当前不绑定

```gdscript
func ping():
    print(self.hp)
```

- `self` 绑定为 `SELF`
- `hp` 当前不绑定

### 9.2 当前不支持、明确 deferred，或仅支持链头的绑定情形

```gdscript
func ping(value = seed):
    pass
```

- parameter default subtree 当前 deferred

```gdscript
func ping(values):
    for item in values:
        print(item)
```

- `for` subtree 当前 deferred

```gdscript
func ping(value):
    var f = func():
        return value
```

- lambda body / capture 当前 deferred

```gdscript
func ping():
    const answer = seed
```

- block-local `const` initializer subtree 当前 deferred

```gdscript
func ping():
    ClassName.build()
```

- 只有最外层 `ClassName` 允许绑定为 `TYPE_META`
- `build` 仍不在当前支持面内

```gdscript
func ping():
    foo.bar.baz
```

- 只继续分析最前面的 `foo`
- `bar`、`baz` 当前都不绑定

```gdscript
func ping(player):
    player.move()
```

- `player` 会绑定
- `move` 当前不绑定

```gdscript
const EnemyType = preload("res://enemy.gd")

func ping():
    EnemyType.build()
```

- `EnemyType` 这类 context-defined preload alias 当前不支持作为 `TYPE_META` 绑定来源

```gdscript
enum LocalKind { A }

func ping():
    LocalKind.A
```

- `LocalKind` 这类 local enum 当前不支持作为 `TYPE_META` 绑定来源

```gdscript
func ping():
    matrix[row][col]
```

- `matrix`、`row`、`col` 会继续进入 binding
- 两次 subscript operation 本身当前都不绑定

---

## 10. 参考事实源

### 10.1 本仓库

- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendAnalysisData.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendBinding.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendBindingKind.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendResolvedMember.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendResolvedCall.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendVariableAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/resolver/FrontendVisibleValueResolver.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/resolver/FrontendVisibleValueResolverTest.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/ClassScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/CallableScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/BlockScope.java`
- `src/main/java/dev/superice/gdcc/scope/Scope.java`
- `src/main/java/dev/superice/gdcc/scope/ResolveRestriction.java`
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`

### 10.2 `gdparser` AST 事实

- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/IdentifierExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/SelfExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/LiteralExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/CallExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/AttributeExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/SubscriptExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/AssignmentExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/FunctionDeclaration.java`

### 10.3 相关 Godot 资料

- `godotengine/godot` 中 `modules/gdscript/gdscript_analyzer.cpp`
  - 用于交叉确认 body analyzer 仍按 namespace 与语法上下文分流
  - 用于交叉确认 static-context blocked-hit 仍然构成 shadowing
