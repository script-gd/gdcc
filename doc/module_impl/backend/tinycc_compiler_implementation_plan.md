# TinyCcCompiler 实施计划（tinycc 内嵌 FFM 编译后端）

## 文档状态

- 状态：实施计划（未开工，已经两轮独立审阅修订）。本文档是 `TinyCcCompiler` 特性的唯一计划文档，完成后按归档约定转化为事实源文档或归档。
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
2. **生成 C 事实为 C23**：zig 管线用 `-std=c23`，含 `nullptr`、裸 `true`/`false`、`u8"..."`、`__int128`；tcc（含 mob dev）全部拒绝（tmp 证据）。mob dev 支持 `_Generic`、`_Static_assert`、`_Noreturn`、`_Thread_local`/`__thread`、语句表达式、`typeof`、空初始化器、大枚举值（静默正确）。
3. **FFM 契约已验证**（Windows）：`tcc_set_lib_path` 必须先于 `tcc_set_output_type`（后者经 `{B}` 展开 include/lib 路径，实现见 `libtcc.c` 的 `tcc_split_path`）；`TCC_OUTPUT_DLL=4`；每轮新建 state；诊断 upcall 须拷贝瞬时字符串且异常不得穿过 native 帧；`tcc_output_file` 会重置错误计数（其实现首行 `s->nb_errors = 0`），不能仅凭其返回值判定成功；失败后不得继续后续操作。
4. **int 返回值语义**：`tcc_add_file` 出错不止返回 -1（`FILE_NOT_FOUND=-2`、`FILE_NOT_RECOGNIZED=-3`），`tcc_set_output_type`/`tcc_set_options`/`tcc_add_include_path`/`tcc_output_file` 同样以非 0 表失败。所有 int API 必须按 `!= 0` 判定。
5. **minicoro asm 适配路径已探明**：`.type name @function` → `.type name, @function`；`jmpq *%r12`/`*({rsi})` → `jmp ...`（gcc/tcc 均接受逗号形式与 `jmp`）。
6. **minicoro TLS 宏链路**：mob dev 在 `-std=c11` 下经 `tccdefs.h` 定义 `__STDC_NO_THREADS__`，且 tcc 不定义 `__GNUC__`，现有分支会落到 `MCO_THREAD_LOCAL` 为空 + `MCO_NO_MULTITHREAD`（协程退化为非线程安全，是**行为变化**而非编译错误）。适配必须强制走 `__thread` 分支并禁止该落回。
7. **运行时 TU 依赖 libc 头文件与启动文件**：`godot_interface.c`（`stdio.h`/`stdarg.h`）、`gdcc_hrx.c`（`string.h`/`sys/mman.h` 或 `windows.h`）、`minicoro.c`（vmem 分配器）。Linux 上 `TCC_OUTPUT_DLL` 链接路径会链接宿主 glibc（`tcc_add_library(s,"c")`）并查找 `crti.o`/`crtn.o` 等启动文件；Windows 上必须随 bundle 分发完整 `win32/include`（含 CRT 头，`windows.h` 依赖 `_mingw.h`，不能只取 `winapi/` 子目录）与 `win32/lib/*.def`。
8. **产物 libc 合同差异**：zig 用其 bundled sysroot 编译链接（不依赖宿主开发头/CRT），tcc 依赖宿主开发头、CRT 与库；两者产物在 Linux 上均为动态链接 glibc 的共享库，部署兼容性以实际动态依赖与符号需求验证为准，不存在"zig 产物静态链 libc"的前提。
9. **FFM 启动参数**：launcher 已带 `--enable-native-access=ALL-UNNAMED`（`src/launcher/zig/src/main.zig:94`）；`java -jar` 为 unnamed 部署，该参数匹配。named-module 启动（IDE 模块路径）需 `--enable-native-access=gdcc`，须在阶段 0 用真实加载核对各启动方式。
10. **逃逸生产者**：`StringUtil.escapeStringLiteral` 对非 ASCII 输出 `\uXXXX`/`\UXXXXXXXX`。tcc 对 plain 字面量中 UCN 的执行编码行为**未验证**，是 Unicode 正确性缺口的根源（handoff §5.1）。
11. **金字面量测试面小**：`src/test` 中 `u8"` 断言 11 处（3 个测试类）、`nullptr` 1 处，改造可控。

