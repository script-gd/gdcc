# Frontend Object Identity Equality 实施计划

> 本文档是 issue #58（frontend: object identity equality between two object types is rejected）的实施计划与验收细则。按 `frontend_unary_binary_expr_semantic_implementation.md` §7.6 的文档维护约束，阶段拆分与执行清单独立成文，不回流 implementation 文档；本计划落地后，其冻结合同应被吸收进 `frontend_unary_binary_expr_semantic_implementation.md`（§4.2 / §4.5 / §4.6 / §6 / §7.3 / §7.4），本文档随后转为历史记录。

## 文档状态

- 状态：计划待实施（语义合同已在 §2 冻结，等待确认后进入实现）
- 更新时间：2026-08-19
- 关联 issue：`script-gd/gdcc#58`
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupportTest.java`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`
  - `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`
  - `src/test/java/gd/script/gdcc/backend/c/gen/COperatorInsnGenTest.java`
  - `src/test/test_suite/unit_test/script/smoke/**`
  - `src/test/test_suite/unit_test/validation/smoke/**`
  - `src/test/java/gd/script/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md`（§4.1–§4.2、§4.5–§4.6、§6、§7.3–§7.4）
  - `doc/module_impl/backend/operator_insn_implementation.md`（§3.4–§3.6）
  - `doc/gdcc_type_system.md`
- 参考实现 / 事实依据：
  - Godot `core/variant/variant_op.cpp`：Variant 运行时为 `OBJECT/OBJECT` 注册 `OP_EQUAL` / `OP_NOT_EQUAL` evaluator，语义为 object identity 比较，不要求两侧类相关
  - Godot `modules/gdscript/gdscript_analyzer.cpp`：静态类型 object 之间的 `==` / `!=` 在 analyzer 层即被接受并归约为 `bool`
  - 本仓库 backend 已实现任意 object/object pair（含 GDCC/engine 混合）的 equality-normalized raw 比较，见 `operator_insn_implementation.md` §3.5 与 `COperatorInsnGenTest.objectEqualUsesNormalizedRawComparison` / `mixedGdccEngineObjectEqualUsesPerSideNormalization`
- 明确非目标：
  - 不在这里放宽 object 之间的 ordering 运算符（`<`、`>`、`<=`、`>=`），它们必须继续 `FAILED`
  - 不在这里把 object identity equality 扩张成 assignment compatibility、parameter compatibility 或 slot compatibility 规则
  - 不在这里改动 object/nil equality 既有窄规则与 `NIL_COMPARISON` 路由
  - 不在这里收窄 exact `Variant` / `DYNAMIC` operand 的 runtime-open `DYNAMIC(Variant)` 路由
  - 不在这里把 binary semantic 耦合到 `ClassRegistry.checkAssignable(...)` 或任何继承链检查
  - 不在这里新增 backend codegen 路线；lowering 继续产出 `BinaryOpInsn`，backend 继续复用既有 object 比较特化
  - 不在这里处理 `Signal.get_object()` binding 问题（其返回 `Object` 是正确行为）

---

## 1. 背景与问题定位

### 1.1 现象

frontend binary semantic 当前已接受 object 与 `null` 的 `==` / `!=`，但拒绝两个静态类型 object 之间的普通 identity 比较：

```gdscript
func same_node(left: Node, right: Node) -> bool:
    return left == right      # FAILED: Binary operator '==' is not defined for operand types 'Node' and 'Node'

func object_vs_node(obj: Object, node: Node) -> bool:
    return obj == node        # FAILED（同上，'Object' 与 'Node'）
```

root-owned `FAILED` 进入 `sema.expression_resolution`，compile-mode 随后把它当作 not lowering-ready。

### 1.2 原因链（已核实）

