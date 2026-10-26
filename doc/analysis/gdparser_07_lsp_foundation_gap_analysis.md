# gdparser 0.7.0 纯 Java parser 对 frontend_lsp_foundation 缺口补齐能力分析

> 状态：定稿（v3；review-expert-a / review-expert-c 三轮复核后 ACCEPT）。
> 受测版本：`../gdparser` HEAD `12c22f8`（"preserve damaged syntax with linked recovery diagnostics"）。
> **注意：远端已发布标签 `0.7.0` 指向 `d2c3ef3`，不包含 `12c22f8` 的损伤保留改造**；本文所有 NEW 结论仅适用于包含 `12c22f8` 的构建。
> 实证归档：`tmp/probes/gdparser07-refresh/probe.log`（22 例 NEW=pure-Java vs OLD=tree-sitter 双轨对比）、`tmp/probes/gdparser07-refresh/anchor-probe.log`（文档精确锚点补探）、`tmp/probes/gdparser07-refresh/completion-probe.log`（completion 分类实测存档）。
> OLD 列代表性：`git diff 0.6.0..HEAD` 对 `infra/`、`frontend/lowering/`、`frontend/cst/` 全部文件仅触及 `AstDiagnostic.java`（新增可空 `code`/`recovery` 字段，兼容构造器保留）与新增 `RecoveryLink.java`，故 OLD 列行为 ≡ gdcc 当前依赖的 0.6.0。
> 过期产物警示：`tmp/probes/gdparser07-research/` 基于旧编译产物，其 NEW 列形态与当前不一致，仅作历史参考。

## 1. 问题 framing

`doc/module_impl/frontend/frontend_lsp_foundation_implementation.md`（下称"LSP 文档"）已实施完成，其 §3 将 parser 契约钉在 **gdparser 0.6.0（tree-sitter 轨道）**。gdcc 当前依赖 `com.github.SuperIceCN:gdparser:0.6.0`（`build.gradle.kts:60`），解析入口为 `GdScriptParserService`（`frontend/parse/GdScriptParserService.java:44-45`：`GdParserFacade.parseCstRoot` + `CstToAstMapper.map`），completion 分类走 `infra.treesitter.GdParserFacade.parseCompletionContext`（`api/analysis/FrontendSnapshotCompletionService.java:88`）。

gdparser 新版新增了完整的**纯 Java parser**（`frontend/gdscript`，入口 `GdScriptParserFacade.parse/parseStrict/tokenize`），与 tree-sitter 轨道双轨共存（0.7.0 未废弃旧轨）。本分析回答：纯 Java parser 能否更好地补齐 LSP 文档中目前与 parser 相关的实现缺口？

## 2. 纯 Java parser 能力基线（实证 + 文档）

来源：`../gdparser/doc/godot-java-parser-implementation.md`（事实源）、`pure-java-recovery-and-completion-plan.md`、探针实测。

