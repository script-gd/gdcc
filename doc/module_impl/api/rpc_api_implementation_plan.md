# API RPC 实施计划

> 本文档记录 `dev.superice.gdcc.api` 包下“供 RPC 调用的远程编译器 API”实施方案。当前仓库尚未落地 RPC API、模块级虚拟文件系统或虚拟链接能力；本文档给出面向现有 frontend/lowering/backend 主链的具体实施步骤、代码落点与验收细则。

## 文档状态

- 状态：计划维护中
- 更新时间：2026-04-22
- 目标目录：
  - `src/main/java/dev/superice/gdcc/api/**`
  - `src/test/java/dev/superice/gdcc/api/**`
- 直接事实源：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/runtime_name_mapping_implementation.md`
  - `doc/module_impl/frontend/gdcc_facing_class_name_contract.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/gdcc_c_backend.md`
  - `src/main/java/dev/superice/gdcc/frontend/parse/FrontendModule.java`
  - `src/main/java/dev/superice/gdcc/frontend/parse/FrontendSourceUnit.java`
  - `src/main/java/dev/superice/gdcc/frontend/parse/GdScriptParserService.java`
  - `src/main/java/dev/superice/gdcc/frontend/sema/FrontendModuleSkeleton.java`
  - `src/main/java/dev/superice/gdcc/frontend/FrontendClassNameContract.java`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/FrontendLoweringPassManager.java`
  - `src/main/java/dev/superice/gdcc/backend/CodegenContext.java`
  - `src/main/java/dev/superice/gdcc/backend/GeneratedFile.java`
  - `src/main/java/dev/superice/gdcc/backend/c/build/CProjectBuilder.java`
  - `src/main/java/dev/superice/gdcc/backend/c/build/CProjectInfo.java`
  - `src/main/java/dev/superice/gdcc/backend/c/build/CBuildResult.java`
  - `src/test/java/dev/superice/gdcc/backend/c/build/FrontendLoweringToCProjectBuilderIntegrationTest.java`
  - `src/test/java/dev/superice/gdcc/test_suite/GdScriptUnitTestCompileRunner.java`
- 明确非目标：
  - 不在本轮 API 实施中直接选型或嵌入具体网络协议栈，例如 gRPC、JSON-RPC、HTTP server
  - 不修改 frontend/backend 既有编译语义，只在其外层增加远程可调用的状态管理与编排层
  - 不重做现有 `ClassRegistry`、`FrontendModule`、`FrontendLoweringPassManager`、`CProjectBuilder` 的主责任
  - 不把虚拟文件系统做成通用操作系统抽象；它只服务当前 API 模块的远程编译场景

---

## 1. 调研结论

### 1.1 当前仓库已经具备的事实

- `dev.superice.gdcc.api.API` 目前是空壳 utility class，还没有任何远程可调用能力。
- parser 已支持“逻辑路径 + 源码字符串”输入：
  - `GdScriptParserService.parseUnit(Path sourcePath, String source, DiagnosticManager diagnostics)` 直接接收源码字符串，不要求源码先落盘。
  - `FrontendSourceUnit` 只持有 `Path path`、`String source` 和 AST。
- frontend 的统一模块入口已经稳定为 `FrontendModule`：
  - 模块名、源码单元列表、顶层类名映射在构造时冻结。
  - `topLevelCanonicalNameMap` 在 public boundary 就会校验 blank 和保留序列 `__sub__`。
- frontend -> lowering -> LIR 的公共编译入口已经稳定：
  - `FrontendLoweringPassManager.lower(module, classRegistry, diagnostics)`。
- backend 的 C 产物构建链已经稳定：
  - `CCodegen.prepare(new CodegenContext(projectInfo, classRegistry), loweredModule)`
  - `new CProjectBuilder().buildProject(projectInfo, codegen)`
- `CProjectBuilder.buildProject(...)` 的行为已经非常明确：
  - 生成 `entry.c`、`entry.h`、`engine_method_binds.h`
  - 将生成文件写到 `projectInfo.projectPath()`
  - 编译后返回 `CBuildResult(success, buildLog, artifacts)`，其中 `artifacts` 是本地磁盘上的真实路径
- 集成测试已经证明以下链路成立：
  - 纯内存源码 -> `FrontendModule`
  - 模块级类名映射 -> lowering/runtime class name
  - lowering -> 本地项目目录构建 -> 本地动态库产物

