# FrontendTypeCheckAnalyzer 实施计划

> 本文档给出 `FrontendTypeCheckAnalyzer` 及其相邻补洞工作的冻结实施计划，目标是在进入最终 lowering 之前补齐 frontend 最后一层 typed semantic gate，并补上 `@onready` 的最小用法验证。typed gate 仍主要覆盖 local 显式 declared initializer compatibility、class property initializer compatibility、return compatibility，以及 condition bool contract。本文档只保留当前代码库校对后的设计结论、分步骤实施与验收细则，不记录执行流水账。

## 文档状态

- 状态：实施计划（T0-T1 已完成并验收，T2-T7 尚未落地）
- 更新时间：2026-03-19
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_top_binding_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/scope_type_resolver_implementation.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
- 明确非目标：
  - 不在这里补 suite merge、definite return、missing-return exhaustiveness 分析
  - 不在这里转正 `lambda`、`for`、`match`、parameter default、block-local `const` 等当前 unsupported/deferred 子树
  - 不在这里扩张 `int -> float`、`StringName` / `String`、`null -> Object` 等当前未支持的隐式兼容
  - 不在这里引入新的 `FrontendAnalysisData` side table，除非后续实现证明诊断-only phase 无法满足需要
  - 不在这里直接生成 lowering/LIR 产物
  - 不在 MVP 内复刻 Godot 的 class-member initializer declaration-order、default-state visibility、member-level cycle/resolving graph 语义；同 class 非静态依赖将通过显式 boundary 封口

---

## 1. 背景与目标

当前 frontend 已经稳定发布：

- `symbolBindings()`
- `resolvedMembers()`
- `resolvedCalls()`
- `expressionTypes()`

但在进入 lowering 之前，仍缺少最后一层“typed contract 是否可 lowering”的显式检查。现状会导致一部分已解析表达式虽然拿到了 binding/member/call/type 事实，却还没有在 frontend 侧被明确拦截：

- `var x: T = expr` 中 `expr` 是否兼容 `T`
- class property initializer 是否兼容 property type
- `return` / `return expr` 是否兼容 callable 的 return slot
- `if` / `elif` / `while` / `assert` 条件是否满足 bool contract

本计划的目标是新增一个 diagnostics-only 的 `FrontendTypeCheckAnalyzer`，把这些 lowering 前必须成立的 typed contract 前移到 frontend，并保持以下工程目标：

1. 复用已经发布的 `expressionTypes()`，不重新发明表达式求值或类型推断。
2. 对应当兼容 exact `Variant` slot 的位置，统一复用 `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)`，而不是各处手写 `Variant` 分支。
3. 保持现有 body-phase diagnostic owner 边界，不为 upstream 已经失败/暂缓/不支持的表达式再补第二条同级错误。
4. 只把 lowering 真正需要的 contract 前移，不把该 phase 膨胀成新的 binder/body mega-phase。

---

## 2. 当前代码库事实与约束

### 2.1 当前 phase 顺序

`FrontendSemanticAnalyzer` 当前稳定顺序是：

1. skeleton
2. scope
3. variable inventory
4. top binding
5. chain binding
6. expr typing
7. type check

这意味着当前 `FrontendTypeCheckAnalyzer` 消费的稳定事实已经是：

- skeleton 中冻结的 property / function metadata
- `scopesByAst()`
- `symbolBindings()`
- `resolvedMembers()` / `resolvedCalls()`
- `expressionTypes()`

### 2.2 concrete-slot compatibility 的唯一公开入口

当前代码库已经冻结一条重要合同：

- `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(slotType, valueType)` 是 frontend 内部唯一公开的 concrete-slot compatibility gate
- exact `Variant` slot 接受任意来源类型
- 其余 slot 继续回退 `ClassRegistry.checkAssignable(...)`

这条合同不能被新 analyzer 绕开，原因是：

- `ClassRegistry.checkAssignable(int, Variant)` 当前并不会返回 `true`
- 若 return slot / property slot / local slot 直接调用 `checkAssignable(...)`，会把 exact `Variant` 目标错误地收紧
- 用户已经明确要求：对于应当兼容 `Variant` 的位置，必须复用 `checkAssignmentCompatible(...)`

### 2.3 lowering 已经要求 bool condition

backend 当前已经冻结以下事实：

- `CBodyBuilder.jumpIf(...)` 要求 condition type 为 `bool`
- `ControlFlowInsnGen` 也会对 `GoIfInsn` 的 condition variable 做 bool 检查

因此 frontend 的 condition contract 不能采用“truthy / falsy 宽松兼容”，而必须在 lowering 前显式要求 bool。

### 2.4 当前进入 type check 前的剩余缺口

