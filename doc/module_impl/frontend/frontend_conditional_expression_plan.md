# Frontend Conditional（Ternary）Expression 实施计划

本文档是三元表达式 `value1 if condition else value2`（含嵌套）进入 compile-ready 支持面的实施计划与验收细则。
状态：**Phase 0、1、2、3 已完成**；Phase 4–5 待实施。

## 1. 目标与范围

- 支持 GDScript 三元表达式 `left if condition else right` 及其右结合嵌套（`a if c1 else b if c2 else d`）。
- 覆盖 compile-ready 全链路：shared sema 类型推导 → compile gate 放行 → CFG 构图 → body lowering → C backend 运行。
- 对齐 Godot 语义（参考 `godotengine/godot` `modules/gdscript/gdscript_analyzer.cpp` `reduce_ternary_op`）：
  - 先归约 `condition`，再归约两个分支表达式；运行时只求值被选中的分支（短路与 `and/or` 一致的单臂求值）。
  - 结果类型：任一分支为 Variant 则结果为 Variant；否则若 `right` 可赋给 `left` 取 `left` 类型，否则若 `left` 可赋给 `right` 取 `right` 类型，否则回退 Variant（Godot 额外发 `INCOMPATIBLE_TERNARY` warning，MVP 不发，见 §8 决策点 D2）。
- 不在本期范围：常量折叠（Godot 会在 condition 与双臂均常量时折叠，`is_constant`/`reduced_value`）；flow-sensitive 类型收窄（与现有 `if` 保持一致，不收窄）；`assert`/`preload`/`get_node` 仍保持 compile gate 拦截不变。

## 2. 现状与证据链

parser 已就绪，无需改动 gdparser：

- AST 节点 `dev.superice.gdparser.frontend.ast.ConditionalExpression`（record：`condition/left/right/range`）已存在于 `gdparser-0.5.3.jar`。
- 语法定义（`E:/Projects/gdparser/vendor/tree-sitter-gdscript/grammar.js:743-753`）：`conditional_expression` 为 `prec.right(PREC.conditional=-1)`，fields `left`/`condition`/`right`；`left`=`if` 前表达式（真值臂），`right`=`else` 后表达式（假值臂）。
- 映射（`E:/Projects/gdparser` 仓库 `src/main/java/dev/superice/gdparser/frontend/lowering/CstToAstMapper.java:669-678`）：原样透传，无字段交换。
- 结合性：`prec.right` 保证右结合：`a if c1 else b if c2 else d` 解析为 `a if c1 else (b if c2 else d)`；`PREC.conditional=-1` 为最低优先级，故 `a + b if c else d` = `(a+b) if c else d`；`(a if c1 else b) if c2 else d` 需括号。

GDCC 处理状态（下表为计划起草时快照；最新进度以状态头与各 Phase done notes 为准）：

| 层 | 位置（均相对 `src/main/java/gd/script/gdcc/`） | 现状 |
|---|---|---|
| sema 类型推导 | `frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java:714-720` | Phase 1 已落地：`resolveConditionalExpressionType` 发布合并类型（起草时为恒 `DEFERRED`） |
| body owner 分发 | `frontend/sema/analyzer/FrontendBodyOwnerProcedures.java:1645` | Phase 1 已落地：`computeExpressionType` 专用 `case ConditionalExpression`（起草时为 default deferred） |
| compile gate | `frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java:572-575`（+`157-160` 消息） | `walkExpression` 显式 `sema.compile_check` 拦截，不递归子树（Phase 4 解封） |
| CFG 构图 | `frontend/lowering/cfg/FrontendCfgGraphBuilder.java:776-777, 870-874, 1494-1575` | Phase 3 已落地：value 语境 branch-result merge / condition 语境纯控制流展开（起草时为 `throw unsupportedConditionalExpression`） |
| sequence item lowering | `frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java:310` | opaque 分类对 `ConditionalExpression` 标 `DEFER`（护栏，保留） |
| 规则文档 | `frontend_rules.md`（compile intercept 清单条目）、`frontend_compile_check_analyzer_implementation.md` §3.3 | 显式列为 temporary compile intercept，解除前提为"CFG merge 合同稳定" |
| 测试锚 | `FrontendCompileCheckAnalyzerTest.java:1087-1130`（`sema/analyzer/`）；`FrontendExpressionSemanticSupportTest.java:1277`（`sema/analyzer/support/`） | 仅锁定"拦截/deferred"现状，实施时需改写 |

关键既有机制（三元复用基础）：

