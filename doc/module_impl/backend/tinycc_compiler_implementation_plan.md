# TinyCcCompiler 实施计划（tinycc 内嵌 FFM 编译后端）

## 文档状态

- 状态：实施中（阶段 1-4 已完成，阶段 5-6 实施中）。本文档是 `TinyCcCompiler` 特性的唯一计划文档，完成后按归档约定转化为事实源文档或归档。
- 适用范围：`gd.script.gdcc.backend.c.build` 包、`src/main/c/codegen/{include_451,template_451}`、`src/main/c/tinycc`（新增）、API/CLI/RPC 编译选项接线、zig launcher（行为变更，见 §8）。
- 依据文档（结论来源，本文档不重复其论证）：
  - `doc/analysis/tinycc_analysis_windows_handoff.md`：Windows x86_64 上 tinycc mob `43c7708` + Java 25 FFM 的完整验证（构建命令、FFM 契约、11 文件兼容清单、资源包结构）。
  - `tmp/tcc-c11-probes/EVIDENCE.md`：Linux x86_64 上 tcc 0.9.27/0.9.28rc 对 gdcc 生成 C 的特性接受矩阵。
  - `tmp/tcc-risk-verify/EVIDENCE.md`：asm 逗号/`jmpq`、TLS 宏链路、`_MCO_ASM_BLOB`、`__builtin___clear_cache`、大枚举值等风险实证。
  - `doc/module_impl/backend/backend_build_system_implementation.md`：现有 zig 构建管线事实源，本文档声明的不变边界以其为准。

## 1. 目标与范围

### 1.1 目标

1. 新增 `TinyCcCompiler`（与 `ZigCcCompiler` 同级，实现 `CCompiler` 接口），通过 Java 25 FFM（`java.lang.foreign`）在进程内加载 libtcc 动态库完成 C 编译，产出与 zig 管线契约一致的共享库。
2. tinycc mob `43c7708b85681a2fd4451c8a541af4494a8919b2`（0.9.28rc）源码 vendored 于 `src/main/c/tinycc/`，配套构建链产出平台化资源包（bundle）。
3. 改造共享 C 层（模板 + include 树 + Java 字面量生产者），使同一份生成 C 同时被 zig cc（`-std=c23`）与 tinycc（`-std=c11`）接受，zig 行为不回退。
4. Windows 支持按 `tinycc_analysis_windows_handoff.md` 的已验证路径落地。

### 1.2 非目标（本期不做）

- 交叉编译：TinyCcCompiler 只支持 host == target 的目标平台（见 §4 D5）。
- `TCC_OUTPUT_MEMORY` JIT 执行、`tcc_run`、PCH、LTO、ThinLTO、PDB 生成。
- 优化对等：不宣称 tcc 产物的优化水平与 zig `-O2`/LTO 对等。
- macOS、Android、Web、linux-riscv64 的 tcc 支持；`LINUX_AARCH64` 待实测后另行开放。
- 修改 `CCompiler` 接口签名、`CProjectBuilder` 输入收集顺序、`.gdextension` 由 CLI 写入的合同（不变边界，同 `backend_build_system_implementation.md` §1）。

## 2. 前置调研结论（已确认事实）

1. **CCompiler 抽象已就绪**：`CCompiler.compile(...)` 与 zig 无关；`CCompileResult(success, buildLog, artifacts)` 契约（artifacts 首位为共享库、失败为空）可直接复用。`CProjectBuilder` 已有构造器注入缝。
2. **生成 C 事实为 C23**：zig 管线用 `-std=c23`，含 `nullptr`、裸 `true`/`false`、`u8"..."`、`__int128`；上游 tcc（含 mob dev）全部拒绝（tmp 证据）。**vendored 树已打本地补丁**（`src/main/c/tinycc/tccpp.c`，GDCC_PIN.md 记录）：`u8"..."` 作为透明前缀走既有窄字符串解析路径，`u8` 不再是可移植化对象。mob dev 支持 `_Generic`、`_Static_assert`、`_Noreturn`、`_Thread_local`/`__thread`、语句表达式、`typeof`、空初始化器、大枚举值（静默正确）。
3. **FFM 契约已验证**（Windows）：`tcc_set_lib_path` 必须先于 `tcc_set_output_type`（后者经 `{B}` 展开 include/lib 路径，实现见 `libtcc.c` 的 `tcc_split_path`）；`TCC_OUTPUT_DLL=4`；每轮新建 state；诊断 upcall 须拷贝瞬时字符串且异常不得穿过 native 帧；`tcc_output_file` 会重置错误计数（其实现首行 `s->nb_errors = 0`），不能仅凭其返回值判定成功；失败后不得继续后续操作。
4. **int 返回值语义**：`tcc_add_file` 出错不止返回 -1（`FILE_NOT_FOUND=-2`、`FILE_NOT_RECOGNIZED=-3`），`tcc_set_output_type`/`tcc_set_options`/`tcc_add_include_path`/`tcc_output_file` 同样以非 0 表失败。所有 int API 必须按 `!= 0` 判定。
5. **minicoro asm 适配路径已探明**：`.type name @function` → `.type name, @function`；`jmpq *%r12`/`*({rsi})` → `jmp ...`（gcc/tcc 均接受逗号形式与 `jmp`）。
6. **minicoro TLS 宏链路**：mob dev 在 `-std=c11` 下经 `tccdefs.h` 定义 `__STDC_NO_THREADS__`，且 tcc 不定义 `__GNUC__`，现有分支会落到 `MCO_THREAD_LOCAL` 为空 + `MCO_NO_MULTITHREAD`（协程退化为非线程安全，是**行为变化**而非编译错误）。适配必须强制走 `__thread` 分支并禁止该落回。
7. **运行时 TU 依赖 libc 头文件与启动文件**：`godot_interface.c`（`stdio.h`/`stdarg.h`）、`gdcc_hrx.c`（`string.h`/`sys/mman.h` 或 `windows.h`）、`minicoro.c`（vmem 分配器）。Linux 上 `TCC_OUTPUT_DLL` 链接路径会链接宿主 glibc（`tcc_add_library(s,"c")`）并查找 `crti.o`/`crtn.o` 等启动文件；Windows 上必须随 bundle 分发完整 `win32/include`（含 CRT 头，`windows.h` 依赖 `_mingw.h`，不能只取 `winapi/` 子目录）与 `win32/lib/*.def`。
8. **产物 libc 合同差异**：zig 用其 bundled sysroot 编译链接（不依赖宿主开发头/CRT），tcc 依赖宿主开发头、CRT 与库；两者产物在 Linux 上均为动态链接 glibc 的共享库，部署兼容性以实际动态依赖与符号需求验证为准，不存在"zig 产物静态链 libc"的前提。
9. **FFM 启动参数**：launcher 已带 `--enable-native-access=ALL-UNNAMED`（`src/launcher/zig/src/main.zig:94`）；`java -jar` 为 unnamed 部署，该参数匹配。named-module 启动（IDE 模块路径）需 `--enable-native-access=gdcc`，须在阶段 0 用真实加载核对各启动方式。
10. **逃逸生产者**：`StringUtil.escapeStringLiteral` 对非 ASCII 输出 `\uXXXX`/`\UXXXXXXXX`。UCN 在 plain 与 `u8"..."` 字面量中的执行编码已在 Linux x86_64 的 zig `-std=c23` 与 vendored tcc `-std=c11` 双侧验证（P0-1 字节矩阵 + `TinyCcU8StringTest`）；Windows 侧执行字符集仍未验证（handoff §5.1）。
11. **金字面量测试面**：`src/test` 中 `u8"` 断言保持原样（vendored 补丁后 u8 前缀保留，无需清扫）；`nullptr` 断言 1 处，随可移植化同步。

## 3. 总体架构

### 3.1 组件与职责（新增/改动）

| 组件 | 位置 | 职责 |
|---|---|---|
| `TinyCcCompiler` | `backend/c/build/`（新增） | `CCompiler` 实现：一轮 = 一个 `TCCState`，逐 TU `tcc_add_file` 后一次 `tcc_output_file`；全局锁串行；合作式取消 |
| `TinyCcLibrary` | `backend/c/build/`（新增，package-private） | FFM 绑定层：惰性一次性加载 libtcc、10 个 downcall handle、诊断 upcall stub 工厂；进程生命周期内不卸载 |
| `TinyCcNative` | `backend/c/build/`（新增，package-private） | 可替换的 native 调用窄接口（state 创建/删除、options、output type、include、add_file、output_file、错误回调注册），生产实现委托 `TinyCcLibrary`；测试可用纯 Java fake 驱动失败路径 |
| `TinyCcBundle` | `backend/c/build/`（新增，package-private） | bundle 定位：`GDCC_TINYCC_HOME` 环境变量优先；否则从 classpath 按版本键安装到用户缓存目录；校验布局与 `VERSION` 标记 |
| `CCompilerKind` | `backend/c/build/`（新增） | 枚举 `ZIG_CC` / `TINY_CC` |
| `CompileOptions` | `api/`（改动） | 新增 `cCompilerKind` 组件（`@Nullable`，null=按级别自动分派：debug→tcc、release→zig，见阶段 4 用户决议）；保留六参兼容构造器（委托新构造器，先例：`CBuildResult` 辅助构造器） |
| `CProjectInfo` | `backend/c/build/`（改动） | 新增 `cCompilerKind` 访问器，随 `CompileOptions` 透传 |
| `CProjectBuilder` | `backend/c/build/`（改动） | 无参构造：持有 `EnumMap<CCompilerKind, CCompiler>` 双实现，按 `projectInfo.cCompilerKind()` 逐轮选择；单参构造：安装强制编译器并**忽略 kind**（现有测试语义）；移除运行期 `setCCompiler`（共享可变槽有数据竞争且当前无生产调用方） |
| `GdccCommand` | `cli/`（改动） | 新增 `--backend zig|tcc` 选项 |
| launcher | `src/launcher/zig/src/main.zig`（行为变更，§8） | zig 发现从启动硬前置降级为可选：找不到 zig 时不再 `exit(1)`，仅不设置 `ZIG_HOME`；zig 缺失由 `ZigCcCompiler` 在编译时报告 |
| tinycc vendored 源码 | `src/main/c/tinycc/`（新增） | mob `43c7708` 干净源码树（去除 `.git` 与全部生成物：config.mak/config.h、*.o、tcc、libtcc1.a 等，清单以 `.gitignore` 为准），附 pin 记录与 license |
| bundle 构建脚本 | 见 §8 权限项 | 构建 libtcc 动态库与各目标运行时归档并组装 bundle |

### 3.2 编译流程（对比 zig 两阶段）

zig：每 TU 一个子进程产 object（并行、内容缓存、PCH）→ 一次链接。tcc 一轮内全部 TU 合并编译直接产共享库，无 object 阶段：

