# Frontend typed object / `null` equality 实施计划

> 创建时间：2026-06-23
>
> 本文档记录 GitHub issue #44 的调研结论、职责边界、分阶段实施步骤与验收细则。
> 问题现状是：frontend 在 lowering 前错误拒绝 typed object 与 `null` 的 `==` / `!=`，
> 但 backend 已经具备对应的 `NIL_COMPARISON` 路径。
>
> 本 issue-fix 的范围是故意收窄的：只修 typed object 与 `null` 的 equality 语义，
> 不追求 Godot/backend 已支持的 `int == null`、`String == null` 等更宽 nil equality 接受面。

## 1. 范围

本计划只覆盖 source-level binary equality 语义中的 typed object / `null` 放行：

- `GdObjectType == GdNilType`
- `GdNilType == GdObjectType`
- `GdObjectType != GdNilType`
- `GdNilType != GdObjectType`
- `GdNilType == GdNilType`
- `GdNilType != GdNilType`

其中 `GdNilType == GdNilType` / `!=` 只是同一窄规则的伴随 case，用来保持现有 backend
nil compare 行为稳定；它不表示本 issue 要实现通用 “任意类型与 `null` 可比较” 语义。

完成条件是：

- shared frontend binary semantic 把上述组合稳定发布为 `RESOLVED(bool)`
- compile-only gate 不再把这类已稳定表达式误判为 compile blocker
- lowering/backend 能继续消费该事实，并走已有 `NIL_COMPARISON` 路径

本计划不包含：

- 放宽其它比较运算，如 `<`、`>`、`<=`、`>=`
- 修改 ordinary assignment compatibility
- 修改 `ClassRegistry.checkAssignable(...)`
- 把 `Nil -> object` 扩张成通用赋值或参数兼容规则
- 修改 backend `OperatorResolver` / `OperatorInsnGen` 的既有 nil compare 语义
- 在 frontend 扩张到 `int == null`、`String == null`、container 与 `null` 比较等更宽 nil equality
  接受面
- 扩大到所有 object comparison contract 的重构
- 把 object validity 语义改成 “`== null` 等价 `is_instance_valid`”

## 2. 现状结论

### 2.1 当前失败链路

当前 issue #44 的失败链路已经收敛为：

1. `null` 字面量在 `FrontendChainHeadReceiverSupport.resolveLiteralType(...)` 中被稳定发布为
   `GdNilType.NIL`
2. typed inner class / typed object 在 shared resolver 中已经稳定发布为 `GdObjectType`
3. `FrontendExprTypeAnalyzer` 将 binary root 交给
   `FrontendExpressionSemanticSupport.resolveBinaryExpressionType(...)`
4. `resolveBinaryOperatorResultType(...)` 先尝试 source-level special rule
5. 当前 `resolveBinarySpecialReturnType(...)` 只处理：
   - `and/or`
   - typed `Array[T] + Array[T]` preserve
6. `typed object == null` / `!= null` 没有 special rule，于是继续落到 builtin metadata exact lookup
7. `resolveBinaryExactReturnType(...)` 无法为 typed object 与 `Nil` 找到对应 metadata，最终发布
   `FAILED`
8. `FrontendCompileCheckAnalyzer.scanExpressionTypeCompileBlocks()` 会把该 `FAILED` 结果升级成
   compile blocker

根因是 frontend binary semantic 缺少 object/nil equality 的显式语义合同，而不是：

- `null` 字面量类型化错误
- inner class canonical name 注册错误
- compile-check 误实现 source 语义
- backend nil comparison 缺失

### 2.2 当前 backend 已经具备的能力

backend 侧已存在可承载本计划目标的现成路由：

- `OperatorResolver.resolveBinaryPath(...)`：
  - 任一侧是 `GdNilType` 时，仅允许 `==` / `!=`
  - 成功时选择 `OperatorPath.NIL_COMPARISON`
- `OperatorInsnGen.emitNilComparison(...)`：
  - `Nil == Nil`
  - `Nil != Nil`
  - `Nil == Object`
  - `Object == Nil`
  - `!=` 的取反语义

因此本任务不应绕过 frontend 直接改 backend；正确做法是让 frontend 放行已知静态可接受的
object/nil equality，使其能够抵达已有 lowering/backend 合同。

