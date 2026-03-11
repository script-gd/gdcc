# Frontend Scope Analyzer 实施计划

> 本文档作为 `frontend.sema.analyzer` 中 scope analyzer 的专项实施计划，定义 scope analyzer 的职责边界、`Node -> Scope` side-table 建图规则、与 Godot 的对齐结论、分阶段实施步骤，以及每个阶段的验收细则。

## 文档状态

- 状态：Phase 0 / Phase 1 / Phase 2 已完成（scope phase 骨架、总控接线与 callable 主干 scope graph 已落地，Phase 3+ 待继续实现）
- 更新时间：2026-03-11
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/frontend/diagnostic/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
  - `src/test/java/dev/superice/gdcc/frontend/scope/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`

---

## 1. 背景与目标

当前 frontend 已经完成两块基础工作：

- parser 产出 `FrontendSourceUnit`
- `FrontendSemanticAnalyzer` 完成注解收集与模块骨架分析，并把结果发布到 `FrontendAnalysisData`

但 scope 相关工作仍然停留在“协议与测试已经冻结、生产代码尚未接线”的状态：

- `frontend.scope` 已有 `ClassScope`、`CallableScope`、`BlockScope`
- `FrontendAnalysisData` 已预留 `scopesByAst()` side-table
- 生产代码中仍没有任何阶段为 AST 实际建立 scope graph，也没有任何地方向 `scopesByAst()` 写入事实

因此，下一个 frontend 语义阶段应当是一个专门的 scope analyzer，其目标不是一次性完成全部 binding，而是在已经完成：

- annotation collection
- module/class skeleton
- diagnose phase boundary publication

之后，为 AST 建立可被后续 binder/body phase 消费的 scope side-table。

本计划冻结的总目标如下：

1. 在 `FrontendSemanticAnalyzer` 中接入 scope analyzer，使 analyze 结果不再只有 annotations 和 skeleton，而是额外拥有 `Node -> Scope` 的 side-table。
2. scope analyzer 只负责建立 lexical/container scope graph，并把 AST 的当前 lexical scope 事实发布到统一 side-table。
3. 变量绑定事实（包括 callable parameters、普通 locals、captures、pattern bindings）以及更多 type-meta、成员绑定结果等内容，继续由完整 scope graph 建成后的后续 binder/body phase 渐进填充。
4. 整体语义必须与 `scope_architecture_refactor_plan.md` 已冻结的 scope 协议保持一致，不能在 analyzer 中重新发明一套新规则。

---

## 2. 当前事实与约束

### 2.1 已有 frontend 分析链路

当前 `FrontendSemanticAnalyzer` 的职责是：

- bootstrap 一份共享 `FrontendAnalysisData`
- 调用 `FrontendClassSkeletonBuilder`
- 在 skeleton 之后调用独立的 `FrontendScopeAnalyzer`
- 用显式 `updateXXX(...)` 方法回写阶段结果

当前它已经拥有独立的 scope phase worker，但该 worker 仍是 Phase 1 骨架实现，尚未开始 AST scope 建图。

### 2.2 `FrontendAnalysisData` 已是统一 side-table 载体

`FrontendAnalysisData` 已稳定拥有以下 side-table：

- `annotationsByAst`
- `scopesByAst`
- `symbolBindings`
- `expressionTypes`
- `resolvedMembers`
- `resolvedCalls`

其中 `annotationsByAst`、阶段边界 diagnostics，以及 scope phase 的显式空 `scopesByAst` 发布已在生产代码中落地。`scopesByAst` 当前仍不承载真实 scope facts，但它的存在已经说明 scope analyzer 的结果应当写入这里，而不是再新建一份并行结构。

### 2.3 `frontend.scope` 的协议已经冻结

根据现有实现和测试，frontend scope 层已经冻结为：

- module/global root：`ClassRegistry`
- class：`ClassScope`
- function/constructor/lambda：`CallableScope`
- lexical block：`BlockScope`

同时，三套 namespace 的语义也已经冻结：

- value namespace：最近命中优先
- function namespace：最近非空层优先
- type-meta namespace：独立 lexical namespace

scope analyzer 只能实例化并连接这些对象，不能改写它们的协议。

### 2.4 诊断管理继续服从 shared manager 规则

scope analyzer 作为 `frontend.sema` 的 phase worker，必须延续 `diagnostic_manager.md` 中已经冻结的约束：

- 显式接收 `DiagnosticManager`
- 不在 `Scope` / shared resolver 中注入 manager
- 自己只负责在需要时把诊断写入 manager
- 阶段结果仍由 `FrontendAnalysisData` 的显式 `updateXXX(...)` 方法写回

### 2.5 与用户要求一致的职责边界

本次 scope analyzer 的目标不是“构建最终完整的 scope 内容”，而是“先建立 AST 的 lexical scope 框架”。

因此必须明确以下边界：

- 要做：
  - 建立 scope graph
  - 把 scope 挂到 AST side-table
  - 为后续 binder 提供稳定的“当前 scope”入口
  - 为完整 scope graph 建成后的独立 binding pass 提供稳定输入
- 不做：
  - 任何变量绑定写入 `Scope`，包括 callable parameters
  - 普通局部变量的完整绑定
  - capture 推导
  - pattern binding 的完整定义与作用域判定
  - 成员/调用解析
  - 类型推断
  - 除必要 guard rail 外的大规模 diagnostics

---

## 3. Godot 对齐结论与有意差异

### 3.1 Godot 的可复用结论

