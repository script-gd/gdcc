# C 后端内置构造器重构（CBuiltinBuilder）

## 背景

`CGenHelper` 之前同时承担了两类职责：

1. 通用 C 代码生成辅助（类型渲染、utility 名称解析、对象指针转换等）
2. 内置类型构造器相关逻辑（构造函数名拼接、构造签名元数据校验）

这会导致 `CGenHelper` 职责过重，且与 `construct_builtin` 指令生成相关的 API 不够聚焦。

## 本次重构目标

- 将“内置类型构造器生成/校验”从 `CGenHelper` 抽离到 `CBuiltinBuilder`
- 保持 `CGenHelper` 对外可访问性：通过 `helper.builtinBuilder()` 获取新能力
- 先聚焦命名与元数据校验，`ConstructBuiltinInsn` 的专用解析职责延后实现

## 变更摘要

### 1) 新增 `CBuiltinBuilder`

路径：`src/main/java/dev/superice/gdcc/backend/c/gen/CBuiltinBuilder.java`

核心 API：

- `renderConstructorBaseName(GdType)`
  - 生成 `godot_new_<Type>`
- `renderConstructorFunctionName(GdType, List<String>)`
  - 支持 `utf8_chars` 这类非类型 token 后缀
- `renderConstructorFunctionNameByTypes(GdType, List<GdType>)`
  - 按类型列表生成 `_with_<t1>_<t2>...`
- `hasConstructor(GdType, List<GdType>)`
  - 用 `ExtensionBuiltinClass` 元数据校验构造签名是否存在
- `renderDefaultConstructorExpr(GdType, String, String)`
  - 渲染默认值里的内置构造表达式（含 NodePath、数值向量、Transform helper、TypedArray 空字面量）
- `validateConstructor(GdType, List<GdType>, boolean)`
  - 统一构造签名校验入口，支持 helper shim 构造跳过 API 校验
- `renderTypedArraySetTypedLine(String, GdType)`
  - 渲染 `godot_array_set_typed(...)` 行，统一 typed array 元数据写入代码

> 说明：`resolveConstructBuiltinCall(...)` 职责已从 builder 中移出，不再放在 `CBuiltinBuilder`。
> 后续若实现 `ConstructBuiltinInsnGen`，应在专用指令生成器或独立 resolver 中承接该职责。

### 2) `CGenHelper` 职责收敛

- 移除内置构造器相关方法：
  - `renderBuiltinConstructorBaseName`
  - `renderBuiltinConstructorFunctionName`
  - `renderBuiltinConstructorFunctionNameByTypes`
  - `hasBuiltinConstructor`
- 新增字段与访问器：
  - 字段：`private final CBuiltinBuilder builtinBuilder;`
  - 访问器：`builtinBuilder()`

### 3) 调用方迁移

`CBodyBuilder` 现在将“默认内置构造表达式渲染及参数推断/校验”以及
typed array 的 `set_typed` 语句渲染，整体委托给 `helper.builtinBuilder()`：

- `renderUtilityDefaultValueExpr(...)` 在 builtin 分支调用 `renderDefaultConstructorExpr(...)`
- 原先在 `CBodyBuilder` 内部的相关辅助方法（参数切分、numeric/transform 参数类型推断、typed array 元素类型解析、构造校验、`renderTypedArraySetTypedLine`）已迁移到 `CBuiltinBuilder`

这样 `CBodyBuilder` 仅保留与函数体输出直接相关的职责，依赖边界更清晰。

### 4) 测试更新

`src/test/java/dev/superice/gdcc/backend/c/gen/CGenHelperUtilityResolutionTest.java`

- 构造器命名与元数据校验测试统一使用 `helper.builtinBuilder()` 入口
- 移除 `resolveConstructBuiltinCall(...)` 相关测试（职责已移出 `CBuiltinBuilder`）
- 新增 `renderDefaultConstructorExpr(...)` 的单元测试，覆盖默认值内置构造表达式渲染路径
- 新增 `renderTypedArraySetTypedLine(...)` 单元测试，覆盖 typed array 的 object / non-object 元素渲染
- 回归执行 `CBodyBuilderCallUtilityTest`，确认 NodePath / Vector3 / Transform / typed array 默认值路径均继续正确

## 设计收益