1. `TinyCcBundle` 解析 bundle 根（失败 → `success=false`，日志说明缺失与 `GDCC_TINYCC_HOME` 用法）。
2. 校验 `targetPlatform == TargetPlatform.getNativePlatform()` 且在支持集 `{LINUX_X86_64, WINDOWS_X86_64}` 内，否则快速失败（不触碰 native 库）。
3. 锁获取顺序固定为 **per-project 构建锁 → tcc 全局锁**，禁止反向：per-project 锁与 zig 共用同一把（提取为编译器中立的 package-private 持有器，`ZigCcCompiler` 改用之），保护同一 `projectDir` 下的产物文件与输出路径，使 zig/tcc 对同一项目的并发构建互斥；tcc 全局锁（`ReentrantLock.lockInterruptibly`，取消通道）仅保护 libtcc 进程级 native 状态。
4. 本轮 `Arena.ofConfined`；经 `TinyCcNative` 创建 state（NULL 拒绝）；安装诊断 upcall（消息即时拷贝为 Java `String` 追加到本轮列表）。
5. `tcc_set_lib_path(bundleRoot)` → `tcc_set_options("-std=c11")` → `tcc_set_output_type(TCC_OUTPUT_DLL)` → 逐个 `tcc_add_include_path`（顺序与 `includeDirs` 一致）。**每个 int 返回值 `!= 0` 即中止本轮**（覆盖 -1/-2/-3 与 setup 期失败）。
6. 按 `cFiles` 顺序逐个 `tcc_add_file`，返回值 `!= 0` 即中止（不继续后续文件、不 output）；文件间检查线程中断标记（合作式取消，中断则中止并恢复 interrupt 状态）。**每次 downcall 返回后检查本轮回调失败标记：非空即按失败中止**（native 返回 0 且产物已生成也不得报成功）。
7. output 前删除已存在的同名旧产物（避免以陈旧文件冒充成功）；全部成功后 `tcc_output_file(outputPath)`，返回值 `!= 0` 即失败；再校验产物为普通文件。
8. `finally`：`tcc_delete`（upcall/库 arena 仍存活）→ 关闭本轮 arena → 释放锁。
9. 成功：`artifacts=[outputPath]`（tcc 不产 PDB）；buildLog 为诊断列表拼接（可为空）。失败：`buildLog` = 诊断列表 + 失败通道说明；`artifacts` 为空。

FFM 生命周期不变量（写入实现类注释与测试）：

- 全部 downcall/upcall 发生在持有全局锁的同一条线程上（`Arena.ofConfined` 只允许创建线程访问）。
- upcall 返回前必须把瞬时消息拷贝为 Java `String`（libtcc 回调后释放 native string）；异常在 upcall 内部捕获并记录为回调失败，绝不穿过 native 帧。
- upcall stub 的 `MemorySegment` 存活于本轮 arena，关 arena 后地址作废，不得跨轮复用。
- `tcc_delete` 先于 arena 关闭；库本体用进程级共享 arena 承载（可跨线程访问），进程生命周期内不卸载；初始化半途失败时关闭临时 arena 释放资源。

日志合同差异声明：tcc 无子进程，不产生 `Command:` 段；诊断文本天然含 `文件:行: error:` 前缀（tcc 自带）。

### 3.3 编译器选择接线

- `API` 持有的单一 `CProjectBuilder` 服务全部并发任务，因此编译器选择必须是**逐轮不可变**的：不允许按任务改共享可变槽。
- `CProjectBuilder` 无参构造 = `EnumMap` 双实现；单参构造 = 强制编译器（忽略 `cCompilerKind`，保持现有测试语义）。`TinyCcCompiler` 的惰性初始化（bundle/FFM）只在被选中的首轮发生。
- `CompileTaskRunner` 构造 `CProjectInfo` 时从 `request.compileOptions().cCompilerKind()` 透传；`GdccCommand.compileOptions(...)` 从 `--compiler` 解析。
- RPC/wire 兼容政策：`CompileOptions` 经 Gson 按 record 组件名反序列化（`options.set` 整体替换快照）。新增 `cCompilerKind` 缺失或显式 null 均表示自动（按级别分派，见阶段 4 的用户决议；原"缺失默认 ZIG_CC"方案已被取代）；显式未知枚举值按既有枚举解析规则报错。需同步更新 `doc/module_impl/api/rpc_api_implementation.md` 与 `json_rpc_service_implementation.md` 的字段表。

### 3.4 bundle 布局与分发

```text
tinycc-bundle/<platformKey>/            # platformKey: linux-x86_64 / windows-x86_64
  VERSION                               # properties：bundleFormat（=1）+ tinyccCommit（pin）+ platform
  MANIFEST                              # 全部其他文件的 sha256sum 清单（安装完整性校验用）
  bin/libtcc.so | bin/libtcc.dll        # FFM 加载对象
  include/                              # tcc 自有头（tccdefs.h、stdarg.h、stdbool.h…）
  libtcc1.a 或 lib/libtcc1.a            # 位置按平台路径合同（见下）
  COPYING、RELICENSING                  # 上游 license 随包
  # windows 追加（对齐上游 install-win：win32/include 递归合并进 include/，winapi 落在 include/winapi/）：
  include/                              # 合并 include/*.h、tcclib.h 与 win32/include/**（_mingw.h 在顶层）
    winapi/**                           # Windows 兼容头（windows.h 在 include/winapi/ 下）
  lib/libtcc1.a                         # 由交叉目标产出的 x86_64-win32-libtcc1.a 改名安装
  lib/{msvcrt,kernel32,user32,gdi32,ws2_32,...}.def
```

平台路径合同（以 pinned 源码 `tcc.h:255-300`/`Makefile install-win` 实证为准）：

- Windows：PE 默认系统头路径 `{B}/include` + `{B}/include/winapi`，库路径 `{B}/lib`；`tcc_set_lib_path(root)` 后 `lib/libtcc1.a` 命中。bundle 的 `include/` 必须是上游 `include/*.h` 与 `win32/include/**` 的**合并树**（`windows.h` 依赖同层 `_mingw.h`），禁止另建 `include/win32/` 层级。
- Linux：默认系统头路径 `{B}/include` + triplet 化的系统 include；库路径为 `{B}` + triplet 化系统库目录（**不含 `{B}/lib`**）；multiarch include/CRT 路径依赖 `configure` 生成的 `CONFIG_TRIPLET`。因此 Linux bundle 要么将 `libtcc1.a` 直接置于 root，要么在构建 libtcc 时以受控 `config.h` 明确 `{B}/lib`；系统头/CRT 路径由 `configure` 产物决定，禁止靠手写 `-D` 跳过该块。实现时以 G1 的移位 smoke 验收锁定（见 §5 阶段 1）。
- `tcc_set_lib_path` 指向 `<root>`；libtcc 本体路径仅用于 FFM `SymbolLookup.libraryLookup`。
- JAR 内资源必须先落盘（handoff 已确认 loader 不接受 JAR URL）。

安装协议（独立定义，不引用 `ResourceExtractor` 语义——后者只做单文件内容比较，无目录锁与自愈）：

- 目标目录：用户缓存目录（Linux `~/.cache/gdcc/tinycc/<key>/`，Windows `%LOCALAPPDATA%/gdcc/tinycc/<key>/`），key = gdcc 版本 + mob commit + platformKey（实现形态：`<sanitized-gdccVersion>-tinycc-<fullCommit>-<platformKey>`）。
- 安装：按 key 的安装锁是**两层**互斥——先取 JVM 内 per-key `ReentrantLock`（按规范化锁文件路径；必须在打开 channel 之前获取、在 FileLock 与 channel 关闭之后释放，因为 Linux/macOS 上 `FileLock` 底层是经典 POSIX fcntl 锁，进程关闭同一文件的任意 fd 会释放该进程在此文件上的全部锁），再对兄弟锁文件 `<key>.lock` 以非阻塞 `tryLock` + 可中断退避循环获取跨进程互斥（**不得用阻塞式 `channel.lock()`**：JDK 25 虚拟线程的中断语义下 `interrupt()` 会等待 native 锁调用返回，等于不可取消）。两处等待均可中断并恢复 interrupt 状态。锁内：建唯一 staging 临时目录（同父目录、`.staging-<key>-<random>` 随机名）→ 全量写入 → 按 MANIFEST 逐文件 SHA-256 校验 → 布局/VERSION 校验 → 最后写 `.ready` → 原子 rename 发布；rename 冲突（并发实例已发布）时校验胜出目录完整性后使用之，校验失败则替换；`.ready` 缺失或校验失败的目录（含普通文件/符号链接占位）整体删除重来。
- 完整性：`MANIFEST` 是必需布局条目；缓存复用、胜出者采用与 `GDCC_TINYCC_HOME` 覆盖路径均按 MANIFEST 重验全部文件哈希（损坏/缺失即触发自愈），且 fail-closed——空清单、行边界截断清单均不通过，树内每个普通文件（除 `MANIFEST`/`.ready` 元数据）都必须被清单覆盖，清单路径经穿越/盘符/非法字符校验。
- staging 清理：只清理安装锁未被持有且满足保留期（如 mtime 超过 24h）的 staging 目录；持锁安装中的目录不得被其他实例清理（含"安装者暂停 vs 崩溃"用例：B 不得清理 A 持锁中的 staging，A 崩溃且锁释放后方可回收）。
- 已加载 libtcc 的 bundle 目录视为不可变版本：进程内一经加载即固定，自愈不替换正在使用的版本目录。
- 旧版本清理政策：本期不自动清理，记录为已知限制。

## 4. 关键设计决策

- **D1 接受进程内故障边界消失**（MVP）：tcc native 内存错误可终止 JVM；现有 zig 子进程取消/超时模型不可复用。本期接受该取舍，worker 进程隔离列为后续方向。需在用户文档明示。
- **D2 可移植化改到生产者**：模板、include 头、Java 字面量生产者三处源头改造；禁止生成后 search/replace；禁止为 tcc 派生第二套模板/include 树（单一源、两编译器共食）。
- **D3 每轮新 state + 全局锁**：`TCCState` 不跨轮复用（失败态不可信）；静态 `ReentrantLock` 串行全部 tcc 轮次（tcc 进程全局状态 + 内部编译锁不构成并发安全承诺）。
- **D4 取消语义降级为合作式**：仅在 TU 边界与锁等待处响应中断；活跃 downcall 不可中断；中断后恢复 interrupt 状态。buildLog 固定通道 `Failed to run tcc: interrupted`，与 zig 通道形态对齐。API 层 `cancelCompileTask()` 的 `BUILDING_NATIVE → CANCELED` 映射仍成立（native 返回后 runner 再查取消标记），但完成延迟变长，`rpc_api_implementation.md` 的"runner 迅速退出"预期需同步修订。
- **D5 不支持交叉编译**：libtcc 的目标平台即其构建目标；`targetPlatform != host` 快速失败并提示改用 zig。支持集首期为 `{LINUX_X86_64, WINDOWS_X86_64}`；`LINUX_AARCH64` 待实测后开放。
- **D6 `CompileOptions` 增加 `cCompilerKind` 组件**：record 构造点全部更新（生产 2 处：`CompileOptions.defaults`、`GdccCommand.compileOptions`；测试 11 处）；保留六参兼容构造器。**默认值经阶段 4 用户决议修改**：未指定（null）表示自动——debug→`TINY_CC`、release→`ZIG_CC`（`CCompilerKind.defaultFor`）；`CProjectInfo` 5 参兼容构造器仍固定 `ZIG_CC` 以保持后端既有调用点行为不变。
- **D7 DEBUG/RELEASE 同构**：tcc 无有效 `-O2`/LTO；两档仅影响输出基名（`debug|release`），编译参数一致，文档明示不承诺优化对等。
- **D8 构建时 libc 来源差异显式化**：zig 用 bundled sysroot、不依赖宿主开发环境；tcc 依赖宿主开发头/CRT/库。两者产物均为动态 glibc 共享库，部署兼容性以实测动态依赖为准，不作静态链接承诺。

