# Frontend Rules

## 恢复约定

- frontend 对普通源码错误必须优先通过 `DiagnosticManager` 发诊断，不要把异常当成常规控制流。
- 当某个 AST 节点树已经无法稳定产生产物时，当前 phase 必须跳过该节点树，并继续处理同一 module 中其他仍可恢复的节点树。
- 对 deferred subtree 的 warning 与 unsupported feature boundary 的 error，都应优先锚定到被跳过子树的根节点；若无法识别更大的恢复根，才允许退化到节点自身这一最小 skipped root。
- 只有 programmer error、共享 side-table 破坏、协议不变量失真等不可恢复 guard rail，才允许抛异常；`FrontendSemanticException` 不作为普通源码错误的主路径。

## 诊断约定

- parser 必须保持 tolerant：`gdparser` lowering diagnostics 映射为 `parse.lowering`，parser/runtime 失败映射为 `parse.internal`，不要把运行时异常直接抛给调用方。
- skeleton / 当前 analyzers / 后续新增 frontend phase 对可恢复错误都必须采用“diagnostic + skip subtree”策略；不要因为单个坏节点打断整条 frontend pipeline。
- 若 skeleton phase 已判定某个 member subtree 必须跳过，必须把该 root 显式发布到 `FrontendAnalysisData.skippedSubtreeRoots()`，并由 scope phase 停止为该 subtree 发布 scope；后续 analyzer 只能沿用既有 skipped-subtree 合同恢复，不得再假设这些节点仍拥有完整 skeleton metadata。
- 新增 frontend 诊断或恢复路径时，必须同步更新 `diagnostic_manager.md`、相关实现注释和受影响的模块文档，避免代码与文档冲突。
- 当前合同中“已识别但明确不支持”的 feature boundary 统一发 error；只有真正的 deferred/暂缓恢复路径才保留 warning。
- body phase 的 diagnostic owner 必须保持单一：
  - top binding 负责 bare `TYPE_META` ordinary-value misuse 的首条 `sema.binding`
  - chain binding 负责 `sema.member_resolution` / `sema.call_resolution` / chain deferred/unsupported boundary
  - expr analyzer 负责 `sema.expression_resolution` / `sema.deferred_expression_resolution` / `sema.unsupported_expression_route` / `sema.discarded_expression`
  - var-type-post analyzer 负责 `sema.variable_slot_publication`
  - type-check analyzer 负责 `sema.type_check` / `sema.type_hint`
  - annotation-usage analyzer 负责 `sema.annotation_usage`
  - loop-control analyzer 负责 `sema.loop_control_flow`
  - compile-only `FrontendCompileCheckAnalyzer` 负责 `sema.compile_check`
  - 若同一根源错误已经有 upstream diagnostic，下游 analyzer 只能保留 side-table status，不得再补第二条同级错误
