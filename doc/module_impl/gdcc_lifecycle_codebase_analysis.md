# GDCC C Backend 生命周期与所有权语义完备性分析报告

> 归档说明（2026-02-24）：本文主要记录 `godot_object_from_gdcc_object_ptr(...)` 重构前的风险分析结论。当前代码库中的 GDCC -> Godot 指针转换已统一为 helper 宏路径，不再采用调用侧手写 `->_object`。

> 基于代码库状态：2026-02-24
> 对应规范：`doc/gdcc_ownership_lifecycle_spec.md`

## 一、当前实现状态总结

当前后端已实现以下生命周期基础设施：

- `OwnershipKind`（`BORROWED` / `OWNED`）模型已在 `CBodyBuilder` 中落地。
  - `ValueRef#ownership()` 默认 `BORROWED`，`valueOfOwnedExpr(...)` 用于显式 OWNED 值来源。
  - `emitObjectSlotWrite` 统一了对象槽位写入：`release old → assign(convert ptr) → own only for BORROWED`。
  - `emitNonObjectSlotWrite` 统一了非对象槽位写入：`destroy old (if needed) → assign`。
- `__prepare__` / `__finally__` 控制流框架已稳定。
- `TempVar` 初始化状态追踪（first-write 不销毁旧值）。
- GDCC ↔ Godot 指针自动转换（`toGodotObjectPtr` / `convertPtrIfNeeded`）。
- `RefCountedStatus` 三分支（YES/UNKNOWN/NO）调度。
- `_return_val` 声明、`returnValue` 通过 `emitObjectSlotWrite` 管理对象返回槽生命周期。
- `emitDiscardedCall` 处理丢弃可析构返回值的清理。

---

## 二、🔴 严重缺陷（规范违反 / 运行时 Bug）

## 三、🟡 重要缺陷（语义存疑 / 潜在不正确）

### 3.2 `StorePropertyInsnGen` setter-self 直写分支绕过 Builder 生命周期

**现状**：当 `isStoringInsideSetterSelf` 为 true 时，`StorePropertyInsnGen` 直接通过 `appendLine` 写入字段，绕过了 `CBodyBuilder` 的 `assignVar` / `emitObjectSlotWrite` 路径：

```java
// StorePropertyInsnGen setter-self 分支
var rhs = renderDirectFieldAssignRhs(helper, func, valueVar.id(), valueVar.type());
bodyBuilder.appendLine("$" + objectVar.id() + "->" + insn.propertyName() + " = " + rhs + ";");
```

对于值语义类型，`renderDirectFieldAssignRhs` 会调用 `renderCopyAssignFunctionName` 处理拷贝。对于对象类型，直接赋值指针——但旧值的 release 和新值的 own 依赖后续独立的 `TryOwnObjectInsn` / `TryReleaseObjectInsn` 指令。

**风险**：
- 如果 IR 生成阶段遗漏了配套的 own/release 指令，直写路径不会有任何生命周期安全网。
- 对于可析构非对象类型（如 String 属性），直写分支不会 destroy 旧值，依赖 `renderDirectFieldAssignRhs` 的 copy 语义——但实际上新值是 copy 后的独立副本，旧值并未被销毁，**setter 写入 String 属性会泄漏旧值**。

---

### 3.3 `__prepare__` 中 GDCC 对象变量 own NULL 问题

**规范要求**：§3.1 "`literal_null` / `NULL` is treated as `BORROWED`"；§3.2 "RHS is BORROWED: must own the new value"

**现状**：`__prepare__` 中对象变量通过 `LiteralNullInsn` 初始化为 NULL，走 `assignVar` → `emitObjectSlotWrite` 路径。在 `__prepare__` 中 `releaseOldValue` 被正确跳过，但 ownership 为 `BORROWED`（NULL 是 BORROWED），所以仍会执行 `emitOwnObject(targetCode, objType)`：

- 引擎类型：生成 `own_object(NULL)` / `try_own_object(NULL)` — 安全 no-op，但产生无意义的函数调用。
- GDCC 类型：生成 `own_object(NULL->_object)` — **NULL 解引用**（与 §2.1 相同问题）。

**建议**：对 NULL 字面量赋值时，应跳过 own 操作（因为 own NULL 在语义上无意义）。可在 `emitObjectSlotWrite` 中检测 rhsCode 为 "NULL" 时跳过 own，或在规范中明确 "对 NULL 值的 own 操作应视为 no-op"。

---

## 四、迁移计划中的不足与可改进之处

### 4.1 迁移计划缺少 FTL 模板修复方案

`cbodybuilder_ownership_semantics_migration_plan.md` §6 提到模板层风险：

> 模板层（`template_451/entry.c.ftl`）仍可能与 Java 路径演进节奏不一致，需持续对齐。

但对 `entry.c.ftl` 中构造器/析构器的具体生命周期问题（§2.2、§2.3）没有给出任何修复方案。建议补充：

