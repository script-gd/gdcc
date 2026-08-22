# Frontend 数组与字典字面量实现说明

> 本文档作为 GDScript 数组字面量 `[...]` 与字典字面量 `{...}` 在 frontend shared semantic、type-check、CFG/body lowering、LIR 与 C backend 全链路的长期事实源，记录当前冻结的字面量类型语义、`FrontendContainerLiteralPlan` 元素边界事实、expected-type 传播与 overload preview/rank 合同、`ContainerLiteralItem` CFG 合同、`construct_container_literal` LIR 合同、backend 填充路径与回归锚点。本文档替代原 `frontend_container_literal_implementation_plan.md`，不保留分步骤实施、阶段状态、验收清单或已完成任务日志。

## 文档状态

- 状态：事实源维护中（parser 契约、generic/contextual shared semantic、expected-type 传播、overload preview/rank、CFG `ContainerLiteralItem`、`construct_container_literal` LIR、C backend 填充路径与 compile gate 放行均已纳入当前实现；Array/Dictionary literal 属于 compile-ready 支持面）
- 更新时间：2026-08-12
- Godot 对齐基线：`4.5.1-stable`；gdparser 基线 `0.5.3`（字典 Lua-style / 混用 style 契约）
- 适用范围：
  - 普通 executable body 与当前已支持的 property initializer island
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/main/java/gd/script/gdcc/util/type/TypedContainerAbiSupport.java`
  - 对应的 frontend、LIR、backend 与 test-suite 测试
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_local_type_stabilization_implementation.md`
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_cast_expression_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_plan.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/module_impl/backend/construct_array_implementation.md`
  - `doc/module_impl/backend/construct_container_literal_implementation.md`
  - `doc/module_impl/backend/typed_array_abi_contract.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
- 明确非目标：
  - `const` 数组/字典的常量折叠、常量池共享和 recursive read-only 语义
  - block-local `const`、class constant、annotation default 等当前 frontend 本身尚未支持的上下文
  - `match` 数组/字典 pattern；`ArrayExpression.openEnded()` / `DictionaryExpression.openEnded()` 对应的 `..` pattern opening 不作为普通字面量处理
  - nested typed container，例如 `Array[Array[int]]` 或 `Dictionary[String, Array[int]]`；Godot 4.5 不支持 nested typed Array，当前 typed-container ABI 也 fail-closed
  - 将普通数组字面量隐式转换为 `Packed*Array`；conversion matrix 明确不支持 `Array -> Packed*Array`
  - 新增 Godot strict conversion；元素级兼容性只消费 `frontend_implicit_conversion_matrix.md` 已正式支持的 ordinary typed boundary
  - 复刻全部 Godot warning 开关，例如 `INFERRED_DECLARATION`、`UNTYPED_DECLARATION`、`NARROWING_CONVERSION` 的项目级配置

## 1. 当前定位与数据流

当前支持的源码形态为：

```gdscript
var values = [1, "two", null]
var config = {"name": "hero", "hp": 100}

var typed_values: Array[float] = [1, 2.5]
var typed_config: Dictionary[StringName, int] = {&"hp": 100}
```

完整数据流固定为：

```text
ArrayExpression / DictionaryExpression (gdparser AST)
  -> shared semantic (expected-type aware):
       generic 或 contextual 构造类型；递归求型子表达式；
       发布 RESOLVED result + FrontendContainerLiteralPlan side-table
  -> type-check: 消费 plan 中 REJECT decision 与 duplicate-key issues
  -> compile-only final gate (不再为 Array/Dictionary 建立 blocker)
  -> CFG: 源序 buildValue 子表达式 -> ContainerLiteralItem (operand value ids 冻结) + plan 校验
  -> frontend body lowering:
       逐 operand 按 plan 的 source/target/decision materialize
       发射 construct_container_literal
  -> C backend:
       空构造 generic/typed 容器
       逐元素 pack Variant 后 push_back / set
  -> typed/generic result slot
```

shared semantic 负责发布字面量结果类型与元素边界 plan；type-check 只消费 plan、不重新归约；compile gate 不再拦截；CFG 显式记录源码求值顺序；body lowering 与 backend 只消费已求值的变量，不重放 AST。

