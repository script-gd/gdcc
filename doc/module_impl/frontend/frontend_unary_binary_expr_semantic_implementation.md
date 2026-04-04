# Frontend Unary / Binary Expression 语义实现说明

> 本文档作为 frontend `UnaryExpression` / `BinaryExpression` 语义分析的长期事实源，定义当前支持面、owner 边界、运算符规范化合同、稳定 typed contract、显式边界与后续工程需要继续遵守的约束。本文档替代原 `frontend_unary_binary_expr_semantic_plan.md`，不再保留阶段拆分、进度记录或已完成任务流水账。

## 文档状态

- 状态：事实源维护中（unary/binary shared semantic、analyzer 集成、type-check / compile-gate 消费路径已落地）
- 更新时间：2026-03-20
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
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/backend/operator_insn_implementation.md`
  - `doc/gdcc_type_system.md`
- 参考实现 / 事实依据：
  - Godot `modules/gdscript/gdscript_analyzer.cpp`
    - `and/or` 在 analyzer 中走 source-level 特判，结果固定为 `bool`
    - `Array + Array` 在元素类型已知且相同时保留 typed array 结果类型
  - `E:/Projects/gdparser/vendor/tree-sitter-gdscript/grammar.js`
    - binary grammar 同时接受 `and` / `&&`、`or` / `||`、`in` / `not in`
    - unary grammar 同时接受 `not` / `!`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/lowering/CstToAstMapper.java`
    - `UnaryExpression.operator()` / `BinaryExpression.operator()` 保存源码字面量，而不是 extension metadata canonical operator name
- 明确非目标：
  - 不在这里转正 `ConditionalExpression`
  - 不在这里补 `ConditionalExpression` 的 lowering / CFG / compile-only 放行
  - 不在这里扩张 `Dictionary`、packed array family 或其他 container 的保型规则
  - 不在这里引入 numeric promotion、`StringName` / `String` 等更宽隐式转换
  - 不在这里把 compile-only blocker 反向回灌到 shared semantic 路径
  - 不在这里把 `not in` 提升为已支持语义；当前版本仍保持显式 unsupported 边界

---

## 1. 当前集成位置

### 1.1 当前角色分工

frontend 当前将 unary / binary 语义冻结在 shared expression helper，而不是重新引入新的 analyzer：

- `FrontendExpressionSemanticSupport`
  - 负责 unary / binary 的局部纯语义求值
  - 不发布 side table
  - 不发 diagnostic
- `FrontendExprTypeAnalyzer`
  - 负责把 unary / binary 结果发布到 `expressionTypes()`
  - 负责 root-owned `FAILED` / `UNSUPPORTED` 的 expr-owned diagnostic
- `FrontendChainBindingAnalyzer`
  - 允许在链头 / 嵌套表达式场景桥接 unary / binary 的局部结果
  - 不接管 `expressionTypes()` 的 owner 身份
- `FrontendTypeCheckAnalyzer`
  - 只消费已发布 typed fact
  - 不复制 operator 语义
- `FrontendCompileCheckAnalyzer`
  - 只消费已发布状态
  - 不再把 unary / binary 当作“表达式家族尚未实现”的 compile blocker

### 1.2 当前支持面

当前 frontend body phase 中：

- `UnaryExpression` 已属于正式支持面
- `BinaryExpression` 已属于正式支持面
- `ConditionalExpression` 继续保留在 remaining explicit-deferred / compile-only intercept 边界

这条边界意味着：

- unary / binary 的 typed fact 可以继续向 type-check、property initializer、return gate 与 compile gate 传递
- compile-only block 的剩余重心不再是 unary / binary，而是 `ConditionalExpression` 等明确尚未接通 lowering 的表达式家族

---

## 2. 运算符规范化合同

### 2.1 双入口工厂

`GodotOperator` 当前必须通过两个来源感知入口解释运算符：

1. `fromMetadataName(String name)`
   - 只服务 extension metadata canonical operator name
   - 例如 `and`、`or`、`not`、`unary-`、`unary+`
2. `fromSourceLexeme(String lexeme, OperatorArity arity)`
   - 只服务源码字面量
   - 必须显式带 unary / binary 语境

之所以不能把源码入口做成单参数版本，是因为：

- `-` 既可能是 unary `NEGATE`，也可能是 binary `SUBTRACT`
- `+` 既可能是 unary `POSITIVE`，也可能是 binary `ADD`

没有 arity 的 source factory 不是可靠工厂，只会把歧义推给调用方。

### 2.2 当前源码别名集合

当前 source factory 已冻结的别名集合包括：

- unary
  - `not` / `!` -> `NOT`
  - `+` -> `POSITIVE`
  - `-` -> `NEGATE`
  - `~` -> `BIT_NOT`
