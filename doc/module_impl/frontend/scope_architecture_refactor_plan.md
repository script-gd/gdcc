# Scope 架构与共享 Resolver 重构计划

> 本文档作为 `dev.superice.gdcc.scope` 与 `dev.superice.gdcc.frontend.scope` 的长期事实源，定义当前已冻结的协议、shared resolver 边界、Godot 对齐结论，以及 frontend 后续接入时必须遵守的约束。

## 文档状态

- 状态：事实源维护中（`Scope` / frontend scope chain / shared resolver 已落地，signal S0/S2 已落地，frontend binder 接入待实施）
- 更新时间：2026-03-09
- 适用范围：
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/resolver/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendPropertyAccessResolver.java`
  - 后续 `src/main/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/frontend/frontend_implementation_plan.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/module_impl/call_method_implementation.md`
  - `doc/module_impl/load_store_property_implementation.md`
  - `doc/module_impl/backend/load_static_implementation.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_low_ir.md`
- 已归并文档：
  - `doc/module_impl/frontend/phase4_inner_class_and_restriction_followup_plan.md`

---

## 1. 背景与目标

当前仓库已经有 `dev.superice.gdcc.scope` 包，但后续工程真正需要的不是“若干 metadata 接口 + `ClassRegistry` 注册表”的松散集合，而是一套可以被 frontend binder 与 backend adapter 同时依赖的稳定协议。

本次重构已经冻结并正在继续推进的目标有两件：

1. 把原先埋在 backend `BackendMethodCallResolver` / `BackendPropertyAccessResolver` 中、未来前后端都要复用的成员 metadata 查找逻辑抽到 `scope` 包。
2. 基于 `dev.superice.gdcc.scope.Scope` 协议，在 `dev.superice.gdcc.frontend.scope` 包内建立 frontend 真正会实例化的 lexical scope chain，使 AST 语义分析可以按“class -> callable -> block”的层级显式建 scope，并通过 parent 链完成无 base 标识符解析。

从 Godot 当前 analyzer 可提炼出的总体形态仍然成立：

- 一条 lexical scope 链：block / function / class / global。
- 一套成员解析 helper：在 class metadata 或 receiver type 上做 method/property owner lookup。
- `Scope` 回答“无 base 的名字从哪里来”，shared resolver 回答“有 receiver 的成员从哪里来”；两者相关，但不能重新混成一个万能对象。

---

## 2. 当前已冻结的架构事实

### 2.1 `ClassRegistry` 是 global scope root

`ClassRegistry` 现在既是全局 metadata root，也是正式的 global scope root。

它当前稳定承载：

- builtin / engine / gdcc class 的注册、查询与 `checkAssignable(...)`
- singleton / global enum / utility function 的全局查询
- 严格 `type-meta` namespace 的全局入口
- frontend skeleton/interface 产物注入后的跨脚本可见性

它在 `Scope` 协议下的当前事实如下：

- `getParentScope()` 恒为 `null`
- `setParentScope(non-null)` 必须拒绝，保持 root invariant
- `resolveValueHere(...)` 处理 singleton / global enum
- `resolveFunctionsHere(...)` 处理 utility function
- `resolveTypeMetaHere(...)` 处理 builtin / engine / gdcc class、global enum type、strict typed container
- 兼容入口 `findType(...)`、`findSingletonType(...)`、`findGlobalEnum(...)`、`findUtilityFunctionSignature(...)` 继续保留，但后续 frontend binder 不应再把 `findType(...)` 当作最终语义判定器

### 2.2 `Scope` 协议已经从“lexical skeleton”升级为正式 binding protocol

`Scope` 当前是 restriction-aware lexical binding protocol，而不是仅供未来参考的骨架。

协议层已经冻结的事实：

- `Scope` 明确分离三套 namespace：
  - value：`resolveValue(...)`
  - function：`resolveFunctions(...)`
  - type-meta：`resolveTypeMeta(...)`
- 三套 namespace 彼此独立：
  - value lookup 采用最近命中优先
  - function lookup 采用最近非空层优先
  - type-meta lookup 采用严格、独立的 lexical type namespace
- `Scope` 的 found/miss 已不再靠 `null` 或空列表表达，而是统一通过 `ScopeLookupResult<T>` + `ScopeLookupStatus`
- tri-state lookup 已冻结为：
  - `FOUND_ALLOWED`
  - `FOUND_BLOCKED`
  - `NOT_FOUND`
- 只有 `NOT_FOUND` 允许继续递归到 parent
- `FOUND_ALLOWED` 与 `FOUND_BLOCKED` 都必须停止 lookup，确保当前层命中继续构成 shadowing
- `Scope` 实现继续只承载 metadata/lookup，不承载 AST 节点、backend-only 状态或 codegen 细节

### 2.3 `ResolveRestriction` 的当前语义边界

`ResolveRestriction` 已正式进入 `Scope` 方法签名，其职责是表达“当前上下文允许哪些未限定 class member 停止 lookup”，而不是承载诊断文本。

当前冻结的 restriction 语义：

- `ResolveRestriction.unrestricted()`：兼容旧调用方与 unrestricted 测试
- `ResolveRestriction.staticContext()` 允许无 base 命中：
  - class const
  - static property
  - static method
- `ResolveRestriction.instanceContext()` 允许无 base 命中：
  - class const
  - instance property / instance method
  - static property / static method
- 当前 restriction 只影响未限定 class-member 的 value/function lookup
- parameter / capture / block local / global root 当前都不受 static-vs-instance member restriction 影响
- frontend binder 仍负责把“为什么不合法”翻译成用户可见的 diagnostics

### 2.4 `type-meta` 当前采用“统一签名 + always-allowed”契约

为了统一三套 namespace 的调用形状，`resolveTypeMeta(..., restriction)` 也接受 `ResolveRestriction`，但当前协议已经明确冻结：

- 对现有 `ScopeTypeMetaKind` 集合，`type-meta` lookup 只允许返回 `FOUND_ALLOWED` / `NOT_FOUND`
- 当前 `type-meta` lookup 不产生 `FOUND_BLOCKED`
- `TYPE_META` 的后续合法性继续由 frontend binder 在消费阶段判断，而不是由 type lookup 本身承担
- 当前 binder/static analysis 需要处理的消费路径至少包括：
  - `TypeRef`
  - `CastExpression`
  - `TypeTestExpression`
  - static access
  - constructor resolution
  - `load_static`

这条约定不能被后续实现重新改写为“lookup 顺便完成全部 type legality 判断”，否则会再次把 binding 与消费阶段耦合回去。

### 2.5 Frontend lexical scope chain 已落地

`dev.superice.gdcc.frontend.scope` 当前已经落地以下实现：

- `AbstractFrontendScope`
- `ClassScope`
- `CallableScope`
- `BlockScope`

当前冻结的链式语义如下：

- value namespace：
  - `BlockScope` local
  - `CallableScope` parameter
  - `CallableScope` capture
  - `ClassScope` member
  - global scope
- function namespace：
  - 继续使用“最近非空层优先”
  - 同名 overload set 在当前层若存在，即使被 restriction 全部阻止，也必须返回 `FOUND_BLOCKED`
- type-meta namespace：
  - 最近命中优先
  - 只沿 type namespace 的 lexical parent 链递归
  - 不污染 value/function namespace

当前 `ClassScope` 的事实：

- 直接索引当前类 direct property / direct method
- inherited property / method 只在 direct miss 时回退
- 类成员继承查找属于当前 class scope layer，不是额外 lexical parent
- class-local type-meta 只走 lexical namespace，不沿继承链扩散

当前 `CallableScope` / `BlockScope` 的事实：

- 默认继承 parent 的 type namespace
- 当前没有 function namespace 的本地注册能力
- 仍保留 `defineTypeMeta(...)` 级别的扩展余地，以承接 preload alias / const alias / local enum 等未来来源

当前仍未纳入 `Scope` 直接建模的内容：

- `self` 不是隐式 `ScopeValue`
- signal 不是现成的 `ScopeValue` / `FunctionDef`
- 这两者的完整语义和 diagnostics 仍由 frontend binder 的后续阶段负责

### 2.6 Shared resolver 已经成为前后端共享事实源

`scope` 包当前已经拥有两个正式 shared resolver：

- `ScopePropertyResolver`
- `ScopeMethodResolver`

#### `ScopePropertyResolver` 的边界

它当前只负责 instance-style property metadata lookup：

- known object receiver：沿 class metadata + inheritance 解析 owner
- builtin receiver：走 builtin class metadata

它明确不处理：

- `TypeMeta` 驱动的静态常量或 enum 项读取
- builtin constant / engine integer constant
- frontend static binding 与 `load_static` 决策

它当前暴露的失败协议有明确区分：

- object receiver metadata 缺失：`MetadataUnknown`
- hierarchy malformed：`Failed`
- property 不存在：`Failed`
- builtin class 缺失或 builtin property 缺失：`Failed`

这意味着 shared resolver 只回答“metadata lookup 的事实”，至于 caller 是否在 `PROPERTY_MISSING` 时选择 fail-fast 或动态路径，仍是 caller 的策略责任。

#### `ScopeMethodResolver` 的边界

它当前同时支持：

- instance receiver：基于 `GdType`
- static/type-meta receiver：基于 `ScopeTypeMeta`

它明确不处理：

- constructor route，例如 `ClassName.new(...)`
- `EnumType.VALUE` / builtin constant 读取
- frontend LIR 形态选择
- backend C 发射细节

它当前冻结的候选筛选与排序基线如下：

- 先做 applicability 过滤：
  - 参数数量
  - 默认参数兼容
  - vararg 兼容
  - `ClassRegistry#checkAssignable(...)` 类型兼容
