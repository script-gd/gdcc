# Frontend for-in loop 实施计划

> 本文档是 `for iterator[: Type] in expr` 的长期实施事实源，定义 shared semantic / compile / lowering 三层支持面的架构合同、核心设计与分阶段实施步骤。不再保留已完成阶段的验收流水账。

## 文档状态

- 状态：实施中（shared semantic 结构支持已完成；bare `range(...)` header 预路由、iteration plan、CFG、lowering 尚未实施）
- 创建日期：2026-07-03
- 更新时间：2026-07-23
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
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
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
  - Godot source `modules/gdscript/gdscript_analyzer.cpp` 的 `GDScriptAnalyzer::resolve_for(...)`
  - Godot source `modules/gdscript/gdscript_compiler.cpp` 的 for-statement lowering path
  - Godot source `modules/gdscript/gdscript_byte_codegen.cpp` 的 `write_for(...)`
  - Godot source `modules/gdscript/gdscript_vm.cpp` 的 `OPCODE_ITERATE*`
  - Godot source `core/variant/variant_setget.cpp` 的 `Variant::iter_init` / `iter_next` / `iter_get`
  - Godot GDExtension API `variant_iter_init` / `variant_iter_next` / `variant_iter_get`

- 明确非目标：
  - 不在这里定义 generic Variant iterator route 的 runtime helper 或 C backend 实现细节
  - 不在这里定义 known iterable 专用 route 的 helper 准备度
  - 不在这里改变 Godot range runtime 语义

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
- 不把 hidden iterator state 编码成普通 CFG value id、`CfgValueMaterializationKind`、`MergeValueItem` result 或 `operandValueIds()` entry。
- 不用 `sourceOrder == 0` 推导 runtime slot id。该值只表示每个 `FOR_BODY` declaration inventory 中 iterator 是 synthetic 第 0 项。

## 2. 当前基线

已完成：

- `gdparser` 的 `ForStatement` AST 已包含 `iterator`、`iteratorType`、`iterable`、`body`、`range` 字段；其中 `range()` 是 AST source-location anchor，不是 `range(...)` builtin classifier，pre-route 必须检查 `iterable` expression shape。
- `FrontendScopeAnalyzer` 已为 `ForStatement` 建立 `FOR_BODY` scope，`iteratorType` 与 `iterable` 在外层 scope 下遍历，`body` 在独立 `FOR_BODY` scope 下遍历。
- `FrontendLoopControlFlowAnalyzer` 已把 `for` 视为 loop boundary，`break` / `continue` 在 `for` body 内合法。
- `FrontendVariableAnalyzer` 已无条件发布 iterator 与 for body ordinary local inventory；`FrontendInterfacePhase` 已发布 iterator declaration index、typed baseline 与 suite entry；`FrontendStatementResolver` 已通过 header-only statement boundary 进入普通 child-suite path；`FrontendVisibleValueResolver` 已允许 for header/body ordinary lookup。
- `FrontendBodyLocalDeclaration` 与 `FrontendBodyDeclarationIndex` 已支持 `Node` declaration identity，以 `ForStatement` 作为 `ITERATOR` entry identity。
- `FrontendBodySemanticSupportPolicy` 已将 `FOR_BODY` 映射为 `EXECUTABLE_BODY`；`FrontendBodyStructuralCompleteness` 已实现 `FOR_BODY` 双向校验（iterator entry 位于 sourceOrder==0）。
- `GdccForRangeIterType.FOR_RANGE_ITER` 已作为 compiler-only `GdCompilerType` 子类型存在，backend 与 runtime helper 已有对应实现。
- `doc/gdcc_lir_intrinsic.md` 已冻结四个 range intrinsic：`gdcc.for_range_iter.init`、`gdcc.for_range_iter.should_continue`、`gdcc.for_range_iter.next`、`gdcc.for_range_iter.get`。

尚未实施：

- `FrontendStatementResolver.resolveForStatement(...)` 仍无条件对整个 `forStatement.iterable()` 调用 ordinary `runSupportedRoot(...)`。bare `range(...)` 缺少专用预路由，canonical `for i in range(3)` 会为 callee 发布 unknown binding 并让 call root expression typing 失败。
- `FrontendForIterationPlan`、`FrontendForIterationRoute` 不存在；iteration planning owner 未实现。
- `FrontendCompileCheckAnalyzer` 对每个 `ForStatement` root 发布临时、无条件的 `sema.compile_check` blocker。
- `FrontendCfgGraphBuilder.processStatement(...)` 没有 `ForStatement` 分支；`FrontendCfgRegion` 只允许 `BlockRegion`、`FrontendIfRegion`、`FrontendElifRegion`、`FrontendWhileRegion`。
- `FrontendCfgGraphBuilder.ExecutableBodyBuild` 与 `FunctionLoweringContext` 没有 compiler-only hidden-local registry。
- generic Variant iterator helper、typed container iterator helper、Object `_iter_*` helper 尚未实现。

