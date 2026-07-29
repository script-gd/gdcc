# C Backend 对象值胖指针实施计划

> 本文档记录将 C backend 内部对象值从裸指针迁移为“静态类型指针 + Godot instance
> ID”胖指针的实施路线。阶段 0–4 的代码迁移与阶段 5 的文档/注释同步均已完成；
> 当前运行时与 ABI 事实以现有 backend 合同文档为准，本计划保留设计依据与 backlog。

## 文档状态

- 状态：阶段 0–4 代码已实施；阶段 5 文档与注释同步已完成（2026-07-29）
- 更新时间：2026-07-29
- Godot 对齐版本：`4.5.1-stable`
- Godot 对齐提交：`f62fdbde15035c5576dad93e586201f4d41ef0cb`
- 主要事实源：
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

## 1. 背景与问题

> 历史背景（迁移前）。阶段 0–5 完成后，内部对象值已是 per-type fat pointer；下文保留问题动机。

迁移前 backend 将 `GdObjectType` 直接渲染为裸 C 指针：

- engine 类型：`godot_Node *`
- GDCC 类型：`Player *`
- 未识别对象类型：`GDExtensionObjectPtr`

该表示无法在对象被 Godot 释放后安全保留其对象身份：

- 裸指针可能悬空，不能再安全调用 `godot_object_get_instance_id(...)`。
- 仅把悬空指针与 `NULL` 比较不能实现 Godot 的 freed-object null 语义。
- 生命周期、property、method、operator、Variant pack 等路径可能继续把悬空指针传回 Godot。
- 未识别对象类型退化为 `GDExtensionObjectPtr` 会掩盖静态类型收集缺失，并破坏“每种静态对象类型都有明确 C 表示”的目标。

Godot 4.5.1 的 `Variant::ObjData` 同时保存 `ObjectID id` 和 `Object *obj`。对象使用前通过 `ObjectDB::get_instance(id)`
验证；ID 非零但无法解析表示 previously freed。GDCC 的内部对象表示必须采用同样的核心原则：身份由 instance ID
保留，裸指针只能作为非权威缓存和 ABI 载荷。

---

## 2. 目标

### 2.1 核心目标

1. 每个可达的静态 `GdObjectType` 都生成独立 C 胖指针类型。
2. 胖指针按值携带具体静态指针和 `GDObjectInstanceID`。
3. locals、parameters、returns、properties、temporaries、default values 和内部 direct calls 统一使用胖指针。
4. 所有进入 GDExtension/Godot raw ABI 的对象值都先取得 live pointer：non-RefCounted 按 ID 验证；满足 ownership
   invariant 的 RefCounted 强引用可直接使用 cached pointer。
5. 所有从 raw ABI 进入内部表示的对象值都在对象仍存活时立即捕获 instance ID。
6. Variant 解包必须从 Variant 自身读取保存的 ID，不能从可能悬空的 pointer 重新读取。
7. 对象 `==`/`!=` 直接比较 raw Godot object pointer；object-vs-null / `object_is_null` 使用 `(raw, id)` 安全查询。
8. 保持现有 `BORROWED` / `OWNED`、对象槽位写入顺序和 `_return_val` 发布合同。
9. 删除未知对象类型到 `GDExtensionObjectPtr` 的静默 fallback。
10. 将解引用前的硬失败存活性守卫建模为显式无返回 LIR 指令 `assert_object_live`；条件判断、equality、lifecycle、Variant
    pack/unpack 不引入额外 LIR liveness 指令。
11. 对静态或动态 RefCounted 对象启用基于 ObjectID reference bit 的免 ObjectDB 验证快速路径。
12. 为存活性与 ID 查询 helper 标注 `pure`/`const`，允许 C 编译器裁剪冗余调用，并为后续 LIR pass 合并检查保留基础。

### 2.2 非目标

- 不修改 frontend 或 LIR 的对象类型模型；新增专用 LIR 存活性查询指令不改变对象类型、ownership 或 provenance 模型。
- 不在首次迁移中实现完整的 LIR assert-merging 优化 pass；只要求硬失败守卫显式建模，为后续 pass 提供稳定输入。
- 不修改 Godot engine、GDExtension ABI 或 vendored interface layout。
- 不把 GDCC wrapper 实例本体改成胖指针。
- 不改变 `_super` 位于偏移 0 的显式继承布局。
- 不在 generated C 中读取或写入 `godot_Variant` 私有内存布局。
- 不为所有对象类型增加一个通用 `void *` 胖指针作为 fallback。
- 不新增第二套 ownership 类型系统。

---

## 3. 目标表示

### 3.1 每种静态对象类型独立生成

建议生成以下形状：

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
- 这里的“值语义”只描述 C ABI 的存储/传递方式：复制 fat pointer 是复制整个 struct，不会创建新对象，也不重新捕获
  `instance_id`。对象身份仍然由 `instance_id` 表达，own/release 生命周期合同仍由第 3.2 节和 backend ownership
  规则约束。

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

以下布局继续保留：

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

原因：

- `_super` / `_object` 是 GDCC class instance layout，不是 GDScript 对象值表示。
- `p_instance`、instance binding、create/free/notification callback 仍属于 raw GDExtension ABI。
- 将 root `_object` 改为胖指针会混淆 instance layout 与 value representation，并扩大 create/free 生命周期风险。

### 3.4 RefCounted liveness fast path

Godot 4.5.1 的 `ObjectID` 使用 bit 63（`OBJECTDB_REFERENCE_BIT`）标记 RefCounted 对象；`GDObjectInstanceID` 在 GDExtension
ABI 中保留该位。RefCounted 对象在引用计数大于 0 时不会被释放，因此只要 GDCC 当前 fat pointer 持有强引用，持有 pointer 即证明存活。

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

只有“解引用前必须 live，否则 hard-fail”的路径使用显式 LIR 指令：

```
assert_object_live $<object_id:Object>
```

语义：

- 无返回值。
- 若对象按第 3.4 节策略 live，则顺序 fallthrough。
- 若对象为 null/freed，则进入当前函数的稳定 runtime error / default-return cleanup。
- 该指令不 retain/release/destroy，不改变对象状态，也不需要 lifecycle provenance。
- 它只用于 method receiver、property receiver、`_super` 链访问、direct GDCC field/method owner 等硬失败守卫。
- 用户条件判断、equality、own/release/destroy、Variant pack/unpack 不使用该指令；它们分别由 `object_is_null`、operator
  lowering、lifecycle helper、conversion helper 内部处理。

C lowering 合同：

- 必须使用通用无类型 helper，同时传入 cached raw Godot object pointer 与 `instance_id`：
  `gdcc_object_is_null_raw_and_id(raw, instance_id)`。
- **禁止**从 raw pointer 调用 `godot_object_get_instance_id(...)` 来“恢复”ID；对象已释放时该调用是 use-after-free。
- **禁止**为每个类生成单独的胖指针 assert helper；本路径不需要编译时特化，也不追求编译器进一步优化。
- 阶段 2 在 ordinary value 仍为 legacy raw pointer 时，C lowering 只能 hard-fail `raw == NULL`；完整 `(raw, id)`
  路径在阶段 3 fat pointer cutover 后接入。

backend generator helper（例如 `CBodyBuilder`/`CGenHelper` 中需要 validated receiver/owner 的路径）必须统一请求该指令，
不得各自拼接 ad-hoc liveness branch，也不得绕过统一入口直接调用 helper。

后续优化 pass 可以把 `assert_object_live` 作为显式守卫进行合并/删除，但必须建模其 implicit error edge：

- 合并同一变量定义点支配的重复 assert；LIR 非 SSA，必须追踪变量重定义。
- 仅在证明没有 release/free/overwrite/Godot side-effect 边界时 hoist/删除。
- 对静态 `RefCountedStatus.YES` 可降级为 `instance_id != 0` 守卫。
- 对 null literal 可直接进入 hard-fail 或按语言语义处理。

首次迁移不要求实现完整 pass pipeline，但不得把硬失败守卫重新内联到不透明 C helper call 中，以免阻断后续优化。

---

## 4. 类型收集与声明顺序

### 4.1 ObjectFatPtrSpec

在 Java backend 增加一个简单 immutable spec，至少包含：

- `GdObjectType objectType`
- `canonicalClassName`：opaque identity，直接等于 `GdObjectType.getTypeName()`  
  （inner class 保留 `Outer__sub__Inner`）
- `cIdentifier`：`GodotBindingSupport.cIdentifier(canonicalClassName)`  
  （非法字符 → `_`，**连续下划线折叠为单个 `_`**，因此 `Outer__sub__Inner` → `Outer_sub_Inner`）
- `fatPtrTypeName`：`gdcc_<cIdentifier>_fat_ptr`
- `pointerCType`：engine 为 `godot_<canonicalClassName> *`，GDCC 为 `<canonicalClassName> *`
- `ENGINE` / `GDCC` 分类
- `RefCountedStatus`（`YES` / `NO` / `UNKNOWN`，来自现有 `ClassRegistry.getRefCountedStatus(...)`）
- 对 GDCC 类型可用的 `<canonicalClassName>_object_ptr` helper name

spec 不承担 ownership，也不保存运行时值。

#### 4.1.1 canonical vs C identifier（inner class 必读）

