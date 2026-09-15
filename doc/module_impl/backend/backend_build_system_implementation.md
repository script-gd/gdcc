# 后端构建系统

## 文档状态

本文档是 native 构建系统（`gd.script.gdcc.backend.c.build` 包）当前实现的事实源，面向后续工程：只描述当前状态、架构合同与约定，不保留实施过程记录。本文档取代已归档的 `native_build_acceleration_plan.md`（历史内容可从 git 历史查阅）。

相关文档：

- `doc/gdcc_c_backend.md`：C 后端总览，是 Native Compiler Cache 与 PCH Cache 的合同源头（缓存目录解析、PCH key 组成与自愈协议）。本文档从构建管线侧引用其结论，不重复定义。
- `doc/gdcc_runtime_lib.md`：runtime C 源码（`godot_binding.c`、`minicoro.c`、`gdcc_coroutine.c`）的内容与宏配置合同。

## 1. 范围与职责边界

- `CProjectBuilder`：项目级编排——include 树提取、generated files 写入、native 输入收集、调用 `CCompiler`、记录 `CBuildResult.Timing`。
- `ZigCcCompiler`：`CCompiler` 的 zig 实现——逐 TU（translation unit）编译为 object + 一次链接的两阶段构建，含 zig 缓存、PCH、并行、取消与日志合并。
- `ZigUtil`：zig 工具链发现（`findZig`）与版本探测（`findZigVersion`）。
- 不变边界：`CCompiler` 接口签名、`CProjectBuilder` 输入收集顺序、generated-file 集合与发布合同、runtime 源码及其宏配置（如 `MCO_USE_ASM` 锁定）、API module 级串行合同均不因构建系统内部演进而改变。

## 2. 总体架构

`ZigCcCompiler.compile(...)` 的一轮构建（round）按以下顺序执行：

1. zig 发现（找不到则失败返回，日志 `Zig executable not found on PATH or known locations`）；空 `cFiles` 直接失败。
2. 获取 per-project 构建锁（见 §5.4），锁等待可中断。
3. 解析输出路径、cache root、zig target、LTO 模式。
4. PCH 准备（probe/构建/自愈，见 §4）。
5. 每个 `.c` 一个 TU slot，并行编译为 object（见 §5）。
6. 全部 TU 成功后按 `cFiles` 顺序一次链接；链接成功且输出文件存在才返回成功。
7. TU 失败：不启动链接，按 §6 合同合并日志返回失败；链接失败：按 §6 合并（含链接段）。中断或进程无法启动走固定 `Failed to run zig: ...` 通道（见 §5.3），不合并 Command 段。

native 输入顺序固定（`CProjectBuilder.buildProject()` 内联收集）：

1. 本轮生成的 `.c`（当前为 `entry.c`）；
2. `<includeRoot>/godot/godot_binding.c`；
3. `<includeRoot>/gdcc/minicoro.c`；
4. `<includeRoot>/gdcc/gdcc_coroutine.c`。

include 目录为 `<includeRoot>/gdcc` 与 `<includeRoot>/godot`。include root 解析：项目父目录存在 `shared-include/` 则用之，否则用项目内 `include/`；`CProjectBuilder.setIgnoreSharedInclude(true)` 强制项目本地（测试隔离缝）。runtime 资源从 classpath `include_451` 的 `gdcc/**`、`godot/**` 提取，`initProject()` 与每次 `buildProject()` 都执行；提取按内容比较，内容一致时不替换文件（保持 mtime 稳定，见 §4.4），内容变化时临时文件 + atomic replace。

## 3. 编译与链接命令合同

### 3.1 命令形态

per-TU 编译（每个 `.c` 一条）：

```text
zig cc -target <zigTarget> -std=c23 -fPIC [-flto=thin|-flto] <-O0|-O2>
       -Wno-macro-redefined -Wno-pointer-sign -c -I<includeDir>...
       [-include-pch <pchPath>] -o <objPath> <cFile>
```

