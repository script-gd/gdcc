# CBodyBuilder 实施与迁移总指南

> 本文是 `doc/module_impl` 下 CBodyBuilder 相关文档的合并基线。  
> 语义优先级：`doc/gdcc_c_backend.md` > 本文 > 历史迁移记录。

## 1. 目标与边界

### 1.1 目标

- 将 C 后端函数体生成从 FTL 分散逻辑收敛到 `CBodyBuilder`。
- 统一赋值/析构/引用计数/指针转换/错误定位语义。
- 保证 `__prepare__` / `__finally__` 控制流可验证、可回归。
- 允许新旧路径并存，但高风险路径（Variant/Object）优先迁移。

### 1.2 必须遵循

- 所有语义以 `doc/gdcc_c_backend.md` 为准。
- 每条指令生成前必须 `setCurrentPosition(...)`，异常统一通过 `invalidInsn(...)` 输出定位。
- Builder API 必须同时支持：
  - 变量输入（`valueOfVar`）。
  - 表达式输入（`valueOfExpr(code, type)`，必要时显式 `PtrKind`）。
- 赋值目标为 `ref=true` 时必须拒绝。
- 赋值语义必须隐式处理旧值清理与新值所有权。

### 1.3 非目标

- 第一版不提供字段赋值 API（字段路径由指令生成器自行负责）。
- 不一次性删除 `CGenHelper`（按迁移阶段逐步瘦身）。
- 不修改 Gradle/构建脚本。

## 2. 语义基线

### 2.1 统一赋值语义（核心）

`assignVar` / `callAssign` 必须统一执行：

1. 校验目标存在、可写、非 `ref`。
2. 计算 RHS（必要时 copy/convert）。
3. 若目标是“已初始化存储”，先销毁旧值：
   - 非对象：destroy。
   - 对象：release / try_release。
4. 写入新值。
5. 若是对象类型，对新值 own / try_own。
6. 标记目标初始化状态。

特殊规则：
- 在 `__prepare__` 中，非 ref 变量视为“首次初始化”，不得销毁旧值。
- 对“未初始化 TempVar”首写也不得走旧值销毁路径。

### 2.2 对象与所有权语义

- GDCC 对象传给 GDExtension API 时，必须用 `obj->_object`。
- `gdcc_object_from_godot_object_ptr` 仅做包装转换，不自动持有；后续存储需 own/try_own。
- `RefCountedStatus` 策略：
  - `YES`: `own_object` / `release_object`
  - `UNKNOWN`: `try_own_object` / `try_release_object`
  - `NO`: 不做 own/release

### 2.3 `__prepare__` / `__finally__` 语义

- 非 `__finally__` 块中的 `return*` 不生成真实 `return`，统一跳转 `goto __finally__`。
- 非 void 函数返回值先写入隐式槽 `_return_val`，最终在 `__finally__` 返回。
- `_return_val` 为代码生成期隐式变量，不属于 LIR 变量表，不参与自动析构。
- `__prepare__` 仅初始化非参数、非 ref 变量。

## 3. Builder 模型与 API 约束

### 3.1 值模型

- `ValueRef`：值来源抽象（变量 / 表达式）。
- `TargetRef`：赋值目标抽象（变量 / 临时 / 丢弃目标）。
- `PtrKind`：
  - `GDCC_PTR`
  - `GODOT_PTR`
  - `NON_OBJECT`

### 3.2 目标 API（指令生成器可直接使用）

- 控制流：`beginBasicBlock`、`jump`、`jumpIf`、`returnVoid`、`returnValue`
- 赋值与调用：`assignVar`、`assignExpr`、`callVoid`、`callAssign`
- 常量：`assignGlobalConst`
- 值工厂：`valueOfVar`、`valueOfExpr(...)`

### 3.3 临时变量生命周期

- 分类：
  1. 声明即初始化（表达式物化/copy staging）
  2. 仅声明后首写（out-init）
  3. 指令内闭合临时（per-insn）
