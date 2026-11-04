# CLI 整项目编译实施计划

状态：实施中。第一、二、三步已实现并经定向测试验证；第四步未实施，对应行为尚不存在。

## 1. 目标与范围

新增以 `project.godot` 为入口的项目编译模式：

- 自动收集项目内符合排除规则的 `.gd3`，忽略所有未显式指定的 `.gd`。
- 允许额外传入 `.gd` 文件，将其与 `.gd3` 作为同等 GDCC 源，放入同一个 module 编译。
- 成功后将动态库发布到项目 `bin/`，创建或更新唯一的 `bin/gdcc.gdextension`。
- 复用现有 API、前端和 C backend，不改变 GDScript 语义或新增编译服务接口。

本期不做场景/Script 挂载转换、Autoload 改写、UID 迁移，也不主动控制正在运行的 Godot
编辑器。文件发布成功与编辑器已加载扩展是两个不同状态。

## 2. 现有实现与改动位置

| 位置 | 现状与复用方式 |
|---|---|
| `src/launcher/zig/src/main.zig` | 已透传参数并返回 Java 退出码；本次不增加项目解析逻辑 |
| `src/main/java/gd/script/gdcc/Main.java` | 保持 `serve` 与普通 CLI 的现有路由 |
| `src/main/java/gd/script/gdcc/cli/GdccCommand.java` | 目前只接受显式 `.gd`；在输入准备处增加项目模式，复用选项、任务等待及日志输出 |
| `src/main/java/gd/script/gdcc/api/ModuleState.java` | 已支持同一 module 收集 `.gd` 和 `.gd3`，无需改 API |
| `src/editor_addon/addons/gdcc/gdcc_file_reconciler.gd3` | 作为自动收集 `.gd3`、点目录和排除 glob 的对齐依据 |
| `src/editor_addon/addons/gdcc/gdcc_project_manager.gd` | 以 `_deploy_build_result()` 为 `bin` 库命名、托管标记和描述文件合并的依据 |

实施前重点回读：

- `doc/module_impl/cli/cli_implementation.md`：旧 CLI 行为。
- `doc/module_impl/editor_addon/gd3_editor_integration_implementation.md` §4.9、§7.1、§7.4：
  项目发布、来源元数据与排除规则。
- `doc/module_impl/frontend/superclass_canonical_name_contract.md` §3：当前继承语义边界。
- `doc/module_impl/common_rules.md`、`doc/module_impl/frontend/frontend_rules.md`：实施约束。

## 3. 命令行为

```bash
gdcc game/project.godot
gdcc game/project.godot game/src/explicit_helper.gd
gdcc --opt release --backend zig game/project.godot game/src/explicit_helper.gd
```

- positional 参数中有且只有一个 `project.godot` 时进入项目模式，其余文件参数接受 `.gd`。
- 没有项目 positional 参数时仍走旧文件模式；`--project` 保持现有 metadata 选项含义，
  不将旧调用悄悄改为项目扫描。
- `.gd` 由命令行显式指定，不向 `project.godot` 新增源文件清单或其他配置项。
- `-o` 仍指定内部构建目录，不改变发布目的地；项目模式不在该目录额外生成第二个
  `.gdextension`。
- `--prefix`、`--class-map`、`--gde`、`--target`、help/version 和 `serve` 保持既有含义。

## 4. 实现设计

### 4.1 读取项目设置

在 CLI 层读取项目文件，沿用 addon 使用的设置：

| 设置 | 用途 / 缺省值 |
|---|---|
| `application/config/name` | 项目名称，要求非空 |
| `gdcc/sync/excluded_globs` | 自动发现排除项，缺省 `["addons/gdcc/*"]` |
| `gdcc/build/optimization_level` | `DEBUG` / `RELEASE`，缺省 `DEBUG` |
| `gdcc/build/strict_mode` | bool，缺省 false |
| `gdcc/build/c_compiler_kind` | `AUTO` / `ZIG_CC` / `TINY_CC`，缺省 `AUTO` |

显式 CLI 优化级别和 backend 选项覆盖项目设置，省略时继承项目值；必须区分用户显式参数
和 picocli 默认值。`AUTO` 继续传 null，由 API 根据最终优化级别选后端。