链接（一轮一次）：

```text
zig cc -target <zigTarget> -shared [-flto=thin|-flto -O2]
       -o <outputPath> <objPath...>
```

- `-I`/`-std`/`-fPIC`/`-c` 只出现在编译类命令；`-shared` 只出现在链接命令；两者共享同一 `-target`、同一 `ZIG_CACHE_DIR`/`ZIG_GLOBAL_CACHE_DIR`（同一 cache root）与同一工作目录（`CProcessLauncher` 对每个子进程设置 `directory(projectDir)`）。
- `languageFlags(ltoMode, opt)` 是 TU 编译、PCH 构建、PCH probe、PCH key 的统一 flag 来源；PCH 构建命令使用同一 `languageFlags` 但不带 `-c`，形态为 `zig cc -target <zigTarget> <languageFlags> -I<includeDir>... -x c-header <prefix.h> -o <pchPath>`。
- object 路径固定为 `<projectDir>/obj/<debug|release>/<zigTarget>/<index>_<fileName>.o`，`<index>` 为 `cFiles` 下标，用于同名 `.c` 消歧。
- 链接输入只使用本轮按 `cFiles` 顺序生成的 object 绝对路径列表：禁止 glob `obj/`、禁止以"object 已存在"跳过编译；每个 TU 每轮都执行 `zig cc -c`，缓存复用完全交给 zig 内容缓存。stale object 永远不进入链接。

### 3.2 LTO 决策（优先级从高到低）

1. ABI 替换（非 Windows 宿主上 `*-windows-msvc` 被替换为 `*-windows-gnu`）：编译与链接均不追加任何 `-flto*`；
2. DEBUG：无 LTO（`-O0`）；
3. target 命中 `THIN_LTO_UNSUPPORTED_ZIG_TARGETS`：RELEASE 回退 `-flto`（full LTO）；当前该集合为空；
4. 其余 RELEASE：`-flto=thin`（编译与链接同带，链接随 LTO 带 `-O2`）。

决策由 package-private 静态方法 `resolveLtoMode(...)` 给出，ThinLTO 不兼容 target 必须由命令级测试（`ZigCcCompilerCommandTest`）锁定，不得通过链接失败反推。

### 3.3 target 已知限制

- `windows-gnu` 的 LTO 链接失败（`frexpf`/`frexpl`/`modfl` undefined），但该 target 只能经 msvc→gnu 替换路径到达、该路径本就禁 LTO，故不进入回退判定集。
- android/web-wasm32 的失败是 sysroot/runtime 限制（android 需 NDK/Bionic；wasm 上 minicoro 锁定 `MCO_USE_ASM` 按设计 fail loudly），与 LTO 无关：二者的 LTO 决策保持默认（RELEASE 取 `-flto=thin`），真实构建显式跳过或仅做 `-c` 编译。web 的 Emscripten 后端是独立 post-MVP 项目，不属于 zig 后端职责。
- zig cc 拒绝透传 `-Wl,--thinlto-cache-dir`/`-fthinlto-cache-dir`，因此 ThinLTO 链接后端无法跨构建缓存；不直接驱动 `zig ld.lld`（见 §9 后续方向）。

## 4. PCH 使用（构建管线侧）

PCH 的完整合同（key 组成、布局、自愈协议）以 `doc/gdcc_c_backend.md` 的 PCH Cache 章节为准；本节只列构建管线侧的实现要点：

