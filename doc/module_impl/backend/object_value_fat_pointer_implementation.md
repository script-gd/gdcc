# C Backend 对象值胖指针实现规范

> 本文档是 C backend 内部对象值胖指针表示的单一事实源。只保留当前代码已落地的合同、约束、
> 架构设计与长期风险，不记录阶段性实施流水账。如与历史文档或旧实现描述冲突，以本文件为准。

## 文档状态

- 状态：Implemented / Maintained
- 更新时间：2026-07-30
- Godot 对齐版本：`4.5.1-stable`
- Godot 对齐提交：`f62fdbde15035c5576dad93e586201f4d41ef0cb`
- 核心实现落点：
    - `src/main/java/gd/script/gdcc/backend/c/gen/fatptr/ObjectFatPtrSpec.java`
    - `src/main/java/gd/script/gdcc/backend/c/gen/fatptr/CObjectFatPtrCollector.java`
    - `src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
    - `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
    - `src/main/c/codegen/template_451/object_fat_ptr_types.h.ftl`
    - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
- 关联文档：
    - `doc/gdcc_c_backend.md`
    - `doc/gdcc_runtime_lib.md`
    - `doc/gdcc_ownership_lifecycle_spec.md`
    - `doc/module_impl/backend/backend_ownership_lifecycle_contract.md`
    - `doc/module_impl/backend/cbodybuilder_implementation.md`
    - `doc/module_impl/backend/explicit_c_inheritance_layout_contract.md`
    - `doc/module_impl/backend/godot_binding_implementation.md`
    - `doc/module_impl/backend/operator_insn_implementation.md`
    - `doc/module_impl/backend/variant_abi_contract.md`
    - `doc/gdcc_low_ir.md`
    - `doc/gdcc_lir_intrinsic.md`
    - `doc/gdcc_type_system.md`

---

## 1. 设计动机

Godot 4.5.1 的 `Variant::ObjData` 同时保存 `ObjectID id` 和 `Object *obj`。对象使用前通过
`ObjectDB::get_instance(id)` 验证；ID 非零但无法解析表示 previously freed。

裸指针表示的缺陷：

- 裸指针可能悬空，不能再安全调用 `godot_object_get_instance_id(...)`。
- 仅把悬空指针与 `NULL` 比较不能实现 Godot 的 freed-object null 语义。
- 生命周期、property、method、operator、Variant pack 等路径可能继续把悬空指针传回 Godot。

GDCC 的内部对象表示采用同样的核心原则：身份由 instance ID 保留，裸指针只能作为非权威缓存和 ABI 载荷。

---

## 2. 核心合同

1. 每个可达的静态 `GdObjectType` 都生成独立 C 胖指针类型。
2. 胖指针按值携带具体静态指针和 `GDObjectInstanceID`。
3. locals、parameters、returns、properties、temporaries、default values 和内部 direct calls 统一使用胖指针。
4. 所有进入 GDExtension/Godot raw ABI 的对象值都先取得 live pointer：non-RefCounted 按 ID 验证；满足 ownership
   invariant 的 RefCounted 强引用可直接使用 cached pointer。
5. 所有从 raw ABI 进入内部表示的对象值都在对象仍存活时立即捕获 instance ID。
6. Variant 解包必须从 Variant 自身读取保存的 ID，不能从可能悬空的 pointer 重新读取。
7. 对象 `==`/`!=` 直接比较 normalized raw Godot object pointer；object-vs-null / `object_is_null` 使用 `(raw, id)` 安全查询。
8. 保持现有 `BORROWED` / `OWNED`、对象槽位写入顺序和 `_return_val` 发布合同。
9. 未识别对象类型在 type collection 或 C type rendering 阶段 fail-fast，不退化为 `GDExtensionObjectPtr`。
10. 解引用前的硬失败存活性守卫建模为显式无返回 LIR 指令 `assert_object_live`；条件判断、equality、lifecycle、Variant
    pack/unpack 不引入额外 LIR liveness 指令。
11. 对静态或动态 RefCounted 对象启用基于 ObjectID reference bit 的免 ObjectDB 验证快速路径。
12. 存活性与 ID 查询 helper 标注 `pure`/`const`，允许 C 编译器裁剪冗余调用。

### 2.1 非目标

- 不修改 frontend 或 LIR 的对象类型模型。
- 不修改 Godot engine、GDExtension ABI 或 vendored interface layout。
- 不把 GDCC wrapper 实例本体改成胖指针。
- 不改变 `_super` 位于偏移 0 的显式继承布局。
- 不在 generated C 中读取或写入 `godot_Variant` 私有内存布局。
- 不为所有对象类型增加一个通用 `void *` 胖指针作为 fallback。
- 不新增第二套 ownership 类型系统。

---

## 3. 目标表示

### 3.1 每种静态对象类型独立生成

```c
typedef struct gdcc_Node_fat_ptr {
    godot_Node *ptr;
    GDObjectInstanceID instance_id;
} gdcc_Node_fat_ptr;

typedef struct gdcc_Player_fat_ptr {
    Player *ptr;
    GDObjectInstanceID instance_id;
} gdcc_Player_fat_ptr;
```

规则：

