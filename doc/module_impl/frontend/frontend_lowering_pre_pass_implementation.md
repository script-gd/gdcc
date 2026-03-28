# Frontend Lowering Pre-Pass 实现说明

> 本文档作为 frontend pre-pass lowering 的长期事实源，记录当前已经落地的 lowering 入口、固定 pass pipeline、function lowering context scaffold、skeleton/shell-only `LirModule` 产物合同，以及与 compile-only gate、frontend skeleton 和 LIR 的边界。本文档替代原 `frontend_lowering_implementation_plan.md` 中已完成的实施部分，不再保留阶段清单、完成记录或验收流水账。

## 文档状态

- 状态：事实源维护中（pre-pass lowering 已落地；当前稳定产物为 `FrontendModule -> analyzeForCompile -> skeleton/shell-only LirModule + function lowering context scaffold`）
- 更新时间：2026-03-27
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/lir/**`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/lir/**`
- 关联文档：
  - `doc/module_impl/common_rule.md`
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `runtime_name_mapping_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/gdcc_low_ir.md`
  - `frontend_lowering_plan.md`
- 明确非目标：
  - 当前实现不生成 function body basic block / CFG / instruction
  - 当前实现不接受 `FrontendModuleSkeleton`、`FrontendAnalysisData` 或 `LirClassDef` 列表作为 public lowering 输入
  - 当前实现不绕过 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
  - 当前实现不在 lowering 中重做 class header discovery、scope 构建或 body semantic analyzer 逻辑
  - 当前实现不解除 compile-only gate 对 blocker 的封口

---

## 1. 当前已实现边界

当前 frontend lowering 已稳定实现如下最小链路：

- 输入：`FrontendModule`
- 语义前置：`FrontendSemanticAnalyzer.analyzeForCompile(...)`
- skeleton 事实源：`FrontendModuleSkeleton`
- 内部产物：
  - `LirModule(skeleton/shell-only)`
  - `FunctionLoweringContext` 集合

这条链路的含义是：

- frontend 已负责冻结 top-level mapping、class identity、canonical superclass、signals、properties、functions 与 `sourceFile`
- lowering pre-pass 只消费这些已发布事实，不重新发现类，也不再做第二套 header/member build
- lowering preparation 会为 compile-ready executable callable 建立函数级 lowering context
- lowering preparation 会为 supported property initializer 补 hidden synthetic `init_func` shell
- 当前产物合法进入 DOM/LIR serializer，但仍不是 body-complete module

---

## 2. Frontend 前置事实

当前 pre-pass lowering 依赖以下 frontend 稳定产物：

- `FrontendModule`
  - 冻结 `moduleName`、`units`、`topLevelCanonicalNameMap`
- `FrontendAnalysisData`
  - 作为 lowering 与 frontend 共享的分析结果容器
- `FrontendModuleSkeleton`
  - 保留 source relation、top-level canonical mapping、skeleton diagnostics
  - `allClassDefs()` 直接返回当前 module 贡献的全部 `LirClassDef`
- `LirClassDef`
  - 已由 skeleton phase 填入 canonical `name`、canonical `superName`、signals、properties、functions、`sourceFile`

因此当前 lowering 不需要也不允许：

- 重新扫描 AST 发现类
- 重新注册 class shell
- 重建或 clone `LirClassDef`
- 在 lowering 中修补 source/canonical identity

---

## 3. 公共入口合同

当前 lowering 唯一 public 入口固定为：

```java
public @Nullable LirModule lower(
        @NotNull FrontendModule module,
        @NotNull ClassRegistry classRegistry,
        @NotNull DiagnosticManager diagnosticManager
)
```

合同冻结为：

- `FrontendModule` 是唯一 source payload
- `ClassRegistry` 与 `DiagnosticManager` 继续沿用 frontend 已有共享基础设施
- diagnostics 仍是源码错误主路径，不用异常替代常规控制流
- 返回 `null` 表示 lowering 当前未产生产物，或已被上游 compile gate 阻断

当前不新增以下入口：

- `lower(FrontendModuleSkeleton ...)`
- `lower(FrontendAnalysisData ...)`
- `lower(List<LirClassDef> ...)`

---

## 4. 当前包结构与内部协议

### 4.1 `FrontendLoweringPassManager`

`FrontendLoweringPassManager` 负责：

1. 创建 lowering 上下文
2. 按固定顺序执行 lowering pass
3. 在 stop flag 被请求后停止后续 pass
4. 返回最终 `LirModule` 或 `null`

固定顺序是当前合同的一部分，不允许靠反射扫描、命名约定或插件注册隐式装配。

### 4.2 `FrontendLoweringContext`

`FrontendLoweringContext` 当前保存：

- `FrontendModule`
- `ClassRegistry`
- `DiagnosticManager`
- `FrontendAnalysisData`
- `LirModule`
- `FunctionLoweringContext` 集合
- stop flag

它当前为 `public`，仅为了允许具体 pass 实现驻留在 `frontend.lowering.pass` 子包。该可见性不表示 lowering 对外开放新的可扩展中间态 API。

### 4.3 `FrontendLoweringPass`

`FrontendLoweringPass` 是固定 pipeline 使用的共享协议：

```java
void run(FrontendLoweringContext context)
```

它当前同样为 `public`，原因与 `FrontendLoweringContext` 相同，只服务 lowering 内部跨子包协作，不代表 external plugin contract。

### 4.4 `frontend.lowering.pass`

当前具体 pass 实现位于：

- `FrontendLoweringAnalysisPass`
- `FrontendLoweringClassSkeletonPass`
- `FrontendLoweringFunctionPreparationPass`

`frontend.lowering.pass` 只承载固定 pipeline 的具体 pass，不承载额外注册或发现机制。

### 4.5 `FunctionLoweringContext`

`FunctionLoweringContext` 当前是 lowering 内部使用的统一函数级 scaffold，最小 kind 集合固定为：

- `EXECUTABLE_BODY`
- `PROPERTY_INIT`
- `PARAMETER_DEFAULT_INIT`

当前实现已实际发布：

- `EXECUTABLE_BODY`
- `PROPERTY_INIT`

`PARAMETER_DEFAULT_INIT` 当前只冻结模型，不实际收集。

冻结合同补充：

- future parameter default lowering 单元仍必须走 `FunctionLoweringContext`
- `sourceOwner` 应保留原始 parameter/default declaration AST 节点
- `loweringRoot` 只指向 parameter default expression
- 对应 synthetic function 最终由 `LirParameterDef.defaultValueFunc` 引用，而不是退化成 call-site inline-only 设计

---

## 5. 当前固定 pass pipeline

当前默认 pipeline 顺序固定为：

1. `FrontendLoweringAnalysisPass`
2. `FrontendLoweringClassSkeletonPass`
3. `FrontendLoweringFunctionPreparationPass`

### 5.1 `FrontendLoweringAnalysisPass`

职责：

- 调用 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
- 把 `FrontendAnalysisData` 发布进 lowering context
- 若 `analysisData.diagnostics().hasErrors()`，则请求 pipeline stop

冻结边界：

- lowering 只能从 `analyzeForCompile(...)` 结果继续
- compile-only gate 留下错误时，不允许继续进入 skeleton emission
- 该 pass 不创建 `LirModule`

### 5.2 `FrontendLoweringClassSkeletonPass`

职责：

- 读取 `context.requireAnalysisData().moduleSkeleton()`
- 使用 `moduleName + allClassDefs()` 发布 `LirModule`

冻结边界：

- 只消费 skeleton 已发布事实
- 不重新构建 `LirClassDef`
- 不 clone `LirClassDef`
- 不补充任何 basic block
- 若 analysis data 未发布，必须按 invariant fail-fast

### 5.3 `FrontendLoweringFunctionPreparationPass`

职责：

- 读取 `context.requireAnalysisData()` 与 `context.requireLirModule()`
- 基于 `FrontendModuleSkeleton.sourceClassRelations()` 建立 AST owner -> class/source relation 索引
- 为 compile-ready executable callable 发布 `EXECUTABLE_BODY` context
- 为 supported property initializer 发布 `PROPERTY_INIT` context
- 在 `LirPropertyDef.initFunc` 缺失时补 `_field_init_<property>` hidden synthetic function shell
- 当 `LirPropertyDef.initFunc` 已预先指向 synthetic shell 时，仅在该 shell 仍满足 hidden/property-signature/shell-only 合同时复用；冲突则 fail-fast

冻结边界：

- preparation pass 不写 basic block、不设置 `entryBlockId`、不生成 instruction
- executable callable 继续复用 skeleton phase 已发布的同一份 `LirFunctionDef`
- property initializer 的 synthetic shell 只建立函数壳，不代表完整初始化时序已经落地
- parameter default 仍不生成 context，但 `FunctionLoweringContext.Kind` 必须保留 `PARAMETER_DEFAULT_INIT`，且 future `default_value_func` 合同已冻结为 hidden synthetic function route

---

## 6. Skeleton/Shell-Only `LirModule` 合同

当前 lowering 输出的 `LirModule` 必须满足：

- `moduleName == FrontendModule.moduleName()`
- `classDefs` 顺序与 `FrontendModuleSkeleton.allClassDefs()` 一致
- top-level 与 inner class 全部进入 module
- 每个 `LirClassDef` 至少保持 skeleton phase 已写入的：
  - canonical `name`
  - canonical `superName`
  - `signals`
  - `properties`
  - `functions`
  - `sourceFile`
- `FrontendLoweringFunctionPreparationPass` 允许在 owning class 上追加 hidden synthetic property init shell：
  - 名称来自 `LirPropertyDef.initFunc`
  - `initFunc` 为空时当前兼容命名基线为 `_field_init_<property>`
  - 若 `initFunc` 已存在，对应 shell 必须保持 hidden、property-compatible 且无 body，才能继续复用
  - shell 仍无 basic block / `entryBlockId`
- 所有 `LirFunctionDef` 当前都保持 shell-only 状态：
  - `basicBlockCount == 0`
  - `entryBlockId` 为空

当前实现刻意复用 `FrontendModuleSkeleton` 提供的同一组 `LirClassDef`。后续 body / CFG lowering 若要补全函数内容，应继续在这组对象上原地推进，而不是复制出第二份 skeleton。

---

## 7. Compile Gate 与当前 blocker 边界

当前 compile-only gate 仍是 lowering 唯一合法前置门。以下内容仍由 compile gate 显式封口，不得在 lowering 中偷偷放行：

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

只要对应 lowering/backend 合同、实现和测试未闭环，这些边界就必须继续留在 compile gate，而不是在 lowering 中做局部放行。

---

## 8. LIR / 序列化边界

当前 `doc/gdcc_low_ir.md` 与 `DomLirSerializer` 共同固定了以下 pre-pass lowering 边界：

- `LirModule` 由 `moduleName + List<LirClassDef>` 组成
- `LirFunctionDef` 只有在真的存在 basic blocks 时，才要求显式 `entryBlockId`
- 没有 basic blocks 的函数是合法的 skeleton-only 中间态
- serializer 不应因为函数当前没有 body 而要求伪造空 `entry` block 或硬塞 `ReturnInsn`

因此，当前 skeleton-only lowering 既是合法 LIR，也是后续 CFG/body lowering 的稳定起点。

---

## 9. 回归与维护规则

涉及 pre-pass lowering 合同的改动，必须继续满足以下回归约束：

- pass 至少同时覆盖 happy path 与 negative path
- negative path 至少锚定：
  - diagnostics category
  - pipeline 是否 stop
  - 是否禁止继续产生产物
- skeleton-only 阶段不得写“看起来更完整”的假 CFG 测试
- 任何会改变 compile gate blocker 或 lowering 输入边界的改动，都必须同步更新：
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - 本文档
  - 对应 lowering / backend 测试

当前已经固定的核心回归锚点包括：

- `FrontendLoweringPassManagerTest`
- `FrontendLoweringAnalysisPassTest`
- `FrontendLoweringClassSkeletonPassTest`
- `FrontendLoweringFunctionPreparationPassTest`
- `DomLirSerializer` 对 skeleton-only module 的兼容性测试

---

## 10. 后续工程接口

后续若继续推进 lowering，不应修改本文档去记录分步骤进度，而应：

- 把未来实施顺序、待办项和解除 blocker 的计划维护在 `frontend_lowering_plan.md`
- 把当前已经稳定落地的新事实回写到本文档

判断规则固定为：

- 已落地并被测试锚定的当前合同：写入本文档
- 尚未实现、尚未冻结或只是一条工程推进顺序：写入 `frontend_lowering_plan.md`
