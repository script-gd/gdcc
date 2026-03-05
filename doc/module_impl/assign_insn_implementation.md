# `assign` 指令（Low IR）C 后端实现计划

> 目标：为 Low IR 新增 `assign` 指令补齐从 **IR 解析/序列化 → LIR 指令模型 → C 后端指令生成器 → 单测验收** 的完整链路。
>
> 校对基线：2026-03-05（以当前代码库为准；本文是实施计划，不包含实现产物）

---

## 1. 背景与语义锚点

### 1.1 Low IR 语义（规范来源）

`doc/gdcc_low_ir.md` 定义：

- `assign`：把一个变量赋值给另一个变量。
- 约束：source 类型必须与 result 类型一致，或 **可隐式转换** 到 result 类型。

语法：

```text
$<result_id> = assign $<source_id>
```

### 1.2 C 后端槽位写入语义（实现事实源）

`CBodyBuilder#assignVar(...)` 已落地统一槽位写入模型（详见 `doc/module_impl/cbodybuilder_implementation.md` 与 `doc/gdcc_c_backend.md`）：

- 对象槽位写入：`capture old -> assign(convert ptr if needed) -> own(BORROWED only) -> release captured old`
- 非对象槽位写入：`prepare/copy rhs -> destroy old (if needed) -> assign`

结论：`assign` 指令生成应尽量 **薄封装**，把生命周期/ownership/指针转换统一交给 `CBodyBuilder#assignVar(...)`。

---

## 2. 现状调研（差距清单）

基于当前代码库（2026-03-05）检索结果：

1. 文档已出现 `assign`（`doc/gdcc_low_ir.md`），但 **代码侧缺口**：
   - `src/main/java/dev/superice/gdcc/enums/GdInstruction.java` 中 **没有** `ASSIGN` opcode。
   - `src/main/java/dev/superice/gdcc/lir/insn/` 中 **没有** `AssignInsn`（或等价 record）。
   - `src/main/java/dev/superice/gdcc/lir/parser/ParsedLirInstruction.java` 的 `toConcrete()` switch 中 **没有** `ASSIGN` 分支。
   - C 后端 `src/main/java/dev/superice/gdcc/backend/c/gen/insn/` 中 **没有**对应 `CInsnGen`。
2. 但后端已有成熟“赋值语义管道”：
   - `CBodyBuilder#assignVar(...)` 已集中实现对象/非对象写入、类型可赋值性校验与 ptr 转换。

因此：本次实现需要补齐“指令建模 + 解析/序列化 + generator + 单测”，而不是重写赋值语义。

---

## 3. 设计决策（实施前先定）

### 3.1 `assign` 的生成策略

- **唯一推荐路径**：`AssignInsnGen` 调用 `CBodyBuilder#assignVar(targetOfVar(resultVar), valueOfVar(sourceVar))`。
- 不在生成器中手写：
  - destroy/own/release 顺序
  - GDCC/Godot ptr 转换
  - destroyable builtin copy 规则

### 3.2 `ref` 变量写入范围（必须明确）

当前 `CBodyBuilder#targetOfVar(...)` 明确拒绝 `ref=true` 目标（会 fail-fast）。

实施选择（建议）：

- **MVP：`assign` 禁止写入 `ref=true` 变量**，与现有 `assignVar/targetOfVar` 契约一致。
- 若未来确实需要“写入 ref 变量（out-parameter 形式）”：
  - 不建议扩大 `assign` 语义；
  - 更建议新增单独的 IR 指令（如 `copy_to_ref` / `store_ref`）或新增 Builder API 专门处理 out-init 模式，避免把通用 assign 变成语义大杂烩。

---

## 4. 分步骤实施计划（含验收细则）

### Phase A：补齐 IR opcode 与 LIR 指令模型

**A1. 新增 opcode**

- 修改：`src/main/java/dev/superice/gdcc/enums/GdInstruction.java`
- 新增：
  - `ASSIGN("assign", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE), 1, 1)`
- 验收：
  - `SimpleLirBlockInsnParser` 能通过 `OPCODE_MAP` 识别 `assign`。
  - 不影响既有 opcode 的 operandKinds/min/max 约束。

**A2. 新增 LIR 指令 record**

- 新增：`src/main/java/dev/superice/gdcc/lir/insn/AssignInsn.java`
- 建议形态：
  - `public record AssignInsn(@NotNull String resultId, @NotNull String sourceId) implements LirInstruction { ... }`
  - `operands()` 返回 `List.of(new VariableOperand(sourceId))`
  - `opcode()` 返回 `GdInstruction.ASSIGN`
- 验收：
  - `AssignInsn.resultId()` 非空（由 record 字段保障）。
  - `operands()` 与 `GdInstruction.ASSIGN` 的 operandKinds 严格匹配。

**A3. 解析器 concrete 映射**

- 修改：`src/main/java/dev/superice/gdcc/lir/parser/ParsedLirInstruction.java`
- 在 `toConcrete()` 的 switch 增加：
  - `case ASSIGN -> new AssignInsn(Objects.requireNonNull(resultId), ((VariableOperand) operands.getFirst()).id());`
- 验收：
  - `$a = assign $b;` 可解析为 `AssignInsn("a","b")`。
  - `resultId` 缺失时 parser 报错位置与现有行为一致（由 ReturnKind.REQUIRED 驱动）。

**A4. Parser/Serializer 单测补齐**

- 修改/新增：
  - `src/test/java/dev/superice/gdcc/lir/parser/SimpleLirBlockInsnParserTest.java`
  - `src/test/java/dev/superice/gdcc/lir/parser/SimpleLirBlockInsnSerializerTest.java`
- 建议新增用例：
  - parse：`$a = assign $b;` -> `AssignInsn`，字段正确。
  - serialize：`new AssignInsn("a","b")` -> 包含 `$a = assign $b;`
