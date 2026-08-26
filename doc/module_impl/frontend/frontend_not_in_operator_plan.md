# Frontend `not in` 操作符实施计划

本文档是 GDScript `not in` 操作符在 gdcc 中的实施计划与验收细则，落地后将转为该特性的长期事实源。

## 文档状态

- 状态：**实施中（步骤 1、2 及其配套测试步骤 3、4 已落地；步骤 5、6 待实施）**
- 创建时间：2026-08-26
- 步骤状态：
  - 步骤 1（sema 复合规则）：**已完成**（2026-08-26）——`resolveBinaryOperatorResultType(...)` 在枚举工厂前拦截 `"not in"` 并委托 `resolveNotInOperatorResultType(...)`；`resolveUnaryExactReturnType(...)` 已静态化；`FrontendExpressionSemanticSupportTest` / `FrontendBodyOwnerProceduresExprTypeTest` / `GodotOperatorTest` 全绿。
  - 步骤 2（lowering 复合指令）：**已完成**（2026-08-26）——`FrontendBinaryOpaqueExprInsnLoweringProcessor.lower(...)` 在 generic 路径前特判 `"not in"`，产出 `BinaryOpInsn(IN, 固定 bool 中间槽) -> UnaryOpInsn(NOT)`；backend 零改动成立。
  - 步骤 3（sema 层测试）：**已完成**（随步骤 1 验收同步落地）
  - 步骤 4（lowering 测试）：**已完成**（2026-08-26）——新增 typed value / dynamic value / condition 三个语境测试，`FrontendLoweringBodyInsnPassTest` 全绿；另以 `gd.script.gdcc.frontend.*` 全包回归兜底通过。
  - 步骤 5（端到端 test_suite 锚点）：未开始
  - 步骤 6（文档同步）：未开始
- Godot 对齐基线：Godot 4.x（`modules/gdscript`）
- 关联文档：
  - `frontend_unary_binary_expr_semantic_implementation.md`（运算符语义主合同，§4.4 当前冻结 `not in` 为 `UNSUPPORTED`）
  - `frontend_is_type_test_implementation.md`（`is not` 的正向测试 + `UnaryOpInsn(NOT)` 复合 lowering 先例）
  - `frontend_rules.md`（MVP 支持约定中的 `not in` 条目当前声明不支持）
  - `doc/module_impl/common_rules.md`

## 1. 背景与目标

GDScript 的 `a not in b` 是合法源码运算符，语义严格等价于 `not (a in b)`。gdcc 当前在 sema 层将其显式发布为 `UNSUPPORTED`（compile blocker），本计划将其完整落地为 compile-ready 特性。

目标：

- `not in` 在 sema 层按复合规则 `not (lhs in rhs)` 完成类型分析，结果类型恒为 `bool`；
- lowering 产出 `BinaryOpInsn(IN)` + `UnaryOpInsn(NOT)` 的复合 LIR；
- 复用现有 C backend（`GDEXTENSION_VARIANT_OP_IN` / builtin evaluator 与 unary `NOT` 的 bool 特化路径），不新增 backend 路线；
- 不引入 synthetic AST、不新增 `NOT_IN` 枚举或 metadata。

## 2. Godot 对齐语义基线

对 godotengine/godot 仓库（`modules/gdscript`）与 tree-sitter-gdscript 的调研结论：

1. Godot tokenizer 没有 `NOT_IN` token；parser（`gdscript_parser.cpp`）在解析二元表达式时识别 `not in`，先构造普通 `in` 二元节点，再用 `UnaryOpNode(OP_LOGIC_NOT)` 包裹。
2. `Variant::Operator` 只有 `OP_IN`，没有 `OP_NOT_IN`；bytecode/VM 层同样只有 `OP_IN` + 逻辑非。
3. `OP_IN` evaluator 覆盖 `Array`、`Dictionary`、`String`/`StringName`、各 packed array、`Object` 属性查找，**返回类型恒为 `bool`**。
4. analyzer 对 `in` 做 evaluator lookup：静态支持的操作数对结果为 `bool`；hard-typed 不支持时报无效操作数；`Variant`/dynamic 操作数保持 runtime-open（不在编译期判非法，运行时非法配对报错）。错误消息锚定内层 `in` 配对。
5. tree-sitter-gdscript 的 grammar 把 `not in` 作为单个 binary operator（与 `in` 同优先级），AST 中 operator 字段保留源码字面量 `"not in"`。

