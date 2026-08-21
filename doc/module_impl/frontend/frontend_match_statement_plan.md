# Frontend Match Statement 实施计划

> Updated: 2026-08-21
> 本文档是 GDScript `match` 从结构性 deferred / unsupported 边界毕业进入 frontend 正式支持面的
> 实施计划与验收基线。实施完成后本文档转为冻结合同（参照 `frontend_for_range_loop_implementation.md`
> 的先例，阶段性进度以 git 历史为准）。
>
> 术语说明：GDScript 官方语法中 `match` 是**语句（statement）**而非表达式；本计划按语句实施，
> 不包含任何「match 作为表达式求值」的扩展。
>
> 分支说明：常量 pattern 对全局常量/枚举成员的依赖以
> `frontend_global_constant_implementation.md` 的 compile-ready 裸访问合同为前提。
>
> 修订记录：v2（2026-08-21，经 review-expert-a 审阅修订）：修正 Step 2 中间态的 fail-closed 设计
> （compile gate 本步即落地 route-aware，ready set 为空）；新增 pattern-context 解析分派合同
> （ARRAY/DICTIONARY/`var`/`_` 不得走普通 container-literal / identifier 管线）；补全
> `MATCH_PATTERN_RESOLUTION` 的 stage/patch/slot-update 门控链改动表；bind 的 source slot 改为
> for 式 registry + 独立 `MatchBindItem`（放弃「policy 翻转自动预声明」假设）；字典仅键省略值形态
> 以 Step 1 parse 实证为准。
> v3（2026-08-21，第二轮复核修订）：`isRouteLoweringReady` 集合改为随步骤单调增长且 Step 1/2
> 为空集（消除与 Step 2 fail-closed 的真源冲突）；`resolveMatchStatement` 补
> `runVarTypePost(matchStatement)` 步骤（bind 的 `slotTypes()` 发布与被拒 bind 的
> `sema.variable_slot_publication` warning）；inspection 合同拆为「删除无条件
> `MatchStatement -> true`」与「pattern-context 故意不发布专用 reason」两条；
> `scanSlotTypeCompileBlocks` 扩展与 L49 门闩对齐；补回 Step 2 标题、补 Step 4 文档同步条目。
> v4（2026-08-21，第三轮复核修订）：Step 3 验收的 bind 缺洞补发改为带 L49 门闩（须存在
> upstream `sema.variable_slot_publication` warning）；inspection 专用 reason 的判定顺序写死
> （先于 `hasUnsupportedOrDeferredAncestor`，防 `for` 内 match 被 `ForStatement -> true`
> 祖先短路），并新增 for 内 match 的验收锚点；Step 2/3 文档同步分别补 `frontend_rules.md`
> L123 / L49；「与 `FOR_ITERATION_RESOLUTION` 互斥」限定为 statement-local owner 层面，
> 明确 suite 级 transaction 必须同时允许两个 stage 且 order 不得相同。
> v5（2026-08-21，第四轮复核，Verdict: APPROVE）：修正「同序 fail-fast」过满措辞——
> 当前 `checkOrder` 允许同序，fail-fast 只挂在重复 stage / order 回退上；同序作为设计约束禁止。
> v6（2026-08-21，Step 1 实施实证修订）：parse 探针实证 R8——仅键省略值形态 `{"a", "b"}`
> 在 gdparser 0.5.3 不可表示（键被丢弃并发 `parse.lowering` WARNING、映射为零 entry），
> 从支持面删除；实证精化 R1——grammar 仅接受尾位 `..`，`[.., 1]` 直接 parse 错误而非
> openEnded=true 歧义形态，字典同；Step 1 产物（`FrontendMatchSupport` / plan 模型 /
> `FrontendMatchSupportTest` / `FrontendMatchParseBehaviorTest`）落地并锚定。
> v7（2026-08-21，expression pattern 语义修订，经 Godot master 源码实证）：`CONSTANT_EXPRESSION`
> route 改名并放宽为 `EXPRESSION`（对齐 Godot `PT_EXPRESSION` 1:1）——Godot analyzer 实证
> （`gdscript_analyzer.cpp` `resolve_match_pattern`）expression pattern 合法当且仅当
> `is_constant` 或形状为裸标识符/标识符根 attribute 链，非常量时**运行时求值比较**
> （`gdscript_compiler.cpp` `_parse_match_pattern` PT_EXPRESSION）；gdcc 决策为有意超集：
> 任意可求值表达式均为合法 pattern（不设形状白名单），常量性只决定 lowering 子模式
> （常量比较 / 运行时比较），不再设 type-check 常量性校验；字典 key 保持 Godot 常量限定
> 不放宽。详见 §1.2-2/3、§5.6、§5.9、§9 R9。
> v8（2026-08-21，第五轮复核修订）：§5.9-1 运行时子模式的静态类型矩阵不得跳过
> `buildValue` 求值（fold-to-false 不物化操作数仅限 LITERAL/常量子模式；运行时子模式
> 到达测试段必求值以保留副作用，对齐 Godot 对合法 identifier/attribute pattern 的运行时
> 求值）；R9 删除「副作用表达式 Godot 本来就不接受」的错误表述；§5.8 多 pattern OR 的
> 「无副作用比较」旧口径改为惰性短路语义；inspection 条目 literal/constant 叶子改为
> LITERAL/EXPRESSION 叶子；Step 2 改名回执扩为可勾选清单（Route/Support/PatternPlan
> javadoc 与测试方法名/类头）；Step 3 验收拆清「省略 typeof ≠ 跳过求值」。
> v9（2026-08-21，第六轮复核修订）：R7 可选优化收窄——「跳过建图」仅限 LITERAL /
> 常量子模式的 body（测试片段已是无副作用常量 false 时）；运行时子模式即使测试结果为
> 常量 false 也必须保留测试段的 `buildValue`，不得整段跳过；§5.9-1 三条弹头加
> 子模式前缀，避免与 R7 误读叠加。
> v10（2026-08-21）：改名回执（v7 清单）在 Step 1 补做完成——`CONSTANT_EXPRESSION` →
> `EXPRESSION` 落实在 Step 1 产物（Route/Support/PatternPlan/SupportTest）并通过靶向测试；
> 回执从 Step 2 改动清单迁移到 Step 1 状态块，§7 对应行标记已完成，Step 2 工作面保持干净。

---

## 文档状态