- **value 上下文短路 `and/or`**：`FrontendCfgGraphBuilder.buildShortCircuitBinaryValue`（`1369-1430`）——分配共享 `resultValueId` + `mergeSequence`，双臂各建独立 `OpenSequence`，臂内发布值后由 `MergeValueItem` 写入共享结果 id，双臂 `publishSequenceNode(..., mergeSequence.id())` 合流。
- **`MergeValueItem`**（`lowering/cfg/item/MergeValueItem.java`）：唯一合法的多生产者 value id 形态；`FrontendCfgGraph.validateValueProducerContracts`（`202-234`）要求多生产者必须全为 `MergeValueItem`；`validateMergeSourceContracts`（`239-261`）要求 merge 的 `sourceValueId` 在**同一 SequenceNode** 内先于它产生。
- **merge 槽类型收集**：`FrontendBodyLoweringSupport.requireProducedValueMaterialization`（`frontend/lowering/FrontendBodyLoweringSupport.java:191-195`）目前对 `MergeValueItem` 取 `sourceValueId` 的类型；同一结果 id 的多生产者 materialization 不一致会 fail-fast（`collectProducedValueMaterialization` 的 `putIfAbsent` + 冲突检查，`155-177`）。
- **merge 槽写入 lowering**：`FrontendMergeValueInsnLoweringProcessor`（`frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java:243-263`）目前是无转换的 `AssignInsn(cfg_merge_<id>, slotFor(source))`。
- **condition 分支 lowering**：`FrontendBranchNodeInsnLoweringProcessor.emitConditionBranch`（`frontend/lowering/pass/body/FrontendCfgNodeInsnLoweringProcessors.java:90-125`）按 `cfgTempSlotId(conditionValueId)` 读取 condition 值并统一做 `bool` 直传 / `Variant→unpack` / 其他 stable 类型 `pack+unpack` 归一化——**其输入必须是 temp 槽产生的值**，这是 condition 语境三元采用纯控制流展开的直接原因（§3.3）。
- **普通 typed boundary 唯一入口**：`FrontendBodyLoweringSession.materializeFrontendBoundaryValue`（`824-929`），decision 由 `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision` 给出；同型 `ALLOW_DIRECT` 直接返回源槽（零行为变化），`int→float` 走 `ALLOW_WITH_INTRINSIC_CAST`，`Nil→object` 走 `ALLOW_WITH_LITERAL_NULL`，具体类型→Variant 走 `ALLOW_WITH_PACK`。
- **condition 归一化**：`go_if` 保持 bool-only；`FrontendCfgNodeInsnLoweringProcessors.FrontendBranchNodeInsnLoweringProcessor.emitConditionBranch`（`90-125`）统一做 `bool` 直传 / `Variant→unpack` / 其他 stable 类型 `pack+unpack` 归一化。三元 `condition` 复用此合同，sema/type-check 不做 strict-bool 收紧（Godot-compatible，见 `frontend_rules.md` condition 合同条目）。

## 3. 总体设计

### 3.1 类型推导（shared sema）

新增 `FrontendExpressionSemanticSupport.resolveConditionalExpressionType(ConditionalExpression, ContextualNestedExpressionResolver, boolean)`：

1. 按求值顺序先解析 `condition`（无 expectedType）；`firstNonResolvedDependency(condition)` 命中 → `propagated`（同 binary/unary 模式）。
2. 双臂经 contextual resolver 解析并透传外层 `expectedType`（使 `var x: Array[int] = [1] if c else [2]` 的双臂容器字面量获得 contextual typing，模式同 `resolveCastExpressionType` 的 target-first 透传）：任一臂不稳定 → `propagated`。
3. 三者均稳定后计算合并类型 `resolveConditionalMergedType(leftType, rightType)`：
   - 任一臂 `status==DYNAMIC` 或 published type 为 exact `Variant` → 结果 `DYNAMIC(Variant)`（runtime-open，与 unary/binary 的 Variant 处理一致）。
   - 双臂均为 `RESOLVED` 具体类型：
     - `determineFrontendBoundaryDecision(classRegistry, right, left).allows()` → 合并为 `left`；
     - 否则 `determineFrontendBoundaryDecision(classRegistry, left, right).allows()` → 合并为 `right`；
     - 否则 → `RESOLVED(Variant)`。
   - 该规则即 Godot 双向 `is_type_compatible` 归并，但"可赋给"关系统一复用 GDCC ordinary typed boundary 矩阵（单一真源），从而 `int/float→float`、子类→父类、`null→object`、容器协变全部与赋值边界语义一致。
   - 任一臂 published type 为 `void` → `rootOutcome(UNSUPPORTED)`（显式 feature boundary：merge 槽禁止 void 物化；`x() if c else y()` 语句位丢弃语义不在 MVP）。
   - 双臂 Nil（`null if c else null`）→ `RESOLVED(Nil)`（`determineFrontendBoundaryDecision(Nil, Nil)` 因 typeName 相等为 `ALLOW_DIRECT`），Phase 1 即锁定该行为，不因物化顾虑回退 Variant。
4. **诊断归属采用 binary 式 root 重持有**（审阅修正：原计划"propagated 不重复发诊断 + rootOwnsOutcome=false"会与 compile gate 不变量冲突，见下）：wrapper 不记录 `rootOwnsExpressionDiagnostics`（缺省 root-owned=true），propagated 状态在 conditional root 以同 status+detailReason 重发 `sema.expression_resolution`（与 binary/unary/call 完全一致——`frontend_compile_check_analyzer_implementation.md` 的 ownership 不变量明确指出只有 cast/type-test 才使用 rootOwns=false 模式）；compile gate 侧由 `reportCompileBlock` 内置的 `hasPublishedConflictingDiagnosticAt` exact-range 冲突去重覆盖（`FrontendCompileCheckAnalyzer.java:992-1011`），arm 与 root 的 generic `sema.compile_check` 均被各自 range 上的 upstream error 压掉，**零 gate 改动、零双报**。无附加 side-table 产物（不需要 plan/target 类附表）。

`BodyExpressionResolver.computeExpressionType` 新增专用 `case ConditionalExpression` 分支（置于 `Cast/TypeTest/Array/Dict` 同级），经私有包装方法以 `this::resolveExpressionTypeExpected` 透传 `expectedType`，直接返回 `.expressionType()`（**不**触碰 `rootOwnsExpressionDiagnostics`，同 `BinaryExpression` 分支形态）；`resolveRemainingExplicitExpressionType` 中删除 `ConditionalExpression` 的 deferred 分支，并将其加入末尾"必须使用专用 resolver"穷举白名单。

### 3.2 `MergeValueItem` 合同精炼（共享合同，and/or 行为不变）

现状两个限制使异型双臂无法合并：(a) merge 槽类型取自 `sourceValueId`，双臂异型触发 materialization 冲突 fail-fast；(b) merge 写入是无转换 `AssignInsn`；(c) `validateMergeSourceContracts` 禁止 merge 的 source 来自其他 sequence（嵌套三元的臂值是内层 merge 结果，产生自别的 sequence）。

