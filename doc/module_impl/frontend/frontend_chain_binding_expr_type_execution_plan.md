# Frontend Chain Binding / ExprType 执行计划

> 本文档记录 `FrontendChainBindingAnalyzer` 与 `FrontendExprTypeAnalyzer` 的执行计划、阶段边界、MVP 支持面与数据模型演进约束。本文档是实施计划，不是当前实现事实源；已落地事实仍以对应 implementation 文档为准。

## 文档状态

- 性质：执行计划
- 更新时间：2026-03-17
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/resolver/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_top_binding_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/module_impl/frontend/scope_type_resolver_implementation.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/gdcc_type_system.md`

---

## 1. 计划目标

后续 frontend body 语义阶段的近期目标不是“先做完整表达式类型推断，再补链式 binding”，也不是“先做完整链式 binding，再补表达式类型推断”，而是：

1. 保持 frontend 顶层 phase 边界清晰。
2. 在单条链式表达式内部按从左到右局部交替推进 binding 与类型。
3. 先让 `resolvedMembers()`、`resolvedCalls()`、`expressionTypes()` 三张 side table 进入可发布状态。
4. 在 MVP 中优先覆盖 receiver 已知时的稳定路径，避免一开始就把 dynamic / constructor / static load 全部揉成一个巨型 pass。

---

## 2. 核心决策

## 2.1 整体分层

frontend 顶层 pipeline 继续保持“整体分层、单次发布”的组织方式，而不是引入 whole-module fixpoint 反复迭代。

当前稳定前序仍是：

1. skeleton
2. scope
3. variable inventory
4. top binding

后续新增 body phase 时，目标顺序为：

- `chain binding`
- `expression typing`

这里的 `chain binding -> expression typing` 表达的是**发布边界顺序**，不是要求 chain phase 在实现上完全不接触任何类型事实。

## 2.2 局部交替

单条链式表达式内部采用局部交替策略：

1. 先拿链头 binding / 头部 receiver 事实。
2. 计算当前层 receiver type。
3. 基于该 receiver type 解析当前 step 的 member / call / static access。
4. 产出当前 step 的结果类型。
5. 用该结果类型继续分析下一层。

这条规则适用于：

- `player.hp`
- `player.move(i + 1)`
- `ClassName.build()`
- `ClassName.new()`
- `EnumType.VALUE`
- `matrix[row][col].basis`

## 2.3 不采用全局 ping-pong

MVP 不采用以下策略：

- 先跑一遍完整 `FrontendExprTypeAnalyzer`
- 再跑一遍完整 `FrontendChainBindingAnalyzer`
- 再回头基于新结果重跑整个 `FrontendExprTypeAnalyzer`

原因是：

- 当前 `FrontendAnalysisData` 的 side-table 模型是“整表发布”，不是 fixpoint lattice。
- 当前 frontend 恢复合同是“diagnostic + skip subtree”，不是 speculative refinement。
- 现有 `FrontendResolvedMember` / `FrontendResolvedCall` 需要先扩充，当前占位模型无法承载多轮收敛所需的中间状态。

---

## 3. MVP 边界

## 3.1 MVP 正式支持面

MVP 目标优先覆盖以下路径：

- bare value / bare callee 继续复用已发布的 `symbolBindings()`
- instance receiver 的 property step
- instance receiver 的 signal step
- `TYPE_META` receiver 的 static method call
- receiver 已知且参数类型已知时的方法调用
- instance receiver 命中 static method 时的 resolved call + diagnostic
- `TYPE_META` 驱动的 class/static chain head
- 构造器路由
- `EnumType.VALUE` / builtin constant / engine integer constant 这类 static load 路由

## 3.2 MVP 明确非目标

以下内容仍不纳入 MVP 正式支持面：

- lambda / `for` / `match` 的 body 级链式语义转正
- 参数默认值表达式的正式 body 语义
- class constant 的正式收集、注册、继承绑定
- callable scope / block scope 中的手动类型别名消费
- 依赖多轮全局迭代才能稳定的复杂 flow-sensitive typing

## 3.3 MVP 禁止 scope-local 手动类型别名

MVP 版本中，frontend body phase 明确禁止手动声明在作用域中的类型别名。

这里的“作用域中的类型别名”包括：

- callable scope 手动发布的 `type-meta`
- block scope 手动发布的 `type-meta`
- 未来若 parser 暴露出的 local enum / const-based alias / preload alias 等 scope-local type source

MVP 消费合同固定为：

- body phase 不把这些来源当成普通 class-like `TYPE_META`
- 若在 strict body 语义位置命中这类来源，必须走 deferred / unsupported 路径
- analyzer 不得把它们静默降级成 ordinary value miss，也不得回退成宽松 `findType(...)`

---

## 4. 静态访问与构造器的单独处理原则

## 4.1 不走 `ScopePropertyResolver`

以下路径不走 `ScopePropertyResolver`：

- `EnumType.VALUE`
- builtin constant
- engine integer constant
- 其他直接以 class / enum / builtin type 为 base 的 static 常量读取

这些路径统一归入 frontend static binding + `load_static` 方向的专门分流。

## 4.2 不走 `ScopeMethodResolver`

以下路径不走普通 `ScopeMethodResolver`：

- `ClassName.new(...)`
- builtin ctor
- object ctor
- 其他构造器专用路由

原因是 shared `ScopeMethodResolver` 的合同已经明确把 constructor route 排除在外。

## 4.3 直接对类的链式访问单独处理

当链头已经解析成 `TYPE_META` 时，后续 step 不走 instance receiver 路由。

必须单独分流的场景包括：

- `ClassName.static_method(...)`
- `ClassName.new(...)`
- `EnumType.VALUE`
- `BuiltinType.CONSTANT`
- `EngineClass.CONSTANT`

这类场景的 shared input 应是：

- `ScopeTypeMeta`
- 当前 step 名字
- 当前 step 参数表达式与参数类型

而不是伪装成：

- object property lookup
- instance method lookup

## 4.4 实例对象调用静态方法单独诊断

当 receiver 是实例对象，但最终命中的 callable 是 static method 时：

- analyzer 仍应发布稳定的 `resolvedCalls()` 结果
- analyzer 必须额外发布专门 diagnostic
- 该路径不应被伪装成普通 instance method call
- 该路径也不应直接 hard-fail 成 `FAILED` call

这条规则用于覆盖：

- engine / builtin / gdcc class 上的 static method 被实例语法调用
- inner class / outer class 相关的 static method 被实例语法调用

MVP 目标行为与 Godot 当前 analyzer 方向保持同向：

- “实例调用静态方法”是可解析的 static route
- 但调用方式需要 warning / diagnostic 明确指出

---

## 5. 输出合同扩展

## 5.1 `FrontendResolvedMember`

当前 `FrontendResolvedMember(memberName, bindingKind)` 过于瘦弱，无法承载 chain phase 的稳定输出。

MVP 需要把它扩展成至少能表达以下事实：

- `memberName`
- `bindingKind`
- resolution status
- receiver kind
- owner kind
- receiver type
- result type
- declaration site / metadata payload
- blocked / deferred / dynamic / failed / unsupported 的原因

建议引入独立状态枚举，而不是把这些状态隐含塞进 `bindingKind`。

推荐最小字段集合：

- `String memberName`
- `FrontendBindingKind bindingKind`
- `FrontendMemberResolutionStatus status`
- `FrontendReceiverKind receiverKind`
- `@Nullable ScopeOwnerKind ownerKind`
- `@Nullable GdType receiverType`
- `@Nullable GdType resultType`
- `@Nullable Object declarationSite`
- `@Nullable String detailReason`

## 5.2 `FrontendResolvedCall`

当前 `FrontendResolvedCall(callableName)` 同样不足以支持 method / static call / constructor / dynamic fallback。

MVP 需要至少承载：

- `callableName`
- call kind
- resolution status
- receiver kind
- owner kind
- receiver type
- return type
- declaration site / chosen callable payload
- arg types snapshot
- blocked / deferred / dynamic / failed / unsupported 的原因

推荐最小字段集合：

- `String callableName`
- `FrontendCallResolutionKind callKind`
- `FrontendCallResolutionStatus status`
- `FrontendReceiverKind receiverKind`
- `@Nullable ScopeOwnerKind ownerKind`
- `@Nullable GdType receiverType`
- `@Nullable GdType returnType`
- `List<GdType> argumentTypes`
- `@Nullable Object declarationSite`
- `@Nullable String detailReason`

## 5.3 新增辅助枚举

为避免把多个维度压扁进现有 `FrontendBindingKind`，MVP 建议新增：

- `FrontendReceiverKind`
- `FrontendMemberResolutionStatus`
- `FrontendCallResolutionKind`
- `FrontendCallResolutionStatus`

这些枚举只表达 body phase 结果状态，不重写现有 `FrontendBindingKind` 的 symbol-category 语义。

推荐把 `FrontendMemberResolutionStatus` / `FrontendCallResolutionStatus` 的最小稳定成员集合固定为：

- `RESOLVED`
- `BLOCKED`
- `DEFERRED`
- `DYNAMIC`
- `FAILED`
- `UNSUPPORTED`

这里不再使用含混的 `UNRESOLVED` 作为 published 状态名。

## 5.4 状态语义与传播合同

## 5.4.1 设计原则

body phase 里的 step status 需要同时回答三件事：

- 当前 step 是否已经命中稳定 target
- 当前 step 为何不能继续走 exact static resolution
- 当前 step 的结果是否还能作为后续 suffix 的 receiver

因此这组状态不是 diagnostic severity，也不是 `FrontendBindingKind` 的替代品，而是 body phase reduction trace 的发布合同。

这里还需要特别区分：当前 `FrontendVisibleValueResolver` 的 `DEFERRED_UNSUPPORTED` 是“lookup 封口”用的合并状态；而 chain/body phase 需要把 `DEFERRED` 与 `UNSUPPORTED` 明确拆开，否则后续 `resolvedCalls()`、`expressionTypes()`、suffix 传播和测试矩阵都会失真。

## 5.4.2 `RESOLVED`

`RESOLVED` 表示：

- 当前 step 已命中稳定 target
- 当前 route 已确定，不需要 runtime-dynamic fallback
- 当前 step 有稳定结果类型，可作为下一层 exact receiver

补充约束：

- `RESOLVED` 可以伴随 diagnostic，例如“实例语法命中 static method”
- 只要 target 与结果类型稳定，这类 warning 不会阻断后续 suffix 解析

## 5.4.3 `BLOCKED`

`BLOCKED` 表示：

- 当前 step 对应的 binding / member / callable 实际存在
- 它就是当前语义位置的命中目标，而不是 miss
- 但它被当前 restriction / context policy 明确禁止使用

它与 `FAILED` 的根本区别是：`BLOCKED` 仍然是“found-here but illegal”，因此必须保留 shadowing / winner 语义，不能回退去找别的名字或别的 route。

典型场景：

- static context 下命中实例 member
- static context 下命中实例 method
- 其他 future restriction 已知存在目标但当前上下文禁止消费的路径

后缀传播合同：

- 当前 step 发布 `BLOCKED` 并发 diagnostic
- 默认不把它的结果继续当作 downstream exact receiver
- 后续依赖该结果的 suffix 进入“被上游 blocked step 阻断”的恢复路径，而不是各自重新做一次普通 miss

## 5.4.4 `DEFERRED`

`DEFERRED` 表示：

- 当前 route 本身属于计划支持面
- 但当前 analyzer 缺少冻结结果所需的前置事实
- 因而现在不能稳定发布 target / result type

它表达的是“时序上暂缓”，不是“语言上失败”，也不是“当前版本明确不支持”。

典型场景：

- call step 依赖 argument types，但 argument typing 尚未发布
- 某条局部 reduction 还缺少本阶段承诺会在后续里程碑接通的 side-table 事实

后缀传播合同：

- 当前 step 发布 `DEFERRED`
- 若后续 suffix 严格依赖当前 step 的结果类型，则后缀整体进入 deferred-by-upstream 路径
- 不得把 `DEFERRED` 降级成普通 `NOT_FOUND`
- 不得给后缀每一层重复发新的“找不到成员/方法”诊断

局部重试窗口与 suffix 停机规则固定为：

- `DEFERRED` step 允许在“当前 step 自己的 finalize 窗口”内做一次或少量有界重试，用来补完本 step 合法依赖的前置事实
- 这类重试只允许消费当前 step 自己的输入，例如：
  - receiver type
  - 当前 call step 的 argument expression types
  - 当前 subscript step 的 key/index type
- 不允许把“已经进入下一层 suffix 解析”本身当成反向触发条件，去回溯重跑上一层 `DEFERRED` step
- 不允许让 downstream step 倒逼 upstream step 进入开放式 fixpoint / 回溯求值

局部重试后的分流规则为：

- 若当前 step 在 finalize 窗口内恢复出稳定 target 与结果类型，则当前 step 改为 `RESOLVED`，并继续后续 exact suffix 解析
- 若当前 step 在 finalize 窗口内仍无法恢复，则它正式发布为 `DEFERRED`
- 一旦某个 step 已正式发布为 `DEFERRED`，后续 suffix 不再触发上游重新求值

当 `DEFERRED` 正式落定后，后续解析策略固定为：

- 当前 step 内部与 suffix receiver 无关的独立子表达式仍可继续分析，例如 argument expression / index expression
- 依赖该 step 结果类型的后续 suffix 停止 exact member / method / static-access 解析
- 后续 suffix 进入 deferred-by-upstream 路径，而不是继续尝试普通 lookup
- 诊断优先锚定到“第一个无法继续 exact 解析的 suffix root”
- 同一条 deferred suffix 当前只发 1 条恢复诊断
- 不再为 suffix 内部每个 step 额外发 `NOT_FOUND` / `FAILED` 类级联诊断

只有在外层消费者确实需要整条表达式的最终类型，且 deferred suffix 使该需求无法满足时，才允许由外层再补一条 expression-level 汇总诊断。

## 5.4.5 `DYNAMIC`

`DYNAMIC` 表示：

- 当前 analyzer 已走到支持域中的合法分流点
- exact static resolution 在这里被有意识地交给 runtime-dynamic 语义
- 这不是简单失败，而是语言/runtime 允许的动态退化

它与 `DEFERRED` 的区别是：

- `DEFERRED` 是“当前还不能决定”
- `DYNAMIC` 是“已经决定走动态路径”

它与 `FAILED` 的区别是：

- `DYNAMIC` 仍保留一条可执行的 runtime 路由
- `FAILED` 则是当前支持域里已经得出稳定负结论

典型场景：

- object receiver 缺 metadata，但允许 runtime object-dynamic fallback
- object receiver 的 method lookup 在 shared resolver 中落入 dynamic fallback
- `Variant` receiver 的 method call

后缀传播合同：

- 当前 step 发布 `DYNAMIC`
- 后续 suffix 不再承诺 exact member/method 解析
- 后续若继续分析，只能沿 dynamic / `Variant` 路由传播，而不是假装仍处于 exact static chain

## 5.4.6 `FAILED`

`FAILED` 表示：

- 当前 route 属于正式支持域
- analyzer 已拥有足够输入去完成本路由的静态判定
- 且判定结果是稳定负结论

它不是 feature boundary，也不是暂缓，而是“支持域内真实失败”。

典型场景：

- known hierarchy 上 property missing
- overload 选择后得到 `NO_APPLICABLE_OVERLOAD`
- static receiver 调 non-static method
- metadata malformed 并导致当前 route 无法继续

后缀传播合同：

- 当前 step 发布 `FAILED` 并发 semantic diagnostic
- 当前 step 不产出 downstream receiver
- 后续依赖该 receiver 的 suffix 统一进入 blocked-by-upstream-failure / skipped-suffix 路径

## 5.4.7 `UNSUPPORTED`

`UNSUPPORTED` 表示：

- analyzer 已识别出当前语法/来源/路由是什么
- 但它不在当前 body phase / 当前 MVP 的正式支持合同内
- 因此必须显式封口，而不是继续尝试解析

它与 `DEFERRED` 的根本区别是：

- `DEFERRED` 是当前实现顺序下的暂缓
- `UNSUPPORTED` 是当前版本边界下的 fail-closed 拒绝

典型场景：

- scope-local 手动类型别名命中 strict body 语义位置
- 尚未纳入 MVP 的 static access source
- 已识别但当前版本明确不支持的 subtree / route

后缀传播合同：

- `UNSUPPORTED` 应优先锚定到最近的 feature-boundary root
- 同一棵 unsupported subtree / suffix root 当前只发 1 条 boundary diagnostic
- 后续 suffix 直接 skip，不再逐层伪装成 `FAILED` 或 `NOT_FOUND`

## 5.4.8 五者边界总结

可用一句话归纳：

- `BLOCKED`：找到了，但当前上下文不允许用
- `DEFERRED`：计划支持，但当前前置事实还没齐
- `DYNAMIC`：已决定交给运行时动态语义
- `FAILED`：支持域内已经得到稳定负结论
- `UNSUPPORTED`：当前版本明确不支持这条路

对实现的硬约束是：

- 不得把 `BLOCKED`、`DEFERRED`、`DYNAMIC`、`FAILED`、`UNSUPPORTED` 重新压扁成同一个 `unresolved`
- 不得把 `DEFERRED` / `UNSUPPORTED` / `BLOCKED` 伪装成普通 `NOT_FOUND`
- 不得让 suffix 在上游已失效时继续制造级联 miss 诊断

---

## 6. 实施顺序

## 6.0 阶段总则

第六部分的里程碑不是宽泛愿景，而是串行 gate：

- 上一里程碑未通过验收前，不开始下一里程碑的正式发布接线
- 允许为后续里程碑预留空类型 / 空 helper，但不允许提前把未验收状态写进 published side table
- 每个里程碑都必须同时完成：
  - 数据模型或实现主体
  - 诊断 / 恢复合同接线
  - 对应测试锚点
  - 文档事实同步

里程碑之间的最小通过条件固定为：

- 代码能在当前模块编译通过
- 新旧 side-table 合同不存在 shape 破坏
- 负路径不会退化成普通 `NOT_FOUND` / `UNKNOWN`
- targeted tests 足以证明新阶段不会破坏前一阶段已冻结的行为

## 6.0.1 统一代码落点约束

默认代码落点建议如下：

- analyzer 发布入口：`src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
- side-table 结果模型：`src/main/java/dev/superice/gdcc/frontend/sema/**`
- shared resolver 复用接线：`src/main/java/dev/superice/gdcc/scope/resolver/**`
- 辅助 reduction / trace 模型：优先放在 `frontend.sema.analyzer` 的 package-private 内部实现，不直接外露为 shared API
- 测试：
  - side-table / framework 合同测试放 `src/test/java/dev/superice/gdcc/frontend/sema/**`
  - resolver 级行为测试放 `src/test/java/dev/superice/gdcc/scope/resolver/**`