1. `FrontendExpressionSemanticSupport.resolveBinaryOperatorResultType(...)`（`FrontendExpressionSemanticSupport.java:635`）先走 `resolveBinarySpecialReturnType(...)`（`:1746`），再走 runtime-open 检查，最后走 `resolveBinaryExactReturnType(...)`（`:1787`）metadata 精确查找。
2. 特殊规则当前只有三类：`and/or -> bool`（`:1751`）、object/nil equality（`:1754`，`isObjectNilEqualityPair` 只覆盖 `Nil/Nil`、`Object/Nil`、`Nil/Object`，`:1768`）、typed array preserve（`:1758`）。`Object/Object` 不在覆盖面内。
3. metadata 精确路由对 object 左操作数必然失败：`findOperatorOwnerClass(...)`（`:1814`）委托 `ClassRegistry.findBuiltinClass(...)`（`ClassRegistry.java:184`），engine class（`Object`/`Node`）不在 builtin namespace，返回 `null`；且 matcher 要求右操作数类型名精确字符串匹配，不做 promote、swap 或 `checkAssignable` widening。
4. 因此根节点在 `:692` 发布 `FAILED`，经 `FrontendBodyOwnerProcedures.publishExpressionType(...)` / `reportExpressionDiagnostic(...)`（`FrontendBodyOwnerProcedures.java:1028`、`:1292`）报 `sema.expression_resolution`；`FrontendCompileCheckAnalyzer.isCompileBlocking(...)`（`FrontendCompileCheckAnalyzer.java:249`）把 `FAILED` 列入 compile blocker。

注：issue 中提到的 `FrontendExprTypeAnalyzer` 在当前代码库已不存在，binary 表达式 typing 实际收口在 `FrontendBodyOwnerProcedures` 内部 `BodyExpressionResolver`；`frontend_unary_binary_expr_semantic_implementation.md` §1.1 / §6 的同名引用为陈旧引用，本计划 Step 7 顺带修正。

### 1.3 下游已就绪事实

- backend `operator_insn_implementation.md` §3.5 已完整规定并实现两侧均为 Object 类型时的 `==` / `!=` codegen：每侧 materialize 为 equality-normalized raw（null/freed -> `NULL`，live engine -> `(GDExtensionObjectPtr)ptr`，live GDCC -> fat-ptr live object），结果 `bool`；禁止 fat-struct 直接比较、`instance_id` 比较。
- 因此本 issue 的唯一缺口在 frontend semantic 层：被接受的事实到达 lowering 后，既有 `BinaryOpInsn` 主路径与 backend 特化即可完整承接，不需要任何 backend 改动。

---

## 2. 语义合同决策（实施前必须冻结）

### 2.1 冻结合同

新增第四条 binary source-level special rule：**object identity equality**。

- 只锚定 `==`（`GodotOperator.EQUAL`）与 `!=`（`GodotOperator.NOT_EQUAL`）
- 命中条件：两侧 `publishedType` 均为 `GdObjectType`（只允许 `instanceof GdObjectType` 判断，不得依赖 source-facing 类名文本）
- 命中后固定发布 `RESOLVED(bool)`（`GdBoolType.BOOL`）
- 实现入口固定在 `FrontendExpressionSemanticSupport.resolveBinarySpecialReturnType(...)`，与既有三条特殊规则并列

### 2.2 覆盖范围决策：接受任意两个静态已知 object 类型

本计划选择 **identity 语义合同**：同类 pair（`Node == Node`）、继承相关 pair（`Object == Node`、`Node == Object`）与继承无关 pair（如 `Sprite2D == Label`、GDCC class vs engine class）一律接受为 identity 比较，结果 `bool`。

理由：

- Godot Variant 运行时对 `OBJECT/OBJECT` 的 `==` / `!=` 就是 identity 语义，不检查两侧类是否相关；GDScript analyzer 同样接受任意 object/object 比较。
- backend §3.5 已实现任意 object pair（含 GDCC/engine 混合）的 equality 特化，frontend 若额外设限会与已落地的 backend 合同错位。
- issue 明确要求 "no silent reuse of ordinary assignability / implicit-conversion widening as operator promotion"。若用 `checkAssignable(...)` 门控这条规则，恰恰把 binary semantic 耦合进 assignability；identity 合同从定义上就不需要继承关系判断。
- `resolveBinarySpecialReturnType(...)` 当前是不持有 `ClassRegistry` 的纯静态 helper（`:1746`）；identity 合同保持这一形状，不需要为其穿线 registry，改动面最小。

### 2.3 窄规则约束（实施后必须继续成立）

- 只覆盖 `==` / `!=`；object/object 的 ordering 运算符必须继续走普通路由并最终 `FAILED`（`sema.expression_resolution`，detail 含 `not defined for operand types`）
- 不降级两侧均为静态已知类型的 pair 到 `DYNAMIC(Variant)`
- exact `Variant` / `DYNAMIC` operand 继续保持 runtime-open：实践中 DYNAMIC 状态的 operand 一律发布 `GdVariantType`，`instanceof GdObjectType` pair 检查天然排除它们；本规则与既有 object/nil 规则保持同一形状，不额外加 guard
- 不得据此扩张 `ClassRegistry.checkAssignable(...)`、type-check slot compatibility 或 compile gate
- 该规则属于 binary semantic，不是 ordinary typed-boundary widening

