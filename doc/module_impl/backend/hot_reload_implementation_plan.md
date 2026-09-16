# GDExtension 热重载（Hot Reload）实施计划

## 0. 文档状态

- 状态：**计划，未实施**。本文描述分步实施方案与验收细则；实施落地后应拆分为长期事实源文档并修订本文状态。
- 修订记录：
    - v2：经两轮审阅并按 `godotengine/godot@4.5.1-stable` 源码核验后，重写 §2 协议事实基线（reload 时间线、recreate 检查、属性保存/恢复过滤、重注册语义）、修正 recreate 返回值/binding 合同、重写用户可见语义合同（STORAGE 而非 export）、修正 D2 析构守卫截断缺陷、补充协程取消/惰性壳/Signal waiter 处理、收窄 Callable 限制范围。
    - v3：custom Callable 跨 reload 方案定稿——堆驻留 thunk + 双模派发（§5），替换 v2 的"文档化限制 + 待确认"立场；核验两项引擎事实：Godot 不凭 `GDExtensionCallableCustomInfo2.token` 自动失效 custom Callable（`core/extension/gdextension_interface.cpp`），`[dependencies]` 不保证依赖库跨 reload 存活（`gdextension_library_loader.cpp:236-239` 只关主库句柄）；据此否定 stable shim 伴随库路线，确立单库 thunk 路线。
    - v4：§5 经两位审阅专家复核后全面硬化：`get_argument_count` 补 `r_is_valid` 两参数 ABI；失效 call 写完整 `GDExtensionCallError`；standalone 共享 spec 引入 `refcount` 与 payload 字符串堆拷贝；锚点由 Engine meta（脚本可篡改）改为 Engine 单例 instance binding + 固定 token；execmem 探测失败改为 fail-closed 三态模式机（禁止回退 direct）；thunk 页 slab 化（live-count 控制 unmap）；清扫改两阶段防重入；失配 spec 引入显式 `binding_state`；重绑键升级 128-bit schema 指纹；补协程 waiter 政策与线程禁止合同；补 is_valid 嵌套调用 ABI 约束与 macOS Hardened Runtime/mprotect 失败点。
    - v5：§5 第二轮复核（双 BLOCKING 收敛）后修正：free thunk 改"先递减、归零才置 dead"（v4 的顺序会使共享 spec 提前失效且永不回收）；sweeper/清扫增加 interning 表摘除步骤（hub 结构显式持有该表）；锚点 callbacks 全 NULL 且结构体随 hub 驻 Godot 堆（引擎保存的是 callbacks 指针，库内 static 会悬空）；锚点 token 改 codegen 生成的 per-extension 稳定 64 位常量（删除 `gdcc_coro_binding_token()` 先例引用——TU-static 地址跨代不稳）；线程禁令扩至 custom Callable 全生命周期；`impl_key` 改"稳定源身份"（`Class::enclosingFunc@file:line:col`，新增 frontend 合同）——**否定**"lambda 本体哈希入指纹"方案（会破坏"改 body 执行新代码"主路径），schema 指纹只服务析构安全；hub 补 pending 清扫队列与 `sweep_depth` 进出时序；spec 存 canonical descriptor 副本支撑逐字节比对；壳元数据（`impl_key`/descriptor/intern key）与 captures 分离释放。
    - v6：§5 第三轮复核后修正，含一处对 v5 的事实纠错（经 `object.cpp:2104-2141, 2167-2187`、`object.h:673-681` 核验）：**引擎拷贝 binding 的 free/reference 两个函数指针（`create_callback` 不保存），而非保存 callbacks 结构体指针**——v5"callbacks 结构体必须驻 Godot 堆"系误判，真正要求是拷贝出的函数指针全 NULL；`Object::set_instance_binding` 只写 slot 0 且被占用即失败（审阅者指认成立），锚点获取改走 `object_get_instance_binding`（动态数组追加、无固定上限、token 线性查找）；spec 增加 `pending_next`（pending 队列专用链，不得复用 registry/interning 链指针）、`interned` 标志（摘除判定不得用 `intern_next==NULL`，单桶节点同样为 NULL）、`thunk_slot` 句柄；slab 页管理状态迁入 hub（跨代存续，新代可回收旧代槽位）；新代阶段二明确使用**重绑表条目的** `destroy_fn`（spec 字段已在 deinitialize 置 NULL）；删除 `GDCC_HRX_DEAD` 枚举值（生死只看 `spec->dead`）；standalone payload 改为固定 ABI 堆克隆（字符串堆拷贝、作为壳元数据无条件释放、`destroy_fn` 恒 NULL）。新代清扫改"单遍历先完成全部存活 spec 重绑、再执行任何析构"（终审捕获的时序漏洞：先析构后重绑会使重入变 dead 的 spec 仍处 UNBOUND 而泄漏 captures）。
    - v7：`impl_key` 合同修订（设计问答确认）：**去掉文件名**——类名模块内唯一（不允许重名）且 canonical name 已锚定来源文件，hub 又 per-extension，文件名纯冗余且引入"重命名文件打翻全部连接"的失效面；**绝对行号改相对偏移**（`@+Δline:col`，相对最外层具名函数起始行）——绝对行号会被其他函数的增删高频打翻，相对偏移下其他函数改动与本函数整体移动均不影响 key，唯一剩余失效面是本函数体内该 lambda 之前的增删；**修正对 `FrontendLambdaPlan` 的误判**——plan 已携带 `lambda`/`enclosingCallable` AST 节点（gdparser `Node.range()` 自带源位置）与 `owningClassCanonicalName`，数据齐备，缺的只是派生 key 的 helper，改动面远小于此前估计。
    - v8：HR-0.3 结论落定——**headless editor 自动化端到端测试可行**（经 `4.5.1-stable` 源码与官方文档核验）：`GDExtensionManager.reload_extension(path)` 脚本可直接调用、同步执行、强制重载指定扩展（不看 mtime）、实例恢复与 `NOTIFICATION_EXTENSION_RELOADED` 在返回前完成；`--headless --editor --script` 是官方支持路径（脚本须继承 `SceneTree`/`MainLoop`）；`--editor` 自动开启 extension reloading；headless 无窗口，焦点自动 reload 不触发，全部走脚本显式触发，天然确定性；Godot 官方无 GDExtension 热重载自动化测试先例。据此将 HR-9 自动化由 stretch 升级为正式交付项 `GodotEditorHotReloadTestSession`（架构见 HR-9 自动化节），手测清单保留为兜底；HR-9 改号并移至最终步骤，新增步骤覆盖矩阵。
    - v9：HR-9 自动化路线本机探针验证通过（Linux，Godot 4.5.2-stable editor binary，探针产物存于 `tmp/editor_hotreload_probe/`）：手写最小 C 扩展（reloadable + recreate + 注册属性）完成全链路——`--headless --editor --script` 启动、SceneTree 脚本与 EditorNode 共存（EditorNode 首帧即挂载）、`GDExtensionManager.reload_extension` 同步返回 OK、state save → deinitialize/unregister/free → dlclose/dlopen → recreate → state restore 完整往返（`get_value` 1→2 证明新代码在同一 Godot 对象上生效、`stored_value` 跨 reload 保留证明引擎属性 save/restore 路径）、`quit(code)` 退出码 editor/runtime 双模式原样传递、bogus 路径优雅报错。**关键陷阱（已修正换库合同）**：禁止 in-place 覆盖已映射 `.so`——`cp` 原地截断写入会使运行中映射的干净页重灌新文件内容而脏页（GOT）保留旧版，旧 GOT 调用新代码立即 SIGSEGV；换库必须**原子 rename**（`cp v2 临时文件 && mv -f 临时文件 目标路径`），unlink 不影响既有映射，引擎 dlclose 旧 inode 后 dlopen 路径即得新 inode。Windows `~xxx.dll` 副本机制项仍为 Windows 专属待验证。
    - v10：补齐 macOS x86_64（Intel）thunk 生成表述——§5.3 明确复用 x86_64 SysV 模板（macOS Intel 同为 System V AMD64 ABI，参数寄存器/16B 对齐/128B red zone 一致，模板零新增）；§5.4 macOS execmem 路径覆盖两架构（`MAP_JIT`/`pthread_jit_write_protect_np` 为 arm64 专属，Intel 不涉及；slab 页大小运行时查询，Intel 4K / Apple Silicon 16K）；§5.10 平台矩阵新增 macOS x86_64 行（universal2 编辑器 Intel 切片必须覆盖），RW→RX 实机验证项改为按架构分别核验。
    - v11（实施期修订，HR-4 双审阅发现并经用户确认）：**D8 顺序修订——static backing 销毁与类注销段对调**（static 销毁 → 类注销 → registry 销毁）。原顺序仅在 reload 路径安全；正常退出（非 reload）时 `_unregister_extension_class` 不调 `_clear_extension`（`gdextension.cpp` 非 reload 分支直接 `extension_classes.erase`），static backing 持有的 GDCC 实例在最后引用释放时会经悬空的 `_extension` 执行析构回调（UAF，链路经 `object.cpp:2263-2278, 933-935` 与 `main.cpp:4985,5016` 核验成立）；GDExtension 接口不暴露 `is_reloading`，无法运行时分流，故统一改为两路径皆安全的对调顺序，并扩展 D3 纪律（字段析构链/协程字段清理不得依赖 static backing）。HR-4 验收细则同步修订；`gdcc_c_backend.md` 与 `explicit_c_inheritance_layout_contract.md` 已同步。
    - v12（实施期修订，HR-9 自动化落地 HR-1~HR-5 部分）：`GodotEditorHotReloadTestSession` 与 4 个端到端场景测试已实现并全绿（Linux + Godot 4.5.2 editor，见 §6 HR-9 自动化节状态行）。关键新事实：**`_process` 挂载方式结论落定**——headless editor 下 `root.add_child()` 即可驱动节点 `_process`，但 fixture 类必须 `@tool`：gdcc 生成的 `call_virtual_with_data` 对非 tool 类的 `_process`/`_physics_process` 在 `gdcc_is_editor_hint()` 下既定跳过（非 tool 脚本编辑器语义对齐，见 `entry.c.ftl` 虚拟分派段），非热重载缺陷；冒烟验证项"需 `_process` 的场景挂载方式"据此闭环。
    - v13（实施期修订，HR-9 自动化性能与健壮性）：① 耗时画像——nativeCompile 占绝对大头（约 19.7s × 9 次构建 ≈ 77% 总时长），编辑器启动约 10s/次，reload 本身毫秒级；② 加速落地——4 个场景的原生构建目录改为 `tmp/test/editor_hot_reload/build_<scenario>` 单目录跨 v1/v2/v3 复用并跨测试运行存活（路径一致使 zig 按 TU 内容缓存吸收 3 个运行时 TU，仅 `entry.c` 逐版本重编），预建 `shared-compiler-cache` 使 4 场景共享 PCH/zig 缓存根，测试类加 `@Execution(CONCURRENT)`（`ZigCcCompiler` 锁按 projectDir 粒度，构建真并行；编辑器进程互相独立）：全类墙钟 3m50s → 约 25~27s（热缓存 nativeCompile ≈ 0.4~0.5s/次）；③ **上游引擎缺陷实证**：`reload_extension` 返回后于重负载下立即 `quit` 会在 `Main::cleanup()` 的 `MessageQueue::flush()` 中崩溃（SIGSEGV）——机制同 `godotengine/godot#123511`/`#111048`（deferred 编辑器文档再生成在扩展卸载后执行，StringName 键悬空；4.5.2 本机以手写最小 C 扩展 probe_ext 复现，与 gdcc 无关；负载放大竞态窗口），缓解：driver 在最终 marker/quit 前停留 60 帧让消息队列在扩展存活期内排空（满核负载下验证 4/4 干净），手测清单遇同类崩溃亦按此处理。
    - 实施进展（2026-09）：HR-0 ✅（附录 A）；HR-1 ✅；HR-2 ✅（D2，双审阅闭环）；HR-3 ✅（D4/D5，双审阅闭环：修复 alloc OOM 守卫、壳执行级测试）；HR-4 ✅（D8 v11，双审阅 + BLOCKING 修订闭环）；HR-5 ✅（D5 + 双模门控，双审阅闭环）；HR-6 ✅（文档合同，随 HR-8 落地）；HR-8 ✅（§5 全合同，实施期发现的事实纠偏见下）。详见 §6 各步骤状态行。
    - **HR-8 实施期事实纠偏**（相对 §5 设计稿，均为实现层细化而非合同变更）：① ~~四 thunk 合入**单 slab 槽位**~~（**已被 v14 取代**：per-spec slab 槽位整体废弃，改为每 hub 一页共享静态 thunk）；② standalone 身份结构与重绑表在 codegen 侧统一由 `CHrxIdentityCatalog` 发射（§5 预期的运行时字符串构造改为 codegen 静态发射，runtime 零格式化代码，schema_desc 跨代逐字节可比对）；③ `argument_count` 数据化字段并入 `gdcc_hrx_identity`（创建/重绑同源）；④ 锚点 token 派生输入定为模块名 MD5 前 64 位（不含 optimization/architecture）；⑤ 清扫重入 drain 与 §5.6 点 4 伪码等价地实现于 sweeper 尾部与阶段二尾部两处（深度归零即排空）。
    - v14（实施期修订，HR-8 双审阅复核期间发现的 BLOCKING 驱动）：**per-spec slab thunk 槽位整体废弃，改为每 hub 一页的共享静态 thunk**。动因：slab 写新槽位需把整页 RX→RW→RX，若 RX 恢复持久失败，同页已发布 sibling thunk 将执行 NX 页崩溃，fail-closed 被穿透（review-expert-c 复核指认）。关键事实核验（`core/extension/gdextension_interface.cpp:65-73,132-170,204-234`）：四个回调的 arg0 恒为 `callable_userdata`（= spec），默认相等为 `(call_func, userdata)` 二元组、默认哈希混合两指针——因此 spec 立即数本就是冗余（arg0 寄存器已携带），共享 `call_func` 后相等归约为 spec 同一性，与 per-spec thunk 的等价类完全一致。唯一缺口是 free thunk 需要 hub（sweeper）：spec 新增 `hub` 回指针字段（偏移 48，ABI 冻结），free thunk 经 `spec->hub->sweeper` 两次加载。§5.1 不变量 6、§5.2 结构、§5.3 模板合同（零补丁，离线汇编字节内嵌）、§5.4（slab→共享 thunk 页，hub 创建时一次性 RW→写→RX 后**永不再写**，probe 回退单循环；页与 hub 同寿命、跨代 thunk 地址恒定）、§5.6（摘除 slot 归还步骤）、§5.7（身份语义改述）同步修订；原 slab 验收项（同页 sibling 保活、跨代槽位回收）作废，替换为"全 spec 共享同一组 thunk 函数指针 + 跨代地址恒定"验收项。
