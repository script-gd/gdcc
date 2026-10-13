# Frontend LSP 基础设施实现说明

> 本文档是"编译前端作为 LSP 实现基础"改造的长期事实源，覆盖容错语义分析、不可变语义快照、
> 分析并发模型、光标查询与补全候选枚举的既定合同。本文档替代原
> `frontend_lsp_foundation_plan.md`，不保留分阶段实施记录、验收清单或评审流水账。

## 文档状态

- 状态：已实施，事实源维护中（容错语义分析、语义快照与并发发布、光标查询服务、
  补全候选枚举均已落地；gdparser 依赖已升级至 0.6.0）
- 更新时间：2026-10-07
- 适用范围：
    - `src/main/java/gd/script/gdcc/frontend/**`
    - `src/main/java/gd/script/gdcc/api/**`
    - `src/main/java/gd/script/gdcc/rpc/**`（方法名与请求 schema 不变；`analyze.run` 响应
      增加 `moduleGeneration`/`snapshotVersion`，并发行为按 §2.3.3 合同）
    - 外部依赖 `com.github.SuperIceCN:gdparser` 0.6.0（独立仓库）
- 关联文档：
    - `doc/module_impl/common_rules.md`
    - `doc/module_impl/frontend/frontend_rules.md`
    - `doc/module_impl/frontend/diagnostic_manager.md`
    - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
    - `doc/module_impl/frontend/scope_analyzer_implementation.md`
    - `doc/module_impl/api/rpc_api_implementation.md`
    - `doc/module_impl/api/json_rpc_service_implementation.md`
- 明确非目标：
    - 不实现 LSP 协议服务端（didOpen、publishDiagnostics 等），不在 JSON-RPC 方法面新增
      语义查询方法（查询接口为进程内 Java API，RPC 查询方法留待 LSP 服务端迭代）。
    - 不改变 compile 任务的门闩语义：编译仍独占模块门闩全程，编译期间 `putFile` 仍阻塞；
      仅保证**分析**期间写不阻塞，编译与分析互不等待、各读各的冻结快照。
    - 不做增量解析与增量语义分析（每次分析全量重跑；增量化是后续优化）。
    - 不扩展 `FrontendBinding` 的 read/write/call 用法分类。
    - 不把 AST 对象图或身份键暴露到 RPC wire format。
    - 不做 rename、code action、hover 富文本等编辑器上层能力（由未来 LSP 服务端基于
      本文档的查询原语实现）。

---

## 1. 背景与目标

### 1.1 改造前的问题

1. 解析错误使整模块失去语义结果：parser 已能容错返回部分 AST 与带范围诊断，但
   `AnalysisRunner` 汇总解析诊断后只要存在一条 ERROR 就跳过整个模块的语义分析。
2. 没有 LSP 可消费的语义快照与光标查询接口：`FrontendSemanticAnalyzer.analyze(...)`
   返回的完整 `FrontendAnalysisData` 被 `AnalysisRunner` 默认路径丢弃。
3. 分析在模块门闩上串行：分析期间同模块 `vfs.putFile` 等写操作全部阻塞，且分析排在
   已预约编译之后。
4. 无法处理光标处未完成语法与成员候选枚举：gdparser 0.5.5 没有补全上下文 API，CST 的
   `ERROR`/`MISSING` 结构只映射为诊断。

### 1.2 既定目标（当前能力）

1. 解析存在错误时，语义分析仍对**未受损子树**（含受损文件内的健康子树）正常运行并
   发布事实。
2. API 层按模块保留**不可变语义快照**（AST + `FrontendAnalysisData` + 诊断 + 源文本 +
   版本号），并提供进程内 Java 查询接口：按光标位置查符号定义、用法、类型与成员候选。
3. 分析基于 VFS 冻结快照在模块门闩外执行；同模块分析期间 `putFile` 等写操作不被阻塞；
   同模块多个分析可并行，结果按版本单调发布；分析不等待已预约/进行中的编译。
4. gdparser 0.6.0 提供错误节点映射、未完成成员访问的部分链保留与补全上下文提取，
   GDCC 基于此实现成员候选枚举。
5. 编辑器插件可经 JSON-RPC 频繁 `vfs.putFile`；RPC 方法名与请求 schema 不变。

---

## 2. 总体设计

### 2.1 语义快照 `ModuleAnalysisSnapshot`

位于 `gd.script.gdcc.api.analysis` 的不可变对象，由 `API.analyze(...)` 发布：

- 身份：`moduleGeneration` + `snapshotVersion`（均 long，见 §2.3；代际标识模块删除重建，
  版本标识同代内的内容代）、`moduleId`、`godotVersion`、`topLevelCanonicalNameMap`。
- 源视图：每源单元的 logicalPath / displayPath / 源文本（直接引用冻结的
  `SourceSnapshot`，不复制字符串）与**源单元级解析失败标记**（见 §2.2.5）。
- 同一代的 `FrontendModule`（AST）与 `FrontendAnalysisData`（语义 side table 全集），
  以及本次分析私有的 `ClassRegistry`（每次分析经 `ExtensionApiLoader.loadVersion` 新建，
  skeleton 期间 `addGdccClass`，发布后即冻结）。
- 最终 `DiagnosticSnapshot`（含 parse + sema 全部诊断，已 remap 到 displayPath；
  **不含** compile-only `sema.compile_check`——快照永远来自共享 `analyze(...)` 路径，
  见 §2.2.6）。

不可变性合同（freeze-on-publish 加只读视图两道防线）：

