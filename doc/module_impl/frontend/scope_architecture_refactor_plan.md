# Scope 架构与共享 Resolver 约定

> 本文档作为 `gd.script.gdcc.scope` 与 `gd.script.gdcc.frontend.scope` 的长期事实源，定义当前已冻结的协议、shared resolver 边界、Godot 对齐结论，以及 frontend 后续接入时必须遵守的约束。

## 文档状态

- 状态：事实源维护中（`Scope` / frontend scope chain / `ScopeTypeResolver` / `ScopeSignalResolver` / `FrontendScopeAnalyzer` / variable / top binding / chain binding / expr type / type check / compile-only gate 已落地）
- 更新时间：2026-03-23
- 适用范围：
  - `src/main/java/gd/script/gdcc/scope/**`
  - `src/main/java/gd/script/gdcc/scope/resolver/**`
  - `src/main/java/gd/script/gdcc/frontend/scope/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
  - `src/main/java/gd/script/gdcc/backend/c/gen/insn/BackendPropertyAccessResolver.java`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/scope_type_resolver_implementation.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/superclass_canonical_name_contract.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/module_impl/backend/load_store_property_implementation.md`
  - `doc/module_impl/backend/load_static_implementation.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_low_ir.md`
- 已吸收的后续事实源：
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/frontend/scope_type_resolver_implementation.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`

---

## 1. 背景与目标

当前仓库已经有 `gd.script.gdcc.scope` 包，但后续工程真正需要的不是“若干 metadata 接口 + `ClassRegistry` 注册表”的松散集合，而是一套可以被 frontend binder 与 backend adapter 同时依赖的稳定协议。

当前架构已经冻结的目标有两件：

1. 把原先埋在 backend `BackendMethodCallResolver` / `BackendPropertyAccessResolver` 中、未来前后端都要复用的成员 metadata 查找逻辑抽到 `scope` 包。
2. 基于 `gd.script.gdcc.scope.Scope` 协议，在 `gd.script.gdcc.frontend.scope` 包内建立 frontend 真正会实例化的 lexical scope chain，使 AST 语义分析可以按“class -> callable -> block”的层级显式建 scope，并通过 parent 链完成无 base 标识符解析。

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

`gd.script.gdcc.frontend.scope` 当前已经落地以下实现：

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

- 直接索引当前类 direct property / direct signal / direct method
- 真实 `FrontendScopeAnalyzer` 路径依赖 `ClassScope` 构造期的自动索引：analyzer 只需要显式发布 direct inner type-meta，不需要额外调用 `defineProperty(...)` / `defineSignal(...)`
- `FrontendClassSkeletonBuilder.buildDeclaredTypeScopes(...)` 属于更早的 type-only scaffold；那里的 `ClassScope` 仍会走同一构造逻辑，但因为 class shell 尚未填入成员，所以 value/function 视图保持为空是刻意行为
- inherited property / signal / method 只在 direct miss 时回退
- 类成员继承查找属于当前 class scope layer，不是额外 lexical parent
- class-local type-meta 只走 lexical namespace，不沿继承链扩散

当前 `CallableScope` / `BlockScope` 的事实：

- 默认继承 parent 的 type namespace
- 当前没有 function namespace 的本地注册能力
- 当前作用域对象已经提供 parameter / capture / local / const 的写入口，但 `FrontendScopeAnalyzer` 只负责建图，不负责预填充这些绑定
- 仍保留 `defineTypeMeta(...)` 级别的扩展余地，以承接 preload alias / const alias / local enum 等未来来源

当前仍未纳入 `Scope` 直接建模的内容：

- `self` 不作为隐式 `ScopeValue` 进入 lexical value namespace；当前由 top binding 直接发布 `SELF` binding，并由 chain receiver support 解析为当前类实例 receiver
- `self` 在 static context 与 property initializer 中的 fail-closed 边界已经落地；相关非法用法的用户可见 diagnostics 仍由 frontend binder 各阶段负责，而不是由 `Scope` 协议承载
- `signal` 的无 receiver 名称解析与 receiver-based metadata lookup 已纳入 `ClassScope` / `ScopeSignalResolver`。值读取 / `.emit` / `.connect` 与普通 executable function 中的 `await signal` 已闭环（见 `frontend_signal_support.md`、`frontend_await_implementation.md`）。

### 2.6 Shared resolver 已经成为前后端共享事实源

`scope` 包当前已经拥有四个正式 shared resolver / shared lookup helper：

- `ScopeTypeResolver`
- `ScopePropertyResolver`
- `ScopeMethodResolver`
- `ScopeSignalResolver`

#### `ScopeTypeResolver` 的边界

它当前负责基于 `Scope#resolveTypeMeta(...)` 做严格 declared-type 解析：

