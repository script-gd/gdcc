# Packed*Array 引用语义实现

> 本文档作为 Packed*Array（PackedByteArray / PackedInt32Array / PackedInt64Array /
> PackedFloat32Array / PackedFloat64Array / PackedStringArray / PackedVector2Array /
> PackedVector3Array / PackedColorArray / PackedVector4Array）引用语义实现的长期事实源。
> 只保留当前代码已经落地的语义合同、架构决策、边界约定与对后续工程仍有价值的反思，
> 不记录阶段性实施流水账。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - `src/main/java/gd/script/gdcc/backend/c/**`（packed 存储映射与调用发射）
  - `src/main/java/gd/script/gdcc/frontend/**`（写回 route 分流）
  - `src/main/c/codegen/include_451/gdcc/gdcc_packed_ref.h`
  - `src/main/c/codegen/include_451/gdcc/intrinsic/for_packed_array_iter.h`
  - `src/test/resources/packed_ref_semantics/**`
  - `src/test/java/gd/script/gdcc/backend/c/build/packedref/**`
- 更新时间：2026-09-25
- 语义基线：Godot 4.5.2 解释器实测
- 关联文档：
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`

## 1. 背景与问题起源

改造前 gdcc 将 Packed*Array 以 **by-value C 结构体**存储（`godot_Packed*Array`，
16 字节 opaque struct），赋值/别名/传参时经 `godot_new_<Type>_with_<Type>` 拷贝构造。
该拷贝是 C++ `Vector<T>` 层拷贝，首次写入触发 COW detach，形成值语义。而 Godot 4.4+
官方文档与 4.5.2 解释器实测均确认 Packed*Array 在语言层为**引用（共享）语义**：Variant
内部以指针持有堆上的共享数组实例（引擎侧 `PackedArrayRef`），所有别名持有者对
mutation 互相可见。

由此产生的行为分歧（实机探针证实）：局部别名不可见、形参 mutation 对调用方不可见、
`for-in` 迭代期间新增元素不被访问、Variant 双向转换后身份分裂。当前实现已将存储与
运算语义严格对齐解释器引用语义，覆盖：局部变量、参数、脚本属性、静态变量、
Array/Dictionary 元素、信号参数、lambda 捕获、协程 frame、`for-in` 迭代、Variant
双向转换、`duplicate()`、`+=` 重绑定、cast/类型测试、运算符与索引读写。

## 2. 语义合同（解释器实测基线）

以下矩阵是已实现并由双跑测试锁定的行为合同。每行对应
`src/test/resources/packed_ref_semantics/packed_ref_semantics_golden.txt` 中由
Godot 4.5.2 解释器锁定的 golden 行，用例名与顺序另由
`PackedRefSemanticsGoldenInventoryTest.MATRIX_CASE_NAMES` 独立锚定。

| # | 场景 | 解释器行为（= gdcc 行为） |
|---|---|---|
| 1 | `var b := a; a.push_back(7)` | `b` 可见（共享） |
| 2 | callee 对形参 mutation | 调用方可见（共享） |
| 3 | 脚本属性 `payloads.push_back`（self / 外部） | 持久可见 |
| 4 | 静态变量 mutation | 持久可见 |
| 5 | `Array[PackedInt32Array]` 元素 mutation | 持久可见 |
| 6 | Dictionary 值中的 packed mutation | 持久可见 |
| 7a | 内建属性 getter mutation（`poly.polygon.push_back`） | **不持久**（getter 返回副本） |
| 7b | 内建属性重赋值（取出 mutation 后赋回） | 持久 |
| 7c | 内建属性索引写（`poly.polygon[0] = v`） | **持久**（read-modify-write，赋值 route 写回） |
| 8 | `a += b` | 产生新数组并**重绑定**，旧别名不可见 |
| 9 | `a.duplicate()` | 独立副本 |
| 10 | 信号参数 mutation | 发射方可见 |
| 11 | lambda 捕获后 mutation | 捕获方/外部互相可见 |
| 12 | `for v in a` 期间 `a.push_back` | **活迭代**：新元素会被本轮访问 |
| 13 | `append_array` | 共享 |
| 14 | `resize` | 共享 |
| 15 | `a[0] = 99` 索引写 | 共享 |
| 16 | `Variant` ↔ typed 转换（赋值） | 保持共享（三方共享） |
| 17 | 参数默认值 | 每次调用物化新数组 |
| 18 | 元素写入重绑定：`var e := arr[0]; arr[0] = 新数组` | `e` 仍指向旧数组（元素槽重绑定） |
| 19 | PackedStringArray 迭代 | 元素为 String 副本，值相等 |
| 20 | `in` 成员测试 | 内容匹配 |
| 21 | `==`/`!=` 与 Dictionary 键 | 内容相等；键按内容哈希，插入后经共享别名 mutation 使旧条目查找 missing |
| 22 | `v as PackedInt32Array`（同 family） | **COW 拷贝（新身份），非共享**——实测推翻"保持共享"的原始预期 |
| 23 | 协程形参/捕获在 await 挂起前后双向 mutation | 双向可见 |
| 24 | 多参数 typed 信号携带 packed | 共享 |

矩阵之外的组合场景同样以双跑锁定：动态 Variant mutation、混合场景（属性 + lambda +
信号 + 协程 + 活迭代同链组合）、复杂控制流分支体内 mutation、返回值身份五种形态、
引擎方法 packed 参数/返回边界、PackedStringArray mutation 专项。

## 3. 引擎机制依据（godotengine/godot 4.5 分支核实）

设计决策的引擎侧论据：

1. GDScript VM 的局部槽（含 typed packed local）均为 `Variant`
   （`modules/gdscript/gdscript_vm.cpp:571-574`），typed 信息只用于静态检查。
2. VM 对 builtin 方法的验证调用路径（`OPCODE_CALL_BUILTIN_TYPE_VALIDATED`）把 base
   `Variant*` 直接交给方法；`core/variant/variant_call.cpp` 经
   `VariantGetInternalPtr<T>::get_ptr(base)` 取内部值指针原位调用，不构造副本。
3. **GDExtension 暴露同一能力**：`variant_get_ptr_internal_getter`
   （`core/extension/gdextension_interface.h`）返回按类型的内部值指针 getter，文档
   明确允许用于原位修改；本项目绑定暴露为
   `godot_variant_get_ptr_internal_getter`（`godot_interface.h`）。
4. `+` 运算符产生新数组（`core/variant/variant_op.h`），`+=` 由语言层重绑定。
5. lambda 捕获按 Variant 拷贝保存，共享内部实例；GDScript 成员槽为 `Vector<Variant>`，
   属性读取返回 Variant 拷贝，共享内部实例。
6. typed Array 元素存储为 `Vector<Variant>`，元素访问返回 Variant 引用，packed 元素
   mutation 经 Variant 层共享。
7. GDExtension typed 拷贝构造是 `Vector` 层拷贝（`variant_construct.h`），首写触发
   COW（`cowdata.h`）——这正是旧 struct 存储模型产生值语义的根源。

## 4. 架构决策

### 4.1 核心决策：Variant-backed 存储

Packed*Array 的**规范存储形式**为 `godot_Variant`（不再是 `godot_Packed*Array`
by-value struct）：

- **身份共享**：Variant 拷贝（`godot_new_Variant_with_Variant`）共享引擎堆上的同一
  `PackedArrayRef`，引用计数由引擎管理，gdcc 无需自建 box/refcount。
- **原位 mutation**：调用 builtin 方法时，先经缓存的
  `GDExtensionVariantGetInternalPtrFunc` 取内部值指针，再以其为 base 执行既有
  `GDExtensionPtrBuiltInMethod` wrapper（wrapper 签名不变，仅 base 实参来源变化）。
  语言层下标写不走这条路径，见 §5 第 4 条。
- **核心不变式**：gdcc 持有的 packed 值**禁止**经过 struct pack/unpack
  （`godot_new_Packed*_with_Variant` / `godot_new_Variant_with_Packed*`）。仅有的合法
  struct 边界（白名单，集中定义于 `gdcc_packed_ref.h`）：
  (a) ptrcall ABI 边界双向——入向 struct→Variant 物化与出向 Variant→struct 返回拷贝；
  (b) empty 构造时的临时 struct（此刻尚无共享者）；
  (c) builtin 方法返回的原生 struct 临时值（立即包装为 Variant 并 destroy）；
  (d) 带参数的 packed 构造器——跨类型构造（如 `PackedInt32Array([1, 2])` 经
  `godot_new_PackedInt32Array_with_Array`）与显式同型构造
  （`PackedInt32Array(otherPacked)`，产出**独立新数组**，与 `duplicate()` 等价）；
  同 family `as` cast 复用同型拷贝构造路径（见 §5）。
  构造/cast 实参按原生 ABI 形状渲染，结果接入原生临时 struct，立即包装为 Variant 并
  destroy temp。
- **不得**依据 CowData refcount 做任何复制决策（外部 ptrcall 拆箱或 getter 副本会使
  rc>1，那是另一 `PackedArrayRef` 的正常现象）。
- **Variant ↔ typed 转换按 payload 分流**（`InsnGenSupport.emitPackedUnpackAssign`）：精确
  family 的 payload 是类型检查 + Variant 持有者拷贝（三方共享）；`Array` payload 走
  白名单 (d) `new_from_array`，产出独立数组（解释器实测锚定）；其余 kind 为运行时
  类型错误，赋值不生效。

### 4.2 否决方案及理由

1. **维持 struct 存储 + 扩大写回**：无法修复局部别名共享（`b := a` 要求两个 slot 永久
   同步）与 GDScript 边界；形参写回还需要 assign-through-pointer 架构改造。
2. **手动引用计数盒（heap struct + gdcc 管理 refcount）**：身份仍在每次 Variant
   pack/unpack 处断裂（struct 拷贝 detach），且重复引擎已有的 Variant 引用计数。

## 5. 设计约定

1. **C 存储形状**：局部变量、参数、实例字段、静态字段、协程 frame 字段中的 packed
   类型统一为 `godot_Variant`；内部 gdcc 函数签名的 packed 参数/返回类型相应为
   `godot_Variant*` / `godot_Variant`（仅内部 ABI）。形参共享身份直接消解了旧
   PARAMETER 写回限制：mutation 经共享身份对调用方可见，不需要 assign-through-pointer。
   存储映射入口：`CGenHelper`；helper 命名集中入口：`PackedRefCNames`。
2. **默认初始化**：`var a: PackedInt32Array` 必须初始化为**空数组 Variant**
   （per-family helper：临时 struct 构造 → `godot_new_Variant_with_<Type>` →
   destroy temp），**严禁** nil Variant（nil 无内部值指针，方法调用会失败）。
3. **方法调用 receiver/参数/返回值 ABI**（发射支持：
   `PackedNativeAbiCallSupport`）：receiver 与 packed 实参渲染为 Variant 内部指针
   （getter 按 `GDExtensionVariantType` per-family 缓存，初始化期可用性检查，缺失
   fail-fast）；packed 返回值先接入原生临时 struct，再包装为新 Variant 并 destroy
   临时 struct（临时值未经过写入，不发生 detach；新 Variant 是独立 `PackedArrayRef`）。
4. **索引读写**：packed 存储本身就是 Variant，语言层下标读经 `godot_variant_get_indexed`
   （`IndexLoadInsnGen`），下标写直接把存储 Variant 槽交给 Variant indexed setter 原位
   修改共享数组，不发射 pack/unpack 写回（`IndexStoreInsnGen`）。内部指针用于 builtin
   方法/运算符求值与迭代器 `get`（`operator_index_const`），不用于语言层下标路径。
5. **运算符**：`==`/`!=`/`in` 等内容运算**按操作数逐个处理**：packed 操作数取其
   Variant 内部指针，非 packed 操作数（标量等）保持 op evaluator wrapper 声明的原生
   ABI 形状。`+` 在原生临时 struct 中产出新数组后立即包装为新 Variant。**禁止**将
   `+`/`+=` 优化为对内部指针的 in-place `append_array`（会把 mutation 泄漏给旧别名，
   违反 §2 第 8 行）。
6. **cast 与类型测试**：`is` 经 `Variant.get_type()` 与目标 `GDExtensionVariantType`
   比较。同 family `as` cast **产生 COW 拷贝（新身份）**（§2 第 22 行实测）：内部指针
   → 同型拷贝构造（白名单 (d)）→ 立即包装为新 Variant。`ExplicitCastSupport` 负责
   分类，backend 经 runtime-cast surface 发射。
7. **for-in 迭代器**（`intrinsic/for_packed_array_iter.h` + `GdccForPackedArrayIterType`
   + intrinsic 发射，三者必须同步变更）：state 持有源数组的 Variant 拷贝（共享）与
   index；`next` 只递增 index；`should_continue` 每次求 **live size**；`get` 先以
   live size 越界检查再经内部指针取元素；**禁止跨迭代缓存元素基址**（mutation 可能
   realloc，缓存指针悬垂）。PackedStringArray 元素按访问拷贝 String。
8. **析构**：所有 packed slot 的析构统一为 `godot_Variant_destroy`（引擎递减共享
   引用），不存在 packed struct 析构。
9. **协程 frame 与 lambda 捕获**：参数/捕获/返回字段为 Variant；"拷贝"语义为 Variant
   持有者拷贝（共享身份），**不得** struct copy-construct。
10. **C 侧类型擦除的补偿**：存储统一为 `godot_Variant` 后 C 编译器无法区分具体
    packed family，类型安全由 LIR 类型系统与类型检查 helper 保证；内部指针 getter 对
    类型不匹配的 Variant 是未定义行为，`get_type()` 检查是必需前置。热重载 schema
    因此必须保留语义类型信息（`CHrxIdentityCatalog`），防止错误 family 的 holder 被
    重新绑定。

### ABI 边界例外

- **ptrcall ABI 边界（身份隔离）**：ptrcall 入口的 packed 参数以原始 struct 指针
  （`Vector<T>` 值槽）传入，callee 做 struct→Variant 物化（`Vector` 层拷贝），此后
  mutation 与调用方隔离；packed 返回值经 ptrcall 写出时同样发生 struct 拷贝。出向
  调用（gdcc→engine）的 packed 实参反向物化：Variant→临时 struct→ptrcall→调用后
  销毁。该例外只影响使用 ptrcall 的扩展间调用路径；GDScript 与 GDExtension 之间的
  常规调用走 `call_func`（Variant ABI），**不受此例外影响**。Godot 对 GDExtension
  脚本方法的调用恒走 call_func，故 ptrcall wrapper 无真机触发路径，其序列正确性由
  文本锚定测试与 fake-引擎运行测试双重锁定。
- **call_func 边界（身份保持）**：wrapper 入参从 `const godot_Variant*` 拷贝构造本地
  Variant，callee mutation 对 GDScript 调用方可见；packed 参数/返回只经
  `godot_new_Variant_with_Variant`，禁止 struct 中转。
- **内建属性 getter 返回副本**：引擎语义（`poly.polygon.push_back(x)` 在解释器中同样
  不持久），不是 gdcc 分歧。

### 不受影响的范围

- `String` / `StringName`：语言层无 mutating 方法，维持 struct 值语义。
- `Array` / `Dictionary` / `Object`：本已是共享/引用语义，仅 Packed*Array 作为其
  元素/值时间接受益。
- typed Array / typed Dictionary ABI 合同：packed 作为 outward leaf 的 hint 与
  `godot_Array` 物理 carrier 不变。

## 6. 前端写回分流（route provenance）

旧写回机制为补偿值语义而设。Variant 存储下按 route provenance 决策（实现入口：
`FrontendWritableTypeWritebackSupport`、`FrontendWritableRouteSupport`、
`FrontendCfgGraphBuilder`、`FrontendLoweringBodyInsnPass`）：

| route | 行为 |
|---|---|
| direct-slot（LOCAL_VAR）snapshot commit | **不发布**（Variant 拷贝共享身份，写回冗余） |
| direct-slot（PARAMETER） | 不涉及 step；形参共享身份，mutation 天然可见 |
| 内建引擎属性 route（`poly.polygon`）mutating call | **不写回**（getter 副本语义；与旧 gdcc 合同相反，属有意对齐） |
| 内建引擎属性 route 显式赋值 / 索引写（§2-7b/7c） | 写回（read-modify-write 持久化） |
| GDCC 脚本属性 route | 保留写回（冗余无害，存回同一身份；可选清理项） |
| STATIC_CONTEXT 静态属性 route | 不追加 step；Variant 存储使静态 leaf 共享身份后自然解锁（旧值语义 carrier 曾被 static-terminal 合同 fail-closed） |
| Array/Dictionary 元素 route | 保留写回（冗余无害；可选清理项） |
| method-result route（`get_baked_points().push_back`） | 不写回 |

动态 Variant receiver route：runtime gate helper `gdcc_variant_requires_writeback`
对全部 10 个 packed kind 返回 `false`（**必须显式列出全部 kind**——漏列会落入
`default: true`）；`default` 分支保持 `true`（"未知 kind 保守 true" 的冻结合同，见
`gdcc_type_system.md`），其余值类型分支（`String`、`Vector*`、`Color` 等）不受影响。

**迁移顺序教训**：存储切换必须先于（或同提交于）DIRECT_SLOT snapshot commit 的关闭；
反向顺序会在 struct 存储下重新引入 snapshot detach 丢更新，且可能静默通过部分旧测试。

## 7. 验证架构

双跑对照体系（解释器与 gdcc 编译产物运行同一探针库，逐行比对）：

- `src/test/resources/packed_ref_semantics/packed_ref_probes.gd`：双跑共用探针库，
  按固定顺序输出 `PROBE|<CASE>|<payload>`。
- `packed_ref_semantics_golden.txt`：Godot 4.5.2 解释器锁定的 payload 事实源；golden
  行序即用例顺序。
- `PackedArrayReferenceSemanticsDualRunTest`：双跑入口，两侧均对完整 golden 断言；
  缺 Godot 或 Zig 时按条件跳过相应部分。
- `PackedRefSemanticsDualRunHarness` / `ProbeOutput` / `ProbeGoldenComparison`：
  运行编排、输出解析（拒绝 malformed/重复 case）、golden 比对（缺失/payload 分歧/
  未知用例/相对顺序四类差异逐一定名）。
- `PackedRefSemanticsGoldenInventoryTest.MATRIX_CASE_NAMES`：**不依赖 Godot** 的用例
  清单锚点，防止探针与 golden 被同步删减导致覆盖静默收缩（锚点若置于双跑类内会随
  `GODOT_BIN` 缺失整类跳过而失效，故独立成类）。
- fake-引擎运行锚点：`GdccPackedRefRuntimeSmokeTest`（`gdcc_packed_ref.h` 独立编译、
  getter fail-fast、白名单转换）、`PackedRefStorageModelSmokeTest`（ptrcall 双向身份
  隔离 + 持有者平衡）。
- 生成代码文本锚点：`CCodegenTest`（call_func/ptrcall wrapper 序列、出向 engine
  helper 物化/销毁游标顺序、生成代码全文件白名单扫描）、`CallMethodInsnGenTest`、
  `CConstructInsnGen(Test|EngineTest)`、`BuiltinCastInsnGenTest`、
  `IndexStoreInsnGen(Test|EngineTest)`、`COperatorInsnGenTest` 等。
- 前端分流矩阵：`FrontendWritableTypeWritebackSupportTest`（family × provenance）、
  `FrontendCfgGraphBuilderTest`、`FrontendLoweringBodyInsnPassTest`、
  `FrontendWritableRouteSupportTest`。
- 端到端（test_suite）：`member/packed_call_func_identity.gd`（解释器经 call_func 把
  packed 交给编译类，正反锚定六种身份形态）、`member/packed_ref_full_usage.gd`
  （函数/循环/分支/match/字段/lambda/协程/信号全组合共享身份）。

### 探针编写约束（踩坑记录）

- golden payload **不得含 `|`**（`PROBE|<CASE>|<payload>` 三段式格式冲突）。
- gdcc 不做 bool→int 隐式转换，探针打印须 `int(...)` 显式转换。
- 新增探针必须三处同步：探针库、golden、`MATRIX_CASE_NAMES`。
- 身份合同锚定不能只测内容：边界在值语义下可能巧合通过（如引擎方法 get 返回未二次
  观测、`String.join` 读原变量而非别名），须用别名 + 重复观测双重锚定。
- 出向 engine helper 的 ABI 断言须以游标顺序锁定"物化 → 临时槽入 args → ptrcall →
  销毁"，仅断言符号存在会被 destroy/wrap 语句巧合满足。

## 8. 风险与长期注意事项

1. **`variant_get_ptr_internal_getter` 属于半内部 API**（godot-cpp 亦依赖）：初始化期
   可用性检查 + fail-fast 兜底；若未来 Godot 移除，需整体回退本存储模型。
2. **活迭代安全性**：`get` 每次 live-size 检查，不缓存基址，realloc/缩容安全。实机
   探针（100k 元素 × 200 趟）测得现行 live-size 形态 12.5 ns/元素，优于旧快照迭代器
   的 35.3 ns/元素；可选优化方向是融合 `should_continue`/`get` 的 size 求值
   （实测可达 6.9 ns/元素）。
3. **nil Variant 误用**：所有 packed slot 默认初始化统一走 empty-Variant helper。
4. **热重载 fingerprint**：packed 参数/字段的 C 类型名变化使旧连接 fail-closed
   （拒绝重载而非错误重载），属安全的升级行为。
5. **`+=` 语义泄漏**：必须走"新数组 + 重绑定"，禁止 in-place append；以
   `b := a; a += x; b` 身份不变用例锁定。
6. **调用开销**：方法调用多一次间接 getter 调用（常数级）；Variant 拷贝与 struct
   拷贝同为一次原子引用计数。

## 9. 非目标

- 不改变 `String`/`Array`/`Dictionary`/`Object` 的既有语义合同。
- GDScript↔GDExtension 常规调用的 ptrcall 化：常规调用恒走 call_func，ptrcall 例外
  仅服务扩展间路径，无推广计划。
- 脚本属性/容器元素 route 冗余写回的清理（保留以降低迁移风险，列为可选后续项）。
- 迭代器 size 求值融合的 perf 优化（见 §8-2，记录为可选方向）。
