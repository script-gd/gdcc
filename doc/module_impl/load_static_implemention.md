# LOAD_STATIC 实现复盘与长期约定（C Backend）

> 本文档作为 `load_static` 的长期维护说明。
> 已完成的实施清单与阶段性打点不再保留，只保留后续工程仍有价值的约定、设计思路与反思。

## 文档状态

- 状态：Implemented / Maintained
- 更新时间：2026-02-26
- 适用范围：`C backend` 对 `load_static` / `store_static` 的生成与校验

---

## 1. 语义边界（长期有效）

### 1.1 `load_static`

`$r = load_static "<class_name>" "<static_name>"`

当前后端只支持三类静态读取：

1. global enum 项
2. builtin class constants
3. engine class integer constants

不支持：

- 脚本类静态字段读写
- 任意“可写静态属性”路径
- engine class 非整数字面量常量读取

### 1.2 `store_static`

`store_static` 在当前阶段统一拒绝（fail-fast）。

理由：

- 与 GDScript 语义对齐：不开放脚本静态字段写入
- builtin constants / global enums 均为只读语义

---

## 2. 实现结构与职责分离

### 2.1 指令生成器分工

- `LoadStaticInsnGen`：
  - 做 IR 层校验（result 存在、可写、非 ref）
  - 完成三路分发（enum / builtin / engine-int）
  - 调用 `CBodyBuilder` 与 `CBuiltinBuilder` 发射代码
- `StoreStaticInsnGen`：
  - 对 `STORE_STATIC` 统一抛 `InvalidInsnException`

### 2.2 注册入口

- `CCodegen` 必须注册：`LOAD_STATIC`、`STORE_STATIC`
- 这属于“不可回退”约束：若移除注册，会回到 unsupported opcode 行为

---

## 3. 元数据契约（ExtensionAPI）

### 3.1 builtin constants

`ExtensionBuiltinClass.ConstantInfo` 采用：

- `name`
- `type`
- `value`

设计目的：

- 在 codegen 阶段做“常量声明类型 -> 目标变量类型”兼容校验
- 错误信息可定位到“常量声明类型”而不是仅凭 literal 推断

### 3.2 engine class constants

- 从 `classes[].constants[]` 读取
- 当前只接受可解析为整数的 `value`
- 非整数常量直接 `InvalidInsnException`

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

1. global enum 成功/失败
2. builtin constant 成功（普通值 + `INF`）
3. engine class integer constant 成功
4. engine class non-integer constant 失败
5. result 变量非法（缺失 / ref）
6. `store_static` 统一拒绝
7. builtin constant `type` 元数据解析正确

建议命令（按需 targeted）：

```bash
./gradlew test --tests CLoadStaticInsnGenTest --tests CStoreStaticInsnGenTest --tests ExtensionApiLoaderTest --no-daemon --info --console=plain
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