- bare type name 走 lexical `type-meta` namespace
- 顶层 `Array[T]` / `Dictionary[K, V]` 由 shared parser 负责结构解析
- nested structured container text 继续拒绝

它明确不处理：

- diagnostics 生产
- guessed object fallback
- class header `extends` 的专用 canonical binding 协议

当前与 `scope_type_resolver_implementation.md` 一致的已知差异如下：

- inner class type-meta 仍存在 `base -> outer` 优先级未与 Godot 完全对齐的问题
- class header `extends` 仍独立于 shared declared-type resolver，继续走 `FrontendSuperClassRef` / canonical contract

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

当前 `ResolveRestriction` 与 tri-state lookup 对 Godot 的对齐范围已覆盖：

- class const
- property
- method
- signal 的 unqualified shadowing / static-context blocked-hit 语义
- signal 的 receiver-based metadata lookup 事实

尚未纳入完整 parity 的内容：

- `self`
- `await signal` 与其它 coroutine / context-sensitive signal 语义
- 通过实例语法访问静态成员时的 frontend 绑定与诊断策略

这些问题必须在后续 frontend binder 阶段单独建模，而不能假装已经由 `Scope` 或 shared resolver 自动解决。

---

## 4. Frontend 接入约束

当前 frontend 主链已经稳定落地为：

1. `FrontendClassSkeletonBuilder.build(...)`
2. `FrontendScopeAnalyzer.analyze(...)`
3. `FrontendVariableAnalyzer.analyze(...)`
4. `FrontendTopBindingAnalyzer.analyze(...)`
5. `FrontendChainBindingAnalyzer.analyze(...)`
6. `FrontendExprTypeAnalyzer.analyze(...)`
7. `FrontendAnnotationUsageAnalyzer.analyze(...)`
8. `FrontendTypeCheckAnalyzer.analyze(...)`
9. `FrontendCompileCheckAnalyzer.analyze(...)`（仅 `analyzeForCompile(...)`）

shared scope / resolver 的职责边界必须服务于这条已发布主链，而不是让后续实现重新发明第二套 lookup 语义。

### 4.1 AST -> Scope 建图策略

当前推荐并冻结的建图策略：

- global root / lexical parent root -> `ClassRegistry`
- `SourceFile` -> 顶层脚本 `ClassScope`
- `ClassDeclaration` -> `ClassScope`
- `FunctionDeclaration` / `ConstructorDeclaration` / `LambdaExpression` -> `CallableScope`
- callable `body: Block` -> 独立 `BlockScope`
- 普通独立 `Block` -> `BlockScope`
- `IfStatement.body` / `ElifClause.body` / `elseBody` / `WhileStatement.body` / `ForStatement.body` -> 独立 `BlockScope`
- `MatchSection` -> branch `BlockScope`，`MatchSection.body` 复用所属 section scope
- `ClassDeclaration.body` 复用 owning `ClassScope`，不额外生成 `BlockScope`

AST 节点与 scope 的关联仍应由 side-table 维护，而不是把 AST 节点塞回 `Scope` 实现。

