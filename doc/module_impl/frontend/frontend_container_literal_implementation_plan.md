# Frontend 数组与字典字面量实施计划

> 本文档描述 GDScript 数组字面量与字典字面量从 parser AST、shared semantic、type-check、CFG、LIR 到 C backend 的完整实施方案。
> 本文档只制定计划，不表示当前功能已经进入 compile-ready 支持面。

## 文档状态

- 状态：Planned / Not Implemented
- 目标 Godot 基线：4.5.1-stable
- gdparser 基线：0.5.2
- 计划范围：普通 executable body 与当前已支持的 property initializer island
- 主要关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_local_type_stabilization_implementation.md`
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_lowering_plan.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/module_impl/backend/construct_array_implementation.md`
  - `doc/module_impl/backend/typed_array_abi_contract.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`

---

## 1. 目标

本轮目标是让以下表达式进入完整 compile-ready 支持面：

```gdscript
var values = [1, "two", null]
var config = {"name": "hero", "hp": 100}

var typed_values: Array[float] = [1, 2.5]
var typed_config: Dictionary[StringName, int] = {&"hp": 100}
```

完成后必须具备以下闭环：

1. parser 能稳定产生准确 AST，并保留实现语义所需的信息。
2. shared semantic 为字面量及其所有子表达式发布稳定事实。
3. 无 typed 上下文时，字面量保持 Godot-compatible 的泛型容器类型。
4. 有 typed 上下文时，字面量直接按目标容器类型进行元素级检查与物化。
5. CFG 显式记录源码求值顺序，不在 lowering 中重放 AST。
6. LIR 有独立的容器字面量构造合同，不破坏现有空容器构造指令。
7. C backend 构造真实 Array/Dictionary，并正确处理 Variant 打包、typed 容器与生命周期。
8. compile-only gate 仅在上述链路全部验收后解除拦截。

---

## 2. 非目标

以下内容不在本轮支持范围内：

- `const` 数组/字典的常量折叠、常量池共享和 recursive read-only 语义。
- block-local `const`、class constant、annotation default 等当前 frontend 本身尚未支持的上下文。
- `match` 数组/字典 pattern；`ArrayExpression.openEnded()` / `DictionaryExpression.openEnded()` 对应的 `..` pattern opening 不作为普通字面量处理。
- nested typed container，例如 `Array[Array[int]]` 或 `Dictionary[String, Array[int]]`。Godot 4.5 不支持 nested typed Array，当前 GDCC typed-container ABI 也会 fail-fast。
- 将普通数组字面量隐式转换为 `Packed*Array`。当前 conversion matrix 明确不支持 `Array -> Packed*Array`。
- 新增 Godot strict conversion。元素级兼容性只消费 `frontend_implicit_conversion_matrix.md` 已正式支持的 ordinary typed boundary。
- 复刻全部 Godot warning 开关，例如 `INFERRED_DECLARATION`、`UNTYPED_DECLARATION`、`NARROWING_CONVERSION` 的项目级配置。
- 修改正在实施的 `as` 关键字功能或修改其实施计划。

---

## 3. 调研结论

### 3.1 Godot 4.5.1 参考语义

主要参考位置：

- `godotengine/godot@4.5.1-stable/modules/gdscript/gdscript_parser.cpp`
  - `GDScriptParser::parse_array`
  - `GDScriptParser::parse_dictionary`
- `godotengine/godot@4.5.1-stable/modules/gdscript/gdscript_analyzer.cpp`
  - `GDScriptAnalyzer::reduce_array`
  - `GDScriptAnalyzer::reduce_dictionary`
  - `GDScriptAnalyzer::update_array_literal_element_type`
  - `GDScriptAnalyzer::update_dictionary_literal_element_type`
- `godotengine/godot@4.5.1-stable/modules/gdscript/gdscript_compiler.cpp`
  - `_parse_expression` 的 ARRAY / DICTIONARY 分支
- `godotengine/godot@4.5.1-stable/modules/gdscript/gdscript_byte_codegen.cpp`
  - `write_construct_array`
  - `write_construct_typed_array`
  - `write_construct_dictionary`
  - `write_construct_typed_dictionary`
- `godotengine/godot@4.5.1-stable/modules/gdscript/gdscript_vm.cpp`
  - `OPCODE_CONSTRUCT_ARRAY`
  - `OPCODE_CONSTRUCT_TYPED_ARRAY`
  - `OPCODE_CONSTRUCT_DICTIONARY`
  - `OPCODE_CONSTRUCT_TYPED_DICTIONARY`
- `godotengine/godot-docs` 的 `4.5` 分支：
  - `tutorials/scripting/gdscript/gdscript_basics.rst`
  - `classes/class_dictionary.rst`

Godot 行为中与本计划直接相关的结论：

1. `[1, 2]` 在没有 typed 上下文时是泛型 `Array`，不是 `Array[int]`。
2. `{"hp": 100}` 在没有 typed 上下文时是泛型 `Dictionary`，不是 `Dictionary[String, int]`。
3. typed 容器字面量由使用上下文提供目标元素类型，不从字面量元素反向推导 typed container。
4. 数组元素按源码从左到右求值。
5. 字典按 entry 源码顺序求值，每个 entry 先 key、后 value。
6. 所有子表达式先求值，随后执行容器构造。
7. 常量重复字典键由 analyzer 报错；动态重复键运行时由后写值覆盖先写值，并保持首次插入位置。
8. `Array` 与 `Array[Variant]` 等价；`Dictionary` 与 `Dictionary[Variant, Variant]` 等价。
9. nested typed container 不属于 Godot 4.5 正式支持面。

### 3.2 gdparser 0.5.2 当前 AST 事实

当前依赖提供以下记录：

```java
record ArrayExpression(List<Expression> elements, boolean openEnded, Range range)
record DictionaryExpression(List<DictEntry> entries, boolean openEnded, Range range)
record DictEntry(Expression key, Expression value, Range range)
```

`SuperIceCN/gdparser@0.5.2` 的 `CstToAstMapper` 当前行为还暴露两个实施前必须确认的问题：

1. `openEnded` 来自 `pattern_open_ending`，表达的是 `..` pattern opening，不是普通尾逗号。
2. `DictionaryExpression` 不保留 Python-style `:` 与 Lua-style `=` 的 style 字段，`mapDictionaryEntry(...)` 直接映射 CST left/value。

因此 parser 验收不能只检查“产生了 DictionaryExpression”，还必须检查 Lua-style key 是否已经被 parser 正确归一化为 `StringName` 常量语义，以及混用 style 是否在 parse 阶段报错。

若 gdparser 0.5.2 无法满足第 0 阶段验收，本功能必须先在 gdparser 仓库修正 AST/mapper，再在获得修改 build 配置的明确许可后升级 GDCC 依赖。不得在 GDCC semantic 层通过 source text 猜测 `:` / `=` 风格。