## 6.0.2 阶段间禁止事项

在整个第六部分实施期间，以下事项禁止发生：

- 在 `FrontendExprTypeAnalyzer` 落地前，把 chain result type 反向塞进别的 phase 假装已经有完整 expression typing
- 在 `FrontendChainBindingAnalyzer` 落地前，让 `FrontendTopBindingAnalyzer` 继续扩张去吞掉尾部 step 解析
- 为了省实现量，把 `BLOCKED` / `DEFERRED` / `DYNAMIC` / `FAILED` / `UNSUPPORTED` 重新合并成单一 “unresolved”
- 在 analyzer 内部引入 whole-module fixpoint / 反复重跑 published phase
- 把 constructor route / static load route / static method route 混在同一条分支里靠 `if` 拼凑

## 6.0.3 推荐提交与验收节奏

建议按以下粒度推进，而不是一个超大提交一次性做完：

- A：只做输出模型与 analysis-data 兼容
- B：只做 reduction helper 与 trace 测试
- C：分 3 到 4 个子阶段推进 chain analyzer
- D：分 literal/bare/member-call/result-typing 几批推进 expr type
- E：最后接 `:=` 回填和扩展 typing

每完成一个子阶段，都至少需要一次 targeted test 绿灯后再继续。

## 6.1 里程碑 A：扩展输出模型

