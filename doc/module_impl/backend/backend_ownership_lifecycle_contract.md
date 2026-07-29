# Backend 对象所有权与生命周期合同

> 本文档作为 backend 对象所有权与生命周期实现的长期事实源。  
> 只保留当前代码已经落地的合同、约束、测试锚点与长期风险，不记录阶段性实施流水账。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - `src/main/java/gd/script/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
- 更新时间：2026-07-29（Object → UNKNOWN ownership boundary）
- 上游对齐基线：Godot `755fa449c4aa94fdf2c58e2b726fd62efde07e09`
- 关联文档：
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/cbodybuilder_implementation.md`

## 当前最终状态

### 核心实现落点

- 对象槽位写入：`src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
- 返回发布：`src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
- `__finally__` 自动清理选择：`src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`
- 生命周期指令生成：`src/main/java/gd/script/gdcc/backend/c/gen/insn/DestructInsnGen.java`
- GDCC/Godot 对象指针表示转换 helper：
  - `src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`

### 当前已锁定的实现结论

- `OwnershipKind` 是 backend 唯一的对象值所有权来源分类：`BORROWED` / `OWNED`
- 对象写槽统一走 `emitObjectSlotWrite(...)`
- 对象返回统一建模为写隐藏返回槽 `_return_val`
- `__finally__` auto-cleanup 只处理当前函数仍然持有的 managed local slots
- GDCC/Godot 对象指针转换是纯表示转换，不承担 retain/release

## 长期合同

### 1. 对象值来源合同

- fresh object producer 默认产生 `OWNED`：
  - function call
  - method call
  - constructor / materialization
  - property initializer helper
- 从现有存储读取对象值默认产生 `BORROWED`：
  - local variable
  - parameter
  - backing field / `self` field
  - property read
  - index read
- `null` / `NULL` 视为 `BORROWED`
- GDCC/Godot 指针表示转换不会改变 `OWNED` / `BORROWED`

### 2. 对象槽位写入合同

- 对象写槽固定顺序必须保持：
  1. capture old value
  2. convert pointer representation if needed
  3. assign new value
  4. own new value only when rhs is `BORROWED`
  5. release captured old value
- `OWNED` rhs 必须被直接 consume，不能因“写入 slot 更安全”而重复 retain
- `BORROWED` rhs 必须在新 slot 边界 retain
- 这条顺序同样适用于 `_return_val` 的对象发布路径

### 3. `_return_val` 发布合同

- object return 的 backend 语义是“写 `_return_val`”，不是“函数尾部统一 retain 一次”
- 发布规则：
  - `BORROWED` source -> `_return_val` retain
  - `OWNED` source -> `_return_val` direct consume
- 仅 ordinary local object slot 允许 move-return：
  - 不允许 parameter
  - 不允许 `ref` variable
  - 不允许 capture
  - 不允许 field/property/index expression
- 发生 move-return 时，源 slot 必须在进入 `__finally__` 前显式清空

### 4. discard 合同

- discard `OWNED` object value -> immediate release
- discard `BORROWED` object value -> no-op
- discard destroyable non-object value -> immediate destroy

### 5. cleanup 合同

- `__finally__` auto-cleanup 是 slot-based cleanup，不是对所有 live object values 的统一收尾
- auto-cleanup 只面向当前函数仍然持有的 managed locals
- 以下对象必须继续排除在 auto-cleanup 之外：
  - `_return_val`
  - `ref=true` variables
  - definite non-`RefCounted` object locals
  - moved-out return sources
- `RefCountedStatus` 选择矩阵必须保持：
  - `YES` -> `release_object`
  - `UNKNOWN` -> `try_release_object`
  - `NO` -> no-op
- 所有 release/destroy helper 内部检查 `godot_RefCounted_unreference` 返回值：为 true（引用计数归零且无
  script/binding veto）时调用 `godot_object_destroy` 释放对象。调用侧在 release/destroy 后不得再访问该指针。

### 6. 指针表示转换合同

- `convertPtrIfNeeded(...)`
- `toGodotObjectPtr(...)`
- `fromGodotObjectPtr(...)`
- `gdcc_object_to_godot_object_ptr(...)`
- `gdcc_object_from_godot_object_ptr(...)`

以上 helper 都是表示转换边界，不是 ownership transfer 边界。

必须保持以下约束：

- `OWNED` 值跨表示转换后仍然是 `OWNED`
- `BORROWED` 值跨表示转换后仍然是 `BORROWED`
- conversion helper 内不得补发 `own_object` / `try_own_object`
- conversion helper 内不得补发 `release_object` / `try_release_object`
- retain/release 只能发生在：
  - object slot write
  - `_return_val` publish
  - discard owned object result
  - explicit lifecycle instruction

### 7. 生命周期 provenance 合同

