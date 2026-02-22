# GDCC C Backend 生命周期与所有权语义完备性分析报告

## 一、当前实现状态总结

当前后端已实现以下基础设施：
- `CBodyBuilder` 统一了赋值语义：`release old → assign → own new`
- `__prepare__` / `__finally__` 控制流框架
- `TempVar` 初始化状态追踪（first-write 不销毁旧值）
- GDCC ↔ Godot 指针自动转换
- `RefCountedStatus` 三分支（YES/UNKNOWN/NO）调度
- `_return_val` 声明与基础 return 流程

---

## 二、🔴 严重缺陷（规范违反 / 运行时 Bug）

### 2.1 缺失 OWNED/BORROWED 模型 → 函数返回值双重 own

**规范要求**：§3.1 "函数调用返回对象值产出 OWNED"；§3.2 "RHS 为 OWNED 时不得再 own"

**现状**：`ValueRef` 接口没有 ownership 维度。`assignVar` 和 `emitCallResultAssignment` 都**无条件**对新对象值执行 `own`：

```java
// emitCallResultAssignment (CBodyBuilder.java)
out.append(targetCode).append(" = ").append(finalExpr).append(";\n");
if (targetType instanceof GdObjectType objType) {
    emitOwnObject(targetCode, objType);  // ← 无条件 own
}
```

对于 `callAssign` 的赋值路径（函数返回值 → 变量），生成代码为：
```c
release_object($target->_object);                         // ✓ 释放旧值
$target = (MyClass*)gdcc_object_from_godot_object_ptr(...); // ✓ 赋值
own_object($target->_object);                              // ✗ 双重 own！
```

函数返回值已经是 OWNED（引用计数 +1 已在被调函数内完成），调用方再 own 一次导致引用计数 +2，最终只 release 一次，**每次调用泄漏一个引用**。

**影响**：所有返回 RefCounted 对象的函数调用赋值路径都会泄漏。

---

### 2.2 `returnValue` 不管理 `_return_val` 生命周期

**规范要求**：§3.4 "Writing `_return_val` follows the same slot write rules from 3.2"

**现状**：`returnValue` 方法仅做指针拷贝，完全不处理 own/release：

```java
// CBodyBuilder.returnValue()
out.append("_return_val = ").append(returnCode).append(";\n");
emitTempDestroys(returnResult.temps());
out.append("goto __finally__;\n");
```

**问题 A：读取变量返回时缺少 own**

执行流程：
1. `_return_val = $var;` （仅指针拷贝）
2. `goto __finally__;`
3. `__finally__` 中 `destruct $var` → `release_object($var->_object)` → 引用计数 -1
4. `return _return_val;` → 但此时 `_return_val` 指向的对象可能已被释放！

规范要求从变量读取产出 BORROWED，写入 `_return_val` 时必须 own，但当前**完全没有 own**。

**问题 B：多分支 return 覆盖写 `_return_val` 时不释放旧值**

```gdscript
func get_obj() -> RefCounted:
    if condition:
        return obj_a    # 写入 _return_val（第一次）
    return obj_b        # 再次写入 _return_val（第二次，旧值未 release）
```

当前没有 `_return_val` 的初始化状态追踪，第二次写入不会释放 `obj_a` 的引用。

**影响**：对象返回函数的返回值可能是悬空指针（问题 A），或在多路返回时泄漏旧值（问题 B）。

---

### 2.3 Discard 路径泄漏可析构返回值

**规范要求**：§3.5 "丢弃 OWNED 对象返回值必须立即 release"

**现状**：`callAssign` 的 discard 分支仅发射裸调用：

```java
if (discardResult) {
    out.append(callExpr).append(";\n");  // 返回值直接丢弃，无清理
}
```

对于返回 String、Variant、Array、Object 等可析构类型的函数，返回值内存永久泄漏。

**已在 TODO 中记录**，`cbodybuilder_discard_leak_fix.md` 有详细方案。

---

### 2.4 FTL 模板中构造器 `try_own_object` 双重 own

**`entry.c.ftl`** 中类构造器：
```c
self->property = ClassName_initFunc(self);  // initFunc 返回 OWNED
<#if property.type == "OBJECT">
    try_own_object(self->property);          // ← 再次 own = 双重 own！
</#if>
```

initFunc 是通过 `CCodegen.generateFuncBody` 编译的普通函数，其返回值按规范是 OWNED。模板再做一次 `try_own_object` 导致双重 own。

---

### 2.5 FTL 模板中 GDCC 对象指针未转换

**同一模板**中：
```c
try_own_object(self->property);       // property 是 GDCC ptr
try_release_object(self->property);   // 也是 GDCC ptr
```

但 `try_own_object`/`try_release_object` 接受的是 Godot raw ptr（`GDExtensionObjectPtr`），对于 GDCC 类型应该传 `self->property->_object`。

同时，模板中对所有对象属性统一用 `try_own`/`try_release`，没有区分 `RefCountedStatus`（YES → `own_object`/`release_object`，NO → no-op）。

