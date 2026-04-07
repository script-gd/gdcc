# CBodyBuilder 实现规范（单一事实来源）

> 本文整合并取代以下文档中的实现信息：
> - `doc/module_impl/cbodybuilder_implementation_guide.md`
> - `doc/module_impl/cbodybuilder_ownership_semantics_migration_plan.md`
> - `doc/module_impl/gdcc_lifecycle_codebase_analysis.md`
> - `doc/module_impl/gdcc_object_ptr_conversion_refactor_checklist.md`
>
> 目标：仅保留当前代码库已落地且可验证的实现语义、长期风险和工程反思。
>
> 校对基线：2026-02-25（代码与单测已交叉检查）

## 1. 范围与对齐关系

- 代码范围：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/`
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
  - `src/main/c/codegen/template_451/entry.c.ftl`
- 规范关系：
  - 上游语义文档：`doc/gdcc_c_backend.md`、`doc/gdcc_ownership_lifecycle_spec.md`
  - 本文提供 CBodyBuilder 及相关生成器的实现侧“可执行语义”与“工程约束”。

## 2. 当前实现总览

### 2.1 统一槽位写入模型

- 对象类型写入统一走 `emitObjectSlotWrite(...)`，顺序固定：
  1. 可覆盖时先 capture old 到对象 temp（`__gdcc_tmp_old_obj_*`）
  2. 必要时做指针表示转换
  3. 赋值
  4. RHS 为 `BORROWED` 时 own new；RHS 为 `OWNED` 时消费所有权，不重复 own
  5. release captured old（`release_object` / `try_release_object` / no-op）
- 非对象类型写入统一走 `emitNonObjectSlotWrite(...)`：
  1. 满足条件时 destroy old（`destroyOldValue && !__prepare__ && type.isDestroyable()`）
  2. 赋值
- `markTargetInitialized(...)` 与 temp 生命周期仍由调用方控制（未内聚到槽位写入 helper）。
- constructor-time property initializer apply 当前通过 `CCodegen#generatePropertyInitApplyBody(...)` 调用 direct backing-field helper：
  - `${Class}_class_apply_property_init_<property>(self)`
  - 该 helper 明确不是 setter route
  - object-valued apply 通过 `applyPropertyInitializerFirstWrite(...)` 复用统一 ptr conversion / ownership consume 语义
  - constructor-time first write 仍不释放旧字段值

### 2.2 所有权与值来源模型

- `OwnershipKind`：`BORROWED` / `OWNED`
- `ValueRef#ownership()` 默认 `BORROWED`
- `valueOfOwnedExpr(...)` 用于显式 `OWNED` 值来源（例如 call result 语义）
- `callAssign(...)` 将对象返回值按 `OWNED` 路径写入目标槽，避免重复 own。

### 2.3 指针表示模型与转换

- `PtrKind`：`GDCC_PTR` / `GODOT_PTR` / `NON_OBJECT`
- GDCC -> Godot 统一使用 helper：
  - `gdcc_object_to_godot_object_ptr(obj, Class_object_ptr)`（唯一基线）
- Godot -> GDCC 统一使用：
  - `gdcc_object_from_godot_object_ptr(...)`
- `convertPtrIfNeeded(...)` 仅做表示转换，不改变所有权类别。
- 显式类型转换表达式统一由 `CBodyBuilder#valueOfCastedVar(LirVariable, GdType)` 构造：
  - 返回 `ExprValueRef`（表达式值），不可作为赋值目标使用。
  - 用于生成器中的“按目标类型强制转换后参与调用参数”的场景，避免手工拼接 cast 字符串。

### 2.4 赋值可兼容性（`ClassRegistry#checkAssignable`）

- `assignVar` / `assignExpr` / `callAssign` / `returnValue` 的可赋值性检查由 `CBodyBuilder#checkAssignable` 触发，并委托给 `ClassRegistry#checkAssignable`。
- 基础规则：
  - 同类型可赋值。
  - 对象类型允许继承上行转换。
- 容器协变扩展（全局语义，不限于 CBodyBuilder）：
  - `Array[T] -> Array`（等价 `Array[Variant]`）允许。
  - `Array[SubClass] -> Array[SuperClass]` 允许。
  - `Dictionary[K, V] -> Dictionary`（等价 `Dictionary[Variant, Variant]`）允许。
  - `Dictionary[K1, V1] -> Dictionary[K2, V2]` 在 key/value 均可赋值时允许。
  - 除上述规则外，不引入其他容器协变或数值提升。

## 3. GDCC/Godot 对象指针转换规则（已落地）

### 3.1 统一约束

- 调用 GDExtension API（如 `godot_*`、`*own_object`、`*release_object`、`try_destroy_object`）时，若参数是 GDCC 对象指针，必须转换为 Godot raw ptr。
- 代码生成侧禁止手写 `->_object` 来做“调用侧转换”；统一走：
  - `CBodyBuilder#toGodotObjectPtr(...)`
  - `gdcc_object_to_godot_object_ptr(...)`（唯一允许的 GDCC -> Godot 路径）

### 3.2 helper 宏语义

- `gdcc_object_to_godot_object_ptr(obj, Class_object_ptr)` 是当前推荐路径，且 NULL-safe。
- `godot_object_from_gdcc_object_ptr(obj)` 已废弃，不得用于新增或迁移后的路径。
- `godot_new_Variant_with_gdcc_Object(obj)` 已复用上述 helper，因此打包 Variant 时不应额外手动转换。

### 3.3 明确不替换的场景