- **单一职责更明确**：`CGenHelper` 保留通用渲染/查表职责，内置构造器策略集中在 `CBuiltinBuilder`
- **builder 边界更清晰**：负责内置构造命名、默认构造表达式渲染、typed array 元数据行渲染与 API 元数据校验，不混入指令解析逻辑
- **后续扩展更安全**：若未来补齐 `ConstructBuiltinInsnGen`，可引入独立 resolver 复用同一命名/校验 API
- **降低重复实现风险**：默认值构造与指令构造走同一命名/校验能力源

## 建议的后续工作

1. 新增 `ConstructBuiltinInsnGen`，在 `CCodegen.INSN_GENS` 注册 `CONSTRUCT_BUILTIN`
2. 在 `ConstructBuiltinInsnGen`（或独立 `ConstructBuiltinCallResolver`）中实现 `resolveConstructBuiltinCall` 逻辑
3. 统一 `construct_array` / `construct_dictionary` 的专属生成器，补齐构造指令覆盖
4. 逐步移除 `CGenHelper` 中其他非通用逻辑（如绑定数据装配可考虑独立策略类）

---

## 代码审阅报告（2026-02-20）

> 本审阅基于对 `CBuiltinBuilder`、`CGenHelper`、`CBodyBuilder`、`CCodegen`、
> `CGenHelperUtilityResolutionTest`、`CBodyBuilderCallUtilityTest` 及相关类型系统的
> 全面代码审查。所有相关测试在审阅时均通过。

### 整体评价

重构目标已基本达成：`CBuiltinBuilder` 成功从 `CGenHelper` 中分离出来，
职责边界清晰（命名、校验、默认构造表达式渲染、typed array 元数据行渲染），
`CBodyBuilder` 正确委托 `helper.builtinBuilder()` 完成构造器相关逻辑。
以下列出发现的潜在问题与不足。

---

### 🔴 P0 — 关键遗漏

#### 1. `CONSTRUCT_BUILTIN` / `CONSTRUCT_ARRAY` / `CONSTRUCT_DICTIONARY` 无已注册的 CInsnGen

`CCodegen.INSN_GENS` 静态初始化块中 **没有** 注册任何处理 `CONSTRUCT_BUILTIN`、
`CONSTRUCT_ARRAY`、`CONSTRUCT_DICTIONARY` 操作码的生成器。
然而 `CCodegen.generateFunctionPrepareBlock()` 和 `generateDefaultGetterSetterInitialization()` 
在 `__prepare__` / init 函数基本块中 **正在生成** 这三种指令：

```java
// generateFunctionPrepareBlock(), line ~195
default -> new ConstructBuiltinInsn(variable.id(), List.of());   // Vector3, Color, Rect2, Transform2D...
case GdArrayType _ -> new ConstructArrayInsn(...);                // Array
case GdDictionaryType _ -> new ConstructDictionaryInsn(...);      // Dictionary
```

`generateFuncBody()` 遍历所有基本块（含 `__prepare__`），遇到未注册的操作码时直接抛出：
```java
throw new UnsupportedOperationException("Unsupported instruction opcode: " + insn.opcode().opcode());
```

这意味着 **任何包含 compound vector 类型 / Array / Dictionary 局部变量的函数在完整代码生成管线中都会崩溃**。
虽然文档已将 `ConstructBuiltinInsnGen` 列为后续工作，但该问题的严重性应被提升，因为它阻塞了实际编译流程。

**建议**：优先实现至少一个最小可用的 `ConstructBuiltinInsnGen`（处理零参构造），以解除管线阻塞。
`CBuiltinBuilder.renderConstructorBaseName()` 已经可以直接服务于零参场景。

---

### 🟡 P1 — 代码重复

#### 2. `isQuotedStringLiteral` 重复定义

`CBuiltinBuilder`（第 313 行）和 `CBodyBuilder`（第 818 行）各有一份完全相同的
`isQuotedStringLiteral(String)` 私有方法。

**建议**：抽取到 `StringUtil` 或在 `CBuiltinBuilder` 中暴露为 package-private，
让 `CBodyBuilder` 直接复用。

#### 3. `renderStaticStringNameLiteral` 重复定义

- `CBuiltinBuilder`：私有实例方法（第 317 行）
- `CBodyBuilder`：public static 方法（第 843 行）

两者逻辑完全一致（`"GD_STATIC_SN(u8\"" + escapeStringLiteral(value) + "\")"`）。

