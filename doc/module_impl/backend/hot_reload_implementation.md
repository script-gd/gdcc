# Hot Reload 实现说明

> 本文档是 GDExtension 热重载当前代码事实、长期合同和维护边界的唯一事实源。
> 修改相关代码时必须同步修订本文。HRX runtime 细节、entry 生命周期、wrapper 布局的模块切片分别由关联文档承载，但不得与本文冲突。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - Godot 4.5.1-stable 热重载协议在 GDCC 侧的落地合同
  - `.gdextension` `reloadable` 元数据
  - `initialize` / `deinitialize` 生命周期顺序
  - 用户类与隐藏协程状态类的 `recreate_instance_func`、`free_instance`、字段析构
  - 协程 bulk cancel 与 `RELOADED_SHELL`
  - HRX 堆驻留 thunk、hub/spec ABI、standalone interning、lambda 身份重绑
- 不覆盖：
  - GDCC 类自身 ClassDB 方法/属性/信号注册细节；见 `godot_binding_implementation.md` 与 `entry.c.ftl` 注册段
  - vtable slot 规划本身；见 `virtual_override_vtable_implementation.md`（recreate 必须重写本代 `_vtable`）
  - native 构建缓存与 PCH；见 `backend_build_system_implementation.md`
  - 对象 ownership 通用规则；见 `gdcc_ownership_lifecycle_spec.md` 与 `backend_ownership_lifecycle_contract.md`
- 关联文档：
  - `doc/gdcc_c_backend.md`：C 后端 ABI 与 entry 生命周期切片
  - `doc/gdcc_runtime_lib.md`：协程 runtime 与 HRX runtime 切片
  - `doc/module_impl/backend/explicit_c_inheritance_layout_contract.md`：wrapper 布局、create/recreate/free、析构 exactly-once
  - `doc/module_impl/backend/virtual_override_vtable_implementation.md`：vtable 与 `_vtable` 初始化
  - `doc/module_impl/frontend/frontend_lambda_implementation.md`：lambda 合成函数、plan 与 lowering；重绑身份见本文 §8
  - `doc/gdcc_ownership_lifecycle_spec.md`：协程 frame ownership 与 cancel abandonment
  - `doc/gdcc_low_ir.md`：`LirLambdaMeta` XML 往返
  - `doc/test_error/test_suite_engine_integration_known_limits.md`：headless editor 测试的引擎侧限制
- 外部事实来源（按 `godotengine/godot@4.5.1-stable` 核验）：`gdextension_manager.cpp`、`gdextension.cpp`、`gdextension_interface.cpp`、`gdextension_library_loader.cpp`、`object.cpp`、`class_db.cpp`、`callable.cpp`

## 1. 范围与职责边界

热重载的目标是：编辑器进程内重新编译 GDExtension 后，Godot 卸载旧库、加载新库、迁移存活实例，不崩溃、不把旧库代码指针留给引擎。release 导出不受影响。

GDCC 负责：

- 声明 `reloadable = true`，并为每个可创建类提供 `recreate_instance_func`
- 在旧库 `deinitialize()` 内完成全部需要旧代码的清理
- 让 Godot 持有的 custom Callable 回调指向库外可执行 thunk，新代按身份重绑
- 明确用户可见语义与 fail-closed 边界

GDCC 不负责：增量编译、编辑器自动触发编译、`NOTIFICATION_EXTENSION_RELOADED` 用户钩子、worker 线程 custom Callable 的并发回收。

## 2. 当前实现总览

```text
.gdextension (reloadable=true)
        │
entry.c initialize
        ├─ gdcc_init()
        ├─ gdcc_coro_set_hot_reload_active(is_editor_hint)
        ├─ gdcc_hrx_initialize(anchor, rebind table)   // 冻结模式 + 接管 hub + 重绑/清扫
        ├─ ClassDB 注册（含 recreate_instance_func）
        └─ static backing 初始化
entry.c deinitialize
        ├─ gdcc_coro_cancel_all()
        ├─ static backing 逆序销毁
        ├─ 注销隐藏协程类 → 逆序注销用户类   // reload 路径在此段内调 free_instance
        ├─ String/StringName/legacy standalone registry 销毁
        └─ gdcc_hrx_deinitialize()                    // 最后：摘 sweeper、清空 impl 指针
```