- 状态：Step 1 已完成（分类器、plan 模型与 parse 实证探针已落地，管线行为零变化）；Step 2-5 待实施
- 更新时间：2026-08-21
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/resolver/**`
  - `src/main/java/gd/script/gdcc/frontend/scope/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/test/java/gd/script/gdcc/frontend/**`
  - `src/test/java/gd/script/gdcc/test_suite/**`（e2e fixture）
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`（MVP 支持约定、诊断/测试约定）
  - `doc/module_impl/frontend/frontend_for_range_loop_implementation.md`（特性毕业模板）
  - `doc/module_impl/frontend/frontend_lambda_implementation.md`（nested callable 毕业模板）
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_top_binding_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_loop_control_flow_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_container_literal_implementation.md`（`openEnded` 边界）
  - `doc/module_impl/frontend/frontend_global_constant_implementation.md`（常量 pattern 依赖）
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/gdcc_low_ir.md`、`doc/gdcc_lir_intrinsic.md`
- 明确非目标：
  - 不把 `match` 改造成表达式；不支持在表达式位置使用 `match`。
  - 不毕业 block-local `const`、parameter default、class constant；它们的 deferred 合同不变。
  - 不做穷尽性（exhaustiveness）检查（Godot 也不要求穷尽）。
  - 不做 unreachable-section-after-wildcard warning（Godot 有 `UNREACHABLE_PATTERN` warning，列为后续可选）。
  - 不在 parser 层精确复刻 `..` 的位置/唯一性校验（gdparser 0.5.3 只暴露 `openEnded` 布尔，见 §9 风险 R1）。
  - 不更改 Godot `match` 运行时语义；不为 match 引入 `GdCompilerType` 或新 LIR 指令（首批四 route 完全复用现有指令，见 §5.9）。

---

## 1. Godot 官方语义溯源（规范依据）

以下来源已核对：官方文档 `gdscript_basics.html#match`（Godot 4 stable），
`godotengine/godot` master 的 `modules/gdscript/gdscript_parser.cpp`（`parse_match` /
`parse_match_branch` / `parse_match_pattern`）、`gdscript_analyzer.cpp`（`resolve_match*` /
`decide_pattern_type`）、`gdscript_compiler.cpp`（`_parse_match_pattern` / `_parse_block: MATCH`）。

### 1.1 语法形态

```gdscript
match <value_expr>:
    <pattern> [, <pattern>]* [when <guard_expr>]:
        <suite>
```

- `value_expr`：任意表达式（subject），只求值一次。
- 每个 section：`pattern_list` + 可选 `when` guard + 缩进 suite。逗号分隔多 pattern 为 **OR** 语义。
- pattern 种类（Godot 内部 `PT_*`）：`LITERAL`（字面量）、`EXPRESSION`（表达式 pattern：
  常量 / 标识符 / attribute 链，gdcc 放宽为任意可求值表达式，见 §1.2-3）、
  `WILDCARD`（`_`）、`BIND`（`var x`）、`ARRAY`、`DICTIONARY`、`REST`（`..`，仅数组/字典内末尾）。

### 1.2 匹配语义（冻结事实）

1. **求值顺序**：section 自上而下顺序测试，首个命中的 section 执行其 body，之后控制流走到
   `match` 语句之后；无 fallthrough；**无任何 section 命中时静默跳过**（no-op，不报错）。
2. **字面量 / expression pattern 的比较语义**：类型严格相等 —— `typeof(subject) ==
   typeof(pattern 值)` **且** `Variant::OP_EQUAL` 值相等，二者同时成立才命中。唯一例外：
   `String` 与 `StringName` 交叉互通（`"hello"` 命中 `&"hello"`）。`1` 不命中 `1.0`。
   常量 pattern 的类型编译期已知；非常量 identifier/attribute pattern 在运行时对**两侧**
   取 `typeof`（String/StringName 交叉为**双向**放行），且 pattern 表达式的求值发生在
   该 pattern 的测试段内——section 自上而下、section 内多 pattern 从左到右，短路链保护，
   未到达的 pattern 不求值（`gdscript_compiler.cpp` `_parse_match_pattern` 的
   PT_LITERAL / PT_EXPRESSION 与 or/and 短路 codegen 结构实证）。
3. **expression pattern 合法性（Godot 规则，`gdscript_analyzer.cpp`
   `resolve_match_pattern` PT_EXPRESSION 实证）**：合法当且仅当 (a) 解析后
   `is_constant`（含常量折叠：`const` 引用、枚举成员、常量构造/工具调用如
   `Vector2(1,2)` / `sin(20)`、折叠二元 `1+2`、`preload` 等），或 (b) 形状为裸标识符 /
   标识符根的 attribute 链（`y`、`obj.prop`、`A.B.C`；`self.prop` / `super.prop` 根为
   Self/Super 节点，**不在**豁免内）。其余非常量形状（`f()`、`a+1`、`d["k"]` 等）报
   `Expression in match pattern must be a constant expression, an identifier, or an
   attribute access ("A.B").`。字典 pattern 的 key 单独要求 `is_constant`（无形状豁免），
   违反报 `Expression in dictionary pattern key must be a constant.`。
   **gdcc 决策（v7，见 §9 R9）**：pattern 位置不设形状白名单——任意可求值表达式均为
   合法 pattern，非常量走运行时比较（行为与 Godot 的 PT_EXPRESSION  lowering 同构）；
   字典 key 不放宽，保持 Godot 常量限定。
4. **绑定 pattern `var x`**：无条件匹配（等价 wildcard）并把被测值绑定给新变量 `x`；
   顶层绑定整个 subject，数组/字典内嵌套绑定对应位置的子元素。
   - 类型：顶层 bind = subject 的静态类型（`type_constraint`，无则 `Variant`）；嵌套 bind = `Variant`。
   - 作用域：仅在**同一 section 的 guard 与 body** 内可见；section 之间互不可见；`match` 之后不可见。
   - 约束：bind 不允许与逗号多 pattern 混用；同一 section 内 bind 名不得重复；
     与同 callable 既有 local 重名按 duplicate/shadowing 规则报错。
5. **数组 pattern**：要求 `typeof(value) == ARRAY`；长度约束为 `len == 元素数`（无 `..`）或
   `len >= 元素数`（有 `..`）；逐元素递归匹配（子元素是完整 pattern，可嵌套）。
6. **字典 pattern**：要求 `typeof(value) == DICTIONARY`；长度规则同数组；每个 key 先 `has(key)`
   检查键存在，有 value pattern 时取 `get(key)` 递归匹配；`{"a", "b"}` 省略值表示仅检查键存在。
7. **多 pattern OR**：同一 section 的 pattern 列表任一命中即命中（实现上是 `OR` 累积）。
8. **Guard**：仅当该 section 的 pattern 命中后才求值 guard；guard 为 false 时落到下一 section。
   guard 中可见该 section 的 bind 变量。
9. **版本差异**：Godot 4.0 起 `when` guard 才存在；3.x 的 `continue`-继续匹配语义已在 4.0 移除。
   gdcc 对齐 Godot 4.x：`match` 内 `break` / `continue` 只属于外层循环，不影响匹配链。

---

## 2. gdparser 0.5.3 AST 形状（已精读源码确认）

库坐标 `com.github.SuperIceCN:gdparser:0.5.3`（`build.gradle.kts:57`）。match 只有两层 record，
**没有独立的 Pattern 类型层，pattern 全部复用 `Expression`**：

```java
public record MatchStatement(Expression value, List<MatchSection> sections, Range range) implements Statement
public record MatchSection(List<Expression> patterns, @Nullable Expression guard, Block body, Range range) implements Node
public record PatternBindingExpression(String name, Range range) implements Expression  // 仅对应 `var <name>`
```

pattern 形态对照表：

| GDScript 语法 | AST 形态 | 判别方式 |
|---|---|---|
| 字面量 `1` / `"a"` / `1.0` / `true` / `null` | `LiteralExpression(kind, sourceText)` | record 组件直接给 kind |
| wildcard `_` | `IdentifierExpression("_")` | **仅按名字 `_` 判别**，无专用节点 |
| 绑定 `var x` | `PatternBindingExpression(name)` | 必须带 `var`；裸 `x` 是 `IdentifierExpression`（常量/变量引用语义，不是绑定） |
| 裸标识符 / attribute chain（常量引用或运行时值引用） | `IdentifierExpression` / `AttributeExpression`（如 `Variant.Type.TYPE_NIL`、`y`、`obj.prop`） | 走普通可见值/chain 解析 |
| 其他任意表达式（`f()`、`a+1`、`d["k"]` 等） | 普通 `Expression` 节点 | 同走普通解析；gdcc 超集放宽为合法 pattern（§9 R9） |
| 数组 pattern `[1, _, var x]` | `ArrayExpression(elements, openEnded=true/false)` | `..` → `openEnded()==true` |
| 字典 pattern `{"k": var v, ..}` | `DictionaryExpression(entries, openEnded)`，`DictEntry(key, value)` | 同上 |
| 多 pattern `1, 2, 3:` | `MatchSection.patterns().size() > 1` | 列表长度 |
| guard `when expr` | `MatchSection.guard()`（nullable） | 非 null 即有 guard |

已知陷阱（实施时必须显式处理）：

1. `_` 二义性：库把 `_` 降为普通 `IdentifierExpression`；gdcc **仅在 match pattern 递归上下文内**
   把 `_` 认作 wildcard，普通表达式上下文不受影响。
2. 裸标识符不是绑定：只有 `PatternBindingExpression` 是绑定；裸小写标识符是普通表达式引用，
   走普通解析——解析为常量则常量比较，解析为普通可求值值（变量/成员等）则运行时比较
   （§1.2-2/3，gdcc 超集见 §9 R9）；解析失败才由既有 `sema.binding` / chain 链路报错。
3. `openEnded` 无位置信息：Step 1 实证 grammar 仅接受尾位 `..`（`[.., 1]` 为 parse 错误），
   `openEnded==true` 一律对应尾位 `..`（见 §9 R1）。
4. `openEnded` 在非 match 上下文同样出现：`frontend_container_literal_implementation.md` §2.6 已冻结
   「普通 executable 上下文的 `openEnded==true` 字面量必须 fail-closed」，本计划不改变该合同；
   match pattern 上下文是 `openEnded` 的唯一合法消费点。
5. `PatternBindingExpression` 在 match section patterns 之外的任何位置保持 fail-closed
   （`frontend_chain_binding_expr_type_implementation.md` §4.6 的 remaining deferred set 不变，
   match 上下文内由本计划接管）。

---

## 3. gdcc 现状链路与缺口

### 3.1 当前封口点（全部已精读确认，file:line）

| # | 位置 | 当前行为 |
|---|---|---|
| 1 | `FrontendScopeAnalyzer.java:264-269, 299-311` | **不封口**：已为每个 `MatchSection` 建 `BlockScope(MATCH_SECTION_BODY)`，`patterns/guard/body` 共享该 scope；`recordScope(section)==recordScope(body)` 同一对象 |
| 2 | `FrontendVariableAnalyzer.java:307-311`（binder） | `SKIP_CHILDREN`，不发布 pattern binding / section local inventory |
| 3 | `FrontendVariableAnalyzer.java:925-934`（boundary reporter） | 发 `sema.unsupported_variable_inventory_subtree` error |
| 4 | `FrontendVariableAnalyzer.java:989-991`（capture scanner） | `SKIP_CHILDREN`，match 内名字不进入 lambda capture 规划 |
| 5 | `FrontendInterfacePhase.java:209-211` | `SKIP_CHILDREN`，无 declaration index / typed baseline / suite entry |
| 6 | `FrontendStatementResolver.java:61` | `MatchStatement -> resolveUnsupportedRoot` |
| 7 | `FrontendBodyOwnerProcedures.java:597-614` | `runUnsupported`：发 `sema.unsupported_binding_subtree` + `sema.unsupported_chain_route`，仅 subject `value` 走正常 top-binding/chain/expr |
| 8 | `FrontendBodySemanticSupportPolicy.java:30, 127` | `MATCH_SUBTREE(false,false)`；`MATCH_SECTION_BODY -> MATCH_SUBTREE` |
| 9 | `FrontendVisibleValueResolver.java:180-186, 283-327` | AST boundary（`MatchSection.body/guard/patterns == child`）+ current-scope backstop（`MATCH_SECTION_BODY`）双重封口，返回 `DEFERRED_UNSUPPORTED / MATCH_SUBTREE` |
| 10 | `FrontendTypeCheckAnalyzer.java:1201-1203` | `SKIP_CHILDREN` |
| 11 | `FrontendLoopControlFlowAnalyzer.java:252-274` | **不封口**：已穿透 walk section patterns/guard/body；`match` 不重置 loop depth |
| 12 | `FrontendCompileCheckAnalyzer.java:516-518` | `SKIP_CHILDREN`，永不放上 compile surface |
| 13 | `FrontendAnnotationCollector.java:131-135` | 已穿透收集 section body 注解（无语义消费） |
| 14 | `FrontendAnalysisInspectionTool.java:706` | 有 `MatchStatement` 展示分支，未展开 section/pattern/guard 细节 |

结构前提已具备：`BlockScopeKind.MATCH_SECTION_BODY` 已冻结（`BlockScopeKind.java:37`），
scope 图形状（section 级单 scope、body 复用）已落地且与 Godot 「pattern/guard/body 共享一个
branch scope」语义一致。缺口集中在 inventory → resolver → type-check → compile gate → lowering 五段。

### 3.2 lowering 侧缺口

- `FrontendCfgGraphBuilder.processStatement()`（`frontend/lowering/cfg/FrontendCfgGraphBuilder.java`
  的 switch）对 `MatchStatement` 落入 `default -> unsupportedReachableStatement()` fail-fast。
- LIR 现有指令足够表达首批 route 的语义：`get_variant_type`（`gdcc_low_ir.md` §Type）、
  `binary_op "EQUAL"`、`literal_int/float/string/string_name/bool/nil`、`is_instance_of`、
  `pack_variant` / `unpack_variant`、`variant_is_nil`、`assign`、`go_if` / `goto`。
- 解构 route（ARRAY/DICTIONARY）缺「长度 / 键存在性」原语，路线选择见 §5.10 与 §9 R2。

---

## 4. 目标支持面

### 4.1 目标

`match` 语句整体按 **route-aware 分层毕业**（与 `for` 的 `ForLoweringContractRegistry` 同一范式）：

| Route | pattern 形态 | sema 毕业 | compile-ready |
|---|---|---|---|
| `WILDCARD` | `_` | Step 2 | Step 3 |
| `BINDING` | `var x`（含数组/字典内嵌套 `var x` 的**名字**进入 inventory） | Step 2 | Step 3（顶层）；嵌套 bind 随 Step 4 |
| `LITERAL` | int/float/String/StringName/bool/null 字面量 | Step 2 | Step 3 |
| `EXPRESSION` | 任意可求值表达式：裸标识符 / attribute chain（常量或运行时值引用）、call、二元运算等（对齐 Godot `PT_EXPRESSION` 并放宽形状白名单，见 §1.2-3 与 §9 R9） | Step 2 | Step 3 |
| `ARRAY` | `[p0, p1, ..]` | Step 2 | Step 4 |
| `DICTIONARY` | `{"k": p, ..}` / `{"k": _}`（仅键省略值形态经 Step 1 parse 实证确认不可表示，已从支持面删除，见 §9 R8） | Step 2 | Step 4 |

- sema 面（Step 2）对**全部 route** 一次性毕业：inventory / binding / plan 发布不区分 route
  （对齐 for「plan 发布不依赖 lowering readiness」的三层解耦，见
  `frontend_for_range_loop_implementation.md` §1.2）。
- compile gate 在 **Step 2** 即落地 route-aware 判定机制，但 ready set 初始为空：任何含合法
  `match` 的脚本在 `analyzeForCompile` 下都会收到锚定 `MatchStatement` root 的
  `sema.compile_check` route-not-ready blocker（`hasErrors()==true`，lowering 入口不会被打穿）。
  Step 3 起只翻转 route readiness：任一 section 含有未就绪 route 的 pattern，
  整个 `MatchStatement` 发 route-not-ready blocker（锚定 statement root，不重扫 body），
  与 for 的 `OBJECT_CUSTOM` 同一合同。
- match 内 `break` / `continue` 语义不变（隶属外层循环；Godot 4 已移除 match-continue 特例）。
- lambda 交互同步毕业：match section 内的 lambda 可记录；lambda 内的 match 正常解析；
  bind 变量可被 lambda capture（类型冻结为声明处类型，对齐既有 capture 合同）。

### 4.2 非目标（本计划全程不变）

见「文档状态 > 明确非目标」。补充：match subject 为 `void` call、pattern 表达式解析失败
（未声明标识符、非法 chain 等）等非法形态，全部由既有 expression / chain / type-check 合同
兜底，不为 match 新增特殊规则。非常量表达式 pattern **不是**非法形态（v7 起任意可求值
表达式均为合法 pattern，见 §1.2-3 与 §9 R9）。

---

## 5. 设计合同

### 5.1 `FrontendMatchPlan` 与 route 分类

新增纯语义侧表 `matchPlans()`，key = `MatchStatement` AST identity（对齐 `forIterationPlans()`）：

```
FrontendMatchPlan
  statement        : MatchStatement            // side-table key
  sections         : List<FrontendMatchSectionPlan>

FrontendMatchSectionPlan
  section          : MatchSection
  patterns         : List<FrontendMatchPatternPlan>
  hasGuard         : boolean

FrontendMatchPatternPlan
  patternNode      : Expression                // AST identity（LiteralExpression / IdentifierExpression /
                                               // PatternBindingExpression / ArrayExpression / DictionaryExpression）
  route            : FrontendMatchPatternRoute // WILDCARD / BINDING / LITERAL / EXPRESSION / ARRAY / DICTIONARY
  bindings         : List<FrontendMatchBindingPlan>  // 该 pattern 内（含嵌套）的全部 bind

FrontendMatchBindingPlan
  name             : String
  declaration      : PatternBindingExpression  // declaration identity
  topLevel         : boolean                   // 顶层 bind = true；嵌套 bind = false（类型恒 Variant）
```

约束（对齐 for plan 合同）：

- plan **不**携带 `GdCompilerType`、intrinsic 名、C helper、LIR 细节；只做结构分类与 bind 清单。
- 分类唯一真源是新增 `FrontendMatchSupport`（对齐 `FrontendForLoopSupport` 地位）：
  - `IdentifierExpression("_")` → `WILDCARD`（仅在 pattern 递归上下文内）；
  - `PatternBindingExpression` → `BINDING`；
  - `LiteralExpression` → `LITERAL`；
  - `ArrayExpression` / `DictionaryExpression` → `ARRAY` / `DICTIONARY`，元素/entry 递归分类；
  - 其余 `Expression`（裸标识符、attribute chain、call、二元运算等一切形态）→ `EXPRESSION`；
    分类器只做 AST 形状分派，**常量性不决定合法性**（v7 起任意可求值表达式均合法，
    §9 R9），只在 lowering 侧决定常量 / 运行时比较子模式（§5.9）。
- route readiness 的唯一事实源是 `FrontendMatchSupport.isRouteLoweringReady(route)` 的静态集合，
  集合内容随步骤单调增长、**不得预置终态**：Step 1/2 为空集（全部 route 返回 false，
  保证 Step 2 的 fail-closed 中间态）；Step 3 加入
  `WILDCARD / BINDING / LITERAL / EXPRESSION`；Step 4 加入 `ARRAY / DICTIONARY`。
  match route 不携带 operation descriptor 或 compiler-only state，因此**不**引入
  `ForLoweringContractRegistry` 式 registry 类；若未来 route 需要携带 lowering 描述符再升级
  （单调扩展，不得移除已就绪 route）。

### 5.2 结构毕业：policy 矩阵与 scope 身份

- `FrontendBodySemanticSupportPolicy`：`MATCH_SECTION_BODY` 从 `MATCH_SUBTREE` 行移入
  `EXECUTABLE_BODY`（`forBlockScopeKind` switch，当前 `:127`）；`MATCH_SUBTREE` 行与
  `FrontendVisibleValueDomain.MATCH_SUBTREE` 枚举值随引用清零后删除。exhaustive switch 会在
  编译期强制指出全部残留引用点，这是刻意的协议保护，不得用 `default` 分支规避。
- scope 图形状**不变**：`scopesByAst[section]` 与 `scopesByAst[section.body()]` 是同一个
  `BlockScope(MATCH_SECTION_BODY)` 对象；`owningScopeForDeclaration(PatternBindingExpression)`
  就取该对象。因 section/body 天然同实例，不存在 for 的「双录对象身份」陷阱，但
  `FrontendTypedLexicalEnvironment` 的 overlay 匹配仍按 `scope ==` 对象身份执行。
- `FrontendExecutableInventorySupport`（`canPublishCallableLocalValueInventory` /
  `isSupportedSuiteBodyRoot`）与 policy 的桥接同步放行 `MATCH_SECTION_BODY`，
  保持 `FrontendBodySemanticSupportPolicy` 单一事实源地位。

### 5.3 Pattern binding inventory 与 declaration index

- `FrontendVariableAnalyzer`：新增 match 绑定走查（对齐 `bindForIterator`，
  当前 `handleMatchStatement:307` 从 `SKIP_CHILDREN` 改为正式绑定）：
  - 对 section patterns 递归收集全部 `PatternBindingExpression`（含数组/字典嵌套），
    以 `PatternBindingExpression` 为 declaration identity 在 section 的
    `BlockScope(MATCH_SECTION_BODY)` 上 `defineLocal(name, Variant, patternBindingExpression)`，
    `ScopeValue.kind() == LOCAL`。baseline 恒 `Variant`（inventory 阶段不读 typed fact）。
  - duplicate / shadowing 复用既有 callable-local 规则与 `sema.variable_binding` owner：
    同 section 内 bind 重名、bind 与同 callable 既有 parameter/local 冲突，按现状合同报错。
  - `UnsupportedVariableBoundaryReporter.handleMatchStatement`（`:925`）删除 match 分支；
    `LambdaCaptureSourceScanner.handleMatchStatement`（`:989`）解除跳过，使 match 内的
    bare identifier / 嵌套 lambda 正常进入 capture 规划。
- `FrontendInterfacePhase.handleMatchStatement`（`:209`）：从 `SKIP_CHILDREN` 改为
  walk `value`、逐 section walk patterns/guard 并进入 section body 的 declaration index 记录；
  declaration index 新增 `FrontendBodyLocalDeclaration.Kind.PATTERN_BIND`
  （当前仅 `ITERATOR` / `ORDINARY_VAR`，`FrontendBodyLocalDeclaration.java:38-44`）：
  - 每个 section body 的条目顺序：`PATTERN_BIND` 条目按 pattern 源序排在头部
    （`sourceOrder 0..k-1`），`ORDINARY_VAR` 顺延（`sourceOrder >= k`），
    对齐 for 的「iterator 头位」不变量（`FrontendInterfacePhase.java:322-339` 先例）。
  - `enterSupportedBlock` 新增 match 专用重载（**不复用** `ForStatement ownerFor` 形参）：
    在 `walkStatements` 之前按 pattern 源序插入全部 `PATTERN_BIND` 条目（递归收集，含嵌套 bind）。
  - `FrontendBodyStructuralCompleteness` 双向校验扩展覆盖 `PATTERN_BIND`：
    `requireCompleteDeclaration`（`:151-167`）新增分支（declaration 必须是
    `PatternBindingExpression`、`scopesByAst[declaration] == expectedScope`、
    `expectedScope.resolveValueHere(name) == binding` 三重身份一致）；
    `requireScopeInventoryPublished`（`:184-191`）的 declaration identity 白名单加入
    `PatternBindingExpression`（当前只认 `VariableDeclaration` / `ForStatement`，
    不扩展会在 suite entry 的 certificate 处 fail-fast 而不是发诊断）。
- `FrontendStatementResolver`：`case MatchStatement` 从 `resolveUnsupportedRoot`（`:61`）改为
  新增 `resolveMatchStatement`：
  1. `runSupportedRoot(context, matchStatement.value())` 解析 subject；
  2. `ownerProcedures.runMatchPatternResolution(context, matchStatement)`（新 stage，见 §5.4）；
  3. `ownerProcedures.runVarTypePost(context, matchStatement)`：发布全部 bind 的
     `slotTypes()[PatternBindingExpression]`（对齐 for 的 `runForIterationResolution` +
     `runVarTypePost(forStatement)` 先例，`FrontendStatementResolver.java:122-123`）。
     `FrontendBodyOwnerProcedures.runVarTypePost`（`:353-378`，当前只认 `ForStatement` /
     `VariableDeclaration`）新增 `MatchStatement` 分支：对每个 bind 查 section branch scope 的
     accepted local，以 `effectiveScopeValue` 的精化结果发布 slot type；bind 因
     duplicate/shadowing 被拒而无 accepted local 时发 `sema.variable_slot_publication` warning
     （对齐 `reportRejectedLocalSlotPublication` 合同），供 compile gate 缺洞扫描升级（§5.7）；
  4. 逐 section：pattern 按下文「Pattern-context 解析分派」逐 route 处理（**禁止**把整棵
     pattern 子树交给普通 owner 管线），guard 走 condition 合同路径
     （`runSupportedRoot(context, guard)`），
     `childSuiteResolver.resolveChildSuite(context, section.body())` 进入 body；
  5. `flushStatementBoundary` 边界语义不变。
- **Pattern-context 解析分派（强制合同）**：pattern 递归上下文内的节点按 route 分派，
  与普通 executable 表达式管线严格隔离（`ArrayExpression` 的 expr typing 一律进
  container-literal 路径且 `openEnded==true` 直接 FAILED ——
  `FrontendExpressionSemanticSupport.java:807-812` 与
  `FrontendContainerLiteralSemanticSupport.java:98,154`；`PatternBindingExpression`
  在普通 expr typing 中显式 deferred —— `FrontendExpressionSemanticSupport.java:848-854`；
  `_` 只是普通 `IdentifierExpression`。若 pattern 整树走普通管线，会直接撞这三条冻结合同）：
  - `WILDCARD`（`IdentifierExpression("_")`）：不发布任何 binding / expressionTypes 事实，
    不进入普通 identifier 解析；`_` 不是引用。
  - `BINDING`（`PatternBindingExpression`）：不进入表达式 typing；该节点只作为
    declaration identity 参与 inventory / `slotTypes()` / CFG。普通 expr typing 的
    `PatternBindingExpression` deferred 分支保持 fail-closed 且在 match 上下文**永不触发**
    （分派器不为该节点调用普通管线）。
  - `ARRAY` / `DICTIONARY`：节点本身只做结构递归，不发布 `FrontendContainerLiteralPlan` /
    `expressionTypes`；元素、字典 key 与 value 子 pattern 递归按本分派处理；
    `openEnded` 仅在此上下文合法（普通上下文的 fail-closed 合同不变）。
  - `LITERAL` / `EXPRESSION`：作为叶子表达式走普通 owner 管线
    （top binding / chain / expr）；EXPRESSION 无形状白名单与常量性校验（v7，§9 R9），
    合法性与解析失败诊断完全由普通管线承担，分派层不预判。
  - `runSupportedRoot` / `runTopBinding` 等 generic walk 不得以 `MatchStatement` /
    `MatchSection` / 整棵 pattern 为 root 调用；pattern 中出现 lambda / 调用等任意形态时
    按 `EXPRESSION` 叶子走普通管线（lambda 照常记录/解析，运行时求值比较），
    不在分派层特判。
- `FrontendBodyOwnerProcedures.runUnsupported`（`:603-611`）的 `case MatchStatement` 删除；
  match 不再发 `sema.unsupported_binding_subtree` / `sema.unsupported_chain_route`。
  subject 的解析改由 `resolveMatchStatement` 第 1 步承接，可见性行为不变。

### 5.4 `MATCH_PATTERN_RESOLUTION` stage 与 bind 精化

- 新增 `FrontendSemanticStage.MATCH_PATTERN_RESOLUTION`，owner 顺序插入位置对齐 for：
  `top binding → local type stabilization → chain binding → expr typing →
  **match pattern resolution** → var type post`（仅 `MatchStatement` 的 statement-local
  owner procedures 使用）。
- 职责：构建并发布 `FrontendMatchPlan`（`FrontendMatchSupport.buildPlan(...)`）；
  对**顶层** bind 执行 slot 精化 `Variant → subject 静态类型`（仅当 subject 已发布稳定
  non-Variant 类型；否则保持 `Variant`），规则与 for 的 `FOR_ITERATION_RESOLUTION` 完全一致：
  只允许 `Variant → exact`、exact 同型 no-op、禁止 exact A→B、禁止 `void` / `GdCompilerType`；
  overlay 写入的 scope 必须是 `scopesByAst[section] == scopesByAst[section.body()]` 的同一对象。
- 嵌套 bind 不精化，恒 `Variant`（对齐 Godot §1.2-4）。
- **门控链强制改动表**（逐项对齐 for 毕业时的接线面；以下开关全部是穷尽 switch / 白名单 /
  sealed permits，漏改任一项都会在 suite export / patch 边界 fail-fast 抛协议异常，而不是发诊断；
  这不改动 resolution pipeline 的冻结 6 步顺序，只是扩展 stage 白名单）：
  1. `FrontendSemanticStage` 新增 `MATCH_PATTERN_RESOLUTION`（注释冻结顺序：在 `EXPR_TYPE`
     之后、`VAR_TYPE_POST` 之前）。「与 `FOR_ITERATION_RESOLUTION` 互斥」仅指
     statement-local owner 层面——同一 statement 的 owner procedures 不会两者都跑；
     **suite 级 transaction 必须同时允许两者**（同一 callable 内 `for` + `match` 会在一次
     `exportPatchTransaction` 中同时出现两个 stage）。两者必须使用不同的 `order()` 值
     （第 2 项：`MATCH_PATTERN_RESOLUTION -> 5`，`FOR_ITERATION_RESOLUTION` 保持 4）；
     不得做成 `EnumSet` 互斥校验，否则同一 callable 内 `for` + `match` 会在 suite export
     fail-fast。注意当前 `checkOrder` 允许同序（`order < previousOrder` 才拒绝），同序不会被拒，
     但会让两个 stage 失去稳定先后，禁止采用。
  2. `FrontendPatchTransaction.order()`（`patch/FrontendPatchTransaction.java:54-63`）穷尽
     switch 插入 `MATCH_PATTERN_RESOLUTION -> 5`，`VAR_TYPE_POST` / `LAMBDA_RESOLUTION`
     顺延为 6 / 7。
  3. `FrontendOwnerPatch` sealed permits（`patch/FrontendOwnerPatch.java:22-29`）新增
     `FrontendMatchResolutionPatch`。
  4. `FrontendAnalysisData.validateLocalSlotTypeUpdates`（`:399-408`）放行第三个 slot-update
     owner；`checkSlotUpdateDeclarationDomain`（`:422-438`）新增互斥规则
     「`MATCH_PATTERN_RESOLUTION` 的 slot update 只能以 `PatternBindingExpression` 为
     declaration identity」。
  5. `FrontendTypedLexicalEnvironment.addLocalSlotTypeUpdate`（`:230-245`）新增
     `case MATCH_PATTERN_RESOLUTION -> matchPatternSlotTypeUpdates` pending list；新增
     `putMatchPlan(...)`（对标 `putForIterationPlan`，`:248-259`）；`FrontendAnalysisData`
     新增 `matchPlans()` 稳定侧表与对应 pending/committed 管道。
  6. overlay 消费端（`findLocalSlotTypeUpdate` / `owningScopeForDeclaration`）扩展认
     match 更新序列；`scope ==` 对象身份规则不变。
- 独立 patch 类型 `FrontendMatchResolutionPatch`，不得混入
  `FrontendLocalTypeStabilizationPatch`；`LOCAL_TYPE_STABILIZATION` 不处理
  `PatternBindingExpression`（declaration domain 互斥，对齐 for 合同 §2.2）。
- bind 的 source-facing slot type 通过 `slotTypes()[PatternBindingExpression]` 在
  `VAR_TYPE_POST` 发布（对齐 `slotTypes()[ForStatement]` 合同，执行点见 §5.3
  `resolveMatchStatement` 第 3 步），供 CFG 分配 source slot。

### 5.5 VisibleValueResolver 解封

- `classifyBoundaryEdge`（`FrontendVisibleValueResolver.java:180-186`）删除两个
  `MatchSection` case；`classifyUnsupportedCurrentBlockScopeBoundary` 随 policy 翻转而自然放行
  （`MATCH_SECTION_BODY` 变为 `publishesLexicalInventory()==true`）。
- 可见性冻结规则：bind 在同一 section 的 guard / body 内可见；section 间互不可见；
  `match` 之后不可见 —— 这三条由「section 级独立 `BlockScope` + 普通逐层 lookup」自然推出，
  不新增特殊过滤规则。bind 不参与 `declaration-after-use` / `self-reference` 过滤
  （pattern 是「声明即绑定」，guard/body 内不存在先于声明的用点）。
- 合成 `MATCH_SECTION_BODY` scope 的 backstop 语义随 policy 翻转而反转为放行
  （对齐 for 的 `resolveAllowsSyntheticForBody...` 对偶测试）。

### 5.6 Pattern 形状校验诊断（type-check owner）

`FrontendTypeCheckAnalyzer.handleMatchStatement`（`:1201`）从 `SKIP_CHILDREN` 改为正式校验，
统一 owner 为 `sema.type_check`，锚定到违规 pattern / section 节点；以下规则全部对齐 Godot：

1. **bind 与多 pattern 互斥**：section 的 `patterns().size() > 1` 且含 `BINDING` route → error。
2. **expression pattern 合法性（v7 超集）**：`EXPRESSION` route 一律合法——任意可求值
   表达式（含 `f()`、`a+1`、`d["k"]`、`self.prop` 等 Godot 会拒绝的形状）都是合法
   pattern，**不设**常量性/形状 type-check 校验（gdcc 有意超集，§9 R9）。pattern 表达式
   解析失败（未声明标识符、非法 chain 等）时已有 upstream `sema.binding` / chain error，
   不重复发错（owner 单一化）。pattern 表达式引用同 section 嵌套 bind（如 `[var a, a]`）
   不设特殊规则——按普通可见性解析到 bind 槽，读取值为命中前的未赋值状态（对齐 Godot
   的实际行为），实施时以锚点测试锁定。
3. **guard 条件合同**：复用 Godot-compatible condition contract（`frontend_rules.md` L84）：
   只要求稳定 typed fact，允许非 `bool`，truthiness 归一化由 lowering 承接。
4. **数组/字典子结构**：字典 key 必须解析为常量（**保持 Godot 严格语义，不随 §9 R9
   放宽**），否则报 Godot 原文 `Expression in dictionary pattern key must be a constant.`
   （category = `sema.type_check`，锚定 key 节点）；gdcc 常量域 ⊂ Godot `is_constant`
   域（无通用常量折叠），Godot 可折叠而 gdcc 不可折叠的 key 形态同样报此错，记为窄口径
   常量域偏差（与 R1/R8 同级）；`openEnded` 合法性见 §9 R1。
5. **始终 walk 全部 section body**（对齐 for「始终遍历 body，与 route 无关」），
   route-not-ready 不在这里发错（compile gate 职责）。
6. 已有 upstream `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 事实时不追加诊断
   （对齐 for 的去重合同）。

subject 无独立「可匹配性」检查：任何稳定类型的 subject 都合法（Godot 同）；`void` subject 由
既有 expression 合同在外层拦截。

### 5.7 Compile gate：route readiness

- 本 gate 在 **Step 2** 即落地（ready set 初始为空，全部 match 收 route-not-ready），
  Step 3 / Step 4 只向 ready set 追加 route，不改变判定机制。Step 2 的中间态因此保持
  fail-closed：`analyzeForCompile` 对含 match 的脚本始终 `hasErrors()==true`，
  `FrontendLoweringAnalysisPass` 的 `hasErrors` 门控不会被合法 match 打穿。
- `FrontendCompileCheckAnalyzer.handleMatchStatement`（`:516-518`）从 `SKIP_CHILDREN` 改为：
  1. 读取 `matchPlans()` 中已发布 plan；plan 缺失（upstream error）→ 保持封口，由 upstream
     owner 持有诊断，compile gate 不补发；
  2. 全部 section 的全部 pattern route 均 `isRouteLoweringReady` → mark compile surface，
     重扫 subject / patterns / guard / section body 的 published facts（generic
     `BLOCKED/DEFERRED/FAILED/UNSUPPORTED` blocker 合同不变，`frontend_rules.md` L43）；
  3. 任一 route 未就绪（Step 2 的全部、Step 3 的 `ARRAY` / `DICTIONARY`）→ 在
     `MatchStatement` root 发 `sema.compile_check` route-not-ready blocker，**不重扫 body**
     （对齐 for §4.3 的 `reportExplicitCompileBlock` 先例，`FrontendCompileCheckAnalyzer.java:497-505`）。
- 去重：同一 `MatchStatement` 已有 upstream error（如 `sema.type_check` 的 pattern 校验错误）
  时省略 route-not-ready（对齐 `frontend_rules.md` L17-27 的 owner 单一化与 for §4.3 的去重合同；
  允许的共存 category 走 L50 的静态配置维护）。
- `scanSlotTypeCompileBlocks`（`FrontendCompileCheckAnalyzer.java:783-807`）扩展覆盖
  `PatternBindingExpression`：仅在 route-ready 且已 mark compile surface 后扫描；
  与 `VariableDeclaration` 同一门闩——bind 缺 `slotTypes()` 且存在 upstream
  `sema.variable_slot_publication` warning（var-type-post 对被拒 bind 发出，见 §5.3 第 3 步）时
  补发 `sema.compile_check`（对齐 `frontend_rules.md` L49 的缺洞升级合同）。无 upstream
  warning 的缺洞属于协议破坏，不在此路径兜底，由 CFG 跨表验证 fail-fast 拦截。

### 5.8 CFG 形状与 body lowering（首批四 route）

- `FrontendCfgGraphBuilder.processStatement()` 新增 `case MatchStatement -> processMatchStatement(...)`：
  1. subject `buildValue` **单次求值**为一个 temp-backed value（`cfg_tmp_<id>` 命名纪律不变）；
  2. 每个 section 构建测试片段为 `BranchNode` 链，命中端进入 section body block，
     未命中端链到下一 section；最后一个 section 的未命中端与所有 body 出口汇合到
     `mergeId`（无命中即 no-op，直接落到 merge）。全终止（全 return/break）的 match 允许
     `mergeId -> TERMINAL_MERGE`，规则与 if 相同；`TERMINAL_MERGE` 不得作为 `goto` 目标。
  3. 新增 `FrontendMatchRegion`（AST identity keyed，进入 `frontendCfgRegions` side-table），
     锚点：`headerEntryId` / 每 section `testEntryId` + `bodyEntryId` / `mergeId`。
- 测试片段按 route 分解为显式 `ValueOpItem`（禁止重读 AST、禁止用外层 value id 充当
  branch condition，遵守 `frontend_rules.md` L86 的 `conditionRoot` 对齐与 L87 的
  branch-local 独立 value id 合同）：
  - `WILDCARD`：无测试，body 即目标（等价常量 true；可用既有 `BoolConstantItem`）。
  - `BINDING`（顶层）：无测试；body 起始把 subject temp 经 `materializeFrontendBoundaryValue`
    物化到 bind 的 `slotTypes()` 类型后 `AssignInsn` 写入 bind 的 source slot（source 名）。
  - `LITERAL` / `EXPRESSION`：类型严格相等（§5.9，EXPRESSION 分常量 / 运行时两个子模式）。
  - guard：pattern 命中后接 guard 的 `buildCondition` 展开（`and`/`or`/`not` 短路复用既有
    条件合同）；guard false 链到下一 section。
  - 多 pattern OR：同 section 各 pattern 的命中端全部指向 body，未命中端串联形成短路——
    后一 pattern 仅在前者未命中时求值（运行时子模式可能有副作用，**禁止 eager 求值全部
    pattern**），不引入 value 语境 `BinaryOp(OR)`，遵守 `frontend_rules.md` L108。
- body lowering（bind 槽走 for 式 registry，**禁止**假设「policy 翻转后 source-local 预声明
  自动覆盖 bind」——`declareSourceLocalSlots()` 只扫 `LocalDeclarationItem`
  （`FrontendBodyLoweringSession.java:1312-1325`），且 `LocalDeclarationItem.declaration`
  的类型是 `VariableDeclaration`（`cfg/item/LocalDeclarationItem.java:17-19`），
  撑不住 `PatternBindingExpression`，也不为其放宽）：
  - CFG builder 与 `FrontendMatchRegion` 同批发布 `FrontendMatchBindSlot` registry
    （key = `PatternBindingExpression` identity；字段：source slot id（源码名）、
    exposedType（取 `slotTypes()` 发布值）、owning section identity），进入
    `FunctionLoweringContext` 的 match 侧表（对标 `frontendForSourceIteratorSlots`）。
  - `FrontendBodyLoweringSession` 新增 `declareMatchBindSlots()`（对标 `declareForLoopSlots()`，
    `FrontendBodyLoweringSession.java:1353-1361`），在任何 block 物化前预声明 bind 槽；
    跨表验证（slot owner / type / 唯一性、section scope 身份、bind id 不混入 ordinary
    `LocalDeclarationItem` 面）。
  - bind 提交用独立 `MatchBindItem`（`ValueOpItem`，anchor = `PatternBindingExpression`，
    operand = subject temp value id），挂在 section body 入口 sequence 头部（对齐 for 的
    `ForLoopGetItem` 位置纪律）；lowering = `materializeFrontendBoundaryValue` +
    `AssignInsn(bindSlot)`。
  - 其余新 item 的 processor 在 `FrontendSequenceItemInsnLoweringProcessors` 注册；
    item 字段在 CFG build 时固化，body lowering 不得重查 AST。

### 5.9 类型严格相等的 lowering 分解（LITERAL / EXPRESSION）

对齐 Godot §1.2-2（`typeof` 相等 AND `OP_EQUAL` 值相等，String/StringName 交叉例外）。
`EXPRESSION` route 按 published facts 分两个子模式（**常量性只决定 lowering 策略，
不决定合法性**）：解析为常量（`CONSTANT` binding / `load_static` chain / 语言常量）→
**常量子模式**；否则（变量、成员、call、二元运算等任意可求值表达式）→ **运行时子模式**。

1. **subject 静态类型已知且非 Variant**：类型比较在 CFG build 期静态折叠 —
   - 【LITERAL / 常量子模式】pattern 侧类型族与 subject 静态类型一致 → 省略类型分支，
     只发值比较 `binary_op "EQUAL"`（操作数按既有 boundary 物化规则准备）；
   - 【LITERAL / 常量子模式】不一致（如 subject `int` vs literal `"a"`，或 `int` vs
     `1.0`）→ 该 pattern 静态不可能命中，测试片段折叠为常量 false（`BoolConstantItem`），
     body 仍然建图但不可达；不做 warning（非目标）。
   - 【运行时子模式】：只要控制流到达该 pattern 测试段，就**必须** `buildValue(patternExpr)`
     得到 `pv`，**不得因静态类型不兼容而跳过求值**（保留副作用，对齐 Godot 对合法
     identifier/attribute pattern 的运行时求值）。静态类型只决定类型检查的形态：
     两侧已知且兼容 / String↔StringName 交叉 → 省略 typeof 分支，只发值比较；
     两侧已知且不兼容 → 仍求值 `pv`，测试结果接常量 false（R7 的「body 建图但不可达」
     仍适用，但不跳过 `buildValue`）；任一侧 `Variant` / 未知 → 走第 2 条完整运行时链。
     完全折叠为 false 且不物化 pattern 操作数，仅限 LITERAL / 常量子模式（无副作用）。
2. **subject 为 Variant / 静态未知**：运行时分解为 `go_if` 短路链 —
   - 先 materialize / pack 出 Variant subject；subject 的 `t = get_variant_type $subject`
     每个 match 只求值一次并跨 pattern 复用（对齐 Godot compiler 的 `p_type_addr`）；
   - **LITERAL / 常量子模式**：类型相等用 `literal_int <type_id>` + `binary_op "EQUAL"`；
     String 家族的类型相等是 `(t == STRING) or (t == STRING_NAME)`，用两级 `BranchNode`
     短路展开（禁 `BinaryOp(OR)`，同上）；值相等用
     `binary_op "EQUAL" $subject $patternOperand`；pattern 操作数来源：`LITERAL` → 既有
     `literal_*` 物化；裸全局常量/枚举成员/语言常量 → 复用
     `frontend_global_constant_implementation.md` 的 `LiteralIntInsn` / `LiteralFloatInsn`
     物化链；限定式枚举 chain → 既有 `load_static` 路径。
   - **运行时子模式（v7 新增，对齐 Godot PT_EXPRESSION lowering）**：pattern 表达式经
     `buildValue(patternExpr)` 求值为 temp `pv`——**求值时机冻结：只在控制流到达该
     pattern 的测试段时求值**（section 自上而下、section 内多 pattern 从左到右，短路链
     保护，未到达不求值；对齐 Godot compiler 的 or/and 短路 codegen 结构）；随后
     `pt = get_variant_type $pv`，类型相等 = `(t == pt)` **或双向 String↔StringName 交叉**
     （`(t == STRING && pt == STRING_NAME) || (t == STRING_NAME && pt == STRING)`，
     两方向都查——区别于 LITERAL 的单向展开，因为 pattern 值类型编译期未知），同样用
     `BranchNode` 短路链展开；值相等 `binary_op "EQUAL" $subject $pv`，与类型结果 AND。
   - `null` literal 走 `variant_is_nil` 特例（subject 非 Variant 时静态折叠：仅静态 `Nil`
     可命中）；运行时子模式求值为 `null` 不设特例，走通用路径（`pt == NIL` 比较自然覆盖）。
3. 常量子模式不要求 frontend 新增常量求值器：常量值不需要编译期已知，只需要
   **类型已知 + 运行时可求值**（枚举 chain 的运行时 `load_static` 即属此类）。
4. Godot `is_constant` 域 ⊃ gdcc 常量域（无通用常量折叠）：`1+2`、`Vector2(1,2)`、
   `sin(20)`、`-1` 等 Godot 可折叠形态在 gdcc 落入运行时子模式。对纯表达式（无副作用）
   可观察行为与 Godot 等价，仅求值时机从编译期变为运行时（§9 R9）；这不是兼容性偏差。

### 5.10 数组/字典解构 lowering（Step 4，决策点）

解构需要「长度检查 / 键存在性 / 安全取值」三种能力。候选路线：

- 路线 A（首选，零新指令）：类型守卫后把 subject 物化为 `Array` / `Dictionary` 静态类型槽
  （既有 boundary materialization），用既有 builtin method call 发 `size()` / `has(key)`，
  用 `variant_get_indexed` / `variant_get_keyed` 取值（取值前已被长度/键存在分支保护，
  规避缺键 `Nil` 二义）；逐元素递归展开子 pattern 测试与嵌套 bind 的 `AssignInsn`。
- 路线 B（备选）：新增 `gdcc.match_*` intrinsic 族 + C helper。仅当路线 A 在 typed boundary /
  dynamic dispatch 上被证伪才启用；启用前必须先冻结 `gdcc_lir_intrinsic.md` 并同步
  backend，遵守 for 维护规则「先冻结 intrinsic 再注册 contract」。

长度语义：无 `..` 时 `len == 元素数`，有 `..` 时 `len >= 元素数`（`..` 本身不计数、
不捕获剩余元素，对齐 Godot §1.2-5/6）。字典 entry 恒带 value pattern（gdparser
`DictEntry.value` 非 nullable，仅键形态经 R8 实证已移出支持面），故无需 `has`-only 分支。
数组元素 / 字典 value pattern 递归继承完整 route 分派——含 `EXPRESSION` 运行时子模式
（Godot 对嵌套 pattern 同样递归 `resolve_match_pattern`，§1.2-3 的合法性与比较语义在
嵌套位置一致）；字典 key 保持常量限定（§5.6-4），key 无运行时求值路径。

### 5.11 Lambda 交互合同

- match section 内的 lambda 照常 `recordCallable`（既有 lambda 合同），其 capture 可见 section
  bind；capture 类型取 bind 的声明处类型（顶层 bind = 精化后类型或 `Variant`，嵌套 = `Variant`）。
- lambda 内的 match 正常解析；`LambdaCaptureSourceScanner` 解除对 match 的跳过后，
  match 内的 bare identifier 用点正常参与外层 capture 规划（post-order 顺序不变）。
- 既有负向锚点 `lambdaInsideMatchSectionStaysUnrecordedAndFailClosed` 等在本计划下**语义反转**，
  处置见 §7 测试清单。

### 5.12 Loop control 合同

`FrontendLoopControlFlowAnalyzer` 现状已穿透 match 且不重置 loop depth
（`frontend_loop_control_flow_analyzer_implementation.md` §2.6：「`if` / `elif` / `else` / `match` /
普通 block 不重置 loop depth」），本计划**不改变**该合同，仅补充测试锚点：
match section body 内对外层循环的 `break` / `continue` 合法；无外层循环时照旧报
`sema.loop_control_flow`。

---

## 6. 分阶段实施步骤与验收细则

> 通用验收纪律（每步都必须满足）：
> - 只跑靶向测试：`pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests <类名列表>`；
>   步骤收尾跑 `./gradlew classes --no-daemon --info --console=plain` 保证全量编译；
>   涉及既有测试修改的步骤加跑受影响测试类。
> - 每个新恢复路径同时覆盖 happy path 与 negative path；negative 至少锚定
>   「正确 category + 坏 subtree 被跳过 + 同 module 其他合法 subtree 仍继续工作」
>   （`frontend_rules.md` 测试约定）。
> - 发现既有测试失败时，先查实现根因，不得改测试迁就错误行为。

### Step 1：规格冻结、`FrontendMatchSupport` 分类器与 parse 实证探针

> 状态：已完成（2026-08-21）。产物：`FrontendMatchSupport` / `FrontendMatchPatternRoute` /
> `FrontendMatchPlan` / `FrontendMatchSectionPlan` / `FrontendMatchPatternPlan` /
> `FrontendMatchBindingPlan`（`gd.script.gdcc.frontend.sema`，未接入任何 analyzer）；
> 测试 `FrontendMatchSupportTest`（13 项）与 `FrontendMatchParseBehaviorTest`（12 项 parse
> 实证探针）；既有 match 锚点回归全绿，管线行为零变化。parse 实证结论已回写 §2 陷阱 3 与
> §9 R1 / R8；`samePlan` 幂等合并随 Step 2 的 `matchPlans()` 侧表管道一并落地。
> 改名回执（v7 清单）已于 2026-08-21 在本步完成，Step 2 不再携带该回执：
> `FrontendMatchPatternRoute.CONSTANT_EXPRESSION` → `EXPRESSION`（枚举值 + javadoc：合法性由
> 普通表达式管线承担，常量性只在 lowering 选子模式）；`FrontendMatchSupport`
> （`LOWERING_READY_ROUTES` 注释、`classifyPatternRoute` javadoc 与 `default` 分支；
> 字典 key 注释保持「keys are constant expressions, never patterns」不变——§5.6-4，
> key 不在超集内）；`FrontendMatchPatternPlan` javadoc（`LITERAL / EXPRESSION leaves`）；
> `FrontendMatchSupportTest`（断言、类头注释、方法名
> `classifiesAttributeChainAndOtherExpressionsAsConstantExpression` → `...AsExpression`）。
> `FrontendMatchSupportTest` / `FrontendMatchParseBehaviorTest` 靶向测试绿。

改动：

- 新增 `gd.script.gdcc.frontend.sema.FrontendMatchSupport`（与 `FrontendForLoopSupport` /
  `FrontendBodySemanticSupportPolicy` 同包同层；分类真源 + route readiness 静态集合）。
- 新增 plan 模型类（`FrontendMatchPlan` / section / pattern / binding plan）与
  `FrontendMatchPatternRoute` 枚举；此时**不**接入任何 analyzer，管线行为零变化。

验收：

- 新增 `FrontendMatchSupportTest`：7 种 pattern 形态的分类（literal / `_` / `var x` /
  裸标识符 / attribute chain / 数组（含嵌套 bind、`..`）/ 字典（嵌套））、
  多 pattern 列表、guard 有无、嵌套 bind 收集完整性与 `topLevel` 标记。
- **parse 实证探针**（先于分类器定稿）：`match x: {"k": _}:` / `{"a", "b"}`（仅键省略值）/
  `[.., 1]` vs `[1, ..]` / 嵌套 `{"user": {"name": var n}}`，实证 gdparser 0.5.3 的可表示性
  （`DictEntry.value` 非 nullable，mapper 对 entry 强制要求 value 字段）。
  若仅键省略值不可表示：从支持面删除该形态（以 `{"k": _}` 替代），按 §9 R8 记录 parser 偏差；
  **不得**为此修改/升级 gdparser（架构级变更，不在本计划范围）。
- 全部既有 match 锚点测试**保持绿色不变**（本步不改管线）。

### Step 2：shared semantic 毕业与空 ready set 的 compile gate

改动（§5.2 - §5.7、§5.11、§5.12 全部 sema 侧与 compile gate 条目）：

- policy 翻转（`FrontendBodySemanticSupportPolicy`、`FrontendExecutableInventorySupport`、
  `FrontendVisibleValueDomain` 清理、`FrontendSuiteContext.visibleValueDomainForCurrentBody`）；
- `FrontendVariableAnalyzer` match inventory + boundary reporter / capture scanner 解封；
- `FrontendInterfacePhase` declaration index（`PATTERN_BIND`，含 `enterSupportedBlock` match
  重载）+ `FrontendBodyStructuralCompleteness` 扩展；
- `FrontendStatementResolver.resolveMatchStatement` + pattern-context 解析分派 +
  `FrontendBodyOwnerProcedures` 新 stage 与 `runUnsupported` 的 match 分支删除；
- §5.4 门控链强制改动表 6 项全部落地；
- `FrontendVisibleValueResolver` 解封；
- `FrontendTypeCheckAnalyzer` 的 §5.6 校验；
- `FrontendCompileCheckAnalyzer.handleMatchStatement` 按 §5.7 落地 route-aware 判定，
  **ready set 为空**：所有 match 收锚定 statement root 的 `sema.compile_check`
  route-not-ready blocker，不重扫 body——本步 e2e 不可编译 match，
  这是刻意的 fail-closed 中间态（删掉 upstream unsupported 诊断后，若 gate 不发显式 blocker，
  `hasErrors()==false` 会让 match 漏进 lowering 并撞 `FrontendCfgGraphBuilder` 的
  `unsupportedReachableStatement` fail-fast，协议异常而非诊断）；
- `FrontendAnalysisInspectionTool` 的 match 展示扩展，分两条处理
  （`hasUnsupportedOrDeferredAncestor` 只在节点 UNPUBLISHED 时经 `inferUnpublishedReason`
  调用，`FrontendAnalysisInspectionTool.java:433-447`）：
  - 删除其中无条件的 `MatchStatement -> true` 分支：毕业后 subject / guard /
    `LITERAL` / `EXPRESSION` 叶子（含 call / 二元 / 变量引用）均有 published 事实，
    不依赖该分支；保留它会继续把整棵 match 子树当作
    intentionally skipped（`ForStatement` 同类分支是既有脏点，不作为模板）；
  - 对合同内故意不发布的 pattern 节点（WILDCARD `_` / BINDING `var x` / ARRAY / DICTIONARY
    pattern 根），`inferUnpublishedReason` 新增「pattern-context 故意不发布」的专用 reason
    文案；不得复用 skipped-subtree / unsupported 措辞，也不得显示为 phase 漏发。
    **判定顺序写死**（对齐 `isRouteHeadTypeMeta` 的既有先例）：`inferUnpublishedReason`
    依次判定 1) route-head TYPE_META 专用 reason；2) pattern-context 故意不发布专用 reason
    （WILDCARD / BINDING / ARRAY / DICTIONARY 根，含嵌套 pattern）；3) 其余才走
    `hasUnsupportedOrDeferredAncestor`；4) 否则 phase 漏发。专用 reason 必须先于 ancestor
    判定——`hasUnsupportedOrDeferredAncestor` 沿全部祖先返回 true，残留的
    `ForStatement -> true` 脏点会把 `for` body 内 match 的 pattern 节点短路成 skipped 措辞。
- 文档同步（本步即做，不留到 Step 5）：`frontend_rules.md` L41 / L66 改为中间态措辞
  （match 进入 shared semantic 支持面；compile gate 按 route-aware 处理且尚无就绪 route）、
  **L123 同步**（match 进入 sema 支持面后，pattern 内全局常量/枚举裸访问不再 deferred，
  改走普通 EXPRESSION 合同）、`diagnostic_manager.md` §2.8、`frontend_resolution_pipeline_implementation.md`、
  `frontend_compile_check_analyzer_implementation.md`、`frontend_variable_analyzer_implementation.md`、
  `frontend_top_binding_analyzer_implementation.md`、`frontend_visible_value_resolver_implementation.md`、
  `frontend_type_check_analyzer_implementation.md`、`frontend_chain_binding_expr_type_implementation.md`、
  `scope_analyzer_implementation.md`、`frontend_lambda_implementation.md` 的 match 条目。

验收：

- §7 清单中标注「Step 2」的既有锚点测试全部按处置表改写并通过（含两个 compile-check 测试
  改写为 route-not-ready 断言）；
- 新增 `FrontendMatchSemanticsTest`（或并入既有类的正向组）：
  - bind 在 guard / body 内可见、跨 section 不可见、`match` 后不可见；
  - bind 重名 / 与外层 local 冲突的 `sema.variable_binding` 负向锚点；
  - bind 精化：subject 静态 `int` 时顶层 bind 精化为 `int`、subject `Variant` 时保持 `Variant`、
    嵌套 bind 恒 `Variant`；
  - §5.6 校验锚点：bind 与多 pattern 互斥、字典 key 非常量（Godot 原文消息）各自的负向锚点
    （category = `sema.type_check` + 锚定节点 + 其余合法 section 仍发布 facts）；
    **v7 正向锚点**：任意表达式 pattern（裸变量标识符 / attribute chain / call / 二元运算）
    均合法——无 type-check 诊断、expressionTypes 等 facts 正常发布；
  - pattern-context 分派负向锚点：`[1, ..]` pattern 不产生 `FrontendContainerLiteralPlan` /
    container FAILED 事实；`var x` pattern 不产生 expressionTypes 事实；`_` 不产生 binding 事实；
  - compile gate 中间态锚点：仅含合法 match 的脚本 `analyzeForCompile` 后 `hasErrors()==true`
    且唯一新诊断是锚定 `MatchStatement` root 的 route-not-ready；已有 upstream error 时不重复发；
  - match 内 lambda 可记录且 capture bind；lambda 内 match 正常解析；
  - match 内 `break` / `continue` 对外层循环合法、无循环时报 `sema.loop_control_flow`；
  - inspection：match 子树内已发布 expression 正常显示 published 事实；故意不发布的
    pattern 节点显示专用「pattern-context 故意不发布」reason，不再是 skipped/unsupported 措辞；
    **`for` body 内 match** 的故意不发布节点仍显示专用 reason（不得被外层
    `ForStatement -> true` 祖先短路回 skipped 措辞）；
- 回归：`FrontendScopeAnalyzerTest` / `FrontendInterfacePhaseTest` / `FrontendSuiteResolverTest` /
  `FrontendVariableAnalyzerTest` / `FrontendLambdaSuiteResolutionTest` /
  `FrontendBodySemanticSupportPolicyTest` / `FrontendSemanticAnalyzerFrameworkTest` /
  `FrontendVisibleValueResolverTest` / `FrontendTypeCheckAnalyzerTest` /
  `FrontendLoopControlFlowAnalyzerTest` / `FrontendCompileCheckAnalyzerTest` /
  `FrontendAnalysisInspectionToolTest` 全绿。

### Step 3：首批四 route 的 CFG/body lowering

改动（§5.8 - §5.9；compile gate 机制已在 Step 2 落地）：

- route readiness 集合加入 `WILDCARD` / `BINDING` / `LITERAL` / `EXPRESSION`；
  `handleMatchStatement` 的就绪分支开始 mark compile surface 并重扫 facts；
- `FrontendCfgGraphBuilder.processMatchStatement` + `FrontendMatchRegion` +
  `FrontendMatchBindSlot` registry + `MatchBindItem` 等测试用 `ValueOpItem`
  类型与 `FrontendSequenceItemInsnLoweringProcessors` 注册；
- `FrontendBodyLoweringSession.declareMatchBindSlots()` 预声明与跨表验证。
- 文档同步（本步即做）：`frontend_rules.md` compile surface 相关措辞、
  **L49 同步**（缺洞升级对象从 callable-local `VariableDeclaration` 扩到
  `PatternBindingExpression`）、`frontend_lowering_plan.md` backlog 移除首批 route、
  `frontend_lowering_cfg_pass_implementation.md` 新增 `FrontendMatchRegion` 合同。

验收：

- 新增 `FrontendCfgGraphBuilderMatchTest`：region 锚点、BranchNode 链形状、branch-local value id
  纪律、静态折叠（`int` subject vs `int` literal 省略类型分支；`int` subject vs `"a"` literal
  折叠常量 false）、guard 短路、多 pattern OR、TERMINAL_MERGE 规则、bind slot registry 与
  跨表验证；
- `FrontendCompileCheckAnalyzerTest`：route-ready 放行并重扫 facts、route-not-ready 锚定
  statement root、不重扫 body、与 upstream error 的去重；ARRAY/DICTIONARY pattern 在本步仍
  route-not-ready；bind 缺 `slotTypes()` **且存在 upstream
  `sema.variable_slot_publication` warning** 时补发 `sema.compile_check`；
  无 upstream warning 的缺洞不在 compile gate 兜底，由 CFG 跨表验证 fail-fast
  （对齐 §5.7 / `frontend_rules.md` L49）；
- `FrontendLoweringBodyInsnPassTest`：`LITERAL`/`EXPRESSION` 常量子模式的
  `get_variant_type` + `binary_op EQUAL` 序列、String/StringName 交叉的两级短路、
  `BINDING` 的 `MatchBindItem` 物化 + `AssignInsn`、`null` literal 的 `variant_is_nil`；
  `EXPRESSION` **运行时子模式**（v7）：`buildValue` 求值 + 双侧 `get_variant_type` 比较 +
  **双向** String/StringName 交叉、求值时机惰性（未到达的 pattern 不求值，以 call 计数类
  fixture 锚定）、pattern 静态类型已知且兼容时省略 typeof 分支但**仍** `buildValue` 求值、
  静态不兼容时仍 `buildValue`（call/getter 计数 +1）且测试结果为常量 false——
  完全跳过求值的 fold-to-false 仅限 LITERAL / 常量子模式；
- e2e：新增 `src/test/resources/unit_test/script/control_flow/match_*` 正向 fixture
  （literal 命中 / wildcard 兜底 / 多 pattern OR / guard / bind 值使用 / 无命中 no-op），
  登记 `GdScriptUnitTestCompileRunnerTest.EXPECTED_SCRIPT_PATHS`；zig / Godot 可用时
  `GdScriptUnitTestCompileRunnerTest` 对应动态测试通过，环境缺失时按既有 Assumptions 跳过；
- 回归：`FrontendCfgGraphBuilder*Test`、`FrontendLoweringBodyInsnPassTest`、
  `FrontendCompileCheckAnalyzerTest` 全绿。

### Step 4：ARRAY / DICTIONARY 解构 route

改动（§5.10）：路线 A 落地；route readiness 集合加入 `ARRAY` / `DICTIONARY`。
文档同步（本步即做）：`frontend_rules.md` compile surface 措辞、
`frontend_lowering_plan.md` backlog 移除解构 route、
`frontend_container_literal_implementation.md` §2.6 的交叉引用更新。

验收：

- 解构单测：长度相等 / `..` 长度下界 / 键存在 / 仅键 entry / 嵌套数组字典 / 嵌套 bind 赋值
  （类型恒 `Variant`）/ 解构失败落到下一 section / 与 guard 组合；
- compile gate：ARRAY/DICTIONARY 放行后的 facts 重扫；
- e2e fixture 扩展 `match_array_*` / `match_dict_*`；
- 若启用路线 B，验收追加 `gdcc_lir_intrinsic.md` 冻结条目与 backend helper 测试。

### Step 5：文档终态冻结与毕业判定

各步已同步对应合同文档（Step 2/3/4 的「文档同步」条目），本步只做终态收口：
`frontend_rules.md` MVP 支持约定转正 match 条目、
`doc/test_error/test_suite_engine_integration_known_limits.md` §1、`doc/benchmark.md`、
`doc/test_suite.md` 更新，以及 §8 清单逐项核对关闭。

验收：

- 毕业三同时（`frontend_lowering_plan.md` §6）：lowering 产物稳定 + backend 可消费 +
  文档与正反测试同步，逐条核对；
- 全量 `./gradlew clean build --no-daemon --info --console=plain` 绿；
- 本计划文档转为冻结合同（移除步骤进度，保留合同与边界）。

---

## 7. 既有测试锚点处置清单

| 测试（file:line） | 处置 | 步骤 |
|---|---|---|
| `FrontendMatchSupportTest`（Step 1 已落地） | ~~v7 改名回执~~ 已完成（Step 1 状态块）：`CONSTANT_EXPRESSION` → `EXPRESSION` 断言/类头/方法名同步；裸变量标识符正向分类锚点已存在（`value` 用例） | 1（已完成） |
| `FrontendVisibleValueResolverTest.resolveRejectsSyntheticMatchSectionCurrentScopeEvenWithoutMatchAstBoundary`（679-708） | 反转为合成 `MATCH_SECTION_BODY` 放行（对齐 for 对偶） | 2 |
| `FrontendVisibleValueResolverTest.resolveSealsMatchSectionBodyAsDeferredUnsupported`（739-765） | 改写为正向：bind 在 guard/body 可见、section 外不可见 | 2 |
| `FrontendVariableAnalyzerTest.analyzeBindsForInventoryWhileMatchAndBlockLocalConstRemainUnsupported`（357-439） | 拆分：for 正向保留；match 半部改正向 inventory 断言；block-local const 半部保持负向 | 2 |
| `FrontendVariableAnalyzerTest.analyzeStillReportsMatchAndBlockLocalConstInsideLambdaBody`（640-696） | 同上拆分，lambda 内 match 改正向 | 2 |
| `FrontendScopeAnalyzerTest.analyzeBuildsLoopAndMatchBranchScopesWhileLeavingDeferredBindingsUnfilled`（357-426） | 结构断言保留；`resolveValue("bound")==null` 反转为正向 LOCAL 断言 | 2 |
| `FrontendInterfacePhaseTest.publishesForInventoryWhileUnsupportedFeatureOwnedBodiesStayExcluded`（185-252） | `matchSectionBody` 的 `containsSupportedBlock`/`containsBodyRoot` 反转为 true + `PATTERN_BIND` 条目断言 | 2 |
| `FrontendInterfacePhaseTest.nestedForBodiesPublishInventoryAndLambdaBodiesWhileMatchOrConstSubtreesStayClosed`（300-349） | 同上；const 部分保持 | 2 |
| `FrontendSuiteResolverTest.forBodyAndLambdaResolveWhileUnsupportedFeatureOwnedBodiesRemainFailClosed`（212-291） | 反转：`matchSectionBody` 进入 supported、`hasOwnerEvent(fromMatch)==true`，补 subject→pattern→guard→body 的 source-order 断言 | 2 |
| `FrontendLambdaSuiteResolutionTest.lambdaContainingMatchKeepsCallableTypeAndUpstreamMatchDiagnostics`（139-170） | 反转：lambda 内 match 无 unsupported 诊断、facts 正常发布 | 2 |
| `FrontendLambdaSuiteResolutionTest.lambdaInsideMatchSectionStaysUnrecordedAndFailClosed`（385-405） | 反转：lambdaPlans 非 null、capture 类型冻结断言 | 2 |
| `FrontendBodySemanticSupportPolicyTest`（42-61 / 117-145 等） | `MATCH_SECTION_BODY` 移入 supported 集合；deferred domain 表删除 `MATCH_SUBTREE` | 2 |
| `FrontendSemanticAnalyzerFrameworkTest.defaultInterfaceBodyPipelineSupportsForWhileOtherUnsupportedSubtreesStayFailClosed`（1050-1093） | match 半部转正（slotTypes/symbolBindings 正向断言），const 半部保持 | 2 |
| `FrontendCompileCheckAnalyzerTest.analyzeForCompileSkipsExplicitCompileBlocksOutsideCompileSurface`（913-951） | 改写：`unsupported_binding_subtree` 计数 `==3` 改 `==2`（仅剩 parameter default + const）；match 收 1 条锚定 statement root 的 `sema.compile_check` route-not-ready；match 内 preload/`$Node`/`as`/`is`/`assert` 正常发布 shared facts 但不被 compile gate 重扫。Step 3 route-ready 后再次改写为放行 + 重扫断言 | 2（Step 3 再改） |
| `FrontendCompileCheckAnalyzerTest.analyzeForCompileLambdaBodyMatchFailsWithoutCompileCheckWrap`（2122-2151） | 改写：lambda 内 match 无 `unsupported_binding_subtree`，改由 route-not-ready `sema.compile_check` 锚定。Step 3 route-ready 后反转为放行断言 | 2（Step 3 再改） |
| `FrontendAnalysisInspectionToolTest` 相关展示探针（101-119 等） | 随 inspection 的 match 展示扩展更新 | 2 |

原则：纯边界锚点（只为证明「match 不支持」）删除或改写为正向；复合场景锚点
（source-order 遍历 / 混合 body 家族计数）改写场景断言、保留场景骨架，不得一删了之；
家族计数断言（`==3` 类）借机改为按 range 精确定位，降低未来 const 毕业时的连锁破坏。

---

## 8. 文档同步清单

纪律：**每步改代码的同时同步该步对应的合同文档**（见 Step 2/3/4 的「文档同步」条目），
不得把文档积压到 Step 5；Step 5 只做终态冻结措辞与逐项核对。全量清单：

1. `frontend_rules.md`：L41 compile-gate 跳过集合移除 `match`、L66 MVP 约定转正、
   L123 全局常量条目的「`match` pattern 内使用仍 deferred」解除。
2. `diagnostic_manager.md` §2.8：`sema.unsupported_variable_inventory_subtree` /
   `sema.unsupported_binding_subtree` 的适用范围移除 match；route-not-ready 归属
   `sema.compile_check` 的说明补 match 条目。
3. `frontend_resolution_pipeline_implementation.md`、`frontend_compile_check_analyzer_implementation.md`、
   `frontend_variable_analyzer_implementation.md`、`frontend_top_binding_analyzer_implementation.md`、
   `frontend_visible_value_resolver_implementation.md`、`frontend_type_check_analyzer_implementation.md`、
   `frontend_loop_control_flow_analyzer_implementation.md`、`frontend_chain_binding_expr_type_implementation.md`、
   `frontend_local_type_stabilization_implementation.md`、`scope_analyzer_implementation.md`、
   `frontend_lambda_implementation.md`：各自的 match 非目标/封口条目更新。
4. `frontend_lowering_plan.md`（backlog 移除 match）、
   `frontend_lowering_cfg_pass_implementation.md`（新增 `FrontendMatchRegion` 合同）。
5. `frontend_container_literal_implementation.md` §2.6：补「match pattern 是 `openEnded` 唯一
   合法消费上下文」交叉引用。
6. `frontend_global_constant_implementation.md`：常量 pattern 引用全局常量的互通说明。
7. `doc/test_error/test_suite_engine_integration_known_limits.md` §1、`doc/benchmark.md` 边界说明、
   `doc/test_suite.md`：`match` 从已知限制移除并登记 `control_flow/match_*` fixture 家族。
8. 若 Step 4 启用路线 B：`gdcc_lir_intrinsic.md` + `gdcc_runtime_lib.md` 冻结新 helper。
9. `doc/analysis/*` 两份历史报告保持快照不改（带校对日期），不反向更新。

---

## 9. 风险与开放问题

- **R1 `..` 位置/唯一性无法从 AST 校验**：gdparser 0.5.3 只暴露 `openEnded` 布尔。
  Step 1 parse 实证（`FrontendMatchParseBehaviorTest.trailingRestParsesOpenEndedWhileLeadingRestFailsAtParse`）：
  grammar 仅接受尾位 `..`——`[1, ..]` 映射 `openEnded=true`，`[.., 1]` 直接产生 parse 错误
  （CST structural ERROR）而非 openEnded=true 歧义形态，字典同；与 Godot「`..` 必须是最后
  元素」的拒绝结果一致（Godot 发语义错误，gdparser 发 parse 错误）。残留偏差仅为位置信息
  不暴露（AST 无法区分 `..` 出现的精确位置）。决策：接受为对 Godot 的已知偏差并在测试中
  锚定；若后续 gdparser 暴露位置信息再补 type-check 校验。不为此升级/修改 parser 库。
- **R2 解构能力路线**（§5.10 路线 A vs B）：默认路线 A；若实施中证伪（typed boundary /
  dynamic dispatch 不满足），升级路线 B 属于「新增 intrinsic + C helper」的面扩展，
  需先冻结 `gdcc_lir_intrinsic.md` 并知会维护者。
- **R3 类常量 / 类枚举成员 pattern**（如 `ItemType.WEAPON`，`ItemType` 为类内枚举）依赖
  class constant 支持（`frontend_rules.md` L72 整体延后）：本计划不毕业它们；pattern 中此类
  chain 走普通解析，失败时由既有 upstream 诊断兜底；`Variant.Type.TYPE_NIL` 等限定式全局枚举
  chain 已毕业（`load_static` 路径）不受影响。
- **R4 `_` 的上下文判别**：`_` 只在 pattern 递归上下文内认作 wildcard；pattern 上下文之外的
  `_` 保持普通标识符语义。若用户在 body 内写 `_`，走普通解析（Godot 中 `_` 可作普通变量名，
  本计划不扩展其特殊语义）。
- **R5 诊断 owner 单一化**：match 毕业后 `sema.unsupported_variable_inventory_subtree` 与
  `sema.unsupported_binding_subtree` 不再覆盖 match；pattern 校验统一 `sema.type_check`，
  绑定冲突统一 `sema.variable_binding`，route-not-ready 统一 `sema.compile_check`。
  不得在多个 owner 间重复发同一根源的诊断。
- **R6 lambda capture 反转面**：Step 2 反转两个 lambda/match 锚点测试的语义方向，
  实施时需成对修改（§7 清单），避免只改一侧导致 suite resolver 对 match 内 lambda
  重复发布或丢失 capture 冻结类型。
- **R7 静态折叠的不可达 body**：§5.9-1 的常量 false 折叠会产生「建图但不可达」的 section body；
  风险点在 CFG 发布不变量（无入边 sequence 的 value 引用、region 锚点一致性），而非
  `ControlFlowIntegrityValidator`（其只校验 terminator/successor，不拒绝无入边块）。
  实施 Step 3 时核对 CFG 不变量。可选优化**仅限 LITERAL / 常量子模式**：其测试片段已是
  无副作用的常量 false 时，可跳过 **body** 建图并把未命中链短路到下一 section。
  运行时子模式即使测试结果为常量 false，也必须保留测试段的 `buildValue`（§5.9-1），
  不得整段跳过。
- **R8 字典仅键省略值形态不可表示（Step 1 parse 实证已确认）**：gdparser 0.5.3 的
  `DictEntry.value` 非 `@Nullable`，mapper 对 entry 强制要求 value 字段。实证
  （`FrontendMatchParseBehaviorTest.keyOnlyDictionaryEntryIsNotRepresentable`）：
  Godot 的 `{"a", "b"}` 仅键形态被 grammar 接受为两个 `_primary_expression` 字典子节点，
  但 mapper 丢弃键、发两条 `parse.lowering` WARNING 并映射为零 entry 字典——键存在语义
  无法表示。处置：该形态已从支持面删除（用户改写 `{"k": _}` 等价），记录为与 R1 同级的
  parser 偏差；**不得**为此修改/升级 gdparser（架构级，非目标）。
- **R9 任意表达式 pattern 是 gdcc 对 Godot 的有意超集（v7 决策）**：Godot analyzer 对非
  常量、非「裸标识符/标识符根 attribute 链」形状的 pattern 报错
  （§1.2-3）；gdcc 不设形状白名单，任意可求值表达式均为合法 pattern，非常量走运行时
  子模式（§5.9）。性质评估：
  - **方向单向**：Godot 合法 ⇒ gdcc 合法且可观察行为一致——常量形态两侧等价（Godot 可
    折叠而 gdcc 未折叠的纯表达式走运行时子模式，仅求值时机从编译期变为运行时）；非常量
    identifier/attribute 形态（Godot 合法，可含 getter 副作用）两侧都在运行时求值，gdcc
    不得静态跳过求值（§5.9-1 的运行时子模式矩阵）。逆不成立——使用 `f()` / `a+1` /
    `d["k"]` / `self.prop` 等超集形态的脚本在 Godot 编辑器会报 analyzer 错误，
    **不可移植**；
  - 与 R1/R8 性质不同：那不是表达能力缺口，而是语义面主动放宽；不新增诊断（既无 error
    也无 warning），如需 portability lint（标记 Godot 不可移植 pattern）另行立项；
  - 字典 key 不在超集内：保持 Godot 常量限定（§5.6-4），因为 key 语义是 lookup 键值
    而非值比较，且 Godot 对 key 无形状豁免；
  - 求值时机合同已在 §5.9-2 冻结（逐 pattern 惰性、短路保护、subject 单次求值），
    副作用 pattern 的行为因此是良定义的。

---

## 10. 毕业判定（对齐 `frontend_lowering_plan.md` §6 三同时）

`match` 从 compile-only gate 移除全部 blocker、宣布毕业，必须同时满足：

1. 首批四 route 的 lowering 已稳定产生产物（CFG region + LIR 序列经测试锁定）；
2. backend 已能消费该产物（e2e fixture 在 zig / Godot 环境编译运行通过）；
3. 文档与正反测试全部同步（§7、§8 清单逐项关闭）。

ARRAY / DICTIONARY route 毕业（Step 4）单独走同一判定；未毕业期间保持 route-not-ready
fail-closed，不得以任何形式漏进 lowering。
