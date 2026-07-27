# Frontend For-in Loop 实现说明

> 本文档作为 `for-in`（含 `range(...)`、数值简写、known-iterable 专用 route 与 generic Variant 回退）
> 在 frontend shared semantic、type-check、compile gate、CFG 与 body lowering 中的**长期事实源**。
> 定义已冻结的 iteration plan 合同、route / lowering contract 分离、iterator declaration 与
> `FOR_BODY` scope 身份、source slot 与 hidden state 分离，以及 fail-closed 边界。
> 本文档吸收并取代原 `frontend_for_range_loop_implementation_plan.md` 中的架构与合同描述，
> **不再保留**分阶段实施步骤、验收流水账或进度状态表。

## 文档状态

- 状态：事实源维护中（shared body inventory、iteration plan 发布、`FOR_ITERATION_RESOLUTION` 精化、
  type-check、route-aware compile gate、CFG region/items、body lowering、以及
  `RANGE_CALL` / `INT_SHORTHAND` / `FLOAT_SHORTHAND` / `STRING` / `ARRAY` / `DICTIONARY_KEYS` /
  全部 `PACKED_*` / `GENERIC_VARIANT` 的 lowering contract 注册与后端 intrinsic 已落地；
  `OBJECT_CUSTOM` 仍未注册 contract）