精炼为三小步（前两步保持 `and/or` LIR 形状不变：`bool→bool` 是 `ALLOW_DIRECT`，仍只产 `LiteralBoolInsn` + `AssignInsn(cfg_merge_*, cfg_tmp_*)`，不出现 pack/unpack/intrinsic）：

1. **merge 槽类型改由 anchor 表达式类型决定**：`requireProducedValueMaterialization` 的 `MergeValueItem` 分支改为读取 `mergeAnchor`（必须为已发布 `RESOLVED/DYNAMIC` 类型的 `Expression`，否则 fail-fast programmer error）的 `expressionTypes` 事实。`and/or` anchor 是 `BinaryExpression`（`RESOLVED(bool)`），三元 anchor 是 `ConditionalExpression`（合并类型），两者均为"整条分支表达式的 outward-facing 类型"，语义更正确且天然消除多生产者冲突（同一 anchor 同一类型）。
2. **merge 写入经统一 boundary 物化**：`FrontendMergeValueInsnLoweringProcessor` 改为 `materializeFrontendBoundaryValue(block, slotFor(source), sourceType, mergeType, "merge_write")` + `AssignInsn(cfg_merge_<id>, materialized)`。`and/or` 的 `bool→bool` 为 `ALLOW_DIRECT`，LIR 形状不变；三元异型臂（如 `int` 臂写入 `float` 合并槽）在此完成转换，符合"(un)pack 单入口"合同。注意 `merge_write` 是 `materializeFrontendBoundaryValue` 的 **re-derive consumer**（与 assignment/return/local-init 同类，现场调 `determineFrontendBoundaryDecision`），不是 frozen-decision consumer；§6 文档同步时按此口径登记，不暗示存在已发布的冻结 decision。
3. **`validateMergeSourceContracts` 窄化放宽**：merge 的 `sourceValueId` 允许 (a) 同 sequence 内已产生（现状），或 (b) **graph 全局生产者索引**中该 id **至少存在 1 个生产者**且**全部**为 `MergeValueItem`（merge-of-merge，支撑嵌套三元臂与 value 语境 `and/or` 臂）。实现必须先建与 `validateValueProducerContracts` 同构的 graph-wide producer 索引再判定，禁止用局部 `locallyPublishedValueIds` 之外的空流 `allMatch`（空集会真空为 true，导致悬空 source 漏过 fail-fast）。merge 槽类型不再依赖 source 的收集顺序（见第 1 步），原规则的动机已被覆盖；其余校验不变。

### 3.3 CFG 构图

实现 `FrontendCfgGraphBuilder` 两个 stub（签名补齐 `cursor`/targets/`preferredResultValueId`）：

- `buildConditionalExpressionValue(cursor, expr, preferredResultValueId)`：
  - `resultValueId = chooseResultValueId(preferredResultValueId)`；建 `mergeSequence`、`trueArmCursor`、`falseArmCursor`（各持独立 `OpenSequence`）。
  - `conditionBuild = buildCondition(cursor, expr.condition(), trueArm.entryId(), falseArm.entryId())`——`condition` 中的 `not`/`and/or`/嵌套三元全部由现有 condition 机制处理。
  - 每臂：`armBuild = buildValue(armCursor, armExpression, null)`；在 `armBuild.cursor().currentSequence()` 末尾追加 `MergeValueItem(expr, armBuild.resultValueId(), resultValueId)`；`publishSequenceNode(armFinalSeq.id(), items, mergeSequence.id())`。
  - 返回 `ValueBuild(new BuildCursor(conditionBuild.entryId(), mergeSequence), expr, resultValueId, null)`。
  - 嵌套三元臂：`buildValue` 递归进入内层 `buildConditionalExpressionValue`，内层 merge sequence 即外层臂的 final sequence，`MergeValueItem` 追加其中——由 §3.2 第 3 步放宽保障合法性；每级嵌套持有独立 `resultValueId`，不违反单定义合同。
- `buildConditionalExpressionCondition(cursor, expr, trueTargetId, falseTargetId)`：**纯控制流展开，不产生 merge 值**（审阅修正：原计划"走 value 路径再 `publishConditionBranch`"不可行——`FrontendBranchNodeInsnLoweringProcessor.emitConditionBranch` 在 `FrontendCfgNodeInsnLoweringProcessors.java:98` 写死 `cfgTempSlotId(conditionValueId)`，而 value 路径的结果是 `cfg_merge_<id>` 槽，会读到从未赋值的 `cfg_tmp_`；且 `frontend_rules.md` 明确禁止把 outward-facing merge result id 当作 branch condition id）。展开形状为：
  ```java
  var trueArmCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
  var falseArmCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
  var conditionBuild = buildCondition(cursor, expr.condition(), trueArmCursor.entryId(), falseArmCursor.entryId());
  buildCondition(trueArmCursor, expr.left(), trueTargetId, falseTargetId);
  buildCondition(falseArmCursor, expr.right(), trueTargetId, falseTargetId);
  return conditionBuild;
  ```
  即 `if (a if c else b):` ≡ 先测 `c`，再对被选臂做 truthiness 分支。两臂经 `buildCondition` 递归分发：`not`/`and/or`/嵌套三元臂全部复用现有 condition 机制（嵌套三元臂继续纯控制流展开，永不产生 merge id 作为 `conditionValueId`）；普通臂走 `buildConditionFromValue`，其值必为 temp 槽，`emitConditionBranch` 与 truthiness 归一化零改动。各臂 `BranchNode.conditionRoot` 由 `publishConditionBranch` 按 fragment 对齐规则自行处理。
- 更新 `buildValue`/`buildCondition` 两处 case 调用点传参（补 `cursor`/`preferredResultValueId`/`trueTargetId`/`falseTargetId`）；删除 `unsupportedConditionalExpression` 或改为不可达护栏。
- 不新增 CFG item 类型，不发布 `Region`（与 value 上下文 `and/or` 一致）。