对 gdcc 的直接推论：

- gdparser 产出的 AST 已是 `BinaryExpression("not in", left, right)`（单节点，operator 为源码字面量），parser 层无需任何改动；
- 语义层应复刻 Godot 的两段式结构：先按 `in` 规则分析操作数配对，再对结果应用逻辑 `not`；
- 由于 `in` 的运行时结果恒为 `bool`，`not in` 的静态结果类型恒为 `bool`（runtime-open 只表示"不因 Variant 操作数在编译期判非法"，不改变结果类型）——这与既有 `and`/`or` special rule 对 dynamic 操作数仍发布 `RESOLVED(bool)` 的先例一致（`FrontendBodyOwnerProceduresExprTypeTest.java:1672-1714`）；
- 失败诊断锚定内层 `in` 配对（与 Godot 消息风格一致）。

## 3. 现状链路与障碍点

`a not in b` 当前链路：

```text
source: a not in b
  -> gdparser AST: BinaryExpression("not in", left, right)     # 无需改动
  -> sema: FrontendExpressionSemanticSupport.resolveBinaryOperatorResultType(...)
       显式拦截 "not in" -> FrontendExpressionType.unsupported(...)   # 障碍点 1
  -> FrontendBodyOwnerProcedures 发布 UNSUPPORTED + sema.unsupported_expression_route 诊断
  -> FrontendCompileCheckAnalyzer 视 UNSUPPORTED 为 compile blocker，lowering 不可达
```

辅助事实：

- `GodotOperator.fromSourceLexeme("not in", BINARY)` fail-closed（`GodotOperator.java:88-111`），**保持不变**；
- 普通 `in` 的 sema 路径：source-level special rule 未命中 -> runtime-open 判定 -> builtin metadata lookup（owner 取左操作数 raw builtin 名，右操作数按 raw 名匹配），metadata 返回类型即表达式类型（`FrontendExpressionSemanticSupport.java:667-729`、`2029-2053`）；extension metadata 中全部 `in` 条目的 `return_type` 均为 `bool`；
- 普通 binary lowering：`FrontendCfgGraphBuilder.buildValue(...)` 对非 short-circuit `BinaryExpression` 发出 `OpaqueExprValueItem`（两个 operand value id），由 `FrontendBinaryOpaqueExprInsnLoweringProcessor.lower(...)` 生成单条 `BinaryOpInsn(fromSourceLexeme(operator))`（`FrontendOpaqueExprInsnLoweringProcessors.java:285-309`）——若只放开 sema，此处会因 `"not in"` fail-closed 而炸毁，**是必须同步修改的障碍点 2**；
- condition 语境（`if a not in b:`）：`buildCondition(...)` 只特判 unary `not`（交换分支）与 short-circuit `and/or`；`not in` 作为 `BinaryExpression` 走 `buildConditionFromValue(...)`（先求值再分支），该路径在语义 `RESOLVED` 后天然可用（`FrontendCfgGraphBuilder.java:1932-1966`）；
- 复合 lowering 先例：`is not` 在 `emitIsInstanceOfWithOptionalNot(...)` 中先把正向测试写入 `session.allocateWritableRouteTemp("type_test_positive", GdBoolType.BOOL)` 临时槽，再 `UnaryOpInsn(result, NOT, temp)`（`FrontendSequenceItemInsnLoweringProcessors.java:1817-1835`）；
- backend 边界（决定 D3/D4 形状的硬约束）：
  - unary `NOT` 在 C backend 只有 `NOT + bool` 特化路径，**没有 Variant evaluator 路径**；Variant 操作数查不到 builtin metadata 会 fail-fast（`OperatorResolver.java:48-79`）；
  - `checkAssignable(bool, Variant)` 为 false（`ClassRegistry.java:1001-1013`），结果槽为 Variant 的 `UnaryOpInsn(NOT bool -> Variant)` 同样无法通过校验；
  - `BinaryOpInsn(IN)` 遇 Variant 操作数走 `godot_variant_evaluate`，对非 Variant 结果槽做 runtime type-check + unpack（`OperatorInsnGen.java:273-340`），因此 **IN 的 bool 结果可以安全写入 bool 槽**，typed 与 dynamic 操作数皆然；
  - typed 复合形态 `IN -> bool 临时值 -> NOT(bool)` 已被 `CCodegenTest.java:2294-2335` 覆盖。

