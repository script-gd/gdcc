# Frontend LSP 基础设施实施计划

> 本文档是"编译前端作为 LSP 实现基础"改造的总计划与验收依据。本轮不实现 LSP 服务端本身，
> 只扫清前端与 API 层的障碍，使未来 LSP 服务端（或编辑器插件直连）能够消费语义快照、
> 光标查询与补全候选。

## 文档状态

- 状态：实施中（Phase 1-5 已实施并经评审修复；Phase 6 收尾待办；计划本身已经两轮评审修订）
- 创建时间：2026-09-28
- 适用范围：
    - `src/main/java/gd/script/gdcc/frontend/**`
    - `src/main/java/gd/script/gdcc/api/**`
    - `src/main/java/gd/script/gdcc/rpc/**`（方法名与请求 schema 不变；响应形状与并发行为合同变化）
    - 外部依赖 `com.github.SuperIceCN:gdparser`（独立仓库，随本计划发布 0.6.0）
- 关联文档：
    - `doc/module_impl/common_rules.md`
    - `doc/module_impl/frontend/frontend_rules.md`
    - `doc/module_impl/frontend/diagnostic_manager.md`
    - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
    - `doc/module_impl/frontend/scope_analyzer_implementation.md`
    - `doc/module_impl/api/rpc_api_implementation.md`
    - `doc/module_impl/api/json_rpc_service_implementation.md`

---

## 1. 背景与目标

### 1.1 现状问题

1. **解析错误使整模块失去语义结果。**
   parser 已能容错返回部分 AST 与带范围诊断（`GdScriptParserService.parseUnit`，
   `src/main/java/gd/script/gdcc/frontend/parse/GdScriptParserService.java:35`），
   但 `AnalysisRunner` 汇总解析诊断后只要存在一条 ERROR 就跳过整个模块的语义分析
   （`src/main/java/gd/script/gdcc/api/AnalysisRunner.java:79-90`）。
2. **没有 LSP 可消费的语义快照与光标查询接口。**
   底层语义事实按 AST 对象身份索引（`FrontendAstSideTable` 包装 `IdentityHashMap`，
   `src/main/java/gd/script/gdcc/frontend/sema/FrontendAstSideTable.java:15-25`），
   `FrontendSemanticAnalyzer.analyze(...)` 返回完整 `FrontendAnalysisData`，
   但 `AnalysisRunner` 默认路径直接忽略该返回值（`AnalysisRunner.java:113-124`），
   公开 `AnalysisResult` 只保留诊断等概要（`src/main/java/gd/script/gdcc/api/AnalysisResult.java:22-30`）。
3. **分析在模块门闩上串行。**
   `API.analyze(...)` 在 `ManagedModule.runExclusive` 内执行完整分析
   （`src/main/java/gd/script/gdcc/api/API.java:306-314`、`API.java:634-641`），
   分析期间同模块 `vfs.putFile` 等写操作全部阻塞；分析也排在已预约编译之后。
4. **无法处理光标处未完成语法与成员候选枚举。**
   gdparser 0.5.5 只暴露整源字符串解析入口，没有补全上下文 API；
   CST 的 `ERROR`/`MISSING` 结构只映射为诊断，不映射为可识别的 AST 错误节点
   （gdparser `CstToAstMapper`，`requireField` 缺字段时以原节点猜测代替）。

### 1.2 目标

1. 解析存在错误时，语义分析仍对**未受损子树**（含受损文件内的健康子树）正常运行并发布事实。
2. API 层按模块保留**不可变语义快照**（AST + `FrontendAnalysisData` + 诊断 + 源文本 + 版本号），
   并提供进程内 Java 查询接口：按光标位置查符号定义、用法、类型与成员候选。
3. 分析基于 VFS 冻结快照在模块门闩外执行；同模块分析期间 `putFile` 等写操作不被阻塞；
   同模块多个分析可并行，结果按版本单调发布；分析不再等待已预约/进行中的编译。
4. gdparser 新增错误节点映射、未完成成员访问的部分链保留与补全上下文提取能力，
   GDCC 基于此实现成员候选枚举。
5. 编辑器插件可通过 JSON-RPC 极其频繁地 `vfs.putFile`；本计划不改变现有 RPC 方法名与请求
   schema（查询接口本轮只做进程内 Java API，RPC 查询方法留待 LSP 服务端迭代）。

### 1.3 非目标

- 不实现 LSP 协议服务端（textDocument/didOpen、publishDiagnostics 等）。
- 不在 JSON-RPC 方法面新增语义查询方法。
- 不改变 compile 任务的门闩语义：编译仍独占模块门闩全程；编译期间 `putFile` 仍阻塞
  （本轮只保证**分析**期间写不阻塞）。编译与分析互不等待，各读各的冻结快照。
- 不做增量语义分析（每次分析仍全量重跑；增量化是后续优化）。
- 不扩展 `FrontendBinding` 的 read/write/call 用法分类（`frontend_rules.md` §113-114
  的既定扩展任务，不在本轮）。
- 不把 AST 对象图或身份键直接暴露到 RPC wire format。

---

## 2. 总体设计

### 2.1 语义快照 `ModuleAnalysisSnapshot`

新增 API 层不可变对象（放在新子包 `gd.script.gdcc.api.analysis`）：

- `moduleGeneration` + `snapshotVersion`（均为 long，见 §2.3；代际标识模块删除重建，
  版本标识同代内的内容代）、`moduleId`、`godotVersion`、`topLevelCanonicalNameMap`。
- 源视图：每源单元的 logicalPath / displayPath / 源文本（直接引用冻结的 `SourceSnapshot`，
  不复制字符串）与**源单元级解析失败标记**（见 §2.2.5）。
- `FrontendModule`（本轮分析的 AST 代）与 `FrontendAnalysisData`（语义 side table 全集）。
- 本次分析私有的 `ClassRegistry`（每次分析经 `ExtensionApiLoader.loadVersion` 新建，
  `AnalysisRunner.java:92-107`；skeleton 期间会 `addGdccClass`，发布后即冻结，禁止再变更）。
- 最终 `DiagnosticSnapshot`（含 parse + sema 全部诊断，已 remap 到 displayPath；
  **不含** compile-only `sema.compile_check`，快照永远来自共享 `analyze(...)` 路径，见 §2.2.6）。

不可变性合同（评审修订，原"发布即只读"表述不足以成立）：

- `FrontendAnalysisData` 的 getter 返回活表（`FrontendAstSideTable` 是可变 `IdentityHashMap`），
  `ClassRegistry` 也有公开修改方法。因此快照**不直接暴露**这两个原对象：
  快照发布时构造只读查询视图/冻结投影，查询服务只能通过该视图读。
- 发布后禁止调用任何 `update*` / `applyPatch` / registry 修改方法；以测试锁死
  （尝试修改已发布快照内容应失败或不影响查询结果）。
- usages 反向索引等惰性结构存放在快照自有的线程安全 memo（`ConcurrentHashMap` + 单次计算），
  **不写回** `FrontendAnalysisData`。
- AST 对象图同样纳入不可变合同：gdparser 0.6.0 必须在 AST record 构造时冻结集合
  （`List.copyOf`，见 §3 第 6 项）；若该保证不成立，快照只暴露受控只读节点视图，
  不暴露可修改的 AST 图。
- 快照内所有 side table 的键都属于**同一代 AST**；查询只允许在快照内部闭合，
  不得把快照 A 的 AST 节点拿去查快照 B 的表。
- 每模块最多保留一个最新快照（`AtomicReference`），按 §2.3 规则原子替换；
  旧快照由调用方引用自然 GC，不做主动失效。

### 2.2 容错语义分析（子树级打捞）

核心思路：把 parser 产生的错误结构接入 frontend 已有的 "diagnostic + skip subtree"
恢复合同（`frontend_rules.md` 恢复约定），并补齐该合同目前覆盖不到的环节。
评审确认的现状缺口：`skippedSubtreeRoots()` 当前只是 skeleton→scope 协议，
body 阶段不消费该表；`FrontendScopeAnalyzer` 的若干专用 handler
（`IfStatement`/`ForStatement`/`MatchStatement` 等）不检查 skipped 根；
skeleton 只扫类成员列表，不走进函数体。因此必须按下述顺序改造。

#### 2.2.1 gdparser 侧两类错误形态（详见 §3）

- **确缺成员名的成员访问**（`obj.`，`.` 后无任何标识符）：映射为**正常
  AttributeExpression 链 + 缺失末步标记**（错误仅作为 `parse.lowering` 诊断存在），
  不产错误节点。这样 receiver 前缀仍能被正常绑定与定型，补全可读其 `expressionTypes`。
  注意 `obj.par` 是合法完整语法，普通解析没有光标信息，**不得**为它产缺失标记或诊断；
  "正在输入 `par`"由带光标偏移的补全上下文 API 判定（§3 第 4 项）。