参考 Godot 当前 `modules/gdscript/gdscript_analyzer.*` 与 `gdscript_parser.h` 的实现，可以确认以下结论：

- analyzer 按 inheritance -> interface -> body 的顺序分阶段推进
- parser/analyzer 会先建立 lexical/container 结构，再逐步填充局部绑定信息
- suite/block 级节点显式携带 locals、parent_function、loop context 等信息

这与 GDCC 当前的 frontend 演进方向一致：

- skeleton phase 先产出类与成员骨架
- scope analyzer 在此基础上补 lexical scope graph
- binder/body phase 再继续填充 bindings、types、resolved members/calls

### 3.2 GDCC 不照搬 Godot 的部分

虽然 Godot 会把 outer class 也纳入 current-scope class chain，但 GDCC 的 `ClassScope` 相关文档和测试已经冻结了不同的约束：

- outer class 继续提供 type-meta 能力
- outer class 不自动提供 unqualified value/function lookup

因此 scope analyzer 不能照搬 Godot 的 outer-class value/function 可见性模型，而必须服从当前 `frontend.scope` 已测试冻结的 parent/skip 行为。

### 3.3 对本计划的直接影响

Godot 调研支持以下实施原则：

1. scope analyzer 先建 lexical graph，再把更细粒度 binding 留给后续 phase。
2. analyzer 要与 skeleton/body 分阶段推进，而不是试图在一个 walker 中同时解决所有语义问题。
3. inner class、lambda、match branch 等结构性边界必须在 scope graph 中被显式建模。

---

## 4. Scope Analyzer 的冻结职责边界

### 4.1 建议入口与包路径

建议新增入口类：

- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendScopeAnalyzer.java`

其定位是 `FrontendSemanticAnalyzer` 调度的一个 phase worker，而不是 `frontend.scope` 包中的协议对象。

### 4.2 建议输入与输出

建议 `FrontendScopeAnalyzer` 显式接收：

- `ClassRegistry`
- `FrontendAnalysisData`
- `DiagnosticManager`

必要时内部可建立 analyzer 私有上下文，例如：

- 当前 source path
- 当前 lexical parent scope
- 当前所属 class / callable
- 当前 static context 标志

但这些上下文对象应当仅服务于 scope analyzer，不应复用 skeleton phase 的 context。

建议输出保持为对 `FrontendAnalysisData` 的原位写入：

- 向 `analysisData.scopesByAst()` 写入 `Node -> Scope` 映射
- 如有必要，向 manager 追加少量结构性 diagnostics

### 4.3 `FrontendSemanticAnalyzer` 的接线顺序

scope analyzer 的推荐接线方式如下：

1. `FrontendAnalysisData.bootstrap(sourcePath, sourceUnit)`
2. `FrontendClassSkeletonBuilder.build(...)`
3. `analysisData.updateModuleSkeleton(moduleSkeleton)`
4. `analysisData.updateDiagnostics(diagnosticManager.snapshot())`
5. `FrontendScopeAnalyzer.analyze(...)`
6. `analysisData.updateDiagnostics(diagnosticManager.snapshot())`

这里要保留 skeleton 之后的一次 boundary publication，原因是：

- scope analyzer 需要读取已经完成的 `moduleSkeleton`
- skeleton 仍然是一个独立阶段
- analyze 最终返回的边界快照应反映 scope analyzer 之后的状态

### 4.4 analyzer 初版的职责声明

初版 scope analyzer 只负责：

- 建 scope 对象
- 连接 parent 链
- 为 AST 节点记录“当前应使用的 lexical scope”

初版 scope analyzer 不负责：

- 任何变量绑定写入 `Scope`，包括 callable parameters
- 普通 `var` / `const` 的声明顺序与初始化器可见性
- `for` 迭代变量的预填
- capture 计算
- `PatternBindingExpression` 的完整落地
- 成员/调用/类型 side-table
- shared resolver 调用

---

## 5. `scopesByAst` 的语义与建图规则

### 5.1 `scopesByAst` 的冻结语义

`scopesByAst` 不应只记录“哪些 AST 节点本身拥有一个 scope”，而应记录：

> 某个 AST 节点在后续语义分析中应使用的当前 lexical scope。

这意味着 side-table 的 key 不仅应覆盖 scope owner，还应覆盖后续 binder 需要在其上发起名称解析的 AST 节点。随着 `gdparser 0.5.x` 把 `SourceFile` 也纳入 `Node` 体系，`scopesByAst` 的 key 类型现在应直接收敛为 `Node`。

### 5.2 按阶段覆盖的 AST 节点类别

完整 scope analyzer 最终应逐步为以下 AST 节点挂 scope。Phase 2 当前只覆盖其中与顶层脚本 /
callable 主干和外层安全表达式相关的子集；`ClassDeclaration`、控制流 body block、
`MatchSection` 等结构性边界继续按后续 phase 推进：

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
- 需要名称解析的表达式节点

这里的关键点是：条件表达式、返回类型、参数默认值、循环 iterable、match guard、lambda body 内表达式等对象，后续 binder 都需要知道“在何种 scope 下解释它们”。

### 5.3 推荐的 owner -> scope 对应关系

建议按下表建立结构性映射。表中描述的是完整目标形态；Phase 2 当前只物化：

- `SourceFile` -> 顶层脚本 `ClassScope`
- `FunctionDeclaration` / `ConstructorDeclaration` / `LambdaExpression` -> `CallableScope`
- callable `body: Block` -> 对应 `BlockScope`

`ClassDeclaration`、普通 `Block`、控制流 body、`MatchSection` body 的独立 scope 继续在
Phase 3 / Phase 4 推进：

| AST 节点 | 建立的 Scope | 说明 |
| --- | --- | --- |
| `SourceFile` | 顶层脚本 `ClassScope` | 顶层语义最终以脚本类为承载 |
| `ClassDeclaration` | `ClassScope` | inner class 也需要 lexical boundary |
| `FunctionDeclaration` | `CallableScope` | 仅承载参数/capture 等 callable 级绑定 |
| `ConstructorDeclaration` | `CallableScope` | 与 function 同层级 |
| `LambdaExpression` | `CallableScope` | lambda 自成 callable boundary |
| callable `body: Block` | `BlockScope` | 与 `CallableScope` 分离，普通局部变量落在 body block |
| 普通 `Block` | `BlockScope` | block 级 boundary |
| `IfStatement` body / `ElifClause` body / `else` body | `BlockScope` | 各分支独立 |
| `WhileStatement` body | `BlockScope` | 循环体独立 |
| `ForStatement` body | `BlockScope` | 循环体独立 |
| `MatchSection` body | `BlockScope` | 分支独立 |

### 5.3.1 `CallableScope` 的来源盘点

当前会直接生成 `CallableScope` 的 AST 节点只有以下三类：

| 语义来源 | 直接 AST 节点 | 备注 | 建议枚举值 |
| --- | --- | --- | --- |
| 函数声明 | `FunctionDeclaration` | 普通 `func` 边界 | `FUNCTION_DECLARATION` |
| 构造函数声明 | `ConstructorDeclaration` | `_init` / constructor 边界 | `CONSTRUCTOR_DECLARATION` |
| lambda 表达式 | `LambdaExpression` | 表达式级 callable 边界 | `LAMBDA_EXPRESSION` |

这三类虽然都会实例化为 `CallableScope`，但它们的语义来源不同，后续调试、测试断言和 binder 行为检查都不应再靠“声明对象类型推断”来猜测。

### 5.3.2 `BlockScope` 的来源盘点

当前会生成 `BlockScope` 的情况比 `CallableScope` 更多，而且很多场景在 AST 运行时层面都表现为同一个 `Block` 节点类型，因此必须把“语义来源”和“实际 AST 载体”分开记录。

| 语义来源 | 实际携带 scope 的 AST 节点 | 备注 | 建议枚举值 |
| --- | --- | --- | --- |
| 普通块语句 | `Block` | 独立块语句，不属于 callable/branch/loop 特化 body | `BLOCK_STATEMENT` |
| 函数体 | `FunctionDeclaration.body` (`Block`) | callable body | `FUNCTION_BODY` |
| 构造函数体 | `ConstructorDeclaration.body` (`Block`) | callable body | `CONSTRUCTOR_BODY` |
| lambda 体 | `LambdaExpression.body` (`Block`) | callable body | `LAMBDA_BODY` |
| if 主分支体 | `IfStatement.body` (`Block`) | branch body | `IF_BODY` |
| elif 分支体 | `ElifClause.body` (`Block`) | branch body | `ELIF_BODY` |
| else 分支体 | `IfStatement.elseBody` (`Block`) | nullable，且没有独立 AST 节点类型 | `ELSE_BODY` |
| while 循环体 | `WhileStatement.body` (`Block`) | loop body | `WHILE_BODY` |
| for 循环体 | `ForStatement.body` (`Block`) | loop body | `FOR_BODY` |
| match section 体 | `MatchSection.body` (`Block`) | branch body | `MATCH_SECTION_BODY` |

需要明确排除的一点是：`ClassDeclaration.body` 虽然也是 `Block`，但它承载的是 `ClassScope` 下的类成员遍历上下文，不应再额外生成 `BlockScope`。

### 5.3.3 scope kind 枚举与字段任务

由于 `CallableScope` 和 `BlockScope` 都存在“一种 scope 类对应多种 AST 来源”的情况，本计划新增一项明确任务：

- 新增 `CallableScopeKind` 枚举，并添加到 `CallableScope` 的 final 字段中。
- 新增 `BlockScopeKind` 枚举，并添加到 `BlockScope` 的 final 字段中。
- 两个 scope 类的构造函数都必须显式接收对应 kind，禁止事后推断或懒计算。
- 两个 scope 类都应提供只读访问器，例如 `kind()`，供 analyzer 测试、binder 和调试输出使用。
- `FrontendScopeAnalyzer` 在创建 scope 时，必须根据上面的来源盘点传入正确枚举值。
- 现有 `frontend.scope` 测试以及后续新增的 scope analyzer 测试，需要补对 kind 字段的断言，避免只验证“是某类 scope”而不验证“是哪一种来源的 scope”。

本任务当前只针对 `CallableScope` 与 `BlockScope`。`ClassScope` 是否也需要引入 kind，不在本计划首轮范围内。

### 5.4 callable 采用两层 scope 的冻结方案

对于 function / constructor / lambda，建议固定使用两层作用域：

- callable 节点本身：`CallableScope`
- callable 的 `body: Block`：独立 `BlockScope`

原因如下：

- 参数与 capture 明确属于 callable 层
- 普通局部变量属于函数体 block 层
- 这与现有 `frontend.scope` 设计及测试更一致
- 后续 binder 实现声明顺序和遮蔽规则时更容易保持清晰

### 5.5 表达式与类型引用的挂载原则

scope analyzer 需要遍历 expression tree，而不是只遍历 statement tree。建议规则如下：

- 表达式节点默认继承其所在语境的当前 scope
- `LambdaExpression` 作为新的 callable boundary，进入时切换到新的 `CallableScope`
- 参数默认值表达式在 parameter 所在 callable 的外层定义语境中求值还是在 callable 内求值，需要以后续语言规范最终确认；在规范未收口前，本计划先要求其行为被显式记录在测试中，不允许保持隐式
- `TypeRef` 节点统一挂载其所在语境的当前 scope，供后续 type-meta lookup 使用

### 5.6 inner class 的初版策略

inner class 是第一阶段最需要明确的难点之一。当前模块骨架主要面向顶层脚本类，不能天然提供完整 inner-class metadata。

因此本计划冻结如下策略：

- Phase 2 / Phase 3 不把 inner class 伪装成“已支持”，其 lexical boundary 明确延后到 Phase 4
- Phase 4 落地时允许使用 placeholder / deferred metadata 策略，只要 lexical scope graph 是稳定的
- 一旦进入 Phase 4，就不能因为 metadata 未完备而放弃 inner class subtree 的 scope side-table

推荐的阶段性落地顺序是：

1. 先完成基于 `gdparser` 内置 `ASTWalker` 的顶层脚本 / callable scope 主干
2. 再补控制流 body / match branch 的 block boundary
3. 然后保证 inner class subtree 具有独立 `ClassScope`
4. outer class 只通过已冻结的 type-meta 链影响其解析
5. 更完整的 inner-class skeleton/interface 信息由后续 phase 补齐

### 5.7 `MatchSection` 与 `PatternBindingExpression`

`gdparser` 已经存在 `PatternBindingExpression`，说明 match pattern 的局部绑定迟早需要被建模。

本计划冻结的阶段性要求是：

- Phase 2：`MatchSection`、其 body 与 pattern bindings 保持 deferred，避免把 branch scope 伪装成“已支持”
- Phase 3：每个 `MatchSection` 必须拥有独立 branch `BlockScope`
- Phase 3：`PatternBindingExpression` 至少要在 side-table 中拿到正确 scope
- 是否立即把 pattern binding 注册成 local，由后续 binder/body phase 决定

换言之，Phase 2 可以先保留 match branch deferred，但一旦进入 Phase 3，就不能把所有分支错误地压成同一个 scope。

### 5.8 `for` 迭代变量明确延期到后续 pass

`ForStatement` AST 直接暴露：

- `iterator`
- `iteratorType`
- `iterable`
- `body`

这是一类结构化且边界清晰的局部绑定，但本计划明确不在 scope analyzer 首轮中预填，而是留给后续独立 pass 处理。

scope analyzer 首轮只负责以下结构性事实：

- `iterable` 与 `iteratorType` 在进入 loop body 前按外层当前 scope 求值
- loop body 使用独立 `BlockScope`

将 `iterator` 本身注册为 local binding 的动作延期到后续 pass，原因如下：

- 它虽然比普通 `var` / `const` 更结构化，但仍然属于“向 scope 中注入值绑定”的行为，不应在 scope analyzer 首轮扩大职责。
- `BlockScope.defineLocal(...)` 需要 `GdType`，而 iterator 的稳定类型通常依赖 `iterable` 的后续类型分析。
- 若过早预填 iterator，会提前冻结 loop body 内的 duplicate / shadowing 语义，增加首轮 scope analyzer 的协议负担。

因此，首轮验收只要求 `ForStatement` 相关节点拿到正确 scope，不要求 loop body 已能通过 scope 直接命中 iterator。

---

## 6. 实施范围与延期项

### 6.1 本计划纳入范围

scope analyzer 实施计划纳入以下内容：

- 新增 phase worker
- 新增或补充 AST walker
- 接线到 `FrontendSemanticAnalyzer`
- 产出 `scopesByAst`
- 冻结“先建完整 scope graph，再做独立 binding pass”的阶段边界
- 新增和更新测试
- 文档同步

### 6.2 明确延期的内容

以下内容明确延期到后续 phase，不在本计划首轮验收范围内：

- callable parameters 的变量绑定写入
- 普通局部变量的完整 binding
- `for` 迭代变量的预填与其类型落地
- class local const alias / preload alias / local enum type-meta
- capture 推导与捕获集合写入
- pattern binding 的完整定义与生命周期规则
- `self` / signal 的完整语义
- `resolvedMembers`、`resolvedCalls`、`expressionTypes` 的生产填充
- 依赖 shared resolver 的成员解析逻辑

这些延期项必须在文档和测试中显式注明，不允许通过“暂未处理”模糊化。

---

## 7. 分阶段实施计划

### 7.1 Phase 0：冻结职责边界与 side-table 语义

### 目标

在代码落地前，先把 scope analyzer 的职责边界、`scopesByAst` 语义、deferred 项目和 inner class 初版策略写成事实源，避免实现过程中边写边改协议。

### 实施内容

- 补充本实施计划文档
- 与 `scope_architecture_refactor_plan.md` 保持术语一致
- 明确 `scopesByAst` 是“当前 lexical scope”而不是“scope owner 集合”
- 明确普通 locals/captures/pattern bindings 为 deferred

### 输出物

- 本文档作为事实源

### 验收细则

- 文档明确列出：
  - scope analyzer 输入输出
  - `Node -> Scope` 映射原则
  - 初版纳入范围与延期项
  - 与 Godot 的对齐和差异
- 不存在与 `diagnostic_manager.md` 或 `scope_architecture_refactor_plan.md` 冲突的职责描述

### 当前状态（2026-03-11）

- [x] 已冻结 `scopesByAst` 的语义为“某个 AST 节点当前应使用的 lexical scope”，而不是“拥有 scope 的节点集合”。
- [x] 已明确 scope analyzer 的输入输出、与 `FrontendAnalysisData` 的集成方式，以及 `DiagnosticManager` 的共享 manager 约束。
- [x] 已补齐 callable / block scope 来源盘点，并把 `CallableScopeKind` / `BlockScopeKind` 作为后续 Phase 2 的明确任务。
- [x] 已把普通 locals、captures、pattern bindings、`for` 迭代变量预填等内容显式标记为 deferred，避免在首轮 scope phase 中扩大职责。
- [x] 已把 inner class 初版 lexical-boundary 策略、match section 边界规则、与 Godot 的对齐结论和有意差异写成事实源。

### 7.2 Phase 1：引入 `FrontendScopeAnalyzer` 并接入总控流程

### 目标

让 `FrontendSemanticAnalyzer` 在 skeleton 之后显式调用 scope analyzer，使 analyze 流程具备独立的 scope phase。

### 实施内容

- 新增 `FrontendScopeAnalyzer`
- 明确其公开入口签名
- 在 `FrontendSemanticAnalyzer` 中接线
- 调整 analyze 阶段边界发布顺序

### 设计要求

- `FrontendScopeAnalyzer` 必须显式接收 `DiagnosticManager`
- 不提供无 manager 的兼容入口
- 不把 scope analyzer 做成 `frontend.scope` 的一部分
- 不改动 `Scope` / `ResolveRestriction` 协议

### 验收细则

- analyze 主链路出现独立的 scope analyzer 调用点
- `FrontendAnalysisData.moduleSkeleton()` 在 scope analyzer 运行前已经可用
- analyze 返回结果仍然通过显式 `updateDiagnostics(...)` 保持最终 diagnostics 快照为最新值
- 现有 skeleton 相关测试在行为上不回退

### 当前状态（2026-03-11）

- [x] 已新增 `FrontendScopeAnalyzer`，并保持它位于 `frontend.sema.analyzer` 下而非 `frontend.scope` 协议层。
- [x] 已冻结其公开入口为 `analyze(ClassRegistry, FrontendAnalysisData, DiagnosticManager)`，不提供无 manager 的兼容入口。
- [x] `FrontendSemanticAnalyzer` 已按计划在 skeleton 之后发布 `moduleSkeleton` 与 pre-scope diagnostics boundary，再调用 `FrontendScopeAnalyzer`。
- [x] `FrontendSemanticAnalyzer` 已在 scope phase 返回后再次调用 `updateDiagnostics(...)`，确保最终 diagnostics 快照反映 scope phase 之后的 shared manager 状态。
- [x] Phase 1 引入的独立 scope phase 骨架仍保持不变，但其“仅发布 side-table 边界”的职责已被 Phase 2 的真实 AST 建图继续扩展，不再停留在空 `scopesByAst`。
- [x] 已新增 `FrontendScopeAnalyzerTest`，并补充 `FrontendAnalysisDataTest`，用正反测试锚定 phase 顺序、前置条件和 shared side-table 语义。

### 7.3 Phase 2：基于 `gdparser` AST walker 建立顶层脚本/callable scope 主干

### 目标

先打通最基础的 scope graph 主干：

- source file / top-level script class
- function
- constructor
- lambda
- callable body block

### 实施内容

- 复用 `gdparser 0.5.1` 内置 `ASTWalker` / `ASTNodeHandler` / `FrontendASTTraversalDirective`
- 新增 `CallableScopeKind` / `BlockScopeKind`，并把它们作为 `CallableScope` / `BlockScope` 的 final 字段
- 为 `SourceFile` 写入顶层 `ClassScope`
- 为 `FunctionDeclaration`、`ConstructorDeclaration`、`LambdaExpression` 建 `CallableScope`
- 为 callable body 建独立 `BlockScope`
- 为 `FunctionDeclaration`、`ConstructorDeclaration`、`LambdaExpression` 及其 body block 写入正确的 scope kind
- 为参数、返回类型、函数体中表达式和类型引用挂载当前 scope

### 设计要求

- 必须遍历 expression tree，不能只沿 statement tree
- callable 必须使用两层 scope
- 不能依赖 AST 运行时类型去反推 scope 来源；`CallableScopeKind` / `BlockScopeKind` 必须成为一等事实
- 参数至少要具备 side-table scope 信息
- lambda 不能被遗漏

### 验收细则

- `scopesByAst()` 不再为空
- `SourceFile`、顶层声明、callable 节点、callable body 都能回溯到正确 scope
- `FunctionDeclaration` / `ConstructorDeclaration` / `LambdaExpression` 使用各自独立的 `CallableScope`
- callable body 不与 `CallableScope` 复用同一个对象
- callable 相关 `CallableScopeKind` / `BlockScopeKind` 枚举值正确
- 所有 `Parameter` 节点和 callable 返回类型 `TypeRef` 都存在 scope side-table 记录

### 当前状态（2026-03-11）

- [x] 已新增 `CallableScopeKind` / `BlockScopeKind`，并把它们接入 `CallableScope` / `BlockScope` 的 final 字段、显式构造器与只读访问器；现有 `frontend.scope` 协议测试已补 kind 断言。
- [x] 在 `gdparser` 升级到 `0.5.1` 后，已回退本地 `FrontendASTWalker` / `FrontendASTNodeHandler` / `FrontendASTTraversalDirective` 封装，并改为直接复用 parser 库内置的 `ASTWalker` 族 API，避免在 gdcc 中重复维护一套 AST 遍历基础设施。
- [x] 随着 `gdparser 0.5.x` 把 `SourceFile` 也纳入 `Node` 体系，`FrontendAstSideTable` 及其相关语义 side-table 已统一收敛为 `Node` key；测试中原先依赖裸 `Object` key 的旧假设也已回退。
- [x] `FrontendScopeAnalyzer` 已迁移到基于 `gdparser ASTWalker` 的实现，并以 `moduleSkeleton.units()` 与 `moduleSkeleton.classDefs()` 的一一对应关系，为每个 `SourceFile` 物化顶层脚本 `ClassScope`。
- [x] 已为 `FunctionDeclaration`、`ConstructorDeclaration`、`LambdaExpression` 建立独立 `CallableScope`，并为 callable body 建立独立 `BlockScope`；两层 scope 不再复用同一对象。
- [x] 已把 callable source kind 稳定写入 side-table：`FUNCTION_DECLARATION`、`CONSTRUCTOR_DECLARATION`、`LAMBDA_EXPRESSION` 以及 `FUNCTION_BODY`、`CONSTRUCTOR_BODY`、`LAMBDA_BODY` 均通过单元测试锚定。
- [x] 已把 constructor 的 parse-to-scope 端到端回归测试收敛到合法 Godot 4 `_init(...)` 源码，避免再用旧版 GDScript 3.x 的 `func _init(...).(...)` 语法去伪装“标准语义”。
- [x] `gdparser 0.5.1` 已在 lowering 阶段把旧版 inherited-constructor header 语法直接报告为 parse error，`ConstructorDeclaration` AST 形状中不再保留 `baseArguments()`；对应的 gdcc scope 构建分支与相关兼容性测试已回退删除。
- [x] `scopesByAst()` 在非空 module 上已不再为空；`SourceFile`、顶层声明、callable 节点、callable body、`Parameter`、callable 返回类型 `TypeRef`、parameter default value 表达式，以及 callable/body 中当前已纳入覆盖的表达式节点都能回溯到正确 lexical scope。
- [x] parameter default value 表达式的当前行为已不再隐式：Phase 2 明确把它们挂载到所属 callable 的 `CallableScope`，后续若语言规范收敛到不同模型，需要以文档和测试一起迁移。
- [x] 已通过正反测试明确 Phase 2 的边界：控制流 body block、`MatchSection` branch scope、inner class lexical boundary 与 parameter prefill 仍保持 deferred，没有在本阶段被伪装成“已支持”。
- [x] 通用 walker 的遍历语义现由 `gdparser` 本地副本中的 `frontend.ast.ASTWalkerTest` 负责 upstream 覆盖；gdcc 侧通过 `FrontendScopeAnalyzerTest` 的正反场景锚定集成行为，不再复制维护一套本地 walker 单元测试。
- [x] 已完成 targeted tests 更新与回归：`FrontendParseSmokeTest`、`FrontendScopeAnalyzerTest`、`FrontendSemanticAnalyzerFrameworkTest`、`FrontendAnalysisDataTest`、`FrontendAstSideTableTest`、`ScopeChainTest`、`ScopeTypeMetaChainTest`、`ScopeCaptureShapeTest` 已通过。

### 7.4 Phase 3：扩展控制流 block scope 与表达式覆盖

### 目标

补齐常见控制流节点的 block boundary，确保 statement tree 和 expression tree 中所有后续 binder 会访问的节点都能拿到正确 scope。

### 实施内容

- 为 `IfStatement`、`ElifClause`、`else` body 建独立 `BlockScope`
- 为 `WhileStatement` body 建独立 `BlockScope`
- 为 `ForStatement` body 建独立 `BlockScope`
- 为 `MatchSection` body 建独立 `BlockScope`
- 为普通独立 `Block`、`if/elif/else`、`while`、`for`、`match section` 的 block 写入正确的 `BlockScopeKind`
- 为条件表达式、iterable、match guard、pattern 表达式等挂 scope

### 设计要求

- 条件表达式与分支 body 不能共享错误的 scope
- 同一 `if` 的各个分支必须彼此隔离
- `MatchSection` 必须是独立 branch scope
- `ForStatement.iterable` 必须在 loop body 之前的外层 scope 下分析

### 验收细则

- `if / elif / else` 各 body 拥有不同的 `BlockScope`
- `while` 与 `for` body 不与外层 block 混用
- `match` 每个 section 拥有独立 `BlockScope`
- 各类控制流 block 的 `BlockScopeKind` 枚举值正确，特别是 `ELSE_BODY` 与普通 `Block` 必须可区分
- 条件表达式、loop iterable、guard 表达式在 side-table 中可回溯到外层正确 scope

### 7.5 Phase 4：处理 inner class 与 lambda 等特殊边界

### 目标

把容易被忽略但必须独立建模的结构性边界纳入 scope graph。

### 实施内容

- 为 inner class 建独立 `ClassScope`
- 明确 inner class 的 parent 链连接策略
- 校准 outer class 仅提供 type-meta、不自动提供 unqualified value/function 的规则
- 复查 lambda 中嵌套 lambda / nested callable 的 parent chain

### 设计要求

- inner class 即使 metadata 暂不完整，也必须有 lexical boundary
- 不能把 outer class member 可见性做成与 Godot 相同的宽松模型
- nested callable 的 `CallableScope` parent 必须稳定落在其 enclosing lexical scope 上

### 验收细则

- inner class subtree 在 `scopesByAst()` 中完整存在
- inner class 拥有独立 `ClassScope`
- 相关行为不违反现有 `FrontendInnerClassScopeIsolationTest` 和 `FrontendNestedInnerClassScopeIsolationTest` 的协议基线
- 嵌套 lambda/函数体中的 scope parent 链与现有 `frontend.scope` 语义一致

### 7.6 Phase 5：冻结“先建完整 scope graph，再做独立 binding pass”的边界

### 目标

明确变量绑定不属于 scope 构建阶段；只有在完整 scope graph 建成并稳定发布后，后续独立 binding pass 才开始把变量写入 `CallableScope` / `BlockScope` 等对象。

### 实施内容

- 把 callable parameters、普通 locals、captures、pattern bindings 统一标记为“scope analyzer 之外”的后续工作
- 明确这些变量绑定动作只能发生在完整 scope graph 建成之后，而不是建图过程中穿插进行
- 明确 `for` iterator 继续 deferred 到后续独立 pass

### 设计要求

- scope analyzer 只能建立 scope graph 和 side-table，不能在 walker 中调用 `defineParameter(...)`、`defineLocal(...)`、`defineCapture(...)` 等写绑定 API
- `Parameter` 节点的 scope side-table 记录仍然是必须项，但它不等价于 parameter 已经成为 `CallableScope` 中可解析的变量绑定
- `for` iterator 不在本阶段预填，文档与测试必须把它标记为 deferred
- 普通 `var` / `const` 不在本阶段预填，以免提前引入声明顺序与初始化器可见性错误

### 验收细则

- `Parameter` 节点能够从 side-table 回溯到正确的 `CallableScope`
- scope analyzer 创建出的 `CallableScope` / `BlockScope` 不因建图阶段被偷偷写入变量绑定
- parameter/locals/captures/pattern bindings 均已在文档中明确标注为 deferred，并约束为“完整 scope graph 建成后再进入独立 binding pass”
- `for` iterator 未在本阶段预填，且测试和文档已明确标注为 deferred

### 7.7 Phase 6：测试、文档与回归收口

### 目标

把 scope analyzer 从“能跑”推进到“有稳定回归面”的状态。

### 实施内容

- 新增 scope analyzer 专项测试
- 更新 `FrontendSemanticAnalyzerFrameworkTest`
- 更新必要的 `FrontendAnalysisData` 相关测试
- 复核现有 `frontend.scope` 协议测试仍然成立
- 同步本文档与其他事实源文档的引用

### 验收细则

- 新增测试覆盖顶层、callable、control-flow block、inner class、lambda、match section
- 集成测试不再断言 `scopesByAst().isEmpty()`
- 现有 `frontend.scope` 测试不因 analyzer 接线而语义回退
- 文档清楚列出已实现与 deferred 边界

---

## 8. 测试与验收矩阵

### 8.1 现有测试的角色划分

以下现有测试应作为 scope analyzer 的协议基线继续保留：

- `frontend.scope` 协议类测试
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
- `frontend.sema` 集成基线
  - `FrontendAnalysisDataTest`
  - `FrontendSemanticAnalyzerFrameworkTest`
  - `FrontendAstSideTableTest`

### 8.2 建议新增的测试集合

建议新增：

- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendScopeAnalyzerTest.java`