当前 local initializer、class property initializer、return value、assert/if/elif/while condition 的表达式，已经被 top-binding / chain-binding / expr-typing 访问并发布事实。

其中 class property initializer 的当前冻结事实已经是：

- 只放开 `VariableDeclaration(kind == VAR && value != null)` 且 declaration scope 为 `ClassScope` 的最小支持岛
- `FrontendTopBindingAnalyzer` 对该域的 bare identifier / bare callee / top-level `TYPE_META` chain head 直接消费 shared `Scope` 的 class/global lookup，而不是复用 `FrontendVisibleValueResolver(EXECUTABLE_BODY)`
- `FrontendChainBindingAnalyzer` 与 `FrontendExprTypeAnalyzer` 已为该支持岛发布 `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()`
- class `const` initializer 仍明确留在正式 body-phase 支持面之外

但这些事实当前只证明“property initializer subtree 已可发布 binding/member/call/type side table”，并不自动等价于“frontend 已拥有 class-member initializer semantic domain”。

为避免把 shared class/global lookup 误读为成员初始化语义，本计划在 T0.5 明确收紧 MVP 支持面：

- property initializer 不支持访问同 class 下的非静态 property / method / signal / `self`
- 该收紧是对 T0 语义面的封口，而不是对 T0 事实发布支持岛的回滚
- MVP 继续允许 property initializer 使用当前已可稳定发布事实的非该类实例依赖，例如 literal、普通表达式子树、global/type-meta static route 等

因此，`FrontendTypeCheckAnalyzer` 当前的真实剩余缺口不再是“补一个完整成员初始化语义域”，而是：

- 在 T0.5 先把 property initializer 支持面收口到 frontend 能 defend 的 MVP 语义
- 再进入 T1 之后的 diagnostics-only typed contract gate 本体

### 2.5 return slot 的当前真源

当前 callable return target 的稳定来源是：

- 普通 `FunctionDeclaration`：使用 skeleton 已发布的 `FunctionDef.getReturnType()`
- `ConstructorDeclaration`：当前 frontend / LIR 合同下统一建模为 `void _init(...)`
- 名字就是 `_init` 的函数：在 skeleton 层同样按 constructor 语义收口到 `void`

因此，type check phase 不应重新解析 return type 文本，也不应再发第二次 `sema.type_resolution`；它应直接消费 skeleton 已冻结的 callable metadata。

### 2.6 `@onready` 的当前状态

当前 frontend 对 `@onready` 的已落地行为仍停留在 annotation retention：

- skeleton phase 会把 `@onready` 保留到 property annotations
- 当前尚未验证 owner class 是否派生自 `Node`
- 当前尚未验证 `@onready` 不可用于 `static` property
- 当前也尚未引入 `_ready()` 时序或延迟初始化执行语义

因此，计划中的 `@onready` 新阶段必须明确是“用法验证”，而不是“完整运行时/初始化时序语义”。

---

## 3. 冻结设计结论

### 3.1 phase 位置与产物形态

- 新 phase 命名为 `FrontendTypeCheckAnalyzer`
- 插入位置固定在 `FrontendExprTypeAnalyzer` 之后、lowering 之前
- 该 phase 是 diagnostics-only analyzer，不新增 side table
- `FrontendAnalysisData` 只继续通过 `updateDiagnostics(...)` 刷新边界快照，不新增 `updateTypeChecks(...)` 之类的新发布接口

### 3.2 诊断 owner 与 category

- `FrontendTypeCheckAnalyzer` 对自己负责的 typed contract 使用 `sema.type_check`
- `sema.type_check` 的 severity 固定为 `error`
- `FrontendTypeCheckAnalyzer` 对 property `:=` / 未声明显式类型 property 的手动声明提醒使用 `sema.type_hint`
- `sema.type_hint` 的 severity 固定为 `warning`
- `@onready` 用法验证阶段使用独立 category `sema.annotation_usage`
- `sema.annotation_usage` 同样固定为 `error`
- `sema.unsupported_annotation` 继续只保留给“已识别但当前未实现”的 annotation，本计划不把合法 annotation 的非法摆放混进该 category
- upstream 已经发出的 `sema.binding` / `sema.member_resolution` / `sema.call_resolution` / `sema.expression_resolution` / `sema.unsupported_*` / `sema.deferred_*` 仍保持原 owner，不在 type check phase 重复翻译

### 3.3 新 analyzer 只消费稳定表达式事实

对需要检查的 expression root：

- `expressionTypes().status() == RESOLVED` 或 `DYNAMIC`：允许继续做 compatibility check
- `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`：跳过，不再补第二条 typed contract 诊断

这条规则的目的不是“放过错误”，而是保持单一 owner：

