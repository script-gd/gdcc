# CALL_METHOD 实现方案（C Backend）

## 文档状态

- 状态：implemented/maintained（Phase 1 / Phase 1.1 / Phase 2 / Phase 3 / Phase 4 已落地）
- 目标模块：`backend.c` / `CALL_METHOD`
- 关联实现基线：
  - `doc/module_impl/call_global_implementation.md`
  - `doc/module_impl/load_static_implementation.md`
  - `doc/module_impl/cbodybuilder_implementation.md`
- 更新时间：2026-02-27

---

## 实施进度同步（2026-02-26）

- Phase 1 已完成并提交代码实现：
  - 已注册 `CallMethodInsnGen` 到 `CCodegen` 指令分发表。
  - 新增 `CallMethodInsnGen`，落地 `GDCC/ENGINE/BUILTIN` 静态分派路径。
  - 新增 `MethodCallResolver`，统一接收者分类、方法元数据查找，并在对象静态不可决议时回退 `OBJECT_DYNAMIC`。
  - 已落地 Phase 1 约束：结果契约校验、参数变量存在性校验、固定参数/vararg 类型校验、静态方法调用允许并输出 warning。
- 已识别需在下一步修复的实现偏差（详见 4.6/4.7）：
  - owner 在父类链上切换时，调用符号分派模式必须跟随 `owner`，不能固化为 receiver 初始分支。
  - 重载选择规则需显式定义“最近 owner + 非 vararg 优先 + 唯一最佳匹配”，避免误判 `ambiguous`。
- Phase 2 已完成并提交工作区实现（待合并）：
  - 已实现默认参数补全：
    - extension literal default（复用 `CBuiltinBuilder.materializeUtilityDefaultValue`）
    - GDCC `default_value_func`（含实例/静态 default 函数契约校验与 fail-fast）
  - 已补齐 vararg 契约：extra 参数必须可赋值给 `Variant`。
  - 已完成方法签名类型规范化：
    - `enum::` / `bitfield::` -> `int`
    - `typedarray::Packed*Array` -> `GdPacked*ArrayType`
    - 非 packed `typedarray::T` -> `GdArrayType(T)`
  - 已补齐 `CallMethodInsnGenTest` Phase 2 覆盖：
    - extension literal default
    - GDCC `default_value_func`（实例/静态）
    - vararg 类型失败路径
    - `typedarray::` packed/non-packed 参数与返回值规范化路径
- Phase 3 已完成并提交工作区实现（待合并）：
  - 已实现 `OBJECT_DYNAMIC`：
    - 对编译期无法静态决议的 `GdObjectType` receiver 生成 `godot_Object_call`
    - 非 `Variant` 实参自动 pack 为 `Variant`，调用后逆序销毁 pack 临时变量
    - 返回值统一按 `Variant` 接收；当目标非 `Variant` 时自动 unpack 到目标类型
  - 已实现 `VARIANT_DYNAMIC`：
    - 对 `Variant` receiver 生成 `godot_Variant_call`
    - 方法参数统一按 `Variant` argv 组织；非 `Variant` 实参自动 pack
    - 支持 `Variant` 直接结果与非 `Variant` 目标 unpack 路径
    - 已移除 `CallMethodInsnGen` 中手工拼接 `argv/argc` 字符串，统一改为 `CBodyBuilder.callAssign(..., args, varargs)` 发射 vararg
  - 已补齐 `CallMethodInsnGenTest` Phase 3 覆盖：
    - `OBJECT_DYNAMIC` pack + unpack 成功路径
    - `VARIANT_DYNAMIC` 直写 `Variant` 结果路径
    - `VARIANT_DYNAMIC` unpack 到非 `Variant` 结果路径
    - 动态路径 `resultId` 为 ref 的失败路径