新增一个 CLI 内部配置适配器，支持本期使用的 String、bool、整数和字符串数组，识别
section、转义及多行值；不使用逐行正则或 `split("=")` 解析 Godot 配置。项目中无关设置
可以跳过，描述文件中无关字段必须保留。遇到不支持且会影响本次构建的类型、配置覆盖来源
或语法时明确报错，不能静默使用错误值。此适配器不执行项目代码，也不依赖启动 Godot。

### 4.2 准备源文件

最终源集为“自动发现的 `.gd3` ∪ 显式指定的 `.gd`”：

1. 以项目文件定位资源根，自动扫描时沿用 addon 的点目录及逐文件 glob 排除规则；
   `*` 跨目录，不用 Java glob 的不同语义替代。遇到嵌套项目停止进入该子树。
2. 显式 `.gd` 相对 CLI 工作目录解析，要求是当前项目内可读的 regular file，不跨入其他
   项目。显式选择可覆盖自动扫描排除项，但不能编译 `addons/gdcc/`、缓存和托管发布目录。
3. 拒绝源符号链接及目录链接绕过；不把整个项目目录挂进 VFS，也不自动追加未指定 `.gd`
   的依赖。按项目相对路径去重、排序，源集非空即可；只有显式 `.gd` 也是合法输入。
4. 保留原文和真实后缀。例如显式 `src/helper.gd` 上传为 `/src/src/helper.gd`，
   `displayPath = res://src/helper.gd`，同时传宿主绝对路径。通过五参数 `API.putFile`
   保留诊断及编译期元数据来源，不改磁盘文件名。
5. 完成源读取与配置校验后才创建 module；文件不可读、坏 UTF-8 或准备期间检测到变动时
   整体失败。重复类名、path-based extends 等语义错误仍由现有前端诊断，不由 CLI 改写。

“当作 `.gd3`”指相同编译处理，不是让 Godot 将磁盘上的 `.gd` 当成自定义脚本资源。
若显式 `.gd` 的 `class_name` 与编译后的原生类同名，仍可能触发 Godot 的
`Class "X" hides a native class`；不自动擦除或改名，可由用户使用既有 class map 区分。
class map 不能分开两个本就相同的 source name，后者需要修改源码类名。

### 4.3 编译与工作目录

- 一个项目准备一个 module，统一设置 options/map、上传全部源，并启动一次 compile task。
- 默认工作目录放在 `.godot/gdcc/cli/<项目身份>/`；身份由安全化项目名和真实项目路径
  派生。`-o` 只允许该 CLI 缓存子树或项目外的非祖先目录，不能落入任何 Godot 项目的
  资源树；`bin/` 只供下面的受控发布使用。
- 输出目录锁由旧文件模式和项目模式共用，覆盖生成文件、native 编译及结果处理。
  项目模式另持项目级锁，防止相同项目使用不同 `-o` 同时更新 `bin`。
- 固定先取项目锁、再取输出锁；锁冲突在创建 module 前失败。中断时请求取消并等待任务
  终态，再释放锁，避免 native 仍在写文件时下一轮进入。

锁只协调采用该协议的 CLI，不宣称能约束现有 addon 或其他写入者。

### 4.4 发布到 `bin`

发布只使用本次成功结果中的目标动态库，不扫描旧构建目录猜测产物；C、object、PDB 等
保留在工作目录。

发布流程：

1. 编译前校验 `bin` 和已有描述文件，发布时锁内重读并复核各目标路径，不跟随链接越界。
   描述文件不存在时新建；已有文件必须可解析且 `configuration/gdcc_managed` 为布尔 true，
   否则拒绝覆盖。
2. 计算库内容 SHA-256，以包含项目、平台、优化级别、架构和 hash 的新文件名发布到
   `bin`。同路径仅在完整 hash 相同时复用，不覆盖内容不同或正在使用的旧库。
3. 合并 `bin/gdcc.gdextension`，保持 addon 的格式：
   - `entry_symbol = "gdextension_entry"`、`compatibility_minimum = "4.5"`、
     `reloadable = true`、`gdcc_managed = true`。
   - `[libraries]` 使用 `platform.arch` 键，如 `linux.x86_64`；当前目标改指本次库，
     保留其他目标。优化级别体现在库名中，不新增 debug/release 别名。
   - 维护 `gdcc_libraries` 的 `PackedStringArray` 所有权列表；校验并移除旧
     `gdcc_library_aliases`。保留无关字段，不按所有权列表删除文件。
   - 迁移旧三段式 mode 键，避免更具体的旧键覆盖新条目。其他目标若无规范键且旧
     debug/release 指向不同库，明确报歧义，不能猜测编辑器模式或丢弃其他目标。
   - 管理字段类型错误或不支持的 library feature 键明确拒绝，不能悄悄保留会遮蔽新目标的旧键。
