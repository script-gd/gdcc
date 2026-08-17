# Frontend Lambda 实施计划

> 本文档是 GDScript `func(...)` / `LambdaExpression` 从当前结构性 deferred / unsupported
> 边界，转正到 frontend shared semantic、type-check、compile gate、CFG、body lowering
> 与 C backend 的**分阶段实施计划**。
> 落地完成后，应将架构与冻结合同吸收为长期事实源，并**删除**本计划中的进度流水账。
> 本文档不改变 Godot 运行时语义，也不提前开放 `match`、parameter default、
> block-local `const` 或 `await`。

## 文档状态

- 状态：阶段 A、B、C 已落地；D–I 尚未实施。lambda body 已成为 supported executable
  suite：resolver / interface / suite 解封，nested resolve 经独立 `LAMBDA_RESOLUTION`
  owner 首次发布完整 `FrontendLambdaPlan`（capture 声明处类型已填充并与 scope 同源）。
  表达式类型（`GdCallableType`）、LIR 合成、CFG lowering、compile surface 仍 fail-closed。
- 更新时间：2026-08-17（阶段 C 落地：resolver/interface/suite 解封 + capture 类型填充
  + `lambdaPlans()` 首次发布）
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/scope/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/main/c/codegen/include_451/gdcc/**`
  - `src/main/c/codegen/template_451/**`
  - `src/test/java/gd/script/gdcc/frontend/**`
  - `src/test/java/gd/script/gdcc/lir/**`
  - `src/test/java/gd/script/gdcc/backend/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_top_binding_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_plan.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_complex_writable_target_implementation.md`
  - `doc/module_impl/frontend/frontend_signal_support.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`（转正顺序真源）
  - `doc/module_impl/frontend/frontend_for_range_loop_implementation.md`（三层支持面 / compile-gate 解封条件的已落地参照；该文档本身已不再含阶段流水账）
  - `doc/gdcc_low_ir.md`（`construct_lambda`、`is_lambda`、`<captures>`）
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/module_impl/frontend/frontend_gdcompiler_type_implementation.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`
- 明确非目标：
  - 不在本计划转正 `match`、parameter default、block-local `const`、`await`
  - 不在本计划实现 Godot `OPCODE_CREATE_SELF_LAMBDA` / `GDScriptLambdaSelfCallable`
    的独立 opcode；`self` 走既有 `construct_lambda` capture，
    `capturesSelf` 时 custom Callable 的 `object_id` 绑 enclosing instance
    （见 §3.5）
  - 不把 `CAPTURE` 纳入 direct-slot alias publication（见 §3.6）
  - 不复用 `construct_callable` / `construct_standalone_callable` 承载 lambda
    （`frontend_signal_support.md` 已禁止）
  - 不把 `GdCompilerType` 写入 lambda `<captures>` 或 lambda 参数/返回 ABI
  - 不在 property initializer / parameter default / skipped subtree 中开放 lambda
  - 不把 lambda 注册进 ClassDB / `_class_bind_methods`
  - 不实现 `Callable.bind` / `unbind` 新 lowering
  - 不更改 Godot 对普通局部/参数的 copy-on-capture 语义

---

## 1. 背景与当前起点

### 1.1 源码形态

GDScript lambda 是表达式级匿名函数：

```gdscript
var cb := func(offset: int) -> int:
    return seed + offset
sig.connect(func():
    pinged.emit())
```

Parser AST 为 `dev.superice.gdparser.frontend.ast.LambdaExpression`，已提供
`parameters()` / `returnType()` / `body()`。它不是 `FunctionDeclaration`，
没有源码级函数名，也不进入 class member namespace。

### 1.2 已落地、可复用的骨架

| 层 | 已有事实 | 文件 |
|----|----------|------|
| Scope 图 | `LambdaExpression` → `CallableScope(LAMBDA_EXPRESSION)` + `BlockScope(LAMBDA_BODY)` | `FrontendScopeAnalyzer.handleLambdaExpression` |
| Capture API | `CallableScope.defineCapture(...)`、`ScopeValueKind.CAPTURE`、`CaptureDef` | `CallableScope.java`、`CaptureDef.java` |
| Binding kind | `FrontendBindingKind.CAPTURE` 已存在，但生产路径几乎不产出 | `FrontendBindingKind.java` |
| Loop | lambda 是 callable boundary，重置 loop depth | `FrontendLoopControlFlowAnalyzer` |
| LIR insn | `ConstructLambdaInsn` / `CONSTRUCT_LAMBDA` / text+DOM parse | `lir/insn/ConstructLambdaInsn.java` |
| LIR function | `LirFunctionDef.isLambda`、`addCapture`（非 lambda 会抛） | `LirFunctionDef.java` |
| ABI 护栏 | `LirPublicAbiValidator` 拒绝 compiler-only capture type | `LirPublicAbiValidator.java` |
| C 模板 | `lambdaCaptureName`、`entry.h.ftl` capture typedef、`func.ftl` `_capture` 参数 | `template_451/*` |
| Custom Callable 先例 | `gdcc_new_standalone_callable` + `godot_callable_custom_create2` | `gdcc_callable.h` |

### 1.3 当前 fail-closed 封口点

这些封口必须按阶段显式翻面，禁止“某一个 analyzer 单独偷开”：

| 封口 | 位置 | 当前行为 |
|------|------|----------|
| 结构策略 | `FrontendBodySemanticSupportPolicy` | `LAMBDA_BODY` / `LAMBDA_EXPRESSION` → `LAMBDA_SUBTREE(false, false)` |
| Variable inventory | `FrontendVariableAnalyzer` | `sema.unsupported_variable_inventory_subtree`；不 bind param/local/capture |
| Interface | `FrontendInterfacePhase.handleLambdaExpression` | `SKIP_CHILDREN`；body 不进 suite entry / declaration index |
| Suite owner | `FrontendSuiteResolver.callableBody/callableParameters` | 只认 Function/Constructor；lambda 返回 `null` / empty |
| Visible value | `FrontendVisibleValueResolver` | AST 边 + current-scope backstop → `DEFERRED_UNSUPPORTED` / `LAMBDA_SUBTREE` |
| Top / chain binding | `FrontendBodyOwnerProcedures` | `sema.unsupported_binding_subtree` / `sema.unsupported_chain_route`；`walkRootBounded` 剪枝 |
| Expr typing | `FrontendExpressionSemanticSupport.resolveLambdaExpressionType` | `UNSUPPORTED`："Lambda expression typing is not supported..." |
| Type-check | `FrontendTypeCheckAnalyzer.handleLambdaExpression` | `SKIP_CHILDREN` |
| Compile surface | `FrontendCompileCheckAnalyzer.walkExpression` | 不进入 lambda；依赖上游 unsupported |
| Inspection | `FrontendAnalysisInspectionTool.hasUnsupportedOrDeferredAncestor` | 把 `LambdaExpression` 硬编码为 deferred 祖先 |
| Skeleton | `FrontendClassSkeletonBuilder.toLirFunction` | 只从 `FunctionDeclaration` 建 `LirFunctionDef` |
| Func pre-pass | `FrontendLoweringFunctionPreparationPass.visitStatements` | 不扫描表达式里的 lambda |
| CFG alias | `FrontendCfgGraphBuilder.requireDirectSlotAliasRoot` | `CAPTURE` fail-fast |
| C codegen | `ConstructInsnGen` | 无 `CONSTRUCT_LAMBDA` case |
| DOM serialize | `DomLirSerializer` | 写空 `<captures/>`，丢失 capture 描述 |

### 1.4 Godot 参考（语义对齐，不复制 VM）

Godot 4 实现（`godotengine/godot`）：

- Analyzer：`modules/gdscript/gdscript_analyzer.cpp` 在解析 identifier 时，
  沿 `source_lambda -> parent_function` 链把外层 local/parameter 记入
  `LambdaNode.captures` / `captures_indices`；中间层 nested lambda 一并记录。
- Parser 节点：`LambdaNode.use_self`、`captures`。
- Codegen：`write_lambda(..., p_use_self)` → `OPCODE_CREATE_LAMBDA` 或
  `OPCODE_CREATE_SELF_LAMBDA`。
- Runtime：`GDScriptLambdaCallable`（`function->call(nullptr, ...)`，
  `get_object()` 返回 **script**）与 `GDScriptLambdaSelfCallable`
  （`function->call(instance, ...)`，`get_object()` 返回 **instance**）。
- Capture 在构造时拷进 `Vector<Variant>`；调用时插在用户实参前面。
  对象 capture 若已被释放则替换为 `null`。

GDCC 已冻结的 LIR 是 `construct_lambda "<name>" $cap...` + userdata 结构体 +
`free_func` 析构，**没有**独立 self-lambda opcode。本计划按该 LIR 落地，
Godot self-lambda 的 object-id / disconnect-by-object 差异记入 §3.5 与 §10。

---

## 2. 目标、职责边界与转正顺序

### 2.1 目标

1. 在 supported executable body（函数 / 构造函数，以及其内部已支持 block）中，
   `LambdaExpression` 成为正式表达式：结果类型为 `GdCallableType`，可赋给
   `Callable` 槽、作为 `Signal.connect` / `Callable.call` 实参。
2. lambda 拥有完整 lexical inventory：parameter、ordinary local、capture。
3. lambda body 无条件进入 shared semantic（禁止 typed-dependent body gate）。
4. 外层 lowering 发射 `construct_lambda`；合成 hidden `LirFunctionDef`
   （`is_lambda=true`）承接 body CFG / LIR。
5. C backend 生成 capture 结构体、custom Callable、`free_func` 与 lambda 函数体。
6. compile-only gate 仅在 lowering + backend + 文档 + 测试全部闭环后解封。

### 2.2 职责拆分

| 阶段 | 组件 | 职责 |
|------|------|------|
| Scope | `FrontendScopeAnalyzer` | 已建双层图；本计划不改图形状 |
| Inventory | `FrontendVariableAnalyzer` | bind lambda param/local；推导并 `defineCapture`（`Variant` 占位）。**不**写 `lambdaPlans()` |
| Capture plan | 阶段 C 独立 owner | 在 nested resolve 入口填声明处类型后**第一次**发布 `FrontendLambdaPlan` |
| Interface | `FrontendInterfacePhase` | 把 `LambdaExpression` 记为 callable owner；body 进 suite entry |
| Suite | `FrontendSuiteResolver` | 把 lambda 当 nested callable owner 解析 |
| Expr typing | `FrontendExpressionSemanticSupport` | 发布 `RESOLVED(GdCallableType)` |
| Type-check | `FrontendTypeCheckAnalyzer` | 消费 Callable 事实；遍历 lambda body |
| Loop | `FrontendLoopControlFlowAnalyzer` | 保持 callable boundary（不断开） |
| Skeleton / pre-pass | `FrontendLoweringFunctionPreparationPass` | 合成 hidden lambda `LirFunctionDef` + context |
| CFG | `FrontendCfgGraphBuilder` | 新增 lambda value item；capture 读数；`CAPTURE` 仍非 alias root |
| Body lowering | 新增 processor | 发射 `ConstructLambdaInsn` |
| Backend | `ConstructInsnGen` + `gdcc_callable.h` + 模板 | custom Callable + capture copy/free |
| Compile gate | `FrontendCompileCheckAnalyzer` | 阶段 I 才把 lambda 纳入 compile surface |

### 2.3 强制转正顺序

复制 `frontend_resolution_pipeline_implementation.md` §2 与 for-in 模板：

```
scope graph（已完成）
  → 完整 lexical inventory（param / local / capture 名）
  → declaration index / typed baseline / suite entry
  → SuiteResolver body entry（无条件）
  → typed resolution（capture 类型取外层绑定的声明处类型，含 `:=` 推断）
  → type-check
  → 合成 LirFunctionDef + CFG + construct_lambda
  → C backend
  → compile gate 解封
```

任一阶段不得靠 `PENDING` / typed readiness 决定是否进入 lambda body。
compile gate 的 readiness 是 lowering 边界，不是 semantic body entry gate。

### 2.4 MVP 支持面

正式支持：

- 出现在 function / constructor executable body（含 `if` / `while` / `for` body、
  普通 block）中的 `LambdaExpression`。Interface / Suite 只在
  `supportedBodyDepth > 0` 时把 lambda 记为 callable owner；property
  initializer、class-level 表达式、parameter default 中的 lambda 不得
  `recordCallable`
- 嵌套 lambda（内层 capture 必须同时写入中间层，见 §3.4）
- 显式参数类型、显式/缺省返回类型（缺省按既有 `resolveTypeOrVariant → Variant`）
- 无 capture、捕获外层 `ScopeValueKind.PARAMETER` / `LOCAL`（含 ordinary
  `var` 与 for 迭代器）/ 外层 `CAPTURE`
- 捕获 enclosing instance 的 `self`（作为普通 capture 名，见 §3.5）
- lambda 值赋给 `var` / `Callable` 参数 / `Signal.connect` / `Callable.call`

继续 fail-closed：

- property initializer、parameter default、class-level 表达式中的 lambda
- lambda 自己的 parameter default
- lambda body 内的 `match`、block-local `const`、`await`
- `CAPTURE` 作为 direct-slot alias root
- 把 class member / global / utility / type-meta 收成 capture
- 静态函数里使用 `self` 或 instance member（沿用既有 `ResolveRestriction`）

---

## 3. 冻结合同（实施前必须遵守）

### 3.1 结构策略翻转

`FrontendBodySemanticSupportPolicy` 必须改为：

- `forBlockScopeKind(LAMBDA_BODY)` → `EXECUTABLE_BODY`
- `forCallableScopeKind(LAMBDA_EXPRESSION)` → `EXECUTABLE_BODY`

该翻转必须落在 **阶段 B**（与 inventory 同提交），不能拖到阶段 C。
原因：`FrontendVariableAnalyzer.bindLocal` 经
`FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(kind)`
读取 `publishesLexicalInventory()`；`LAMBDA_BODY` 在翻转前会把 body local
报成 `sema.variable_binding` “expected supported executable BlockScope”。

阶段 B 时 `FrontendInterfacePhase.handleLambdaExpression` 仍 `SKIP_CHILDREN`，
`FrontendSuiteResolver` 尚无 `LambdaExpression` 分支，因此
`entersSuiteResolver=true` 没有活消费者。阶段 C 再打开 interface / suite /
resolver。不得把 policy 拆成两个 boolean 分两次翻。

`FrontendVisibleValueDomain.LAMBDA_SUBTREE` 枚举值保留，但生产路径从阶段 B
policy 翻转起不再映射到它。`MATCH_SUBTREE` / `PARAMETER_DEFAULT` /
`BLOCK_LOCAL_CONST_SUBTREE` 不得被这次改动打开。

### 3.2 Lambda 是 nested callable owner，不是 for 那种 header-only statement

`ForStatement` 的 body 是**同一** enclosing callable 的 child suite。
`LambdaExpression` 是**新的** callable：

1. `FrontendInterfacePhase.handleLambdaExpression` 仅当 `supportedBodyDepth > 0`
   时调用 `recordCallable(lambda, parameters, body)`。这与
   `handleVariableDeclaration` 在 depth=0 时走 property-init 而不 bind local
   的守卫同构。depth=0（property initializer / class-level 表达式）必须
   `SKIP_CHILDREN` 且不进 `callableOwners`。
2. `FrontendSuiteResolver.callableBody/callableParameters/restrictionForCallable/isStaticCallable`
   增加 `LambdaExpression` 分支。
3. **解析时机**：不要把 lambda 仅丢进顶层 `callableOwners` 循环、在外层函数
   **之后**才解析。外层语句的 expr typing 会先碰到 `LambdaExpression`。
   正确顺序：
   - Interface 对 supported-body 内的 lambda 先无条件发布 inventory /
     baseline / suite entry。
    - 外层 **非 silent** 的 expr-type owner 路径在发布 `GdCallableType`
      **之前**，若该 lambda 已 `recordCallable` 且尚未解析，则调用
      `FrontendSuiteResolver.resolveCallableOwner(lambda)`
      （独立 `FrontendCallableExportBatch`）。这是 nested resolve 的
      **唯一**生产触发点。
    - 局部稳定化的 **silent resolver** 必须把 `LambdaExpression`
      initializer 列入 fail-closed（`var cb := func(): ...` 的 slot
      保持 inventory `Variant`，不写 side table、不发诊断、不触发
      nested resolve）。`:=` 推断为 `GdCallableType` 若要发生，只能由
      非 silent 路径在 nested resolve 完成、表达式类型已发布后，按
      `frontend_local_type_stabilization` 既有单调写回决定；不得为了
      稳定化去解析 lambda body。
    - 未 `recordCallable` 的 lambda（property-init / default / skipped）
      **不得**触发 nested resolve；保持 deferred / unsupported。
    - 用 AST-identity side table 标记已解析，避免顶层循环二次解析。
4. lambda 的 static/instance restriction **继承 enclosing callable**：
   - enclosing `FunctionDeclaration.isStatic()` → `ResolveRestriction.staticContext()`
   - 否则 `instanceContext()`
   - 嵌套 lambda 继续继承最外层 enclosing 非 lambda callable。

### 3.3 表达式类型

`resolveLambdaExpressionType` 成功时发布：

- `FrontendExpressionType.resolved(new GdCallableType())`
- 不在 MVP 填 `arguments` / 特化 `returnType`（与 method-as-value /
  `construct_callable` 的 unparameterized `Callable` 对齐）
- 不稳定依赖（参数类型解析失败等）按既有 `propagated(...)` 传递，不得伪装成
  `UNSUPPORTED`

仅 **已 `recordCallable`** 的 lambda 节点不再发
`sema.unsupported_expression_route` / `sema.unsupported_binding_subtree` /
`sema.unsupported_chain_route`。未记录的 lambda（property-init 等）必须
继续由 `FrontendBodyOwnerProcedures` 按位置发 unsupported，避免
`resolveLambdaExpressionType` 对无 suite entry 的 body 触发
`FrontendBodyStructuralCompleteness` fail-fast。

### 3.4 Capture 推导

新增 `FrontendLambdaCapturePlanner`（或等价 support），在 variable inventory
阶段按**名字**推导。capture **类型**取外层绑定的**声明处类型**
（显式标注，或 `:=` 在该声明上的推断/稳定化结果），不是 inventory
baseline，也不是声明之后的断言或精化。

**可捕获**（scope lookup 命中的是 `ScopeValueKind`，不是
`FrontendBindingKind`）：

- 最近 enclosing supported callable 中的 `PARAMETER`
- 最近 enclosing supported executable block 中的 `LOCAL`
  （ordinary `var` 与 for 迭代器都是 `LOCAL`）
- 外层 lambda 已发布的 `CAPTURE`（嵌套传递）
- enclosing instance callable 的 `self`（§3.5）

**不可捕获**（按普通词法继续解析，不进 `<captures>`）：

- 当前 lambda 自己的 parameter / local
- class property / signal / method / 内层 class type-meta
- global / singleton / utility / enum
- 尚未转正域里的名字（match pattern、block-local const、parameter default）

**算法（对齐 Godot innermost-first，但不依赖 Godot AST）**：

1. 先 bind 当前 lambda 的 parameters，再 bind body ordinary locals。
2. 扫描 lambda body 中所有 `IdentifierExpression`（含嵌套表达式；跳过
   声明站点本身，即 `Parameter.name` / `VariableDeclaration.name`）。
3. **从该 identifier 自己的 scope 向上**做 value lookup（innermost-first），
   **禁止**一律从 lambda `CallableScope.parent` 查找。
4. 仅当第一次命中落在**当前 lambda callable 边界之外**，且
    `ScopeValueKind` 为 `PARAMETER` / `LOCAL` / `CAPTURE` 时，才记为
    当前 lambda 的 capture。当前 lambda 自己的 parameter / local 命中
    → 不捕获。
5. 若该名沿 parent lambda 链仍可见，中间层也 `defineCapture`（嵌套传递）。
6. 同一名字只记一次；顺序按**首次出现的源码顺序**冻结，供 LIR operand 顺序使用。
7. capture 与 parameter 冲突时 `ensureCallableValueSlotAvailable` 仍 fail-fast；
   生产路径必须在 define 前做 diagnostic + skip，不要让用户源码打到
   `IllegalArgumentException`。
8. planner 按 **post-order** 运行：先完成嵌套 lambda 的 param/local bind 与
   其自身 capture plan，再扫描外层。外层扫描依赖内层 scope 已就位；
   否则内层尚未 bind 的 param/local 会被误判成外层 capture（H2 同类缺陷）。
9. 中间层传递：当且仅当 source binding 位于中间层 lambda 边界之外，且
   中间层无同名 param/local 遮蔽时，中间层 `defineCapture` 同名条目
    （所有传递层共享同一个 `sourceDeclaration`，声明处类型同源）。
    中间层遮蔽时传递在该层终止，内层改为捕获中间层自己的 param/local。

反例（必须作为阶段 B negative path）：

```gdscript
func f():
    var x = 1
    var cb := func():
        var x = 2
        return x    # 指 lambda local，不得捕获外层 x
```

**类型（已冻结：声明处类型，含 `:=` 推断；后续断言不影响）**：

Capture 类型等于外层被捕获绑定在其**声明语句完成时**的 source-facing
类型。阶段 B 只登记名字与 `sourceDeclaration`，`defineCapture` 先写
`Variant` 占位。真实类型在 **该 lambda 自己的 nested suite resolution
开始、其 body 语句尚未处理时** 填充，写入后立即冻结。

**`lambdaPlans()` 首次发布（已冻结，2026-08-17）**：

- 阶段 B **不得**把 `Variant` 占位写入 `FrontendAnalysisData.lambdaPlans()`。
  inventory 只通过 `CallableScope.defineCapture` 暴露名字；inspection /
  阶段 B 测试读 scope 上的 `CAPTURE` 绑定，不读 side table。
- 阶段 C 在 nested resolve 入口填完声明处类型后，**第一次** publish
  完整 `FrontendLambdaPlan`（`LambdaCaptureEntry.type` 已是声明处类型，
  `capturesSelf` 已按 §3.5 与 leading `self` 对齐）。
- `applyPatch` / `mergeSideTable` 保持 first-wins。禁止对同一
  `LambdaExpression` 先发占位再 `withType` 走 patch 覆盖。
  `LambdaCaptureEntry.withType` 只用于构造最终 plan 的本地组装，不是
  side-table 回写 API。
- 阶段 C 必须新增**独立** semantic stage + `OverlayFacts.lambdaPlans`
  槽 + 专用 `FrontendOwnerPatch`（建议名 `LAMBDA_RESOLUTION` /
  `FrontendLambdaResolutionPatch`）。**不得**把 plan 折进
  `FrontendExprTypePatch`。`FrontendPatchTransaction.order` 必须给
  该 stage 编号；位置在 inventory 之后、且必须能在外层 EXPR_TYPE
  发布 `GdCallableType` **之前**随 nested resolve export。
- lowering 只消费已发布的完整 plan；缺 plan 仍 fail-fast，禁止
  现场从 scope 重推导。

填充必须同时更新三处，禁止只写 plan 不写 scope：

- `LambdaCaptureEntry.type`（随第一次 publish 写入 `lambdaPlans()`）
- LIR `<captures>`（lowering 时消费 **已发布** plan，不再回读 scope）
- 该 lambda `CallableScope` 上的 `CAPTURE` 绑定：新增
  `resetCaptureType`（镜像 `BlockScope.resetLocalType` 的单调
  `Variant → exact` 守卫与 declaration 身份校验）。body type-check
  经 `effectiveScopeValue` 读 CAPTURE 时**不会**套 overlay，因此
  scope 绑定必须与 `LambdaCaptureEntry.type` 同源。

**读取路径**（禁止读物理 scope slot，禁止回落 `slotTypes()` /
`VAR_TYPE_POST`）：

1. 填充时点发生在外层语句 mid-suite。`BlockScope.resetLocalType`
   只在外层 callable 的 `exportBatch.applyTo(analysisData)` 时执行，
   此刻物理 slot 仍是 inventory `Variant`。
2. 必须走外层当前 typed environment 上的 **declaration-anchored**
   查询：按 `sourceDeclaration` + owning `BlockScope` 对象身份匹配
   已 flush 的 `LOCAL_TYPE_STABILIZATION` / `FOR_ITERATION_RESOLUTION`
   update（`FrontendTypedLexicalEnvironment.localSlotType` 的 overlay
   段）。这不是“求值点最新 effective 类型”，而是该声明自己的
   已提交稳定化事实。
3. 不得复用 `localSlotType` 在无 update 时回落到 `slotType(astNode)`
   （那会读到 `VAR_TYPE_POST` / 函数摘要）。无 matching update 时
   取声明类型或 inventory baseline。
4. 只有上述两个 stage 能写 slot update（`FrontendAnalysisData`
   强校验）且写回单调 `Variant → exact`，因此“该声明的最新
   update”≡“该声明自己的稳定化结果”。日后若新增会写 slot 的
   断言 stage，会先撞该校验——这就是“声明之后的断言不改 capture”
   的执行点。

| 外层绑定 | capture 类型 |
|----------|----------------|
| 显式标注 `PARAMETER` / `var x: T` / `var x: T = ...` | 声明类型 `T` |
| 未标注 `PARAMETER` | `Variant` |
| `var x := 1`（`:=` 推断） | 该声明的局部稳定化结果，此处为 `int` |
| `var x := expr` 且 initializer 未能稳定 | 保持 `Variant` |
| `var x = 1`（无标注、非 `:=`） | `Variant`（不因 initializer 是 `1` 而推断 `int`） |
| for 迭代器（`ScopeValueKind.LOCAL`，声明身份是 `ForStatement`） | `FOR_ITERATION_RESOLUTION` 在进入 for body 前已提交的精化结果 |
| 外层 lambda 的 `CAPTURE` | 该外层 capture 已冻结的声明处类型 |
| `self`（§3.5） | enclosing class 的 `GdObjectType` |

`:=` 的 `int` 来自**那条声明**已 flush 的稳定化 update，不是物理
scope 写回，也不是函数末尾摘要。阶段 B inventory 仍会先给推断
局部写 `Variant` baseline；若在 nested resolve 入口读物理 slot，
`var x := 1` 会错成 `Variant`。

capture 合法性以外层声明在 **lambda 语句处按 declaration-order
可见**为前提。`var cb := func(): return x` 后接 `var x := 1` 时，
planner 可能仍登记名字，但该条目不得进入 lowering；use-site
走既有 `DECLARATION_AFTER_USE_SITE`。不可见条目不得填进
`<captures>`。

明确禁止：

- 用声明**之后**的赋值、类型断言、后续 overlay、`VAR_TYPE_POST` 或
  函数末尾 slot 类型回写 `CAPTURE` / `FrontendLambdaPlan` / `<captures>`。
  即使后来断言 `x` 为其他具体类型，capture 仍是声明处类型。
- 对 `var x = 1` 因看到 initializer `1` 而把 capture 写成 `int`。
- 只更新 `LambdaCaptureEntry.type` 却让 `CallableScope` 的 CAPTURE 停在
  `Variant` 占位。
- 引入 compiler-only 类型。`LirPublicAbiValidator` 继续拒绝
  compiler-only capture。

例：

```gdscript
var a := 1          # 声明处稳定为 int → capture int
var b = 1           # 声明处 Variant → capture Variant
var cb := func():
    return a + b    # a:int 副本，b:Variant 副本
# 此后即使断言 b 为 int，或再给 a 做其它精化，都不改 capture ABI
```

lambda body 的 type-check / lowering 只消费这份声明处类型。外层槽后续
变化只影响外层语句。

**Godot 语义**：capture 是**构造时拷贝**。lambda 内对 capture 名赋值只改
lambda 自己的副本，不写回外层 local。对象/容器元素的可变性来自共享对象身份，
不是 live-slot alias。

### 3.5 `self`：用 capture，不新增 opcode

Godot 用 `use_self` + `GDScriptLambdaSelfCallable` 把 Callable 绑到 instance。
GDCC LIR 只有 `construct_lambda` + capture 列表。

MVP 冻结：

- 若 lambda body 出现显式 `SelfExpression`，或 instance restriction 下的
  implicit instance member / instance method 调用需要 receiver，则把
  enclosing executable 的 `self` 收为名为 `self` 的 capture。
- `capturesSelf == true` **当且仅当** capture 列表的**第一项**名为 `self`。
  其余 capture 仍按首次出现的源码顺序排在其后。不得把 `self` 插在中间，
  也不得在没有 leading `self` 时把 flag 设为 true。
- 该 capture 的类型是 enclosing class 的 `GdObjectType`（与函数 `self` 参数一致）。
- lowering 把外层 `self` slot 作为 `construct_lambda` 的一个 capture operand。
- 合成 lambda `LirFunctionDef` **不**再注入第二份 `ensureExecutableSelfParameter`；
  `self` 只出现在 `<captures>` / `_capture->self`。
- lambda `LirFunctionDef` 一律 `setStatic(true)`（§3.7），避免
  `declareSelfSlotIfNeeded` 造 stray `self`。仅当 `capturesSelf` 时把
  `self` 放进 `<captures>`。`Kind.LAMBDA_BODY` 仍必须新增（CFG/body
  pass 的 sourceOwner 门闩），但 self 槽策略以 `setStatic(true)` 为准，
  不要两套并行。
- CFG / body lowering 的 self capture **operand** 不得伪造
  `IdentifierExpression + SELF`（该路径会 fail-fast）。必须用专用
  descriptor（例如 `SELF_SLOT`）直接读 enclosing executable 的 `self` 槽。
- 静态 enclosing callable 中使用 `self`：沿用既有 restriction diagnostic，
  不合成非法 capture。

**`object_id`（已确认，对齐 Godot self-lambda）**：

- `capturesSelf == true`：`GDExtensionCallableCustomInfo2.object_id` 必须填
  enclosing `self` 在 **`construct_lambda` 求值点** 的
  `GDObjectInstanceID`（fat pointer 已缓存的 `instance_id`，或
  `gdcc_object_id_from_raw` 仅当 raw 已保证 live）。
  这样 `Callable.get_object()` / Signal 连接把这颗 Callable 绑到实例：
  实例 `free` / `queue_free` 后连接自动失效，后续 `emit` 不得再进
  `call_func`。
- `capturesSelf == false`：`object_id = 0`。GDCC 没有与 Godot
  `GDScript` 资源对等的 script 对象可绑；不得把 enclosing instance
  或 class singleton 偷偷填进去。
- **不得**改 `construct_standalone_callable` / `gdcc_new_standalone_callable`
  的 `object_id = 0`，也不得改 `construct_callable` 的 receiver 合同。
- `is_valid_func`：`object_id != 0` 时必须提供；对象已从 ObjectDB
  消失则返回 false。不得只靠 `call_func` 里解引用悬空 `self` 来“发现”
  失效。
- `self` capture 字段仍按 ownership 合同 copy/release；`object_id`
  只负责 Godot 侧身份 / disconnect，不替代 capture 里的对象指针。

### 3.6 `CAPTURE` 与 writable / alias

- `CallableScope.defineCapture` 现有 `writable=true`：允许 lambda **内部**
  对 capture 名赋值（写副本）。
- `FrontendCfgGraphBuilder.requireDirectSlotAliasRoot` 对 `CAPTURE` 继续
  fail-fast。本计划**不**把 capture 当 live-slot alias。
- `FrontendOpaqueExprInsnLoweringProcessors` 已把 `CAPTURE` 当符号名解析：
  lambda **body** 内读 capture 名，按该函数的 local / capture 变量降低。
- 外层函数里，被 capture 的 local 仍按普通 `LOCAL_VAR` 读写；构造 lambda 时
  读一次快照。

### 3.7 合成函数身份

每个通过 compile surface 的 `LambdaExpression` 对应恰好一个 hidden
`LirFunctionDef`：

- 名字：`_lambda_<k>`，`k` 在 owning `LirClassDef` 内从 0 递增，稳定且与
  AST 遍历顺序一致（先声明顺序，再源码出现顺序）。
- `setLambda(true)`、`setHidden(true)`、`setStatic(true)`。
  `setStatic(true)` 是为了让 `FrontendBodyLoweringSession.declareSelfSlotIfNeeded`
  不给 lambda 造 stray `self` 变量；`isLambda` 已跳过 ClassDB bind，
  无绑定副作用。`self` 只作为 §3.5 capture 存在。
  **禁止**再写 `setStatic(false)`。
- 参数表 = lambda 源码参数（含类型）；**不含** `self` 参数。
- `<captures>` = `FrontendLambdaCapturePlan` 冻结列表。
- 返回类型 = `FrontendDeclaredTypeSupport.resolveTypeOrVariant(lambda.returnType())`。
- 不进入 `ClassScope.defineFunction` / 不进 ClassDB bind。
- 命名不得与用户函数或 `_field_init_` / `_field_getter_` / `_field_setter_` 冲突；
  `_lambda_` 前缀视为 compiler-owned（若用户声明同名前缀，skeleton 发
  `sema.class_skeleton` 并跳过用户声明，与 `_field_*` 合同平行）。

Side table（建议挂在 `FrontendAnalysisData`）：

```
IdentityHashMap<LambdaExpression, FrontendLambdaPlan>
```

`FrontendLambdaPlan` 至少包含：

- `LambdaExpression` AST 身份
- 合成名 `_lambda_<k>`
- 有序列表 `captures: List<LambdaCaptureEntry(name, type, sourceKind, sourceDeclaration)>`
- `capturesSelf: boolean`
- enclosing callable AST
- owning class canonical name

### 3.8 LIR / C ABI

沿用 `doc/gdcc_low_ir.md`：

```
$result = construct_lambda "<lambda_function_name>" $capture1 $capture2 ...
```

- 无 capture：userdata = `NULL`，`free_func` 仍可 no-op。
- 有 capture：拷入 heap 上的 `${Class}_Capture_${func}`，
  `callable_userdata` 指向该块；`free_func` 按字段析构后 `godot_mem_free`。
- `object_id` 按 §3.5：`capturesSelf` → enclosing `self.instance_id`，
  否则 `0`。该字段在 `godot_callable_custom_create2` 时写入，之后不改。
- `call_func` 把 userdata 解成 `_capture`，再调
  `${Class}_${lambdaName}($arg..., _capture)`。进入 `call_func` 前若
  `object_id != 0` 且对象已死，应被 Godot / `is_valid_func` 挡掉，
  不得解引用 `_capture->self`。
- 用户可见 arity = lambda 源码参数个数（**不含** capture）。
- `DomLirSerializer` 必须写出真实 `<capture name type>`，不得再写空节点。
- `func.ftl` 现有 bug：`<#list func.captureList as capture>` 对 **每个**
  capture 元素都发射一次 `_capture` 形参。`captureCount == 1` 碰巧正确；
  `captureCount > 1` 会得到多个无逗号分隔的 `_capture` 形参。必须改成
  `captureCount > 0` 时只发射一次。
- lambda 函数 prologue：把 `_capture->name` 拷入 `LirFunctionDef` 为该
  capture 登记的 local `$name`（`addCapture` 已 `variables.put(name, ...)`）。
- `CCodegen.generateFunctionPrepareBlock` 会给每个非参数 variable
  发射默认构造（`String` → `""`，`Array` → 新数组等）。capture local
  必须从该自动初始化中排除，再由 prologue 从 `_capture->name` 拷入；
  否则 destroyable 类型会泄漏默认构造值。问题在 `CCodegen.java`，
  不是 `entry.c.ftl` 的“未初始化 local”。

### 3.9 诊断

| 场景 | category | 策略 |
|------|----------|------|
| 仍未转正的位置出现 lambda（property init 等） | 保持 `sema.unsupported_binding_subtree` 或 inventory 对应 category | diagnostic + skip |
| lambda 内 parameter default | 与普通函数相同的 deferred default 诊断 | 不打开 default 语义 |
| capture / parameter 同名冲突 | `sema.variable_binding` 既有 duplicate/shadow 类 | diagnostic + skip define |
| 静态上下文捕获 `self` | 既有 restriction / `sema.type_check` 或 lookup | 不新增平行语义 |
| compile 时 lambda 缺 plan / 缺合成函数 | `sema.compile_check` 或 lowering fail-fast | 缺 published fact 不得重绑 |
| 用户函数名以 `_lambda_` 开头 | `sema.class_skeleton` | 跳过该 member |

普通源码错误走 `DiagnosticManager` + skip subtree，不得当异常控制流。
缺失/冲突的 **published** fact 在 lowering 侧 fail-fast。

---

## 4. 分阶段实施步骤

每阶段必须可运行、可回归、可单独提交。未到阶段 I 前，
`analyzeForCompile` 对 lambda 的 compile surface 仍可保持跳过或
“有 lambda 则 blocker”，但 **shared `analyze()`** 从阶段 C 起必须进入 body。

### 阶段 A — 数据面与合同测试（不翻支持面）

**目标**：先把 plan / side table / 命名 / 模板缺陷修掉，生产封口不变。

**状态**：已落地（2026-08-17）。

**实施内容**：

- 新增 `FrontendLambdaPlan` / `FrontendLambdaCapturePlan` / `LambdaCaptureEntry`。
- `FrontendAnalysisData` 增加 lambda plan map 的存取 API。
- 单测 `ScopeCaptureShapeTest` 风格的 planner 纯函数测试可先用手工 scope 图。
- 修复 `func.ftl`：`_capture` 只发射一次（纯模板修复，不改变语义封口）。
- `DomLirSerializer` 写出真实 `<capture>`（可先用手工 LIR fixture 测）。

**落地记录**：

- 数据类：`LambdaCaptureEntry`、`FrontendLambdaCapturePlan`、`FrontendLambdaPlan`
  （`frontend/sema`）。`LambdaCaptureEntry.withType` 只用于本地组装最终 plan，
  不是 side-table 回写。`capturesSelf` 当且仅当列表第一项名为 `self`。
- Side table：`FrontendAnalysisData.lambdaPlans()` /
  `updateLambdaPlans(...)`，keyed by `LambdaExpression` identity。
  现有 owner patch 通过 `FrontendOwnerPatch.lambdaPlans()` 默认空表接入
  `applyPatch`；阶段 A 不新增 semantic stage，也不把 plan 折进
  `FrontendExprTypePatch`。完整 plan 的第一次生产发布在阶段 C。
- Planner：`FrontendLambdaCapturePlanner` 纯函数，输入手工 scope 图 +
  有序 `IdentifierUse`；不写 scope / side table / 诊断。嵌套传递通过
  `ParentLambda(callable, body)` 从父 lambda **body** 向上查找，避免
  漏掉中间层 local 遮蔽。
- `func.ftl`：`captureCount > 0` 时只发射一次 `_capture`。
- `DomLirSerializer`：lambda 按 `getCaptureList()` 写出
  `<capture name type>`；`LirFunctionDef.captures` 改为 `LinkedHashMap`
  以冻结插入序。
- 测试：`FrontendLambdaCapturePlannerTest`、
  `FrontendLambdaPlanSideTableTest`、`FuncHeaderCaptureTemplateTest`、
  `DomLirSerializerTest` 往返 / ABI / 非 lambda `addCapture`。
  `FrontendVariableAnalyzerTest.analyzeWarnsForDeferredLambdaSubtrees*`
  保持 fail-closed 红字断言。

**验收细则**：

- happy path：手工构造 `is_lambda` + 两个 capture 的 `LirFunctionDef`，
  DOM 往返后 `getCaptureList()` 完整。
- happy path：`func.ftl` 对 `captureCount > 1` 只出现一个 `_capture` 形参
  （不是“两条路径重复”，而是 list 按元素各发射一次）。
- negative path：非 lambda `addCapture` 仍抛；compiler-only capture 仍被
  `LirPublicAbiValidator` 拒绝。
- 现有 `FrontendVariableAnalyzerTest.analyzeWarnsForDeferredLambdaSubtrees*`
  **仍红字不变**（本阶段不翻 inventory）。

### 阶段 B — Policy 翻转 + Variable inventory 解封（仍不进 SuiteResolver）

> 状态：已落地（2026-08-17）。policy 已翻转；`bindLambdaInventory` 按 post-order 绑定
> param / local / capture（`Variant` 占位）；reporter 停止对 supported executable 内
> lambda 报 inventory 错；`self` capture（显式 / 隐式 instance member / static 抑制）
> 已接线；`lambdaPlans()` 保持为空。

**目标**：lambda param / local / capture **名字**进入 scope；去掉
`sema.unsupported_variable_inventory_subtree`。

**实施内容**：

- **先做 §3.1 policy 翻转**（`LAMBDA_BODY` / `LAMBDA_EXPRESSION` →
  `EXECUTABLE_BODY`），否则 `bindLocal` 会因
  `canPublishCallableLocalValueInventory(LAMBDA_BODY)==false` 发
  `sema.variable_binding`。同步更新
  `FrontendBodySemanticSupportPolicyTest` 的 lambda 行。
- `FrontendVariableAnalyzer.handleLambdaExpression` 改为
  `bindCallableParameters(lambda, params, body)`，不再 SKIP。
- `UnsupportedVariableBoundaryReporter.handleLambdaExpression` 停止对
  **supported executable 内**的 lambda 报 inventory 错。
- 引入 planner：在 bind param/local 之后按 §3.4 innermost-first
  `defineCapture`。本阶段只登记名字 + `sourceDeclaration`，
  `defineCapture` 的类型用 `Variant` 占位。**禁止**
  `updateLambdaPlans` / overlay 发布占位 plan。真实类型与
  `FrontendLambdaPlan` 的第一次 publish 按 §3.4 留到阶段 C
  nested resolve 入口。
- **本阶段不改** `FrontendInterfacePhase.handleLambdaExpression`（仍
  `SKIP_CHILDREN`），因此 `entersSuiteResolver=true` 无消费者。
- property-init / parameter-default / skipped subtree 内的 lambda：
  variable analyzer 的 boundary reporter **只扫描 callable body**，
  不会走进 property initializer。property-init lambda 的 unsupported
  仍由 `FrontendBodyOwnerProcedures` 的 binding/chain 诊断负责
  （本阶段保持这些诊断）。不得误以为 Phase B 会给 property-init
  再发一条 inventory 错。

**验收细则**：

- happy path：`FrontendVariableAnalyzerTest` 原
  `analyzeWarnsForDeferredLambdaSubtreesWhileBindingOuterLocal` **翻转**：
  `lambdaScope.resolveValue("item")` 为 `PARAMETER`；
  `lambdaBodyScope.resolveValue("lambda_local")` 为 local；
  外层被引用的 `seed` 在 lambda `CallableScope` 上为 `CAPTURE`。
- happy path：嵌套 lambda 引用最外层 local 时，中间 lambda 也能
  `resolveValueHere` 到 `CAPTURE`。
- negative path：lambda local 遮蔽外层同名 local 时 **不得** capture
  （`var x = 1; func(): var x = 2; return x`）。
- negative path：嵌套 lambda 的 param/local 遮蔽外层名时，中间层不得
  被错误插入该 capture。
- negative path：`match` / block-local const 的 inventory 错仍在。
- negative path：property initializer 里的 lambda 仍发
  `sema.unsupported_binding_subtree`（owner 是 BodyOwnerProcedures，
  不是 variable inventory），不得 bind、不得 `recordCallable`。
- negative path：lambda 参数与将捕获的同名冲突 → 一条
  `sema.variable_binding`，不抛异常。
- negative path：本阶段结束时 `lambdaPlans()` 对任何 lambda 仍为空；
  capture 名只出现在 `CallableScope` 的 `CAPTURE` 绑定上。

### 阶段 C — Policy / resolver / interface / suite 解封

> 状态：已落地（2026-08-17）。resolver 已去掉 lambda AST 边与 kind 封口（callable scope
> gate 改为 policy 驱动）；interface 在 `supportedBodyDepth > 0` 时 `recordCallable`；
> suite 四个 helper 已加 `LambdaExpression` 分支；nested resolve 入口填充 capture
> 声明处类型（`declarationSiteLocalSlotType` 严格 overlay 查询 + `resetCaptureType`）
> 并经 `LAMBDA_RESOLUTION` / `FrontendLambdaResolutionPatch` 首次发布完整 plan；
> `walkRootBounded` 剪枝保留；未 record 的 lambda 继续发 unsupported binding/chain。
> `smallestContainingCallable` 已识别 `LambdaExpression`。已 record lambda 的表达式类型
> 仍发布 UNSUPPORTED 但不发诊断（`GdCallableType` 属阶段 D）。

**目标**：lambda body 成为 supported executable suite。shared `analyze()`
进入 body 并发布普通表达式事实。

**实施内容**：

- Policy 已在阶段 B 翻转；本阶段只打开 resolver / interface / suite。
- `FrontendVisibleValueResolver` 去掉 lambda AST 边与
  `LAMBDA_EXPRESSION` / `LAMBDA_BODY` current-scope 的 deferred 封口。
- `FrontendInterfacePhase.handleLambdaExpression`：仅
  `supportedBodyDepth > 0` 时 `recordCallable(...)`；depth=0 仍
  `SKIP_CHILDREN`。
- `FrontendSuiteResolver` 四个 helper 增加 `LambdaExpression`。
  `resolveCallableOwner` 对 lambda 必须在走 body 语句之前按 §3.4
  填充 capture 类型（declaration-anchored overlay + `resetCaptureType`），
  并经独立 `LAMBDA_RESOLUTION` owner 把完整 `FrontendLambdaPlan`
  **第一次**写入 overlay → suite export → `lambdaPlans()`。
  该填充 / publish 不是 body 进入条件。
- 新增 `FrontendSemanticStage.LAMBDA_RESOLUTION`（或等价名）、
  `FrontendLambdaResolutionPatch`、`OverlayFacts.lambdaPlans`。
  `toOwnerPatches` / `mergeFrom` / `hasFacts` / `clear` /
  `FrontendPublishedFactTypeGuard.checkOwnerPatch` 必须接线。
  不得复用 `FrontendExprTypePatch` 或改 `mergeSideTable` 覆盖语义。
- `FrontendBodyOwnerProcedures`：对**已 record** 的 lambda，把
  unsupported binding/chain 诊断换成 nested `resolveCallableOwner`
  触发。`walkRootBounded` 对 `LambdaExpression` 的剪枝**必须保留**，
  外层 owner 不得再 walk 进其 children 当普通表达式树。
  对**未 record** 的 lambda，继续发 unsupported binding/chain。
- `FrontendAnalysisInspectionTool.hasUnsupportedOrDeferredAncestor`
  中把 `LambdaExpression` 从硬编码 deferred 祖先列表移除（否则
  inspection 仍把已转正 lambda 标成 deferred）。
- 同步检查 `smallestContainingCallable`：若它只认
  `FunctionDeclaration` / `ConstructorDeclaration`，lambda body 内诊断
  会回退到 class/file 锚点。阶段 C 应让它识别 `LambdaExpression`，
  或在文档中明确接受该回退。
- `FrontendBodySemanticSupportPolicyTest` 应已在阶段 B 翻转；本阶段
  补 resolver / interface 测试。

**验收细则**：

- happy path：`FrontendInterfacePhaseTest` /
  `FrontendSuiteResolverTest` 中 `lambda.body()` **进入**
  `suiteEntryRoots()` 与 `bodyDeclarationIndex()`。
- happy path：`FrontendVisibleValueResolverTest.resolveSealsLambdaBody*`
  **翻转**为可解析外层 capture / 当前 param / 当前 local。
- happy path：lambda body 内 `return seed` 对 `seed` 发布
  `symbolBindings` + `CAPTURE`。
- happy path：nested resolve 完成后 `lambdaPlans()[lambda]` 存在，
  capture 类型是声明处类型（`var a := 1` → `int`，`var b = 1` →
  `Variant`），且与 `CallableScope` 上对应 `CAPTURE` 同源。
  阶段 B 结束时同一 key **仍不存在**。
- negative path：对已发布 plan 再 patch 不同 payload 必须冲突；
  不得靠 `withType` + `applyPatch` 覆盖。
- negative path：`match` / const / parameter default 仍 `DEFERRED_UNSUPPORTED`。
- negative path：合成 `LAMBDA_BODY` scope 不再被 current-scope backstop 误封
  （原 `resolveRejectsSyntheticLambdaBodyCurrentScope*` 翻转或改为
  “无 AST 时仍按 EXECUTABLE_BODY 解析”）。

本阶段 **仍不必**让 `analyzeForCompile` 放行。

### 阶段 D — 表达式类型与 Callable 值

**目标**：lambda 表达式发布稳定 `RESOLVED(GdCallableType)`。

**实施内容**：

- `resolveLambdaExpressionType` 按 §3.2 / §3.3 先 nested-resolve body，
  再 `resolved(new GdCallableType())`。
- `FrontendTypeCheckAnalyzer.handleLambdaExpression` 改为 walk body
  （或依赖 suite 已走完、此处只消费外层赋值兼容）。
- 赋值 `var cb: Callable = func(): pass` 走既有 ordinary boundary。
- `var x: int = func(): pass` 发既有 `sema.type_check` 不兼容。

**验收细则**：

- happy path：`FrontendTypeCheckAnalyzerTest` /
  `FrontendBodyOwnerProceduresExprTypeTest` 中
  `var deferred_value := func(...): ...` 的 initializer 从 `UNSUPPORTED`
  变为 `RESOLVED(Callable)`。silent 稳定化不得解析该 initializer；
  该 local 是否随后精化为 `GdCallableType` 只由 nested resolve 完成后
  的非 silent 写回决定，缺写回则保持 `Variant`。
- happy path：`sig.connect(func(): pass)` 在 shared analyze 下不再出现
  `unsupported_binding_subtree`（compile_check 仍可暂缺或仍 blocker）。
- negative path：类型不兼容赋值仍只由 type-check 拥有，不重复 inventory 错。
- negative path：lambda 内仍未支持的 `match` 继续上游 owner 报错，
  不把整个 lambda 打回 `UNSUPPORTED`。

### 阶段 E — 合成 hidden `LirFunctionDef`

**目标**：每个 compile-bound lambda 在 lowering 前拥有完整 shell 函数。

**实施内容**：

- 在 `FrontendLoweringFunctionPreparationPass`（或紧前的专用 pass）扫描
  已发布 `FrontendLambdaPlan` 的 `LambdaExpression`。
- `owningClass.addFunction` 合成 `_lambda_<k>`，`setLambda` / `setHidden` /
  `setStatic(true)`（§3.7），`addCapture`，`fill` 参数与返回类型。
- 为每个 lambda 建 `FunctionLoweringContext`。**必须新增**
  `Kind.LAMBDA_BODY`（或等价），不得复用 `Kind.EXECUTABLE_BODY`：
  `FrontendLoweringBuildCfgPass.publishStraightLineExecutableGraph`
  要求 `sourceOwner` 是 `FunctionDeclaration` / `ConstructorDeclaration`，
  `LambdaExpression` 会直接 `IllegalStateException`。
- 同步给 `FrontendLoweringBuildCfgPass` 增加接受
  `sourceOwner instanceof LambdaExpression` 且 `loweringRoot instanceof Block`
  的分支。
- `FrontendLoweringBodyInsnPass` 的 Kind switch **必须**把 `LAMBDA_BODY`
  并入与 `EXECUTABLE_BODY` 相同的分支（跑 `FrontendBodyLoweringSession`）。
  该 switch 无 `default`：漏加会静默跳过，留下 shell-only 函数再被
  `CCodegen.generateFunctionPrepareBlock` 接上 `__prepare__`。
- 不得把 lambda 塞进 `PROPERTY_INIT`。
- **不要**对 lambda 调 `ensureExecutableSelfParameter`；self 只走
  §3.5 capture。
- `CGenHelper` / bind methods 已跳过 `isLambda()`，保持。

**验收细则**：

- happy path：一个含两层嵌套 lambda 的函数，class 上有两个 hidden
  `is_lambda` 函数，capture 列表与 plan 一致。
- happy path：无 capture 的 lambda 无 `<captures>` 条目，且
  `getCaptureCount()==0`。
- negative path：用户手写 `func _lambda_0():` → `sema.class_skeleton`，
  不覆盖合成函数。
- negative path：缺 `FrontendLambdaPlan` 的 lambda 进入该 pass → fail-fast，
  不静默跳过。

### 阶段 F — CFG + `construct_lambda` lowering

**目标**：外层 body 把 lambda 值 lower 成 `ConstructLambdaInsn`；
lambda body 走既有 CFG / body insn pass。

**实施内容**：

- 新增 CFG item（建议名 `LambdaLoadItem` / `LambdaConstructItem`）：
  持有合成名、capture 的 source value id 列表、result value id。
- `FrontendCfgGraphBuilder` 在表达式为 `LambdaExpression` 且存在 plan 时
  建该 item。普通 capture operand 从 enclosing
  `FrontendBindingKind.LOCAL_VAR` / `PARAMETER` / 外层 `CAPTURE`
  slot 读取；`self` capture 必须用 `SELF_SLOT`
  descriptor，禁止伪造 `IdentifierExpression + SELF`。
- `FrontendSequenceItemInsnLoweringProcessors` 发射
  `ConstructLambdaInsn(result, name, captures...)`。
- lambda **body** 的 CFG 以合成 `LirFunctionDef` 为 target，复用
  `buildExecutableBody`。
- body 内 `IdentifierExpression + CAPTURE`：按该 lambda 函数的变量名读，
  **仍不得**走 `DirectSlotAliasValueItem`。
- 外层 `CAPTURE` 出现在非 lambda body（不应发生）继续 fail-fast。

**验收细则**：

- happy path：`var cb := func(x: int): return x` 外层 LIR 含
  `construct_lambda "_lambda_0"`，无 capture operand；`_lambda_0`
  有参数 `x` 与 return。
- happy path：捕获外层 `seed` 时 insn 为
  `construct_lambda "_lambda_0" $seed`；`_lambda_0` 的
  `<capture name="seed">` 类型等于该外层绑定的**声明处类型**（§3.4），
  不是外层函数末尾 slot 类型。
- happy path：使用 `self.foo` 的 lambda 带 `self` capture operand。
- negative path：`FrontendCfgGraphBuilderTest` 对 **外层**
  `CAPTURE` alias 的 fail-fast **保留**。
- negative path：plan 与 CFG capture 数量不一致 → fail-fast。

### 阶段 G — C runtime 与 codegen

**目标**：`construct_lambda` 可编译、可调用、可释放。

**实施内容**：

- 新增 `gdcc_new_lambda_callable(...)`（或 per-lambda 生成的薄包装），
  复用 `godot_callable_custom_create2`：
  - `call_func` / `free_func` / `hash` / `equal`（引用相等即可，对齐 Godot）
  - `get_argument_count` = 源码参数个数
  - `object_id`：`capturesSelf` 时为 enclosing `self.instance_id`，否则 `0`
    （§3.5）。helper 必须接收该 ID，不得在 C 里对可能已死的 raw 调
    `godot_object_get_instance_id`。
  - `is_valid_func`：`object_id != 0` 时按 ObjectDB 存活返回；
    `object_id == 0` 时可与 standalone 一样只检查 userdata / 函数指针。
- `ConstructInsnGen` 注册 `CONSTRUCT_LAMBDA`：
  分配 capture 结构体、逐字段 copy、调用 helper。
- 从 `CCodegen.generateFunctionPrepareBlock` 排除 capture locals
  （`func.getCapture(name) != null`），避免默认构造后再 copy 造成
  destroyable 泄漏。
- 在 lambda 函数入口（`__prepare__` 之后或专用 prologue insn）把
  `_capture->name` 拷入对应 `$name`（destroyable 用既有 copy helper）。
- `free_func`：对每个 destroyable 字段 `destruct`，再 free userdata。
- 无 capture：userdata `NULL`，`free_func` no-op。
- 对象 capture 的生命周期遵循 `gdcc_ownership_lifecycle_spec.md` 的
  copy/destruct；不必在 MVP 复刻 Godot “已释放则变 null” 的调试打印，
  但不得 double-free。
- 同步 `doc/gdcc_runtime_lib.md`（新 helper）与 `doc/gdcc_low_ir.md`
  （`_capture` 尾参 + prologue-copy 约定；当前只写了 userdata）。

**验收细则**：

- happy path：targeted backend 测试（可手工 LIR）生成的 C 含 capture
  typedef、`construct_lambda` 调用、`free_func`。
- happy path：Zig 可用时 e2e：lambda 返回常量、`Callable.call` 得到预期值。
- happy path：捕获 `int` / `String` / `Array`；String/Array 在 Callable
  释放后无泄漏（ASan/现有 destroy 约定）。
- happy path：`capturesSelf` 的 lambda 生成的 C 里
  `GDExtensionCallableCustomInfo2.object_id` 来自 `self` 的 cached
  `instance_id`，不是字面 `0`。
- happy path（Zig + `GODOT_BIN`）：`sig.connect(func(): self.flag = true)`
  后 `free`/`queue_free` 该实例，再 `sig.emit()` 不得改到已释放对象，
  也不得崩溃；连接应按 Godot 随 `object_id` 失效。
- happy path：不捕获 `self` 的 lambda 仍 `object_id = 0`。
- negative path：未知 lambda 名 / capture 数与函数定义不一致 →
  codegen fail-fast，不生成半残 C。

### 阶段 H — 回归锚点补齐（仍可选择暂不解封 gate）

**目标**：focused tests 覆盖 happy / negative；test_suite 仍可暂避 lambda，
直到阶段 I。

**建议测试类**（新建或扩展）：

- `FrontendLambdaInventoryTest`
- `FrontendLambdaCapturePlannerTest`
- `FrontendLambdaSuiteResolverTest`
- `FrontendLambdaExpressionTypeTest`
- `FrontendLambdaLoweringTest` / 扩 `FrontendLoweringBodyInsnPassTest`
- `ConstructLambdaInsnGenTest`
- 扩 `FrontendCompileCheckAnalyzerTest`（阶段 I 翻转）
- 扩 `FrontendLoopControlFlowAnalyzerTest`（保持 boundary）

**验收细则**：

- 每条新恢复规则同时有 happy + negative（category、坏 subtree 跳过、
  同模块其他 subtree 继续）。
- `FrontendLoopControlFlowAnalyzerTest.analyzeResetsOuterLoopDepthAtLambdaBoundary`
  **保持**：lambda 内 `break` 仍非法。
- `FrontendLambdaCapturePlannerTest` / 对应 suite 测试必须锚定 §3.4
  类型表：`var x := 1` → capture `int`；`var x = 1` → capture
  `Variant`（不得因 initializer 推断）；显式标注 / 参数 → 声明类型；
  未标注参数 → `Variant`；外层 `CAPTURE` 传递同源类型；`self` →
  enclosing `GdObjectType`；for 迭代器 → `FOR_ITERATION_RESOLUTION`
  已提交结果。负例：lambda 之后对 source 的断言不改变已冻结
  capture 类型；`CallableScope` 的 CAPTURE 绑定与 `LambdaCaptureEntry.type`
  同源。

### 阶段 I — Compile gate 解封与文档吸收

**目标**：满足 `frontend_compile_check_analyzer_implementation.md` §7 五条件。

**实施内容**：

- `FrontendCompileCheckAnalyzer` 将 supported executable 内的
  `LambdaExpression` 纳入 compile surface，递归扫描其 body facts。
- 缺 `FrontendLambdaPlan` / 缺合成函数 / 缺 `construct_lambda` lowering
  合同 → `sema.compile_check` blocker 或 fail-fast，不得静默放行。
- 更新：
  - `frontend_rules.md` §MVP（删“lambda 仍 deferred”）
  - `frontend_signal_support.md` 拒绝列表（其 lambda / capture 句）与
    compile-gate 句；**新增** `construct_lambda` 的 `object_id` 按 §3.5
    （`capturesSelf` 绑 `self.instance_id`，否则 `0`）。该文档现有
    “`object_id` 填 0”写的是 `construct_standalone_callable`，**不得**
    改那一句。`construct_callable` 合同也不动
  - `frontend_lowering_plan.md` §7 将 lambda 移出 post-MVP
  - `frontend_variable_analyzer_implementation.md` /
    `frontend_visible_value_resolver_implementation.md` /
    `frontend_resolution_pipeline_implementation.md` /
    `frontend_top_binding_analyzer_implementation.md` /
    `scope_architecture_refactor_plan.md` 中对应 deferred 句
  - `doc/gdcc_runtime_lib.md`、`doc/gdcc_low_ir.md`（若阶段 G 已改 ABI 文本）
  - `doc/test_error/test_suite_engine_integration_known_limits.md` §1
  - `doc/benchmark.md` 若仍写 “avoid lambda”
- 本计划合同吸收为 `frontend_lambda_implementation.md` 事实源，
  删除阶段流水账。

**验收细则**：

- happy path：`analyzeForCompileBlocksDirectLambdaConnectArgument` **翻转**
  为 compile 成功、无 `unsupported_binding_subtree`、无多余 `compile_check`。
- happy path：`analyzeForCompileReleasesBuiltinAndStaticMethodReferences`
  中的 `lambda_cb` 不再贡献 unsupported binding。
- negative path：property-init / parameter-default 中的 lambda 仍不进
  compile surface。
- negative path：lambda body 内 `match` 仍使 compile 失败，且诊断 owner
  不是误挂的 `sema.compile_check` 重复包一层。
- Zig + `GODOT_BIN` 可用时：`sig.connect(func(): ...)` e2e 跑通。

---

## 5. 测试规则与建议命令

- 只跑相关测试类/方法，使用 `--no-daemon --info --console=plain`。
- 优先：

```text
pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 `
  -Tests FrontendLambdaInventoryTest,FrontendLambdaCapturePlannerTest,FrontendBodySemanticSupportPolicyTest
```

阶段 C 之后追加：

```text
FrontendVisibleValueResolverTest,FrontendInterfacePhaseTest,FrontendSuiteResolverTest,FrontendVariableAnalyzerTest
```

阶段 D–F 之后追加：

```text
FrontendTypeCheckAnalyzerTest,FrontendSemanticAnalyzerFrameworkTest,FrontendCfgGraphBuilderTest,FrontendLoweringBodyInsnPassTest
```

阶段 G–I 之后追加：

```text
FrontendCompileCheckAnalyzerTest,ConstructLambdaInsnGenTest,DomLirParserTest,LirPublicAbiValidatorTest
```

- e2e / test_suite 仅在阶段 I 且 Zig 可用时加正向锚点；negative 留在
  frontend focused tests。

---

## 6. 必须翻转 vs 必须保持的现有测试

### 6.1 阶段推进后应翻转

| 测试 | 当前断言 | 翻转后 |
|------|----------|--------|
| `FrontendVariableAnalyzerTest.analyzeWarnsForDeferredLambdaSubtrees*` | inventory error + 空 scope | bind param/local/capture |
| `FrontendSemanticAnalyzerFrameworkTest.analyzePublishesTopBindings...` 中 lambda 段 | 无 binding + unsupported_* | `CAPTURE`/`PARAMETER` + `GdCallableType` |
| `FrontendInterfacePhaseTest.publishesForInventoryWhileUnsupported...` 中 lambda | body 不在 suite entry | body 在 suite entry |
| `FrontendSuiteResolverTest.forBodyResolvesWhileUnsupported...` 中 lambda | 非 supported block | supported + owner events |
| `FrontendVisibleValueResolverTest.resolveSealsLambdaBodyAsDeferredUnsupported` | `LAMBDA_SUBTREE` | 可解析 |
| `FrontendVisibleValueResolverTest.resolveRejectsSyntheticLambdaBody...` | current-scope 封口 | 不再因 kind 封口 |
| `FrontendBodySemanticSupportPolicyTest` lambda 行 | `LAMBDA_SUBTREE` | **阶段 B** 即翻成 `EXECUTABLE_BODY` |
| `FrontendTypeCheckAnalyzerTest` lambda initializer | `UNSUPPORTED` | `RESOLVED(Callable)` 或类型不兼容 |
| `FrontendBodyOwnerProceduresExprTypeTest` inferred lambda local | `UNSUPPORTED` | `Callable` |
| `FrontendCompileCheckAnalyzerTest.analyzeForCompileBlocksDirectLambdaConnectArgument` | unsupported binding，无 compile_check | 阶段 I 后 compile-ready |
| `FrontendCompileCheckAnalyzerTest.analyzeForCompileReleasesBuiltinAndStaticMethodReferences` 的 lambda_cb | unsupported binding | 无该 error |

### 6.2 必须保持

| 测试 | 原因 |
|------|------|
| 全部 `FrontendScopeAnalyzerTest` 的 lambda 图测试 | 图形状不改 |
| `ScopeCaptureShapeTest` | 纯 scope API |
| `FrontendLoopControlFlowAnalyzerTest.analyzeResetsOuterLoopDepthAtLambdaBoundary` | 仍是 callable boundary |
| `FrontendCfgGraphBuilderTest.buildExecutableBodyFailsFastWhenReceiverBindingIsCaptureAliasRoot` | alias 仍不开放 |
| `LirPublicAbiValidatorTest` / `DomLirParserTest` compiler-only capture | ABI 护栏 |
| for / match / const 的 fail-closed 锚点 | 不得误开 |
| compile-surface skip：parameter default / match 内嵌套的 lambda | MVP 外 |

---

## 7. 长期不变量

1. lambda body 一旦转正，inventory 与 suite entry **无条件**发布；typed fact
   只用于外层/体内普通 slot 精化、`:=` 声明处稳定化，以及 compile readiness。
   capture 类型取外层绑定的声明处类型（含 `:=` 推断），声明之后的断言
   或精化不得改写。capture 类型填充永远不构成 body 进入条件。
2. `construct_callable` / `construct_standalone_callable` 永不承载 lambda。
3. `CAPTURE` 不是 alias root；capture 是构造时拷贝。
4. compiler-only 类型不得出现在 lambda 参数、返回、`<captures>`、
   `expressionTypes()`。
5. hidden lambda 函数不进 ClassDB；`is_lambda` 与 `is_hidden` 同时为真。
6. 缺 published `FrontendLambdaPlan` 时 lowering fail-fast，禁止现场重推导。
7. 诊断：一处根因一条 diagnostic；downstream 不得重复包。
8. `match` / parameter default / block-local `const` / `await` 不得借这次
   改动进入支持面。
9. 修改本合同时必须同步 `frontend_rules.md`、compile-check 文档、
   `gdcc_low_ir.md`（若改 insn）、`gdcc_runtime_lib.md`（若改 helper）。

---

## 8. 完成定义

全部满足才算本计划完成：

1. 阶段 A–I 的验收细则均有 targeted 测试锚定。
2. `analyzeForCompile` 对 MVP 支持面内的 lambda 不再因“是 lambda”而失败。
3. 至少一条 e2e（Zig 可用时）：构造 Callable、`.call()`、以及
   `Signal.connect(func(): ...)`。
4. `func.ftl` 多 capture 只生成一个 `_capture` 参数；prologue 初始化全部
   capture local。
5. DOM LIR 往返保留 `<captures>`。
6. 文档：`frontend_rules.md` MVP 句、known_limits §1、lowering plan §7、
   signal 拒绝列表已更新；本计划吸收为事实源或明确标注“已落地”。
7. `match` / default / const 回归测试仍 fail-closed。

---

## 9. 风险与未定点（不阻塞按上列 MVP 开工）

1. **`object_id` 来源**：已在 §3.5 冻结。实施时必须从 fat pointer 缓存
   的 `instance_id` 取值；对可能已死的 raw 调 `godot_object_get_instance_id`
   会崩（`object_value_fat_pointer_implementation.md`）。
2. **capture 类型**：已在 §3.4 冻结为外层绑定的**声明处类型**
   （`var x := 1` → `int`，`var x = 1` → `Variant`）。填充时点是
   该 lambda nested resolve 入口；读取路径是外层 typed environment
   上按 `sourceDeclaration` 匹配的已 flush 稳定化 update，并同步
   `resetCaptureType`。不得读物理 scope slot，不得回落
   `VAR_TYPE_POST` / 函数末尾 overlay。
   **首次 publish**：阶段 B 不写 `lambdaPlans()`；阶段 C 填完后
   第一次发布完整 plan；独立 `LAMBDA_RESOLUTION` stage + overlay
   槽 + patch；禁止 first-wins merge 上的 `withType` 回填。
3. **嵌套 suite export**：lambda 的 `FrontendCallableExportBatch` 不得写进
   外层函数的 statement overlay。必须独立 apply。
4. **表达式中的 lambda 与 Interface 遍历顺序**：必须测
   `return func(): ...`、`foo(func(): ...)`、容器字面量里的 lambda
   （容器字面量已 compile-ready，lambda 元素要走 nested resolve）。
   Interface 默认 `CONTINUE` 已能走进这些表达式，无需新 walker；只需
   `supportedBodyDepth` 守卫。
5. **capture 与 `__prepare__`**：阶段 G 必须从
   `CCodegen.generateFunctionPrepareBlock` 排除 capture local，再 copy。
6. **参数默认值**：lambda 的 default 与普通函数同一边界，不在本计划打开。
7. Godot 把 capture 作为**前缀实参**喂给 `GDScriptFunction::call`；GDCC 把
   capture 放进 userdata 结构体。这是 ABI 差异，不是语义差异；测试应对齐
   用户可见 arity 与 capture 值，而不是对齐 Godot 调用约定。

---

## 10. 与 Godot 的有意识差异

| 点 | Godot | GDCC MVP |
|----|-------|----------|
| self-lambda 身份 | 独立 `GDScriptLambdaSelfCallable`，`get_object()=instance` | 仍用 `construct_lambda` + `self` capture；`object_id = self.instance_id` |
| 非 self-lambda 身份 | `GDScriptLambdaCallable.get_object()=script` | `object_id = 0`（无 GDScript 资源可绑） |
| capture 传递 | 调用时插到实参前的 `Variant*` | userdata 结构体 + 函数尾部 `_capture` |
| lambda 名 | 可有 debug 名 | `_lambda_<k>` hidden |
| 相等 | 引用相等 | 引用相等 |
| 已释放的 Object capture | debug 下替换 null | 遵循 GDCC ownership；不强制复刻打印 |

`capturesSelf` 的 `Signal.connect(func(): self.foo())` **必须**按 Godot
随实例释放自动断开。不捕获 `self` 的 lambda 不得假装绑到 instance 或 script。

---

## 11. 参考事实源

### 11.1 本仓库

- `src/main/java/gd/script/gdcc/frontend/sema/FrontendBodySemanticSupportPolicy.java`
- `src/main/java/gd/script/gdcc/frontend/scope/CallableScope.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendVariableAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendInterfacePhase.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendSuiteResolver.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendBodyOwnerProcedures.java`
- `src/main/java/gd/script/gdcc/frontend/sema/resolver/FrontendVisibleValueResolver.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/FrontendLoweringFunctionPreparationPass.java`
- `src/main/java/gd/script/gdcc/lir/insn/ConstructLambdaInsn.java`
- `src/main/java/gd/script/gdcc/lir/LirFunctionDef.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/ConstructInsnGen.java`
- `src/main/c/codegen/include_451/gdcc/gdcc_callable.h`
- `src/main/c/codegen/template_451/func.ftl`
- `src/main/c/codegen/template_451/entry.h.ftl`
- `src/main/c/codegen/template_451/entry.c.ftl`

### 11.2 Godot

- `modules/gdscript/gdscript_parser.h`（`LambdaNode`）
- `modules/gdscript/gdscript_analyzer.cpp`（capture 沿 parent lambda 链复制）
- `modules/gdscript/gdscript_compiler.cpp`（`write_lambda`）
- `modules/gdscript/gdscript_byte_codegen.cpp`（`OPCODE_CREATE_*_LAMBDA`）
- `modules/gdscript/gdscript_lambda_callable.h/.cpp`
- `modules/gdscript/gdscript_vm.cpp`（`OPCODE_CREATE_LAMBDA` / `OPCODE_CREATE_SELF_LAMBDA`）