- API：`parse(String) -> AstMappingResult`（与 tree-sitter 轨道 `CstToAstMapper.map` **同一返回类型**），tolerant 不抛异常；`parseStrict`；`tokenize`（token 级新能力）。`GdScriptParserFacade` 无状态、每次调用新建 parser/tokenizer，满足 gdcc 共享单实例的线程安全要求（LSP 文档 §3 契约项 5）。
- 损伤表示：`ErrorExpression`（`CstIssueKind` ERROR/MISSING + slot 名 nodeType + 零宽/片段 range）作为**表达式槽位标记**保留；`MissingAttributeStep` 保留部分成员链。诊断携带稳定 code（`parse.missing_closer.*`、`parse.missing_expression.*`、`parse.attribute.missing_name`、`parse.super.*`、`parse.signature.*`；**覆盖不全**，实测 `Cannot use "break" outside of a loop.`、`Constructor cannot return a value.`、`Expected bind name after "var".` 等 code=null）与可选 `RecoveryLink`（缺失闭包诊断 → 同代属主节点身份，孤儿链返回前按身份清扫；不参与序列化）。
- **不再产生** `ErrorStatement` / `UnknownStatement` / `UnknownExpression`（`frontend/gdscript` 中无任何构造调用）；损伤语句原则上以槽位标记保留宿主结构，例外见 §4.3 R8/R9。
- 返回值槽特殊：`returnSlotValue`（`GdScriptParser.java:1337-1343`）对 `Missing` 返回 `null`、对 `Damaged` 保留节点 → `return 1+` 保留带标记的 BinaryExpression，`return = 3` 则是 `value=null` 无标记。
- range 不变式 I3（父子包络）有专门测试锚定；UTF-8 字节偏移为唯一事实源（`SourceTextIndex`），CJK/CRLF/emoji 实测与 tree-sitter 轨道逐字节一致（probe `unicode_crlf`）。诊断 range 不一定等于标记节点 range（如 `if :` 诊断 11..13 vs 标记 13..13）。
- 嵌套预算 `MAX_NESTING_DEPTH = 256`（对 Godot 无限制的有意偏离，防递归下降 parser 的 JVM 栈溢出；多行分组续行触发时已知兄弟作用域误归属限制已登记）。suite 熔断为单 suite 累计错误超过 100 后的下一次（`GdScriptParser.java:1282`）。
- 移植了 vendor parse-time 检查：`break`/`continue` 出循环、构造器带值返回（`GdScriptParser.java:1410-1451`）等，报错但保留正常 AST 节点。
- AST 不可变（record + `List.copyOf`），满足 §3 契约项 6。
- completion 上下文分类**仍只在 tree-sitter 轨道**（B10 未实施，`frontend/gdscript` 无 `CompletionContext*`；`parseCompletionContext` 两版逐字节相同）。

## 3. 对 LSP 文档缺口的逐项评估

### 3.1 确认可补齐 / 改善的缺口（实证）

**[精确]** = 文档原例逐字探针；**[代表]** = 同机制代表例探针（含仅空白差异的非逐字输入）；**[推导]** = 源码核对。