- engine 类型的 `ptr` 必须保持 `godot_<Type> *`。
- GDCC 类型的 `ptr` 必须保持 `<Type> *` wrapper pointer。
- ID 字段使用 Godot ABI 的 `GDObjectInstanceID`，其物理语义为 64-bit instance ID。
- 不生成 `gdcc_object_ref`、`void *ptr` 或 `GDExtensionObjectPtr ptr` 形式的通用内部 fallback。
- 名字必须通过现有 canonical C identifier 规则生成，并在模块内检查冲突。
- fat pointer 是 C 层面的值语义 struct：internal storage、internal parameter、return 和临时值都使用 `gdcc_<Type>_fat_ptr`
  本身，而不是 `gdcc_<Type>_fat_ptr *`；只有显式槽地址角色才使用 `gdcc_<Type>_fat_ptr *`。
- 复制 fat pointer 是复制整个 struct，不会创建新对象，也不重新捕获 `instance_id`。对象身份仍然由 `instance_id` 表达，
  own/release 生命周期合同仍由 §3.2 和 backend ownership 规则约束。

### 3.2 表示不变量

1. `instance_id` 是身份与存活性的唯一权威；RefCounted fast path 只是该权威的证明方式之一，不是第二身份源。
2. canonical null 固定为 `{ .ptr = NULL, .instance_id = 0 }`。
3. live value 必须满足：
    - `instance_id != 0`
    - 对 non-RefCounted：`godot_object_get_instance_from_id(instance_id) != NULL`
    - 对满足 ownership invariant 的 RefCounted：持有强引用本身证明 live，可不经过 ObjectDB 解析
    - live raw object 与静态对象类型兼容
4. freed value 固定表现为：
    - `instance_id != 0`
    - `godot_object_get_instance_from_id(instance_id) == NULL`
    - `ptr` 可能保留旧缓存，也可能在重新物化时为 `NULL`
5. 不允许仅根据 `ptr == NULL` 判断 null/freed。
6. 除 RefCounted fast path 外，不允许对未取得 live pointer 的 `ptr` 做以下操作：
    - 解引用
    - `_super` 链寻址
    - class/property/method 调用
    - retain/release/destroy
    - 传入 Godot raw-object API

   live pointer 的取得方式：
    - non-RefCounted，或 unknown 但运行时 reference bit 未命中：按 ID 重新验证。
    - 静态 RefCounted，或 unknown 且运行时 reference bit 命中：在 ownership invariant 成立时直接使用 cached `ptr`。
7. 同静态类型的复制必须原样复制 `ptr` 与 `instance_id`，不得重新捕获 ID。
8. upcast 或 GDCC/engine 表示转换必须保留原 ID，并从 ID 解析出的 live raw pointer 构造目标 `ptr`；RefCounted fast
   path 下可使用已证明 live 的 source cached pointer 构造目标 `ptr`。
9. RefCounted fast path 仅适用于当前 fat pointer 持有强引用，或其 borrowed source 仍持有强引用的情形；释放、move-out、
   overwrite 或 canonical null 化之后不得再使用旧 cached `ptr`。
10. Godot 4.5.1 的 ObjectID 最高位（bit 63）是 RefCounted reference bit；GDCC 只把它作为动态 RefCounted 快速路径依据，
    不作为对象身份，也不作为 non-RefCounted liveness 证据。

### 3.3 Wrapper 实例布局保持不变

```c
struct Player {
    BasePlayer _super;
    /* fields */
};

struct RootScriptClass {
    GDExtensionObjectPtr _object;
    /* fields */
};
```

- `_super` / `_object` 是 GDCC class instance layout，不是 GDScript 对象值表示。
- `p_instance`、instance binding、create/free/notification callback 仍属于 raw GDExtension ABI。
- 将 root `_object` 改为胖指针会混淆 instance layout 与 value representation，并扩大 create/free 生命周期风险。

### 3.4 RefCounted liveness fast path

Godot 4.5.1 的 `ObjectID` 使用 bit 63（`OBJECTDB_REFERENCE_BIT`）标记 RefCounted 对象。RefCounted 对象在引用计数
大于 0 时不会被释放，因此只要 GDCC 当前 fat pointer 持有强引用，持有 pointer 即证明存活。

静态策略：

- `RefCountedStatus.YES`：跳过 `godot_object_get_instance_from_id(...)`。null 仅由 `instance_id == 0` 判断；非零 ID 的
  cached `ptr` 可直接使用。
- `RefCountedStatus.NO`：必须按 ID 通过 ObjectDB 验证；不得使用 cached `ptr` 作为 live 证据。
- `RefCountedStatus.UNKNOWN`：运行时检查 `instance_id & GDCC_OBJECT_ID_REFERENCE_BIT`。命中时按 RefCounted fast path
  处理；未命中时按 non-RefCounted 路径做 ObjectDB 验证。

适用前提：

- fat pointer 所在 slot/value 已经完成应有的 retain，或其 borrowed source 在作用域内仍持有强引用。
- 对同一 owning fat pointer 的释放、move-out、overwrite 或 canonical null 化之后，旧 cached `ptr` 立即失效。
- fast path 不得用于已明确释放但仍被错误保留的 stale RefCounted ID；这类状态属于 ownership 合同违约，应通过测试和
  sanitizer 发现，而不是通过运行时 ObjectDB 检查掩盖。

### 3.5 解引用守卫指令 `assert_object_live`

只有"解引用前必须 live，否则 hard-fail"的路径使用显式 LIR 指令：

