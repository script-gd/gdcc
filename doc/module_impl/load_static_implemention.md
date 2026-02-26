# LOAD_STATIC 实施方案（C Backend）

## 文档状态

- 状态：Proposed
- 更新时间：2026-02-25
- 目标：为 `load_static` 增加可落地的 C 后端生成能力
- 范围约束：
  - 仅支持三类静态读取：
    1. 语言内建类型常量（builtin class constants）
    2. 全局枚举项（global enums）
    3. 引擎类整数常量（engine class integer constants）
  - 不支持 GDScript 静态字段读写
  - `store_static` 在当前阶段应显式拒绝

---

## 背景与问题

当前仓库中：

1. IR 已定义 `load_static` / `store_static`
   - `src/main/java/dev/superice/gdcc/enums/GdInstruction.java`
   - `src/main/java/dev/superice/gdcc/lir/insn/LoadStaticInsn.java`
   - `src/main/java/dev/superice/gdcc/lir/insn/StoreStaticInsn.java`

2. Parser 已能解析这两条指令
   - `src/main/java/dev/superice/gdcc/lir/parser/ParsedLirInstruction.java`

3. C 后端尚未接入对应 `CInsnGen`
   - `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java` 未注册 `LOAD_STATIC` / `STORE_STATIC`
   - 若 IR 中出现 `load_static`，当前会在代码生成时落入 unsupported opcode 分支

4. 可复用能力已存在
   - 全局枚举赋值：`CBodyBuilder.assignGlobalConst(...)`
   - default literal 物化：`CBuiltinBuilder.materializeUtilityDefaultValue(...)`

---

## 语义对齐（本项目约束）

### 1) `load_static`

`$r = load_static "<class_name>" "<static_name>"`

在本项目中解释为：

- 分支 A：`<class_name>` 是 global enum 名，`<static_name>` 是枚举项
- 分支 B：`<class_name>` 是 builtin class 名，`<static_name>` 是该类型常量
- 分支 C：`<class_name>` 是 engine class 名，`<static_name>` 是该类的整数常量

不做：

- 任意类的静态字段读取
- 任意脚本类静态属性读取
- engine class 非整数常量读取（当前阶段仅支持可解析为整数的常量）

### 2) `store_static`

当前阶段统一禁止。理由：

- GDScript 不支持静态字段
- builtin 常量与 global enum 均为只读语义

---

## ExtensionAPI 元数据来源

### 1) builtin constants

来源：`extension_api_451.json` 中 `builtin_classes[].constants[]`

结构示例（概念上）：

```json
{
  "name": "BACK",
  "type": "Vector3",
  "value": "Vector3(0, 0, 1)"
}
```

### 2) global enums

来源：`extension_api_451.json` 中 `global_enums[]`

结构示例（概念上）：

```json
{
  "name": "Side",
  "values": [
    { "name": "SIDE_LEFT", "value": 0 }
  ]
}
```

### 3) engine class integer constants

来源：`extension_api_451.json` 中 `classes[].constants[]`

约束：

- 当前仅支持 `value` 能解析为整数（如 `-1`、`0`、`42`）的常量
- 非整数常量在本阶段按不支持处理并抛 `InvalidInsnException`

结构示例（概念上）：

```json
{
  "name": "Node",
  "constants": [
    { "name": "NOTIFICATION_ENTER_TREE", "value": "10" }
  ]
}
```

---

## 关键设计决策

## 决策 A：新增专用生成器 `LoadStaticInsnGen`

新增文件：

- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/LoadStaticInsnGen.java`

注册位置：

- `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`

职责：

- 做 IR 指令层校验
- 根据 `class_name/static_name` 解析目标来源（enum / builtin constant / engine class integer constant）
- 调用 `CBodyBuilder` / `CBuiltinBuilder` 完成代码发射

## 决策 B：`store_static` 显式 fail-fast

新增文件：

- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/StoreStaticInsnGen.java`

行为：

