# FrontendVariableAnalyzer 实现说明

> 本文档作为 `FrontendVariableAnalyzer` 的长期事实源，定义当前职责边界、已发布 variable inventory 语义、诊断与恢复合同、deferred 边界，以及后续 binder / visible-value 工程必须遵守的接线约束。

## 文档状态

- 性质：长期事实源
- 最后校对：2026-03-15
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/scope_type_resolver_implementation.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
- 明确非目标：
  - 不在这里实现完整 frontend binder / body
  - 不在这里写入 `symbolBindings()` 或表达式类型 side-table
  - 不在这里实现成员解析、调用解析或 use-site declaration-order 可见性
  - 不在这里接入 lambda / `for` / `match` / block-local `const` 的完整 inventory
  - 不改变 shared `Scope` lookup 协议

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

这意味着：

- `FrontendVariableAnalyzer` 只运行在 skeleton 与 scope graph 已发布之后
- 它消费 `moduleSkeleton`、`diagnostics` 与 `scopesByAst`
- 它不会重建 scope graph，而是就地 enrich 已发布的 `CallableScope` / `BlockScope`

### 1.2 当前职责

`FrontendVariableAnalyzer` 当前冻结负责：

- 把 `FunctionDeclaration` / `ConstructorDeclaration` 参数写入对应 `CallableScope`
- 把 supported executable subtree 中的普通局部 `var` 写入对应 `BlockScope`
- 对当前明确 deferred 的 variable-inventory 来源发出显式 warning，避免静默跳过
- 对 duplicate / shadowing / target scope kind mismatch 发布恢复性 diagnostic，并保持其他 subtree 继续处理

`FrontendVariableAnalyzer` 明确不负责：

- lambda parameter / local / capture inventory
- `for` iterator binding 与 loop body local inventory
- `match` pattern binding 与 section local inventory
- block-local `const` binding
- 参数默认值表达式的类型/绑定分析
- 局部变量 initializer 的类型推断
- use-site 可见性结论

---

## 2. 当前已发布的 variable inventory

### 2.1 已写入的绑定

当前生产代码已经稳定写入以下 binding：

- `FunctionDeclaration` parameter -> owning `CallableScope`
- `ConstructorDeclaration` parameter -> owning `CallableScope`
- function / constructor body 中的普通局部 `var` -> body `BlockScope`
- supported nested executable block 中的普通局部 `var` -> 对应 `BlockScope`

当前支持写入 ordinary local `var` 的 `BlockScopeKind` 为：

- `FUNCTION_BODY`
- `CONSTRUCTOR_BODY`
- `BLOCK_STATEMENT`
- `IF_BODY`
- `ELIF_BODY`
- `ELSE_BODY`
- `WHILE_BODY`

### 2.2 当前不写入的绑定

以下 binding 仍明确保持 deferred：

- lambda parameter
- lambda local
- lambda capture
- `for` iterator binding
- `for` body local
- `match` pattern binding
- `match` section local
- block-local `const`

需要明确区分：

- class property / class const 不属于本 analyzer 的 callable-local inventory
- block-local `const` 属于本 analyzer 已识别但当前明确 deferred 的输入

### 2.3 与 `FrontendAnalysisData` 的关系

当前 analyzer 的 side-table 合同是：

- 直接 enrich 已发布的 scope 对象
- 不改写 `scopesByAst` 的图结构
- 不写 `symbolBindings()`
- 不写 `expressionTypes()`
- 不写 `resolvedMembers()`
- 不写 `resolvedCalls()`

因此当前可消费的事实是：

- parameter / local declaration inventory 可通过 scope 对象查询
- 但 bare `Scope.resolveValue(...)` 仍只代表 declaration inventory，不代表 use-site 可见性真相

---

## 3. 类型规则

### 3.1 声明类型解析入口

参数与 ordinary local `var` 当前统一通过 `FrontendDeclaredTypeSupport.resolveTypeOrVariant(...)` 解析声明类型。

稳定规则为：

- `typeRef == null` -> `Variant`
- `typeRef.sourceText().trim().isEmpty()` -> `Variant`
- `typeRef.sourceText().trim().equals(\":=\")` -> 当前临时回退为 `Variant`
- 其他显式类型文本 -> 走严格 declared-type 解析
- declared-type 解析失败 -> 发出 `sema.type_resolution` warning，并回退到 `Variant`

### 3.2 `:=` 的当前与未来语义

这里必须冻结一条事实：

- `:=` 的目标语义是“分析右侧表达式类型，并据此确定变量类型”
- 当前返回 `Variant` 只是因为 `FrontendExprTypeAnalyzer` 尚未发布表达式类型结果

后续工程不得把“当前回退到 `Variant`”误解释成语言层面的长期语义。

### 3.3 参数默认值

若 `Parameter.defaultValue() != null`，当前行为固定为：

- 发出 `sema.unsupported_parameter_default_value` warning
- 不分析默认值表达式
- 不改变参数本身写入 `CallableScope` 的行为

---

## 4. 遍历与 scope-targeting 合同

### 4.1 入口来源

当前 analyzer 只从 `FrontendModuleSkeleton.sourceClassRelations()` 中已接受的 source file 出发。

对每个 accepted source file：

- 顶层 `SourceFile -> ClassScope` 记录必须已经存在
- 若不存在，视为 phase-order / side-table guard rail 破坏，直接抛异常

