# TinyCC 编译后端（TinyCcCompiler）

> 本文档是 tinycc（libtcc FFM 进程内）编译后端当前代码事实、长期合同与维护边界的唯一事实源。
> 修改相关代码时必须同步修订本文。历史实施过程可从 git 历史中的 `tinycc_compiler_implementation_plan.md` 查阅。

## 文档状态

- 状态：Implemented / Maintained
- 范围：`gd.script.gdcc.backend.c.build` 包中 tinycc 侧组件（`TinyCcCompiler`、`TinyCcLibrary`、`TinyCcNative`/`TinyCcFfmNative`、`TinyCcBundle`、`TinyCcObjCache`）、`src/main/c/tinycc` vendored 树、bundle 构建脚本与 CI 接线、编译器选择的 API/CLI/RPC 合同。
- 上游对齐基线：tinycc mob 分支 commit `43c7708b85681a2fd4451c8a541af4494a8919b2`（`src/main/c/tinycc/GDCC_PIN.md`；vendored 树含本地补丁，升级 pin 前必须重验本文全部合同）。
- 关联文档：
  - `doc/module_impl/backend/backend_build_system_implementation.md`：构建管线总事实源（zig 侧合同、项目构建锁、共享 include/缓存根）；tinycc 差异在该文 §3.4 登记。
  - `doc/gdcc_c_backend.md`：Native Compiler Cache 章节与 TinyCC Compatibility Surface（C 源码双编译器适配清单）。
  - `doc/gdcc_runtime_lib.md`：vendored minicoro 补丁登记与 `gdcc_hrx.c` icache 分支。
  - `doc/analysis/tinycc_analysis_windows_handoff.md`：FFM 契约的原始验证记录（背景资料，非合同）。
  - `doc/module_impl/api/rpc_api_implementation.md`、`json_rpc_service_implementation.md`、`cli/cli_implementation.md`：`cCompilerKind` 字段、取消延迟与 `--backend` 选项合同。

## 1. 定位与支持集

- 定位：默认 Debug 的快速迭代后端，也可在无 zig 运行环境中使用；zig 是默认 Release 与发布优化后端。libtcc 经 FFM 在 JVM 进程内调用，无子进程开销；代价是优化能力弱（不宣称与 zig `-O2`/LTO 对等）且无故障隔离（native 崩溃可终止 JVM，属已接受的取舍，worker 进程隔离列后续方向）。
- 支持集 `{LINUX_X86_64, WINDOWS_X86_64, LINUX_AARCH64, MACOS_AARCH64}`，仅 host == target，不支持交叉编译；目标平台不等于本机或不在支持集时快速失败（不触碰 native 库），并指引改用 zig。macOS x86_64、Android、Web、linux-riscv64 不在路线图。
- 编译器选择：`CCompilerKind { ZIG_CC, TINY_CC }`；`CompileOptions.cCompilerKind` 为 `null` 时按优化级别自动选择（debug→tcc、release→zig，`CCompilerKind.defaultFor`），显式值永远优先。RPC 线上字段缺失或显式 `null` 同义于自动，未知枚举名报错。CLI 为 `--backend zig|tcc`（大小写不敏感，非法值在 module 创建前返回 usage error）。
- 非目标：`TCC_OUTPUT_MEMORY` JIT 执行、`tcc_run`、PCH、LTO/ThinLTO、PDB 生成；`CCompiler` 接口签名、`CProjectBuilder` 输入收集顺序、`.gdextension` 由 CLI 写入的合同不变。

## 2. 总体架构与组件