`ObjectFatPtrSpec` 同时携带两套名字，职责不得混用：

| 字段 / 产物 | 形式 | 示例 | 用途 |
|---|---|---|---|
| `canonicalClassName` | 保留 `__sub__` | `Outer__sub__Inner` | Godot 注册/元数据、GDCC wrapper struct、`_object_ptr` 等 identity surface |
| `cIdentifier` | 折叠为 `_sub_` | `Outer_sub_Inner` | 必须作为合法 C 标识符的符号 |
| `fatPtrTypeName` | 基于 `cIdentifier` | `gdcc_Outer_sub_Inner_fat_ptr` | fat pointer typedef |
| upcast helper | 两侧都用 `cIdentifier` | `gdcc_Outer_sub_Child_fat_ptr_upcast_to_Outer_sub_GrandParent` | `ObjectFatPtrUpcastSpec.forPair` 与 call site |

强制约束：

- `ObjectFatPtrUpcastSpec.forPair` 与 `CBodyBuilder.renderObjectFatPtrUpcastHelperName` **必须**同用 `cIdentifier` 拼 helper 名。
- 禁止在 call site 使用 `canonicalClassName` 拼 upcast helper（否则声明为 `_sub_`、调用为 `__sub__`，C 编译失败）。
- 单元测试期望 fat pointer / upcast 符号时，应对齐 `_sub_`，不要把 canonical `__sub__` 抄进 C 符号断言。
- 更广的 class-name 合同见 `doc/module_impl/frontend/gdcc_facing_class_name_contract.md` §2.4。

### 4.2 模块级收集范围

收集器必须递归扫描所有会影响 generated C surface 的 `GdType`：

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
- nested typed Array/Dictionary 的 object leaf，避免未来 type renderer 扩展时漏声明

结果必须去重并按稳定 C type name 排序，保证 generated C 可复现。

### 4.3 未识别类型 fail-fast

`GdObjectType` 若既不是 registry 中的 engine class，也不是 GDCC class，必须在 type collection 或 C type rendering
阶段抛出明确错误：

- 输出缺失的 canonical type name。
- 输出发现该类型的 surface，例如 parameter、return、property 或 helper signature。
- 不得退化为 `GDExtensionObjectPtr`。

### 4.4 `entry.h` 声明顺序

`entry.h.ftl` 应调整为：

1. Godot/runtime includes 和 `class_library`。
2. GDCC wrapper struct forward declarations。
3. 所有 generated fat pointer typedef。
4. GDCC wrapper struct definitions；object fields 此时使用 fat pointer。
5. `<Class>_object_ptr(...)` 等 raw layout helper declarations。
6. per-type fat pointer helper declarations/definitions。
7. `engine_method_binds.h`。
8. internal function declarations和 registered binding wrappers。

这样可以同时满足：

- wrapper fields 使用已声明的 fat pointer。
- fat pointer 的 GDCC pointer 可以指向 incomplete wrapper type。
- exact engine helpers 能使用 fat pointer。
- 需要 `_super` 完整布局的 upcast helper 在 wrapper definition 后生成。

依赖说明：

- `class_library` 和 `gdcc_helper.h` 在步骤 1 已可用。
- `engine_method_binds.h` 后移后，其内部对 `class_library`、Godot constructor API 和 binding lookup failure helper 的依赖仍由步骤
  1 满足。
- 新增依赖仅是步骤 3 的 fat-pointer typedef 和步骤 6 的 per-type helper，因此不能把该 include 提前到它们之前。

---

## 5. 类型渲染职责拆分

当前 `renderGdTypeInC(...)`、`renderGdTypeRefInC(...)` 和 `renderValueRef(...)` 同时承担 storage、internal call 和 raw ABI
角色。对象改为 struct 后不能继续依赖“对象本身已经是指针”这一隐式假设。

实施时应至少明确以下角色：

- internal storage type：对象为 `gdcc_<Type>_fat_ptr`
- internal parameter type：对象按值传递 `gdcc_<Type>_fat_ptr`
- internal storage address type：对象槽地址为 `gdcc_<Type>_fat_ptr *`
- Godot ptrcall slot type：对象为 raw `godot_<Type> *` 或 `GDExtensionObjectPtr`
- Godot receiver type：`GDExtensionObjectPtr`
- non-object value-semantic argument type：继续按现有 pointer-to-storage 规则

内部对象角色必须保持值语义：storage、parameter、return/temporary 都是 `gdcc_<Type>_fat_ptr` 本身；只有
internal storage address 是 `gdcc_<Type>_fat_ptr *`。raw ABI 角色不得被 fat-pointer 值语义反向影响：Godot ptrcall slot 和
receiver 仍使用 raw object pointer。

可以保留现有 public method name，但必须把所有调用点按角色审核；不能让一个 `renderGdTypeRefInC(...)` 同时表示“对象按值参数”和“对象槽地址”。

---

## 6. Runtime 与 generated helper 合同

### 6.1 通用 ID helper

`gdcc_helper.h` 中增加仅处理 ID/raw pointer 的 helper：

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

- `gdcc_object_id_is_ref_counted(...)` 只检查 Godot 4.5.1 ObjectID reference bit，不访问 ObjectDB。
- `gdcc_object_live_ptr(0)` 返回 `NULL`；非零 non-RefCounted ID 通过 `godot_object_get_instance_from_id(...)` 解析。
- `gdcc_object_is_live(...)`：ID 0 为 false；reference bit 命中时在 RefCounted ownership invariant 下为 true；否则返回
  validated live pointer 是否非 null。
- `gdcc_object_is_null(instance_id)` 是 ID-only 的 `gdcc_object_is_live(...)` 语义反值。
- `gdcc_object_is_null_raw_and_id(raw, instance_id)` 是 fat-pointer 世界的 null/freed 查询与 `assert_object_live`
  通用入口：
  - 同时接收 untyped raw pointer 与 instance ID；
  - `raw == NULL` 或 `instance_id == 0` 视为 null；
  - 否则用 ID 做 liveness 查询；
  - **绝不**对 raw 调用 `godot_object_get_instance_id(...)`。
- `gdcc_object_live_ptrs_equal(...)` 直接比较两个 raw Godot object pointer；`NULL == NULL` 为 true。object equality
  使用该语义（或等价的 C `==`/`!=`），不检查存活，也不比较 instance ID。
- `gdcc_object_id_from_raw(raw)` **仅**用于调用者已保证 raw live 的 capture 路径（例如 `from_raw`）。对已释放 raw
  调用会 use-after-free；**禁止**用它实现 null/equality/assert。

危险路径（明确禁止）：

- 不得提供或使用 `gdcc_object_is_null_from_raw(raw)` / `gdcc_object_equal_live_from_raw(raw, raw)` /
  `gdcc_object_validated_live_ptr_from_raw(raw)` 这类“从裸指针恢复 ID 再验证存活”的 helper。
- 若对象已不存活，强制 `godot_object_get_instance_id(raw)` 会使程序崩溃；该路径无用且危险。

属性合同：

- 只有无副作用的查询 helper 可标注 `pure`/`const`；`from_raw`、`from_variant`、`to_variant`、own/release/destroy 不得标注。
- `pure` 只允许 C 编译器在无可观察副作用且无可能改变 ObjectDB 状态的调用之间裁剪冗余查询；不得据此把检查跨 Godot call、
  free/destroy、retain/release 或用户回调移动。
- 该安全性依赖 C 编译器对非 `pure`/`const` 函数调用的保守处理：任何 Godot API、own/release/destroy 或用户回调调用
  （均不标注 `pure`）会阻断编译器对 `pure` 查询的 CSE。实施时必须确保所有 side-effecting helper 和外部 Godot 函数均未被
  误标 `pure`/`const`。
- 若目标工具链不支持属性，应通过宏降级为空，不得改变语义。
- `assert_object_live` 使用通用 `(raw, id)` helper，不为每个类生成特化 helper，也不为该路径做额外编译时优化假设。

### 6.2 Per-type helper

每个 `ObjectFatPtrSpec` 至少需要以下能力：

- null 构造
- live raw Godot pointer -> fat pointer
- Variant -> fat pointer
- fat pointer -> validated raw Godot pointer
- fat pointer -> validated typed pointer
- source static type -> target static type upcast
- fat pointer -> Variant

建议 helper naming surface：

```c
gdcc_Node_fat_ptr gdcc_Node_fat_ptr_from_raw(GDExtensionObjectPtr raw);
gdcc_Node_fat_ptr gdcc_Node_fat_ptr_from_variant(const godot_Variant *value);
GDExtensionObjectPtr gdcc_Node_fat_ptr_live_object(gdcc_Node_fat_ptr value);
godot_Node *gdcc_Node_fat_ptr_live_ptr(gdcc_Node_fat_ptr value);
godot_Variant gdcc_Node_fat_ptr_to_variant(gdcc_Node_fat_ptr value);
```

per-type live helper 按静态 `RefCountedStatus` 特化：

- `YES`：非零 ID 时直接使用 fat pointer cached pointer；GDCC 类型通过 wrapper layout helper 得到 raw Godot object，engine
  类型直接 cast。不得调用 ObjectDB。
- `NO`：必须先按 ID 解析 raw Godot object；GDCC 类型再从 instance binding 获得 wrapper，engine 类型 cast。
- `UNKNOWN`：运行时先检查 reference bit；命中时按 `YES` 路径，未命中时按 `NO` 路径。