### 1.1 Parser 形状（gdparser 0.5.3）

当前依赖提供以下 AST 记录：

```java
record ArrayExpression(List<Expression> elements, boolean openEnded, Range range)
record DictionaryExpression(List<DictEntry> entries, boolean openEnded, Range range)
record DictEntry(Expression key, Expression value, Range range)
```

`SuperIceCN/gdparser@0.5.3` 的关键行为（由 `FrontendContainerLiteralParseBehaviorTest` 冻结）：

1. `openEnded` 来自 `pattern_open_ending`，表达的是 `..` pattern opening，不是普通尾逗号；尾逗号 `[1, 2,]` / `{"x": 1,}` 产生 `openEnded == false` 的普通 literal AST。
2. `DictionaryExpression` / `DictEntry` 不保留 style 字段；风格语义在 CST→AST 时已落地：
   - Python-style `:`：key 保持 expression（`{x: 1}` → `IdentifierExpression("x")`）。
   - Lua-style `=`：key 归一化为 `LiteralExpression(kind="string_name", sourceText=&"...")`（`{x = 1}` / `{"name" = 1}`）。
   - 同一字典内混用 `:` / `=` → lowering ERROR（GDCC 映射为 `parse.lowering`）。
3. Grammar（上游 tree-sitter-gdscript `c5c8fa4`）允许 Lua-style string key：`{"name" = 1}`。

**硬性规则：** 不得在 GDCC semantic 层通过 source text 猜测 `:` / `=` 风格；风格语义由 gdparser 负责。

### 1.2 Godot 参考语义

主要参考位置（`godotengine/godot@4.5.1-stable`）：`gdscript_parser.cpp`（`parse_array` / `parse_dictionary`）、`gdscript_analyzer.cpp`（`reduce_array` / `reduce_dictionary` / `update_array_literal_element_type` / `update_dictionary_literal_element_type`）、`gdscript_compiler.cpp`（`_parse_expression`）、`gdscript_byte_codegen.cpp`（`write_construct_*`）、`gdscript_vm.cpp`（`OPCODE_CONSTRUCT_*`）；godot-docs `4.5` 分支的 `gdscript_basics.rst` 与 `class_dictionary.rst`。

与本文档合同直接相关的 Godot 行为结论：

1. `[1, 2]` 在没有 typed 上下文时是泛型 `Array`，不是 `Array[int]`。
2. `{"hp": 100}` 在没有 typed 上下文时是泛型 `Dictionary`，不是 `Dictionary[String, int]`。
3. typed 容器字面量由使用上下文提供目标元素类型，不从字面量元素反向推导 typed container。
4. 数组元素按源码从左到右求值。
5. 字典按 entry 源码顺序求值，每个 entry 先 key、后 value。
6. 所有子表达式先求值，随后执行容器构造。
7. 常量重复字典键由 analyzer 报错；动态重复键运行时由后写值覆盖先写值，并保持首次插入位置。
8. `Array` 与 `Array[Variant]` 等价；`Dictionary` 与 `Dictionary[Variant, Variant]` 等价。
9. nested typed container 不属于 Godot 4.5 正式支持面。

## 2. 冻结语义

### 2.1 类型结果

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

### 2.2 typed 上下文来源

当前支持的 expected-type 来源固定为：

1. 显式 typed local initializer。
2. 显式 typed property initializer。
3. assignment RHS 的 writable target type。
4. function return type。
5. 已选定 exact callable 的 fixed parameter type。
6. typed container 内嵌字面量的 element/key/value target，但仍受 nested typed container 禁止规则约束。
7. `value as Array[T]` / `value as Dictionary[K, V]` 的 cast target（先解析 target，再以 target 为 expected 解析 value；见 `frontend_cast_expression_implementation.md`）。

以下场景不提供 typed literal context：

- dynamic call 或无法发布 exact callable boundary 的调用。
- vararg tail 没有具体 element slot 类型时。
- 目标为 `Variant`。
- expression statement。
- 未记录 lambda / match / default-argument 上下文。已记录 lambda body 是 supported executable suite，其中的 typed literal 走普通 body 合同。

