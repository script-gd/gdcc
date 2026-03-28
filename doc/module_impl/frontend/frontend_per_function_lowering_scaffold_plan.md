# Per-Function Lowering Scaffold 实施计划

> 本文档作为 `frontend_lowering_plan.md` 第一步 “per-function lowering scaffold” 的细化实施计划，定义当前阶段的目标、分步骤落地顺序、每一步的验收细则，以及与 compile-only gate、skeleton-only `LirModule`、property `init_func`、parameter `default_value_func` 和后续 CFG/body lowering 的边界。

## 文档状态

- 状态：计划维护中（Step 1 / Step 2 已落地；用于指导后续 executable/property context 扩展、parameter default 合同冻结与 body lowering 接线）
- 更新时间：2026-03-27
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/pass/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/lir/**`
  - `src/test/java/dev/superice/gdcc/frontend/lowering/**`
- 当前事实源：
  - `frontend_lowering_plan.md`
  - `frontend_lowering_pre_pass_implementation.md`
  - `frontend_rules.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_variable_analyzer_implementation.md`
  - `frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/gdcc_low_ir.md`
- 直接非目标：
  - 不在本阶段生成真实 basic block / CFG / instruction
  - 不在本阶段解除 compile-only gate blocker
  - 不在本阶段放行 `ConditionalExpression`、`assert`、container literal、cast/type-test、`GetNodeExpression`、`PreloadExpression`
  - 不在本阶段实现 parameter default 的完整语义、可见性与求值时序
  - 不在本阶段新增新的 public lowering 入口或 plugin-style pass 装配机制

---

## 1. 当前起点与新增架构约束

当前仓库已经稳定具备以下 lowering 起点：

- public lowering 输入固定为 `FrontendModule`
- lowering 内部统一走 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
- pipeline 当前稳定产出 `LirModule(skeleton/shell-only)` 与 function lowering context scaffold
- `LirClassDef` / `LirFunctionDef` 由 skeleton phase 预先发布，function body 仍保持：
  - `basicBlockCount == 0`
  - `entryBlockId` 为空

但现有后端/LIR 合同额外要求 frontend 在架构上预留两类 “不是普通 callable body，但最终仍然是函数” 的 lowering 单元：

1. property initializer function
   - `PropertyDef` / `LirPropertyDef` 已正式包含 `init_func`
   - backend 当前会消费 `init_func`，并在缺失时补 `_field_init_<property>`
   - 这说明 frontend 未来不能把 property initializer 永远当成 “直接内联在构造期的一段表达式”，而应有能力把它 lowering 成独立函数
2. parameter default initializer function
   - `LirParameterDef` 已正式包含 `default_value_func`
   - shared `ScopeMethodResolver` 与 backend `CALL_METHOD` 已把 `default_value_func` 视为正式 metadata
   - backend 当前明确支持 instance/static 两种 default function 合同

因此，本阶段的真正目标不是只为 “普通函数 body” 建立入口，而是为所有未来需要产出 `LirFunctionDef` 的 lowering 单元建立统一的函数级入口模型：

- executable callable body
- property initializer function
- parameter default initializer function

当前只有前两者中的 executable callable 已 compile-ready；property initializer 处于 compile-ready island；parameter default 仍未支持。但架构必须现在就为三类单元留出统一模型，避免后续默认参数实现时推翻第一阶段的设计。

---

## 2. 实施总原则

本阶段必须始终遵守以下原则：

1. 继续以前置 `analyzeForCompile(...)` 结果为 lowering 唯一合法输入，不新增 `FrontendAnalysisData` 或 `LirClassDef` 列表形式的 public 入口。
2. frontend lowering context 不只对应 “源码里显式声明的函数”，而是对应 “未来会形成一个 `LirFunctionDef` 的 lowering 单元”。
3. executable callable context 绑定到 skeleton phase 已发布的同一份 `LirFunctionDef` 对象；initializer context 则允许绑定到 preparation pass 新建的 hidden synthetic function shell。
4. 当前 compile-ready 范围内，只收集：
   - executable callable
   - supported property initializer island
   parameter default initializer context 目前只冻结合同与上下文形状，不实际收集。
5. 对缺失的 published fact、损坏的 AST-to-skeleton 对应关系、未发布的 scope graph、缺失的 property/function metadata 等协议问题，统一 fail-fast，而不是 silent skip。
6. preparation 阶段结束后，所有 `LirFunctionDef` 仍保持无 body 的 scaffold 状态；不得伪造空 entry block、空 return block 或假 CFG。
7. synthetic initializer function 必须设为 hidden，避免把内部实现细节暴露为用户可见成员 surface。
8. context 结构保持最小，但不能再假设每个 lowering 单元都天然拥有一个 `Block`；property/default initializer 的 lowering root 是 expression，而不是 statement block。

---

## 3. 推荐提交切分

建议把实施拆成 5 个可以单独回归、单独提交的步骤：

1. 定义统一的 function lowering context 模型
2. 新增 `FrontendLoweringFunctionPreparationPass` 并接入固定 pipeline
3. 收集 executable callable context
4. 收集 property initializer synthetic function context，并补 hidden function shell
5. 冻结 parameter default initializer function 合同、测试边界与文档同步

这样每一步都可以独立验证，不会把 “上下文模型设计”、“普通 callable 收集” 和 “synthetic init function 架构” 混在一笔大 diff 里。

---

## 4. Step 1: 定义统一的 function lowering context 模型

### 4.0 当前状态

- 状态：已完成
- 完成时间：2026-03-27
- 已落地产出：
  - `frontend.lowering.FunctionLoweringContext`
  - `FrontendLoweringContext.publishFunctionLoweringContexts(...)`
  - `FrontendLoweringContext.functionLoweringContextsOrNull()`
  - `FrontendLoweringContext.requireFunctionLoweringContexts()`

### 4.1 目标

在 lowering 内部引入一个统一的函数级上下文模型，覆盖普通 callable 与 synthetic initializer function。

### 4.2 建议实施内容

- 在 `frontend.lowering` 包内新增一个仅供 lowering 内部使用的 `FunctionLoweringContext` 类型。
- 推荐使用 `record`，避免手写样板代码。
- 建议为 context 增加一个最小 kind 枚举：
  - `EXECUTABLE_BODY`
  - `PROPERTY_INIT`
  - `PARAMETER_DEFAULT_INIT`
- `FunctionLoweringContext` 最少应包含以下信息：
  - `kind`
  - `Path sourcePath`
  - `FrontendSourceClassRelation sourceClassRelation`
  - `LirClassDef owningClass`
  - `LirFunctionDef targetFunction`
  - `Node sourceOwner`
    - `FunctionDeclaration`
    - `ConstructorDeclaration`
    - `VariableDeclaration`
    - 未来的 parameter/default owner
  - `Node loweringRoot`
    - executable callable 对应 `Block`
    - property/default initializer 对应根 `Expression`
  - `FrontendAnalysisData analysisData`
- `sourceOwner` 与 `loweringRoot` 都必须保持为原始 AST 节点引用，不包装成新对象。
  - 原因是 frontend side table 使用 AST identity 作为 key，后续 lowering 需要直接用原始节点去取 `scopesByAst()` / `symbolBindings()` / `expressionTypes()` / `resolvedMembers()` / `resolvedCalls()`。
- `targetFunction` 在 context 发布时必须已经存在。
  - 普通 callable：复用 skeleton phase 已发布的 `LirFunctionDef`
  - initializer context：由 preparation pass 先创建 hidden synthetic `LirFunctionDef` shell，再发布 context
- `FrontendLoweringContext` 新增 function lowering context 集合发布位。
  - 建议保留：
    - `publishFunctionLoweringContexts(...)`
    - `functionLoweringContextsOrNull()`
    - `requireFunctionLoweringContexts()`

### 4.3 验收细则

- happy path：
  - lowering context 可以发布并读取统一的 function lowering context 集合
  - `FunctionLoweringContext` 可以同时表达 ordinary callable、property init、future parameter default init
  - context 不再假设每个 lowering 单元都拥有 `Block body`
- negative path：
  - 在未发布 context 集合时调用 `requireFunctionLoweringContexts()` 必须 fail-fast
  - context 结构不允许缺失 `kind` / `targetFunction` / `sourceOwner` / `loweringRoot` / `analysisData`

---

## 5. Step 2: 新增 function preparation pass 并接入 pipeline

### 5.0 当前状态

- 状态：已完成
- 完成时间：2026-03-27
- 已落地产出：
  - `frontend.lowering.pass.FrontendLoweringFunctionPreparationPass`
  - 固定 pipeline 顺序扩展为 analysis -> class skeleton -> function preparation
  - compile-ready executable callable context 收集
  - supported property initializer context 收集
  - hidden synthetic property init function shell 自动补齐与 `initFunc` 回写

### 5.1 目标

把 function preparation 固定为 lowering pipeline 的正式一环，使后续 CFG/body lowering 不需要各自重建 callable / initializer 索引。

### 5.2 建议实施内容

- 新增 `FrontendLoweringFunctionPreparationPass`
- 固定放在：
  1. `FrontendLoweringAnalysisPass`
  2. `FrontendLoweringClassSkeletonPass`
  3. `FrontendLoweringFunctionPreparationPass`
- `FrontendLoweringPassManager` 继续使用显式 `List.of(...)` 固定顺序装配。
- 新 pass 运行前置条件固定为：
  - `analysisData` 已发布
  - `lirModule` 已发布
  - 当前 pipeline 未被 stop
- 该 pass 只负责：
  - 建立类级索引
  - 建立 executable / initializer function lowering context 集合
  - 在需要时补 synthetic hidden function shell
- 该 pass 不负责：
  - 写 basic block
  - 设置 `entryBlockId`
  - 分配 lowering temp variable
  - 写 instruction

### 5.3 验收细则

- happy path：
  - compile-ready module 执行到该 pass 后，context 中出现 function lowering context 集合
  - 返回的 `LirModule` 仍保持 skeleton-only / shell-only 合同
- negative path：
  - 若 analysis pass 因 compile gate 报错而请求 stop，preparation pass 不得运行
  - 若 analysisData 或 lirModule 缺失，preparation pass 必须 fail-fast，而不是空跑

---

## 6. Step 3: 收集 executable callable context

### 6.0 当前状态

- 状态：已完成
- 完成时间：2026-03-28
- 已落地产出：
  - compile-ready executable callable context 收集已覆盖 top-level function、inner class function、static function 与 constructor `_init`
  - executable callable context 继续复用 skeleton phase 已发布的同一份 `LirFunctionDef`
  - AST owner -> owning class / source relation identity 索引已用于 inner class executable callable 收集
  - fail-fast guard rail 已通过测试锚定：缺失 callable owner scope、callable body scope、function skeleton、published class skeleton 时立即中止
  - `FrontendLoweringFunctionPreparationPassTest` 已补充 executable callable 的对象同一性与负向边界回归

### 6.1 目标

在不重新发现类、不重建 function skeleton 的前提下，稳定收集每个 compile-ready executable callable 对应的 `LirFunctionDef`。

### 6.2 建议实施内容

#### 6.2.1 先建立类级索引

pass 开始时先基于 `FrontendModuleSkeleton.sourceClassRelations()` 构建两张索引：

- `Node astOwner -> LirClassDef owningClass`
- `Node astOwner -> FrontendSourceClassRelation sourceClassRelation`

索引对象建议继续使用 `IdentityHashMap`，保持与 frontend side table 的 identity-key 语义一致。

类级索引至少要覆盖：

- top-level source file 对应的 top-level class
- inner class declaration 对应的 inner `LirClassDef`

#### 6.2.2 再遍历 compile-ready executable callable 边界

建议复用当前 frontend analyzer 的 traversal 边界，而不是自己写一套宽泛的 AST 扫描器：

- top-level source file statement list
- class declaration body statement list
- `FunctionDeclaration`
- `ConstructorDeclaration`

需要收集进 `EXECUTABLE_BODY` context 的 callable：

- top-level script class function
- inner class function
- static function
- instance function
- constructor `_init`

当前明确不纳入 `EXECUTABLE_BODY` context 的内容：

- lambda
- property initializer
- parameter default
- getter/setter lowering 特化入口
- backend 注入的隐式 prepare/finally block

#### 6.2.3 callable -> skeleton function 匹配规则

当前阶段推荐直接复用仓库内已经稳定存在的 callable skeleton 识别方式，而不是发明新 key。

函数匹配键建议固定为：

- name
- `isStatic`
- parameter count

其中：

- `ConstructorDeclaration` 统一映射到 `_init`
- 普通 `FunctionDeclaration` 若名称就是 `_init`，也按 `_init` 处理

当前不额外纳入的维度：

- 默认参数形态
- 参数类型逐项匹配
- annotations
- vararg 细化签名

这些维度应在未来真正需要 disambiguation 时再升级，不要在 preparation 阶段提前扩张模型。

### 6.3 验收细则

- happy path：
  - top-level / inner class function 全部进入 `EXECUTABLE_BODY` context 集合
  - static 与 instance function 可区分
  - constructor 与 `_init` 可稳定映射到对应 `LirFunctionDef`
  - 每个 context 中的 `targetFunction` 与 `LirModule` / skeleton phase 发布的是同一对象引用
- negative path：
  - compile gate 已拦截的 module 不产出 executable callable context
  - class owner 找不到 skeleton class 时 fail-fast
  - callable 找不到对应 skeleton function 时 fail-fast
  - callable 或 body 未发布 scope 时 fail-fast

---

## 7. Step 4: 收集 property initializer synthetic function context

### 7.0 当前状态

- 状态：已完成
- 完成时间：2026-03-28
- 已落地产出：
  - supported property initializer island 会发布 `PROPERTY_INIT` context
  - `LirPropertyDef.initFunc` 缺失时会稳定回写 `_field_init_<property>` 命名基线
  - preparation pass 会为 property initializer 自动补 hidden synthetic shell，并保持 shell-only 合同
  - 预先存在的 `initFunc` shell 现在只会在合同兼容时复用；hidden、static/instance、return type、self 参数与 shell-only 状态冲突时统一 fail-fast
  - `FrontendLoweringFunctionPreparationPassTest` 已补充 property init shell 复用、scope 缺失与 shell 合同冲突的正反回归

### 7.1 目标

把 supported property initializer island 正式纳入 “函数化 lowering 单元” 体系，而不是把它永久留作 callable body lowering 之外的特例。

### 7.2 建议实施内容

- 对每个 `FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(...)` 命中的 property declaration，创建一个 `PROPERTY_INIT` context。
- property initializer context 的：
  - `sourceOwner` 为 `VariableDeclaration`
  - `loweringRoot` 为 `variableDeclaration.value()`
  - `owningClass` 为声明该 property 的 `LirClassDef`
- preparation pass 需要为它补一个 hidden synthetic `LirFunctionDef` shell：
  - 函数名来自 `LirPropertyDef.initFunc`
  - 若 `initFunc` 为空，当前兼容命名基线继续采用 `_field_init_<propertyName>`
  - `returnType == property.type`
  - 当前 compile-ready MVP 下，non-static property init function 继续采用 instance-style shell
  - synthetic function 必须 `setHidden(true)`
  - shell 阶段仍不写 basic block / `entryBlockId`
- shell 与 metadata 的更新顺序固定为：
  1. 找到 owning `LirPropertyDef`
  2. 确认 / 写入 `initFunc`
  3. 在 owning class 上找到或创建同名 hidden synthetic `LirFunctionDef`
  4. 用这份 function 作为 `PROPERTY_INIT` context 的 `targetFunction`

当前必须保持的边界：

- property initializer 仍不等于 “完整 class initialization timeline”
- 不在 preparation 阶段求值
- 不在 preparation 阶段合并进 `_init`
- 不在 preparation 阶段放宽 `self` / current-instance hierarchy 的现有 fail-closed 边界

### 7.3 验收细则

- happy path：
  - 每个 supported property initializer island 都能建立 `PROPERTY_INIT` context
  - owning property 的 `initFunc` 已被稳定写入
  - owning class 上存在同名 hidden synthetic function shell
  - synthetic shell 的 `returnType` 与 property type 一致
- negative path：
  - property declaration 找不到 owning class / property metadata 时 fail-fast
  - initializer expression 缺失、scope 未发布或 metadata 冲突时 fail-fast
  - preparation 阶段不得把 property initializer 直接伪装成 executable callable body

---

## 8. Step 5: 冻结 parameter default initializer function 合同

### 8.1 目标

虽然 parameter default 当前仍不在 frontend MVP 支持面，但 preparation 架构必须现在就允许它未来以 hidden synthetic function 的形态接入，而不需要推翻 `FunctionLoweringContext` 模型。

### 8.2 当前已知后端合同

当前后端对 GDCC `default_value_func` 的既有合同已经固定为：

- 参数 default source 可以是 function
- default function 可以是：
  - static：0 个参数
  - instance：1 个 `self` 参数
- default function 不能是 vararg
- default function 返回类型必须兼容参数类型

这意味着 frontend 后续实现 parameter default 时，不能把默认值 permanently 设计成 “调用点内联表达式补全” 的唯一路径；必须能发布一个真正的 hidden synthetic function，并把它写入 `LirParameterDef.defaultValueFunc`。

### 8.3 建议实施内容

- `FunctionLoweringContext.Kind` 现在就保留 `PARAMETER_DEFAULT_INIT`
- 当前 preparation pass 不收集这类 context
  - 原因是 parameter default 仍在 frontend semantic MVP / compile surface 之外
- 但以下合同现在就应冻结：
  - 每个 supported parameter default expression 最终对应一个 hidden synthetic function
  - 该函数由对应 parameter 的 `defaultValueFunc` 引用
  - 该函数的 lowering root 是 parameter default expression，而不是 enclosing callable body
  - 该函数可以是 static 或 instance-style，frontend 架构不得硬编码 “默认参数函数一定没有 `self`”
  - 该函数不得暴露为普通 user-visible method surface
- 命名模式当前不要求在本阶段彻底冻结，但必须保持：
  - owner-local 唯一
  - 可稳定回写到 `defaultValueFunc`
  - 不与用户函数名冲突

### 8.4 验收细则

- happy path：
  - `FunctionLoweringContext` 模型无需改形状即可容纳 future `PARAMETER_DEFAULT_INIT`
  - 计划文档已明确 parameter default 必须函数化，而非仅靠 call-site inline 设计
- negative path：
  - 当前 compile-ready 范围内，parameter default 仍不生成 context
  - 不允许因为 “当前不支持” 就把模型重新收窄成只能表达 executable callable / property initializer

---

## 9. Step 6: 明确 published fact 消费入口，但不提前做 body lowering

### 9.1 目标

让 `FunctionLoweringContext` 成为后续 pass 的统一函数级事实入口，同时避免在 preparation 阶段偷跑 executable body / initializer expression lowering。

### 9.2 建议实施内容

- context 中直接挂载 `FrontendAnalysisData`
- 后续 pass 统一从 `analysisData` 读取：
  - `scopesByAst()`
  - `symbolBindings()`
  - `expressionTypes()`
  - `resolvedMembers()`
  - `resolvedCalls()`
- preparation pass 自身只做前置 invariant 校验：
  - `sourceOwner` / `loweringRoot` 的 scope 已发布
  - owning class / function / property metadata 已发布
  - synthetic function shell 已与 metadata 正确互相引用

当前不要在 preparation 阶段做的事情：

- 对 body 中的每个 expression 建立即时 lowering node
- 对 initializer expression 立即生成 instruction
- 提前做 truthiness normalization
- 提前分配 LIR variable
- 提前写入 terminator

### 9.3 验收细则

- happy path：
  - `FunctionLoweringContext` 已包含后续 CFG/body/init lowering 所需的全部入口引用
  - 下一阶段 pass 不需要再次扫描 `FrontendModuleSkeleton` 去找 callable/property 对应关系
- negative path：
  - preparation pass 不得因为 backend 已有某类 instruction 就偷跑 expression/body lowering
  - preparation pass 结束后，所有函数仍保持 `basicBlockCount == 0` 且 `entryBlockId` 为空

---

## 10. Step 7: 测试计划

### 10.1 新增测试建议

建议新增或扩展以下测试：

1. compile-ready module 生成 executable callable context
   - 含 top-level function
   - 含 inner class function
   - 含 static function
   - 含 constructor / `_init`
2. compile-ready module 生成 property initializer context
   - property `initFunc` 被写入
   - hidden synthetic function shell 被创建
3. compile-blocked module 在 analysis pass 后 stop
   - 验证 preparation pass 不运行
   - 验证 context 集合未发布
4. 缺失 published fact 时 fail-fast
   - 缺 analysisData
   - 缺 lirModule
   - 缺 class scope
   - 缺 callable body scope
   - 缺 property initializer scope
   - 缺 skeleton function / property metadata
5. preparation pass 不改变 shell-only LIR 合同
   - `basicBlockCount == 0`
   - `entryBlockId` 为空
6. parameter default 仍被阻断，但 context 架构无需扩展
   - 当前不生成 `PARAMETER_DEFAULT_INIT`
   - 文档 / test helper 不把模型写死成只有两类 context

### 10.2 测试锚点

测试至少要显式锚定：

- context 总数
- `kind` 分布
- 每个 executable callable context 的 owning class name / target function name
- 每个 property initializer context 的 owning class name / property name / target init function name
- constructor 是否映射到 `_init`
- synthetic init function 是否 hidden
- property `initFunc` 是否回写成功
- pipeline 是否在 compile-blocked 时提前 stop
- `LirModule` 是否仍可被 serializer 接受为 skeleton-only / shell-only 中间态

### 10.3 验收细则

- happy path：
  - context 覆盖预期 executable callable 与 property initializer 集合
  - hidden synthetic init function shell 合同稳定
  - serializer 回归继续通过
- negative path：
  - 每种不变量破坏都能通过测试稳定复现
  - 没有 silent skip 的测试空洞

---

## 11. 完成定义

本阶段完成时，仓库应同时满足以下条件：

1. lowering fixed pipeline 中已经存在 `FrontendLoweringFunctionPreparationPass`
2. `FrontendLoweringContext` 已能发布并读取统一的 `FunctionLoweringContext` 集合
3. 所有 compile-ready executable callable 都能稳定映射到已有 `LirFunctionDef`
4. 所有 supported property initializer island 都能稳定映射到 hidden synthetic init function shell
5. parameter default 虽仍未支持，但其 future synthetic function 合同已固定在当前架构中
6. compile-blocked module 仍在 analysis pass 后停止，不进入 context 生成
7. preparation 阶段结束后，`LirModule` 仍保持无 body 的 scaffold/shell 合同
8. 正反测试已覆盖 executable callable、property init、pipeline stop、fail-fast、不写假 CFG
9. 以下文档已同步更新：
   - `frontend_lowering_plan.md`
   - `frontend_lowering_pre_pass_implementation.md`（若实现已落地）
   - `frontend_rules.md`（仅当边界变化时）
   - 本文档

---

## 12. 当前阶段明确延后处理的风险

以下问题当前只记录为后续阶段风险，不在本计划内解决：

- callable skeleton 匹配键未来可能需要从 “name + static + parameter count” 升级
- property initializer 的完整初始化时序语义仍未闭环；当前只固定 “它最终是一个函数”
- parameter default 的可见性、捕获、求值顺序、instance-vs-static default function 选择策略仍待后续语义设计
- `ConditionalExpression` 依赖 CFG 合同，必须等下一阶段控制流 lowering 稳定后再放行
- truthiness / condition normalization 属于 CFG/body lowering 责任，不应提前下沉到 function preparation
- backend 的 `__prepare__` / `__finally__` block 注入发生在函数已有 body 的前提下；preparation 阶段不得提前耦合到这套机制

这些风险需要在后续 CFG/body lowering 与 parameter-default 设计中继续展开，但不应阻塞当前 scaffold 首轮落地。