- Phase 4 已完成并提交工作区实现（待合并）：
  - 已新增 `CallMethodInsnGenEngineTest`，覆盖真实 Godot 运行与代码生成断言下的七类路径（四类基础路径 + 三个专项引擎测试）：
    - builtin 调用
    - engine 调用
    - `VARIANT_DYNAMIC`（`godot_Variant_call`，真实运行断言）
    - `OBJECT_DYNAMIC`（`godot_Object_call`，`entry.c` 生成断言）
    - 引擎 vararg 调用专项测试：`callMethodEngineVarargShouldRunInRealGodot`
      （覆盖 `Node.call` 的 vararg `argv/argc` 生成与真实运行）
    - 跨 GDCC 类互调专项测试：`callMethodBetweenDifferentGdccClassesShouldRunInRealGodot`
      （覆盖两个不同 GDCC 类之间静态分派，不得回退 `godot_Object_call` / `godot_Variant_call`）
    - 父类类型变量触发动态分派专项测试：`callMethodParentTypedGdccReceiverShouldUseObjectDynamicAndRunInRealGodot`
      （覆盖“父类类型接收子类实例 + 调用仅子类方法”场景，验证 `godot_Object_call` 与 GDCC 指针转换）
  - 已修复模板侧隐藏函数绑定语义：
    - `entry.c.ftl` 仅为非 hidden / 非 lambda 函数发射 `gdcc_bind_method*` 绑定代码
    - hidden 函数仍生成函数体，可用于内部/回归代码生成验证
  - 已补强 `CallMethodInsnGenTest` 回归边界断言：
    - GDCC 静态互调场景显式断言不得回退到 `godot_Object_call` / `godot_Variant_call`
    - 动态场景增加互斥断言，防止对象动态与 Variant 动态路径串线
    - GDCC 对象实参在动态路径中必须使用 `godot_new_Variant_with_gdcc_Object(...)` 打包，且接收者保持 `godot_object_from_gdcc_object_ptr(...)` 转换
  - 维护更新：调整 `GdObjectType` 动态分派规则：
    - 当编译期无法确定目标函数（如方法缺失、owner 元数据缺失、重载歧义）时，
      无论类型是否出现在注册表，统一回退 `OBJECT_DYNAMIC` 并生成 `godot_Object_call`
    - 参数类型明确不兼容等“可确定为非法调用”的场景仍保持编译期报错
  - 维护更新：合并 `MethodCallResolver` 内部重载选择逻辑：
    - 合并 `chooseBestCandidate` 与 `chooseBestObjectCandidate`，消除重复实现
    - 保留对象动态回退语义与注释：对象歧义回退 `OBJECT_DYNAMIC`，其余场景保持编译期歧义错误
  - 本文档阶段状态已收敛为 `implemented/maintained`。

---

## 维护审计补充（2026-02-27）

> 本节用于把“已确认的问题”与“可执行的解决方案”固化到文档，避免实现与设计目标在维护期发生漂移。

### 审计范围

- 代码：`CallMethodInsnGen` / `MethodCallResolver` / `CBodyBuilder` / `CGenHelper`
- 模板与运行时辅助：`entry.c.ftl` / `gdcc_helper.h`
- 测试：`CallMethodInsnGenTest` / `CallMethodInsnGenEngineTest`

### 审计结论（摘要）

1. Phase 1~4 的主体目标已落地且整体覆盖良好：静态分派（GDCC/ENGINE/BUILTIN）、默认参数（literal + `default_value_func`）、
   typedarray/enum/bitfield 类型规范化、OBJECT/VARIANT 动态分派 pack/unpack、模板侧 hidden/lambda bind 语义均已实现并有回归。
2. 审计中识别的 **P0 功能 bug（GDCC receiver -> ENGINE owner 指针转换错误）已修复**：
   `CBodyBuilder#valueOfCastedVar` 已改为“先做指针表示转换，再做目标类型 cast”，并在 `renderArgument` 增加 ptr kind/type 一致性 fail-fast 防线，
   避免将 GDCC wrapper 指针伪装为 `godot_<Owner>*` 传入。
3. `VARIANT_DYNAMIC` 的 `file_name/line_number` 已完成定位语义增强：
   - `file_name` 使用真实 `LirClassDef.sourceFile`（缺失时回退类名）
   - `line_number` 使用当前指令前最近的 `LineNumberInsn`
   - 若不存在可用 `LineNumberInsn`，回退 `insnIndex` 且 `file_name` 添加 `(assemble)` 标记
4. 维护策略取舍需显式记录：对 “已知 `GdObjectType` 但静态不可决议的方法调用” 统一回退 `OBJECT_DYNAMIC` 可以提升动态兼容性，
   但会降低拼写/接口错误的编译期可发现性；后续若引入更严格的 fail-fast 策略，需要在文档中明确适用条件与边界。
5. 测试稳定性提醒：静态方法 warning 当前由 `CallMethodInsnGen` 使用 SLF4J 输出，若单测通过 `System.out` 捕获文本做断言，
   依赖 logging backend 的输出流配置，存在维护期脆弱性；建议后续改为可控的 appender 捕获或 builder 级诊断事件。

### 审计后修复同步（2026-02-27）

- 已落地 `CBodyBuilder#valueOfCastedVar` 安全实现：
  - Object cast 路径统一先做 `GDCC_PTR <-> GODOT_PTR` 表示转换，再生成目标类型表达式。
  - 禁止 object/non-object 混合 cast，直接 fail-fast。
