# .gd3 虚拟脚本语言编辑器集成实施计划

> 本文档是 `src/editor_addon` Godot 编辑器集成功能的实施计划：通过 GDExtension 注册一个
> 虚拟 `ScriptLanguageExtension` + `ScriptExtension`，让 Godot 4.5 编辑器把 `.gd3` 识别为
> 脚本资源（可打开、编辑、保存），并把解析/补全请求代理到引擎自带的 GDScript 实现（编辑器
> 内建 LSP 服务），同时在 GDScript 判定无误后叠加 gdcc 前端的诊断。全部集成代码用 `.gd3`
> 编写，经 gdcc 编译进与 `gdcc_rpc_client.gd3` 相同的 `gdcc_for_editor` 原生库。
> `.gd3` 从不作为可运行脚本挂载；运行时行为完全由 gdcc 编译出的原生类承担。

## 文档状态

- 状态：实施计划（Phase 0 已实施并通过验收；Phase 1+ 尚未实施；已经过多轮并行评审并修订）
- 更新日期：2026-09-23
- Phase 0 验收结果（2026-09-23）：P0-A/B/C 全绿（`EditorAddonIntegrationProbeTest`
  3/3 通过，无跳过）。`_init` 语义与 typed array 返回的探针结论已回填 §3.2/§2.3。
  探针暴露并已修复两个 gdcc 后端缺陷（阻塞级，修复侧已含回归测试）：
  1. 绑定包装器符号名冲突：`BindingData` 相等性区分 typed 容器元素类型，但 bind name
     编码把所有 `GdArrayType`/`GdDictionaryType` 折叠为 `Array`/`Dictionary`，同类中两个
     仅容器元素类型不同的方法（如 `Array[Dictionary]` 与 `Array[StringName]` 返回）生成
     重复 C 符号。修复：bind name 编码嵌入容器元素类型、且容器元素段采用长度前缀
     （`Array_<len>_<elem>`，`Dictionary_<klen>_<key>_<vlen>_<value>`；类型名自身可含
     `_`，裸 `_` 拼接仍可有二义），泛型容器拼写不变，见
     `CGenHelper.renderFuncBindTypeName`。
  2. `ClassRegistry.getRefCountedStatus` 的 GDCC 类继承链遍历在遇到首个引擎祖先时
     不查引擎元数据，直接落 NO：经引擎类间接继承 RefCounted 的 GDCC 类
     （`extends ScriptExtension` / `ResourceFormatLoader` 等）被误判为非 RefCounted，
     `ConstructInsnGen` 因此跳过 `gdcc_ref_counted_init_raw` 归一化，新建对象保持
     未跟踪态（refcount=1, refcount_init=1）；`call_` 包装器打包时
     `Variant(Object*)` 的 `init_ref` 对新对象是**收养**语义（净增 0，Variant 接管
     初始创建引用，Godot 4.5 variant.cpp/ref_counted.cpp），随后的 consume 释放把
     刚被收养的引用释放回 0——gdcc 方法向解释侧返回新建对象得到 null。修复：
     遍历时用引擎元数据 `isRefcounted()` 判定首个引擎祖先（与
     `ObjectReturnConsumptionLeakIntegrationTest` 钉住的"构造点 init_ref 归一化 +
     包装器 consume 净零转移"既有设计一致）。该缺陷阻断一切"gdcc 方法返回新建
     RefCounted 对象"的路径（含 `_create_script`）。
- 范围：
  - `src/editor_addon/addons/gdcc/**`（新增 `.gd3` 源文件 + `server_launcher.gd` +
    `plugin.gd`/`gdcc_dock.gd` 接线）
  - `src/main/java/gd/script/gdcc/rpc/**`（新增 `server.shutdown` RPC 方法，§2.8）
  - `doc/module_impl/api/json_rpc_service_implementation.md`（同步登记 `server.shutdown`）
  - `src/test/java/gd/script/gdcc/rpc/**`（安装器与测试增补）
  - `src/test/resources/**`（探针与引擎驱动 fixture）
- 直接事实源：
  - `doc/module_impl/api/json_rpc_service_implementation.md`（RPC 方法面、`.gd3` 客户端约束、
    自举测试模式）
  - `doc/module_impl/api/rpc_api_implementation.md`（`analyze.run` 合同）
  - `doc/module_impl/common_rules.md`
  - `src/main/resources/extension_api_451.json`（Godot 4.5.1 绑定元数据，签名事实源）
  - Godot 4.5 分支源码（见 §2 各节引用）
- 明确非目标：
  - `.gd3` 的运行时执行、调试器、性能分析、重构工具、`.gd3` 间的脚本继承。
  - 把 `plugin.gd` / `gdcc_dock.gd` 迁移为 gdcc 编译目标（沿用
    `json_rpc_service_implementation.md` 的非目标约定）。
  - 非编辑器（导出模板）环境下注册本语言；本功能产出的 GDExtension 仅在编辑器内使用。
  - 自研 LSP 服务端；这里只做 LSP 客户端。
  - 管理**非本插件拉起**的 gdcc 服务进程的生命周期（外部启动的服务只连接、绝不关闭，
    §3.7 所有权规则）。

---

## 1. 目标与总体设计

### 1.1 目标

1. Godot 编辑器 FileSystem 面板把 `.gd3` 显示为脚本资源，双击在内置脚本编辑器中打开，
   可编辑、可保存（纯文本往返）。
2. 脚本编辑器对 `.gd3` 提供错误/警告标注：优先采用引擎 GDScript 解析结果（经编辑器内建
   LSP 服务），GDScript 侧无错误时叠加 gdcc 前端 `analyze.run` 的诊断（合并显示）。
3. 代码补全代理到 GDScript LSP（承担大部分补全能力）。
4. 悬浮/符号查找（`_lookup_code`）、自动缩进、全局类名、脚本模板为后续打磨项。
5. gdcc 编译服务未在目标端口侦听时，可按**用户配置的启动命令**自动拉起
   `gdcc serve`；插件卸载/编辑器退出时正确关闭**由本插件拉起**的服务进程
   （外部启动的服务绝不触碰）。未配置启动命令时保持现有"被动连接、失败即报错"
   行为不变（§3.7）。
6. 全部新代码为 `.gd3`，与 `gdcc_rpc_client.gd3` 同一模块（`gdcc_for_editor`）编译进同一
   GDExtension 库；遵守该客户端的全部自举约束（无 `await`、无协程等，见 §5）。
   唯一例外是 `server_launcher.gd`（解释型 GDScript）：dock 在 gdcc 语言注册前就
   需要使用它，与 `plugin.gd`/`gdcc_dock.gd` 同理不迁入编译目标。

### 1.2 总体架构

```text
Godot 编辑器
├── plugin.gd（解释型 GDScript，已有）
│     └── _enter_tree:
│           ├── 在 SceneTree 根下查找/验证常驻节点 GdccEditorService（身份不符则
│           │   fail-closed 放弃，零副作用，先于任何设置改动；§3.6）
│           ├── 设置 network/language_server/use_thread = true（LSP 线程模式硬前提；
│           │   记录原值，卸载时恢复；该设置退出编辑器时会被引擎持久化，见 §2.6）
│           ├── install(...)（失败回滚并恢复 use_thread）→ 连接 filesystem_changed
│           ├── server_launcher.gd（解释型 GDScript，新增）：按配置的启动命令拉起
│           │   gdcc serve、记录 PID，退出时仅关闭自己拉起的进程（§3.7）
│           └── （延迟一帧）EditorFileSystem.scan() —— 仅运行期启用插件时
├── gdcc_for_editor GDExtension（gdcc 编译产物，本计划扩展；安装为 reloadable = false）
│     ├── GdccRpcClient            （已有，异步 HTTP JSON-RPC → gdcc serve :6099）
│     ├── GdccEditorService (Node) 进程级常驻单实例（§3.5）：语言/加载器/保存器的注册与
│     │                            注销、诊断缓存、私有诊断模块生命周期、gdcc 异步分析
│     │                            调度（防抖 + 单飞 + 版本校验，经服务专用 RPC 客户端）、
│     │                            LSP 连接管理
│     ├── GdccScriptLanguage (ScriptLanguageExtension)
│     │     ├── _validate       → LSP 诊断（线程模式下有界同步等待 ≤150ms）
│     │     │                     + 缓存的 gdcc 诊断合并（异步产生，§4.4）
│     │     ├── _complete_code  → 0xFFFF 哨兵定位光标 → LSP completion（≤400ms）
│     │     └── _lookup_code    → LSP hover/definition（Phase 5）
│     ├── GdccScript (ScriptExtension, _can_instantiate() == false)
│     ├── GdccScriptFormatLoader (ResourceFormatLoader，_load 纯加载、线程安全)
│     ├── GdccScriptFormatSaver  (ResourceFormatSaver)
│     └── GdccLspClient (Node)   最小 LSP 客户端：StreamPeerTCP → GDScript LSP
│                                  （默认 127.0.0.1:6005，线程模式下服务端独立线程轮询）
└── gdcc serve（已有）             analyze.run → gdcc 前端诊断；
                                   可外部常驻，或由 server_launcher 按配置拉起/关闭（§3.7）
```

关键设计约束：

- `_validate` / `_complete_code` / `_lookup_code` 被编辑器 UI 线程**同步**调用。
  允许的唯一阻塞形态：非阻塞 `poll()` + `Time.get_ticks_msec()` 单调时钟截止的紧凑
  轮询循环，带硬超时与降级；**禁止** `HTTPClient.set_blocking_mode(true)`（单次
  `poll()`/读体可能无限卡住，外层时钟无法中断），**禁止** 在主线程使用
  `OS.delay_msec`（冻结绘制与输入）。超时必须关闭连接以防悬挂。唯一豁免：
  `_exit_tree` 中 `shutdown_owned` 的 ≤2s 有界等待（此时帧泵可能已停，只允许
  ticks 截止循环，§3.7）。
- 同步等待仅在 LSP 服务端为线程模式时有效（§2.6）；非线程模式下直接降级。
- 未实现的引擎虚函数落到基类 GDVIRTUAL 默认实现：不会崩溃，但元数据中
  `is_required=true` 的虚函数首次被调会打印 `ERR_PRINT_ONCE`。凡编辑器路径可能触及
  且签名可表达的 required 虚函数都显式实现（哪怕是 no-op）；仅 `void*`/
  `ProfilingInfo*` 等签名不可表达的条目允许跳过（§2.2/§2.3）。
- `GdccScriptFormatLoader._load` 可能运行在 worker 线程（`ResourceLoader` 线程加载），
  必须是无副作用的纯加载：不触碰 Node、LSP、RPC、诊断缓存（§2.4）。

---

## 2. Godot 4.5 集成点事实核查（已验证）

以下结论均已对照 Godot 4.5 分支源码或本仓库 `extension_api_451.json` 核实。