无论哪条路径，null/freed 结果都必须返回 `NULL`，且不得把 stale cached pointer 传给 Godot API。

### 6.3 从 raw pointer 捕获

`from_raw(...)` 的固定顺序：

1. 若 raw 为 `NULL`，返回 canonical null。
2. 在 raw 保证 live 时调用 `godot_object_get_instance_id(raw)`。
3. 构造静态类型 pointer cache。
4. 返回 fat pointer。

constructor、singleton、ptrcall object return、registered ptrcall argument、instance callback self 等入口都复用该合同。

### 6.4 从 Variant 捕获

`from_variant(...)` 的固定顺序：

1. 确认 Variant type 为 OBJECT 的检查继续由现有 type gate 承担。
2. 先调用 `godot_variant_get_object_instance_id(value)` 保存 ID。
3. helper 自身仍做防御检查：`value == NULL`、Variant type 非 OBJECT 或读取到 ID 0 时返回 canonical null；语义调用者仍必须保留外部
   type gate，不能依赖该防御路径吞掉类型错误。
4. 使用 ID 解析 live raw pointer；不要对 Variant 解出的 pointer 再调用 `godot_object_get_instance_id(...)`。
5. live 时构造 typed pointer cache；freed 时 pointer cache 为 `NULL`。
6. 返回保留 Variant 原 ID 的 fat pointer。

### 6.5 Fat ref -> Variant 的 ABI 限制

Godot 4.5.1 public GDExtension ABI提供：

- `variant_get_object_instance_id`
- `object_get_instance_id`
- `object_get_instance_from_id`
- Object pointer -> Variant constructor

但没有提供“以任意 instance ID 构造 OBJECT Variant”或“设置 Variant ObjData ID”的接口。因此：

- live fat pointer：使用 validated raw pointer 构造 OBJECT Variant。
- canonical null：构造 OBJECT/null Variant。
- freed fat pointer：也只能安全构造 OBJECT/null Variant，outbound Variant 中 ID 变为 0。
- 禁止把 stale cached pointer 传给 Variant constructor。
- 禁止写入 Godot private `Variant::ObjData`。

该降级仍保持以下语义：

- `== null` / `!= null`
- object truthiness/nullness
- 避免 use-after-free

但在该 outbound Variant 边界之后无法保持：

- 原 freed instance ID
- `<Freed Object>` string/debug representation
- 依赖旧 ID 的反射行为

如果未来必须跨 GDExtension boundary 保持 freed ID，需要 Godot 新增公开 ABI；不能在当前 backend 中通过私有布局 hack 实现。

---

## 7. `CBodyBuilder` 中心迁移

### 7.1 表示 provenance

现有 `PtrKind.GDCC_PTR/GODOT_PTR` 同时描述“值本身是 pointer”与“pointer 指向哪种对象”。胖指针落地后应改为表达实际 value
representation，例如：

- internal fat pointer
- raw Godot pointer producer
- non-object

具体 static pointer flavor 由 `GdObjectType/ObjectFatPtrSpec` 决定，不需要再为 engine/GDCC fat pointer各建一套 ownership 规则。

### 7.2 默认值与初始化

所有对象默认值必须从 `NULL` 改为对应 compound literal 或 zero initialization：

```c
gdcc_Node_fat_ptr value = { 0 };
value = (gdcc_Node_fat_ptr){ 0 };
```

覆盖：

- local declarations
- temporary declarations
- `_return_val`
- `LiteralNullInsn`
- runtime hard-fail default return
- moved-return source clear
- discarded result temp
- property first-write initialization

`renderDefaultValueExpr(...)` 当前是无 context static helper，无法为 object 生成具体 fat-pointer compound literal。object
default rendering 应迁移到 `CGenHelper` 的 context-aware path，或显式传入 rendered object C type。

### 7.3 对象槽位写入

`emitObjectSlotWrite(...)` 的顺序保持不变：

1. capture old fat pointer
2. 把 RHS 转成 target static fat pointer
3. struct assignment
4. RHS 为 `BORROWED` 时 retain validated live raw object
5. release old fat pointer 对应的 validated live raw object

变化点：

- capture old 是完整 struct，不是裸 pointer。
- conversion 保留 `instance_id`。
- own/release 使用第 11.2 节取得的 live raw pointer；non-RefCounted 不使用未验证 cached `ptr`，RefCounted fast path 可使用
  cached `ptr`。
- self-assignment 和 alias-safe 顺序继续保持。

### 7.4 Cast 与 upcast

- 同一静态对象类型：直接复制 struct。
- GDCC child -> GDCC parent：保留 ID；live 时从 source wrapper 走显式 `_super` chain 得到 target pointer，dead 时 target
  pointer 为 `NULL`。
- engine child -> engine parent：保留 ID；live raw pointer cast 为 target engine pointer。
- GDCC -> engine ancestor：保留 ID；live raw Godot pointer cast 为 target engine pointer。
- engine/raw -> GDCC：仅在 registry 证明兼容时，通过 instance binding 构造 target wrapper pointer。
- 不允许通过 C struct cast转换两个不同 fat-pointer 类型。

### 7.5 Argument rendering

内部函数调用默认直接按值传 fat pointer。

只有明确标记为 Godot/raw ABI 的 callee 才执行：

```text
fat pointer -> live pointer acquisition -> validated GDExtensionObjectPtr
```

live pointer acquisition 按第 3.4 节选择 RefCounted fast path 或 ObjectDB ID validation。

`checkGlobalFuncRequireGodotRawPtr(...)` 当前依赖 function-name prefix。实施时应保留现有显式 provided/module-local
binding 验证，同时逐步把对象 argument ABI shape 变为结构化 metadata，避免新 helper 因名字未列入而误传 struct。

### 7.6 Return 与 discard

- internal function object return type改为具体 fat pointer。
- `_return_val` 继续是 owning publish slot。
- borrowed return source retain validated live raw object。
- owned return source direct consume。
- move-return 后 source 清为对应 `{0}`。
- discarded owned fat pointer立即按 ID release。
- non-RefCounted cleanup 规则保持现有合同。

---

## 8. 调用与属性边界矩阵

| 边界                                    | 内部形状                         | ABI 形状                             | 适配要求                                          |
|---------------------------------------|------------------------------|------------------------------------|-----------------------------------------------|
| GDCC direct function parameter/return | per-type fat pointer             | 不跨 ABI                             | 按值传递，转换保留 ID                                  |
| GDCC instance `self`                  | owner fat pointer                | `p_instance` raw wrapper           | owner-specific wrapper 构造 self fat pointer        |
| engine exact method receiver          | fat pointer                      | `GDExtensionObjectPtr`             | 调用前取得 live pointer；RefCounted fast path 见 3.4 |
| engine exact ptrcall object arg       | fat pointer                      | raw object pointer slot address    | helper 内物化 local raw slot                     |
| engine exact ptrcall object return    | fat pointer                      | raw object return slot             | raw return 后立即捕获 ID                           |
| engine vararg fixed object arg        | fat pointer                      | Variant                            | 通过 safe fat-pointer pack helper                   |
| engine vararg object return           | fat pointer                      | Variant                            | 解包保留 Variant ID，并在销毁临时 Variant 前发布 ownership  |
| dynamic Object call/property          | fat pointer                      | raw receiver + Variant args/return | receiver 取得 live pointer；Variant 解包保留 ID      |
| utility/fixed/builtin wrapper         | fat pointer at caller            | generated raw Godot wrapper        | caller/body builder 显式适配                      |
| constructor/singleton                 | fat pointer at caller            | raw object return                  | live raw 返回后立即捕获 ID                           |
| registered `call_func` arg            | fat pointer in internal function | incoming Variant                   | wrapper-local borrowed fat pointer                |
| registered `call_func` return         | owned fat pointer                | outgoing Variant                   | pack 后消费内部 return ownership                   |
| registered `ptrcall` arg              | fat pointer in internal function | incoming raw slot                  | wrapper-local borrowed fat pointer                |
| registered `ptrcall` return           | owned fat pointer                | outgoing raw slot                  | validated raw pointer ownership transfer      |
| create/free/notification callbacks    | 不作为普通对象值                     | raw GDExtension ABI                | 保持现状，不改 callback signature                    |

### 8.1 Exact engine non-vararg helper

当前 helper 对 object 参数执行 `&arg`，并直接把 object return storage 声明成 `renderGdTypeInC(returnType)`。胖指针后必须改为：

- helper public/internal surface接收 fat pointer。
- 每个 object fixed arg 在 helper 内生成 raw pointer local。
- `args[]` 保存 raw pointer local 的地址。
- object return 使用 raw object pointer local 作为 ptrcall return slot。
- ptrcall 成功后把 raw return 包装成目标 fat pointer并捕获 ID。
- ptrcall lookup/error default 返回对应 `{0}` fat pointer。

### 8.2 Exact engine vararg helper

- fixed object args 使用 per-type `to_variant(...)`。
- object return 从 raw return Variant 读取 ID。
- result helper 在销毁 raw return Variant 前必须建立 caller-owned return：
    - 对 `RefCounted`/unknown status retain live object。
    - 对 definite non-RefCounted 不增加生命周期动作。
