# 内置类型构造函数命名审计

## 问题概述

gdextension-lite 库的构造函数命名规范为：

- 无参构造: `godot_new_<TypeName>()`
- 有参构造: `godot_new_<TypeName>_with_<arg1_type>_<arg2_type>...(<args>)`

例如:
- `godot_new_Vector3()` — 无参
- `godot_new_Vector3_with_float_float_float(x, y, z)` — 3个float参数
- `godot_new_Color_with_float_float_float_float(r, g, b, a)` — 4个float参数
- `godot_new_Rect2i_with_int_int_int_int(x, y, w, h)` — 4个int参数

关键点：**当有参数时，`_with_` 后缀必须包含所有参数的类型名**。

---

## 状态同步（2026-02-20）

**全部问题已修复并通过代码审阅和单元测试验证。**

> 补充（2026-02-20 晚些时候）：
> 默认值内置构造表达式渲染相关方法已从 `CBodyBuilder` 迁移到
> `CBuiltinBuilder`（例如参数切分、numeric/transform 参数推断、构造校验）。
> `renderTypedArraySetTypedLine` 也已迁移到 `CBuiltinBuilder`，由 builder 统一渲染
> typed-array 重标记调用：`godot_new_Array_with_Array_int_StringName_Variant(...)`
> （含 object/non-object 元素类型分支）。
> 本文中部分 `CBodyBuilder` 行号引用属于历史记录，请以当前源码为准。

- ✅ 已修复 `renderUtilityDefaultValueExpr` 有参构造缺失 `_with_<types>` 后缀问题。
  - 旧的内联逻辑已完全替换为 `renderBuiltinDefaultConstructorExpr`，
    通过 `CGenHelper.renderBuiltinConstructorFunctionNameByTypes` 正确生成带类型后缀的函数名。
- ✅ 已修复 `NodePath("...")` 默认值使用裸字符串参数的问题，改为 `godot_new_NodePath_with_utf8_chars(u8"...")`。
  - 见 `CBodyBuilder.java` 第 861–868 行。
- ✅ 已将内置类型构造函数命名逻辑抽取到 `CGenHelper`：
  - `renderBuiltinConstructorBaseName`（第 297 行）
  - `renderBuiltinConstructorFunctionName`（第 305 行）
  - `renderBuiltinConstructorFunctionNameByTypes`（第 325 行）
- ✅ 已新增基于 `ExtensionBuiltinClass` 的构造签名校验（通过 `ClassRegistry` 查询），用于约束默认值构造与 API 元数据一致。
  - `CGenHelper.hasBuiltinConstructor`（第 337 行）查询 API 元数据。
  - `CBodyBuilder.validateBuiltinConstructor`（第 929 行）在代码生成阶段校验。
- ✅ 已实现 `Transform2D` / `Transform3D` 的默认值构造：
  - `Transform2D(6 float)` → `godot_new_Transform2D_with_float_float_float_float_float_float(...)`
  - `Transform3D(12 float)` → `godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float(...)`
  - 以上 2 个构造函数来自 `gdcc_helper.h`，校验时已通过 `skipApiValidation=true` 排除。
  - 见 `CBodyBuilder.resolveHelperTransformCtorArgTypes`（第 897 行）。
- ✅ 已实现类型化数组 `Array[T]([])` 默认值初始化：
  - 先构造 `godot_new_Array()`
  - 再调用 `godot_new_Array_with_Array_int_StringName_Variant(...)` 完成 typed-array 重标记
    （含 object 类型 class_name）。
  - 见 `renderTypedArraySetTypedLine` 的实现与 `CBodyBuilder` 默认参数补全路径。
- ✅ 已补充并通过单元测试（`CBodyBuilderCallUtilityTest`、`CGenHelperUtilityResolutionTest`）。
  - 测试覆盖了：Vector3 有参构造、NodePath 字符串默认值、类型化数组、Transform2D/3D helper 构造、
    `renderBuiltinConstructorFunctionNameByTypes` 后缀组合、`hasBuiltinConstructor` 元数据校验。

---

## ✅ 已修复: `renderUtilityDefaultValueExpr` — 有参构造函数名缺少 `_with_<types>` 后缀

### 修复前的代码（已不存在）
```java
var leftParen = literal.indexOf('(');
if (leftParen > 0 && literal.endsWith(")")) {
    var ctorArgs = literal.substring(leftParen + 1, literal.length() - 1).trim();
    var ctorName = "godot_new_" + helper.renderGdTypeName(type);
    if (ctorArgs.isEmpty() || ctorArgs.equals("[]") || ctorArgs.equals("{}")) {
        return ctorName + "()";
    }
    return ctorName + "(" + ctorArgs + ")";
}
```

### 修复后的实现

`renderUtilityDefaultValueExpr`（第 767 行）现在将构造逻辑委托给 `renderBuiltinDefaultConstructorExpr`（第 835 行），
后者按以下优先级处理：

