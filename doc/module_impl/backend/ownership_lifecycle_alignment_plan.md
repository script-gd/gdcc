# Backend 对象所有权与生命周期对齐实施计划

> Status: In Progress (`Step 1` completed on 2026-04-09)  
> Scope: `src/main/java/dev/superice/gdcc/backend/c/**`, `src/main/c/codegen/**`  
> 校对基线: 2026-04-09  
> 上游对齐基线: Godot `755fa449c4aa94fdf2c58e2b726fd62efde07e09`

## 1. 目标与背景

本计划要解决的是 backend 当前关于对象值所有权的两条语义来源虽然已经大体成形，但仍然分散在若干 helper、调用路径和文档中的问题：

1. **调用返回对象值时，返回本身是否已经把一份所有权交给 caller**
2. **把对象值写入 slot、返回给 caller、或在函数退出时清理时，哪些路径应该 retain，哪些路径只能 consume**

若这两件事没有被固定为单一事实源，后续继续演进 `OwnershipKind`、`_return_val`、构造路径、dynamic call 或复杂 writable target 时，最容易重新引入：

- duplicate retain（额外 `+1` 导致泄漏）
- missing retain（返回悬空 / 提前释放）
- double release（`_return_val` 发布后仍被本地 auto-cleanup 释放）
- 把 GDCC/Godot 指针表示转换误当成 ownership transfer

当前代码库已经拥有可复用的主体骨架：

- `CBodyBuilder.emitObjectSlotWrite(...)` 已实现统一对象写槽顺序
- `CBodyBuilder.returnValue(...)` 已把 object return 建模为写 `_return_val`
- `CCodegen` / `DestructInsnGen` 已把 auto-cleanup 限定在 reference-managed locals

因此本计划的方向不是重写 backend，而是**把现有骨架收口为一套明确、可验证、可测试的 ownership/lifecycle 合同**。

## 2. 上游 Godot 合同（本计划的外部事实基线）

### 2.1 GDExtension 直调返回 `RefCounted` 的 caller-owned 合同

对照 Godot `755fa449c4aa94fdf2c58e2b726fd62efde07e09`：

- `core/variant/method_ptrcall.h`
  - `PtrToArg<Ref<T>>::EncodeT = Ref<T>`
  - `encode(...)` 会把返回值写入 caller 提供的结果存储
- `core/object/object.h`
  - `RequiredResult<T>::ptr_type` 对 `RefCounted` 子类收口为 `Ref<T>`
- `core/object/ref_counted.h`
  - `Ref<T>` 赋值和析构本身带有 refcount 维护语义

因此，对 engine/GDExtension 直调路径而言：

- 返回 `RefCounted` 结果时，**返回本身已经把一份强引用交给 caller**
- caller 不应为了“让它在当前函数里先活着”再统一补一次 `reference()`
- 如果 caller 把这个结果存入新的 owning slot，则应直接 consume；只有在把 borrowed 值写入 slot 时才需要 own

### 2.2 Godot 对非 `RefCounted` 对象的作用域退出语义

Godot 并不会因为局部变量离开作用域而自动 `free()` / `queue_free()` 非 `RefCounted` 对象。  
因此 backend 只能对 reference-managed object slots 做 scope-exit cleanup：

- `RefCountedStatus.YES` -> `release_object`
- `RefCountedStatus.UNKNOWN` -> `try_release_object`
- `RefCountedStatus.NO` -> 不做 auto-cleanup

这条规则必须继续保持，不能因为“对象 slot 统一 cleanup”而放宽。

## 3. 当前代码库现状与缺口

### 3.1 已经对齐的部分

- `OwnershipKind` 已区分 `BORROWED` / `OWNED`
- `ValueRef#ownership()` 默认 `BORROWED`
- `callAssign(...)` 对 object call result 已固定按 `OWNED` 写槽
- `emitObjectSlotWrite(...)` 已固定顺序：
  1. capture old
  2. convert ptr if needed
  3. assign
  4. own only for `BORROWED`
  5. release old
- `returnValue(...)` 已把 object return 建模为写 `_return_val`
- `resolveMovedObjectReturnSource(...)` 已对一部分 owning local return 做 move-return
- `CCodegen` / `DestructInsnGen` 已避免对 definite non-`RefCounted` object locals 自动释放

### 3.2 当前仍存在的工程缺口

1. **producer 语义尚未作为唯一事实源冻结**
   - “哪些值源产生 `OWNED`”目前分散在 `callAssign(...)`、property-init route 和若干注释中
   - 未来若其他调用路径新增 object result，很容易忘记标记为 `OWNED`

