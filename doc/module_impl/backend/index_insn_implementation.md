# Indexing Instructions C Backend 实现说明（归档事实源）

> 本文档是 `variant_get*` / `variant_set*` 在 C Backend 的单一事实来源。  
> 仅保留当前已落地语义、长期约定与风险边界；阶段性实施步骤与执行流水已归档移除。

## 文档状态

- 状态：`Active / 单一事实来源`
- 更新时间：`2026-03-04`
- 适用范围：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/IndexLoadInsnGen.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/IndexStoreInsnGen.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- 关联文档：
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdextension-lite.md`

---

## 1. 覆盖范围与指令清单

Indexing Instructions 共 8 条，分为 Load（GET）与 Store（SET）两类：

| LIR 指令 | GdInstruction | ReturnKind | 操作数签名 | 语义 |
|---|---|---|---|---|
| `variant_get` | `VARIANT_GET` | REQUIRED | `(VARIABLE, VARIABLE)` | 通用 Variant 键读取 |
| `variant_get_keyed` | `VARIANT_GET_KEYED` | REQUIRED | `(VARIABLE, VARIABLE)` | 字典式键读取 |
| `variant_get_named` | `VARIANT_GET_NAMED` | REQUIRED | `(VARIABLE, VARIABLE)` | 属性名读取（`StringName` 变量） |
| `variant_get_indexed` | `VARIANT_GET_INDEXED` | REQUIRED | `(VARIABLE, VARIABLE)` | 整数索引读取（`int` 变量） |
| `variant_set` | `VARIANT_SET` | NONE | `(VARIABLE, VARIABLE, VARIABLE)` | 通用 Variant 键写入 |
| `variant_set_keyed` | `VARIANT_SET_KEYED` | NONE | `(VARIABLE, VARIABLE, VARIABLE)` | 字典式键写入 |
| `variant_set_named` | `VARIANT_SET_NAMED` | NONE | `(VARIABLE, VARIABLE, VARIABLE)` | 属性名写入（`StringName` 变量） |
| `variant_set_indexed` | `VARIANT_SET_INDEXED` | NONE | `(VARIABLE, VARIABLE, VARIABLE)` | 整数索引写入（`int` 变量） |

LIR 侧统一标记接口：`IndexingInstruction extends LirInstruction`。

---

## 2. 外部 API 契约（gdextension-lite）

### 2.1 GET API 要点

- `godot_variant_get` / `godot_variant_get_keyed`：
  - `p_self: GDExtensionConstVariantPtr`
  - `p_key: GDExtensionConstVariantPtr`
  - `r_ret: GDExtensionUninitializedVariantPtr`
  - `r_valid: GDExtensionBool*`
- `godot_variant_get_named`：
  - key 为 `GDExtensionConstStringNamePtr`
- `godot_variant_get_indexed`：
  - index 为 `GDExtensionInt`
  - 额外输出 `r_oob: GDExtensionBool*`

### 2.2 SET API 要点

- `godot_variant_set` / `godot_variant_set_keyed`：
  - `p_self: GDExtensionVariantPtr`（可变）
  - `p_key: GDExtensionConstVariantPtr`
  - `p_value: GDExtensionConstVariantPtr`
  - `r_valid: GDExtensionBool*`
- `godot_variant_set_named`：
  - key 为 `GDExtensionConstStringNamePtr`
- `godot_variant_set_indexed`：
  - index 为 `GDExtensionInt`
  - 额外输出 `r_oob: GDExtensionBool*`

### 2.3 统一差异约束

1. GET 的返回值通过未初始化 out 参数 `r_ret` 传出；SET 无返回 Variant。
2. `_indexed` 指令必须处理 `r_oob`。
3. 所有调用都基于 Variant 指针语义；非 Variant 操作数需进行 pack/unpack。

---

## 3. 架构与分工

### 3.1 生成器拆分（长期约定）

按 ReturnKind 拆分为两个生成器：

1. `IndexLoadInsnGen` 负责 4 条 GET。
2. `IndexStoreInsnGen` 负责 4 条 SET。

拆分原因：

1. 返回值语义不同（GET 必有 result，SET 无 result）。
2. API 形态不同（GET `r_ret`，SET `p_value`）。
3. 生命周期关注点不同（GET 管理返回 Variant；SET 管理 value/self pack 与写回）。

### 3.2 注册入口

- `CCodegen` 必须注册 `IndexLoadInsnGen` 与 `IndexStoreInsnGen`。
- opcode 分发不允许落回默认路径。

### 3.3 关键职责边界

1. 生成器负责指令语义校验与 fail-fast。
2. `CBodyBuilder` 负责参数渲染、赋值与生命周期通用语义。
3. 错误分支统一使用 `CBodyBuilder.returnDefault()`，禁止生成器自行拼装默认返回表达式。

---

## 4. 操作数与类型策略（当前实现）

### 4.1 named/indexed 建模约束

1. `variant_get_named` / `variant_set_named` 的 key 必须是 `StringName` 变量。
2. `variant_get_indexed` / `variant_set_indexed` 的 index 必须是 `int` 变量。
3. 不再使用 named/indexed 字面量操作数模型，文本 IR 需保持 `$name` / `$idx` 形态。

### 4.2 GET 操作数约束

1. `result` 必须存在且必须是非 ref 变量。
2. `self`、`key`（或 `name` / `index`）必须存在。
3. `variant_get` / `variant_get_keyed` 的 `key` 允许任意类型（必要时 pack 为 Variant）。
4. `variant_get_named` 的 `name` 必须是 `StringName`。
5. `variant_get_indexed` 的 `index` 必须是 `int`，允许 ref/non-ref。

### 4.3 SET 的 self 分类与回写策略

`variant_set*` 的核心是 self 的语义类别，而非“是否声明为 `Variant`”。

| self 类别 | 典型类型 | 处理策略 | ref 是否允许 |
|---|---|---|---|
| Variant | `GdVariantType` | 直接传入 | 允许 |
| 引用语义 | `GdArrayType` / `GdDictionaryType` / `GdObjectType` | pack 后调用，无需回写 | 允许 |
| 值语义且支持 set | 由 `resolveSelfStrategy` 判定（例如 `String` / `Vector*` / `Packed*Array` 等） | pack 后调用，必须 unpack 回写 | 仅 non-ref |
| 不支持 set | 由 `isUnsupportedSetSelfType` 判定 | 编译期 fail-fast | 不适用 |

补充约束（按指令模式）：

1. `variant_set_keyed`：非 Variant self 必须是 `Object`/`Dictionary`。
2. `variant_set_named`：非 Variant self 必须命中 named 支持集。
3. `variant_set_indexed`：非 Variant self 必须命中 indexed 支持集。
4. `ref` 且需要 writeback 的 self（例如 `ref Packed*Array`）必须 fail-fast。

### 4.4 key/value/index 的 ref 语义（当前实现）

1. `materializeVariantOperand` 已允许 key/value 为 ref 或 non-ref。
2. 非 Variant key/value 在 ref 情况下同样允许 pack。
3. indexed 的 `index:int` 在 GET/SET 两侧均允许 ref 或 non-ref。

---

## 5. 代码生成与生命周期规范

### 5.1 GET 通用流程

1. 校验 result/self/operand。
2. 对 non-Variant `self`、`key` 执行 pack（如需）。
3. 声明未初始化 `r_ret` 与 `r_valid`（indexed 额外 `r_oob`）。
4. 发射 `godot_variant_get*`。
5. 先检查 `r_valid`，indexed 再检查 `r_oob`。
6. 成功路径将 `r_ret` 写回：
   - `Variant -> Variant`：`godot_new_Variant_with_Variant` 构造拷贝回写。
   - `Variant -> 非 Variant`：调用对应 unpack 函数回写。
7. 所有路径必须销毁 `r_ret` 及 pack 临时变量。

### 5.2 SET 通用流程

1. 校验无 `resultId`。
2. 校验并物化 self（含分类策略）。
3. 处理 key/index/value（key/value 非 Variant 时 pack）。
4. 发射 `godot_variant_set*`。
5. 检查 `r_valid`（indexed 额外检查 `r_oob`）。
6. 若 self 属于值语义写回路径，执行 unpack 回写。
7. 所有路径销毁临时变量（value/key/self）。

### 5.3 错误分支与资源销毁约束

1. 失败分支必须先销毁当前已初始化临时变量，再 `returnDefault()`。
2. 分支返回后需要恢复必要的临时变量初始化状态，避免后续路径析构语义被破坏。
3. 运行时错误信息必须包含指令名与关键操作数 ID（`self/key/name/index/result/value`）。

### 5.4 `r_ret` 专项约束

1. `r_ret` 使用 `declareUninitializedTempVar` 声明。
2. `r_valid=false` 路径下，`r_ret` 可能未初始化，需先置 `godot_new_Variant_nil()` 再销毁。
3. `r_oob=true` 路径下，`r_ret` 已写入，直接销毁。

---

## 6. 运行时能力边界与防线

### 6.1 引擎运行时能力（SET）

| 指令 | 运行时核心适用 self | 说明 |
|---|---|---|
| `variant_set` | `Dictionary` / `Object`（keyed）及按 key 分派到 named/indexed 的类型 | 语义由 key 形态与 self 共同决定 |
| `variant_set_keyed` | `Dictionary` / `Object` | 其他类型通常 `r_valid=false` |
| `variant_set_named` | 支持命名成员写入的值类型、`Object`、`Dictionary` | 常见如向量、颜色、变换等 |
| `variant_set_indexed` | 支持索引写入的类型 | 常见如 `String`、`Array`、`Dictionary`、`Packed*Array` 等 |

### 6.2 编译期与运行时双层防线

1. 编译期：对明确不支持 set 的 self 类型 fail-fast。
2. 运行时：统一生成 `r_valid` / `r_oob` 检查兜底。

### 6.3 ref 回写安全反思（长期有效）

1. Godot `call` 路径会先把参数解包到调用栈局部对象。
2. 对 ref 值语义对象做直接写回存在不安全风险。
3. 因此当前策略固定为：
   - 放行“无需回写”的 ref self（`Variant`/`Array`/`Dictionary`/`Object`）。
   - 拒绝“需要回写”的 ref self（如 `ref Packed*Array` 等）。

---

## 7. 测试基线与回归建议

### 7.1 单元测试事实源

1. `src/test/java/dev/superice/gdcc/backend/c/gen/IndexLoadInsnGenTest.java`
2. `src/test/java/dev/superice/gdcc/backend/c/gen/IndexStoreInsnGenTest.java`
3. `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`（opcode 注册分发）

### 7.2 引擎集成测试事实源

`src/test/java/dev/superice/gdcc/backend/c/gen/IndexStoreInsnGenEngineTest.java` 覆盖以下运行时锚点：

1. ref self：`Array` / `Dictionary` set 后无需回写可读回。
2. `PackedInt32Array`：局部写回路径正确读回。
3. ref index/value：`variant_set_indexed` + `variant_get_indexed` 读写一致。
4. ref key/value：`variant_set` + `variant_get` 读写一致。
5. ref named：`variant_set_named` + `variant_get_named` 读写一致。
6. ref String key/value：`variant_set_keyed` + `variant_get_keyed` 读写一致。

---

## 8. 关键实现锚点

| 用途 | 文件路径 |
|---|---|
| CInsnGen 接口 | `src/main/java/dev/superice/gdcc/backend/c/gen/CInsnGen.java` |
| CBodyBuilder | `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java` |
| CGenHelper | `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java` |
| CCodegen 注册入口 | `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java` |
| GET 生成器 | `src/main/java/dev/superice/gdcc/backend/c/gen/insn/IndexLoadInsnGen.java` |
| SET 生成器 | `src/main/java/dev/superice/gdcc/backend/c/gen/insn/IndexStoreInsnGen.java` |
| IndexingInstruction | `src/main/java/dev/superice/gdcc/lir/insn/IndexingInstruction.java` |
| GdInstruction | `src/main/java/dev/superice/gdcc/enums/GdInstruction.java` |
| 参考实现（Operator） | `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java` |
| 参考实现（Construct） | `src/main/java/dev/superice/gdcc/backend/c/gen/insn/ConstructInsnGen.java` |
| C Helper 头文件 | `src/main/c/codegen/include_451/gdcc/gdcc_helper.h` |
| GDExtension 声明 | `tmp/inspect_gdlite/generated/extension_interface.h` |

---

## 9. 非目标（当前不做）

1. 不新增或重构除 indexing 指令外的其他 LIR record。
2. 不改动 `IndexingInstruction` 接口与 unrelated 枚举语义。
3. 不在 `gdcc_helper.h` 新增 index 专用 C helper 包装（保持直接调用 gdextension-lite API）。
4. 不在本轮扩展到 indexed/named 失败分支（`r_valid=false`/`r_oob=true`）的引擎级故障注入测试。
5. 不引入 variant_get/set 结果类型的额外编译期强类型约束（依赖运行时 `r_valid` 与类型检查链路）。

---

## 10. 工程反思（长期保留）

1. Indexing 指令最容易出错的点不是调用语句本身，而是 Variant 生命周期与错误分支析构对称性。
2. `ref` 语义与“是否需要 writeback”必须分离看待：ref 并不自动等价可安全回写。
3. named/indexed 必须坚持变量操作数模型，避免字面量模型与解析器/序列化器再次漂移。
4. 文档应长期保持“当前事实 + 长期约定 + 风险边界”，不再回填实施流水，避免事实源失真。
