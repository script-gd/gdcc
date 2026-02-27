# CALL_METHOD 实现说明（最终合并版）

> 本文档作为 `CALL_METHOD` 在 C Backend 的长期维护说明。  
> 只保留已完成实现、当前状态和后续工程仍有价值的约定与反思。

## 文档状态

- 状态：Implemented / Maintained
- 范围：`backend.c` 的 `CALL_METHOD` 代码生成与调用契约
- 更新时间：2026-02-27
- 关联基线：
  - `doc/module_impl/call_global_implementation.md`
  - `doc/module_impl/cbodybuilder_implementation.md`
  - `doc/module_impl/load_static_implementation.md`

## 当前最终状态（与代码对齐）

### 覆盖范围

- 指令生成入口：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`
- 分派与签名解析：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/MethodCallResolver.java`
- 调用发射与生命周期托管：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
- 模板/运行时辅助：
  - `src/main/c/codegen/template_451/entry.c.ftl`
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`

### 已实现分派模式

- `GDCC`：已知 GDCC 类型静态分派
- `ENGINE`：已知 engine 类型静态分派
- `BUILTIN`：builtin 类型静态分派
- `OBJECT_DYNAMIC`：`GdObjectType` 编译期无法静态决议时回退 `godot_Object_call`
- `VARIANT_DYNAMIC`：`Variant` receiver 编译期无法静态决议时回退 `godot_Variant_call`

### 已实现调用契约

- 接收者、参数变量、结果变量存在性与合法性校验
- 固定参数数量与类型校验
- vararg 契约：extra 参数必须可赋值给 `Variant`
- 返回值契约校验：
  - `void` 方法禁止提供 `resultId`
  - 非 `void` 方法允许 `discard`
  - `resultId` 非空时必须存在、非 ref、类型兼容
- 静态方法通过实例 `call_method` 时允许生成，但会输出 warning

### 已实现默认参数补全

- extension literal default：复用 `CBuiltinBuilder.materializeUtilityDefaultValue(...)`
- GDCC `default_value_func`：
  - 支持实例/静态默认值函数
  - 校验函数参数个数、vararg 禁止、返回类型兼容、receiver 参数兼容
  - default 值通过 temp 物化，调用后逆序销毁

### 已实现动态路径 pack/unpack

- 动态调用统一以 `Variant` 组织 argv
- 非 `Variant` 实参先 pack 到临时 `Variant`
- 动态结果统一按 `Variant` 接收
  - 目标为 `Variant`：直接赋值
  - 目标为非 `Variant`：调用 helper unpack 后写入目标
- pack default temp / dynamic temp 均采用逆序 destroy

## 长期约定（必须保持）

### 1. 分派与失败策略

- 对已知可静态解析且唯一最佳匹配的调用，必须走静态路径。
- 对 `GdObjectType` 且编译期无法静态决议目标函数（含 owner 元数据缺失、方法缺失、对象场景歧义），统一回退 `OBJECT_DYNAMIC`。
- 对“参数类型明确不兼容”等可确定非法调用，必须编译期 fail-fast，不允许动态兜底掩盖错误。

### 2. 重载选择规则（确定性）

- 先过滤：只保留参数数量可接受且类型可赋值的候选。
- 再按 owner 距离优先：仅保留最近 owner 分组。
- 同组内实例优先（仅 static 可用时允许 static）。
- 同组内 non-vararg 优先于 vararg。
- 最终若仍并列：
  - `GdObjectType` 场景回退 `OBJECT_DYNAMIC`
  - 非对象动态场景报 `ambiguous overload`

### 3. 生成层职责边界

- `CallMethodInsnGen` 负责 IR 语义校验与调用路由。
- 生命周期、参数渲染、argv/argc 组装、销毁语义统一交给 `CBodyBuilder`。
- 禁止在生成器中手工拼接 vararg `argv/argc` 文本。
- 禁止在生成器中复制 own/release 或指针转换规则。

### 4. 指针转换安全约束

- 当 receiver 是 GDCC wrapper，但目标 owner 为 ENGINE 时，必须先做 GDCC -> Godot raw ptr 转换，再按需要 cast 到 `godot_<Owner>*`。
- 禁止仅通过 C cast 把 GDCC wrapper 指针伪装成 engine 指针。
- `CBodyBuilder.renderArgument(...)` 的 ptr kind/type fail-fast 防线属于不可回退约束。

### 5. `VARIANT_DYNAMIC` 诊断定位约束

- `file_name` 优先使用 `LirClassDef.sourceFile`，缺失时回退类名。
- `line_number` 取当前指令前最近的 `LineNumberInsn`。
- 无可用行号时回退 `insnIndex`，并将 `file_name` 标记为 `(assemble)`。
- `file_name` 必须以 C 字面量传递给 `godot_Variant_call`，不复用对象指针占位类型。

### 6. 类型规范化约束（resolver 内局部实现）

- `enum::...` / `bitfield::...` 规范化为 `int`
- `typedarray::Packed*Array` 规范化为对应 `GdPacked*ArrayType`
- 非 packed 的 `typedarray::T` 规范化为 `GdArrayType(T)`
- 无法识别类型应 fail-fast，避免静默降级

## 回归测试基线

- `src/test/java/dev/superice/gdcc/backend/c/gen/CallMethodInsnGenTest.java`
  - 静态分派（GDCC/ENGINE/BUILTIN）
  - 默认参数补全（literal + `default_value_func`）
  - 动态分派（OBJECT/VARIANT）及 pack/unpack
  - 重载决议与边界失败路径
  - GDCC receiver -> ENGINE owner 指针转换回归
- `src/test/java/dev/superice/gdcc/backend/c/gen/CallMethodInsnGenEngineTest.java`
  - 真实运行覆盖与生成文本断言（含 vararg、跨 GDCC 调用、父类类型变量触发动态回退）
- `src/test/java/dev/superice/gdcc/backend/c/gen/CBodyBuilderPhaseCTest.java`
  - cast / renderArgument 防御语义与 fail-fast 约束

建议命令：

```bash
./gradlew test --tests CallMethodInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CallMethodInsnGenEngineTest --no-daemon --info --console=plain
./gradlew test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

## 工程反思（保留长期价值）

1. 动态回退策略提升兼容性，但会降低一部分编译期拼写/接口错误可发现性；后续若收紧策略，必须先明确“何时仍允许动态回退”的边界。
2. 默认参数补全必须坚持“temp 物化 -> 参与调用 -> 逆序销毁”，避免 destroyable 默认值泄漏。
3. 指针表示转换必须单点化，禁止在生成器里以字符串拼接方式临时修补，避免出现运行期 UB。
4. 文档应只保留当前事实与长期约束；阶段推进记录应留在提交历史，不应长期污染实现文档。

## 非目标（当前不做）

- `CALL_SUPER_METHOD` / `CALL_STATIC_METHOD` / `CALL_INTRINSIC` 的完整实现
- 对全局类型解析系统做大范围重构
- 放宽 fail-fast 到“尽量生成”策略