- 统一规则：
  - 临时变量必须可追踪声明与销毁。
  - `destroyTempVar` 仅对已初始化临时变量生效。
  - 指令生成器优先用 `assignVar/callAssign` 完成首写，避免手拼初始化。

## 4. 指针转换与参数渲染规则

### 4.1 参数自动转换

当函数要求 Godot raw object ptr 且参数是 `GDCC_PTR` 时，参数渲染自动 `toGodotObjectPtr`。

典型命中函数：
- `godot_*`
- `*own_object` / `*release_object`
- `try_destroy_object`
- `gdcc_object_from_godot_object_ptr`

### 4.2 返回值自动转换

当返回被判定为 Godot raw ptr，且赋值目标是 GDCC 对象类型时，调用表达式自动包裹 `fromGodotObjectPtr(...)`。

### 4.3 生成器编写约束

- 禁止手写 `->_object` 与 `gdcc_object_from_godot_object_ptr(...)`。
- 统一通过 `valueOfVar` + `call*` 交给 Builder 自动处理。
- 仅在规则无法覆盖的特殊场景，允许 `valueOfExpr(..., ptrKind)` 显式指定。

## 5. 迁移实施策略

### 5.1 优先级

- P0：Variant/Object 生命周期敏感指令。
- P1：控制流与返回。
- P2：普通调用与构造。
- P3：剩余模板指令。

### 5.2 指令迁移顺序建议

1. `ControlFlowInsnGen`（先统一 `__finally__` 路径）
2. `OwnReleaseObjectInsnGen`
3. `DestructInsnGen`
4. `NewDataInsnGen`
5. 其余模板生成器退役

### 5.3 `CCodegen` 协同规则

- 自动确保存在 `__prepare__` / `__finally__` 块。
- 已有块内容不清空，按语义等价去重追加（`checkEquals`）。
- 自动补齐：
  - `__prepare__` 初始化与入口跳转
  - `__finally__` 的析构与最终返回

## 6. 审阅沉淀：高价值检查项

### 6.1 P0（必须持续守护）

- `__prepare__` 必须跳过 ref 变量初始化。
- 指令生成器不得绕过 Builder 直接 `$var = ...` 覆盖 destroyable/object 变量。

### 6.2 P1（实现一致性）

- `_return_val` 的非 finally 路径行为必须与文档一致（跳转 finally 或明确拒绝，二选一且文档化）。
- `__finally__` 自动析构要避免重复析构和手工逻辑覆盖。

### 6.3 P2（可维护性与扩展）

- 已迁移模板需及时退役，避免被误用。
- 对未来 global call 与 Object 返回值场景预留测试。

## 7. 测试与回归清单

### 7.1 必测语义

- `getCurrentInsn` opcode 校验与定位信息。
- ref 变量禁赋值。
- `jumpIf` 变量/表达式双路径。
- GDCC/Godot 指针转换。
- own/release 决策矩阵。
- 临时变量唯一性与即时销毁。
- `__prepare__` / `__finally__` 自动补齐与去重行为。

### 7.2 建议命令

```bash
./gradlew test --tests CBodyBuilderPhaseBTest --no-daemon --info --console=plain
./gradlew test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
./gradlew test --tests CPhaseAControlFlowAndFinallyTest --no-daemon --info --console=plain
./gradlew test --tests "C*InsnGenTest" --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

## 8. 常见误区速查

1. 直接把 GDCC 对象传给 `release_object`/`try_own_object`。
2. 误以为 `gdcc_object_from_godot_object_ptr` 会自动持有对象。
3. 对 `ref=true` 变量执行赋值。
4. 忽略非对象类型的 copy/destroy。
5. 对对象覆盖写入时漏掉旧值 release。
6. 条件跳转仅支持变量，不支持表达式。
7. 错误消息缺少函数/块/指令索引。

## 9. 完成标准（DoD）

- 核心指令路径已迁移到 Builder，且无关键模板依赖。
- `__prepare__` / `__finally__` 语义与文档一致并有测试守护。
- 对象生命周期与指针转换语义可回归验证。
- 关键失败路径均通过 `InvalidInsnException` 提供可定位错误信息。