### 1.2 当前仓库明确缺失的能力

- 没有模块会话或模块仓库。
- 没有虚拟文件系统。
- 没有虚拟链接或外部本地磁盘链接建模。
- 没有面向 RPC 的稳定请求/响应数据模型。
- 没有“编译后将本地产物挂回模块虚拟文件系统”的回写流程。

### 1.3 对本次设计最关键的现有边界

- API 层不需要为 frontend 引入“必须先落地源文件”的过渡层。
  - 解析阶段可以直接从虚拟文件内容构造 `FrontendSourceUnit`。
- API 层必须保留现有 frontend 类名映射合同。
  - 模块上的类名映射最终仍要直通 `FrontendModule.topLevelCanonicalNameMap`。
  - `__sub__` 保留序列约束仍由 `FrontendClassNameContract` 和 `FrontendModule` 守住。
- API 层不能试图把 backend 产物“虚拟化到不存在的物理目录”。
  - 现有 `CProjectBuilder` 真实依赖 `projectPath` 落盘生成和本地编译。
  - 因此“编译产物链接到虚拟文件系统”必须建模为“VFS link 指向本地磁盘路径”，而不是让 backend 直接写进 VFS。

---

## 2. 目标能力与总体设计

### 2.1 本轮 API 需要覆盖的能力

- 构造模块。
- 在模块的虚拟文件系统中增删改查文件和目录。
- 支持虚拟链接。
- 设置模块编译参数。
- 设置顶层类名映射。
- 执行编译。
- 将本地磁盘上的编译输出以虚拟链接形式挂回模块虚拟文件系统。

### 2.2 建议的公共 API 形状

本轮建议继续保留 `dev.superice.gdcc.api.API` 作为唯一 public facade，不额外引入 interface 层。RPC 层直接调用这个 facade；协议编解码、网络监听、权限控制放在仓库外层或后续单独立项。

建议 public API 至少覆盖以下操作：

- `createModule(...)`
- `deleteModule(...)`
- `getModule(...)`
- `listModules()`
- `putFile(...)`
- `readFile(...)`
- `deletePath(...)`
- `createDirectory(...)`
- `listDirectory(...)`
- `createLink(...)`
- `readEntry(...)`
- `setCompileOptions(...)`
- `getCompileOptions(...)`
- `setTopLevelCanonicalNameMap(...)`
- `getTopLevelCanonicalNameMap(...)`
- `compile(...)`
- `getLastCompileResult(...)`

### 2.3 建议的内部状态模型

不要在 `api` 包里引入“一套只有一个实现的 service interface”。建议使用一个 public facade 加少量 package-private 实现类。

建议内部状态最小拆分为：

- `API`
  - public facade，负责参数校验、模块路由和返回 DTO。
- `ModuleState`
  - 一个模块的全部可变状态，包括模块名、虚拟文件树、编译参数、类名映射、最近一次编译结果。
- `VfsNode`
  - package-private sealed hierarchy。
  - 三个实现足够：
    - `DirectoryNode`
    - `FileNode`
    - `LinkNode`
- `CompileOptions`
  - 模块的编译参数快照。
- `CompileResult`
  - 最近一次编译的输入快照、诊断、输出链接和底层构建结果摘要。

这组类型足以覆盖当前需求，不需要先引入 repository / gateway / adapter / driver 等抽象层。

### 2.4 路径模型

建议把 API 暴露给 RPC 的路径固定为“模块内 POSIX 风格虚拟路径字符串”：

- 根目录固定为 `/`
- 目录分隔符固定为 `/`
- 不允许 `.`、`..` 语义泄漏到存储层
- 不允许空段或尾随多段重复斜杠形成多义路径

原因：

- 远程调用面不应暴露宿主机是 Windows 还是其他系统。
- parser/backend 需要的 `Path` 只是内部适配结果，不应反向定义 RPC 路径语义。

### 2.5 链接模型

建议从第一版就统一支持两类链接：

- `VIRTUAL`
  - 指向模块 VFS 内的另一个虚拟路径
- `LOCAL`
  - 指向宿主机本地磁盘路径

这样可以同时覆盖：

- 模块内部源码/目录复用
- 编译后把本地磁盘产物挂回模块 VFS

对链接的约束建议如下：