## 3. 总体架构

### 3.1 组件与职责（新增/改动）

| 组件 | 位置 | 职责 |
|---|---|---|
| `TinyCcCompiler` | `backend/c/build/`（新增） | `CCompiler` 实现：一轮 = 一个 `TCCState`，逐 TU `tcc_add_file` 后一次 `tcc_output_file`；全局锁串行；合作式取消 |
| `TinyCcLibrary` | `backend/c/build/`（新增，package-private） | FFM 绑定层：惰性一次性加载 libtcc、10 个 downcall handle、诊断 upcall stub 工厂；进程生命周期内不卸载 |
| `TinyCcNative` | `backend/c/build/`（新增，package-private） | 可替换的 native 调用窄接口（state 创建/删除、options、output type、include、add_file、output_file、错误回调注册），生产实现委托 `TinyCcLibrary`；测试可用纯 Java fake 驱动失败路径 |
| `TinyCcBundle` | `backend/c/build/`（新增，package-private） | bundle 定位：`GDCC_TINYCC_HOME` 环境变量优先；否则从 classpath 按版本键安装到用户缓存目录；校验布局与 `VERSION` 标记 |
| `CCompilerKind` | `backend/c/build/`（新增） | 枚举 `ZIG_CC` / `TINY_CC` |
| `CompileOptions` | `api/`（改动） | 新增 `cCompilerKind` 组件，默认 `ZIG_CC`；保留六参兼容构造器（委托新构造器，先例：`CBuildResult` 辅助构造器） |
| `CProjectInfo` | `backend/c/build/`（改动） | 新增 `cCompilerKind` 访问器，随 `CompileOptions` 透传 |
| `CProjectBuilder` | `backend/c/build/`（改动） | 无参构造：持有 `EnumMap<CCompilerKind, CCompiler>` 双实现，按 `projectInfo.cCompilerKind()` 逐轮选择；单参构造：安装强制编译器并**忽略 kind**（现有测试语义）；移除运行期 `setCCompiler`（共享可变槽有数据竞争且当前无生产调用方） |
| `GdccCommand` | `cli/`（改动） | 新增 `--compiler zig|tcc` 选项 |
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
- RPC/wire 兼容政策：`CompileOptions` 经 Gson 按 record 组件名反序列化（`options.set` 整体替换快照）。新增 `cCompilerKind` 缺失时默认为 `ZIG_CC`（紧凑构造器把 null 归一为默认）；显式未知枚举值按既有枚举解析规则报错。需同步更新 `doc/module_impl/api/rpc_api_implementation.md` 与 `json_rpc_service_implementation.md` 的字段表。

### 3.4 bundle 布局与分发

```text
tinycc-bundle/<platformKey>/            # platformKey: linux-x86_64 / windows-x86_64
  VERSION                               # mob commit + gdcc bundle 格式版本
  bin/libtcc.so | bin/libtcc.dll        # FFM 加载对象
  include/                              # tcc 自有头（tccdefs.h、stdarg.h、stdbool.h…）
  libtcc1.a 或 lib/libtcc1.a            # 位置按平台路径合同（见下）
  COPYING、RELICENSING                  # 上游 license 随包
  # windows 追加（对齐上游 install-win：win32/include 递归合并进 include/，winapi 落在 include/winapi/）：
  include/                              # 合并 include/*.h、tcclib.h 与 win32/include/**（含 _mingw.h 等 CRT 头）
    winapi/**                           # Windows 兼容头
  lib/libtcc1.a                         # 由交叉目标产出的 x86_64-win32-libtcc1.a 改名安装
  lib/{msvcrt,kernel32,user32,gdi32,ws2_32,...}.def
```

平台路径合同（以 pinned 源码 `tcc.h:255-300`/`Makefile install-win` 实证为准）：