实施目标：所有 `for-in` 在 shared semantic 中都是 supported body；iteration plan 决定 iterator type refinement 与 lowering route；compile surface 按 route helper 准备度分阶段打开。

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

- `ORDINARY_VAR` 的 `declaration` 仍必须是 `VariableDeclaration`，`sourceOrder >= 0`（在 `FOR_BODY` 中 iterator 占用 `0`，因此 ordinary local 从 `sourceOrder >= 1` 开始连续编号）。
- `ITERATOR` 的 `declaration` 必须是 owning `ForStatement`，作为 synthetic 第 0 项固定使用 `sourceOrder == 0`，位于 body declaration list 头部、在所有 ordinary statement 之前可见；不使用独立前置 sentinel（`sourceOrder` 禁止负数）。
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
- `iteratorStateType` 只允许出现在该 dedicated iteration plan 与 lowering-internal state，不得进入 ordinary expression / slot / binding tables。任何被 compile gate 放行的 route 都必须具有 non-null compiler-only state type；尚无 state contract 的 route 必须保持 route-not-ready。
- `operationNames` 由 route contract 提供，consumer 不得在 CFG builder / lowering processor 中散落硬编码 intrinsic 名称。
- `sourceOperands` 保留源码 expression，不伪造 AST。

`range(...)` route 第一版使用已有 contract：

- `iteratorStateType = GdccForRangeIterType.FOR_RANGE_ITER`
- `rawElementType = GdIntType.INT`
- `operationNames = gdcc.for_range_iter.init / should_continue / next / get`

generic Variant route 需要新增 contract：

- `iteratorStateType` 使用新的 compiler-only generic iterator state type。generic route 在该类型及其 lifecycle/intrinsic contract 冻结前不得进入 compile-ready surface。
- `rawElementType = GdVariantType.VARIANT`
- `operationNames` 使用新 intrinsic，例如 `gdcc.for_variant_iter.init / should_continue / next / get`，具体名称在 intrinsic catalog 阶段冻结。

### 3.5 dedicated hidden iterator state slot

loop-carried iterator state 是 lowering-owned mutable storage，不是 source expression value。每个 compile-ready `ForStatement` 必须在 frontend CFG build artifact 中发布一个 `FrontendForIteratorStateSlot` 或等价 immutable metadata，key 使用 owning `ForStatement` identity。建议形状：

```java
record FrontendForIteratorStateSlot(
        @NotNull ForStatement statement,
        @NotNull String slotId,
        @NotNull GdCompilerType stateType
) {}
```

第一版稳定命名为 `cfg_for_iter_<n>`，其中序号由单个 executable-body CFG build 按 source traversal order 分配。该 metadata 与 graph/regions 一起复制并发布到 `FunctionLoweringContext`，由 body lowering session 声明对应 LIR function local。它不是 semantic published fact，不进入 `FrontendAnalysisData`；route、type 与 operation names 仍以 `FrontendForIterationPlan` 为唯一 sema 事实源。

隐藏槽必须遵守：

- `slotId` 不是 CFG value id；不得出现在 `ValueOpItem.resultValueIdOrNull()`、`operandValueIds()`、value producer map、`cfg_tmp_*` / `cfg_merge_*` materialization collection 中。
- 不新增 `HIDDEN_COMPILER_STATE` 一类 `CfgValueMaterializationKind`。value materialization 只负责 CFG value；hidden mutable local 由独立 registry 声明和验证。
- 每个 `FrontendForRegion` 恰好引用一个 hidden slot；每个 hidden slot 恰好归属一个 `ForStatement` / `FrontendForRegion`。
- 同一 executable body 内 `slotId` 唯一，nested/sibling loops 不得复用；slot type 必须严格等于对应 plan 的 non-null `iteratorStateType`。
- range/int route 的 state type 是 `GdccForRangeIterType.FOR_RANGE_ITER`，只允许进入 dedicated plan、hidden-slot metadata、LIR function local、intrinsic argument/result 与 backend storage。
- source-facing iterator local 仍以 `ForStatement` 为 declaration identity，类型来自 final `slotTypes()[ForStatement]`；hidden slot 与 source local 不共享 id、type 或 lifecycle。

CFG item 对 hidden slot 的访问是显式字段合同，而不是 ordinary operand：

