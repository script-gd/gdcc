# Packed*Array 引用语义实施计划

## 1. 背景与目标

### 1.1 背景

gdcc 目前将 `Packed*Array`（PackedByteArray / PackedInt32Array / PackedInt64Array /
PackedFloat32Array / PackedFloat64Array / PackedStringArray / PackedVector2Array /
PackedVector3Array / PackedColorArray / PackedVector4Array）以 **by-value C 结构体**存储
（`godot_Packed*Array`，16 字节 opaque struct），赋值/别名/传参时通过
`godot_new_<Type>_with_<Type>` 拷贝构造。该拷贝是 C++ `Vector<T>` 层拷贝，首次写入触发
COW detach，形成值语义。

Godot 4.4+ 官方文档与 Godot 4.5.2 解释器实测均确认 Packed*Array 在语言层为
**引用（共享）语义**：Variant 内部以指针持有堆上的共享数组实例（引擎侧
`PackedArrayRef`），Variant 拷贝共享该实例，所有别名持有者对 mutation
（`push_back`、`append_array`、`resize`、索引写等）互相可见。

由此产生的行为分歧（实机探针证实，见 §2）：局部别名不可见、形参 mutation 对调用方
不可见、`for-in` 迭代期间新增元素不被访问、Variant 双向转换后身份分裂。

### 1.2 目标

将 gdcc 中 Packed*Array 的存储与运算语义**严格对齐 Godot 4.5 解释器的引用语义**，
覆盖：局部变量、参数、脚本属性、静态变量、Array/Dictionary 元素、信号参数、lambda 捕获、
协程 frame、`for-in` 迭代、Variant 双向转换、`duplicate()`、`+=` 重绑定、cast/类型测试、
运算符与索引读写。

### 1.3 已记录的例外与有意行为变更

**例外（引擎 ABI 固有限制，用户确认可接受）：**

1. **ptrcall ABI 边界**：ptrcall 入口的 packed 参数以原始 struct 指针（`Vector<T>` 值槽）
   传入，callee 必须做 struct→Variant 物化（`Vector` 层拷贝），此后 mutation 与调用方隔离；
   packed 返回值经 ptrcall 写出时同样发生 struct 拷贝。GDScript 与 GDExtension 之间的常规
   调用走 `call_func`（Variant ABI），**不受此例外影响**；该例外只影响使用 ptrcall 的扩展间
   调用路径。必须在 `gdcc_c_backend.md` 与本文档中显式记录，并有运行测试锁定
   （断言身份不共享，不得以注释代替测试）。
2. **内建属性 getter 返回副本**：这是引擎语义（`poly.polygon.push_back(x)` 在解释器中同样
   不持久），不是 gdcc 分歧。

**有意行为变更（严格对齐解释器所必需，推翻现行 gdcc 合同）：**

3. **内建引擎属性 route 的 reverse-commit 写回将被移除**。现行 gdcc 对
   `poly.polygon.push_back(x)` 会在调用后生成 `StorePropertyInsn` 写回属性
   （`frontend_complex_writable_target_implementation.md` §7.1 将此列为"需要 property
   writeback"），但解释器中该 mutation **不持久**（getter 返回副本）。严格对齐要求移除
   该 route 对 Packed*Array 的写回；现有断言 `StorePropertyInsn` 存在的测试随之反转。
   脚本属性 route（GDCC 实例字段）与 Array/Dictionary 元素 route 的写回在新模型下冗余
   但无害（存回同一身份），本次**保留**以降低迁移风险，标记为后续可选清理项。

## 2. 语义合同（Godot 4.5.2 解释器实测基线）

以下矩阵是本次改造的目标行为。第 1–17 行已由 `tmp/probes/packed_ref_semantics/` 探针在
Godot 4.5.2 headless 下实测；第 18–22 行为审阅补充场景，其中标注"待探针"的行由 Phase A
先补解释器探针锁定 golden，再进入验收。每行同时是 §8 验收测试的 golden。

| # | 场景 | 解释器行为 | gdcc 目标 |
|---|---|---|---|
| 1 | `var b := a; a.push_back(7)` | `b` 可见（共享） | 共享 |
| 2 | callee 对形参 mutation | 调用方可见（共享） | 共享 |
| 3 | 脚本属性 `payloads.push_back`（self / 外部） | 持久可见 | 持久可见 |
| 4 | 静态变量 mutation | 持久可见 | 持久可见 |
| 5 | `Array[PackedInt32Array]` 元素 mutation | 持久可见 | 持久可见 |
| 6 | Dictionary 值中的 packed mutation | 持久可见 | 持久可见 |
| 7a | 内建属性 getter（`poly.polygon.push_back`） | **不持久**（返回副本） | 不持久（需移除现行写回） |
| 7b | 内建属性重赋值（`var p=...; p.push_back; poly.polygon=p`） | 持久 | 持久（不变） |
| 8 | `a += b` | 产生新数组并**重绑定**，旧别名不可见 | 重绑定 |
| 9 | `a.duplicate()` | 独立副本 | 独立副本 |
| 10 | 信号参数 mutation | 发射方可见 | 发射方可见 |
| 11 | lambda 捕获后 mutation | 捕获方/外部互相可见 | 共享 |
| 12 | `for v in a` 期间 `a.push_back` | **活迭代**：新元素会被本轮访问 | 活迭代 |
| 13 | `append_array` | 共享 | 共享 |
| 14 | `resize` | 共享 | 共享 |
| 15 | `a[0] = 99` 索引写 | 共享 | 共享 |
| 16 | `Variant` ↔ typed 转换（赋值） | 保持共享（三方共享） | 保持共享 |
| 17 | 参数默认值 | 每次调用物化新数组 | 每次调用物化新数组（不变） |
| 18 | 元素写入重绑定：`var e := arr[0]; arr[0] = 新数组` | `e` 仍指向旧数组（元素槽重绑定） | 重绑定（已探针锁定：`ELEMENT_REBIND e=2,1;slot=1,9`） |
| 19 | PackedStringArray 迭代 | 元素为 String 副本，值相等 | 元素副本（不变） |
| 20 | `in` 成员测试 | 内容匹配 | 内容匹配（已探针锁定：`IN_MEMBERSHIP 1,0,1,0`） |
| 21 | `==`/`!=` | 内容相等（含别名、内容相同身份不同、mutation 后别名三种子场景） | 内容相等（已探针锁定：`EQUALITY 1,1,1,1`；哈希与 Dictionary 键行为经 `DICT_KEY_HASH` 锁定：键按内容哈希，插入后经共享别名 mutation 使旧条目查找 missing，以 mutation 后的键再插入产生第二个条目） |
| 22 | `v as PackedInt32Array`（同 family） | **COW 拷贝（新身份），非共享** | COW 拷贝（已探针锁定：`AS_SAME_FAMILY 2,1,2`——`a.push_back(7)` 后 a=2、b=1、v=2；v 与 a 共享，b 为独立新身份。**推翻本表原始"保持共享"预期**，§4.3.7 随之修订） |
| 23 | 协程形参/捕获在 await 挂起前后双向 mutation | 双向可见（已探针锁定：`CORO_AWAIT before=2,1;during=3,2;after=4,3`） | 与探针一致 |
| 24 | 多参数 typed 信号携带 packed | 共享（已探针锁定：`SIGNAL_MULTI 2,5,2`） | 与探针一致 |

## 3. 引擎机制依据（godotengine/godot 4.5 分支核实）

1. GDScript VM 的局部槽（含 typed packed local）均为 `Variant`
   （`modules/gdscript/gdscript_vm.cpp:571-574`），typed 信息只用于静态检查。
2. VM 对 builtin 类型方法的验证调用路径（`OPCODE_CALL_BUILTIN_TYPE_VALIDATED`，
   `gdscript_vm.cpp:2346-2363`）把 base `Variant*` 直接交给方法，原位执行。
3. `core/variant/variant_call.cpp:65-69` 的 `builtin_methodcall` 通过
   `VariantGetInternalPtr<T>::get_ptr(base)` 取 Variant 内部值指针调用方法，不构造副本。