| LSP 文档位置 | 0.6.0 缺口描述 | 纯 Java（`12c22f8`）实测 | 证据 |
|---|---|---|---|
| §3 lines 443-444 | 函数尾部分链（`self.` 后随下一 `func`）receiver 事实静默丢失（`ErrorStatement` 吞并 `self.\nfunc g():`），completion 返回空 | `AttributeExpression(self, [MissingAttributeStep@16..16])` 保留 receiver；下一 `func g` 为健康兄弟。completion 分类（ts 轨）报 `MEMBER_ACCESS receiver=11..15`，与快照 AST `SelfExpression@endByte=15` 精确对齐 → **成员补全恢复可用**（replaceable=17..21 覆盖下一 `func` 关键字，由 gdcc 既有"必须含光标"钳制处理） | **[代表]** probe `self_before_func`；`completion-probe.log` |
| §3 lines 445-446 | 行尾 `call().` 映射 `ErrorStatement`，分类可能降级 `UNKNOWN`/`IDENTIFIER` | 纯 Java AST 保留 `AttributeExpression(v, [AttributeCallStep@41..53, MissingAttributeStep@54..54])` + 诊断；ts 分类实测 `MEMBER_ACCESS receiver=39..53`（未降级），端点对齐 `AttributeCallStep@endByte=53`（gdcc `FrontendSnapshotCompletionService.java:263-273` 按 endByte 回扫匹配）→ **成员补全恢复可用** | **[代表]** probe `normalized_before_func`；`completion-probe.log` |
| §3 lines 441-442 关联形态 | `v.normalized().` 后随同函数下一语句：0.6.0 把 `pass` **吞为 `AttributePropertyStep` 且无任何诊断**（静默错误链） | 链保留 + `MissingAttributeStep` + 明确诊断，`pass` 正确成为兄弟语句 | **[代表]** probe `normalized_before_stmt` |
| §2.2.7 lines 208-209 | `func f(x = 1 +)` 产生幻影 Parameter（name `"+"`），参数默认孤岛跳过消费纯防御、无真实源码可锚 | 无幻影参数；`Parameter(x, default=BinaryExpression(1, ErrorExpression(MISSING binary.right)))` 带标记保留 → 标注器既有参数默认孤岛分支（`FrontendErrorSubtreeAnnotator.java:92-99`）首次被真实源码激活 | **[代表]** probe `bad_default`（探针输入 `func f(x=1+)`，与原例仅空白差异） |
| §2.2.7 line 206 | `match :` → `MatchStatement` 整棵丢弃（仅剩 `pass`） | `MatchStatement(value=ErrorExpression(MISSING match.value), sections=[])` 保留并被跳过根标记；副作用：多出一条 `Missing unindent...` 诊断 | **[精确]** probe `match_missing_expr` |
| §2.2.7 line 206 | `if :` / `for i in :` → 宿主保留 + MISSING 槽位 | 形态保持，nodeType 词汇由 `"identifier"` 变为 slot 名（`if.condition`/`for.iterable`），语义等价 | **[代表]** probe `if_missing_cond`（探针输入 `if:`）/`for_missing_iter`（逐字） |
| §2.2.7 line 210-211 | `var x = self.` 函数尾 → 部分链 + missing 诊断（已满足） | 形态保持：`VariableDeclaration(x, value=AttributeExpression(self, [MissingAttributeStep]))` + `parse.attribute.missing_name` 诊断 | **[精确]** anchor-probe `doc_var_self_func_tail` |
| （未登记缺陷） | tree-sitter 轨道优先级误解析：`2*3**2` → `(2*3)**2`、`-2**2` → `(-2)**2`、`1+2 as int` → `1+(2 as int)`（均与 Godot 相反） | 纯 Java 按 Godot 优先级：`2*(3**2)`、`-(2**2)`、`(1+2) as int`。**这同时是编译正确性修复**（gdcc 当前经 0.6.0 继承错误 AST，docs 中未见登记）；但注意 §4.3 R4 的冻结测试 | **[精确]** probe `precedence` |
| §3 契约项 1 | `receiver.` → `AttributeExpression` + `MissingAttributeStep`；`receiver.par` 无标记无诊断 | 函数体内各形态实测满足；**例外：类体悬空 `self.` 被整体丢弃**（见 §4.3 R8） | **[代表]** probe `dangling_make_eof` 等 |
| §3 契约项 2 | 错误节点携带 range / kind / 原文片段 | 表达式槽位标记保留（`ternary.condition`/`binary.right`/`call.argument`/`annotation.argument`/`if.condition`/`for.iterable`/`match.value` 已实测；`subscript.index` 见 `GdScriptParser.java:3559-3563` 为 **[推导]**）；**替代合同**：不产 `ErrorStatement`，部分损伤仅诊断+`RecoveryLink`（§4.3 R9） | **[代表]** probe `bad_annotation`、`call_arg_*` 等 |
| §3 契约项 3 | MISSING 消息携带期望 token/符号名 | 实测消息均含期望内容（`Expected identifier after "."...`、`Expected closing ")"...`） | **[代表]** 各 probe 诊断 |

### 3.2 不再成立的旧基线（需更正 LSP 文档，非迁移收益）

- §2.2.7 lines 212-213 称"0.6.0 无法从真实源码产生仅 WARNING 无 ERROR 的 parse 诊断"。实测证伪：key-only 字典模式 `{"name"}:` 在 0.6.0 即产出 WARNING-only（`Unsupported dictionary child node: string`，`hasError=false`；mapper 自 0.6.0 未变）。纯 Java 轨产出等价 WARNING（`Key-only dictionary pattern drops the key...`）。**结论：该形态两轨均可锚定，LSP 文档旧基线描述需更正；不计为迁移收益。**（probe `keyonly_dict_match`）
- §2.2.7 line 206 将 `match :` 锚为"值位置错误表达式"，但 0.6.0 实测为整棵丢弃；旧锚点描述本身与 0.6.0 行为不符，迁移时应一并更正。

### 3.3 不能补齐的缺口（保持现状）

| LSP 文档位置 | 缺口 | 原因 |
|---|---|---|
| §3 lines 436-437；§"明确非目标" line 31 | 增量解析 / 增量语义 | 0.7.0 计划文档明确排除；纯 Java parser 全量重 parse |
| §3 契约项 4 的实现层 | completion 分类仍依赖 tree-sitter CST（B10 未实施） | `frontend/gdscript` 无 `parseCompletionContext`；gdcc 升级后**仍需保留 tree-sitter native 依赖**用于 completion 分类，形成"快照 AST 纯 Java + 分类 CST tree-sitter"双 parser 运行形态（契约 §4 item 2 本就不要求两者同代，兼容；`parseCompletionContext` 两版逐字节相同，分类行为不变） |
| §"明确非目标" lines 26-35 | LSP 协议服务器、RPC 查询方法、rename/hover 等 | 与 parser 无关 |
| §2.4 lines 339-340, 347-348 | engine/builtin 枚举组、元数据文档文本 | frontend 元数据范畴，与 parser 无关 |
| §2.4 lines 354-356 | UTF-16 列转换 | LSP 适配层职责（0.7.0 `tokenize` 可为未来语义高亮提供 token 级基础，属新增能力而非缺口补齐） |