- upstream 已经说明该表达式为什么不能稳定求值
- type check phase 只负责“表达式已稳定时，它是否满足目标 slot contract”

### 3.4 `checkAssignmentCompatible(...)` 的使用边界

以下位置都必须统一复用 `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)`：

- ordinary local 显式 declared initializer
- class property initializer
- 非 `void` callable 的 `return expr`
- bool condition contract（目标 slot 固定为 `bool`）

这保证：

- exact `Variant` target 继续接受任意已解析值
- bool slot 仍然保持 strict，不会因为 value 是 `Variant`/`DYNAMIC` 就被放宽

### 3.5 bare `return` 的冻结语义

本计划冻结以下 return contract：

- target slot 为 `void`
  - `return`：合法
  - `return expr`：报错
- target slot 非 `void`
  - `return expr`：按 `checkAssignmentCompatible(targetType, exprType)` 检查
  - bare `return`：按“synthetic `Nil` value”处理，再与 target slot 做 compatibility check

之所以把 bare `return` 视为 `Nil` 而不是 `Void`，是为了保持当前 strict contract 一致：

- `Variant` return slot 应兼容 bare `return`
- exact object / numeric / bool slot 不应因为 bare `return` 被误判为可接受
- 当前 frontend 也没有放宽 `null -> Object` 兼容

### 3.6 class property initializer 的 MVP 支持策略

class body 作为整体仍保持“非 executable region”的冻结边界，不整体放开。

本计划在 T0 已落地的事实上继续保留一个最小支持岛：

- `VariableDeclaration.kind() == VAR`
- 位于 class body
- `value() != null`

即：

- class body 仍只继续遍历 callable / inner class 声明
- 但 property initializer expression subtree 需要被显式纳入 top-binding / chain-binding / expr-typing 的已发布支持面
- class constant initializer 继续不进入正式 body-phase 支持面

同时，MVP 明确追加一条支持面收口：

- property initializer 不支持访问 declaring class 的 non-static property / method / signal / `self`
- 这条边界同时适用于 instance property initializer 与 static property initializer
- 其目的不是模仿 Godot 的完整成员初始化规则，而是避免在缺少 declaration-order/default-state/cycle model 时把 shared class lookup 错当成正确语义

### 3.7 property initializer 的解析上下文

property initializer 的 restriction 固定按 property staticness 选择：

- instance property initializer -> `ResolveRestriction.instanceContext()`
- static property initializer -> `ResolveRestriction.staticContext()`

同时冻结一条实现边界：

- property initializer 的 bare identifier 解析不直接复用 `FrontendVisibleValueResolver(EXECUTABLE_BODY)`
- 该位置没有 callable-local declaration-order / local inventory 语义
- 应直接走 shared `Scope.resolveValue(...)` / `resolveFunctions(...)` / `resolveTypeMeta(...)` 的 class/global contract，并沿用 top-binding 现有的 namespace fallback 顺序

同时冻结一条 MVP boundary：

- shared scope lookup 在 property initializer 中只负责查找候选，不等价于“允许直接消费同 class 非静态成员”
- 一旦首个依赖 step 命中当前 class 的 non-static property / method / signal / `self`，对应 owner 必须发显式 unsupported diagnostic 并封口
- downstream chain step、expr typing 与 type check 只允许传播上游状态，不得再补第二条同级错误

这样可以避免把 `FrontendVisibleValueResolver` 扩张成一个混合 executable-body 与 class-member initializer 的“万能可见性解析器”，也避免把 shared class lookup 误包装成完整成员初始化语义。

### 3.8 `@onready` 用法验证的冻结边界

`@onready` 的新增阶段只做最小 usage contract，不承担 runtime initialization timing：

- 只验证 annotation 是否挂在 class property `var` 上
- 验证 owner class 必须是 `Node` 或其派生类
- 验证 `@onready` 不可用于 `static` property
- diagnostics owner 与 category 固定为 `sema.annotation_usage`
- 不在这里实现 `_ready()` 前后的执行时机、dependency ordering、或 `@onready` 表达式的额外 lowering 行为

### 3.9 property `:=` 与未声明显式类型 property 的 MVP 合同

当前 property type metadata 的冻结事实是：

- skeleton/property metadata 对缺失 type ref 与 `:=` 都先收口为 exact `Variant`
- 当前 `:=` 最小回填只作用于 block-local inventory，不回写 property metadata

这意味着：

- `var field := expr` 与 `var field = expr` 这类未声明显式类型的 property，在 T3 中都不会获得 property-side inference
- 若 T3 直接从 skeleton/property metadata 读取 target type，则这两类声明在 MVP 下都会以 exact `Variant` target 参与 compatibility check
- 因此它们的 compatibility pass 不能被表述成“property 类型已被成功推导”

