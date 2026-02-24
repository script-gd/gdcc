# CBodyBuilder 所有权语义迁移状态与后续执行清单

> 对应规范：`doc/gdcc_ownership_lifecycle_spec.md`  
> 范围：后端代码生成层（不改 LIR 结构）  
> 更新时间：2026-02-24（已完成 `emitNonObjectSlotWrite` 收敛）

## 1. 本轮结论（已实施）

- 对象槽位继续由 `emitObjectSlotWrite(...)` 统一处理。
- 非对象槽位新增统一入口 `emitNonObjectSlotWrite(...)`，并接入到：
  - `assignVar(...)` 非对象分支
  - `emitCallResultAssignment(...)` 非对象分支
- 本轮是维护性重构：仅消除重复逻辑，不改写 copy/destroy 语义，不引入 copy-elision。

## 2. 当前已落地能力（代码现状）

### 2.1 所有权模型

- `OwnershipKind`：`BORROWED` / `OWNED`。
- `ValueRef#ownership()` 默认 `BORROWED`。
- `valueOfOwnedExpr(...)` 已用于显式 OWNED 值来源。

### 2.2 对象槽位写入统一

`emitObjectSlotWrite(...)` 维持既有顺序：
1. release 旧值（按可释放策略）
2. 指针表示转换
3. 赋值
4. `BORROWED` 才 own，`OWNED` 仅消费不重复 own

### 2.3 非对象槽位写入统一（本轮新增）

`emitNonObjectSlotWrite(...)` 语义为：
1. 满足 `destroyOldValue && !checkInPrepareBlock() && targetType.isDestroyable()` 时，先 `emitDestroy(...)`
2. 发出 `target = rhs;`

约束保持：
- 不负责 `markTargetInitialized(...)`
- 不负责 temp 声明/销毁

### 2.4 `callAssign` / `discard` / `_return_val` 协作

- `callAssign` 仍要求显式非 `void` 返回。
- 对象 target + 非对象 return 仍前置报错。
- discard 路径仍即时清理可析构返回值（对象 release/try_release；非对象 destroy）。
- `returnValue(...)`：
  - 对象 `_return_val` 继续复用对象槽位语义。
  - 非对象 `_return_val` 继续保持 direct assignment（本轮明确不接入 `emitNonObjectSlotWrite`）。

## 3. 本轮风险应对约定（新增文档化）

以下约定已同步到 `doc/gdcc_c_backend.md` 的 Slot Write Consolidation 小节：

- `emitNonObjectSlotWrite` 不触发目标初始化标记，避免 TempVar 状态语义漂移。
- `emitNonObjectSlotWrite` 不管理 temp 生命周期，避免调用方阶段顺序被隐藏。
- 非对象 `_return_val` 写入继续保持独立路径，避免把 return-slot 控制流耦合到 assign/callAssign 的 target-state 钩子。

## 4. 回归检查点（已完成）

- 自赋值（String/Variant）仍保持“先 copy RHS，再 destroy old，再 assign”。
- `TempVar` 首写仍不 destroy old。
- `__prepare__` 中非对象赋值仍不 destroy old。
- callAssign 非对象返回赋值行为与改造前一致。

## 5. 测试执行基线

建议持续回归命令（按类执行）：

```bash
./gradlew test --tests CBodyBuilderLiteralValueTest --no-daemon --info --console=plain
./gradlew test --tests CBodyBuilderPhaseBTest --no-daemon --info --console=plain
./gradlew test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
./gradlew test --tests CPhaseAControlFlowAndFinallyTest --no-daemon --info --console=plain
./gradlew test --tests CallGlobalInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CCodegenTest --no-daemon --info --console=plain
./gradlew test --tests CNewDataInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CStorePropertyInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

## 6. 剩余风险热点（未在本轮处理）

- `StorePropertyInsnGen` setter-self 直写分支仍绕过 Builder 生命周期入口，仍需人工审计。
- 模板层（`template_451/entry.c.ftl`）仍可能与 Java 路径演进节奏不一致，需持续对齐。

## 7. DoD（当前状态）

- 目标范围内重构已完成：非对象槽位写入逻辑已单点收敛。
- 关键行为保持语义等价并通过增量测试回归。
- 文档已从“待实施清单”切换为“已实施现状 + 风险与后续清单”，去除了已实现项的冗余待办。