## 4. 形态差异与 gdcc 适配面

### 4.1 迁移改动面（实测）

- `GdScriptParserService`：44-45 行换成 `new GdScriptParserFacade().parse(source)`（返回值同为 `AstMappingResult`，诊断映射循环、severity 映射、`parseFailed` 路径不变）。构造函数/字段同步收缩。facade 无状态，可共享单实例。
- `build.gradle.kts:60` 版本升级（前置见 §5 阻塞项 B0）。
- `module-info.java` / tree-sitter 模块配置：保留（completion 分类仍用 `infra.treesitter`）。
- `AstDiagnostic` 新增 `code`/`recovery` 可空字段：当前映射（`GdScriptParserService.java:46-56`）只读 severity/message/range，**API 接线兼容**；但若未来用 `RecoveryLink` 做受损 facet 定位，必须用同次 parse 返回的同代节点身份（`RecoveryLink.java` 身份语义 + gdcc `FrontendAstSideTable` 身份表合同），禁止跨代/serde 后查表。
- gdcc 对 `nodeType()` 仅用于消息拼接（`FrontendExpressionSemanticSupport.java:1073-1077`、`FrontendChainReductionHelper.java:2337`），slot 词汇变化不影响逻辑。
- `ErrorStatement`/`UnknownStatement` 分支（`FrontendErrorSubtreeAnnotator.java:72-75`）与 `UnknownExpression` 分支（`:76-79` 的一部分）在纯 Java 轨道成为死路径（`ErrorExpression` 分支仍活跃）；双轨期保留。
- 部分链契约（§2.2.1）：`FrontendBodyOwnerProcedures.java:2490`、`FrontendChainReductionHelper.java:583,593,2360` 对 `MissingAttributeStep` 的结构判断与诊断消息无关，实测形态一致 → 无需改动。
- **仅升级版本、不切换 parser**（继续走 0.7.0 的 tree-sitter 轨）是可行的中间态：`GdParserFacade.parseCstRoot/parseCompletionContext`、`CstToAstMapper.map` 及 module exports 均在，行为 ≡ 0.6.0。可作分阶段升级/回退路线。

### 4.2 会破坏的现有测试与合同（迁移阻塞工作项）

以下均非架构变更，但属必须显式处理的阻塞工作项：

- **B1（测试锁死旧形态）** `FrontendErrorRecoveryIntegrationTest`：
  - `:123-138 brokenReturnStatementSkipsOnlyItself`（`return = 3`）：NEW `value=null` 无标记 → return 句不再进 skipped root；
  - `:141-157 errorStatementInFunctionBodySkipsOnlyItself`（`var x = true if  else false`）：NEW 无 `ErrorStatement`，改为 `VariableDeclaration`（内含 `ErrorExpression(ternary.condition)`）被跳过；
  - `:186-206 classLevelErrorStatementSkipsOnlyItself`（`var hp = (1`）：NEW 无 `ErrorStatement`，且 `hp` **会被注册为属性**（见 R9），当前断言"damaged member must not be registered"将失败。
  - 同文件的 `brokenLambdaBodyStatementKeepsLambdaAndOuterStatementAlive`（`if :`）新旧形态等价，应仍通过。
