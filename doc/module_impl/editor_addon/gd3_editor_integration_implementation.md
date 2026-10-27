# .gd3 编辑器集成实现（单一事实来源）

> 本文档是 `src/editor_addon` Godot 编辑器集成功能的唯一事实源：通过 GDExtension 注册一个
> 虚拟 `ScriptLanguageExtension` + `ScriptExtension`，让 Godot 编辑器把 `.gd3` 识别为
> 脚本资源（可打开、编辑、保存），并把校验/补全/查找请求代理到引擎自带的 GDScript 实现
> （编辑器内建 LSP 服务），在 GDScript 判定无误后叠加 gdcc 前端的诊断；另含编译工作流
> （Build 部署与启用）、编译服务器拉起与安装向导。全部语言集成代码用 `.gd3` 编写，经
> gdcc 编译进与 `gdcc_rpc_client.gd3` 相同的 `gdcc_for_editor` 原生库。`.gd3` 从不作为
> 可运行脚本挂载；运行时行为完全由 gdcc 编译出的原生类承担。
>
> 本文档只保留当前已落地、可验证的实现事实与长期约定，不保留实施过程、阶段进度与评审
> 记录（历史内容可从 git 历史查阅）。修改相关代码时必须同步修订本文。

## 文档状态

- 状态：事实源维护中
- 更新日期：2026-10-18
- 范围：
  - `src/editor_addon/addons/gdcc/**`（编译型 `.gd3` 源 + 解释型 `.gd` 接线 + 插件配置/
    翻译/图标）
  - `src/main/java/gd/script/gdcc/rpc/**` 与 `api/**`（`server.shutdown`、`module.copy`、
    `vfs.putFile` 的 `absolutePath` 等服务端配套）
  - `src/main/java/gd/script/gdcc/backend/**`（`_gdcc_get_metadata` 合成与类元数据通道）
  - `src/test/java/gd/script/gdcc/**`（编辑器集成测试与锚定测试）
  - `src/test/resources/editor_addon_probe/**`（自举探针 fixture）
- 直接事实源：
  - `doc/module_impl/api/json_rpc_service_implementation.md`（RPC 方法面、`.gd3` 客户端
    约束、自举测试模式）
  - `doc/module_impl/api/rpc_api_implementation.md`（`analyze.run` / `module.copy` 合同）
  - `doc/module_impl/common_rules.md`
  - `src/main/resources/extension_api_451.json`（Godot 4.5.1 绑定元数据，签名事实源）
  - Godot 4.5 分支源码（见 §2 各节引用）
- 明确非目标：
  - `.gd3` 的运行时执行、调试器、性能分析、重构工具、`.gd3` 间的脚本继承。
  - 把 `plugin.gd` / `gdcc_dock.gd` 等解释型脚本迁移为 gdcc 编译目标（沿用
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
   LSP 服务），GDScript 侧无错误时叠加 gdcc 前端 `analyze.run` 的诊断（合并显示，§4.2）。
3. 代码补全与参数提示代理到 GDScript LSP（§4.3）。
4. 自动缩进逐字镜像 4.5 GDScript 参考算法（§4.8）；悬浮/符号查找（`_lookup_code`）在
   Godot >= 4.6.0 代理到 LSP，旧版本因浅层脚本接管资源路径的引擎缺陷返回
   `ERR_UNAVAILABLE`（§4.6）；成员大纲经异步 `documentSymbol` 提供（§4.7）；全局类名
   注册暂缓（§9 Stage C）。
5. gdcc 编译服务未在目标端口侦听时，按**用户配置的启动命令**自动拉起 `gdcc serve`；
   插件卸载/编辑器退出时正确关闭**由本插件拉起**的服务进程（外部启动的服务绝不触碰）。
   未配置启动命令时保持"被动连接、失败即报错"行为（§3.7）。
6. 编辑器内一键 Build：`module.copy` 复制诊断模块为一致快照、编译副本、部署动态库到
   项目 `bin/` 并生成/更新 `bin/gdcc.gdextension`，经引擎扫描持久化并加载/重载（§4.9）。
7. 编译服务器安装向导：探测/安装 JRE、安装发布版/预览版/本地编译器、写入启动命令并
   安全替换在跑服务（§3.10）。
8. 全部语言集成代码为 `.gd3`，与 `gdcc_rpc_client.gd3` 同一模块（`gdcc_for_editor`）
   编译进同一 GDExtension 库；遵守该客户端的全部自举约束（§5）。例外（解释型 `.gd`）：
   `plugin.gd`、`gdcc_dock.gd`、`gdcc_project_manager.gd`、`gdcc_build_toolbar.gd`、
   `server_launcher.gd`、`gdcc_syntax_highlighter.gd`、`gdcc_setup_dialog.gd`——理由分别
   是"语言注册前就需要"（plugin/dock/launcher/manager/toolbar）、编辑器专有基类不可被
   编译类继承（高亮器，§5）、依赖信号 `await` 协程与 EditorFileSystem/GDExtensionManager
   （manager/向导）。

### 1.2 总体架构

```text
Godot 编辑器
├── plugin.gd（解释型 GDScript）
│     └── _enter_tree:
│           ├── 常驻节点 GdccEditorService 就位与身份核验（fail-closed，先于任何设置改动）
│           ├── 暂存并设置 network/language_server/use_thread = true（LSP 线程模式硬前提；
│           │   卸载时恢复原值；该设置退出编辑器时被引擎持久化，§2.6）
│           ├── 注册项目设置 schema 与旧键迁移（gdcc/compilation_server/* 等，§3.6）
│           ├── 低功耗 busy 协调器初始化（单一写入者，§3.5）
│           ├── server_launcher.gd（解释型）：按配置拉起/关闭 gdcc serve（§3.7）
│           ├── gdcc_project_manager.gd（解释型 Node）：共享项目操作（§3.8）
│           │     ├── gdcc_dock.gd：底部单行状态 + 只读输出视图
│           │     └── gdcc_build_toolbar.gd：标题栏 Build 按钮与设置弹出窗
│           ├── install(...)（失败完整回滚并恢复 use_thread）→ 连接 filesystem_changed
│           │   与 ProjectSettings.settings_changed 转发
│           ├── "编辑器"菜单挂"安装 GDCC 编译服务器"入口（§3.10）
│           ├── 注册 gdcc_syntax_highlighter.gd 并为已开页签接力应用（§3.9）
│           └── 仅运行期启用时 call_deferred 触发 EditorFileSystem.scan()
├── gdcc_for_editor GDExtension（gdcc 编译产物；安装为 reloadable = false，§6）
│     ├── GdccRpcClient            异步 HTTP JSON-RPC → gdcc serve（默认 127.0.0.1:6099）
│     ├── GdccEditorService (Node) 进程级常驻单实例（§3.5）：注册中枢与功能协调
│     │     （hub-and-spoke 子节点：GdccModuleLifecycle / GdccFileReconciler /
│     │       GdccDiagScheduler / GdccSourceRegistry / GdccDiagCache /
│     │       GdccCompletionService / GdccLookupService / GdccClassNameEraser /
│     │       GdccWorkspaceMirror / GdccOutlineService）
│     ├── GdccScriptLanguage (ScriptLanguageExtension)
│     │     ├── _validate       → LSP 诊断（线程模式下有界同步等待 ≤150ms）
│     │     │                     + 缓存的 gdcc 诊断合并（异步产生，§4.4）
│     │     ├── _complete_code  → 0xFFFF 哨兵定位光标 → LSP completion（≤400ms）
│     │     │                     + signatureHelp（≤200ms，§4.3）
│     │     ├── _lookup_code    → Godot >= 4.6.0：hover/definition + 可选符号检查
│     │     │                     （各 ≤200ms）；旧版降级（§4.6）
│     │     └── _auto_indent_code → 逐字镜像 GDScript 参考算法（§4.8）
│     ├── GdccScript (ScriptExtension, _can_instantiate() == false)
│     ├── GdccScriptFormatLoader (ResourceFormatLoader，_load 纯加载、线程安全)
│     ├── GdccScriptFormatSaver  (ResourceFormatSaver)
│     ├── GdccLspClient (Node)   最小 LSP 客户端：StreamPeerTCP → GDScript LSP
│     │                          （默认 127.0.0.1:6005，线程模式下服务端独立线程轮询）
│     ├── GdccMarkdownToBbcode (RefCounted 静态)   LSP Markdown → 文档 BBCode（§4.6）
│     └── GdccInstallService (Node) + GdccInstallUtil (RefCounted 静态)（安装向导，§3.10；
│         由 gdcc_setup_dialog.gd 直接持有，不挂在服务树下）
└── gdcc serve                     analyze.run / module.copy / compile.* 等；
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

## 2. Godot 集成点事实核查

以下结论均已对照 Godot 4.5 分支源码或本仓库 `extension_api_451.json` 核实；标注其他
版本处按标注核验。

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
  `_enter_tree`。主题刷新却按当前注册语言数设置图标，迟注册会导致菜单索引越界。
  `plugin.gd` 在注入图标前为已有对话框补齐 GD3 行，并在注销后移除该行，按
  `ScriptServer` 的末项换位规则保留其他语言与选中项。通过首项不翻译的 `GDScript`
  定位语言选择器，避免依赖会本地化的 accessibility name；Godot 版本升级需复核该结构。
  GD3 行在 Godot ≤ 4.6.0 禁用（创建入口走 dock 按钮或文件面板外创建），> 4.6.0
  允许在原生对话框选择 GD3（§10 R5）。

### 2.2 ScriptLanguageExtension 合同

`doc/classes/ScriptLanguageExtension.xml`（4.5 分支）中绝大多数虚函数标注
`virtual required`，对应 `extension_api_451.json` 中 `is_required=true`。
`core/object/script_language_extension.h` / `.cpp` 为基类全部纯虚函数提供了 GDVIRTUAL
转发 + 安全默认值（空/0/false）：**未实现不会崩溃**，但 required 虚函数首次被调用会
打印 `ERR_PRINT_ONCE`（`core/object/make_virtuals.py` 生成的守卫）。分档策略：

- A 档（显式实现，决定核心体验与日志干净）：`_get_name`、`_get_type`、`_get_extension`、
  `_get_recognized_extensions`、`_get_reserved_words`、`_is_control_flow_keyword`、
  `_get_comment_delimiters`、`_get_string_delimiters`、`_validate`、`_validate_path`、
  `_create_script`、`_supports_builtin_mode`（返回 false）、`_can_inherit_from_file`、
  `_handles_global_class_type`、`_thread_enter`、`_thread_exit`、`_init`（no-op，
  语义见 §3.2）、`_finish`（no-op）、`_frame`（no-op）、`_has_named_classes`
  （4.5 已废弃但 required，返回 false）、`_find_function`、`_make_function`、
  `_can_make_function`、`_open_in_external_editor`、`_overrides_external_editor`、
  `_add_global_constant`、`_add_named_global_constant`、`_remove_named_global_constant`、
  `_reload_all_scripts`、`_reload_scripts`、`_reload_tool_script`。
- B 档（功能实现）：`_complete_code`、`_lookup_code`、`_auto_indent_code`、
  `_make_template`、`_get_doc_comment_delimiters`（非 required）已实现；
  `_get_global_class_name` 返回 `{}`（暂缓，§9 Stage C）；`_get_built_in_templates`
  返回空列表、`_is_using_templates` 返回 false；`_preferred_file_name_casing`
  （非 required）未实现，基类默认行为安全。
- C 档（跳过，接受默认值与可能的 `ERR_PRINT_ONCE`）：`_profiling_*` 全部与
  `_debug_get_stack_level_instance`（返回 `void*` / 接收
  `ScriptLanguageExtensionProfilingInfo*`，gdcc 无法表达；调试/性能分析本就在非目标内，
  这些路径不会被触发）。**注意**：其余 `_debug_*` 与 `_get_public_functions`、
  `_get_public_annotations`、`_get_public_constants` 全部显式实现为空答案——引擎的
  脚本调试器在任何脚本错误的打印路径上会对**每个**已注册语言调用
  `debug_get_current_stack_info`（required 虚函数，缺省实现报 `ERR_PRINT_ONCE` 级
  错误），跳过会污染编辑器日志；typed array 返回可实现已经探针验证
  （`_get_public_functions() -> Array[Dictionary]` 等签名经 analyze+lowering、native
  compile 与运行时实调往返验证）。

关键 Dictionary 契约（逐字来自 `core/object/script_language_extension.h`，4.5）：

`_validate(...) -> Dictionary`：

| 键 | 类型 | 说明 |
|---|---|---|
| `valid` | bool | 缺省视为 false |
| `errors` | Array[Dictionary] | 每项必须含 `line:int`、`column:int`、`message:String`；`path:String` 在本集成中**必填**（缺省时 `ScriptTextEditor` 会把错误归入"依赖脚本错误"而非当前文件，见 §4.2） |
| `warnings` | Array[Dictionary] | 每项必须含 `start_line:int`、`end_line:int`、`code:int`、`string_code:String`、`message:String`，缺任一键被引擎丢弃 |
| `functions` | PackedStringArray | `validate_functions=true` 时读取本集成的大纲缓存，格式 `方法名:1基行号`；内部类方法为 `Inner.method:行号`（§4.7） |
| `safe_lines` | PackedInt32Array | 可选，本集成不提供 |

`_complete_code(code, path, owner) -> Dictionary`：

| 键 | 类型 | 说明 |
|---|---|---|
| `result` | int(Error) | 缺失时引擎按 `ERR_UNAVAILABLE` 处理 |
| `force` | bool | 必填 |
| `call_hint` | String | 必填（可为 `""`） |
| `options` | Array[Dictionary] | 每项**必填**：`kind:int`、`display:String`、`insert_text:String`、`font_color:Color`、`icon`（可为 null）、`default_value`（可为 null）、`location:int`；可选 `matches:PackedInt32Array`（成对；4.5 `script_language_extension.h` 以 `op.has("matches")` 守卫读取） |

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
  （§3.5）。**自愈回退**：创建资源对话框直接 `ClassDB.instantiate` 创建 `GdccScript`
  会绕过 loader/语言工厂的 `setup()`，使 `_language` 为 null，而引擎在脚本编辑器
  高亮/校验路径上对 `get_language()` 无判空解引用（`script_text_editor.cpp`，实测
  signal 11 崩溃）。因此 `_get_language()` 在字段为空时先查 `ScriptServer` 已注册
  语言表（ACTIVE），再查常驻服务持有的实例（UNINSTALLED；实例常驻不释放）。
- `_has_source_code() -> bool` → true；`_get_source_code()/_set_source_code(code)`：
  内存字符串字段。`_set_source_code` 置 `_valid = true`（有源码即有效，`_reload`
  失败才置回）。
- `_reload(keep_state: bool) -> int(Error)`：按 `_load` 传入的路径重读文件、更新
  `_source` 与解析缓存字段。**不触发**任何分析/调度（线程安全，见 §2.4）。
- `_is_valid() -> bool`：加载/校验状态。
- `_is_tool() -> bool`：恒 `false`（避免编辑器尝试执行）。
- `_get_instance_base_type() -> StringName`：解析 `extends` 行，缺省 `&"RefCounted"`。
- `_get_global_name() -> StringName`：解析 `class_name` 行，缺省空。
  页眉扫描只解析列 0 起始行；关键字边界为空格/制表符；同行 `class_name X extends Y`
  的 `extends` 查找按双侧空白边界扫描（兼容制表符）。
- `_get_base_script() -> Script`：null。`_inherits_script(script) -> bool`：false。
  `_instance_has(object) -> bool`：false。
- `_get_doc_class_name() -> StringName`、`_get_documentation()`：承载符号查找发布的
  LSP 脚本文档缓存（按声明类名命名，缺省为路径，§4.6）；源文本变化时清空资源侧
  当前缓存，但保留上次发布的类名/文档快照，供编辑器线程在源更新通知或重新发布时
  清除帮助缓存中的全部旧类（含内部类）。`_get_class_icon_path()` 返回插件脚本图标路径。
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
  节点时编辑器拿不到占位实例（§10 R7）。
- 显式空实现（`TypedArray[StringName]`/`TypedArray[Dictionary]` 返回类型的 override
  支持已经探针验证；默认空列表行为安全）：`_get_members`、`_get_constants`、
  `_get_script_method_list`、`_get_script_property_list`、`_get_script_signal_list`。

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
可选虚函数中本集成实现了 `_get_resource_uid`（恒 -1）；`_recognize_path`、
`_get_dependencies`、`_get_resource_script_class` 等未实现，基类默认行为安全。

两条硬性约束：

1. **线程安全**：`_load` 可能在 `WorkerThreadPool` 上执行
   （`core/io/resource_loader.cpp` 的 threaded load 路径）。实现必须是纯加载：
   只用入参 `path`/`original_path` 与 `FileAccess` 读文件、创建并填充 `GdccScript`，
   不调用 `get_path()`（引擎在 `_load` 返回后才绑定路径）、不触碰任何 Node/服务/缓存。
2. **依赖注入**：loader 由 `GdccEditorService` 创建时经 `setup(language)` 注入
   语言实例，禁止依赖单例或静态字段；saver 无注入（`_save` 只用入参）。

### 2.5 脚本编辑器集成

- `editor/script/script_text_editor.cpp`：`ScriptTextEditor` 是通用脚本编辑器，对任意
  `Script` 资源按其 `get_language()` 分发：`_validate_script()` 调用语言的
  `validate(...)`（传入**编辑缓冲区当前文本**而非磁盘文本），并显示错误/警告/安全行；
  `_code_complete_script()` 调用 `complete_code(...)`；符号跳转/悬浮调用
  `lookup_code(...)`。语法高亮**不**来自语言的分隔符接口：脚本编辑器按脚本语言名匹配
  已注册 `EditorSyntaxHighlighter` 的 `_get_supported_languages()`（仅当资源不是 Script
  时才退回扩展名匹配），因此 `.gd3` 需要自带高亮器实现（`gdcc_syntax_highlighter.gd`，
  见 §3.9）。
- `_validate_script()` 的触发点（4.5 实测）：页签启用（`enable_editor`）、
  `reload_text()`（外部重载）、编辑器 idle 定时器超时
  （`CodeTextEditor._text_changed_idle_timeout`，one-shot，由文本变更或
  `CodeTextEditor.validate_script()` 重启）。没有**公开的**主动重校验 API：
  `validate_script()` 方法未绑定到脚本层；本集成经下一条的 `validate_script` 信号
  通道主动触发重校验（§4.5）。错误计数为 0 时 idle 间隔
  `text_editor/completion/idle_parse_delay`（默认 1.5s），有错误时
  `idle_parse_delay_with_errors_found`（默认 0.5s；`set_error_count` 在有错误显示时
  切换过去，`code_editor.cpp:1733-1737`）。
- 主动重校验通道（4.5 源码核实 + 缺陷实证）：`CodeTextEditor` 注册了
  `validate_script` 信号（`code_editor.cpp:1857`），`ScriptTextEditor` 将其连到
  `_validate_script()`（`script_text_editor.cpp:2672`）。发射目标注意两点：(1)
  `ScriptTextEditor.get_base_editor()` 直接返回内层 **CodeEdit**
  （`script_text_editor.cpp:2150`），信号在其**父级** CodeTextEditor 包装上，须从
  base editor 向上找第一个 `has_signal("validate_script")` 的祖先再发射；(2) **缺陷
  实证**（引擎测试 bisect）：gdcc 编译代码经 `Object.emit_signal` method-bind 对外来
  对象发射，从 `SceneTree.process_frame` 泵调用栈发起时**任何连接都收不到**（同对象
  id、信号已注册、调用无错误返回但不触发；同一调用从非泵直发栈发起则正常，自有信号
  `.emit()` 从泵发起也正常；编译代码对普通 Node 的 method-bind 发射在 runtime 上下文
  可送达——缺陷仅限编辑器泵调用栈）。故最终形态：service 泵在门禁通过后发射**自有**
  信号 `revalidation_requested(path, version)`（泵上下文可达解释型监听），解释型
  `plugin.gd` 先 `call_deferred` 跳出泵栈、再在消息队列帧发射 `validate_script`
  （§4.5）。该信号接线属编辑器内部契约而非公开 API 承诺，Godot 升级时重验（§10
  R24）。
- 光标位置传递机制：请求补全/查找时，编辑器把光标位置以哨兵字符
  `String.chr(0xFFFF)` 插入源码（`scene/gui/code_edit.cpp`），语言实现需自行定位并
  剔除；GDScript 解析器以同一哨兵确定补全上下文
  （`modules/gdscript/gdscript_parser.cpp`）。`validate` 的入参文本**不含**哨兵。
- 左侧成员大纲来自 `ScriptEditor._update_members_overview()` →
  `ScriptTextEditor.get_functions()` → `ScriptLanguage.validate(..., r_functions)`，
  不是 `ScriptExtension._get_script_method_list()`。原生 GDScript 的
  `get_function_names_recursively()` 只列出类方法与静态函数，递归内部类并使用点限定名；
  不显示变量、信号、常量、属性访问器或函数体内 lambda。编辑器把 `name:line` 中的
  1 基行号减一作为跳转位置，且只有 `valid=true` 时更新缓存；有错误时保留旧大纲。
  源码依据：Godot 4.6.3 的
  [`gdscript_editor.cpp:133–143`](https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_editor.cpp#L133-L143)
  与 [`script_text_editor.cpp:126–140`](https://github.com/godotengine/godot/blob/4.6.3-stable/editor/script/script_text_editor.cpp#L126-L140)。
- `.gd3.uid`：4.4+ 编辑器会为脚本生成 `.uid` 伴生文件，对本集成无害。

### 2.6 GDScript LSP 服务（编辑器内建）

- `GDScriptLanguageServer` 是随 GDScript 模块注册的编辑器插件
  （`modules/gdscript/register_types.cpp`，编辑器构建时挂到 `EditorNode`），
  在 `EditorNode::is_editor_ready()` 之后才开始监听——**晚于**插件 `_enter_tree`，
  客户端必须重试连接（§4.1）。默认 `127.0.0.1:6005`（EditorSettings
  `network/language_server/remote_host` / `remote_port`，命令行 `--lsp-port` 可覆盖，
  覆盖值无法运行时查询）。
- **线程模式（硬前提）**：`network/language_server/use_thread` 默认 `false`，此时
  服务端只在主线程帧通知里 `poll()`；`_validate`/`_complete_code` 同在主线程执行，
  若在其中阻塞等待响应，服务端永远得不到执行机会，必然超时且每次冻结编辑器。
  因此本集成把该设置置为 `true`（插件 `_enter_tree` 早于服务启动，正常启动路径
  生效）。两点实测行为：
  1. `EditorSettings.set_setting` 后**退出编辑器时引擎会自动持久化**该设置
     （`EditorSettings::destroy()` 调 `save()`）——不存在"会话级"设置。因此插件必须
     在卸载时恢复用户原值（§4.1），并在文档与 dock 状态中明示这一改动。
  2. 服务端在运行期监听该设置变化并自动重启监听（`gdscript_language_server.cpp`
     `NOTIFICATION_EDITOR_SETTINGS_CHANGED` 分支比对 `network/language_server` 组后
     `stop(); start()`），因此运行期启用插件不需要重启编辑器，重连逻辑会接管
     新监听。**但该通知只由 C++ UI 路径**（`EditorSettings::notify_changes()`，设置
     对话框等）发出，`set_setting` 不触发，脚本无法驱动服务端运行期重启；运行期翻转
     的门禁见 §4.1 第 2 步（`lsp_blocking_unsafe` / `lsp_thread_primed`）。
- 协议为标准 LSP（`Content-Length` 帧头 + JSON-RPC）。已实现方法含 `initialize`、
  `textDocument/didOpen|didChange|didClose|completion|signatureHelp|hover|definition|
  references|rename|documentSymbol` 等；诊断由服务端经 `textDocument/publishDiagnostics`
  推送，但默认只发给最近初始化的那个客户端（`LSP_MAX_CLIENTS=8`，`notify_client`
  默认目标是 `latest_client_id`）：外部 LSP 客户端（如 VSCode）并存时诊断可能发给
  别人，此时按超时降级到缓存（§10 R14）。
- **URI 方言（实证）**：服务端 `get_file_uri` 产出 `file:///` + 逐段 `uri_encode`
  （空 authority 形式；`file://E:/...` 会被 `get_file_path` 的 `parse_url` 把盘符
  误判为 host 而报 "nonlocal files"）。客户端必须按同一方言生成 URI
  （`GdccLspClient.path_to_uri`），服务端回显字节一致。