- 已增强 `CBodyBuilder#renderArgument` 防御：
  - 对 `requireGodotRawPtr` 且 `ptrKind=GDCC_PTR` 的参数，强制要求类型为 GDCC object；不一致时 fail-fast 报错，防止静默生成 UB 代码。
- 已补齐回归测试：
  - `CBodyBuilderPhaseCTest` 增加 cast+参数渲染语义回归（含 fail-fast 负例），用于锁死 `renderArgument` 行为。
  - `CallMethodInsnGenTest` 增加 `GDMyNode extends Node` 的 `self.queue_free` 断言，锁死 `godot_object_from_gdcc_object_ptr($self)` 转换。
  - `CallMethodInsnGenEngineTest` 增加引擎集成测试，验证 GDCC receiver 命中 ENGINE owner 的真实运行行为与生成文本。
- 已更新 `VARIANT_DYNAMIC` 调试位置信息生成：
  - `file_name` 使用真实 `sourceFile`，并通过 C 字面量传入 `godot_Variant_call`。
  - `line_number` 使用当前指令前最近 `LineNumberInsn`，无可用行号时回退 `insnIndex` 并将 `file_name` 标记为 `(assemble)`。

## 1. 调研结论（现状）

### 1.1 IR 与语义现状

- Low IR 已定义 `call_method "<method_name>" $<object_id> ...`（`doc/gdcc_low_ir.md`）。
- `ParsedLirInstruction` 已可解析成 `CallMethodInsn`。
- `GdInstruction.CALL_METHOD` 元数据已完整（返回可选、最少 2 操作数）。

### 1.2 Backend 现状

- `CCodegen` 已注册 `CALL_METHOD` 对应 `CInsnGen`（`CallMethodInsnGen`）。
- 已有可复用调用基础设施：
  - `CBodyBuilder.callVoid` / `callAssign`（含 vararg 发射、discard 清理、对象所有权写槽语义）。
  - `CBodyBuilder.renderArgument` 自动处理：
    - `godot_*` 调用中的 GDCC 指针 -> Godot raw ptr 转换。
    - 值语义类型自动 `&` 传参。
  - `CallGlobalInsnGen` 已实现完整的调用校验、默认参数补全、vararg 契约、结果校验。
  - `PropertyAccessResolver` 已验证 `GDCC/ENGINE/GENERAL/BUILTIN` 分流模式在属性访问场景可行。
  - `CBuiltinBuilder.materializeUtilityDefaultValue` 已具备默认字面量物化能力（构造字面量、typed container、String/StringName/NodePath/null 等）。

### 1.3 外部 API 与命名现状

- `gdextension-lite` 方法命名规范：`godot_<Type>_<method>`。
- vararg 方法统一参数尾部：`const godot_Variant** argv, godot_int argc`。
- 现有 include 基线可确认：
  - `godot_Vector3_rotated(const godot_Vector3 *self, ...)`
  - `godot_Object_call(godot_Object *self, const godot_StringName *method, const godot_Variant **argv, godot_int argc)`
  - `godot_Callable_call(const godot_Callable *self, const godot_Variant **argv, godot_int argc)`
- 新增约束：当接收者是 `Variant` 且无法静态确定调用目标时，回退到你手动添加的 `godot_Variant_call` 动态分派路径。

---

## 2. 设计目标与边界

### 2.1 目标

实现 `CALL_METHOD` 的 C 代码生成，满足以下约束：

1. **优先复用现有架构**（`CBodyBuilder` / `CBuiltinBuilder` / 现有校验模式），避免重复生命周期与指针转换逻辑。
2. 与 `CALL_GLOBAL` 一致：
   - 参数数量/类型校验
   - 默认参数补全
   - vararg 契约
   - 结果变量契约与 discard 行为
3. 支持不同接收者类型：
   - GDCC object
   - engine object
   - builtin 值类型（如 `Vector3`、`String`、`Array`、`Callable`）
   - `GdObjectType`（编译期无法静态决议目标函数时走动态 `godot_Object_call`）
   - `Variant`（静态不可决议时走动态 `godot_Variant_call`）
4. 对 destroyable 类型与对象返回值保持与 `CBodyBuilder` 统一语义。
5. **已知 GDCC 类型之间的调用必须优先静态绑定**，不得错误回退到动态分派或直接报错。

### 2.2 非目标（本方案阶段内不做）