注意：backend 当前 `NIL_COMPARISON` 的接受面比本 issue-fix 更宽，非 object 类型与 `Nil`
配对时也有既有落地语义，例如 `int == null` 会被 backend 接受并落成 false。本计划不把这部分
backend 能力提升为 frontend 本次必须放行的 source-level 语义；frontend 本次只补齐 typed
object / `null` 的缺口。

### 2.3 文档与分层边界结论

现有 frontend 文档已经冻结了几个关键边界：

- `FrontendTypeCheckAnalyzer` 只消费稳定事实，不解释 upstream 为何失败
- concrete-slot compatibility 只能走 shared assignment/boundary helper，不能手写
  `Nil -> object` 特判
- compile-only gate 只拦截 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`
- `RESOLVED` / `DYNAMIC` binary expression 不应再被 generic compile blocker 误封口

所以本 issue 的修复层级必须是：

- binary semantic special rule

而不是：

- type-check widening
- compile-check 放宽
- backend 宽化

## 3. Godot 依据

已确认的 Godot 4.5.2-stable 依据不是 “只有 `Object` / `null` 合法”，而是：

- `GDScriptParser::get_builtin_type(...)` 明确把 `Variant::NIL` 与 `Variant::OBJECT` 排除在
  builtin type name 映射之外：
  - `Variant::NIL`：`null` 是 literal，不是 type
  - `Variant::OBJECT`：`Object` 应作为 class 处理，不是 ordinary builtin type
- `GDScriptAnalyzer::get_operation_type(...)` 通过
  `Variant::get_validated_operator_evaluator(...)` 判断 `(operator, lhs, rhs)` 是否有合法运算符，
  并通过 `Variant::get_operator_return_type(...)` 取得返回类型；analyzer 的 operation typing
  不是硬编码成 “只有 object/null 可比较”
- `Variant::_register_variant_operators()` 注册具体 `(operator, lhs_type, rhs_type)` 组合：
  - `Object == Nil` / `Nil == Object` 与 `Object != Nil` / `Nil != Object` 有专门 evaluator
  - `Nil == Nil` / `Nil != Nil` 有专门 constant evaluator
  - 多个非 object 类型与 `Nil` 的 equality / inequality 也有注册结果，例如 `Int == Nil`
    是 false、`Int != Nil` 是 true

辅助依据仍然成立，但不能当作 equality 合法性的主来源：

- Godot 官方 `Object` 文档只说明 `== null` 不承担 object validity 语义；判断对象是否被释放仍应使用
  `is_instance_valid(...)`
- Godot 上游 `script_language.cpp` 关于 `Variant::evaluate(Variant::OP_EQUAL, ...)` 的注释只说明
  比较语义是 Variant/runtime 驱动的，不能单独推出 “只有 object/null 合法”
- `GDScriptAnalyzer::check_type_compatibility(...)` 中 `null is acceptable in object` 的分支属于
  assignment / compatibility 边界，不是 binary equality 规则本身

本计划据此采用以下边界：

- source-level `==` / `!=` 的 typed object / `null` 比较是 frontend 当前需要补齐的静态缺口
- 但这不是 Godot 全量 equality 语义规格，也不能反向写成 “Godot 只允许 object/null 与 `null` 比较”
- 本 issue 不把 `== null` 解释为 “对象是否仍然存活” 的推荐写法
- 本 issue 不据此扩张到 ordering operator
- 本 issue 不据此扩张为 Godot/backend 全量 nil equality 语义对齐；`int == null` 等非 object
  与 `null` 的比较虽然在 Godot/backend 中有既有语义，但不在本 issue 范围内

参考：

- <https://github.com/godotengine/godot/blob/6ce3de25aa58466e14ef354703ba8d9791a417da/modules/gdscript/gdscript_parser.cpp>
- <https://github.com/godotengine/godot/blob/6ce3de25aa58466e14ef354703ba8d9791a417da/modules/gdscript/gdscript_analyzer.cpp>
- <https://github.com/godotengine/godot/blob/6ce3de25aa58466e14ef354703ba8d9791a417da/core/variant/variant_op.cpp>
- <https://github.com/godotengine/godot-docs/blob/5a9c5b241271a75cb564f37ed2af63e976499585/classes/class_object.rst>
- <https://github.com/godotengine/godot/blob/28db2de289b3eb8dbc193b9fa5fdf012aed12da7/core/object/script_language.cpp>

## 4. 设计决策

### 4.1 修复点固定在 binary semantic special rule

推荐把修复点固定在：

- `FrontendExpressionSemanticSupport.resolveBinarySpecialReturnType(...)`

理由：

- 它是 shared binary semantic 的集中入口
- `resolveBinaryExpressionType(...)` 与 compound assignment 语义都复用该入口
- 现有 `and/or` 与 typed array preserve 已在此表达 source-level special rule
- object/nil equality 同样属于 “不依赖 builtin metadata exact lookup 的 source-level 特例”

不推荐的方案：

- 在 `FrontendCompileCheckAnalyzer` 放宽：
  - 这只会掩盖 `FAILED`，不会建立正确的 typed fact
- 在 `FrontendTypeCheckAnalyzer` 加 `Nil -> object` 特判：
  - 这会把比较语义伪装成赋值兼容，违反现有文档合同
- 在 `resolveBinaryExactReturnType(...)` 兜底：
  - 这条路径的职责是 exact builtin metadata，不应塞进 source-level object/nil 比较特例
- 在 backend 新增绕路：
  - backend 已经支持，问题不在 backend

### 4.2 special rule 的建议形态

建议新增一个窄规则：

- 仅当 `operator` 为 `==` 或 `!=`
- 且满足以下任一组合时，直接返回 `GdBoolType.BOOL`
  - `Nil` / `Nil`
  - `Object` / `Nil`
  - `Nil` / `Object`

建议保持窄边界，不顺手扩面：

- `Object` / `Object` 暂不纳入本 issue 的计划范围
- `Variant` / `DYNAMIC` 继续走 runtime-open route
  - `Variant` / `DYNAMIC` 不属于本次 special rule，即使另一侧是 `Nil` 也不能被提前发布为
    `RESOLVED(bool)`
- mixed numeric promotion 继续保持当前 reject contract
- 非 object 类型与 `Nil` 的 `==` / `!=` 继续保持 frontend 当前行为，即使 backend 已有更宽
  `NIL_COMPARISON` 落点

### 4.3 为什么本次不改用 `variant_is_nil` / `object_is_null`

本节只解释为什么本 issue 继续复用现有 `BinaryOpInsn` + backend `NIL_COMPARISON` 主路径，
不是在承诺跟进 Godot/backend 更宽的 nil comparison 接受面。

仓库当前确实已经定义了两条 low IR 指令：

- `variant_is_nil`
- `object_is_null`

但本次 issue 不推荐改成这两条指令路线，原因有两层。

第一层是语义边界不完全一致：

- `Nil == Object` 在当前 typed route 下，确实可以近似看成 `object_is_null(object)`；
  backend 现有 `NIL_COMPARISON` 也正是把它落成 `obj == NULL`
- 但 `Nil == Variant` 不能降格成 `variant_is_nil(variant)`：
  - 当前 backend 对任一侧为 `Variant` 的 binary compare 优先走 `VARIANT_EVALUATE`
  - 这保留的是 Godot `Variant::evaluate(OP_EQUAL, ...)` 的动态比较语义
  - 该语义比单纯“runtime tag 是否为 `NIL`”更宽
  - 因此 `variant_is_nil` 只覆盖 `Nil == Variant` 的一个严格子集，不能作为等价替换

第二层是实现主路径不匹配：

- frontend ordinary `BinaryExpression` lowering 当前直接产出 `BinaryOpInsn`
- backend 当前已经对 `BinaryOpInsn` 接通：
  - `OBJECT_COMPARISON`
  - `NIL_COMPARISON`
  - `VARIANT_EVALUATE`
- `variant_is_nil` / `object_is_null` 虽然在指令枚举、LIR parser 与文档中存在，但当前没有接入这条
  binary compare 主路径；若改走这两条指令，需要额外新增：
  - frontend binary rewrite 规则
  - `!=` 取反规则
  - `null == null` 的独立处理
  - 对称顺序 `null == x` / `x == null`
  - 对应的 backend codegen 与 focused tests

因此本 issue 的最小且与现有架构一致的实现应继续保持：

- frontend semantic 放行 `typed object` / `null` 的 `==` / `!=`
- lowering 继续产出 `BinaryOpInsn`
- backend 继续复用现有 `NIL_COMPARISON`
- `int == null` / `null == int` / 其它非 object 类型与 `null` 的比较不纳入本 issue

如果未来要把 low IR `variant_is_nil` / `object_is_null` 真正纳入正式路线，应另立任务，
讨论它们与 `BinaryOpInsn` compare family 的职责分工，而不是在本 issue 中顺手切换实现模型。

### 4.4 inner class typed object 的身份规则

如果 object 操作数来自 inner class，计划实现必须继续遵守：

- lexical type lookup 用 `sourceName`
- typed value 的稳定身份用 `canonicalName`
- comparison contract 只依赖 published `GdType` 家族，不依赖 source-facing 名字文本

也就是说，本 issue 的对象侧判断应基于：

- `publishedType instanceof GdObjectType`

而不是基于类名字符串比较或 source token 特判。

## 5. 分步骤实施

### Phase 0：实施前确认

- [x] 重新读取 `AGENTS.md`，确认并行子代理、文档先行、targeted test 与工具要求
- [x] 使用 MCP `list_directory_tree` 列出 `doc` 与 `doc/module_impl`
- [x] 完成并关闭文档、代码、测试调研子代理
- [x] 重新确认 issue #44 的当前描述与验收期望未变更

### Phase 1：前端 binary semantic 特例落地

状态：已完成（2026-06-23）。实现落点保持在
`FrontendExpressionSemanticSupport.resolveBinarySpecialReturnType(...)`，新增的私有 helper 只识别
`Nil/Nil`、`Object/Nil`、`Nil/Object` 的 `==` / `!=`，没有改动 type-check、compile-check、
assignment compatibility 或 backend 路由。

修改位置：

- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`