- 快照构造时立即做**结构性冻结**：`FrontendAnalysisData.freeze()` 级联冻结全部
  side table 与 provenance 索引，`ClassRegistry.freeze()` 关闭
  `addGdccClass`/`removeGdccClass`。side table / provenance 的冻结闭合覆盖一切写通道：
  直接方法、`Map.Entry.setValue`、`replaceAll`（side table 覆写以避免被默认实现包装成
  CME）、iterator/view 移除，以及**冻结前获取的视图**（`FreezableIdentityMap` 组合式
  容器在操作时查验冻结位）；发布后对这些容器的任何修改尝试抛异常。
  `ClassRegistry.freeze()` 只关闭类成员变更——`getVirtualMethods`/
  `getEngineVirtualMethods` 的惰性缓存在冻结后仍可填充（`ConcurrentHashMap`，并发读
  安全），这是刻意保留的读路径缓存，不是冻结漏洞。
- AST 对象图深不可变：gdparser 0.6.0 在 AST record 构造时冻结子节点集合
  （`List.copyOf`，见 §3）。
- 容器内可达的模型对象（`Scope`/`ClassDef` 等自有修改器不受容器冻结覆盖）只能经
  **无修改器视图**访问：`Scope` 一律经 `ReadOnlyScope` 门面（`setParentScope` 抛
  `UnsupportedOperationException`，返回值递归包装；`resolveValue`/`resolveFunctions`/
  `resolveTypeMeta` 在原始 scope 上直接委托以保留 `ClassScope` 跳过连续外层类 scope 的
  语义；`valuesHere()` 返回 `List.copyOf` 快照），`ClassDef` 窄化为只读接口，查询结果
  DTO-only（模型对象不出 `api.analysis` 包）。结构性冻结是视图之下的第二道防线。
- usages 反向索引等惰性结构存放在快照自有的线程安全 memo（`queryMemo`：
  `ConcurrentHashMap` + 单次计算），**不写回** `FrontendAnalysisData`。
- 快照内所有 side table 的键都属于**同一代 AST**；查询只允许在快照内部闭合，
  不得把快照 A 的 AST 节点拿去查快照 B 的表。
- 每模块最多保留一个最新快照（`AtomicReference`），按 §2.3.2 规则条件发布；
  旧快照由调用方引用自然 GC，不做主动失效。

### 2.2 容错语义分析（子树级打捞）

核心思路：把 parser 产生的错误结构接入 frontend 已有的 "diagnostic + skip subtree"
恢复合同（`frontend_rules.md`），并补齐该合同的覆盖缺口。语义入口把未预期异常视为
整次分析失败（`INTERNAL_FAILED`），**绝不**把半提交的 `FrontendAnalysisData` 放进快照
（patch transaction 非原子，见 §4）。

#### 2.2.1 gdparser 侧两类错误形态（0.6.0）

- **确缺成员名的成员访问**（`obj.`，`.` 后无任何标识符）：映射为**正常
  `AttributeExpression` 链 + 缺失末步标记**（`MissingAttributeStep`），错误仅作为
  `parse.lowering` 诊断存在，不产错误节点、**不**标注 skipped——receiver 前缀保持可
  绑定与定型，补全可读其 `expressionTypes`。`obj.par` 是合法完整语法，普通解析没有
  光标信息，**不得**产缺失标记或诊断；"正在输入"由补全上下文 API 按光标偏移判定（§3）。
  部分链归约的 FAILED 事实保留，但根表达式诊断被抑制（parser 的 `Missing identifier`
  保持诊断单一 owner）。
- **其余 `ERROR`/`MISSING` 结构**：映射为专用错误节点（`ErrorStatement`/
  `ErrorExpression`），携带 range 与 issue kind；sibling 映射继续。

#### 2.2.2 错误子树标注（scope 之前的独立步骤）

`FrontendErrorSubtreeAnnotator`（静态工具）在 skeleton 发布后、scope 前遍历整棵 AST
（含函数体、控制流、match、lambda、参数默认值 island）：

- 错误节点向上提升到**最小 statement 或 declaration 根**，写入 `skippedSubtreeRoots()`。
- 只含错误表达式的合法语句（`var x = <err>`、`if <err>:`）同样提升到该语句根。
- 空名 match 绑定（gdparser 把 `var :` 映射为 `name=""` 的 `PatternBindingExpression`
  而非错误节点）视为损坏，标记所属 statement 根；
  `FrontendMatchSupport.collectPatternBindingsInto` 再防御性跳过空名。
- 类成员位置的错误节点走与 enum 拒绝相同的 `markSkippedSubtreeRoots` 路径。
- 不新发诊断（parser 已发 `parse.lowering`），保持诊断单一 owner。

#### 2.2.3 scope 与 body 消费 skipped 合同

- `FrontendScopeAnalyzer`：**任何** handler（含 `IfStatement`/`WhileStatement`/
  `ForStatement`/`MatchStatement` 等专用 handler）进入节点前先检查 skipped 根，
  命中即 `SKIP_CHILDREN`，不发布该根及后代的 `scopesByAst`。annotation usage visitor
  同样不进入 skipped 根；`FrontendGdAnnotation.sourceStatement` 被标注的投影不再追加
  参数校验诊断。
- `FrontendSuiteResolver` 逐句循环与 `FrontendStatementResolver` 防御入口：解析每条
  statement 前先查 `skippedSubtreeRoots()`，命中即整句跳过，**不得**再
  `requireBlockScope` 或深入子结构（残缺语句深入会触发结构性 fail-fast，并因 patch
  非原子拖垮整次分析）。