## 4. 冻结设计决策

- **D1（AST 不变）**：保持 `BinaryExpression("not in", left, right)` 原样流经 sema 与 lowering；不做 AST rewrite，不造 synthetic 节点。
- **D2（枚举不变）**：`GodotOperator` 不新增 `NOT_IN`；`fromSourceLexeme("not in", ...)` 继续 fail-closed。sema 在调用枚举工厂之前拦截 `"not in"`，lowering 在调用枚举工厂之前拦截 `"not in"`。
- **D3（sema 两段式复合规则，结果恒为 bool）**：在 `resolveBinaryOperatorResultType(...)` 内把 `"not in"` 分支替换为：
  1. 以 operator `"in"` 递归调用自身，得到内层结果；
  2. 内层 `FAILED` -> 原样传播（诊断锚定内层 `in` 配对，与 Godot 一致）；
  3. 内层 `RESOLVED` 或 `DYNAMIC` -> 对 `GdBoolType.BOOL` 查 unary `NOT` metadata 返回类型并以其为准发布 `RESOLVED(该类型)`（防御性查询，实际恒命中 `bool`）；未命中则 `FAILED`（防御分支）。

  **禁止**把 generic binary 的 `DYNAMIC(Variant)` 原样套到 `not in`：runtime-open 只表示"不在编译期判非法"，结果类型仍是 `bool`；若发布 `DYNAMIC(Variant)`，lowering 产物会在 C backend 因 unary `NOT` 无 Variant 路径而 fail-fast（见 §3 backend 边界）。
- **D4（lowering 复合指令，中间槽固定 bool）**：`FrontendBinaryOpaqueExprInsnLoweringProcessor.lower(...)` 在 generic 路径前特判 `"not in"`：
  - 用 `session.allocateWritableRouteTemp("not_in_positive", GdBoolType.BOOL)` 申请**固定 `bool` 类型**的中间槽（typed / dynamic 操作数皆同）；
  - 依次追加 `BinaryOpInsn(temp, GodotOperator.IN, lhsSlot, rhsSlot)` 与 `UnaryOpInsn(resultSlot, GodotOperator.NOT, temp)`；
  - dynamic 操作数时 backend 对 `BinaryOpInsn(IN)` 走 `godot_variant_evaluate` 并 unpack 进 bool 槽（既有能力），`UnaryOpInsn(NOT)` 走 `NOT + bool` 特化路径，backend 零改动成立；
  - 该文件需新增 `import gd.script.gdcc.type.GdBoolType;`（其余所需类型已 import）。
- **D5（condition 语境不做分支翻转）**：`if a not in b:` 走 `buildConditionFromValue(...)` 求值后分支，不新增 `buildCondition` 特判。理由：分支翻转需要内层 `in` 节点作为 condition fragment root，而 synthetic 节点没有 published side-table facts，会在 `requireLoweringReadyExpressionType(...)` 处失败；求值路径语义正确且已被 condition normalization 合同覆盖。
- **D6（backend 零改动）**：不新增 C codegen 路线、runtime helper 或 GDExtension opcode。该论断依赖 D3/D4 的"结果与中间槽恒为 bool"形状；回归证明以 `CCodegenTest`（已覆盖 typed `IN -> bool -> NOT` 复合形态，含 unary `NOT + bool` 路径）与 `COperatorInsnGenTest`（单独的 `IN`：typed evaluator 与 Variant unpack 到 bool）共同承担。
- **D7（诊断归属不变）**：`not in` 的诊断 owner 仍是 expr analyzer；`FAILED` 对应 `sema.expression_resolution`；不再产生 `sema.unsupported_expression_route`。
- **D8（compile gate 零改动）**：`FrontendCompileCheckAnalyzer` 无需修改；`RESOLVED` 自动放行（`isCompileBlocking` 对 `RESOLVED` 为 false）。

## 5. 分步骤实施计划

### 步骤 1：sema 复合规则落地

改动位置：`src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`

