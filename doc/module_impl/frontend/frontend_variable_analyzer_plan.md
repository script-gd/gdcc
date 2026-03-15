# FrontendVariableAnalyzer MVP 实施方案（参数与普通局部变量）

> 本文档定义 `FrontendVariableAnalyzer` 的 MVP 目标、恢复合同、阶段拆分、验收标准与测试锚点。本文只覆盖“把参数写进 `CallableScope`、把普通局部 `var` 写进 `BlockScope`”这一轮工作，不扩展到完整 binder / body / expression typing。

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
- variable phase 当前已经接入主链路，并已完成：
  - function / constructor parameter prefill
  - function / constructor body 及 supported nested block 中的 ordinary local `var` prefill
- lambda inventory、`for` / `match` / `const` 仍保持 deferred。

### 1.2 当前 scope 与 side-table 的可复用事实

当前已经具备本轮 MVP 可以直接复用的基础设施：

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

当前仍有以下缺口：

1. `for` / `match` / lambda / block-local `const` 仍未接入 variable inventory，相关 subtree 仍需保持 deferred。
2. declaration-order 可见性仍未有 frontend 专用消费层；后续 binder 不能直接把 `scope.resolveValue(...)` 当作最终 use-site 结论，需单独接入 `FrontendVisibleValueResolver`。

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

- 既然 `for` / `match` / lambda 不在本轮范围内，那么这些 subtree 内的 binding 也不作为本轮目标。
- 这意味着 `for` body / `match` section body / lambda body 里的普通 `var`，本轮也应一并跳过，而不是“顺手半支持”。

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

本轮参数默认值一律按以下策略处理：

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

结论必须明确：

- 本轮完成后，参数 / 局部变量应当能通过 scope 对象被查询到
- 但 `symbolBindings()` 仍保持为空

### 2.6 同一 callable 内禁止变量遮蔽

当前 frontend 的 MVP 语义仍冻结为：

- 同一 `FunctionDeclaration` / `ConstructorDeclaration` 内，不允许新的局部变量遮蔽：
  - parameter
  - 更外层 block local
  - 未来接入的 capture

需要明确：

- 这是当前 MVP 的变量绑定政策，不等价于长期的 Godot warning/error 分级合同。
- 本轮仍按 error 处理这类冲突，但 category 设计必须保留后续拆分空间，不能把“duplicate”和“shadowing”永久捆死在同一语义层。

---

## 3. 恢复合同与失败边界

本节是本次重写后最需要冻结的部分。

### 3.1 哪些情况允许抛异常

只有以下 guard rail 允许抛异常：

- `FrontendAnalysisData` 尚未发布 `moduleSkeleton`
- `FrontendAnalysisData` 尚未发布 pre-variable diagnostics boundary
- `moduleSkeleton` 中已接受的 source file 在 variable phase 启动时仍没有顶层 `SourceFile -> ClassScope` 记录

这些情况属于 phase-order / side-table 不变量损坏，而不是普通源码错误。

### 3.2 哪些情况必须走“skip and continue”

以下情况不允许因为单个节点中断整个 module analyze：

- 手工 walker 走到某个 AST subtree，但该 subtree 在前序 phase 中已经被 skeleton/scope recovery 跳过
- 某个 parameter/local declaration 没有 `scopesByAst` 记录
- 某个 declaration 的 target scope 存在，但不是本阶段期望的 `CallableScope` / `BlockScope`
- 节点位于本轮明确 deferred 的 subtree 中

这些情况的恢复合同冻结为：

- 当前 phase 必须跳过当前 declaration 或当前 subtree
- 继续处理同一 module 中其他仍可恢复的 subtree
- 不把普通负路径重新升级成整模块失败

### 3.3 缺 scope 记录的具体语义

对于 parameter/local declaration：

- 如果 `scopesByAst` 中没有该节点：
  - 优先解释为“前序 phase 已经跳过该坏 subtree”
  - 当前 phase 必须跳过当前 declaration
  - 不再额外升级为异常

是否发新 diagnostic 的约束：

- 如果缺 scope 已经是前序 phase recovery 的自然结果，当前 phase 不要求重复发诊断
- 只有当实现者能够稳定判断“这是 supported subtree 内的 binding target 丢失”时，才允许补一条 `sema.variable_binding` 诊断后跳过