`FrontendScopeAnalyzer` 当前已经把上述建图结果发布到 `FrontendAnalysisData.scopesByAst()`；更细的 side-table 覆盖口径与 guard rail 以 `scope_analyzer_implementation.md` 为准。

### 4.2 语法上下文敏感解析顺序

当前 top binding、chain binding 与后续新增 analyzer 都必须按语法上下文选择 namespace：

- value position：优先 `resolveValue(...)`
- function/callable position：优先 `resolveFunctions(...)`
- type position：优先 `resolveTypeMeta(...)`

额外约束：

- 遇到同名 value/type symbol 时，由语法上下文决定 namespace，不做“猜哪个更像用户想要的绑定”
- 未限定类成员解析时，先构造 `ResolveRestriction`，再调用 restriction-aware `Scope` API
- 若 `Scope` 返回 `FOUND_BLOCKED`，必须直接形成“当前上下文非法访问该绑定”的语义结论，不能继续回退 outer/global

### 4.3 `TYPE_META` 的消费规则

当前 frontend 对 `TYPE_META` 的消费必须继续遵守以下边界：

- `TypeRef`、`CastExpression`、`TypeTestExpression` 与显式 type hint 的严格解析统一走 shared `ScopeTypeResolver`
- 对 bare type name，`ScopeTypeResolver` 最终仍应收口到 `resolveTypeMeta(...)`；对 `Array[T]` / `Dictionary[K, V]` 这类 strict container text，则由 `ScopeTypeResolver` 负责做顶层结构解析
- `ScopeTypeResolver` 的 optional `UnresolvedTypeMapper` 只保留给 compatibility caller；frontend binder/skeleton 这类 strict 位置必须继续使用 no-mapper overload
- `ClassRegistry#resolveTypeMetaHere(...)` 仍是 global scope 的 primitive，不应反向委托 shared resolver
- `ClassName.static_method(...)` 走 static method 路径
- `ClassName.new(...)` / builtin ctor / object ctor 走 constructor resolution / `construct_*`
- `EnumType.VALUE` / builtin constant / engine integer constant 走 `load_static`
- 禁止在 binder/type inference 中回退到宽松 `findType(...)`

### 4.4 当前必须继续保留 deferred / unsupported 的来源

若当前主链尚未完整接入以下来源，必须给出显式 deferred / unsupported 诊断，而不是静默忽略：

- preload class
- const-based type alias
- local enum
- 其他 parser 已接入但 frontend 仍未消费的 type-meta 来源

当前仍需继续保持 deferred 的 scope-prefill / binding 内容包括：

- block-local `const` prefill
- `match` pattern binding
- lambda capture 推导与 `CallableScope.defineCapture(...)` 的生产接线

这些内容应由独立 analyzer 或后续新增语义 phase 接手，而不是回写进 `FrontendScopeAnalyzer`。

### 4.5 signal 的当前冻结合同

`signal` 相关的 scope / resolver / frontend 接线当前已经冻结为以下事实：

- `signal foo(...)` 在 frontend 里首先属于值侧实例成员，而不是 function namespace。
- 无 receiver 的 `foo` 必须走 `resolveValue(...)`，并和 property 一样参与当前 `ClassScope` 层的 shadowing 与 restriction。
- 显式 receiver 的 `obj.foo` 若 `foo` 为 signal，必须走独立的 `ScopeSignalResolver`，不能伪装成 property 或 method resolver 的结果。
- frontend analyzer 不得在 metadata 缺失时猜测一个“动态 signal”；只有当 `Scope` 或 shared resolver 明确确认目标是 signal 时，后续阶段才允许把它发布为 `FrontendBindingKind.SIGNAL` 或相关 typed fact。
- 当前已落地的 signal 范围只覆盖：
  - skeleton signal metadata 收集
  - `ScopeValueKind.SIGNAL`
  - `FrontendBindingKind.SIGNAL`
  - `ClassScope` 的 unqualified signal lookup
  - `ScopeSignalResolver` 的 receiver-based metadata lookup
