# Frontend Match Statement 实现说明

> 本文档作为 GDScript `match` **语句**在 frontend shared semantic、type-check、compile gate、
> CFG 与 body lowering 中的**长期事实源**。定义已冻结的 `FrontendMatchPlan` 合同、六种
> pattern route、`MATCH_SECTION_BODY` 与 bind 声明身份、pattern-context 分派、route-aware
> compile gate，以及复用既有 LIR 的 CFG/body lowering。
> 本文档吸收并取代原 `frontend_match_statement_plan.md` 中的架构与合同描述，
> **不再保留**分阶段实施步骤、验收流水账或进度状态表。

## 文档状态

- 状态：事实源维护中（shared body inventory、`FrontendMatchPlan` 发布、`MATCH_PATTERN_RESOLUTION`
  精化、type-check、route-aware compile gate、CFG region/items、body lowering 均已落地；
  `WILDCARD` / `BINDING` / `LITERAL` / `EXPRESSION` / `ARRAY` / `DICTIONARY` 六 route 全部
  compile-ready）
- 更新时间：2026-08-23
- Godot 对齐基线：官方文档 `gdscript_basics.html#match`；`godotengine/godot`
  `modules/gdscript/gdscript_parser.cpp` / `gdscript_analyzer.cpp` / `gdscript_compiler.cpp`
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/resolver/**`
  - `src/main/java/gd/script/gdcc/frontend/scope/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/test/java/gd/script/gdcc/frontend/**`
  - `src/test/test_suite/unit_test/{script,validation}/control_flow/match_*`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_for_range_loop_implementation.md`（同类语句毕业模板）
  - `doc/module_impl/frontend/frontend_lambda_implementation.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_loop_control_flow_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_local_type_stabilization_implementation.md`
  - `doc/module_impl/frontend/frontend_container_literal_implementation.md`（`openEnded` 边界）
  - `doc/module_impl/frontend/frontend_global_constant_implementation.md`（常量 pattern 叶子）
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/gdcc_low_ir.md`
- 明确非目标：
  - 不把 `match` 改造成表达式；不支持在表达式位置使用 `match`
  - 不做穷尽性（exhaustiveness）检查（Godot 也不要求）
  - 不做 unreachable-section-after-wildcard warning（Godot 有 `UNREACHABLE_PATTERN`，列为后续可选）
  - 不在 parser 层精确复刻 `..` 的位置/唯一性校验（gdparser 0.5.3 只暴露 `openEnded`）
  - 不支持仅键字典形态 `{"a", "b"}`（gdparser 无法表示，见 §10）
  - 不毕业 block-local `const`、parameter default、class constant
  - 不为 match 引入 `GdCompilerType` 或新 LIR 指令 / intrinsic

---

## 1. 当前职责与集成位置

### 1.1 Pipeline 位置

`match` 跨越多条 frontend 链路，职责拆分如下：

| 阶段 | 组件 | 职责 |
|------|------|------|
| Scope | `FrontendScopeAnalyzer.handleMatchStatement` | 每个 `MatchSection` 建独立 `MATCH_SECTION_BODY`；`patterns` / `guard` / `body` 共享该 scope |
| Inventory | `FrontendVariableAnalyzer` / `FrontendInterfacePhase` | 递归发布 `PATTERN_BIND`；section body ordinary local 正常发布 |
| Suite body | `FrontendStatementResolver` / `FrontendBodyOwnerProcedures` | subject 走普通管线；pattern-context 分派；`MATCH_PATTERN_RESOLUTION` 发布 plan 并精化顶层 bind；child suite 进入 body |
| Type-check | `FrontendTypeCheckAnalyzer.handleMatchStatement` | bind 与多 pattern 互斥、字典 key 常量性；始终遍历全部 section body |
| Loop control | `FrontendLoopControlFlowAnalyzer` | 穿透 match，不重置 loop depth |
| Compile gate | `FrontendCompileCheckAnalyzer.handleMatchStatement` | `FrontendMatchSupport.isRouteLoweringReady`；当前六 route 全部放行 |
| CFG | `FrontendCfgGraphBuilder.processMatchStatement` | `FrontendMatchRegion` + match CFG items + bind slot registry |
| Body lowering | `FrontendSequenceItemInsnLoweringProcessors` | 既有 LIR：`EQUAL` / `get_variant_type` / `CallMethodInsn` / `VariantGet*` / `AssignInsn` |

Statement-local owner 顺序：

`top binding` → `local type stabilization` → `chain binding` → `expr typing` →
**`match pattern resolution`** → `var type post`。

Suite 级 transaction 同时允许 `FOR_ITERATION_RESOLUTION`（order 4）与
`MATCH_PATTERN_RESOLUTION`（order 5）；同一 callable 内 `for` + `match` 必须能一次导出两个 stage。

### 1.2 三层支持面解耦

1. **Shared body semantic**：section inventory / pattern bind / declaration index / suite entry /
   `FrontendMatchPlan` **不依赖** route lowering readiness。
2. **Type-check**：消费 plan；只做形状校验，不发 route-not-ready，也不因单个 pattern 错误跳过 body。
3. **Compile / CFG / lowering**：只放行 `isRouteLoweringReady` 为真的 route；当前六 route 全部 ready。

### 1.3 当前不负责

- 伪造 bind 的 `VariableDeclaration` AST
- 在 plan 中存储 intrinsic 名、`GdCompilerType`、helper result type
- 用 ordinary local stabilization 处理 `PatternBindingExpression`（declaration domain 互斥）
- 在 body lowering 重新分类 route 或重读 AST 选择指令
- 把 pattern 子树交给普通 container-literal / identifier 管线

---

## 2. Godot 语义与 gdcc 对齐

官方语法：

```gdscript
match <value_expr>:
    <pattern> [, <pattern>]* [when <guard_expr>]:
        <suite>
```

冻结语义：

1. **求值顺序**：section 自上而下；首个命中 section 执行 body，之后走到 `match` 之后；无 fallthrough；
   无任何 section 命中时静默 no-op。subject 只求值一次。
2. **字面量 / expression 比较**：`typeof(subject) == typeof(pattern 值)` **且** `Variant::OP_EQUAL`
   同时成立。唯一例外：`String` ↔ `StringName` 交叉互通。`1` 不命中 `1.0`。
   非常量 pattern 在到达其测试段时求值；未到达的 pattern 不求值。
3. **expression pattern 合法性**：Godot 仅允许常量，或裸标识符 / 标识符根 attribute 链。
   **gdcc 有意超集**：任意可求值表达式均为合法 pattern；常量性只决定 lowering 子模式，不决定合法性。
   字典 key **不放宽**，必须是常量。
4. **绑定 `var x`**：无条件匹配并把被测值写入新变量。顶层 bind 类型可精化为 subject 静态类型
   （无则 `Variant`）；嵌套 bind 恒 `Variant`。仅同一 section 的 guard / body 可见。
   bind 不得与逗号多 pattern 混用；同 section bind 名不得重复。
5. **数组 pattern**：`typeof == ARRAY`；无 `..` 时 `len == 元素数`，有 `..` 时 `len >= 元素数`；
   逐元素递归匹配。`..` 不计数、不捕获剩余元素。
6. **字典 pattern**：`typeof == DICTIONARY`；长度规则同数组；每个 key 先 `has(key)`，再对 value
   pattern 递归。gdparser 的 `DictEntry.value` 非空，仅键形态不在支持面。
7. **多 pattern OR**：惰性短路，禁止 eager 求值全部 pattern。
8. **Guard**：仅 pattern 命中后求值；false 落到下一 section。guard 可见该 section 的 bind。
9. **循环控制**：对齐 Godot 4.x。`match` 内 `break` / `continue` 只属于外层循环；3.x 的
   match-`continue` 特例不实施。

---

## 3. gdparser AST 形状

库坐标 `com.github.SuperIceCN:gdparser:0.5.3`。match 只有两层 record，**没有独立 Pattern 类型**，
pattern 全部复用 `Expression`：

```java
public record MatchStatement(Expression value, List<MatchSection> sections, Range range) implements Statement
public record MatchSection(List<Expression> patterns, @Nullable Expression guard, Block body, Range range) implements Node
public record PatternBindingExpression(String name, Range range) implements Expression
```

| GDScript 语法 | AST 形态 | 判别 |
|---|---|---|
| 字面量 | `LiteralExpression` | record kind |
| wildcard `_` | `IdentifierExpression("_")` | **仅按名字 `_`**，无专用节点 |
| 绑定 `var x` | `PatternBindingExpression` | 必须带 `var`；裸 `x` 不是绑定 |
| 裸标识符 / attribute / 任意表达式 | 普通 `Expression` | `EXPRESSION` route |
| 数组 `[1, _, var x, ..]` | `ArrayExpression(..., openEnded)` | `..` → `openEnded==true` |
| 字典 `{"k": var v, ..}` | `DictionaryExpression(..., openEnded)` | 同上 |
| 多 pattern `1, 2:` | `MatchSection.patterns().size() > 1` | 列表长度 |
| guard `when expr` | `MatchSection.guard()` | 非 null |

强制陷阱：

1. `_` **仅在 match pattern 递归上下文**内是 wildcard；普通表达式上下文不受影响。
2. 裸标识符永远不是 bind；解析失败走既有 `sema.binding` / chain，不由 match 另发。
3. grammar 只接受尾位 `..`（`[.., 1]` 为 parse 错误）；`openEnded==true` 一律按尾位 rest 处理。
4. 普通 executable 上下文的 `openEnded==true` 仍 fail-closed
   （`frontend_container_literal_implementation.md`）；match pattern 是 `openEnded` 的唯一合法消费点。
5. `PatternBindingExpression` 在 match pattern 之外保持 fail-closed。

---

## 4. Match plan 与 route 分类

### 4.1 `FrontendMatchPlan`（纯语义事实）

发布侧表：`matchPlans()`，key = `MatchStatement` AST identity。

```
FrontendMatchPlan
  statement        : MatchStatement
  sections         : List<FrontendMatchSectionPlan>

FrontendMatchSectionPlan
  section          : MatchSection
  patterns         : List<FrontendMatchPatternPlan>
  hasGuard         : boolean

FrontendMatchPatternPlan
  patternNode      : Expression
  route            : FrontendMatchPatternRoute
  bindings         : List<FrontendMatchBindingPlan>   // 含嵌套，源序

FrontendMatchBindingPlan
  name             : String
  declaration      : PatternBindingExpression         // declaration identity
  topLevel         : boolean                          // 顶层可精化；嵌套恒 Variant
```

约束：plan **不**携带 `GdCompilerType`、intrinsic 名、C helper、LIR 细节。

分类唯一真源是 `FrontendMatchSupport`：

| AST | Route |
|---|---|
| `IdentifierExpression("_")`（仅 pattern 上下文） | `WILDCARD` |
| `PatternBindingExpression` | `BINDING` |
| `LiteralExpression` | `LITERAL` |
| `ArrayExpression` | `ARRAY`（元素递归） |
| `DictionaryExpression` | `DICTIONARY`（value 递归；key 不 bind） |
| 其余 `Expression` | `EXPRESSION` |

分类器只做 AST 形状分派。常量性不决定合法性，只在 lowering 选择常量 / 运行时子模式。

### 4.2 Route readiness

唯一事实源：`FrontendMatchSupport.isRouteLoweringReady(route)` 的静态集合。

当前六 route **全部 ready**。集合只允许单调增长，不得预置未实现终态，也不得移除已就绪 route。
match route 不携带 operation descriptor，因此**不**引入 `ForLoweringContractRegistry` 式 registry；
若未来 route 需要 lowering 描述符再升级。

---

## 5. Scope、inventory 与可见性

### 5.1 Scope 身份

- `FrontendBodySemanticSupportPolicy`：`MATCH_SECTION_BODY` → `EXECUTABLE_BODY`。
- `scopesByAst[section]` 与 `scopesByAst[section.body()]` 是**同一个**
  `BlockScope(MATCH_SECTION_BODY)` 对象。
- `owningScopeForDeclaration(PatternBindingExpression)` 取该对象。
- overlay 匹配仍按 `scope ==` 对象身份。

### 5.2 Binding inventory 与 declaration index

- `FrontendVariableAnalyzer` 递归收集全部 `PatternBindingExpression`（含数组/字典嵌套），
  以该节点为 declaration identity 在 section scope 上
  `defineLocal(name, Variant, patternBindingExpression)`，`ScopeValue.kind() == LOCAL`。
  inventory 阶段 baseline 恒 `Variant`。
- duplicate / shadowing 复用 callable-local 规则，owner = `sema.variable_binding`。
- `LambdaCaptureSourceScanner` 穿透 match；match 内名字正常进入 capture 规划。
- declaration index：`FrontendBodyLocalDeclaration.Kind.PATTERN_BIND`。
  每个 section body：`PATTERN_BIND` 按 pattern 源序排在头部（`sourceOrder 0..k-1`），
  `ORDINARY_VAR` 顺延。
- `FrontendBodyStructuralCompleteness` 校验：declaration 必须是 `PatternBindingExpression`、
  `scopesByAst[declaration] == expectedScope`、`resolveValueHere(name) == binding`。

### 5.3 VisibleValueResolver

bind 在同一 section 的 guard / body 内可见；section 间互不可见；`match` 之后不可见。
这三条由「section 级独立 `BlockScope` + 普通逐层 lookup」自然推出，不新增特殊过滤。
bind 不参与 `declaration-after-use` / `self-reference` 过滤（声明即绑定）。

---

## 6. `MATCH_PATTERN_RESOLUTION` 与 bind 精化

- 新增 stage `FrontendSemanticStage.MATCH_PATTERN_RESOLUTION`。
- 职责：`FrontendMatchSupport.buildPlan(...)` 发布 plan；对**顶层** bind 执行
  `Variant → subject 静态类型`（仅当 subject 已发布稳定 non-Variant 类型；否则保持 `Variant`）。
- 精化规则与 for 一致：只允许 `Variant → exact`、exact 同型 no-op、禁止 exact A→B、
  禁止 `void` / `GdCompilerType`。
- 嵌套 bind 不精化，恒 `Variant`。
- 独立 patch：`FrontendMatchResolutionPatch`。`LOCAL_TYPE_STABILIZATION` **不处理**
  `PatternBindingExpression`。
- `FrontendPatchTransaction.order()`：`MATCH_PATTERN_RESOLUTION -> 5`，
  `VAR_TYPE_POST` / `LAMBDA_RESOLUTION` 为 6 / 7。
- bind 的 source-facing slot type 由 `VAR_TYPE_POST` 发布到
  `slotTypes()[PatternBindingExpression]`。被拒 bind 发 `sema.variable_slot_publication` warning，
  供 compile gate 缺洞升级。

### 6.1 同名 bind 类型统一

同一 match 内、跨 section 的同名 bind 在 lowering 共享一个按名字键控的函数变量。

- 若同一 match 中某名字既有顶层 bind（可精化）又有嵌套 bind（恒 `Variant`），sema 期整组
  **保留 `Variant` 基线、不做顶层精化**，使 capture entry / scope binding / `slotTypes()` /
  共享存储一致。
- 同 match 内无分歧的名字仍可精化。
- 跨 **不同** match statement 的同名分歧无法从单个 plan 看见，由 CFG
  `unifyCollidingMatchBindSlotTypes` 统一为 `Variant`。
- 被重定型的 bind 若是某 lambda 的非 `Variant` capture 来源 → lowering fail-fast
  （该 edge 暂不支持，不穿透到 backend `construct_lambda`）。

---

## 7. Pattern-context 解析分派

pattern 递归上下文与普通 executable 表达式管线严格隔离。`runSupportedRoot` /
`runTopBinding` 不得以 `MatchStatement` / `MatchSection` / 整棵 pattern 为 root。

| Route | 分派 |
|---|---|
| `WILDCARD` | 不发布 binding / `expressionTypes`；`_` 不是引用 |
| `BINDING` | 不进入表达式 typing；节点只作 declaration identity |
| `ARRAY` / `DICTIONARY` | 只做结构递归；不发布 `FrontendContainerLiteralPlan` / `expressionTypes`；`openEnded` 仅此处合法 |
| `LITERAL` / `EXPRESSION` | 叶子走普通 owner 管线；无形状白名单；解析失败由普通管线诊断 |

---

## 8. Type-check 与 compile gate

### 8.1 Type-check（`sema.type_check`）

1. section `patterns().size() > 1` 且含 `BINDING` → error。
2. `EXPRESSION` 一律合法；不设常量性/形状白名单。上游 `sema.binding` / chain 错误不重复包装。
3. guard 复用 Godot-compatible condition contract：只要稳定 typed fact，允许非 `bool`。
4. 字典 key 必须是已发布常量，否则报 Godot 原文
   `Expression in dictionary pattern key must be a constant.`（锚定 key 节点）。
   gdcc 常量域 ⊂ Godot `is_constant` 域（无通用常量折叠）。
5. 始终 walk 全部 section body。
6. 已有 upstream `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 时不追加诊断。

subject 无独立「可匹配性」检查；`void` subject 由既有 expression 合同拦截。

### 8.2 Compile gate

`FrontendCompileCheckAnalyzer.handleMatchStatement`：

1. plan 缺失（upstream error）→ 保持封口，不补发。
2. 全部 pattern route 均 ready → mark compile surface，重扫 subject / patterns / guard / body。
3. 任一 route 未就绪 → 在 `MatchStatement` root 发 `sema.compile_check` route-not-ready，**不重扫 body**。
4. 同一 statement 已有 upstream error 时省略 route-not-ready。
5. `scanSlotTypeCompileBlocks` 覆盖 `PatternBindingExpression`：仅在已 mark compile surface 后扫描；
   缺 `slotTypes()` 且存在 upstream `sema.variable_slot_publication` warning 时补发
   `sema.compile_check`。无 warning 的缺洞属协议破坏，由 CFG 跨表验证 fail-fast。

当前六 route 均 ready，合法 match 不再因 route-not-ready 被挡。机制本身保留，供未来新 route 使用。

---

## 9. CFG 形状与 body lowering

### 9.1 Region

`FrontendCfgGraphBuilder.processMatchStatement`：

1. subject `buildValue` **单次**求值为 temp-backed value。
2. 每个 section 是 `BranchNode` 测试链：命中进 body，未命中链到下一 section；最后未命中与全部
   body 出口汇合到 `mergeId`。全终止时允许 `mergeId -> TERMINAL_MERGE`；`TERMINAL_MERGE`
   不得作为 `goto` 目标。
3. `FrontendMatchRegion`（AST identity keyed）：`headerEntryId` / 每 section
   `testEntryId` + `bodyEntryId` / `mergeId`。

```
header (subject once)
  -> section0 test --hit--> [bind] [guard?] body --\ 
                 --miss-> section1 test ...          --> merge
                                              last miss --/
```

### 9.2 测试片段与 items

禁止重读 AST、禁止用外层 value id 充当 branch condition。

| Route | 测试 |
|---|---|
| `WILDCARD` | 无测试（常量 true） |
| `BINDING`（顶层） | 无测试；body 入口 `MatchBindItem` 写入 bind slot |
| `LITERAL` / `EXPRESSION` | 类型严格相等 + 值相等（§9.3） |
| 多 pattern OR | 命中端全部指向 body；未命中端串联短路。禁止 value 语境 `BinaryOp(OR)` |
| guard | pattern 命中后 `buildCondition`；false 链到下一 section |

专用 CFG items（字段在 CFG build 时固化，body lowering 不得重查 AST）：

- `MatchBindItem` — subject / 嵌套 fetch → bind slot
- `MatchEqualItem` — `binary_op EQUAL`
- `MatchContainerMaterializeItem` — typeof gate 后物化为静态 `Array` / `Dictionary`
- `MatchLengthCheckItem` — `size() == n` 或 `size() >= n`
- `MatchHasKeyItem` — `has(key)`
- `MatchElementFetchItem` — `variant_get_indexed` / `variant_get_keyed`

### 9.3 LITERAL / EXPRESSION 严格比较

`EXPRESSION` 按 published facts 分两个子模式：**常量性只选策略，不选合法性**。

1. **subject 静态类型已知且非 Variant**
   - LITERAL / 常量子模式：类型族一致 → 只发值比较；不一致 → 测试折叠为常量 false
     （body 仍建图但不可达；不做 warning）。完全折叠且不物化操作数，仅限无副作用的常量侧。
   - 运行时子模式：控制流到达测试段时**必须** `buildValue(patternExpr)`，不得因静态不兼容跳过求值。
     两侧已知且兼容 / String↔StringName → 省略 typeof；两侧已知且不兼容 → 仍求值，结果接 false；
     任一侧 `Variant` / 未知 → 走完整运行时链。
2. **subject 为 Variant / 静态未知**：`go_if` 短路链。subject `get_variant_type` 每个 match
   只求值一次并跨 pattern 复用。
   - 常量子模式：`literal_int <type_id>` + `EQUAL`；String 家族用两级 `BranchNode` 展开
     `(t == STRING) or (t == STRING_NAME)`。
   - 运行时子模式：求值 `pv` 后 `pt = get_variant_type $pv`；类型相等为 `(t == pt)` **或双向**
     String↔StringName；再 `EQUAL $subject $pv`。
   - `null` literal 走 `variant_is_nil`；运行时求值为 `null` 走通用路径。
3. 常量子模式不要求 frontend 新增常量求值器：只需类型已知 + 运行时可求值。
4. Godot 可折叠而 gdcc 未折叠的纯表达式（`1+2`、`Vector2(1,2)`、`sin(20)`）落入运行时子模式；
   可观察行为等价，仅求值时机从编译期变为运行时。

### 9.4 数组 / 字典解构（路线 A）

零新指令。顺序：

1. typeof gate（`ARRAY` / `DICTIONARY`）
2. `MatchContainerMaterializeItem`：Variant 解包到 untyped 容器槽；已是静态容器则保留已发布类型
3. 长度门：无 `..` → `==`，有 `..` → `>=`（`..` 不计入）
4. 字典：每 key `has(key)`，再 fetch value
5. 数组：`variant_get_indexed`；字典：`variant_get_keyed`
6. 子 pattern 递归完整 route 分派（含 `EXPRESSION` 运行时子模式与嵌套 bind）

静态不可能命中的容器 pattern：在 route 分派处直接接 falseTarget，不发测试片段；bind 槽仍预分配
供不可达 body 读取，但不提交 `MatchBindItem`。跨表验证以 `foldedMatchBindDeclarations` 豁免。

路线 B（`gdcc.match_*` intrinsic）**未采用**。若未来证伪路线 A，必须先冻结
`gdcc_lir_intrinsic.md` 再启用。

### 9.5 Bind slot registry

- CFG 与 region 同批发布 `FrontendMatchBindSlot`（key = `PatternBindingExpression`；
  source slot id = 源码名；exposedType = `slotTypes()`）。
- `declareSourceLocalSlots()` **不**覆盖 bind（`LocalDeclarationItem.declaration` 只认
  `VariableDeclaration`）。`FrontendBodyLoweringSession.declareMatchBindSlots()` 在任何
  block 物化前预声明。
- 同名跨 section（及同 callable 内不同 match）共享一个函数变量；寿命不重叠，因为同一时刻
  只执行一个 section。读取经声明槽类型物化，而不是 per-use 发布类型。

---

## 10. Lambda 与 loop control

- match section 内 lambda 照常 `recordCallable`；capture 可见 section bind；capture 类型取
  声明处类型（同 match 分歧组为 `Variant`）。
- lambda 内的 match 正常解析。
- `FrontendLoopControlFlowAnalyzer` 穿透 match 且不重置 loop depth。section body 内外层
  `break` / `continue` 合法；无外层循环时照旧 `sema.loop_control_flow`。

---

## 11. 核心实现位置

| 职责 | 位置 |
|---|---|
| 分类 / plan / readiness | `FrontendMatchSupport`、`FrontendMatchPlan*`、`FrontendMatchPatternRoute` |
| Stage / patch | `FrontendSemanticStage.MATCH_PATTERN_RESOLUTION`、`FrontendMatchResolutionPatch`、`FrontendPatchTransaction` |
| Scope / inventory | `FrontendScopeAnalyzer`、`FrontendVariableAnalyzer`、`FrontendInterfacePhase`、`FrontendBodyLocalDeclaration.PATTERN_BIND` |
| Statement 解析 | `FrontendStatementResolver.resolveMatchStatement`、`FrontendBodyOwnerProcedures` |
| Policy | `FrontendBodySemanticSupportPolicy`（`MATCH_SECTION_BODY` → `EXECUTABLE_BODY`） |
| 可见性 | `FrontendVisibleValueResolver` |
| Type-check | `FrontendTypeCheckAnalyzer.handleMatchStatement` |
| Compile gate | `FrontendCompileCheckAnalyzer.handleMatchStatement` |
| CFG | `FrontendCfgGraphBuilder.processMatchStatement`、`FrontendMatchRegion`、`cfg/item/Match*Item` |
| Body lowering | `FrontendBodyLoweringSession.declareMatchBindSlots`、`FrontendSequenceItemInsnLoweringProcessors` |

---

## 12. 测试锚点

主要类：

- `FrontendMatchParseBehaviorTest` — AST 形状、`..`、仅键字典不可表示
- `FrontendMatchSupportTest` — 分类、bind 收集、六 route readiness
- `FrontendMatchSemanticsTest` — 可见性、冲突、精化、分歧组 `Variant`、compile gate、lambda
- `FrontendCfgGraphBuilderMatchTest` — region、惰性 OR、guard、解构、嵌套 bind
- `FrontendCompileCheckAnalyzerTest` — compile surface、body 重扫、lambda 内 match
- `FrontendLoweringBodyInsnPassTest` — CFG items → LIR
- `FrontendLambdaLoweringTest` — bind capture 与跨 match 分歧
- `FrontendVisibleValueResolverTest` / `FrontendVariableAnalyzerTest` / `FrontendScopeAnalyzerTest`
- `FrontendLoopControlFlowAnalyzerTest` — match 不切断 loop
- `GdScriptUnitTestCompileRunnerTest` — e2e fixture 登记

代表性 fixture（`src/test/test_suite/unit_test/{script,validation}/control_flow/`）：

- `match_literal_wildcard` / `match_bind_guard` / `match_expression`
- `match_array_destructure` / `match_dict_destructure` / `match_nested_containers`
- `match_lambda` / `match_control_flow_mix` / `match_mixed` / `match_string_stringname`

---

## 13. 当前局限与已知边界

- **`..` 无位置信息**：grammar 只接受尾位；AST 只有 `openEnded`。不为此升级 parser。
- **仅键字典 `{"a", "b"}`**：gdparser mapper 丢弃键并发 `parse.lowering` WARNING，映射为零 entry。
  用户改写 `{"k": _}`。不为此修改 parser。
- **expression pattern 超集**：Godot 合法 ⇒ gdcc 合法且行为一致；逆不成立。`f()` / `a+1` /
  `d["k"]` / `self.prop` 等在 Godot 编辑器会报错，**不可移植**。不新增 portability lint。
  字典 key 不在超集内。
- **class constant / 类枚举成员 pattern**：仍受 class-constant 延后合同约束；失败由既有
  upstream 诊断兜底。限定式全局枚举 chain（`Variant.Type.TYPE_NIL`）已毕业。
- **跨 match 同名 bind + 非 Variant lambda capture**：CFG fail-fast，暂不支持。
- **无独立 type-pattern route**：`x is T` 不是 match pattern 形态。
- 静态折叠产生「建图但不可达」的 body；CFG 不变量必须继续成立。可选「跳过 body 建图」仅限
  LITERAL / 常量子模式。

---

## 14. 维护结论

1. **Plan 与 lowering 分离**不可回退：语义事实进 `matchPlans()` / `slotTypes()`；CFG items
   在 build 时固化；body lowering 只消费 published facts。
2. **`MATCH_SECTION_BODY` 对象身份**是 bind overlay 的唯一匹配键；section 与 body 必须是同一
   `BlockScope` 实例。
3. **Pattern-context 分派**不得回退到普通 container-literal / identifier / `PatternBindingExpression`
   deferred 管线。
4. **Route readiness 单调增长**：新 route 必须先有 CFG/LIR/backend/测试闭环，再加入 ready set。
5. 修改本合同时，同步 `frontend_rules.md`、`frontend_compile_check_analyzer_implementation.md`、
   `frontend_type_check_analyzer_implementation.md`、`frontend_lowering_cfg_pass_implementation.md`、
   `frontend_container_literal_implementation.md`、`frontend_lambda_implementation.md`、
   `scope_analyzer_implementation.md` 中的对应条目。
