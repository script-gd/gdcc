# GDCC C Backend 生命周期与所有权语义完备性分析报告

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

### 2.1 GDCC 对象指针 NULL 解引用（`toGodotObjectPtr` 无 NULL 守卫）

**规范要求**：`doc/gdcc_c_backend.md` § Lifecycle "Call lifecycle functions on NULL is safe, they will do nothing."

**现状**：`toGodotObjectPtr` 对 GDCC 类型通过 `varCode + "->_object"` 提取 Godot 指针。当变量值为 NULL 时（如 `__prepare__` 初始化后首次赋值、`_return_val` 首次写入），此转换**在调用生命周期函数之前解引用 NULL 指针**，属于未定义行为：

```java
// CBodyBuilder.toGodotObjectPtr()
if (objType.checkGdccType(classRegistry())) {
    return varCode + "->_object";   // varCode 为 NULL 时 -> UB
}
```

**触发路径**：

1. **普通变量首次赋值**：`__prepare__` 中 `literal_null` 将 GDCC 类型变量初始化为 NULL，随后在正常基本块中通过 `assignVar` 赋值新值。此时 `emitObjectSlotWrite` 的 `releaseOldValue=true`，生成 `release_object($var->_object)` — NULL 解引用。

2. **`_return_val` 首次写入**：`_return_val` 在 `beginBasicBlock("__prepare__")` 中被声明为 `MyGdccClass* _return_val = NULL`。首次 `returnValue` 调用 `emitObjectSlotWrite(RETURN_SLOT_NAME, ..., releaseOldValue=true, ...)`，生成 `release_object(_return_val->_object)` — NULL 解引用。

3. **`__finally__` 析构**：若 GDCC 类型变量运行时仍为 NULL（如条件分支未走到赋值路径），`DestructInsnGen` 通过 `callVoid(releaseFunc, valueOfVar(variable))` 生成的参数渲染会调用 `renderArgument` → `toGodotObjectPtr`，产出 `$var->_object` — NULL 解引用。

**影响**：涉及 GDCC 类型对象的所有 release/own 路径，在目标为 NULL 时均存在段错误风险。引擎类型（`godot_Node*` 等）不受影响，因为 `toGodotObjectPtr` 对非 GDCC 类型直接返回原指针，`release_object(NULL)` 安全。

**建议**：在生成的 C 代码中为 GDCC 类型的 release/own 添加 NULL 守卫，例如：
```c
if ($var != NULL) { release_object($var->_object); }
```
或者在 `emitReleaseObject` / `emitOwnObject` 中为 GDCC 类型条件性生成守卫。

---

### 2.2 FTL 模板构造器 `try_own_object` 双重 own + 指针类型错误

**规范要求**：§3.1 "函数调用返回对象值产出 OWNED"；§3.2 "RHS 为 OWNED 时不得再 own"

**现状**：`entry.c.ftl` 中类构造器：
```c
self->property = ClassName_initFunc(self);  // initFunc 返回 OWNED（由 generateFuncBody 编译）
<#if property.type.gdExtensionType.name() == "OBJECT">
    try_own_object(self->property);          // ← 问题 A + 问题 B
</#if>
```

**问题 A：双重 own**

`initFunc` 通过 `CCodegen.generateFuncBody` 编译，其返回值按规范为 OWNED（引用计数 +1 已在被调函数的 return 路径中完成）。模板再做一次 `try_own_object` 导致引用计数 +2，最终只 release 一次，**每个有对象属性的类实例在构造时泄漏一个引用**。

> 注：当前默认 `_field_init_*` 对对象属性返回 `literal_null`（即 NULL），此场景下 `try_own_object(NULL)` 为 no-op，不会触发实际泄漏。但若用户自定义 init 函数返回真实对象实例，则会泄漏。

**问题 B：GDCC 类型指针未转换**

`try_own_object` / `try_release_object` 接受的是 `GDExtensionObjectPtr`（即 `godot_Object*`），但对于 GDCC 类型属性，`self->property` 的 C 类型是 `MyGdccClass*`（GDCC 包装指针）。直接传递给生命周期函数将以错误的指针值操作引用计数，可能导致**内存损坏或崩溃**。

正确写法应为：
```c
try_own_object(self->property->_object);
```

**问题 C：未区分 `RefCountedStatus`**

模板无条件使用 `try_*` 变体。对于明确 `YES` 的类型（如 `RefCounted` 子类），应使用更快的 `own_object`；对于 `NO` 类型应为 no-op。

---

### 2.3 FTL 模板析构器 GDCC 对象指针未转换

**现状**：`entry.c.ftl` 中类析构器：
```c
<#if property.type.gdExtensionType.name() == "OBJECT">
    try_release_object(self->${property.name});   // GDCC 类型时传递了错误的指针类型
</#if>
```

与 §2.2 问题 B 相同：对于 GDCC 类型属性，`self->property` 是 GDCC 包装指针而非 Godot 原始指针。此外，同样缺少 `RefCountedStatus` 分派。

**影响**：析构器中对 GDCC 类型对象属性的引用释放操作使用错误指针，属于未定义行为。

---

### 2.4 非对象 `_return_val` 多分支覆盖写入泄漏旧值

**规范要求**：§3.4 "Writing `_return_val` follows the same slot write rules from 3.2"

**现状**：`returnValue` 对非对象返回类型使用直接赋值，不销毁旧值：