- expression island 入口（参数默认值 sweep、属性初始化器根）不经过 suite 逐句解析，
  各自在进入表达式前消费 skipped 根：命中时跳过的是**默认值表达式或该属性声明**，
  而不是整个 callable——不得把 `Parameter`/`FunctionDeclaration` 整体标为 skipped 根。
  参数默认值 island 命中即回收元数据并静默返回。
- `FrontendVariableAnalyzer` 不为无 scope 的声明建 locals；
  `FrontendTypeCheckAnalyzer.walkStatements` 消费 skipped 根。
- 幻影参数（`func f(x = 1 +)`）覆盖在 parse 诊断范围内时抑制
  `sema.invalid_parameter_default_order`。

#### 2.2.4 analyzer 防御性加固

- 对"假设 well-formed AST"的入口做防御性改造：遇到错误节点/缺失子结构按跳过处理，
  不抛异常；guard rail 异常仅保留给 programmer error / 协议不变量破坏。
- 任何错误形态下管线不向调用方抛异常；parse/sema 未预期异常 → `INTERNAL_FAILED`
  且无载荷。

#### 2.2.5 `parse.internal` 源单元的显式失败状态

parser 异常路径返回空 `SourceFile`，但 skeleton 会为**每个**源单元合成顶层类头——
空 AST 不会天然退出语义分析，反而会注册虚构的空脚本类污染跨文件引用。因此：

- `FrontendSourceUnit.parseFailed` 显式标记 `parse.internal` 单元；skeleton 整体排除
  失败单元（不合成顶层类头、不收集 annotation），保留其 `parse.internal` 诊断与源视图。
- 其他文件引用失败单元的类时走正常 `sema.type_resolution` 未解析诊断，不得命中虚构
  类声明。
- 查询服务（nodeAt/definitionAt/usagesAt/typeAt/documentationAt）对失败单元一律返回空
  结果，不抛 NPE（其诊断 range 允许为 null）；补全对失败单元仍回传上下文 kind 与净化后
  的 `replaceableRange`，仅候选列表为空（见 §2.5）。

#### 2.2.6 与 lowering 路径的分工

- `AnalysisRunner` 无解析错误短路：无条件运行共享 `FrontendSemanticAnalyzer.analyze(...)`，
  其结果即快照内容；包级入口 `analyzeRich` 返回 `AnalysisRunResult`（公开
  `AnalysisResult` + COMPLETED 时的语义载荷）。包级 `SemanticRun`/`LoweringRun` seam
  与 API 层 `AnalysisRunSeam`（构造器注入）供失败注入与并发确定性测试，不加生产开关。
- 解析诊断含 ERROR 且 `includeLowering=true`：**只**跑共享 `analyze(...)`，不调用
  `analyzeForCompile`/`FrontendLoweringPassManager.lower`，`loweringStatus=FAILED`，
  结果诊断中不得出现 `sema.compile_check`。
- 无解析错误且 `includeLowering=true`：快照仍取自共享 `analyze(...)`；lowering 验证用
  **独立新建的 `ClassRegistry` 与独立的 `DiagnosticManager`** 另跑 `lower(...)`——
  `lower` 内部 `analyzeForCompile` 会重跑 skeleton，`ClassRegistry.addGdccClass` 的替换
  语义会改写共享 registry 并使快照事实的对象身份失配，因此必须与快照那一代完全隔离
  （opt-in 验证模式接受第二次语义分析的开销）。诊断口径：`AnalysisResult.diagnostics`
  取 lowering 运行口径以保留 compile 验证语义；快照诊断取共享 `analyze(...)` 口径，
  不含 compile-only 诊断。lowering manager 运行前**仅导入本次解析阶段的诊断一次**
  （无解析错误不等于无解析警告），不得导入共享语义阶段的诊断（lower 会重跑语义并
  重新产生）。
- 共享 `analyze(...)` 成功后，lowering 验证的诊断失败或未预期异常只影响
  `loweringStatus`（记 `FAILED`）与结果诊断，**不作废**已稳定快照的发布；lower 抛出
  异常时 runner 捕获，outcome 仍为 `COMPLETED`，并补发单条 `sema.lowering` error
  携带异常原因（可观测性）。

#### 2.2.7 gdparser 0.6.0 实测形态锚点

- `if :`/`for i in :`/`match :`/`return = 3` → 错误表达式落在条件/迭代/值位置。
- `var x = true if  else false`（函数尾）与 `var hp = (1`（类尾）→ `ErrorStatement`。
- `func f(x = 1 +)` → 幻影 Parameter（无错误节点；参数默认值 island 的 skipped 消费
  是纯防御路径，由手写 AST 单测锚定）。
- `var x = self.`（函数尾）→ `MissingAttributeStep` 部分链 + `Missing identifier`
  诊断，不标 skipped。
- 0.6.0 无法从真实源码产生"仅 WARNING 无 ERROR"的 parse 诊断形态；lowering 诊断保留
  解析警告的口径由"无重复锚点 + 代码审查"覆盖。

### 2.3 VFS 内容版本与并发模型

#### 2.3.1 `contentVersion` 与 `moduleGeneration`