- `TinyCcCompiler`：`CCompiler` 实现。每轮构建：解析 bundle（fail-fast）→ 平台校验 → per-project `CProjectBuildLock` → 进程级 `TCC_GLOBAL_LOCK`（锁序固定为项目锁 → 全局锁，两级均 `lockInterruptibly`）→ 逐 TU 独立 state 产 `TCC_OUTPUT_OBJ` object（经 §5 缓存）→ 新 state 按 `cFiles` 顺序一次链接所有 `.o` 并 `tcc_output_file` 产出物。无 PCH、无 PDB；`optimizationLevel` 不改变编译 flag（唯一固定选项 `-std=c11`），DEBUG/RELEASE 共享缓存条目，仅输出基名不同。
- `TinyCcLibrary`：进程级 FFM 绑定（`Arena.ofShared()` 加载一次、永不卸载；加载失败不缓存并释放 arena）。绑定 10 个符号：`tcc_new`、`tcc_delete`、`tcc_set_lib_path`、`tcc_set_error_func`、`tcc_set_options`、`tcc_set_output_type`、`tcc_add_include_path`、`tcc_add_file`、`tcc_compile_string`、`tcc_output_file`。常量 `TCC_OUTPUT_DLL=4`、`TCC_OUTPUT_OBJ=3`。
- `TinyCcNative`：窄接口（`newState`/`delete`/`setLibPath`/`setOptions`/`setOutputType`/`addIncludePath`/`addFile`/`outputFile`/`setErrorHandler`/`callbackFailure`），是全部单测的替换缝（纯 Java fake）；`TinyCcFfmNative` 为 FFM 实现。`tcc_compile_string` 仅为 API 完整性绑定，不在该接口上。
- `TinyCcBundle`：bundle 解析、校验、安装与缓存（§4）。
- `TinyCcObjCache`：按 TU 内容寻址的 object 缓存（§5）；`CContentHash`/`CCompilerCacheRoot` 与 zig 后端共用。
- `CProjectBuilder`：无参构造经 `EnumMap` 注册 zig 与 tinycc 双实现，按 `CProjectInfo.getCCompilerKind()` 逐轮派发；单参构造为忽略 kind 的强制编译器（既有测试语义）。`availableCCompilerKinds()` 探测（zig 可发现性 / bundle 在场）是只读无副作用的，经 `API.availableCCompilerKinds()` 暴露为 RPC `server.availableCCompilerKinds`。

## 3. FFM 与轮次合同

- 所有 downcall/upcall 发生在持有全局锁的线程上；`Arena.ofConfined` 覆盖单轮全部临时分配，`tcc_delete` 先于 arena 关闭。
- 每 TU/每次链接尝试都新建 state，绝不复用；失败轮次不再对该 state 做任何后续调用。
- 调用顺序硬性约束：`tcc_set_lib_path` 必须先于 `tcc_set_output_type`。Windows bundle 关闭预定义（`--config-predefs=no`）构建，libtcc 运行时从 `{B}/include` 读 `tccdefs.h`，因此 `set_lib_path` 一律传 bundle 根（不是 `bin/`）；Linux bundle 内嵌预定义会掩盖缺失的 lib path，测试不得依赖该掩盖。
- 全部 int 返回值按 `!= 0` 判失败（`tcc_add_file` 可返回 -2/-3）；每个 downcall 后检查回调失败标志。
- 诊断经 error 回调上报：瞬态字符串必须当场拷贝；异常绝不越过 native 帧（回调内异常记录到失败标志）。`tcc_output_file` 会重置 `nb_errors`，其返回值单独不能证明成功——成功判定 = 无回调失败 + 返回 0 + 产物存在。
- 轮次产物的成功结果 `artifacts=[outputPath]`；失败为空。中断轮次不带缓存统计（见 §6）。

## 4. bundle 合同

### 4.1 布局与版本

```text
tinycc-bundle/<platformKey>/            # linux-x86_64 / windows-x86_64 / linux-aarch64 / macos-aarch64
  VERSION                               # properties：bundleFormat（=1）+ tinyccCommit（pin）+ platform
  MANIFEST                              # 全部其他文件的 sha256sum 清单（安装完整性校验用）
  bin/libtcc.so | bin/libtcc.dll | bin/libtcc.dylib   # FFM 加载对象
  include/                              # tcc 自有头（tccdefs.h、stdarg.h、stdbool.h…）
  libtcc1.a 或 lib/libtcc1.a            # 位置按平台路径合同（见下）
  COPYING、RELICENSING                  # 上游 license 随包（tcc LGPL-2.1，分发前需 license 审查）
  # windows 追加：include/ 为上游 include/*.h 与 win32/include/** 的合并树（含 include/winapi/**，
  # _mingw.h 在顶层，禁止另建 include/win32/ 层级）；lib/{msvcrt,kernel32,user32,gdi32,...}.def
```