- `AUTO_GENERATED` 生命周期指令只用于编译器自动维护路径
- `AUTO_GENERATED` destruct 只允许出现在 `__finally__`
- `AUTO_GENERATED` 不允许用于 `try_own_object` / `try_release_object`
- backend 继续依赖 provenance 校验后的 IR；生成器做 fail-fast 防御，不做语义修补

### 8. vararg 动态调用返回所有权合同

exact-engine helper 的 vararg（动态 `object_method_bind_call`）路径，对象返回值必须经过临时 `Variant ret`
中转。该路径的引用计数收支必须保持平衡：

- helper 端：unpack 出对象后、`godot_Variant_destroy(&ret)` 之前，必须按 `RefCountedStatus` 补发
  `own_object` / `try_own_object`（`CGenHelper.renderEngineMethodHelperVarargObjectReturnOwnStmt`）。
  这一步把临时 Variant 持有的引用转交给返回的 fat pointer，使 helper 返回成为 `OWNED` producer。
  - `YES` -> `own_object`
  - `UNKNOWN` -> `try_own_object`
  - `NO` -> no-op（非 `RefCounted` 对象不引用计数，Variant 销毁不影响其存活）
- 调用端：helper 返回的这份 `OWNED` 强引用必须被**恰好消费一次**，三条闭合路径：
  - 写槽：`emitObjectSlotWrite(..., OWNED)` 直接接管，不重复 retain；后续由覆盖写或 `__finally__` cleanup 释放
  - discard：`emitDiscardedCall` 立即 `release_object` / `try_release_object`
  - public wrapper：call wrapper 用 `to_variant` 把 fat 返回打包进返回 Variant。打包为 `r_return`
    建立自己的引用（`to_variant` + `variant_new_copy` + `Variant_destroy`），但**不消费**函数返回的
    internal OWNED 强引用；wrapper 必须在 `Variant_destroy(&ret)` 之后对返回载体 `r` 补发
    `release_object` / `try_release_object`（`CGenHelper.renderCallWrapperOwnedObjectReturnConsumeStmt`），
    使 ownership 净零转入 `r_return`。`YES` → `release_object`，`UNKNOWN` → `try_release_object`，
    `NO` → no-op。该 consume **仅**用于返回载体，不得对 BORROWED 参数 locals 使用。
- exact / ptrcall 路径**不**适用本合同：它直接把 raw 指针写进 slot（`result_raw`），没有 Variant 中转去释放引用，
  `_from_raw` 捕获即自带所有权，无需额外 retain / release。

静态类型驱动的所有权边界（`Object` 根类型）：

- own/release/consume 决策以**槽或返回值的静态类型**的 `RefCountedStatus` 为键，不是运行时实际类型。
- 精确引擎类型 `Object` 由 `ClassRegistry.getRefCountedStatus` 解析为 `UNKNOWN`（扩展 API 虽标
  `is_refcounted=false`，但 `Object` 槽/返回值可能持有 live `RefCounted` 实例）。
- 因此所有权转移边界（slot retain/release、wrapper consume、discard、helper own、`__finally__`
  cleanup）对 `Object` 统一走运行期 `try_own_object` / `try_release_object`，与已有 UNKNOWN 路径一致。
- `try_*` helper 以 `(validated live raw ptr, fat.instance_id)` 为参，内部用 ObjectID reference bit
  判别 RefCounted（不做 ClassDB 类名查询）；ID 来自 fat pointer 缓存，不从可能已释放的裸指针反推。
- 例：`func f() -> Object: return RefCounted.new()` — wrapper 在 pack 后 `try_release` 消费构造
  `init_ref` 的 OWNED；`func echo(o: Object) -> Object: return o` — body 对 BORROWED 写返回时
  `try_own`，wrapper 再 `try_release`，引用收支平衡。
- 确定的非 RC 子类（`Node` 等）与继承 `Object` 但未走到 `RefCounted` 的 GDCC 用户类仍为 `NO`，不
  触发 `try_*`。
- 若将来出现其它“静态基类可能承载 RC 实例”的边界类型，同样应按 `UNKNOWN` 处理，而不是仅改
  wrapper consume 一侧。

违约后果：

- 把 helper 返回误判为 `BORROWED`（再 retain 一次，或始终不 release）→ 残留多余 ownership，形成 `RefCounted` 泄漏
- 漏掉 helper 端的 own → fat pointer 不持有强引用，违反 fat-pointer ownership invariant，
  RefCounted fast path 会读到已被 Variant destroy 释放的对象（UAF）
- 漏掉 call wrapper 对 OWNED 返回 `r` 的 `release_object` → 内部 OWNED 引用泄漏，GDScript 侧
  `get_reference_count()` 多 1（fresh return 终值 2 而非 1）
- 对 BORROWED 参数 locals 误发 `release_object` → 少计 1 引用，GDScript 释放时提前 free 形成 UAF

实现锚点：