关键落点：

| 层 | 落点 |
|---|---|
| 元数据 | `GdextensionMetadataFile` 始终输出 `reloadable = true` |
| entry | `src/main/c/codegen/template_451/entry.c.ftl` / `entry.h.ftl` |
| HRX runtime | `src/main/c/codegen/include_451/gdcc/gdcc_hrx.h` / `gdcc_hrx.c` |
| Callable 分流 | `src/main/c/codegen/include_451/gdcc/gdcc_callable.h` |
| 协程 | `gdcc_coroutine.h` / `gdcc_coroutine.c` + 生成的状态类 recreate |
| 身份 catalog | `CHrxIdentityCatalog`（`HRX_ABI_VERSION = 2`，与 C 宏由契约测试锚定） |
| frontend 身份 | `FrontendLambdaIdentityAnalyzer` → `FrontendLambdaPlan` → `LirLambdaMeta` |
| native 输入 | `CProjectBuilder` 固定编译 `gdcc_hrx.c`（在 `gdcc_coroutine.c` 之后） |

锚点 token 由模块名 UTF-8 的 MD5 前 8 字节按 little-endian 组成 64 位常量；零值改为 1。不含 optimization / architecture。只作相等键，永不解引用。

## 3. Godot 热重载协议合同

核验对象为 Godot 4.5.1-stable；当前端到端测试跑在 4.5.2 editor。协议未发现破坏性差异。

### 3.1 reload 时间线

1. `.gdextension` 必须含 `reloadable = true`。编辑器在窗口重获焦点时按库文件 mtime 触发；`--editor` 开启 extension reloading。`GDExtensionManager.reload_extension(path)` 可脚本同步强制重载，不看 mtime。
2. `prepare_reload()` 标记本扩展类 `is_reloading`，遍历受跟踪实例（跟踪来源是 `godot_object_set_instance`，不是 `ClassDB.instantiate`），保存带 `PROPERTY_USAGE_STORAGE` 的属性。存在非 NIL 默认值且当前值等于默认值则跳过；Object 值为 null 且无 `STORE_IF_NULL` 则跳过。
3. 调用旧库 `deinitialize()`。类注销时因 `is_reloading`，引擎对每个存活实例执行 `_clear_extension()`：调用旧库 `free_instance_func`（**不发 PREDELETE**）→ 清空 `_extension` / `_extension_instance` → 释放 slot 0 主 instance binding → 清空 per-object virtual 缓存。
4. `clear_instance_bindings()` 只按 **GDExtension\* token** 释放被跟踪 binding，不调用 `free_instance_func`。固定常量 token（HRX 锚点）不被 track，因而不被该路径触及。Engine 单例不是扩展实例，`clear_internal_extension` 不适用。
5. `close_library()`：`dlclose`。`[dependencies]` 不被独立保活。Windows 加载 `~xxx.dll` 副本。
6. 新库 `initialize()` 重注册类/方法/属性/信号。方法命中旧 bind 时 `try_update`：兼容则更新函数指针；不兼容则旧 bind 失效。未重注册的方法/类在 `finish_reload` 失效或移除。兼容性检查覆盖 static/vararg/返回值/参数个数/各参数 Variant 类型，**不查** class_name、默认参数、const。
7. `finish_reload()`：`_extension_instance = recreate_instance_func(...)`（返回值直接成为扩展实例指针，NULL 则失败），随后恢复属性并发送 `NOTIFICATION_EXTENSION_RELOADED`。引擎不调 `object_set_instance`、不发初始化通知。

### 3.2 硬性门槛