- 值读取、`.emit` / `.connect` / `.disconnect` 与 Object/self / builtin / static / utility Callable 已闭环（见 `frontend_signal_support.md`）。当前仍未闭环的 signal 语义只包括：
  - `await signal`
  - 其他 coroutine / context-sensitive signal 语义

signal 路径必须继续遵守当前 scope 架构的分层边界：

- `Scope` 负责无 receiver 名字绑定事实。
- shared resolver 负责显式 receiver 成员 metadata 查找事实。
- frontend analyzers 负责语法上下文分流、diagnostics 与 typed semantic result。

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
  - `ClassScopeSignalResolutionTest`
- Inner class / restriction follow-up：
  - `FrontendInnerClassScopeIsolationTest`
  - `FrontendNestedInnerClassScopeIsolationTest`
  - `FrontendStaticContextValueRestrictionTest`
  - `FrontendStaticContextFunctionRestrictionTest`
  - `FrontendStaticContextShadowingTest`
- Frontend scope-analyzer / analysis-data / phase-boundary：
  - `FrontendScopeAnalyzerTest`
  - `FrontendSemanticAnalyzerFrameworkTest`
  - `FrontendAnalysisDataTest`
  - `FrontendAstSideTableTest`
- Frontend variable / visible-value / binding / body semantics：
  - `FrontendVariableAnalyzerTest`
  - `FrontendVisibleValueResolverTest`
  - `FrontendTopBindingAnalyzerTest`
  - `FrontendChainBindingAnalyzerTest`
  - `FrontendExprTypeAnalyzerTest`
  - `FrontendTypeCheckAnalyzerTest`
  - `FrontendCompileCheckAnalyzerTest`
- Shared property resolver：
  - `ScopePropertyResolverTest`
  - `PropertyResolverParityTest`
- Shared method resolver：
  - `ScopeMethodResolverTest`
  - `MethodResolverParityTest`
- Shared signal resolver：
  - `ScopeSignalResolverTest`

---

## 6. 已知风险与后续检查项

### 6.1 不要把 `FOUND_BLOCKED` 重新退化回 `null` / 空列表语义

只要把 blocked hit 重新当成 miss，Godot-style shadowing 就会立即失真。

### 6.2 不要把 `type-meta` legality 提前塞回 lookup 阶段

`resolveTypeMeta(..., restriction)` 当前只是统一协议形状。若未来把 static access / constructor / `load_static` 的全部合法性都塞回 lookup，会再次制造 binding 阶段与消费阶段的职责漂移。

### 6.3 Inner class 规则与 Godot 外层成员可见性仍有差异

当前方案是工程化折中，不是最终 parity 终点。若未来目标转向更高 Godot 一致性，必须重新设计 parent/view 模型，而不是在现有 `ClassScope` 上继续堆条件分支。

### 6.4 `self` 与 signal await 后续维护边界

当前 restriction parity 不能宣称已经完整覆盖 Godot 的 static-context 语义。后续 analyzer / binder 工作仍需补齐；signal 的 scope/resolver 冻结合同见 §4.5，值读取与 `.emit`/`.connect` 见 `frontend_signal_support.md`。

- `SelfExpression`
- `await signal` / coroutine
- 相关错误文案与语法上下文差异

### 6.5 已完成：`ClassScope` missing-super failure policy 收敛

当前 `ClassScope` 在 missing super metadata 时 fail fast：

- 直接抛出 `ScopeLookupException`，把缺失 superclass metadata 视为 malformed metadata。
- 这样 unqualified value/function/signal lookup 不会再静默跌落到 outer/global bindings。
- 这与显式 property/signal resolver 当前对 `MISSING_SUPER_METADATA` 的 hard-failure 基线保持一致。

当前用于冻结该行为的 targeted tests：

- `ClassScopeResolutionTest`
- `ClassScopeSignalResolutionTest`
- `FrontendStaticContextValueRestrictionTest`
- `FrontendStaticContextFunctionRestrictionTest`

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