先扩展以下类型与测试：

- `FrontendResolvedMember`
- `FrontendResolvedCall`
- 相关 status / kind 枚举
- `FrontendAnalysisData` 对新模型的兼容性测试

本里程碑目标：

- 让后续 analyzer 有足够的 side-table shape
- 在数据模型层明确区分 resolved / blocked / deferred / dynamic / failed / unsupported
- 避免后续实现阶段因为 record 过瘦而反复返工

**实施细则**：

- 扩展 `FrontendResolvedMember`：
  - 字段至少覆盖本计划第 5.1 节的最小集合
  - 构造器或静态工厂必须显式校验状态与字段组合是否合法
  - `RESOLVED` / `BLOCKED` / `FAILED` / `DEFERRED` / `DYNAMIC` / `UNSUPPORTED` 的字段约束要有明确不变量
- 扩展 `FrontendResolvedCall`：
  - 字段至少覆盖本计划第 5.2 节的最小集合
  - `argumentTypes` 必须在 record 边界做 defensive copy
  - `callKind` 与 `status` 的组合关系要冻结，避免后续 analyzer 自由拼装出非法状态
- 新增并冻结以下枚举：
  - `FrontendReceiverKind`
  - `FrontendMemberResolutionStatus`
  - `FrontendCallResolutionKind`
  - `FrontendCallResolutionStatus`