- `CALL_SUPER_METHOD` / `CALL_STATIC_METHOD` / `CALL_INTRINSIC` 的完整落地（但方案会预留可复用结构）。
- 大范围重构全局类型解析系统（只在 `call_method` 路径做最小必要适配）。

---

## 3. 复用优先的总体方案

### 3.1 新增生成器

- 新增：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`
- 在 `CCodegen` 注册：`registerInsnGen(new CallMethodInsnGen());`

### 3.2 新增解析器（建议）

- 新增：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/MethodCallResolver.java`
- 角色定位与 `PropertyAccessResolver` 对齐：
  - 只负责“接收者分类 + 方法元数据定位 + 调用符号解析”。
  - 不做 C 代码发射。

建议分流模式：

- `GDCC`：接收者为已知 GDCC 类（含父类链方法查找）。
- `ENGINE`：接收者为已知 engine 类（含父类链方法查找）。
- `BUILTIN`：接收者为 builtin 类型（如 Vector/String/Array/...）。
- `OBJECT_DYNAMIC`：接收者是 `GdObjectType` 且编译期无法静态决议目标函数时，走 `godot_Object_call`。
- `VARIANT_DYNAMIC`：接收者是 `GdVariantType` 且无法编译期静态决议，走 `godot_Variant_call`。

---

## 4. 核心算法（CallMethodInsnGen）

以下流程严格参考 `CallGlobalInsnGen` 的结构化写法。

### 4.1 前置校验

1. 读取 `CallMethodInsn` 当前指令。
2. 校验接收者变量存在；`void/nil` 直接拒绝。
3. 校验参数操作数均为变量操作数（与 `CALL_GLOBAL` 一致）。
4. 解析分派模式（`GDCC/ENGINE/BUILTIN/OBJECT_DYNAMIC/VARIANT_DYNAMIC`）。
5. 明确约束：
   - 接收者类型可静态解析且匹配唯一最佳签名时，走静态分派。
   - 接收者为 `GdObjectType` 但编译期无法确定目标函数时，回退 `OBJECT_DYNAMIC`。
   - 参数类型明确不兼容等可判定非法调用场景，仍在编译期报错。

### 4.2 已知签名路径（GDCC/ENGINE/BUILTIN）

统一流程：

1. 解析到 `ResolvedMethodCall`：
   - `dispatchMode`
   - `ownerType`（声明该方法的类型）
   - `cFunctionName`
   - `signature`
2. `isStatic=true` 时允许生成静态调用，并输出 warning（提示应优先使用 `call_static_method` / 显式静态调用语义）。
3. 固定参数校验：
   - provided > fixed 且非 vararg -> 抛错。
   - provided < fixed 时补默认值：
     - extension 方法：默认值字面量（复用 `CBuiltinBuilder` 物化到 temp）。
     - GDCC 方法：`default_value_func` 路径（见 4.5）。
4. vararg 校验：extra 参数必须可赋值给 `Variant`。
5. 结果契约校验：
   - void + resultId 非空 -> 抛错。
   - non-void + resultId 为空 -> 允许 discard。
   - non-void + resultId 非空 -> result 变量存在、非 ref、类型可赋值。
6. 通过 `bodyBuilder.callVoid/callAssign` 发射。
7. 逆序销毁 default 临时变量。

### 4.3 `OBJECT_DYNAMIC` 路径（GdObjectType unresolved）

`OBJECT_DYNAMIC` 用于：接收者是 `GdObjectType`，且编译期无法静态确定目标函数。

目标语义：使用 `godot_Object_call` + Variant 参数打包/解包。

步骤：

1. receiver 作为固定第一个参数。
2. 方法名作为固定第二个参数：`valueOfStringNamePtrLiteral(methodName)`。
3. 实参统一转 `Variant`（已是 `Variant` 可直传，否则 pack 到 `TempVar(Variant)`）。
4. 调用：
   - `callAssign(discardRef, "godot_Object_call", Variant, fixedArgs, varargs)`（无结果）
   - 或写入 `variantResultTemp` 后 `unpack` 到目标类型（有结果且目标非 Variant）。
5. 逆序 destroy 所有临时变量。

### 4.4 `VARIANT_DYNAMIC` 路径（Variant receiver）

当接收者为 `Variant` 且方法调用无法编译期静态决议时，回退 `godot_Variant_call`。

建议策略：

1. receiver 固定参数为 `Variant` 自身。
2. 方法名传 `StringName` 指针字面量。
3. 实参转为 `Variant` argv（已是 `Variant` 可直传）。
4. 调用 `godot_Variant_call` 获取 `Variant` 结果，再按目标类型 unpack（或直接丢弃）。
5. 若 `godot_Variant_call` 提供 `CallError` 输出，应在生成层保留 fail-fast 钩子（至少可扩展，不吞错）。

