# CALL_GLOBAL 实施状态（v2）

## 状态

- 状态：实施中（M1/M2 已完成，M3 待实现）
- 目标模块：`backend.c`（`CallGlobalInsnGen` + `CBodyBuilder` + `CGenHelper`）
- 更新时间：2026-02-21
- 本文用途：仅保留当前有效状态与剩余待办，历史分段日志已归并

## 已落地能力（代码基线）

### 1) utility 调用解析与发射

- `CALL_GLOBAL` 已接入 C 后端指令分发：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- `CallGlobalInsnGen` 已完成 utility 路径发射（含 `foo` / `godot_foo` 统一解析）：
  - `resolveUtilityCall`：`src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
  - 指令生成器：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallGlobalInsnGen.java`
- `CBodyBuilder` 已支持 vararg 调用尾参数契约：
  - `varargs == null`：不生成 vararg 尾参数
  - `varargs != null`：总是生成 vararg 尾参数（空列表发射 `NULL, (godot_int)0`）
  - 实现位置：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`

### 2) 已启用校验

- utility 存在性（含 lookup key 诊断）
- 实参必须为变量操作数且变量存在
- 参数数量校验（non-vararg / vararg）
- fixed 参数类型校验（`checkAssignable`）
- vararg extra 必须可赋值给 `Variant`
- 返回值与 `resultId` 契约校验（void/non-void、result 变量存在性、`ref` 限制、类型兼容）

### 3) 当前测试覆盖（关键）

- `CallGlobalInsnGenTest` 覆盖：
  - non-vararg/non-void 成功路径
  - vararg extra=0 / extra>0 发射形态
  - utility 不存在、参数数量错误、参数类型错误、结果变量错误等失败路径
  - 默认参数未实现时抛出 `NotImplementedException`
- `CBodyBuilderPhaseCTest$CallVoidSignatureValidationTests.testCallVoidVarargTailContract` 覆盖 `varargs` 约定

## 待办（仅保留未完成项）

### M3：默认参数补全（未实现）

- 现状：当调用缺少 fixed 参数且缺失位置存在 default 值时，`CallGlobalInsnGen` 统一抛 `NotImplementedException`
- 待完成：
  1. 生成器判定缺失参数是否可由 default 补齐
  2. Builder 统一物化 default 的 C 表达
  3. 补齐后再进入 vararg extra 处理
- 相关代码：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallGlobalInsnGen.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`

### 非目标（当前仍不做）

- 非 utility 的 global symbol 调用分流
- IR 层将 `CallGlobalInsn.args` 收窄为 `List<VariableOperand>` 的结构性改造

## 约束与同步规则

- 语义校验集中在 `CallGlobalInsnGen`；`CBodyBuilder` 负责通用发射与生命周期语义
- 变更优先复用既有 `callVoid/callAssign` 语义，不新增平行实现
- 本文与代码不一致时，以代码与测试结果为准，并在同次改动中回写本文