### 2.1 ScriptLanguage 注册

- `Engine.register_script_language(language: ScriptLanguage) -> Error` 与
  `Engine.unregister_script_language` 是公开绑定 API（`core/config/engine.cpp`，
  内部转调 `ScriptServer::register_language`）。本仓库
  `src/main/resources/extension_api_451.json:104185` 起可查到全部相关方法
  （含 `get_script_language_count` / `get_script_language`）。
- `ScriptServer` 上限 16 种语言；`register_language` 只入表，不会为迟注册语言补调
  `init()`（`init_languages()` 仅在启动时执行一次）；`unregister_language` 只从数组
  移除指针，不析构对象、不更新已加载脚本。因此语言初始化全部在构造后显式完成，
  注销后实例由我们自管（§3.5）。
- 参考实现 `gilzoide/lua-gdextension` 在 GDExtension SCENE 初始化级别创建语言单例并
  注册，证明纯 GDExtension 路径可行。
- 已知限制（Godot 4.5，godotengine/godot#106480）：`ScriptCreateDialog` 在
  `ScriptEditor` 初始化时构造并一次性缓存语言列表——**早于**任何 addon 的
  `_enter_tree`，因此 4.5 中本语言**不会**出现在"创建脚本"对话框的 Language 下拉中，
  与注册时机无关（§9 R5）。

### 2.2 ScriptLanguageExtension 合同

`doc/classes/ScriptLanguageExtension.xml`（4.5 分支）中绝大多数虚函数标注
`virtual required`，对应 `extension_api_451.json` 中 `is_required=true`。
`core/object/script_language_extension.h` / `.cpp` 为基类全部纯虚函数提供了 GDVIRTUAL
转发 + 安全默认值（空/0/false）：**未实现不会崩溃**，但 required 虚函数首次被调用会
打印 `ERR_PRINT_ONCE`（`core/object/make_virtuals.py` 生成的守卫）。分档策略：

- A 档（显式实现，决定核心体验与日志干净）：`_get_name`、`_get_type`、`_get_extension`、
  `_get_recognized_extensions`、`_get_reserved_words`、`_is_control_flow_keyword`、
  `_get_comment_delimiters`、`_get_string_delimiters`、`_validate`、`_validate_path`、
  `_create_script`、`_supports_builtin_mode`（返回 false，见 §3.2）、
  `_can_inherit_from_file`、`_handles_global_class_type`、`_thread_enter`、
  `_thread_exit`、`_init`（no-op，语义见 §3.2）、`_finish`（no-op）、`_frame`（no-op）、
  `_has_named_classes`（4.5 已废弃但 required，返回 false）、`_find_function`、
  `_make_function`、`_can_make_function`、`_open_in_external_editor`、
  `_overrides_external_editor`、`_is_using_templates`、`_supports_documentation`、
  `_add_global_constant`、`_add_named_global_constant`、`_remove_named_global_constant`、
  `_reload_all_scripts`、`_reload_scripts`、`_reload_tool_script`。
- B 档（功能实现）：`_complete_code`、`_lookup_code`、`_auto_indent_code`、
  `_get_global_class_name`、`_make_template`、`_get_built_in_templates`、
  `_get_doc_comment_delimiters`（非 required）、`_preferred_file_name_casing`
  （非 required）。
- C 档（跳过，接受默认值与可能的 `ERR_PRINT_ONCE`）：全部 `_debug_*`、
  `_profiling_*`、`_get_public_functions`、`_get_public_constants`、
  `_get_public_annotations`。其中 `_debug_get_stack_level_instance` 返回 `void*`、
  `_profiling_get_*` 接收 `ScriptLanguageExtensionProfilingInfo*`，gdcc 无法表达；
  `_get_public_*` 返回 `Dictionary[]`。**探针结论（2026-09-23，P0 全绿）：typed array
  返回可实现**——`ProbeLang._get_public_functions/_get_public_annotations`
  （`-> Array[Dictionary]`）与 `ProbeScript._get_documentation/_get_members`
  （`-> Array[Dictionary]` / `-> Array[StringName]`）通过 analyze+lowering 与 native
  compile；P0-C 运行时经 `found._get_public_functions()`/`script._get_documentation()`/
  `script._get_members()` 实调验证虚分发与空 typed array 往返
  （`typed_array_virtuals` 步骤）。实现期应把这些条目补为显式空实现以消除日志噪音。调试/性能分析本就在非目标内，这些路径不会被触发。

关键 Dictionary 契约（逐字来自 `core/object/script_language_extension.h`，4.5）：

`_validate(...) -> Dictionary`：

| 键 | 类型 | 说明 |
|---|---|---|
| `valid` | bool | 缺省视为 false |
| `errors` | Array[Dictionary] | 每项必须含 `line:int`、`column:int`、`message:String`；`path:String` 在本计划中**必填**（缺省时 `ScriptTextEditor` 会把错误归入"依赖脚本错误"而非当前文件，见 §4.2） |
| `warnings` | Array[Dictionary] | 每项必须含 `start_line:int`、`end_line:int`、`code:int`、`string_code:String`、`message:String`，缺任一键被引擎丢弃 |
| `functions` | PackedStringArray | 可选，本计划不提供 |
| `safe_lines` | PackedInt32Array | 可选，本计划不提供 |

`_complete_code(code, path, owner) -> Dictionary`：

| 键 | 类型 | 说明 |
|---|---|---|
| `result` | int(Error) | 缺失时引擎按 `ERR_UNAVAILABLE` 处理 |
| `force` | bool | 必填 |
| `call_hint` | String | 必填（可为 `""`） |
| `options` | Array[Dictionary] | 每项**必填**：`kind:int`、`display:String`、`insert_text:String`、`font_color:Color`、`icon`（可为 null）、`default_value`（可为 null）、`location:int`；可选 `matches:PackedInt32Array`（成对） |

`_lookup_code(code, symbol, path, owner) -> Dictionary`：必填 `result:int(Error)`、
`type:int(LookupResultType)`；可选 `class_name`、`class_member`、`description`、
`is_deprecated`、`deprecated_message`、`is_experimental`、`experimental_message`、
`doc_type`、`enumeration`、`is_bitfield`、`value`、`script:Script`、`script_path`、
`location:int`。

`_get_global_class_name(path) -> Dictionary`：`name`（缺省视为无全局类）、可选
`base_type`、`icon_path`、`is_abstract`、`is_tool`。

`_get_built_in_templates(object) -> Array[Dictionary]`：每项必填 `inherit`、`name`、
`description`、`content`、`id:int`、`origin:int`。

### 2.3 ScriptExtension 合同

`doc/classes/ScriptExtension.xml`（4.5）。未实现项走基类 GDVIRTUAL 默认。实现清单：

- `_can_instantiate() -> bool`：恒 `false`（`.gd3` 从不实例化）。
- `_get_language() -> ScriptLanguage`：返回进程级唯一的 `GdccScriptLanguage` 实例
  （§3.5）。
- `_has_source_code() -> bool` → true；`_get_source_code()/_set_source_code(code)`：
  内存字符串字段。
- `_reload(keep_state: bool) -> int(Error)`：按 `_load` 传入的路径重读文件、更新
  `_source` 与解析缓存字段。**不触发**任何分析/调度（线程安全，见 §2.4）。
- `_is_valid() -> bool`：加载/校验状态。
- `_is_tool() -> bool`：恒 `false`（避免编辑器尝试执行）。
- `_get_instance_base_type() -> StringName`：解析 `extends` 行，缺省 `&"RefCounted"`。
- `_get_global_name() -> StringName`：解析 `class_name` 行，缺省空。
- `_get_base_script() -> Script`：null。`_inherits_script(script) -> bool`：false。
  `_instance_has(object) -> bool`：false。
- `_get_doc_class_name() -> StringName`、`_get_documentation()`（空；typed array 可表达
  已由探针证实，见 §2.2 C 档结论）、`_get_class_icon_path()`（空串，非 required）。
- `_has_method/_has_static_method/_has_script_signal` → false；
  `_has_property_default_value` → false、`_get_property_default_value` → null
  （required，显式实现避免日志噪音）；`_get_method_info` → `{}`（required，同理）；
  `_get_member_line` → -1。
- `_editor_can_reload_from_file() -> bool` → true。
- `_update_exports()` → 空操作。`_get_rpc_config()` → null（required，显式实现）。
- `_is_placeholder_fallback_enabled()` → false。
- 跳过（`void*` 签名 gdcc 无法表达，基类默认返回 nullptr，与
  `_can_instantiate() == false` 自洽）：`_instance_create`、
  `_placeholder_instance_create`、`_placeholder_erased`。残留风险：`.gd3` 被误挂载到
  节点时编辑器拿不到占位实例（§9 R7）。
- 跳过（`TypedArray[StringName]`/`TypedArray[Dictionary]` 返回类型的 override 支持已
  由探针验证可实现，见本节上方与 §2.2 C 档的探针结论；默认空列表行为安全）：
  `_get_members`、`_get_constants`、`_get_script_method_list`、
  `_get_script_property_list`、`_get_script_signal_list`。实现期补为显式空实现。

### 2.4 ResourceFormatLoader / ResourceFormatSaver

仅注册语言不足以让 `ResourceLoader` 加载 `.gd3`；必须注册自定义加载器/保存器。
`ResourceLoader.add_resource_format_loader()` 与 `ResourceSaver.add_resource_format_saver()`
在 4.5 均为公开绑定（本仓库 json `:247570` / `:247958`）。参考实现
`lua-gdextension/src/script-language/LuaScriptResourceFormatLoader.cpp` 确认五个核心
虚函数：`_get_recognized_extensions`、`_handles_type`、`_get_resource_type`、
`_exists`、`_load(path, original_path, use_sub_threads, cache_mode)`，以及缓存模式
惯例（`CACHE_MODE_REUSE` 用 `ResourceLoader.get_cached_ref`，`CACHE_MODE_REPLACE*`
用 `take_over_path`）。保存器核心虚函数：`_save(resource, path, flags) -> int(Error)`、
`_recognize(resource) -> bool`、`_get_recognized_extensions(resource) -> PackedStringArray`。
可选虚函数（`_get_resource_uid`、`_recognize_path`、`_get_dependencies`、`_set_uid`）
在实现阶段对照 4.5 XML 补验。

两条硬性约束：

1. **线程安全**：`_load` 可能在 `WorkerThreadPool` 上执行
   （`core/io/resource_loader.cpp` 的 threaded load 路径）。实现必须是纯加载：
   只用入参 `path`/`original_path` 与 `FileAccess` 读文件、创建并填充 `GdccScript`，
   不调用 `get_path()`（引擎在 `_load` 返回后才绑定路径）、不触碰任何 Node/服务/缓存。