- 关联文档：
    - `doc/gdcc_c_backend.md`：C 后端 ABI 与 entry 生命周期合同（Scene-level initialize/deinitialize）。
    - `doc/gdcc_runtime_lib.md`：runtime 全局 registry（String/StringName/standalone Callable）、协程运行时清理规则。
    - `doc/module_impl/backend/virtual_override_vtable_implementation.md`：vtable 布局、实例 `_vtable` 初始化、virtual userdata 生命周期。
    - `doc/module_impl/backend/explicit_c_inheritance_layout_contract.md`：wrapper 布局、create/bind/free 链路、static 两阶段初始化。
    - `doc/module_impl/backend/godot_binding_implementation.md`：method/property/signal 注册合同与模块级 wrapper 缓存。
    - `doc/gdcc_ownership_lifecycle_spec.md`：对象 ownership 与协程 frame 生命周期（含 cancel 的 abandonment 语义）。
- 外部事实来源（均已按 `4.5.1-stable` 核验，行号对应该 tag）：`core/extension/gdextension_manager.cpp`、`core/extension/gdextension.cpp`、`core/extension/gdextension_interface.cpp`、`core/extension/gdextension_library_loader.cpp`、`core/object/object.cpp`、`core/object/class_db.cpp`、`core/variant/callable.cpp`。
- 需求编号：HR-0（残余契约核对）、HR-1（`reloadable` 元数据）、HR-2（free_instance 幂等全量析构）、HR-3（recreate_instance_func 生成）、HR-4（deinitialize 逆序类注销）、HR-5（协程统一取消）、HR-6（Callable 跨 reload 合同落地）、HR-8（堆驻留 thunk Callable 间接派发）、HR-9（端到端验收，最终步骤）。

## 1. 目标与范围

- **目标**：编译产出的 GDExtension 库在 Godot 编辑器中支持热重载——重新编译后编辑器获得焦点时自动完成旧库卸载、新库加载、存活实例迁移，不崩溃、不泄漏（happy path），语义边界有明确合同。
- **本期范围（最小完整闭环 + 健壮性）**：HR-0 ~ HR-9（注：编号 HR-7 已随端到端验收改号 HR-9 并移至最终步骤）。
- **非本期范围（仅记录，见 §8）**：增量编译管线、`NOTIFICATION_EXTENSION_RELOADED` 用户钩子、同映像重复初始化加固、worker 线程 Callable 调用的 generation quiescence 协议、sljit 替换字节模板（§5.3 的隔离接口预留升级位）。

## 2. Godot 热重载协议事实基线（4.5.1-stable 已核验）

### 2.1 引擎 reload 时间线（`GDExtensionManager::reload_extension`，gdextension_manager.cpp:146-194）

1. 前提：`.gdextension` 含 `reloadable = true`；编辑器在窗口重获焦点时按库文件 mtime 触发 reload（仅 editor build 可用）。
2. `prepare_reload()`（gdextension.cpp:916-977）：将本 extension 全部类标记 `is_reloading`；遍历受跟踪实例（跟踪来源：`ClassDB::set_object_extension_instance` → `track_instance`，即我们的 `godot_object_set_instance`；**不是** `ClassDB::instantiate` 本身），保存属性值——过滤条件：必须带 `PROPERTY_USAGE_STORAGE`；存在非 NIL 默认值且当前值等于默认值则跳过；Object 类型值为 null 且无 `STORE_IF_NULL` 则跳过。
3. `_unload_extension_internal()` → `deinitialize_library()` → 调用 extension 的 `deinitialize()`（旧库代码仍在，可正常执行）。
4. **关键**：deinitialize 中调用 `classdb_unregister_extension_class` 时，因 `is_reloading` 标记，引擎对每个存活实例执行 `_clear_extension()` → `Object::clear_internal_extension()`（object.cpp:2189-2217），依次：调用**旧库** `free_instance_func`（**不发 PREDELETE**）→ 清空 `_extension`/`_extension_instance` → 释放主 instance binding → 清空 per-object 缓存的 virtual 函数指针。**因此 free_instance_func 的执行时点在 deinitialize 的类注销段内部**。
5. `clear_instance_bindings()`（gdextension.cpp:987-997）：对每个对象调 `free_instance_binding(this)`，即 instance binding 的 `free_callback`；**不调用** `free_instance_func`。
6. `close_library()`：`dlclose`（Linux/macOS 可能因 TLS 析构/残留线程等不真正卸载，见 Issue #90108；Windows 加载 `~xxx.dll` 临时副本，保证全新映像且原文件可被覆盖）。`gdextension_library_loader.cpp:236-239` 只关闭主库句柄，**`[dependencies]` 不被独立保活**（Linux 上作为 `DT_NEEDED` 随主库引用计数归零而卸载），故"伴随 shim 库"路线已被否定。
7. `open_library()` → `initialize_library()`：extension 重新注册全部类/方法/属性/信号。方法重注册命中 `is_reloading` 旧 bind 时执行 `try_update`（gdextension.cpp:161-191, 520-545）：兼容性检查覆盖 static/vararg/返回值/参数个数/各参数 Variant 类型（**不查** class_name、默认参数、const）；兼容则更新函数指针/userdata/元数据；不兼容则旧 bind 标记 `valid=false` 入 `invalid_methods` 并创建新 bind。未重注册的方法在 `finish_reload` 标记 invalid；未重注册的类被移除。
8. `finish_reload()`（gdextension.cpp:1028-1073）：对每个存活对象调 `Object::reset_internal_extension()`（object.cpp:2219-2227）——`_extension_instance = recreate_instance_func(...)`（返回值直接成为 extension 实例指针，NULL 则失败），随后 `_extension = p_extension`；**引擎不调 `object_set_instance`、不发任何通知**。之后经 `obj->set(name, value)`（走 property setter 路径）恢复保存的属性，最后发送 `NOTIFICATION_EXTENSION_RELOADED`。

### 2.2 硬性门槛与重注册语义（已核验）

- **recreate 强制**：注册任何带 `create_instance_func` 的类时若无 `recreate_instance_func`，引擎打印告警并将**整个 extension** 置 `reloadable = false`（gdextension.cpp:428-439）。判定**无** `is_runtime`/`is_exposed`/`is_virtual`/`is_abstract` 豁免——隐藏的协程状态类同样必须提供 recreate。
- **注销是重注册的硬前置**：不注销同名类直接重注册会被 ClassDB 拒绝（already registered）；注销必须派生类→基类逆序。
- **is_runtime 变更**：reload 重注册时报错并**强制沿用旧值**（不失败、不禁用 reload）。
- **父类变更**：仅当新父类不是本 extension 的已知扩展类且名字变化时 `ERR_FAIL_MSG`（要求重启）；改为另一个 GDCC 类（同 extension）不会触发该检查，属未定义行为，用户合同列为不支持。
- **初始化级别降低**：`LOAD_STATUS_NEEDS_RESTART`。
- **引擎 virtual 重建**：`clear_internal_extension` 已清空 per-object virtual 缓存；下次虚调用时引擎经**新库** `get_virtual_call_data_func`/`call_virtual_with_data_func` 重新查询，无需 extension 额外动作，但要求新库这两回调正确重挂。
- **custom Callable 无自动失效**：`GDExtensionCallableCustomInfo2.token` 仅作 `callable_custom_get_userdata` 身份匹配（`core/extension/gdextension_interface.cpp:162-164, 204-219`），引擎不解释、不验证、不追踪；`call`/`is_valid`/`free`/`equal`/`to_string`/`get_argument_count` 全部回调路径均无卸载检查（`:85-174, 230-234`），卸载/reload 流程无 track/untrack 机制。`hash` 是构造时唯一缓存项（`:106-108, 221-227`）。`Callable::callp` 与信号发射前会查 `is_valid()`（`core/variant/callable.cpp:43-57`、`core/object/object.cpp:1283-1297`），false 则静默跳过/返回 `CALL_ERROR_INSTANCE_IS_NULL`——这是失效 Callable 优雅降级的引擎侧落点；也因此 `is_valid_func` **必须提供**（否则引擎视 Callable 永远有效）。
- **custom Callable 默认身份**：未提供 `hash_func`/`equal_func` 时，引擎以 `(call_func, callable_userdata)` 二元组作 hash/相等身份（`gdextension_interface.h:526-532`）——共享同一 `(thunk, spec)` 即可保持"两次独立构造的同一 standalone Callable 相等"的现状语义。
- **free 粒度**：`CallableCustomExtension` 析构对每个 custom object 各调一次 `free_func(userdata)`；Callable 普通副本共享 custom object，但每次 `callable_custom_create2` 产生独立 custom object（`gdextension_interface.cpp:230-234`）——共享 spec 必须配引用计数。

### 2.3 关键不对称（一切清理设计的出发点）

- `godot_mem_alloc` 分配的是 **Godot 堆内存，dlclose 后仍然存在**；函数指针指向库代码页，dlclose 后**必然悬空**（旧库 static 全部随映像消失；重新 dlopen 得到全新零初始化映像）。
- 推论：**凡需要执行旧代码的操作，必须在 `deinitialize()` 内完成**（协程取消、类注销触发的实例释放、static 销毁、registry 销毁、hub 失效置空）；dlclose 之后旧库代码指针一个都不许再被调用。
- 推论：旧库 static（MethodBind/singleton 缓存、StringName registry、`_inited` 标志、静态 vtable、default-arg userdata）随映像清零，**无需显式缓存清理**；engine 侧 MethodBind/singleton 指针指向 Godot 进程（引擎未重启），reload 后依然有效，且**禁止**在 deinitialize 中 `memdelete` 引擎 MethodBind。
- 推论（Windows CRT 风险）：跨代库各自链接的 CRT 可能拥有独立堆状态（静态 CRT 堆随 FreeLibrary 销毁；跨代 malloc/free 配对错乱），因此**跨代共享数据一律使用 `godot_mem_alloc`**（引擎分配器跨 reload 稳定、任一代解析到的都是同一分配器），禁止依赖 CRT 堆跨代存活。

## 3. 现状差距清单

| # | 差距 | 位置 |
|---|---|---|
| G1 | `.gdextension` 未输出 `reloadable` | `GdextensionMetadataFile.java:43-71` |
| G2 | creation info 未设置 `recreate_instance_func`（用户类与协程状态类；引擎无豁免，缺一不可） | `entry.c.ftl:66-78`、`entry.c.ftl:90-100` |
| G3 | `deinitialize()` 不注销任何 extension 类（不注销则新库重注册被 ClassDB 拒绝） | `entry.c.ftl:127-146` |
| G4 | `free_instance_func` 只 `godot_mem_free`；字段析构挂在 `PREDELETE` 上，而 reload 路径经 `clear_internal_extension` 调 free_instance **不发 PREDELETE** → 字段泄漏 | `entry.c.ftl:329-335`、`entry.c.ftl:358-386` |
| G5 | 实例 `_vtable` 指向库内静态 vtable，recreate 时必须重写为新库地址 | `virtual_override_vtable_implementation.md` §4 |
| G6 | 挂起协程持有旧库代码指针（minicoro body、descriptor 回调、signal waiter Callable）；无统一取消机制 | `gdcc_coroutine.c`、`entry.c.ftl:499-538` |
| G7 | Godot 侧存活的 custom Callable（lambda/standalone）持有旧库 `call_func`/`free_func`，跨 reload 调用即崩；副本可逃逸至信号连接、deferred 队列、Variant，无法枚举撤销；引擎无 token 自动失效（§2.2）→ **由 §5 方案（HR-8）解决** | `gdcc_callable.h:380-436`、`doc/gdcc_runtime_lib.md` |
| G8 | 协程状态对象已被引擎跟踪（create 走 `godot_object_set_instance`，`entry.c.ftl:488`），但其 binding 使用 `gdcc_coro_binding_token()` 且 header 无"死亡/惰性壳"状态，recreate 语义需专门定义 | `entry.c.ftl:483-496`、`gdcc_coroutine.h:69-81` |

## 4. 关键设计决策

- **D1（清理时点）**：所有需要执行旧代码的 teardown 集中在 `deinitialize()` 内完成；dlclose 之后不再存在任何"稍后回调旧库"的路径（custom Callable 由 §5 方案从根上消除此问题）。
- **D2（free_instance 幂等全量析构，守卫与链分离）**：根 wrapper（`_object`/`_vtable` 同级）增加 `GDExtensionBool _gdcc_destructed` 标志（属 extension 内部布局，需同步 `explicit_c_inheritance_layout_contract.md`，避免被误认为 ABI 变更或逐派生类各放一份）。析构拆分为两层：
    - 不带守卫的字段析构链 `<C>_class_destruct_fields(self)`：析构本类字段后递归 `<super>_class_destruct_fields(&self->_super)`（镜像现有 destructor 的 derived→base 链）；
    - 带守卫的入口 `<C>_class_destructor(self)`：`if (self == NULL || 根标志) return; 置位根标志; <C>_class_destruct_fields(self);`。
      PREDELETE 路径与 `free_instance_func`（`if (!destructed) <C>_class_destructor(self); godot_mem_free(self);`）都只调守卫入口，实现两条路径的 exactly-once；守卫禁止放在链式函数入口，否则派生类置位后父类字段全部泄漏。根标志经 wrapper `_super` 链访问（访问模式复用 vtable accessor 既定方案）；`godot_mem_alloc` 不置零，create/recreate 必须显式置 `false`。