### 2.4 对 §7.3 重新论证清单的回答

`frontend_unary_binary_expr_semantic_implementation.md` §7.3 要求扩张 equality 规则前重新论证三点：

1. **是否仍属于 binary semantic**：是。规则只回答 "binary operator 结果类型是什么"，不触碰任何 typed boundary（赋值、参数、slot）的兼容性判断。
2. **是否会误伤 `Variant` / `DYNAMIC` 的 runtime-open 路由**：不会。pair 检查基于 `instanceof GdObjectType`，`GdVariantType` 不是 `GdObjectType`；runtime-open 检查在特殊规则之后继续原样执行。
3. **是否需要同步扩大 lowering、backend 与 smoke test 锚点**：lowering / backend 不需要改动（§1.3）；smoke 锚点需要新增 object/object 端到端资源（Step 6），既有 `object_nil_equality` smoke 资源保持不动并继续绿色。

---

## 3. 分步骤实施

每一步都必须可独立编译、可独立回归、可独立提交。测试命令一律使用 `--no-daemon --info --console=plain`，只跑目标测试类/方法；可复用 `script/run-gradle-targeted-tests.ps1`。

### Step 1：实现 object identity equality 特殊规则

改动 `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`，且只改动这一个 main-source 文件：

1. 在 `resolveBinarySpecialReturnType(...)` 的 object/nil 分支（`:1754-1757`）之后追加并列分支：

```java
if ((operator == GodotOperator.EQUAL || operator == GodotOperator.NOT_EQUAL)
        && isObjectObjectEqualityPair(publishedLeftType, publishedRightType)) {
    return GdBoolType.BOOL;
}
```

2. 在 `isObjectNilEqualityPair(...)`（`:1768-1772`）之后追加 helper，命名与同一文件内既有 `isXxx` pair 判断保持一致：

```java
private static boolean isObjectObjectEqualityPair(@NotNull GdType leftType, @NotNull GdType rightType) {
    return leftType instanceof GdObjectType && rightType instanceof GdObjectType;
}
```

`GdBoolType`、`GdObjectType`、`GodotOperator` 均已在该文件 import，无新增依赖。

自动覆盖说明：`resolveBinaryOperatorResultType(...)` 同时被普通 binary typing（`FrontendExpressionSemanticSupport.java:621`）、compound assignment typing（`FrontendAssignmentSemanticSupport.java:322`）与 body lowering temp typing（`FrontendBodyLoweringSupport.java:522`）复用，本规则对三处自动生效，无需各自改动。

验收：

- `./gradlew classes --no-daemon --info --console=plain` 编译通过
- `./gradlew test --tests FrontendExpressionSemanticSupportTest --no-daemon --info --console=plain` 既有用例全绿（规则不得改变任何既有 case 的结果）

### Step 2：语义层单测

在 `FrontendExpressionSemanticSupportTest` 中，于 `resolveBinaryExpressionTypeAcceptsOnlyNarrowObjectNilEqualitySpecialRule`（`:1059`）旁新增测试方法 `resolveBinaryExpressionTypeAcceptsObjectIdentityEqualitySpecialRule`，复用该文件既有 `analyze(...)` / `createSupport(...)` / `publishedExpressionResolver(...)` 配方（默认 `ClassRegistry(ExtensionApiLoader.loadDefault())` 已含真实 `Object` / `Node` 继承元数据，无需自定义 registry）。

fixture 形态（表达式按语句顺序按下标断言）：

```gdscript
class_name ExpressionSemanticSupportObjectIdentityBinary
extends Node

func ping(left: Node, right: Node, obj: Object, typed_variant: Variant, dynamic_value):
    left == right          # RESOLVED bool（同类）
    left != right          # RESOLVED bool
    obj == obj             # RESOLVED bool（同类 Object）
    obj != left            # RESOLVED bool（继承相关，双向各一）
    left == obj            # RESOLVED bool
    left < right           # FAILED（ordering 继续拒绝）
    left <= obj            # FAILED
    typed_variant == left  # DYNAMIC(Variant)（runtime-open 不被收窄）
    dynamic_value == left  # DYNAMIC(Variant)
```

每个 case 断言 `rootOwnsOutcome()`、对应 `status()`、`publishedType().getTypeName()`（`RESOLVED` 为 `"bool"`，`DYNAMIC` 为 `GdVariantType.VARIANT`），`FAILED` 断言 `detailReason()` 含 `"not defined for operand types"`。