- **其余 `ERROR`/`MISSING` 结构**：映射为专用错误节点（`ErrorStatement`/`ErrorExpression`），
  携带 range 与 issue kind。既有 `UnknownStatement`/`UnknownExpression` 若保留，
  打捞标注一视同仁。

#### 2.2.2 错误子树标注（scope 之前的全 AST 独立步骤）

在 skeleton 之后、scope 之前新增独立标注步骤（遍历整棵 AST，含函数体、控制流、
match、lambda、参数默认值 island）：

- 错误节点向上提升到**最小 statement 或 declaration 根**，写入 `skippedSubtreeRoots()`。
- 只含错误表达式的合法语句（`var x = <err>`、`if <err>:`）同样提升到该语句根。
- 类成员位置的错误节点走与 enum 拒绝相同的 `markSkippedSubtreeRoots` 路径，
  不再落入 skeleton 的空 `default`。
- 不新发诊断（parser 已发 `parse.lowering`），保持诊断单一 owner。

#### 2.2.3 scope 与 body 消费 skipped 合同

- `FrontendScopeAnalyzer`：**任何** handler（含 `IfStatement`/`WhileStatement`/
  `ForStatement`/`MatchStatement` 等专用 handler）进入节点前先检查 skipped 根，
  命中即 `SKIP_CHILDREN`，不发布该根及后代的 `scopesByAst`。
- `FrontendSuiteResolver` / statement resolver：解析每条 statement 前先查
  `skippedSubtreeRoots()`，命中即整句跳过，**不得**再 `requireBlockScope` 或深入子结构；
  残缺语句若未被跳过而深入，会触发结构性 fail-fast 并因 patch 非原子（R14）拖垮整次分析。
- expression island 入口（参数默认值 sweep、属性初始化器根）不经过 suite 逐句解析，
  必须各自在进入表达式前消费 skipped 根：命中时跳过的是**默认值表达式或该属性声明**，
  而不是整个 callable——不得把 `Parameter` / `FunctionDeclaration` 整体标为 skipped 根，
  否则函数 body 失去 scope 会把整次分析拖成结构性失败。
- 这意味着 body 阶段正式成为该表的消费者：属于恢复合同变更，必须同步
  `frontend_rules.md`、`diagnostic_manager.md`、`scope_analyzer_implementation.md` 与
  `FrontendBodyOwnerProcedures` 相关注释。

#### 2.2.4 analyzer 防御性加固

- 对"假设 well-formed AST"的入口做防御性改造：遇到错误节点/缺失子结构按跳过处理，
  不抛异常；guard rail 异常仅保留给 programmer error / 协议不变量破坏。
- 语义入口把未预期异常视为整次分析失败（`INTERNAL_FAILED`），**绝不**把半提交的
  `FrontendAnalysisData` 放进快照（R14：patch transaction 非原子）。

#### 2.2.5 `parse.internal` 源单元的显式失败状态

parser 异常路径返回空 `SourceFile`（`GdScriptParserService.java:60-76`），但 skeleton
会为**每个**源单元合成顶层类头——空 AST 不会天然退出语义分析，反而会注册一个虚构的
空脚本类，污染跨文件引用。因此：

- `FrontendSourceUnit`（或 `FrontendModule`）携带显式的"本单元解析失败"状态；
  skeleton 输入排除失败单元，保留其 `parse.internal` 诊断与源视图。
- 其他文件引用失败单元的类时走正常未解析诊断，不得命中虚构类声明。
- 查询层对失败单元一律返回空结果，不抛 NPE（其诊断 range 允许为 null）。

#### 2.2.6 与 lowering 路径的分工

- `AnalysisRunner` 删除 `parseDiagnostics.hasErrors()` 短路（`AnalysisRunner.java:79-90`），
  无条件运行共享 `FrontendSemanticAnalyzer.analyze(...)`，其结果即快照内容。
- 解析诊断含 ERROR 且 `includeLowering=true`：**只**跑共享 `analyze(...)`，
  不调用 `analyzeForCompile` / `FrontendLoweringPassManager.lower`，
  `loweringStatus=FAILED`，结果诊断中不得出现 `sema.compile_check`。
- 无解析错误且 `includeLowering=true`：快照仍取自共享 `analyze(...)`；
  lowering 验证用**独立新建的 `ClassRegistry` 与独立的 `DiagnosticManager`** 另跑
  `lower(...)`——`lower` 内部 `analyzeForCompile` 会重跑 skeleton，
  `ClassRegistry.addGdccClass` 的替换语义会改写共享 registry 并使快照事实的
  对象身份失配，因此必须与快照那一代完全隔离（opt-in 验证模式接受第二次
  语义分析的开销；诊断口径：`AnalysisResult.diagnostics` 取 lowering 运行口径以保留
  现有 compile 验证语义，快照诊断取共享 `analyze(...)` 口径、不含 compile-only 诊断）。
  lowering manager 在运行前**仅导入本次解析阶段的诊断一次**（无解析错误不等于
  无解析警告，空起家的 manager 会丢失 `parse.lowering` WARNING）；
  不得导入共享语义阶段的诊断（lower 会重跑语义并重新产生）。
  后续若性能敏感，再改为从 lowering context 取回同次 `FrontendAnalysisData`。
- 共享 `analyze(...)` 成功后，lowering 验证的诊断失败或未预期异常只影响
  `loweringStatus`（记 `FAILED`）与结果诊断，**不作废**已稳定快照的发布；
  实施时确认 `FrontendLoweringPassManager.lower` 把异常包进诊断还是抛出，
  抛出则由 runner 捕获并按本规则收口。

### 2.3 VFS 内容版本与并发模型

#### 2.3.1 `contentVersion`

`ModuleState` 新增单调递增 `contentVersion`：凡改变冻结输入的变更都递增——
`putFile`、`deletePath`、`createDirectory`、`createLink`、`options.set`、`classMap.set`。
编译产物挂载（`prepareOutputPublication`/`mountCompileOutputs`）只挂非 `.gd` 链接、
不进入分析源集合，**不计入**（写入文档注明；若未来挂载会影响源集合则必须递增）。
`freezeCompileRequest()` 把当前 version 捕获进冻结请求；`AnalysisResult` 同时携带
`moduleGeneration` 与 `snapshotVersion`，对**所有** outcome
（含 `SOURCE_COLLECTION_FAILED`/`INTERNAL_FAILED`）都填冻结时捕获的
（代际, 版本）对，使调用方只持有单次结果也能可靠判断陈旧度。

`moduleGeneration` 由 `API` 在 `createModule` 时从全局单调计数器分配并写入
`ModuleState`，模块删除不回收计数；同 id 重建得到新代际。陈旧度判断必须同时比较
**代际 + 内容版本**——只比版本会在删除重建后误判（新模块版本从低位重新计数）。
快照与 `getModuleContentVersion(moduleId)` 都返回 `(generation, version)` 对。

#### 2.3.2 分析三段式（不再占用模块门闩）

1. **冻结段**：仅在 `ModuleState` 自身的 `synchronized` 边界内执行
   `freezeCompileRequest()`（VFS 写方法同为该锁，冻结原子性由 monitor 保证）。
   **不进入** `ManagedModule.busy`，**不等待** `queuedCompileTaskId`——
   已排队/进行中的编译不再阻塞分析冻结。同时捕获当前 `ManagedModule` 实例引用。
2. **闩外执行段**：`AnalysisRunner` 对冻结请求做 parse + sema（现有同步逻辑不变，
   只是不持任何闩）。每个分析使用**独立的 `GdScriptParserService`/`GdParserFacade`
   实例**——Tree-sitter parser 不能并发 parse，现状安全仅靠分析占闩；
   实施时先确认 gdparser `CstToAstMapper` 无内部可变状态，有状态则同样每分析新建。
3. **发布段**：在 `ManagedModule` 的同步边界内做**条件发布**——
   仅当（a）注册表中当前实例与冻结段捕获的实例**为同一对象**（防止删除后同 id
   重建的模块收到旧分析），（b）该实例未删除，（c）新版本**严格大于**已发布版本时，
   原子替换 `AtomicReference<ModuleAnalysisSnapshot>`；否则丢弃。同版本的并发分析
   内容等价，先发布者胜出，后到者丢弃快照但仍向各自调用方返回自己的 `AnalysisResult`。
   已删除模块上发布是**无操作**，不是异常。

#### 2.3.3 并发合同变化（需改写 RPC 文档 §8）