- **D3（free_instance 的执行环境）**：按 §2.1 步骤 4，free_instance 在 deinitialize 的类注销段内部执行；按 D8（v11）顺序，此时 String/StringName registry **尚未销毁**（`GD_STATIC_SN` 使用安全），缓存 MethodBind 亦有效，现有析构路径可正常工作，但 **static backing 已销毁**。三条保守纪律：析构路径不得**新增**对 `GD_STATIC_S`/`GD_STATIC_SN` 惰性创建的依赖（防止未来调整 D8 顺序后引入 use-after-destroy）；字段析构链与协程字段清理**不得依赖 static backing**（它们只触碰 self 字段，保证 v11 对调后注销段内 free_instance 安全）；**Object 存储的释放一律经 `instance_id` → ObjectDB（`gdcc_object_live_ptr`），禁止解引用 fat ptr 缓存的 GDCC wrapper**——wrapper 生命 ≠ 对象生命：reload 批量 `clear_internal_extension` 的实例遍历顺序任意，被引用实例的 wrapper 可能已先被释放而其 Godot 对象因强引用存活（v11 实施期复核发现的 UAF，统一在 `renderManagedStorageFreeStmt` 与 `destruct_fields` 落实；ObjectDB 是引擎侧权威，跨整个 deinitialize 有效）。
- **D4（recreate 合同）**：每个用户类生成 `<C>_class_recreate_instance(void* p_class_userdata, GDExtensionObjectPtr p_object)`，语义：
    - **必须** `return self`（wrapper 指针；引擎直接赋给 `_extension_instance`）。**禁止** `return p_object`（create 模板返回的是 Godot 对象，照抄会把 `Object*` 当 wrapper 用，必崩）。
    - **做**：`godot_mem_alloc` 分配 wrapper → `_set_object_ptr(self, p_object)` → 写 `_vtable`（与 create 共用 `renderVtableFieldInitExpr` 渲染结果）→ `_gdcc_destructed = false` → `godot_object_set_instance_binding`（与 create 相同的 library/callbacks）→ 调新增递归字段初始化 helper `<C>_class_init_fields(self)`（base-first 递归祖先段后执行本类非 static 属性的 init apply helper；**不调** `<C>_class_constructor`、**不跑** `_init`）。
    - **不做**：不调 `classdb_construct_object2`（Godot 对象存活，禁止新建）；不调 `godot_object_set_instance`——`reset_internal_extension` 自身只负责把 recreate 返回值写入 `_extension_instance` 并设置 `_extension`，无需也不应被 recreate 抢先改写（该函数以 `ERR_FAIL_COND(_extension != nullptr)` 开头，调用时引擎保证 `_extension` 已清空；实例跟踪亦无需重建：reload 期间 `_unregister_extension_class` 在 `is_reloading` 时不清 `Extension::instances`，跟踪关系自然存活）；不发 POSTINITIALIZE；不做 RefCounted 初始化（对象原生部分存活）。
    - 属性恢复由引擎在 recreate 之后经 setter 完成；`prepare_reload` 跳过等于 ClassDB 默认值的属性（默认值快照来自引擎跑过 `_init` 的临时实例），因此仅在 `_init` 中赋值、之后未再改动的字段，reload 后保持 initializer 值而非 `_init` 值——写入用户合同。
- **D5（协程取消与惰性壳）**：
    - **活跃链表不变量**：模块级侵入式链表非 owning；仅真正可 resume 的 state 入链；finalize/cancel/free 统一走幂等 unlink；OOM 态（`co == NULL && done`）与惰性壳**永不入链**。
    - **`gdcc_coro_cancel_all()` 算法**（deinitialize 第一步，旧代码存活期内）：每轮先摘除头节点 → `own_object` 临时保活 state → **断开全部 signal waiter 挂起边**（见下）→ 执行现有 cancel-resume（abandonment 语义）→ release 临时引用。禁止"缓存 next 再取消"（cancel 释放 waiter 强引用可能级联释放相邻节点）。
    - **signal waiter 建模**：每个 signal suspension 在 state 上登记为可取消挂起边。生命周期合同：挂起边保存 emitter 的 **ObjectID**（经 liveness 校验使用，不持有无验证裸指针）、signal 名与 Callable 身份；边本身归 state 持有但**非 owning**（不得通过在边上复制 owning Callable 形成 `state → Callable → state` 强引用环，也不得为同一 userdata 复制出会重复执行 `free_func` 的第二个 Callable）；connect 成功后才发布边；signal 正常触发、connect 失败、Callable 被 free、cancel 四条路径统一走 exactly-once detach。取消时在释放 frame 前断开连接。测试须覆盖：emitter reload 后存活、signal 永不触发、断开过程 free callback 重入、signal 正常触发后边已清除、emitter 先于取消被销毁。
    - **取消语义合同**：沿用现有 abandonment 语义——**不设置 done、不 resume waiter、不发射 `completed`**；`cancel_all` 对每个活跃协程逐一取消，等待方协程自身也会在遍历中被取消。用户合同写明"reload 时进行中的协程被静默取消，`completed` 不发射"。
    - **惰性壳 recreate**（协程状态类）：wrapper 全量分配后：header 以**新库** descriptor 初始化并置于显式"RELOADED_SHELL"终态（需在 runtime header 生命周期中新增该状态，与 done/cancel 并列）；参数/capture/返回槽等 owning 字段全部初始化为合法默认值（返回槽 written 标志置 false），保证既有 `free_instance` 清理路径对壳安全且幂等；壳不入活跃链表；对壳 await 必须立即返回确定的取消结果，不得挂起；壳的 PREDELETE/free 幂等，绝不调用 coroutine body、旧 waiter 或 `emit_completed`。binding 必须使用 `gdcc_coro_binding_token()`（与用户类的 `class_library` 不同，照抄会导致 `gdcc_coroutine.c` 取不到 binding）。
- **D6（custom Callable 合同）**：跨 reload 安全由 **§5 堆驻留 thunk 方案（HR-8）** 解决——编辑器进程内 lambda/standalone Callable 全部经堆 thunk 间接派发，回调地址永久有效；存活 Callable 在新代按名重绑后**执行新代码**；签名/捕获布局失配或被删除的 lambda 优雅失效（`is_valid=false`、call 走引擎标准错误路径）。非编辑器进程维持现状直调（零改动）；编辑器内若可执行内存不可用则 **fail-closed**（拒绝创建 custom Callable，绝不回退 direct 重新引入悬空指针）。`Callable(对象, "方法名")` 连接走 ClassDB MethodBind 经 `try_update` 更新，**始终有效**，与本方案正交。
- **D7（static 与缓存）**：依赖映像清零语义，不新增缓存清理代码；runtime 审计禁止引入线程/TLS，保证 dlclose 真正卸载（开发期用 `/proc/<pid>/maps` 验证）。static var 跨 reload 重置为初始值，写入用户合同。
- **D8（deinitialize 内部顺序，v11 修订）**：打印 → `gdcc_coro_cancel_all()`（HR-5）→ **static backing 逆序销毁** → **类注销段**：先协程状态类按**生成序的严格逆序**、再用户类按 `inheritanceOrderedClassDefs` 逆序，调 `godot_classdb_unregister_extension_class`；free_instance 在此段内被引擎触发（§2.1 步骤 4）→ 三个 registry 销毁（现有；thunk 模式下 standalone interned spec 的归属见 §5.7）→ **hrx hub 失效置空（HR-8：先 `sweeper=NULL`，再全 spec 函数指针置 NULL）放最后**。此前各阶段释放的 Callable 仍能经 sweeper 被本代即时回收（最佳内存卫生）；置空后只剩 Godot 侧残余副本（deferred 队列等）可触发 free thunk，仅标记 dead，由新代清扫。
    - **v11 修订缘由（static 销毁与类注销对调）**：GDExtension 接口不向扩展暴露 `is_reloading`，deinitialize 同时服务 reload 与正常退出两条路径，而两条路径对"static 销毁 vs 类注销"的相对顺序要求**相反**——正常退出时 `_unregister_extension_class` 不调 `_clear_extension`（`gdextension.cpp` 非 reload 分支直接 `extension_classes.erase`），若先注销类，static backing 持有的 GDCC 实例在最后引用释放时会经已悬空的 `_extension` 执行 `untrack_instance`/`notification2`/`free_instance`（`object.cpp:2263-2278, 933-935`），构成 UAF；reload 时 `is_reloading` 使注销先 `_clear_extension` 清空 `_extension`，两序皆安全。对调后两条路径各自安全（已核验）：
        - **正常退出**：static 销毁释放引用时 `_extension` 仍有效，实例正常析构；注销时该类已无存活实例，`erase` 安全；
        - **reload**：static 销毁同上（tracked 实例尚未 clear，`_extension` 有效）；注销段内引擎对剩余 tracked 实例 `clear_internal_extension` 并调 free_instance——此时 static 已销毁，故须满足下方纪律。
    - **配套纪律（扩展 D3）**：字段析构链（`<C>_class_destruct_fields`）、协程状态类 `free_instance` 的字段清理、以及未来 sweeper 的 spec 销毁**不得依赖 static backing**（它们只触碰 self 字段/spec 自身）；原 D3 纪律不变——析构路径不得新增对 `GD_STATIC_S`/`GD_STATIC_SN` 惰性创建的依赖（registry 销毁仍在类注销段之后，注销段内 `GD_STATIC_SN` 使用安全）。
    - `gdcc_coro_cancel_all()`（HR-5）保持在 static 销毁**之前**：取消-resume 的 `__finally__` 清理虽不读 static，保守起见于一切资源存活时执行。

## 5. 堆驻留 thunk：custom Callable 跨 reload 方案（HR-8 设计合同）

### 5.1 架构与不变量

编辑器进程内，交给 Godot 的 `call_func`/`free_func`/`is_valid_func`/`get_argument_count_func` 全部指向**运行时生成在可执行堆内存里的微型 thunk**（不属于任何库映像，dlclose 后仍可执行）；thunk 只做查表、标记与（尾）调用，真正的 lambda 实现函数指针存在 Godot 堆上的 spec 里，由新代主库按名重绑。

```text
Godot Callable ──► 共享 thunk(hub 可执行页, 永久, 全 spec 同一份) ──► spec(Godot 堆, 永久)
                                                 ├─ impl_ptr / destroy_fn / is_valid_fn (随代重绑)
                                                 ├─ impl_key / schema_fingerprint / argument_count
                                                 ├─ binding_state / dead / refcount / hub(回指针)
                                                 └─ captures（现状捕获块，原封不动）
hub(Godot 堆, 永久): registry 双向链表 + sweeper(当前代清扫器) + magic/version + sweep_depth
                     + thunk_page(创建时一次性 RX 发布，永不再写)
锚点: Engine 单例的 instance binding（固定 per-module token，脚本不可访问）
```

不变量：
1. **hrx 模式下** dlclose 后 Godot 持有的每个扩展函数指针都指向堆 thunk，永无悬空代码跳转；
2. thunk 不做堆分配、不做链表操作、不做 Variant 析构——只查表、标记、（尾）调用；
3. 内存回收只由"当前代主库代码"执行（sweeper 或新代清扫），旧代码永不在卸载后被需要；
4. 跨代共享数据一律 `godot_mem_alloc`（§2.3 CRT 条款）且**显式初始化**（`godot_mem_alloc` 不置零：`dead/refcount/binding_state/impl_ptr/prev/next/sweeper` 等字段一律显式赋值）；thunk 代码页用 OS 可执行内存（进程级，与库卸载无关）；
5. 捕获布局失配时宁泄漏不误析构；失效一律走引擎标准错误路径（§2.2 的 `is_valid` 检查落点）；
6. `GDExtensionCallableCustomInfo2.callable_userdata` **钉死为 spec**（不是 captures）；thunk **直接使用 Godot 传入的 arg0**（四回调 arg0 恒为 `callable_userdata`，已按 `gdextension_interface.cpp:132-170,230-234` 核验）作为 spec 指针，再把 `spec->captures` 作为实现函数首参；free thunk 需要的 hub 经 `spec->hub` 回指针获取（`spec->hub->sweeper`）。thunk 字节因此**零运行时补丁**，全进程同一 ISA 只需一份。

### 5.2 数据结构与分配合同

```c
typedef enum gdcc_hrx_binding_state {
    GDCC_HRX_BOUND_COMPATIBLE,        // 已绑定到某代实现，可安全调用/析构
    GDCC_HRX_UNBOUND_INCOMPATIBLE     // 失配或未重绑：不可调用、不可析构捕获
    // 注意：生死只看 spec->dead，binding_state 禁止出现第三种取值
} gdcc_hrx_binding_state;

typedef struct gdcc_hrx_spec {
    uint32_t abi_version;
    gdcc_hrx_binding_state binding_state;
    uint32_t dead;                    // 仅当 refcount 归零后由 free thunk 置位（幂等）：全部 custom object 已释放
    uint32_t refcount;                // §5.7：共享 spec 时每次 create2 +1，free thunk -1
    uint32_t interned;                // 1 = standalone（在 hub interning 表中）；0 = lambda
    const char *impl_key;             // godot_mem_alloc 堆驻留字符串（稳定源身份，见 §5.6；standalone 即 identity key）
    const unsigned char *schema_desc; // godot_mem_alloc 的 canonical descriptor 副本（布局+签名+abi_version）
    uint32_t schema_desc_len;
    unsigned char schema_fingerprint[16]; // 128-bit：schema_desc 内容的指纹（查找用；执行 destroy 前逐字节比对 schema_desc）
    gdcc_hrx_impl impl_ptr;           // = 现 per-lambda call_func（签名天然兼容）
    gdcc_hrx_destroy_fn destroy_fn;   // = 现 per-lambda free_func 的捕获析构逻辑（standalone 恒 NULL，见 §5.7）
    gdcc_hrx_is_valid_fn is_valid_fn; // = 现 per-lambda is_valid_func（可空）
    int32_t argument_count;           // = 现 per-lambda get_argument_count 值，数据化
    void *captures;                   // = 现 callable_userdata 捕获块（布局/分配不变）
    gdcc_hrx_hub *hub;                // 回指针（free thunk 经此读 hub->sweeper；与 spec 同寿命）
    struct gdcc_hrx_spec *prev, *next;       // registry 双向链
    struct gdcc_hrx_spec *intern_next;       // hub interning 表哈希链（仅 standalone；lambda 恒 NULL）
    struct gdcc_hrx_spec *pending_next;      // sweep_pending 队列链（仅清扫期使用，独立于 registry/interning 链）
} gdcc_hrx_spec;

typedef struct gdcc_hrx_hub {
    uint64_t magic;  uint32_t version;
    gdcc_hrx_spec *registry;          // 非 owning 双向链表头
    gdcc_hrx_spec **intern_table;     // standalone interning 哈希表（godot_mem_alloc，hub 拥有，§5.7）
    uint32_t intern_table_size;
    gdcc_hrx_sweep_fn sweeper;        // 当前代主库注册；deinitialize 置 NULL
    uint32_t sweep_depth;             // 清扫重入守卫（§5.6 点 4）
    gdcc_hrx_spec *sweep_pending;     // 重入期间入队的待清扫 spec 链（§5.6 点 4，经 spec->pending_next 串联）
    void *thunk_page;                 // 共享 thunk 代码页（hub 创建时一次性 RX 发布，永不再写，§5.4）
} gdcc_hrx_hub;
```