4. **GDExtension 暴露同一能力**：`variant_get_ptr_internal_getter`
   （`core/extension/gdextension_interface.h:1443-1458`）返回按类型的内部值指针 getter，
   文档明确允许用于原地修改。本项目绑定已暴露：
   `godot_variant_get_ptr_internal_getter`（`godot_interface.h:393`），类型签名
   `GDExtensionVariantGetInternalPtrFunc = void *(*)(GDExtensionVariantPtr)`。
5. `duplicate()` 是引擎注册的 packed builtin method（`variant_call.cpp:2585,2661,2685` 等）。
6. `+` 运算符产生新数组（`core/variant/variant_op.h:861-867`），`+=` 由语言层重绑定。
7. lambda 捕获按 Variant 拷贝保存（`gdscript_vm.cpp:2651-2658`），共享内部实例。
8. GDScript 成员槽为 `Vector<Variant>`（`gdscript.h:372-377`），属性读取返回 Variant 拷贝，
   共享内部实例。
9. typed Array 元素存储为 `Vector<Variant>`（`core/variant/array.cpp:41-45`），元素访问返回
   Variant 引用，packed 元素 mutation 经 Variant 层共享。
10. GDExtension typed 拷贝构造是 `Vector` 层拷贝（`variant_construct.h:114-135`），首写触发
    COW（`cowdata.h:153-156`）——这正是现行 struct 存储模型产生值语义的根源。

## 4. 目标设计

### 4.1 核心决策：Variant-backed 存储

将 gdcc 生成的 C 代码中 Packed*Array 的**规范存储形式**从
`godot_Packed*Array`（by-value struct）改为 **`godot_Variant`**：

- **身份共享**：Variant 拷贝（`godot_new_Variant_with_Variant`）共享引擎堆上的同一
  `PackedArrayRef`，引用计数由引擎管理。无需 gdcc 自建 box/refcount。
- **原位 mutation**：调用 builtin 方法/索引写/resize 时，先经缓存的
  `GDExtensionVariantGetInternalPtrFunc` 取内部值指针，再以其为 base 执行现有
  `GDExtensionPtrBuiltInMethod` wrapper（`GodotBuiltinGenerator` 生成的 wrapper 签名不变，
  仅 base 实参来源变化）。
- **核心不变式**：gdcc 持有的 packed 值**禁止**再经过 struct pack/unpack
  （`godot_new_Packed*_with_Variant` / `godot_new_Variant_with_Packed*`），仅有的合法
  struct 边界是：(a) ptrcall ABI 边界双向（§1.3 例外 1）：入向 struct→Variant 物化与
  出向 Variant→struct 返回拷贝；(b) empty 构造时的临时 struct
  （此刻尚无共享者）；(c) builtin 方法返回的原生 struct 临时值（立即包装为 Variant 并
  destroy）；(d) 带参数的 packed 构造器——含跨类型构造（如 `PackedInt32Array([1, 2])`
  经 `godot_new_PackedInt32Array_with_Array`，`godot_builtin.h:1777`）与显式同型构造
  （`PackedInt32Array(otherPacked)` 经 `godot_new_PackedInt32Array_with_PackedInt32Array`，
  `godot_builtin.h:1776`；同型构造产出**独立新数组**，与 `duplicate()` 等价）：构造实参
  按原生 ABI 形状渲染，结果接入原生临时 struct，立即包装为 Variant 并 destroy temp
  （构造完成前无共享者）。赋值、传参、cast、信号、lambda/协程捕获只允许
  `godot_new_Variant_with_Variant`。**不得**依据 CowData refcount 做任何复制决策
  （外部 ptrcall 拆箱或 getter 副本会使 rc>1，那是另一 `PackedArrayRef` 的正常现象）。
- **Variant ↔ typed 转换恒等**：typed packed 值本身即 Variant，pack/unpack 退化为
  类型检查 + Variant 拷贝，天然满足 §2 第 16 行。
- **call_func 边界保持身份**：wrapper 入参从 `const godot_Variant*` 拷贝构造本地 Variant，
  callee mutation 对 GDScript 调用方可见（§1.3 例外仅剩 ptrcall）。

### 4.2 备选方案与否决理由

1. **维持 struct 存储 + 扩大写回**：无法修复局部别名共享（`b := a` 要求两个 slot 永久同步）
   与 GDScript 边界；形参写回还需要 backend assign-through-pointer 架构改造，否决。
2. **手动引用计数盒（heap struct + gdcc 管理 refcount）**：身份仍在每次 Variant pack/unpack
   处断裂（struct 拷贝 detach），需要自建原子计数，且重复引擎已有的 Variant 引用计数，否决。

### 4.3 设计要点

1. **C 存储形状**：局部变量、参数、实例字段、静态字段、协程 frame 字段中的 packed 类型
   统一为 `godot_Variant`。内部 gdcc 函数签名的 packed 参数/返回类型相应改为
   `godot_Variant*` / `godot_Variant`（仅内部 ABI，无外部兼容负担）。**形参共享身份直接
   解决现行 PARAMETER 写回限制**：mutation 经共享身份对调用方可见，不需要
   assign-through-pointer，也不追加任何 commit step。
2. **默认初始化**：`var a: PackedInt32Array` 必须初始化为**空数组 Variant**（per-family
   helper：临时 struct 构造 → `godot_new_Variant_with_<Type>` → destroy temp），
   **严禁** nil Variant（nil 无内部值指针，方法调用会失败）。现有
   `ConstructInsnGen.java:85-98` 对 `ConstructArrayInsn` + `GdPackedArrayType` 走
   `constructBuiltin(..., List.of())` 零参 struct 构造，必须改发 empty-Variant helper。
3. **方法调用 receiver**：receiver 地址 = 缓存 getter 求值后的内部指针。getter 按
   `GDExtensionVariantType` 在初始化期缓存一次（per-family 静态变量），初始化时做
   可用性检查，缺失则 fail-fast 并给出明确错误。
4. **方法调用的参数与返回值 ABI**（审阅补充，覆盖 receiver 之外的位置）：
   - packed 类型**实参**（如 `append_array(const godot_PackedInt32Array *array)`，
     `godot_builtin.h:1787`）：渲染为该实参 Variant 的内部指针。
   - packed 类型**返回值**（如 `duplicate`、`slice` 返回原生 struct）：先接入原生临时
     struct，再 `godot_new_Variant_with_<Type>` 包装为新 Variant 并 destroy 临时 struct
     （临时值未经过写入，不发生 detach；新 Variant 是独立 `PackedArrayRef`，身份正确）。
5. **索引读写**：`indexed_get` / `indexed_set` / `operator_index(_const)` 作用于内部指针。
   typed packed self 的索引写去除现行 pack/call/unpack 写回
   （`IndexStoreInsnGen.java` 的 `isIndexedValueSemanticSelfType` 含 `GdPackedArrayType`），
   `ref` self 禁令解除。
6. **运算符**：`==`/`!=`/`in` 等内容运算**按操作数逐个处理**：packed 操作数取其
   Variant 内部指针，非 packed 操作数（标量等）保持 op evaluator wrapper 声明的原生
   ABI 形状（如 `godot_int_op_in_PackedInt32Array(godot_int left, const
   godot_PackedInt32Array *right)`，`godot_builtin.h:129`——仅 right 取内部指针，
   left 按值传递）；
   `+` 在原生临时 struct 中产出新数组后立即包装为新 Variant 并 destroy temp。
   **禁止**将 `+`/`+=` 优化为对内部指针的 in-place `append_array`（会把 mutation 泄漏给
   旧别名，违反 §2 第 8 行）。哈希与 Dictionary 键行为以解释器实测 golden 为准，不作假设。
