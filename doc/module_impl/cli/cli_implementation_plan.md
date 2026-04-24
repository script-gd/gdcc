# GDCC CLI 实施计划

> 本文档是首个基于 picocli 的 `gdcc` 命令行界面的实施计划。CLI 必须作为
> `dev.superice.gdcc.api.API` 之上的本地薄适配层：它负责从宿主机文件准备一个虚拟
> module，配置编译选项和顶层 canonical 类名映射，启动一个 compile task，等待任务完成，
> 并把结果渲染给终端用户。

## 文档状态

- 状态：实施计划
- 创建日期：2026-04-24
- 适用范围：
  - `src/main/java/dev/superice/gdcc/Main.java`
  - `src/main/java/dev/superice/gdcc/cli/**`
  - `src/test/java/dev/superice/gdcc/cli/**`
  - CLI 输入需要的聚焦 API/frontend 测试
  - 本文档
- 直接事实源：
  - `doc/module_impl/api/rpc_api_implementation.md`
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/runtime_name_mapping_implementation.md`
  - `doc/module_impl/frontend/gdcc_facing_class_name_contract.md`
  - `src/main/java/dev/superice/gdcc/api/API.java`
  - `src/main/java/dev/superice/gdcc/api/CompileOptions.java`
  - `src/main/java/dev/superice/gdcc/api/CompileResult.java`
  - `src/main/java/dev/superice/gdcc/api/CompileTaskSnapshot.java`
  - `src/main/java/dev/superice/gdcc/api/CompileTaskEvent.java`
  - `src/main/java/dev/superice/gdcc/frontend/FrontendClassNameContract.java`
  - `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
  - `src/main/java/dev/superice/gdcc/backend/c/build/CProjectBuilder.java`
- 明确非目标：
  - 不实现网络或 RPC transport。
  - 不在 CLI 代码中实现第二套编译管线。
  - 除 `--prefix` 所需的窄范围 source-name 发现外，不让 CLI 直接 parse/lower/codegen。
  - 不新增一套镜像 `CompileOptions` 的配置抽象。

---

## 1. 目标用户契约

### 1.1 命令形态

首版实施目标：

```powershell
gdcc [options] <files...>
```

必需行为：

- `files` 是非空的宿主机 `.gd` 源文件列表。
- 每个文件按 UTF-8 文本读取，并写入同一个 API module VFS 的 `/src/...` 下。
- 原始宿主机路径作为 API 文件 `displayPath` 传入，让诊断和结果里的 source path 对终端用户有意义。
- `-o` / `--output` 指定编译目标，语义与 clang 风格一致：多个输入文件对应一个输出目标。
- `--prefix` 为使用文件名推导出的默认顶层类名提供通用 canonical 前缀。
- `--class-map` 指定显式的顶层 source 到 canonical 映射，并允许重复传入。
- `-v` / `--verbose` 可重复传入，用于提高终端输出详细程度。
- `--gde` 指定编译使用的 Godot GDExtension API 版本。当前仓库状态下唯一接受的值是 `4.5.1`，映射到 `GodotVersion.V451`。

计划支持示例：

```powershell
gdcc -o build/demo src/player.gd src/enemy.gd
gdcc -o build/demo --prefix Game_ src/player.gd src/enemy.gd
gdcc -o build/demo --class-map Player=RuntimePlayer --class-map Enemy=RuntimeEnemy src/player.gd src/enemy.gd
gdcc -o build/demo --gde 4.5.1 -v src/player.gd src/enemy.gd
```

### 1.2 `-o` / `--output` 解释规则

使用一个 path 参数：

```text
-o <output-dir>/<module-name>
```

具体映射：

- 最后一个 path segment 是 API 的 `moduleId` 和 `moduleName`。
- 父路径是 `CompileOptions.projectPath`。
- 如果该 path 没有父路径，则父路径使用当前工作目录。
- 该 path 不是 API VFS 路径，而是宿主机文件系统路径。

示例：

```text
-o build/demo
```

含义：

- API module id/name：`demo`
- 宿主机构建目录：`build`

当前 backend 仍会按如下规则命名 native artifact：

```text
<module>_<optimization>_<architecture>.<platform-extension>
```

例如，在 native Windows debug x86_64 下，artifact 预计类似：

```text
build/demo_debug_x86_64.dll
```