- spec/hub/捕获包装/payload 字符串：`godot_mem_alloc`（§2.3）。共享 thunk 代码页：`VirtualAlloc`/`mmap` RW→RX（§5.4）。
- `captures` 与现状 `gdcc_new_lambda_callable` 的 `userdata` 捕获块（`gdcc_callable.h:407-431`）是**同一份数据**：同样的 codegen 布局、同样的 `godot_mem_alloc`；`impl_ptr` 就是现 per-lambda `call_func`（thunk 尾调用把首参换成 `captures`，`args/argc/r_return/r_error` 原样透传，正是其现有签名）。
- spec/hub/thunk 模板布局 ABI **永久冻结**（append-only + version）；`abi_version` 参与 schema 指纹计算。
- `binding_state` 与 `dead` 职责分离：`dead` 由 free thunk 写（Callable 已死），`binding_state` 只由主库代码写（重绑/清扫决策依据）；**禁止用 `destroy_fn == NULL` 推断失配**（兼容但无析构需求的 spec 同样为 NULL）。

### 5.3 thunk 模板合同

四个函数型 thunk（`hash` 构造时缓存、`equal`/`to_string` 省略走引擎默认、均不执行扩展代码——代价：`to_string` 显示 `<CallableCustom>`，写入用户合同）：

```text
is_valid(userdata=spec):                          // 嵌套调用，非尾调用
    if (spec->impl_ptr == NULL || spec->dead) return false;
    if (spec->is_valid_fn == NULL) return true;
    return spec->is_valid_fn(spec->captures);     // 需完整栈帧（见下方 ABI 约束）

free(userdata=spec):                              // Godot 保证每个 custom object 恰好调一次
    if (spec->refcount == 0) return;              // 防下溢（已回收/异常重复 free）
    if (--spec->refcount != 0) return;            // 仍有副本共享：保持可调用，不得置 dead
    spec->dead = 1;                               // 全部 custom object 已释放
    if (hub->sweeper != NULL)
        tail-jmp hub->sweeper(spec);              // 库存活期：当代代码完整回收（含 intern 摘除）
    // 卸载后/清扫置空期：仅标记，新代清扫

call(userdata=spec, args, argc, r_return, r_error):
    if (spec->impl_ptr != NULL && !spec->dead) {
        arg0 = spec->captures;  jmp spec->impl_ptr;   // 尾调用，栈不动
    }
    // 失效兜底（仅 TOCTOU 路径；正常走 §2.2 的 is_valid → INSTANCE_IS_NULL）
    if (r_error != NULL) {                        // GDExtensionCallError 三字段全写
        r_error->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
        r_error->argument = 0;
        r_error->expected = 0;
    }
    // r_return 为引擎侧已构造 Variant（默认 NIL），thunk 不得手写其 type 字段
    return;

get_argument_count(userdata=spec, r_is_valid):    // 两参数 ABI，非无参直读
    if (r_is_valid != NULL)
        *r_is_valid = (spec->impl_ptr != NULL && !spec->dead);
    return spec->argument_count;
```

- 模板为手写位置无关字节（x86_64 SysV / x86_64 Win64 / aarch64 三变体），离线汇编一次后内嵌为 `static const unsigned char[]`，永久冻结；**运行时零补丁**——spec 经 arg0 寄存器（SysV `rdi` / Win64 `rcx` / aarch64 `x0`）传入，hub 经 `spec->hub`（偏移 48）+ `hub->sweeper`（偏移 40）两次加载。**macOS x86_64 直接复用 SysV 变体**：macOS Intel 遵循同一 System V AMD64 ABI（参数寄存器 `rdi/rsi/rdx/rcx/r8/r9` 一致、16B 栈对齐一致、128B red zone 受 Apple ABI 明确支持），模板零新增；thunk 字节本就是运行时 memcpy 生成，与宿主库编译目标无关，支持 Intel macOS 不要求第四个模板。
- ABI 约束（逐变体核验）：
    - `call`：SysV 第 5 参 `r8`、Win64 第 5 参在 shadow space 后栈上（尾调用不动栈即原样透传）、aarch64 `x0..x4`；
    - `free` → sweeper 纯尾跳：签名一致（`void sweeper(spec*)`），SysV 复用 `rdi`、Win64 复用 `rcx`（原 32B shadow 可用）、aarch64 复用 `x0` 保留 `lr`；
    - `is_valid` 嵌套调用：Win64 须在 `call` 前备好 32B shadow space 且保持 16B 对齐（**无 red zone**；当前模板用 `push rbx` + `sub rsp, 0x20` 同时满足两者，`rbx` 持有 spec）；SysV 须保持 16B 对齐；aarch64 须保存/恢复 `x30` 后 `blr`；
    - `get_argument_count`：SysV `rdi/rsi`、Win64 `rcx/rdx`、aarch64 `x0/x1`。
- aarch64 写后刷指令缓存：GNU 工具链用 `__builtin___clear_cache`，Apple 用 `sys_icache_invalidate`（不可假定对方符号存在）；x86_64 不需要 flush。
- **禁止拷贝库中已编译函数**：编译产物的全局引用走 PC 相对寻址、外部调用走 PLT/GOT，拷贝后仍指向原映像。
- 生成接口隔离 ISA 细节（sljit 为预留升级实现，本期不引入）：`const gdcc_hrx_thunk_template *gdcc_hrx_thunk_template_get(kind)`。
- `object_id` 字段照旧填（lambda 捕获 self 时由 `gdcc_new_lambda_callable` 传入；ObjectID 校验在引擎侧，与库卸载无关）。

### 5.4 可执行内存：探测、模式状态机与共享 thunk 页

- **进程级探测 + 冻结模式**：`initialize()` 在任何 custom Callable 产生之前执行 `gdcc_hrx_execmem_probe()`（完整走一遍 RW→写→RX→执行一个 nop thunk→unmap），三态模式机进程生命周期内冻结：
    - `DIRECT_NON_RELOAD`：非编辑器进程（`gdcc_is_editor_hint()==false`，读于 `gdcc_init()` 缓存 Engine 单例之后）→ 现状 direct 路径；
    - `HRX_ACTIVE`：编辑器进程且 probe 成功 → thunk 路径；
    - `HRX_UNAVAILABLE`：编辑器进程但 probe 失败 → **fail-closed**：custom Callable 创建一律返回无效 Callable 并打印一次性错误。**禁止回退 direct**（`reloadable` 已声明时 direct 会在 reload 后重新引入悬空指针，G7 原病）；也禁止 per-Callable 回退（身份/哈希分叉）。
- **旧 hub 接管**：新代 initialize 发现锚点已有 hub 时，无论本次 probe 结果都必须继续 hrx 接管（旧 spec/thunk 页已存在，切 direct 无法赎回）。**损坏锚点不是接管凭证**：magic/version 失配的 binding 经孤岛协议摘除后，本次 probe 结果仍然门控新 hub 的创建（probe 失败 → `HRX_UNAVAILABLE`）。
- **共享 thunk 页（v14）**：四角色静态 thunk 在 **hub 创建时**一次性写入**同一 OS 页**（RW→memcpy 模板→`mprotect`/`VirtualProtect` RX→aarch64 刷 icache），此后**该页永不再写**——发布时刻任何 Callable 尚不存在，因此"对已发布代码页做保护降级"的窗口从构造上不存在。所有 spec 的四个函数指针都指向这同一页（`thunk_page + 角色偏移`）；页与 hub 同寿命（跨代恒定：重绑后旧 Callable 的函数指针不变；孤儿 hub 的页随之孤岛泄漏，宁漏勿错）。无 thunk 模板的 ISA（如 riscv64）：probe 恒失败 → 编辑器 `HRX_UNAVAILABLE`、非编辑器 direct，导出构建不受影响。验收必测"全部 spec 共享同一组 thunk 函数指针"与"跨代重绑后旧 Callable 函数指针不变"。
- 单次创建失败（thunk 页发布失败/spec OOM）：返回无效 Callable + 错误日志，半成品不得交付——lambda 用当前代 `destroy_fn` 释放已分配 captures；standalone（interned）走 `gdcc_hrx_standalone_payload_free`（其 `destroy_fn` 恒 NULL）。
- 各平台路径：Linux `mmap(RW)`→写入→`mprotect(RX)`；Windows `VirtualAlloc(RW)`→写入→`VirtualProtect(RX)`；macOS（aarch64 与 x86_64 同一路径）`mmap(RW)`→写入→`mprotect(RX)`（**禁用 `MAP_JIT`**——其 entitlement 在编辑器主二进制上；且 `MAP_JIT`/`pthread_jit_write_protect_np` 是 arm64 专属机制，Intel 上无对应物亦不需要；真正失败点是 Hardened Runtime 下的 `mprotect(PROT_EXEC)`——Apple 文档对两架构同样要求 `allow-unsigned-executable-memory` entitlement，实际执法强度按架构分别实机核验，见 §5.10——probe 会在此暴露并走 `HRX_UNAVAILABLE`）；aarch64 写后刷 icache（§5.3），x86_64 不需要 flush；thunk 页大小一律运行时 `getpagesize()`/`GetSystemInfo` 查询（Intel mac 4K、Apple Silicon 16K，不硬编码）。

### 5.5 锚点与 hub 获取

- **锚点**：Engine 单例的 instance binding。token 为 **codegen 生成的 per-extension 稳定 64 位常量**（由扩展输出名/UUID 派生，同一 `.gdextension` 跨代相等、跨扩展不碰撞；仅作相等键，**永不解引用**；注意避开会变化的 `class_library` 指针值）。选择 instance binding 而非 Engine meta：`get_meta/set_meta/remove_meta` 全公开，hub 裸指针可被脚本读取/覆盖/删除，且"先解引用再验 magic"本身即非法访问；instance binding 无脚本 API 可达。`clear_instance_bindings`/`clear_internal_extension` 只处理本 extension token（`GDExtension*` 值）与 slot 0 主 binding（§2.1 步骤 4/5），固定常量 token 的 binding 跨 reload 存活；Engine 对象非扩展实例，`clear_internal_extension` 不适用于它。
- **获取 API 合同（已核验 `object.cpp:2104-2141`）**：
    - **`Object::set_instance_binding` 只写 slot 0**（`_instance_bindings[0].binding` 非空即 `ERR_FAIL_COND` 失败），**禁止**用于锚点——slot 0 可能已被其他绑定占用；
    - 锚点一律走 `Object::get_instance_binding(engine, token, &callbacks)`：内部按 token 线性查找，未命中则**追加新槽**（动态数组、`next_power_of_2` 扩容、无固定上限）并调 `create_callback` 懒创建——多 GDCC 扩展各持不同 token 可共存；
    - **callbacks 语义（已核验 `object.h:673-681`）**：引擎**拷贝** `free_callback`/`reference_callback` 两个函数指针进槽位（`create_callback` 不被保存，仅在本次调用中执行）。因此：拷贝出的两个指针**必须全 NULL**（跨 reload 存活的 binding 绝不允许把库内函数地址留给引擎——free_callback 指向旧库即悬空；进程退出诊断因此放弃，退出泄漏可接受，写入合同）；`create_callback` 为库内函数是安全的（不存储、调用时本代库必然存活），但 **`create_callback == NULL` 时引擎会直接空调用**，不得省略；callbacks 结构体本身位置不限（static const 即可）；
    - **NULL tombstone 陷阱（已核验 `object.cpp:2116-2147`）**：引擎在调 `create_callback` **之前**就追加槽位，且**无论回调是否返回 NULL 都保存结果并递增计数**；查询按 token 线性查找**命中第一个匹配即停**。因此 `create_callback` 失败会留下 NULL tombstone，遮蔽后续一切同名 token 查询（后续 `get_instance_binding` 见 NULL 还会再次追加新槽，形成无限叠加）。合同：① `create_callback` 失败路径必须立即 `object_free_instance_binding` 摘除刚产生的 tombstone；② initialize 的 hub 查询前必须先做**有界 tombstone 清扫**——`object_free_instance_binding` 每次移除首个命中槽（`object.cpp:2165-2185` 已核验：摘除并前移后续槽），循环"查询为 NULL 则 free 一次"直到露出非 NULL（真实 hub 浮现即接管）或达上限 `GDCC_HRX_TOMBSTONE_SWEEP_MAX`（8；`object_has_instance_binding` 不经 GDExtension 暴露，空表与 tombstone 不可区分，no-op free 无害）。超过上限的叠加视为外来篡改，本代新 hub 可能仍被遮蔽，但每代清扫 8 个可在后续代数内自愈。
- **hub 获取**（initialize）：`object_get_instance_binding(engine, token, NULL)` → 返回非 NULL 则校验 `magic`/`version`；返回 NULL 则 `godot_mem_alloc` 创建 hub 并以 `{create_callback=gdcc_hrx_anchor_create, free=NULL, reference=NULL}` 再次 `get_instance_binding` 完成挂载：
    - 匹配 → 接管（含 `HRX_UNAVAILABLE` 历史后的恢复接管）；
    - **失配协议**：不遍历旧 registry、不归还旧页（宁漏勿错）、`object_free_instance_binding(engine, token)` 后以新 hub 重新挂载，旧 hub 成为孤岛直到进程退出（记录诊断计数）。
- Engine 单例对象跨 reload 存活（引擎不重启）；锚点丢失（binding 被异常释放）= 孤岛泄漏，仅"可达 hub"中的死 spec 能在下次成功加载时清扫（§5.6）。

### 5.6 registry 使用点与重绑/清扫

registry 是跨代花名册（非 owning，spec 生死由 Callable 引用计数 + sweeper 决定），四个使用点：

