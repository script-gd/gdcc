# Int To Float 隐式转换实施计划

## 文档状态

- 状态：Planned
- 范围：frontend ordinary typed boundary、LIR `call_intrinsic` 物化、C backend intrinsic codegen
- 更新时间：2026-05-05
- 关联文档：
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/cbodybuilder_implementation.md`
  - `doc/module_impl/backend/call_global_implementation.md`
  - `doc/module_impl/backend/call_method_implementation.md`

## 目标

实现 source-level ordinary typed boundary 中的 `int -> float` 隐式转换，使以下边界在语义阶段被接受，并在 lowering 阶段显式物化为 `float` slot：

1. `var x: float = 1`
2. `x: float; x = 1`
3. `func f() -> float: return 1`
4. `func take_float(v: float): ...; take_float(1)`
5. `Dictionary[float, V]` 的 key 边界接收 `int` key

该能力只覆盖 `int -> float`，且必须通过 shared frontend boundary helper 统一生效；不得在 assignment、call、return、subscript 等 consumer 中各自硬编码转换规则。

## 非目标

以下行为在本计划中必须继续禁止：

1. `float -> int`，包括 `Array` / packed array index 中以 `float` 下标自动转 `int`。
2. `bool -> int`、`bool -> float`、`int -> bool`、`float -> bool`。
3. `String <-> StringName`、`String -> int`、`String -> float`。
4. unary / binary operator numeric promotion，例如不因为本计划而放开 `int + float` 的新语义路径。
5. `Array` 和 `Dictionary` 的递归数值协变，例如 `Array[int] -> Array[float]`、`Dictionary[int, V] -> Dictionary[float, V]`、`Dictionary[K, int] -> Dictionary[K, float]`。
6. 修改 `ClassRegistry.checkAssignable(...)` 来承载 numeric promotion。
7. 新增后端指令或新增类似 `PrimitiveCastInsn` 的 LIR opcode。
8. 把 `int -> float` 偷渡为 builtin constructor special route。

## Godot 行为依据

Godot 的 `GDScriptAnalyzer::check_type_compatibility(...)` 在允许隐式转换时，对 builtin source/target 使用 `Variant::can_convert_strict(source, target)` 判断兼容性。Godot `Variant::can_convert_strict(...)` 中，目标 `FLOAT` 的 strict valid sources 包含 `INT`。

本计划只采用这条 `INT -> FLOAT` 兼容事实，不导入同一 Godot strict 表中的其它转换。这样可以缩小 first step 的 blast radius，并保持 GDCC 现有 frontend ordinary boundary 的显式矩阵管理方式。

## 当前 GDCC 约束

本计划实施前的相关实现约束是：

1. `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)` 是 ordinary typed boundary 的 shared semantic gate。
2. 现有 decision 只有 `ALLOW_DIRECT`、`ALLOW_WITH_PACK`、`ALLOW_WITH_UNPACK`、`ALLOW_WITH_LITERAL_NULL`、`REJECT`。
3. `int -> float` 会落到 `ClassRegistry.checkAssignable(...)`，因此被判为 `REJECT`。
4. `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 只显式物化 pack、unpack、`Nil -> object`；其它 accepted case 直接返回 source slot。
5. LIR 已有 `CallIntrinsicInsn` 和 `CALL_INTRINSIC` opcode，simple/DOM parser 与 serializer 已能处理该表面。
6. C backend 尚未注册 `CALL_INTRINSIC` 的 `CInsnGen`；当前 `CALL_INTRINSIC` 进入 C codegen 会报 unsupported opcode。
7. `ClassRegistry.checkAssignable(...)` 是全局 strict assignability 真源，只覆盖同类型、对象上行转换和既有容器协变；numeric promotion 不属于这里。

## 目标设计

### 1. Frontend decision

在 `FrontendVariantBoundaryCompatibility.Decision` 中新增：

```java
ALLOW_WITH_PRIMITIVE_CAST
```

该 decision 表达：

1. source-level ordinary typed boundary 合法。
2. source slot 不能直接流入 target slot。
3. lowering 必须生成新的 target-typed temp，并用显式 primitive cast materialization 填充该 temp。

第一阶段只让 `source instanceof GdIntType && target instanceof GdFloatType` 返回 `ALLOW_WITH_PRIMITIVE_CAST`。其它 scalar pair 仍按原路径进入 `ClassRegistry.checkAssignable(...)` 或 `REJECT`。

`ALLOW_WITH_PRIMITIVE_CAST` 不得作为 `ALLOW_DIRECT` 的别名。它必须在 lowering 层可见，否则 `int` slot 会被直接拿去写 `float` slot。

### 2. Lowering materialization

`FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 在收到 `ALLOW_WITH_PRIMITIVE_CAST` 且 source/target 为 `int -> float` 时：

1. 生成新的 boundary temp slot，类型为 `GdFloatType.FLOAT`。
2. 追加 `CallIntrinsicInsn`：

```java
new CallIntrinsicInsn(
        castedSlotId,
        "c_int_to_float",
        List.of(new LirInstruction.VariableOperand(sourceSlotId))
)
```

最后返回 `castedSlotId`，后续 assignment / call / return / subscript consumer 只消费这个 `float` slot。

这条路线复用已有 LIR `CALL_INTRINSIC`，不新增后端指令，也不引入新的 cast opcode。

### 3. LIR surface

现有 `call_intrinsic` 文本形态保持不变：

```text
$<result_id> = call_intrinsic "<intrinsic_name>" $<arg1_id> ...
```

本计划约定 `c_int_to_float` 的合法形态为：

```text
$<float_result> = call_intrinsic "c_int_to_float" $<int_source>;
```

`CALL_INTRINSIC` 的 LIR metadata 允许 optional result，这是 LIR opcode 的通用表面；具体 intrinsic 是否允许空返回值由该 intrinsic 自己定义。`c_int_to_float` 是 ordinary boundary materialization，用于产生后续 consumer 必须读取的 `float` slot，因此 frontend lowering 生成该 intrinsic 时必须提供 result。

C backend 必须把手工构造的 `resultId == null` `c_int_to_float` 视为坏 IR 并 fail-fast。这样比 no-op discard 更适合本计划，因为 conversion intrinsic 当前不是 side-effect API。

### 4. C backend intrinsic 分派

新增以下 backend C codegen 结构：

- `gd.script.gdcc.backend.c.gen.CIntrinsicManager`
   - 作为 `CGenHelper` 的成员创建。
   - 负责注册 backend-owned intrinsic functions。
   - 使用 `Map.of(...)` 保存当前 intrinsic 注册表；注册表无需维持顺序。
   - 提供 `find(...)` 入口；unknown intrinsic 的空结果由调用者结合当前 instruction context 负责报错。

- `gd.script.gdcc.backend.c.gen.CIntrinsicFunction`
   - 接口由用户指定新增，放在 `gdcc.backend.c.gen` 包。
   - 接收已经解析好的 result slot 和 argument slots，避免每个 intrinsic 重复解析 `CallIntrinsicInsn` 的 result/operand。
   - result slot 使用 nullable 形态，因为 LIR intrinsic 返回值可空性因具体 intrinsic 而异。
   - 方法职责保持很窄：

```java
@NotNull String name();

void generateCCode(@NotNull CBodyBuilder bodyBuilder,
                   @Nullable LirVariable resultVar,
                   @NotNull List<LirVariable> argVars);
```

- `gd.script.gdcc.backend.c.gen.intrinsic.CIntToFloatIntrinsic`
   - 实现 `CIntrinsicFunction`。
   - 校验 exactly one argument slot。
   - 校验 source slot 类型为 `GdIntType.INT`。
   - 校验 result slot 存在、非 `ref`、类型为 `GdFloatType.FLOAT`。
   - 通过 `CBodyBuilder.valueOfCastedVar(sourceVar, GdFloatType.FLOAT)` 与 `CBodyBuilder.assignVar(target, value)` 生成实际 C cast。

- `gd.script.gdcc.backend.c.gen.insn.CallIntrinsicInsnGen`
   - 注册 `GdInstruction.CALL_INTRINSIC`。
   - 从当前 instruction 读取 intrinsic name。
   - 委托 `bodyBuilder.helper().intrinsicManager().find(...)` 查找实现。
   - 对 unknown intrinsic 报 `InvalidInsnException`，错误信息包含 intrinsic name。
   - 统一解析 result slot 和 variable argument slots，再传给 `CIntrinsicFunction`。

### 5. C 代码形态

`CIntToFloatIntrinsic` 生成的 C 代码应等价于：

```c
$target = (godot_float)$source;
```

实际实现不应手写 `"(godot_float)$" + sourceId` 字符串，而应复用 `CBodyBuilder.valueOfCastedVar(...)` 和 `assignVar(...)`。这样可以沿用现有 target 校验、初始化状态、非对象 slot 写入策略和错误定位。

## 分步骤实施

### Step 1. 更新文档真源

状态：Done（2026-05-05）。已同步 frontend conversion matrix、ordinary boundary materialization 合同、frontend rules 与 type-check analyzer 事实源；这些文档现在把 `int -> float` 表达为 `ALLOW_WITH_PRIMITIVE_CAST`，并保留其它 scalar conversion、recursive primitive container widening、`float -> int` array index 的拒绝基线。

修改 `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`：

1. 将 `int -> float` 从 `GDCC N` 改为 `GDCC Y`。
2. 在注释中写明该转换由 `ALLOW_WITH_PRIMITIVE_CAST` 表达，lowering 生成 `call_intrinsic "c_int_to_float"`。
3. 明确其它 scalar conversion 仍为 `N`。
4. 明确 `Array` / `Dictionary` 不做递归 primitive widening。
5. 明确 `Dictionary[float, V]` key boundary 接收 `int` 是 ordinary typed boundary 的直接结果；但 `Array` / packed array 的 `float` index 仍禁止，因为那需要 `float -> int`。

同步修改 `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`：

1. 将 ordinary boundary materialization 支持面扩展到 `ALLOW_WITH_PRIMITIVE_CAST`。
2. 保留 pack/unpack/null-object 的既有描述。
3. 写明 `int -> float` 不走 builtin constructor special route。
4. 写明 lowering 不得在 consumer 层绕过 shared helper。

同步复核并更新以下事实源，避免保留旧状态描述：

1. `doc/module_impl/frontend/frontend_rules.md`
   - 将 subscript key/index 的旧基线收窄为：`Dictionary[float, V]` key boundary 接收 `int`，但 `Array` / packed array `float` index 仍禁止。
   - 保留 builtin unary-`Variant` constructor special route 与 ordinary typed-boundary conversion 的分离描述。
2. `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
   - 移除或改写“不放宽 `int -> float`”的旧非目标描述。
   - 保持 type-check analyzer 只消费 `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)`，不维护平行 conversion 表。