### 3.4 scope 类型不符的具体语义

对于 supported subtree 内的 declaration：

- parameter 目标 scope 应为 `CallableScope`
- ordinary local `var` 目标 scope 应为 `BlockScope`

若不满足：

- 当前 phase 应发一条 `sema.variable_binding` 诊断并跳过当前 declaration
- 不覆盖已有 binding
- 不阻断同一 callable 或同一 source file 的其余合法 declaration

这条约束的目的不是把实现 bug 静默吞掉，而是保证 frontend 继续遵守 `frontend_rules.md` 的“diagnostic + skip subtree / skip node”恢复约定。

---

## 4. 推荐设计

### 4.1 phase 位置

`FrontendVariableAnalyzer` 继续保持在 `FrontendSemanticAnalyzer` 中的第三阶段：

1. skeleton
2. scope
3. variable

并保持每个阶段结束后都刷新一次 diagnostics snapshot。

### 4.2 不修改 `FrontendScopeAnalyzer` 职责

`FrontendScopeAnalyzer` 的职责边界已经冻结，不应回流 binding prefill。

`FrontendVariableAnalyzer` 应遵守以下约束：

- 只读取已发布的 `moduleSkeleton`
- 只读取已发布的 `scopesByAst`
- 不重建 scope graph
- 不新造第二套 lexical scope 推导逻辑
- 直接把 declaration 写进已有 scope 对象

### 4.3 声明导向 walker 是当前 MVP 的临时工程策略

当前仍建议使用“声明导向 walker”，但这里必须明确把它写成临时工程策略，而不是长期架构建议。

当前策略：

- 从 `FrontendModuleSkeleton.sourceClassRelations()` 出发，按 accepted source file 逐个处理
- 只显式进入当前支持的 declaration 容器：
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

但必须额外冻结三条约束：

1. 这是 MVP 过渡方案，不代表后续 binder/body 也应维持第二套结构遍历知识。
2. 凡是 walker 的结构判断与 `scopesByAst`、`FrontendSourceClassRelation` 冲突时，以后两者为真源。
3. walker 一旦进入缺 relation / 缺 scope 的 subtree，必须按恢复合同跳过，而不是试图“靠结构猜回来”。

### 4.4 scope 选择必须以 `scopesByAst` 为真源

本轮不要自己维护一套“当前应该写到哪个 scope”的推导逻辑。

推荐规则：

- parameter 写入目标：`analysisData.scopesByAst().get(parameter)`
- local `var` 写入目标：`analysisData.scopesByAst().get(variableDeclaration)`

scope 选择之后再做两步判断：

1. 是否落在当前支持的 subtree 中
2. scope 类型是否满足本阶段期望

若任一条件不满足，按恢复合同 `diagnostic + skip` 或直接 skip。

### 4.5 declaration object 直接挂 AST 节点

调用 `defineParameter(...)` / `defineLocal(...)` 时，继续建议把声明节点本身作为 `declaration` 参数传进去：

- parameter -> `Parameter`
- local -> `VariableDeclaration`

理由：

- 这与当前 `ScopeValue.declaration()` 设计天然兼容
- 方便后续 binder / diagnostics 直接拿 declaration range
- 后续 `FrontendVisibleValueResolver` 可以直接基于 declaration node 产生被过滤命中的 provenance

### 4.6 same-callable shadowing 检查先于 local 写入

由于同一 callable 内禁止变量遮蔽，本轮 local prefill 不能采用“先 define，再靠异常兜底”的最小实现。

推荐顺序：

1. 先定位 `VariableDeclaration` 所属 `BlockScope`
2. 再沿 lexical parent 向上检查，直到当前 callable 边界结束
3. 若命中 parameter / 更外层 local / future capture 同名绑定，则发出 shadowing 诊断并跳过该 declaration 的写入
4. 只有在 same-callable shadowing 检查通过后，才调用 `BlockScope.defineLocal(...)`

---

## 5. 诊断策略

### 5.1 当前 MVP category 冻结

当前仍建议使用以下 category：