或按实现粒度拆为更小的测试类，但首轮建议至少覆盖以下主题：

1. 顶层 `SourceFile` 与脚本类 scope 挂载
2. `FunctionDeclaration` / `ConstructorDeclaration` / `LambdaExpression` 的 `CallableScope`
3. callable body 独立 `BlockScope`
4. `if / elif / else` 的分支隔离
5. `while / for / match section` 的 block boundary
6. 表达式、`TypeRef`、`Parameter` 的 side-table 挂载
7. `CallableScopeKind` / `BlockScopeKind` 的来源枚举断言
8. inner class 的 lexical boundary
9. `FrontendSemanticAnalyzer` 集成接线

### 8.3 分阶段验收矩阵

| 验收项 | P1 | P2 | P3 | P4 | P5 | P6 |
| --- | --- | --- | --- | --- | --- | --- |
| `FrontendSemanticAnalyzer` 接入 scope phase | Y | Y | Y | Y | Y | Y |
| `scopesByAst()` 非空 | - | Y | Y | Y | Y | Y |
| 顶层脚本/callable scope 建图 | - | Y | Y | Y | Y | Y |
| 控制流 block scope 建图 | - | - | Y | Y | Y | Y |
| `CallableScopeKind` / `BlockScopeKind` 接线 | - | Y | Y | Y | Y | Y |
| inner class lexical boundary | - | - | - | Y | Y | Y |
| 变量绑定保持 deferred（含 parameters） | - | Y | Y | Y | Y | Y |
| “完整 graph 后再做 binding”边界冻结 | - | - | - | - | Y | Y |
| `for` iterator 预填（deferred） | - | - | - | - | - | - |
| 集成测试更新完成 | - | - | - | - | - | Y |