- error path不得销毁未初始化 raw return Variant，继续遵守现有合同。

### 8.3 Dynamic call/property/index

- receiver 不再直接提交未验证 cached pointer；non-RefCounted 按 ID 验证，RefCounted fast path 可按第 3.4 节提交 cached
  pointer。
- 非 Variant object arg pack 使用 safe `to_variant(...)`。
- Variant object result的 slot write按 `BORROWED` 处理，在 source Variant 销毁前由 destination slot retain。
- `InsnGenSupport.unpackVariantAssign(...)` 不能继续让所有 unpack helper call result隐式走 `OWNED callAssign`；object
  unpack 应显式标记为 `BORROWED` representation read。

### 8.4 Direct GDCC property/method access

`self->field`、`receiver->_super` 等表达式必须先物化 validated typed pointer：

1. 按第 3.4 节策略取得 live raw object：RefCounted fast path 使用 cached pointer，non-RefCounted 或 unknown 未命中时按 ID
   查找。
2. 构造 owner-aligned typed pointer。
3. pointer 为 null 时发出稳定 runtime error 并走当前函数 default-return cleanup。
4. 仅对 validated pointer 做 field/method access。

getter/setter self fast path也必须使用该流程，不能直接改成 `$self.ptr->field`。

性能约束：

- non-RefCounted validated pointer 不得跨任意 Godot call、user callback、free/destroy、retain/release 或其他可能改变
  对象存活性的边界复用。
- 可以在已证明不包含上述失效边界的局部 region 内复用一次 non-RefCounted 验证结果，例如连续 backing-field 访问。
- 对 definite RefCounted owning value，可在该 owning 变量/slot 未被 release/move-out/overwrite 期间复用 cached
  pointer，因为强引用本身阻止释放。
- 对 unknown value，一旦运行时 reference-bit 证明为 RefCounted，适用 RefCounted 规则；否则适用 non-RefCounted 规则。
- 对 method-entry `self` 的更大范围缓存只有在 callback lifetime contract 能证明 method 执行期间实例不会失效时才允许；
  RefCounted self 可基于强引用合同放宽，non-RefCounted self 必须单独测试。

---

## 9. Registered method 与 virtual callback

### 9.1 `BindingData` 必须携带 owner

当前 binding wrapper 只按 parameter/return/static shape 共享，function pointer首参数固定写成 `void *`，无法为 instance
method构造静态 owner fat `self`。

计划：

- `BindingData` 增加 owner class/type identity。
- instance method wrapper identity包含 owner type；不同 owner 不再共享需要 owner-specific self materialization 的
  wrapper。
- static method wrapper不接收或转发 fake `p_instance` 参数。
- internal function pointer signature必须与真实 generated function signature一致，禁止继续依赖不兼容 function pointer
  cast。

### 9.2 `call_func`

instance wrapper：

1. 从 `p_instance` 得到 owner wrapper pointer。
2. 通过 owner `<Class>_object_ptr(...)` 获得 live raw Godot object。
3. 捕获 ID并构造 owner fat self。
4. 每个 object Variant argument构造 borrowed fat pointer。
5. 调用 typed internal function。
  6. object return pack 成 Variant。
  7. pack 已建立 Variant ownership 后，对 internal OWNED return 调用 `release_object` /
     `try_release_object`（按 `RefCountedStatus`；`CGenHelper.renderCallWrapperOwnedObjectReturnConsumeStmt`），
     避免 RefCounted 泄漏。

### 9.3 `ptrcall`

- object argument slot按 raw object pointer读取，再构造 borrowed fat pointer。
- object return先接收 internal owned fat pointer，再把 validated raw pointer写入 `r_return`。
- successful raw return是 ownership transfer，不额外 release。
- dead/non-live return安全写 `NULL`。
- non-object storage-pointer规则保持现状。

### 9.4 Virtual method

`class_call_virtual_with_data(...)` 必须调用 owner-aware ptrcall wrapper。wrapper name/lookup不能只由 ABI param/return
shape决定，否则同 signature 的不同 GDCC owner会构造错误 self type。

---

## 10. Variant 与 operator 语义

### 10.1 Pack/Unpack

- `renderPackFunctionName(GdObjectType)` 改为 per-type fat-pointer pack helper。
- `renderUnpackFunctionName(GdObjectType)` 改为 per-type Variant -> fat-pointer helper。
- registered wrapper、dynamic call、operator evaluator、default Variant materialization统一复用。
- object unpack helper本身只做表示读取，产生 `BORROWED` value；是否 retain由 destination slot或 return publish边界决定。

### 10.2 Object equality 与 `object_is_null`

#### Object/object equality

object equality 比较的是两侧 **equality-normalized raw Godot object pointer**（以下简称 normalized raw），再做
`==` / `!=`：

- object/object `==`：`normalized_left == normalized_right`
- object/object `!=`：`normalized_left != normalized_right`
- 禁止直接比较 fat struct。
- 禁止直接比较 instance ID（equality 不以 ID 为比较键）。
- 禁止为 equality 从 raw pointer 调用 `godot_object_get_instance_id(...)`。

**Normalized raw（C1 合同，阶段 3C 落地）**：

1. 先用 `gdcc_object_is_null_raw_and_id(raw_sentinel, instance_id)` 判定 null ∪ freed  
   （`raw_sentinel` 仅作 NULL 位型哨兵：engine 为 `.ptr` 强转；GDCC 为 wrapper `.ptr` 强转，**不解引用** wrapper）。
2. 若为 null/freed：normalized raw = `NULL`（dead 与 canonical null 在 equality 上折叠为同一空身份）。
3. 若为 live：
   - engine：normalized raw = `(GDExtensionObjectPtr)value.ptr`（已是 Godot raw）；
   - GDCC：normalized raw = `gdcc_<Type>_fat_ptr_live_object(value)`（或等价 live 路径）；**禁止**在未证明 live 时调用
     `Type_object_ptr(value.ptr)`。
4. 最后比较两个 normalized raw 的指针值。

语义推论（相对“无条件比 dangling address”）：

- `null == null`、`freed == null`、`freedA == freedB` 均为 true（均归一为 `NULL`）。
- live 同实例 true，live 异实例 false；live 与 null/freed 为 false。
- 为避免 GDCC freed wrapper UAF，equality **允许**在 materialize 前做 nullness/liveness 查询；该查询只服务
  “禁止解引用 dead wrapper / 折叠 dead”，**不**把 `instance_id` 当作 `==` 的比较键。

**阶段状态**：

- 阶段 2 ordinary value 为 legacy raw pointer：直接 `raw == raw`（无 ID）。
- 阶段 3A/3B 过渡实现（已废弃）：从 fat 取 cached raw 后直接比（GDCC 经 `object_ptr`），对 freed GDCC wrapper 有 UAF 风险。
- 阶段 **3C（已完成）**：production equality 切换为上述 C1 normalized raw 路径；characterization / operator 测试已锚定。

#### Object nullness

`object_is_null` 与 object/null equality 需要同时看到 raw pointer 与 instance ID：

- fat-pointer 路径：`gdcc_object_is_null_raw_and_id(value.ptr_as_raw, value.instance_id)`
- 不得使用 `gdcc_object_is_null_from_raw(raw)` 或任何从 raw 恢复 ID 的路径
- 阶段 2 ordinary value 仍为 legacy raw pointer、没有可用 ID 时，production lowering 只能退化为 `raw == NULL`；
  完整 freed 检测推迟到阶段 3 fat pointer cutover 后接入

`gdcc_cmp_object(...)` 是已删除的 legacy helper（阶段 4）；production equality / nullness 不得再依赖它。

### 10.3 Variant evaluate

任一 operand 已是 Variant 时继续走 `godot_variant_evaluate(...)`。非 Variant object operand先 safe pack：

- live：OBJECT/live pointer Variant。
- null：OBJECT/null Variant。
- freed：OBJECT/null Variant，受第 6.5 节公开 ABI 限制。

### 10.4 Truthiness 与条件

若 backend 出现 object condition normalization，必须使用 validated nullness，不允许把 struct或 cached pointer直接作为 C
condition。当前 frontend/LIR 若总是先 lower 为 bool，也应增加防御测试确保未来路径不回退到 pointer truthiness。

---

## 11. 生命周期保持与修正

### 11.1 保持不变的合同

- fresh producer -> `OWNED`
- existing slot/parameter/property read -> `BORROWED`
- object slot write：capture old -> convert -> assign -> own borrowed new -> release old
- `_return_val` 是 publish slot，不进入普通 local cleanup
- ordinary local owning object可 move-return
- definite non-RefCounted local不因 scope exit自动 destroy

### 11.2 own/release/destroy 输入

所有对象生命周期 helper 调用前都必须取得 live raw pointer：

1. 读取 fat pointer `instance_id`。
2. `RefCountedStatus.YES`：ID 非零时按第 3.4 节 fast path 取得 cached/live raw pointer；ID 0 时 no-op。
3. `RefCountedStatus.NO`：通过 `godot_object_get_instance_from_id(...)` 验证；null/freed 时 no-op。
4. `RefCountedStatus.UNKNOWN`：运行时 reference-bit 命中时按 RefCounted 路径，否则按 non-RefCounted 路径。
5. live 时执行现有 `own_object`、`try_own_object`、`release_object`、`try_release_object` 或 `try_destroy_object`。
6. 生命周期 helper 本身会改变 ownership/ObjectDB 状态，因此不得被标注 pure，也不得跨 helper 调用复用其输入 live pointer。