### 3.4 compile gate 解封

- `FrontendCompileCheckAnalyzer.walkExpression` 删除 `case ConditionalExpression -> reportExplicitCompileBlock(...)`（含 `conditionalCompileBlockedMessage()` 与不再需要的 import），使三元落入 `default`：`markCompileSurfaceNode` + `walkNestedExpressionChildren`（`getChildren()=[condition,left,right]` 递归覆盖）。
- generic `scanExpressionTypeCompileBlocks` 保持不变：未稳定的 conditional fact 仍按 `BLOCKED/DEFERRED/FAILED/UNSUPPORTED` 阻断（fail-closed 兜底）。
- 诊断去重**零 gate 改动**：因 §3.1 采用 binary 式 root 重持有（不记录 `rootOwnsOutcome=false`），conditional root 的 propagated fact 自带同 range 的 upstream `sema.expression_resolution` error，`reportCompileBlock` 的 `hasPublishedConflictingDiagnosticAt` exact-range 去重直接覆盖；**不**扩展 `isCoveredByPropagatedValueOperandCompileBlock` 的 AST-kind 硬编码（维持 `FrontendCompileCheckAnalyzer.java:880-884` 记载的 ownership 不变量）。

### 3.5 不在改动范围

- `FrontendTypeCheckAnalyzer`：condition 不做 strict-bool 检查（Godot-compatible 合同对 statement condition 与三元 condition 一致）；臂内嵌套 `CastExpression` 已由 `visitNestedCastExpressions` 经 `getChildren()` 递归覆盖。
- `FrontendSequenceItemInsnLoweringProcessors.java:310` 的 opaque `DEFER` 分类保留为护栏（新路径下三元永不产生 `OpaqueExprValueItem`）。
- 后端：无新 LIR 指令；`GoIfInsn`/`AssignInsn`/boundary (un)pack/intrinsic cast 均复用。
- gdparser：零改动。

## 4. 分步实施计划

> 每一步含"改动内容"与"验收细则"。测试命令统一使用 `pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests <Classes>`；全量验收用 `./gradlew clean build --no-daemon --info --console=plain`。

### Phase 0：基线与解析锚点测试

状态：**已完成**。产出 `src/test/java/gd/script/gdcc/frontend/parse/FrontendConditionalParseBehaviorTest.java`（经 `GdScriptParserService` 解析真实源码）。本步未改 parser / sema / CFG / compile gate。

改动：
- 新增上述测试类：锚定 `ConditionalExpression` 的 `left/condition/right` 字段映射、右结合嵌套、括号强制左结合、最低优先级（`a + b if c else d` 的 `left` 为 `BinaryExpression`）。本仓库此前无三元解析回归锚点（gdparser 侧 `CstToAstMapperTest` 未覆盖）。

已落地的断言（正/反）：
- 字段映射：`yes if flag else no` → `left=yes` / `condition=flag` / `right=no`；`getChildren()=[condition, left, right]`（record 组件顺序 ≠ 源码顺序）。
- 右结合：`a if c1 else b if c2 else d` 内层挂在 `outer.right`。
- 括号左结合：`(a if c1 else b) if c2 else d` 内层挂在 `outer.left`（mapper 剥括号，无 `ParenthesizedExpression`）。
- 显式右括号：`a if c1 else (b if c2 else d)` 与无括号右结合同构。
- 最低优先级：`+` / `or` / `not` / `as` 均绑在 `left`；假值臂 `a if c else d + e` 的 `right` 为 `BinaryExpression("+")`；`or` 出现在 condition 槽时不被三元吸收。
- 语句位：`if a if c else b:` 的 `IfStatement.condition` 为三元；同函数后续 `return` 仍解析。
- 负例：缺 `else`、缺真值臂均发 `parse.lowering` ERROR（tolerant，不崩）。

验收：
- `pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendConditionalParseBehaviorTest,FrontendCastParseBehaviorTest,FrontendContainerLiteralParseBehaviorTest,FrontendAnnotationParseBehaviorTest,FrontendParseSmokeTest` 通过。
- `./gradlew classes --no-daemon --info --console=plain` 编译通过。

### Phase 1：sema 类型推导

状态：**已完成**。sema 三元类型推导落地，compile gate 仍拦截（Phase 4 解封）；CFG/lowering 未动。

改动：
- `FrontendExpressionSemanticSupport`：新增 `resolveConditionalExpressionType` + 私有静态 `resolveConditionalMergedType`；`resolveRemainingExplicitExpressionType` 移除 `ConditionalExpression` deferred 分支并更新穷举白名单。
- `FrontendBodyOwnerProcedures.BodyExpressionResolver`：`computeExpressionType` 新增 `case ConditionalExpression`（contextual 透传 `expectedType`），包装方法**不**记录 `rootOwnsExpressionDiagnostics`（binary 式 root 重持有）。
- 测试：
  - `FrontendExpressionSemanticSupportTest` 新增合并类型矩阵用例：同型（`String/String`）、`int/float→float`（双向顺序各一例，恒为 `float`）、对象子/父类→父类（双向顺序）、`null/object→object`、双臂 `Nil→RESOLVED(Nil)`、无公共类型（如 `int`/`String`）→`Variant`、**`Array[int]`/`Array[float]`→`Variant`**（容器元素不递归标量 widening，双向 REJECT）、**`String`/`StringName`→恒为 `left`**（双向均 `ALLOW_WITH_BUILTIN_CONSTRUCTOR`，先匹配方向胜出，锁左偏向性）、任臂 `Variant` 或 `DYNAMIC`→`DYNAMIC`、`void` 臂→`UNSUPPORTED`。
  - 诊断归属（binary 式 root 重持有，按 status 分category）：condition 或臂 `FAILED`（如未绑定标识符）时 arm/condition 自身一条 + conditional root 一条 `sema.expression_resolution`（不同 range）；`void` 臂为 root 自身 `UNSUPPORTED`——仅 root 一条 `sema.unsupported_expression_route`（臂本身 `RESOLVED(void)` 无诊断），两者不得混写断言。
  - 覆盖 `expectedType` 透传：`var x: Array[int] = [1] if c else [2]` 双臂容器 plan 为 contextual typed。
  - 嵌套三元类型：内层结果类型参与外层合并。
  - negative 三件套（`frontend_rules.md` 测试约定）：错误 category 锚定、坏子树跳过、**同函数后续合法语句仍正常发布类型**（如 `var x = missing if c else 1` 后 `var y = 1` 不受影响）。
  - 改写既有 deferred 回归用例（`FrontendExpressionSemanticSupportTest.java:1277` 附近）为新行为。