1. 将 `resolveBinaryOperatorResultType(...)` 中第 678-683 行的 `UNSUPPORTED` 拦截替换为：

   ```java
   if ("not in".equals(actualOperatorText)) {
       return resolveNotInOperatorResultType(classRegistry, leftOperandType, rightOperandType);
   }
   ```

2. 新增 private static helper `resolveNotInOperatorResultType(...)`，按 D3 实现两段式逻辑（内层 `FAILED` 原样传播；否则对 `GdBoolType.BOOL` 查 unary `NOT` metadata 并发布其返回类型）。
3. 将现有实例方法 `resolveUnaryExactReturnType(...)`（第 1935-1956 行）改为 `static` 并显式接收 `ClassRegistry` 参数，同步更新第 622 行唯一调用点；新 helper 复用该方法做 `NOT` metadata 查询（`findOperatorOwnerClass` / `parseOperatorReturnType` 已是 static，改动机械）。
4. 更新第 632-635 行 `resolveBinaryExpressionType(...)` 的方法级 `///` 注释，移除 "fail-closed `not in` boundary" 表述，改为 "source-level composite rule（`not in`）"。

验收细则：

- `./gradlew classes --no-daemon --info --console=plain` 编译通过；
- `FrontendExpressionSemanticSupportTest`、`FrontendBodyOwnerProceduresExprTypeTest` 中现有 `not in` 用例按步骤 3 同步更新后通过；
- 手工核对：`1 not in ints_a`（`Array[int]`）-> `RESOLVED(bool)`；`dynamic_value not in ints_a` -> `RESOLVED(bool)`；`typed_variant not in ints_a` -> `RESOLVED(bool)`；`"hello" not in 1` -> `FAILED`（消息锚定 `'in'` 配对）。

### 步骤 2：lowering 复合指令落地

改动位置：`src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendOpaqueExprInsnLoweringProcessors.java`（`FrontendBinaryOpaqueExprInsnLoweringProcessor.lower(...)`，第 292-308 行）

在 `requireOpaqueOperandCount(item, 2)` 之后、generic `BinaryOpInsn` 追加之前插入 `"not in"` 特判，按 D4 生成两条指令（中间槽固定 `GdBoolType.BOOL`，经 `session.allocateWritableRouteTemp("not_in_positive", ...)` 分配）；generic 路径保持不变。新增 `import gd.script.gdcc.type.GdBoolType;`，其余所需类型（`GodotOperator`、`BinaryOpInsn`、`UnaryOpInsn`）已在文件中。

验收细则：

- 编译通过；
- 步骤 4 的 lowering 测试通过，证明 value 语境（typed 与 dynamic 操作数）产出 `BinaryOpInsn(IN) -> UnaryOpInsn(NOT)` 指令对，且 condition 语境（`if x not in y:`）正常走完 value+branch 路径。

### 步骤 3：sema 层测试更新

改动位置：

1. `src/test/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupportTest.java`
   - 第 1051-1054 行：`1 not in ints_a`（下标 14）期望由 `UNSUPPORTED` 改为 `RESOLVED` + `bool`；
   - 在该测试源码 text block **末尾追加**（避免扰动既有下标 0-15）`dynamic_value not in ints_a`、`typed_variant not in ints_a`、`"hello" not in 1` 三条表达式语句（新下标 16、17、18），新增断言：前两者 `RESOLVED` + `bool`，第三者 `FAILED` 且消息含 `'in'`；
   - 测试方法名 `resolveBinaryExpressionTypePublishesMetadataDynamicSpecialAndUnsupportedOutcomes` 随语义变化改名（例如 `...SpecialAndNotInOutcomes`）。
2. `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendBodyOwnerProceduresExprTypeTest.java`
   - 第 1680 行变量 `unsupported` 改名为 `not_in_membership`（语义已反转为正向支持）；
   - 第 1741-1743 行断言改为 `RESOLVED` + `bool`；
   - 第 1757-1759 行 `sema.unsupported_expression_route` 断言改为 `isEmpty()`；
   - 第 1745-1755 行 `sema.expression_resolution` 仍为 3 条（`not in` 不再产生诊断）。

验收细则：

- `./gradlew test --tests FrontendExpressionSemanticSupportTest --tests FrontendBodyOwnerProceduresExprTypeTest --no-daemon --info --console=plain` 全绿；
- `GodotOperatorTest` 不做任何修改且仍全绿（枚举层 fail-closed 不变）。