- 任何带 `create_instance_func` 的类若无 `recreate_instance_func`，引擎将整个扩展置 `reloadable = false`。隐藏协程状态类无豁免。
- 不注销同名类直接重注册被 ClassDB 拒绝；注销必须派生类→基类。
- `is_runtime` 变更被强制沿用旧值，不失败、不禁用 reload。
- 父类改为非本扩展已知类且名字变化时引擎要求重启；改为另一个 GDCC 类（同扩展）引擎不检查，属未定义行为，合同列为不支持。
- 初始化级别降低 → `LOAD_STATUS_NEEDS_RESTART`。
- engine virtual 缓存已在 clear 时清空；下次虚调用经新库 `get_virtual_call_data` / `call_virtual_with_data` 重查。
- custom Callable 的 `token` 仅作 userdata 身份匹配，引擎不自动失效。`is_valid_func` 必须提供，否则引擎视 Callable 永远有效。未提供 `hash_func`/`equal_func` 时身份为 `(call_func, userdata)`。每次 `callable_custom_create2` 产生独立 custom object，共享 spec 必须引用计数。

### 3.3 跨代内存不对称

- `godot_mem_alloc` 分配的是 Godot 堆，`dlclose` 后仍存在。
- 指向库映像的函数指针在 `dlclose` 后悬空。凡需执行旧代码的操作必须在 `deinitialize()` 内完成。
- 跨代共享数据一律 `godot_mem_alloc`，禁止依赖 CRT 堆。`godot_mem_alloc` 不置零，跨代结构必须显式初始化。
- 引擎 MethodBind / singleton 指针指向 Godot 进程，禁止在 deinitialize 中 `memdelete`。
- runtime 除 minicoro 的 POD TLS 指针外不得引入线程/TLS 析构器，以免阻碍 `dlclose`。

## 4. Entry 生命周期合同

`initialize()` 必须在任何 custom Callable 产生之前冻结 HRX 模式。顺序：

1. `gdcc_init()`
2. `gdcc_coro_set_hot_reload_active(gdcc_is_editor_hint())`（仅当模块含协程函数）
3. `gdcc_hrx_initialize(class_library, GDCC_HRX_ANCHOR_TOKEN, rebind_table, count)`
4. ClassDB 注册与 static 初始化

`deinitialize()` 同时服务 reload 与正常退出。GDExtension 接口不暴露 `is_reloading`，两条路径对“static 销毁 vs 类注销”的相对顺序要求相反，因此统一采用对两条路径都安全的顺序：

1. 打印 unloading 消息
2. `gdcc_coro_cancel_all()`（仅当模块含协程函数；门控关闭时为 no-op）
3. **static backing 按初始化逆序销毁**
4. **类注销**：先隐藏协程状态类按生成序严格逆序，再用户类按 `inheritanceOrderedClassDefs` 逆序
5. 销毁 StringName / String / legacy standalone registry
6. **`gdcc_hrx_deinitialize()` 最后**：先 `sweeper = NULL`，再将全部 spec 的实现函数指针置 NULL、`binding_state = UNBOUND_INCOMPATIBLE`；`dead` / `refcount` 不动

纪律：

- 字段析构链、协程字段清理、spec 捕获析构不得依赖 static backing（注销段内 `free_instance` 执行时 static 已销毁）。
- 析构路径不得新增对 `GD_STATIC_S` / `GD_STATIC_SN` 惰性创建的依赖。注销段内 registry 仍存活，现有 `GD_STATIC_SN` 使用安全。
- Object 存储释放一律经 `instance_id` → ObjectDB（`gdcc_object_live_ptr`），禁止解引用 fat pointer 缓存的 GDCC wrapper。reload 批量 clear 的遍历顺序任意：被引用实例的 wrapper 可能已释放，而其 Godot 对象因强引用仍存活。
- HRX 模式下 standalone interned spec 归 hub，legacy registry 为空，其 `destroy_all` 为 no-op；direct 模式相反。禁止两套 registry 释放同一对象。