### 8.4 最终通过标准

scope analyzer 首轮实施完成时，至少要满足以下最终标准：

1. `FrontendSemanticAnalyzer` 跑完后，`scopesByAst()` 不再为空。
2. `SourceFile` 与顶层声明节点能够回溯到正确的顶层 `ClassScope`。
3. `FunctionDeclaration`、`ConstructorDeclaration`、`LambdaExpression` 拥有独立 `CallableScope`。
4. callable body、`if/elif/else`、`while`、`for`、`match section` 都有独立 `BlockScope`。
5. `CallableScopeKind` 与 `BlockScopeKind` 已准确表达各 scope 的语义来源，不再依赖 AST 运行时类型或 declaration object 反推。
6. 条件表达式、`TypeRef`、`Parameter`、循环 iterable、match guard 等关键节点都能从 side-table 拿到正确 scope。
7. scope analyzer 不会在建图阶段把 parameters 或其他变量绑定写入 `Scope`；这些绑定留给完整 scope graph 建成后的独立 pass。
8. 文档中被声明为 deferred 的项目，在测试与实现中都没有被伪装成“已支持”。

---

## 9. 风险与控制策略

### 9.1 风险：把 scope analyzer 做成“半个 binder”

风险描述：

- 如果在 scope analyzer 阶段过早注册 parameters、普通 locals、captures、pattern bindings，就会把声明顺序、初始化器可见性、捕获规则等复杂语义硬塞进 scope phase。