### 步骤 4：lowering 测试

改动位置：`src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`

新增测试（参照第 9340-9373 行 `is not` lowering 先例与 `requireOnlyInstruction` 辅助）：

1. typed value 语境：`var r: bool = 1 not in ints_a`（或等价形态）断言 LIR 依次包含
   - `BinaryOpInsn`，operator 为 `GodotOperator.IN`，结果槽名以 `cfg_writable_not_in_positive_` 为前缀、槽类型为 `bool`；
   - `UnaryOpInsn`，operator 为 `GodotOperator.NOT`，operand 为该临时槽，结果槽为表达式结果槽；
2. dynamic value 语境：`var r := dynamic_value not in ints_a`（`dynamic_value` 无类型标注）断言产出相同的指令对形状，中间槽类型仍为 `bool`；
3. condition 语境：`if 1 not in ints_a:` 断言 lowering 正常完成且存在以该表达式求值结果为 condition value 的 branch。

验收细则：

- `./gradlew test --tests FrontendLoweringBodyInsnPassTest --no-daemon --info --console=plain` 全绿。

### 步骤 5：端到端 test_suite 锚点

改动位置：

1. 新增 `src/test/test_suite/unit_test/script/smoke/not_in_membership.gd`，覆盖：
   - `Array[int]` 成员与非成员（含 `if x not in arr:` condition 语境）；
   - `Dictionary` key 命中/未命中；
   - `String` 子串命中/未命中；
   - dynamic（无类型标注）操作数的 runtime-open 路径（运行时合法配对，验证 evaluate+unpack 到 bool 的复合链路）；
   - 与 `not (a in b)` 手写等价形式的结果一致性掩码断言（参照 `smoke/object_nil_equality.gd` 的 bitmask 风格）。
2. 新增配套 `src/test/test_suite/unit_test/validation/smoke/not_in_membership.gd`（runner 要求每个 script 用例必须有同相对路径的 validation 孪生文件，见 `GdScriptUnitTestCompileRunner.java:94-106`）。
3. 在 `src/test/java/gd/script/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java` 的 `EXPECTED_SCRIPT_PATHS` 中按字典序注册 `"smoke/not_in_membership.gd"`（位于 `"smoke/basic_arithmetic.gd"` 与 `"smoke/object_identity_equality.gd"` 之间）。

验收细则：

- `./gradlew test --tests GdScriptUnitTestCompileRunnerTest --no-daemon --info --console=plain` 全绿（该测试依赖 zig/godot 环境，按既有约定环境不可用时跳过并记录）；
- `CCodegenTest` 与 `COperatorInsnGenTest` 不修改且全绿（backend 零改动的回归证明；前者已覆盖 typed `IN -> bool -> NOT` 复合形态，smoke 用例负责 dynamic 复合路径的真实验收）。

### 步骤 6：文档同步

1. `frontend_unary_binary_expr_semantic_implementation.md`：
   - §1.2 支持面加入 `not in`（同步修正第 50 行"仍保持显式 unsupported 边界"表述）；
   - §2.3 保留"枚举 source 工厂拒绝 `not in`"，补充说明 sema 复合规则已在工厂调用前接管；
   - §4.4 整节改写为"`not in` 复合规则"，描述 D3/D4/D5 并指向本文档；
   - §5 第 366 行与 §6 第 390 行的 `not in` 表述同步更新（不再被 compile gate 阻断、不再是 unsupported 边界）；§6 第 380 行 `GodotOperatorTest` 的 "`not in` fail-closed" 锚点**保留不变**（枚举层 fail-closed 合同不变，与步骤 3 "`GodotOperatorTest` 不做任何修改"一致）；§7.5 移除或改写为已落地说明。
2. `frontend_rules.md` 中 MVP 支持约定的 `not in` 条目（当前约第 125 行，引用时核对行号不要写死）：改为"`not in` 已按 `not (lhs in rhs)` 复合规则支持"，并链接本文档。
3. `frontend_chain_binding_expr_type_implementation.md` 第 363、380、508 行：三处"`not in` 显式 `UNSUPPORTED`"表述更新为已支持并指向本文档。
4. `frontend_type_check_analyzer_implementation.md` 第 78 行：`not in` 不再"例外地保持显式 `UNSUPPORTED`"，更新表述。
5. `frontend_compile_check_analyzer_implementation.md` 第 282 行：`not in` 不再被 compile gate 阻断，更新表述。
6. `frontend_is_type_test_implementation.md` 第 26 行：保留"`not in` 不属于本合同"，补充指向本文档的交叉引用。
7. `diagnostic_manager.md` 无 `not in` 专条，已核实不需改动。
8. 本文档状态由"计划阶段"更新为"已落地"，并保留验收细则作为回归锚点。