实施：

- 在 `resolveBinarySpecialReturnType(...)` 中新增 object/nil equality 特例
- 规则只覆盖 `GodotOperator.EQUAL` 与 `GodotOperator.NOT_EQUAL`
- 当两侧为 `Nil/Nil`、`Object/Nil`、`Nil/Object` 时返回 `GdBoolType.BOOL`
- 不改变以下现有路由：
  - `and/or`
  - typed array preserve
  - runtime-open `Variant` / `DYNAMIC`
  - mixed int/float reject
  - exact builtin metadata lookup

代码约束：

- 不新增 type-check/compile-check 层的补丁式分支
- 不新增新的 public abstraction
- 不把规则写成泛化 “任意 type 与 nil 都可比较”
- 不为了对齐 backend 更宽接受面而顺手放行 primitive / string-like / container 与 `null`

### Phase 2：前端语义 focused tests

状态：已完成（2026-06-23）。新增 `FrontendExpressionSemanticSupportTest`
覆盖 typed inner object / `null` 双向 `==` / `!=`、`null` / `null`、runtime-open
`Variant` / `DYNAMIC` 回归，以及 object/nil ordering 负例。

优先修改测试：

- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupportTest.java`

建议新增覆盖：

- typed inner object `!= null` -> `RESOLVED(bool)`
- typed inner object `== null` -> `RESOLVED(bool)`
- `null != typed inner object` -> `RESOLVED(bool)`
- `null == typed inner object` -> `RESOLVED(bool)`
- `null == null` -> `RESOLVED(bool)`
- `null != null` -> `RESOLVED(bool)`
- `Variant == null` -> `DYNAMIC(Variant)`
- `null == Variant` -> `DYNAMIC(Variant)`
- `DYNAMIC == null` -> `DYNAMIC(Variant)`
- `null == DYNAMIC` -> `DYNAMIC(Variant)`
- 非 object 类型与 `null` 的 `==` / `!=` 保持 frontend 现状，不因本 issue 被新增放行
- `typed inner object < null` 继续 `FAILED`
- `null < typed inner object` 继续 `FAILED`

测试形态建议：

- 优先复用现有 binary expression test 方法风格
- object 侧样例应包含 typed inner class，避免只测顶层 `Object`
- 断言里同时覆盖：
  - `status == RESOLVED`
  - `publishedType == bool`
  - runtime-open 回归用例的 `status == DYNAMIC`
  - runtime-open 回归用例的 `publishedType == Variant`
  - 负例 detail reason 仍保持 “operator not defined”

### Phase 3：compile-check 回归测试

状态：已完成（2026-06-23）。新增 `FrontendCompileCheckAnalyzerTest` 回归，覆盖
`return point.next != null` 与 `while current != null` 不再产生
`sema.expression_resolution` / `sema.compile_check`；同时补充
`point < null` 反例，锚定 object/nil 的放行范围只限 `==` / `!=`，且 shared
`sema.expression_resolution` 已解释失败时 compile gate 不重复追加 `sema.compile_check`。

修改测试：

- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`