7. **cast 与类型测试**：`is` 经 `Variant.get_type()` 与目标 `GDExtensionVariantType` 比较。
   `as` cast 在运行时类型同属目标 packed family 时**产生 COW 拷贝（新身份）**——Phase A
   探针实测（§2 第 22 行）：解释器的 `v as PackedInt32Array` 产出独立新数组（等价于
   同型构造的 COW 拷贝），`a.push_back(7)` 后 `a=2, b=1, v=2`。实现形态：内部指针 →
   `godot_new_Packed*Array_with_Packed*Array`（同型拷贝构造，属白名单 (d) 同类豁免）→
   立即包装为新 Variant。~~类型检查 + Variant 拷贝（保持身份）~~的原始设计与实测不符，
   已修订。其余合法转换遵循既有 cast 合同。
8. **for-in 迭代器重写**（`intrinsic/for_packed_array_iter.h` + Java 侧
   `GdccForPackedArrayIterType` 布局注释 + intrinsic 发射，三者必须同一阶段变更）：
   - state 持有源数组的 **Variant 拷贝**（共享）与 index；**不再**持有 COW struct 快照。
   - `from` 接收 `const godot_Variant*`；`next` **只递增 index**（禁止复用现行 `copy`
     ——它会连带复制缓存指针字段）。
   - `should_continue` 每次对内部指针求 **live size**。
   - `get` 每次先以 live size 做越界检查，再经内部指针 + `operator_index_const` 取元素；
     **禁止跨迭代缓存元素基址**（mutation 可能 realloc，缓存指针会悬垂；迭代期间缩容由
     live size 检查保证不越界）。
   - PackedStringArray 元素按访问拷贝 String（值语义不可观测，与现行一致）；packed 数组
     元素只能是标量/数学类型/String，不存在 Packed/Object 元素的所有权问题。
9. **析构**：所有 packed slot 的析构统一为 `godot_Variant_destroy`（引擎递减共享引用）。
   wrapper carrier 清理由现有 `renderCallWrapperDestroyStmt` 承担（carrier 改为 Variant 后
   同一函数路径覆盖）。
10. **协程 frame**：参数/捕获/返回字段为 Variant；`entry.c.ftl` 的 frame copy/destroy 段
    （约 704-846 行）中 packed 的"拷贝"语义为 Variant 持有者拷贝（共享身份），**不得**
    struct copy-construct。这与解释器 lambda/协程的 Variant 捕获一致（§2 第 11、23 行）。
11. **ptrcall wrapper 具体转换**（§1.3 例外 1 的实现侧）：
    - 入参：从 `p_args[i]` 的原生 struct 槽物化 `struct → Variant`（`godot_new_Variant_with_
      <Type>`），调用后 destroy 该 Variant。
    - 返回：callee 产出 Variant 后经 `gdcc_packed_ref.h` 的出向拷贝 helper
      （`godot_new_Packed*_with_Variant` 或等价的内部指针 copy-construct）将
      `Variant → struct` 拷贝写入 `r_return`，随后清理临时值。该 helper 与入向物化
      helper 同属白名单 (a)。
    - `CGenHelper.renderPtrcallNonObjectArgExpr`（`CGenHelper.java:1056-1062`）与
      `entry.h.ftl:774-812` 当前按内部 C 类型直读/直写，存储映射变更后必须同步改为上述
      物化/拷贝流程，否则会把原生 ABI 槽误当 Variant 槽。
12. **call_func wrapper**：`CGenHelper.renderCallWrapperUnpackExpr` 与
    `renderPackFunctionName`（`entry.h.ftl:336-350,399-412,699-720`）对 packed 改为
    类型检查 + Variant 拷贝（入）/ Variant 直出（返），**禁止**再经 struct 中转。

### 4.4 不受影响的范围

- `String` / `StringName`：语言层无 mutating 方法，维持现有 struct 值语义。
- `Array` / `Dictionary` / `Object`：本已是共享/引用语义，仅 Packed*Array 作为其元素/值时
  间接受益（§2 第 5、6 行）。
- typed Array / typed Dictionary ABI 合同：packed 作为 outward leaf 的 hint 与
  `godot_Array` 物理 carrier 不变。

## 5. 前端写回机制的调整（按 route provenance 分流）

现行机制（`frontend_complex_writable_target_implementation.md`）为补偿值语义而设。静态
谓词 `FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType`
（`frontend/lowering/FrontendWritableTypeWritebackSupport.java:25-32`）当前是 **family 级
二值**（`default -> true`），直接把 Packed*Array 划进 `false` 会连带关闭所有 route 的
reverse-commit，这是错误的。改造后按 route provenance 决策（谓词需引入 provenance 参数或
拆分为 per-route 谓词）：

| route | 现行行为 | 改造后行为 |
|---|---|---|
| direct-slot（LOCAL_VAR）snapshot commit（`DIRECT_SLOT` step） | packed 发布 step 并写回 | **停止发布**（snapshot 为 Variant 拷贝，共享身份） |
| direct-slot（PARAMETER） | fail-closed，mutation 丢失 | **不涉及 step**；形参改 `godot_Variant*` 共享身份（§4.3.1），mutation 天然可见 |
| 内建引擎属性 route（`poly.polygon`） | 写回（与解释器相反） | **移除写回**（§1.3 第 3 条；getter 副本语义） |
| GDCC 脚本属性 route | 写回 | 保留（冗余无害，标记可选清理） |
| STATIC_CONTEXT bare 静态属性 route（`FrontendCfgGraphBuilder.appendCallReceiverCommitSteps` 静态分支，现行按 family 谓词决定 promotion） | **编译期 fail-closed**（Phase A 实测修订：值语义 carrier 的 promotion 被 `FrontendCfgGraph` static-terminal 合同拒绝，非"现行写回"；见下方 Phase A 状态） | Variant 存储使静态 leaf 共享身份后，谓词改造为 route-provenance-aware 时该 route 自然解锁；不再追加 promotion step，mutation 经共享身份天然可见 |
| Array/Dictionary 元素 route | 写回 | 保留（冗余无害，标记可选清理） |
| method-result route（`get_baked_points().push_back`） | 不写回 | 不写回（不变） |

动态 Variant receiver route：runtime gate helper `gdcc_variant_requires_writeback`
（`gdcc_helper.h:577-625`）对 packed 各 kind 改为 `false`。**必须显式列出全部 10 个
packed kind**（现行 switch 漏列 `GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY`，落入
`default: true`，本次一并修复）；`default` 分支保持 `true`（"未知 kind 保守 true" 的冻结
合同不变，`gdcc_type_system.md` §130）。apply/skip 控制流骨架保留；静态 family 矩阵中
Packed*Array 改为 `false` 后，helper 中其余已列出的值类型分支（`String`、`Vector*`、
`Color` 等）仍返回 `true`——它们不因本次改造删除，`gdcc_type_system.md` §130 的
default-true 冻结合同保持不变。动态 Variant 调用在缺少 const 事实时保守按
`mayMutateReceiver == true` 发射 gate，因此这些分支对合法的非 mutating 调用仍会执行
冗余但无害的写回（写回未被修改的 carrier），不视为本次改造的目标。

## 6. 分阶段实施步骤与验收细则

**硬顺序约束：Phase C（存储切换）必须先于 Phase D（gate 翻转）落地，或同一次提交。**
反向顺序（先关 `DIRECT_SLOT` step 而存储仍是 struct）会重新引入 snapshot detach 丢更新。
C 先行时遗留的冗余写回是 identity 自赋值，无害。

### Phase A：语义基线与测试基础设施

- 内容：
  1. 补充解释器探针，锁定 §2 第 18–24 行"待探针"场景的 golden（含协程 await 挂起前后
     双向 mutation 可见性、多参数 typed 信号、哈希/Dictionary 键行为）。
  2. 建立**双跑对照 harness**：同一行为在独立 GDScript 项目（解释器执行）与 gdcc 编译
     项目中各跑一遍，输出统一 `PROBE|...` 格式规范化后比对 golden 文件。说明与现有
     集成设施（gdcc 编译 + GDScript 验证脚本）的关系——本 harness 是新增的双项目
     side-by-side 比对，不是对现有 verifier 脚本的复用。测试类置于
     `src/test/java/gd/script/gdcc/backend/c/build/`，golden 置于 `src/test/resources`。
  3. 初始仅启用当前已通过的用例；分歧用例（含 7a——现行 gdcc 会错误持久化）以显式清单
     禁用并标注所属 Phase。