- `break` / `continue` 的位置合法性属于 shared semantic contract；`FrontendLoopControlFlowAnalyzer` 必须在进入 compile-only gate 前就对非法 loop control 发出 `sema.loop_control_flow`，lowering 中的 loop-frame fail-fast 只能保留为实现不变量保护。
- `_field_init_`、`_field_getter_`、`_field_setter_` 是 compiler-owned synthetic property helper 前缀；source class member 一旦以这些前缀开头，skeleton phase 必须发出清晰的 `sema.class_skeleton` 并跳过该 member subtree，而不是等到 lowering/backend 再因 helper 名冲突抛异常。
- `FrontendCompileCheckAnalyzer` 只能挂在 compile-only 入口上；默认共享 `FrontendSemanticAnalyzer.analyze(...)`、inspection 与未来 LSP 入口不得隐式附带 compile-only gate。
- compile-only gate 只允许扫描未来 lowering 会消费的 compile surface：supported executable body 与 supported property initializer island；不得重新深入 parameter default、lambda、`for`、`match`、block-local `const` 或 skipped subtree。
- compile-only gate 一旦放行 supported property initializer，默认 lowering pipeline 必须把它 materialize 为真实 `init_func` helper；backend 不得再把同名 shell-only function 当作可修补中间态消费。
- compile-only gate 对已发布 side-table 事实的最终阻断范围固定为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`；`DYNAMIC` 继续保留为 frontend 已认可的 runtime-open 事实，不得在 compile gate 中误判成 blocker。
- executable-body body lowering 对 call 的 lowering-ready surface 必须与 compile gate / CFG builder 保持一致：`resolvedCalls()` 只要已发布为 `RESOLVED` 或 `DYNAMIC` 就允许进入 body pass；其中 `DYNAMIC_FALLBACK` 当前只允许 instance route，必须继续复用 `CallMethodInsn`，结果类型真源来自 call anchor 的 `expressionTypes()`，frontend 不得为该路由重做 callable signature 推导；runtime-open 调用本身仍由 backend dynamic dispatch 承接，但其已发布的 `Variant` 结果若继续跨越 ordinary typed boundary，则仍由 frontend ordinary boundary helper 负责后续 `(un)pack`。长合同以 `frontend_dynamic_call_lowering_implementation.md` 为准。
- compile-only gate 还必须检查 lowering-only published fact 的缺洞：若 supported callable-local `var` 因已登记为 non-error blocker 的 diagnostic（当前为 `sema.variable_slot_publication`）仍缺少 `slotTypes()`，compile gate 必须补发 `sema.compile_check` error 并阻止进入 lowering。
- compile-only gate 的去重规则不得继续写死单个 category；哪些 upstream diagnostic 与 `sema.compile_check` 不冲突、因此允许共存，必须通过静态 category 配置维护。
- `assert` 在共享语义路径中继续沿用 Godot-compatible condition contract；compile-only `FrontendCompileCheckAnalyzer` 只是暂时阻断 statement 自身，不得把它回退成 `sema.type_check` 或 grammar unsupported。
- 脚本类 `static var` 当前属于“frontend 已识别但 compile target 明确不接受”的 declaration-level compile boundary；共享语义可继续发布 metadata，但 compile-only gate 必须在进入 lowering/backend 前显式封口。
- `ConditionalExpression`、`ArrayExpression`、`DictionaryExpression`、`PreloadExpression`、`GetNodeExpression`、`CastExpression`、`TypeTestExpression` 当前属于 frontend 已识别但 lowering 尚未就绪的 temporary compile intercept；共享语义路径可以继续发布 deferred/unstable facts，但 compile-only gate 必须在进入 lowering 前最终封口。compound assignment 已不再属于这组 temporary compile intercept。
- `ConditionalExpression` 当前之所以在 compile-only gate 中被显式封口，是因为它的 lowering 需要依赖 frontend CFG graph / condition-evaluation-region 合同先稳定；legacy `FrontendLoweringCfgPass` 已移除，但 `FrontendLoweringBodyInsnPass` 尚未接通 value merge / branch-result materialization 之前，仍不得放行进编译管线。
- 共享 `FrontendSemanticAnalyzer.analyze(...)` 的结果不是 lowering-ready 合同；未来 frontend -> LIR lowering 只能以前置的 `analyzeForCompile(...)` 结果为准，并在 diagnostics 无 error 时继续。

## 测试约定

- 每条新的 frontend 恢复规则都必须同时覆盖 happy path 与 negative path。
- negative path 至少要锚定：正确 diagnostic category、坏 subtree 被跳过、同一 module 中其他合法 subtree 仍继续工作。

## MVP 支持约定 

- 下述 MVP 约定描述的是当前 frontend 共享语义、body analyzer 与 compile surface 的正式支持面；它们不否认 parser 与 scope phase 对部分语法结构已经能识别或建图。
- `lambda`、`match`、`for` 当前不在 frontend body semantic MVP 正式支持面；相关子树仍按 deferred / unsupported boundary fail-closed。
- 协程与 signal-based coroutine 当前不在 frontend semantic MVP 范围内；`await` / `.emit(...)` 等 use-site 语义仍未闭环。
- path-based `extends`、autoload superclass、global-script-class superclass 绑定不实施。
- 多 gdcc module 的 header superclass 绑定不在最小可行产品范围内。
- 函数参数默认值当前不在 frontend body semantic MVP 范围内；相关可见性与求值顺序继续按 deferred boundary 处理。
- class constant 的收集、注册、继承可见性与绑定不在 MVP 范围内，整体延后到 MVP 之后再实施。
- callable scope / block scope 中手动声明或发布的类型别名不在 MVP 范围内；frontend body phase 必须对这类 scope-local `type-meta` 采用 fail-closed 的 deferred / unsupported 处理，而不是把它们当成普通 class-like `TYPE_META` 消费。
- H1 subscript MVP 只正式支持 container family 的最小 typed contract：`Array[T]`、`Dictionary[K, V]`、packed array family。
- 上述 container-family subscript 当前故意复用 `ClassRegistry.checkAssignable(...)` 做 key/index 校验；MVP 不追求复刻 Godot 更宽的 keyed/index 兼容规则，例如 `String` / `StringName` 互通、`int -> float`、以及 `Array` / packed array 的 float index 兼容。
- builtin keyed access 即使在 extension metadata 中声明了 `isKeyed`，当前也不属于 MVP 支持面；frontend 必须发出显式 `UNSUPPORTED`，而不是猜测 `String` / `Vector*` / `Color` / `Basis` / `Transform*` / `Object` 等 builtin keyed route 的结果类型。
- H2 assignment compatibility 的具体 source/target 规则以 `frontend_implicit_conversion_matrix.md` 为唯一真源；`FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)` 只是 concrete slot gate 的统一入口，不得在其他 frontend 路径里各自复制一份 conversion 清单。
- `DYNAMIC` target 的 runtime-open 处理仍属于 assignment semantic helper 的内聚语义；其他 frontend 路径若只需要 concrete slot 兼容判断，必须调用 `checkAssignmentCompatible(...)`，不要各自硬编码 `Variant` 分支。
- 除 `DYNAMIC` target 的 runtime-open 语义外，frontend 若需要调整 typed boundary compatibility，必须先更新 `frontend_implicit_conversion_matrix.md`，再改 shared helper、测试与下游 materialization；不得直接在某个 consumer 中偷偷放宽 `int -> float`、`StringName` / `String` 等 widened conversion。
- source-level `if` / `elif` / `while` / `assert` condition 当前采用 Godot-compatible 合同：frontend 只要求 condition root 已稳定发布 typed fact，不再把非 `bool` 一概当作 `sema.type_check`。
- `frontend.lowering.cfg` 中 `FrontendIfRegion` / `FrontendElifRegion` / `FrontendWhileRegion` 的 `conditionEntryId` 表达的是整个 condition subgraph 的稳定入口；consumer 与测试都不得假设固定 `SequenceNode -> BranchNode` 两节点模板。
- `FrontendCfgGraph.BranchNode.conditionRoot` 表达的是“当前 branch 直接测试的 condition fragment root”，必须与 `conditionValueId` 的直接 producer subtree 对齐；它不保证等于外围 source-level condition 的最外层根，也不承诺可以仅凭 `conditionValueId` 从整个 condition region 中反推出唯一一个 producer item。
- short-circuit lowering 现已要求每个 `BranchNode.conditionValueId` 都保持为当前 fragment 自己计算出的 branch-local 独立 value id；不得复用 value-context `and` / `or` 的 outward-facing merge result value id 作为 branch condition id。
- frontend CFG value id 默认仍是 single-definition 合同，但有一个刻意保留的例外：同一个 outward-facing merged result value id 可以由多个 `MergeValueItem` 在互斥路径上写入。
- 这个例外必须保持收口：若同一个 value id 出现多个 producer，则所有 producer 都必须是 `MergeValueItem`；`MergeValueItem.resultValueId` 不允许与 `OpaqueExprValueItem`、`CallItem`、`CastItem`、`BoolConstantItem` 等普通 producer 共享同一个 value id。
- 任何按 value id 收集 producer 的代码、测试或 future lowering 都必须按“可能有多个 reaching producers”建模；不得把 merged result 当作可唯一反查的 SSA expression definition。
- `assert` 的 compile-only block 不改变这条 source-level 合同；真正的 backend / lowering 缺口必须继续留在 compile gate，而不是反向污染 shared type-check 规则。
- backend/LIR 的 control-flow 仍保持 bool-only 边界；当未来接上 frontend -> LIR lowering 时，必须在 lowering 侧补上显式 truthiness / condition normalization，不得再反向把 frontend 收紧成 undocumented strict-bool dialect。
- lowering 侧的 condition normalization 合同已经冻结：`bool` 直接 branch，`Variant` 只做 `unpack_variant -> bool temp -> GoIfInsn`，其余 stable type 必须先 `pack_variant` 再 `unpack_variant`，不得绕过这条路径。
- body lowering 的三类局部变量命名必须固定：CFG value temp slot 用 `cfg_tmp_<valueId>`，merge slot 用 `cfg_merge_<valueId>`，source-level local 直接沿用源码名；不得在实现期临时发明第二套命名。
- `OpaqueExprValueItem` 当前只允许承载 leaf / eager unary / 非短路 eager binary；`and` / `or`、assignment-as-opaque、以及绕过 dedicated item 的 attribute / call / subscript 必须视为协议违例。
- value-context `and` / `or` 的 LIR 形态必须保持为“branch + branch-local bool constant + merge slot assign”；不得生成 `BinaryOpInsn(AND/OR)`。
- `FrontendTopBindingAnalyzer` 当前只发布 symbol category，不区分 read / write / call 等 usage 语义；assignment 左值链头等 use-site 也可能进入 `symbolBindings()`。
- 若后续 frontend 需要记录完整用法，必须扩展 `FrontendBinding` 模型，不要依赖当前 binding kind 反推读写调用语义。
- `ScopeValue.writable` 当前只表达 bare identifier direct-write contract；不要把它误当成完整的 member/container/property mutation 语义模型。
- property writable 判定必须统一复用共享 helper，而不是在 scope publication、assignment analyzer、其他 frontend 路径里各自硬编码 engine/builtin property metadata 分支。
- property initializer 的 MVP 支持面是“published subtree facts”，不是“完整 class-member initializer 语义”。
- supported property initializer 当前已经属于 compile-ready lowering surface：它们在默认 pipeline 终态拥有真实 CFG/LIR helper body，但这不等于完整实例初始化时序语义已经闭环。
- 脚本类 static property declaration 当前不在 compile-ready 支持面；即使它在 shared semantic 中可被识别，也必须由 compile-only gate fail-closed，而不是等 backend 在 codegen 阶段拒绝。
- MVP 不支持 property initializer 访问同 class 下的 non-static property / method / signal / `self`；这类访问必须 fail-closed，而不是假装已经拥有 declaration-order / default-state / cycle-aware 语义。
- property initializer 若确实需要静态 helper，优先通过 global name、type-meta route 或其他不依赖当前类实例状态的路径进入；不要把当前类 direct member namespace 当成实例初始化期可见性模型。
- property `:=` 在 MVP 中不支持类型推导，也不会因为 RHS 稳定类型而回写 class property metadata。
- property `:=` 与未声明显式类型 property 在 type-check 中仍按普通 initializer expression 处理；若 RHS 已稳定，type-check analyzer 需要发 `sema.type_hint` warning，提示用户手动补写建议的显式类型。
- `sema.type_hint` 的职责是提醒用户手动添加显式类型，而不是暗中把 property 当成已经推导完成的 typed slot。
- `@onready` 的 MVP 合同当前是“annotation retention + usage validation”，不是完整 ready-time 执行模型。
- `@onready` 的最小合法性规则固定为：只能用于 Node 派生类中的 non-static class property；相关非法用法由独立的 `sema.annotation_usage` owner 负责，不应混入 `sema.unsupported_annotation` 或 `sema.type_check`。
- `not in`运算符语法糖在MVP版本中不支持。
