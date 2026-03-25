# Frontend Lowering 实施计划

> 本文档作为 frontend -> LIR lowering 的实施计划，定义 lowering 的公共入口、`FrontendLoweringPassManager` 与多 pass 架构、v1 skeleton-only 目标、后续 CFG/body lowering 的开通顺序，以及每一步的验收细则。

## 文档状态

- 状态：规划中（v1 目标：`FrontendModule -> analyzeForCompile -> LirModule(class skeleton only)`）
- 更新时间：2026-03-25
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/lir/**`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/lir/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `runtime_name_mapping_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `scope_architecture_refactor_plan.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/gdcc_low_ir.md`
- 明确非目标：
  - v1 不实现 function body basic block / CFG / instruction lowering
  - v1 不接受 `FrontendModuleSkeleton`、`FrontendAnalysisData` 或 `LirClassDef` 列表作为 public lowering 输入
  - v1 不绕过 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
  - v1 不解除 compile-only gate 对当前 blocker 的封口
  - v1 不在 lowering 里重做 class header discovery、scope 构建或 body semantic analyzer 逻辑

---

## 1. 当前事实与约束

### 1.1 当前 frontend 已具备的 lowering 前置产物

当前 frontend 已经能够稳定发布以下产物：

- `FrontendModule`
  - 作为 frontend 当前统一模块输入
  - 冻结 `moduleName`、`units`、`topLevelCanonicalNameMap`
- `FrontendAnalysisData`
  - 作为共享 semantic side-table 容器
- `FrontendModuleSkeleton`
  - 保留 source relation、top-level canonical mapping 与 skeleton diagnostics
  - `allClassDefs()` 可直接返回当前 module 贡献的全部 `LirClassDef`
- `LirClassDef`
  - 已由 skeleton phase 填入 canonical class identity、canonical superclass、signals、properties、functions、`sourceFile`

这意味着 lowering 不需要重新发现类、重新注册类或重新拼装 class/member skeleton。v1 应直接复用 frontend 现成 skeleton 产物，而不是在 lowering 里复制一套 header/member build 逻辑。

### 1.2 当前 lowering-ready 边界

当前 frontend 事实已经冻结：

- 共享 `FrontendSemanticAnalyzer.analyze(...)` 不是 lowering-ready 合同
- frontend -> LIR lowering 必须以 `FrontendSemanticAnalyzer.analyzeForCompile(...)` 为入口
- compile-only gate 仍会阻断当前未接通 lowering/backend 的 surface

因此 lowering manager 的公共入口必须内建 analyze-for-compile phase，而不是要求调用方先手工传入 `FrontendAnalysisData` 或绕开 compile-only gate。

### 1.3 当前 LIR 对 skeleton-only 输出的允许范围

`doc/gdcc_low_ir.md` 与现有 `DomLirSerializer` 共同固定了以下边界：

- `LirModule` 由 `moduleName + List<LirClassDef>` 组成
- `LirFunctionDef` 只有在真的存在 basic blocks 时，才要求显式 `entryBlockId`
- 没有 basic blocks 的函数在当前 LIR 中是合法的 skeleton-only 状态

因此 v1 只产出 class skeleton 是合法且可序列化的中间状态，不需要为了“形式完整”伪造空 `entry` block。

### 1.4 当前 compile blocker 与未来 lowering 的关系

当前被 compile-only gate 显式封口的内容，不能在 lowering 中偷偷放行：

- `assert`
- `ConditionalExpression`
- `ArrayExpression`
- `DictionaryExpression`
- `PreloadExpression`
- `GetNodeExpression`
- `CastExpression`
- `TypeTestExpression`
- 脚本类 `static var`

此外，frontend MVP 仍未完整支持：

- `lambda`
- `for`
- `match`
- 参数默认值
- block-local `const`
- signal coroutine use-site（`await` / `.emit(...)`）

这些边界在对应 lowering pass 真正落地、测试齐备、文档同步之前，必须继续由 compile-only gate 封口。

---

## 2. 目标模型

### 2.1 公共入口

v1 lowering 的公共入口固定为：

- `FrontendLoweringPassManager`

当前建议的 public API 形状：

```java
public @Nullable LirModule lower(
        @NotNull FrontendModule module,
        @NotNull ClassRegistry classRegistry,
        @NotNull DiagnosticManager diagnosticManager
)
```

其中：

- `FrontendModule` 是唯一 source payload
- `ClassRegistry` 与 `DiagnosticManager` 仍沿用 frontend 现有共享基础设施
- `null` 表示 lowering 未能产生产物
- 源码错误继续通过 `DiagnosticManager` 表达，而不是用异常当常规控制流

当前不计划新增以下 public overload：

- `lower(FrontendModuleSkeleton ...)`
- `lower(FrontendAnalysisData ...)`
- `lower(List<LirClassDef> ...)`

这样可以强制所有 lowering 统一经过 compile-only gate，而不是绕开 frontend 已冻结的 lowering-ready 边界。

### 2.2 pass manager 结构

`FrontendLoweringPassManager` 负责：

1. 创建 lowering 上下文
2. 按固定顺序执行多个 `FrontendLoweringXXXPass`
3. 在 diagnostics 出现 error 后停止后续 lowering pass
4. 返回最终 `LirModule` 或 `null`

当前建议的内部结构为：

- package-private `FrontendLoweringContext`
  - 保存 module、registry、diagnostic manager、analysisData、lirModule、stop flag
- package-private `FrontendLoweringPass`
  - 单一方法：`void run(FrontendLoweringContext context)`

这里引入 pass 协议是合理的，因为 lowering 从第一天起就不是单 pass，而是明确要拆成 analysis、module/class skeleton emission、CFG、body lowering、finalize 等多个阶段。该协议应保持 package-private，不升级为额外 public 扩展点。

### 2.3 v1 pass 列表

v1 只落以下两个 pass：

1. `FrontendLoweringAnalysisPass`
2. `FrontendLoweringClassSkeletonPass`

其中：

- `FrontendLoweringAnalysisPass`
  - 调用 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
  - 把 `FrontendAnalysisData` 发布进 lowering context
  - 若 diagnostics 已有 error，则标记 pipeline stop
- `FrontendLoweringClassSkeletonPass`
  - 从 `analysisData.moduleSkeleton()` 读取全部 class skeleton
  - 产出 `new LirModule(moduleName, classDefs)`
  - 不处理 function body
  - 不创建 basic blocks

### 2.4 v1 输出合同

v1 输出的 `LirModule` 必须满足：

- `moduleName == FrontendModule.moduleName()`
- `classDefs` 顺序与 `FrontendModuleSkeleton.allClassDefs()` 一致
- 每个 `LirClassDef` 继续保持 skeleton phase 已经写好的：
  - canonical `name`
  - canonical `superName`
  - `signals`
  - `properties`
  - `functions`
  - `sourceFile`
- 所有 `LirFunctionDef` 维持 skeleton-only 状态：
  - `basicBlockCount == 0`
  - `entryBlockId` 保持空

v1 不应 clone 这些 `LirClassDef`。当前计划保持“同一组 class/function skeleton 对象被后续 lowering pass 原地补全 body”的策略，避免重复复制 skeleton metadata、避免 source/canonical contract 漂移，也避免未来 body lowering 需要再把修改回写两份对象。

---

## 3. 分步骤实施计划

### 3.1 第 1 步：建立 lowering 包与 pass manager 框架

实施内容：

- 新建 `src/main/java/dev/superice/gdcc/frontend/lowering/`
- 引入：
  - `FrontendLoweringPassManager`
  - `FrontendLoweringContext`
  - `FrontendLoweringPass`
- 固定 pass manager 的执行框架：
  - 构造 pass 列表
  - 依序执行
  - 若 context 已 stop，则停止后续 pass
  - 最终返回 `context.lirModule()`

约束：

- `FrontendLoweringContext` 必须是 package-private 内部实现，不暴露为外部 API
- pass 列表顺序必须固定且可测试，不允许靠反射扫描或命名约定隐式装配
- 不引入额外的 plugin / extension registry

验收细则：

- 编译通过
- 只有一个 lowering public entrypoint
- manager 在空成功路径下能够完成一次 pass 执行并返回 `null`
- manager 在某个 pass 标记 stop 后，不再执行后续 pass
- 新增单元测试至少覆盖：
  - pass 按顺序执行
  - stop 后短路
  - context 中间状态可被后续 pass 观察

### 3.2 第 2 步：实现 `FrontendLoweringAnalysisPass`

实施内容：

- 在 pass 中统一调用 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
- 把 `FrontendAnalysisData` 写入 context
- 若 diagnostics 已有 error，则：
  - 不抛普通源码异常
  - 直接标记 stop
  - 不允许后续 skeleton emission pass 继续执行

约束：

- lowering 不得改用共享 `analyze(...)`
- lowering 不得接受调用方预先传入的 `FrontendAnalysisData`
- 该 pass 不得创建 `LirModule`

验收细则：

- happy path：
  - `FrontendModule` 正常进入 analysis pass
  - context 中成功发布 `FrontendAnalysisData`
  - `analysisData.moduleSkeleton()` 可读
- negative path：
  - 含 `assert` / `static var` / `ConditionalExpression` 的脚本会被 compile-only gate 挡下
  - manager 返回 `null`
  - 后续 pass 不执行
  - diagnostics category 继续保持 `sema.compile_check`

建议测试：

- `FrontendLoweringAnalysisPassTest`
  - `lower_compileReadyModule_publishesAnalysisDataAndContinues`
  - `lower_compileBlockedModule_stopsAfterAnalysisPass`

### 3.3 第 3 步：实现 `FrontendLoweringClassSkeletonPass`

实施内容：

- 读取 `analysisData.moduleSkeleton()`
- 使用 `moduleSkeleton.moduleName()` 与 `moduleSkeleton.allClassDefs()` 构造 `LirModule`
- 把结果写入 context

约束：

- 只消费 skeleton 已发布事实，不重新扫描 AST
- 不重新构建 `LirClassDef`
- 不修改 class/member skeleton 的 canonical/source 合同
- 不补充任何 basic block

验收细则：

- happy path：
  - 输出 `LirModule.moduleName` 与 `FrontendModule.moduleName` 一致
  - 输出类顺序与 `moduleSkeleton.allClassDefs()` 一致
  - top-level 与 inner class 全部进入 module
  - mapped top-level / inner class 继续保持 canonical `name/superName`
  - `sourceFile`、properties、signals、functions 全部保留
- negative path：
  - 若 analysis pass 没有成功发布 `analysisData`，该 pass 必须 fail-fast 为 invariant error，而不是 silent fallback
  - 若 diagnostics 已有 error 且 manager 已 stop，该 pass 不应被执行

建议测试：

- `FrontendLoweringClassSkeletonPassTest`
  - `lower_emitsModuleSkeletonFromCompileReadyFrontendModule`
  - `lower_preservesMappedTopLevelAndInnerCanonicalNames`
  - `lower_preservesFunctionSkeletonsWithoutBasicBlocks`
  - `lower_preservesClassOrderAndSourceFileMetadata`

### 3.4 第 4 步：补齐 v1 集成回归与序列化锚点

实施内容：

- 为 `FrontendLoweringPassManager` 增加 v1 集成测试
- 用 `DomLirSerializer` 验证 skeleton-only `LirModule` 可以稳定序列化
- 明确“函数没有 basic block 仍是合法中间态”的测试锚点

约束：

- 不伪造空 `entry` block
- 不为了通过 serializer 测试而给每个函数硬塞 `ReturnInsn`

验收细则：

- happy path：
  - 一个 compile-ready `FrontendModule` 能 lower 出 `LirModule`
  - `DomLirSerializer` 成功序列化
  - 输出 XML 只包含 class/member skeleton，不包含 basic blocks
- negative path：
  - compile-blocked module 不产生 `LirModule`
  - serializer 集成测试不会在 blocked path 上被误执行

建议测试：

- `FrontendLoweringPassManagerTest`
  - `lower_compileReadyModule_returnsSerializableSkeletonOnlyLirModule`
  - `lower_compileBlockedModule_returnsNullAndKeepsDiagnostics`

---

## 4. v1 完成后的稳定合同

v1 完成后，frontend lowering 必须稳定满足以下事实：

- lowering public input 的 source payload 只有 `FrontendModule`
- lowering 内部统一走 `analyzeForCompile(...)`
- compile-only gate 仍是 lowering 唯一合法前置门
- `FrontendModuleSkeleton` 是 class skeleton 唯一事实源
- v1 产物是 `LirModule(class skeleton only)`，不是 body-complete module
- function 没有 basic blocks 属于合法中间态
- diagnostics 仍是普通源码错误主路径
- lowering pass 之间通过内部 context 协作，不向外暴露中间态对象

---

## 5. 后续阶段路线图

### 5.1 第 5 步：建立 per-function lowering scaffold

目标：

- 在不生成真实 CFG 的前提下，为每个 `LirFunctionDef` 建立 body lowering 工作入口
- 引入 function-local lowering context，但继续保持 package-private

实施内容：

- 增加 `FrontendLoweringFunctionPreparationPass`
- 为每个 compile-ready function 收集：
  - owning class
  - source AST declaration
  - published scope / binding / member / call / expression type facts
- 建立 function-level lowering worklist

验收细则：

- happy path：
  - 所有 top-level / inner class function 都可进入 worklist
  - `_init`、普通函数、static function 都能被区分
- negative path：
  - compile gate 已挡下的 subtree 不会进入 function lowering worklist

### 5.2 第 6 步：实现最小 CFG lowering

目标：

- 为当前 MVP 已支持的 executable body 建立最小 basic block / CFG

第一批只支持：

- 空函数
- `pass`
- 直线型 `return`
- `if / elif / else`
- `while`

当前明确不纳入：

- `for`
- `match`
- `lambda`
- `assert`
- `ConditionalExpression`

实施内容：

- 引入 `FrontendLoweringCfgPass`
- 为 function 创建：
  - `entry` block
  - 需要时的 branch / merge / loop blocks
- 生成 `GotoInsn` / `GoIfInsn` / `ReturnInsn`
- 在首次写入 basic block 时同步设置 `entryBlockId`

验收细则：

- happy path：
  - 空函数至少拥有合法 entry / return 结构
  - `if / elif / else` 与 `while` 的 block shape 可预测且有回归测试锚定
- negative path：
  - 未实现结构不被 silent drop
  - 若 compile gate 尚未解除，对应脚本仍在 analysis pass 被挡下

### 5.3 第 7 步：实现 MVP 支持面的 statement / expression lowering

目标：

- 打通当前 frontend 已稳定发布事实的 MVP surface

优先顺序：

1. identifier / literal / unary / binary
2. bare/global call
3. member call / property access
4. assignment
5. supported property initializer island

实施内容：

- 引入 `FrontendLoweringBodyInsnPass`
- 严格消费已发布的：
  - `symbolBindings`
  - `resolvedMembers`
  - `resolvedCalls`
  - `expressionTypes`
- 不在 lowering 里重新推导语义

验收细则：

- happy path：
  - lowering 只依赖 frontend 已发布 facts，不再做第二套 binder
  - 输出 instruction 的类型与 owner 路径和 frontend 已发布事实一致
- negative path：
  - 对缺失或冲突的 published fact 采用 invariant fail-fast
  - 不因为 backend 已有某条指令就跳过 frontend 事实校验

### 5.4 第 8 步：按实现进度解除 compile gate blocker

只有在以下三个条件同时满足后，才允许从 compile-only gate 中移除某一类 blocker：

1. lowering 已稳定产生产物
2. backend 已能消费该产物
3. 文档与正反测试都已同步更新

建议解除顺序：

1. `ConditionalExpression`
   - 依赖 CFG 合同先稳定
2. `assert`
   - 依赖 lowering/backend 的 statement 语义
3. `ArrayExpression` / `DictionaryExpression`
   - 依赖 construct 指令与 typed container contract
4. `CastExpression` / `TypeTestExpression`
   - 依赖 runtime type / cast lowering 设计
5. `GetNodeExpression` / `PreloadExpression`
   - 依赖具体 runtime integration 决策
6. 脚本类 `static var`
   - 依赖 backend 对静态字段的完整支持

### 5.5 第 9 步：MVP 之后的剩余 backlog

以下内容即使 body lowering 初步落地，也继续保持 post-MVP：

- `for`
- `match`
- `lambda`
- 参数默认值
- block-local `const`
- signal coroutine use-site（`await` / `.emit(...)`）
- property initializer 中依赖实例状态的完整初始化时序语义

这些内容必须单独立项，不得在已有 lowering pass 中用“局部放行 + 少量特判”混入。

---

## 6. 测试与验收总则

lowering 相关实施必须继续遵守以下测试规则：

- 每个 pass 至少同时覆盖 happy path 与 negative path
- negative path 至少锚定：
  - diagnostics category
  - pipeline 是否 stop
  - 是否禁止继续产生产物
- 只支持 skeleton-only 的阶段，不得写“看起来更完整”的假 CFG 测试
- 一旦开始生成 basic block，必须同时测试：
  - `entryBlockId`
  - terminator 完整性
  - serializer / parser round-trip
- 凡是解除 compile gate blocker 的提交，都必须同时更新：
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - 本文档
  - 对应 lowering / backend 测试

---

## 7. 当前建议的测试文件布局

- `src/test/java/dev/superice/gdcc/frontend/lowering/FrontendLoweringPassManagerTest.java`
- `src/test/java/dev/superice/gdcc/frontend/lowering/FrontendLoweringAnalysisPassTest.java`
- `src/test/java/dev/superice/gdcc/frontend/lowering/FrontendLoweringClassSkeletonPassTest.java`
- 后续新增：
  - `src/test/java/dev/superice/gdcc/frontend/lowering/FrontendLoweringCfgPassTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`

若需要验证 XML/LIR 兼容性，应优先复用 `src/test/java/dev/superice/gdcc/lir/parser/**` 现有 serializer / parser 测试模式，而不是另写一套 ad-hoc 文本断言。

---

## 8. 实施顺序建议

建议按以下顺序推进，且每完成一步就单独提交可运行状态：

1. `FrontendLoweringPassManager` / context / pass contract
2. `FrontendLoweringAnalysisPass`
3. `FrontendLoweringClassSkeletonPass`
4. v1 集成回归与 serializer smoke test
5. function preparation
6. minimal CFG
7. MVP statement / expression lowering
8. compile gate blocker 按顺序解除

在第 4 步完成前，不要开始写 body lowering。先把 `FrontendModule -> analyzeForCompile -> skeleton-only LirModule` 这条最小链路做成稳定事实，能显著降低后续 CFG 和 instruction lowering 的返工成本。