```text
ForLoopInitItem:
    ordinary operands = range/int source value ids
    hidden effect = initialize iteratorStateSlotId
    ordinary result = none

ForLoopShouldContinueItem:
    hidden read = iteratorStateSlotId
    ordinary result = bool condition value id

ForLoopGetItem:
    hidden read = iteratorStateSlotId
    ordinary result = raw element value id
    source effect = convert if required, then commit to iterator local

ForLoopNextItem:
    hidden read/write = iteratorStateSlotId
    ordinary result = none
```

四个 for-loop item 都实现 `ValueOpItem`，并加入 `ValueOpItem` sealed permits；`SequenceItem` 已 permits `ValueOpItem`，无需为每个 item 单独扩展。`ForLoopInitItem` 与 `ForLoopNextItem` 必须返回 `null` result、`hasStandaloneMaterializationSlot() == false`，且只把真正的 source operands 放入 `operandValueIds()`。`ForLoopShouldContinueItem` 与 `ForLoopGetItem` 的 bool/raw result 仍是 ordinary single-definition CFG value。

next intrinsic 返回一个新 state，不是 in-place mutation。阶段 H 必须使用 distinct lowering-owned temp：

```text
cfg_for_iter_next_<n> = next(cfg_for_iter_<n>)
cfg_for_iter_<n> = cfg_for_iter_next_<n>
```

第二步使用现有 `AssignInsn` lifecycle path。不得直接生成 `cfg_for_iter_<n> = next(cfg_for_iter_<n>)`；当前 range state 虽然是 direct-assign-safe POD 且 destroy 为 no-op，generic state 未来可能包含 destroyable resource，计划不能依赖该偶然事实。

build artifact 构造时必须执行跨表验证，而不是只在 processor 中 fail fast：

- hidden-slot metadata 的 key、`statement`、region owner 必须 identity 一致。
- init/condition/body/update sequence 中的四个 item 必须引用同一 slot。
- init item 必须位于首次 condition 前；next item 必须位于 update entry，并在 backedge 前提交。
- should-continue 结果必须是 `bool`，condition branch 必须消费该 ordinary value id。
- get item 必须在 body statements 前提交 source-facing iterator local。
- 缺失 metadata、重复 slot id、state type mismatch、跨 nested-loop slot 引用、把 slot id 混入 value-id surface 均为 graph construction error。

### 3.6 `range(...)` header 预路由与 ordinary call route 隔离

`range(...)` 在 `for` iterable 位置是 loop-specific syntax form：

- `resolveForStatement(...)` 必须在对 iterable 调用 ordinary owner pipeline 前按 AST shape 识别 bare `IdentifierExpression("range")` call。该预路由不能等待 expr typing 后的 iteration planning，因为届时 unknown callee / failed call facts 已进入 pending overlay，现有 transaction 没有删除或覆盖这些 facts 的协议。
- bare `range(...)` 的预路由识别与参数合法性验证分离：只要 callee 是 bare `IdentifierExpression("range")` 就先绕开 ordinary call root；1/2/3 positional arguments 才能形成有效 `RANGE_CALL` plan，错误 arity / argument form 由后续 range-specific type-check 诊断。
- 命中预路由后，只按 source order 对 arguments 分别运行 top binding、local stabilization、chain binding、expr typing 与必要的 var type post。callee identifier 与 call root 都不进入 ordinary top-binding / call-resolution / expr-type publication 路径。
- `range(...)` root 不发布 ordinary `resolvedCalls()`，也不发布 ordinary `expressionTypes()`；arguments 必须各自拥有 planning/type-check 所需的 expression type，并进入既有 `int` boundary。
- attribute call、subscript call、`some_range(...)`、`obj.range(...)` 不触发该预路由，继续把整个 iterable 交给 ordinary `runSupportedRoot(...)`。named argument 是否被 parser 表达为 bare call argument form，不改变预路由；它必须由 range-specific validation 拒绝，而不是退回 unknown ordinary callee 诊断。D0 pre-route 仍按 source order 对 named argument 的 value expression 运行 owner procedures；named-ness 本身在阶段 E range-specific validation 中拒绝，不影响 D0 发布 argument expression facts。
- range arguments、显式 iterator type 与后续 iteration plan facts 仍共享一个 for-statement header flush；不得为每个 argument 单独建立 statement boundary。
- literal `step == 0` 应在 frontend 发 diagnostic；非 literal step 交给 runtime helper 的防无限循环保护。

