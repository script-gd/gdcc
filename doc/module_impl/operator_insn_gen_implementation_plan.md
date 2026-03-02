# OperatorInsnGen 实施计划（`unary_op` / `binary_op`）

## 文档状态

- 状态：`Active / Stage 7 Completed`
- 更新时间：`2026-03-02`
- 适用范围：`backend.c` 中 `UNARY_OP`、`BINARY_OP` 的 C 代码生成与校验
- 本文目标：给出可直接落地的工程计划，不在本轮文档内提交实现代码
- 收敛说明：`2026-03-02` 起以“第 0 节语义基线”为唯一事实源；与基线冲突的历史描述均视为失效。

---

## 0. 当前语义基线（2026-03-02）

1. `swap/dual` 回退当前 **暂不支持**（`unary_op` 与 `binary_op` 均不支持）。
2. `binary_op` 的 metadata 仅按原顺序 `(leftType, op, rightType)` 匹配；不做隐式交换，不做对偶操作符替换。
3. “Variant 统一 evaluate”当前仅覆盖 `binary_op`：
   - 当左右任一操作数为 `Variant` 时，统一走 `godot_variant_evaluate`。
   - 不再尝试 ptr-evaluator 优先路径。
4. `unary_op` 当前 **不** 走 `godot_variant_evaluate`，仍走 metadata + builtin evaluator 路径。
5. `IN` 仅允许原顺序解析（`left IN right`），不支持任何交换/对偶补救。
6. `variant_evaluate` 路径启用编译期结果类型校验：语义结果类型固定为 `Variant`，`result` 非 `Variant` 时直接 fail-fast。
7. `Variant -> Variant` 回写必须走构造拷贝（`godot_new_Variant_with_Variant` + `callAssign`），禁止“浅拷贝赋值 + 临时变量销毁”模式。
8. primitive 快路径当前统一发射 guard，策略为：
   - `ADD/SUBTRACT/MULTIPLY` 不做整型溢出 guard（仅保留 NOOP guard 占位）。
   - `DIVIDE/MODULE`：整型仅检查除数为 `0`；浮点同样生成除数为 `0.0` guard。
   - `SHIFT_LEFT/SHIFT_RIGHT`：检查移位量非法；`SHIFT_LEFT` 额外拒绝负左操作数（避免 C UB）。

---

## 1. 背景与目标

当前 Low IR 已支持：

- `unary_op`
- `binary_op`

但 C 后端尚未实现对应 `InsnGen`，`CCodegen` 也未注册这两个 opcode。

本计划目标：

1. 新增 `OperatorInsnGen`，支持 `UNARY_OP` / `BINARY_OP`。
2. 按类型分流生成：
   - 对可静态判定的 primitive 快路径直接生成 C 表达式。
   - `POWER` 在快路径中分流为：含浮点使用 `pow`，纯整型使用 `pow_int`。
   - 非快路径 builtin 走 operator evaluator（`godot_variant_get_ptr_operator_evaluator`）。
   - `binary_op` 中只要任一操作数为 `Variant`，统一走 `godot_variant_evaluate` 动态派发。
   - `unary_op` 维持 metadata + builtin evaluator，不走 `godot_variant_evaluate`。
   - 比较运算仅对基本类型、`Object`、`Nil` 做特化。
3. 在 `CBodyBuilder` 增加“声明未初始化变量”方法，保障 `Variant evaluate` 返回槽位语义。
4. 保持全路径 fail-fast：不支持的运算/类型组合抛 `InvalidInsnException`。

---

## 2. 现状与证据

### 2.1 后端现状

- `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
  - 已注册多类 `InsnGen`，但未注册 `UNARY_OP` / `BINARY_OP`。
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/`
  - 当前无 `OperatorInsnGen`。

### 2.2 IR 与枚举现状

- `src/main/java/dev/superice/gdcc/lir/insn/UnaryOpInsn.java`
- `src/main/java/dev/superice/gdcc/lir/insn/BinaryOpInsn.java`
- `src/main/java/dev/superice/gdcc/enums/GodotOperator.java`

### 2.3 本地头文件锚点

- `tmp/test/c_build/include/gdextension-lite/generated/extension_interface.h:30`
  - `godot_variant_evaluate(...)` 声明。
- `tmp/test/c_build/include/gdextension-lite/generated/extension_interface.h:59`
  - `godot_variant_get_ptr_operator_evaluator(...)` 声明。
- `tmp/test/c_build/include/gdextension-lite/generated/extension_interface.h:146`
  - `godot_object_get_instance_id(...)` 声明。
- `src/main/c/codegen/include_451/gdcc/gdcc_operator.h:4`
  - `pow_int(godot_int base, godot_int exp)` 声明。
- `src/main/c/codegen/include_451/gdcc/gdcc_helper.h:9`
  - 已包含 `gdcc_operator.h`。

### 2.4 运算符枚举锚点

- `tmp/test/c_build/include/gdextension-lite/gdextension/gdextension_interface.h:104`
  - `GDExtensionVariantOperator` 枚举定义。
- 特别注意：
  - `BIT_NOT` 对应 `GDEXTENSION_VARIANT_OP_BIT_NEGATE`。

---

## 3. 目标语义（最终约束）

### 3.1 通用输入输出约束

- `resultId` 必须存在且对应非 `ref` 变量。
- 操作数变量必须存在。
- `result` 类型必须可接收运算结果类型（`ClassRegistry#checkAssignable`）。
- 任一校验失败立即抛 `InvalidInsnException`。