3. 本计划文档
   - 清理重复标题和已经被本次修订替换的旧假设。
   - 明确 `c_int_to_float` 属于 C backend-owned intrinsic namespace；其它 backend 若未来出现，应显式决定是否复用该 intrinsic 名称或映射到自己的 backend intrinsic。

### Step 2. 扩展 frontend compatibility decision

状态：Done（2026-05-05）。`FrontendVariantBoundaryCompatibility.Decision` 已新增 `ALLOW_WITH_PRIMITIVE_CAST`，shared helper 只在 `int -> float` 上返回该 decision；`ClassRegistry.checkAssignable(...)` 保持 strict contract。为保持 Java switch expression 穷尽性，constructor decision rank 已加入该 decision，但 bare call / method resolver 的完整 specificity 仍保留给 Step 3。已运行 `script/run-gradle-targeted-tests.sh --tests FrontendVariantBoundaryCompatibilityTest,ClassRegistryTest`。

修改 `FrontendVariantBoundaryCompatibility`：

1. 在 `Decision` enum 中新增 `ALLOW_WITH_PRIMITIVE_CAST`。
2. 在 `determineFrontendBoundaryDecision(...)` 中，在 `Variant` pack/unpack 和 `Nil -> object` 之后、`ClassRegistry.checkAssignable(...)` 之前增加 `int -> float` 判定。
3. `isFrontendBoundaryCompatible(...)` 不需要额外分支，只要 `Decision.allows()` 继续以 `REJECT` 为唯一 false。
4. 更新类注释，列出新的 decision，并指向 conversion matrix 和 lowering 文档。

不要修改 `ClassRegistry.checkAssignable(...)`。该函数的 strict contract 必须保持不变。

### Step 3. 更新 call / method / constructor specificity

状态：Done（2026-05-05）。

产出：

- frontend boundary specificity rank 已收敛到 `FrontendVariantBoundaryCompatibility`，并由 bare call、constructor resolver 与 method resolver frontend path 共同消费。
- `ScopeMethodResolver` 新增显式命名的 rank-aware 入口，避免用同形态 lambda overload 表达 frontend-only ranking；strict `ClassRegistry.checkAssignable(...)` 和默认 resolver 路径仍不接受 `int -> float`。
- `FrontendChainReductionHelper` 的 instance/static method frontend path 已改为传入 rank，而不是只传 allow/reject predicate。

验证：

- `script/run-gradle-targeted-tests.sh --tests FrontendExpressionSemanticSupportTest,ScopeMethodResolverTest,FrontendConstructorResolutionSupportTest`
- `script/run-gradle-targeted-tests.sh --tests FrontendVariantBoundaryCompatibilityTest,ClassRegistryTest,FrontendChainReductionHelperTest`

维护约束：

- `FrontendCallableOverloadRankingSupport.selectMostSpecificApplicable(...)` 是 frontend callable overload ranking 的 shared dominance-selection 真源，当前由 bare call 与 constructor resolution 共同使用。
- 后续新增 frontend callable overload path 时，如果语义仍是“唯一非支配候选胜出，多个非支配候选保持 ambiguous”，必须复用该 helper；不得在新 resolver 中再复制一份 `selectMostSpecificApplicable*` 循环。
- 各调用点仍应保留自己的 argument-specific comparison 规则。该 helper 只承载通用选择结构，不承载 bare call、constructor 或 method resolver 的具体 specificity 语义。

新增或抽出一个 frontend boundary specificity 规则，供 bare call、method resolver frontend path、constructor resolver 共同使用。该规则只比较已经通过 applicability 过滤的候选，不改变默认 strict resolver 的兼容范围。

推荐 decision rank：

- `ALLOW_DIRECT -> 4`
- `ALLOW_WITH_LITERAL_NULL -> 3`
- `ALLOW_WITH_PRIMITIVE_CAST -> 2`
- `ALLOW_WITH_PACK, ALLOW_WITH_UNPACK -> 1`
- `REJECT -> 0`

规则含义：

1. `ALLOW_DIRECT` 仍最高。
2. `ALLOW_WITH_LITERAL_NULL` 继续高于 runtime-open pack/unpack。
3. `ALLOW_WITH_PRIMITIVE_CAST` 高于 `ALLOW_WITH_PACK` / `ALLOW_WITH_UNPACK`，因为 primitive cast 是静态、确定、非 runtime-open 的转换。
4. `ALLOW_WITH_PRIMITIVE_CAST` 低于 `ALLOW_DIRECT`，确保 `int` 实参在 `int` 与 `float` 候选同时存在时选择 `int`。

需要修改的路径：

1. `FrontendConstructorResolutionSupport.decisionSpecificityRank(...)`
   - 加入 `ALLOW_WITH_PRIMITIVE_CAST`。
   - 保持 existing constructor ranking 的“不可更差且至少一项更好”选择规则。