- 审核 `FrontendAnalysisData`：
  - 确认新增 side-table 值类型不会破坏现有 builder / copy / snapshot 语义
  - 确认空表、缺项、已发布项三种状态仍可区分
- 若现有测试辅助直接 new 旧 record，需要同步升级测试支持代码，避免后续每个 analyzer 测试重复构造样板
- 文档同步：
  - 第 5 节中的字段与实际代码命名一致
  - 若实现期发现字段需微调，应在同一提交内同步修正文档

**验收标准**：

- `FrontendResolvedMember` / `FrontendResolvedCall` 已能无歧义表达 6 种状态
- 非法状态组合会在对象创建时直接失败，而不是留到 analyzer 运行期才暴露
- `FrontendAnalysisData` 兼容性测试通过，证明新增模型不会破坏现有 published side-table 容器
- 至少存在一组 focused tests 覆盖：
  - resolved shape
  - blocked shape
  - deferred/dynamic/failed/unsupported shape
- defensive copy / null-contract / invariant failure
- 此时仍未引入真正的 chain/body 语义逻辑；A 的完成标准是“模型冻结”，不是“功能跑通”

**当前状态（2026-03-17）**：

- [x] 已完成结果模型扩展：`FrontendResolvedMember` / `FrontendResolvedCall` 已从 placeholder record 升级为正式 published result model，稳定承载状态、receiver kind、owner/type payload、declaration metadata 与 detail reason。
- [x] 已完成辅助枚举落位：新增 `FrontendReceiverKind`、`FrontendMemberResolutionStatus`、`FrontendCallResolutionKind`、`FrontendCallResolutionStatus`，用于固定 body phase 的最小状态空间。
- [x] 已完成模型不变量冻结：resolved/blocked/dynamic 等组合现在会在对象创建时校验关键字段约束，避免后续 analyzer 自由拼装非法状态。
- [x] 已完成 `FrontendAnalysisData` 兼容性测试扩展：新增 `resolvedMembers()` / `resolvedCalls()` 的 stable-reference + stale-entry-clearing 测试，继续沿用现有 side-table publish 合同。
- [x] 已完成 `FrontendResolvedMember` / `FrontendResolvedCall` focused tests：覆盖 happy path、非法状态组合、防御性复制与 detail-reason/null-contract。
- [x] 已完成里程碑 A 的 targeted test 验证与文档收口：focused model tests 与 framework / top-binding / variable analyzer 回归测试均已通过。