### 3.3 GDCC 当前实现缺口

当前代码明确把两类字面量保持在 temporary compile intercept：

- `FrontendExpressionSemanticSupport.resolveRemainingExplicitExpressionType(...)`
  - `ArrayExpression` 返回 `DEFERRED`
  - `DictionaryExpression` 返回 `DEFERRED`
- `FrontendCompileCheckAnalyzer.walkExpression(...)`
  - 显式发 `sema.compile_check` blocker
- `FrontendCfgGraphBuilder.buildValue(...)`
  - 没有 Array/Dictionary 分支
- `FrontendSequenceItemInsnLoweringProcessors.classifyOpaqueExpression(...)`
  - Array/Dictionary 归类为 `DEFER`

后端已有的 `ConstructArrayInsn` 与 `ConstructDictionaryInsn` 只负责空容器构造和 typed metadata，不携带字面量元素。

`doc/module_impl/backend/construct_array_implementation.md` 还明确冻结了现有 `construct_array` 的操作数合同。本计划不得通过给现有指令追加 variable varargs 的方式实现字面量。

---

## 4. 冻结语义

### 4.1 类型结果

| 场景 | 字面量结果类型 | 说明 |
| --- | --- | --- |
| `var a = [1, 2]` | `Array` | 不根据元素推导 `Array[int]` |
| `var a := [1, 2]` | `Array` | local stabilization 将 slot 从 Variant 稳定为泛型 Array |
| `var d := {"x": 1}` | `Dictionary` | 不根据 key/value 推导 typed Dictionary |
| `var a: Array[int] = [1, 2]` | `Array[int]` | 上下文直接决定 literal construction type |
| `var a: Array[float] = [1, 2]` | `Array[float]` | 每个 int 元素走现有 `int -> float` boundary materialization |
| `var a: Array[String] = [1]` | `Array[String]` + type-check error | literal root 保持目标类型，错误锚定到不兼容元素 |
| `var a: Variant = [1, 2]` | `Array` | 先构造泛型 Array，再由 ordinary boundary pack 到 Variant |
| `var a = []` | `Array` | 空字面量合法 |
| `var d = {}` | `Dictionary` | 空字面量合法 |

禁止采用“全部元素同型则推导 typed container”的方案。该方案会让 `var a := [1, 2]` 偏离 Godot，并使 mixed/empty/contextual conversion 语义不稳定。

### 4.2 typed 上下文来源

MVP 必须覆盖以下 expected-type 来源：

1. 显式 typed local initializer。
2. 显式 typed property initializer。
3. assignment RHS 的 writable target type。
4. function return type。
5. 已选定 exact callable 的 fixed parameter type。
6. typed container 内嵌字面量的 element/key/value target，但仍受 nested typed container 禁止规则约束。
7. `value as Array[T]` / `value as Dictionary[K, V]` 的 cast target。`as` 全链路已完成（见 `frontend_cast_expression_implementation.md`）；container-literal 侧对该 expected-type 源的接通仍属本计划后续工作。

以下场景不提供 typed literal context：

- dynamic call 或无法发布 exact callable boundary 的调用。
- vararg tail 没有具体 element slot 类型时。
- 目标为 `Variant`。
- expression statement。
- 当前仍 deferred 的 conditional/lambda/match/default-argument 上下文。

### 4.3 元素级兼容性

typed literal 的每个元素、key、value 都视为独立 ordinary typed boundary：

```text
source expression type -> literal target element/key/value type
```

必须统一调用：

```java
FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)
```

支持的 decision：

- `ALLOW_DIRECT`
- `ALLOW_WITH_PACK`
- `ALLOW_WITH_UNPACK`
- `ALLOW_WITH_LITERAL_NULL`
- `ALLOW_WITH_INTRINSIC_CAST`
- `ALLOW_WITH_BUILTIN_CONSTRUCTOR`
- `REJECT`

本功能不新增 conversion rule。若未来需要 `float -> int`、`String -> NodePath` 等 Godot strict conversion，必须先更新 `frontend_implicit_conversion_matrix.md`。

容器级 covariance 不得替代元素级 materialization。例如 `Array[float] = [1, 2]` 必须直接构造 `Array[float]`，并把两个 int 元素分别物化为 float；不得先构造 `Array[int]` 再尝试 `Array[int] -> Array[float]`。

### 4.4 求值顺序

数组：

```text
element[0] -> element[1] -> ... -> construct array
```

字典：

```text
entry[0].key -> entry[0].value -> entry[1].key -> entry[1].value -> ... -> construct dictionary
```

要求：

- 每个子表达式只求值一次。
- CFG 必须显式保存 operand value id，不允许 body lowering 重新遍历子 AST 求值。
- 嵌套字面量递归沿用同一顺序。
- 字面量结果被丢弃时，所有子表达式副作用仍必须执行。
- LIR/backend 只能消费已经求值完成的变量，不得改变源码求值顺序。

### 4.5 字典键

Parser 层应负责：

- `{expr: value}` 的 key 是普通表达式。
- `{name = value}` 的 key 是 StringName 常量 `&"name"` 语义，不是变量读取。
- `{"name" = value}` 同样按 Lua-style StringName key 处理。
- 同一字典混用 `:` 与 `=` 必须产生 parse error。

Semantic 层负责直接可归约常量键的重复检查：

- 首批至少覆盖 `null`、bool、int、float、String、StringName、NodePath 直接字面量。
- String 与 StringName 使用 Godot 的 string-like key 等价规则判重。
- int `1` 与 float `1.0` 保持不同 key，不得因 Java 数值相等而合并。
- 非直接字面量 key 不做静态猜测，运行时后写覆盖先写。
- 重复键错误由 `FrontendTypeCheckAnalyzer` 以 `sema.type_check` 发布，锚定后出现的 key，并在消息中指出首次出现位置。

若完整 constant-expression evaluator 尚未实现，本轮不得声称覆盖函数常量、class constant 或容器下标常量键。

### 4.6 `openEnded`

`ArrayExpression.openEnded()` / `DictionaryExpression.openEnded()` 为 true 时，当前 AST 表示 `..` pattern opening。

普通 executable expression 中必须 fail-closed：

- shared semantic 发布 root-owned `FAILED` 或明确 `UNSUPPORTED`。
- 不允许将其解释为尾逗号。
- 不允许 lowering 忽略该字段继续构造普通容器。

尾逗号 `[1, 2,]` / `{"x": 1,}` 应由 parser 正常接受，并产生 `openEnded == false` 的普通 literal AST。

---

## 5. 目标架构

### 5.1 新增 semantic fact

新增 `FrontendContainerLiteralPlan`，由 `EXPR_TYPE` owner 发布，并存入 `FrontendAnalysisData.containerLiteralPlans()`。

建议记录形状：