```
assert_object_live $<object_id:Object>
```

语义：

- 无返回值。
- 若对象按 §3.4 策略 live，则顺序 fallthrough。
- 若对象为 null/freed，则进入当前函数的稳定 runtime error / default-return cleanup。
- 该指令不 retain/release/destroy，不改变对象状态，也不需要 lifecycle provenance。
- 它只用于 method receiver、property receiver、`_super` 链访问、direct GDCC field/method owner 等硬失败守卫。
- 用户条件判断、equality、own/release/destroy、Variant pack/unpack 不使用该指令。

C lowering 合同：

- 必须使用通用无类型 helper：`gdcc_object_is_null_raw_and_id(raw, instance_id)`。
- **禁止**从 raw pointer 调用 `godot_object_get_instance_id(...)` 来"恢复"ID。
- **禁止**为每个类生成单独的胖指针 assert helper。

后续优化 pass 可以把 `assert_object_live` 作为显式守卫进行合并/删除，但必须建模其 implicit error edge：

- 合并同一变量定义点支配的重复 assert；LIR 非 SSA，必须追踪变量重定义。
- 仅在证明没有 release/free/overwrite/Godot side-effect 边界时 hoist/删除。
- 对静态 `RefCountedStatus.YES` 可降级为 `instance_id != 0` 守卫。
- 对 null literal 可直接进入 hard-fail 或按语言语义处理。

---

## 4. 类型收集与声明

### 4.1 ObjectFatPtrSpec

`ObjectFatPtrSpec`（`gd.script.gdcc.backend.c.gen.fatptr.ObjectFatPtrSpec`）为 immutable record，包含：

- `GdObjectType objectType`
- `canonicalClassName`：opaque identity，直接等于 `GdObjectType.getTypeName()`
- `cIdentifier`：`GodotBindingSupport.cIdentifier(canonicalClassName)`（非法字符 → `_`，连续下划线折叠为单个 `_`）
- `fatPtrTypeName`：`gdcc_<cIdentifier>_fat_ptr`
- `pointerCType`：engine 为 `godot_<canonicalClassName> *`，GDCC 为 `<canonicalClassName> *`
- `ENGINE` / `GDCC` 分类
- `RefCountedStatus`（来自 `ClassRegistry.getRefCountedStatus(...)`）
- 对 GDCC 类型可用的 `<canonicalClassName>_object_ptr` helper name

#### 4.1.1 canonical vs C identifier（inner class 合同）

| 字段 / 产物 | 形式 | 示例 | 用途 |
|---|---|---|---|
| `canonicalClassName` | 保留 `__sub__` | `Outer__sub__Inner` | Godot 注册/元数据、GDCC wrapper struct、`_object_ptr` 等 identity surface |
| `cIdentifier` | 折叠为 `_sub_` | `Outer_sub_Inner` | 必须作为合法 C 标识符的符号 |
| `fatPtrTypeName` | 基于 `cIdentifier` | `gdcc_Outer_sub_Inner_fat_ptr` | fat pointer typedef |
| upcast helper | 两侧都用 `cIdentifier` | `gdcc_Outer_sub_Child_fat_ptr_upcast_to_Outer_sub_GrandParent` | `ObjectFatPtrUpcastSpec.forPair` 与 call site |

强制约束：

- `ObjectFatPtrUpcastSpec.forPair` 与 `CBodyBuilder.renderObjectFatPtrUpcastHelperName` **必须**同用 `cIdentifier` 拼 helper 名。
- 禁止在 call site 使用 `canonicalClassName` 拼 upcast helper。
- 更广的 class-name 合同见 `doc/module_impl/frontend/gdcc_facing_class_name_contract.md` §2.4。

### 4.2 模块级收集范围

收集器（`CObjectFatPtrCollector`）必须递归扫描所有会影响 generated C surface 的 `GdType`：

- class 的 `self` 类型
- instance properties
- function parameters 和 returns
- function variables 和 temporaries
- captures
- property init functions
- default-value functions
- registered method binding data
- 已收集 exact engine method 的 owner、parameters 和 return
- 已收集 singleton/constructor binding 的 object return
- nested typed Array/Dictionary 的 object leaf

结果必须去重并按稳定 C type name 排序，保证 generated C 可复现。

### 4.3 未识别类型 fail-fast

`GdObjectType` 若既不是 registry 中的 engine class，也不是 GDCC class，必须在 type collection 或 C type rendering
阶段抛出明确错误：

- 输出缺失的 canonical type name。
- 输出发现该类型的 surface。
- 不得退化为 `GDExtensionObjectPtr`。

### 4.4 `entry.h` 声明顺序

1. Godot/runtime includes 和 `class_library`。
2. GDCC wrapper struct forward declarations。
3. 所有 generated fat pointer typedef（由独立头文件 `object_fat_ptr_types.h` 承载）。
4. GDCC wrapper struct definitions；object fields 使用 fat pointer。定义按模块继承拓扑
   base-before-derived（`inheritanceOrderedClassDefs`）：非根 wrapper 以值嵌入父 wrapper
   `Parent _super`，父 struct 定义必须先完整，详见
   `explicit_c_inheritance_layout_contract.md` §7。