1. **构造器**：移除 `try_own_object`（initFunc 返回 OWNED，直接消费即可），或将属性初始化也纳入 `CBodyBuilder` 管理。
2. **析构器**：对 GDCC 类型属性添加 `->_object` 转换，并按 `RefCountedStatus` 分派。
3. **长期**：考虑将构造器/析构器也通过 `generateFuncBody` 编译，消除模板层的手写生命周期代码。

### 4.2 `_return_val` 生命周期管理方案不完整

迁移计划有意将非对象 `_return_val` 保持为直接赋值（"避免耦合到 assign/callAssign 的 target-state 钩子"），但这导致了 §2.4 中描述的多分支覆盖写入泄漏问题。

需要补充的关键细节：

1. **初始化状态追踪**：引入一个 `boolean returnSlotInitialized` 标记（类似 `TempVar`），用于区分首次写入和覆盖写入。
2. **首次写入前初始化**：对可析构非对象返回类型，应在 `__prepare__` 中将 `_return_val` 初始化为类型默认值（如 `godot_String _return_val = godot_new_String_with_utf8_chars(u8"")`），使后续覆盖写入可以安全 destroy 旧值。或者使用 boolean 标记方案，首次写入跳过 destroy。
3. **异常路径**：需明确当函数中途出错、`goto __finally__` 但 `_return_val` 持有已写入值时，谁负责在 `__finally__` 中释放。当前 `_return_val` 不在自动析构范围中——如果 `_return_val` 持有一个已写入的可析构值但函数通过非 return 路径进入 `__finally__`（如未来可能的异常处理），该值会泄漏。

### 4.4 Ownership 模型仅覆盖对象，值语义类型的 OWNED 语义隐含

规范的 `OWNED/BORROWED` 模型仅讨论对象（`GdObjectType`）。对于 String/Variant/Container 等值语义类型：

- 函数返回 String 是"值拷贝"语义，所有权隐含在栈值中（拥有者是接收变量）。
- 当前 `callAssign` 的赋值路径中，函数返回的 String 不经过 `prepareRhsValue`（因为是 call result 直接赋值），所以不会额外 copy。这是正确的。
- 但 `assignVar` 路径中，从变量读取 String 赋值到另一个变量时，`prepareRhsValue` 会触发 copy。这也是正确的。

**风险点**：如果未来将 OWNED/BORROWED 模型扩展到值语义类型，必须注意不要对 call result 的值语义类型引入不必要的 copy（call result 已经是独立副本）。当前实现是正确的，但缺少文档明确声明这一设计决策。

---

## 五、设计层面可改进之处

### 5.1 `toGodotObjectPtr` 应统一处理 NULL 安全性

当前 `toGodotObjectPtr` 纯粹做字符串拼接，不考虑运行时值是否为 NULL。所有通过此函数生成的 `$var->_object` 表达式在 `$var` 为 NULL 时均为 UB。

建议方案：

**方案 A**（生成层守卫）：在 `emitReleaseObject` / `emitOwnObject` 中，对 GDCC 类型生成 `if ($var != NULL)` 守卫：
```c
if ($var != NULL) { release_object($var->_object); }
```

**方案 B**（C 层辅助宏）：在 `gdcc_helper.h` 中定义 NULL 安全的宏：
```c
#define GDCC_OBJ_PTR(x) ((x) != NULL ? (x)->_object : NULL)
```
然后 `toGodotObjectPtr` 生成 `GDCC_OBJ_PTR($var)` 而非 `$var->_object`。

方案 B 更简洁，且不增加分支指令数量。

### 5.2 缺少端到端 ownership 验证测试

现有测试验证了 `CBodyBuilder` 生成的 C 代码片段是否包含 `own_object`/`release_object` 关键词，但没有验证：

1. 引用计数的**净变化**是否正确（+1 应对应 -1）。
2. 多条路径合并后是否有**不变式违反**（如多分支 return 的 own/release 平衡）。
3. 完整函数（经过 `__prepare__`/`__finally__`）的 C 代码是否语义正确。

**建议**：增加端到端测试，构造完整 LIR 函数 → `CCodegen.generateFuncBody` → 验证生成的 C 代码中 own/release 的匹配关系。特别应覆盖：
- GDCC 类型对象变量的完整生命周期（init NULL → assign → destruct）。
- 多分支 return 场景下 `_return_val` 的 own/release 平衡。
- Discard 路径的清理完整性。

---

## 六、优先级总结

| 优先级 | 问题 | 影响 | 对应规范 |
|---|---|---|---|
| 🟡 P1 | `StorePropertyInsnGen` setter-self 直写绕过生命周期 | String 等属性旧值泄漏 | §3.2 |
| 🟡 P1 | `__prepare__` 中 own NULL 无意义 / GDCC 类型 UB | 无意义调用 / 段错误 | §3.1 |
| 🟡 P1 | 迁移计划缺 FTL 模板修复方案 | 模板与 Java 层语义分叉 | §5 |
| 🟡 P1 | `_return_val` 生命周期方案细节不完整 | 实施时可能引入新 bug | §3.4 |
| 🟢 P2 | 缺少端到端 ownership 测试 | 回归风险 | §6 |
