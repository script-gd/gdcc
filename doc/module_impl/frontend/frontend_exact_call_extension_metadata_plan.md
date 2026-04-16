# Frontend Exact Call Extension Metadata Plan

> 本文档记录“exact engine call 在 frontend body lowering 中重新读取 extension API 导出的 raw metadata，进而因 `enum::...` / `bitfield::...` / `typedarray::...` 文本失配而 fail-fast”的调查结论、设计约束、推荐修复路线与分阶段验收细则。

---

## 1. 问题界定

这次问题不能再笼统表述成“带参数的 engine `Node` method call 会在 lowering 中空指针”，真实现象需要拆成两条链。

### 1.1 plain `var` receiver 链

示例：

```gdscript
var holder = Node.new()
holder.add_child(Node.new())
```

当前 gdcc 中：

- plain `var` 不会因为 initializer 自动回填成 exact local type
- `holder` 仍是 `Variant` carrier
- `add_child(...)` 会先退成 `DYNAMIC_FALLBACK`

这条链不是本次 exact-lowering 崩溃的直接根因，也不属于本计划的修复目标。

### 1.2 显式 typed receiver 链

示例：

```gdscript
var holder: Node = Node.new()
holder.add_child(Node.new())
```

当前 gdcc 中：

- call route 会先发布为 `RESOLVED + INSTANCE_METHOD`
- 随后 body lowering 在参数边界物化阶段重新读取 extension metadata
- 调用链落到 `FrontendBodyLoweringSession.callBoundaryParameterTypes(...)`
- 当时最终会在 `ExtensionFunctionArgument.getType()` 命中旧 parser 路径并 fail-fast

因此，这次问题的真实定义是：

- exact engine method route 已经被 shared resolver 正确接受
- 但 exact body lowering 没有消费 shared resolver 已规范化的 callable signature
- 而是回头把 extension API JSON 里的 raw type text 再 parse 一次

---

## 2. 调查结论

### 2.1 gdcc 当前成因链路

当前代码里的关键事实如下：

- `ScopeMethodResolver` 已通过 `ScopeTypeParsers.parseExtensionTypeMetadata(...)` 解析 extension metadata
- `ScopeResolvedMethod.parameters()` 保存的是规范化后的参数类型，而不是 raw metadata text
- exact call publication 当前仍主要保留 `resolvedMethod.function()` / `FunctionDef` 作为 declaration metadata，而没有把这份 normalized parameter boundary 一起发布出去
- `FrontendBodyLoweringSession.materializeCallArguments(...)` 对 exact route 仍调用 `callBoundaryParameterTypes(...)`
- `callBoundaryParameterTypes(...)` 当前通过 `FunctionDef.getParameters().map(ParameterDef::getType)` 重新读取参数类型
- 当时的 `ExtensionFunctionArgument.getType()` 仍走 `ClassRegistry.tryParseTextType(type)`；现已收口到 shared `ScopeTypeParsers.parseExtensionTypeMetadata(...)`

这就形成了两套事实源：

- shared resolver 真源：`ScopeResolvedMethod.parameters()`
- lowering 临时回读：`ParameterDef::getType`

同一个 selected extension callable 因此至少会发生两次参数类型处理：

1. `ScopeMethodResolver` 先把 exported spelling 规范化成 `ScopeResolvedMethod.parameters()`
2. lowering 再沿 `FunctionDef -> ParameterDef::getType` 回到 raw metadata

第二套路径不认识 Godot extension API 导出的 compatibility spelling，因此会在 exact route 中重新引入失败。

### 2.2 当前受影响的 metadata 形状

已确认会触发这类问题的 extension-only metadata 至少包括：

- `enum::...`
- `bitfield::...`
- `typedarray::...`

本地 `extension_api_451.json` 中的真实例子包括：

- `Node.add_child(...)`
  - 第三个默认参数为 `enum::Node.InternalMode`
- `Node.set_process_thread_messages(...)`
  - 参数为 `bitfield::Node.ProcessThreadMessages`