- 布局：`<cacheRoot>/pch/<key>/{gdcc_godot_prefix.h, gdcc_godot_prefix.pch, .ready}` 三者齐全才可消费；key 为 SHA-256 截断前 16 字节（32 个 hex 字符），输入含 zig 版本、resolved zig target、完整语言 flags（含 `-O` 与实际 LTO token）、保序 include 目录列表及每个目录的归一化绝对路径与目录树内容哈希（length-prefixed 拼接防歧义）。`GodotVersion`/`REAL_T_IS_DOUBLE` 不进 key，由头文件内容哈希覆盖。
- 前缀头内容固定为 `#include <godot_binding.h>\n`；`-include-pch` 白名单仅 `entry.c`、`godot_binding.c`、`gdcc_coroutine.c`，`minicoro.c` 明确排除（它不包含 `godot_binding.h`）。
- 前缀头安装到最终路径后 mtime 归一为 `Instant.EPOCH`，避免 zig 缓存回放旧 PCH 时触发 clang 的 mtime 校验。
- 消费前 probe：在并行 TU 启动前以完整 TU flags、同一 registry/工作目录/`ZIG_*_CACHE_DIR` 执行；probe 源内容与文件名带轮次唯一后缀，防止 zig 内容缓存回放陈旧 probe 结果。
- 自愈：已安装 PCH probe/校验失败时删除该 key 条目并最多重建一次；构建、安装、probe、版本探测或 include 树哈希失败一律只回退本轮无 PCH，不使构建失败。
- 回退时日志首行固定为 `[gdcc] PCH unavailable this round: <reason>`。
- 任一 TU 诊断命中 PCH 拒绝标记（`precompiled file`/`precompiled header`/`-include-pch`/`pch file`/`ast file`）时，整轮去掉 `-include-pch` 重编，日志首行固定为 `[gdcc] zig rejected the PCH during TU compilation; the whole round was retried without -include-pch`；禁止 PCH/no-PCH object 混链。当前实现先完成同批 TU 再做整轮重试（probe 先行下该路径理论不可达）；在发现首个拒绝时立即销毁其余 TU 需要 attempt 级进程注册表，未实施。
- `ResourceExtractor` 的内容比较跳过是 PCH 可复用性的前提：runtime 头文件 mtime 不因重复提取而变化。外部仅改 mtime 的极端情况由回退路径兜底（安全降级，永不失败构建）。

## 5. 并行、进程注册表与取消

### 5.1 TU 并行

- 并行度 `Math.clamp(Runtime.getRuntime().availableProcessors(), 1, cFiles.size())`，固定大小虚拟线程池（线程名 `gdcc-zig-tu-*`）。
- 所有已启动 TU 运行到结束；单个 TU 失败不取消兄弟 TU（失败收集语义）。worker 的未检查异常（`ExecutionException`）触发整轮取消。
- TU 结果按完成序收集以及时观察异常；日志按 `cFiles` 输入序合并。

### 5.2 进程注册表

- `CProcessRegistry`（package-private）提供 `register(process)` 与 `cancelAndSnapshot()`，两者在同一监视器下互斥；`cancelAndSnapshot()` 先置 closed 再返回已登记进程，一次性生效；closed 后的 `register()` 立即 `destroyForcibly()` 该进程。每轮构建创建新实例。
- 注册范围覆盖 TU 编译、PCH 构建、PCH probe、链接与 `zig version` 探测。
- `CProcessLauncher`（package-private 函数式接口）是全部子进程的启动缝：默认实现 `processBuilder()`；只有进程完全无法启动才抛 `IOException`，返回即视为已启动（`ProcessBuilder.start()` 的不可中断窗口由 closed-register 销毁迟发进程兜底）。

### 5.3 取消与中断

- `compile()` 捕获 `InterruptedException`：关闭 registry、销毁已登记进程、`shutdownNow()` executor、不可中断地等待全部 worker 终止、输出 reader 有界 join（1 秒）、恢复线程 interrupt 状态，返回 `success=false` + `Failed to run zig: interrupted` + 空 artifacts，不合并 Command 段。中断绝不落入 PCH 回退通道；进程完全无法启动（`IOException`）同样走固定 `Failed to run zig: <message>` 通道。
- 已排队未启动的 TU 不会迟启动；链接尚未启动时不启动链接（中断发生在链接 `waitFor` 之后时，已启动的链接进程由 registry 销毁）。
- `ZigUtil.findZigVersion()` 遵循同一中断语义；只缓存成功结果（失败/中断/空输出不缓存）；冷缓存允许并发重复探测，第一个成功值经 `synchronized` 发布（`volatile` 读取）。`findZig()` 的成功路径进程内缓存、负结果不缓存。
- API 层：`cancelCompileTask()` 在 `BUILDING_NATIVE` 阶段映射为 `CANCELED`；普通失败映射 `FAILED`+`BUILD_FAILED` 并透传 buildLog。