- `vfs.putFile` 等写操作仍走 `ModuleState` 锁（短暂），只与冻结段竞争，
  不再被整次分析阻塞。
- 分析不再等待编译；编译仍独占门闩全程，编译期间写操作行为不变。
- 同模块多个分析并行（各自读各自的不可变冻结请求，analyzer 每次新建，
  `AnalysisRunner.java:27-29` 的 per-run 隔离已保证）。
- 跨模块分析天然并行；配合独立 parser facade，不存在共享 parse 状态。

### 2.4 光标查询服务

新增进程内 Java API（`gd.script.gdcc.api.analysis.FrontendSnapshotQueryService`），
全部方法以 `ModuleAnalysisSnapshot` 为第一参数，纯读取：

- **快照构建期索引**：发布时一次性构建 AST parent 索引与节点 range 索引
  （AST 无父指针；避免每次查询重复建树）。
- `nodeAt(snapshot, displayPath, offset)`：从 `SourceFile` 根按 `Node.range()` 字节偏移
  下行定位**最深**覆盖节点。覆盖判定为半开区间 `[startByte, endByte)`；
  零宽 range（start==end）不覆盖任何偏移，永远不会被选中；同 span 取最深节点。
  光标停在标识符**之后**时由调用方/适配层换算到标识符内偏移（补全场景不经过
  nodeAt 覆盖判定，走 §2.5 上下文 API）。失败单元/无覆盖返回空。
- `definitionAt(...)`：声明来源归一化——`symbolBindings()` 的 `declarationSite`、
  `resolvedMembers()` / `resolvedCalls()` 的声明来源**不保证是带 range 的 AST 节点**
  （可为 `PropertyDef`、`List<? extends FunctionDef>` 重载集合、合成构造器等模型对象，
  可无源码位置）。因此 skeleton 期新增**声明溯源索引**（同代内"声明模型对象身份 →
  源 AST 声明节点 + displayPath"），随快照发布。归一化规则：AST 节点直接用；
  单个模型对象查溯源索引；集合型 provenance 逐元素归一化：**按元素序**返回全部可归一化
  元素的源码位置（**"多个候选声明"结果种类**），无法归一化的元素在结果中**按原元素位
  置**显式标记为无源码位置——不得擅自挑选其一，也不得因部分元素失败丢弃其余元素或
  把失败标记挪到列表尾部；仅当全部元素都无法归一化时才返回"外部声明/无源码位置"
  结果种类（engine/builtin metadata 等外部声明同为此类）。单元素集合且可归一化
  （如方法引用恒为 `List[1]`）归并为"单一源码声明"种类——多候选种类保留给真正
  有歧义的 provenance（多元素集合或存在不可归一化元素）。
- `usagesAt(...)`：反向索引**先归一化再分组**——三张表的每个站点先按上述规则
  归一化到稳定源码声明身份，再按该身份（`IdentityHashMap`）分组；
  集合型 provenance 的站点计入每个可归一化元素的 usages。member/call 站点**按状态
  过滤**：仅 `RESOLVED`/`BLOCKED` 入索引（BLOCKED 保留 blocked-winner provenance 作
  为真实使用意图）；`DEFERRED`/`UNSUPPORTED`/`FAILED`/`DYNAMIC` 按状态缺席——它们
  携带的声明载荷（如失败 `ClassName.member` 为诊断记录的 receiver 类）不是使用意图。
  绑定站点仅当归一化失败（null provenance、外部声明、集合元素全部失败）时缺席；
  缺席集合（含未绑定标识符、`DEFERRED`/`UNSUPPORTED` 站点、类型位置引用
  ——TYPE_META 按 kind 过滤）写入文档。索引惰性构建、线程安全 memo（见 §2.1）。
- `typeAt(...)`：按节点查 `expressionTypes()` / `slotTypes()`，返回类型显示名文本；
  无事实节点返回空结果。注意 `expressionTypes` 键空间含 attribute step
  （`FrontendAnalysisData.java:47-51`），查询要覆盖 step 键。
- `documentationAt(...)`：符号文档描述符投影——把光标处符号的解析事实归一成一条
  `SymbolDocDescriptor`（符号种类 / 文档归属命名空间 / 属主名 / 成员名），供编辑器拼接
  官方文档 URL 或展示归属。归一化规则：
  - `GDCC` 用户符号：复用 `definitionAt` 的完整声明归一化规则（AST 声明直接定位、
    模型对象查声明溯源索引、集合型 provenance 逐元素归一化）；描述符的候选列表
    **按元素序**承载全部候选源码位置，不可定位项以 null-location 候选**按原位置**
    显式保留，不擅自挑选其一。多候选 provenance 的 `ownerName` 取**首个可定位候选**的
    声明类（provenance 序即解析序，最近声明优先）。描述符身份**不替代** `usagesAt`
    的源码声明身份分组键。
  - `ENGINE`/`BUILTIN` 类成员：属主取**实际声明类**。已发布事实的 `declarationSite`
    只保留成员级元数据（`PropertyDef`/`SignalDef`/成员 `PropertyInfo` 等），**不保留**
    共享 resolver 中间结果的 `ownerClass`；查询期按成员类别用 registry 既有层次查找
    补齐属主（`findEngineClassConstantInHierarchy`/`findEngineClassEnumValueInHierarchy`/
    `findBuiltinClassConstantInHierarchy`/`findBuiltinClassEnumValueInHierarchy`/
    `findPropertyInHierarchy`/`findEngineSignalInHierarchy`，入口接收类名，由
    `receiverType` 取名）。对已解析的方法调用/引用：沿 receiver 类链按**已选中
    `FunctionDef` 的对象身份**定位声明类——不得重新执行重载选择，也不得按最近同名
    方法推断属主。builtin 属性的 `PropertyInfo` 是逐次合成的（无跨次对象身份），属主
    直接取 receiver 的 builtin 类。
  - 全局元数据（全局常量、全局枚举组/枚举值、GDScript 语言常量）**没有实际声明类**，
    适用独立的文档归属命名空间规则，按 registry provenance 判定归属，不得用其值类型
    （如全局枚举值是 `int`）推断属主；`@GlobalScope`/`@GDScript` 是文档归属命名空间
    名称，不是 `ClassDef` 类名。    engine/builtin 类枚举值当前经类域静态成员路径解析（如 `Node.PROCESS_MODE_INHERIT`）；
    GDCC 当前前端不解析这两类的枚举组名，不发布对应的组级解析事实。本阶段枚举组
    描述符只覆盖 GDCC 枚举组与全局枚举组。
  - 工具函数按 registry 来源分类：`ExtensionUtilityFunction` 中 dump 来源的归
    `@GlobalScope`；registry 合成的 GDScript 语言函数（`len`/`range`/`load` 等）归
    `@GDScript`——经 `isGdScriptLanguageFunction`/`findGdScriptLanguageFunction` 判定，
    不得按 `ownerKind`（两类同为 ENGINE）或函数名名单区分。
  - `FrontendResolvedCall` 的分类字段是 `callKind` 而非 `bindingKind`，描述符需规定
    映射（如 utility 调用发布为 `STATIC_METHOD` route，裸绑定才是 `UTILITY_FUNCTION`）。
  - 引擎元数据不含文档文本（GDExtension dump 无描述字段）；描述符只产出
    分类 + 归属命名空间 + 属主名 + 成员名，文档 URL 锚点规则由编辑器/适配层负责。
  - `definitionAt` 的"外部声明"只表示无源码位置，不表示无文档归属：外部符号照常产出
    描述符，但不进入 `usagesAt` 的源码身份分组。
  - 未绑定标识符、`FAILED`/`DEFERRED`/`UNSUPPORTED`/`DYNAMIC`/`BLOCKED` 站点返回空结果，
    不抛异常；绑定侧的 BLOCKED 形态是 `FOUND_BLOCKED` 值绑定（声明前使用、参数默认值
    island），同样返回空。
- 坐标约定：入参接受字节偏移或（0 基行, 0 基列）；内部规范形式为字节偏移。
  返回位置统一 displayPath + 1 基行列 range（与诊断一致）+ 字节偏移；
  UTF-16 列换算属未来 LSP 适配层职责。

### 2.5 补全基础

1. gdparser 新增补全上下文提取（§3）：对**快照保存的源文本**做新的补全解析。
   该解析产出的是**新一代 CST**，只允许用于上下文分类、完整标识符
   replaceableRange 与 receiver **range**，绝不用于节点身份。
