# CALL_GLOBAL 实现说明（最终合并版）

## 文档状态

- 状态：**Active / 单一事实来源**
- 范围：`CALL_GLOBAL` 在 `backend.c` 的 utility 路径实现与长期约束
- 更新时间：2026-02-24
- 目标读者：后续维护者、代码评审者、重构实施者

## 当前最终状态（与代码对齐）

### 覆盖范围

- 当前仅支持 global **utility function** 路径（含 `foo` / `godot_foo` 统一解析）
- 指令入口：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallGlobalInsnGen.java`
- 名称解析与 utility 元数据适配：`src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
- 通用调用发射与生命周期语义：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
- default literal 物化与 builtin 构造策略：`src/main/java/dev/superice/gdcc/backend/c/gen/CBuiltinBuilder.java`

### 已实现语义

- utility 存在性校验（含 lookup key 诊断）
- 实参必须为变量操作数且变量存在
- 参数数量校验（non-vararg / vararg）
- fixed 参数类型校验（`checkAssignable`）
- vararg extra 必须可赋值给 `Variant`
- 返回值与 `resultId` 契约校验（void/non-void、result 变量存在性、`ref` 限制、类型兼容）
- utility 参数类型元数据为空时立即失败（`parameter.type() == null`）

### vararg 契约（固定）

- `varargs == null`：不生成 vararg 尾参数
- `varargs != null`：始终生成 vararg 尾参数
- 空 vararg：发射 `NULL, (godot_int)0`
- `argv` 组装统一在 `CBodyBuilder`，避免生成器手工拼接

### 默认参数补全（已完成）

- 缺失 fixed 参数时：
  - 有 default：补齐后继续调用
  - 无 default：抛出缺参错误（含参数序号）
- default 物化入口：`CBuiltinBuilder.materializeUtilityDefaultValue(...)`
- 支持 literal：
  - `null`
  - bool/int/float
  - `"..."`（String）
  - `&"..."`（StringName）
  - `$"..."`（NodePath）
  - `[]` / `{}`
  - `Type(...)`（基于 constructor metadata + helper shim）
  - typed container default：`Array[T]([])`、`Dictionary[K, V]({})`

### 默认参数生命周期（关键）

- `CallGlobalInsnGen` 为每个缺失参数创建 `TempVar`
- 由 `CBuiltinBuilder` 将 default 物化到该临时变量
- 将临时变量作为实际调用参数
- 调用后逆序 `destroyTempVar(...)`
- 该策略用于避免 destroyable default expr 内联后无法析构导致的泄漏风险

## 设计思路（长期保留）

### 1) 职责边界

- `CallGlobalInsnGen`：仅做指令级语义校验、参数组织、调用路由
- `CBodyBuilder`：仅做通用 C 发射、参数渲染、生命周期处理
- `CGenHelper`：名称规范化与共享辅助
- `CBuiltinBuilder`：builtin/default literal 的解析与构造决策

### 2) 复用优先

- 优先复用 `callVoid` / `callAssign` 既有调用语义
- 默认构造优先复用 `constructBuiltin(...)`
- 避免在生成器中复制 Builder 的通用生命周期逻辑

### 3) 单点实现默认值

- default literal 的语义解析集中在 `CBuiltinBuilder`
- 生命周期托管集中在 `CallGlobalInsnGen`（TempVar 声明/销毁）
- 避免“多处解析、多处生命周期”导致行为漂移

## 犯错反思与反模式

### 反思 1：过早把 default 直接做成 ExprValue

- 问题：destroyable 值可能在调用后未析构，存在泄漏风险
- 结论：default 物化必须走“先落地临时变量，再参与调用，再显式销毁”

### 反思 2：手工拼接 constructor 字符串

- 问题：重复逻辑多、可维护性差、生命周期语义难统一
- 结论：尽量通过 `constructBuiltin(...)` 复用现有构造与容器类型逻辑

### 反思 3：职责下沉过度

- 问题：utility 专有校验下沉到 Builder 会污染通用层
- 结论：Builder 保持通用发射；IR/指令语义校验保留在 InsnGen

## 测试基线（当前有效）

- `src/test/java/dev/superice/gdcc/backend/c/gen/CallGlobalInsnGenTest.java`
  - utility 调用成功/失败主路径
  - vararg 发射契约
  - default 补齐（含 typed Array/Dictionary）
  - default 临时变量生命周期（含 destroy）
- `src/test/java/dev/superice/gdcc/backend/c/gen/UtilityDefaultLiteralMaterializationTest.java`
  - 覆盖 `doc/gdcc_c_backend.md` 中默认值清单的逐项物化验证
  - 优先使用真实 API 类型上下文，不足时使用语法推导兜底
- `src/test/java/dev/superice/gdcc/backend/c/gen/CBodyBuilderPhaseCTest.java`
  - `callVoid` vararg 尾参数契约验证

## 非目标（仍不做）

- 非 utility 的 global symbol 调用分流
- 将 `CallGlobalInsn.args` 收窄为 `List<VariableOperand>` 的 IR 结构改造

## 后续有价值的增强点

- 增强嵌套构造 default literal（当前复杂场景以防御性报错为主）
- 增补 default literal 负例矩阵（复杂转义、边界数字、构造歧义）
- 保持“代码 + 测试 + 本文档”同次同步，避免文档滞后

