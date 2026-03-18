# Frontend Chain Binding / ExprType 执行计划

> 本文档记录 `FrontendChainBindingAnalyzer` 与 `FrontendExprTypeAnalyzer` 的执行计划、阶段边界、MVP 支持面与数据模型演进约束。本文档是实施计划，不是当前实现事实源；已落地事实仍以对应 implementation 文档为准。

## 文档状态

- 性质：执行计划
- 更新时间：2026-03-19
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

- [x] B0 输入输出冻结：已新增 `frontend.sema.analyzer.support.FrontendChainReductionHelper`，先把 request/result、receiver state、step trace、upstream provenance、局部 note sink 等局部 contract 固定下来，继续保持“不写 `FrontendAnalysisData`、不直接承担 phase 发布”的边界。
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

- 新增 `FrontendChainReductionHelper` 作为 frontend 专用 reduction adapter，现位于 `frontend.sema.analyzer.support`，与 shared `scope.resolver` 和 analyzer-side support glue 保持清晰分层。
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
  - 状态：已完成（2026-03-17）
  - 在 `FrontendSemanticAnalyzer` 中插入 `FrontendChainBindingAnalyzer` 的发布位置
  - 明确输入依赖：`symbolBindings()`、`scopesByAst()`、class/type metadata、diagnostic manager
  - 明确输出 side table：`resolvedMembers()`、`resolvedCalls()`
  - 先保证未覆盖节点保持空表 / 无发布，而不是伪造默认结果
- C1：稳定 member 路径
  - 状态：已完成（2026-03-17）
  - 先接 instance property
  - 再接 instance signal
  - 确保 `BLOCKED` hit 会保留 winner 并发 diagnostic，不会回退其他同名 route
  - 对 metadata unknown / inheritance malformed 与 property missing 要区分成 `DYNAMIC` / `FAILED`
- C2：type-meta 与 static 路径
  - 状态：已完成（2026-03-17）
  - 接 `TYPE_META` receiver 的 static method call
  - 接 constructor route
  - 接 static constant / enum value / builtin constant / engine integer constant
  - 明确 static method / constructor / static load 三条路完全分流
  - 接 instance receiver 命中 static method 的 resolved static call + diagnostic
- C3：普通 call 路径
  - 状态：已完成（2026-03-17）
  - 在 argument types 已知时接 receiver-known method call
  - argument types 未知时优先发布 `DEFERRED`
  - 仅在 shared resolver 明确返回 dynamic fallback 时发布 `DYNAMIC`
  - 对 overload ambiguous / no applicable overload / static-nonstatic mismatch 分别走对应状态
- C4：恢复与后缀传播
  - 状态：已完成（2026-03-17）
  - 把第 5.4 节的 suffix 传播合同真正接入 analyzer
  - `DEFERRED` suffix 只发 1 条恢复诊断
  - `UNSUPPORTED` subtree / route 只在最近恢复根发 boundary diagnostic
  - 坏链式表达式不得污染同模块其他表达式的 member/call 结果

**当前里程碑 C 产出（2026-03-17）**：

- `FrontendSemanticAnalyzer` 已接入 `FrontendChainBindingAnalyzer`，并在 top-binding 后刷新 `resolvedMembers()` / `resolvedCalls()` 与 diagnostics 边界。
- `FrontendChainReductionHelper` 已接通 instance property/signal、static method、constructor、static load、dynamic fallback、deferred finalize-window retry 与 suffix 传播。
- `FrontendChainBindingAnalyzer` 已发布：
  - blocked/failed member -> `sema.member_resolution`
  - blocked/failed call 与 instance-syntax-static-method note -> `sema.call_resolution`
  - deferred subtree / deferred chain root -> `sema.deferred_chain_resolution`
  - unsupported static route boundary -> `sema.unsupported_chain_route`
- 测试锚点已覆盖：
  - helper 级别的 resolved/deferred/dynamic/failed/unsupported 路径
  - analyzer 级别的 static method、constructor、static load、deferred suffix、failed static route、unsupported gdcc static load
  - semantic framework 的 chain phase 接线、stable side-table 引用与 diagnostics boundary refresh

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
- top-level `TYPE_META` static-route head
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
  - 不新增新的表达式 side table；直接把 `expressionTypes()` 的元素类型从裸 `GdType` 升级为可承载状态、published type 与 detail reason 的富语义对象
  - `expressionTypes()` 的富语义对象必须保留 `RESOLVED` / `BLOCKED` / `DEFERRED` / `DYNAMIC` / `FAILED` / `UNSUPPORTED` 区分，禁止再把 provenance 压回单个 `GdType`
- D1：无争议原子表达式
  - literal
  - `self`
  - bare identifier
  - top-level `TYPE_META` 的 route-head 参与规则
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

**当前状态（2026-03-17）**：

- [x] D0.a 已冻结本轮修复边界：`expressionTypes()` 不新增新表，改为发布富语义 payload，用于同时承载状态、published type 与 detail reason。
- [x] D0.b 已完成数据模型升级：新增 `FrontendExpressionType` / `FrontendExpressionTypeStatus`，并将 `FrontendAnalysisData.expressionTypes()` 升级为富语义 payload side table；兼容性测试同时锁定 stable-reference 与 stale-entry-clearing 合同。
- [x] D0.c 已完成局部依赖协议修复：`FrontendChainReductionHelper.ExpressionTypeResult`、argument resolution 与 `FrontendChainBindingAnalyzer` 的 local typing glue 现在原生接受 `BLOCKED` / `DYNAMIC`，不再把这两类 provenance 压成 `FAILED`。
- [x] D2/D3 已完成 published type 规则接线：`FrontendExprTypeAnalyzer` 已能按 `resolvedMembers()` / `resolvedCalls()` 的 status 逐类发布 property/signal/static load/constructor/method/static method 的 expression typing，而不是只在成功路径写精确类型。
- [x] D4 已完成动态/阻断恢复合同：`DYNAMIC` 稳定降级发布为 `Variant`，`BLOCKED` 继续保留结构化依赖并向后缀传播，只有 `FAILED` 才表示真实失败；上游阻断传播到后缀时不再伪造当前层精确类型。
- [x] D0-D4 已完成 shared resolver 兼容层补齐：`ScopeMethodResolver` 现同时接受“frontend skeleton 无 synthetic self”与“lowered/shared LIR 带 synthetic self”两种 GDCC 实例方法元数据布局；若命中错误类型的 synthetic `self`，仍稳定保留 `MALFORMED_METADATA`。
- [x] D0-D4 已完成合同测试：focused tests 已覆盖 nested chain、argument dependency、dynamic degradation、blocked propagation、exact `Variant` 区分，以及 GDCC instance method metadata 双布局兼容与 malformed-self negative path。

**推荐 targeted tests**：

- 新增 `FrontendExprTypeAnalyzerTest`
- `FrontendAnalysisDataTest`
- `FrontendSemanticAnalyzerFrameworkTest`

## 6.4.1 D/E 间重构收敛：抽出链头 receiver 与原子局部类型 support

在 D 阶段功能已进入可发布状态后、E 阶段继续扩张表达式覆盖面前，建议先做一轮小范围结构收敛，优先消除
`FrontendChainBindingAnalyzer` 与 `FrontendExprTypeAnalyzer` 中最容易再次造成语义漂移的重复 support 逻辑。

第一步优先抽出“链头 receiver / 原子局部类型 support”，目标不是改变 published 语义，而是把两边几乎逐行重复的
base expression 支撑逻辑收口到同一个 frontend helper。

建议新增的 support helper 至少统一以下职责：

- `resolveHeadReceiver(...)`
- `resolveIdentifierHeadReceiver(...)`
- `resolveTypeMetaReceiver(...)`
- `resolveValueReceiver(...)`
- `resolveSelfReceiver(...)`
- `resolveLiteralType(...)`
- `findEnclosingClassScope(...)`

建议形态：

- helper 位置保持在 frontend analyzer/support 层，而不是继续塞进 `FrontendChainReductionHelper`
- helper 显式接收：
  - `FrontendAnalysisData`
  - `scopesByAst()`
  - `ResolveRestriction`
  - `staticContext`
- 对 nested attribute base 的递归解析与 fallback expression receiver 解析，继续通过回调注入，而不是让 helper 直接依赖某个具体 analyzer

原因与边界：

- 这些逻辑本质上是 phase-agnostic 的 base lookup support，不是 step-by-step chain reduction 核心
- 它们不应直接写 side table，也不应直接发 diagnostic
- 抽出后可以减少 `BLOCKED` / `TYPE_META` / `self` / literal head 在两个 analyzer 之间出现语义漂移的概率

验收标准：

- `FrontendChainBindingAnalyzer` 与 `FrontendExprTypeAnalyzer` 中上述 support 方法被删除或收缩为薄转发
- `self` 在 static context 下的 `BLOCKED` 语义保持不变
- `TYPE_META`、普通 value、literal 作为 chain head 的行为保持不变
- 现有 focused tests 无回归

当前实施状态：