### 5.4 项目构建锁

- 静态 `ConcurrentHashMap<Path, ReentrantLock>`（key 为归一化绝对路径），`lockInterruptibly()` 覆盖整轮（路径/缓存解析、编译、链接、产物探测）。
- 只协调同一 JVM 内同一 `projectDir` 的构建；跨 gdcc 进程/实例的冲突由外部启动方协调，PCH 同 key 并发写入为 last-writer-wins（临时文件 + `ATOMIC_MOVE` rename，不支持时退化普通 rename），重复构建不得损坏条目。

## 6. buildLog 合并合同

- 成功日志只合并原始输出：PCH 前导（如有）→ TU 槽位（按 `cFiles` 序）→ 链接；空输出不添加 `Command:` 行。
- 失败日志只为**已启动**的进程输出 `Command: <argv>` + 其输出段，顺序为 PCH 回退/重试行（如有，永远最前）→ PCH 构建/probe 段 → TU 段（按 `cFiles` 序）→ 链接段。未启动的 TU/probe/链接无 Command 段；链接失败时链接段位于全部 TU 段之后；TU 失败或取消时无链接段。
- worker 先把输出写入按 `cFiles` 下标索引的槽位，最终统一合并；并行度不足时已启动 TU 集合可受调度影响，合同只要求已启动段按固定顺序排列。
- 输出读取失败不覆盖进程 exit code，追加 `[gdcc] incomplete compiler output: <exception>`。

## 7. 产物、命名与时序

- `CCompileResult.artifacts()`：第一项永远是最终共享库（CLI 经 `artifacts.getFirst()` 生成 `.gdextension`）；Windows 下 `<projectDir>/<outputBaseName>.pdb` 存在时列第二；object 与 PCH 文件绝不进入 artifacts。失败时 artifacts 为空，下游不从磁盘推断。
- 输出基名 `<projectName>_<debug|release>_<architecture-lowercase>`，扩展名/前缀按 target 平台规则。
- `CBuildResult.Timing` 字段：`includeExtraction`、`codeGeneration`、`generatedFileWrite`、`compileInputCollection`、`nativeCompile`（覆盖全部 TU 编译 + 链接全程）、`total`。
- 链接直接经 `-o` 写正式产物，不做临时输出 + rename 的原子发布；失败时磁盘可残留旧产物或半成品。

## 8. 测试约定