该分流在 AST-shape 识别模式上与 Godot compiler 的 lowering 边界一致，但 GDCC 把识别提前到 semantic owner 调度层：Godot compiler 按 for-list AST shape 识别 bare `range(...)`，逐个处理 operands，并跳过 ordinary list-expression call lowering。GDCC 不机械复制 Godot analyzer 的内部 utility-function 模型；当前 GDCC 既没有可供 `range` 使用的 ordinary binding，又明确禁止为 for-range root 发布 ordinary call fact，因此必须在 `resolveForStatement(...)` 的 statement-local owner 调度入口完成更早的预路由。

### 3.7 Godot iteration 语义落点

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

range/int route 的生产链路（阶段 C → D0 → D1 → E → G → H → F 解封）属于一个原子实施边界。不得先放宽 compile gate，也不得在 plan producer 尚不存在时通过测试专用 side-table mutation 声称某阶段已完成。generic Variant route（阶段 I）与 known iterable 专用 route（阶段 J）留在后续独立实施。


### 阶段 A/B：parse / scope 基线与 body inventory 解封（已完成）

阶段 A（parse/AST/scope 基线测试）与阶段 B（body inventory 与 declaration index 解封）已完成。所有 `for-in` body 已转为 shared semantic 结构支持面；iterator binding、body local inventory、declaration index、typed baseline、suite entry 均已无条件发布。`FrontendCompileCheckAnalyzer` 对所有 `ForStatement` 发布临时无条件 `sema.compile_check` blocker，等待后续 route-aware policy 替换。

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

状态：未完成。ordinary iterable 的结构性 header/body path 已落地；bare `range(...)` 仍被错误送入 ordinary owner pipeline，canonical range header 不可用。

目标：

- 让 `SuiteResolver` 先解析 for header，再以阶段 B 已发布的 iterator baseline 进入 child body。
- 在 statement-local owner pipeline 之前完成 bare `range(...)` header 预路由，使 canonical range form 不依赖 ordinary callable binding。
- 本阶段只依赖阶段 B，不依赖阶段 C 的 iteration plan 数据结构，也不做 iterator slot refinement。
- 为 resolution pipeline 提供“没有 typed gate 也能进入 for body”的 production path。

实施内容：

- 已落地：`FrontendStatementResolver` 增加 `resolveForStatement(...)`，替换原 `resolveUnsupportedRoot(context, forStatement)`。
- `resolveForStatement(...)` 必须是 header-only：
  - 如果 `iteratorType` 非空，解析该 type ref 相关 expression / type use-site 所需 facts。
  - 若 iterable 是 bare `range(...)`，只逐个解析 arguments，不解析 callee identifier 或 call root。
  - 其他 iterable 继续解析整个 expression root。
  - 不在 header pass 中遍历 body statements。
- 尚未落地的 D0 blocker：在调用 ordinary `runSupportedRoot(...)` 前增加 range shape pre-route：
  - `CallExpression` 的 callee 必须是名称严格为 `range` 的 bare `IdentifierExpression`。
  - 命中后按 source order 对每个 argument 单独运行现有 owner procedures；不得对 call root 或 callee 运行 ordinary top binding / call resolution / expr typing。
  - 未命中的 ordinary iterable 保持当前整 root `runSupportedRoot(context, iterable)` 路径。
  - pre-route 只负责正确划分 owner domain，不构造 iteration plan、不精化 iterator slot，也不在本阶段承担 arity/type diagnostic。
- header facts 在同一个 statement boundary flush；不得把 `iteratorType`、`iterable` 与 body 分别当成
  三个独立 statement flush。
- 本阶段不增加 `runForIterationPlanning(...)`，也不要求 `FrontendForIterationPlan` 已存在。
- `resolveForStatement(...)` 在 header facts flush 后调用
  `childSuiteResolver.resolveChildSuite(context, forStatement.body())`。
- child body 读取阶段 B 的 iterator baseline：无显式 type 时为 `Variant`，有显式 declared type 时为该
  source-facing type。

验收细则：

- D0 完成前必须增加 canonical `for i in range(3): var x := i` production resolver test；默认 shared `analyze(...)` 不得产生 `range` callee 的 `sema.binding` 或 identifier/call-root `sema.expression_resolution`。
- 同一 canonical test 必须断言 argument `3` 已发布 expression type，而 `range` callee identifier 与 call root 均不出现在 ordinary successful `resolvedCalls()` / `expressionTypes()` 中。
- 在 D1 尚未实现时，canonical range body 仍应正常进入，`i` 与 `x` 可以保持 `Variant`。该断言与“header 无 ordinary resolution error”必须同时成立，不能再用单独的 body-entry 绿色测试替代 header 验收。
- `range()`、`range(1, 2, 3, 4)` 与非法 argument form 也必须走 range header pre-route：arguments 能按 source order 解析，且不产生 unknown `range` binding 噪声；精确 arity/type diagnostic 在阶段 E 落地。
- `obj.range(3)`、`some_range(3)` 等非 bare form 必须继续走 ordinary iterable pipeline，证明 pre-route 没有按文本或任意 method name 误分类。
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