`try_*` helper 签名为 `(GDExtensionObjectPtr obj, GDObjectInstanceID instance_id)`：`obj` 是上一步取得的
validated live raw pointer，`instance_id` 是 fat pointer 缓存的 ID。helper 内部用 `gdcc_object_id_is_ref_counted`
（reference bit）判别 RefCounted，不再做 ClassDB 类名查询；ID 一律来自 fat pointer，绝不从可能已释放的
`obj` 反推。精确变体 `own_object` / `release_object` 仍为单参数。

### 11.3 Variant 临时量与 ownership

重点修正以下易错路径：

- Variant -> object slot：unpack结果是 borrowed；slot在 Variant销毁前 retain。
- exact vararg object return：helper在销毁 return Variant前发布 owned fat return。
- registered call_func object return：Variant pack建立自己的引用后，wrapper 对 internal OWNED return
  调用 `release_object` / `try_release_object`（按 `RefCountedStatus`）消费所有权。
- object default argument Variant：pack不消费 source fat pointer。

这些规则必须通过 RefCounted计数测试验证，不能只断言 generated C string。

---

## 12. 删除 fake object C expression

`NewDataInsnGen` 当前把 UTF-8 C literal伪装为 `GdObjectType.OBJECT`，只为绕过 value-semantic address-of规则。对象改为 fat
ref后该路径会生成无效 C。

计划：

- 在 `CBodyBuilder.ValueRef` 中增加明确的 direct C string pointer literal形状，或让现有 `CStringLiteralValue` 明确绕过
  `needsAddressOf(...)`。
- 支持生成 `u8"..."`。
- `NewDataInsnGen` 改用该专用值，不再构造 fake `GdObjectType`。
- 增加防御测试，禁止 non-object raw C expressions借用 object type影响 ABI rendering。

---

## 13. 分阶段实施顺序

### 阶段 0：Characterization tests

实施前先锁定当前关键输出和 ownership计数：

- engine/GDCC object type rendering
- object locals/properties/parameters/returns
- constructor/singleton/raw method return
- object assignment/upcast/move-return
- Variant pack/unpack
- registered call_func/ptrcall
- object equality/null comparison
- RefCounted own/release balance

验收：测试先证明当前裸 pointer输出，后续每阶段有明确需要更新的断言；不得以删除测试完成迁移。

#### 阶段 0 任务状态

| 任务 | 状态 | 锚定测试 |
|---|---|---|
| engine/GDCC/unknown object type rendering | 已完成 | `ObjectValueRepresentationCharacterizationTest.TypeRenderingBaseline` |
| object locals/parameters/returns/default null | 已完成 | `ObjectValueRepresentationCharacterizationTest.FunctionSurfaceBaseline` |
| registered call_func/ptrcall object ABI | 已完成 | `ObjectValueRepresentationCharacterizationTest.RegisteredWrapperBaseline` |
| object assignment/upcast/move-return | 已完成 | `ObjectValueLifecycleCharacterizationTest.AssignmentBaseline`、`MoveReturnBaseline` |
| constructor/singleton/raw method return | 已完成 | `ObjectValueLifecycleCharacterizationTest.ConstructorSingletonBaseline` |
| Variant pack/unpack | 已完成 | `ObjectValueLifecycleCharacterizationTest.VariantPackUnpackBaseline` |
| object equality/null comparison | 已完成 | `ObjectValueLifecycleCharacterizationTest.EqualityBaseline` |
| RefCounted own/release balance | 已完成 | `ObjectValueLifecycleCharacterizationTest.RefCountedMatrixBaseline` |

阶段 0 仅新增 characterization tests，不修改 production backend 行为。property field 的 C 类型渲染由
`TypeRenderingBaseline` 锁定；entry.h 结构体模板直接复用该 renderer。registered ptrcall object return
由 `RegisteredWrapperBaseline` 锚定；exact engine method ptrcall object return 需要完整 engine bind fixture，
其基线推迟到阶段 3D 实施前补充。

### 阶段 1：ObjectFatPtrSpec、collector 与声明

主要文件：

- `CGenHelper.java`
- `CCodegen.java`
- 新增简单 backend spec/collector class
- `entry.h.ftl`
- `object_fat_ptr_types.h.ftl`

工作：

- 建立 deterministic object-ref spec collection。
- 生成 per-type typedef。
- 未识别 object fail-fast。
- 在 ObjectFatPtrSpec 中记录 `RefCountedStatus`，来自现有 `ClassRegistry.getRefCountedStatus(...)`。
- 调整 header include/declaration顺序。
- 先增加 storage/internal-parameter/storage-address/raw-ABI 等 role-specific renderer；现有 production call site在阶段 3
  前继续显式使用 legacy raw renderer。
- fat-pointer typedef在本阶段可以尚未被 ordinary storage使用，但不得引入 generic fat fallback或让同一 renderer同时表达
  internal fat value与raw ABI slot。

验收：

- engine 与 GDCC typedef pointer字段类型正确。
- 同类型只声明一次。
- 顺序稳定。
- unknown type生成失败。
- generated header通过 `GodotAbiHeaderCompileTest`。

#### 阶段 1 任务状态

| 任务 | 状态 | 锚定测试/产出 |
|---|---|---|
| `ObjectFatPtrSpec` 与 `RefCountedStatus` 记录 | 已完成 | `ObjectFatPtrDeclarationTest.SpecTests` |
| deterministic module/engine binding collector | 已完成 | `ObjectFatPtrDeclarationTest.CollectorTests` |
| unknown object fail-fast | 已完成 | `ObjectFatPtrDeclarationTest.SpecTests`、`CollectorTests`、`HeaderDeclarationTests` |
| role-specific renderer（add-only） | 已完成 | `ObjectFatPtrDeclarationTest.RendererTests` |
| per-type typedef 声明、去重与排序 | 已完成 | `ObjectFatPtrDeclarationTest.HeaderDeclarationTests` |
| `entry.h` include/declaration 顺序调整 | 已完成 | `ObjectFatPtrDeclarationTest.HeaderDeclarationTests`、`gd.script.gdcc.backend.c.build.*` |

阶段 1 新增 `ObjectFatPtrSpec.java`、`CObjectFatPtrCollector.java`、`object_fat_ptr_types.h.ftl` 和
`ObjectFatPtrDeclarationTest.java`。fat-pointer typedef 由独立生成头文件 `object_fat_ptr_types.h` 承载，`entry.h` 只 include
该头文件，不再内联生成所有 fat-pointer typedef。ordinary object storage、function signature、registered wrapper 仍保持
legacy raw pointer；role-specific renderer 仅供后续阶段按角色接入。generated header 的 Zig 编译由现有
`gd.script.gdcc.backend.c.build.*` 集成测试覆盖。

### 阶段 2：Runtime/per-type conversion helpers

主要文件：

- `gdcc_helper.h`
- `gdcc_operator.h`
- `entry.h.ftl`
- `entry.c.ftl`

工作：

- 添加 ID validation/null/live-pointer-equality helper，并按第 6.1 节标注 `pure`/`const`。
- 添加 reference-bit fast path helper 和 per-type RefCountedStatus 特化 live pointer helper。
- 新增 LIR `assert_object_live` 指令：opcode、无返回 instruction record、parser/serializer、validator（如需要）、CInsnGen。
- backend generator helper 统一暴露请求 `assert_object_live` 的入口；本阶段可 add-only，不强制 ordinary path 使用。
- 生成 raw/Variant/fat conversion helper。
- 生成 type-specific live pointer和 upcast helper。
- helper 以 add-only 方式落地；ordinary object value仍保持旧表示，避免产生半切换 ABI。
- 为 helper 和 `assert_object_live` 增加独立 header compile、parser/serializer、C-level behavior tests。

验收：

- null/live/freed ID helper行为正确。
- object equality 直接比较 raw Godot object pointer，不检查存活、不比较 instance ID。
- `assert_object_live` LIR surface 可解析/序列化；阶段 2 C lowering 在 legacy raw 表示下对 `raw == NULL` hard-fail cleanup。
- `gdcc_object_is_null_raw_and_id(raw, id)` 通用 helper 存在，且**不**从 raw 恢复 ID。
- **不**在阶段 2 production lowering 中接入“validated helper / 从 raw 恢复 ID”路径；完整
  `object_is_null` / equality / `assert_object_live` 的 fat-pointer 语义切换推迟到阶段 3。
- static RefCounted 路径不生成 ObjectDB lookup；unknown 路径生成 reference-bit fast path。
- generated `gdcc_helper.h` 中查询 helper 的 `pure`/`const` 属性标注存在（或按工具链宏降级为空），side-effecting helper
  无标注。
- 新 helper尚未接入 production fat-pointer representation 时不改变现有 generated function/property/method ABI。

#### 阶段 2 任务状态

