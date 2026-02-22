# CBodyBuilder 非 void 返回值丢弃路径泄漏修复方案

> 对应 TODO 条目：`gdcc_backend_todo.md` → CBodyBuilder → 非 void 返回值"丢弃"路径可能泄漏可析构类型。

## 1. 问题描述

### 1.1 背景

`CBodyBuilder.callAssign()` 接受一个 `TargetRef target` 参数，当 `target` 为 `DiscardRef` 时表示调用者不需要保留函数的返回值。目前 discard 分支仅发射裸调用语句，不对返回值做任何析构/释放处理：

```java
// CBodyBuilder.java L376-377 (callAssign 中 varargs==null 分支)
if (discardResult) {
    out.append(callExpr).append(";\n");
}
```

同样的模式出现在 `varargs != null` 分支 (L389-390)。

### 1.2 泄漏场景

当被调用函数的返回类型是**可析构类型**（`isDestroyable() == true`）时，返回值在 C 层面被丢弃，导致：

| 返回类型分类 | isDestroyable | 泄漏后果 |
|---|---|---|
| `GdPrimitiveType`（int, float, bool） | `false` | ✅ 无泄漏（栈值自动回收） |
| `GdVectorType` 及其子类型（Vector2, Rect2 等） | `false` | ✅ 无泄漏（栈值自动回收） |
| `GdStringLikeType`（String, StringName, NodePath） | `true` | ❌ 内部持有 opaque 指针，不析构将泄漏引擎内部 COW 数据 |
| `GdVariantType` | `true` | ❌ 可持有任意类型数据，不析构将泄漏 |
| `GdContainerType`（Array, Dictionary, Packed*Array） | `true` | ❌ 内部持有引擎对象，不析构将泄漏 / 引用计数不减 |
| `GdMetaType`（Callable, Signal） | `true` | ❌ 内部持有 opaque 指针，不析构将泄漏 |
| `GdObjectType` | `true` | ❌ 若为 RefCounted 则引用计数不减，导致内存泄漏；若为非 RefCounted 则取决于所有权语义 |

### 1.3 触发条件

IR 层面，任何 `call_global`、`call_method`、`call_static_method` 等调用指令的 `resultId` 为 `null` 且被调用函数返回非 void 类型时，指令生成器 (如 `CallGlobalInsnGen`) 会 resolve 出 `DiscardRef`，最终走到有泄漏的分支。

当前已知入口：
- `CallGlobalInsnGen.resolveResultTarget()` — 当 `instruction.resultId() == null` 时返回 `discardRef()`

未来可能的入口：
- `call_method`、`call_static_method`、`call_intrinsic` 的指令生成器（尚未实现）都将遵循相同模式。

### 1.4 现有测试缺口

现有测试 `CBodyBuilderPhaseCTest.CallAssignOverloadTests.testCallAssignDiscardReturn()` 仅测试了 `GdIntType.INT`（非可析构基础类型）的 discard：

```java
void testCallAssignDiscardReturn() {
    builder.callAssign(builder.discardRef(), "some_func", GdIntType.INT, List.of());
    assertEquals("some_func();\n", builder.build());
}
```

无任何测试覆盖可析构类型（String, Variant, Object 等）的 discard 路径。

`CallGlobalInsnGenTest.callGlobalNonVoidUtilityMissingResultId()` 测试的是 `deg_to_rad`（返回 float），同样不涉及可析构类型。

## 2. 解决方案设计

### 2.1 核心思路

当 discard 分支遇到**可析构返回类型**时，不能直接丢弃返回值，而应：

1. 声明一个临时变量接收返回值。
2. 发射调用语句，将返回值写入该临时变量。
3. 立即对该临时变量执行析构/释放。

对于**不可析构返回类型**（primitives、vectors），保持原来的裸调用语句即可。

### 2.2 分类处理策略

根据返回类型的不同，discard 路径的析构策略如下：