`ConditionalExpression` 本身不是独立的 typed-literal 来源，但会把外层 `expectedType` 原样转发给左右双臂（见 `frontend_conditional_expression_implementation.md` §2），因此 `var a: Array[int] = [1] if c else [2]` 的双臂容器字面量仍可获得 contextual typed context。

### 2.3 元素级兼容性

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

### 2.4 求值顺序

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

### 2.5 字典键

Parser 层负责：

- `{expr: value}` 的 key 是普通表达式。
- `{name = value}` 的 key 是 StringName 常量 `&"name"` 语义，不是变量读取。
- `{"name" = value}` 同样按 Lua-style StringName key 处理。
- 同一字典混用 `:` 与 `=` 必须产生 parse error。

Semantic 层负责直接可归约常量键的重复检查：

- 覆盖 `null`、bool、int、float、String、StringName、NodePath 直接字面量。
- String 与 StringName 使用 Godot 的 string-like key 等价规则判重。
- int `1` 与 float `1.0` 保持不同 key，不得因 Java 数值相等而合并。
- 非直接字面量 key 不做静态猜测，运行时后写覆盖先写。
- 重复键错误由 `FrontendTypeCheckAnalyzer` 以 `sema.type_check` 发布，锚定后出现的 key，并在消息中指出首次出现位置。

在完整 constant-expression evaluator 实现之前，不得声称覆盖函数常量、class constant 或容器下标常量键。

### 2.6 `openEnded`

`ArrayExpression.openEnded()` / `DictionaryExpression.openEnded()` 为 true 时，当前 AST 表示 `..` pattern opening。

普通 executable expression 中必须 fail-closed：

- shared semantic 发布 root-owned `FAILED` 或明确 `UNSUPPORTED`。
- 不允许将其解释为尾逗号。
- 不允许 lowering 忽略该字段继续构造普通容器。

`match` pattern 是 `openEnded` 的唯一合法消费上下文：ARRAY / DICTIONARY pattern 由 pattern-context 分派递归处理，不发布 `FrontendContainerLiteralPlan`；其解构已落地为 route-A 门闩链（typeof gate → 容器物化 → 长度门 → 逐元素/entry fetch 与递归子测试），`..` 不计入长度要求（无 `..` 为 `==`，有 `..` 为 `>=`）。详见 `frontend_match_statement_implementation.md`。

## 3. FrontendContainerLiteralPlan 与 expected-type 解析

### 3.1 Plan 事实

`FrontendContainerLiteralPlan` 由 `EXPR_TYPE` owner 发布，并存入 `FrontendAnalysisData.containerLiteralPlans()`：

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

`OperandRole` 固定为：

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
- plan 是唯一的元素边界 lowering fact；不新增平行的“literal result type”side table，字面量最终类型仍由 `expressionTypes()` 唯一发布，plan 只冻结元素边界和 lowering route。

side-table 发布管道覆盖 `FrontendAnalysisData`、`FrontendTypedLexicalEnvironment`、`FrontendExprTypePatch`、`FrontendOwnerPatch` 与 `FrontendPublishedFactTypeGuard`，并配有 stable-reference、merge-conflict、copy/idempotent tests。

### 3.2 expected-type-aware 解析

`BodyExpressionResolver` 在 Expression identity 缓存之外引入可选 expected type，但保持“每个 AST 最终只发布一个 expression type”。

expected-aware 解析固定经独立 functional interface，避免现有 method reference 通过 default method 静默丢失 expected type：

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

`BodyExpressionResolver` 的 expected-type 路径规则：