### 3.2 `unary_op` 语义

- 读取 `(op, operand)`。
- evaluator 查询时 `type_b` 固定 `GDEXTENSION_VARIANT_TYPE_NIL`。
- 当前实现统一走 metadata + builtin evaluator 路径（不走 `godot_variant_evaluate`，也不做 unary 快路径特化）。

### 3.3 `binary_op` 语义

- 读取 `(op, left, right)`。
- metadata 仅按原顺序 `(leftType, op, rightType)` 匹配。
- 不支持隐式交换，不支持对偶操作符回退。
- 原顺序不命中时直接 fail-fast。

### 3.4 比较特化范围

比较运算仅对以下类型特化：

1. 基本类型（`bool/int/float`）。
2. `Object`（仅 `==` / `!=`）。
3. `Nil`（仅比较运算）。

其他类型（向量、容器、Variant 等）不走比较特化。

### 3.5 Object 比较特化（`==` / `!=`）

仅当左右两侧均为 `Object` 类型（含 engine/GDCC 对象）时生效：

- `==`：
  - 双 `NULL` -> `true`
  - 单侧 `NULL` -> `false`
  - 双非空 -> `godot_object_get_instance_id(left) == godot_object_get_instance_id(right)`
- `!=`：上述结果取反。
- 其他 Object 运算符：fail-fast。

### 3.6 Nil 比较特化

- `Nil == Nil` -> `true`。
- `Nil != Nil` -> `false`。
- `Nil` 与任意非 `Nil` 值比较：
  - 若对方是 `Object` 且为 `null`，视为相等。
  - 其余情况一律不相等。
- `Nil` 的非比较运算不特化，按默认分流规则处理。

### 3.7 primitive 快路径（`bool` / `int` / `float`）

快路径必须同时满足：

1. `(leftType, op, rightType)` 命中快路径白名单矩阵。
2. metadata 确认该运算签名可用。

补充约束：

- 快路径白名单必须使用三元矩阵：`(leftType, op, rightType)`。
- `POWER` 不使用 C 原生运算符写法，统一按 `pow/pow_int` 分流实现快路径。
- 逻辑 `XOR` 不可直接按位异或泛化，非 `bool` 场景需按逻辑 xor 语义表达式生成。
- 快路径 guard 规则：
  - 所有 primitive 快路径都会生成 guard（允许 NOOP guard）。
  - 不在 guard 中做整型加减乘溢出判定。
  - `DIVIDE/MODULE` 对整型与浮点路径均生成“除数为 0” guard。
  - `SHIFT_LEFT` 不做结果溢出判定，仅做 UB 相关前置检查（移位量范围 + 左操作数非负）。

`POWER` 快路径规则：

- 只要任一操作数为浮点类型：生成 `pow`（来自 `math.h`）。
- 两个操作数均为整型：生成 `pow_int`（来自 `gdcc_operator.h`）。
- 两种情况都仍归入快路径。

### 3.8 Variant 参与运算策略

当前策略按操作类型区分：

1. `binary_op`：只要任一操作数为 `Variant`，统一走 `godot_variant_evaluate`。
   - 编译期语义结果类型固定为 `Variant`；`result` 必须是 `Variant` 兼容类型。
2. `unary_op`：不走 `godot_variant_evaluate`，保持 metadata + builtin evaluator。
3. `IN` 不参与 primitive 快路径，仅允许原顺序解析（`left IN right`）。
4. `Variant -> Variant` 回写统一经 `godot_new_Variant_with_Variant` 构造拷贝后写入目标槽位。

---

## 4. 运算符映射与 metadata 规则

### 4.1 `GodotOperator` -> `GDExtensionVariantOperator`

计划在 resolver 中维护确定性映射表，覆盖 `GodotOperator` 全量值。

### 4.2 metadata 匹配规则

- unary 匹配：`rightType` 采用空串语义（归一化后匹配）。
- binary 匹配：按 `(leftType, op, rightType)` 查找。
- 原顺序不命中：直接 fail-fast（不做交换/对偶补救）。
- metadata 异常记录（如空类型名、非法操作符字符串）必须按统一容错策略处理：跳过异常条目并输出可定位告警；若最终无可用匹配则 fail-fast。

### 4.3 结果类型规则

- 快路径、evaluator 路径、Variant evaluate 路径都必须先解析“语义结果类型”。
- 若结果类型与 `result` 不兼容，直接 fail-fast。
- 在 `binary_op` 的 Variant evaluate 路径中，语义结果类型固定为 `Variant`；因此该路径不允许写入 `bool/int/String/...` 等非 Variant 结果变量。

建议实现入口：

- 增加统一规则函数（例如 `resolveOperatorReturnType(leftType, op, rightType)`）作为结果类型单一事实源。
- 快路径不能绕过结果类型判定；必须先过 metadata 与兼容性校验后再生成 C 表达式。

### 4.4 `GdType -> GDExtensionVariantType` 映射规则

- evaluator 与 `variant_evaluate` 路径必须使用确定性映射规则。
- 映射来源以 `GdType.getGdExtensionType()` 为基线，再转换为对应的 C 枚举常量名。
- 映射失败属于编译期错误，直接 fail-fast。

---

## 5. 生成路径设计

### 5.1 总体分流顺序

