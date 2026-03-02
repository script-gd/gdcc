# OperatorInsnGen 实施计划（`unary_op` / `binary_op`）

## 文档状态

- 状态：`Planned / Active`
- 更新时间：`2026-03-02`
- 适用范围：`backend.c` 中 `UNARY_OP`、`BINARY_OP` 的 C 代码生成与校验
- 本文目标：给出可直接落地的工程计划，不在本轮文档内提交实现代码

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
   - Variant 参与运算时优先尝试 metadata 支持的 operator 函数指针路径，不可用再回退 `godot_variant_evaluate`。
   - 比较运算仅对基本类型、`Object`、`Nil` 做特化。
3. 增加“交换/对偶回退注册表”：支持交换顺序时同步变换为对偶操作符。
4. 在 `CBodyBuilder` 增加“声明未初始化变量”方法，保障 `Variant evaluate` 返回槽位语义。
5. 保持全路径 fail-fast：不支持的运算/类型组合抛 `InvalidInsnException`。

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
- 快路径命中则直接生成表达式，否则走 evaluator 路径。

### 3.3 `binary_op` 语义

- 读取 `(op, left, right)`。
- 使用“交换/对偶回退注册表”进行回退：
  - 若注册表规则存在，先尝试原顺序。
  - 原顺序不可用时，可交换为 `right (dualOp) left` 再尝试。
- 若无回退规则，保持原顺序，禁止互换。

补充约束：

- 交换律回退、对偶比较回退、普通不交换是三类独立规则，禁止混用。
- 比较对偶回退（如 `>` 回退为 `<`）不等价于“交换律不变换操作符”。

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

`POWER` 快路径规则：

- 只要任一操作数为浮点类型：生成 `pow`（来自 `math.h`）。
- 两个操作数均为整型：生成 `pow_int`（来自 `gdcc_operator.h`）。
- 两种情况都仍归入快路径。

### 3.8 Variant 参与运算策略

当任一侧为 `Variant` 时，执行如下策略：

1. 优先检查 GDExtension API 是否存在“某类型与 Variant”的 operator 记录。
2. 若原顺序无记录，且交换/对偶回退后存在记录，则按回退后的顺序与操作符使用 operator 函数指针路径。
3. 若上述都不可用，回退 `godot_variant_evaluate`。
4. `IN` 不参与 primitive 快路径，且不使用交换/对偶回退，仅允许原顺序解析（`left IN right`）。

### 3.9 交换/对偶回退注册表（早期范围）

注册表项必须同时记录：

- `originalOp`
- `swappedOp`（对偶变换后操作符）
- `swappable` 标志

示例：

- 交换不变：`ADD -> ADD`、`MULTIPLY -> MULTIPLY`、`EQUAL -> EQUAL`、`NOT_EQUAL -> NOT_EQUAL`。
- 交换后变换：`GREATER -> LESS`、`LESS -> GREATER`、`GREATER_EQUAL -> LESS_EQUAL`、`LESS_EQUAL -> GREATER_EQUAL`。

早期注册范围：

- 基本类型、基本向量类型、`Variant` 之间的基本算数与基础逻辑。
- 所有内置类型与 `Variant` 之间的比较操作。

---

## 4. 运算符映射与 metadata 规则

### 4.1 `GodotOperator` -> `GDExtensionVariantOperator`

计划在 resolver 中维护确定性映射表，覆盖 `GodotOperator` 全量值。

### 4.2 metadata 匹配规则

- unary 匹配：`rightType` 采用空串语义（归一化后匹配）。
- binary 匹配：按 `(leftType, op, rightType)` 查找。
- 回退匹配：若原顺序不命中且存在回退规则，则按 `(rightType, swappedOp, leftType)` 再查一次。
- 两个方向均不命中：fail-fast。
- metadata 异常记录（如空类型名、非法操作符字符串）必须按统一容错策略处理：跳过异常条目并输出可定位告警；若最终无可用匹配则 fail-fast。

### 4.3 结果类型规则