#### 4.4.1 `file_name` / `line_number` 参数定位规则（维护更新，已落地）

`godot_Variant_call` 的签名包含 `file_name` 与 `line_number`，用于在运行期错误发生时提供可读诊断。

- 当前实现规则：
  1. `file_name` 优先使用 `LirClassDef.sourceFile`；若缺失则回退 `clazz.name`。
  2. `line_number` 取“当前指令之前最近的 `LineNumberInsn`”的行号。
  3. 若不存在可用 `LineNumberInsn`，则回退 `line_number = insnIndex`，并把 `file_name` 组装为 `<name>(assemble)` 以标记汇编回退来源。
  4. `file_name` 以 C `const char*` 字面量传入，避免复用对象指针占位类型。
  5. 生成层保持 `gdcc_helper.h` 的 fail-fast 报错语义，不吞错。

### 4.5 GDCC `default_value_func` 补参策略

GDCC 方法默认参数不是字面量，而是函数引用（`default_value_func`）。

建议实现：

1. 方法签名解析阶段记录 `DefaultArgKind`：`NONE` / `LITERAL` / `FUNCTION`。
2. 对 `FUNCTION`：
   - 创建参数类型 temp。
   - 调用 default 函数计算值并写入 temp。
3. default 函数调用规则：
   - 函数名解析为 `<ownerClass>_<defaultFuncName>`。
   - 若 default 函数是实例函数，传当前 receiver；若 static，无 self。
   - 返回类型必须可赋值给参数类型。
4. temp 参与正常调用后逆序 destroy。

### 4.6 Phase 1 错误修复清单（新增）

> 本节用于明确“已落地实现”中的修复项，优先级高于新功能扩展。

1. **owner 分派模式修复（P0）**
   - 问题：当前分派模式由 receiver 初始类型决定，若最终命中的是父类（尤其 engine 父类）方法，可能生成错误调用符号。
   - 规则：`ResolvedMethodCall.mode` 必须基于“方法实际 owner”判定，而不是 receiver 入口分支。
   - 预期：当 owner 为 engine 类时，必须生成 `godot_<Owner>_<method>`，并沿用 `CBodyBuilder` 的 Godot raw ptr 参数转换语义。

2. **已知类型元数据缺失回退动态（P0）**
   - 规则：receiver 为 `GdObjectType` 且编译期无法静态确定目标函数时（包括 `ClassDef` 缺失），统一回退 `OBJECT_DYNAMIC`。
   - 约束：仅对“无法确定目标函数”回退；参数类型明确不兼容等非法调用仍保持 fail-fast。

3. **typed 容器接收者归一化（P1）**
   - 问题：`Array[T]` / `Dictionary[K,V]` 作为 receiver 时，builtin 查找键可能与 API 元数据键（`Array` / `Dictionary`）不一致。
   - 规则：resolver 在查找 builtin class 前先做 receiver 名称归一化，不改变最终参数/返回类型语义。

4. **GDCC receiver 命中 ENGINE owner 时 receiver 指针转换 bug（P0，审计新增）**
   - 现象：当 receiver 是 GDCC wrapper（例如 `GDMyNode* self`），但解析到的方法 owner 是 ENGINE 类（例如 `Node.queue_free`），
     若通过“类型 cast”把 `$self` 直接转成 `godot_Node*`，会绕过 `CBodyBuilder.renderArgument` 的 GDCC→Godot raw ptr 自动转换，
     最终把 wrapper 指针当作 engine 对象指针传入 `godot_Node_*` API，存在未定义行为/崩溃风险。
   - 根因（实现层面）：
     - `CallMethodInsnGen.renderReceiverValue(...)` 在 `ownerType != receiverType` 时使用“表达式强制 cast”，该 cast **不等价于**“取 `_object` 再转换”的语义。
     - `CBodyBuilder.renderArgument(...)` 仅在参数被识别为 GDCC_PTR 且调用需要 raw ptr 时才插入 `godot_object_from_gdcc_object_ptr(...)`。
       若 receiver 在上一步被 cast 成 ENGINE 类型，其 `PtrKind` 可能变为 `GODOT_PTR`，转换逻辑被跳过。
   - 修复规则（必须满足）：
     1. 当 `resolved.mode == ENGINE` 且 receiver 的静态类型是 GDCC 类型（`checkGdccType == true`）时，receiver 必须先做 GDCC→Godot raw ptr 转换，
        再按需要 cast 到具体 `godot_<Owner>*`：
        - 推荐生成：`(godot_<Owner>*)godot_object_from_gdcc_object_ptr($receiver)`。
     2. 不允许通过单纯的 C cast 把 GDCC wrapper 指针伪装成 engine 指针。
  - 状态：**已修复（2026-02-27）**。
  - 已新增测试（强制回归）：
    - `CallMethodInsnGenTest` 已新增 `GDMyNode extends Node` + `call_method "queue_free" self` 用例，
      断言生成 `godot_Node_queue_free((godot_Node*)godot_object_from_gdcc_object_ptr($self))`，并禁止纯 C cast 路径。
    - `CallMethodInsnGenEngineTest` 已新增真实运行覆盖（若 Zig 可用），锁死运行期行为与代码生成语义。

