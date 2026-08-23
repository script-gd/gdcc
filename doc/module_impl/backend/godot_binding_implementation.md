# Godot Binding 生成、使用集与 Runtime Helper 实现说明

> 本文档是 C Backend 中 Godot binding 静态 runtime support、模块级动态使用集、
> `engine_method_binds.h` 生成、builtin constructor runtime helper surface 和 exact
> engine method-bind route 的长期事实源。
> 本文整合原迁移计划与 exact route 说明，只保留当前事实、长期约定和后续工程边界。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - `src/main/c/codegen/include_451/godot/**` 的静态 runtime binding support
  - `GodotBindingTool` 的 ABI / interface / builtin / utility / fixed wrapper 生成
  - `GodotBindingUsageSession` 的模块级使用集收集
  - `engine_method_binds.h` 中的 exact engine helper、engine constructor wrapper 和
    module-local wrapper
  - `CALL_METHOD` exact engine route 的 method-bind lookup、helper 命名和 ABI 合同
- 不覆盖：
  - `CBuiltinBuilder` 的 builtin constructor 选择；见
    `doc/module_impl/backend/builtin_builder_implementation.md`
  - `CALL_METHOD` 的整体分派规则；见 `doc/module_impl/backend/call_method_implementation.md`
  - `LOAD_STATIC` 的静态常量解析；见 `doc/module_impl/backend/load_static_implementation.md`
  - Variant / typed container outward ABI；见对应 `*_abi_contract.md`
  - GDCC class 自身 signal 的 ClassDB 注册（`entry.c.ftl` `// Signals` 段，经
    `CGenHelper.renderSignalParameterMetadata`）