验收：
- 上述测试全部通过；`analyze(...)` 共享路径对 `1 if c else 2` 发布 `RESOLVED(int)`，无 `sema.deferred_expression_resolution`。
- `FrontendExpressionSemanticSupportTest`、`FrontendTypeCheckAnalyzerTest`、`FrontendVariableAnalyzerTest` 不回归。

落地说明（Phase 1 已完成）：
- `resolveConditionalExpressionType` 实际签名为 `(ConditionalExpression, ContextualNestedExpressionResolver, boolean finalizeWindow, @Nullable GdType expectedType)`：`expectedType` 必须显式透传给双臂才能满足 §3.1 第 2 条与 contextual 容器测试，故在 §3.1 书写的三参签名基础上补充该参数（owner 包装以 `this::resolveExpressionTypeExpected` + 外层 `expectedType` 调用）。
- `resolveConditionalMergedType` 判定顺序与 §3.1 一致：先 runtime-open（`DYNAMIC`/exact `Variant`→`DYNAMIC(Variant)`），再 `void` 臂→`UNSUPPORTED`，再双向 `determineFrontendBoundaryDecision` 归并（先 `right→left` 命中取 `left`，否则 `left→right` 命中取 `right`，否则 `RESOLVED(Variant)`）。`Nil/Nil` 经 `checkAssignable(Nil,Nil)` 同名为 `ALLOW_DIRECT` 自然落到 `RESOLVED(Nil)`。
- `void`+`Variant` 边界已确认为 `DYNAMIC(Variant)`（非待复评项）：void 臂在运行时产出 Variant 的 `nil`，Variant 合并槽可承载，故该对是 runtime-open 而非 `UNSUPPORTED`。`void` 臂只有与**非 Variant** 具体类型配对时才落 `UNSUPPORTED`（具体合并槽无法承载 void 臂的 `nil`）。Phase 2 merge 槽物化对 void 臂按「产出 Variant nil」处理，不得反向收紧为拒绝。
- 除计划点名的 `FrontendExpressionSemanticSupportTest` route 用例外，另有三处既有测试以三元作为「deferred 参数/未支持 kind」代理，随 Phase 1 一并改写为仍 deferred 的 `preload` 代理或新解析行为：`FrontendBodyOwnerProceduresChainBindingTest.analyzeKeepsDeferredArgumentBoundaryForRemainingUnsupportedExpressionKinds`、`FrontendCompileCheckAnalyzerTest.analyzeForCompileUpgradesDeferredWarningsIntoCompileBlockingErrors`、`FrontendBodyOwnerProceduresExprTypeTest` 原 `analyzeReportsExprOwnedDeferredDiagnosticsForRemainingGenericMvpGaps`（改写为 `analyzePublishesConditionalExpressionTypesWithoutDeferredDiagnostics`）。
- compile gate（`FrontendCompileCheckAnalyzer.walkExpression` 的 `case ConditionalExpression`）与 CFG/lowering 未改，三元在 compile 模式仍被显式封口；`FrontendCompileCheckAnalyzerTest` 的去重用例与 `FrontendLoweringAnalysisPassTest` 的 `Conditional expression` 拦截用例因此继续通过。

### Phase 2：`MergeValueItem` 合同精炼

改动：
- `FrontendBodyLoweringSupport.requireProducedValueMaterialization`：`MergeValueItem` 分支改为 anchor 表达式类型（新增 `requireExpressionAnchor` 护栏）。
- `FrontendMergeValueInsnLoweringProcessor`：merge 写入改走 `materializeFrontendBoundaryValue`（re-derive consumer）。
- `FrontendCfgGraph.validateMergeSourceContracts`：按 §3.2 第 3 步放宽 merge-of-merge（graph-wide 生产者索引 + 至少 1 个生产者 + 全部 `MergeValueItem`）。
- 文档同步：`frontend_lowering_cfg_pass_implementation.md` 的 merge 合同章节（含 §9 remaining 清单）、`frontend_rules.md` 中 merge 相关条目。
- 测试：
  - 现有短路 `and/or` 测试（`FrontendLoweringBodyInsnPassTest` 930-1028、`FrontendCfgGraphBuilderTest`）必须**零修改通过**（证明 LIR 形状不变：仍 `LiteralBoolInsn` + `AssignInsn(cfg_merge_*, cfg_tmp_*)`，无 pack/unpack/intrinsic）。
  - merge-of-merge 放宽的验收**不能**用嵌套 `and/or`（value 语境 `and/or` 的左右操作数走 `buildCondition` 纯分支展开，现有构图根本产生不了 merge-of-merge 形状）；必须手写 `FrontendCfgGraph` 构造单测：两臂 `MergeValueItem` 写 `V_inner`，另一 sequence 的 `MergeValueItem` 以 `V_inner` 为 source（合法）；外加悬空 source（图中无任何生产者）仍 fail-fast 的 negative 用例。
  - 既有 negative `FrontendCfgGraphTest.constructorRejectsMergeSourceWithoutEarlierProducerInSameSequence`（约 `:382-416`，cross-sequence source 为 `BoolConstantItem`/`OpaqueExprValueItem`）在新规则 (b) 下**必须继续失败**（其全局生产者并非全是 `MergeValueItem`）——零修改保持红色语义，防止放宽被误实现成"接受任意跨 sequence merge source"。