| 任务 | 状态 | 锚定测试/产出 |
|---|---|---|
| 通用 ID validation/null/live/equality helper 与 `pure`/`const` 属性 | 已完成 | `GodotAbiHeaderCompileTest.gdccObjectIdHelpersShouldCompileAndFollowLivenessContract` |
| 通用 `gdcc_object_is_null_raw_and_id(raw, id)` helper（不从 raw 恢复 ID） | 已完成 | `GodotAbiHeaderCompileTest.gdccObjectIdHelpersShouldCompileAndFollowLivenessContract` |
| 删除/禁止 `*_from_raw` 存活恢复 helper 作为 production 路径 | 已完成 | `gdcc_helper.h` 不再提供 `gdcc_object_is_null_from_raw` / `gdcc_object_equal_live_from_raw` / `gdcc_object_validated_live_ptr_from_raw` |
| reference-bit fast path 与 per-type RefCountedStatus live pointer helper | 已完成 | `ObjectFatPtrDeclarationTest.HelperGenerationTests`、`GodotAbiHeaderCompileTest` |
| raw/Variant/fat conversion helper 生成 | 已完成 | `ObjectFatPtrDeclarationTest.HelperGenerationTests` |
| type-specific live pointer 与 upcast helper 生成 | 已完成 | `ObjectFatPtrDeclarationTest.HelperGenerationTests` |
| LIR `assert_object_live` opcode/record/parser/serializer/CInsnGen | 已完成 | `SimpleLirBlockInsnParserTest`、`SimpleLirBlockInsnSerializerTest`、`AssertObjectLiveInsnGenTest` |
| backend generator 统一 `assert_object_live` 入口（add-only；legacy raw 仅 `raw == NULL`） | 已完成 | `CBodyBuilder.emitAssertObjectLiveGuard`、`AssertObjectLiveInsnGenTest` |
| object equality production lowering 直接 raw pointer 比较 | 已完成 | `COperatorInsnGenTest`、`ObjectValueLifecycleCharacterizationTest.EqualityBaseline` |
| `object_is_null` / object-vs-nil production lowering 在 legacy raw 下退化为 `raw == NULL` | 已完成 | `ObjectIsNullInsnGenTest`、`COperatorInsnGenTest`、`ObjectValueLifecycleCharacterizationTest.EqualityBaseline` |
| `object_is_null` / equality / assert 的 fat-pointer `(raw, id)` production 切换 | 部分完成 / equality 待 3C | null/assert 见 3B；equality C1 见阶段 3C 与 §10.2 |
| add-only 不改变现有 generated function/property/method ABI | 已完成 | `gd.script.gdcc.backend.c.*` 集成测试 |

阶段 2 新增通用 ID helper、`gdcc_object_is_null_raw_and_id` 和 generated per-type fat pointer helper；`object_fat_ptr_types.h` 承载 typedef、
per-type conversion/live/Variant helper、upcast helper 和 GDCC `object_ptr` helper forward declaration，`entry.h` 只 include
`object_fat_ptr_types.h`，不再生成任何胖指针 helper。`assert_object_live` 已完成 LIR surface 与统一 C 入口，但 ordinary
production path 仍保持 legacy raw 表示：equality 直接比较 raw pointer；`object_is_null` / object-vs-nil / assert 仅检查
`raw == NULL`。曾短暂接入的 `*_from_raw` 存活恢复路径已撤回，因其对已释放 raw 调用 `godot_object_get_instance_id` 会崩溃。
完整 `(raw, id)` null/assert 与 fat-pointer equality materialization 推迟到阶段 3。`gdcc_cmp_object` 曾保留为 legacy helper，
已在阶段 4 删除。`RefCountedStatus.UNKNOWN` 的 per-type 模板路径已就绪，当前 collector 对未知类型 fail-fast，
因此不会生成 UNKNOWN spec；通用 helper 的 reference-bit fast path 由 C behavior test 锚定。

### 阶段 3：原子 representation cutover

该阶段是唯一允许改变 internal object C ABI 的阶段。以下子步骤必须在同一 cutover gate 内全部完成，不能在只完成其中一部分时合并、发布或把
generated C 标记为可运行：

- internal storage/function signature切换会立即影响 ordinary call、exact engine helper、registered wrapper和virtual
  bridge。
- exact engine caller与 helper public signature必须同时切换。
- registered function declaration与 call_func/ptrcall/virtual adapter必须同时切换。
- 实际编辑仍按仓库规则小批次进行，但阶段验收只在全部子步骤完成后执行。

#### 3A. Internal storage、assignment、return

主要文件：

- `CGenHelper.java`
- `CBodyBuilder.java`
- `CCodegen.java`
- `func.ftl`
- `entry.h.ftl`
- `entry.c.ftl`

工作：

| 任务 | 状态 | 备注 |
|---|---|---|
| locals、fields、parameters、returns切换为 fat pointer | 已完成 | `renderGdTypeInC` / `renderGdTypeRefInC` → `gdcc_<Type>_fat_ptr`；容器元素仍为 bare raw |
| object default/null/return slot改为 `{0}` | 已完成 | `CGenHelper.renderDefaultValueExprInC`；`_return_val` / move-return clear |
| 重构 value representation metadata | 已完成 | `PtrKind`：`FAT_PTR` / `RAW_PRODUCER` / `NON_OBJECT` |
| slot write、move-return、discard、upcast保留 ID | 已完成 | `emitObjectSlotWrite` + upcast helper / `_from_raw` |
| own/release/destroy切换为 ID-validated raw pointer | 已完成 | `*_live_object` 后 `own/release/try_*` |
| wrapper instance `_object/_super`保持 raw layout | 已完成 | 未改 instance layout / `object_ptr` helpers |

#### 3B. 对象 producer 与 ordinary instructions

主要文件：

- `ConstructInsnGen.java`
- `LoadStaticInsnGen.java`
- `CallGlobalInsnGen.java`
- `CallMethodInsnGen.java`
- `LoadPropertyInsnGen.java`
- `StorePropertyInsnGen.java`
- `IndexLoadInsnGen.java`
- `IndexStoreInsnGen.java`
- `OwnReleaseObjectInsnGen.java`
- `DestructInsnGen.java`
- `PackUnpackVariantInsnGen.java`
- `InsnGenSupport.java`
- `OperatorInsnGen.java`
- `NewDataInsnGen.java`

工作：

| 任务 | 状态 | 备注 |
|---|---|---|
| 所有 raw producer立即捕获 ID | 已完成 | construct/singleton/`godot_*` → `_from_raw`；exact helper 公共面已 fat（3D），helper 内 raw return 再 `_from_raw` |
| raw receiver/owner 解引用前 `assert_object_live` | 已完成 | property/method receiver assert + exact helper `*_live_object(self)` 于 helper 体内 |
| dynamic Variant 路径 borrowed object unpack | 已完成 | `InsnGenSupport.unpackVariantAssign` → `*_from_variant` + `BORROWED` |
| direct GDCC field 访问 assert + live owner pointer | 已完成 | getter/setter self field：`_live_ptr` |
| `object_is_null` / object-vs-nil → `(raw, id)` | 已完成 | `ObjectIsNullInsnGen` / `OperatorInsnGen` |
| object/object equality 比较 cached raw | 已由 3C 取代 | 见阶段 3C C1 normalized raw |
| `assert_object_live` → `gdcc_object_is_null_raw_and_id` | 已完成 | null/assert 已不经 `object_ptr`；无 per-type assert helper |
| own/release/destroy liveness 留在 helper 内 | 已完成 | 无额外 LIR check |
| UTF-8 literal 专用 C string pointer | 已完成 | `CStringLiteralValue` + `u8"..."`；不再伪装 `GdObjectType.OBJECT` |

说明：3A–3E 对象值 fat-pointer cutover 的核心路径已落地。阶段 4 再清 unknown fallback / legacy raw 特例。

#### 3C. Object equality C1（normalized raw）

**状态：已完成**

主要文件：

- `CBodyBuilder.java`（`renderEqualityNormalizedRaw`；与 null/assert 路径分离）
- `OperatorInsnGen.java`（`emitObjectComparison`）
- `gdcc_helper.h`（`gdcc_object_live_ptrs_equal` 注释对齐：比较已归一化 raw）
- `operator_insn_implementation.md` §3.5
- `ObjectValueLifecycleCharacterizationTest` / `COperatorInsnGenTest`

工作：

| 任务 | 状态 | 备注 |
|---|---|---|
| `renderEqualityNormalizedRaw` API | 已完成 | null∪freed→`NULL`；live engine→`.ptr`；live GDCC→`*_live_object` |
| 移除 unsafe `renderCachedGodotObjectPtr` | 已完成 | equality 不再经 dead `object_ptr` |
| `emitObjectComparison` 切换 C1 | 已完成 | 两侧 normalized raw 后 `==`/`!=` |
| null/assert 路径保持分离 | 已完成 | 仍用 `renderObjectIsNullExpr` / `renderNullQueryRawOperand` |
| engine/GDCC/mixed 正反测试 | 已完成 | 锚定 is_null 门闩、live 路径、禁止 `object_ptr` / ID 比较 / raw 恢复 ID |

验收（3C 单独）：

- GDCC freed equality 不 UAF（生成码无 dead wrapper `object_ptr`）。
- `null == null`、`freed == null`、`freedA == freedB` 语义由两侧归一为 `NULL` 保证。
- live 同/异实例仍按 normalized raw 指针 identity。
- 无 per-type assert helper；不以 ID 为 `==` 比较键。