```java
public record FrontendContainerLiteralPlan(
        @NotNull GdContainerType resultType,
        @NotNull List<OperandPlan> operands,
        @NotNull List<DuplicateKeyIssue> duplicateKeyIssues
) {
    public record OperandPlan(
            int sourceIndex,
            @NotNull OperandRole role,
            @NotNull GdType sourceType,
            @NotNull GdType targetType,
            @NotNull FrontendVariantBoundaryCompatibility.Decision decision
    ) {}

    public record DuplicateKeyIssue(
            int firstEntryIndex,
            int duplicateEntryIndex,
            @NotNull String keyDisplay
    ) {}
}
```

`OperandRole` 至少包含：

- `ARRAY_ELEMENT`
- `DICTIONARY_KEY`
- `DICTIONARY_VALUE`

约束：

- Array plan 的 operands 数量必须等于 `elements().size()`。
- Dictionary plan 的 operands 数量必须等于 `entries().size() * 2`，顺序固定为 key0/value0/key1/value1。
- `resultType` 只能是 `GdArrayType` 或 `GdDictionaryType`。
- plan 可以包含 `REJECT` decision，供 diagnostics-only type-check 精确报错；有 error 时 compile gate 会阻止 lowering。
- duplicate-key 检测结果必须冻结在 `duplicateKeyIssues`；`FrontendTypeCheckAnalyzer` 不得重新解释 key AST 或实现第二套常量归约。
- 非稳定子表达式导致 literal 本身 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 时，不发布伪造 plan。
- plan 内所有 GdType 必须接入 `FrontendPublishedFactTypeGuard`，禁止 `GdCompilerType` 泄漏。

需要扩展：

- `FrontendAnalysisData`
- `FrontendTypedLexicalEnvironment`
- `FrontendExprTypePatch`
- `FrontendOwnerPatch`
- `FrontendPublishedFactTypeGuard`
- 对应 stable-reference、merge-conflict、copy/idempotent tests

不新增平行的“literal result type”side table。字面量最终类型仍由 `expressionTypes()` 唯一发布；plan 只冻结元素边界和 lowering route。

### 5.2 expected-type-aware expression resolution

当前 `BodyExpressionResolver` 只按 Expression identity 缓存结果。容器字面量需要增加可选 expected type，但必须保持“每个 AST 最终只发布一个 expression type”。

保留现有 `NestedExpressionResolver`，另增 expected-aware functional interface，避免现有 method reference 通过 default method 静默丢失 expected type：

```java
@FunctionalInterface
public interface ContextualNestedExpressionResolver {
    @NotNull FrontendExpressionType resolve(
            @NotNull Expression expression,
            boolean finalizeWindow,
            @Nullable GdType expectedType
    );
}
```

需要 contextual typing 的 support API 必须显式接收 `ContextualNestedExpressionResolver`，调用点传 `this::resolveExpressionTypeExpected`。现有无上下文 support API 继续使用 `NestedExpressionResolver`，不得依赖默认 fallback。

`BodyExpressionResolver` 实现真实 expected-type 路径：

1. 普通表达式忽略 expected type，继续按现有语义求型。
2. Array literal 仅当 expected type 是 `GdArrayType` 时采用该类型，否则采用 generic Array。
3. Dictionary literal 仅当 expected type 是 `GdDictionaryType` 时采用该类型，否则采用 generic Dictionary。
4. expected type 与字面量 family 不匹配时，不强行改写 literal 类型，由外围 ordinary boundary 报错。
5. owner-local literal cache 使用 `(Expression identity, GdType expectedTypeOrNull)` 或等价 key，不能继续只按 Expression identity 命中，也不能只比较可能别名冲突的字符串。
6. `finalizedExpressionTypes` 与 stable published fact 仍只允许每个 Expression 一个最终结果；expected-aware key 只存在于 owner-local preview/retry cache。
7. 同一 AST 一旦 finalized，再以不同 expected type 请求时必须立即 fail-fast，包括 `typedEnvironment().expressionType(expression)` 的 published-first 命中路径。
8. speculative call candidate 检查禁止 `finalizeWindow=true`、禁止 `putExpressionType(...)`、禁止写 `containerLiteralPlans()`。
9. preliminary generic preview 不得进入 finalized cache；选定消费上下文后才允许 literal root finalize。

建议明确拆分三张 owner-local map：

```text
previewLiteralPlans[(expression identity, candidate target)] -> candidate-local preview
finalExpectedTypes[expression identity] -> 唯一 finalized expected type guard
finalizedExpressionTypes[expression identity] -> 唯一最终 expression fact
```

`resolveExpressionType(...)` 的 published-first 分支也必须调用 `checkExpectedTypeMatchesFinal(...)`，不得因为 stable fact 已存在就跳过 expected-type 冲突检查。

新增 `FrontendContainerLiteralSemanticSupport`，职责限定为：

- 解析 generic/contextual construction type。
- 递归解析 element/key/value。
- 生成 `FrontendContainerLiteralPlan`。
- 提供 call-overload speculative compatibility/rank 计算。
- 检查 nested typed container 与 `openEnded` hard boundary。
- 不直接发 diagnostics，不写 stable side table。

新增纯类型分类器 `gd.script.gdcc.util.type.TypedContainerAbiSupport`，由 frontend plan 构造与 backend typed-container guard 共同消费：

- generic container：允许。
- engine/GDCC object leaf 与已支持 builtin leaf：允许。
- nested typed container leaf：拒绝。
- 当前无法表达 non-nil typed script identity 的 script leaf：拒绝。
- void、compiler-only、未知 ABI leaf：拒绝。

该 helper 只分类 type shape，不生成 C metadata。`CGenHelper` 继续负责渲染 hint/guard，但不得再维护一套与 frontend 不一致的 supported-leaf 判断。

### 5.3 expected type 传播点

必须在以下位置传递 expected type：

| 消费点 | expected type 真源 |
| --- | --- |
| typed local/property initializer | published slot/declared type |
| inferred local initializer | null，结果保持 generic container |
| assignment RHS | frozen writable target type |
| return value | current callable return type |
| exact call argument | selected callable fixed parameter type |
| cast operand | resolved cast target type |
| nested Array element | outer Array element target type |
| nested Dictionary key/value | outer Dictionary key/value target type |

assignment、call、cast 等 owner 已有自己的 resolver 路由，expected type 必须沿这些既有路由传入，不能新增一个脱离 owner 的全 AST 后处理器。

return type 不能等到 diagnostics-only `FrontendTypeCheckAnalyzer` 才回查。需要新增明确数据通路：

1. 抽取 `FrontendCallableReturnTypeSupport`，统一按 callable owner 与已发布 class/function skeleton 读取 return slot。
2. `FrontendSuiteResolver` 创建 callable-root `FrontendSuiteContext` 时冻结 `currentCallableReturnType`。
3. `FrontendSuiteContext.withChildBlock(...)` 原样传递该类型。
4. property initializer context 的 `currentCallableReturnType` 为 null。
5. `publishRootExpressionTypes(...)` 处理 `ReturnStatement.value()` 时调用 expected-aware resolver。
6. `FrontendTypeCheckAnalyzer` 改为复用同一 helper 或已冻结结果，不能继续维护一套语义可能漂移的 return-slot 查找。