因此本计划冻结以下实现边界：

- MVP 不支持 property `:=` 的类型推导，也不因为 RHS 稳定类型而回写 property metadata
- `var field := expr` 与 `var field = expr` 仍按普通 initializer expression 参与 type-check 流程
- 若 property 带显式 declared type：
  - incompatibility 继续发 `sema.type_check` error
- 若 property 使用 `:=` 或未声明显式类型：
  - 当 RHS 为 `RESOLVED` 或 `DYNAMIC` 时，`FrontendTypeCheckAnalyzer` 发 `sema.type_hint` warning
  - warning 必须指出建议用户手动补上的显式类型
  - `RESOLVED(T)` 建议写成 `: T`
  - `DYNAMIC(Variant)` 建议写成 `: Variant`
- 若 RHS 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`：
  - 跳过 hint warning，继续保持 upstream owner
- 若后续确实要支持 property `:=` 推导，必须先新增 dedicated property inference/backfill stage，或扩展 skeleton/property metadata 的发布合同

### 3.10 本 phase 明确不做的事

- 不检查 missing return / must-return-on-all-paths
- 不对 unsupported subtree 做额外补诊断
- 不让 property initializer 的引入把整个 class body 变成新的 executable scope
- 不把 property `:=` 变成 property-side inference；当前 `:=` backfill 仍只属于 block-local
- 不在这里补 class-member initializer declaration order / default-state / cycle detection 语义
- 不把 `@onready` 用法验证扩大成 runtime timing 语义

---

## 4. 预计改动面

### 4.1 生产代码

预计至少涉及以下文件：

- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendPropertyInitializerSupport.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendTopBindingAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendChainBindingAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendAnnotationUsageAnalyzer.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendTypeCheckAnalyzer.java`

### 4.2 测试

预计至少涉及以下测试文件：

- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonAnnotationTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendSemanticAnalyzerFrameworkTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendTopBindingAnalyzerTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendChainBindingAnalyzerTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzerTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendAnnotationUsageAnalyzerTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendTypeCheckAnalyzerTest.java`

### 4.3 文档

功能落地后需要同步更新：

- `doc/module_impl/frontend/frontend_rules.md`
- `doc/module_impl/frontend/diagnostic_manager.md`
- `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
- 新的长期事实源文档 `doc/module_impl/frontend/frontend_annotation_usage_analyzer_implementation.md`
- 新的长期事实源文档 `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`

---

## 5. 分步骤实施

### 5.1 阶段 T0：补齐 class property initializer 的上游发布事实

**目标**

先让 class property initializer 能像 ordinary local initializer 一样，稳定拿到：

- `symbolBindings()`
- `resolvedMembers()`
- `resolvedCalls()`
- `expressionTypes()`

否则 `FrontendTypeCheckAnalyzer` 没有可消费的事实源。

**实施细则**

- 当前状态（2026-03-19）：
  - `FrontendTopBindingAnalyzer`：已完成
  - `FrontendChainBindingAnalyzer`：已完成
  - `FrontendExprTypeAnalyzer`：已完成
  - T0 单元测试补齐：已完成
- 当前已通过的 targeted tests：
  - `FrontendChainReductionHelperTest`
  - `FrontendTopBindingAnalyzerTest`
  - `FrontendChainBindingAnalyzerTest`
  - `FrontendExprTypeAnalyzerTest`
  - `FrontendSemanticAnalyzerFrameworkTest`
  - `FrontendAnalysisDataTest`
- 在 `FrontendTopBindingAnalyzer` 中新增“property initializer 支持岛”：
  - 只放开 class body 下 `VariableDeclaration(kind == VAR && value != null)` 的 `value()` subtree
  - 不把整个 class body 改造成 executable region
  - 为该支持岛单独设置 restriction/static-context，而不是沿用 `supportedExecutableBlockDepth`
- property initializer 的 bare identifier 绑定路径应与 executable-body 路径分开：
  - executable body 继续走 `FrontendVisibleValueResolver`
  - property initializer 走 shared scope value/function/type-meta lookup 与现有 top-binding fallback 顺序
- 在 `FrontendChainBindingAnalyzer` 中为 property initializer expression subtree 补 chain/member/call 发布
- 在 `FrontendExprTypeAnalyzer` 中为 property initializer expression subtree 补 expression type 发布
- route-head-only `TYPE_META`、unsupported subtree sealing、discarded-expression policy、assignment contract 等既有合同保持不变

**验收标准**

- class property initializer root 在 `expressionTypes()` 中有稳定 published fact
- initializer 内的 bare identifier / member / call 若属于当前支持面，对应 side table 均已发布
- static property initializer 中对实例 property / instance method 的访问，仍按现有 owner 发布 blocked/failed 诊断，不被静默吞掉
- class body 其他非 initializer 区域仍不被误判为 executable body