## 6.2 里程碑 B：抽出局部链式 reduction helper

新增内部 helper，用于单条链式表达式的左到右 reduction。

**当前状态**：

- [x] B0 输入输出冻结：已新增 `frontend.sema.resolver.FrontendChainReductionHelper`，先把 request/result、receiver state、step trace、upstream provenance、局部 note sink 等局部 contract 固定下来，继续保持“不写 `FrontendAnalysisData`、不直接承担 phase 发布”的边界。
- [x] B1 shared resolver 接线：已接上 instance property / signal、instance/static method，并把 constructor/static-load 先冻结成 frontend-only route 占位；instance-syntax 命中 static method 时同步产出 local note。
- [x] B2 finalize-window 规则：call-step 参数类型现在支持一次有界 finalize-window 重试；若仍无法恢复，则当前 step 正式发布 `DEFERRED`，后续 suffix 只走 deferred-by-upstream trace，不再回溯重跑上游。
- [x] B3 helper 合同测试：已新增 `FrontendChainReductionHelperTest`，纯 helper 级覆盖 resolved / blocked / deferred 恢复与停机 / dynamic / failed / unsupported / static route / repeatability，不依赖完整 `FrontendSemanticAnalyzer` wiring。

该 helper 的职责：

- 消费链头 binding / type-meta 事实
- 对每个 step 做 receiver kind 分流
- 在局部流程里交替推进 binding 与结果类型
- 返回 step-by-step reduction trace

该 helper 不是新的 published side table，也不是新的 whole-module public phase。

**实施细则**：

- 先冻结 helper 的输入输出，不急于接 analyzer：
  - 输入至少应包含：chain head 事实、当前 phase 可见的 side-table、diagnostic reporter 接口或回调、resolver 依赖
  - 输出至少应包含：逐 step trace、最终 receiver/result 状态、建议发布的 member/call 结果、建议恢复根
- helper 内部建议显式建模 step trace，例如：
  - step kind
  - incoming receiver kind/type
  - chosen route
  - published status
  - outgoing receiver kind/type
  - upstream-blocked / upstream-deferred provenance
- 冻结 helper 的职责边界：
  - 可以做当前 step finalize 前的局部有界重试
  - 不负责 whole-module 级重跑
  - 不直接写 `FrontendAnalysisData`
  - 不在内部偷偷发布全局 side-table
- 接线 shared resolver 时要显式分流：
  - instance property -> `ScopePropertyResolver`
  - instance signal -> `ScopeSignalResolver`
  - instance/static method -> `ScopeMethodResolver`
  - constructor / static load -> frontend 专用 route
- helper 必须原生支持第 5.4 节定义的 6 状态，不允许对外只返回一个模糊的 “success/failure”
- 对 `DEFERRED` 实现 finalize-window 规则：
  - 允许在当前 step 内补完自身依赖后重试
  - 一旦正式落定为 `DEFERRED`，trace 中后续 suffix 必须显式标记为 deferred-by-upstream，而不是再尝试 exact 解析

**验收标准**：

- 存在独立 helper 测试，不依赖完整 `FrontendSemanticAnalyzer` 运行即可验证单条链 reduction 行为
- helper 能稳定输出 step-by-step trace，覆盖：
  - resolved 连续链
  - blocked hit
  - deferred suffix
  - dynamic suffix
  - failed suffix
  - unsupported root sealing