- Windows：PE 默认系统头路径 `{B}/include` + `{B}/include/winapi`，库路径 `{B}/lib`；`tcc_set_lib_path(root)` 后 `lib/libtcc1.a` 命中。bundle 的 `include/` 必须是上游 `include/*.h` 与 `win32/include/**` 的**合并树**（`windows.h` 依赖同层 `_mingw.h`），禁止另建 `include/win32/` 层级。
- Linux：默认系统头路径 `{B}/include` + triplet 化的系统 include；库路径为 `{B}` + triplet 化系统库目录（**不含 `{B}/lib`**）；multiarch include/CRT 路径依赖 `configure` 生成的 `CONFIG_TRIPLET`。因此 Linux bundle 要么将 `libtcc1.a` 直接置于 root，要么在构建 libtcc 时以受控 `config.h` 明确 `{B}/lib`；系统头/CRT 路径由 `configure` 产物决定，禁止靠手写 `-D` 跳过该块。实现时以 G1 的移位 smoke 验收锁定（见 §5 阶段 1）。
- `tcc_set_lib_path` 指向 `<root>`；libtcc 本体路径仅用于 FFM `SymbolLookup.libraryLookup`。
- JAR 内资源必须先落盘（handoff 已确认 loader 不接受 JAR URL）。

安装协议（独立定义，不引用 `ResourceExtractor` 语义——后者只做单文件内容比较，无目录锁与自愈）：

- 目标目录：用户缓存目录（Linux `~/.cache/gdcc/tinycc/<key>/`，Windows `%LOCALAPPDATA%/gdcc/tinycc/<key>/`），key = gdcc 版本 + mob commit + platformKey。
- 安装：按 key 的安装锁文件（`FileLock`，跨进程互斥）→ 锁内建唯一 staging 临时目录（同父目录、随机名）→ 全量写入 → 清单与哈希校验 → 最后写 `.ready` → 原子 rename 发布；rename 冲突（并发实例已发布）时校验胜出目录完整性后使用之；`.ready` 缺失或校验失败的目录整体删除重来。
- staging 清理：只清理安装锁未被持有且满足保留期（如 mtime 超过 24h）的 staging 目录；持锁安装中的目录不得被其他实例清理（含"安装者暂停 vs 崩溃"用例：B 不得清理 A 持锁中的 staging，A 崩溃且锁释放后方可回收）。
- 已加载 libtcc 的 bundle 目录视为不可变版本：进程内一经加载即固定，自愈不替换正在使用的版本目录。
- 旧版本清理政策：本期不自动清理，记录为已知限制。

## 4. 关键设计决策

- **D1 接受进程内故障边界消失**（MVP）：tcc native 内存错误可终止 JVM；现有 zig 子进程取消/超时模型不可复用。本期接受该取舍，worker 进程隔离列为后续方向。需在用户文档明示。
- **D2 可移植化改到生产者**：模板、include 头、Java 字面量生产者三处源头改造；禁止生成后 search/replace；禁止为 tcc 派生第二套模板/include 树（单一源、两编译器共食）。
- **D3 每轮新 state + 全局锁**：`TCCState` 不跨轮复用（失败态不可信）；静态 `ReentrantLock` 串行全部 tcc 轮次（tcc 进程全局状态 + 内部编译锁不构成并发安全承诺）。
- **D4 取消语义降级为合作式**：仅在 TU 边界与锁等待处响应中断；活跃 downcall 不可中断；中断后恢复 interrupt 状态。buildLog 固定通道 `Failed to run tcc: interrupted`，与 zig 通道形态对齐。API 层 `cancelCompileTask()` 的 `BUILDING_NATIVE → CANCELED` 映射仍成立（native 返回后 runner 再查取消标记），但完成延迟变长，`rpc_api_implementation.md` 的"runner 迅速退出"预期需同步修订。
- **D5 不支持交叉编译**：libtcc 的目标平台即其构建目标；`targetPlatform != host` 快速失败并提示改用 zig。支持集首期为 `{LINUX_X86_64, WINDOWS_X86_64}`；`LINUX_AARCH64` 待实测后开放。
- **D6 `CompileOptions` 增加 `cCompilerKind` 组件**：record 构造点全部更新（生产 2 处：`CompileOptions.defaults`、`GdccCommand.compileOptions`；测试 11 处），默认值 `ZIG_CC` 保证既有行为不变；保留六参兼容构造器。
- **D7 DEBUG/RELEASE 同构**：tcc 无有效 `-O2`/LTO；两档仅影响输出基名（`debug|release`），编译参数一致，文档明示不承诺优化对等。
- **D8 构建时 libc 来源差异显式化**：zig 用 bundled sysroot、不依赖宿主开发环境；tcc 依赖宿主开发头/CRT/库。两者产物均为动态 glibc 共享库，部署兼容性以实测动态依赖为准，不作静态链接承诺。