建议新增覆盖：

- 包含 `while current != null`
- 包含 `return point.next != null`
- shared semantic 发布 `RESOLVED(bool)` 后，compile-check 不产生
  `sema.compile_check`
- 不因同一 anchor 产生重复 diagnostic
- `typed object < null` 仍保留 shared expression-resolution 失败，不被 compile-check
  误当成 equality 放行面或重复报告

这一步的目标不是新增 compile-check 逻辑，而是证明：

- Phase 1 修复后，现有 compile gate 会自然放行这类 binary fact

### Phase 4：lowering / backend 路由验证

状态：已完成（2026-06-23）。新增 lowering 回归确认 typed object / `null` equality 继续降为
`BinaryOpInsn`，并新增 backend 反向顺序 `Object == Nil` / `Object != Nil` codegen 覆盖。

建议至少覆盖两层：

- frontend lowering focused test
- backend operator codegen focused test

优先测试文件：

- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`
- `src/test/java/gd/script/gdcc/backend/c/gen/COperatorInsnGenTest.java`

建议覆盖：

- `while current != null` 最终能进入 lowering-ready 路径
- 生成出的 LIR 中存在 `BinaryOpInsn`，operator 为 `EQUAL` 或 `NOT_EQUAL`
- backend 对 `Object != Nil`、`Nil != Object` 的 C codegen 继续走 nil specialization
- 现有 `Nil == Object`、`Nil == Nil` 行为不回退
- backend 已支持的非 object / `Nil` 比较行为只作为既有 backend 事实保留，不要求 frontend
  本次新增对应放行

说明：

- 当前本地没有直接看到专门断言 `NIL_COMPARISON` path name 的前端测试锚点
- 若测试基础设施更适合从 C 输出断言 specialization 结果，可继续沿用 backend 现有风格

### Phase 5：文档同步

状态：已完成（2026-06-23）。已同步
`doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md`；compile-check 与
type-check 文档经复核仍只描述消费已发布稳定事实的现有合同，无需新增逻辑说明。

本计划落地后，至少应同步复核以下事实源：

- `doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md`
- `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`

同步要求：

- binary special rule 列表中加入 object/nil equality
- 明确其职责属于 binary semantic，而不是 typed boundary widening
- compile-check 文档不需要描述新逻辑，只需确保其“`RESOLVED` 不拦截”的事实与实现一致

### Phase 6：端到端 test_suite 验收

状态：已完成（2026-06-23）。本轮复核发现前端语义、compile-check、lowering 与 backend
focused tests 已覆盖计划边界；剩余缺口是 `test_suite` 中缺少直接锚定 object/nil equality
运行时结果的资源用例。已新增 smoke 资源对：

- `src/test/test_suite/unit_test/script/smoke/object_nil_equality.gd`
- `src/test/test_suite/unit_test/validation/smoke/object_nil_equality.gd`

覆盖内容：

- typed inner object 为 `null` 时，`object == null` 与 `null == object` 为 true
- typed inner object 非 `null` 时，`object != null` 与 `null != object` 为 true
- typed property `next: Point = null` 的双向 equality 为 true
- `null == null` 为 true
- 对应 false 分支不会误计入 mask，包括 `present == null`、`null == present`、
  `missing != null`、`null != missing`、`null != null`

同步项：

- 已更新 `GdScriptUnitTestCompileRunnerTest.EXPECTED_SCRIPT_PATHS`，保持资源发现列表与新增
  fixture 对齐

## 6. 验收细则

### 6.1 语义层验收

- `typed object == null` 发布为 `RESOLVED(bool)`
- `typed object != null` 发布为 `RESOLVED(bool)`
- `null == typed object` 发布为 `RESOLVED(bool)`
- `null != typed object` 发布为 `RESOLVED(bool)`
- `null == null` 发布为 `RESOLVED(bool)`
- `null != null` 发布为 `RESOLVED(bool)`
- operand order 不影响结果类别
- `typed object < null`、`null < typed object` 仍失败
- `int == null`、`null == int` 以及其它非 object 类型与 `null` 的 equality 不属于本 issue
  验收目标，应保持 frontend 当前行为
- `Variant == null`、`null == Variant`、`DYNAMIC == null`、`null == DYNAMIC` 继续发布为
  `DYNAMIC(Variant)`，不被 object/nil special rule 抢先发布为 `RESOLVED(bool)`

### 6.2 compile-check / lowering 验收

- `FrontendCompileCheckAnalyzer` 不再把上述 equality expression 升级为
  `sema.compile_check`
- `while current != null` 能继续进入 lowering-ready 路径
- 不出现同一表达式根因的重复 diagnostic
- compile-only gate 对其它既有 unsupported/deferred expression 不发生漂移

### 6.3 backend 验收

- `Object == Nil`、`Nil == Object` 继续走 null compare specialization
- `Object != Nil`、`Nil != Object` 继续走 null compare specialization + 取反
- `Nil == Nil`、`Nil != Nil` 行为保持不变
- 非 equality 的 nil/object compare 继续 fail-fast

### 6.4 非目标回归验收

- `ClassRegistry.checkAssignable(...)` 行为不变
- ordinary typed boundary 文档与实现不新增 “比较语义式的 Nil widening”
- `FrontendTypeCheckAnalyzer` 未新增 object/nil 比较专用分支
- `FrontendCompileCheckAnalyzer` 未新增掩盖式放宽逻辑
- 非 object 类型与 `null` 的 equality 未被本 issue 顺手放行

## 7. 建议的 targeted tests

建议按项目约定使用：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendExpressionSemanticSupportTest
script/run-gradle-targeted-tests.sh --tests FrontendCompileCheckAnalyzerTest
script/run-gradle-targeted-tests.sh --tests FrontendLoweringBodyInsnPassTest
script/run-gradle-targeted-tests.sh --tests COperatorInsnGenTest
script/run-gradle-targeted-tests.sh --tests GdScriptUnitTestCompileRunnerTest
```