依赖：阶段 C 与完整完成的阶段 D0，包括 bare `range(...)` header 预路由及其 canonical regression tests。

目标：

- 在 D0 已建立的 header-first 路径中发布 iteration plan 与 iterator slot refinement。
- body resolver 必须看到 header 已提交的 iterator effective type；该精化只改变 typed fact，不改变
  body 是否进入 `SuiteResolver`。

实施内容：

- 增加 feature-specific owner hook，例如 `runForIterationPlanning(context, forStatement)`，执行位置
  固定在 ordinary iterable root expr typing 或 bare range arguments expr typing 完成后、iterator var type post 前。该 hook 不复用或恢复
  `runGateClassifier(...)`。
- `runForIterationPlanning(...)`：
  - 对 ordinary iterable 读取 root 的 effective expression / slot typed fact。
  - 对 bare `range(...)` 读取 D0 已发布的 argument expression facts 并检查原始 AST shape；不得要求或补发 range callee/call-root ordinary binding、expression type 或 resolved call。
  - 调用 `FrontendForLoopSupport` 构造 plan。
  - 发布 `FrontendForIterationPlan`。
  - 若 iterator baseline 是 `Variant` 且 plan 的 `exposedIteratorType` 是 exact source-facing type，则发布 iterator slot refinement。
  - 若 iterator 有显式 declared type，不自动改写 exact type，只记录 raw element -> exposed type 的 conversion requirement。
- `runVarTypePost(...)` 扩展为支持 `ForStatement` iterator declaration：
  - 为 iterator declaration key 发布 final source-facing `slotTypes()` fact。
  - `slotTypes()` value 必须是 exposed iterator type，不能是 compiler-only state type。
- D1 落地后的完整 header 顺序必须是：iterator type facts -> ordinary iterable root 或 bare range arguments 的 owner procedures -> `FOR_ITERATION_PLANNING` -> iterator var type post -> 单次 statement flush -> child suite。child body 的 `FrontendSuiteContext` 通过 parent environment 读取 iterator slot refinement。
- planning 不得承担“清理 ordinary range call 失败 facts”的职责。D0 pre-route 必须保证这些 facts 从未发布；pending / committed overlay 与 patch transaction 均不新增 delete/overwrite 机制来掩盖错误 owner routing。

验收细则：

- `var limit := 3; for i in limit: var x := i + 1` 中 body resolver 看到 `i:int`。
- `for item in values: var x := item` 中 `item` 在 unknown route 下保持 `Variant`。
- `for i in range(3): var x := i + 1` 不发布 ordinary `range(...)` call route，但 `range` argument 有 expression type。
- canonical range planning 前不得存在 callee/call-root `FAILED` expression fact 或 `sema.binding` / `sema.expression_resolution`；否则 D1 验收直接失败，不能仅凭 plan 已发布判定通过。
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

状态：未实施。先实现 route-aware policy，但只有阶段 G/H 与对应测试完成后才实际解封 range/int route。

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

状态：未实施。不得脱离阶段 C、完整 D0、D1 与 range/int 必要 type-check 单独进入 production path；必须与这些前置及阶段 H 一起原子实施。

目标：

- 在 `FrontendCfgGraphBuilder` 中为 `for-in` 建立显式 CFG。
- `break` 跳到 loop exit。
- `continue` 跳到 iterator update，再回 condition。
- 用 dedicated hidden mutable slot 表达 loop-carried iterator state，不扩展 `MergeValueItem` 或 CFG value producer 合同。

实施内容：

- 新增 `FrontendForRegion`，不要复用 `FrontendWhileRegion`。
- `FrontendCfgRegion` sealed permits 增加 `FrontendForRegion`。
- 新增 AST-keyed `FrontendForIteratorStateSlot` registry 或等价 immutable graph artifact，并与 graph/regions 一起发布到 `FunctionLoweringContext`。
- `FrontendForRegion` 至少记录 `initEntryId`（即 `entryId()`）、`conditionEntryId`、`bodyEntryId`、`updateEntryId`、`exitId` 与 `iteratorStateSlotId`。
- `FrontendCfgGraphBuilder.processStatement(...)` 增加 `case ForStatement forStatement -> processForStatement(...)`。
- `processForStatement(...)` 只消费已发布的 `FrontendForIterationPlan`，不得重新推导 iterable 语义。
- 在 `frontend.lowering.cfg.item` 增加通用的 `ForLoopInitItem`、`ForLoopShouldContinueItem`、`ForLoopGetItem`、`ForLoopNextItem`。item 消费 plan 已冻结的 route/type/operation 信息，不硬编码 range intrinsic 名称。
- 四个 item 使用独立 `iteratorStateSlotId` 字段引用 hidden slot。该 id 不进入 ordinary result/operand value-id surface：
  - init：消费 source operand value ids，初始化 hidden slot，不发布 ordinary result。
  - should-continue：读取 hidden slot，发布 ordinary `bool` result。
  - get：读取 hidden slot，发布 ordinary raw element result，并在 body statements 前提交 source-facing iterator local。
  - next：读取并更新 hidden slot，不发布 ordinary result。