- 平台路径合同：Windows 库路径为 `{B}/lib`（`lib/libtcc1.a`）；Linux/macOS 库路径为 `{B}` 根（`libtcc1.a` 置于 root）。交叉构建器按目标生成配置：Linux 经 `CONFIG_TRIPLET` 使用目标系统头/CRT；macOS 使用目标机标准 CLT 路径 `/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk/usr`，不烤入构建机路径。
- macOS 的 `bin/libtcc.dylib` 必须使用 twolevel namespace（flat namespace 会把 libtcc 全局符号错绑到进程内其他库的同名符号，首次 FFM 编译即崩溃）。Zig 0.15.2 的 Mach-O 链接器默认设置 `MH_TWOLEVEL`，不接受 `-Wl,-twolevel_namespace`；交叉构建只设置可移植的 `@rpath/libtcc.dylib` install name，ARM64 ad-hoc 签名由 Zig 自动生成，矩阵测试检查 namespace、install name 与 `LC_CODE_SIGNATURE`。旧宿主脚本仍用 `otool -hv`/`codesign -v` 验证。
- `tcc_set_lib_path` 指向 bundle 根；libtcc 本体路径仅用于 FFM `SymbolLookup.libraryLookup`；JAR 内 bundle 资源必须先落盘。

### 4.2 安装协议

- 目标目录：用户缓存目录（Linux/macOS `~/.cache/gdcc/tinycc/<key>/`，Windows `%LOCALAPPDATA%/gdcc/tinycc/<key>/`），key = `<sanitized-gdccVersion>-tinycc-<fullCommit>-<platformKey>`；`GDCC_TINYCC_HOME` 覆盖直接指向 bundle 根（跳过安装，仍全量校验）。
- 按 key 的安装锁为两层互斥：先取 JVM 内 per-key `ReentrantLock`（在打开 channel 之前获取、在 FileLock 与 channel 关闭之后释放——POSIX fcntl 锁在进程关闭同一文件任意 fd 时释放该进程全部锁），再对兄弟锁文件 `<key>.lock` 以非阻塞 `tryLock` + 25ms 可中断退避循环获取跨进程互斥（禁止阻塞式 `channel.lock()`：虚拟线程的中断语义下它会等 native 锁调用返回，等于不可取消）。两处等待均可中断并恢复 interrupt 状态。
- 锁内：建唯一 staging 目录（同父目录 `.staging-<key>-<uuid>`）→ 全量写入 → 按 MANIFEST 逐文件 SHA-256 校验 → 布局/VERSION 校验 → 最后写 `.ready` → rename 发布（优先 `ATOMIC_MOVE`；文件系统不支持时退回普通 `Files.move`，仍不带 `REPLACE_EXISTING`，冲突走胜出者校验——与 §5 object 发布的「原子失败即降级、禁止非原子覆写」是两套不同语义）；rename 冲突时校验胜出目录完整性后采用之，校验失败则替换；`.ready` 缺失或校验失败的目录（含占位文件/符号链接）整体删除重来。
- 完整性 fail-closed：缓存复用、胜出者采用与 `GDCC_TINYCC_HOME` 覆盖路径均按 MANIFEST 重验全部文件哈希；空清单、截断清单、树内未被清单覆盖的普通文件（`MANIFEST`/`.ready` 除外）、穿越/盘符/非法字符路径一律拒绝。
- staging 清理只回收安装锁未被持有且超过保留期（mtime 24h）的目录；已加载 libtcc 的 bundle 目录视为不可变，自愈不替换使用中版本；旧版本目录本期不自动清理（已知限制）。