CLI MVP 不改变 `CProjectBuilder` 的输出命名。如果后续 CLI 工作需要精确的 clang-compatible artifact 名称，应单独修改 backend/API 的 output-name 契约，而不是在 CLI build 后偷偷移动或重命名文件。

验收：

- `gdcc -o build/demo a.gd b.gd` 创建一个名为 `demo` 的 API module。
- generated C 文件和最终 artifact 产出到 `build` 下。
- CLI 从 `CompileResult.artifacts()` 报告 artifact path，而不是自行猜测。
- blank output path、root-only path，以及被 `API.createModule(...)` 拒绝的 module name，以 exit code `2` 失败。

### 1.3 输入文件 VFS 布局

CLI 应创建确定性的 VFS 路径，但不把宿主机文件系统镜像成通用 VFS：

```text
/src/<input-index>/<host-file-name>
```

其中 `input-index` 是否补零只取决于实现是否希望 lexical order 与输入顺序一致。`displayPath` 仍保留用户传入的宿主机路径。

理由：

- 避免两个宿主机文件 basename 相同导致 VFS path 冲突。
- 保持 source collection 简单。
- 避免把 Windows 绝对路径误当成 virtual path。
- 保持 frontend 默认类名推导基于每个文件 basename，这与当前 `FrontendClassSkeletonBuilder` 行为一致。

验收：

- 来自不同目录的两个 `main.gd` 都可以加载进同一个 module VFS。
- 如果这两个文件都依赖默认 `Main` 类名，它们的 frontend source name 仍会冲突；CLI 不得隐藏这个 frontend diagnostic。
- 诊断使用原始 display path，而不是 `/src/...` 或 synthetic logical path。

### 1.4 顶层 canonical 映射选项

`--class-map` 语法：

```text
--class-map Source=Canonical
```

规则：

- 该选项可重复传入。
- key 和 value 是原始类名，不是 VFS path。
- 重复的 `Source` entry 由 CLI 在调用 `API` 前拒绝。
- entries 必须传给 `API.setTopLevelCanonicalNameMap(...)`，因此 reserved `__sub__`、blank 和 null-like 边界检查仍由 `FrontendClassNameContract` 负责。

`--prefix` 语法：

```text
--prefix PrefixText
```

规则：

- prefix 只作用于没有显式 `class_name` statement、因此顶层类名由文件名推导得到的文件。
- 对每个这类文件，CLI 添加一个生成映射：

```text
<defaultSourceName> -> <prefix><defaultSourceName>
```

- 对同一个 source name，显式 `--class-map` entry 覆盖由 `--prefix` 生成的 entry。
- 如果合并后的 map 包含非法名称或 canonical 冲突，应由现有 API/frontend 边界报告。

实现说明：

- 现有默认名称算法是 `FrontendClassSkeletonBuilder` 的 private 方法。
- 为避免 CLI 复制该规则，先把它提取为 `FrontendClassNameContract.deriveDefaultTopLevelSourceName(Path sourcePath)`，再更新 `FrontendClassSkeletonBuilder` 调用该 helper。
- 为判断一个文件是否存在显式 `class_name`，CLI 可以用 `GdScriptParserService` 解析 source 并检查 top-level statements。这只是为 option expansion 服务的窄范围 CLI preflight，不得替代 API compile pipeline 中的 parse/lowering 工作。
- 如果 preflight parsing 本身产生错误，CLI 不应尝试二次判断；可以跳过该文件的 prefix-generated mapping，让实际 API compile 产生 canonical diagnostics。

验收：

- 文件 `player.gd` 没有 `class_name` 且传入 `--prefix Game_` 时，编译使用 map `Player -> Game_Player`。
- 文件 `player.gd` 包含 `class_name Hero` 且传入 `--prefix Game_` 时，不自动生成 `Hero -> Game_Hero`。
- `--class-map Player=CustomPlayer --prefix Game_ player.gd` 使用 `Player -> CustomPlayer`。
- `--prefix A__sub__` 通过 `FrontendClassNameContract` 的同一 reserved-sequence 错误失败。
- mapped top-level + inner class 仍得到类似 `Game_Player__sub__Weapon` 的 canonical name。

### 1.5 GDExtension API 版本选项

CLI 选项：

```text
--gde 4.5.1
```

规则：

- 接受 `4.5.1`，映射到 `GodotVersion.V451`。
- 仅当测试固定该行为时，才可额外接受 `451` 作为 convenience alias。
- 拒绝所有其他值，并在错误信息中列出支持的版本。
- 不允许静默 fallback 到 `4.5.1`。