### 4.7 重载选择规则澄清（新增）

为避免当前与后续实现的行为漂移，`MethodCallResolver.chooseBestCandidate` 必须遵循以下确定性规则：

1. **过滤阶段**
   - 仅保留“参数数量可接受（含 vararg）且类型可赋值”的候选。
   - 空集时：进入“无匹配诊断”，报告最接近候选的失败原因（参数个数或首个不兼容参数）。

2. **owner 距离优先**
   - 候选按 owner 到 receiver 的继承距离升序排序（距离越小优先）。
   - 仅保留最小距离组；父类候选不能与子类候选并列竞争。

3. **实例方法优先（但不禁止静态）**
   - 在同一距离组中，优先非 static 候选。
   - 若仅 static 命中，允许生成静态调用；代码生成阶段输出 warning，不阻断编译。

4. **非 vararg 优先**
   - 在同一距离与 static 维度下，优先固定参数（non-vararg）候选。
   - 仅当 non-vararg 不可用时才选择 vararg 候选。

5. **唯一最佳匹配**
   - 若最终仍有多个候选并列：
   - `GdObjectType` 场景回退 `OBJECT_DYNAMIC`
   - 非对象动态场景抛出 `ambiguous overload`。
   - 错误信息需包含：receiver 类型、方法名、候选 owner 列表、各候选签名。

### 4.8 Phase 3 代码生成规则补充（新增）

1. **禁止手工拼接 vararg 调用字符串**
   - `CallMethodInsnGen` 不再直接拼接 `const godot_Variant* argv[] = {...}` 与 `argc` 文本。
   - 所有 vararg 调用必须通过 `CBodyBuilder.callAssign(@NotNull TargetRef target, @NotNull String funcName, @NotNull GdType returnType, @NotNull List<ValueRef> args, @Nullable List<ValueRef> varargs)` 或 `callVoid(..., args, varargs)` 统一发射。

2. **`VARIANT_DYNAMIC` 的调用约定**
   - 固定参数顺序：`receiver`, `method`, `file_name`, `line_number`。
   - 动态实参列表作为 `varargs` 传入，由 `CBodyBuilder` 负责生成 `argv/argc`。
   - `godot_Variant_call` 原函数参数顺序已调整为“固定参数在前，`argv/argc` 在尾部”，与 `CBodyBuilder` vararg 发射约定一致。

---

## 5. 不同值类型的生成策略（重点）

`call_method` 对“接收者/参数/返回值类型”的处理约定如下：

- receiver = GDCC object（已知）：走 `GDCC` 静态分派；找不到方法直接报错。
- receiver = engine object（已知）：走 `ENGINE` 静态分派；找不到方法直接报错。
- receiver = builtin 值语义类型：走 `BUILTIN`；非 ref 变量参数自动 `&`。
- receiver = `GdObjectType` 且编译期无法静态决议目标函数：走 `OBJECT_DYNAMIC`，调用 `godot_Object_call`。
- receiver = `Variant`：静态不可决议时走 `VARIANT_DYNAMIC`，调用 `godot_Variant_call`。
- receiver = `void` / `nil`：fail-fast。
- 参数 = primitive（`bool/int/float`）：direct 模式直接传值；动态模式先 pack `Variant`。
- 参数 = destroyable 非对象（`String/Array/Dictionary/Variant` 等）：direct 模式按现有参数规则；动态模式转 `Variant`（已有 `Variant` 可直传）。
- 参数 = object：direct 模式按签名传递；动态模式 pack 为 `Variant`。
- 返回 = `void`：必须无 `resultId`，走 `callVoid`。
- 返回 = non-void primitive/值语义：`callAssign`；无 `resultId` 时可 discard，Builder 负责 destroyable discard 清理。
- 返回 = object：`callAssign`；Builder 负责 OWNED 写槽、ptr 转换、old release。
- 返回 = `Variant`：可直接写结果；动态路径原生返回 `Variant`。