1. 比较特化（基本类型 / Object / Nil）。
2. primitive 快路径（含 `POWER -> pow/pow_int`）。
3. `binary_op` 的 Variant 参与路径：统一走 `godot_variant_evaluate`。
4. 非 Variant builtin：operator evaluator 路径（严格原顺序 metadata 匹配）。
5. 其余 -> fail-fast。

补充约束：

- `swap/dual` 回退当前暂不支持；所有 binary 解析均按原顺序执行。
- Variant 参与路径不得绕过结果类型与生命周期规则。

### 5.2 `godot_variant_evaluate` 槽位策略

- `r_return` 使用未初始化槽位语义。
- 先写入“未初始化临时变量”，再通过 `CBodyBuilder.callAssign` 写回目标变量。
- 禁止直接把 `r_return` 指向已初始化目标槽位。

补充约束：

- 当只有一侧是 `Variant` 时，仍需按约定完成 `pack -> evaluate` 流程。
- 当前语义下，`variant_evaluate` 的结果写回目标变量时不做 `unpack`；非 Variant `result` 在编译期直接 fail-fast。
- `Variant` 结果写回必须使用 `godot_new_Variant_with_Variant` 构造拷贝，避免浅拷贝后销毁临时值导致悬挂。
- 对 destroyable/value-semantic 结果，强制经 `assignVar/callAssign` 进入目标槽位，禁止裸写目标变量。

### 5.3 evaluator helper（缓存）

建议模板生成：

- `gdcc_eval_unary_<op>_<type_a>(...)`
- `gdcc_eval_binary_<op>_<type_a>_<type_b>(...)`

helper 内部：

1. `static GDExtensionPtrOperatorEvaluator evaluator = NULL;`
2. 首次调用查询并缓存。
3. `evaluator == NULL` 立即 hard-fail。
4. 调用 evaluator 产出结果。

运行时失败风格：

- `valid == false` 与 `evaluator == NULL` 均按后端统一运行时错误风格处理。
- 编译期可判定的问题（例如 metadata 缺失、类型不兼容）优先抛 `InvalidInsnException`，不延后到运行时。

### 5.4 快路径表达式策略

- 普通算数、位、基础逻辑按白名单矩阵生成。
- `POWER`：
  - 浮点参与 -> `pow`。
  - 双整型 -> `pow_int`。
- 快路径 guard：
  - `DIVIDE/MODULE`：整型 `gdcc_int_division_by_zero`，浮点 `gdcc_float_division_by_zero`。
  - `SHIFT_LEFT/SHIFT_RIGHT`：使用 `gdcc_int_shift_left_invalid` / `gdcc_int_shift_right_invalid`。
  - 其他 primitive op 发射 NOOP guard，保持生成结构一致。
- `IN` 永不进入快路径，统一走原顺序 metadata + evaluator/variant_evaluate 分流。

---

## 6. 代码改动清单（计划）

### 6.1 新增生成器