- duplicate parameter / duplicate local / same-callable variable shadowing / binding target 不可用：
  - `sema.variable_binding`
- unsupported parameter default value：
  - `sema.unsupported_parameter_default_value`
- unsupported deferred variable-inventory source（当前覆盖 lambda / `for` / `match` / block-local `const`）：
  - `sema.unsupported_variable_inventory_subtree`

### 5.2 这不是永久分类

这里必须明确：

- `sema.variable_binding` 是当前 MVP 的临时收口，而不是长期 warning/error 分层合同。
- 后续若要向 Godot warning 体系靠拢，至少可能拆开：
  - duplicate declaration
  - callable-local shadowing
  - binding target unavailable / variable phase recovery

### 5.3 message 模板必须先分开

即使暂时共用一个 category，也建议消息模板区分开：

- duplicate parameter
- duplicate local
- local shadows parameter
- local shadows outer local
- declaration target scope missing
- declaration target scope kind mismatch

这样后续若拆 category / severity，不需要同时重写 category 和消息语义。

---

## 6. 分阶段实施清单与验收标准

### Phase 0：契约冻结与测试拆层

#### 实施清单

- [x] 冻结本文档的目标、非目标、恢复合同与测试分层。
- [x] 明确 statement-order 不是本轮 variable phase 的验收项。
- [x] 明确 deferred subtree 不在本轮支持范围。

#### 验收标准

- [x] 团队后续实现不需要再就“缺 scope 记录怎么办”“deferred subtree 是否可以静默回退”重新讨论。

### Phase 1：抽出共享声明类型解析 helper（已完成）

#### 实施清单

- [x] 从 `FrontendClassSkeletonBuilder` 提取 `resolveTypeOrVariant(...)` 逻辑到共享 helper。
- [x] 保持以下行为不变：
  - [x] `null` -> `Variant`
  - [x] `:=` 当前阶段临时 -> `Variant`
  - [x] unknown declared type -> `sema.type_resolution` warning + `Variant`
  - [x] 解析走严格 `ScopeTypeResolver.tryResolveDeclaredType(...)`
- [x] 让 skeleton phase 改为调用新 helper，而不是保留一份私有副本。

#### 验收标准

- [x] `FrontendClassSkeletonBuilder` 的类型解析外部行为不发生变化。
- [x] 现有 skeleton 相关测试继续通过。
- [x] 新 helper 不依赖 `SkeletonBuildContext`。

### Phase 2：`FrontendVariableAnalyzer` 骨架与主链路接线（已完成）

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

#### 验收标准

- [x] 默认 `FrontendSemanticAnalyzer` 已形成 `skeleton -> scope -> variable` 主链路。
- [x] diagnostics 最终快照能够覆盖 variable phase 新增的 warning/error。
- [x] 不需要改动 `FrontendScopeAnalyzer` 职责。

### Phase 3：参数写入 `CallableScope`（已完成）

#### 实施清单

- [x] 只处理 `FunctionDeclaration` 与 `ConstructorDeclaration`。
- [x] walker 只进入 supported callable subtree，不进入 lambda。
- [x] 对每个 `Parameter`：
  - [x] 从 `scopesByAst` 取目标 scope
  - [x] 若缺记录：按恢复合同 skip 当前参数
  - [x] 若 scope 不是 `CallableScope`：发 `sema.variable_binding` 并 skip
  - [x] 用共享 helper 求 declared type
  - [x] 调用 `CallableScope.defineParameter(parameter.name().trim(), type, parameter)`
- [x] 若 `parameter.defaultValue() != null`：
  - [x] 发出 `sema.unsupported_parameter_default_value` warning
  - [x] 不继续分析默认值表达式
- [x] duplicate parameter：
  - [x] 保留第一个成功写入的 binding
  - [x] 后续同名 declaration 只发 diagnostic，不覆盖

#### 验收标准

- [x] function / constructor 的参数可通过对应 `CallableScope.resolveValue(...)` 命中。
- [x] 未标注类型参数默认得到 `Variant`。
- [x] unknown type 参数会发出与 skeleton 一致的 `sema.type_resolution` warning。
- [x] 带默认值表达式的参数会收到 warning，但参数本身仍成功写入 `CallableScope`。
- [x] lambda 参数仍不可通过 scope 被解析到。
- [x] `symbolBindings()` 仍为空。