2. GDCC `completionCandidatesAt(snapshot, path, offset)`：
   - **成员访问**：用 receiver range 在**快照 AST**（同一代）中定位 receiver 末端
     最深且已发布 `expressionTypes` 的节点/attribute step；命中则按类型枚举成员
     （Object 派生经 `ClassRegistry` + GDCC class skeleton；builtin 经扩展 metadata）。
     命中失败（receiver 被错误结构吞掉、无类型事实）返回空候选列表，不抛异常。
     `obj.` 场景依赖 §2.2.1 的部分链映射：receiver 前缀在快照中已正常定型。
   - **标识符前缀**：为 `Scope` 新增**枚举可见名字**的 API（现有只有按名单查的
     `resolveValueHere(name)`），沿 scope 链枚举并复用 declaration-after-use 的
     字节序过滤（声明 `range().endByte() <= useSite.range().startByte()`）；
     光标落在无 scope 记录的叶子上时沿 parent 索引向上回退到最近有 scope 的祖先。
   - **类型位置**：枚举全局类名映射、`ClassRegistry` 可见类型与文件内 inner class。
   - 上下文落在 skipped/error 子树内（未完成成员访问的部分链除外）返回空候选。
3. 候选 DTO：名称、种类（property/method/value/type）、类型或签名文本；不序列化内部对象。

---

## 3. gdparser 改造清单（独立仓库，发布 0.6.0）

> 按用户决定：允许修改 gdparser 并发布新版，GDCC 随后升级依赖。
> 以下 API 形状为设计提案，实施时以 gdparser 仓库的实际包结构为准。

1. **缺失成员名的部分链映射（必须，补全正确性前提）**
   `receiver.`（`.` 后无任何标识符）映射为正常 `AttributeExpression` 链 +
   缺失末步的显式标记，receiver 前缀保持可分析；对应 `MISSING` 诊断照旧产生。
   禁止把整条语句吞进错误节点。`receiver.par` 是完整语法，按普通链映射、
   不产生任何标记或诊断——是否处于补全由 `parseCompletionContext` 按光标偏移判定。
2. **错误节点映射（必须）**
   - 新增 `ErrorStatement` / `ErrorExpression` record：携带 `Range`、`CstIssueKind`
     （`ERROR`/`MISSING`）与原始 CST 片段文本。
   - `CstToAstMapper` 遇到其余 CST `ERROR`/`MISSING` 节点时构造错误节点，
     不再用 `requireField` 猜测补齐（现有行为会构造不准确节点并污染下游）。
   - sibling 映射继续（现有行为基本满足，需补回归测试）。
   - `AstMappingResult` 诊断照旧携带错误节点 range。
3. **MISSING 诊断增强（必须）**：诊断消息带期望 token/符号名，便于编辑器展示。
4. **补全上下文 API（必须）**
   - `parseCompletionContext(String source, long byteOffset)` → `CompletionContext`：
     - `kind`：`MEMBER_ACCESS` / `IDENTIFIER` / `TYPE_POSITION` / `CALL_ARGUMENT` / `UNKNOWN`；
     - `replaceableRange`：光标所在标识符的**完整** range（覆盖光标前后两段，
       选中候选时整体替换）；用于过滤的已输入前缀由调用方按光标偏移从
       replaceableRange 起点截取，不单独定义 prefixRange；
     - `receiverRange`：`MEMBER_ACCESS` 时 `.` 左侧片段的字节 range（只给 range，不给节点）；
     - 未完成输入基于 Tree-sitter 自然产生的 `ERROR`/`MISSING` 结构做光标定位；
       **语法完整的成员访问**（光标落在成员名前缀内，如 `obj.pa|r`）也必须能沿
       正常 attribute CST 判定 `MEMBER_ACCESS`，给出 receiverRange 与前缀的
       replaceableRange——不得只覆盖错误结构路径。
5. **线程安全确认（必须）**：明确 `GdParserFacade` 是否内含单个 `TSParser`、
   `CstToAstMapper` 是否无状态；GDCC 侧按结论决定每分析新建 facade 还是加锁池化。
6. **AST 集合冻结（必须）**：AST record 构造时对子节点集合做 `List.copyOf` 等防御性
   冻结，保证发布后的 AST 对象图不可变（快照不可变合同的前提，见 §2.1）。
7. **不纳入本版**：Tree-sitter 增量重解析（edit + reparse 复用旧树）列为后续性能优化，
   本轮全量重解析（单文件解析为毫秒级，可接受）。

---

## 4. GDCC 改造清单（按包）

### 4.1 `frontend.parse`

- 升级 gdparser 至 0.6.0（`build.gradle.kts` 版本变更需用户确认后执行）。
- `GdScriptParserService`：`parse.internal` 路径在返回单元上置解析失败标记；
  错误节点随 AST 流入，诊断照旧入 manager。

### 4.2 `frontend.sema`

- 新增错误子树标注步骤（§2.2.2）。
- `FrontendScopeAnalyzer`：所有 handler 入口先查 skipped 根（§2.2.3）。
- `FrontendSuiteResolver` / statement resolver：statement 级 skipped 根消费（§2.2.3）。
- `FrontendClassSkeletonBuilder`：排除解析失败单元（§2.2.5）；类成员错误节点显式标注；
  新增声明溯源索引发布（§2.4 definitionAt）。
- `Scope`：新增可见名字枚举 API（§2.5）。
- 各 analyzer 防御性加固（§2.2.4）。
- 同步更新 `frontend_rules.md`（body 消费 skipped 表、错误节点恢复合同）、
  `diagnostic_manager.md`（错误节点接入 skipped-subtree 合同）、
  `scope_analyzer_implementation.md`——随对应实施阶段完成，不积压到收尾。

### 4.3 `api` 包

- `ModuleState`：`contentVersion` 计数与冻结捕获（§2.3.1）。
- `API`：`analyze` 三段式（§2.3.2）；`AtomicReference<ModuleAnalysisSnapshot>` 条件发布；
  `getModuleContentVersion(moduleId)`；`getLatestAnalysisSnapshot(moduleId)`；
  包级私有分析执行 seam（构造器注入的包装器），供并发测试确定性阻塞/乱序，
  不加生产开关。
- `AnalysisRunner`：删除短路（§2.2.6）；返回内部富结果（`AnalysisResult` + 快照载荷）。
- `AnalysisResult`：追加 `moduleGeneration` 与 `snapshotVersion` 组件。紧凑构造器规则同步更新
  （所有 outcome 都必填；现有 `loweringStatus` 校验不变）。构造点：
  `AnalysisRunner` 的 `completedResult`/`failureResult` 与 RPC codec 测试样例。
- `ManagedModule`：门闩合同注释更新（分析不再入闩；编译语义不变）。
- 新子包 `api.analysis`：`ModuleAnalysisSnapshot`（含只读视图/冻结投影、parent/range
  索引、线程安全 usages memo）、`FrontendSnapshotQueryService`、补全候选 DTO。

### 4.4 `rpc` 包

- 方法名与请求 schema 不变；`analyze.run` 响应增加 `moduleGeneration` 与 `snapshotVersion`；
  解析错误时结果仍为 `COMPLETED` 且可能含 `sema.*`；`analyze.run` 不再因编译阻塞。
- 同步更新（随实施阶段完成，不积压）：`rpc_api_implementation.md` §4.9/§7.1/§8、
  `json_rpc_service_implementation.md` §2.6；
  测试 `RpcJsonCodecTest.analysisResultSerializesAllComponentsWithoutDerivedMethods`、
  `ApiAnalyzeTest.analyzeWaitsForModuleGateBehindOtherOperations`（行为变化需重写）、
  `RpcApiRoundTripHttpTest` analyze 段。

---

## 5. 分阶段实施与验收细则

> 每阶段完成后运行对应测试类（`script/run-gradle-targeted-tests.sh --tests ...`），
> 全部阶段结束后跑 `./gradlew clean build --no-daemon --info --console=plain`。
> 遵循 `frontend_rules.md` 测试约定：每条恢复规则 happy + negative 双覆盖，
> negative 至少锚定 diagnostic category、坏 subtree 被跳过、其他合法 subtree 继续工作。

### Phase 1：gdparser 0.6.0（外部仓库）

内容：§3 第 1-6 项。

验收（gdparser 仓库单测）：

1. `obj.`（`.` 后无标识符）映射为部分 AttributeExpression 链，receiver 前缀节点完好，
   `MISSING` 诊断带期望符号名；`obj.par` 保持普通链映射、无伪诊断。
2. 含 `ERROR` 结构的源（函数体内残缺 statement）映射出 `ErrorStatement`，
   range 覆盖错误区间，同 suite 后续 statement 正常映射。
3. 错误表达式嵌在 `var` / `if` / `for` / `match` / lambda / 参数默认值中的各形态
   均产错误节点而非猜测节点。