1. **创建登记（O(1) 头插）**：spec 分配并显式初始化（含 `hub` 回指针）→ 入链 → `refcount=1` → 才交付 `callable_custom_create2`（函数指针取自 `hub->thunk_page` 的角色偏移，全 spec 共享）；失败则摘链回收。登记必须先于交付，保证 deinitialize 任意时刻不漏。共享 spec（standalone）查 hub interning 表：**拒绝 `dead` 与失配 spec**；命中存活且 schema 兼容项则 `refcount+1` 复用既有 spec（§5.7）。
2. **deinitialize 遍历置失效**（D8 最后一步）：`sweeper=NULL` → 全 spec 函数指针置 NULL、`binding_state` 置 `UNBOUND_INCOMPATIBLE`（`dead`/`refcount` 不动）。之后旧代码不再被任何路径需要。
3. **新代 initialize 单遍历重绑 + 两阶段清扫**：先注册本代 `sweeper`，再：
    - **阶段一（单遍历，只摘链+重绑，不执行任何析构）**：遍历 registry——`dead && refcount==0` 的 spec 摘入独立 worklist（interned 的同时从 interning 表摘除）；**存活 spec（`!dead`）就地完成重绑**：按 `impl_key` 查本代重绑表，`schema_desc` 逐字节一致 → 三函数指针写入本代实现、`argument_count` 更新、`binding_state=BOUND_COMPATIBLE`（**原地升级到新代码**）；否则保持 `UNBOUND_INCOMPATIBLE`（优雅失效）。captures 一律不动。
      **顺序合同：任何析构发生之前，全部存活 spec 必须先完成重绑**——阶段二析构可能同步释放其他 Callable 使其 spec 变 dead 并入 pending；已重绑的兼容 spec 此时持有本代 `destroy_fn`，pending drain 才能正确析构其捕获（若先析构后重绑，重入变 dead 的 spec 仍处 `UNBOUND_INCOMPATIBLE`，其 captures 将被永久泄漏）。
    - **阶段二（执行）**：对 worklist 逐项：`sweep_depth++` → 析构/释放 → `sweep_depth--` → depth 归零后统一 drain `sweep_pending`（drain 项按 §5.6 点 4 同一逻辑处理）。逐项动作：按 `impl_key` 查本代重绑表，指纹匹配且 `schema_desc` 与表条目**逐字节一致** → 用**表条目的** `destroy_fn` 析构捕获（注意：spec 上的函数指针已在 deinitialize 全部置 NULL，**禁止使用 spec->destroy_fn**——该字段仅供库存活期 sweeper 使用）；失配 → 捕获块整块泄漏（记诊断）；**壳元数据（`impl_key`/`schema_desc`/standalone payload 堆字符串与结构，见 §5.7）无条件释放**（已知 ABI，与 captures 析构分离）；释放 spec 壳（thunk 页归 hub 所有，spec 无 per-spec 代码资源）。
    - 重入守卫：阶段二或库存活期 sweeper 执行 `destroy_fn` 可能同步触发其他 free thunk → 嵌套 sweeper 见 `sweep_depth>0` 时把 spec 挂入 `sweep_pending`（`pending_next` 专用链）即返回；depth 归零后统一 drain。合同：**摘链先于析构，遍历期间不跨析构保留 registry 节点指针**。
4. **库存活期 sweeper**（free thunk 尾调用，单节点操作；仅在 `refcount==0 && dead` 后被触发）：
    ```c
    void gdcc_gen_sweep(gdcc_hrx_spec *spec) {
        if (hub->sweep_depth > 0) { gdcc_hrx_pending_push(hub, spec); return; }  // 重入：只入队（spec->pending_next 专用链）
        if (spec->interned) gdcc_hrx_intern_remove(hub, spec);                   // 先撤索引（按 identity 摘除；lambda 因 interned==0 跳过——禁止以 intern_next==NULL 当判定，单桶 interned spec 的 intern_next 也是 NULL）
        gdcc_hrx_dll_remove(&hub->registry, spec);                               // 再摘链
        hub->sweep_depth++;
        if (spec->binding_state == GDCC_HRX_BOUND_COMPATIBLE && spec->destroy_fn != NULL) {
            spec->destroy_fn(spec->captures);                                    // 本代函数，布局必然兼容
        } // 失配/无析构：不执行未知布局析构（intentional leak，记诊断）
        godot_mem_free((void *)spec->impl_key);                                  // 壳元数据无条件释放
        godot_mem_free((void *)spec->schema_desc);
        if (spec->interned) gdcc_hrx_standalone_payload_free(spec->captures);    // standalone：固定 ABI payload（字符串×3+结构），与 destroy_fn 无关
        godot_mem_free(spec);                                                    // thunk 页归 hub 所有，spec 无 per-spec 代码资源
        hub->sweep_depth--;
        if (hub->sweep_depth == 0) gdcc_hrx_pending_drain(hub);
    }
    ```

- **重绑表**：codegen 生成模块级 `{impl_key, schema_desc+len, fingerprint, impl, destroy, is_valid, argument_count}[]`（lambda 与 standalone 统一编目）。
- **impl_key 稳定源身份合同（新增 frontend 合同）**：lambda 的 key 为 `<Class>::<enclosingFunc>@+Δ<line>:<col>`；standalone 为 `standalone:<kind>:<owner>:<name>`。构造规则与设计依据：
    - **不含文件名**：类名模块内唯一（不允许重名），canonical name 已锚定来源文件；hub 锚点又 per-extension，key 只需模块内唯一——文件名是纯冗余，且会引入"重命名文件打翻全部连接"的额外失效面；
    - **行号取相对偏移**：`Δline = lambda 起始行 − 最外层具名 enclosing 函数起始行`，列号取 lambda 自身列号。绝对行号会被文件中**其他函数**的增删（注释/import/他函数改动）高频打翻；相对偏移使"其他函数增删、本函数整体在文件内移动"均保持 key 不变，唯一剩余失效面是**本函数体内、该 lambda 之前**的增删（位置型 key 不可避免的最小面）；
    - **嵌套 lambda**：沿 `enclosingCallable` 上溯到最外层具名函数取基准，key 始终锚在具名实体上。
    语义：
    - 原地修改 lambda 实现（签名/捕获不变）→ 同一 key → **重绑新实现（热重载主路径，执行新代码）**；
    - 两个 lambda 换位 → 相对偏移变化 → key 不复用 → 旧连接失配失效（fail-closed，**不会绑错 body**）。
    - **禁止**把 lambda 本体内容哈希加入指纹（会把"改 body"误判为失配，打爆主路径）；schema 指纹只服务析构/调用安全（布局+签名+abi_version）。
    - **frontend 合同（改动面小）**：`FrontendLambdaPlan` 已携带 `lambda`/`enclosingCallable` AST 节点（gdparser `Node.range()` 自带源位置）与 `owningClassCanonicalName`——**数据齐备，缺的只是派生计算**：新增 helper 由现有字段算出该 key（可在 plan 上加 `sourceIdentityKey()` 派生方法），透传到后端重绑表发射处；无需 AST/plan 结构变更。
- **schema 指纹与 descriptor**：128-bit 指纹仅用于查找；执行 `destroy_fn` 前必须对 `schema_desc`（捕获字段 offset/size/表示/ownership 分类 + 签名 arity/参数/返回类型序列 + `abi_version` 的 canonical 编码）做长度+逐字节比对（防碰撞误析构）。
- **reload 失败/锚点丢失的死 spec**：定义"安全泄漏"——新库加载失败或扩展被禁用期间释放的 Callable 仅标记 dead，spec/thunk 页/捕获留存至下次同模块成功加载时恢复清扫（仅限可达 hub）；锚点丢失的孤岛 hub 留存至进程退出（记诊断计数，不归入正常无泄漏合同）。
- 线程约束：所有 registry/interning 变更与 sweeper 均在主线程；free thunk 的 `refcount--` 在主线程串行下安全（跨线程全生命周期禁令见 §5.8）。

### 5.7 standalone interning 与共享 spec

- 现状（`gdcc_callable.h:9-12, 335-400`）：同一 `(kind, owner, name)` 共享 interned spec，`free_func` 为 no-op，payload 字符串指向库内 `.rodata`，unload 时由 `gdcc_standalone_callable_registry_destroy_all()` 统一释放；每次 `callable_custom_create2` 产生**独立** custom object（§2.2 free 粒度条款）。
- hrx 模式改造：
    - interning 表为 `gdcc_hrx_hub.intern_table`（hub 拥有，按 identity 字符串哈希）；查找命中即 `refcount+1` 复用同一 spec——§2.2 默认身份为 `(call_func, userdata)` 二元组：v14 后 `call_func` 全 spec 共享（同一 thunk 页同一偏移），相等实际归约为 `userdata`（= spec）同一性，与 per-spec thunk 时代价类完全一致，equal/hash 语义不变；**查找必须拒绝 `dead` 与 schema 失配 spec**（前者 Callable 已全部释放等待清扫；后者是 schema 变更 reload 留下的僵尸，复用会让新 Callable 永久失效——命中僵尸时先将其从 interning 表摘除再新建 spec 占位）；
    - `kind/owner/name` payload 处理：standalone 的 `captures` 为**固定 ABI 的 payload 堆克隆**（`gdcc_standalone_callable_spec` 同构结构 + 三个 `godot_mem_alloc` 字符串副本——`.rodata` 随库卸载，新代读旧 spec 会悬空）。该 payload 布局属已知 ABI（不随用户代码变化），因此其释放**不依赖 `destroy_fn`/schema 匹配**：作为壳元数据在 sweeper/清扫两条路径中**无条件释放**（字符串×3 + payload 结构）；相应地 standalone spec 的 `destroy_fn` **恒 NULL**（payload 析构走固定路径，schema 指纹仅保护 lambda 捕获）；
    - `free` thunk 按 §5.3 顺序：先递减 `refcount`，**归零才置 `dead`** 并 tail-jmp sweeper——禁止"共享 spec + 无计数 free"（首个析构即提前失效且永不回收）；sweeper 释放前必须先从 interning 表摘除（防同 identity 再创建命中尸检 spec），顺序见 §5.6 点 4；
    - `gdcc_standalone_callable_registry_destroy_all()` 与 hub interning 表职责切分：direct 模式维持现状（registry 统一释放）；hrx 模式下 interned spec 生命周期归 hub（registry 表本身不再登记），**禁止两套 registry 释放同一对象**。

### 5.8 双模派发与线程合同

```c
static gdcc_hrx_mode g_hrx_mode;   // §5.4 三态，initialize 时冻结

// Callable 创建点（gdcc_callable.h 既有两个入口内部分流）：
HRX_ACTIVE        → thunk 路径（spec 入 hub->registry / interning 表）
DIRECT_NON_RELOAD → 现状路径（CallableCustomInfo2 直指库内函数，观察等价）
HRX_UNAVAILABLE   → 返回无效 Callable + 一次性错误（fail-closed）
```

- 门控语义：reload 只发生在编辑器进程；非编辑器路径不分配可执行堆、不建 hub、不挂锚点，行为与现状观察等价（`gdcc_callable.h` 仅多一条分支）。
- **协程 signal waiter 政策**（`gdcc_coro_signal_connect_wait`，`gdcc_coroutine.c:185-191`）：**纳入 hrx 但不提供重绑条目**——reload 后恒失效（`impl_ptr=NULL`，信号静默跳过），杜绝漏网 waiter 跳旧库；正常路径依赖 D8 顺序（`cancel_all` 先于 hub 失效置空）提前拆边。HR-8 验收必含"cancel 漏网时不得跳入已卸载库"。
- **线程禁止合同（v1）**：custom Callable **全生命周期**不得离开主线程——创建、复制、存入容器、连接/断开、调用、**最终释放**（worker 上丢弃最后一个 Variant 副本会在 worker 线程执行 free thunk，与主线程 deinitialize/重绑/清扫竞争）。这是用户合同而非 runtime 机械保证：违反即数据竞争，runtime 不拦截（写入 §7）。完整支持（generation quiescence：active-call pin + call-return trampoline + 原子状态机）列为 §8 后续项。注意 `Callable(对象,"方法名")` 的既有线程语义不受本合同影响。

### 5.9 失效与降级语义

| 场景 | 行为 |
|---|---|
| 存活 lambda，签名+捕获布局未变 | 重绑到新实现，跨 reload 连接**执行新代码** |
| 捕获布局/签名失配 | `UNBOUND_INCOMPATIBLE`：`is_valid=false` → 信号静默跳过；`call()` → 引擎标准错误；捕获块清扫时整块泄漏（不误析构） |
| lambda 被删除/换位导致源身份 key 不复用 | 同上（无匹配条目；**不会绑错 body**） |
| 本函数体内、lambda 之前增删行 | 相对行偏移变化 → 假失效（位置型 key 的最小失效面）；**其他函数增删、本函数整体移动不影响 key** |
| 跨 reload 的 Callable 析构 | free thunk → sweeper（已是新代函数）按 `binding_state` 分流回收 |
| deferred 队列/Variant 中的副本 | 与普通连接一致（spec 共享，refcount 保护） |
| 编辑器内 execmem 不可用 | `HRX_UNAVAILABLE`：custom Callable 创建即无效 + 一次性错误；永不回退 direct |
| `Callable(对象,"方法名")` | 引擎 `try_update` 更新 bind，始终有效（与本方案正交） |

### 5.10 平台适配与待验证项

- 平台矩阵：Linux x86_64（SysV 模板）、Windows x86_64（Win64 模板，第 5 参栈传/无 red zone）、**macOS x86_64（复用 SysV 模板**——macOS Intel 同为 System V AMD64 ABI；execmem 走 macOS 路径，`MAP_JIT` 为 arm64 专属不涉及；官方编辑器为 universal2，Intel Mac 运行 x86_64 切片故该目标必须覆盖**）**、Linux aarch64 与 macOS aarch64（arm64 模板 + icache flush 按工具链）；macOS 失败点为 Hardened Runtime 下 `mprotect(PROT_EXEC)`（两架构），由 probe 暴露并 fail-closed。
- 待验证（并入 §6 HR-0）：Engine 单例 instance binding 以固定 token 跨 reload 存活（含 `clear_instance_bindings` 不触及的实证）；macOS 无 entitlement RW→RX 实机验证（**aarch64 与 x86_64 分别核验**——Apple 文档要求一致，但两架构历史执法强度可能不同，且 Intel 机还需覆盖老 macOS 版本）；`is_editor_hint` 在编辑器进程/编辑器启动的游戏进程/导出包三处取值。

## 6. 分步实施与验收细则

### HR-0 残余契约核对（0 代码改动）✅ 已完成

> 实施状态：已完成（2026-09）。四项未决项全部关闭，结论见文末附录 A；未推翻 §2/§4/§5 任何条款。