验收：

- `--gde 4.5.1` 得到 `CompileOptions.godotVersion() == GodotVersion.V451`。
- `--gde 4.6.1` 以 exit code `2` 失败，并且不创建 compile task。

### 1.6 Verbosity 与终端输出

verbosity level：

- `0`：默认
  - 打印 frontend diagnostics 和最终 success/failure summary。
  - 成功时打印 artifact paths。
  - 只在 build failure 时打印 build log。
- `1`：`-v` / `--verbose`
  - 打印每次 compile task stage 变化。
  - 通过 `listCompileTaskEvents(taskId, start, max)` 轮询并打印所有 retained compile task events。
  - 打印 output VFS links。
- `2+`：重复 verbose，例如 `-vv`
  - 额外打印 compile options、source display paths、top-level canonical map、generated file paths。
  - 即使成功，也打印非空 build log。

event 处理：

- 任何大于 0 的 verbosity level 都必须输出运行期间 retained 的所有 API task events。
- 使用 `API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE` 进行分页读取。
- 记录下一个未读 event index，避免重复打印 events。
- task 完成后，最终渲染结果前再 drain 一次剩余 event pages。

logger 与控制台编码：

- CLI 模式下必须把 `GdccLogger` 切到 plain output 模式，日志只输出 SLF4J message pattern 格式化后的内容，不再追加时间、等级、logger name 或 ANSI 颜色。
- plain output 只应在 CLI 执行期间生效；嵌入式调用或测试结束后要恢复进入 CLI 前的 logger 输出模式，避免污染同一 JVM 内后续调用。
- CLI 和 logger 写入 stdout/stderr 时不得默认假定终端是 UTF-8；应读取当前 `PrintStream.charset()`，按真实 stdout/stderr 编码写出。
- 为避免目标编码无法表示的字符被静默替换成 `?` 或丢失，输出前应按 code point 检查可编码性；不可编码字符转义为 `\uXXXX` 或 `\UXXXXXXXX` 后再写入。这样即使控制台是 ASCII/GBK 等受限编码，用户仍能看到完整的原始字符信息。

验收：

- 默认输出足够稳定，脚本可以识别 success/failure 和 artifact paths。
- `-v` 打印 compiler phases 记录的所有 `CompileTaskEvent` entries。
- `-v` 不依赖 `listCompileTaskEvents(taskId)` 全量读取。
- 重复传入 `-v` 不改变编译行为。
- CLI 模式下 logger 输出不包含时间、等级、logger name 或 ANSI 颜色。
- 默认非 CLI logger 输出仍保留原有彩色格式。
- stdout/stderr writer 使用各自 `PrintStream.charset()`，UTF-8 可表示文本保持原样，目标编码不可表示文本转为 `\uXXXX`/`\UXXXXXXXX`，不发生静默漏字。

### 1.7 Exit Codes

使用一组小而稳定的 exit-code surface：

- `0`：compile succeeded
- `1`：compile completed，但 `CompileResult.outcome()` 不是 `SUCCESS`
- `2`：compile task 创建前的命令行用法或配置错误
- `130`：等待 compile task 时被用户中断

验收：

- invalid options 通过 picocli parameter exception handling 返回 `2`。
- frontend errors 返回 `1`，不是 `2`。
- native build failure 返回 `1`，默认输出 build log。
- interrupt 在返回 `130` 前调用 `API.cancelCompileTask(taskId)`。

---

## 2. 实施步骤

### Step 1：新增 CLI package 与 picocli 入口

状态：已实施，已通过聚焦测试验证。

创建 `dev.superice.gdcc.cli.GdccCommand`。

所有新增 CLI 相关生产代码类都必须放在 `dev.superice.gdcc.cli` 包下。`Main` 只作为现有顶层入口保留在
`dev.superice.gdcc` 包中，并委托给 `dev.superice.gdcc.cli.GdccCommand`；不要在 `api`、`backend`、
`frontend` 或根包中散落 CLI helper。

形态：

- `GdccCommand implements Callable<Integer>`
- 使用声明式 picocli：命令、参数和选项通过 picocli annotations 定义在 command class 和 fields 上。
- command object 只持有 terminal adapter 状态：parsed options、output writers、API instance。
- 默认 public constructor 创建 `new API()`。
- 测试 constructor 可接收 `API`、output writer 和 error writer。