- 关联文档：
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/module_impl/backend/load_store_property_implementation.md`
  - `doc/module_impl/backend/index_insn_implementation.md`

## 当前最终状态

GDCC 当前不 vendor、不解压、不编译 `gdextension-lite`。生成项目编译本模块
`entry.c` 与静态 runtime source `<includeRoot>/godot/godot_binding.c`，外加协程
runtime 的两个 `gdcc/**` translation unit（`<includeRoot>/gdcc/minicoro.c` 与
`<includeRoot>/gdcc/gdcc_coroutine.c`，见 `doc/gdcc_runtime_lib.md` §Coroutine
Runtime），并包含 `godot/**` 与 `gdcc/**` helper 树。

`CCodegen.generate()` 稳定返回三个模块级 generated files：

- `entry.c`
- `engine_method_binds.h`
- `entry.h`

`entry.h` 先包含 `<godot_binding.h>` 与 `<gdcc_helper.h>`，再包含
`"engine_method_binds.h"`。因此模块级 wrapper 都以 header-only `static inline`
形式进入唯一模块 translation unit `entry.c`。`CProjectBuilder` 不新增 `projectPath`
include dir，不生成或编译 `godot_module_bindings.h/.c`，也不扫描旧工作区残留文件决定
native input。

`engine_method_binds.h` 承载三类随模块变化的内容：

- engine constructor wrappers：`godot_new_<Class>()`
- module-local Godot wrappers：当前只允许 singleton getter 与 class constant
- exact engine method-bind accessors 与 backend-owned call helpers

静态 runtime support 与模块级 wrapper 的边界固定如下：

- `godot_binding.h/.c` 只聚合静态 runtime-provided support。
- `engine_method_binds.h` 只保存当前模块实际使用且不能由静态 runtime support 提供的内容。
- `godot_*` public wrapper 名不表示旧 vendor 代码；它表示 GDCC 自有 Godot binding surface。
- exact engine method helper 是 backend-owned `gdcc_engine_*` symbol，不属于 public
  `godot_*` wrapper surface。

## 静态 Runtime Binding 分层

`src/main/c/codegen/include_451/godot/**` 是版本化 Godot 4.5.1 binding support。
该目录由 `GodotBindingTool` 生成或维护，当前分层如下：

- ABI support：
  - `godot_macros.h`
  - `godot_global_enums.h`
  - `godot_global_constants.h`
  - `godot_builtin_sizes.h`
  - `godot_builtin_layout.h`
  - `godot_builtin_types.h`
  - `godot_native_structures.h`
  - `godot_abi.h`
- interface wrappers：
  - 输入为目标 Godot 的 `gdextension/gdextension_interface.h`
  - lookup name 来自 `@name` 注释
  - C signature 来自紧随其后的 function pointer typedef
  - 输出为 `godot_interface.h/.c`
- builtin wrappers：
  - 输入为 `ExtensionAPI.builtin_classes[]`
  - 输出为 `godot_builtin.h/.c`
  - 覆盖 builtin constructor/destructor、Variant pack/unpack、member、method、operator、
    indexed/keyed accessor 和 String codec helper
- utility wrappers：
  - 输入为 `ExtensionAPI.utility_functions[]`
  - 输出为 `godot_utility.h/.c`
  - vararg utility 使用 trailing `const godot_Variant **argv, godot_int argc` 约定
  - vararg builtin method 使用同一 trailing `argv/argc` 约定
  - 两类 vararg wrapper 体内均用
    `GDExtensionConstTypePtr args[fixed + (argc > 0 ? argc : 1)]`，再以
    `(fixed + argc == 0) ? NULL : args` 调用（utility 走缓存指针，builtin 走
    `GDCC_BUILTIN_METHOD_VOID/RETURN`），避免 `argc==0` 时的零长度 VLA
- fixed wrappers：
  - 输入为版本化源码清单，当前是 `Godot451FixedBindings`
  - 输出为 `godot_fixed_binding.h/.c`
  - 用于 runtime/helper/template 需要、但不由 interface/builtin/utility 全量集合提供的
    小型稳定 wrapper surface
- aggregation pair：
  - `godot_binding.h` 聚合所有静态 Godot binding headers
  - `godot_binding.c` 聚合对应 `.c` files，并且是唯一加入 native compiler input 的
    `godot/**` runtime binding source（`gdcc/**` 侧另有 `minicoro.c` 与
    `gdcc_coroutine.c` 两个协程 runtime TU）

`godot_initialize_interface(...)` 在 `gdextension_entry(...)` 内最早运行，通过 Godot
提供的 `get_proc_address` eager resolve interface pointer table。缺失 interface entry
时初始化返回 `false`，不会进入后续 class registration 或 scene lifecycle。

## Runtime-Provided 与 Module-Local 边界

`GodotBindingProvidedSymbols` 发布当前 runtime-provided `godot_*` symbol set。这个 set
由以下来源组成：

- interface wrappers
- builtin wrappers
- utility wrappers
- fixed wrappers
- 少量明确登记的 GDCC runtime helper `godot_*` symbol

非 provided 的 `godot_*` 调用只有在同一使用集 buffer 中显式登记为 module-local 后才允许
发射。当前 module-local family 只包含：

- `ModuleLocalGodotBinding.Singleton`
- `ModuleLocalGodotBinding.ClassConstant`

不再保留 generic module-local class method family。exact engine method 使用
`EngineMethodUsageSession`，engine object constructor 使用 `EngineConstructorUsageSession`，
都不归入 `ModuleLocalGodotBinding`。

`src/main/c/codegen/include_451/gdcc/gdcc_builtin_ctor.h` 是 GDCC-owned builtin
constructor helper surface。它补齐 Extension API metadata 已声明、但
`godot_builtin.h/.c` 生成器当前跳过的 constructor helper，例如 `godot_new_int_with_int`，
并集中承载 Transform/Basis/Projection flat-float constructor shim。生成的 `entry.h`
通过 `<gdcc_helper.h>` 间接获得该头文件；`GodotBindingProvidedSymbols` 必须把其中提供的
`godot_*` helper 纳入 runtime-provided set，避免 usage collector 把这些 runtime helper
误判为 module-local missing symbol。

这条 runtime helper surface 的当前合同固定如下：

- frontend 对 `int(value)`、`float(value)`、`bool(value)` 等 bare builtin constructor call
  继续发布普通 builtin constructor route；不为 `int(int)` 这类 metadata-backed atomic
  constructor 新增 frontend intrinsic 或 backend intrinsic。
- lowering 继续把这类调用 materialize 为普通 `ConstructBuiltinInsn`；backend 通过
  `CBuiltinBuilder.constructRegularBuiltin(...)` 做 exact metadata matching，并按现有命名规则
  渲染 `godot_new_<Type>_with_<Arg...>`。
- `gdcc_builtin_ctor.h` 的职责是补齐“metadata 已声明但 `godot_builtin.h/.c` 当前不生成”的
  constructor helper surface，而不是接管普通 constructor 选择逻辑。
- `bool` / `int` / `float` 的默认、同型与 atomic-to-atomic constructor helper 都属于这条
  metadata-backed runtime helper surface；`godot_new_int_with_int` 是其合法成员。
- Transform/Basis/Projection 的 flat-float constructor helper 是另一组显式 GDCC-owned
  helper shim，也统一归口到 `gdcc_builtin_ctor.h`，但它们不改变 `CBuiltinBuilder` 对普通
  metadata-backed constructor 与 helper shim 的分层。
- 不把 `int(int)` 改写成 cast、direct alias、ordinary assignment route 或新的
  `c_int_to_int` intrinsic；typed boundary widening 规则也不因此扩展到 backend constructor
  matcher。

新增固定 wrapper 时：

1. 维护目标版本对应的 fixed source list，当前是 `Godot451FixedBindings.FUNCTIONS`。
2. 在 `Godot451FixedBindings.appendDefinitions(...)` 中提供匹配 C body。
3. 更新 `godot_fixed_binding.h/.c`。
4. 用 focused tests 覆盖 symbol metadata 与 emitted C 行为。
5. 通过 `check-fixed` 或等价测试确认 handwritten helper/template 里的 `godot_*` 引用仍在
   provided set 中。

新增 module-local wrapper family 时：

1. 扩展 `ModuleLocalGodotBinding`，并保存完整 `GodotBindingSymbol` metadata。
2. 扩展 `engine_method_binds.h.ftl` 的 header-only renderer。
3. 在发射调用的同一 buffer 中显式登记，Java body generator 走
   `GodotBindingUsageBuffer.recordModuleLocalGodotBinding(...)`，模板走
   `GenerateRenderFacade.recordModuleLocalGodotBinding(...)`。
4. 确保登记发生在 `CBodyBuilder.callVoid(...)` / `callAssign(...)` 检查同名 `godot_*`
   调用之前或同时发生。

generated C scanner 只做测试验收。它不能发现 wrapper、不能补登记、不能修改 usage
session，也不能成为生成行为的事实源。

## 使用集收集合同

`GodotBindingUsageSession` 是 `CCodegen.generate()` 持有的模块级 session。它内部拥有：

- exact engine method collector
- engine constructor collector
- module-local binding collector

`GodotBindingUsageBuffer` 是函数级临时记录器。函数体、property init apply body 和模板
facade 各自使用独立 buffer；只有 render 成功后才 commit 到模块级 session，失败时丢弃。
这条边界保证：

- `generateFuncBody(clazz, func)` 的 public 入口保持近似纯函数，不依赖隐式共享状态。
- 失败的 body render 不会把半成品 helper 或 module-local wrapper 泄漏到后续 header。
- 使用集顺序由首次成功 render 的 hit order 决定，并通过 `LinkedHashMap` 稳定输出。

主要记录入口：

- `CBodyBuilder.recordUsedEngineMethodCall(...)`
- `CBodyBuilder.recordUsedEngineConstructor(...)`
- `CBodyBuilder.recordModuleLocalGodotBinding(...)`
- `CBodyBuilder.callVoid(...)` / `callAssign(...)` 的 `godot_*` provided/module-local 检查
- `GenerateRenderFacade.recordModuleLocalGodotBinding(...)`

`appendLine(...)` / `appendRaw(...)` 直接拼出的 `godot_*` 调用不能依赖 scanner 兜底。
新增路径必须显式落入以下二选一：

- 该 symbol 已由 interface/builtin/utility/fixed provided set 提供。
- 该 symbol 在 emit 点登记为 module-local wrapper。

## Symbol Identity 与 Lookup Metadata

`GodotBindingSymbol` 是 generated Godot wrapper 的结构性身份。C function name 冲突只有在
完整 ABI signature 相同的时候才合法；同名不同 ABI 必须 fail-fast。

lookup hash 是 lookup metadata，不是 generated symbol identity：

- builtin method / utility function / exact engine method 都可以有 lookup hash。
- hash 不得直接参与 wrapper C name、exact helper name 或 usage key。
- utility function 没有 compatibility fallback；hash 漂移应在 generator merge 或 runtime lookup
  环节 fail-fast。
- class method bind lookup 必须覆盖 primary hash 与 `hash_compatibility` 候选。

ABI model 必须保留 `GDExtensionUninitialized*Ptr` destination 语义。generator 和 helper
不能把 uninitialized destination 改写成 initialized pointer，也不能在 failure path destroy
未初始化 storage。

## Exact Engine Method-Bind Route

exact engine `CALL_METHOD` 不走 public `godot_<Owner>_<method>` wrapper。它统一生成
backend-owned helper：

- non-vararg：`gdcc_engine_call_<owner>_<method>_<symbolId>(...)`
- vararg：`gdcc_engine_callv_<owner>_<method>_<symbolId>(...)`
- static non-vararg：`gdcc_engine_call_static_<owner>_<method>_<symbolId>(...)`
- static vararg：`gdcc_engine_callv_static_<owner>_<method>_<symbolId>(...)`

method-bind accessor 名称为：

- instance：`gdcc_engine_method_bind_<owner>_<method>_<symbolId>()`
- static：`gdcc_engine_method_bind_static_<owner>_<method>_<symbolId>()`

`BackendMethodCallResolver.ResolvedMethodCall` 只负责 lookup 身份：

- dispatch mode
- owner / method / return / normalized parameter types
- `isStatic`
- `isVararg`
- `EngineMethodBindSpec`

`EngineMethodBindSpec` 只服务 method bind lookup，包含 primary hash 与
`hashCompatibility` 候选。generated symbol 身份由 `EngineMethodSymbolKey` 决定，最小字段为：

- `ownerClassName`
- `methodName`
- `isStatic`
- `EngineMethodAbiSignature`

`EngineMethodAbiCodec` / `EngineMethodAbiSignature` 是 ABI descriptor 的唯一事实源。
descriptor 语法固定为：

- `P<paramDescriptors>_R<returnDescriptor>[_Xv]`
- 无 `_Xv` 表示 non-vararg
- 有 `_Xv` 表示 vararg

类型编码由 codec 集中维护。`Array[T]` 使用 `A<elementDescriptor>_`，
`Dictionary[K, V]` 使用 `D<keyDescriptor><valueDescriptor>_`，`Object` 使用
`L<length><ClassName>_`。不允许用摘要算法、`hashCode()` 或默认 `toString()` 充当长期
symbol identity。

### Non-Vararg Helper

- call site 继续通过普通 helper 调用模型接入。
- caller 保持 normalized callable surface；helper 才负责 `ptrcall` slot shaping。
- object 参数以内部 fat pointer（`gdcc_<Type>_fat_ptr`，by value）进入 helper；helper 体内物化为 raw slot
  （`<Type>_fat_ptr_live_object` → 本地 `GDExtensionObjectPtr`），再把 `&raw_slot` 提交给 `ptrcall`。
  不得把 fat struct 的地址当作 Godot ptrcall object slot。
- enum / bitfield 在 helper 内物化为本地 raw `godot_int` slot，再提交 `&local_slot`。
- static helper 不接收 receiver，bind 调用固定传 `NULL`。
- 缺失非零 bind hash 的 exact route 显式失败，不回退 public wrapper。

### Vararg Helper

- helper 只拥有 fixed prefix packed `Variant` 与本地 raw return `Variant` storage。
- caller 继续拥有 extra `const godot_Variant **argv`、`godot_int argc`、default temp 和
  call-site temp。
- helper 只 pack fixed prefix，不重新 pack caller-owned vararg tail。
- `_error.error` 检查必须先于 typed unpack。
- helper-owned fixed prefix temps 在 success / error 两条路径都必须清理。
- `godot_object_method_bind_call(...)` 的 return slot 是
  `GDExtensionUninitializedVariantPtr`，helper 传入 raw `godot_Variant` storage。
- 只有 `_error.error == GDEXTENSION_CALL_OK` 后才允许 unpack 和 destroy 本地 return
  `Variant`；error path 不得 destroy 未构造 return storage。
- `void` helper 不允许把 `NULL r_ret` 传给 `godot_object_method_bind_call(...)`。

## Engine Constructor Wrapper

engine object constructor 由 `ConstructInsnGen` 显式登记为 `EngineConstructorUsage`。
`engine_method_binds.h.ftl` 按使用集输出：

- `static inline godot_<Class> *godot_new_<Class>(void)`
- 内部调用 `godot_classdb_construct_object(GD_STATIC_SN(u8"<Class>"))`
- 返回 `NULL` 时调用 `gdcc_binding_lookup_fail(...)`，并带上
  `engine_constructor` lookup context

constructor wrapper 不属于 fixed runtime wrapper，也不属于 `ModuleLocalGodotBinding`。
它是模块级 header-only wrapper，与 exact engine helper 一样只在当前模块使用时输出。

## Module-Local Wrapper

module-local wrapper 只输出 `providedByRuntime = false` 的 symbol。当前两个 family 的行为为：

- singleton getter：
  - `static inline godot_<returnTypeName> * godot_<lookupName>_singleton(void)`
  - 使用 `godot_global_get_singleton(GD_STATIC_SN(u8"<lookupName>"))`
  - `lookupName` 来自 `ExtensionSingleton.name()` / `LoadStaticInsn.staticName()`，只用于 Godot singleton registry
    lookup、cache/C function identity 和 lookup diagnostic
  - `returnTypeName` 来自已验证的 `ExtensionSingleton.type()`，用于 C return type、cast type、cache pointer type
    和 diagnostic `context.type`
  - symbol owner 固定为 `"@GlobalScope"`，symbol name 固定为 `lookupName`
  - 只缓存非空结果；`NULL` 走 `gdcc_binding_lookup_fail(...)`
- class constant：
  - `static inline godot_int godot_<Class>_<CONSTANT>(void)`
  - 返回当前 metadata 中的 constant value

module-local key 由 family、owner、name、C function name 和 signature 组成。同一 canonical
binding 的 metadata 必须兼容；当前 singleton `returnTypeName`、class constant value 漂移都会
fail-fast。同一 C function name 对应不同结构性 signature 或 ABI 也必须 fail-fast。

`Engine`、`ClassDB` 等 fixed singleton wrappers 属于 runtime-provided symbol set。发射
`godot_Engine_singleton()` 这类调用的路径仍可以显式记录对应 module-local binding，但 usage session
会在提交前过滤 provided C function name，避免 `engine_method_binds.h` 再输出同名 `static inline`
wrapper。`GameSingleton -> Node` 这类 runtime 未提供的 singleton 才进入 module-local snapshot，并生成
按 `GameSingleton` lookup、按 `Node` 返回的 wrapper。

## 动态路径与固定 Helper 边界

动态 method/property/index fallback 不生成自由漂移的 wrapper：

- `OBJECT_DYNAMIC` / `VARIANT_DYNAMIC` 的 `godot_Object_call` / `godot_Variant_call` 来自
  fixed/runtime helper surface。
- unknown object property fallback 的 `godot_Object_get` / `godot_Object_set` 来自 fixed
  source list。
- `IndexLoadInsnGen` / `IndexStoreInsnGen` 使用的 `godot_variant_get*` /
  `godot_variant_set*` 来自 interface wrapper。
- builtin property/method/operator/index/key wrapper 来自全量 builtin provided set。
- utility call wrapper 来自全量 utility provided set。
- pack/unpack/default Variant wrapper 来自全量 builtin provided set。

因此动态路径的新增 `godot_*` 调用同样必须先落入 provided set；只有具体 singleton/class
constant 这类模块变化 symbol 才进入 module-local。

## 构建输入与旧残留规则

`CProjectBuilder` 的 native input 规则是显式的：

- 本轮 `CCodegen.generate()` 返回的 `.c` files
- `<includeRoot>/godot/godot_binding.c`
- `<includeRoot>/gdcc/minicoro.c`
- `<includeRoot>/gdcc/gdcc_coroutine.c`

不允许根据旧文件存在性把 `<includeRoot>/gdextension-lite/gdextension-lite-one.c` 加回
`cFiles`。`ResourceExtractor` 的覆盖/新增语义不承担旧目录清理；旧工作区残留只作为构建
回归样本，不是当前 runtime 依赖。

## 回归测试基线

- `CCodegenEngineMethodUsageSessionTest`
  - module session / function buffer / success-only commit
  - failed body render 不污染使用集
- `CCodegenEngineMethodBindHeaderTest`
  - generated file 集合为 `entry.c`、`engine_method_binds.h`、`entry.h`
  - exact engine helper/accessor 命名
  - non-vararg `ptrcall` slot shaping
  - enum / bitfield local slot materialization
  - vararg helper fixed prefix packing、cleanup 与 error path
  - module-local wrapper section 只写入 `engine_method_binds.h`
- `CCodegenSignalRegistrationTest` / `CGenHelperTest`
  - `_class_bind_methods` 只注册当前类 `classDef.signals`
  - 无参 signal 传 `NULL, 0`；有参 signal 复用 `gdcc_make_property_full` /
    `gdcc_destruct_property`，usage 为 method-arg `godot_PROPERTY_USAGE_DEFAULT`
  - Object signal 参数 `class_name` 保持空默认
- `CallMethodInsnGenTest` / `CallMethodInsnGenEngineTest`
  - caller-side normalized helper surface
  - exact engine route 不回退 public wrapper
  - static engine method warning + receiver-free helper
- `EngineMethodSymbolKeyTest` / `EngineMethodAbiCodecTest`
  - symbol identity 不随 lookup hash 漂移
  - ABI descriptor 可逆且稳定
- `GenerateRenderFacadeTest`
  - 模板级 module-local 使用集登记
- `GodotBindingUsageSessionTest`
  - provided set、module-local explicit registration、C name 冲突和 metadata drift fail-fast
- `ModuleLocalGodotBindingTemplateTest`
  - singleton / class constant header-only renderer
- `FixedGodotBindingsTest` / `GodotBindingToolAbiSupportTest`
  - fixed source list、ABI support 和 generator contract
- `CProjectBuilderSharedIncludeTest` / `ApiCompilePipelineTest`
  - 编译输入包含 `godot_binding.c`
  - stale legacy vendor runtime source 不进入 `cFiles` 或 include dirs
- `CProjectBuilderCoroutineRuntimeInputTest`
  - `gdcc/minicoro.c` 与 `gdcc/gdcc_coroutine.c` 被提取并固定顺序进入 `cFiles`
- `GdccCoroutineRuntimeSmokeTest`（zig-gated）
  - 纯 C 层锚定 minicoro 往返、finalize 不变量、cancel 级联放弃、identify 拒绝与
    dynamic 分派的非 engine 分支

## 风险与维护提醒

- 不要把 `godot_binding.h/.c` 扩展成模块级 wrapper 聚合入口；它只代表静态 runtime
  support。
- 不要让 scanner 变成 wrapper discovery 机制；缺少显式 provided/module-local 归属应 fail-fast。
- 不要把 method lookup hash 放进 exact helper/accessor 名称；hash 只属于 lookup metadata。
- 不要在 `CallMethodInsnGen` 或模板里复制 helper 参数推导、slot mode 或 ABI descriptor 逻辑；
  共享逻辑由 `CGenHelper`、`EngineMethodAbiCodec` 和相关 usage key 类型发布。
- 不要在失败路径 destroy `GDExtensionUninitialized*Ptr` raw storage。
- 不要放宽三文件 generated-file 公共契约；module-local wrapper 非空也不能新增 generated `.c`。
- 不要把旧 vendor 残留当作当前编译依赖；测试中的 legacy path 只用于证明 stale 文件不会被编译。

## 非目标

- 不在本文档中定义 frontend exact-call metadata 发布规则。
- 不在本文档中记录 `CBuiltinBuilder` 的 constructor 选择和 literal materialization 细节。
- 不把 `CALL_METHOD` 的所有分派、overload 和动态诊断规则搬到本文档。
- 不保留迁移阶段时间线、任务勾选、测试执行日志或完成定义流水账。