- binary
  - `and` / `&&` -> `AND`
  - `or` / `||` -> `OR`
  - `+`、`-`、`*`、`/`、`%`、`**`
  - `<<`、`>>`、`&`、`|`、`^`
  - `==`、`!=`、`<`、`<=`、`>`、`>=`
  - `in`

### 2.3 fail-closed 边界

当前必须继续保持 fail-closed：

- metadata 工厂拒绝 source-only alias，如 `&&` / `||` / `!`
- source 工厂拒绝未知 lexeme 与错误 arity 组合
- source 工厂当前必须拒绝 `not in`

`not in` 不得被静默映射成 `IN`。那样会直接丢掉源码层的逻辑取反语义，并把“独立的语义复合规则”错误降级成“普通 alias”。

---

## 3. Unary 合同

### 3.1 当前求值顺序与状态传播

`resolveUnaryExpressionType(...)` 当前冻结为：

1. 先解析 operand
2. 若 operand 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`
   - 根节点只传播 upstream 结果
   - `rootOwnsOutcome = false`
3. 若 operand 已发布为 `Variant`
   - 无论来源是 exact `RESOLVED(Variant)` 还是 `DYNAMIC(Variant)`
   - 根节点统一发布 `DYNAMIC(Variant)`
4. 若 operand 为 exact non-`Variant`
   - 先通过 source factory 归一化 operator
   - 再走 builtin operator metadata exact lookup
   - 命中则 `RESOLVED(returnType)`
   - 未命中则 root-owned `FAILED`

### 3.2 typed container owner 归一化

unary metadata owner 查找当前补上了 typed container 回退：

- `Array[T] -> Array`
- `Dictionary[K, V] -> Dictionary`

这样 frontend 不会因为自己持有 richer typed container 信息，就错过 Godot extension metadata 中使用的 raw builtin owner。

### 3.3 当前精度边界

unary 当前有意保持保守精度：

- exact `Variant` operand 不会被硬判成 exact failure
- `DYNAMIC` operand 也不会被伪装成 deferred
- 当前不尝试从 operator family 反推出更窄的 dynamic return type

这条边界不是最终精度上限，但它能保证：

- 不错误承诺过窄类型
- 不阻断 downstream 对 stable fact 的消费
- 不把当前工作膨胀成“整套 Variant-return precision”任务

---

## 4. Binary 合同

### 4.1 当前求值顺序与普通路由

`resolveBinaryExpressionType(...)` 当前冻结为：

1. 固定先算 left，再算 right
2. 任一 child 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`
   - 根节点只传播 upstream 结果
   - `rootOwnsOutcome = false`
3. 对 exact / stable child：
   - 先处理 source-level special rule
   - 再处理普通 builtin metadata exact lookup
4. 普通路由中，任一 operand 为 `DYNAMIC` 或 exact `Variant`
   - 根节点保守发布 `DYNAMIC(Variant)`
5. 普通 exact metadata 未命中
   - 根节点发布 `FAILED`

普通 binary route 必须保持顺序敏感：

- 只按 `leftType + operator + rightType` 查找
- 不做自动 swap
- 不做“双向试探谁能过就算谁”

### 4.2 当前 source-level special rule

binary 当前有两类 source-level special rule，不得强行回退到 extension metadata：

1. `and/or`
   - 同时覆盖源码别名 `&&/||`
   - 操作数合同与 condition expression 一致
   - 操作数只要求 stable typed fact，不要求 exact `bool`
   - 结果固定为 `RESOLVED(bool)`
2. typed array preserve
   - 只锚定 `Array[T] + Array[T]`
   - 仅在两侧都为 typed array、元素类型相同、且元素类型不是 exact `Variant` 时命中
   - 命中后保留 `Array[T]`
   - 其他情况必须回退普通 binary route，不能凭空扩张更多保型规则

### 4.3 typed container 元数据匹配

binary metadata 路由当前统一按 raw builtin 名称参与匹配：

- 左操作数 owner `Array[T] -> Array`
- 左操作数 owner `Dictionary[K, V] -> Dictionary`
- 右操作数匹配也使用 raw builtin type name

这保证 metadata lookup 与 extension API 的 builtin owner 协议一致，不会因为 frontend 侧 richer generic type text 失配。

### 4.4 `not in` 当前边界

`not in` 当前必须继续视为显式 `UNSUPPORTED`：

- frontend 已识别它是合法源码运算符
- frontend 当前不支持它的语义分析
- frontend 不得把它悄悄降成 `in`
- frontend 不得仅因为理论结果为 `bool` 就发布 `RESOLVED(bool)`

当前推荐的正式支持路径仍是：

- 保持原始 `BinaryExpression("not in", ...)` 根节点
- 在 binary 分发层实现复合规则 `not (lhs in rhs)`
- 不引入 synthetic AST
- 不引入新的 `NOT_IN` metadata/operator 枚举