2. **return boundary 合同仍然容易被误解成“函数返回前统一 retain”**
   - 当前实现其实是“写 `_return_val` 时按 source ownership 决定是否 retain”
   - 但若不明确冻结，后续很容易有人把 retain 挪到函数尾部统一处理，造成 fresh call result double retain

3. **auto-cleanup 语义虽然已在实现中收口，但文档与心智模型仍不够明确**
   - cleanup 针对的是“当前函数仍持有的 slot”
   - 不是“所有对象值”
   - 更不是“所有对象结果在 return 前统一 release/own 一次”

4. **指针表示转换与 ownership transfer 的边界仍有漂移风险**
   - `gdcc_object_from_godot_object_ptr(...)`
   - `gdcc_object_to_godot_object_ptr(...)`
   - `convertPtrIfNeeded(...)`
   - 这些必须继续只承担表示转换职责，不能夹带 retain/release

## 4. 目标合同（实施完成后必须满足）

### 4.1 统一 producer 规则

- function/method/construct/property-init helper 返回 object value -> `OWNED`
- variable/field/parameter/self/index/property read -> `BORROWED`
- `NULL` / `null` -> `BORROWED`
- 表示转换不改变 ownership

### 4.2 统一 consumer 规则

- object slot write：
  - `BORROWED` rhs -> slot own new
  - `OWNED` rhs -> slot consume directly
- return publish：
  - 本质上是写 `_return_val`
  - `BORROWED` source -> `_return_val` retain
  - `OWNED` source -> `_return_val` consume
- discard：
  - discard `OWNED` -> immediate release
  - discard `BORROWED` -> no-op

### 4.3 统一 cleanup 规则

- `__finally__` auto-cleanup 只处理当前函数变量表中的 managed slots
- `_return_val` 不参与 auto-cleanup
- `ref=true` 变量不参与 owning cleanup
- definite non-`RefCounted` object local 不自动 cleanup
- move-return 后源 slot 必须显式清空，避免再次 release

## 5. 明确拒绝的错误路线

### 5.1 拒绝“所有 object return 在函数返回前统一 own 一次”

这条路线会直接把 fresh call result 误判为 borrowed。

错误后果：

- fresh engine `RefCounted` return 本身已 caller-owned
- 若 return boundary 再统一 own 一次，则 refcount 多 `+1`
- caller 正常消费一次后，仍残留一份 ownership，形成泄漏

### 5.2 拒绝“所有对象 slot 在函数结束前统一 try_release 一次”

这条路线会把 slot cleanup、return publish 与 Godot 非 `RefCounted` 生命周期合同混为一谈。

错误后果：

- `_return_val` 被提前释放
- moved source 未清空时 double release
- `Node` / `Object` 一类非 `RefCounted` 对象被错误自动销毁
- `ref` 变量这种 alias-only 变量被误当 owning slot 清理

## 6. 实施约束

本计划实施时必须遵守以下设计约束：

- 不新增第二套 ownership 枚举；继续以 `OwnershipKind` 作为唯一值来源 ownership 标记
- 不新增与 `RefCountedStatus` 重叠的生命周期类型系统；继续以 `RefCountedStatus` 决定 `own/release/try_*` 选择
- 不引入新的对象写槽分支；继续把对象写槽统一收口到 `emitObjectSlotWrite(...)`
- 不把 retain/release 逻辑下推到指针转换 helper 或各个指令生成器里
- 不把 `_return_val` 建模成普通 auto-cleanup variable

## 7. 分步骤实施计划

### Step 1. 冻结 producer / consumer 合同为单一事实源

#### 当前状态

- Status: Completed on 2026-04-09
- Synced facts into:
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/cbodybuilder_implementation.md`
- Added code-side binding comments in:
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
- Added regression anchors in:
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CBodyBuilderPhaseCTest.java`
- Verified with:
  - `.\gradlew.bat test --tests CBodyBuilderPhaseCTest --tests CPhaseAControlFlowAndFinallyTest --tests CDestructInsnGenTest --no-daemon --info --console=plain`

#### 修改范围

- `doc/gdcc_ownership_lifecycle_spec.md`
- `doc/gdcc_c_backend.md`
- `doc/module_impl/backend/cbodybuilder_implementation.md`

#### 目标

- 把“object call result 默认 `OWNED`、var/field read 默认 `BORROWED`、return publish 等价于写 `_return_val`”写成显式合同
- 移除或修正任何“写入对象 slot 总是 retain new value”这类会误导后续维护的说法
- 明确 `_return_val` 不进入 auto-cleanup
- 明确 pointer conversion 不改变 ownership

#### 验收

