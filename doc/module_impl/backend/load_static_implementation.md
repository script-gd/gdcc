# LOAD_STATIC 实现复盘与长期约定（C Backend）

> 本文档作为 `load_static` 的长期维护说明。
> 已完成的实施清单与阶段性打点不再保留，只保留后续工程仍有价值的约定、设计思路与反思。

## 文档状态

- 状态：Implemented / Maintained
- 更新时间：2026-06-27
- 适用范围：`C backend` 对 `load_static` / `store_static` 的生成与校验

---

## 1. 语义边界（长期有效）

### 1.1 `load_static`

`$r = load_static "<class_name>" "<static_name>"`

 当前后端支持七类静态读取：

1. `@GlobalScope` singleton properties
2. `@GlobalScope` 顶层 global constants
3. global enum 项
4. builtin class constants
5. builtin class enum values
6. engine class integer constants
7. engine class enum values

engine class constants / enum values 按 `ClassRegistry` 的 inherited static lookup 解析：先查 receiver class
本身，再沿 `classes[].inherits` 近到远查父类，返回实际声明 owner 后再执行 literal materialization 与诊断。
IR 中的 `class_name` 仍保留源码 receiver class，不在 frontend canonicalize 成 owner class。builtin metadata 当前没有
superclass edge，因此 builtin constant / enum value 入口保持 direct-only。

不支持：

- 脚本类静态字段读写
- 任意“可写静态属性”路径
- engine class 非整数字面量常量读取

### 1.2 `store_static`

`store_static` 在当前阶段统一拒绝（fail-fast）。

理由：

- 与 GDScript 语义对齐：不开放脚本静态字段写入
- builtin constants / global enums / global constants 均为只读语义

---

## 2. 实现结构与职责分离

### 2.1 指令生成器分工

- `LoadStaticInsnGen`：
  - 做 IR 层校验（result 存在、可写、非 ref）
  - 完成多路分发（global constant / global enum / singleton / builtin constant / builtin enum / engine-int constant / engine enum）
  - 调用 `CBodyBuilder` 与 `CBuiltinBuilder` 发射代码
- `StoreStaticInsnGen`：
  - 对 `STORE_STATIC` 统一抛 `InvalidInsnException`

### 2.2 注册入口

- `CCodegen` 必须注册：`LOAD_STATIC`、`STORE_STATIC`
- 这属于“不可回退”约束：若移除注册，会回到 unsupported opcode 行为

---

## 3. 元数据契约（ExtensionAPI）

### 3.0 global constants

- 从顶层 `global_constants[]` 读取，进入 `ExtensionAPI.globalConstants()` 后由 `ClassRegistry` 建立按名索引。
- `global_constants[]` 是 Godot `@GlobalScope` 的扁平常量表，不是 global enum 的 owner/member 分组。
- Backend IR 使用 `load_static "@GlobalScope" "<NAME>"` 显式表示这一路径；裸脚本常量 lowering 可直接发
  `literal_int`。
- `ExtensionGlobalConstant.value` 使用 Java `long` 保存 Godot int64 metadata；后端输出十进制 `godot_int`
  literal，不按 Java `int` 截断。

### 3.0.1 singleton properties

- 从顶层 `singletons[]` 读取，`ExtensionSingleton.name()` 是 `@GlobalScope` property lookup name，
  `ExtensionSingleton.type()` 是 declared object return type。
- `ClassRegistry` 预先验证 singleton metadata。只有 `type` 能 strict resolve 为 `GdObjectType` 时，
  `findSingletonType(lookupName)` 才返回有效类型；缺失、空白、unknown 或非 object type 都不会发布为
  singleton value。
- Backend IR 使用 `load_static "@GlobalScope" "<singleton_name>"` 显式表示 singleton property read。
  `LoadStaticInsnGen` 先检查 singleton property，再回退 global constant，避免 object singleton 被旧的
  global-constant-only `int` 校验误拒绝。
- C backend 发射 `godot_<lookupName>_singleton()` wrapper 调用。`Engine`、`ClassDB` 等 fixed-provided
  wrappers 只作为 provided symbol 使用，不重复输出到 `engine_method_binds.h`；非 provided singleton 才生成
  module-local wrapper。
- singleton getter 返回 Godot engine registry 中已存在的 object pointer，是 borrowed source。
  `LoadStaticInsnGen` 必须用 borrowed object assignment 路径，不能走 `callAssign(...)` 或任何 owned-result
  producer 路径。

### 3.1 global enum values

- 从 `global_enums[].values[]` 读取。
- `ExtensionEnumValue.value` 使用 Java `long` 保存 Godot int64 metadata；`load_static` 输出十进制
  `godot_int` literal，不按 Java `int` 截断。
- 静态类型仍是 `GdIntType.INT`。这里的 `long` 只是 metadata/literal carrier 宽度，不引入新的脚本整数类型。

### 3.2 builtin constants

`ExtensionBuiltinClass.ConstantInfo` 采用：

- `name`
- `type`
- `value`

设计目的：

- 在 codegen 阶段做“常量声明类型 -> 目标变量类型”兼容校验
- 错误信息可定位到“常量声明类型”而不是仅凭 literal 推断