当前版本尚未落地这条复合规则，因此 `not in` 仍是明确 feature boundary，而不是模糊 TODO。

---

## 5. Downstream 消费合同

### 5.1 `FrontendExprTypeAnalyzer`

当前 unary / binary 已经不再因为“表达式家族尚未实现”被发布为 `DEFERRED`。

expr analyzer 当前需要继续保持：

- 区分 root-owned 非成功结果与 upstream 传播结果
- 只为 root-owned `FAILED` / `UNSUPPORTED` 补 expr-owned diagnostic
- 对 propagated 结果复用 upstream owner

### 5.2 `FrontendTypeCheckAnalyzer`

type-check 当前直接消费 unary / binary 的 stable fact：

- `RESOLVED`
- `DYNAMIC`

这条合同当前已经覆盖：

- unary condition，如 `!true`
- binary condition，如 `1 + 2`、`payload and 1`
- dynamic unary condition，如 `not payload`

type-check 继续遵守 Godot-compatible condition contract：

- condition 只要求 stable typed fact
- 不把 source-level condition 回退成 undocumented strict-bool dialect

### 5.3 `FrontendCompileCheckAnalyzer`

compile gate 当前只把以下状态视为 blocker：

- `BLOCKED`
- `DEFERRED`
- `FAILED`
- `UNSUPPORTED`

因此：

- `RESOLVED` unary / binary 不再命中 generic compile blocker
- `DYNAMIC` unary / binary 同样不再命中 generic compile blocker
- `not in` 仍会因为 upstream 发布 `UNSUPPORTED` 而被 compile gate 阻断
- `ConditionalExpression` 继续依赖显式 compile-only block，而不是借 unary/binary 转正被顺带放行

---

## 6. 测试锚点

当前实现的测试锚点至少包括：

- `GodotOperatorTest`
  - metadata canonical operator 解析
  - source lexeme 解析
  - unary / binary `+` / `-` arity 区分
  - `&&` / `||` / `!` alias
  - `not in` fail-closed
- `ExtensionBuiltinClassTest`
  - metadata operator 解析委托 `GodotOperator.fromMetadataName(...)`
- `FrontendExpressionSemanticSupportTest`
  - unary 正反例
  - binary 正反例
  - `and/or` 合同
  - typed array preserve 正反例
  - `not in` unsupported 边界
- `FrontendExprTypeAnalyzerTest`
  - unary / binary 结果发布
  - root-owned 与 upstream-propagated 区分
- `FrontendTypeCheckAnalyzerTest`
  - unary / binary 稳定结果进入 condition / initializer / return 消费面
- `FrontendCompileCheckAnalyzerTest`
  - unary / binary resolved / dynamic route 不再触发 compile blocker
  - `ConditionalExpression` 继续被 compile-only block

后续若扩张 unary / binary 行为，测试必须继续同时覆盖：

- happy path
- root-owned negative path
- upstream propagation path
- 与 `frontend_rules.md`、type-check 合同、compile-check 合同的对齐

---

## 7. 风险与后续工程约束

### 7.1 `NOT` 仍可能需要后续精度升级

当前 `NOT` 已经进入 unary 框架并具备 source alias，但它是否要完全升级为与 condition contract 对齐的 source-level special rule，当前尚未收口为独立任务。

后续若继续提升它的精度，应保持：

- 不修改现有 operator normalization 边界
- 不破坏 `DYNAMIC(Variant)` 的保守合同
- 用 characterization tests 先冻结当前行为，再讨论收窄

### 7.2 dynamic 精度仍然保守

除 `and/or -> bool` 外，当前 unary / binary 对 runtime-open operand 统一保守发布 `DYNAMIC(Variant)`。

这条边界的价值在于：

- 不错误承诺过窄类型
- 不阻断 downstream 消费 stable fact
- 不把当前事实源重新拉回“为实现阶段服务的计划文档”

### 7.3 `not in` 的真实成本高于 alias

`not in` 后续若要转正，至少同时涉及：

- source operator normalization 边界
- `in` 共享 containment contract
- root anchor / diagnostic owner 维持
- compile gate 与 downstream 消费者的一致性

因此它不能被当成“多映射一个别名”的小修。当前文档明确保留这条工程反思，目的是防止后续实现又把它错误简化回 alias。

### 7.4 文档维护约束

后续若继续扩张 unary / binary 支持面，应优先更新：

- 本文档中的当前合同
- `frontend_chain_binding_expr_type_implementation.md`
- `frontend_type_check_analyzer_implementation.md`
- `frontend_compile_check_analyzer_implementation.md`

不要重新恢复阶段拆分、完成清单或执行流水账；阶段过程应留在提交历史，不应回流到 implementation 文档。