1. 普通表达式忽略 expected type，继续按现有语义求型。
2. Array literal 仅当 expected type 是 `GdArrayType` 时采用该类型，否则采用 generic Array。
3. Dictionary literal 仅当 expected type 是 `GdDictionaryType` 时采用该类型，否则采用 generic Dictionary。
4. expected type 与字面量 family 不匹配时，不强行改写 literal 类型，由外围 ordinary boundary 报错。
5. owner-local literal cache 使用 `(Expression identity, GdType expectedTypeOrNull)` 或等价 key，不能只按 Expression identity 命中，也不能只比较可能别名冲突的字符串。
6. `finalizedExpressionTypes` 与 stable published fact 仍只允许每个 Expression 一个最终结果；expected-aware key 只存在于 owner-local preview/retry cache。
7. 同一 AST 一旦 finalized，再以不同 expected type 请求时必须立即 fail-fast，包括 `typedEnvironment().expressionType(expression)` 的 published-first 命中路径。
8. speculative call candidate 检查禁止 `finalizeWindow=true`、禁止 `putExpressionType(...)`、禁止写 `containerLiteralPlans()`。
9. preliminary generic preview 不得进入 finalized cache；选定消费上下文后才允许 literal root finalize。

owner-local cache 固定拆分为三张 map：

```text
previewLiteralPlans[(expression identity, candidate target)] -> candidate-local preview
finalExpectedTypes[expression identity] -> 唯一 finalized expected type guard
finalizedExpressionTypes[expression identity] -> 唯一最终 expression fact
```

`resolveExpressionType(...)` 的 published-first 分支也必须调用 `checkExpectedTypeMatchesFinal(...)`，不得因为 stable fact 已存在就跳过 expected-type 冲突检查。

`FrontendContainerLiteralSemanticSupport` 是唯一的 shared semantic helper，职责限定为：

- 解析 generic/contextual construction type。
- 递归解析 element/key/value。
- 生成 `FrontendContainerLiteralPlan`。
- 提供 call-overload speculative compatibility/rank 计算。
- 检查 nested typed container 与 `openEnded` hard boundary。
- 不直接发 diagnostics，不写 stable side table。

local stabilization 只复用该 helper，不保留第二套 deferred/generic literal 规则。

### 3.3 expected type 传播点

| 消费点 | expected type 真源 |
| --- | --- |
| typed local/property initializer | published slot/declared type |
| inferred local initializer | null，结果保持 generic container |
| assignment RHS | frozen writable target type |
| return value | current callable return type |
| exact call argument | selected callable fixed parameter type |
| cast operand | resolved cast target type |
| nested Array element | outer Array element type |
| nested Dictionary key/value | outer Dictionary key/value target type |

assignment、call、cast 等 owner 各有自己的 resolver 路由，expected type 沿这些既有路由传入；不存在脱离 owner 的全 AST 后处理器。

return type 不等到 diagnostics-only `FrontendTypeCheckAnalyzer` 才回查，其数据通路固定为：

1. `FrontendCallableReturnTypeSupport` 统一按 callable owner 与已发布 class/function skeleton 读取 return slot。
2. `FrontendSuiteResolver` 创建 callable-root `FrontendSuiteContext` 时冻结 `currentCallableReturnType`。
3. `FrontendSuiteContext.withChildBlock(...)` 原样传递该类型。
4. property initializer context 的 `currentCallableReturnType` 为 null。
5. `publishRootExpressionTypes(...)` 处理 `ReturnStatement.value()` 时调用 expected-aware resolver。
6. `FrontendTypeCheckAnalyzer` 复用同一 helper 或已冻结结果，不维护语义可能漂移的第二套 return-slot 查找。

### 3.4 typed 容器 ABI 分类

纯类型分类器 `gd.script.gdcc.util.type.TypedContainerAbiSupport` 由 frontend plan 构造与 backend typed-container guard 共同消费：

- generic container：允许。
- engine/GDCC object leaf 与已支持 builtin leaf：允许。
- nested typed container leaf：拒绝。
- 当前无法表达 non-nil typed script identity 的 script leaf：拒绝。
- void、compiler-only、未知 ABI leaf：拒绝。

该 helper 只分类 type shape，不生成 C metadata。`CGenHelper` 继续负责渲染 hint/guard，但不得维护一套与 frontend 不一致的 supported-leaf 判断。

## 4. Overload resolution 合同

Call argument 中的 typed literal 采用两阶段处理，覆盖 bare call、attribute/chain call 和 constructor/static call 三条真实 owner 路径：