- link 自身是 first-class entry，读取元数据时默认返回“链接信息”，不是偷偷解引用后的目标快照
- 在“编译收集源码文件”场景中，允许显式解引用链接
- 编译后自动挂回的产物链接统一使用 `LOCAL` link

### 2.6 编译管线在 API 层的建议责任

API 层的 `compile(...)` 不应重写编译器主线，只应编排现有能力：

1. 冻结模块快照。
2. 从 VFS 递归收集参与编译的源码文件。
3. 将每个源码文件转成 `FrontendSourceUnit`。
4. 用模块名和顶层类名映射构造 `FrontendModule`。
5. 根据 `CompileOptions` 初始化 `ClassRegistry`、`CodegenContext`、`CProjectInfo`。
6. 调用 `FrontendLoweringPassManager.lower(...)`。
7. 调用 `CCodegen.prepare(...)` 与 `CProjectBuilder.buildProject(...)`。
8. 将生成文件和最终本地 artifact 以 `LOCAL` link 形式挂回模块 VFS。
9. 记录本次编译结果快照供 RPC 查询。

---

## 3. 目录与类落点建议

建议首轮实现尽量少文件，保持 `api` 包内聚。

建议文件布局：

- `src/main/java/dev/superice/gdcc/api/API.java`
  - public facade
- `src/main/java/dev/superice/gdcc/api/CompileOptions.java`
  - public record，表达 RPC 可设置的编译参数
- `src/main/java/dev/superice/gdcc/api/CompileResult.java`
  - public record，表达 RPC 可观察的编译结果
- `src/main/java/dev/superice/gdcc/api/ModuleSnapshot.java`
  - public record，表达模块当前元数据和 VFS 概览
- `src/main/java/dev/superice/gdcc/api/ModuleState.java`
  - package-private final class
- `src/main/java/dev/superice/gdcc/api/VfsNode.java`
  - package-private sealed interface
- `src/main/java/dev/superice/gdcc/api/VirtualPath.java`
  - package-private value helper，用于统一路径规范化

如果实现过程中发现 `CompileResult` / `ModuleSnapshot` 结构很小，也可以把它们收回 `API` 的嵌套 record，减少 public file 数量。不要为了“看起来分层”把简单 DTO 强行拆成更多文件。

---

## 4. 分步骤实施

### 4.1 第一步：建立模块仓库与公共 facade

当前状态（2026-04-22）：

- 已完成：
  - `API` 已改为可实例化 facade，并在内存中维护模块表。
  - 模块生命周期首批 public surface 已落地：`createModule(...)`、`getModule(...)`、`listModules()`、`deleteModule(...)`。
  - 默认模块快照已落地并对外冻结：
    - 默认 `CompileOptions`
    - 空顶层类名映射
    - `hasLastCompileResult == false`
    - `rootEntryCount == 0`
  - 模块重复创建与缺失查询/删除已收口到 API 专用异常。
  - targeted 单元测试与编译校验已完成：
    - IntelliJ incremental build 成功
    - `ApiModuleLifecycleTest` 已通过

目标：

- 让 `API` 真正承载模块生命周期。
- 为后续 VFS、参数设置和编译提供稳定的模块句柄。

建议实施内容：

- 将当前空壳 `API` 改为可实例化 facade，不再维持“永远不能 new 的 utility class”。
- 在 `API` 内部维护 `ConcurrentHashMap<String, ModuleState>`。
- 模块标识建议由调用方显式提供 `moduleId`，不要先引入生成器和二次索引。
- `ModuleState` 至少包含：
  - `moduleId`
  - `moduleName`
  - `DirectoryNode root`
  - `CompileOptions compileOptions`
  - `Map<String, String> topLevelCanonicalNameMap`
  - `CompileResult lastCompileResult`
- `createModule(...)` 只做创建和默认状态初始化，不隐式创建源码文件。
- `deleteModule(...)` 完全删除模块状态，不保留兼容别名或软删除层。

验收细则：

- happy path：
  - 可以创建、查询、列出、删除模块
  - 新模块默认拥有空根目录、空类名映射、默认编译参数、无最近编译结果
- negative path：
  - 重复 `moduleId` 创建必须失败
  - 删除不存在模块必须返回清晰错误
  - `moduleName`、`moduleId` 的 blank/null 公共边界校验明确

建议测试：

- `ApiModuleLifecycleTest`