- 验收：`script/run-gradle-targeted-tests.sh --tests <新测试类>` 通过；禁用清单与 §2
  矩阵逐行对应；探针 transcript 存 `tmp/probes/` 备查。

#### Phase A 状态（2026-09-24 完成）

**已完成并验收。** 产物清单：

- 探针与 golden 锁定：
  - `tmp/probes/packed_ref_semantics/project/packed_ref_semantics_phase_a.gd`（新增，
    §2 第 18–24 行探针源码）与 `tmp/probes/packed_ref_semantics/results_phase_a.txt`
    （Godot 4.5.2 实测 transcript）。
  - `src/test/resources/packed_ref_semantics/packed_ref_probes.gd`（双跑共享探针库，
    覆盖 §2 全部 24 行 + 动态 Variant receiver 用例，共 25 个主库用例 + 2 个伴随库
    用例）。
  - `src/test/resources/packed_ref_semantics/packed_ref_probes_blocked.gd`（编译受阻
    伴随库，见下）。
  - `src/test/resources/packed_ref_semantics/packed_ref_semantics_golden.txt`（golden，
    由解释器运行锁定；第 1–17 行数值与 `tmp/probes/.../results.txt` 完全一致）。
- 双跑对照 harness（`src/test/java/gd/script/gdcc/backend/c/build/packedref/`，包
  `gd.script.gdcc.backend.c.build.packedref`）：
  `PackedRefSemanticsDualRunHarness`（双项目组装 + 双跑编排）、`ProbeOutput`（严格
  `PROBE|<CASE>|<payload>` 解析）、`ProbeGoldenComparison`（payload 仅检查启用集、
  结构检查无条件）、`PackedRefSemanticsCase`（用例注册表，含 §2 行号与启用标记——
  Phase C 时重构为语义门控 + baseline 回归下限，见 Phase C 状态节）、
  `PackedArrayReferenceSemanticsDualRunTest`（集成测试：解释器侧全量比对 golden，
  gdcc 侧结构 + 启用集 payload 比对，逐用例 DynamicTest）。
- 单元测试（同包）：`ProbeOutputTest`（正反锚定解析合同）、`ProbeGoldenComparisonTest`
  （正反锚定比较语义，含禁用用例豁免）、`PackedRefSemanticsCaseRegistryTest`
  （注册表与 golden 同步、§2 矩阵逐行覆盖、Phase 锚定、compile-blocked 不变式）。
- 运行 transcript 存档：`tmp/test/packed_ref_semantics_dual_run/transcripts/`（每次
  运行刷新）与 `tmp/probes/packed_ref_semantics/dual_run/`（快照备查）。

**启用清单（Phase A 实测当前已通过，10 例）**：SCRIPT_PROPERTY(3)、TYPED_ARRAY_ELEMENT(5)、
DICT_VALUE(6)、BUILTIN_PROPERTY_REASSIGN(7b)、PLUS_EQUALS_REBIND(8)、DUPLICATE(9)、
PARAM_DEFAULT_SHARED(17)、ELEMENT_REBIND(18)、STRING_ITER_ELEMENTS(19)、IN_MEMBERSHIP(20)。

**禁用清单（与 §2 矩阵逐行对应，括号内为归属 Phase）**：
- Phase C（运行时分歧，现行值语义 struct 存储所致）：LOCAL_ALIAS(1)、PARAM_VISIBILITY(2)、
  SIGNAL_ARG(10)、FOR_ITER(12)、APPEND_ARRAY_ALIAS(13)、RESIZE_ALIAS(14)、
  INDEX_WRITE_ALIAS(15)、VARIANT_IDENTITY(16)、EQUALITY(21)、DICT_KEY_HASH(21-hash)、
  AS_SAME_FAMILY(22)。
- Phase D（§5 前端 route/gate 改造）：BUILTIN_PROPERTY_MUTATION(7a)、
  DYNAMIC_VARIANT_MUTATION(dyn-variant)、STATIC_VAR(4)、LAMBDA_CAPTURE(11)。
- Phase F（全量验收，计划 §8 锁定）：CORO_AWAIT(23)、SIGNAL_MULTI(24)。

**Phase A 实测对原计划的两处事实修订**：

1. **§2 第 22 行（`v as PackedInt32Array`）**：探针实测为 COW 拷贝（新身份），非保持
   共享。§4.3.7 已修订。
2. **§2 第 4、11 行（STATIC_VAR、LAMBDA_CAPTURE）在现行 gdcc 下为编译期 fail-closed**，
   非运行时分歧：
   - STATIC_VAR：`static_packed.push_back(7)` 的可写 route 以 STATIC_CONTEXT 为根、
     静态属性 leaf 自身即终态，packed 属值语义写回 family，
     `FrontendCfgGraphBuilder.appendCallReceiverCommitSteps` 静态分支仍追加 promotion
     step，被 `FrontendCfgGraph.validateStaticWritableRouteTerminalContract` 拒绝。
   - LAMBDA_CAPTURE：对 CAPTURE binding 的 mutating 调用在 direct-slot alias 发布处被
     否决（`requireDirectSlotAliasRoot`，"before lambda/capture semantics are
     implemented"）。
   - 二者置于伴随库 `packed_ref_probes_blocked.gd` 单独编译；编译失败即记录为
     compile-blocked（`GDCC_COMPILE_BLOCKED_CASE_NAMES`）；意外编译成功时集成测试经
     `assertFalse(gdccBlockedModuleCompiled)` 硬性失败，提示将用例迁回主库。
3. **SIGNAL_MULTI（§2-24）在现行 gdcc 下运行期崩溃**（`variant_get_indexed failed`，
   编译后的信号回调索引读缺陷），无 PROBE 行产出；Phase F 解锁时需先行修复该缺陷。

### Phase B：C 运行时基础设施（仅新增，不改既有文件）

#### Phase B 状态（2026-09-24 完成）

**已完成并验收。** 产物清单：

- `include_451/gdcc/gdcc_packed_ref.h`（新增；未做任何 include 接线，未修改既有头文件）：
  - 文件头注释完整记录 §4.1 核心不变式与白名单 (a)(b)(c)(d) 到具名 helper 的映射表。
  - per-family 内部指针 getter 缓存（per-TU `static`，与 `_gd_engine` 先例一致）+
    `gdcc_packed_ref_init()` 一次性解析全部 10 个 family，缺失即 fail-fast
    （`godot_print_error` + `abort()`，宏内字符串化给出 family 名）。
  - `gdcc_packed_<slug>_internal_ptr`：NULL self / 未初始化两路为 gdcc 侧 fail-fast，
    getter 返回 NULL 仅为兜底检查（nil/错型 Variant 取内部指针在引擎侧本就是 UB，
    不保证干净返回 NULL，§7.5）；const 转换安全性与"调用方保证 family 匹配"在宏内块
    注释中说明（宏内禁用 `//` 注释——C 翻译阶段 2 行拼接会吞掉宏体，实测踩坑后改块注释）。
  - fail-fast 路径（`gdcc_packed_ref_fail`，`_Noreturn`）：优先 `godot_print_error`，
    接口未就绪（`gdcc_interface_print_error == NULL`）时回退 stderr，随后 `abort()`；
    `gdcc_packed_ref_init` 入口先检查 getter 接口全局已解析。
  - `gdcc_packed_ref_copy` / `gdcc_packed_ref_destroy`（Variant 持有者拷贝/析构别名）、
    `gdcc_packed_ref_is`（get_type 精确匹配，供 `is`/cast，NULL/nil 安全返回 false）。
  - 白名单具名 helper（10 family × 6）：`new_empty`(b)、`wrap_temp`(c)、
    `variant_from_struct`(a 入向)、`struct_from_variant`(a 出向)、`new_copy`(d 同型，
    兼供 §4.3.7 同 family `as`)、`new_from_array`(d 跨类型)。
