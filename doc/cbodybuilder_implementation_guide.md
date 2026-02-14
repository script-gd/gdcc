# CBodyBuilder 后端重构实施说明

## 1. 项目背景

GDCC 当前 C 后端函数体代码生成主要基于 FreeMarker 模板与 `CGenHelper`，存在以下问题：

- 指令级生命周期语义分散在模板中，不易统一维护
- `Variant` 与 `Object` 生命周期历史实现存在不一致
- GDCC 类型与 Godot 对象指针转换细节容易遗漏
- 缺少统一的类型校验与错误定位抽象

本次重构目标是引入 `CBodyBuilder`，作为函数级、单线程使用的链式 C 代码构建器，逐步替换模板路径，并严格遵循 `doc/gdcc_c_backend.md` 的语义。

## 2. 约束与边界

### 2.1 必须遵循

- 语义以 `doc/gdcc_c_backend.md` 为准
- `setCurrentPosition` 和 `getCurrentInsn` 用于精确错误定位与类型安全
- 所有相关 API 支持两种输入
  - 函数内变量
  - 自定义 C 表达式加显式 `GdType`
- 赋值目标若为 `ref=true` 变量必须拒绝
- 赋值语义必须隐式处理旧值销毁和对象所有权交接

### 2.2 明确不做

- `CBodyBuilder` 第一版不支持字段赋值 API
  - 字段赋值由各指令生成器负责
- 不一次性删除 `CGenHelper`
  - 采用渐进迁移
- 不改动构建脚本

## 3. 核心语义基线

### 3.1 类型与传参

- primitive 与 object 指针按值传递
- 值语义包装类型传参时按 C ABI 需求传引用
- 是否加 `&` 必须按静态类型与变量 ref 属性统一判定

### 3.2 赋值语义

赋值不是简单覆盖，必须执行完整流程：

1. 校验目标存在、可赋值
2. 校验目标不是 ref 变量
3. 准备 RHS 存储值
   - 必要时复制或类型转换
4. 处理旧值
   - 非对象销毁
   - 对象 release
5. 写入新值
6. 对对象新值 own

### 3.3 GDCC 对象与 Godot 对象

- GDCC 对象不能直接作为 GDExtension API 的对象指针
  - 需使用 `gdccObj->_object`
- `gdcc_object_from_godot_object_ptr` 只做转换，不获取所有权
  - 后续若存储需 own 或 try_own

### 3.4 RefCounted 策略

根据 `ClassRegistry` 查询 `RefCountedStatus`：

- 确定为 RefCounted
  - 使用 `own_object` 与 `release_object`
- 确定非 RefCounted
  - 不做 own/release
- 不确定
  - 使用 `try_own_object` 与 `try_release_object`

始终先做对象类型判定；对 GDCC 对象执行 own/release 时传 `_object`。

## 4. 重点注意事项

- `getCurrentInsn` 做 opcode 兼容校验后再进行泛型强转
  - 可抑制 unchecked 警告
  - 不依赖 `Class.cast`
- 临时变量生命周期只对当前指令负责
  - 指令内创建、使用、销毁
  - 不接管 IR 显式变量生命周期
- 唯一例外是赋值语义
  - 覆盖变量包含隐式销毁和所有权切换
- 模板与 builder 并存阶段需优先迁移高风险指令
  - Variant 与 Object 相关优先
- `render*` 辅助方法必须为纯渲染
  - 不允许直接写入 `out`
  - 仅返回渲染结果或登记临时变量需求
- 所有生成的临时变量必须可追踪并在指令末尾销毁
  - 生成时登记类型与初始化表达式
  - 指令级 API 负责在语句前输出声明、语句后输出 destroy
- `__prepare__` 与 `__finally__` 基本块的语义必须严格遵循
  - `__prepare__` 中 IR 显式声明的非 ref 变量视为未初始化，赋值不应销毁旧内容
  - 非 `__finally__` 基本块中 `return/returnValue` 不生成真正的 return
    - 对于非 void 返回值，将结果赋给隐式变量 `_return_val`
    - 然后跳转到 `__finally__`
  - 仅在 `__finally__` 基本块中生成真实的 return
  - 对于非 void 函数，在 `__prepare__` 基本块顶部声明 `_return_val`
    - `_return_val` 无需自动销毁

## 5. 易踩坑清单

1. 将 GDCC 对象指针直接传入 `try_own_object` 或 `release_object`
   - 错误，必须传 Godot 对象指针
2. 认为 `gdcc_object_from_godot_object_ptr` 会自动持有对象
   - 错误，转换后仍需 own
3. 对 `ref=true` 变量执行赋值
   - 必须直接报错
4. 对非对象类型遗漏 copy 与 destroy
   - 会导致悬挂引用或资源泄漏
5. 对对象覆盖赋值漏掉旧值 release
   - 会导致泄漏
6. 条件跳转仅支持变量，遗漏表达式
   - 本次要求两类输入都支持