- `ModuleState.contentVersion` 单调递增，且**只在变更实际落地后递增**：`putFile`、
  `deletePath`、`createDirectory`、`createLink`、`options.set`、`classMap.set` 成功时
  递增；幂等 `createDirectory`（目录已存在）与失败的 `createLink`/`deletePath`
  （根路径、类型冲突、路径不存在、非空目录）不推进版本（`ApiContentVersionTest`
  锚定）。写路径采用"试探性放置 + 完整回滚"：target 语法与 `displayPath` 校验在创建
  父目录之前，链接放置后在拟提交树上 `inspectLink`，失败即回滚被替换的叶节点与本次
  自动创建的全部祖先目录——覆盖文件形成的合法自引用（CYCLE broken link）行为不变，
  不可解析目标干净失败。
- 编译产物挂载（`prepareOutputPublication`/`mountCompileOutputs`）只挂非 `.gd` 链接、
  不进入分析源集合，**不计入** `contentVersion`（走内部非递增变体；若未来挂载会影响
  源集合则必须递增）。编译产物清理同样走非递增内部删除路径。managed 输出目录排他性
  由强制守卫落实：清理前发现 `.gd`/`.gd3` 源码条目即让编译以 `CONFIGURATION_FAILED`
  响亮失败，不再静默删除同版本源码。指向输出目录的外部 `VIRTUAL` 别名在清理-重挂载
  窗口的瞬时 broken 是已知瞬态（冻结失败但版本对不变，调用方可检测重试）。
- `freezeCompileRequest()` 把当前 (代际, 版本) 捕获进冻结请求；`AnalysisResult` 对
  **所有** outcome（含 `SOURCE_COLLECTION_FAILED`/`INTERNAL_FAILED`）都填冻结时捕获的
  版本对（紧凑构造器校验代际为正、版本非负），使调用方只持有单次结果也能可靠判断
  陈旧度。
- `moduleGeneration` 由 `API.createModule` 从全局单调计数器分配并写入 `ModuleState`，
  模块删除不回收计数；同 id 重建得到新代际。陈旧度判断必须同时比较**代际 + 内容
  版本**——只比版本会在删除重建后误判（新模块版本从低位重新计数）。快照与
  `getModuleContentVersion(moduleId)` 都返回 (代际, 版本) 对。

#### 2.3.2 分析三段式（不占模块门闩）

1. **冻结段**：仅在 `ModuleState` 自身的 `synchronized` 边界内执行
   `freezeCompileRequest()`（VFS 写方法同为该锁，冻结原子性由 monitor 保证）。
   **不进入** `ManagedModule.busy`，**不等待** `queuedCompileTaskId`——已排队/进行中的
   编译不阻塞分析冻结。同时捕获当前 `ManagedModule` 实例引用。
2. **闩外执行段**：`AnalysisRunner` 对冻结请求做 parse + sema，不持任何闩。gdparser
   0.6.0 线程安全（每次 parse 新建 `TSParser`、`CstToAstMapper` 无状态），
   `GdScriptParserService` 保持共享实例，不新建 facade。
3. **发布段**：在 `ManagedModule` 的同步边界内做**条件发布**——仅当（a）注册表中当前
   实例与冻结段捕获的实例**为同一对象**（防止删除后同 id 重建的模块收到旧分析），
   （b）该实例未删除，（c）新版本**严格大于**已发布版本时，原子替换
   `AtomicReference<ModuleAnalysisSnapshot>`；否则丢弃。同版本的并发分析内容等价
   （tree-sitter 解析对同一输入是确定性的），先发布者胜出，后到者丢弃快照但仍向各自
   调用方返回自己的 `AnalysisResult`。已删除模块上发布是**无操作**，不是异常。

`getModuleContentVersion`/`getLatestAnalysisSnapshot` 在 `ManagedModule` monitor 内
复核注册表实例身份与删除标记，消除删除/重建窗口读到上一代快照或版本对的读侧竞态。
`API.close()` 不等待在途分析。

#### 2.3.3 并发合同

- `vfs.putFile` 等写操作仍走 `ModuleState` 锁（短暂），只与冻结段竞争，不再被整次
  分析阻塞。
- 分析不等待编译；编译仍独占门闩全程，编译期间写操作行为不变。
- 同模块多个分析并行（各自读各自的不可变冻结请求，analyzer 每次新建）；跨模块分析
  天然并行。
- RPC 面：`analyze.run` 响应增加 `moduleGeneration`/`snapshotVersion`；解析错误时结果
  仍为 `COMPLETED` 且可能含 `sema.*`；`analyze.run` 不再因编译阻塞（详见
  `rpc_api_implementation.md` 与 `json_rpc_service_implementation.md`）。

### 2.4 光标查询服务

`gd.script.gdcc.api.analysis.FrontendSnapshotQueryService`：全部方法静态、以
`ModuleAnalysisSnapshot` 为第一参数、纯读取。

- **发布期索引**：快照构造时一次性构建 `SnapshotAstIndex`（按 normalized logicalPath
  配对 `SourceView` 与 unit；`parseFailed` 单元建空索引，查询恒空）与每单元
  `AstUnitIndex`（parent / range / line 三索引；line 索引按 UTF-8 字节累计换算
  (0 基行, 0 基字节列) 入参；`byteOffsetAt` 先比行内长度再相加以防整数溢出）。发布期
  遍历时同步填充 `unitOf`（`IdentityHashMap<Node, AstUnitIndex>`），反向索引构建为
  O(站点)。AST 无父指针，查询不得重建树遍历。
