# Frontend `as`（CastExpression）实现说明

> 本文档作为 GDScript `as` 显式转换表达式在 frontend、LIR、C backend 与 runtime helper 全链路的长期事实源，记录当前冻结的 explicit-cast decision 合同、类型目标发布、body lowering 映射、diagnostic 边界与回归锚点。本文档替代原 `frontend_cast_expression_implementation_plan.md`，不保留分步骤实施、阶段状态、验收清单或已完成任务日志。

## 文档状态

- 状态：事实源维护中（shared semantic、CFG/body lowering、`builtin_cast` / `object_cast` codegen、runtime helpers、compile-only gate 与 `cast/` 端到端测试均已纳入当前实现）
- 更新时间：2026-08-08
- Godot 对齐基线：runtime / GDExtension ABI 固定为 `4.5.1`（`GodotVersion.V451`）；`Variant::can_convert` 矩阵与 `4.5.1-stable` / `4.7.1-stable` 源表相同（设计时对照 `4.7.1-stable` 的 `core/variant/variant.cpp` 抄录，两 tag 字节级一致）
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/main/java/gd/script/gdcc/util/type/ExplicitCast*.java`
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
  - 对应的 frontend、LIR、backend 与 test-suite 测试
- 关联文档：
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/frontend_is_type_test_implementation.md`
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/backend/backend_ownership_lifecycle_contract.md`
  - `doc/module_impl/backend/object_value_fat_pointer_implementation.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
- 明确非目标：
  - 不修改 `gdparser` 语法或 AST；当前依赖提供 `CastExpression(Expression value, TypeRef targetType, Range range)`
  - 不把 `as` 规则加入 `frontend_implicit_conversion_matrix.md`；该文档只覆盖 ordinary typed-boundary implicit conversion
  - 不把 `as` lowering 展开为 constructor resolution、method overload resolution 或 implicit boundary materialization
  - 不支持 `as void`、`as null`、未知类型名、malformed structured type text 或 compiler-only 类型
  - 不新增独立 HIR pass
  - 不支持依赖 GDScript script-instance inheritance metadata 的 external/path-based script resource、autoload script type、global-script-class 等当前 scope/runtime 尚未正式支持的类型来源
  - 参数化容器 cast 对齐 Godot base-builtin runtime 行为；cast 本身不验证、不补写也不转换 typed metadata

## 1. 当前定位与数据流

当前支持的源码形态为：

```gdscript
value as TargetType
```

完整数据流固定为：

```text
CastExpression
  -> shared semantic: resolve value + target type, publish RESOLVED(target type)
  -> type-check: validate explicit-cast compatibility and unsafe runtime cast warning
  -> compile-only final gate (no longer intercepts CastExpression)
  -> CastItem
  -> frontend body lowering:
       identity / assign
       pack_variant
       builtin_cast
       object_cast
  -> C backend:
       Variant construction for builtin targets
       ClassDB/runtime-name object check for object targets
  -> typed result slot
```

shared semantic 负责解析并发布 target-typed result；type-check 负责 hard invalid cast error；compile gate 不再为 `CastExpression` 建立显式 blocker；CFG/body lowering 只消费已发布的 source/target type；backend 按 decision 路径执行 runtime cast。

### 1.1 Parser 形状（gdparser 0.5.2）

- `a + b as float` 解析为 `a + (b as float)`（外层 `BinaryExpression`，右侧为 `CastExpression`）。Godot 将 `as` 置于二元算符之下（4.5.1 与 4.7.1 相同）；`(a + b) as float` 可恢复 Godot 形状。
- `x as int as float` 外层 Cast operand 是内层 CastExpression（左结合）。
- 上述形状由 `FrontendCastParseBehaviorTest` 冻结，不通过修改 gdparser 强制对齐 Godot 优先级。

## 2. 统一 decision 与 LIR 合同

### 2.1 ExplicitCastDecision

`ExplicitCastSupport` / `ExplicitCastDecision` 是 frontend 与 backend 共享的唯一分类真源：

| Decision | 含义 | LIR |
|---|---|---|
| `IDENTITY` | exact same type | `AssignInsn` |
| `PACK_TO_VARIANT` | non-Variant source → `Variant` | `PackVariantInsn` |
| `BUILTIN_RUNTIME_CAST` | Godot-compatible builtin construct cast | `BuiltinCastInsn` |
| `OBJECT_UPCAST` | proven Object subclass → ancestor | `AssignInsn` |
| `OBJECT_RUNTIME_CAST` | runtime Object check (downcast / Nil / Variant) | `ObjectCastInsn` |
| `INVALID` | hard rejection | type-check error；body lowering fail-fast |

输入为 `ClassRegistry + source GdType + target GdType`。hard builtin pair 使用 Godot `Variant::can_convert(...)`（非 strict、非 implicit matrix；4.5.1 与 4.7.1 表相同）。`Nil→RID = INVALID`；仅 `Nil→Object` 允许。compiler-only 类型 fail-fast。

### 2.2 LIR 形状