- 新文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`

### 6.2 新增 resolver

- 新文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
- 负责：
  - 类型分流
  - metadata 匹配
  - 结果类型判定
  - `GdType -> GDExtensionVariantType` 映射

### 6.3 扩展 `CBodyBuilder`

- 新增“声明未初始化变量”方法（例如 `declareUninitializedTempVar`）。
- 该方法只声明变量，不做初始化，专用于 `Variant evaluate` 返回槽位。

### 6.4 注册 opcode

- 修改：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- 注册 `OperatorInsnGen`。

### 6.5 模板与头文件

- 修改：`src/main/c/codegen/template_451/entry.c.ftl`
- 确保生成代码可用 `pow`（`math.h`）与 `pow_int`（`gdcc_operator.h`）。

---

## 7. 失败策略（Fail-Fast）

以下场景必须 `InvalidInsnException`：

1. result 变量缺失 / `ref` 变量作为 result。
2. 操作数变量缺失。
3. 结果类型与 `result` 类型不兼容。
4. metadata 原顺序签名不支持（无交换/对偶回退）。
5. `Object` 使用 `==` / `!=` 以外运算。
6. `Nil` 比较特化条件不满足却误入特化分支。
7. `POWER` 快路径分支无法判定应走 `pow` 还是 `pow_int`。
8. `godot_variant_evaluate` 返回 `valid == false`。
9. evaluator 查询返回 `NULL`。
10. 生成路径绕过 `assignVar/callAssign` 生命周期管道。
11. `variant_evaluate` 语义结果为 `Variant`，但 `result` 为非 Variant 类型（例如 `bool/int/String`）。

---

## 8. 测试计划

建议新增：`src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenTest.java`

核心用例矩阵：

1. `POWER(float,int)` 走 `pow`。
2. `POWER(int,int)` 走 `pow_int`。
3. `MODULE(float,float)` 快路径命中。
4. 原顺序 metadata 严格匹配：`A > B` 原顺序不可用时直接 fail-fast。
5. 非交换运算（如 `SUBTRACT` / `DIVIDE`）不允许隐式交换。
6. Variant 混合场景（`binary_op`）：只要任一操作数为 `Variant`，统一走 `godot_variant_evaluate`（即使 metadata 可用）。
   - 且 `result` 必须是 `Variant` 兼容类型；非 Variant `result` 需在编译期 fail-fast。
7. Variant 运行时失败场景：`godot_variant_evaluate` 返回 `valid == false` 时应 hard-fail 并返回函数默认值。
8. `Nil == Nil`、`Nil != Nil`。
9. `Nil == Object(null)` 与 `Nil == 非空 Object`。
10. `Nil` 与其他非 Object 类型比较（应不等）。
11. `godot_variant_evaluate` 使用未初始化临时槽，并经 `callAssign` + `godot_new_Variant_with_Variant` 回写 `Variant` 结果。
12. result 为 `ref` 时 fail-fast。
13. `IN(int, Array)` 不走快路径，按原顺序 metadata 命中 evaluator，并通过结果类型校验。
14. `variant_evaluate` 路径中 `result` 为非 Variant 类型时编译期 fail-fast（类型安全守卫）。
15. `DIVIDE(float,float)` 与 `MODULE(float,float)` 快路径会生成浮点除零 guard。
16. `ADD/SUBTRACT/MULTIPLY(int,int)` 快路径不生成溢出 guard（仅 NOOP guard 占位）。
17. `SHIFT_LEFT(int,int)` guard 仅覆盖 UB 条件（非法移位量、负左操作数），不覆盖结果溢出。

辅助回归：

- `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`。

### 8.1 引擎集成测试目标

引擎集成测试用于验证“生成代码在真实 Godot 运行时中的语义一致性”，重点覆盖单元测试难以发现的问题：

1. `binary_op` 的 `godot_variant_evaluate` 与 `unary_op` builtin evaluator 在真实引擎中的可用性与返回语义。
2. `POWER -> pow/pow_int`、比较特化与“原顺序 metadata 严格匹配”在运行时的行为一致性。
3. 字符串、向量、矩阵等非 primitive 运算在真实引擎中的行为一致性。
4. `binary_op` Variant 回写与生命周期管道（`assignVar/callAssign`）是否在高频调用下稳定。
5. 运行时失败路径（`valid == false`、`evaluator == NULL`）是否按计划 hard-fail 且可观测。

### 8.2 测试分层与门禁级别

定义 3 层引擎集成测试，避免一次性把所有场景都放入阻塞门禁：

1. `L1-SMOKE`（阻塞）：
   - 覆盖最核心路径：primitive 快路径、Object/Nil 比较、`binary_op` Variant evaluate。
   - 每次 PR 必跑。
2. `L2-SEMANTIC`（阻塞）：
   - 覆盖运算语义矩阵与原顺序匹配约束（`IN` 原顺序约束、结果类型一致性）。
   - 与 `COperatorInsnGenTest` 同步维护。
3. `L3-STRESS`（非阻塞，夜间或手动）：
   - 高频循环与大样本输入，关注生命周期与稳定性。
   - 失败不会阻塞合并，但必须生成 issue 或 TODO 追踪。

### 8.3 环境与版本矩阵

基线环境：

1. Godot：`4.5.1`（与 `template_451` / `include_451` 对齐）。
2. Zig：用于 backend 产物编译（遵循现有集成测试配置）。
3. 运行模式：`headless`，确保 CI 与本地可复现。

建议执行矩阵：

1. `Windows + Godot 4.5.1 + Debug`（阻塞）。
2. `Windows + Godot 4.5.1 + Release`（阻塞）。
3. `Linux + Godot 4.5.1 + Release`（可选，建议逐步提升为阻塞）。

环境缺失策略（必须）：

1. Godot 或 Zig 不可用时，测试 `skip` 并输出明确原因。
2. `skip` 不得伪装为通过，报告中需单独统计。

### 8.4 测试资产与目录规划

建议目录（计划）：

1. `src/test/resources/integration/operator_insn/ir/`：用于集成测试的最小 IR 输入样本。
2. `src/test/resources/integration/operator_insn/expected/`：期望结果（JSON/TXT 快照）。
3. `tmp/integration/operator_insn/build/`：临时编译产物（C 代码、二进制、日志）。
4. `tmp/integration/operator_insn/godot_project/`：临时 Godot 工程与驱动脚本。

产物归档（失败时）：

1. 生成的 C 片段（定位对应 operator 路径）。
2. 编译日志（Zig 与链接阶段）。
3. Godot stdout/stderr 与断言输出。

### 8.5 场景矩阵（引擎级）

建议以“输入 IR -> 编译 -> 运行 Godot 场景 -> 断言结果”的方式组织：

1. `E1-PRIMITIVE-POWER`：
   - `POWER(float,int)` 与 `POWER(int,int)`。
   - 断言 `pow`/`pow_int` 路径行为与预期值一致。
2. `E2-COMPARE-OBJECT-NIL`：
   - Object `==/!=` 三态（双 null、单 null、双非空）。
   - `Nil` 与 `Nil`、`Object(null)`、非 Object 的比较语义。
3. `E3-METADATA-ORDER-STRICT`：
   - 构造 `A op B` 原顺序 metadata 缺失场景。
   - 断言编译期 fail-fast，错误信息包含签名上下文。
4. `E4-IN-NON-SWAP`：
   - 验证 `IN` 仅原顺序解析，不触发交换/对偶回退。
5. `E5-VARIANT-MIXED`：
   - `binary_op` 中只要任一操作数为 `Variant`，统一走 `godot_variant_evaluate`。
   - 覆盖 pack 与 `Variant` 构造拷贝回写生命周期路径。
   - 覆盖 `Variant("a") + Variant("b") -> Variant` 的字符串拼接语义一致性。
6. `E6-RUNTIME-FAIL-PATH`：
   - 人工构造 `valid == false` 或 evaluator 缺失场景。
   - 断言 hard-fail 与错误消息格式。
7. `E7-LIFECYCLE-STRESS`：
   - 高频调用 destroyable/value-semantic 结果路径。
   - 检查是否出现异常崩溃或明显资源异常增长。
8. `E8-STRING-OPS`：
   - 覆盖 `String + String`、`String == String`。
   - 断言拼接与比较结果与 GDScript 基线一致。
9. `E9-VECTOR-OPS`：
   - 覆盖 `Vector2 + Vector2`（可扩展 `Vector3`）。
   - 断言返回向量分量与引擎直接运算结果一致。
10. `E10-MATRIX-OPS`：
   - 覆盖 `Transform2D * Transform2D`（后续可扩展 `Transform3D`）。
   - 断言矩阵/基向量/平移分量与引擎直接运算结果一致。

### 8.6 执行流程（单次集成测试）

标准流程：

1. 准备测试 IR 与期望快照。
2. 调用现有 backend 流程生成 C 代码。
3. 使用 Zig 编译为可被 Godot 加载的产物。
4. 启动 Godot `headless` 执行测试驱动场景/脚本。
5. 收集输出并与 `expected` 比对。
6. 失败时归档产物并输出定位信息。

建议脚本化入口（计划）：

1. `script/run-engine-integration-tests.ps1 -Suite operator_insn -GodotExe <path> -Mode <Debug|Release>`
2. 若短期不新增脚本，可先在 JUnit 集成测试中封装上述流程，保持单命令执行体验。

### 8.7 与阶段计划的映射关系

将引擎集成测试分批接入各阶段，避免集中爆炸：

1. 阶段 2 完成后：接入 `E2`（比较特化）。
2. 阶段 4 完成后：接入 `E1`（POWER 快路径）。
3. 阶段 5 收敛后：接入 `E3` + `E4`（原顺序匹配约束与 `IN` 约束）。
4. 阶段 6 完成后：接入 `E5` + `E6` + `E7`（Variant 与生命周期）。
5. 阶段 7 收尾：全量 `E1~E10` 作为合并前门禁回归。

### 8.8 验收标准与失败归因

通过标准：

1. `L1-SMOKE`、`L2-SEMANTIC` 全部通过。
2. 阻塞矩阵（Windows Debug/Release）无失败。
3. 失败信息可直接映射到路径分类：快路径、builtin evaluator、variant_evaluate、比较特化、原顺序 metadata 约束。

失败归因顺序（固定）：

1. 先判定是否环境问题（Godot/Zig/动态库加载）。
2. 再判定是否 metadata 匹配与结果类型判定问题。
3. 再判定是否路径决策错误（快路径/原顺序匹配/特化分支）。
4. 最后定位生命周期与运行时 hard-fail 分支。

回归报告最小字段：

1. 用例 ID（如 `E3-METADATA-ORDER-STRICT`）。
2. 输入类型签名与操作符。
3. 命中路径与关键约束信息（例如原顺序命中/失败、Variant evaluate 命中）。
4. Godot 日志摘要与产物路径。

### 8.9 当前实施进展

1. `2026-03-02`：已开始落地引擎集成测试，新增 `src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenEngineTest.java`。
2. 首批覆盖：字符串运算（`E8`）、向量运算（`E9`）、矩阵运算（`E10`）。
3. `2026-03-02`：修复 operator evaluator helper 的返回类型渲染错误，新增 `renderOperatorEvaluatorHelperReturnTypeInC`，避免 `String/Vector2/Transform2D` 被错误生成为指针返回类型。
4. `2026-03-02`：执行 `./gradlew test --tests COperatorInsnGenEngineTest --no-daemon --info --console=plain`，结果 `BUILD SUCCESSFUL`，`COperatorInsnGenEngineTest` 1/1 通过。
5. `2026-03-02`：执行 `./gradlew test --tests COperatorInsnGenTest --tests CCodegenTest --no-daemon --info --console=plain` 回归验证，结果 `BUILD SUCCESSFUL`，`COperatorInsnGenTest` 29/29、`CCodegenTest` 6/6 通过。
6. `2026-03-02`：补齐并落地 `E1~E7` 引擎集成测试，统一纳入 `COperatorInsnGenEngineTest`：
   - 运行时场景：`E1`（POWER 快路径）、`E2`（Object/Nil 比较特化）、`E4`（`IN` 原顺序正向）、`E5`（Variant mixed evaluate）、`E6`（`valid == false` 失败分支）、`E7`（生命周期压力）。
   - 编译期 fail-fast 场景：`E3`（`String > int` 原顺序 metadata 缺失）、`E4` 反向约束（`Array IN int`）。
7. `2026-03-02`：执行联合定向命令 `./gradlew test --tests COperatorInsnGenEngineTest --tests COperatorInsnGenTest --tests CCodegenTest --no-daemon --info --console=plain`。
8. `2026-03-02`：联合回归统计：
   - `COperatorInsnGenEngineTest` 4/4 通过（`0` skipped / `0` failures / `0` errors）。
   - `COperatorInsnGenTest` 29/29 通过（`0` skipped / `0` failures / `0` errors）。
   - `CCodegenTest` 6/6 通过（`0` skipped / `0` failures / `0` errors）。
9. `2026-03-02`：修复 `variant_evaluate` 的 `Variant -> Variant` 回写生命周期风险，改为 `godot_new_Variant_with_Variant` 构造拷贝后写回，避免“浅拷贝 + destroy”导致悬挂。
10. `2026-03-02`：为 `variant_evaluate` 路径补齐编译期结果类型校验；当 `result` 非 Variant 时直接 `InvalidInsnException`。
11. `2026-03-02`：新增引擎用例覆盖 `Variant("a") + Variant("b") -> Variant`，并新增编译期 fail-fast 用例覆盖 Variant 结果类型守卫。
12. `2026-03-02`：修复 `pow_int` 负指数死循环问题，负指数场景按 `base=1/-1/other` 分支返回；正指数路径保留快速幂并使用 `__int128` 中间计算提升截断前精度。
13. `2026-03-02`：primitive 快路径 guard 收敛为“全路径发射 + UB/除零导向”：
   - 去除 `ADD/SUBTRACT/MULTIPLY` 整型溢出 guard。
   - `DIVIDE/MODULE` 改为整型/浮点统一除零 guard（`gdcc_int_division_by_zero` / `gdcc_float_division_by_zero`）。
   - `SHIFT_LEFT` guard 去除结果溢出判定，仅保留非法移位量与负左操作数检查。
14. `2026-03-02`：执行 `./gradlew test --tests COperatorInsnGenTest --tests COperatorInsnGenEngineTest --no-daemon --info --console=plain`：
   - `COperatorInsnGenTest` 35/35 通过（`0` skipped / `0` failures / `0` errors）。
   - `COperatorInsnGenEngineTest` 4/4 通过（`0` skipped / `0` failures / `0` errors）。

---

## 9. 实施里程碑

### 阶段 0：准备与基线冻结

目标：

1. 冻结本计划中的语义规则，避免实现期间反复改口径。
2. 建立“每阶段必须可回归”的最小测试基线。

实施项：

1. 在 `OperatorResolver` 设计稿中先列出：分流顺序、原顺序匹配约束、结果类型判定入口。
2. 在测试文件中预留分组命名（比较、快路径、metadata 顺序约束、Variant 生命周期）。
3. 梳理受影响文件清单并确认仅触达后端相关代码与模板。

阶段输入：

1. 已确认第 3、4、5、7 节约束不再变更。

阶段输出：

1. 一份可直接映射到代码结构的实现清单（类/方法级）。
2. 一套最小 smoke 测试用例骨架（可先 `@Disabled` 占位）。

验收标准：

1. 变更范围清晰，无跨模块不必要改动。
2. 后续阶段均可在此基线上增量推进。

---

### 阶段 1：骨架接入与 fail-fast 基础能力

目标：

1. 接入 `UNARY_OP` / `BINARY_OP` 到 C 后端生成流程。
2. 先打通“识别 + 报错”的最小闭环。

实施项：

1. [x] 新增 `OperatorInsnGen`，接入指令分派入口。
2. [x] 新增 `OperatorResolver` 空框架，提供接口：路径决策、metadata 查询、结果类型解析。
3. [x] 在 `CCodegen` 注册 `OperatorInsnGen`。
4. [x] 完成基础参数校验：`resultId`、操作数存在性、`ref` result 拒绝。
5. [x] 对未实现路径统一抛 `InvalidInsnException`（含明确信息）。

涉及文件：

1. `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
2. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
3. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
4. `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`