- `nodeAt`（公开）：从 `SourceFile` 根按 `Node.range()` 字节偏移下行定位**最深**覆盖
  节点。覆盖判定为半开区间 `[startByte, endByte)`；零宽 range 不覆盖任何偏移，永远不
  被选中；同 span 取最深节点。AST 图深不可变故节点只读暴露给外部包，而 side table 与
  registry 保持包级私有，节点身份键无法在外部跨代解析。光标停在标识符**之后**时由
  调用方/适配层换算到标识符内偏移（补全场景不经过 nodeAt，走 §2.5 上下文 API）。
  失败单元/无覆盖返回空。
- `definitionAt`：声明来源归一化——`symbolBindings()` 的 `declarationSite`、
  `resolvedMembers()`/`resolvedCalls()` 的声明来源**不保证是带 range 的 AST 节点**
  （可为 `PropertyDef`、重载 `List<? extends FunctionDef>`、合成构造器等模型对象）。
  skeleton 期发布**声明溯源索引** `FrontendAnalysisData.declarationOrigins()`
  （`IdentityHashMap<Object, FrontendDeclarationOrigin>`：声明模型对象身份 → 同代 AST
  声明节点 + 单元 logicalPath；displayPath 由快照层 remap）。归一化规则（
  `DeclarationNormalizer`）：AST 节点直接用；单个模型对象查溯源索引；集合型 provenance
  逐元素归一化，**按元素序**返回全部可归一化元素的源码位置，无法归一化的元素**按原
  元素位置**以 null-location 显式标记——不得擅自挑选其一、不得丢弃其余元素、不得把
  失败标记挪到列表尾部；全部元素无法归一化才归 `EXTERNAL`（engine/builtin metadata
  等外部声明同为此类）；null provenance 归 `NONE`。单元素集合且可归一化（如方法引用
  恒为 `List[1]`）归并 `SINGLE_SOURCE`；`MULTIPLE_CANDIDATES` 保留给真正有歧义的
  provenance。
- `usagesAt`：反向索引惰性构建于 `queryMemo.computeIfAbsent`（单次计算线程安全）。
  **先归一化再分组**：三张表的每个站点先归一化到稳定源码声明身份，再按该身份
  （`IdentityHashMap`）分组；集合型 provenance 的站点计入每个可归一化元素。member/call
  站点**按状态过滤**：仅 `RESOLVED`/`BLOCKED` 入索引（BLOCKED 保留 blocked-winner
  provenance 作为真实使用意图）；`DEFERRED`/`UNSUPPORTED`/`FAILED`/`DYNAMIC` 按状态
  缺席——它们携带的声明载荷（如失败 `ClassName.member` 为诊断记录的 receiver 类）不是
  使用意图。绑定站点仅当归一化失败（null provenance、外部声明、集合元素全部失败）时
  缺席。缺席集合含未绑定标识符、`DEFERRED`/`UNSUPPORTED` 站点与类型位置引用
  （`TYPE_META` 按 kind 过滤；构造调用步骤如 `QueryBase.new()` 的 `.new()` 仍合法归组，
  类型位置头站点本身绝不出现）。排序以 endByte/行/列 tiebreak 成全序。
- `typeAt`：先 `expressionTypes()`（键空间含 attribute step，查询必须覆盖 step 键）后
  `slotTypes()`；返回类型显示名文本；`publishedType` 为 null 的状态
  （`DEFERRED`/`FAILED`/`UNSUPPORTED`）与无事实节点返回空。
- `documentationAt`：把光标处符号归一成 `SymbolDocDescriptor`（`DocSymbolKind` /
  `DocNamespace` / 属主名 / 成员名 / `sourceCandidates`），供编辑器拼接官方文档 URL
  或展示归属。归一化规则：
    - `GDCC` 用户符号：复用 `definitionAt` 的完整声明归一化规则；`sourceCandidates`
      按元素序承载全部候选源码位置，不可定位项以 null-location 候选按原位置显式保留。
      多候选 provenance 的 `ownerName` 取**首个可定位候选**的声明类（provenance 序即
      解析序，最近声明优先）。描述符身份**不替代** `usagesAt` 的源码声明身份分组键。
      `GdScriptClassConstant` 包装解包到枚举声明，并按所携枚举声明分类
      （`ENUM_VALUE`/`ENUM_GROUP` 而非 `CONSTANT`）。
    - `ENGINE`/`BUILTIN` 类成员：属主取**实际声明类**。已发布事实的 `declarationSite`
      只保留成员级元数据（`PropertyDef`/`SignalDef`/成员 `PropertyInfo` 等），不保留
      共享 resolver 中间结果的 `ownerClass`；查询期按成员类别用 registry 既有层次查找
      补齐属主（`findEngineClassConstantInHierarchy`/
      `findEngineClassEnumValueInHierarchy`/`findBuiltinClassConstantInHierarchy`/
      `findBuiltinClassEnumValueInHierarchy`/`findPropertyInHierarchy`/
      `findEngineSignalInHierarchy`，入口接收类名，由 `receiverType` 取名）。对已解析的
      方法调用/引用：沿 receiver 类链按**已选中 `FunctionDef` 的对象身份**定位声明
      类——不得重新执行重载选择，也不得按最近同名方法推断属主。builtin 属性的
      `PropertyInfo` 逐次合成（无跨次对象身份），属主直接取 receiver 的 builtin 类。
    - 全局元数据（全局常量、全局枚举组/枚举值、GDScript 语言常量）**没有实际声明类**，
      按 registry provenance 判定文档归属命名空间，不得用其值类型推断属主；
      `@GlobalScope`/`@GDScript` 是文档归属命名空间名称，不是 `ClassDef` 类名。全局
      枚举值经 `findGlobalEnumValueByBareName` 身份比对区分 @GlobalScope 与类枚举。
      engine/builtin 类枚举值当前经类域静态成员路径解析；GDCC 前端不解析这两类的
      枚举组名、不发布组级解析事实，故枚举组描述符只覆盖 GDCC 枚举组与全局枚举组。
    - 工具函数按 registry 来源分类：`ExtensionUtilityFunction` 中 dump 来源归
      `@GlobalScope`；registry 合成的 GDScript 语言函数（`len`/`range`/`load` 等）归
      `@GDScript`——经 `isGdScriptLanguageFunction`/`findGdScriptLanguageFunction`
      判定，不得按 `ownerKind`（两类同为 ENGINE）或函数名名单区分。
    - `FrontendResolvedCall` 的分类字段是 `callKind` 而非 `bindingKind`（utility 调用
      发布为 `STATIC_METHOD` route，裸绑定才是 `UTILITY_FUNCTION`）。
    - 引擎元数据不含文档文本（GDExtension dump 无描述字段）；描述符只产出分类 + 归属
      命名空间 + 属主名 + 成员名，文档 URL 锚点规则由编辑器/适配层负责。
    - `definitionAt` 的"外部声明"只表示无源码位置，不表示无文档归属：外部符号照常产出
      描述符，但不进入 `usagesAt` 的源码身份分组。
    - 未绑定标识符、`FAILED`/`DEFERRED`/`UNSUPPORTED`/`DYNAMIC`/`BLOCKED` 站点返回空
      结果，不抛异常；绑定侧的 BLOCKED 形态是 `FOUND_BLOCKED` 值绑定（声明前使用、
      参数默认值 island），同样返回空。