§2 主体事实已按 4.5.1-stable 核验完毕，仅保留以下真正未决项：
1. 协程状态对象的 token binding（`gdcc_coro_binding_token()`）在引擎 `clear_instance_bindings`/`clear_internal_extension` 中的清理路径：确认 token 与本 extension library 的关联方式，保证 reload 时旧 binding 被释放、recreate 重建无冲突。
2. Linux/macOS 上验证 gdcc 产物 dlclose 真正卸载的方法学（`/proc/<pid>/maps`、loader 日志），并审计 runtime 是否已无意引入 TLS/线程。
3. ~~headless editor 脚本驱动 reload 的自动化可行性~~ **已结论（v8）：可行**。`GDExtensionManager.reload_extension(path)` 同步强制重载单扩展且脚本可调用；`--headless --editor --script`（继承 `SceneTree`/`MainLoop`）为官方支持路径。落地方案见 HR-9 自动化节。
4. §5.10 三项：Engine 单例固定 token instance binding 跨 reload 存活实证；macOS 无 entitlement RW→RX 实机验证；`is_editor_hint` 三进程环境取值。

- **验收细则**：结论追加至本文档附录；若推翻 §2/§4/§5 任何条款，先修订本文档再继续。

### HR-1 `.gdextension` 输出 `reloadable = true` ✅ 已完成

> 实施状态：已完成（2026-09）。`GdextensionMetadataFile.render(...)`/`renderMultiPlatform(...)` 均在 `[configuration]` 段固定输出 `reloadable = true`（共享常量 `RELOADABLE_LINE`）；`GdextensionMetadataFileTest` 已补齐 `render()` 入口断言并更新 multi-platform 快照，`script/run-gradle-targeted-tests.sh --tests GdextensionMetadataFileTest` 通过。末条验收（test_project 重生成 + 编辑器告警消除）移交 HR-9 场景 1 验证。

- **改动点**：`GdextensionMetadataFile.java` 的 `render(...)` 与 `renderMultiPlatform(...)` 均在 `[configuration]` 段固定输出 `reloadable = true`（与 godot-cpp 模板一致；release 导出由引擎忽略 reload，无需 CLI 开关）。
- **验收细则**：
    - 更新 `GdextensionMetadataFileTest`：**两个**渲染入口都断言新字段（现有测试只覆盖 `renderMultiPlatform`，须补 `render()`）；
    - `script/run-gradle-targeted-tests.sh --tests GdextensionMetadataFileTest` 通过；
    - 重新生成 `test_project` 的 `.gdextension` 后含该字段，编辑器不再打印"不具备热重载条件"告警。

### HR-2 free_instance 幂等全量析构 ✅ 已完成

> 实施状态：已完成（2026-09）。D2 落地：根 wrapper 新增 `_gdcc_destructed`（`entry.h.ftl`，仅根段，派生类经 `_super` 链访问——`CGenHelper.renderDestructedFlagAccessExpr`，与 vtable 字段访问共抽 `renderRootWrapperFieldAccessExpr`）；析构拆分为无守卫链 `<C>_class_destruct_fields`（derived→base 递归）与带守卫入口 `<C>_class_destructor`；`<C>_class_free_instance` 判守卫后调守卫入口再 `godot_mem_free`；create 显式置 `false`。协程状态类经核对**不接入**该标志（其 PREDELETE 仅 cancel、free_instance 是唯一清理点，两条引擎路径各调一次，天然 exactly-once）。`explicit_c_inheritance_layout_contract.md` 已同步 §1/§3/§8。新增 `freeInstancePerformsExactlyOnceGuardedFullDestruction` 测试（含基类+派生类 destroyable 字段防截断用例），`CCodegenTest,CCoroutineStateClassCodegenTest,CVtableCodegenTest` 与 `ObjectOwnershipCornerCaseIntegrationTest,ObjectReturnConsumptionLeakIntegrationTest`（Godot 实机，PREDELETE→free 路径）全部通过。

- **改动点**：按 D2 拆分守卫入口与字段析构链；根 wrapper 加 `_gdcc_destructed`（同步 `explicit_c_inheritance_layout_contract.md`）；`free_instance_func` 升级为"未析构则先析构再释放"；create 显式置 `false`；全量走查 free_instance 可达析构路径确认无新增 intern 宏依赖（D3）。协程状态类 `free_instance`（`${stateName}_class_free_instance`）本已承担字段清理，核对与 cancel/惰性壳（HR-3）组合的 exactly-once，必要时接入同一标志。
- **验收细则**：
    - 模板测试断言：守卫只在 `<C>_class_destructor` 入口、链式 `<C>_class_destruct_fields` 无守卫、free_instance 调守卫入口、标志字段位于根段；
    - **必须**含"基类与派生类均有 destroyable 字段"的用例（防 D2 截断回归）；
    - `script/run-gradle-targeted-tests.sh --tests CCodegenTest,CCoroutineStateClassCodegenTest` 通过；
    - 现有 `GodotGdextensionTestRunner` 驱动的集成测试不回归（正常 PREDELETE→free 路径）。

### HR-3 recreate_instance_func 生成 ✅ 已完成（双审阅闭环）

> 实施状态：已完成（2026-09）。D4/D5 落地：用户类生成 `<C>_class_recreate_instance`（返回 wrapper、`set_object_ptr`→vtable 重绑（与 create 共用 `renderVtableFieldInitExpr`）→`_gdcc_destructed=false`→`set_instance_binding`→新增 base-first 递归 helper `<C>_class_init_fields`；不含 construct/set_instance/POSTINITIALIZE/RefCounted init/`_init`）与协程状态类 RELOADED_SHELL 惰性壳 recreate（wrapper 整体 `memset` 置合法默认值→`header_init` 新代 descriptor→`reloaded_shell=true`→coro token binding；runtime header 新增 `reloaded_shell` 终态字段，`gdcc_coro_finalize`/`gdcc_coro_cancel`/`gdcc_coro_register_waiter` 三处对壳短路——await 壳立即返回确定取消结果不挂起）。两类 creation info 均强制填入 `recreate_instance_func`，两处 recreate 均含 alloc OOM 守卫（返回 NULL 供引擎优雅降级）。测试：`recreateInstanceRebuildsWrapperStateWithoutTouchingGodotObject`（CCodegenTest）、`coroutineStateClassRecreateBuildsReloadedShell`（CCoroutineStateClassCodegenTest，5 状态类全覆盖+OOM 顺序锚定）、CVtableCodegenTest 四角色 recreate vtable 断言、`GdccCoroutineRuntimeSmokeTest.reloadedShellShouldShortCircuitFinalizeCancelAndAwait`（壳终态执行级锚定：finalize/cancel 短路、await 立即返回、consume 合同驱动壳 free 幂等）。双审阅修复：HIGH（alloc 未检查）、MEDIUM（壳无执行级测试）及全部 LOW 已闭环；§7 用户合同补 initializer 副作用重放条款。全部单测与 ObjectOwnership 集成测试通过。

- **改动点**：按 D4 为用户类生成 recreate 并填入 creation info（`entry.c.ftl:66-78`）；按 D5 惰性壳合同为协程状态类生成 recreate（`entry.c.ftl:90-100`），含 header 新终态与 owning 字段安全初始化；新增 `<C>_class_init_fields` 递归 helper（base-first、无 `_init`）。
- **验收细则**：
    - 模板测试断言：两类 creation info 均填 `recreate_instance_func`（协程状态类缺失会禁用整个扩展 reload，必须强制断言）；
    - recreate 函数体断言：**含** `return self`（wrapper 类型）、vtable 写入、`set_instance_binding`（状态类为 coro token）；**不含** `classdb_construct_object2`、`object_set_instance`、POSTINITIALIZE、`_init`、`<C>_class_constructor`；
    - vtable 写入覆盖四角色 golden test：introducer（自有表）、override-only（最近 introducer 表）、pass-through（祖先共享表值）、旁支/无 slot（NULL/无赋值），与 create 输出逐一一致；
    - `script/run-gradle-targeted-tests.sh --tests CCodegenTest,CVtableCodegenTest,CCoroutineStateClassCodegenTest` 通过。

### HR-4 deinitialize 逆序类注销 ✅ 已完成（D8 经 v11 修订）

> 实施状态：已完成（2026-09）。D8 注销段落地：`deinitialize()` 在卸载打印之后按 **v11 修订顺序**执行——`gdcc_coro_cancel_all()`（HR-5，条件生成）→ static backing 逆序销毁 → 类注销段（先协程状态类按生成序严格逆序（嵌套 `?reverse` 全局反转）、再用户类按 `inheritanceOrderedClassDefs?reverse`，统一调 `godot_classdb_unregister_extension_class`）→ registry 销毁。**v11 修订**：首轮双审阅发现原 D8"类注销先于 static 销毁"在正常退出路径构成 UAF（`_unregister_extension_class` 非 reload 分支直接 erase 使 `_extension` 悬空，static backing 持有的实例析构时经悬空指针回调）——经用户确认后将 static 销毁与类注销对调（两条路径均安全，论证见 §4 D8）。**复核轮再修一处 BLOCKING**：HR-2 引入的 Object 释放路径在 reload 批量 clear 下经 fat ptr 缓存 wrapper 取 raw object 构成 UAF（wrapper 生命 ≠ 对象生命）——已统一改为 `gdcc_object_live_ptr(instance_id)`（ObjectDB 权威），覆盖 `renderManagedStorageFreeStmt`（capture/param/ret-slot/static）与 `destruct_fields`；D3 纪律同步扩展（不得依赖 static backing + Object 释放一律 ObjectDB）。测试：`deinitializeUnregistersClassesInStrictReverseRegistrationOrder`（乱序 module 拓扑轴 + abstract + static→注销→registry 全序）、`coroutineStateClassesUnregisterInGlobalReverseGenerationOrder`（两类四协程全局逆序双轴 + static 联合位置锚）、D2 测试 Object 字段 ObjectDB 正反断言、协程 Object param/ret-slot ObjectDB 锚定。三轮审阅全部闭环（APPROVE）。

- **改动点**：`entry.c.ftl` `deinitialize()` 按 D8（v11）插入注销段：协程状态类按生成序严格逆序、用户类按 `inheritanceOrderedClassDefs` 逆序，调用 `godot_classdb_unregister_extension_class`（`godot_interface.h:873`）；static backing 销毁移至类注销段**之前**。
- **验收细则（v11 修订）**：
    - 模板测试断言注销序列是注册序列的严格镜像反转，且类注销段位于 registry 销毁之前、static backing 销毁位于类注销段之前；
    - `script/run-gradle-targeted-tests.sh --tests CCodegenTest` 通过；
    - HR-9 流程无 "Attempt to unregister class while other extension classes inherit from it" 报错。

### HR-5 协程活跃注册表与统一取消

> 实施状态：已完成（2026-09，双审阅闭环；**2026-09 追加双模运行时门控**）。runtime 层落地：header 新增 `signal_reg`（signal 挂起登记，connect 前分配、成功后发布、free callback 依身份守卫清除）与 `active_prev/active_next`（模块本地侵入式活跃链表，非持有）；`gdcc_coro_active_link()` 由 start thunk 在 `mco_create` 成功后、首次 `mco_resume` 前调用（OOM 态与 RELOADED_SHELL 从不入链），`finalize`/`cancel`/`state_free` 幂等 unlink。`gdcc_coro_cancel_all()` 摘头循环（释放 waiter 边可能级联 unlink/free 其他节点，故每轮重读 head）：unlink → 临时强引用保活 → `gdcc_coro_signal_detach`（以 Godot 默认 custom Callable 相等性 `call_func + userdata` 重建**查找钥匙**断开 one-shot 连接——钥匙的 free callback 为 NULL，wait 由连接自身句柄 exactly-once 释放；**disconnect 经局部 Signal 副本**：引擎 `_disconnect` 在 erase 同步销毁登记后仍读 `p_signal`，直接传 `&reg->sig` 构成 UAF——首轮审阅发现并已修复，fake 层 Signal 真析构 + disconnect 后读 name 使回归可被测试捕获）。**双模门控（方案 B，双审阅闭环）**：模块全局 `gdcc_hot_reload_active` 由生成的 `initialize()` 在 `gdcc_init()` 后、首个类注册前按 `is_editor_hint()` 设置——仅编辑器进程为 true；为 false（release 导出、F5 游戏进程、未设置的纯 C fixture）时 `active_link`/`cancel_all` 为 no-op、signal_reg 不分配，协程行为与 HR-5 之前完全一致、零跟踪开销；普通生命周期（await/finalize/PREDELETE cancel）与 recreate/RELOADED_SHELL 刻意不受门控（creatable 类缺 recreate 会被引擎禁用整个扩展 reload）。模板：`deinitialize()` 打印后、static 销毁前条件调用（`hasCoroutineFunctions()`）；start thunk 插入 link 调用；initialize 条件插入门控设置。测试：`GdccCoroutineRuntimeSmokeTest` 七 probe——`cancelAllShouldDetachSignalsAndCancelEveryActiveState`（四态混合主场景）、`cancelAllShouldFreeConnectionEdgeOnlyStateInsideTheLoop`（fire-and-forget 仅连接边保活，free 发生在循环内）、`cancelAllShouldCascadeWaiterEdgeOnlyAwaiterWithoutDanglingHead`（awaiter 仅 waiter 边保活，嵌套销毁 + head 重读安全）、`signalRegistrationShouldSurviveResuspensionAndEmitterDeath`（同次发射二次挂起身份守卫 + emitter 先死亡清登记）、`hotReloadGateOffShouldDisableTrackingAndBulkCancel`（门控正反两面，含 gate-on 后 re-link 前的空链表证明——覆盖单节点盲区）、`gateOffEmitterDeathShouldDriveOrdinaryPredeleteCancel`（gate-off 下 emitter 死亡走普通 PREDELETE 退出路径）、`signalRegAllocationFailureShouldFailTheAwaitCleanly`（reg OOM 失败注入：两次诊断 + nil + 无连接无边无挂起）；codegen 锚定 initialize 门控位置（gdcc_init 后、首个 `register_extension_class5` 前）与恰好一次、无协程 module 双负断言、deinitialize 顺序链、start thunk link 位置与 OOM/recreate 不 link。真引擎回归：`compilesAndValidatesCoroutineScripts`（22 例）与 `compilesAndValidatesLambdaScripts` 全绿。review-expert-a/c 双审阅（首轮 + 门控轮）：HIGH（detach Signal UAF + 最后保活边路径测试缺口）与 MEDIUM（gate-off link no-op 证明盲区）全部修复并复核 CONFIRMED/APPROVE。HR-9 场景 4（真引擎 reload 无协程泄漏）仍属最终验收。