4. 先完成新 hash 库，再写同目录临时描述文件，最后原子替换描述文件。平台不支持安全
   原子替换时发布失败，不退化为先删旧文件。原有 `FileUtil.moveReplacing` 的退化行为
   不变；另加仅允许原子替换的工具方法。
5. 发布前重新校验路径与原描述文件状态；若外部写入者已改变文件，拒绝提交。本期不删除
   旧库，失败最多留下未引用的新库，不破坏原安装。此检查不等于跨 addon 的并发事务。

项目模式只维护这一份描述文件，不因项目改名或更换 `-o` 创建第二份。
CLI 不直接写 `.godot/extension_list.cfg`，不启动 Godot 或调用 scan/reload；成功输出
“已发布”及描述文件路径，不输出“已在编辑器启用”。

### 4.5 错误结果

- 输入、配置、源准备、既有描述文件校验或锁冲突：exit 2，在创建 module 前失败。
- 前端/native 编译失败：exit 1，不改现有安装。
- 编译成功但发布失败：exit 1，分别报告编译成功和发布失败，保留原描述文件及旧库。
- 取消：exit 130；最后描述文件提交前不更新安装，提交成功后不回滚或谎报安装未变。
- 编译及文件发布成功：exit 0，不等同于 Godot 已加载。

## 5. 分步实施与验收

### 第一步：项目输入和配置

状态：已完成。

在 `cli` 下增加项目输入准备和配置读取组件，接入 `GdccCommand` 的模式分流。

验收：项目入口、项目加显式 `.gd`、无 `.gd3` 但有显式 `.gd` 均能正确准备；多个项目、
跨项目脚本和坏配置在 module 创建前拒绝；CLI 显式选项覆盖项目值，旧文件模式不回归。

实施记录：新增 `GodotConfigFile`（Godot ConfigFile 语法解析）、`GodotProjectConfig`、
`GodotProjectInput` 与 `GodotConfigException`/`GodotProjectException`；`GdccCommand` 以
单个 `project.godot` positional 分流项目模式，经 picocli `ParseResult` 区分显式选项。
覆盖测试：`GodotConfigFileTest`、`GodotProjectInputTest`、
`GdccCommandProjectIntegrationTest`。

### 第二步：源收集和单次编译

状态：已完成。

实现自动/显式源集的合并、资源路径映射，复用现有 API 编译。

验收：未指定的 `.gd` 即使有语法错误也不进入 VFS；显式 `.gd` 与 `.gd3` 进入同一任务，
可按现有语义跨文件引用；去重、排除项、路径边界和 `.gd` 来源元数据均有正反例。

实施记录：新增 `GodotProjectSourceCollector`（点目录跳过、Godot `String.match` 语义
排除 glob、嵌套项目边界、托管区域禁入、符号链接拒绝、UTF-8 与准备期变动校验），经
`GdccCommand.runCompilation` 把全部源放进同一 module 单次编译；工作目录锁与发布在
第三步接入。覆盖测试：`GodotProjectSourceCollectorTest`、
`GdccCommandProjectIntegrationTest`。

### 第三步：项目发布

状态：已完成。

新增项目 publisher 和描述文件读写组件，加入工作目录锁、项目锁及原子提交。

验收：新安装、接续 addon 文件、Debug/Release 替换同一目标、其他平台/架构保留、旧格式
迁移均正确；未托管文件、坏类型、缺库、多库、hash 冲突或注入提交失败不损坏原安装。
真实双进程验证相同输出、相同项目不同输出互斥，并验证取消后不提前放锁。