---

## 三、🟡 重要缺失（阻塞功能 / 不完整实现）

### 3.1 `CONSTRUCT_BUILTIN`/`CONSTRUCT_ARRAY`/`CONSTRUCT_DICTIONARY` 无 InsnGen

`CCodegen.generateFunctionPrepareBlock()` 和 `generateDefaultGetterSetterInitialization()` 会生成这三种指令，但 `INSN_GENS` 中没有注册对应的生成器。任何包含 compound 类型局部变量的函数在 `generateFuncBody` 时会抛出：

```
UnsupportedOperationException: Unsupported instruction opcode: construct_builtin
```

**这阻塞了实际编译管线**。

### 3.2 大量 LIR 指令缺少 InsnGen

当前仅注册了 10 个指令生成器，而 LIR 定义了 30+ 种指令。缺失的关键指令包括：

| 指令 | 重要度 | 说明 |
|---|---|---|
| `call_method` | P0 | 方法调用是最常见的操作 |
| `call_super_method` | P0 | 虚方法覆盖 |
| `call_static_method` | P1 | 静态方法调用 |
| `call_intrinsic` | P1 | 内置操作 |
| `construct_object` | P0 | 对象创建 |
| `construct_builtin` | P0 | 值类型构造 |
| `binary_op` / `unary_op` | P0 | 运算符 |
| `object_cast` / `is_instance_of` | P1 | 类型系统操作 |
| `variant_get/set_*` | P1 | 动态属性访问 |
| `load_static` / `store_static` | P1 | 静态成员 |

### 3.3 `__prepare__` 中对象变量初始化 own(NULL) 问题

`__prepare__` 中对象变量被初始化为 `NULL`，走 `assignVar` 路径后会执行 `own_object(NULL)` / `try_own_object(NULL)`。虽然在 `__prepare__` 中跳过了 destroy old value，但 own 新值（NULL）仍然会执行，产生无意义的函数调用。

---

## 四、🟡 迁移计划中的不足

### 4.1 `_return_val` 生命周期管理方案不够完整

迁移计划（`cbodybuilder_ownership_semantics_migration_plan.md` §4.1.7）提到：

> 新增 `private boolean returnSlotInitialized`

但方案缺少以下关键细节：

1. **`_return_val` 在 `__finally__` 析构范围之外**（正确），但方案未明确当 `_return_val` 持有对象引用时，谁负责在异常路径（如函数中途出错、goto 绕过 return）释放它。
2. **多分支 return 的 `_return_val` 旧值释放**需要知道 `_return_val` 是否已被写入过。仅用 `boolean` 不够——还需要知道当前持有的是什么类型的值（对象 vs 非对象可析构类型）来决定释放方式。不过由于 `_return_val` 类型固定为函数返回类型，一个 boolean 加上返回类型即可。
3. **`_return_val` 初始化为 NULL** 后是否应标记为 initialized？如果标记了，后续第一次 return 写入时会尝试释放 NULL。需要确保 `release_object(NULL)` 安全（或添加 NULL 检查）。

### 4.2 Discard 方案忽略了 `callAssign` 后续可能的 ownership 交互

`cbodybuilder_discard_leak_fix.md` §2.2.3 对 Object discard 使用 `release_object`/`try_release_object`。但如果后续实现了 OWNED/BORROWED 模型，discard 路径应该更简单——OWNED 值直接 release 即可，不需要考虑"这是从变量读的还是函数返回的"。

方案应与 ownership 模型统一，避免在 discard 路径重复实现一套独立的判断逻辑。

### 4.3 迁移计划缺少 FTL 模板修复

迁移计划 §4.3.4 提到：
> 对 `appendLine` 直写字段分支做一次语义审计

但对 `entry.c.ftl` 中构造器/析构器的生命周期代码（§2.4, §2.5 中的问题）完全没有提及修复方案。模板层和 Java 层的所有权语义必须保持一致。

### 4.4 Ownership 模型仅覆盖对象，未考虑 String/Variant 等值语义类型

规范和迁移计划的 `OWNED/BORROWED` 模型仅讨论对象（`GdObjectType`）。但对于 String/Variant/Container 等值语义类型：
- 函数返回 String 是"值拷贝"语义，所有权隐含在栈值中
- 当前 `prepareRhsValue` 中通过 `copy` 函数处理值语义拷贝
- 但 `callAssign` 的赋值路径中，函数返回的 String 不需要额外 copy（它已经是一个新的栈值）

目前的实现对 String 等值语义类型也走 `emitCallResultAssignment`，不会做 copy（因为没有进入 `prepareRhsValue`），所以结果是正确的。但如果引入 OWNED/BORROWED 后不小心将 copy 逻辑也纳入 ownership 决策，可能导致不必要的额外拷贝。

### 4.5 `literal_null` 的 ownership 语义未明确

规范 §3.1 说：
> `literal_null` / `NULL` is treated as `BORROWED`