7. 错误定位信息缺失 block 与 insnIndex
   - 影响排查效率

## 6. CBodyBuilder 目标 API

以下 API 均返回 builder，实现链式调用：

- `beginBasicBlock`
- `assignVar`
- `callVoid`
- `callAssign`
- `jump`
- `jumpIf`
- `returnVoid`
- `returnValue`
- `assignExpr`
- `assignGlobalConst`

统一值输入模型：

- `valueOfVar`
- `valueOfExpr`

说明：`jumpIf`、`call`、`returnValue` 必须同时支持变量与表达式输入。

## 7. 分阶段实施方案

### 阶段 A：值模型与定位能力固化

- 在 `CBodyBuilder` 增加内部值抽象
  - `ValueRef`
  - `TargetRef`
- 完善 `setCurrentPosition` 与 `getCurrentInsn`
  - 未设置上下文时抛错
  - opcode 不匹配抛 `InvalidInsnException`

交付结果：

- builder 可安全获取当前指令上下文
- 后续 API 均基于统一值抽象开发

### 阶段 B：链式 API 主干落地

实现以下主干方法并接入统一值输入：

- `assignVar`
- `callVoid`
- `callAssign`
- `jump`
- `jumpIf`
- `returnVoid`
- `returnValue`

交付结果：

- 指令生成器可直接调用 builder API 生成函数体语句

### 阶段 C：语义内核实现

新增内部能力：

- 参数渲染与 `&` 决策
- 可赋值校验
- RHS 复制与转换
- 非对象销毁
- 对象 own/release

交付结果：

- 赋值、传参、返回具备一致语义

### 阶段 D：GDCC Object 转换与 RefCounted 决策

新增内部方法：

- toGodotObjectPtr
- fromGodotObjectPtr
- ownOrTryOwn
- releaseOrTryRelease

交付结果：

- GDCC 与 Godot 对象转换行为一致
- 所有权策略由 `RefCountedStatus` 驱动

### 阶段 E：接入 CCodegen 渐进迁移

- 在函数级实例化 builder
- 每条指令先调用 `setCurrentPosition`
- 优先迁移 Variant 和 Object 敏感指令
- 其余暂时保留模板路径

交付结果：

- 新旧架构并存可运行
- 高风险路径先修复

### 阶段 F：测试闭环与回归

新增或改造测试覆盖：

- `getCurrentInsn` 校验
- ref 变量禁赋值
- 可赋值校验失败路径
- GDCC 与 Godot 对象转换
- own/release 决策矩阵
- `jumpIf` 变量与表达式双路径
- 临时变量唯一性与即时销毁

交付结果：

- 可验证语义正确性
- 迁移过程可回归

## 8. 迁移优先级

- P0: Variant 与 Object 生命周期相关指令
- P1: 控制流和返回
- P2: 普通调用与构造
- P3: 其余模板指令

## 9. 风险与缓解

### 风险 1

新旧生成路径并存导致行为差异

缓解：

- 先迁移高风险指令
- 对比关键样例输出并加回归测试

### 风险 2

`RefCountedStatus` 信息不完整

缓解：

- 默认走 try 路径保证正确性
- 后续逐步完善类型信息提升性能

### 风险 3

表达式输入被误用造成类型漂移

缓解：

- 表达式入口必须显式传 `GdType`
- 所有关键 API 强制做 assignable 校验

## 10. 执行顺序建议

1. 完成 builder 值模型与主干 API
2. 完成语义内核与对象转换
3. 接入 `CCodegen` 并迁移 P0 指令
4. 补齐测试后迁移 P1 P2 P3
5. 准备后续将 `CGenHelper` 静态能力迁出到 util

## 11. PtrKind 与自动指针转换机制

### 11.1 背景与动机

GDExtension API 不接受 GDCC 对象指针，调用时需要 `gdcc_object->_object` 转换；从 GDExtension API 返回的 `godot_Object*` 若目标为 GDCC 变量则需要 `gdcc_object_from_godot_object_ptr` 转换。在 Phase D 阶段，`toGodotObjectPtr` 和 `fromGodotObjectPtr` 方法已实现，但 `callVoid`/`callAssign`/`renderArgument` 路径未集成这些转换，导致 ABI 不匹配。

### 11.2 PtrKind 枚举

在 `ValueRef` 上引入指针来源语义，避免仅靠 `GdType` 推断而产生歧义：

```java
public enum PtrKind {
    GDCC_PTR,    // GDCC 对象指针（带 _object 字段的 wrapper）
    GODOT_PTR,   // Godot/引擎原始对象指针
    NON_OBJECT   // 非对象指针（基元、值语义类型等）
}
```

**解析规则**（`resolvePtrKind`）：
- `GdObjectType` + `checkGdccType` → `GDCC_PTR`
- `GdObjectType` + 非 GDCC → `GODOT_PTR`（含引擎类型和未知类型）
- 其他类型 → `NON_OBJECT`
- **注意：该规则并非100%准确，GDCC对象的指针可能由于某些操作变为GODOT_PTR，但其类型仍然为GDCC自定义对象类型，反之亦然！**

