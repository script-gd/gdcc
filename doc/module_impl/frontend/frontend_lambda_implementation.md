# Frontend Lambda 实现说明

> 本文档作为 GDScript `func(...)` / `LambdaExpression` 在 frontend shared semantic、type-check、
> compile gate、CFG、body lowering 与 C backend 中的**长期事实源**。
> 本文档吸收并取代原 `frontend_lambda_plan.md` 中的架构与合同描述，
> **不再保留**分阶段实施步骤、验收流水账或进度状态表。

## 文档状态

- 状态：事实源维护中（scope 双层图、lexical inventory、nested suite resolution、
  `FrontendLambdaPlan` 首次发布、`RESOLVED(GdCallableType)`、hidden `_lambda_<k>` shell、
  `LambdaConstructItem` / `construct_lambda`、C custom Callable 与 compile gate 按 published plan
  放行已落地；property initializer / parameter default / skipped subtree 中的未记录 lambda
  以及 lambda 自己的 parameter default、body 内 `match` / block-local `const` / `await` 仍 fail-closed）
- 更新时间：2026-08-18
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
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_top_binding_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_local_type_stabilization_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_func_pre_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_complex_writable_target_implementation.md`
  - `doc/module_impl/frontend/frontend_signal_support.md`
  - `doc/module_impl/frontend/frontend_for_range_loop_implementation.md`
  - `doc/gdcc_low_ir.md`（`construct_lambda`、`is_lambda`、`<captures>`）
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/module_impl/frontend/frontend_gdcompiler_type_implementation.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`
- 明确非目标：
  - 不在此转正 `match`、parameter default、block-local `const`、`await`
  - 不实现 Godot `OPCODE_CREATE_SELF_LAMBDA` / `GDScriptLambdaSelfCallable` 的独立 opcode；
    `self` 走既有 `construct_lambda` capture，`capturesSelf` 时 custom Callable 的 `object_id`
    绑 enclosing instance（见 §3.5）
  - 不把 `CAPTURE` 纳入 direct-slot alias publication（见 §3.6）
  - 不复用 `construct_callable` / `construct_standalone_callable` 承载 lambda
    （`frontend_signal_support.md` 已禁止）
  - 不把 `GdCompilerType` 写入 lambda `<captures>` 或 lambda 参数/返回 ABI
  - 不在 property initializer / parameter default / skipped subtree 中开放 lambda
  - 不把 lambda 注册进 ClassDB / `_class_bind_methods`
  - 不实现 `Callable.bind` / `unbind` 新 lowering
  - 不更改 Godot 对普通局部/参数的 copy-on-capture 语义

---

## 1. 当前职责与集成位置

### 1.1 Pipeline 位置

`LambdaExpression` 是新的 nested callable，不是 for 那种 header-only statement。职责拆分如下：

| 阶段 | 组件 | 职责 |
|------|------|------|
| Scope | `FrontendScopeAnalyzer.handleLambdaExpression` | `CallableScope(LAMBDA_EXPRESSION)` + `BlockScope(LAMBDA_BODY)`；图形状已冻结 |
| Inventory | `FrontendVariableAnalyzer` | bind param / ordinary local；按名 `defineCapture`（`Variant` 占位）。**不**写 `lambdaPlans()` |
| Interface | `FrontendInterfacePhase` | `supportedBodyDepth > 0` 时 `recordCallable`；body 进 suite entry |
| Suite | `FrontendSuiteResolver.resolveNestedLambdaOwner` | 独立 `FrontendCallableExportBatch`；填声明处 capture 类型并首次发布 `FrontendLambdaPlan` |
| Expr typing | `FrontendExpressionSemanticSupport` | 已记录节点发布 `RESOLVED(GdCallableType)` |
| Type-check | `FrontendTypeCheckAnalyzer.scanNestedLambdaBodies` | 以 plan 为闸门 walk body；return slot 消费 `plan.returnType()` |
| Loop | `FrontendLoopControlFlowAnalyzer` | callable boundary，重置 loop depth |
| Skeleton / pre-pass | `FrontendLoweringFunctionPreparationPass` | 发现式 walk 已记录 lambda，合成 hidden `_lambda_<k>` shell + `LAMBDA_BODY` 上下文 |
| CFG | `FrontendCfgGraphBuilder` | 外层构造点建 `LambdaConstructItem`；`CAPTURE` 仍非 alias root |
| Body lowering | `FrontendLambdaConstructInsnLoweringProcessor` | 发射 `ConstructLambdaInsn`；body 走共享 `FrontendBodyLoweringSession` |
| Backend | `ConstructInsnGen` + `gdcc_callable.h` + 模板 | custom Callable + capture copy / free |
| Compile gate | `FrontendCompileCheckAnalyzer` | 已记录 lambda 纳入 compile surface 并递归扫描 body；未记录 fail-closed |