- 再按 owner distance 优先
- instance call 路径上，实例方法优先于 static 方法
- non-vararg 优先于 vararg
- instance object receiver 若同优先级最佳候选仍歧义，则允许 `OBJECT_DYNAMIC`
- `Variant` receiver 允许 `VARIANT_DYNAMIC`
- builtin/static receiver 的歧义或不适用仍属于 hard failure，而不是动态兜底

shared resolver 与 adapter 的边界已经冻结为：

- shared resolver：只输出 metadata 事实、fallback reason 与 hard failure
- backend adapter：继续负责 `LirVariable` 校验、receiver 渲染、pack/unpack、default literal 物化、最终 C 发射
- frontend binder：后续负责语法上下文分流、diagnostics 与 typed semantic result

---

## 3. Godot 对齐事实与已知差异

### 3.1 被 restriction 阻止的当前层命中仍然构成 shadowing

这条结论已经作为 `ScopeLookupStatus.FOUND_BLOCKED` 的设计前提冻结下来。

Godot 当前行为可概括为：

- 标识符解析顺序先 local/member，再 global
- `static_context` 检查发生在当前层命中之后
- 当前层/local/member 一旦命中，即使随后因 static context 非法而报错，也不会继续回退 outer/global 的同名绑定

对 GDCC 的直接约束是：