2. **依赖注入**：loader/saver 由 `GdccEditorService` 创建时经 `setup(language)` 注入
   语言实例，禁止依赖单例或静态字段。

### 2.5 脚本编辑器集成

- `editor/script/script_text_editor.cpp`：`ScriptTextEditor` 是通用脚本编辑器，对任意
  `Script` 资源按其 `get_language()` 分发：`_validate_script()` 调用语言的
  `validate(...)`（传入**编辑缓冲区当前文本**而非磁盘文本），并显示错误/警告/安全行；
  `_code_complete_script()` 调用 `complete_code(...)`；符号跳转/悬浮调用
  `lookup_code(...)`。语法高亮所需的分隔符/关键字来自语言的分隔符接口。
  无需自研编辑器 UI。
- `_validate_script()` 的触发点（4.5 实测）：页签启用（`enable_editor`）、
  `reload_text()`（外部重载）、编辑器 idle 定时器超时
  （`CodeTextEditor._text_changed_idle_timeout`，one-shot，由文本变更或
  `CodeTextEditor.validate_script()` 重启；`validate_script()` 未绑定到脚本层，
  GDExtension/解释型脚本均**无法主动触发**重新校验——这决定 gdcc 诊断只能异步
  合并、等下一次校验节拍，见 §4.4 与 §9 R17）。错误计数为 0 时 idle 间隔
  `text_editor/completion/idle_parse_delay`（默认 1.5s），有错误时
  `idle_parse_delay_with_errors_found`（默认 0.5s）。
- 光标位置传递机制：请求补全/查找时，编辑器把光标位置以哨兵字符
  `String.chr(0xFFFF)` 插入源码（`scene/gui/code_edit.cpp`），语言实现需自行定位并
  剔除；GDScript 解析器以同一哨兵确定补全上下文
  （`modules/gdscript/gdscript_parser.cpp`）。`validate` 的入参文本**不含**哨兵。
- `.gd3.uid`：4.4+ 编辑器会为脚本生成 `.uid` 伴生文件，对本计划无害。

### 2.6 GDScript LSP 服务（编辑器内建）

- `GDScriptLanguageServer` 是随 GDScript 模块注册的编辑器插件
  （`modules/gdscript/register_types.cpp`，编辑器构建时挂到 `EditorNode`），
  在 `EditorNode::is_editor_ready()` 之后才开始监听——**晚于插件 `_enter_tree`**，
  客户端必须重试连接（§4.1）。默认 `127.0.0.1:6005`（EditorSettings
  `network/language_server/remote_host` / `remote_port`，命令行 `--lsp-port` 可覆盖，
  覆盖值无法运行时查询）。
- **线程模式（硬前提）**：`network/language_server/use_thread` 默认 `false`，此时
  服务端只在主线程帧通知里 `poll()`；`_validate`/`_complete_code` 同在主线程执行，
  若在其中阻塞等待响应，服务端永远得不到执行机会，必然超时且每次冻结编辑器。
  因此本计划要求把该设置置为 `true`（插件 `_enter_tree` 早于服务启动，正常启动
  路径生效）。注意两点实测行为：
  1. `EditorSettings.set_setting` 后**退出编辑器时引擎会自动持久化**该设置
     （`EditorSettings::destroy()` 调 `save()`）——不存在"会话级"设置。因此插件必须
     在卸载时恢复用户原值（§4.1），并在文档与 dock 状态中明示这一改动。
  2. 服务端在运行期监听该设置变化并自动重启监听（`gdscript_language_server.cpp`），
     因此运行期启用插件不需要重启编辑器，我们的重连逻辑会接管新监听（实现期验证，
     列入 Phase 2 验收）。
- 协议为标准 LSP（`Content-Length` 帧头 + JSON-RPC）。已实现方法含 `initialize`、
  `textDocument/didOpen|didChange|didClose|completion|hover|definition|references|
  rename|documentSymbol` 等；诊断由服务端经 `textDocument/publishDiagnostics` 推送，
  但默认只发给最近初始化的那个客户端（`LSP_MAX_CLIENTS=8`，`notify_client` 默认
  目标是 `latest_client_id`）：外部 LSP 客户端（如 VSCode）并存时诊断可能发给别人，
  此时按超时降级到缓存（§9 R14）。
- `initialize` 结果中 `capabilities.textDocumentSync` 在 4.5 是**对象**
  `{openClose, change: 1, willSave, willSaveWaitUntil, save}`（`change == 1` 即全量
  同步），不是整数 `1`；握手校验必须两种形状都接受（§3.4）。
- 该 LSP 是 GDScript 专用：`didOpen` 不检查扩展名/`languageId`（固定填
  `"gdscript"`），单文件解析与诊断对 `.gd3` URI 可用；但 workspace 索引只收 `.gd`，
  跨文件符号/类缓存解析对 `.gd3` 天然受限（§9 R1）。
- 工作区校验：服务端的 `rootUri`/`rootPath` 不匹配时不会拒绝服务，而是发
  `gdscript_client/changeWorkspace` 自定义通知并继续为本进程项目服务；客户端应以该
  通知 + `ProjectSettings.globalize_path("res://")` 判定是否连错编辑器实例，不能从
  `initialize` 返回值读取工作区（§9 R2）。

### 2.7 gdcc 侧既有基础

- `analyze.run`（同步、不落盘、不触发 C 后端）返回 `AnalysisResult`；诊断的 JSON 形状
  为 `result.diagnostics.diagnostics[]`（外层是快照对象，不是裸数组），条目字段
  `{severity, category, message, sourcePath, range}`，`sourcePath`/`range` 可空，
  `range` 含 `startByte/endByte` 与 1-based 的 `start/end{line,column}`
  （`json_rpc_service_implementation.md` §2.2/§4.4，编解码测试
  `RpcJsonCodecTest` 可佐证）。`analyze.run` 是同步整模块分析，在模块门闩上与其他
  编译/分析串行；服务端没有 analyze 取消/超时 API。
- `includeLowering=false` 只跑共享语义管线；`includeLowering=true` 额外执行
  compile-only 门禁（`sema.compile_check`）与 lowering——仅前者会漏掉"GDScript 合法
  但 gdcc 无法编译"的错误（`ApiAnalyzeTest` 有实证）。编辑器诊断目标是提前暴露真实
  编译失败，故默认使用 `includeLowering=true`（可配置，见 §3.5）。
- 编辑器插件已有 `GdccRpcClient` 挂起对象 + `process_frame` 帧泵模式；dock 的
  `auto_setup_module` 会创建/重建**它自己的**模块。编辑器诊断使用独立私有模块，
  由 `GdccEditorService` 独占创建/删除，与 dock 模块互不可见，避免创建/删除竞争与
  VFS 互相清空（§4.4）。
- gdcc 注册多类、engine virtual 严格签名校验（frontend+backend 双层，frontend 在
  analyze 阶段即拒绝错误签名）已具备：签名写错会在 gdcc 编译期报错，是签名合同的
  回归测试；但 analyze+lowering 不覆盖 C 代码生成与链接，故 Phase 0 另设 native
  compile 探针（§5、§7）。

### 2.8 gdcc 服务进程管理（已核实）

- 服务端启动形态：`java -jar build/libs/gdcc-0.0.2.jar serve [--host H] [--port P]
  [--max-request-bytes N]`（`Main.java:12-23` 路由到 `RpcServeCommand.java:36-46,
  85-94`；`--host` 缺省 `127.0.0.1`、`--port` 缺省 `6099`、`0` 为临时端口）。JAR
  为普通 JAR + 旁置 `lib/`（manifest `Class-Path`），启动命令的工作目录或 jar 路径
  必须保证 `lib/` 相对可达（`build.gradle.kts:159-169`）。
- 协议方法表 25 个方法中**没有**服务级 shutdown（`json_rpc_service_implementation.md`
  方法表与 `JsonRpcMethodRegistry.java:64-174` 一致）；notification 只是 HTTP 204
  不退出进程（`JsonRpcDispatcher.java:87-89,161-167`）。进程级优雅停止依赖 JVM
  shutdown hook：`server.close()` + `API.close()`（取消在途编译、最长约 30s 等待
  runner，`RpcServeCommand.java:65-82`、`JsonRpcServer.java:82-91`、`API.java:39-51,
  442-491`）。
- 因此"正确关闭"必须新增 **`server.shutdown() -> {}` RPC 方法**（本计划的服务端任务，
  Phase 1）。时序合同（现有写回路径决定，违反即死锁）：dispatcher 同步调用 handler，
  **handler 返回后** HTTP 层才 `sendResponseHeaders` + 写 body，`try (exchange)` 在
  `handle` 返回前关闭连接（`JsonRpcHttpHandler.java:63-72`）。因此方法 handler
  **只做**两件事：用 `AtomicBoolean` 保证只登记一次退出意图（幂等，重复调用返回
  相同结果）、返回 `{}`。`JsonRpcHttpHandler` 在该 exchange 写完且
  try-with-resources close 之后检查标志位，**启动一条不属于请求 executor 的平台
  线程**调用 `System.exit(0)`，然后自己正常返回。三条禁令：禁止在方法 handler 内
  启动退出线程；禁止在请求线程上直接 `System.exit`——即使 exchange 已 close，
  JDK `HttpServer.stop(0)` 会等待 handler 返回，hook 的
  `stop(0)`/`executor.close()` 与尚未返回的请求线程互相等待死锁；禁止用 sleep 赌
  写回完成。exit 后由既有 hook 完成优雅收尾（`server.close()` + `API.close()`，
  在途编译取消并最长约 30s 等待 runner）。仅靠 `OS.kill` 不够：Windows 上它是
  `TerminateProcess`，**不会**执行 JVM shutdown hook。验收必须打真实
  `POST /rpc`：读到 200 与 body 之后进程才退出（退出码 0），只断言 dispatcher
  返回值的单测无效。
- Godot 4.5 进程 API（`extension_api_451.json:179748` 起）：
  `OS.create_process(path: String, arguments: PackedStringArray, open_console: bool
  = false) -> int`（返回 PID，失败为负值）；`OS.kill(pid: int) -> Error`；
  `OS.is_process_running(pid: int) -> bool`；`OS.get_process_exit_code(pid) -> int`。
  另有阻塞型 `OS.execute`，本功能禁用（主线程阻塞）。
- 服务端无 PID 文件/单实例锁/多实例发现协议；同端口重复绑定会失败，不同端口可并存。
  拉起前必须先探测目标端口，避免重复拉起或抢占外部实例（§3.7 所有权规则）。

---

## 3. 组件设计

全部位于 `src/editor_addon/addons/gdcc/`，`@tool`、带 `class_name`，与
`gdcc_rpc_client.gd3` 同约束（§5）。模块 VFS 写入路径沿用 `/src/<文件名>.gd3`。

### 3.1 `gdcc_script.gd3` — `GdccScript extends ScriptExtension`