5. `<Class>_object_ptr(...)` 等 raw layout helper declarations。
6. per-type fat pointer helper declarations/definitions。
7. `engine_method_binds.h`。
8. internal function declarations 和 registered binding wrappers。

---

## 5. 类型渲染职责

对象改为 struct 后不能继续依赖"对象本身已经是指针"这一隐式假设。角色划分：

- internal storage type：对象为 `gdcc_<Type>_fat_ptr`
- internal parameter type：对象按值传递 `gdcc_<Type>_fat_ptr`
- internal storage address type：对象槽地址为 `gdcc_<Type>_fat_ptr *`
- Godot ptrcall slot type：对象为 raw `godot_<Type> *` 或 `GDExtensionObjectPtr`
- Godot receiver type：`GDExtensionObjectPtr`
- non-object value-semantic argument type：继续按现有 pointer-to-storage 规则

内部对象角色必须保持值语义；raw ABI 角色不得被 fat-pointer 值语义反向影响。

`PtrKind` 枚举（`CBodyBuilder`）表达实际 value representation：`FAT_PTR` / `RAW_PRODUCER` / `NON_OBJECT`。
具体 static pointer flavor 由 `GdObjectType/ObjectFatPtrSpec` 决定。

---

## 6. Runtime 与 generated helper 合同

### 6.1 通用 ID helper

`gdcc_helper.h` 中仅处理 ID/raw pointer 的 helper：

```c
#define GDCC_OBJECT_ID_REFERENCE_BIT (UINT64_C(1) << 63)

static inline godot_bool gdcc_object_id_is_ref_counted(GDObjectInstanceID instance_id) __attribute__((const));
static inline GDExtensionObjectPtr gdcc_object_live_ptr(GDObjectInstanceID instance_id) __attribute__((pure));
static inline godot_bool gdcc_object_is_live(GDObjectInstanceID instance_id) __attribute__((pure));
static inline godot_bool gdcc_object_is_null(GDObjectInstanceID instance_id) __attribute__((pure));
static inline godot_bool gdcc_object_live_ptrs_equal(GDExtensionObjectPtr left, GDExtensionObjectPtr right)
        __attribute__((const));
static inline godot_bool gdcc_object_is_null_raw_and_id(
        GDExtensionObjectPtr raw,
        GDObjectInstanceID instance_id) __attribute__((pure));
static inline GDObjectInstanceID gdcc_object_id_from_raw(GDExtensionObjectPtr raw);
```

语义：

- `gdcc_object_id_is_ref_counted(...)` 只检查 reference bit，不访问 ObjectDB。
- `gdcc_object_live_ptr(0)` 返回 `NULL`；非零 non-RefCounted ID 通过 `godot_object_get_instance_from_id(...)` 解析。
- `gdcc_object_is_null_raw_and_id(raw, instance_id)` 是 fat-pointer 世界的 null/freed 查询与 `assert_object_live`
  通用入口：`raw == NULL` 或 `instance_id == 0` 视为 null；否则用 ID 做 liveness 查询；**绝不**对 raw 调用
  `godot_object_get_instance_id(...)`。
- `gdcc_object_live_ptrs_equal(...)` 直接比较两个 raw Godot object pointer；`NULL == NULL` 为 true。
- `gdcc_object_id_from_raw(raw)` **仅**用于调用者已保证 raw live 的 capture 路径。

危险路径（明确禁止）：

- 不得提供或使用从裸指针恢复 ID 再验证存活的 helper（如 `gdcc_object_is_null_from_raw`）。
- 若对象已不存活，强制 `godot_object_get_instance_id(raw)` 会使程序崩溃。

属性合同：

- 只有无副作用的查询 helper 可标注 `pure`/`const`；`from_raw`、`from_variant`、`to_variant`、own/release/destroy 不得标注。
- 该安全性依赖 C 编译器对非 `pure`/`const` 函数调用的保守处理。
- 若目标工具链不支持属性，应通过宏降级为空，不得改变语义。

### 6.2 Per-type helper

每个 `ObjectFatPtrSpec` 至少需要以下能力：

- null 构造
- live raw Godot pointer -> fat pointer（`from_raw`）
- Variant -> fat pointer（`from_variant`）
- fat pointer -> validated raw Godot pointer（`live_object`）
- fat pointer -> validated typed pointer（`live_ptr`）
- source static type -> target static type upcast
- fat pointer -> Variant（`to_variant`）

Helper naming surface：

```c
gdcc_Node_fat_ptr gdcc_Node_fat_ptr_from_raw(GDExtensionObjectPtr raw);
gdcc_Node_fat_ptr gdcc_Node_fat_ptr_from_variant(const godot_Variant *value);
GDExtensionObjectPtr gdcc_Node_fat_ptr_live_object(gdcc_Node_fat_ptr value);
godot_Node *gdcc_Node_fat_ptr_live_ptr(gdcc_Node_fat_ptr value);
godot_Variant gdcc_Node_fat_ptr_to_variant(gdcc_Node_fat_ptr value);
```

per-type live helper 按静态 `RefCountedStatus` 特化：

- `YES`：非零 ID 时直接使用 cached pointer；不调用 ObjectDB。
- `NO`：必须先按 ID 解析 raw Godot object。
- `UNKNOWN`：运行时先检查 reference bit；命中时按 `YES` 路径，未命中时按 `NO` 路径。

### 6.3 从 raw pointer 捕获