## 5. 分步骤实施

阶段依赖：`G0 → G1；G0/G1 → G2；G1 → G3；G2/G3 → G4 → G5/G6 → G7`。内部基础设施可先行合入，但未通过对应平台端到端门前不得声明该平台受支持；关键测试全部 skipped 不算验收通过。

### 阶段 0：权限确认与前置探针

改动：
- 向用户申请 §8 权限清单（构建脚本、`build-script/` 新文件、launcher 行为变更）。
- 探针 P0-1（tmp 下，用 `/tmp/opencode/tinycc` 的 mob tcc）：plain `"..."` 中 `\uXXXX`/`\UXXXXXXXX` 的 tcc 执行编码；原始 UTF-8 字节直通在 zig/tcc 两侧的等价性。结论写入 tmp 证据文件。~~决定 D2 中字面量方案~~（后续 vendored u8 补丁使字面量方案维持原状：UCN 转义 + `u8` 前缀，见阶段 2 进度）。
- 探针 P0-2：以真实 FFM 加载分别核对 `java -jar`（unnamed）与 IDE 模块路径（`--enable-native-access=gdcc`）两种启动方式所需参数。
- 探针 P0-3：进程内 libtcc 在 Linux 下对 `stdio.h`/`sys/mman.h`/crt/libc 的解析链路（`configure` 产物 vs `tcc_add_sysinclude_path` 补充），结论决定 §3.4 Linux 布局落点。

验收：
- 权限清单获批；三份探针结论明确，附复现命令与输出。

### 阶段 1：vendored tinycc 与 bundle 构建链