另补一个 GDCC class 与 engine class 混合 pair（如本脚本自身类型 vs `Node`）的 `==` case，锚定 §2.2 的继承无关 pair 决策。

验收：

- `./gradlew test --tests "FrontendExpressionSemanticSupportTest.resolveBinaryExpressionTypeAcceptsObjectIdentityEqualitySpecialRule" --no-daemon --info --console=plain` 通过
- 该测试类全量回归通过

### Step 3：compile gate 测试

在 `FrontendCompileCheckAnalyzerTest` 中，对照 `analyzeForCompileLeavesTypedObjectNilEqualityOutOfCompileBlocks`（`:600`）新增 `analyzeForCompileLeavesTypedObjectIdentityEqualityOutOfCompileBlocks`：snippet 含 `return left == right` / `obj != node` 形态的 typed object/object 比较，断言 compile-block 面不出现这些表达式的 blocker、无 error diagnostic。

同时保留负面对照：同类 snippet 中 `left < right` 必须仍被 compile gate 阻断（对照 `analyzeForCompileKeepsNonEqualityObjectNilComparisonBlocked`，`:634`）。

验收：

- `./gradlew test --tests FrontendCompileCheckAnalyzerTest --no-daemon --info --console=plain` 通过

### Step 4：lowering 测试

在 `FrontendLoweringBodyInsnPassTest` 中，对照 `runKeepsTypedObjectNilEqualityOnBinaryOpRoute`（`:262`）新增 `runKeepsTypedObjectIdentityEqualityOnBinaryOpRoute`：

- snippet：`func same_node(left: Node, right: Node) -> bool: return left == right`
- 断言：无 error diagnostic；恰好一个 `BinaryOpInsn`，`op()` 为 `GodotOperator.EQUAL`，结果类型 `bool`；两个 operand 均为 object-typed value id；`PackVariantInsn` / `UnpackVariantInsn` 计数为 0

验收：

- `./gradlew test --tests "FrontendLoweringBodyInsnPassTest.runKeepsTypedObjectIdentityEqualityOnBinaryOpRoute" --no-daemon --info --console=plain` 通过

### Step 5：backend codegen 覆盖核对

`COperatorInsnGenTest` 已覆盖 `Object == Object`（`objectEqualUsesNormalizedRawComparison`，`:83`）、`Object != Object`（`:107`）、GDCC object（`:131`）与 GDCC/engine 混合 pair（`:159`）。本步骤只补一个对称 case：`GdObjectType("Node")` 双侧的 `==`，复用 `engineObjectApi()`（`:996`）既有 fixture，断言与 `objectEqualUsesNormalizedRawComparison` 相同的 equality-normalized raw 输出。

不新增任何 main-source backend 代码；若本步骤暴露 backend 对非根 object class 的处理缺陷，停下来先回报，不在本 issue 内顺手修。

验收：

- `./gradlew test --tests COperatorInsnGenTest --no-daemon --info --console=plain` 通过

### Step 6：smoke / end-to-end 锚点

新增（而不是修改）smoke 资源，保持 `object_nil_equality` 既有锚点不动：

- `src/test/test_suite/unit_test/script/smoke/object_identity_equality.gd`：沿用 mask 模式（对照 `object_nil_equality.gd`），覆盖：同一实例自反 `==`、两个不同实例 `!=`、`Node == Object`（`get_parent()` 或参数 upcast 形态）、`Signal.get_object() == node`（engine API 返回 `Object` 的 issue 原生形态）、`null` 分支不误翻转
- `src/test/test_suite/unit_test/validation/smoke/object_identity_equality.gd`：对照既有 validation 写法，`EXPECTED_MASK` 与脚本 mask 对齐
- 在 `GdScriptUnitTestCompileRunnerTest` 的 `EXPECTED_SCRIPT_PATHS`（`:140` 附近）登记新 smoke

该层依赖 Zig + Godot 二进制，必须保持环境感知跳过（`Assumptions`），无环境时不构成回归失败。

验收：

- `./gradlew test --tests GdScriptUnitTestCompileRunnerTest --no-daemon --info --console=plain` 通过（有环境时新 smoke PASS marker 对齐；无环境时按既有约定 abort-skip）

### Step 7：文档同步