- 对 `STORE_STATIC` 一律抛 `InvalidInsnException`
- 错误信息说明当前限制（仅支持 `load_static`，且只支持 builtin constants / global enums / engine class integer constants）

## 决策 C：统一使用 `godot_inf` 宏表达无穷大

当常量 literal 中出现 `inf` 时：

- 正无穷统一生成为 `godot_inf`
- 负无穷统一生成为 `-godot_inf`

注意：

- 本文档要求后续所有 `inf` C 代码生成都统一走该宏，不直接写 `INFINITY` 或 `HUGE_VAL`。

---

## 数据流与生成流程

## 1) `LoadStaticInsnGen` 主流程

输入：`LoadStaticInsn(resultId, className, staticName)`

步骤：

1. 校验基础合法性
   - `resultId` 非空
   - result 变量存在
   - result 变量不可为 `ref`

2. 尝试按 global enum 解析
   - `classRegistry.findGlobalEnum(className)`
   - 若命中，则按 enum value 名查找 `staticName`
   - 命中后调用 `bodyBuilder.assignGlobalConst(target, className, staticName)`
   - 结束

3. 尝试按 builtin constant 解析
   - `classRegistry.findBuiltinClass(className)`
   - 在 `builtin.constants` 中按名匹配 `staticName`
   - 命中后将常量 `value` literal 物化到 result
   - 结束

4. 尝试按 engine class integer constant 解析
   - 通过 `classRegistry.getClassDef(new GdObjectType(className))` 定位 class 定义
   - 仅当 class 为引擎类（`ExtensionGdClass`）时继续
   - 在 `class.constants` 中按名匹配 `staticName`
   - 将 `value` 解析为整数并赋值到 result（按 `int` 语义）
   - 若 `value` 不是整数，抛 `InvalidInsnException`
   - 结束

5. 三路均未命中
   - 抛 `InvalidInsnException`

## 2) builtin constant literal 物化策略

优先复用 `CBuiltinBuilder` 现有能力，不另起一套 parser。

建议新增一个通用入口（可选命名）：

- `materializeStaticLiteralValue(...)`

其内部策略可复用 `materializeUtilityDefaultValue(...)` 的解析框架，重点补强：

1. 允许构造器字面量 `Type(...)`
2. 支持 `inf` token
3. `inf` token 映射为 `godot_inf`
4. 对构造器参数中的 `-inf` 映射为 `-godot_inf`

---

## 元数据模型补强

当前 `ExtensionBuiltinClass.ConstantInfo` 只有 `(name, value)`，缺少 `type`。

建议改为：

- `ConstantInfo(String name, String type, String value)`

并同步更新：

- `ExtensionApiLoader.parseBuiltinClasses(...)`

收益：

1. 可校验常量声明类型与目标变量类型是否兼容
2. 报错更准确
3. 后续支持更多常量形态时更稳

兼容策略：

- 若 JSON 缺失 `type`，允许回退为 `null`，但在使用时做显式分支与报错。

---

## 错误处理规范

所有语义错误统一抛 `InvalidInsnException`，不要静默降级。

建议错误文案（示例）：

- `Load static instruction missing result variable ID`
- `Result variable ID '<id>' not found in function`
- `Result variable ID '<id>' cannot be a reference`
- `Global enum '<name>' not found`
- `Global enum value '<value>' not found in enum '<name>'`
- `Builtin class '<name>' not found`
- `Builtin constant '<const>' not found in class '<name>'`
- `Static load target type '<target>' is not assignable from builtin constant type '<constType>'`
- `Engine class '<class>' not found`
- `Engine class constant '<const>' not found in class '<class>'`
- `Engine class constant '<const>' in class '<class>' is not an integer literal: '<value>'`
- `Static load target type '<target>' is not assignable from engine class integer constant`
- `Unsupported static store: 'store_static' is not allowed in current backend`

---

## `godot_inf` 统一约定

## 1) 约定内容

- 只要遇到语义上的正无穷，C 代码统一输出 `godot_inf`
- 负无穷统一输出 `-godot_inf`

## 2) 生效范围