改动：
- `src/main/c/tinycc/`：复制 mob `43c7708` **干净树**（以 fresh clone 为准，去除 `.git` 与 `.gitignore` 列出的全部生成物：config.mak/config.h/*.o/tcc/libtcc1.a 等），附 `GDCC_PIN.md`（commit、上游 URL、获取命令、license 摘要）；`COPYING`/`RELICENSING` 随源码树自带。
- bundle 构建脚本（位置见 §8），分平台：
  - Linux x86_64：`./configure --cc="zig cc"`（生成正确 `CONFIG_TRIPLET` 的 config.h/config.mak）→ `make tcc`（native CLI，供后续归档构建）→ `make libtcc.so`（与上游目标对齐：`-shared -Wl,-soname,libtcc.so -fPIC`，链接 `$(LIBS)` 含 `-lm -ldl -lpthread`，**不加** `-fvisibility=hidden`）→ `make libtcc1.a`（由刚构建的 tcc 经 `lib/Makefile` 自举）。
  - Windows x86_64（从 Linux 交叉或 Windows 主机）：干净树先经 `./configure --cc="zig cc" --config-predefs=no` 生成 `config.h`——`tcc.h` 无条件包含 `config.h`，直接 zig cc 编译前必须先生成；`--config-predefs=no` 使预定义宏改为运行时从 bundle `include/tccdefs.h` 包含，**规避** `tccdefs_.h` 生成步骤（该头由 Makefile 模式规则经宿主 `c2str.exe` 转换产生，非 configure 产物；若选择启用 predefs 则必须先 `make tccdefs_.h`）；libtcc.dll 按 handoff 命令（`zig cc -target x86_64-windows-gnu -fno-sanitize=undefined -shared -DTCC_TARGET_PE -DTCC_TARGET_X86_64 -DLIBTCC_AS_DLL libtcc.c`）；win32 运行时归档经上游交叉目标 `make cross-x86_64-win32`（其 `x86_64-win32-tcc` 是 Linux 宿主、PE 目标的交叉编译器，可运行并构建 win32 归档），产物 `x86_64-win32-libtcc1.a` **改名安装为 bundle 的 `lib/libtcc1.a`**；或在 Windows 主机用 `win32/build-tcc.bat`（由该脚本负责配置生成，且默认不启用 predefs）；按上游 `install-win` 语义组装：`win32/include/**` 递归合并进 bundle `include/`（winapi 落在 `include/winapi/`），`win32/lib/*.def` 入 `lib/`。
  - 组装 bundle 目录并写 `VERSION`。
- `TinyCcBundle`：定位/安装/校验实现（§3.4 安装协议）+ `GDCC_TINYCC_HOME` 覆盖。

进度（实施中逐项同步）：

- [x] 权限决议（§8.2/§8.3）：构建脚本目录定为 `build-script/`；产物输出到 `build/`；`.gitignore` 无需改动。
- [x] vendored 源码树 + `GDCC_PIN.md`（`git archive 43c7708` 导出，已验证无 `.git`/生成物，`COPYING`/`RELICENSING` 随树；仅保留构建必需内容：上游 `tests/`、`examples/`、`win32/examples/`、`.github/`、文档生成文件 `tcc-doc.texi`/`texi2pod.pl` 均未 vendor）
- [x] bundle 构建脚本（`build-script/build-tinycc-bundle-linux-x86_64.sh` 本机构建+验证通过；`build-script/build-tinycc-bundle-windows-x86_64.sh` Linux 交叉构建+结构校验通过，PE 运行验证归 G6）
- [x] `TinyCcBundle` 实现（`TinyCcBundle.java` + `TinyCcBundleTest` 39 项测试全绿：布局/VERSION/MANIFEST 完整性正反校验（含空/截断清单 fail-closed、不可读子树的受控失败）、classpath/目录源安装、确定性锁竞争、跨 JVM POSIX 锁回归探针与跨进程持锁即时中断（`TinyCcBundleLockProbe`）、rename 冲突采用/替换胜出者、`.ready`/内容/清单自愈、普通文件与符号链接占位自愈、staging 保留期与持锁保护、中断恢复、路径穿越/盘符/NUL 防护）
- [x] 验收：Linux bundle 产物 + `nm -D` 导出检查（78 个 `tcc_*` 导出）+ 移位 smoke（真实 `-shared` 链接含 stdio/sys/mman 调用；`ldd` 记录 `libc.so.6 => /lib/x86_64-linux-gnu/libc.so.6`；证据 `tmp/tinycc-bundle-g1/EVIDENCE.md`）+ `TinyCcBundleTest` 全绿
- [x] vendored 本地补丁：`tccpp.c` 透明 `u8"..."` 前缀支持——`u8"..."` 作为单个预处理字符串 token 保留拼写（stringify/粘贴/预处理输出），转 C token 时跳过前缀走既有窄字符串解析路径（非 C23 `char8_t` 语义）；`GDCC_PIN.md` 记录补丁，`TinyCcU8StringTest` 回归覆盖（前缀透明性、UCN/八进制/十六进制转义、混合前缀拼接、stringify、`u8 ## x` 粘贴、行拼接、8192 缓冲边界、`u8'a'` 拒绝）

验收：
- 脚本在本机（Linux x86_64）产出 bundle；`nm -D libtcc.so` 可见 `tcc_new` 等导出。
- **移位 smoke（G1 核心）**：将 bundle 移至全新目录，仅以 `tcc_set_lib_path` 指向它，通过 FFM（或先经 CLI 等价物）以 `TCC_OUTPUT_DLL` 编译并链接一个含 `stdio.h`、`sys/mman.h` 调用的最小共享库；`ldd` 记录产物 libc soname（分发合同证据）；禁止用 `-c`/OBJ 类型冒充该验收。
- `TinyCcBundleTest`（纯 Java，fixture 目录）：布局校验、版本键、双实例并发安装（staging 竞争与胜出目录复用）、`.ready` 缺失/损坏目录自愈、崩溃残留 staging 清理。

### 阶段 2：共享 C 层可移植化（zig 不回退为前提）

改动（按生产者分组）：
1. 模板 `template_451/`：`u8` 前缀**保留**（vendored u8 补丁后不再是可移植化对象，见阶段 1 进度）；`entry.c.ftl:110/135` 空初始化器实测后决定保留或改 `{ 0 }`。
2. Java 生产者：`u8` 前缀与 `StringUtil.escapeStringLiteral`（UCN 形式）**均维持原状**（vendored u8 补丁后无需改造）。
3. include 树：`gdcc_string.h:23`、`gdcc_string_name.h:30`、`gdcc_callable.h:29` 的 `{nullptr}` → `{NULL}`；`gdcc_operator.h:39-40` `__int128` → `uint64_t`（保留平方求幂与负指数分支，禁止换用有符号 `int64_t` 中间量）。
4. `<stdbool.h>` 集中引入：**改生成器**（`godot_macros.h` 是 GodotBinding 工具链的生成物，头注禁止手改——在生成器模板中注入 include），并验证所有裸 `true`/`false` 使用点的包含链。
5. minicoro（vendored 第三方，补丁须就地注释说明原因，升级需重放）：asm 串 `.type name, @function`、`jmp` 化；**恢复地址硬编码位移必须标签化**：`leaq 0x3d(%rip), %rax` → `leaq .Lmco_switch_resume(%rip), %rax` 并在 `jmp *(%rsi)` 后补 `.Lmco_switch_resume:` 标签——tcc 内联汇编器把 RIP 相对的硬编码常量位移误编码为原值减 4（实测：`0x3d`→`0x39`，gcc 正确；误编码的保存地址落在 `mov 0x8(%rsi),%rsp` 指令中间，当前纯属字节对齐侥幸解码为无害指令序列才测试通过，任何指令长度变化都会变成内存破坏或死循环）；TLS 宏链在 `__TINYC__` 下强制 `__thread` 且**禁止落到 `MCO_NO_MULTITHREAD`**（行为变化，非编译错误）；`_MCO_ASM_BLOB` 分支识别 `__TINYC__`（Windows blob 入 `.text`）。证据：`tmp/minicoro-patch-verify/EVIDENCE.md`。
6. `gdcc_call.h:84` GNU 分支条件追加 `|| defined(__TINYC__)`；`gdcc_hrx.c:476` aarch64 分支在 `__TINYC__` 下改用 `__arm64_clear_cache`。

进度（实施中逐项同步）：

- [x] 探针 P0-1（阶段 0 遗留缺口，本阶段补做）：plain 字面量中 UCN/原始 UTF-8 直通/逐字节三位八进制/三连符在 zig `-std=c23` 与 tcc `-std=c11` 下的双编译器行为矩阵，输出逐字节一致；证据 `tmp/tcc-p0-1-probes/EVIDENCE.md`（初版结论的八进制方案后被 vendored u8 补丁取代，字面量路径维持原状）
- [x] ~~`StringUtil.escapeStringLiteral` 改造~~：**已回退**，维持原 UCN `\uXXXX`/`\UXXXXXXXX` 形式（vendored u8 补丁后 UCN 在 `u8"..."` 中与 plain 字面量同路径解析，`TinyCcU8StringTest` 锚定）；本阶段仅新增独立的 `escapeLineDirectiveFileName`（见 review 收口条目）
- [x] ~~Java 生产者 `u8` 前缀移除~~：**已回退**，`u8"..."` 前缀保留（vendored u8 补丁）
- [x] 模板：`u8` 前缀**保留**（vendored u8 补丁）；`entry.c.ftl` 两处 `= {}` 改为 `= { 0 }`——实测 tcc 接受空初始化器（`tmp/tcc-c11-probes/EVIDENCE.md` 矩阵），但 `{ 0 }` 是严格 C11 且此处语义相同（`GDExtensionClassCreationInfo5` 首成员为标量 `is_virtual`、变量为块局部），不依赖 tcc 扩展
- [x] include 树：三处 `{nullptr}` → `{NULL}`；`pow_int` 中间量 `__int128` → `uint64_t`（平方求幂与负指数分支保留，无符号取模乘法等价性注释就地说明）；`gdcc_call.h` GNU 分支追加 `|| defined(__TINYC__)`；`gdcc_hrx.c` aarch64 分支在 `__TINYC__` 下改用 `__arm64_clear_cache`（tcc 内建，`tcctok.h` 实证）
- [x] `<stdbool.h>` 经 `GodotBindingTool.renderMacrosHeader` 注入并用 `generate-abi-support` 重新生成 `godot_macros.h`（其余 7 个生成头与跟踪副本字节一致）；裸 `true`/`false` 使用点包含链全部经 `godot_binding.h → godot_abi.h → godot_macros.h` 覆盖（`minicoro.h` 无 bool 代码使用）
- [x] minicoro 补丁五件套（均就地注释为 gdcc patch）：`.type name, @function` 逗号化、`jmpq`→`jmp`、`leaq 0x3d(%rip)` 恢复地址标签化（`.Lmco_switch_resume`）、TLS 链识别 `__TINYC__` 并新增编译期护栏禁止落回 `MCO_NO_MULTITHREAD`、`_MCO_ASM_BLOB` 识别 `__TINYC__`；双编译器编译通过 + zig 产物反汇编确认 `lea` 目标 == `jmp *(%rsi)` 之后的 `ret`；`GdccCoroutineRuntimeSmokeTest` 通过
- [x] ~~金字面量断言清扫~~：**已回退**（u8 前缀保留后金字面量恢复 HEAD 原状，含 `\uXXXX` UCN 形式断言）
- [x] `CPortabilitySurfaceTest`（纯 Java）：tokenizer 剥离注释/字面量内容后断言 `nullptr`/`__int128` 代码 token 不存在（include_451 + template_451 + 真实 fixture 模块生成的全部 C 产物；`u8` 前缀经 vendored 补丁获支持，不在检查集内，类注释明示）；minicoro 内联汇编字符串内容断言无 `jmpq`/无逗号 `.type`（覆盖 riscv/i386 块的同形态清扫，补丁随之补全）且逗号形态与 `.Lmco_switch_resume` 标签在场锚定；scanner 自身正反合成用例（注释/字符串中的违规不报、代码中的违规必报、行连接单次拼接与混合 CRLF/LF 回归锚点、汇编形态正则双向区分）
- [x] `GdccOperatorPowSmokeTest`（zig 门控）：`pow_int` 边界语义锁定——2^63、2^64、2^1000、INT64_MIN/MAX 底数与指数、负底数奇偶、负指数坍缩、0^0，期望值由 Java `long` 环绕语义的同算法 oracle 计算，与 C 产物逐位一致
- [x] `MinicoroAsmEncodingTest`（zig/tcc/objdump 各自门控）：双编译器链接产物反汇编，`_mco_switch` 的 `lea` 解析目标 == `jmp *(%rsi)` 之后的 `ret`（R5 回归锁定；tcc 可执行文件无 symtab，按指令序列特征锚定）
- [x] `TinyCcCliGateTest`（tcc CLI 门控）：记录型 fake `CCompiler` 捕获 `CProjectBuilder` 的真实生产输入集（生成的 entry TU + 4 个提取的运行时 TU），tcc CLI `-std=c11 -shared`（真实链接，含 crt/libc 链路，非 `-c`）0 错误 0 诊断，`nm -D` 确认导出 `gdextension_entry`；fixture 含非 ASCII 字符串字面量，`u8"..."` + UCN 转义路径随生成代码一并过门（vendored 补丁构建的 CLI）
- [x] 验收：zig 全回归 `./gradlew clean build --no-daemon --console=plain` 全绿（4800+ 测试，含 Godot 集成）；重点定向 `CBodyBuilderLiteralValueTest`、`GdccStaticStringRuntimeSmokeTest`、`GodotAbiHeaderCompileTest`、`GdccCoroutineRuntimeSmokeTest` 均通过；上述 4 个新测试类全部实际运行（0 跳过）
- [x] review-expert-c 三轮复核收口（5+1+1 项，全部修复并复验）：`#line` 文件名改用独立的 `StringUtil.escapeLineDirectiveFileName`（tcc 的 `#line` 不做转义解码，`tccpp.c:1929` 实证；非 ASCII 与 tab 原始字节直通，`"`/`\` 转义，其余控制字符拒绝——P0-1 证据 §结论 5 附双编译器 tab 探针）；`CPortabilitySurfaceTest` scanner 修复注释 token 分隔与行连接单次拼接（含混合 CRLF/LF 二次拼接回归锚点）；`MinicoroAsmEncodingTest` 增加 `LINUX_X86_64` 平台门控；`TinyCcCliGateTest` 落实零诊断断言（输出全空白）；`TinyCcCliTestSupport` 的 `-B` runtime root 按 `GDCC_TINYCC_HOME` → 仓库 bundle → 可用二进制父目录优先级解析并校验布局（不可用时省略 `-B` 保留编译器内建路径）
- [x] u8 路线反转：vendored `tccpp.c` 透明前缀补丁落地后，u8 相关的全部可移植化改动（模板/Java 生产者/gdcc_bind.h 前缀移除、`escapeStringLiteral` 八进制改造、26 文件金字清扫、`CPortabilitySurfaceTest` 的 u8 检查）整体回退至 HEAD 原状；非 u8 改动（`{nullptr}`→`{NULL}`、`pow_int`、`__TINYC__` 分支、stdbool 注入、minicoro 补丁、`{ 0 }`、`escapeLineDirectiveFileName`）保留
- [x] 测试用 CLI 构建链：`buildTinyccCli` Gradle 任务（`gradle/tinycc.gradle.kts` 封装平台探测/路径/脚本定位函数）在 `test` 前按需构建 CLI——输入为整个 vendored 源码树与构建脚本（内容哈希），输出为 CLI 二进制 + `libtcc1.a` + `cli-verified` 标记（脚本仅在 u8 探针通过后写标记，被 kill 的半成品构建永远触发重建），不支持的平台 `onlyIf` 跳过；`build-script/build-tinycc-cli-linux-x86_64.sh`（configure + `make tcc libtcc1.a`，已本机验证）与 `build-tinycc-cli-windows-x86_64.bat`（zig cc 单文件构建 + 先合并 win32 头文件再按上游 `build-tcc.bat:make_lib` 步骤自举 win32 运行时，Windows 主机首验归 G6）为任务执行体；`TinyCcCliTestSupport` 纯查找（`GDCC_TINYCC_TCC` 覆盖 → 工作树二进制 + `cli-verified` 标记 → null 跳过），Java 侧不再启动任何构建/探测子进程，`/tmp/opencode` 与 PATH 回退已移除；运行时根布局校验按平台区分（Linux `{B}/libtcc1.a`、Windows `{B}/lib/libtcc1.a`）

注释纪律：上述改动点的注释只解释 C 语义与编译器兼容性事实（如 "tcc accepts statement expressions but does not define `__GNUC__`"），**不得引用本计划文档或任务编号**（AGENTS.md 约定）。

验收：
- zig 全回归：`./gradlew clean build --no-daemon --info --console=plain` 全绿；重点定向：`script/run-gradle-targeted-tests.sh --tests CBodyBuilderLiteralValueTest,GdccStaticStringRuntimeSmokeTest,GodotAbiHeaderCompileTest`（金字面量断言同步更新）。
- 新增 `CPortabilitySurfaceTest`（纯 Java）：扫描 `include_451`/`template_451`/生成的 `entry.c`，**忽略注释与字符串内容上下文**：`nullptr`/`__int128` 按代码 token 断言不存在（`gdcc_string.h:58`、`gdcc_string_name.h:65` 注释中的示例不算命中）；`jmpq` 与 `.type name @function`（无逗号）只在内联汇编字符串字面量中断言不存在（`minicoro.h:683,746` 的机器码数组注释不算命中）。
- tcc CLI 门（本机 mob tcc）：对从 classpath 提取的正式 include 树 + 一份生成的 `entry.c` 全量 5 TU 以 `TCC_OUTPUT_DLL` 真实链接通过（含 crt/libc 链路），0 error；禁止用 `-c` 代替。
- minicoro 反汇编断言（双编译器产物）：`_mco_switch` 中 `lea` 的目标地址 == `jmp *(%rsi)` 之后的第一条指令（`ret`），即标签化恢复地址编码正确（tcc 与 zig 两侧都验；防 R5 回归）。
- `pow_int` 语义：新增/更新针对 `pow` 边界的测试（`2^63`、`2^64`、`INT64_MIN/MAX`、负指数），zig 产物行为不变（等价性论证限定为二进制补码乘法低 64 位，最终截断到 `godot_int` 不变）。

### 阶段 3：FFM 绑定层与 TinyCcCompiler

改动：
- `TinyCcLibrary`：`SymbolLookup.libraryLookup(libPath, 进程级共享 arena)` + `Linker.nativeLinker()`；10 个 downcall（签名按 handoff §7.1 表）；惰性初始化：**失败不缓存，仅在完整绑定成功后发布进程级共享实例**（对齐 `ZigUtil` 语义；半途失败关闭临时 arena）。
- `TinyCcNative`：窄接口封装全部有状态 native 调用，测试可注入纯 Java fake。
- `TinyCcCompiler`：实现 §3.2 流程与 FFM 不变量；支持集校验；全局锁；中断点（锁等待、TU 边界、output 前）；旧产物删除；`finally` 释放顺序。
- 测试缝：注入 `TinyCcNative` fake 与 bundle 根解析器，纯 Java 驱动失败路径。

进度（实施中逐项同步）：

- [x] 权限决议（§8.1/§8.5，用户已逐条批准）：`tasks.test` 与 `EditorAddonProjectInstaller` 两个 JavaExec 任务加入 `--enable-native-access=ALL-UNNAMED`（探针实证 Gradle 测试运行于 unnamed module，FFM 默认 warn 模式下功能可用但警告刷屏；参数与 launcher 的正式运行合同对齐）；per-project 锁提取获批
- [x] `CProjectBuildLock`：per-project 构建锁提取为编译器中立 package-private 持有器（归一化绝对路径键、进程生命周期持有，语义不变）；`ZigCcCompiler` 改用之；`ZigCcCompilerProjectLockTest` 改经新持有器取锁（3 项全绿）
- [x] `TinyCcLibrary`：FFM 绑定层，10 个 downcall 签名按 handoff §7 表与 pinned `libtcc.h` 逐行核对；`Arena.ofShared` 进程级承载、不卸载；惰性加载失败不缓存（半途失败关闭临时 arena），仅在完整绑定成功后发布单例（对齐 `ZigUtil.findZigVersion` 语义）；`clearCachedLibraryForTesting` 测试钩子
- [x] `TinyCcNative` + `TinyCcFfmNative`：可替换窄接口 + 生产实现（字符串经本轮 confined arena marshal；upcall 经 `newErrorFuncStub` 注册；`callbackFailure()` 回调失败标记）
- [x] `TinyCcCompiler`：§3.2 全流程落地——bundle 解析 → 支持集/host==target 校验（不触碰 native 库）→ 空输入 → 锁序 per-project→tcc 全局（均 `lockInterruptibly`）→ confined arena → newState（NULL 拒绝）→ error handler → set_lib_path → `-std=c11` → TCC_OUTPUT_DLL → include 保序 → 逐 TU add_file（TU 边界中断检查）→ 每次 downcall 后回调失败检查 → output 前中断检查 → 删旧产物 → output_file → 普通文件校验；`finally` 顺序 delete → arena close → 解锁；固定通道 `Failed to run tcc: interrupted` / `Failed to run tcc: <msg>`；诊断逐行并入 buildLog；D7 注释明示 optimizationLevel 不影响编译参数
- [x] 探针驱动的两处实证修复：① upcall 的 ADDRESS 参数是零长 segment，必须 `reinterpret(Long.MAX_VALUE)` 后再 `getString`，否则 getString 抛越界异常、诊断静默丢失（该故障形态被"回调失败整轮失败"锚点覆盖）；② libtcc 仅在 `tcc_set_output_type` 内分配 sections/symtab，未设 output type 直接编译会在 `tccelf_begin_file` 解引用空 symtab 崩溃（libtcc 使用契约；编译器主路径本就先设 output type，库测试探针补设 `TCC_OUTPUT_MEMORY`）
- [x] 验收（G3 命令 + zig 侧回归共 156 项，0 跳过 0 失败）：`TinyCcCompilerNativeFakeTest`（20 项，纯 Java 不门控：五个 setup 步任一非零即中止且调用序列精确截断（含 add_file -3 锚点）、native 全 0 且产物已生成但回调失败 → 整轮失败 artifacts 为空、清理顺序 delete→arena close、TU 间/最终 TU 后/双锁等待中断且 interrupt 状态恢复、轮内双锁持有锚定、空输入/非 host 目标/bundle 缺失快速失败且不触碰 native、旧产物不误报成功）；`TinyCcCompilerTest`（6 项，真 bundle：手写 fixture 多 TU 合并编译 + `nm -D` 导出 `gdextension_entry`、语法错误诊断透传 + 失败通道、失败后新一轮成功（fresh state）、空输入、旧产物成功替换、全局锁等待中断）；`TinyCcLibraryTest`（6 项，真 bundle：符号绑定与基本调用、诊断拷贝语义（4k 长消息/逐 state 触发）、handler 异常入失败槽且不穿透 native 帧、失败不缓存/成功仅加载一次）；`TinyCcCompilerParallelTest`（2 项：fake 驱动确定性证明全局锁串行（第一轮阻塞期间第二轮确在全局锁排队）+ 真 bundle 双项目并发各编译成功且 nm 符号隔离）；`TinyCcBundleTest` 与 zig 侧 `ZigCcCompiler*`/`CProjectBuilder*`/`TinyCcCliGateTest` 等回归全绿
- [x] review-expert-c 复核收口（4 项：修复 2、拒绝 2）：①修复——bundle 安装锁等待中断经 resolver IOException 通道漏出，现按 resolver 已恢复的中断位改走固定通道 `Failed to run tcc: interrupted`（`TinyCcCompilerNativeFakeTest.interruptDuringBundleResolutionSurfacesTheFixedChannel` 锚定）；②修复——`TinyCcCompilerParallelTest.globalLockSerializesConcurrentRounds` 由 sleep 统计探针改为确定性证明：第一轮阻塞在 fake addFile 内（持全局锁），`hasQueuedThread(threadB)` 锚定第二轮确在全局锁排队、`secondInsideAddFile` 锚定其未提前进入，并经变异测试验证（移除全局锁后该测试确定性失败）；③拒绝——"output/delete 期间收到中断仍报成功"：D4 与阶段 3 改动清单将合作式取消点穷举为锁等待/TU 边界/output 前，晚到中断不在取消面内属设计取舍（zig 在链接等待/输出收集结束后的最终返回前存在同类窗口），且中断位不被清除、API 层取消映射不受影响（review 复核对 zig 窗口位置的措辞已按其证据收窄）；④拒绝——"中断丢弃已收集诊断"：D4 明确 buildLog 为固定通道且与 zig 通道形态对齐（zig 取消同样不合并任何输出），API 层 CANCELED 映射本就丢弃 buildLog
- [x] review-expert-c 第二轮复核：两项修复判定已解决、两项拒绝判定成立；其新增指出的并发测试失败路径不等待工作线程退出的清理缺口（可能把全局锁泄漏进后续测试类）已修复——`finally` 无条件释放 latch、中断并等待两个工作线程（best-effort，不掩盖主断言）；复测 89 项（G3 五类 + zig 锁/并行/失败回归 + TinyCcCliGateTest）0 跳过 0 失败

验收（真 bundle 用 `Assumptions` 门控，模式对齐 zig 测试约定；fake 驱动的不门控）：
- `TinyCcCompilerTest`：多 TU 成功编译（**手写 fixture C 输入**，不依赖阶段 2 的生成代码移植，含一个导出 `gdextension_entry` 的 fixture）、产物存在且导出该符号（Linux 用 `nm -D`，Windows 用 PE 适用工具如 `dumpbin`/zig 自带工具，不机械照搬）、语法错误输入的诊断文本透传与失败通道、失败后新一轮成功（fresh state）、中断通道（锁等待取消、TU 间取消、最终 TU 后取消且恢复 interrupt）、空 `cFiles` 失败、旧产物存在但 output 失败不误报成功。
- `TinyCcCompilerNativeFakeTest`（纯 Java）：setup 各步（options/output_type/include/add_file/output）任一失败即中止且不继续后续调用、**native 全部返回 0 且产物存在但回调失败时令整轮失败且 artifacts 为空**、清理顺序（delete → arena close）。
- `TinyCcLibraryTest`：符号绑定齐全；upcall 拷贝语义（长消息、多次触发）；初始化失败可重试、成功后只加载一次。
- 并发：`TinyCcCompilerParallelTest` 证明全局锁串行且结果正确（两项目并发各编译成功）；类级保持 `same_thread`，并发由测试内部创建（对齐 §8 测试约定）。

### 阶段 4：编译器选择接线与 launcher 行为变更

改动：`CCompilerKind`、`CompileOptions`（含兼容构造器与 null 归一）、`CProjectInfo`、`CProjectBuilder`（EnumMap/强制编译器语义、移除 `setCCompiler`）、`GdccCommand --compiler`、`CompileTaskRunner` 透传、RPC wire 政策（§3.3）与 `EditorAddonProjectInstaller` 构造点兼容（其多平台任务继续固定 zig）；launcher zig 发现降级为可选（§8 确认项）。

**用户决议的修改（覆盖 §3.3/D6 的默认语义）**：launcher 与 API 的默认后端按优化级别分派——debug 默认 `TINY_CC`、release 默认 `ZIG_CC`（§9 的定位表述不变：zig 仍是发布后端）；API  additionally 提供可用后端查询。落地形态：

- `CompileOptions.cCompilerKind` 为 `@Nullable`：`null`（含 wire 缺失/显式 null）表示自动，由 `effectiveCCompilerKind()` 经 `CCompilerKind.defaultFor(optimizationLevel)` 解析（debug→tcc、release→zig）。原"null 归一为 ZIG_CC"的紧凑构造器方案被本决议取代。
- `CProjectInfo` 6 参为规范构造器（runner 总是透传已解析的 kind）；5 参兼容构造器固定 `ZIG_CC`——后端内部调用点的既有 zig 语义不变（全部既有 zig 集成测试零扰动），级别自动规则只存在于 API 边界。
- 后端可用性查询：`CProjectBuilder.availableCCompilerKinds()`（静态，逐 kind 探针：zig=可发现性，tcc=bundle 存在性，均无副作用）→ `API.availableCCompilerKinds()` → RPC `server.availableCCompilerKinds`。

进度（实施中逐项同步）：

- [x] `CCompilerKind`（`ZIG_CC`/`TINY_CC` + `defaultFor` 级别默认策略）
- [x] `CompileOptions` 第 7 组件 `cCompilerKind`（`@Nullable`，null=自动）+ 6 参兼容构造器 + `effectiveCCompilerKind()`
- [x] `CProjectInfo` 增加 `cCompilerKind`（6 参规范 + 5 参兼容固定 `ZIG_CC`，equals/hashCode 覆盖）
- [x] `CompileTaskRunner` 透传 `effectiveCCompilerKind()`
- [x] `TinyCcBundle.isHostBundlePresent`/`isBundlePresent` 无副作用探针（env override 权威、缓存 `.ready`+布局、classpath 可安装性；不安装/不哈希/不加载 native）
- [x] `CProjectBuilder`：无参=`EnumMap` 双实现按 `CProjectInfo.getCCompilerKind()` 逐轮分发；单参=强制编译器忽略 kind；包私 `Map` 构造缝要求覆盖全部 kind；移除 `setCCompiler`/`getCCompiler`；`availableCCompilerKinds` 静态查询 + 探针注入缝
- [x] `API.availableCCompilerKinds()`（只读、无副作用，close 后仍可用）
- [x] `GdccCommand --backend zig|tcc`（缺省=自动；专用解析器固定 `zig|tcc` 拼写；非法值 `EXIT_USAGE`；`-vv` 渲染含解析后 kind）
- [x] RPC：`server.availableCCompilerKinds` 方法注册
- [x] `EditorAddonProjectInstaller` 显式固定 `ZIG_CC`（多平台交叉编译继续走 zig）
- [x] launcher `main.zig`：zig 发现降级为可选——找不到/搜索出错不再 `exit(1)`，仅不设置 `ZIG_HOME`（搜索错误仍打印警告）；三目标 `zig build` 通过
- [x] 修复阶段 3 遗留缺陷（由本阶段 launcher 端到端 smoke 暴露）：`TinyCcLibrary` 的 `delete`/`setLibPath`/`setErrorFunc` 使用表达式 lambda 体调用签名多态 `invokeExact`，javac 将其静态类型绑定为 `Object` 返回（字节码 `pop` 丢弃），与 `(MemorySegment...)void` 的 handle 运行期精确性检查冲突抛 `WrongMethodTypeException`；改为块体语句表达式（语句位置才静态类型为 void）。`TinyCcCompilerTest`/`TinyCcLibraryTest` 复测 12/12 全绿
- [x] 单元/集成测试（全部新增或扩展，0 跳过）：`ApiCompileOptionsTest`（兼容构造器 auto 语义、`effectiveCCompilerKind` 级别矩阵、显式 kind 双向覆盖、defaults 为 debug+auto、API set/get 往返）；`CProjectInfoTest`（5 参兼容固定 zig、6 参携带 kind、null kind 拒绝、equals/hashCode 含 kind）；`CProjectBuilderCompilerSelectionTest`（双 fake 按 kind 分发且互不触达、单参强制忽略 kind、EnumMap 缝缺 kind fail-closed、`availableCCompilerKinds` 探针注入正反与生产探针一致性）；`TinyCcBundleTest` 探针三项（env override 权威不回落、缓存 `.ready`+布局门槛与版本键隔离、classpath 可安装性回退）；`GdccCommandOptionTest`/`GdccCommandInputTest`（`--compiler` 声明集/帮助文本/解析缺省 auto/显式 zig|tcc/大小写/非法值 EXIT_USAGE 且不创建模块）；`RpcJsonCodecTest`（wire 形状含 `cCompilerKind`、枚举表、缺失/显式 null=auto、双 kind 往返、未知值 `JsonSyntaxException`）；`JsonRpcDispatcherTest`（25→26 方法、`server.availableCCompilerKinds` 与 API 探针一致、options.set 显式 kind 回显/缺省 null/未知值 -32602 且不污染已存快照）；`ApiCompileOptionsFreezeTest`（冻结后选项变更：在途轮次冻结快照保持 tcc、模块当前选项演进为 zig——放 api 包是因冻结观察需要 `ApiCompileTestSupport` 的 latch 编译器缝，该缝对 rpc 包不可见；wire 侧经 `CompileResult.compileOptions` 形状覆盖）
- [x] launcher 端到端 smoke（`LauncherSmokeTest`，linux-x86_64 门控，本机 4/4 真实运行 0 跳过）：`--backend tcc` 在 zig 环境变量/PATH 全部缺席下经正式 launcher 完成完整管线编译并产出共享库；`ZIG_HOME` 在 zig 可发现时照常传播；经 user+mount namespace 用 `/dev/null` 绑定遮蔽全部可达 zig 后——launcher 不再 `exit(1)` 且无 `ZIG_HOME` 正常引导 JVM；`--backend zig` 在 zig 不可达时由 `ZigCcCompiler` 报出既有 "Zig executable not found on PATH or known locations" 通道（exit 1）
- [x] CLI 选项重命名（用户决议）：`--compiler` → `--backend`；`GdccCommand` 字段/解析器/报错文案、`GdccCommandOptionTest`/`GdccCommandInputTest`/`LauncherSmokeTest` 与本节引用同步更名（`CCompilerKind`/`cCompilerKind`/`availableCCompilerKinds` 等 API 命名不变）；复测 CLI 四测试类 + `LauncherSmokeTest` 全绿（0 跳过）
- [x] 既有 RPC/Godot 集成测试固定 zig（默认翻转的预期追随）：`RpcCompileHttpIntegrationTest` 与 `EditorAddonBootstrapEngineTest` 的环境门控提供的是 zig 而非 tcc bundle，其 `options.set` 显式置 `cCompilerKind=ZIG_CC`
- [x] 验收：全量 `./gradlew build buildZigLauncher` 全绿（4800+ 测试）；上列全部新增/受影响测试 0 跳过 0 失败（`LauncherSmokeTest` 因 `buildZigLauncher` 与 `test` 无任务依赖需在其产物就绪后单独重跑，已单独复测 4/4）；事实源文档已同步（`rpc_api_implementation.md` §3.4/§3.7/§4.5/§14、`json_rpc_service_implementation.md` §2.2/§2.3、`cli_implementation.md` §2/§5/§10/§11、`backend_build_system_implementation.md` §1）
- [x] review-expert-c 复核收口（3 项全部修复并复验）：①`CProjectBuilder` 的 zig 可用性探针原直连 `ZigUtil.findZig()`，其 `which`/`where` 分支在 spawn 失败时会置调用线程中断位，违反查询的无副作用合同——`ZigUtil` 提取无 spawn 的 `isZigDiscoverable()`（env/PATH/常见路径，与 `findZig` 覆盖等价：`which` 只搜 PATH），探针改接之，新增调用线程中断位双向不变性测试与 spawn/无 spawn 发现一致性锚点；②`LauncherSmokeTest` 的 tcc 端到端用例原只清洗 env/PATH，zig 仍可能经 launcher 常见路径被发现从而掩盖错误分发——现同样经 namespace 遮蔽全部可达 zig；③`TinyCcBundleTest` 的 override 权威性探针测试原先没有可用回退路径（断言无法区分"不回落"与"无回落对象"）——现建立 `.ready` 缓存与 classpath 双回退，正反断言：null/空白 override 回落可用、无效 override 不回落
- [x] review-expert-c 第二轮复核：原 3 项确认解决；其新增指出的 Windows 发现范围问题已修复——`where` 在 Windows 会额外搜索当前目录，无 spawn 探针遗漏该通道会少报 `ZIG_CC`；`isZigDiscoverable` 经 OS 注入缝补上 Windows 当前目录检查，`ZigUtilTest.discoveryPeekMirrorsWhereCurrentDirectoryRuleOnWindows` 正反双向锚定（正：CWD 内 `zig.exe` 可发现；反：非 Windows 不看 CWD、不存在的名字恒报缺失），并修正一致性测试的等价性注释
- [x] review-expert-c 第三轮复核：Windows CWD 修复确认解决；其指出的测试密封性问题已修复——探针负例原先仍读真实环境（`ZIG` 等 env 直接命中时不查 exe 名，负例会在特定主机误失败）且 Linux 负例查询名与 fixture 名不一致；`isZigDiscoverable` 改为带回退注入的 4 参缝（生产 0 参直连真实发现），测试以固定 `false` 回退完全隔离宿主环境，负例查询名与 fixture 严格一致；复测 `ZigUtilTest`(6)/`CProjectBuilderCompilerSelectionTest`(6) 全绿

验收：
- `./gradlew classes` 编译通过；既有 API/CLI/后端测试全绿（6 参 `CompileOptions` 与 5 参 `CProjectInfo` 兼容构造器保持既有调用点编译与 zig 语义；新默认仅在 kind 未指定时按级别自动分派）。
- 新增：`CompileOptions` 默认值/兼容构造器/`effectiveCCompilerKind` 级别矩阵测试；`CProjectInfo` 兼容构造器固定 zig 与 equals/hashCode 测试；`CProjectBuilder` 双编译器选择测试（fake `CCompiler` 双注入断言按 kind 分发；单参构造忽略 kind；`availableCCompilerKinds` 探针注入）；CLI `--compiler` 解析与非法值报错测试；RPC 测试（`RpcJsonCodec`/`JsonRpcDispatcher` 层面）：字段缺失为自动（null）、**显式 null 同缺失**、两种编译器往返、未知值报错、冻结后选项变更。
- launcher：全机 zig 发现位置均不可用时，`--backend tcc` 经正式 launcher 完成编译；`--backend zig` 时报 zig 缺失的既有错误通道。

### 阶段 5：Linux x86_64 端到端验收

进度（实施中逐项同步）：

- [x] HRX 运行时行为门（tcc 产物）：`HrxSmokeProbeSources` 承载 zig 基线与 tcc 门共享的字节一致探针源码（FAKE_ENGINE + execmem/四类 thunk/rebind 失配/retain 四项，`GdccHrxRuntimeSmokeTest` 改为引用同一常量，24 项回归 0 跳过全绿——提取保真度由同一断言集复绿锚定）；`RuntimeProbeToolchain` 双编译器 harness（zig `-std=c23` / tcc `-std=c11` + `-B` 运行时根，include/define/运行时 TU 列表完全一致，tcc Linux 腿补 `-lm -lpthread`，Windows 自动 `.exe` 后缀与 CR/LF 归一）；`TinyCcHrxRuntimeGateTest` 4 项 tcc 门 0 跳过全绿（execmem RW→RX 往返、四类 thunk 真实机器码执行、retain 跨代等值查找键、rebind 兼容升级 + 描述符失配 fail-closed）
- [x] 泄漏/稳定长跑：`TinyCcCompilerStabilityTest`（bundle 门控，本机实际运行 0 跳过）——3 轮预热 + 12 轮交替（成功 / 语法错误失败 / 确定性取消：包装 `TinyCcNative` 在第 2 个 TU 边界自中断，部分填充状态走完整 delete→arena 关闭清理路径），断言每轮结果形状与固定中断通道、interrupt 位恢复并在测试线程清除；内存观察为 post-GC heap（全平台断言 ≤32MiB）；句柄观察为 `/proc/self/fd` 计数（Linux 腿断言 ≤+2，Windows 无等价 JDK 面故跳过该腿）；committed VM 仅打印不断言（进程级高水位指标在共享测试 JVM 内会被兄弟类污染——实测隔离运行 +0K 而多类同 JVM 运行时一度被抬升 32GiB，归因不可定位故不设断言）。本机实测增长 heap +32K / committed +0K / fd +0
- [x] 同一 includeRoot 上 zig→tcc→zig 交替回归：`CrossBackendIncludeStabilityTest`（zig+bundle 门控，本机 1/1 实际运行）——生产布局的 `shared-include` 兄弟目录（空 environment map 屏蔽 CI 的 `GDCC_SHARED_INCLUDE`），zig→tcc→zig→tcc→zig 五轮真实管线交替于同一 projectDir；自第一轮起对整树（路径集 + size + mtime + sha256）逐轮比对，锚定 `ResourceExtractor` 内容比较跳过后 tcc 轮次对共享 include 树零触碰（保护 zig PCH/对象缓存的 mtime 键）；每轮产物形状校验（ELF/PE）且 tcc 轮 buildLog 无 `Command:` 段（合同差异锚定）
- [x] 双后端产物竞争：`CrossBackendProjectLockTest`（本机 3/3 实际运行）——①确定性互斥：fake native 阻塞于 addFile 的 tcc 轮持双锁期间，真实 zig 轮被观测为在 per-project 锁上排队（`hasQueuedThread`），tcc 完成后 zig 才执行，最终产物经形状 + `nm` 导出断言为 zig 完整产物（无半成品/交错）；②反向：测试线程直持共享 project 锁时 tcc 轮排队且 native 工厂零调用（工厂仅在双锁获取后执行）；③真实 zig/tcc 在同一输出名上交替 3 轮，每轮产物形状 + `gdextension_entry` 导出校验。配套：`NativeArtifactInspector` 统一跨平台产物断言（Linux `nm -D` / Windows 纯 Java PE 解析），`TinyCcCompilerTest`/`TinyCcCompilerParallelTest` 的 Linux-only 私有导出断言迁移至其上（Windows 上不再静默跳过导出断言——G6 验证面扩宽）
- [x] 性能参考值（本机实测，临时探针测量后已删除，不入库）：fixture = `coroutine/await_signal_basic` 真实生成 entry.c + 4 运行时 TU，DEBUG 全量单轮——**tcc ≈ 99ms（无缓存，每轮全量）**；**zig 冷启动（PCH+对象缓存全冷）≈ 18922ms**；**zig 热缓存 ≈ 120-157ms**。tcc 全量编译快于 zig 热增量（进程内无子进程开销），与 §9"快速迭代备选后端"定位一致；zig 冷启动数字含一次性 PCH 构建，同项目后续轮次摊销。该组数字供 PR 描述引用，不进 CI 断言
- [x] review-expert-c 第一轮复核收口（8 项 + 1 项覆盖声明修正，全部修复并复验）：①TLS 探针消除 C 层数据竞争——volatile 自旋 + 共享计数器改为 mutex/condvar 真屏障（pthread 与 Win32 双实现）+ 每线程独立失败槽（join 后汇总），且屏障置于协程体内 step 0，四个协程同时处于运行态形成确定性重叠窗口（共享 `mco_current_co` 时至少三个线程必然观测到他者，确定性失败而非统计性）；②③④`PeExportReader` 加固——RVA 映射校验完整读取范围且拒绝落在 section 虚拟零填充尾区（`delta+len ≤ rawSize`）、`e_lfanew` 先 unsigned long 校验再收窄、PE32+ 固定头最小 112 字节、读取 `NumberOfRvaAndSizes` 区分"未声明"与"声明但截断"、导入表以声明的目录尺寸为界且终止符必须五 DWORD 全零、目录内无终止符即 fail-closed；⑤`RuntimeProbeToolchain` 子进程全部经 `runProcess`：虚拟线程异步收集输出 + 编译/运行双 deadline + 超时/中断一律 destroyForcibly 并收敛进程与 reader（Windows 上残留进程会锁住临时目录 DLL）；⑥`CrossBackendProjectLockTest` 反向用例改为幂等 finally 清理（锁持有时释放、worker 存活则中断并等待）；⑦`TinyCcCompilerStabilityTest` 取消轮先捕获并清除 interrupt 位再断言（finally 兜底），断言失败不再污染执行线程；⑧注释纪律——清除测试类注释中对计划阶段编号的引用（G5/G6 字样），`gdcc_call.h` 分支注释改为描述栈上参数编组的真实理由；⑨动态调用探针补 bool 实参（`_GDV` 六类全覆盖）——暴露并处理真实的跨标准分歧：C23 `true` 是 bool 关键字而 C11 stdbool 是 int 宏，裸字面量在两个标准下 `_Generic` 派发不同（zig 腿 BOOL / tcc 腿 INT），探针改用 bool 变量锚定生成代码的实际形态（类型化值派发一致），该差异对生成代码无影响（生成代码从不传裸字面量）。`PeExportReaderTest` 扩至 13 项（合成 PE 构建器：正解析 + 截断/越界/伪造签名/PE32 magic/声明但截断目录/零填充尾区/目录尺寸说谎/部分零终止符/无终止符/非终止名串）
- [x] review-expert-c 第三~五轮复核收口：①TLS 探针确定性闭环——四组一次性屏障（主协程/取消目标 × 进入/采样完毕）消除顺序调度假通过路径（barrier 成对，任一线程未完成身份采样前不得 yield）；②Windows 屏障实现换用 vendored winapi 实际具备的 CRITICAL_SECTION + manual-reset Event（原 CONDITION_VARIABLE/SleepConditionVariableCS 在 vendored 头文件与 def 中均不存在，属 Windows CI 阻塞级缺陷，已核实 `winbase.h`/`kernel32.def` 两侧证据）；③`cString` 同时受 section 原始范围与文件物理长度约束，负例改为 section 声明越出 EOF（旧实现会 AIOOBE 而非 fail-closed）；④`runProcess` 根因级重写——输出捕获从管道+reader 线程改为**文件重定向**（`redirectOutput` 临时文件），进程收敛即输出完备，结构性消除"孙进程持管道导致 reader 无法终止"（Linux 跨线程 close 不保证唤醒阻塞 read 的 OS 级缺陷）；⑤中断路径统一：waitFor/成功路径 join 的中断均延迟清理后恢复并传播原异常；⑥Windows 文件句柄共享语义下后代持柄会使 `deleteIfExists` 抛错——日志删除改为 best-effort（失败降级 `deleteOnExit`），不掩盖进程结果。新增 `RuntimeProbeToolchainTest` 3 项 harness 自测（干净退出合并 stderr、`exec sleep` 超时强杀、孙进程持柄不停滞）。最终全量受影响测试 156 项 19 类 0 跳过 0 失败；reviewer 终审确认 round-4 根因闭环、无新增阻塞项，Windows 主机验收（G6）保持显式待决状态
- [x] C 探针资源外置（用户追加要求）：本次涉及与相关的全部大段内嵌 C 从 Java text block 提取至 `src/test/resources/c/`（`hrx/` 24 个——FAKE_ENGINE + 全部 23 个探针，含 `GdccHrxRuntimeSmokeTest` 既有探针的统一外置；`dynamic_call_probe.c`/`minicoro_tls_probe.c`/`pow_int_boundary_probe.c` 模板含 `@@POW_CASES@@` 生成占位）；新增 `CProbeSources` 资源加载器（缺失即硬错误，防空探针假通过）；`HrxSmokeProbeSources` 保留常量门面、改为资源加载。提取经 Java text block 语义（缩进/转义/尾随空白）机械还原，行为锚定：全部精确输出断言（parity 冻结事件序列、HRX 事件序列、冻结 thunk 字节表）复绿即保真证明。复跑：156 项 19 类 0 跳过 0 失败
- [x] 动态调用（`GD_OBJECT_CALL*`）运行时行为门：`GdccCallMacroRuntimeParityTest` 双编译器 parity——`_GDV` `_Generic` 六类派发（int/double/bool/StringName/Vector3/Object）、方法名前置为 StringName Variant、参数逐一销毁平衡、返回值传播，zig/tcc 两腿输出逐行一致且与 Java 侧冻结期望一致（3 项全绿）。探针顺带暴露并修复了 `gdcc_call.h` 两个预存缺陷（均属从未被执行过的路径）：①GNU 分支 `GD_OBJECT_CALL0` 多传第 3 实参给 2 参宏 `GD_CALLV0_EXPR`（预处理器报错，该宏此前不可展开）；②fallback 分支 `gd_call_variadic` 把 `godot_Variant*` 值数组当作 `const godot_Variant**` 指针数组传给 `gdcc_callv_variants`（运行时必崩）。修复后强制 fallback 构建验证通过且行为与 GNU 分支等价；语句表达式分支的零分配锚点（`scalar_call_large_allocs`：fallback=1 / GNU=0）经双向验证有判别力。回归：`TinyCcCliGateTest`/`CPortabilitySurfaceTest`/`GdccCoroutineRuntimeSmokeTest` 全绿
- [x] 协程四线程 TLS 隔离与取消门：`MinicoroTlsIsolationParityTest` 双编译器 parity——四线程各自创建主协程 + 取消目标协程，rendezvous 后交错 resume/yield 共 250 步，逐步校验 `mco_running()` 线程本地身份，中途 mco_destroy 取消挂起协程不影响本线程主协程；探针 TU 携带 `#error` 编译期锚点禁止 `MCO_NO_MULTITHREAD` 回退（已用 `-DMCO_NO_MULTITHREAD` 负向验证该锚点必然触发）；线程 API 经 `#if _WIN32`（CreateThread）/ pthread 双实现，Windows CI 直接可跑；3 项全绿。生成代码层的协程行为（await/cancel/信号）由 tcc test_suite 子集（coroutine/ 22 用例）在真实 Godot 中覆盖（见下）
- [x] `TinyCcCompilerIntegrationTest`（bundle 门控 + Godot 门控分离，本机 2/2 实际运行 0 跳过）：最小 GDScript fixture（int 属性 + 两方法）经真实 frontend→lowering→codegen→`CProjectBuilder` 全管线由 `CCompilerKind.TINY_CC` 分派编译（dispatch map 注入缝 + `setIgnoreSharedInclude(true)`，不依赖环境变量）；断言 `.gdextension` 内容（entry_symbol/reloadable/宿主 library key 绑定到产物）与 `extension_list.cfg`；Godot 两腿——行为腿（`GodotGdextensionTestRunner`：stop signal + 方法调用/属性读取穿越 Variant 边界 + 无 FAIL）与 shutdown 腿（直接进程等待完整退出，exit 0 且输出含 `Test stop.` 与 PASS，对齐 handoff §8 的 shutdown 验证项）；Godot 项目为 `test_project` 约定的私有副本（@TempDir，非共享目录），Windows 主机经 PE 形状断言与同一 Godot 门直接复用
- [x] test_suite tcc 子集（本机 40/40 实际运行 0 跳过）：`GdScriptUnitTestCompileRunner` 新增 `compileAndValidate(path, runOptions, forcedCompiler)` 注入缝（null 保持既有按 kind 分派路径，既有签名语义不变）；`TinyCcCliTestSupport` 公开化并新增 `newBundleCompiler()`（FFM 后端绑定到测试发现的 bundle）；`GdScriptUnitTestTinyCcSubsetTest` 以 `@TestFactory` 跑 coroutine/（22）+ string_format/（8）+ runtime/string_literal_*（3，Unicode/转义面）+ 属性绑定（builtin_property_* 3 + abi property_roundtrip 3）+ runtime/dynamic_call（1），子集经发现过滤推导并锚定具名用例存在性（套件增长不会静默失同步）；与 zig 套件共用 runner 与 pass marker 合同，行为 parity 直接成立。pow 项由 `GdccOperatorPowSmokeTest` 新增 tcc 腿覆盖（同一探针经 `RuntimeProbeToolchain` 双编译器跑 Java oracle 边界矩阵，2/2 全绿——套件无 pow 用例，C 层 oracle 比对是该行为的最强锚点）

- `TinyCcCompilerIntegrationTest`（`Assumptions` 门控，需要 bundle + 可选 Godot）：最小 fixture 模块全管线编译 → `.gdextension` 生成 → 产物加载冒烟（复用既有 Godot 校验夹具的目录约定，禁用共享目录）。
- HRX/运行时行为门（tcc 产物，Godot 可用时必跑，记录非零跳过声明）：execmem probe、四类 thunk 执行、保留 Callable 后卸载/重载、rebind 与失配失效；动态调用（`GD_OBJECT_CALL*`）运行时行为；生成协程四线程 TLS 隔离与取消；与 zig 产物行为一致。
- 用 `--backend tcc` 跑 test_suite 的一个子集（协程、字符串、pow、属性绑定用例），Godot 编辑器内行为与 zig 产物一致。
- 泄漏/稳定长跑：成功/失败/取消交替轮次的内存与句柄观察。
- 同一 includeRoot 上 zig→tcc→zig 交替构建回归（共享 include 树内容比较跳过不影响 tcc 轮次）。
- 双后端产物竞争：zig 与 tcc 对**同一 `projectDir`、同名输出**并发构建，验证 per-project 锁互斥（固定顺序 project lock → tcc global lock），无半成品/交错删除。
- 性能参考值记录（DEBUG 增量/全量），写入 PR 描述，不进 CI 断言。

### 阶段 6：Windows 支持

进度（实施中逐项同步）：

- [x] bundle 脚本产出 windows-x86_64 包：Linux 交叉路径 `build-script/build-tinycc-bundle-windows-x86_64.sh` 本机重跑成功（`build/tinycc-bundle/windows-x86_64/` 产物齐全）；新增 Windows 主机路径 `build-script/build-tinycc-bundle-windows-x86_64.bat`（zig cc 产 `libtcc.dll`，复用 CLI 工作树组装，PowerShell 生成 sha256sum 兼容 MANIFEST，MZ+布局结构校验；首批真实运行归 CI）。parity 门布局清单补入 `include/tcclib.h`（两平台 bundle 均携带）
- [x] 交叉构建 bundle 的主机侧结构门：`TinyCcWindowsBundleParityTest`（5 项，本机实际运行 0 跳过）——全布局合同逐项存在（合并 include 树 + `include/winapi/`、`lib/libtcc1.a`、五个 def、license）、VERSION 三键精确匹配（bundleFormat=1 / pinned commit / platform）、MANIFEST 全覆盖且逐文件 sha256 匹配（fail-closed）；`PeExportReader`（新增纯 Java PE/COFF 解析器，替代 `dumpbin`——CI Windows runner 无 MSVC 工具链）断言 `bin/libtcc.dll` 为 AMD64 DLL、导出覆盖 FFM 绑定的全部 10 个符号、导入仅系统 CRT/kernel 模块（api-ms-win-crt-*/msvcrt/kernel32）；`PeExportReaderTest` 6 项正反用例（非 PE/截断头/越界 e_lfanew/伪造签名/PE32 magic 拒绝 + 最小 PE32+ 壳正解析），不依赖任何 fixture
- [ ] Windows 主机端到端验证（FFM 五 TU 编译、诊断回调、fresh-state、Godot 注册/属性/引擎调用、HRX 门、shutdown）：**归 CI**——现有 tcc 测试类（`TinyCcCompilerTest`/`TinyCcLibraryTest`/`TinyCcCompilerParallelTest`/`TinyCcCompilerStabilityTest`/`TinyCcHrxRuntimeGateTest`/`TinyCcCompilerIntegrationTest`/`GdScriptUnitTestTinyCcSubsetTest` 等）已全部跨平台化（PE 解析/`.exe`/`lib/libtcc1.a` 路径/CR-LF 归一均就绪），在 bundle 可用时于 Windows CI 全部真实执行。~~CI 侧尚需一处接线决策~~ **用户已批准并落地（§8.7）**：`ci.yml` 的 windows-x64 job 在测试前经 `build-tinycc-bundle-windows-x86_64.bat` 于 Windows 主机构建 bundle（zig cc 单文件产 libtcc.dll + 复用 CLI 工作树的合并头/libtcc1.a/def + PowerShell 生成 sha256sum 格式 MANIFEST + MZ/布局校验），linux-x64 job 同步接入既有 `.sh`（此前两平台 CI 均不构建 bundle，FFM 测试全部 skip——本次一并接通）；`push.paths` 补入 `build-script/**`。静态复核（review-expert-a 两轮）收口：MANIFEST 写入改绝对路径（PS 5.1 的 `Set-Location` 不同步 `[System.IO.File]` 解析用的进程工作目录）+ 单文件数组安全 + `-LiteralPath`/`ToLowerInvariant`/无 BOM UTF-8。G6 以 Windows CI 实际运行结果为准，未通过前支持声明保持不变
- [x] `CreateFiberEx` 缺口确认不涉及：fiber 后端禁用合同不变（`gdcc_runtime_lib.md` 未动），阶段 6 不做任何 fiber 改动