#### 2.2.1 不可析构类型（`isDestroyable() == false`）

包括 `GdPrimitiveType`、`GdVectorType`、`GdVoidType`、`GdNilType`、`GdRidType` 等。

**处理方式**：保持现状，直接发射裸调用语句。

```c
some_func(args);  // 返回值自然被忽略，无需析构
```

#### 2.2.2 非对象可析构类型（`isDestroyable() == true` 且非 `GdObjectType`）

包括 `GdStringLikeType`（String, StringName, NodePath）、`GdVariantType`、`GdContainerType`（Array, Dictionary, Packed*Array）、`GdMetaType`（Callable, Signal）。

**处理方式**：创建临时变量接收返回值，然后立即 destroy。

```c
godot_String __gdcc_tmp_discard_0 = get_string(args);
godot_String_destroy(&__gdcc_tmp_discard_0);
```

#### 2.2.3 Object 类型（`GdObjectType`）

Object 的析构/释放取决于其 `RefCountedStatus`：

- **YES（确定 RefCounted）**：`release_object(tmp->_object)` 或 `release_object(tmp)`
- **UNKNOWN（未知）**：`try_release_object(tmp->_object)` 或 `try_release_object(tmp)`
- **NO（确定非 RefCounted）**：这种情况比较特殊。调用者丢弃一个非 RefCounted 对象的返回值，相当于放弃了该对象的所有权，可能导致悬空对象。但在 GDScript 语义中，非 RefCounted 对象通常不由调用者管理，所以此处应使用 `try_release_object` 作为安全的释放手段，与对象在正常赋值目标上的析构语义一致。

注意：
- 若函数名以 `godot_` 开头（GDExtension 函数），返回的是 Godot raw ptr，不需要做 `gdcc_object_from_godot_object_ptr` 转换再释放。可以直接用 `try_release_object` / `release_object` 对 raw ptr 调用。
- 若函数不以 `godot_` 开头，返回的可能是 GDCC ptr，需要先转换再释放。但实际上 discard 场景下，临时变量总是以 C 层面函数的返回类型接收，所以直接使用 `toGodotObjectPtr` 转换后释放即可。

```c
// RefCounted 示例
godot_RefCounted* __gdcc_tmp_discard_0 = create_ref_counted(args);
release_object(__gdcc_tmp_discard_0);

// GDCC 对象示例
MyGdccClass* __gdcc_tmp_discard_0 = (MyGdccClass*)gdcc_object_from_godot_object_ptr(godot_create_something(args));
release_object(__gdcc_tmp_discard_0->_object);

// 未知 RefCounted 状态
godot_Object* __gdcc_tmp_discard_0 = godot_get_something(args);
try_release_object(__gdcc_tmp_discard_0);
```

### 2.3 实现方案

#### 2.3.1 新增私有方法 `emitDiscardWithCleanup`

在 `CBodyBuilder` 中新增一个私有方法，负责 discard 分支的析构逻辑：

```java
/// Emits a call expression whose return value is intentionally discarded.
/// For destroyable return types, materializes a temp, calls, then destroys the temp.
/// For non-destroyable types, emits a bare call statement.
private void emitDiscardedCall(@NotNull String callExpr,
                               @Nullable GdType returnType) {
    // 1. 若 returnType 为 null 或不可析构，直接裸调用
    if (returnType == null || !returnType.isDestroyable()) {
        out.append(callExpr).append(";\n");
        return;
    }

    // 2. 可析构类型：创建临时变量 → 接收返回值 → 析构
    var tempName = newTempName("discard");
    var cType = helper.renderGdTypeInC(returnType);
    out.append(cType).append(" ").append(tempName).append(" = ").append(callExpr).append(";\n");

    if (returnType instanceof GdObjectType objType) {
        // Object 类型：release/try_release
        var godotPtrCode = toGodotObjectPtr(tempName, objType);
        releaseOrTryRelease(godotPtrCode, objType);
    } else {
        // 非对象可析构类型：destroy
        emitDestroy(tempName, returnType);
    }
}
```