`gdcc_is_editor_hint()` 取值：仅 `--editor` / project manager 进程为 true；编辑器 F5 启动的游戏进程与导出包为 false。三态模式机以该值为进程判定输入。

## 5. 实例 recreate / free / 析构合同

布局、命名与拓扑顺序的权威切片见 `explicit_c_inheritance_layout_contract.md`。本节只冻结热重载相关不变量。

### 5.1 用户类 recreate

每个可创建用户类生成 `<C>_class_recreate_instance`：

- **必须** `return self`（wrapper）。禁止 `return p_object`。
- 做：`godot_mem_alloc` → `_set_object_ptr` → 写本代 `_vtable`（与 create 共用 `renderVtableFieldInitExpr`）→ `_gdcc_destructed = false` → `godot_object_set_instance_binding`（与 create 相同的 library/callbacks）→ `<C>_class_init_fields(self)`（base-first 重放非 static 属性 initializer）。
- 不做：不构造新 Godot 对象、不调 `godot_object_set_instance`、不发 POSTINITIALIZE、不做 RefCounted 初始化、不调 constructor / `_init`。
- OOM 或 `p_object == NULL` 返回 NULL，引擎按 recreate 失败处理。
- 属性恢复由引擎随后经 setter 完成。

`<C>_class_init_fields` 专用于 recreate，不与 constructor 共用：constructor 中 `_init` 与父递归交错。

### 5.2 析构 exactly-once

根 wrapper（与 `_object` / `_vtable` 同级）持有 `_gdcc_destructed`。只存在于根段，经 `_super` 链访问。

- `<C>_class_destruct_fields(self)`：无守卫；析构本类字段后递归父段。
- `<C>_class_destructor(self)`：唯一带守卫入口；已析构则返回。
- PREDELETE 与 `free_instance` 都只调守卫入口。reload 路径不发 PREDELETE，`free_instance` 必须补齐析构。
- 守卫禁止放在链式函数入口，否则派生类置位后父类字段泄漏。
- `godot_mem_alloc` 不置零，create / recreate 必须显式置 `false`。
- 协程状态类不接入该标志：PREDELETE 只 cancel，字段清理由 `free_instance` 唯一承担。

### 5.3 vtable

recreate 必须写入本代静态表。pass-through 类共享最近非 pass-through 祖先表，禁止写 `NULL`。vtable 是 GDCC 内部多态分发，不是 ClassDB 注册机制。

## 6. 协程取消与 RELOADED_SHELL

编辑器进程通过 `gdcc_coro_set_hot_reload_active(true)` 打开跟踪；非编辑器为零成本直通。

活跃链表：模块级侵入式、非 owning；仅真正可 resume 的 state 入链；finalize / cancel / free 幂等 unlink。OOM 态（`co == NULL && done`）与 `RELOADED_SHELL` 永不入链。

`gdcc_coro_cancel_all()`：每轮先摘头节点 → 临时强引用保活 → 断开全部 signal waiter → 现有 cancel-resume（abandonment：不设 done、不 resume waiter、不发射 `completed`）→ 释放临时引用。禁止缓存 `next`。

signal waiter：

- 边保存 emitter 的 ObjectID、signal 名与 Callable 身份；非 owning，禁止 `state → Callable → state` 环。
- connect 成功后才发布到 `signal_reg`。
- HRX 模式下登记 backing spec，detach 时用 `gdcc_hrx_callable_retain` 重建相等查找键。waiter spec **没有重绑条目**，reload 后恒失效。
- 正常路径依赖 cancel_all 先于 hub 失效。

协程状态类 recreate：

- wrapper 整体 `memset` 为零
- 用**新代** descriptor 初始化 header
- `reloaded_shell = true`
- binding 使用 `gdcc_coro_binding_token()`，禁止照抄 `class_library`
- 不入活跃链表、不跑 body、不恢复 waiter、不发射 `completed`
- await 立即返回取消结果