- `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`（`renderEngineMethodHelperVarargObjectReturnOwnStmt` / `renderCallWrapperOwnedObjectReturnConsumeStmt`）
- `src/main/c/codegen/template_451/engine_method_binds.h.ftl`（vararg 路径 `result = unpack(&ret)` 之后；engine constructor `gdcc_ref_counted_init_raw`）
- `src/main/c/codegen/template_451/entry.h.ftl`（call wrapper pack 后 consume OWNED `r`）
- `src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`（`emitCallResultAssignment` / `emitDiscardedCall`）

## 明确拒绝的错误路线

### 1. 拒绝“所有 object return 在函数返回前统一 own 一次”

这会把 fresh object result 误判为 borrowed，并让已 caller-owned 的 `RefCounted` 返回值多一次 `+1`。

直接后果：

- fresh call result 写入 slot 后多 retain 一次
- 正常消费结束后残留多余 ownership
- 形成泄漏

### 2. 拒绝“所有对象 slot 在函数结束前统一 try_release 一次”

这会把 slot cleanup、return publish 与 Godot 非 `RefCounted` 生命周期合同混为一谈。

直接后果：

- `_return_val` 被提前 release
- moved source 未清空时发生 double release
- `Node` / `Object` 一类非 `RefCounted` local 被错误自动销毁
- `ref` alias slot 被误当 owning slot 清理

## 与 Godot 的对齐点

### 1. `RefCounted` 返回值是 caller-owned

对 engine/GDExtension 直调路径，Godot 会把 `RefCounted` 返回结果以 caller-owned 的方式交给调用方。  
因此 backend 不能在“返回后为了安全”再统一补一次 retain；fresh result 进入新 owning slot 时必须 direct consume。

### 2. 非 `RefCounted` 对象不因作用域退出而自动释放

Godot 不会因为 local variable 离开作用域就自动 `free()` / `queue_free()` 普通对象。  
因此 backend 自动清理只能覆盖 reference-managed object slots，不能扩展到 definite non-`RefCounted` objects。

## 回归测试基线

- `src/test/java/gd/script/gdcc/backend/c/gen/CBodyBuilderPhaseCTest.java`
  - producer / consumer matrix
  - `_return_val` publish
  - move-return
  - pointer conversion neutrality
- `src/test/java/gd/script/gdcc/backend/c/gen/CDestructInsnGenTest.java`
  - `YES` / `UNKNOWN` / `NO` cleanup behavior
- `src/test/java/gd/script/gdcc/backend/c/gen/CPhaseAControlFlowAndFinallyTest.java`
  - `__finally__` auto-cleanup selection
  - `_return_val` exclusion
- `src/test/java/gd/script/gdcc/backend/c/gen/CGenHelperTest.java`
  - helper-level pointer conversion rendering

建议命令：

```bash
./gradlew.bat test --tests CBodyBuilderPhaseCTest --tests CGenHelperTest --no-daemon --info --console=plain
./gradlew.bat test --tests CDestructInsnGenTest --tests CPhaseAControlFlowAndFinallyTest --no-daemon --info --console=plain
./gradlew.bat classes --no-daemon --info --console=plain
```

## 长期风险与维护提醒

1. 新增 object-producing 路径时，最容易漏掉 `OWNED` 标记，导致 fresh result 被当成 borrowed 再 retain 一次。
2. 新增 ptr conversion helper 或模板宏时，最容易把表示转换误做成 ownership 边界，造成隐式 retain/release 漂移。
3. 若未来放宽 move-return 允许集合，必须先增强 slot provenance；不能继续靠 `ValueRef` 形态猜测 source 是否可 move。
4. auto-cleanup 若被重新理解为“所有对象值统一回收”，会直接破坏 `_return_val`、non-`RefCounted` locals 与 `ref` alias 的既有合同。
5. 新增消费 exact-engine helper 返回的路径时，必须保证其 `OWNED` 对象返回被恰好消费一次（写槽 / discard / wrapper consume）；
   漏消费或误当 `BORROWED` 再 retain 都会造成 `RefCounted` 泄漏（见长期合同 §8）。

## 工程反思

1. ownership 规则一旦分散到生成器、helper、模板三处，就会很快漂移；backend 必须把 retain/release 收敛到少数固定边界。
2. `_return_val` 是 return-publish slot，不是普通 local variable。只要把它误并入 auto-cleanup，返回值生命周期就会立刻出错。
3. “转换成功”不等于“生命周期正确”。GDCC/Godot 指针双表示场景必须同时验证表示转换和 ownership 不漂移。
4. 阶段计划文档不应长期充当事实源；最终实现文档必须只保留当前合同、回归锚点和仍有长期价值的边界说明。

## 非目标

- 为 backend 再引入第二套 ownership 枚举或新的生命周期类型系统
- 把 retain/release 逻辑下推到每个 instruction generator
- 把 `_return_val` 当作普通 managed local 处理