#### 2.3.2 修改 `callAssign` 的 discard 分支

将 `callAssign` 方法中的两处 discard 分支：

```java
if (discardResult) {
    out.append(callExpr).append(";\n");
}
```

替换为：

```java
if (discardResult) {
    emitDiscardedCall(callExpr, returnType);
}
```

由于 `callAssign` 的 5 参数重载版本（含 `varargs`）中有两个 discard 分支（L376-377 和 L389-390），都需要统一修改。

#### 2.3.3 `returnType` 的传递保证

关键前置条件：**discard 路径必须有明确的 `returnType`**。

当前 `callAssign` 的 3 参数重载（无显式 returnType）委托给 5 参数重载时传入 `returnType = null`：

```java
public CBodyBuilder callAssign(TargetRef target, String funcName, List<ValueRef> args) {
    return callAssign(target, funcName, null, args, null);
}
```

这意味着通过此重载走 discard 时 `returnType == null`，`emitDiscardedCall` 会按"不可析构"处理（裸调用）。

但实际上，目前唯一通过 3 参数重载走 discard 的路径并不存在——`CallGlobalInsnGen` 总是传入明确的 `returnType`。为安全起见，建议：

- **方案 A（推荐）**：在 `validateCallAssignReturnContract` 中增加约束——当 `discardResult == true` 且 `returnType == null` 时直接抛出 `InvalidInsnException`，强制调用者提供返回类型信息。这样可以从根本上杜绝"returnType 缺失导致析构被跳过"的隐患。
- **方案 B**：保持 `returnType == null` 时裸调用的行为，依赖调用者自行保证。

推荐方案 A，因为 `callAssign` 的 discard 路径应该总是已知返回类型（IR 调用指令的函数签名总是可解析的）。

#### 2.3.4 GDExtension 返回值的 GDCC 指针转换

对于返回 GDCC 对象的 `godot_*` 函数，`emitCallResultAssignment` 中原本会做 `fromGodotObjectPtr` 转换。在 discard 路径中，由于只需要释放引用而不需要保留指针，有两种策略：

- **简单策略（推荐）**：始终按 Godot raw ptr 接收并释放。即临时变量类型用 `godot_Object*` 而非 GDCC 包装类型。这样避免了不必要的 `gdcc_object_from_godot_object_ptr` 调用，且 `try_release_object` 接受的就是 Godot raw ptr。
- **一致性策略**：完全复用 `emitCallResultAssignment` 的转换逻辑，接收为 GDCC ptr 后再 `->_object` 释放。代码复杂度较高但与赋值路径一致。

推荐简单策略，因为 discard 场景下临时变量的唯一目的就是析构/释放，无需关心 GDCC 包装。但实现时要注意：对于 Object 类型的 discard，一律先用 `checkGlobalFuncReturnGodotRawPtr` 判断是否需要做 ptr 转换，若为 godot_ 函数则临时变量直接以 engine ptr 类型接收。

考虑到实现复杂度，**初版推荐保持与 `emitCallResultAssignment` 一致的转换逻辑**，即：
1. 临时变量类型为 `returnType` 对应的 C 类型
2. 若需要 `fromGodotObjectPtr`，在赋值时加入转换
3. 释放时使用 `toGodotObjectPtr` 获取 Godot raw ptr 后释放

这样保持了代码路径的统一性，降低了出错风险。

### 2.4 Object discard 特别考量

对 Object 类型的 discard，使用 `releaseOrTryRelease` 而非 `DestructInsnGen` 中的 `try_destroy_object`。原因：

- `try_destroy_object` 对非 RefCounted 对象会**真正销毁**（mem-delete），这在 discard 场景下可能不安全——调用者可能只是不关心返回值，但对象本身可能还被其他地方引用。
- `release_object` / `try_release_object` 仅减少引用计数，对非 RefCounted 对象是 no-op，更安全。
- 这与 `assignVar` / `emitCallResultAssignment` 中覆盖写入旧值时的释放策略一致。