2. `FrontendExpressionSemanticSupport.selectCallableOverload(...)`
   - 不能继续在 `applicable.size() > 1` 时直接报 ambiguous。
   - 应按每个实参的 boundary decision specificity 选择唯一最优候选。
   - 需要保持 exact match 胜过 primitive cast，primitive cast 胜过 `Variant` pack。
3. `ScopeMethodResolver`
   - 默认 `resolveInstanceMethod(...)` / `resolveStaticMethod(...)` 仍使用 `ClassRegistry.checkAssignable(...)`，因此继续拒绝 `int -> float`。
   - 传入 frontend compatibility predicate 的 overload 必须有同等 specificity 排序；否则 frontend chain/member call 会在 `method(int)` 与 `method(float)` 同时存在时变成 ambiguous 或 dynamic fallback。
   - 若 `BiPredicate<GdType, GdType>` 只能表达 allow/reject，不足以表达 rank，应新增一个 frontend 专用解析入口或可选 rank provider，而不是把 primitive rank 硬编码到 strict resolver。

### Step 4. Lowering 生成 `call_intrinsic`

状态：Done（2026-05-05）。

已完成：

- `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 已改为先消费 `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)`，再按 decision 物化 `ALLOW_DIRECT` / pack / unpack / literal-null / primitive-cast / reject。
- `ALLOW_WITH_PRIMITIVE_CAST` 当前只接受 `int -> float`，并生成 `CallIntrinsicInsn(..., "c_int_to_float", ...)` 到新的 `float` temp；其它 primitive-cast pair 会 fail-fast。
- writable-route subscript leaf / reverse-commit step 已携带 receiver/key 类型，read/write 发指令前通过同一个 key/index materialization helper 选择最终 access kind。
- CFG 发布的 writable subscript access kind 已改为按 materialized key/index type 计算，避免 `Variant -> int` array index 在 body lowering 前被误冻成 generic route。

验证：

- `script/run-gradle-targeted-tests.sh --tests FrontendBodyLoweringSessionTest,FrontendLoweringBodyInsnPassTest,FrontendSubscriptSemanticSupportTest,FrontendWritableRouteSupportTest,FrontendLoweringBuildCfgPassTest`

修改 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`：

1. 先通过 `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(...)` 获取 decision，避免 lowering 侧重复手写完整转换矩阵。
2. 对 `ALLOW_DIRECT` 返回 source slot。
3. 对 `ALLOW_WITH_PACK`、`ALLOW_WITH_UNPACK`、`ALLOW_WITH_LITERAL_NULL` 保持现有物化逻辑。
4. 对 `ALLOW_WITH_PRIMITIVE_CAST`：
   - 校验当前唯一支持 pair 为 `GdIntType.INT -> GdFloatType.FLOAT`。
   - 创建 `GdFloatType.FLOAT` temp。
   - 追加 `CallIntrinsicInsn(temp, "c_int_to_float", List.of(new VariableOperand(sourceSlot)))`。
   - 返回 temp。
5. 对 `REJECT` fail-fast，错误信息包含 boundary use、source type、target type。正常语义流程不应让 rejected boundary 到达 lowering；这里是协议保护。

local initializer、assignment final store、return、call fixed argument、call vararg 继续通过既有 `materializeFrontendBoundaryValue(...)` 路径消费，不新增局部分支。

Subscript key/index 需要单独补齐 lowering materialization：

1. 语义层 `FrontendSubscriptSemanticSupport.resolveSubscriptType(...)` 已经用 shared helper 检查 provided key/index type 与 container key/index type。
2. lowering 层必须在读写 subscript 前，将 key/index operand 也通过 `materializeFrontendBoundaryValue(...)` 物化到 container key/index type。
3. materialized key/index type 必须参与 access-kind selection；不能先用原始 source key type 选择 `INDEXED` / `KEYED` / `NAMED` / `GENERIC`，再事后转换。
4. `Dictionary[float, V]` 使用 `int` key 时，key operand 必须先生成 `float` temp，再传给 `VariantGetKeyedInsn` / `VariantSetKeyedInsn` 或 writable-route subscript leaf。
5. `Array[T]` / packed array 的 key type 是 `int`。本计划不会让 `float` index 通过，因为那需要 `float -> int`；但现有 ordinary `Variant -> int` boundary 已经可能让 `array[variant_index]` 在语义层通过，因此 lowering 必须先生成 `UnpackVariantInsn(int_index, variant_index)`，再选择并发出 `VariantGetIndexedInsn` / `VariantSetIndexedInsn`。
6. `Dictionary[Variant, V]` 等 target key 为 `Variant` 的路径也应走同一个 key materialization helper；stable key 需要 pack 成 `Variant` key slot，除非后端 keyed instruction 已经明确把该 key operand 作为 `Variant` boundary materialization 处理。实现时只能保留一个明确真源，不能让 frontend lowering 和 backend keyed codegen 各自隐式转换。
7. writable-route reverse commit 若复用 key operand，也必须消费同一个 materialized key slot，避免 read/write 使用不同 key 形态。

### Step 5. 补齐必要的 LIR `call_intrinsic` 测试

状态：Done（2026-05-06）。

已完成：