### 阶段 6 原条目（验收口径，保留备查）

- bundle 脚本产出 windows-x86_64 包（阶段 1 交叉或主机路径）。
- 在 Windows 主机验证：FFM 五 TU 编译、诊断回调、fresh-state 重复、Godot 注册/属性/引擎调用、HRX 门（同阶段 5 清单）、shutdown（对齐 handoff §8 验证表）。
- `CreateFiberEx` 缺口不涉及（fiber 后端禁用，`gdcc_runtime_lib.md` 合同不变）。
- G6 未通过前，Windows 不得在支持声明中标记完成。

### 阶段 7：文档收口

- `backend_build_system_implementation.md`：新增 TinyCcCompiler 职责段、日志/取消差异合同、§8 测试锚点清单与并行约定更新。
- `doc/gdcc_c_backend.md`：编译器后端章节补 tcc 路径；可移植化落地的实际差异（`{nullptr}`→`{NULL}`、`pow_int` 中间量、`__TINYC__` 分支、minicoro 补丁、stdbool 注入）随阶段 2 同步修订（`u8"..."` 合同不变）。
- `doc/gdcc_runtime_lib.md`：minicoro tcc 适配补丁登记（`__TINYC__` 分支清单）。
- `doc/module_impl/api/rpc_api_implementation.md`、`json_rpc_service_implementation.md`：`cCompilerKind` 字段表与取消延迟预期修订。
- 分发说明：两后端的 libc/构建环境合同（D8）与 launcher 行为变更。
- 本计划文档按归档约定转化或归档；`tmp` 探针证据不入库。
- 事实源文档只在对应实现与验收完成后更新（AGENTS.md 约定）。

