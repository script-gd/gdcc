# Frontend for-in loop 实施计划

> 本文档记录 `for iterator[: Type] in expr` 的分阶段实施。阶段 B/D0 已把所有 `for-in` body 转为 frontend shared semantic 正式支持面：body entry 不依赖 range-like classifier，iterator 先以保守 source-facing type 进入 lexical inventory；后续 `SuiteResolver` iteration-planning 阶段再发布 `FrontendForIterationPlan`，并在可静态确定 element type 时精化 iterator slot。lowering 最终根据 iteration plan 选择 `range(...)` / known iterable 专用 helper或 generic Variant iterator helper。

## 文档状态

- 状态：实施中（阶段 B 与 D0 已完成；阶段 C/D1 及后续 route、CFG、lowering 尚未实施）
- 创建日期：2026-07-03
- 最近校对：2026-07-20（阶段 B/D0 已落地；all for-in shared semantic 已解封，compile-only 仍由临时 root blocker 阻断）
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/**`
  - `src/main/java/gd/script/gdcc/type/**`
  - `src/main/c/codegen/**`
  - `src/test/java/gd/script/gdcc/frontend/**`
  - `src/test/java/gd/script/gdcc/backend/**`
  - `src/test/java/gd/script/gdcc/type/**`
- 关联事实源：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_segmented_type_resolution_pipeline_plan.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_plan.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_func_pre_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_gdcompiler_type_implementation.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/module_impl/backend/typed_array_abi_contract.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_lir_intrinsic.md`
  - `doc/gdcc_runtime_lib.md`
- 外部语义参考：
  - Godot docs `tutorials/scripting/gdscript/gdscript_basics.rst` 的 `for` 语义说明
  - Godot source `modules/gdscript/gdscript_byte_codegen.cpp` 的 `write_for(...)`
  - Godot source `modules/gdscript/gdscript_vm.cpp` 的 `OPCODE_ITERATE*`
  - Godot source `core/variant/variant_setget.cpp` 的 `Variant::iter_init` / `iter_next` / `iter_get`
  - Godot GDExtension API `variant_iter_init` / `variant_iter_next` / `variant_iter_get`

## 1. 范围与非目标

本计划把 `for-in` 拆成两个互不混淆的支持面：

- shared semantic 支持面：所有 `for iterator[: Type] in expr` 都是 supported body statement。
- compile / lowering 支持面：最终所有 `for-in` 都通过 iteration plan 进入 lowering；实现上可以先接通 range route，再接通 generic Variant route，再追加 known iterable 专用 route。

shared semantic 第一轮必须支持：

- `for i in range(stop):`
- `for i in range(start, end):`
- `for i in range(start, end, step):`
- `for i in 3:`、`for i in limit:`、`for i in values:`、`for i in some_object:` 等任意 iterable expression 形态。
- `for i: Type in expr:` 显式 iterator type。
- body 内 iterator local lookup、body local `var` inventory、source-order local stabilization、`break` / `continue` legality。

compile / lowering 最终目标必须覆盖：

- `range(...)` 专用 route，复用现有 `gdcc.for_range_iter.*` intrinsic 与 `GdccForRangeIterType`。
- 编译期可确定的 known iterable route，例如 `int` 数值简写、`String`、`Array`、`Dictionary`、packed array family。每个 route 是否第一轮落地由对应 helper 与 ABI 准备度决定。
- 编译期不能确定 iterable 类型时的 generic Variant iterator route，运行时通过 Godot Variant iteration API 或 gdcc runtime wrapper 分派。

本轮明确不做的事情：

- 不把 `range(...)` 当作 ordinary user-facing callable route；它在 `for` iterable 位置仍是 loop-specific form，不向普通 `resolvedCalls()` 发布 utility function route。
- 不把 `GdccForRangeIterType` 或未来 compiler-only iterator state type 发布到 ordinary `expressionTypes()`、source-facing `slotTypes()`、declared type parser、public ABI、`Variant` pack/unpack 或普通 Godot runtime 调用路径。
- 不通过 AST rewrite 把 `for i in limit:`、`for i in 3:` 伪造成 `range(...)` call。
- 不在 frontend semantic 阶段强制证明任意 `expr` 一定可迭代；无法静态确定时保留 `Variant` iterator type，并让 generic runtime helper 处理 Godot 运行时语义。
- 不在第一批 known-route 中强行复刻所有 Godot 特例。`float` 数值简写、`Vector2` / `Vector3` 迭代、Object `_iter_*` 专用优化可以在 generic route 之后逐步转成专用 route。
- 不恢复已删除的 whole-module body analyzers；所有 typed fact 仍必须通过 `SuiteResolver`、statement-local owner procedures 与 per-owner patch transaction 发布。

## 2. 当前基线

当前代码库已经具备的基础：

- `gdparser` 的 `ForStatement` AST 已包含 `iterator`、`iteratorType`、`iterable`、`body`、`range` 字段。
- `GdScriptParserService` 只是外部 parser adapter，本仓库没有本地 parser grammar 可改；若 parse smoke 发现 `for` AST 形态不足，应先升级或修复外部 parser，而不是在 frontend analyzer 中猜文本。
- `FrontendScopeAnalyzer` 已为 `ForStatement` 建立 `FOR_BODY` scope，并保证 `iteratorType` 与 `iterable` 在外层 scope 下遍历，`body` 在独立 `FOR_BODY` scope 下遍历。
- `FrontendLoopControlFlowAnalyzer` 已把 `for` 与 `while` 一样视为 loop boundary，`break` / `continue` 在 `for` body 内不再报 `sema.loop_control_flow`。
- `FrontendSemanticAnalyzer` 的 shared phase 顺序固定为 skeleton -> scope -> variable inventory -> interface surface -> `SuiteResolver` -> diagnostics-only analyzers。`SuiteResolver` 是逐语句运行的 typed fact owner，能在 `var limit := 3` flush 后让后续 `for i in limit:` 读取 `limit:int`。
- `FrontendTypedLexicalEnvironment` 已支持 pending / committed overlay、per-owner patch transaction、`Variant -> exact` 的 local slot update 校验；但当前 API 只允许 `LOCAL_TYPE_STABILIZATION` owner 发布 local slot update。
- `FrontendVariableAnalyzer` 已无条件发布 iterator 与 for body ordinary local inventory；`FrontendInterfacePhase` 已发布 iterator declaration index、typed baseline 与 suite entry；`FrontendStatementResolver` 已通过 header-only statement boundary 进入普通 child-suite path；`FrontendVisibleValueResolver` 已允许 for header/body ordinary lookup。
- `FrontendBodyLocalDeclaration` 与 `FrontendBodyDeclarationIndex` 已支持 `Node` declaration identity，并以 `ForStatement` 作为 `ITERATOR` entry identity；ordinary local 继续使用 `VariableDeclaration` identity。
- `FrontendCompileCheckAnalyzer` 当前对每个 `ForStatement` root 发布一个临时、无条件的 `sema.compile_check` blocker，不读取 iterable typed fact、iteration plan 或 route，也不进入 body。
- `FrontendCfgGraphBuilder.processStatement(...)` 当前没有 `ForStatement` 分支；`FrontendCfgRegion` 当前只允许 `BlockRegion`、`FrontendIfRegion`、`FrontendElifRegion`、`FrontendWhileRegion`。
- `GdccForRangeIterType.FOR_RANGE_ITER` 已作为 compiler-only `GdCompilerType` 子类型存在。
- `doc/gdcc_lir_intrinsic.md` 已冻结四个 backend-owned range intrinsic：`gdcc.for_range_iter.init`、`gdcc.for_range_iter.should_continue`、`gdcc.for_range_iter.next`、`gdcc.for_range_iter.get`；C backend 与 runtime helper 已有对应实现。
- generic Variant iterator helper、typed container iterator helper、Object `_iter_*` helper 在本仓库 runtime / intrinsic 层尚未实现。

当前实现张力：

- 早期文档曾把 `for` 放在 post-MVP / deferred 范围；阶段 B/D0/L 完成后，shared semantic 文档已改为结构支持，compile/lowering 仍分阶段开放。
- loop-control semantic 已把 `for` 当成合法 loop boundary。
- backend/LIR 已具备 range iterator state 与 intrinsic。
- frontend shared semantic、compile gate、CFG 与 body lowering 尚未承认 `for-in` 为 supported surface。

本计划的实施目标就是把这个张力收口为：“所有 `for-in` 在 shared semantic 中都是 supported body；iteration plan 决定 iterator type refinement 与 lowering route；compile surface 按 route helper 准备度分阶段打开”。

## 3. 核心设计

### 3.1 supported body 与 route classification 解耦

`for` body 是否进入 shared semantic 不再依赖 iterable 的最终 typed fact。早期 inventory 阶段无条件发布 for body inventory：

- iterator binding 先进入 `FOR_BODY` scope。
- body 内 ordinary local `var` 按完整 lexical inventory 模型发布。
- `FrontendInterfacePhase` 为 for body 建立 body declaration index 与 typed baseline。
- `FrontendVisibleValueResolver` 不再把 `FOR_BODY` 固定判定为 `FOR_SUBTREE` deferred boundary。

iterable typed fact 只影响两个后续事实：

- iterator 的 source-facing effective slot type 是否能从 `Variant` 精化为 exact element type。
- lowering 使用哪个 `FrontendForIterationPlan` route。

示例：

```gdscript
func f():
    var limit := 3
    for i in limit:
        var x := i + 1
```

正确顺序是：

1. Variable inventory 发布 `limit` 与 `i`。`i` 的 baseline 是 `Variant`，或显式 iterator type。
2. `SuiteResolver` 解析 `var limit := 3`，把 `limit` 稳定为 `int` 并 flush 到 current-suite overlay。
3. `SuiteResolver` 解析 for header `limit`，构造 `FrontendForIterationPlan(INT_RANGE_SHORTHAND)`。
4. iteration planning owner 把 `i` 从 `Variant` 精化为 `int`。
5. child body resolver 进入 body，`var x := i + 1` 读取到 `i:int`。

反例也必须成立：

```gdscript
func f(values):
    for item in values:
        print(item)
```

如果 `values` 没有静态 element type，body 仍正常解析；`item` 保持 `Variant`，iteration plan 是 `GENERIC_VARIANT`，lowering 走 runtime helper。

### 3.2 iterator declaration identity

Iterator 不是 ordinary `VariableDeclaration`，不能伪造 AST local declaration。计划采用 `ForStatement` 自身作为 iterator declaration identity：

- `BlockScope.defineLocal(iteratorName, baselineType, forStatement)` 发布 iterator binding。
- `ScopeValue.kind()` 使用 `LOCAL`，使 assignment、lookup、slot update 与普通 local 共用路径。
- `ScopeValue.declaration()` 必须严格等于 owning `ForStatement`，以便 local slot update 的 declaration identity guard 生效。
- iterator 在 body 中从第一条 statement 开始可见，在 loop 后不可见。
- iterator 和 body ordinary local 使用同一个 duplicate / shadowing 规则，不能与同 callable 内已有 parameter/local 静默重名。

因此需要把 body inventory index 从“ordinary `VariableDeclaration` index”扩展为“body local inventory index”：

```java
enum FrontendBodyLocalDeclarationKind {
        ITERATOR,
        ORDINARY_VAR
}

record FrontendBodyLocalDeclaration(
        @NotNull Node declaration,
        @NotNull ScopeValue binding,
        @NotNull FrontendBodyLocalDeclarationKind kind,
        int sourceOrder
) {}
```

约束：

- `ORDINARY_VAR` 的 `declaration` 仍必须是 `VariableDeclaration`，`sourceOrder >= 0`。
- `ITERATOR` 的 `declaration` 必须是 owning `ForStatement`，`sourceOrder` 使用固定 sentinel，例如 `-1`，表示在 body 所有 ordinary statement 之前可见。
- `FrontendBodyDeclarationIndex` 必须支持按 `Object` / `Node` declaration identity 查询，而不是只接受 `VariableDeclaration`。
- `FrontendVisibleValueResolver` 的 published inventory guard 必须覆盖 iterator local，不能让 iterator 成为绕过 declaration index 的 side channel。

### 3.3 iterator baseline 与 slot refinement

无显式 iterator type 时，iterator baseline 一律是 `GdVariantType.VARIANT`。这样才能复用现有 local slot “从保守类型到 exact type”的模型。

显式 iterator type 时，baseline 是 declared type：

```gdscript
for i: float in range(3):
    print(i)
```

此时 body 中 `i` 的 source-facing type 是 `float`，iteration plan 的 raw element type 仍是 `int`。type-check 阶段必须验证 raw element 可以进入 explicit iterator type；lowering 必要时插入 per-element conversion。

无显式 type 时：

- `range(...)`、`int` 数值简写 -> raw element type 为 `int`，iterator 可精化为 `int`。
- typed `Array[T]` -> raw element type 为 `T`，iterator 可精化为 `T`。
- typed `Dictionary[K, V]` -> Godot 语义迭代 key，raw element type 为 `K`。
- plain `Dictionary`、unknown `Variant`、Object custom iterator -> raw element type 为 `Variant`，iterator 保持 `Variant`。

当前 `FrontendTypedLexicalEnvironment.addLocalSlotTypeUpdate(...)` 只允许 `LOCAL_TYPE_STABILIZATION` owner 写 local slot update，并拒绝 exact -> exact 改写。for iteration planning 需要显式扩展：

- 新增 `FrontendSemanticStage.FOR_ITERATION_PLANNING` 或等价 stage。
- 新增 `FrontendForIterationPlanningPatch`，或允许该 stage 发布受限的 `FrontendLocalSlotTypeUpdate`。
- 校验规则仍必须保持：只允许当前 effective local 是 `Variant` 时精化为 exact type；显式 exact iterator type 不得被自动改写为另一个 exact type。
- patch transaction 顺序扩展为 top binding -> local stabilization -> chain binding -> expr typing -> for iteration planning -> var type post。

### 3.4 `FrontendForIterationPlan`

新增 stable frontend fact，key 为 `ForStatement`。建议命名为 `FrontendForIterationPlan`，由 SuiteResolver header owner 发布，供 type-check、compile gate、CFG builder 与 lowering 消费。

建议形状：

```java
enum FrontendForIterationRoute {
        RANGE_CALL,
        INT_SHORTHAND,
        FLOAT_SHORTHAND,
        STRING,
        ARRAY,
        DICTIONARY_KEYS,
        PACKED_ARRAY,
        OBJECT_CUSTOM,
        GENERIC_VARIANT
}

record FrontendForIterationPlan(
        @NotNull ForStatement statement,
        @NotNull FrontendForIterationRoute route,
        @NotNull String iteratorName,
        @Nullable TypeRef declaredIteratorType,
        @NotNull GdType rawElementType,
        @NotNull GdType exposedIteratorType,
        boolean requiresPerElementConversion,
        @NotNull List<Expression> sourceOperands,
        @Nullable GdCompilerType iteratorStateType,
        @NotNull List<String> operationNames
) {}
```

约束：

- `rawElementType` 是 runtime helper / intrinsic 产出的元素类型。
- `exposedIteratorType` 是 body 中 iterator local 的 source-facing type。
- `iteratorStateType` 只允许出现在该 dedicated iteration plan 与 lowering-internal state，不得进入 ordinary expression / slot / binding tables。
- `operationNames` 由 route contract 提供，consumer 不得在 CFG builder / lowering processor 中散落硬编码 intrinsic 名称。
- `sourceOperands` 保留源码 expression，不伪造 AST。

`range(...)` route 第一版使用已有 contract：

- `iteratorStateType = GdccForRangeIterType.FOR_RANGE_ITER`
- `rawElementType = GdIntType.INT`
- `operationNames = gdcc.for_range_iter.init / should_continue / next / get`

generic Variant route 需要新增 contract：

- `iteratorStateType` 使用新的 compiler-only generic iterator state type，或由 LIR item 隐藏为 backend-owned storage。
- `rawElementType = GdVariantType.VARIANT`
- `operationNames` 使用新 intrinsic，例如 `gdcc.for_variant_iter.init / should_continue / next / get`，具体名称在 intrinsic catalog 阶段冻结。

### 3.5 `range(...)` 与 ordinary call route 隔离

`range(...)` 在 `for` iterable 位置是 loop-specific syntax form：

- bare `IdentifierExpression("range")` + 1/2/3 positional arguments 才进入 `RANGE_CALL` route。
- named argument、attribute call、subscript call、`some_range(...)`、`obj.range(...)` 不进入 `RANGE_CALL` route。
- `range(...)` root 不发布 ordinary `resolvedCalls()`。
- `range(...)` arguments 必须按 source order 运行 top / chain / expr type，且能进入 `int` boundary。
- literal `step == 0` 应在 frontend 发 diagnostic；非 literal step 交给 runtime helper 的防无限循环保护。

### 3.6 Godot iteration 语义落点

外部语义参考表明 Godot 对 `for-in` 使用统一 iteration 协议：

- `int`：类似 `range(0, n, 1)`，`n <= 0` 时 0 次迭代。
- `float`：类似 `range(ceil(n))`，但 element 精确类型与 conversion 需要专门测试锁定后再做 high-performance route。
- `Array` / packed array：按 index 返回元素。
- `Dictionary`：迭代 key，不是 pair，也不是 value。
- `String`：迭代字符。
- `Object`：通过 `_iter_init` / `_iter_next` / `_iter_get` 协议。
- unknown `Variant`：通过 `Variant::iter_init` / `iter_next` / `iter_get` runtime dispatch。

GDCC 第一批 route 不必全部专用化，但必须让 `FrontendForIterationRoute` 和 tests 保留扩展位。无法静态确定或尚未实现专用 helper 的类型必须落到 `GENERIC_VARIANT`，而不是重新把 body 关回 deferred boundary。

## 4. 分阶段实施步骤

### 阶段 A：parse / AST / scope 基线测试

目标：

- 锁住外部 parser 对 `for-in` 的 AST shape。
- 确认 `FrontendScopeAnalyzer` 的 header/body scope 分层满足 all for-in 支持面。

实施内容：

- 增加或扩展 parse smoke / scope 测试，覆盖：
  - `for i in range(3):`
  - `for i in 3:`
  - `for i in limit:`
  - `for i in values:`
  - `for i: int in values:`
- 不修改 `GdScriptParserService`，除非 parser diagnostic mapping 本身有问题。
- 确认 `iteratorType` 与 `iterable` 仍在外层 scope 解析，`body` 仍在独立 `FOR_BODY` scope 下解析。

验收细则：

- `ForStatement.iterator()` 返回源码 iterator name。
- `ForStatement.iteratorType()` 对 typed iterator 非空，对 untyped iterator 为空。
- `ForStatement.iterable()` 保持原始 expression shape，不被 rewrite。
- `ForStatement.body()` 已由 `FrontendScopeAnalyzer` 发布 `BlockScopeKind.FOR_BODY`。
- nested `for` 的内层 body 也有独立 `FOR_BODY`，且 parent scope 链正确。

### 阶段 B：body inventory 与 declaration index 解封

状态：已完成（2026-07-20）。

目标：

- 把 `for` 从 shared semantic 的 unsupported boundary 中移除。
- 为所有 `for-in` 发布 iterator binding 与完整 body local inventory。
- 扩展 body declaration index，使 iterator 不绕过 published inventory guard。

实施内容：

- `FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(BlockScopeKind)` 增加 `FOR_BODY`。这是 all for-in semantic supported 的明确边界变化；`MATCH_SECTION_BODY`、`LAMBDA_BODY` 等仍不得一起打开。
- `FrontendVariableAnalyzer.handleForStatement(...)` 改为 supported path：
  - 找到 `forStatement.body()` 对应 `BlockScope`。
  - 用 `ForStatement` 作为 declaration identity 定义 iterator local。
  - 无显式 iterator type 时使用 `GdVariantType.VARIANT` baseline。
  - 有显式 iterator type 时通过既有 declared-type resolver 解析 source-facing type。
  - 使用现有 duplicate / same-callable shadow 规则检查 iterator 与参数、外层 local、同 body local 的冲突。
  - 遍历 body，发布 body 内 ordinary local `var`。
- 在移除 shared semantic 的 `ForStatement` unsupported diagnostic 前，先让
  `FrontendCompileCheckAnalyzer.handleForStatement(...)` 对所有 `for-in` 发布临时、无条件的
  statement-root `sema.compile_check` blocker：
  - blocker 只锚定 `ForStatement`，不得进入 body 重扫 semantic facts。
  - blocker 不读取 iteration plan、iterable type 或 route readiness。
  - 阶段 F 以 route-aware compile policy 替换该临时 blocker；在此之前任何 `for-in` 都不能进入
    `FrontendCfgGraphBuilder`。
- 移除 `UnsupportedVariableBoundaryReporter` 对 `ForStatement` 的 unsupported diagnostic。
- `FrontendBodyLocalDeclaration` / `FrontendBodyDeclarationIndex` 扩展为支持 iterator declaration identity：
  - declaration key 使用 `Node` 或 `Object` identity，而不是只接受 `VariableDeclaration`。
  - iterator entry kind 为 `ITERATOR`，source order 表示 body-start visible。
  - ordinary `var` entry kind 为 `ORDINARY_VAR`，继续使用 source-order visibility。
- `FrontendInterfacePhase.handleForStatement(...)` 不再注册 `FOR_SUBTREE` pending gate；它应：
  - walk `iteratorType` 与 `iterable`。
  - 记录 iterator baseline 到 `FrontendTypedLexicalBaseline`。
  - `enterSupportedBlock(forStatement.body())`，使 nested body local 与 nested for 都进入 interface surface。
- `FrontendVisibleValueResolver` 删除 for-specific body/header deferred boundary：
  - `ForStatement.body()` edge 不再返回 `FOR_SUBTREE`。
  - `ForStatement.iteratorType()` / `iterable()` header edge 不再返回 `VARIABLE_INVENTORY_NOT_PUBLISHED`。
  - `BlockScopeKind.FOR_BODY` current-scope gate 不再 fail-closed。

验收细则：

- `for i in values: print(i)` 中 `i` 在 body 内解析为 `LOCAL`，baseline type 为 `Variant`。
- `for i in range(3): print(i)` 在本阶段也至少解析为 `LOCAL`，即使还未精化为 `int`。
- `i` 在 loop 后不可见。
- body 内 `var local := i` 正常发布为 `FOR_BODY` ordinary local。
- `for i in values: var item := i` 的 `item` 出现在 body declaration index 中。
- iterator entry 出现在 body declaration index 中，且 resolver 的 published inventory guard 覆盖该 entry。
- 同一 callable 内 iterator name 与已有 parameter/local 冲突时按现有 local conflict 规则报错。
- body 内 `var i := ...` 与 iterator `i` 冲突，不能覆盖 iterator。
- `match`、lambda、block-local `const` 的旧 unsupported / deferred 行为不因 `FOR_BODY` 解封而改变。
- 默认 shared `analyze(...)` 不再为 `for-in` 产生 `FOR_SUBTREE` / variable-inventory unsupported
  diagnostic；同一 source 通过 `analyzeForCompile(...)` 时由临时 `sema.compile_check` blocker
  明确阻断，且 lowering pipeline 不进入 CFG build。

### 阶段 C：iteration plan 数据结构与 publication surface

目标：

- 建立 lowering 与 type-check 共同消费的 `FrontendForIterationPlan`。
- 建立 iteration planning owner 后续需要的数据与 publication surface，避免把 route 选择散落到 type-check / CFG / lowering。

实施内容：

- 新增 `FrontendForIterationRoute` 与 `FrontendForIterationPlan`，先覆盖：
  - `RANGE_CALL`
  - `INT_SHORTHAND`
  - `GENERIC_VARIANT`
- 为后续保留但可以暂不生成的 route：
  - `FLOAT_SHORTHAND`
  - `STRING`
  - `ARRAY`
  - `DICTIONARY_KEYS`
  - `PACKED_ARRAY`
  - `OBJECT_CUSTOM`
- 在 `FrontendAnalysisData` 或等价 stable surface 中新增 `forIterationPlans()` side table，key 为 `ForStatement`。
- 新增 patch / transaction support，使 iteration plan 通过 owner-specific patch 发布，而不是在 CFG builder 中重新推导。
- `FrontendPublishedFactTypeGuard` 增加 iteration plan guard：
  - source-facing fields 不得含 compiler-only type。
  - `iteratorStateType` 只允许在 dedicated iteration plan field 中携带。
  - operation name 必须非空，且 route 与 operation arity 合法。
- `FrontendForLoopSupport` 只做纯分类与 plan construction helper，不读源码文本，不扫描后续 statements，不直接写 scope 或 side table。

验收细则：

- `FrontendAnalysisDataTest` 覆盖 `forIterationPlans()` idempotent merge、conflict merge、compiler-only guard。
- `FrontendForLoopSupportTest` 覆盖 bare `range(...)`、非 bare `range`、`int` shorthand、unknown iterable fallback。
- `RANGE_CALL` plan 的 `sourceOperands` 保留源码 arguments。
- `INT_SHORTHAND` plan 的 `sourceOperands` 只包含 stop expression，不伪造 `0` / `1` AST。
- `GENERIC_VARIANT` plan 不携带 range iterator state type。
- route 与 operation name 不在 CFG builder / lowering processor 中重复硬编码。

### 阶段 D0：SuiteResolver header-only for path 与 baseline body entry

状态：已完成（2026-07-20）。

目标：

- 让 `SuiteResolver` 先解析 for header，再以阶段 B 已发布的 iterator baseline 进入 child body。
- 本阶段只依赖阶段 B，不依赖阶段 C 的 iteration plan 数据结构，也不做 iterator slot refinement。
- 为 segmented pipeline 阶段 L 提供“没有 typed gate 也能进入 for body”的 production path。

实施内容：

- `FrontendStatementResolver` 增加 `resolveForStatement(...)`，替换当前 `resolveUnsupportedRoot(context, forStatement)`。
- `resolveForStatement(...)` 必须是 header-only：
  - 如果 `iteratorType` 非空，解析该 type ref 相关 expression / type use-site 所需 facts。
  - 解析 `iterable` 或 `range(...)` arguments。
  - 不在 header pass 中遍历 body statements。
- header facts 在同一个 statement boundary flush；不得把 `iteratorType`、`iterable` 与 body 分别当成
  三个独立 statement flush。
- 本阶段不增加 `runForIterationPlanning(...)`，也不要求 `FrontendForIterationPlan` 已存在。
- `resolveForStatement(...)` 在 header facts flush 后调用
  `childSuiteResolver.resolveChildSuite(context, forStatement.body())`。
- child body 读取阶段 B 的 iterator baseline：无显式 type 时为 `Variant`，有显式 declared type 时为该
  source-facing type。

验收细则：

- `var limit := 3; for i in limit: var x := i` 中 header 能读取前缀 `limit:int`，但 body 中 `i` 与
  `x` 在本阶段仍可以保持 `Variant`。
- `for item in values: var x := item` 中 `item`、`x` 均进入 ordinary shared semantic，不产生
  `FOR_SUBTREE` deferred result。
- `for i: float in range(3): print(i)` 的 body 在 iteration planning 尚未实现时已经能读取 declared
  baseline `i:float`；element conversion 是否成立由后续阶段判断。
- header pass 不遍历 body，child body 只通过普通 `resolveChildSuite(...)` 进入。
- nested `for` 递归使用同一 D0 path，不依赖 gate registry 或 iteration plan。
- owner procedure 不从 `SourceFile` root 重新 walk，不恢复 legacy whole-module analyzer。

### 阶段 D1：iteration planning 与 iterator slot refinement

依赖：阶段 C 与阶段 D0。

目标：

- 在 D0 已建立的 header-first 路径中发布 iteration plan 与 iterator slot refinement。
- body resolver 必须看到 header 已提交的 iterator effective type；该精化只改变 typed fact，不改变
  body 是否进入 `SuiteResolver`。

实施内容：

- 增加 feature-specific owner hook，例如 `runForIterationPlanning(context, forStatement)`，执行位置
  固定在 header expr typing 后、iterator var type post 前。该 hook 不复用或恢复
  `runGateClassifier(...)`。
- `runForIterationPlanning(...)`：
  - 读取 `iterable` 的 effective expression / slot typed fact。
  - 调用 `FrontendForLoopSupport` 构造 plan。
  - 发布 `FrontendForIterationPlan`。
  - 若 iterator baseline 是 `Variant` 且 plan 的 `exposedIteratorType` 是 exact source-facing type，则发布 iterator slot refinement。
  - 若 iterator 有显式 declared type，不自动改写 exact type，只记录 raw element -> exposed type 的 conversion requirement。
- `runVarTypePost(...)` 扩展为支持 `ForStatement` iterator declaration：
  - 为 iterator declaration key 发布 final source-facing `slotTypes()` fact。
  - `slotTypes()` value 必须是 exposed iterator type，不能是 compiler-only state type。
- D0 的 header flush 必须发生在 planning 与 iterator var type post 之后；child body 的
  `FrontendSuiteContext` 通过 parent environment 读取 iterator slot refinement。

验收细则：

- `var limit := 3; for i in limit: var x := i + 1` 中 body resolver 看到 `i:int`。
- `for item in values: var x := item` 中 `item` 在 unknown route 下保持 `Variant`。
- `for i in range(3): var x := i + 1` 不发布 ordinary `range(...)` call route，但 `range` argument 有 expression type。
- `for i: float in range(3): print(i)` 若现有 boundary 允许 `int -> float`，body 中 `i` 是 `float`，plan 记录 per-element conversion；若不允许则发 type diagnostic。
- `for i: String in range(3): pass` 不静默通过。
- nested `for j in range(i):` 可以读取外层 `i` 的 refined type。
- 改变 iterable 的 resolved type 只能改变 plan/refinement/type diagnostic，不能改变 D0 已建立的
  inventory、declaration index、suite entry 或 child-body dispatch。

### 阶段 E：type-check 与 Godot iteration 语义

目标：

- 在不关闭 body semantic 的前提下，对可静态验证的 route 进行 type-check。
- 明确 generic route 的 runtime error / runtime dispatch 边界。

实施内容：

- `FrontendTypeCheckAnalyzer` 消费 `FrontendForIterationPlan`：
  - `RANGE_CALL` arguments 必须能进入 `int` slot。
  - `INT_SHORTHAND` stop expression 必须能进入 `int` slot。
  - literal `range(..., 0)` 发 frontend diagnostic。
  - explicit iterator type 必须能接收 raw element type；不新增 parallel conversion matrix。
  - `GENERIC_VARIANT` 不因无法静态证明 iterable 而发 unsupported diagnostic。
- `FLOAT_SHORTHAND` 第一版可以保持 generic Variant route；只有在 `ceil` 语义、element exposed type 与 C helper 都被测试锁住后，才转为专用 route。
- `DICTIONARY_KEYS` route 必须明确 iterator 是 key type。
- Object custom iterator 的 static element type 默认仍为 `Variant`，除非未来有明确 type contract。

验收细则：

- `range()` 与 `range(1, 2, 3, 4)` 有清晰 diagnostic。
- `range(1, 2, 0)` literal zero step 有清晰 diagnostic。
- `for i in values:` 不再产生 `FOR_SUBTREE` unsupported diagnostic。
- `for i in 2.2:` 在未专用化前进入 generic Variant route，shared semantic 不失败。
- typed dictionary route 测试锁定 iterator 是 key，不是 value 或 pair。
- type-check 不把 generic route 的运行时不可迭代可能性误报为 compile-time unsupported。

### 阶段 F：compile gate 分阶段解封

目标：

- shared semantic 支持与 compile-ready 支持分离。
- 只有对应 lowering helper 已准备好的 route 才能通过 `analyzeForCompile(...)`。

实施内容：

- shared semantic 阶段完成后，`FrontendCompileCheckAnalyzer.handleForStatement(...)` 用 route-aware
  policy 替换阶段 B 的临时无条件 blocker，并按 route 分阶段阻断 compile：
  - range route 已有 intrinsic/backend/runtime，可以优先解封。
  - generic Variant route 必须等 LIR intrinsic、runtime helper、C backend codegen 与 tests 全部完成后再解封。
  - known iterable 专用 route 必须等对应 helper 准备好后再解封；否则先降级到 generic Variant route。
- compile gate 解封时必须 mark：
  - `ForStatement`
  - iterator declaration key
  - iterable / range arguments
  - body block 与 body statements
  - iteration plan fact
- compile gate 不能重新包装 upstream semantic/type-check error。
- 同步更新：
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_lowering_plan.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_gdcompiler_type_implementation.md` 如新增 compiler-only iterator state
  - `gdcc_lir_intrinsic.md` 与 `gdcc_runtime_lib.md` 如新增 generic helper

验收细则：

- shared semantic 支持的 `for i in values:` 在 generic helper 尚未实现时可以被 compile gate 明确阻断，但 diagnostic 必须说明缺少 lowering route，而不是 `FOR_SUBTREE` unsupported。
- range route 在无 semantic error 时可通过 `analyzeForCompile(...)`。
- generic Variant route 完成后，unknown iterable 的 `for-in` 可通过 compile gate。
- compile gate 不为已有 upstream semantic error 追加同级 generic `sema.compile_check` 噪声。

### 阶段 G：frontend CFG graph

目标：

- 在 `FrontendCfgGraphBuilder` 中为 `for-in` 建立显式 CFG。
- `break` 跳到 loop exit。
- `continue` 跳到 iterator update，再回 condition。

实施内容：

- 新增 `FrontendForRegion`，不要复用 `FrontendWhileRegion`。
- `FrontendCfgRegion` sealed permits 增加 `FrontendForRegion`。
- `FrontendCfgGraphBuilder.processStatement(...)` 增加 `case ForStatement forStatement -> processForStatement(...)`。
- `processForStatement(...)` 只消费已发布的 `FrontendForIterationPlan`，不得重新推导 iterable 语义。
- CFG shape 建议：
  - iterable / source operands 在进入 loop 前按 source order 计算。
  - init entry 调用 plan 指定的 init operation，产出 hidden iterator state。
  - condition entry 调用 should-continue operation，产出 bool condition value。
  - body entry 调用 get operation，写入 source-facing iterator local，再执行 body statements。
  - update entry 调用 next operation，更新 iterator state，再跳回 condition entry。
  - exit sequence 是 loop 后续 continuation。
- active loop frame 对 `for` 应使用：
  - `breakTargetId = exitId`
  - `continueTargetId = updateEntryId`
- hidden iterator state value id / slot id 必须稳定命名，建议沿用现有 `cfg_tmp_<valueId>` 风格或使用明确前缀，例如 `cfg_for_iter_<n>`；一旦选择，测试锁定。

验收细则：

- CFG regions 中 `ForStatement` 映射到 `FrontendForRegion`。
- `FrontendForRegion` 至少暴露 `initEntryId`、`conditionEntryId`、`bodyEntryId`、`updateEntryId`、`exitId`。
- `continue` 的 target 是 update entry，不是 condition entry。
- `break` 的 target 是 exit。
- `range(...)` arguments、`INT_SHORTHAND` stop operand 或 generic iterable value ids 在 init item 前已经发布。
- condition branch 的 condition value type 是 `bool`，不会触发 compiler-only condition normalization。
- nested `if` 中的 `break` / `continue` 能正确连边。
- unreachable body 后续 statement 处理仍遵守现有 reachability 规则。

### 阶段 H：range route LIR lowering

目标：

- 先接通已有 backend/runtime 已支持的 `range(...)` 与 `int` shorthand route。
- 不重新扫描 AST，不重新推导 sema facts。

实施内容：

- 在 `frontend.lowering.cfg.item` 增加最小 dedicated item，保持通用命名：
  - `ForLoopInitItem`
  - `ForLoopShouldContinueItem`
  - `ForLoopGetItem`
  - `ForLoopNextItem`
- 这些 item 都实现现有 `ValueOpItem`，并持有：
  - AST anchor
  - operand value ids
  - result value id
  - route / iterator state type / element type / lowering operation 必要信息
- body lowering 在 `FrontendSequenceItemInsnLoweringProcessors` 中增加对应 processor。
- processor 消费 `FrontendForIterationPlan` 并生成 `CallIntrinsicInsn`；range route 对应：
  - init：`gdcc.for_range_iter.init`
  - should_continue：`gdcc.for_range_iter.should_continue`
  - get：`gdcc.for_range_iter.get`
  - next：`gdcc.for_range_iter.next`
- `FrontendBodyLoweringSession` 必须能为 hidden iterator state 声明 `compiler::GdccForRangeIter` local，并沿用 `GdCompilerType` storage/lifecycle 合同。
- `INT_SHORTHAND` source form 不生成伪造 `range(stop)` AST；init item/lowering processor 负责按 `(0, stop, 1)` 解释。
- 不通过 `PackVariantInsn` / `UnpackVariantInsn` materialize range iterator state。

验收细则：

- LIR function variables 包含 hidden `compiler::GdccForRangeIter` local。
- range init / should_continue / get / next 以 `CallIntrinsicInsn` 出现，参数顺序符合 `doc/gdcc_lir_intrinsic.md`。
- `INT_SHORTHAND` source form 经由同一组 intrinsic 降低，不新增第二套数值简写专用 intrinsic。
- source-facing loop variable slot 是 `int` 或 declared compatible type。
- generated C 使用 `gdcc_for_range_iter_*` helper，不出现 `godot_GdccForRangeIter`、`godot_new_GdccForRangeIter...`、`Variant` pack/unpack 相关路径。
- `step == 0` literal 在 frontend 阶段阻断；动态零 step 仍由 runtime helper 防止无限循环。

### 阶段 I：generic Variant iterator route

目标：

- 让无法静态专用化的 `for i in expr` 进入 compile-ready surface。
- 复用 Godot Variant iteration 语义，而不是在 frontend 中枚举所有运行时类型。

实施内容：

- 在 `doc/gdcc_lir_intrinsic.md` 新增 generic iterator intrinsic catalog，暂定命名：
  - `gdcc.for_variant_iter.init`
  - `gdcc.for_variant_iter.should_continue`
  - `gdcc.for_variant_iter.next`
  - `gdcc.for_variant_iter.get`
- 新增 compiler-only iterator state type，或明确由 backend helper 持有 opaque state；无论哪种形态，都不得进入 ordinary type tables。
- 在 C runtime 中新增 helper，优先通过 GDExtension Variant iteration API：
  - `variant_iter_init`
  - `variant_iter_next`
  - `variant_iter_get`
- 如果 gdextension-lite 未暴露这些 API，应先增加薄 wrapper，再让 gdcc runtime helper 调用 wrapper。
- `GENERIC_VARIANT` route 的 `get` 返回 `Variant`；显式 iterator type 或后续 use-site 需要通过既有 Variant boundary / unpack materialization 处理。
- runtime helper 必须定义不可迭代值的错误策略，尽量贴近 Godot：运行时 fail / print error，而不是 frontend 编译期 unsupported。

验收细则：

- `for item in values:` 在 `values` 静态类型未知时 lowering 到 generic Variant route。
- generic route 的 iterator local body type 是 `Variant`，除非有显式 iterator type。
- `Dictionary` 在 generic route 下按 Godot runtime 迭代 key。
- Object custom iterator 不需要 frontend 特判；runtime route 负责调用 Godot protocol。
- generic iterator state 不出现在 ordinary `expressionTypes()` / `slotTypes()` / public ABI。
- C backend 对 helper 的 lifecycle、copy、destroy 路径有 focused tests。

### 阶段 J：known iterable 专用 route

目标：

- 在不改变 shared semantic 支持面的前提下，为可静态确定的 iterable 类型提供高性能 route。
- 每个专用 route 都必须能安全 fallback 到 generic Variant route，不能因专用 helper 未实现而重新关闭 body semantic。

实施内容：

- 按 helper 准备度逐个启用：
  - `INT_SHORTHAND`：可复用 range route，`0..stop`。
  - `STRING`：迭代字符，element type 按 Godot 语义锁定后再启用。
  - `ARRAY`：typed `Array[T]` element type 为 `T`，plain `Array` 为 `Variant`。
  - `DICTIONARY_KEYS`：typed `Dictionary[K, V]` element type 为 `K`，plain `Dictionary` 为 `Variant`。
  - `PACKED_ARRAY`：按 packed array family 返回对应 element type。
  - `FLOAT_SHORTHAND`：必须先锁定 `ceil` 语义、element exposed type 与 C helper。
  - `OBJECT_CUSTOM`：如需专用化，必须先有明确 Object method dispatch / `_iter_*` contract。
- known route 的 classifier 只能读取 header typed facts 与当前 suite prefix facts，不得扫描后续 statement。
- route 切换只影响 iteration plan 与 lowering helper；body inventory 不受影响。

验收细则：

- known route 成功时 iterator slot 从 `Variant` 精化为对应 element type。
- known route 未启用时，同一源码仍能走 `GENERIC_VARIANT` route。
- `Array[int]` route 使 body 中 iterator 是 `int`。
- typed dictionary route 使 body 中 iterator 是 key type。
- plain container route 不假装拥有 exact element type。
- 每个 route 都有 lowering helper readiness gate；没有 helper 时 compile gate 不静默放行。

## 5. 验收测试清单

### Parser / scope

- `for i in range(3): pass` 解析为 `ForStatement`。
- `for i in 3: pass`、`for i in limit: pass`、`for i in values: pass` 都解析为 `ForStatement`，且 iterable 保持原始 expression 形态。
- `for i: int in values: pass` 解析并保留 `iteratorType`。
- `FrontendScopeAnalyzerTest` 继续断言 `iteratorType` / `iterable` 在外层 scope，`body` 在 `FOR_BODY`。

### Shared semantic inventory

- `for i in values: var x := i` 成功发布 iterator 与 body local。
- `i` 在 loop 后不可见。
- iterator 与 parameter / local / body local 冲突时按现有 local conflict 规则报错。
- nested for body 能正常发布内外层 iterator，且内层可读取外层 iterator。
- `for` 不再产生 `sema.unsupported_variable_inventory_subtree`、`sema.unsupported_binding_subtree` 或 `FOR_SUBTREE` deferred lookup。
- `match`、lambda、block-local `const` 的 deferred / unsupported tests 保持不变。

### SuiteResolver / type facts

- `var limit := 3; for i in limit: var x := i + 1` 中 `i` 在 body 中为 `int`。
- `for i in range(3): var x := i + 1` 中 `i` 为 `int`，`range(...)` root 不出现在 ordinary successful `resolvedCalls()` 中。
- `for item in values: var x := item` 中 unknown route 的 `item` 为 `Variant`。
- `for item: String in values:` 中 body 的 `item` 为 `String`，但 plan 记录 raw element 需要 conversion 或 runtime check。
- `GdccForRangeIterType` 不出现在 ordinary `expressionTypes()` 与 user-facing `slotTypes()` 中。

### Type-check

- `range()`、`range(1, 2, 3, 4)`、literal `range(1, 2, 0)` 均有明确 diagnostic。
- `for i: String in range(3): pass` 不静默通过。
- `for i in 2.2:` 在未专用化前不报 frontend unsupported；若启用 float route，测试锁定 `ceil` 语义。
- `Dictionary` route 测试锁定 iterator 是 key。

### Compile gate

- range route 在无 error 时可通过 `analyzeForCompile(...)`。
- generic route helper 未实现前，unknown iterable compile path 有明确 route-not-ready diagnostic。
- generic route helper 完成后，`for item in values:` 可通过 compile gate。
- compile gate 不为已有 upstream semantic error 追加同级 generic `sema.compile_check` 噪声。

### CFG

- `FrontendCfgGraphBuilderTest` 覆盖 `FrontendForRegion` shape。
- `continue` target 为 update entry。
- `break` target 为 exit。
- nested `if` 中的 `continue` / `break` 能正确连边。
- 空 body、只有 `pass` 的 body、body 内 `return` 的 reachable/fallthrough 行为均有断言。

### Body lowering / LIR / backend

- `FrontendLoweringBuildCfgPassTest` 覆盖 function context 中发布 `FrontendForRegion`。
- `FrontendLoweringBodyInsnPassTest` 覆盖 range intrinsic instruction sequence，并锁住 `INT_SHORTHAND` 仍走相同 intrinsic 路线。
- generic Variant route 完成后，测试锁住 generic iterator intrinsic sequence。
- `DomLirParserTest` / `DomLirSerializerTest` 覆盖新增 compiler-only iterator state round-trip。
- `GdccForRangeIterTypeTest` / `GdCompilerTypeTest` 继续覆盖 compiler-only type contract。
- `GdccForRangeIterIntrinsicTest` / `CallIntrinsicInsnGenTest` 继续覆盖 intrinsic C generation。
- 如 test-suite 已具备可运行 GDScript fixture，再增加 `range(3)`、`range(1, 3)`、`range(2, 8, 2)`、`range(8, 2, -2)`、generic `Array` / `Dictionary` route 的 runtime output 锚点。

## 6. 建议 targeted test 命令

开发时按阶段运行，不要一开始全量跑：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendParseSmokeTest,FrontendScopeAnalyzerTest,FrontendLoopControlFlowAnalyzerTest
```

```bash
script/run-gradle-targeted-tests.sh --tests FrontendVariableAnalyzerTest,FrontendVisibleValueResolverTest,FrontendInterfacePhaseTest,FrontendSuiteResolverTest
```

```bash
script/run-gradle-targeted-tests.sh --tests FrontendTypeCheckAnalyzerTest,FrontendCompileCheckAnalyzerTest
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

- `FOR_BODY` 已加入 unconditional supported block kind；resolver 的 request domain、AST edge 与 current-scope 三处必须持续允许 for ordinary lookup，否则会重新出现 inventory 已发布但 lookup fail-closed 的矛盾。
- Iterator 使用 `ForStatement` 作为 declaration identity 会触及 `FrontendBodyLocalDeclaration`、`FrontendBodyDeclarationIndex`、visible resolver inventory guard、slot type publication 与 lowering lookup；这些位置必须一起改，不能只改 scope binding。
- Header-derived iterator refinement 发生在 expr typing 之后，不能伪装成原有 statement-local local stabilization。需要明确的 iteration planning owner / patch order。
- `range(...)` root 是否发布 expression type 必须统一。当前计划是不发布 ordinary root type，只发布 arguments 与 iteration plan，避免把 range 当作 user-facing builtin call。
- `int` 简写不允许通过 AST rewrite 伪装成 `range(stop)` call；否则 parser/scope/diagnostic anchor、future object-iterator classifier 与测试都会被污染。
- explicit iterator type 的兼容规则必须复用 ordinary typed-boundary helper；不要为 `for` 私下硬编码 `int -> T` 或 `Variant -> T` 特例。
- `continue` 对 `for` 的目标不是 condition entry，而是 update entry。这一点和 `while` 不同，不能复用 `FrontendWhileRegion`。
- generic Variant iterator helper 当前不存在。shared semantic 可以先完成，但 compile gate 不得在 helper / backend / runtime 未完成时静默放行 generic route。
- Godot 的 `float`、Vector2 / Vector3、Object custom iterator 等语义应优先通过 generic route 保持运行时一致；专用 route 必须有 dedicated semantic tests 后再启用。
- `Dictionary` 迭代返回 key。任何 typed dictionary route 都必须锁住 key type，不能误用 value type。
- known iterable 专用 route 是优化，不是 body semantic 前置条件；专用 helper 缺失时应 fallback generic route 或 compile-gate route-not-ready，而不是恢复 `FOR_SUBTREE`。

## 8. 完成定义

本计划完成后，必须同时满足：

1. 所有 `for iterator[: Type] in expr` 在 shared semantic 中都不再触发 `FOR_SUBTREE` unsupported boundary。
2. loop iterator 在 body 内是 source-facing local；无显式 type 时 baseline 为 `Variant`，可由 iteration plan 精化为 exact element type；显式 type 时 body 使用 declared type。
3. `FrontendForIterationPlan` 是 type-check、compile gate、CFG builder 与 lowering 选择 route 的唯一事实源；CFG / lowering 不重新扫描 AST 推导 iterable 语义。
4. `range(...)` 与 `int` shorthand 复用 `gdcc.for_range_iter.*` route，且不把 `range(...)` 作为 ordinary call 发布。
5. generic Variant route 有完整 LIR intrinsic、runtime helper、C backend codegen 与 tests；unknown iterable 不再需要 compile-time unsupported。
6. compiler-only iterator state type 只出现在 dedicated iteration plan、hidden LIR local / intrinsic operand-result / backend C storage 路径。
7. CFG 中存在独立 `FrontendForRegion`，且 `continue` / `break` 连边正确；同一 region/item/processor 基础设施能承载 range、generic Variant 与 known iterable route。
8. 文档、正反测试、targeted tests、compile check、lowering plan、runtime / intrinsic catalog 全部同步。