- **诊断位置（4.5 实证，4.6 相同）**：`update_diagnostics` 把每条诊断的 `character`
  报为错误行**首个非空白字符**位置（缩进的码点数，`range.end` 覆盖整行去空白内容），
  不携带 token 级精确列；全程码点计数，无 UTF-16/码点分歧（§10 R11 在诊断路径不显化；
  补全路径的 `character` 同样按码点索引行文本）。Godot 4.7 的
  [PR #114185](https://github.com/godotengine/godot/pull/114185) 改为报告真实 token
  范围，`GodotPosition.to_lsp()` 直接将原始字符串行列各减一，仍按码点而非 UTF-16
  单元计数。客户端映射不变；回归锚定分别覆盖旧版缩进列与 4.7 在 emoji 后的未闭合
  字符串起始引号列（UTF-16/字节误计会得到不同的值）。
- `initialize` 结果中 `capabilities.textDocumentSync` 在 4.5 是**对象**
  `{openClose, change: 1, willSave, willSaveWaitUntil, save}`（`change == 1` 即全量
  同步），不是整数 `1`；握手校验必须两种形状都接受（§3.4）。
- 该 LSP 是 GDScript 专用：`didOpen` 不检查扩展名/`languageId`（固定填
  `"gdscript"`），单文件解析与诊断对 `.gd3` URI 可用；但 workspace 索引只收 `.gd`，
  跨文件符号/类缓存解析对 `.gd3` 天然受限——由 LSP workspace 镜像（§7.3）补齐
  数据面。
- 工作区校验：服务端的 `rootUri`/`rootPath` 不匹配时不会拒绝服务，而是发
  `gdscript_client/changeWorkspace` 自定义通知并继续为本进程项目服务；客户端应以该
  通知 + `ProjectSettings.globalize_path("res://")` 判定是否连错编辑器实例，不能从
  `initialize` 返回值读取工作区（§10 R2）。

### 2.7 gdcc 侧既有基础

- `analyze.run`（同步、不落盘、不触发 C 后端）返回 `AnalysisResult`；诊断的 JSON 形状
  为 `result.diagnostics.diagnostics[]`（外层是快照对象，不是裸数组），条目字段
  `{severity, category, message, sourcePath, range}`，`sourcePath`/`range` 可空，
  `range` 含 `startByte/endByte` 与 1-based 的 `start/end{line,column}`
  （`json_rpc_service_implementation.md`，编解码测试 `RpcJsonCodecTest` 可佐证）。
  `analyze.run` 是同步整模块分析，在模块门闩上与其他编译/分析串行；服务端没有
  analyze 取消/超时 API。
- `includeLowering=false` 只跑共享语义管线；`includeLowering=true` 额外执行
  compile-only 门禁（`sema.compile_check`）与 lowering——仅前者会漏掉"GDScript 合法
  但 gdcc 无法编译"的错误。编辑器诊断目标是提前暴露真实编译失败，故默认使用
  `includeLowering=true`（服务字段 `analyze_include_lowering`）。
- 编辑器插件使用 `GdccRpcClient` 挂起对象 + `process_frame` 帧泵。私有诊断模块由
  `GdccEditorService` 独占创建/删除并自动同步；共享项目管理器不创建第二个诊断模块
  （`auto_setup_module` 只确保服务可达并宣布诊断模块 ID）。Build 经 `module.copy`
  建立隔离编译快照，诊断模块不承载编译任务，避免门闩冻结和 VFS 互相清空（§4.9）。
- gdcc 注册多类、engine virtual 严格签名校验（frontend+backend 双层，frontend 在
  analyze 阶段即拒绝错误签名）已具备：签名写错会在 gdcc 编译期报错，是签名合同的
  回归测试；但 analyze+lowering 不覆盖 C 代码生成与链接，故自举探针另设 native
  compile 与运行时冒烟级别（§8.1/§8.2）。

### 2.8 gdcc 服务进程管理

- 服务端启动形态：`java -jar build/libs/gdcc-<version>.jar serve [--host H] [--port P]
  [--max-request-bytes N]`（`Main.java` 路由到 `RpcServeCommand.java`；`--host` 缺省
  `127.0.0.1`、`--port` 缺省 `6099`、`0` 为临时端口）。JAR 为普通 JAR + 旁置 `lib/`
  （manifest `Class-Path`），启动命令的工作目录或 jar 路径必须保证 `lib/` 相对可达。
- 服务级关停由 **`server.shutdown() -> {}` RPC 方法**承担（协议方法表见
  `json_rpc_service_implementation.md`；notification 形态（无 `id`）同样执行 handler
  并触发退出，只是客户端收到 204 而非 200+body——等待方两种形态都接受）。
  时序合同（现有写回路径决定，违反即死锁）：dispatcher 同步调用 handler，
  **handler 返回后** HTTP 层才 `sendResponseHeaders` + 写 body，`try (exchange)` 在
  `handle` 返回前关闭连接（`JsonRpcHttpHandler.java`）。因此方法 handler **只做**两件
  事：用 `AtomicBoolean` 保证只登记一次退出意图（幂等，重复调用返回相同结果）、返回
  `{}`；退出意图经**请求线程上的 ThreadLocal 服务标记**归属到"实际调用了该方法的
  exchange"（防无关请求冒领）。`JsonRpcHttpHandler` 在该 exchange 写完且
  try-with-resources close 之后检查标志位，**启动一条不属于请求 executor 的平台
  线程**调用 `System.exit(0)`，然后自己正常返回。三条禁令：禁止在方法 handler 内
  启动退出线程；禁止在请求线程上直接 `System.exit`——即使 exchange 已 close，
  JDK `HttpServer.stop(0)` 会等待 handler 返回，hook 的
  `stop(0)`/`executor.close()` 与尚未返回的请求线程互相等待死锁；禁止用 sleep 赌
  写回完成。exit 后由既有 hook 完成优雅收尾（`server.close()` + `API.close()`，
  在途编译取消并最长约 30s 等待 runner）。并发双 shutdown 的合同：先完成写回者触发
  退出，另一并发调用方可能断连（双方请求同一退出，结果等价）。仅靠 `OS.kill` 不够：
  Windows 上它是 `TerminateProcess`，**不会**执行 JVM shutdown hook。验收必须打真实
  `POST /rpc`：读到 200 与 body 之后进程才退出（退出码 0），只断言 dispatcher
  返回值的单测无效（回归锚点 `RpcServerShutdownTest`）。
- Godot 4.5 进程 API（`extension_api_451.json:179748` 起）：
  `OS.create_process(path: String, arguments: PackedStringArray, open_console: bool
  = false) -> int`（返回 PID，失败为负值）；`OS.kill(pid: int) -> Error`；
  `OS.is_process_running(pid: int) -> bool`；`OS.get_process_exit_code(pid) -> int`。
  另有阻塞型 `OS.execute`，本功能禁用（主线程阻塞）。注意 `OS.create_process` 的
  失败时点随平台分叉（`os_windows.cpp` / `os_unix.cpp`）：Windows 的 CreateProcessW
  同步拒绝不存在的可执行文件，返回负值；Unix-like 先 fork 成功返回正 PID，可执行
  文件不存在要等子进程 execvp() 才暴露——引擎在子进程里打印 `Could not create
  child process` 并 `raise(SIGKILL)`，父进程随后由 `OS.is_process_running` 的
  waitpid 轮询观察到早退。
- 服务端无 PID 文件/单实例锁/多实例发现协议；同端口重复绑定会失败，不同端口可并存。
  拉起前必须先探测目标端口，避免重复拉起或抢占外部实例（§3.7 所有权规则）。

---

## 3. 组件设计

全部位于 `src/editor_addon/addons/gdcc/`，`@tool`、带 `class_name`，编译型 `.gd3` 与
`gdcc_rpc_client.gd3` 同约束（§5）。模块 VFS 写入路径保留 addon 相对路径
（`/src/gdcc_script.gd3`、`/src/util/gdcc_install_util.gd3` 等）。

### 3.1 `gdcc_script.gd3` — `GdccScript extends ScriptExtension`

职责：`.gd3` 源文本的资源载体。字段：`_source: String`、`_valid: bool`、
`_global_name: StringName`、`_base_type: StringName`、`_language: GdccScriptLanguage`
（创建时由 `setup(language)` 注入）。方法按 §2.3 清单实现；`class_name`/`extends`
解析用行扫描（按 `\n` 分行 + 前缀匹配），不引入 RegEx 依赖。`_reload` 只重读文件并
更新上述字段，不触发任何分析调度（§2.4 线程安全约束同样适用于此）。
`_get_language()` 带自愈回退（§2.3）。另承载符号查找发布的文档缓存
（`set_lookup_documentation` / `published_lookup_documentation` /
`lookup_documentation_source` / `clear_lookup_documentation`，§4.6）。

### 3.2 `gdcc_script_language.gd3` — `GdccScriptLanguage extends ScriptLanguageExtension`

字段：`_service: GdccEditorService`（由 `setup(service)` 注入；由于二者同为进程级
常驻实例（§3.5），该引用在整个编辑器会话内有效）。实现清单（签名与 4.5 元数据严格
一致；A/B/C 档全集见 §2.2）：

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
func _supports_builtin_mode() -> bool: return false   # 不允许场景内建 .gd3 脚本（§10 R7）
func _supports_documentation() -> bool: return false
func _can_inherit_from_file() -> bool:  return false
func _handles_global_class_type(type: String) -> bool: return type == "GdccScript"
func _find_function(function: String, code: String) -> int
func _make_function(class_name: String, function_name: String,
        function_args: PackedStringArray) -> String
func _can_make_function() -> bool:      return false
func _open_in_external_editor(script: Script, line: int, column: int) -> int  # ERR_UNAVAILABLE
func _overrides_external_editor() -> bool: return false
func _complete_code(code: String, path: String, owner: Object) -> Dictionary   # §4.3
func _lookup_code(code: String, symbol: String, path: String, owner: Object) -> Dictionary  # §4.6
func _auto_indent_code(code: String, from_line: int, to_line: int) -> String   # §4.8
func _make_template(template: String, class_name: String, base_class_name: String) -> Script  # §4.8
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
func _is_using_templates() -> bool:     return false
func _get_built_in_templates(object: StringName) -> Array[Dictionary]  # 空列表
func _get_global_class_name(path: String) -> Dictionary  # {}（暂缓，§9 Stage C）
```

`_init` 语义结论（探针证实）：gdcc 把 `func _init() -> void` 建模为类构造钩子（骨架层
固定 `void _init(...)`），同时后端按名字匹配把它注册为 `ScriptLanguageExtension._init`
的 engine virtual 分发（`checkVirtualMethod` 走名称查找）；`() -> void` 签名与元数据
一致，两条路径兼容，无需省略该 override。注意语义边界不变：语言注册晚于
`ScriptServer::init_languages()`，引擎不会对本语言调用 `init()`；构造钩子形参默认值
仍不支持（frontend 既有约束）。

### 3.3 `gdcc_script_format_loader.gd3` / `gdcc_script_format_saver.gd3`

按 §2.4 实现。`_load` 用入参路径读文件、创建 `GdccScript` 并 `setup(language)`、
填充 `_source` 后返回；缓存模式按参考实现惯例处理（`CACHE_MODE_REUSE` 先查
`get_cached_ref`，`CACHE_MODE_REPLACE*` 用 `take_over_path`）。`_save` 用
`FileAccess.open(path, WRITE)` 写回 `resource.get_source_code()`（先 `as GdccScript`
守卫，失败返回 `ERR_INVALID_PARAMETER`），并做完整写后校验：检查 `store_string` 布尔
返回、close 后显式 READ 重开 + `get_error` + 内容比对（`flush()` 不更新
`get_error`，`get_file_as_string` 把读取失败与空文件混为 `""`）；写失败不误报 OK。
saver 仅在写盘和读回校验均成功后记录项目脚本的最近保存时间（`last_script_save_time`，
供构建状态提示 §3.8 使用）；未保存输入、其他资源保存、失败保存及文件系统扫描不更新
此记录。

### 3.4 `gdcc_lsp_client.gd3` — `GdccLspClient extends Node`

最小 LSP 客户端，`StreamPeerTCP` 实现，与 `GdccRpcClient` 同款帧泵纪律：

- `start(host: String, port: int)` 启动连接（`stop()` 断开）；状态机
  `DISCONNECTED / CONNECTING / READY / DEGRADED`。连接采用**有上限指数退避重试**
  （LSP 服务在编辑器 ready 后才监听，插件加载时立即连接大概率被拒，见 §2.6）；
  断开/服务重启后同样重连。DEGRADED 可恢复，不进入终态 FAILED。
- 握手：`initialize`（`rootPath` 与 `rootUri` 都填项目根）+ `initialized`。
  能力校验：`capabilities.textDocumentSync` 为 int 时接受 `== 1`；为 Dictionary 时
  接受 `change == 1`；其余（增量/未知）置 DEGRADED 并告警。监听
  `gdscript_client/changeWorkspace` 通知，与本项目根不符时置 DEGRADED（连错实例）。
- 帧解析：累积 `PackedByteArray` 缓冲，扫描 `Content-Length: N\r\n\r\n` 边界后
  `JSON.parse_string`；`Content-Length` 用 `is_valid_int()` 严格校验（`to_int()`
  会吞非法值）；不完整帧保留到下次。
- 通知路由：`textDocument/publishDiagnostics` → 按 URI 写入诊断缓存。4.5 服务端
  通知**不含 `version` 字段**（`GDScriptWorkspace::publish_diagnostics` 只写
  `uri` + `diagnostics`），但服务端对每次 `didOpen`/`didChange` 都同步解析并恰好
  发布一次诊断，且同一连接上按 FIFO 顺序发送（`gdscript_text_document.cpp` /
  `gdscript_workspace.cpp` / `gdscript_language_protocol.cpp` 的 res_queue）。客户端
  据此维护**每 URI 待确认 generation 队列**：发送 `didOpen`/`didChange` 时把递增
  generation 追加到队尾；收到通知时弹出队首并与之绑定（"发送后首个通知属于当前
  generation"的简单规则在旧通知仍在途时是错的，必须用队列）；队列为空时收到的
  通知属服务端主动产生，不得推进任何等待水位；**断线**清空该连接全部待确认
  generation；**超时不清空**——服务端在存活连接上对每次 didOpen/didChange 恰好
  发布一次，迟到的通知会弹出自己的 generation，FIFO 自行对齐；若超时清空，迟到
  通知反而会错绑到清空后发送的下一代。发送失败回滚队列项与版本号并断连返回 -1。
  `_validate` 的同步等待即等待本次发送的 generation 出队——**水位线语义**：出队按
  FIFO 递增，等待断言为"已出队水位 ≥ 目标 generation"（相等断言会在两条通知同批
  读出时被后一条覆盖而永假）；另保留每 URI 最近 8 条 generation→诊断 绑定历史
  （`_diag_history`）供回溯断言。
- 请求/响应：`_send_request(method, params) -> int(id)` + `_pending: Dictionary`；
  `request_blocking(method, params, timeout_msec: int) -> Dictionary`：在调用线程内
  以非阻塞 `poll()` + `Time.get_ticks_msec()` 截止循环驱动读写分发，直到目标 id
  返回或超时；遵守 §1.2 的阻塞形态约束（禁 blocking mode、禁 `OS.delay_msec`、
  超时断连）。**仅当服务端为线程模式时合法**（安装时校验 EditorSettings，§4.1）。
  断线（aborted）与真超时区分：连接已失不再二次拆连（退避不被翻倍推后），真超时
  才拆连接；`_drive_io()` 后再判一次断线以消除截止边界竞态。`_in_blocking` 置位
  期间帧泵直接返回，避免重入。
- 异步请求面（供成员大纲 §4.7）：`request_async` / `take_response` /
  `cancel_request` 使用既有帧驱动收包；读取挂起状态不轮询 socket，也不阻塞。
  `ready_advanced` 信号在 READY 跃迁沿发射（服务接入 `refresh_busy`，低功耗窗口
  内重放不被饿死）；该信号从泵栈发射对解释型监听是投递安全的。
- 文档同步：`open_document(uri, text)` / `change_document(uri, text)`（全量，
  版本号递增）/ `close_document(uri)`；URI 为服务端方言 `file:///<逐段 uri_encode
  的绝对路径>`（`path_to_uri` / `path_from_uri`，§2.6）。`close_document` 的
  didClose 发送失败与 didOpen/didChange 同款——断连重建（无条件擦除本地态会让
  客户端/服务端打开集静默分叉）。线程模式复探测的探针文档放在 `res://.godot/`
  下（点目录不入双方扫描，避免与真实项目文件撞路径）。
- 可观察性（测试与门禁用）：`document_version_for(uri)`（LSP 文档版本前进断言，
  如擦除决策翻转的重同步）、`debug_sent_text(uri)`（每 URI 最近发送文本
  `_sent_texts`，随 didOpen/didChange/关闭/断连维护；翻转断言要求卸载后发送的是
  原文、加载后是擦除视图）。

### 3.5 `gdcc_editor_service.gd3` — `GdccEditorService extends Node`

注册中枢与诊断调度。**进程级常驻单实例**：节点挂在 SceneTree 根下（固定节点名
`GdccEditorService`），插件禁用时**不释放**——语言、加载器、保存器、LSP 客户端
实例同理保留，仅注销对外注册并断开连接。已加载的 `GdccScript` 与仍打开的编辑器
页签会持续持有语言实例（`script.get_language()` 随 `validate()` 继续被调），
常驻设计使这些引用始终有效；重新启用时**复用同一语言实例**重新注册，杜绝第二份
`GD3` 语言或悬空 `_service`（§10 R15）。状态机 `ACTIVE / UNINSTALLED`，
UNINSTALLED 下所有对外入口早退（`_validate` 返回 `{"valid": true, "functions":
PackedStringArray()}`，补全/查找返回降级字典）。

**内部架构（hub-and-spoke）**：根节点只负责生命周期、调度与子节点协作——基础设施
（常驻 RPC/LSP 客户端子节点、端点配置）、帧泵（`_on_diag_frame` 以固定顺序 tick 各
功能子节点：lifecycle → reconciler → scheduler → mirror →（eraser 延迟重同步）→
outline，随后统一 `_pump_revalidation`，最后 `refresh_busy`）、协调广播
（`broadcast_module_ready` / `broadcast_module_outage` / `notify_content_changed`，
生产者与消费者解耦）、`busy_delta` 的唯一边沿发射（轮询各子节点 `busy_wanted()` 聚合：
lifecycle/reconciler/scheduler/mirror/outline）、LSP 通道门面与对外公共 API 门面。
功能切片为常驻子节点（`install` 首调创建、永不释放）：

| 子节点 | 职责 |
|---|---|
| `GdccModuleLifecycle` | 私有模块建删/心跳/outage 恢复/setup 失败上限 |
| `GdccFileReconciler` | 磁盘↔VFS 文件集合对账（含排除 glob 与临时纳入，§7.4） |
| `GdccDiagScheduler` | 单飞 upload+analyze 链、镜像版本、失败退避、结果合并 |
| `GdccSourceRegistry` | 内容真值表/脏跟踪/墓碑/disk-known |
| `GdccDiagCache` | 过期可见的诊断缓存（§4.4） |
| `GdccCompletionService` | 无状态补全 + signature-help 切片（无 tick/busy/关停挂接） |
| `GdccLookupService` | 无状态符号查找切片（`_lookup_code`，§4.6） |
| `GdccClassNameEraser` | `class_name` 条件擦除：视图变换/准入备忘/GDExtensionManager 翻转监听（§7.2） |
| `GdccWorkspaceMirror` | LSP workspace 镜像（§7.3） |
| `GdccOutlineService` | 成员大纲：帧泵异步 `documentSymbol` 缓存（§4.7） |

子节点经根访问器（`rpc_client()`/`lifecycle()`/`registry()`/`cache()`/`reconciler()`/
`scheduler()` 等）与共享工具（`response_ok`/`response_error_code`/`vfs_path`）协作；
因 gdcc bootstrap MVP 不含自定义基类 `extends`，功能协议（`bind_service`/
`service_tick(now)`/`busy_wanted()`/`on_module_ready`/`on_module_outage`/
`on_content_changed`/`service_shutdown`/`service_reset_channel`）按约定实现而非继承。
新增功能 = 新子节点脚本 + install 中一个构造块 + 一个访问器 + 视需要的 tick 行、
`refresh_busy` 聚合项与广播/关停/重置挂接，不改动既有子节点逻辑。

- `install(editor_interface: EditorInterface, lsp_host: String, lsp_port: int,
  rpc_host: String, rpc_port: int) -> int(Error)` —— ACTIVE 状态下重复调用幂等早退
  （但仍把 host/port 写回客户端，见下）。首次调用时创建语言/加载器/保存器/LSP 客户
  端（语言经 `setup(self)`、加载器经 `setup(language)` 注入；保存器只构造不注入），
  同时创建**服务专用**的 `GdccRpcClient` 子节点（常驻；与
  项目管理器持有的客户端分属两条 HTTP 队列，诊断分析不会饿死构建进度轮询，§4.4）。
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
  对账与防抖共用同一调度泵。**纳入范围受 `gdcc/sync/excluded_globs` 项目设置约束**
  （§7.4）：命中排除 glob 的路径视同"不在项目集合内"——扫描不返回它，已镜像的它
  按删除路径对账出模块；**例外：打开页签中的排除文件临时纳入**（缓冲区即内容真源），
  页签关闭后由节流检查触发对账移出。
- `uninstall()`：停防抖调度并丢弃脏标记与在途分析回调 → 断 LSP → 移除
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
  dock 改为只向 plugin.gd 上报 busy ±1，不再直写 `OS.*`；协调器由管理器、launcher
  与诊断调度共用）——总计数 0→1 时保存进入前的原值并置 `false`，1→0 或
  `uninstall` 时恢复**该原值**（不写死 `true`，以免覆盖用户原本的设置）；dock 在
  `_exit_tree` 仍有 busy 时须先归零。
- gdcc 诊断通道：私有模块，模块 ID 含项目根哈希与进程 ID 以防多编辑器实例共享同一
  `gdcc serve` 时互相删除：`gdcc_editor_diagnostics_<projectRootHash>_<pid>`
  （`OS.get_process_id()`）。`install` 时懒创建（首个 `server.ping` 成功后
  `module.create`）；`module.create` 返回 `-32001`（同 ID 残留，例如上次进程崩溃
  未清理）时先 `module.delete` 再重建——ID 内含 pid，同 ID 必属本进程残留，不会
  误删他人模块；删除因模块门闩占用失败（-32002）则记日志并推迟重试。`uninstall`
  时仅删除自己成功创建的模块。端点重定向前经一次性旧端点客户端串行队列做
  fire-and-forget 删除（快速连切不会跳过中间端点的清理；切回某端点时丢弃其未发出
  的清理项，由既有 -32001 重建路径接管）；`module.create` 在途时发生重定向的过期
  成功应答经 `enqueue_orphan_cleanup` 向**发送端点**补登记删除。
- 分析参数：`analyze_include_lowering: bool = true`（§2.7）。
- VFS 映射合同：`res://a/b.gd3` → VFS 路径 `/src/a/b.gd3`，`put_file` 必须携带
  `displayPath = "res://a/b.gd3"`（`analyze.run` 诊断的 `sourcePath` 依赖
  displayPath 回映；缺省会得到 VFS 逻辑路径，缓存将键不上）。
- 诊断状态分散在两个子节点（§3.5 表）：`GdccSourceRegistry` 持有内容真值表
  `_sources`（`res://` 路径 → `{text, version}`）与 `_dirty`（路径 → 待分析版本）、
  墓碑与 `_disk_known`；`GdccDiagCache` 持有 `_entries`（`res://` 路径（displayPath）→
  `{version, items}`），读取过期可见（§4.4）。
- 供 `GdccScriptLanguage` 查询的接口（签名为合同，§4.2 依赖之）：
  `lsp_diagnostics_for(path) -> Array`；
  `notify_source_changed(path, text) -> int`（内容未变：返回现有版本、**不升版**；
  内容变化：版本 +1 并标脏；非项目路径返回 -1 不跟踪。**排除路径不拒绝**——notify
  只在文件有打开页签时发生，构成临时纳入，§7.4）；
  `gdcc_diagnostics_for(path, version) -> Array`（**过期可见**：缓存中有该路径的条目
  即返回（可能是编辑前旧版本的分析结果），直到新一轮分析合并将其替换；version 不再
  门控读取，新鲜度由 `is_version_current` 探针单独观测，§4.4）；
  `outline_functions(path, source) -> PackedStringArray`（大纲缓存读取，只登记需求
  不发网络请求，§4.7）。

### 3.6 `plugin.gd` 接线

`_enter_tree`（解释型 GDScript，可直接用编辑器 API；同步顺序，LSP 与 gdcc 模块为
异步就绪，不阻塞注册）：

1. 注册翻译域（§3.11）。
2. 常驻节点就位：`get_tree().root.get_node_or_null("GdccEditorService")`，命中时必须
   `is GdccEditorService` 校验身份，类型不符则 `push_error` 并放弃安装（fail-closed，
   绝不创建第二个常驻节点或第二份语言实例）；不存在则 `GdccEditorService.new()` +
   命名 + `add_child` 到根。**此步先于任何设置改动**，放弃路径无需恢复现场（仅回滚
   翻译注册）。
3. 读取 LSP 端点（`network/language_server/remote_host`/`remote_port`，缺省
   127.0.0.1/6005）；暂存并设置 `network/language_server/use_thread = true`（持久化
   副作用与恢复义务见 §2.6/§4.1）。
4. 注册项目设置 schema（`gdcc/compilation_server/host|port|launch_command`、
   `gdcc/sync/excluded_globs`、`gdcc/build/optimization_level|strict_mode|
   c_compiler_kind`）并做旧键一次性迁移（§3.7 配置项）；配置非法时 fail-closed
   回退默认值并 push_error。
5. 初始化低功耗 busy 协调器（计数 0，此时**不触碰** `OS.low_processor_usage_mode`；
   仅登记保存/恢复逻辑，首个 busy 0→1 时才保存原值并置 `false`，§3.5）。协调器
   必须先于任何 `ensure_server_hook` 调用存在。
6. 创建 `server_launcher.gd` 实例并挂为子节点（§3.7），其 busy 上报指向协调器；
   创建项目专用 RPC 客户端和 `GdccProjectManager` 节点，将 launcher/service/协调器
   注入管理器；创建 dock 并挂到底部面板。dock 只持有管理器引用，不管理请求、模块
   或 busy（§3.8）。
7. 注入 `ensure_server_hook`（`server_launcher.ensure_running`）并连接服务的
   `busy_delta`/`revalidation_requested`/`outline_revalidation_requested` 信号
   （中继形态见 §4.5）；调用 `install(...)` 并检查返回的 Error，失败时
   `_rollback_failed_enter_tree()` 完整回滚 dock/管理器/客户端/launcher 并恢复
   `use_thread`。成功后调用管理器 `auto_setup_module()`，创建共享管理器的标题栏
   Build 控件并重排到 `EditorRunBar` 左侧（§3.8），挂"编辑器"菜单的安装向导入口
   （§3.10），注册语法高亮器并为当前页签接力应用（§3.9）。
8. 连接 `EditorInterface.get_resource_filesystem().filesystem_changed` → 转发服务的
   对账调度（重复启用时先 `is_connected` 检查，仅连接一次）；连接
   `ProjectSettings.settings_changed` → 全量转发（4.5 无 `get_changed_settings`，
   插件无法按 key 过滤）：host/port 变化经 `retarget_rpc_endpoint` 切换诊断通道
   （只对**配置值相对上次中继的变化**触发，显式 `service.install` 不会被无关设置
   写入拽回；配置非法 fail-closed 保持现有通道），排除 glob 变化触发对账（§7.4）。
9. 不直接调 `EditorFileSystem.scan()`：首次启动时本插件在首次扫描的 `init_plugins()`
   阶段进入树，同步 scan 会重入首次扫描；且 loader 注册后外层扫描本就会识别 `.gd3`。
   仅当编辑器已就绪后手动启用插件时（`is_scanning() == false`），用 `call_deferred`
   延迟触发一次 scan。

`_exit_tree`：断开事件转发连接 → 释放标题栏及 dock → 管理器核验并清理构建占用、
残余 busy 计数归零 → `uninstall()`（常驻节点保留；其中删除私有模块仍需服务在线）→
恢复 `use_thread` 原值（脚本恢复不会重启引擎 LSP 服务，仍必须发生在本客户端已断开、
语言已注销之后，顺序不可颠倒，§2.6、§4.1；离开前若服务端尚未开始监听需撤销
`lsp_thread_primed`）→ **最后** `launcher.shutdown_owned()` 关闭本插件拉起的服务
（§3.7，须在 uninstall 之后，否则模块删除失去服务端）。

### 3.7 `server_launcher.gd` — 编译服务拉起与关闭（解释型 GDScript）

职责：gdcc 编译服务的**按需拉起**与**所有权内关闭**。用解释型 GDScript（不用 `.gd3`）：
项目管理器在 gdcc 语言注册之前就持有它。形态为 `Node`，由 plugin.gd 创建并挂为
子节点，项目管理器与 `GdccEditorService` 共用同一实例（拉起是单飞操作，需要单点协调）。

- 配置：**项目级设置** `gdcc/compilation_server/launch_command`（String，默认 `""` =
  不自动拉起，保持纯被动连接行为）、`gdcc/compilation_server/host|port`。端点与启动
  命令属于"项目使用哪台编译服务"的配置，随项目版本化并共享给协作者（与排除 glob
  `gdcc/sync/excluded_globs` 同一先例）。plugin.gd 在 `_enter_tree` 用
  `set_initial_value` + `add_property_info` 注册 schema（端口 RANGE 提示 1..65535）。
  **旧键迁移**：旧项目键 `gdcc/server/host|port|launch_command` 在对应新键不存在时
  迁入并随后移除旧项目键（新旧并存时保留新键）；迁移先于默认值注册，迁移发生时
  push_warning 提示保存项目。没有项目配置时，旧机器级 EditorSettings `gdcc/server/*`
  值仍从原键播种（遗留 EditorSettings 键保留不删，便于降级回退）。launcher 每次
  拉起前重新读取（不缓存），项目设置对话框的编辑对下次拉起即生效。机器相关的可执行
  文件路径若不便提交，可把命令留空、改用外部启动的服务（被动连接行为不变）。
- 命令模板：支持 `{host}` / `{port}` 占位符，拉起前替换为目标端点；典型值使用 zig
  启动器 `gdcc serve --host {host} --port {port}`（zig 启动器原样转发参数给 jar，
  `json_rpc_service_implementation.md`；非 PATH 安装时写启动器的完整路径，如
  `D:/tools/gdcc/gdcc.exe serve --host {host} --port {port}`）。
  解析规则为空白分词 + 双引号成组（无 shell 展开、无管道；首 token 为可执行文件，
  其余为参数）。无占位符时按原样执行（服务须自行监听目标端点）。命令文本是用户
  显式配置的本地命令，以编辑器权限执行，信任边界见 §10 R22。
- `ensure_running(host: String, port: int, on_ready: Callable) -> void` —— 全程异步
  （`_process` 轮询驱动，不在主线程阻塞），完成时回调
  `on_ready(err: int, spawned_by_us: bool)`。**低功耗活性**：`_process` 在编辑器
  低功耗模式下会停帧（§3.5）；launcher **不自备**全局开关快照（双写入者会互相
  覆盖：把对方写的 `false` 当原值保存、恢复时互相踩踏），而是在 ensure 开始到回调
  完成期间向 plugin.gd 的 busy 协调器上报 ±1（§3.5/§3.6）。
  1. 探测：`StreamPeerTCP` 连接目标端点并短轮询（≤200ms）；已可连接 →
     回调 `OK, false`（**外部服务**，本会话绝不关闭它）。
  2. 拒绝连接且启动命令为空 → 回调 `ERR_CANT_CONNECT`（被动行为不变）。
  3. 本会话已为目标端点拉起过且 `OS.is_process_running(pid)` → 直接进入等待就绪。
     否则按模板 `OS.create_process(exe, args)`：返回值 ≤ 0 → 回调 `ERR_CANT_FORK`
     （Windows 同步失败路径：CreateProcessW 直接拒绝不存在的可执行文件）；空命令文本
     报 `ERR_INVALID_PARAMETER` 而非误报 `ERR_CANT_FORK`。成功则把 `{pid, host, port}`
     记录追加进 `_spawned` 列表（按端点追踪本会话拉起的每个进程），回调排队期间的
     其他调用者共享同一单飞操作。
  4. 等待就绪：周期性重新探测端口，最长 15s；超时且进程仍存活 → `OS.kill` 回收
     本次拉起并回调 `ERR_TIMEOUT`；进程已早退 → 回调 `ERR_CANT_CONNECT` 并在日志
     给出检查启动命令的提示（Unix-like 的不存在可执行文件路径落在这里：fork 已
     成功，子进程 execvp() 失败，引擎打印 `Could not create child process` 并
     SIGKILL 子进程，`is_process_running` 的下次轮询观察到早退）。
- `shutdown_owned() -> void`（plugin `_exit_tree` 最后一步）：逐条处理 `_spawned`
  中的记录（不丢弃记录），仅当记录的 `pid > 0` 且 `OS.is_process_running` 时执行。
  这是 §1.2 主线程阻塞禁令的**唯一豁免**：`_exit_tree` 期间帧泵可能不再运行，必须用
  非阻塞 `poll()` + `Time.get_ticks_msec()` 截止的有界循环（≤2s），禁用 `_process`
  等待与 `OS.delay_msec`。流程：一次性发送 `server.shutdown` RPC——**收到响应即
  完成**（退出已被触发，shutdown hook 会在后台完成 `API.close()` 的收尾，编辑器退出
  不等待它，绝不硬切在途编译）；RPC 预算按剩余记录均分，耗尽后进入带小额固定探测
  预算的 kill 兜底（软上限）；RPC 不可达/超时才考虑 `OS.kill`，且 kill 前必须再
  探测记录的端点仍在接受 TCP 连接（证明我们的服务进程仍存活并持有端口，防 PID
  复用误杀，§10 R19）；端口已关闭则跳过 kill 只记日志。完成后清空状态。编辑器
  异常退出（崩溃）走不到此路径，孤儿进程由下次会话的端口探测自然"收养"为外部
  服务（只连不关），安全。
- 所有权不变量：**只关闭本会话自己 `create_process` 拉起的 PID**；探测先于拉起，
  永不认领已在侦听的服务。PID 复用的残余风险记入 §10 R19。
- 向导交互式替换使用 `stop_owned_async(host, port)`：只向本会话记录的旧端点发送有
  超时的 `server.shutdown`，通过独立 RPC client 等待完整响应，不使用退出阶段的
  阻塞轮询。成功确认后移除该 PID 的跟踪，允许 JVM 在后台完成收尾；失败保留记录供
  退出阶段重试。外部端点直接跳过，其他已拥有端点不受影响；项目管理器在整个替换
  期间持有 busy（§3.10）。

### 3.8 共享项目管理与视图（`gdcc_project_manager.gd` / `gdcc_dock.gd` / `gdcc_build_toolbar.gd`）

- `plugin.gd` 创建唯一 `GdccProjectManager` 子节点（解释型 `.gd`：异步操作依赖信号
  `await`，构建部署使用 EditorFileSystem/GDExtensionManager；编译型 addon 的无协程
  自举约束不变，§5），注入独立 RPC 客户端、launcher、驻留诊断服务和 busy 协调器。
  `gdcc_dock.gd` 与 `gdcc_build_toolbar.gd` 仅持有同一管理器实例，不各自创建模块、
  客户端或构建副本。Ping/Create/Analyze、构建/取消、所有权核验、部署及延迟启用均由
  管理器执行，日志和动作状态通过信号传播。管理器不为 UI 拆分另建编译状态机，不改
  诊断服务的自动模块创建、全项目递归同步、排除 glob、打开排除脚本临时纳入以及关闭
  后的剔除行为。
- 所有普通动作在修改 RPC 端点前检查共享 busy 与待启用状态；构建在首次 `await` 前
  同步占用 busy。两个视图因此无法重入构建或将同一客户端改道。Cancel 继续使用任务
  启动时端点上的独立客户端（钉住任务端点，避免 -32002 取消重试端点错绑）。待扫描/
  重载阶段仍禁用 Build，轮询超时保留任务 ID，不阻止原有取消旧任务并重建路径。
- **dock（精简形态）**：连接时只有两块可视内容——顶部单行
  `Connected/Disconnected | Diagnostics: ... | Build: ...` 与下方只读 `TextEdit`
  输出；连接指示对应诊断通道 READY；诊断时间取最近成功合并轮的单调时钟，构建时间
  沿用成功安装/启用后的记录；尚无记录时显示 `Never`。诊断通道未 READY 时隐藏输出
  与 Clear，保留状态行，下方显示"手动配置编译服务器"（复用 Build toolbar 的原生项目
  设置导航，选中 `gdcc/compilation_server`）和"安装编译服务器"（打开向导，§3.10）
  两个按钮；日志仍在后台保留，连接恢复后自动显示原输出。输出水平/垂直填满剩余区域，
  最小高度按实际行高容纳至少六行并加内边距及横向滚动条预留高度，字体/主题变化后
  重新计算；状态文字不换行，窄宽度省略并可悬停读取完整内容；右侧主题图标 `Clear`
  清空现有输出（构建忙时仍可用，不修改任务、诊断或构建记录）。
- **标题栏 Build 控件**：根控件是 `HBoxContainer`——左侧 GDCC 图标与 **Build** 文本
  的动作按钮，右侧独立箭头打开 `PopupPanel`。使用公开 `CONTAINER_TOOLBAR` 添加后，
  在其实际父容器中按原生类名找到 `EditorRunBar` 并 `move_child` 到其左侧；不进入
  私有 `get_buttons_container()`（Godot 4.5.1 的公开容器实际追加到渲染选择器后，故
  需显式重排；内部类名不存在时警告并保留可用入口；插件退出时按原公开容器移除）。
  Build 与箭头均使用 `FOCUS_ACCESSIBILITY`（普通鼠标点击不留持续焦点框，保留读屏
  聚焦能力）。构建准备、编译、部署及待启用期间，Build 文本显示 `Building ` 加点阵
  序列 `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏`（每帧检查距上次切换是否超过 100ms，满足时仅推进一个
  字符，不补帧）；成功启用或构建失败/取消/轮询超时后恢复 `Build`；空闲时不运行
  动画。管理器单独发布构建状态，不把 Ping/Create/Analyze 等其他忙操作误显示为构建。
- **Build 弹出窗**：显示 `server.info` 返回的版本/分支/commit 和 **Last successful
  build**。服务版本与 `server.availableCCompilerKinds` 查询共用独立客户端（不能改写
  正在构建的端点）；诊断通道就绪后自动查询，每五秒及打开菜单时刷新；端点改变或断连
  后清除缓存，旧端点响应不更新 UI；查询使用具名信号回调，不依赖会被脚本重载取消的
  挂起 await。PopupPanel 显式使用编辑器主题并随标题栏主题变化更新；自动换行的版本
  标签具有与内容容器一致的最小宽度（避免隐藏窗口首次布局前按零宽度折行放大弹窗最小
  高度）。上次成功构建时间仅在扫描确认安装或本机加载/重载成功后记录，使用 Unix 秒
  时间戳，保存于项目 editor metadata `gdcc/last_build_time`，不混入共享项目配置。
- **构建参数**：项目设置 `gdcc/build/optimization_level`（字符串枚举 `DEBUG`/`RELEASE`，
  默认 `DEBUG`）、`gdcc/build/strict_mode`（bool，默认 false，沿用 API 编译策略字段）、
  `gdcc/build/c_compiler_kind`（默认 `AUTO`）。菜单始终额外保留 **Auto**，其他项仅
  来自 RPC 的可用后端列表（`ZIG_CC` 显示为 Zig，`TINY_CC` 显示为 Builtin）；Auto 回传
  null，沿用服务端 Debug→TinyCC、Release→Zig 的规则。未连接到编译服务、无可用后端
  或查询失败时 Build 禁用，菜单显示以编辑器警告图标开头的黄色提示；不可用的已保存
  显式选择同样禁用 Build，用户可重新选择 Auto 或可用后端。下拉修改即时更新
  ProjectSettings 并检查 `ProjectSettings.save()` 返回值。构建点击时冻结选择，
  `options.get` 获取完整副本快照，仅改 `projectPath`、`optimizationLevel`、
  `strictMode`、`cCompilerKind` 后整体回传（Auto 显式覆盖继承的后端选择，不修改诊断
  模块选项）；构建中的设置编辑只影响下一次构建。
- **GDCC Project Settings 入口**：弹出窗底部全宽按钮打开真实的 Project Settings
  General 页面、启用 Advanced Settings 并过滤 `gdcc`，选中可编辑的 `gdcc/build` 子
  分类（`gdcc` 父分类不可选）。4.5.1 没有公开的项目设置导航入口，适配器先核验
  `ProjectSettingsEditor` 及搜索行结构，再调用继承的 Window API 和筛选控件；找不到时
  提示手动打开，不替换成 Editor Settings 或私有配置文件。该结构依赖需在更换 Godot
  版本时复验。
- **相对时间**：`gdcc_project_manager.gd.relative_time_text` 是两个视图唯一的相对
  时间格式化入口（英文原文 + 中文翻译；负间隔按 0 秒）：<60s 显示整数秒（`59s ago`）；
  60–599s 向下取整到半分钟（`1.5min ago`）；600–3599s 向下取整到整数分钟；
  3600–86399s 向下取整到半小时（`1.5h ago`）；≥86400s 向下取整到半天（`1.5d ago`）。
  dock 状态行与 Build 菜单的 Last successful build 均每秒刷新，状态变化/菜单打开时
  立即刷新；空闲 Build 动画仍保持关闭。`gdcc/last_build_time` 旧格式（无偏移本机日期
  字符串）按当前本机 UTC 偏移一次性转换为 Unix 秒并保存，后续启动不再重复解释
  （旧格式未记录历史时区偏移，首次迁移跨夏令时的旧值可能有偏差）。
- **构建状态提示**：`.gd3` saver 在最近保存时间晚于最后一次构建尝试时，空闲按钮显示
  `Build*`（构建尝试以开始时间为界；构建中再次保存仍保留星号；没有新保存时已失败的
  构建只显示错误标记）。构建准备、启动、轮询、编译、部署或启用失败后，按钮在文字
  右侧使用编辑器原生 `StatusError` 红色图标（替换正常左侧 GDCC 图标），菜单单独显示
  红色"上次构建失败"；失败不禁用重试，取消不作为编译失败；尚在构建或等待启用时卸载
  插件按未完成构建保存失败状态（避免重新启用后误显成功）。按钮悬浮提示按优先级：
  断连 `Cannot connect to the GDCC compilation server` → 后端不可用
  `No compilation backend available` → 上次失败 `Compile GDCC scripts [last build
  failed]` → 默认 `Compile GDCC scripts`（中文编辑器使用翻译目录对应中文）。最近保存
  时间、构建尝试时间和失败状态分别保存在项目 editor metadata
  `gdcc/last_source_save_time`、`gdcc/last_compile_time`、`gdcc/last_build_failed`；
  旧项目尚无构建尝试记录时沿用既有成功时间；`last_build_time` 只表示安装/启用成功，
  不因失败构建改变。源码保存和构建尝试使用带小数的 Unix 秒区分同秒内先后事件。
- **卸载顺序**：先释放两个视图，再释放管理器（副本所有权清理及 busy 排空），之后
  注销诊断服务，最后关闭本插件拉起的服务器；失败安装同样回收管理器，不留半安装状态。

### 3.9 `gdcc_syntax_highlighter.gd` — `.gd3` 语法高亮（解释型 GDScript）

**成因**：脚本编辑器按脚本语言名匹配已注册 `EditorSyntaxHighlighter` 的
`_get_supported_languages()`（4.5 `script_editor_plugin.cpp`；仅非 Script 文本才退回
扩展名匹配），原生 `GDScriptSyntaxHighlighter` 只认领 `"GDScript"`，`.gd3`（语言名
`"GD3"`）会静默回落到 `EditorStandardSyntaxHighlighter`——关键字/引擎类型有颜色而
注解、类型提示、脚本成员、节点路径等 GDScript 专属词法着色全缺。**形态**：必须用
解释型 `.gd`——编译类注册在 GDExtension SCENE 级，编辑器专有基类在 EDITOR 级才存在
（§5 实证）。

**实现**：逐块移植 4.5 `gdscript_highlighter.cpp` 的行内状态机（区域、数字、关键字/
类名/成员/全局函数查表、函数/信号/声明名检测、点后成员、`expect_type`、`&^$%@` 前缀、
颜色优先级链与 `prev_text` 转移器），跨行区域经 `_color_region_cache` + 基类行缓存
回填；色值全部读 EditorSettings 同名主题键（含 `gdscript/*` 专属键与注释标记三色三
表），主题变更经编辑器既有 `update_cache()` 节拍生效。与原生仅有的方向安全差异
（只可能错色、不可能崩溃）写进类头注释（无 `is_class_exposed` 过滤、非 ASCII 一律按
标识符字符、空白仅空格/Tab、自有成员与基类来自缓冲区扫描）。**局部变量无语义着色**
——原生也没有，不属缺口。

**注册/应用**（plugin.gd）：install 成功后注册单个模板实例；编辑器只对新页签自动选择
（注册不触发既有页签重选），已开页签由 `_apply_highlighter_to_current_editor` 在启用
时（当前页签）与 `editor_script_changed`（后续焦点）接力应用。门控：资源路径扩展名
须为 `gd3`（`Script.get_language()` 4.5 未绑定，GDScript 页签会把处理器打挂）；仅
替换两个内置回落高亮器（Standard/Plain Text——`get_class()` 对未注册 GDCLASS 类也
上报**自身**派生名（`object.cpp::_get_class_namev`），据此精确识别；用户下拉选的
`GDScriptSyntaxHighlighter` 等语言高亮器绝不替换）；每个 CodeEdit 实例只 settle 一次
（`_handled_highlighter_editors` 按实例 ID 记忆，ID 会话内单调不重用），用户之后
手动切换的选择在页签往返间不被覆盖。`_exit_tree` 反注册模板并断开信号；已装实例留在
页签里继续工作（类是解释型的，卸载扩展不影响）。

**页签状态踩点（实证，引擎用例无法覆盖的真实会话路径）**：`ScriptTextEditor` 打开页签
时会按项目 `.godot/editor/script_editor_cache.cfg` 里保存的 `syntax_highlighter` 状态
恢复用户上次的高亮器选择——在本功能落地前打开过 `.gd3` 的项目把 `"Standard"` 钉在
缓存里，恢复动作发生在原生自动选择**之后**，把选好的实例换回 Standard。脚本侧无法
阻止恢复，但接力在焦点信号时按上述门控换回我们的高亮器（踩点页签此前从未 settle，
不会被 settle-once 挡住）；我们赋值后，页签的编辑状态在下一次布局保存/关页签时经
`get_edit_state()` 记为 `"GD3"`（非赋值的同步副作用），之后打开直接恢复我们的。

### 3.10 编译服务器安装向导（`gdcc_setup_dialog.gd` / `GdccInstallService` / `GdccInstallUtil`）

- 入口：诊断通道未 READY 时 dock 显示"安装编译服务器"按钮（§3.8）；顶部"编辑器"菜单
  另提供"安装 GDCC 编译服务器"，连接与断连状态都可打开同一向导。菜单条目使用独立
  命令 ID，菜单重建后在打开时恢复，语言变化时刷新，插件卸载时移除。
- 形态：向导 UI 是解释执行的 `gdcc_setup_dialog.gd`；安装服务编译为
  `GdccInstallService`（`gdcc_install_service.gd3`），文件/压缩包/版本解析编译为
  `GdccInstallUtil`（`util/gdcc_install_util.gd3`）；原生构建递归收集 `.gd3` 并保留
  addon 相对路径（§3.12）。安装服务是 §5 无协程约束的**受控例外**：可使用信号、
  lambda 与 `await`；公开异步方法返回 `Variant`，解释型向导通过 `await_result` 区分
  同步结果与带 `completed` 信号的挂起状态。协程只在主线程运行；工作线程只承担同步
  文件操作。服务等待自己的帧信号，在离树时取消并排空协程、join 工作线程，退出期间
  的剩余文件清理同步完成，不再创建新的挂起任务。
- UI 合同：顶部可点击的"安装JRE » 安装GDCC"步骤链接，就绪步骤显示绿色 ✔，右侧退出
  为链接文本；步骤与对勾按 2 像素间距分组，箭头两侧各 12 像素；行内符号不自动换行并
  与链接垂直居中。头尾固定、内容区滚动；弹出前仅显示第一页，默认尺寸按 720×460 缩放
  并限制在父窗口 80% 内（避免同时计算两页最小高度撑出屏幕）。底部进度条与下载文字
  始终参与布局，空闲时通过透明度隐藏；状态和下载文字单行裁剪，完整状态可悬停读取
  （避免新增内容在容器未排序时撑大 `AcceptDialog` 内容最小尺寸触发
  `Window.wrap_controls` 单向扩窗）。底部全宽按钮第一步为"下一步"、第二步为"完成
  编译器配置"；检查 Java 或安装 JRE 成功只启用下一步，不自动跳转。
- Java 探测：依次检查该项目上次确认的路径、`JAVA`、`JAVA_HOME`、`JDK_HOME`、
  `JRE_HOME`、PATH 以及 Editor Data 中的缓存，去重后列出所有可识别 Java 供用户选择。
  每个候选通过非阻塞进程管道执行 `--version`（必要时回退 `-version`）；低于 25 的
  版本仍显示但不可选。管道同时排空 stdout/stderr，有截止时间并只终止自己创建的探测
  进程；不存在的可执行文件不发起子进程。
- JRE 安装：查询 `script-gd/gdcc-jre/releases/latest`，按平台/架构选择 Windows x64
  ZIP、Linux x64/aarch64 或 macOS aarch64 tar.gz，使用同次发布的 `SHA256SUMS` 强制
  校验；没有对应资产的平台提示手动选择已有 Java，不猜测其他架构或静默使用模拟器
  版本。
- 缓存布局：根为 `EditorInterface.get_editor_paths().get_data_dir()/gdcc`（遵循
  Godot 的 Windows/Linux/macOS 和便携模式路径）：
  `runtimes/<发行包完整SHA256>[-<随机后缀>]/jre` 存运行环境，
  `compilers/release-<id>`、`actions-<id>`、`local-<完整SHA256>` 分离编译器安装。
  读取当前发布的 SHA256SUMS 后先查找同校验和的主目录及复制副本（跳过 `.installing`
  标记目录），实际运行 Java 验证通过即复用，不再下载/解压/建目录；校验和统一小写，
  不按发布名称认定内容相同；原先按发布包命名的缓存不删除（已保存路径仍可扫描选择，
  但不能推断为某一归档校验和）。列表读取 JAR 内 `gdcc-version.properties` 的
  version/branch/commit，不依赖文件名推断版本；下载的发行包验证 manifest Class-Path
  依赖，本地导入按 gdparser 标志库规则识别依赖。
- 编译器列表：正式版和预览版标签页各按 20 条分页按需加载；"加载更多"是列表末尾的
  特殊项（选中即请求下一页，不作为独立按钮，不可当作可安装版本）。正式版只列非
  draft/prerelease 的 `script-gd/gdcc` Releases，同时安装 JAR 与 `-libs.zip`，追加
  `published_at` 日期；预览版查询 `gdcc-jar-and-libs` Actions artifacts（排除
  expired），显示分支、commit、按当前系统时区偏移转换的本地日期时间（中文用年月日
  格式，字段用 `·` 分隔，不以分支名已用的 `/` 作分隔符，不直接显示 ISO 8601 时间戳）。
  预览包经 `https://nightly.link/script-gd/gdcc/actions/artifacts/<整数ID>.zip` 匿名
  下载（nightly.link 已获仓库 Actions 读取授权；插件不收集 Token，也不依赖本机 gh
  登录）；预览压缩包中的嵌套 libs ZIP 同样解压；界面不显示 nightly.link 下载说明。
  "安装所选版本"和"从本地安装"同行，中间显示"或"，按 2:1 分配按钮空间；该操作行位于
  列表滚动区之外、进度区上方，仅在第二步显示。选择本地 JAR 后要求相邻 `lib/` 中存在
  `gdparser-*.jar`，否则显示错误对话框；满足此标志即认为依赖库已找到，导入时复制
  lib 中的文件，不逐项拒绝 manifest 中其他缺失项。完成配置前仍实际运行
  `java -jar ... --version`，运行校验失败后不保存启动命令。
- 下载与解包：下载保持原始字节、有限重定向，直接流式写入 staging；进度显示已下载
  大小和文件总大小，取消时中止请求/探测进程。哈希、解压和复制在工作线程执行，通过
  插件既有 busy 计数维持帧泵。服务经 `phase_changed` 更新当前阶段（读取发布信息、
  下载、SHA-256 校验、解压、检验解压后的 Java、安装/复制校验、检验安装后的 Java、
  清理、完成），非下载阶段使用不确定进度动画。JRE 必须有 SHA-256；编译器在 GitHub
  提供 digest 时校验，旧资产无 digest 时依赖 HTTPS。显式 Godot Editor HTTP
  代理优先；未配置时读取 `http_proxy`/`https_proxy` 环境变量（小写优先、兼容大写，
  允许 scheme 前缀，忽略认证信息，缺省端口 80）；Windows 上环境变量未覆盖的协议再
  读取当前用户手动代理的 `ProxyEnable`/`ProxyServer`（忽略 PAC/绕过规则）；系统策略
  强制机器级代理、SOCKS 和代理认证不在此接口范围。
  ZIP 解包前检查中央目录的大小/条目数，拒绝路径穿越、加密和不支持的 ZIP64；
  tar.gz 有解压大小、头校验和及条目数上限，支持发行包的 PAX 时间戳和普通文件/目录，
  保留 Unix 执行位；法律声明符号链接在普通文件完成后物化为包内副本，不创建可用于
  逃逸的链接；TAR 字段先按 NUL 字节截断再解码（避免构造 `String.chr(0)` 产生大量
  Unicode 解析诊断）。
- 落盘：编译器验证完成后把 staging 原子移动为缓存，失败不修改项目设置，取消后清理
  staging。JRE 优先复用同校验和的有效目标（含随机后缀副本）；目标不存在时尝试快速
  移动；Windows `MoveFileW` 失败只返回笼统的 `FAILED=1`，因此移动失败或旧目标不可用
  时改用独立随机后缀目录复制完整 JRE，逐文件 SHA-256 校验后再次运行 Java；复制成功
  不要求最后再重命名，直接使用实际可执行路径；旧目标不被移动/覆盖/删除。复制中的
  目录带 `.installing` 标记，扫描时跳过，校验成功才移除标记；失败/取消清理未完成
  目录（含隐藏文件）；仅存在空目录、被占用的旧目标不再阻止安装，也不对不存在的
  java.exe 发起探测。
- 完成配置："完成编译器配置"在 Java 和所选编译器就绪后启用，先验证 Java、JAR 依赖
  和 `java -jar ... --version`，再将本地启动命令写入
  `gdcc/compilation_server/launch_command`、host 设为 loopback，并保存项目；保存失败
  恢复命令、host、port 三项内存设置。Java/JAR 路径另存项目 editor metadata。管理器
  忙时拒绝改写；已连接不再构成拒绝条件。向导先用 `TCPServer.listen(0, "127.0.0.1")`
  预留一个不同于旧端点的本地空闲端口，保存后释放预留；新端点避免 `ensure_running`
  把旧服务误判为可复用的外部服务。随后共享项目管理器显式 retarget 诊断通道（旧
  epoch 失效），异步关闭本会话拥有的旧服务（`stop_owned_async`，§3.7），再经共享
  launcher 拉起新命令；外部旧服务继续运行。端点切换仍使用既有私有模块清理与重新
  同步，不卸载语言或重启 Godot 的 GDScript LSP。旧服务停止失败保留跟踪并记录日志；
  新服务拉起失败保留已保存配置，向导显示明确错误，不误报成功。设置窗口手工修改命令
  仍只影响下次启动。`configured` 保留为对话框成功事件，仅在保存与新服务拉起成功后
  发出，不再由 dock 监听重复拉起。已知边界：端口预留释放到 JVM 绑定之间并非原子
  交接，仍有被无关进程抢占的低概率窗口（§10 R28）；此流程配置的是编译服务器启动
  程序，不擅自修改 `c_compiler_kind`、优化级别或其他构建偏好。

### 3.11 编辑器插件国际化

- 英文是消息原文和未支持语言的回退；`addons/gdcc/locale/zh.po` 提供中文，使用原生
  `Translation` 资源和独立的 `gdcc.editor` 翻译域。`Language: zh` 匹配旧版的 `zh_CN`
  与新版的 `zh_Hans`，其他中文区域也使用此简体中文目录。
- `plugin.gd` 在启用时注册目录，回滚或卸载时移除自己的翻译和域；不向 `godot.editor`
  或游戏主域注入消息，不修改项目的语言、翻译资源设置或编辑器语言设置。`.po` 由核心
  资源加载器直接读取，不依赖导入缓存；插件首次扫描前即可预加载。
- 管理器和 launcher 继承插件的域；驻留 service 显式使用该域，卸载后恢复继承。
  dock 与 toolbar 在 `setup` 中复制管理器的域（添加到编辑器容器后不再位于插件子树
  内）；其子节点（含 PopupPanel、OptionButton 菜单及原生悬浮 tooltip）继承该域。
- 静态标签、选项和 tooltip 保留英文，使用节点自动翻译；状态、相对时间、构建点阵及
  带参数的提示使用 `tr()`。语言变化通知会刷新动态文字，已有日志不回溯翻译。
  管理器的 `_log` 先翻译完整模板、再插入参数，不对参数中的 `%`、路径或外部错误二次
  格式化。
- 翻译范围包含构建菜单、dock、所有插件自有操作日志、launcher 输出及插件/诊断调度器
  警告。技术标识（GDCC、GD3、Zig、设置键、RPC 方法、状态枚举、代码模板）及编译器/
  LSP 返回的原始诊断保留原文。日志 TextEdit 禁用自动翻译，避免再次翻译已格式化消息
  或源码。
- 引擎限制：插件列表中的 `plugin.cfg` description 是插入引擎 tooltip 的原始元数据，
  Godot 4.5–4.7 均不为其调用翻译；保留英文，不通过修改引擎或注入保留域绕过。
- 原生项目设置使用 `EditorPropertyNameProcessor` 与 `godot.properties` 域，不会读取
  插件自有域，也不使用控件自动翻译。`locale/zh_properties.po` 单独提供分类和属性名，
  启用时向现有属性域注册；卸载或回滚只移除这份资源，绝不删除或清空引擎属性域。
  目录使用处理器生成的英文显示名（如 `Compilation Server`、`C Compiler Kind`），不
  包含已有引擎翻译的 `Host`、`Port`、`Sync`，避免覆盖通用属性名。设置名称遵循编辑器
  的"本地化设置"开关；`zh_CN` 与 `zh_Hans` 都匹配此简体目录。Godot 4.6+ 切换语言会
  重载并清空属性目录，因此在翻译变化通知中幂等重加同一资源，再由原生 inspector 刷新
  分类与标签。键名和 `DEBUG`、`RELEASE`、`AUTO`、`ZIG_CC`、`TINY_CC` 等实际存储值保持
  不变，不通过翻译枚举提示修改配置格式。

### 3.12 文件与构建接线

- `EditorAddonProjectInstaller`（`src/test/java/gd/script/gdcc/rpc/`）：递归收集
  `addons/gdcc/**` 全部 `.gd3` 源（含 `util/` 子目录），按 addon 相对路径写入模块
  VFS（`/src/<相对路径>`，displayPath 为 `res://addons/gdcc/<相对路径>`，absolutePath
  为主机绝对路径——供类元数据 `source_res_path`/`source_path`，§7.1）；全部源进同一
  模块以便跨类引用解析。**所有**安装出口（`buildNative`/`buildAllPlatform`/测试安装）
  统一后处理 `gdcc_for_editor.gdextension`，把 `reloadable = true` 改写为 `false`
  （§6）；无动态库产物的安装抛 `IOException`（负例锚定）。
- 解释型 `.gd`（`plugin.gd`、`gdcc_dock.gd`、`gdcc_project_manager.gd`、
  `gdcc_build_toolbar.gd`、`server_launcher.gd`、`gdcc_syntax_highlighter.gd`、
  `gdcc_setup_dialog.gd`）不进编译模块、不上面向世界清单，由 `plugin.gd` `preload`
  直接引用。
- `buildAddonNative` / `buildAddonAllPlatform` Gradle 任务不变。
- 分发入口：`./gradlew buildAddonAllPlatform --args=--package --no-daemon --info
  --console=plain`。独立编译 Windows x86_64、Linux x86_64、Linux AArch64、macOS
  AArch64 四套 **Release** 库，不覆盖源码目录的手工安装；生成
  `build/distributions/gdcc_editor_addon.zip`。四套库以 `stripNativeSymbols=true`
  编译（`-g0` 抑制 zig 在 ELF 目标上默认产生的 DWARF，链接 `-s` 剥离符号表；
  Windows 轮次在链接前清理早期未剥离构建遗留的同名 `.pdb`），仅此分发路径启用，
  其余构建保持默认符号输出。ZIP 仅包含
  `src/editor_addon/addons/gdcc/` 的运行时内容，路径前缀固定为 `addons/gdcc/`；保留
  解释型 `.gd`、插件配置、翻译和图标，排除已编译的 `.gd3`、其 UID、导入缓存、
  `.godot/` 和源码目录原有的 `bin/`。重新生成的 `bin/` 仅来自本次四平台构建，
  `gdcc_for_editor.gdextension` 的八个架构限定 debug/release 键均指向对应 Release 库，
  并维持 `reloadable = false`。不包含 `src/editor_addon` 项目根或其他 addon 的内容。
- CI 在 Windows x64 和 Linux x64 分别运行 editor-addon 测试；Linux x64 的专用任务
  在测试通过后执行上述四平台打包并上传 `gdcc-editor-addon` 工件。`release.yml` 使用
  同一入口，将 `gdcc_editor_addon.zip` 加入工件、完整性检查和 GitHub Release 附件。
- 测试门控：editor-addon 测试以 `@Tag("editor_addon")` 标记、默认排除；
  `-PincludeEditorAddonTest=true` 随全量运行，`-PeditorAddonTestOnly=true` 只跑它们
  （`build.gradle.kts`）。

---

## 4. 关键流程

### 4.1 注册/注销时序

注册（插件 `_enter_tree`，同步顺序；LSP 与 gdcc 模块为异步就绪，不阻塞注册；
plugin.gd 侧的完整步骤清单见 §3.6）：

1. （plugin.gd）常驻节点就位与身份核验（fail-closed、零副作用，**先于任何设置改动**）。
2. （plugin.gd）暂存并设置 `network/language_server/use_thread = true`（LSP 线程模式
   硬前提）。**运行期启用的门禁**：`set_setting` 不发出重启监听所需的
   `NOTIFICATION_EDITOR_SETTINGS_CHANGED`（§2.6），因此门闩不看"是否在扫描"而看
   **翻转瞬间端点是否已在听**（≤150ms 非阻塞探测，不发送数据故不抢占
   `latest_client_id`）：已在听 → 本会话服务端必是主线程轮询，置常驻服务上
   `lsp_blocking_unsafe = true`（进程内粘性，禁用/恢复同样不会重启服务端故不清除）
   并 push_warning 提示重启；未在听 → 服务端启动时会读到翻转值（线程模式），置
   `lsp_thread_primed = true`（粘性，避免禁用/再启用后端口已在听而误闩 unsafe；
   仅当本次 primed 的安装被回滚、设置已还原时才清除）。该会话内 `_validate` 的
   内联等待仅在未标记 unsafe 时进行（诊断仍异步流动，见 §4.2 第 1 步）。
3. （plugin.gd）初始化低功耗 busy 协调器（§3.5；必须先于任何 `ensure_server_hook`
   调用）。
4. `install(...)`（RPC 端点来自**项目级设置** `gdcc/compilation_server/host|port`，
   缺省 127.0.0.1:6099；配置非法时 fail-closed 回退默认值并 push_error，§3.6）：
   首建时创建服务专用 `GdccRpcClient` 子节点（与项目管理客户端队列隔离）；语言实例
   （首建或复用）`Engine.register_script_language(lang)`，返回值非 `OK` 即按 §3.5
   回滚并向上报错（16 语言上限/重名属可预期失败，不得断言了事）；注册 loader/saver
   并以探针资源分别验证后置条件；启动 LSP 带退避重连。`install` 返回非 OK 时恢复
   `use_thread` 原值并报告，后续步骤不再执行。
5. （plugin.gd）`install` 成功后连接 `filesystem_changed` 与 `settings_changed`
   转发（先 `is_connected` 防重，仅连接一次；放弃/失败路径不连接，注销时仅在已连接
   时断开）。
6. 经 `ensure_server_hook`（§3.7）确保 RPC 服务可用（未配置启动命令且服务缺席时
   静默降级，后续心跳失败仍会再经钩子重试）；首个 `ping` 成功后创建私有诊断模块，
   随后初始同步：`DirAccess` 递归遍历项目 `.gd3`（**排除 `gdcc/sync/excluded_globs`
   命中的路径**，§7.4）上传 VFS 并做一次 `analyze.run` 预热缓存。
7. （plugin.gd）仅运行期启用场景：`call_deferred` 触发 `EditorFileSystem.scan()`。

注销（`_exit_tree`）：断开事件连接、释放两个视图、管理器清理占用并排空残余 busy →
`uninstall()`（停防抖调度与在途分析回调 → 断 LSP → 移除 loader/saver → 注销语言
（**不释放**实例）→ 删除私有模块（需服务在线）→ UNINSTALLED）→ 恢复 `use_thread`
原值（脚本 `set_setting` 不会重启引擎 LSP 服务（§2.6）；顺序仍须保持——先断开本
客户端、注销语言，再把设置翻回，且离开前若服务端尚未开始监听需撤销
`lsp_thread_primed`）→ **最后** `launcher.shutdown_owned()`：对拉起的服务发
`server.shutdown` RPC，**收到响应即完成**（优雅收尾由 JVM hook 在后台完成，编辑器
不等待）；仅当 RPC 不可达且记录端点仍在侦听时才 `OS.kill` 兜底（§3.7）。

不在 GDExtension 入口初始化阶段注册的原因：gdcc 生成的入口不向 `.gd3` 暴露初始化
钩子；插件 `_enter_tree` 早于编辑器就绪与用户操作。

### 4.2 诊断合并流程（`_validate(script, path, ...)`）

入参 `script` 即编辑缓冲区当前文本（无 0xFFFF 哨兵）。全程单线程（主线程），
`_in_validate` 守卫防重入；服务 UNINSTALLED 时直接返回
`{"valid": true, "functions": PackedStringArray()}`。

1. LSP READY：URI 为 `res://` → `file:///` 服务端方言（§2.6）；`didOpen`（首次）或
   `didChange`（全量文本，generation 递增，水位规则见 §3.4）；随后**有界同步等待
   ≤150ms** 收取该 URI 当前 generation 的 `publishDiagnostics`（仅线程模式下有效，
   §2.6）；超时则用该 URI 的最近缓存诊断。**READY 但非线程模式**（运行期启用翻转，
   §4.1 第 2 步）：同步仍发送（诊断异步进缓存、下一校验节拍浮现），仅跳过内联
   等待——主线程轮询的服务端会被内联等待饿死。
2. LSP 诊断映射（0-based → 1-based，行与列均 +1）：
   - `severity == 1` → `errors[]`：`{line, column, message, path: <当前 res:// 路径>}`
     （`path` 必填，否则编辑器把错误归入"依赖脚本错误"面板）。
   - `severity == 2` → `warnings[]`：`{start_line, end_line, code: 0,
     string_code: "GDSCRIPT_LSP", message}`（五键齐全，缺键会被引擎丢弃；LSP 的
     `code` 可能是字符串，不作 int 转换）。
   - 其余 severity 丢弃。
   - 映射 error 前过滤已证实的代理类型身份误报：仅当本次 `lsp_safe_text` 通过完整
     擦除准入（§7.2），消息中的两端类型恰为当前 `.gd3` 路径与该视图返回的
     `class_name`，才丢弃声明初始化、普通赋值、返回值和实参不兼容诊断（支持两端
     互换）。这是同一源文件在 LSP 中被拆成匿名脚本类与 ClassDB 编译类造成的限制，
     不按 `.gd3` 子串或类型错误类别整体过滤。未擦除、来源不匹配、其他类/基础类型/
     容器类型仍照常显示；原始 LSP 缓存不变，gdcc 诊断也不参与过滤。被过滤项不触发
     第 3 步的级联抑制门禁。
3. gdcc 通道：**先** `version := notify_source_changed(path, script)`（每次校验都执行，
   包括 LSP 有错误时——LSP 错误只抑制 gdcc 诊断的**显示**，不抑制标脏与缓存失效），
   再读 `gdcc_diagnostics_for(path, version)`：**过期可见**——缓存中有条目即合并
   （编辑已升版时其行列指向旧文本，直到新一轮合并替换，§4.4），按 `severity` 拆入
   `errors`/`warnings`（`string_code: <category>`，`message` 前缀
   `[gdcc <category>]`；`sourcePath`/`range` 为空的条目按文件级诊断处理：行 1 列 1）。
   若 LSP 有错误，即使缓存新鲜也不叠加（避免级联噪音）。缓存仅在诊断模块 READY 时
   提供——离线降级让 `_validate` 只剩 LSP 通道而不是旧 gdcc 缓存。
4. `validate_functions=true` 时登记大纲需求并读取大纲缓存填入 `functions`（§4.7；
   该路径不发送网络请求）。
5. 返回 `{"valid": errors.is_empty(), "errors": ..., "warnings": ..., "functions":
   functions}`（`validate_functions=false` 时 `functions` 为空数组）。
6. LSP 不可用（DEGRADED）：跳过第 1–2 步，仅 gdcc 通道（gdcc 前端本身覆盖 GDScript
   语法解析，降级仍有意义）。

### 4.3 补全流程（`_complete_code(code, path, owner)`）

前置：服务 ACTIVE、LSP READY 且服务端线程模式；否则返回降级字典。

1. `idx := code.find(String.chr(0xFFFF))`；`idx < 0` → 返回
   `{"result": ERR_UNAVAILABLE, "force": false, "call_hint": "", "options": []}`。
2. 由 `idx` 计算 0-based `(line, character)`（码点计数），剔除哨兵得纯净文本，
   经 `lsp_safe_text` 擦除视图与前向映射（§7.2）后先同步文档再
   `request_blocking("textDocument/completion", ...)`，预算 ≤400ms。补全成功后复用
   同一已同步文档与位置追加 `request_blocking("textDocument/signatureHelp", ...)`，
   预算 ≤200ms；签名查询失败、超时或无结果只产生空 `call_hint`，不丢弃已成功的补全
   应答。
3. 结果映射（LSP `CompletionItem` → 引擎 option）：
   - `display := item.label`；
     `insert_text := item.textEdit.newText ?? item.insertText ?? label`。
   - `kind` 映射：Class→0，Method/Function/Constructor→1，Event→2(SIGNAL)，
     Variable/Field→3，Property→4(MEMBER)，Enum→5，Constant/EnumMember/Value→6，
     File/Folder→8(FILE_PATH)，Snippet→7(NODE_PATH)（4.5 服务端把节点路径选项发为
     Snippet=15，见 `gdscript_text_document.cpp` 的 kind 映射；其余→9(PLAIN_TEXT)。
     4.5 无 KEYWORD kind。4.5 服务端实际发出的 kind 集合经核实为 Text=1 / Method=2 /
     Variable=6 / Class=7 / Property=10 / Enum=13 / Snippet=15 / File=17 / Constant=21 /
     Event=23；Function/Constructor/Field/EnumMember/Value 分支为规格容忍兜底。）
   - `font_color := Color(1, 1, 1)`，`icon := null`，`default_value := null`，
     `location := 1024`（`LOCATION_OTHER`）。
4. `call_hint` 映射：读取 SignatureHelp 的 `signatures[activeSignature].label`，
   按引号与括号深度拆分顶层参数列表，选中 `activeParameter` 对应的完整参数片段
   （含类型与默认值），在其两侧插入成对 `String.chr(0xFFFF)`；编辑器绘制时移除该
   标记并对当前参数做下划线/高亮。无可用签名、无参数或越界且无 vararg 槽时返回
   原 label 或空串，不视为补全失败。
5. 返回 `{"result": OK, "force": false, "call_hint": call_hint, "options": options}`；
   任何补全失败返回第 1 步的降级字典。**引擎事实**：类体内纯空行不产生任何补全选项
   （`GDScriptParser.make_completion_context` 仅在光标落在某个 token 上时才赋上下文，
   纯空行保持 `COMPLETION_NONE`，`complete_code` 直接返回空列表——与 `.gd` 行为逐字
   一致）；关键字补全需要标识符前缀上下文（`COMPLETION_IDENTIFIER` →
   `_find_identifiers` 注入关键字表）。空选项列表是合法的成功应答，不得坍缩为降级
   形状。

### 4.4 gdcc 诊断调度（全异步）

- **不引入任何主线程阻塞 RPC 通道**（阻塞 `HTTPClient` 无法在 UI 线程保证硬截止）。
  一律走 `GdccRpcClient` 的异步挂起对象队列，且使用服务**专用**的客户端实例
  （§3.5）：与项目管理器的客户端分属两条 HTTP 连接与超时配置，Build 期间其
  `compile.getTask` 进度轮询不被整模块 `analyze.run` 阻塞，诊断分析也不被构建操作
  饿死。
- 标脏与防抖：`_validate` 第 3 步标脏；服务在 `process_frame` 累积计时，自上次变更
  满 800ms 且无在途分析时发射：按序执行 `put_file`（VFS 映射合同见 §3.5）+
  `analyze.run(analyze_include_lowering)`。
- **单飞以服务端完成为准**：在途期间不发射新请求（新变更只更新 registry 的脏标记）。
  flight
  记录整模块版本戳（版本和 + 路径数），完成时不匹配即整轮丢弃并立即重飞——逐文件
  版本门禁不足：flight 中途任一源变化（含快照外文件）时，未变文件仍会并入按旧模块
  状态算出的诊断。
- **合并写回**：响应到达后按版本戳比对，匹配则回填缓存——合并写回
  是唯一清除/替换途径。`analyze.run` 返回 RPC 成功但 `outcome != COMPLETED`（如元数据
  加载失败）不当干净轮合并：仅 COMPLETED 合并并清脏；失败轮按次数退避，连续 5 次后
  挂起调度直至下一次真实变更（push_warning 告知）；置"整模块重分析"标记时同步解除
  挂起并清零失败计数。
- **读取侧过期可见**：缓存条目在新鲜轮合并前持续返回（行列指向编辑前文本，属刻意
  取舍，与 Godot 自身"新结果到达才替换"的行为一致）。根因：编辑器每次校验节拍用
  `_validate` 的返回值**整体替换**该页签的错误显示
  （`ScriptTextEditor._validate_script`：`errors.clear()` 后按新结果重建），若按内容
  版本门控读取，`notify_source_changed` 先升版会使同一次 `_validate` 读到空缓存，
  防抖+分析窗口内既有错误被整体清空。`is_version_current` 保持严格新鲜度语义，供
  主动重校验门（§4.5）与测试观测。
- **删除与墓碑**：删除一律落墓碑（`_deleted_paths`），`notify_source_changed` 对墓碑
  路径且磁盘不存在时返回 -1，墓碑在文件重新出现时解除（解除即恢复 `_disk_known`）；
  flight 上传前再查一次墓碑关闭"删除与对账之间"的窗口；flight 的墓碑守卫只清脏标记
  与缓存、保留 `_sources` 并触发对账。删除判定只认 `_disk_known`（"已镜像"不是"曾在
  盘"的证据：已 flight 上传的纯缓冲区文件从未落盘，不得误判为删除）。对账中出现删除
  即置"整模块重分析"——幸存文件的诊断（如消失的被继承 `class_name`）需要刷新。
  `vfs.deletePath` 仅 ok/-32005 视为删除成功，其余失败走 outage——恢复时
  `module.create` 命中 -32001 重建路径自然清空僵尸。
- **outage/重定向/卸载**一律清空诊断缓存（旧结果可能在恢复时已过时），恢复后由重
  镜像+重分析重建；outage 保留模块创建标记（仅重定向/实际删除才清除），瞬时故障后
  卸载不会跳过模块删除造成孤儿；outage 恢复时把"不在盘、非已知、非墓碑、未镜像、
  未脏"的 `_sources` 条目补标脏，由下一 flight 重传（纯缓冲区文件不丢失）。
- **setup 状态机**（lifecycle 子节点）：hook-first——模块未就绪时每个断供窗口先走
  `ensure_server_hook`（launcher 自带 ≤200ms TCP 快探，实证 `HTTPRequest` 对拒连端口
  会挂满 30s 请求超时）再 ping；hook 在途期间不发 ping。hook 全局单飞
  （`_hook_sent == _hook_done` 才允许新 hook），30s hook 超时按失败语义放弃窗口并
  合成缺失的完成计数；teardown（outage/重定向/卸载）只清在途标记，迟到应答由
  session 守卫丢弃。setup ping/create/残留删除共用失败计数，达 5 次上限即结论化
  释放 busy（计数清零只在 hook 失败/进入就绪/通道重置；hook 成功不清零也不强制
  未结论化——"TCP 可连但 RPC 永失败"循环必须能触到上限）。同窗口第二次 -32001 改走
  共享退避上限（矛盾服务器可致"删除成功→立即重建"紧密循环）。hook 成功时置回未
  结论化——launcher 的 busy 与服务的 busy 在回调内重叠交接，ping→create→首轮对账
  全程有帧；无钩子时 outage 直接结论化、静默降级。hook 失败即重置"已尝试"标记，
  重试周期始终先走 launcher 快探。
- **busy 交接边界**：服务缺席期间两次快速探测之间的退避不占 busy（完全空闲的编辑器
  里到期的重试在下一帧用户活动时执行），符合静默降级；"杀进程后无任何输入也能自动
  拉起"不作承诺——心跳检测本身依赖帧。心跳在纯空闲时暂停：killed 服务的发现延迟到
  下一次编辑器活动；期间 `_validate` 可能短暂提供 kill 前的缓存（与 §10 R17 的节拍
  级延迟同类）。不以心跳新鲜度门控缓存读取——健康服务下空闲数秒就隐藏既有诊断更差。
- **主动重校验**：合并完成记录"哪些 (path, version) 首次变为缓存可读"，由 §4.5 通道
  触发当前页签重校验；在此之前新诊断随编辑器下一次校验节拍（再编辑的 idle 超时、
  页签切换、外部重载）自然浮现。
- 与编译任务的关系：`analyze.run` 在服务端与 compile 串行；Build 期间诊断只是变旧，
  不会卡死编辑器（异步）。
- 服务器离线：静默降级（只记录日志），`_validate` 仅剩 LSP 通道。

### 4.5 主动重校验通道

背景：异步分析完成后没有任何公开 API 让编辑器重跑 `_validate`（§2.5），且"打字→
停顿→再打字"节奏下校验节拍与可读版本可能永远错开。本通道经 `CodeTextEditor` 的注册
信号 `validate_script` 主动触发（接线事实与"编译代码泵内直发外来信号不送达"的缺陷
实证见 §2.5——中继是必要形态，不是风格选择）：

1. scheduler 合并分析结果时记录本轮"哪些 (path, version) 首次变为缓存可读"（写入前
   缓存未服务该版本即计入——诊断内容不变但版本前进也必须计入，此前的校验可能因版本
   不匹配而显示空结果）。
2. service 帧泵 `_pump_revalidation` 做**初筛**：命中路径是**当前页签** + registry
   版本一致 + **缓冲区文本与分析文本逐字一致**（registry 版本只在校验时前进，用户
   键入后合并前会有滞后，文本比对兜住这一拍）+ 补全弹窗未开（弹窗判定用
   `CodeEdit.get_code_completion_options()` 非空，与 4.5 的绘制条件
   `code_completion_active && !options.is_empty()` 逐字等价；弹窗期间暂缓入队、关闭后
   次帧补发）。门禁通过后发射**自有**信号 `revalidation_requested(path, version)`；
   初筛与发射在同一帧内原子完成。
3. 解释型 `plugin.gd` 收信号后先 `call_deferred` 跳出泵栈，再经
   `should_still_revalidate(path, version)` **复核**全部门禁（一帧间用户可能又键入或
   打开弹窗；outage/retarget 后的缓存新鲜度/就绪度也在复核内；弹窗刚开则重新入队而非
   丢弃），通过后对当前页签的 `CodeTextEditor` 发射 `validate_script`。
4. 无反馈循环：相同文本的重校验不会重新标脏。
5. gdcc 缓存新鲜门禁对"纯 LSP 重同步"场景保持不变：gdcc 通道离线时，擦除决策翻转后
   的显示刷新依赖编辑器自身节拍（诊断数据面已在重同步后更新）。

观测锚定：合并检出后有界帧数内语言 `_validate` 调用计数前进（引擎测试
`diag_revalidate`）。idle 时序事实：clean→error 的 idle 间隔 1.5s、**晚于** 0.8s
防抖（`set_error_count` 切到 0.5s 只在错误已显示之后）；已有错误时 idle 为 0.5s、
严格早于合并周期（§2.5）。

### 4.6 符号查找（`_lookup_code`）

- **版本门槛**：Godot >= 4.6.0 转发 `hover`/`definition`；更早版本返回
  `{"result": ERR_UNAVAILABLE, "type": 0}` 且不同步文档、不发送查询（`request_lookup`
  原始传输入口同样拒绝旧版本，防止绕过门槛）。原因：Godot
  [PR #109345](https://github.com/godotengine/godot/pull/109345)（4.6）将
  `GDScriptCache.get_shallow_script` 的 `set_path(path, true)` 改为
  `set_path_cache(path)`，浅层脚本不再替换全局 `ResourceCache` 中的原脚本；4.5.1 的
  浅层 GDScript 会接管 `.gd3` 资源路径。4.8 开发版
  [PR #120487](https://github.com/godotengine/godot/pull/120487) 进一步把 lookup 结果
  改为路径表示，并移除结果构造中的浅层脚本获取（保留 `ScriptLanguageExtension` 适配）。
  该门槛独立于脚本创建对话框的 `> 4.6.0` 限制（§2.1）。客户端继续用
  `languageId: "gdscript"` 打开 `.gd3`；这不是正式的自定义语言支持，也不是非
  GDScript 文档过滤带来的修复。
- **形态**：无状态子节点 `GdccLookupService`（全量同步 + hover/definition 各 200ms
  有界阻塞；hover 先答——悬浮是更高频路径；definition 腿失败降级为 hover-only，
  hover 腿失败整体降级）。服务根 `request_lookup` 门面做前置守卫（ACTIVE + LSP
  READY + 线程模式，与补全同款）。哨兵定位（4.5 全部编辑器调用路径均经
  `get_text_with_cursor_char`/`get_text_for_symbol_lookup` 携带 0xFFFF 哨兵；无哨兵时
  整词回退、跳过注释尾）、0 基码点行列计算、擦除视图前向映射与 LSP → 引擎
  LookupResult 字典映射都集中在 `GdccLookupService.lookup_code`；语言入口仅做服务
  可用性检查和委托。
- **OK 判定收紧**：description 为空**且** definition 不可导航（目标不出项目、无有效
  起始行、或跨文件 Script 加载失败）时一律降级——不允许"无提示也不可跳转"的假成功。
  返回范围的列不做擦除反向映射（LookupResult 契约只消费行号——`_lookup_symbol`/
  `goto_line_centered` 以 `location - 1` 跳行，无任何列消费路径；擦除两种形态均不
  改变行数）。`location` 为 1 基；跨文件目标必须附 `script` 资源，否则引擎会在当前
  页签内跳行。
- **符号分类**（4.5 引擎事实：适配器强制 `result` 与 `type` 两键，缺一即
  ERR_UNAVAILABLE；`LookupResultType` 枚举 SCRIPT_LOCATION=0…LOCAL_VARIABLE=10）：
  hover/definition 本身不提供结构化符号 kind，统一标 LOCAL_VARIABLE 会把类/函数等错误
  显示为局部变量。故 hover/definition 后最多追加一次有界符号检查：项目定义用
  `documentSymbol` 的 selectionRange 精确分类，原生符号用 `nativeSymbol` 分类；类、
  方法、属性、常量、枚举与信号按真实类型返回。分类失败保留有效导航
  （SCRIPT_LOCATION），不误标局部变量；无法确认的脚本符号不伪造分类。单次 lookup
  最多三个各 200ms 的 LSP 请求。LookupResult 无构造函数、运算符与主题项种类，这些
  符号引用所属类型的帮助，而非错误的方法/属性分区。信号大纲缺失参数类型时不编造
  类型字段。原生属性访问器被上游 LSP 文档表省略时，仅对当前脚本根函数的未限定方法
  调用做 ClassDB 回退（`_native_method_fallback`），不猜测任意接收者类型；原生符号
  检查失败时可从带原生所有者的 hover 声明恢复帮助引用。
- **文档**：局部变量/常量通过 `GdccMarkdownToBbcode` 转换 hover Markdown；脚本成员从
  大纲读取原始 BBCode，发布到 ScriptExtension 文档与编辑器帮助缓存。CLASS_* tooltip
  从帮助缓存读取文档，忽略 LookupResult.description；LOCAL_* 才消费该字段。文档名
  优先使用目标脚本原始源码中的 `class_name`，内部类使用 `Outer.Inner`；无声明类名时
  才回退为带引号的项目相对路径。命名脚本文档以源文档身份覆盖同名编译扩展的帮助条目
  （成员显示类名并保留源码导航）；lookup 时重新发布命名条目，避免扩展文档重新生成后
  误走原生帮助导航；旧命名条目的清理按已发布快照执行。类名读取不依赖已擦除
  `class_name` 的 LSP 同步视图：同文件使用请求携带的原始缓冲区；跨文件优先使用源码
  注册表的存活文本，再回退为目标资源源码（避免未保存的 `class_name` 改名仍显示旧
  名字）；不采用墓碑条目。未命名脚本的相同文档不重复更新帮助缓存；命名脚本每次
  lookup 都恢复源文档帮助条目。`nativeSymbol` 为 Godot 内置 LSP 扩展方法，其响应保留
  原始 BBCode；原生符号直接引用引擎帮助。`_get_documentation` 是按需 LSP 大纲缓存，
  不在资源加载或文档虚函数内发网络请求。
- **跨文件边界**：跨文件解析**只**经 path-based extends 工作——4.5 服务器的全局类名
  解析读引擎 ScriptServer 表，`.gd3` 类不入表（该注册属暂缓的 Stage C，§9）；extends
  路径经解析器依赖加载解析，由 workspace 镜像（§7.3）的 didOpen 供数。
- **资源身份**：lookup 不得改变当前页签脚本的资源身份与保存路径（`lookup_suspended`
  回归覆盖启用与禁用分支，含真实编辑器符号信号后原资源身份、缓存路径与保存往返）。

### 4.7 成员大纲（`documentSymbol`）

- 编辑器左侧成员大纲来自 `validate(..., r_functions)`（§2.5）。`_validate` 在
  `validate_functions=true` 时仅通过 `GdccEditorService.outline_functions(path,
  source)` 读取缓存并登记需求，**不在该路径发送网络请求**。
- `GdccOutlineService` 子节点在服务帧泵中同步所需 `lsp_safe_text` 擦除视图并异步请求
  `textDocument/documentSymbol`，按 §2.5 的原生规则扁平化为
  `functions: PackedStringArray`：使用声明 `range.start.line + 1`；擦除保持行数不变；
  顶层不加脚本类名前缀，内部类递归限定；仅递归类节点，不进入方法、变量或属性子树
  （不会把局部 lambda 或 getter/setter 混入大纲）。
- 请求键包含路径、源码内容版本、连接 READY 代次及擦除视图；同键只发一次，空列表也
  作为已完成结果缓存。新源码/擦除决策使旧请求失效；断线清除可见缓存，重连重新请求；
  删除、墓碑、关闭的排除路径及卸载取消并清理相关请求。模块诊断服务不可用不影响大纲。
  响应消费前重新检查版本和视图；同文本的诊断/镜像同步可能推进 LSP 文档版本，不因此
  丢弃仍对应相同视图的结果。等待更新时保留上次成功的大纲；失败/超时不重复请求同键，
  内容、擦除视图或连接代次改变后才重试。
- 传输纪律：`GdccLspClient.request_async/take_response/cancel_request` 使用已有帧驱动
  收包；读取挂起状态不轮询 socket，也不阻塞。大纲请求超时 2s，仅移除该 id，迟到响应
  忽略，不断开连接。每帧最多发起两个查询；只有在途/未处理请求及待刷新工作持有忙
  标记。异步查询无 `use_thread` 前提，非线程服务端随正常帧推进即可回答；诊断路径的
  150ms 有界等待及非线程降级逻辑保持独立，不为大纲额外等待。
- 新结果经服务自有信号 `outline_revalidation_requested` → 解释型插件延迟发射
  `validate_script` 刷新实际大纲（与 §4.5 同一中继形态）；发送前和延迟回调均检查
  当前页签、实际缓冲区、缓存新鲜度，弹出补全时延后。该门禁独立于 gdcc 诊断缓存的
  READY/版本门禁，纯 LSP 会话也会主动刷新。重验证只读已完成缓存，不重新发起同键
  请求。离线、禁用或不支持的路径返回空列表；关闭 `validate_functions` 不登记新需求；
  错误仍令 `valid=false`，不为强制刷新大纲而隐藏诊断。`safe_lines` 仍不提供。
- 本查询**不**沿用 lookup 的 4.6 门槛：4.5.1 的 `documentSymbol`
  （[`gdscript_text_document.cpp:L170-L180`](https://github.com/godotengine/godot/blob/4.5.1-stable/modules/gdscript/language_server/gdscript_text_document.cpp#L170-L180)）
  仅从工作区现有解析缓存读取并序列化符号，不经过 hover/definition 的浅层脚本加载；
  同步引起的分析与既有诊断通道相同。

### 4.8 自动缩进与脚本模板

- `_auto_indent_code`：**逐字镜像** 4.5 GDScript 参考算法
  （`GDScriptLanguage::auto_indent_code`，gdscript_editor.cpp）：嵌套完全由前行非空
  非注释行的前导空白宽度栈推导（不看冒号/括号）；`from_line` 起各行重写为
  缩进单元×栈深 + 去空白内容；空行与 `#` 注释行逐字节透传且不入栈；退阶落在两个栈
  宽度之间时按引擎"not right but gets the job done"怪癖压为新一级；`from_line >
  to_line` 病态区间触发引擎提前 break 怪癖（输出恒等）。缩进单元经服务门面
  `editor_indent_unit()` 镜像 `GDScriptLanguage::_get_indentation`（`indent/type` 选
  Spaces 时按 `indent/size` 重复空格，否则单个制表符；未安装时恒为制表符——引擎非
  编辑器分支）。契约级约束：适配器把虚函数返回**无条件**写回缓冲区，实现缺失/失败
  会清空用户代码，故实现必须是全函数。
- `_make_template(template, class_name, base_class_name)`：复用语言工厂返回已关联语言
  的有效脚本；空模板生成 `class_name <类名>\nextends <基类>\n`，类名由无扩展名的文件名
  转 PascalCase 并规范为合法标识符（镜像 Godot 原生 identifier sanitizer：
  非标识起始字符补 `_`，非法字符逐字替换为 `_`）。非空模板替换 `_BASE_`、`_CLASS_`、
  `_CLASS_SNAKE_CASE_`、`_TS_`，类名占位符按 Godot 规则转换，缩进使用编辑器设置，
  不额外插入 `class_name`。原生创建对话框即使关闭模板也调用 `_make_template`，因此
  不能返回 null，否则引擎在设置脚本路径时空指针崩溃。`_get_built_in_templates` 返回
  空列表，`_is_using_templates` 为 false。"创建脚本"对话框的 GD3 项仅在 Godot >
  4.6.0 时可选（§2.1）；旧版本 `.gd3` 新建入口仍为 dock 按钮或在文件面板外创建。

### 4.9 编译工作流与 Build 部署

**形态**：dock/标题栏的 Build 从 `module.copy` 复制私有诊断模块得到一致快照开始，
编译副本，部署动态库到项目 `bin/` 并生成/更新 `bin/gdcc.gdextension`，经引擎扫描
持久化并加载/重载。方案取舍（调研结论）：第二模块持续同步 = 双份流量 + 双套生命
周期；直编诊断模块 = 编译持门冻结诊断（≤120s）且打破所有权隔离；**服务端模块复制
+ 编译副本**零额外同步流量、诊断不冻结、所有权隔离不破、编译输入为显式一致快照——
采纳。服务端可行性：`ModuleState` 无分析缓存（analyze/compile 每次自冻结源码快照
重建），模块 = VFS 树 + CompileOptions + 顶层类名映射 + 运行态；VFS 目录节点可变、
叶子节点不可变（内存 String），复制 = 新根 + 递归新目录节点 + 共享叶子，纯内存操作。

**服务端 `module.copy`**（合同见 `json_rpc_service_implementation.md` 与
`rpc_api_implementation.md`）：`API.copyModule(sourceModuleId, newModuleId,
newModuleName?)` 持有**源模块门**完成整份快照（VFS + options + 类名映射一次性一致；
经 `runExclusive` 排在在途操作之后——禁止多次 RPC 读拼快照的混合时间态）；复制 VFS
（新目录节点、共享不可变叶子并保留 displayPath/absolutePath/updatedAt）、
CompileOptions、顶层类名映射、moduleName；**不复制运行态**（lastCompileResult = null、
输出发布清零、任务槽空闲）；`freezeCompileRequest` 按 moduleId 派生逻辑路径，副本是
全新 `ModuleState`。错误：目标已存在 -32001（先快速失败再等待源门）、源缺失 -32000、
源忙按既有门语义等待（不新增忙失败）。可选 `newModuleName` 只覆盖副本的
`moduleName`，诊断模块仍保持 PID 作用域隔离；旧调用缺省时仍继承源名称；服务端忽略
覆盖字段、回显名称不符时中止并提示升级。

**Build 流程**（管理器执行）：

1. 校验端点字段与服务 `diag_channel_status()` 的有效端点一致（防止对错误服务器按 id
   复制/删除）→ 从服务取诊断模块 id 并就绪校验。
2. 经管理器自有 RPC 客户端（与诊断流量分队列）`module.copy(诊断id, 副本id)` →
   `options.set` 重写 `projectPath`（`res://.godot/gdcc/<副本id>/<端点hash>`，与诊断
   模块的宿主目录隔离）→ `compile.start` → 轮询。
3. 再次编译命中 -32001 → 仅当服务端 owner token 属于本管理器且本地记录证明副本由本
   管理器创建时，删除旧副本后重新复制（永远编译最新快照）；-32002（旧副本编译仍在
   进行，如上次轮询超时）→ 取消本管理器发起的旧任务并等待终态后重试删除一次。
4. 旧服务端无 `module.copy`（-32601）时报错提示升级服务端，不回退有损的手动上传路径。

**身份与目录**：

- 按钮每次从 `application/config/name` 读取并 trim 项目名称，空白时提示设置名称而不
  启动构建；即使在诊断 setup 后修改项目名，新构建仍使用当前名称。
- 编译副本 id 固定为 `gdcc_editor_compile_<安全化项目名前32字符>_<hash>`。安全化使用
  Godot `String.validate_filename()`，再将 U+0000–U+001F 和 U+007F 控制字符替换为
  `_`（引擎的 filename 校验本身不处理这些字符）；hash 为
  `ProjectSettings.globalize_path("res://") + "\n" + trim 后项目名` 的 SHA-256 前 16
  个十六进制字符，区分同名不同路径、非法字符替换碰撞及长名称截断碰撞。不含 PID，
  相同项目路径和名称在重启编辑器后仍复用 `.godot/gdcc/<副本id>` 身份目录和后端产物名。
  项目改名或移动路径会产生新身份；不自动删除旧 PID 目录或其他身份的历史产物。
- 实际构建路径为 `.godot/gdcc/<副本id>/<端点hash>`，端点 hash 为 `host + "\n" + port`
  的 SHA-256 前 16 字符，不含 PID 或任务 id。相同服务端点跨会话复用子目录；切换端点
  或其他编辑器使用不同服务器时分开生成 C、对象和原始库，不自动删除旧端点目录。原因：
  占用模块仅在单个服务内互斥，后端构建锁也仅限同一 JVM，且生成 C 发生在原生编译锁
  之前；上一次轮询超时的旧服务可能仍在写入。
- 同一服务上同一项目身份只允许一个项目管理器拥有构建模块：先用既有 `module.create`
  原子创建 `<副本id>_owner`，其 `moduleName` 为每个项目管理器随机生成的 token，该
  占用模块不编译、无磁盘产物。重复构建先核对服务端 token，再替换自己已创建的快照；
  其他 token、无占用但已存在的未知副本均拒绝接管，不删除/取消他人的模块或任务。
  正常空闲卸载核对 token 后先删副本，确认成功/不存在后再删占用；服务器重启后本地
  记录不能证明所有权。异常退出、忙副本、网络清理失败或在途请求卸载保留占用，提示
  关闭占用编辑器或重启 gdcc 服务恢复。这替代旧的多编辑器独立构建目录约定；诊断/
  补全仍为每个编辑器独立通道，不修改 API/RPC 协议。副本按端点逐条记录（端点可在两次
  编译间重定向）；插件空闲卸载时对每条记录用**阻塞 HTTPClient**（绕过已离树的帧泵
  客户端）核对服务器上的所有权后做有界删除。
- 已打开的被排除插件脚本仍会临时进入诊断快照（§7.4）；若成功结果的 `sourcePaths`
  包含 `res://addons/gdcc/`，拒绝部署并提示关闭这些页签或恢复同步排除规则，避免
  项目库重复注册驻留编辑器扩展的类。诊断本身仍允许检查这些打开的脚本。

**部署与启用**：

- C 和内部编译产物留在独立 `.godot/gdcc/<副本id>/<端点hash>`。只从**当前终态任务的**
  `CompileResult.artifacts` 选择目标平台对应的唯一动态库，不查询后续 lastResult，
  不扫描旧构建目录猜产物；缺库、多个库、库在编辑器机器上不可访问时部署失败。
  RPC 服务必须能提供编辑器可读的本地/共享文件路径，尚无跨机器二进制下载协议。
- 库部署到 `res://bin/[lib]<安全化项目名>_<平台>_<debug|release>_<架构>-<hash>.<后缀>`，
  hash 是库内容 SHA-256 的前 16 个十六进制字符。不同内容发布到新路径，避免 macOS
  dyld 的同路径映像缓存，以及覆盖被编辑器/游戏进程映射的库；相同内容复用已校验的
  路径，不重复换库（与后端 hot_reload 文档的换库合同一致）。平台和架构来自结果冻结
  的 CompileOptions，而非当前 OS 猜测；Godot 标签转换为 `AARCH64 → arm64`、
  `RISCV64 → rv64`。只有动态库进入 `bin`，生成 C、对象文件及辅助调试文件保留在内部
  构建目录。
- 描述文件固定为 `res://bin/gdcc.gdextension`，避免项目改名留下多个启用同组类的
  描述文件。`entry_symbol = "gdextension_entry"`、`compatibility_minimum = "4.5"`、
  `reloadable = true`；`gdcc_managed = true` 标记插件管理的文件，拒绝覆盖未标记或
  无法解析的已有文件。用 ConfigFile 正确转义路径并保留其他目标条目。
  `[libraries]` 只生成 `<platform>.<architecture>`（例如 `windows.x86_64`），不使用
  debug/release 标签或模式别名；同一目标的后续构建覆盖该条目，因此 Debug 编辑器也
  加载本次选择的 Release 库。库文件名仍保留优化级别，描述文件键与编译优化选项分离。
  已托管描述文件中的旧 `<platform>.<debug|release>.<architecture>` 条目合并为新键；
  其他目标优先保留已有新键，没有新键时优先选择当前编辑器原来匹配的模式，缺少该模式
  则保留已有库路径。当前构建目标始终改指本次产物，删除所有被迁移的模式条目，避免
  Godot 的更具体标签匹配盖过新键。旧 `gdcc_library_aliases` 校验类型后移除，不再生成。
  `gdcc_libraries` 继续以 PackedStringArray 记录插件拥有的库文件；该字段及存在的旧
  别名字段若类型非法，在文件发布前拒绝。重载比较迁移前实际匹配的旧模式路径或新键
  路径与本次路径，不能因旧 Debug 路径保留而跳过 Release 重载。此合同仅作用于 addon
  的项目 Build；CLI 和 `gdcc_for_editor` 安装器的独立元数据生成合同不变。
- 等待现有编辑器扫描结束，再暂存库并通过 rename 发布到内容哈希路径，不直接截断
  可能被映射的库文件；描述文件使用 ConfigFile.save，不使用 Windows 会先删除旧目标的
  rename。成功启用后仅清理 `res://bin` 直接目录中、不再被任何目标条目引用的插件库，
  不通过目录穿越路径删除项目文件；删除被游戏进程锁定的旧库失败只记日志，留待后续
  构建重试。所有文件操作检查错误，失败不报告启用成功。编译失败/取消不部署；无效
  结果或缺产物不改动上次安装。文件发布不是跨两个文件的事务；发布或引擎重载失败会
  明确记日志，不能把"编译 SUCCESS"当作"已启用"。
- 编译协程结束后延迟发起 `EditorFileSystem.scan()`，由 `filesystem_changed` 回调确认
  主线程扫描动作已完成，让 Godot `ensure_extensions_loaded()` 发现描述文件、加载并
  写入 `.godot/extension_list.cfg`。**不能先手动 load 再 scan**：manager 已有此扩展时
  扫描可能看不到成员变化，从而不写加载清单，下一次运行又失效。插件不直接改写此引擎
  维护文件。`is_scanning() == false` 不足以确认扫描完成：Godot worker 会在主线程
  join 和扩展对账前清除此标志；定时器重试被在途 worker 拒绝的 scan，只在完成信号后
  检查描述文件已被资源树识别。首次扩展成员变化还会触发引擎全脚本软重载，取消解释型
  脚本的悬挂协程，因此构建收尾不能 await 此扫描；按钮在回调结束/超时前保持禁用。
- 首次扫描后检查 manager 已加载；重建时调用 `reload_extension` 并检查 LoadStatus。
  引擎加载/重载失败或扫描超时提示检查引擎错误、重启编辑器；非本机目标只报告已安装，
  不把它当成本机已启用。Godot 单扩展 reload 的 `extension_loaded` 信号由既有类名
  擦除服务处理（§7.2）；无需伪造 batch `extensions_reloaded` 信号。项目库的
  reloadable 合同不改变 `gdcc_for_editor` 必须 `reloadable = false` 的 ScriptServer
  安全规则（§6）。
- 测试驱动细节：观察副本注册**不能用 `module.get`/`read_file`**——它们走模块门，副本
  编译期间会阻塞；用 `module.list` 观察注册，内容校验移到编译结束后。引擎测试驱动在
  真实构建模式将自身脚本资源置为无路径，避免被 Godot 按资源路径收集的全脚本软重载
  取消；被测插件与 dock 保留正常资源路径，仍经过真实引擎扫描/重载。

**引擎实现依据（Godot 4.5）**：

- `core/extension/gdextension_library_loader.cpp`：配置解析、feature tags 最具体匹配、
  相对库路径与修改时间检测。
- `core/extension/gdextension_manager.cpp`：`ensure_extensions_loaded` 的清单更新条件、
  `reload_extension` 状态与单扩展信号。
- `editor/file_system/editor_file_system.cpp`：`scan` / `_scan_extensions`，扫描完成信号
  在扩展成员对账之后；scan 本身不等于二进制重载。
- [Godot 4.5.1 Windows 库加载](https://github.com/godotengine/godot/blob/4.5.1-stable/platform/windows/os_windows.cpp)：
  编辑器通过 `~<库名>` 临时副本加载 DLL；
  [macOS](https://github.com/godotengine/godot/blob/4.5.1-stable/platform/macos/os_macos.mm) 和
  [Unix](https://github.com/godotengine/godot/blob/4.5.1-stable/drivers/unix/os_unix.cpp)
  直接 `dlopen` 原库，因此固定内部构建身份不等于取消 `bin` 的内容哈希路径。
  Godot 不扫描或加载 `.godot/gdcc` 中的库，仅发布到 `bin` 后经引擎启用。

**非目标**：不引入第二个持续同步模块；编译仍为显式手动动作（不自动触发）。

---

## 5. gdcc 自举约束

编译型 `.gd3` 文件遵守 `gdcc_rpc_client.gd3` 已钉死的全部约束：禁止 `await`/协程、
禁止 lambda 与 `Callable.bind`、引擎对象局部变量显式标注类型、`JSON.parse_string`
结果显式守卫、公开 API 不返回协程（异步 RPC 沿用 `PendingRequest` 挂起对象；
LSP 的同步等待是紧凑 `poll` 循环，不是协程）。

安装服务为经原生验收覆盖的例外：`gdcc_install_service.gd3` 可使用信号、lambda 与
`await`；公开异步方法返回 `Variant`，解释型向导通过 `await_result` 区分同步结果与带
`completed` 信号的挂起状态（§3.10）。该例外不放开语言服务、RPC 客户端的同步边界，
也不扩大 HRX 的多线程支持面。

拆分重构期间新增的实证边界（分析门禁 + 原生编译双重验证）：

- 自定义类**带参构造**不可用：`class X ...; X.new(args)` 被前端
  `sema.call_resolution`/`sema.type_check` 拒绝（仅支持零参构造），用零参
  `new()` + `setup(...)` 方法替代。
- **方法引用 Callable 不保留接收者（与官方语义一致）**：`obj.method` 前端零诊断、
  同作用域调用正常，跨作用域（接收者仅靠 Callable）调用报 `null::method`。对照实证
  （4.5.1 官方解释器隔离探针）：官方的标准 Callable（糖式**和**显式构造
  `Callable(obj, method)`）同样只存 `(ObjectID, StringName)`、**不保活任何接收者**
  （`callable.cpp` 构造函数实证），报错措辞与我们完全一致；唯一保活的是 lambda
  捕获（`GDScriptLambdaSelfCallable` 持 `Ref<RefCounted>`），而 lambda 恰在 .gd3
  禁用清单内。故糖式路径我们与官方**逐行为对齐**，非缺陷。显式构造
  `Callable(obj, &"method")` 对自定义类接收者在前端放行、C 后端曾经报
  `ExtensionBuiltinClass` 无此构造；该拒绝已解除（固定调用参数的对象向上转换物化），
  且明确**不保留**接收者（与官方语义一致）。最小复现/锚定：
  `CustomReceiverCallableGapTest`（糖式+显式的同作用域对照、跨作用域不保活特征化、
  codegen 接受锚定）。
- **编译类不能继承编辑器专有基类**（实证）：GDExtension 类注册发生在
  `GDEXTENSION_INITIALIZATION_SCENE` 级（后端 `entry.c.ftl` 固定），而
  `EditorSyntaxHighlighter` 等编辑器类要等 `register_editor_types()`（EDITOR 级）才进
  ClassDB——编译产物注册 `extends EditorSyntaxHighlighter` 的类时引擎报
  `non-existing parent class`（gdextension.cpp `_register_extension_class_internal`），
  属失败但其余类照常注册，运行时表现为该类不存在。结论：需要编辑器专有基类的功能
  （如高亮器）只能用解释型 `.gd` 实现（与 `plugin.gd`/`gdcc_dock.gd` 同类）；
  后端若今后支持按模块选择 EDITOR 级注册，可再迁回编译目标。
- 方法引用 Callable 目前仅对 `self`（Node）可靠——但即使后端补齐保留/构造，标准
  Callable 按官方语义也**不应**保活接收者，令牌式 hook 归属依旧不可行；共享字段/
  计数器 + 单飞不变量是与引擎语义一致的正确设计。

自举能力探针（`src/test/resources/editor_addon_probe/*.gd3`，不进插件目录；
`EditorAddonIntegrationProbeTest` 为门禁，任一级别失败即先补 gdcc 能力）：

- **P0-A 编译探针**：`analyze(includeLowering=true)` 要求 `COMPLETED` 且无 ERROR。
  覆盖：虚函数覆盖（ScriptLanguageExtension/ScriptExtension/ResourceFormatLoader/
  ResourceFormatSaver 各核心签名）、`_init` 语义、跨类引用（字段/`as`/实例化）、
  注册 API（`Engine.register_script_language` 等）、资源 API
  （`add/remove_resource_format_loader/saver`、`get_cached_ref`、`take_over_path`、
  线程加载三件套）、网络 API（`StreamPeerTCP` 全套含 `get_partial_data` 数组解包）、
  文件 API（`FileAccess`/`DirAccess`）、文本 API（`String.find(0xFFFF 哨兵)`、`substr`、
  分行、`unicode_at`、`PackedInt32Array`、`Color`）、时间/系统（`Time.get_ticks_msec`、
  `OS.get_process_id`）、编辑器类型标注（`EditorInterface`）、`is_instance_valid`。
- **P0-B native 编译探针**（Zig 门控）：对探针模块执行完整 `compile`，断言产物生成。
- **P0-C 运行时冒烟**（`GODOT_BIN` 门控，非编辑器 `-s` SceneTree 驱动）：加载探针
  扩展，断言 `ClassDB.class_exists` 命中探针类、
  `Engine.register_script_language(ProbeLang.new())` 返回 `OK`、虚函数经
  `Engine.get_script_language(i)` 取回后可经绑定方法调用返回预期值。

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

## 7. 特性合同

### 7.1 编译期类元数据（`_gdcc_get_metadata`）

背景：运行时设施（及 §7.2 的擦除准入）需要按类查询"该类由本项目编译而来"的来源
证明。所需事实（源路径、类名、模块、版本）在编译期全部可得，故**不提供调用方自定义
元数据入口**——元数据完全由编译器自动生成，`gdcc.format` 版本号为未来演进（如调试器
行号表）预留。

- 每个编译类生成合成静态方法 `static func _gdcc_get_metadata() -> Dictionary`：C 实现
  返回从内嵌 JSON 字符串字面量**惰性解析**（解析一次后缓存）的 Dictionary，且返回
  **深拷贝**（载荷自身含嵌套 `gdcc` 子字典，浅拷贝共享子表会让调用方污染共享缓存）。
  运行时经 `ClassDB.class_call_static(name, "_gdcc_get_metadata")` 读取。内部类同样
  逐类生成（注册名即 canonical `Outer__sub__Inner`），每个内部类携带自己的元数据。
- 运行时 Dictionary 形状（全部键由编译器生成，收进 `gdcc` 子字典）：

  ```gdscript
  {
    "gdcc": {
      "format": 1,                            # 元数据格式版本
      "source_res_path": "res://src/test2.gd3",  # displayPath 为 res:// 时产出
      "source_path": "E:/.../src/test2.gd3",  # 绝对路径已知时产出（见下）
      "source_name": "Test2.Inner",           # 源级名（嵌套类用点号路径）
      "module": "game",
      "compiled_at": "2026-09-27T12:34:56Z",
      "version": "<gdcc 版本>",
    }
  }
  ```
- 编译期事实来源（全部自动，无调用方输入）：
  - `source_res_path` ← 源快照 `displayPath`（复用诊断的 logical→display 重映射通道，
    `DiagnosticSourcePathRemapper` 同款；仅当形如 `res://` 时产出该键）；
  - `source_path` ← 源快照可选 `absolutePath` 字段（`vfs.putFile` 的可选参数，向后
    兼容；addon 上传时填 `ProjectSettings.globalize_path(path)`，CLI 填规范化绝对输入
    路径；缺省时省略该键）。**消费方自行 globalize `source_res_path` 的兜底已删除**——
    res 标签无法证明同项目来源（同类名+同 res 相对路径的第三方扩展会误判），缺省即
    不擦除（§7.2）；
  - `source_name` ← 骨架 relation：前端骨架构建期把点号源级名直接写入
    `LirClassDef.sourceClassName`（顶层 = relation `sourceName`，嵌套沿 inner 链拼点号），
    codegen 只读该字段；LIR XML 装载的类无此字段，回退 canonical 名（仅测试夹具路径，
    生产不经 XML）；
  - `module` / `compiled_at` / `version` ← 编译上下文。
  plumbing：`vfs.putFile` 的可选 `absolutePath` 经 `RpcParams`/API/`ModuleState`/
  `VfsNode`/`SourceSnapshot`/`CompileTaskRunner.SourceSnapshot` 透传，codegen 经
  `CodegenContext.sourceFileFacts`（键 = 与 `LirClassDef.sourceFile` 同规约的 logical
  路径文本，两侧统一走 `FrontendDiagnostic.sourcePathText` /
  `DiagnosticSourcePathRemapper.logicalPathKey`）取回；`entry.c.ftl` 逐类生成；JSON→C
  转义复用 `StringUtil.escapeStringLiteral`。
- 合成时机在 `CCodegen.prepare`（`CGenHelper` 收集绑定形状之前），且幂等：管线夹具
  会对同一 module 二次 prepare，重复合成会触发 `validateFileScopeSymbolsDisjoint`
  冲突（已加 `hasFunction` 守卫）。`compiled_at` 使同一模块两次编译的 entry.c 不再
  逐字节相等——名称稳定性断言归一化该字段后比较（该断言的本意是符号名稳定，时间戳
  是唯一已知变动字段）。
- `_gdcc_` 前缀保留：前端语义分析拒绝用户声明 `_gdcc_` 前缀成员（落入既有 member 级
  `RESERVED_PREFIXES`，与类级 `_gdcc_coro_state_` 保留前缀同族——类名合同）；合成
  符号纳入 `validateFileScopeSymbolsDisjoint()`。边界：const/enum 不纳入拒绝——
  `RESERVED_PREFIXES` 机制对 const/enum 本就同样不覆盖（与 `_field_*` 同边界）；
  const/enum 不产生 ClassDB 方法或 C 文件级符号，与合成访问器无碰撞面。
- 插件侧通道边界：**ClassDB 枚举 + 元数据探测只服务已编译类**的身份与来源证明
  （§7.2 擦除、未来调试器）——初次编译前它天然为空，不承担"项目里有哪些类"的发现；
  枚举 `ClassDB.get_class_list()` + 探测 `class_has_method(name, "_gdcc_get_metadata")`
  即得 `源文件 ↔ 注册类名` 映射（自描述含嵌套类），备忘后随
  `GDExtensionManager.extensions_reloaded` 失效。未编译类清单属编译器分析面事实
  （骨架 relation 已含 sourceName/canonicalName/unit 路径），若 Stage C 需要，从同一
  analyze 管线收割（§9 Stage B 大纲的最小子集）。注：配置 canonical 重命名后注册名
  ≠ 源类名，撞名根本不发生——擦除仅在默认无映射配置下需要。
- C 侧调用卫生：静态访问器体内失败分支销毁已构造的 `result` Variant（引擎
  `gdextension_object_method_bind_call` 对 r_return 总是 placement 构造）；兄弟路径
  （`godot_fixed_binding.c` 的 `godot_Object_call`、`engine_method_binds.h.ftl` 的
  call helper、`gdcc_helper.h` 的 `godot_Variant_call`）同规约——错误分支先
  `godot_Variant_destroy`，成功路径在 unpack/consume 之后销毁，每路恰销毁一次。
  字面量由编译器生成且经测试验证可解析，惰性解析失败闩锁是有意选择（失败重试无
  收益）；编辑器消费路径在主线程，竞争代价最多一次重复解析。

### 7.2 `class_name` 条件擦除（LSP 同步视图）

背景：编译安装扩展后 `class_name Test2` 与 ClassDB 扩展类同名，GDScript analyzer 报
error 级 `Class "X" hides a native class.`（`gdscript_analyzer.cpp:394-403`——
`push_error` 而非 warning，`@warning_ignore` 与项目设置均不可抑制），既污染显示又
触发 `_validate` 的 LSP-error 抑制门禁（§4.2 第 3 步）压制 gdcc 诊断。已否决方案：
仅在显示端过滤 `hides a native class`（服务端视图仍被污染——标识符解析 ClassDB
优先于 ScriptServer，`gdscript_analyzer.cpp:4530`，补全仍解析到滞后的编译类）；
全项目合成改名（跨文件成员解析对非 `.gd` 脚本类走 `ResourceLoader.load` + Script
资源内省——`gdscript_analyzer.cpp:787-801`、`gdscript_editor.cpp:1266-1333`——不读
LSP 同步文本，中心赌注不成立；另需令牌级改写、逐行映射表、诊断消息回译，且合成名经
`_get_global_class_name` 注册会污染创建对话框且无干净隐藏手段）。

实施形态：

1. 统一入口 `GdccEditorService.lsp_safe_text(path, text)`：`_validate` 同步、补全同步
   与镜像发送共用；只改 LSP 视图，磁盘内容与 gdcc 分析输入不变。逻辑落在子节点
   `GdccClassNameEraser`（hub-and-spoke，§3.5）：视图变换/双向映射/扫描器/准入探测/
   备忘/三个 GDExtensionManager 信号监听全在子节点；service 保留
   `lsp_safe_text`/`lsp_safe_forward_position`/`lsp_safe_reverse_column`/
   `erasure_memo_size` 四个 facade 委托与翻转的编辑器侧重同步
   `resync_current_document_lsp()`（依赖编辑器上下文/registry/LSP/重校验队列）；
   卸载延迟重同步经 `_on_diag_frame` 在 `_pump_revalidation` 前调用子节点
   `process_deferred_resync()`。
2. 准入四条件（全部满足才擦除）：本文件声明 `class_name X`（行首无缩进、首个匹配）；
   `ClassDB.class_exists(X)`；`ClassDB.class_get_api_type(X)` ∈ {API_EXTENSION,
   API_EDITOR_EXTENSION}；`ClassDB.class_call_static(X, "_gdcc_get_metadata")` 的
   `gdcc` 命名空间 `source_path` 绝对路径与本文件一致（§7.1 产物；无元数据 → 不擦除，
   安全默认；同时排除非 gdcc 编译的第三方扩展撞名）。附加校验：
   `ClassDB.get_parent_class(X)` 与声明基类一致。撞核心/编辑器原生类
   （API_CORE/API_EDITOR，如 `class_name Node`）不擦除——错误保留以促改名。准入的
   路径比较双侧归一化为 `/` 形态（Windows 下 globalize 与编译期绝对路径的反斜杠
   分歧）。
3. 两种行形式：独立行 → 整行等长空白（行列零漂移）；`class_name X extends Y` 同行 →
   删除 `class_name X ` 前缀、保留 `extends Y`（仅该行尾部列漂移；helper 返回双向
   映射：补全/查找请求位置正映射、返回诊断列反映射；映射随诊断缓存按代存储）。
   同行形式的行尾部为**顺序解析**（名称 → 可选图标字符串（转义感知跳过）→ 位置锚定
   的 extends），不做全文自由搜索（注释/图标字符串内的 `extends` 不会误当基类）。
   独立 `extends` 行空基类/纯注释尾标 `base_resolvable = false`（中途编辑的未完成
   声明不误准入）；`extends ,` 空 token 同样标 unresolvable。
4. 扫描器为行级迷你词法器（无 RegEx 依赖）：字符串状态携带（分隔符, 是否三引号）
   二元组，理解转义对、`#` 注释、`"""`/`'''` 两种三引号（闭合需未转义三连）、
   **普通字符串跨物理行**（4.5 tokenizer 的字符串读取循环从不把换行当终止符）。
   已知残余为词法近似的极端角落（§10 R25）；根治待编译器引用区间输出（评估项）。
5. 决策翻转：监听 `GDExtensionManager` 的 `extension_loaded` / `extension_unloading`
   / `extensions_reloaded` 三个信号 → 失效按类名的备忘缓存；unloading（类集合变化
   **前**触发）只清备忘不重同步（类消失后伪错误不可能存在），loaded/reloaded 清备忘
   + 重同步当前文档 + 触发 §4.5 的重校验。白盒可观察性：`erasure_memo_size()` 与
   `GdccLspClient.document_version_for` / `debug_sent_text`（卸载后发送的是原文、
   加载后是擦除视图）。
6. 性能：C 侧元数据惰性解析缓存（§7.1）；addon 按类名备忘、重载信号失效。
7. 擦除视图携带通过准入的 `class_name`（未擦除时为空），供 §4.2 精确过滤同一类型的
   路径/原生类身份误报。例如 `var a: Test2 = self` 的路径类型不兼容诊断不会显示；
   `int` 赋给 `Test2` 等真实错误不受影响。这不替代服务端视图修复：`hides a native
   class` 不得仅在显示端过滤。
8. 已知残余：超时回退的旧代诊断按**当前**视图反映射，擦除形态在两代间变化时擦除行
   的列可能瞬偏（成立需超时 ∧ 形态变化 ∧ 擦除行有诊断同时命中；"陈旧诊断位置引用
   旧文本"是 `_validate` 既有合同 stale-beats-none 的同类边界；根治需按代绑定视图，
   记入 §10 R25）。

### 7.3 LSP workspace 镜像

背景：GDScript LSP 的 workspace 磁盘扫描只收 `.gd`（`gdscript_workspace.cpp` 的
`list_script_files`），`.gd3` 仅经客户端 didOpen 进入解析缓存；该缓存服务于
`resolve_symbol`（hover/定义跳转），**不**服务补全成员枚举（补全走 ClassDB/Script
资源内省，§9 Stage C 条目），故本特性收益限于跨文件 hover/定义跳转的数据面。

形态：常驻子节点 `GdccWorkspaceMirror`（`gdcc_workspace_mirror.gd3`），服务根接线
（install 构建块、`service_tick` 于 scheduler 之后、`busy_wanted` 聚合项、
`notify_filesystem_changed` 同源转发、`service_shutdown`、访问器）。镜像**不依赖**
reconciler 的磁盘采用结果——reconciler 的采用被模块就绪门控，无 gdcc 服务时
registry 永远学不到未打开文件，而跨文件 hover/定义必须在无 gdcc 服务时可用；故镜像
自行做事件驱动磁盘扫描（复用 reconciler 的遍历函数 `project_gd3_files()`），纯磁盘
路径直接读盘且永不标脏。

镜像语义：

- LSP READY epoch 变化即全量重放（清 `_mirrored` 与发送队列并重扫）；文本经 §7.2
  `lsp_safe_text` 在**发送时**求值（决策翻转不会带出陈旧视图）；registry 路径按内容
  版本逐帧比对（无 syscall），纯磁盘路径仅在事件驱动扫描时比对文本。
- 每帧 8 条发送预算（`MAX_SYNC_OPS_PER_TICK`），批量同步不饿死其他帧工作；发送失败
  清空队列由 epoch 重放接管（不重复入队）。
- 待发送队列为 FIFO（队头索引 + 摊销压缩）+ 字典结构（批量建队 O(1) 去重）；sync
  意图**不携带文本**——出队时经 `_resolve_current_text` 重解析（`_disk_seen` 已消失
  且通过关闭决策的路径直接判失、registry 优先、磁盘兜底、双双缺失即丢弃）；close
  意图发送时同样重判（文件已复现或保留规则成立则改排 sync）；排队期间的新编辑/
  删除/复建/页签开合永远不会带出陈旧内容、复活已删文件或误关已恢复文件。
- 镜像自维护 `_disk_seen` 磁盘来源与 `_disk_texts` 末次磁盘文本（registry 的
  disk-known 被模块门控、dirty 语义是"待分析"而非"未保存编辑"，均不可用作判据）。
  关闭决策统一走 `_should_close`：**任一已打开页签持有该路径**（服务门面
  `is_path_open_in_editor`，页签自己的 `_validate` 会持续刷新 LSP 副本）、registry
  文本与末次磁盘文本**确实不一致**（真正的未保存编辑）、或镜像从未在磁盘见过该路径
  （纯缓冲区新文件）时保留，其余删除一律 didClose。
- 共享客户端被其他功能外部关闭的镜像文档经逐帧 `document_version_for` 核对自愈重开；
  `busy_wanted` 计入未处理的 READY epoch，且客户端 `ready_advanced` 信号在 READY
  跃迁沿即时触发 `refresh_busy`（低功耗窗口内重放不被饿死）。
- 与 tombstone 的交错：逐帧 tombstone 分支先查磁盘存在性，存在即落入正常 heal/漂移
  流程（避免"close 入队、发送时改 sync、下一帧再入队"的每帧全量 didChange 循环）。
- 与排除 glob 的交互见 §7.4（排除路径恒 didClose）。
- 已知边界：每次扫描事件在单帧内完成目录遍历与已镜像纯磁盘文件的文本比对——典型
  项目可接受；超大项目的可续跑遍历与分帧读盘留待后续评估（§10 R30）。

### 7.4 同步排除 glob 与临时纳入

**设置**：项目设置 `gdcc/sync/excluded_globs`（`PackedStringArray`，默认
`["addons/gdcc/*"]`；plugin.gd 注册属性与初始值，名称与默认值由常驻服务单源
（`EXCLUDED_GLOBS_SETTING` / `default_excluded_globs()`））。匹配语义：对 `res://`
相对路径做 `String.match`；`*` 跨 `/`（Godot 语义），故默认 glob 覆盖 addon 全部
嵌套文件；**无通配符的目录名同时排除其子树**（判词额外尝试 `<glob>/*`）。服务端
4.5 实证：`ProjectSettings.set_setting` 经 `_set` 延迟合帧发射 `settings_changed`
（4.5 无 `get_changed_settings`，插件无法按 key 过滤，故全量转发、由对账合并吸收）。

作用于三处入口，保持"诊断模块 VFS 与 LSP 镜像对项目 `.gd3` 集合认知一致"的不变式：

1. `GdccFileReconciler._scan_gd3_files`（诊断对账与 LSP 镜像共用的唯一递归扫描）逐
   文件过滤；**不做目录剪枝**——按探针路径剪枝会被精确文件 glob 误伤（如
   `addons/gdcc/x` 会连带隐藏 `addons/gdcc/other.gd3`）。
2. `GdccSourceRegistry.notify_source_changed` **不拒绝**排除路径——`_validate` 只在
   文件被编辑器打开（存在页签）时被调用，故 notify 即"已打开"信号：**打开的排除
   文件被临时纳入模块**（其缓冲区即内容真源，正常走 标脏→上传→分析→诊断 链路）。
3. `GdccFileReconciler` 对账删除分支增加**临时纳入保留规则**：排除路径在页签打开
   期间保留（disk-known 路径磁盘删除仍优先，与既有"删除胜缓冲区"一致）；另设
   500ms 节流的关闭检查（遍历已跟踪路径中"排除且无页签"者）标记对账，页签关闭后
   下一趟对账自动将其移出模块（本地除名 + 服务端删除；未落盘路径不置墓碑——墓碑
   只随真实磁盘删除，否则取消排除后永不复纳）。**恢复回路**（镜像重建后的重传）
   的门卫是 `vanished` 而非 `is_disk_known`：排除的 disk-known 临时纳入文件不在磁盘
   扫描集合内，旧门卫会让它在模块重建后永远不再上传。
4. `GdccWorkspaceMirror` 维持排除（不随临时纳入改变）：`_should_close` 对排除路径恒
   true（已镜像的排除路径 didClose，即使页签打开——该页签自身的 `_validate` 同步
   会独立维持其单文件 LSP 文档）；`_resolve_current_text` 对排除路径返回
   not-found（发送时重估原则：设置变更前入队的旧 sync 意图不得把刚排除的内容发
   出去）；drain 的 close→sync 复活分支以排除优先于"文件仍存在"（否则排除路径
   的 sync 解析为 not-found 会重新入队 close，两个转换分支均不耗预算，主线程
   死循环）。

**生效与时效**：判词每次调用现读设置（无缓存）；plugin.gd 将
`ProjectSettings.settings_changed` 转发为对账触发，设置变更即刻生效——新排除的已
同步**且已关闭**的路径按"删除"对账出模块（本地除名 + 服务端 `vfs.deletePath`；
disk-known 路径置墓碑防复活），页签仍打开的排除路径保留（临时纳入），移出排除后
下一趟对账经墓碑复活重新上传。两功能共享的是**磁盘扫描集合**；诊断模块额外持有
临时纳入的打开缓冲区，LSP 镜像不随临时纳入改变。

**边界**：排除路径的磁盘内容永不入模块；打开页签临时纳入（gdcc 通道诊断可用），
关闭即移出。LSP 通道：打开页签的 `_validate` 每页签同步与排除无关，诊断始终可用；
镜像层面排除停止的是**自动镜像**，不构成跨文件索引隔离：Godot 4.5 的 GDScript LSP
服务端 `didClose` 为空实现，此前已解析的内容在服务端缓存中可能残留。镜像侧
didClose 的意义是停止客户端后续的 didChange 维护流量并释放客户端簿记。

**曾暴露的前端缺陷（已修）**：临时纳入的文件若引用模块外类型（如 addon 文件引用
其他 addon 类），`includeLowering=true` 的分析曾在 CFG lowering 的
`requireLoweringReadyCall` 不变式上抛异常——根因是 `propagateStep` 只为动态链的
**属性**后缀发布 fact，**调用**后缀无 fact（前端 chain-reduction 合同的对称性缺口，
任何 `x.foo().bar()` 动态链式调用都可触发，与排除功能无关）。已在前端补齐动态调用
后缀的 DYNAMIC fact 发布（见
`frontend_chain_binding_expr_type_implementation.md`），回归锚点
`FrontendLoweringUnresolvedTypeFallbackTest` + 链绑定焦点测试。此类文件现在正常分析
并给出 `sema.type_resolution` 警告，诊断通道不再因此进入 outage。

---

## 8. 测试策略与回归锚点

### 8.1 静态合同测试（无环境依赖）

- `EditorAddonScriptLanguageAnalysisTest`：与 `EditorAddonClientAnalysisTest` 同构——
  逐文件 `analyze(includeLowering=true)`、无 ERROR、无 `await` 等禁用构造；
  `plugin.cfg` 五字段不变；解释型 plugin/dock/项目管理器/构建工具栏/launcher/高亮器/
  安装向导仅语法解析，不作为编译目标；断言所有安装产物 `reloadable = false`；无动态
  库产物的安装抛 `IOException`（负例）。
- `EditorAddonClientAnalysisTest`：客户端库的编译就绪门禁（挂起对象 API、帧泵纪律），
  插件清单与 i18n 目录锚点（`chineseCatalogCoversEditorMessagesAndPreservesFormatArguments`
  检查用户可见消息的目录覆盖、英文原文、重复键和格式参数顺序；
  `settingsCatalogContainsOnlyAddonPropertyNames` 保护设置名称专用目录边界）。
- `EditorAddonDiagnosticsFixtureTest`：Java 侧钉死引擎测试所用 fixture 的前端诊断事实
  ——路径式 `extends` 报 `sema.class_skeleton`（ERROR，锚定 `class_name` 行）；
  显式类型普通 get-node 初始化器（`@warning_ignore_start/_restore("get_node_default_without_onready")`
  区域标注包裹 `var camera: Node = $Camera3D`；gdcc 忽略区域标注故不产生额外诊断）
  报 `sema.deferred_expression_resolution`
  （WARNING）+ `sema.compile_check`（ERROR）配对（5 行 20 列）且 `includeLowering=false`
  时后者不出现；显式类型 `@onready var camera: Node = $Camera3D` 为正例（lowering
  成功且无诊断）；两 fixture 同模块单轮齐报；同名文件经 displayPath 独立键控。
- 服务端/后端锚点：`RpcServerShutdownTest`（`server.shutdown` 三层时序合同，§2.8）、
  `ApiModuleCopyTest`（复制保真、运行态重置、-32001/-32000、源忙门等待、副本编译、
  源续写不污染副本）、`JsonRpcDispatcherTest`（路由数与错误码）、`RpcJsonCodecTest`
  （wire 形状含 `absolutePath` 可选绑定）、`ApiVirtualFileSystemTest`（空白
  `absolutePath` -32602 负例）、`RpcCompileHttpIntegrationTest`（absolutePath-only
  上传的 RPC 级锚定）、`RpcApiRoundTripHttpTest`（copy 工作流）、
  `CClassMetadataSupportTest`（元数据 JSON 全键形状、转义矩阵经"渲染→C 反转义→JSON
  解析"逐字往返、惰性解析+缓存+深拷贝）、`ApiCompileClassMetadataTest`（管线级：逐类
  恰一个访问器、JSON 与 putFile 事实一致、静态绑定形状、无 absolutePath 的重传保留
  既有 provenance）、`CCodegenEngineMethodBindHeaderTest`（名称稳定性断言归一化
  `compiled_at`）、`CCodegenTest.moduleDisplayNameIsEscapedInEntryMessages`（C 字符串
  边界）、`FrontendClassSkeletonTest` 的 `_gdcc_` 保留前缀用例、
  `CustomReceiverCallableGapTest`（§5 Callable 边界）。

### 8.2 引擎集成测试（`GODOT_BIN` + Zig 门控，Assumptions 跳过）

harness：复制 `src/editor_addon` 到 `tmp/test/editor_addon_script_language/<case>/`
时排除 `.godot/` 和项目根 `bin/`，避免手动 Build 的游戏扩展在首次扫描中触发额外
脚本重载。启动前清空副本的 `gdcc/compilation_server/launch_command`，由用例配置自己的
端点和拉起命令，不能继承源工程的相对 jar 路径。安装编译产物（复用
`EditorAddonBootstrapEngineTest` 模式），**额外注入一个仅测试用的解释型驱动插件**
`addons/gdcc_test_driver/`（`plugin.cfg` + `driver_plugin.gd`，GDScript，不随插件
发布）：编辑器就绪后驱动等待 `GdccEditorService` 就绪与 LSP 监听，逐项断言并逐行
输出 `GD3_TEST_RESULT: ...`，最后 `get_tree().quit(exit_code)`。启动命令：
`godot --headless --editor --path <copy> --quit-after <N>`，其中 N 为纯兜底的大帧数
（如 100000；正常退出由驱动 `quit()` 触发），进程级超时 `destroyForcibly()` 沿用
现有模式。不使用 `-s EditorScript`：`-s` 要求 `MainLoop`/`SceneTree` 入口，与
EditorScript 不兼容；驱动插件路径同时保证 EditorPlugin 加载顺序与真实使用一致。
子进程配置目录经 `APPDATA`/`XDG_CONFIG_HOME`/`HOME` 重定向隔离，EditorSettings 写入
不污染真实用户配置。

harness 实施期事实：

- Java 侧逐行匹配 `GD3_TEST_RESULT: `；RPC 服务端 `127.0.0.1:0` 起停沿用现有模式，
  临时端口由驱动插件在服务节点入树后、首个 `ping` 前注入（再调一次
  `install(..., rpc_host, rpc_port)`，或直接写服务下固定名 `GdccEditorRpcClient`
  子节点的 `host`/`port` 字段；§3.5 已要求每次 `install` 写回端点，两条路径等价）。
- 拉起功能用例反向操作：Java 侧只选定空闲端口而不启动服务，由驱动插件把项目设置
  `gdcc/compilation_server/host|port|launch_command` 写好后触发连接（harness 项目
  天然隔离）；拉起命令为 `java -cp <测试 classpath> gd.script.gdcc.Main serve`
  （自包含，不依赖预构建 jar），占位符替换路径与生产一致。
- 携带 `lsp_port` 的用例由 harness 注入 `--lsp-port` CLI 覆盖（引擎支持且优先于
  EditorSettings），服务端端口在插件代码运行前钉死（脚本改设置不重启服务，§2.6）。
- 停机坪端点用**永远应答 HTTP 500 的黑洞服务器**——实证 Godot `HTTPRequest` 对连接
  被拒端口会挂起至 30s 请求超时，快速失败使钩子路径可被确定性断言。
- 引擎测试驱动 `DRIVER_PLUGIN` 文本超过 Java 单一字符串字面量 64KB class 文件上限：
  拆为多个方法返回的文本块运行时拼接（方法调用非常量表达式，避免编译期折叠回原
  限制）。
- `dock_compile` 用例副本中删除工程自带的故意错误样例 `src/test2.gd3`（其前端错误会
  阻断整工作区编译），删除需先等初始同步波把它传上去再等删除落库（竞态防护）；
  `--quit-after` 帧数上限按用例可配置（真实原生构建耗时远超交互用例）。

`EditorAddonScriptLanguageEngineTest` 当前用例（每个用例对应 `EXPECTED_STEPS` 键）：

| 用例 | 覆盖 |
|---|---|
| `language` | 语言注册、加载/保存/重读/worker 线程加载往返、`_validate` valid、禁用/启用循环中语言实例同一性 |
| `create_resource` | 模拟 CreateDialog→InspectorDock 序列（ClassDB 实例化 + 属性枚举 + 保存 + `edit_resource`），禁用态同路径不崩溃（§2.3 自愈回退） |
| `launch` / `launch_bad` / `launch_none` / `launch_replace`（两个测试方法） | 冷启动自动拉起、退出关闭；不存在可执行文件的两种平台分叉报告；无命令被动失败；向导替换已拥有服务/保留外部服务 |
| `lsp_connect` | 退避重连吸收启动窗口；强制断开重连与禁用/启用循环后重新握手（READY epoch 递增）；循环后内联等待仍可用 |
| `lsp_validate` | 降级路径 valid+空、错误样例行号与 `path` 键、警告五键、非 BMP emoji 行/列锚定、禁用态安全应答 |
| `lsp_fifo` | 背靠背发送按 FIFO 归属（per-generation 绑定历史）；等待超时不清队列；`request_blocking` 往返 |
| `lsp_runtime_enable` | gdcc 启动时禁用 → 运行期启用 → 阻塞被拒（标记 + 快速返回）且异步诊断浮现 |
| `gdcc_diag` | 路径式 extends 浮现 `[gdcc ...]` 前缀错误；WARNING/ERROR 配对映射；同名文件独立键控；过期可见；LSP 错误抑制 gdcc 叠加；删除/重命名对账（服务端 -32005 证据）；墓碑与整模块重分析；-32001 残留重建；HTTP 500 黑洞经 hook 恢复；busy 上报与排空；hook 单飞/超时/失败上限 |
| `diag_revalidate` | 合并检出后无输入重校验（`_validate` 计数窗口）；同诊断新版本必刷；同版本重并不发；非当前页签不发；弹窗暂缓与关闭补发；端点 retarget（项目设置驱动）与状态区 |
| `lsp_completion` | 降级形状、kind/insert 文本白盒映射、`call_hint` 0xFFFF 对标记、关键字（标识符前缀触发）、空上下文合法空结果、成员补全、CJK 码点锚、无哨兵降级、FIFO 按代绑定不受补全流量污染、禁用态安全、循环后 `_validate` 健康 |
| `class_name_erasure` | 元数据读回（含嵌套类与缓存隔离）、准入矩阵、两种行形式改写与双向映射、CJK、陷阱组（注释/字符串/多行串/跨行串/转义闭合/空基类/空 token）、伪错误消失与 gdcc 诊断浮现、决策翻转（备忘清空 + LSP 文档版本前进 + 重校验）、真实 `unload_extension`/`load_extension` 双向翻转、擦除后大纲行号 |
| `lsp_workspace` | 未就绪不镜像、初始同步进服务端文档集、缓冲区优先于磁盘、磁盘变更刷新、删除 didClose、tombstone+复建静默窗口、40 文件批量同步期间 `_validate` 逐次 < 400ms、断连重连全量重放 |
| `lsp_outline` | 映射/过滤/行号/标志/降级/错误/空脚本、真实 Members Overview 与点击跳转、未保存刷新、纯缓存读取、同键去重、过期响应、超时不断线、空结果缓存、非线程查询、删除/排除清理、重连重请求 |
| `lsp_lookup`（Godot >= 4.6.0） | 同步拒绝降级、白盒（MarkupContent/MarkedString/Location 各形、URI 方言往返、整词回退边界）、同文件方法、path-extends 跨文件、class_name 跨文件降级（负例）、CJK 锚、未知符号负例（三段式：文档已解析 + 双腿 ok+空结果 + 语言降级）、无哨兵回退、禁用态安全、符号分类（`lookup_markup`/`lookup_classification`：原生/内建/脚本成员/局部变量/遮蔽/BBCode 发布/代码与链接转换/文档失效/检查失败导航）、`lookup_class_names`（同文件/跨文件/内部类/未保存改名）、`lookup_named_erased`（擦除后类名与文档重生成后源码导航） |
| `lookup_suspended` | 各版本验证门槛、真实编辑器符号信号后原资源身份、缓存路径与保存往返 |
| `auto_indent` | 空白栈精确输出、注释/空行透传、退阶怪癖、from_line 门禁、空格设置切换、from>to 恒等负例 |
| `syntax_highlighter` | 新页签自动选中、成员扫描计数、24 组逐列颜色断言（注解/类型提示/基类与自有成员/字符串含跨行与 raw/注释标记/数字/关键字）、主题改写即时生效、`.gd` 页签仍用原生高亮器 |
| `dock_compile` | 真实 RPC 服务 + 真实 zig 构建：固定模块身份与非法字符替换碰撞隔离、未知副本/其他所有者不被删除、卸载释放占用、新会话复用同一身份及目录、Debug/Release 两次真实构建复用同一内部目录、控制字符安全化、两个真实 RPC 服务的构建目录隔离、构建期间诊断仍响应、setup 后改含引号项目名、库字节一致、描述文件配置与实际加载、清单持久化、无效结果不覆盖安装、二次构建后存活实例方法返回值变化、旧 debug/release 条目迁移、单一 OS.arch 条目切换、其他目标保留、真实 Release DLL 在 Debug 编辑器中重载并改变存活实例行为、非法配置类型/未托管描述文件/误包含插件源码/bin 外与目录穿越路径的失败锚点 |
| `build_toolbar` | 真实标题栏位置、弹出内容高度、焦点策略、菜单内容、版本/后端查询、Auto 与显式选择持久化、断连/无后端/查询失败提示、重连恢复、查询期间脚本重载与旧端点隔离、启动命令迁移、共享 busy/待启用门、点阵动画节拍、设置导航及启停清理、精简 dock 布局、相对时间边界/定时刷新、忙时清空输出、旧时间元数据读取、保存/构建状态提示（`Build*`/错误图标/悬浮提示/启用失败边界）、双语界面（英文/中文/未支持语言回退、静态控件自动翻译、动态日志、原生悬浮 tooltip、翻译域隔离与卸载/重新启用） |
| `sync_exclusions` | 默认排除不进模块且不被 LSP 镜像（正对照 `src/test2.gd3` 双通道同步）；真实页签打开排除文件经编辑器 `_validate` 自然纳入并跨对账保留；模拟准入被节流关闭检查逐出；自定义目录形式 glob（无通配符）改设置后不经手动通知即对账删除且镜像 didClose；纯缓冲区源"上传→排除删除→取消排除后无需落盘重新接纳"全链；恢复默认后重新同步；恢复路径回归（页签打开期间被排除保留、模块重建后无需编辑即恢复上传分析） |

`EditorAddonSetupEngineTest`（隔离真实编辑器 + 本地 HTTP 服务 + ZIP/JAR fixture）：
断连/恢复、timeline、Java 进程探测（含不发起不存在可执行文件、超时/取消）、分页、
nightly.link、依赖解包、缓存复用（主目录/随机副本/大写校验和/同名校验和改变拒绝）、
本地导入与 gdparser 标志库规则、SHA-256 失败、路径穿越、窗口范围与布局、步骤停留/
切换、阶段即时显示、Windows 文件锁导致移动失败后的完整复制与 Java 运行、未完成缓存
排除与隐藏标记清理、同步/挂起结果桥接、代理初始化、工作线程运行期间释放服务、
忙时拒绝改写、新服务启动失败提示、已连接服务保护、项目配置持久化；存在已下载离线
发行包时额外校验并解压 Windows/Linux JRE 并实际执行 Windows Java。真实插件回归另
验证拥有服务替换后的新 PID、诊断重新就绪、旧服务退出、外部旧服务在切换和插件禁用
后仍存活。

若 headless editor 路径在某环境证实不稳定，降级为：(a) 非编辑器 bootstrap 断言
（`EditorAddonBootstrapEngineTest`：编译产物可加载、类可实例化、P0-C 冒烟）保持
全量；(b) §8.3 手动清单逐项执行并记录。

### 8.3 手动验收

打开 `src/editor_addon`：`.gd3` 出现在 FileSystem 面板并可双击打开；编辑保存往返；
错误标注与 `.gd` 逐条一致（含行列号）；补全可用；函数调用输入 `(`/`,` 时显示参数
提示；连续输入→停顿后 gdcc 错误自行浮现（无需切页签/再键入）；停键且不移动鼠标
（低功耗窗口）诊断仍出现；Build 期间编辑器不卡死、诊断只是变旧、进度轮询照常推进；
编译→安装→继续编辑全流程无伪错误、gdcc 错误正常显示；配置启动命令后冷启动编辑器
无需手工 `gdcc serve` 即可 Build，退出编辑器后该服务进程消失（外部手动启动的服务
不受影响）；冷启动后不再移动鼠标，服务仍在 15s 内就绪；创建节点/资源对话框条目检查
（Stage C 暂缓期间 GD3 不出现全局类条目）；禁用/启用插件循环、重建产物后重启编辑器
均无崩溃、无悬空指针报错。

---

## 9. 暂缓项与已否决方案

- **Stage B（gdcc 大纲通道 + 补全合并）暂缓**：`analyze.outline` RPC（收割点定位：
  `AnalysisRunner` 语义分析返回后、`FrontendAnalysisData` 丢弃前；骨架在 COMPLETED-
  含错误时保留、解析失败时无）+ `_complete_code` 对 `self.`/`X.`/显式标注上下文合并
  缺失成员。排期条件：按残余补全缺口的实证需求。这不再阻止编辑器左侧成员大纲：该区
  域已由 `_validate.functions` 的 LSP 通道独立提供（§4.7），不新增 gdcc RPC，也不
  依赖 Stage C 的资源成员内省。
- **Stage C（全局类注册 + `GdccScript` 成员内省虚函数）暂缓**：`_get_global_class_name`
  行扫描解析 `class_name`/`extends` 返回字典。已核实的实施约束：(a) **必须同批**实现
  `GdccScript` 成员内省虚函数（`_get_script_method_list`/`_get_script_property_list`/
  `_get_script_signal_list`/常量表，由大纲缓存供给），否则出现"类名可解析但成员为空"
  的倒退窗口——analyzer 对非 `.gd` 全局脚本类走 `ResourceLoader.load` + 资源内省
  （`gdscript_analyzer.cpp:787-801`）；(b) 创建对话框中同名时 ClassDB 条目占槽、按名
  去重（`create_dialog.cpp:175-198, 295-298`），已编译态不会产生双份条目；(c) 未编译
  态脚本条目默认出现且**可选中**（对话框不查 `can_instantiate`）——`.gd3` 无法运行时
  挂载，故注册字典置 `is_abstract: true` 使条目禁用显示，但 `GdccScript._is_abstract()`
  必须保持 `false`：analyzer 的抽象类实例化检查看资源而非注册标志，资源侧 abstract
  会让 `X.new()` 报伪错误；(d) 对话框打开时会加载每个全局脚本类资源（非懒加载），
  loader 必须保持廉价且永不失败（加载失败 = 条目消失，属安全降级）；(e) 引擎怪癖：
  `ScriptServer.add_global_class` 仅在 base/path/language 变化时更新 abstract/tool
  标志（`script_language.cpp:408-418`），本集成的 abstract 恒定不变故不受影响。
  验收方式：手动检查创建节点/资源对话框条目（含同名已编译类的占槽行为），回归确认
  不产生重复/不可实例化条目。
- **`--lsp-port`/host 手动覆盖设置项未实施**：harness 测试经 `--lsp-port` CLI 覆盖
  钉死端口（§8.2），面向用户的 dock 覆盖入口未提供（dock 状态行只读）。
- **已否决**：全项目合成改名、仅在显示端过滤 `hides a native class`（证据链见
  §7.2 背景）。同文件路径/编译类的类型身份误报使用 §4.2 的精确显示过滤，不在此
  否决项内。

---

## 10. 风险登记册

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | `.gd3` 不在 GDScript LSP 的 workspace 索引内，跨文件符号/类缓存解析受限 | 中 | 以 `languageId: "gdscript"` didOpen 的文档可解析；workspace 镜像（§7.3）将全部项目 `.gd3` 主动 didOpen，提供跨文件查询的数据面；Godot >= 4.6.0 启用 `_lookup_code` hover/定义跳转，旧版本因浅层脚本接管资源路径而降级（§4.6）。未打开文件的磁盘扩展名过滤及 Stage C 全局类名边界仍存在；补全成员枚举不经过 workspace（引擎事实，§7.3），不受镜像影响 |
| R2 | 多编辑器实例/端口占用导致连错实例 | 中 | 握手后监听 `gdscript_client/changeWorkspace` 并与 `ProjectSettings.globalize_path("res://")` 比对，不符即 DEGRADED；host/port 可配；运行期改 `use_thread` 服务端自动重启、客户端重连接管（§2.6） |
| R3 | `_complete_code`/`_validate` 同步等待卡顿编辑器 | 中 | 硬超时（LSP 150ms / 补全 400ms / 签名提示与 lookup 单请求 200ms）+ 超时降级；阻塞形态约束写入 §1.2；线程模式硬前提 |
| R4 | 热重载导致 ScriptServer 悬空指针 | 高 | `reloadable = false`（§6），安装器全出口断言钉死 |
| R5 | `ScriptCreateDialog` 缓存语言列表，主题刷新却使用实时语言数（godot#106480） | 低 | 插件补齐 GD3 行并在注销后移除，避免索引越界；Godot ≤ 4.6.0 禁用该行并使用 dock/外部创建，> 4.6.0 允许选择（§2.1） |
| R6 | gdcc 前端/后端不支持所需 API 调用路由或类型 | 高 | 自举探针三级门禁（含 native compile 与运行时冒烟，§5/§8）；新增能力先探针后采用 |
| R7 | `.gd3` 被误挂载到节点时无占位实例（`void*` 虚函数未实现） | 低 | `_can_instantiate() == false` + `_supports_builtin_mode() == false` 双重拒绝；文档声明非目标 |
| R8 | LSP 同步模式假设不成立 | 低 | 握手校验 `textDocumentSync`（int `1` 或 `{change: 1}` 两种形状），否则 DEGRADED |
| R9 | 诊断行号 0/1-based 转换错误 | 中 | 与同内容 `.gd` 文件逐条对比验收（§8.3） |
| R10 | Godot 版本漂移（master 已废弃 `_create_script`/`_get_recognized_extensions`） | 低 | 锁定 4.5 元数据；升级 Godot 时重跑 §2 核查 |
| R11 | 非 ASCII 文本下 LSP `character` 与 Godot 列口径不一致（UTF-16 vs 码点） | 中 | §2.6：4.5/4.6 诊断列为行首非空白字符，4.7 为真实 token 列，两者均按码点而非 UTF-16 单元计数；非 BMP emoji 回归分别锚定旧版缩进列与 4.7 引号列。补全光标也按码点索引，`complete_cjk` 以"emoji 在光标同行之前且光标后有文本"的样例钉死（误算会丢失成员补全上下文） |
| R12 | 编辑器-only 类进入导出构建 | 低 | 本扩展仅 addon 内使用，文档声明不随项目导出 |
| R13 | `analyze.run` 是整模块分析，大项目延迟高 | 中 | 异步 + 防抖 + 单飞；服务专用客户端与项目管理器队列隔离（§4.4）；后续在 RPC 侧评估增量分析 |
| R14 | 外部 LSP 客户端并存时 `publishDiagnostics` 只发最近客户端 | 中 | 等待超时即回落缓存并继续；记录为已知限制 |
| R15 | 禁用插件后已打开页签持有语言/服务引用 | 高 | 常驻单实例 + 注销不释放 + 复用再注册（§3.5）；引擎测试 `language` 用例含禁用/启用循环与实例同一性断言 |
| R16 | `_load` 在 worker 线程执行时的数据竞争 | 高 | 纯加载约束（§2.4）；引擎测试 `language` 用例含 `load_threaded_request` 路径 |
| R17 | 无公开 API 主动触发编辑器 revalidate，异步 gdcc 诊断存在节拍级延迟 | 中 | 已缓解：§4.5 经 `CodeTextEditor` 的注册信号 `validate_script` 主动触发（4.5 已核实接线：`code_editor.cpp` `ADD_SIGNAL`、`script_text_editor.cpp` 连接）；残留风险是该接线非公开 API 承诺（R24） |
| R18 | `use_thread` 设置被引擎在退出时持久化，影响用户其他项目 | 中 | 卸载时恢复原值（§4.1）；dock 状态与文档明示该改动。另：运行期首次启用发生 false→true 翻转时服务端不会运行期重启（通知仅 C++ UI 可达），该会话置 `lsp_blocking_unsafe` 降级并提示重启编辑器（§4.1 第 2 步） |
| R19 | 拉起进程的 PID 在会话内被系统复用，`shutdown_owned` 误杀无关进程 | 低 | 仅跟踪本会话 `create_process` 返回的 PID；`OS.kill` 仅在 `server.shutdown` 不可达**且**记录端点仍接受 TCP 连接时使用（双条件，§3.7）；会话级窗口内复用概率极低 |
| R20 | 编辑器崩溃导致拉起的服务成为孤儿进程 | 低 | 崩溃路径本就无法执行插件代码；下次会话端口探测将其收养为外部服务（只连不关），不会泄漏累积（§3.7） |
| R21 | `server.shutdown` 不可达（服务挂起/半死连接）时服务残留 | 低 | 2s 有界等待后按 R19 双条件决定是否 `OS.kill`；服务已死但 PID 被复用时宁可残留也不误杀；Windows 下硬切不执行 shutdown hook 的事实写入 §2.8 |
| R22 | 启动命令以编辑器权限执行任意本地命令 | 低 | 命令来自项目所有者显式配置，或安装向导经过确认后生成（项目级设置 `gdcc/compilation_server/launch_command`，旧项目键一次性迁移）；下载的 Java/JAR 亦属于用户确认执行的工具代码。项目级意味着 clone 他人项目即带入命令，与打开含 tool 脚本/编辑器插件的项目同属一类既有信任面，评审陌生项目时应一并检查该键及安装来源 |
| R23 | 运行期注销脚本语言与编辑器子系统迭代 `ScriptServer` 语言表的引擎侧竞态 | 中 | 引擎测试曾观测到一次（批量跑、高负载）：禁用窗口内引擎报 `ScriptServer::get_language(1)` 越界（`_language_count = 1`）后空指针崩溃（0xC0000005）；此后多次手动复现与强制重跑均未再出现。引擎 4.5 的 `get_language`/`unregister_language` 各自加锁而 `get_language_count` 是非锁定内联读取（`core/object/script_language.cpp/.h`），调用方先取的计数在按索引取值时可能已失效（TOCTOU），且越界返回 nullptr 后调用方未判空。无崩溃栈，调用点未最终定位；插件生产代码不按索引取语言。**当前视为已观测但尚未缓解的风险**：保持"禁用即注销"合同；经评估禁用插件属低频操作且崩溃仅观测到一次，暂不特殊处理，若重现率上升再评估候选缓解（禁用时不注销语言只置 UNINSTALLED——偏离 §3.5 合同，架构级变更；或把注销延迟到编辑器静默帧——缩小窗口但不消除） |
| R24 | `validate_script` 信号触发重校验依赖编辑器内部信号接线（非公开 API 承诺） | 中 | 4.5 已核实接线（§2.5）；Godot 升级时重跑 §2.5 核查；引擎测试以"无输入后 `_validate` 调用计数前进"锚定该路径（§4.5） |
| R25 | `class_name` 擦除的行扫描可能误命中字符串/注释内的同名文本 | 低 | 行级迷你词法器（字符串转义/注释/两种三引号/普通字符串跨物理行状态机）+ 行首无缩进锚定 + 首个匹配 + 顺序尾部解析（图标串/注释尾不注入基类）+ 陷阱样例测试（§7.2）；已知残余：多行字符串内转义引号的闭合歧义（词法近似，方向安全）、超时旧代诊断按当前视图反映射的一拍级列偏移；根治待编译器引用区间输出（评估项） |
| R26 | 元数据 JSON→C 双层转义缺陷，或 `_gdcc_` 合成方法与用户代码冲突 | 中 | 复用 `StringUtil.escapeStringLiteral` + 转义矩阵测试；前端 `_gdcc_` 前缀保留规则（用户声明冲突即报错）；合成符号纳入 `validateFileScopeSymbolsDisjoint`（§7.1） |
| R27 | workspace 镜像批量同步造成启动卡顿，或重连后服务端文档集漂移 | 中 | 分帧限额 + notification 不占 `_validate` 阻塞预算；删除 didClose + 重连全量重放；引擎测试断言文档集（§8.2 `lsp_workspace`） |
| R28 | 安装向导端口预留释放到 JVM 绑定之间并非原子交接，存在被无关进程抢占的低概率窗口 | 低 | 当前 launcher 沿用 TCP 探测收养策略，无法保证抢占者就是本次命令对应的实例；消除此边界需要服务实例身份握手或端口交接协议（未扩展启动协议，不放宽外部进程所有权约束，§3.10） |
| R29 | 端点重定向时共享 RPC 客户端的未发出队列请求在发送时按当时地址拼 URL，可能落到新服务器；切回某端点时若其清理在途，迟到删除可能命中刚重建的模块 | 低 | 有界残余：模块 id 按项目哈希+pid 作用域，错误端点上的残留模块随其进程退出消亡，且下次回访该端点时由既有 -32001 重建路径自愈；迟到删除命中表现为一次瞬时 outage，经 outage→重建路径自愈。彻底隔离（按端点/会话分客户端，或请求携带真实分发端点贯穿完成回调）属较大改造，未阻塞现状 |
| R30 | 超大项目下镜像的单帧目录遍历与漂移读盘 | 低 | 当前每次扫描事件在单帧内完成目录遍历与已镜像纯磁盘文件的文本比对，典型项目可接受；可续跑遍历与分帧读盘留待后续评估（§7.3） |

---

## 11. 工程反思

1. **编译代码的泵内信号发射缺陷**：gdcc 编译代码经 `Object.emit_signal` method-bind
   对外来对象发射，从 `SceneTree.process_frame` 泵调用栈发起时不送达任何连接（§2.5）。
   凡是"泵 → 编辑器对象"的通知都走"自有信号 + 解释型 `call_deferred` 中继"两段式；
   观测锚定用计数器窗口而非信号次序。
2. **Godot `HTTPRequest` 对拒连端口挂起 30s**：任何"先探测再请求"的路径都必须用
   `StreamPeerTCP` 快探（≤200ms）先行；测试用恒 500 黑洞服务器获得确定性。
3. **编辑器 idle 校验节拍双间隔**（1.5s/0.5s，`set_error_count` 在有错误显示后才
   切快）与防抖（800ms）的先后关系决定测试基线的取点顺序；涉及"无输入后自发刷新"
   的断言先用 `_settle_past_idle` 排除 idle 节拍混入。
4. **页签状态恢复踩点**：`.godot/editor/script_editor_cache.cfg` 会在原生高亮器自动
   选择之后恢复用户上次选择；接管类功能必须在焦点信号上接力换回，并以
   settle-once + 实例 ID 记忆尊重用户后续手动选择（§3.9）。
5. **JDK HttpServer 关停死锁**：`server.shutdown` 的 handler 只置标志位，退出线程
   必须在 exchange 写完并 close 之后、且不属于请求 executor（§2.8）；测试必须打
   真实 HTTP 端口验证"200+body 先于进程退出"。
6. **`OS.create_process` 平台分叉**：Windows 同步拒绝不存在的可执行文件，Unix-like
   fork 成功后子进程 execvp 失败早退；测试断言必须接受两种报告（§3.7）。
7. **EditorSettings 无会话级设置**：`set_setting` 在退出编辑器时被引擎持久化；改动
   用户机器级设置必须登记恢复义务。项目级配置优先入 ProjectSettings（随项目版本化）。
8. **Java 单一字符串字面量 64KB class 文件上限**：引擎测试驱动的大型 GDScript 文本
   拆为多个方法返回的文本块运行时拼接。
9. **模块门下的观测**：`module.get`/`read_file` 会阻塞于在途编译；观察副本注册用
   `module.list`，内容校验移到编译结束后（§4.9）。
10. **扩展清单是引擎维护文件**：不能先手动 load 再 scan（清单可能不写）；确认扫描
    完成要看 `filesystem_changed` 回调而非 `is_scanning()`（worker 提前清标志）；
    构建收尾不能 await 扫描（首次成员变化触发全脚本软重载会取消悬挂协程）（§4.9）。
11. **内容哈希换库路径**：macOS dyld 同路径映像缓存 + 被映射库不可覆盖 → 库按内容
    SHA-256 发布到新路径，描述文件改指向；Windows 描述文件用 ConfigFile.save 而非
    rename（rename 先删旧目标）（§4.9）。
12. **Windows `MoveFileW` 只报笼统 FAILED**：跨卷/被占用移动失败时按"独立随机后缀
    目录复制 + 逐文件 SHA-256 校验 + 实跑验证"回退，不再尝试改名收尾（§3.10）。
13. **"已镜像"不是"曾在盘"的证据**：删除判定只认 disk-known 记录；纯缓冲区（未落盘）
    文件的生命周期与磁盘文件全程分开处理（§4.4/§7.4）。
14. **临时纳入的驱逐不能依赖上传先发生**：从未被页签持有的排除路径在上传前被逐出
    是合法行为，测试不得强制排序；用 channel session 钉住窗口排除恢复路径假绿，
    并以变异运行（临时摘除节流检查）实证锚点致死（§7.4）。
