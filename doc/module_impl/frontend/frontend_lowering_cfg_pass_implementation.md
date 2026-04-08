# Frontend Lowering CFG Pass 实现说明

> 本文档作为 frontend lowering 中 CFG build / executable-body materialization 的长期事实源，记录当前已经稳定落地的 frontend-only CFG graph、`FrontendLoweringBuildCfgPass` 与 `FrontendLoweringBodyInsnPass` 的职责边界、published-fact 消费合同、constructor materialization 与 compound assignment 的当前实现，以及对后续扩面仍然有效的约定。本文档吸收并取代原 `frontend_lowering_cfg_graph_plan.md` 与 `frontend_lowering_cfg_pass_plan.md` 的已完成内容，不再保留实施步骤、阶段状态或验收流水账。

## 文档状态

- 状态：事实源维护中（executable-body CFG build / body lowering、property-initializer CFG/body lowering、constructor materialization、compound assignment、`StopNode.kind` 空-return 图修复均已落地；parameter default 仍未接通）
- 更新时间：2026-04-06
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/cfg/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/pass/**`
  - `src/test/java/dev/superice/gdcc/frontend/lowering/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `frontend_lowering_plan.md`
  - `frontend_lowering_func_pre_pass_implementation.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_analysis_inspection_tool_implementation.md`
  - `frontend_loop_control_flow_analyzer_implementation.md`
  - `diagnostic_manager.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
- 明确非目标：
  - 不在这里引入 high-level IR / sea-of-nodes
  - 不在这里放行 `ConditionalExpression`
  - 不在这里把 parameter default 接到 body pass
  - 不在这里让 lowering 重跑 chain reduction、call route 选择或表达式求值顺序推导

---

## 1. 当前定位

当前默认 frontend lowering pipeline 固定为：

1. `FrontendLoweringAnalysisPass`
2. `FrontendLoweringClassSkeletonPass`
3. `FrontendLoweringFunctionPreparationPass`
4. `FrontendLoweringBuildCfgPass`
5. `FrontendLoweringBodyInsnPass`

这条链路的稳定入口和前置条件是：

- lowering 入口固定为 `FrontendModule`
- compile-ready 语义事实统一来自 `FrontendSemanticAnalyzer.analyzeForCompile(...)`
- 只有在 `analysisData.diagnostics().hasErrors() == false` 时 lowering 才继续
- function-shaped lowering 单元统一经由 `FunctionLoweringContext`
- public lowering 返回值是“executable body 与 compile-ready property-init helper 都已 materialize”为真实函数体的 `LirModule`

当前 pipeline 的产物边界固定为：

- `EXECUTABLE_BODY`
  - 发布 frontend CFG graph
  - 发布 frontend CFG regions
  - materialize real `LirBasicBlock` / terminator / instruction
- `PROPERTY_INIT`
  - 发布 expression-rooted frontend CFG graph
  - 不伪造 `Block` 或 property-init-only node kind
  - 复用同一套 body lowering session materialize `LirBasicBlock` / instruction
- `PARAMETER_DEFAULT_INIT`
  - 只保留 context kind 与模型槽位，不接入默认 pipeline

---

## 2. Legacy 迁移结论

旧的 `FrontendLoweringCfgPass` 与 `FunctionLoweringContext.cfgNodeBlocks` 已经从默认 pipeline 与代码中移除。它们只表达过 metadata-only block bundle，不再是当前实现的一部分。

这次迁移里真正保留下来的稳定结论只有三条：

- frontend CFG 必须继续以 `FunctionLoweringContext` 为函数级 carrier，而不是发明第二套 lowering 入口
- AST identity keyed side table 方向是正确的，region / semantic anchor 继续沿用 parser 节点 identity
- compile gate 负责拦截 non-lowering-ready surface；lowering 不重复扫描源码去补第二套编译阻断

旧 block-bundle 方案已经证明不足以承担当前 lowering：

- 不能表达显式 `conditionEntryId`
- 不能表达 source truthy condition 到 bool-only branch 的 normalization 过渡
- 不能表达 `and` / `or` 的 condition/value 双路径短路
- 不能表达 `while` 中 `break` / `continue` 的稳定跳转语义
- 不能为 `ConditionalExpression` 预留 branch-result merge 入口

因此当前长期约束是：

- 新增 lowering 需求只能落在 `frontend.lowering.cfg` + `FrontendLoweringBuildCfgPass` + `FrontendLoweringBodyInsnPass`
- 不得恢复或重建 `CfgNodeBlocks` 风格的过渡 side table
- 不得让 graph 与 legacy block bundle 双写并长期漂移

---

## 3. Frontend CFG 模型

frontend CFG 是 frontend-only 中间层，位于真实语义事实与 LIR basic block 之间。它只负责整理 source-level control flow、value flow 与 condition-evaluation-region，不承担读写 runtime instruction 的最终责任。

### 3.1 Node 形状

当前 `FrontendCfgGraph` 固定为三种 node：

- `SequenceNode`
  - `id`
  - `items`
  - `nextId`
- `BranchNode`
  - `id`
  - `conditionRoot`
  - `conditionValueId`
  - `trueTargetId`
  - `falseTargetId`
- `StopNode`
  - `id`
  - `kind`
  - `returnValueIdOrNull`

`StopNode.kind` 当前固定为两类：

- `RETURN`
  - 真实 callable exit
  - `returnValueIdOrNull` 可为空，表示 bare `return` 或 `nil`-equivalent return 路径
- `TERMINAL_MERGE`
  - 仅用于 frontend CFG 内部表达“该结构化分支链已全部终止”的 synthetic anchor
  - 不代表真实源码 `return`
  - 不得携带 `returnValueIdOrNull`
  - 不得作为 graph entry，也不得作为任何 executable edge 的 target；它只允许通过 region `mergeId` 被结构化 side table 引用

fully-terminated 的 `if` / `elif` / `else` 允许把 region `mergeId` 指向 `StopNode(kind = TERMINAL_MERGE)`，以保留结构化 region 事实；但该 node 只服务于 frontend graph/测试观察，不允许在 body lowering 中翻译成真实 `ReturnInsn`。

`BranchNode.conditionRoot` 的稳定含义是：

- 它必须是直接产出 `conditionValueId` 的 condition fragment root
- 它不要求等于外围 `if` / `elif` / `while` 的最外层 source condition
- `not expr` 路径允许保留 `expr` 作为 root，再通过 target inversion 表达取反
- `and` / `or` 的每个短路 split 都必须绑定各自的 fragment root，而不是重复悬挂外层 shell

`BranchNode.conditionValueId` 当前允许是非 `bool` source value。bool-only normalization 固定在 frontend CFG -> LIR lowering 阶段完成，而不是在 graph publication 阶段抢先收紧 source contract。

### 3.2 Region side table

`FunctionLoweringContext` 当前会为 executable body 同时发布 AST identity keyed `frontendCfgRegions`。稳定 region 形状包括：

- `BlockRegion`
- `FrontendIfRegion`
- `FrontendElifRegion`
- `FrontendWhileRegion`

这张 side table 当前承担两类职责：

- 让 CFG/测试能够按 AST identity 回读结构化入口
- 给 loop-control 与 future condition-value feature 提供稳定的结构锚点

### 3.3 构图状态机

`FrontendCfgGraphBuilder` 继续采用显式状态机构图，而不是 generic AST walker callback 主导。当前必须显式维护的状态包括：

- lexical continuation
- currently writable sequence
- loop stack
- value-context / condition-context 分工

这是当前实现固定的设计结论，不属于可随意替换的风格偏好。

---

## 4. Sequence Item 与 Value Id 合同

`SequenceNode.items` 当前已经冻结为“线性执行内容”，而不是 statement passthrough。

稳定 item 分层为：

- `SourceAnchorItem`
- `ValueOpItem`

当前已落地的 `ValueOpItem` 子类包括：

- `OpaqueExprValueItem`
- `BoolConstantItem`
- `LocalDeclarationItem`
- `AssignmentItem`
- `CompoundAssignmentBinaryOpItem`
- `MemberLoadItem`
- `SubscriptLoadItem`
- `CallItem`
- `MergeValueItem`
- `CastItem`
- `TypeTestItem`

这里的核心合同是：

- child subtree 先产出 value id
- parent item 只消费 operand value ids
- body lowering 不允许回到原 AST 子树重做第二套递归 lower

plain assignment、compound assignment 与 constructor materialization 当前各自冻结为：

- `AssignmentItem`
  - 只表示最终 store commit
  - target receiver/index operand 与将要写回的 RHS value id 都必须预先冻结
- `CompoundAssignmentBinaryOpItem`
  - 只表示 compound assignment 的当前值读取结果与 RHS 之间的 binary op
  - 不承载最终写回
  - 其 result value id 必须再由后续 `AssignmentItem` 提交到 target
- `CallItem`
  - 同时承载 ordinary call 与 constructor call
  - runtime-open `DYNAMIC_FALLBACK` instance call 继续复用同一个 item，不新增 dynamic-call 专用 CFG item
  - constructor route 不新增专用 CFG item
  - CFG 继续负责冻结 operand 顺序、anchor 与 result value id
  - 若某个 call site 后续需要 mutating receiver writeback，则同一个 `CallItem` 还必须承载单个 writable receiver access-chain payload
  - 这条 chain payload 必须以“整条 route”的形式冻结；CFG 不得为同一个 call receiver 再发布一串额外 step item 让 body lowering 事后拼装
  - call result runtime type 的真源是 call anchor 对应的 `expressionTypes()`；`resolvedCalls()` 只负责 route fact，不是 `DYNAMIC` call result type 的唯一来源

当前 body lowering 侧已经把 writable-route 的 leaf read / leaf write / reverse commit 共用逻辑收敛到 package-private
`FrontendWritableRouteSupport`。在 CFG payload 尚未扩展完成之前，assignment / member load / subscript load / direct-slot call receiver
仍基于现有 published operand surface 组装 route，但 lowering 不再各自维护一套平行 writeback 逻辑。

其中 compound assignment 的 source-order 合同固定为：

1. 先冻结 assignment target 所需的 receiver/index/prefix operand
2. 再基于这些 frozen operand 读取当前 target value
3. 再求值 RHS
4. 再发 `CompoundAssignmentBinaryOpItem`
5. 最后用 `AssignmentItem` 走普通 store 路径提交结果

value id 当前“基本单一定义”，但有一条刻意保留的窄例外：

- 同一 outward-facing result value id 可以被多个 `MergeValueItem` 沿互斥路径写入
- `MergeValueItem.sourceValueId` 必须来自同一个 `SequenceNode` 中更早出现的某个 `ValueOpItem.resultValueId`
- graph publication 必须在发布时验证这条“同 sequence、先 producer 后 merge”合同；type collection / body lowering 不负责为跨 sequence 或逆序 merge source 做补救

因此所有 consumer 都必须接受：

- merge result 的读取真源是 merge slot
- 不能假设“一个 value id 只有一个 producer”
- 不能把 merge value 再压平成唯一 SSA 定义

---

## 5. 条件与短路合同

frontend 与 LIR 当前同时冻结了两条事实：

- source-level condition 继续采用 Godot-compatible truthy contract
- backend / LIR control flow 继续保持 bool-only branch 边界

frontend CFG / body lowering 当前用以下方式闭合这组约束。

### 5.1 Condition context

`buildCondition(...)` 当前固定行为：

- `not expr`
  - 不额外生成无意义值
  - 通过 true/false target inversion 表达
- `and` / `or`
  - 直接展开短路分支
  - 每个 split 都绑定自己的 fragment-local `conditionValueId`
- 其他 condition
  - 先经由 `buildValue(...)` 求出 source value
  - 再发布 `BranchNode`

### 5.2 Value context

`and` / `or` 在 value context 中当前绝不允许退化成 eager binary。

当前固定形态是：

- branch
- path-local `BoolConstantItem`
- `MergeValueItem`
- merge continuation

对应 LIR 产物也已冻结为：

- `GoIfInsn`
- branch-local `LiteralBoolInsn`
- 写入 `cfg_merge_<valueId>` 的 `AssignInsn`

当前明确禁止出现：

- `BinaryOpInsn(AND)`
- `BinaryOpInsn(OR)`

### 5.3 Bool-only normalization

frontend CFG -> LIR body lowering 当前统一复用以下 normalization 规则：

- `bool` source
  - 直接 `GoIfInsn`
- `Variant` source
  - 直接 `UnpackVariantInsn -> bool temp -> GoIfInsn`
- 非 `bool` 且非 `Variant` source
  - `PackVariantInsn -> UnpackVariantInsn -> bool temp -> GoIfInsn`

条件临时槽位命名固定为：

- `cfg_cond_variant_<valueId>`
- `cfg_cond_bool_<valueId>`

---

## 6. Slot Naming 与 published fact 真源

当前 body lowering 只允许消费 published facts，不能再透视 scope 私有状态或临时重建语义。

### 6.1 三类局部槽位

当前固定的命名与类型来源为：

- CFG temp value
  - 命名：`cfg_tmp_<valueId>`
  - 类型来源：对应 CFG producer item 消费的 published fact
- merge result
  - 命名：`cfg_merge_<valueId>`
  - 类型来源：merged result value id 的 published type
- source-level local variable
  - 命名：沿用源码名
  - 类型来源：`analysisData.slotTypes()`

此外，instance executable function 当前会在必要时补出 `self` local slot，供以下路径统一复用：

- bare instance call
- bare property access
- `self` expression

### 6.2 允许读取的 published facts

当前 body lowering 只允许读取：

- `analysisData.symbolBindings()`
- `analysisData.resolvedMembers()`
- `analysisData.resolvedCalls()`
- `analysisData.expressionTypes()`
- `analysisData.slotTypes()`

当前明确禁止：

- 重跑 chain reduction
- 重选 bare call overload / route
- 为 local slot type 重新透视 `scopesByAst()` / `BlockScope`
- 重新推断哪些 child 先求值

### 6.3 `expressionTypes()` 的 key-space

`analysisData.expressionTypes()` 当前明确不是 `Expression`-only key-space。正式 published key 包括：

- ordinary expression root
- `AttributePropertyStep`
- `AttributeCallStep`
- `AttributeSubscriptStep`

因此 lowering helper 若扫描或读取该表，必须把“step keyed published fact”视为正式输入，而不是兼容例外。

`AttributeSubscriptStep` 现在已经是 compile-ready lowering 的正式 key。直接结果是：

- body-lowering session / processor 可以直接读取 subscript step 的 published expression type
- compile-check 可以把 step 本身作为 compile anchor
- lowering 不再保留“step type 尚未发布”的临时分支

若有人绕过 compile gate，step fact 仍停留在 `FAILED` / `UNSUPPORTED` / 其他无 `publishedType` 状态，body lowering 会抛出包含以下信息的异常：

- step 名称
- published status
- detail reason
- “本应由 `FrontendCompileCheckAnalyzer` 先行拦截”的提示

这条异常是绕过 compile gate 时的保底定位信息，不是正常 compile 路径的主要 owner。

---

## 7. Pass 职责边界

### 7.1 `FrontendLoweringBuildCfgPass`

当前只负责：

- 消费 compile-ready `EXECUTABLE_BODY` / `PROPERTY_INIT` context
- 调用 `frontend.lowering.cfg` 下的 builder
- 发布 `frontendCfgGraph`
- 为 executable body 发布 `frontendCfgRegions`
- 对 property initializer 校验 `sourceOwner == property declaration`、`loweringRoot == initializer expression`

当前不负责：

- 写 `LirBasicBlock`
- 设置 `entryBlockId`
- materialize instruction
- 处理 parameter default
- 为 property initializer 发布伪造的 block/loop region

### 7.2 `FrontendLoweringBodyInsnPass`

当前只负责：

- 消费 frontend CFG graph/region 与 published facts
- 声明 lowering local/temp/merge slots
- 将可执行 graph node id materialize 为 `LirBasicBlock.id`
- 将 `SequenceNode` lower 成 instruction 序列
- 将 `BranchNode` lower 成 bool-only branch terminator
- 将 `StopNode(kind = RETURN)` lower 成 `ReturnInsn`
- 保留 `StopNode(kind = TERMINAL_MERGE)` 在 frontend CFG 中，但不为其创建 LIR basic block
- `FrontendBodyLoweringSupport.collectCfgValueSlotTypes(...)` 只按 graph 已发布的 node/item 顺序收集类型；它依赖 graph publication 已经验证 merge source 的本地先后合同，而不是自己跨 sequence 回溯 producer

当前内部组织也已经冻结为以下形状：

- `FrontendLoweringBodyInsnPass` 本体只保留 compile-ready function context 调度；当前默认 pipeline 覆盖 executable-body 与 property-init，parameter-default 继续显式 fail-fast
- 真实的 per-function lowering state 收口到 `frontend.lowering.pass.body.FrontendBodyLoweringSession`
- CFG node、`SequenceItem`、opaque expression root、assignment target / attribute step 都通过 `FrontendInsnLoweringProcessor` 注册表按“当前节点实际类型”动态分派
- `FrontendBodyLoweringSupport` 只保留 slot naming、condition temp naming、published type collection 这类跨节点通用 helper；branch/subscript/opaque-expression 等节点专属逻辑都留在各自 processor 邻域

### 7.3 owner 边界

当前 owner 划分已经冻结为：

- shared semantic / type-check
  - 不把 upstream `FAILED` / `UNSUPPORTED` / `BLOCKED` / `DEFERRED` 改写成新的 type-check error
- `FrontendCompileCheckAnalyzer`
  - 负责把 non-lowering-ready compile surface 在进入 lowering 前拦截下来
- body lowering
  - 只保留 invariant guard rail 与绕过 compile gate 时的高质量异常

---

## 8. 当前 compile-ready executable surface

当前 compile-ready executable surface 已经稳定支持：

- straight-line executable body
- `if` / `elif` / `else`
- `while`
- loop-local `break` / `continue`
- value-context 与 condition-context 的 `and` / `or` / `not`
- identifier / literal / `self`
- eager unary 与非短路 eager binary
- bare/global/static/instance method call（含 runtime-open dynamic instance call）
- member/property access
- subscript
- plain assignment
- compound assignment
- constructor materialization
- callable-local slot type published contract

### 8.1 Ordinary `Variant` boundary materialization

ordinary `Variant` boundary materialization 现在已经冻结为 executable-body body pass 的正式合同：

- local initializer / bare assignment / attribute-property assignment
  - target slot 是 `Variant` 时显式插入 `PackVariantInsn`
  - stable `Variant` source 流向 concrete target 时显式插入 `UnpackVariantInsn`
- fixed call arguments / vararg tail
  - fixed parameter 按 selected callable signature 的 parameter type 做 ordinary boundary materialization
  - vararg tail 统一按 `Variant` tail 处理，concrete extra arg 先 pack，stable `Variant` extra arg direct
- `DYNAMIC_FALLBACK` instance call
  - frontend 不读取 callable signature，也不做 fixed-parameter boundary materialization
  - 已求值的 argument slot 直接透传给 `CallMethodInsn`
  - receiver 若已由 CFG 发布为 writable access-chain payload，则必须走独立 receiver-side writable-route core；这不属于 ordinary argument boundary 合同
  - backend dynamic dispatch 继续承担 runtime-open call 的实际分派与直接 `Variant` 结果发布
  - 该 `Variant` 结果若随后跨越 ordinary typed boundary，再由 frontend ordinary boundary helper 做后续 `(un)pack`
- return
  - stop-node lowering 按当前函数 return slot type 做同一套 boundary materialization

这条 ordinary-boundary helper 与 condition normalization 是两条并列但不同的合同：

- condition 只在 `FrontendCfgNodeInsnLoweringProcessors` 中做 bool-only branch normalization
- local / assignment / call / return 统一走 `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`
- `materializeFrontendBoundaryValue(...)` 只负责物化 `frontend_implicit_conversion_matrix.md` 已允许、且已由 shared semantic helper 放行的 ordinary boundary；不得在 lowering 侧独立新增 conversion
- ordinary boundary consumer/materialization 的长合同以 `frontend_lowering_(un)pack_implementation.md` 为准

### 8.2 Constructor materialization 合同

constructor route 当前已经闭合为 compile-ready executable surface 的正式部分。

semantic / published-fact 合同：

- object constructor 语法入口使用 type-meta `.new(...)`
  - 例如 `Node.new()`、`Worker.new()`
- builtin direct constructor 使用 bare call route
  - 例如 `Array(...)`、`Vector3i(1, 2, 3)`、`Color(...)`
- 以上两类入口统一发布为 `FrontendResolvedCall(callKind = CONSTRUCTOR)`
- downstream 不得再通过 syntax shape 区分 constructor route kind

CFG / body-lowering 合同：

- constructor 继续复用现有 `CallItem`
- type-meta chain head 不 materialize 头部 identifier 为运行时值；第一个 lowering step 直接从已发布的 type-meta fact 进入：
  - static member load 产出 `MemberLoadItem(..., null, ...)`
  - static/constructor call 产出 `CallItem(..., null, ...)`
- 因而 `Vector3.ZERO`、`Color.RED`、`ClassName.SOME_CONST` 以及它们后续继续链式访问的写法都属于当前 compile-ready surface
- builtin instance property read 同样已经闭环为 compile-ready surface：
  - `vector.x`、`Color(...).r`、`Basis.IDENTITY.x` 在 CFG 中继续只是 ordinary `MemberLoadItem`
  - body lowering 对其统一发出 `LoadPropertyInsn`
  - 这条 contract 不会放宽 builtin keyed access；`vector["x"]` 仍不走这里
- builtin instance property write 继续复用 ordinary assignment route：
  - `vector.x = 1.0`、`color.a = 0.5` 在 CFG 中继续只是 ordinary `AssignmentItem`
  - body lowering 对其统一发出 `StorePropertyInsn`
  - builtin member writable / missing-property policy 仍以上游 published member fact 与 shared writable helper 为真源
- body lowering 依据已发布的 constructor result type 选择 LIR：
  - builtin/container constructor -> `ConstructBuiltinInsn`
  - object constructor -> `ConstructObjectInsn`
- constructor 不伪装成 static/instance method
- body lowering 不重跑 overload 选择，不回退成语义分析

语义封口与 runtime 约束：

- gdcc 自定义类的带参 `_init(...)` 在语义阶段直接报错
- compile gate 继续保留对 parameterized gdcc constructor route 的兜底拦截，防止上游错误重新发布
- 零参数 `_init(self)` 由 runtime postinitialize / class constructor path 触发；frontend lowering 不追加 follow-up `_init(...)` 调用

overload 选择合同：

- builtin constructor 不再采用“多个 applicable 就直接歧义”策略
- 当前选择顺序固定为：
  - 先做 applicability 过滤
  - 再比较逐参数转换质量与目标类型具体度
  - 若仍无法区分，则以语义诊断 fail-closed，而不是抛异常把问题下沉到 lowering/backend

backend 相关的当前事实：

- engine class object construction 直调 gdextension-lite `godot_new_XXX()`
- gdcc class object construction 复用 `XXX_class_create_instance(...)`
- 对显式 C 构造的 gdcc `RefCounted` 对象：
  - 先调用 `XXX_class_create_instance(NULL, false)`
  - 再调用 `gdcc_ref_counted_init_raw(..., true)`
- `*_class_create_instance(...)` 本体保持 shared create/bind helper，不内嵌 `gdcc_ref_counted_init_raw(...)`
- 通过 Godot 引擎函数或 GDScript 创建继承 `RefCounted` 的 gdcc 类时，引用计数初始化继续由 Godot 自身创建路径负责

### 8.3 Compound assignment 合同

compound assignment 当前已经闭合为 compile-ready executable surface 的正式部分。

语义合同：

- 已支持的闭合集合为：
  - `+=`
  - `-=`
  - `*=`
  - `/=`
  - `%=`
  - `**=`
  - `>>=`
  - `<<=`
  - `&=`
  - `^=`
  - `|=`
- success root 继续发布 `RESOLVED(void)`
- statement-position 的 compound assignment success root 不发 discarded warning
- value-required 的 nested compound assignment 继续 fail-closed
- compound operator 的类型判定复用 ordinary binary operator 语义，不另造一套 operator typing 规则

target 支持面：

- bare identifier
- attribute property
- plain subscript
- attribute-subscript

CFG 合同：

- `AssignmentItem` 继续只表示最终 store commit
- `CompoundAssignmentBinaryOpItem` 显式冻结：
  - assignment anchor
  - binary operator lexeme
  - current-target-value id
  - rhs value id
  - computed-result value id
- builder 不得把 compound assignment 机械改写成 `lhs = lhs <op> rhs`
- target receiver/index/prefix 必须只求值一次

body-lowering 合同：

- `CompoundAssignmentBinaryOpItem` 直接 lower 为 `BinaryOpInsn`
- compound-op processor 只消费 CFG 已冻结的 binary lexeme 与 operand value ids
- compound-op processor 本身不插入额外的 assignment-boundary `(un)pack`
- 最终写回继续统一走 `FrontendAssignmentTargetInsnLoweringProcessors`
- compound temp slot 的类型必须是“真实 binary 结果类型”，而不是最终 assignment target 类型
- `Variant` pack/unpack 只允许保留在最终 assignment/store boundary，不能前移到 `BinaryOpInsn`

---

## 9. Remaining shell-only / unsupported surface

当前仍保持 shell-only、compile-block 或 fail-fast 的部分包括：

- `PARAMETER_DEFAULT_INIT` CFG / body lowering
- `ConditionalExpression`
- `CastExpression`
- `TypeTestExpression`
- `ArrayExpression`
- `DictionaryExpression`
- `PreloadExpression`
- `GetNodeExpression`
- callable-value invocation
- multi-key subscript lowering

其中 `ConditionalExpression` 继续 compile-block 的原因已经固定：

- graph 虽已具备分支区域与 merge slot 基础设施
- 但 branch-result merge、ownership、evaluation-order 语义还没有单独冻结

当前 body lowering 明确保留 fail-fast 的路径包括：

- `CastItem`
- `TypeTestItem`
- multi-key subscript lowering
- 缺失 published fact 的 call/member/value type 路径

---

## 10. 回归锚点

涉及本文档合同的改动，至少要继续覆盖以下回归锚点：

- `FrontendLoweringBuildCfgPassTest`
  - deterministic graph shape / node id
  - AST identity keyed region lookup
  - value-op operand/result id contract
  - nested call/member/subscript evaluation order
  - non-bool condition region
  - condition/value short-circuit subgraph
  - `break` / `continue`
  - compound assignment read-modify-write graph shape
  - constructor route graph publication
- `FrontendLoweringBodyInsnPassTest`
  - body lowering 只消费 graph + published facts
  - declaration / assignment / call 不做第二套 AST 递归 lower
  - value-context `and` / `or` 的 LIR 形态
  - subscript-step published type consumption
  - fully-terminated `if` chain 的 synthetic terminal-merge stop 不得产出 `ReturnInsn(null)`
  - constructor route lower 为 `ConstructBuiltinInsn` / `ConstructObjectInsn`
  - compound assignment 只在最终 store boundary 做 `(un)pack Variant`
  - compile gate 绕过时的 lowering exception 质量
- `FrontendLoweringPassManagerTest`
  - 默认 pipeline 顺序
  - executable body materialization
  - property initializer CFG publication + executable LIR body boundary
- `FrontendCompileCheckAnalyzerTest`
  - step-level / expression-level compile anchor
  - `AttributeSubscriptStep` published fact 的 compile blocker 行为
  - `ConditionalExpression` 继续 compile-block
  - parameterized gdcc constructor route 的 compile-only 兜底
- `FrontendVarTypePostAnalyzerTest`
  - parameter / typed local / `:=` local 的 slot type publication
  - duplicate / shadowing local 的 fail-closed 行为
- engine / integration tests
  - builtin direct constructor
  - engine `.new()`
  - gdcc zero-arg `.new()`
  - compound assignment 的 runtime smoke

---

## 11. 架构反思与后续要求

当前实现已经沉淀出以下长期有效的工程结论：

- frontend CFG 是 frontend-only 中间层，不是 legacy block bundle 的增强版，也不是 future HIR 的缩写版
- `FrontendLoweringBuildCfgPass` 是唯一的 CFG 构图入口
- `FrontendLoweringBodyInsnPass` 只消费 graph + published facts，不能回退成第二套语义分析器
- compile gate 负责“能否进入 lowering”，body lowering 负责“如何 materialize 已允许的 surface”
- merge result 必须继续以 merge slot 为中心建模，而不是强行恢复唯一 producer 幻觉
- constructor 与 compound assignment 都已经证明：应先冻结 published fact / graph item / owner 边界，再接 body lowering，最后再解除 compile gate

后续若继续扩张 lowering surface，必须优先遵守以下顺序：

1. 先冻结 published fact / compile gate / graph item 合同
2. 再接 `FrontendLoweringBuildCfgPass`
3. 最后接 `FrontendLoweringBodyInsnPass`

不得反过来用 body lowering 的局部实现去倒逼 semantic side table 或 compile gate 临时补洞。

---

## 12. 文档同步要求

只要 CFG graph、body lowering 或 compile-ready surface 合同发生变化，至少要同步更新：

- `frontend_lowering_plan.md`
- `frontend_lowering_func_pre_pass_implementation.md`
- `frontend_compile_check_analyzer_implementation.md`
- `frontend_chain_binding_expr_type_implementation.md`
- `frontend_analysis_inspection_tool_implementation.md`
- `frontend_loop_control_flow_analyzer_implementation.md`
- `diagnostic_manager.md`
- 本文档

若变化同时影响 constructor lowering 或 object construction backend 行为，还需同步：

- `doc/gdcc_low_ir.md`
- `doc/gdcc_c_backend.md`
- backend implementation docs