### 4.2 当前遍历策略

当前实现使用 declaration-directed `ASTWalker`，并显式控制可进入的 subtree：

- `SourceFile.statements()`
- `ClassDeclaration.body().statements()`
- `FunctionDeclaration`
- `ConstructorDeclaration`
- supported executable `Block`
- `IfStatement` / `ElifClause` / `elseBody`
- `WhileStatement`

需要特别注意：

- binder 主遍历不会把 arbitrary expression subtree 当作 local-binding 域
- lambda / `for` / `match` subtree 不会进入 binding walk
- deferred 边界 warning 由独立的 boundary reporter 扫描 callable body 后补发

### 4.3 executable-context 判定

`supportedExecutableBlockDepth` 当前是稳定实现细节，其语义为：

- `0`：当前遍历位置不在允许写入 callable-local inventory 的 executable block 中
- `> 0`：当前遍历位置已经进入 function / constructor body 或其支持的嵌套 executable block

这条约束保证：

- class body 中的成员声明不会被误判成 block local
- 普通 `Block` / `if` / `elif` / `else` / `while` 只有在 callable body 之下才会参与 local binding

---

## 5. 诊断与恢复合同

### 5.1 允许抛异常的 guard rail

只有以下情况允许 fail-fast：

- `moduleSkeleton` 尚未发布
- diagnostics boundary 尚未发布
- accepted source file 在 variable analyzer 启动时缺少顶层 `SourceFile -> Scope` 记录

这些都属于 framework 不变量损坏，而不是普通源码错误。

### 5.2 恢复性 warning

当前恢复性 warning 包括：

- `sema.unsupported_parameter_default_value`
  - 参数默认值当前被忽略
- `sema.unsupported_variable_inventory_subtree`
  - lambda subtree 当前 deferred
  - `for` subtree 当前 deferred
  - `match` subtree 当前 deferred
  - block-local `const` 当前 deferred

这些 warning 的目标是防止“代码库已识别但当前未实现”的变量来源静默失败。

### 5.3 恢复性 error

当前恢复性 error 统一收口为 `sema.variable_binding`，覆盖：

- duplicate parameter
- duplicate local in the same block
- same-callable local shadowing parameter / outer local / future capture
- supported declaration 的 target scope kind mismatch

恢复规则固定为：

- 诊断当前 declaration
- 跳过当前 declaration 的写入
- 保留先前已发布的合法 binding
- 继续处理同一 module 中其他 subtree

### 5.4 缺 scope 记录的语义

若某个 parameter/local declaration 缺少 `scopesByAst` 记录，当前解释固定为：

- 优先视为前序 recovery 已跳过该 subtree
- 当前 analyzer 跳过该 declaration
- 不重复制造新的 diagnostic

这条规则是为了避免重复噪声，并保持前序 recovery 的封口语义。

---

## 6. 当前冻结的语义约束

### 6.1 同一 callable 内禁止变量遮蔽

当前 frontend 额外冻结一条非 Godot 同步语义：

- 同一 `FunctionDeclaration` / `ConstructorDeclaration` 内，不允许新的局部变量遮蔽同一 callable 中更早可见的 parameter / outer local / future capture

这里的检查边界是：

- 只检查同一 callable 边界内的 parameter / local / future capture
- 不把 class/global binding 纳入 same-callable no-shadowing 检查

### 6.2 这不是 use-site visibility

当前 analyzer 交付的是 declaration inventory，而不是 use-site visibility。

因此后续工程必须遵守：

- 不能直接把 `scope.resolveValue(...)` 当作 binder 的最终 use-site 结果
- declaration-order、自引用 initializer、future declaration provenance 等问题必须交给 `FrontendVisibleValueResolver`

---

## 7. 对后续工程的约束

### 7.1 binder 接线约束

后续 frontend binder 若要消费 parameter/local inventory，必须同时满足：

- use-site 位于已发布 inventory 的有效 executable subtree 中
- declaration-order 可见性通过 `FrontendVisibleValueResolver` 处理
- deferred subtree 被当作 `DEFERRED_UNSUPPORTED`，而不是当作正常 miss

### 7.2 新 lexical boundary 的维护要求

若未来新增新的 executable/local-binding boundary，必须同步更新：

- `FrontendScopeAnalyzer`
- `FrontendVariableAnalyzer`
- 相关测试
- 本文档

否则就会重新引入“scope graph 已有、inventory 却未同步”的静默偏差。

### 7.3 后续待接线能力

后续最直接的增量工作包括：

- lambda parameter / local / capture inventory
- `for` iterator 与 loop-body inventory
- `match` pattern binding 与 section inventory
- block-local `const` inventory
- `FrontendVisibleValueResolver`
- `symbolBindings()` 的 use-site 发布
- `FrontendExprTypeAnalyzer` 落地后对 `:=` 与参数默认值的真实语义接线

---

## 8. 稳定测试锚点

当前与本文档直接对应的测试锚点主要在：

- `FrontendVariableAnalyzerTest`
  - parameter/local 正向写入
  - deferred warning
  - duplicate / shadowing / kind mismatch 负向路径
  - missing scope recovery
- `FrontendSemanticAnalyzerFrameworkTest`
  - `scope -> variable` 主链路顺序
  - diagnostics snapshot refresh

后续改动若改变上述行为，必须同步更新本文档，而不是只改测试预期。