- **改动点**：`gdcc_coroutine.h`/`gdcc_coroutine.c`：按 D5 实现活跃链表（不变量见 D5）、signal waiter 挂起边登记与断开、`gdcc_coro_cancel_all()`；`entry.c.ftl` `deinitialize()` 在打印之后、**static backing 销毁之前**调用（D8 v11：保守起见于一切资源存活时执行取消-resume 的 `__finally__` 清理）。
- **验收细则**：
    - 纯 C runtime 测试（新增或扩展现有协程测试）：取消过程中释放相邻节点、嵌套 waiter 级联、重复调用 `cancel_all`、signal emitter 在取消后仍存活且 signal 永不触发、断开过程 free callback 重入；
    - `script/run-gradle-targeted-tests.sh --tests CCoroutineStateClassCodegenTest`（及新增测试类）通过；
    - 取消语义符合 abandonment 合同：无 `completed` 发射、无 waiter resume（HR-9 场景 4 验证）。

### HR-6 Callable 跨 reload 合同落地

> 实施状态：已完成（2026-09）。随 HR-8 同步落地：`doc/gdcc_runtime_lib.md` 重写 `gdcc_callable.h` 条目为三态分流合同并新增 §HRX Hot-Reload Thunk Runtime 总章节（模式机/thunk/共享 thunk 页/hub/锚点/重绑清扫/协程 waiter/线程合同）与 `gdcc_hrx` 编译接线，协程 `cancel_all` 段补 HRX retain 查等键路径；`doc/gdcc_c_backend.md` 的 GDExtension Entry Lifecycle Contract 补 initialize 模式冻结（先于类注册）与 deinitialize 第 6 步 hub 失效置空（D8 末尾），删除"HR-8 未发射"保留注记。用户可见合同仍由本文 §7 承载（HR-9 端到端验收时随场景验证）。

- **改动点**：随 HR-8 落地修订长期文档（`doc/gdcc_c_backend.md`、`doc/gdcc_runtime_lib.md`）：编辑器内 custom Callable 跨 reload 安全且按 §5.9 语义升级/失效；execmem 不可用时 fail-closed；非编辑器维持现状；worker 线程调用禁令；`Callable(对象, "方法名")` 连接跨 reload 始终有效；`to_string` 在 thunk 路径显示 `<CallableCustom>`。
- **验收细则**：文档合并；HR-9 场景 5/5a/5b/5c/10/10b 覆盖 thunk 与 direct 双路径。

### HR-8 堆驻留 thunk Callable 间接派发

> 实施状态：已完成（2026-09，§5 全合同落地，v14 共享 thunk 页形态）。**runtime**：新增 `gdcc_hrx.h/.c`——三变体手写位置无关 thunk 模板（零运行时补丁：spec 经回调 arg0 传入、hub 经 `spec->hub` 回指针获取，全部经 zig 汇编器逐字节核验并静态断言尺寸）、**共享 thunk 页**（hub 创建时一次性 RW→写→RX 发布四角色 thunk 到单页，此时无任何 Callable 存在，此后永不再写——"对已发布代码页降级"窗口从构造上不存在；v14 前为 per-spec slab 槽位，因 RX 恢复失败可波及已发布 sibling 的 BLOCKING 而废弃）、hub/spec（显式初始化、ABI 静态断言、spec 增 `hub` 回指针偏移 48）、standalone interning（hub 表、拒绝 dead/失配 spec、僵尸先摘除再新建、共享 spec 经默认身份 `(call_func, userdata)` 保持相等语义）、free thunk"先递减归零才置 dead"顺序、sweeper（intern 先摘除、重入 pending 队列 depth 归零 drain）、新代单遍历重绑+两阶段清扫（析构只用重绑表条目、指纹+desc 双层闸门、失配宁泄漏不误析构、壳元数据无条件释放）、Engine 单例 instance binding 锚点（`get_instance_binding` 追加语义、callbacks free/reference 全 NULL、失配孤岛协议且**失配后仍需过 probe**）、execmem probe 三态模式机（旧 hub 存在则无视 probe 结果接管；无模板 ISA probe 恒 false 保住导出构建）、`gdcc_hrx_callable_retain` 查等键。**分流**：`gdcc_callable.h` 两个创建入口按 `gdcc_hrx_get_mode()` 内部分流，UNAVAILABLE fail-closed 且消费 captures；standalone payload 改 `gdcc_hrx_standalone_payload` 固定 ABI 堆克隆（`gdcc_standalone_callable_spec` 成为其别名）；协程 signal waiter 经 `gdcc_new_lambda_callable_ex` 取回 spec 句柄存于 reg，bulk-cancel detach 在 thunk 模式改走 `gdcc_hrx_callable_retain`（同一 spec 身份），waiter 无重绑条目。**codegen**：`CHrxIdentityCatalog` 编目全模块 lambda/standalone 身份（impl_key 一律用 **resolve 后声明类 owner** 规范化——继承静态经子类/父类双路径引用为同一身份，canonical schema_desc=捕获/签名 C 存储类型编码、MD5 128-bit 指纹、数据化 argument_count），`entry.c.ftl` 顶部发射锚点 token（模块名 MD5 派生 64 位常量）+ identity 结构体重用创建点与重绑表，initialize 在类注册前调 `gdcc_hrx_initialize`，deinitialize 在 D8 末尾调 `gdcc_hrx_deinitialize`；standalone spec 解析抽取 `StandaloneCallableSpecSupport` 供发射点与编目共用。**frontend**：`FrontendLambdaPlan.sourceIdentityKey()`（`<Class>::<enclosingFunc>@+Δline:col`，构造器作 `_init`，相对偏移+1 基列）经 `LirFunctionDef.sourceIdentityKey`（XML `source_identity_key` 往返保留）透传后端；无 key lambda codegen fail-fast。**测试**：`GdccHrxRuntimeSmokeTest` 21 探针（三变体字节精确冻结+bundle 累计尺寸双锁、execmem probe、真机执行 thunk 往返、重绑升级/失配 fail-closed、共享 spec 引用计数顺序、dead 拒绝、防下溢（同 userdata 重复 free 形态）、重绑表 destroy 双层闸门、清扫重入 drain、**共享 thunk 页**（四角色全 spec 同址、跨代指针恒定、发布期字节快照 reload 后 memcmp 恒等、页不随代重建）、schema 失配 standalone 僵尸摘除后新建、锚点共存/callbacks/失配孤岛/失配+probe 失败冻结 UNAVAILABLE、**NULL tombstone 单槽清扫接管与叠加三槽清扫接管**（fake 引擎全真模拟 object.cpp 追加/摘除语义，探针经 `-DGDCC_HRX_TEST_HOOKS` 独有的 image 状态重置钩子在每次 re-init 真正重入 UNINITIALIZED 决策分支）、UNAVAILABLE fail-closed 消费 captures、direct 零回归、waiter 永不重绑、retain 查等键、三 ISA 交叉编译），`GdccCoroutineRuntimeSmokeTest` 增 HRX 模式 signal detach 集成探针（thunk 身份+retain detach+exactly-once），`CCodegenTest` 五个 golden/语义/负向测试（含 inherited standalone canonical 去重），`FrontendLambdaPlanSideTableTest`/`FrontendLambdaSuiteResolutionTest`/`FrontendLambdaLoweringTest`/`DomLirSerializerTest` key 正反锚定。`script/run-gradle-targeted-tests.sh` 相关测试类全绿。HR-9 场景 5a/5b/5c/10/10b 待 HR-9 落地时验证。

- **改动点**（合同见 §5 全文）：
    - 新增 runtime 模块 `gdcc_hrx.h/.c`（三变体零补丁 thunk 模板表、共享 thunk 页发布、hub/spec 管理、registry 与 interning 表操作、sweeper 与 pending 队列、重绑表消费、execmem probe、锚点 binding）；
    - `gdcc_callable.h` 两个创建入口按 §5.8 三态分流；standalone interning 在 hrx 模式移入 hub（payload 字符串堆拷贝，§5.7）；
    - codegen：生成模块级重绑表（lambda + standalone 编目，`impl_key`/canonical `schema_desc`/128-bit 指纹/三函数指针/`argument_count`）；锚点 token 常量（per-extension 稳定 ID 派生）；协程 waiter 走 hrx 但无重绑条目（§5.8）；
    - **frontend 小改**（数据已齐备）：从 `FrontendLambdaPlan` 现有字段派生 `impl_key`——`owningClassCanonicalName` + 最外层具名 `enclosingCallable` 及其与 `lambda` 的 `range()` 起点差（helper 或 plan 派生方法 `sourceIdentityKey()`），透传到后端重绑表发射处；无需 AST/plan 结构变更；
    - `entry.c.ftl`：`initialize` 探测并冻结模式、获取/创建 hub（锚点走 `get_instance_binding`；拷贝进引擎的 free/reference 指针必须为 NULL、`create_callback` 不可省略、callbacks 结构体可为 static const）、注册 sweeper、执行重绑+两阶段清扫；`deinitialize` 按 D8 末尾执行失效置空。
- **验收细则**：
    - thunk 模板 golden test：三变体字节序列冻结断言（零补丁；含 `get_argument_count` 两参数 ABI、`is_valid` 嵌套调用帧、free 经 `spec->hub` 二次加载 sweeper）；
    - 纯 C 单元测试（host 侧直接驱动，不经 Godot）：spec 创建/登记；**free 顺序**（共享 spec 首次 free 不失效、归零才 dead+sweep）；refcount 防下溢；sweeper 的 intern 摘除先于析构（同 identity 再创建不命中尸检 spec，含单桶 interned spec）；dead 幂等；**共享 thunk 页**（全部 spec 的四个函数指针指向同一页同一偏移、跨代重绑后旧 Callable 函数指针不变、hub 页与 spec 生命周期解耦）；schema 失配 standalone 僵尸不得被新代同 key 创建复用（摘除后新建 fresh spec，旧 Callable 保持 fail-closed）；两阶段清扫重入（destroy_fn 触发另一 free → pending 队列经 `pending_next`、depth 归零 drain）；descriptor 逐字节比对（指纹匹配但 desc 不同 → 不执行 destroy_fn）；新代阶段二只使用**重绑表条目**的 destroy_fn（spec 字段已置 NULL）；standalone payload 无条件释放（失配场景亦不泄漏壳）；壳元数据与 captures 分离释放；重绑按 `binding_state` 匹配/失配 + 独立 `spec->dead` 分流（禁止复活第三种 binding_state 枚举值）；锚点 callbacks 拷贝出的 free/reference 指针全 NULL、`create_callback` 仅在获取期执行；锚点获取/失配协议（含**失配+probe 失败 → UNAVAILABLE**）、**slot 0 已被占用时经 get_instance_binding 追加成功**、两个 GDCC 扩展 token 共存；probe 失败 fail-closed；
    - impl_key 源身份：同 key 改 body → 重绑新实现（主路径）；两 lambda 换位 → 旧连接失效而非绑错 body；
    - 协程 waiter 漏网防护测试：cancel_all 后无 waiter Callable 可跳入已卸载库；
    - HR-9 场景 5a/5b/5c/10/10b 全部通过；
    - `script/run-gradle-targeted-tests.sh` 相关新旧测试类通过。

### HR-9 端到端验收（编辑器手测清单，最终步骤）

- **前置**：HR-1 ~ HR-6、HR-8 全部完成；`test_project` 可正常编译加载。
- **步骤覆盖矩阵**（每个前置步骤至少被一个场景验收）：

| 步骤 | 验收场景 |
|---|---|
| HR-1（reloadable 元数据） | 场景 1（编辑器获焦自动 reload 的前提成立） |
| HR-2（free_instance 幂等全量析构） | 场景 9（正常 PREDELETE→free、reload free→recreate、recreate 后再析构、基类+派生类 destroyable 字段） |
| HR-3（recreate 生成） | 场景 1（实例存活/方法新逻辑）、2（属性恢复）、3（vtable 重写与虚分派）、9（reload free→recreate、连续两次 reload） |
| HR-4（逆序类注销） | 场景 1（全流程无 "Attempt to unregister class while other extension classes inherit from it" 报错） |
| HR-5（协程统一取消） | 场景 4（取消语义）、9（协程 param/capture/返回槽/result cache exactly-once） |
| HR-6（Callable 合同落地） | 场景 5/5a/5b/5c |
| HR-8（堆驻留 thunk） | 场景 5a/5b/5c/10/10b |

- **场景与通过标准**（Godot 编辑器，`--verbose` 可选）：
    1. 基础：场景中挂 gdcc 类节点（含导出属性赋值），修改方法实现 → 重新编译 → 编辑器获焦自动 reload → 节点存活、导出属性值保留、方法执行**新**逻辑、无崩溃无报错。
    2. 属性保留：非导出实例属性（`PROPERTY_USAGE_NO_EDITOR`，含 STORAGE）在运行时被改过后，reload 后**保留**（引擎语义）；等于 ClassDB 默认值的属性不保存不报错。
    3. 继承与多态：派生类实例 reload 后虚方法分派正确（至少覆盖 override-only 与 pass-through 两角色）；带 `_process` 的 Node 子类 reload 后下一帧仍进入**新库**实现（引擎 virtual 重挂验证；注意 `_ready` 不会由 reload 重发——引擎只发 `NOTIFICATION_EXTENSION_RELOADED`，`_ready` 仅在节点重新进树时再执行，不作为重挂验证手段）。
    4. 协程：reload 时存在挂起协程 → 被静默取消（abandonment：无 `completed`）、等待方协程亦被取消、无崩溃、无 minicoro 栈泄漏。
    5. 方法名连接：`Callable(对象, "方法名")` 信号连接跨 reload 后仍触发且执行新逻辑。
    5a. lambda 连接跨 reload：存活节点持有 lambda 信号连接 → reload 后触发信号执行**新 lambda 逻辑**（thunk 重绑验证，§5.9 行 1）。
    5b. lambda 失效路径：修改 lambda 捕获结构或删除 lambda → reload → 旧连接 `is_valid=false`、信号静默跳过、无崩溃（§5.9 行 2/3）。
    5c. deferred lambda：reload 前排队的 lambda deferred 调用，reload 后按重绑/失效语义执行，无崩溃（§5.9 行 5）。
    6. 签名变更：修改某方法签名 → reload → 经旧缓存 bind 的调用报 invalid（引擎已知语义），不崩溃。
    7. 父类变更二分：改为引擎父类/不存在父类 → 引擎报错要求重启；改为另一个 GDCC 类 → 引擎不检查，列为不支持项，验证无静默损坏声明写入用户合同。
    8. 静态变量：static var reload 后重置为初始值。
    9. 生命周期矩阵（计数器/探针 + ASan/Valgrind）：正常 PREDELETE→free；reload free→recreate；recreate 后再正常析构；连续两次 reload；基类+派生类 destroyable 字段；协程 param/capture/返回槽/result cache 的 exactly-once——无 double-free、UAF、残留栈。
    10. 双模回归：headless（非编辑器）运行 `GodotGdextensionTestRunner` 既有集成测试全绿（direct 路径零回归、无可执行堆分配）。
    10b. 编辑器启动的游戏进程（F5 运行 `test_project`）：断言无 execmem 分配（direct 路径）、lambda 行为与 headless 一致（验证 §5.8 三进程取值结论）。