- `SimpleLirBlockInsnParserTest` 覆盖 `$f = call_intrinsic "c_int_to_float" $i;` 正向解析，并断言 result、intrinsic name 与变量参数。
- `SimpleLirBlockInsnParserTest` 覆盖 intrinsic name 未加引号时的解析错误，避免 `c_int_to_float` 被误收为普通 identifier。
- `SimpleLirBlockInsnParserTest` 覆盖 vararg 使用 literal 而非 `$var` 时的解析错误，锚定 intrinsic arguments 必须是已物化 LIR slot 的合同。
- `SimpleLirBlockInsnSerializerTest` 覆盖 `CallIntrinsicInsn("f", "c_int_to_float", List.of(new VariableOperand("i")))` 的 simple 文本序列化。
- 现有 parser / serializer 实现已满足本步骤测试，无需新增 LIR opcode 或修改 DOM 通道。

验证：

- `script/run-gradle-targeted-tests.sh --tests SimpleLirBlockInsnParserTest,SimpleLirBlockInsnSerializerTest`

LIR parser / serializer 已有基础实现。只补必要 focused tests，避免为已稳定的通用 simple/DOM 通道重复铺测试：

1. `SimpleLirBlockInsnParserTest`
   - `$f = call_intrinsic "c_int_to_float" $i;` 能解析成 `CallIntrinsicInsn`。
   - intrinsic name 未加引号时报解析错误。
   - vararg 使用 literal 而非 `$var` 时报解析错误。

2. `SimpleLirBlockInsnSerializerTest`
   - `new CallIntrinsicInsn("f", "c_int_to_float", List.of(new VariableOperand("i")))` 序列化为 `$f = call_intrinsic "c_int_to_float" $i;`。

如果这些测试暴露 parser/serializer 漏洞，再按既有 parser 结构修复；本计划不需要新增 LIR opcode。

### Step 6. 新增 C intrinsic manager 与 intrinsic implementation

新增 `CIntrinsicManager`：

1. 构造时注册 `new CIntToFloatIntrinsic()`。
2. 内部使用 `Map.of(...)` 保存实现；注册表不需要顺序。
3. 提供 `find(...)` 方法并返回 nullable；调用者负责空结果处理，以便结合当前 `CBodyBuilder` instruction context 报错。

修改 `CGenHelper`：

1. 添加 `private final @NotNull CIntrinsicManager intrinsicManager`。
2. 在构造函数中初始化。
3. 添加 getter，命名为 `intrinsicManager()`。

新增 `CIntrinsicFunction` 接口：

1. 放在 `gd.script.gdcc.backend.c.gen`。
2. 只表达 intrinsic name 和 codegen 方法。
3. codegen 方法接收已经解析好的 nullable result slot 和 argument slots，不接收原始 `CallIntrinsicInsn`。
4. 不引入额外复杂 signature object。
5. 虽然首个 PR 只有 `c_int_to_float` 一个实现，但后续 PR 会陆续添加多个 backend-owned builtin/intrinsic functions，因此这里保留接口与 manager 结构；新增 intrinsic 时不得绕过 manager 白名单。

新增 `CIntToFloatIntrinsic`：

1. 放在 `gd.script.gdcc.backend.c.gen.intrinsic`。
2. 只负责 `c_int_to_float`。
3. 使用 `CBodyBuilder` 既有写入 API，不手写生命周期或目标写入语义。
4. 生成 target-typed cast expression 后再写入 target；不得让 `assignVar(...)` 或 `ClassRegistry.checkAssignable(...)` 接受 raw `int -> float`。

### Step 7. 注册 `CALL_INTRINSIC` codegen

新增 `CallIntrinsicInsnGen`：

1. 放在 `gd.script.gdcc.backend.c.gen.insn`。
2. `getInsnOpcodes()` 返回 `EnumSet.of(GdInstruction.CALL_INTRINSIC)`。
3. `generateCCode(CBodyBuilder bodyBuilder)` 中获取当前 `CallIntrinsicInsn`。
4. 调用 `bodyBuilder.helper().intrinsicManager().find(insn.intrinsicName())`。
5. 若返回 `null`，通过 `bodyBuilder.invalidInsn(...)` 报 unknown intrinsic。
6. 将 `resultId` 解析为 nullable result slot；若 `resultId` 非空但变量不存在，应 fail-fast。
7. 将 `args` 统一解析为 `List<LirVariable>`；非 `VariableOperand` 或变量不存在应 fail-fast。
8. 调用 `CIntrinsicFunction.generateCCode(bodyBuilder, resultVar, argVars)`。

修改 `CCodegen` 静态注册块：

```java
registerInsnGen(new CallIntrinsicInsnGen());
```

该变更只是为已有 LIR opcode 增加 C backend 支持，不是新增后端指令。

### Step 8. 更新必要的 frontend focused tests

只添加能锚定新增语义边界和防止误扩面的 focused tests：

1. `FrontendVariantBoundaryCompatibilityTest`
   - `int -> float` 返回 `ALLOW_WITH_PRIMITIVE_CAST`。
   - `float -> int` 仍为 `REJECT`。
   - `bool -> float`、`int -> bool` 仍为 `REJECT`。

2. `FrontendAssignmentSemanticSupportTest`
   - `ratio: float = 1` 或 `ratio = 1` 不再产生 assignment compatibility error。
   - `value: int = 1.0` 仍失败。