- `ArrayMesh.add_surface_from_arrays(...)`
  - 第三个默认参数为 `typedarray::Array`
  - 第五个默认参数为 `bitfield::Mesh.ArrayFormat`

所以影响面并不限于 `Node.add_child(...)` 这一个 API，而是任何 exact engine method，只要其签名里包含上述 metadata spelling。

### 2.3 Godot 上游的处理方式

对照 Godot 上游实现，可以确认 gdcc 当前的“二次 string parse”不是引擎侧的处理模式。

- `core/object/method_bind.h`
  - `MethodBind` 内部直接缓存 `Variant::Type *argument_types`
  - `get_argument_type(...)` / `get_argument_info(...)` 都是读取内部已结构化的参数元数据
- `core/extension/extension_api_dump.cpp`
  - `enum::...` / `bitfield::...` / `typedarray::...` 这些 spelling 是在导出 extension API JSON 时由 `get_property_info_type_name(...)` 生成的
  - `typeddictionary::K;V` 也属于 Godot 的 exported spelling，但它不是当前 exact-call regression 的命中面
    - 当前 `extension_api_451.json` 中它出现在 property metadata，例如 `DPITexture.color_map`、`GraphEdit.type_names`
    - shared `ScopeTypeParsers.parseExtensionTypeMetadata(...)` 现已支持它的 flat-leaf normalization
    - 它仍应继续视为 backend typed-dictionary ABI 的独立合同，但这个“独立”现在指向 outward metadata / runtime gate，而不是 exported spelling 解析本身
  - 上述 spelling 都属于“导出表示”，不是引擎内部调用/分析的主表示
- `modules/gdscript/gdscript_analyzer.cpp`
  - `function_signature_from_info(...)` 与 `validate_call_arg(...)` 直接基于 `MethodInfo` / `PropertyInfo` 构造 `DataType`
  - Godot 内部不会先解析成功，再在后续 lowering/validation 环节把 JSON 导出的 type string 拿回来重 parse 一次

这意味着 gdcc 的修复方向应当是：

- 让 lowering 复用 shared resolver 已规范化的 callable signature
- 而不是继续保留“先规范化一次，之后又回到 raw string”的双轨合同

### 2.4 `gdparser` 的边界结论

本地 `E:/Projects/gdparser` 副本中：

- `VariableDeclaration` 只保存可空的 `TypeRef`
- `TypeRef` 本身只保存 `sourceText`

也就是说，parser AST 的职责本来就只是保留文本和结构，而不是在 parser 层完成 extension metadata 规范化。  
这进一步说明：

- 规范化责任应继续停留在 shared semantic / scope resolver 层
- 不应尝试把这类问题下沉回 parser 或 AST mapping 层去修

---

## 3. 修复目标与非目标

### 3.1 目标

- exact call lowering 对 callable fixed-parameter boundary 只消费一份 shared-normalized 真源
- `enum::...` / `bitfield::...` / `typedarray::...` 在 exact route 上不再因二次 raw parse 而 fail-fast
- 同一个 selected extension callable 的 fixed-parameter metadata 在 exact 主链上只规范化一次、只发布一次，后续阶段只复用，不再重复解析
- compile gate / semantic side-table / CFG builder / body lowering 对 exact route 的 callable boundary 保持一致
- 若 exact route 缺失 lowering-ready callable boundary fact，应当以清晰 invariant violation fail-fast，而不是以 `NullPointerException` 形式漂移

### 3.2 非目标

- 不在本次修复中改变 `var holder = Node.new()` 的类型推导合同
- 不把 plain `var` receiver 的 dynamic fallback 改写成 exact route
- 不在本次修复中扩写 backend dynamic dispatch 语义
- 不把问题简化成“只为 `Node.add_child(...)` 打补丁”
- 不在本次修复中展开 `gdextension` exported metadata 的广义审计，也不顺手清扫所有 `ClassRegistry.tryParseTextType(...)` 入口
- 不在本次修复中把 `declarationSite` 改写成 `ScopeResolvedMethod` 或引入新的 side-table 生命周期

