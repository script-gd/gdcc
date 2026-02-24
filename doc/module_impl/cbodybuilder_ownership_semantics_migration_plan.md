# CBodyBuilder 所有权语义迁移状态与后续执行清单

> 对应规范：`doc/gdcc_ownership_lifecycle_spec.md`  
> 范围：后端代码生成层（不改 LIR 结构）  
> 更新时间：基于当前 `main` 代码状态复盘（文档阶段，不执行代码修改）

## 1. 本轮结论（决策沉淀）

- 对象槽位已经有 `emitObjectSlotWrite(...)` 统一入口，方向正确。
- 当前**不建议**引入 `emitBuiltinSlotWrite`（命名会误导为“builtin 类型特化”）。
- 下一步仅做一个语义等价的收敛：引入 `emitNonObjectSlotWrite(...)`，把非对象路径重复逻辑统一到 Builder 内部。
- 本轮目标是“维护性重构”，不是“语义重写”或“copy-elision 优化”。

## 2. 当前已完成状态（从旧计划转为现状）

以下内容已在代码中落地，不再作为待办：

### 2.1 `CBodyBuilder` 所有权模型已落地

- 已有 `OwnershipKind`：`BORROWED` / `OWNED`。
- `ValueRef#ownership()` 已存在，默认 `BORROWED`。
- 已有 `valueOfOwnedExpr(String code, GdType type, PtrKind ptrKind)`。

### 2.2 对象槽位写入统一

- 已有 `emitObjectSlotWrite(...)`，并执行统一顺序：
  1. release 旧值（按是否可释放）
  2. 做指针表示转换
  3. 赋值
  4. `BORROWED` 才 own，`OWNED` 只消费不再 own

### 2.3 `callAssign` / `discard` 关键行为已收敛

- `callAssign` 要求显式非 `void` 返回类型。
- 对象 target + 非对象 return 会直接报错（前置校验）。
- 丢弃可析构返回值已做即时清理：
  - 对象：`release/try_release`
  - 非对象 destroyable：`destroy`

### 2.4 `_return_val` 协作现状

- `__prepare__` 中已声明 `_return_val`。
- 对象返回类型下 `_return_val` 以 `NULL` 初始化。
- 非 finally 的对象 `returnValue` 已复用对象槽位写入语义。
- `__finally__` 实际 `return _return_val;` 路径保持稳定。

> 注：当前实现没有单独 `returnSlotInitialized` 字段；通过“对象 `_return_val = NULL` + 生命周期函数对空安全”维持行为正确。

### 2.5 相关测试覆盖已存在

- `CBodyBuilderPhaseCTest` 已覆盖：
  - callAssign 对象返回消费 OWNED（不重复 own）
  - `_return_val` 对象路径写入/覆盖
  - discard destroy/release 行为
- `CallGlobalInsnGenTest` 已覆盖 non-void discard 清理。
- `CPhaseAControlFlowAndFinallyTest` 已覆盖 `_return_val` 控制流协作。

## 3. 未完成与风险热点（当前状态）

### 3.1 非对象槽位写入逻辑重复（P1）

重复点主要在 `CBodyBuilder`：

- `assignVar(...)` 的非对象分支
- `emitCallResultAssignment(...)` 的非对象分支

问题：

- destroy-old / assign 顺序逻辑重复，后续改动容易漂移。
- 临时变量 first-write、`__prepare__` 特判、destroyable 判断在多处维护。

### 3.2 `StorePropertyInsnGen` 直写分支绕过 Builder 生命周期路径（P1）

- 在 setter-self 直写分支中仍存在 `appendLine("$obj->field = ...")` 路径。
- 该路径未自动继承 Builder 槽位写入规则，属于人工审计热点。

### 3.3 模板与 Java 路径可能继续分叉（P2）

- `template_451/entry.c.ftl` 仍有手写生命周期代码。
- 若后续 Builder 规则变更而模板未同步，可能出现语义分叉。

## 4. 只引入 `emitNonObjectSlotWrite` 的详细执行清单

本清单限定为“收敛重复逻辑，不改变语义”。

### 4.1 设计与约束

- [ ] 新增私有方法（建议签名）：
  - `emitNonObjectSlotWrite(String targetCode, GdType targetType, boolean destroyOldValue, String rhsCode)`
- [ ] 明确该方法仅处理“非对象类型”，对象仍走 `emitObjectSlotWrite(...)`。
- [ ] 严禁在本次重构中引入 copy 省略、生命周期策略改写或 IR 行为改动。

### 4.2 方法语义（必须保持与现有一致）

- [ ] 若 `destroyOldValue && !checkInPrepareBlock() && targetType.isDestroyable()`，先 `emitDestroy(targetCode, targetType)`。
- [ ] 然后发出 `targetCode = rhsCode;`。
- [ ] 不在该方法中处理 `markTargetInitialized(...)`（保持调用方现有职责）。
- [ ] 不在该方法中处理 temp 声明/销毁（保持调用方现有职责）。

### 4.3 接入点改造

- [ ] `assignVar(...)` 非对象分支改为调用 `emitNonObjectSlotWrite(...)`。
- [ ] `emitCallResultAssignment(...)` 非对象分支改为调用 `emitNonObjectSlotWrite(...)`。
- [ ] 确认 `returnValue(...)` 的非对象 `_return_val` 路径是否保持原行为（本次默认不改语义）。

### 4.4 回归检查点

- [ ] 自赋值场景（String/Variant）保持“先复制 RHS，再 destroy old，再 assign”。
- [ ] `TempVar` 首写不 destroy old。
- [ ] `__prepare__` 中非对象赋值仍不 destroy old。
- [ ] callAssign 非对象返回赋值路径行为与改造前一致。

### 4.5 目标测试（增量回归）

- [ ] `CBodyBuilderPhaseCTest`（至少覆盖非对象 assign/callAssign 现有断言）。
- [ ] `CallGlobalInsnGenTest`（确保 discard 相关路径不受影响）。
- [ ] `./gradlew classes --no-daemon --info --console=plain` 编译校验。

建议命令：

```bash
./gradlew test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
./gradlew test --tests CallGlobalInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

## 5. 预期收益与边界

### 5.1 可获得收益

- 维护性提升：非对象写槽规则单点维护，减少分支漂移。
- 审计成本降低：assign/callAssign 的非对象生命周期逻辑路径一致。
- 后续优化入口更清晰：若要做 copy-elision，可在单点上扩展。

### 5.2 不应误判的收益

- 仅引入 `emitNonObjectSlotWrite` 本身**不会自动减少复制**。
- 若要减少复制，需要额外引入“可移动语义/唯一性证明/逃逸分析”等机制，本次不涉及。

## 6. DoD（本轮文档与后续执行基线）

- 文档已从“未来计划”转为“当前状态 + 剩余风险 + 执行清单”。
- 已完成项不再作为待办重复列出。
- 下一步编码任务边界明确：仅收敛非对象槽位写入重复逻辑。