- [x] 6.4.1.a 已新增 `FrontendChainHeadReceiverSupport`，统一承载 chain-head value / `TYPE_META` / `self` / literal receiver 支撑逻辑，并通过回调注入 nested attribute base 与 fallback expression receiver 解析。
- [x] 6.4.1.b `FrontendChainBindingAnalyzer` 已删除本地重复的 head receiver / literal / enclosing class support 实现，改为收口到共享 helper。
- [x] 6.4.1.c `FrontendExprTypeAnalyzer` 已删除本地重复的 head receiver / literal / enclosing class support 实现，改为收口到共享 helper。
- [x] 6.4.1.d helper 单元测试、analyzer focused tests 与格式化校验已完成；新增 `FrontendChainHeadReceiverSupportTest`，并已跑通 `FrontendChainHeadReceiverSupportTest`、`FrontendChainReductionHelperTest`、`FrontendChainBindingAnalyzerTest`、`FrontendExprTypeAnalyzerTest`、`FrontendSemanticAnalyzerFrameworkTest`。

## 6.4.2 D/E 间重构收敛：抽出状态对象转换桥

第二步建议抽出“状态对象转换桥”，把 `ReceiverState`、`ExpressionTypeResult`、`FrontendExpressionType`、
`FrontendResolvedMember`、`FrontendResolvedCall` 之间的转换协议固定到一处。

当前最危险的重复不在 shared resolver，也不在 chain reduction 内核，而在 analyzer 两侧的 glue code：

- `ReceiverState -> ExpressionTypeResult`
- `ExpressionTypeResult -> ReceiverState`
- `ReductionResult -> FrontendExpressionType`
- `FrontendResolvedMember -> FrontendExpressionType`
- `FrontendResolvedCall -> FrontendExpressionType`

建议新增一个纯转换 helper，并冻结以下规则：

- `DYNAMIC` 一律发布为 runtime-dynamic / `Variant`
- `BLOCKED` 保留 winner type；若没有可发布 winner，则允许 `publishedType == null`
- `FAILED` 才表示真实失败，不得再把 `BLOCKED` / `DYNAMIC` 压成 `FAILED`
- `UPSTREAM_BLOCKED` 传播到 suffix 时，不得伪造当前层精确类型

边界要求：

- 该 helper 只负责对象转换与状态桥接
- 不做 AST 遍历
- 不做 side-table 发布
- 不发 diagnostic

验收标准：

- 两个 analyzer 不再各自维护整套状态转换方法
- `BLOCKED` / `DYNAMIC` / `UPSTREAM_BLOCKED` 的 published 行为由单一 helper 冻结
- focused tests 继续覆盖：
  - dynamic degradation
  - blocked propagation
  - exact `Variant` 与 dynamic `Variant` 区分

当前实施状态：

- [x] 6.4.2.a 已新增 `FrontendChainStatusBridge`，统一冻结 `ReceiverState`、`ExpressionTypeResult`、`FrontendExpressionType`、`FrontendResolvedMember`、`FrontendResolvedCall` 之间的状态转换协议。
- [x] 6.4.2.b `FrontendChainBindingAnalyzer` 与 `FrontendExprTypeAnalyzer` 已删除本地重复的状态桥接方法，统一改为消费 `FrontendChainStatusBridge`。
- [x] 6.4.2.c `FrontendChainReductionHelper.ExpressionTypeResult.fromPublished(...)` 已收口到共享 bridge，避免 published-expression 转换规则在 helper 与 analyzer 两侧漂移。
- [x] 6.4.2.d 已新增 `FrontendChainStatusBridgeTest`，并与 `FrontendChainHeadReceiverSupportTest`、`FrontendChainReductionHelperTest`、`FrontendChainBindingAnalyzerTest`、`FrontendExprTypeAnalyzerTest`、`FrontendSemanticAnalyzerFrameworkTest` 一起跑通，覆盖 dynamic degradation、blocked propagation 与 exact/dynamic `Variant` 区分。

## 6.4.3 D/E 间重构收敛：抽出带缓存的链 reduction 门面

第三步建议抽出一个 analyzer-side 的“带缓存的 chain reduction 门面”，统一两边各自维护的
`reducedChains` cache 与 `reduceAttributeExpression(...)` 样板。

这个门面的职责应仅限于：

- 维护 `AttributeExpression -> Optional<ReductionResult>` 的本地缓存
- 统一 cache hit / miss 逻辑
- 统一：
  - head receiver 计算
  - `FrontendChainReductionHelper.reduce(...)` 调用
- 通过回调注入 expression dependency resolver

这个门面**不应**负责：

- 发布 `resolvedMembers()` / `resolvedCalls()`
- 发布 `expressionTypes()`
- 发 chain boundary diagnostic
- 在内部偷偷持有 whole-module 级状态

建议这样分层：

- `FrontendChainReductionHelper` 继续只承担单条链 reduction 内核
- 新门面承担 analyzer-side orchestration 与 cache
- `FrontendChainBindingAnalyzer` 保留 `publishReduction(...)` 与 diagnostics 边界决策
- `FrontendExprTypeAnalyzer` 保留：
  - 优先消费 `resolvedMembers()` / `resolvedCalls()` 的 fast path
  - reduction fallback 后的 expression type 发布

验收标准：

- 两个 analyzer 不再各自维护重复的 `reducedChains` 与 reduction 门面样板
- nested attribute recursion 行为保持稳定
- chain analyzer 仍只发布一次 diagnostics，不出现双重发布
- expr analyzer 仍不会越权发布 member/call side table

当前实施状态：

- [x] 6.4.3.a 已新增 `FrontendChainReductionFacade`，统一承载 analyzer-side 的 attribute reduction cache、chain-head receiver 计算以及 `FrontendChainReductionHelper.reduce(...)` 编排。
- [x] 6.4.3.b `FrontendChainBindingAnalyzer` 与 `FrontendExprTypeAnalyzer` 已删除各自本地的 `reducedChains` 与 reduction 样板，改为共享 facade；其中 chain analyzer 仅在 `computedNow()` 时发布 reduction 结果，保持 diagnostics 单次发布边界。
- [x] 6.4.3.c facade 已通过 `FrontendChainReductionFacadeTest` 锁定成功缓存、空结果缓存与 nested attribute base 复用行为；相关 analyzer focused tests 与 `FrontendSemanticAnalyzerFrameworkTest` 均已回归通过。

## 6.4.4 D/E 间收敛顺序建议

建议按以下顺序推进，而不是同时做大范围重构：

1. 先抽“链头 receiver / 原子局部类型 support”
2. 再抽“状态对象转换桥”
3. 最后抽“带缓存的链 reduction 门面”

原因是：

- 第一步收益最大、风险最低
- 第二步最能降低语义状态再次漂移的风险
- 第三步主要是结构清理，依赖前两步先把 support 与 conversion 收口

这一轮重构完成后，再进入 E 阶段的 `:=` 回填与表达式覆盖扩张会更稳，不容易在 phase 边界重复堆积 glue code。

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

**当前实施状态（2026-03-17）**：