`from_raw(...)` 的固定顺序：

1. 若 raw 为 `NULL`，返回 canonical null。
2. 在 raw 保证 live 时调用 `godot_object_get_instance_id(raw)`。
3. 构造静态类型 pointer cache。
4. 返回 fat pointer。

### 6.4 从 Variant 捕获

`from_variant(...)` 的固定顺序：

1. 确认 Variant type 为 OBJECT 的检查继续由现有 type gate 承担。
2. 先调用 `godot_variant_get_object_instance_id(value)` 保存 ID。
3. helper 自身仍做防御检查：`value == NULL`、Variant type 非 OBJECT 或读取到 ID 0 时返回 canonical null。
4. 使用 ID 解析 live raw pointer；不要对 Variant 解出的 pointer 再调用 `godot_object_get_instance_id(...)`。
5. live 时构造 typed pointer cache；freed 时 pointer cache 为 `NULL`。
6. 返回保留 Variant 原 ID 的 fat pointer。

### 6.5 Fat ref -> Variant 的 ABI 限制

Godot 4.5.1 public GDExtension ABI 没有提供"以任意 instance ID 构造 OBJECT Variant"的接口。因此：

- live fat pointer：使用 validated raw pointer 构造 OBJECT Variant。
- canonical null：构造 OBJECT/null Variant。
- freed fat pointer：也只能安全构造 OBJECT/null Variant，outbound Variant 中 ID 变为 0。
- 禁止把 stale cached pointer 传给 Variant constructor。
- 禁止写入 Godot private `Variant::ObjData`。

该降级保持 `== null` / `!= null`、object truthiness/nullness、避免 use-after-free；但在该 outbound Variant 边界之后
无法保持原 freed instance ID、`<Freed Object>` string/debug representation、依赖旧 ID 的反射行为。

---

## 7. CBodyBuilder 中心合同

### 7.1 默认值与初始化

所有对象默认值使用 compound literal 或 zero initialization：

```c
gdcc_Node_fat_ptr value = { 0 };
value = (gdcc_Node_fat_ptr){ 0 };
```

覆盖：local declarations、temporary declarations、`_return_val`、`LiteralNullInsn`、runtime hard-fail default return、
moved-return source clear、discarded result temp、property first-write initialization。

### 7.2 对象槽位写入

`emitObjectSlotWrite(...)` 的顺序：

1. capture old fat pointer（完整 struct）
2. 把 RHS 转成 target static fat pointer（保留 `instance_id`）
3. struct assignment
4. RHS 为 `BORROWED` 时 retain validated live raw object
5. release old fat pointer 对应的 validated live raw object

own/release 使用 §11.2 取得的 live raw pointer；non-RefCounted 不使用未验证 cached `ptr`，RefCounted fast path 可使用
cached `ptr`。self-assignment 和 alias-safe 顺序继续保持。

### 7.3 Cast 与 upcast

Representation-only upcast（`valueOfCastedVar` / generated `_upcast_to_*` helper）在 backend 已证明 assignable 时使用：

- 同一静态对象类型：直接复制 struct。
- GDCC child -> GDCC parent：保留 ID；live 时从 source wrapper 走显式 `_super` chain 得到 target pointer，dead 时 target
  pointer 为 `NULL`。
- engine child -> engine parent：保留 ID；live raw pointer cast 为 target engine pointer。
- GDCC -> engine ancestor：保留 ID；live raw Godot pointer cast 为 target engine pointer。
- engine/raw -> GDCC：仅在 registry 证明兼容时，通过 instance binding 构造 target wrapper pointer。
- 不允许通过 C struct cast 转换两个不同 fat-pointer 类型。

Runtime class-check cast（GDScript `as` / LIR `object_cast`）**不得**用 representation upcast 替代：

- 使用 ownership-neutral helper `gdcc_object_cast_raw_and_id` / `gdcc_object_cast_variant`（见 `gdcc_helper.h`）。
- success：validated live raw + source `instance_id` 经 target `_from_raw` 捕获；provenance 保留 source BORROWED/OWNED。
- failure/null/freed/class-mismatch：canonical null `{ptr = NULL, instance_id = 0}`。
- 禁止 `godot_object_cast_to`、`gdcc_check_variant_type_object`、plain `_fat_ptr_from_variant` 作为 class check。

### 7.4 Argument rendering

内部函数调用默认直接按值传 fat pointer。只有明确标记为 Godot/raw ABI 的 callee 才执行
`fat pointer -> live pointer acquisition -> validated GDExtensionObjectPtr`。

raw ABI 识别使用显式 allowlist（`GLOBAL_FUNCS_REQUIRE_GODOT_RAW_PTR`）加 `godot_*` 前缀 backlog。
禁止再堆新 prefix 特例；长期方向为 structured callee metadata。

### 7.5 Return 与 discard

- internal function object return type 为具体 fat pointer。
- `_return_val` 继续是 owning publish slot。
- borrowed return source retain validated live raw object。
- owned return source direct consume。
- move-return 后 source 清为对应 `{0}`。
- discarded owned fat pointer 立即按 ID release。

---

## 8. 调用与属性边界矩阵