### 3.2.1 builtin class enum values

- 从 `builtin_classes[].enums[].values[]` 读取（`ExtensionBuiltinClass.ClassEnum` -> `ExtensionEnumValue`）。
- 查找走 `ClassRegistry.findBuiltinClassEnumValueInHierarchy(className, valueName)`；由于 ExtensionAPI builtin
  metadata 当前没有 superclass edge，这条入口保持 direct-only。`LoadStaticInsnGen` 在 builtin constant 未命中后回退到 enum value 分支。
- enum value 恒为整数：静态类型 `GdIntType.INT`，后端以 `Long.toString(value)` 输出十进制 `godot_int`
  literal，与 global enum value 路径一致，不经过 `CBuiltinBuilder.materializeStaticLiteralValue`。

### 3.3 engine class constants

- 从 `classes[].constants[]` 读取
- 查找走 `ClassRegistry.findEngineClassConstantInHierarchy(className, constantName)`，直接类优先，父类按
  `inherits` 链近到远查找；缺失 superclass metadata 或循环继承时 fail closed。
- 当前只接受可解析为整数的 `value`
- 该值在模型中保持 Godot 原始字符串 literal；不要为了统一 enum value 宽度把 builtin / engine class
  constant 的 construct string 改成数值 carrier。
- 非整数常量直接 `InvalidInsnException`

### 3.3.1 engine class enum values

- 从 `classes[].enums[].values[]` 读取（`ExtensionGdClass.ClassEnum` -> `ExtensionEnumValue`）。
- 查找走 `ClassRegistry.findEngineClassEnumValueInHierarchy(className, valueName)`；`LoadStaticInsnGen` 在 engine
  integer constant 未命中后回退到 enum value 分支，并保留 source receiver class 作为 IR 操作数。
- enum value 恒为整数：静态类型 `GdIntType.INT`，后端以 `Long.toString(value)` 输出十进制 `godot_int`
  literal，与 global enum value 路径一致。
- 这与 engine class **常量** 的字符串 literal 校验（`INTEGER_LITERAL_PATTERN`）是两条独立分支；enum value
  走 long carrier，不经过该正则。

---

## 4. literal 物化约定（单一路径）

### 4.1 入口收敛

`load_static` 的 builtin constant literal 物化必须走 `CBuiltinBuilder` 的统一入口，
与 utility default literal 共用核心解析逻辑，避免规则分叉。

### 4.2 `inf` 统一策略

语义上的无穷大统一映射：

- `inf` / `+inf` -> `godot_inf`
- `-inf` -> `-godot_inf`

约束目的：

- 避免 `INFINITY` / `HUGE_VAL` 在不同平台头文件差异引入不一致
- 统一生成风格，便于后续集中替换

### 4.3 正则性能约定

在热路径中使用的数字字面量匹配规则应预编译为 `Pattern` 常量，
避免频繁 `String.matches(...)` 的隐式重复编译开销。

---

## 5. 错误处理约定

- 所有语义问题统一抛 `InvalidInsnException`
- 不允许静默降级或“猜测性兜底”
- 错误文案应满足：
  - 指令位置可定位（依赖 `CBodyBuilder.invalidInsn(...)`）
  - 带上 class/constant/target type 等关键上下文
  - 与测试断言保持稳定（避免无意义改写）

---

## 6. 回归测试基线

建议长期保留以下测试关注点：

1. `@GlobalScope` singleton property 成功/失败
2. `@GlobalScope` global constant 成功/失败
3. global enum 成功/失败
4. builtin constant 成功（普通值 + `INF`）
5. engine class integer constant 成功
6. inherited engine class integer constant / enum value 成功，且直接类成员遮蔽父类成员
7. engine class non-integer constant 失败
8. inherited engine class non-integer constant 失败，诊断指向实际 owner class
9. result 变量非法（缺失 / ref）
10. `store_static` 统一拒绝
11. builtin constant `type` 元数据解析正确
12. fixed-provided singleton wrapper 不进入 module-local header，non-provided singleton 才进入

建议命令（按需 targeted）：

```bash
script/run-gradle-targeted-tests.sh --tests CLoadStaticInsnGenTest,CStoreStaticInsnGenTest,ExtensionApiLoaderTest
```

---

## 7. 工程反思（对后续有价值）

1. **不要复制 literal parser**：`load_static` 与 utility default literal 若各自演化，维护成本会快速上升。
2. **元数据优先于推断**：builtin constant 的声明类型应作为校验主依据，避免由 literal 反推类型带来的歧义。
3. **fail-fast 比“宽松兼容”更安全**：当前阶段对 `store_static` 和 engine non-int constant 的拒绝，有助于保持 IR 语义边界清晰。
4. **文档只保留长期信息**：阶段实施步骤、已完成打点应从实现文档中清理，避免后续阅读噪音。

---

## 8. 非目标（当前不做）

1. 支持脚本类静态字段
2. 支持 engine class 静态字段写入
3. 放宽 `store_static` 到可写路径
4. 为 `load_static` 引入无关 IR 结构改造