## 5. 按 TU 内容寻址对象缓存

- 形态：每 TU 独立 state 以 `TCC_OUTPUT_OBJ` 产 object（tcc 的 object 恒为 ELF，含 Windows 目标；`.o` 扩展名各平台一致；无需 `-fPIC`）；命中条目直接进链接。缓存根与 zig 共用解析合同，条目位于 `<cacheRoot>/tcc-obj/<key>.o`。
- 键（SHA-256 截断 16 字节）按序包含：格式版本 `gdcc-tcc-obj-v2`、固定输入（`-std=c11|obj`）、`targetPlatform`、有序 include 目录（规范化绝对路径 + 整树哈希，目录符号链接拒绝）、TU 规范化绝对路径、长度前缀化的 bundle VERSION 内容、TU 内容、TU 同目录顶层 `*.h`（按名排序的名+内容）。**刻意不含** `optimizationLevel`、bundle 根路径与宿主系统头。键必须在锁内计算（避免污染窗口）。新增编译输入必须入键——键组成集中在 `TinyCcObjCache` 单点。
- 发布：未命中编译到同目录唯一临时文件后 `ATOMIC_MOVE + REPLACE_EXISTING`（last-writer-wins，无跨进程文件锁）；`AtomicMoveNotSupportedException` 向上传播使本轮降级（绝不用非原子覆写）。命中仅当最终路径是普通文件；任何查询异常按未命中处理。
- 链接期自愈恰好一次：仅首轮（`attempt == 0`）的缓存命中 object 被链接拒绝时，驱逐该条目 → 重新编译 → 新 state 重链接；本轮新编译的 object 被拒绝不重试。
- 降级哲学：缓存永远不成为构建失败源；任何缓存问题降级为直编。buildLog 固定行：统计 `[gdcc] tcc object cache: <hits> hits, <misses> misses`，降级 `[gdcc] tcc object cache unavailable this round: <reason>`（降级行在前且只在首次降级时出现；仅发布腿降级时两行都出现）。命中 TU 的诊断不再重现（与 zig 内容缓存同性质）。

## 6. 取消、日志与诊断

- 取消为合作式断点：项目锁等待、全局锁等待、每个 TU 前、自愈重编前、最终输出前；恢复 interrupt 状态，固定通道 `Failed to run tcc: interrupted`（失败通道 `Failed to run tcc: <message>`），中断轮次不带缓存统计行。进行中的 native 调用必然先完成，因此 tinycc 轮次的取消收尾慢于 zig（zig 直接销毁子进程）。
- buildLog 无 `Command:` 段（无子进程 argv）；诊断由 error 回调携带 `file:line: error:` 前缀按序合并。
- API/RPC 层：`cancelCompileTask()` 在 `BUILDING_NATIVE` 阶段映射 `CANCELED`；取消延迟预期见 `rpc_api_implementation.md` §3.7 与 `json_rpc_service_implementation.md` §2.6。

## 7. 可移植化适配（双编译器单一源码树）

- vendored tcc 树（`src/main/c/tinycc`）带本地补丁：`tccpp.c` 将 `u8"..."` 视为透明窄字符串前缀（codegen `u8"..."` 合同不变，见 `GDCC_PIN.md`）。
- runtime include 树与生成代码的适配清单（含 `{NULL}`、`pow_int` 的 `uint64_t` 中间量、`gdcc_call.h` 的 `__TINYC__` 分支、`<stdbool.h>` 注入、`= { 0 }` 初始化、`#line` 文件名转义）登记于 `doc/gdcc_c_backend.md` 的 TinyCC Compatibility Surface；minicoro 与 `gdcc_hrx.c` 的补丁登记于 `doc/gdcc_runtime_lib.md`。
- 维护规则：可移植化只允许改生产者（模板、include 树、字面量生成处），禁止生成后文本替换，禁止第二套模板/include 树；`CPortabilitySurfaceTest` 锁定代码 token（`nullptr`/`__int128`）与 inline asm 字符串形态（`jmpq`、无逗号 `.type`、aarch64 块 `#function`），忽略注释与字符串字面量；pthread TLS 谓词、`__attribute((destructor))` 与 `_MCO_ASM_BLOB` 的 `__TINYC__` 分支仅源码登记、未被该测试钉住。