| 边界 | 内部形状 | ABI 形状 | 适配要求 |
|---|---|---|---|
| GDCC direct function parameter/return | per-type fat pointer | 不跨 ABI | 按值传递，转换保留 ID |
| GDCC instance `self` | owner fat pointer | `p_instance` raw wrapper | owner-specific wrapper 构造 self fat pointer |
| engine exact method receiver | fat pointer | `GDExtensionObjectPtr` | 调用前取得 live pointer |
| engine exact ptrcall object arg | fat pointer | raw object pointer slot address | helper 内物化 local raw slot |
| engine exact ptrcall object return | fat pointer | raw object return slot | raw return 后立即捕获 ID |
| engine vararg fixed object arg | fat pointer | Variant | 通过 safe fat-pointer pack helper |
| engine vararg object return | fat pointer | Variant | 解包保留 Variant ID，并在销毁临时 Variant 前发布 ownership |
| dynamic Object call/property | fat pointer | raw receiver + Variant args/return | receiver 取得 live pointer；Variant 解包保留 ID |
| utility/fixed/builtin wrapper | fat pointer at caller | generated raw Godot wrapper | caller/body builder 显式适配 |
| constructor/singleton | fat pointer at caller | raw object return | live raw 返回后立即捕获 ID |
| registered `call_func` arg | fat pointer in internal function | incoming Variant | wrapper-local borrowed fat pointer |
| registered `call_func` return | owned fat pointer | outgoing Variant | pack 后消费内部 return ownership |
| registered `ptrcall` arg | fat pointer in internal function | incoming raw slot | wrapper-local borrowed fat pointer |
| registered `ptrcall` return | owned fat pointer | outgoing raw slot | validated raw pointer ownership transfer |
| create/free/notification callbacks | 不作为普通对象值 | raw GDExtension ABI | 保持现状，不改 callback signature |

### 8.1 Exact engine non-vararg helper

- helper public/internal surface 接收 fat pointer。
- 每个 object fixed arg 在 helper 内生成 raw pointer local。
- `args[]` 保存 raw pointer local 的地址。
- object return 使用 raw object pointer local 作为 ptrcall return slot。
- ptrcall 成功后把 raw return 包装成目标 fat pointer 并捕获 ID。
- ptrcall lookup/error default 返回对应 `{0}` fat pointer。

### 8.2 Exact engine vararg helper

- fixed object args 使用 per-type `to_variant(...)`。
- object return 从 raw return Variant 读取 ID。
- result helper 在销毁 raw return Variant 前必须建立 caller-owned return：
    - 对 `RefCounted`/unknown status retain live object。
    - 对 definite non-RefCounted 不增加生命周期动作。
- error path 不得销毁未初始化 raw return Variant。

### 8.3 Dynamic call/property/index

- receiver 不再直接提交未验证 cached pointer。
- 非 Variant object arg pack 使用 safe `to_variant(...)`。
- Variant object result 的 slot write 按 `BORROWED` 处理，在 source Variant 销毁前由 destination slot retain。
- object unpack 显式标记为 `BORROWED` representation read。

### 8.4 Direct GDCC property/method access

`self->field`、`receiver->_super` 等表达式必须先物化 validated typed pointer：

1. 按 §3.4 策略取得 live raw object。
2. 构造 owner-aligned typed pointer。
3. pointer 为 null 时发出稳定 runtime error 并走当前函数 default-return cleanup。
4. 仅对 validated pointer 做 field/method access。

性能约束：

- non-RefCounted validated pointer 不得跨任意 Godot call、user callback、free/destroy、retain/release 边界复用。
- 可以在已证明不包含上述失效边界的局部 region 内复用一次 non-RefCounted 验证结果。
- 对 definite RefCounted owning value，可在该 owning 变量/slot 未被 release/move-out/overwrite 期间复用 cached pointer。
- 对 method-entry `self` 的更大范围缓存只有在 callback lifetime contract 能证明 method 执行期间实例不会失效时才允许。

---

## 9. Registered method 与 virtual callback

### 9.1 BindingData 携带 owner

- `BindingData` 包含 owner class/type identity。
- instance method wrapper identity 包含 owner type；不同 owner 不共享需要 owner-specific self materialization 的 wrapper。
- static method wrapper 不接收或转发 fake `p_instance` 参数。
- internal function pointer signature 必须与真实 generated function signature 一致。

### 9.2 `call_func`

instance wrapper：

1. 从 `p_instance` 得到 owner wrapper pointer。
2. 通过 owner `<Class>_object_ptr(...)` 获得 live raw Godot object。
3. 捕获 ID 并构造 owner fat self。
4. 每个 object Variant argument 构造 borrowed fat pointer。
5. 调用 typed internal function。
6. object return pack 成 Variant。
7. pack 已建立 Variant ownership 后，对 internal OWNED return 调用 `release_object` / `try_release_object`
   （按 `RefCountedStatus`），避免 RefCounted 泄漏。

### 9.3 `ptrcall`

- object argument slot 按 raw object pointer 读取，再构造 borrowed fat pointer。
- object return 先接收 internal owned fat pointer，再把 validated raw pointer 写入 `r_return`。
- successful raw return 是 ownership transfer，不额外 release。
- dead/non-live return 安全写 `NULL`。

### 9.4 Virtual method

`class_call_virtual_with_data(...)` 必须调用 owner-aware ptrcall wrapper。wrapper name/lookup 不能只由 ABI param/return
shape 决定，否则同 signature 的不同 GDCC owner 会构造错误 self type。