1. Candidate preview：对每个候选 parameter type 计算 literal element boundary decision 和 specificity rank，不发布 expression type/plan。
2. Candidate selection：bare call 在 `FrontendExpressionSemanticSupport` 内选择；attribute/chain call 在 `FrontendChainReductionHelper` / `ScopeMethodResolver` 路径选择；两者调用同一个 literal preview helper。
3. Candidate publication：CHAIN_BINDING 可以先发布 selected `FrontendResolvedCall`，但不得发布 EXPR_TYPE-owned literal plan。
4. Candidate finalize：EXPR_TYPE owner 使用 selected exact parameter type 重新解析 literal argument，发布唯一 contextual expression type 和 plan。
5. `FrontendResolvedCall.argumentTypes()` 必须记录 contextual literal type，例如 `Array[int]`，不能保留 preliminary generic Array snapshot。

三条 finalize 入口的固定合同：

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

具体约束：

1. `FrontendBodyOwnerProcedures.resolveAttributeExpressionType(...)` 不得在 `reduceAttributeExpression(...)` 前对所有 call argument 无条件调用 `resolveExpressionType(argument, finalizeWindow)`；exact call step 必须按已发布 fixed parameter type 调 contextual resolver。
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

实现落点（bare / constructor / chain instance / chain static **共用**）：

- 聚合：`FrontendCallableLiteralArgumentSupport.literalAggregateRank(...)` → `CandidateRank(worst, total)`
- 比较：`FrontendContainerLiteralSemanticSupport.compareCandidateRanks(...)`（经 `isStrictlyMoreSpecificByLiteralAggregate`）
- chain 在 `ScopeMethodResolver` 中通过 `CandidateSpecificity` 注入该比较器；`ParameterCompatibilityRank` 只负责 applicable（reject 为 0），不再用 packed int 的 min/sum 做候选级 specificity。

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

scope 包无 parser AST 依赖：applicability rank 经 index-aware callback 注入；specificity 经 AST-free `CandidateSpecificity` 注入（frontend 可闭包 call-site expressions）。`exactCallableBoundary()` 与 `argumentTypes()` 职责分离；constructor RESOLVED 可发布 `exactCallableBoundary`。chain 聚合固定参列表取自 `ScopeResolvedMethod.parameters()`（已剥离 synthetic `self`），与 bare 的 user-visible 参数视图一致。同 rank typed-container overload 保持 ambiguity（object 路径 dynamic fallback；不按声明顺序任选）。

## 5. Type-check 诊断

`FrontendTypeCheckAnalyzer` 消费容器字面量 plan：

- 对 `REJECT` array element 发 `sema.type_check` error。
- 对 `REJECT` dictionary key/value 发 `sema.type_check` error。
- 对 nested typed container 发 `sema.type_check` error。
- 对直接可归约重复 key 发 `sema.type_check` error。
- upstream 已有 error 的 element/key/value 不重复发错。
- 一个坏 literal 不阻止同 module 其他 subtree 继续分析。

文案与 Godot 语义对齐：

```text
Cannot have an element of type "X" in an array of type "Array[Y]".
Cannot have a key of type "X" in a dictionary of type "Dictionary[K, V]".
Cannot have a value of type "X" in a dictionary of type "Dictionary[K, V]".
Key "X" was already used in this dictionary; first occurrence is at ...
```

普通 literal root 即使存在 element-level REJECT，也保留 contextual result type；type-check error 负责阻止 compile，lowering 不处理 error recovery。

## 6. CFG 与 body lowering

### 6.1 专用 item

`ContainerLiteralItem` 是 Array/Dictionary literal 共用的专用 CFG value item，不复用 `OpaqueExprValueItem`：

```java
public record ContainerLiteralItem(
        @NotNull Expression expression,
        @NotNull List<String> operandValueIds,
        @NotNull String resultValueId
) implements ValueOpItem {}
```

构造器验证 expression 只能是 `ArrayExpression` 或 `DictionaryExpression`。

采用一个 item 而不是 Array/Dictionary 两个 item 的原因：

- 两者都遵守“所有 child 先求值，再构造一个 result”的同一 CFG 合同。
- family 可由 expression 和 `FrontendContainerLiteralPlan.resultType()` 双重校验。
- Dictionary 的扁平 operand 顺序由 plan 与 AST entry 数量共同校验。
- 减少 sealed hierarchy、registry 与 materialization switch 的平行样板。