### 5.2 阶段 T0.5：收紧 property initializer 的 MVP 支持面

**目标**

在不引入 class-member initializer declaration-order/default-state/cycle model 的前提下，把 T0 的 property initializer 支持岛收口为 frontend 能在 MVP 中自洽维护的语义面。

**实施细则**

- 保留 T0 已落地的 binding/member/call/expression fact publication
- 对 property initializer 新增一条显式 MVP boundary：
  - 不支持访问同 class 下的 non-static property / method / signal / `self`
  - 该 boundary 同时适用于 instance property 与 static property
- shared `Scope.resolveValue(...)` / `resolveFunctions(...)` / `resolveTypeMeta(...)` 仍可用于候选查找，但命中上述 same-class non-static 候选后，不得再把它当成 ordinary supported binding/member/call 消费
- 首个非法依赖 step 必须由对应 upstream owner 发出显式 unsupported diagnostic：
  - bare identifier / bare callee / `self` 由 top-binding owner 封口
  - 首个在 chain suffix 中暴露出的非法依赖由 chain-binding owner 封口
- downstream expr typing 与后续 type-check 只能复用上游 status，不得补第二条同级错误
- MVP 明确不在此阶段引入 member initialization order、default-state visibility、forward-reference 语义、或 member-level cycle detection

**实施状态**

- `T0.5.1` root 级 boundary 封口（bare identifier / bare callee / `self`）：已完成
- `T0.5.2` chain suffix 首个 same-class non-static 依赖封口：已完成
- `T0.5.3` 单元测试与回归验收：已完成
- 当前已落地事实（2026-03-19）：
  - `FrontendTopBindingAnalyzer` 会在 property initializer 中对 bare identifier、bare callee 与 `self` 的 same-class non-static 依赖发布首个 `sema.unsupported_binding_subtree`
  - `FrontendChainBindingAnalyzer` 会对 chain suffix 中首个 same-class non-static property / signal / method 依赖，以及 same-class type-meta route 命中的 non-static property / signal / method 依赖发布 `UNSUPPORTED`
  - `FrontendExprTypeAnalyzer` 对 top-binding owner 的 property-initializer boundary 只传播 `BLOCKED`，对 chain-owned boundary 只传播 `UNSUPPORTED`，不再补第二条 expr-owned 同级错误
  - 回归测试已覆盖 bare property / signal / bare callee / `self`、same-class suffix、same-class type-meta route，以及允许的 static route 正向场景

**验收标准**

- `var mirror := payload`、`var ready := read()`、`var copy := self.value` 这类依赖同 class 非静态内容的 property initializer，会收到首个 explicit unsupported diagnostic
- `Worker.build()`、builtin/global enum static route、global callable 等非该类实例依赖，仍保持 T0 已发布的支持面
- class constant initializer 仍不被误打开
- 其他 class body 区域仍不被误判为 executable body

### 5.3 阶段 T1：落地 `FrontendTypeCheckAnalyzer` 骨架与 phase 接线

**目标**

建立一个 diagnostics-only phase，稳定消费 skeleton + scope + binding/member/call/type 事实。

**实施细则**

- 新增 `FrontendTypeCheckAnalyzer`
- 在 `FrontendSemanticAnalyzer` 中把 phase 顺序改为：
  1. skeleton
  2. scope
  3. variable
  4. top binding
  5. chain binding
  6. expr typing
  7. type check
- 在 `FrontendSemanticAnalyzer` 的构造链中补齐该 analyzer 的默认实例与可注入构造重载
- type check phase 只刷新 diagnostics snapshot，不新增 analysis data 发布槽位
- analyzer 内部应维护最小上下文：
  - 当前 class metadata
  - 当前 callable return slot
  - 当前 restriction/static-context
  - executable-body 深度
  - property initializer 临时上下文

**实施状态**

- `T1.1` `FrontendTypeCheckAnalyzer` 骨架与最小上下文 walker：已完成
- `T1.2` `FrontendSemanticAnalyzer` phase 接线与可注入构造重载：已完成
- `T1.3` T1 单元测试与回归验收：已完成
- 当前已通过的 targeted tests：
  - `FrontendTypeCheckAnalyzerTest`
  - `FrontendSemanticAnalyzerFrameworkTest`
  - `FrontendExprTypeAnalyzerTest`
  - `FrontendTopBindingAnalyzerTest`
  - `FrontendChainBindingAnalyzerTest`
  - `FrontendAnalysisDataTest`

**验收标准**

- `FrontendSemanticAnalyzerFrameworkTest` 能证明新 phase 在 expr typing 之后运行
- phase 执行后 diagnostics snapshot 已刷新到 `analysisData.diagnostics()`
- `FrontendAnalysisData` 拓扑保持不变