### 4.2 第二步：落地虚拟文件系统与路径规范化

当前状态（2026-04-22）：

- 已完成：
  - `VirtualPath` 已落地，统一规范模块内 POSIX 风格绝对路径：
    - 根路径固定为 `/`
    - 拒绝 blank path、相对路径、反斜杠分隔符、`.` / `..` 段、重复分隔段
  - `ModuleState` 已接入最小 VFS 节点模型：
    - `DirectoryNode` 使用有序 map 保证目录列举稳定排序
    - `FileNode` 持有源码文本、UTF-8 字节数与更新时间戳
    - `ModuleSnapshot.rootEntryCount` 现由根目录直系条目数真实驱动
  - 第 2 步 public surface 已落地：
    - `createDirectory(...)`
    - `putFile(...)`
    - `readFile(...)`
    - `deletePath(..., recursive)`
    - `listDirectory(...)`
    - `readEntry(...)`
  - 关键行为已在实现层冻结：
    - `putFile(...)` 自动创建缺失父目录
    - `createDirectory(...)` 对已存在目录保持幂等，并补齐缺失祖先目录
    - `putFile("/")` 与 `deletePath("/")` 在 API boundary 明确失败，不允许把模块根目录重解释为普通文件或可删目录
  - 第 2 步首批异常已落地：
    - `ApiPathNotFoundException`
    - `ApiEntryTypeMismatchException`
    - `ApiDirectoryNotEmptyException`
  - targeted tests 与编译校验已完成：
    - IntelliJ incremental build 成功
    - `ApiVirtualPathTest` 已通过（3 tests）
    - `ApiVirtualFileSystemTest` 已通过（4 tests）
    - `ApiModuleLifecycleTest` 已通过（6 tests）

目标：

- 支持远程增删改查文件与目录。
- 建立统一的虚拟路径语义。

建议实施内容：

- 引入 `VirtualPath` 统一规范化：
  - 输入字符串 -> 规范段列表
  - 拒绝空路径、`.`、`..`、重复分隔段
- `DirectoryNode` 使用有序 map 保存子节点，便于 RPC 列表结果稳定排序。
- `FileNode` 至少持有：
  - 文本内容
  - 可选字节数/更新时间戳
- `DirectoryNode` 不需要和 `FileNode` 共用“内容接口”；保持简单。
- `API` 暴露：
  - `createDirectory`
  - `putFile`
  - `readFile`
  - `deletePath`
  - `listDirectory`
  - `readEntry`
- `putFile(...)` 建议自动创建缺失父目录。
- `deletePath(...)` 允许删文件、空目录、非空目录，但应通过明确参数控制是否递归，避免 RPC 调用歧义。

验收细则：

- happy path：
  - 可以写入 `/src/main.gd` 这类带目录的文件路径
  - 列目录结果稳定、顺序可预测
  - 删除目录时递归与非递归行为清晰可验证
- negative path：
  - 把文件路径当目录继续写子项必须失败
  - 读取不存在路径必须失败
  - 非递归删除非空目录必须失败
  - 非法虚拟路径必须在 API boundary 失败

建议测试：

- `ApiVirtualFileSystemTest`
- `ApiVirtualPathTest`

### 4.3 第三步：补齐虚拟链接与链接解析规则

当前状态（2026-04-22）：  

- 已完成：
  - `VfsNode` 已扩展 `LinkNode`，统一承载 `VIRTUAL` / `LOCAL` 两类链接。
  - `API` 已新增 `createLink(...)` public surface，`ModuleState` 已接入链接创建、覆盖与删除前快照逻辑。
  - `VfsEntrySnapshot` 已扩展 `LinkEntrySnapshot`、`LinkKind`、`BrokenReason`，支持在元数据面稳定暴露链接类型、目标文本与 broken/cycle 状态。
  - 链接解析合同已在实现层冻结：
    - `readEntry(...)` 读取链接自身元数据，不隐式解引用最终链接
    - `readFile(...)` / `listDirectory(...)` 对 `VIRTUAL` 链接显式解引用
    - `LOCAL` 链接只暴露元数据，不触碰宿主机文件系统
    - 中间路径若命中指向目录的 `VIRTUAL` 链接，会按统一规则继续遍历目标目录
    - 通过目录型 `VIRTUAL` 链接执行 `putFile(...)` / `createDirectory(...)` / `deletePath(...)` 时，会稳定落到目标目录，不额外复制节点
  - broken link / cycle 已有清晰异常出口：
    - `ApiBrokenLinkException`
    - `ApiLinkCycleException`