---

## 4. 设计原则

### 4.1 callable boundary 只能发布一次、消费一次

exact route 一旦已经通过 shared resolver 完成 method selection，下游必须继续复用同一份规范化结果。  
body lowering 不得再以 `FunctionDef.getParameters()` + `ParameterDef::getType()` 自建第二套 callable signature 推导。

对同一个 selected extension callable，本计划只允许发生下面这一次规范化：

1. `ScopeMethodResolver` 生成 `ScopeResolvedMethod.parameters()`
2. exact call publication 把这些已规范化参数类型发布到 `FrontendResolvedCall`
3. 后续 compile/lowering 只读这份 published boundary，不再回读 raw metadata

换句话说，本计划不只是“防止 lowering 二次 parse”，还要求**不要再为同一 extension 函数制造第三套边界投影**。

### 4.2 `FrontendResolvedCall.argumentTypes()` 语义保持不变

当前 `FrontendResolvedCall.argumentTypes()` 表示的是：

- 调用点实参的已发布类型

它不是：

- callable fixed-parameter signature

因此这次修复不能偷懒把 `argumentTypes()` 重新解释成参数签名；需要新增一条 exact-call boundary contract，而不是篡改现有字段语义。

### 4.3 保持 `declarationSite` 的既有语义

当前 frontend 多处逻辑和测试仍依赖 `declarationSite` 的现有运行时形状，例如：

- mutability support 依赖 `ExtensionBuiltinClass.ClassMethod` / `ExtensionGdClass.ClassMethod`
- 构造器路径依赖 constructor metadata object
- debug / compile-check / 既有测试会直接观察 `declarationSite`

因此本次不应把 `declarationSite` 改写成 `ScopeResolvedMethod`。  
exact boundary 应作为新增 published fact 附着在 `FrontendResolvedCall` 上，而不是替换掉现有 declaration metadata。

### 4.4 不引入 side-table，也不复用 `FunctionSignature`

这次问题发生在 exact route published fact 与 lowering consumer 之间。  
再引入一个 side-table，只会增加同步点和生命周期复杂度，不会让修复更干净。

同理，现有 `FunctionSignature` 是 utility-function 侧的局部描述，不应被拿来扩写成 frontend exact-call 的通用 carrier。  
本计划应使用一个更小、更贴近 exact route 需求的 boundary record，仅承载：

- ordered fixed parameter types
- `isVararg`

default-parameter 切分不属于这次 bug 的必要面，不应顺手并入。

### 4.5 旧 helper 可以保留，但 exact route 不得再触达它

`callBoundaryParameterTypes(...)` 当前是问题入口，但它同时也代表“legacy callable boundary fallback”。  
本次不要求立即删除它；更稳的策略是：

- exact resolved engine-call path 不再触达它
- constructor / 非 exact / 尚未迁移的旧路径仍可保留 fallback

这样 diff 更小，也能避免把 unrelated route 一起卷进来。

### 4.6 不推荐“只修 `ExtensionFunctionArgument.getType()` 就收工”

只把 `ExtensionFunctionArgument.getType()` 改成 shared parser 虽然能缓解当前 `add_child(...)` 的空指针，但它仍保留了错误架构：

- shared resolver 一套
- lowering 再回头读一套

这会继续让 callable signature 真源分裂，也会让后续 exact route 工程继续围绕 `FunctionDef` / raw metadata 打转。  
因此，“把 lowering 接到 shared-normalized signature”必须是主修复；`ExtensionFunctionArgument.getType()` 的 shared-parser 收口现在已经是已落地 hygiene 修复，但它仍不能替代主方案。

---

## 5. 推荐实施方案

推荐把修复拆成四个阶段推进。

### Phase A. 建立文档与回归锚点，锁住 route split

当前状态（2026-04-16）：已完成