更新 `Main`：

- 用 picocli invocation 替换当前 logger-only body。
- 保持 `Main` 很小；它不应包含 option handling 或 compile orchestration。

验收：

- [x] `Main.main(args)` 委托给 picocli。
- [x] `GdccCommand` help text 列出 files、`-o/--output`、`--prefix`、`--class-map`、`--gde` 和 `-v/--verbose`。
- [x] 除 `Main` 外，新增 CLI 生产代码全部位于 `dev.superice.gdcc.cli` 包。
- [x] `GdccCommand` 使用声明式 picocli annotations，而不是手写一套 option parser。
- [x] 不为单一实现新增 service interface。

实施记录：

- `dev.superice.gdcc.cli.GdccCommand` 当前只安装声明式命令面，并持有后续步骤需要的 parsed options、terminal writers 和 `API` 实例。
- `GdccCommand.call()` 在 Step 1 阶段故意返回 usage failure，并输出 compile pipeline 尚未实现；该边界已在 Step 2
  替换为真实的 API module/input 准备逻辑，后续步骤继续在此基础上接入 compile options、mapping 和 task polling。
- `Main` 保持在 `dev.superice.gdcc` 根包内，但只调用 `GdccCommand.execute(args)`，不包含 option handling 或编译编排。
- `GdccCommandOptionTest` 覆盖声明式 annotations、help 文本、缺失必填项时 picocli 在 command body 前失败、repeatable `--class-map`、`-vv` 计数，以及 Step 1 临时边界。
- `MainEntrypointTest` 固定 `Main.run(args)` 到 picocli help 的委托行为，避免入口重新引入本地 option parsing 或编译编排。
- 测试发现：picocli 4.7.7 不会把 `int -v` 字段自动当作计数 flag；`-vv` 会被解释为 `-v v` 并触发 int 转换错误。Step 1 实现改用声明式 `boolean[]` flag 累积，并通过 `verbosityLevel()` 暴露计数。
- 已运行 `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdccCommandOptionTest,MainEntrypointTest`，结果通过。
- CLI 入口通过 `GdccLogger.setPlainOutput(true)` 临时启用 logger 朴素输出，并在 `finally` 中恢复原状态。
- `ConsoleOutputUtil` 统一创建 stdout/stderr writer，并在 logger 直接写入 `System.out` 时复用同一编码策略；该策略读取目标 `PrintStream.charset()`，对不可编码 code point 进行可逆文本转义，避免控制台编码不支持时漏字。

### Step 2：实现 CLI 输入归一化

状态：已实施，已通过聚焦测试验证。

职责：

- [x] 要求至少一个文件。
- [x] 要求 `-o/--output`。
- [x] 使用 `Path` 归一化并校验宿主机输入路径。
- [x] 在创建 API module 前拒绝缺失文件和目录。
- [x] 按 UTF-8 读取 source files。
- [x] 创建一个 module。
- [x] 按如下方式写入每个文件：

```java
api.putFile(moduleId, virtualPath, sourceText, displayPath)
```

将 API exceptions 作为 user-facing errors 处理；除非确实需要新的项目异常，不要包一层平行异常体系。

验收：

- [x] 缺失 input file 在 compile task 创建前失败。
- [x] 重复 host file arguments 默认可视为用户有意创建重复 sources；如果测试中证明这很困惑，再以清晰 CLI error
  拒绝重复项。
- [x] VFS path 生成是确定性的，且不依赖宿主机 path separators。

实施记录：

- `GdccCommand.call()` 现在先解析 `-o/--output` 的最后一个 path segment 作为 `moduleId`/`moduleName`，再预读所有输入文件，
  最后才调用 `API.createModule(...)` 和 `API.putFile(...)`。因此缺失文件、目录输入、blank/root-only output path 等错误不会创建
  module。
- 输入文件使用 `Path.toAbsolutePath().normalize()` 进行宿主机校验，并通过 `Files.readString(..., StandardCharsets.UTF_8)` 读取。
  API `displayPath` 保留用户传入的原始 path 文本，避免诊断以后显示 synthetic VFS path。
- VFS 路径采用 `/src/%04d/<host-file-name>`。补零 index 让普通输入规模下的 lexical order 与参数顺序一致，同时允许两个不同目录的
  `main.gd` 或同一个 host file 重复传入时都作为独立 source 写入同一 module。
