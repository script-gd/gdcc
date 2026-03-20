# Frontend Scope Analyzer 实现说明

> 本文档作为 `frontend.sema.analyzer` 中 scope analyzer 的长期事实源，定义当前职责边界、`Node -> Scope` side-table 语义、frontend 接入约定、inner class / type-meta 发布规则，以及后续工程需要遵守的 deferred 边界。

## 文档状态

- 性质：长期事实源
- 最后校对：2026-03-14
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/frontend/diagnostic/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
  - `src/test/java/dev/superice/gdcc/frontend/scope/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/module_impl/frontend/scope_type_resolver_implementation.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
- 明确非目标：
  - 不在此处实现完整 frontend binder/body
  - 不在 scope analyzer 中写入 parameter/local/capture/pattern binding
  - 不在 scope analyzer 中执行成员解析、调用解析或表达式类型推断
  - 不改变 `frontend.scope` 已冻结的 lookup 协议
  - 不把 `DiagnosticManager` 注入 `Scope` 或 shared resolver

---

## 1. 目标与职责边界

`FrontendScopeAnalyzer` 的职责已经冻结为：

- 在 skeleton phase 之后建立真实 lexical/container scope graph
- 为 AST 发布“当前 lexical scope”事实到 `scopesByAst`
- 复用 skeleton 已发布的 `FrontendModuleSkeleton` / `FrontendSourceClassRelation` / `FrontendInnerClassRelation`
- 为后续 binder/body phase 提供稳定的 scope 入口，而不是在建图阶段提前做 binding

`FrontendScopeAnalyzer` 明确不负责：

- parameter、普通 local、`for` iterator、capture、pattern binding 的写入
- 成员解析、调用解析、表达式类型推断
- shared resolver 调用与类型求值
- 普通源码错误的主路径 diagnostics
- 重新定义 `ClassScope` / `CallableScope` / `BlockScope` 的语义

---

## 2. 当前集成与发布语义

### 2.1 Frontend 主链路

当前 `FrontendSemanticAnalyzer` 的稳定顺序是：

1. `FrontendAnalysisData.bootstrap()`
2. `FrontendClassSkeletonBuilder.build(...)`
3. `analysisData.updateModuleSkeleton(moduleSkeleton)`
4. `analysisData.updateDiagnostics(diagnosticManager.snapshot())`
5. `FrontendScopeAnalyzer.analyze(...)`
6. `analysisData.updateDiagnostics(diagnosticManager.snapshot())`

这意味着：

- annotation collection 当前仍内聚在 `FrontendClassSkeletonBuilder` 内部，不是 analyzer 顶层单独暴露的 public phase
- scope phase 只在 skeleton 边界已发布之后运行
- analyze 结束后的 diagnostics 快照必须至少反映 skeleton 之后、scope phase 返回之后的 shared manager 状态

### 2.2 `FrontendAnalysisData`

`FrontendAnalysisData` 已是统一 side-table carrier，当前稳定持有：

- `annotationsByAst`
- `scopesByAst`
- `symbolBindings`
- `expressionTypes`
- `resolvedMembers`
- `resolvedCalls`

其中：

- `annotationsByAst` 由 skeleton build 内部直接写入稳定 side-table
- `moduleSkeleton` 与 `diagnostics` 通过显式 boundary publish 发布
- `scopesByAst` 由 scope phase 先构建新表，再通过 `updateScopesByAst(...)` 一次性发布

side-table 对象在 `FrontendAnalysisData.bootstrap()` 时一次性创建；后续 `updateXXX(...)` 会复制内容并清理 stale entry，而不是替换引用。

### 2.3 诊断边界

scope phase 当前显式接收 `DiagnosticManager`，但生产实现常态下不会额外发 diagnostics。

因此当前约定是：

- 若 scope phase 未命中 guard rail，`moduleSkeleton.diagnostics()` 与最终 `analysisData.diagnostics()` 会保持同一份快照
- 若 scope phase 或后续 phase 追加 diagnostics，最终 `analysisData.diagnostics()` 可以晚于 `moduleSkeleton.diagnostics()`
- resolver / scope 协议层继续不持有 `DiagnosticManager`

---

## 3. `scopesByAst` 的语义

### 3.1 冻结语义

`scopesByAst` 记录的是：

> 某个 AST 节点在后续语义分析中应使用的当前 lexical scope。

它不是“拥有 scope 的节点集合”，也不是“仅记录 boundary owner 的表”。

### 3.2 key 与存储语义

当前 side-table 已冻结为：

- key 类型：`gdparser` 的 `Node`
- 匹配语义：AST identity
- `SourceFile` 也直接作为 `Node` key 使用

### 3.3 当前覆盖方式

当前 `FrontendScopeAnalyzer` 通过 `gdparser` 的 `ASTWalker` 遍历 AST，并利用通用 `handleNode(...)` fallback 为每个真正被 walker 访问到的节点记录当前 lexical scope。

对后续工程最重要的消费点包括：

- `SourceFile`
- `ClassDeclaration`
- `FunctionDeclaration`
- `ConstructorDeclaration`
- `LambdaExpression`
- `Block`
- `IfStatement`
- `ElifClause`
- `ForStatement`
- `WhileStatement`
- `MatchStatement`
- `MatchSection`
- `VariableDeclaration`
- `Parameter`
- `TypeRef`
- 参数默认值表达式、条件表达式、loop iterable、match pattern、match guard 等表达式节点

---

## 4. Scope Graph 模型

### 4.1 已冻结的 scope 类型

frontend scope 层当前已经冻结为：

- module/global root：`ClassRegistry`
- class boundary：`ClassScope`
- function/constructor/lambda boundary：`CallableScope`
- lexical block boundary：`BlockScope`

三套 namespace 语义保持不变：

- value namespace：最近命中优先
- function namespace：最近非空层优先
- type-meta namespace：独立 lexical namespace

### 4.2 boundary 到 scope 的映射

当前实现的结构性映射如下：

- `SourceFile` -> 顶层脚本 `ClassScope`
- `ClassDeclaration` -> 独立 `ClassScope`
- `FunctionDeclaration` -> `CallableScope`
- `ConstructorDeclaration` -> `CallableScope`
- `LambdaExpression` -> `CallableScope`
- callable `body: Block` -> 独立 `BlockScope`
- 普通独立 `Block` -> `BlockScope`
- `IfStatement.body` / `ElifClause.body` / `elseBody` -> 独立 `BlockScope`
- `WhileStatement.body` -> 独立 `BlockScope`
- `ForStatement.body` -> 独立 `BlockScope`
- `MatchSection` -> 独立 branch `BlockScope`
- `MatchSection.body` 复用所属 section 的 branch `BlockScope`
- `ClassDeclaration.body` 复用 owning `ClassScope`，不额外生成 `BlockScope`

### 4.3 callable 双层结构

function / constructor / lambda 当前固定采用两层 scope：

- callable 节点本身使用 `CallableScope`
- executable body block 使用独立 `BlockScope`

这条结构必须保持，因为：

- parameter / capture 属于 callable 层
- 普通 local 属于 body block 层
- 后续 binder 若要实现声明顺序、遮蔽和 capture 规则，必须建立在这条边界之上

### 4.4 scope kind 事实

`CallableScope` 当前稳定带有 `CallableScopeKind`：

- `FUNCTION_DECLARATION`
- `CONSTRUCTOR_DECLARATION`
- `LAMBDA_EXPRESSION`

`BlockScope` 当前稳定带有 `BlockScopeKind`：

- `BLOCK_STATEMENT`
- `FUNCTION_BODY`
- `CONSTRUCTOR_BODY`
- `LAMBDA_BODY`
- `IF_BODY`
- `ELIF_BODY`
- `ELSE_BODY`
- `WHILE_BODY`
- `FOR_BODY`
- `MATCH_SECTION_BODY`

这些 kind 已经是 protocol 事实，而不是临时调试字段。后续若新增新的 lexical boundary，必须同步更新：

- scope 类本身
- scope analyzer 建图
- 对应测试
- 本文档

---

## 5. Inner Class 与 Type-Meta 发布

### 5.1 skeleton relation 是唯一事实源

inner class boundary 当前必须建立在 `FrontendModuleSkeleton.sourceClassRelations()` 之上，而不是靠 source 顺序、名字猜测或临时索引恢复。

其中：

- `FrontendSourceClassRelation` 持有每个 source file 的顶层类与同源 inner class relation
- `FrontendInnerClassRelation` 持有 inner class 的 `lexicalOwner`、`sourceName`、`canonicalName`、`FrontendSuperClassRef` 与 canonical `classDef`
- scope analyzer 与 skeleton builder 共用这套 relation 事实

### 5.2 immediate inner type-meta 发布规则

当前顶层 `ClassScope` 和每个 inner `ClassScope` 都只发布 direct inner classes 的 type-meta，不平铺全部后代。

这条规则对后续工程是强约束，因为：

- 它决定 lexical type namespace 的粒度
- 它要求 skeleton 的最小 type-scope 与真实 scope graph 保持一致
- 它避免后续 resolver 因“扁平化所有 inner classes”而偏离 lexical 可见性

### 5.3 reject / skip 规则

当前行为已经冻结为：

- 如果 top-level source unit 在 header/skeleton phase 就未进入 `moduleSkeleton.sourceClassRelations()`，scope phase 不会再为该 `SourceFile` 物化顶层 `ClassScope`
- 如果某个 inner class subtree 没有已发布 relation/classDef，scope analyzer 只跳过该 subtree，不扩大成整条 source 的失败
- 已接受的 sibling subtree 仍应继续分析

### 5.4 outer class 可见性

当前 inner class 与 outer class 的关系保持如下约束：

- outer class 继续提供 lexical type-meta 可见性
- outer class 不自动提供 unqualified value lookup
- outer class 不自动提供 unqualified function lookup

这是一条有意保持的 GDCC 差异；后续工程不得按 Godot 的 outer value/function 模型回退。

---

## 6. Deferred Binding 边界

当前 scope analyzer 只发布 lexical graph，不做 binding prefill。

仍然明确 deferred 的内容包括：

- callable parameters 的 binding 写入
- 普通 `var` / `const` 的 binding 写入
- `for` iterator 的 binding 与类型落地
- captures 的推导与写入
- `PatternBindingExpression` 的 binding 写入
- 成员解析、调用解析、表达式类型推断

这意味着当前稳定事实是：

- `Parameter` 节点必须拥有 side-table scope 记录，但这不等价于 parameter 已经能通过 `CallableScope.resolveValue(...)` 被解析
- `for` 的 `iteratorType` 与 `iterable` 在进入 loop body 之前按外层 scope 分析
- `for` body 拥有独立 `BlockScope`，但 `iterator` 仍未预填
- `MatchSection` 的 pattern、guard 与 body 共享同一个 branch scope，但 pattern binding 仍未注册成 local

后续若要实现 binder，必须在完整 scope graph 建成之后单独做 binding pass，而不是把 binding 写回夹进当前 walker。

---

## 7. Godot 对齐结论与有意差异

当前仍保留的 Godot 对齐结论是：

- analyzer 需要按 inheritance -> interface -> body 分层推进
- lexical/container graph 应先于更细粒度 binding 建立
- inner class、lambda、match branch 等结构性边界必须显式建模

当前仍保留的有意差异是：

- GDCC 不采用 Godot 的 outer class unqualified value/function 可见性模型
- GDCC 当前 type-meta 仍主要依赖 lexical parent chain；当 base class 与 outer class 同时贡献同名 type-meta 时，base-vs-outer precedence 仍未与 Godot 完全对齐

后者若要变更，应优先更新：

- `ClassScope` / type-meta 可见性协议
- `scope_type_resolver_implementation.md`
- 对应测试

而不是只在 analyzer 层做局部绕过。

---

## 8. 当前测试锚点与后续维护要求

当前与 scope analyzer 直接相关的测试锚点包括：

- `FrontendScopeAnalyzerTest`
- `FrontendSemanticAnalyzerFrameworkTest`
- `FrontendAnalysisDataTest`
- `FrontendAstSideTableTest`
- `ScopeChainTest`
- `ScopeCaptureShapeTest`
- `ScopeTypeMetaChainTest`
- `ClassScopeResolutionTest`
- `ClassScopeSignalResolutionTest`
- `FrontendInnerClassScopeIsolationTest`
- `FrontendNestedInnerClassScopeIsolationTest`
- `FrontendStaticContextValueRestrictionTest`
- `FrontendStaticContextFunctionRestrictionTest`
- `FrontendStaticContextShadowingTest`

后续工程若要改变以下任一事实，必须同步更新本文档、相关实现与测试：

- `scopesByAst` 的语义
- scope kind 枚举与 boundary 映射
- inner class relation / immediate-inner type-meta 发布规则
- deferred binding 边界
- outer type-meta 与 outer value/function 的可见性差异