3. `FrontendTypeCheckAnalyzerTest`
   - 选取 local initializer 与 return contract 作为代表，验证 `int -> float` 通过。
   - `float -> int` 继续报 `sema.type_check`。

4. `FrontendExpressionSemanticSupportTest`
   - `take_float(1)` 能匹配 `func take_float(v: float)`。
   - `take(1)` 在 `take(int)` 与 `take(float)` 都存在时选择 `take(int)`。
   - `take(1)` 在 `take(float)` 与 `take(Variant)` 都存在时选择 `take(float)`，锚定 primitive cast 高于 pack。
   - 只有 `take(float)` 时才使用 primitive cast route。
   - `float(1)` 与 `var x: float = 1` 分别走 constructor route 与 ordinary typed-boundary route，不能互相替代。

5. `FrontendSubscriptSemanticSupportTest`
   - `Dictionary[float, V]` 使用 `int` key 通过。
   - `Array[T]` 和 packed array 使用 `float` index 仍失败。
   - `Array[T]` 和 packed array 使用 `Variant` index 仍按 ordinary `Variant -> int` boundary 通过。
   - `Array[int] -> Array[float]`、`Dictionary[int, V] -> Dictionary[float, V]`、`Dictionary[K, int] -> Dictionary[K, float]` 仍失败。

6. `ScopeMethodResolverTest`
   - 默认 strict resolver 仍不接受 `int -> float`。
   - 传入 frontend compatibility predicate 的 resolver 才接受该边界。
   - frontend resolver 在 `method(int)` 与 `method(float)` 同时存在时选择 `method(int)`。
   - frontend resolver 在 `method(float)` 与 `method(Variant)` 同时存在时选择 `method(float)`。

7. `ClassRegistry` focused test
   - `checkAssignable(GdIntType.INT, GdFloatType.FLOAT)` 仍为 false。
   - `checkAssignable(GdFloatType.FLOAT, GdIntType.INT)` 仍为 false。

8. Unary / binary expression semantic tests
   - `1 + 1.0` 和 `1.0 + 1` 不因 ordinary boundary 扩面而新增 numeric promotion。
   - 既有 `int + int`、`float + float` 路径保持不变。

### Step 9. 更新必要的 frontend lowering focused tests

只添加能证明 materialization 接缝正确的 focused tests：

1. `FrontendBodyLoweringSessionTest`
   - 直接调用 `materializeFrontendBoundaryValue(...)`，验证 `int -> float` 返回新 slot。
   - 新 slot 类型为 `GdFloatType.FLOAT`。
   - block 中追加 exactly one `CallIntrinsicInsn`。
   - intrinsic name 为 `c_int_to_float`。
   - argument 是原 `int` source slot。
   - `int -> int`、`float -> float` direct route 仍不生成 instruction。

2. `FrontendLoweringBodyInsnPassTest`
   - 选取 assignment、call arg、subscript key 三个 representative consumer，确认它们消费 casted temp。
   - return / local init 若已有相邻测试能通过 `materializeFrontendBoundaryValue(...)` 证明同一 shared path，可不额外重复铺用例。

测试不应只断言语义阶段无 error；必须断言 LIR 中存在 `CallIntrinsicInsn("c_int_to_float")`，且下游 consumer 使用转换后的 temp。

Subscript lowering 测试必须额外断言：

1. `Dictionary[float, V]` read/write 使用 `int` source key 时，`VariantGetKeyedInsn` / `VariantSetKeyedInsn` 的 key operand 是 casted `float` temp。
2. `Array[T]` / packed array read/write 使用 `Variant` source index 时，先生成 `UnpackVariantInsn` 到 `int` temp，再让 `VariantGetIndexedInsn` / `VariantSetIndexedInsn` 消费该 temp。
3. access-kind selection 使用 materialized key/index type：`Variant -> int` 的 array index 必须走 `INDEXED`，不能因为原始 key type 是 `Variant` 而落到 `GENERIC`。
4. writable-route writeback 中重复使用同一个 materialized key/index slot，不重新取原始 source slot。

### Step 10. 更新 backend codegen 测试

新增 `CallIntrinsicInsnGenTest`：

1. 正常路径：
   - 函数中有 `i: int`、`f: float`。
   - 指令为 `new CallIntrinsicInsn("f", "c_int_to_float", List.of(new VariableOperand("i")))`。
   - 生成 C 包含等价 `(godot_float)$i` cast，且写入 `$f`。

2. unknown intrinsic：
   - `call_intrinsic "unknown"` 抛 `InvalidInsnException` 或 backend 当前统一使用的 invalid insn 异常。
   - 错误信息包含 intrinsic name。

3. 参数数量错误：
   - 0 个参数失败。
   - 2 个参数失败。

4. 参数 operand 错误：
   - 手工构造非 `VariableOperand` 失败。

5. 参数变量错误：
   - 参数变量不存在失败。
   - 参数变量类型非 `int` 失败。

6. result 错误：
   - `resultId == null` 失败。
   - result 变量不存在失败。
   - result 变量为 `ref` 失败。
   - result 变量类型非 `float` 失败。

不额外新增 `CGenHelperTest`。`CIntrinsicManager` 当前只是薄注册表，成功路径和 unknown intrinsic 由 `CallIntrinsicInsnGenTest` 覆盖即可。