强制顺序（任一环不得靠 `PENDING` / typed readiness 决定是否进入 lambda body）：

```
scope graph
  → 完整 lexical inventory（param / local / capture 名）
  → declaration index / typed baseline / suite entry
  → SuiteResolver body entry（无条件）
  → typed resolution（capture 类型取外层绑定的声明处类型，含 `:=` 推断）
  → type-check
  → 合成 LirFunctionDef + CFG + construct_lambda
  → C backend
  → compile gate 放行
```

compile gate 的 readiness 是 lowering 边界，不是 semantic body entry gate。

### 1.2 三层支持面解耦

1. **Shared body semantic**：inventory / declaration index / suite entry **不依赖** capture 最终类型或 lowering readiness。
2. **Type-check**：已记录 lambda 作为独立 callable island 遍历 body；未记录保持 fail-closed。
3. **Compile / CFG / lowering**：只放行已发布 `FrontendLambdaPlan` + 已发布 body 的节点。

### 1.3 当前不负责

- 在 property initializer / parameter default / class-level 表达式中 `recordCallable`
- 把 `CAPTURE` 当 live-slot alias
- 为 lambda 注入第二份 `self` 参数
- 在 lowering 侧从 scope 现场重推导 capture
- 把 lambda 注册进 ClassDB 或 source function namespace

### 1.4 源码形态

GDScript lambda 是表达式级匿名函数：

```gdscript
var cb := func(offset: int) -> int:
    return seed + offset
sig.connect(func():
    pinged.emit())
```

Parser AST 为 `dev.superice.gdparser.frontend.ast.LambdaExpression`，提供
`parameters()` / `returnType()` / `body()`。它不是 `FunctionDeclaration`，
没有源码级函数名，也不进入 class member namespace。

---

## 2. 当前支持面

正式支持：

- 出现在 function / constructor executable body（含 `if` / `while` / `for` body、普通 block）
  中的 `LambdaExpression`。Interface / Suite 只在 `supportedBodyDepth > 0` 时把 lambda
  记为 callable owner
- 嵌套 lambda（内层 capture 必须同时写入中间层，见 §3.4）
- 显式参数类型、显式/缺省返回类型（缺省按既有 `resolveTypeOrVariant → Variant`）
- 无 capture、捕获外层 `ScopeValueKind.PARAMETER` / `LOCAL`（含 ordinary `var` 与 for 迭代器）/
  外层 `CAPTURE`
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

## 3. 冻结合同

### 3.1 结构策略

`FrontendBodySemanticSupportPolicy` 当前为：

- `forBlockScopeKind(LAMBDA_BODY)` → `EXECUTABLE_BODY`
- `forCallableScopeKind(LAMBDA_EXPRESSION)` → `EXECUTABLE_BODY`

`FrontendVisibleValueDomain.LAMBDA_SUBTREE` 枚举值保留，但生产路径不再映射到它。
`MATCH_SUBTREE` / `PARAMETER_DEFAULT` / `BLOCK_LOCAL_CONST_SUBTREE` 不得被这次合同打开。

`FrontendVariableAnalyzer.bindLocal` 经
`FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(kind)`
读取 `publishesLexicalInventory()`；`LAMBDA_BODY` 必须保持 `EXECUTABLE_BODY`，
否则 body local 会误报成 `sema.variable_binding`。

### 3.2 Lambda 是 nested callable owner

1. `FrontendInterfacePhase.handleLambdaExpression` 仅当 `supportedBodyDepth > 0` 时调用
   `recordCallable(lambda, parameters, body)`。depth=0（property initializer / class-level
   表达式）必须 `SKIP_CHILDREN` 且不进 `callableOwners`。
