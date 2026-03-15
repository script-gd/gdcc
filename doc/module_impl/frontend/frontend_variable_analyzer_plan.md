# FrontendVariableAnalyzer MVP 实施方案（参数与普通局部变量）

> 本文档定义 `FrontendVariableAnalyzer` 的 MVP 目标、前置任务、阶段拆分、验收标准与已知风险。本文只覆盖“把参数写进 `CallableScope`、把普通局部 `var` 写进 `BlockScope`”这一轮工作，不扩展到完整 binder / body / expression typing。

## 文档状态

- 性质：实施方案 / MVP 任务分解
- 更新时间：2026-03-15
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/scope_type_resolver_implementation.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`

---

## 1. 当前代码库事实

### 1.1 已落地的 frontend 主链路

当前 `FrontendSemanticAnalyzer` 的稳定顺序已经扩展为三段：

1. `FrontendClassSkeletonBuilder.build(...)`
2. `FrontendScopeAnalyzer.analyze(...)`
3. `FrontendVariableAnalyzer.analyze(...)`

这意味着：

- `moduleSkeleton`、`diagnostics`、`scopesByAst` 已有稳定发布边界。
- `symbolBindings`、`expressionTypes`、`resolvedMembers`、`resolvedCalls` 仍然是空 side-table 预留位。
- scope phase 结束后，`CallableScope` / `BlockScope` 对象已经存在。
- variable phase 当前已经接入主链路，但在 Phase 2 里仍只负责边界校验与测试 seam，尚未开始 parameter / local binding prefill。

### 1.2 当前 scope 与 side-table 的可复用事实

当前已经具备本轮 MVP 直接可复用的基础设施：

- `FrontendAnalysisData.scopesByAst()` 已记录 declaration 节点所属 lexical scope。
- `CallableScope` 已提供 `defineParameter(...)` / `defineCapture(...)`。
- `BlockScope` 已提供 `defineLocal(...)` / `defineConstant(...)`。
- `FrontendScopeAnalyzer` 已保证：
  - `Parameter` 节点归属到对应 `CallableScope`
  - function / constructor body 归属到独立 `BlockScope`
  - 普通 block、`if` / `elif` / `else`、`while` body 归属到独立 `BlockScope`
  - `for` / `match` 的 scope graph 已建好，但 binding prefill 仍明确 deferred
  - lambda 也已有 scope graph，但本轮 MVP 不接入

### 1.3 当前缺口

当前仍有三个明确缺口，另有两个前置项已经完成：

- 已完成：声明类型解析 helper 已从 `FrontendClassSkeletonBuilder` 中抽到 `FrontendDeclaredTypeSupport`，后续 `FrontendVariableAnalyzer` 可直接复用同一套 declared-type fallback 规则。
- 已完成：`FrontendVariableAnalyzer` 骨架、`FrontendSemanticAnalyzer` 中的 variable phase 注入点，以及 framework test seam 已落地。
1. `FrontendVariableAnalyzer` 当前仍只是 phase-boundary validator，参数写入 `CallableScope` 与普通局部变量写入 `BlockScope` 仍待后续阶段实现。
2. 现有 `FrontendScopeAnalyzerTest` 中仍保留“parameter 尚未 prefill”的默认主链路锚点；等 Phase 3/4 真正接入 binding 后，需要把这类断言迁移到 scope-analyzer isolation 路径。
3. declaration-order 可见性仍未有 frontend 专用消费层；后续 binder 不能直接把 `scope.resolveValue(...)` 当作最终 use-site 结论，需单独接入 `FrontendVisibleValueResolver` 一类的前端可见性修正层。

---

## 2. 本轮 MVP 范围

### 2.1 本轮目标

本轮只实现两件事：

1. 把 `FunctionDeclaration` / `ConstructorDeclaration` 的参数写入对应 `CallableScope`。
2. 把 function / constructor body 及其嵌套普通 block、`if` / `elif` / `else`、`while` 中的普通局部 `var` 写入对应 `BlockScope`。

### 2.2 本轮明确非目标

以下内容全部不纳入本轮验收：

- `for` 语句
- `match` 表达式 / `PatternBindingExpression`
- lambda 参数、lambda 局部变量、lambda capture
- `DeclarationKind.CONST` 的 block-local `const`
- 参数默认值表达式的语义分析
- 局部变量 initializer 的类型推断
- use-site `symbolBindings` 写入
- 表达式类型、成员解析、调用解析

这里有一个需要明确写死的边界：

- 既然 `for` / `match` 不在本轮范围内，那么它们整个 subtree 内的 binding 也不作为本轮目标。
- 这意味着 `for` body / `match` section body 里的普通 `var`，本轮也应一并跳过，而不是“顺手半支持”。

### 2.3 本轮类型规则

本轮变量声明类型采用与 skeleton 一致的规则：

- `typeRef == null` -> `Variant`
- `typeRef.sourceText().trim().isEmpty()` -> `Variant`
- `typeRef.sourceText().trim().equals(":=")` -> 当前阶段临时回退为 `Variant`
- 其余显式类型文本 -> 走严格 `ScopeTypeResolver.tryResolveDeclaredType(...)`
- 解析失败 -> 发出 warning，并 fallback 到 `Variant`

这里必须明确 `:=` 的真实语义边界：

- `:=` 不等价于“语言层面的无类型变量”。
- 它的目标语义应当是：分析右侧表达式的类型，并据此确定变量声明类型。
- 当前返回 `Variant` 只是因为这一步依赖后续 `FrontendExprTypeAnalyzer` / 表达式类型解析能力。
- 在 `FrontendExprTypeAnalyzer` 落地前，skeleton / variable phase 只能把 `:=` 视为“当前无法完成真实推断的 deferred 输入”，并临时按 `Variant` 处理。

### 2.4 参数默认值的本轮策略

用户需求已经明确，本轮参数默认值一律按以下策略处理：

- 如果 `Parameter.defaultValue() != null`：
  - 发出 warning
  - 不做类型推断
  - 不做表达式绑定
  - 不改变参数本身写入 `CallableScope` 的行为

建议 warning category 先冻结为：

- `sema.unsupported_parameter_default_value`

建议消息语义：

- 明确说明“该默认值会在后续表达式类型解析分析器完成后再处理”
- 明确说明“当前阶段只发 warning 并忽略默认值语义”

### 2.5 `symbolBindings` 的本轮契约

本轮不写 `FrontendAnalysisData.symbolBindings()`。

原因必须明确：

- `FrontendBinding` 当前文档语义是“附着到 AST use-site 的 binding fact”
- 而本轮目标只是把 declaration inventory 写进 lexical scope
- 若现在把 declaration 节点也写进 `symbolBindings`，会把“scope prefill”和“真正 binder 解析”混成一层
- 现有 `FrontendSemanticAnalyzerFrameworkTest` 也明确锁定了 `symbolBindings().isEmpty()`

结论：

- 本轮完成后，参数 / 局部变量应当能通过 scope 对象被查询到
- 但 `symbolBindings()` 仍保持为空

### 2.6 同一 callable 内禁止变量遮蔽

本计划据此冻结一条额外约束：

- 同一 `FunctionDeclaration` / `ConstructorDeclaration` 内，不允许新的局部变量遮蔽：
  - parameter
  - 更外层 block local
  - 未来接入的 capture

需要明确：

- 这条规则约束的是“同一 callable 的变量绑定”，不是整个模块里所有 value namespace 的统一禁令。
- 同 scope duplicate 只是它的一个真子集；即使 nested block 中的 local 与外层 block local 不在同一个 `BlockScope`，若构成同一 callable 内的 shadowing，也应报错而不是放行。
- `ClassScope` / global root 上的 property / singleton / global enum / utility function 是否也要纳入同名禁止，不在本文当前冻结范围内。

这条约束的直接后果是：

- `FrontendVariableAnalyzer` 不能只依赖 `BlockScope.defineLocal(...)` / `CallableScope.defineParameter(...)` 的“同 scope duplicate 防线”
- 还必须在写入 `BlockScope` 之前，显式检查当前 callable 祖先链上的 parameter/local/capture 同名冲突

---

## 3. 前置任务总览

在正式写 `FrontendVariableAnalyzer` 之前，建议先完成以下前置任务。

### 3.1 提取共享的声明类型解析 helper（已完成）

必须先把 `FrontendClassSkeletonBuilder.resolveTypeOrVariant(...)` 抽成 phase-neutral helper。

原因：

- skeleton 与 variable phase 需要完全一致的 declared-type fallback 规则
- 若复制一份逻辑到新 analyzer，后续 warning category、unknown-type fallback、`:=` 处理极易漂移
- 该 helper 不应依赖 `SkeletonBuildContext`

建议形态：

- 新建 `frontend.sema` 层的小型 helper，例如 `FrontendDeclaredTypeSupport`
- 只接收：
  - `@Nullable TypeRef typeRef`
  - `@NotNull Scope declaredTypeScope`
  - `@NotNull Path sourcePath`
  - `@NotNull DiagnosticManager diagnostics`

当前状态：

- 已落地 `FrontendDeclaredTypeSupport.resolveTypeOrVariant(...)`
- `FrontendClassSkeletonBuilder` 已改为调用该 helper，不再保留私有副本

### 3.2 冻结 diagnostics 策略

在开工前需要先冻结以下诊断策略：

- duplicate parameter / duplicate local / same-callable variable shadowing 的 category 与 severity
- 参数默认值 warning 的 category 与 message 语义
- duplicate declaration 冲突时保留哪一个 binding

建议冻结为：

- duplicate parameter / local / same-callable variable shadowing：`sema.variable_binding` error
- unsupported parameter default value：`sema.unsupported_parameter_default_value` warning
- duplicate 时保留第一个已成功写入的 binding，后续同名 declaration 只发诊断、不覆盖

### 3.3 冻结测试分层

在变量 phase 接线前，先把测试边界想清楚，否则接线后会出现“scope analyzer 单测被集成 phase 行为污染”的问题。

建议冻结为：

- `FrontendScopeAnalyzerTest` 继续只验证 scope graph，不承担 variable prefill 行为验证
- `FrontendVariableAnalyzerTest` 专门验证 parameter/local prefill
- `FrontendSemanticAnalyzerFrameworkTest` 验证 phase 顺序与 diagnostics 边界刷新

### 3.4 冻结 statement-order 风险说明

即使已经冻结“同一 callable 内禁止变量遮蔽”，本轮 prefill 仍然不会自动解决“声明前引用”的顺序问题。

这是必须在实现前写死的边界，因为当前 `BlockScope` / `CallableScope` 的 lookup 是“整层命中即停止”，并不编码 statement order。

这意味着后续 binder 若直接用本轮 prefill 结果做 use-site 解析，会存在一个真实风险：

- 当前 block 中首次出现、但声明位置晚于 use-site 的 local，可能会被错误地当作已经可见
- initializer 内对“正在声明的 local 本身”的引用，可能会被错误地命中当前 declaration

因此必须先冻结以下结论：

- 本轮验收只针对“declaration inventory 已写入正确 scope”
- 不把“完整 use-site 可见性顺序正确”当成本轮目标
- 后续 binder phase 需要单独引入“声明顺序过滤”或“按位置可见的 lookup”机制
- 该机制的推荐形态见 `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`

---

## 4. 推荐设计

### 4.1 phase 位置

推荐把新 phase 接到 `FrontendSemanticAnalyzer` 中，形成：

1. skeleton
2. scope
3. variable

并保持每个阶段结束后都刷新一次 diagnostics snapshot。

这意味着 `FrontendSemanticAnalyzer` 需要新增 `FrontendVariableAnalyzer` 注入点。

### 4.2 不修改 `FrontendScopeAnalyzer` 职责

`FrontendScopeAnalyzer` 的职责边界已经冻结，不应回流 binding prefill。

`FrontendVariableAnalyzer` 应遵守以下约束：

- 只读取已发布的 `moduleSkeleton`
- 只读取已发布的 `scopesByAst`
- 不重建 scope graph
- 不新造第二套 lexical scope 推导逻辑
- 直接把 declaration 写进已有 scope 对象

### 4.3 推荐遍历方式：声明导向 walker，而不是通用 ASTWalker

本轮不建议直接复用 `FrontendScopeAnalyzer` 那种“通用 ASTWalker + 所有节点都可进入”的模式。

更稳妥的做法是：

- 从 `FrontendModuleSkeleton.sourceClassRelations()` 出发，按 source file 逐个处理
- 手工递归：
  - `SourceFile.statements()`
  - `ClassDeclaration.body().statements()`
  - `FunctionDeclaration`
  - `ConstructorDeclaration`
  - 普通 `Block`
  - `IfStatement` / `ElifClause` / `elseBody`
  - `WhileStatement`
- 显式跳过：
  - `LambdaExpression`
  - `ForStatement`
  - `MatchStatement`
- 不主动遍历 arbitrary expression subtree

这样做有三个好处：

1. 不会意外把 lambda / for / match 半做进来。
2. 不需要碰参数默认值表达式、局部变量 initializer 的内部表达式结构。
3. 更容易保证“只做 declaration inventory，不提前做 binder 工作”。

### 4.4 scope 选择必须以 `scopesByAst` 为真源

本轮不要自己维护一套“当前应该写到哪个 scope”的推导逻辑。

推荐规则：

- parameter 写入目标：`analysisData.scopesByAst().get(parameter)`，并要求它是 `CallableScope`
- local `var` 写入目标：`analysisData.scopesByAst().get(variableDeclaration)`，并要求它是 `BlockScope`

这样可以天然避免两个错误：

1. 把 class body 里的 `VariableDeclaration` 误当成 local
2. 变量 analyzer 与 scope analyzer 的 boundary drift

### 4.5 declaration object 建议直接挂 AST 节点

调用 `defineParameter(...)` / `defineLocal(...)` 时，建议把声明节点本身作为 `declaration` 参数传进去：

- parameter -> `Parameter`
- local -> `VariableDeclaration`

理由：

- 这与当前 `ScopeValue` 的 `declaration` 设计天然兼容
- 方便后续 binder / diagnostics 直接拿 declaration range
- 即使本轮不解决声明顺序问题，后续 phase 也能基于 declaration node 追加更精细逻辑

### 4.6 callable 内 shadowing 检查先于 local 写入

由于同一 callable 内禁止变量遮蔽，本轮 local prefill 不能采用“先 define，再靠异常兜底”的最小实现。

推荐顺序应当是：

1. 先定位 `VariableDeclaration` 所属 `BlockScope`
2. 再沿 lexical parent 向上检查，直到当前 callable 边界结束
3. 若命中 parameter / 更外层 local / future capture 同名绑定，则发出 `sema.variable_binding` error，并跳过该 declaration 的写入
4. 只有在 same-callable shadowing 检查通过后，才调用 `BlockScope.defineLocal(...)`

这样做有两个直接收益：

- 不会把“同 scope duplicate”和“同 callable nested shadowing”混为一类实现细节
- 后续 `FrontendVisibleValueResolver` 可以建立在“scope 中只存放合法 variable inventory”这一前提上，避免额外绕开非法声明

---

## 5. 分阶段实施清单与验收标准

### Phase 0：契约冻结与测试拆层

#### 实施清单

- 新增本文档，并把它挂到 `scope_architecture_refactor_plan.md` 的 6.7 节旁边。
- 明确写死以下非目标：
  - lambda
  - `for`
  - `match`
  - `const`
  - `symbolBindings`
  - 参数默认值语义
- 明确写死 statement-order 不是本轮验收项。
- 确定 duplicate / unsupported diagnostics 的 category 命名。
- 规划 `FrontendScopeAnalyzerTest` 与 `FrontendVariableAnalyzerTest` 的职责分界。

#### 验收标准

- 文档中对目标、非目标、诊断策略、风险边界的描述无歧义。
- 团队后续实现不需要再就“for body 要不要顺手支持”“是否要顺手写 symbolBindings”反复返工。

### Phase 1：抽出共享声明类型解析 helper（已完成）

#### 实施清单

- [x] 从 `FrontendClassSkeletonBuilder` 提取 `resolveTypeOrVariant(...)` 逻辑到共享 helper。
- [x] 保持以下行为不变：
  - [x] `null` -> `Variant`
  - [x] `:=` 当前阶段临时 -> `Variant`，真实语义 deferred 到 `FrontendExprTypeAnalyzer`
  - [x] unknown declared type -> `sema.type_resolution` warning + `Variant`
  - [x] 解析走严格 `ScopeTypeResolver.tryResolveDeclaredType(...)`
- [x] 让 skeleton phase 改为调用新 helper，而不是保留一份私有副本。

已完成产出：

- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendDeclaredTypeSupport.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendDeclaredTypeSupportTest.java`
- skeleton / framework 回归测试继续覆盖该 helper 的集成行为