### 6.7 scope graph 已落地，variable prefill 已拆成独立 analyzer 合同

当前 `FrontendScopeAnalyzer` 仍只发布 lexical/container graph，不负责往 `CallableScope` / `BlockScope` 写入运行时前端绑定。

参数与普通局部变量的当前实现合同，详见：

- `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`

frontend binder 侧的 declaration-order 可见性修正层，详见：

- `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
- `doc/module_impl/frontend/frontend_top_binding_analyzer_implementation.md`

当前已经冻结并落地的 variable-phase 边界包括：

- function / constructor parameter -> `CallableScope`
- function / constructor body 与 supported nested block 中的 ordinary local `var` -> `BlockScope`
- `ForStatement` iterator 与 for body ordinary local -> `FOR_BODY` scope；iterator declaration identity 与 fallback baseline 已冻结
- same-callable parameter/local shadowing 在 variable phase 直接诊断并拒绝写入
- 参数默认值、`match`、block-local `const` 继续 deferred；已记录 lambda 的 param / local / capture inventory 已转正（见 `frontend_lambda_implementation.md`）

后续 binder phase 在接线前或接线过程中仍需要冻结：

- 同一 callable 内的 parameter/local/capture shadowing 规则
- 参数默认值的可见性顺序
- ordinary local `var` initializer 已按 `FrontendVisibleValueResolver` 合同进入当前支持面，不再作为 binder 的前置阻塞项
- for iteration planning 与 iterator exact slot refinement
- `match` pattern binding 在 guard/body 中的可见性
- capture-backed live-slot alias 是否进入 publication surface（当前仍不开放）

同时不要把这部分职责回流到 `FrontendScopeAnalyzer`，否则会破坏当前 `scope_analyzer_implementation.md` 已冻结的 phase 边界。

---

## 7. 参考事实源

### 7.1 本仓库

- `src/main/java/gd/script/gdcc/scope/Scope.java`
- `src/main/java/gd/script/gdcc/scope/ResolveRestriction.java`
- `src/main/java/gd/script/gdcc/scope/ScopeLookupStatus.java`
- `src/main/java/gd/script/gdcc/scope/ScopeLookupResult.java`
- `src/main/java/gd/script/gdcc/scope/ClassRegistry.java`
- `src/main/java/gd/script/gdcc/scope/ScopeTypeMeta.java`
- `src/main/java/gd/script/gdcc/scope/ScopeTypeMetaKind.java`
- `src/main/java/gd/script/gdcc/scope/resolver/ScopeTypeResolver.java`
- `src/main/java/gd/script/gdcc/scope/resolver/ScopePropertyResolver.java`
- `src/main/java/gd/script/gdcc/scope/resolver/ScopeMethodResolver.java`
- `src/main/java/gd/script/gdcc/scope/resolver/ScopeSignalResolver.java`
- `src/main/java/gd/script/gdcc/frontend/scope/AbstractFrontendScope.java`
- `src/main/java/gd/script/gdcc/frontend/scope/ClassScope.java`
- `src/main/java/gd/script/gdcc/frontend/scope/CallableScope.java`
- `src/main/java/gd/script/gdcc/frontend/scope/BlockScope.java`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendAnalysisData.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendScopeAnalyzer.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/BackendPropertyAccessResolver.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- `doc/module_impl/frontend/scope_analyzer_implementation.md`
- `doc/module_impl/frontend/scope_type_resolver_implementation.md`
- `doc/module_impl/frontend/diagnostic_manager.md`
- `doc/analysis/frontend_semantic_analyzer_research_report.md`

### 7.2 Godot 参考

- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L320-L344`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4160-L4251`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4363-L4524`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4458-L4484`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4523-L4549`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/tests/scripts/runtime/features/await_signal_with_parameters.gd`
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/tests/scripts/analyzer/features/static_non_static_access.gd`