- [x] E1.a 已冻结本轮小改动边界：不新增 side table、不新增顶层 analyzer，仅在 `FrontendExprTypeAnalyzer` 中消费已发布的 RHS `expressionTypes()` 来回填支持域内的 block-local `:=` 绑定。
- [x] E1.b 已落地最小回填通道：`FrontendDeclaredTypeSupport` 现明确暴露 `:=` 判定合同，`BlockScope` 新增窄化的 local type backfill 入口，`FrontendExprTypeAnalyzer` 仅在 RHS 为 `RESOLVED` / `DYNAMIC` 时重写局部变量类型；`BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 保持原始 `Variant` 绑定不变。
- [x] E1.c 已补齐并跑通合同测试：`FrontendExprTypeAnalyzerTest` 现覆盖 resolved backfill、dynamic degrade-to-Variant、以及 deferred/unsupported/blocked 不回填的负路径；`FrontendDeclaredTypeSupportTest` 补充了 `:=` marker 判定；回归已跑通 `FrontendVariableAnalyzerTest`、`FrontendChainBindingAnalyzerTest`、`FrontendExprTypeAnalyzerTest`、`FrontendDeclaredTypeSupportTest`、`FrontendSemanticAnalyzerFrameworkTest`。
- [ ] E2/E3 仍维持原边界，不在本轮小改动中扩张 parameter default、operator/container/ternary/assignment typing 或更复杂的 flow-sensitive 恢复。

## 6.6 里程碑 F：static-route 头语义与链头失败合同收口

E1 把 `:=` 的最小回填通道接上后，当前 body phase 还剩两类会直接影响最终阶段稳定性的合同缺口，需要在进入更大范围的 E2/E3 扩张前先收口：

- `TYPE_META` 在 MVP 中不是一等 value，只能作为 static route 的语法头使用
- 链式调用的头 binding 一旦未解析，就属于源码错误；frontend 必须发出 error diagnostic，并把整条链稳定归类为 head-failure，而不是在不同消费路径里漂成空结果、`UNSUPPORTED`、`FAILED` 或被后续 `:=` 回填洗成普通 `Variant`

这一里程碑的目标不是增加新的语言支持面，而是把已经明确下来的 MVP 语义边界真正冻结进 published contract。

**实施细则**：

- F1：`TYPE_META` 的“static-route 头、非一等 value”合同收口
  - 明确 bare `TYPE_META` identifier 在 MVP 中不发布 ordinary value expression typing，也不参与 `:=` 回填
  - `FrontendExprTypeAnalyzer` 不得再把“合法 static route 内部的链头 `TYPE_META`”发布成会污染 side table 的普通 `UNSUPPORTED` 子表达式事实
  - 对于 `Worker.build(seed)`、`Worker.new()`、`Vector3.BACK`、`Node.NOTIFICATION_ENTER_TREE` 这类合法 static route：
    - 最终链式表达式继续按 `resolvedCalls()` / `resolvedMembers()` 发布稳定类型
    - static route head `Worker` / `Vector3` / `Node` 本身要么不写入 `expressionTypes()`，要么只按明确冻结的“route-head only”内部规则处理，但不得伪装成普通 feature-boundary `UNSUPPORTED`
  - 文档事实同步：
    - D1 中关于 top-level `TYPE_META` 的表述必须改成与本合同一致
    - 明确“top-level `TYPE_META` chain head 可参与 static route reduction”与“bare `TYPE_META` expression 不是普通 value”这两个语义并不矛盾
- F2：链头 binding 未解析的 hard-fail 合同收口
  - 只要链头 binding 未解析，或链头已发布 binding 但无法恢复出合法 receiver，就必须稳定进入 `FAILED`
  - 这一类 head-failure 必须发出 error diagnostic；diagnostic 允许在 LSP 源模式下与后续容错分析共存，但不能静默掉地板
  - `FrontendChainHeadReceiverSupport` / `FrontendChainReductionFacade` 不得再把 supported chain head 的失败压成 `null`
    - `binding == null`
    - `binding.kind() == UNKNOWN`
    - 已发布 head binding 与当前 scope/restriction 不一致且无法恢复 receiver
  - 上述场景必须改为返回结构化的 failed receiver state，再由 `FrontendChainReductionHelper` 统一做 suffix 传播
  - dynamic fallback 的边界必须保持清晰：
    - head 未解析 -> `FAILED`
    - head 已解析、但中间成员/方法只能运行时分派 -> `DYNAMIC`
    - 不允许再把 head-failure 伪装成 nested-unsupported、空结果缓存命中或普通 `Variant` 降级
  - `FrontendExprTypeAnalyzer`、`FrontendChainBindingAnalyzer` 与 local expression dependency resolver 对同一类 head-failure 必须给出同向 provenance
    - 顶层链式表达式
    - 作为 call argument / nested attribute 的子表达式
    - 作为 `:=` initializer 的 RHS
- F3：`:=` 与 provenance 保真
  - F2 完成后，重新核对 E1 回填策略
  - 对 head-failure RHS：
    - 局部变量 binding 允许继续保持 inventory 阶段的 `Variant` 以满足容错/LSP 继续分析
    - 但 initializer 自身的 `expressionTypes()` 与对应 diagnostic 必须稳定保留 `FAILED` provenance
    - 不得因为回填未发生，就让失败的 initializer 在后续消费者视角中只剩下一个“干净的 resolved Variant local”而无法追溯来源
  - 若需要补充“声明由失败 initializer 引入”的测试锚点，应优先通过现有 side table 与 diagnostic 合同表达，而不是新增新的 side table

**验收标准**：

- F1 完成后：
  - bare `TYPE_META` 不再被文档误写成 ordinary value expression
  - 合法 static route 不会因为其链头 `TYPE_META` 而在 `expressionTypes()` 中留下误导性的普通 `UNSUPPORTED` 子节点记录
  - `Worker.build(seed)` / `Worker.new()` / `Vector3.BACK` / `Node.NOTIFICATION_ENTER_TREE` 均有 focused tests 锁定 head 与整链的发布行为
- F2 完成后：
  - 顶层坏链头会稳定发出 error diagnostic
  - nested bad chain（例如作为 argument 的子表达式）也会保持 `FAILED`，而不是漂成 `UNSUPPORTED`
  - `FrontendChainReductionFacade` 不再把 supported head failure 压成 `null`
  - dynamic middle-step fallback 与 head-failure 的测试矩阵明确分开
- F3 完成后：
  - `:=` initializer 为 head-failure 时，不会回填局部变量真实类型
  - 但 initializer 的 `FAILED` expression typing 与对应 diagnostic 仍可被下游稳定观察
  - 不会再出现“同一类坏链头在 chain analyzer 中静默、在 expr analyzer 中 failed、在 nested dependency 中 unsupported”的 provenance 分叉

**推荐 targeted tests**：

- `FrontendChainBindingAnalyzerTest`
- `FrontendExprTypeAnalyzerTest`
- `FrontendSemanticAnalyzerFrameworkTest`
- 若新增 head-failure / static-head 专项 support tests，则一并纳入 targeted 回归

**当前实施状态（2026-03-17）**：

- [x] F1.a 已冻结 static-route head `TYPE_META` 发布合同：`FrontendExprTypeAnalyzer` 现只把这类 `TYPE_META` identifier 当作 reduction 的瞬时输入，不再把它们写入 ordinary `expressionTypes()`，因此也不会被 `:=` 回填路径误消费。
- [x] F1.b 已收口 static-route head 污染面：`Worker.build(seed)`、`Worker.new()`、`Vector3.BACK`、`Node.NOTIFICATION_ENTER_TREE` 这类合法 static route 的整链结果继续正常发布，但链头 `TYPE_META` 本身不再以普通 `UNSUPPORTED` 子表达式形态混入 side table。
- [x] F1.c 已补齐 focused tests：`FrontendExprTypeAnalyzerTest` 现同时锚定 legal static route 的 head `TYPE_META` 不落 ordinary expression typing、整链结果继续稳定发布、以及 unsupported static-route initializer 仍保持原有 `UNSUPPORTED`/不回填行为。
- [x] F2.a 已统一 head receiver failure 语义：`FrontendChainHeadReceiverSupport` 现对 `binding == null`、`binding.kind() == UNKNOWN`、以及“已发布 binding 但当前 scope 无法恢复 receiver”稳定返回结构化 `FAILED` receiver state；`FrontendChainReductionFacade` 对这类 supported head failure 不再缓存空结果。
- [x] F2.b 已收口 top-level head-failure 出口：`FrontendChainReductionHelper` 现为 head-failure 的第一步补出具体 failed trace，使 `FrontendChainBindingAnalyzer` 能通过既有 `sema.member_resolution` / `sema.call_resolution` error 通道稳定诊断顶层坏链头，而不是静默为空结果。
- [x] F2.c 已补齐 provenance 回归：`FrontendExprTypeAnalyzer` / local expression dependency 现对 nested bad chain 与 `:=` initializer RHS 同向保留 `FAILED`，不再把 head-failure 漂成 nested `UNSUPPORTED`；support/helper/analyzer targeted tests 已覆盖 missing/unknown head、nested argument、initializer RHS 三类负路径。
- [x] F3.a 已冻结 backfill / observability 实现合同：`FrontendExprTypeAnalyzer` 的 `:=` backfill 现明确只改写 block-local inventory slot，不反向改写 `expressionTypes()` / `symbolBindings()`；`BlockScope.resetLocalType(...)` 也明确保留 declaration identity，使 downstream consumer 仍可沿 use-site binding 的 `declarationSite()` 回到原始 initializer provenance。
- [x] F3.b 已补齐系统化测试锚点：`FrontendExprTypeAnalyzerTest` 现对 `:=` local use-site 统一断言 `symbolBindings().declarationSite()` 仍回指原始 `VariableDeclaration`，并可进一步读取该 declaration 的 initializer `expressionTypes()`；覆盖矩阵同时包含 `RESOLVED` / `DYNAMIC` 回填正路径，以及 `FAILED` / `DEFERRED` / `UNSUPPORTED` / `BLOCKED` 不回填负路径。
- [x] F3.c 已完成 E1 回填可观测性复核：head-failure initializer 继续保留 `FAILED` expression typing 与原有 diagnostics，本地变量 binding 仅维持 inventory 阶段的 `Variant` 容错类型；后续消费者不需要新增 side table，即可沿“local use-site -> binding declaration site -> initializer expression type/diagnostic”稳定恢复 provenance。

## 6.7 里程碑 G：callable 值语义、discarded-expression warning 与 expr-owned diagnostics 收口

F 阶段完成后，frontend body phase 仍有一块与 GDScript 语义不对齐、且会直接影响用户可见诊断质量的缺口尚未收口：

- bare expression statement 在语义上是允许存在的，但除了返回 `void` 的函数调用以外，frontend 需要发出 warning；当前 `handleExpressionStatement(...)` 只发布 `expressionTypes()`，没有 statement-position warning 合同。
- bare function name 在 value position 上应 materialize 为 `Callable`；当前实现要么把它当成 unknown value 发错误的 `sema.binding`，要么在 expression-only 路径里继续漂成 `DEFERRED` / `FAILED`。
- bare `TYPE_META` 在 ordinary value position 上也缺少显式合同；当前实现最多只能借“普通 value lookup miss”发一条泛化的 `sema.binding`，并没有真正声明“这是已知 type-meta 被错误当成 value 使用”。
- `FrontendExprTypeAnalyzer` 已显式接收 `DiagnosticManager`，但整个文件没有任何真正的 diagnostic emission；bare callable、bare call、assignment、subscript、generic deferred expression 等 expression-only 路径大量停留在“side table 有状态、用户侧无 frontend diagnostic”的不完整状态。

这一里程碑的目标不是把 E2/E3 的更大表达式覆盖面提前做完，而是以最小改动把三件事真正冻结进 published contract：

- bare callable 是 ordinary value，值类型为 `Callable`
- expression statement 的 discarded-result warning 规则稳定可测
- `FrontendExprTypeAnalyzer` 从“状态发布器”升级为真正承担 expr-owned 恢复合同的 analyzer

设计锚点需要与现有 GDScript 语义保持一致：

- Godot 官方 warning 集合中已经区分 `STANDALONE_EXPRESSION` 与 `RETURN_VALUE_DISCARDED`
- Godot 源码对 `FUNCTION_USED_AS_PROPERTY` 的注释已明确写明“这是合法的，会返回 `Callable`”
- 官方 `Callable` 文档也把方法与全局函数都视为可作为值存储和传递的一等 `Callable`

**实施细则**：

- G0：冻结 owner 边界与非目标
  - `FrontendTopBindingAnalyzer` 仍只发布 symbol category，不升级为 usage-aware binding model。
  - `FrontendChainBindingAnalyzer` 继续拥有 chain/member/call route diagnostics；不要把既有 `sema.member_resolution` / `sema.call_resolution` 的责任悄悄迁移到 expr analyzer。
  - `FrontendExprTypeAnalyzer` 新增的诊断责任只覆盖 expression-only 路径与 statement-position warning：
    - bare `TYPE_META` ordinary-value misuse 的 downstream provenance
    - bare callable identifier / member-method reference / bare call
    - assignment / subscript / generic deferred expression
    - discarded expression statement
  - 不新增 side table，不引入 whole-module fixpoint，不在本里程碑顺手扩张 parameter default、lambda、`for`、`match` 的正式 typing 覆盖面。
- G1：value-position bare callable / bare `TYPE_META` 分流
  - `bindValueIdentifier(...)` 继续先走 value namespace；只有 value lookup 返回 `NOT_FOUND` 时，才允许继续做 namespace fallback。
  - fallback 顺序需要显式冻结为：
    - value winner
    - function namespace
    - type-meta misuse
    - generic unknown value miss
  - value namespace 一旦 `FOUND_ALLOWED` 或 `FOUND_BLOCKED`，仍按当前 value winner 发布，不允许 function / type-meta namespace 反抢，保持现有 shadowing / blocked-hit 语义。
  - value miss 但 function namespace 命中时，沿用现有 `publishFunctionBinding(...)` / `classifyFunctionBindingKind(...)`，在 value-position 也允许发布 `METHOD` / `STATIC_METHOD` / `UTILITY_FUNCTION`；不要为此重塑 `FrontendBinding` 数据模型。
  - value 与 function 都 miss、但 type-meta namespace 命中时，不能再退化成 generic unknown value miss：
    - 需要发布真实的 `TYPE_META` symbol category
    - 同时发一条精确的 `sema.binding` error，明确说明“`TYPE_META` 只能作为 static-route head 使用，不能作为 ordinary value”
    - diagnostic message 必须覆盖典型修复方向，例如 `Worker.build(...)` / `Worker.new()` / `Vector3.BACK`
  - `DEFERRED_UNSUPPORTED` 仍保持原 binding boundary，不在 skipped subtree 中偷偷探测 function/type-meta namespace。
  - 文档同步：
    - `frontend_top_binding_analyzer_implementation.md` 中“`METHOD` / `STATIC_METHOD` / `UTILITY_FUNCTION` 只表示 bare callee”的表述要改成“function-like symbol category，可被 callee/value use-site 共同消费”
    - 同时补充 bare `TYPE_META` ordinary-value misuse 的 binding-owner 规则，避免后续再退回 generic unknown value
- G2：callable 作为值的类型发布
  - `FrontendExprTypeAnalyzer.resolveIdentifierExpressionType(...)` 对 `METHOD` / `STATIC_METHOD` / `UTILITY_FUNCTION` 不再发布 `DEFERRED`；改为发布 `RESOLVED(Callable)`。
  - `FrontendChainBindingAnalyzer` 的 local expression dependency resolver 也要同步采用同一规则，否则 callable argument 仍会把外层 call 锁死在 `DEFERRED`。
  - `GdCallableType` 作为最小 published type：
    - 优先允许保守 materialization，先稳定发布 `Callable` 本身；
    - 只有在 overload payload 足够稳定且不会引入额外分派歧义时，才补精确 `returnType` / `arguments`；
    - 不要因为想一次性做精确 callable signature 而拖慢本里程碑落地。
  - bare function name 在以下位置都要表现成 ordinary value：
    - expression statement 根
    - call argument
    - local initializer / `:=` RHS
    - attribute chain head，例如 `foo.call(...)`、`foo.bind(...)`
  - 与 bare callable 相对，bare `TYPE_META` ordinary-value misuse 需要显式收口成“已知非法 value 用法”，而不是继续混成 route-head-only 特判：
    - 现有 `isRouteHeadOnlyTypeMeta(...)` 基于 `binding.kind() == TYPE_META` 的一刀切 skip 规则过宽
    - 该规则必须改为语法敏感的 static-route-head 判定，只对“正在参与合法 static route reduction 的链头 base”跳过 ordinary publication
    - `Worker`、`consume(Worker)`、`var bad := Worker` 这类 bare value use-site 一旦已经被 top binding 识别为 `TYPE_META`，expr analyzer 必须保留结构化 provenance，而不能被 route-head-only 规则直接吞掉
  - bare `TYPE_META` 在 ordinary value position 的 expression status 也需要冻结：
    - 对用户而言这是源码错误，不是 generic unknown symbol
    - downstream `expressionTypes()` / local dependency result 应稳定归类为 `FAILED`
    - 若上游同一 use-site 已由 `sema.binding` 发出精确 error，expr analyzer 只保留 `FAILED` provenance，不重复补第二条同级 error
- G3：bare call typing 与 `void` 判定最小闭环
  - `resolveCallExpressionType(...)` 不能再无条件返回 `DEFERRED`。
  - 第一轮只收口真正的 bare callee path：
    - `callee` 为 bare `IdentifierExpression`
    - top binding 已发布 function-like binding
    - argument types 已通过现有 local dependency 规则可见
  - 对这一路径，expr analyzer 应基于当前 scope / restriction 与 argument types 做真正的 bare-call 选择，并发布：
    - `RESOLVED(returnType)`
    - `BLOCKED(returnType when overload resolution succeeds)`
    - `FAILED`
    - `DEFERRED`
    - `UNSUPPORTED`
  - `void` 免 warning 合同必须来自这里，而不是从 statement 语法形状硬编码猜测：
    - 只有“bare call 已稳定解析，且 return type 确认是 `void`”时，expression statement 才保持静默
    - 其他 bare call 一律走普通 discarded-expression / expr-owned diagnostic 规则
  - 非目标：
    - 不在这一轮把 arbitrary callable object 的 `foo()` 直接调用语义一并扩张进去
    - 若 `callee` 不是 bare identifier 或现有 MVP 能稳定识别的 route，必须显式 `DEFERRED` / `UNSUPPORTED` 并发 expr-owned diagnostic，而不是继续 silent side-table
- G4：method reference / static method reference 作为 member value
  - `FrontendChainReductionHelper.reducePropertyStep(...)` 在 property/signal/static-load 路线之后，需要新增“method-as-value” route：
    - `instance.method` -> `Callable`
    - `TYPE_META.static_method` -> `Callable`
  - 路由优先级必须保持保守：
    - 先 property
    - 再 signal
    - 再 static load
    - 最后才尝试 method reference
  - `FrontendResolvedMember` 继续复用当前 published model，使用 `bindingKind = METHOD / STATIC_METHOD` 与 `resultType = Callable` 表达 method reference；不新增第五张表。
  - `FrontendChainHeadReceiverSupport.resolveIdentifierHeadReceiver(...)` 对 function-like binding 不再一律返回 `FAILED`；在 value position 上应能 materialize `Callable` receiver，使 `foo.call(...)` / `foo.bind(...)` / `Type.func.bind(...)` 这类链头继续进入既有 chain reduction。
- G5：`FrontendExprTypeAnalyzer` 的 expr-owned diagnostics 与 statement warning
  - 新增 expr analyzer 自己的诊断出口，不再只把状态写进 `expressionTypes()`。
  - 建议新增并冻结以下 category：
    - `sema.expression_resolution`
    - `sema.deferred_expression_resolution`
    - `sema.unsupported_expression_route`
    - `sema.discarded_expression`
  - 诊断 owner 规则：
    - chain/member/call route 仍由 chain analyzer 发
    - bare call / assignment / subscript / generic deferred / discarded-expression 由 expr analyzer 发
    - bare `TYPE_META` ordinary-value misuse 的首条用户可见 error 仍由 top binding 发；expr analyzer 负责把它保真进 `FAILED` provenance，而不是重复发第二条同级错误
    - 若上游 binding/member/call 已经为同一根源错误发过 diagnostic，expr analyzer 只保留 published status，不重复补第二条同级 error
  - `handleExpressionStatement(...)` 需要从“只发布类型”升级为“发布类型 + statement-position policy”：
    - `RESOLVED` 且 published type 明确非 `void` -> 发 `sema.discarded_expression` warning
    - `RESOLVED(Callable)` -> 发 `sema.discarded_expression` warning
    - bare call resolved to `void` -> 不发 warning
    - `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` / `DYNAMIC` 根节点本轮不再额外叠加 discarded warning，优先依赖各自的 expr-owned / upstream diagnostic，避免双报与误报
  - deferred / unsupported recovery 也要像 chain analyzer 一样有 root 去重：
    - 一个 expr-owned deferred root 只报一次
    - 一个 unsupported boundary 只在最近 recovery root 报一次
    - 不让 nested dependency 与顶层 statement 对同一坏子树各报一条
- G6：回归边界与文档收口
  - 不得破坏 F 阶段已冻结的 `TYPE_META` route-head only 合同。
  - 不得破坏 E1 的 `:=` backfill 规则：initializer provenance 继续以 `expressionTypes()` + diagnostics 为真源，local binding 只做 block-local type rewrite。
  - `diagnostic_manager.md` 必须同步把 expression typing 从“仍待扩展”更新为“expr-owned diagnostics 已落地”，并登记新增 category 语义。
  - `frontend_rules.md` 需要补充 expression-only diagnostics 的 owner 约束，避免后续 analyzer 再把相同错误各自发一遍。

**验收标准**：

- G0 完成后：
  - callable value / discarded-expression / expr-owned diagnostics 的 owner 边界已经冻结
  - 本里程碑不依赖新的 side table 或新的顶层 phase
- G1 完成后：
  - bare function name 在 value position 上不再误发 “unknown value binding” 的 `sema.binding`
  - bare `TYPE_META` ordinary-value use-site 不再退化成 generic unknown value miss，而会发出精确的 type-meta misuse `sema.binding` error
  - local/global value 仍优先于 function namespace，blocked winner 仍继续遮蔽 outer/global function
  - `FrontendBindingKind` 的 function-like category 已能被 callee/value 双路径共同消费
  - bare `TYPE_META` 在普通 value position 上会发布真实的 `TYPE_META` category，而不是丢失为 `UNKNOWN`
- G2 完成后：
  - bare callable identifier 在 `expressionTypes()` 中稳定发布 `RESOLVED(Callable)`
  - callable argument 不再把外层 call 无谓拖成 `DEFERRED`
  - `foo.call(...)` / `foo.bind(...)` 这类 callable chain head 已具备稳定 receiver facts
  - bare `TYPE_META` ordinary-value use-site 不再被 route-head-only skip 规则吞掉；其 initializer / nested dependency / statement root 都能稳定保留 `FAILED` provenance
- G3 完成后：
  - bare call 能稳定区分 `void` / non-`void` return type
  - `void` bare call 作为 expression statement 保持静默
  - non-`void` bare call 作为 expression statement 会发 discarded warning
  - unsupported bare call 不再只停留在 side table 状态
- G4 完成后：
  - `obj.method` / `Type.static_func` 能作为 ordinary value 发布 `Callable`
  - property/signal/static-load 的既有优先级与 diagnostics 无回归
  - method reference 的 member route 不会错误覆盖真实 property / signal / static load winner
- G5 完成后：
  - bare call、assignment、subscript、generic deferred expression 的 `FAILED` / `DEFERRED` / `UNSUPPORTED` 至少会走“expr-owned diagnostic”或“明确已有 upstream diagnostic”二选一，不再出现 silent side-table-only 状态
  - 同一坏子树不会被顶层 statement、nested dependency 与局部恢复路径重复各报一条
  - `FrontendExprTypeAnalyzer` 不再只是状态发布器，而是真正拥有 expression-only 恢复出口
- G6 完成后：
  - `TYPE_META` route-head only、`:=` provenance/backfill、既有 chain diagnostics 合同都保持稳定
  - 相关 implementation/rules/diagnostic 文档已与新合同同步

**推荐 targeted tests**：

- `FrontendTopBindingAnalyzerTest`
- `FrontendExprTypeAnalyzerTest`
- `FrontendChainBindingAnalyzerTest`
- `FrontendChainHeadReceiverSupportTest`
- `FrontendChainReductionHelperTest`
- `FrontendSemanticAnalyzerFrameworkTest`

建议至少补齐以下 focused case matrix：

- bare function name 作为 value：
  - `foo`
  - `consume(foo)`
  - `var cb := foo`
  - local value 遮蔽同名 function
- bare `TYPE_META` 作为 ordinary value：
  - `Worker`
  - `consume(Worker)`
  - `var bad := Worker`
  - legal static route `Worker.build()` / `Worker.new()` / `Vector3.BACK` 继续不为 head 本身发误报
- bare call：
  - `foo()` 返回 `void`，expression statement 不告警
  - `foo()` 返回 non-`void`，expression statement 发 warning
  - bare call no-applicable-overload / blocked / unsupported route 必须有 frontend diagnostic
- method reference：
  - `obj.method`
  - `Type.static_func`
  - `obj.method.bind(1)`
  - `Type.static_func.call()`
- expr-owned recovery：
  - `a = b`
  - `arr[i]`
  - 当前仍未正式支持的 generic deferred expression root
  - nested dependency 与顶层 statement 共享同一坏子树时的去重

**当前实施状态（2026-03-19）**：

- [x] G0 已冻结 owner 边界与非目标：`FrontendTopBindingAnalyzer` 继续只发布 symbol category，并负责 bare `TYPE_META` ordinary-value misuse 的首条 `sema.binding`；`FrontendChainBindingAnalyzer` 继续拥有 chain/member/call route diagnostics；`FrontendExprTypeAnalyzer` 现只补 expression-only diagnostics 与 discarded-expression warning，没有新增 side table，也没有把 lambda / parameter default / `for` / `match` 偷偷扩成正式 typing 面。
- [x] G1 已落地 value-position callable / type-meta 分流：`bindValueIdentifier(...)` 与 `bindTopLevelTypeMetaCandidate(...)` 现统一冻结为“value winner -> function namespace -> type-meta misuse -> generic unknown miss”；bare function name 在 value position 会发布 `METHOD` / `STATIC_METHOD` / `UTILITY_FUNCTION`，bare `TYPE_META` 则发布真实 `TYPE_META` 并由 top binding 发出精确 `sema.binding`，不再退化成 generic unknown value miss。
- [x] G2 已落地 callable value typing 与 bare `TYPE_META` provenance：`FrontendExprTypeAnalyzer.resolveIdentifierExpressionType(...)` 与 chain analyzer 的 local dependency resolver 现把 function-like binding materialize 为 `RESOLVED(Callable)`，从而让 bare callable、callable argument、`foo.call(...)` / `foo.bind(...)` 与 bare local initializer 共享同一 published contract；同时 route-head-only `TYPE_META` skip 已收窄为“仅合法 static-route head base 不落 ordinary publication”，bare `TYPE_META` value use-site 会稳定保留 `FAILED` provenance，不再被静默吞掉。
- [x] G3 已落地 bare call typing 与 `void` 判定最小闭环：`resolveCallExpressionType(...)` 现对 bare identifier callee 做真正的 overload 选择，并稳定区分 `RESOLVED` / `BLOCKED` / `FAILED` / `DEFERRED` / `UNSUPPORTED`；statement-position 上只对 resolved non-`void` bare call 发 discarded warning，而 resolved `void` bare call 保持静默。
- [x] G4 已落地 method-as-value route：`FrontendChainReductionHelper.reducePropertyStep(...)` 现保持 property -> signal -> static load 的既有优先级后，再保守尝试 method reference，把 `obj.method` / `Type.static_func` 发布为 `Callable`；`FrontendChainHeadReceiverSupport.resolveIdentifierHeadReceiver(...)` 也已支持 function-like chain head materialization，使 `foo.call(...)`、`foo.bind(...)`、`Type.func.bind(...)` 能继续进入既有 chain reduction，而不是 fail-closed。
- [x] G5 已落地 expr-owned diagnostics 与 statement warning：`FrontendExprTypeAnalyzer` 现正式发出 `sema.expression_resolution` / `sema.deferred_expression_resolution` / `sema.unsupported_expression_route` / `sema.discarded_expression`，并对 assignment、subscript、generic deferred expression、bare call 与 discarded-expression statement 建立 root 去重；若同一根源错误已由 top binding / chain binding 发过诊断，expr analyzer 只保留 published status，不重复补第二条同级 error。
- [x] G6 已完成文档收口与回归边界复核：`diagnostic_manager.md`、`frontend_rules.md`、`frontend_top_binding_analyzer_implementation.md` 已同步 bare callable 价值语义、bare `TYPE_META` misuse owner、expr-owned diagnostics category 与 statement warning 合同；F 阶段冻结的 `TYPE_META` route-head only 规则、E1 的 `:=` provenance/backfill 规则以及既有 chain diagnostics 均保持稳定。

**本轮 targeted 验收（2026-03-18）**：

- 已运行：`powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendTopBindingAnalyzerTest,FrontendExprTypeAnalyzerTest,FrontendChainBindingAnalyzerTest,FrontendChainHeadReceiverSupportTest,FrontendChainReductionHelperTest,FrontendSemanticAnalyzerFrameworkTest`
- 结果：`BUILD SUCCESSFUL`
- 当前 focused coverage 已锚定：
  - bare function name 作为 ordinary value、call argument、initializer 与 callable chain head
  - bare `TYPE_META` ordinary-value misuse 与合法 static route head 分流
  - bare call 的 `void` / non-`void` discarded-expression policy
  - method / static-method reference 作为 member value 的 `Callable` materialization
  - assignment / subscript / generic deferred expression 的 expr-owned diagnostics 与 root-level 去重

## 6.8 里程碑 H：共享表达式语义 support、subscript/container typing 与 assignment 语义收口

G 里程碑完成后，frontend body phase 仍有三条会继续污染 published contract 的高风险空洞，需要在更大范围的 E2/E3 扩张之前先收口：

- `FrontendChainBindingAnalyzer` 与 `FrontendExprTypeAnalyzer` 目前各自维护了一套局部表达式语义解析：
  - identifier / bare callable materialization
  - bare call overload 选择
  - nested expression dependency typing
  - direct-callable-invocation unsupported sealing
  - 这些逻辑在 G 之后已经明显变长；若继续分别扩张 subscript / assignment / 更多 expression node，语义漂移风险会显著上升
- `SubscriptExpression` 与 `AttributeSubscriptStep` 现在仍停留在“有诊断的 deferred / unsupported”：
  - plain subscript 没有真正的 base/container typing 与 element/result typing
  - attribute-subscript 没有 exact chain reduction route
  - chain-local dependency typer 也没有把 subscript 视为一等 expression node
- `AssignmentExpression` 当前仍只是 deferred 包装，但 GDScript 语义要求 assignment 是有副作用的语句节点，不是返回普通值的 expression：
  - 不能继续停留在 generic deferred wrapper
  - 也不能直接把 assignment 改成“普通 resolved value”，否则会与现有 discarded-expression policy 发生冲突

这一里程碑的目标不是把 E2/E3 的更大表达式覆盖面整体提前做完，而是以最小但真实可消费的语义收口以下四件事：

- 把 duplicated expression semantic logic 抽取为公共 support，冻结单一真源
- 让 supported container family 上的 plain / attribute subscript 成为真正 typed semantics
- 让 assignment 以符合 GDScript 语义的“成功但无值”合同转正
- 明确缩小 generic deferred bucket，并补齐 callable 分支的 focused tests

设计锚点与新增语义需要与当前已知规则保持一致：

- `FrontendExpressionType.publishedType` 的语义是 downstream 可消费类型；`DEFERRED` / `FAILED` / `UNSUPPORTED` 不发布类型，因此 subscript / assignment 不能继续长期停留在 placeholder 状态。
- 官方 GDScript 语义要求 assignment 不是 ordinary value expression；statement-position 的 `_a = 2` 不属于 `STANDALONE_EXPRESSION` warning 范畴。
- 官方 warning 体系已经区分 `STANDALONE_EXPRESSION` 与 `RETURN_VALUE_DISCARDED`，因此 assignment 转正时不能误入 discarded-expression warning。
- 现有类型系统与 backend 已经具备 container key/value type、index load/store 与 assignment target 基础设施；frontend 的缺口主要在 published contract 没有接通，而不是底层模型不存在。

**新增语义与明确非目标**：

- 新增语义：
  - 共享 expression semantic support 成为 chain/expr 两个 analyzer 的唯一局部表达式语义真源；analyzer 自己只继续拥有 publication、diagnostic owner 与 statement policy。
  - supported container family 上的 plain subscript / attribute-subscript 将发布真正的 typed result，而不是单纯 deferred/unsupported wrapper。
  - assignment 成功态按 GDScript 语义发布 `RESOLVED(void)`，表达“语义成功且有副作用，但不产生 ordinary value”。
  - `handleExpressionStatement(...)` 的 warning policy 需要显式区分 ordinary discarded value、void call、assignment-success root 三类语义。
  - generic deferred bucket 需要从“默认兜底黑箱”收窄为“显式枚举的剩余延期节点集合”。
- 明确非目标：
  - 不在 H 里程碑一次性做完 full operator / ternary / array literal / dictionary literal / cast / await / preload / get_node typing。
  - 不在 H 里程碑引入 whole-module fixpoint、新的顶层 analyzer phase 或新的全局 side table。
  - 不在 H 里程碑扩张 destructuring / pattern assignment、flow-sensitive assignment refinement、arbitrary keyed builtin 的完整 subscript 语义。
  - 不在 H 里程碑重塑 `FrontendBinding` 为 usage-aware 模型；assignment target 解析需要新增独立 support/model，而不是让 top binding 越权。

**实施细则**：

- H0：提取共享 expression semantic support
  - 在 `frontend/sema/analyzer/support` 中新增公共 support，建议命名为 `FrontendExpressionSemanticSupport` 或语义等价名称。
  - 该 support 必须是 pure semantic helper：
    - 不直接写 side table
    - 不直接发 diagnostic
    - 不直接 walk AST statement tree
  - analyzer 责任边界需要冻结为：
    - shared support：identifier / bare callable / bare call / subscript / assignment / remaining explicit-deferred nodes 的局部语义解析
    - `FrontendExprTypeAnalyzer`：publication、expr-owned diagnostics、statement warning、`:=` backfill
    - `FrontendChainBindingAnalyzer`：chain/member/call route diagnostics、本地 dependency 消费、shared helper callback 接线
  - `FrontendChainBindingAnalyzer.resolveExpressionType(...)` 与 `FrontendExprTypeAnalyzer.resolveExpressionType(...)` 不得再各自维持独立但语义重复的 call/callable/subscript/assignment 实现。
  - shared support 的输出必须保持 analyzer-neutral：
    - 推荐直接返回 `FrontendExpressionType` 或与其等价的 pure result model
    - 若 chain analyzer 需要 `ExpressionTypeResult`，只能通过 bridge 转换，不得在两个 analyzer 内部复制一套状态映射
  - 当前落地约束：
    - `FrontendExpressionSemanticSupport` 已成为 literal / `self` / identifier / bare callable / bare call / assignment / subscript / explicit-deferred 的唯一局部语义真源。
    - shared support 通过 `rootOwnsOutcome` 区分“当前 root 自己产生的结果”与“从子依赖上传播的结果”；只有 analyzer 自己可以据此决定是否发 expr-owned diagnostic。
    - chain analyzer 继续保留 `finalizeWindow` 传递能力；shared support 只消费该窗口，不拥有 retry/publish policy。
- H1：subscript/container typing 收口
  - 新增共享 `FrontendSubscriptSemanticSupport`，或作为 H0 shared support 的子域实现：
    - plain `SubscriptExpression`
    - `AttributeSubscriptStep`
    - chain-local dependency 中出现的 nested subscript
  - 第一阶段仅正式支持 `GdContainerType` family：
    - `Array[T]`
    - `Dictionary[K, V]`
    - packed array family
  - 支持规则固定为：
    - receiver 为 `GdContainerType`：
      - key/index type 用 `ClassRegistry.checkAssignable(argumentType, container.getKeyType())` 校验
      - 成功后结果类型为 `container.getValueType()`
      - 这是 H1 明确接受的 **strict typed MVP contract**，不是对 Godot keyed/index 兼容性的完整对齐：
        - 当前不额外放宽 `String` / `StringName` 互通
        - 当前不额外放宽 `int -> float`
        - 当前不额外放宽 `null -> Object`
        - 当前也不为 `Array` / packed array 额外接受 float index
    - receiver 为 `Variant`：
      - 发布 `DYNAMIC(Variant)`
      - 不退化成 deferred
    - receiver 不是 container：
      - 发布明确 `FAILED`
    - metadata 表明 receiver keyed，但 frontend 当前仍无稳定规则时：
      - 显式 `UNSUPPORTED`
      - 不退回 generic deferred
      - 该分支在 MVP 中是刻意的范围冻结，而不是待自动降级的“半支持”：
        - `String`
        - `Vector2` / `Vector3` / `Vector4`
        - `Color`
        - `Basis`
        - `Transform2D` / `Transform3D`
        - `Projection`
        - `Object`
        - 其他 extension metadata 仅声明 `isKeyed`、但当前 frontend 仍缺少稳定 key/result typing 规则的 builtin route
  - `FrontendChainReductionHelper` 必须把 `AttributeSubscriptStep` 从 hardcoded unsupported 改成真实 reduction step，并复用现有 argument finalize/retry window。
  - `FrontendChainBindingAnalyzer` 的本地 expression dependency typer 必须把 `SubscriptExpression` 纳入一等节点，否则 chain call argument 中的 `arr[i]` 仍会无谓拖成 `DEFERRED`。
  - 当前阶段不新增独立的 subscript side table；先依赖 `expressionTypes()` 与 chain reduction trace/output receiver 完成最小闭环。
  - 当前实施拆分（2026-03-18）：
    - [x] H1.1 已完成：新增 `FrontendSubscriptSemanticSupport` 作为 shared 子域真源，统一 plain/attribute subscript 的 container-family、Variant、keyed-builtin、non-container contract。
    - [x] H1.2 已完成：`FrontendExpressionSemanticSupport` / `FrontendChainReductionHelper` 均已接线 shared subscript support；`AttributeSubscriptStep` 不再 hardcoded unsupported，而是先解析 `name` 对应 member value，再对其结果类型应用 subscript 语义。
    - [x] H1.3 已完成：`FrontendExprTypeAnalyzer` 已统一 shared support 的 root-owned deferred/failed/unsupported 诊断出口，并补齐 attribute-subscript argument 的 expression-type 发布，避免 final-step 直接命中 published member/call 时漏发 nested argument facts。
    - [x] H1.4 已完成：已新增/扩展 `FrontendSubscriptSemanticSupportTest`、`FrontendExpressionSemanticSupportTest`、`FrontendExprTypeAnalyzerTest`、`FrontendChainReductionHelperTest`、`FrontendChainBindingAnalyzerTest`，覆盖 container happy path、Variant dynamic、bad key type、non-container receiver、keyed-builtin unsupported、plain subscript nested call argument、attribute-subscript exact suffix 与 unsupported boundary；并跑通上述测试以及 `FrontendChainStatusBridgeTest`、`FrontendSemanticAnalyzerFrameworkTest`。
- H2：assignment 语义与 statement policy 收口
  - 新增独立 assignment semantic support，建议命名为 `FrontendAssignmentSemanticSupport` 或语义等价名称。
  - 必须新增 assignment-target resolution model，而不是继续依赖 usage-agnostic `FrontendBinding` 反推左值语义。
  - 第一阶段支持的 assignment target 建议保守冻结为：
    - bare identifier
    - attribute property
    - plain subscript
    - attribute-subscript
  - 第一阶段明确不支持：
    - destructuring / pattern assignment
    - flow-sensitive narrowing / smart-cast-style refinement
    - 更复杂的 multi-target assignment
    - compound assignment（`+=` / `-=` / `*=` / ...）：当前阶段必须稳定 `UNSUPPORTED`
  - 当前阶段保守冻结的额外边界：
    - `attribute-subscript` 目前按“property value 上的 container element mutation”建模
    - getter-only property 是否同时阻断 `obj.prop[i] = value` 这类 aliasing route 暂不在 H2 冻结；后续需结合 property/container mutation contract 一并收口
  - assignment 成功态 contract 必须明确为：
    - 发布 `RESOLVED(void)`
    - 表达“赋值语义成功，但不产生 ordinary value”
    - 不得发布 RHS 类型为 assignment 的 published type
  - 赋值校验至少要冻结以下行为：
    - target 是否可写
    - RHS 是否 assignable 到 target slot
    - constant / method reference / `TYPE_META` / 不可写 signal 等非法左值要稳定 `FAILED`
  - bare identifier property 与 attribute-property 的 direct-write writable 判定必须同源：
    - scope publication 负责把 bare identifier 所需的最小 writable contract 放进 `ScopeValue`
    - use-site assignment analysis 负责 receiver/path 语义，但不能另起一套 property writable metadata 解释
    - `ScopeValue.writable` 仅服务 bare identifier direct write；不覆盖 `attribute-subscript` aliasing 或更广的 property/container mutation 语义
  - `handleExpressionStatement(...)` 需要同步重构为语义分类策略：
    - resolved ordinary non-`void` value -> `sema.discarded_expression`
    - resolved `void` bare/chain call -> 静默
    - resolved assignment-success root -> 静默
    - `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` / `DYNAMIC` -> 继续优先依赖 expr-owned / upstream diagnostic，不额外叠 discarded warning
  - 若 assignment 出现在 value-required 位置，必须 fail-closed，而不是伪装成 ordinary resolved value。
  - 当前实施拆分（2026-03-18）：
    - [x] H2.1 已完成：新增 `FrontendAssignmentSemanticSupport` 作为 shared assignment 真源，并从 `FrontendExpressionSemanticSupport` 中移除 assignment 语义；left-hand side 不再走 ordinary expression resolver，而是通过 dedicated assignment-target model 单独解析。
    - [x] H2.2 已完成：`FrontendExprTypeAnalyzer` 已按 statement-root / value-required 分流 assignment usage；statement-root success 稳定发布 `RESOLVED(void)`，且 `handleExpressionStatement(...)` 不再对 assignment-success 误发 discarded warning。
    - [x] H2.3 已完成：`FrontendChainBindingAnalyzer` 也已接线 shared assignment support，nested assignment 在 local dependency typing 中稳定 fail-closed；illegal lvalue、assignability failure 与 compound assignment boundary 现拥有明确 `FAILED` / `UNSUPPORTED` contract。
    - [x] H2.4 已完成：已新增 `FrontendAssignmentSemanticSupportTest`，并扩展 `FrontendExprTypeAnalyzerTest`、`FrontendChainBindingAnalyzerTest`；覆盖 bare identifier / attribute property / plain subscript / attribute-subscript success、signal / `TYPE_META` / bare callable / assignability failure、compound assignment unsupported，以及 value-required nested assignment 的正反路径。
    - [x] H2.5 已完成（2026-03-19）：`ScopeValue` 已新增 `writable` direct-write contract，`ClassScope` 通过共享 `PropertyDefAccessSupport` 发布 property writable 元数据；`FrontendAssignmentSemanticSupport` 现以同一 property writable helper 收口 bare identifier property 与 attribute-property，避免 `prop = value` / `self.prop = value` 对同一 readonly property 分叉。当前阶段仍明确不把 `ScopeValue.writable` 扩展为完整 property/container mutation 模型。
- H3：generic deferred bucket 收窄
  - `FrontendExprTypeAnalyzer` 不能继续把大批节点统一压回 “generic deferred expression” 黑箱。
  - 需要先把 remaining unsupported/deferred node kinds 显式列出并单独命名 reason，即使当前仍暂不转正。
  - 本里程碑结束时至少应满足：
    - `SubscriptExpression` 与 `AssignmentExpression` 不再属于 generic deferred bucket
    - 其余仍延期节点变为“显式列举的 deferred set”，而不是默认分支黑箱
  - 当前阶段不要求一并做完：
    - full operator typing
    - ternary typing
    - array/dictionary literal typing
    - await/preload/get_node/cast/type-test 的全面转正
- H4：callable focused test matrix 补齐
  - 补齐以下当前明显缺失的 focused tests：
    - `.bind(...)`：
      - `helper.bind(...)`
      - `self.helper.bind(...)`
      - `Worker.build.bind(...)`
    - blocked bare call：
      - chain path
      - expr path 必须继续断言 `BLOCKED` 且保留 overload-selected 真实返回类型，而不是把 published type 擦平成 `Callable`
    - direct-callable-invocation unsupported 变体：
      - callable produced by `.bind(...)`
      - callable chain head variant
    - callable chain head 变体：
      - `self.helper.call()`
      - `Worker.build.call()`
      - blocked / failed callable head receiver
  - `ambiguous bare call` 与 `empty overload set` 当前更适合作 helper-level focused test：
    - 可以通过提取 overload selection helper 做纯单元测试
    - 不要求强依赖 end-to-end GDScript fixture 自然触发这些分支
  - subscript 收口后，需要同步补 integration cases：
    - `items[0].bind(...)`
    - `dict["cb"].call()`
    - `consume(items[0])`

**验收标准**：

- H0 完成后：
  - chain/expr analyzer 的局部 expression semantic logic 已有单一 shared support 真源
  - duplicated identifier/call/callable/subscript/assignment 语义实现不再分散在两个 analyzer 内部漂移
  - shared support 本身不拥有 side-table publication 或 diagnostic owner
- H1 完成后：
  - supported container family 上的 plain subscript 稳定发布 `RESOLVED(element/value type)`
  - `AttributeSubscriptStep` 不再 hardcoded unsupported，exact suffix 可继续 reduction
  - `Variant` receiver subscript 稳定走 `DYNAMIC(Variant)`，而不是 generic deferred
  - non-container receiver 与当前未正式支持的 keyed route 拥有明确 `FAILED` / `UNSUPPORTED` contract
- H2 完成后：
  - assignment 不再只是 deferred wrapper
  - assignment-success root 稳定发布 `RESOLVED(void)`，且 statement-position 不发 discarded warning
  - 非法左值与 assignability 失败拥有明确 error contract
  - assignment 若被放到 value-required 位置，会 fail-closed，而不是伪装成 ordinary value
  - compound assignment 在当前阶段稳定 `UNSUPPORTED`，不再混入 generic deferred / ordinary assignment 成功语义
- H3 完成后：
  - generic deferred bucket 已从默认黑箱收窄为显式枚举集合
  - `SubscriptExpression` / `AssignmentExpression` 不再属于 generic deferred catch-all
  - 后续 E2/E3 若继续扩张 operator/ternary/其他 expression node，不需要再同时维护两个 analyzer 内部的漂移实现
- H4 完成后：
  - `.bind(...)`、blocked bare call（含真实返回类型合同）、callable direct invocation variants、callable chain head variants 均有 focused tests
  - ambiguous bare call 与 empty overload set 至少在 helper/unit 层被稳定锚定
  - subscript + callable integration cases 具备正反两面 coverage

**推荐 targeted tests**：

- `FrontendExprTypeAnalyzerTest`
- `FrontendChainBindingAnalyzerTest`
- `FrontendChainReductionHelperTest`
- `FrontendChainHeadReceiverSupportTest`
- `FrontendSemanticAnalyzerFrameworkTest`
- 若 overload selection helper 被提取：
  - 新增 dedicated helper/unit test

建议至少补齐以下 focused case matrix：

- subscript/container：
  - `items[0]`
  - `typed_items[i]`
  - `dict["name"]`
  - `self.items[0].length`
  - `consume(items[0])`
  - bad key type / non-container receiver / keyed-but-unsupported route
- assignment：
  - `value = 1`
  - `self.hp = 1`
  - `items[0] = 1`
  - constant / method reference / `TYPE_META` / signal 等非法左值
  - compound assignment（`+=` / `-=` / ...）明确 `UNSUPPORTED`
  - assignment-success root 不发 discarded warning
  - assignment 被放到 value-required 位置时 fail-closed
- shared expression semantic support：
  - expr analyzer 与 chain analyzer 对 bare callable / bare call / subscript / assignment 的 status contract 保持一致
  - bridge 转换不引入第二套 status/publishedType 语义
- callable focused hardening：
  - `helper.bind(...)`
  - `self.helper.bind(...)`
  - `Worker.build.bind(...)`
  - blocked bare call（尤其 chain path 与真实返回类型合同）
  - ambiguous bare call
  - empty overload set
  - callable produced by `.bind(...)` 后再 direct invoke
  - `items[0].bind(...)` / `dict["cb"].call()`

**当前实施状态（2026-03-18）**：

- [x] H0 已完成（2026-03-18）：已提取 `FrontendExpressionSemanticSupport`，并接线到 `FrontendExprTypeAnalyzer` / `FrontendChainBindingAnalyzer`；两侧不再各自维护 bare callable / bare call / assignment / subscript / explicit-deferred 的重复局部语义实现。新增 `FrontendExpressionSemanticSupportTest`，并通过 `FrontendExpressionSemanticSupportTest, FrontendExprTypeAnalyzerTest, FrontendChainBindingAnalyzerTest, FrontendChainReductionHelperTest, FrontendChainStatusBridgeTest` 定向测试锚定合同。
- [x] H1 已完成（2026-03-18）：plain `SubscriptExpression` 现已对 `Array` / `Dictionary` / `Packed*Array` 发布真实 element/value type，对 `Variant` receiver 发布 `DYNAMIC(Variant)`，并对 bad key type、non-container receiver、keyed-builtin route 发布明确 `FAILED` / `UNSUPPORTED`；`AttributeSubscriptStep` 已进入真实 chain reduction，可继续 exact suffix；plain subscript 也已作为 chain-local dependency 的一等节点支撑 outer call exact resolution。当前阶段仍未新增独立 subscript side table，attribute-subscript 本身继续只依赖 reduction trace / `expressionTypes()` 完成最小闭环。
- [x] H1 范围说明已冻结：当前实现中的 strict container key/index 校验与 keyed builtin `UNSUPPORTED` 都是 MVP 的显式边界，而不是待 H1 内补齐的缺陷；文档必须继续按“container-family strict contract”表述，不得泛化成“已基本对齐 Godot subscript 语义”。
- [x] H2 已完成（2026-03-19）：已新增 `FrontendAssignmentSemanticSupport` 作为独立 assignment semantic 真源，并从 `FrontendExpressionSemanticSupport` 中移除 assignment；left-hand side 不再走 ordinary expression resolver，而是通过 dedicated assignment-target model 解析 bare identifier / attribute property / plain subscript / attribute-subscript。assignment-success root 现稳定发布 `RESOLVED(void)`，statement-position 不再误发 discarded warning；value-required nested assignment 会 fail-closed；illegal lvalue / assignability failure / compound assignment boundary 现具备明确 `FAILED` / `UNSUPPORTED` contract。`ScopeValue` 现额外承载 bare identifier direct-write 所需的最小 `writable` contract，且 bare identifier property 与 attribute-property 都统一复用 `PropertyDefAccessSupport` 解释 readonly metadata，避免对同一 property 分叉出不同 assignment 结论。当前阶段对 `attribute-subscript` 仍按 property value 上的 element mutation 建模，getter-only property 是否额外阻断该 aliasing route 暂留后续里程碑统一收口。已新增/扩展 `FrontendAssignmentSemanticSupportTest`、`FrontendExprTypeAnalyzerTest`、`ClassScopeResolutionTest`、`ClassScopeSignalResolutionTest`、`ClassRegistryScopeTest` 与 `ScopeProtocolTest`；通过 assignment / expr / scope 定向测试共同锚定 direct-write contract。
- [ ] H3 待实施：generic deferred bucket 仍过宽；除本里程碑明确要收口的节点外，remaining deferred node set 尚未显式拆分。
- [ ] H4 待实施：callable focused tests 仍缺 `.bind(...)`、blocked bare call 的 chain-path coverage、ambiguous bare call、empty overload set、callable direct invocation variants 与 subscript+callable integration cases；expr-path blocked bare call 已要求并锚定“保留 overload-selected 真实返回类型，而不是 `Callable`”合同。

## 6.9 阶段总体验收出口

第六部分整体完成时，应满足以下条件：

- `symbolBindings()`、`resolvedMembers()`、`resolvedCalls()`、`expressionTypes()` 四张表的职责边界清晰，互不篡位
- constructor route、static method route、static load route 三条语义路径已结构化分流，不再依赖临时分支拼装
- `BLOCKED` / `DEFERRED` / `DYNAMIC` / `FAILED` / `UNSUPPORTED` 的 published 语义、后缀传播和诊断数量都可测试、可预测
- `:=` 已能消费稳定 RHS typing 完成变量真实类型落地
- supported container family 上的 plain / attribute subscript 已具备稳定 typed semantics，可继续支撑 chain suffix 与 `:=` backfill
- assignment 已按 GDScript 语义收口为“成功但无 ordinary value”的 `RESOLVED(void)` 语义，且 statement-position 不误发 discarded warning
- bare `TYPE_META` 已按“static-route 头、非一等 value”合同收口，不再污染 ordinary value expression typing
- bare `TYPE_META` ordinary-value misuse 已有精确用户可见 error，且在 statement root、nested dependency 与 `:=` initializer 中都能稳定保留 `FAILED` provenance
- 链头 binding 未解析的 hard-fail 合同已统一：坏链头会发 error diagnostic，且在顶层、nested dependency 与 `:=` initializer 场景下都保持同向 provenance
- bare function-like symbol 在 value position 上已稳定 materialize 为 `Callable`，不再误报 unknown value binding
- expression statement 已有稳定的 discarded-result warning 合同：non-`void` resolved value 会告警，resolved `void` bare call 保持静默
- shared expression semantic support 已成为 chain/expr analyzer 的共同真源，不再维持两套局部 expression semantic 解析实现
- expression-only `FAILED` / `DEFERRED` / `UNSUPPORTED` 根节点不再只停留在 side table；它们要么拥有 expr-owned diagnostic，要么明确复用既有 upstream diagnostic provenance
- generic deferred bucket 已收窄为显式枚举的剩余延期节点集合，而不是默认黑箱
- callable 的高风险分支已有 focused tests 锚定：至少包含 `.bind(...)`、blocked bare call（保留真实返回类型而非 `Callable`）、direct-callable-invocation variants，以及 helper-level ambiguous/empty-overload coverage
- 整体设计仍保持“整体分层、局部交替”，未退化成 whole-module fixpoint

建议在整体出口前至少跑通以下 targeted tests 组合：

- `FrontendAnalysisDataTest`
- `FrontendSemanticAnalyzerFrameworkTest`
- `FrontendTopBindingAnalyzerTest`
- `ScopePropertyResolverTest`
- `ScopeSignalResolverTest`
- `ScopeMethodResolverTest`
- `FrontendChainHeadReceiverSupportTest`
- `FrontendChainReductionHelperTest`
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
5. 让 `FrontendExprTypeAnalyzer` 在稳定的 member/call 结果之上发布 `expressionTypes()`，并先完成 G 里程碑的 callable/type-meta/expr-owned diagnostics 收口。
6. 在继续扩张更多 expression node 之前，提取 shared expression semantic support，收口 H 里程碑中的 subscript/container typing、assignment 语义与 callable focused test matrix。

这条路线既与当前仓库的 side-table / resolver 合同一致，也与 Godot 当前“整体分阶段、局部 reduction” 的组织方式保持同向。