### Phase 4：普通局部 `var` 写入 `BlockScope`（已完成）

#### 实施清单

- [x] 只处理 `VariableDeclaration.kind() == DeclarationKind.VAR`。
- [x] 只在 supported executable subtree 中处理：
  - [x] function / constructor body
  - [x] 普通嵌套 block
  - [x] `if` / `elif` / `else` body
  - [x] `while` body
- [x] 显式跳过：
  - [x] `ForStatement` 整个 subtree
  - [x] `MatchStatement` 整个 subtree
  - [x] `LambdaExpression` 整个 subtree
  - [x] `DeclarationKind.CONST`
- [x] 对每个 ordinary local：
  - [x] 从 `scopesByAst` 取目标 scope
  - [x] 若缺记录：按恢复合同 skip 当前 declaration
  - [x] 若 scope 不是 supported `BlockScope`：发 `sema.variable_binding` 并 skip
  - [x] 用共享 helper 解析显式类型
  - [x] 无显式类型时直接写 `Variant`，不看 initializer expression
- [x] same-callable no-shadowing：
  - [x] 在 `defineLocal(...)` 前显式检查 parameter / outer local / future capture 冲突
  - [x] 冲突时发 diagnostic 并 skip
- [x] duplicate local：
  - [x] 保留 `defineLocal(...)` 的底层保护
  - [x] 转成 `sema.variable_binding` error

#### 验收标准

- [x] 普通函数 / 构造器 body 中的 `var` 可通过所属 `BlockScope.resolveValue(...)` 命中。
- [x] 嵌套 block / `if` / `while` 中的 `var` 会进入各自独立 `BlockScope`。
- [x] class property 不会被误写成 local。
- [x] `for` / `match` / lambda / `const` 仍保持 deferred。
- [x] 未标注类型 local 默认为 `Variant`。

### Phase 5：测试收口与文档回填

#### 实施清单

- [x] `FrontendVariableAnalyzerTest` 覆盖：
  - [x] parameter prefill
  - [x] constructor parameter prefill
  - [x] duplicate parameter diagnostics
  - [x] default parameter warning + ignore
  - [x] skip lambda
  - [x] untyped local -> `Variant`
  - [x] typed local strict resolution
  - [x] duplicate local diagnostics
  - [x] local shadows parameter / outer local
  - [x] skip `for` / `match` / `const`
- [x] 新增恢复路径测试：
  - [x] bad inner class / skipped subtree 不影响同 module 其他合法 subtree
  - [x] declaration 缺 scope 记录时当前 phase 会 skip，不会中断整模块
  - [x] declaration target scope kind mismatch 时 diagnostic + skip
- [x] 更新 `FrontendSemanticAnalyzerFrameworkTest`：
  - [x] 验证 variable phase 顺序与 diagnostics refresh
  - [x] 验证默认主链路能观察到 local binding 生效
  - [x] 继续断言 `symbolBindings()` / `expressionTypes()` / `resolvedMembers()` / `resolvedCalls()` 为空
- [x] 调整 `FrontendScopeAnalyzerTest`：
  - [x] scope-analyzer-only 测试不再依赖默认全量 `FrontendSemanticAnalyzer`
  - [x] 原先“parameter 尚未 prefill”的断言移动到 scope-analyzer isolation 路径

#### 验收标准

- [x] scope graph 测试与 variable prefill 测试的责任边界清晰，不互相污染。
- [x] 默认 analyzer 主链路测试能看到 variable phase 效果。
- [x] 负路径测试能锚定“不会静默成功，也不会轻易撞死整模块”。

---

## 7. 建议测试矩阵

### 7.1 参数

- `func ping(value: int, alias):`
  - `value -> int`
  - `alias -> Variant`
- `func _init(seed: MissingType):`
  - `seed -> Variant`
  - 产生 `sema.type_resolution`
- `func ping(value, alias = value):`
  - `value` / `alias` 均写入 `CallableScope`
  - 产生 `sema.unsupported_parameter_default_value`

### 7.2 局部变量

- function body 中：
  - `var typed_local: int = value`
  - `var inferred_local := value`
  - `var plain_local`