- 验收命令：
  - `./gradlew.bat test --tests SimpleLirBlockInsnParserTest --no-daemon --info --console=plain`
  - `./gradlew.bat test --tests SimpleLirBlockInsnSerializerTest --no-daemon --info --console=plain`

---

### Phase B：实现 C 后端 `AssignInsnGen`

**B1. 新增生成器类**

- 新增：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/AssignInsnGen.java`
- 实现要点：
  - `getInsnOpcodes()` 返回 `EnumSet.of(GdInstruction.ASSIGN)`
  - `generateCCode(CBodyBuilder bodyBuilder)`：
    1. 校验 `resultId != null`（按惯例：缺失 resultId 直接 `invalidInsn`）
    2. resolve `resultVar` / `sourceVar` 存在性
    3. 调用：
       - `var target = bodyBuilder.targetOfVar(resultVar);`（自动拒绝 ref 目标）
       - `bodyBuilder.assignVar(target, bodyBuilder.valueOfVar(sourceVar));`
- 验收：
  - 类型不兼容时 fail-fast（最终由 `CBodyBuilder#checkAssignable(...)` 抛 `InvalidInsnException`）
  - 不出现任何手写 own/release/destroy/ptr-convert 字符串拼接

**B2. 注册到 CCodegen**

- 修改：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- 在 static 注册区增加 `registerInsnGen(new AssignInsnGen());`（建议放在 `NewDataInsnGen()` 之后、property/store 之前，保持分类清晰）
- 验收：
  - `CCodegen.generateFuncBody(...)` 遇到 `ASSIGN` 不再抛 `Unsupported instruction opcode: assign`

**B3. 生成器单测（核心验收）**

- 新增：`src/test/java/dev/superice/gdcc/backend/c/gen/CAssignInsnGenTest.java`
- 覆盖建议（最小但要“抓语义”）：
  1. `int`：`$a = assign $b` 生成 `$a = $b;`
  2. destroyable builtin（如 `String`）：
     - 构造 `b`（literal_string 或其他路径）后 `assign` 到 `a`
     - 断言生成代码走 copy/construct，而不是浅赋值（具体断言以当前 `CBodyBuilder#prepareRhsValue(...)` 产物为准）
  3. `Object`（RefCounted/Node 等）：
     - 至少断言生成代码进入对象槽位写入路径（出现 `try_own_object` / `try_release_object` 或对应 helper 调用）
     - 若要断言顺序：使用“两次写入同一目标”触发 `capture old -> ... -> release old` 的分支
  4. 负例：source/target 类型不可赋值时抛 `InvalidInsnException`
- 验收命令：
  - `./gradlew.bat test --tests CAssignInsnGenTest --no-daemon --info --console=plain`
  - （可选回归）`./gradlew.bat test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain`

---

### Phase C：文档同步与回归门禁

**C1. 文档一致性检查**

- 确认以下文档与实现一致：
  - `doc/gdcc_low_ir.md`：`assign` 仍为 `$result = assign $source`（result 必须存在）
  - `doc/gdcc_c_backend.md`、`doc/module_impl/cbodybuilder_implementation.md`：槽位写入顺序不被 `assign` 绕过
- 若发现文档与实现冲突：优先修正文档（或在实现计划中记录决策），避免“文档先行但行为漂移”。

**C2. 最终回归建议**

实现完成后（合入前）建议至少跑：

```bash
./gradlew.bat classes --no-daemon --info --console=plain
./gradlew.bat test --tests SimpleLirBlockInsnParserTest --no-daemon --info --console=plain
./gradlew.bat test --tests SimpleLirBlockInsnSerializerTest --no-daemon --info --console=plain
./gradlew.bat test --tests CAssignInsnGenTest --no-daemon --info --console=plain
```

---

## 5. 风险点与防御性策略

1. **`ref` 变量写入语义不清**：
   - 本计划明确 MVP 禁止。
   - 若未来需求出现，建议新增专用指令/Builder API，而不是扩写 `assign`。
2. **隐式转换矩阵的归属**：
   - `assign` 不负责定义“哪些类型可隐式转换”；
   - 统一以 `ClassRegistry#checkAssignable(...)` 为准（生成器只触发 fail-fast）。
3. **对象 ptrKind 与 helper 匹配**：
   - 生成器必须只用 `valueOfVar(...)` + `assignVar(...)`，避免手写转换导致 helper 不匹配或 NULL 解引用风险。
4. **回归断言过于脆弱**：
   - 单测优先断言“关键语义点”（是否走对象/非对象写槽、是否出现 own/release、是否出现 destroyable copy），
     避免对格式化细节（空格、临时变量名）做硬编码。

---

## 6. 关键实现锚点（便于落地）

| 用途 | 文件路径 |
|---|---|
| opcode 枚举 | `src/main/java/dev/superice/gdcc/enums/GdInstruction.java` |
| LIR 基类 | `src/main/java/dev/superice/gdcc/lir/LirInstruction.java` |
| 文本解析入口 | `src/main/java/dev/superice/gdcc/lir/parser/SimpleLirBlockInsnParser.java` |
| concrete 映射 | `src/main/java/dev/superice/gdcc/lir/parser/ParsedLirInstruction.java` |
| 文本序列化 | `src/main/java/dev/superice/gdcc/lir/parser/SimpleLirBlockInsnSerializer.java` |
| CCodegen opcode 分发 | `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java` |
| CBodyBuilder 赋值管道 | `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java` |
| 参考生成器（Pack/Unpack） | `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PackUnpackVariantInsnGen.java` |
| 参考生成器（NewData） | `src/main/java/dev/superice/gdcc/backend/c/gen/insn/NewDataInsnGen.java` |