1. **空参数** → 直接调用 `renderBuiltinConstructorBaseName` + `()`
2. **类型化数组 `[]`** → `godot_new_Array()` + `godot_array_set_typed(...)`
3. **空字典 `{}`** → 无参构造
4. **Transform2D/3D helper** → `resolveHelperTransformCtorArgTypes` + `skipApiValidation`
5. **NodePath 字符串参数** → `godot_new_NodePath_with_utf8_chars(u8"...")`
6. **数值参数** → `resolveBuiltinNumericCtorArgTypes` + `renderBuiltinConstructorFunctionNameByTypes`

所有路径均经过 `validateBuiltinConstructor` 校验（helper 路径除外）。

### 问题说明（历史记录）
当默认值字面量含有参数（如 `Vector3(0, 0, 0)`）时，旧代码仅生成 `godot_new_Vector3(0, 0, 0)`，但正确的 C 函数名应为 `godot_new_Vector3_with_float_float_float(0, 0, 0)`。

无参构造的情况（如 `RID()`、`Callable()`、`PackedByteArray()` 等）生成 `godot_new_RID()` 是正确的。

### 受影响的默认值（已全部修复）

| 默认值字面量 | 修复前错误生成 | 修复后正确生成 |
|---|---|---|
| `Color(0, 0, 0, 0)` | `godot_new_Color(0, 0, 0, 0)` | `godot_new_Color_with_float_float_float_float(0, 0, 0, 0)` |
| `Color(0, 0, 0, 1)` | `godot_new_Color(0, 0, 0, 1)` | `godot_new_Color_with_float_float_float_float(0, 0, 0, 1)` |
| `Color(1, 1, 1, 1)` | `godot_new_Color(1, 1, 1, 1)` | `godot_new_Color_with_float_float_float_float(1, 1, 1, 1)` |
| `Vector2(1, 1)` | `godot_new_Vector2(1, 1)` | `godot_new_Vector2_with_float_float(1, 1)` |
| `Vector2(0, -1)` | `godot_new_Vector2(0, -1)` | `godot_new_Vector2_with_float_float(0, -1)` |
| `Vector2(0, 0)` | `godot_new_Vector2(0, 0)` | `godot_new_Vector2_with_float_float(0, 0)` |
| `Vector2i(0, 0)` | `godot_new_Vector2i(0, 0)` | `godot_new_Vector2i_with_int_int(0, 0)` |
| `Vector2i(-1, -1)` | `godot_new_Vector2i(-1, -1)` | `godot_new_Vector2i_with_int_int(-1, -1)` |
| `Vector2i(1, 1)` | `godot_new_Vector2i(1, 1)` | `godot_new_Vector2i_with_int_int(1, 1)` |
| `Vector3(0, 1, 0)` | `godot_new_Vector3(0, 1, 0)` | `godot_new_Vector3_with_float_float_float(0, 1, 0)` |
| `Vector3(0, 0, 0)` | `godot_new_Vector3(0, 0, 0)` | `godot_new_Vector3_with_float_float_float(0, 0, 0)` |
| `Rect2(0, 0, 0, 0)` | `godot_new_Rect2(0, 0, 0, 0)` | `godot_new_Rect2_with_float_float_float_float(0, 0, 0, 0)` |
| `Rect2i(0, 0, 0, 0)` | `godot_new_Rect2i(0, 0, 0, 0)` | `godot_new_Rect2i_with_int_int_int_int(0, 0, 0, 0)` |
| `NodePath("")` | `godot_new_NodePath("")` | `godot_new_NodePath_with_utf8_chars(u8"")` |
| `Transform2D(1, 0, 0, 1, 0, 0)` | `godot_new_Transform2D(1, 0, ...)` | `godot_new_Transform2D_with_float_float_float_float_float_float(...)` |
| `Transform3D(1, 0, ..., 0)` | `godot_new_Transform3D(1, 0, ...)` | `godot_new_Transform3D_with_float_..._float(...)` |
| `Array[StringName]([])` | `godot_new_Array()` (无类型) | `godot_new_Array()` + `godot_array_set_typed(...)` |

### 无参构造（不受影响，当前正确）

以下在默认值列表中出现的无参构造已正确处理：
- `RID()`, `Callable()`, `PackedVector2Array()`, `PackedVector3Array()`, `PackedColorArray()`,
  `PackedFloat32Array()`, `PackedInt64Array()`, `PackedByteArray()`, `PackedStringArray()`,
  `PackedInt32Array()`

---

## ✅ 已修复: `NodePath("")` 的处理

`NodePath("")` 的默认值现在由 `renderBuiltinDefaultConstructorExpr` 中的 NodePath 特殊分支处理（第 861–868 行）：