验证命令：

```bash
./gradlew test --tests CCodegenTest --no-daemon --info --console=plain
```

验收标准：

1. 不再出现 `UNARY_OP` / `BINARY_OP` unsupported opcode。
2. 基础无效输入能稳定 fail-fast。

回退策略：

1. 若接入后影响其他指令生成，先保留注册但在生成器内短路为统一错误，保证行为可控。

---

### 阶段 2：比较特化（primitive/Object/Nil）

目标：

1. 先完成语义最清晰且风险可控的比较路径。
2. 固化 Object 与 Nil 特化边界，避免后续路径冲突。

实施项：

1. [x] 实现 primitive 比较特化（仅 `bool/int/float` 且满足 metadata）。
2. [x] 实现 Object `==/!=`：双空/单空/双非空 `instance_id` 比较。
3. [x] 实现 Nil 比较特化：`Nil==Nil`、`Nil!=Nil`、`Nil` 与 `Object(null)` 等值规则。
4. [x] 明确排斥路径：Object 非 `==/!=` 直接 fail-fast。
5. [x] 增加比较路径结果类型校验（必须兼容 `bool` 结果）。

涉及文件：

1. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
2. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
3. `src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenTest.java`

验证命令：

```bash
./gradlew test --tests COperatorInsnGenTest --no-daemon --info --console=plain
```