控制策略：

- 初版严格限制为结构性 scope graph
- 对 parameters、普通 locals、captures、pattern bindings 统一 deferred 到完整 graph 之后的独立 binding pass

### 9.2 风险：只遍历 statement tree，遗漏 expression tree

风险描述：

- 若只模仿早期 annotation collector 的 statement walker，后续 binder 在表达式、返回类型、默认参数、guard、iterable 等位置将拿不到 scope。

控制策略：

- scope analyzer 必须拥有完整 expression walker
- 测试必须覆盖 `TypeRef`、guard、iterable、lambda expression 等节点

### 9.3 风险：inner class 语义被误套 Godot 模型

风险描述：

- 若直接搬运 Godot 的 outer-class lookup，会违反 GDCC 当前 `ClassScope` 已冻结的 value/function 规则。

控制策略：

- 以现有 `frontend.scope` 测试为准
- Godot 只作为阶段划分和结构建模的参考，不作为 outer-class value/function 可见性的直接事实源

### 9.4 风险：scope side-table 只记录 owner，不记录消费点

风险描述：

- 若 side-table 只给 block/class/function owner 节点挂 scope，后续 binder 仍需要重新推导“当前 scope”，那就会失去 side-table 的价值。

控制策略：

- 明确 `scopesByAst` 的语义是“该 AST 节点的当前 lexical scope”
- 验收测试必须覆盖 expression 和 type ref 节点