如果 lowering 侧最终落点不在 `FrontendLoweringBodyInsnPassTest`，则将第三条替换为实际承载
`BinaryOpInsn` / compile-lowering continuity 的 focused test 类。

本次实际执行：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendExpressionSemanticSupportTest,FrontendCompileCheckAnalyzerTest,FrontendLoweringBodyInsnPassTest,COperatorInsnGenTest
```

结果：通过。

本轮再次验收执行：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendExpressionSemanticSupportTest,FrontendCompileCheckAnalyzerTest,FrontendLoweringBodyInsnPassTest,COperatorInsnGenTest,GdScriptUnitTestCompileRunnerTest
```

结果：通过。

## 8. 风险点

- **规则放宽过宽**：若把规则写成“任意类型与 `Nil` 的 `==/!=` 都通过”，会错误改变 primitive /
  string-like / container 的现有语义边界。
- **把比较语义混入 typed boundary helper**：这会污染 assignment compatibility 的职责边界。
- **顺手扩张 object/object 比较合同**：backend 有 `OBJECT_COMPARISON` 不代表本 issue 必须同时重写
  frontend object/object 路由；若一起做，diff 和回归面会明显放大。
- **依赖 source-facing 名字判断 object 类型**：inner class 场景下容易把 `sourceName` / `canonicalName`
  混用。