验收标准：

1. 第 8 节中 Nil/Object 相关用例全部通过。
2. 未命中特化前置条件时不误入特化分支。

回退策略：

1. 若 Nil 与 Object 分支冲突，优先保留 Object 规则并将 Nil 分支收窄到显式 `Nil` 类型输入。

---

### 阶段 3：metadata 解析、结果类型判定与基础 evaluator 路径

目标：

1. 建立“先判定类型再生成代码”的统一主线。
2. 打通 unary/binary metadata 查询与 evaluator 调用。

实施项：

1. [x] 实现 unary `rightType=""` 归一化匹配。
2. [x] 实现 binary `(leftType, op, rightType)` 匹配与异常 metadata 容错。
3. [x] 实现统一结果类型解析入口（`resolveOperatorReturnType(...)`）。
4. [x] 实现 `GdType -> GDExtensionVariantType` 映射与 fail-fast。
5. [x] 打通非快路径 builtin 的 evaluator 代码生成。

涉及文件：

1. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
2. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
3. `src/main/c/codegen/template_451/entry.c.ftl`
4. `src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenTest.java`

验证命令：

```bash
./gradlew test --tests COperatorInsnGenTest --no-daemon --info --console=plain
```

验收标准：

1. metadata 命中与不命中行为可预测、错误信息可定位。
2. 结果类型不兼容会在编译期 fail-fast。