职责：`.gd3` 源文本的资源载体。字段：`_source: String`、`_valid: bool`、
`_global_name: StringName`、`_base_type: StringName`、`_language: GdccScriptLanguage`
（创建时由 `setup(language)` 注入）。方法按 §2.3 清单实现；`class_name`/`extends`
解析用行扫描（按 `\n` 分行 + 前缀匹配），不引入 RegEx 依赖。`_reload` 只重读文件并
更新上述字段，不触发任何分析调度（§2.4 线程安全约束同样适用于此）。

### 3.2 `gdcc_script_language.gd3` — `GdccScriptLanguage extends ScriptLanguageExtension`

字段：`_service: GdccEditorService`（由 `setup(service)` 注入；由于二者同为进程级
常驻实例（§3.5），该引用在整个编辑器会话内有效）。实现清单（签名与 4.5 元数据严格
一致；A/B 档全集见 §2.2）：

```gdscript
func _get_name() -> String:            return "GD3"
func _get_type() -> String:            return "GdccScript"
func _get_extension() -> String:       return "gd3"
func _get_recognized_extensions() -> PackedStringArray
func _get_reserved_words() -> PackedStringArray       # GDScript 关键字全集
func _is_control_flow_keyword(keyword: String) -> bool
func _get_comment_delimiters() -> PackedStringArray   # ["#"]
func _get_doc_comment_delimiters() -> PackedStringArray # ["##"]
func _get_string_delimiters() -> PackedStringArray    # ['" "', "' '", '""" """']
func _validate(script: String, path: String, validate_functions: bool,
        validate_errors: bool, validate_warnings: bool, validate_safe_lines: bool) -> Dictionary
func _validate_path(path: String) -> String:          return ""
func _create_script() -> Object                       # GdccScript.new() + setup(self)
func _supports_builtin_mode() -> bool: return false   # 不允许场景内建 .gd3 脚本（§9 R7）
func _supports_documentation() -> bool: return false
func _can_inherit_from_file() -> bool:  return false
func _handles_global_class_type(type: String) -> bool: return type == "GdccScript"
func _find_function(function: String, code: String) -> int
func _make_function(class_name: String, function_name: String,
        function_args: PackedStringArray) -> String
func _can_make_function() -> bool:      return false
func _open_in_external_editor(script: Script, line: int, column: int) -> int  # ERR_UNAVAILABLE
func _overrides_external_editor() -> bool: return false
func _complete_code(code: String, path: String, owner: Object) -> Dictionary
func _lookup_code(code: String, symbol: String, path: String, owner: Object) -> Dictionary
func _auto_indent_code(code: String, from_line: int, to_line: int) -> String
# 以下为显式 no-op/空实现（消除 required 虚函数的 ERR_PRINT_ONCE，语义上无可做之事）：
func _init() -> void / _finish() -> void / _frame() -> void
func _thread_enter() -> void / _thread_exit() -> void
func _has_named_classes() -> bool:      return false
func _add_global_constant(name: StringName, value: Variant) -> void
func _add_named_global_constant(name: StringName, value: Variant) -> void
func _remove_named_global_constant(name: StringName) -> void
func _reload_all_scripts() -> void
func _reload_scripts(scripts: Array, soft_reload: bool) -> void
func _reload_tool_script(script: Script, soft_reload: bool) -> void
func _is_using_templates() -> bool:     return false    # Phase 5 前
func _get_global_class_name(path: String) -> Dictionary # Phase 5
```

`_init` 语义结论（2026-09-23 探针证实，P0 全绿）：gdcc 把 `func _init() -> void`
建模为类构造钩子（骨架层固定 `void _init(...)`），同时后端按名字匹配把它注册为
`ScriptLanguageExtension._init` 的 engine virtual 分发（`checkVirtualMethod` 走名称
查找）；`() -> void` 签名与元数据一致，两条路径兼容，无需省略该 override。探针
`ProbeLang` 定义 `_init() -> void` 后 analyze+lowering、native compile 与运行时
（`ClassDB.instantiate` + `Engine.register_script_language`）全部通过。注意语义边
界不变：语言注册晚于 `ScriptServer::init_languages()`，引擎不会对本语言调用
`init()`；构造钩子形参默认值仍不支持（frontend 既有约束）。

### 3.3 `gdcc_script_format_loader.gd3` / `gdcc_script_format_saver.gd3`

按 §2.4 实现。`_load` 用入参路径读文件、创建 `GdccScript` 并 `setup(language)`、
填充 `_source` 后返回；缓存模式按参考实现惯例处理。`_save` 用
`FileAccess.open(path, WRITE)` 写回 `resource.get_source_code()`（先
`as GdccScript` 守卫，失败返回 `ERR_INVALID_PARAMETER`）。

### 3.4 `gdcc_lsp_client.gd3` — `GdccLspClient extends Node`

最小 LSP 客户端，`StreamPeerTCP` 实现，与 `GdccRpcClient` 同款帧泵纪律：

- `setup(host: String, port: int)`；状态机
  `DISCONNECTED / CONNECTING / READY / DEGRADED`。连接采用**有上限指数退避重试**
  （LSP 服务在编辑器 ready 后才监听，插件加载时立即连接大概率被拒，见 §2.6）；
  断开/服务重启后同样重连。DEGRADED 可恢复，不进入终态 FAILED。
- 握手：`initialize`（`rootPath` 与 `rootUri` 都填项目根）+ `initialized`。
  能力校验：`capabilities.textDocumentSync` 为 int 时接受 `== 1`；为 Dictionary 时
  接受 `change == 1`；其余（增量/未知）置 DEGRADED 并告警。监听
  `gdscript_client/changeWorkspace` 通知，与本项目根不符时置 DEGRADED（连错实例）。
- 帧解析：累积 `PackedByteArray` 缓冲，扫描 `Content-Length: N\r\n\r\n` 边界后
  `JSON.parse_string`；不完整帧保留到下次。
- 通知路由：`textDocument/publishDiagnostics` → 按 URI 写入诊断缓存。4.5 服务端
  通知**不含 `version` 字段**（`GDScriptWorkspace::publish_diagnostics` 只写
  `uri` + `diagnostics`），但服务端对每次 `didOpen`/`didChange` 都同步解析并恰好
  发布一次诊断，且同一连接上按 FIFO 顺序发送（`gdscript_text_document.cpp` /
  `gdscript_workspace.cpp` / `gdscript_language_protocol.cpp` 的 res_queue）。客户端
  据此维护**每 URI 待确认 generation 队列**：发送 `didOpen`/`didChange` 时把递增
  generation 追加到队尾；收到通知时弹出队首并与之绑定（"发送后首个通知属于当前
  generation"的简单规则在旧通知仍在途时是错的，必须用队列）；队列为空时收到的
  通知属服务端主动产生，不得推进任何等待水位；断线或超时清空该连接全部待确认
  generation。`_validate` 的同步等待即等待本次发送的 generation 出队。
- 请求/响应：`_send_request(method, params) -> int(id)` + `_pending: Dictionary`；
  `request_blocking(method, params, timeout_msec: int) -> Dictionary`：在调用线程内
  以非阻塞 `poll()` + `Time.get_ticks_msec()` 截止循环驱动读写分发，直到目标 id
  返回或超时；遵守 §1.2 的阻塞形态约束（禁 blocking mode、禁 `OS.delay_msec`、
  超时断连）。**仅当服务端为线程模式时合法**（安装时校验 EditorSettings，§4.1）。
  `_in_blocking` 置位期间帧泵直接返回，避免重入。
- 文档同步：`open_document(uri, text)` / `change_document(uri, text)`（全量，
  版本号递增）/ `close_document(uri)`；URI 为 `file://<绝对路径>`。

### 3.5 `gdcc_editor_service.gd3` — `GdccEditorService extends Node`

注册中枢与诊断调度。**进程级常驻单实例**：节点挂在 SceneTree 根下（固定节点名
`GdccEditorService`），插件禁用时**不释放**——语言、加载器、保存器、LSP 客户端
实例同理保留，仅注销对外注册并断开连接。已加载的 `GdccScript` 与仍打开的编辑器
页签会持续持有语言实例（`script.get_language()` 随 `validate()` 继续被调），
常驻设计使这些引用始终有效；重新启用时**复用同一语言实例**重新注册，杜绝第二份
`GD3` 语言或悬空 `_service`（§9 R15）。状态机 `ACTIVE / UNINSTALLED`，
UNINSTALLED 下所有对外入口早退（`_validate` 返回 `{"valid": true}`，补全返回降级
字典）。

- `install(editor_interface: EditorInterface, lsp_host: String, lsp_port: int,
  rpc_host: String, rpc_port: int) -> int(Error)` —— ACTIVE 状态下重复调用幂等早退
  （但仍把 host/port 写回客户端，见下）。首次调用时创建语言/加载器/保存器/LSP 客户
  端并 `setup(...)` 互注，同时创建**服务专用**的 `GdccRpcClient` 子节点（常驻；与
  dock 持有的客户端分属两条 HTTP 队列，诊断分析不会饿死 dock 的进度轮询，§4.4）。
  **每次** `install`（含禁用后重启用）都把四个端点参数写回对应客户端字段，禁止只在
  首建时赋值。注册按 语言→加载器→保存器 顺序逐步记录成功状态，任一步失败即逆序
  回滚已注册项、置 UNINSTALLED、返回错误码；`plugin.gd` 收到失败后立即恢复
  `use_thread` 原值并报告用户，不留半安装状态。注意可检测性差异：
  `Engine.register_script_language` 直接返回 `Error`（16 语言上限/重名属可预期
  失败）；`ResourceLoader.add_resource_format_loader` 与
  `ResourceSaver.add_resource_format_saver` 在 4.5 返回 `void`，无法直接判失败——
  其后置条件用探针**分别**验证：loader 注册后立即 `ResourceLoader.load` 一个已知
  测试 `.gd3` 并断言返回 `GdccScript` 实例；saver 注册后创建测试 `GdccScript` 并
  `ResourceSaver.save` 到临时 `.gd3`，断言返回 `OK` 且读回内容一致。任一验证失败
  按已注册项逆序回滚并返回 `FAILED`。
- 文件集合对账：项目内 `.gd3` 的删除/重命名必须反映到诊断 VFS。由 plugin.gd
  （解释型）连接 `EditorInterface.get_resource_filesystem().filesystem_changed`
  信号转发给服务；服务维护已镜像路径集合，对账时计算新增/修改/删除差异：删除与
  重命名旧路径先 `vfs.deletePath`，新增/修改走 `put_file`，然后才安排分析。
  对账与防抖共用同一调度泵。
- `uninstall()`：停防抖调度并丢弃 `_dirty` 与在途分析回调 → 断 LSP → 移除
  loader/saver → `unregister_script_language` → 删除私有诊断模块 → 状态置
  UNINSTALLED。**不释放任何常驻对象**；UNINSTALLED 下所有对外入口与内部调度器、
  `notify_source_changed` 一律早退（专用 RPC 客户端虽常驻但不再被使用，迟到响应
  由状态门丢弃）。