```text
$result_id = builtin_cast "<target_type_name>" $value_id
$result_id = object_cast "<class_name>" $value_id
```

固定约束：

- `builtin_cast`：result required；target 必须是 non-Object/non-Variant/non-Nil runtime builtin（含 base `Array` / `Dictionary` 与参数化容器文本）；`target_type_name` 使用 `GdType.getTypeName()` 完整文本；不得复用 `construct_builtin`。
- `object_cast`：`class_name` 必须是 canonical / Godot-facing runtime name；source 允许 Object / Variant / Nil；`resultId == null` 时 generator 校验后 no-op。
- identity / upcast 不进入 cast opcode，直接 `AssignInsn`。
- Variant target 对 concrete source 使用 `PackVariantInsn`，Variant source 使用 `AssignInsn`。

`doc/gdcc_low_ir.md` 是 LIR 语法公共说明；本文档记录 frontend / backend / runtime 的具体实现合同。

## 3. Shared semantic 与 diagnostic

### 3.1 RHS 解析

`FrontendExpressionSemanticSupport.resolveCastExpressionType(...)`：

1. 先解析 `castExpression.value()`；不稳定 dependency 继续 propagated。
2. trim `targetType.sourceText()`。
3. 空文本、`null`、`void` 直接 `FAILED`。
4. scope 顺序：cast root → value scope → class registry root。
5. 调用 `FrontendModuleSkeleton.tryResolveSourceFacingDeclaredType(...)`。
6. structured type text 失败或合法但未知 bare identifier 直接 `FAILED`（比 `is` 更严格，不降级为 unresolved Object）。
7. 成功时发布 `expressionTypes()[cast] = RESOLVED(targetType)`；**不**维护独立 castTargets side-table。

### 3.2 Diagnostic owners

| 场景 | Category | Severity | Owner |
|---|---|---|---|
| target 解析失败 / unknown / void / null | `sema.expression_resolution` | error | expression semantic |
| hard source/target 且 `checkAllowed == false` | `sema.type_check` | error | type-check (`visitCastExpression`) |
| source 为 `Variant` / `DYNAMIC` 且 target 非 `Variant` | `sema.unsafe_cast` | warning | expression publisher；与 `RESOLVED` 共存 |
| supported cast | 无 `sema.compile_check` | — | compile gate 不拦截 CastExpression |

invalid cast 只保留 shared semantic / type-check error，不被 compile gate 重复包装。shared `analyze(...)` 与 compile-only `analyzeForCompile(...)` 的 diagnostic owner 保持分离。

### 3.3 Consumer 路径

cast root 发布 `RESOLVED(targetType)` 后，现有 fallback expression receiver 自然支持：

```gdscript
(value as Node).name
(value as Array)[0]
```

local/property initializer、assignment RHS、return、fixed call argument、condition normalization 等 consumer 只消费 target-typed result，不得重新执行 explicit-cast compatibility。

## 4. CFG 与 frontend body lowering

`FrontendCfgGraphBuilder.buildCastValue` 先构建 operand，再发布单一 `CastItem`（operand value id + result value id + AST anchor）。operand 只求值一次；result 始终写入 `cfg_tmp_<resultValueId>` target-typed TEMP_SLOT。

`FrontendCastInsnLoweringProcessor` / `FrontendBodyLoweringSession.emitExplicitCast`：

- 读取 published source/target type，委托 `ExplicitCastSupport.classify`。
- 按 §2.1 固定映射发射 LIR；不复用 `FrontendVariantBoundaryCompatibility` 或隐式转换 materialization。
- 不重新解析 `TypeRef.sourceText()`。
- `Nil as Object` 固定发 `ObjectCastInsn`，不得旁路为 literal-null。
- `INVALID` 在 body lowering fail-fast；正常 compile 路径应由 type-check 阻断。
- cast 作为 chain head 时 CFG 先生成 CastItem，再生成 member/call/subscript item。

## 5. Backend 与 runtime

### 5.1 BuiltinCastInsnGen

- non-Variant source 只 pack 一次；Variant source 透传。
- 调用 `godot_variant_construct`；target enum 与 result type 一致。
- 参数化 `Array[T]` / `Dictionary[K, V]` 使用 base ARRAY/DICTIONARY enum，不调用 typed metadata guard/constructor。
- 检查 `GDExtensionCallError.error == GDEXTENSION_CALL_OK`。
- 成功后 exact unpack 到 target result。
- 失败路径打印稳定 runtime error（`godot_variant_construct failed for builtin_cast to '<target>'`），销毁 initialized temp，走 default-return cleanup。
- exact/identity/Variant target LIR 若误入 generator，明确 invalid-instruction fail-fast。

注意：Godot `Variant::construct` 只接受已注册 constructor pair。`can_convert` 允许但无 constructor 的 pair（例如 `int → String`）在 runtime 会走 failure path；frontend 分类仍按 `can_convert` 表放行，与 Godot VM `OPCODE_CAST_TO_BUILTIN` 使用同一 construct 入口。