## 5. 分步骤实施

阶段依赖：`G0 → G1；G0/G1 → G2；G1 → G3；G2/G3 → G4 → G5/G6 → G7`。内部基础设施可先行合入，但未通过对应平台端到端门前不得声明该平台受支持；关键测试全部 skipped 不算验收通过。

### 阶段 0：权限确认与前置探针

改动：
- 向用户申请 §8 权限清单（构建脚本、script/ 新文件、.gitignore、launcher 行为变更）。
- 探针 P0-1（tmp 下，用 `/tmp/opencode/tinycc` 的 mob tcc）：plain `"..."` 中 `\uXXXX`/`\UXXXXXXXX` 的 tcc 执行编码；原始 UTF-8 字节直通在 zig/tcc 两侧的等价性。结论写入 tmp 证据文件，决定 D2 中字面量方案（首选：非 ASCII/控制字符按字节三位八进制 `\ooo` 转义，ASCII 可打印字符保持原样；该方案不依赖任何执行字符集映射）。
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

验收：
- 脚本在本机（Linux x86_64）产出 bundle；`nm -D libtcc.so` 可见 `tcc_new` 等导出。
- **移位 smoke（G1 核心）**：将 bundle 移至全新目录，仅以 `tcc_set_lib_path` 指向它，通过 FFM（或先经 CLI 等价物）以 `TCC_OUTPUT_DLL` 编译并链接一个含 `stdio.h`、`sys/mman.h` 调用的最小共享库；`ldd` 记录产物 libc soname（分发合同证据）；禁止用 `-c`/OBJ 类型冒充该验收。
- `TinyCcBundleTest`（纯 Java，fixture 目录）：布局校验、版本键、双实例并发安装（staging 竞争与胜出目录复用）、`.ready` 缺失/损坏目录自愈、崩溃残留 staging 清理。

### 阶段 2：共享 C 层可移植化（zig 不回退为前提）

改动（按生产者分组）：
1. 模板 `template_451/`：移除全部 `u8` 前缀（`entry.c.ftl` 27 次/22 行、`entry.h.ftl`、`engine_method_binds.h.ftl`，按"全部"执行而非按计数打勾）；`entry.c.ftl:110/135` 空初始化器实测后决定保留或改 `{ 0 }`。
2. Java 生产者：`CBodyBuilder.java:1848`、`ConstructInsnGen.java:322-324`、`CGenHelper`、`CBuiltinBuilder` 的字面量前缀移除；`StringUtil.escapeStringLiteral` 按 P0-1 结论改造（新增 C 字面量专用路径时按 `common_rules.md` 命名约定命名）。
3. include 树：`gdcc_string.h:23`、`gdcc_string_name.h:30`、`gdcc_callable.h:29` 的 `{nullptr}` → `{NULL}`；`gdcc_bind.h:90,138` 的 3 个 `u8""` 前缀移除；`gdcc_operator.h:39-40` `__int128` → `uint64_t`（保留平方求幂与负指数分支，禁止换用有符号 `int64_t` 中间量）。
4. `<stdbool.h>` 集中引入：**改生成器**（`godot_macros.h` 是 GodotBinding 工具链的生成物，头注禁止手改——在生成器模板中注入 include），并验证所有裸 `true`/`false` 使用点的包含链。
5. minicoro（vendored 第三方，补丁须就地注释说明原因，升级需重放）：asm 串 `.type name, @function`、`jmp` 化；**恢复地址硬编码位移必须标签化**：`leaq 0x3d(%rip), %rax` → `leaq .Lmco_switch_resume(%rip), %rax` 并在 `jmp *(%rsi)` 后补 `.Lmco_switch_resume:` 标签——tcc 内联汇编器把 RIP 相对的硬编码常量位移误编码为原值减 4（实测：`0x3d`→`0x39`，gcc 正确；误编码的保存地址落在 `mov 0x8(%rsi),%rsp` 指令中间，当前纯属字节对齐侥幸解码为无害指令序列才测试通过，任何指令长度变化都会变成内存破坏或死循环）；TLS 宏链在 `__TINYC__` 下强制 `__thread` 且**禁止落到 `MCO_NO_MULTITHREAD`**（行为变化，非编译错误）；`_MCO_ASM_BLOB` 分支识别 `__TINYC__`（Windows blob 入 `.text`）。证据：`tmp/minicoro-patch-verify/EVIDENCE.md`。
6. `gdcc_call.h:84` GNU 分支条件追加 `|| defined(__TINYC__)`；`gdcc_hrx.c:476` aarch64 分支在 `__TINYC__` 下改用 `__arm64_clear_cache`。