非目标（3C 不做，保持）：

- 不改 exact engine helper 公共签名（3D）。
- 不改 registered call_func/ptrcall/virtual bridge（3E）。
- 不把 fat 布局改为额外缓存 godot raw（方案 A）。
- 不采用 C2（dead 比 ID）或 hard-fail equality。

#### 3D. Exact engine caller/helper 原子切换（原 3C，后延）

**状态：已完成**

主要文件：

- `CBodyBuilder.java`（移除 `gdcc_engine_call_*` raw arg/return 门控）
- `CGenHelper.java`（helper self/object raw slot/from_raw/vararg own helpers）
- `engine_method_binds.h.ftl`
- `CCodegenEngineMethodBindHeaderTest` / `CBodyBuilderPhaseCTest` / property&method call 测试

工作：

| 任务 | 状态 | 备注 |
|---|---|---|
| helper public `self` → owner fat | 已完成 | 体内 `self_raw = *_live_object(self)` |
| object fixed arg → helper 内 raw slot | 已完成 | `argN_raw` + `args[] = &argN_raw` |
| object return → raw slot + `_from_raw` | 已完成 | 禁止把 fat storage 当地址写给 ptrcall |
| caller 不再对 `gdcc_engine_call*` 做 raw 适配 | 已完成 | fat 直接传/接 |
| vararg fixed object → `*_to_variant` | 已完成 | |
| vararg object return retain before Variant destroy | 已完成 | YES/`UNKNOWN` own；`NO` 无操作 |
| method-bind lookup/error 合同 | 保持 | |

#### 3E. Registered methods 与 virtual bridge 原子切换（原 3D，后延）

**状态：核心 cutover 已完成；运行时/refcount 全量验收仍可增强（codegen 正反锚定已补）**

主要文件：

- `BindingData.java`（`ownerClassName`）
- `CGenHelper.java`（owner bind name、self fat、ptrcall raw 适配）
- `entry.h.ftl` / `entry.c.ftl`
- `CCodegen.java`（property-init apply 对 fat self 的构造）
- `ObjectValueRepresentationCharacterizationTest`

工作：

| 任务 | 状态 | 备注 |
|---|---|---|
| BindingData 携带 owner | 已完成 | instance wrapper 名称含 owner |
| instance/static function pointer 分离 | 已完成 | static 无 fake self |
| call_func：`p_instance` → owner fat self | 已完成 | `*_from_raw(Class_object_ptr(...))` |
| ptrcall object args raw→fat | 已完成 | 不再把 fat 当地址当 Godot slot |
| ptrcall object return fat→raw transfer | 已完成 | `*((GDExtensionObjectPtr*)r_return)=*_live_object(r)` |
| virtual 走 owner-aware ptrcall 名 | 已完成 | `renderFuncBindName(classDef, function)` |
| property-init apply / `_init` Class*→fat | 已完成 | constructor 边界适配 |

阶段 3 统一验收：

- internal generated signatures中不再出现对象裸 pointer参数/返回。
- struct assignment与生命周期顺序正确。
- `_return_val` 和 moved source清零正确。
- explicit inheritance layout tests不回归。
- 每个 object-producing instruction都输出 `{ptr, instance_id}`。
- freed receiver不被解引用，property/method hard-fail cleanup正确。
- `object_is_null` / object-vs-nil 使用 `gdcc_object_is_null_raw_and_id(raw, instance_id)`，可安全识别 freed。
- object/object equality 使用 §10.2 **C1 normalized raw**（null∪freed→`NULL`，live 比 Godot raw；无 dead wrapper 解引用；
  不比 fat struct / 不以 ID 为比较键）。
- `assert_object_live` 使用通用 `(raw, id)` helper；无 per-type assert helper，且不从 raw 恢复 ID。
- no stale cached pointer传入 Godot API。
- object ptrcall args不是 fat struct地址。
- object ptrcall return不是写入 fat struct storage。
- exact vararg return在 Variant销毁后仍有正确 RefCounted ownership。
- 两个 owner拥有相同 method ABI shape时不会共享错误 self wrapper。
- static method不再收到 fake self。
- generated function pointer type与实际 function declaration一致。
- call_func 与 ptrcall对象参数/返回都通过 header compile和运行时测试。

### 阶段 4：清理 fallback、legacy path 与事实文档

主要文件：

- `CGenHelper.java`
- `CBodyBuilder.java`
- `gdcc_helper.h`
- `entry.h.ftl`
- backend相关事实文档
- 相关测试

工作：

| 任务 | 状态 | 说明 |
|------|------|------|
| 删除 unknown object -> `GDExtensionObjectPtr` fallback | 已完成（阶段 1/3 已 fail-fast，阶段 4 复核） | `ObjectFatPtrSpec` / characterization 负路径锚定 |
| 删除 `gdcc_cmp_object` legacy helper | 已完成 | `gdcc_operator.h`；equality 仅走 C1 normalized raw |
| 删除 `gdcc_new_Variant_with_gdcc_Object` legacy macro | 已完成 | `entry.h.ftl`；pack 统一走 `<Type>_fat_ptr_to_variant` |
| operator evaluator 内部签名切 fat pointer | 已完成 | `CGenHelper` + `entry.h.ftl`：参数/返回 fat；helper 内 raw slot 降级 |
| raw ABI 显式 allowlist + prefix backlog | 已完成 | `GLOBAL_FUNCS_REQUIRE_GODOT_RAW_PTR` + `godot_*` backlog；移除 `gdcc_eval_*` 特例 |
| 更新 backend 事实文档（阶段 4 相关段落） | 已完成 | `gdcc_c_backend.md`、`cbodybuilder_implementation.md`、本计划 |
| 行为锚定测试 | 已完成 | `CGenHelperTest`、`CBodyBuilderPhaseCTest`、call method 测试 |

后续（非阶段 4 阻塞）：

| 项 | 决策 | 说明 |
|----|------|------|
| `godot_*` 前缀 backlog → structured callee metadata | **延后到以后 PR** | 当前 `GODOT_RAW_ABI_PREFIX_BACKLOG` 保持；禁止再堆新 prefix 特例 |
| evaluator object 返回 `_from_raw` | **保留防御** | 当前 API 无 Object-returning operator；注释 + 单元测试锚定 ABI shape |
| generated C raw/fat post-gen scanner | **不新增** | 用户确认（2026-07-29）：codegen fail-fast + characterization / binding-wrapper scanner 已满足首次迁移；不另做 raw/fat post-gen scanner |

验收：

- internal signatures/fields/locals无裸 object pointer。
- raw `GDExtensionObjectPtr`只出现在明确 ABI/layout/helper边界。
- 违规的 internal 裸 object 表示在 codegen 路径 fail-fast（由 characterization 与 binding-wrapper scanner 锚定）；**不要求**单独的 post-gen raw/fat ABI scanner。

| 验收项 | 状态 |
|--------|------|
| internal 无裸 object pointer | 已满足（codegen fail-fast + characterization） |
| raw 仅在 ABI/layout/helper 边界 | 已满足（allowlist + 生成边界） |
| 违规 fail-fast（无 post-gen raw/fat scanner） | 已满足（codegen fail-fast + characterization + binding-wrapper scanner；2026-07-29 用户确认不新增 post-gen scanner） |

**阶段 4 代码清理状态：已完成（2026-07-29）。** scanner 验收已按用户确认关闭。

### 阶段 5：关联文档、代码注释与迁移说明同步

主要文件：

- `doc/gdcc_c_backend.md`
- `doc/gdcc_runtime_lib.md`
- `doc/gdcc_ownership_lifecycle_spec.md`
- `doc/gdcc_low_ir.md`
- `doc/gdcc_lir_intrinsic.md`
- `doc/module_impl/backend/*.md`
- `src/main/java/gd/script/gdcc/backend/c/gen/**`
- `src/main/c/codegen/template_451/**`
- `src/main/c/codegen/include_451/gdcc/**`
- 阶段 0 新增 characterization tests 的注释

工作：

| 任务 | 状态 | 说明 |
|------|------|------|
| 事实文档：对象值 = fat pointer | 已完成 | `gdcc_c_backend.md` 新增 Object Value Representation；`ref`/type mapping/`Pointer Conversion` 区分 value vs layout |
| ownership 规范表示转换 | 已完成 | `gdcc_ownership_lifecycle_spec.md` §2.3 明确 fat value vs wrapper layout |
| backend 模块文档 | 已完成 | `godot_binding_implementation.md` exact helper 入口；`call_method` / 其它已 fat-aligned 文档复核 |
| 生产代码注释 | 已完成 | `CGenHelper` / 指令生成器已对齐；`CBodyBuilder` raw-ABI allowlist 与 `godot_*` backlog 注释标明长期方向 |
| runtime helper pure/side-effect 注释 | 已完成 | `gdcc_helper.h`：query 已有 `GDCC_PURE`/`GDCC_CONST`；own/release/try_* 标明非 pure、有副作用 |
| 阶段 0 characterization 注释 | 已完成 | 测试类注释已是 fat-pointer 迁移后锚点（非裸指针基线） |
| 记录 fail-fast / assert / ref-bit / freed Variant ID 限制 | 已完成 | 见 `gdcc_c_backend.md` Object Value Representation 与 lifecycle 段落 |
| 清理失效 legacy 注释 | 已完成 | 已删除 helper 仅作历史记录；allowlist 保留并标注 structured metadata backlog |
| 计划文档自身状态 | 已完成 | 阶段 0–5 标记完成；LIR assert 合并 pass 等仍在 §16 backlog |