### 5.2 ObjectCastInsnGen

- 使用 instance ID live lookup；不得只检查 cached ptr。
- 使用 ClassDB / runtime registered class name 检查 inheritance。
- 仅接受 runtime registration 已证明可查询的类；script-instance-only 或未注册继承关系的 target 在 shared semantic / type-check 明确 unsupported。
- success 保留 source `instance_id` 与 ownership category（OWNED 保持 OWNED，BORROWED 保持 BORROWED）。
- failure / null / freed / mismatch 归一化为 canonical null `{ptr=NULL, instance_id=0}`。
- 禁止使用 `godot_object_cast_to`、`gdcc_check_variant_type_object` 或 plain `_fat_ptr_from_variant` 代替 class check。
- `CBodyBuilder.valueOfCastedVar(...)` 只用于已证明 assignable 的 layout upcast，不得替代 `ObjectCastInsnGen`。

### 5.3 Runtime helpers

- `gdcc_object_cast_raw_and_id` / `gdcc_object_cast_variant`：ownership-neutral object cast query。
- 完整 ABI 以 `doc/gdcc_runtime_lib.md` 与 `gdcc_helper.h` 为准。

### 5.4 参数化容器 base-only cast

- runtime `as Array[T]` / `as Dictionary[K, V]` 只执行 base ARRAY/DICTIONARY cast。
- target 参数文本只决定 frontend/LIR 静态结果类型。
- runtime typed metadata 保持原样：已有 metadata 不被改写；plain container 不因 cast target 获得 metadata。
- 后续 metadata-sensitive operation 必须查询实际 runtime metadata。

## 6. Compile-only 边界

`FrontendCompileCheckAnalyzer.walkExpression` **不再** case-intercept `CastExpression`；它进入 default compile surface recursion（`markCompileSurfaceNode` + nested children）。

- supported cast 不产生 `sema.compile_check`。
- invalid cast 只保留 shared type-check / expression-resolution error。
- 当前仍被显式 intercept 的表达式（`PreloadExpression`、`GetNodeExpression`）以及 `assert` / route-aware `ForStatement` 仍被正确封口。`ArrayExpression` / `DictionaryExpression` 与 `ConditionalExpression` 已离开 intercept（见 `frontend_container_literal_implementation.md`、`frontend_conditional_expression_implementation.md`）。

## 7. 核心实现落点

- `src/main/java/gd/script/gdcc/util/type/ExplicitCastSupport.java` / `ExplicitCastDecision.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendBodyOwnerProcedures.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendTypeCheckAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilder.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/item/CastItem.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendBodyLoweringSession.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`
- `src/main/java/gd/script/gdcc/lir/insn/BuiltinCastInsn.java` / `ObjectCastInsn.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/BuiltinCastInsnGen.java` / `ObjectCastInsnGen.java`
- `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`

## 8. 回归锚点

- `FrontendCastParseBehaviorTest` — parser 形状
- `ExplicitCastSupportTest` — Godot `can_convert` matrix 与 Object relation
- `FrontendExpressionSemanticSupportTest` — publication、unsafe warning、compile gate 放行
- `FrontendCompileCheckAnalyzerTest` — 无 cast compile blocker；其余 intercept 仍封口
- `FrontendCastInsnLoweringTest` — decision→LIR 正反路径与 source wiring
- `FrontendCfgGraphBuilderTest` / `FrontendLoweringBodyInsnPassTest` — CFG shape 与统一 body pass
- `BuiltinCastInsnContractTest` / `ObjectCastInsnContractTest`
- `BuiltinCastInsnGenTest` / `ObjectCastInsnGenTest`
- `GdScriptUnitTestCompileRunnerTest` — `cast/` allowlist 与 factory
- `src/test/test_suite/unit_test/{script,validation}/cast/` — e2e pairs：
  - `builtin_identity_conversion`
  - `variant_to_builtin_success`
  - `variant_to_builtin_runtime_failure`
  - `engine_object_cast`
  - `gdcc_object_cast`
  - `null_freed_object_cast`
  - `cast_result_consumers`
  - `parameterized_container_cast`

compile-fail 场景（unrelated hard types、unregistered script-instance-only）锚定在 frontend focused tests，不进入 test_suite。

## 9. 长期维护约束

- explicit cast matrix 只有一个长期真源：`ExplicitCastSupport` + 本文档；禁止复制 private 平行 decision。
- 不得把 `as` 误并入 implicit conversion matrix。
- 不得用 `construct_builtin` 代替 `builtin_cast`；不得用 `_fat_ptr_from_variant` 代替 object class check。
- success object cast 必须保留 source ownership category；failure 必须是 canonical null。
- parameterized container cast 不得改写 runtime typed metadata。
- RHS canonical/facing name 必须与 class-name / runtime registration 合同一致。
- 若 cast 合同变化，必须同步本文档、`gdcc_low_ir.md`、`gdcc_runtime_lib.md`、ownership / fat-pointer、compile gate 与 frontend rules 文档，以及对应 targeted/test-suite 测试。