```java
// CBodyBuilder.returnValue() 非对象分支
out.append(RETURN_SLOT_NAME).append(" = ").append(returnCode).append(";\n");
```

`_return_val` 对非对象类型（如 String）在 `beginBasicBlock("__prepare__")` 中**未初始化**（仅声明 `godot_String _return_val;`）。多分支 return 场景下：

```gdscript
func get_str() -> String:
    if condition:
        return "hello"    # _return_val = copy("hello")  — 首次写入，OK
    return "world"        # _return_val = copy("world")  — 旧值 "hello" 未 destroy，泄漏！
```

**原因**：迁移计划明确将非对象 `_return_val` 排除在 `emitNonObjectSlotWrite` 之外（"避免将 return-slot 控制流耦合到 assign/callAssign 的 target-state 钩子"），但这导致了对可析构值语义类型（String、Variant、Container 等）的旧值泄漏。

**修复困难点**：
- 若要在覆盖写入前 destroy 旧值，需要知道 `_return_val` 是否已被写入过（否则 destroy 未初始化的结构体是 UB）。
- 需要引入 `_return_val` 初始化状态追踪（类似 `TempVar` 的 `initialized` 标记）或将 `_return_val` 初始化为类型默认值。

---

## 三、🟡 重要缺陷（语义存疑 / 潜在不正确）

### 3.1 `DestructInsnGen` 对非 RefCounted 对象使用 `try_destroy_object`

**规范要求**：§3.6 "NO: object own/release is a no-op"

**现状**：`DestructInsnGen.generateObjectDestruct()` 中：
```java
case RefCountedStatus.NO -> "try_destroy_object";
```

`try_destroy_object` 对非 RefCounted 对象会**真正执行 mem-delete**（`doc/gdcc_c_backend.md` "if it is not ref-counted, it will be actually destroyed"）。在 `__finally__` 自动析构路径中，对非 RefCounted 对象变量调用 `try_destroy_object`，可能销毁一个仍被场景树或其他代码持有的对象。

例如，局部变量持有一个 `Node` 引用，函数结束时 `try_destroy_object` 会 mem-delete 该 Node，但 Node 可能仍挂在场景树中。

**建议**：`__finally__` 析构路径中，非 RefCounted 对象应为 no-op（仅放弃指针引用，不真正销毁），与规范 §3.6 一致。应改为：
```java
case RefCountedStatus.NO -> { /* no-op, just drop the reference */ }
```

---

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

### 4.3 Discard 方案应与 Ownership 模型统一

`cbodybuilder_discard_leak_fix.md` 中的方案已实施（`emitDiscardedCall`），当前对 Object discard 使用 `emitReleaseObject`（即 `release` / `try_release` 按 RefCountedStatus 分派）。

但方案文档中 §2.2.3 的讨论仍提到独立的判断逻辑。现在 OWNED/BORROWED 模型已落地，discard 路径的语义可以简化为：

- 函数返回值 = OWNED → 必须消费一次 → discard 路径通过 release 消费。

建议更新方案文档，使其明确依赖 OwnershipKind 模型，而非独立判断。

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

### 5.3 Slot Write 统一路径仍有分支遗漏

当前 `emitObjectSlotWrite` 和 `emitNonObjectSlotWrite` 已收敛了 `assignVar` 和 `callAssign` 的赋值路径。但以下路径仍独立于统一入口：

- `returnValue` 的非对象 `_return_val` 写入（直接赋值，无旧值管理）。
- `StorePropertyInsnGen` 的 setter-self 直写（通过 `appendLine`，无旧值管理）。
- `initTempVar`（无 own/release 语义，用于首写，设计如此）。

前两者应被纳入统一路径或至少接受同等的生命周期约束审计。

---

## 六、优先级总结

| 优先级 | 问题 | 影响 | 对应规范 |
|---|---|---|---|
| 🔴 P0 | GDCC 对象 NULL 解引用（`toGodotObjectPtr`） | 段错误 / UB | §3.6, §3.7 |
| 🔴 P0 | FTL 构造器双重 own + 指针类型错误 | 引用计数错误 / 内存损坏 | §3.1, §3.2 |
| 🔴 P0 | FTL 析构器 GDCC 对象指针未转换 | 内存损坏 / UB | §3.6 |
| 🔴 P0 | 非对象 `_return_val` 多分支覆盖泄漏 | 持续内存泄漏 | §3.4 |
| 🟡 P1 | `DestructInsnGen` NO 分支 `try_destroy_object` | 可能误删非 RC 对象 | §3.6 |
| 🟡 P1 | `StorePropertyInsnGen` setter-self 直写绕过生命周期 | String 等属性旧值泄漏 | §3.2 |
| 🟡 P1 | `__prepare__` 中 own NULL 无意义 / GDCC 类型 UB | 无意义调用 / 段错误 | §3.1 |
| 🟡 P1 | 迁移计划缺 FTL 模板修复方案 | 模板与 Java 层语义分叉 | §5 |
| 🟡 P1 | `_return_val` 生命周期方案细节不完整 | 实施时可能引入新 bug | §3.4 |
| 🟢 P2 | 缺少端到端 ownership 测试 | 回归风险 | §6 |
| 🟢 P2 | Discard 方案文档未与 Ownership 模型对齐 | 文档一致性 | §3.5 |
| 🟢 P2 | Slot write 路径仍有分支遗漏 | 分支漂移风险 | §3.2 |
