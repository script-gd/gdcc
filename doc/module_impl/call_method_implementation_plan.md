# CALL_METHOD 实现方案（C Backend）

## 文档状态

- 状态：Proposal（待实施）
- 目标模块：`backend.c` / `CALL_METHOD`
- 关联实现基线：
  - `doc/module_impl/call_global_implementation.md`
  - `doc/module_impl/load_static_implementation.md`
  - `doc/module_impl/cbodybuilder_implementation.md`
- 更新时间：2026-02-26

---

## 1. 调研结论（现状）

### 1.1 IR 与语义现状

- Low IR 已定义 `call_method "<method_name>" $<object_id> ...`（`doc/gdcc_low_ir.md`）。
- `ParsedLirInstruction` 已可解析成 `CallMethodInsn`。
- `GdInstruction.CALL_METHOD` 元数据已完整（返回可选、最少 2 操作数）。

### 1.2 Backend 现状

- `CCodegen` 当前 **未注册** `CALL_METHOD` 对应的 `CInsnGen`，遇到该指令会在 `generateFuncBody` 抛 `UnsupportedOperationException`。
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
   - unknown object（仅在对象类型未知时走动态 `godot_Object_call`）
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
- `OBJECT_DYNAMIC`：接收者是 `GdObjectType` 但类型未出现在注册表，走 `godot_Object_call`。
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
   - 接收者类型可确定（GDCC/ENGINE/BUILTIN）但找不到方法时，**直接报错**。
   - 仅在接收者类型本身未知时才走 `OBJECT_DYNAMIC`。

### 4.2 已知签名路径（GDCC/ENGINE/BUILTIN）

统一流程：

1. 解析到 `ResolvedMethodCall`：
   - `dispatchMode`
   - `ownerType`（声明该方法的类型）
   - `cFunctionName`
   - `signature`
2. 校验不可调用静态方法（`isStatic=true`）并 fail-fast。
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

### 4.3 `OBJECT_DYNAMIC` 路径（unknown object）

`OBJECT_DYNAMIC` 仅用于：接收者是 `GdObjectType`，但该对象类型在注册表中找不到类定义。

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

---

## 5. 不同值类型的生成策略（重点）

`call_method` 对“接收者/参数/返回值类型”的处理约定如下：

- receiver = GDCC object（已知）：走 `GDCC` 静态分派；找不到方法直接报错。
- receiver = engine object（已知）：走 `ENGINE` 静态分派；找不到方法直接报错。
- receiver = builtin 值语义类型：走 `BUILTIN`；非 ref 变量参数自动 `&`。
- receiver = unknown object（`GdObjectType` 且注册表未知）：走 `OBJECT_DYNAMIC`，调用 `godot_Object_call`。
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
3. `GDCC` 成功路径：
   - 同类实例方法调用
   - 继承链方法调用（验证 owner 符号与 receiver 传递）
   - **两个 GDCC 类型互调时必须生成静态调用符号，不得回退动态路径**
   - `default_value_func` 补参（函数默认值）
4. 动态路径成功：
   - unknown object + 非 Variant 参数（验证 `godot_Object_call` + pack）
   - Variant receiver + 未知方法（验证回退 `godot_Variant_call`）
5. 失败路径：
   - 接收者不存在 / result 不存在 / result 为 ref
   - 调用静态方法
   - 参数过多/过少且不可补
   - 参数类型不兼容
   - void 方法却给 resultId
   - **对象类型可确定但方法未知 -> 必须报错（不得动态回退）**
6. 类型规范化回归：
   - `typedarray::PackedByteArray` / `typedarray::PackedVector3Array` 解析结果应为 `GdPacked*ArrayType`
   - 非 packed 的 `typedarray::StringName` 仍应解析为 `GdArrayType(StringName)`

### 8.2 集成测试（可选但建议）

新增：`CallMethodInsnGenEngineTest.java`

目标：真实 Godot 跑通至少四类调用：

1. builtin 方法（`Vector3.rotated`）
2. engine 方法（`Node.call_thread_safe` 或 `Object.call`）
3. unknown object 动态路径（`godot_Object_call`）
4. Variant 动态路径（`godot_Variant_call`）

### 8.3 回归断言重点

对关键路径增加字符串级断言，避免语义回退：

- GDCC 互调场景：
  - 必须包含 `<OwnerClass>_<method>(...)`
  - 不得出现 `godot_Object_call(`
  - 不得出现 `godot_Variant_call(`
- Variant 动态场景：
  - 必须出现 `godot_Variant_call(`
- unknown object 动态场景：
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

- 注册 `CallMethodInsnGen`
- 实现 `BUILTIN/ENGINE/GDCC` 静态调用（不含默认补参）
- 完成结果契约 + 参数变量存在性 + 类型校验
- 落地“已知对象未知方法即报错”规则

### Phase 2（语义补齐）

- 默认参数补全：
  - extension literal default（复用 `CBuiltinBuilder`）
  - GDCC `default_value_func`
- vararg 契约补齐
- 完成 `typedarray::` 局部类型规范化（含 Packed*Array 特殊分支）

### Phase 3（动态兼容）

- `OBJECT_DYNAMIC`：`godot_Object_call` + pack/unpack
- `VARIANT_DYNAMIC`：`godot_Variant_call` + pack/unpack

### Phase 4（质量收敛）

- 单测矩阵补齐 + 引擎集成测试
- 对 GDCC 静态互调与动态回退边界加回归断言
- 文档同步（本文件转为 implemented/maintained）

---

## 10. 与现有架构的一致性说明

本方案严格遵循项目现有“生成器只做 IR 校验和路由，Builder 负责通用生命周期/指针语义”的原则：

- 不在 `CallMethodInsnGen` 手工写 own/release。
- 不在 `CallMethodInsnGen` 手工写 GDCC/Godot 指针转换。
- 不在 `CallMethodInsnGen` 重复实现值语义 destroy 规则。

因此该实现与 `CALL_GLOBAL`、`LOAD_PROPERTY/STORE_PROPERTY` 的长期维护方向一致，可最小化后续语义漂移风险。