- **compile-check 误修**：若通过 compile gate 放宽去“掩盖”上游 `FAILED`，会让 lowering 接收到错误的
  typed fact。
- **Godot 语义误读**：`object == null` 合法，不等于推荐用它判断对象是否被释放；实现与文档都不应暗示
  validity semantics。
- **backend 能力误读**：backend 已有 `NIL_COMPARISON` 接受面比本 issue 更宽，不代表 frontend
  本次也要放行 `int == null` 等非 object / `null` equality；本文档只描述 object/nil issue-fix。
- **runtime-open 路由被抢跑**：`resolveBinarySpecialReturnType(...)` 位于 runtime-open operand 检查之前，
  若 special rule 写成“遇到 `Nil` equality 就返回 `bool`”，会把 `Variant == null`、`DYNAMIC == null`
  等本应保持 `DYNAMIC(Variant)` 的表达式提前收窄。

## 9. 完成标准

本任务完成时应同时满足：

- `FrontendExpressionSemanticSupport.resolveBinarySpecialReturnType(...)` 已新增窄范围
  object/nil equality rule
- typed inner object 与 `null` 的 `==` / `!=` 在 frontend 语义测试中稳定发布 `bool`
- compile-check focused tests 证明该表达式不再被误判为 compile blocker
- lowering/backend focused tests 证明已有 nil specialization 路径未回退
- 非 equality compare 仍保持失败
- 文档事实源已与最终实现对齐