4. `parseCompletionContext` 对 `obj.` 返回 `MEMBER_ACCESS` 与正确 receiver range
   （replaceableRange 为 `.` 后的零宽位置）；对裸标识符返回 `IDENTIFIER` 与覆盖
   完整标识符的 replaceableRange；对 `obj.pa|r`（光标在成员名中间）同样返回
   `MEMBER_ACCESS`，replaceableRange 覆盖完整 `par`。
5. AST 集合冻结：构造后修改传入的子节点列表不影响已构造 AST。
6. 既有测试套件全绿（无回归）。

### Phase 2：GDCC 容错语义分析

> **状态：已完成实施（2026-10-01），评审修复已合入。** 实施事实：
> - 错误子树标注为 `FrontendErrorSubtreeAnnotator`（静态工具，skeleton 发布后、scope 前运行）；
>   scope 全部专用 handler 入口补 skipped 根检查；body 消费点=`FrontendSuiteResolver` 逐句循环
>   +`FrontendStatementResolver` 防御入口 + 参数默认值 island（`analyzeIsland` 命中即回收元数据并
>   静默返回）+ 属性初始化器 island；`FrontendVariableAnalyzer` 不为无 scope 声明建 locals；
>   `FrontendTypeCheckAnalyzer.walkStatements` 消费 skipped 根。
> - `FrontendSourceUnit.parseFailed` 显式标记 `parse.internal` 单元；skeleton 整体排除
>   （不合成顶层类头、不收集 annotation），其他文件引用走 `sema.type_resolution` 未解析诊断。
> - 声明溯源索引=`FrontendAnalysisData.declarationOrigins()`（`IdentityHashMap<Object,
>   FrontendDeclarationOrigin>`，skeleton 在建 class/property/function/signal/enum 常量与 group
>   时按对象身份记录 AST 声明节点 + 单元 logicalPath；displayPath 由快照层 remap）。
> - `AnalysisRunner.analyzeRich` 返回包级 `AnalysisRunResult`（公开结果 + COMPLETED 时的语义
>   载荷）；删除 parse 错误短路；lowering 验证用独立新建 `ClassRegistry`/`DiagnosticManager`
>   （仅导入 parse 阶段诊断一次）；共享 analyze 成功后 lower 抛异常只记 `loweringStatus=FAILED`；
>   parse/sema 未预期异常 → `INTERNAL_FAILED` 且无载荷。包级 `SemanticRun`/`LoweringRun` seam
>   供失败注入测试。
> - gdparser 0.6.0 实测形态锚点：`if :`/`for i in :`/`match :`/`return = 3` → 错误表达式在
>   条件/迭代/值位置；`var x = true if  else false`（函数尾）与 `var hp = (1`（类尾）→
>   `ErrorStatement`；`func f(x = 1 +)` → 幻影 Parameter（无错误节点，参数默认值 island 的
>   skipped 消费为纯防御路径，由手写 AST 单测锚定）；`var x = self.`（函数尾）→
>   `MissingAttributeStep` 部分链 + `Missing identifier` 诊断、不标 skipped。0.6.0 无法从真实
>   源码产生产生"仅 WARNING 无 ERROR"的 parse 诊断形态，验收 5 的警告保留由 lowering 诊断
>   无重复锚点 + 代码审查覆盖。
> - `Scope` 新增 `valuesHere()`/`collectVisibleValues()`/`enumerationParentScope()`（§2.5 的
>   Scope 枚举 API）；declaration-after-use 字节序过滤在
>   `FrontendVisibleValueEnumerator.enumerateVisibleValues`（先过滤再遮蔽）。全局常量枚举留待
>   Phase 5 经 `ClassRegistry` 直连。
>
> 评审修复（review-expert 复核后合入）：
> - 部分成员链（`obj.`）链归约 FAILED 事实保留，但根表达式诊断被抑制（parser 的
>   `Missing identifier` 保持单一 owner）；幻影参数（`func f(x = 1 +)`）覆盖在 parse 诊断
>   范围内时抑制 `sema.invalid_parameter_default_order`。
> - lowering 验证抛异常时补发单条 `sema.lowering` error 携带异常原因（可观测性）。
>
> 评审修复（终审轮，review-expert-a/c 并行复核后合入）：
> - **缺名 match 绑定**（阻断级）：gdparser 把 `var :` 映射为 `name=""` 的
>   `PatternBindingExpression` 而非错误节点，未经标注直达变量 inventory 抛
>   `IllegalArgumentException` 拖垮整模块分析。修复：标注器把空名绑定视为损坏（标记所属
>   statement 根），`FrontendMatchSupport.collectPatternBindingsInto` 再防御性跳过空名；
>   直接绑定与字典嵌套绑定均有 runner 级回归。
> - **annotation usage 消费 skipped 合同**：visitor 不再进入 skipped 根（损坏属性的
>   `@onready` 不再误报 placement）；`FrontendGdAnnotation` 新增 `sourceStatement` 身份，
>   源 statement 被标注的投影（如 `@export_range(1, = 3)`）不再追加参数校验诊断。
> - 枚举器字节序过滤与 resolver 对齐：`Parameter` 节点豁免，其余所有
>   `VariableDeclaration`（含 CAPTURE）按声明顺序过滤。
>
> 内容：§4.1/§4.2 与 §2.2 全部（含 `AnalysisRunner` 短路移除），以及 runner 的
**包级私有富结果入口**（返回 `FrontendAnalysisData` 等语义载荷；Phase 3 才包装为
公开快照）。本阶段断言直接打在包内可得的 `FrontendAnalysisData` 上（frontend 级测试 +
该包级入口），**不依赖** Phase 3 的快照 API。

验收：

1. 跨文件：模块内一个文件有语法错误、其余文件干净时，共享语义照常运行，
   干净文件的 `symbolBindings`/`expressionTypes` 事实存在；诊断含 `parse.lowering` ERROR。
2. 同文件子树级：错误 statement 之后的同函数健康 statement 仍发布事实；
   错误根出现在 `skippedSubtreeRoots()` 且其下无 `scopesByAst` 记录；
   分别用损坏的普通 statement、`if`、`for`、`match`、lambda 形态验证。
   expression island 单独验证：损坏的参数默认值只跳过该默认值表达式，
   损坏的属性初始化器只跳过该属性声明，两者都不得导致整个 callable 被跳过。
3. `parse.internal` 单元：不注册合成顶层类；其他文件引用其类名得到正常未解析诊断。
4. `includeLowering=true` + 解析错误：语义事实仍发布、`loweringStatus == FAILED`、
   诊断中无 `sema.compile_check`、无异常。
5. `includeLowering=true` 且无解析错误：lowering 验证的二次分析使用独立新建的
   `ClassRegistry`/`DiagnosticManager`；共享分析那一代的 registry 与已发布事实的
   对象身份不被改写；lowering manager 已导入解析阶段诊断——仅有解析 WARNING 时
   结果诊断仍包含该警告，且不重复出现共享语义诊断。
6. 消极面：任何错误形态下管线不向调用方抛异常。解析或共享语义分析阶段的
   未预期异常返回 `INTERNAL_FAILED` 且不发布半成品数据（R14）。
   共享 `analyze(...)` 已成功后 `lower` 抛出：runner 捕获，outcome 仍为
   `COMPLETED`，快照照常发布，`loweringStatus=FAILED`，结果诊断取 lowering 侧
   已收集诊断。
7. 既有 frontend 测试套件（skeleton/scope/binding/type-check 等）全绿；
   `frontend_rules.md`/`diagnostic_manager.md`/`scope_analyzer_implementation.md`
   合同更新随本阶段合入。

### Phase 3：API 语义快照与并发