验收：
- 既有 CFG/body lowering 测试全绿（零修改）是本步核心验收。
- `MergeValueItem` anchor 非表达式或无 published 类型时 fail-fast 的单测通过。


Phase 2 done notes:
- FrontendBodyLoweringSupport collectCfgValueMaterializations MergeValueItem now uses requireMergeAnchorType anchored at expressionTypes; and-or anchor BinaryExpression(RESOLVED bool), ternary anchor ConditionalExpression merged type.
- FrontendMergeValueInsnLoweringProcessor now materializes via session.materializeFrontendBoundaryValue(..., merge_write) then AssignInsn(cfg_merge_*, materialized); bool to bool ALLOW_DIRECT keeps LIR shape.
- FrontendCfgGraph.validateMergeSourceContracts relaxed to graph-wide merge-of-merge (at least 1 producer and all MergeValueItem), guards empty allMatch vacuum; dangling source still fail-fast.
- Docs synced: frontend_lowering_cfg_pass_implementation value id contract, frontend_lowering_(un)pack consumer list, frontend_rules single-definition and slot contracts.
- Tests anchored: FrontendCfgGraphTest.constructorAllowsMergeOfMergeAndRejectsDanglingOrNonMergeSources, FrontendBodyLoweringSupportTest.collectCfgValueMaterializationsAnchorsMergeSlotsByExpressionType / RejectsNonExpressionMergeAnchorOrMissingFact; existing and-or and constructorRejectsMergeSourceWithoutEarlierProducerInSameSequence stay red.

### Phase 3：CFG 构图实现

状态：**已完成**。`FrontendCfgGraphBuilder` 两种语境构图落地，compile gate 仍拦截（Phase 4 解封）；`FrontendLoweringBuildCfgPassTest` 未动。

改动：
- `FrontendCfgGraphBuilder`：实现 `buildConditionalExpressionValue` / `buildConditionalExpressionCondition`（§3.3），更新两处调用点，移除/改造 `unsupportedConditionalExpression`。
- 测试：
  - **本步构图单测只进 `FrontendCfgGraphBuilderTest`**（其 helper 走 `analyzeSharedSemanticFunction`，不经 compile gate）**与 `FrontendCfgGraphTest`**（手写图校验）；`FrontendLoweringBuildCfgPassTest` 的 `prepareContext` 走 `analyzeForCompile`，在本步 gate 仍拦截三元时其 `assertFalse(hasErrors())` 必然失败，相关整链断言挪到 Phase 4。
  - `FrontendCfgGraphBuilderTest`：图形状锚定——1 个 `BranchNode`（condition）+ 两臂 sequence 各以 `MergeValueItem` 收尾指向同一 merge；`resultValueId` 双生产者均为 `MergeValueItem`；`preferredResultValueId` 透传生效；condition 内 `not`/`and/or` 组合。
  - 嵌套三元：三级 `a if c1 else b if c2 else d` 的图形状与 value id 唯一性；`x if c1 else (y if c2 else z)` 括号形态等价；value 语境 `and/or` 作为臂（`x = (a or b) if c else d`）触发 merge-of-merge 路径。
  - condition 语境：`if (a if c else b):` 的纯控制流展开形状（无 merge 值、两臂各自 `BranchNode`），含臂为嵌套三元与 `and/or` 的递归用例。
- 本步完成后 `analyzeForCompile` 整链尚未放行（compile gate 仍拦截）。

验收：
- 新增图测试通过；既有 CFG 测试不回归。
- 非法形态（如 sema 未发布类型时强行走 builder）仍 fail-fast。

Phase 3 done notes:
- `buildConditionalExpressionValue(cursor, expr, preferredResultValueId)`：`chooseResultValueId` 定共享结果 id（preferred 直接透传，如 `var x = 三元` 的 `x_<n>`），condition 经 `buildCondition` 分发到两臂独立 `OpenSequence`，臂经 `publishMergedConditionalArmSequence`（`buildValue(armCursor, arm, null)` + 末尾追加 `MergeValueItem(conditional, armResult, sharedResult)` 后发布）合流到 merge continuation；返回 `ValueBuild(BuildCursor(conditionBuild.entryId(), mergeSequence), ...)`。嵌套三元 / value 语境 `and/or` 臂返回未发布内层 merge sequence，外层 merge 写入追加同 sequence（merge-of-merge 窄例外的实际服务对象）。
- `buildConditionalExpressionCondition(cursor, expr, trueTargetId, falseTargetId)`：纯控制流展开，先测 condition 再对选中臂 `buildCondition` 递归分发外层 targets，不产生任何 merge 值；注释显式记录 `emitConditionBranch` 读 `cfg_tmp_<conditionValueId>` 的约束（merge 槽不得作 branch condition id）。
- 两处调用点补齐传参；`unsupportedConditionalExpression` 已删除。
- 文档同步：`frontend_lowering_cfg_pass_implementation.md` §5.1（condition 语境三元条目）、§5.2（value 语境三元固定形态）、§9（compile-block 原因更新为"构图已落地、gate/e2e 未完成"）；拦截清单划除仍留待 Phase 5。
- 测试锚定（均走 `analyzeSharedSemanticFunction`，不经 compile gate）：value 基础形状（双 MergeValueItem 同 result/同 anchor、臂 producer 对齐、合流序列唯一、stop 返回合并 id、无 BoolConstant）、preferred id 透传（`x_` 前缀）、condition 含 `not/and` 组合、右结合嵌套（4 merge、merge-of-merge 写入、内外 result id 唯一）、括号左嵌套、`and/or` 臂（merge-of-merge + 双 BoolConstant）；condition 语境三例（基础 / 嵌套三元臂 / `or` 臂）全图零 MergeValueItem 零 BoolConstant、臂 branch targets 对齐 if region；negative：sema FAILED 三元强行走 builder 抛 `IllegalStateException("not lowering-ready")`。
- 验证：`FrontendCfgGraphBuilderTest`（66）+ `FrontendCfgGraphTest`（17）全绿；`gd.script.gdcc.frontend.lowering.*` + `FrontendCompileCheckAnalyzerTest` + `FrontendExpressionSemanticSupportTest` + `FrontendConditionalParseBehaviorTest` 共 563 例无回归（gate 拦截用例继续通过）。