**建议**：`CBuiltinBuilder` 应直接调用 `CBodyBuilder.renderStaticStringNameLiteral()`
而非自行持有副本。或将此类 C 宏渲染 helper 抽到共享 utility。

#### 4. `DefaultValueExprResult` 与 `DefaultConstructorExprResult` 近似平行 record

- `CBodyBuilder.DefaultValueExprResult`（private，第 1353 行）
- `CBuiltinBuilder.DefaultConstructorExprResult`（public，第 321 行）

两者结构完全一致：`(String expression, @Nullable GdType arrayElementTypeForSetTyped)`。
`CBodyBuilder.renderUtilityDefaultValueExpr()` 在调用 `builtinBuilder().renderDefaultConstructorExpr()` 
后需要手动将 `DefaultConstructorExprResult` 转换为 `DefaultValueExprResult`（第 809 行）。

**建议**：让 `CBodyBuilder` 直接复用 `CBuiltinBuilder.DefaultConstructorExprResult`，
或将两者统一为共享 record，消除多余的映射转换。

---

### 🟡 P1 — 类型覆盖不完整

#### 5. `resolveBuiltinNumericCtorArgTypes` 缺少多种 compound 类型

当前覆盖范围：

| 类型 | 参数数量 | 元素类型 |
|---|---|---|
| `GdFloatVectorType` (Vector2/3/4) | dimension | float |
| `GdIntVectorType` (Vector2i/3i/4i) | dimension | int |
| `GdColorType` | 3 或 4 | float |
| `GdRect2Type` | 4 | float |
| `GdRect2iType` | 4 | int |

**缺失**（这些类型在 Godot API 默认值中可能出现）：

| 类型 | 典型参数数量 | 元素类型 | 说明 |
|---|---|---|---|
| `GdQuaternionType` | 4 | float | Quaternion(x, y, z, w) |
| `GdPlaneType` | 4 | float | Plane(a, b, c, d) |
| `GdAABBType` | 6 | float | AABB(x, y, z, sx, sy, sz)；或与 Transform helper 类似处理 |

`GdBasisType` (9 float) 和 `GdProjectionType` (16 float) 是否需要类似 Transform 的 helper
shim 构造函数也应被纳入考虑。

**建议**：扫描 `extension_api_451.json` 中所有 utility function 的默认值参数，
确认哪些 compound 类型实际出现，补齐对应的参数推断分支。

#### 6. `renderDefaultConstructorExpr` 不支持嵌套构造函数调用

当前实现将构造参数作为原始字符串切分后直接拼接到 C 代码。
如果遇到嵌套构造（如 `AABB(Vector3(0, 0, 0), Vector3(1, 1, 1))`），
内部的 `Vector3(...)` 不会被递归解析为 `godot_new_Vector3_with_float_float_float(...)`，
而是作为原始文本传入，导致生成的 C 代码不合法。

`splitCtorArguments` 正确处理了括号嵌套（不会在内层逗号处断开），
但递归构造表达式的渲染尚未实现。

**建议**：当前如果 Godot API 默认值中不存在嵌套构造的实际案例，
可暂不处理但应在 `renderDefaultConstructorExpr` 入口处做防御性检查，
遇到嵌套构造参数时抛出明确错误而非生成错误 C 代码。

---

### 🟢 P2 — 测试与文档

#### 7. 测试 DisplayName 仍引用旧 API 名称

`CGenHelperUtilityResolutionTest` 中多个测试的 `@DisplayName` 仍使用旧的 `CGenHelper` 方法名：

- `"renderBuiltinConstructorFunctionNameByTypes should compose typed constructor suffixes"`
  → 实际 API 已为 `CBuiltinBuilder.renderConstructorFunctionNameByTypes`
- `"renderBuiltinConstructorFunctionName should support non-type suffix tokens"`
  → 实际 API 已为 `CBuiltinBuilder.renderConstructorFunctionName`
- `"hasBuiltinConstructor should check constructor metadata by argument type list"`
  → 实际 API 已为 `CBuiltinBuilder.hasConstructor`

**建议**：更新 `@DisplayName` 使其与当前 API 一致，降低维护混淆。

#### 8. `splitCtorArguments` 缺少直接单元测试