> **状态：已完成实施（2026-10-01），评审修复已合入。** 实施事实：
> - `ModuleState` 新增 `moduleGeneration`（`API.createModule` 从全局单调计数器分配）与
>   `contentVersion`（`putFile`/`deletePath`/`createDirectory`/`createLink`/`options.set`/
>   `classMap.set` 递增；编译产物挂载走内部非递增变体，不计入）；`freezeCompileRequest()` 把
>   （代际, 版本）捕获进 `CompileRequest`。
> - `AnalysisResult` 追加 `moduleGeneration`/`snapshotVersion`（所有 outcome 必填，紧凑构造器
>   校验代际为正、版本非负）。
> - `API.analyze` 三段式：`ModuleState` monitor 内冻结（不进 `ManagedModule.busy`、不等
>   `queuedCompileTaskId`）→ 闩外经包级私有 `AnalysisRunSeam`（构造器注入，默认委托
>   `AnalysisRunner.analyzeRich`）执行 → `ManagedModule` monitor 内条件发布（注册表实例身份
>   guard + 未删除 + 版本严格更大）。新增 `getModuleContentVersion`（返回
>   `ModuleContentVersion` 对）与 `getLatestAnalysisSnapshot`。
> - 新子包 `api.analysis`：`ModuleAnalysisSnapshot`（公开元数据/源视图/诊断；`FrontendModule`/
>   `FrontendAnalysisData`/`ClassRegistry` 仅包级私有暴露给查询服务；`queryMemo`
>   `ConcurrentHashMap` 留给 Phase 4 惰性索引）。gdparser 0.6.0 线程安全（每 parse 新建
>   `TSParser`、mapper 无状态），故 `GdScriptParserService` 保持共享实例，不新建 facade。
> - `analyzeWaitsForModuleGateBehindOtherOperations` 按新合同重写为
>   `analyzeDoesNotWaitForModuleGateBehindOtherOperations`。
>
> 评审修复与裁决（review-expert 复核后）：
> - 已修复：编译产物清理改走非递增内部删除路径（§2.3.1 的"不计入"合同此前被
>   `clearManagedOutputDirectories` 经公开 `deletePath` 破坏）。
> - 裁决保留：同版本"先发布者胜出"不变——仅当 parser 对同一内容非确定性崩溃
>   （`parse.internal`）时才存在内容不等价的同版本重试，tree-sitter 解析对同一输入是确定性的，
>   该场景不可达。
> - ~~保留待确认（架构级）~~ **已解决（方案 B，freeze-on-publish）**：快照构造时对
>   `FrontendAnalysisData`（14 张 side table + provenance 索引级联 `freeze()`，全部
>   `update*`/`applyPatch`/`mark*`/pending 方法闸门）与 `ClassRegistry`
>   （`addGdccClass`/`removeGdccClass` 闸门）做结构性冻结。冻结闭合覆盖全部写通道：直接
>   方法、`Map.Entry.setValue`、`replaceAll`（side table 覆写以避免被默认实现包装成
>   CME）、iterator/view 移除，以及**冻结前获取的视图**（`FreezableIdentityMap` 组合式
>   容器在操作时查验冻结位）；registry 惰性 virtual-method 缓存迁移 `ConcurrentHashMap`
>   （发布后查询线程并发读安全）。合同收窄声明：冻结覆盖 §2.1 枚举的容器拓扑面
>   （side table/provenance/registry 成员）；容器内可达模型对象（`Scope`/`ClassDef` 自有
>   修改器）在 Phase 4 只读视图（方案 C）落地前仍按约定冻结，已在
>   `ModuleAnalysisSnapshot` javadoc 写明。方案 C 保留为 Phase 4 开工首项，B 的冻结闸
>   届时作为视图之下的第二道防线。
>
> 评审修复（终审轮，review-expert-a/c 并行复核后合入）：
> - `contentVersion` 只在变更实际落地后递增：幂等 `createDirectory`（目录已存在）、失败的
>   `createLink`/`deletePath`（根路径、类型冲突、路径不存在、非空目录）不再推进版本
>   （新增 `ApiContentVersionTest` 锚定）。写路径采用"试探性放置 + 完整回滚"：target 语法与
>   `displayPath` 校验在创建父目录之前，链接放置后在拟提交树上 `inspectLink`，失败即回滚
>   被替换的叶节点与本次自动创建的全部祖先目录——因此覆盖文件形成的合法自引用（CYCLE broken
>   link）行为不变，而不可解析目标干净失败。
> - `getModuleContentVersion`/`getLatestAnalysisSnapshot` 在 `ManagedModule` monitor 内复核
>   注册表实例身份与删除标记，消除删除/重建窗口读到上一代快照或版本对的读侧竞态。
> - managed 输出目录排他性落实为强制守卫：清理前发现 `.gd`/`.gd3` 源码条目即让编译以
>   `CONFIGURATION_FAILED` 响亮失败，不再静默删除同版本源码；指向输出目录的外部
>   `VIRTUAL` 别名在清理-重挂载窗口的瞬时 broken 已记录为已知瞬态（冻结失败但版本对不变，
>   调用方可检测重试）。`close()` 不等待在途分析，已在 `analyze` javadoc 写明。
>
> 内容：§2.1/§2.3 全部，§4.3/§4.4。

验收：

1. `AnalysisResult.snapshotVersion` 对所有 outcome 都等于冻结版本，且随
   `putFile` 单调递增；`getLatestAnalysisSnapshot` 返回版本与内容自洽。
2. 分析执行期间（经包级 seam 阻塞 sema 入口）发起 `vfs.putFile`：
   putFile 在分析完成前返回；分析结果对应冻结时的版本。
3. 已预约编译时 `analyze` 的冻结立即返回（不再等待编译）；
   `analyzeWaitsForModuleGateBehindOtherOperations` 按新合同重写。
4. 同版本并发分析：先发布者胜出，后到者各自返回自己的结果且快照不被回退。
5. 版本乱序完成（seam 控制）：旧版本后完成不覆盖新快照。
6. 分析进行中删除模块：分析正常返回，快照不发布；删除后同 id 立即重建模块，
   旧分析不得写入新模块的快照（实例身份守卫）；新模块代际不同，调用方对
   删除重建前后的 `AnalysisResult`（不只快照）按（代际 + 版本）判断陈旧度，
   不会把旧结果误判为当前。
7. 跨模块并行 analyze、同模块 analyze ∥ compile 回归（独立 parser facade 验证）。
8. 已发布快照不可变：外部尝试经快照修改语义数据或 AST 节点集合被拒绝/不影响查询。
9. `ApiConcurrentMutationTest`、compile 相关既有并发测试全绿；
   `RpcJsonCodecTest` 与 `RpcApiRoundTripHttpTest` 更新后全绿。

### Phase 4：光标查询服务