### Phase 4：compile gate 解封（代码侧）+ body lowering 端到端

改动：
- `FrontendCompileCheckAnalyzer`：删除显式拦截（§3.4）。**本步只做 gate 代码删除 + focused 测试**；`frontend_rules.md` / `frontend_compile_check_analyzer_implementation.md` 的拦截清单划除推迟到 Phase 5（见其前置条件）。
- 测试：
  - 改写 `FrontendCompileCheckAnalyzerTest.java:1087-1130` 现状锚：这两测用三元当"仍被显式拦截"的节点来验证 exact-range 去重非空转——解封后三元变 compile-ready 会导致其空转，因此**锚点换成仍拦截的 `preload(...)` / `$Node` 形态**，保住 Preload/GetNode 的去重回归；三元另写新用例。
  - 新增三元 gate 用例：不再发 `sema.compile_check`；臂内含 `FAILED`（如未绑定标识符）时 binary 式重持有生效——arm 与 root 各一条 `sema.expression_resolution`（不同 range），compile gate 经 exact-range 去重**零** `sema.compile_check`；`UNSUPPORTED(void 臂)` 时 root 一条 `sema.unsupported_expression_route`（臂本身 `RESOLVED(void)` 无诊断），root 自带 upstream error 压掉 compile_check。
  - `FrontendLoweringBuildCfgPassTest`：gate 删除后补三元整链（`analyzeForCompile` → CFG 发布）happy path 断言。
  - `FrontendLoweringBodyInsnPassTest`：LIR 形状——`GoIfInsn`（condition 归一化序列：`Variant` condition 经 `unpack`，具体类型 condition 经 `pack+unpack`）+ 两臂 `materialize` + `AssignInsn(cfg_merge_<id>, ...)` + 合流读取；异型臂（`1 if c else 2.0`）的 `int→float` intrinsic cast 出现在 `int` 臂 merge 写入前；**condition 语境三元（`if (a if c else b):`）的 LIR：两臂各自 condition 归一化 + `GoIfInsn`，全图无 `cfg_merge_` 槽**。
  - 语句位三元（结果丢弃）与 `var x := 三元`（slot stabilization 取合并类型）路径。

验收：
- `analyzeForCompile` 对支持面内三元全程无 error；`FrontendCompileCheckAnalyzerTest`、`FrontendLoweringBodyInsnPassTest`、`FrontendLoweringBuildCfgPassTest` 全绿。
- property initializer 中的三元（`var p = 1 if c else 2`）可走通 `buildPropertyInitializer` 路径。

### Phase 5：e2e、文档最终化与全量验收

改动：
- 参考 `cast/` e2e 对（`GdScriptUnitTestCompileRunnerTest`），新增 `ternary/` 用例对：基础同型、`int/float` 混合、嵌套右结合、括号左结合、对象父类合并、`null` 臂、condition 为非 bool stable 类型（如 `Variant`）、语句位丢弃、condition 语境三元。
- 验收 destroyable 合并槽生命周期：String/Array 臂三元的 C 产物中 merge 槽声明、单写、作用域退出销毁与现有 source-local 行为一致（对照 `cbodybuilder_implementation.md` 槽合同；若发现 merge 槽漏 destroy，按既有 `cfg_tmp_*` 同策略补齐，不得特判）。
- **拦截清单文档划除是解封的最终确认步骤**：只有在上述最小 e2e 集合（至少同型 + 异型 + condition 语境）通过后，才把 `ConditionalExpression` 从 `frontend_rules.md` compile intercept 清单与 `frontend_compile_check_analyzer_implementation.md` §3.3 划掉（满足该文档 §7 的解除前提：lowering 已实现、CFG/ownership 合同已冻结、backend 能消费、targeted tests 齐备、文档同步）。其余文档同步（§6）随本步一并完成。
- 更新 `README.md` 支持面清单（**需用户许可**，README 不在 src/doc/tmp 授权范围内）。

验收：
- e2e 新增用例全部通过（Zig 不可用时按既有约定 skip）。
- `./gradlew clean build --no-daemon --info --console=plain` 全绿。
- 对照 Godot 行为的差集仅保留 §8 已记录项。

## 5. 测试矩阵汇总

| 维度 | 用例 | 层 |
|---|---|---|
| 解析 | 字段映射/右结合/括号/最低优先级 | parse |
| 类型合并 | 同型、int+float（双向顺序）、对象继承（双向顺序）、null+object、双臂 Nil→Nil、无公共→Variant、`Array[int]`/`Array[float]`→Variant、`String`/`StringName`→恒 left、Variant/DYNAMIC 臂、void 臂 UNSUPPORTED | sema |
| 诊断归属 | 臂/condition `FAILED`：arm + root 各一条 `sema.expression_resolution`（不同 range）；`void` 臂：仅 root 一条 `sema.unsupported_expression_route`；compile gate exact-range 去重零 `sema.compile_check`；坏子树跳过；同函数后续合法语句继续发布 | sema+gate |
| expectedType | contextual 容器双臂 | sema |
| CFG | 图形状、双生产者 merge、嵌套多级（含 value 语境 `and/or` 臂）、condition 语境纯控制流展开（无 merge 值）、preferredResultValueId | CFG |
| merge 合同 | and/or 零修改回归、手写 graph 的 merge-of-merge 合法化、悬空 source fail-fast、anchor 非表达式 fail-fast | CFG/body |
| body lowering | merge_write boundary（int→float）、condition 归一化、`cfg_merge_` 槽类型、condition 语境三元 LIR（双臂各自归一化 + GoIfInsn、无 `cfg_merge_`） | body |
| e2e | 基础/混合/嵌套/括号/null 臂/非 bool condition/语句位/condition 语境 | test_suite |
| 生命周期 | destroyable 臂类型 merge 槽的声明-单写-销毁 | e2e+codegen |