### 9.5 风险：inner class metadata 不完备导致实现卡死

风险描述：

- 当前 skeleton 更偏向顶层脚本类，inner class 元数据未完全收口，可能导致实现者试图等待“完整 metadata”后再做 scope graph。

控制策略：

- 初版先保 lexical boundary
- 允许 placeholder / deferred metadata 策略
- 不允许因此跳过 inner class subtree 的 side-table 构建

---

## 10. 推荐的实现顺序

为了降低回归面，建议采用以下落地顺序：

1. 先接入 `FrontendScopeAnalyzer` 空骨架与 analyzer phase 调度。
2. 先让 `scopesByAst()` 对顶层、class、callable、callable body 非空。
3. 在 callable / block 建图接线时同步引入 `CallableScopeKind` / `BlockScopeKind`。
4. 再扩展 `if/while/for/match` 等 block boundary。
5. 再接 inner class 与 lambda 嵌套场景。
6. 完整 scope graph 稳定后，再单独规划变量 binding pass（parameters / locals / captures / pattern bindings）。
7. `for` iterator 预填在 scope analyzer 首轮之外，留给后续独立 pass。
8. 所有结构稳定后，再规划 binder/body phase 如何消费这些 scope 与后续 binding 结果。

该顺序的核心目的是把：

- 接线问题
- 建图问题
- 特殊边界问题
- 绑定问题