> **状态：已完成实施（2026-10-05），评审修复已合入。** 实施事实：
> - `api.analysis` 新增：`QuerySourceRange`（displayPath + 1 基行列 + 字节区间）、
>   `AstUnitIndex`（单单元 parent/range/line 三索引；line 索引按 UTF-8 字节累计换算
>   （0 基行, 0 基字节列）入参）、`SnapshotAstIndex`（按 normalized logicalPath 键配对
>   SourceView 与 unit；`parseFailed` 单元建空索引，查询恒空）、`DeclarationLookupResult`、
>   `DeclarationNormalizer`、`UsagesReverseIndex`、`DocNamespace`/`DocSymbolKind`/
>   `SymbolDocDescriptor`、`FrontendSnapshotQueryService`。
> - 索引在 `ModuleAnalysisSnapshot` 构造时一次性构建（紧随 freeze）；`nodeAt` 公开——AST
>   图深度不可变故节点只读暴露给外部包，而 side table 与 registry 保持包级私有，节点身份
>   键无法在外部跨代解析；definitionAt/usagesAt/typeAt/documentationAt 为公开 DTO 投影。
>   （初版 nodeAt 曾为包级私有，因后续外部包需要光标→节点定位原语而放开。）
> - 方案 C 落地形态：查询服务对容器内可达模型对象只做**接口窄化只读访问**（不调用任何
>   修改器）+ 结果 DTO-only（模型对象不出包），冻结闸作为其下第二道防线；未新建包装类
>   层级。已写入 `ModuleAnalysisSnapshot` javadoc。
> - definitionAt：单元素集合（方法引用恒为 `List[1]`）归并为 SINGLE_SOURCE；
>   MULTIPLE_CANDIDATES 逐元素列出可归一化位置并对不可归一化元素给 null-location 显式
>   标记；全失败归 EXTERNAL；null provenance 归 NONE。
> - usagesAt：反向索引惰性构建于 `queryMemo.computeIfAbsent`（单次计算线程安全）；
>   先归一化再按声明 AST 节点身份分组，集合型 provenance 站点计入每个可归一化元素；
>   TYPE_META 过滤落地"类型位置缺席"（构造调用步骤如 `QueryBase.new()` 的 `.new()` 仍
>   合法归组——类型位置头站点本身绝不出现）。
> - typeAt：先 `expressionTypes()`（含 attribute step 键）后 `slotTypes()`；
>   publishedType 为 null 的状态（DEFERRED/FAILED/UNSUPPORTED）返回空。
> - documentationAt：GDCC 溯源优先（含 `GdScriptClassConstant` 包装解包到枚举声明）；
>   工具函数集合解包后经 `isGdScriptLanguageFunction` 分类（print→@GlobalScope、
>   len→@GDScript）；engine/builtin 属主经 registry 层次查找（常量/枚举值/信号/属性）与
>   已选中 FunctionDef 身份链走查（方法）；全局枚举值经 `findGlobalEnumValueByBareName`
>   身份比对区分 @GlobalScope 与类枚举；builtin 属性属主直接取 receiver builtin 类。
> - 实测形态锚点：`extends` 目标为标量（`ExtendsStatement.target`，无标识符节点、无
>   绑定事实），跨文件类引用经表达式位置（`QueryBase.new()`）锚定；4.5.1 dump
>   `global_constants` 为空——全局常量无 @GlobalScope 真实用例，`PI` 锚定合成语言常量
>   的 @GDSCRIPT 路径；未标注类型的局部变量槽为 Variant（builtin 成员正例需显式
>   `: Vector2` 标注，未标注的 `vu.x` 锚定 DYNAMIC 空结果反例）。
> - 测试：`FrontendSnapshotQueryServiceTest`(17) / `DocumentationAtTest`(6) /
>   `SnapshotQueryEdgeTest`(9，手工快照锚定集合归一化、混合集合、全失败、parseFailed
>   单元、FOUND_BLOCKED 绑定、GdScriptClassConstant 解包) 共 32 项全绿；回归
>   ModuleAnalysisSnapshotImmutabilityTest / ApiAnalysisSnapshotConcurrencyTest /
>   ApiAnalyzeTest / ApiContentVersionTest 全绿。
>
> 评审修复（review-expert-a/c 并行复核后合入）：
> - **集合归一化元素序**（WARNING）：`DeclarationNormalizer.Result` 改为元素序 nullable
>   列表，失败元素原位显式标记，不再挪到尾部；单元素集合（`List[1]`）且可归一化归并
>   SINGLE_SOURCE 的规则从状态块上升为 §2.4 正文合同（多候选种类保留给真正歧义）。
> - **documentationAt 候选完整性**（WARNING，双审）：描述符 `sourceLocations` 改为
>   `sourceCandidates`（元素序、null-location 显式槽），GDCC 属主规则明确为首个可定位
>   候选的声明类（provenance 序=解析序）。
> - **BLOCKED 绑定过滤**（WARNING）：`FOUND_BLOCKED` 值绑定（参数默认值 island、静态
>   上下文）不再产出描述符（验收 7 的 BLOCKED 空结果）；usages 侧 BLOCKED 站点保留
>   （真实使用意图）——双向行为均有手工快照锚定（语句级 declaration-before-use 实测
>   为 UNKNOWN/null-site，FOUND_BLOCKED 形态由种子事实锚定）。
> - **usages 按状态缺席**（WARNING）：member/call 站点仅 RESOLVED/BLOCKED 入索引——
>   失败 `ClassName.member` 为诊断记录的 receiver 类 provenance 不再污染类 usages；
>   §2.4 usages 条目同步改写（原"仅归一化失败才缺席"主句与验收 2 的矛盾按验收 2
>   口径统一）。
> - **并发测试真实首次竞态**（WARNING，双审）：改用未做过 usages 查询的新快照，
>   工作线程首次调用即竞态 memo 单算；Future 全部 get() 传播线程异常。
> - **行列换算整数溢出**（WARNING）：`byteOffsetAt` 先比行内长度再相加；新增
>   `Integer.MAX_VALUE` 列、多字节字符、CRLF 往返用例。
> - **unitOf 快照级索引**（WARNING）：发布期遍历时同步填充
>   `IdentityHashMap<Node, AstUnitIndex>`，反向索引构建从 O(站点×单元) 降为 O(站点)。
> - **方案 C 闭合**（WARNING）：新增 `ReadOnlyScope` 门面（`setParentScope` 抛 UOE，
>   返回值递归包装），查询服务对 Scope 的访问全部经门面；快照 javadoc 与 §2.1 同步。
> - usages 排序补 endByte/行/列 tiebreak 成全序；`classifyKind` 对
>   `GdScriptClassConstant` 按所携枚举声明分类（ENUM_VALUE/ENUM_GROUP 而非 CONSTANT）；
>   新增裸 `print` 引用（集合解包路径）锚定。
>
> 评审修复（终审轮，review-expert-a/c 并行复核后合入，终审均 APPROVE）：
> - **ReadOnlyScope 沿链查找语义**：`resolveValue`/`resolveFunctions`/`resolveTypeMeta`
>   改为在原始 scope 上直接委托——接口默认协议会走包装后的父链，把 `ClassScope`
>   跳过连续外层类 scope 的规则吃掉；`valuesHere()` 改 `List.copyOf` 快照。
>   `ReadOnlyScopeTest` 锚定内类门面下外类成员 NOT_FOUND、内类成员 FOUND、父链递归
>   包装且各层 `setParentScope` 均关闭。
> - **并发测试期望值预热**：改为先逐 `Future.get()` 收集全部工作线程结果（传播
>   异常），主线程最后才计算期望值比对，彻底排除主线程抢先构建 memo 的路径。

内容：§2.4（parent/range 索引、声明溯源索引、definitionAt/usagesAt/typeAt/documentationAt）。

验收：

1. 局部变量/参数/属性/方法的 definitionAt 命中声明 range；跨文件类引用命中声明文件；
   重载方法等集合型 provenance 返回"多个候选声明"种类并列出全部归一化位置；
   混合集合（部分元素可归一化、部分不可）返回可归一化位置并对其余显式标记无源码位置；
   engine/builtin 声明返回"外部声明"种类。
2. usagesAt 返回三张表内可归一化的全部站点（含受损文件未受损子树内的站点）；
   未绑定标识符、DEFERRED/UNSUPPORTED 站点、类型位置缺席（文档化）。
3. typeAt 对表达式/变量/attribute step 键返回正确类型文本；无事实节点返回空。
4. `parse.internal` 单元上 `nodeAt` 返回空，无 NPE。
5. nodeAt 半开区间与最深节点规则用例；零宽节点不被选中用例；
   offset 与 line/col 两种入参结果一致。
6. 过期快照查询仍自洽（快照内闭合）；并发 `usagesAt` 首次构建无竞态。
7. documentationAt：engine 方法/属性/常量/枚举值/信号返回 ENGINE 域 + **实际声明类**名 +
   成员名（继承成员不归调用点静态类型；方法用例断言**已选中 FunctionDef** 的声明类，
   含最近声明遮蔽）；builtin 类型成员返回 BUILTIN 域与属主名；`print` 归
   `@GlobalScope`、`len` 归 `@GDScript`；全局常量与全局枚举按 registry provenance 归
   `@GlobalScope`（裸枚举值与限定访问各一例）；枚举组描述符只覆盖 GDCC 枚举组与全局
   枚举组（GDCC 当前前端不解析 engine/builtin 类枚举组名，不发布对应的组级解析事实）；GDCC 用户
   符号按 definitionAt 归一化返回全部候选源码位置（局部变量、参数、未调用的重载方法
   引用各一例）；未绑定/FAILED/DEFERRED/UNSUPPORTED/DYNAMIC/BLOCKED 站点返回空结果。

### Phase 5：补全候选枚举