- `src/test/java/gd/script/gdcc/backend/c/build/GdccPackedRefRuntimeSmokeTest.java`（新增，
  8 测试全绿，zig-gated）：
  - `headerShouldCompileStandalone`：头文件自包含编译 smoke（不依赖其他 gdcc 头与
    `class_library` 全局）。
  - `helpersShouldProvideSharedIdentityAndWhitelistedConversions`：fake 引擎按引擎身份合同
    建模（Variant 拷贝共享同一堆数组；struct↔Variant 每次穿越产生新身份），逐 helper
    正反锚定——别名仅经 `variant_new_copy`（断言 struct 拷贝构造零调用）、别名 mutation
    双向可见、ptrcall 入向/出向双向隔离（§1.3）、`wrap_temp` 临时析构恰好一次、
    `new_copy` 新身份+内容相等+源 mutation 不可见（§2-22）、`new_from_array` 走
    ctor index 2、`is` 对 NULL/nil/异族均为 false、10 family getter 各解析恰好一次、
    销毁恰好一次且无泄漏、happy path 零引擎错误；Int32 全 helper + Byte/Vector4 两个
    附加 family 的 empty/is/alias 验证宏实例独立性。
  - fail-fast 负向锚定（均要求非零退出 + 具体错误信息 + 探针尾部 FAIL 标记不出现，确保
    abort 发生在 helper 内部而非探针自身返回）：`initShouldFailFastWhenInternalGetterMissing`
    （getter 缺失，错误信息含 family 名）、`internalPtrShouldFailFastWithoutInit`（未初始化）、
    `internalPtrShouldFailFastOnNullVariant`（NULL self）、
    `internalPtrShouldFailFastWhenGetterReturnsNull`（getter 返 NULL 兜底；错型/nil 仍为
    §7.5 调用方 UB，不做探针）、`initShouldFailFastBeforeInterfaceInit`（接口未就绪时
    `print_error` 为 NULL，fail 路径回退 stderr）、
    `internalPtrShouldRequireInitInEveryTranslationUnit`（多 TU：第二 TU 未初始化必须
    fail-fast，锁定 per-TU 缓存合同）。
- 验收记录：`./gradlew classes --no-daemon --console=plain` 通过；
  `script/run-gradle-targeted-tests.sh --tests GdccPackedRefRuntimeSmokeTest` 8/8 通过；
  `CProjectBuilderSharedIncludeTest,GodotAbiHeaderCompileTest` 回归通过（新头文件经
  `ResourceExtractor` 递归提取机制自动进入生成项目 include 树，无需清单改动）。

- 内容：新增 `include_451/gdcc/gdcc_packed_ref.h`：per-family 内部指针 getter 缓存与
  初始化（对 `godot_variant_get_ptr_internal_getter(<TYPE>)` 求值一次 + 可用性
  fail-fast）；empty-Variant 构造 helper；Variant 拷贝/析构别名；`get_type()` 类型检查
  helper；**白名单转换的具名 helper**（ptrcall 入向物化 struct→Variant、ptrcall 出向
  拷贝 Variant→struct——经 `godot_new_Packed*_with_Variant` 或等价的内部指针
  copy-construct、empty 构造、临时值立即包装、带参/同型构造包装——集中白名单转换是
  Phase C 生成代码禁令可 grep 验收的前提）。**本阶段不修改任何既有头文件**（迭代器重写推迟到 Phase C 与发射端同步变更），
  保证阶段间可独立编译。
- 验收：新头文件存在且 `./gradlew classes --no-daemon --console=plain` 通过。本阶段
  **不做 include 接线**（新头文件不进入任何编译单元）；include 接线列入 Phase C 必改项。

### Phase C：后端存储模型切换（含迭代器与全部 ABI 边界）

#### Phase C 状态（2026-09-25 完成）

**已完成并验收。** 产物清单（按 §6 清单逐条对应）：

- **核心不变式落地（§4.1）**：
  - `CGenHelper.java`：packed 存储/参数 C 类型映射改 `godot_Variant`/`godot_Variant*`
    （`renderGdTypeInC`/`renderGdTypeRefInC`）；copy/destroy/pack/unpack 分别映射为
    `godot_new_Variant_with_Variant`/`godot_Variant_destroy`（后者）。新增
    `checkPackedType` 与 ptrcall/engine-helper/operator-evaluator 的 packed 专用渲染
    （`renderPtrcallPackedArgDecl`/`renderPtrcallPackedArgDestroyStmt`/
    `renderPtrcallPackedReturnWrite`/`renderEngineMethodHelperPackedSlotDecl` 等）；
    `renderPtrcallNonObjectArgExpr` 对 packed fail-fast（强制走白名单物化分支）。
  - `PackedRefCNames.java`（新增）：`gdcc_packed_ref.h` 全部具名 helper 的集中命名 surface，
    slug 映射 fail-fast，全部 10 family 与头文件宏实例一一对应。
  - `PackedNativeAbiCallSupport.java`（新增）：原生 ABI wrapper（builtin 方法/构造/运算符
    evaluator/utility）的统一调用点适配——packed 形参渲染 `internal_ptr`，packed 返回值经
    原生临时 struct + `wrap_temp` 落入 Variant 槽；discard 路径即时析构；非 packed 位置保持
    通用渲染。调用方：CallMethodInsnGen（BUILTIN mode 钩子）、CallGlobalInsnGen（utility）、
    OperatorInsnGen（BUILTIN_EVALUATOR）、CBuiltinBuilder（非 packed 目标构造器的 packed
    实参，如 `Array(packed)`）。
- **构造（§4.3.2、§4.1(b)(d)）**：`CBuiltinBuilder.constructBuiltin` 新增 packed 分支——
  零参 → `new_empty`，同型实参 → `new_copy`，Array 实参 → `new_from_array`；
  `hasConstructor` 元数据校验保留为有效性闸门（Godot 4.5 packed 构造器恰好是这三个），
  跨 family 等非法组合 fail-closed。`CBodyBuilder.renderDefaultValueExpr` 的 packed 默认值
  改发 `new_empty`（`__prepare__`、默认参数、协程默认值等全部默认值路径随之切换）。
  `CConstructInsnGen`/`CConstructInsnGenEngineTest` 断言已反转为白名单形状并含负向锚定。
- **赋值/返回/析构（§4.3.1、§4.3.9）**：`assignVar`/`returnValue`/scope-exit 析构/协程 frame
  copy/destroy/lambda 捕获/静态 backing 全部经 helper 名映射自动切换为 Variant 持有者
  拷贝与 `godot_Variant_destroy`。`CBodyBuilderAliasSafetySupport` 复核结论：**保留**
  stable-carrier 路径（Variant 拷贝 carrier 在 holder 语义下依然正确，且 String/Vector 等
  struct 槽仍依赖该保守路径；文件头注释记录了该评估）。
- **方法调用 receiver/实参/返回（§4.3.3、§4.3.4）**：builtin wrapper 签名不变，调用点经
  `PackedNativeAbiCallSupport` 适配（receiver=internal_ptr(&$var)，packed 实参同，packed
  返回 wrap_temp）。覆盖 `push_back`/`append_array`/`duplicate`/`slice`（含默认参数补全）
  单测锚定（`CallMethodInsnGenTest`）。
- **索引读写（§4.3.5）**：`IndexStoreInsnGen` 的 packed self 改为直传存储 Variant（与
  Variant self 同路径），pack/call/unpack 写回移除，`ref` self 禁令随之解除；
  `isIndexedValueSemanticSelfType` 不再含 `GdPackedArrayType`。`IndexLoadInsnGen` 无需改动
  （self 经共享 Variant 拷贝物化，元素读取语义不变）。`IndexStoreInsnGenTest`/
  `IndexStoreInsnGenEngineTest` 断言已反转并保留真机行为锚定。
