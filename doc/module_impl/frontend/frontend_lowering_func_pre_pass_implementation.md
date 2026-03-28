# Frontend Lowering Function Pre-Pass 实现说明

> 本文档作为 frontend lowering 中 function pre-pass 的长期事实源，记录当前已经稳定落地的 function-shaped lowering scaffold、`FunctionLoweringContext` 合同、preparation pass 行为边界、shell-only `LirModule` 约束，以及对后续 CFG/body lowering 仍然有效的架构反思。本文档替代原 `frontend_per_function_lowering_scaffold_plan.md` 的已完成实施内容，不再保留阶段切分、完成记录或验收流水账。

## 文档状态

- 状态：事实源维护中（function pre-pass 已落地；当前稳定产物为 compile-ready executable/property initializer context scaffold）
- 更新时间：2026-03-28
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/pass/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/test/java/dev/superice/gdcc/frontend/lowering/**`
- 关联文档：
  - `frontend_lowering_plan.md`
  - `frontend_lowering_pre_pass_implementation.md`
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `runtime_name_mapping_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `doc/gdcc_low_ir.md`
- 明确非目标：
  - 当前实现不生成 function body basic block / CFG / instruction
  - 当前实现不解除 compile-only gate blocker
  - 当前实现不让 parameter default 进入 compile-ready lowering surface
  - 当前实现不把无 initializer 的 property 变成 frontend-generated init shell

---

## 1. 当前定位

function pre-pass 是 `FrontendLoweringAnalysisPass` 与 `FrontendLoweringClassSkeletonPass` 之后的第三个固定 lowering pass。它只负责把 compile-ready 的 function-shaped lowering 单元整理成统一 scaffold，供后续 CFG/body lowering 继续消费。

当前稳定覆盖的 lowering 单元只有两类：

- `EXECUTABLE_BODY`
- `PROPERTY_INIT`

`PARAMETER_DEFAULT_INIT` 当前只保留模型槽位与合同，不实际收集。

这意味着 function pre-pass 当前不是 “开始 lowering body” 的入口，而是 “冻结后续 body lowering 入口形状” 的入口。

---

## 2. 稳定输入与输出

当前 function pre-pass 的稳定前置输入为：

- `FrontendModule`
- `FrontendSemanticAnalyzer.analyzeForCompile(...)` 发布的 `FrontendAnalysisData`
- `FrontendModuleSkeleton` 已发布的 `LirClassDef` / `LirFunctionDef` / `LirPropertyDef`
- `FrontendLoweringClassSkeletonPass` 已发布的 `LirModule`

当前稳定输出为：

- `FrontendLoweringContext` 上发布的 `List<FunctionLoweringContext>`
- 必要时追加到 owning class 上的 hidden property-init synthetic function shell

pre-pass 不新增新的 public lowering 入口，也不接受 `FrontendAnalysisData`、`FrontendModuleSkeleton` 或 `List<LirClassDef>` 作为替代输入。

---

## 3. `FunctionLoweringContext` 合同

`FunctionLoweringContext` 是 lowering 内部统一的函数级 scaffold。它当前固定承载：

- lowering unit 的 `kind`
- source file path
- source-class relation
- owning `LirClassDef`
- target `LirFunctionDef`
- declaration-level `sourceOwner`
- 实际 lowering root
- compile-ready `FrontendAnalysisData`

当前固定 kind 集合为：

- `EXECUTABLE_BODY`
- `PROPERTY_INIT`
- `PARAMETER_DEFAULT_INIT`

冻结边界：

- executable callable 使用 declaration-level owner，lowering root 是 body `Block`
- property initializer 使用 property declaration 作为 owner，lowering root 是 initializer expression
- future parameter default 使用 parameter/default declaration 作为 owner，lowering root 只指向 default-value expression
- 后续 pass 必须统一经由 `FunctionLoweringContext.analysisData()` 读取：
  - `scopesByAst()`
  - `symbolBindings()`
  - `expressionTypes()`
  - `resolvedMembers()`
  - `resolvedCalls()`
- 后续 pass 不应再次扫描 `FrontendModuleSkeleton` 去重建 callable/property 到 target function 的映射

---

## 4. 固定 Pipeline 位置

当前默认 pipeline 顺序固定为：

1. `FrontendLoweringAnalysisPass`
2. `FrontendLoweringClassSkeletonPass`
3. `FrontendLoweringFunctionPreparationPass`

`FrontendLoweringFunctionPreparationPass` 的职责固定为：

- 读取 `context.requireAnalysisData()` 与 `context.requireLirModule()`
- 基于 `FrontendModuleSkeleton.sourceClassRelations()` 建立 AST owner 到 owning class/source relation 的 identity 索引
- 为 compile-ready executable callable 发布 `EXECUTABLE_BODY` context
- 为 supported property initializer 发布 `PROPERTY_INIT` context
- 在 `LirPropertyDef.initFunc` 缺失时补 `_field_init_<property>` hidden synthetic function shell
- 当 `LirPropertyDef.initFunc` 已预先指向 synthetic shell 时，只在 shell 仍满足 property-signature 与 shell-only 合同时复用

`FrontendLoweringFunctionPreparationPass` 明确不负责：

- 写 basic block
- 设置 `entryBlockId`
- 分配 lowering temp variable
- 生成 instruction
- truthiness normalization
- parameter default context 收集

---

## 5. Executable Callable 收集边界

当前 compile-ready `EXECUTABLE_BODY` context 覆盖：

- top-level script class function
- inner class function
- static function
- instance function
- constructor `_init`

当前固定行为：

- executable callable 继续复用 skeleton phase 已发布的同一份 `LirFunctionDef`
- preparation 不重新发现 callable，不 clone `LirFunctionDef`
- inner class callable 通过 AST owner identity 索引解析到对应 owning class

当前 fail-fast guard rail 包括：

- `analysisData` 未发布
- `lirModule` 未发布
- callable owner scope 缺失
- callable body scope 缺失
- skeleton function 缺失或歧义
- indexed class skeleton 不属于当前 published `LirModule`
- executable function 已经偏离 shell-only 状态

其中 “偏离 shell-only 状态” 的含义固定为：

- `basicBlockCount != 0`
- 或 `entryBlockId` 非空

preparation 不接受任何已经进入 CFG/body 形状的 executable function 继续被当作 scaffold 消费。

---

## 6. Property Initializer 收集边界

当前只有 supported property initializer 会进入 `PROPERTY_INIT` context。它的最小支持面固定为：

- `VariableDeclaration(kind == VAR && value != null)`
- declaration scope 为 `ClassScope`

当前固定行为：

- property declaration 作为 `sourceOwner`
- initializer expression 作为 `loweringRoot`
- `LirPropertyDef.initFunc` 缺失时，frontend pre-pass 生成 hidden synthetic shell，并回写 `_field_init_<property>`
- `initFunc` 已存在时，只在现有 shell 仍满足以下条件时复用：
  - hidden
  - static flag 与 property 一致
  - return type 与 property type 一致
  - shell-only
  - static property 不声明参数
  - instance property 恰好声明一个名为 `self`、类型为 owning class 的参数

当前明确不做的事情：

- 无 initializer 的 property 不生成 `PROPERTY_INIT` context
- 无 initializer 的 property 不在 frontend pre-pass 里回写 `initFunc`
- 无 initializer 的 property 仍保留给 backend 默认值路径兜底
- property init shell 的存在不表示完整 class member initialization ordering 已经闭环

---

## 7. Parameter Default 冻结合同

parameter default 当前仍不在 compile-ready lowering surface。

但当前架构已经固定以下合同：

- future parameter default lowering 单元仍必须走 `FunctionLoweringContext`
- `FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT` 必须保留
- `sourceOwner` 保留原始 parameter/default declaration AST 节点
- `loweringRoot` 只指向 default-value expression
- 对应 synthetic function 最终由 `LirParameterDef.defaultValueFunc` 引用
- 不允许把 parameter default permanently 设计成 call-site inline-only 路线

当前实现与测试锚定的边界是：

- compile-only analysis 仍会拦下 parameter default
- preparation pass 不会发布 `PARAMETER_DEFAULT_INIT` context
- 但现有 context 形状无需改动即可承载未来实现

---

## 8. Shell-Only `LirModule` 合同

function pre-pass 结束后，`LirModule` 仍必须保持 shell-only 中间态：

- 所有函数 `basicBlockCount == 0`
- 所有函数 `entryBlockId` 为空
- serializer 不需要也不允许因为函数无 body 而伪造空 CFG

允许的唯一结构性变化是：

- 在 owning class 上追加 hidden property-init synthetic shell
- 对应 property 的 `initFunc` 指向该 shell

当前 no-initializer property 的边界也固定为：

- serializer 输出中不应出现由 frontend pre-pass 生成的 `init_func="_field_init_<property>"`
- serializer 输出中不应出现对应 `_field_init_<property>` function shell

---

## 9. 回归锚点

涉及 function pre-pass 合同的改动，至少要继续覆盖以下回归锚点：

- compile-ready executable/property-init context 发布
- context 总数与 kind 分布
- executable callable 对应 owning class / target function / constructor `_init`
- property initializer 对应 owning class / property / target init function
- property `initFunc` 是否回写成功
- property shell 是否 hidden 且保持 shell-only
- context 是否直接暴露 compile-ready `analysisData()` side tables
- compile-blocked module 是否在 analysis 后停止
- `functionLoweringContexts` 是否在 compile-blocked 时保持未发布
- `LirModule` 是否仍能被 serializer 接受为 shell-only 中间态
- no-initializer property 是否仍然不由 frontend pre-pass 生成 shell

negative path 至少要锚定：

- 缺失 published fact 时 fail-fast
- shell-only contract 被破坏时 fail-fast
- 不存在 silent skip 掩盖协议破坏

---

## 10. 架构反思与后续风险

当前实现已经验证了 function-shaped lowering 单元的统一入口模型，但以下问题仍属于后续工程风险：

- callable skeleton 匹配键未来可能需要从 `name + static + parameterCount` 升级
- property initializer 的完整执行时序、实例状态可见性和初始化顺序仍未闭环
- parameter default 的可见性、捕获、求值顺序，以及 instance-vs-static synthetic function 策略仍待专门设计
- `ConditionalExpression` 等依赖 CFG 的结构，必须等最小 CFG lowering 稳定后再放行
- truthiness / condition normalization 属于后续 CFG/body lowering 责任，不应下沉回 function pre-pass
- backend 目前仍保留 no-initializer property 的默认 init shell 兜底；未来若要把这部分前移到 lowering，需要先统一 frontend/backend 对“默认值函数所有权”的合同

这些问题不应反向污染当前 function pre-pass 的事实边界。后续工程应在现有 scaffold 之上继续推进，而不是回退到“每个后续 pass 各自重建 callable/property 索引”的分叉设计。