- targeted tests 与编译校验已完成：
  - IntelliJ incremental build 成功
  - `ApiVirtualLinkTest` 已通过（3 tests）
  - `ApiVirtualFileSystemTest` 已通过（4 tests）
  - `ApiVirtualPathTest` 已通过（3 tests）
  - `ApiModuleLifecycleTest` 已通过（6 tests）
- 与后续步骤的接口约束已冻结：
  - 第 5 步源码收集可直接复用当前 `VIRTUAL` 链接解引用与 cycle/broken 检测逻辑
  - 第 6 步编译产物回挂可直接复用当前 `LOCAL` 链接数据模型，无需重新定义 entry 类型

目标：

- 支持模块内虚拟链接。
- 为“编译输出挂回 VFS”预留同一套 link 机制。

建议实施内容：

- `LinkNode` 增加 link kind：
  - `VIRTUAL`
  - `LOCAL`
- `createLink(...)` 明确要求：
  - link path
  - target kind
  - target text
- 提供只读元数据接口：
  - 返回是否链接
  - 返回 link kind
  - 返回目标文本
- 编译前收集源码时，递归解引用 `VIRTUAL` link。
- 编译前若遇到 `LOCAL` link 指向源码文件，本轮建议默认不纳入编译输入，避免把宿主机文件读取权限、编码和变更监控一并引入第一版。
- 需要循环检测：
  - `A -> B -> A`
  - 目录遍历过程中跨层回环

验收细则：

- happy path：
  - `VIRTUAL` link 能指向文件与目录
  - 通过链接读取源码可获得与目标一致的编译输入
  - 目录列举时能区分实体节点和链接节点
- negative path：
  - 虚拟链接循环必须被检测并报告
  - link 目标不存在时，元数据读取可见为 broken link
  - broken `VIRTUAL` link 一旦参与编译输入收集必须阻断本次编译

建议测试：

- `ApiVirtualLinkTest`
- `ApiVirtualLinkCompileInputTest`

### 4.4 第四步：接入编译参数与类名映射配置

目标：

- 让模块具备“可远程配置再编译”的最小状态面。

建议实施内容：

- `CompileOptions` 第一版建议只覆盖现有构建链已稳定支持的字段：
  - `GodotVersion godotVersion`
  - `Path projectPath`
  - `COptimizationLevel optimizationLevel`
  - `TargetPlatform targetPlatform`
  - `boolean strictMode`
  - `String outputMountRoot`
- `outputMountRoot` 用于约定编译后自动挂载链接的位置。
  - 建议默认 `/__build__`
- 顶层类名映射直接保留 `Map<String, String>` 形状，不另造包装层。
- `setTopLevelCanonicalNameMap(...)` 最终应复用 `FrontendModule` / `FrontendClassNameContract` 的既有约束，不要在 API 模块里维护第二套 `__sub__` 校验规则。
- `setCompileOptions(...)` 只替换快照，不做即时编译。

验收细则：

- happy path：
  - 可以更新并读取模块编译参数
  - 可以更新并读取顶层类名映射
  - 映射内容最终能稳定进入 `FrontendModule`
- negative path：
  - blank project path、缺失 target platform 等公共边界错误必须清晰
  - 映射中出现 blank 或 `__sub__` 必须失败
  - 模块删除后旧配置不可残留

建议测试：

- `ApiCompileOptionsTest`
- `ApiCanonicalNameMapTest`

### 4.5 第五步：把 VFS 模块接到现有编译器主线

目标：

- 用现有 parser/lowering/backend 完成一次模块编译。
- 不改写既有编译器相位职责。

建议实施内容：

- 在 `API.compile(moduleId)` 中执行以下固定步骤：
  - 冻结当前模块状态，避免编译中途被 RPC 更新破坏一致性
  - 从 VFS 收集所有参与编译的源码文件
  - 为每个源码文件构造“compiler-facing logical path”
  - 调用 `GdScriptParserService.parseUnit(logicalPath, source, diagnostics)`
  - 构造 `FrontendModule(moduleName, units, topLevelCanonicalNameMap)`
  - 初始化 `ClassRegistry(ExtensionApiLoader.loadVersion(godotVersion))`
  - 调用 `FrontendLoweringPassManager.lower(...)`
  - 若 lowering 返回 `null` 或 diagnostics 有 error，记录失败结果并停止
  - 构造 `CProjectInfo`
  - 调用 `CCodegen.prepare(...)`
  - 调用 `CProjectBuilder.buildProject(...)`