验收细则：文档交叉引用一致，无残留"`not in` 不支持"表述（`frontend_is_type_test_implementation.md` 的合同边界声明与枚举层 fail-closed 表述除外）。

## 6. 验收细则总表

| 步骤 | 验证命令 | 通过标准 |
| --- | --- | --- |
| 1 | `./gradlew classes --no-daemon --info --console=plain` | 编译通过 |
| 1+3 | `./gradlew test --tests FrontendExpressionSemanticSupportTest --tests FrontendBodyOwnerProceduresExprTypeTest --tests GodotOperatorTest --no-daemon --info --console=plain` | 全绿；`not in` 结果符合 D3（合法配对恒 `RESOLVED(bool)`，非法配对 `FAILED` 锚定 `'in'`） |
| 2+4 | `./gradlew test --tests FrontendLoweringBodyInsnPassTest --no-daemon --info --console=plain` | 全绿；指令对形状符合 D4（中间槽恒 `bool`） |
| 5 | `./gradlew test --tests GdScriptUnitTestCompileRunnerTest --tests CCodegenTest --tests COperatorInsnGenTest --no-daemon --info --console=plain` | 全绿（环境依赖测试按约定跳过） |
| 收尾 | `./gradlew clean build --no-daemon --info --console=plain` | 全量构建与测试通过 |

## 7. 非目标与风险

非目标：

- 不新增 `NOT_IN` 枚举、metadata、LIR 指令或 C opcode；
- 不做 condition 语境的分支翻转优化（D5）；
- 不改 parser / gdparser；
- 不引入 `not in` 常量折叠（当前无对应 folding 框架合同）。

风险与缓解：

- **R1（`resolveUnaryExactReturnType` 静态化触碰共享代码）**：仅一个既有调用点（第 622 行），改动机械；用步骤 1 编译 + sema 测试兜底。
- **R2（dynamic 操作数的 backend 通路）**：已核实 `godot_variant_evaluate` 对非 Variant 结果槽做 type-check + unpack（`OperatorInsnGen.java:305-331`），`IN -> bool` 与 `NOT bool -> bool` 均为既有路径；步骤 4 dynamic lowering 测试与步骤 5 smoke 用例双重兜底。
- **R3（condition 语境行为回退）**：D5 不改 CFG 代码，行为由既有 condition normalization 合同保证；步骤 4 条件语境测试兜底。
- **R4（递归调用自身导致死循环）**：内层递归固定传 `"in"`，不会重入 `"not in"` 分支；`not in` 不可能出现在 compound assignment 中，`resolveBinaryOperatorResultType(...)` 的另一调用方不受影响。

## 8. 长期维护约束

- `GodotOperator.fromSourceLexeme("not in", ...)` 必须继续 fail-closed；任何"把 `not in` 加进枚举别名表"的改动都是架构错误（会丢失逻辑取反语义）。
- sema 对 `"not in"` 的拦截必须先于枚举工厂调用；lowering 对 `"not in"` 的特判必须先于 generic `BinaryOpInsn` 路径。
- `not in` 的 sema 结果必须保持 `RESOLVED(bool)` / `FAILED` 两态，不得回退为 `DYNAMIC(Variant)`（backend unary `NOT` 无 Variant 路径）。
- 中间临时槽必须固定 `GdBoolType.BOOL` 并经 `session.allocateWritableRouteTemp(...)` 分配，不得手写槽 id、复用 operand 槽或改用根表达式 published type。
- 未来若做 condition 分支翻转优化，必须先解决 synthetic 内层节点的 side-table facts 问题，并同步更新本文档与 `frontend_unary_binary_expr_semantic_implementation.md`。
- 任何行为变更必须同步更新本文档、`frontend_unary_binary_expr_semantic_implementation.md` §4.4、`frontend_rules.md` 的 `not in` 条目及步骤 6 列出的全部文档。