注释纪律：上述改动点的注释只解释 C 语义与编译器兼容性事实（如 "tcc accepts statement expressions but does not define `__GNUC__`"），**不得引用本计划文档或任务编号**（AGENTS.md 约定）。

验收：
- zig 全回归：`./gradlew clean build --no-daemon --info --console=plain` 全绿；重点定向：`script/run-gradle-targeted-tests.sh --tests CBodyBuilderLiteralValueTest,GdccStaticStringRuntimeSmokeTest,GodotAbiHeaderCompileTest`（金字面量断言同步更新）。
- 新增 `CPortabilitySurfaceTest`（纯 Java）：扫描 `include_451`（含 `gdcc_bind.h`）/`template_451`/生成的 `entry.c`，**忽略注释与字符串内容上下文**：`u8"`/`nullptr`/`__int128` 按代码 token 断言不存在（`gdcc_string.h:58`、`gdcc_string_name.h:65` 注释中的示例与 `decode_u8`/`encode_u8` 标识符不算命中）；`jmpq` 与 `.type name @function`（无逗号）只在内联汇编字符串字面量中断言不存在（`minicoro.h:683,746` 的机器码数组注释不算命中）。
- tcc CLI 门（本机 mob tcc）：对从 classpath 提取的正式 include 树 + 一份生成的 `entry.c` 全量 5 TU 以 `TCC_OUTPUT_DLL` 真实链接通过（含 crt/libc 链路），0 error；禁止用 `-c` 代替。
- minicoro 反汇编断言（双编译器产物）：`_mco_switch` 中 `lea` 的目标地址 == `jmp *(%rsi)` 之后的第一条指令（`ret`），即标签化恢复地址编码正确（tcc 与 zig 两侧都验；防 R5 回归）。
- `pow_int` 语义：新增/更新针对 `pow` 边界的测试（`2^63`、`2^64`、`INT64_MIN/MAX`、负指数），zig 产物行为不变（等价性论证限定为二进制补码乘法低 64 位，最终截断到 `godot_int` 不变）。

### 阶段 3：FFM 绑定层与 TinyCcCompiler

改动：
- `TinyCcLibrary`：`SymbolLookup.libraryLookup(libPath, 进程级共享 arena)` + `Linker.nativeLinker()`；10 个 downcall（签名按 handoff §7.1 表）；惰性初始化：**失败不缓存，仅在完整绑定成功后发布进程级共享实例**（对齐 `ZigUtil` 语义；半途失败关闭临时 arena）。
- `TinyCcNative`：窄接口封装全部有状态 native 调用，测试可注入纯 Java fake。
- `TinyCcCompiler`：实现 §3.2 流程与 FFM 不变量；支持集校验；全局锁；中断点（锁等待、TU 边界、output 前）；旧产物删除；`finally` 释放顺序。
- 测试缝：注入 `TinyCcNative` fake 与 bundle 根解析器，纯 Java 驱动失败路径。