2. `FrontendSuiteResolver.callableBody/callableParameters/restrictionForCallable/isStaticCallable`
   识别 `LambdaExpression`。
3. **解析时机**：
   - Interface 对 supported-body 内的 lambda 先无条件发布 inventory / baseline / suite entry。
   - 外层 **非 silent** 的 expr-type owner 路径在发布 `GdCallableType` **之前**，若该
     lambda 已 `recordCallable` 且尚未解析，则调用
     `FrontendSuiteResolver.resolveCallableOwner(lambda)`（独立
     `FrontendCallableExportBatch`）。这是 nested resolve 的**唯一**生产触发点。
   - 局部稳定化的 **silent resolver** 必须把 `LambdaExpression` initializer 列入
     fail-closed（`var cb := func(): ...` 的 slot 保持 inventory `Variant`，不写
     side table、不发诊断、不触发 nested resolve）。`:=` 推断为 `GdCallableType`
     若要发生，只能由非 silent 路径在 nested resolve 完成、表达式类型已发布后，
     按 `frontend_local_type_stabilization` 既有单调写回决定。
   - 未 `recordCallable` 的 lambda（property-init / default / skipped）**不得**触发
     nested resolve；保持 deferred / unsupported。
   - 用 AST-identity side table 标记已解析，避免顶层循环二次解析。
4. lambda 的 static/instance restriction **继承 enclosing callable**：
   - enclosing `FunctionDeclaration.isStatic()` → `ResolveRestriction.staticContext()`
   - 否则 `instanceContext()`
   - 嵌套 lambda 继续继承最外层 enclosing 非 lambda callable。

lambda 的 `FrontendCallableExportBatch` 不得写进外层函数的 statement overlay，必须独立 apply。

### 3.3 表达式类型

`resolveLambdaExpressionType` 成功时发布：

- `FrontendExpressionType.resolved(new GdCallableType())`
- 不填 `arguments` / 特化 `returnType`（与 method-as-value / `construct_callable` 的
  unparameterized `Callable` 对齐）
- 不稳定依赖按既有 `propagated(...)` 传递，不得伪装成 `UNSUPPORTED`

仅 **已 `recordCallable`** 的 lambda 节点不再发
`sema.unsupported_expression_route` / `sema.unsupported_binding_subtree` /
`sema.unsupported_chain_route`。未记录的 lambda 必须继续由
`FrontendBodyOwnerProcedures` 按位置发 unsupported，避免对无 suite entry 的 body
触发 `FrontendBodyStructuralCompleteness` fail-fast。

### 3.4 Capture 推导

`FrontendLambdaCapturePlanner` 在 variable inventory 阶段按**名字**推导。capture **类型**
取外层绑定的**声明处类型**（显式标注，或 `:=` 在该声明上的推断/稳定化结果），
不是 inventory baseline，也不是声明之后的断言或精化。

**可捕获**（scope lookup 命中的是 `ScopeValueKind`，不是 `FrontendBindingKind`）：

- 最近 enclosing supported callable 中的 `PARAMETER`
- 最近 enclosing supported executable block 中的 `LOCAL`（ordinary `var` 与 for 迭代器）
- 外层 lambda 已发布的 `CAPTURE`（嵌套传递）
- enclosing instance callable 的 `self`（§3.5）

**不可捕获**（按普通词法继续解析，不进 `<captures>`）：

- 当前 lambda 自己的 parameter / local
- class property / signal / method / 内层 class type-meta
- global / singleton / utility / enum
- 尚未转正域里的名字（match pattern、block-local const、parameter default）

**算法（对齐 Godot innermost-first，但不依赖 Godot AST）**：

1. 先 bind 当前 lambda 的 parameters，再 bind body ordinary locals。
2. 扫描 lambda body 中所有 `IdentifierExpression`（含嵌套表达式；跳过声明站点本身）。
3. **从该 identifier 自己的 scope 向上**做 value lookup，**禁止**一律从 lambda
   `CallableScope.parent` 查找。
4. 仅当第一次命中落在**当前 lambda callable 边界之外**，且 `ScopeValueKind` 为
   `PARAMETER` / `LOCAL` / `CAPTURE` 时，才记为当前 lambda 的 capture。