该私有方法实现了完整的带括号/引号/转义的参数切分逻辑，
但只有通过 `renderDefaultConstructorExpr` 间接测试。
边界情况（空字符串参数、转义引号内逗号、多层嵌套括号）未被直接覆盖。

**建议**：考虑提升为 package-private 并增加针对性测试，
或在 `renderDefaultConstructorExpr` 测试中增加更复杂的参数模式用例。

#### 9. `CBuiltinBuilder` 单元测试缺少错误路径覆盖

当前 `CGenHelperUtilityResolutionTest` 仅测试了正常路径：
- ✅ 有参构造、空参构造、typed array、non-object/object 元素
- ❌ 未测试 `validateConstructor` 校验失败时的异常抛出
- ❌ 未测试不支持的类型或参数组合（如 `renderDefaultConstructorExpr` 最后的 `throw IllegalArgumentException`）
- ❌ 未测试 `renderConstructorFunctionName` 接收空白后缀时的 `IllegalArgumentException`

**建议**：补充异常路径测试以提升防御健壮性。

---

### 🟢 P2 — 设计与耦合

#### 10. `CBuiltinBuilder` ↔ `CGenHelper` 双向耦合

`CBuiltinBuilder` 接收 `CGenHelper` 并频繁调用其 `renderGdTypeName()`、`context()` 等方法。
同时 `CGenHelper` 在构造时创建 `CBuiltinBuilder`。
虽然不是运行时循环依赖，但 `CBuiltinBuilder` 对 `CGenHelper` 的依赖面较宽：
- `helper.renderGdTypeName(type)` — 类型名渲染
- `helper.context().classRegistry()` — 类注册表查询

如果未来 `CBuiltinBuilder` 的使用场景扩展（如被 `ConstructBuiltinInsnGen` 直接持有），
当前的宽耦合可能增加测试 mock 成本。

**建议**：可考虑让 `CBuiltinBuilder` 的构造参数改为更窄的接口
（如 `TypeNameRenderer` + `ClassRegistry`），降低对 `CGenHelper` 整体的依赖。
但这是长期改进，当前实现可接受。

#### 11. `CBodyBuilder` 中仍残留部分构造相关 private 逻辑

`CBodyBuilder.renderUtilityDefaultValueExpr()` 仍然包含：
- String/StringName 默认值渲染（第 782–796 行）
- primitive 默认值直通（第 798–800 行）
- null 默认值渲染（第 826–837 行）
- 构造表达式识别与切分（`literal.indexOf('(')` 判定，第 803–812 行）

其中前三者确实不属于 `CBuiltinBuilder` 的职责（它们不是构造器），
但构造表达式的 **识别** 逻辑（`leftParen > 0 && literal.endsWith(")")"`）
作为入口判定与 `CBuiltinBuilder` 的调用紧耦合，日后如需支持更多构造形式
（如函数调用型默认值），可能需要重新审视此处的分界线。

当前实现可接受，但值得作为技术债记录。

---

### 总结

| 等级 | 编号 | 问题 | 状态 |
|---|---|---|---|
| 🔴 P0 | #1 | `CONSTRUCT_BUILTIN` 等操作码无 CInsnGen，管线崩溃 | 需优先修复 |
| 🟡 P1 | #2 | `isQuotedStringLiteral` 重复 | 建议合并 |
| 🟡 P1 | #3 | `renderStaticStringNameLiteral` 重复 | 建议合并 |
| 🟡 P1 | #4 | `DefaultValueExprResult` / `DefaultConstructorExprResult` 近似重复 | 建议统一 |
| 🟡 P1 | #5 | `resolveBuiltinNumericCtorArgTypes` 缺少 Quaternion/Plane/AABB 等类型 | 需补齐 |
| 🟡 P1 | #6 | 嵌套构造表达式不支持且无防御检查 | 需加防御 |
| 🟢 P2 | #7 | 测试 DisplayName 引用旧方法名 | 建议更新 |
| 🟢 P2 | #8 | `splitCtorArguments` 无直接单元测试 | 建议补充 |
| 🟢 P2 | #9 | 缺少错误路径测试 | 建议补充 |
| 🟢 P2 | #10 | `CBuiltinBuilder` ↔ `CGenHelper` 耦合面较宽 | 长期改进 |
| 🟢 P2 | #11 | `CBodyBuilder` 中构造入口识别逻辑与 builder 紧耦合 | 技术债记录 |

---