协程 token 占该对象 slot 0。reload 时 `clear_internal_extension` 先 `free_instance` 再清空 slot 0；callbacks 全 NULL，无悬空回调。该 token 不是 GDExtension\*，不被 `clear_instance_bindings` 跟踪。recreate 写 slot 0 时该槽已空。

## 7. HRX custom Callable 合同

权威 runtime 切片见 `gdcc_runtime_lib.md` §HRX。本节冻结跨模块不变量。

### 7.1 架构

```text
Godot Callable ──► 共享 thunk（hub 可执行页，永久，全 spec 同一份）──► spec（Godot 堆）
                                              ├─ impl_ptr / destroy_fn / is_valid_fn
                                              ├─ impl_key / schema_desc / fingerprint
                                              ├─ callsite_context / argument_count
                                              ├─ binding_state / dead / refcount / hub
                                              └─ captures
hub: registry + intern table + sweeper + pending + 共享 thunk 页
锚点: Engine 单例 instance binding（per-module 常量 token）
```

不变量：

1. HRX 模式下 Godot 持有的四个回调指针指向堆 thunk，永无悬空代码跳转。
2. thunk 只查表、标记、（尾）调用；不做堆分配、链表操作、Variant 析构。
3. 内存回收只由当代主库代码执行。
4. `callable_userdata` 钉死为 spec。thunk 把 `spec->captures` 作为实现函数首参。free thunk 经 `spec->hub->sweeper`（hub 偏移 40，spec.hub 偏移 48）。
5. 捕获布局失配时宁泄漏不误析构。壳元数据无条件释放。
6. `binding_state` 与 `dead` 分离。禁止用 `destroy_fn == NULL` 推断失配。

### 7.2 三态模式

进程生命周期内冻结：

| 模式 | 条件 | 行为 |
|---|---|---|
| `DIRECT_NON_RELOAD` | 非编辑器 | 现状直调；不建 hub、不挂锚点 |
| `HRX_ACTIVE` | 编辑器且 execmem probe 成功 | thunk 路径 |
| `HRX_UNAVAILABLE` | 编辑器但 probe 失败 | 创建 custom Callable 返回无效并一次性报错；**禁止回退 direct** |

旧 hub 必须继续接管，不得因本次 probe 失败切 direct。损坏锚点（magic/version 失配）不是接管凭证：孤岛协议摘除后，本次 probe 仍门控新 hub。无 thunk 模板的 ISA（如 riscv64）probe 恒失败。

### 7.3 ABI

- `GDCC_HRX_ABI_VERSION = 2`
- `GDCC_HRX_HUB_VERSION = 3`
- `GDCC_HRX_HUB_MAGIC = 0x4744434348525855`
- spec 布局 append-only；`callsite_context` 为 v2 尾部字段，偏移 136（静态断言）。读取或释放该字段必须先检查 `abi_version >= 2`。v1 spec 首次遇到 v2 runtime 时全部失效。
- Java `CHrxIdentityCatalog.HRX_ABI_VERSION` 必须与 C 宏相等（契约测试锚定，非编译期共享定义）。schema 前缀 `gdcc-hrx:2;` 同源。
- schema 文法由 `CHrxIdentityCatalog` 冻结；fingerprint 是 schema 字节的 MD5。类型字段使用 `CGenHelper.renderGdTypeInC` 的 C 存储类型（同时钉表示与 ownership）。改该渲染会让旧连接全部 fail-closed。
  - lambda：`gdcc-hrx:2;caps=<C types>;params=<C types>;ret=<C type>;va=0|1;co=0|1`
  - standalone：`gdcc-hrx:2;sa;kind=<token>;argc=<n>;va=0|1;ret=0|1;uh=<hash>`
- thunk 模板：x86_64 SysV（含 macOS Intel）、x86_64 Win64、aarch64（写后刷 icache）。运行时零补丁。POSIX 只用匿名 RW→RX 页（`mmap(MAP_PRIVATE|MAP_ANONYMOUS)` + `mprotect`），不使用 `MAP_JIT`。页大小运行时查询。