### 5.4 overload resolution

Call argument 中的 typed literal 需要两阶段处理，并且必须覆盖 bare call、attribute/chain call 和 constructor/static call 三条真实 owner 路径：

1. Candidate preview：对每个候选 parameter type 计算 literal element boundary decision 和 specificity rank，不发布 expression type/plan。
2. Candidate selection：bare call 在 `FrontendExpressionSemanticSupport` 内选择；attribute/chain call 在 `FrontendChainReductionHelper` / `ScopeMethodResolver` 路径选择；两者必须调用同一个 literal preview helper。
3. Candidate publication：CHAIN_BINDING 可以先发布 selected `FrontendResolvedCall`，但不得发布 EXPR_TYPE-owned literal plan。
4. Candidate finalize：EXPR_TYPE owner 使用 selected exact parameter type 重新解析 literal argument，发布唯一 contextual expression type 和 plan。
5. `FrontendResolvedCall.argumentTypes()` 必须记录 contextual literal type，例如 `Array[int]`，不能保留 preliminary generic Array snapshot。

必须改写当前真实 finalize 入口，不能只在 overload helper 外围追加 preview：

```text
CHAIN_BINDING:
  preview container-literal arguments per candidate
  select exact callable
  publish FrontendResolvedCall with contextual argumentTypes
  do not publish expressionTypes/containerLiteralPlans

EXPR_TYPE attribute/chain call:
  read selected FrontendResolvedCall for each AttributeCallStep
  for each fixed argument:
      resolveExpressionTypeExpected(argument, true, selectedParameterType)
  then reduce/publish outer attribute expression type

EXPR_TYPE bare call:
  preview literal arguments without finalization
  select exact callable
  finalize literal arguments with selected parameter types
  publish FrontendResolvedCall and expression/plan facts once
```

具体修改合同：

1. `FrontendBodyOwnerProcedures.resolveAttributeExpressionType(...)` 不得继续在 `reduceAttributeExpression(...)` 前对所有 call argument 无条件调用 `resolveExpressionType(argument, finalizeWindow)`；exact call step 必须按已发布 fixed parameter type 调 contextual resolver。
2. `finalizeAttributeAssignmentTargetExpressionTypes(...)` 中的 call arguments 使用同一 selected-boundary 路径，避免 assignment target chain 先 generic finalize。
3. `FrontendExpressionSemanticSupport.resolveCallArgumentTypes(...)` 对 container literal 只做 candidate preview；`selectCallableOverload(...)` 返回 selected boundary 后再 finalize。
4. `FrontendChainReductionHelper.resolveArgumentTypes(...)` 不得把 generic Array/Dictionary 当作最终 call-site snapshot；selected call 的 `argumentTypes()` 直接记录 contextual container type。
5. CHAIN_BINDING 已发布的 `FrontendResolvedCall` 是 EXPR_TYPE finalize 的真源，EXPR_TYPE 不得重新选 overload 或重发不同 call fact。
6. 任一入口若先以 expected=null finalized container literal，再尝试 selected parameter expected type，必须由 cache guard 暴露为 programmer error；测试不得通过放宽冲突覆盖来“修复”。

每个 literal candidate 的 operand ranks 聚合为：

```text
worstRank = min(all operand ranks)
totalRank = sum(all operand ranks)
```

比较顺序固定为：

1. `REJECT` operand 直接淘汰候选。
2. `worstRank` 较高者优先。
3. `worstRank` 相同则 `totalRank` 较高者优先。
4. 仍相同则回到现有 parameter-type specificity / overload ambiguity 合同。

单个 operand rank 复用：

```java
FrontendVariantBoundaryCompatibility.decisionSpecificityRank(...)
```

候选包含 typed container 时：

- 任一稳定元素为 `REJECT`，该候选不可用。
- 全 direct 的候选优先于需要 intrinsic/constructor 的候选。
- runtime-open Variant unpack 的候选优先级低于稳定 direct/cast 候选。
- generic Array/Dictionary parameter 以每个 operand target 为 Variant 计算。
- dynamic fallback 没有 exact boundary 时不做 contextual typing，literal 保持 generic。

必须增加 bare/chain/constructor overloaded-call tests，避免 `[1]` 在 `f(Array[int])` 与 `f(Array[String])` 间错误选择或无条件歧义。`[Variant]` 面对两个同 rank typed-container overload 时应沿用现有 ambiguity 合同，不得按声明顺序任选。

### 5.5 type-check diagnostics

`FrontendTypeCheckAnalyzer` 新增容器字面量 plan 消费：

- 对 `REJECT` array element 发 `sema.type_check` error。
- 对 `REJECT` dictionary key/value 发 `sema.type_check` error。
- 对 nested typed container 发 `sema.type_check` error。
- 对直接可归约重复 key 发 `sema.type_check` error。
- upstream 已有 error 的 element/key/value 不重复发错。
- 一个坏 literal 不阻止同 module 其他 subtree 继续分析。

建议文案与 Godot 语义对齐：

```text
Cannot have an element of type "X" in an array of type "Array[Y]".
Cannot have a key of type "X" in a dictionary of type "Dictionary[K, V]".
Cannot have a value of type "X" in a dictionary of type "Dictionary[K, V]".
Key "X" was already used in this dictionary; first occurrence is at ...
```

普通 literal root 即使存在 element-level REJECT，也保留 contextual result type；type-check error 负责阻止 compile，lowering 不处理 error recovery。

---

## 6. CFG 设计

### 6.1 专用 item

新增一个专用 `ContainerLiteralItem`，不要复用 `OpaqueExprValueItem`。

建议形状：

```java
public record ContainerLiteralItem(
        @NotNull Expression expression,
        @NotNull List<String> operandValueIds,
        @NotNull String resultValueId
) implements ValueOpItem {}
```

构造器必须验证 expression 只能是 `ArrayExpression` 或 `DictionaryExpression`。

采用一个 item 而不是 Array/Dictionary 两个 item 的原因：

- 两者都遵守“所有 child 先求值，再构造一个 result”的同一 CFG 合同。
- family 可由 expression 和 `FrontendContainerLiteralPlan.resultType()` 双重校验。
- Dictionary 的扁平 operand 顺序由 plan 与 AST entry 数量共同校验。
- 减少 sealed hierarchy、registry 与 materialization switch 的平行样板。

### 6.2 builder 路由

`FrontendCfgGraphBuilder.buildValue(...)` 增加：

- `case ArrayExpression -> buildArrayLiteralValue(...)`
- `case DictionaryExpression -> buildDictionaryLiteralValue(...)`

Array builder：