- 编译输入收集建议只纳入 `.gd` 文件。
- 同一模块内源码单元顺序建议按规范化虚拟路径排序，保证编译结果稳定。
- 诊断中的 `sourcePath` 应保留可追溯到虚拟路径的逻辑路径，不要泄漏临时目录路径。

验收细则：

- happy path：
  - VFS 中的多文件模块可成功构造 `FrontendModule`
  - 编译可成功生成 `entry.c`、`entry.h`、`engine_method_binds.h` 和本地动态库
  - 类名映射能体现在 lowering/backend/runtime class name 上
- negative path：
  - parse diagnostics 能回传到 API 结果
  - frontend compile-block diagnostics 能回传到 API 结果
  - broken link、无源码文件、project path 不可写等错误能被区分

建议测试：

- `ApiCompilePipelineTest`
- `ApiCompileDiagnosticsTest`
- `ApiMappedClassCompileTest`

### 4.6 第六步：将本地编译产物挂回虚拟文件系统

目标：

- 让远程调用方在不额外扫描磁盘的情况下，直接从模块 VFS 看到编译输出。

建议实施内容：

- 编译成功后，将以下内容以 `LOCAL` link 形式挂回 `outputMountRoot`：
  - `entry.c`
  - `entry.h`
  - `engine_method_binds.h`
  - `CBuildResult.artifacts()` 中的所有最终本地产物
- 挂载命名建议稳定且可预测：
  - `/__build__/generated/entry.c`
  - `/__build__/generated/entry.h`
  - `/__build__/generated/engine_method_binds.h`
  - `/__build__/artifacts/<artifact-file-name>`
- 每次重新编译时，先清空旧的自动挂载输出目录，再写入新的链接，避免陈旧产物残留。
- `CompileResult` 应明确区分：
  - frontend diagnostics
  - build success / failure
  - build log
  - 输出链接列表
  - 本地 artifact 原始路径列表

验收细则：

- happy path：
  - 编译成功后，VFS 中能读到生成文件和最终 artifact 的链接元数据
  - 重复编译后，旧链接会被新结果替换
- negative path：
  - 编译失败时，不应把半成品本地路径误挂成成功产物
  - 若输出挂载目录被用户手工占成普通文件，API 必须报清晰错误而不是静默覆盖

建议测试：

- `ApiCompileArtifactLinkTest`
- `ApiRecompileArtifactRefreshTest`

### 4.7 第七步：补齐并发约束、回归测试与文档

目标：

- 让该 API 可安全作为 RPC 后端使用。

建议实施内容：

- 模块级并发建议采用“每模块独立锁”：
  - 文件更新与配置更新拿写锁
  - 只读查询拿读锁
  - 编译拿写锁并基于快照执行
- 若不想在第一版引入读写锁，至少先使用模块级 `synchronized` 串行化，保证语义正确，再评估优化。
- 新增 `doc/module_impl/api` 下的事实源文档或把本计划转成事实源时，需同步删除过期计划信息。
- 保持 targeted test 组织，不跑整套项目测试。

验收细则：

- happy path：
  - 同模块串行操作结果稳定
  - 不同模块互不污染
- negative path：
  - 编译与写文件并发冲突时，不会读到半更新状态
  - 删除模块与编译并发冲突时，有清晰失败语义

建议测试：

- `ApiConcurrentMutationTest`
- `ApiMultiModuleIsolationTest`

---

## 5. 建议的默认行为

为了降低首版 RPC API 的歧义，建议直接冻结以下默认行为：

- 默认源码收集根：整个模块 VFS。
- 默认只编译 `.gd` 文件。
- 默认输出挂载根：`/__build__`。
- 默认不把 `LOCAL` link 当作源码输入。
- 默认编译 backend 固定为当前已实现的 C backend。
- 默认 Godot 版本固定由 `CompileOptions.godotVersion` 指定；若未设置，直接使用 `V451`。