5. 若该名沿 parent lambda 链仍可见，中间层也 `defineCapture`（嵌套传递）。
6. 同一名字只记一次；顺序按**首次出现的源码顺序**冻结，供 LIR operand 顺序使用。
7. capture 与 parameter 冲突时 `ensureCallableValueSlotAvailable` 仍 fail-fast；
   生产路径必须在 define 前做 diagnostic + skip。
8. planner 按 **post-order** 运行：先完成嵌套 lambda 的 param/local bind 与其自身
   capture plan，再扫描外层。
9. 中间层传递：当且仅当 source binding 位于中间层 lambda 边界之外，且中间层无同名
   param/local 遮蔽时，中间层 `defineCapture` 同名条目（所有传递层共享同一个
   `sourceDeclaration`，声明处类型同源）。中间层遮蔽时传递在该层终止。

反例（内层 local 遮蔽外层，不得捕获）：

```gdscript
func f():
    var x = 1
    var cb := func():
        var x = 2
        return x    # 指 lambda local，不得捕获外层 x
```

**`lambdaPlans()` 首次发布**：

- inventory **不得**把 `Variant` 占位写入 `FrontendAnalysisData.lambdaPlans()`。
  inspection / inventory 测试读 scope 上的 `CAPTURE` 绑定，不读 side table。
- nested resolve 入口填完声明处类型后，**第一次** publish 完整 `FrontendLambdaPlan`
  （`LambdaCaptureEntry.type` 已是声明处类型，`capturesSelf` 已按 §3.5 与 leading
  `self` 对齐）。
- `applyPatch` / `mergeSideTable` 保持 first-wins。禁止对同一 `LambdaExpression`
  先发占位再 `withType` 走 patch 覆盖。`LambdaCaptureEntry.withType` 只用于构造
  最终 plan 的本地组装，不是 side-table 回写 API。
- 独立 semantic stage + overlay 槽 + 专用 owner patch：`LAMBDA_RESOLUTION` /
  `FrontendLambdaResolutionPatch`。**不得**把 plan 折进 `FrontendExprTypePatch`。
  该 stage 必须能在外层 EXPR_TYPE 发布 `GdCallableType` **之前**随 nested resolve export。
- lowering 只消费已发布的完整 plan；缺 plan 仍 fail-fast，禁止现场从 scope 重推导。

填充必须同时更新三处：

- `LambdaCaptureEntry.type`（随第一次 publish 写入 `lambdaPlans()`）
- LIR `<captures>`（lowering 时消费**已发布** plan，不再回读 scope）
- 该 lambda `CallableScope` 上的 `CAPTURE` 绑定：`resetCaptureType`（镜像
  `BlockScope.resetLocalType` 的单调 `Variant → exact` 守卫与 declaration 身份校验）

**读取路径**（禁止读物理 scope slot，禁止回落 `slotTypes()` / `VAR_TYPE_POST`）：

1. 填充时点发生在外层语句 mid-suite。此刻物理 slot 仍是 inventory `Variant`。
2. 必须走外层当前 typed environment 上的 **declaration-anchored** 查询：按
   `sourceDeclaration` + owning `BlockScope` 对象身份匹配已 flush 的
   `LOCAL_TYPE_STABILIZATION` / `FOR_ITERATION_RESOLUTION` update。
3. 无 matching update 时取声明类型或 inventory baseline，不得回落到
   `slotType(astNode)`。
4. 只有上述两个 stage 能写 slot update；日后若新增会写 slot 的断言 stage，
   会先撞该校验——这就是“声明之后的断言不改 capture”的执行点。

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

capture 合法性以外层声明在 **lambda 语句处按 declaration-order 可见**为前提。
`var cb := func(): return x` 后接 `var x := 1` 时，planner 可能仍登记名字，
但该条目不得进入 lowering；use-site 走既有 `DECLARATION_AFTER_USE_SITE`。

明确禁止：

- 用声明**之后**的赋值、类型断言、后续 overlay、`VAR_TYPE_POST` 或函数末尾
  slot 类型回写 `CAPTURE` / `FrontendLambdaPlan` / `<captures>`
- 对 `var x = 1` 因看到 initializer `1` 而把 capture 写成 `int`
- 只更新 `LambdaCaptureEntry.type` 却让 `CallableScope` 的 CAPTURE 停在 `Variant` 占位
- 引入 compiler-only 类型。`LirPublicAbiValidator` 与
  `FrontendPublishedFactTypeGuard.checkLambdaPlan` 继续拒绝