- 三份文档在以下问题上说法一致：
  - object call result 是否默认 `OWNED`
  - borrowed field/local return 是否由 `_return_val` retain
  - move-return 是否需要清空源 slot
  - non-`RefCounted` locals 是否 auto-cleanup
- 文档中显式写出本计划拒绝的两条错误路线

### Step 2. 收口 object ownership producer 入口

#### 修改范围

- `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
- 相关 object-producing instruction generators

#### 目标

- 审计所有 object-producing 路径，确保它们只能通过以下方式进入 slot-write / return publish：
  - `valueOfVar(...)` / property/index read -> `BORROWED`
  - `valueOfOwnedExpr(...)` / `emitCallResultAssignment(...)` / constructor/property-init helper -> `OWNED`
- 若当前存在直接把 object call expression 当普通 `ExprValue(BORROWED)` 传入对象写槽的路径，必须改为显式 `OWNED`
- 若当前存在从已有 storage 读取却被包装成 `OWNED` 的路径，必须回退为 `BORROWED`

#### 验收

- `callAssign(...)` 对 object return 仍固定走 `OwnershipKind.OWNED`
- constructor/property-init fresh object 路径明确产生 `OWNED`
- `valueOfVar(...)`、字段读取、参数读取、property/index read 默认仍为 `BORROWED`
- 没有第二套“隐式猜测 object result ownership”的 helper 或 ad-hoc 分支

#### 重点测试

- Builder / generator 级单测：
  - fresh call result -> local slot
  - fresh call result -> field slot
  - fresh call result -> discard
- 负向断言：
  - 生成代码中 fresh object call result 写槽不出现额外 retain

### Step 3. 强化 `_return_val` 发布路径

#### 修改范围

- `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
- return 相关文档与注释

#### 目标

- 把 object return publish 明确收口为“写 `_return_val`”
- 保持 current move-return 策略保守：
  - 仅允许普通本地 object slot move 到 `_return_val`
  - parameter / `ref` / field read / expression value 不参与 move-return
- 在实现和注释中明确：
  - `_return_val` retain/consume 决策发生在 slot-write 时
  - 不是函数尾部统一 own

#### 验收

- `resolveMovedObjectReturnSource(...)` 的允许集合与文档一致
- borrowed parameter / field / property read 返回时，`_return_val` 走 retain 语义
- returning owned local object slot 时，源 slot 在进入 `__finally__` 前被清空
- `_return_val` 本身不会被后续 local cleanup 路径再次 release

#### 重点测试

- 正向：
  - `return ResourceLoader.load(...)`
  - `var r = ResourceLoader.load(...); return r`
  - `return self.cached_resource`
  - `return param_resource`
- 负向：
  - moved local return 不产生 double release
  - borrowed field return 不产生 missing retain

### Step 4. 锁定 `__finally__` auto-cleanup 只处理 managed slots

#### 修改范围

- `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/DestructInsnGen.java`
- 生命周期相关文档与注释

#### 目标

- 审计 `__finally__` 自动插入 `destruct` 的变量选择逻辑
- 继续保证以下对象不进入 auto-cleanup：
  - `_return_val`
  - `ref=true` 变量
  - definite non-`RefCounted` object locals
- 明确“cleanup 的对象是 slot，而不是值”

#### 验收

- `CCodegen` 插入 auto-generated `destruct` 时仍跳过 `ref` locals
- `DestructInsnGen` 对 `RefCountedStatus.NO` 且 `AUTO_GENERATED` 的 object destruct 继续 no-op
- 文档中明确 `_return_val` 不在 auto-cleanup 集合

#### 重点测试

- 正向：
  - `RefCountedStatus.YES` local 在 finally 被 release
  - `RefCountedStatus.UNKNOWN` local 在 finally 走 `try_release`
- 负向：
  - `Node` / `Object` local 不自动 `free` / destroy
  - `_return_val` 不被注入 auto-destruct
  - move-return 源 slot 清空后不会再 release 已发布返回值

### Step 5. 指针表示转换与 ownership 边界审计

#### 修改范围

- `CBodyBuilder.convertPtrIfNeeded(...)`
- `CBodyBuilder.toGodotObjectPtr(...)`
- `CBodyBuilder.fromGodotObjectPtr(...)`
- `gdcc_helper.h` / 相关模板注释（如有必要）

#### 目标

- 审计所有 GDCC/Godot object pointer 转换路径，确认它们只做表示转换，不承担 retain/release
- 明确禁止在转换 helper 中补 own/release
- 明确任何需要 retain 的地方都只能发生在：
  - object slot write
  - discard owned object result
  - explicit lifecycle instruction

#### 验收

