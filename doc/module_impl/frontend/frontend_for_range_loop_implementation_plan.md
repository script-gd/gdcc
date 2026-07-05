# Frontend for-range loop 实施计划

> 本文档记录 `for` loop 从当前 deferred 状态推进到“仅支持 range-like loop”的具体实施计划。本文中的 `for-range` 特指所有最终复用 `gdcc.for_range_iter.*` intrinsic contract 的 loop form：既包括 `for iterator[: Type] in range(...)`，也包括 Godot 兼容的 `for iterator[: Type] in <int-expr>` 数值简写。目标是先让这两类 loop 进入 frontend shared semantic、compile gate、frontend CFG 与 body lowering 的正式支持面，同时要求第一版实现就预留未来任意 iterable / iterator `for` 的可扩展内部 iterator contract。

## 文档状态

- 状态：计划中
- 创建日期：2026-07-03
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/type/**`
  - `src/test/java/gd/script/gdcc/frontend/**`
  - `src/test/java/gd/script/gdcc/type/**`
  - `src/test/java/gd/script/gdcc/backend/**`
- 关联事实源：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_lowering_plan.md`
  - `doc/module_impl/frontend/frontend_loop_control_flow_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_gdcompiler_type_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_lir_intrinsic.md`
- 外部语义参考：
  - Godot docs `tutorials/scripting/gdscript/gdscript_basics.rst`
  - Godot source `modules/gdscript/gdscript_utility_functions.cpp`
  - Godot source `core/variant/variant_setget.cpp`

## 1. 范围与非目标

本轮只实现 range-like loop 支持面：

- 支持 `for i in range(stop):`
- 支持 `for i in range(start, end):`
- 支持 `for i in range(start, end, step):`
- 支持 `for i: int in range(...):`
- 支持 `for i in 3:`、`for i in limit:` 这类 `iterable` 已稳定发布为 `int` 的 Godot range 简写；其语义按 `range(stop)` 处理，但 frontend 不得通过伪造 `CallExpression("range")` AST 节点来实现。
- range 参数必须在 frontend 表达式分析中拥有 lowering-ready typed fact，并能作为 `int` slot 下沉到 `gdcc.for_range_iter.init`。
- `int` 简写的 `iterable` 表达式也必须在 frontend 表达式分析中拥有 lowering-ready typed fact，并能通过现有 ordinary boundary helper 进入 `int` stop slot。
- loop iterator binding 的 source-facing element type 是 `int`，不是 `GdccForRangeIter`。

本轮明确不实现：

- `for i in values:` 这类任意 iterable / iterator loop。
- `Array`、`Dictionary`、`String`、`Object`、typed array 等容器遍历。
- Godot 的 `for i in 2.2` / `for i in some_float` 浮点数值简写。它们仍属于 range-like 后续扩面，届时必须明确 `ceil` 语义并复用本文定义的内部 iterator contract，而不是再开一条平行 lowering 管线。
- 将 `range(...)` 当作普通 user-facing callable value。`range(...)` 在本计划中只作为 `for` iterable 位置的 loop-specific form 识别，不向普通 `resolvedCalls()` 发布 utility function route。
- 让 `GdCompilerType` 进入 source-facing declared type、ordinary `expressionTypes()`、ordinary local/property/return `slotTypes()`、public ABI、`Variant` pack/unpack 或 Godot runtime 普通调用路径。

## 2. 当前基线

当前代码库已经具备的基础：

- `gdparser` 的 `ForStatement` AST 已包含 `iterator`、`iteratorType`、`iterable`、`body`、`range` 字段。
- `GdScriptParserService` 只是外部 parser adapter，本仓库没有本地 parser grammar 可改；若 parse smoke 发现 `range` loop AST 形态不足，应先升级或修复外部 parser，而不是在 frontend analyzer 中猜文本。
- `FrontendScopeAnalyzer` 已为 `ForStatement` 建立 `FOR_BODY` scope，并保证 `iteratorType` 与 `iterable` 在外层 scope 下遍历，`body` 在独立 `FOR_BODY` scope 下遍历。
- `FrontendLoopControlFlowAnalyzer` 已把 `for` 与 `while` 一样视为 loop boundary，`break` / `continue` 在 `for` body 内不再报 `sema.loop_control_flow`。
- `FrontendVisibleValueResolver`、`FrontendVariableAnalyzer`、`FrontendTopBindingAnalyzer`、`FrontendChainBindingAnalyzer`、`FrontendExprTypeAnalyzer`、`FrontendLocalTypeStabilizationAnalyzer`、`FrontendVarTypePostAnalyzer` 与 `FrontendCompileCheckAnalyzer` 仍分别把 `for` 作为 deferred / unsupported boundary。
- `FrontendCfgGraphBuilder.processStatement(...)` 当前没有 `ForStatement` 分支；`break` / `continue` lowering 依赖 active `LoopFrame`。
- `FrontendCfgRegion` 当前只允许 `BlockRegion`、`FrontendIfRegion`、`FrontendElifRegion`、`FrontendWhileRegion`。
- `GdccForRangeIterType.FOR_RANGE_ITER` 已作为唯一 `GdCompilerType` 子类型存在。
- `doc/gdcc_lir_intrinsic.md` 已冻结四个 backend-owned intrinsic：`gdcc.for_range_iter.init`、`gdcc.for_range_iter.should_continue`、`gdcc.for_range_iter.next`、`gdcc.for_range_iter.get`。

当前实现张力：

- 文档仍把 `for` 放在 post-MVP / deferred 范围。
- loop-control semantic 已把 `for` 当成合法 loop boundary。
- backend/LIR 已具备 range iterator state 与 intrinsic。
- frontend shared semantic、compile gate、CFG 与 body lowering 尚未承认 `for-range` 为 compile-ready surface。

本计划的实施目标就是把这个张力收口为：“复用 range intrinsic contract 的 `for-range` 正式支持；非 range-like `for` 继续 fail-closed。”

## 3. 核心设计

### 3.1 range-like loop classifier

新增一个 frontend 内部 helper，用于识别且描述 supported `for` loop source form，并把它规范化为 lowering 可消费的内部 contract。建议命名为 `FrontendForLoopSupport`，放在 frontend sema/lowering 均可复用的位置；如果只被少量 analyzer 使用，保持为普通 final util class，不引入 public interface。第一版虽然只返回 range-like contract，但 helper 名称与 API 要保持面向未来对象迭代的可扩展形状，避免第二种 iterable 接入时再做大面积改名。

识别规则：

- 若 `ForStatement.iterable()` 是 `CallExpression`，则 `CallExpression.callee()` 必须是 bare `IdentifierExpression("range")`，且 `arguments().size()` 必须是 1、2 或 3。
- 若 `ForStatement.iterable()` 不是 `range(...)` call，但其 root 已稳定发布 lowering-ready typed fact，且该 typed fact 可通过现有 ordinary typed-boundary helper 进入 `int` slot，则将其识别为 Godot 的 `range(stop)` 简写。
- `float` 简写本轮仍不识别；它必须留给 follow-up，在明确 `ceil` 语义后复用同一 contract 扩面。
- 不支持 named argument、attribute call、subscript call 或用户变量 `range` shadowing 的普通 callable 路由；本轮将 `range(...)` 视为 loop-specific syntax form。
- `ForStatement.iterator()` 必须使用现有 AST 提供的 iterator name。
- `ForStatement.iteratorType()` 可为空；为空时 iterator element type 为 `int`。

helper 输出建议使用一个简单 record，例如：

```java
enum ForRangeSourceKind {
        RANGE_CALL,
        INT_SHORTHAND
}

record ForRangeLoopSpec(
        @NotNull ForStatement statement,
        @NotNull String iteratorName,
        @Nullable TypeRef declaredIteratorType,
        @NotNull ForRangeSourceKind sourceKind,
        @NotNull List<Expression> sourceOperands,
        @NotNull GdType elementType,
        @NotNull GdCompilerType iteratorStateType,
        @NotNull String initIntrinsicName,
        @NotNull String shouldContinueIntrinsicName,
        @NotNull String nextIntrinsicName,
        @NotNull String getIntrinsicName
) {}
```

这里 `iteratorStateType` 第一版固定为 `GdccForRangeIterType.FOR_RANGE_ITER`，四个 intrinsic 名称第一版固定为 `gdcc.for_range_iter.*`。但 consumer 必须通过 `GdCompilerType` 与 contract 字段读取 storage / operation 协议，不能把 `GdccForRangeIterType` 或 `gdcc.for_range_iter` 字面量散落到多个 analyzer、CFG builder 与 lowering processor 中。`elementType` 第一版固定为 `GdIntType.INT`，用于 source-facing loop variable binding 与 ordinary typed boundary。

`sourceOperands` 保留源码级 iterable 形态，不伪造 AST：

- `RANGE_CALL`：按源码顺序保存 1/2/3 个 `range(...)` arguments。
- `INT_SHORTHAND`：只保存一个 `stop` expression；后续 CFG/lowering 通过 contract 语义把它解释为 `(start=0, end=stop, step=1)`，不要构造假的 `0` / `1` AST 节点或假的 `range(stop)` call。

不要为当前唯一实现新增 public `GdIteratorType`、`ForIterator` interface 或 builder。未来新增第二种 compiler-owned iterator state 时，再根据实际第二个 subtype 扩展 sealed `GdCompilerType` 层次与对应 contract；但第一版的 helper、CFG item 与 lowering API 必须已经采用通用命名，以便未来对象迭代直接挂到同一 contract 管线。

### 3.2 iterator state 与 element type 分离

range lowering 需要两个完全不同的类型事实：

- iterator state type：`GdccForRangeIterType.FOR_RANGE_ITER`，只用于 hidden LIR local/temp、range intrinsic operand/result、C storage lifecycle。
- element type：`GdIntType.INT`，用于 `for` iterator binding、body 中 `i` 的 visible value、`slotTypes()`、assignment/type-check 与 return/boundary materialization。

禁止事项：

- 不把 `GdccForRangeIterType` 发布到 `expressionTypes()`。
- 不把 `GdccForRangeIterType` 发布为 loop variable 的 `slotTypes()`。
- 不让 `GdccForRangeIterType` 进入 declared type parser、ordinary implicit conversion matrix、public ABI 或 `Variant` pack/unpack。
- 不通过普通 `CallItem` 表达 `range(...)`，避免 body lowering 把它当成 user-facing call route。

### 3.3 未来 iterator 接口预留

未来任意 iterable 的接线应保持以下形状：

- semantic 阶段把 source iterable type 解析成一个内部 iterator contract。
- iterator contract 持有：
  - compiler-only `GdCompilerType` 子类型作为 iterator state storage。
  - source-facing `GdType` 作为 yielded element type。
  - init / should_continue / next / get 这组 operation 的 lowering 名称或 intrinsic 名称。
- lowering 只消费该 contract，不重新推导 iterable 语义。
- `range(...)`、`int` 简写、以及未来 `Object._iter_*` / container iterable 都必须复用这条 contract 管线；差异只能体现在 classifier 产出的 contract 内容，不得表现为多套互不相干的 `for` CFG / lowering 分支。
- 第一版即使还不实现 `Array` / `Dictionary` / `Object` iterable，也必须把 `FrontendForRegion`、CFG item 与 lowering processor 命名设计为通用 `for-loop` 语义，而不是把 AST 级结构永久绑定到 `for-range` 专名。

第一版不需要把这个 contract 抽成 public interface；可以先把 range-like 的 record 与 helper 留在 frontend 内部。文档与测试必须锁住“state type 与 yield type 分离”这个接口边界，保证未来新增 `GdCompilerType` 子类型时不会把 compiler-only state 泄漏到 ordinary type 系统。

## 4. 分阶段实施步骤

### 阶段 A：parse / AST 基线测试

目标：

- 确认外部 parser 对目标语法形态已经稳定产出 `ForStatement`。
- 锁住 `iterator`、`iteratorType`、`iterable`、`body` 的 AST shape。

实施内容：

- 增加或扩展 parse smoke / scope 测试，覆盖 `for i in range(3):`。
- 增加或扩展 parse smoke / scope 测试，覆盖 `for i in 3:` 与 `for i in limit:`，确认 `iterable()` 保持原始 expression shape，而不是被 frontend 伪装成 `range(...)` call。
- 覆盖 `for i: int in range(1, 3, 1):`，确认 `iteratorType()` 挂在 `ForStatement` 上，`iterable()` 是 `CallExpression`。
- 不修改 `GdScriptParserService`，除非 parser diagnostic mapping 本身有问题。

验收细则：

- `ForStatement.iterator()` 返回源码 iterator name。
- `ForStatement.iteratorType()` 对 typed iterator 非空，对 untyped iterator 为空。
- `ForStatement.iterable()` 是 bare `range(...)` call。
- `for i in 3:` / `for i in limit:` 的 `ForStatement.iterable()` 保持原始 literal / identifier / expression 形态，不被 rewrite。
- `ForStatement.body()` 已由 `FrontendScopeAnalyzer` 发布 `BlockScopeKind.FOR_BODY`。

### 阶段 B：range loop classifier 与诊断边界

目标：

- 建立单一 for-range 判定入口。
- 非 range-like `for` 保持 fail-closed，不因 `FOR_BODY` scope 已存在而误进入普通 executable body。

实施内容：

- 新增 `FrontendForLoopSupport` 或等价 helper。
- 提供 `tryClassifyRangeLoop(ForStatement)` / `isSupportedRangeLoop(ForStatement)` 这类简洁 API；它们虽然先返回 range-like contract，但名字与返回值形状要允许未来对象迭代扩展。
- 对 arity 非 1/2/3 的 `range(...)` 发 frontend diagnostic。
- 对 `range(..., 0)` 的 literal zero step 发 frontend diagnostic；非 literal step 不能静态确定为零时允许进入 lowering，由 runtime helper 保留最终保护。
- 对 `for i in <int-expr>` 识别为 supported range-like loop，并通过 contract 记下 `INT_SHORTHAND` source form。
- 对 `for i in <float-expr>` 与其它非 bare `range(...)`、非 `int` 简写的 `for` 不发新的 range-specific error，继续沿用现有 deferred / unsupported boundary。

验收细则：

- `for i in range():` 与 `for i in range(1, 2, 3, 4):` 有清晰 diagnostic。
- `for i in 3:`、`for i in limit:` 在 typed fact 为 lowering-ready `int` 时被识别为 supported range-like loop。
- `for i in values:` 仍被归类为非 range-like `for`，保留 `FOR_SUBTREE` deferred / unsupported 行为。
- `for i in 2.2:`、`for i in some_float:` 当前仍保留旧 deferred / unsupported 行为，不误走 `int` 简写捷径。
- `for i in obj.range(3):`、`for i in some_range(3):` 不被误识别为 supported for-range。
- classifier 不读取源码文本，不依赖 `range` identifier 的 source slice。

### 阶段 C：variable inventory 与 visible value 解封

目标：

- 为 supported for-range 发布 loop iterator binding。
- 只对 supported for-range body 发布 callable-local inventory。
- 非 range `for` body 继续是 `FOR_SUBTREE` deferred domain。

实施内容：

- 不要直接把 `BlockScopeKind.FOR_BODY` 加进 `FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(BlockScopeKind)` 的无条件 true 列表。
- 为 `FOR_BODY` 引入 AST-aware 支持判定：只有能从 use-site / body block 找到 owning `ForStatement`，且该 statement 是 supported for-range，才视为可发布 inventory。
- `FrontendVariableAnalyzer.handleForStatement(...)` 对 supported for-range 执行：
  - 在 `forStatement.body()` 的 `BlockScope` 中定义 iterator local。
  - iterator name 来自 `forStatement.iterator()`。
  - declared iterator type 为空时使用 `GdIntType.INT`。
  - declared iterator type 非空时通过既有 declared-type resolver 解析，再用 existing typed-boundary helper 检查 `int -> declaredType` 是否允许。
  - iterator binding 与 body local 按现有 duplicate / shadowing 规则处理。
  - 然后遍历 body，发布 body 内普通 local `var`。
- `FrontendVisibleValueResolver` 对 supported for-range body 返回正常 executable lookup，对非 range `FOR_BODY` 继续返回 `DEFERRED_UNSUPPORTED + FOR_SUBTREE`。

验收细则：

- `for i in range(3): print(i)` 中 `i` 在 body 内可解析为 `LOCAL`，type 为 `int`。
- `i` 在 loop 后不可见。
- body 内 `var local := i` 正常发布为 `FOR_BODY` local。
- `for i in values:` 的 body 内 lookup 仍返回 `DEFERRED_UNSUPPORTED + FOR_SUBTREE`。
- 同一 callable 内 iterator name 与已有 parameter/local 冲突时按现有 local conflict 规则报错。
- 非 range `for` 的旧 unsupported 测试继续存在，只把 supported range case 从旧断言中拆出。

### 阶段 D：top-binding、chain-binding、expr-type、local stabilization、slot type 与 type-check

目标：

- 让 supported for-range 的 iterable arguments 与 body 进入正常 shared semantic facts。
- 仍不把 `range(...)` root 当 ordinary call。
- 让 loop iterator slot type 稳定为 element type。

实施内容：

- `FrontendTopBindingAnalyzer.handleForStatement(...)`：
  - supported for-range：分析 `range(...)` arguments 或 `int` 简写 iterable expression 与 body。
  - 非 range：继续 `reportDeferredSubtree(..., FOR_SUBTREE)`。
- `FrontendChainBindingAnalyzer.handleForStatement(...)`：
  - supported for-range：分析 range arguments 或 `int` 简写 iterable expression 与 body。
  - 不为 `range(...)` root 发布 ordinary `resolvedCalls()`。
- `FrontendExprTypeAnalyzer.handleForStatement(...)`：
  - supported for-range：发布 range arguments 或 `int` 简写 iterable expression 的 expression types，遍历 body。
  - 不为 iterator state 发布 ordinary expression type。
- `FrontendLocalTypeStabilizationAnalyzer`：
  - supported for-range：进入 body，使 body 内 `:=` local 能从 iterator / arguments / body expressions 稳定。
- `FrontendVarTypePostAnalyzer`：
  - supported for-range：为 iterator binding 与 body locals 发布 final `slotTypes()`。
  - iterator slot type 是 source-facing element type，不是 compiler-only iterator state type。
- `FrontendTypeCheckAnalyzer`：
- 检查 range arguments 必须是 `int` 或通过现有 ordinary boundary 可安全进入 `int` slot。
- 检查 `int` 简写 iterable expression 也必须是 `int` 或通过现有 ordinary boundary 可安全进入 `int` stop slot。
- 检查 explicit iterator type 能接收 `int` element。
- 不新增一套 parallel conversion matrix；必须复用现有 compatibility helper。

验收细则：

- `range(start, end, step)` 的每个 argument 都有 lowering-ready expression type。
- `for i in limit:` 的 `limit` expression 在进入 CFG 前也已有 lowering-ready expression type。
- `range(...)` call root 不出现在 ordinary `resolvedCalls()` 成功路径中。
- `for i: float in range(3):` 若现有 matrix 允许 `int -> float`，则 body 中 `i` 为 `float`；若不允许则发 `sema.type_check` / `sema.type_hint` 对应 owner 的诊断。
- `for i in 2.2:` 当前仍不静默进入 supported path。
- `for i: String in range(3):` 不能静默放行。
- `GdccForRangeIterType` 不出现在 ordinary `expressionTypes()` 与 user-facing `slotTypes()` 中。

### 阶段 E：compile gate 分阶段解封

目标：

- 只在 shared semantic、CFG、body lowering 和 backend 消费都准备好之后，才让 supported for-range 通过 `analyzeForCompile(...)`。

实施内容：

- 在 CFG/body lowering 完成前，`FrontendCompileCheckAnalyzer.handleForStatement(...)` 继续跳过 `for`，沿用 upstream unsupported owner。
- CFG/body lowering 完成后：
  - supported for-range 加入 compile surface。
  - mark `ForStatement`、range arguments、body block、body statements 为 compile surface。
  - 非 range `for` 继续跳过，不把 deferred subtree 打平成 generic compile blocker。
- 同步更新：
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_lowering_plan.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_gdcompiler_type_implementation.md` 如 iterator contract 有新增事实
  - `diagnostic_manager.md` 如新增 category

验收细则：

- supported for-range 在无 error 时可通过 `analyzeForCompile(...)`。
- 非 range `for` 在 compile mode 仍被阻断，且 diagnostic owner 仍是 upstream semantic boundary，不被 compile gate 重新包装。
- compile gate 没有绕过 upstream `sema.loop_control_flow`、type-check 或 variable-binding error。

### 阶段 F：frontend CFG graph

目标：

- 在 `FrontendCfgGraphBuilder` 中为 supported for-range 建立显式 CFG。
- `break` 跳到 loop exit。
- `continue` 跳到 iterator update，再回 condition。

实施内容：

- 新增 `FrontendForRegion`，不要复用 `FrontendWhileRegion`。
- `FrontendCfgRegion` sealed permits 增加 `FrontendForRegion`。
- `FrontendCfgGraphBuilder.processStatement(...)` 增加 `case ForStatement forStatement -> processForStatement(...)`。
- `processForStatement(...)` 只接受 supported for-range；非 range `for` 到达这里必须 fail-fast。
- CFG shape 建议：
  - `range(...)` arguments 或 `int` 简写 iterable expression 在进入 loop 前按 source order 计算。
  - init sequence 调用 contract 指定的 `init` operation，第一版仍是 `gdcc.for_range_iter.init`，并产出 hidden iterator state slot。
  - condition entry 调用 `gdcc.for_range_iter.should_continue`，产出 bool condition value。
  - body entry 先调用 `gdcc.for_range_iter.get` 写入 source-facing iterator slot，再执行 body statements。
  - update entry 调用 `gdcc.for_range_iter.next` 更新 iterator state，再跳回 condition entry。
  - exit sequence 是 loop 后续 continuation。
- `INT_SHORTHAND` source form 不生成伪造 `range(stop)` AST；它只在 contract 层带一个 `stop` operand，init item/lowering processor 负责按 `(0, stop, 1)` 解释。
- active loop frame 对 for-range 应使用：
  - `breakTargetId = exitId`
  - `continueTargetId = updateEntryId`
- hidden iterator state value id / slot id 必须稳定命名，建议沿用现有 `cfg_tmp_<valueId>` 风格或用明确前缀，例如 `cfg_for_iter_<n>`；一旦选择，测试锁定。

验收细则：

- CFG regions 中 `ForStatement` 映射到 `FrontendForRegion`。
- `FrontendForRegion` 至少暴露 `initEntryId`、`conditionEntryId`、`bodyEntryId`、`updateEntryId`、`exitId`。
- `continue` 的 target 是 update entry，不是 condition entry。
- `break` 的 target 是 exit。
- `range(...)` arguments 或 `int` 简写 stop operand 的 value ids 在 init item 前已经发布。
- condition branch 的 condition value type 是 `bool`，不会触发 compiler-only condition normalization。
- unreachable body 后续 statement 处理仍遵守现有 reachability 规则。

### 阶段 G：CFG item 与 body LIR lowering

目标：

- 把 range iterator state 与四个 intrinsic materialize 为 LIR。
- 不重新扫描 AST，不重新推导 sema facts。

实施内容：

- 在 `frontend.lowering.cfg.item` 增加最小 dedicated item，建议保持 narrow 且使用面向未来的通用命名：
  - `ForLoopInitItem`
  - `ForLoopShouldContinueItem`
  - `ForLoopGetItem`
  - `ForLoopNextItem`
- 这些 item 都实现现有 `ValueOpItem`，并持有：
  - AST anchor
  - operand value ids
  - result value id
  - iterator state type / element type / lowering operation 必要信息
- body lowering 在 `FrontendSequenceItemInsnLoweringProcessors` 中增加对应 processor。
- processor 生成 `CallIntrinsicInsn`；第一版 range-like contract 对应：
  - init：`gdcc.for_range_iter.init`
  - should_continue：`gdcc.for_range_iter.should_continue`
  - get：`gdcc.for_range_iter.get`
  - next：`gdcc.for_range_iter.next`
- `FrontendBodyLoweringSession` 必须能为 hidden iterator state 声明 `compiler::GdccForRangeIter` local，并沿用 `GdCompilerType` storage/lifecycle 合同。
- 不通过 `PackVariantInsn` / `UnpackVariantInsn` materialize iterator state。

验收细则：

- LIR function variables 包含 hidden `compiler::GdccForRangeIter` local。
- range init / should_continue / get / next 以 `CallIntrinsicInsn` 出现，参数顺序符合 `doc/gdcc_lir_intrinsic.md`。
- `INT_SHORTHAND` source form 经由同一组 intrinsic 降低，不新增第二套数值简写专用 intrinsic。
- source-facing loop variable slot 是 `int` 或 declared compatible type。
- generated C 使用 `gdcc_for_range_iter_*` helper，不出现 `godot_GdccForRangeIter`、`godot_new_GdccForRangeIter...`、`Variant` pack/unpack 相关路径。
- `step == 0` literal 在 frontend 阶段阻断；动态零 step 仍由 runtime helper 防止无限循环。

## 5. 验收测试清单

### Parser / scope

- `for i in range(3): pass` 解析为 `ForStatement`。
- `for i in 3: pass` 与 `for i in limit: pass` 解析为 `ForStatement`，且 iterable 保持原始 expression 形态。
- `for i: int in range(1, 3, 1): pass` 解析并保留 `iteratorType`。
- `FrontendScopeAnalyzerTest` 继续断言 `iteratorType` / `iterable` 在外层 scope，`body` 在 `FOR_BODY`。

### Shared semantic

- `for i in range(3): var x := i` 成功发布 `i` 与 `x`。
- `for i in 3: var x := i` 与 `for i in limit: var x := i` 在 `limit` 已稳定为 `int` 时也成功发布 `i` 与 `x`。
- `i` 在 loop 后不可见。
- `for i in values:` 继续是 `FOR_SUBTREE` deferred / unsupported。
- `for i in 2.2:` 继续是 deferred / unsupported，直到 follow-up 明确 `ceil` 语义。
- `break` / `continue` 在 for-range body 内合法。
- `break` / `continue` 在 loop 外仍报 `sema.loop_control_flow`。
- lambda / nested callable 内的 loop-control 仍按 callable boundary 重新判定。
- `range()`、`range(1, 2, 3, 4)`、literal `range(1, 2, 0)` 均有明确 diagnostic。
- `for i: String in range(3): pass` 不静默通过 type-check。

### Compile gate

- supported for-range 在 `analyzeForCompile(...)` 无 error 时进入 lowering。
- `for i in <int-expr>` 这类 supported range-like loop 在无 error 时也进入 lowering。
- 非 range `for` 仍不能进入 lowering。
- compile gate 不为已有 upstream semantic error 追加同级 generic `sema.compile_check` 噪声。

### CFG

- `FrontendCfgGraphBuilderTest` 覆盖 for-range region shape。
- `FrontendCfgGraphBuilderTest` 覆盖 `INT_SHORTHAND` source form 与 `range(...)` source form 共用同一 `FrontendForRegion` shape。
- `continue` target 为 update entry。
- `break` target 为 exit。
- nested `if` 中的 `continue` / `break` 能正确连边。
- 空 range body、只有 `pass` 的 body、body 内 `return` 的 reachable/fallthrough 行为均有断言。

### Body lowering / LIR / backend

- `FrontendLoweringBuildCfgPassTest` 覆盖 function context 中发布 `FrontendForRegion`。
- `FrontendLoweringBodyInsnPassTest` 覆盖 range intrinsic instruction sequence，并锁住 `INT_SHORTHAND` 仍走相同 intrinsic 路线。
- `DomLirParserTest` / `DomLirSerializerTest` 继续覆盖 `compiler::GdccForRangeIter` local round-trip。
- `GdccForRangeIterTypeTest` / `GdCompilerTypeTest` 继续覆盖 compiler-only type contract。
- `GdccForRangeIterIntrinsicTest` / `CallIntrinsicInsnGenTest` 继续覆盖 intrinsic C generation。
- 如 test-suite 已具备可运行 GDScript fixture，再增加 `range(3)`、`range(1, 3)`、`range(2, 8, 2)`、`range(8, 2, -2)` 的 runtime output 锚点。

## 6. 建议 targeted test 命令

开发时按阶段运行，不要一开始全量跑：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendParseSmokeTest,FrontendScopeAnalyzerTest,FrontendLoopControlFlowAnalyzerTest
```

```bash
script/run-gradle-targeted-tests.sh --tests FrontendVariableAnalyzerTest,FrontendVisibleValueResolverTest,FrontendTopBindingAnalyzerTest,FrontendTypeCheckAnalyzerTest
```

```bash
script/run-gradle-targeted-tests.sh --tests FrontendCfgGraphBuilderTest,FrontendLoweringBuildCfgPassTest,FrontendLoweringBodyInsnPassTest
```

```bash
script/run-gradle-targeted-tests.sh --tests GdCompilerTypeTest,GdccForRangeIterTypeTest,DomLirParserTest,DomLirSerializerTest,GdccForRangeIterIntrinsicTest,CallIntrinsicInsnGenTest
```

阶段完成后再考虑：

```bash
./gradlew classes --no-daemon --info --console=plain
```

## 7. 风险与未定点

- `FOR_BODY` 支持不能只靠 `BlockScopeKind` 判定，必须关联 owning `ForStatement` 是否为 supported for-range；否则会把未来 generic iterable `for` 误放行。
- `range(...)` root 是否发布 expression type 必须统一。当前计划是不发布 ordinary root type，只发布 arguments 与 loop iterator binding，避免把 range 当作 user-facing `Array[int]` 或 builtin call。
- `int` 简写不允许通过 AST rewrite 伪装成 `range(stop)` call；否则 parser/scope/diagnostic anchor、future object-iterator classifier 与测试都会被污染。
- explicit iterator type 的兼容规则必须复用 ordinary typed-boundary helper；不要为 `for` 私下硬编码 `int -> T` 特例。
- `continue` 对 for-range 的目标不是 condition entry，而是 update entry。这一点和 `while` 不同，不能复用 `FrontendWhileRegion`。
- literal `step == 0` 可以前端诊断；动态零 step 不能静态证明时仍需要 backend runtime helper 保护。
- `for i in <int-expr>` 与 `range(...)` 现在就应走同一 contract；未来若接入 `float` 简写或 `Object._iter_*` / container iterable，也必须在 classifier 层扩充 contract，而不是新增第二套 CFG / lowering 基础设施。

## 8. 完成定义

本计划完成后，必须同时满足：

1. `for i in range(...)` 与 `for i in <int-expr>` 在 shared semantic 中都不再触发 `FOR_SUBTREE` unsupported boundary。
2. 非 range-like `for` 仍保持 deferred / unsupported，不进入 compile-ready surface。
3. loop iterator 在 body 内是 source-facing local，类型为 `int` 或显式兼容类型。
4. compiler-only `GdccForRangeIterType` 只出现在 hidden LIR local / intrinsic operand-result / backend C storage 路径。
5. CFG 中存在独立 `FrontendForRegion`，且 `continue` / `break` 连边正确；同一 region/item/processor 基础设施能承载未来对象迭代扩展。
6. Body lowering 生成 contract 指定的 intrinsic；第一版 range-like loop 仍生成 `gdcc.for_range_iter.*`，且不重扫 AST、不重做 semantic 推导。
7. 文档、正反测试、targeted tests 与 compile check 全部同步。