实施记录：新增 `GdccExtensionDescriptor`（有序读-改-写模型 + `ConfigFile.save` 版式序列化
+ `property_name_encode` 键编码）与 `GodotProjectPublisher`（prepare/publish 两阶段：锁内
预校验 + 描述文件快照；库经同目录暂存后以 `createLink` 免覆盖安装，同名文件仅在非符号链接
且 SHA-256 全量一致时才采纳；受管 `res://bin/` 路径逐组件拒绝符号链接与 `..` 逃逸，并在
提交时复核（防编译期布局被改写）；`platform.arch` 规范键、旧三段式 mode 键迁移与歧义拒绝、
feature-tag 遮蔽键拒绝、`gdcc_libraries` 所有权维护、`validate_filename` 库名与 hash16、
同目录临时文件 + 单次原子替换，整个"复核 + 提交"在 AccessDenied 重试内逐次重验）。
`GodotConfigFile` 增补 quoted-key 解码；`TargetPlatform` 暴露 Godot platform/architecture
feature 名供 `GdextensionMetadataFile` 与发布共用；`FileLockGuard` 用 `FileChannel.tryLock`
实现跨进程锁并以 JVM 内注册表防止同进程二次获取削弱 POSIX 锁；`GdccCommand` 按"先项目锁
后输出锁"在 module 创建前取锁并持有至发布结束，取消时等待任务终态再放锁，发布期中断按
130 处理且不提交。覆盖测试：`GodotProjectPublisherTest`、`FileLockGuardTest`、
`GdccCommandProjectIntegrationTest`（含真实双进程互斥、取消持锁与发布中断）、
`FileUtilTest`、`GdccCommandInputTest`。

### 第四步：端到端与文档收口

临时项目包含两个 `.gd3`、一个显式 `.gd` 和一个未指定 `.gd`。经真实 launcher 编译，
核实产品实际生成的 `bin/gdcc.gdextension` 与库；再由隔离 Godot editor 导入/扫描后运行
解释型 driver，验证 ClassDB 注册、跨文件调用和来源元数据。不能由测试手工复制库或重写
描述文件来替代产品发布；唯一 pass marker、退出码、错误输出和超时都要检查。

验收：真实项目 launcher 用例与 Godot runtime 用例均通过；缺工具只能记跳过，不能宣布
端到端完成。另用 Godot `ConfigFile` 验证配置/描述文件往返和 addon 格式兼容。
最后依据实现更新 CLI help、`cli_implementation.md` 和双语 README，不提前修改事实源。

## 6. 测试安排

拟新增：`GodotProjectInputTest`、`GodotProjectSourceCollectorTest`、
`GodotProjectPublisherTest`、`GdccCommandProjectIntegrationTest`；扩展现有
`LauncherSmokeTest`、`GdccCommandTaskTest` 和 `FileUtilTest`。

已随第一、二步落地：`GodotConfigFileTest`、`GodotProjectInputTest`、
`GodotProjectSourceCollectorTest`、`GdccCommandProjectIntegrationTest`。
已随第三步落地：`GodotProjectPublisherTest`、`FileLockGuardTest`、`FileUtilTest` 增补，
`GdccCommandProjectIntegrationTest`/`GdccCommandInputTest` 增补发布与锁用例。
Linux 使用定向测试包装脚本，其余新增类在实施前尚不存在：

```bash
script/run-gradle-targeted-tests.sh --tests GodotProjectInputTest,GodotProjectSourceCollectorTest
script/run-gradle-targeted-tests.sh --tests GodotProjectPublisherTest,GdccCommandTaskTest
script/run-gradle-targeted-tests.sh --tests GdccCommandProjectIntegrationTest,LauncherSmokeTest
script/run-gradle-targeted-tests.sh --tests GdccCommandInputTest,GdccCommandOptionTest,GdccCommandMappingTest,MainEntrypointTest,ApiCompileDiagnosticsTest
```

按阶段运行，不做 clean 或全量测试。Java 与注释遵守 AGENTS：Java 25、既有代码风格、
自足的必要注释，不引用本计划或实施阶段；不修改构建配置。

## 7. 保留的协作边界

`bin/gdcc.gdextension` 维护已纳入本期，不再作为待确认事项。当前设计支持与 addon
**顺序维护同一格式**，不支持双方同时 Build：现有 addon 不参与 CLI 文件锁，同时读取旧
描述文件再分别写回会丢失另一方更新。

本期运行 CLI 发布时需避免其他发布者同时写入。若要求与活动编辑器并发 Build、自动通知
扫描或即时热重载，需要修改双方的发布协作协议，应另行确认；不以单方面 Java 锁冒充解决。

第一、二步产品代码与测试已实施；`bin/` 发布、锁与端到端验证仍未实现，
不沿用旧版本计划的审阅结论。
