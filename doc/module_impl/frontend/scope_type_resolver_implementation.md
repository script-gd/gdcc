# ScopeTypeResolver 实现说明

> 本文档作为 `ScopeTypeResolver` 及其 frontend 接入方式的长期事实源，定义当前已落地的职责边界、严格/兼容解析分工、frontend 集成约束，以及后续工程仍需面对的已知差异。本文档替代原 `scope_type_resolver_migration_plan.md`。

## 文档状态

- 状态：事实源维护中（shared resolver、skeleton 接入、真实 scope graph 接入、compatibility mapper 与 caller-side remap 接入已落地）
- 更新时间：2026-03-25
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/resolver/**`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/scope/**`
- 关联文档：
  - `runtime_name_mapping_implementation.md`
  - `scope_architecture_refactor_plan.md`
  - `scope_analyzer_implementation.md`
  - `inner_class_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `diagnostic_manager.md`
- 明确非目标：
  - 不在此处实现完整 frontend binder/body
  - 不改变 `FrontendSemanticAnalyzer` 已发布的 phase 顺序，尤其不要打乱 skeleton 先于 scope analyzer 的边界
  - 不把 `DiagnosticManager` 注入 `ScopeTypeResolver`、`Scope` 或其他 shared resolver
  - 不放宽当前 strict declared type 的容器规则
  - 不让 `ClassRegistry#resolveTypeMetaHere(...)` 反向依赖 `ScopeTypeResolver`

---

## 1. 目标与职责边界

`ScopeTypeResolver` 的职责已经冻结为：

- 以 `Scope` 为入口解析严格的类型名与 declared type 文本
- 通过 `scope.resolveTypeMeta(...)` 处理 bare type name
- 处理顶层 `Array[T]` / `Dictionary[K, V]`
- 在解析容器参数时继续沿同一 lexical type namespace 解析 leaf type
- 保持 nested structured container text 继续被拒绝

`ScopeTypeResolver` 明确不负责：

- 猜测 unknown object type
- 产生 diagnostics
- 决定调用方的 fallback 策略
- 建立或维护 `Scope` 图本身

`ClassRegistry#resolveTypeMetaHere(...)` 仍然是 global scope 的本地 primitive，而不是 shared resolver 的包装器。shared resolver 只能建立在 `Scope#resolveTypeMeta(...)` 之上，不能反向驱动 registry 的底层本地查找。

---

## 2. 当前实现事实

### 2.1 `ScopeTypeResolver`

当前公开 API：

- `tryResolveTypeMeta(Scope scope, String typeText)`
- `tryResolveDeclaredType(Scope scope, String typeText)`
- `tryResolveDeclaredType(Scope scope, String typeText, @Nullable UnresolvedTypeMapper unresolvedTypeMapper)`

当前行为：

- 先做 exact type-meta lookup
  - lexical `sourceName` 绑定可先于 global name 命中
  - registry 仍可命中 builtin / engine / gdcc / global enum / strict container text
- exact lookup miss 后，只重试顶层 `Array[...]` 与 `Dictionary[..., ...]`
- nested structured container text 继续被拒绝
  - 例如 `Array[Array[int]]`
  - 例如 `Dictionary[String, Array[int]]`
- `UnresolvedTypeMapper` 只在 bare type name 或顶层容器 leaf type 无法严格解析时才会介入
- malformed structured text 不触发 mapper

### 2.2 `ClassRegistry`

当前分工：

- `resolveTypeMetaHere(...)`
  - 作为 global type namespace 的本地 primitive
  - 负责 builtin / engine / gdcc / global enum / strict container text
- `tryResolveDeclaredType(...)`
  - 作为 registry-aware strict API
  - 直接委托 shared `ScopeTypeResolver`
- `findType(...)`
  - 先走 shared strict declared-type 解析
  - 只有 strict miss 后才通过 compatibility unresolved mapper 保留 guessed-object fallback
- `tryParseTextType(...)`
  - 仍是兼容文本类型解析入口
  - 现有生产消费面仍存在，不能假定已经废弃

对 inner class 而言：

- registry 只按 `canonicalName` 注册 gdcc class
- `gdccClassSourceNameByCanonicalName` 记录任意 `sourceName != canonicalName` 的 gdcc class source-facing `sourceName`
- global namespace 不为 inner class 建立 `sourceName` alias

若后续 resolver 的消费方需要统一展示类名，应从 `canonicalName` 派生 display 视图；resolver 本身不承担持久化 `runtimeName` 的职责。

因此当前 registry 返回的 `ScopeTypeMeta` 已可同时覆盖：

- inner class 的 `canonicalName != sourceName`
- mapped top-level gdcc class 的 `canonicalName != sourceName`
- `displayName() == canonicalName`

### 2.3 Frontend skeleton

`FrontendClassSkeletonBuilder` 当前事实：

- declared type 解析已经切到 shared `ScopeTypeResolver`
- frontend source-facing declared type 入口现统一遵循 caller-side remap-on-miss 规则
  - 先按源码字面名执行正常 lexical strict 查找
  - 只有 miss 时才按 `FrontendModuleSkeleton.topLevelCanonicalNameMap()` 重试 canonical
  - 底层 strict lookup 仍使用 shared resolver 的 no-mapper overload；mapping 只发生在 frontend 调用侧
- parser 当前会把 inferred declaration marker `:=` 暴露到 `TypeRef.sourceText()`
  - 这不意味着 `:=` 的最终语义就是 `Variant`
  - 正确语义应当是在后续 `FrontendExprTypeAnalyzer` 中分析右侧表达式类型，并据此确定声明类型
  - skeleton 当前把它临时按“无法在本阶段完成真实推断”的 deferred 输入处理，并回退到 `Variant`
- unknown type 仍由调用方负责恢复
  - 当前 skeleton 的策略是发 `sema.type_resolution` 诊断
  - 然后回退到 `Variant`
- 在真实 scope phase 之前，builder 会先构建一条仅用于类型解析的最小 `ClassScope` 链
  - root 为 `ClassRegistry`
  - 每个 accepted class 一个 `ClassScope`
  - 每个 class scope 只发布 direct inner class 的 `type-meta`
  - 不预填充 value/function/parameter/local binding

### 2.4 Frontend scope analyzer

`FrontendScopeAnalyzer` 当前事实：

- 已在真实 `ClassScope` 上发布 immediate inner class type-meta
- 顶层 `SourceFile` 和嵌套 `ClassDeclaration` 都会建立各自的 `ClassScope`
- callable / block scope 默认复用 parent 的 type namespace
- outer class 只通过 type-meta parent chain 暴露 outer type
- outer value/function namespace 仍不应被 inner class 直接继承

---

## 3. strict 与 compatibility API 分工

严格 API：

- `ScopeTypeResolver.tryResolveTypeMeta(...)`
- `ScopeTypeResolver.tryResolveDeclaredType(...)`
- `ClassRegistry.tryResolveDeclaredType(...)`

这些 API 的共同约束：

- unknown type 返回 `null`
- 不猜测对象类型
- 不产出 diagnostics
- 适合 frontend declared type、类型检查与其他需要确定答案的语义位置

兼容 API：

- `ScopeTypeResolver.tryResolveDeclaredType(..., mapper)`
- `ClassRegistry.findType(...)`
- `ClassRegistry.tryParseTextType(...)`
- `ScopeTypeParsers.parseExtensionTypeMetadata(...)`

这些 API 的共同约束：

- compatibility fallback 必须显式出现
- fallback 只能恢复 unresolved leaf type，不能吞掉 malformed structured text
- frontend strict 位置不得默认走这些兼容入口

---

## 4. Frontend 接入约定

### 4.1 Skeleton 阶段

当前 skeleton 与 shared resolver 的契约：

- member filling 只能依赖最小 type-scope 链，不直接回放 relation 私有解析逻辑
- skeleton member declared type 解析必须先走 lexical strict lookup；mapped top-level source-facing miss 再由 caller-side remap helper 重试 canonical
- inner class、outer class、same-module class 的 type 解析必须通过 lexical type namespace 生效
- diagnostics 继续由 skeleton 自己决定何时发出、如何恢复

### 4.2 Scope 阶段

当前真实 scope graph 与 shared resolver 的契约：

- immediate inner classes 必须在 owner `ClassScope` 上发布为 local type-meta
- callable / block scope 继续沿 parent chain 继承 type namespace
- type namespace 与 value/function namespace 必须保持独立
- outer type 的可见性只能通过 type-meta parent chain 实现
- mapped top-level 自身或跨文件 top-level 的 source-facing `TYPE_META` / strict declared type 恢复，不通过 scope graph 额外发布 source alias，而由 analyzer 调用侧按“lexical first, remap-on-miss second”统一完成

### 4.3 诊断边界

当前约定保持不变：

- resolver 只返回 lookup / parse 事实
- parser / skeleton / analyzer / 后续 binder 负责把事实翻译成诊断
- resolver 不得持有 `DiagnosticManager`

---

## 5. 当前已知差异与后续工程接口

### 5.1 base-vs-outer precedence 尚未与 Godot 对齐

参考 Godot 当前 `get_class_node_current_scope_classes()` 的行为，当前 scope classes 的优先顺序是：

- current
- base
- outer

并且 base 明确优先于 outer。

GDCC 当前的 type-meta 解析仍主要建立在 lexical parent chain 上。结果是：当 inner class 的 base class 和 outer class 同时贡献同名 type-meta 时，当前实现会优先命中 outer，而不是 Godot 的 base-first 行为。

这条差异已经通过测试显式锚定，后续若要对齐 Godot，需要在 `ClassScope` 的 type-meta 可见性模型中把 “current -> base -> outer” 做成稳定协议，而不能简单依赖普通 lexical parent chain。

### 5.2 header `extends` 路径仍独立于 shared resolver

当前 class header 的 `extends` 解析仍独立于 shared declared-type resolver，但 accepted skeleton 的产物已经 canonicalized：

- `FrontendClassSkeletonBuilder` 会在 accepted header freeze 阶段生成 frontend-only `FrontendSuperClassRef`
- `FrontendSourceClassRelation` / `FrontendInnerClassRelation` 会保留 superclass 的 `sourceName` / `canonicalName`
- `LirClassDef.superName` 只保存 canonical superclass name
- `rawExtendsText` 只保留在 discovery / rejected-header / diagnostic 的短生命周期对象中

因此当前事实是：

- header super 仍走专用 header graph 解析协议，而不是 shared resolver
- `FrontendClassSkeletonBuilder` 现通过结构化 `HeaderSuperBindingDecision` 统一驱动 accepted freeze、unsupported diagnostic 与 same-module inheritance validation，而不是让这些路径各自重复做字符串猜测
- accepted header super 的 MVP 支持面已冻结为：
  - 当前编译目标内唯一 gdcc module 中 lexical 可见 inner class 的 `sourceName`
  - 当前编译目标内唯一 gdcc module 中 top-level class 的 `sourceName`
  - `sourceName == canonicalName` 的 engine/native class 名字
- `GDCC_CLASS` unsupported path 当前只会报中性的“unsupported gdcc superclass source”类诊断；在没有额外 provenance 前，不会再把它直接说成 global-script-class
- member declared type 的 lexical/type-parser 覆盖面仍强于 header `extends`，两者必须被视为不同的产品边界
- `LirClassDef.superName` 已可以被稳定解释为 canonical 继承目标
- `$` canonical raw text、path-based `extends`、autoload superclass、global-script-class superclass 以及其他 unresolved raw text 现在都会在 skeleton/header phase 发显式 `sema.class_skeleton` diagnostic，并拒绝进入 accepted canonical contract

后续若要让 header inheritance 与 shared resolver 严格对齐，需要单独设计 super path 的 canonical 绑定产物，而不是默认复用当前字符串字段。

同理，若后续要统一 frontend 中映射类的展示文案，也应建立在 `sourceName + canonicalName` 合同之上，通过 `canonicalName` 派生展示名，而不是把第三层持久化名字重新引入 resolver。

### 5.3 deferred / unsupported source 目前必须显式诊断

当前 strict declared-type 位置若遇到当前阶段无法稳定发布的 type-meta 来源，行为必须是：

- 显式 diagnostic
- 调用方决定 fallback 或 skip

当前实现与测试已经锚定：

- skeleton member declared type 的 `diagnostic + Variant fallback` 路径
- header superclass source boundary 的 `diagnostic + rejected-subtree` 路径

以上行为都不允许再退化为静默忽略或 raw-text fallback。

### 5.4 compatibility 消费面仍然存在

`findType(...)` 与 `tryParseTextType(...)` 当前仍有真实生产消费面，例如：

- extension metadata 类型解析
- DOM/LIR 兼容文本类型解析

因此后续迁移不能把这些 API 当作“名义保留但实际上已无调用”的 dead compatibility surface。若要继续收口，必须先逐一迁移真实调用点。

---

## 6. 当前测试锚点

当前测试已经把以下行为固定下来：

- shared resolver 能在 lexical scope 中解析 inner class `sourceName`
- shared resolver 与 frontend lexical scope 都继续按 mapped top-level `sourceName` 命中，再发布 canonical-derived `displayName()`
- `FrontendModuleSkeleton` caller-side remap helper 已覆盖 skeleton、variable inventory、top binding、chain binding、expr type 与 compile-only gate 的 mapped top-level source-facing 回归
- malformed structured text 不会触发 compatibility mapper
- skeleton 与 analyzer 对 immediate inner class type-meta 的发布规则保持一致
- deferred type-meta 来源会产生显式 diagnostic，而不是静默忽略
- `findType(...)` / `tryParseTextType(...)` 仍然有活跃的兼容消费面
- base-vs-outer precedence 差异与 canonical header-super 合同都已有显式回归测试
- DOM/LIR parser/serializer 与 backend adapter 继续证明 downstream canonical-only contract 没被破坏

后续工程若要改变这些行为，必须同步更新：

- 本文档
- 相关实现注释
- 对应单元测试