- CFG shape 建议：
  - iterable / source operands 在进入 loop 前按 source order 计算。
  - init entry 调用 plan 指定的 init operation，写入 dedicated hidden iterator state slot。
  - condition entry 调用 should-continue operation，产出 bool condition value。
  - body entry 调用 get operation，写入 source-facing iterator local，再执行 body statements。
  - update entry 调用 next operation，更新 iterator state，再跳回 condition entry。
  - exit sequence 是 loop 后续 continuation。
- active loop frame 对 `for` 应使用：
  - `breakTargetId = exitId`
  - `continueTargetId = updateEntryId`
- hidden iterator state slot 固定使用 `cfg_for_iter_<n>`；next commit temp 固定使用独立 `cfg_for_iter_next_<n>` namespace。两者都不是 CFG value id。
- build artifact 必须验证 slot owner/type/uniqueness、四个 item 的 slot 引用、init-before-condition、get-before-body、next-before-backedge，以及 hidden slot 未泄漏到 ordinary value producer/materialization surface。

验收细则：

- CFG regions 中 `ForStatement` 映射到 `FrontendForRegion`。
- `FrontendForRegion` 暴露 `initEntryId`、`conditionEntryId`、`bodyEntryId`、`updateEntryId`、`exitId` 与 `iteratorStateSlotId`。
- 每个 region 恰好有一个 `FrontendForIteratorStateSlot` metadata；nested/sibling loop slot id 唯一。
- `continue` 的 target 是 update entry，不是 condition entry。
- `break` 的 target 是 exit。
- `range(...)` arguments、`INT_SHORTHAND` stop operand 或 generic iterable value ids 在 init item 前已经发布。
- condition branch 的 condition value type 是 `bool`，不会触发 compiler-only condition normalization。
- hidden slot id 不在 value producer map、ordinary operand ids 或 CFG value materialization map 中。
- init/next 不发布 ordinary result；should-continue/get 各自只有一个 ordinary single-definition result。
- get item 的 ordinary raw result 使用独立 `cfg_tmp_*` slot，不与 source-facing iterator local alias；需要 conversion 时两者允许具有不同类型。
- graph construction 对 missing metadata、duplicate slot id、type mismatch、cross-loop slot reference、错误 entry 中的 item 和 slot-id/value-id 混用 fail fast。
- nested `if` 中的 `break` / `continue` 能正确连边。
- unreachable body 后续 statement 处理仍遵守现有 reachability 规则。

### 阶段 H：range route LIR lowering

状态：未实施。与阶段 C/D0/D1/E/G 和 range/int compile-gate 解封一起原子实施。

目标：

- 先接通已有 backend/runtime 已支持的 `range(...)` 与 `int` shorthand route。
- 不重新扫描 AST，不重新推导 sema facts。

实施内容：

- 消费阶段 G 已冻结的 `ForLoopInitItem`、`ForLoopShouldContinueItem`、`ForLoopGetItem`、`ForLoopNextItem`，不在 body lowering 阶段重新解释 item shape。
- body lowering 在 `FrontendSequenceItemInsnLoweringProcessors` 中增加对应 processor。
- processor 消费阶段 G 从 `FrontendForIterationPlan` 固化到 item/hidden-slot metadata 的 operation/type payload，并生成 `CallIntrinsicInsn`；processor 不重新查询 AST shape 或重新分类 route。range route 对应：
  - init：`gdcc.for_range_iter.init`
  - should_continue：`gdcc.for_range_iter.should_continue`
  - get：`gdcc.for_range_iter.get`
  - next：`gdcc.for_range_iter.next`