- 坐标约定：入参接受字节偏移或 (0 基行, 0 基列)；内部规范形式为字节偏移。返回位置
  统一 displayPath + 1 基行列 range（与诊断一致）+ 字节偏移（`QuerySourceRange`）；
  UTF-16 列换算属未来 LSP 适配层职责。

### 2.5 补全候选枚举

`FrontendSnapshotCompletionService.completionCandidatesAt`（字节偏移与行/列两个重载），
结果 DTO `CompletionLookupResult`（`CompletionContextKind` + 可空 `QuerySourceRange`
replaceableRange + `List<CompletionCandidate>`）；候选 DTO
`CompletionCandidate(name, CompletionCandidateKind(PROPERTY/METHOD/VALUE/TYPE),
typeText, signatureText)`，不携带任何内部对象。参数策略：负偏移抛
`IllegalArgumentException`（编程错误）；越过源尾的偏移返回空 `UNKNOWN`（与行/列越界
一致）。

1. **补全上下文解析**：单个共享 `GdParserFacade`（gdparser 每次解析新建 `TSParser`、
   实例无状态，线程安全）；解析对象是**快照自身保存的源文本**（陈旧快照自洽）。补全
   解析产出**新一代 CST**，只消费 `kind`/`replaceableRange`/`receiverRange`，节点身份
   绝不接触快照 side table。`replaceableRange` 做"必须包含光标"净化，否则钳制为光标处
   零宽（`AstUnitIndex.queryRangeAt` 按行索引投影任意字节区间）。
2. **成员访问**：receiverRange 在快照 AST（同一代）中定位"末端恰好对齐 receiver 末尾
   且最深"的已发布事实节点（含 attribute step 键）；命中则按类型枚举成员：
    - `TYPE_META` 绑定走静态面（static 属性/方法 + 脚本常量/engine/builtin 类常量与
      枚举组及值）；`SINGLETON` 绑定按实例面枚举其类；`super` receiver（SUPER 绑定或
      裸 `super` 标识符形态）只枚举**词法超类层次的方法**（其发布类型按合同指向当前
      类，不得用于枚举）。
    - 枚举组 declaration 经两条通道识别：裸引用（`GdScriptClassConstant` 包装或
      `CONSTANT`/`GLOBAL_ENUM` 值绑定）与限定链（`AttributePropertyStep` 的 RESOLVED
      member 事实；subscript 步骤的组 declaration 仅为容器溯源，不触发枚举值路径）。
    - 发布类型 `GdObjectType` 经 registry 沿超类链枚举实例面（非 static 属性/方法 +
      信号；builtin 无层次；`Array`/`Dictionary` 参数化类型归一族名）。
    - 括号/cast receiver 按"**逐层**剥离一个行尾 `)` 并重试精确末端匹配"处理（UTF-8
      字节扫描；调用括号因调用节点先精确命中而保留，嵌套包裹逐层解析；已命中但无可用
      类型事实的调用节点——`CallExpression`/`AttributeCallStep`——为**终态**返回空，
      不继续剥入调用参数）。
    - 命中失败（receiver 被错误结构吞掉、无类型事实）返回空候选列表，不抛异常。
3. **遮蔽语义**：static 面层次枚举按 static resolver 的终态遮蔽——子类成员（不分
   static）先认领名字，祖先同名 static 不泄漏；标识符路径按命名空间认领——可见值
   （含属性）认领值命名空间、外围类方法认领函数命名空间，同名全局常量/全局函数不再
   作为重复候选或"回退重载"出现；裸方法与 `super` 方法枚举按"**最近声明层**"逐层认领
   方法名（`ClassScope.resolveFunctionsHere`/`ScopeMethodResolver` 对齐），祖先同名重载
   不混入，重载仅在胜出层内按签名键共存（键含 vararg 标记）。