## 8. 构建链与 CI

- 正式构建器为 `src/buildtools/java/gd/script/gdcc/tools/TinyCcBundleBuilder.java`，在独立的
  Java 25 `buildTools` source set 中编译，不依赖主编译器类或主资源。每次用 Zig 交叉构建
  Windows x86_64、Linux x86_64、Linux aarch64、macOS aarch64 四套 `libtcc`，同一 JAR
  包含全部平台，不要求人工 stage 非宿主 bundle。
- 每个目标先由 Zig 构建宿主可执行的 TinyCC 交叉编译器，再由它编译和归档对应
  `libtcc1.a`。运行时对象保持 TinyCC ELF 格式，不能换成 Zig 生成的 COFF/Mach-O 对象；
  Windows 头只合并到 Windows 工作树和 bundle。各目标 ABI 探针用 `-nostdinc` 配合显式
  `-I<work>/include`，断言指针与 `size_t` 均为 8 字节，Windows `long` 为 4 字节，其他为 8。
- macOS 宿主交叉编译器关闭 `CONFIG_CODESIGN`，原生 macOS 库启用该配置并包含
  `<sys/wait.h>`，最终目标程序由目标机 `codesign` 签名。此设置不影响 Zig 自动签名的
  `libtcc.dylib`；`-fno-sanitize=undefined` 保留 PE import 路径的 UBSan 规避。
- 所有平台先写入唯一 staging，完整生成 native 库、运行时库、头文件、VERSION、SHA-256
  MANIFEST 与 COPYING/RELICENSING 后才发布整个矩阵。替换旧目录时保留备份，发布失败恢复
  旧目录；构建失败清理 staging，不覆盖已有完整矩阵。旧 `build-script/build-tinycc-bundle-<platform>`
  宿主脚本继续供独立构建/CI 使用，不再是 Gradle 正式打包路径。
- CLI 工作树脚本（`build-script/build-tinycc-cli-<platform>.sh`）产出带 `cli-verified` 标记的 tcc 二进制，供 CLI 门控测试使用。
- `gradle/tinycc.gradle.kts` 的 `tinyccHostPlatformKey()` 将宿主映射到四平台键并声明产物；
  `buildTinyccCli` 任务挂入 `test` 依赖，完整声明运行时 include 树及 Windows lib 树。
  `buildTinyccBundle` 依赖独立 buildTools 类及 vendored 源码，构建完整四平台矩阵；
  `processResources` 依赖该任务，将 `build/tinycc-bundle/<platformKey>/**` 映射至
  `gdcc/tinycc-bundle/<platformKey>/**`，完整的 native 库、头文件、运行时库、清单与许可随 JAR 打包。
- CI：四个 `Unit Tests` job 各带本平台的 bundle 构建步骤（按矩阵条件跳过其他平台），全量 TinyCC 门控集在四平台真实执行。
- 构建环境合同：正式 bundle 矩阵构建只需 Java 25 与 Zig，不需构建机的目标 SDK。
  TinyCC 运行时仍依赖目标机开发头/CRT/库（Linux 为 glibc 开发环境，macOS 为 CLT/SDK）；
  两后端产物在 Linux 上均动态链接 glibc，部署兼容性以实测动态依赖为准。
  zip 通过主 JAR 携带 bundle；每个 `packageDistribution<Platform>` 在发布前检查该平台
  MANIFEST 是否存在于 JAR，缺失时失败而不发布默认 Debug 后端不可用的分发包。
  库的四平台交叉构建只用于分发载荷，不扩展 GDCC 运行时 TinyCC 的 host == target 边界。