- **运算符（§4.3.6）**：evaluator helper 声明保持原生 ABI（`renderOperatorEvaluatorHelperTypeInC`
  packed → `const godot_Packed*Array*`），调用点按操作数逐个适配（标量按值、packed 取
  internal_ptr）；packed 结果在 helper 内以原生 struct 承载（`renderOperatorEvaluatorResultCarrierTypeInC`），
  调用点 wrap_temp 落入 Variant 槽；evaluator 不可用回退表达式对 packed 返回原生零值 struct
  （新增 `renderOperatorEvaluatorHelperDefaultExpr`）。**未**将 `+`/`+=` 优化为 in-place
  append（§4.3.6 禁令遵守，PLUS_EQUALS_REBIND 用例锁定重绑定语义）。混合类型
  `int in PackedInt32Array` 单测锚定（`COperatorInsnGenTest`）。
- **cast（§4.3.7）**：同 family `as` 经 `BuiltinCastInsnGen` 新增分支发 `new_copy`；
  `ExplicitCastSupport` 同型 packed 分类由 IDENTITY 修订为 BUILTIN_RUNTIME_CAST（前端
  lowering 随之路由到 `BuiltinCastInsn`）。**实测补充修订**：Phase A 探针仅覆盖 Variant 源；
  本阶段对 Godot 4.5.2 补测确认静态同型 `as`（`var c := a as PackedInt32Array`，a 为静态
  packed）同样是 COW 拷贝（新身份），AS_SAME_FAMILY 探针扩展为 `3,1,3,2`（a/v 共享、
  b/c 各自独立），golden 已用解释器重锁。Variant 源 `as` 保持 variant_construct 路径
  （引擎构造即产生新身份），unpack 三分支落入目标槽。
- **unpack 三分支（§4.1 恒等修订落地）**：`InsnGenSupport.unpackVariantAssign` packed 分支=
  精确 kind 检查（`gdcc_packed_ref_is`）→ 共享拷贝；Array payload → `new_from_array` 转换
  （**实测锚定**：解释器对 `var p: PackedInt32Array = variantHoldingArray` 做转换且产独立
  数组，非报错）；其余 payload → 运行时类型错误 + default-return（与解释器类型错误一致）。
  正反锚定见 `CPackUnpackVariantInsnGenTest`。
- **for-in 迭代器重写（§4.3.8）**：`intrinsic/for_packed_array_iter.h` 重写为活迭代——state 持
  源数组 Variant 持有者拷贝 + index；`from` 收 `const godot_Variant*`；`next` 仅持有者拷贝
  +index 递增；`should_continue` 每次求 live size；`get` 每次 live size 越界检查后经
  `operator_index_const` 取元素，禁止跨迭代缓存基址。`GdccForPackedArrayIterType` 布局注释
  已同步。Java 侧 intrinsic 发射无需改动（`from` 实参渲染自动匹配新签名）。
- **wrapper 与 include 接线（§4.3.10-12）**：`entry.h.ftl` ptrcall wrapper——packed 参数经
  白名单 (a) 入向物化（调后 destroy），packed 返回经 (a) 出向拷贝写出；call_func wrapper 经
  既有 gate + unpack/pack 名映射自动切换为"类型检查 + Variant 拷贝"（身份保持）。
  `engine_method_binds.h.ftl` ptrcall 路径——packed 参数物化原生 slot（调后 destroy），
  packed 返回 raw slot + `wrap_temp`；vararg 路径经 pack/unpack 名映射自动正确。
  `entry.c.ftl` `initialize()` 在 `gdcc_init()` 后调用 `gdcc_packed_ref_init()`；
  `gdcc_helper.h` 接入 `gdcc_packed_ref.h`（先于 `gdcc_intrinsic.h`），`gdscript_builtins.h`
  自带 include 且 `GDCC_LEN_PACKED_CASE` 改走 internal_ptr（消除禁令符号）。
- **静态变量与默认参数核对（清单第 11 项）**：static backing 声明/初始化/析构均经
  renderGdTypeInC/ConstructArrayInsn/renderDestroyFunctionName 自动切换；默认参数维持
  caller-side 逐调用物化 + wrapper `defK(...)`（§2-17 不变，PARAM_DEFAULT_SHARED 通过）。
- **生成代码禁令验收**：对双跑模块全量生成产物（entry.c/entry.h/engine_method_binds.h）
  grep 验收 0 命中；`CCodegenTest` 新增永久回归锚定（逐文件扫描全部生成产物中的
  `godot_new_Packed*Array_with_*`/`godot_new_Variant_with_Packed*Array`/裸
  `godot_new_Packed*()`）。

**双跑验收（Godot 4.5.2 + zig 实测）**：归属本阶段的 11 个用例（§2 第 1、2、10、12-16、
21、21-hash、22 行）全部启用并通过，Phase A 基线 10 例保持通过。启用机制：
`PackedRefSemanticsCase` 以**语义门控**登记每个用例——`ASSERTED`（当前必须对齐 golden）或
按能力缺口暂缓（`DEFERRED_FRONTEND_WRITEBACK_ROUTES` / `DEFERRED_FULL_MATRIX_ACCEPTANCE`），
另以 `baseline` 标记锁定引入 harness 起即对齐的回归下限集；阶段归属叙事只保留在本文档中，
代码不出现执行阶段概念（AGENTS.md 语义自描述要求）。注册表测试锚定断言清单、回归下限集与
暂缓分组不变式。

**超范围自愈现象（保持原 phase 归属，未提前启用）**：CORO_AWAIT（23）、SIGNAL_MULTI（24）、
DYNAMIC_VARIANT_MUTATION（dyn）在 gdcc 侧输出已与 golden 一致——协程 frame/信号回调的
Variant 存储切换顺带修复（SIGNAL_MULTI 的 `variant_get_indexed failed` 崩溃随索引路径重写
消失；动态 Variant receiver 在共享身份下现行冗余写回无害且正确）。7a
（BUILTIN_PROPERTY_MUTATION）仍按预期分歧（gdcc=2 vs 解释器=1），STATIC_VAR/LAMBDA_CAPTURE
仍编译期 fail-closed——三者均属 Phase D 范围。

**专项验收**：append_array/duplicate/slice 参数与返回值 ABI 单测通过（`CallMethodInsnGenTest`）；
混合类型运算符载体测试通过（`COperatorInsnGenTest`）；ptrcall 例外**运行测试**通过
（`PackedRefStorageModelSmokeTest.ptrcallBoundaryShouldIsolateCallerIdentityInBothDirections`——
fake 引擎按引擎身份合同建模，对生成的 wrapper 序列断言双向身份隔离、callee 侧共享可见、
持有者计数平衡）；迭代器活迭代运行测试通过（`packedIteratorShouldIterateLiveAndStayBalanced`——
正向：迭代中 append 被本轮访问且别名可见；负向：中途缩容 live-size 提前终止、OOB `get`
返回 family 默认值、全生命周期持有者平衡无泄漏）。
`./gradlew test --no-daemon --console=plain` 全量回归通过（4617 测试，0 失败）。

**Phase C 实测对原计划的补充修订**（已反映在上文与 §4.3 对应节注）：

1. **静态同型 `as` 的语义确认**：§4.3.7 的探针基线仅覆盖 Variant 源；本阶段补测确认静态
   同型 `as` 同为 COW 拷贝，因此 `ExplicitCastSupport` 的同型 packed 分类显式改为
   BUILTIN_RUNTIME_CAST（否则 IDENTITY → AssignInsn 会产生共享，违反 §2-22）。
2. **unpack 的 Array 转换臂**：§4.1 的"类型检查 + Variant 拷贝"在 Array payload 下需为转换
   （解释器实测），非报错；白名单 (d) `new_from_array` 承担该臂。
3. **索引读保持 Variant API 路径**：§4.3.5 的"作用于内部指针"在读取侧经共享 Variant 拷贝
   物化即可满足（语义等价），写侧直传存储 Variant；二者均不产生 struct 穿越。