本阶段任务与状态：

- [x] 重新核对 `doc/test_error/test_suite_engine_integration_known_limits.md` 中的 route split 描述与当前 `extension_api_451.json` / resolver / lowering 实现一致，无需再把问题退化回“所有带参 `Node` 方法都会同样崩溃”的笼统说法。
- [x] 新增 focused sema 回归，固定 `var holder = Node.new()` 继续走 `DYNAMIC_FALLBACK`，而 `var holder: Node = Node.new()` 继续走 `RESOLVED + INSTANCE_METHOD`。
- [x] 新增 focused lowering 回归，固定 typed exact route 目前仍会在 body lowering 命中 raw metadata regression，避免后续实现把它误“修复”为 dynamic fallback 漂移。

目标：

- 先把“当前到底是哪条链出问题”稳定钉死
- 保留必要文档与 focused tests，避免后续实现把 dynamic fallback 链和 exact route 链重新混写

建议修改点：

- 错误记录：
  - `doc/test_error/test_suite_engine_integration_known_limits.md`
- focused tests：
  - frontend sema / lowering 相关单测

建议测试锚点：

- positive reproduction
  - `var holder: Node = Node.new(); holder.add_child(Node.new())`
  - 断言 route 为 `RESOLVED + INSTANCE_METHOD`
  - 当前基线应稳定暴露 exact-lowering failure
- route split
  - `var holder = Node.new(); holder.add_child(Node.new())`
  - 断言 route 为 `DYNAMIC_FALLBACK`
  - 不再把它误记成同一条 exact-lowering bug

验收细则：

- 错误记录不再写成“所有带参数 engine Node method 都会在 lowering 中空指针”
- focused tests 能稳定区分 plain `var` receiver 与 typed receiver 两条链
- 后续改动若把 plain `var` 误抬成 exact route，测试会直接失败

---

### Phase B. 在 `FrontendResolvedCall` 上发布 exact-only boundary，并保证单次发布

当前状态（2026-04-16）：已完成

本阶段任务与状态：

- [x] 在 `FrontendResolvedCall` 上新增 exact-only callable boundary published fact，并明确 `argumentTypes()` 继续只表示调用点实参快照。
- [x] 由 `ScopeResolvedMethod.parameters()` 发布 fixed-parameter boundary，并在 blocked suggested-call 复制路径中保留该 fact。
- [x] 以 focused unit tests 锁定 boundary 的字段语义、发布来源与传播行为：
  - `FrontendResolvedCallTest`
  - `FrontendChainReductionHelperTest`
  - `FrontendSemanticAnalyzerFrameworkTest`
  - `FrontendLoweringBodyInsnPassTest`
- [x] 受影响回归面已补跑并通过：
  - `FrontendResolvedCallTest`
  - `FrontendChainReductionHelperTest`
  - `FrontendSemanticAnalyzerFrameworkTest`
  - `FrontendLoweringBodyInsnPassTest`
  - `FrontendCallMutabilitySupportTest`
  - `FrontendChainBindingAnalyzerTest`
  - `FrontendCompileCheckAnalyzerTest`
  - `FrontendAnalysisDataTest`

目标：

- 给 exact route 增加 lowering-ready 的 callable boundary 真源
- 让该真源直接来自 `ScopeResolvedMethod.parameters()`
- 防止同一个 selected extension callable 在 publication 之后再次被解析

推荐落点：

- 在 `FrontendResolvedCall` 上新增 exact-only 的 boundary record
- 不引入 side-table
- 不改变 `declarationSite`

推荐内容：

- ordered fixed parameter types
- `isVararg`

实施要点：