### 6.2 builder 路由

`FrontendCfgGraphBuilder.buildValue(...)` 固定路由：

- `case ArrayExpression -> buildArrayLiteralValue(...)`
- `case DictionaryExpression -> buildDictionaryLiteralValue(...)`

Array builder 按 `elements()` 顺序调用 `buildValue`，收集每个 child result value id，最后 append `ContainerLiteralItem`。Dictionary builder 按 `entries()` 顺序遍历，每项先 build key、再 build value，按 key0/value0/key1/value1 顺序收集 value id，最后 append `ContainerLiteralItem`。

builder 必须读取并验证 `analysisData.containerLiteralPlans()[literal]`：

- plan 必须存在。
- plan result type 必须与 `expressionTypes()[literal]` 相同。
- plan operand 数量、role 和源码顺序必须一致。
- plan 不得包含 `REJECT`；若包含，说明 compile error gate 被绕过，应 fail-fast。

### 6.3 materialization 与 processor

`FrontendBodyLoweringSupport.collectProducedValueMaterialization(...)` 为 `ContainerLiteralItem` 声明一个独立 `cfg_tmp_*` result slot，类型取自 literal 的 published expression type。

`FrontendSequenceItemInsnLoweringProcessors` 注册专用 processor：

1. 逐 operand 读取 plan 的 source/target/decision。
2. 调用现有 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`。
3. 收集 materialized operand variable id。
4. 发射 `ConstructContainerLiteralInsn`。

禁止：

- 在 processor 中重新调用 semantic helper 决定兼容性。
- 在 processor 中重新遍历 child AST 求值。
- 把 literal 回退到 opaque expression handler。
- 让 generic literal operands 绕过 `target=Variant` 的 pack materialization。

`classifyOpaqueExpression(...)` 对 Array/Dictionary 固定为 protocol violation（`REJECT`，dedicated-item-only），不是 `DEFER`。

## 7. LIR 合同

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

不得修改它们的 operand 数量或语义，不得通过给现有指令追加 variable varargs 的方式实现字面量。

### 7.2 `construct_container_literal`

```text
$result = construct_container_literal $operand0 $operand1 ...
```

```java
public record ConstructContainerLiteralInsn(
        @Nullable String resultId,
        @NotNull List<Operand> operands
) implements ConstructionInstruction {}
```

合同：

- `GdInstruction.CONSTRUCT_CONTAINER_LITERAL` 为 pure `VARARGS`，min=0；parser 强制 varargs 为 `VARIABLE`。
- result variable type 为 `GdArrayType` 时，所有 operands 按数组元素顺序解释。
- result variable type 为 `GdDictionaryType` 时，operands 按 key0/value0/key1/value1 解释；**偶数数量由 C backend 校验**，LIR record/parser 不因奇数 fail-fast（frontend CFG 已按 entry 对发布）。
- result variable 不得为 ref（backend 校验）。
- result variable 不得是 Packed*Array 或其他 builtin（backend 校验）。
- operands 全部必须是 `VariableOperand`（record 构造 + parser varargs kind）。
- 每个 operand 可以是任意 source-facing GdType；backend 负责临时 pack 为 Variant 后写入容器。
- 空 operands 合法，family 由 result variable type 决定。

采用单一 opcode 的原因：

- empty Array/Dictionary 可由 result type 无歧义区分。
- 不复制两套完全相同的 varargs parser/serializer 合同。
- 不污染已冻结的 `construct_array` / `construct_dictionary`。

## 8. C backend

backend 维护文档为 `doc/module_impl/backend/construct_container_literal_implementation.md`；本节记录 frontend 视角的合同边界。

### 8.1 generator 划分

`ContainerLiteralInsnGen` 只负责 `CONSTRUCT_CONTAINER_LITERAL`。字面量逻辑不并入 `ConstructInsnGen`；后者继续维护已有空构造与 object/builtin construction 合同。

### 8.2 Array 路径

1. 验证 result 是 non-ref `GdArrayType`。
2. 调用 `CBuiltinBuilder.constructBuiltin(..., List.of())` 构造正确的 generic/typed 空 Array。
3. 按 operand 顺序调用 `InsnGenSupport.materializeVariantOperand(...)`。
4. 调用仓库自维护 wrapper `src/main/c/codegen/include_451/godot/godot_builtin.h` 中的 `godot_Array_push_back(...)`。
5. 每次 append 后立即销毁 generator-local Variant temp。

typed Array 的元素在 frontend body lowering 已先 materialize 到目标元素类型，因此 backend append 只负责 Variant carrier，不负责重新决定类型转换。`godot_Array_push_back(...)` 返回 `void`，不能伪造与 Dictionary 对称的返回值检查；合法程序依赖 frontend 已冻结的目标类型/materialization 合同和 Godot typed Array 校验。无论引擎是否报告 typed write error，generator-local Variant temp 都必须按正常顺序销毁。

### 8.3 Dictionary 路径

1. 验证 result 是 non-ref `GdDictionaryType`。
2. 验证 operand 数量为偶数。
3. 调用 `CBuiltinBuilder.constructBuiltin(..., List.of())` 构造正确的 generic/typed 空 Dictionary。
4. 按 key/value pair 顺序分别 materialize Variant operand。
5. 调用仓库自维护 wrapper `godot_Dictionary_set(...)`。
6. 检查返回的 `godot_bool`；false 视为 runtime container write failure，打印明确错误并走现有 failure cleanup/return 路径。
7. 每个 pair 写入后立即销毁 key/value generator-local Variant temp。

动态重复 key 必须自然表现为后写覆盖先写；不得在 backend 人为拒绝。

### 8.4 生命周期

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

`Array[Variant]` 与 `Dictionary[Variant, Variant]` 必须走 plain container 路径（`godot_new_Array()` / `godot_new_Dictionary()`），不生成 typed metadata。

engine test 必须验证 typed fill 后 `is_typed()`、typed element/key/value metadata 与实际读取值，不能只比较生成 C 字符串。

## 9. Compile-only 边界

`FrontendCompileCheckAnalyzer.walkExpression` **不再** 为 `ArrayExpression` / `DictionaryExpression` 建立显式 blocker；两者进入 default compile surface recursion。

- 字面量不产生 `sema.compile_check`。
- 当前仍被显式 intercept 的表达式固定为 `PreloadExpression`、`GetNodeExpression`。`ConditionalExpression` 已离开 intercept（见 `frontend_conditional_expression_implementation.md`）。已记录 `LambdaExpression`（published plan + body）已纳入 compile surface 并递归扫描 body；未记录 lambda 保持 fail-closed。合同见 `frontend_lambda_implementation.md`。
- 移除 Array/Dictionary blocker 时不得顺带改变其他 feature 的 intercept 状态。

## 10. 核心实现落点

- `src/main/java/gd/script/gdcc/frontend/sema/FrontendContainerLiteralPlan.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendContainerLiteralSemanticSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendCallableLiteralArgumentSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendConstructorResolutionSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendChainReductionHelper.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendCallableReturnTypeSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendBodyOwnerProcedures.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendTypeCheckAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendAnalysisData.java` / `FrontendTypedLexicalEnvironment.java` / `FrontendResolvedCall.java`
- `src/main/java/gd/script/gdcc/frontend/sema/patch/FrontendExprTypePatch.java` / `FrontendOwnerPatch.java` / `FrontendPublishedFactTypeGuard.java`
- `src/main/java/gd/script/gdcc/util/type/TypedContainerAbiSupport.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/item/ContainerLiteralItem.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilder.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/FrontendBodyLoweringSupport.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendBodyLoweringSession.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`
- `src/main/java/gd/script/gdcc/lir/insn/ConstructContainerLiteralInsn.java`
- `src/main/java/gd/script/gdcc/enums/GdInstruction.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/ContainerLiteralInsnGen.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`

## 11. 回归锚点

Focused tests：

- `FrontendContainerLiteralParseBehaviorTest` — parser 形状（gdparser 0.5.3 契约）
- `FrontendContainerLiteralSemanticSupportTest` — generic/contextual shared semantic 与 rank
- `FrontendCallableLiteralArgumentSupportTest` — preview/rank/rewrite 共享 helper
- `TypedContainerAbiSupportTest` — typed-container ABI 分类
- `FrontendExpressionSemanticSupportTest` / `FrontendAssignmentSemanticSupportTest` — expected-aware publication
- `FrontendBodyOwnerProceduresExprTypeTest` / `FrontendBodyOwnerProceduresVarTypePostTest` / `FrontendBodyOwnerProceduresChainBindingTest` — contextual initializer/return/assign/cast/call、chain three-way consistency、same-rank ambiguity
- `FrontendResolvedCallTest` — `argumentTypes()` contextual rewrite 与 `exactCallableBoundary`
- `FrontendAnalysisDataTest` / `FrontendTypedLexicalEnvironmentTest` — plan side-table 发布/合并
- `FrontendTypeCheckAnalyzerTest` — REJECT 与 duplicate-key 诊断
- `FrontendCompileCheckAnalyzerTest` — 无 Array/Dictionary blocker；其余 intercept 仍封口
- `FrontendCfgGraphBuilderContainerLiteralTest` / `FrontendCfgGraphTest` / `FrontendLoweringBuildCfgPassTest` — CFG 源序、single producer、plan 校验
- `FrontendBodyLoweringSupportTest` / `FrontendContainerLiteralInsnLoweringTest` — materialization 与 plan-driven emit
- `ConstructContainerLiteralInsnContractTest` — LIR parser/serializer round-trip 与合同边界
- `CContainerLiteralInsnGenTest` / `CContainerLiteralInsnGenEngineTest` — codegen 合同与 engine 填充路径（含 typed metadata、dynamic duplicate key 顺序与最终值）
- `CConstructInsnGenTest` / `CPackUnpackVariantInsnGenTest` — 既有空构造与 pack/unpack 合同不回退

test-suite e2e 对（`src/test/test_suite/unit_test/{script,validation}/collection/`）：

- `array_literal_roundtrip`
- `dictionary_literal_roundtrip`
- `container_literal_evaluation_order`
- `container_literal_nested_untyped`
- `typed_container_literal_boundaries`
- `for_in_container_literal`

## 12. 长期维护约束

1. 不得从字面量元素反推 typed container；generic/contextual 结果类型的唯一决定因素是上下文（§2.1）。
2. 不得在 GDCC semantic 层通过 source text 猜测字典 `:` / `=` 风格；风格语义由 gdparser 负责（§1.1）。
3. `openEnded` 只表示 `..` pattern opening；普通表达式中必须 fail-closed，不得当作尾逗号（§2.6）。
4. candidate preview 不写 finalized cache / stable side table；selected candidate 才 finalize；同 AST conflicting expected type 必须 fail-fast，不得通过放宽冲突覆盖来“修复”（§3.2）。
5. plan result type 直接取 contextual target；容器级 covariance 不得替代元素级 materialization（§2.3）。
6. 不得给 `construct_array` / `construct_dictionary` 追加 varargs；字面量只走独立 `construct_container_literal`（§7.1）。
7. LIR/backend 只接受已求值 variable operands；CFG 形状测试与 runtime side-effect 测试双重锚定求值顺序（§2.4）。
8. typed-container ABI 必须在 frontend fail-closed 并由 type-check 报源码诊断，不得泄漏到 backend 才失败（§3.4 / §8.5）。
9. backend 每元素临时 Variant 必须销毁；object element 不得被重复 own/release（§8.4）。
10. compile gate 放行状态只随全链路合同变化；移除/恢复 blocker 时不得回退其他 feature（例如 Cast）的放行状态；blocker 相关测试使用 feature-specific anchor，避免只断言总数（§9）。
11. 元素级兼容性不新增 conversion rule；扩展必须先改 `frontend_implicit_conversion_matrix.md` 事实源，再改代码。
12. 若本合同变化，必须同步本文档、`gdcc_low_ir.md`、`construct_container_literal_implementation.md`、compile-check / type-check / chain-binding / lowering 相关事实源文档与 `frontend_rules.md`，以及对应 targeted/test-suite 测试。