- 快路径、evaluator 路径、Variant evaluate 路径都必须先解析“语义结果类型”。
- 若结果类型与 `result` 不兼容，直接 fail-fast。

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
3. gdextension-lite wrapper 路径（当存在可复用 wrapper 时优先）。
4. Variant 参与路径：优先 operator 函数指针，失败回退 `godot_variant_evaluate`。
5. 非 Variant builtin：operator evaluator 路径（含交换/对偶回退）。
6. 其余 -> fail-fast。

补充约束：

- 三层优先级基线：`primitive 快路径 > wrapper > ptr-evaluator fallback`。
- Variant 参与路径可视为该基线上的专门分支，且不得绕过结果类型与生命周期规则。

### 5.2 `godot_variant_evaluate` 槽位策略

- `r_return` 使用未初始化槽位语义。
- 先写入“未初始化临时变量”，再通过 `CBodyBuilder.assignVar/callAssign` 写回目标变量。
- 禁止直接把 `r_return` 指向已初始化目标槽位。

补充约束：

- 当只有一侧是 `Variant` 时，若回退到 `godot_variant_evaluate`，需按约定完成 `pack -> evaluate -> (optional unpack)` 流程。
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
- `IN` 永不进入快路径，统一走 wrapper/evaluator/variant_evaluate 默认分流。

---

## 6. 代码改动清单（计划）

### 6.1 新增生成器