分离开，避免一次提交同时引入多种语义噪声。

---

## 11. 最终验收总表

在 scope analyzer 首轮开发结束时，需要逐项确认以下问题：

- 是否已经新增独立的 `FrontendScopeAnalyzer` 并接入 `FrontendSemanticAnalyzer`
- 是否在 skeleton 之后、body/binder 之前建立了 scope phase
- `scopesByAst()` 是否已经覆盖顶层、类、callable、block、关键表达式与 `TypeRef`
- callable 是否采用 `CallableScope + body BlockScope` 双层结构
- `if / elif / else`、`while`、`for`、`match section` 是否都已建立独立 `BlockScope`
- `CallableScopeKind` 与 `BlockScopeKind` 是否已经覆盖所有已盘点的来源，并以字段形式挂在 scope 对象上
- inner class 是否拥有独立 lexical boundary
- 是否已经明确 parameters 不在 scope 构建阶段预填，而是在完整 scope graph 建成后交由独立 binding pass
- `for` iterator 是否已明确保留给后续 pass，而不是被 scope analyzer 首轮偷偷实现
- 现有 `frontend.scope` 协议测试是否仍然通过
- `FrontendSemanticAnalyzerFrameworkTest` 是否已从“空 `scopesByAst`”更新为“存在 scope side-table 事实”
- 文档是否明确标注未实现项，而不是留下隐式空白

只要以上任一项没有被明确回答，scope analyzer 就不应视为完成首轮交付。
