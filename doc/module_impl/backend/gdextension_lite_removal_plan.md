# 移除 gdextension-lite 后端依赖实施计划

> 本文是 C backend 从捆绑 `gdextension-lite` 迁移到 GDCC 自有 GDExtension binding support
> 的实施计划。目标是删除巨大 vendor binding 包，同时保留当前生成代码已经稳定使用的
> `godot_` 命名标准和 backend-owned `gdcc_*` helper 边界。

## 文档状态

- 状态：阶段 5 已完成
- 范围（整份计划的真实触点，具体阶段仍按后文分批提交）：
  - `src/main/java/gd/script/gdcc/gdextension/**`
  - `src/main/java/gd/script/gdcc/scope/**`
  - `src/main/java/gd/script/gdcc/backend/c/**`
  - `src/main/java/gd/script/gdcc/util/ResourceExtractor.java` 的非删除语义边界
  - `src/main/c/codegen/include_451/**`
  - `src/main/c/codegen/template_451/**`
  - `src/test/java/gd/script/gdcc/gdextension/**`
  - `src/test/java/gd/script/gdcc/scope/**`
  - `src/test/java/gd/script/gdcc/frontend/**`
  - `src/test/java/gd/script/gdcc/backend/c/**`
  - `src/test/java/gd/script/gdcc/api/**`
  - `src/test/java/gd/script/gdcc/cli/**`
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/**`
  - 被触及源码注释、test display name、failure message 中的旧 `gdextension-lite` 事实表述
- 阶段边界：
  - 阶段 0 是 metadata/property 语义重构，会触及 `ExtensionAPI` / `ExtensionApiLoader` /
    `ExtensionGdClass.PropertyInfo`、shared property resolver 和相关 frontend/scope/backend tests；不能当成无害数据垫片提交。
  - 阶段 3 只切换 vendor include / runtime binding / native 编译输入；`generatedFiles()` 与 `outputLinks()`
    的核心 3 文件公共契约仍保持不变。
  - 阶段 3 不能假定 `shared-include` 或项目 `include` 是干净目录；旧 `gdextension-lite` 子树即使残留，
    也必须被 `CProjectBuilder` 的显式输入规则排除。
  - 阶段 4 接入 module-local wrapper 使用集时，仍保持模块级 C 代码只由 `entry.c` 一个 translation unit 承载；
    不新增 generated `.c`、API / CLI generated file、output link 或 `/generated` 目录列表公共契约。
- 目标 Godot ABI：`GodotVersion.V451` / Godot 4.5.1
- 目标 Godot ABI 配置：仅支持 `float_64`，也就是 64-bit pointer + single-precision `real_t`。
  `REAL_T_IS_DOUBLE` 和 32-bit Godot build configuration 不在 GDCC C backend 支持范围内，生成的 ABI support
  header 必须在这些配置下 fail-fast。
- 重点参考：
  - `doc/module_impl/backend/engine_method_bind_implementation.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/module_impl/backend/backend_ownership_lifecycle_contract.md`
  - `doc/module_impl/backend/variant_abi_contract.md`
  - `doc/module_impl/backend/typed_array_abi_contract.md`
  - `doc/module_impl/backend/typed_dictionary_abi_contract.md`
- 外部参考：
  - `godotengine/godot`: `core/extension/gdextension_interface_header_generator.cpp`
    - Godot 自身通过导出器生成 `gdextension_interface.h`；该 header 是 binder 可直接消费的 C ABI 基线。
    - header 注释明确区分 `GDExtension*Ptr`、`GDExtensionConst*Ptr` 和
      `GDExtensionUninitialized*Ptr`；后者要求由 constructor / placement 初始化，不能零初始化替代。
  - `godotengine/godot`: `core/extension/gdextension_interface.cpp`
    - Godot 在运行时注册 proc-address 表；GDCC 不解析该 C++ 源码，只用它做抽样核对。
      `godot_interface.h/.c` 的长期事实源应是 Godot 导出的 `gdextension_interface.h`。
    - builtin method、utility function 和 class method bind lookup 在 hash 不匹配或兼容回退失败时返回 `nullptr`；
      GDCC generated wrapper 必须把该结果转换为 fail-fast 诊断，不能缓存或调用空函数指针。
  - `godotengine/godot`: `core/extension/extension_api_dump.cpp`
    - Godot 的 extension API dump 负责输出 builtin size、global enum、global constants、builtin constructor/method/operator、
      class method/property/constant、singleton、native structure 等 metadata；非 interface wrapper 生成必须从这些 metadata 对齐。
    - `hash` / `hash_compatibility` 是 lookup 和兼容性验证输入，不是 wrapper 身份。
  - `godotengine/godot`: `core/variant/variant_call.cpp`、`core/variant/variant_utility.cpp`、`core/object/class_db.cpp`
    - 分别定义 builtin method、utility function、class method bind 的 hash lookup / compatibility 行为和失败边界。
  - `gilzoide/gdextension-lite`: `gdextension-lite/gdextension-lite.c`
    - `gdextension_lite_initialize(...)` 本质上只委托初始化 interface wrapper 表。
  - `gilzoide/gdextension-lite`: `binding_generator/extension_interface.py`
    - gdextension-lite 直接解析 `gdextension_interface.h` 中的 `@name` / `@since` 注释和紧随其后的函数指针 typedef，
      生成全部 interface wrapper；它不解析 Godot C++ 注册源码。
  - `gilzoide/gdextension-lite`: `binding_generator/misc/extension_interface_function.py`
    - wrapper 名和 lookup key 来自 `@name`，函数指针 typedef 名只作为 C 调用类型保留。
  - `gilzoide/gdextension-lite`: `README.md`
    - gdextension-lite 的使用模型是包含 `gdextension-lite.h` 并编译 `gdextension-lite-one.c`，与当前 GDCC 捆绑方式一致。

## 目标

1. 不再在编译项目中解压、包含或编译完整 `gdextension-lite.zip`。
2. `entry.c` 不再调用 `gdextension_lite_initialize(...)`。
3. 编译输入不再包含 `gdextension-lite/gdextension-lite-one.c`。
4. 总是根据 Godot 导出的 `gdextension_interface.h` 全量生成并发布所有 interface function wrapper。
5. 由 GDCC 自有文件提供当前依赖 `gdextension-lite` 获得的宏、enum、全局常量、native struct、builtin 类型声明、
   builtin ABI 尺寸定义和 builtin member offset/meta layout 定义；`global_enums[]` 与 `global_constants[]`
   都必须从目标 Godot metadata 全量预生成，不按 helper 或脚本使用情况裁剪。
6. builtin callable wrapper 和 utility function wrapper 按 Godot 版本全量预生成，不再依赖模块使用集或源码扫描决定是否输出。
7. 固化 GDCC 自有 builtin wrapper 生成 contract：迁移完成后，`doc/gdcc_runtime_lib.md` 作为当前 runtime
   library 与 `godot_` wrapper 命名事实源；具体生成行为仍以版本化 generator 和测试锚点为准。
8. 固定 `gdcc/*.h` helper 和固定模板所需的非 interface wrapper 由版本化源码清单 + 命令行工具生成，并作为模块级生成的 provided set 输入。
9. 保留现有 `godot_` 命名规则，避免让 Java 后端、模板和测试在同一轮迁移中大规模改名。
10. 保留已经迁移完成的 exact engine `CALL_METHOD` 合同：继续走 `engine_method_binds.h` 中的 `gdcc_engine_call*` helper，不回退 `godot_<Owner>_<method>` public wrapper。

## 非目标

- 不改 `.gdextension` 文件格式、entry symbol 或 platform library key。
- 不改 LIR、frontend lowering、类型系统或 ownership 语义。
- 不引入 `godot-cpp`、新的第三方 binding 库或运行时动态代码生成。
- 不把 engine class method/property、singleton、class constant wrapper 全量预生成进项目；这些面仍走 exact route、版本化固定清单或模块级按需生成。
- 不用兼容 shim 继续保留 `gdextension_lite_*` public API。
- 不继续暴露 `GDEXTENSION_LITE_*` 宏名作为 GDCC public contract；可复用其实现思路，但新宏应归入 GDCC/Godot 自有命名。

## 现状依赖面

当前 `CProjectBuilder` 会把 `include_451` 解压到项目或 shared include 目录。由于其中包含
`gdextension-lite.zip`，最终会生成 `<includeRoot>/gdextension-lite/**`，并把
`<includeRoot>/gdextension-lite/gdextension-lite-one.c` 加入 native 编译输入。

这里还有一个迁移风险：`ResourceExtractor.extract(...)` 的既有 contract 是覆盖或新增资源，但不删除目标目录里
已经存在的旧文件。`CProjectBuilder.resolveIncludeRoot(...)` 又会优先复用父目录的 `shared-include`。因此即使后续
资源包停止发布 `gdextension-lite.zip`，旧工作区中已经解压过的
`<includeRoot>/gdextension-lite/gdextension-lite-one.c` 仍可能留在磁盘上；当前 `CProjectBuilder` 的
`Files.exists(...)` 存在即加入逻辑会把这个 stale vendor C 文件重新送进 native 编译。

`gdextension-lite-one.c` 实际包含这些类别：

- `gdextension-lite.c`
- `variant/all.c`
- `generated/class-methods/all.c`
- `generated/class-stubs/all.c`
- `generated/extension_interface.c`
- `generated/utility_functions.c`
- `generated/variant/all.c`

这说明当前成本不是单个头文件，而是完整 Godot C binding 树。当前 `gdcc/*.h` 也普遍包含
`<gdextension-lite.h>`，并通过它获得 `godot_String`、`godot_Variant`、`godot_mem_alloc`、
`godot_classdb_*`、`godot_variant_*`、builtin constructor/destructor、utility function、
class method wrapper 和 singleton getter 等符号。

`gdextension-lite.h` 还聚合了以下非函数依赖面，迁移时必须逐项替代：

- `definition-macros.h`：`GDEXTENSION_LITE_DECL`、inline、opaque struct、cleanup 等声明辅助宏。
- `implementation-macros.h`：variant constructor/destructor、member/index/key access、operator、utility、class method 和 interface wrapper 的宏模板；当前 `entry.c.ftl` 直接包含它。
- `generated/global_enums.h`：`godot_PropertyHint`、`godot_PropertyUsageFlags`、`godot_MethodFlags`、`godot_Error`、`godot_Variant_Type`、`godot_Variant_Operator` 等 Godot 全局 enum/常量。
- `generated/native_structures.h`：`godot_AudioFrame`、`godot_Glyph`、`godot_ObjectID`、`godot_PhysicsServer*Extension*`、`godot_ScriptLanguageExtensionProfilingInfo` 等 native struct。
- `variant/all.h` 与 `generated/variant/all.h`：builtin struct typedef、`godot_Variant`、`godot_Object`、builtin constructor/destructor、Variant pack/unpack wrapper。
- `generated/variant/sizes.h`：`GDEXTENSION_LITE_SIZE_*` builtin ABI 尺寸表，按 pointer width 与 `REAL_T_IS_DOUBLE` 分支。

当前模板和 Java 生成器至少直接依赖这些常量族：

- `GDEXTENSION_VARIANT_TYPE_*`
- `GDEXTENSION_VARIANT_OP_*`
- `GDEXTENSION_CALL_ERROR_*`
- `GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE`
- `GDEXTENSION_METHOD_FLAGS_DEFAULT`
- `GDEXTENSION_METHOD_FLAG_STATIC`
- `GDEXTENSION_INITIALIZATION_SCENE`
- `godot_PROPERTY_HINT_NONE`
- `godot_PROPERTY_HINT_ARRAY_TYPE`
- `godot_PROPERTY_HINT_DICTIONARY_TYPE`
- `godot_PROPERTY_USAGE_DEFAULT`
- `godot_PROPERTY_USAGE_NO_EDITOR`
- `godot_PROPERTY_USAGE_NIL_IS_VARIANT`

## 替代层设计

迁移后保留两类 include 根：

- `<includeRoot>/gdcc`：现有 GDCC runtime helper，继续放项目自有 helper。
- `<includeRoot>/godot`：新增 GDCC 自有 Godot binding support，替代 `<includeRoot>/gdextension-lite`。

新增文件建议如下：

- `src/main/c/codegen/include_451/godot/gdextension/gdextension_interface.h`
  - 由 Godot 4.5.1 引擎导出，保持纯上游 ABI header。
  - 不手改函数指针 typedef、struct、enum。
- `src/main/c/codegen/include_451/godot/godot_macros.h`
  - 提供 `GDE_EXPORT`、GDCC/Godot binding 内部声明可见性、inline、opaque typedef、size assert 等基础宏。
  - 不复用 `GDEXTENSION_LITE_*` 作为 public contract；需要兼容旧实现思路时在这里用新命名重建。
- `src/main/c/codegen/include_451/godot/godot_abi.h`
  - 包含 `gdextension/gdextension_interface.h`。
  - 聚合 `godot_macros.h`、`godot_global_enums.h`、`godot_global_constants.h`、`godot_native_structures.h`、
    `godot_builtin_sizes.h`、`godot_builtin_layout.h`、`godot_builtin_types.h`。
  - 定义 `godot_bool`、`godot_int`、`godot_float`、opaque object typedef、builtin struct typedef。
  - 手写 builtin include 可以拆到 `src/main/c/codegen/include_451/godot/builtin/*.h`，但统一由 `godot_abi.h` 对外聚合。
- `src/main/c/codegen/include_451/godot/godot_global_enums.h`
  - 由 Godot 4.5.1 `extension_api.json` 或等价 metadata 生成。
  - 提供 `generated/global_enums.h` 当前暴露的 `godot_*` enum value 名，尤其是 `godot_PROPERTY_HINT_*`、`godot_PROPERTY_USAGE_*`、`godot_METHOD_FLAG_*`、`godot_OK`/`godot_Error`、global input/layout enum。
  - `GDEXTENSION_*` ABI enum 继续来自 `gdextension_interface.h`，不要重复定义。
- `src/main/c/codegen/include_451/godot/godot_global_constants.h`
  - 由 Godot 4.5.1 `extension_api.json` 顶层 `global_constants[]` 生成。
  - `global_constants[]` 即使在当前 4.5.1 metadata 中为空，也必须进入 Java 模型、生成器 manifest 和静态 header 输出；
    输出可以是只有 include guard 的确定性空 header，但不能把该字段留给“后续需要时再解析”。
  - 若目标 Godot 版本导出非空 global constants，全部生成到该 header，命名沿用 GDCC/Godot binding 的
    `godot_*` 常量命名规则，并和 `godot_global_enums.h` 做同名冲突 fail-fast。
  - 手写 `gdcc/*.h` helper、模板和后续脚本路径只能消费该 header 或 Java 模型中的全局常量，不允许继续 include
    `generated/global_enums.h` 或绕过 `ExtensionAPI` 重新读 JSON。
- `src/main/c/codegen/include_451/godot/godot_native_structures.h`
  - 由 Godot 4.5.1 metadata 生成 native structure 声明。
  - 覆盖当前 `generated/native_structures.h` 中的 audio、physics、glyph、object id、script profiling 等结构体。
- `src/main/c/codegen/include_451/godot/godot_builtin_sizes.h`
  - 替代 `generated/variant/sizes.h`。
  - 提供 `GDCC_GODOT_SIZE_*` 或等价自有命名的 builtin ABI 尺寸表，只生成 `float_64` 配置。
  - 32-bit pointer 或 `REAL_T_IS_DOUBLE` 配置不是支持目标；该 header 必须用预处理错误直接拒绝。
  - builtin struct 声明处必须继续做 size assert，避免手写 builtin include 和 Godot ABI 漂移。
- `src/main/c/codegen/include_451/godot/godot_builtin_layout.h`
  - 从 `builtin_class_member_offsets` 生成 builtin member layout 常量或 private assert 输入。
  - 只输出 `float_64` build configuration 的 `GDCC_GODOT_OFFSET_*` / `GDCC_GODOT_META_*`。
  - 每个成员必须保留 `member/offset/meta` 原始语义；`meta` 不是注释，它决定字段访问时应按 `float`、`double`、
    `int32`、`Vector2`、`Vector3` 等 ABI 形态解释内存。
  - 任何直接字段访问、未来 builtin scalar replacement、字段级优化或手写 builtin struct 声明都必须以该 layout
    metadata 为验收事实源，不能只靠整体 `sizeof` 通过。
- `src/main/c/codegen/include_451/godot/godot_builtin_types.h`
  - 替代 `variant/all.h` 中的手写 builtin struct typedef 聚合。
  - 覆盖 `godot_Variant`、`godot_String`、`godot_StringName`、`godot_Array`、`godot_Dictionary`、`godot_Object`、所有 vector/rect/transform/packed array 等 builtin 类型。
- `src/main/c/codegen/include_451/godot/godot_interface.h`
  - 声明 `GDExtensionBool godot_initialize_interface(GDExtensionInterfaceGetProcAddress get_proc_address)`。
  - 声明统一的 lookup failure helper，例如带 `gdcc_binding_lookup_context` 的
    `gdcc_binding_lookup_fail(...)`，供 interface、builtin、utility、fixed 和 module-local generated wrapper
    在 Godot lookup 返回空指针时复用。
  - 声明从 `gdextension_interface.h` 的 interface 注释/typedef 配对解析出的全部 GDExtension interface function wrapper，
    不按当前模块使用集裁剪。
  - wrapper 名继续使用 `godot_<interface_name>`，例如 `godot_mem_alloc(...)`、`godot_variant_new_nil(...)`、`godot_classdb_get_method_bind(...)`。
- `src/main/c/codegen/include_451/godot/godot_interface.c`
  - 保存 `get_proc_address`，并实现 `godot_initialize_interface(...)`。
  - 为 header 中每个可解析 interface function 生成 wrapper 实现；`godot_initialize_interface(...)`
    在入口初始化期一次性解析全部 Godot function pointer，运行期 wrapper 只调用已解析指针。
  - lookup failure helper 先尽量通过已解析的 `print_error` interface 输出 wrapper kind、lookup name、owner/type 和 hash 候选，
    `print_error` 不可用时退回 `stderr`；随后返回 `false`，由调用者拒绝注册、走 cleanup 或忽略返回值。它永远不能
    `abort()` / trap 整个宿主进程，也不能依赖正在失败的 wrapper。
  - 该层固定全量生成，因为 interface wrapper 是集中、轻量、稳定的 ABI lookup 层，能降低后续 backend 开发时遗漏 interface function 的成本。
- `src/main/c/codegen/include_451/godot/godot_builtin.h`
  - 声明按 Godot 版本全量预生成的 GDCC builtin wrapper contract：经过过滤的 raw metadata wrapper 加上兼容旧 ABI 的合成 helper。
  - 不按模块使用集裁剪；生成输入来自 `ExtensionAPI` builtin metadata 和少量版本化 helper override。
- `src/main/c/codegen/include_451/godot/godot_builtin.c`
  - 实现全量 GDCC builtin wrapper contract，而不是简单把 `builtin_classes[]` 每个 JSON 项逐一映射成 C 函数。
  - 构造器通过 `godot_variant_get_ptr_constructor(...)` 或 specialized interface function 实现。
  - 析构通过 `godot_variant_get_ptr_destructor(...)` 实现。
  - `Variant <-> Type` pack/unpack 通过 `godot_get_variant_from_type_constructor(...)` / `godot_get_variant_to_type_constructor(...)` 实现。
- `src/main/c/codegen/include_451/godot/godot_utility.h`
  - 声明按 Godot 版本全量预生成的 utility function wrapper，例如 `godot_print`、`godot_deg_to_rad`。
- `src/main/c/codegen/include_451/godot/godot_utility.c`
  - 使用 `godot_variant_get_ptr_utility_function(...)` 和 lookup hash 实现全量 utility function wrapper。
- engine class method/property/singleton/constant wrapper 不放入静态 `include_451/godot` 全量预生成文件。
  - 固定 helper/template 必需的 engine wrapper 进入 `godot_fixed_binding.h/.c`。
  - 脚本实际使用且未被 fixed 覆盖的残余 engine wrapper 进入 `engine_method_binds.h` 的模块级
    `static inline` section。
  - exact engine `CALL_METHOD` 不回退到这些 public wrapper；它继续只走 `engine_method_binds.h` 中的 backend-owned helper。
- `src/main/c/codegen/include_451/godot/godot_binding.h`
  - 可选聚合头，替代 `<gdextension-lite.h>` 的 include 体验。
  - 对外聚合 `godot_abi.h`、`godot_interface.h`、`godot_builtin.h`、`godot_utility.h`、`godot_fixed_binding.h`，不拉入完整 vendor 树。
  - 不包含任何模块级 generated binding header；它属于静态 runtime support，不依赖具体编译模块。
- `src/main/c/codegen/include_451/godot/godot_binding.c`
  - 可选聚合源，只 `#include` 静态 runtime support 的 `.c`，让 `CProjectBuilder` 继续只追加一个 runtime binding C 文件。
  - 不聚合模块级 generated binding `.c`；模块级 `.c` 是 `CCodegen.generate()` 返回的普通 generated C 输入。
- `src/main/c/codegen/include_451/godot/godot_fixed_binding.h`
  - 由命令行工具根据版本化固定 wrapper 源码清单生成。
  - 声明固定 helper 必需且不属于 interface / builtin 全量 / utility 全量的具体 wrapper，
    例如清单中的 `godot_Engine_singleton`、`godot_Object_get`、`godot_Object_notification`。
- `src/main/c/codegen/include_451/godot/godot_fixed_binding.c`
  - 实现 `godot_fixed_binding.h` 声明的固定 wrapper。
  - 由 `godot_binding.c` 聚合编译，作为 runtime support 的一部分，避免每个模块重复生成这些 helper 依赖。
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/FixedGodotBindings`
  - 固定 helper wrapper 的版本化抽象入口，负责生成骨架、manifest symbol 投影和版本门禁。
  - 当前 Godot 4.5.1 的具体数据集由 `Godot451FixedBindings` 提供；后续版本只应新增数据子类，不应把 4.5.1
    类名扩散为长期调用入口。
  - 只列出当前 runtime/helper/template 需要、且不由 interface / builtin / utility 全量输出覆盖的 wrapper，
    并作为后续模块级使用集的 provided set 输入。

模块级 generated binding 不放在 `include_451/godot` 下，也不生成新的模块级 `.c` 文件。阶段 4 的
module-local wrapper 作为 `engine_method_binds.h` 中的 `static inline` section 输出，并由现有
`entry.h` include 链进入唯一的模块级 `entry.c` translation unit：

- `engine_method_binds.h`
  - 保持 exact engine method helper header 的现有职责，并新增 module-local Godot wrapper section。
  - 只在 module-local usage snapshot 非空时输出该 section；snapshot 为空时仍只包含 constructor / exact method helper 区块。
  - module-local wrapper 只能使用 header-visible 依赖：`entry.h` 先 include `<godot_binding.h>`、
    `<gdcc_helper.h>`，再 include `"engine_method_binds.h"`，因此 wrapper 可以复用 `GD_STATIC_SN(...)`、
    `gdcc_binding_lookup_fail(...)` 和 entry translation unit 的 `class_library`。
  - wrapper 必须是 `static inline`，避免新增动态导出符号和跨 translation unit 链接表面。
- `CCodegen.generate()`
  - 继续只返回 `entry.c`、`engine_method_binds.h`、`entry.h` 三个核心 generated files。
  - 不生成 `godot_module_bindings.h`、`godot_module_bindings.c` 或任何其它 module-local `.c`。
- `CProjectBuilder`
  - native compiler input 继续是本轮 generated `.c` 中的 `entry.c` 加固定 runtime
    `<includeRoot>/godot/godot_binding.c`。
  - 不因为 module-local wrapper snapshot 非空而新增 projectPath 下的 `.c` 输入。

## GDExtension interface wrapper 生成 contract

interface wrapper 的长期事实源是 Godot 导出的 `gdextension_interface.h`。这与 `gilzoide/gdextension-lite` 的做法一致：
解析 header 中的 `@name` / `@since` 注释和紧随其后的函数指针 typedef，直接生成 `godot_<name>` wrapper。
不要解析 Godot C++ 源码里的 `REGISTER_INTERFACE_FUNC(...)`；那条路径对上游实现细节过敏，Godot 版本升级时维护成本高。

interface generator 必须把 header 事实拆成两个字段，而不是从 typedef 名推导一切：

- lookup name / wrapper name：来自注释中的 `@name`，例如 `@name variant_new_nil` 生成 `godot_variant_new_nil(...)`，
  初始化期调用 `get_proc_address("variant_new_nil")` 解析并保存对应函数指针。
- typedef / ABI 签名：来自同一注释块后紧随的 `typedef ... (*TypedefName)(...)`。typedef 名原样保留为 C 函数指针类型，
  不参与 wrapper 名规范化。
- since：来自 `@since`，只写入生成注释或 manifest，不能影响 symbol 身份。

这种 contract 可以自然处理 Godot header 的历史包袱：

- `GDExtensionInterfaceGetProcAddress` 位于 interface 注释块之前，是 Godot 传入 `gdextension_entry(...)` 的入口回调 typedef；
  它没有对应 `@name`，不生成 `godot_get_proc_address`。
- Godot 4.5.1 header 中 `editor_help_load_xml_from_utf8_chars` 与
  `editor_help_load_xml_from_utf8_chars_and_len` 的 typedef 前缀写成 `GDExtensionsInterface...`。因为 wrapper 名来自 `@name`，
  typedef 拼写异常不会影响 lookup key。
- Godot 4.5.1 header 中存在 `editor_register_get_classes_used_callback` 与 `register_main_loop_callbacks` typedef；
  只要它们有标准 `@name` + typedef 配对，就按 header 生成 wrapper。是否在具体 Godot runtime 中可 lookup
  由 `godot_initialize_interface(...)` 的 eager resolve 验证，不在生成器中解析 C++ 注册表。
- deprecated interface 只要仍出现在 header 中就生成 wrapper；`DISABLE_DEPRECATED` 这类 Godot 内部编译条件不进入 GDCC
  的 header-driven 生成规则。当前 eager resolve 策略要求目标 runtime 暴露这些 header entry；若后续需要支持
  no-deprecated Godot runtime，应作为单独目标 runtime 兼容性矩阵处理，不应引入 C++ 源码解析。

验收口径改为“header 可解析配对集合”：

- 每个 `@name` 必须和后续第一个 function pointer typedef 配对；遇到 `@name` 后没有 typedef 时 fail-fast。
- 每个生成 wrapper 的 name 必须来自 `@name`，不能从 typedef 名转换。
- header 中没有 `@name` 的 function typedef 不进入 wrapper 集合，并作为非 interface/bootstrap typedef 报告。
- 同一个 `@name` 重复出现、或同一个 wrapper name 对应不同 ABI 签名时 fail-fast。
- 生成器可以附带一个抽样对照测试：固定样本如 `godot_mem_alloc`、`godot_variant_new_nil`、
  `godot_editor_help_load_xml_from_utf8_chars`、`godot_editor_register_get_classes_used_callback`、
  `godot_register_main_loop_callbacks` 都必须从 header 解析得到。但这个测试不读取 Godot C++ 源码。

## 初始化流程

移除 `gdextension_lite_initialize(...)` 后，`entry.c.ftl` 仍在 `gdextension_entry(...)` 内初始化绑定，因为只有这个入口能拿到
`GDExtensionInterfaceGetProcAddress p_get_proc_address`。初始化顺序固定为：

1. `godot_initialize_interface(p_get_proc_address)` 使用 Godot 提供的 `get_proc_address`
   解析全部 interface wrapper function pointer。
2. 若 `p_get_proc_address` 为空或 interface 初始化失败，`gdextension_entry(...)` 返回 `false`，不继续注册 lifecycle callback。
3. `class_library = p_library`。
4. 设置 `r_initialization->minimum_initialization_level = GDEXTENSION_INITIALIZATION_SCENE`。
5. 设置 `r_initialization->userdata = NULL`、`initialize = &initialize`、`deinitialize = &deinitialize`。
6. 返回 `true`。

`initialize(...)` 与 `deinitialize(...)` 仍遵守 `doc/gdcc_c_backend.md` 的 scene-level guard：

- 非 `GDEXTENSION_INITIALIZATION_SCENE` level 直接返回。
- `gdcc_init()` 仍在 scene-level `initialize(...)` 中执行，此时 interface wrapper 已可用。
- 类注册、属性注册、`GD_STATIC_S` / `GD_STATIC_SN` registry、loading/unloading log 的行为不改变。

`godot_initialize_interface(...)` 只负责 interface lookup 层：

- 在 `godot_interface.c` 内部静态状态中保存当前 Godot core extension 生命周期内的 interface function pointer。
- 不注册类、不触发 `gdcc_init()`、不创建 module-level Godot object。
- 每次 entry 初始化都会先清空旧 pointer，再按 bundled Godot 4.5.1 header 中的 `@name` 集合重新解析。
- interface wrapper 不再做首次调用 lazy lookup；这避免并发首次调用时写入裸静态全局状态，
  并让高频 wrapper 的运行期 hot path 保持为一次已解析函数指针调用。
- eager resolve 要求目标 Godot 暴露 bundled header 中的 interface entry。自定义 `DISABLE_DEPRECATED`
  或裁剪 interface 表的 Godot 构建不在当前支持范围内；缺失 entry 按 lookup miss 报告并返回 `false`。

## 宏、枚举、常量与 ABI 声明

移除 `gdextension-lite` 时，不能只补函数 wrapper。当前代码还通过 `<gdextension-lite.h>` 间接拿到多类声明：

- `GDE_EXPORT` 当前在 `gdcc_helper.h` 有 fallback；若入口模板、Godot binding 和 GDCC helper 都需要它，应移动或复制到 `godot_macros.h`，再由 `gdcc_helper.h` 聚合。
- `GDEXTENSION_VARIANT_TYPE_*`、`GDEXTENSION_VARIANT_OP_*`、`GDEXTENSION_CALL_ERROR_*`、`GDEXTENSION_METHOD_*`、`GDEXTENSION_INITIALIZATION_*` 来自 Godot 原始 `gdextension_interface.h`，新方案只保证 include 路径正确，不重命名。
- `godot_PROPERTY_HINT_*`、`godot_PROPERTY_USAGE_*`、`godot_METHOD_FLAG_*`、`godot_Error`、input/layout/global enum 来自 `godot_global_enums.h`，按 Godot 4.5.1 metadata 生成。
- 顶层 `global_constants[]` 来自 `godot_global_constants.h`，按目标 Godot metadata 全量生成。Godot 4.5.1 当前
  `extension_api_451.json` 中该数组为空，也必须生成稳定空 header、loader 字段和 generator snapshot，防止后续 Godot
  版本或手写 helper 需要全局常量时重新引入按需解析或旧 vendor 头。
- `godot_Object_NOTIFICATION_*`、engine class enum/int constant、`godot_Engine_singleton()`、`godot_ClassDB_singleton()` 属于 engine class wrapper 面；固定 helper 依赖由 `FixedGodotBindings` 当前版本数据提供，脚本路径中的残余 engine wrapper 再由 `GodotBindingUsageSession` 按需生成。
- `godot_String`、`godot_StringName`、`godot_Array`、`godot_Dictionary`、`godot_Variant` 等 builtin struct typedef 由 `godot_builtin_types.h` 提供。
- builtin ABI size 表由 `godot_builtin_sizes.h` 提供，命名不再使用 `GDEXTENSION_LITE_SIZE_*` 作为 public contract。
- builtin member layout 表由 `godot_builtin_layout.h` 或等价 generator snapshot 提供，至少包含
  build configuration、builtin class、member、offset、meta；命名不再使用旧 vendor 的 layout 宏作为 public contract。
- `godot_AudioFrame`、`godot_Glyph`、`godot_ObjectID`、physics extension result、profiling info 等 native struct 由 `godot_native_structures.h` 提供。
- `implementation-macros.h` 不再被模板或生成 C 文件 include；其中的 vararg mapping、constructor lookup、method bind lookup 等逻辑，要么由 Java generator 直接展开成 C 代码，要么收敛到 GDCC 自有的私有 C helper/macro。

验收时需要单独检查这些名字族，不允许因为函数 wrapper 编译通过就认为迁移完成。builtin ABI 验收也不能只看
`sizeof`：`Vector3` 在 double build 下 size 变为 24，但真正决定 `z` 字段访问是否正确的是 offset 从 8 变为 16
且 meta 从 `float` 变为 `double`；`Color` 则即使在 double build 下仍保持 `float` member meta。

## Uninitialized pointer ABI contract

Godot 4.5.1 `gdextension_interface.h` 明确把指针 ABI 分成 initialized、const 和 uninitialized destination：
`GDExtensionVariantPtr` / `GDExtensionConstVariantPtr` / `GDExtensionUninitializedVariantPtr`，
`GDExtensionStringPtr` / `GDExtensionConstStringPtr` / `GDExtensionUninitializedStringPtr`，
`GDExtensionStringNamePtr` / `GDExtensionConstStringNamePtr` / `GDExtensionUninitializedStringNamePtr`，
`GDExtensionObjectPtr` / `GDExtensionConstObjectPtr` / `GDExtensionUninitializedObjectPtr`，
以及 `GDExtensionTypePtr` / `GDExtensionConstTypePtr` / `GDExtensionUninitializedTypePtr`。
这些 typedef 不能在 GDCC 的 ABI model、interface generator、builtin generator 或 wrapper signature 中被折叠成普通
`void *` / `GDExtensionTypePtr`。

硬性规则：

- `GDExtensionUninitialized*Ptr` 表示调用方提供的原始存储，由 Godot constructor、conversion function 或返回值 out-param
  执行 placement 初始化。零初始化、普通赋值、把地址当成 initialized pointer 传入都不是等价替代。
- wrapper generator 必须在内部签名模型中保存参数的初始化状态：initialized mutable input、const input、uninitialized output
  是三种不同 ABI。该状态进入 wrapper 结构性签名和生成测试；不能只保存底层 C 类型都是 `void *` 这一事实。
- interface wrapper 必须原样保留 header typedef 签名，例如 `GDExtensionInterfaceVariantNewNil` 的
  `GDExtensionUninitializedVariantPtr`、`GDExtensionInterfaceStringNewWithUtf8Chars` 的
  `GDExtensionUninitializedStringPtr`、`GDExtensionInterfaceObjectMethodBindCall` 的
  `GDExtensionUninitializedVariantPtr`。
- builtin constructor、`Variant <-> Type` conversion、`String` / `StringName` convenience constructor、`Variant` nil/copy
  和后续 object/callable construction wrapper 必须把本地 raw storage 地址以 `GDExtensionUninitialized*Ptr`
  语义传给 Godot；只有 Godot 调用成功构造后，结果 carrier 才能被标记为 initialized。
- `godot_variant_call`、`godot_variant_evaluate`、`godot_variant_get*`、`godot_object_method_bind_call`、
  `godot_object_get_class_name`、`godot_get_library_path` 这类返回值 out-param 也按 uninitialized destination 处理。
  现有 `CBodyBuilder.declareUninitializedTempVar(...)`、`IndexLoadInsnGen`、`OperatorInsnGen` 的思路需要保留并扩展到
  新 generated wrapper，而不是被新的统一 wrapper 层抹平。
- destructor 只能作用于已初始化 carrier / slot。若 lookup failure、constructor 失败、call error 或 valid flag 表明结果不可用，
  cleanup path 不能销毁仍处于 uninitialized 状态的存储。
- `String`、`StringName`、`Variant`、typed `Array` / `Dictionary`、new object 路径是优先保护面；这些路径一旦把
  uninitialized destination 当成 initialized value，会产生双初始化、漏初始化、未初始化析构、内存泄漏或跨扩展 ptrcall 崩溃。

验收时必须有文本或生成模型级测试证明：

- `godot_interface.h/.c` 保留所有 `GDExtensionUninitialized*Ptr` 参数，不把它们规范化成 `GDExtensionTypePtr`。
- `godot_builtin.c` 中 constructor / conversion wrapper 使用 uninitialized destination 语义初始化本地结果，并且只在结果已初始化后进入 destroy path。
- `IndexLoadInsnGenTest`、`COperatorInsnGenTest` 或等价测试覆盖 `godot_variant_get*` 与 `godot_variant_evaluate`
  的 uninitialized temp；若现有 operator 测试没有同强度覆盖，阶段 2/3 必须补齐。
- `GD_STATIC_S` / `GD_STATIC_SN` 相关测试确认静态 `String` / `StringName` 首次初始化走 constructor wrapper，
  registry 只销毁已经初始化并注册的对象。

## Godot builtin wrapper 生成 contract

`doc/gdcc_runtime_lib.md` 定义当前 runtime helper 布局、`godot_` / `gdcc_` 命名形状和 fixed binding
注册规则；具体生成集合、过滤和 ABI contract 仍以本节规则与 generator 测试为准。
迁移后的 `GodotBuiltinGenerator` 必须把旧库中已经被 GDCC 代码依赖的过滤、合成和冲突规则固化为 GDCC 自己的版本级 contract。
“全量预生成 builtin wrapper”表示全量输出这个 contract 允许的 wrapper，不表示把 Godot JSON 的每个 builtin constructor/operator/method
无条件映射成 C 函数。

生成输入分层：

- raw metadata wrapper：
  - `builtin_classes[]` 中的 constructor、destructor、method、member、operator、indexed/keyed accessor。
  - constructor 的 `index` 是 Godot `Variant::get_ptr_constructor(type, index)` 的语义输入，必须保留原值；不能按 C 函数名重新分配 index。
  - operator 身份来自 operator enum、left builtin type、right builtin type 和 return ABI；实现 lookup 使用
    `godot_variant_get_ptr_operator_evaluator(op, left_type, right_type)`。
  - builtin method 的 `hash` 只用于 `godot_variant_get_ptr_builtin_method(type, method_name, hash)` lookup，不参与 wrapper 身份。
- synthesized compatibility wrapper：
  - 单 `String` 参数 constructor 的 char / UTF convenience wrapper。
  - `Variant` nil、from-type、to-type conversion helper。
  - typed `Array` / `Dictionary` 构造和 typed metadata accessor。
  - GDCC 现有 helper shim，例如 `Transform2D`、`Transform3D`、`Basis`、`Projection` 的矩阵/向量形式构造辅助；
    这些如果不是 Godot raw constructor，就归入 GDCC-owned helper 或 fixed/runtime override，而不是伪装成 JSON 项。

constructor / operator 过滤规则：

- 定义 atomic/non-struct set 为 `Nil`、`bool`、`int`、`float`，规则必须写入 generator 测试，不能散落在字符串特判中。
- 不生成 target / left type 为 `Nil` 的 constructor 或 operator wrapper。
- constructor 没有参数时，把第一个参数视为 `Nil` 参与过滤。
- 当 target / left type 和第一个 constructor 参数或 right operator type 同时属于 atomic/non-struct set 时，不生成 wrapper。
- 上述过滤保留 `String` 转换，例如 Godot 4.5.1 中 `int(String)`、`float(String)`、`Color(String)` 仍可生成；
  因为 `String` 不属于 atomic/non-struct set。
- 过滤是 ABI contract，不是测试优化。后续 GDCC 代码不能假设 `godot_new_int()`、primitive self-copy 或 primitive-primitive operator
  wrapper 一定存在。

method / member 冲突规则：

- builtin member accessor 由 `builtin_classes[].members[]` 生成 `godot_<Type>_get_<member>` /
  `godot_<Type>_set_<member>`。
- 若 builtin method 名为 `get_<member>` 或 `set_<member>`，且后缀与同一 builtin 的 member 名完全相同，
  则 method wrapper 不生成，由 member accessor 拥有该 C 名称。
- Godot 4.5.1 当前可见冲突是 `Transform2D.get_origin` 与 member `origin`；测试必须覆盖这个具体样本，防止将来改成同名不同 ABI 的双生成。
- builtin property surface 仍以 shared metadata normalization 后的 `members -> PropertyInfo` 为准；backend 不额外发明一套
  “method getter/setter fallback” 来绕过冲突过滤。

indexed / keyed accessor 规则：

- 只有 builtin class metadata 存在 `indexing_return_type` 时才生成 accessor。
- `is_keyed = false` 时生成 indexed accessor，key ABI 为 `int`，名称使用 `indexed_get` / `indexed_set` 家族。
- `is_keyed = true` 时生成 keyed accessor，key ABI 为 `Variant`，名称使用 `keyed_get` / `keyed_set` 家族。
- 返回 ABI 来自 `indexing_return_type`；例如 Godot 4.5.1 中 `Array` 是 indexed `Variant`，`Dictionary` 是 keyed `Variant`，
  `String` 是 indexed `String`，vector/rect/packed array 按 metadata 的元素类型生成。

合成 constructor / conversion 规则：

- 对每个已经通过过滤、且参数列表恰好为一个 `String` 的 constructor，合成以下便利构造器：
  - `godot_new_<Type>_with_latin1_chars`
  - `godot_new_<Type>_with_latin1_chars_and_len`
  - `godot_new_<Type>_with_utf8_chars`
  - `godot_new_<Type>_with_utf8_chars_and_len`
  - `godot_new_<Type>_with_utf16_chars`
  - `godot_new_<Type>_with_utf16_chars_and_len`
  - `godot_new_<Type>_with_utf32_chars`
  - `godot_new_<Type>_with_utf32_chars_and_len`
  - `godot_new_<Type>_with_wide_chars`
  - `godot_new_<Type>_with_wide_chars_and_len`
- 规则必须 metadata-driven。Godot 4.5.1 下当前命中类型是 `int`、`float`、`String`、`Color`、`StringName`、`NodePath`，
  但 generator 测试应校验“所有单 `String` 参数 constructor 都有合成 helper”，而不是硬编码这 6 个类型作为唯一事实。
- `String` 类型自身可以直接调用 Godot string interface constructor；其他类型先构造临时 `String`，再调用已有
  `godot_new_<Type>_with_String` constructor，最后销毁临时 `String`。
- `Variant` conversion helper 是合成 ABI：
  - `godot_new_Variant_nil`
  - `godot_new_Variant_with_<Type>`
  - `godot_new_<Type>_with_Variant`
  - `godot_new_Variant_with_Variant`
  - `godot_new_<Type>_with_<Type>` copy constructor
- `_Generic` overload 只在 `gdcc/*.h` 仍需要时作为 GDCC helper 内部宏保留；它不是新的 public wrapper 发现机制。

typed container 和 GDCC-owned helper 边界：

- `godot_new_Array_with_Array_int_StringName_Variant`、
  `godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant`、
  `godot_Array_get_typed_builtin`、`godot_Array_get_typed_class_name`、`godot_Array_get_typed_script`、
  `godot_Dictionary_get_typed_key_builtin`、`godot_Dictionary_get_typed_key_class_name`、
  `godot_Dictionary_get_typed_key_script`、`godot_Dictionary_get_typed_value_builtin`、
  `godot_Dictionary_get_typed_value_class_name`、`godot_Dictionary_get_typed_value_script`
  是 typed container ABI 的固定 contract，必须由 builtin 全量预生成或明确的版本化 override 覆盖。
- `godot_TypedArray(...)` 若继续作为宏暴露，应在 `godot_builtin.h` 或 GDCC helper 头中重新定义清楚；
  `godot_TypedDictionary(...)` 当前属于 `gdcc_helper.h` 的 GDCC-owned 宏，不应误归类为 Godot raw wrapper。
- `gdcc_new_Vector*_from_call_arg_variant`、`gdcc_new_Variant_with_gdcc_Object`、
  `_field_init_` / `_field_getter_` / `_field_setter_` 这类名字是 GDCC-owned ABI，不进入 `godot_*` wrapper 生成集合。

命名、身份和冲突处理：

- C 函数名沿用 `doc/gdcc_runtime_lib.md` 的 `godot_` 命名习惯，但生成 contract 以本节规则为准。
- canonical key 必须包含 wrapper family/kind、owner/type、constructor index 或 operator/member/method/property 标识、
  参数 ABI、返回 ABI、vararg/static 标记；参数 ABI 必须包含 initialized / const / uninitialized destination 状态，
  不能只记录底层 `void *` 形态。engine property helper 若捕获 indexed property 的固定 `index`，必须把该 index
  作为调用材料纳入 key，避免共享同一 getter/setter 的不同 property 被错误合并。
- `hash`、`hash_compatibility`、constructor/method lookup 缓存变量名都不是 canonical key。
- 同一 canonical symbol 重复出现只合并一次；同一 C function name 映射到不同 canonical key 或不同 ABI 时 fail-fast。
- 同一 canonical symbol 的 lookup metadata 只能在兼容时合并；不兼容时 fail-fast，不能通过新增第二个同名或近似同名 wrapper 掩盖。
- 生成顺序可以为 diff 稳定做排序，但不能改变 Godot Variant type order、constructor index、operator lookup type 这些语义字段。

## 固定 helper wrapper 生成

`src/main/c/codegen/include_451/gdcc/*.h` 是固定 runtime helper，不属于某个脚本模块的 IR。
这些头文件会直接或通过宏拼接调用 `godot_*` wrapper。典型来源包括：

- `gdcc_call.h`：`godot_new_Variant_with_int`、`godot_new_Variant_with_float`、`godot_new_Variant_with_String`、`godot_Object_call`。
- `gdcc_string.h` / `gdcc_string_name.h`：`godot_new_String_with_utf8_chars`、`godot_new_StringName_with_utf8_chars`、destroy、hash、memory wrapper。
- `gdcc_bind.h`：property info 所需的 `godot_new_StringName_with_StringName`、`godot_new_String_with_String`、property registration。
- `gdcc_intrinsic.h`：`Vector*i -> Vector*` conversion constructor。
- `gdcc_helper.h`：object getter/setter、`Engine` / `ClassDB` singleton、`RefCounted` lifecycle、`Object.notification`、manual builtin constructors、Variant call error formatting。
- `entry.c.ftl` / `entry.h.ftl` / `engine_method_binds.h.ftl`：模板固定路径中使用的 `godot_print`、`godot_new_Variant_nil`、typed Array/Dictionary probe、method bind call/ptrcall。

这些固定依赖不能靠扫描器从 C/FreeMarker 文本中自动推断事实源：

- FreeMarker 会拼接符号名，例如 `gdcc_bind_method${...}`、`helper.renderEngineMethodCallHelperName(...)`。
- Java helper 会返回函数名字符串，例如 pack/unpack、copy/destroy、utility、engine fallback 名称。
- C helper 使用 `_Generic`、`##` token pasting 和多层宏，例如 `_GDV(...)`、`GDCC_DEFINE_OBJECT_GETTER(...)`、`GD_STATIC_SN(...)`。
- generated C 文本扫描只能看到展开后的部分结果，无法知道哪些符号是版本固定 runtime contract，哪些只是模块局部产物。

因此固定 helper 依赖的事实源必须改为版本化源码清单。扫描器只做验收对账，不负责写入清单、不负责补生成。
建议增加：

- `src/main/java/gd/script/gdcc/backend/c/gen/binding/FixedGodotBindings`
  - 源码维护固定 wrapper 清单的抽象生成骨架，按 `GodotVersion` 分派到具体版本数据集。
  - `Godot451FixedBindings` 只维护 Godot 4.5.1 固定 wrapper 清单；新版本应添加新的数据子类。
  - 使用 `List.of(...)` / record literal 描述 `GodotBindingSymbol`，保持 review 可读。
  - 只包含不由全量 interface、全量 builtin、全量 utility 覆盖的固定 runtime/helper/template wrapper。
  - 对 `_Generic`、宏拼接、FreeMarker 拼接和 Java helper 生成的固定调用，必须在这里显式列出。
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/GdccHelperBindingScanner.java`
  - 扫描 `src/main/c/codegen/include_451/gdcc/*.h` 与固定模板，只做对账。
  - 报告“扫描可见但不在 interface/builtin/utility/fixed 清单/module-local 白名单中的 `godot_*`”。
  - 报告“固定清单中已经不再被固定 helper 使用且没有文档解释的 symbol”，由维护者决定是否移除。
  - 不修改 `FixedGodotBindings` 版本数据，不把扫描结果写回事实源。
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/GodotBindingSymbolHelper.java`
  - 可以保留为 generated snapshot 校验与写出工具，但不再作为固定 helper 的事实源。
  - 从 `FixedGodotBindings` 当前版本数据生成规范化 snapshot，用于测试、对账和 module-local provided set。
  - 读入时按 canonical key 去重，遇到同一 C function name 映射到不同结构性签名/ABI 时直接报错。
  - 对同一 canonical symbol，只允许合并兼容的 lookup hash metadata。
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/GodotBindingTool.java`
  - 提供 `public static void main(String[] args)`，可以直接用 classpath 调用。
  - 该工具不依赖 Gradle task；本迁移不修改 build script。

命令行工具建议支持以下子命令：

```bash
java -cp "build/classes/java/main:build/resources/main:build/libs/lib/*" gd.script.gdcc.backend.c.gen.binding.GodotBindingTool \
  generate-interface \
  --gde 4.5.1 \
  --header src/main/c/codegen/include_451/godot/gdextension/gdextension_interface.h \
  --out src/main/c/codegen/include_451/godot

java -cp "build/classes/java/main:build/resources/main:build/libs/lib/*" gd.script.gdcc.backend.c.gen.binding.GodotBindingTool \
  generate-abi-support \
  --gde 4.5.1 \
  --api-resource /extension_api_451.json \
  --out src/main/c/codegen/include_451/godot

java -cp "build/classes/java/main:build/resources/main:build/libs/lib/*" gd.script.gdcc.backend.c.gen.binding.GodotBindingTool \
  check-fixed \
  --gde 4.5.1 \
  --helper-root src/main/c/codegen/include_451/gdcc \
  --template-root src/main/c/codegen

java -cp "build/classes/java/main:build/resources/main:build/libs/lib/*" gd.script.gdcc.backend.c.gen.binding.GodotBindingTool \
  generate-fixed \
  --gde 4.5.1 \
  --out src/main/c/codegen/include_451/godot

java -cp "build/classes/java/main:build/resources/main:build/libs/lib/*" gd.script.gdcc.backend.c.gen.binding.GodotBindingTool \
  dump-fixed-manifest \
  --gde 4.5.1 \
  --out src/main/c/codegen/binding_451/godot_fixed_bindings.snapshot.json
```

本地运行前先执行 `./gradlew jar --no-daemon --info --console=plain` 或任何会产出
`build/classes/java/main`、`build/resources/main` 和 `build/libs/lib` 的构建步骤。

命令语义：

- `generate-interface`：从 Godot 导出的 `gdextension_interface.h` 读取 `@name` / typedef 配对，生成
  `godot_interface.h/.c`；缺失配对、重复 `@name` 或同名不同 ABI 时 fail-fast，bootstrap typedef 只写入报告。
- `generate-abi-support`：从 `ExtensionAPI` Java 模型生成 `godot_global_enums.h`、`godot_global_constants.h`、
  `godot_native_structures.h`、`godot_builtin_sizes.h`、`godot_builtin_layout.h` 等 ABI support header；
  `global_constants[]` 为空时也写出稳定空 header。
- `check-fixed`：读取版本化固定清单，校验所有 symbol 都可由当前 Godot 4.5.1 metadata 解析，没有 C function name 冲突；
  同时扫描固定 helper/template 做对账，发现清单外 `godot_*` 只报错，不自动写入。interface wrapper
  provided set 来自 `--header`，若未传入则从 `--helper-root` 的 sibling `godot/gdextension/gdextension_interface.h`
  推断。
- `generate-fixed`：从版本化固定清单生成 `godot_fixed_binding.h/.c`，并更新 `godot_binding.h/.c` 的聚合 include。
- `dump-fixed-manifest`：从版本化固定清单导出规范化 snapshot，方便 review、测试和 generated C 扫描使用。

`FixedGodotBindings` 的版本数据是固定 helper wrapper 的事实来源。修改 `gdcc/*.h` 或固定模板后，流程必须是：

1. 手动维护目标 Godot 版本对应的数据子类，例如当前 4.5.1 的 `Godot451FixedBindings`。
2. 运行 `check-fixed`，让扫描器只做对账和 fail-fast。
3. 运行 `generate-fixed` 写回 `godot_fixed_binding.h/.c`。
4. 可选运行 `dump-fixed-manifest` 更新 snapshot。
5. 运行 targeted tests。

## 使用集收集

迁移不能只替换头文件，因为 Java 生成器当前直接拼出大量 `godot_*` 符号。但是使用集不能再承担 builtin / utility /
fixed helper 的发现职责：FreeMarker 字符串拼接、Java helper 返回名、C `_Generic` 和 token pasting 宏都会让扫描式登记漏记。

wrapper 来源按层分离：

- interface wrapper：从 Godot 4.5.1 `gdextension_interface.h` 的 `@name` / typedef 配对取得 wrapper 集合和签名，
  全量生成到 `godot_interface.h/.c`。
- builtin wrapper：按 Godot 版本从 `ExtensionAPI.builtin_classes[]` 经 GDCC builtin contract 投影后全量生成到 `godot_builtin.h/.c`。
- utility wrapper：按 Godot 版本从 `ExtensionAPI.utility_functions[]` 全量生成到 `godot_utility.h/.c`。
- fixed runtime wrapper：只包含 `FixedGodotBindings` 当前版本数据列出的具体符号，生成到
  `godot_fixed_binding.h/.c`；它不按 singleton、class constant、engine class 这类大类拥有符号。
- module-local wrapper：只管理经过 interface/builtin/utility/fixed provided set 过滤后仍随模块变化的具体符号，
  例如未 provided 的 engine public method/property helper、singleton getter、class constant helper。

因此 `CCodegen.generate()` 只需要持有一个总的 backend binding usage session。该 session 对外作为
`GodotBindingUsageSession` API，内部再拆分 exact engine method、engine constructor、module-local singleton/constant
三条使用集；builtin / utility 不进入模块级输出。

当前 Java 类型：

- `src/main/java/gd/script/gdcc/backend/c/gen/binding/usage/GodotBindingUsageSession.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/usage/GodotBindingUsageBuffer.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/usage/AbstractUsageSession.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/usage/AbstractUsageBuffer.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/ModuleLocalGodotBinding.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/GodotBindingSymbol.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/binding/GodotBindingProvidedSymbols.java`
- `src/main/c/codegen/template_451/engine_method_binds.h.ftl`

职责划分：

- `GodotBindingUsageSession` 是 `CCodegen` 使用的总 API：
  - 对外提供 `newFunctionBuffer()`、`commit(buffer)`、`engineMethods()`、`engineConstructors()`、
    `moduleLocalBindings()`；测试可额外读取 provided/module-local C function name snapshot。
  - 内部拥有 exact engine method、engine constructor、module-local binding 三套 collector；生成器不再在调用链中并列传递多组
    session/buffer 参数。
  - 通过 `GodotBindingProvidedSymbols` 加载 interface/builtin/utility/fixed provided set。
- `GodotBindingUsageBuffer` 是函数级临时记录器：
  - 同时承载 engine method、engine constructor、module-local binding 三类记录入口。
  - 函数体、property init apply body、模板 facade 各自使用独立 buffer；对应 render 成功后才 commit，失败时丢弃，
    不污染模块级 session。
- `ModuleLocalGodotBinding` 表示当前会在 module-local header section 中输出的 wrapper：
  - 当前只允许 `Singleton` 和 `ClassConstant` 两条路径。
  - 已去掉无生产使用点的 generic `CLASS_METHOD` 类别；exact engine method/property 继续走 backend-owned
    `gdcc_engine_call*` / accessor 使用集，engine constructor 继续走 constructor 使用集。
- `GodotBindingSymbol` 表示 Godot wrapper 的结构性 C surface：
  - 预生成层：builtin constructor/destructor、variant pack/unpack、builtin member/index/key/operator helper、utility function。
  - 固定层：固定 helper/template 当前实际消费、且不属于 interface/builtin/utility 全量集合的具体符号。
  - 模块层：脚本或模板在本模块实际需要、且不属于 provided set 的具体 singleton/class constant 符号。
- `engine_method_binds.h.ftl` 根据 `moduleLocalBindings()` snapshot 渲染 module-local `static inline` wrapper section；
  Java 侧只传递结构化 binding 列表，不再用 Java 字符串拼接生成 singleton/class constant C wrapper。

去重规则：

- module-local key 由 `ModuleLocalGodotBinding` 的 family、owner、name、C function name 和 signature 组成。
- 当前 module-local singleton/class constant 不保存 method lookup hash；exact engine method lookup hash 保留在
  `EngineMethodUsageSession` 对应的 `ResolvedMethodCall` 路径内。
- `GodotBindingUsageSession` 内部 collector 使用 `LinkedHashMap` 保持稳定顺序；该顺序匹配首次成功 body render 的 hit order。
- session 初始化时读入 interface/builtin/utility/fixed provided set，并把这些 symbol 标记为 `providedByRuntime = true`。
- 函数级 `GodotBindingUsageBuffer` 渲染成功后再 commit；commit 时 `putIfAbsent`，脚本再次使用预生成/fixed wrapper 或同一脚本内重复使用同一 wrapper 都不会生成第二份实现。
- 若两个不同 spec 生成同一个 C function name，但结构性 signature 或 ABI 不一致，必须 fail-fast；这表示命名规则冲突，不能靠后写覆盖。
- 若同一个 canonical module-local binding 的 metadata 不兼容，也必须 fail-fast；当前主要覆盖 class constant value 漂移。
- `engine_method_binds.h.ftl` 只输出 `providedByRuntime = false` 的 module-local wrapper；输出位置必须已经位于
  `entry.h` include 链路中，以复用 `<godot_binding.h>`、`<gdcc_helper.h>` 和 entry translation unit 的状态。
- 预生成集合或固定清单更新后，`CCodegen` 不需要改 builtin/utility 调用点；后续脚本中使用同一 wrapper 会被 session 识别为已由 runtime support 提供。

另需新增一个固定 interface generator：

- 输入：Godot 4.5.1 导出的 `src/main/c/codegen/include_451/godot/gdextension/gdextension_interface.h`。
- 输出：`src/main/c/codegen/include_451/godot/godot_interface.h` 与 `godot_interface.c`。
- 规则：解析 header 中 `@name` / `@since` / 紧随 function pointer typedef 的配对，生成同签名的 `godot_*` wrapper。
  wrapper name 和 lookup key 来自 `@name`，typedef name 只作为 C 函数指针类型保留。
- 验证：生成的 wrapper name 集合等于 header 中可解析的 `@name` / typedef 配对集合；模板和固定 helper 使用的每个
  `godot_*` interface function 均来自该全量输出。没有 `@name` 的 bootstrap typedef 只进入报告，不参与 wrapper 数量断言。

使用集收集必须遵守 `engine_method_bind_implementation.md` 中已经确立的原则：`generateFuncBody(clazz, func)` 的 public 入口保持近似纯函数，不引入共享隐式状态；只有 `CCodegen.generate()` 持有模块级 session。

## 模块级 wrapper 使用集接入策略

当前代码没有独立的 `CCodeGenContext`，实际上下文链路是：

1. `CCodegen.prepare(CodegenContext, LirModule)` 注册 GDCC 类并创建 `CGenHelper`。
2. `CCodegen.generate()` 创建模块级 session 和模板级 buffer，构造 `GenerateRenderFacade`，再渲染 `entry.c.ftl`。
3. 模板通过 `bodyRender.generateFuncBody(classDef, func)` 进入 Java 函数体渲染。
4. `generateFuncBody(clazz, func, usageSession)` 为每个函数创建函数级 buffer，
   构造 `CBodyBuilder`，逐条分发 `CInsnGen`。
5. 函数体渲染成功后才 commit buffer；渲染失败不得污染模块级使用集。
6. `entry.c` 渲染成功后，`CCodegen.generate()` 先 commit 模板级 buffer，再取得 session snapshot，并把 module-local wrapper section
   作为 `engine_method_binds.h` 的一部分渲染；最终仍只返回核心 3 个 `GeneratedFile`。

因此 `GodotBindingUsageSession` 应保持 caller-owned module session 所有权边界，而不是放进全局单例。
它通过内部子 collector 管理 exact engine method、engine constructor、module-local singleton/class constant：

- `CCodegen.generate()` 持有一个 `GodotBindingUsageSession`。
- `GodotBindingUsageSession` 构造时读取 interface symbol set、全量 builtin symbol set、全量 utility symbol set、fixed source-list snapshot，把它们放入 module-local provided set。
- `GenerateRenderFacade` 提供类型化 `recordModuleLocalGodotBinding(ModuleLocalGodotBinding binding)`，
  仅写入 `CCodegen.generate()` 创建的模板级 buffer，用于模板中仍可能产生 module-local singleton/class constant wrapper 的路径；
  全量/固定 provided symbol 不需要登记来驱动生成。
- `CCodegen.generateFuncBody(clazz, func, usageSession)` 创建一个总 `GodotBindingUsageBuffer`，并传给 `CBodyBuilder`。
- `CBodyBuilder` 通过 `recordUsedEngineMethodCall(...)`、`recordUsedEngineConstructor(...)`、
  `recordModuleLocalGodotBinding(...)` 分别记录到总 buffer 的对应子 collector；`callVoid(...)` / `callAssign(...)`
  中对 `funcName.startsWith("godot_")` 的调用先查询 provided/module-local set。
- 对 `appendLine(...)` / `appendRaw(...)` 直接拼出的 `godot_*` 调用，不依赖字符串扫描兜底；对应 generator 必须在 emit 点显式登记
  `ModuleLocalGodotBinding`，前提是该符号不属于 interface/builtin/utility/fixed provided set。
- `CCodegen.generatePropertyInitApplyBody(...)` 接收同一个 `GodotBindingUsageSession`，创建函数级 buffer 并在成功后 commit；
  constructor/copy/destroy 等 builtin wrapper 已由全量 builtin 提供。

记录入口按“尽量集中，必要时显式”的策略分层：

- `CBodyBuilder.callVoid` / `callAssign`
  - 统一检查函数体中经 builder 发出的 `godot_*` 调用是否属于 provided set 或 module-local set。
  - `CallGlobalInsnGen` 的 utility wrapper、`NewDataInsnGen` 的 String/StringName/Variant constructor、`PackUnpackVariantInsnGen`、
    `CBuiltinBuilder`、builtin property/operator/index/key 路径均由全量 builtin/utility 提供，不驱动 module-local 生成。
  - `CallMethodInsnGen` dynamic fallback、engine property、class constant 等残余路径仍可登记 module-local wrapper。
  - engine class constructor 已由阶段 3.2 的 constructor usage snapshot 按需生成；阶段 4 只把该 snapshot
    收进总 `GodotBindingUsageSession` 的内部 constructor collector，不作为 `ModuleLocalGodotBinding` 类别。
- `CBodyBuilder.emitDestroy(...)`
  - `godot_object_destroy` 是 interface wrapper。
  - `godot_<Builtin>_destroy` 来自全量 builtin wrapper，不进入 module-local usage set。
- `CBodyBuilder.prepareRhsValue(...)` / `prepareReturnValue(...)`
  - 通过 `helper.renderCopyAssignFunctionName(type)` 发出的 copy constructor wrapper 来自全量 builtin wrapper。
- `CBodyBuilder.renderDefaultValueExpr(...)`
  - 该静态方法目前返回 `godot_new_*` 文本，无法访问 session；这些 default constructor wrapper 必须全部来自全量 builtin provided set。
  - generated C 扫描负责验证输出中的 `godot_new_*` 均能被 provided/module-local set 解释。
- `CGenHelper.renderPackFunctionName(...)` / `renderUnpackFunctionName(...)`
  - 这些方法只负责稳定命名，不隐式访问 session。
  - pack/unpack wrapper 来自全量 builtin provided set；调用点不需要为生成而登记。
- `CGenHelper.resolveUtilityCall(...)`
  - 解析 `foo` / `godot_foo` 并返回 `godot_<utility>`；utility wrapper 来自全量 utility provided set。
- `CBuiltinBuilder.constructBuiltin(...)`
  - 构造器选择仍在这里完成；constructor wrapper 来自全量 builtin provided set。
  - 对 `bodyBuilder.newTempVariable(..., "godot_new_Array()")`、`"godot_new_Variant_nil()"` 这类 initializer 字符串，不依赖登记生成。
- `OperatorInsnGen`
  - `godot_variant_evaluate` 是 interface wrapper，不进 usage set。
  - `godot_new_Variant_with_Variant`、unpack wrapper、`godot_new_Variant_nil` 来自全量 builtin provided set，不登记生成。
  - `entry.h.ftl` 生成的 operator evaluator helper 中调用 `godot_variant_get_ptr_operator_evaluator` 属于全量 interface wrapper。

- `IndexLoadInsnGen` / `IndexStoreInsnGen`
  - `godot_variant_get*` / `godot_variant_set*` 是 interface wrapper。
  - pack/unpack、nil/default Variant wrapper 来自全量 builtin provided set，不登记生成。
- `LoadPropertyInsnGen` / `StorePropertyInsnGen`
  - builtin property wrapper `godot_<Builtin>_get_<property>` / `godot_<Builtin>_set_<property>` 来自全量 builtin provided set。
  - engine property wrapper `godot_<Class>_get_<property>` / `godot_<Class>_set_<property>` 只在 exact engine route 之外仍需要时生成。
  - unknown object fallback 的 `godot_Object_get` / `godot_Object_set` 来自 fixed source-list provided set。
- `BackendMethodCallResolver`
  - exact `ENGINE` route 继续生成 `gdcc_engine_call*`，只进入总 `GodotBindingUsageSession` 的 engine method 子 collector。
  - `BUILTIN` route 生成 `godot_<Builtin>_<method>`，来自全量 builtin provided set。
  - `GDCC` route 生成 `Owner_method`，不进入 Godot binding usage。
  - `OBJECT_DYNAMIC` / `VARIANT_DYNAMIC` 的 `godot_Object_call` / `godot_Variant_call` 来自 fixed source-list provided set。
- `ConstructInsnGen`
  - engine object constructor `godot_new_<Class>()` 由 `valueOfOwnedExpr(...)` 直接拼出，必须在该分支显式登记。
  - GDCC object constructor、`gdcc_ref_counted_init_raw(...)` 不进入 Godot binding usage；其内部 fixed helper 依赖由 fixed source-list 覆盖。
- `LoadStaticInsnGen`
  - global enum、global constant、builtin constant 当前多为直接值或 literal materialization。
  - engine class integer constant 若未来改为 `godot_<Class>_<Constant>()` helper，必须登记为 class constant symbol；当前直接 literal 路径不登记。

模板路径不能依赖模块函数体 session 自动覆盖：

- `entry.c.ftl`
  - 初始化/注册使用的 `godot_classdb_register_extension_class5`、`godot_classdb_construct_object2`、`godot_object_set_instance` 等来自全量 interface wrapper。
  - loading/unloading log 中的 `godot_new_Variant_with_String`、`godot_Variant_destroy` 来自全量 builtin wrapper；`godot_print` 来自全量 utility wrapper。
  - `godot_Object_notification` 和 `godot_Object_NOTIFICATION_*` 属于 fixed source-list/runtime set。
- `entry.h.ftl`
  - method wrapper 的 typed Array/Dictionary preflight、operator evaluator helper、call wrapper unpack/destroy 会直接调用 `helper.*` 渲染出的名字。
  - pack/unpack/destroy/typed metadata wrapper 来自全量 builtin/fixed provided set；模板不负责为这些符号登记生成。
- `engine_method_binds.h.ftl`
  - exact engine bind lookup和 ptrcall/call 都来自 interface wrapper。
  - vararg exact engine helper 中固定参数打包、返回值 unpack、临时 destroy 均来自全量 builtin provided set；模板只需要验证这些符号已在 provided set 中。

防重防漏策略：

- 非 interface `godot_*` 函数名先查询 provided set：interface、全量 builtin、全量 utility、fixed source-list。
- 只有不在 provided set 且确认为具体 singleton getter 或 class constant helper 的符号进入 module-local binding collector。
- module-local header renderer 只输出未 provided 的 symbol。
- generated C 文本扫描只做验收：所有 `godot_*` 调用必须属于 interface set、builtin set、utility set、fixed set、module-local generated set、
  GDCC-owned macro/helper 白名单或类型名白名单。扫描失败表示漏记或清单缺失。
- 同一 canonical symbol 重复出现只保留首次顺序；同一 C function name 对应不同结构性 signature/ABI 直接报错。
- 同一 canonical module-local binding 的 metadata 必须兼容；当前 class constant value 漂移会 fail-fast，
  但不生成第二个 symbol。method hash 兼容规则留在 exact engine method / builtin / utility generator 路径内。
- 扫描不负责发现 wrapper、不负责补登记、不负责生成；新增路径要么落入全量预生成/固定清单，要么显式登记为 module-local。

## 模块级 wrapper 输出与编译规则

当前 `GeneratedFile.saveTo(projectPath)` 会把 codegen 产物写到生成项目目录；`CProjectBuilder` 再把本轮
`GeneratedFile` 中所有 `.c` 文件加入 native compiler input。为保持当前模块级单翻译单元模型，阶段 4
不得生成新的 module-local `.c`。module-local wrapper 必须沿用现有 `entry.c` / `entry.h` /
`engine_method_binds.h` 的项目生成物模型，由 header-only `static inline` code 进入 `entry.c`。

最佳方案固定为：

- `godot_binding.h/.c` 只代表静态 runtime support。
  - header 只聚合 `godot_abi.h`、`godot_interface.h`、`godot_builtin.h`、`godot_utility.h`、`godot_fixed_binding.h`。
  - source 只聚合 `godot_interface.c`、`godot_builtin.c`、`godot_utility.c`、`godot_fixed_binding.c`。
  - 任何 module-local wrapper 都不得被它们 include 或聚合。
- module-local header renderer 输出到 `engine_method_binds.h`。
  - `engine_method_binds.h` 与 `entry.h`、`entry.c` 仍是同一模块生成物链路。
  - `entry.h` 继续先 include `<godot_binding.h>` 与 `<gdcc_helper.h>`，再 include `"engine_method_binds.h"`。
  - module-local wrapper section 因此和 engine constructor wrapper、exact engine helper 一样进入 `entry.c`
    这一唯一模块级 translation unit。
- `CProjectBuilder` 不新增 `projectPath` include dir。
  - 阶段 3 仍只追加一个静态 runtime C 输入：`<includeRoot>/godot/godot_binding.c`。
  - 阶段 4 后，module-local wrapper snapshot 非空也不得让 projectPath 下出现额外 generated `.c`。
  - 不扫描 `projectPath` 寻找额外 `.c`，只编译本轮 codegen 返回的 `entry.c` 和固定 runtime source，
    避免 stale 文件进入编译。
  - 这条规则还要覆盖 shared include 残留：`CProjectBuilder` 不得扫描或按存在性追加
    `<includeRoot>/gdextension-lite/gdextension-lite-one.c`，否则 `ResourceExtractor` 不删除旧文件的语义会让迁移前 vendor
    重新参与编译。

这条规则解决两个边界问题：

- 静态 include root 不需要也不能知道某个模块的 generated wrapper。
- “继续只追加一个 runtime binding C 文件”同时也是阶段 4 的 native input 约束：模块级 C 输入仍只有 `entry.c`。

## ExtensionAPI metadata 与 engine property 语义前置重构

后续 ABI header、全量 builtin/utility wrapper、fixed wrapper 和 module-local wrapper 都依赖 Godot `extension_api_451.json` 的完整 metadata。
当前 Java 侧模型还不能作为这些 generator 的输入事实来源，必须先补齐再进入阶段 1A / 阶段 2 / 阶段 4。
但这个前置工作不是“先做了总没坏处”的无行为准备：engine property 的可读/可写、owner 解析和 fallback 已经参与现行
frontend/backend 语义，`ExtensionGdClass.PropertyInfo` 一旦开始暴露 raw `getter/setter/index`，就会影响后续 direct engine
accessor、module-local wrapper 和 fallback 的边界。

现行调用面：

- frontend 属性链路由 `ScopePropertyResolver.resolveObjectProperty(...)` 决定 resolved property、metadata unknown fallback
  和 missing property failure；`PropertyDefAccessSupport.isDirectlyWritable(...)` 已经读取 engine `isWritable()`。
- backend 的 `BackendPropertyAccessResolver` 只翻译 shared resolver 结果；真正输出 direct accessor / fallback 的是
  `LoadPropertyInsnGen` 和 `StorePropertyInsnGen`：
  - `MetadataUnknown` 走 `godot_Object_get` / `godot_Object_set` runtime fallback。
  - ENGINE owner 当前直接拼 `godot_<Owner>_get_<property>` / `godot_<Owner>_set_<property>`。
  - GDCC owner 才读取 `PropertyDef.getGetterFunc()` / `getSetterFunc()`。
  - builtin property 仍由 `ExtensionBuiltinClass.members -> synthetic PropertyInfo` 的 property surface 驱动。
- 因此阶段 0 必须保护“现有 resolved/fallback/readonly/direct accessor”语义；不能只验证 loader 字段非空。

现状缺口：

- `ExtensionAPI` 已有 `builtinClassSizes` 和 `builtinClassMemberOffsets` 字段，但
  `ExtensionApiLoader.loadFromResource(...)` 当前返回 `Collections.emptyList()`。
  这会直接阻塞 `godot_builtin_sizes.h`、builtin struct size assert、builtin member offset/member metadata 生成。
- builtin constructor index 当前已有解析路径；builtin method hash、utility function hash 也已有解析路径，但必须作为 lookup metadata
  回归测试的一部分锁住，因为 fixed wrapper 和 module-local wrapper 会把这些值写入 C lookup。
- `ExtensionBuiltinClass` 当前缺少 `has_destructor`、`indexing_return_type` 等字段；这会阻塞全量 builtin destructor、
  indexed/keyed accessor 和固定 helper 周边 wrapper 的可靠生成。
- `ExtensionAPI` 当前没有承载顶层 `global_constants[]`；`ExtensionApiLoader` 也没有对应解析分支。
  这不应写成“后续 helper 需要时再补”，因为手写 `gdcc` helper 和后续脚本常量路径都必须依赖同一个
  Java 模型和静态 header，避免重新引入旧 vendor 常量头或按需 JSON 解析。
- `ExtensionGdClass.PropertyInfo` 当前只承载 `name/type/isReadable/isWritable/defaultValue`，
  `getGetterFunc()` / `getSetterFunc()` 始终返回 `null`，也没有保存 Godot JSON 中的 `index`。
  这会阻塞 `classes[].properties[].getter/setter/type/index` 到 engine property wrapper 的解析。

Godot 上游字段语义：

- `core/extension/extension_api_dump.cpp` 中的 `builtin_class_sizes` 来自 Godot 按
  `float_32`、`float_64`、`double_32`、`double_64` 维护的 builtin type size 表；这是 ABI layout metadata，
  不是 ClassDB 对象属性。
- `builtin_class_member_offsets` 来自 Godot 的 builtin member offset/meta 表；它描述 `Vector2.x`、`Vector3.z`
  这类 builtin 复合值成员布局，也不属于 engine property 系统。
- 上游对 size 和 member offset/meta 使用同一组 build configuration：
  `float_32`、`float_64`、`double_32`、`double_64`。GDCC metadata 模型必须保留这个维度，不能只读取当前宿主机配置；
  但 C backend 生成物和运行时 ABI 只支持 `float_64`，其余配置只作为上游 metadata 完整性事实保留。
- member offset/meta 是字段级 layout ABI，不是整体 size 的派生物：
  - `Vector3.z` 在 `float_32` / `float_64` 下是 `offset=8, meta=float`，在 `double_32` / `double_64`
    下是 `offset=16, meta=double`。
  - `Color.r/g/b/a` 即使在 `double_*` 配置下也仍是 `meta=float`，不能用 “REAL_T_IS_DOUBLE => 所有 real member 都是 double”
    这类推断替代 metadata。
  - `Transform2D.origin`、`Basis.x` 等复合 member 的 `meta` 是 `Vector2` / `Vector3`，字段访问和后续优化必须按
    meta 的 ABI 形态解释 member。
- `classes[].properties[]` 由 `ClassDB::get_property_list(...)` 枚举 property，再通过
  `ClassDB::get_property_getter(...)`、`get_property_setter(...)`、`get_property_index(...)` 补充访问方式。
  `getter` / `setter` 是 property 关联的方法名；`index` 是 indexed property 调用 getter/setter 时传入的固定索引，
  不是单独的 property 身份。
- `classes[].methods[].hash` / `hash_compatibility`、builtin method hash / 可选 `hash_compatibility`、
  utility hash 只用于 method bind / builtin / utility lookup 及兼容性验证；它们不参与 wrapper canonical identity。
- `global_constants[]` 是 Godot API dump 的顶层数组，来自 `CoreConstants` 中没有归入某个 global enum 的常量；
  每项至少包含 `name`、`value`、`is_bitfield`，可选 `description`。Godot 4.5.1 当前资源中的数组为空，
  但字段仍是目标版本 metadata contract 的一部分，GDCC 必须完整建模和生成稳定空输出。
- `global_enums[].values[].value`、`classes[].enums[].values[].value` 同样来自 Godot 的 64-bit integer 常量面；
  Java 模型必须用 `long` 保真。`builtin_classes[].enums[].values[].value` 当前上游来源较窄，但为了统一
  `ExtensionEnumValue` contract 也按 `long` 建模。`builtin_classes[].constants[].value` 仍是 Godot construct string，
  不能为了统一数字宽度改成数值 carrier。

前置修改：

- `ExtensionApiLoader`
  - 新增 `parseBuiltinClassSizes(...)`，解析 `builtin_class_sizes[]`：
    `build_configuration` 和 `sizes[].name/size`。
  - 新增 `parseBuiltinClassMemberOffsets(...)`，解析 `builtin_class_member_offsets[]`：
    `build_configuration`、`classes[].name`、`classes[].members[].member/offset/meta`。
  - `loadFromResource(...)` 必须把上述两个集合传入 `new ExtensionAPI(...)`，不再传空集合。
  - 解析 builtin class 的 `has_destructor`、`indexing_return_type`，并保留 raw Godot 字段语义。
  - 新增并解析 `global_constants[]` 模型，字段至少保留 `name`、`value`、`is_bitfield` 和可选 description；
    `value` 必须能承载 Godot int64 常量，不能按 Java `int` 截断。
  - 解析 global enum、builtin enum 和 engine class enum 的 `values[].value` 时使用 `long` / `getAsLong()`；
    这些字段是 metadata value carrier，不改变 `enum::...` / `bitfield::...` 归一化为脚本 `int` 的类型规则。
  - `loadFromResource(...)` 必须把 `global_constants[]` 传入 `new ExtensionAPI(...)`；字段缺失时可以是空集合，
    但不能让 generator 绕过 Java 模型重读 JSON。
  - 解析时保留 Godot JSON 原始字段语义，不把缺失 size/offset 静默填成可用值；缺失关键字段应在 generator 或测试中 fail-fast。
- `ExtensionGlobalConstant`
  - 新增顶层 record，作为 `ExtensionAPI.globalConstants()` 的元素。
  - 后续 `godot_global_constants.h` generator、scope/global constant resolver 和 `LoadStaticInsnGen` 只读取该模型。
  - 即使 Godot 4.5.1 为空，也要通过 fixture 测试覆盖非空 `global_constants[]` 的解析、命名和 literal materialization。
- `ExtensionBuiltinClassSizes`
  - 继续作为 build-configuration 到 builtin size list 的模型。
  - 后续 `godot_builtin_sizes.h` generator 只读取该模型，不重新扫描 JSON。
  - builtin size 查询由 `ExtensionAPI.findBuiltinClassSize(...)` /
    `requireBuiltinClassSize(...)` 集中暴露；调用方不应在 generator/test 中重复扫描原始列表。
- `ExtensionBuiltinClassMemberOffsets`
  - 继续作为 build-configuration 到 builtin member offset/meta 的模型。
  - 若阶段 1A 的 builtin struct 声明或阶段 2 全量 builtin wrapper 需要 member ABI meta，应从这里读取。
  - 必须提供按 `(buildConfiguration, builtinClass, member)` 查询 layout 的路径；generator 不应在每次使用时线性扫描
    全量列表，也不应按 `ExtensionBuiltinClass.members[]` 的顺序自行累加 offset。
  - member layout 查询由 `ExtensionAPI.findBuiltinClassMemberLayout(...)` /
    `requireBuiltinClassMemberLayout(...)` 集中暴露；`find` 缺失返回 `null`，`require` 缺失 fail-fast 并报告完整 lookup key。
  - 查询结果必须同时返回 `offset` 和 `meta`；只有 offset 没有 meta 不能作为字段访问材料。
  - `ExtensionBuiltinClass.members[]` 只提供语言层 property surface 和类型名；`builtin_class_member_offsets[]`
    只提供 ABI layout。两者必须能交叉校验同一 member 名，但不能互相替代。
- `ExtensionBuiltinClass`
  - 扩展字段保存 `has_destructor`、nullable `indexing_return_type`。
  - 全量 builtin generator 只读取 Java 模型，不绕过 `ExtensionApiLoader` 直接读 JSON。
- `ExtensionGdClass.PropertyInfo`
  - 扩展字段保存 Godot JSON 的 `getter`、`setter`、`index`。
  - `getGetterFunc()` 返回 raw `getter`，`getSetterFunc()` 返回 raw `setter`。
  - `isReadable` 应由 `getter != null` 或显式 `is_readable` 字段推导；`isWritable` 应由 `setter != null` 或显式 `is_writable` 字段推导。
  - `index` 使用 nullable 数值承载，因为 `index = 0` 是有效 indexed property，不可用 `0` 表示缺失。
- engine property wrapper generator
  - 生成 getter wrapper 前必须确认 property 有 `getter`，并能在同一 owner class 或继承链中找到对应 `ClassMethod` hash。
  - 生成 setter wrapper 前必须确认 property 有 `setter`，并能找到对应 `ClassMethod` hash。
  - 若 property 有 `index`，实现调用 setter/getter method 时必须传入固定 index 参数；若 wrapper 内联该固定参数，
    canonical symbol 必须包含该 index 作为调用材料。
  - 若 getter/setter method metadata 缺失、hash 为 0、参数形态和 property `index` 不匹配，必须 fail-fast。

前置验收：

- `ExtensionApiLoader.loadVersion(GodotVersion.V451).builtinClassSizes()` 非空，且包含 `float_32`、`float_64`、`double_32`、`double_64`。
- builtin size metadata 至少能查到 `Variant`、`String`、`Vector2`、`Array`、`Dictionary` 的 size。
- `ExtensionApiLoader.loadVersion(GodotVersion.V451).builtinClassMemberOffsets()` 非空，且能查到典型 builtin member，例如 `Vector2.x` / `Vector3.z` 的 offset 与 meta。
- member offset/meta 验收必须覆盖至少两个 build configuration，并验证差异确实保留：
  - `Vector3.z` 在 `float_32` 或 `float_64` 下是 `offset=8, meta=float`。
  - `Vector3.z` 在 `double_32` 或 `double_64` 下是 `offset=16, meta=double`。
  - `Color.r` 在 `double_64` 下仍是 `offset=0, meta=float`。
- 以上 build configuration 差异只约束 `ExtensionAPI` metadata 读取完整性；阶段 1A 之后的 C ABI support
  只消费 `float_64`，不为 `double_*` 或 `*_32` 生成可用分支。
- builtin member-backed property 的现有语义不能因为 layout metadata 进入模型而改变：
  `Vector3.x`、`Color.r` 仍通过 `ExtensionBuiltinClass.members -> synthetic PropertyInfo` 进入 shared property route；
  `builtin_class_member_offsets` 只参与 ABI/layout 生成与校验。
- builtin constructor metadata 保留 index，例如 `String`、`Array`、`Dictionary` 的 constructor index 不丢失。
- builtin class metadata 保留 `has_destructor` 和 nullable `indexing_return_type`。
- builtin/utility/engine method metadata 在 lookup metadata 中保留非零 hash；exact engine method bind 和 wrapper generator 不再接受 hash 缺失的可调用 metadata。
- engine property metadata 保留 `getter`、`setter` 和 nullable `index`：
  - 普通属性可查到 getter/setter，例如 `Window.title` 或 `AnimatedSprite2D.animation`。
  - indexed property 可查到同名 getter/setter 和不同 index，例如 `Window` 的 flag property family。
  - getter-only property 保持 writable=false，不生成 setter wrapper。
- `ExtensionGdClass.PropertyInfo.getGetterFunc()` / `getSetterFunc()` 对 engine JSON property 返回 raw Godot method name，而不是 `null`。

Godot metadata 对齐规则：

wrapper 身份与 lookup metadata 必须分层：

- `GodotBindingSymbol` 表示生成 C wrapper 的结构性身份，只包含会改变 C 函数签名、命名语义或 wrapper 调用形状的字段。
- `hash` 是 Godot API dump 输出的签名查找值，`hash_compatibility` 是升级兼容元数据；二者都不是 wrapper 身份。
- `gdextension_interface.cpp` 中 builtin / utility / class method lookup 使用 `p_hash` 匹配 Godot callable signature；`extension_api_dump.cpp`
  输出的 compatibility hash 用于验证和兼容查找策略，不应参与 GDCC wrapper 去重 key。
- 对同一个 `GodotBindingSymbol`，hash 变化只能作为 lookup metadata 变化处理：
  - engine class method 和 builtin method 能通过 Godot `hash_compatibility` 规则证明兼容时，保留一个 wrapper symbol，
    并记录可用于 lookup 的 hash 集合。
  - utility function 没有 runtime compatibility fallback，也没有 `hash_compatibility` 合并规则；只有相同 hash 能合并。
  - 不能证明兼容时 fail-fast。
  - 不允许通过把 hash 放进 canonical key 生成两个 C wrapper。

- interface wrapper：从 `gdextension_interface.h` 的 `@name` 段落取得 wrapper lookup name，从紧随 typedef
  解析函数签名。因此 wrapper lookup 名必须是 header 注释中的原始 interface name，不能由 typedef 名简单变换。
- builtin constructor：按“Godot builtin wrapper 生成 contract”过滤后全量预生成，从 extension API 的
  `builtin_classes[].constructors[].index/arguments` 解析；实现使用 `godot_variant_get_ptr_constructor(type, index)`。
  单 `String` 参数 constructor 还必须生成 char / UTF convenience wrapper。
- builtin destructor：全量预生成，从 builtin `has_destructor` / type metadata 判定；实现使用 `godot_variant_get_ptr_destructor(type)`。
- Variant pack/unpack：全量预生成，从 `GdType.getGdExtensionType()` 到 `GDExtensionVariantType` 的映射解析；实现使用
  `godot_get_variant_from_type_constructor(type)` / `godot_get_variant_to_type_constructor(type)`。
- builtin method：按 member 冲突过滤规则全量预生成，从 `builtin_classes[].methods[].arguments/return_type/is_vararg/is_static`
  解析 wrapper 身份，`hash` 作为 lookup metadata；实现使用 `godot_variant_get_ptr_builtin_method(type, method_name, hash)`。
- builtin property member：全量预生成，从 `builtin_classes[].members[]` 解析；实现使用
  `godot_variant_get_ptr_getter(type, member)` / `godot_variant_get_ptr_setter(type, member)`。
- builtin index/key accessor：全量或固定预生成，从 builtin class、element/key/value ABI 和 `indexing_return_type` / `is_keyed` 解析；实现使用
  `godot_variant_get_ptr_indexed_getter/setter` 和 `godot_variant_get_ptr_keyed_getter/setter`。
- operator helper：按 atomic/non-struct 过滤规则全量预生成，从 `builtin_classes[].operators[]` 或现有 `OperatorResolver`
  的 operator/type/return 决策解析；实现使用 `godot_variant_get_ptr_operator_evaluator(op, left_type, right_type)`。
- utility function：全量预生成，从 `utility_functions[].arguments/return_type/is_vararg` 解析 wrapper 身份，`hash` 作为 lookup metadata；实现使用
  `godot_variant_get_ptr_utility_function(name, hash)`.
- engine class method：exact route 不生成 public wrapper；非 exact route 根据 `classes[].methods[].arguments/return_value/is_vararg/is_static`
  生成 public wrapper，`hash` / `hash_compatibility` 只作为 method bind lookup metadata。
- engine property：从 `classes[].properties[].getter/setter/type/index` 解析访问方式，再用对应 method metadata 的
  hash / compatibility hash 做 method bind lookup；property helper 可以按 property name 暴露 C 函数，但不得从 property name 猜 accessor method。
- singleton：从 `singletons[]` 解析 name/type；实现使用全量 interface wrapper `godot_global_get_singleton(...)`。
- class constants：从 `classes[].constants[]` / `classes[].enums[]` 解析 literal value；可生成 inline constant helper，或在 Java 侧直接 literal 化，但同一策略必须统一。

runtime lookup failure 规则：

- Godot 的 lookup 失败语义必须作为 generated wrapper 硬约束，而不是只作为测试说明：
  - Godot 4.5.1 `gdextension_variant_get_ptr_builtin_method(...)` 在 builtin method hash 不匹配时打印错误并返回 `nullptr`。
    新版本即使通过 builtin compatibility 表尝试回退，未命中时仍返回 `nullptr`。
  - `gdextension_variant_get_ptr_utility_function(...)` 只做 utility hash 严格相等检查；hash 不匹配返回 `nullptr`，
    没有 compatibility fallback。
  - `gdextension_classdb_get_method_bind(...)` 先查当前 method hash，再查 ClassDB compatibility method，并在非
    `DISABLE_DEPRECATED` Godot 中应用 `GDExtensionSpecialCompatHashes` 历史坏 hash 修正；仍失败时返回 `nullptr`。
- 所有 generated lookup wrapper 都必须在 lookup 返回 `NULL` / `nullptr` 时立即停止当前 lookup 路径，不能把 `NULL`
  缓存为“已解析”状态，也不能继续调用函数指针。
- lookup miss 必须通过一个统一的 C helper 或宏实现，例如 `gdcc_binding_lookup_fail(...)` / `GDCC_BINDING_LOOKUP_FAIL(...)`：
  - 诊断信息至少包含 wrapper kind、C function name、Godot lookup name、owner/type、primary hash、candidate compatibility hashes。
  - `gdcc_binding_lookup_fail(...)` 必须返回 `GDExtensionBool`，lookup miss 返回 `false`；调用者需要传播失败时检查返回值，
    不需要传播时可以忽略返回值。
  - context 中的 `suppress_internal_error` 为 false/零值时，helper 默认通过 Godot `print_error*` 或 `stderr` 报错；
    调用者需要自己汇总诊断时才显式置 true。
  - helper 永远不能 `abort()`、`__builtin_trap()` 或等价地终止 Godot/editor 进程；调用方必须显式处理 `false`，
    避免继续执行并触发空函数指针崩溃。
- generated lookup 只能发布非空函数指针或 method bind：
  - interface wrapper 在 `godot_initialize_interface(...)` 中 eagerly 调用 `get_proc_address("@name")`；
    任一 required interface entry 返回空时报告 lookup miss、清空已解析 pointer table 并返回 `false`，运行期 wrapper
    不再执行 lazy lookup 或写入 cache。
  - builtin method wrapper 的 `godot_variant_get_ptr_builtin_method(type, method, hash)` 返回空时调用 lookup failure helper；
    如果目标 Godot metadata 提供 builtin `hash_compatibility`，生成器可以按候选 hash 顺序尝试，但最后仍必须停止当前 lookup 路径。
  - utility wrapper 的 `godot_variant_get_ptr_utility_function(name, hash)` 返回空时调用 lookup failure helper；不允许尝试无 hash 调用或猜测兼容 hash。
  - exact engine method bind accessor 必须按 primary hash、metadata `hash_compatibility` 去重顺序调用
    `godot_classdb_get_method_bind(...)`，全部失败后调用 lookup failure helper；调用 helper 不再用默认返回值掩盖 bind lookup miss。
  - module-local engine property / public method wrapper 使用 method bind 时，必须复用同一候选 hash 与 lookup failure 规则。
- `hash_compatibility` 的职责是让 lookup 候选集合覆盖 Godot 的兼容修正逻辑；它仍然不能进入 canonical symbol。
  对 class method，`ExtensionAPI.classes[].methods[].hash_compatibility` 已包含 Godot `ClassDB::get_method_compatibility_hashes(...)`
  和非 `DISABLE_DEPRECATED` 构建下 `GDExtensionSpecialCompatHashes::get_legacy_hashes(...)` 输出的历史 hash；
  GDCC 不再解析 `gdextension_special_compat_hashes.cpp` 来维护第二套修正规则。

## 需要修改的主要位置

### C 资源

- 删除或停止发布：
  - `src/main/c/codegen/include_451/gdextension-lite.zip`
- 新增：
  - `src/main/c/codegen/include_451/godot/**`
- 修改：
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
  - `src/main/c/codegen/include_451/gdcc/gdcc_call.h`
  - `src/main/c/codegen/include_451/gdcc/gdcc_bind.h`
  - `src/main/c/codegen/include_451/gdcc/gdcc_intrinsic.h`
  - `src/main/c/codegen/include_451/gdcc/gdcc_string.h`
  - `src/main/c/codegen/include_451/gdcc/gdcc_string_name.h`

这些 `gdcc/*.h` 应从 `<gdextension-lite.h>` 改为包含 `<godot_abi.h>`、`<godot_interface.h>` 和需要的具体 binding header。
`CProjectBuilder` 会把 `<includeRoot>/godot` 加入 include dirs，因此模板和 helper 中不使用 `<godot/...>` 前缀。
该 include 替换必须晚于 fixed runtime wrapper 生成，并且和 `CProjectBuilder` 的 include/build 输入切换在同一阶段落地；
否则会先失去 `<gdextension-lite.h>` / `<implementation-macros.h>` 或失去非 interface `godot_*` wrapper 的定义。

### 模板

- `src/main/c/codegen/template_451/entry.h.ftl`
  - `#include <gdextension/gdextension_interface.h>` 改为新 ABI include。
  - `#include <gdextension-lite.h>` 改为 `#include <godot_binding.h>` 或按需包含 `godot_abi.h` / `godot_interface.h`。
  - 保持 `#include <gdcc_helper.h>` 和 `#include "engine_method_binds.h"`。
  - 阶段 4 后，module-local wrapper 仍由 `"engine_method_binds.h"` 承载，不额外 include
    `godot_module_bindings.h`。
- `src/main/c/codegen/template_451/entry.c.ftl`
  - 删除 `#include <implementation-macros.h>`。
  - `gdextension_lite_initialize(p_get_proc_address);` 改为 `if (!godot_initialize_interface(p_get_proc_address)) { return false; }`。
  - 模板中直接使用的 `godot_classdb_*`、`godot_object_*` 等 interface wrapper 必须来自全量 `godot_interface.h/.c`。
  - 模板中直接使用的 `godot_new_Variant_with_String` 来自全量 builtin wrapper，`godot_print` 来自全量 utility wrapper；
    `godot_Object_notification` 来自 fixed source-list runtime binding。
- `src/main/c/codegen/template_451/engine_method_binds.h.ftl`
  - 保持 exact engine helper 语义。
  - 确认 `godot_classdb_get_method_bind`、`godot_object_method_bind_call`、`godot_object_method_bind_ptrcall` 来自新 interface wrapper。

### Java 生成器

- `ExtensionApiLoader` / `ExtensionAPI` / `ExtensionGdClass`
  - 在任何 binding generator 之前补齐 `builtin_class_sizes`、`builtin_class_member_offsets`、
    顶层 `global_constants[]`、builtin `has_destructor/indexing_return_type`、engine property `getter/setter/index`
    的加载和模型字段。
  - `builtin_class_sizes` 和 `builtin_class_member_offsets` 共同构成 builtin layout metadata；它们是
    `godot_builtin_sizes.h`、`godot_builtin_layout.h`、手写/生成 builtin type 声明、全量 builtin wrapper 和未来字段级优化的事实来源。
  - `global_constants[]` 与 `global_enums[]` 共同构成静态全局常量事实源；`godot_global_constants.h`
    和 `godot_global_enums.h` 只能读取 Java 模型，不能在 generator 中重新扫描 JSON。
  - engine property `getter/setter/index` 是 engine property wrapper 的事实来源；
    不允许 generator 自己绕过 Java 模型重新读 JSON。
  - 这不是无害字段扩展：`ExtensionGdClass.PropertyInfo` 的 readable/writable、getter/setter/index 会影响
    `ScopePropertyResolver`、`PropertyDefAccessSupport`、`LoadPropertyInsnGen`、`StorePropertyInsnGen` 的 resolved/fallback/direct 行为。
  - 对缺失 size/offset/hash/getter/setter/index 这类 generator 必需字段的情况 fail-fast。
- `CProjectBuilder`
  - include dir 从 `<includeRoot>/gdextension-lite` 改为 `<includeRoot>/godot`。
  - 静态 runtime 编译输入从 `gdextension-lite/gdextension-lite-one.c` 改为 `<includeRoot>/godot/godot_binding.c`。
  - 编译输入必须是显式允许集合：本轮 `GeneratedFile` 产生的 `.c` 加上固定 runtime
    `<includeRoot>/godot/godot_binding.c`。不能再根据 `<includeRoot>/gdextension-lite/gdextension-lite-one.c`
    是否存在来决定输入。
  - `godot_binding.c` 缺失应作为构建准备错误 fail-fast；不能像旧 vendor 一样“存在才追加、缺失就跳过”。
  - 不能依赖 `ResourceExtractor` 清理旧文件。若阶段 3 仍暂时保留 vendor zip 资源，`CProjectBuilder` 必须改为显式抽取
    `gdcc/**` 与 `godot/**` 等非 vendor 资源，或在同阶段删除 vendor zip；不得继续盲目抽取整个 `include_451`
    后再用存在性扫描捞编译输入。
  - 继续把本轮 `CCodegen.generate()` 返回的所有 generated `.c` 加入 `cFiles`；阶段 4 不新增 module-local
    generated `.c`，因此这里仍只收集 `entry.c`。
  - 注释和测试断言中不再提 `gdextension-lite` amalgamation。
  - 该切换只能在 `godot_binding.c` 已聚合 `godot_interface.c` 和 `godot_fixed_binding.c` 后执行；
    不能早于 fixed runtime wrapper 阶段。
- `CCodegen`
  - 继续生成 `entry.c`、`engine_method_binds.h`、`entry.h`。
  - 当 module-local usage snapshot 非空时，把 module-local wrapper section 渲染进 `engine_method_binds.h`；
    不新增 generated file。
  - 并列管理 `EngineMethodUsageSession` 与 `GodotBindingUsageSession`。
  - 构造 `GodotBindingUsageSession` 时加载 interface/builtin/utility/fixed provided set，把预生成和固定 wrapper 作为 runtime-provided symbol 注入去重表。
  - `GenerateRenderFacade` 或 `CBodyBuilder` 需要同时持有 engine method buffer 与 Godot binding buffer；仍保持函数渲染成功后再 commit。
- `GodotInterfaceGenerator`
  - 从 resource 中的 `godot/gdextension/gdextension_interface.h` 解析 `@name` / typedef 配对，生成全量
    `godot_interface.h/.c`。
  - ABI signature 必须以 typedef 原文为准，保留 `GDExtensionUninitializedVariantPtr`、
    `GDExtensionUninitializedStringPtr`、`GDExtensionUninitializedStringNamePtr` 等 uninitialized destination 参数。
  - 这是固定 ABI support 的维护工具，不纳入 per-module 使用集裁剪。
  - 若后续需要自动化刷新 Godot 版本，先保持为显式调用的生成器/测试工具，不为本迁移修改 Gradle build script。
- `GodotBuiltinGenerator`
  - 从 `ExtensionAPI.builtin_classes[]` 全量生成 `godot_builtin.h/.c`。
  - 覆盖 GDCC builtin wrapper contract 允许的 constructors、destructors、pack/unpack、methods、members、operators、
    indexed/keyed helper、typed container helper 和合成 compatibility helper。
  - constructor、copy constructor、`Variant <-> Type` conversion、`String` / `StringName` convenience wrapper 必须按
    uninitialized destination ABI 生成：本地结果先是 raw storage，Godot constructor 成功后才成为 initialized carrier。
  - 生成 member getter/setter 或任何直接读取/写入 builtin field 的 helper 时，必须读取
    `ExtensionBuiltinClassMemberOffsets` 的 `(buildConfiguration, class, member) -> offset/meta`；不能只依据
    `ExtensionBuiltinClass.members[]` 的类型名或手写 struct 顺序。
  - `ExtensionBuiltinClass.members[]` 仍负责语言层 property surface；layout-sensitive C 代码只接受 offset/meta 查询结果。
  - constructor/operator 过滤、单 `String` 参数便利构造器、`Variant` conversion helper、member/method 冲突过滤、
    C 函数名冲突 fail-fast 都应在 generator 内部集中实现或用紧邻私有 helper 表达；不要把这些规则拆散到调用点。
  - 这是版本级预生成器，不读取 module usage session。
- `GodotUtilityGenerator`
  - 从 `ExtensionAPI.utility_functions[]` 全量生成 `godot_utility.h/.c`。
  - 这是版本级预生成器，不读取 module usage session。
- `FixedGodotBindings` / `Godot451FixedBindings`
  - 维护固定 helper/template 需要但不属于 interface/builtin/utility 全量集合的 wrapper 清单。
- `GdccHelperBindingScanner`
  - 扫描固定 `gdcc/*.h` 与固定模板，只做对账和 fail-fast。
  - 不生成或更新 fixed 清单，不直接写 C wrapper。
- `GodotBindingSymbolHelper`
  - 可读写 generated snapshot，负责 canonical key 去重和 C function name 冲突检测。
- `GodotBindingTool`
  - 提供 `public static void main(String[] args)`。
  - 支持 `generate-interface`、`generate-abi-support`、`generate-builtin`、`generate-utility`、
    `generate-fixed`、`check-fixed`、`dump-fixed-manifest` 子命令。
  - 可通过 jar/classpath 手动调用，不要求新增 Gradle task。
- `CGenHelper`
  - `renderPackFunctionName`、`renderUnpackFunctionName`、`renderCopyAssignFunctionName`、`renderDestroyFunctionName` 等继续只返回 `godot_*` 符号。
  - 不在 `CGenHelper` 内持有 `GodotBindingUsageSession`；这些 builtin/utility 名称由全量预生成集合覆盖。
  - `resolveUtilityCall` 继续接受 `foo` / `godot_foo`，`cFunctionName` 仍是 `godot_<name>`，wrapper 由全量 utility 生成。
- `CBuiltinBuilder`
  - 构造器元数据校验继续使用 `ExtensionBuiltinClass`。
  - 生成构造器时不再登记 constructor symbol；typed Array/Dictionary constructor helper 来自全量 builtin 或 fixed source-list。
- `CallGlobalInsnGen`、`CallMethodInsnGen`、`ConstructInsnGen`、`IndexLoadInsnGen`、`IndexStoreInsnGen`、`LoadPropertyInsnGen`、`StorePropertyInsnGen`、`NewDataInsnGen`、`OperatorInsnGen`
  - 尽量不改 emit 的 C 符号文本。
  - builtin/utility 符号不登记生成；只有经过 provided set 过滤后的具体 engine public wrapper、
    engine property helper、singleton getter、class constant helper 需要登记。
- `BackendMethodCallResolver`
  - exact engine route 保持不回退 wrapper。
  - builtin route 使用全量 builtin provided wrapper，不登记生成。
  - object/variant dynamic fallback 中的固定 wrapper 来自 fixed source-list；只有落到 engine public wrapper 的残余路径才登记 `GodotBindingSymbol`。

## 分阶段实施

### 阶段 0：ExtensionAPI metadata 与 engine property 语义重构

阶段 0 不生成 C runtime 文件，也不切换模板 include / native input；它的目标是把现有 property 解析、可写性诊断、
backend direct/fallback 分支和后续 wrapper generator 的输入语义整理到同一套 Java 模型中。这个阶段必须按语义重构管理，
每个子阶段都要保持当前编译测试可定位失败原因。

0A：metadata 模型和 loader 结构补全。

状态（2026-05-19）：Done。

- `ExtensionApiLoader` 已把 `builtin_class_sizes`、`builtin_class_member_offsets`、`global_constants[]`、
  builtin `has_destructor` / `indexing_return_type` 和 engine property raw `getter/setter/index` 接入 `ExtensionAPI`
  Java 模型；`global_constants[]` 在 4.5.1 为空时保留稳定空集合，非空 fixture 使用 `long` 保存 value。
- global enum、builtin enum 与 engine class enum 的 `values[].value` 使用 `long` 保存 Godot int64 metadata；
  脚本可见类型仍归一化为 `int`，但 loader / 后端 literal 输出不得按 Java `int` 截断。
- engine `PropertyInfo.getGetterFunc()` / `getSetterFunc()` 现在返回 raw Godot method name；`index = 0`
  作为有效 indexed property 值保留为 `Integer 0`，缺失时为 `null`。
- 定向测试已覆盖 4 个 builtin ABI build configuration、`Vector3.z` / `Color.r` offset/meta 差异、
  `String` / `Array` / `Dictionary` constructor index、global constant 大数值、普通 / indexed / getter-only engine property
  以及显式 readable/writable 覆盖规则；补充回归覆盖 Godot 4.5.1 `RenderingServer.ArrayFormat` 的 int64 enum value。

0A.1：global constants 语义边界补齐。

状态（2026-05-20）：Done。

- `ClassRegistry` 已把 `ExtensionAPI.globalConstants()` 接入全局 value namespace，暴露为不可写 `CONSTANT`，
  类型为脚本 `int`，declaration 保留 `ExtensionGlobalConstant`，并明确不进入 type-meta namespace。
- 顶层 global constant 按 Godot `@GlobalScope.<NAME>` 语义进入 backend static-load 边界：
  `LoadStaticInsn("@GlobalScope", name)` 只读取 `ClassRegistry.findGlobalConstant(...)`，不重新扫描 JSON。
- `LiteralIntInsn` / `IntOperand` 的数值载体改为 `long`，裸全局常量在 frontend lowering 中直接物化为
  int64 宽度的 `literal_int`，后端输出十进制 `godot_int` literal。
- 回归测试覆盖 registry 正向可见性、type namespace 负向隔离、frontend 绑定/lowering、`@GlobalScope`
  static-load 正负路径、builder 物化和 LIR parser / C backend int64 literal 输出。

- `ExtensionApiLoader.loadFromResource(...)`
  - 从 `builtin_class_sizes` 解析 `List<ExtensionBuiltinClassSizes>`。
  - 从 `builtin_class_member_offsets` 解析 `List<ExtensionBuiltinClassMemberOffsets>`。
  - 对 size 与 member offset/meta 都保留 4 个 Godot build configuration，不得只保留当前开发机或当前 target 的一组值。
  - 对 member offset/meta 保留 `member`、`offset`、`meta` 原始字段；缺失 `meta` 与缺失 `offset` 一样是 layout metadata 不完整。
  - 解析 builtin `has_destructor`、nullable `indexing_return_type`。
  - 继续解析并回归测试 builtin constructor index，以及作为 lookup metadata 使用的 builtin method hash、utility function hash、engine method hash。
  - 同步解析顶层 `global_constants[]` 并传入 `ExtensionAPI.globalConstants()`；当前 4.5.1 数组为空也必须保留这个空集合，
    因为 `godot_global_constants.h`、scope global constant resolver 和后续 `LoadStaticInsnGen` 都要以 Java 模型为事实源。
  - 非空 `global_constants[]` fixture 必须验证 `name/value/is_bitfield` 保留，`value` 不按 Java `int` 截断。
- `ClassRegistry`
  - 全局常量是 value namespace 符号，不是 class-like / enum type-meta head。
  - `findType(...)` 不能把全局常量名猜成 `GdObjectType`；`resolveTypeMeta(...)` 对全局常量名保持未命中。
- `LoadStaticInsnGen`
  - `@GlobalScope` 是 backend IR 中顶层 global constant 的显式 receiver。
  - 该路径只接受 `GdIntType.INT` 兼容目标，并用 `ExtensionGlobalConstant.value()` 的 `long` 值输出 decimal literal。
- `ExtensionGdClass.PropertyInfo`
  - 新增 raw `getter`、`setter`、nullable `index` 字段。
  - `getGetterFunc()` / `getSetterFunc()` 返回 raw Godot method name。
  - loader 对 Godot JSON property 使用 `getter != null` 推导 readable、`setter != null` 推导 writable；显式
    `is_readable` / `is_writable` 字段存在时按显式字段覆盖。
  - 测试 fixture 中手写 `new ExtensionGdClass.PropertyInfo(...)` 的调用点必须同步更新，不能让旧构造器默认值掩盖 raw accessor 缺失。

0A.2：builtin layout metadata 集中查询 API。

状态（2026-05-22）：Done。

- `ExtensionAPI` 已提供 builtin layout metadata 的统一查询入口：
  `findBuiltinClassSize(...)`、`requireBuiltinClassSize(...)`、
  `findBuiltinClassMemberLayout(...)`、`requireBuiltinClassMemberLayout(...)`。
- 查询 key 显式保留 Godot `build_configuration` 维度；缺失 build configuration、builtin class 或 member 时，
  `find` 返回 `null`，`require` fail-fast，并在异常信息中保留完整 lookup key。
- member layout 查询结果同时携带 `offset` 与 `meta`，避免后续 generator 只复制 offset 查询后丢掉 ABI meta。
- 该 API 只服务 ABI/layout-sensitive generator 和校验；builtin property surface 仍来自
  `ExtensionBuiltinClass.members -> synthetic PropertyInfo`，不能从 `builtin_class_member_offsets` 合成属性。
- `ExtensionApiLoaderMetadataTest` 已改用集中查询 API 验证 size 与 member layout，并补充缺失 build configuration、
  缺失 class、缺失 member 和空 metadata 的负向测试。

0B：shared property semantic normalization。

状态（2026-05-19）：Done。

- `ScopePropertyResolver` 继续保持 shared resolver 边界：known hierarchy resolved / missing property failure /
  metadata unknown fallback 不变；builtin property surface 仍只消费 `ExtensionBuiltinClass.members -> synthetic PropertyInfo`。
- `PropertyDefAccessSupport` 已有直接测试锁住 engine/builtin/GDCC 三类属性可写性：raw getter/setter 存在不能覆盖
  normalized `isWritable=false`，GDCC 属性仍保持保守可写。
- 回归测试补齐 layout-only `builtin_class_member_offsets` 不会合成 builtin property、engine raw getter/setter 不能覆盖显式
  unreadable / non-writable flag，以及 frontend assignment、scope publication、load/store backend 分支的现有行为。

- `ScopePropertyResolver` 继续决定 resolved property、metadata unknown fallback 和 missing property failure；阶段 0 不改变
  `MetadataUnknown -> godot_Object_get/set` 的 fallback 边界。
- `PropertyDefAccessSupport.isDirectlyWritable(...)` 必须基于 normalize 后的 engine property writability；
  getter-only property 应在 frontend 赋值诊断中保持只读。
- builtin property surface 仍来自 `ExtensionBuiltinClass.members -> synthetic PropertyInfo`，不把
  `builtin_class_member_offsets` 当成 builtin property 列表。
- 阶段 0 不把 offset/meta 接入 `ScopePropertyResolver` 或现有 `LoadPropertyInsnGen` / `StorePropertyInsnGen`
  的普通 member-backed property 决策；它只把 layout metadata 准备成后续 ABI/layout-sensitive generator 的事实来源。

0C：engine property accessor resolver。

状态（2026-05-19）：Done。

- `BackendPropertyAccessResolver` 新增 exact-engine property accessor resolver；read/write helper 从 raw
  getter/setter method name 出发，返回 property owner、method owner、method metadata、nullable fixed index、
  property type、method bind hash / compatibility hash、normalized 参数材料和后续 wrapper helper 名称。
- indexed property 的 `index = 0` 作为有效 `Integer 0` 保留并进入 resolver 输出；resolver 不再允许从 property name
  反推 accessor method。
- fail-fast 测试已覆盖 raw accessor method metadata 缺失、setter 缺失、method-bind hash 为 0、indexed 参数形态不匹配、
  getter return type 与 property type 不匹配。

- 新增或扩展查询 helper：给定 owner class、property、read/write，返回 raw getter/setter method metadata、nullable index、
  property type、lookup hash/compatibility hash 和最终 wrapper 调用材料。
- 普通 property 示例应覆盖 `Node.name -> get_name/set_name`；indexed property 示例应覆盖 `Window.unresizable -> get_flag/set_flag, index=0`。
- `index = 0` 是有效 fixed index；resolver 不得用 `0` 表示缺失。
- getter/setter method metadata 缺失、hash 为 0、参数形态和 indexed property 不匹配时 fail-fast。
- `index` 不单独定义 property 身份；若生成的 property helper 把 index 内联为固定调用参数，它必须进入 wrapper key 和生成材料。

0D：Load/Store Property consumer 接入 raw engine property accessor resolver。

状态（2026-05-19）：Done。

- 文档方案：Done。`LoadPropertyInsnGen` / `StorePropertyInsnGen` 的 ENGINE owner 分支必须直接消费 0C
  `EnginePropertyAccessor` 输出，不再从 property name 拼 `godot_<Owner>_get/set_<property>`。
- 生产代码接线：Done。ENGINE load/store 调用使用 raw getter/setter 对应的 exact engine helper name，并把
  accessor 转成 `BackendMethodCallResolver.ResolvedMethodCall` 记录到 `EngineMethodUsageSession`，确保
  `engine_method_binds.h` 同步生成 helper/bind lookup。
- 单元测试：Done。新增 consumer 级测试覆盖 raw accessor name 与 property name 不一致、indexed `index = 0`
  固定参数传递、缺失 raw accessor metadata fail-fast，并补齐 exact engine usage session 与
  `engine_method_binds.h` 生成回归。
- 验证：Done。已运行
  `script/run-gradle-targeted-tests.sh --tests BackendPropertyAccessResolverTest,CLoadPropertyInsnGenTest,CStorePropertyInsnGenTest,CCodegenEngineMethodUsageSessionTest,CCodegenEngineMethodBindHeaderTest`
  和 `script/run-gradle-targeted-tests.sh --tests LoadStorePropertyInsnGenEngineInheritanceTest`，两组均通过。

- `LoadPropertyInsnGen`：
  - resolved ENGINE property 调用 `resolveEnginePropertyReadAccessor(...)`。
  - 调用参数顺序为 `receiver` 或 `receiver, fixedIndex`；`index = 0` 必须作为有效固定实参发出。
  - result assignment 继续走 `CBodyBuilder.callAssign(...)`，保持现有返回值/生命周期路径。
- `StorePropertyInsnGen`：
  - resolved ENGINE property 调用 `resolveEnginePropertyWriteAccessor(...)`。
  - 调用参数顺序为 `receiver, value` 或 `receiver, fixedIndex, value`。
  - value 仍来自 `bodyBuilder.valueOfVar(valueVar)`，不在生成器里手写生命周期逻辑。
- `EnginePropertyAccessor` 到 exact engine helper usage 的转换必须使用 method owner 作为 helper owner；
  property owner 只保留为 property 语义和错误上下文，不参与 `EngineMethodSymbolKey` 身份。
- 本阶段不能改变 unknown object runtime fallback、GDCC getter/setter-self fast path、builtin member-backed property、
  以及 `LOAD_PROPERTY` / `STORE_PROPERTY` 的类型检查方向。

0E：现有 fallback 与迁移边界回归。

状态（2026-05-19）：Done。

- `LoadPropertyInsnGen` / `StorePropertyInsnGen` 的非 exact-engine 边界保持不变：unknown object runtime fallback、
  GDCC getter/setter-self fast path、builtin member-backed property 都由现有测试继续保护。
- 0E 新增入口 fail-fast 回归：缺失 object/result/value variable 和 `Nil` receiver 不会落入 runtime fallback 或 property lookup。
- `BackendPropertyAccessResolverTest` 新增迁移边界样本：当 property name 与 raw accessor name 不一致时，Stage 4
  可消费的 helper/material 只跟随 0C resolver 的 raw getter/setter metadata；同时补齐 indexed setter 参数形态不匹配的
  写侧 fail-fast。
- 旧 ENGINE direct wrapper 的 property-name 拼接路径不再作为长期边界；0D 接线后，consumer 与 Stage 4 module-local
  wrapper 生成都必须以 0C resolver 输出为事实源。

- `LoadPropertyInsnGen` / `StorePropertyInsnGen` 的现有分支必须有测试保护：
  - unknown object 仍走 `godot_Object_get/set`。
  - ENGINE owner 走 exact engine helper 路径；helper lookup 材料必须来自 0C resolver，而不是从 property name 猜 getter/setter。
  - GDCC owner 的 getter-self / setter-self fast path 继续只用于 GDCC property。
  - builtin property 仍走 `godot_<Builtin>_get/set_<member>`。
- 阶段 0 完成后，后续阶段 4 接入 module-local property wrapper 时，只需要复用 0D 的 consumer/usage 材料，不再重新解释 Godot JSON。

验收：

- `ExtensionApiLoaderMetadataTest` 验证 `builtinClassSizes()` 和 `builtinClassMemberOffsets()` 非空，
  并覆盖 4 个 build configuration。
- `ExtensionApiLoaderMetadataTest` 验证 `Variant`、`String`、`Vector2`、`Array`、`Dictionary` size 可查。
- `ExtensionApiLoaderMetadataTest` 验证 `Vector2.x`、`Vector3.z` member offset/meta 可查。
- `ExtensionApiLoaderMetadataTest` 验证 member offset/meta 的 build configuration 差异：
  `Vector3.z` 在 `float_32` 或 `float_64` 下为 `offset=8/meta=float`，在 `double_32` 或 `double_64`
  下为 `offset=16/meta=double`；`Color.r` 在 `double_64` 下仍为 `offset=0/meta=float`。
- 这些差异测试只证明 loader 没有丢弃 Godot 上游 metadata；C backend ABI support 的可编译目标仍只有
  `float_64`。
- `ExtensionApiLoaderMetadataTest` 通过 `ExtensionAPI` 集中查询 API 验证 layout metadata，且覆盖缺失
  build configuration、缺失 class、缺失 member 和空 metadata 的负向查询。
- `ScopePropertyResolverTest` 或 `ClassRegistryTest` 验证 layout metadata 不改变 builtin property surface：
  `Vector3.x`、`Color.r` 仍来自 `ExtensionBuiltinClass.members`，而不是从 `builtin_class_member_offsets` 合成。
- `ExtensionApiLoaderMetadataTest` 验证 `String`、`Array`、`Dictionary` constructor index 不丢失。
- `ExtensionApiLoaderMetadataTest` 验证 `globalConstants()` 字段存在；Godot 4.5.1 当前为空时返回空集合，
  非空 fixture 能保留 `name/value/is_bitfield`，且 `value` 不被截断。
- `ClassRegistryScopeTest` / `FrontendTopBindingAnalyzerTest` / `FrontendLoweringBodyInsnPassTest` /
  `CLoadStaticInsnGenTest` 验证脚本或 IR 引用 global constant 时，走同一 Java 模型和 int64 literal
  materialization，不通过 wrapper 或 JSON 回扫补生成。
- `ExtensionBuiltinClassMetadataTest` 验证 `has_destructor`、nullable `indexing_return_type` 和 `is_keyed` 被加载。
- `ExtensionGdClassPropertyMetadataTest` 验证 ordinary engine property 的 getter/setter/type 保留，例如 `Node.name`。
- `ExtensionGdClassPropertyMetadataTest` 验证 indexed engine property 的 `index = 0` 和其他 index 都能保留，
  不能把 `0` 当作缺失。
- `ExtensionGdClassPropertyMetadataTest` 验证 getter-only property 只读，setter wrapper 不会被生成。
- `ScopePropertyResolverTest`、`PropertyDefAccessSupport` 相关测试验证 metadata unknown、missing property、engine owner、
  builtin member-backed property 和 readonly 诊断不回归。
- `BackendPropertyAccessResolverTest` 验证 engine property accessor resolver 能找到 raw getter/setter 对应的 method metadata、
  nullable index 和 lookup hash；indexed property 参数形态不匹配时 fail-fast。
- `CLoadPropertyInsnGenTest` / `CStorePropertyInsnGenTest` 回归当前 direct/fallback 分支，并新增样本证明
  Stage 4 wrapper 不能从 property name 猜 accessor method。

### 阶段 1A：建立 Godot ABI 声明头

状态同步（2026-05-22）：

- [x] 重新确认 AGENTS、阶段 1A 边界和阶段 0 metadata 前置状态。
- [x] 并行调研相关文档、metadata 模型、C include/template 依赖和测试现状。
- [x] 新增 `GodotBindingTool generate-abi-support`，并确保只消费 `ExtensionAPI` 模型。
- [x] 生成并提交 `include_451/godot` ABI header 集合。
- [x] 补充正反向单元测试和 `<godot_abi.h>` include-only 编译 smoke。
- [x] 运行定向测试并记录验证结果：
  `script/run-gradle-targeted-tests.sh --tests ExtensionApiLoaderMetadataTest,GodotBindingToolAbiSupportTest,GodotAbiHeaderCompileTest,EngineMethodAbiCodecTest,EngineMethodSymbolKeyTest`。
- [x] 根据支持范围收窄 ABI support：`godot_builtin_sizes.h` / `godot_builtin_layout.h` 只输出 `float_64`，
  并在 `REAL_T_IS_DOUBLE` 或 32-bit target 下编译期拒绝。
- [x] 测试迭代记录：新增 `float_64`-only generator 测试时，`ExtensionApiLoader.loadDefault()` 的
  `IOException` 必须显式进入测试方法签名，避免测试编译阶段先于 ABI 行为验证失败。
- [x] `float_64` 收窄后重新运行定向测试并通过：
  `script/run-gradle-targeted-tests.sh --tests ExtensionApiLoaderMetadataTest,GodotBindingToolAbiSupportTest,GodotAbiHeaderCompileTest,EngineMethodAbiCodecTest,EngineMethodSymbolKeyTest`。
- [x] 重新运行 `GodotBindingTool generate-abi-support` 到 `/tmp/gdcc-godot-abi-check`，逐个比对 ABI support header，
  确认 checked-in 输出与当前生成器一致。

阶段 0 验收通过后，新增 `include_451/godot` 目录，只处理可以独立编译验证的 ABI 声明，不切换模板和构建输入。
本阶段交付的是“能被 GDCC helper 和后续 wrapper include 的头文件集合”：

- `godot/gdextension/gdextension_interface.h`
  - 使用 Godot 4.5.1 引擎导出的原始 header，作为 interface function typedef、`GDExtensionVariantType`、
    `GDExtensionCallErrorType`、`GDExtensionInitializationLevel` 等 ABI enum 的唯一来源。
  - 不在 GDCC 中重写这些 ABI enum，避免和 Godot header 漂移。
- `godot/godot_macros.h`
  - 提供 `GDE_EXPORT`、inline、visibility、size assert、opaque typedef 辅助宏。
  - 不继续暴露 `GDEXTENSION_LITE_*` 宏名。
- `godot/godot_global_enums.h`
  - 从 Godot 4.5.1 extension API metadata 生成 `godot_PropertyHint`、`godot_PropertyUsageFlags`、
    `godot_MethodFlags`、`godot_Error` 和其他 global enum value。
  - 覆盖当前模板需要的 `godot_PROPERTY_HINT_*`、`godot_PROPERTY_USAGE_*`、`godot_METHOD_FLAG_*`。
- `godot/godot_global_constants.h`
  - 从 Godot 4.5.1 extension API metadata 顶层 `global_constants[]` 全量生成 standalone global constants。
  - 当前 4.5.1 metadata 为空时也输出稳定空 header；生成器和测试仍必须覆盖非空 fixture，防止后续 Godot
    版本出现 global constants 时被遗漏。
  - 与 `godot_global_enums.h` 共享命名冲突检测；同一 `godot_*` 常量名由 enum value 和 global constant 同时占用时 fail-fast。
- `godot/godot_native_structures.h`
  - 从 Godot metadata 生成 native struct 声明，覆盖 `AudioFrame`、`Glyph`、`ObjectID`、
    physics extension result、script profiling info 等类型。
- `godot/godot_builtin_sizes.h`
  - 从 Godot builtin size metadata 生成 `GDCC_GODOT_SIZE_*` 或等价自有命名。
  - 只生成 `float_64`；32-bit pointer 与 `REAL_T_IS_DOUBLE` 配置不在支持范围内，必须 fail-fast。
- `godot/godot_builtin_layout.h`
  - 从 Godot builtin member offset metadata 生成 `GDCC_GODOT_OFFSET_*`、`GDCC_GODOT_META_*` 或等价自有命名。
  - 只生成与 size 表相同的 `float_64` build configuration。
  - 能表达 `Vector3.z` 这类 scalar member、`Transform2D.origin` 这类复合 member，以及 `Color` 使用 `float`
    meta 的固定布局。
- `godot/godot_builtin_types.h`
  - 手写或生成 builtin struct typedef，统一由 `godot_abi.h` 聚合。
  - 每个 builtin struct 必须用 `godot_builtin_sizes.h` 做 size assert，并用 `godot_builtin_layout.h`
    对所有可直接字段访问的 member 做 `offsetof` / meta assert。
- `godot/godot_abi.h`
  - 聚合以上头文件，作为替代 `<gdextension-lite.h>` 的 ABI 声明入口。
- `GodotBindingTool generate-abi-support`
  - 从 `ExtensionAPI` 模型生成本阶段的 ABI support header，不直接重读 JSON。
  - 重复执行输出稳定；`global_constants[]` 为空时仍更新/保留 `godot_global_constants.h` 的空 header。

验收：

- 一个最小 C translation unit 只 include `<godot_abi.h>` 即可解析：
  - `GDExtensionVariantType`、`GDEXTENSION_VARIANT_TYPE_*`
  - `GDEXTENSION_VARIANT_OP_*`
  - `GDEXTENSION_CALL_ERROR_*`
  - `GDEXTENSION_METHOD_*`
  - `GDEXTENSION_INITIALIZATION_*`
  - `godot_PROPERTY_HINT_*`
  - `godot_PROPERTY_USAGE_*`
  - `godot_METHOD_FLAG_*`
  - `godot_global_constants.h` 中的 standalone global constant 输出；当前 4.5.1 为空时至少验证 header 可 include，
    非空 fixture 验证全量常量可见
  - builtin typedef、native struct、builtin size assert、builtin member offset/meta assert
- 最小 C translation unit 不能只证明 `sizeof` 正确，还必须证明直接字段访问相关 layout 正确：
  - `offsetof(godot_Vector3, z)` 与当前 target configuration 对应的 `GDCC_GODOT_OFFSET_Vector3_z` 一致。
  - `godot_Vector3.z` 的 meta 在 double build 对应 `double`，在 float build 对应 `float`。
  - `godot_Color.r` 在 double build 仍对应 `float` meta。
- `rg -n "GDEXTENSION_LITE_SIZE_|GDEXTENSION_LITE_DECL|definition-macros" src/main/c/codegen/include_451/godot`
  无结果。
- 该阶段不要求 `entry.c.ftl` 或 `CProjectBuilder` 改动，避免把 ABI 声明问题和构建输入切换混在一起。

### 阶段 1B：生成全量 interface wrapper

状态同步（2026-05-23）：

- [x] 重新确认 AGENTS、阶段 1B / 1C 边界，并完成相关文档和代码只读调研。
- [x] 使用 `gpt-5.4-mini` 子代理并行调研 1B / 1C 文档边界、1A generator 现状和 C support/include 形态。
- [x] 新增 `GodotInterfaceGenerator`，从 `gdextension_interface.h` 的 `@name` / 紧随 typedef 配对生成全量
  `godot_interface.h/.c`。
- [x] 扩展 `GodotBindingTool generate-interface` CLI，保持显式调用工具，不接入 Gradle task。
- [x] 生成并写入 `src/main/c/codegen/include_451/godot/godot_interface.h` 与 `godot_interface.c`。
- [x] 补充 interface generator 正反向测试：
  header 全量 `@name` / typedef 配对数量、wrapper name 来源、上游 typedef 拼写异常、`GDExtensionUninitialized*Ptr`
  保留、重复 `@name` 和缺失 function pointer typedef fail-fast。
- [x] 运行定向测试：
  `script/run-gradle-targeted-tests.sh --tests GodotInterfaceGeneratorTest,GodotAbiHeaderCompileTest,GodotBindingToolAbiSupportTest`。
- [x] 重新运行 `generate-interface` 到 `/tmp/gdcc-godot-interface-check`，逐个比对 `godot_interface.h/.c`，
  确认 checked-in 输出与当前生成器一致。

状态同步（2026-05-24）：

- [x] 将公开的 `gdcc_binding_lookup_fail(...)` 从 `kind/name` 两参形式扩宽为 `gdcc_binding_lookup_context`
  上下文结构，提前承载后续 builtin method、utility function、class method bind lookup 需要的
  `function_name`、`lookup_name`、`owner`、`type`、primary hash 和 compatibility hash 候选。
- [x] interface wrapper 继续只填真实存在的 interface lookup 信息：`kind = "interface"`、C wrapper
  function name 和 Godot `@name` lookup name；`owner/type/hash` 用 `NULL`、`has_primary_hash = false`、
  `NULL + count=0` 表达“不适用”，不伪造 owner/type 或把缺失 type 混成 `GDEXTENSION_VARIANT_TYPE_NIL`。
- [x] lookup failure 诊断优先使用 `print_error_with_message` 输出完整上下文，缺失时退回 `print_error`
  和 `stderr`；helper 返回 `false`，调用方必须在需要传播失败的路径上显式停止，避免继续执行空函数指针。
- [x] 补充测试锁定新公共 API、旧两参调用不再出现在生成产物中，并通过 C compile probe 验证富上下文调用可编译、
  旧两参调用会被编译器拒绝。
- [x] 将 interface wrapper 从首次调用 lazy lookup 改为 `godot_initialize_interface(...)` 初始化期 eager resolve：
  入口初始化先清空旧 function pointer，再预取 lookup 失败诊断所需的 print hooks，随后解析 bundled
  `gdextension_interface.h` 中每个 `@name` 对应的 interface pointer。
- [x] 运行期 `godot_*` interface wrapper 不再检查 cache 或调用 `get_proc_address`，只通过已解析静态函数指针转发；
  该策略消除了并发首次调用时的裸静态写入，并最大化高频 interface wrapper 的 hot path 性能。
- [x] 明确支持边界：eager resolve 要求目标 Godot 暴露 bundled 4.5.1 header 中的 interface entry；
  自定义 `DISABLE_DEPRECATED` 或裁剪 interface 表的 Godot 构建不在当前支持范围内。
- [x] 补充 generator 正反测试，锁定初始化期 `GDCC_RESOLVE_INTERFACE(...)` 输出、wrapper hot path
  无 `get_proc_address` / lazy cache 分支，并保留旧 fail helper 签名的 C 编译反向测试。
- [x] 运行定向测试：
  `script/run-gradle-targeted-tests.sh --tests GodotInterfaceGeneratorTest,GodotAbiHeaderCompileTest,GodotBindingToolAbiSupportTest`。
- [x] 重新运行 `generate-interface` 到 `/tmp/gdcc-godot-interface-eager-check`，逐个比对
  `godot_interface.h/.c`，确认 checked-in 输出与当前生成器一致。

状态同步（2026-05-24，lookup failure 不崩溃修正）：

- [x] 将 `gdcc_binding_lookup_fail(...)` 从 `GDCC_NORETURN void` 调整为 `GDExtensionBool` 返回值；
  lookup miss 始终返回 `false`，不再 `abort()` / trap 宿主进程。
- [x] 在 `gdcc_binding_lookup_context` 中新增 `suppress_internal_error`；零值保持默认内部报错，
  需要由调用方自行汇总诊断时才显式关闭 helper 内部报错。
- [x] `godot_initialize_interface(...)` 在 `get_proc_address == NULL` 或任一 interface entry 缺失时通过统一
  `gdcc_binding_lookup_fail(...)` 报告，随后返回 `false`；中途失败会清空已解析 pointer table，避免留下半初始化状态。
- [x] 删除 interface 专用 lookup fail adapter，interface lookup miss 直接填充 `gdcc_binding_lookup_context`
  并调用统一 helper，保证后续 builtin/method-bind/utility lookup 使用同一诊断合同。
- [x] 补充 generator 和 C runtime smoke 测试，锁定 no-`abort`、no-`noreturn`、默认报错、显式 suppression
  不报错，以及初始化期 lookup miss 返回 `false` 并清理已解析指针。

在阶段 1A 的 ABI header 可独立 include 后，实现固定 interface generator。它只处理
Godot 4.5.1 `gdextension_interface.h` 中的 interface function，不接入模块级使用集。该阶段不解析 Godot C++ 源码，
避免把 wrapper 生成绑定到 `gdextension_interface.cpp` 内部实现细节。

新增或完善：

- `GodotInterfaceGenerator`
  - 输入：`src/main/c/codegen/include_451/godot/gdextension/gdextension_interface.h`。
  - 解析 header 中 `@name` 与紧邻 function typedef 的配对，支持 `GDExtensionInterface...` 和 Godot 4.5.1
    `GDExtensionsInterfaceEditorHelpLoadXml...` 这种上游拼写异常。
  - 根据 `@name` 生成 lookup key 和 `godot_<name>` wrapper；typedef 名只用于选择函数指针类型。
  - 生成签名不能把 `GDExtensionUninitialized*Ptr` 参数改写成 initialized pointer 或泛型 `GDExtensionTypePtr`。
  - 输出 `godot_interface.h` 和 `godot_interface.c`。
- `godot_interface.h`
  - 声明 `GDExtensionBool godot_initialize_interface(GDExtensionInterfaceGetProcAddress get_proc_address)`。
  - 声明 `gdcc_binding_lookup_context` 和返回 `GDExtensionBool` 的 `gdcc_binding_lookup_fail(...)`；该公共 helper
    必须能表达 kind、C function name、Godot lookup name、owner/type、primary hash、compatibility hash 候选和内部报错开关。
  - 声明 header 中所有可解析 `@name` / typedef 配对对应的 `godot_<interface_name>(...)` wrapper。
- `godot_interface.c`
  - 保存 `get_proc_address`。
  - 为每个 header interface function 生成同签名 wrapper。
  - `godot_initialize_interface(...)` 内 eagerly 解析每个 `GDExtensionInterface<Name>` function pointer；
    wrapper 内不再做 lazy lookup。
  - `godot_initialize_interface(...)` 只初始化 lookup 层，不注册类、不调用 `gdcc_init()`。
  - interface lookup miss 直接填充上下文后调用统一 lookup failure helper；不要恢复只传
    `kind/name` 的公共签名，也不要重新引入 no-return / abort 行为。

本阶段明确改变之前“只导出 runtime 和模板必须 interface function”的策略：interface wrapper 总是全量导出。
原因是 Godot interface 函数数量可控、集中、轻量，全量发布能降低后续 backend 开发时遗漏 interface function 的维护成本。
这里的“全量”指目标 Godot 版本 header 中可解析的 `@name` / function pointer typedef 配对全量，不是当前模块使用集全量。

验收：

- `GodotInterfaceGeneratorTest` 统计出的 wrapper name 集合等于 `gdextension_interface.h` 中可解析的 `@name` / typedef 配对集合。
- 每个 `@name` 都能在后续文本中找到紧随的 function pointer typedef；找不到时 fail-fast。
- 每个 wrapper name 都来自 `@name`，而不是 typedef 名转换。
- `GDExtensionInterfaceGetProcAddress` 被明确识别为 entry callback typedef，不生成 `godot_get_proc_address`。
- `editor_help_load_xml_from_utf8_chars` 与 `editor_help_load_xml_from_utf8_chars_and_len` 即使 typedef 前缀是
  `GDExtensionsInterface...`，也能按 `@name` 生成正确 wrapper，并原样使用 typedef 名作为函数指针类型。
- `editor_register_get_classes_used_callback`、`register_main_loop_callbacks` 只要在 header 中有标准 `@name` / typedef
  配对，就生成 wrapper，不需要从 Godot C++ 注册表确认。
- deprecated interface 只要仍出现在 header 中就生成 wrapper；不解析 `DISABLE_DEPRECATED` 条件。
- `godot_variant_new_nil`、`godot_string_new_with_utf8_chars`、`godot_string_name_new_with_utf8_chars`、
  `godot_object_method_bind_call` 等样本在生成签名中保留 `GDExtensionUninitialized*Ptr` 参数。
- 固定模板和 helper 中使用的 interface wrapper 都能在全量输出中找到，例如：
  - `godot_mem_alloc`
  - `godot_mem_free`
  - `godot_variant_new_nil`
  - `godot_variant_destroy`
  - `godot_variant_get_ptr_constructor`
  - `godot_variant_get_ptr_destructor`
  - `godot_get_variant_from_type_constructor`
  - `godot_get_variant_to_type_constructor`
  - `godot_variant_get_ptr_builtin_method`
  - `godot_variant_get_ptr_utility_function`
  - `godot_classdb_get_method_bind`
  - `godot_object_method_bind_call`
  - `godot_object_method_bind_ptrcall`
  - `godot_classdb_register_extension_class5`
- `godot_interface.c` 中的 proc address 字符串和 header `@name` 名称一致。
- `gdcc_binding_lookup_fail(...)` 的公共签名使用上下文结构而不是旧 `kind/name` 两参；诊断文本保留
  owner/type/hash 候选槽位，即使 interface wrapper 当前没有这些值；默认内部报错，可通过 context 显式关闭。
- `godot_interface.h/.c` 生成结果重复执行稳定。

### 阶段 1C：准备 binding 聚合入口

状态同步（2026-05-23）：

- [x] 新增薄聚合输出 `godot_binding.h/.c`，当前只聚合 `godot_abi.h`、`godot_interface.h` 和
  `godot_interface.c`。
- [x] 扩展 `GodotBindingTool generate-binding` CLI，保持 1C 聚合入口可重复显式生成。
- [x] 确认本阶段未切换 `entry.c.ftl`、`entry.h.ftl`、`gdcc/*.h` include 或 `CProjectBuilder`
  include/native input。
- [x] 补充并运行 `<godot_binding.h>` / `godot_binding.c` 最小 C 编译 smoke test；该 smoke 只使用
  `src/main/c/codegen/include_451/godot` include root，不触碰阶段 3 的正式 build input 切换。
- [x] 重新运行 `generate-binding` 到 `/tmp/gdcc-godot-interface-check`，逐个比对 `godot_binding.h/.c`，
  确认 checked-in 输出与当前生成器一致。

在 ABI 头和全量 interface wrapper 已可验证后，先建立 `godot_binding.h/.c` 聚合入口，但不切换
`entry.c.ftl`、`entry.h.ftl`、`gdcc/*.h` include，也不修改 `CProjectBuilder` 的 include dir 或 native 编译输入。
这样可以验证新 interface support 本身，而不制造模板仍依赖 fixed wrapper 但 fixed wrapper 尚未生成的中间态。

修改：

- `godot_binding.h`
  - 聚合 `godot_abi.h`、`godot_interface.h`。
  - 后续阶段再聚合 `godot_builtin.h`、`godot_utility.h`、`godot_fixed_binding.h`。
  - 永远不聚合 module-local generated binding header。
- `godot_binding.c`
  - 聚合 `godot_interface.c`。
  - 后续阶段再聚合 builtin/utility/fixed `.c`。
  - 永远不聚合 module-local generated binding `.c`。

验收：

- 一个最小 C translation unit include `<godot_binding.h>` 可以看到 `godot_initialize_interface(...)` 和 interface wrapper 声明。
- `godot_binding.c` 可以和 `godot_interface.c` 聚合编译。
- `godot_binding.h/.c` 的 include 图不出现 `godot_module_bindings.*` 或任何项目级 generated 文件名。
- `entry.c.ftl` 仍保留旧 vendor 初始化和 include，`CProjectBuilder` 仍保留旧 vendor include dir / `gdextension-lite-one.c` 输入；
  本阶段不是切换点。

### 阶段 1D：收口 interface wrapper 符号并内联 hot path

状态同步：

- [x] 将 `godot_interface` wrapper 从 out-of-line default-visible C 函数改为 header-level `static inline`
  转发函数，避免 `godot_variant_*`、`godot_classdb_*`、`godot_mem_*` 等内部 binding wrapper 在默认 shared library
  构建下进入动态符号表。
- [x] 保留单定义的 hidden interface pointer table：每个 `gdcc_interface_*` function pointer 只在
  `godot_interface.c` 中定义一次，并在 `godot_interface.h` 中以 hidden `extern` 形式声明，所有 inline wrapper
  都读取同一份由 `godot_initialize_interface(...)` eager resolve 填充的指针表。
- [x] `godot_initialize_interface(...)`、`gdcc_binding_lookup_fail(...)` 和 lookup message/failure 实现继续作为
  `godot_interface.c` 中的 out-of-line hidden 函数；它们不是 hot path，不做 `static inline`，也不能复制到每个
  translation unit。
- [x] `gdextension_entry(...)` 仍是唯一需要默认导出的 GDCC/Godot 入口符号；`godot_interface` support 符号默认应为
  hidden，除非后续明确引入跨动态库公共 ABI。
- [x] 补充多 translation unit C compile/link smoke test，证明两个不同 `.c` 文件 include `<godot_binding.h>` /
  `<godot_interface.h>` 后调用同一批 inline wrapper，最终共享同一份 `gdcc_interface_*` 指针表，不会产生每个
  translation unit 各自未初始化 cache 的错误。
- [x] 阶段完成后手动构建一次最小 shared object，并用平台符号表工具查看默认导出符号；
  只把这一步作为人工验收记录，不把 `nm`、`readelf`、`objdump` 等工具调用写入单元测试。

验证记录：

- `script/run-gradle-targeted-tests.sh --tests GodotInterfaceGeneratorTest,GodotAbiHeaderCompileTest,GodotBindingToolAbiSupportTest`
  通过；其中 `GodotAbiHeaderCompileTest` 覆盖 C/C++ header include、多 translation unit include/link/execute，
  验证 inline wrapper 共享 `godot_binding.c` 中的单一定义指针表。
- 手动构建含 `GDE_EXPORT gdextension_entry(...)` 和 `godot_binding.c` 的最小 shared object，并执行
  `nm -D --defined-only`；默认动态符号表仅包含 `gdextension_entry`，未导出 `godot_*` wrapper、
  `gdcc_interface_*` 指针表、`godot_initialize_interface(...)` 或 `gdcc_binding_lookup_fail(...)`。

本阶段只处理阶段 1B / 1C 产物的链接可见性和 wrapper hot path 形态，不切换模板 include、不接入 builtin/utility/fixed
wrapper，也不改变 `godot_initialize_interface(...)` 的 eager resolve 合同。

设计约束：

- 不能简单把当前 `godot_interface.c` 中的 wrapper 定义改成 `static inline`。`godot_interface.h` 仍有外部声明时会造成
  链接属性冲突；只改 `.c` 也无法让调用方获得 inline 函数体。
- 不能把 `gdcc_interface_*` cache 作为 header-local `static` 变量放进 `godot_interface.h`。那会让每个 translation unit
  拥有独立指针表，`godot_initialize_interface(...)` 只初始化其中一个 TU 的状态，其他 TU 的 inline wrapper 可能读到
  未初始化的 `NULL` cache。
- 正确形态是：header 生成 `static inline godot_*` wrapper，source 生成单定义 hidden `gdcc_interface_*` 指针表和
  hidden 初始化 / 诊断函数。这样 hot path 只剩一次对共享函数指针的间接调用，同时不扩大动态库默认导出面。
- hidden 可见性应复用 `godot_macros.h` 中的 `GDCC_GODOT_DECL` 或等价项目宏；不要为 interface generator 引入第二套
  platform visibility 分支。
- C++ include 仍要保持 `extern "C"` 边界正确：hidden `extern` 指针声明和 out-of-line 初始化 / fail helper 使用 C linkage，
  header-level `static inline` wrapper 不引入 C++ name mangling 依赖。

修改：

- `GodotInterfaceGenerator`
  - `godot_interface.h` 生成每个 `gdcc_interface_*` 指针的 hidden `extern` 声明。
  - `godot_interface.h` 生成每个 `godot_*` wrapper 的 `static inline` 函数体，函数体只调用对应
    `gdcc_interface_*` 指针，不做 lookup、不做 lazy branch。
  - `godot_interface.c` 只定义共享指针表、`godot_initialize_interface(...)`、lookup 诊断 helper 和 failure helper；
    不再生成 out-of-line `godot_*` wrapper 定义。
  - `godot_binding.c` 继续聚合 `godot_interface.c`，保证静态 runtime support 只有一个指针表定义。
- `GodotInterfaceGeneratorTest`
  - 验证 header 中存在 `static inline` wrapper 函数体，source 中不存在 out-of-line `godot_*` wrapper 定义。
  - 验证 header 中 `gdcc_interface_*` 是 `extern` + hidden 声明，source 中是唯一非 `extern` 定义。
  - 验证 wrapper hot path 无 `get_proc_address`、无 lazy `NULL` 检查、无 fallback 默认返回。
  - 验证 `godot_initialize_interface(...)` 仍 eagerly resolve 全部 interface pointer。
- `GodotAbiHeaderCompileTest` 或新增 build smoke test
  - 编译两个 include `<godot_binding.h>` / `<godot_interface.h>` 的 translation unit，再和 `godot_binding.c`
    一起链接最小 shared object。
  - 该测试只证明多 translation unit 共享同一份 `gdcc_interface_*` 指针表；默认导出符号检查留给阶段完成后的
    一次性人工验收。

验收：

- `godot_interface.h` 中每个 interface wrapper 都是 header-level `static inline`，且只通过共享
  `gdcc_interface_*` 指针表转发。
- `godot_interface.c` 中不存在 out-of-line `godot_*` wrapper 定义。
- `godot_initialize_interface(...)` 对同一份 shared pointer table 做 eager resolve；多 translation unit 调用不会复制
  cache 状态。
- 阶段完成后的人工符号表检查确认：最小 shared object 的默认动态符号表只暴露 Godot 需要的 entry symbol 和平台运行时符号，
  不暴露 GDCC internal interface support 符号。
- targeted tests 通过：
  `script/run-gradle-targeted-tests.sh --tests GodotInterfaceGeneratorTest,GodotAbiHeaderCompileTest,GodotBindingToolAbiSupportTest`。

### 阶段 2：预生成 builtin / utility 并迁移固定 runtime wrapper

本阶段前移 runtime wrapper 迁移，作为 include/build 输入切换的硬前置。先不要接入模块级使用集生成，
也不要修改模板 include 或 `CProjectBuilder`；先让命令行工具全量生成 builtin / utility wrapper，并从版本化固定清单生成 fixed wrapper，
让 `godot_binding.h/.c` 在新 Godot support 内部完整聚合 interface + builtin + utility + fixed support。

状态同步（2026-05-25）：

- [x] 已重新确认 `AGENTS.md` 并完成 doc / code / test 的并行只读调研；阶段边界确认：本阶段不修改模板 include、不切换 `CProjectBuilder` 输入、不移除 `gdextension-lite-one.c`。
- [x] 已实现 `generate-builtin`、`generate-utility`、`generate-fixed`、`check-fixed`、`dump-fixed-manifest`，并生成 `godot_builtin.*`、`godot_utility.*`、`godot_fixed_binding.*` 与阶段 2 聚合版 `godot_binding.*`。
- [x] 已将固定 runtime wrapper 从单一 `FixedGodotBindings451` 入口改为 `FixedGodotBindings` 抽象骨架 + `Godot451FixedBindings` 版本数据集；
  工具入口不再直接绑定 4.5.1 类名，后续 Godot 版本可新增数据子类接入。
- [x] 已把重复 C wrapper 形状收敛到共享宏：`GDCC_RESOLVE_*` 负责 lazy lookup / fail-fast / non-null cache，
  `GDCC_DEFINE_VARIANT_*` 和 fixed singleton/constant 宏负责纯形状重复；宏不承载生命周期分派或 fallback 语义。
- [x] 已将 builtin method wrapper 的函数指针解析、兼容 hash fallback、参数数组声明、返回 carrier 声明和 ptrcall/return
  收敛到 `GDCC_RESOLVE_BUILTIN_METHOD_CACHE` 与 `GDCC_BUILTIN_METHOD_*` 宏；method wrapper 本体不再重复展开 lookup/call boilerplate。
- [x] 已运行 `check-fixed` 对账；现有 `gdcc` helper / 模板没有发现清单外固定 wrapper 依赖。
- [x] 已运行 `GodotAbiHeaderCompileTest`，聚合后的 `godot_binding.c` 最小 C smoke 通过。
- [x] 已补齐正反向单元测试，覆盖 lookup fail-fast、hash contract、过滤规则、utility vararg / strict hash、fixed 清单对账和 scanner 缺失 wrapper fail-fast。
- [x] 已根据只读审查补修 `godot_Object_call` 的失败路径：`GDExtensionUninitializedVariantPtr` 返回槽在调用失败时不再被销毁；
  同时补充测试锁定该生命周期合同。
- [x] `check-fixed` 的 provided set 已包含从 `gdextension_interface.h` 解析出的 interface wrapper，并移除
  `godot_variant_*` / `godot_object_*` 等宽泛前缀放行；新增负向测试锁定 interface wrapper typo 必须 fail-fast。
- [x] `GodotBindingSymbolHelper` 对完全重复的 canonical symbol 改为 fail-fast，避免固定事实源被静默去重。
- [x] 已重新运行阶段 2 定向测试并通过：
  - `script/run-gradle-targeted-tests.sh --tests GodotInterfaceGeneratorTest,GodotBindingToolAbiSupportTest,GodotBuiltinGeneratorTest,GodotUtilityGeneratorTest,FixedGodotBindingsTest,EngineMethodAbiCodecTest,GodotAbiHeaderCompileTest`
  - `script/run-gradle-targeted-tests.sh --tests "CCodegenTest,CGenHelperTest,CBodyBuilderPhaseBTest,CBodyBuilderPhaseCTest,CConstructInsnGenTest,CPackUnpackVariantInsnGenTest"`
- [x] 已重新执行 `generate-builtin`、`generate-utility`、`generate-fixed`、`generate-binding` 和 `check-fixed`，确认静态 runtime support 生成物与当前生成器一致。
- [x] 生成物规模已复核：`godot_builtin.c` 15608 行、`godot_utility.c` 1355 行、`godot_fixed_binding.c` 334 行；
  `git diff --check` 通过。

- 实现 `GodotBindingSymbol`、`GodotBindingSymbolHelper`、`FixedGodotBindings` / `Godot451FixedBindings`、`GdccHelperBindingScanner`、`GodotBindingTool` 的最小版本。
- 实现 `GodotBuiltinGenerator`，全量生成 `godot_builtin.h/.c`：
  - 按 GDCC builtin wrapper contract 生成 filtered raw metadata wrapper，而不是简单映射全部 JSON；
  - builtin constructors、destructors、methods、members、operators；
  - builtin method lookup 使用 metadata primary hash，若目标 metadata 暴露 `hash_compatibility` 则按候选 hash 顺序尝试；
  - 每个 builtin method / constructor / destructor / member / operator / index/key lookup 结果都必须做 non-null 检查，
    其中 hash-sensitive builtin method lookup miss 必须走统一 fail-fast 诊断；
  - constructor、copy、from-variant、to-variant、nil `Variant`、`String` / `StringName` char helper 必须使用
    `GDExtensionUninitialized*Ptr` destination 语义；不允许把本地结果先零初始化后当 initialized pointer 调用；
  - 生成的 cleanup 路径必须知道 carrier 是否已初始化，lookup failure 或构造失败路径不能 destroy uninitialized storage；
  - constructor/operator atomic/non-struct 过滤；
  - 单 `String` 参数 constructor 的 char / UTF convenience wrapper；
  - builtin member getter/setter 与 method `get_*` / `set_*` 重名过滤；
  - `Variant <-> Type` pack/unpack；
  - indexed/keyed helper 和 typed Array/Dictionary helper。
- 实现 `GodotUtilityGenerator`，全量生成 `godot_utility.h/.c`；utility function lookup 只接受 metadata primary hash，
  返回空时立即 fail-fast，不做兼容 hash 猜测。
- `Godot451FixedBindings` 源码清单列出当前 `gdcc/*.h` 和固定模板仍需要、且不由 interface/builtin/utility 覆盖的 wrapper；
  `FixedGodotBindings` 负责通用生成骨架和版本门禁。
- `check-fixed` 扫描 `gdcc/*.h`、`entry.c.ftl`、`entry.h.ftl`、`engine_method_binds.h.ftl` 只做对账，不自动写清单。
  扫描器不能用 `godot_variant_*` / `godot_object_*` 这类前缀白名单替代 interface provided set；
  interface wrapper typo 必须 fail-fast。
- `generate-fixed` 从 `FixedGodotBindings` 当前版本数据生成 `godot_fixed_binding.h/.c`，并让 `godot_binding.h/.c` 聚合它。

全量 builtin / utility 覆盖当前大多数固定 helper 依赖：

- `godot_new_Variant_nil`
- `godot_new_Variant_with_<Type>`
- `godot_new_<Type>_with_Variant`
- `godot_new_<Type>_with_<Type>`
- `godot_<Type>_destroy`
- `godot_new_String_with_utf8_chars`
- `godot_new_StringName_with_utf8_chars`
- `godot_new_NodePath_with_utf8_chars`
- typed Array/Dictionary constructor 和 typed metadata accessor
- `godot_print`、`godot_deg_to_rad` 等 utility function
- macro 拼接产生的 builtin conversion constructor，例如 `godot_new_Vector2_with_Vector2i`、`godot_new_Vector3_with_Vector3i`、`godot_new_Vector4_with_Vector4i`

fixed source-list 至少覆盖剩余 engine/runtime helper 面：

- `godot_Engine_singleton`
- `godot_Engine_is_editor_hint`
- `godot_ClassDB_singleton`
- `godot_ClassDB_is_parent_class`
- `godot_Object_call`
- `godot_Object_get`
- `godot_Object_set`
- `godot_Object_get_instance_id`
- `godot_Object_notification`
- `godot_Object_NOTIFICATION_POSTINITIALIZE`
- `godot_Object_NOTIFICATION_PREDELETE`
- `godot_RefCounted_reference`
- `godot_RefCounted_unreference`
- `godot_RefCounted_init_ref`

`godot_new_gdcc_Object_with_Variant` 保持为 `gdcc_helper.h` 本地 helper，因为它依赖 entry translation unit
中的 `class_library` 从 Godot object pointer 取回已绑定的 GDCC instance；它不能移入独立编译的 fixed runtime support。

验收：

- `GodotBindingTool generate-builtin`、`generate-utility`、`check-fixed`、`generate-fixed` 可在命令行重复执行，重复执行不改变输出。
- builtin 预生成 symbol 集与 GDCC builtin wrapper contract 一致；至少覆盖 4.5.1 contract 允许的 constructors、methods、operators、members。
- builtin method wrapper 的生成文本必须先 lazy lookup，再对空指针 fail-fast，最后才 ptrcall；测试用 fixture 覆盖
  primary hash mismatch / compatibility hash 未命中时不会缓存空指针。
- primitive default/self-copy/primitive-primitive constructor 与 operator wrapper 不生成；例如不把 `Nil`、`bool`、`int`、`float`
  的 filtered raw metadata 项当成缺失。
- Godot 4.5.1 中所有单 `String` 参数 constructor 都生成 char / UTF convenience wrapper；当前样本至少包括
  `int`、`float`、`String`、`Color`、`StringName`、`NodePath`。
- `Transform2D.get_origin` 这类与 member `origin` 重名的 builtin method wrapper 不生成，`godot_Transform2D_get_origin`
  只对应 member accessor ABI。
- `indexing_return_type` / `is_keyed` 驱动 indexed/keyed helper 生成；`Array` 生成 indexed `Variant` accessor，
  `Dictionary` 生成 keyed `Variant` accessor。
- `Variant` 合成 helper 齐全，至少包含 `godot_new_Variant_nil`、`godot_new_Variant_with_<Type>`、
  `godot_new_<Type>_with_Variant`、`godot_new_Variant_with_Variant` 和 builtin copy constructor。
- constructor / conversion / nil / copy helper 的生成文本必须能证明使用 `GDExtensionUninitialized*Ptr` destination；
  测试覆盖 `Variant`、`String`、`StringName` 和 `TypeFromVariant` / `VariantFromType` 代表路径，且失败路径不会 destroy
  未初始化 storage。
- typed Array/Dictionary constructor 与 typed metadata accessor 已由 builtin 全量预生成或版本化 override 覆盖，不需要 fixed 清单重复列出。
- utility 预生成 symbol 集与 `ExtensionAPI.utility_functions[]` 一致，包含 vararg / void return utility。
- utility wrapper 的生成文本必须包含严格 hash lookup 和空指针 fail-fast；测试覆盖 utility hash 冲突路径，
  并确认没有 `hash_compatibility` 或无 hash fallback。
- `FixedGodotBindings` 当前版本数据中没有重复 canonical symbol，且没有同名不同签名冲突。
- `check-fixed` 发现清单外固定 `godot_*` 调用时 fail-fast，但不自动修改源码清单。
- `godot_fixed_binding.c` 只包含 fixed source-list 需要且不被 interface/builtin/utility 覆盖的 wrapper。
- `godot_builtin.c`、`godot_utility.c`、`godot_fixed_binding.c` 中重复 lookup / cache / fail-fast 形状必须通过共享 C 宏表达；
  测试应锚定宏调用、non-null cache 和失败路径，而不是重新锁死大段展开文本。
- `godot_binding.h` 聚合 `godot_abi.h`、`godot_interface.h`、`godot_builtin.h`、`godot_utility.h`、`godot_fixed_binding.h`。
- `godot_binding.c` 聚合 `godot_interface.c`、`godot_builtin.c`、`godot_utility.c`、`godot_fixed_binding.c`，并可在只包含 `<includeRoot>/godot` 的最小 C smoke test 中编译。
- 固定 wrapper 阶段完成前，`entry.c.ftl`、`entry.h.ftl`、`gdcc/*.h` 仍不切换 include，`CProjectBuilder` 仍不移除 `gdextension-lite-one.c`。
- 阶段 2 只新增生成器和 fixed/runtime support 的 contract 测试，不反向修改仍锁定旧 vendor 编译输入的测试；
  `ApiCompilePipelineTest`、`CProjectBuilderSharedIncludeTest` 在本阶段继续允许 `gdextension-lite-one.c` 存在，
  避免在模板/helper include 尚未切换前制造半迁移状态。
- `CCodegenTest`
- `CGenHelperTest`
- `CBodyBuilderPhaseBTest`
- `CBodyBuilderPhaseCTest`
- `CConstructInsnGenTest`
- `CPackUnpackVariantInsnGenTest`

### 阶段 3：原子切换 entry/include/build 输入

本阶段是唯一的 gdextension-lite 编译输入切换点。必须在阶段 2 的 fixed runtime wrapper 验收通过后执行，
并把模板 include 替换、entry 初始化替换、`gdcc/*.h` include 替换、`CProjectBuilder` include dir / cFiles 切换放在同一个可编译边界。
不允许先移除 `gdextension-lite-one.c` 再补 fixed wrapper，也不允许先让模板 include `<godot_binding.h>` 但构建仍看不到 `<includeRoot>/godot`。

状态同步（2026-05-25）：

- [x] 已重新确认 `AGENTS.md`，并使用 `gpt-5.4-mini` 子代理并行完成阶段 3 相关文档、后端代码、测试和 Godot ABI 调研。
- [x] 已切换 `entry.c.ftl` / `entry.h.ftl` 与 `gdcc/*.h` 的 runtime include 边界：
  `entry.c` 不再 include `<implementation-macros.h>` 或调用 `gdextension_lite_initialize(...)`，入口初始化改为
  `godot_initialize_interface(p_get_proc_address)` 失败即返回 `false`；`entry.h` 和固定 helper 头改为消费 `<godot_binding.h>`。
- [x] 已切换 `CProjectBuilder` 的 include 抽取和 native 编译输入：只抽取 `gdcc/**` 与 `godot/**` runtime support，
  include dirs 改为 `<includeRoot>/gdcc` 与 `<includeRoot>/godot`，编译输入显式追加并要求
  `<includeRoot>/godot/godot_binding.c` 存在；旧 `gdextension-lite-one.c` 残留不再被扫描或加入 `cFiles`。
- [x] 已更新阶段 3 测试锚点：`CProjectBuilderSharedIncludeTest` 覆盖 `godot_binding.c` 正向输入和 stale vendor
  负向输入，`ApiCompilePipelineTest` 保持核心 3 个 generated files 契约并断言 include/native 输入已切到 `godot`；
  `CCodegenTest` 锁定 `godot_initialize_interface(...)` 的入口初始化顺序，`CCodegenEngineMethodBindHeaderTest`
  锁定 exact engine method bind lookup 统一失败诊断与 primary / compatibility hash 候选。
- [x] 已清理被触及源码注释、test display name、failure message 和后端文档中的当前 `gdextension-lite`
  事实表述；旧名称仅保留为历史命名来源或 stale vendor 负向测试材料。
- [x] 集成烟测发现 `godot_new_gdcc_Object_with_Variant` 不能作为 fixed runtime wrapper 独立编译：它必须读取
  entry translation unit 的 `class_library` 才能通过 instance binding 取回 GDCC wrapper；因此已将它保留为
  `gdcc_helper.h` 本地 helper，并从 `Godot451FixedBindings` / `godot_fixed_binding.*` 中移除，避免声明冲突和语义回退。
- [x] 集成烟测继续暴露旧 `<gdextension-lite.h>` 曾隐式提供的 engine class 声明面：生成 C 会使用
  `godot_Node` / `godot_Node2D` 这类 opaque pointer 类型，以及 `godot_Node_InternalMode` 这类 class-scoped enum。
  已改为由 `GodotBindingTool` 从 `ExtensionAPI.classes[]` 生成到 `godot_abi.h`；native structure 已承载的 scoped enum
  会被跳过，避免和 `godot_native_structures.h` 重复定义。
- [x] 已重新分析 engine constructor 语义并纠正错误方向：`godot_new_<Class>()` 不能替换为
  `godot_classdb_construct_object2(...)`，因为二者 postinitialize 语义不同。阶段 3 保持 `ConstructInsnGen` 输出
  `godot_new_<Class>()`，并由 `godot_fixed_binding.*` 从 `ExtensionAPI.classes[]` 为可实例化 engine class 生成
  constructor wrapper；wrapper 内部当前使用 `godot_classdb_construct_object(...)`，锚定迁移前 public wrapper contract。
  测试只锁定 `construct_object` 继续经过 `godot_new_<Class>()` wrapper 边界和真实运行行为，不添加
  `godot_classdb_construct_object2(...)` 字符串级负向断言，以免阻塞后续用显式 API 组合替换旧隐式行为。
- [x] 已完成阶段 3 验证：
  - `script/run-gradle-targeted-tests.sh --tests CConstructInsnGenTest,CConstructInsnGenEngineTest,FixedGodotBindingsTest,GodotAbiHeaderCompileTest`
  - `script/run-gradle-targeted-tests.sh --tests ApiCompilePipelineTest,CProjectBuilderSharedIncludeTest,CCodegenTest,CCodegenEngineMethodBindHeaderTest,GodotAbiHeaderCompileTest,FixedGodotBindingsTest,GodotBindingToolAbiSupportTest`
  - `script/run-gradle-targeted-tests.sh --tests CProjectBuilderIntegrationTest,FrontendLoweringToCProjectBuilderIntegrationTest,FrontendVoidReturnCallIntegrationTest`
  - `rg -n "gdextension-lite|gdextension_lite|gdextension-lite-one|implementation-macros|<gdextension-lite.h>|shared-include/gdextension-lite|tmp/inspect_gdlite" src/main src/test doc`
    确认 `src/main` 中不再存在当前 vendor include / init / native input 依赖；剩余命中是历史命名文档、迁移计划说明或 stale vendor 负向测试材料。
  - IntelliJ inspections 已检查 `FixedGodotBindings.java`、`GodotBindingTool.java`、`ConstructInsnGen.java`、`CProjectBuilder.java`
    和相关 test 文件；未发现 error，仅保留既有未使用、恒定参数或长方法类 warning。
  - `git diff --check` 已通过；清理 trailing whitespace 后重新运行
    `script/run-gradle-targeted-tests.sh --tests GodotAbiHeaderCompileTest,FixedGodotBindingsTest` 并通过。
  - 移除旧的 engine RefCounted `classdb_construct_object2` 字符串负向断言后，重新运行
    `script/run-gradle-targeted-tests.sh --tests FrontendLoweringToCProjectBuilderIntegrationTest` 并通过。

修改：

- `entry.c.ftl`
  - 删除 `#include <implementation-macros.h>`。
  - 删除 `gdextension_lite_initialize(p_get_proc_address);`。
  - 改为：
    `if (!godot_initialize_interface(p_get_proc_address)) { return false; }`
  - 初始化成功后再写入 `class_library = p_library` 和 lifecycle callback。
- `entry.h.ftl`
  - `#include <gdextension/gdextension_interface.h>` 改为新 ABI include。
  - `#include <gdextension-lite.h>` 改为 `#include <godot_binding.h>`。
  - 保持 `#include <gdcc_helper.h>` 和 `#include "engine_method_binds.h"`。
- `engine_method_binds.h.ftl`
  - method bind accessor 不再在所有 hash 候选失败后返回 `NULL` 给调用 helper。
  - accessor 必须按 primary hash、去重后的 `hash_compatibility` 顺序调用 `godot_classdb_get_method_bind(...)`；
    每次非空立即缓存并返回，全部失败后调用统一 binding lookup failure helper。
  - exact instance/static/vararg helper 不再通过默认返回值吞掉 bind lookup miss；只有 Godot method call 本身返回
    `GDExtensionCallError` 时，才继续走现有 call error 诊断和安全返回。
- `gdcc/*.h`
  - `gdcc_call.h`、`gdcc_helper.h`、`gdcc_bind.h`、`gdcc_intrinsic.h`、`gdcc_string.h`、`gdcc_string_name.h`
    从 `<gdextension-lite.h>` 改为 include `<godot_binding.h>` 或更窄的 `godot_abi.h` / `godot_interface.h` / `godot_fixed_binding.h`。
- `CProjectBuilder`
  - `includeDirs = List.of(includeRoot.resolve("gdcc"), includeRoot.resolve("godot"))`。
  - `cFiles` 追加 `<includeRoot>/godot/godot_binding.c`；该文件必须存在且是 regular file，否则构建准备阶段直接失败。
  - `cFiles` 仍先收集本轮 `GeneratedFile` 中所有 `.c`；阶段 4 不生成 `godot_module_bindings.c`，
    因此 module-local wrapper snapshot 非空也不能改变 native input 数量。
  - 删除旧的 `Files.exists(includeRoot.resolve("gdextension-lite").resolve("gdextension-lite-one.c"))`
    存在即加入分支；旧 vendor 子树即使残留在 `shared-include` 或项目 `include`，也不能影响本轮 native input。
  - 不把 `projectPath` 加入 include dirs；项目级 generated header 只能通过同目录 quote include 链路进入 translation unit。
  - `ResourceExtractor` 保持“抽取/覆盖但不删除旧文件”的工具语义；阶段 3 不把它升级成通用 manifest 清理器。
  - `initProject` 和 `buildProject` 不能继续盲目抽取整个 `include_451` 后假定目标目录干净：
    - 若 vendor zip 在本阶段仍保留到阶段 5，改用版本化 include 资源 allow-list 或 `extractSpecific(...)`
      只抽取 `gdcc/**` 与 `godot/**` 所需文件。
    - 若选择不引入 allow-list，则必须在本阶段同时删除 `src/main/c/codegen/include_451/gdextension-lite.zip`。
    - 两种做法都不允许把 `gdextension-lite/gdextension-lite-one.c` 重新抽取为新内容；旧目录若已存在，只能作为待阶段 5 清理的残留，
      不能进入 include dirs 或 native compiler input。
- 活动文档和被触及注释
  - 同阶段更新 `doc/gdcc_runtime_lib.md` 中 entry lifecycle、runtime helper 与 binding wrapper 事实表述；
    新的入口初始化由 `godot_initialize_interface(...)` 完成。
  - 同阶段更新 `doc/gdcc_c_backend.md` 的 entry lifecycle / wrapper behavior 相关表述，不能继续把
    gdextension-lite 当作当前初始化或 wrapper 行为事实源。
  - 同阶段更新被触及源码注释，例如 `CProjectBuilder`、`ConstructInsnGen`、`CGenHelper` 中把
    `gdextension-lite` 描述为当前 public wrapper 或 native input 的注释。

初始化顺序必须保持：

1. `godot_initialize_interface(p_get_proc_address)`。
2. 初始化失败时 `gdextension_entry(...)` 返回 `false`。
3. `class_library = p_library`。
4. 设置 `r_initialization->minimum_initialization_level = GDEXTENSION_INITIALIZATION_SCENE`。
5. 设置 `userdata`、`initialize`、`deinitialize`。
6. 返回 `true`。

验收：

- 生成的 `entry.c` 不再出现 `gdextension_lite_initialize`。
- 生成的 `entry.c` 不再 include `<implementation-macros.h>`。
- 生成的 `entry.h` 和 `gdcc/*.h` 不再 include `<gdextension-lite.h>`。
- `godot_initialize_interface(...)` 发生在任何 `godot_*` wrapper 调用前。
- `initialize(...)` / `deinitialize(...)` 的 scene-level guard 和 `gdcc_init()` 调用位置不变。
- `CCodegenEngineMethodBindHeaderTest` 反向更新：旧断言中“bind lookup 失败后打印 runtime error 并返回默认值”的形状必须删除；
  新断言应确认生成代码包含 primary + compatibility hash 候选、空 bind failure helper 调用、以及调用 helper 不再直接处理
  `bind == NULL` 分支。
- `CProjectBuilderSharedIncludeTest` 改为断言：
  - `shared-include/gdcc/gdcc_helper.h`
  - `shared-include/godot/godot_interface.h`
  - `shared-include/godot/godot_fixed_binding.h`
  - `shared-include/godot/godot_binding.c`
  - include dirs 等于 `shared-include/gdcc` 和 `shared-include/godot`
  - native compiler input 包含 `shared-include/godot/godot_binding.c`
  - native compiler input 不包含 `shared-include/gdextension-lite/gdextension-lite-one.c`
  - 测试应预先写入一个 `shared-include/gdextension-lite/gdextension-lite-one.c` 残留文件，证明旧 vendor
    即使存在也不会被 `CProjectBuilder` 加入 `compiler.cFiles()`
  - include dirs 不包含生成项目目录。
- `ApiCompilePipelineTest` 中旧的 native input 断言必须同阶段反向更新：
  - 保留 `entry.c` 作为 generated C 输入。
  - 把 `gdextension-lite-one.c` 改为 `godot_binding.c`。
  - 增加 include dir 断言，确认存在 `gdcc` 与 `godot` include root，且不再存在 `gdextension-lite` include root。
  - 增加负向断言：即使构建目录或 shared include 中存在迁移前遗留的 `gdextension-lite-one.c`，也不会出现在
    `compiler.lastCFiles()`。
- `ApiCompilePipelineTest`、`ApiCompileArtifactLinkTest`、`ApiCompileDiagnosticsTest`、`ApiRecompileArtifactRefreshTest`、
  `GdccCommandInputTest` 中关于 generated file 列表的断言，本阶段只在仍恰好输出 3 个文件时保持；
  不要在阶段 3 提前加入 module-local generated binding 文件断言，避免和阶段 4 职责混合。
  阶段 3 不改变 `CompileResult.generatedFiles()`、`outputLinks()`、`/generated` 目录列表的公共文件集合形状。
- `FrontendLoweringToCProjectBuilderIntegrationTest` 等测试中的说明文字如果写着“gdextension-lite constructor”，
  应改为“generated builtin constructor”或直接断言 `godot_new_Vector3_with_Vector3i` 这类 public C symbol，
  不再把旧 vendor 当作当前行为来源。
- `CProjectBuilderIntegrationTest` 不再检查 `gdextension-lite` 路径。
- 空 module 和至少一个使用固定 helper 的最小 module 能完成 C 编译；若 Zig 不可用，测试必须明确 skip 而不是静默放过。

### 阶段 3.1：修复 builtin ptrcall 返回 carrier 初始化回归

本阶段是阶段 3 切换到新 generated Godot binding 后的回归修复闸口，必须先于阶段 4 的 module-local binding
使用集接入。阶段 3 已经完成编译输入切换；阶段 3.1 只修复新 binding 内部的 ptrcall 返回槽语义，不回退到
gdextension-lite，不改变 `CProjectBuilder` 的 native input 规则，也不提前引入 `godot_module_bindings.h/.c`。

实施状态：

- [x] 修复 builtin method / operator / getter / indexed getter / keyed getter 的 ptrcall return carrier 初始化。
- [x] 修复模块 `entry.h` operator evaluator helper 和 fixed runtime `object_method_bind_ptrcall` wrapper 的 return carrier 初始化。
- [x] 补充正反向单元测试，锚定 initialized carrier 与 `GDExtensionUninitialized*Ptr` destination 的边界。
- [x] 更新 `doc/gdcc_c_backend.md` 中的 ptrcall return carrier contract。
- [x] 运行 targeted 单元测试和 `GdScriptUnitTestCompileRunnerTest` ABI/runtime 回归。

追加修复状态（2026-05-26）：

- [x] 将全量预生成 `godot_utility.c` 的非 void utility wrapper 纳入 initialized carrier 规则。
- [x] 补充 `GodotUtilityGeneratorTest` 正反向断言，覆盖 `Variant` / `String` / primitive / object pointer
  返回 carrier、void utility `NULL` destination、lookup failure zero-return 边界。
- [x] 重新执行 `generate-utility`，同步 `src/main/c/codegen/include_451/godot/godot_utility.c/.h`。
- [x] 运行 utility generator targeted 单元测试和
  `CallGlobalInsnGenEngineTest#callGlobalUtilitiesShouldRunInRealGodot` 真实 Godot 回归。

验证记录（2026-05-25）：

- [x] 运行 `script/run-gradle-targeted-tests.sh --tests GodotBuiltinGeneratorTest,FixedGodotBindingsTest,CCodegenTest,CCodegenEngineMethodBindHeaderTest`。
- [x] 运行 `script/run-gradle-targeted-tests.sh --tests gd.script.gdcc.test_suite.GdScriptUnitTestCompileRunnerTest.compilesAndValidatesAbiScripts`。
- [x] 运行 `script/run-gradle-targeted-tests.sh --tests gd.script.gdcc.test_suite.GdScriptUnitTestCompileRunnerTest.compilesAndValidatesRuntimeScripts`。
- [x] 运行 `rg -n "return_type result;|godot_String result;|godot_Array result;|godot_Dictionary result;|godot_float value;|uint64_t result;" ...`
  审计 generated builtin/fixed/template return carrier；未发现裸 ptrcall return carrier。
- [x] 运行 `git diff --check`。
- [x] IntelliJ inspections 已检查 `GodotBindingSupport.java`、`GodotBuiltinGenerator.java`、`FixedGodotBindings.java`
  和 `GodotBuiltinGeneratorTest.java`；未发现 error，仅保留既有 warning / weak warning。

追加验证记录（2026-05-26）：

- [x] 运行 `script/run-gradle-targeted-tests.sh --tests gd.script.gdcc.backend.c.gen.binding.GodotUtilityGeneratorTest`。
- [x] 运行 `script/run-gradle-targeted-tests.sh --tests gd.script.gdcc.backend.c.gen.binding.GodotUtilityGeneratorTest,gd.script.gdcc.backend.c.gen.binding.GodotBuiltinGeneratorTest`。
- [x] 运行 `script/run-gradle-targeted-tests.sh --tests gd.script.gdcc.backend.c.gen.CallGlobalInsnGenEngineTest.callGlobalUtilitiesShouldRunInRealGodot`。
- [x] 运行 `script/run-gradle-targeted-tests.sh --tests gd.script.gdcc.backend.c.gen.CallGlobalInsnGenEngineTest`。
- [x] 运行 `rg -n "godot_(Variant|String|Array|Dictionary|Object \\*) result;|godot_float result;|godot_int result;|godot_bool result;" ...`
  审计 generated utility return carrier；未发现裸 `result;`。
- [x] 运行 `git diff --check`。
- [x] IntelliJ inspections 已检查 `GodotUtilityGenerator.java`、`GodotUtilityGeneratorTest.java` 和
  `FixedGodotBindings.java`；未发现 error，仅保留既有或无害 warning / weak warning。

回归现象（2026-05-25）：

- 重新运行 `GdScriptUnitTestCompileRunnerTest` 后，`abi/typed_array`、`abi/typed_dictionary`、
  `runtime/string_literal` 中部分 case 在 Godot runtime 阶段触发 SIGSEGV。
- 典型 `addr2line` 定位：
  - `runtime/string_literal_internal_surface` 崩在 generated `godot_String_substr(...)`；
  - `abi/typed_array/*` 崩在 generated `godot_Array_get_typed_script(...)`；
  - `abi/typed_dictionary/*` 崩在 generated `godot_Dictionary_get_typed_*_script(...)` 或相邻 typed metadata getter。
- 这些崩溃发生在 generated builtin wrapper 内部，早于用户方法体执行；typed container guard 的 `Variant == nil`
  null-script 判断、字符串 literal UTF-8 materialization、以及 engine method bind lookup 不是本次 SIGSEGV 的直接原因。

根因：

- `GDExtensionPtrBuiltInMethod`、`GDExtensionPtrOperatorEvaluator`、`GDExtensionPtrGetter`、
  `GDExtensionPtrIndexedGetter`、`GDExtensionPtrKeyedGetter` 的返回/result 参数类型是 `GDExtensionTypePtr`。
  这些 ptrcall 路径的返回槽是 initialized carrier / assignment target，不是
  `GDExtensionUninitializedTypePtr`。
- Godot 上游 builtin ptrcall 通过 `PtrToArg<R>::encode(..., r_ret)` 写返回值；对 `String`、`StringName`、
  `Array`、`Dictionary`、`Variant` 等 value-semantic wrapper，`encode` 会对 `r_ret` 指向的对象执行赋值。
  因此目标必须已经是有效 carrier；不能是未初始化栈内存。
- 当前 `GodotBindingSupport.builtinMethodMacros()` 生成的 `GDCC_BUILTIN_METHOD_RETURN` 使用：

  ```c
  return_type result;
  cache(..., (GDExtensionTypePtr)&result, ...);
  return result;
  ```

  这对 primitive return 通常不稳定但不一定立刻崩；对 `godot_String`、`godot_Variant` 等 destroyable value
  wrapper 会在赋值路径读取随机内部状态，触发未定义行为和 SIGSEGV。
- `appendOperatorDefinitions(...)`、builtin member getter、indexed/keyed getter、以及模块内直接生成的 operator evaluator
  helper 若也声明裸 `result;`，属于同一类风险。`runtime/string_literal` 中字符串拼接 helper 已暴露同形代码，只是当前
  更稳定地在 `String.substr()` 处崩溃。
- 追加回归：`CallGlobalInsnGenEngineTest#callGlobalUtilitiesShouldRunInRealGodot` 在 `call_lerp` 处触发 SIGSEGV；
  `addr2line` 指向 generated `godot_lerp(...)` 的 `gdcc_utility_lerp((GDExtensionTypePtr)&result, args, 3)`。
  `godot_lerp` / `godot_max` 这类 utility wrapper 仍生成裸 `godot_Variant result;`，`godot_str` /
  `godot_error_string` 这类 `String` 返回 wrapper 也同形。`GDExtensionPtrUtilityFunction` 的返回槽同样是
  `GDExtensionTypePtr`，Godot 侧通过 `PtrToArg<R>::encode(...)` 写回，属于 initialized carrier 规则，
  不属于 `GDExtensionUninitialized*Ptr` raw storage 规则。

硬性边界：

- 不要把这次修复理解为“所有 Godot out-param 都要先初始化”。
- 下列路径仍然是 uninitialized destination，必须继续传 raw storage：
  - builtin constructor；
  - `Variant <-> Type` conversion；
  - `String` / `StringName` convenience constructor；
  - `godot_variant_call`、`godot_variant_call_static`、`godot_variant_evaluate`、`godot_variant_get*`；
  - `godot_object_method_bind_call`，包括 `engine_method_binds.h` 中 exact engine vararg helper 的本地
    `Variant` 返回槽；
  - 其他 interface typedef 明确写成 `GDExtensionUninitialized*Ptr` 的函数。
- 下列 ptrcall/result 路径必须先准备 initialized carrier，再传 `GDExtensionTypePtr`：
  - `GDExtensionPtrBuiltInMethod`；
  - `GDExtensionPtrOperatorEvaluator`；
  - `GDExtensionPtrGetter` / `GDExtensionPtrIndexedGetter` / `GDExtensionPtrKeyedGetter`；
  - `GDExtensionPtrUtilityFunction`；
  - `godot_object_method_bind_ptrcall(...)`；该路径已有 exact engine helper contract，本阶段只防止新 builtin
    wrapper 偏离同一规则。
- typed array / typed dictionary 的 object leaf script metadata 仍按 null-object 处理：
  - runtime preflight 继续读取 `typed_builtin` / `typed_class_name` / `typed_script` 三元组；
  - script 位继续通过 `Variant == nil` 判断；
  - 不允许为绕过崩溃删除 `get_typed_script()`，也不允许把 object leaf 降级成 plain object 或跳过 preflight。

修改计划：

- `GodotBindingSupport.builtinMethodMacros()`：
  - `GDCC_BUILTIN_METHOD_RETURN` / `RETURN0` 的本地返回 carrier 改为 initialized carrier，例如
    `return_type result = { 0 };`。
  - void 路径保持不变。
  - lookup failure 的默认返回仍使用现有 `zeroReturn(...)`，不能在 lookup failure 后 destroy 未初始化值。
- `GodotBuiltinGenerator`：
  - builtin method wrapper 继续只通过宏调用 ptrcall，避免把 lookup、参数数组和 ptrcall 重新内联到每个方法。
  - `appendOperatorDefinitions(...)` 中所有非 void operator result carrier 改为 initialized carrier。
  - member getter、indexed getter、keyed getter 的 non-void result carrier 同步改为 initialized carrier。
  - constructor、destructor、`Variant` conversion、`String` codec helper 不参与这次 carrier 初始化替换。
- 直接生成 operator evaluator helper 的后端路径：
  - 扫描 `COperatorInsnGen` / `OperatorResolver` / `CGenHelper` 中直接调用
    `godot_variant_get_ptr_operator_evaluator(...)` 后声明 `result;` 的 helper。
  - 这些 helper 的 result carrier 也必须使用 initialized carrier，与 generated builtin operator wrapper 一致。
- 固定 runtime wrapper：
  - 复核 `FixedGodotBindings` 中 `godot_object_method_bind_ptrcall(...)` 的返回 carrier；若仍存在裸 destroyable wrapper
    `result;`，按 `engine_method_binds.h.ftl` 的规则补齐 initialized carrier。
  - 已经使用 `godot_object_method_bind_call(...)` 的 `Variant` 返回路径保持 uninitialized destination，不要误改。
  - `engine_method_binds.h.ftl` 的 vararg helper 同样必须保持 raw `Variant` 返回槽，成功后才能 unpack/destroy；
    `GDEXTENSION_CALL_ERROR_*` 路径不得销毁未构造的返回槽。
- utility wrapper：
  - `GodotUtilityGenerator.appendDefinition(...)` 中所有非 void utility return carrier 改为 initialized carrier，
    复用 `GodotBindingSupport.initializedCarrierDeclaration(...)`，不要新增 utility 专用 helper。
  - `void` utility 继续传 `NULL` return slot，继续使用 `return;` 作为 lookup failure fallback，不生成 result carrier。
  - lookup failure 的 zero-return 继续使用 `GodotBindingSupport.zeroReturn(...)`，不要为了初始化 carrier 改变
    `godot_new_Variant_nil()`、`(godot_String){ 0 }`、`NULL`、primitive zero 等 fallback 语义。
  - `godot_lerp` / `godot_max` 等 `Variant` 返回、`godot_str` / `godot_error_string` 等 `String` 返回、
    `godot_sin` 等 primitive 返回、`godot_instance_from_id` 等 object pointer 返回都应在生成文本中表现为
    `return_type result = { 0 };` 后再调用 `GDExtensionPtrUtilityFunction`。
- 文档同步：
  - 在 `doc/gdcc_c_backend.md` 的 ptrcall return carrier contract 中补充：同一规则也适用于 builtin method、
    builtin operator、builtin getter wrapper 和 utility wrapper。
  - 在本计划的阶段 2/3 完成记录中保留“已迁移到新 binding”的事实，但追加本阶段修复记录，避免阶段 3 被误读成
    runtime ABI 已完全稳定。

测试计划：

- `GodotBuiltinGeneratorTest`
  - 增加 builtin method 返回 `String` 的文本断言，例如 `godot_String_substr(...)` 的 wrapper 使用 initialized carrier。
  - 增加 builtin method 返回 `Variant` 的文本断言，例如 `godot_Array_get_typed_script(...)` 或
    `godot_Dictionary_get_typed_value_script(...)`。
  - 增加 operator 返回 `String` 的文本断言，例如 `godot_String_op_add_String(...)`。
  - 增加 getter/indexed/keyed getter 中 destroyable return carrier 的断言；如果 fixture metadata 不覆盖这类 getter，
    使用最小 fake API fixture 补齐。
- 后端 operator helper 测试：
  - 覆盖 `String + String -> String` 或等价 destroyable wrapper operator helper，确认 direct evaluator helper
    不再生成裸 `godot_String result;`。
- 固定 wrapper / exact engine helper 测试：
  - 保留 `CCodegenEngineMethodBindHeaderTest` 现有 “zero initialize destroyable ptrcall return carriers” 断言。
  - 若 `FixedGodotBindings` 发生改动，补充对应 generator test。
- 集成回归：
  - `script/run-gradle-targeted-tests.sh --tests gd.script.gdcc.test_suite.GdScriptUnitTestCompileRunnerTest.compilesAndValidatesAbiScripts`
  - `script/run-gradle-targeted-tests.sh --tests gd.script.gdcc.test_suite.GdScriptUnitTestCompileRunnerTest.compilesAndValidatesRuntimeScripts`
  - 重点确认 `abi/typed_array`、`abi/typed_dictionary`、`runtime/string_literal_internal_surface` 不再出现 signal 11。

验收：

- generated `godot_builtin.c` 中 `godot_String_substr(...)`、`godot_Array_get_typed_script(...)`、
  `godot_Dictionary_get_typed_value_script(...)` 不再把未初始化本地变量作为 ptrcall return carrier。
- generated string operator helper 不再把未初始化 `godot_String result;` 传给 `GDExtensionPtrOperatorEvaluator`。
- typed container runtime preflight 逻辑保持完整，仍检查 typed builtin、class name 和 script metadata。
- `runtime/string_literal_internal_surface.gd` 的 `substr_internal()` 能返回 `"cde"`；`concat_internal()` 的字符串拼接也不依赖
  未定义行为。
- 本阶段不改变 `CompileResult.generatedFiles()`、`outputLinks()`、`CProjectBuilder` include dirs 或 native input 集合。

### 阶段 3.2：按需生成 engine constructor 并修复 fixed StringName 缓存

本阶段是阶段 3 runtime support 切换完成后的第二个收尾闸口，必须先于阶段 4 的通用 module-local binding
使用集接入。阶段 3.1 已修复新 binding 的 ptrcall return carrier；阶段 3.2 只处理 engine class
constructor wrapper 的生成范围和构造名缓存问题，不扩展 engine method/property/singleton/class constant 的 module-local
wrapper 面。

阶段 3.2 的目标是：

- 停止把 `ExtensionAPI.classes[]` 中所有 `isInstantiable()` engine class constructor wrapper 全量预生成进
  `godot_fixed_binding.h/.c`。
- 保留 `construct_object` 的 public wrapper 边界：engine object construction 继续由 `ConstructInsnGen` 输出
  `godot_new_<EngineClass>()`，而不是直接输出 `godot_classdb_construct_object2(...)` 或其它 ClassDB fallback。
- 让实际用到的 `godot_new_<EngineClass>()` 由模块 codegen 按需生成，且生成点必须使用 `GD_STATIC_SN(...)`
  或等价静态缓存，不能在热路径里每次构造/销毁 `godot_StringName`。
- 修复 `FixedGodotBindings.symbols(api)` / fixed manifest / helper scanner 只覆盖 `functions()` 清单、却漏掉当前全量
  engine constructor 输出的事实源缺口；阶段 3.2 之后 fixed manifest 不应再隐式包含全量 constructor。
- 同步把 `engine_method_binds.h` 中重复的 `gdcc_engine_method_bind*` accessor 函数体收束到 `gdcc_bind.h`
  的宏封装中；这是模板可读性整理，不改变 exact engine method route 的 lookup 和调用语义。

当前问题：

- `FixedGodotBindings.FixedRenderer.renderHeader()` 和 `appendEngineConstructorDefinitions(...)` 会遍历所有
  `api.classes()`，对每个可实例化 engine class 输出 `godot_new_<Class>()` 声明和实现。这个集合很大，且多数模块根本不会使用。
- `FixedGodotBindings.collectSymbols(...)` 只收集 `Godot451FixedBindings.functions()` 中的固定 helper，
  不收集这些额外生成出来的 constructor wrapper。因此 `GodotBindingTool.providedSymbols(...)`、fixed manifest
  和 `GdccHelperBindingScanner` 的视角里并没有这批 wrapper；它们既全量生成，又不在 fixed source-list 事实源里。
- 当前 generated `gdcc_fixed_construct_object(const char *class_name_text)` 每次调用都会：
  1. `godot_new_StringName_with_latin1_chars(class_name_text)`；
  2. `godot_classdb_construct_object(&class_name)`；
  3. `godot_StringName_destroy(&class_name)`。
  这会把每次 engine object construction 都变成一次 `StringName` 分配/注册/销毁路径，偏离当前 runtime
  已经在模板和 helper 中使用的 `GD_STATIC_SN(...)` 静态缓存规则。
- 不能通过把 `gdcc_fixed_construct_object(...)` 改成 `godot_classdb_construct_object2(...)` 来规避旧 helper。
  Godot 上游 `gdextension_interface.cpp` 中：
  - `classdb_construct_object` 对应 `ClassDB::instantiate_no_placeholders(...)`；
  - `classdb_construct_object2` 对应 `ClassDB::instantiate_without_postinitialization(...)`；
  - `gdextension_interface.json` 对 `classdb_construct_object2` 明确要求构造后还必须发送
    `NOTIFICATION_POSTINITIALIZE`。
  因此 `construct_object2` 是 GDCC `*_class_create_instance(...)` 这种“先创建 raw object、再绑定 extension instance、
  再按需要手动 postinitialize”的协议入口，不是 engine class public constructor wrapper 的等价替代。

硬性边界：

- `ConstructInsnGen` 的 engine object 分支继续生成 `godot_new_<Class>()`，并继续用
  `valueOfOwnedExpr(..., PtrKind.GODOT_PTR)` 标记 fresh object producer。destination slot 必须消费该 `OWNED`
  结果，不能重新 retain。
- `ConstructInsnGen` 的 GDCC class 分支继续生成 `<Class>_class_create_instance(NULL, notify_postinitialize)`；
  GDCC `RefCounted` 的显式构造继续在需要时包 `gdcc_ref_counted_init_raw(...)`。本阶段不改变 GDCC class
  create/bind/postinitialize 协议。
- `entry.c.ftl` 中 `*_class_create_instance(...)` 继续使用最近 native ancestor 调
  `godot_classdb_construct_object2(GD_STATIC_SN(u8"..."))`，再执行 `godot_object_set_instance(...)`、
  `godot_object_set_instance_binding(...)` 和可选 `godot_Object_notification(...POSTINITIALIZE...)`。
- exact engine `CALL_METHOD` / engine property route 继续只进入 `EngineMethodUsageSession` 和
  `engine_method_binds.h` 中的 `gdcc_engine_call*` helper，不回退到 `godot_<Owner>_<method>` public wrapper。
- 本阶段不恢复 `gdextension-lite`，不改变 `CProjectBuilder` 的 include dir / native input 规则，不把
  `godot_binding.c` 或 stale vendor 处理规则重新打开。
- 本阶段优先不改变 `CompileResult.generatedFiles()`、`outputLinks()` 和 API/CLI 的核心 generated file 契约。
  engine constructor 的按需 wrapper 应先作为 `engine_method_binds.h` 中的模块局部 `static inline` 生成物出现。
  该 header 已由 `entry.h` 无条件 include，当前角色实际是 module-local exact engine helper header，不只是
  method-bind helper 容器。阶段 4 继续沿用这个落点；若未来要迁移到独立 module binding 文件，必须作为后续显式重构处理。

实现计划：

实施状态：

- [x] 已新增 constructor usage buffer/session，并把 `ConstructInsnGen` 的 engine object 分支接入按需登记；
  函数体 render 失败时仍通过函数级 buffer 丢弃未提交的 constructor 使用项。
- [x] 已将 `engine_method_binds.h.ftl` 扩展为同时输出模块实际使用的 constructor wrapper；
  wrapper 使用 `godot_classdb_construct_object(GD_STATIC_SN(...))`，失败时走统一 binding lookup 诊断并返回 `NULL`。
- [x] 已从 `FixedGodotBindings` 移除全量 engine constructor 声明、定义以及每次构造/销毁 `StringName`
  的 `gdcc_fixed_construct_object(...)` 热路径，fixed manifest 继续只来自版本化 `functions()` 清单。
- [x] 已在 `gdcc_bind.h` 增加 `GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(...)`，让
  `engine_method_binds.h.ftl` 用宏生成 accessor 并保留每个 accessor 独立 static cache、primary hash 优先和
  compatibility hash 顺序 fallback；compatibility hash 数组声明也由宏内部持有，模板只传 fallback hash 字面量。
- [x] 已补充阶段 3.2 正反向单元测试、重新生成 checked-in fixed support，并运行 targeted 验证；
  集成测试捕获的 `GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(...)` 宏参数与 `.function_name` 字段名冲突已修复。
  已覆盖 `FixedGodotBindingsTest`、`CConstructInsnGenTest`、`CCodegenTest`、
  `CCodegenEngineMethodUsageSessionTest`、`CCodegenEngineMethodBindHeaderTest`、
  `CProjectBuilderSharedIncludeTest`、`ApiCompilePipelineTest`、`CConstructInsnGenEngineTest`、
  `FrontendLoweringToCProjectBuilderIntegrationTest` 和 `GodotAbiHeaderCompileTest`。

- 新增一个 narrow constructor usage snapshot，复用现有 exact engine method 使用集的函数级 buffer + 成功后
  commit 模式：
  - `CCodegen.generate()` 在渲染 `entry.c.ftl` 前创建 constructor usage session。
  - `generateFuncBody(...)` 为每个函数创建 constructor buffer；函数体完整 render 成功后才 commit。
  - `ConstructInsnGen` 在确认 `classDef instanceof ExtensionGdClass` 且 class 可实例化后登记 `ENGINE_CONSTRUCTOR`
    使用项；不要通过 generated C 文本扫描倒推 constructor 使用。
  - render 失败的函数不得污染 constructor snapshot；顺序使用 `LinkedHashMap` / `LinkedHashSet` 保持首次出现顺序。
- `engine_method_binds.h.ftl` 接收 `usedEngineConstructors`，在 exact engine method helper 之前输出本模块实际需要的
  `static inline godot_<Class> *godot_new_<Class>(void)`：
  - wrapper 内部直接调用 `godot_classdb_construct_object(GD_STATIC_SN(u8"<Class>"))`；
  - lookup / construction 返回 `NULL` 时调用 `gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){ ... })`
    并返回 `NULL`，不能缓存 `NULL`，不能吞掉失败；
  - 不复用 `GodotBindingSupport.sourceBindingMacros()` 中只在 generated runtime `.c` 内可见的
    `GDCC_BINDING_LOOKUP_FAIL_*` 宏；header-level static inline code 应直接使用 `gdcc_binding_lookup_fail(...)`
    或新增同等 header-visible helper；
  - wrapper 名继续是 `godot_new_<Class>()`，使 `ConstructInsnGen` 的调用形状和已有测试锚点保持稳定。
- 同步清理 exact engine method bind accessor 的模板噪声，但不改变调用语义：
  - 在 `gdcc_bind.h` 增加 header-visible 宏，例如 `GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(...)`，
    用来生成 `engine_method_binds.h` 中的 `gdcc_engine_method_bind*` accessor 函数；
  - 宏内部保留每个 accessor 自己的 static `GDExtensionMethodBindPtr` cache，并按 primary hash 后接
    `hash_compatibility` 候选的顺序调用 `godot_classdb_get_method_bind(...)`；
  - `GD_STATIC_SN(...)` 必须在宏展开出的每个 accessor call site 内直接包 owner/method 字面量，不能下沉到
    接收 `const char *owner_text` / `method_text` 的通用 C helper，否则不同 accessor 会共享同一个 macro-static
    `StringName` 槽位并缓存错误名称；
  - lookup miss 仍走 `gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){ ... })`，诊断字段、primary hash 和
    compatibility hash 数组语义与当前模板保持一致；
  - `engine_method_binds.h.ftl` 后续只输出宏调用和 fallback hash 字面量，`gdcc_engine_call*` helper
    继续保留在模板中，以减少大量重复 accessor 函数体对阅读的干扰。
- 从 `FixedGodotBindings` 中移除全量 engine constructor 输出：
  - 删除或停用 `renderHeader()` 中对所有 `api.classes().isInstantiable()` 的 constructor 声明循环；
  - 删除或停用 `appendEngineConstructorDefinitions(...)`；
  - 删除 generated `gdcc_fixed_construct_object(const char *class_name_text)`，或者把它降级为不再被 fixed support 使用的私有实现时同步删除；
  - `FixedGodotBindings.symbols(api)` 继续只代表版本化固定 helper source-list，不再和 engine constructor 全量输出混在一起。
- 保护 `GD_STATIC_SN` 生命周期：
  - 阶段 3.2 的 constructor wrapper 放在 `engine_method_binds.h`，由 `entry.h` include，再由 `entry.c` include 后
    在同一个 translation unit 内使用 `gdcc_string_name.h` 的 header-static registry；`entry.c` 现有
    `deinitialize(...)` 调用 `gdcc_sn_registry_destroy_all()` 能销毁这些 constructor wrapper 初始化过的 static
    `StringName`。
  - `entry.h` 当前 include 顺序是先 `<gdcc_helper.h>`、后 `"engine_method_binds.h"`。现有 `gdcc_helper.h`
    只使用 builtin constructor、Variant/Object conversion、singleton 和 fixed helper，不直接依赖 engine class
    public constructor wrapper；若未来某个 `gdcc/*.h` helper 需要调用 `godot_new_<EngineClass>()`，不能只把该
    wrapper 放在 `engine_method_binds.h`，必须改为 fixed/runtime provided wrapper 或调整 include 边界。
  - 不要在独立 `godot_module_bindings.c` 中直接使用 `GD_STATIC_SN(...)`，除非同时引入该 translation unit 自己的
    deinitialize hook 或把 `gdcc_string_name.h` 的 registry 改成单一外部定义。当前 header-static registry 是
    per-translation-unit 状态，不能假设 `entry.c` 的 cleanup 会销毁其它 `.c` 文件里的静态缓存。
  - 如果未来迁移 constructor wrapper 到 `godot_module_bindings.c`，必须先解决上面的 cleanup 边界。
- 更新工具和扫描器语义：
  - `generate-fixed` 输出的 `godot_fixed_binding.h/.c` 不再包含 `godot_new_Node()` / `godot_new_RefCounted()` 这类
    engine constructor wrapper。
  - `dump-fixed-manifest` 不再出现 engine constructor symbol。
  - `check-fixed` 只检查固定 helper/template 对 fixed source-list 的覆盖，不把“全量 constructor wrapper”当作 provided set。
  - generated C scan（若本阶段新增或扩展）必须把 `engine_method_binds.h` 中的 on-demand constructor wrapper 纳入允许集合，
    确保 `ConstructInsnGen` 生成的每个 `godot_new_<Class>()` 都有同模块 wrapper 定义。

测试计划：

- `FixedGodotBindingsTest`
  - 反向更新 `renderFixedSupportShouldUseFixedRuntimeWrappersAndEngineConstructors()`：
    - 不再期待 `godot_fixed_binding.h/.c` 含有 `godot_new_Node()` / `godot_new_RefCounted()`；
    - 明确断言 generated fixed support 不含 `gdcc_fixed_construct_object`；
    - 保留 `godot_Engine_singleton`、`godot_Object_get/set/call`、`godot_RefCounted_*` 等固定 helper 断言；
    - 保留无 `gdextension-lite` 断言。
  - 增加或调整 manifest 断言，证明 fixed symbol set 只等于版本化 `functions()` 清单。
- `CConstructInsnGenTest`
  - 保留 engine `construct_object` 生成 `godot_new_<Class>()` 的断言；
  - 增加断言证明 fresh engine constructor result 仍按 `OWNED` 路径写入，不发生额外 own；
  - 保留未知类、abstract、not instantiable、类型不匹配和 trimmed class name 负例。
- `CCodegenTest`
  - 增加 generated header 形状断言：
    - 使用 `Node.new()` 的 fixture 在 `engine_method_binds.h` 中生成
      `static inline godot_Node *godot_new_Node(void)`；
    - 未使用的可实例化 engine class 不出现在 `engine_method_binds.h` 中；
    - constructor wrapper 使用 `GD_STATIC_SN(u8"Node")`，不出现
      `godot_new_StringName_with_latin1_chars("Node")` / per-call destroy；
    - engine constructor wrapper 不出现 `godot_classdb_construct_object2(...)`；
    - GDCC `*_class_create_instance(...)` 仍出现 `godot_classdb_construct_object2(GD_STATIC_SN(...))`。
  - 增加失败隔离断言：某个函数 render 失败时，不把该函数中登记的 engine constructor 泄漏到最终
    `engine_method_binds.h`。
  - 保留 `gdextension_entry` / `initialize` / `deinitialize` 顺序断言，尤其是 `gdcc_sn_registry_destroy_all()` 的卸载位置。
- `CConstructInsnGenEngineTest`
  - 保留真实 Godot runtime 构造回归，确认 `construct_object` 的 engine/GDCC/RefCounted 路径都仍能运行。
- `CCodegenEngineMethodUsageSessionTest` / `CCodegenEngineMethodBindHeaderTest`
  - 保留 exact engine method/property 按需生成断言，确认 constructor usage snapshot 不污染 exact method usage
    snapshot，只是在同一个 `engine_method_binds.h` 中新增独立 constructor wrapper 区块。
  - 增加 `gdcc_bind.h` accessor 宏使用断言：generated `engine_method_binds.h` 不再展开大段重复的
    `gdcc_engine_method_bind*` lookup 函数体，而是输出 compatibility hash 数据和宏调用；运行时 lookup 行为、
    failure 诊断和 compatibility hash 顺序保持不变。
- `CProjectBuilderSharedIncludeTest` / API compile pipeline tests
  - 若本阶段保持 constructor wrapper in `engine_method_binds.h`，这些测试不应因为阶段 3.2 改动而改变 generated file 数量、
    output links、include dirs 或 native input。
  - 只需保留阶段 3 的 `godot_binding.c` / `godot` include root / stale vendor 负向断言。

建议 targeted 验证：

```bash
script/run-gradle-targeted-tests.sh --tests FixedGodotBindingsTest,CConstructInsnGenTest,CCodegenTest
script/run-gradle-targeted-tests.sh --tests CCodegenEngineMethodUsageSessionTest,CCodegenEngineMethodBindHeaderTest
script/run-gradle-targeted-tests.sh --tests CProjectBuilderSharedIncludeTest
script/run-gradle-targeted-tests.sh --tests CConstructInsnGenEngineTest
script/run-gradle-targeted-tests.sh --tests FrontendLoweringToCProjectBuilderIntegrationTest
```

验收：

- `godot_fixed_binding.h/.c` 不再全量包含 engine class constructor wrapper。
- 使用 engine constructor 的模块仍生成并调用 `godot_new_<Class>()`，但该 wrapper 只在实际使用的模块中出现。
- `godot_new_<Class>()` wrapper 内部使用 `godot_classdb_construct_object(GD_STATIC_SN(u8"<Class>"))` 或等价 cached
  `StringName`，不再每次调用构造/销毁 `StringName`。
- `godot_new_<Class>()` wrapper 内部不使用 `godot_classdb_construct_object2(...)`；`construct_object2` 仍只属于
  GDCC `*_class_create_instance(...)` 协议。
- constructor wrapper lookup / construction failure 走统一 binding lookup failure 诊断，不继续调用空指针、不用默认对象掩盖失败。
- `engine_method_binds.h` 中的 `gdcc_engine_method_bind*` accessor 由 `gdcc_bind.h` 的宏生成；宏展开后仍保留每个
  accessor 独立 static cache、primary hash 优先 lookup、compatibility hash 顺序 fallback 和统一 failure 诊断。
- `ConstructInsnGen` 的 engine/GDCC/RefCounted 分支和 ownership 语义不变。
- 本阶段不改变 runtime include / native input，不恢复 vendor binding，不改变无 constructor module 的核心 3 个 generated file 契约。

### 阶段 4：接入模块级 Godot wrapper 使用集

本阶段只把仍随模块变化的非 interface `godot_*` 调用纳入可验证的使用集生成。interface、builtin、utility、fixed
runtime wrapper 已在前置阶段作为 provided set 发布，不再由模块使用集决定是否生成。module-local session 必须复用
`EngineMethodUsageSession` 的函数级 buffer + 成功后 commit 模式，不能用全局单例或 generated C 扫描自动补 wrapper。
本阶段不新增模块级 C translation unit：module-local wrapper 作为 `engine_method_binds.h` 中的 header-only
`static inline` section 输出，并继续通过 `entry.h` 进入唯一的模块级 `entry.c`。

实施状态（2026-05-26）：

- [x] 已重新确认 `AGENTS.md` 的并行子代理、定向测试、文档同步和“不新增 build/config 修改”约束；阶段 4
  调研已由 `gpt-5.4-mini` 子代理并行覆盖相关文档、C 后端生成链路、build/API/CLI 输出契约和测试缺口，并已关闭完成的子代理。
- [x] 已把 usage collector 统一迁移到 `gd.script.gdcc.backend.c.gen.binding.usage`，新增总 API
  `GodotBindingUsageSession` / `GodotBindingUsageBuffer`，并用 `AbstractUsageSession` / `AbstractUsageBuffer`
  抽出函数级 buffer、成功后 commit、稳定顺序 snapshot 的公共骨架。
- [x] 已将原 module-local wrapper spec 收窄为 `ModuleLocalGodotBinding` sealed API，当前只保留 `Singleton`
  与 `ClassConstant` 两条路径；已删除没有生产使用点且容易误导维护的 generic `CLASS_METHOD` 类别。
- [x] 已将 module-local singleton/class constant wrapper 的 C 输出逻辑放入 `engine_method_binds.h.ftl`，
  Java 侧只传递 `ModuleLocalGodotBinding` 列表；已用 `ModuleLocalGodotBindingTemplateTest` 分别覆盖空 section、
  singleton lookup helper、class constant literal helper，并负向断言二者不会走 class method bind lookup。
- [x] 已避免 `template_451` 生成路径使用 C designated initializer；lookup failure context 和 method info
  使用 `{ 0 }` 初始化后逐字段赋值，callback 三元结构使用位置初始化，规避当前 `CCodeFormatter` 对 `.field = ...`
  结构体字面量的格式化缺口。
- [x] 已用 `GodotBindingUsageSessionTest` 覆盖 provided 不生成、singleton/class constant first-use 顺序、失败 buffer
  不泄漏、未知 non-provided 调用 fail-fast、同 C function name 冲突、同 canonical constant 值漂移 fail-fast，以及跨
  buffer commit 的 C name 冲突。
- [x] 已将 `GodotBindingGeneratedCScanner` 移到 `src/test/java`，作为单元测试辅助扫描器；`CCodegen.generate()`
  不再运行该校验。它只在测试中验证 `godot_*` 是否属于 provided、module-local、本地定义、GDCC helper/type
  白名单，不参与补登记；已用 `GodotBindingGeneratedCScannerTest` 覆盖正向白名单、本地指针返回 wrapper
  定义和未知 wrapper 失败。
- [x] 已将 `CCodegen.generate()`、`GenerateRenderFacade` 和 `CBodyBuilder` 改为只传递总的
  `GodotBindingUsageSession` / `GodotBindingUsageBuffer` API；engine method、engine constructor、module-local binding
  三套内部 collector 不再作为生成器调用链参数泄漏。
- [x] 已把 module-local section 传入 `engine_method_binds.h.ftl`；`generate()` 仍只返回 `entry.c`、
  `engine_method_binds.h`、`entry.h`，未新增 `godot_module_bindings.h` / `godot_module_bindings.c`。
- [x] 已将 `CBodyBuilder.callVoid(...)` / `callAssign(...)` 接入 provided/module-local 调用校验；`appendLine(...)`
  和 `appendRaw(...)` 仍不做隐式解析，直接拼接的残余 `godot_*` 由显式登记和生成后扫描共同约束。
- [x] 已补强 `CCodegenTest` / `CCodegenEngineMethodBindHeaderTest` 的三文件、module-local section
  和当前 helper 输出锚点；旧 module-local 独立文件名不再作为测试专项负向断言。当前已通过
  `script/run-gradle-targeted-tests.sh --tests GodotBindingUsageSessionTest,ModuleLocalGodotBindingTemplateTest,GodotBindingGeneratedCScannerTest,CCodegenEngineMethodUsageSessionTest,CCodegenEngineMethodBindHeaderTest,CCodegenTest`。
- [x] 已通过 build/API/CLI 和 binding generator 回归：
  `script/run-gradle-targeted-tests.sh --tests CProjectBuilderSharedIncludeTest,ApiCompilePipelineTest,ApiCompileArtifactLinkTest,ApiCompileDiagnosticsTest,ApiRecompileArtifactRefreshTest,GdccCommandInputTest,FixedGodotBindingsTest,GodotBuiltinGeneratorTest,GodotUtilityGeneratorTest,GodotInterfaceGeneratorTest,GodotBindingToolAbiSupportTest`；
  最终 `./gradlew classes --no-daemon --info --console=plain` 和 `git diff --check` 通过。

阶段 3.2 已把 engine class constructor wrapper 从 fixed runtime 全量预生成中移出，并通过 constructor usage snapshot
在 `engine_method_binds.h` 中按需生成 `static inline godot_new_<Class>()`。本阶段默认不重新处理 engine constructor；
也不把 constructor wrapper 迁移到新的 module binding 文件。若未来要拆出独立 module binding 文件，必须作为
单独重构先解决 `class_library`、`GD_STATIC_SN` / `GD_STATIC_S` header-static registry 和 deinitialize cleanup 边界。

新增类型：

- `binding.usage.GodotBindingUsageSession`
  - 由 `CCodegen.generate()` 创建，是生成器调用链唯一需要传递的 usage session API。
  - 构造时读取 interface/builtin/utility/fixed symbol snapshot，把这些 symbol 放入 module-local provided set。
  - 对外暴露 `newFunctionBuffer()` / `commit(buffer)`、`engineMethods()`、`engineConstructors()`、
    `moduleLocalBindings()`；`providedCFunctionNames()`、`moduleLocalCFunctionNames()` 仅用于 focused tests。
  - 内部拥有 exact engine method、engine constructor、module-local binding 三套 session。
- `binding.usage.GodotBindingUsageBuffer`
  - 函数级临时记录器，同时提供 `recordEngineMethodCall(...)`、`recordEngineConstructor(...)`、
    `recordModuleLocalGodotBinding(...)`、`recordGodotCall(...)`。
  - 函数体、property init apply body、模板 facade 各自使用独立 buffer。
  - 对应 render 成功后才 commit；失败时丢弃，不污染模块级 session。
- `binding.usage.AbstractUsageSession` / `binding.usage.AbstractUsageBuffer`
  - 仅抽出有序 map、函数级 snapshot、成功后 commit 这些重复骨架。
  - 具体冲突规则仍留在 engine/module-local 专用 collector 中，避免把业务语义塞进抽象父类。
- `GodotBindingSymbol`
  - 预生成/fixed/module-local wrapper 共享的结构性 C surface 描述。
  - 当前 module-local key 使用 family、owner、name、C function name 和 signature；exact engine method lookup hash
    继续留在 `ResolvedMethodCall` / engine method usage 路径中。
- `ModuleLocalGodotBinding`
  - sealed API，仅包含 `Singleton` 与 `ClassConstant`。
  - `Singleton` 对应 `godot_global_get_singleton(...)` helper；`ClassConstant` 对应 literal return helper。
  - generic class method public wrapper 不再作为阶段 4 类别存在。
- `engine_method_binds.h.ftl`
  - 根据 session snapshot 输出 module-local header-only wrapper section。
  - 只输出不在 provided set 中的 module-local singleton/class constant。
  - singleton lookup wrapper 必须缓存非空结果并在 lookup miss 时走统一 failure 诊断；class constant wrapper 只输出 literal helper。
  - lookup failure context 不使用 `.field = ...` designated initializer，保持 `CCodeFormatter` 可处理。
  - 后续若要拆成 engine class / engine property / class constant 小节，
    也必须保持在 `entry.h` include 链路内，不得生成新的 module-local `.c`。

session 传递链路：

1. `CCodegen.generate()` 创建一个 `GodotBindingUsageSession` 和一个模板级 `GodotBindingUsageBuffer`。
2. 构造 `GenerateRenderFacade(..., templateUsageBuffer)`，让模板能显式登记残余 module-local wrapper，但不直接写入 module session。
3. `GenerateRenderFacade.generateFuncBody(classDef, func)` 调用 `CCodegen.generateFuncBody(classDef, func, usageSession)`。
4. `generateFuncBody(...)` 从总 session 创建一个 `GodotBindingUsageBuffer`。
5. `new CBodyBuilder(helper, clazz, func, usageBuffer)`，函数体内所有 `CInsnGen` 通过 builder 校验 provided wrapper，并只登记残余 module-local wrapper。
6. 函数体完整 render 成功后，调用 `usageSession.commit(usageBuffer)`；commit 内部分别合并 engine method、
   engine constructor 和 module-local binding。
7. `CCodegen.generatePropertyInitApplyBody(...)` 接收同一个 `GodotBindingUsageSession`，为 apply body 创建 buffer 并在成功后 commit。
8. `entry.c` 渲染成功后 commit 模板级 buffer，再取得 `usageSession.engineMethods()`、`usageSession.engineConstructors()` 和
   `usageSession.moduleLocalBindings()`；此时函数体、property init apply body 和 `entry.c.ftl` 内的登记都已完成。
9. `engine_method_binds.h.ftl` 根据 `usageSession.moduleLocalBindings()` 渲染 module-local wrapper section；
   snapshot 为空时输出空 section。
10. 渲染 `engine_method_binds.h`，把 module-local wrapper section 放在 engine constructor wrapper 与 exact engine helper
    附近，继续作为模块级 helper header。
11. 渲染 `entry.h`；include 关系保持 `#include <godot_binding.h>`、`#include <gdcc_helper.h>`、
    `#include "engine_method_binds.h"`，不新增 `godot_module_bindings.h`。
12. 最后仍只把 `entry.c`、`engine_method_binds.h`、`entry.h` 作为 `GeneratedFile` 返回。

生成文件集合测试必须在本阶段锁定“不新增文件”：

- `CCodegenEngineMethodBindHeaderTest`、`CCodegenTest` 中的核心文件集合断言继续保持
  `entry.c`、`engine_method_binds.h`、`entry.h`。
- 需要 module-local wrapper 的 fixture 必须断言 wrapper 出现在 `engine_method_binds.h` 的 module-local section，
  并通过 `CCodegen.generate()` 的精确三文件列表证明没有其它 generated file。
- API / CLI 层通过 `CompileResult.generatedFiles()` 和 `outputLinks()` 暴露 generated files；阶段 4 不改变这些列表。
  `ApiCompilePipelineTest`、`ApiCompileArtifactLinkTest`、`ApiCompileDiagnosticsTest`、`ApiRecompileArtifactRefreshTest`、
  `GdccCommandInputTest` 应保持核心 3 文件和 artifact link 顺序稳定。
- build 层测试必须确认 native compiler input 不变：
  - `generatedFiles()` 仍只包含核心 3 文件。
  - `compiler.cFiles()` 仍只包含 `projectPath/entry.c` 和 `<includeRoot>/godot/godot_binding.c` 这两类输入。
  - `compiler.includeDirs()` 仍只能包含 `includeRoot/gdcc` 与 `includeRoot/godot`，不得为 module-local header 添加 `projectPath`。
- `CCodegenEngineMethodBindHeaderTest` 的 exact engine method 语义断言必须保留：
  builtin / dynamic / GDCC local call 不进入 `engine_method_binds.h`，exact engine route 仍只生成 `gdcc_engine_call*` helper，
  不回退 `godot_<Owner>_<method>` public wrapper。

provided wrapper 不登记生成：

- utility function wrapper
  - `CallGlobalInsnGen` 通过 `CGenHelper.resolveUtilityCall(...)` 解析到 `godot_<utility>`。
  - 由全量 `godot_utility.h/.c` 提供，不进入 module-local session。
- builtin constructor/copy/destroy/pack/unpack/member/index/key/operator wrapper
  - 覆盖 `CBuiltinBuilder`、`ConstructInsnGen`、`NewDataInsnGen`、`PackUnpackVariantInsnGen`、`OperatorInsnGen`、
    `IndexLoadInsnGen`、`IndexStoreInsnGen`、builtin property load/store、default value 字符串等路径。
  - 由全量 `godot_builtin.h/.c` 提供，不进入 module-local session。
- fixed runtime wrapper
  - 覆盖 `godot_Object_call`、`godot_Object_get`、`godot_Object_set`、`godot_Object_notification`、
    `godot_Engine_singleton`、`godot_ClassDB_singleton` 等版本化固定清单符号。
  - 由 `godot_fixed_binding.h/.c` 提供，不进入 module-local session。

module-local 登记位置必须按类型明确落点：

- engine class constructor
  - 阶段 3.2 已先行处理：`ConstructInsnGen` 的 engine object constructor 分支登记 constructor usage snapshot，
    `engine_method_binds.h` 按需输出 `static inline godot_new_<Class>()`。
  - 阶段 4 只把 constructor usage 纳入总 `GodotBindingUsageSession` 的内部子 collector，不把 constructor 重新纳入
    module-local `ModuleLocalGodotBinding` 路径，也不改变阶段 3.2 的输出位置。
  - public constructor wrapper 继续使用 `godot_classdb_construct_object(...)` 和 cached `StringName`，
    不得用 `godot_classdb_construct_object2(...)` 直接替换。
- engine class method/property public wrapper
  - 使用点：exact engine route 之外仍需要 engine public wrapper 的 dynamic/object bridge，
    `LoadPropertyInsnGen` / `StorePropertyInsnGen` 的 engine property helper。
  - 当前状态：生产路径没有这类 public wrapper 登记点；`CLASS_METHOD` generic module-local 类别已移除，避免让维护者误以为
    exact engine method 会回退到 `godot_<Owner>_<method>` public wrapper。
  - 后续若重新引入，必须先明确哪条非 exact 路径会发出 public wrapper call，再新增单独类型化路径和测试。
  - method wrapper 身份 metadata：`classes[].methods[].arguments/return_value/is_vararg/is_static`。
    engine property helper 输入 metadata 来自阶段 0 resolver 输出的 `getter/setter/type/index`；method
    `hash` / `hash_compatibility` 只进 lookup metadata。
  - 约束：exact `ENGINE` route 只进入 `EngineMethodUsageSession`，不得登记 `godot_<Owner>_<method>` public wrapper。
- singleton getter
  - 使用点：未来 `LoadStaticInsnGen` 或 global access path；固定 helper 使用的 singleton 必须走 `FixedGodotBindings` 当前版本数据。
  - 登记方式：脚本路径使用时由 `LoadStaticInsnGen` 或 resolver 显式登记。
  - metadata：`singletons[]`。
- class enum/int constant helper
  - 使用点：当前 `LoadStaticInsnGen` 多数可直接 literal 化；若生成 `godot_<Class>_<Constant>()` helper，必须登记。
  - 登记方式：literal 路径不登记，helper 路径登记 `ModuleLocalGodotBinding.ClassConstant`。
  - metadata：`classes[].constants[]` / `classes[].enums[]`。

`CBodyBuilder` 的改造规则：

- `callVoid(...)` / `callAssign(...)`
  - 对 `funcName.startsWith("godot_")` 的调用统一调用 usage buffer 的 `recordGodotCall(...)`。
  - `recordGodotCall(...)` 先查询 interface/builtin/utility/fixed provided set；provided wrapper 只做校验，不进入 module-local output。
  - 其余名字必须已经由同一 emit 分支显式登记为 `ModuleLocalGodotBinding`，否则 fail-fast。
- `appendLine(...)` / `appendRaw(...)`
  - 不做隐式解析。
  - 任何直接拼出的残余 module-local `godot_*` 必须在同一 emit 分支显式登记。
  - builtin/utility/fixed 直接字符串由 provided set 和 generated C 扫描验证。
- `renderDefaultValueExpr(...)`
  - 可以继续返回 `godot_new_*` 文本，但这些符号必须由全量 builtin provided set 覆盖。
  - generated C 扫描负责验证默认值 wrapper 已在 provided/module-local set 中；不在集合内时 fail-fast，不自动补生成。
- `CGenHelper`
  - 继续只负责稳定命名和 metadata 查询。
  - 不持有 session，避免隐藏共享状态。

模板登记规则：

- `GenerateRenderFacade` 提供类型化 `recordModuleLocalGodotBinding(ModuleLocalGodotBinding binding)` API。
  - 该 API 只能写入模板级 `GodotBindingUsageBuffer`；`CCodegen.generate()` 在 `entry.c` 渲染成功后统一 commit。
  - 不允许把它绑定到 `GodotBindingUsageSession` 的立即提交入口，否则失败渲染会绕过事务边界污染模块级 usage。
- `entry.c.ftl`
  - interface wrapper 如 `godot_classdb_register_extension_class5`、`godot_object_set_instance` 由 interface set 忽略。
  - `godot_print` 来自全量 utility，`godot_new_Variant_with_String` / `godot_Variant_destroy` 来自全量 builtin，不登记生成。
- `entry.h.ftl`
  - typed Array/Dictionary preflight、pack/unpack、destroy、operator helper 来自全量 builtin/fixed provided set，不登记生成。
  - 不新增 `#include "godot_module_bindings.h"`；模板本身不登记新的 module-local wrapper，避免“渲染 header
    后才知道是否需要 header”的循环。
- `engine_method_binds.h.ftl`
  - `godot_classdb_get_method_bind`、`godot_object_method_bind_call`、`godot_object_method_bind_ptrcall` 属于全量 interface。
  - exact vararg helper 的参数 pack、返回 unpack、临时 destroy 来自全量 builtin provided set。
  - 直接根据 `moduleLocalBindings` 列表输出 module-local wrapper section，不经由 Java 侧 renderer。

生成逻辑：

- wrapper name 继续按 `doc/gdcc_runtime_lib.md` 的 `godot_` 命名约定；生成集合、过滤和合成只服从本文的 GDCC contract。
- constructor index、variant type、operator enum、class/method/property 名全部来自 `ExtensionAPI` metadata 或
  Godot `extension_api_dump.cpp` 输出的对应字段；engine property accessor method 名必须来自 raw `getter` / `setter`，
  不从 property 名拼接推导。
- exact engine lookup hash 同样来自 `ExtensionAPI` metadata，但只存入 `ResolvedMethodCall` / engine method usage 路径，
  不进入 `ModuleLocalGodotBinding`。
- generated wrapper 的参数和返回 carrier 生成必须保留 initialized / const / uninitialized destination 区别；
  module-local engine method/property wrapper 调用 `godot_object_method_bind_call` 时，返回 `Variant` carrier 也按
  `GDExtensionUninitializedVariantPtr` out-param 处理；不得先构造 nil `Variant` 再把同一地址作为返回槽传入。
- 使用 `GD_STATIC_SN` / `GD_STATIC_S` 或等价静态缓存，避免每次调用重复构造 name carrier。
  因为 module-local wrapper section 位于 `engine_method_binds.h` 并最终进入 `entry.c`，这些缓存必须落在
  entry translation unit 的 header-static registry 内，由现有 `deinitialize(...)` cleanup 覆盖。
- 对缺失 metadata 的 wrapper fail-fast，不静默发空实现。
- 对 lookup 返回空指针的 wrapper fail-fast，不缓存 `NULL`，不继续调用，不通过返回默认值掩盖 metadata/hash 冲突。
- exact engine method bind lookup 候选必须由 primary hash + `hash_compatibility` 组成；utility function 没有候选集合，
  只允许 primary hash 严格匹配。当前 module-local singleton/class constant 路径不执行 method bind lookup。
- module-local wrapper section 必须依赖 `entry.h` 既有 include 顺序复用 ABI、interface、builtin、utility、fixed
  wrapper 声明，不得自行引入新的 generated header。

防重防漏验收：

- 除固定全量的 `godot_interface.h/.c`、`godot_builtin.h/.c`、`godot_utility.h/.c` 和 fixed source-list 输出外，
  module-local 生成产物只包含本模块实际使用且未 provided 的 wrapper。
- 脚本调用预生成/固定 wrapper，例如 `godot_new_Variant_nil` 或 `godot_print` 时，
  `engine_method_binds.h` 的 module-local section 不生成同名函数。
- 脚本调用未 provided 的 engine public wrapper / property helper / class constant helper 时，`engine_method_binds.h`
  的 module-local section 正常生成对应 `static inline` wrapper。
- 同一 canonical symbol 重复使用只保留首次稳定顺序。
- 同一 C function name 对应不同结构性 signature/ABI 时生成失败，而不是后者覆盖前者。
- 同一 canonical symbol 对应不兼容 lookup hash / `hash_compatibility` 时生成失败；兼容 hash 变化不能制造第二个 wrapper symbol。
- lookup miss 诊断必须能区分 metadata 缺失、hash 不兼容、Godot runtime 版本不匹配和 header interface 不存在；不能退化成普通空指针崩溃。
- generated C 文本扫描必须通过：所有 `godot_*` 调用都属于 interface set、builtin set、utility set、fixed set、module-local generated set、
  GDCC-owned macro/helper 白名单或类型名白名单。
- 扫描只做 fail-fast 验收，不负责发现 wrapper、不负责补登记、不负责生成。
- `CCodegen.generatePropertyInitApplyBody(...)` 中出现的 constructor/copy/destroy wrapper 被全量 builtin provided set 覆盖；
  其中出现的 engine property/class constant helper 能进入 session snapshot。
- `entry.c.ftl`、`entry.h.ftl`、`engine_method_binds.h.ftl` 中的非 interface builtin/utility/fixed wrapper 均被 provided set 覆盖。
- `CCodegen.generate()` 在 module-local snapshot 非空时仍只返回 `entry.c`、`engine_method_binds.h`、`entry.h`。
- `CProjectBuilder` 在 module-local snapshot 非空时仍只把 `entry.c` 和 `<includeRoot>/godot/godot_binding.c`
  送入 native compiler。
- 不再出现 `#include <gdextension-lite.h>`、`gdextension_lite_initialize`、`implementation-macros.h`。
- exact engine `CALL_METHOD` 测试确认仍只使用 `gdcc_engine_call*` helper。

### 阶段 5：删除 gdextension-lite vendor 包并整理全量文档事实源

本阶段不是只跑一次 `rg` 的收尾工作。完成阶段 0-4 后，`doc/module_impl` 下每一份文档都必须被归类并检查：

- **现行合同文档**：保留为当前实现合同，清除旧 vendor 作为当前事实源的表述。
- **历史来源文档**：明确标注为历史命名来源、迁移前行为或审计记录，不再承担生成规则职责。
- **引用型文档**：只引用统一事实源和测试锚点，不重复维护实现规则表。

#### 阶段 5 执行状态

- [x] 已重新确认 `AGENTS.md` 的并行子代理、定向测试、文档同步和阶段 5 验收要求。
- [x] 已使用 `gpt-5.4-mini` 子代理并行完成 backend 文档、frontend/scope 文档、API/CLI/common 文档、
  测试文案和构建输入清理点的只读调研。
- [x] 5A 已完成：删除 `src/main/c/codegen/include_451/gdextension-lite.zip`；`CProjectBuilder`
  不再自动删除旧 vendor 子树 `<includeRoot>/gdextension-lite`，旧内容由迁移操作手动清理；其 native
  输入规则仍为本轮 generated `.c` 加 `<includeRoot>/godot/godot_binding.c`；`ResourceExtractor`
  保持“抽取/覆盖但不删除无关文件”的工具 contract。
- [x] 5A 已补充并运行定向测试锚点：
  `script/run-gradle-targeted-tests.sh --tests CProjectBuilderSharedIncludeTest,ApiCompilePipelineTest,ResourceExtractorTest`。
- [x] 5B backend 文档已清理：旧根层历史命名文档已改名为 `doc/gdcc_runtime_lib.md`，
  并扩展为当前 runtime helper、binding generator、命名和 fixed binding 注册事实源；
  `doc/gdcc_c_backend.md` 补充 runtime library 事实源链接与 `godot_initialize_interface(...)` 入口初始化合同；
  backend active contract 文档不再把旧 vendor wrapper 写成当前事实源。
- [x] 5C frontend/scope 文档分类记录已写入；`runtimeName` / 三名模型 / raw metadata 命中均为明确禁止项或历史说明。
- [x] 5D API / CLI / common 文档分类记录已写入，三者均保持 current-contract。
- [x] 5E 源码注释、模板注释和测试文案已清理；`src/main` / `src/test` 不再把旧 vendor
  文件名、旧初始化函数、旧 macro header 或旧 module-local generated file 名称作为当前事实源或专项负向断言。
  `ApiCompilePipelineTest` 与 `CProjectBuilderSharedIncludeTest` 仅保留 stale vendor fixture 常量，用于证明脏工作区残留
  不会进入 native compiler input。
- [x] 5F 已完成 `rg` 验收：旧 module-local 文件名在 `src/main` / `src/test` 中无命中；旧 vendor 命中仅保留在
  stale vendor fixture 常量、本迁移计划、runtime library 历史说明或明确标注“迁移前/历史”的说明中。
- [x] 阶段 5 targeted tests 已通过：
  - `script/run-gradle-targeted-tests.sh --tests ApiCompilePipelineTest,CProjectBuilderSharedIncludeTest,CCodegenTest,CCodegenEngineMethodBindHeaderTest,GdccCommandInputTest,GodotInterfaceGeneratorTest,GodotBindingToolAbiSupportTest,FixedGodotBindingsTest,ModuleLocalGodotBindingTemplateTest`
  - `script/run-gradle-targeted-tests.sh --tests CProjectBuilderSharedIncludeTest,ResourceExtractorTest`
  - `script/run-gradle-targeted-tests.sh --tests CProjectBuilderSharedIncludeTest,ResourceExtractorTest,ApiCompilePipelineTest,GdccCommandInputTest,CCodegenTest,CCodegenEngineMethodBindHeaderTest,FixedGodotBindingsTest,GodotInterfaceGeneratorTest,GodotBindingToolAbiSupportTest,ModuleLocalGodotBindingTemplateTest`
  - `script/run-gradle-targeted-tests.sh --tests CGenHelperTest,CBodyBuilderPhaseBTest,CBodyBuilderPhaseCTest,CConstructInsnGenTest,CallGlobalInsnGenTest,CallMethodInsnGenTest,MethodResolverParityTest,EngineMethodSymbolKeyTest,CPackUnpackVariantInsnGenTest,COperatorInsnGenTest,IndexLoadInsnGenTest,IndexStoreInsnGenTest,ApiCompileArtifactLinkTest,ApiCompileDiagnosticsTest,ApiRecompileArtifactRefreshTest,GdccCommandTaskTest`
- [x] 已运行 `git diff --check`；IDE problem scan 仅报告既有未使用 accessor / 测试辅助参数等 warning，
  无本阶段新增错误。

#### 5A：vendor 资源、构建输入和 stale include 卫生

当以上 wrapper 覆盖和测试通过后，删除或确认已删除：

- `src/main/c/codegen/include_451/gdextension-lite.zip`
  - 若为了阶段 3 避免盲目抽取旧 vendor，该删除已经前移到阶段 3，本阶段只验证资源不存在并清理旧事实源。

同步清理：

- `CProjectBuilder`
  - 保留阶段 3 的显式输入规则：只编译本轮 generated `.c` 与 `<includeRoot>/godot/godot_binding.c`。
  - 不在 `initProject` / `buildProject` 的 include 准备阶段自动删除
    `<includeRoot>/gdextension-lite`；旧目录如需清理，由迁移操作手动删除。
  - 仍要通过 stale vendor 测试证明：即使旧文件被用户或旧版本构建留下，native compiler input 也不会回退到
    `gdextension-lite-one.c`。
- `ResourceExtractor`
  - 保持“抽取/覆盖但不删除旧文件”的工具 contract；阶段 5 不把它升级成 include root 同步器，
    也不让 `CProjectBuilder` 代替用户执行旧目录删除。
  - 文档和测试不得把“资源包不再携带旧 zip”误写成“目标 include root 会自动清空旧文件”。

#### 5B：根层文档与 backend 文档清理

- `doc/gdcc_runtime_lib.md`
  - 由旧根层历史命名文档改名而来；不再作为历史命名备忘录，而是当前 GDCC runtime library
    与 binding wrapper 事实源。
  - 必须覆盖 runtime helper 文件职责、binding generator 行为、后端 C 函数命名约定、默认 fixed binding 分类，
    以及新增 fixed binding / handwritten `godot_*` helper 的注册流程。
  - 保留 `gdextension-lite` 仅作为迁移前历史说明，不能作为当前生成或编译依赖。
- `doc/gdcc_c_backend.md`
  - 将 “matches gdextension-lite wrapper behavior” 改成描述 GDCC 自有 Godot binding wrapper 的 ABI 行为。
  - 将 “gdextension-lite exposes the helper” 这类默认值/constructor 事实源改成 GDCC builtin wrapper contract
    或版本化 compatibility helper。
  - 更新 entry lifecycle 小节，说明 entry 初始化调用 `godot_initialize_interface(...)`，不再提旧初始化函数作为当前路径。
- 必须重写或历史化的 backend 文档：
  - `doc/module_impl/backend/builtin_builder_implementation.md`
    - 作为 GDCC builtin constructor naming / materialization / typed container 构造的当前事实源；
      不能继续把 gdextension-lite 当现行构造器生成规则。
  - `doc/module_impl/backend/implicit_conversion_implementation.md`
    - 将 `CVectorIToVectorIntrinsic` 的 “gdextension-lite constructor conversion” 改为 generated builtin constructor /
      `CBuiltinBuilder` / `godot_builtin` contract。
  - `doc/module_impl/backend/index_insn_implementation.md`
    - 第 2 节“外部 API 契约”不能再写成 gdextension-lite；改成 GDCC Godot binding support、全量 builtin/utility/fixed
      provided set 和 Variant indexed/keyed wrapper contract。
    - 删除或历史化 `tmp/inspect_gdlite/**` 这类旧检查路径；验收锚点改为 `godot_binding` / `godot_builtin`
      生成物和 runtime integration。
  - `doc/module_impl/backend/engine_method_bind_implementation.md`
    - exact engine route 已经是当前合同；“其他路径仍消费 gdextension-lite wrapper ABI debt” 这类过渡句必须删除，
      或明确标为迁移前 debt。
  - `doc/module_impl/backend/call_method_implementation.md`
    - 将 “gdextension-lite public wrapper 不再是事实来源” 收束成历史注记；当前合同只写
      exact route、dynamic fallback 和 module-local wrapper 分工。
  - `doc/module_impl/backend/builtin_builder_implementation.md`
    - 保持当前 API 名、测试锚点和 `CBuiltinBuilder` helper 合同与源码一致。
- 逐份确认无需语义改写但必须扫描的 backend 合同：
  - `assign_insn_implementation.md`
  - `backend_ownership_lifecycle_contract.md`
  - `call_global_implementation.md`
  - `cbodybuilder_implementation.md`
  - `construct_array_implementation.md`
  - `explicit_c_inheritance_layout_contract.md`
  - `lifecycle_instruction_restriction.md`
  - `load_static_implementation.md`
  - `load_store_property_implementation.md`
  - `operator_insn_implementation.md`
  - `typed_array_abi_contract.md`
  - `typed_dictionary_abi_contract.md`
  - `variant_abi_contract.md`
  这些文档若仍出现 `gdextension-lite`、`gdextension_lite_initialize`、`<gdextension-lite.h>`、
  `gdextension-lite-one.c`、`shared-include/gdextension-lite` 或 `tmp/inspect_gdlite`，必须归类为历史说明或改写。

#### 5C：frontend 文档事实源收口

frontend 文档清理的核心不是把 backend 生成细节复制进去，而是把事实源层级统一成：

1. Godot raw exported metadata / header。
2. GDCC normalized publication layer。
3. frontend / scope / lowering / backend downstream consumer。

逐份整理要求：

- metadata、property、call lowering 强影响文档：
  - `frontend_exact_call_extension_metadata_contract.md`
    - 保持 shared resolver publication 是 exact route 唯一事实源；新 metadata family 只能经 normalization 进入。
    - 清理 `gdextension-lite` class-method wrapper ABI debt 的当前口吻，改成迁移前 runtime anchor 限制或删除。
  - `frontend_builtin_property_access_implementation.md`
    - 明确 builtin property 只消费 normalized `members -> PropertyInfo` surface；
      engine property 的 raw `getter/setter/index` 属于 engine metadata normalization，不由 frontend 回扫 JSON。
  - `frontend_dynamic_call_lowering_implementation.md`
    - dynamic route 继续复用普通 call publication，result type 只来自 call anchor 的 `expressionTypes()`。
  - `frontend_engine_virtual_override_implementation.md`
    - override 判定只依赖共享 metadata 和 `ClassRegistry` virtual lookup；backend lookup hash 不是 frontend 真源。
  - `frontend_builtin_constructor_variant_argument_plan.md`
    - builtin constructor special route 继续和 ordinary conversion matrix 并列；不得把 special route 混进 matrix。
  - `frontend_property_init_lowering_implementation.md`
    - property init helper、constructor-time apply 和 first-write 语义只消费已完成 frontend facts。
  - `frontend_void_call_result_behavior.md`
    - statement-position void call 仍不发 result slot；value-required void call 仍 fail-fast。
  - `frontend_chain_binding_expr_type_implementation.md`
    - `resolvedMembers()`、`resolvedCalls()`、`expressionTypes()` 的 published fact key-space 必须和新的 metadata surface 对齐。
  - `frontend_type_check_analyzer_implementation.md`
    - type-check 只消费已发布 typed fact，不维护平行 conversion 或 metadata rewrite 规则。
  - `frontend_implicit_conversion_matrix.md`
    - 只维护 ordinary typed boundary；constructor、property getter/setter/index 和 dynamic call 不写成 matrix 扩面。
  - `frontend_lowering_(un)pack_implementation.md`
    - Pack/Unpack materialization 继续复用 shared typed-boundary helper，constructor special route 不能反向污染普通 boundary。
  - `frontend_lowering_cfg_pass_implementation.md`
    - 清理“engine class object construction 直调 gdextension-lite `godot_new_XXX()`”这类当前事实表述。
    - CFG item、writable payload、void no-result 继续消费已发布 facts，不重推语义。
  - `frontend_top_binding_analyzer_implementation.md`
    - builtin / utility / global enum / global constant / type-meta 的分流规则只在 top binding 侧定义，不在后续 analyzer 重复编码。
- class name、type resolver、scope 口径文档：
  - `gdcc_facing_class_name_contract.md`
  - `inner_class_implementation.md`
  - `runtime_name_mapping_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `scope_type_resolver_implementation.md`
  - `scope_analyzer_implementation.md`
  - `scope_architecture_refactor_plan.md`
  - `frontend_variable_analyzer_implementation.md`
  - `frontend_visible_value_resolver_implementation.md`
  - `frontend_unary_binary_expr_semantic_implementation.md`
  这些文档必须统一为 `sourceName / canonicalName / displayName()`、lexical-first、caller-side remap-on-miss、
  canonical-only downstream 的口径；不得恢复 runtimeName 持久化、三名模型或下游回扫 raw metadata。
- 引用型、门禁和展示文档：
  - `diagnostic_manager.md`
  - `frontend_analysis_inspection_tool_implementation.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_complex_writable_target_implementation.md`
  - `frontend_lowering_func_pre_pass_implementation.md`
  - `frontend_lowering_plan.md`
  - `frontend_lowering_skeleton_pre_pass_implementation.md`
  - `frontend_loop_control_flow_analyzer_implementation.md`
  - `frontend_rules.md`
  这些文档只同步 owner/category、compile gate、display/published fact 边界和测试锚点；不要复制 backend generator 规则。
  同时检查 `frontend_lowering_skeleton_pre_pass_implementation.md` 的文件名、标题和内容是否一致，若它实际是 pre-pass
  总览文档，要在阶段 5 明确重命名、改标题或标注历史命名。

#### 5D：API / CLI / common 文档清理

- `doc/module_impl/api/rpc_api_implementation.md`
  - 核对 `Compile Pipeline Contract`、`Output Publication Contract`、`Stable Test Anchors`。
  - 阶段 3 仍保持核心 3 个 generated files 公共契约时，不提前改 API 文档示例。
  - 阶段 4 仍保持核心 3 个 generated files 公共契约；module-local wrapper 只是
    `engine_method_binds.h` 的内部 section，不改变 generated file、output VFS local links、
    `CompileResult.generatedHostPaths` 或 `/__build__/generated/**` 示例。
  - API 仍不生成 `.gdextension` metadata；这条职责边界不能因 binding 文件变化而移动到 API 层。
- `doc/module_impl/cli/cli_implementation.md`
  - 核对 artifact 命名、generated file paths、verbose 输出、output VFS links、build log、`.gdextension` library path
    和 stable test anchors。
  - CLI 继续只是 API/backend 输出的适配层；只有 backend 产物列表、artifact path 或 diagnostics display path 变化时才同步文档。
- `doc/module_impl/common_rules.md`
  - 只在本迁移引入异常类归属、`requireXxx` / `checkXxx` 命名规则或 `StringUtil` 共用 helper 变化时更新。
  - 不为了 backend binding 清理修改通用规则文档。

#### 5E：源码注释、模板注释和测试文案

- 源码和模板注释
  - 清理 `CProjectBuilder`、`CGenHelper`、`CBuiltinBuilder`、`ConstructInsnGen`、`entry.c.ftl`、`entry.h.ftl`、
    `engine_method_binds.h.ftl` 中把 gdextension-lite 当作当前 public wrapper、collision target、constructor source、
    initialization path 或 native input 的注释。
  - `gdcc_helper.h`、`gdcc_call.h`、`gdcc_bind.h`、`gdcc_string.h`、`gdcc_string_name.h`、`gdcc_intrinsic.h`
    不再 include `<gdextension-lite.h>`，注释也不能暗示旧 header 是当前 ABI 聚合入口。
  - 保留必要历史名时，必须说明它只是旧命名来源，不是当前编译依赖。
- 测试文案和旧断言
  - 清理 test display name、failure message、注释中的 “gdextension-lite constructor”、
    “gdextension-lite include dir”、`gdextension-lite-one.c` 当前依赖等旧说法。
  - `ApiCompilePipelineTest`、`CProjectBuilderSharedIncludeTest`、`FrontendLoweringToCProjectBuilderIntegrationTest`、
    `CConstructInsnGenTest` 是必须同步更新的最小公开锁点。
  - 对阶段 4 影响的 API / CLI 公共契约测试，继续使用 generated file / output link 的公共语义命名；
    不保留旧 module-local 独立文件名的专项负向断言。

#### 5F：文档和事实源验收

- `rg -n "gdextension-lite|gdextension_lite|gdextension-lite-one|implementation-macros|<gdextension-lite.h>|shared-include/gdextension-lite|tmp/inspect_gdlite" src/main src/test doc`
  - 旧根层文档路径必须是 `doc/gdcc_runtime_lib.md`；旧历史命名文件不再存在。
  - 只允许本计划、runtime library 历史说明、明确标注“迁移前/历史”的说明，或
    `ApiCompilePipelineTest` / `CProjectBuilderSharedIncludeTest` 中的 stale vendor fixture 常量残留。
  - `src/main`、`src/test` 中不允许有运行时代码、构建代码、测试断言、display name 或 failure message 依赖旧 vendor。
- `rg -n "runtimeName|三名模型|raw JSON|重新读 JSON|回扫 raw|godot_module_bindings.*runtime binding|godot_module_bindings.*generated file|projectPath/godot_module_bindings" doc/module_impl`
  - 命中必须逐条解释：要么是历史说明，要么是明确禁止项；现行合同不能继续宣称下游回扫 raw metadata、runtimeName
    持久化，或把 module-local binding 当作静态 runtime binding / 独立 generated file。
- 对 `doc/module_impl` 下每一份文档形成清理记录：
  - `current-contract`: 已确认无旧事实源，且和统一事实源层级一致。
  - `updated-contract`: 已改写为 GDCC 自有 binding / metadata / generated file / class name 口径。
  - `historical-reference`: 已标注历史命名来源、迁移前行为或审计记录，且不再作为当前实现依据。
- 干净工作区构建准备测试：确认 `CProjectBuilder` 只收本轮 generated `.c` 与 `<includeRoot>/godot/godot_binding.c`。
- 脏工作区构建准备测试：预先放入旧 `shared-include/gdextension-lite/gdextension-lite-one.c`，确认它不会进入
  native compiler input，也不会让 include dirs 重新包含 `shared-include/gdextension-lite`。

#### 阶段 5 文档归类记录

根层文档：

- `updated-contract`: `doc/gdcc_c_backend.md`, `doc/gdcc_runtime_lib.md`

backend 文档：

- `updated-contract`: `doc/module_impl/backend/gdextension_lite_removal_plan.md`,
  `builtin_builder_implementation.md`, `implicit_conversion_implementation.md`,
  `index_insn_implementation.md`, `engine_method_bind_implementation.md`,
  `call_method_implementation.md`
- `current-contract`: `assign_insn_implementation.md`, `backend_ownership_lifecycle_contract.md`,
  `call_global_implementation.md`, `cbodybuilder_implementation.md`, `construct_array_implementation.md`,
  `explicit_c_inheritance_layout_contract.md`, `lifecycle_instruction_restriction.md`,
  `load_static_implementation.md`, `load_store_property_implementation.md`,
  `operator_insn_implementation.md`, `typed_array_abi_contract.md`,
  `typed_dictionary_abi_contract.md`, `variant_abi_contract.md`

frontend / scope 文档：

- `updated-contract`: `frontend_exact_call_extension_metadata_contract.md`,
  `frontend_builtin_property_access_implementation.md`, `gdcc_facing_class_name_contract.md`,
  `inner_class_implementation.md`, `runtime_name_mapping_implementation.md`,
  `superclass_canonical_name_contract.md`, `scope_architecture_refactor_plan.md`
- `historical-reference`: `frontend_builtin_constructor_variant_argument_plan.md`,
  `frontend_lowering_plan.md`
- `current-contract`: `frontend_dynamic_call_lowering_implementation.md`,
  `frontend_engine_virtual_override_implementation.md`, `frontend_property_init_lowering_implementation.md`,
  `frontend_void_call_result_behavior.md`, `frontend_chain_binding_expr_type_implementation.md`,
  `frontend_type_check_analyzer_implementation.md`, `frontend_implicit_conversion_matrix.md`,
  `frontend_lowering_(un)pack_implementation.md`, `frontend_lowering_cfg_pass_implementation.md`,
  `frontend_top_binding_analyzer_implementation.md`, `scope_type_resolver_implementation.md`,
  `scope_analyzer_implementation.md`, `frontend_variable_analyzer_implementation.md`,
  `frontend_visible_value_resolver_implementation.md`, `frontend_unary_binary_expr_semantic_implementation.md`,
  `diagnostic_manager.md`, `frontend_analysis_inspection_tool_implementation.md`,
  `frontend_compile_check_analyzer_implementation.md`, `frontend_complex_writable_target_implementation.md`,
  `frontend_lowering_func_pre_pass_implementation.md`, `frontend_lowering_skeleton_pre_pass_implementation.md`,
  `frontend_loop_control_flow_analyzer_implementation.md`, `frontend_rules.md`

API / CLI / common 文档：

- `current-contract`: `doc/module_impl/api/rpc_api_implementation.md`,
  `doc/module_impl/cli/cli_implementation.md`, `doc/module_impl/common_rules.md`

## 行为验收清单

- exact engine non-vararg `CALL_METHOD` 继续走 `godot_object_method_bind_ptrcall(...)`。
- exact engine vararg `CALL_METHOD` 继续走 `godot_object_method_bind_call(...)`。
- 缺失 method bind hash 继续 fail-fast，不回退 public wrapper。
- engine property ordinary access 使用 raw getter/setter method metadata；indexed property 使用 raw getter/setter 加 fixed index；
  metadata unknown object property 仍走 `godot_Object_get/set` fallback。
- `CALL_GLOBAL` utility 的默认参数和 vararg 继续按现有 `CallGlobalInsnGen` 语义工作。
- `Variant` outward ABI 仍是 `NIL + godot_PROPERTY_USAGE_NIL_IS_VARIANT`。
- typed `Array[T]` / `Dictionary[K, V]` 仍保留 hint、hint_string 和 wrapper preflight。
- object slot write 顺序不变：
  1. capture old
  2. pointer conversion
  3. assign
  4. own borrowed rhs
  5. release old
- `_return_val` 不进入 `__finally__` auto-cleanup。
- GDCC -> ENGINE 继续先走 `gdcc_object_to_godot_object_ptr(...)`。
- GDCC -> GDCC upcast 继续走 `_super` 链，不允许裸 cast。
- non-RefCounted object local 不因 scope exit 自动 destroy。

## 测试验收

优先 targeted tests：

```bash
script/run-gradle-targeted-tests.sh --tests CProjectBuilderSharedIncludeTest,CCodegenTest,CCodegenEngineMethodBindHeaderTest
script/run-gradle-targeted-tests.sh --tests CGenHelperTest,CBodyBuilderPhaseBTest,CBodyBuilderPhaseCTest,CConstructInsnGenTest
script/run-gradle-targeted-tests.sh --tests CallGlobalInsnGenTest,CallMethodInsnGenTest,MethodResolverParityTest,EngineMethodSymbolKeyTest
script/run-gradle-targeted-tests.sh --tests CPackUnpackVariantInsnGenTest,COperatorInsnGenTest,IndexLoadInsnGenTest,IndexStoreInsnGenTest
script/run-gradle-targeted-tests.sh --tests ApiCompilePipelineTest,ApiCompileArtifactLinkTest,ApiCompileDiagnosticsTest,ApiRecompileArtifactRefreshTest
script/run-gradle-targeted-tests.sh --tests GdccCommandInputTest,GdccCommandTaskTest
```

需要 Zig/Godot 的 integration tests：

```bash
script/run-gradle-targeted-tests.sh --tests CProjectBuilderIntegrationTest
script/run-gradle-targeted-tests.sh --tests FrontendLoweringToCProjectBuilderIntegrationTest
script/run-gradle-targeted-tests.sh --tests CallMethodInsnGenEngineTest,CallMethodInsnGenEngineInheritanceTest
script/run-gradle-targeted-tests.sh --tests FrontendLoweringToCTypedArrayAbiIntegrationTest,FrontendLoweringToCTypedDictionaryAbiIntegrationTest
```

现有测试反向更新规则：

- 阶段 2：只新增 `GodotBuiltinGenerator`、`GodotUtilityGenerator`、`FixedGodotBindings` / `Godot451FixedBindings`、`GodotBindingTool`
  等生成器 contract 测试。此时旧 runtime 仍可编译 `gdextension-lite-one.c`，不要提前修改
  `ApiCompilePipelineTest` 和 `CProjectBuilderSharedIncludeTest` 的 vendor 输入断言。
- 阶段 3：一次性反向更新构建输入和 include root 相关旧断言：
  - `src/test/java/gd/script/gdcc/api/ApiCompilePipelineTest.java`
    - `compiler.lastCFiles()` 不再包含 `gdextension-lite-one.c`。
    - `compiler.lastCFiles()` 必须包含 `entry.c` 和 `godot_binding.c`。
    - include dirs 必须包含 `gdcc` 和 `godot`，且不包含 `gdextension-lite`。
  - `src/test/java/gd/script/gdcc/backend/c/build/CProjectBuilderSharedIncludeTest.java`
    - `initProjectSyncsSharedIncludeAndSkipsProjectInclude` 不再检查 `shared-include/gdextension-lite/gdextension-lite-one.c`，
      改为检查 `shared-include/godot/godot_interface.h`、`godot_fixed_binding.h`、`godot_binding.c`。
    - `buildProjectUsesSharedIncludePaths` 的 expected include dir 从 `gdextension-lite` 改为 `godot`。
    - native compiler input 必须包含 `shared-include/godot/godot_binding.c`，且不包含
      `shared-include/gdextension-lite/gdextension-lite-one.c`；`stale.c` 仍不能进入输入。
    - 新增或扩展 stale vendor 场景：在 `buildProject(...)` 前手动创建
      `shared-include/gdextension-lite/gdextension-lite-one.c`，确认它不会进入 `compiler.cFiles()`，也不会让
      include dirs 重新包含 `shared-include/gdextension-lite`。
  - `FrontendLoweringToCProjectBuilderIntegrationTest` 等测试里的 “gdextension-lite constructor” 文本必须改为
    “generated builtin constructor” 或直接描述 `godot_new_*` wrapper。
  - `src/test/java/gd/script/gdcc/backend/c/gen/CCodegenEngineMethodBindHeaderTest.java`
    - 旧的 `bind == NULL` 分支、`GDCC_PRINT_RUNTIME_ERROR(...)` 后返回 `0` / `NULL` / 默认值的断言必须反向更新。
    - 新断言检查 accessor 输出 primary hash 与 `hashCompatibility` 候选、lookup miss failure helper、以及调用 helper
      不再吞掉 bind lookup miss。
- 阶段 4：反向更新 generated file 精确列表断言：
  - `CCodegenEngineMethodBindHeaderTest`、`CCodegenTest` 应继续断言文件集合恰好为
    `entry.c`、`engine_method_binds.h`、`entry.h`。
  - 对需要 module-local wrapper 的 fixture，断言 wrapper 出现在 `engine_method_binds.h` 的 module-local section，
    并通过核心三文件精确列表证明不会新增其它 generated file。
  - API / CLI 层的 `generatedFiles()`、`outputLinks()`、`/generated` 目录列表断言保持核心 3 文件和稳定顺序；
    阶段 4 不因 wrapper 使用集新增公开输出。
  - build 层 fixture 若触发 module-local wrapper，必须断言 native compiler input 仍只包含
    `projectPath/entry.c` 和 `shared-include/godot/godot_binding.c` 这两类输入，同时 include dirs 仍不包含 `projectPath`。
- 阶段 5：`rg` 清理时，`doc/module_impl` 全目录、测试注释、display name、failure message 中的
  `gdextension-lite` 也要处理；只允许本计划和明确标注为历史来源的文档保留旧名。

建议新增：

- `ExtensionApiLoaderMetadataTest`
  - 验证 `builtin_class_sizes` 不再被 loader 丢弃，4 个 build configuration 都被加载。
  - 验证 `builtin_class_member_offsets` 不再被 loader 丢弃，4 个 build configuration 都被加载，典型 builtin member
    offset/meta 可查。
  - 验证 offset/meta 的真实布局差异，而不是只验证列表非空：
    `Vector3.z` 在 float 配置下为 `offset=8/meta=float`，在 double 配置下为 `offset=16/meta=double`；
    `Color.r` 在 double 配置下仍为 `offset=0/meta=float`。
  - 验证 layout 查询统一走 `ExtensionAPI.find/requireBuiltinClassSize(...)` 和
    `find/requireBuiltinClassMemberLayout(...)`，负向覆盖缺失 build configuration、缺失 class、缺失 member 和空 metadata。
  - 验证 `ExtensionBuiltinClass.members[]` 与 `ExtensionBuiltinClassMemberOffsets` 能按 member 名交叉校验，
    但不会把 offset 表当成 property surface。
  - 验证 builtin constructor index 在模型中保留；builtin method hash、utility function hash、engine method hash 作为 lookup metadata 保留。
  - 验证 builtin method `hash_compatibility` 若出现在目标 Godot metadata 中不会被 loader 丢弃；utility function 不伪造
    `hash_compatibility`。
  - 验证顶层 `global_constants[]` 被加载到 `ExtensionAPI.globalConstants()`；Godot 4.5.1 当前为空时保留空集合，
    非空 fixture 验证 `name/value/is_bitfield`、int64 value 和 description 不丢失。
- `ExtensionBuiltinClassMetadataTest`
  - 验证 `has_destructor`、nullable `indexing_return_type` 被加载。
  - 验证 `is_keyed`、members、operators、constants/enums 能支撑全量 builtin generator。
- `ExtensionGdClassPropertyMetadataTest`
  - 验证 engine property 的 raw `getter`、`setter`、nullable `index` 被加载到 `ExtensionGdClass.PropertyInfo`。
  - 验证 `getGetterFunc()` / `getSetterFunc()` 返回 raw Godot method name。
  - 验证 `index = 0` 不会被当作缺失。
  - 验证 getter-only property 不生成 setter wrapper。
- `ScopePropertyResolverTest` / `PropertyDefAccessSupport` 相关测试
  - 验证 `MetadataUnknown` 仍进入 runtime fallback，missing property 仍失败。
  - 验证 engine readonly property 的前端可写性诊断不因 raw setter 字段扩展而回归。
  - 验证 builtin `members -> synthetic PropertyInfo` 仍是 builtin property surface。
- `BackendPropertyAccessResolverTest`
  - 验证 ordinary property 能解析到 raw getter/setter method metadata。
  - 验证 indexed property 能保留 `index = 0` 并校验 getter/setter 方法参数形态。
  - 验证 resolver 产物只作为 Stage 4 wrapper 输入，不改变 unknown object fallback 边界。
- `CLoadPropertyInsnGenTest` / `CStorePropertyInsnGenTest`
  - 反向更新 `ExtensionGdClass.PropertyInfo(...)` fixture，继续覆盖 engine direct wrapper、unknown object fallback、
    builtin getter/setter、read/write failure、shadowed owner 和 getter-self/setter-self。
  - 新增 `Window.unresizable` 一类样本，证明 wrapper lookup 使用 raw `get_flag/set_flag + index`，
    而不是从 property 名推导 `get_unresizable/set_unresizable`。
- `CProjectBuilderGodotBindingIncludeTest`
  - 验证 shared include / local include 都同步 `godot/**`。
  - 验证编译输入包含 `godot_binding.c`，不包含 `gdextension-lite-one.c`。
- `GodotBuiltinGeneratorTest`
  - 验证 Godot 4.5.1 builtin constructors、destructors、methods、members、operators、pack/unpack wrapper 按 GDCC builtin contract 全量生成。
  - 验证 member getter/setter 或任何 direct field helper 只能从 `ExtensionBuiltinClassMemberOffsets` 读取 offset/meta：
    不允许用 `ExtensionBuiltinClass.members[]` 顺序累加 offset，也不允许用 `REAL_T_IS_DOUBLE` 推导所有 member meta。
  - 验证 layout-sensitive helper 对 build configuration 分支稳定：
    至少覆盖 `Vector3.z` 的 float/double offset 差异、`Transform2D.origin` 的复合 `Vector2` meta、
    以及 `Color.r` 在 double build 下仍是 `float` meta。
  - 验证 atomic/non-struct constructor/operator 过滤：`Nil` target / left 不生成，primitive default/self-copy/primitive-primitive 不生成，
    但 `int(String)`、`float(String)` 这类 String conversion 保留。
  - 验证所有单 `String` 参数 constructor 都合成 latin1/utf8/utf16/utf32/wide char helper，且含 `_and_len` 变体；
    当前 4.5.1 样本至少覆盖 `int`、`float`、`String`、`Color`、`StringName`、`NodePath`。
  - 验证 `Variant` 合成 helper：nil、from-type、to-type、Variant copy、builtin copy constructor。
  - 验证 constructor / conversion / nil / copy wrapper 使用 `GDExtensionUninitialized*Ptr` destination 语义，
    不把 raw storage 先零初始化后当 initialized pointer 使用；lookup 或构造失败路径不 destroy uninitialized storage。
  - 验证 builtin member getter/setter 与 method `get_*` / `set_*` 冲突时过滤 method wrapper，至少覆盖
    `Transform2D.get_origin`。
  - 验证 typed Array/Dictionary helper、indexed/keyed helper 的生成只依赖 `ExtensionAPI` 模型和版本化 override。
  - 验证 builtin method lookup 生成 primary hash 和可用 compatibility hash 候选，并在 lookup 返回 `NULL` 时调用
    binding lookup failure helper；生成代码不能缓存 `NULL` 或继续 ptrcall。
  - 验证同一 C function name 映射到不同 canonical key / ABI 时 fail-fast。
  - 验证生成 symbol 集与 `ExtensionAPI.builtin_classes[]` 经 GDCC contract 投影后的结果一致，重复执行输出稳定。
- `GodotUtilityGeneratorTest`
  - 验证 `ExtensionAPI.utility_functions[]` 中所有 utility wrapper 全量生成。
  - 验证 vararg / void return utility 的 lookup hash 和调用 ABI 正确进入生成代码。
  - 验证 utility lookup 返回 `NULL` 时调用 binding lookup failure helper；utility hash 冲突不能通过
    compatibility hash、无 hash lookup 或默认返回值绕过。
  - 验证 `godot_print` 等模板固定路径需要的 utility 不再依赖 module-local session。
- `FixedGodotBindingsTest`
  - 验证源码清单没有重复 canonical symbol。
  - 验证 `FixedGodotBindings.forVersion(GodotVersion.V451)` 选择 `Godot451FixedBindings`，并拒绝未支持的 API 版本。
  - 验证 `godot_Object_call` 失败路径不会 destroy 可能未初始化的 return storage，而是返回 initialized nil
    `Variant`。
  - 验证清单内每个 wrapper 都不被 interface/builtin/utility 全量集合覆盖，否则 fail-fast。
  - 验证同一 C function name 映射到不同结构性 signature/ABI 时 fail-fast。
- `GodotBindingToolTest`
  - 验证 `generate-abi-support` 从 `ExtensionAPI` 模型生成 global enum、global constants、native struct、
    builtin size/layout header，重复执行输出稳定。
  - 验证 `check-fixed` 只读取 `FixedGodotBindings` 当前版本数据并做 helper/template 对账，不修改源码清单或生成物。
  - 验证 `check-fixed` 使用 interface provided set，能接受真实 interface wrapper 并拒绝 prefix-looking typo。
  - 验证 `generate-fixed` 从源码清单输出 `godot_fixed_binding.h/.c`，重复执行输出稳定。
  - 验证 `dump-fixed-manifest` 只导出 snapshot，snapshot 不是固定 wrapper 的事实来源。
- `GdccHelperBindingScannerTest`
  - 验证 scanner 会忽略 interface wrapper、builtin/utility provided wrapper、类型名、`gdcc_*` helper、`GD_STATIC_*`。
  - 验证 scanner 对 FreeMarker 拼接、`_Generic`、token pasting 只报告“不可由扫描推断”，不把扫描结果当作新增 wrapper 来源。
  - 验证固定 helper 中可见但不在 provided/fixed 清单中的 `godot_*` 会让 `check-fixed` fail-fast。
- `ModuleLocalGodotBindingTemplateTest`
  - 验证只生成未被 interface/builtin/utility/fixed provided set 覆盖的 module-local wrapper。
  - 验证 singleton getter 和 class constant helper 的最小 wrapper section 生成到 `engine_method_binds.h`。
  - 验证 singleton 与 class constant 分别走 `godot_global_get_singleton(...)` 和 literal return 两条路径，
    不混入 `godot_classdb_get_method_bind(...)` / `module_method_bind`。
  - 验证 singleton 和 engine constructor lookup failure context 不使用 `.field = ...` designated initializer。
  - 验证相同 symbol 去重和稳定排序。
  - 验证 provided set 中已提供的 wrapper 不会被 module-local section 再次输出。
  - 验证渲染结果不要求新增 `godot_module_bindings.h/.c`，也不改变 `CCodegen.generate()` 的 generated file 集合。
- `GodotBindingUsageSessionTest`
  - 验证函数级 buffer render 成功后才 commit，失败 buffer 不污染 session。
  - 验证 interface/builtin/utility/fixed snapshot 预加载为 provided set，module-local template section 不输出这些 symbol。
  - 验证 module-local singleton/class constant first-use 顺序和同一 canonical binding 重复登记稳定去重。
  - 验证同一 C function name 映射到不同结构性 signature/ABI 时 fail-fast。
  - 验证同一 class constant canonical binding 的 value 漂移 fail-fast，且跨 buffer commit 的 C name 冲突也会 fail-fast。
- `GodotBindingUsageRegistrationTest`
  - 验证 `CBodyBuilder.callAssign` / `callVoid` 自动登记非 interface `godot_*`。
  - 验证 `appendLine` / `appendRaw` 直接拼出的 `godot_*` 若未显式登记，会被 generated C 扫描测试发现。
  - 验证 `CCodegen.generatePropertyInitApplyBody(...)` 中的 constructor/copy/destroy wrapper 被 builtin provided set 覆盖，
    只有显式 module-local singleton/class constant helper 才进入 module-local binding collector。
  - 验证 `GenerateRenderFacade` 只登记模板中的残余 module-local wrapper，不登记 builtin/utility/fixed provided wrapper。
- `GodotBindingGeneratedCScannerTest`
  - 使用 test-only scanner 验证 generated C 片段中每个 `godot_*` 调用都属于 interface set、builtin set、utility set、fixed set、module-local generated set、
    GDCC-owned macro/helper 白名单或类型名白名单。
  - 验证扫描只负责单元测试 fail-fast，不会在 `CCodegen.generate()` 中运行，也不会自动修改 usage session 或生成物。
- `GodotInterfaceGeneratorTest`
  - 验证生成的 `godot_*` 声明和实现集合等于 `gdextension_interface.h` 中可解析的 `@name` / function pointer typedef 配对集合。
  - 验证每个 `@name` 都能和紧随的签名 typedef 配对；缺失配对时 fail-fast。
  - 验证 wrapper name 和 proc-address 字符串来自 `@name`，typedef 名只作为函数指针类型保留。
  - 验证 `GDExtensionInterfaceGetProcAddress` 不进入 wrapper 集合。
  - 验证 `GDExtensionsInterfaceEditorHelpLoadXml...` 这类 typedef 拼写异常仍按 `@name` 生成正确 wrapper。
  - 验证 `editor_register_get_classes_used_callback`、`register_main_loop_callbacks` 这类新增 header 项只要有标准配对就进入输出。
  - 验证同一 `@name` 重复或同一 wrapper name 对应不同 ABI 时 fail-fast。
  - 验证 `variant_new_nil`、`string_new_with_utf8_chars`、`string_name_new_with_utf8_chars`、
    `object_method_bind_call` 等 interface wrapper 的生成签名保留 `GDExtensionUninitialized*Ptr`。
  - 验证固定模板依赖的 `godot_mem_alloc`、`godot_variant_new_nil`、`godot_classdb_get_method_bind`、`godot_object_method_bind_ptrcall`、`godot_object_method_bind_call` 均来自全量 interface 输出。
  - 验证 interface wrapper function pointer 都在 `godot_initialize_interface(...)` 中 eager resolve；运行期 wrapper
    不再调用 `get_proc_address`、不检查 lazy cache。
  - 验证 `get_proc_address` 返回 `NULL` 时通过 binding lookup failure helper 报告缺失 interface entry。
- `GodotAbiSupportHeaderTest`
  - 验证 `godot_PROPERTY_HINT_*`、`godot_PROPERTY_USAGE_*`、`GDEXTENSION_*` 常量族、`godot_global_constants.h`、
    native struct、builtin size assert header、builtin member offset/meta assert header 都能被一个最小 C translation unit include。
  - 验证非空 fixture 能生成 standalone global constants；当前 4.5.1 为空时，空 header 仍稳定输出并被 `<godot_abi.h>` 聚合。
  - 验证 `gdextension_interface.h` 的 `GDExtensionUninitialized*Ptr` typedef 家族可见，且 generated wrapper header
    不把这些参数改写为 initialized pointer。
  - 验证一个带 `offsetof` 的最小 translation unit 能检查 `godot_Vector3.z` 与 `godot_Color.r` 的 offset/meta，
    避免只有 size assert 通过的假阳性。
- test suite runtime fixture：
  - 覆盖 exact engine instance call。
  - 覆盖 exact static engine call。
  - 覆盖 exact vararg call。
  - 覆盖 parent-typed GDCC receiver 的 object dynamic fallback。
  - 覆盖 typed Array/Dictionary outward ABI。

## 实施顺序建议

1. 先提交阶段 0，按 0A/0B/0C/0D 顺序补齐 `ExtensionAPI` metadata 模型、shared property 语义和 backend accessor resolver；
   验证 sizes、member offsets、member meta、`global_constants[]`、engine property getter/setter/index 都可从 Java 模型读取，
   同时保护现有 resolved/fallback/readonly/direct accessor 行为。
2. 再提交阶段 1A，只建立 Godot ABI 声明头和 global enum / global constant / native struct / builtin type 替代，
   验证 `<godot_abi.h>` 可独立 include。
3. 提交阶段 1B，全量生成 `godot_interface.h/.c`，验证 wrapper name 集合和 `gdextension_interface.h`
   中可解析的 `@name` / typedef 配对集合完全一致。
4. 提交阶段 1C，只建立 `godot_binding.h/.c` 聚合入口并聚合全量 interface support；不切换模板 include 或构建输入。
5. 提交阶段 1D，将 interface wrapper hot path 改为 header-level `static inline`，并把共享 function pointer table
   保留为 `godot_interface.c` 中的 hidden 单定义状态；用多 translation unit 测试证明 wrapper 不复制 cache，
   并在阶段完成后手动查看一次动态符号表，确认内部符号不默认导出。
6. 提交阶段 2，按 GDCC builtin contract 全量生成 `godot_builtin.h/.c`，全量生成 `godot_utility.h/.c`，
   并从 `FixedGodotBindings` 当前版本数据生成 `godot_fixed_binding.h/.c`；`check-fixed` 只做对账，不做发现或补写。
7. 提交阶段 3，原子切换 `entry.c` 初始化、`entry.h` / `gdcc/*.h` include、`CProjectBuilder` include dir 和 native 编译输入；
   这个阶段完成后生成项目不再编译 `gdextension-lite-one.c`，相关测试同步断言 `godot_binding.c` 和 `godot` include root；
   同时加入 stale vendor 回归场景，证明旧 `shared-include/gdextension-lite/gdextension-lite-one.c` 残留不会进入 `cFiles`；
   同提交更新活动文档和被触及源码注释，不能继续公开宣称旧初始化模型。
8. 提交阶段 3.1，修复新 generated Godot binding 暴露出的 ptrcall return carrier 初始化回归；该阶段只收口
   builtin / utility / fixed / template 中的 initialized carrier 规则，不改变 runtime input 或 generated file 契约。
9. 提交阶段 3.2，把 engine class constructor wrapper 从 `godot_fixed_binding.h/.c` 的全量预生成中移出，
   由 `ConstructInsnGen` 登记 constructor usage snapshot，并在 `engine_method_binds.h` 中按需生成使用
   `GD_STATIC_SN(...)` 的 `static inline godot_new_<Class>()`。同阶段在 `gdcc_bind.h` 增加宏封装
   `engine_method_binds.h` 中的 `gdcc_engine_method_bind*` accessor 生成，让模板只输出 compatibility hash 数据和宏调用。
   本阶段必须保留 `godot_classdb_construct_object(...)` 的 public constructor 语义，不能回退到
   `godot_classdb_construct_object2(...)`；同时固定 `FixedGodotBindings.symbols(api)` / fixed manifest 不再隐式漏算
   全量 constructor wrapper。
10. 提交阶段 4，把 provided set 之外残余的具体 engine public/property/singleton/class constant wrapper 接入 module-local session，
   并用 interface/builtin/utility/fixed provided set 防止脚本重复生成 runtime 已提供的 wrapper。
   该阶段把 module-local wrapper 作为 `engine_method_binds.h` 内的 header-only `static inline` section 输出，
   继续由 `entry.h` 进入唯一模块级 `entry.c` translation unit；不生成 `godot_module_bindings.h/.c`，
   不改变 `CCodegen.generate()` 的 3 个 generated files、API/CLI 公开输出或 `CProjectBuilder` native input。
   同阶段更新 `CCodegen*`、API、CLI 和 build 层断言，锁定“不新增文件、不新增 `.c` 输入”；engine constructor
   默认沿用阶段 3.2 的 `engine_method_binds.h` 按需生成，不在本阶段重复迁移。
11. 最后提交阶段 5，删除 vendor zip，手动清理工作区中已知旧 `<includeRoot>/gdextension-lite` 子树；
   不在 `CProjectBuilder` 增加自动删除逻辑。并对 `doc/module_impl` 每一份文档做
   `current-contract` / `updated-contract` / `historical-reference` 归类；同提交完成源码注释、模板注释、
   测试 display name、failure message 和旧断言的全仓清理。

这个顺序避免旧计划中“先移除 vendor 编译输入、后补 fixed wrapper”的不可编译中间态。每个阶段都有明确可回归的边界。

## 风险与处理

- ExtensionAPI metadata 不完整：
  - 阶段 0 是硬前置；`builtin_class_sizes`、`builtin_class_member_offsets`、engine property `getter/setter/index`
    未进入 Java 模型前，不允许开始 ABI size header、fixed wrapper 或 engine property wrapper 生成。
  - generator 只能依赖 `ExtensionAPI` 模型，不应绕过 `ExtensionApiLoader` 直接重读 JSON。
  - 阶段 0 会触碰现有 property 语义；如果 `PropertyInfo.getGetterFunc()` / `getSetterFunc()` 从 `null`
    改为 raw Godot method name，必须同步确认 `ScopePropertyResolver`、`PropertyDefAccessSupport`、
    `LoadPropertyInsnGen`、`StorePropertyInsnGen` 没有把 ENGINE property 误判成 GDCC getter/setter 或错误跳过 fallback。
- 阶段切换造成不可编译中间态：
  - `entry.c.ftl` 当前 include `<implementation-macros.h>` 并调用 `gdextension_lite_initialize(...)`，
    `entry.h.ftl` 与多个 `gdcc/*.h` 当前 include `<gdextension-lite.h>`；这些旧 include 未替换前不能移除 vendor include dir。
  - 阶段 2 的 builtin/utility/fixed support 未生成并聚合前不能移除 `gdextension-lite-one.c`，否则 `godot_print`、
    `godot_new_Variant_with_String`、`godot_Object_call`、`godot_Object_get`、`godot_Object_set` 等非 interface wrapper 会在链接期缺定义。
  - 阶段 3 必须把模板 include、helper include、entry 初始化和 `CProjectBuilder` 输入切换作为同一个编译边界验收。
- stale vendor 文件被重新编译：
  - `ResourceExtractor.extract(...)` 不删除目标目录旧文件；`shared-include/gdextension-lite/gdextension-lite-one.c`
    可能来自迁移前构建并长期残留。
  - 阶段 3 必须删除 `CProjectBuilder` 中“旧 vendor 文件存在就加入 `cFiles`”的分支，改为显式加入
    `<includeRoot>/godot/godot_binding.c`，并在新 runtime C 文件缺失时 fail-fast。
  - 阶段 3 不应通过“清空整个 include root”解决这个问题；若需要防止 vendor zip 被重新抽取，只使用版本化非 vendor
    资源 allow-list，或在同阶段删除 vendor zip。
  - 阶段 5 的目录卫生是人工迁移操作：只手动清理 `<includeRoot>/gdextension-lite` 这个已知旧 vendor 子树，
    不能删除用户未授权的其他 shared include 内容；`CProjectBuilder` 不负责自动删除。
- 旧测试反向卡住迁移：
  - 阶段 0 必须反向更新直接构造 `ExtensionGdClass.PropertyInfo(...)` 的测试 fixture，例如
    `ExtensionMetadataTypeParsingTest`、`ScopePropertyResolverTest`、`BackendPropertyAccessResolverTest`、
    `CLoadPropertyInsnGenTest`、`CStorePropertyInsnGenTest`、`FrontendAssignmentSemanticSupportTest`、
    `ClassScopeResolutionTest`、`FrontendExprTypeAnalyzerTest`、`ClassRegistryTest`；更新时要保留原有
    metadata unknown、readonly、owner dispatch、getter-self/setter-self 语义断言。
  - `ApiCompilePipelineTest`、`CProjectBuilderSharedIncludeTest` 当前锁定 `gdextension-lite-one.c` 和 `gdextension-lite` include dir；
    它们必须在阶段 3 与 `CProjectBuilder` 输入切换同提交更新。
  - `CCodegenEngineMethodBindHeaderTest`、`CCodegenTest` 和 API / CLI 层部分测试当前锁定 generated file 恰好为 3 个；
    阶段 3 和阶段 4 都不应放宽这个公共文件集合断言。阶段 4 的 module-local fixture 应反向证明 wrapper
    进入 `engine_method_binds.h`，且没有 `godot_module_bindings.h/.c` 或其它新增 generated file。
  - build 层若新增 module-local fixture，必须同时断言 `cFiles` 不出现 `projectPath/godot_module_bindings.c`，
    仍只包含 `projectPath/entry.c` 与 `<includeRoot>/godot/godot_binding.c`，且 include dirs 不新增 `projectPath`。
  - 反向更新测试时不能削弱语义：exact engine method bind、typed Array/Dictionary outward ABI、ownership lifecycle 断言必须保留，
    只替换旧 vendor 路径和旧文件数量假设。
- builtin struct ABI layout 不匹配：
  - `godot_abi.h` 必须和 Godot 4.5.1 导出的 `gdextension_interface.h` / extension API 对齐。
  - 若 builtin 类型声明采用手写 include，必须作为 4.5.1 目标版本 ABI 文件维护，并由 size assert 覆盖；这不适用于 fixed wrapper，fixed wrapper 必须由 `FixedGodotBindings` 当前版本数据 + tool 生成。
- builtin member layout 漂移：
  - 只验证 `sizeof(godot_Vector3)` 不足以证明 `Vector3.z`、`Transform2D.origin`、`Basis.x` 等字段访问正确。
    offset 或 meta 错误会让字段访问、未来 scalar replacement、字段级优化在看似编译通过后读写错误内存。
  - `builtin_class_member_offsets` 必须和 `builtin_class_sizes` 一样作为硬前置加载、查询和测试；缺失 offset 或 meta 不能降级为
    “不用直接字段访问就先通过”。
  - 不能从 `ExtensionBuiltinClass.members[]` 顺序累加 offset，也不能用 `REAL_T_IS_DOUBLE` 推导所有 real member meta；
    `Color` 在 double build 下仍是 `float` member 是必须覆盖的反例。
  - 阶段 1A 的 ABI header 验收必须包含 `offsetof` / meta assert；阶段 2 的 builtin member wrapper 测试必须证明 generator
    消费 `ExtensionBuiltinClassMemberOffsets`，而不是只消费 `members[]` 类型名。
- `GDExtensionUninitialized*Ptr` ABI 被抹平：
  - Godot header 明确警告 uninitialized destination 必须由 constructor / placement 初始化；把它当成 initialized pointer、
    零初始化后复用、或在失败路径上 destroy 都可能造成双初始化、漏初始化、未初始化析构、内存泄漏或跨扩展 ptrcall 崩溃。
  - interface generator、builtin generator、module-local wrapper generator 和 ABI codec 都必须保留 initialized / const /
    uninitialized destination 三态；不能因为 C typedef 底层都是 `void *` 就合并。
  - `Variant`、`String`、`StringName`、typed container、`object_method_bind_call`、`object_get_class_name`
    和 new object/callable 路径必须有代表性测试；现有 `declareUninitializedTempVar(...)` 相关路径不能被新 wrapper 层替换成普通初始化变量。
  - cleanup 测试必须证明只 destroy initialized carrier / slot；lookup failure、constructor failure、invalid return path 不销毁 raw storage。
- wrapper 生成遗漏：
  - interface function wrapper 必须全部来自目标 Godot 版本 `gdextension_interface.h` 的固定全量生成。
  - 不能靠 typedef 名推导 wrapper：lookup key 必须来自 `@name`。entry callback typedef 没有 `@name`，不能进入 wrapper；
    `GDExtensionsInterface...` 这类 typedef 拼写异常也不能影响 wrapper 名。
  - 不解析 Godot C++ 注册源码；若某个 header 中存在的 interface 在特定 runtime 中 lookup 失败，应作为 runtime 兼容性或
    Godot 版本不匹配问题处理。
  - builtin callable wrapper 与 utility function wrapper 必须全部来自 Godot 版本级全量预生成集合。
  - 固定 runtime/helper/template 依赖必须来自 `FixedGodotBindings` 当前版本数据。
  - 只有经过 provided set 过滤后仍残余的具体 engine public wrapper、engine property helper、singleton getter、
    class constant helper 来自 `GodotBindingUsageSession`。
  - generated C 扫描只做 fail-fast 验收；新增生成器路径时必须明确落入 provided set 或显式登记 module-local wrapper。
- hash lookup miss 被空指针崩溃掩盖：
  - Godot 对 builtin method、utility function、class method bind 的 hash mismatch 语义都是返回 `nullptr`，最多伴随 Godot 自身错误输出。
    GDCC wrapper 必须把这个结果升级为带上下文的 fail-fast 诊断。
  - builtin、utility、method-bind 等后续 lazy cache 只缓存非空 lookup 结果；禁止用 “已经查过但为空” 的状态继续执行，
    也禁止调用 helper 再返回默认值来掩盖 metadata/hash 问题。interface wrapper 已改为初始化期 eager resolve，
    不再有运行期 lazy cache。
  - class method bind 必须覆盖 Godot compatibility 修正链路：primary hash、`hash_compatibility` 候选，以及 Godot runtime
    `classdb_get_method_bind` 内部对特殊 legacy hash 的修正。GDCC 不维护第二套特殊 hash 表，但必须把 metadata 中的候选全部渲染进 lookup。
  - utility function 没有 compatibility fallback；hash 漂移应在 generator merge 阶段或 runtime lookup 阶段 fail-fast。
- builtin wrapper 语义相对旧库漂移：
  - `gdextension-lite` 的生成行为包含过滤和合成，不是 `extension_api.json` 的朴素全量映射。
  - GDCC 必须把 constructor/operator atomic 过滤、单 `String` 参数 char helper、`Variant` conversion helper、
    indexed/keyed accessor、typed container helper、member/method 冲突过滤写成自己的 generator contract 和测试。
  - 对旧库中存在但 GDCC contract 故意不生成的 symbol，要在测试快照或说明中体现为 deliberate exclusion；
    对 GDCC 额外合成的 helper，要标明来源是 GDCC-owned compatibility wrapper。
  - C function name 冲突必须 fail-fast，不能靠“后生成覆盖先生成”得到看似通过的 ABI。
- fixed wrapper 重复生成：
  - `FixedGodotBindings` 当前版本数据是固定 helper wrapper 的唯一事实来源；snapshot JSON 只能作为对账和测试产物。
  - `GodotBindingUsageSession` 必须把 interface/builtin/utility/fixed snapshot 作为 provided set 预加载；module-local header renderer
    只输出不在 provided set 中的 wrapper。
  - 同名不同签名必须报错，不能靠 include 顺序或链接顺序掩盖。
- fixed helper 扫描遗漏：
  - scanner 不再负责发现 wrapper；FreeMarker 拼接、Java helper 名称、`_Generic`、`##` token pasting 都必须通过版本化源码清单或全量预生成集合覆盖。
  - `check-fixed` 必须作为固定 helper 修改后的验收步骤，只报告不一致并 fail-fast，不自动修改清单或生成物。
- enum/常量遗漏：
  - `godot_PROPERTY_HINT_*`、`godot_PROPERTY_USAGE_*`、顶层 `global_constants[]` 和 class constants 不在
    `gdextension_interface.h` 中，必须从 Godot metadata 或 ExtensionAPI 生成。
  - `global_constants[]` 即使在 Godot 4.5.1 中为空也不能写成后续按需解析；必须由 `ExtensionAPI.globalConstants()`
    和 `godot_global_constants.h` 固化为空集合/空 header，以便后续 Godot 版本或 helper 使用时不绕过模型。
  - 不允许通过继续 include `generated/global_enums.h` 来绕过 `gdextension-lite` 移除。
- 初始化顺序漂移：
  - `godot_initialize_interface(...)` 必须在 `gdextension_entry(...)` 中、任何 `godot_*` wrapper 使用前执行。
  - `gdcc_init()` 仍只在 scene-level `initialize(...)` 中执行。
- helper 名称冲突：
  - `gdcc_*` 继续只表示 GDCC-owned helper。
  - `godot_*` 表示 Godot binding wrapper，不表示 gdextension-lite vendor 代码。
- ownership 漂移：
  - 新 wrapper 只改变调用实现，不改变 `CBodyBuilder` 的 `OWNED` / `BORROWED` 决策。
  - 表示转换 helper 不得新增 retain/release。
- exact engine route 回归：
  - 不允许为了补 wrapper 而让 migrated exact engine route 回到 `godot_<Owner>_<method>`。

## 完成定义

迁移完成必须同时满足：

- `src/main/c/codegen/include_451/gdextension-lite.zip` 已删除。
- 新资源抽取不会生成 `gdextension-lite` 目录；旧工作区若已残留该目录，迁移时手动删除。
- `entry.c` 不出现 `gdextension_lite_initialize`。
- native 编译输入不包含 `gdextension-lite-one.c`；即使 `shared-include` 或项目 `include` 中预先残留该文件，也不会被
  `CProjectBuilder` 加入 `cFiles`。
- `CProjectBuilder` 不再使用旧 vendor 文件存在性作为编译输入规则；静态 runtime support 只来自显式的
  `<includeRoot>/godot/godot_binding.c`，该文件缺失时构建准备阶段 fail-fast。
- `ResourceExtractor` 的非删除语义不会重新引入旧 vendor：运行时构建路径要么不再盲目抽取 vendor zip，要么 vendor zip
  已被删除；已知旧 `<includeRoot>/gdextension-lite` 子树是手动迁移清理项，不由运行时构建路径自动删除。
- `entry.c`、`entry.h`、`engine_method_binds.h` 能只依赖 `gdcc/**` 与 `godot/**` 编译。
- `ExtensionApiLoader` 不再丢弃 `builtin_class_sizes`、`builtin_class_member_offsets`。
- `ExtensionAPI` 承载顶层 `global_constants[]`；`ClassRegistry`、裸常量 lowering 和 `@GlobalScope` static-load
  都从该模型读取；`godot_global_constants.h` 从该模型全量预生成，当前 4.5.1 为空时也稳定输出空 header。
- builtin layout metadata 能按 `float_32`、`float_64`、`double_32`、`double_64` 查询 size、member offset 和 member meta；
  这些查询只作为上游 metadata 完整性验证。C backend ABI support 只支持 `float_64`，`REAL_T_IS_DOUBLE` 与 32-bit
  target 都必须在 generated header 层 fail-fast。
- `ExtensionGdClass.PropertyInfo` 保留 engine property `getter`、`setter`、nullable `index`，且
  `getGetterFunc()` / `getSetterFunc()` 返回 raw Godot method name。
- engine property resolver 使用 raw getter/setter method metadata 和 nullable index 生成调用材料；ordinary、indexed、
  getter-only、metadata unknown fallback、GDCC getter-self/setter-self 都有回归测试覆盖。
- `godot_interface.h/.c` 从目标 Godot 4.5.1 `gdextension_interface.h` 的 `@name` / function pointer typedef 配对
  全量生成 interface function wrapper，且不按模块使用集裁剪。
- interface generator 明确排除没有 `@name` 的 `GDExtensionInterfaceGetProcAddress`，正确处理
  `GDExtensionsInterfaceEditorHelpLoadXml...` typedef 拼写异常，并对缺失配对、重复 `@name`、同名不同 ABI fail-fast。
- interface、builtin 和 module-local wrapper 的 ABI model 保留 `GDExtensionUninitialized*Ptr` destination 语义；
  generated wrapper 不把 uninitialized destination 改写成 initialized pointer，且失败路径不 destroy 未初始化 storage。
- `godot_builtin.h/.c` 按 GDCC builtin wrapper 生成 contract 从 `ExtensionAPI.builtin_classes[]` 全量预生成 filtered raw metadata wrapper
  和 synthesized compatibility wrapper，且不按模块使用集裁剪。
- constructor/operator 过滤、单 `String` 参数 char helper、`Variant` conversion helper、indexed/keyed accessor、
  typed container helper、member/method 冲突过滤、member offset/meta 消费和 C 函数名冲突 fail-fast 都有 generator 测试覆盖。
- `godot_utility.h/.c` 从 `ExtensionAPI.utility_functions[]` 全量预生成 utility function wrapper，且不按模块使用集裁剪。
- interface wrapper 的 function pointer 在 `godot_initialize_interface(...)` 中 eager resolve；builtin method、utility function、
  exact engine method bind、module-local engine method/property wrapper 的 lazy lookup 都只缓存非空结果。任何 lookup
  返回空时统一走 lookup failure helper，并输出包含 owner/name/hash 候选的诊断。
- interface wrapper hot path 使用 header-level `static inline` 转发到单定义 hidden `gdcc_interface_*` pointer table；
  阶段 1D 完成后的人工符号表检查确认 generated shared library 不默认导出 `godot_*` interface wrapper、
  `gdcc_interface_*` 指针表、`godot_initialize_interface(...)` 或 `gdcc_binding_lookup_fail(...)`。
- class method bind lookup 覆盖 primary hash 与 `hash_compatibility` 候选；utility hash 冲突没有兼容路径，必须 fail-fast。
- 固定 helper 所需且不被 interface/builtin/utility 覆盖的非 interface wrapper 由 `FixedGodotBindings` 当前版本数据生成到 `godot_fixed_binding.h/.c`，不是手写散落在多个 C 文件中。
- `GodotBindingTool` 提供可命令行调用的 `main`，支持 `generate-interface`、`generate-builtin`、`generate-utility`、
  `generate-abi-support`、`check-fixed`、`generate-fixed`、`dump-fixed-manifest`。
- `check-fixed` 与 generated C 扫描只做 fail-fast 验收，不负责发现 wrapper、不负责补登记、不负责生成。
- 模块级 wrapper 生成加载 interface/builtin/utility/fixed provided set，脚本再次使用预生成或固定 wrapper 时不会生成重复 C 函数。
- 模块级 wrapper 输出为 `engine_method_binds.h` 内的 header-only `static inline` section；`godot_binding.h/.c` 不 include
  module-local wrapper，`CProjectBuilder` 不新增 `projectPath` include dir，也不新增 `projectPath/godot_module_bindings.c`
  之类的模块级 `.c` 输入。
- `entry.c` 在 `gdextension_entry(...)` 中调用 `godot_initialize_interface(...)`，失败时返回 `false`，scene-level lifecycle guard 保持不变。
- `GDE_EXPORT`、`godot_PROPERTY_HINT_*`、`godot_PROPERTY_USAGE_*`、`godot_METHOD_FLAG_*`、`godot_global_constants.h`、
  native struct、builtin type、builtin size assert 和 builtin member offset/meta assert 都来自 GDCC 自有 `godot/**` 头文件。
- `implementation-macros.h`、`definition-macros.h`、`generated/global_enums.h`、`generated/native_structures.h`、`generated/variant/sizes.h` 不再被运行时代码或生成代码 include。
- 现有测试已按阶段反向更新：
  - 构建输入测试断言 `godot_binding.c` 和 `godot` include root，而不是 `gdextension-lite-one.c` / `gdextension-lite` include root。
  - generated file 集合测试继续锁定 `entry.c`、`engine_method_binds.h`、`entry.h`，并验证 module-local wrapper section
    只写入 `engine_method_binds.h`，不会新增其它 generated file。
  - exact engine method bind、typed container ABI 和 ownership lifecycle 相关断言保留。
- targeted unit tests 和需要环境支持的 runtime integration tests 通过。
- `doc/module_impl` 下每一份文档已被归类为 `current-contract` / `updated-contract` / `historical-reference`，
  且现行合同统一区分 raw exported metadata、GDCC normalized publication layer 和 downstream consumer。
- 文档、源码注释、模板注释、test display name 和 failure message 已把 gdextension-lite 降级为历史命名来源或迁移前行为，
  而不是当前编译依赖、当前初始化模型、当前 wrapper fact source 或当前 generated file 公共契约。