- `entry.c.ftl` 中 `self->_object = obj;` 是封装体字段初始化，不属于“调用侧 GDCC->Godot 转换”，不应替换。
- helper 宏内部 `_o->_object` 是宏实现细节，不属于调用侧手写访问。

## 4. `__prepare__` / `__finally__` 与返回槽

### 4.1 控制流框架

- `CCodegen` 会确保每个函数存在 `__prepare__` 与 `__finally__`。
- 非 `__finally__` 中的 `returnValue(...)`：写 `_return_val` 后 `goto __finally__`。
- 非 `__finally__` 中的 `returnVoid()`：仅允许 void 函数并 `goto __finally__`。
- `returnTerminal()` 仅允许在 `__finally__` 返回 `_return_val`。

### 4.2 `_return_val` 语义

- 在 `__prepare__` 顶部声明 `_return_val`；对象返回类型初始化为 `NULL`。
- 对象返回槽写入复用对象槽位写入语义（含 own/release/转换）。
- 当返回值来自本地 owning object slot 时，Builder 会把该 slot move 到 `_return_val` 并清空源槽，避免 `__finally__` auto-destruction 释放已发布的返回对象。
- 非对象返回槽目前保持 direct assignment（不走 `emitNonObjectSlotWrite`）。

## 5. TempVar 与首写语义

- `TempVar` 维护可变 initialized 状态。
- 未初始化 temp 首写时：
  - 不 destroy/release old
  - 写入后标记 initialized
- `destroyTempVar(...)` 仅在 initialized 为 true 时生效。

## 6. 指令生成器协作约束

- 指令生成器负责 IR 校验与错误定位，不直接复制生命周期策略。
- 应优先通过 `assignVar` / `assignExpr` / `callAssign` / `callVoid` 交给 Builder 处理生命周期与转换。
- 对对象类型，避免在生成器中手工拼 own/release 与指针转换逻辑。
- 涉及变量强制类型转换时，避免在生成器里手写 `(<ctype>)$<id>` 字符串，统一使用 `valueOfCastedVar(...)`。
- 生命周期受限指令（`destruct` / `try_own_object` / `try_release_object`）在进入 Builder 前必须通过 provenance 校验。
  - 生成器可做轻量防御断言，但主校验在 `LifecycleInstructionRestrictionValidator`。
  - 对非法来源应 fail-fast，不允许降级为“尽量生成”。
- `ExprTargetRef` / `targetOfExpr(...)` is assignment-only by design.
  Use it only with `assignVar` / `assignExpr`, not as a generic target for `callAssign` or return/discard paths.

## 7. 已知风险与后续关注点

### 7.1 `StorePropertyInsnGen` 的 setter-self 直写路径

- 现状：setter-self 分支已收敛到 `assignVar(targetOfExpr(...), valueOfVar(...))`，
  通过 Builder 统一槽位写入语义处理生命周期和指针转换。
- 收敛收益：
  - 不再需要在生成器里手工拼接 own/release。
  - 对象写槽顺序与 `assignVar` / `callAssign` / `_return_val` 保持一致。
- 仍需关注：
  - 模板层和 Java 层双轨演进时，需持续做语义对齐回归。

### 7.2 FTL 与 Java 路径演进偏差风险

- 模板层（`entry.c.ftl`）仍保留部分生命周期代码。
- property initializer constructor-time apply 已通过 Java 侧统一 body generation 收口为单一 apply helper，降低了 class constructor 或模板层继续内联字段写入的漂移风险。
- 后续若 Java 语义继续收敛，需同步审计模板逻辑，避免双轨语义漂移。

### 7.3 helper 宏可移植性

- `gdcc_object_to_godot_object_ptr` 使用 GNU 扩展（statement expression + `__typeof__`）。
- 当前链路可用，但若未来切换到不支持 GNU 扩展的纯 MSVC 语义环境，需要替代实现方案。

## 8. 回归校验基线

以下用例用于验证本文语义与代码一致性（已通过）：

```bash
./gradlew.bat test --tests LifecycleInstructionRestrictionValidatorTest --no-daemon --info --console=plain
./gradlew.bat test --tests LifecycleProvenancePropagationTest --no-daemon --info --console=plain
./gradlew.bat test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
./gradlew.bat test --tests CDestructInsnGenTest --tests COwnReleaseObjectInsnGenTest --tests CPackUnpackVariantInsnGenTest --tests CStorePropertyInsnGenTest --no-daemon --info --console=plain
./gradlew.bat test --tests LifecycleInstructionProvenanceParserTest --tests SimpleLirBlockInsnSerializerTest --tests DomLirSerializerTest --no-daemon --info --console=plain
./gradlew.bat classes --no-daemon --info --console=plain
```

关键顺序断言（对象写槽）已按以下语义更新：

- `capture old -> assign(convert if needed) -> own(BORROWED only) -> release captured old`
- `_return_val` 仍保持“return-flow 发布槽位”边界：不进入变量表自动析构范围。

## 9. 工程反思（保留可复用教训）

1. 指针转换规则分散在调用点时，最容易出现 `NULL->_object`、遗漏转换、断言漂移等问题；必须集中到单点 helper + Builder 自动转换。
2. 迁移文档混合“待办清单”和“已完成记录”会快速失真；实现文档应只保留当前事实，历史过程归档到提交记录。
3. 生命周期规则若在模板和 Java 两侧同时手写，长期一定发生语义偏差；应尽可能由单一入口承载。
4. 单元测试不应只断言“有某个函数名”，还要断言关键顺序
   （capture old -> assign -> own -> release old）与负向约束（不重复 own、不错误转换）。