- 代码中不存在“转换 helper 内部改变 ownership”的实现
- 文档明确 `Representation conversion does not change ownership`
- 回归测试覆盖：
  - GDCC_PTR -> GODOT_PTR -> slot write 不额外 own
  - GODOT_PTR -> GDCC_PTR -> return publish 不额外 own

### Step 6. 测试矩阵补全与行为锚定

#### 修改范围

- backend unit tests
- 如有必要，新增端到端 compile/integration tests

#### 目标

- 用正反两面测试把本计划涉及的 ownership 语义锁定下来
- 避免后续维护者在“感觉上更安全”的方向把 retain/release 重新挪错位置

#### 最少测试矩阵

- producer matrix
  - call result object -> `OWNED`
  - local/field/parameter read object -> `BORROWED`
- object slot write
  - `BORROWED` rhs -> own new
  - `OWNED` rhs -> no extra own
  - overwrite old -> release old
- return publish
  - fresh owned call result return
  - borrowed field return
  - borrowed parameter return
  - moved local return
- discard
  - discard owned object call result -> immediate release
  - discard borrowed object value -> no cleanup
- finally cleanup
  - yes/unknown/no refcounted status cases
  - non-`RefCounted` local no auto-cleanup
  - `_return_val` excluded
- representation conversion
  - conversion path does not change own/release count

## 8. 潜在风险与应对

### 风险 1. fresh call result 被误标成 `BORROWED`

#### 结果

- 写槽时额外 retain
- finally / overwrite 只 release 一次
- 泄漏

#### 应对

- 冻结“object call result = `OWNED`”为硬合同
- 所有 object call result 统一经 `emitCallResultAssignment(...)`
- 用单测断言 fresh object return 写槽不出现额外 `own_object` / `try_own_object`

### 风险 2. borrowed field/local read 被误标成 `OWNED`

#### 结果

- return publish 或 slot write 时不 retain
- 原 owner 变化后悬空

#### 应对

- 限制 `OWNED` 只来自 fresh producer
- `valueOfVar(...)` / property/index read 默认保持 `BORROWED`
- 用 borrowed field/parameter return 测试锚定

### 风险 3. move-return 允许集合过宽

#### 结果

- 错误清空 source
- 或错误跳过 retain
- 或发布后仍被 finally 清理

#### 应对

- 保持 `resolveMovedObjectReturnSource(...)` 保守
- 若未来要扩大 move-source 集合，必须先引入更强的 slot provenance，而不是继续靠 `ValueRef` 猜

### 风险 4. `_return_val` 被错误纳入 auto-cleanup

#### 结果

- 已发布返回对象被提前 release

#### 应对

- 文档和代码都显式声明 `_return_val` 不在变量表 auto-cleanup 范围
- 单测断言 finally 中无 `_return_val` auto-destruct

### 风险 5. non-`RefCounted` locals 被错误自动清理

#### 结果

- 与 Godot 显式生命周期合同冲突
- `Node` / `Object` 行为错误

#### 应对

- 保持 `RefCountedStatus.NO` 的 auto-generated object destruct 为 no-op
- 加正反回归测试锁定

### 风险 6. 指针表示转换被误当 retain/release 边界

#### 结果

- GDCC/engine 双表示路径下引用计数漂移

#### 应对

- 保持 `convertPtrIfNeeded(...)` 只做表示转换
- 文档与注释双向绑定“conversion != ownership transfer”

## 9. 关联文档同步要求

实施本计划时，以下文档必须一并同步，避免出现“代码变了但事实源没改”的漂移：

- `doc/gdcc_ownership_lifecycle_spec.md`
- `doc/gdcc_c_backend.md`
- `doc/module_impl/backend/cbodybuilder_implementation.md`
- 如实现影响 `construct_object` / object-return LIR 合同，再同步：
  - `doc/gdcc_low_ir.md`

同步原则：

- 规范文档写“长期合同”
- 实现文档写“当前代码如何落地该合同”
- 不在最终事实源中保留阶段性进度流水账

## 10. 完成定义（Definition of Done）

当且仅当以下条件全部满足时，本计划视为完成：

1. backend 中 object ownership producer/consumer 规则只有一套事实源，不再依赖隐式约定
2. `emitObjectSlotWrite(...)` 仍是唯一对象写槽核心
3. `_return_val` 发布与 finally cleanup 的关系被代码和测试同时锁定
4. `RefCountedStatus.YES/UNKNOWN/NO` 三类 cleanup 行为均有正反测试
5. 指针表示转换路径被验证不会改变 ownership
6. 文档、代码注释、单元测试三者在上述语义上说法一致