```gdscript
var a := 1          # 声明处稳定为 int → capture int
var b = 1           # 声明处 Variant → capture Variant
var cb := func():
    return a + b    # a:int 副本，b:Variant 副本
# 此后即使断言 b 为 int，或再给 a 做其它精化，都不改 capture ABI
```

**Godot 语义**：capture 是**构造时拷贝**。lambda 内对 capture 名赋值只改 lambda
自己的副本，不写回外层 local。对象/容器元素的可变性来自共享对象身份，不是 live-slot alias。

### 3.5 `self`：用 capture，不新增 opcode

GDCC LIR 只有 `construct_lambda` + capture 列表，没有独立 self-lambda opcode。

- 若 lambda body 出现显式 `SelfExpression`，或 instance restriction 下的 implicit
  instance member / instance method 调用需要 receiver，则把 enclosing executable 的
  `self` 收为名为 `self` 的 capture。
- `capturesSelf == true` **当且仅当** capture 列表的**第一项**名为 `self`。
  不得把 `self` 插在中间，也不得在没有 leading `self` 时把 flag 设为 true。
  `FrontendLambdaCapturePlan` 构造器强制该不变量。
- 该 capture 的类型是 enclosing class 的 `GdObjectType`。
- 合成 lambda `LirFunctionDef` **不**再注入第二份 `ensureExecutableSelfParameter`；
  `self` 只出现在 `<captures>` / `_capture->self`。
- lambda `LirFunctionDef` 一律 `setStatic(true)`（§3.7），避免
  `declareSelfSlotIfNeeded` 造 stray `self`。`Kind.LAMBDA_BODY` 的 `sourceOwner`
  是 `LambdaExpression`，`loweringRoot` 是其 body `Block`。
- CFG / body lowering 的 self capture **operand** 不得伪造
  `IdentifierExpression + SELF`。必须用 `SelfSlotOperand.SELF_SLOT` 直接读
  enclosing executable 的 `self` 槽。
- 静态 enclosing callable 中使用 `self`：沿用既有 restriction diagnostic，不合成非法 capture。

**`object_id`**：

- `capturesSelf == true`：`GDExtensionCallableCustomInfo2.object_id` 必须填
  enclosing `self` 在 **`construct_lambda` 求值点** 的 `GDObjectInstanceID`
  （fat pointer 已缓存的 `instance_id`）。实例 `free` / `queue_free` 后连接自动失效。
  必须从缓存取值；对可能已死的 raw 调 `godot_object_get_instance_id` 会崩
  （见 `object_value_fat_pointer_implementation.md`）。
- `capturesSelf == false`：`object_id = 0`。不得把 enclosing instance 或 class
  singleton 偷偷填进去。
- **不得**改 `construct_standalone_callable` / `gdcc_new_standalone_callable` 的
  `object_id = 0`，也不得改 `construct_callable` 的 receiver 合同。
- `is_valid_func`：`object_id != 0` 时必须经 ObjectDB
  （`godot_object_get_instance_from_id`）判定；不得只靠 `call_func` 里解引用
  悬空 `self` 来“发现”失效。
- `self` capture 字段仍按 ownership 合同 copy/release；`object_id` 只负责
  Godot 侧身份 / disconnect。

### 3.6 `CAPTURE` 与 writable / alias

- `CallableScope.defineCapture` 的 `writable=true`：允许 lambda **内部**对 capture 名赋值（写副本）。
- `FrontendCfgGraphBuilder.requireDirectSlotAliasRoot` 对 `CAPTURE` 继续 fail-fast。
- lambda **body** 内读 capture 名，按该函数的 local / capture 变量降低。
- 外层函数里，被 capture 的 local 仍按普通 `LOCAL_VAR` 读写；构造 lambda 时读一次快照。

### 3.7 合成函数身份

每个通过 compile surface 的 `LambdaExpression` 对应恰好一个 hidden `LirFunctionDef`：

- 名字：`_lambda_<k>`，`k` 在 owning `LirClassDef` 内从 0 递增，与 AST 遍历 / 源码出现顺序一致。
- `setLambda(true)`、`setHidden(true)`、`setStatic(true)`。`setLambda(true)` 必须先于
  `addCapture`。**禁止**再写 `setStatic(false)`。