### 5.4 阶段 T2：ordinary local 显式 declared initializer compatibility

**目标**

补齐 `var local: T = expr` 的 lowering 前 typed gate。

**实施细则**

- 仅对 ordinary local `var` 做检查
- 仅在 declaration 带显式 declared type 时要求产生独立 type-check 诊断
- `:=` local 不在本阶段产生新的 compatibility 诊断，继续由 expr typing 的 backfill 合同负责
- 从已发布 `BlockScope` local slot 获取 target type，不重新解析类型文本
- 对 RHS：
  - `RESOLVED` / `DYNAMIC` -> 调 `checkAssignmentCompatible(slotType, rhsType)`
  - 其余 status -> 跳过，保持 upstream owner

**验收标准**

- `var x: Variant = expr` 对任意已解析 RHS 不报 compatibility error
- `var x: float = 1` 在当前 strict contract 下继续报错，不引入 numeric promotion
- 若 RHS 已因 upstream 问题发布为 `FAILED` / `UNSUPPORTED` / `DEFERRED` / `BLOCKED`，type check phase 不重复发第二条错误

### 5.5 阶段 T3：class property initializer compatibility 与显式类型提示

**目标**

补齐 class property initializer 的 lowering 前 typed gate，并对 property `:=` / 未声明显式类型 property 发出“请手动补显式类型”的 warning 提示。

**实施细则**

- 在 class body `VariableDeclaration(kind == VAR && value != null)` 上执行检查
- property target type 必须来自当前 class skeleton/property metadata，而不是重新解析 AST `typeRef`
- instance/static property 的 restriction 必须与 property 自身 staticness 对齐
- RHS expression type 来源于阶段 T0 已发布的 `expressionTypes()`
- compatibility 继续统一走 `checkAssignmentCompatible(propertyType, initializerType)`
- 对带显式 declared type 的 property：
  - incompatibility 发 `sema.type_check` error
- 对 property `:=` / missing type ref：
  - 当前 skeleton/property metadata 仍会把 target 冻结为 `Variant`
  - 仍按普通 initializer expression 走 compatibility 检查，但这条 compatibility pass 不得被表述成 inferred property type
  - 当 RHS 已发布为 `RESOLVED(T)` 时，发 `sema.type_hint` warning，提示把声明改成显式 `: T`
  - 当 RHS 已发布为 `DYNAMIC(Variant)` 时，发 `sema.type_hint` warning，提示把声明改成显式 `: Variant`
  - 当 RHS 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 时，不补 hint warning，继续保持 upstream owner
- warning message 必须明确指出：
  - MVP 当前不支持 property `:=` 类型推导
  - 或当前 property 缺少显式类型
  - 推荐的显式声明写法是什么

**验收标准**

- `var field: Variant = expr` 不因 exact `Variant` target 被误报
- `var field: int = "x"` 会发 `sema.type_check` error
- `var field := 1` 会发 `sema.type_hint` warning，提示补 `: int`
- `var field = 1` 会发 `sema.type_hint` warning，提示补 `: int`
- `var field := dynamic_call()` 若 RHS 已发布为 `DYNAMIC(Variant)`，会发 `sema.type_hint` warning，提示补 `: Variant`
- `var field := "x"` 不因为 metadata 中的 `Variant` target 被伪装成“已完成严格 property compatibility”
- static property initializer 的依赖表达式若命中 instance-only binding，仍由 upstream owner 发 restriction 诊断；type check phase 不制造重复错误

### 5.6 阶段 T4：return compatibility

**目标**

补齐 callable return slot 与 `return` / `return expr` 的兼容性检查。

**实施细则**

- 进入 `FunctionDeclaration` 时，从 skeleton 已发布的 `FunctionDef` 读取 effective return type
- `ConstructorDeclaration` 与 `_init` 固定使用 `void`
- `return expr`：
  - target 为 `void` -> 直接报错
  - target 非 `void` -> 对已发布 expression type 调 `checkAssignmentCompatible(targetType, exprType)`
- bare `return`：
  - target 为 `void` -> 合法
  - target 非 `void` -> 以 synthetic `Nil` value 执行 compatibility check

**验收标准**

- `-> Variant` 的函数接受任意已解析 `return expr`
- `-> Variant` 的函数接受 bare `return`
- `-> int` 的函数对 bare `return` 报错
- constructor / `_init` 中的 `return expr` 报错
- 本阶段仍不做 all-path return completeness 分析

### 5.7 阶段 T5：condition bool contract

**目标**

让 lowering 之前的 control-flow condition 类型与 backend bool contract 对齐。

**实施细则**