- 新文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`

### 6.2 新增 resolver

- 新文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
- 负责：
  - 类型分流
  - wrapper 可用性判定与路径优先级决策
  - metadata 匹配
  - 交换/对偶回退决策
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
4. metadata 两个方向都不支持且无可用回退。
5. `Object` 使用 `==` / `!=` 以外运算。
6. `Nil` 比较特化条件不满足却误入特化分支。
7. `POWER` 快路径分支无法判定应走 `pow` 还是 `pow_int`。
8. `godot_variant_evaluate` 返回 `valid == false`。
9. evaluator 查询返回 `NULL`。
10. 生成路径绕过 `assignVar/callAssign` 生命周期管道。

---

## 8. 测试计划

建议新增：`src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenTest.java`

核心用例矩阵：

1. `POWER(float,int)` 走 `pow`。
2. `POWER(int,int)` 走 `pow_int`。
3. `MODULE(float,float)` 快路径命中。
4. 交换/对偶回退：`A > B` 原顺序不可用但 `B < A` 可用时正确回退。
5. 非回退运算（如 `SUBTRACT`）不交换。
6. Variant 混合场景：metadata 支持时走 operator 函数指针。
7. Variant 混合场景：metadata 不支持时回退 `godot_variant_evaluate`。
8. `Nil == Nil`、`Nil != Nil`。
9. `Nil == Object(null)` 与 `Nil == 非空 Object`。
10. `Nil` 与其他非 Object 类型比较（应不等）。
11. `godot_variant_evaluate` 使用未初始化临时槽并经 `assignVar` 回写。
12. result 为 `ref` 时 fail-fast。
13. `IN(int, Array)` 不走快路径且不做交换/对偶回退，按默认分流生成并通过结果类型校验。

辅助回归：

- `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`。

### 8.1 引擎集成测试目标

引擎集成测试用于验证“生成代码在真实 Godot 运行时中的语义一致性”，重点覆盖单元测试难以发现的问题：

1. `godot_variant_evaluate` / ptr-evaluator 在真实引擎中的可用性与返回语义。
2. `POWER -> pow/pow_int`、比较特化、交换/对偶回退在运行时的行为一致性。
3. `Variant` 回写与生命周期管道（`assignVar/callAssign`）是否在高频调用下稳定。
4. 运行时失败路径（`valid == false`、`evaluator == NULL`）是否按计划 hard-fail 且可观测。

### 8.2 测试分层与门禁级别

定义 3 层引擎集成测试，避免一次性把所有场景都放入阻塞门禁：

1. `L1-SMOKE`（阻塞）：
   - 覆盖最核心路径：primitive 快路径、Object/Nil 比较、Variant 混合 fallback。
   - 每次 PR 必跑。
2. `L2-SEMANTIC`（阻塞）：
   - 覆盖运算语义矩阵与回退规则（交换/对偶、`IN` 原顺序约束、结果类型一致性）。
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
3. `E3-SWAP-DUAL-FALLBACK`：
   - 构造 `A op B` 不可用且 `B dualOp A` 可用场景。
   - 断言结果正确且日志可定位到回退分支。
4. `E4-IN-NON-SWAP`：
   - 验证 `IN` 仅原顺序解析，不触发交换/对偶回退。
5. `E5-VARIANT-MIXED`：
   - metadata 命中时走 operator 函数指针。
   - metadata 不命中时回退 `godot_variant_evaluate`。
6. `E6-RUNTIME-FAIL-PATH`：
   - 人工构造 `valid == false` 或 evaluator 缺失场景。
   - 断言 hard-fail 与错误消息格式。
7. `E7-LIFECYCLE-STRESS`：
   - 高频调用 destroyable/value-semantic 结果路径。
   - 检查是否出现异常崩溃或明显资源异常增长。

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
3. 阶段 5 完成后：接入 `E3` + `E4`（回退规则与 `IN` 约束）。
4. 阶段 6 完成后：接入 `E5` + `E6` + `E7`（Variant 与生命周期）。
5. 阶段 7 收尾：全量 `E1~E7` 作为合并前门禁回归。

### 8.8 验收标准与失败归因

通过标准：

1. `L1-SMOKE`、`L2-SEMANTIC` 全部通过。
2. 阻塞矩阵（Windows Debug/Release）无失败。
3. 失败信息可直接映射到路径分类：快路径、wrapper、ptr-evaluator、variant_evaluate、比较特化、回退规则。

失败归因顺序（固定）：

1. 先判定是否环境问题（Godot/Zig/动态库加载）。
2. 再判定是否 metadata 匹配与结果类型判定问题。
3. 再判定是否路径决策错误（快路径/回退/特化分支）。
4. 最后定位生命周期与运行时 hard-fail 分支。

回归报告最小字段：

1. 用例 ID（如 `E3-SWAP-DUAL-FALLBACK`）。
2. 输入类型签名与操作符。
3. 命中路径与回退信息。
4. Godot 日志摘要与产物路径。

---

## 9. 实施里程碑

### 阶段 0：准备与基线冻结

目标：

1. 冻结本计划中的语义规则，避免实现期间反复改口径。
2. 建立“每阶段必须可回归”的最小测试基线。

实施项：

1. 在 `OperatorResolver` 设计稿中先列出：分流顺序、回退规则、结果类型判定入口。
2. 在测试文件中预留分组命名（比较、快路径、回退、Variant 生命周期）。
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

1. 新增 `OperatorInsnGen`，接入指令分派入口。
2. 新增 `OperatorResolver` 空框架，提供接口：路径决策、metadata 查询、结果类型解析。
3. 在 `CCodegen` 注册 `OperatorInsnGen`。
4. 完成基础参数校验：`resultId`、操作数存在性、`ref` result 拒绝。
5. 对未实现路径统一抛 `InvalidInsnException`（含明确信息）。

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

1. 实现 primitive 比较特化（仅 `bool/int/float` 且满足 metadata）。
2. 实现 Object `==/!=`：双空/单空/双非空 `instance_id` 比较。
3. 实现 Nil 比较特化：`Nil==Nil`、`Nil!=Nil`、`Nil` 与 `Object(null)` 等值规则。
4. 明确排斥路径：Object 非 `==/!=` 直接 fail-fast。
5. 增加比较路径结果类型校验（必须兼容 `bool` 结果）。

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

1. 实现 unary `rightType=""` 归一化匹配。
2. 实现 binary `(leftType, op, rightType)` 匹配与异常 metadata 容错。
3. 实现统一结果类型解析入口（`resolveOperatorReturnType(...)`）。
4. 实现 `GdType -> GDExtensionVariantType` 映射与 fail-fast。
5. 打通非快路径 builtin 的 evaluator 代码生成。

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

1. 落地快路径白名单矩阵 `(leftType, op, rightType)`。
2. 实现基础算数/位/逻辑快路径表达式生成。
3. 实现 `POWER`：
   - 含浮点 -> `pow`
   - 双整型 -> `pow_int`
4. 保留 metadata 前置校验，禁止“C 可编译但 Godot 语义不合法”路径。
5. 对 `XOR` 在非 `bool` 场景按逻辑 xor 语义生成表达式。

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

### 阶段 5：交换/对偶回退注册表与匹配执行

目标：

1. 实现“原顺序失败后按规则回退”的稳定机制。
2. 区分交换不变与对偶变换两类回退。

实施项：

1. 定义注册表结构：`originalOp`、`swappedOp`、`swappable`。
2. 实现回退流程：
   - 先查 `A op B`
   - 再查 `B swappedOp A`
3. 非注册运算保持原顺序，不允许隐式交换。
4. 为 `IN` 增加硬约束：仅原顺序解析，不回退。
5. 补齐错误分支：双向都不命中时 fail-fast。

涉及文件：

1. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
2. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
3. `src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenTest.java`

验证命令：

```bash
./gradlew test --tests COperatorInsnGenTest --no-daemon --info --console=plain
```

验收标准：

1. `A > B` 回退到 `B < A` 的路径正确且可观测。
2. `SUBTRACT`/`DIVIDE` 等非回退运算不会被误交换。

回退策略：

1. 若对偶映射出现语义争议，先将对应 operator 从注册表移除并保持原顺序 fail-fast。

---

### 阶段 6：Variant 混合路径与生命周期收口

目标：

1. 打通 Variant 参与场景的完整策略链路。
2. 确保 `variant_evaluate` 不破坏变量生命周期。

实施项：

1. 实现 Variant 混合路径优先级：
   - 优先 metadata 可用的 operator 函数指针路径
   - 不可用时回退 `godot_variant_evaluate`
2. 在 `CBodyBuilder` 新增未初始化变量声明方法（例如 `declareUninitializedTempVar`）。
3. 对 `variant_evaluate` 实现固定流程：
   - 写入未初始化临时槽
   - `assignVar/callAssign` 回写目标
   - 必要时执行 `pack/unpack`
4. 补齐运行时错误风格：`valid == false` 与 `evaluator == NULL` 的统一 hard-fail。

涉及文件：

1. `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
2. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
3. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorResolver.java`
4. `src/main/c/codegen/template_451/entry.c.ftl`
5. `src/test/java/dev/superice/gdcc/backend/c/gen/COperatorInsnGenTest.java`

验证命令：

```bash
./gradlew test --tests COperatorInsnGenTest --tests CCodegenTest --no-daemon --info --console=plain
```

验收标准：

1. Variant 路径命中优先级与文档一致。
2. `variant_evaluate` 回写全程经 `assignVar/callAssign`，无裸写槽位。
3. 生命周期相关用例无泄漏/双析构迹象。

回退策略：

1. 若生命周期实现不稳定，先禁用回退 evaluate 路径，仅保留 metadata 可证路径并 fail-fast。

---

### 阶段 7：收尾回归与合并门禁

目标：

1. 在合并前完成回归验证与文档收口。
2. 输出可审阅的实现结果与风险余项。

实施项：

1. 对第 8 节测试矩阵逐条对照，补缺失用例。
2. 运行目标测试并记录命令与关键输出。
3. 检查异常信息可读性，统一错误前缀与上下文信息。
4. 更新本计划文档中的“状态/风险”与 deferred 项。

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

1. 交换/对偶注册表早期范围是收敛版本，不覆盖全部运算。
2. `pow` / `pow_int` 在边界输入下的语义需与 Godot 对齐。
3. Variant 混合路径在 metadata 不完备时可能频繁回退 evaluate，需测试覆盖。
4. `Nil` 与 `Object(null)` 的等值特化必须避免与 Object 特化冲突。

---

## 11. 已确认补充规则

1. `POWER` 含浮点使用 `math.h` 的 `pow`。
2. `POWER` 双整型使用 `gdcc_operator.h` 的 `pow_int`。
3. 两种 `POWER` 情况都属于快路径。
4. 交换注册表必须记录交换后的对偶操作符（如 `>` / `<=`）。
5. `CBodyBuilder` 需要新增“声明未初始化变量”方法。
6. Variant 参与运算优先尝试 operator 函数指针（含回退后匹配），不可用再 `godot_variant_evaluate`。
7. Nil 比较特化：除 `Object(null)` 例外外，`Nil` 与其他非 Nil 类型均不相等；Nil 的非比较运算走默认规则。