1. 由 shared resolver 产出的 `ScopeResolvedMethod.parameters()` 直接映射出 fixed parameter types。
2. 该映射只做“复制已规范化结果”，不重新读取 `FunctionDef`，也不重新 parse raw metadata。
3. 该 boundary 只对已经握有 `ScopeResolvedMethod` 的 exact resolved route 发布。
4. `FrontendResolvedCall.argumentTypes()` 继续保留“调用点实参类型”的原语义，不复用、不重命名。
5. `FrontendChainReductionHelper.resolvedCallTrace(...)` 是首选发布点，因为这里已经同时持有：
   - `resolvedMethod.returnType()`
   - `resolvedMethod.parameters()`
   - `resolvedMethod.function()`
6. `toBlockedSuggestedCall(...)` 之类会复制 call fact 的路径必须保留该 boundary，避免“已发布后再丢失，再次回读”的漂移。
7. 不在这一阶段引入 default-parameter 切分；当前 bug 不需要。
8. 不在这一阶段再做一次 `self` 归一化；resolver 输出已经是应被信任的 shared-normalized callable boundary。

验收细则：

- exact `RESOLVED` route 能稳定拿到 shared-normalized fixed parameter types
- `FrontendResolvedCall.argumentTypes()` 语义未漂移
- `declarationSite` 保持原有运行时形状
- 对同一个 selected extension callable，不再存在“发布时再重新 parse 参数类型”的第二次解析

---

### Phase C. 让 lowering 优先消费 published boundary，并把旧 helper 缩成窄 fallback

目标：

- 让 `FrontendBodyLoweringSession.materializeCallArguments(...)` 对 exact route 只消费已发布的 normalized boundary signature
- 保持 diff 小，不把 constructor / 旧路径一起改坏

建议修改点：

- `FrontendBodyLoweringSession`
- 与之相邻的 exact-call lowering helper

实施要点：

1. `resolvedCall.status() == RESOLVED` 且 route 属于 exact callable path 时：
   - 优先读取 published exact boundary
   - 不再调用 `callBoundaryParameterTypes(FunctionDef, ...)`
2. vararg tail 仍按当前 ordinary boundary contract 物化为 `Variant`
3. constructor special route 继续保留原先“先分流，再决定是否需要 callable signature”的合同
4. exact route 若缺失 boundary signature，应抛出明确 invariant violation，而不是落成空指针
5. `requireBoundaryCallableSignature(...)` 与 `callBoundaryParameterTypes(...)` 本轮不必删除，但应退化成：
   - 仅服务尚未迁移的旧 fallback 路径
   - 不再是 exact engine method route 的正常入口

验收细则：

- `var holder: Node = Node.new(); holder.add_child(Node.new())` 不再在 lowering 阶段命中 `ExtensionFunctionArgument.getType()` 空指针
- exact route 仍保持 `RESOLVED + INSTANCE_METHOD`，不会被误降级为 dynamic fallback
- exact call lowering 正常路径中不再出现 `FunctionDef.getParameters().map(ParameterDef::getType)`
- 同一个 selected extension callable 的 fixed-parameter metadata 不会在 lowering 阶段再次解析

---

### Phase D. 扩充 focused regression，并补充窄文档说明

目标：

- 用 focused tests 把真实风险面钉死，而不是只靠 `Node.add_child(...)` 一个例子
- 保留必要文档与注释同步，避免 exact boundary contract 在后续演进中失真

建议测试集：

- `enum::...` 锚点
  - `var holder: Node = Node.new(); holder.add_child(Node.new())`
  - 这里即使调用点只传第一个参数，也会触及含 `enum::Node.InternalMode` 的 selected signature
- `bitfield::...` 锚点
  - `var holder: Node = Node.new(); holder.set_process_thread_messages(0)`
- `typedarray::...` 锚点
  - `var mesh: ArrayMesh = ArrayMesh.new()`
  - `mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, Array())`
  - 这条签名同时覆盖：
    - `enum::Mesh.PrimitiveType`
    - `typedarray::Array`
    - `bitfield::Mesh.ArrayFormat`

建议测试层次：

- sema test
  - 锁 exact route 发布结果
  - 锁 published boundary 存在且与 selected exact route 一致
- lowering test
  - 锁 exact route 不再重读 raw metadata
  - 锁没有 `NullPointerException`