回退策略：

1. 若 evaluator helper 方案阻塞，可先内联调用 evaluator，后续再抽 helper，不影响语义正确性。

---

### 阶段 4：primitive 快路径与 `POWER` 特化

目标：

1. 在语义可证前提下引入性能快路径。
2. 完成 `POWER -> pow/pow_int` 的双分流实现。

实施项：

1. [x] 落地快路径白名单矩阵 `(leftType, op, rightType)`。
2. [x] 实现基础算数/位/逻辑快路径表达式生成。
3. [x] 实现 `POWER`：
   - 含浮点 -> `pow`
   - 双整型 -> `pow_int`
4. [x] 保留 metadata 前置校验，禁止“C 可编译但 Godot 语义不合法”路径。
5. [x] 对 `XOR` 在非 `bool` 场景按逻辑 xor 语义生成表达式。

涉及文件：

1. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
2. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
3. `src/main/c/codegen/template_451/entry.c.ftl`
4. `src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenTest.java`

验证命令：

```bash
./gradlew test --tests COperatorInsnGenTest --no-daemon --info --console=plain
```

验收标准：

1. `POWER(float,int)` 与 `POWER(int,int)` 分流稳定。
2. 快路径不绕过结果类型和 metadata 校验。

回退策略：

1. 若某 operator 快路径语义有争议，先降级到 evaluator 路径并保留测试断言。

---

### 阶段 5：原顺序匹配约束收敛

目标：

1. 固化 binary metadata 的原顺序匹配语义。
2. 明确当前版本不支持 `swap/dual` 回退。

实施项：

1. [x] 删除 swap/dual 回退决策相关实现与文档入口。
2. [x] binary 仅匹配 `(leftType, op, rightType)`，原顺序不命中直接 fail-fast。
3. [x] 为 `IN` 增加硬约束：仅原顺序解析。
4. [x] 补齐错误分支：metadata 缺失时输出带签名上下文的错误信息。

涉及文件：

1. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
2. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
3. `src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenTest.java`

验证命令：

```bash
./gradlew test --tests COperatorInsnGenTest --no-daemon --info --console=plain
```

验收标准：

1. 原顺序 metadata 缺失时稳定 fail-fast。
2. `SUBTRACT`/`DIVIDE` 等运算不存在隐式交换行为。

回退策略：

1. 当前阶段不引入任何 swap/dual 补救逻辑；若需求恢复需单独立项。

---

### 阶段 6：Variant 混合路径与生命周期收口

目标：

1. 打通 `binary_op` 中 Variant 参与场景的完整策略链路。
2. 确保 `variant_evaluate` 不破坏变量生命周期。

实施项：

1. [x] 实现 Variant 混合路径策略（按当前收敛语义）：
   - `binary_op` 只要任一操作数为 `Variant`，统一走 `godot_variant_evaluate` 动态派发
   - 不再走 operator 函数指针优先路径
2. [x] 在 `CBodyBuilder` 新增未初始化变量声明方法（`declareUninitializedTempVar`）。
   - `2026-03-02`：`emitBinaryVariantEvaluate` 中的 `op_eval_result` 已改为未初始化声明，专用于 out-parameter 写入。
3. [x] 对 `variant_evaluate` 实现固定流程：
   - 写入未初始化临时槽
   - 通过 `callAssign` 回写目标
   - `Variant -> Variant` 使用 `godot_new_Variant_with_Variant` 构造拷贝写回
   - 仅输入侧执行必要 `pack`，不对结果做 `unpack`
4. [x] 补齐运行时错误风格：`valid == false` 与 `evaluator == NULL` 的统一 hard-fail。
   - `2026-03-02`：`emitBinaryVariantEvaluate` 在 `op_eval_valid == false` 时通过 `GDCC_PRINT_RUNTIME_ERROR` 输出错误，并返回当前函数返回类型的默认值。
   - `2026-03-02`：operator evaluator helper 在 `evaluator == NULL` 时通过同一宏报错并返回类型默认值。
5. [x] 补齐 `variant_evaluate` 路径的编译期结果类型校验。
   - `2026-03-02`：`emitBinary` 统一执行 `validateResultCompatibility`，`Variant` 语义结果不再允许写入非 Variant `result`。

涉及文件：