- helper 不直接写 side table，不直接承担 whole-module phase 发布职责
- 对同一输入重复运行 helper，trace 结果保持稳定，不因内部缓存或副作用漂移
- helper 已足以支撑后续 analyzer 实现，不需要再回到 A 去重塑结果模型

**当前验收记录**：

- 新增 `FrontendChainReductionHelper` 作为 frontend 专用 reduction adapter，位置固定在 `frontend.sema.resolver`，与 shared `scope.resolver` 和后续 analyzer phase 保持清晰分层。
- 新增 `FrontendChainReductionHelperTest`，使用手工 AST + fixture registry 做纯局部 reduction 测试，不依赖完整 `FrontendSemanticAnalyzer`。
- 已跑通 targeted tests：
  - `FrontendChainReductionHelperTest`
  - `FrontendResolvedMemberTest`
  - `FrontendResolvedCallTest`
  - `FrontendAnalysisDataTest`
  - `ScopePropertyResolverTest`
  - `ScopeSignalResolverTest`
  - `ScopeMethodResolverTest`

## 6.3 里程碑 C：`FrontendChainBindingAnalyzer` MVP

在已存在 `symbolBindings()` 的基础上新增 `FrontendChainBindingAnalyzer`。

它的正式输出是：

- `resolvedMembers()`
- `resolvedCalls()`
- 相关 chain/static-access diagnostics

MVP 支持顺序建议为：

1. instance property
2. instance signal
3. `TYPE_META` receiver 的 static method call
4. instance receiver 命中 static method 时的 resolved call + diagnostic
5. constructor route
6. static constant / enum value / builtin constant / engine integer constant
7. receiver 已知且参数类型已知的方法调用

其中：

- step 参数表达式继续递归进入普通表达式分析入口
- 当参数类型尚未稳定时，call step 优先发布 `DEFERRED`，而不是使用含混的 `unresolved`
- 仅当 shared resolver 已明确给出 runtime-dynamic fallback 时，才发布 `DYNAMIC`
- analyzer 不得把 constructor route 伪装成普通 static method
- analyzer 必须区分：
  - `TYPE_META` receiver 调 non-static method：error / failed call
  - instance receiver 调 static method：resolved static call + diagnostic

**实施细则**：

- C0：phase 接线与发布骨架
  - 在 `FrontendSemanticAnalyzer` 中插入 `FrontendChainBindingAnalyzer` 的发布位置
  - 明确输入依赖：`symbolBindings()`、`scopesByAst()`、class/type metadata、diagnostic manager
  - 明确输出 side table：`resolvedMembers()`、`resolvedCalls()`
  - 先保证未覆盖节点保持空表 / 无发布，而不是伪造默认结果
- C1：稳定 member 路径
  - 先接 instance property
  - 再接 instance signal
  - 确保 `BLOCKED` hit 会保留 winner 并发 diagnostic，不会回退其他同名 route
  - 对 metadata unknown / inheritance malformed 与 property missing 要区分成 `DYNAMIC` / `FAILED`
- C2：type-meta 与 static 路径
  - 接 `TYPE_META` receiver 的 static method call
  - 接 constructor route
  - 接 static constant / enum value / builtin constant / engine integer constant
  - 明确 static method / constructor / static load 三条路完全分流
  - 接 instance receiver 命中 static method 的 resolved static call + diagnostic
- C3：普通 call 路径
  - 在 argument types 已知时接 receiver-known method call
  - argument types 未知时优先发布 `DEFERRED`
  - 仅在 shared resolver 明确返回 dynamic fallback 时发布 `DYNAMIC`
  - 对 overload ambiguous / no applicable overload / static-nonstatic mismatch 分别走对应状态
- C4：恢复与后缀传播
  - 把第 5.4 节的 suffix 传播合同真正接入 analyzer
  - `DEFERRED` suffix 只发 1 条恢复诊断
  - `UNSUPPORTED` subtree / route 只在最近恢复根发 boundary diagnostic
  - 坏链式表达式不得污染同模块其他表达式的 member/call 结果

**验收标准**：

- C0 完成后：
  - chain analyzer 已加入 frontend pipeline
  - 未覆盖节点不会产出伪结果
  - 现有 top binding 测试不因 phase 新增而回归
- C1 完成后：
  - property / signal 路径能稳定发布 `resolvedMembers()`
  - blocked property / signal 不回退到别的名字
  - metadata unknown 与 real missing 已被测试区分
- C2 完成后：
  - `TYPE_META` static method / constructor / static load 三类路径均有独立测试
  - instance 调 static method 会发布 resolved call + diagnostic
  - `TYPE_META` 调 non-static method 会稳定进入 `FAILED`
- C3 完成后：
  - receiver-known method call 能稳定发布 `resolvedCalls()`
  - argument-type-insufficient 场景进入 `DEFERRED`，不会被压成 `FAILED`
  - dynamic fallback、ambiguous、no-applicable-overload 均有独立测试锚点
- C4 完成后：
  - deferred/dynamic/failed/unsupported suffix 均不会制造级联 miss 诊断
  - 同一条坏链表达式的恢复诊断数量可预测、可测试
  - `resolvedMembers()` / `resolvedCalls()` 已达到可供 `FrontendExprTypeAnalyzer` 消费的稳定程度

**推荐 targeted tests**：

- `FrontendSemanticAnalyzerFrameworkTest`
- 新增 `FrontendChainBindingAnalyzerTest`
- 与 shared resolver 相关的 targeted parity tests：
  - `ScopePropertyResolverTest`
  - `ScopeSignalResolverTest`
  - `ScopeMethodResolverTest`