- 检查节点固定为：
  - `AssertStatement.condition`
  - `IfStatement.condition`
  - `ElifClause.condition`
  - `WhileStatement.condition`
- 目标 slot 固定为 `GdBoolType.BOOL`
- 对已发布 expression type 调 `checkAssignmentCompatible(GdBoolType.BOOL, conditionType)`
- 由于 bool slot 不是 `Variant`，`DYNAMIC`/`Variant` condition 当前也必须报错
- `assert` 的 message 表达式继续只做 ordinary expression typing，不参与 bool contract

**验收标准**

- `if true:`、`while flag:`
  - 当 condition 已发布为 `bool` 时通过
- `if 1:`、`while value:`、`assert(payload)`：
  - 若 condition 已发布为非 `bool`，发 `sema.type_check` error
- `if something()` 且 `something()` 的表达式已因 upstream 失败/暂缓而没有稳定 typed value：
  - type check phase 不补第二条错误

### 5.8 阶段 T6：`@onready` 用法验证

**目标**

在不实现完整初始化时序语义的前提下，补齐 `@onready` 的最小合法使用验证。

**实施细则**

- 在 skeleton/property annotation 事实已经可用的前提下，新增 diagnostics-only 的 `FrontendAnnotationUsageAnalyzer`
- 验证规则固定为：
  - `@onready` 只能用于 class property `var`
  - owner class 必须是 `Node` 或其派生类
  - `@onready` 不可用于 `static` property
- diagnostics category 固定为 `sema.annotation_usage`
- 该阶段只做 usage contract，不实现 `_ready()` timing、deferred evaluation、或新的 property initializer lowering 语义
- `sema.unsupported_annotation` 继续只表示“已识别但当前未实现”的 annotation，而不是“实现了但摆放非法”的 annotation
- 在 `FrontendSemanticAnalyzer` 中把 phase 顺序扩展为：
  1. skeleton
  2. scope
  3. variable
  4. top binding
  5. chain binding
  6. expr typing
  7. annotation usage
  8. type check

**验收标准**

- `extends Node` + `@onready var child = $Child` 不因 usage validator 被误报
- `extends RefCounted` + `@onready var child = $Child` 发 `sema.annotation_usage` error
- `@onready static var child = $Child` 发 `sema.annotation_usage` error
- 该阶段不改变 annotation retention side-table 与 property skeleton annotation 的既有行为

### 5.9 阶段 T7：文档同步与回归

**目标**

把新 phase 的 owner 边界、category、支持面和 fail-closed 规则同步到 frontend 文档体系。

**实施细则**

- 更新 `frontend_rules.md`：
  - 补上 type-check phase 在 body-phase owner 链路中的位置
  - 补上 `checkAssignmentCompatible(...)` 在 return/property/local/condition contract 中的统一使用约定
  - 补上 property initializer MVP 不支持同 class 非静态依赖的约束
  - 补上 property `:=` / 未声明显式类型 property 的 warning 合同
  - 补上 `@onready` usage validation 与 `sema.annotation_usage` 的 owner 边界
- 更新 `diagnostic_manager.md`：
  - 新增 `sema.type_check`
  - 新增 `sema.type_hint`
  - 新增 `sema.annotation_usage`
- 更新 `frontend_chain_binding_expr_type_implementation.md`：
  - 标明 class property initializer 已进入 published support surface
  - 标明 MVP 下同 class 非静态依赖将被收口为 explicit unsupported boundary
  - 标明 expr typing 之后会依次接上 annotation-usage / type-check phase
- 新增长期事实源 `frontend_annotation_usage_analyzer_implementation.md`
- 新增长期事实源 `frontend_type_check_analyzer_implementation.md`

**验收标准**

- 文档不再暗示 lowering 会接收尚未做 typed contract gate 的 local/property/return/condition
- code comments、diagnostic contract、implementation doc 三者不冲突

---

## 6. 测试策略

### 6.1 新增测试重点

- `FrontendTypeCheckAnalyzerTest`
  - local explicit declared initializer compatibility
  - class property initializer compatibility
  - return compatibility
  - condition bool contract
  - `Variant` target 接受任意已解析值
  - bare `return` 作为 `Nil` 与 return slot 的兼容性
  - property `:=` 与未声明显式类型 property 会发 `sema.type_hint`
  - warning 会给出建议补写的显式类型
  - property `:=` 不被误当成 strict property typed gate
  - property initializer 对同 class 非静态依赖的 MVP boundary
- `FrontendAnnotationUsageAnalyzerTest`
  - `@onready` owner class / staticness usage validation
  - `@onready` 不抢 skeleton retention owner

### 6.2 需要扩展的现有测试

- `FrontendTopBindingAnalyzerTest`
  - property initializer 的 bare identifier / bare callee / top-level type-meta route head