1. 更新 `frontend_unary_binary_expr_semantic_implementation.md`：
   - §4.2 增列第四条特殊规则（object identity equality），按既有条目格式写明锚定运算符、命中条件、结果类型、实现入口、`instanceof` 约束、"不得扩张 typed boundary" 约束、runtime-open carve-out
   - §4.5 / §4.6 补充 object/object 沿用同一 `BinaryOpInsn` 主路径与 backend object 比较特化的事实，并补一条 Godot 语义边界结论（Variant `OBJECT/OBJECT` equality 为 identity 语义）
   - §6 测试锚点补充 Step 2–6 新增用例；顺带把 §1.1 / §6 中陈旧的 `FrontendExprTypeAnalyzer(Test)` 引用修正为当前实际 owner（`FrontendBodyOwnerProcedures`）
   - §7.3 更新为已论证状态（引用本文档 §2.4）；§7.4 补充新 smoke 资源锚点
   - 更新 "更新时间"
2. 本文档状态转为 "已落地"，事实以 implementation 文档为准。

验收：

- 文档间无相互矛盾表述；`frontend_rules.md` 的 code/doc 一致性约束满足

### Step 8：全量回归

- `./gradlew clean build --no-daemon --info --console=plain` 全量通过后再进入提交流程
- commit message 遵循 Conventional Commits，建议 `feat(frontend): accept object identity equality in binary semantic`（最终以实际改动为准）

---

## 4. 验收细则汇总（映射 issue Suggested Acceptance Coverage）

| issue 要求 | 落点 | 验收命令 |
|---|---|---|
| `typed_node == typed_node` 解析为 `bool` | Step 2 语义单测 | `--tests FrontendExpressionSemanticSupportTest` |
| `typed_object == typed_object` 解析为 `bool` | Step 2 语义单测 | 同上 |
| `typed_object == typed_node` / `typed_node == typed_object` 解析为 `bool`（identity 合同，非 type equality） | Step 2 语义单测 + §2.2 合同 | 同上 |
| `typed_node != typed_node` 同一规则 | Step 2 语义单测 | 同上 |
| 非 equality 运算符（`<`、`<=`）继续明确失败 | Step 2 负面 case + Step 3 负面对照 | 同上 + `--tests FrontendCompileCheckAnalyzerTest` |
| 既有 `object == null` / `null == object` 覆盖保持绿色 | Step 1 既有回归 + 既有 smoke 不动 | `--tests FrontendExpressionSemanticSupportTest` |
| 被接受的事实以 `bool` 结果到达 `BinaryOpInsn` | Step 4 lowering 测试 | `--tests FrontendLoweringBodyInsnPassTest` |
| 端到端编译运行 | Step 6 smoke | `--tests GdScriptUnitTestCompileRunnerTest`（环境感知） |

总体验收门槛：Step 1–5、7 不依赖外部工具链，必须在任何环境全绿；Step 6 在有 Zig + Godot 的环境必须 PASS，无环境时必须按既有约定 skip 而非 fail。

---

## 5. 风险与工程约束

- **合同冻结前置**：§2.2 的 "接受任意 object pair" 是本计划唯一需要显式确认的决策点；若选择更窄的 "同类 + 继承相关" 合同，则 `resolveBinarySpecialReturnType(...)` 需要穿线 `ClassRegistry` 并引入 `checkAssignable` 双向组合，改动面与耦合度都会上升，且仍与 Godot runtime 行为不一致。实施前如对该决策有异议，先回到本节修改，不要直接改代码。
- **diagnostic owner 不变**：本规则只减少 `FAILED` 的产出，不新增 diagnostic 类别，不改变 `sema.expression_resolution` 的 owner 关系；负面路径继续由既有 root-owned `FAILED` 路径承载。
- **不触发 `frontend_rules.md` 的 diagnostic-sync 条款**：无新增 diagnostic / recovery 路径；若实施中发现需要新增诊断，停下来同步更新 `diagnostic_manager.md` 后再继续。
- **compound assignment 与 lowering 复用点自动生效**（§3 Step 1）：复核这三处复用不会在 `==` 场景产生意外结果——`a == b` 不是 compound assignment operator，compound 入口不会命中本规则；body lowering temp typing 命中本规则与主路径结果一致。
- **smoke 资源只增不改**：`object_nil_equality` 两个 smoke 文件是既有冻结锚点，本计划不改动其内容。
- **编码安全**：新增 / 修改的 `.gd` 与 `.md` 文件按仓库约定以 UTF-8 写入，保持既有换行风格，避免 `CRLF`/`LF` 意外转换。