---

## 6. 元数据与类型解析风险（必须显式处理）

`extension_api_451.json` 的方法签名里大量使用：

- `enum::...`
- `bitfield::...`
- `typedarray::...`

当前 `ClassRegistry.tryParseTextType` 直接复用会导致部分类型误判。

建议在 `MethodCallResolver` 内部增加 **局部规范化**（不影响其他模块）作为 v1 必需防护：

1. `enum::X` / `bitfield::X` -> `GdIntType.INT`
2. `typedarray::T` -> 分支处理：
   - `T` 为 `Packed*Array`：直接映射到对应 `GdPacked*ArrayType`，**不要包装成 `GdArrayType`**。
   - 其他 `T`：`new GdArrayType(parse(T))`
3. 其余无法识别类型：优先 fail-fast（报清晰错误），避免静默降级掩盖问题。

注：`typedarray::Packed*Array` 的特殊处理是本次方案的硬约束，否则会生成错误的类型校验与调用路径。

---

## 7. 代码结构建议（最小侵入）

### 7.1 `CallMethodInsnGen` 建议私有方法

- `resolveReceiverVar(...)`
- `resolveArgumentVariables(...)`
- `resolveResultTarget(...)`
- `validateFixedArgsAndCompleteDefaults(...)`
- `validateVarargs(...)`
- `emitKnownSignatureCall(...)`
- `emitObjectDynamicCall(...)`
- `emitVariantDynamicCall(...)`

### 7.2 `MethodCallResolver` 建议结构

```text
MethodDispatchMode { GDCC, ENGINE, BUILTIN, OBJECT_DYNAMIC, VARIANT_DYNAMIC }
ResolvedMethodCall {
  mode,
  ownerType,
  receiverType,
  cFunctionName,
  signature,
  supportsDefaults,
  supportsVararg
}
MethodParamSpec {
  name,
  type,
  defaultKind(NONE/LITERAL/FUNCTION),
  defaultLiteral,
  defaultFunctionName
}
MethodSignatureSpec {
  returnType,
  params,
  isVararg,
  isStatic
}
```

---

## 8. 测试计划（按现有项目风格）

### 8.1 单元测试（首批）

新增：`src/test/java/dev/superice/gdcc/backend/c/gen/CallMethodInsnGenTest.java`

覆盖矩阵：

1. `BUILTIN` 成功路径：
   - `Vector3.rotated`（返回 builtin）
   - `String.substr`（默认参数补齐）
2. `ENGINE` 成功路径：
   - `Node.queue_free`（void）
   - `Node.call_deferred_thread_group`（vararg + Variant 返回）
   - 静态方法通过实例 `call_method` 调用（应成功生成并输出 warning）
3. `GDCC` 成功路径：
   - 同类实例方法调用
   - 继承链方法调用（验证 owner 符号与 receiver 传递）
   - GDCC 子类调用 engine 父类方法（验证生成 `godot_<Owner>_<method>` 而非 `<Owner>_<method>`）
     - **审计新增回归断言**：当 receiver 是 GDCC wrapper 时，必须生成 `godot_object_from_gdcc_object_ptr(...)` 转换（必要时 cast 到 `godot_<Owner>*`），禁止仅用 C cast。
   - **两个 GDCC 类型互调时必须生成静态调用符号，不得回退动态路径**
   - `default_value_func` 补参（函数默认值）
4. 动态路径成功：
   - `GdObjectType` 无法静态决议 + 非 Variant 参数（验证 `godot_Object_call` + pack）
   - GDCC 父类类型变量调用子类独有方法（验证 `godot_Object_call(godot_object_from_gdcc_object_ptr(...), ...)`）
   - GDCC 对象实参动态打包（验证 `godot_new_Variant_with_gdcc_Object(...)`，且不得退化为手工 `godot_new_Variant_with_Object(godot_object_from_gdcc_object_ptr(...))`）
   - Variant receiver + 未知方法（验证回退 `godot_Variant_call`）
5. 失败路径：
   - 接收者不存在 / result 不存在 / result 为 ref
   - 参数过多/过少且不可补
   - 参数类型不兼容
   - void 方法却给 resultId
   - **参数类型明确不兼容等可判定非法调用 -> 必须编译期报错**