- 当前 Step 3 仍不会创建 compile task；成功准备 module/input/options 后继续返回 usage exit code `2` 并输出
  `compile task execution is not implemented yet`。Step 5 接入 task polling 后再替换为真实编译返回码。
- 新增 `GdccCommandInputTest`，覆盖 UTF-8 source 读取、同名 basename、重复 host file、缺失文件、目录输入、blank/root-only
  output path，以及 API module duplicate error 的 user-facing 渲染。
- 已运行 `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdccCommandOptionTest,GdccCommandInputTest,MainEntrypointTest`，
  结果通过。

### Step 3：配置 `CompileOptions`

状态：已实施，已通过聚焦测试验证。

直接构建 `CompileOptions`：

```java
new CompileOptions(
        parsedGodotVersion,
        outputDirectory,
        COptimizationLevel.DEBUG,
        TargetPlatform.getNativePlatform(),
        false,
        CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT
)
```

CLI MVP 不需要 release/target/strict 选项，除非后续明确提出。不要因为 `CompileOptions` 有字段就添加未使用 flags。

验收：

- [x] `--gde` 映射到选中的 `GodotVersion`。
- [x] `projectPath` 来自 `-o/--output` 的父目录。
- [x] optimization、target platform、strict mode 和 output mount root 使用当前 API defaults。

实施记录：

- `GdccCommand` 在任何 API state mutation 前解析 `--gde`；当前只接受 `4.5.1` 并映射到 `GodotVersion.V451`。
  测试只锚定已存在 gdextension 数据的正向映射，不用 `4.6.1`、`451` 等具体版本值负向用例固定未来扩展空间。
- `-o/--output` 继续使用最后一个 path segment 作为 `moduleId`/`moduleName`；`CompileOptions.projectPath` 使用该输出路径的父目录。
  若用户传入无父路径的输出目标，例如 `-o demo`，则 project path 使用当前工作目录。
- CLI 直接构造 API `CompileOptions`，没有新增镜像配置对象；除 `godotVersion` 和 `projectPath` 外，optimization 使用
  `COptimizationLevel.DEBUG`，target 使用 `TargetPlatform.getNativePlatform()`，strict mode 为 `false`，output mount root 使用
  `CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT`。
- `GdccCommandInputTest` 新增 Step 3 覆盖：显式 `--gde 4.5.1`、无父输出路径的当前工作目录 fallback，以及默认
  compile options 字段。版本负向行为不使用具体未来版本值锚定，避免后续添加新 gdextension 数据时测试成为阻碍。
- 已运行 `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdccCommandOptionTest,GdccCommandInputTest,MainEntrypointTest`，
  结果通过。

### Step 4：实现 mapping merge

使用 `LinkedHashMap<String, String>`，让错误输出和结果 snapshot 保持 option order。

合并顺序：

1. 按 input-file order 生成 prefix mappings。
2. 应用显式 `--class-map` mappings。

对同一个 source name，显式 `--class-map` 覆盖生成 mapping。重复的显式 key 应 early fail，而不是静默选择最后一个。

然后调用：

```java
api.setTopLevelCanonicalNameMap(moduleId, mergedMap)
```

验收：

- CLI tests 覆盖 prefix-only、class-map-only、prefix-plus-override。
- invalid reserved sequence errors 来自现有 frontend contract message。
- result snapshot 通过 `CompileResult.topLevelCanonicalNameMap()` 暴露合并后的 map。

### Step 5：等待 Compile Task 并 drain events

调用：

```java
var taskId = api.compile(moduleId);
```

polling loop：

- 轮询 `api.getCompileTask(taskId)`，直到 `snapshot.completed()`。
- verbose 时检测 stage/revision 变化并渲染 progress。
- verbose 时调用分页 `listCompileTaskEvents(taskId, nextEventIndex, pageSize)` 并打印每个新 event。
- 每次 poll 之间短暂 sleep。
- 中断时请求 cancellation，并返回 `130`。

验收：

- 不 busy spin；polling 有短暂 sleep。
- queued/running/succeeded/failed/canceled task states 都被处理。
- final result 始终从 completed snapshot 读取，只要该 snapshot 可用。

### Step 6：渲染结果

如果存在 frontend diagnostics，先渲染 diagnostics。

建议 diagnostic line：