## 实施补充记录（2026-02-20，审阅 #5 定向修复）

### 已落地的策略调整

1. `renderDefaultConstructorExpr` 更名为 `renderDefaultValueExpr`，语义定位为：
   - **全局 utility 默认值到 C 表达式的适配层**；
   - 只负责“默认值字面量解析 + 构造表达式渲染”。

2. 在 `CBuiltinBuilder` 新增 `constructBuiltin(TargetRef, ValueRef...)` 能力（通过 `CBodyBuilder` 执行）：
   - 支持统一构造入口；
   - 对 `Array` / `Dictionary` 走专门分支；
   - 对其他 builtin 按 `ExtensionBuiltinClass` 做**严格精确类型匹配**；
   - 若 API 元数据未命中，再匹配 `gdcc_helper.h` 中 4 个 helper shim：
     - `Transform2D(6 float)`
     - `Transform3D(12 float)`
     - `Basis(9 float)`
     - `Projection(16 float)`

3. `Array` / `Dictionary` typed 构造策略明确：
   - Typed Array（元素类型非 Variant）：
     - `godot_new_Array_with_Array_int_StringName_Variant(...)`
   - Typed Dictionary（key/value 均非 Variant）：
     - `godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant(...)`
   - 其余场景（含 Variant）回退无参构造（`godot_new_Array()` / `godot_new_Dictionary()`）。

### 对后续工程有价值的见解

1. **不要再扩展 numeric ctor 的硬编码表**  
   通过 API 元数据精确匹配是可维护路径；硬编码只适合作为 helper shim 的显式白名单。

2. **construct_* 指令与 utility 默认值应复用同一构造策略源**  
   当前已将核心策略收敛到 `CBuiltinBuilder`，后续实现 `Construct*InsnGen` 时应直接复用，避免双轨漂移。

3. **typed container 构造的“script 参数”语义需后续确认**  
   现阶段以 `NULL` 作为 script 指针占位可先跑通构造流程；后续若引入脚本类型化容器，需要补充脚本来源与生命周期约束。

4. **严格匹配策略应保持一致**  
   目前 constructor 匹配明确为精确类型匹配（不做隐式 int->float 放宽），这有助于尽早暴露 IR 前端/中端类型问题。

5. **落地生成器时建议一次覆盖三类 opcode**  
   `CONSTRUCT_BUILTIN`、`CONSTRUCT_ARRAY`、`CONSTRUCT_DICTIONARY` 应同批注册，避免 prepare/init 阶段再出现 opcode 断层。

### 实施补充记录（2026-02-20，typed array 默认值路径迁移）

在 `CBodyBuilder` 的 utility 默认参数补全链路中，`Array[T]([])` 之前采用：
- `godot_new_Array()`
- `godot_array_set_typed(...)`

现已迁移为：
- 先初始化为 `godot_new_Array()`
- 再通过重标记赋值调用  
  `godot_new_Array_with_Array_int_StringName_Variant(&arr, type, class_name, NULL)`

迁移后收益：
- typed array 的默认值路径与后续 `construct_array` 的 typed 构造策略一致；
- 避免继续依赖 `godot_array_set_typed(...)` 的分支语义；
- 为后续统一 container 构造逻辑（默认值/指令）提供同一实现基线。

### 实施补充记录（2026-02-20，移除 `renderTypedArraySetTypedLine`）

- 已移除 `CBuiltinBuilder.renderTypedArraySetTypedLine(...)`。
- `CBodyBuilder.renderUtilityDefaultValueExpr(...)` 现在直接返回 typed array 的构造表达式，
  不再通过额外 pre-call 语句做二次改写。
- `Array[T]([])` 默认值在表达式内完成：
  1) 先以逗号表达式初始化 `godot_new_Array()`
  2) 再调用 `godot_new_Array_with_Array_int_StringName_Variant(...)` 返回 typed array

### 实施补充记录（2026-03-03，construct_array 语义收敛）

- 本轮 `construct_array` 重构不改变 `ConstructBuiltinInsn` 的既有行为与职责边界。
- `Packed*Array` 的构造策略统一收敛到 `construct_array`：
  - 仅根据结果变量类型构造。
  - 出现 `class_name` 操作数时 fail-fast。
- 扩展类型文本规范化由 `CGenHelper.parseExtensionType(...)` 统一提供，避免 resolver 层重复实现。