共享 thunk 页在 hub 创建时一次性 RW→写→RX，此后永不再写。页与 hub 同寿命；孤儿 hub 的页随之泄漏。

### 7.4 锚点

- 一律走 `object_get_instance_binding`。禁止 `set_instance_binding`（只写 slot 0）。
- callbacks：`free_callback` / `reference_callback` 必须 NULL（引擎拷贝这两个指针）；`create_callback` 不得省略（NULL 会空调用）。
- `create_callback` 失败必须立即 `object_free_instance_binding` 摘除 NULL tombstone。initialize 查询前做有界清扫，上限 `GDCC_HRX_TOMBSTONE_SWEEP_MAX = 8`。
- 失配：不遍历旧 registry、不归还旧页，摘除后挂新 hub。孤岛泄漏至进程退出。

### 7.5 重绑与清扫

重绑三门（顺序负载）：

1. `abi_version == GDCC_HRX_ABI_VERSION`，否则禁止读 v2 字段
2. `impl_key` 命中且 `schema_desc` 长度+逐字节一致
3. `callsite_context` NULL-safe 相等（standalone 双方恒 NULL 视为相等）

两阶段：

1. 单遍历：dead 且 `refcount==0` 的 spec 摘入 worklist；全部存活 spec 就地重绑。**任何析构发生之前，全部存活 spec 必须先完成重绑**。
2. 对 worklist：仅当重绑表命中且 `destroy != NULL` 且 fingerprint **与** `schema_desc` 都匹配时，才用**表条目的** `destroy_fn` 析构捕获；否则泄漏捕获。壳元数据无条件释放。standalone interned payload 走固定释放路径，不计入 `leaked_capture_count`。

库存活期 sweeper：先 intern 摘除（按 `interned` 标志，禁止用 `intern_next==NULL` 判定），再摘 registry，再析构。重入时入 `pending_next` 专用链，`sweep_depth` 归零后 drain。

`free` thunk：先递减 refcount，归零才置 `dead` 并尾跳 sweeper。

### 7.6 standalone

HRX 模式下 intern 表由 hub 拥有。命中必须拒绝 `dead` 与 schema 失配 spec。payload 为固定 ABI 的 Godot 堆克隆（三个字符串副本），`destroy_fn` 恒 NULL，释放走固定路径。

### 7.7 线程

custom Callable 全生命周期不得离开主线程。runtime 不拦截。`Callable(对象, "方法名")` 不受本合同影响，经 MethodBind `try_update` 始终有效。

## 8. Lambda 身份合同

身份由 frontend 产出，经 LIR 透传到 backend catalog。缺条目 fail-fast，禁止默认空串。

### 8.1 管线

1. `FrontendSemanticAnalyzer` 在 interface 分析后、suite 解析前调用 `FrontendLambdaIdentityAnalyzer`
2. 结果发布到 `FrontendAnalysisData.lambdaIdentities()`
3. `FrontendSuiteResolver` 查表填充 `FrontendLambdaPlan.identityOrdinal` / `callSiteContext`
4. lowering 写入 `LirFunctionDef.lambdaMeta`（`LirLambdaMeta`）
5. XML `<meta source_identity_key= call_site_context=>` 往返；无 key 的 lambda codegen 失败
6. `CHrxIdentityCatalog` 发射 impl_key、schema、fingerprint、argument_count、callsite_context

property initializer 与 parameter default 中的 lambda 不进入 `FrontendLambdaPlan`，不占序号。

禁止用类级 `_lambda_<k>` 计数器或无序 Map 遍历作为身份序号。

### 8.2 三门

| 层 | 内容 |
|---|---|
| 主键 `impl_key` | `<Class>::<enclosingFunc>#<ordinal>`；constructor 为 `_init` |
| schema | 捕获布局 + 签名 + `abi_version` 的 canonical descriptor（文法见 §7.3） |
| 调用点上下文 | 归一化描述子；standalone 为 NULL |