### Step 11. `test_suite` 端到端验收

不新增额外 Java integration test；通过 `doc/test_suite.md` 记录并执行一个端到端场景，验证 frontend、lowering、backend 和运行期结果串联正确：

```gdscript
class_name IntToFloatBoundarySmoke
extends RefCounted

func take_float(v: float) -> float:
    return v

func run() -> float:
    var ratio: float = 1
    ratio = take_float(2)
    return ratio
```

验收点：

1. 编译通过，C codegen 不再报 `Unsupported instruction opcode: call_intrinsic`。
2. 运行结果为 `2.0`。
3. 该端到端用例只验证最终链路，不替代上面的 focused semantic / lowering / backend tests。

## 验收细则

实现完成后，以下条件必须全部成立：

1. 文档矩阵中 `int -> float` 标记为 GDCC supported，且注明物化路线。
2. `FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(INT, FLOAT)` 返回 `ALLOW_WITH_PRIMITIVE_CAST`。
3. `FLOAT -> INT`、`BOOL -> FLOAT`、`INT -> BOOL` 仍返回 `REJECT`。
4. `ClassRegistry.checkAssignable(GdIntType.INT, GdFloatType.FLOAT)` 仍为 false。
5. `Array[int] -> Array[float]` 和 `Dictionary[int, V] -> Dictionary[float, V]` 仍被拒绝。
6. `Dictionary[float, V]` 的 key boundary 可以接收 `int` key。
7. `Array[T]` / packed array index 仍不接收 `float` index。
8. Lowering 对 `int -> float` 生成新的 `float` temp，而不是复用 `int` source slot。
9. Lowering 生成 `CallIntrinsicInsn(..., "c_int_to_float", ...)`。
10. C backend 注册 `CALL_INTRINSIC`，不再对该 opcode 报 unsupported。
11. `CIntToFloatIntrinsic` 生成等价 `(godot_float)<int_expr>` 的 C cast。
12. unknown intrinsic 和坏签名均 fail-fast，错误信息可定位 intrinsic name、function/block/instruction。
13. unary/binary operator 测试证明 `int -> float` boundary 不会放开 operator numeric promotion。
14. bare call、method resolver frontend path、constructor resolver 都遵守 exact > primitive cast > Variant pack 的 specificity。
15. Subscript key/index lowering 会物化所有 accepted ordinary key/index boundary：`Dictionary[float, V]` 的 `int` key 物化为 `float` temp，`Array[T]` / packed array 的 `Variant` index 物化为 `int` temp。
16. `float(1)` constructor route 与 ordinary boundary route 相互独立。
17. `doc/test_suite.md` 包含一个 `int -> float` 端到端场景，并验证运行结果。

建议最小验证命令：