- `restriction` 绝不能被建模为“当前层不合法就当成 miss”
- lookup 协议必须能区分：
  - `FOUND_ALLOWED`
  - `FOUND_BLOCKED`
  - `NOT_FOUND`

相关 Godot 事实源：

- `reduce_identifier(...)` 先 local/member，再 global：
  - `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4363-L4524`
- `static_context` 检查发生在命中之后，且随后直接返回：
  - `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4458-L4484`
  - `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4523-L4549`
- `reduce_identifier_from_base(...)` 命中类成员后不会退回 global：
  - `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4160-L4251`

### 3.2 Inner class 的当前规则是有意识的工程化差异

当前 GDCC 在 inner class 上已经冻结以下规则：

- `type-meta` namespace：
  - 继续沿完整 lexical parent 链查找
  - inner class 仍可看见 outer class 提供的 inner class / class enum / 其他 type-meta
- `value/function` namespace：
  - `ClassScope` 在 lexical miss 时跳过连续 outer `ClassScope` ancestor
  - inner class 不会无 base 继承 outer class 的 property / const / function

这条规则当前是通过 `ClassScope` 在 value/function lookup 时显式寻找“第一个非 `ClassScope` ancestor”实现的，而不是通过引入双 parent 或 namespace-specific parent 改写整条 parent 链。

这与 Godot 当前 outer-class member visibility 并不完全一致。Godot 当前会把 outer class 纳入 current-scope class chain，只是顺位晚于 base type：

- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L320-L344`

因此必须长期保留以下表述，不能在后续文档里被弱化：

- 这是 GDCC 当前的工程决策，不是 Godot 当前实现的 1:1 平移
- 若未来要进一步靠拢 Godot，需要重新评估：
  - namespace-specific parent
  - scoped view
  - outer class / base type / current class 三者的更细粒度查找顺序

### 3.3 当前 Godot 对齐范围仍有限

当前 `ResolveRestriction` 与 tri-state lookup 对 Godot 的对齐范围只覆盖：

- class const
- property
- method

尚未纳入完整 parity 的内容：

- `self`
- signal
- 通过实例语法访问静态成员时的 frontend 绑定与诊断策略

这些问题必须在后续 frontend binder 阶段单独建模，而不能假装已经由 `Scope` 或 shared resolver 自动解决。

---

## 4. Frontend 后续接入约束

Phase 7+ 的 frontend 接入必须建立在上述事实之上，不能重新发明第二套 lookup 语义。

### 4.1 AST -> Scope 建图策略

当前推荐并冻结的建图策略：

- module/root -> `ClassRegistry`
- class -> `ClassScope`
- function / constructor / lambda -> `CallableScope`
- block / if / while / match branch -> `BlockScope`

AST 节点与 scope 的关联仍应由 side-table 维护，而不是把 AST 节点塞回 `Scope` 实现。

### 4.2 Binder 的上下文敏感解析顺序

后续 binder 必须按语法上下文选择 namespace：

- value position：优先 `resolveValue(...)`
- function/callable position：优先 `resolveFunctions(...)`
- type position：优先 `resolveTypeMeta(...)`

额外约束：

- 遇到同名 value/type symbol 时，由语法上下文决定 namespace，不做“猜哪个更像用户想要的绑定”
- 未限定类成员解析时，先构造 `ResolveRestriction`，再调用 restriction-aware `Scope` API
- 若 `Scope` 返回 `FOUND_BLOCKED`，必须直接形成“当前上下文非法访问该绑定”的语义结论，不能继续回退 outer/global

### 4.3 `TYPE_META` 的消费规则

后续 frontend 接入 `TYPE_META` 时必须继续遵守以下边界：

- `TypeRef`、`CastExpression`、`TypeTestExpression`、显式 type hint 统一走 `resolveTypeMeta(...)`
- `ClassName.static_method(...)` 走 static method 路径
- `ClassName.new(...)` / builtin ctor / object ctor 走 constructor resolution / `construct_*`
- `EnumType.VALUE` / builtin constant / engine integer constant 走 `load_static`
- 禁止在 binder/type inference 中回退到宽松 `findType(...)`

### 4.4 当前建议保留 deferred 的来源

若 Phase 7+ 尚未完整接入以下来源，应给出显式 deferred / unsupported 诊断，而不是静默忽略：

- preload class
- const-based type alias
- local enum
- 其他 parser 已接入但 frontend 仍未消费的 type-meta 来源

### 4.5 signal 解析实施计划（分阶段）

本节给出 `signal` 接入现有 `Scope` / shared resolver / frontend binder 路径的冻结实施计划。该计划的目标不是一次性补齐 Godot analyzer 的全部 signal 语义，而是在不破坏当前 scope 架构分层的前提下，先建立一条稳定、可测试、可逐步扩展的最小闭环。

#### 4.5.1 设计结论（本计划冻结）

- `signal foo(...)` 在语义上首先是“值侧、实例成员”的绑定，不应被塞入 function namespace。
- 无 base 标识符形式的 `foo`（其中 `foo` 为 signal）必须走 `resolveValue(...)`，并与 property 一样参与当前 `ClassScope` 层的 shadowing 与 restriction。
- 显式 receiver 形式的 `obj.foo` 若 `foo` 为 signal，不应复用 `ScopePropertyResolver` 或 `ScopeMethodResolver` 的返回类型硬塞进去，而应新增独立的 `ScopeSignalResolver`，保持 resolver 职责清晰。
- frontend binder 不得在 metadata 缺失时“猜测一个动态 signal”。只有当 scope / resolver 明确确认目标是 signal 时，后续阶段才允许把它绑定为 `FrontendBindingKind.SIGNAL`。
- 当前阶段的 `signal` 计划只覆盖 scope / resolver / binder 侧解析，不纳入 signal 值 lowering，也不处理 `await` / coroutine 语义。
- `signal` 的接入必须遵守当前 scope 架构的分层边界：
  - `Scope` 负责无 receiver 名字绑定事实。
  - shared resolver 负责显式 receiver 成员 metadata 查找事实。
  - frontend binder 负责语法上下文分流、诊断、typed semantic result。

#### 4.5.2 阶段 S0：模型补位与事实冻结

**目标**：先把类型与 binding 元数据补齐，使后续每一层都能显式表达“这是 signal”。

**实施细则**：

- 在 `ScopeValueKind` 中新增 `SIGNAL`，并明确其语义是“值侧的 signal 绑定”，不是 function namespace 的别名。
- 在 `FrontendBindingKind` 中新增 `SIGNAL`，用于 frontend binder 最终产物中的符号分类，避免后续再把 signal 临时伪装成 `PROPERTY` 或 `UNKNOWN`。
- 收敛 `GdSignalType` 的数据形状：
  - 改为不可变实现，避免 public mutable field 导致的分析期状态漂移。
  - 至少稳定承载 signal 参数签名；如对 binder 调试或诊断有帮助，可同时保留 owner 或 signal 名等信息，但不要把 AST 节点直接塞进类型对象。
- 为测试辅助设施补齐 signal 构造入口，例如在 frontend scope / resolver 测试支持类中新增 `createSignal(...)` 与支持 `signals` 参数的 `createClass(...)` 重载，避免后续测试被迫手写大量样板。
- 补充文档交叉引用：在 frontend 实施计划、语义分析调研报告中标记 signal 计划已冻结，后续实现应以本文为准。

**验收标准**：

- `ScopeValueKind.SIGNAL` 与 `FrontendBindingKind.SIGNAL` 均已落位，且命名、注释与现有 `Scope` 术语保持一致。
- `GdSignalType` 不再依赖外部可变字段表达参数列表。
- 现有 skeleton / metadata 相关测试保持通过，至少不回归 `FrontendClassSkeletonBuilder` 已具备的 signal 收集能力。
- 新增的测试辅助 API 足以支持 direct / inherited / engine / gdcc class signal 场景，不需要在每个测试里重复拼装底层对象。

**当前状态（2026-03-09）**：

- [x] 已完成 `ScopeValueKind.SIGNAL`：明确冻结为“值侧的 signal 绑定”，并补充与 `ScopeValue` / `GdSignalType` 配套的注释。
- [x] 已完成 `FrontendBindingKind.SIGNAL`：为后续 frontend binder 接入保留稳定 binding kind，不再需要把 signal 伪装成 `PROPERTY` 或 `UNKNOWN`。
- [x] 已完成 `GdSignalType` 收敛：改为不可变实现，稳定承载 signal 参数签名，并通过单元测试锚定复制/只读语义。
- [x] 已完成测试支撑补齐：`FrontendScopeTestSupport` 新增 `createSignal(...)`、支持 `signals` 参数的 `createClass(...)`、`createEngineSignal(...)`、`createEngineClass(...)` 与额外 registry 入口。
- [x] 已完成文档交叉同步：本计划与 `doc/analysis/frontend_semantic_analyzer_research_report.md` 已同步标记 signal 方案冻结与 S0/S1 落地状态。

#### 4.5.3 阶段 S1：`ClassScope` 接入 unqualified signal 解析

**目标**：让类体内无 receiver 的 `my_signal` 能按当前 scope 协议稳定命中，并正确参与 shadowing / restriction。

**实施细则**：

- 在 `ClassScope` 的 direct value 索引中纳入当前类声明的 signals，使其与 property 一样成为本层 value namespace 的候选。
- direct signal 与 inherited signal 的查找策略保持和 property 一致：
  - 当前类 direct 成员
  - direct miss 后再沿继承链查找 inherited signal。
  - inherited signal 属于当前 `ClassScope` 层的成员视图，不应通过额外 lexical parent 伪造。
- signal 的 `ScopeValue` 载荷应能回溯到 `SignalDef`，并暴露 `GdSignalType` 作为值类型。
- static context 下，无 receiver 访问实例 signal 时必须返回 `FOUND_BLOCKED`，而不是 `NOT_FOUND`，确保它继续构成 shadowing，不能错误回退到 global singleton / global enum / utility function / type-meta 同名符号。
- 第一批实现可先复用现有“实例 value member 在 static context 被阻止”的 restriction 语义；只有当 signal 与 property 的合法性边界出现实质分叉时，再评估是否需要为 `ResolveRestriction` 增加独立的 `allowSignals` 维度。
- 保持 `self` 仍不作为隐式 `ScopeValue` 注入；本阶段只处理 signal 作为 class member 的无 receiver 名称解析。

**验收标准**：

- 在 instance context 中，`my_signal` 能通过 `ClassScope.resolveValue(...)` 直接命中。
- 继承来的 signal 在 direct miss 时可见，且 direct declaration 仍优先于 inherited declaration。
- 在 static context 中命中 signal 时返回 `FOUND_BLOCKED`，并验证 lookup 不会继续回退 global scope。
- 对于 metadata 缺失的 superclass，当前 `ClassScope` 仍保持既有轻量策略，不因为 signal 接入而改变 failure policy。

**当前状态（2026-03-09）**：

- [x] 已完成 direct signal 索引：`ClassScope` 的 direct value 索引已纳入当前类声明的 signals，信号现在与 property 一样参与当前层 value namespace 查找。
- [x] 已完成 inherited signal 查找：direct miss 后会沿继承链查找 inherited signal，且 inherited signal 仍属于当前 `ClassScope` 层视图，没有通过伪造 lexical parent 接入。
- [x] 已完成 `SignalDef` / `GdSignalType` 载荷对接：signal 的 `ScopeValue` 现在稳定暴露 `SIGNAL` kind、`SignalDef` declaration 与 `GdSignalType` 参数签名。
- [x] 已完成 static-context blocked hit 语义：无 receiver 访问实例 signal 时返回 `FOUND_BLOCKED`，并通过 targeted tests 锚定不会错误回退到 global singleton / global enum 同名绑定。
- [x] 已完成 failure policy 保持：metadata 缺失 superclass 仍维持既有轻量 `NOT_FOUND` 策略，inheritance cycle 仍按既有 hard failure 路径暴露。

#### 4.5.4 阶段 S2：新增显式 receiver 的 `ScopeSignalResolver`

**目标**：把 `obj.some_signal` / `self.some_signal` / `ClassRef` 相关的 signal 成员查找从 property/method resolver 中分离出来，形成独立、可复用的 metadata 事实源。

**实施细则**：

- 新增 `ScopeSignalResolver`，输入保持与其他 shared resolver 风格一致：
  - object receiver 走 class metadata + inheritance。
  - builtin / 其他非对象 receiver 不进入单独 signal owner lookup，实例入口直接返回结构化解析错误。
  - `Variant` / 动态对象 / metadata 缺失对象与已知 hard failure 必须区分。
- 新增独立结果模型，例如 `ScopeResolvedSignal`，其最少需要区分：
  - `Resolved`：成功定位到 signal owner 与 `SignalDef`。
  - `MetadataUnknown`：receiver 的 class metadata 当前不可得，caller 可自行决定 deferred / dynamic / error。
  - `Failed`：确认无法解析 signal。
- `Failed` 的 reason 至少建议冻结为：
  - `SIGNAL_MISSING`
  - `MISSING_SUPER_METADATA`
  - `INHERITANCE_CYCLE`
  - 如 builtin 静态路径后续需要，还可补 `UNSUPPORTED_RECEIVER_KIND` 或同等级失败原因。
- 明确与 `ScopePropertyResolver` 的边界：
  - property resolver 不负责 signal owner lookup。
  - signal resolver 不负责 property fallback。
  - binder 在 value member access 上自行决定解析顺序与 fallback policy，而不是在 resolver 内部混写成“万能成员查找器”。
- 与 `ScopeMethodResolver` 的边界同样要冻结：signal 解析只回答“成员是不是 signal、owner 是谁、签名是什么”，不直接处理 `.emit(...)` 的重载匹配或 builtin 方法调用判定。

**验收标准**：

- 对 gdcc class、engine class 的 direct / inherited signal 能正确得到 `Resolved` 结果。
- 对 builtin / 其他非对象 receiver，实例入口会直接得到结构化解析错误（当前为 `Failed(UNSUPPORTED_RECEIVER_KIND)`），而不是进入额外的 signal metadata 查找分支。
- 对缺失 metadata 的 receiver 能稳定返回 `MetadataUnknown`，而不是与 `SIGNAL_MISSING` 混淆。
- 对继承环等 malformed metadata 能稳定暴露 `Failed(INHERITANCE_CYCLE)`。
- 新 resolver 的失败语义与现有 property/method resolver 风格一致，但职责边界不发生重叠。

**当前状态（2026-03-09）**：

- [x] 已完成 `ScopeSignalResolver`：新增 `resolveInstanceSignal(...)`、`resolveObjectSignal(...)` 两个入口；实例入口对 object receiver 走 metadata lookup，对 builtin / 其他非对象 receiver 直接返回解析错误，不再保留额外的 builtin signal resolver 分支。
- [x] 已完成 `ScopeResolvedSignal`：稳定暴露 `ownerKind`、`ownerClass`、`SignalDef` 与派生 `signalType()`，让 frontend/backend 在 receiver-based signal 命中后可以共享同一份 owner + signature 事实。
- [x] 已完成 failure protocol 冻结：`ScopeSignalResolver` 现在稳定区分 `Resolved`、`MetadataUnknown`、`Failed`，并覆盖 `SIGNAL_MISSING`、`MISSING_SUPER_METADATA`、`INHERITANCE_CYCLE`、`UNSUPPORTED_RECEIVER_KIND` 等失败原因。
- [x] 已完成 builtin / 非对象 receiver 语义收敛：依据 Godot “Signal 属于 Object instance” 的语义，builtin 与其他非对象 receiver 不再进入 signal metadata 查找，而是在实例入口直接返回结构化解析错误。
- [x] 已完成 targeted tests：`ScopeSignalResolverTest` 已覆盖 gdcc / engine 的正向 signal 解析，以及 builtin direct-failure、metadata unknown、signal missing、missing super metadata、inheritance cycle、variant receiver 等反向场景，并与现有 signal/property/method 相关 targeted tests 一并通过。

#### 4.5.5 阶段 S3：frontend binder 接入 signal binding

**目标**：把 scope/resolver 的 signal 事实接入前端绑定结果，使 signal 在标识符、成员访问、lambda/self 使用分析中都能被稳定消费。

**实施细则**：

- 在 frontend binder / analysis result 中接入 signal 绑定产物，至少覆盖：
  - `symbolBindings`
  - `resolvedMembers`
  - `expressionTypes`
  - 以及后续语义阶段需要读取的“成员解析结果”载体
- 无 base 标识符的建议解析顺序冻结为：
  - local / parameter / capture
  - class property / constant / function / signal
  - singleton / global enum / utility function / type-meta
  - 若未来 `self` 或更多来源接入，必须显式修订本文档，而不是在 binder 中偷偷插入分支。
- 显式 receiver 的 value member access 建议顺序冻结为：
  - property
  - signal
  - 动态值路径或 deferred / unknown metadata 处理
  - 不允许因为 property miss 就直接把同名成员当成“任意动态属性”吞掉 signal。
- static context 下访问 signal 的诊断应与 Godot 方向保持一致：
  - 先命中当前类 signal。
  - 再基于 restriction / static-context 规则给出错误。
  - 不继续外溯寻找同名 global 绑定。
- lambda / inner callable 中访问外层类的 signal，应记为 `use-self` 风格依赖，而不是当作独立 capture 值复制到闭包环境；这样才能与 Godot 对 `self` / signal 的实例绑定语义保持一致。
- frontend binder 仍不负责 signal 的运行时行为（例如 connect 生命周期），只负责语义归类、错误分流和类型产出。

**验收标准**：

- `my_signal`、`self.my_signal`、`obj.some_signal` 三类表达式都能得到稳定且可区分的 signal binding。
- static context 下的 signal 访问产生明确错误，且不会误绑定到 global 同名符号。
- lambda 内访问 signal 不会被错误记录为普通局部 capture。
- analysis result 中与 signal 相关的 binding kind、member resolution、expression type 三者保持一致，不出现“绑定是 signal、类型却是 unknown/property”的漂移。

#### 4.5.6 阶段 S4：测试矩阵、文档收敛与交付标准

**目标**：为 signal 接入建立完整的回归面，确保后续修改不会重新把 signal 打回“特殊分支”或“临时绕过”的状态。

**实施细则**：

- `scope` 层 targeted tests 至少覆盖：
  - direct signal 命中
  - inherited signal 命中
  - static blocked hit 仍然 shadow outer/global
  - direct signal 与 property / global 同名时的优先级
- `resolver` 层 targeted tests 至少覆盖：
  - direct / inherited signal owner lookup
  - `MetadataUnknown`
  - `MISSING_SUPER_METADATA`
  - `INHERITANCE_CYCLE`
  - builtin / engine / gdcc class 的代表性样例
- `frontend sema` 层 targeted tests 至少覆盖：
  - `my_signal`
  - `self.my_signal`
  - `obj.some_signal`
  - static-context error
  - lambda use-self
- 文档同步要求：
  - 本文档保持为 signal scope 方案的事实源。
  - `doc/analysis/frontend_semantic_analyzer_research_report.md` 需要与本计划保持一致。

**验收标准**：

- scope / resolver / binder 三层均有 targeted tests，且测试命名能够直接反映 signal 语义边界。
- 文档、测试、代码三处对 signal 设计结论表述一致，不存在一处把 signal 当 property、另一处当 function 的分裂实现。
- 代码评审时可以仅凭本文档与对应测试名称定位 signal 接入范围，而无需重新逆向推断实现意图。

---

## 5. 当前行为冻结测试

以下测试当前承担“事实冻结”角色，后续改动若改变这些测试覆盖的行为，应先更新本文档中的迁移说明与边界约定。

- `Scope` 协议与 global scope：
  - `ScopeProtocolTest`
  - `ClassRegistryScopeTest`
  - `ClassRegistryTypeMetaTest`
- Frontend scope chain：
  - `ScopeChainTest`
  - `ScopeTypeMetaChainTest`
  - `ScopeCaptureShapeTest`
  - `ClassScopeResolutionTest`
- Inner class / restriction follow-up：
  - `FrontendInnerClassScopeIsolationTest`
  - `FrontendNestedInnerClassScopeIsolationTest`
  - `FrontendStaticContextValueRestrictionTest`
  - `FrontendStaticContextFunctionRestrictionTest`
  - `FrontendStaticContextShadowingTest`
- Shared property resolver：
  - `ScopePropertyResolverTest`
  - `PropertyResolverParityTest`
- Shared method resolver：
  - `ScopeMethodResolverTest`
  - `MethodResolverParityTest`

---

## 6. 已知风险与后续检查项

### 6.1 不要把 `FOUND_BLOCKED` 重新退化回 `null` / 空列表语义

只要把 blocked hit 重新当成 miss，Godot-style shadowing 就会立即失真。

### 6.2 不要把 `type-meta` legality 提前塞回 lookup 阶段

`resolveTypeMeta(..., restriction)` 当前只是统一协议形状。若未来把 static access / constructor / `load_static` 的全部合法性都塞回 lookup，会再次制造 binding 阶段与消费阶段的职责漂移。

### 6.3 Inner class 规则与 Godot 外层成员可见性仍有差异

当前方案是工程化折中，不是最终 parity 终点。若未来目标转向更高 Godot 一致性，必须重新设计 parent/view 模型，而不是在现有 `ClassScope` 上继续堆条件分支。

### 6.4 `self` / signal 仍未纳入当前 `Scope` 协议

当前 restriction parity 不能宣称已经完整覆盖 Godot 的 static-context 语义。后续 binder 必须补齐；其中 `signal` 的具体实施步骤、边界与验收口径见 `4.5 signal 解析实施计划（分阶段）`。

- `SelfExpression`
- signal 绑定
- 相关错误文案与语法上下文差异

### 6.5 `ClassScope` 与显式 property resolver 的 failure policy 仍不完全一致

当前 `ClassScope` 对 missing super metadata 采取“停止继承链并返回 miss”的轻量策略，而 `ScopePropertyResolver` 对显式 property access 会把 `MISSING_SUPER_METADATA` 作为 hard failure 暴露给 caller。后续 frontend 不应误以为这两条路径已经共享完全一致的失败语义。

### 6.6 当前 method/property resolver 仍有后续补强空间

当前 shared resolver 已统一 backend 基线下的事实源，但仍有明确的后续检查项：

- 为 static/type-meta receiver 补齐更多负面场景测试：
  - pseudo type 拒绝
  - global enum 拒绝
  - static receiver metadata 缺失
  - builtin static lookup 边界
  - constructor route 与 static method 路径分流
- 评估 builtin property lookup 是否也需要与 method resolver 一样做 typed container 的 receiver 名规范化，例如 `Array[T] -> Array`、`Dictionary[K, V] -> Dictionary`
- 若 frontend 后续需要更细粒度的 overload specificity 或 diagnostics，不要误把当前“适用性过滤 + ownerDistance + instance 优先 + non-vararg 优先”基线当成最终闭包

---

## 7. 参考事实源

### 7.1 本仓库

- `src/main/java/dev/superice/gdcc/scope/Scope.java`
- `src/main/java/dev/superice/gdcc/scope/ResolveRestriction.java`
- `src/main/java/dev/superice/gdcc/scope/ScopeLookupStatus.java`
- `src/main/java/dev/superice/gdcc/scope/ScopeLookupResult.java`
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
- `src/main/java/dev/superice/gdcc/scope/ScopeTypeMeta.java`
- `src/main/java/dev/superice/gdcc/scope/ScopeTypeMetaKind.java`
- `src/main/java/dev/superice/gdcc/scope/resolver/ScopePropertyResolver.java`
- `src/main/java/dev/superice/gdcc/scope/resolver/ScopeMethodResolver.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/AbstractFrontendScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/ClassScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/CallableScope.java`
- `src/main/java/dev/superice/gdcc/frontend/scope/BlockScope.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendPropertyAccessResolver.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- `doc/module_impl/frontend/frontend_implementation_plan.md`
- `doc/analysis/frontend_semantic_analyzer_research_report.md`

### 7.2 Godot 参考

- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L320-L344`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4160-L4251`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4363-L4524`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4458-L4484`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4523-L4549`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/tests/scripts/runtime/features/await_signal_with_parameters.gd`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/tests/scripts/analyzer/features/static_non_static_access.gd`