但当 `literal_null` 通过 `NewDataInsnGen` → `assignExpr` → `assignVar` 写入对象变量时，当前代码会执行 `own_object(NULL)` / `try_own_object(NULL)`。如果 `NULL` 是 BORROWED，按照 §3.2 规则应该 own 它，但 own NULL 在语义上是错误的。

**建议**：规范应补充 "对 NULL 值的 own 操作应视为 no-op"，或在 `emitOwnObject` 中添加 NULL 守卫。

---

## 五、🟢 设计层面可改进之处

### 5.1 Ownership 应在 ValueRef 层建模，而非在 Builder 方法中推断

迁移计划提出在 `ValueRef` 中新增 `ownership()` 方法，这是正确方向。但建议进一步：
- `ValueRef` 本身不应是 `OWNED`/`BORROWED`——它是一个**值源描述**
- Ownership 应该是一个与 `ValueRef` 绑定的**元数据标签**，在创建时确定
- 例如：`valueOfVar()` → BORROWED，`callResult(expr, type)` → OWNED

这样可以避免在 `assignVar`/`emitCallResultAssignment` 中根据调用上下文推断 ownership，减少错误。

### 5.2 缺少统一的 Slot Write 辅助方法

当前 `assignVar` 和 `emitCallResultAssignment` 是两条独立的赋值路径，虽然语义类似但实现有差异：
- `assignVar`：`prepareRhsValue` → `emitDestroy/release old` → 写入 → `emitOwnObject`
- `emitCallResultAssignment`：`emitDestroy/release old` → 可能 `fromGodotObjectPtr` → 写入 → `emitOwnObject`

迁移计划 §4.1.3 提出 `emitObjectSlotWrite` 统一入口是正确的，但应该进一步统一**所有类型**的 slot write（不仅对象），包括值语义类型的旧值销毁。

### 5.3 `DestructInsnGen` 对非 RefCounted 对象使用 `try_destroy_object` 但规范未提及

`DestructInsnGen.generateObjectDestruct()` 中：
```java
case RefCountedStatus.NO -> "try_destroy_object";
```

但 ownership 规范 §3.6 只列出了 `own_object`/`release_object`/`try_*` 变体，没有 `try_destroy_object`。这两个语义不同：
- `release_object` / `try_release_object`：减少引用计数，不直接销毁
- `try_destroy_object`：对非 RefCounted 对象会**真正 mem-delete**

`__finally__` 自动析构中使用 `try_destroy_object` 可能导致**真正销毁**一个还被其他地方引用的非 RefCounted 对象。

**建议**：规范应明确 `__finally__` 析构路径中非 RefCounted 对象的处理方式——是 release（no-op）还是 destroy（真正删除）。

### 5.4 测试覆盖集中在 Builder 层，缺少端到端 ownership 验证

现有测试验证了 `CBodyBuilder` 生成的 C 代码片段是否包含 `own_object`/`release_object` 关键词，但没有验证：
1. 引用计数的**净变化**是否正确（+1 应对应 -1）
2. 多条路径合并后是否有**不变式违反**
3. 完整函数（经过 `__prepare__`/`__finally__`）的 C 代码是否语义正确

**建议**：增加端到端测试，构造完整 LIR 函数 → `CCodegen.generateFuncBody` → 验证生成的 C 代码中 own/release 的匹配关系。

---

## 六、优先级总结

| 优先级 | 问题 | 影响 | 对应规范 |
|---|---|---|---|
| 🔴 P0 | 函数返回值双重 own（缺 OWNED/BORROWED 模型） | 每次调用泄漏一个引用 | §3.1, §3.2, §6.1 |
| 🔴 P0 | `returnValue` 不管理 `_return_val` 生命周期 | 悬空指针 / 多路 return 泄漏 | §3.4, §6.3 |
| 🔴 P0 | Discard 路径泄漏可析构返回值 | 持续内存泄漏 | §3.5, §6.2 |
| 🔴 P0 | FTL 构造器双重 own + 指针未转换 | 引用计数错误 + 未定义行为 | §3.1, §4.3 |
| 🔴 P0 | 缺 CONSTRUCT_* InsnGens | 管线崩溃 | §4.3 |
| 🟡 P1 | 迁移计划缺 FTL 模板修复 | 模板与 Java 层语义分叉 | §5 |
| 🟡 P1 | `_return_val` 方案细节不完整 | 实施时可能引入新 bug | §4.1 |
| 🟡 P1 | 规范未覆盖 NULL own 语义 | 边界情况不确定 | §3.1 |
| 🟡 P1 | `DestructInsnGen` NO 分支语义存疑 | 可能误删非 RefCounted 对象 | §3.6 |
| 🟢 P2 | Ownership 应在 ValueRef 建模 | 代码可维护性 | §4.1.1 |
| 🟢 P2 | 缺少端到端 ownership 测试 | 回归风险 | §6 |
| 🟢 P2 | Slot write 路径未完全统一 | 分支漂移风险 | §4.1.3 |