- narrow propagation test
  - 若 call fact 经过 blocked/suggested copy，boundary 不应丢失

negative coverage：

- plain `var holder = Node.new()` 仍保持 `DYNAMIC_FALLBACK`
- constructor / 非 exact route 不被误要求携带 exact boundary
- synthetic exact route 缺失 boundary signature 时必须 fail-fast

文档同步建议：

- 在本计划中明确写明：
  - exact boundary 是 `FrontendResolvedCall` 的 published fact
  - 它来自 `ScopeResolvedMethod.parameters()`
  - 它的目标是避免同一 extension callable 的重复解析
- 如代码注释需要同步，优先更新：
  - `FrontendResolvedCall`
  - `FrontendBodyLoweringSession.materializeCallArguments(...)`

验收细则：

- 三类 metadata spelling 都有 focused regression 锚点
- 正向 exact route 不再因 raw metadata 二次解析失败
- 负向/边界行为不漂移
- 文档明确写出“单次发布、单次消费、不重复解析”的 contract

---

## 6. 为什么推荐这个顺序

推荐顺序是：

1. 先锁文档事实和 route split
2. 再发布 exact-only boundary
3. 再替换 lowering 消费面
4. 最后补 focused regression 和窄文档同步

原因是：

- 如果一开始只改 `ExtensionFunctionArgument.getType()`，虽然能让个别 case 先跑通，但 exact call 的真源分裂仍然存在
- 如果先改 lowering 而不先冻结 published boundary，就会把“怎样把 resolver 结果带到 body pass”写成临时 glue code
- 如果这轮顺手做广义 metadata 审计，diff 会迅速膨胀，反而模糊 exact regression 的主修复
- 先把 exact boundary contract 钉死，后面的实现和测试才不会继续漂移

---

## 7. 最终建议

本次问题的主修复不应理解为“补一个 `add_child(...)` 特例”，而应理解为：

- exact engine call 的 callable boundary 必须从 shared resolver 一路带到 lowering
- extension API JSON 导出的 compatibility spelling 只能作为 exported metadata surface
- 不能在 exact body lowering 里再把它们当作 primary type source 重 parse 一次
- 同一个 selected extension callable 的 fixed-parameter metadata 在 exact 主链上只允许规范化一次，后续只发布与复用，不再重复解析

因此，这份计划的正确形状应该是：

- 保留必要文档与 focused tests 防止漂移
- 在 `FrontendResolvedCall` 上发布 exact-only boundary
- 让 lowering 优先消费该 boundary
- 暂时保留旧 helper 作为窄 fallback
- 把广义 extension metadata 清理延后到独立计划，而不是混入本轮 exact-call 修复

---

## 8. 保留的后续工程约束

> `frontend_extension_definition_normalization_plan.md` 及其审阅报告不会继续作为实施计划保留。  
> 但其中对后续 extension metadata 工程仍然有价值的边界、约定与反思，需要在这里被明确继承，避免未来重新走回“大一统替换”“把合同问题伪装成 parser 问题”或“静默收紧 compatibility 行为”的老路。

### 8.1 raw export layer 与 shared normalized layer 仍应分层

后续若继续推进 extension metadata 工程，必须继续区分：

- raw JSON / exported spelling import layer
- shared compiler-facing normalized publication layer

`extension_api_dump.cpp` 里的 `enum::...` / `bitfield::...` / `typedarray::...` / `typeddictionary::...` 都是 exported spelling，不是引擎内部主表示。  
因此任何后续工作都不应把“能读取 exported spelling”误写成“已经得到了 shared 真源”。

### 8.2 不要把“typed fact”偷换成“只有 `GdType`”

这次 exact-call 修复只处理 callable fixed-parameter boundary，因此 boundary record 只需要：

- ordered fixed parameter types
- `isVararg`

但更广义的 extension metadata 工程不能因此误以为“所有 shared 真相都可以压成 `GdType`”。  
当前代码库里仍然存在明显不属于 `GdType` 的 metadata family，例如：