验收（真 bundle 用 `Assumptions` 门控，模式对齐 zig 测试约定；fake 驱动的不门控）：
- `TinyCcCompilerTest`：多 TU 成功编译（**手写 fixture C 输入**，不依赖阶段 2 的生成代码移植，含一个导出 `gdextension_entry` 的 fixture）、产物存在且导出该符号（Linux 用 `nm -D`，Windows 用 PE 适用工具如 `dumpbin`/zig 自带工具，不机械照搬）、语法错误输入的诊断文本透传与失败通道、失败后新一轮成功（fresh state）、中断通道（锁等待取消、TU 间取消、最终 TU 后取消且恢复 interrupt）、空 `cFiles` 失败、旧产物存在但 output 失败不误报成功。
- `TinyCcCompilerNativeFakeTest`（纯 Java）：setup 各步（options/output_type/include/add_file/output）任一失败即中止且不继续后续调用、**native 全部返回 0 且产物存在但回调失败时令整轮失败且 artifacts 为空**、清理顺序（delete → arena close）。
- `TinyCcLibraryTest`：符号绑定齐全；upcall 拷贝语义（长消息、多次触发）；初始化失败可重试、成功后只加载一次。
- 并发：`TinyCcCompilerParallelTest` 证明全局锁串行且结果正确（两项目并发各编译成功）；类级保持 `same_thread`，并发由测试内部创建（对齐 §8 测试约定）。

### 阶段 4：编译器选择接线与 launcher 行为变更

改动：`CCompilerKind`、`CompileOptions`（含兼容构造器与 null 归一）、`CProjectInfo`、`CProjectBuilder`（EnumMap/强制编译器语义、移除 `setCCompiler`）、`GdccCommand --compiler`、`CompileTaskRunner` 透传、RPC wire 政策（§3.3）与 `EditorAddonProjectInstaller` 构造点兼容（其多平台任务继续固定 zig）；launcher zig 发现降级为可选（§8 确认项）。

验收：
- `./gradlew classes` 编译通过；既有 API/CLI 测试全绿（默认值 `ZIG_CC` 行为不变）。
- 新增：`CompileOptions` 校验/默认值/兼容构造器测试；`CProjectBuilder` 双编译器选择测试（fake `CCompiler` 双注入断言按 kind 分发；单参构造忽略 kind）；CLI `--compiler` 解析与非法值报错测试；RPC 测试（`RpcJsonCodec`/`JsonRpcDispatcher` 层面）：字段缺失默认 zig、**显式 null 同缺失**、两种编译器往返、未知值报错、冻结后选项变更。
- launcher：全机 zig 发现位置均不可用时，`--compiler tcc` 经正式 launcher 完成编译；`--compiler zig` 时报 zig 缺失的既有错误通道。

### 阶段 5：Linux x86_64 端到端验收

- `TinyCcCompilerIntegrationTest`（`Assumptions` 门控，需要 bundle + 可选 Godot）：最小 fixture 模块全管线编译 → `.gdextension` 生成 → 产物加载冒烟（复用既有 Godot 校验夹具的目录约定，禁用共享目录）。
- HRX/运行时行为门（tcc 产物，Godot 可用时必跑，记录非零跳过声明）：execmem probe、四类 thunk 执行、保留 Callable 后卸载/重载、rebind 与失配失效；动态调用（`GD_OBJECT_CALL*`）运行时行为；生成协程四线程 TLS 隔离与取消；与 zig 产物行为一致。
- 用 `--compiler tcc` 跑 test_suite 的一个子集（协程、字符串、pow、属性绑定用例），Godot 编辑器内行为与 zig 产物一致。
- 泄漏/稳定长跑：成功/失败/取消交替轮次的内存与句柄观察。
- 同一 includeRoot 上 zig→tcc→zig 交替构建回归（共享 include 树内容比较跳过不影响 tcc 轮次）。
- 双后端产物竞争：zig 与 tcc 对**同一 `projectDir`、同名输出**并发构建，验证 per-project 锁互斥（固定顺序 project lock → tcc global lock），无半成品/交错删除。
- 性能参考值记录（DEBUG 增量/全量），写入 PR 描述，不进 CI 断言。

