# 字符串字面量归一化修复方案

> 本文档记录 `doc/test_error/test_suite_engine_integration_known_limits.md` 中问题 6 的修复方案。  
> 目标不是再描述“字符串 surface 目前不稳定”，而是把当前根因、跨模块合同、实施顺序和验收细则固定下来，供后续实现直接执行。

## 文档状态

- 状态：Implemented
- 更新时间：2026-04-19
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/lir/insn/**`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/NewDataInsnGen.java`
  - `src/test/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/test/java/dev/superice/gdcc/backend/c/gen/**`
  - `src/test/java/dev/superice/gdcc/test_suite/**`
  - `src/test/test_suite/unit_test/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/test_suite.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`

## 1. 问题概述

`test_suite` 问题 6 记录的表象有两类：

- `substr(...)` 结果看起来像发生了索引偏移
- 编译脚本内部字符串拼接结果会带出额外引号片段

当前调研结果表明，这两类表象高度可能来自同一个根因：

- frontend body lowering 把普通字符串字面量的源码 lexeme 直接下沉进 `LiteralStringInsn`
- backend `NewDataInsnGen` 又把这份 lexeme 当成已经归一化完成的 payload 去构造 Godot `String`
- 运行时真实字符串内容因此被污染，后续 `substr`、拼接、比较、分支分类都会一起失真

这不是 `String.substr(...)` builtin route 本身的独立问题，也不是字符串 `+` 运算 surface 本身的独立问题。当前 focused tests 之所以仍能通过，主要是因为它们大多绕开了“编译脚本内部自带字符串字面量”这条坏路径。

## 2. 当前已确认事实

### 2.1 frontend AST 的 `sourceText()` 保留源码字面文本

- `LiteralExpression.sourceText()` 当前语义是“源码片段”，不是“归一化 payload”
- 对字符串字面量，这意味着它保留外围引号和转义写法，例如 `"fallback"`

这条语义本身没有问题，后续也不应修改。它服务于 parser / analyzer / debug / diagnostic，而不是直接充当 runtime payload。

### 2.2 frontend body lowering 当前直接下沉 raw lexeme

- `FrontendOpaqueExprInsnLoweringProcessors` 对 `string` 和 `string_name` 当前直接使用 `node.sourceText()`
- 这使 `LiteralStringInsn.value()` 与 `LiteralStringNameInsn.value()` 的语义取决于 producer，而不是由 IR 类型自己保证

### 2.3 文本 LIR 解析器产出的却是已归一化 payload

- `SimpleLirBlockInsnParser` 读取 quoted token 时会去掉外围引号并解转义
- 对同一个 `LiteralStringInsn`，文本 LIR parser 与 frontend lowering 当前给出了两套不兼容语义

### 2.4 backend `NewDataInsnGen` 默认假设 `LiteralStringInsn.value()` 已归一化

- 普通 `String` 路径直接把 `value` 送进 `utf8Literal(...)`
- `utf8Literal(...)` 只负责 C 字面量转义，不负责 GDScript lexeme 解码
- `StringName` 路径目前保留了一个只针对 `&"..."` 的兼容分支，这进一步说明 `LiteralStringNameInsn` 也存在 producer 语义不一致

### 2.5 当前 focused regression 没有覆盖坏 surface

- `CNewDataInsnGenTest` 手工构造的 `LiteralStringInsn("hello")` 已经是归一化 payload
- `CallMethodInsnGenEngineTest` 的 `substr` 正向样本把 `"abcdef"` 写在 validation script 里，不经过 GDCC 编译脚本内部 literal lowering
- `COperatorInsnGenEngineTest` 的字符串拼接样本也是把输入字符串从 Godot 侧传进 compiled target

因此当前绿测只能说明以下表面是好的：

- backend 能正确消费“已经归一化”的 `LiteralStringInsn`
- builtin `substr` route 是好的
- string `+` route 是好的

它们并不能证明“frontend 直接 lower 出来的字符串字面量”是好的。

## 3. 根因链路

最小问题链路如下：

1. 源码中出现 `"abcdef"`
2. frontend AST 正确保留 `sourceText() == "\"abcdef\""`
3. frontend body lowering 直接发出 `LiteralStringInsn(..., "\"abcdef\"")`
4. backend `NewDataInsnGen` 将这份值当作 runtime payload，生成 `u8"\"abcdef\""`
5. Godot 运行时拿到的真实字符串变成 `"abcdef"`，而不是 `abcdef`
6. 之后：
   - `substr(2, 3)` 得到的是被污染字符串上的切片，因此看起来像索引偏移
   - 字符串拼接会把多余引号原样拼进去
   - `==` / `!=` 和字符串分类分支也会被一起污染

对应的最小例子如下：

```text
预期字符串内容：a b c d e f
预期索引位置：  0 1 2 3 4 5

当前错误内容： " a b c d e f "
当前错误索引：  0 1 2 3 4 5 6 7
```

因此 `substr(2, 3)` 在坏实现下得到的不是 `cde`，而更接近 `bcd`。这会从外部表现成“`substr` 不是 0-based”或“索引偏了一位”，但根因其实是字符串内容前面多了一个不应存在的引号字符。

## 4. 修复目标

本次修复必须同时满足以下目标：

- 冻结 `LiteralStringInsn` / `LiteralStringNameInsn` 的单一、不漂移的 IR 语义
- 把 GDScript string lexeme 的解码职责收敛到单点共享 helper
- 在 frontend lowering 边界完成 lexeme -> payload 归一化
- 让 backend 只消费归一化后的 LIR，而不是长期保留“顺手帮忙补救”的静默兼容
- 用 unit + compile-run 两层回归把“编译脚本内部字符串字面量 surface”单独锚住

## 5. 明确非目标

本次修复不做以下事情：

- 不修改 `LiteralExpression.sourceText()` 的现有语义
- 不把 AST 层也改成发布归一化字符串字段
- 不改造 `substr` builtin route、string `+` evaluator 或 call resolver 的现有正确路径
- 不借机重做 `String` / `StringName` / `NodePath` 全部 literal family 的大范围建模
- 不继续把“问题 6 是字符串整体不稳定”作为长期表述；修复后应把问题精确收敛成已关闭的合同缺陷

## 6. 方案选择

### 6.1 不采纳方案：只在 backend 做兼容归一化

不采纳原因：

- 这会继续允许 `LiteralStringInsn` 同时承载 raw lexeme 和 normalized payload 两套语义
- 其他依赖 LIR 的组件仍然会面对同一个歧义
- backend 将不得不继续理解 frontend 源码字面语法，职责边界被污染
- `StringName` 当前已经有一个局部兼容分支，事实证明这种做法会让坏合同长期潜伏

### 6.2 采纳方案：在 frontend lowering 边界统一归一化，并让 backend 改为消费单一 IR 合同

采纳原因：

- `sourceText()` 保留源码语义，`Literal*Insn.value()` 保留 runtime payload 语义，两者职责清晰
- 文本 LIR parser、frontend lowering、手工构造测试都能统一到同一不变量
- backend 代码生成无需再知道 raw GDScript string lexeme 长什么样
- 一旦再有 producer 漏掉归一化，backend 可用显式 fail-fast 直接暴露合同回归

## 7. 实施步骤

### 步骤 1：抽取并冻结共享 string lexeme 解码 helper

- 状态：Implemented（2026-04-19）
- 当前落实：
  - `StringUtil.decodeGdStringLexeme(...)` 现已成为完整 GDScript string / `StringName` lexeme 的共享解码入口
  - `unescapeQuoted(...)` 继续只负责 inner content 解码，未被扩张成双语义入口
  - `StringUtilTest` 已覆盖普通字符串、`StringName`、转义、Unicode 与多类 malformed lexeme 的正反断言

#### 目标

把 GDScript 源码字符串字面量的“去外围语法 + 反转义”能力统一收敛到 `StringUtil`，避免 frontend / backend / test helper 各自写一份。

#### 建议改动

- 在 `dev.superice.gdcc.util.StringUtil` 中新增严格的源码 lexeme 解码 helper
- helper 需要覆盖至少两类输入：
  - 普通字符串：`"text"`
  - `StringName` 字面量：`&"text"`
- 建议 API 形式允许直接接收完整 lexeme，而不是要求调用点先手工 `substring(...)`
- 对 malformed lexeme 必须抛出明确异常，而不是静默返回原文

#### 约束

- `unescapeQuoted(...)` 继续承担“只解 inner content”的职责，不要把它变成隐式猜测输入形状的万能函数
- 新 helper 应该建立在 `unescapeQuoted(...)` 之上，而不是复制一套转义解码逻辑

#### 验收细则

- `StringUtilTest` 新增并通过以下断言：
  - `"hello"` -> `hello`
  - `"line\\nbreak"` -> `line\nbreak`
  - `"tab\\tquote\\\""` -> `tab\tquote"`
  - `&"Node_2D"` -> `Node_2D`
  - `"\u0041"` -> `A`
  - `"\U0001F600"` -> 对应 code point
  - 缺失尾引号、错误前缀、过短 lexeme 必须抛错

### 步骤 2：冻结 `LiteralStringInsn` / `LiteralStringNameInsn` 的 IR 不变量

- 状态：Implemented（2026-04-19）
- 当前落实：
  - `LiteralStringInsn` / `LiteralStringNameInsn` 已补充 record 级注释，明确 `value()` 只承载 normalized runtime payload
  - `SimpleLirBlockInsnParserTest` 已新增 string / `StringName` payload 归一化锚点，确认文本 LIR producer 继续发布单一合同
  - `FrontendWritableRouteSupportTest` 继续作为 body-lowering 内部 `StringName` producer 的现有锚点
- 后续衔接：
  - frontend 对 AST `sourceText()` 的 raw lexeme 归一化修复仍归属于步骤 3，本步骤不提前修改该 producer

#### 目标

明确这两个 IR 节点的 `value()` 语义只能是“归一化后的 runtime payload”，不能再是源码 lexeme。

#### 建议改动

- 在 `LiteralStringInsn` 与 `LiteralStringNameInsn` 上补充简洁注释，明确：
  - `value()` 不包含外围引号
  - `value()` 已完成转义解码
  - producer 不得传入 raw source lexeme
- 检查当前所有 producer：
  - 文本 LIR parser：已经满足，无需改语义
  - frontend lowering：后续在步骤 3 修复
  - `FrontendWritableRouteSupport` 等内部 helper：继续只发布归一化 payload
  - backend 内部生成的空字符串/空 `StringName`：继续保持归一化 payload

#### 验收细则

- 代码中不再有任何 producer 以“既可能传 lexeme，也可能传 payload”为默认约定
- 至少新增一组 frontend lowering 或 LIR contract 测试，明确断言 lowered `LiteralStringInsn.value()` / `LiteralStringNameInsn.value()` 已归一化

### 步骤 3：修复 frontend body lowering producer

- 状态：Implemented（2026-04-19）
- 当前落实：
  - `FrontendOpaqueExprInsnLoweringProcessors` 的 `string` / `string_name` 分支已改为通过 `StringUtil.decodeGdStringLexeme(...)` 发布 normalized runtime payload
  - `FrontendLoweringBodyInsnPassTest` 已新增 property-init `String` 与 executable-body `StringName` 两组回归，直接对照 AST `sourceText()` 与 lowered LIR `value()`
  - `FrontendLoweringBodyInsnPassTest` 也补充了 compile-gate bypass 场景下 malformed string lexeme 的 fail-fast 回归，确保坏 lexeme 会在 frontend lowering 边界暴露
  - `FrontendAnnotationParseBehaviorTest` 继续作为 parser-side raw lexeme 语义的回归锚点，证明本步骤没有反向篡改 AST surface

#### 目标

让 frontend 在发布 `LiteralStringInsn` / `LiteralStringNameInsn` 时就输出正确 payload。

#### 建议改动

- 修改 `FrontendOpaqueExprInsnLoweringProcessors`
- 对 `string` 分支：
  - 不再直接传 `node.sourceText()`
  - 改为先通过共享 helper 解码，再构造 `LiteralStringInsn`
- 对 `string_name` 分支：
  - 同样走共享 helper 解码，再构造 `LiteralStringNameInsn`
- 保持 AST 层 `sourceText()` 语义完全不变

#### 需要补的测试

- 优先在 `FrontendLoweringBodyInsnPassTest` 中增加针对真实 lowering 产物的断言
- 如果当前测试组织更适合 graph 阶段观察，也可在 `FrontendLoweringBuildCfgPassTest` 或辅助 test 中增加“最终 materialized LIR insn 内容”断言

#### 验收细则

- 对源码：
  - `"abcdef"` lowering 后 `LiteralStringInsn.value()` 为 `abcdef`
  - `&"Hero"` lowering 后 `LiteralStringNameInsn.value()` 为 `Hero`
  - `"line\\nbreak"` lowering 后 `value()` 内部真实包含换行
- `LiteralExpression.sourceText()` 的既有 analyzer / parser 测试不需要改语义预期

### 步骤 4：收紧 backend 消费合同，移除静默补救

- 状态：Implemented（2026-04-19）
- 当前落实：
  - `NewDataInsnGen` 已删除 `StringName` 路径对 raw `&"..."` lexeme 的静默解码补救，backend 现在只把 `LiteralStringNameInsn.value()` 当作 normalized runtime payload 消费
  - backend 对可明确识别的坏合同增加了 fail-fast：若 `LiteralStringNameInsn.value()` 仍长成 `&"..."`，则直接抛出 `InvalidInsnException`
  - 普通 `String` 路径继续保持 payload-only 消费，不再尝试根据前后引号猜测 producer 是否误传 raw lexeme
  - `CNewDataInsnGenTest` 已补充 quoted payload 正向锚点，以及 `StringName` raw lexeme 显式失败断言

#### 现实边界

- `StringName` raw lexeme 有显式 `&` 前缀，因此 backend 能稳定识别 `&"..."` 这种明显漂移形态
- 普通 `String` 不具备同样的可判定性：`"hero"` 既可能是坏 producer 误传的 raw lexeme，也可能是合法的 normalized payload，其运行时内容本来就包含前后引号
- 因此本步骤不能安全地把“所有带前后引号的 `LiteralStringInsn.value()`”一律视为错误，否则会错误拒绝合法 payload
- 当前实现选择的合同是：
  - 对 `StringName`：发现可判定的 raw lexeme 就显式失败
  - 对 `String`：只消费 payload，不做不可靠的形态猜测

#### 目标

让 backend 明确只消费归一化后的 LIR；如果 future producer 再次漂移，应直接 fail-fast，而不是静默“看起来还能跑”。

#### 建议改动

- 调整 `NewDataInsnGen`
- 普通 `String` 路径继续直接消费 normalized payload
- `StringName` 路径删除当前仅针对 `&"..."` 的静默兼容归一化分支
- guard rail 仅能用于“可明确识别的 raw lexeme 形态”，不能把所有带引号的 payload 都当成坏输入

#### 为什么要显式失败

- backend 一旦继续容忍 raw lexeme，future 新 producer 漂移时测试又会被掩盖
- 与其让错误继续表现成 runtime 拼接/切片异常，不如在 codegen 阶段报出“坏 IR 合同”
- 但显式失败也必须建立在“不会误伤合法 payload”的前提上，因此普通 `String` 只能继续依赖前面步骤建立的 producer 合同与测试锚点

#### 需要补的测试

- `CNewDataInsnGenTest`
  - 保留现有 normalized payload 正向断言
  - Java 断言文本必须直接锚定生成出的 C 代码形态，例如 `u8"hello"` / `u8"\"hero\""`，不要误把日志或工具输出中的二次转义层写进期望值
  - 新增普通 `String` / `StringName` 对 quoted payload 的正向断言，证明 backend 不会把合法 payload 误判为 raw lexeme
  - 新增 raw `&"..."` `LiteralStringNameInsn` 的负向断言，证明明显坏合同会被显式拒绝
- 如有必要，可在更贴近 body generation 的测试里增加“坏 IR 必须 fail-fast”的覆盖

#### 验收细则

- 所有 normalized payload 正向 case 继续生成与现状等价的 C 文本
- 明显可检测的 raw `StringName` lexeme 输入不再被 backend 静默接纳
- 合法的 quoted payload 仍能被稳定 materialize，尤其是内容本身带前后引号的 `String` / `StringName`
- `NewDataInsnGen` 中不再残留只服务某一类 producer 漂移的隐式兼容分支

### 步骤 5：补齐真引擎 compile-run 锚点

- 状态：Implemented（2026-04-19）
- 当前落实：
  - 已新增 `src/test/test_suite/unit_test/script/runtime/string_literal_internal_surface.gd`，把编译脚本内部的字符串拼接、`substr(...)`、比较、分支分类和转义字面量集中锚定到同一条 compile-run case
  - 已新增配对 validation 脚本 `src/test/test_suite/unit_test/validation/runtime/string_literal_internal_surface.gd`，逐项从 Godot 侧调用 compiled target，并分别覆盖匹配分支、fallback 分支和带转义/引号的返回值
  - `GdScriptUnitTestCompileRunnerTest` 与 `GdScriptExpandedCoverageCompileRunnerTest` 已把该资源对纳入稳定入口，避免后续 resource discovery 或 focused compile-run 漂移时遗漏这条 surface

#### 目标

把当前 focused tests 绕开的“编译脚本内部字符串字面量 surface”单独补成端到端回归。

#### 建议资源用例

新增一组 `test_suite` 资源脚本，例如：

- `runtime/string_literal_internal_surface.gd`
- `validation/runtime/string_literal_internal_surface.gd`

compiled script 内建议至少暴露以下方法：

- `concat_internal() -> String`
  - 内部直接拼接多个字符串字面量
- `substr_internal() -> String`
  - 内部先构造固定字符串字面量，再做 `substr(2, 3)`
- `compare_internal() -> bool`
  - 内部使用字符串字面量做 `==` / `!=`
- `classify_internal(mode: String) -> String`
  - 用字符串字面量参与分支分类
- `escape_internal() -> String`
  - 返回带 `\n`、`\t` 或 `\"` 的字面量，用来证明转义链路正确

validation script 必须从 Godot 侧逐项调用 compiled target 的这些方法，而不是把字符串参数都放在 validation 侧完成后再传入。只有这样，才能真正锚定“编译脚本内部 literal lowering + backend materialization”的完整 surface。

#### 关联 Java 测试更新

- `GdScriptUnitTestCompileRunnerTest`
  - 更新 `EXPECTED_SCRIPT_PATHS`
- 如希望保留 focused compile-run 入口，建议同时更新：
  - `GdScriptExpandedCoverageCompileRunnerTest`

#### 验收细则

- compile-run case 必须稳定验证至少以下结果：
  - `concat_internal()` 返回无多余引号的目标文本
  - `substr_internal()` 返回 `cde` 这类明确 0-based 期望
  - `compare_internal()` 与 `classify_internal(...)` 不再被污染
  - `escape_internal()` 的运行时字符串内容与预期一致
- validation 输出必须细化到子项，避免一个失败掩盖其他已通过项

### 步骤 6：文档与已知边界清理

- 状态：Implemented（2026-04-19）
- 当前落实：
  - `doc/test_error/test_suite_engine_integration_known_limits.md` 已移除“字符串 surface 仍不稳定”的当前边界表述，并改为已修复回顾 + 回归锚点
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md` 已补充长期合同，明确 `LiteralExpression.sourceText()` 继续保留 raw lexeme，而 string-like LIR literal 的 `value()` 只承载 normalized runtime payload
  - 本文档顶部状态已更新为 `Implemented`，反映步骤 1-6 已全部落地

#### 目标

在修复真正落地后，把“字符串 surface 仍不稳定”从已知边界中移除，防止文档长期滞后。

#### 建议改动

- 更新 `doc/test_error/test_suite_engine_integration_known_limits.md`
  - 问题 6 不再保留为当前 known limit
  - 如有必要，改为“已修复缺陷回顾 + 对应回归测试锚点”
- 在合适的实现文档中补一条长期事实：
  - `LiteralStringInsn.value()` / `LiteralStringNameInsn.value()` 是 normalized payload
  - `LiteralExpression.sourceText()` 仍是源码 lexeme

#### 验收细则

- 修复完成后，不应再有文档把该问题表述为“当前字符串 surface 未闭环”
- 长期文档中必须明确区分：
  - AST source text 事实
  - LIR runtime payload 事实

## 8. 总体验收矩阵

### Utility 层

- 共享 helper 能正确解码普通字符串与 `StringName` lexeme
- malformed 输入显式失败

### IR 合同层

- 文本 LIR parser 与 frontend lowering 对 `LiteralStringInsn` / `LiteralStringNameInsn` 发布同一语义
- record 注释或相关实现文档中明确写出该合同

### Backend 层

- `NewDataInsnGen` 只消费 normalized payload
- raw lexeme 不再被静默接纳

### End-to-End 层

- 新增 compile-run 资源脚本从 compiled script 内部直接覆盖：
  - 字符串拼接
  - `substr`
  - 比较
  - 分类分支
  - 转义

### 文档层

- `known_limits` 不再把问题 6 当作当前边界
- `module_impl` 有一份长期可执行的合同说明或实现事实说明承接该修复

## 9. 推荐测试命令

迭代阶段建议按层分组执行：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests StringUtilTest
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendLoweringBodyInsnPassTest
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CNewDataInsnGenTest
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests COperatorInsnGenEngineTest,CallMethodInsnGenEngineTest
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdScriptExpandedCoverageCompileRunnerTest,GdScriptUnitTestCompileRunnerTest
```

最终提交前，至少应保证以上 targeted set 全绿。

## 10. 实施注意事项

1. 不要为了修复 compile-run case 而把 backend 继续做成“既接受 lexeme 又接受 payload”的双语义消费者，这只会把问题再次埋回去。
2. 不要修改 `LiteralExpression.sourceText()` 的语义去迁就 backend；源码文本与 runtime payload 本来就应是两个层次。
3. compile-run 验证必须让字符串字面量出现在 compiled script 内部，而不是只从 validation script 传参，否则仍然会错过这次缺陷。
4. 文档清理必须在代码与测试都落地后进行，不能先把问题 6 从 known limits 删除再补实现。