- gdcc 服务可用性钩子：`ensure_server_hook: Callable`（由 plugin.gd 注入，签名为
  `func(host: String, port: int, on_ready: Callable) -> void`，语义见 §3.7）。
  专用 RPC 客户端连接被拒时（install 后的首个 `ping` 之前、以及会话中重连失败时），
  先经该钩子确保服务可用。**钩子本身无返回值**，成败只能看 `on_ready` 回调：
  调用方把后续 `ping` 放进 `on_ready`，`err == OK` 才重试连接，否则维持现有静默
  降级；禁止根据 `hook.call(...)` 的返回值判断成败（void 调用得到 `null`，按字面
  比较会把每次拉起都误判为失败）。
- 编辑器空闲活性：编辑器默认 `OS.low_processor_usage_mode`，无输入/重绘时主循环
  可能跳过迭代，`process_frame` 不发射、`HTTPRequest` 不推进（既有事实源
  `json_rpc_service_implementation.md` §6.3）。诊断调度恰恰在"用户停键 800ms"后
  触发，必须继承 dock 的同款缓解：低功耗开关由 plugin.gd 统一协调（**单一写入者**，
  dock 改为只向 plugin.gd 上报 busy ±1，不再直写 `OS.*`；协调器 **Phase 1 即落地**，
  dock 与 server_launcher 首批接入，服务的诊断调度在 Phase 3 接入）——
  总计数 0→1 时保存进入前的原值并置 `false`，1→0 或 `uninstall` 时恢复**该原值**
  （不写死 `true`，以免覆盖用户原本的设置）；dock 在 `_exit_tree` 仍有 busy 时须先
  归零。实现期验证替代方案：低功耗模式下 `Timer` 是否仍唤醒，若成立可改用它驱动
  防抖而避免触碰全局开关。
- gdcc 诊断通道：私有模块，模块 ID 含项目根哈希与进程 ID 以防多编辑器实例共享同一
  `gdcc serve` 时互相删除：`gdcc_editor_diagnostics_<projectRootHash>_<pid>`
  （`OS.get_process_id()`，列入探针）。`install` 时懒创建（首个 `server.ping` 成功
  后 `module.create`）；`module.create` 返回 `-32001`（同 ID 残留，例如上次进程
  崩溃未清理）时先 `module.delete` 再重建——ID 内含 pid，同 ID 必属本进程残留，
  不会误删他人模块；删除因模块门闩占用失败则记日志并推迟重试。`uninstall` 时仅
  删除自己成功创建的模块。
- 分析参数：`analyze_include_lowering: bool = true`（§2.7）。
- VFS 映射合同：`res://a/b.gd3` → VFS 路径 `/src/a/b.gd3`，`put_file` 必须携带
  `displayPath = "res://a/b.gd3"`（`analyze.run` 诊断的 `sourcePath` 依赖
  displayPath 回映；缺省会得到 VFS 逻辑路径，缓存将键不上）。
- 诊断缓存：`_gdcc_diagnostics: Dictionary`（`res://` 路径（displayPath）→ 条目数组 +
  内容版本号），`_dirty: Dictionary`（路径 → 最新待分析文本/版本）。
- 供 `GdccScriptLanguage` 查询的接口（签名为合同，§4.2 依赖之）：
  `lsp_diagnostics_for(path) -> Array`；
  `notify_source_changed(path, text) -> int`（内容未变：返回现有版本、**不升版**；
  内容变化：版本 +1 并标脏）；
  `gdcc_diagnostics_for(path, version) -> Array`（仅当缓存版本与 version 一致才返回
  条目，否则返回空数组）；
  `request_completion(path, text, line, col) -> Dictionary`。

### 3.6 `plugin.gd` 接线变更

`_enter_tree` 增加（解释型 GDScript，可直接用编辑器 API）：

1. 常驻节点就位：`get_tree().root.get_node_or_null("GdccEditorService")`，命中时必须
   `is GdccEditorService` 校验身份，类型不符则 `push_error` 并放弃安装（fail-closed，
   绝不创建第二个常驻节点或第二份语言实例）；不存在则 `GdccEditorService.new()` +
   命名 + `add_child` 到根。**此步先于任何设置改动**，放弃路径无需恢复现场。
2. 读取并暂存 `network/language_server/use_thread` 原值；为 false 则置 true
   （持久化副作用与恢复义务见 §2.6/§4.1）。
3. 读取 `network/language_server/remote_host` / `remote_port`（缺省 127.0.0.1/6005）；
   gdcc RPC 端点取固定缺省 `127.0.0.1:6099`（与 `GdccRpcClient`/dock 缺省一致，不与
   dock 输入框联动）。
4. 初始化低功耗 busy 协调器（计数 0，此时**不触碰** `OS.low_processor_usage_mode`；
   仅登记保存/恢复逻辑，首个 busy 0→1 时才保存原值并置 `false`，§3.5）。协调器
   必须先于任何 `ensure_server_hook` 调用存在。
5. 创建 `server_launcher.gd` 实例并挂为子节点（§3.7），其 busy 上报指向协调器；
   把引用传给 dock；dock 增加一个"启动命令"输入框（留空 = 不自动拉起），读写
   EditorSettings `gdcc/server/launch_command`，风格沿用现有 host/port 输入框；
   dock 的 busy 同步改为上报协调器（不再直写 `OS.*`）。
6. 调用 `install(...)` 并检查返回的 Error，失败时恢复 `use_thread` 原值并向用户报告。
   同时把 `server_launcher.ensure_running` 作为 `ensure_server_hook` 注入服务（§3.5）。
7. 连接 `EditorInterface.get_resource_filesystem().filesystem_changed` → 转发服务的
   对账调度（重复启用时先 `is_connected` 检查，仅连接一次；§3.5）。
8. 不直接调 `EditorFileSystem.scan()`：首次启动时本插件在首次扫描的 `init_plugins()`
   阶段进入树，同步 scan 会重入首次扫描；且 loader 注册后外层扫描本就会识别 `.gd3`。
   仅当编辑器已就绪后手动启用插件时，用 `call_deferred` 延迟触发一次 scan。

`_exit_tree`：断开 `filesystem_changed` 转发连接 → dock 残余 busy 计数归零 →
`uninstall()`（常驻节点保留；其中删除私有模块仍需服务在线）→ 恢复 `use_thread`
原值（恢复会触发引擎 LSP 服务重启，必须发生在本客户端已断开、语言已注销之后，
顺序不可颠倒，§2.6、§4.1）→ **最后** `launcher.shutdown_owned()` 关闭本插件拉起的
服务（§3.7，须在 uninstall 之后，否则模块删除失去服务端）。`gdcc_dock.gd` 的改动含
busy 上报改造（**Phase 1**，与协调器同时落地，§3.5）、启动命令输入框（§3.7）与一行
LSP/gdcc 连接状态指示（可选，Phase 5）。

### 3.7 `server_launcher.gd` — 编译服务拉起与关闭（解释型 GDScript）

职责：gdcc 编译服务的**按需拉起**与**所有权内关闭**。用解释型 GDScript（不用 `.gd3`）：
dock 在 gdcc 语言注册之前就依赖它（§1.1）。形态为 `Node`，由 plugin.gd 创建并挂为
子节点，dock 与 `GdccEditorService` 共用同一实例（拉起是单飞操作，天然需要单点协调）。

- 配置：EditorSettings `gdcc/server/launch_command`（String，默认 `""` = 不自动拉起，
  保持纯被动连接行为）。放 EditorSettings 而非 ProjectSettings：服务二进制路径是
  机器相关的开发机配置，不应随 `project.godot` 提交（与 `text_editor/external/exec_path`
  等工具路径先例一致）。plugin.gd 在 `_enter_tree` 用 `add_property_info` 注册该条目；
  dock 的"启动命令"输入框直接读写它，每次拉起前重新读取（不缓存）。
- 命令模板：支持 `{host}` / `{port}` 占位符，拉起前替换为目标端点；典型值
  `java -jar D:/tools/gdcc/gdcc-0.0.2.jar serve --host {host} --port {port}`。
  解析规则为空白分词 + 双引号成组（无 shell 展开、无管道；首 token 为可执行文件，
  其余为参数）。无占位符时按原样执行（服务须自行监听目标端点）。命令文本是用户
  显式配置的本地命令，以编辑器权限执行，文档明示其信任边界。
- `ensure_running(host: String, port: int, on_ready: Callable) -> void` —— 全程异步
  （`_process` 轮询驱动，不在主线程阻塞），完成时回调
  `on_ready(err: int, spawned_by_us: bool)`。**低功耗活性**：`_process` 在编辑器
  低功耗模式下会停帧（§3.5）；launcher **不自备**全局开关快照（双写入者会与 dock
  的既有快照互相覆盖：launcher 把对方写的 `false` 当原值保存、恢复时互相踩踏），
  而是在 ensure 开始到回调完成期间向 plugin.gd 的 busy 协调器上报 ±1——协调器
  Phase 1 即落地，dock 与 launcher 首批接入（§3.5/§3.6）。
  1. 探测：`StreamPeerTCP` 连接目标端点并短轮询（≤200ms）；已可连接 →
     回调 `OK, false`（**外部服务**，本会话绝不关闭它）。
  2. 拒绝连接且启动命令为空 → 回调 `ERR_CANT_CONNECT`（现状行为不变）。
  3. 本会话已为目标端点拉起过且 `OS.is_process_running(pid)` → 直接进入等待就绪。
     否则按模板 `OS.create_process(exe, args)`：返回值 ≤ 0 → 回调 `ERR_CANT_FORK`；
     成功则记录 `_spawned_pid` 与端点，回调排队期间的其他调用者共享同一单飞操作。
  4. 等待就绪：周期性重新探测端口，最长 15s；超时且进程仍存活 → `OS.kill` 回收
     本次拉起并回调 `ERR_TIMEOUT`；进程已早退 → 回调 `ERR_CANT_CONNECT` 并在日志
     给出检查启动命令的提示。
- `shutdown_owned() -> void`（plugin `_exit_tree` 最后一步，§4.1）：仅当
  `_spawned_pid > 0` 且 `OS.is_process_running` 时执行。这是 §1.2 主线程阻塞禁令的
  **唯一豁免**：`_exit_tree` 期间帧泵可能不再运行，必须用非阻塞 `poll()` +
  `Time.get_ticks_msec()` 截止的有界循环（≤2s），禁用 `_process` 等待与
  `OS.delay_msec`。流程：一次性发送 `server.shutdown` RPC——**收到响应即完成**
  （退出已被触发，shutdown hook 会在后台完成 `API.close()` 的收尾，编辑器退出不
  等待它，绝不硬切在途编译）；RPC 不可达/超时才考虑 `OS.kill`，且 kill 前必须再
  探测记录的端点仍在接受 TCP 连接（证明我们的服务进程仍存活并持有端口，防 PID
  复用误杀，§9 R19）；端口已关闭则跳过 kill 只记日志。完成后清空状态。编辑器
  异常退出（崩溃）走不到此路径，孤儿进程由下次会话的端口探测自然"收养"为外部
  服务（只连不关），安全。