- `load_static` 对 builtin constants 的字面量物化
- `load_static` 对 engine class integer constants 的整数物化（不涉及 `inf`）
- utility default literal 物化中已有/未来出现的 `inf`（建议同步对齐）
- 其他后续 literal 解析路径（如新增场景）

## 3) 约束目的

- 保持代码生成风格统一
- 降低平台差异与包含头文件差异导致的问题
- 便于后续集中替换实现细节

---

## 实施拆解

## Phase 1（最小可用）

1. `ConstantInfo` 加入 `type` 并添加单元测试验证
2. 新增 `LoadStaticInsnGen`
3. 在 `CCodegen` 注册 `LOAD_STATIC`
4. 新增 `StoreStaticInsnGen`（统一 reject）
5. 在 `CCodegen` 注册 `STORE_STATIC`
6. 完成 `global enum`、`builtin constants`、`engine class integer constants` 三路分发

验收：

- `load_static` 不再触发 unsupported opcode
- 示例 `Vector3.BACK/UP/ZERO` 可生成 C 代码
- 示例 `Node.NOTIFICATION_ENTER_TREE`（或任意可解析整数的引擎类常量）可生成整数字面量赋值
- `store_static` 失败信息可读且稳定

## Phase 2（语义增强）

1. 增加类型兼容校验
2. 统一 `inf -> godot_inf` 映射
3. 将 literal 物化入口抽象成可复用方法，减少重复逻辑

验收：

- `Vector*.INF` 常量生成使用 `godot_inf`
- 类型错误可精准报出常量声明类型与目标类型

---

## 测试计划（JUnit5）

新增测试文件建议：

- `src/test/java/dev/superice/gdcc/backend/c/gen/CLoadStaticInsnGenTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CStoreStaticInsnGenTest.java`

建议用例：

1. global enum 成功
   - 输入：`load_static "Side" "SIDE_LEFT"`
   - 断言：result 为对应整数字面量赋值

2. global enum 缺失
   - 断言：`assertThrows(InvalidInsnException.class, ...)`

3. builtin constant 成功（普通）
   - 输入：`load_static "Vector3" "BACK"`
   - 断言：生成 Vector3 构造代码

4. builtin constant 成功（INF）
   - 输入：`load_static "Vector3" "INF"`
   - 断言：生成代码包含 `godot_inf`

5. engine class integer constant 成功
   - 输入：`load_static "Node" "NOTIFICATION_ENTER_TREE"`（示例，实际以 ExtensionAPI 为准）
   - 断言：result 为整数字面量赋值

6. engine class constant 非整数
   - 断言：`assertThrows(InvalidInsnException.class, ...)`

7. builtin constant 不存在
   - 断言：`InvalidInsnException`

8. result 变量不存在 / result 为 ref
   - 断言：`InvalidInsnException`

9. `store_static` 任意输入
   - 断言：统一失败，错误语义稳定

可选补充：

- `CCodegen` 端到端 smoke，用一个最小函数覆盖 `load_static` 分支。

---

## 风险与注意事项

1. 常量 literal 与 default literal 的解析规则若分叉，后续维护成本会上升。
   - 建议尽早收敛到单一物化通道。

2. `inf` 解析若仅在某一路实现，易出现行为不一致。
   - 必须统一为 `godot_inf` 策略。

3. 文档与实现需同步更新：
   - `doc/gdcc_low_ir.md`
   - 本文档
   - 相关测试说明

---

## 建议落地顺序

1. 先补 `ConstantInfo.type`
2. 再做 `LoadStaticInsnGen` + `StoreStaticInsnGen` + dispatch 注册（含 engine class integer constant 分支）
3. 最后收敛 literal 物化并统一 `godot_inf`（对 `inf` 生效）
4. 逐步补齐测试矩阵

---

## 非目标（当前不做）

1. 支持脚本类静态字段
2. 支持 engine class 静态字段写入
3. 放宽 `store_static` 到任何可写路径
4. 在本阶段引入与 `load_static` 无关的 IR 结构调整