- **B2（冻结的非 Godot 优先级被翻转）** `FrontendCastParseBehaviorTest.java:28-44 unparenthesizedPlusBindsTighterThanCastInGdparser` 显式冻结 `a + b as float` = `a + (b as float)`（注释承认 Godot 为 `(a + b) as float` 且要求"Do not rewrite the dependency AST"）。纯 Java 轨产出 Godot 正确形态 → 该测试必须重写为新预期；**这是编译行为变化，需在提交说明中显式声明**（同 R4）。
- **B3（诊断文案锚定）** 5 测试文件 6 处：`ApiCompileDiagnosticsTest.java:44`、`FrontendContainerLiteralParseBehaviorTest.java:255`、`FrontendCastParseBehaviorTest.java:128`、`GdScriptParserServiceDiagnosticManagerTest.java:58`、`FrontendErrorRecoveryIntegrationTest.java:246,251`；外加 frontend 文档 3 处。`CST structural issue: ERROR`/`Missing identifier` 等文案全部变为具体 slot 消息。
- **B4（诊断属主重叠，见 R10）** 迁移后 `break`/`continue` 出循环、构造器带值返回将同时收到 parser ERROR 与 gdcc sema ERROR，违反单一属主护轨（LSP 文档 §4 item 8、frontend_rules.md lines 17-18），必须先裁定属主再迁移。

### 4.3 风险登记册

- **R1（中）**：`return = 3` 类（return 值槽 `Missing`，后续垃圾 token 另行报错）→ `ReturnStatement(value=null)` 无标记 → 不跳过。对**显式非 void 非 Variant 返回类型**函数，`FrontendTypeCheckAnalyzer.visitReturnStatement`（`FrontendTypeCheckAnalyzer.java:306-314`）触发 `sema.type_check` 裸 return 二次诊断（违反单一属主）；对无标注函数（返回槽 Variant）则无二次诊断，但 `= 3` 垃圾被静默抹除且语句照常分析。对照：`return 1+` 保留 `BinaryExpression(1, ErrorExpression(binary.right))` 标记 → 正常跳过，**不受影响**。修复方向（二选一）：(a) gdparser 为 return 值槽 `Missing` 形态补 `ErrorExpression` 标记；(b) gdcc 标注器对 range 与 parse ERROR 诊断重叠的语句补标。倾向 (a)。
- **R2（低-中）**：match 模式绑定缺名（`{"a": var }`）→ DictEntry 整体丢弃（`entries=[]`，无标记、无空名 `PatternBindingExpression`）→ `MatchStatement` 不跳过而按空字典模式分析；`FrontendErrorSubtreeAnnotator.java:84-87` 空名绑定分支对纯 Java 轨道失效。编译门禁（parse ERROR 即拒编译）使差异不进 codegen；parser 诊断保持单一。修复方向同 R1(a)（模式槽位补标记）。
- **R3（中，流程）**：completion 分类滞留 tree-sitter（B10 未实施）→ native 依赖与双 parser 运行成本保留；ts 分类层固有怪癖（replaceableRange 覆盖下一语句首 token）继续存在但 gdcc 已有钳制（`FrontendSnapshotCompletionService.java:174-183`）。不阻塞迁移，但"去 native 化"收益暂不可兑现。**性能证据缺口**：gdcc 每次分析对全部文件重 parse（`AnalysisRunner.java:138-147`）+ completion 额外跑一次 ts 分类；gdparser 仅有 ~41.6KB 小 fixture 的预热量级测量且无回归阈值，未与 tree-sitter 同机对照。迁移验收应补大文件/错误密集输入的对照测量。
- **R4（低，行为变化）**：优先级修复（`2*3**2`、`-x**2`、`1+2 as int`、以及 `a + b as float`）改变编译结果；虽是 Godot 对齐修复，必须重写 B2 冻结测试并在提交说明声明。
- **R5（低）**：嵌套预算 256 为对 Godot 的有意偏离；已知多行分组续行触发时兄弟作用域误归属（gdparser 已登记）。
- **R6（低）**：诊断 code 覆盖不全（`break`/`continue`/构造器返回值/match 绑定缺名等 code=null）。若采纳"按 code 过滤 parser 诊断"解决 R10，需先在 gdparser 侧补齐这些 code。
- **R7（低）**：`super.` 损伤形态 NEW 为 `AttributeExpression(super, [MissingAttributeStep])`（receiver 保持可见，利于补全），未与 0.6.0 对比锚定，迁移期需对 `frontend_super_call_implementation.md` 相关用例回归。
- **R8（低-中）**：类体悬空 `self.`（`dangling_self_eof`）NEW 整体丢弃（`Unexpected "self" in class body.`，statements 为空），OLD 保留 `AttributeExpression + MissingAttributeStep`。属 AST 保留合同差异：当前 gdcc 不对类体独立表达式语句发布 receiver 绑定/类型事实（`FrontendInterfacePhase` 仅盘点 callable 与属性初始化器），OLD 下此处成员候选本就为空，故不构成已实证的补全回退；但诊断文案变化（`Missing identifier` → `Unexpected "self"...`）与"损伤节点不再存在"会影响 `nodeAt` 等查询在该位置的命中。是否接受需决策（或在 gdparser 侧对类体表达式损伤也保留部分链）。
- **R9（中）**：missing-closer 形态无错误节点、仅诊断+`RecoveryLink`（如 `var hp = (1` → `VariableDeclaration(hp, value=LiteralExpression(1))` + `parse.missing_closer.grouping` 零宽诊断）→ 标注器不跳过 → 受损成员**照常注册/分析**。颠覆 `classLevelErrorStatementSkipsOnlyItself` 的"受损成员不注册"合同与 §2.2 属性初始化器跳过合同。受损子树本身完整时结果无害（甚至对补全更友好），但属合同变化，需显式决策：接受并改文档/测试，或 gdparser 对 missing-closer 宿主补标记，或 gdcc 消费 `RecoveryLink` 做门禁（同代身份约束）。
- **R10（中）**：parser/sema 诊断属主重叠。纯 Java 移植了 vendor parse-time 检查（`GdScriptParser.java:1410-1451`）：`func f(): break`/`continue` → parser ERROR + 保留正常节点 → gdcc `FrontendLoopControlFlowAnalyzer` 再报 `sema.loop_control_flow`；`func _init(): return 1` → parser ERROR + 保留带值 return → gdcc `FrontendTypeCheckAnalyzer` 再报 `sema.type_check`（anchor-probe `break_outside_loop`/`init_valued_return` 实测）。0.6.0 无此重叠。必须逐类裁定属主（建议：gdparser 补齐 code → gdcc 按 code 从 `parse.lowering` 过滤，保留 sema 为唯一属主；或反向）。
- **R11（低-中，合法语法行为变化）**：inner class 体内 `extends` 被提升进 `ClassDeclaration.extendsTarget`（`GdScriptParser.java:1202-1221`，gdparser 测试已登记该轨差异）；OLD 在体内留 `ExtendsStatement`。gdcc 对 inner header 直接读 `extendsTarget()`（`FrontendClassSkeletonBuilder.java:1427`），而 `ExtendsStatement` 仅顶层路径消费（`:247`）→ 迁移后 inner class 继承解析行为变化（大概率是修复），需补 inner-class superclass / 继承成员 / `super` 的 gdcc 侧回归锚点。