- 所有权不变量：**只关闭本会话自己 `create_process` 拉起的 PID**；探测先于拉起，
  永不认领已在侦听的服务。PID 复用的残余风险记入 §9 R19。

### 3.8 文件与构建接线

- `EditorAddonProjectInstaller`：收集 `addons/gdcc/*.gd3` 全部源文件写入模块 VFS
  （替换当前单文件常量，探针文件不进插件目录、不纳入收集）；**所有**安装出口
  （`buildNative`/`buildAllPlatform`/测试安装）统一后处理
  `gdcc_for_editor.gdextension`，把 `reloadable = true` 改写为 `false`（§6）。
- `buildAddonNative` / `buildAddonAllPlatform` Gradle 任务不变。

---

## 4. 关键流程

### 4.1 注册/注销时序

注册（插件 `_enter_tree`，同步顺序；LSP 与 gdcc 模块为异步就绪，不阻塞注册）：

1. （plugin.gd）常驻节点就位：`get_tree().root.get_node_or_null("GdccEditorService")`，
   命中先 `is GdccEditorService` 验明身份，类型不符 `push_error` 并放弃安装
   （fail-closed、零副作用，**此步先于任何设置改动**）；不存在则创建并挂到根。
2. （plugin.gd）暂存并设置 `network/language_server/use_thread = true`（LSP 线程模式
   硬前提；正常启动路径下服务尚未启动故生效；运行期启用时服务端检测到设置变化自动
   重启监听，§2.6）。
3. （plugin.gd）初始化低功耗 busy 协调器（计数 0，**不触碰** `OS.low_processor_usage_mode`，
   首个 busy 0→1 时才保存原值并置 `false`；dock 与 launcher 的 busy 上报指向它）。
   此步必须先于第 6 步的任何 `ensure_server_hook` 调用（§3.5/§3.7）。
4. `install(...)`（RPC 端点缺省 127.0.0.1:6099）：首建时创建服务专用
   `GdccRpcClient` 子节点（与 dock 客户端队列隔离）；语言实例（首建或复用）
   `Engine.register_script_language(lang)`，返回值非 `OK` 即按 §3.5 回滚并向上报错
   （16 语言上限/重名属可预期失败，不得断言了事）；注册 loader/saver 并以探针资源
   分别验证后置条件；启动 LSP 带退避重连。`install` 返回非 OK 时恢复 `use_thread`
   原值并报告，后续步骤不再执行。
5. （plugin.gd）`install` 成功后连接
   `EditorInterface.get_resource_filesystem().filesystem_changed` → 转发服务对账
   （先 `is_connected` 防重，仅连接一次；放弃/失败路径不连接，注销时仅在已连接时
   断开）。
6. 经 `ensure_server_hook`（§3.7）确保 RPC 服务可用（未配置启动命令且服务缺席时
   静默降级，后续心跳失败仍会再经钩子重试）；首个 `ping` 成功后创建私有诊断模块，
   随后初始同步：`DirAccess` 递归遍历项目 `.gd3` 上传 VFS 并做一次 `analyze.run`
   预热缓存。
7. （plugin.gd）仅运行期启用场景：`call_deferred` 触发 `EditorFileSystem.scan()`。

注销（`_exit_tree`）：断开 `filesystem_changed` 连接、dock 残余 busy 归零 →
`uninstall()`（停防抖调度与在途分析回调 → 断 LSP → 移除 loader/saver → 注销语言
（**不释放**实例）→ 删除私有模块（需服务在线）→ UNINSTALLED）→ 恢复
`use_thread` 原值（恢复会触发引擎 LSP 服务重启，必须发生在本客户端断开、语言
注销之后）→ **最后** `launcher.shutdown_owned()`：对拉起的服务发 `server.shutdown`
RPC，**收到响应即完成**（优雅收尾由 JVM hook 在后台完成，编辑器不等待）；仅当
RPC 不可达且记录端点仍在侦听时才 `OS.kill` 兜底（§3.7）。

不在 GDExtension 入口初始化阶段注册的原因：gdcc 生成的入口不向 `.gd3` 暴露初始化
钩子；插件 `_enter_tree` 早于编辑器就绪与用户操作。

### 4.2 诊断合并流程（`_validate(script, path, ...)`）

入参 `script` 即编辑缓冲区当前文本（无 0xFFFF 哨兵）。全程单线程（主线程），
`_in_validate` 守卫防重入；服务 UNINSTALLED 时直接返回 `{"valid": true}`。

1. LSP READY：URI 为 `res://` → `file://` 绝对路径；`didOpen`（首次）或
   `didChange`（全量文本，generation 递增，水位规则见 §3.4）；随后**有界同步等待
   ≤150ms** 收取该 URI 当前 generation 的 `publishDiagnostics`（仅线程模式下有效，
   §2.6）；超时则用该 URI 的最近缓存诊断。
2. LSP 诊断映射（0-based → 1-based，行与列均 +1；验收时与同内容 `.gd` 文件逐条对比，
   §8.3）：
   - `severity == 1` → `errors[]`：`{line, column, message, path: <当前 res:// 路径>}`
     （`path` 必填，否则编辑器把错误归入"依赖脚本错误"面板）。
   - `severity == 2` → `warnings[]`：`{start_line, end_line, code: 0,
     string_code: "GDSCRIPT_LSP", message}`（五键齐全，缺键会被引擎丢弃；LSP 的
     `code` 可能是字符串，不作 int 转换）。
   - 其余 severity 丢弃。
3. gdcc 通道：**先** `version := notify_source_changed(path, script)`（每次校验都执行，
   包括 LSP 有错误时——LSP 错误只抑制 gdcc 诊断的**显示**，不抑制标脏与缓存失效），
   再读 `gdcc_diagnostics_for(path, version)`：缓存内容版本与本次不一致时视为无缓存
   （本轮不合并，后台分析会刷新），一致则按 `severity` 拆入 `errors`/`warnings`
   （`string_code: <category>`，`message` 前缀 `[gdcc <category>]`；`sourcePath`/
   `range` 为空的条目按文件级诊断处理：行 1 列 1）。若 LSP 有错误，即使缓存新鲜也
   不叠加（避免级联噪音）。
4. 返回 `{"valid": errors.is_empty(), "errors": ..., "warnings": ...}`。
5. LSP 不可用（DEGRADED）：跳过第 1–2 步，仅 gdcc 通道（gdcc 前端本身覆盖 GDScript
   语法解析，降级仍有意义）。

### 4.3 补全流程（`_complete_code(code, path, owner)`）

前置：服务 ACTIVE、LSP READY 且服务端线程模式；否则返回降级字典。

1. `idx := code.find(String.chr(0xFFFF))`；`idx < 0` → 返回
   `{"result": ERR_UNAVAILABLE, "force": false, "call_hint": "", "options": []}`。
2. 由 `idx` 计算 0-based `(line, character)`，剔除哨兵得纯净文本，先同步文档再
   `request_blocking("textDocument/completion", ...)`，预算 ≤400ms。
3. 结果映射（LSP `CompletionItem` → 引擎 option）：
   - `display := item.label`；
     `insert_text := item.textEdit.newText ?? item.insertText ?? label`。
   - `kind` 映射：Class→0，Method/Function/Constructor→1，Event→2(SIGNAL)，
     Variable/Field→3，Property→4(MEMBER)，Enum→5，Constant/EnumMember/Value→6，
     File/Folder→8(FILE_PATH)，其余→9(PLAIN_TEXT)。（4.5 无 KEYWORD kind。）
   - `font_color := Color(1, 1, 1)`，`icon := null`，`default_value := null`，
     `location := 1024`（`LOCATION_OTHER`）。
4. 返回 `{"result": OK, "force": false, "call_hint": "", "options": options}`；
   任何失败返回第 1 步的降级字典。

### 4.4 gdcc 诊断调度（全异步）

- **不引入任何主线程阻塞 RPC 通道**（两个评审轮次均判定阻塞 `HTTPClient` 无法在
  UI 线程保证硬截止）。一律走 `GdccRpcClient` 的异步挂起对象队列，且使用服务
  **专用**的客户端实例（§3.5）：与 dock 的客户端分属两条 HTTP 连接与超时配置，
  dock 触发 Compile 期间其 `compile.getTask` 进度轮询不被整模块 `analyze.run`
  阻塞，诊断分析也不被 dock 操作饿死。
- 标脏与防抖：`_validate` 第 3 步标脏；服务在 `process_frame` 累积计时，自上次变更
  满 800ms 且无在途分析时发射：按序执行 `put_file`（VFS 映射合同见 §3.5）+
  `analyze.run(analyze_include_lowering)`。
- **单飞以服务端完成为准**：在途期间不发射新请求（新变更只更新 `_dirty`）；
  响应到达后按内容版本号比对——落后则丢弃并立即安排新一轮，匹配则回填
  `_gdcc_diagnostics`。
- **无主动 revalidation 通道**（§2.5）：新诊断随编辑器下一次校验节拍（再编辑的
  idle 超时、页签切换、外部重载）自然浮现。已知限制记录在 §9 R17。
- 与编译任务的关系：`analyze.run` 在服务端与 compile 串行；dock 触发 Compile 期间
  诊断只是变旧，不会卡死编辑器（异步）。
- 服务器离线：静默降级（沿用现有"只记录日志"约定），`_validate` 仅剩 LSP 通道。

---

## 5. gdcc 自举约束与 Phase 0 探针

新 `.gd3` 文件遵守 `gdcc_rpc_client.gd3` 已钉死的全部约束：禁止 `await`/协程、
禁止 lambda 与 `Callable.bind`、引擎对象局部变量显式标注类型、`JSON.parse_string`
结果显式守卫、公开 API 不返回协程（异步 RPC 沿用 `PendingRequest` 挂起对象；
LSP 的同步等待是紧凑 `poll` 循环，不是协程）。

Phase 0 探针分三级（任一级失败即阻塞后续阶段，先补 gdcc 能力，独立任务不在此展开）：

**P0-A 编译探针**（`src/test/resources/editor_addon_probe/*.gd3`，不进插件目录；
`EditorAddonIntegrationProbeTest` 做 `analyze(includeLowering=true)`，要求
`COMPLETED` 且无 ERROR）：

