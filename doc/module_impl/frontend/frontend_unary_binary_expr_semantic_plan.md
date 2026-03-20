# Frontend Unary / Binary Expression 语义分析实施计划

> 本文档记录 `UnaryExpression` / `BinaryExpression` 在 frontend body phase 中转正的实施计划、设计边界、代码落点、测试矩阵与验收标准。本文档面向实施，不保留阶段流水账；实现完成后，应将已冻结事实归并到对应 implementation 文档。

## 文档状态

- 状态：已完成（P1-P5 已完成；稳定事实已归并到 implementation 文档；`not in` 保持显式 unsupported 边界）
- 更新时间：2026-03-20
- 当前版本约定：`not in` 操作符不属于已支持的 frontend binary 语义面；遇到该源码运算符时，frontend 必须显式发布 `UNSUPPORTED`
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/**`
  - `src/main/java/dev/superice/gdcc/enums/GodotOperator.java`
  - `src/main/java/dev/superice/gdcc/gdextension/ExtensionBuiltinClass.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/test/java/dev/superice/gdcc/enums/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/backend/operator_insn_implementation.md`
  - `doc/gdcc_type_system.md`
- 参考实现 / 事实依据：
  - Godot `modules/gdscript/gdscript_analyzer.cpp`
    - `get_operation_type(...)` 对 `OP_AND` / `OP_OR` 走 source-level 特判，任意参数类型可参与，结果固定为 `bool`
    - `OP_ADD` 的 `Array + Array` 在两侧元素类型相同且已知时保留 typed array 结果类型
  - `E:/Projects/gdparser/vendor/tree-sitter-gdscript/grammar.js`
    - 二元运算符 grammar 同时接受 `and` / `&&`、`or` / `||`、`in` / `not in`
    - 一元运算符 grammar 同时接受 `not` / `!`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/lowering/CstToAstMapper.java`
    - `BinaryExpression.operator()` / `UnaryExpression.operator()` 保存源码字面量，而不是 extension metadata canonical name
- 明确非目标：
  - 不在本计划中转正 `ConditionalExpression`
  - 不在本计划中实现 `ConditionalExpression` 的 lowering / CFG / compile-only 放行
  - 不在本计划中扩张 `Dictionary`、packed array family 或其他 container 的“保型”规则
  - 不在本计划首轮把 `not in` 提升为新的 backend / LIR 对应运算符枚举
  - 不在本计划中于 parser / gdparser lowering 侧把 `not in` 提前改写成 synthetic `not (in)` AST
  - 不在本计划中引入 numeric promotion、`StringName` / `String` 互转、`null -> Object` 等更宽隐式转换
  - 不在本计划中把 compile-only blocker 反向回灌到 shared semantic 路径

---

## 1. 背景与问题陈述

当前 frontend body phase 已经能稳定发布 literal、identifier、call、attribute chain、assignment、subscript，以及已转正的 `UnaryExpression` / `BinaryExpression` 类型事实；剩余显式 deferred 重点已收敛到 `ConditionalExpression` 等明确未落地的表达式家族。实施前 `UnaryExpression` / `BinaryExpression` 都曾停留在显式 deferred 集合里，这带来了三个直接后果：

1. 语义能力与语言支持面明显错位。
   - 源码层面最常见的算术、比较、位运算、逻辑运算仍被当作“尚未分析”处理。
2. 上游已经稳定的 typed fact 无法继续向 type-check 与 compile gate 传递。
   - 例如 `1 + 2`、`a > b`、`flag && value` 这样的表达式本身并不属于 compile-only 缺口，却会因为根节点仍是 `DEFERRED` 被 downstream 当成不稳定结果。
3. 当前实现还没有把“源码运算符字面量”和“extension metadata canonical operator name”区分清楚。
   - gdparser AST 保存的是源码字面量，如 `&&`、`||`、`!`
   - extension metadata 使用的是 canonical 名字，如 `and`、`or`、`not`、`unary-`、`unary+`
   - 这两套表示法不是同一个协议层，强行混用会把别名、运算元个数和特殊语义一并搅乱

本轮计划的目标不是“把所有表达式一次性补完”，而是把 `UnaryExpression` / `BinaryExpression` 从显式 deferred 集合中有边界地转正，并把最关键的 source-level 规则补齐到足以支撑后续实现。

---

## 2. 当前现状与已确认差距

### 2.1 Frontend 当前显式 deferred 边界

当前 `FrontendExpressionSemanticSupport.resolveRemainingExplicitExpressionType(...)` 已不再接管 unary / binary；剩余显式 deferred 边界为：

- `ConditionalExpression`

这说明当前 unary / binary 缺口已经转正，剩余缺口主要集中在 `ConditionalExpression` 与其他明确保留的 compile-only / deferred 家族。

### 2.2 现有 condition 合同已经足够成为 `and/or` 的 source-level 基线

当前 `FrontendTypeCheckAnalyzer.visitConditionExpression(...)` 已经明确冻结为：

- condition 只要求根表达式已稳定发布 typed fact
- 稳定状态为 `RESOLVED` 或 `DYNAMIC`
- 不把 source-level condition 重新解释成 undocumented strict-bool dialect

因此，`and/or` 不能按“两个操作数都必须是 `bool`”来做 frontend 规则。它们的操作数约束应与 condition 表达式保持同一合同，否则 frontend 将人为引入比 Godot 更窄的方言。

### 2.3 AST 中保存的是源码 lexeme，不是 metadata operator name

gdparser 当前通过 `operatorBetween(...)` / `prefixOperator(...)` 直接把源码中的 operator 文本写入 AST：

- 一元：`not`、`!`、`-`、`+`、`~`
- 二元：`and`、`&&`、`or`、`||`、`in`、`not in`、`+`、`-` 等

这意味着 frontend semantic analysis 不能直接把 AST operator 文本拿去喂给 `ExtensionBuiltinClass.ClassOperator.operator()` 的 metadata 解析分支，否则：

- `&&` / `||` / `!` 会直接不认识
- `+` / `-` 会出现 unary / binary 语义歧义
- `not in` 会被误当成普通 alias 问题，实际它是独立运算语义

### 2.4 当前 `GodotOperator` 与 extension metadata 解析路径只覆盖 canonical name

当前 `GodotOperator` 只有枚举常量，没有来源感知的工厂函数。`ExtensionBuiltinClass.ClassOperator.operator()` 也还是手写 `switch`，只认 metadata canonical name：

- 认识：`and`、`or`、`not`、`unary-`、`unary+`
- 不认识：`&&`、`||`、`!`
- 也没有 `not in`

这条路径对 backend 读取 extension metadata 是成立的，但对 frontend 解析 AST 源码运算符并不成立。

### 2.5 不能把 Extension API 生搬到所有 binary rule 上

经 Godot 参考实现确认，至少有两类规则不能只靠 extension metadata 生搬：

1. `and/or`
   - Godot 在 analyzer 中把它们作为 source-level 逻辑运算特殊处理
   - 其结果固定为 `bool`
   - 它们不走普通 Variant operator evaluator 路径
2. `Array[T] + Array[T]`
   - Godot 对同元素类型 typed array 做结果保型
   - 这不是 extension metadata 本身能表达出来的信息，因为 metadata 只看到 builtin `Array`，看不到 frontend 持有的 element type 参数

这两类规则必须在 frontend semantic support 中被明确建模，而不是指望 extension metadata 自己“神奇地带出”源码层语义。

---

## 3. 设计结论

### 3.1 实现边界应继续留在 shared expression semantic support

本轮实现应继续遵守当前 body-phase owner 边界：

- `FrontendExpressionSemanticSupport`
  - 负责 unary / binary 的纯语义求值
  - 不发布 side table
  - 不发 diagnostic
- `FrontendExprTypeAnalyzer`
  - 继续作为 `expressionTypes()` owner
  - 负责 root-owned `FAILED` / `DEFERRED` / `UNSUPPORTED` 的 diagnostic owner 规则
- `FrontendTypeCheckAnalyzer`
  - 只消费已发布 typed fact
  - 不复制 operator 语义
- `FrontendCompileCheckAnalyzer`
  - 只基于已发布状态决定 compile gate
  - 不再把“unary/binary 根节点尚未实现”误当成 compile-only 缺口

换句话说，本轮不是再加一个 analyzer，而是在 shared helper 内补齐规则，让现有 owner 边界继续成立。

### 3.2 `GodotOperator` 必须区分 metadata name 与 source lexeme

本轮推荐把 `GodotOperator` 的来源正规化成两条明确工厂：

1. metadata canonical name 工厂
   - 例如 `fromMetadataName(String name)`
   - 专供 `ExtensionBuiltinClass.ClassOperator.operator()` 与其他 extension metadata 消费者使用
2. source lexeme 工厂
   - 例如 `fromSourceLexeme(String lexeme, OperatorArity arity)`
   - 或至少拆成 `fromUnarySourceLexeme(...)` / `fromBinarySourceLexeme(...)`

这里不能做成“只有一个 `fromSourceLexeme(String)` 的单参数版本”，原因很直接：

- `-` 既可能表示 `NEGATE`，也可能表示 `SUBTRACT`
- `+` 既可能表示 `POSITIVE`，也可能表示 `ADD`

如果源码工厂没有 arity 语境，它就不是一个可靠工厂，而是一个把语义歧义推给调用方的半成品接口。

### 3.3 `ClassOperator.operator()` 应只保留 metadata 委托，不再自带解析策略

当前 `ExtensionBuiltinClass.ClassOperator.operator()` 的手写 `switch` 应下沉到 `GodotOperator.fromMetadataName(...)`，原因有两点：

1. backend 与 frontend 将共享同一 canonical operator 真源
2. 可以从接口层防止“拿 metadata 路径误处理源码 lexeme”的协议漂移

这也意味着新增源码工厂时，不应修改 `ClassOperator` 去接受 `&&` / `||` / `!`。那样是在扩张 metadata 契约，而不是在修正 frontend 契约。

### 3.4 `UnaryExpression` 首轮采用“exact + 保守 dynamic”策略

一元表达式首轮建议使用如下策略：

1. 先解析 operand
2. 若 operand 状态是 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`
   - 根节点直接传播 upstream 结果
   - `rootOwnsOutcome = false`
3. 若 operand 状态是 `DYNAMIC`
   - 首轮默认返回 `DYNAMIC(Variant)`
   - 不在本轮尝试从 operator family 反推出更窄结果类型
4. 若 operand 状态是 `RESOLVED`
   - 把源码 lexeme 通过 source factory 归一化到 `GodotOperator`
   - 再走 exact unary operator 规则
   - 命中则 `RESOLVED(returnType)`
   - 未命中则 root-owned `FAILED`

这样做的理由是：

- 一元 exact 规则不复杂，适合先落地
- 对 dynamic operand 保守处理，可以避免在本轮把整套 Variant-return precision 一次性卷进来
- `FrontendTypeCheckAnalyzer` 已经接受 `DYNAMIC` 作为稳定 published fact，因此这条保守策略不会阻断 condition / initializer / return 的既有合同

### 3.5 `BinaryExpression` 必须拆成“普通 metadata 路径”和“source-level 特殊路径”

二元表达式不能只写成一个“大 switch + metadata lookup”。实现时应显式分成两层：

1. source-level 特殊路径
   - `and` / `&&`
   - `or` / `||`
   - `Array[T] + Array[T]` 同元素类型保型
2. 普通 exact metadata 路径
   - 其他可由 builtin operator metadata 稳定回答的二元运算

这条分层是本轮最重要的架构结论。若把两类规则混成“统一 metadata 优先”，实现会在一开始就把 `and/or` 与 typed array preserve 做错。

### 3.6 `and/or` 的操作数合同必须与 condition 表达式保持一致

`and/or` 的 frontend 规则应冻结为：

1. 先分别解析左右操作数
2. 操作数只要求满足 condition contract
   - 即已稳定发布 typed fact
   - 稳定状态为 `RESOLVED` 或 `DYNAMIC`
   - 不要求操作数本身是 `bool`
3. 当两个操作数都满足合同后
   - 根节点直接发布 `RESOLVED(bool)`

这里要特别强调两点：

- `and/or` 的结果类型在 source-level 上是固定的 `bool`
- 即使左右操作数之一是 `DYNAMIC(Variant)`，根节点也不应因此回退成 `DYNAMIC(Variant)`；因为动态性影响的是运行时 truthiness，不影响该运算结果固定为布尔值这一事实

### 3.7 typed array 保型规则首轮只锚定 `Array[T] + Array[T]`

本轮只建议落地 Godot 已明确存在、且当前仓库类型系统已稳定持有足够信息的一条保型规则：

- `Array[T] + Array[T] -> Array[T]`，前提是两侧 element type 都存在且相同

实现时必须注意：

1. 这是一条覆盖在 generic `Array + Array` 之上的更精确规则
2. 若元素类型不同，不能错误保留左侧 typed array 类型
3. 若元素类型未知，也不能凭空猜出 typed result
4. 不应把这条规则无依据地扩张到：
   - `Dictionary[K, V]`
   - packed array family
   - 任意自定义 container

首轮的正确做法是：

- 命中同元素类型时，返回更窄的 `Array[T]`
- 其他情况回退 generic binary route，而不是擅自发明更多“保型”

### 3.8 普通 binary metadata 路径必须保持“左右操作数顺序敏感”

普通 binary 路径必须保留 Godot / backend 当前已经对齐的顺序敏感性：

- 只按 `leftType + operator + rightType` 查找
- 不做自动 swap
- 不做“左右都试一遍谁能过就算谁”的猜测

原因很简单：

- 运算符不全是交换律
- metadata owner 语义与错误消息都会受左右顺序影响
- 自动交换会制造表面上“更智能”、实则更难调试的隐式语义漂移

### 3.9 `not in` 的推荐实现路径是“语义复合规则”，不是 alias

当前版本事实先行冻结为：

- `not in` 操作符当前不支持
- frontend 不得把它按 `in` 的普通 binary route 解析
- frontend 不得仅因为其结果理论上为 `bool` 就把它发布成已支持的 `RESOLVED(bool)`

`not in` 当前 grammar 会把它作为单个二元源码运算符解析出来。它在结果语义上等价于：

- `lhs not in rhs`
- `not (lhs in rhs)`

但这种等价只成立在“语义复合规则”层面，不成立于“源码别名映射”层面。因此，本计划冻结以下推荐方案：

1. 保持 parser 与 AST 现状不变
   - `BinaryExpression.operator()` 继续保留原始源码字面量 `not in`
   - 不在 parser / gdparser lowering 侧提前构造 synthetic `UnaryExpression("not", BinaryExpression("in", ...))`
2. `GodotOperator` 的源码工厂继续对 `"not in"` fail-closed
   - 它不能被映射成 `IN`
   - 否则会直接丢掉逻辑取反语义
3. frontend semantic support 在 binary 分发层面对 `not in` 单独开复合规则分支
   - 先按 `in` 的 containment 规则分析 `lhs in rhs`
   - 再按逻辑非解释最终结果
   - 但对外仍把结果、source range、diagnostic owner 与 `rootOwnsOutcome` 绑定在原始 `not in` 根节点上
4. 不构造 synthetic AST 节点重新递归求值
   - 这样可以避免 source anchor 漂移、重复诊断和 synthetic subtree 的 owner 混乱

这条方案的关键不是“实现更优雅”，而是它能同时保住三件事：

- 结果语义等价于 `not (lhs in rhs)`
- 子表达式只求值一次，且求值顺序不变
- 当前 frontend 的 diagnostic / publication / compile-gate 根节点语义不被 synthetic desugar 破坏

本计划的首轮 `P1` 到 `P5` 不以 `not in` 落地为阻塞条件，但推荐把它作为 unary/binary 框架稳定后的紧邻后续小任务，而不是继续长期保留为模糊边界。

### 3.10 `ConditionalExpression` 继续留在本计划外

本轮必须明确维持一条边界：

- `UnaryExpression` / `BinaryExpression` 转正
- `ConditionalExpression` 继续保持显式 deferred + compile-only intercept

原因不是它“不重要”，而是它的实现依赖不同：

- `ConditionalExpression` 的 lowering 需要 control-flow / CFG 合同先冻结
- unary / binary 主要是 local expression semantics 问题

若把两者绑在一起，实施节奏会立刻被最慢的 CFG 前置条件拖住。

---

## 4. 分阶段实施计划

### P1. 运算符规范化基础设施

#### 目标

把“源码 lexeme -> `GodotOperator`”与“metadata canonical name -> `GodotOperator`”拆成两个显式入口，并用测试把歧义点冻结下来。

#### 当前状态

- 状态：已完成
- 完成项：
  - [x] `GodotOperator` 已新增 `fromMetadataName(String)` 与 `fromSourceLexeme(String, OperatorArity)`；source 工厂内部继续按 unary / binary 分流，显式消除 `+` / `-` 的语义歧义。
  - [x] `ExtensionBuiltinClass.ClassOperator.operator()` 已改为单点委托 `GodotOperator.fromMetadataName(...)`，避免 metadata canonical operator 解析在多个位置重复维护。
  - [x] source alias 首批支持集合已落地：binary `and` / `&&`、`or` / `||`、`+`、`-`、`*`、`/`、`%`、`**`、`<<`、`>>`、`&`、`|`、`^`、`==`、`!=`、`<`、`<=`、`>`、`>=`、`in`；unary `not` / `!`、`+`、`-`、`~`。
  - [x] fail-closed 行为已冻结并由测试锚定：metadata 工厂拒绝 source-only alias，source 工厂拒绝 `not in`、错误 arity 组合与未知 lexeme。
- 测试产出：
  - `src/test/java/dev/superice/gdcc/enums/GodotOperatorTest.java`
  - `src/test/java/dev/superice/gdcc/gdextension/ExtensionBuiltinClassTest.java`

#### 实施内容

1. 为 `GodotOperator` 增加工厂函数：
   - `fromMetadataName(String name)`
   - `fromSourceLexeme(String lexeme, OperatorArity arity)` 或等价的 unary / binary 拆分接口
2. 将 `ExtensionBuiltinClass.ClassOperator.operator()` 改为委托 metadata 工厂
3. 明确 source factory 首批支持的源码别名：
   - binary：`and` / `&&` -> `AND`
   - binary：`or` / `||` -> `OR`
   - unary：`not` / `!` -> `NOT`
   - unary：`-` -> `NEGATE`
   - unary：`+` -> `POSITIVE`
   - unary：`~` -> `BIT_NOT`
   - binary：`+` / `-` / `*` / `/` / `%` / `**` / `<<` / `>>` / `&` / `|` / `^` / `==` / `!=` / `<` / `<=` / `>` / `>=` / `in`
4. 规定 fail-closed 行为：
   - metadata 工厂遇到未知 canonical name 直接抛错
   - source 工厂遇到未知或当前计划外 lexeme 直接抛错
   - 语义层再把已知边界转译成 `FAILED` 或 `UNSUPPORTED`

#### 代码落点

- `src/main/java/dev/superice/gdcc/enums/GodotOperator.java`
- `src/main/java/dev/superice/gdcc/gdextension/ExtensionBuiltinClass.java`
- 可能新增：
  - `src/test/java/dev/superice/gdcc/enums/GodotOperatorTest.java`

#### 验收标准

1. metadata 工厂能无损替代当前 `ClassOperator.operator()` 的 `switch`
2. source factory 能正确处理 `&&` / `||` / `!`
3. source factory 在 unary / binary `+` / `-` 上不再有歧义
4. `not in` 不会被误解析成 `IN`
5. backend 现有 metadata 消费路径不受影响

### P2. `UnaryExpression` shared semantic 支持

#### 目标

把 unary 从显式 deferred 集合中转正，建立最小但可用的一元运算 typed contract。

#### 当前状态

- 状态：已完成
- 完成项：
  - [x] `FrontendExpressionSemanticSupport` 已新增 `resolveUnaryExpressionType(...)`，并冻结当前 unary 合同：
    - operand 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 时，根节点只传播 upstream 结果
    - operand 已发布为 `Variant`（无论状态是 `RESOLVED Variant` 还是 `DYNAMIC`）时，根节点统一发布 `DYNAMIC(Variant)`，避免把 runtime-open unary 误收窄成 exact 失败
    - exact 非 `Variant` operand 通过 source operator 归一化后，走 builtin operator metadata 求值；未命中则 root-owned `FAILED`
  - [x] unary metadata owner lookup 已补上 typed container 回退：
    - `Array[T]` 回退到 raw builtin owner `Array`
    - `Dictionary[K, V]` 回退到 raw builtin owner `Dictionary`
    - 这样不会因为 frontend 持有 richer container type text 而错过 Godot 的 raw builtin unary metadata
  - [x] `FrontendExprTypeAnalyzer` 与 `FrontendChainBindingAnalyzer` 已新增 dedicated unary 分发，`UnaryExpression` 不再留在 remaining explicit deferred 集合中
- 测试产出：
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupportTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzerTest.java`
  - 回归验证：
    - `FrontendChainBindingAnalyzerTest`
    - `FrontendTypeCheckAnalyzerTest`
    - `FrontendCompileCheckAnalyzerTest`

#### 实施内容

1. 在 `FrontendExpressionSemanticSupport` 中新增一元表达式解析入口
2. 先解析 operand，并沿用现有 dependency propagation 规则
3. 对于 `RESOLVED` operand：
   - 使用 source factory 归一化 operator
   - 走 exact unary route
4. 对于 `DYNAMIC` operand：
   - 首轮发布 `DYNAMIC(Variant)`
5. 对于 operator / operand 组合不合法：
   - 发布 root-owned `FAILED`
6. 从显式 deferred 分支中移除 `UnaryExpression`

#### 代码落点

- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
- 视复杂度决定是否新增：
  - `FrontendBuiltinOperatorSemanticSupport`
  - 或同类 shared helper

#### 测试与验收

正例至少覆盖：

- `-1`
- `+1`
- `~1`
- `!true` / `not true`

反例至少覆盖：

- `~"hello"` 之类不合法 operand
- 未知 unary source lexeme
- operand 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 时根节点只传播 upstream 状态
- operand 为 `DYNAMIC` 时根节点发布 `DYNAMIC` 而不是重新退化成 `DEFERRED`

### P3. `BinaryExpression` 普通 exact metadata 路径

#### 目标

先补齐不依赖 source-level 特判的 binary 基础能力，把普通算术 / 比较 / 位运算从显式 deferred 中移出。

#### 当前状态

- 状态：已完成
- 完成项：
  - [x] `FrontendExpressionSemanticSupport` 已新增 `resolveBinaryExpressionType(...)`，并冻结普通 binary 合同：
    - left / right operand 按固定顺序解析
    - 任一 child 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 时，根节点只传播 upstream 结果
    - source operator 先做来源感知归一化，再进入 source-level special rule 或 builtin metadata exact route
    - 任一普通 operand 为 `DYNAMIC` 或 exact `Variant` 时，根节点保守发布 `DYNAMIC(Variant)`
    - metadata 未命中的 exact 组合发布 root-owned `FAILED`
  - [x] `FrontendExprTypeAnalyzer` 与 `FrontendChainBindingAnalyzer` 已新增 dedicated binary 分发，`BinaryExpression` 不再留在 remaining explicit deferred 集合中
  - [x] binary metadata lookup 已补上 typed container raw-name 归一化：
    - 左操作数 owner `Array[T] -> Array`
    - 左操作数 owner `Dictionary[K, V] -> Dictionary`
    - 右操作数匹配也统一按 raw builtin 名称参与 metadata 对比
- 测试产出：
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupportTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzerTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendChainBindingAnalyzerTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendTypeCheckAnalyzerTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`

#### 实施内容

1. 在 `FrontendExpressionSemanticSupport` 中新增二元表达式解析入口
2. 按固定顺序解析 left / right operand
3. 传播 child 非稳定状态：
   - `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 直接传播
4. 对于 exact `RESOLVED + RESOLVED`：
   - 归一化 operator
   - 先检查 source-level 特殊规则是否命中
   - 未命中特殊规则时，走普通 metadata route
5. 对于含 `DYNAMIC` 的普通 binary：
   - 首轮保守发布 `DYNAMIC(Variant)`
6. 对于不支持的 exact 组合：
   - 发布 root-owned `FAILED`
7. 从显式 deferred 分支中移除 `BinaryExpression`

#### 代码落点

- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
- 如有必要新增内部 lookup helper，用于：
  - builtin class operator metadata 查找
  - `GdType -> builtin owner` 映射

#### 验收标准

正例至少覆盖：

- `1 + 2`
- `1 - 2`
- `1 * 2`
- `1 == 2`
- `1 < 2`
- `1 & 2`

反例至少覆盖：

- 明确非法的 exact 组合应得到 `FAILED`
- 二元 operator 查找保持左右顺序敏感，不因调换操作数而误判
- 左或右 child 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 时根节点不伪造新的 root-owned 错误
- `DYNAMIC` 参与普通 binary 时根节点保持 `DYNAMIC`

### P4. binary source-level 特殊规则

#### 目标

把不能靠 extension metadata 生搬的两条关键规则落地：`and/or` 与 `Array[T] + Array[T]` 同元素类型保型。

#### 当前状态

- 状态：已完成（`and/or` 与 typed-array preserve 已落地；`not in` 暂按显式 unsupported 边界冻结）
- 完成项：
  - [x] `and` / `or` 及其源码别名 `&&` / `||` 已按 condition contract 处理操作数：
    - 两侧只要求稳定 typed fact
    - 不回退成 strict-bool dialect
    - 根节点固定发布 `RESOLVED(bool)`
  - [x] `Array[T] + Array[T]` 同元素类型保型已落地：
    - 仅在两侧都为 typed array 且 element type 相同、且不为 raw `Variant` array 时命中
    - 命中后保留 `Array[T]`
    - 未命中时回退普通 binary metadata route，避免把 preserve 规则误扩张到 mismatched / untyped array
  - [x] `not in` 当前已从“模糊失败 / deferred”收口为显式 `UNSUPPORTED`：
    - binary 分发层显式识别该源码 lexeme
    - 明确拒绝把它悄悄映射成 `IN`
    - 后续若要转正，仍按文档中推荐的复合语义路线单独实施
- 测试产出：
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupportTest.java`
    - 覆盖 `and/or`、`&&/||`、typed-array preserve、mismatched/untyped array fallback、`not in` unsupported 边界
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzerTest.java`
    - 覆盖 shared semantic 结果发布、root-owned diagnostics 与 `not in` unsupported route
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendTypeCheckAnalyzerTest.java`
    - 覆盖 binary 结果进入 condition contract 后不再被误视为 deferred
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`
    - 覆盖 compile surface 上已稳定 binary 不再被 compile gate 误封口

#### 实施内容

1. `and/or`
   - 按 condition contract 验证左右操作数
   - 两侧只要求稳定 typed fact，不要求 `bool`
   - 根节点固定发布 `RESOLVED(bool)`
2. typed array preserve
   - operator 必须是 `ADD`
   - 左右都必须是 `Array[T]`
   - 两侧 element type 都存在且相同
   - 命中后返回该 typed array 类型
   - 未命中时回退普通 binary route
3. `not in` 复合规则
   - 不进入 `GodotOperator` 源码工厂，不把 `"not in"` 映射成 `IN`
   - 语义上按 `not (lhs in rhs)` 处理
   - 结构上保持原始 `BinaryExpression("not in", ...)` 根节点，不构造 synthetic AST
   - 实现顺序建议放在 `in` 路径稳定之后，作为 P4 的后续小任务；若本轮时间不足，可临时保留显式 `UNSUPPORTED`

#### 代码落点

- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
- 可能需要复用：
  - `ClassRegistry`
  - `GdArrayType`

#### 验收标准

`and/or` 正例至少覆盖：

- `1 and 2`
- `1 && 2`
- `variant_value or 0`
- `variant_value || 0`

`and/or` 反例至少覆盖：

- 左右任一 child 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 时根节点只传播 upstream
- 不允许回退成“两个操作数必须是 bool”的 strict-bool 行为

typed array 保型正例至少覆盖：

- `Array[int] + Array[int] -> Array[int]`
- 左右元素类型相同但来源不同的 typed array 变量

typed array 保型反例至少覆盖：

- `Array[int] + Array[String]` 不得错误保留 `Array[int]`
- typed / untyped `Array` 混加时不得凭空构造 typed result
- 本轮不把 `Dictionary` 或 packed array 误纳入同一条保型规则

`not in` 若纳入后续小任务，至少覆盖：

- 正例：`lhs not in rhs` 的结果类型与 `not (lhs in rhs)` 一致
- 负例：`"not in"` 不得悄悄走 `GodotOperator.IN`
- 传播：`lhs` 或 `rhs` 已有 upstream 非成功状态时，根节点只传播 upstream，不重复发 root-owned 错误
- owner：diagnostic 与 source anchor 仍锚定原始 `not in` 根节点，而不是 synthetic 子节点

### P5. Analyzer 集成、测试矩阵补齐与文档同步

#### 目标

让 unary / binary 的 shared semantic 结果真正穿透到 expression typing、type-check 和 compile gate，并用测试把新合同锚死。

#### 当前状态

- 状态：已完成
- 完成项：
  - [x] `FrontendExprTypeAnalyzerTest` 已冻结 unary / binary 的 analyzer 集成结果：
    - unary / binary 根节点不再以“尚未实现”为由发布 `DEFERRED`
    - root-owned `FAILED` / `UNSUPPORTED` 与 upstream 传播结果继续区分
  - [x] `FrontendTypeCheckAnalyzerTest` 已覆盖 unary / binary 结果作为 stable typed fact 被 condition、initializer、return 消费：
    - binary condition 如 `1 + 2`、`payload and 1`、`payload or 0`
    - unary condition 如 `!true`、`not payload`
    - `not payload` 当前按 unary runtime-open 合同发布 `DYNAMIC(Variant)`，但仍满足 condition contract
  - [x] `FrontendCompileCheckAnalyzerTest` 已覆盖 unary / binary 的 compile-only 集成：
    - `RESOLVED` unary / binary 根节点不再触发 generic compile blocker
    - unary/binary `DYNAMIC` 结果继续被 compile gate 视为可接受的 runtime-open fact
    - `ConditionalExpression` 仍保持 compile-only block，不受本轮影响
  - [x] 长期事实文档已同步：
    - `frontend_chain_binding_expr_type_implementation.md`
    - `frontend_type_check_analyzer_implementation.md`
    - `frontend_compile_check_analyzer_implementation.md`
- 测试产出：
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzerTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendTypeCheckAnalyzerTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`

#### 实施内容

1. 更新 `FrontendExpressionSemanticSupportTest`
   - 不再把 unary / binary 断言为 deferred
2. 补充 / 更新 `FrontendExprTypeAnalyzerTest`
   - 验证 root-owned / upstream-propagated 语义
3. 补充 / 更新 `FrontendTypeCheckAnalyzerTest`
   - 验证 unary / binary 结果已能作为 stable typed fact 被 condition / initializer / return 消费
4. 补充 / 更新 `FrontendCompileCheckAnalyzerTest`
   - 验证 unary / binary 在共享语义已稳定时不会再被 generic compile blocker 命中
   - 同时验证 `ConditionalExpression` 仍继续 compile-only block
5. 文档同步
   - 本计划完成后，将稳定事实归并到 `frontend_chain_binding_expr_type_implementation.md` 与相关事实源文档

#### 验收标准

1. unary / binary 不再因为“节点类型尚未实现”而自动变成 `DEFERRED`
2. `FrontendTypeCheckAnalyzer` 能消费 unary / binary 的 `RESOLVED` / `DYNAMIC` 结果
3. compile-only gate 不再把正常 unary / binary 根节点误判成 blocker
4. `ConditionalExpression` 的现有 compile-only 边界不受本轮影响

---

## 5. 测试设计清单

### 5.1 单元测试

建议新增或补充以下单测：

- `GodotOperatorTest`
  - metadata canonical name 解析
  - source unary lexeme 解析
  - source binary lexeme 解析
  - `&&` / `||` / `!` alias
  - unary / binary `+` / `-` 的 arity 区分
  - `not in` fail-closed
- `FrontendExpressionSemanticSupportTest`
  - unary 正反例
  - binary 正反例
  - `and/or` condition-contract 正反例
  - typed array preserve 正反例
  - `not in` 复合规则正反例（若纳入后续小任务）

### 5.2 analyzer 级测试

建议覆盖以下消费面：

- `FrontendExprTypeAnalyzerTest`
  - `UnaryExpression` / `BinaryExpression` 结果发布
  - root-owned 与 upstream-propagated 区分
- `FrontendTypeCheckAnalyzerTest`
  - `if 1 + 2:`
  - `while some_variant && 1:`
  - `return value_a + value_b`
  - typed property / local initializer 上的 unary / binary
- `FrontendCompileCheckAnalyzerTest`
  - unary / binary resolved route 不再触发 compile blocker
  - `ConditionalExpression` 继续触发 compile blocker

### 5.3 必须锚定的负例

下列负例必须明确写入测试，避免实现只覆盖 happy path：

- 未知源码运算符 lexeme fail-closed
- `not in` 不得悄悄当成 `IN`；若暂未启用复合规则，则必须显式 `UNSUPPORTED`
- 不合法 exact unary / binary 组合发布 `FAILED`
- child 已有 upstream 非成功状态时，根节点不重复伪造 root-owned 诊断
- 不同元素类型的 typed array 相加时，结果绝不能错误保留左侧 typed array 类型

---

## 6. 风险与开放问题

### 6.1 `NOT` 是否需要后续升级为 source-level special rule

本计划已明确 `AND/OR` 必须作为 source-level special rule 落地，但一元 `NOT` 是否也应完全对齐 condition contract，当前不建议与本轮绑死，理由是：

- 当前用户明确要求优先处理的是 `and/or`
- `NOT` 即使未来需要升级为 special rule，也不会改变本轮 operator normalization 与 unary framework 的主体结构

因此建议实施顺序为：

1. 先把 `NOT` 接入 unary 框架与 source alias
2. 用 characterization tests 锁定其当前行为
3. 若 extension metadata 覆盖或语义精度不足，再单独补一条小修正

### 6.2 dynamic 结果精度暂时保守

除 `and/or -> bool` 外，本轮对 `DYNAMIC` operand 的处理建议保持保守：

- unary / binary 普通 route 统一返回 `DYNAMIC(Variant)`

这不是最终精度上限，但它能保证：

- 不错误承诺过窄类型
- 不阻断 type-check 的 stable fact 消费
- 不让本轮范围膨胀成“把所有 Variant-return family 一次性做精确”

### 6.3 `not in` 的真实成本高于表面

`not in` 若要正式支持，至少会牵涉：

- frontend source operator normalization
- semantic result 规则
- diagnostic owner / root anchor 保持
- 与 `in` 共享的 containment typed contract

因此它不应作为“顺手加一个 alias”的附属工作项混入本轮。推荐路径是：

- 不新增 `NOT_IN` 枚举
- 不在 parser 侧提前 desugar
- 在 frontend semantic support 中把它实现为保留原始根节点的复合规则 `not (lhs in rhs)`

### 6.4 Object / Nil comparison 与其他 Godot 特判暂不并入首轮范围

backend 侧已经有 object / nil comparison 的实现细节与特化经验，但 frontend 首轮不建议把所有此类规则一起卷入。更稳妥的边界是：

- 先落 unary / binary 的 shared framework
- 先落 `and/or` 与 typed array preserve 两条确定性最高的 source-level 特判
- 其他 Godot 特判后续按独立小任务接入

这能避免首轮一边补基础框架，一边把太多 corner case 一次性耦合进去。

---

## 7. 最终验收口径

本计划完成时，应满足以下最终口径：

1. `UnaryExpression` / `BinaryExpression` 已不再属于 explicit deferred 集合。
2. `GodotOperator` 已能区分 metadata canonical name 与 source lexeme，且 source factory 具备 unary / binary 语境。
3. `and/or` 已按 condition contract 处理操作数，并稳定产出 `bool` 结果。
4. `Array[T] + Array[T]` 在元素类型相同时保留 typed array 结果类型。
5. `FrontendTypeCheckAnalyzer` 与 `FrontendCompileCheckAnalyzer` 已能消费 unary / binary 的稳定 published fact，不再把它们当成“尚未实现的根节点”。
6. `ConditionalExpression` 仍保持现有 compile-only intercept，不被本计划误放行。
7. 测试覆盖 happy path 与 negative path，并明确锚定 source contract，而不是仅验证“代码能跑通”。