4. **标识符前缀**：复用 `FrontendVisibleValueEnumerator`（先字节序过滤再遮蔽；字节
   偏移重载的 use-site 枢轴即光标字节本身——非零宽且包含光标的 replaceable 取其起点，
   其余（零宽或被错误恢复粘到下一 token）取原始光标偏移）+ 沿 parent 索引回退到最近
   有 scope 的祖先 + 外围类层次方法（不做 static 上下文过滤）+ 全局命名空间。零宽
   IDENTIFIER 上下文实测只出现在错误子树内（被 skipped 门拦截）。
   `Scope` 枚举 API：`valuesHere()`/`collectVisibleValues()`/`enumerationParentScope()`；
   declaration-after-use 字节序过滤中 `Parameter` 节点豁免，其余所有
   `VariableDeclaration`（含 CAPTURE）按声明顺序过滤。
5. **类型位置**：builtin/engine 类名 + 全局类名映射（快照 `topLevelCanonicalNameMap`）
   + 文件直接 inner class 与光标词法外围 inner class 的直接嵌套 inner（经
   `ClassDeclaration` 语句扫描）。
6. **全局命名空间枚举**：`ClassRegistry` 只读列表 accessor `getGlobalConstantList`/
   `getGlobalEnumList`/`getGdScriptLanguageConstantList`/`getGdScriptLanguageFunctionList`。
7. **skipped/error 子树门**：光标节点（含祖先）命中 `skippedSubtreeRoots` 即返回空候选
   （部分链不标 skipped，成员路径不受影响）；上下文 kind 与净化后的 `replaceableRange`
   仍照常回传，供适配层定位空补全请求。`CALL_ARGUMENT` 分类但不产候选。

---

## 3. gdparser 0.6.0 依赖合同与已知限制

GDCC 依赖 gdparser 0.6.0 的下列能力（均为已发布合同，升级时需逐项回归）：

1. **缺失成员名的部分链映射**：`receiver.` 映射为正常 `AttributeExpression` 链 +
   `MissingAttributeStep` 缺失末步标记，receiver 前缀保持可分析；`MISSING` 诊断照旧。
   `receiver.par` 是完整语法，按普通链映射、不产生任何标记或诊断。
2. **错误节点映射**：`ErrorStatement`/`ErrorExpression` record 携带 `Range`、
   `CstIssueKind`（`ERROR`/`MISSING`）与原始 CST 片段文本；`CstToAstMapper` 遇到其余
   CST `ERROR`/`MISSING` 节点构造错误节点，不再用 `requireField` 猜测补齐；sibling
   映射继续；`AstMappingResult` 诊断照旧携带错误节点 range。
3. **MISSING 诊断增强**：诊断消息带期望 token/符号名。
4. **补全上下文 API**：`parseCompletionContext(String source, long byteOffset)` →
   `CompletionContext`：`kind`（`MEMBER_ACCESS`/`IDENTIFIER`/`TYPE_POSITION`/
   `CALL_ARGUMENT`/`UNKNOWN`）；`replaceableRange` 为光标所在标识符的完整 range（过滤
   前缀由调用方按光标偏移从起点截取）；`receiverRange` 为 `MEMBER_ACCESS` 时 `.` 左侧
   片段的字节 range。语法完整的成员访问（光标落在成员名前缀内，如 `obj.pa|r`）也必须
   能沿正常 attribute CST 判定 `MEMBER_ACCESS`。
5. **线程安全**：每次 parse 新建 `TSParser`，`GdParserFacade`/`CstToAstMapper` 实例
   无状态——GDCC 侧共享 facade 实例，不加锁池化。
6. **AST 集合冻结**：AST record 构造时对子节点集合做 `List.copyOf` 防御性冻结，
   保证发布后的 AST 对象图不可变（§2.1 不可变合同的前提）。
7. Tree-sitter 增量重解析（edit + reparse 复用旧树）**未纳入**，列为后续性能优化；
   当前全量重解析（单文件解析为毫秒级，可接受）。

实测限制与 GDCC 侧对策：

- 行尾 `receiver.` 的补全 `replaceableRange` 可能覆盖**下一语句**的首 token——服务
  统一做"replaceable 必须包含光标"净化，否则钳制为光标处零宽。
- 部分链后接同函数下一条语句时 receiver 事实保留（补全可用）；位于函数尾且后接下一
  `func` 时 receiver 事实静默丢失（补全返回空）。
- 行尾 `call().`（如 `v.normalized().`）映射为 `ErrorStatement` 且补全上下文分类可能
  退化为 `UNKNOWN`/`IDENTIFIER`——该形态不承诺成员候选。

---

## 4. 工程 guard rail 与反思

1. **patch/export 非原子**：body 分析中 patch 冲突会丢弃整个 `FrontendAnalysisData`。
   容错路径不得把"错误子树"误判为 patch 冲突；未预期异常一律整次失败
   （`INTERNAL_FAILED`）且不发布半成品数据。
2. **身份键跨代误用**：查询层所有入口强制以快照为唯一入口；补全解析产出的新一代
   CST 只用于分类与 range，禁止与快照 AST 混用；`nodeAt` 公开仅因 AST 图深不可变、
   节点身份键无法在外部跨代解析，side table 与 registry 始终保持包级私有。