| 探针 | 内容 |
|---|---|
| 虚函数覆盖 | `ProbeLang extends ScriptLanguageExtension`（含 `_validate(...) -> Dictionary`、`_get_recognized_extensions() -> PackedStringArray`、`_create_script() -> Object`）；`ProbeScript extends ScriptExtension`（`_get_language() -> ScriptLanguage` 等）；`ProbeLoader extends ResourceFormatLoader`（`_load(...) -> Variant`）；`ProbeSaver extends ResourceFormatSaver` |
| `_init` 语义 | 在 `ProbeLang` 上定义 `func _init() -> void`，确定 gdcc 将其视为构造钩子还是 engine virtual override（§3.2） |
| 跨类引用 | 字段 `var lang: ProbeLang`、`as ProbeScript` 转换、`ProbeScript.new()` 实例化 |
| 注册 | `Engine.register_script_language` / `unregister_script_language`、`Engine.get_script_language_count/get_script_language` |
| 资源 | `ResourceLoader.add_resource_format_loader/remove_resource_format_loader`、`ResourceSaver.add_resource_format_saver/remove_resource_format_saver`、`ResourceLoader.get_cached_ref`、`Resource.take_over_path`、`ResourceLoader.load_threaded_request/load_threaded_get_status/load_threaded_get` |
| 网络 | `StreamPeerTCP.connect_to_host/poll/get_status/get_available_bytes/get_partial_data/put_data/disconnect_from_host`（含 `get_partial_data` 的 `[err, PackedByteArray]` 数组解包） |
| 文件 | `FileAccess.open/get_file_as_string/store_string/file_exists`、`DirAccess` 递归遍历 |
| 文本 | `String.find(String.chr(0xFFFF))`、`substr`、按 `\n` 分行、`begins_with`、`unicode_at`、`PackedInt32Array`、`Color(1, 1, 1)` |
| 时间/系统 | `Time.get_ticks_msec`、`OS.get_process_id` |
| 编辑器类型 | 形参/局部变量标注 `EditorInterface`（编辑器专用类经元数据可达性验证） |
| 生命周期 | `is_instance_valid`（防御性检查用） |

**P0-B native 编译探针**（Zig 门控）：对探针模块执行完整 `compile`，断言产物生成。
analyze+lowering 不覆盖 C 代码生成/链接，此级是后端能力门禁。

**P0-C 运行时冒烟**（`GODOT_BIN` 门控，非编辑器 `-s` SceneTree 驱动，复用现有
bootstrap 模式）：加载探针扩展，断言 `ClassDB.class_exists` 命中探针类、
`Engine.register_script_language(ProbeLang.new())` 返回 `OK`、虚函数可被
`Engine.get_script_language(i)` 取回后经绑定方法（如 `_get_name()`）调用返回预期值。
（`ScriptLanguageExtension` 是 core api_type，运行时注册可行；编辑器专属行为留待
Phase 1 的编辑器内 harness。）

---

## 6. 热重载与 `reloadable = false` 决策

`GdextensionMetadataFile` 目前恒写 `reloadable = true`。注册到 `ScriptServer` 的语言
实例是裸指针：热重载销毁并重建扩展实例，`ScriptServer` 不会重新指向新实例，重载后
编辑器访问语言即悬空指针崩溃；Godot 对此无内建善后。常驻单实例设计（§3.5）覆盖了
插件禁用/启用路径，但无法覆盖热重载替换类定义的路径。

决策：`EditorAddonProjectInstaller` 的全部安装出口把 `gdcc_for_editor.gdextension`
中的 `reloadable` 改写为 `false`（安装器侧后处理，不改后端默认行为）。代价：重编
插件库后需重启编辑器才能生效，对本编辑器工具属可接受。验收：安装产物含
`reloadable = false` 文本断言（并入分析测试类）。

---

## 7. 分阶段实施计划

每阶段结束跑 `./gradlew classes --no-daemon --console=plain` + 相关定向测试
（`pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests ...`）。

### Phase 0：编译与运行时探针 ✅（2026-09-23 验收通过）

实施：§5 探针文件（`src/test/resources/editor_addon_probe/`：`probe_lang.gd3`、
`probe_script.gd3`、`probe_loader.gd3`、`probe_saver.gd3`、`probe_engine_calls.gd3`）
+ `EditorAddonIntegrationProbeTest`（P0-A）+ Zig 门控 compile 断言（P0-B）+
`GODOT_BIN` 门控运行时冒烟（P0-C，最小工程 + `-s` SceneTree 驱动，断言
`ClassDB.class_exists`、注册返回 `OK`、`get_script_language` 取回后经
`_get_name`/`_get_extension`/`_get_recognized_extensions` 绑定调用返回预期值、
`_create_script` 全链路往返）。
验收：P0-A/B/C 全绿（3/3，无跳过）；探针覆盖 §5 表中每一行；`_init` 语义结论与
typed array 返回探针结论已写入本文档（§3.2/§2.3）；探针暴露的两个 gdcc 后端缺陷
已修复并附回归测试（见文档状态节）。

### Phase 1：资源骨架（无诊断）+ 服务拉起

实施：`GdccScript`、加载器/保存器、`GdccScriptLanguage`（`_validate` 暂恒返回
`{"valid": true}`）、`GdccEditorService` 常驻单实例与注册/注销、`plugin.gd` 接线
（含 use_thread 暂存/恢复）、安装器多源文件收集与 `reloadable=false` 全出口后处理。
外加服务拉起功能（§3.7）：服务端新增 `server.shutdown` RPC（方法 handler 只置
`AtomicBoolean` 并返回 `{}`，幂等；`JsonRpcHttpHandler` 在该 exchange 写完并
close 之后，由**不属于请求 executor 的平台线程**调 `System.exit(0)`——禁止在方法
handler 内启动退出线程、禁止用 sleep 赌写回完成，否则与 hook 里的
`executor.close()`/`stop(0)` 死锁，§2.8；同步更新
`json_rpc_service_implementation.md` 方法表与编解码测试）、`server_launcher.gd`、
dock"启动命令"输入框与 EditorSettings 读写、`ensure_server_hook` 注入、低功耗
busy 协调器（plugin.gd 单一写入者，本阶段落地，dock busy 改造与 launcher busy
首批接入，§3.5/§3.6）。
验收：
- `EditorAddonScriptLanguageAnalysisTest`（新增）：全部 `.gd3` analyze+lowering 干净；
  沿用客户端合同检查（无 `await` 等）；断言所有安装产物 `reloadable = false`；
  `plugin.gd`/`gdcc_dock.gd`/`server_launcher.gd` 仅语法解析。
- 服务端测试（真实 HTTP，非仅 dispatcher 单测）：`POST /rpc` 调 `server.shutdown`
  读到 200 与 body **之后**进程才退出（退出码 0，shutdown hook 完成收尾）；
  连接仍可用时重复调用幂等返回相同 `{}`（§2.8 时序合同）。
- `EditorAddonScriptLanguageEngineTest`（新增，`GODOT_BIN`+Zig 门控；harness 见
  §8.2）：语言已注册（遍历 `Engine.get_script_language*` 找到 `GD3`）；
  `ResourceLoader.load("res://.../sample.gd3")` 返回对象的
  `get_language().get_name() == "GD3"` 且源码往返一致；`ResourceSaver.save` 后文件
  内容更新；`ResourceLoader.load_threaded_request` + `load_threaded_get_status` 轮询
  + `load_threaded_get` 的 worker 线程加载路径同样成功（覆盖 §2.4 约束）；
  直接调用语言的 `_validate` 返回 `valid == true`。
- 拉起功能引擎测试（同 harness）：Java 侧先找一个空闲端口但不侦听，驱动插件把
  EditorSettings `gdcc/server/launch_command` 设为
  `java -jar <已构建 jar> serve --host 127.0.0.1 --port {port}`（占位符由 launcher
  替换为该端口）——冷启动无服务时 dock 连接经自动拉起最终成功（`ping` 通过）；
  随后禁用插件，断言被拉起的进程退出（端口关闭/`is_process_running == false`）。
  负例：启动命令指向不存在的可执行文件 → 状态行报错且不崩溃；启动命令留空且无
  服务 → 维持现状 fail-fast 报错（回归既有行为）。
- 禁用/启用循环（引擎测试或手动）：禁用插件（不关 `.gd3` 页签）→ 在页签中继续
  编辑触发校验，无崩溃无报错（UNINSTALLED 早退）→ 重新启用 → 断言旧页签的
  `script.get_language()` 与当前注册语言为**同一实例**，校验恢复工作。
- 手动：打开 `src/editor_addon`，FileSystem 面板出现 `.gd3`，双击在内置编辑器打开，
  编辑保存后磁盘内容一致；配置启动命令后冷启动编辑器，无需手工 `gdcc serve` 即可
  Compile，退出编辑器后该服务进程消失（外部手动启动的服务不受影响）；冷启动后
  **不再移动鼠标**（编辑器低功耗窗口），服务仍在 15s 内就绪（§3.7 低功耗活性）。

### Phase 2：LSP 客户端与 GDScript 诊断

实施：`GdccLspClient`（§3.4）、`_validate` 的 LSP 分支（§4.2 第 1–2 步）、降级路径。
验收：
- 引擎测试：LSP 连接在重试后 READY（全新编辑器启动路径，验证"插件先加载、LSP 后
  监听"；另覆盖运行期改变 `use_thread` 后服务端自动重启、客户端重连的路径）；
  对含语法错误的样例调用语言的 `_validate`，断言 `valid == false`、`errors` 非空、
  行号与预期一致且每条含 `path`；干净样例 `valid == true`；警告样例的 `warnings[]`
  五键齐全；发送 g1 后不读响应立即发送 g2，再依次接收两个 `publishDiagnostics`，
  按 FIFO 队列分别归属 g1/g2（§3.4 队列规则，旧通知不得错标为新 generation）。
- 手动：同一段错误代码分别在 `.gd` 与 `.gd3` 中打开，错误行号/条数一致；编辑后错误
  随动更新；无错误文件无误报；含中文注释的样例补全/诊断列位置正确（§9 R11）。

### Phase 3：gdcc 诊断合并

实施：私有模块生命周期、标脏/防抖/单飞/版本校验、缓存合并（§4.2 第 3 步、§4.4）、
文件集合对账（§3.5）、诊断调度接入低功耗 busy 协调（协调器已在 Phase 1 落地，
§3.5/§3.6）。
验收：
- 引擎测试：`.gd3` 中使用路径式 `extends "res://base.gd"`（GDScript 合法、gdcc
  前端拒绝）——LSP 无错误时，异步分析完成后经下一次校验节拍出现带
  `[gdcc ...]` 前缀的错误；再制造 GDScript 语法错误，确认 gdcc 诊断不叠加；修复后
  经下一轮分析+校验诊断消失。补一个 `sema.compile_check` 类样例（
  `includeLowering=true` 路径），具体构造在实现期从前端测试中选取。
  另加负例：不同目录同名 `.gd3` 的诊断分别落回各自页签（验证 displayPath
  键控）；预置同 ID 残留模块（模拟上次崩溃），启用后仍能完成分析（验证
  `-32001` 重建路径）；删除/跨目录重命名 `.gd3` 后，旧路径诊断不再出现（验证
  文件集合对账与 `vfs.deletePath`）。