## 9. 测试约定与平台范围

- 真实 bundle/CLI 依赖一律 `Assumptions` 门控（`TinyCcCliTestSupport` 统一发现，只信任带 `cli-verified` 标记的 CLI；以 `GDCC_TINYCC_TCC`/`GDCC_TINYCC_HOME` 覆盖）；无依赖时跳过而非失败，但 CI 上验收清单内测试类必须 0 跳过 0 失败。fake/纯 Java 测试（`TinyCcCompilerNativeFakeTest`、`TinyCcObjCacheTest`、`TinyCcBundleTest`）不依赖 bundle/CLI，但 `TinyCcCompilerNativeFakeTest` 仍假设宿主在支持集内。
- 平台范围登记：`TinyCcWindowsBundleParityTest`（Windows bundle 结构门，宿主无关）、`MinicoroAsmEncodingTest`（linux-x86_64 objdump 门）、`LauncherSmokeTest`（linux-x86_64）为平台限定豁免；`MinicoroTlsUnloadReclaimTest`（≥1100 轮 dlopen/dlclose + 析构见证）为 POSIX-only；其余 TinyCC 测试类的 supported-host 假设覆盖全部四个支持平台。
- 产物形态与导出断言经 `NativeArtifactInspector` 各平台腿执行（ELF `nm -D`、Mach-O `nm -gU`、PE 由纯 Java `PeExportReader` 解析导出表——CI 无 `dumpbin`）；FFM 绑定面 10 符号的导出覆盖由 `TinyCcWindowsBundleParityTest` 在 Windows bundle 上锁定。
- 锚点测试类清单登记于 `backend_build_system_implementation.md` §8。
- `RpcPackagedTinyCcIntegrationTest` 运行真实 `java -jar` 服务器，清除 TinyCC 环境覆盖并使用
  空的隔离用户缓存，验证 RPC 发现 TinyCC、Auto（null）+ Debug 原生构建成功及 bundle 完整
  校验安装。Gradle `test` 依赖 `jar` 并传入实际归档路径，不能依赖旧 JAR 或测试侧 bundle 覆盖。
- `TinyCcPackagedMatrixTest` 在任意宿主校验四套 classpath bundle 的完整安装、目标机器与
  文件格式、Mach-O namespace/install name/签名，以及运行时 archive 内的目标 ELF 对象。
  `gd.script.gdcc.tools.util.UtilTest` 覆盖完整目录发布及失败回滚。

## 10. 已知限制与后续方向

- 轮次全局串行（`TCC_GLOBAL_LOCK`），吞吐低于 zig 并行 TU；对象缓存命中后轮次仅剩链接与键计算耗时。
- 取消为合作式；强取消/超时需 worker 进程隔离（独立立项）。进程内 libtcc 无故障隔离。
- Linux/macOS 的 TinyCC 原生编译仍依赖目标机开发环境；macOS 当前使用标准 CLT SDK 路径，
  非标准 SDK 布局的运行时发现与额外 sysinclude 配置仍属后续方向。
- bundle 用户缓存目录与 `tcc-obj` 缓存均无容量管理。
- Windows 侧 Unicode/执行字符集正确性只在 Linux x86_64 验证过；macOS 编辑器内热重载受 dyld 同路径缓存限制（平台级缺口，非 tcc 特有）；macOS HRX 在官方签名/entitlement 组合下未全覆盖（保持 fail-closed `HRX_UNAVAILABLE` 降级）。
- tcc 忽略部分 `__attribute__`（visibility/pure/const），不影响正确性；generated-file 写入不参与项目构建锁协议（已接受的取舍，同输出目录并发构建两个模块可能污染缓存键，代码注释已登记）。
- minicoro vendored 补丁在上游升级时有丢失风险：补丁点有就地注释、`gdcc_runtime_lib.md` 登记、`CPortabilitySurfaceTest` 锁定其中 asm/token 形态（范围见 §7），升级 pin 必须重验。