验收：

| 验收项 | 状态 |
|--------|------|
| 文档不将 internal storage/parameter/return 描述为裸 pointer | 已满足 |
| 生产/模板注释与 generated C 一致，不引用已删 helper | 已满足 |
| 阶段 0 测试注释描述 fat-pointer 锚点 | 已满足 |
| 关联文档合同引用一致 | 已满足 |

**阶段 5 状态：已完成（2026-07-29）。**

---

## 14. 测试与验收矩阵

### 14.1 Java codegen unit tests

重点更新/扩展：

- `ObjectValueRepresentationCharacterizationTest`
- `ObjectValueLifecycleCharacterizationTest`
- `CGenHelperTest`
- `CBodyBuilderPhaseBTest`
- `CBodyBuilderPhaseCTest`
- `CBodyBuilderAliasSafetySupportTest`
- `CAssignInsnGenTest`
- `CConstructInsnGenTest`
- `CConstructInsnGenEngineTest`
- `CLoadStaticInsnGenTest`
- `CallGlobalInsnGenTest`
- `CallGlobalInsnGenEngineTest`
- `CallMethodInsnGenTest`
- `CallMethodInsnGenEngineTest`
- `CallMethodInsnGenEngineInheritanceTest`
- `CLoadPropertyInsnGenTest`
- `CStorePropertyInsnGenTest`
- `LoadStorePropertyInsnGenEngineInheritanceTest`
- `CPackUnpackVariantInsnGenTest`
- `COperatorInsnGenTest`
- `COperatorInsnGenEngineTest`
- `COwnReleaseObjectInsnGenTest`
- `CDestructInsnGenTest`
- `CCodegenEngineMethodBindHeaderTest`
- `CCodegenEngineMethodUsageSessionTest`
- `CNewDataInsnGenTest`
- `AssertObjectLiveInsnGenTest`（新增）
- `SimpleLirBlockInsnParserTest` / `SimpleLirBlockInsnSerializerTest`（新增 `assert_object_live` cases）

每类至少覆盖：

- engine static type
- GDCC static type
- child -> parent upcast
- null
- live object
- freed ID
- RefCounted与non-RefCounted
- same-type copy与overwrite
- unknown object type negative path
- static RefCounted fast path（无 ObjectDB lookup）
- unknown dynamic RefCounted reference-bit fast path
- non-RefCounted freed ID validation
- pure helper 属性在 generated header 中存在（或按宏降级）

### 14.2 Generated C/header compile

- `GodotAbiHeaderCompileTest`
- `CProjectBuilderIntegrationTest`
- `FrontendLoweringToCProjectBuilderIntegrationTest`

新增 scanner断言：

- internal function object parameter/return必须是 `gdcc_<Type>_fat_ptr`。
- object field必须是 per-type fat pointer。
- ptrcall object slot必须是 raw pointer slot。
- raw pointer只能出现在 allowlisted ABI/helper/layout位置。
- 不允许 generated generic `void *` object value。
- static RefCounted live pointer 路径不得出现 `godot_object_get_instance_from_id`。
- unknown object liveness 路径必须先出现 reference-bit 测试。
- 解引用硬失败守卫必须来自 `assert_object_live` lowering，并通过通用 `gdcc_object_is_null_raw_and_id(raw, id)`；
  不得出现从 raw 恢复 ID 的路径，也不得生成 per-type assert helper。
- `object_is_null` / object-vs-nil 使用 `gdcc_object_is_null_raw_and_id(raw, id)`；object equality 直接比较 raw pointer。
- own/release/destroy、Variant pack/unpack 的 liveness 判断允许在 pure/runtime helper 内完成，不要求额外 LIR liveness 指令。

### 14.3 Godot runtime integration & E2E test

至少增加以下场景：

1. live engine object复制、传参、返回和 equality。
2. live GDCC object复制、upcast、property/method调用。
3. null object与 live/freed object比较。
4. non-RefCounted对象被外部 `free()` 后，GDCC仍持有 fat pointer：
    - `ref == null` 为 true。
    - `ref != null` 为 false。
    - 不发生 use-after-free。
5. 两个不同 freed refs比较，结果与 Godot Variant equality基线一致。
6. freed ref经过 outbound Variant pack后安全降级为 OBJECT/null，测试明确记录 ID丢失限制。
7. RefCounted object跨 assignment、return、Variant pack/unpack、registered call_func/ptrcall 后引用计数平衡。
8. constructor、singleton、exact engine ptrcall和vararg method都捕获非零 ID。
9. RefCounted 对象在持有强引用期间跨 Godot call 后仍可直接访问；generated C scan 证明该路径未新增 ObjectDB lookup。
10. unknown 静态类型携带动态 RefCounted 对象时，reference-bit fast path 正确跳过 ObjectDB；携带 non-RefCounted freed
    对象时仍安全降级。

### 14.4 推荐验证顺序

迭代期间只运行受影响的 targeted tests，例如：

```bash
script/run-gradle-targeted-tests.sh --tests CGenHelperTest,CBodyBuilderPhaseCTest
script/run-gradle-targeted-tests.sh --tests CPackUnpackVariantInsnGenTest,COperatorInsnGenTest
script/run-gradle-targeted-tests.sh --tests CallMethodInsnGenEngineTest,CCodegenEngineMethodBindHeaderTest
script/run-gradle-targeted-tests.sh --tests GodotAbiHeaderCompileTest
```

每个实施阶段结束后运行：

```bash
./gradlew classes --no-daemon --info --console=plain
```

全部阶段完成后运行 full clean build和环境允许的 Godot/Zig integration tests。

---

## 15. 完成定义

只有同时满足以下条件才可将该计划标记为 implemented：

1. 所有 internal object storage、parameter和return都使用 per-static-type fat pointer。
2. `GdObjectType` 不再静默 fallback到 `GDExtensionObjectPtr`。
3. 所有 raw ingress都在 live边界捕获 ID。
4. 所有 Variant ingress都从 Variant读取 ID。
5. 所有 non-RefCounted liveness-sensitive 操作都按 ID 验证 pointer；RefCounted fast path 按强引用合同取得 live pointer。
6. equality 直接比较 raw pointer；null/assert 通过 `(raw, id)` helper 与 live/null/freed 运行时矩阵。
7. ownership与 `_return_val` 合同无回归。
8. registered call_func、ptrcall和virtual callback完成 owner-aware适配。
9. exact engine ptrcall和vararg object ABI完成双向适配。
10. generated C/header compile tests通过。
11. UTF-8 literal等 raw C expression不再伪装为 object type。
12. 现有 backend事实文档已更新，不再描述对象值为裸 pointer（阶段 5 已完成）。
13. 解引用硬失败守卫以显式无返回 `assert_object_live` LIR 指令表达；条件判断、equality、lifecycle、Variant pack/unpack
    不引入额外 LIR liveness 指令。
14. static RefCounted 路径基于强引用合同跳过 ObjectDB 验证；unknown 路径使用 ObjectID reference-bit fast path。
15. 存活性/ID 查询 helper 按合同标注 `pure`/`const`，own/release/destroy 等副作用 helper 未标注。
16. 后续 LIR assert 合并 pass 的输入表面已稳定，首次迁移至少不阻断该优化。

---

## 16. 长期风险

1. cached typed pointer在对象释放后可能悬空，因此任何新增 use-site若绕过 ID validation都会重新引入 UAF。
2. object unpack若被误标为 `OWNED`，临时 Variant销毁和 destination slot retain之间会发生引用计数错误。
3. registered wrapper若继续按 ABI shape跨 owner共享，会把 raw `p_instance`包装成错误 static self type。
4. ptrcall object slot若误用 fat struct地址，会产生 ABI memory corruption，而不只是类型不匹配。
5. outbound freed Variant无法保持原 ID是 Godot 4.5.1 public ABI限制；维护者不得以 private layout写入绕过。
6. header include/declaration顺序与 usage-driven engine helper收集相互依赖，新增 object type surface时必须同步进入 module
   collector。
7. function-name prefix只能作为过渡 ABI识别机制，长期应优先使用 structured binding metadata。
8. wrapper instance layout和object value representation必须持续分离；不能因为二者都包含 object pointer而重新合并。
9. non-RefCounted liveness-sensitive 操作会引入 ObjectDB lookup；高频路径只能在已证明没有失效边界的局部 region 内缓存
   validated pointer，不能以性能为由放宽 ID-authoritative 合同。
10. RefCounted high-bit fast path 的正确性依赖 ownership invariant；若某个 fat pointer 未 retain 却跨越 source 销毁，stale
    RefCounted ID 会被误判为 live。
11. `pure`/`const` 标注只适用于查询 helper；若误标 own/release/destroy 或把 pure 查询跨 side-effecting call 移动，会产生
    错误优化。
12. 未来 LIR assert 合并 pass 必须把 release/free/overwrite/Godot call 视为 assert invalidation 边界；LIR 非 SSA，不得仅
    按变量 ID 相同就跨边界删除守卫，必须追踪变量重定义和失效边界。