- builtin operator metadata
- constructor metadata / availability
- constants
- property readable / writable flags
- class traits，例如 `isKeyed`、`isInstantiable`

因此未来若继续扩张范围，应按 metadata family 收口，而不是试图让一个大而全的“typed getter 集合”承担所有事实。

### 8.3 typed-dictionary 仍是独立合同，不应被顺手并入 callable parser

`typeddictionary::K;V` 不是本计划的 exact-call regression 主命中面，但 shared parser 现在已经需要理解它。  
后续若进入 property / signal / outward metadata 工程，仍必须把它视为独立合同问题，而不是把“parser 已能读懂 exported spelling”误当成整个 typed-dictionary 工程已经完成：

- 先定义它进入 shared typed contract 的允许子集
- 再决定是否保留 origin / hint / usage 级信息
- 最后才是 parser 细节

在这之前，typed-dictionary 仍应遵守已有事实源：

- `doc/module_impl/backend/typed_dictionary_abi_contract.md`

### 8.4 compatibility policy 不得被静默收紧

审阅过程中已经确认，当前代码库存在明确的 compatibility 合同：某些 extension metadata consumer 仍允许 unknown object leaf 通过 compatibility parser 保持容器形状与 guessed object identity。  
因此未来若扩张 shared normalization 范围，必须先显式回答：

- 哪些 extension metadata family 继续保留 compatibility object-leaf guessing
- 哪些 family 只对 stock extension API dump 做 strict normalization
- 是否有任何行为会被有意收窄

不能以“内部实现更整洁”为理由，默默把现有 compatibility 行为收紧成 strict parser。

### 8.5 未来若继续做大范围治理，应先补 metadata fact matrix

本计划已经把 exact-call 问题收窄到 callable boundary。  
如果未来重新进入 property / signal / class-level metadata 的 broader work，推荐先补一页 metadata fact matrix，而不是直接开做：

- 当前事实源在哪
- 目标 normalized surface 是什么
- 哪些层允许消费
- 哪些 family 仍需保留 raw/exported/compatibility side channel
- 是否需要保留 origin / hint / usage / raw literal

没有这张矩阵，后续大概率会再次把 boundary 争论推迟到实现阶段。

### 8.6 raw side channel 的收缩应按 family，而不是按口号

“frontend/shared 不得看到 raw side channel”这种口号本身不足以指导实现。  
更可执行的未来方向应是：

- 先让 callable boundary 不再回读 raw exported text
- 再分别处理 property / signal / class-level traits
- 对仍未收口的 family，保留显式命名的 compatibility/exported API

也就是说，未来如果真的继续缩 raw DTO 表面，应按 family 收口，而不是一次性宣称“shared 层全面禁 raw”。

### 8.7 默认参数统一应先收口到 resolved callable boundary，而不是先扩 `ParameterDef`

这次 exact-call 计划故意没有把 default descriptor 一起带进 boundary record。  
这是有意的收边界，不代表默认参数问题不存在。更广义地说，后续若要继续处理默认参数，应优先考虑：

- 先在 `ScopeMethodParameter` / resolved callable signature 一级统一 default source kind
- 再让 frontend omission 判定、backend materialization 读取这份 resolved projection
- 最后才判断是否值得扩到 `ParameterDef`

不要把脚本 default-function、AST default expression、extension exported literal 过早压成同一公共 getter。

### 8.8 registry 生命周期与失败语义在大范围治理前必须先冻结

本计划故意避免进入 registry-wide normalization。  
如果未来真的要改 shared publication layer，必须先明确：

- `ClassRegistry` 是否被当作 immutable snapshot
- extension normalization 是 eager build-pass 还是 lazy normalization + cache
- malformed metadata 的失败时机是 registry 初始化期还是 use-site

这些选择不先定，任何“大范围 definition normalization”都会很快从实现问题升级成生命周期和错误语义问题。