## 5. 结论

1. **能更好补齐（收窄后）**：函数尾/行尾部分链的 receiver 事实保留（§3 lines 443-446 实质恢复成员补全）、`match :` 宿主保留、幻影参数消除且参数默认孤岛防御路径首次可被真实源码激活、以及未登记的优先级误解析修复（编译正确性收益）。
2. **旧基线更正（非收益）**：WARNING-only 形态 0.6.0 已可产出（§2.2.7 lines 212-213 描述过时）；`match :` 的 0.6.0 旧锚点描述与实际行为不符。
3. **不能补齐**：增量解析、completion 分类去 tree-sitter 化（B10）、LSP 协议层非目标项。
4. **迁移阻塞项（非架构变更）**：
   - **B0（前置）**：远端标签 `0.7.0` = `d2c3ef3`，**不包含受测的 `12c22f8`**；JitPack `0.7.0` 构建产物不可用。必须先发布包含 `12c22f8` 的新版本（如 0.7.1）并验证 JitPack 产物。
   - **B1/B2/B3**：按 §4.2 重写形态锁定测试、翻转冻结优先级测试（显式行为变更声明）、更新 6 处诊断文案锚点 + 3 处文档。
   - **B4**：裁定 R10 诊断属主后方可迁移（否则用户看到成对重复诊断）。
5. **架构判断**：双 parser 共存符合既有契约（§4 item 2），无架构级变更需求；但"只换解析入口+改文案"不成立——R1/R8/R9/R10/R11 涉及恢复合同与诊断属主的显式决策，建议在迁移方案中先解决 R10 与 R9 的属主/门禁决策，R1 倾向 gdparser 侧补 return 值槽标记。