- `FrontendBodyLoweringSession` 必须能为 hidden iterator state 声明 `compiler::GdccForRangeIter` local，并沿用 `GdCompilerType` storage/lifecycle 合同。
- hidden local declaration 读取 dedicated registry，不通过 `collectCfgValueMaterializations()` 或 `slotIdForValue()` 声明。
- `ForLoopNextItem` 必须先把 intrinsic result 写入 distinct `cfg_for_iter_next_<n>` compiler-only temp，再用 `AssignInsn` commit 到 `cfg_for_iter_<n>`；不得让 intrinsic result target 与 state argument slot 相同。
- `ForLoopGetItem` 先把 raw element 写入 ordinary temp，再复用现有 typed-boundary conversion 写入 `ForStatement` identity 对应的 source local。
- source-local declaration/lookup helper 必须接受 `ForStatement` identity，不能继续假设所有 body local 都是 `VariableDeclaration`。
- `INT_SHORTHAND` source form 不生成伪造 `range(stop)` AST；init item/lowering processor 负责按 `(0, stop, 1)` 解释。
- `INT_SHORTHAND` 的 `0` 与 `1` 必须先通过既有 integer constant lowering 物化为 LIR variables，再作为 `CallIntrinsicInsn` arguments 传入；intrinsic argument 位置不接受 literal。
- 不通过 `PackVariantInsn` / `UnpackVariantInsn` materialize range iterator state。

验收细则：

- LIR function variables 包含 hidden `compiler::GdccForRangeIter` local。
- LIR function variables 包含与 state slot 不同的 next temp；instruction 顺序锁定为 `next(oldState) -> nextTemp`、`AssignInsn(state, nextTemp)`。
- range init / should_continue / get / next 以 `CallIntrinsicInsn` 出现，参数顺序符合 `doc/gdcc_lir_intrinsic.md`。
- `INT_SHORTHAND` source form 经由同一组 intrinsic 降低，不新增第二套数值简写专用 intrinsic。
- source-facing loop variable slot 是 `int` 或 declared compatible type。
- generated C 使用 `gdcc_for_range_iter_*` helper，不出现 `godot_GdccForRangeIter`、`godot_new_GdccForRangeIter...`、`Variant` pack/unpack 相关路径。
- compiler-only state 与 next temp 的 prepare/final cleanup、每轮 overwrite lifecycle 均由既有 `GdCompilerType` / `AssignInsn` 合同覆盖，并有正反测试证明没有 public boundary 泄漏。
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
- 新增 compiler-only generic iterator state type，并冻结 init/copy/destroy/direct-assignment contract；该类型不得进入 ordinary type tables。
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

- D0 canonical regression：`for i in range(3): var x := i` 不产生 unknown `range` binding 或 identifier/call-root expression-resolution error；argument `3` 有 expression type，range callee/call root 没有 ordinary expression type / resolved call，body 仍能进入。
- D0 ordinary-route regression：`for i in limit:` 继续解析整个 iterable root；`obj.range(3)` / `some_range(3)` 不被 bare range pre-route 捕获。
- 当前已有的 `FrontendSuiteResolverTest` 只覆盖 `items` / `values` / `limit` 等 ordinary iterable，不能作为 D0 range header 的完成证据。阶段 D0 的 targeted test 必须显式包含 canonical `range(...)` source。
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
- `FrontendCfgGraphBuilderTest` 覆盖 range/int source operands 在 init 前按 source order 发布，且 init/next 不产生 ordinary value result。
- `FrontendCfgGraphTest` 或 dedicated build-artifact test 覆盖 hidden-slot metadata owner/type/uniqueness 与 item-slot cross validation。
- 负向测试覆盖 missing metadata、duplicate slot id、state type mismatch、nested loop cross-slot reference、item 位于错误 entry、slot id 混入 ordinary operand/result value ids。
- value producer/materialization 测试明确断言 `cfg_for_iter_<n>` 不进入 producer map、`cfg_tmp_*` / `cfg_merge_*` collection 或 `CfgValueMaterializationKind`。
- nested/sibling loops 获得不同 hidden slot，source-facing iterator local 仍分别以 owning `ForStatement` 为 identity。
- `continue` target 为 update entry。
- `break` target 为 exit。
- nested `if` 中的 `continue` / `break` 能正确连边。
- 空 body、只有 `pass` 的 body、body 内 `return` 的 reachable/fallthrough 行为均有断言。

### Body lowering / LIR / backend

