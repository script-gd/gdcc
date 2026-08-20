# Frontend Conditional（三元）表达式实现说明

> 本文档作为 GDScript 三元表达式 `value1 if condition else value2`（含嵌套）在 frontend shared semantic、compile gate、CFG 构图、body lowering 与 C backend 运行全链路的长期事实源，记录当前冻结的类型合并合同、`MergeValueItem` 共享合同、双语境构图形态与 merge 槽生命周期。本文档替代原 `frontend_conditional_expression_plan.md`，不保留分步骤实施、阶段状态、验收清单或已完成任务日志。

## 文档状态

- 状态：事实源维护中（shared sema 类型推导、compile gate 放行、CFG 双语境构图、body lowering `merge_write`、destroyable merge 槽生命周期与 9 对 e2e 均已纳入当前实现；三元表达式属于 compile-ready 支持面，README 支持面清单已同步）
- 更新时间：2026-08-20
- Godot 对齐基线：`godotengine/godot` `modules/gdscript/gdscript_analyzer.cpp` `reduce_ternary_op`；runtime/GDExtension ABI 固定为 `4.5.1`
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/test/java/gd/script/gdcc/frontend/**`
  - `src/test/test_suite/unit_test/{script,validation}/ternary/`
- 关联文档：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
- 明确非目标：
  - 不修改 gdparser 语法或 AST 形状。
  - 不做常量折叠（Godot 在 condition 与双臂均常量时折叠），不做 flow-sensitive 类型收窄（与现有 `if` 一致）。
  - 不新增 `INCOMPATIBLE_TERNARY` 诊断：Godot 对无公共类型双臂发该 warning，当前只回退 `Variant`（见 §9 D2）。
  - 不做 `void` 臂三元的语句位丢弃特判：`void` 臂与非 Variant 具体类型配对为显式 `UNSUPPORTED`。
  - 不改动 `assert` / `PreloadExpression` / `GetNodeExpression` 的 compile gate 拦截。

## 1. 当前定位与数据流

### 1.1 Parser 形状（gdparser 0.5.3）

- AST 节点 `dev.superice.gdparser.frontend.ast.ConditionalExpression`（record：`condition/left/right/range`）；`left` 是 `if` 前的真值臂，`right` 是 `else` 后的假值臂；映射原样透传，无字段交换。
- 语法 `conditional_expression` 为 `prec.right(PREC.conditional=-1)`：右结合且为最低优先级。`a if c1 else b if c2 else d` 解析为 `a if c1 else (b if c2 else d)`；`a + b if c else d` 解析为 `(a+b) if c else d`；左结合必须括号（mapper 剥括号，无 `ParenthesizedExpression`）。
- `getChildren()=[condition, left, right]`（record 组件顺序 ≠ 源码顺序）。

### 1.2 Godot 参考语义

- 先归约 `condition`，再归约两个分支表达式；运行时只求值被选中的分支（短路与 `and/or` 一致的单臂求值）。
- 结果类型：任一分支为 Variant 则结果为 Variant；否则按双向 `is_type_compatible` 归并（`right` 可赋给 `left` 取 `left` 类型，否则 `left` 可赋给 `right` 取 `right` 类型），否则回退 Variant 并发 `INCOMPATIBLE_TERNARY` warning（当前不发，见 §9 D2）。

### 1.3 全链路数据流

```text
gdparser ConditionalExpression
  -> shared sema: resolveConditionalExpressionType 发布双臂合并类型（binary 式 root 重持有诊断）
  -> compile gate: 落入 default 递归，generic scan 对未稳定 fact fail-closed 兜底
  -> CFG: value 语境 branch-result merge / condition 语境纯控制流展开
  -> body lowering: merge_write boundary 物化 + condition 归一化
  -> C backend: 复用 GoIfInsn / AssignInsn / (un)pack / intrinsic cast，无新 LIR 指令
```

## 2. 冻结语义：类型合并合同

入口 `FrontendExpressionSemanticSupport.resolveConditionalExpressionType(...)`：

1. 按求值顺序先解析 `condition`（无 expectedType，纯控制流语境）；`firstNonResolvedDependency(condition)` 命中 → `propagated`（同 binary/unary 模式）。
2. 双臂经 contextual resolver 解析并透传外层 `expectedType`（使 `var x: Array[int] = [1] if c else [2]` 的双臂容器字面量获得 contextual typing，模式同 cast 的 target-first 透传）；任一臂不稳定 → `propagated`。
3. 三者均稳定后计算合并类型 `resolveConditionalMergedType(leftType, rightType)`，判定顺序固定为：
   - 任一臂 `status==DYNAMIC` 或 published type 为 exact `Variant` → `DYNAMIC(Variant)`（runtime-open，与 unary/binary 的 Variant 处理一致）。
   - `void` 臂：`void`+`Variant` → `DYNAMIC(Variant)`（void 臂运行时产出 Variant 的 `nil`，Variant 合并槽可承载）；`void`+非 Variant 具体类型 → root `UNSUPPORTED`（具体合并槽无法承载 void 臂的 `nil`；显式 feature boundary）。
   - 双臂均为 `RESOLVED` 具体类型：`determineFrontendBoundaryDecision(classRegistry, right, left).allows()` → 合并为 `left`；否则 `determineFrontendBoundaryDecision(classRegistry, left, right).allows()` → 合并为 `right`；否则 `RESOLVED(Variant)`。

由该顺序固定的派生行为：

| 双臂类型 | 合并结果 |
|---|---|
| 同型（如 `String`/`String`） | 该类型 |
| `int`/`float`（双向顺序） | `float` |
| 对象子/父类（双向顺序） | 父类 |
| `null`/object | object |
| 双臂 `Nil` | `RESOLVED(Nil)`（`ALLOW_DIRECT`，不因物化顾虑回退 Variant） |
| 无公共类型（如 `int`/`String`） | `RESOLVED(Variant)` |
| `Array[int]`/`Array[float]` | `RESOLVED(Variant)`（容器元素不递归标量 widening，双向 REJECT） |
| `String`/`StringName`（双向顺序） | 恒为 `left`（双向均 `ALLOW_WITH_BUILTIN_CONSTRUCTOR`，先匹配方向胜出） |
| 任臂 `Variant` 或 `DYNAMIC` | `DYNAMIC(Variant)` |

**硬性规则：** "可赋给"关系统一复用 GDCC ordinary typed boundary 矩阵（`FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision`，单一真源），从而 `int/float→float`、子类→父类、`null→object`、容器协变全部与赋值边界语义一致（§9 D1）。

**诊断归属合同（binary 式 root 重持有）：** wrapper 不记录 `rootOwnsExpressionDiagnostics`（缺省 root-owned=true），propagated 状态在 conditional root 以同 status+detailReason 重发 `sema.expression_resolution`（与 binary/unary/call 完全一致）；`void` 臂为 root 自身一条 `sema.unsupported_expression_route`（臂本身 `RESOLVED(void)` 无诊断）。compile gate 侧由 `reportCompileBlock` 内置的 `hasPublishedConflictingDiagnosticAt` exact-range 冲突去重覆盖，arm 与 root 的 generic `sema.compile_check` 均被各自 range 上的 upstream error 压掉，零 gate 改动、零双报。无附加 side-table 产物。**明确禁止：** 为三元扩展 `rootOwnsOutcome=false` 模式——只有 cast/type-test 使用该模式（`FrontendCompileCheckAnalyzer` ownership 不变量）。

**分发合同：** `FrontendBodyOwnerProcedures.BodyExpressionResolver.computeExpressionType` 的专用 `case ConditionalExpression`（置于 `Cast/TypeTest/Array/Dict` 同级）经包装方法透传 `expectedType`，直接返回 `.expressionType()`，不触碰 `rootOwnsExpressionDiagnostics`（同 `BinaryExpression` 分支形态）。`resolveRemainingExplicitExpressionType` 对三元无 deferred 分支，三元位于"必须使用专用 resolver"穷举白名单。

## 3. `MergeValueItem` 共享合同（与 `and/or` 共享）

三元与 value 语境短路 `and/or` 共享同一 merge 基础设施，合同固定为：

1. **merge 槽类型由 anchor 表达式决定**：`FrontendBodyLoweringSupport.requireProducedValueMaterialization` 的 `MergeValueItem` 分支读取 `mergeAnchor`（必须为已发布 `RESOLVED/DYNAMIC` 类型的 `Expression`，否则 fail-fast programmer error）的 `expressionTypes` 事实。`and/or` anchor 是 `BinaryExpression`（`RESOLVED(bool)`），三元 anchor 是 `ConditionalExpression`（合并类型），均为"整条分支表达式的 outward-facing 类型"，天然消除多生产者 materialization 冲突。
2. **merge 写入经统一 boundary 物化**：`FrontendMergeValueInsnLoweringProcessor` 发射 `materializeFrontendBoundaryValue(block, slotFor(source), sourceType, mergeType, "merge_write")` + `AssignInsn(cfg_merge_<id>, materialized)`。`merge_write` 是 `materializeFrontendBoundaryValue` 的 **re-derive consumer**（与 assignment/return/local-init 同类，现场调 `determineFrontendBoundaryDecision`），不是 frozen-decision consumer。异型臂（如 `int` 臂写入 `float` 合并槽）在此完成转换，符合"(un)pack 单入口"合同。
3. **merge-of-merge 窄放宽**：`FrontendCfgGraph.validateMergeSourceContracts` 允许 merge 的 `sourceValueId` (a) 同 sequence 内已产生，或 (b) graph 全局生产者索引中该 id 至少存在 1 个生产者且全部为 `MergeValueItem`（支撑嵌套三元臂与 value 语境 `and/or` 臂）。判定必须先建与 `validateValueProducerContracts` 同构的 graph-wide producer 索引；**明确禁止**用空流 `allMatch` 真空判定（空集会真空为 true，导致悬空 source 漏过 fail-fast）。悬空 source（图中无任何生产者）仍 fail-fast。

**硬不变量：** `and/or` 的 LIR 形状不变——`bool→bool` 为 `ALLOW_DIRECT`，仍只产 `LiteralBoolInsn` + `AssignInsn(cfg_merge_*, cfg_tmp_*)`，不出现 pack/unpack/intrinsic（§9 D5）。

## 4. CFG 构图

不新增 CFG item 类型，不发布 `Region`（与 value 上下文 `and/or` 一致）。

### 4.1 value 语境：branch-result merge

`FrontendCfgGraphBuilder.buildConditionalExpressionValue(cursor, expr, preferredResultValueId)` 固定形态：

1. `resultValueId = chooseResultValueId(preferredResultValueId)`（preferred 直接透传，如 `var x = 三元` 的 `x_<n>`）；建 `mergeSequence` 与两臂各持独立 `OpenSequence` 的 cursor。
2. `conditionBuild = buildCondition(cursor, expr.condition(), trueArm.entryId(), falseArm.entryId())`——`condition` 中的 `not`/`and/or`/嵌套三元全部由现有 condition 机制处理。
3. 每臂：`armBuild = buildValue(armCursor, armExpression, null)`；在臂 final sequence 末尾追加 `MergeValueItem(expr, armBuild.resultValueId(), resultValueId)`；`publishSequenceNode(armFinalSeq.id(), items, mergeSequence.id())` 合流。
4. 返回 `ValueBuild(new BuildCursor(conditionBuild.entryId(), mergeSequence), expr, resultValueId, null)`。

嵌套三元臂：`buildValue` 递归进入内层 `buildConditionalExpressionValue`，内层未发布的 merge sequence 即外层臂的 final sequence，外层 `MergeValueItem` 追加其中（§3 第 3 步 merge-of-merge 窄放宽的实际服务对象）；每级嵌套持有独立 `resultValueId`，不违反单定义合同。

### 4.2 condition 语境：纯控制流展开

`FrontendCfgGraphBuilder.buildConditionalExpressionCondition(cursor, expr, trueTargetId, falseTargetId)` 固定形态：

```java
var trueArmCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
var falseArmCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
var conditionBuild = buildCondition(cursor, expr.condition(), trueArmCursor.entryId(), falseArmCursor.entryId());
buildCondition(trueArmCursor, expr.left(), trueTargetId, falseTargetId);
buildCondition(falseArmCursor, expr.right(), trueTargetId, falseTargetId);
return conditionBuild;
```

即 `if (a if c else b):` ≡ 先测 `c`，再对被选臂做 truthiness 分支，**不产生任何 merge 值**。两臂经 `buildCondition` 递归分发：`not`/`and/or`/嵌套三元臂复用现有 condition 机制（嵌套三元臂继续纯控制流展开）；普通臂走 `buildConditionFromValue`，其值必为 temp 槽。各臂 `BranchNode.conditionRoot` 由 `publishConditionBranch` 按 fragment 对齐规则处理。

**硬性规则：** condition 语境三元禁止走 value 路径——`FrontendBranchNodeInsnLoweringProcessor.emitConditionBranch` 固定读 `cfgTempSlotId(conditionValueId)`，而 value 路径结果是 `cfg_merge_<id>` 槽，会读到从未赋值的 `cfg_tmp_`；`frontend_rules.md` 亦禁止把 outward-facing merge result id 当作 branch condition id。因此 condition 语境三元的 `conditionValueId` 恒为 temp 槽，`emitConditionBranch` 与 truthiness 归一化零改动。

## 5. Compile gate 政策

- `FrontendCompileCheckAnalyzer.walkExpression` 对三元无显式 case，落入 `default`：`markCompileSurfaceNode` + `rememberBareCallCallee` + `walkNestedExpressionChildren`（`getChildren()=[condition,left,right]` 递归覆盖嵌套三元）。
- generic `scanExpressionTypeCompileBlocks` 保持不变：未稳定的 conditional fact 仍按 `BLOCKED/DEFERRED/FAILED/UNSUPPORTED` 阻断（fail-closed 兜底）。
- 诊断去重零 gate 改动：依赖 §2 binary 式 root 重持有 + `hasPublishedConflictingDiagnosticAt` exact-range 冲突去重。**明确禁止：** 扩展 `isCoveredByPropagatedValueOperandCompileBlock` 的 AST-kind 硬编码（维持 cast/type-test-only ownership 不变量）。
- 当前显式表达式 intercept 仅剩 `PreloadExpression` / `GetNodeExpression`（见 `frontend_compile_check_analyzer_implementation.md`）。

## 6. Body lowering 与 backend

- merge 槽 `cfg_merge_*` 是普通 LIR 变量，backend 无 `cfg_merge_` 特判。
- **生命周期合同：** destroyable 合并类型（String/Array/object）的 merge 槽与 source-local / `cfg_tmp_*` 同策略——函数头声明 + `__prepare__` 默认构造、每互斥臂 destroy-then-write、`__finally__` 统一销毁，无漏毁（见 `frontend_lowering_cfg_pass_implementation.md` §6.1）。若发现缺毁，按 `cfg_tmp_*` 同策略修复，不特判三元。
- condition 归一化复用 `emitConditionBranch`：`bool` 直传 / `Variant→unpack` / 其他 stable 类型 `pack+unpack`；`go_if` 保持 bool-only；sema/type-check 对三元 condition 不做 strict-bool 收紧（Godot-compatible，与 statement condition 合同一致，见 `frontend_rules.md`）。
- 无新 LIR 指令：复用 `GoIfInsn` / `AssignInsn` / boundary (un)pack / intrinsic cast。
- `FrontendSequenceItemInsnLoweringProcessors` 对 `ConditionalExpression` 的 opaque `DEFER` 分类保留为护栏（当前路径下三元永不产生 `OpaqueExprValueItem`）。

## 7. 核心实现落点

均相对 `src/main/java/gd/script/gdcc/`：

- `frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`：`resolveConditionalExpressionType` / `resolveConditionalMergedType`。
- `frontend/sema/analyzer/FrontendBodyOwnerProcedures.java`：`BodyExpressionResolver.computeExpressionType` 的 `case ConditionalExpression`。
- `frontend/lowering/cfg/FrontendCfgGraphBuilder.java`：`buildConditionalExpressionValue` / `buildConditionalExpressionCondition`。
- `frontend/lowering/cfg/FrontendCfgGraph.java`：`validateMergeSourceContracts` / `validateValueProducerContracts`。
- `frontend/lowering/FrontendBodyLoweringSupport.java`：`requireProducedValueMaterialization`（merge anchor 化）。
- `frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`：`FrontendMergeValueInsnLoweringProcessor`（`merge_write`）。
- `frontend/lowering/pass/body/FrontendCfgNodeInsnLoweringProcessors.java`：`FrontendBranchNodeInsnLoweringProcessor.emitConditionBranch`。
- `frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`：`walkExpression` default 递归与 exact-range 去重。
- Godot：`modules/gdscript/gdscript_analyzer.cpp` `reduce_ternary_op`。gdparser：`frontend/ast/ConditionalExpression.java`、grammar `conditional_expression`。

## 8. 回归锚点

Focused tests（compile-fail 场景锚定在 frontend focused tests，不进入 test_suite）：

- `FrontendConditionalParseBehaviorTest` — parser 形状：字段映射、右结合、括号左结合、最低优先级、语句位、缺 `else`/缺臂负例。
- `FrontendExpressionSemanticSupportTest` — 类型合并矩阵（§2 表）、诊断归属（FAILED 臂 arm+root 各一条不同 range；void 臂仅 root 一条 route）、expectedType 透传、嵌套类型。
- `FrontendCompileCheckAnalyzerTest` — 支持面三元零 `sema.compile_check`、FAILED 臂 exact-range 去重、void 臂 route 压掉 compile_check；显式拦截计数锚定 Preload/GetNode。
- `FrontendCfgGraphBuilderTest` / `FrontendCfgGraphTest` — 双语境图形状、双生产者 merge、嵌套多级（含 value 语境 `and/or` 臂）、merge-of-merge 合法化、悬空 source fail-fast。
- `FrontendBodyLoweringSupportTest` — merge 槽 anchor 定类型、非表达式 anchor fail-fast。
- `FrontendLoweringBuildCfgPassTest` — executable body 与 property initializer 双语境整链发布。
- `FrontendLoweringBodyInsnPassTest` — LIR 形状：同型/异型臂 `merge_write`（`int→float` intrinsic cast 在 int 臂 merge 写入前）、condition 归一化、condition 语境零 `cfg_merge_`、语句位丢弃、`var x := 三元` slot stabilization。
- 既有短路 `and/or` 测试零修改通过（LIR 形状不变硬验收，§9 D5）。

test-suite e2e 对（`src/test/test_suite/unit_test/{script,validation}/ternary/`，9 对，Zig + Godot 实跑）：

- `basic_same_type`、`mixed_int_float`、`nested_associativity`、`object_ancestor_merge`、`null_arm`、`non_bool_condition`、`statement_position_discard`、`condition_context`、`destroyable_arms`。

运行锚点：

```text
pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendConditionalParseBehaviorTest,FrontendCompileCheckAnalyzerTest,FrontendCfgGraphBuilderTest,FrontendCfgGraphTest,FrontendLoweringBuildCfgPassTest,FrontendLoweringBodyInsnPassTest
```

## 9. 决策记录（与 Godot 语义对齐）

- D1：合并类型复用 GDCC ordinary boundary 矩阵（`determineFrontendBoundaryDecision.allows()`）替代 Godot `is_type_compatible`；差集为矩阵未覆盖的 Godot 宽兼容形态，按 MVP 一致性原则接受。
- D2：Godot 对无公共类型双臂发 `INCOMPATIBLE_TERNARY` warning；当前只回退 `Variant` 不发 warning（避免新增诊断 category 与 `diagnostic_manager.md` 同步面），列为后续可选项。
- D3：`void` 臂三元按显式 `UNSUPPORTED` feature boundary 处理（error），不做语句位丢弃特判；`void`+`Variant` 为 `DYNAMIC(Variant)`（运行时产出 Variant nil）。
- D4：不做常量折叠、不做 flow-sensitive 收窄（与现有 `if` 一致）。
- D5：merge 槽类型 anchor 化是对 `MergeValueItem` 共享合同的精炼而非分叉；`and/or` 的 LIR 形状不变（仍 `LiteralBoolInsn` + `AssignInsn(cfg_merge_*, cfg_tmp_*)`，无 pack/unpack/intrinsic）是硬验收。

## 10. 长期维护约束与文档同步要求

- 类型合并矩阵（§2）变化时，必须同步本文档、`frontend_rules.md` 与 `FrontendExpressionSemanticSupportTest` 矩阵用例。
- `MergeValueItem` 合同（§3）变化时，必须同步本文档、`frontend_lowering_cfg_pass_implementation.md`、`frontend_lowering_(un)pack_implementation.md`（consumer 登记），并以 `and/or` LIR 形状不变为硬验收。
- condition 语境展开形态（§4.2）变化时，必须同步本文档与 `frontend_rules.md` 的"merge result id 禁止作 branch condition id"条目。
- merge 槽生命周期（§6）变化时，必须同步本文档与 `frontend_lowering_cfg_pass_implementation.md` §6.1。
- 支持面变化时，必须同步 `README.md` / `README.zh-CN.md` 支持面清单。