- `FrontendChainBindingAnalyzerTest`
  - property initializer 的 member/call 发布
- `FrontendExprTypeAnalyzerTest`
  - property initializer 的 expression type 发布
- `FrontendAnnotationUsageAnalyzerTest`
  - `@onready` 的 usage validation
- `FrontendClassSkeletonAnnotationTest`
  - `@onready` 的 annotation retention
- `FrontendSemanticAnalyzerFrameworkTest`
  - 新 phase 顺序、boundary snapshot refresh、probe analyzer 注入构造

### 6.3 建议的 targeted test 组合

建议实现期间至少维持以下 targeted tests：

- `FrontendTopBindingAnalyzerTest`
- `FrontendChainBindingAnalyzerTest`
- `FrontendExprTypeAnalyzerTest`
- `FrontendAnnotationUsageAnalyzerTest`
- `FrontendClassSkeletonAnnotationTest`
- `FrontendTypeCheckAnalyzerTest`
- `FrontendSemanticAnalyzerFrameworkTest`
- 如 property initializer 需要额外锚定 skeleton/property metadata，可追加 `FrontendClassSkeletonTest`

---

## 7. 风险与 guard rail

### 7.1 不要把 `ClassRegistry.checkAssignable(...)` 当成前端最终兼容性入口

这会直接破坏 exact `Variant` slot 的现有前端合同，尤其是：

- `Variant` return slot
- `Variant` property
- `Variant` local declared initializer

### 7.2 不要为了 property initializer 把整个 class body 放开成 executable region

这会把当前未设计完成的 class body 语义一次性拖进 top/chain/expr/type-check，扩大影响面。当前必须坚持“只放开 property initializer subtree”。

### 7.3 不要把 shared class lookup 误当成完整的 member-initializer 语义

T0 发布的是 fact support island，不是 declaration-order/default-state/cycle-aware 语义域。当前 MVP 已通过 T0.5 显式封口 same-class non-static 依赖，后续阶段不得再把这些路径回退成 ordinary supported property initializer。

### 7.4 不要让 type check phase 抢 upstream diagnostic owner

一旦 expression root 已经有 `FAILED` / `UNSUPPORTED` / `DEFERRED` / `BLOCKED` 的 published status，新 analyzer 只能跳过，不能再发一条“类型不兼容”把根因遮掉。

### 7.5 不要重新解析 declared type

target slot type 必须消费 skeleton / variable inventory 已经冻结的事实；否则会重新引入：

- 第二次 `sema.type_resolution`
- 与 skeleton/variable phase 漂移的类型语义
- `_init` / constructor return slot 语义分叉

### 7.6 不要把 property `:=` 的 `Variant` metadata 误写成“严格 typed gate 已完成”

在 property-side inference/backfill 还不存在之前，`var field := expr` 与 `var field = expr` 的 target metadata 仍可能只是 `Variant`。此时若直接宣称 T3 已完整覆盖 property compatibility，只会把结构性缺口写成假完成。

### 7.7 不要把 `sema.type_hint` warning 偷偷实现成隐式推导

warning 的职责是提醒用户手动补显式类型，而不是暗中修改 property metadata、重写 scope 中的 property type、或让后续 phase 假定该 property 已被推导成 warning 提示的类型。

---

## 8. 最终验收口径

当以下条件同时成立时，本计划可以视为完成：

1. `FrontendSemanticAnalyzer` 已稳定接入 `FrontendAnnotationUsageAnalyzer` 与 `FrontendTypeCheckAnalyzer`，且 phase 顺序固定为 expr typing 后、annotation usage、再到 type check。
2. local 显式 declared initializer、class property initializer、return、condition 四类 contract 都已覆盖。
3. property initializer 的 MVP 支持面已明确收口，同 class 非静态依赖会被显式封口，而不是继续伪装成 ordinary supported member initializer。
4. `@onready` 最小 usage contract 已覆盖，且非法摆放不会混进 `sema.unsupported_annotation`。
5. exact `Variant` target 的兼容性全部通过 `checkAssignmentCompatible(...)` 落地，没有残留直接 `checkAssignable(...)` 的错误接线。
6. class property initializer 已拥有完整的上游 binding/member/call/expression 发布事实，且 property `:=` / 未声明显式类型 property 不再被误表述成已拥有严格 typed gate。
7. `FrontendTypeCheckAnalyzer` 会对 property `:=` / 未声明显式类型 property 发出 `sema.type_hint` warning，明确告诉用户应手动补上的显式类型。
8. 新增 diagnostic owner/category 与现有 frontend 文档、注释、测试保持一致。
9. targeted frontend tests 通过，且不回归当前 unsupported/deferred 边界。