- 手动：连续输入后停顿，gdcc 诊断最迟在下一次校验节拍出现；停键且不移动鼠标
  （编辑器低功耗窗口）诊断仍能出现（验证 §3.5 空闲活性缓解）；dock 执行 Compile
  期间编辑器不卡死、诊断只是变旧（异步串行），且 dock 进度轮询照常推进（专用
  客户端队列隔离）；分析会话中手工杀掉 gdcc 服务进程，下一次心跳失败经
  `ensure_server_hook` 自动重新拉起并恢复分析（已配置启动命令时）。

### Phase 4：代码补全

实施：`_complete_code`（§4.3）。
验收：
- 引擎测试：对固定样例（类体内空行，含 0xFFFF 哨兵）调用 `_complete_code`，断言
  `result == OK` 且 `options` 非空（至少含 `func`），每项八键齐全；无哨兵输入返回
  降级字典。
- 手动：真实编辑器中 `.gd3` 内触发补全，成员/关键字弹窗与 `.gd` 行为相当；输入无
  可感知卡顿（上限 400ms）。

### Phase 5：打磨（可并行子项，各自独立验收）

- `_lookup_code`：LSP `hover`/`definition` → 悬浮文档与跳转；验收：引擎测试断言对
  已知符号返回 `result == OK`、`type` 合理。
- `_auto_indent_code`：冒号后增缩进等最小规则；验收：编辑器内手动对照。
- `_get_global_class_name`：行扫描解析 `class_name`/`extends` 返回字典；验收：手动
  检查创建节点对话框条目，并回归确认与已编译原生类同名时不产生重复/不可实例化条目
  （addon 自举场景：`.gd3` 源与编译类同名）。
- 模板：`_make_template` / `_get_built_in_templates` 的 API 完整性实现；注意 4.5 中
  "创建脚本"对话框的语言下拉不会列出 GD3（§2.1 已知限制），`.gd3` 新建入口改为
  dock 按钮或在文件面板外创建。
- dock 状态行、`--lsp-port`/host 手动覆盖设置项。

---

## 8. 测试策略

### 8.1 静态合同测试（无环境依赖）

`EditorAddonScriptLanguageAnalysisTest`：与 `EditorAddonClientAnalysisTest` 同构——
逐文件 `analyze(includeLowering=true)`、无 ERROR、无 `await` 等禁用构造；
`plugin.cfg` 五字段不变；`plugin.gd`/`gdcc_dock.gd` 仅语法解析。

### 8.2 引擎集成测试（`GODOT_BIN` + Zig 门控，Assumptions 跳过）

harness：复制 `src/editor_addon` 到 `tmp/test/editor_addon_script_language/<case>/`
并安装编译产物（复用 `EditorAddonBootstrapEngineTest` 模式），**额外注入一个仅测试
用的解释型驱动插件** `addons/gdcc_test_driver/`（`plugin.cfg` + `driver_plugin.gd`，
GDScript，不属于发布物）：编辑器就绪后驱动等待 `GdccEditorService` 就绪与 LSP 监听，
逐项断言并逐行输出 `GD3_TEST_RESULT: ...`，最后 `get_tree().quit(exit_code)`。
启动命令：`godot --headless --editor --path <copy> --quit-after <N>`，其中 N 为纯
兜底的大帧数（如 100000；正常退出由驱动 `quit()` 触发），进程级超时
`destroyForcibly()` 沿用现有模式。不使用 `-s EditorScript`：`-s` 要求
`MainLoop`/`SceneTree` 入口，与 EditorScript 不兼容；驱动插件路径同时保证
EditorPlugin 加载顺序与真实使用一致。

Java 侧逐行匹配 `GD3_TEST_RESULT: `；RPC 服务端 `127.0.0.1:0` 起停沿用现有模式，
临时端口由驱动插件在服务节点入树后、首个 `ping` 前注入（再调一次
`install(..., rpc_host, rpc_port)`，或直接写服务下固定名 `GdccEditorRpcClient`
子节点的 `host`/`port` 字段；§3.5 已要求每次 `install` 写回端点，两条路径等价）。
拉起功能用例（Phase 1）反向操作：Java 侧只选定空闲端口而不启动服务，由驱动插件把
EditorSettings `gdcc/server/launch_command` 与端口写好后触发连接，验证自动拉起与
退出关闭路径（§7 Phase 1 验收）。
若 headless editor 路径在实现期证实不稳定，降级为：(a) 现有非编辑器 bootstrap 断言
（编译产物可加载、类可实例化、P0-C 冒烟）保持全量；(b) §8.3 手动清单逐项执行并记录。

### 8.3 手动验收（每个 Phase 的"手动"项汇总执行）

打开 `src/editor_addon`：`.gd3` 出现在 FileSystem 面板并可双击打开；编辑保存往返；
错误标注与 `.gd` 逐条一致（含行列号）；补全可用；禁用/启用插件循环、重建产物后
重启编辑器均无崩溃、无悬空指针报错。

---

## 9. 风险登记册

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | `.gd3` 不在 GDScript LSP 的 workspace 索引内，跨文件符号/类缓存解析受限 | 中 | didOpen/didChange 不做扩展名过滤，单文件解析与诊断可用；跨文件能力记录为已知限制，不采用影子 URI（无 URI 拒绝问题） |
| R2 | 多编辑器实例/端口占用导致连错实例 | 中 | 握手后监听 `gdscript_client/changeWorkspace` 并与 `ProjectSettings.globalize_path("res://")` 比对，不符即 DEGRADED；host/port 可配；运行期改 `use_thread` 服务端自动重启、客户端重连接管（§2.6） |
| R3 | `_complete_code`/`_validate` 同步等待卡顿编辑器 | 中 | 硬超时（LSP 150ms / 补全 400ms）+ 超时降级；阻塞形态约束写入 §1.2；线程模式硬前提 |
| R4 | 热重载导致 ScriptServer 悬空指针 | 高 | `reloadable = false`（§6），安装器全出口断言钉死 |
| R5 | `ScriptCreateDialog` 语言列表缓存（godot#106480）：4.5 中本语言**永不**出现在创建对话框 | 低 | 已知限制；`.gd3` 新建入口走 dock 按钮/外部创建（Phase 5） |
| R6 | gdcc 前端/后端不支持所需 API 调用路由或类型 | 高 | Phase 0 三级探针门禁（含 native compile 与运行时冒烟），阻塞后续阶段 |
| R7 | `.gd3` 被误挂载到节点时无占位实例（`void*` 虚函数未实现） | 低 | `_can_instantiate() == false` + `_supports_builtin_mode() == false` 双重拒绝；文档声明非目标 |
| R8 | LSP 同步模式假设不成立 | 低 | 握手校验 `textDocumentSync`（int `1` 或 `{change: 1}` 两种形状），否则 DEGRADED |
| R9 | 诊断行号 0/1-based 转换错误 | 中 | 与同内容 `.gd` 文件逐条对比验收（§8.3） |
| R10 | Godot 版本漂移（master 已废弃 `_create_script`/`_get_recognized_extensions`） | 低 | 锁定 4.5 元数据；升级 Godot 时重跑 §2 核查 |
| R11 | 非 ASCII 文本下 LSP `character` 与 Godot 列口径不一致（UTF-16 vs 码点） | 中 | Phase 2 用含中文注释的样例验收列位置；不一致时按 LSP 规范转 UTF-16 列 |
| R12 | 编辑器-only 类进入导出构建 | 低 | 本扩展仅 addon 内使用，文档声明不随项目导出 |
| R13 | `analyze.run` 是整模块分析，大项目延迟高 | 中 | 异步 + 防抖 + 单飞；服务专用客户端与 dock 队列隔离（§4.4）；后续在 RPC 侧评估增量分析（不在本计划） |
| R14 | 外部 LSP 客户端并存时 `publishDiagnostics` 只发最近客户端 | 中 | 等待超时即回落缓存并继续；dock 状态行提示；记录为已知限制 |
| R15 | 禁用插件后已打开页签持有语言/服务引用 | 高 | 常驻单实例 + 注销不释放 + 复用再注册（§3.5）；Phase 1 验收含禁用/启用循环与实例同一性断言 |
| R16 | `_load` 在 worker 线程执行时的数据竞争 | 高 | 纯加载约束（§2.4）；Phase 1 引擎测试含 `load_threaded_request` 路径 |
| R17 | 无公开 API 主动触发编辑器 revalidate，异步 gdcc 诊断存在节拍级延迟 | 中 | 已知限制：随下一次校验节拍浮现（§2.5/§4.4）；若后续版本暴露 `validate_script` 类 API 再接入 |
| R18 | `use_thread` 设置被引擎在退出时持久化，影响用户其他项目 | 中 | 卸载时恢复原值（§4.1）；dock 状态行与文档明示该改动 |
| R19 | 拉起进程的 PID 在会话内被系统复用，`shutdown_owned` 误杀无关进程 | 低 | 仅跟踪本会话 `create_process` 返回的 PID；`OS.kill` 仅在 `server.shutdown` 不可达**且**记录端点仍接受 TCP 连接时使用（双条件，§3.7）；会话级窗口内复用概率极低 |
| R20 | 编辑器崩溃导致拉起的服务成为孤儿进程 | 低 | 崩溃路径本就无法执行插件代码；下次会话端口探测将其收养为外部服务（只连不关），不会泄漏累积（§3.7） |
| R21 | `server.shutdown` 不可达（服务挂起/半死连接）时服务残留 | 低 | 2s 有界等待后按 R19 双条件决定是否 `OS.kill`；服务已死但 PID 被复用时宁可残留也不误杀；Windows 下硬切不执行 shutdown hook 的事实写入 §2.8 |
| R22 | 启动命令以编辑器权限执行任意本地命令 | 低 | 命令仅来自用户显式配置（EditorSettings），插件不自动生成命令文本；文档明示信任边界（§3.7） |

---

## 10. 验收总清单

- [ ] Phase 0–4 全部验收细则通过（含 Phase 1 服务拉起/关闭引擎用例与服务端
      `server.shutdown` 单测），Phase 5 子项逐项记录结果或明确搁置原因。
- [ ] `./gradlew clean build --no-daemon --info --console=plain` 通过。
- [ ] 手动清单（§8.3）全部确认，含禁用/启用插件循环。
- [ ] 本文档状态从"实施计划"转为"事实源维护中"，并回填实现偏差（含 `_init` 语义
      结论与 typed array 探针结论）。