> **状态：已完成实施（2026-10-05），评审修复已合入。** 实施事实：
> - 入口 `FrontendSnapshotCompletionService.completionCandidatesAt`（api.analysis，字节偏移与
>   行/列两个重载），结果 DTO `CompletionLookupResult`（`CompletionContextKind` +
>   可空 `QuerySourceRange` replaceableRange + `List<CompletionCandidate>`）；候选 DTO
>   `CompletionCandidate(name, CompletionCandidateKind(PROPERTY/METHOD/VALUE/TYPE), typeText,
>   signatureText)`，不携带任何内部对象。参数策略：负偏移抛 `IllegalArgumentException`
>   （编程错误），越过源尾的偏移返回空 `UNKNOWN`（与行/列越界一致）。
> - 上下文解析用单个共享 `GdParserFacade`（gdparser 每次解析新建 TSParser、实例无状态，
>   线程安全）；解析对象是**快照自身保存的源文本**（陈旧快照自洽，验收 6）；补全 CST 只
>   消费 kind/replaceableRange/receiverRange，节点身份绝不接触快照 side table。
> - **成员访问**：receiverRange 在快照 AST 中定位"末端恰好对齐 receiver 末尾且最深"的已
>   发布事实节点（含 attribute step 键）；`TYPE_META` 绑定走静态面（static 属性/方法 +
>   脚本常量/engine/builtin 类常量与枚举组及值），`SINGLETON` 绑定按实例面枚举其类，
>   `super` receiver（SUPER 绑定或裸 `super` 标识符形态）按 super 合同只枚举**词法超类
>   层次的方法**（其发布类型按合同指向当前类、不得用于枚举）；枚举组 declaration 经两条
>   通道识别——裸引用（`GdScriptClassConstant` 包装或 `CONSTANT`/`GLOBAL_ENUM` 值绑定）
>   与限定链（`CompStaticUser.State`，`AttributePropertyStep` 的 RESOLVED member 事实；
>   subscript 步骤的组 declaration 仅为容器溯源，不触发枚举值路径）；发布类型
>   `GdObjectType` 经 registry 沿超类链枚举实例面（非 static 属性/方法 + 信号；builtin
>   无层次；`Array`/`Dictionary` 参数化类型归一族名）；括号/cast receiver 按"**逐层**
>   剥离一个行尾 `)` 并重试精确末端匹配"处理（UTF-8 字节扫描；调用括号因调用节点先精确
>   命中而保留，嵌套包裹逐层解析；已命中但无可用类型事实的调用节点——`CallExpression`/
>   `AttributeCallStep`——为**终态**返回空，不会继续剥入调用参数）；无事实 receiver
>   返回空（验收 2）。
> - **遮蔽语义**：static 面层次枚举按 static resolver 的终态遮蔽——子类成员（不分
>   static）先认领名字，祖先同名 static 不泄漏；标识符路径按命名空间认领——可见值
>   （含属性）认领值命名空间、外围类方法认领函数命名空间，同名全局常量/全局函数不再
>   作为重复候选或"回退重载"出现；裸方法与 `super` 方法枚举按"**最近声明层**"逐层认领
>   方法名（ClassScope.resolveFunctionsHere/ScopeMethodResolver 对齐），祖先同名重载不
>   混入，重载仅在胜出层内按签名键共存（键含 vararg 标记）。
> - **标识符前缀**：复用 `FrontendVisibleValueEnumerator`（先字节序过滤再遮蔽；新增
>   **字节偏移重载**，declaration-after-use 的 use-site 枢轴即光标字节本身——非零宽
>   且包含光标的 replaceable 取其起点，其余（零宽或被错误恢复粘到下一 token）取原始
>   光标偏移）+ 沿 parent 索引回退的最近 scope（`nearestScope` 放开为包级私有共享）+
>   外围类层次方法（V1 不做 static 上下文过滤）+ 全局命名空间。零宽 IDENTIFIER 上下文
>   实测只出现在错误子树内（被 skipped 门拦截）。
> - **类型位置**：builtin/engine 类名 + 全局类名映射（外部输入，快照
>   `topLevelCanonicalNameMap`）+ 文件直接 inner class 与光标词法外围 inner class 的直接
>   嵌套 inner（经 `ClassDeclaration` 语句扫描）。
> - **全局命名空间枚举**：`ClassRegistry` 新增只读列表 accessor
>   `getGlobalConstantList`/`getGlobalEnumList`/`getGdScriptLanguageConstantList`/
>   `getGdScriptLanguageFunctionList`（既有只有按名查询）。
> - **skipped/error 子树门**：光标节点（含祖先）命中 `skippedSubtreeRoots` 即返回空
>   （部分链不标 skipped，成员路径不受影响）；`CALL_ARGUMENT` 分类但 V1 不产候选。
> - **扩展元数据联合类型**：`ExtensionGdClass` 属性惰性类型解析曾会对联合元数据（如
>   `CanvasItem.material`）抛 `IllegalArgumentException`。该问题已由**联合类型 NCA 映射
>   特性**根治（加载期按 dump 继承链预计算映射，见
>   `scope_type_resolver_implementation.md` §3.1），补全层曾设置的 Variant 降级保底
>   已随之移除——未进表的未知元数据在补全路径同样保持原有严格失败行为。
> - **gdparser 0.6.0 实测限制与对策**：① 行尾 `receiver.` 的补全 replaceableRange 可能
>   覆盖**下一语句**的首 token——服务统一做"replaceable 必须包含光标"净化，否则钳制为
>   光标处零宽（`AstUnitIndex.queryRangeAt` 按行索引投影任意字节区间）；② 部分链后接同
>   函数下一条语句时 receiver 事实保留（补全可用），位于函数尾且后接下一 `func` 时
>   receiver 事实静默丢失（补全返回空，验收 2 形态）；③ 行尾 `call().`（如
>   `v.normalized().`）映射为 ErrorStatement 且补全上下文分类可能退化为
>   UNKNOWN/IDENTIFIER——该形态不承诺成员候选。
> - 验收 1-6 由 `CompletionServiceTest`（29 例）锚定：self./v./make()./链式/括号/cast/
>   super receiver、成员前缀 replaceable 覆盖（含光标在 token 内部与末尾两形态）、
>   嵌套调用 receiver 止于调用节点（含 FAILED 调用终态空）、static 面排除非 static 成
>   员与终态遮蔽、裸/限定枚举组值、枚举组 subscript 走结果类型、无类型/无事实 receiver
>   空、可见值+全局命名空间+declaration-after-use 排除+按名遮蔽（PI/len）、裸方法与
>   super 方法的最近声明层遮蔽（hide(x)/act 重载）、scope 回退、类型位置四类来源、
>   skipped 子树空（光标节点祖先命中 skipped root 且 IDENTIFIER 上下文仍空）、陈旧快照
>   自洽（含旧文本 replaceable 区间断言）、未知路径/越界偏移 UNKNOWN、负偏移抛异常、
>   行/列重载一致；BUILTIN 夹具以 CJK 注释锚定 UTF-8 字节正确性。

内容：§2.5（接入 gdparser 补全上下文 + 三类候选枚举）。

验收：

1. `obj.`（Object 派生 receiver）候选含 metadata 声明的 property/method；
   `vec.`（builtin）候选含 `x`/`y` 等成员；`factory().` 与链式 receiver 正确解析类型；
   `obj.pa|r`（光标在完整成员名前缀内）返回成员候选且 replaceableRange 覆盖 `par`。
2. receiver 被错误结构吞掉（如无类型事实）时返回空候选，不抛异常。
3. 函数体内裸标识符前缀：候选含可见局部变量（排除 declaration-after-use）、参数、
   类成员、全局常量；光标落在无 scope 叶子时沿 parent 回退正确。
4. 类型标注位置：候选含全局类名映射与 engine 类型。
5. 光标落在 skipped/error 子树内（未完成成员访问部分链除外）：空候选，无异常。
6. 陈旧快照按其自身源文本给出自洽候选。

### Phase 6：收尾

内容：确认各阶段已同步的文档无遗漏（`frontend_rules.md`、`diagnostic_manager.md`、
`scope_analyzer_implementation.md`、`rpc_api_implementation.md`、
`json_rpc_service_implementation.md`），本计划文档状态置为"已实施"。

验收：`./gradlew clean build --no-daemon --info --console=plain` 全绿；
文档与代码行为逐项一致。

---

## 6. 风险与缓解

1. **analyzer 对残缺 AST 的 fail-fast 点数量未知。**
   缓解：Phase 2 以测试驱动逐个加固（覆盖 var/if/for/match/lambda/参数默认值各形态）；
   坚持"错误节点只跳过、不抛异常"口径；未预期异常整次失败且不发布半成品（R14）。
2. **patch/export 非原子**：body 分析中 patch 冲突会丢弃整个 `FrontendAnalysisData`。
   容错路径不得把"错误子树"误判为 patch 冲突；发布前校验数据对象处于稳定终态。
3. **身份键跨代误用**：查询层所有入口强制以快照为唯一入口；补全解析产出的新一代
   CST 只用于分类与 range，禁止与快照 AST 混用。
4. **快照可变对象泄漏**：`FrontendAnalysisData`/`ClassRegistry` 原对象不直接暴露，
   只读视图 + 测试锁死。
5. **内存保留**：每模块一份快照持有整代 AST 与 side table；编辑器场景体积可控；
   如有压力后续加快照 LRU（不改变合同）。
6. **gdparser 版本联动**：Phase 2 依赖 0.6.0 发布；两仓库按 Phase 顺序推进。
7. **并发测试确定性**：使用包级私有执行 seam 控制阻塞与乱序，避免 flaky 的
  `sleep` 式测试。

## 7. 明确排除项（防止范围蔓延）

- LSP 服务端、JSON-RPC 查询方法、诊断推送（publishDiagnostics）。
- `FrontendBinding` 的 read/write/call 用法分类。
- 类型位置引用的 usages 统计、未绑定标识符/DEFFERED 站点的 usages 统计。
- 增量解析与增量语义分析。
- rename、code action、hover 富文本等编辑器上层能力（由未来 LSP 服务端
  基于本计划的查询原语实现）。
- 编译期间的 VFS 写阻塞解除（维持现状，仅分析不再占闩）。