## 6. 文档同步清单

- 本文件（新建）。
- `frontend_rules.md`：compile intercept 清单移除 `ConditionalExpression` 及"CFG merge 合同未稳定"说明条目；merge 合同条目更新为 §3.2 精炼后表述。**在 Phase 5 最小 e2e 通过前不划除拦截清单**。
- `frontend_compile_check_analyzer_implementation.md` §3.3/§7：从显式拦截清单移除，更新解除前提说明（同上前置条件）。
- `frontend_chain_binding_expr_type_implementation.md` §4.6：`ConditionalExpression` 移出 explicit-deferred set。
- `frontend_unary_binary_expr_semantic_implementation.md` §1.2/§5.3：移除"不转正 ConditionalExpression"类表述，补充 condition 语境纯控制流展开与 value 语境 merge 的分工。
- `frontend_lowering_cfg_pass_implementation.md`：merge 槽类型 anchor 化、merge_write boundary（re-derive consumer）、merge-of-merge 放宽、三元构图两种语境形状；§9 remaining 清单同步移除三元。
- `frontend_lowering_(un)pack_implementation.md`：`merge_write` 作为 `materializeFrontendBoundaryValue` 新 re-derive consumer 登记（与 assignment/return/local-init 同类）。
- `diagnostic_manager.md`：无新诊断 category（MVP 不发 `INCOMPATIBLE_TERNARY` warning）；同步既有 category 行为描述（三元 deferred/compile 拦截移除，现状描述见该文档 deferred/compile_check 段落）。
- `README.md`：支持面清单更新（**需用户许可**）。

## 7. 风险与缓解

1. **merge 槽生命周期**（中风险）：destroyable 合并类型（String/Array/object）的 `cfg_merge_` 槽在 C 后端的声明/销毁必须与 source-local 同策略；Phase 5 设专项验收，若缺毁则按 `cfg_tmp_*` 同策略修复，不特判三元。
2. **merge 合同精炼波及面**（低-中风险）：§3.2 触及 3 个共享合同点；以"and/or 现有测试零修改通过 + LIR 形状不变"为硬性验收门槛兜底。
3. **condition 语境三元**（已在设计中消解）：`if (a if c else b):` 属合法语法；纯控制流展开方案使 `conditionValueId` 恒为 temp 槽，`emitConditionBranch` 零改动，嵌套三元臂递归展开后同样不产生 merge 值；Phase 3/4 有专测锚定。
4. **`expectedType` 冲突 fail-fast**：双臂为独立 AST 节点，互不影响 `finalExpectedTypes`；嵌套三元臂的 expected 透传需单测锚定（Phase 1）。

## 8. 决策点（已与 Godot 语义对齐的记录）

- D1：合并类型复用 GDCC ordinary boundary 矩阵（`determineFrontendBoundaryDecision.allows()`）替代 Godot `is_type_compatible`；差集为矩阵未覆盖的 Godot 宽兼容形态，按 MVP 一致性原则接受。
- D2：Godot 对无公共类型双臂发 `INCOMPATIBLE_TERNARY` warning；MVP 只回退 `Variant` 不发 warning（避免新增诊断 category 与 `diagnostic_manager.md` 同步面），列为后续可选项。
- D3：`void` 臂三元按显式 `UNSUPPORTED` feature boundary 处理（error），不做语句位丢弃特判。
- D4：不做常量折叠、不做 flow-sensitive 收窄（与现有 `if` 一致）。
- D5：merge 槽类型 anchor 化是对 `MergeValueItem` 共享合同的精炼而非分叉；`and/or` 的 LIR 形状不变（仍 `LiteralBoolInsn` + `AssignInsn(cfg_merge_*, cfg_tmp_*)`，无 pack/unpack/intrinsic）是硬验收。

## 9. 参考位置

- 本仓库（均相对 `src/main/java/gd/script/gdcc/`）：`frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java:559-1063`；`frontend/sema/analyzer/FrontendBodyOwnerProcedures.java:1433-1722`；`frontend/lowering/cfg/FrontendCfgGraphBuilder.java:756-904, 1376-1575, 2558-2571, 2757-2759`；`frontend/lowering/cfg/FrontendCfgGraph.java:190-261`；`frontend/lowering/FrontendBodyLoweringSupport.java:106-297`；`frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java:238-331`；`frontend/lowering/pass/body/FrontendBodyLoweringSession.java:824-929`；`frontend/lowering/pass/body/FrontendCfgNodeInsnLoweringProcessors.java:60-126`；`frontend/sema/analyzer/support/FrontendVariantBoundaryCompatibility.java`；`scope/ClassRegistry.java:890-915`；`frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java:875-911, 986-1037`。
- Godot：`modules/gdscript/gdscript_analyzer.cpp` `reduce_ternary_op`（归约顺序、Variant 回退、双向兼容归并、常量折叠、`INCOMPATIBLE_TERNARY`）。
- gdparser：`frontend/ast/ConditionalExpression.java`；`vendor/tree-sitter-gdscript/grammar.js:743-753`；`frontend/lowering/CstToAstMapper.java:669-678`。
