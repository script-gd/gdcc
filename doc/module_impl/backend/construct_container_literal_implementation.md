# construct_container_literal 实现说明（Array + Dictionary literal）

> 本文档作为 `construct_container_literal` 在 C Backend 的长期维护说明。
> 只保留已完成实现、当前状态和后续工程仍有价值的约定与反思。

## 文档状态

- 状态：Implemented / Maintained
- 范围：`construct_container_literal` 在 C Backend 的语义、路由与校验
- 更新时间：2026-08-12
- 关联基线：
  - `doc/gdcc_low_ir.md`（`construct_container_literal` 合同）
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/builtin_builder_implementation.md`
  - `doc/module_impl/backend/typed_array_abi_contract.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`
  - `doc/module_impl/frontend/frontend_container_literal_implementation.md`

## 当前最终状态（与代码对齐）

### 覆盖范围

- 指令生成入口：`src/main/java/gd/script/gdcc/backend/c/gen/insn/ContainerLiteralInsnGen.java`
- 注册：`src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`（独立 generator，**不**并入 `ConstructInsnGen`）
- 共享 pack/destroy/failure helper：`src/main/java/gd/script/gdcc/backend/c/gen/insn/InsnGenSupport.java`
- Empty typed/plain construct：`CBuiltinBuilder.constructBuiltin(...)`（`Array[Variant]` / `Dictionary[Variant,Variant]` 走 plain `godot_new_*`）
- LIR 指令：`src/main/java/gd/script/gdcc/lir/insn/ConstructContainerLiteralInsn.java`
- Opcode：`GdInstruction.CONSTRUCT_CONTAINER_LITERAL`（VARARGS，min 0）

### 已实现语义

- family **仅由 result 类型**决定：`GdArrayType` → Array 路径；`GdDictionaryType` → Dictionary 路径。
- result 必须 non-ref；ref result fail-fast。
- 不支持 Packed* 或其它类型 result。
- **Array**
  1. empty `constructBuiltin(target, [])`
  2. 按 operand 顺序：`materializeVariantOperand` → `godot_Array_push_back`（void，无失败分支）→ 立即 destroy pack temp
- **Dictionary**
  1. 奇数 operand 数 → `invalidInsn`
  2. empty `constructBuiltin(target, [])`
  3. 每对 key/value：`materializeVariantOperand` → `godot_Dictionary_set` → **无条件** destroy 两个 pack temp → `if (!ok)` 仅 `emitRuntimeFailureReturn`（不再把已 destroy 的 temp 交给 failure cleanup）
- backend **不**重做元素转换；frontend boundary materialization 已把 operand 放成最终可 pack 的类型。
- 常量 duplicate key 由 frontend type-check 拦截；动态 duplicate key 运行时后写覆盖，插入顺序保持首次 key。

## 长期约定（必须保持）

1. **独立 opcode / 独立 generator**  
   不得把 filled literal 并入 `construct_array` / `construct_dictionary` 的 empty 合同；也不得把本指令并入 `ConstructInsnGen`。
2. **family = result type only**  
   不根据 operand 形状或源码文本猜测 Array vs Dictionary。
3. **pack temp lifecycle**  
   - Array：每个 `push_back` 后立刻 destroy 该元素 pack temp。  
   - Dictionary：每个 `set` 后立刻 destroy key/value pack temp（成功与失败路径共用 destroy；failure 分支不得先 `destroyTempVar` 污染 codegen 期 `TempVar.initialized`）。
4. **typed ABI fail-closed 在 frontend**  
   script leaf / nested typed / void / unknown leaf 必须在 `TypedContainerAbiSupport` 阶段拒绝；backend 不得 silent degrade。
5. **plain surface**  
   `Array[Variant]` / `Dictionary[Variant,Variant]` 继续走 plain empty construct（无 typed hint / preflight）。

## 风险与防线

- 风险：Dictionary 成功路径 pack temp 泄漏（failure-path destroy 在 codegen 期清 `initialized`）。  
  - 防线：set 后先 destroy，再分支；单测锚定 destroy 出现在 failure `if` 之前。
- 风险：与 empty `construct_array` / `construct_dictionary` 语义纠缠。  
  - 防线：独立 opcode + 回归 `CConstructInsnGenTest` 保持 empty 合同。
- 风险：typed container ABI 错误首次出现在 C codegen。  
  - 防线：frontend `TypedContainerAbiSupport` + focused type-check tests。
- 风险：编译门过早解除导致其它 intercept 误放行。  
  - 防线：仅删除 Array/Dictionary case；Conditional/Preload/GetNode/assert 锚点测试继续锁定。

## 回归测试基线

- `CContainerLiteralInsnGenTest` — codegen 正反合同（empty/generic/typed、dict 奇数、ref result、destroy 顺序）
- `CContainerLiteralInsnGenEngineTest` — Godot 运行时 `is_typed` / metadata / 读回 / 动态 duplicate key
- `ConstructContainerLiteralInsnContractTest` — LIR 指令形态
- `FrontendContainerLiteralInsnLoweringTest` — plan-driven body emit（`analyzeForCompile`）
- `FrontendCompileCheckAnalyzerTest` — literal 不再 `sema.compile_check`；其余 intercept 仍在
- suite：
  - `collection/array_literal_roundtrip.gd`
  - `collection/dictionary_literal_roundtrip.gd`
  - `collection/container_literal_evaluation_order.gd`
  - `collection/typed_container_literal_boundaries.gd`

建议命令：

```bash
./gradlew test --tests CContainerLiteralInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CContainerLiteralInsnGenEngineTest --no-daemon --info --console=plain
./gradlew test --tests FrontendCompileCheckAnalyzerTest --no-daemon --info --console=plain
./gradlew test --tests FrontendContainerLiteralInsnLoweringTest --no-daemon --info --console=plain
./gradlew test --tests "GdScriptUnitTestCompileRunnerTest.compilesAndValidatesCollectionScripts" --no-daemon --info --console=plain
```

## 工程反思（保留长期价值）

1. filled literal 与 empty construct 是不同合同：literal 要按元素 materialize + pack + 写入；empty 只负责 typed/plain 空容器。
2. codegen 期 temp 生命周期状态与运行时控制流分支不能混用：failure helper 若在 emit 时 destroy，会饿死 success 路径的 destroy 发射。
3. compile gate 解除必须与 suite 端到端绑定；仅 unit codegen 绿不足以证明 property initializer / exact call / side-effect order。

## 非目标（当前不做）

- 不修改 `GdInstruction.CONSTRUCT_CONTAINER_LITERAL` opcode/操作数数量定义。
- 不修改 `construct_array` / `construct_dictionary` empty 语义。
- 不支持 Packed* literal 语法（不存在对应 source form）。
- 不把常量 duplicate-key warning 降级到 backend。