## 6. 风险登记册

| # | 等级 | 风险 | 成因链路 | 缓解 |
|---|---|---|---|---|
| R1 | 高 | FFM 无故障隔离，native 崩溃终止 JVM | D1 取舍，tcc 内部状态机复杂 | 文档明示；worker 进程隔离列后续方向；失败状态即弃（D3） |
| R2 | 低 | Windows 侧 Unicode/转义正确性未证明 | `escapeStringLiteral` 的 `\u`/`\U` 在 Linux x86_64 已双编译器验证（P0-1 字节矩阵 + `TinyCcU8StringTest`），Windows 执行字符集映射未验证 | G6 Windows 验收时补非 ASCII 字符串用例双编译器对比 |
| R3 | 中 | Linux 系统头/CRT/libc 链路在进程内 tcc 下未验证 | handoff 只验证 Windows（自带 winapi 头）；Linux 依赖 `configure` 产物的内建路径 | P0-3 探针 + G1 移位 smoke（真实 DLL 链接）+ `ldd` 记录；不足则 `tcc_add_sysinclude_path` 显式补充 |
| R4 | 中 | tcc 产物依赖宿主开发头/CRT/库构建，构建环境要求高于 zig（zig 用 bundled sysroot） | 两后端产物均动态链接 glibc；差异在构建时自给程度与可复现性 | D8 文档分述；分发指南按编译器注明构建环境要求；产物兼容性以实测动态依赖为准 |
| R5 | 高 | tcc 内联汇编器对 RIP 相对硬编码常量位移误编码（少 4 字节） | `leaq 0x3d(%rip)` → tcc 编码 `0x39`；`tmp/minicoro-patch-verify/EVIDENCE.md` 实测 | minicoro 恢复地址改标签形式（阶段 2 必做项）；验收含反汇编断言 |
| R6 | 中 | minicoro vendored 补丁在升级时丢失 | 第三方单文件库就地修改 | 补丁点就地注释 + `gdcc_runtime_lib.md` 登记 + `CPortabilitySurfaceTest` 锁定关键形态 |
| R7 | 中 | 并发/重入/泄漏未测 | handoff §7.3 明示未测域 | D3 全局锁 + 每轮新 state；`TinyCcCompilerParallelTest`；阶段 5 长跑 |
| R8 | 中 | `pow_int` 改 `uint64_t` 后语义偏差 | 128→64 位中间量 | 二进制补码乘法低 64 位等价（最终截断到 `godot_int`）；边界测试双编译器锁定 |
| R9 | 中 | HRX/动态调用/协程取消在 tcc 产物下未验 | handoff §12 明示验收缺口 | 阶段 5/6 强制行为门；未过不得声明平台支持 |
| R10 | 低 | tcc 忽略部分 `__attribute__`（visibility/pure/const） | tcc 解析后丢弃语义 | 导出符号默认可见即可用；优化提示丢失不影响正确性 |
| R11 | 低 | bundle 分发 license 合规 | tcc LGPL-2.1、winapi 头等上游许可 | 阶段 1 随包 `COPYING`/`RELICENSING`；发布前 license 审查（handoff §6.2 同要求） |
| R12 | 低 | linux-aarch64 tcc 未实测 | 无该架构探针证据；`__arm64_clear_cache` 为源码级推断 | 首期不支持集排除，实测后再开放 |
| R13 | 低 | bundle 用户缓存目录无容量管理 | 与 zig cache root 同性质缺口 | 本期不自动清理，列入已知限制 |