- 参数表 = lambda 源码参数（含 inventory 已解析的声明类型）；**不含** `self` 参数。
- `<captures>` = `FrontendLambdaCapturePlan` 冻结列表。
- 返回类型在 nested resolve 入口解析**一次**并随 `FrontendLambdaPlan.returnType` 首次发布；
  type-check 的 return slot 与 lowering 的 shell 返回类型都消费该已发布值，禁止两处各自重复解析。
- 不进入 `ClassScope.defineFunction` / 不进 ClassDB bind。
- `_lambda_` 前缀视为 compiler-owned（`FrontendSyntheticPropertyHelperSupport.RESERVED_PREFIXES`）；
  用户声明同名前缀时 skeleton 发 `sema.class_skeleton` 并跳过该 member。

Side table：`FrontendAnalysisData.lambdaPlans()`，AST-identity 键。

`FrontendLambdaPlan` 至少包含：

- `LambdaExpression` AST 身份
- 合成名 `_lambda_<k>`
- 有序列表 `captures: List<LambdaCaptureEntry(name, type, sourceKind, sourceDeclaration)>`
- `capturesSelf: boolean`
- `returnType`（声明返回类型，nested resolve 入口一次解析并冻结）
- enclosing callable AST
- owning class canonical name

`samePlan` / `sameEntry` 比较 payload（`enclosingCallable` 按身份）；first-wins merge；
同一节点不同 payload 必须 fail，不得覆盖。

### 3.8 LIR / C ABI

```
$result = construct_lambda "<lambda_function_name>" $capture1 $capture2 ...
```

- 无 capture：userdata = `NULL`，`free_func` 仍可 no-op。
- 有 capture：拷入 heap 上的 `${Class}_Capture_${func}`，`callable_userdata` 指向该块；
  `free_func` 按字段析构后 `godot_mem_free`。
- `object_id` 按 §3.5：`capturesSelf` → enclosing `self.instance_id`，否则 `0`。
- helper：`gdcc_new_lambda_callable(userdata, object_id, call, is_valid, free, get_argument_count)`
  → `godot_callable_custom_create2`，token 与 standalone 共享。hash / equal / less /
  to_string 留空，走 Godot 默认（`call_func` + userdata 指针身份）。
- `call_func` 把 userdata 解成 `_capture`，再调 `${Class}_${lambdaName}($arg..., _capture)`。
  进入 `call_func` 前若 `object_id != 0` 且对象已死，应被 Godot / `is_valid_func` 挡掉。
- 用户可见 arity = lambda 源码参数个数（**不含** capture）。
- `captureCount > 0` 时 `func.ftl` 只发射一次 `_capture` 尾参。
- lambda 函数 prologue：把 `_capture->name` 拷入该 capture 登记的 local `$name`。
- `CCodegen.generateFunctionPrepareBlock` 必须排除 capture local
  （`func.getCapture(name) != null`），再由 prologue 从 `_capture->name` 拷入；
  否则 destroyable 类型会泄漏默认构造值。
- `DomLirSerializer` 必须写出真实 `<capture name type>`；`DomLirParser` 对称回读。
- `ConstructInsnGen.emitConstructLambda` 必须校验：目标函数存在且 `is_lambda`、
  capture 数一致、operand 均为 `VariableOperand`、名序一致、类型 `checkAssignable`。
  未知名 / 数量或名序不一致 / 无 self 槽却捕获 self → `InvalidInsnException`。
- 对象 capture 的字段写入是 first-write FAT_PTR BORROWED；生命周期遵循
  `gdcc_ownership_lifecycle_spec.md`。不必复刻 Godot “已释放则变 null” 的调试打印，
  但不得 double-free。

Godot 把 capture 作为**前缀实参**喂给 `GDScriptFunction::call`；GDCC 把 capture 放进
userdata 结构体。这是 ABI 差异，不是语义差异；测试应对齐用户可见 arity 与 capture 值，
而不是对齐 Godot 调用约定。

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
一处根因一条 diagnostic；downstream 不得重复包。

---

## 4. Compile gate 政策

`FrontendCompileCheckAnalyzer` 按 published plan 分流：