**工厂方法**：
- `valueOfVar(variable)` — 从变量类型自动推断
- `valueOfExpr(code, type)` — 从类型自动推断
- `valueOfExpr(code, type, ptrKind)` — 显式指定（用于已知来源语义的表达式）

### 11.3 自动转换规则

#### 参数转换（renderArgument）

调用 `callVoid`/`callAssign` 时，通过 `checkGlobalFuncRequireGodotRawPtr(funcName)` 判断目标函数是否需要 Godot 原始指针：

- 函数名以 `godot_` 开头 → 需要转换
- 函数名以 `own_object`/`release_object` 结尾 → 需要转换
- `try_destroy_object`、`gdcc_object_from_godot_object_ptr` → 需要转换

当需要转换且参数 `ptrKind == GDCC_PTR` 时，自动调用 `toGodotObjectPtr`（生成 `$arg->_object`）。

#### 返回值转换（callAssign）

调用 `callAssign` 时，通过 `checkGlobalFuncReturnGodotRawPtr(funcName)` 判断返回值是否为 Godot 原始指针：

- 函数名以 `godot_` 开头 → 返回 Godot 指针

当返回为 Godot 指针且目标变量类型为 GDCC 对象（`checkGdccType`）时，自动调用 `fromGodotObjectPtr` 包裹整个调用表达式。

### 11.4 指令生成器编写指南

**新规则**：指令生成器不应手动拼接 `->_object` 或 `gdcc_object_from_godot_object_ptr`。只需：

1. 使用 `bodyBuilder.valueOfVar(objectVar)` 传递对象参数
2. 使用正确的函数名调用 `callVoid`/`callAssign`
3. 转换由 CBodyBuilder 自动完成

**示例** — `LoadPropertyInsnGen` "general" 模式（旧 vs 新）：

```java
// 旧：手动拼接 ->_object
var objectRef = helper.checkGdccType(objectType) ? "$" + objectVar.id() + "->_object" : "$" + objectVar.id();
var objectValue = bodyBuilder.valueOfExpr(objectRef, new GdObjectType("Object"));

// 新：直接传变量，自动转换
var objectValue = bodyBuilder.valueOfVar(objectVar);
```

**注意**：`callAssign` 的返回值转换仅在 `funcName` 满足 `checkGlobalFuncReturnGodotRawPtr` 时生效。对于已在函数名中包含显式类型转换的调用（如 `renderUnpackFunctionName` 返回的 `(MyClass*)godot_new_gdcc_Object_with_Variant`），由于函数名不以 `godot_` 开头，不会触发二次转换。

### 11.5 后续迁移指引

对于尚未迁移到 CBodyBuilder 的指令生成器（使用 FreeMarker 模板），迁移时应：

1. 移除模板中手动的 `->_object` 和 `gdcc_object_from_godot_object_ptr` 逻辑
2. 改用 `callVoid`/`callAssign` + `valueOfVar`，依赖自动转换
3. 仅在自动转换规则无法覆盖的特殊场景（如非标准函数名）使用 `valueOfExpr` 的显式 `PtrKind` 重载

### 11.6 临时变量使用规范（分类）

为避免跨 API 手工维护状态，`TempVar` 自身维护“是否已初始化”的可变状态。按用途分三类：

1. **声明即初始化（Expression staging）**
   - 典型场景：表达式物化、copy staging。
   - 形式：`newTempVariable(prefix, type, initCode)` + `declareTempVar(temp)`。
   - 语义：声明后立即视为已初始化，可被销毁。

2. **仅声明后首写（Out-init / deferred init）**
   - 典型场景：某些函数需要接收未初始化存储的指针并在调用中完成初始化。
   - 形式：`newTempVariable(prefix, type)` + `declareTempVar(temp)`，后续使用 `assignVar/callAssign/initTempVar` 写入。
   - 语义：在首写前视为未初始化，`assignVar/callAssign` 不得执行旧值 destroy/release。

3. **指令内临时生命周期（Per-insn temp）**
   - 原则：创建、使用、销毁均在同一条指令逻辑中闭合。
   - `destroyTempVar` 仅对“已初始化临时变量”生效，销毁后状态重置为未初始化。

**统一规则：**

- 赋值 API（`assignVar`、`callAssign`）必须统一判定 TargetRef：
  - 若目标是“未初始化 TempVar”，跳过旧值销毁流程，仅执行写入/转换/own。
  - 其他目标维持既有完整赋值语义。
- 不强制做“临时变量读前初始化”全局检查，避免阻断合法的 out-init 场景。
- 指令生成器优先使用 `callAssign`/`assignVar` 完成 temp 首写，减少手写字符串初始化路径。

---

本文件作为评审基线，后续代码实施应严格以本说明和 `doc/gdcc_c_backend.md` 为语义依据。