#### 验收标准

- [x] `FrontendClassSkeletonBuilder` 的类型解析外部行为不发生变化。
- [x] 现有 skeleton 相关测试继续通过。
- [x] 新 helper 不依赖 `SkeletonBuildContext`。

### Phase 2：`FrontendVariableAnalyzer` 骨架与主链路接线

#### 实施清单

- [x] 新增 `FrontendVariableAnalyzer`。
- [x] 为它提供独立 public 入口，接收：
  - [x] `FrontendAnalysisData`
  - [x] `DiagnosticManager`
- [x] 在 `FrontendSemanticAnalyzer` 中接入 variable phase。
- [x] 在 variable phase 返回后刷新 `analysisData.updateDiagnostics(...)`。
- [x] 为测试预留可注入 seam：
  - [x] 默认构造器使用真实 `FrontendVariableAnalyzer`
  - [x] 单测可注入 probe analyzer

已完成产出：

- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendVariableAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendVariableAnalyzerTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendSemanticAnalyzerFrameworkTest.java`

#### 验收标准

- [x] 默认 `FrontendSemanticAnalyzer` 已形成 `skeleton -> scope -> variable` 主链路。
- [x] diagnostics 最终快照能够覆盖 variable phase 新增的 warning/error。
- [x] 不需要改动 `FrontendScopeAnalyzer` 职责。

### Phase 3：参数写入 `CallableScope`

#### 实施清单

- 只处理 `FunctionDeclaration` 与 `ConstructorDeclaration`。
- 对每个 `Parameter`：
  - 从 `scopesByAst` 取到对应 `CallableScope`
  - 用共享 helper 求 declared type
  - 调用 `CallableScope.defineParameter(parameter.name().trim(), type, parameter)`
- 若 `parameter.defaultValue() != null`：
  - 发出 `sema.unsupported_parameter_default_value` warning
  - 不继续分析默认值表达式
- duplicate parameter：
  - 捕获 `defineParameter(...)` 的 duplicate failure
  - 转成 `sema.variable_binding` error
  - 保留第一个 binding，继续处理同 callable 的其余参数
- 显式跳过 `LambdaExpression.parameters()`，不纳入本轮

#### 验收标准

- function / constructor 的参数可通过对应 `CallableScope.resolveValue(...)` 命中。
- 未标注类型参数默认得到 `Variant`。
- unknown type 参数会发出与 skeleton 一致的 `sema.type_resolution` warning。
- 带默认值表达式的参数会收到 warning，但参数本身仍成功写入 `CallableScope`。
- lambda 参数仍不可通过 scope 被解析到。
- `symbolBindings()` 仍为空。

### Phase 4：普通局部 `var` 写入 `BlockScope`

#### 实施清单

- 只处理 `VariableDeclaration.kind() == DeclarationKind.VAR`。
- 只在 `scopesByAst.get(variableDeclaration) instanceof BlockScope` 时写入：
  - class body property declaration 一律跳过
  - top-level script property declaration 一律跳过
- 使用共享 helper 解析显式类型。
- 无显式类型时直接写 `Variant`，不看 initializer expression。
- 递归覆盖：
  - function / constructor body
  - 普通嵌套 block
  - `if` / `elif` / `else` body
  - `while` body
- 显式跳过：
  - `ForStatement` 整个 subtree
  - `MatchStatement` 整个 subtree
  - `LambdaExpression` 整个 subtree
  - `DeclarationKind.CONST`
- duplicate local：
  - 包括同 scope duplicate 与同 callable nested shadowing 两类非法声明
  - 对 nested shadowing，需在 `defineLocal(...)` 前显式检查并诊断
  - 对同 scope duplicate，保留 `defineLocal(...)` 的底层保护并转成 `sema.variable_binding` error
  - 保留第一个合法 binding，跳过后续冲突 declaration

#### 验收标准

- 普通函数 / 构造器 body 中的 `var` 可通过所属 `BlockScope.resolveValue(...)` 命中。
- 嵌套 block / `if` / `while` 中的 `var` 会进入各自独立 `BlockScope`。
- class property 不会被误写成 local。
- `for` / `match` / lambda / `const` 仍保持 deferred。
- 未标注类型 local 默认为 `Variant`。

### Phase 5：测试收口与文档回填

### 实施清单

- 新增 `FrontendVariableAnalyzerTest`，覆盖：
  - parameter prefill
  - constructor parameter prefill
  - untyped local -> `Variant`
  - typed local strict resolution
  - duplicate parameter/local diagnostics
  - default parameter warning + ignore
  - skip lambda / for / match / const
- 更新 `FrontendSemanticAnalyzerFrameworkTest`：
  - 验证 variable phase 顺序与 diagnostics refresh
  - 继续断言 `symbolBindings()` / `expressionTypes()` / `resolvedMembers()` / `resolvedCalls()` 为空
- 调整 `FrontendScopeAnalyzerTest`：
  - scope-analyzer-only 测试不再依赖默认全量 `FrontendSemanticAnalyzer`
  - 原先“parameter 尚未 prefill”的断言移动到 scope-analyzer isolation 路径
- 如有必要，更新：
  - `scope_architecture_refactor_plan.md`
  - `scope_analyzer_implementation.md`

### 验收标准

- scope graph 测试与 variable prefill 测试的责任边界清晰，不互相污染。
- 默认 analyzer 主链路测试能看到 variable phase 效果。
- 现有 skeleton / scope 行为未被意外回归。

---

## 6. 建议测试矩阵

建议最少覆盖以下场景。

### 6.1 参数

- `func ping(value: int, alias):`
  - `value -> int`
  - `alias -> Variant`
- `func _init(seed: MissingType):`
  - `seed -> Variant`
  - 产生 `sema.type_resolution`
- `func ping(value, alias = value):`
  - `value` / `alias` 均写入 `CallableScope`
  - 产生 `sema.unsupported_parameter_default_value`

### 6.2 局部变量

- function body 中：
  - `var typed_local: int = value`
  - `var inferred_local := value`
  - `var plain_local`
- nested `if` / `while` body 中：
  - local 进入对应 branch/loop `BlockScope`
- class body 中：
  - `var hp: int`
  - 不应被写入 `BlockScope`

### 6.3 deferred 边界

- `for item in values: var x := item`
  - `item` 不写入
  - `x` 本轮也不写入
- `match value: var bound: var x := bound`
  - `bound` 不写入
  - `x` 本轮也不写入
- `var f := func(arg): var inner := arg`
  - `f` 若位于普通 block，可作为 local 写入当前 `BlockScope`
  - `arg` / `inner` 不写入 lambda 对应 scope
- `const answer := 42`
  - 本轮不写入

### 6.4 duplicate diagnostics

- 同一 callable 中重复参数名
- 同一 block 中重复 local 名
- inner block local 遮蔽 outer block local，应报 `sema.variable_binding`
- inner block local 遮蔽 parameter，应报 `sema.variable_binding`

---

## 7. 已知风险与不足

### 7.1 最大风险：声明顺序未编码

这是本轮最需要明确暴露的真实风险。

当前 scope prefill 完成后，只能表达“这个 scope 拥有哪些 declaration”，还不能表达“某个 use-site 在这个位置上到底看得到哪些 declaration”。

典型风险形态：

- 当前 block 中首次声明 `value`
- 某个 use-site 位于 `value` 的 declaration 之前
- 或某个 use-site 位于 `var value = value` 的 initializer 内部
- 后续 binder 若直接依赖 `resolveValue(...)`，会把“尚未生效”的当前 declaration 当成已可见绑定

结论：

- 本轮只能交付 declaration inventory
- 后续 binder 必须补 statement-order 可见性机制
- 该机制的建议实现与行为说明见 `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`

### 7.2 partial subtree 支持是有意行为

由于 `for` / `match` / lambda 被刻意跳过，本轮结束后会形成“同一个函数体里一部分 block local 已可见，另一部分仍 deferred”的中间态。

这是可接受的，但必须在文档与测试中明确写死，避免后续误判为 bug。

### 7.3 in-place enrich 已发布 scope 对象

本轮不会重建 `scopesByAst`，而是就地 enrich 已发布的 `Scope` 对象。

这本身不是问题，但要求后续 phase 不能假设：

- “scope phase 发布后 scope 内容永远不再变化”

真正稳定的边界应理解为：

- scope graph 结构在 scope phase 后稳定
- declaration inventory 在 variable phase 后进一步稳定

### 7.4 duplicate 不能依赖异常中断整条链路

`CallableScope.defineParameter(...)` / `BlockScope.defineLocal(...)` 的异常只应该作为底层保护，不应成为普通源码错误的主流程。

variable phase 必须把这类冲突转成 diagnostics + continue，而不是让整个 module analyze 提前失败。

---

## 8. 建议触达文件

本轮预计会触达如下文件。

### 8.1 生产代码

- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendVariableAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- 新增共享 declared-type helper，例如：
  - `src/main/java/dev/superice/gdcc/frontend/sema/FrontendDeclaredTypeSupport.java`

### 8.2 测试

- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendVariableAnalyzerTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendSemanticAnalyzerFrameworkTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendScopeAnalyzerTest.java`
- 如 helper 抽取影响 skeleton 路径，可能还需要：
  - `src/test/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonTest.java`

### 8.3 文档

- `doc/module_impl/frontend/frontend_variable_analyzer_plan.md`
- `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
- `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
- 如需同步事实源：
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`

---

## 9. 最终实施建议

如果按风险最小化顺序推进，建议严格按下面顺序开发：

1. 先抽共享类型解析 helper，确保 skeleton 行为零漂移。
2. 再接 `FrontendVariableAnalyzer` phase 骨架与 framework test seam。
3. 先做参数，再做普通局部 `var`。
4. 最后再收口测试与文档。

不要反过来先把 local prefill 一口气做进主链路，否则一旦 duplicate diagnostics、scope 测试边界、shared type fallback 其中任何一项没有先冻结，返工面会明显扩大。