`ordinal`：最外层具名函数 body 内、与 `collectLambdaContexts` 同构的源码先序序号（0-based）。遇 `LambdaExpression` 先编号再只递归 `body()`。嵌套 lambda 共享外层具名函数的序号域，但上下文搜索不越过 lambda 边界。

不含文件名、不含源位置。位置信息仅可作诊断展示。

禁止把 lambda body 哈希加入 schema：会破坏“改 body 执行新代码”主路径。

### 8.3 调用点描述子

锚到最近语句级锚点；片段文本经 `range()` 切片并做空白/注释归一化。链式表达式必须用 typed accessor 还原角色。

| 源码形态 | 描述子 |
|---|---|
| `self.sig_a.connect(func...)` | `call(base=<链前缀切片>, method=connect, arg=0)`；base 不是链头 |
| `foo(x, func...)` | `call(callee=<callee 切片>, arg=1)` |
| `var cb := func...` | `assign(var=cb, kind=var)` |
| `x = func...` | `assign_expr(lhs=<left 切片>, op=<op>)` |
| `await (func...)` | `await`（禁止切片 operand） |
| `[a, func...]` | `array(idx=1)` |
| `return func...` | `return` |
| 其他 | `stmt(<容器节点类型名>)` |

### 8.4 重绑与失效矩阵

- 改 body / 该 lambda 之前增删非 lambda 行 / 其他函数改动 / 函数整体移动 → key 与 context 不变 → 重绑到新实现
- 捕获布局或签名失配 / lambda 删除 / 可区分调用点换位 → 失效，不绑错 body
- 提取变量、局部变量改名、链前缀改名、冗余链头括号增删 → 预期假失效（fail-closed）

残余盲区（后果为误绑而非失效，当前不修）：

1. 同语句内表达式级换位且 schema 相同
2. 同形语句换位或前插（同一信号两次 connect、同 callee 同实参序、同左值两赋值、同函数双 `await (func...)` / 双 `return`、同型 `stmt(T)`）
3. 跨函数搬迁到同形槽位
4. 在外层 lambda 之前增删外层 lambda 导致嵌套者序号位移且同形相撞

## 9. 用户可见语义合同

- reload 仅 editor build 可用；导出包走 direct 路径，与现状观察等价。
- 存活实例：带 `PROPERTY_USAGE_STORAGE` 的已注册实例属性由引擎保存并恢复（过滤规则见 §3.1）。仅在 `_init` 中赋值、之后未再改动的字段保持 initializer 值（`_init` 不重新执行）。
- 属性 initializer 在 recreate 时对每个存活实例重新执行，随后引擎 setter 覆盖保存值。用户代码不得依赖 initializer 的 exactly-once 副作用。
- static var 重置为初始值。
- 进行中的协程被静默取消：`completed` 不发射，等待方同样被取消。
- 编辑器内 lambda / standalone Callable：兼容连接执行新代码；失配或上下文变化则优雅失效（信号静默跳过；脚本 `call()` 走引擎标准错误）。thunk 路径下 `to_string` 显示 `<CallableCustom>`。
- custom Callable 全生命周期不得离开主线程。
- 编辑器内 execmem 不可用时，custom Callable 创建返回无效 Callable（fail-closed）。
- 方法签名变更后，经旧签名的调用报错（引擎语义）。`is_runtime` 变更被强制沿用旧值。父类变更不支持。

## 10. 回归测试基线