- 真 zig/Godot 依赖一律 `ZigUtil.findZig()` + `Assumptions` 门控；迭代期只跑定向测试：`script/run-gradle-targeted-tests.sh --tests <类名>`。
- negative path 直调公开 `ZigCcCompiler.compile(...)`（精心构造的 `.c` 与独立 include 树），不得通过破坏 shared include/共享 cache 构造失败。
- 性能数字是手工参考值（注明机器与 zig 版本），写入 PR 描述，不进入 CI 断言。
- 类级并行（`src/test/resources/junit-platform.properties`）：全局默认 `same_thread`，仅 `@Execution(ExecutionMode.CONCURRENT)` 标注类进入 fork-join 池，fixed parallelism=4（可经 `-Djunit.jupiter.execution.parallel.config.fixed.parallelism=N` 覆盖）。当前标注类：`ZigCcCompilerCrossTargetSmokeTest`（多 target 以 `@ParameterizedTest` 逐 target 拆分）、`ZigCcCompilerFailureTest`、`ZigCcCompilerIncrementalIntegrationTest`、`ZigCcCompilerPchIntegrationTest`。明确不并行：`ZigCcCompilerParallelTest` 与 `ApiZigCcCompilerCancellationTest`（时序/取消语义敏感）及全部 fake/纯 Java 测试。
- 并行安全约定：并发类必须逐方法 `@TempDir`、无静态可变状态；Godot 校验不得使用共享 `test_project` 目录（复制 fixture 到独立目录）。
- 吞吐量测试：`GdScriptBenchmarkCompileTest` 标注 `@Tag("throughput")`，test 任务默认 `excludeTags("throughput")`，本地经 `-PrunThroughputTests=true` 显式开启。
- 锚点测试类：命令级 `ZigCcCompilerCommandTest`（纯 Java）、`ZigCcCompilerPchTest`/`ZigCcCompilerPchKeyTest`（fake）、`ZigCcCompilerFailureTest`/`ZigCcCompilerIncrementalIntegrationTest`/`ZigCcCompilerCrossTargetSmokeTest`/`ZigCcCompilerPchIntegrationTest`（真 zig）、`ZigCcCompilerProjectLockTest`、`ZigCcCompilerParallelTest`、`CProcessRegistryTest`、`ApiZigCcCompilerCancellationTest`、`ZigUtilTest`。

## 9. 性能参考（本开发机，zig 0.16.0）

rotating_camera 示例全管线实测（44 核 Xeon E5-2699 v4）：

| 场景 | DEBUG | RELEASE |
|---|---|---|
| 增量（仅 entry TU 变，PCH+缓存暖） | ~310ms | ~1.1s |
| 暖机全量（共享 zig cache 暖，新项目 PCH 冷） | ~3.9s | ~6.5s |
| 全冷全量（含 zig 一次性 compiler-rt 构建） | ~19.6s | ~10.5s |

构成性事实（架构取舍依据）：

- `godot_binding.c` 聚合 TU 占编译时间 O0 约 83%、O2 约 93%，其内容只随 Godot API 版本与 gdcc runtime 变化；
- zig per-TU 内容缓存命中代价约 30-48ms，cache key 不含输出路径，同一 cache root 可跨项目复用，因此 gdcc 不自管 object 缓存；
- PCH 使 entry TU 前端耗时再降约 35-40%（`godot_binding.c` 收益很小，其价值在 entry TU 与一致性）；
- 全冷构建的增量成本是 zig 一次性 compiler-rt 构建，属非常态。

## 10. 已知限制与后续方向

- cache root 无容量管理/LRU 清理；PCH 无跨进程锁文件（同 key last-writer-wins 已保证不损坏）。
- 外部仅修改 include 树 mtime（不改内容）时 clang 可能拒绝已安装 PCH：经回退路径安全降级，直到内容变化产生新 key；根治候选（include 树 mtime 归一化、heal 期 nonce 前缀头）未实施。
- TU 拒绝 PCH 的早销毁（发现即销毁其余 PCH TU）未实施：需要 attempt 级注册表与跨 attempt 取消闭合，当前先完成同批再整轮无 PCH 重试（probe 先行下理论不可达）。
- `ZigUtil.findByWhichOrWhere` 在 `IOException` 时会设置 interrupt 标记（语义不精确），列为候选修正。
- 后续方向（post-MVP，另行立项）：拆分 `godot_binding.c` 聚合 TU；直接驱动 `zig ld.lld` 以启用 `--thinlto-cache-dir`/`--time-trace`；PCH 覆盖扩展至 gdcc 树头文件；entry 级 TU 头文件瘦身（当前 `entry.h` 预处理展开约 19k 行，是 entry TU 前端耗时主因）；web 的 Emscripten 后端（ASYNCIFY coroutine + 放宽 `MCO_USE_VMEM_ALLOCATOR` + emcc/Binaryen 构建链，需同步更新 `doc/gdcc_runtime_lib.md` 的协程合同）。