---

## 10. Variant 与 operator 语义

### 10.1 Pack/Unpack

- per-type fat-pointer pack helper（`<Type>_fat_ptr_to_variant`）。
- per-type Variant -> fat-pointer helper（`<Type>_fat_ptr_from_variant`）。
- registered wrapper、dynamic call、operator evaluator、default Variant materialization 统一复用。
- object unpack helper 本身只做表示读取，产生 `BORROWED` value；是否 retain 由 destination slot 或 return publish 边界决定。

### 10.2 Object equality 与 `object_is_null`

#### Object/object equality（C1 normalized raw）

object equality 比较的是两侧 **equality-normalized raw Godot object pointer**，再做 `==` / `!=`：

1. 先用 `gdcc_object_is_null_raw_and_id(raw_sentinel, instance_id)` 判定 null ∪ freed
   （`raw_sentinel` 仅作 NULL 位型哨兵，**不解引用** wrapper）。
2. 若为 null/freed：normalized raw = `NULL`（dead 与 canonical null 在 equality 上折叠为同一空身份）。
3. 若为 live：
   - engine：normalized raw = `(GDExtensionObjectPtr)value.ptr`；
   - GDCC：normalized raw = `gdcc_<Type>_fat_ptr_live_object(value)`；**禁止**在未证明 live 时调用
     `Type_object_ptr(value.ptr)`。
4. 最后比较两个 normalized raw 的指针值。

语义推论：

- `null == null`、`freed == null`、`freedA == freedB` 均为 true（均归一为 `NULL`）。
- live 同实例 true，live 异实例 false；live 与 null/freed 为 false。
- 禁止直接比较 fat struct。
- 禁止直接比较 instance ID（equality 不以 ID 为比较键）。
- 禁止为 equality 从 raw pointer 调用 `godot_object_get_instance_id(...)`。

#### Object nullness

- fat-pointer 路径：`gdcc_object_is_null_raw_and_id(value.ptr_as_raw, value.instance_id)`
- 不得使用从 raw 恢复 ID 的路径。

### 10.3 Variant evaluate

任一 operand 已是 Variant 时继续走 `godot_variant_evaluate(...)`。非 Variant object operand 先 safe pack：

- live：OBJECT/live pointer Variant。
- null：OBJECT/null Variant。
- freed：OBJECT/null Variant，受 §6.5 公开 ABI 限制。

### 10.4 Truthiness 与条件

object condition normalization 必须使用 validated nullness，不允许把 struct 或 cached pointer 直接作为 C condition。

---

## 11. 生命周期合同

### 11.1 保持不变的合同

- fresh producer -> `OWNED`
- existing slot/parameter/property read -> `BORROWED`
- object slot write：capture old -> convert -> assign -> own borrowed new -> release old
- `_return_val` 是 publish slot，不进入普通 local cleanup
- ordinary local owning object 可 move-return
- definite non-RefCounted local 不因 scope exit 自动 destroy

### 11.2 own/release/destroy 输入

所有对象生命周期 helper 调用前都必须取得 live raw pointer：

1. 读取 fat pointer `instance_id`。
2. `RefCountedStatus.YES`：ID 非零时按 §3.4 fast path 取得 cached/live raw pointer；ID 0 时 no-op。
3. `RefCountedStatus.NO`：通过 `godot_object_get_instance_from_id(...)` 验证；null/freed 时 no-op。
4. `RefCountedStatus.UNKNOWN`：运行时 reference-bit 命中时按 RefCounted 路径，否则按 non-RefCounted 路径。
5. live 时执行现有 `own_object`、`try_own_object`、`release_object`、`try_release_object` 或 `try_destroy_object`。
6. 生命周期 helper 本身会改变 ownership/ObjectDB 状态，因此不得被标注 pure，也不得跨 helper 调用复用其输入 live pointer。

`try_*` helper 签名为 `(GDExtensionObjectPtr obj, GDObjectInstanceID instance_id)`：`obj` 是 validated live raw pointer，
`instance_id` 是 fat pointer 缓存的 ID。helper 内部用 `gdcc_object_id_is_ref_counted`（reference bit）判别 RefCounted，
不再做 ClassDB 类名查询；ID 一律来自 fat pointer，绝不从可能已释放的 `obj` 反推。精确变体 `own_object` / `release_object`
仍为单参数。

### 11.3 Variant 临时量与 ownership

易错路径合同：

- Variant -> object slot：unpack 结果是 borrowed；slot 在 Variant 销毁前 retain。
- exact vararg object return：helper 在销毁 return Variant 前发布 owned fat return。
- registered call_func object return：Variant pack 建立自己的引用后，wrapper 对 internal OWNED return
  调用 `release_object` / `try_release_object`（按 `RefCountedStatus`）消费所有权。
- object default argument Variant：pack 不消费 source fat pointer。

---

## 12. 测试锚点

### 12.1 Java codegen unit tests

关键测试类：