## 6.4 里程碑 D：`FrontendExprTypeAnalyzer` MVP

`FrontendExprTypeAnalyzer` 在 chain/member/call 结果已经可见后发布 `expressionTypes()`。

MVP 优先覆盖：

- literal
- `self`
- bare identifier
- top-level `TYPE_META`
- property read
- signal value
- static constant load
- constructor result
- receiver 已知时的 method call result
- `:=` 的 RHS-driven typing 接线

这里需要明确：

- `FrontendExprTypeAnalyzer` 不是“从零猜出所有链式结果”的第一前提
- 它消费 `resolvedMembers()` / `resolvedCalls()`，并在必要时复用局部 chain reduction helper

**实施细则**：

- D0：发布骨架
  - 新增 `FrontendExprTypeAnalyzer`
  - 明确输入依赖：`symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、必要的 class/type metadata
  - 明确 `expressionTypes()` 的缺项语义：未分析、无法发布、发布为 `Variant` 三者不能混淆
- D1：无争议原子表达式
  - literal
  - `self`
  - bare identifier
  - top-level `TYPE_META`
  - 这些节点先独立落地，保证 analyzer 本身的遍历与发布机制稳定
- D2：读取型链式结果
  - property read
  - signal value
  - static constant load
  - 对 `resolvedMembers()` 的 `RESOLVED` / `BLOCKED` / `FAILED` / `DYNAMIC` / `DEFERRED` / `UNSUPPORTED` 分别定义类型发布策略
- D3：调用型链式结果
  - constructor result
  - receiver-known method call result
  - static method call result
  - instance 调 static method但已成功 resolve 的场景，类型仍按 static callable return type 发布
- D4：恢复与 `Variant` 降级
  - `DYNAMIC` 路由需要稳定降级到 runtime-dynamic / `Variant`
  - `DEFERRED` / `UNSUPPORTED` 路由不应假装已经有精确类型
  - 若某表达式因恢复策略只允许发布宽类型，需要固定规则并补测试，而不是 analyzer 自由决定
- D5：为 `:=` 回填准备接线点
  - 先把“RHS 可被取型”这一事实发布稳定
  - 但不在 D 阶段直接写变量声明最终类型，变量回填仍属于 E

**验收标准**：

- D0 完成后：
  - `expressionTypes()` 已发布且空表/缺项语义明确
  - analyzer 能接入 pipeline 而不污染前面 side table
- D1 完成后：
  - literal / `self` / bare identifier / top-level `TYPE_META` 类型发布稳定
  - 这些路径不依赖 chain analyzer 的复杂恢复分支
- D2 完成后：
  - property / signal / static constant 的结果类型与 `resolvedMembers()` 状态一致
  - 不会把 `BLOCKED` / `FAILED` / `UNSUPPORTED` 错发成精确成功类型
- D3 完成后：
  - constructor / method / static method call 的 return type 可稳定发布
  - instance 调 static method 的 warning 不影响其已知返回类型发布
- D4 完成后：
  - `DYNAMIC` 路由的表达式类型降级规则固定并有测试
  - `DEFERRED` suffix 不会伪装成精确类型成功
- D5 完成后：
  - `:=` 回填所需 RHS typing 事实已经可消费
  - 但变量声明最终类型仍未在本阶段偷跑接线

**推荐 targeted tests**：

- 新增 `FrontendExprTypeAnalyzerTest`
- `FrontendAnalysisDataTest`
- `FrontendSemanticAnalyzerFrameworkTest`

## 6.5 里程碑 E：变量类型回填与后续扩张

在 MVP 表达式类型已发布后，再处理：

- `:=` 变量声明的真实类型落地
- 参数默认值的后续接线
- 更多 operator / container / ternary / assignment typing
- 更复杂的 dynamic / flow-sensitive 恢复

这一里程碑之后，再评估是否需要新的 body sub-phase，而不是在 MVP 阶段预设全局 fixpoint。

**实施细则**：

- E1：`:=` 变量声明回填
  - 只消费 D 阶段已经稳定发布的 RHS 类型
  - 对 `DEFERRED` / `UNSUPPORTED` / `DYNAMIC` RHS 明确回填策略
  - 禁止变量回填逻辑反向修改 `expressionTypes()` 的既有结论
- E2：默认值与更多表达式 typing
  - 参数默认值接线必须在不破坏既有 deferred-boundary 合同的前提下逐步转正
  - operator / container / ternary / assignment typing 分批推进，不一次性并入
- E3：更复杂恢复策略
  - 仅在前面 side-table 已稳定后，再评估是否需要更细的 flow-sensitive typing
  - 若确实需要新增 body sub-phase，必须重新立项，不在本计划中隐式扩张

**验收标准**：

- `:=` 已能在稳定 RHS typing 上完成真实类型落地
- `DEFERRED` / `DYNAMIC` / `UNSUPPORTED` RHS 的变量回填策略固定并有测试覆盖
- 参数默认值等此前封口域若开始转正，必须有独立诊断与恢复合同，不得直接拆掉旧 boundary 而无替代
- 新增 operator/container/ternary/assignment typing 不会破坏 D 阶段已冻结的表达式类型发布规则
- 完成 E 后，才允许讨论是否需要新的 body sub-phase 或更强的全局收敛机制

## 6.6 阶段总体验收出口

第六部分整体完成时，应满足以下条件：

- `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、`expressionTypes()` 四张表的职责边界清晰，互不篡位
- constructor route、static method route、static load route 三条语义路径已结构化分流，不再依赖临时分支拼装
- `BLOCKED` / `DEFERRED` / `DYNAMIC` / `FAILED` / `UNSUPPORTED` 的 published 语义、后缀传播和诊断数量都可测试、可预测
- `:=` 已能消费稳定 RHS typing 完成变量真实类型落地
- 整体设计仍保持“整体分层、局部交替”，未退化成 whole-module fixpoint