- `FrontendLoweringBuildCfgPassTest` 覆盖 function context 中发布 `FrontendForRegion`。
- `FrontendLoweringBuildCfgPassTest` 同时覆盖 hidden-slot registry 发布，且 compile-ready range/int context 不依赖测试手工注入 iteration plan。
- `FrontendLoweringBodyInsnPassTest` 覆盖 range intrinsic instruction sequence，并锁住 `INT_SHORTHAND` 仍走相同 intrinsic 路线。
- update lowering 测试锁定 distinct next temp、`CallIntrinsicInsn(next)` 后接 `AssignInsn(state, nextTemp)`，并拒绝 source/target 使用同一 state slot。
- get lowering 测试锁定 raw-element temp、必要 conversion、source-facing iterator local commit 的顺序。
- compiler-only boundary 负向测试覆盖 state/next temp 不得进入 ordinary call argument、return、property/store、Variant pack/unpack 或 public ABI；不使用源码文本扫描代替行为断言。
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
- bare `range(...)` 的“expr typing 之后”指 arguments typing 之后，不是 call root ordinary typing 之后。若先让 call root 失败再运行 planning，错误 diagnostic 与 `FAILED` fact 已进入 pending overlay，现有 transaction 无法删除或覆盖，D1 不能补救 D0 owner routing 错误。
- `range(...)` root 是否发布 expression type 必须统一。当前计划是不发布 ordinary root type，只发布 arguments 与 iteration plan，避免把 range 当作 user-facing builtin call。
- bare `range` 与同名 local/callable shadowing 的语义必须在 D0 实现前用 Godot focused case 锁定；不得把 `ForStatement.range()` source anchor 当作 classifier，也不得在没有语义证据时擅自让 ordinary binding 改写 AST-shape pre-route。
- `int` 简写不允许通过 AST rewrite 伪装成 `range(stop)` call；否则 parser/scope/diagnostic anchor、future object-iterator classifier 与测试都会被污染。
- explicit iterator type 的兼容规则必须复用 ordinary typed-boundary helper；不要为 `for` 私下硬编码 `int -> T` 或 `Variant -> T` 特例。
- `continue` 对 `for` 的目标不是 condition entry，而是 update entry。这一点和 `while` 不同，不能复用 `FrontendWhileRegion`。
- `sourceOrder == 0` 只是 `FOR_BODY` inventory 的 declaration order。若把它复用为 hidden slot number、LIR variable index 或每轮 definition version，会把 lexical fact 与 runtime storage 错误耦合。
- loop-carried iterator state 不是 CFG expression value。不得通过两个 `MergeValueItem` 分别把 init/next 写入同一 result id，也不得扩展现有 branch-result merge 合同来掩盖 mutable storage。
- dedicated hidden slot 若只在 lowering processor 中拼接名称而不随 graph artifact 发布，就会绕过 owner/type/uniqueness 与 nested-loop cross-reference validation。registry、region 与 items 必须在 CFG build 完成时交叉验证。
- 不得新增 `HIDDEN_COMPILER_STATE` value materialization kind。该做法仍会让 hidden storage 进入 value-id producer/materialization 模型，只是更换枚举名称，并未解决模型混淆。
- `next` intrinsic 是 new-value operation，不是 in-place mutation。即使当前 `GdccForRangeIterType` destroy 为 no-op，也必须使用 distinct next temp 再通过 `AssignInsn` commit，为 future destroyable generic state 保留正确生命周期顺序。
- 阶段 G 的 production path 依赖 C/D0/D1；阶段 F 的 range/int 解封又依赖 G/H。必须按依赖链原子提交，不能用手工注入 plan 的 builder test 替代真实 source -> sema -> CFG/LIR integration test。
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
6. compiler-only iterator state type 只出现在 dedicated iteration plan、CFG hidden-slot metadata、hidden LIR local / intrinsic operand-result / backend C storage 路径；hidden slot id 不属于 CFG value-id/materialization surface。
7. CFG 中存在独立 `FrontendForRegion` 与 validated hidden-slot registry，且 `continue` / `break` 连边正确；同一 region/item/processor 基础设施能承载 range、generic Variant 与 known iterable route。
8. 文档、正反测试、targeted tests、compile check、lowering plan、runtime / intrinsic catalog 全部同步。

阶段性完成门槛：D0 只有在 ordinary iterable regression 与 canonical `for i in range(3)` regression 同时通过，且后者证明“arguments 有 facts、callee/call root 无 ordinary facts、header 无 ordinary resolution diagnostic、body 仍进入”后，才能重新标注为已完成。仅证明 header 错误不关闭 body，或仅证明全部现有相关测试为绿色，都不满足 D0 完成定义。

range/int 原子闭环只有在以下条件同时满足后才能合并：C/D0/D1/E facts 由真实 production pipeline 发布；阶段 G graph/region/hidden-slot validation 通过正反测试；阶段 H 生成 temp-then-commit LIR 并通过 lifecycle/boundary 测试；compile gate 最后解封且 end-to-end source test 无手工 side-table mutation。任一条件缺失时应保留 range/int route blocker，不得把局部绿色测试标记为阶段完成。