- nested `if` / `while` body 中：
  - local 进入对应 branch/loop `BlockScope`
- class body 中：
  - `var hp: int`
  - 不应被写入 `BlockScope`

### 7.3 恢复路径

- bad inner class 在 skeleton/scope 已跳过后：
  - variable phase 不重新把整个 module 撞死
  - 同文件或同模块中的其他合法 callable/local 仍继续工作
- 某个 declaration 缺 `scopesByAst` 记录：
  - 当前 declaration 被跳过
  - 其余 declaration 继续处理
- declaration target scope kind mismatch：
  - 产生 `sema.variable_binding`
  - 当前 declaration 被跳过

### 7.4 deferred 边界

- `for item in values: var x := item`
  - `item` 不写入
  - `x` 本轮也不写入
  - 应发出 `sema.unsupported_variable_inventory_subtree` warning，明确说明 loop iterator binding 与 loop body locals 仍 deferred
- `match value: var bound when bound > 0: var x := bound`
  - `bound` 不写入
  - `x` 本轮也不写入
  - 应发出 `sema.unsupported_variable_inventory_subtree` warning，明确说明 pattern bindings 与 section locals 仍 deferred
- `var f := func(arg): var inner := arg`
  - `f` 若位于普通 block，可作为 local 写入当前 `BlockScope`
  - `arg` / `inner` 不写入 lambda 对应 scope
  - 应发出 `sema.unsupported_variable_inventory_subtree` warning，明确说明 lambda parameters / default values / locals / captures 仍 deferred
- `const answer := 42`
  - 本轮不写入
  - 应发出 `sema.unsupported_variable_inventory_subtree` warning，明确说明 block-local `const` 仍 deferred

### 7.5 duplicate / shadowing diagnostics

- 同一 callable 中重复参数名
- 同一 block 中重复 local 名
- inner block local 遮蔽 outer block local，应报当前 MVP 的 shadowing error
- inner block local 遮蔽 parameter，应报当前 MVP 的 shadowing error

---

## 8. 已知风险与不足

### 8.1 最大风险：声明顺序未编码

本轮 variable phase 只能交付 declaration inventory，不能交付完整 use-site visibility。

典型风险：

- 当前 block 中首次声明 `value`
- use-site 位于 declaration 之前
- 或 use-site 位于 `var value = value` 的 initializer 内部
- 若后续 binder 直接依赖 `resolveValue(...)`，会把“尚未生效”的当前 declaration 当成已可见绑定

后续必须接入 `FrontendVisibleValueResolver`，而不是直接复用裸 `Scope` lookup。

### 8.2 手工 walker 存在 drift 风险

声明导向 walker 只是当前 MVP 的工程折中。

只要未来 `FrontendScopeAnalyzer` 新增 lexical boundary，而 variable phase 没同步更新，手工 walker 就可能：

- 漏遍历新的 supported subtree
- 错入本应继续 deferred 的 subtree
- 重新走进前序 phase 已跳过的坏 subtree

因此本方案必须持续把 `source relation + scopesByAst` 写成真源，而不是把 walker 本身写成事实源。

### 8.3 in-place enrich 已发布 scope 对象

本轮不会重建 `scopesByAst`，而是就地 enrich 已发布的 `Scope` 对象。

稳定边界应理解为：

- scope graph 结构在 scope phase 后稳定
- declaration inventory 在 variable phase 后进一步稳定

### 8.4 当前 diagnostics category 仍然偏粗

当前把 duplicate、shadowing、binding target unavailable 暂时收敛到 `sema.variable_binding` 是为了先完成 MVP，不代表长期最优设计。

后续若向 Godot warning 体系靠拢，或需要更细粒度 lint 分级，需要拆分 category / severity。

---

## 9. 建议触达文件

### 9.1 生产代码

- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendVariableAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendDeclaredTypeSupport.java`

### 9.2 测试

- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendVariableAnalyzerTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendSemanticAnalyzerFrameworkTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendScopeAnalyzerTest.java`

### 9.3 文档

- `doc/module_impl/frontend/frontend_variable_analyzer_plan.md`
- `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
- 如需同步事实源：
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