3. **快照可变对象泄漏**：仅靠"发布即只读"的约定不成立——`FrontendAnalysisData` 的
   getter 返回活表、`ClassRegistry` 有公开修改方法、`Scope.setParentScope` 无冻结闸。
   因此必须维持两道防线：结构性冻结（freeze-on-publish，覆盖全部写通道含冻结前获取
   的视图）+ 无修改器视图/DTO-only 查询结果。改动任一防线时不得削弱另一层。
4. **`contentVersion` 只在变更实际落地后递增**：幂等或失败的写操作推进版本会让在途
   分析被无谓判定陈旧；新增写路径时必须遵守"试探性放置 + 完整回滚"模式。
5. **并发测试确定性**：用包级私有执行 seam（`AnalysisRunSeam`/`SemanticRun`/
   `LoweringRun`）控制阻塞与乱序，禁止 `sleep` 式 flaky 测试。并发 usages 测试必须
   用未做过 usages 查询的新快照制造真实首次竞态，且先逐 `Future.get()` 收集全部工作
   线程结果（传播异常），主线程最后才计算期望值比对——防止主线程抢先构建 memo。
6. **内存保留**：每模块一份快照持有整代 AST 与 side table；编辑器场景体积可控，如有
   压力后续加快照 LRU（不改变合同）。
7. **扩展元数据联合类型**：`ExtensionGdClass` 属性惰性类型解析曾对联合元数据（如
   `CanvasItem.material`）抛 `IllegalArgumentException`，已由联合类型 NCA 映射根治
   （加载期按 dump 继承链预计算，见 `scope_type_resolver_implementation.md` §3.1）。
   补全路径不得重新引入 Variant 降级保底——未进表的未知元数据保持严格失败行为。
8. **诊断单一 owner**：错误节点的诊断归 parser（`parse.lowering`），标注器与共享语义
   不得就同一结构重发诊断；部分链根表达式诊断与幻影参数顺序诊断按 §2.2.1/§2.2.3 的
   抑制规则执行。

---

## 5. 回归锚点

- 容错管线：`FrontendErrorRecoveryIntegrationTest`（跨文件与同文件子树级打捞、
  部分链不标 skipped、parseFailed 单元）、`FrontendErrorSubtreeAnnotatorTest`（手写
  AST 锚定提升规则与 island 规则）、`AnalysisRunnerRecoveryTest`（lowering 隔离、
  代际身份、异常收口）。
- 并发与版本：`ApiAnalysisSnapshotConcurrencyTest`（三段式、条件发布、删除重建隔离）、
  `ApiContentVersionTest`（落地才递增）、
  `ApiAnalyzeTest.analyzeDoesNotWaitForModuleGateBehindOtherOperations`（分析不等门闩）、
  `ModuleAnalysisSnapshotImmutabilityTest`（冻结合同）。
- 查询服务：`FrontendSnapshotQueryServiceTest`（nodeAt/definitionAt/usagesAt/typeAt）、
  `DocumentationAtTest`（documentationAt 归属规则）、`SnapshotQueryEdgeTest`（手工快照
  锚定集合归一化、混合集合、全失败、parseFailed 单元、FOUND_BLOCKED、
  `GdScriptClassConstant` 解包）、`ReadOnlyScopeTest`（门面保留 `ClassScope` 沿链语义）。
- 补全：`CompletionServiceTest`（成员/标识符/类型位置三类候选、遮蔽语义、skipped 门、
  陈旧快照自洽；BUILTIN 夹具含 CJK 注释锚定 UTF-8 字节正确性）、
  `FrontendVisibleValueEnumeratorTest`（Scope 枚举 API 与字节序过滤）。
- RPC wire：`RpcJsonCodecTest`、`RpcApiRoundTripHttpTest`（响应含
  `moduleGeneration`/`snapshotVersion`）。
- 测试形态注意：4.5.1 dump `global_constants` 为空，全局常量无 @GlobalScope 真实用例
  （`PI` 锚定合成语言常量的 @GDSCRIPT 路径）；未标注类型的局部变量槽为 Variant
  （builtin 成员正例需显式 `: Vector2` 标注）；`extends` 目标为标量时无标识符节点、
  无绑定事实。

## 6. 核心实现落点

- 快照与查询：`gd.script.gdcc.api.analysis`（`ModuleAnalysisSnapshot`、
  `SnapshotAstIndex`/`AstUnitIndex`、`DeclarationNormalizer`/`DeclarationLookupResult`、
  `UsagesReverseIndex`、`FrontendSnapshotQueryService`、`FrontendSnapshotCompletionService`、
  `ReadOnlyScope`、`QuerySourceRange`、`SymbolDocDescriptor` 等 DTO）。
- 发布与并发：`gd.script.gdcc.api`（`API.analyze` 三段式与条件发布、`ModuleState`
  版本计数、`AnalysisRunner.analyzeRich`、`AnalysisResult` 版本对）。
- 容错语义：`gd.script.gdcc.frontend.sema`（`FrontendErrorSubtreeAnnotator`、
  `FrontendScopeAnalyzer`/`FrontendSuiteResolver`/`FrontendStatementResolver` skipped
  消费、`FrontendClassSkeletonBuilder` 失败单元排除与 `declarationOrigins` 发布、
  `FrontendSourceUnit.parseFailed`）。
- 冻结原语：`FrontendAnalysisData.freeze()`、`ClassRegistry.freeze()`、
  `gd.script.gdcc.util.FreezableIdentityMap`。
- 补全支撑：`FrontendVisibleValueEnumerator`、`Scope.valuesHere()`/
  `collectVisibleValues()`/`enumerationParentScope()`、`ClassRegistry` 全局只读列表
  accessor。