- **已记录 lambda**（`lambdaPlans()` 含该节点且 body 已发布）：放上 compile surface 并递归扫描
  body facts（与普通 executable block 相同）。
- **未记录 lambda**：保持形态级 `sema.compile_check` blocker，不静默放行；若上游已在同一
  exact range 发布 unsupported 诊断，则按统一去重合同省略补发。
- lambda body 内的 `match` 仍走上游 `sema.unsupported_binding_subtree` owner；compile gate
  不得再包一层 `sema.compile_check`。

`FrontendAnalysisInspectionTool` 不再把 `LambdaExpression` 当作 deferred 祖先。

---

## 5. 与 Godot 的有意识差异

| 点 | Godot | GDCC |
|----|-------|------|
| self-lambda 身份 | 独立 `GDScriptLambdaSelfCallable`，`get_object()=instance` | 仍用 `construct_lambda` + `self` capture；`object_id = self.instance_id` |
| 非 self-lambda 身份 | `GDScriptLambdaCallable.get_object()=script` | `object_id = 0`（无 GDScript 资源可绑） |
| capture 传递 | 调用时插到实参前的 `Variant*` | userdata 结构体 + 函数尾部 `_capture` |
| lambda 名 | 可有 debug 名 | `_lambda_<k>` hidden |
| 相等 | 引用相等 | 引用相等 |
| 已释放的 Object capture | debug 下替换 null | 遵循 GDCC ownership；不强制复刻打印 |

`capturesSelf` 的 `Signal.connect(func(): self.foo())` **必须**按 Godot 随实例释放自动断开。
不捕获 `self` 的 lambda 不得假装绑到 instance 或 script。

---

## 6. 长期不变量

1. lambda body 一旦转正，inventory 与 suite entry **无条件**发布；typed fact 只用于
   外层/体内普通 slot 精化、`:=` 声明处稳定化，以及 compile readiness。capture 类型取
   外层绑定的声明处类型（含 `:=` 推断），声明之后的断言或精化不得改写。capture 类型填充
   永远不构成 body 进入条件。
2. `construct_callable` / `construct_standalone_callable` 永不承载 lambda。
3. `CAPTURE` 不是 alias root；capture 是构造时拷贝。
4. compiler-only 类型不得出现在 lambda 参数、返回、`<captures>`、`expressionTypes()`。
5. hidden lambda 函数不进 ClassDB；`is_lambda` 与 `is_hidden` 同时为真。
6. 缺 published `FrontendLambdaPlan` 时 lowering fail-fast，禁止现场重推导。
7. 诊断：一处根因一条 diagnostic；downstream 不得重复包。
8. `match` / parameter default / block-local `const` / `await` 不得借这次改动进入支持面。
9. 修改本合同时必须同步 `frontend_rules.md`、compile-check 文档、
   `gdcc_low_ir.md`（若改 insn）、`gdcc_runtime_lib.md`（若改 helper）。

---

## 7. 测试锚点

主要类：

- `FrontendLambdaInventoryTest` — supported inventory happy + property-initializer skip
- `FrontendLambdaPlanSideTableTest` — 稳定 side table、compiler-only capture 拒绝
- `FrontendLambdaCapturePlannerTest` — 纯函数推导、遮蔽、嵌套传递、`capturesSelf` 不变量
- `FrontendLambdaSuiteResolutionTest` — nested resolve 首次发布、声明处类型、self / 嵌套 / for 迭代器、diverging republish
- `FrontendLambdaExpressionTypeTest` — 已记录 `RESOLVED(Callable)` + 未记录不污染 sibling
- `FrontendLambdaLoweringTest` — hidden shell 合成、`LAMBDA_BODY` CFG/body、外层 `construct_lambda`、缺 plan / 名冲突 / capture 数漂移 fail-fast
- `ConstructLambdaInsnGenTest` — opcode 注册、capture 块、`object_id`、prepare/prologue、名序校验
- `ConstructLambdaInsnGenEngineTest` — Zig + 可选 `GODOT_BIN`：常量 / String capture / self `object_id` / free 后 invalid
- `FrontendCompileCheckAnalyzerTest` — 已记录放行、未记录 fail-closed、body 内 `match` 不被 `compile_check` 重复包
- `FrontendClassSkeletonTest` — `_lambda_` reserved prefix
- `FrontendLoopControlFlowAnalyzerTest.analyzeResetsOuterLoopDepthAtLambdaBoundary` — 仍是 callable boundary
- `FrontendCfgGraphBuilderTest.buildExecutableBodyFailsFastWhenReceiverBindingIsCaptureAliasRoot` — alias 仍不开放
- `LirPublicAbiValidatorTest` / `DomLirParserTest` — compiler-only capture / `<captures>` 往返
- e2e：`src/test/test_suite/unit_test/script/member/signal_connect_lambda.gd`