**审阅加固记录（review-expert-a / review-expert-c 并行审阅后修复，均经复核确认解决）**：

1. **unpack 共享分支改 carrier-first 写序**：`emitPackedUnpackAssign` 精确 family 分支由
   `callAssign` 改为 `moveOwnedCallIntoSlot`——holder 拷贝先于旧槽销毁，消除未来任何
   源/目标同槽路由的 use-after-destroy 风险（当前调用点本就槽位相异，此为纪律对齐加固）；
   测试新增"拷贝先于销毁"顺序锚定。
2. **HRX lambda schema 同时编码语义类型名与 C 存储类型**：存储切换后 packed 各 family 与
   Variant 的 C 名同为 `godot_Variant`，仅编码 C 名会使热重载指纹碰撞（跨 family 重绑定
   会经错误 family 的 internal_ptr getter 解引用旧 holder，引擎侧 UB）。schema 字段现为
   `<语义类型名>@<C存储类型>`；格式变化使旧连接 fail-closed（安全升级方向，符合 §7 第 6 条
   预期）。测试锚定 packed family 两两碰撞消除与相同布局的 rebind 兼容。
3. **迭代器运行探针升级为生成协议镜像**：探针复现前端真实双槽协议（`next` 临时槽覆盖 +
   `AssignInsn` 经 copy helper 回写 state 槽），循环内锚定四持有者（src+alias+state+
   next_temp）共享同一 backing 的计数不变式；另修复探针自身 shrink 段复用耗尽迭代器的 bug。
4. **ptrcall wrapper 文本锚定补充顺序约束**：`assertOrdered` 锁定"入向物化 → 调用 → 出向
   写出 → 逆序清理"的相对顺序（不仅是符号存在性）；编译出的真实 wrapper 的端到端驱动测试
   留待 Phase F 混合调用自动回归统一承载（§6 Phase F 已列）。

**后续微调（2026-09-25）**：为新增 C helper 标注分支预测提示——`gdcc_packed_ref.h` 全部
fail-fast 分支（NULL self / 未初始化 / getter 返 NULL / init 接口与 family getter 缺失）与
`for_packed_array_iter.h` `get` 的 OOB 分支标注 `unlikely(...)`（首次启用既有
`gdcc_likely.h`，clang/gcc 下展开为 `__builtin_expect`，其余编译器退化为普通布尔判断，
不改运行语义）。`GdccPackedRefRuntimeSmokeTest.headerShouldCompileStandalone` 的自包含合同
注释同步放宽为允许 `gdcc_likely.h` 叶级依赖。

#### Phase C 原始内容（保留备查）

- 内容（文件清单为必改集合，实施时按 §4.3 各执行）：
  1. `CGenHelper.java`：packed 类型 storage/参数/返回 C 类型映射（`:344-402`）；
     copy/destroy helper 名映射（`:1297-1348`）；pack/unpack 渲染（`:1167-1284`）；
     `renderPtrcallNonObjectArgExpr`（`:1056-1062`）；`renderCallWrapperUnpackExpr` /
     `renderPackFunctionName`；`needsAddressOf` 相关形状。
  2. `CBodyBuilder.java`（assign/return/临时值生命周期/receiver 与实参地址渲染）与
     `CBodyBuilderAliasSafetySupport.java`（复核 borrowed-RHS stable-carrier 在 Variant
     模型下的必要性；允许简化但保留保守安全路径）。
  3. `DestructInsnGen.java` / `CCodegen.java`（scope-exit 自动析构改 Variant destroy）。
  4. `ConstructInsnGen.java` / `CBuiltinBuilder.java` / `CGenHelper.renderDefaultValueExprInC`：
     `ConstructArrayInsn` 的 packed 分支改发 empty-Variant 构造（§4.3.2）；带参数的
     `ConstructBuiltinInsn` packed 构造走白名单 (d)（§4.1），验收含
     `PackedInt32Array(Array)` 带参构造回归；默认值表达式不得再生成
     `godot_new_Packed*()` 裸 struct。
  5. `CallMethodInsnGen.java` / `InsnGenSupport.java`：receiver、packed 实参、packed 返回
     值的地址/物化渲染（§4.3.3、§4.3.4）。
  6. `IndexStoreInsnGen.java` / `IndexLoadInsnGen.java`：typed packed self 索引读写改走
     内部指针（§4.3.5）。
  7. 运算符发射（`OperatorInsnGen.java` 等）与 `gdscript_builtins.h`
     （`GDCC_LEN_PACKED_CASE` 等按 `godot_Packed*` 取值的宏）：操作数取内部指针（§4.3.6）。
  8. `BuiltinCastInsnGen.java`：同 family `as` 分支（§4.3.7）。
  9. `intrinsic/for_packed_array_iter.h` 重写 + `GdccForPackedArrayIterType.java` 布局注释 +
     intrinsic 发射（§4.3.8），三者同阶段变更。
  10. `entry.h.ftl` / `func.ftl` / `entry.c.ftl`：call_func wrapper（§4.3.12）、ptrcall
      wrapper（§4.3.11）、协程 frame copy/destroy（§4.3.10）、内部函数签名；
      `gdcc_packed_ref.h` 的 include 接线（Phase B 产物接入编译单元）。
  11. 静态变量与默认参数路径核对：static packed 字段的初始化与存储走同一 Variant 映射；
      默认参数维持 caller-side 逐调用物化 + wrapper-side `defK(...)`（§2 第 17 行不变）。
- **生成代码禁令（验收时 grep 检查）**：白名单转换（§4.1 (a)(b)(c)(d)，其中 (a) 含入向
  物化与出向返回拷贝两个方向）全部集中由 `gdcc_packed_ref.h` 的具名 helper 提供。
  grep 范围为 **gdcc 业务发射产物（func/entry 等模板输出）与 GDCC 自有头文件**，排除
  `include_451/godot/**` 生成绑定（其声明/实现天然包含这些符号），该范围内仅豁免
  `gdcc_packed_ref.h`：范围中不得出现任何 `godot_new_Packed*Array_with_*`、
  `godot_new_Variant_with_Packed*Array` 或裸 `godot_new_Packed*()` 的直接调用
  （`godot_new_Variant_with_Variant` 不受限）——call_func 与内部 ABI 的身份传递路径（赋值、
  别名、传参、返回、cast、pack/unpack、wrapper 中转）只允许
  `godot_new_Variant_with_Variant`；ptrcall 入向/出向与显式构造只允许调用白名单
  helper。grep 命中即违规。
- 验收：Phase A 中归属本阶段的用例（1–3、5–6、7b、8–10、12–16、18–22）启用并通过；
  第 4、11 行（STATIC_VAR、LAMBDA_CAPTURE）为编译期 fail-closed，由 Phase D 解锁
  （见 Phase A 状态修订 2）。append_array/duplicate/slice 参数与返回值专项测试通过；混合类型运算符
  （`int in PackedInt32Array`，标量 left 按值、packed right 取内部指针）载体测试通过；ptrcall 例外**运行测试**
  （断言身份不共享）通过；`script/run-gradle-targeted-tests.sh` 目标测试通过后执行
  `./gradlew test --no-daemon --console=plain` 全量回归通过。

### Phase D：前端 gate 与 route 调整

- 内容：§5 全表——`FrontendWritableTypeWritebackSupport` 引入 route-provenance 分流；
  CFG builder 停止为 packed direct-slot receiver 发布 `DIRECT_SLOT` step；内建引擎属性
  route 移除 packed 写回；`gdcc_variant_requires_writeback` 显式 10 个 packed kind 返回
  `false`（补 `PACKED_VECTOR4_ARRAY`）；同步调整相关单测断言：原断言 packed direct-slot
  存在 commit step、runtime gate 为 true 的用例反转；**脚本属性 route 现有
  `StorePropertyInsn` 断言保持不变**（如
  `runLowersTypedInstanceContainerSubscriptThroughPropertyRoute`——该写回按 §5 保留）；
  内建引擎 getter route 新增用例断言**不生成** `StorePropertyInsn`；
  `FrontendCfgGraphBuilder.appendCallReceiverCommitSteps` 的 STATIC_CONTEXT bare 属性
  分支按 §5 表保留冗余写回。