建议在整体出口前至少跑通以下 targeted tests 组合：

- `FrontendAnalysisDataTest`
- `FrontendSemanticAnalyzerFrameworkTest`
- `ScopePropertyResolverTest`
- `ScopeSignalResolverTest`
- `ScopeMethodResolverTest`
- 新增的 `FrontendChainBindingAnalyzerTest`
- 新增的 `FrontendExprTypeAnalyzerTest`

---

## 7. 已知困难

静态方法调用在 MVP 中需要被显式纳入计划，但它的实现复杂度高于普通 instance property / signal，原因至少包括：

- 当前 `ScopeMethodResolver.resolveInstanceMethod(...)` 会在 object receiver 路径上收集 static 与 non-static 候选，并采用“实例方法优先，若无实例候选则允许 static 候选胜出”的排序规则；frontend 需要在消费 shared resolver 结果时额外识别“这是 static route 被实例语法调用”，再单独发诊断。
- 当前 `ScopeMethodResolver.resolveStaticMethod(...)` 只适用于真实 `ScopeTypeMeta` receiver；它不会负责 constructor route，也不会处理 `EnumType.VALUE` / builtin constant 读取。因此 frontend 必须先把 static method、constructor、static load 三条路由拆开，再决定调用哪个 shared resolver。
- static/non-static 的最终判定依赖参数类型是否已经稳定。若 argument type 尚未发布，chain phase 只能保守发布 `DEFERRED`，而不能过早决定“实例调用静态方法”或“静态 receiver 调 non-static 方法”。
- 诊断策略需要区分至少两类不同严重度：
  - `TYPE_META` receiver 调 non-static method：应视为非法调用
  - instance receiver 调 static method：应视为可解析但需要提示的调用方式问题
- 若同名 overload set 同时存在 static 与 non-static 候选，frontend 还需要确保 shared resolver 的筛选结果、最终 emitted diagnostic、以及 `resolvedCalls()` 的 published route 三者一致，避免出现“诊断说是 static call，但 side table 却记录成 instance call”的状态失真。

---

## 8. 诊断与恢复策略

body phase 继续遵守 frontend 统一恢复合同：

- 对普通源码错误优先发 diagnostic
- 对不稳定 subtree 采用 skip / deferred / unsupported sealing
- 不因单个坏链式表达式中断整个 module

链式语义里需要优先区分的恢复面包括：

- receiver metadata unknown
- method overload ambiguous
- method argument type insufficient
- static access source unsupported
- constructor route unsupported
- scope-local type alias hit

这些状态必须保留为结构化结果，再由 analyzer 决定：

- 发布 `BLOCKED` 并保留 winner，再由上层发 restriction diagnostic
- 发布 `DEFERRED`，等待未来里程碑或当前表达式中的前置事实接通
- 发布 `DYNAMIC`，并让表达式类型降级到 runtime-dynamic / `Variant` 路由
- 发布 `FAILED`，并阻断后续 exact suffix
- 发布 `UNSUPPORTED`，并按 subtree root 合同一次性封口

其中 `DEFERRED` 需要额外遵守：

- 只允许在当前 step finalize 前做局部有界重试
- 不允许由下一层 suffix 反向触发上一层重新解析
- 正式落定后，优先对 deferred suffix root 发 1 条恢复诊断，而不是对整条 suffix 逐层报错

不允许把这些情况统一伪装成普通 `NOT_FOUND`，也不允许重新压回笼统的 `unresolved`。

---

## 9. 测试锚点

每个里程碑至少补齐以下测试：

- happy path：instance property / signal / `TYPE_META` receiver static method / constructor / static constant
- negative path：blocked / deferred / dynamic / failed / unsupported / metadata missing / ambiguous
- deferred recovery path：
  - 当前 step 在 finalize 窗口内局部重试后成功恢复，suffix 继续 exact 解析
  - 当前 step 局部重试后仍为 `DEFERRED`，suffix 停止 exact 解析并只发 1 条恢复诊断
- static-call path：
  - `TYPE_META` receiver 调 static method
  - `TYPE_META` receiver 调 non-static method时报错
  - instance receiver 调 static method时发布 resolved call 并发诊断
- 合同测试：`resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` 不互相污染
- 恢复测试：坏 chain subtree 被跳过，但同一 module 中其他表达式仍继续工作
- MVP 约束测试：scope-local type alias 命中时进入 deferred / unsupported，而不是被当成普通 `TYPE_META`

---

## 10. 当前执行结论

当前推荐的实施路线总结如下：

1. 顶层保持 phase 化，不做 whole-module 多轮迭代。
2. 局部在单条链内交替推进 binding 与类型。
3. 先扩充 `FrontendResolvedMember` / `FrontendResolvedCall` 模型。
4. 再做 chain reduction helper 与 `FrontendChainBindingAnalyzer` MVP。
5. 最后让 `FrontendExprTypeAnalyzer` 在稳定的 member/call 结果之上发布 `expressionTypes()` 并接通 `:=`。

这条路线既与当前仓库的 side-table / resolver 合同一致，也与 Godot 当前“整体分阶段、局部 reduction” 的组织方式保持同向。