## 3. 影响分析

### 3.1 受影响的文件

| 文件 | 修改内容 |
|---|---|
| `CBodyBuilder.java` | 新增 `emitDiscardedCall` 方法；修改 `callAssign` 的 discard 分支；可选：增强 `validateCallAssignReturnContract` |
| `CBodyBuilderPhaseCTest.java` | 新增可析构类型 discard 测试用例 |
| `CallGlobalInsnGenTest.java` | 可选：新增返回可析构类型的 utility 的 discard 测试 |

### 3.2 不受影响的部分

- `callVoid()`：仅用于 void 函数，不涉及返回值析构。
- `emitCallResultAssignment()`：非 discard 赋值路径，逻辑不变。
- `__finally__` 自动析构：仅析构 IR 变量表中的已声明变量。discard 产生的临时变量是代码生成期局部变量，不在 IR 变量表中，不会被 `__finally__` 重复析构。
- `TemplateInsnGen` 及 FTL 模板：不使用 `callAssign`/`DiscardRef`。

### 3.3 与 `__finally__` 析构的交互

`__finally__` 块自动为所有 IR 变量表中的可析构非 ref 变量插入 `destruct` 指令。discard 路径产生的临时变量：
- **不在 IR 变量表中**——它们是 `CBodyBuilder` 在代码生成期 `newTempName("discard")` 创建的 C 层面局部变量。
- **生命周期闭合在当前调用语句内**——声明、赋值、析构都在同一段生成代码中完成。
- 因此**不存在双重析构风险**。

## 4. 测试计划

### 4.1 新增单元测试（`CBodyBuilderPhaseCTest`）

在 `CallAssignOverloadTests` 嵌套类中新增以下测试用例：

#### 4.1.1 丢弃 String 返回值

```java
@Test
@DisplayName("callAssign discard of String return should destroy temp")
void testCallAssignDiscardStringReturn() {
    builder.callAssign(builder.discardRef(), "get_string", GdStringType.STRING, List.of());
    var result = builder.build();
    assertTrue(result.contains("godot_String __gdcc_tmp_discard_0 = get_string();\n"));
    assertTrue(result.contains("godot_String_destroy(&__gdcc_tmp_discard_0);\n"));
}
```

#### 4.1.2 丢弃 Variant 返回值

```java
@Test
@DisplayName("callAssign discard of Variant return should destroy temp")
void testCallAssignDiscardVariantReturn() {
    builder.callAssign(builder.discardRef(), "get_variant", GdVariantType.VARIANT, List.of());
    var result = builder.build();
    assertTrue(result.contains("godot_Variant __gdcc_tmp_discard_0 = get_variant();\n"));
    assertTrue(result.contains("godot_Variant_destroy(&__gdcc_tmp_discard_0);\n"));
}
```

#### 4.1.3 丢弃 Array 返回值

```java
@Test
@DisplayName("callAssign discard of Array return should destroy temp")
void testCallAssignDiscardArrayReturn() {
    builder.callAssign(builder.discardRef(), "get_array", GdArrayType.UNTYPED_ARRAY, List.of());
    var result = builder.build();
    assertTrue(result.contains("godot_Array __gdcc_tmp_discard_0 = get_array();\n"));
    assertTrue(result.contains("godot_Array_destroy(&__gdcc_tmp_discard_0);\n"));
}
```

#### 4.1.4 丢弃 RefCounted 对象返回值

```java
@Test
@DisplayName("callAssign discard of RefCounted object return should release temp")
void testCallAssignDiscardRefCountedReturn() {
    builder.callAssign(builder.discardRef(), "create_obj",
            new GdObjectType("RefCounted"), List.of());
    var result = builder.build();
    assertTrue(result.contains("release_object("));
}
```