6. 类型规范化回归：
   - `typedarray::PackedByteArray` / `typedarray::PackedVector3Array` 解析结果应为 `GdPacked*ArrayType`
   - 非 packed 的 `typedarray::StringName` 仍应解析为 `GdArrayType(StringName)`
7. 重载决议回归：
   - 子类/父类同名同参：必须选择最近 owner
   - 同名 fixed + vararg：fixed 优先
   - 同名多个 equally-specific 候选：`GdObjectType` 场景回退 `OBJECT_DYNAMIC`

### 8.2 集成测试（已落地）

新增：`CallMethodInsnGenEngineTest.java`

目标：完成七类路径的集成级验证（真实运行 + 生成断言）：

1. builtin 方法（`Vector3.rotated`）
2. engine 方法（`Node.call_thread_safe` 或 `Object.call`）
3. `GdObjectType` unresolved 动态路径（`godot_Object_call`，`entry.c` 断言）
4. Variant 动态路径（`godot_Variant_call`，真实运行）
5. 引擎 vararg 调用路径（`callMethodEngineVarargShouldRunInRealGodot`）：
   通过 `Node.call` 的 `vararg` 入口验证 `argv/argc` 发射与真实运行结果一致
6. 跨 GDCC 类互调路径（`callMethodBetweenDifferentGdccClassesShouldRunInRealGodot`）：
   验证 `GDGdccCrossCallNode -> GDPeerWorker` 的静态调用符号生成且不回退动态分派
7. 父类类型变量触发 GDCC 动态分派路径（`callMethodParentTypedGdccReceiverShouldUseObjectDynamicAndRunInRealGodot`）：
   验证 `GDBaseDynamicWorker` 类型变量接收 `GDChildDynamicWorker` 实例并调用子类独有方法时，
   编译器发射 `godot_Object_call`，且生成 `godot_object_from_gdcc_object_ptr(...)` 指针转换

### 8.3 回归断言重点

对关键路径增加字符串级断言，避免语义回退：

- GDCC 互调场景：
  - 必须包含 `<OwnerClass>_<method>(...)`
  - 不得出现 `godot_Object_call(`
  - 不得出现 `godot_Variant_call(`
- Variant 动态场景：
  - 必须出现 `godot_Variant_call(`
- `GdObjectType` unresolved 动态场景：
  - 必须出现 `godot_Object_call(`

### 8.4 建议命令

```bash
./gradlew test --tests CallMethodInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CallMethodInsnGenEngineTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

---

## 9. 分阶段实施顺序（建议）

### Phase 1（最小可用）

- [x] 注册 `CallMethodInsnGen`
- [x] 实现 `BUILTIN/ENGINE/GDCC` 静态调用（不含默认补参）
- [x] 完成结果契约 + 参数变量存在性 + 类型校验
- [x] 落地对象接收者静态分派主路径

### Phase 1.1（错误修复与规则收敛）

- [x] 修复 owner 分派模式：按方法实际 owner 选择 `GDCC/ENGINE` 调用符号
- [x] 明确并落地重载选择规则（最近 owner / 实例优先 / non-vararg 优先 / 唯一最佳）
- [x] 补齐对应单测与回归断言
- [x] 维护更新：当 `GdObjectType` 编译期无法静态决议目标函数时，允许回退 `OBJECT_DYNAMIC`

### Phase 2（语义补齐）

- [x] 默认参数补全：
  - extension literal default（复用 `CBuiltinBuilder`）
  - GDCC `default_value_func`
- [x] vararg 契约补齐
- [x] 完成 `typedarray::` 局部类型规范化（含 Packed*Array 特殊分支）

### Phase 3（动态兼容）

- [x] `OBJECT_DYNAMIC`：`godot_Object_call` + pack/unpack
- [x] `VARIANT_DYNAMIC`：`godot_Variant_call` + pack/unpack

### Phase 4（质量收敛）

- [x] 单测矩阵补齐 + 引擎集成测试
- [x] 对 GDCC 静态互调与动态回退边界加回归断言
- [x] 文档同步（本文件转为 implemented/maintained）

---

## 10. 与现有架构的一致性说明

本方案严格遵循项目现有“生成器只做 IR 校验和路由，Builder 负责通用生命周期/指针语义”的原则：

- 不在 `CallMethodInsnGen` 手工写 own/release。
- 不在 `CallMethodInsnGen` 手工写 GDCC/Godot 指针转换。
- 不在 `CallMethodInsnGen` 重复实现值语义 destroy 规则。

因此该实现与 `CALL_GLOBAL`、`LOAD_PROPERTY/STORE_PROPERTY` 的长期维护方向一致，可最小化后续语义漂移风险。
