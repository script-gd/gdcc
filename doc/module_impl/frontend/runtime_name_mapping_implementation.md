# Frontend Runtime Name Mapping 实现说明

> 本文档作为 frontend 顶层类名映射、双名模型、caller-side remap 规则与 downstream canonical contract 的长期事实源。本文档替代原 `runtime_name_mapping_implementation_plan.md`，不再保留分步骤实施计划、完成清单、进度记录或验收流水账。

## 文档状态

- 状态：事实源维护中（双名模型、派生 `displayName()`、module-level frozen mapping、caller-side remap、canonical-only downstream contract 已落地）
- 更新时间：2026-03-25
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/parse/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/resolver/**`
  - `src/main/java/dev/superice/gdcc/lir/**`
  - `src/main/java/dev/superice/gdcc/backend/c/**`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/scope/**`
  - `src/test/java/dev/superice/gdcc/lir/**`
  - `src/test/java/dev/superice/gdcc/backend/**`
- 关联文档：
  - `frontend_rules.md`
  - `inner_class_implementation.md`
  - `scope_analyzer_implementation.md`
  - `scope_type_resolver_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
- 明确非目标：
  - 不恢复持久化三名模型
  - 不把 `runtimeName` 带回 relation、`ScopeTypeMeta`、registry、LIR 或 backend
  - 不在 `ClassRegistry` 中为 mapped top-level 开 source alias 的全局查找
  - 不在 `ClassScope` 中发布 mapped top-level 自身的 source-facing type-meta 特判
  - 不在解析或分析过程中动态修改 mapping
  - 不把 backend 改造成理解 `sourceName`

---

## 1. 术语与目标模型

当前 frontend 类名模型已经冻结为：

- 持久化名称只有两层：
  - `sourceName`
  - `canonicalName`
- 用户可见展示名不持久化，统一派生为：
  - `displayName() = canonicalName`

其中：

- `sourceName`
  - 表示源码里的声明名
  - 继续作为 frontend lexical type namespace 的 source-facing lookup 名
- `canonicalName`
  - 表示 registry、`ClassDef` / `LirClassDef`、LIR、backend、继承链与跨 phase 引用使用的稳定身份
- `displayName()`
  - 只用于诊断、debug、inspection 与其他 human-facing 文案
  - 不单独存储，不参与 lookup

本文档仍保留“runtime name mapping”这一历史命名，只表示“模块输入侧提供的顶层 canonical 重写表”。它不意味着系统内部仍然存在持久化 `runtimeName` 层。

---

## 2. Mapping 输入边界

### 2.1 `FrontendModule`

`FrontendModule` 是 frontend 当前的统一模块输入快照，至少承载：

- `moduleName`
- `List<FrontendSourceUnit> units`
- `topLevelCanonicalNameMap`

冻结合同：

- mapping 只允许在 frontend 入口一次性注入
- key 只表示顶层 gdcc 类的 `sourceName`
- value 只表示顶层 gdcc 类的目标 `canonicalName`
- 进入 `FrontendModule` 后必须冻结，后续 phase 只读

### 2.2 `FrontendModuleSkeleton`

`FrontendModuleSkeleton` 保留冻结后的 `topLevelCanonicalNameMap`，并作为后续 analyzer 读取模块级类名映射的唯一稳定快照。

它当前提供的 helper 语义已经冻结为：

- `findMappedTopLevelCanonicalName(...)`
  - 只在存在顶层映射时返回 canonical override
- `remapTopLevelCanonicalName(...)`
  - 返回 `map.getOrDefault(sourceName, sourceName)`
- `resolveSourceFacingTypeMeta(...)`
  - 执行“先 lexical，miss 后 remap”的 type-meta 查找
- `tryResolveSourceFacingDeclaredType(...)`
  - 执行“先 lexical，miss 后 remap”的 strict declared-type 解析

后续新增 frontend source-facing 类型名消费点时，应复用这些 helper，而不是在调用点手写 `map.getOrDefault(...)`。

---

## 3. 名称生成与身份冻结规则

### 3.1 顶层类

顶层 gdcc 类的 identity 规则：

- `sourceName = 源码 class_name 或文件名推导结果`
- `canonicalName = topLevelCanonicalNameMap.getOrDefault(sourceName, sourceName)`
- `displayName() = canonicalName`

### 3.2 内部类

内部类继续维持局部源码名与派生 canonical 名的双名协议：

- `sourceName = 源码 inner class 名`
- `canonicalName = parentCanonicalName + "$" + sourceName`
- `displayName() = canonicalName`

这意味着：

- 顶层类若命中 mapping，则内部类 `canonicalName` 前缀会自动跟随映射后的顶层 canonical
- inner class 本身不参与 module top-level mapping
- caller-side remap 也不负责为 inner class 做额外 canonical 恢复

### 3.3 Header / relation / class shell

当前 skeleton/header 层的冻结事实：

- `FrontendClassSkeletonBuilder.discoverTopLevelHeader(...)`
  - discovery 时即冻结顶层 `sourceName / canonicalName`
- `FrontendSourceClassRelation`
  - 持久化顶层类的 `sourceName / canonicalName`
- `FrontendInnerClassRelation`
  - 持久化 inner class 的局部 `sourceName / 派生 canonicalName`
- `LirClassDef.getName()`
  - 始终写入 canonical identity
- superclass relation
  - 通过 `FrontendSuperClassRef` 保留 source/canonical 双名

---

## 4. Scope、TypeMeta 与 Registry 合同

### 4.1 `ScopeTypeMeta`

`ScopeTypeMeta` 的字段语义已经冻结为：

- `canonicalName`
  - 稳定 identity
- `sourceName`
  - 当前 lexical type namespace 中可见的 source-facing 名
- `displayName()`
  - 由 `canonicalName` 派生的用户可见展示名

典型情形：

- builtin / engine class
  - 一般 `sourceName == canonicalName`
- mapped top-level gdcc class
  - `sourceName = 源码名`
  - `canonicalName = 映射名`
- inner class
  - `sourceName = 局部类名`
  - `canonicalName = Outer$Inner...`

### 4.2 `AbstractFrontendScope`

frontend lexical type namespace 继续按 `typeMeta.sourceName()` 建 key。当前 contract 不允许把 lookup key 改成 `canonicalName` 或 `displayName()`。

### 4.3 `ClassRegistry`

`ClassRegistry` 对 gdcc class 的 contract 已冻结为：

- registry key 只使用 canonical name
- `gdccClassSourceNameByCanonicalName`
  - 只是 canonical registration 的 source override side table
  - 不是第二套全局 type namespace
- global `resolveTypeMetaHere(...)`
  - 不因 mapping 额外接受顶层源码名 alias

因此：

- registry 允许在 metadata 层保留 mapped top-level 的源码名
- 但 global root 不会因此开放“按源码名字面量直接查到 mapped 顶层类”的额外入口

---

## 5. Frontend Source-Facing Lookup 统一规则

### 5.1 核心规则

所有 frontend source-facing 顶层类型解析都必须遵循同一条规则：

1. 先按源码字面名做正常 lexical lookup
2. 只有 miss 时，若该名字命中 `topLevelCanonicalNameMap`，再按映射后的 canonical 重试

本文档称之为 caller-side remap-on-miss。

### 5.2 规则适用面

当前已经接入该规则的 frontend 路径包括：

- skeleton member declared type
- variable inventory 中 parameter / local declared type
- `TYPE_META` 顶层绑定分析
- static route / static receiver 识别
- assignment 左值中的 type-meta 识别
- compile-only gate 中依赖 source-facing type-meta 的路径

### 5.3 规则的边界

以下约束同样已经冻结：

- lexical hit 优先级高于 remap
  - 本地/inner type-meta 命中后不得继续 remap
- remap 只适用于顶层 mapping
  - inner class 仍只按正常 lexical 规则解析
- 调用方负责 remap
  - shared `Scope` / `ScopeTypeResolver` 不在内部偷偷替调用方做顶层 mapping 语义

### 5.4 明确禁止的旧方案

后续工程不得重新引入以下方案：

- 在 `ClassScope` 中直接发布 mapped top-level 自身的 source-facing type-meta
- 在 `ClassRegistry` 中为 mapped top-level 开 source alias 的 global lookup
- 在 analyzer/support 私自散落手写 remap 规则
- 在 lexical lookup 之前无条件把源码字面名改写成 canonical

---

## 6. 冲突检查与诊断边界

### 6.1 双轨冲突检查

顶层类相关冲突检查必须继续区分两类：

- source-facing duplicate
  - 按顶层 `sourceName` 判定
- canonical conflict after mapping
  - 按映射后的 `canonicalName` 判定

二者互补，不能互相替代。

### 6.2 诊断与展示名

human-facing 文案当前统一遵循：

- 说明“当前类/当前 receiver 是谁”时
  - 优先展示 `displayName()` 或 canonical name
- 说明“用户源码里写了什么文本”时
  - 继续展示原始 source text，例如 `rawExtendsText`

当前展示路径已经切到 canonical-derived `displayName()` 的主要消费面包括：

- `ScopeMethodResolver`
- `FrontendChainReductionHelper`
- 其他 static receiver / constructor / type-meta 失败消息

---

## 7. Downstream Canonical-Only Contract

frontend 名称映射进入 identity 之后，downstream 继续只消费 canonical：

- `ClassDef` / `LirClassDef`
- `ClassScope` 的继承链 walk
- property / method / signal 的 owner class metadata
- `ClassRegistry.checkAssignable(...)`
- `ClassRegistry.getRefCountedStatus(...)`
- DOM/LIR serializer / parser
- backend C codegen 与模板

这条 contract 的含义是：

- frontend 负责在 skeleton/header freeze 阶段把类 identity 与 superclass identity canonicalize 完整
- downstream 不负责再把 sourceName 映射成 canonical
- backend 不需要理解 source alias，也不应为此新增 mapping 逻辑

---

## 8. 维护规则

后续若新增 frontend 类型名字面量消费点，必须先判断它属于哪一类：

- source-facing 类型解析
  - 复用 `FrontendModuleSkeleton` 的 caller-side remap helper
- canonical-only downstream 消费
  - 直接使用已冻结的 canonical identity
- human-facing 展示
  - 使用 `displayName()` 或 canonical name

以下行为视为违反当前合同：

- 新增持久化 `runtimeName` 字段
- 让某个新路径既用 `sourceName` 做 lookup 又把它当展示名输出
- 把 mapped top-level 的 source alias 暴露到 global registry lookup
- 把 remap 规则扩张到 inner class canonical 派生

---

## 9. 实施反思与长期约定

这轮收敛已经证明以下结论对当前 frontend 更稳健：

1. 双名模型比三名模型更适合当前代码库。
   `sourceName` 和 `canonicalName` 已足以承载 lookup 与 identity；展示名从 canonical 派生即可，不需要持久化第三层名称。
2. source-facing remap 必须由 frontend 调用侧显式承担。
   这样能保住 lexical shadowing，也能避免 shared resolver、registry 或 scope graph 被 source alias 污染。
3. mapping 必须是模块级冻结输入，而不是运行中可变状态。
   否则 skeleton、analyzer、registry 与 backend 很难共享同一个稳定 identity 事实。
4. downstream canonical-only 是必要边界。
   一旦让 backend/LIR/registry 混入 source-facing 逻辑，类名身份会再次分裂。

后续工程应把以上四点视为 guard rail，而不是待讨论选项。