```java
if (type instanceof GdNodePathType
        && ctorArgs.size() == 1
        && isQuotedStringLiteral(ctorArgs.getFirst())) {
    var content = ctorArgs.getFirst().substring(1, ctorArgs.getFirst().length() - 1);
    var ctorArgTypes = List.<GdType>of(GdStringType.STRING);
    validateBuiltinConstructor(type, ctorArgTypes, false);
    var ctorFunc = helper.renderBuiltinConstructorFunctionName(type, List.of("utf8_chars"));
    return new DefaultValueExprResult(ctorFunc + "(u8\"" + escapeStringLiteral(content) + "\")", null);
}
```

生成结果：`godot_new_NodePath_with_utf8_chars(u8"")`。
已通过 `CBodyBuilderCallUtilityTest.testCallUtilityVoidWithNodePathDefaultArgument` 测试验证。

---

## ✅ 已修复: 类型化数组 `Array[T]([])` 的处理

类型化空数组默认值现在由 `resolveTypedArrayElementTypeForSetTyped`（第 913 行）解析元素类型，
`renderTypedArraySetTypedLine`（第 945 行）生成 `godot_array_set_typed(...)` 调用：

```c
godot_Array __gdcc_tmp_default_array_0 = godot_new_Array();
godot_array_set_typed(&__gdcc_tmp_default_array_0, GDEXTENSION_VARIANT_TYPE_STRING_NAME, GD_STATIC_SN(u8""), NULL);
```

对于 Object 类型元素，`classNamePtr` 会包含正确的类名而非空字符串。
已通过 `CBodyBuilderCallUtilityTest.testCallUtilityVoidWithTypedArrayEmptyDefaultArgument` 测试验证。

---

## ✅ 已确认正确的部分

以下生成器和函数已经正确使用了 `_with_<types>` 后缀，无需修改：

### 1. `CGenHelper.renderCopyAssignFunctionName` (第 287 行)
生成 `godot_new_<Type>_with_<Type>` 用于值语义类型的拷贝赋值。正确。

### 2. `CGenHelper.renderUnpackFunctionName` (第 263 行)
生成 `godot_new_<Type>_with_Variant` 用于从 Variant 解包。正确。
对 GDCC Object 类型特殊处理，生成 `(TypeName*)godot_new_gdcc_Object_with_Variant`。

### 3. `CGenHelper.renderPackFunctionName` (第 275 行)
生成 `godot_new_Variant_with_<Type>` 用于打包到 Variant。正确。
对 GDCC Object 类型使用 `godot_new_Variant_with_gdcc_Object`。

### 4. `NewDataInsnGen` (Java 实现)
直接在代码中使用正确的函数名，如 `godot_new_String_with_utf8_chars`、`godot_new_StringName_with_utf8_chars`、`godot_new_Variant_nil`。正确。
对 ref 变量使用 `godot_string_new_with_utf8_chars` / `godot_string_name_new_with_utf8_chars` 就地初始化。正确。

### 5. `LoadPropertyInsnGen` (Java 实现, 第 96–101 行)
`builtin` 分支正确使用 `objectType.getTypeName()` 生成 `godot_<ObjectType>_get_<property>`。正确。
`engine` 分支同样正确使用 `objectType.getTypeName()`（第 79–81 行）。正确。

### 6. `StorePropertyInsnGen` (Java 实现, 第 72–76 行)
`engine`/`builtin` 合并分支正确使用 `objectVar.type().getTypeName()` 生成 `godot_<ObjectType>_set_<property>`。正确。

### 7. `renderUtilityDefaultValueExpr` 中的 String/StringName 处理 (第 776–789 行)
使用 `GD_STATIC_S`/`GD_STATIC_SN` 宏正确生成 `godot_new_String_with_String(GD_STATIC_S(...))`。正确。
StringName 同时接受 `&"..."` 和 `"..."` 两种字面量形式。正确。

### 8. `renderNullDefaultValueExpr` (第 814–825 行)
对 Object 类型返回 `NULL`，对 Variant 类型返回 `godot_new_Variant_nil()`。正确。

### 9. 原始类型（int/float/bool）默认值 (第 791–793 行)
直接返回字面量。正确。

---

## 增量同步（2026-02-20，typed array 默认值路径）

`Array[T]([])` 的默认值补全链路已从 `godot_array_set_typed(...)` 迁移为
`godot_new_Array_with_Array_int_StringName_Variant(...)`：

```c
godot_Array __gdcc_tmp_default_array_0 = godot_new_Array();
__gdcc_tmp_default_array_0 = godot_new_Array_with_Array_int_StringName_Variant(
    &__gdcc_tmp_default_array_0,
    (godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME,
    GD_STATIC_SN(u8""),
    NULL
);
```

该迁移发生在 `CBodyBuilder` 的 utility 默认参数补全流程，目标是与后续
`construct_array` typed 构造路线保持一致。

> 补充（2026-02-20）：
> `renderTypedArraySetTypedLine` 已删除，typed array 默认值改为由
> `renderUtilityDefaultValueExpr` 直接返回构造表达式，不再注入额外 pre-call 行。