#### 4.1.5 丢弃未知 RefCounted 状态对象返回值

```java
@Test
@DisplayName("callAssign discard of unknown object return should try_release temp")
void testCallAssignDiscardUnknownObjectReturn() {
    builder.callAssign(builder.discardRef(), "get_something",
            new GdObjectType("UnknownType"), List.of());
    var result = builder.build();
    assertTrue(result.contains("try_release_object("));
}
```

#### 4.1.6 丢弃非 RefCounted 对象返回值

```java
@Test
@DisplayName("callAssign discard of non-RefCounted object should not release")
void testCallAssignDiscardNonRefCountedReturn() {
    builder.callAssign(builder.discardRef(), "get_node",
            new GdObjectType("Node"), List.of());
    var result = builder.build();
    // Node 确定非 RefCounted，releaseOrTryRelease 为 NO 分支 -> no-op
    assertFalse(result.contains("release_object"), "Non-RefCounted should not release");
    assertFalse(result.contains("try_release_object"), "Non-RefCounted should not try_release");
}
```

#### 4.1.7 丢弃 int 返回值（回归）

```java
@Test
@DisplayName("callAssign discard of int return should remain bare call")
void testCallAssignDiscardIntReturnStaysBare() {
    builder.callAssign(builder.discardRef(), "some_func", GdIntType.INT, List.of());
    assertEquals("some_func();\n", builder.build());
}
```

#### 4.1.8 丢弃 GDCC 对象且 godot_ 函数返回

```java
@Test
@DisplayName("callAssign discard of GDCC object from godot_ func should convert and release")
void testCallAssignDiscardGdccObjectFromGodotFunc() {
    builder.callAssign(builder.discardRef(), "godot_get_something",
            new GdObjectType("MyGdccClass"), List.of());
    var result = builder.build();
    assertTrue(result.contains("gdcc_object_from_godot_object_ptr"));
    assertTrue(result.contains("release_object("));
}
```

### 4.2 集成测试扩展（`CallGlobalInsnGenTest`）

可选新增：构造一个返回 `String` 的 utility 函数，验证 `resultId == null` 时生成的代码包含 destroy。

### 4.3 回归测试

运行以下命令确认无回归：

```bash
gradlew test --tests CBodyBuilderPhaseBTest --no-daemon --info --console=plain
gradlew test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
gradlew test --tests CallGlobalInsnGenTest --no-daemon --info --console=plain
gradlew test --tests CPhaseAControlFlowAndFinallyTest --no-daemon --info --console=plain
```

## 5. 实施步骤

1. **在 `CBodyBuilder` 中新增 `emitDiscardedCall` 私有方法**，按 §2.3.1 实现。
2. **修改 `callAssign` 的 discard 分支**，按 §2.3.2 替换为 `emitDiscardedCall` 调用。两处 discard 分支都要修改。
3. **（推荐）增强 `validateCallAssignReturnContract`**，按 §2.3.3 方案 A，discard 时强制要求 `returnType != null`。
4. **新增单元测试**，按 §4.1 添加测试用例。
5. **运行回归测试**，确认所有现有测试通过。
6. **更新 `gdcc_backend_todo.md`**，标记此项为已完成。

## 6. 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|---|---|---|---|
| 临时变量命名冲突 | 极低 | 低 | `newTempName` 使用递增计数器，保证唯一 |
| godot_ 函数 Object 返回转换遗漏 | 低 | 中 | `emitDiscardedCall` 复用 `checkGlobalFuncReturnGodotRawPtr` 判断 |
| 与 varargs 路径组合异常 | 低 | 中 | 两处 discard 分支统一调用同一方法 |
| 非 RefCounted 对象 discard 语义不明 | 中 | 低 | `releaseOrTryRelease` 对 NO 状态为 no-op，安全保守 |
| 现有 `returnType == null` 的 3 参数重载被 discard 调用 | 低 | 高 | 方案 A 在 validation 中拦截 |