1. `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
2. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
3. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
4. `src/main/c/codegen/template_451/entry.h.ftl`
5. `src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenTest.java`

验证命令：

```bash
./gradlew test --tests COperatorInsnGenTest --tests CCodegenTest --no-daemon --info --console=plain
```

验收标准：

1. `binary_op` Variant 路径命中优先级与文档一致，`unary_op` 不误入 evaluate。
2. `variant_evaluate` 回写全程经 `callAssign`，`Variant -> Variant` 使用构造拷贝写回，无裸写槽位。
3. 生命周期相关用例无泄漏/双析构迹象。
4. `variant_evaluate` 路径对非 Variant `result` 在编译期稳定 fail-fast。

回退策略：

1. 若生命周期实现不稳定，先禁用回退 evaluate 路径，仅保留 metadata 可证路径并 fail-fast。

---

### 阶段 7：收尾回归与合并门禁

目标：

1. 在合并前完成回归验证与文档收口。
2. 输出可审阅的实现结果与风险余项。

实施项：

1. [x] 对第 8 节测试矩阵逐条对照，补缺失用例。
   - `2026-03-02`：补充 `IN(int, Array)` 原顺序 metadata 解析用例，验证不进入 primitive 快路径且走 builtin evaluator。
   - `2026-03-02`：补充“metadata 存在时，`binary_op` 的 Variant 参与仍强制走 `godot_variant_evaluate`”回归用例，防止路径回归到 ptr evaluator。
2. [x] 运行目标测试并记录命令与关键输出。
   - `2026-03-02`：执行 `./gradlew test --tests COperatorInsnGenTest --tests CCodegenTest --no-daemon --info --console=plain`。
   - 关键结果：`BUILD SUCCESSFUL`。
   - 关键计数：`COperatorInsnGenTest` 29/29 通过，`CCodegenTest` 6/6 通过，总计 35/35 通过。
3. [x] 检查异常信息可读性，统一错误前缀与上下文信息。
   - `2026-03-02`：`OperatorResolver` 的二元路径未命中原因统一附带签名上下文 `(leftType, op, rightType)`，保证 compare/object/nil 分支错误可直接定位。
   - `2026-03-02`：运行时错误继续统一走 `GDCC_PRINT_RUNTIME_ERROR`，与阶段 6 的 hard-fail 风格保持一致。
4. [x] 更新本计划文档中的“状态/风险”与 deferred 项。
   - `2026-03-02`：文档状态更新为 `Active / Stage 7 Completed`。
   - `2026-03-02`：风险与补充规则收口到当前实现语义（移除交换/对偶回退作为默认行为，`binary_op` Variant 统一 evaluate）。
   - `2026-03-02`：新增 deferred 清单，记录被显式收敛/推迟的能力项。

建议命令：

```bash
./gradlew test --tests COperatorInsnGenTest --tests CCodegenTest --no-daemon --info --console=plain
```

验收标准：

1. 核心与回归用例全部通过。
2. 无新增未解释的行为变化。
3. 文档与代码行为一致。

---

## 10. 风险与边界

1. `pow` / `pow_int` 在边界输入下的语义需持续与 Godot 对齐（尤其是负指数、极值输入）。
2. `binary_op` 的 Variant 路径当前统一使用 `godot_variant_evaluate`，且编译期强制 `result` 与 `Variant` 语义结果兼容；运行时错误分支（`valid == false`）仍依赖 hard-fail 输出与默认返回值兜底，需继续做引擎级验证。
3. metadata 仍采用原顺序匹配；当元数据缺失时直接 fail-fast，不再做交换/对偶补救。
4. `Nil` 与 `Object(null)` 的等值特化必须避免与 Object 特化冲突。
5. primitive 快路径 guard 当前聚焦 UB/除零防护，不覆盖整型算数溢出；后续若要恢复溢出防护需单独评估与 Godot 语义一致性。

---

## 11. 已确认补充规则

1. `POWER` 含浮点使用 `pow`，双整型使用 `pow_int`，两种情况都属于快路径。
2. `gdcc_helper.h` 已提供 `math.h` 与 `gdcc_operator.h`，模板侧不重复显式引入。
3. `CBodyBuilder` 已新增 `declareUninitializedTempVar`，并用于 `variant_evaluate` out-parameter 槽位。
4. `binary_op` 中只要任一操作数为 `Variant`，统一走 `godot_variant_evaluate` 动态派发。
5. runtime hard-fail 统一使用 `GDCC_PRINT_RUNTIME_ERROR`，并在失败分支返回当前函数返回类型默认值。
6. Nil 比较特化：除 `Object(null)` 例外外，`Nil` 与其他非 Nil 类型均不相等；Nil 的非比较运算走默认规则。
7. `unary_op` 不走 `godot_variant_evaluate`，保持 metadata + builtin evaluator 路径。
8. `binary_op` 的 `variant_evaluate` 语义结果固定为 `Variant`，并在编译期执行结果类型兼容性校验；非 Variant `result` 直接 fail-fast。
9. `Variant -> Variant` 回写统一经 `godot_new_Variant_with_Variant` 构造拷贝，避免浅拷贝 + 临时值销毁导致悬挂。
10. `pow_int` 对负指数输入不再迭代计算，避免死循环；`base=1/-1` 返回可精确值，其余返回 `0`。
11. 快路径 guard 当前规则为“全 primitive 快路径均发射 guard”，其中部分算子使用 NOOP guard 以保持结构一致。
12. guard 实现当前不做整型加减乘和左移结果溢出判定；`DIVIDE/MODULE` 的 guard 覆盖整型与浮点除零场景。

---

## 12. Deferred 项

1. 交换/对偶回退注册表能力（含 `A op B -> B dualOp A`）已从当前实现语义中收敛移除，后续如需恢复需单独立项并补充完整语义与回归矩阵。
2. `binary_op` Variant 混合场景的 ptr-evaluator 优先策略已收敛移除；后续如需恢复，必须与生命周期与错误处理规则一并重新验收。