### 阶段 6：Windows 支持

- bundle 脚本产出 windows-x86_64 包（阶段 1 交叉或主机路径）。
- 在 Windows 主机验证：FFM 五 TU 编译、诊断回调、fresh-state 重复、Godot 注册/属性/引擎调用、HRX 门（同阶段 5 清单）、shutdown（对齐 handoff §8 验证表）。
- `CreateFiberEx` 缺口不涉及（fiber 后端禁用，`gdcc_runtime_lib.md` 合同不变）。
- G6 未通过前，Windows 不得在支持声明中标记完成。

### 阶段 7：文档收口

- `backend_build_system_implementation.md`：新增 TinyCcCompiler 职责段、日志/取消差异合同、§8 测试锚点清单与并行约定更新。
- `doc/gdcc_c_backend.md`：编译器后端章节补 tcc 路径；字面量合同（`u8` 要求）随阶段 2 落地同步修订。
- `doc/gdcc_runtime_lib.md`：minicoro tcc 适配补丁登记（`__TINYC__` 分支清单）。
- `doc/module_impl/api/rpc_api_implementation.md`、`json_rpc_service_implementation.md`：`cCompilerKind` 字段表与取消延迟预期修订。
- 分发说明：两后端的 libc/构建环境合同（D8）与 launcher 行为变更。
- 本计划文档按归档约定转化或归档；`tmp` 探针证据不入库。
- 事实源文档只在对应实现与验收完成后更新（AGENTS.md 约定）。

## 6. 风险登记册

| # | 等级 | 风险 | 成因链路 | 缓解 |
|---|---|---|---|---|
| R1 | 高 | FFM 无故障隔离，native 崩溃终止 JVM | D1 取舍，tcc 内部状态机复杂 | 文档明示；worker 进程隔离列后续方向；失败状态即弃（D3） |
| R2 | 高 | Unicode/转义正确性未证明 | `escapeStringLiteral` 的 `\u`/`\U` 依赖执行字符集映射，tcc 行为未验证 | P0-1 探针前置；首选八进制逐字节方案规避映射依赖；test_suite 增加非 ASCII 字符串用例双编译器对比 |
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

1. `build.gradle.kts`：`tasks.test` 增加 `jvmArgs("--enable-native-access=ALL-UNNAMED")`；`sourceSets.main.resources` 增加 bundle srcDir（或在 `processResources` 接入构建产物目录）；`EditorAddonProjectInstaller` 相关 `JavaExec` 任务的 `jvmArgs` 同参数核对。
2. `script/` 新增 bundle 构建脚本（Linux/Windows 各一）。
3. `.gitignore`：忽略 bundle 构建产物与本机提取缓存。
4. launcher 行为变更确认：`main.zig` 的 zig 发现从硬前置降级为可选（找不到 zig 不再 `exit(1)`，仅不设置 `ZIG_HOME`）；这是既有启动合同的变更，实施前需明确批准。
5. per-project 构建锁从 `ZigCcCompiler` 提取为编译器中立持有器（`ZigCcCompiler` 相应改造）：改动既有 zig 实现的锁持有方式，需确认。
6. 移除 `CProjectBuilder.setCCompiler`（当前无生产调用方，测试改用构造器注入）——公共 API 面缩减，需确认。

## 9. 已知限制与后续方向

- tcc 轮次全局串行，吞吐低于 zig 并行 TU；定位为快速迭代/无 zig 环境的备选后端，zig 仍是默认与发布后端。
- 无 PCH/内容缓存，全量编译耗时随 TU 线性增长；`godot_binding.c` 聚合 TU 拆分（zig 侧后续方向）同样利好 tcc。
- 取消为合作式；强取消/超时需 worker 进程隔离方案（独立立项）。
- tcc 构建依赖宿主开发头/CRT/库（zig 用 bundled sysroot 自给），CI/分发环境的 glibc 版本需纳入验证。
- bundle 缓存无自动清理。
- `LINUX_AARCH64` 实测后开放；macOS 需 mach-o 后端评估，不在路线图。