```text
<severity> <category> <sourcePath>:<range>: <message>
```

使用当前 `FrontendDiagnostic` fields；如果 range formatting 比较别扭，首版实现保持简单稳定，不发明新的 diagnostic model。

成功输出：

- 打印 `Compiled module <moduleId>`。
- 打印 `CompileResult.artifacts()` 中的每个 artifact path。

失败输出：

- 打印 `Compile failed: <outcome>`。
- 打印 `failureMessage`。
- 如果 outcome 是 `BUILD_FAILED`，打印 `buildLog`。
- 如果 verbosity level 至少为 `2`，任何 outcome 下只要 build log 非空都打印。

验收：

- frontend diagnostic display paths 与输入文件 display paths 一致。
- success output 包含 API 报告的每个 artifact。
- build failures 暴露足够 log text，用于调试 Zig 缺失或 C compiler errors。

### Step 7：新增聚焦测试

建议测试类：

- `GdccCommandOptionTest`
  - option parsing
  - `--gde` accepted/rejected values
  - `--class-map` syntax 与 duplicate-key rejection
  - `-v`、`-vv` verbosity count
- `GdccCommandApiIntegrationTest`
  - 参照现有 API test pattern，使用注入了 fake `CCompiler` 的 `API`
  - 验证 files 被加载到同一个 virtual module
  - 验证 `-o` 映射到 module id/name 和 project path
  - 验证 successful compile exit code 和 artifact output
  - 验证 frontend failure exit code
- `FrontendClassNameContractTest`
  - 固定提取后的 `deriveDefaultTopLevelSourceName(Path)` 与既有文件名行为一致

目标测试命令：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdccCommandOptionTest,GdccCommandApiIntegrationTest,FrontendClassNameContractTest
```

验收：

- 测试不要求真实 Zig 安装。
- 测试覆盖 CLI 行为，而不是复述整个 API test suite。

---

## 3. 验收清单

- [x] `gdcc -o build/demo a.gd b.gd` 创建且只创建一个 API module。
- [x] 除 `Main` 外，新增 CLI 生产代码全部位于 `dev.superice.gdcc.cli` 包。
- [x] `GdccCommand` 使用声明式 picocli annotations。
- [x] 每个输入文件以确定性的 `/src/...` path 放入 module VFS。
- [ ] diagnostics 和 result source paths 使用原始 display paths。
- [x] `-o/--output` 控制 module name 和宿主机构建目录。
- [x] `--gde 4.5.1` 选择 `GodotVersion.V451`。
- [x] unsupported `--gde` values 在 compile task 创建前失败。
- [ ] `--class-map Source=Canonical` 可重复传入。
- [ ] duplicate explicit `--class-map` keys early fail。
- [ ] `--prefix` 只映射 file-name-derived top-level classes。
- [ ] 对同一 source name，显式 `--class-map` 覆盖生成的 `--prefix` mapping。
- [ ] merged mappings 通过 `API.setTopLevelCanonicalNameMap(...)` 传入。
- [ ] invalid mapping names 复用 frontend/API reserved-sequence errors。
- [ ] 默认输出打印 diagnostics、final status 和 artifacts。
- [ ] verbose 输出通过分页读取 drain 所有 API task events。
- [ ] interrupted compile attempts 调用 `API.cancelCompileTask(taskId)`。
- [ ] success 返回 exit code `0`。
- [ ] compile failure 返回 exit code `1`。
- [x] usage/configuration failure 返回 exit code `2`。
- [ ] interrupt 返回 exit code `130`。
- [x] focused tests 通过 targeted Gradle script 运行。

---

## 4. 风险与后续决策

- 当前 artifact naming 尚未完全 clang-compatible，因为 `CProjectBuilder` 会把 optimization 和 architecture 追加到 module name。CLI MVP 应报告 API artifacts，而不是 build 后移动或重命名文件。
- `--prefix` 需要一次小的 frontend 提取，以共享默认 class-name 推导。避免在 CLI 中复制 `FrontendClassSkeletonBuilder` 的文件名规则。
- prefix preflight parsing 会重复 API parse pass。这对 CLI MVP 可接受，因为它是 option expansion，不是 compilation。如果后续变得昂贵或语义脆弱，应有意新增 API 或 frontend preflight surface。
- 可执行分发可能需要 Gradle `application` 配置或生成脚本；该事项应在 CLI implementation 本体之外单独确认。