1. 按 `elements()` 顺序调用 `buildValue`。
2. 收集每个 child result value id。
3. 最后 append `ContainerLiteralItem`。

Dictionary builder：

1. 按 `entries()` 顺序遍历。
2. 对每项先 build key，再 build value。
3. 按 key0/value0/key1/value1 顺序收集 value id。
4. 最后 append `ContainerLiteralItem`。

builder 必须读取并验证 `analysisData.containerLiteralPlans()[literal]`：

- plan 必须存在。
- plan result type 必须与 `expressionTypes()[literal]` 相同。
- plan operand 数量、role 和源码顺序必须一致。
- plan 不得包含 `REJECT`；若包含，说明 compile error gate 被绕过，应 fail-fast。

### 6.3 materialization

`FrontendBodyLoweringSupport.collectProducedValueMaterialization(...)` 为 `ContainerLiteralItem` 声明一个独立 `cfg_tmp_*` result slot，类型取自 literal 的 published expression type。

`FrontendSequenceItemInsnLoweringProcessors` 注册专用 processor：

1. 逐 operand 读取 plan 的 source/target/decision。
2. 调用现有 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`。
3. 收集 materialized operand variable id。
4. 发射一个专用 LIR container-literal instruction。

禁止：

- 在 processor 中重新调用 semantic helper 决定兼容性。
- 在 processor 中重新遍历 child AST 求值。
- 把 literal 回退到 opaque expression handler。
- 让 generic literal operands 绕过 `target=Variant` 的 pack materialization。

完成后 `classifyOpaqueExpression(...)` 对 Array/Dictionary 应改为 protocol violation，而不是 `DEFER`。

---

## 7. LIR 设计

### 7.1 保持现有空构造合同

以下指令保持不变：

```text
$result = construct_array "<class_name>"?
$result = construct_dictionary "<key_class_name>"? "<value_class_name>"?
```

它们继续服务：

- `__prepare__` 变量初始化。
- property default init helper。
- 显式空 typed/generic container 构造。
- Packed*Array 空构造。

不得修改它们的 operand 数量或语义。

### 7.2 新指令

新增：

```text
$result = construct_container_literal $operand0 $operand1 ...
```

建议 Java record：

```java
public record ConstructContainerLiteralInsn(
        @Nullable String resultId,
        @NotNull List<Operand> operands
) implements ConstructionInstruction {}
```

合同：

- result variable type 为 `GdArrayType` 时，所有 operands 按数组元素顺序解释。
- result variable type 为 `GdDictionaryType` 时，operands 按 key0/value0/key1/value1 解释，数量必须为偶数。
- result variable 不得为 ref。
- result variable 不得是 Packed*Array 或其他 builtin。
- operands 全部必须是 `VariableOperand`。
- 每个 operand 可以是任意 source-facing GdType；backend 负责临时 pack 为 Variant 后写入容器。
- 空 operands 合法，family 由 result variable type 决定。

需要修改：

- `GdInstruction`
- 新增 `ConstructContainerLiteralInsn`
- `ParsedLirInstruction.toConcrete(...)`
- `SimpleLirBlockInsnParser` 的 varargs operand kind 推断
- serializer/parser round-trip tests
- `doc/gdcc_low_ir.md`

采用单一 opcode 的原因：

- empty Array/Dictionary 可由 result type 无歧义区分。
- 不复制两套完全相同的 varargs parser/serializer 合同。
- 不污染已冻结的 `construct_array` / `construct_dictionary`。

---

## 8. C Backend 设计

### 8.1 新 generator

新增 `ContainerLiteralInsnGen`，只负责 `CONSTRUCT_CONTAINER_LITERAL`。

不要把字面量逻辑塞入 `ConstructInsnGen`；后者继续维护已有空构造与 object/builtin construction 合同。

### 8.2 Array 路径

步骤：

1. 验证 result 是 non-ref `GdArrayType`。
2. 调用 `CBuiltinBuilder.constructBuiltin(..., List.of())` 构造正确的 generic/typed 空 Array。
3. 按 operand 顺序调用 `InsnGenSupport.materializeVariantOperand(...)`。
4. 调用仓库自维护 wrapper `src/main/c/codegen/include_451/godot/godot_builtin.h` 中的 `godot_Array_push_back(...)`。
5. 每次 append 后立即销毁 generator-local Variant temp。

typed Array 的元素在 frontend body lowering 已先 materialize 到目标元素类型，因此 backend append 只负责 Variant carrier，不负责重新决定类型转换。
`godot_Array_push_back(...)` 返回 `void`，不能伪造与 Dictionary 对称的返回值检查；合法程序依赖 frontend 已冻结的目标类型/materialization 合同和 Godot typed Array 校验。无论引擎是否报告 typed write error，generator-local Variant temp 都必须按正常顺序销毁。

### 8.3 Dictionary 路径

步骤：

1. 验证 result 是 non-ref `GdDictionaryType`。
2. 验证 operand 数量为偶数。
3. 调用 `CBuiltinBuilder.constructBuiltin(..., List.of())` 构造正确的 generic/typed 空 Dictionary。
4. 按 key/value pair 顺序分别 materialize Variant operand。
5. 调用仓库自维护 wrapper `godot_Dictionary_set(...)`。
6. 检查返回的 `godot_bool`；false 视为 runtime container write failure，打印明确错误并走现有 failure cleanup/return 路径。
7. 每个 pair 写入后立即销毁 key/value generator-local Variant temp。

动态重复 key 必须自然表现为后写覆盖先写；不得在 backend 人为拒绝。

### 8.4 生命周期

必须保持：

- literal result slot 仍由现有 function variable lifecycle 在 `__finally__` 自动 destruct。
- generator-local pack Variant 在每次 append/set 后销毁。
- Array/Dictionary 是 shared/reference-semantics container，不需要 reverse writeback。
- object element 被 pack 进 Variant 后，由 Godot 容器持有对应引用；generator 不插入额外 `own/release`。
- statement-position 丢弃结果仍由普通 temp lifecycle 清理。
- backend 不创建与 source operand 生命周期竞争的长期别名。

### 8.5 typed ABI 限制

在进入 backend 前必须由 frontend fail-closed：

- nested typed Array/Dictionary leaf。
- 当前 typed-container ABI 无法表达的 script leaf。
- void/compiler-only element type。
- typed construction type 与 expression/result slot 不一致。

`Array[Variant]` 与 `Dictionary[Variant, Variant]` 必须走 plain container 路径，不生成 typed metadata。

engine test 必须验证 typed fill 后 `is_typed()`、typed element/key/value metadata 与实际读取值，不能只比较生成 C 字符串。

---

## 9. 分阶段实施与验收

### 阶段 0：Parser 契约验收

实施：

1. 新增 `FrontendContainerLiteralParseBehaviorTest`。
2. 验证普通数组、字典、空字面量、嵌套字面量和尾逗号。
3. 验证 Dictionary Python/Lua 两种 style。
4. 验证 style 混用产生 parse error。
5. 验证 Lua-style identifier/string key 归一化为 StringName 语义。
6. 验证 `openEnded` 只表示 `..`，尾逗号不会设置它。
7. 若失败，在 gdparser 修复并发布新版本；获得许可后再升级依赖。

验收：

- `[1, 2,]` 为 `ArrayExpression(elements=2, openEnded=false)`。
- `{"x": 1,}` 为 `DictionaryExpression(entries=1, openEnded=false)`。
- `{x = 1}` 的 key 不是 ordinary Identifier variable read。
- `{x: 1, y = 2}` 产生 parse error。
- `{1 = "x"}` 产生 parse error。
- `{x: 1}` 保持 expression-key 语义。
- parser error 继续映射到 `parse.lowering`，不抛 runtime exception。

完成门槛：parser 契约未通过前，不开始 semantic 实施。

### 阶段 1：Generic literal shared semantic

实施：

1. 新增 `FrontendContainerLiteralSemanticSupport`。
2. 将 Array/Dictionary 从 explicit deferred resolver 改为 dedicated resolver。
3. 无 expected type 时发布 generic Array/Dictionary。
4. 递归解析所有 child expression，保留 upstream status。
5. `openEnded=true` 在 ordinary expression 中 fail-closed。
6. 新增 `FrontendContainerLiteralPlan` 及 EXPR_TYPE side-table publication plumbing。
7. local stabilization 只复用 `FrontendContainerLiteralSemanticSupport`，不得保留第二套 deferred/generic literal 规则。
8. generic/contextual plan 构造同时冻结直接常量 duplicate-key issues；此阶段 type-check consumer 尚未接入时 compile gate 仍保持 blocker。
9. 保持 compile gate 显式 blocker，不解除编译拦截。

验收：

- `[1, "x"]` 发布 `RESOLVED(Array)`。
- `{"x": 1}` 发布 `RESOLVED(Dictionary)`。
- `var a := [1, 2]` slot 稳定为 generic Array，不是 `Array[int]`。
- `var d := {"x": 1}` slot 稳定为 generic Dictionary。
- mixed/empty/nested generic literal 都为 RESOLVED。
- child expression FAILED 时 literal 传播状态且不重复诊断。
- plan stable-reference、patch merge、idempotent 和 compiler-only guard tests 全部通过。
- compile-only 分析仍阻止 literal 进入 lowering。

建议 targeted tests：

```powershell
.\gradlew.bat test --tests FrontendExpressionSemanticSupportTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendBodyOwnerProceduresExprTypeTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendBodyOwnerProceduresVarTypePostTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendAnalysisDataTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendTypedLexicalEnvironmentTest --no-daemon --info --console=plain
```

### 阶段 2：Contextual typed literal 与 type-check

实施：

1. 扩展 expected-type-aware resolver API 和 cache guard。
2. 新增 `FrontendCallableReturnTypeSupport` 与 `FrontendSuiteContext.currentCallableReturnType` 数据通路。
3. 接通 typed initializer、assignment、return、exact fixed call argument。
4. 在 bare call、chain call、constructor/static call 接通 selected-call candidate preview/finalize。
5. 生成每个 operand 的 target type 与 boundary decision。
6. `FrontendTypeCheckAnalyzer` 只消费 plan 中的 `REJECT` decisions 与 duplicate-key issues。
7. 引入 `TypedContainerAbiSupport`，增加 nested typed container、script leaf、void/compiler-only leaf fail-closed。
8. 接通 cast target expected type（`as` 事实源已稳定，见 `frontend_cast_expression_implementation.md`）。

验收：

- `Array[int] = [1, 2]` 通过。
- `Array[float] = [1, 2]` plan 含两个 `ALLOW_WITH_INTRINSIC_CAST`。
- `Array[StringName] = ["x"]` plan 含 `ALLOW_WITH_BUILTIN_CONSTRUCTOR`。
- `Array[int] = [variant_value]` plan 使用现有 Variant unpack boundary。
- `Array[String] = [1]` 只在元素处产生一条 `sema.type_check` error。
- `Dictionary[StringName, float] = {"x": 1}` 同时冻结 key constructor 与 value intrinsic cast。
- assignment/return/fixed call parameter 均可提供 typed context。
- bare call、chain method 与 constructor/static call overload 均根据统一 element boundary rank 选择正确 callable。
- 多候选 preview 后只发布一个 final expression type/plan，preview cache 不进入 stable side table。
- `f([1])`、`obj.m([1])` 与 exact constructor/static call 中，`expressionTypes[literal]`、`FrontendResolvedCall.argumentTypes()`、`containerLiteralPlans[literal].resultType()` 三者一致。
- CHAIN_BINDING 到 EXPR_TYPE 不因 generic-first publication 触发 expected-type conflict。
- dynamic call argument 保持 generic literal。
- nested typed container 在 frontend 报错，不进入 backend。
- `Array[Array]` 的 nested generic value 合法，`Array[Array[int]]` 的 nested typed leaf 非法。
- unsupported script leaf、void/compiler-only leaf 在 frontend 报源码诊断，不进入 backend。
- 重复 String/StringName key 报错，`1` 与 `1.0` 不误判重复。

建议 targeted tests：

```powershell
.\gradlew.bat test --tests FrontendContainerLiteralSemanticSupportTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendTypeCheckAnalyzerTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendAssignmentSemanticSupportTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendExpressionSemanticSupportTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendChainReductionHelperTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendBodyOwnerProceduresExprTypeTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendVariantBoundaryCompatibilityTest --no-daemon --info --console=plain
```

### 阶段 3：CFG

实施：

1. 新增 `ContainerLiteralItem`。
2. 扩展 `ValueOpItem` permits。
3. 接通 Array/Dictionary buildValue 分支。
4. 读取并验证 literal plan。
5. 扩展 materialization collection。
6. 注册 body processor shell；此阶段可先让 processor fail-fast，LIR 阶段完成后再落地。

验收：

- `[f(1), f(2)]` 的 CFG producer 顺序为 call1、call2、container literal。
- `{k1(): v1(), k2(): v2()}` 的 operand 顺序固定为 k1/v1/k2/v2。
- 每个 child value id 只有一个 ordinary producer。
- 嵌套字面量内层 result id 被外层 item 消费。
- statement-position literal 仍保留 child side effects。
- plan 缺失、数量不匹配、role 不匹配或含 REJECT 时 fail-fast。

建议 targeted tests：

```powershell
.\gradlew.bat test --tests FrontendCfgGraphBuilderContainerLiteralTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendLoweringBuildCfgPassTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendBodyLoweringSupportTest --no-daemon --info --console=plain
```

### 阶段 4：LIR

实施：

1. 新增 `CONSTRUCT_CONTAINER_LITERAL`。
2. 新增 `ConstructContainerLiteralInsn`。
3. 接通 parser/serializer/concrete conversion。
4. body processor 使用 plan materialize operands 后发射新指令。
5. 更新 `doc/gdcc_low_ir.md`。

验收：

- Array operands round-trip 保序。
- Dictionary operands round-trip 保持偶数与 key/value 顺序。
- 空 operands 对 Array/Dictionary 都合法。
- non-variable operand 被 parser/contract 拒绝。
- Dictionary odd operand count 被 instruction/backend validation 拒绝。
- result missing、ref result、非容器 result 被拒绝。
- body lowering 中 int->float、String->StringName、Variant unpack 的前置 materialization 指令准确。

建议 targeted tests：

```powershell
.\gradlew.bat test --tests ConstructContainerLiteralInsnContractTest --no-daemon --info --console=plain
.\gradlew.bat test --tests SimpleLirBlockInsnParserTest --no-daemon --info --console=plain
.\gradlew.bat test --tests DomLirParserTest --no-daemon --info --console=plain
.\gradlew.bat test --tests DomLirSerializerTest --no-daemon --info --console=plain
.\gradlew.bat test --tests FrontendContainerLiteralInsnLoweringTest --no-daemon --info --console=plain
```

### 阶段 5：C Backend

实施：

1. 新增 `ContainerLiteralInsnGen`。
2. 在 `CCodegen` 注册新 generator。
3. Array 使用 empty typed/generic construction + `godot_Array_push_back`。
4. Dictionary 使用 empty typed/generic construction + `godot_Dictionary_set`。
5. 复用 `InsnGenSupport.materializeVariantOperand`。
6. 增加 generator-local temp cleanup 和 invalid-insn guards。

验收矩阵：

| 场景 | 期望 |
| --- | --- |
| generic empty Array | `godot_new_Array()`，无 append |
| generic Array with scalars | 每元素 pack Variant 后按序 push_back |
| typed Array[int] | typed empty constructor + packed int Variant append |
| typed Array[float] with int source | frontend 已生成 int->float，backend 只 pack float |
| generic Dictionary | 按 pair 顺序 set |
| typed Dictionary | typed empty constructor + packed target-typed key/value set |
| object element | pack temp 正确销毁，容器保留对象引用 |
| nested generic literal | 内层容器先构造，外层随后 pack/append |
| duplicate dynamic key | 后写覆盖先写 |
| statement-position discarded result | result 最终 destruct，无泄漏 |

建议 targeted tests：

```powershell
.\gradlew.bat test --tests CContainerLiteralInsnGenTest --no-daemon --info --console=plain
.\gradlew.bat test --tests CContainerLiteralInsnGenEngineTest --no-daemon --info --console=plain
.\gradlew.bat test --tests CConstructInsnGenTest --no-daemon --info --console=plain
.\gradlew.bat test --tests PackUnpackVariantInsnGenTest --no-daemon --info --console=plain
.\gradlew.bat test --tests CPhaseAControlFlowAndFinallyTest --no-daemon --info --console=plain
```

### 阶段 6：解除 compile gate 与端到端验收

只有阶段 0-5 全部完成后才执行：

1. 从 `FrontendCompileCheckAnalyzer.walkExpression(...)` 移除 Array/Dictionary explicit blocker。
2. 从 `classifyOpaqueExpression(...)` 的 DEFER 列表移除并改为 dedicated-item-only guard。
3. 更新 `FrontendCompileCheckAnalyzerTest` 的 blocker 数量和精确 anchor。
4. 新增 test-suite script/validation 对。
5. 验证 `analyze(...)` 与 `analyzeForCompile(...)` 仍保持 shared/compile-only 分流。

端到端用例至少包括：

- generic Array literal 创建、size、subscript、mutation。
- generic Dictionary literal 创建、lookup、mutation。
- empty literal。
- mixed literal。
- nested generic literal。
- typed local/property initializer。
- typed assignment RHS。
- typed function return。
- typed exact call argument。
- literal 结果立即 subscript，例如 `[10, 20][1]`。
- Array 左到右副作用顺序。
- Dictionary key/value 副作用顺序。
- dynamic duplicate Dictionary key 后写覆盖。
- dynamic duplicate Dictionary key 保持首次插入位置，validation 同时断言 keys 顺序与最终 value。
- String/StringName key roundtrip。
- Variant element unpack 到 typed container。
- object element lifecycle。
- `var a: Variant = [1]` 先构造 generic Array，再 pack 到 Variant。
- typed property initializer 真实进入 `init_func`，不是 shell-only helper。
- statement-position discarded literal 保留副作用且 result temp 最终 destruct。

建议 test-suite 文件：

```text
src/test/test_suite/unit_test/script/collection/array_literal_roundtrip.gd
src/test/test_suite/unit_test/script/collection/dictionary_literal_roundtrip.gd
src/test/test_suite/unit_test/script/collection/container_literal_evaluation_order.gd
src/test/test_suite/unit_test/script/collection/typed_container_literal_boundaries.gd
```

对应 validation 文件必须同步新增。

完成门槛：

- focused frontend、LIR、backend tests 全部通过。
- 新增 test-suite 用例 compile/link/run 通过。
- literal 不再产生 `sema.compile_check`。
- 其他 temporary intercept 的 blocker 数量和行为不变。
- 不因解除 literal gate 顺带解除 Conditional/Preload/GetNode/Cast。

### 阶段 7：文档同步与最终清理

必须同步：

- `frontend_rules.md`
  - 删除“数组和字典字面量在 MVP 中不支持”。
  - 从 temporary compile intercept 列表移除 Array/Dictionary。
- `frontend_compile_check_analyzer_implementation.md`
- `frontend_chain_binding_expr_type_implementation.md`
- `frontend_type_check_analyzer_implementation.md`
- `frontend_local_type_stabilization_implementation.md`
- `frontend_lowering_plan.md`
- `frontend_lowering_cfg_pass_implementation.md`
- `frontend_lowering_(un)pack_implementation.md`
- `diagnostic_manager.md`
- `doc/gdcc_low_ir.md`
- 新增 backend 维护文档 `doc/module_impl/backend/construct_container_literal_implementation.md`
- `doc/test_suite.md`

如果本功能没有改变 conversion matrix，不修改其规则表，只在相关文档引用它。若实施中发现必须扩展 matrix，必须按 matrix 文档规定先改事实源，再改代码。

---

## 10. 与 `as` 关键字工作的隔离

`as`（CastExpression）全链路已完成并收敛为 `frontend_cast_expression_implementation.md`。实施本计划时仍须遵守：

1. 本计划文档不得改写 `frontend_cast_expression_implementation.md` 的稳定合同。
2. 开始容器字面量实施前重新读取共享文件，不能基于本计划调研时的旧行号直接套 patch。
3. 不回退、不覆盖任何已落地的 `as` 改动；只在共享 switch/registry 上追加 container-literal 路径。
4. Cast 已离开 compile-gate temporary intercept；移除 Array/Dictionary blocker 时不得回退 Cast 放行状态。

高冲突文件：

| 文件 | 冲突原因 | 隔离策略 |
| --- | --- | --- |
| `FrontendExpressionSemanticSupport.java` | Cast 与 literal 同属 remaining-expression switch | 基于 `as` 完成后的最新版增加 literal dedicated route |
| `FrontendBodyOwnerProcedures.java` | Cast/literal 都使用 BodyExpressionResolver | 不改 cast 结果合同，只扩 expected-type API |
| `FrontendAnalysisData.java` / `FrontendTypedLexicalEnvironment.java` | literal 新增 stable side table，cast 也依赖同一 publication pipeline | 只追加 containerLiteralPlans，不改变 cast expression type publication |
| `FrontendExprTypePatch.java` / `FrontendPublishedFactTypeGuard.java` | literal plan 需要进入 EXPR_TYPE patch/type guard | 基于 cast 完成后的 carrier 形状追加字段与 guard |
| `FrontendTypeCheckAnalyzer.java` | cast validity 与 literal element/duplicate-key diagnostics 共用 visitor | 保留 cast visitor，只增加 plan consumer 与 nested traversal |
| `FrontendChainReductionHelper.java` / `ScopeMethodResolver` call sites | chain overload 必须在选择前 preview literal argument | 使用共享 literal candidate comparator，不改 cast route |
| `FrontendCompileCheckAnalyzer.java` | Array/Dictionary 仍在 blocker switch；Cast 已放行 | 仅移除 Array/Dictionary case，不回退 Cast 已放行状态 |
| `FrontendCfgGraphBuilder.java` | Cast 与 literal 都进入 buildValue | 新增独立 ContainerLiteralItem 分支，不改 CastItem |
| `FrontendSequenceItemInsnLoweringProcessors.java` | processor registry 与 opaque classifier 共享 | 在 `as` processor 落地后追加 literal processor |
| `CCodegen.java` | generator registry 正在被 cast backend 修改 | 等 `as` 修改稳定后追加 ContainerLiteralInsnGen |
| `GdInstruction.java` | cast 已新增 opcode | 只追加新 opcode，不重排或重写 cast opcode |
| `ParsedLirInstruction.java` | cast 与 literal 都增加 concrete case | 基于最新版追加独立 case |
| `FrontendCompileCheckAnalyzerTest.java` | blocker 计数会被双方改变 | 使用 feature-specific assertions，避免只断言总数 |

可先独立新增且不触碰 `as` 的文件建议限于：

- `FrontendContainerLiteralParseBehaviorTest`
- `FrontendContainerLiteralPlan`
- `FrontendContainerLiteralSemanticSupport` 的纯 helper 骨架与 isolated unit tests
- `TypedContainerAbiSupport` 及纯类型测试

上述新增文件在未接入 shared pipeline 前不得宣称 feature 已部分解封。

交叉验收用例放在双方独立闭环之后：

```gdscript
var a = [value as int]
var b: Array[int] = [variant_value as int]
var c = ({"items": [1, 2]} as Dictionary)["items"]
```

这些用例不得成为容器字面量基础阶段的前置条件，以免两项工作互相阻塞。

---

## 11. 风险与防线

### R1：错误地从元素推导 typed container

风险：`var a := [1, 2]` 被实现为 `Array[int]`。

防线：semantic unit test 必须直接断言 generic Array；文档把“不做元素反推”设为冻结语义。

### R2：parser 丢失 Lua-style key 语义

风险：`{name = 1}` 被解释成读取变量 `name`。

防线：阶段 0 作为 hard prerequisite；不允许 GDCC 根据 source text 猜测修复。

### R3：把 `openEnded` 当尾逗号

风险：`..` pattern opening 被静默当作普通字面量接受。

防线：明确 parser tests 与 shared semantic fail-closed tests。

### R4：contextual cache 污染

风险：call candidate speculation 先把 literal 缓存成错误 candidate 类型，最终发布冲突 fact。

防线：candidate preview 不写 finalized cache；selected candidate 才 finalize；同 AST conflicting expected type fail-fast。

### R5：复用 container covariance 代替 element conversion

风险：先构造 `Array[int]` 再错误尝试转成 `Array[float]`。

防线：plan result type直接取 contextual target，每个 operand 单独记录 boundary decision。

### R6：破坏现有 construct_array/dictionary

风险：给既有指令追加 varargs，破坏 prepare/property init/manual LIR。

防线：新增独立 `construct_container_literal`；现有指令和测试保持不变。

### R7：backend 重放求值或改变顺序

风险：generator 重新求值 AST 或 Dictionary value 先于 key。

防线：LIR 只接受已求值 variable operands；CFG 形状测试和 runtime side-effect test 双重锚定。

### R8：typed container ABI 在 backend 才失败

风险：nested typed container/script leaf 直到 C codegen 才 fail-fast。

防线：semantic plan 构造时复用明确的 typed-container support classifier，并由 type-check 报源码诊断。

### R9：Variant/object pack temp 泄漏

风险：每元素临时 Variant 未销毁或 object 被重复 own。

防线：generator helper 级测试检查 temp destruct；ownership tests 检查无额外 own/release。

### R10：compile gate 过早解除

风险：shared semantic RESOLVED 后 literal 提前进入尚未完成的 CFG/backend。

防线：阶段 1-5 保留 explicit blocker；只有阶段 6 才移除。

---

## 12. Definition of Done

全部条件满足后，功能才可标记 Implemented：

- parser contract 对普通/尾逗号/Lua-style/错误 style/openEnded 全部有测试。
- generic literal 不从元素推导 typed container。
- typed initializer/assignment/return/exact call argument 均能提供 contextual type。
- bare/chain/constructor overload preview 使用同一 comparator，且 speculative candidate 不污染 stable facts。
- return expected type 通过 callable-root suite context 在 EXPR_TYPE 前可用。
- `FrontendContainerLiteralPlan` 成为唯一元素边界 lowering fact。
- duplicate-key issues 作为 plan fact 发布，type-check 不重新归约 key AST。
- type-check 对 element/key/value incompatibility 与重复直接常量 key 有准确诊断。
- nested typed、unsupported script leaf、void/compiler-only leaf 在 frontend fail-closed。
- CFG 保证 source-order、single-evaluation 和 dedicated item。
- 新 LIR parser/serializer/contract 完整。
- C backend 支持 generic/typed Array/Dictionary、Variant pack、object element 和 cleanup。
- engine tests 验证 typed fill 后 metadata、读取值、dynamic duplicate key 最终值与 key 顺序。
- compile-only blocker 已解除且其他 feature blocker 未变化。
- focused tests 与新增 test-suite compile/link/run 全部通过。
- 所有相关事实源文档已同步。
- 实施过程未覆盖或回退 `as` 关键字工作的任何改动。