- **自动化（正式交付项，v8 起）**：新增 `GodotEditorHotReloadTestSession`（与 `GodotGdextensionTestRunner` 并列，复用其 artifact 安装、双流读取、超时强杀、`GODOT_BIN`/Zig 探测与 assumption-skip 模式；runner 本体不动）。
    - **实施状态（2026-09，HR-1~HR-5 部分已落地）**：`GodotEditorHotReloadTestSession`（`src/test/java/gd/script/gdcc/backend/c/build/`）已实现——editor capability probe（`--headless --editor --quit` 退出码，进程内缓存）、原子 rename 换库（staging 文件 + `ATOMIC_MOVE`）、双阶段 marker 仲裁（含 `HR_FAIL` 快速失败与提前退出检测）、超时强杀；`GodotEditorHotReloadIntegrationTest` 4 个测试方法覆盖场景 1+2（实例存活/属性恢复/新代码/无注销报错）、3（@tool 继承链虚分派 + `_process` 引擎重挂新实现）、4（挂起协程与等待方的 abandonment 可观测半边：等待方不恢复、被取消 body 不续跑、stale 信号无效、新代协程正常完成；独立 `completed` 发射观测不在自动化腿内）、9 行为段（正常 PREDELETE→free、reload free→recreate、recreate 后再析构、连续两次 reload、基类+派生类 destroyable 字段内容恢复、挂起协程含参数槽的取消——无 sanitizer 的自动化腿证明崩溃自由与值正确，exactly-once 与泄漏证明留在手测 ASan/Valgrind 清单）。`script/run-gradle-targeted-tests.sh --tests GodotEditorHotReloadIntegrationTest` 全绿（4/4，无 skip）。场景 5/5a/5b/5c/6/7/8/10/10b 待 HR-6/HR-8 落地后补齐。
    - **已核验的引擎事实（4.5.1-stable）**：`GDExtensionManager.reload_extension(path)` 脚本可直接调用——同步、强制重载指定扩展（不看 mtime）、实例恢复与 `NOTIFICATION_EXTENSION_RELOADED` 在**返回前**完成（返回后可立即断言）；不触发 `extensions_reloaded` 但触发 `extension_unloading`/`extension_loaded`。`--headless --editor --script res://driver.gd` 为官方支持路径，脚本须继承 `SceneTree`/`MainLoop`（不能是 `EditorScript`）；`--editor` 自动 `set_extension_reloading_enabled(true)`；headless 无窗口，焦点自动 reload 不触发，全部走脚本显式触发。注意区别：`reload_extensions()`（按 mtime 扫描、GDScript 重载走 deferred）不用于测试。
    - **编排协议（双阶段标记）**：
        1. Java：`prepareEditorProject`——编译 v1 库、安装 `bin/` + `.gdextension`（`reloadable=true`）+ 解释型 driver 脚本（`SceneTree` 子类）；
        2. Java：`start()`——`godot --headless --editor --path <test_project> --script res://driver.gd`；
        3. editor 内 driver PHASE1：`ClassDB.instantiate("<gdcc类名>")` 动态实例化（gdcc 类是 SUT，driver 用解释型 GDScript + `call()`/`get()`/`set()` 动态分派，顺带覆盖 method bind `try_update` 路径），断言 v1 行为（方法/属性/lambda/协程等），打印 `HR_PHASE1_OK`，轮询等待 `swap_done.flag`；
        4. Java 见标记 → 编译 v2 库并以**原子 rename** 替换 `bin/` 同名文件（**严禁 in-place 覆盖**：`cp` 原地截断会使已映射 .so 的干净页被新内容重灌、GOT 脏页保留旧版，旧 GOT 调用新代码必然 SIGSEGV——本机探针实测；正确做法：`cp v2 bin/.lib.new && mv -f bin/.lib.new bin/lib.so`，unlink 不影响既有映射，引擎 dlclose 旧 inode 后 dlopen 路径即得新 inode；Windows 引擎加载 `~xxx.dll` 副本，原文件不在映射中，同用 rename 即可）→ 写 `swap_done.flag`；
        5. driver 发现 flag → `GDExtensionManager.reload_extension("res://GDExtensionTest.gdextension")` → 同步返回后立即 PHASE2 断言（实例存活/属性保留/方法新逻辑/协程已取消/lambda 重绑或失效/static 重置）→ 全过打印 `HR_PHASE2_OK` 并 `get_tree().quit(0)`，失败打印 `HR_FAIL:<detail>` 并 `quit(1)`；
        6. Java 校验标记 + 退出码，超时强杀。
    - **JUnit 形态**：每个 HR-9 场景 = driver 内一个 case 函数 + 一个 test method（`prepare → start → awaitMarker(HR_PHASE1_OK) → buildAndSwapV2 → awaitMarker(HR_PHASE2_OK) → awaitExit(0)`）；断言逻辑全在 editor 内（直接接触存活实例），Java 只做编排与标记仲裁。一个 editor 进程可串跑多个 case 摊薄编辑器启动成本。
    - **环境感知**：`GODOT_BIN` 缺失 → skip；`GODOT_BIN` 必须是 **editor 二进制**（runtime 模板无 reload 能力）——session 启动前做 capability probe（`godot --headless --editor --quit` 退出码），不满足则 skip；Zig 缺失 → 现有 `ZigUtil.findZig()` + skip；headless editor 启动较重，超时预算从 30s 上调。
    - **冒烟验证项**（首次落地时逐条确认并归档；标注 ✅ 者已经本机 Linux + Godot 4.5.2 探针验证，探针产物 `tmp/editor_hotreload_probe/`）：✅ `--script` SceneTree 脚本与 EditorNode 在 headless 下共存稳定（EditorNode 首帧即挂载于 `root`）；✅ `get_tree().quit(code)` 退出码 editor/runtime 双模式原样传递；✅ `ClassDB.instantiate` + 动态 `call()` 在 editor 上下文链路完整（含 reload 后新代码生效与属性保留）；✅ `reload_extension` bogus 路径优雅返回错误码不崩溃；✅ 原子 rename 换库后完整 reload 往返成功；⬜ Windows 副本机制下替换原 DLL 后 `reload_extension` 加载新副本（Windows 专属，本机未验证）；✅ 需 `_process` 的场景挂载方式（v12 闭环：`root.add_child()` 即可，**fixture 类必须 `@tool`**——gdcc 对非 tool 类的 `_process`/`_physics_process` 在编辑器既定跳过，非重载缺陷）。
    - 手测清单（上述场景 1~10b）保留为自动化不可用环境下的兜底。

## 7. 用户可见热重载语义合同（随 HR 实施写入长期文档）

- reload 仅 editor build 可用；release 导出不受影响（且导出包走 direct 路径，与现状观察等价）。
- 存活实例：带 `PROPERTY_USAGE_STORAGE` 的已注册实例属性（导出与非导出）由引擎保存并恢复，但存在非 NIL 默认值且当前值等于默认值时跳过不保存、Object 类型值为 null 且无 `PROPERTY_USAGE_STORE_IF_NULL` 时同样跳过；仅在 `_init` 中赋值的字段保持 initializer 值（`_init` 不重新执行）。
- 属性 initializer 在 reload 时会对每个存活实例**重新执行**（recreate 重放 initializer 后引擎才经 setter 恢复保存值）：最终字段值由引擎恢复语义决定，但 initializer 的副作用（对象构造、函数调用）无法避免地再发生一次——用户代码不得依赖属性 initializer 的 exactly-once 副作用；被引擎恢复覆盖的 RefCounted initializer 值由 setter 正常释放，非 RefCounted Object initializer 值同理经 setter/析构路径处理，不形成泄漏。
- static var 重置为初始值。
- 进行中的协程被静默取消：`completed` **不**发射，等待方协程同样被取消。
- 编辑器内 lambda/standalone Callable 跨 reload 安全：签名与捕获布局未变的连接**执行新代码**；捕获布局/签名失配、lambda 被删除/换位、或在**同一函数体内该 lambda 之前**增删行时，连接优雅失效（信号静默跳过、脚本 `call()` 报标准错误，不会绑到错误实现）；其他函数的改动与本函数整体移动**不影响**连接；`Callable(对象, "方法名")` 连接始终有效；thunk 路径下 Callable `to_string` 显示 `<CallableCustom>`。
- custom Callable 全生命周期（创建/复制/存储/连接/调用/最终释放）不得离开主线程（`WorkerThreadPool`/`Thread.start` 等），违反属数据竞争；`Callable(对象,"方法名")` 的既有线程语义不受影响。
- 编辑器内若系统禁止可执行堆内存（加固策略），custom Callable 创建将返回无效 Callable 并打印错误（fail-closed），而非回退到不受保护的实现。
- 方法签名变更后，经旧缓存 MethodBind 的调用报错（引擎语义）；`is_runtime` 变更被引擎强制沿用旧值；父类变更不支持（改 GDCC 父类引擎不检查，属未定义行为）。

## 8. 后续工作（非本期范围）

- 增量编译/dirty tracking 与"编译完成→触发编辑器 reload"的开发管线（当前为全量 codegen + Zig 内容缓存）。
- `NOTIFICATION_EXTENSION_RELOADED` 用户钩子（如 `_on_extension_reloaded` 虚函数）。
- 同映像重复初始化加固（dlclose 未真正卸载时 `_inited` 标志与 registry 的一致性；以"runtime 不引入线程/TLS"规避为主）。
- worker 线程调用 custom Callable 的完整支持：generation quiescence 协议（原子状态机 + active-call pin + call-return trampoline + 并发回收），替代 §5.8 的线程禁止合同。

## 附录 A：HR-0 残余契约核对结论（2026-09，按 `4.5.1-stable` 源码核验）

### A.1 协程 token binding 的引擎清理路径（HR-0 项 1）

核验来源：`object.cpp:2102-2227`（set/get/free_instance_binding、clear/reset_internal_extension）、`gdextension.cpp:987-997`（clear_instance_bindings）、`gdextension_manager.cpp:282-298`（track/untrack_instance_binding）、`entry.c.ftl:483-497`。

- 协程状态对象的 `gdcc_coro_binding_token()` binding 是该对象上**唯一** binding，占 slot 0。reload 时 `Object::clear_internal_extension()` 先调旧库 `free_instance_func`（释放 wrapper 与 header 内存），随后仅将 slot 0 四字段置空——callbacks 全 NULL 故无任何代码回调，**先释放后置空的顺序无悬空解引用**。
- `GDExtension::clear_instance_bindings()` 只按 **GDExtension\* token** 释放被跟踪 binding；而跟踪仅在 `get_instance_binding` 内 `!_extension && is_extension_reloading_enabled()` 时发生（非扩展实例对象），且 `GDExtensionManager::track_instance_binding` 只认 `p_token == GDExtension*`。协程 token（TU-static 地址）不匹配该条件，协程状态对象又是扩展实例（`_extension != nullptr`），**双重不命中**——协程 binding 的清理完全由 `clear_internal_extension`（经 `Extension::instances` 跟踪）承担，无遗漏路径。
- recreate 重建无冲突：`clear_internal_extension` 已将 slot 0 置空，新代惰性壳 recreate 调 `godot_object_set_instance_binding`（写 slot 0）满足其 `_instance_bindings[0].binding == nullptr` 前置断言。
- 附带确认（§5.5 锚点事实基线）：**固定常量 token（≠ GDExtension\*）的 binding 不会被 track，因此不被 `clear_instance_bindings` 触及；Engine 单例非扩展实例，`clear_internal_extension` 不适用**——HR-8 锚点跨 reload 存活的引擎机制源码层面成立（实机验证留给 HR-9 自动化）。

### A.2 dlclose 真正卸载：方法学与 runtime TLS/线程审计（HR-0 项 2）

- **审计结果**：runtime 唯一的 TLS 使用是 minicoro 的 `MCO_THREAD_LOCAL mco_coro* mco_current_co`（`minicoro.c:565`）——POD 指针、无 TLS 析构器（不注册 `__cxa_thread_atexit`），不阻止 dlclose；deinitialize 时（HR-5 `cancel_all` 之后）全部协程已 MCO_DEAD，该值必为 NULL。除 minicoro 外 runtime 无任何线程/TLS/线程局部存储使用（全量 grep 已覆盖 `include_451/gdcc` 与 `include_451/godot`）。
- **验证方法学**（纳入 HR-9 场景 9/10 开发期验证）：Linux 用 `/proc/<pid>/maps` 对比 reload 前后库路径映射消失（辅以 `LD_DEBUG=files` 观察 dlclose）；macOS 用 `vmmap <pid>` / `DYLD_PRINT_LIBRARIES` 对照；Windows 引擎加载 `~xxx.dll` 副本，原 DLL 本就不在映射中。

### A.3 §5.10 待验证项结论（HR-0 项 4）

- Engine 单例固定 token instance binding 跨 reload 存活：**源码确认成立**（A.1 末条）；实机验证项移交 HR-9 自动化（依赖 HR-8 落地）。
- macOS 无 entitlement RW→RX 实机验证：本机为 Linux 无法验证，**保留为 macOS 专属待验证项**（aarch64 与 x86_64 分别核验）；HR-8 的 execmem probe fail-closed 设计已为此保底。
- `is_editor_hint` 三进程取值（`main.cpp:2032-2034, 2161-2163` 核验）：仅 `--editor`/project manager 进程 `set_editor_hint(true)`；编辑器 F5 启动的游戏进程（独立进程，不带 `--editor`）与导出包均为 false。现有 `gdcc_is_editor_hint()`（`gdcc_helper.h:76-77`，经 fixed binding 查询 `gdcc_init()` 缓存的 Engine 单例）可直接作为 §5.4 三态模式机的进程判定输入；HR-9 场景 10b 实机复核。

**总结论**：HR-0 全部未决项关闭；未推翻 §2/§4/§5 任何条款，主体设计不变。