- 验收：Phase A 中 7a、动态 Variant receiver 用例、STATIC_VAR 与 LAMBDA_CAPTURE（编译期
  fail-closed 解锁后）启用并通过；目标测试通过后全量回归通过。**遵守本节顶部硬顺序约束。**

### Phase E：文档重写

- 必改：
  - `gdcc_type_system.md` §90-137：Packed*Array 移入 shared/reference family；静态矩阵改为
    route-provenance 三档描述；runtime helper 矩阵同步；§130 default-true 冻结合同保留。
  - `gdcc_c_backend.md`："Use GDCC Class Types"（Packed*Array 从 value-semantic 组拆出，
    新增 Variant-backed 存储节）；§318-320 helper 冻结段同步；新增 §1.3 例外节并双向
    链接本文档。
  - `gdcc_ownership_lifecycle_spec.md`：先核对 §3.2/3.5/3.10/4.2/4.4 现行措辞再改——
    明确 packed 的 copy=共享身份（Variant 持有者拷贝）、destroy=释放持有；协程
    copy-on-capture 段澄清"拷贝复制持有者而非底层 Vector"。
  - `gdcc_low_ir.md`：destruct 节与 construct 节（`:162` 附近，ConstructArrayInsn 语义）。
  - `gdcc_lir_intrinsic.md` for_packed_iter 节（`:764-785`，活迭代合同）；
    `gdcc_runtime_lib.md` 对应节（`:77-79`）。
  - `construct_array_implementation.md`（`:34-78`，零参 struct 构造改为 empty-Variant）。
  - `cbodybuilder_implementation.md` §2.1/4.2、`load_store_property_implementation.md`
    §2.4/4.5、`index_insn_implementation.md` §4.3/5.2/6。
  - `frontend_complex_writable_target_implementation.md` §3.5/4.3/5.1-5.2/7.1/9、
    `frontend_lowering_cfg_pass_implementation.md` §4、
    `frontend_dynamic_call_lowering_implementation.md` §3.3/7（route 分流矩阵、7a 行为
    变更、回归基线更新）。
  - `hot_reload_implementation.md`（`:265` 附近）：`renderGdTypeInC` 进入 fingerprint，
    类型 C 名变为 `godot_Variant` 后旧连接 fail-closed——记录为可接受的升级行为。
- 核对（仅在确实受影响处补充说明）：`variant_abi_contract.md`、
  `typed_array_abi_contract.md`、`typed_dictionary_abi_contract.md`、
  `call_method_implementation.md`、`assign_insn_implementation.md`、
  `backend_ownership_lifecycle_contract.md`、`lifecycle_instruction_restriction.md`。
- 验收：文档间引用一致；例外与行为变更在两处以上文档间双向链接。

### Phase F：全量验收

- 内容：Phase A 测试全量启用（含 23、24）；`./gradlew clean build --no-daemon
  --console=plain`；现有 Godot 集成测试套件全量通过；GDScript↔gdcc 混合调用（call_func
  身份保持、ptrcall 例外）纳入**自动回归**而非人工抽查；混合场景（属性 + 信号 + lambda +
  协程 + 循环组合）双跑比对。
- **双跑 harness 清理（全量验收通过后执行）**：全部用例转为断言态后，迁移期脚手架按下列
  清单退役，行为合同的终身回归由保留项承担。
  - 退役：`AssertionGate`/`baseline` 字段与暂缓跳过逻辑；STATIC_VAR、LAMBDA_CAPTURE 迁回主
    探针库后，伴随库 `packed_ref_probes_blocked.gd`、`GDCC_COMPILE_BLOCKED_CASE_NAMES` 与
    tripwire 断言一并删除；`PackedRefSemanticsCase` 注册表类与
    `PackedRefSemanticsCaseRegistryTest` 删除（golden 行序即唯一事实源，
    `ProbeGoldenComparison` 的未知用例/重复/顺序结构检查在全量比对下足以捕获漂移；
    §2 行号归属由本文档与探针注释承担）。
  - 保留：探针库 `packed_ref_probes.gd` 与 golden（§2 矩阵及 §1.3 例外的行为合同锚）；
    简化后的 `PackedArrayReferenceSemanticsDualRunTest`（改为 golden 全量断言，解释器侧
    基线校验不变）；`ProbeOutput`/`ProbeGoldenComparison` 及其单测（独立的解析/比对合同）；
    `GdccPackedRefRuntimeSmokeTest` 与 `PackedRefStorageModelSmokeTest`（fake 引擎层
    identity/fail-fast 锚定）。
- 验收：构建全绿；无新增 known-limit 记录（或新增记录经人工确认属于 §1.3 例外）；harness
  清理不减少任何用例的 golden 断言覆盖。

## 7. 风险与缓解

1. **`variant_get_ptr_internal_getter` 属于半内部 API**：godot-cpp 亦依赖之，4.5.x 绑定
   已暴露。缓解：初始化期可用性检查 + fail-fast；若未来 Godot 移除，需回退本计划
   （git 可追踪各 Phase）。
2. **阶段顺序**：Phase C 必须先于 Phase D（或同提交），见 §6 硬顺序约束；违反会重新引入
   丢更新且可能静默通过部分旧测试。
3. **活迭代安全性**：`get` 每次 live-size 检查 + `operator_index_const`，不缓存基址，
   realloc/缩容安全。性能**不是**风险项：Godot 4.5.2 实机探针（`tmp/probes/iter_perf`，
   手写 GDExtension 对照现行 intrinsic 与计划形态，100k 元素 × 200 趟 × 7 轮取最优）
   测得现行快照迭代器 35.3 ns/元素、计划形态 12.5 ns/元素、融合 size 求值的优化形态
   6.9 ns/元素（i32 与 f64 一致，checksum 相同）——现行形态每次 `next` 都伴随一对
   GDExtension 构造/析构调用，成本高于计划形态的廉价逐次调用。后续可选优化：融合
   `should_continue`/`get` 的 size 求值。
4. **nil Variant 误用**：所有 packed slot 默认初始化统一走 empty-Variant helper；Phase C
   验收包含"声明后未显式赋值直接调用方法"用例。
5. **C 侧类型擦除**：存储统一为 `godot_Variant` 后 C 编译器无法区分具体 packed family，
   类型安全由 LIR 类型系统与类型检查 helper 保证；getter 缓存按 family 静态区分；内部
   指针 getter 对类型不匹配的 Variant 是未定义行为，`get_type()` 检查是必需前置。
6. **热重载 fingerprint**：packed 参数/字段的 C 类型名变化使旧连接 fail-closed（拒绝
   重载而非错误重载），属安全的升级行为，Phase E 记录。
7. **`+=` 语义泄漏**：运算符实现必须走"新数组 + 重绑定"，禁止 in-place append（§4.3.6），
   验收用 `b := a; a += x; b` 身份不变用例锁定。
8. **性能**：方法调用多一次间接 getter 调用（常数级）；Variant 拷贝与 struct 拷贝同为
   一次原子引用计数。

## 8. 验收测试映射

- §2 矩阵 24 行 → Phase A 建立的双跑对照测试类；各行标注启用 Phase（A/C/D/F）。
- 专项：append_array/duplicate/slice 参数与返回值 ABI（Phase C）；ptrcall 例外运行测试
  （Phase C）；生成代码禁令 grep 检查（Phase C）；`+=` 身份不变用例（Phase C）；
  协程 frame 双向可见性（Phase C/F，基线由 Phase A 探针锁定）。
- 保留并更新：现有 LOCAL_VAR snapshot 写回回归（反转为断言 packed 无 commit step）、
  动态 Variant gate 测试（10 个 packed kind 全 false，含 Vector4）、for-iter intrinsic
  测试（活迭代语义）、wrapper ABI 测试（call_func 身份保持、ptrcall 例外）。