```bash
./gradlew test --tests FrontendVariantBoundaryCompatibilityTest --no-daemon --info --console=plain
./gradlew test --tests FrontendAssignmentSemanticSupportTest --no-daemon --info --console=plain
./gradlew test --tests FrontendExpressionSemanticSupportTest --no-daemon --info --console=plain
./gradlew test --tests FrontendSubscriptSemanticSupportTest --no-daemon --info --console=plain
./gradlew test --tests ScopeMethodResolverTest --no-daemon --info --console=plain
./gradlew test --tests FrontendBodyLoweringSessionTest --no-daemon --info --console=plain
./gradlew test --tests FrontendLoweringBodyInsnPassTest --no-daemon --info --console=plain
./gradlew test --tests CallIntrinsicInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests SimpleLirBlockInsnParserTest --tests SimpleLirBlockInsnSerializerTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

在 Ubuntu/Linux 上按项目规则优先使用 `script/run-gradle-targeted-tests.sh --tests ...` 运行上述 targeted tests。端到端行为通过 `doc/test_suite.md` 中新增的 test-suite 场景验证。

## 风险与解决方案

### 1. `ALLOW_WITH_PRIMITIVE_CAST` 被误当成 direct route

风险：

- semantic 放行后 lowering 直接返回 source slot。
- `int` slot 被拿去写 `float` slot。
- backend strict assignability 或 C codegen 类型形态出现不一致。

解决方案：

- `ALLOW_WITH_PRIMITIVE_CAST` 必须有独立 decision。
- `materializeFrontendBoundaryValue(...)` 必须按 decision 分支物化 temp。
- 测试必须断言返回 slot 不等于 source slot。

### 2. 误改 `ClassRegistry.checkAssignable(...)`

风险：

- backend/global strict assignment 被放宽。
- `assignVar(...)`、`callAssign(...)`、operator result check 等 backend 路径可能接受没有显式 materialization 的 numeric promotion。
- container covariance 会递归放开 `Array[int] -> Array[float]` 等不需要的行为。

解决方案：

- 保持 `ClassRegistry.checkAssignable(...)` 不变。
- `ScopeMethodResolverTest` 锚定默认 strict path 继续拒绝 `int -> float`。
- container negative tests 锚定 `Array` / `Dictionary` 不做 primitive covariance。

### 3. Subscript 支持面扩大不清

风险：

- `Dictionary[float, V]` key 接收 `int` 是 shared boundary 的自然结果。
- 如果没有文档和测试，后续维护者可能误以为所有 index/key conversion 都应对齐 Godot。
- 如果 lowering 不物化 key，语义接受的 `Dictionary[float, V]` 访问会继续把 raw `int` key 传给 runtime keyed access。
- 同类缺口不只存在于字典：`Array[T]` / packed array 的 expected index type 是 `int`，现有 ordinary `Variant -> int` boundary 可让 `array[variant_index]` 语义通过；如果 lowering 不物化 index，会继续把 raw `Variant` index 传下去，并可能错误选择 `GENERIC` 而不是 `INDEXED` access。

解决方案：

- 文档明确：`Dictionary[float, V]` key 接收 `int`，`Array` / packed array `Variant` index 通过 ordinary `Variant -> int` boundary 后必须 unpack；但 `Array` / packed array `float` index 仍禁止。
- `FrontendSubscriptSemanticSupportTest` 同时加正负例。
- `FrontendLoweringBodyInsnPassTest` 断言 subscript read/write 消费 materialized key/index temp，而不是原始 source slot，并断言 access-kind selection 基于 materialized type。

### 4. Call / method / constructor overload ambiguity 或 specificity 漂移

风险：

- `take(int)` 与 `take(float)` 同时存在时，`take(1)` 若不优先 exact match，会出现错误选择或 ambiguous。
- `take(float)` 与 `take(Variant)` 同时存在时，`take(1)` 若不优先 primitive cast，会错误选择 `Variant` pack 或 ambiguous。
- `ScopeMethodResolver` 使用 frontend compatibility predicate 后，如果仍只按 owner/static/vararg 排序，method overload 会出现同类 ambiguity 或 object dynamic fallback。
- constructor ranking 如果没有加入新 decision，会编译失败或排序不稳定。

解决方案：

- 抽出或复用统一 boundary decision specificity rank。
- bare call、method resolver frontend path、constructor tests 同时覆盖 exact-vs-cast、cast-vs-pack 和 cast-only 三种情况。
- 默认 strict resolver 测试继续锚定不接受 `int -> float`。

### 5. `CALL_INTRINSIC` 变成任意 C 函数逃逸口

风险：

- 如果 codegen 直接把 intrinsic name 当 C function name 发射，LIR 可以调用任意 C symbol。
- 类型签名、生命周期和错误定位都绕过 backend 现有约束。

解决方案：

- `CIntrinsicManager` 使用 `Map.of(...)` 白名单注册 `CIntrinsicFunction`。
- unknown intrinsic 必须 fail-fast。
- `CIntToFloatIntrinsic` 自己校验参数和 result 类型。

### 6. 后续 intrinsic 复用时绕过生命周期

风险：

- `c_int_to_float` 是 primitive cast，生命周期简单。
- 后续 intrinsic 若返回 `Variant`、`String`、`Array`、`Dictionary` 或 object，直接拼 assignment 会造成 destroy/retain/own 语义错误。

解决方案：

- `CIntrinsicFunction` 合同要求通过 `CBodyBuilder` API 生成代码。
- 文档注明：destroyable/object intrinsic 必须单独审计 ownership，不得照抄 primitive cast 的 direct expression route。

### 7. 误混 builtin constructor special route

风险：

- `float(1)` 和 `var x: float = 1` 是不同 source surface。
- 如果 ordinary boundary 借 constructor route 实现，会污染 constructor warning、runtime-open `Variant` constructor special route 和 overload ranking。

解决方案：

- ordinary boundary 只生成 `call_intrinsic "c_int_to_float"`。
- builtin constructor 相关逻辑只消费 shared helper 的 match decision，不把 `int -> float` materialization 改写成 constructor call。

## 建议实施顺序

推荐按以下顺序提交实现，便于定位回归：

1. 文档矩阵与 frontend lowering 文档更新。
2. `FrontendVariantBoundaryCompatibility` 新 decision 与 focused semantic tests。
3. bare call、`ScopeMethodResolver` frontend path、constructor specificity 与 focused tests。
4. `FrontendBodyLoweringSession` primitive cast materialization 与 local/assignment/call/return lowering tests。
5. Subscript key/index materialization 与 focused lowering tests。
6. LIR `call_intrinsic` parser/serializer direct tests。
7. `CIntrinsicManager`、`CIntrinsicFunction`、`CIntToFloatIntrinsic`、`CallIntrinsicInsnGen` 与 backend focused tests。
8. `doc/test_suite.md` 中新增最小端到端场景。
9. `./gradlew classes --no-daemon --info --console=plain`。

如果任一步出现测试失败，先把失败原因补记到对应实现文档或计划文档的风险段，再运行更小的 focused test。不要一次性修改所有 frontend / backend 测试期望。

## 完成定义

本计划视为完成时：

1. `int -> float` 在 ordinary typed boundary 中可用。
2. 所有 conversion 都通过 `ALLOW_WITH_PRIMITIVE_CAST` 和 `call_intrinsic "c_int_to_float"` 显式物化。
3. C backend 对 `c_int_to_float` 生成真实 C cast。
4. strict backend/global assignability 不被放宽。
5. `Array` / `Dictionary` primitive covariance 仍禁止。
6. operator numeric promotion 仍禁止。
7. focused semantic、lowering、LIR parser/serializer、C codegen 测试覆盖正向和负向行为。
8. `test_suite` 端到端场景覆盖 frontend 到运行期的完整链路。