- `ObjectValueRepresentationCharacterizationTest`
- `ObjectValueLifecycleCharacterizationTest`
- `ObjectFatPtrDeclarationTest`
- `CGenHelperTest`
- `CBodyBuilderPhaseBTest` / `CBodyBuilderPhaseCTest`
- `CBodyBuilderAliasSafetySupportTest`
- `CAssignInsnGenTest`
- `CConstructInsnGenTest` / `CConstructInsnGenEngineTest`
- `CLoadStaticInsnGenTest`
- `CallGlobalInsnGenTest` / `CallGlobalInsnGenEngineTest`
- `CallMethodInsnGenTest` / `CallMethodInsnGenEngineTest` / `CallMethodInsnGenEngineInheritanceTest`
- `CLoadPropertyInsnGenTest` / `CStorePropertyInsnGenTest` / `LoadStorePropertyInsnGenEngineInheritanceTest`
- `CPackUnpackVariantInsnGenTest`
- `COperatorInsnGenTest` / `COperatorInsnGenEngineTest`
- `COwnReleaseObjectInsnGenTest` / `CDestructInsnGenTest`
- `CCodegenEngineMethodBindHeaderTest` / `CCodegenEngineMethodUsageSessionTest`
- `CNewDataInsnGenTest`
- `AssertObjectLiveInsnGenTest`
- `SimpleLirBlockInsnParserTest` / `SimpleLirBlockInsnSerializerTest`

每类至少覆盖：engine static type、GDCC static type、child -> parent upcast、null、live object、freed ID、
RefCounted 与 non-RefCounted、same-type copy 与 overwrite、unknown object type negative path、
static RefCounted fast path、unknown dynamic reference-bit fast path、non-RefCounted freed ID validation。

### 12.2 Generated C/header compile

- `GodotAbiHeaderCompileTest`
- `CProjectBuilderIntegrationTest`
- `FrontendLoweringToCProjectBuilderIntegrationTest`

Scanner 断言：

- internal function object parameter/return 必须是 `gdcc_<Type>_fat_ptr`。
- object field 必须是 per-type fat pointer。
- ptrcall object slot 必须是 raw pointer slot。
- raw pointer 只能出现在 allowlisted ABI/helper/layout 位置。
- static RefCounted live pointer 路径不得出现 `godot_object_get_instance_from_id`。
- 解引用硬失败守卫必须来自 `assert_object_live` lowering，并通过通用 `gdcc_object_is_null_raw_and_id(raw, id)`。
- `object_is_null` / object-vs-nil 使用 `gdcc_object_is_null_raw_and_id(raw, id)`。

### 12.3 Godot runtime integration & E2E

关键场景：

1. live engine/GDCC object 复制、传参、返回和 equality。
2. null object 与 live/freed object 比较。
3. non-RefCounted 对象被外部 `free()` 后，GDCC 仍持有 fat pointer：`ref == null` 为 true，不发生 use-after-free。
4. freed ref 经过 outbound Variant pack 后安全降级为 OBJECT/null。
5. RefCounted object 跨 assignment、return、Variant pack/unpack、registered call_func/ptrcall 后引用计数平衡。
6. constructor、singleton、exact engine ptrcall 和 vararg method 都捕获非零 ID。
7. RefCounted 对象在持有强引用期间跨 Godot call 后仍可直接访问；generated C scan 证明该路径未新增 ObjectDB lookup。

---

## 13. Deferred 能力

| 项 | 说明 |
|----|------|
| LIR assert 合并 pass | `assert_object_live` 输入表面已稳定；首次迁移不实现完整 pass pipeline |
| `godot_*` 前缀 backlog → structured callee metadata | 当前 `GODOT_RAW_ABI_PREFIX_BACKLOG` 保持；禁止再堆新 prefix 特例 |
| 跨 GDExtension boundary 保持 freed ID | 需要 Godot 新增公开 ABI；不能通过私有布局 hack 实现 |

---

## 14. 长期风险

1. cached typed pointer 在对象释放后可能悬空，任何新增 use-site 若绕过 ID validation 都会重新引入 UAF。
2. object unpack 若被误标为 `OWNED`，临时 Variant 销毁和 destination slot retain 之间会发生引用计数错误。
3. registered wrapper 若继续按 ABI shape 跨 owner 共享，会把 raw `p_instance` 包装成错误 static self type。
4. ptrcall object slot 若误用 fat struct 地址，会产生 ABI memory corruption。
5. outbound freed Variant 无法保持原 ID 是 Godot 4.5.1 public ABI 限制；维护者不得以 private layout 写入绕过。
6. header include/declaration 顺序与 usage-driven engine helper 收集相互依赖，新增 object type surface 时必须同步进入
   module collector。
7. function-name prefix 只能作为过渡 ABI 识别机制，长期应优先使用 structured binding metadata。
8. wrapper instance layout 和 object value representation 必须持续分离；不能因为二者都包含 object pointer 而重新合并。
9. non-RefCounted liveness-sensitive 操作会引入 ObjectDB lookup；高频路径只能在已证明没有失效边界的局部 region 内缓存
   validated pointer。
10. RefCounted high-bit fast path 的正确性依赖 ownership invariant；若某个 fat pointer 未 retain 却跨越 source 销毁，stale
    RefCounted ID 会被误判为 live。
11. `pure`/`const` 标注只适用于查询 helper；若误标 own/release/destroy 或把 pure 查询跨 side-effecting call 移动，会产生
    错误优化。
12. 未来 LIR assert 合并 pass 必须把 release/free/overwrite/Godot call 视为 assert invalidation 边界；LIR 非 SSA，不得仅
    按变量 ID 相同就跨边界删除守卫。