- 更新时间：2026-07-27
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/cfg/**`
  - `src/main/java/gd/script/gdcc/type/GdccFor*IterType*.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/intrinsic/foriter/**`
  - `src/test/java/gd/script/gdcc/frontend/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`（§6.1 `ForStatement` scope 双录）
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/frontend_local_type_stabilization_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_loop_control_flow_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/gdcc_lir_intrinsic.md`
  - `doc/gdcc_runtime_lib.md`
- 明确非目标：
  - 不在此定义 Object `_iter_*` 精确协议（`OBJECT_CUSTOM` 的 classdb 查询与 element 类型推导）
  - 不在此把 `GdCompilerType` 迭代器状态暴露为 ordinary expression / binding / Variant pack 路径
  - 不更改 Godot `range` / `for-in` 运行时语义
  - 不把 for body 重新关回 deferred / unsupported structural boundary

---

## 1. 当前职责与集成位置

### 1.1 Pipeline 位置

`for-in` 跨越多条 frontend 链路，职责拆分如下：

| 阶段 | 组件 | 职责 |
|------|------|------|
| Scope | `FrontendScopeAnalyzer.handleForStatement` | header 外层 scope 录在 `ForStatement`；body 建 `FOR_BODY` |
| Inventory | `FrontendVariableAnalyzer` / Interface | iterator 以 `ForStatement` 为 declaration identity 进入 `FOR_BODY`；body ordinary local 正常发布 |
| Suite body | `FrontendBodyOwnerProcedures` | header 解析后 `buildPlan` + `FOR_ITERATION_RESOLUTION` 精化；child suite 进入 body |
| Type-check | `FrontendTypeCheckAnalyzer` | 消费 plan：route-aware header 校验、不可迭代诊断、显式 iterator 兼容、始终遍历 body |
| Loop control | `FrontendLoopControlFlowAnalyzer` | break/continue 合法性 |
| Compile gate | `FrontendCompileCheckAnalyzer` | `ForLoweringContractRegistry.get(route)`；null → route-not-ready |
| CFG | `FrontendCfgGraphBuilder.processForStatement` | `FrontendForRegion` + 四个 `ForLoop*Item` + source/hidden slot registry |
| Body lowering | `FrontendSequenceItemInsnLoweringProcessors` | intrinsic 序列 + boundary materialize + temp-then-commit |

SuiteResolver owner 顺序（与 resolution pipeline 一致）：

`top binding` → `local type stabilization` → `chain binding` → `expr typing` →
**`for iteration resolution`** → `var type post`。

### 1.2 三层支持面解耦

1. **Shared body semantic**：inventory / declaration index / suite entry **不依赖** iterable 的最终 typed fact 或 lowering readiness。
2. **Type-check**：消费 plan；hard non-iterable 报 `sema.type_check`，但不阻止 plan 发布与 body 遍历。
3. **Compile / CFG / lowering**：只放行 registry 中已注册 contract 的 route。

iterable typed fact 只影响：

- iterator 是否可从 baseline `Variant` 精化为 exact `semanticElementType` / 显式类型；
- plan 的 `route` 与后续 lowering contract 选择。

### 1.3 当前不负责

- 伪造 iterator 的 `VariableDeclaration` AST
- 在 plan 中存储 intrinsic 名、`GdCompilerType` state、helper result type
- 用 ordinary local stabilization 处理 iterator（declaration domain 互斥）
- 在 body lowering 重新分类 route 或重读 AST shape 选择 intrinsic

---

## 2. Iterator declaration 与 scope 双录

### 2.1 Declaration identity

- Iterator **不是** ordinary `VariableDeclaration`。
- Declaration identity 固定为 owning **`ForStatement`**。
- `BlockScope.defineLocal(iteratorName, baselineType, forStatement)`；`ScopeValue.kind() == LOCAL`。
- Inventory index 使用 `FrontendBodyLocalDeclaration`：`ITERATOR`（`sourceOrder == 0`）与
  `ORDINARY_VAR`（`sourceOrder >= 1`）。
- Iterator 仅在 body 内从首条 statement 可见；loop 之后不可见。
- 与 parameter / 同 callable local 共用 duplicate / shadowing 规则。

### 2.2 Baseline 与精化

| 情形 | Baseline | 精化后 body 可见类型 |
|------|----------|----------------------|
| 无显式 type | `Variant` | `semanticElementType`（若非 Variant） |
| 有显式 `for i: T in ...` | `T` | 保持 `T`（type-check 校验 element → T） |

精化 owner：`FrontendSemanticStage.FOR_ITERATION_RESOLUTION`。

- 只允许 **ForStatement** identity 的 iterator update。
- `LOCAL_TYPE_STABILIZATION` 只处理 `VariableDeclaration` 的 `var :=`。
- 共用规则：`Variant → exact`、exact same-type no-op、禁止 exact A→B、禁止 `GdVoidType` /
  `GdCompilerType`。
- 独立 patch：`FrontendForIterationResolutionPatch`（含 `forIterationPlans` + 受限
  `localSlotTypeUpdates`），不得混入 `FrontendLocalTypeStabilizationPatch`。

### 2.3 Scope 双录与对象身份（强制）

`handleForStatement` 发布两条不同的 `scopesByAst` 记录：

| Key | Scope | 用途 |
|-----|-------|------|
| `ForStatement` | header **外层** scope | iterable / iterator type 等 header 边 |
| `forStatement.body()` | 独立 **`FOR_BODY` `BlockScope`** | iterator local、body locals、iteration overlay |

下游硬性规则：

1. Inventory 与 `refineIteratorSlot` 写入 **`FOR_BODY`**。
2. `FrontendLocalSlotTypeUpdate.scope` 必须是 `scopesByAst[body]` 的**同一对象实例**。
3. `findLocalSlotTypeUpdate` 使用 `update.scope() == scope`（对象身份）。
4. `FrontendTypedLexicalEnvironment.owningScopeForDeclaration(ForStatement)` **必须**取
   `scopesByAst[body]`，不得取 `scopesByAst[ForStatement]`。

否则嵌套 suite 中 iterator 精化会静默 miss，body 内回落到 Interface baseline `Variant`。
完整叙述见 `scope_analyzer_implementation.md` §6.1。

---

## 3. Iteration plan 与 route 分类

### 3.1 `FrontendForIterationPlan`（纯语义事实）

发布侧表：`forIterationPlans()`，key = `ForStatement` identity。

| 字段 | 含义 |
|------|------|
| `statement` | owning `ForStatement`；side-table key 与 declaration identity |
| `route` | `FrontendForIterationRoute` |
| `iteratorName` | 源码 iterator 名（= `statement.iterator()`） |
| `declaredIteratorTypeRef` | 源码 `TypeRef` 或 null（**不是** resolved `GdType`） |
| `semanticElementType` | Godot 静态分析推导的元素类型（source-visible） |
| `exposedIteratorType` | body 内 iterator 可见类型（显式 T 或镜像 `semanticElementType`） |
| `sourceOperands` | 源码 expression 列表（不伪造 AST） |

约束：

- **不**携带 `GdCompilerType`、intrinsic 名、helper result type、unpack 决策。
- `samePlan` 用于幂等合并：忽略 statement key；类型按 class+name；`TypeRef`/operands 按 AST 身份。

构造入口：`FrontendForLoopSupport.buildPlan(statement, declaredIteratorType, iterableType)`。

### 3.2 `FrontendForIterationRoute`

当前 enum 包括（与代码一致）：

- `RANGE_CALL`、`INT_SHORTHAND`、`FLOAT_SHORTHAND`
- `STRING`、`ARRAY`、`DICTIONARY_KEYS`
- 十个 `PACKED_*_ARRAY` 族成员
- `OBJECT_CUSTOM`（保留，**无** registry 合同）
- `GENERIC_VARIANT`（静态未知 / Object / Variant / 未专用化回退）

### 3.3 分类真源

`FrontendForLoopSupport` 是 route / 可迭代性分类的**唯一**实现点：

1. **`isBareRangeCall`**：callee 为标识符 `range` 的 bare call → `RANGE_CALL`，
   `semanticElementType = int`，operands = call arguments。  
   **Shadow 合同**：同名 local/callable **不能**取消该预路由（对齐 Godot 4.5.1 形状判定）。
2. **`iterableType instanceof GdIntType`** → `INT_SHORTHAND`，element `int`。
3. 否则 `classifyIterableSemantics` + `selectKnownRoute`：
   - 有专用 route 且 registry 已注册 → 该 known route；
   - 否则 → `GENERIC_VARIANT`。

`FrontendIterableSemantics`：

- `StaticIterable(elementType)`
- `DynamicIterable()`（`Variant` / `Object`：运行时开放）
- `NonIterable(iterableType)`（hard non-iterable）

### 3.4 `semanticElementType` 推导（body 静态语义）

| Iterable 静态类型 | semantic element |
|-------------------|------------------|
| bare `range(...)` / `int` 简写 | `int` |
| `float` 简写 | `float` |
| `String` | `String` |
| `Array` / typed `Array[T]` | value type（untyped → 相应默认） |
| `Dictionary` / typed | **key** 类型 |
| `Packed*Array` | 对应元素类型 |
| `Vector2`/`Vector3` | `float`；`Vector2i`/`Vector3i` → `int`；dim>3 / compound → non-iterable |
| `Variant` / `Object` / 静态未知 | `Variant`（动态） |
| hard non-iterable（`bool`、`Nil`、`RID`、`StringName`、`NodePath`、compiler/meta/void 等） | plan 仍发 `GENERIC_VARIANT` + element `Variant`；type-check 报错 |

`GdObjectType` 的 element 当前保守为 `Variant`；`OBJECT_CUSTOM` 精确 `_iter_get` 推导为后续工作。

### 3.5 三路径类型解耦

| 路径 | 输入 | 用途 |
|------|------|------|
| Semantic compatibility | `semanticElementType` → `exposedIteratorType` | type-check `checkAssignmentCompatible` |
| Lowering materialization | `contract.get().resultType()` → `exposedIteratorType` | `materializeFrontendBoundaryValue` |
| Route runtime guarantee | helper 逻辑值符合 element 语义 | backend/runtime |

plan **不得**再引入 `requiresPerElementConversion` 一类平行 conversion matrix。

---

## 4. Lowering contract 与 compile gate

### 4.1 `FrontendForLoweringContract`

不进入 `FrontendAnalysisData`。由 compile gate / CFG / processors 按 route 查询：

```
iteratorStateType : GdCompilerType
init / shouldContinue / next / get : ForIterationOperationDescriptor
  (intrinsicName, resultType, argumentTypes)
```

`ForLoweringContractRegistry`：

- non-null → compile-ready；
- null → route-not-ready；
- 单调：注册后不得移除或替换；每 route 至多一次。

### 4.2 当前注册面（与代码一致）

| Route | State type（示例） | Intrinsic 族 |
|-------|-------------------|--------------|
| `RANGE_CALL` / `INT_SHORTHAND` | `GdccForRangeIterType` | `gdcc.for_range_iter.*` |
| `FLOAT_SHORTHAND` | `GdccForFloatIterType` | `gdcc.for_float_iter.*` |
| `STRING` | `GdccForStringIterType` | `gdcc.for_string_iter.*` |
| `ARRAY` | `GdccForArrayIterType` | `gdcc.for_array_iter.*` |
| `DICTIONARY_KEYS` | `GdccForDictionaryIterType` | `gdcc.for_dictionary_iter.*` |
| 各 `PACKED_*` | `GdccForPackedArrayIterType` 族 | per-family names |
| `GENERIC_VARIANT` | `GdccForVariantIterType` | `gdcc.for_variant_iter.*` |
| `OBJECT_CUSTOM` | **未注册** | — |

Intrinsic 名称与签名以 `doc/gdcc_lir_intrinsic.md` 与 backend `CFor*IterIntrinsic` 为冻结副本。

### 4.3 Compile gate 政策

`FrontendCompileCheckAnalyzer.handleForStatement`：

1. 读取已发布 `FrontendForIterationPlan`；
2. `ForLoweringContractRegistry.get(plan.route())`；
3. null → statement root **route-not-ready** blocker，不进入 body 重扫；
4. non-null → mark compile surface，重扫 operands + body facts。

若同一 `ForStatement` 已有上游 `sema.type_check` error，route-not-ready 按统一去重合同省略。

---

## 5. Type-check 合同

`FrontendTypeCheckAnalyzer.handleForStatement`：

- 与 `while` 共用 executable-depth 与 published-fact guard。
- **始终** `walkSupportedExecutableBlock(body)`，与 route 无关。
- Header 按 route 分流：
  - `RANGE_CALL`：arity 1..3；各 argument 进入 `int` slot；
  - `INT_SHORTHAND`：stop operand 进入 `int` slot；
  - 其余 route：ordinary iterable 稳定事实检查；不发 “unsupported for” 类诊断。
- 显式 iterator type：`checkAssignmentCompatible(semanticElementType, exposedIteratorType)`。
- Hard non-iterable：
  - category：`sema.type_check`；
  - 文案：`Unable to iterate on value of type "X"`；
  - anchor：iterable 表达式源范围；
  - 仍发布 plan 并遍历 body；
  - 上游 `BLOCKED`/`FAILED`/`DEFERRED`/`UNSUPPORTED` 时不追加；
  - `Variant` / 静态未知 **不**触发 hard non-iterable。

可迭代性诊断 **不得** 伪装为 compile gate blocker。

---

## 6. CFG 与 body lowering

### 6.1 `FrontendForRegion`

五个结构锚点 + 两个 slot 引用：

- `initEntryId` / `conditionEntryId` / `bodyEntryId` / `updateEntryId` / `exitId`
- `sourceIteratorSlotId` / `iteratorStateSlotId`

形状：

```
init [operands..., ForLoopInitItem]
  → condition [ForLoopShouldContinueItem] → branch
      true  → body [ForLoopGetItem] → statements → update [ForLoopNextItem] → condition
      false → exit
```

### 6.2 四个 `ForLoop*Item`（`ValueOpItem`）

| Item | 结果 | 职责 |
|------|------|------|
| `ForLoopInitItem` | 无 ordinary result | 写 hidden state |
| `ForLoopShouldContinueItem` | bool temp | 读 state |
| `ForLoopGetItem` | raw element temp + source slot | get → materialize → assign source |
| `ForLoopNextItem` | 无 ordinary result | next → **distinct** next temp → assign 回 state |

Item 字段在 CFG build 时固化 contract 中的 operation descriptor 与 slot id；body lowering
**不得**重查 AST 或重分类 route。

### 6.3 Source slot vs hidden state

| | Source iterator slot | Hidden state slot |
|--|----------------------|-------------------|
| 类型 | ordinary `GdType`（`exposedType`） | **仅** `GdCompilerType` |
| Id | 源码 iterator 名 | `cfg_for_iter_<n>` |
| Next temp | — | `cfg_for_iter_next_<n>`（与 state **不同 id**） |
| Registry | `frontendForSourceIteratorSlots` | `frontendForIteratorStateSlots` |
| 生命周期 | 源码 local | 循环携带；next 为 temp-then-commit，非 in-place mutation |

**严禁** source 与 hidden 共享 id / type / lifecycle / registry。

`slotTypes()[ForStatement]` 发布 source-facing exposed type，供 CFG 分配 source slot；其值来自
在 **`FOR_BODY` 身份**上精化后的 effective slot type（`VAR_TYPE_POST`）。

### 6.4 Range 操作数归一化

`RANGE_CALL` / `INT_SHORTHAND` init lowering 将 1/2/3 个 source operand 归一为
`(start, end, step)`；缺失的 `0` start / `1` step 经常量物化补齐。`INT_SHORTHAND` 与
`range(stop)` 统一为单 stop operand 语义。

### 6.5 Get 路径

```
raw = get(state)   // resultType = contract.get().resultType()
mat = materializeFrontendBoundaryValue(raw, exposedIteratorType)
AssignInsn(sourceIteratorSlot, mat)
```

debug boundary tag：`for_in_get`。

### 6.6 跨表验证

CFG build artifact 与 body predeclaration 必须在任意 instruction lowering 前验证：

- slot owner / type / 唯一性；
- item 引用的 slot 与 region 一致；
- source / hidden 分离；
- hidden id 不进入 ordinary value 表面。

细节见 `frontend_lowering_cfg_pass_implementation.md`。

---

## 7. Header 解析与 range 预路由

`SuiteResolver` for path：

1. 解析 iterator type（若有）与 iterable（含 range 参数的 source-order owner procedures）；
2. 发布 / 消费 `FrontendForIterationPlan` 并执行 iterator 精化；
3. 以 child suite 进入 body（读取精化后的 iterator）。

Bare `range(...)`：

- 不得因同名 binding 退回 ordinary unknown-callee 路径；
- named argument 的 **value** 仍按 source order 解析；named-ness 本身由 range-specific
  validation / type-check 拒绝，而不是变成 unknown `range` binding 噪声。

---

## 8. 测试锚点

主要类：

- `FrontendForLoopSupportTest` — 分类、plan 字段、route 选择
- `FrontendTypeCheckAnalyzerTest` — range arity、显式 type、non-iterable、body 遍历
- `FrontendCompileCheckAnalyzer` 相关测试 — route-aware gate
- `FrontendCfgGraphBuilderForLoopTest` — region / items / slot registries
- `FrontendLoweringBodyInsnPassTest` — intrinsic 序列、转换、嵌套 distinct slots
- `ForLoweringContractRegistryTest` — 注册面与 `OBJECT_CUSTOM` null
- `FrontendSuiteResolverTest` / `FrontendTypedLexicalEnvironmentTest` — nested suite 精化可见性
- `GdScriptUnitTestCompileRunnerTest` 等 e2e control-flow fixtures
- backend：`CFor*IterIntrinsic` / `CBodyBuilder*` / integration 测试

代表性场景：

- `for i in range(n):` / `range(a,b)` / `range(a,b,s)`
- `for i in 3:` int shorthand
- `for i: float in range(3):` 显式类型 + conversion
- `for c in "abc":` / array / dictionary keys / packed arrays
- hard non-iterable → shared type-check，非 compile-gate
- nested `for`：distinct hidden slots；inner iterator 精化可见
- `break` / `continue` 与 loop-control 协作
- `OBJECT_CUSTOM` 仍 route-not-ready

---

## 9. 当前局限与后续

- **`OBJECT_CUSTOM`**：enum 保留，registry 未注册；Object `_iter_*` element 推导仍为后续。
- **`GdObjectType` `semanticElementType`**：当前保守 `Variant`；精确协议依赖 classdb。
- 新增 `GdType` 子类型时必须更新 `classifyIterableSemantics` exhaustive switch。
- hard non-iterable 集合与 Godot 对齐需持续校对。
- backend lifecycle / destroy 合同以 runtime / ownership 文档为准，本文只约束 frontend 不把
  compiler-only state 泄漏到 ordinary 表面。

---

## 10. 维护结论

1. **Plan 与 contract 分离**不可回退：semantic 事实进 `FrontendAnalysisData`；lowering ABI 只在
   `ForLoweringContractRegistry`。
2. **`FOR_BODY` 对象身份**是 iterator overlay 的唯一匹配键；改 scope 图时必须同步
   `owningScopeForDeclaration` / `refineIteratorSlot`。
3. 新 route：先冻结 intrinsic + compiler-only state + C helper，再 **register** contract，
   最后才期望 compile gate 放行。
4. 修改本合同时，同步
   `frontend_rules.md`、`scope_analyzer_implementation.md` §6.1、
   `frontend_compile_check_analyzer_implementation.md`、
   `frontend_type_check_analyzer_implementation.md`、
   `frontend_lowering_cfg_pass_implementation.md` 与 `gdcc_lir_intrinsic.md` 中对应条目。