| 测试 | 覆盖 |
|---|---|
| `GdextensionMetadataFileTest` | `reloadable = true` |
| `CCodegenTest` / `CVtableCodegenTest` / `CCoroutineStateClassCodegenTest` | recreate、析构守卫、deinitialize 顺序、vtable 重写、RELOADED_SHELL、身份 catalog 发射 |
| `FrontendLambdaIdentityAnalyzerTest` 及 plan/lowering/LIR 往返测试 | 序号、描述子、fail-closed、XML meta |
| `GdccHrxRuntimeSmokeTest` | thunk、重绑三门、版本守卫、direct 模式、waiter 无重绑 |
| `GdccCoroutineRuntimeSmokeTest` | cancel_all、shell、门控、HRX detach |
| `GodotEditorHotReloadIntegrationTest` | headless editor 端到端 |
| `GodotRuntimeDirectPathIntegrationTest` | 非编辑器 direct 路径 |
| `CProjectBuilderCoroutineRuntimeInputTest` | native 输入含 `gdcc_hrx.c` |

端到端场景（Linux + Godot 4.5.2 editor/headless）：

- 实例存活、STORAGE 属性恢复、方法跑新代码
- 继承/多态与 `@tool` 类 `_process` 重挂
- 挂起协程静默取消；新代协程正常完成
- PREDELETE→free、reload free→recreate、连续两次 reload、继承字段、逆序注销
- `Callable(对象, "方法名")` 连接跨 reload 执行新方法
- lambda 重绑 / schema 失配与删除失效 / deferred 副本
- 方法签名变更走引擎错误；static 重置；engine 父类变更报重启
- 可区分调用点换位失效、序号免疫、提取变量假失效

编排合同：

- 换库必须原子 rename（先拷到临时文件再 `mv`）。禁止 in-place 覆盖已映射 `.so`：会把干净页灌成新内容而 GOT 脏页仍指向旧版，随后 SIGSEGV。
- driver 在最终 marker / `quit` 前停留 180 帧，让编辑器文档再生成在扩展存活期内排空（上游缺陷，类 godot#123511 / #111048）。
- 观测 `_process` 的 fixture 必须 `@tool`。
- 解释型 driver 不得主动调用已知失效的 Callable 或已知错误 arity 的方法（GDScript SCRIPT ERROR 会中止当前函数）。

## 11. 工程反思

- deinitialize 必须同时安全服务 reload 与正常退出。先注销类再销毁 static 会在正常退出路径上经悬空 `_extension` UAF。
- 析构守卫与析构链必须分离；Object 释放必须以 ObjectDB 为准，不能信 wrapper 缓存指针。
- Godot 不凭 custom Callable token 自动失效，也不能靠伴随 shim 库保活。共享 thunk 页必须在任何 Callable 存在之前一次性 RX 发布；对已发布页做 RX→RW 会把 fail-closed 穿透。
- 重绑必须先于任何析构。捕获析构可能同步释放其他 Callable。
- 身份主键用序号而不是源位置，才能让“改 body / 插非 lambda 行”走热重载主路径；调用点上下文只能消歧可区分形态，同形换位仍是已知缺口。
- Java 与 C 的 ABI 版本是两个字面常量，只能靠契约测试防漂移。

## 12. 已知限制与非目标

已知限制：

- macOS Hardened Runtime 无 entitlement 时的 RW→RX 尚未按 aarch64 / x86_64 分别实机验证；probe fail-closed 保底。
- Windows `~xxx.dll` 副本替换机制尚未实机验证。
- 有窗口 F5 游戏进程以 `is_editor_hint()==false` 走 direct，与 headless 非 editor 判定相同，有窗变体保留手测。
- 协程 `completed` 不发射尚无独立端到端观测断言。
- ASan / Valgrind 对 exactly-once / 泄漏的完整证明不在自动化范围内。
- schema/context 失配时 lambda 捕获块故意泄漏；损坏锚点孤岛泄漏；tombstone 超过 8 个可能继续遮蔽新 hub。
- §8.4 残余盲区仍可能误绑。

非目标 / 后续接缝：

- 增量编译与 dirty tracking
- `NOTIFICATION_EXTENSION_RELOADED` 用户钩子
- 同映像重复初始化加固（以“runtime 不引入线程/TLS”规避为主）
- worker 线程 custom Callable 的 generation quiescence
- 可选的 lambda body 哈希消歧
- sljit 替换手写 thunk 模板（生成接口已隔离 ISA）