## 7. 验收细则总表

| 关口 | 内容 | 命令/方式 |
|---|---|---|
| G0 | 权限获批、P0-1/P0-2/P0-3 结论 | §8 清单确认；tmp 证据文件 |
| G1 | bundle 构建链与安装协议 | 构建脚本产物 + 移位 smoke（真实 `TCC_OUTPUT_DLL` 链接 + `ldd` 记录）+ `TinyCcBundleTest` |
| G2 | 可移植化 + zig 零回退 | `./gradlew clean build --no-daemon --info --console=plain`；`CPortabilitySurfaceTest`；tcc 5 TU 真实链接门 |
| G3 | TinyCcCompiler 单元层 | `script/run-gradle-targeted-tests.sh --tests TinyCcCompilerTest,TinyCcCompilerNativeFakeTest,TinyCcLibraryTest,TinyCcCompilerParallelTest,TinyCcBundleTest` |
| G4 | 接线与 launcher | `--tests CProjectBuilder*,CompileOptions*,GdccCommand*,RpcJsonCodec*,JsonRpcDispatcher*` 相关新增/既有测试；launcher 无 zig 环境 tcc 编译 smoke |
| G5 | Linux 端到端 | `--tests TinyCcCompilerIntegrationTest`；HRX/运行时行为门；test_suite 子集 tcc 冒烟；zig→tcc→zig 交替回归 |
| G6 | Windows 端到端 | Windows 主机按 handoff §8 验证表 + HRX 门复测 |
| G7 | 文档 | §5 阶段 7 清单全落 |