必须保持 fail-closed 的现有锚点：全部 `FrontendScopeAnalyzerTest` 的 lambda 图形状、
`ScopeCaptureShapeTest`、for / match / const 的 fail-closed 锚点、parameter default /
match 内嵌套 lambda 的 compile-surface skip。

---

## 8. 当前局限与后续

- property initializer / parameter default / class-level 表达式中的 lambda 仍未 `recordCallable`。
- lambda 自己的 parameter default、body 内 `match` / block-local `const` / `await` 仍 deferred。
- `CAPTURE` 不进入 direct-slot alias publication。
- 不实现 `Callable.bind` / `unbind`。
- silent 局部稳定化不把 `var cb := func(): ...` 的 slot 精化为 `Callable`；若未来要打开，
  只能走 nested resolve 完成后的非 silent 单调写回。
- 表达式中的 lambda（`return func(): ...`、`foo(func(): ...)`、容器字面量元素）已由
  Interface 默认 `CONTINUE` + `supportedBodyDepth` 守卫走进 nested resolve；新增宿主表达式
  时必须继续测这条路径。

---

## 9. 维护结论

1. **Plan 与 scope 分离**不可回退：inventory 只写 scope 名与 `Variant` 占位；完整
   `FrontendLambdaPlan` 只由 `LAMBDA_RESOLUTION` 在 nested resolve 入口首次发布。
2. **声明处类型**是 capture ABI 真源；禁止读物理 slot 或 `VAR_TYPE_POST`。
3. **`self` 只作为 leading capture**；禁止独立 opcode、禁止注入 `self` 参数、禁止
   伪造 `IdentifierExpression + SELF`。
4. 缺 published plan / shell 漂移必须 fail-fast，禁止现场重推导或静默跳过。
5. 修改本合同时，同步 `frontend_rules.md`、`diagnostic_manager.md`、
   `frontend_compile_check_analyzer_implementation.md`、lowering 三份 pre-pass/CFG 文档、
   `frontend_signal_support.md` §8.4、`gdcc_low_ir.md` 与 `gdcc_runtime_lib.md` 中对应条目。

---

## 10. 参考实现位置

### 10.1 本仓库

- `src/main/java/gd/script/gdcc/frontend/sema/FrontendLambdaPlan.java`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendLambdaCapturePlan.java`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendLambdaCapturePlanner.java`
- `src/main/java/gd/script/gdcc/frontend/sema/LambdaCaptureEntry.java`
- `src/main/java/gd/script/gdcc/frontend/sema/patch/FrontendLambdaResolutionPatch.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendVariableAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendSuiteResolver.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/FrontendLoweringFunctionPreparationPass.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/item/LambdaConstructItem.java`
- `src/main/java/gd/script/gdcc/lir/insn/ConstructLambdaInsn.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/ConstructInsnGen.java`
- `src/main/c/codegen/include_451/gdcc/gdcc_callable.h`
- `src/main/c/codegen/template_451/func.ftl`
- `src/main/c/codegen/template_451/entry.h.ftl`
- `src/main/c/codegen/template_451/entry.c.ftl`

### 10.2 Godot

- `modules/gdscript/gdscript_parser.h`（`LambdaNode`）
- `modules/gdscript/gdscript_analyzer.cpp`（capture 沿 parent lambda 链复制）
- `modules/gdscript/gdscript_compiler.cpp`（`write_lambda`）
- `modules/gdscript/gdscript_byte_codegen.cpp`（`OPCODE_CREATE_*_LAMBDA`）
- `modules/gdscript/gdscript_lambda_callable.h/.cpp`
- `modules/gdscript/gdscript_vm.cpp`（`OPCODE_CREATE_LAMBDA` / `OPCODE_CREATE_SELF_LAMBDA`）