这些默认值都可以在后续扩展，但第一版不应为了“将来可能支持更多”而把 API 先做成开放式配置系统。

---

## 6. 关键风险与防漂移约束

### 6.1 不要在 API 层复制 frontend 的类名映射语义

API 层只保存映射并在构造 `FrontendModule` 时传递。真正的 reserved-sequence、caller-side remap、canonical-only downstream contract 仍由 frontend 现有实现负责。

### 6.2 不要把虚拟文件系统误做成“源码必须先落盘”的中间仓库

现有 parser 已支持内存源码；若 API 层额外维护一套临时源码镜像目录，只会引入诊断路径漂移和同步成本。

### 6.3 不要把本地磁盘产物复制回 VFS

当前需求只要求“链接到虚拟文件系统”。复制会引入大文件传输、同步失效和本地路径权限问题。第一版应该只挂 `LOCAL` link。

### 6.4 不要在第一版引入协议层耦合

`api` 包应该是 RPC-friendly，而不是 RPC-framework-bound。不要让 `API` 直接依赖 HTTP request、JSON encoder 或 socket session。

### 6.5 不要过度抽象

这一轮目标是把现有编译器变成可远程操作的状态机，不是引入第二套平台架构。一个 public facade 加少量 package-private 类型已经够用。

---

## 7. 最小验收矩阵

完整实施后，至少要用 targeted tests 锚定以下场景：

1. 模块生命周期：
   - 创建、查询、删除、重复创建失败
2. VFS 文件与目录：
   - 带目录写文件、列目录、递归删除、非法路径失败
3. 虚拟链接：
   - 文件链接、目录链接、broken link、循环链接
4. 编译参数：
   - GodotVersion、projectPath、优化级别、strictMode、输出挂载根
5. 类名映射：
   - 空映射
   - 合法映射
   - `__sub__` 违规映射
6. 编译成功链路：
   - 单文件模块
   - 多文件模块
   - 带类名映射模块
7. 编译失败链路：
   - parse error
   - compile-check error
   - broken source link
   - 不可写项目目录
8. 输出挂载：
   - 生成文件链接
   - 本地动态库链接
   - 重新编译刷新旧链接
9. 并发与隔离：
   - 同模块写入/编译互斥
   - 多模块互不污染

---

## 8. 分阶段提交建议

为了降低 diff 风险，建议按以下提交粒度推进：

1. `api` facade + `ModuleState` + 基础模块生命周期
2. VFS 路径、目录、文件 CRUD
3. VFS link 与 link 解析
4. 编译参数与类名映射配置
5. compile orchestration 接入现有 frontend/lowering/backend
6. 编译输出链接回挂
7. 并发、回归测试、文档收尾

每一步都应保持：

- 有独立测试
- 不破坏现有 frontend/backend 测试
- 不引入半成品 public API

---

## 9. 建议的阶段验收命令

实施时建议只跑新增或直接受影响的 targeted tests，例如：

```powershell
.\gradlew.bat test --tests ApiModuleLifecycleTest --no-daemon --info --console=plain
.\gradlew.bat test --tests ApiVirtualFileSystemTest --no-daemon --info --console=plain
.\gradlew.bat test --tests ApiVirtualLinkTest --no-daemon --info --console=plain
.\gradlew.bat test --tests ApiCompilePipelineTest --no-daemon --info --console=plain
.\gradlew.bat test --tests ApiCompileArtifactLinkTest --no-daemon --info --console=plain
```

若需要一轮阶段性联调，再补跑：

```powershell
.\gradlew.bat test --tests FrontendLoweringToCProjectBuilderIntegrationTest --no-daemon --info --console=plain
```

---

## 10. 最终实施完成的定义

当且仅当以下条件同时满足时，才视为本计划完成：

- `api` 包提供稳定的模块生命周期、VFS CRUD、链接、参数设置、类名映射设置和编译入口
- 编译路径复用现有 `FrontendModule`、`FrontendLoweringPassManager`、`CCodegen`、`CProjectBuilder`
- 编译成功后，本地生成文件与最终 artifact 会以 `LOCAL` link 形式稳定挂回模块 VFS
- 失败场景能返回清晰的 diagnostics / buildLog / broken link 信息
- 新增 targeted tests 覆盖模块管理、VFS、链接、编译成功与失败、输出挂载、并发隔离
- `doc/module_impl/api` 下有与实现一致的长期文档