## 8. 权限与确认清单（超出 src/doc/tmp 范围或改变既有合同，需用户逐条批准）

1. `build.gradle.kts`：`tasks.test` 增加 `jvmArgs("--enable-native-access=ALL-UNNAMED")`；`sourceSets.main.resources` 增加 bundle srcDir（或在 `processResources` 接入构建产物目录）；`EditorAddonProjectInstaller` 相关 `JavaExec` 任务的 `jvmArgs` 同参数核对。（**已批准并落地（阶段 3）**：`tasks.test` 与两个 `EditorAddonProjectInstaller` JavaExec 任务均已加入该参数；bundle srcDir 接入属分发打包范畴，随阶段 4/7 另行处理。）
2. `build-script/` 新增 bundle 构建脚本（Linux/Windows 各一）。（用户决议：目录由 `script/` 改为 `build-script/`；`script/` 被 `.gitignore` 整体忽略，而 `build-script/` 可正常纳入版本管理。）
3. ~~`.gitignore`：忽略 bundle 构建产物与本机提取缓存~~（已消解：构建产物默认输出到已被忽略的 `build/`；用户缓存目录在仓库之外；`build-script/` 未被忽略，无需改动 `.gitignore`）。
4. launcher 行为变更确认：`main.zig` 的 zig 发现从硬前置降级为可选（找不到 zig 不再 `exit(1)`，仅不设置 `ZIG_HOME`）；这是既有启动合同的变更，实施前需明确批准。
5. per-project 构建锁从 `ZigCcCompiler` 提取为编译器中立持有器（`ZigCcCompiler` 相应改造）：改动既有 zig 实现的锁持有方式，需确认。（**已批准并落地（阶段 3）**：`CProjectBuildLock` 提取完成，`ZigCcCompiler` 与其锁测试均已迁移。）
6. 移除 `CProjectBuilder.setCCompiler`（当前无生产调用方，测试改用构造器注入）——公共 API 面缩减，需确认。
7. `ci.yml` 改动与 Windows 主机 bundle 构建脚本：Windows job 新增 bundle 构建步骤（`build-script/build-tinycc-bundle-windows-x86_64.bat`，zig cc 产 `libtcc.dll` + 组装/校验 bundle），Linux job 接入既有 `build-tinycc-bundle-linux-x86_64.sh`，使 FFM/bundle 门控测试集在两平台 CI 真实执行。（**已批准并落地（阶段 6）**：用户明确批准"允许修改ci.yml，Windows job 内新增 bundle 构建步骤，执行与Linux类似步骤的测试"。）

## 9. 已知限制与后续方向

- tcc 轮次全局串行，吞吐低于 zig 并行 TU；定位为快速迭代/无 zig 环境的备选后端，zig 仍是默认与发布后端。
- 无 PCH/内容缓存，全量编译耗时随 TU 线性增长；`godot_binding.c` 聚合 TU 拆分（zig 侧后续方向）同样利好 tcc。
- 取消为合作式；强取消/超时需 worker 进程隔离方案（独立立项）。
- tcc 构建依赖宿主开发头/CRT/库（zig 用 bundled sysroot 自给），CI/分发环境的 glibc 版本需纳入验证。
- bundle 缓存无自动清理。
- `LINUX_AARCH64` 实测后开放；macOS 需 mach-o 后端评估，不在路线图。
