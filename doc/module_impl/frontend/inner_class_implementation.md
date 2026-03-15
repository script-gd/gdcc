# Inner Class 实现说明

> 本文档作为 frontend inner class 身份模型、skeleton 发布时序、registry 集成、lexical type-meta 规则与恢复策略的长期事实源。本文档替代原 `inner_class_registry_canonical_name_plan.md`，不再保留实施阶段、进度记录或执行流水账。

## 文档状态

- 状态：事实源维护中（inner class 双名模型、module header discovery、两阶段 shell publish、strict declared type 接入、scope/type-meta 发布规则已落地）
- 更新时间：2026-03-15
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/resolver/**`
  - `src/main/java/dev/superice/gdcc/lir/**`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/scope/**`
- 关联文档：
  - `frontend_rules.md`
  - `diagnostic_manager.md`
  - `scope_architecture_refactor_plan.md`
  - `scope_analyzer_implementation.md`
  - `scope_type_resolver_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
- 明确非目标：
  - 不修改 backend C 代码生成逻辑
  - 不引入 `cSymbolName`、额外 mangling 或 backend symbol alias
  - 不为 inner class 建立全局 `sourceName` alias
  - 不把 inner class 暴露到 value/function namespace
  - 不改变 canonical `$` 当前可进入 backend 标识符链路的既有前提

---

## 1. 目标与职责边界

当前 inner class 实现的职责已经冻结为：

- 为每个 accepted inner class 同时保留：
  - source-facing 的局部类名
  - registry / LIR / backend 使用的 canonical identity
- 让 inner class 进入与 top-level class 一致的 gdcc class 注册链路
- 让 source-level 可见性继续通过 lexical type namespace 表达，而不是污染 value/function namespace
- 让 skeleton 阶段的 declared type 解析可以稳定命中：
  - self
  - outer class
  - immediate / lexical 可见 inner class
  - same-module top-level gdcc class

当前实现明确不负责：

- 为 inner class 提供全局 source-name 查找入口
- 把 source-facing 名字重新塞回 `ClassDef` / `LirClassDef`
- 把 inner class 可见性扩展成跨 module 或跨 source 的隐式全局规则
- 在 backend 侧额外处理 canonical name 与 source name 的映射

---

## 2. 冻结合同

### 2.1 双名模型

- top-level gdcc class：
  - `canonicalName == sourceName`
- inner class：
  - `canonicalName = Outer$Inner$Deep...`
  - `sourceName = 源代码中的局部类名`

双名模型的用途严格区分为：

- `canonicalName`
  - registry、`ClassDef` / `LirClassDef`、LIR、shared resolver、backend 使用的稳定身份
- `sourceName`
  - frontend relation、lexical type namespace、source-facing 诊断使用的局部名字

后续工程不得再把这两层重新混回单一字段，也不得把 canonical `$` spelling 反向暴露成 frontend source-level 命名合同。

### 2.2 `ClassDef` / `LirClassDef`

- `ClassDef#getName()` 返回 canonical class name
- inner class 的 `LirClassDef#getName()` 直接返回 `Outer$Inner...`
- `ClassDef` / `LirClassDef` 不承担 inner class 的 source-facing 名字

任何新代码若只有 `ClassDef`，就只拥有 canonical identity；若需要 source-facing inner class 名字，必须显式消费 frontend relation 或 `ScopeTypeMeta`。

### 2.3 Frontend relation

当前稳定 relation 模型为：

- `FrontendSourceClassRelation`
  - 表示一个 `FrontendSourceUnit` 对其 top-level class 与同源 inner class 的拥有关系
  - top-level class 只保留一个 `name` 字段，因为顶层类满足 `sourceName == canonicalName`
- `FrontendInnerClassRelation`
  - 显式保存：
    - `lexicalOwner`
    - `declaration`
    - `sourceName`
    - `canonicalName`
    - `FrontendSuperClassRef`
    - `classDef`
- `FrontendOwnedClassRelation`
  - 作为 top-level / inner class 共用的 ownership 协议

relation 层的长期约束：

- `findRelation(Node astOwner)` 通过 AST identity 恢复 relation，不靠名字或索引猜测
- `findImmediateInnerRelations(Node lexicalOwner)` 只返回 direct inner classes，不平铺全部后代
- `allClassDefs()` 返回 top-level class 加上同源 inner classes
- `FrontendModuleSkeleton` 只发布 accepted relation 与 accepted class shell

### 2.4 `ClassRegistry`

`ClassRegistry` 对 gdcc class 的 inner-class 合同已经冻结为：

- 只按 `canonicalName` 注册 gdcc class
- global namespace 不为 inner class 建立 `sourceName` alias
- `gdccClassSourceNameByCanonicalName` 只记录：
  - key: accepted inner class 的 canonical name
  - value: 对应的 source-local `sourceName`
- top-level gdcc class 不写入冗余 side-table 条目
- 删除 gdcc class 时必须同步清理 side-table

该 side-table 只服务于：

- 构造正确的 `ScopeTypeMeta`
- 返回 source-facing inner class 名字
- source-facing diagnostics 或调试辅助

它不是第二套全局名称空间，也不能被当作 `findGdccClass(...)` 的别名查找入口。

### 2.5 `ScopeTypeMeta` 与 lexical type namespace

`ScopeTypeMeta` 的字段语义已经冻结为：

- `canonicalName`
  - registry / backend / shared resolver 使用的稳定身份
- `sourceName`
  - 当前 lexical type namespace 中可见的名字
- `instanceType`
  - 离开 type namespace 后进入值语义时对应的运行时类型
- `kind`
  - type-meta 来源类别
- `declaration`
  - 可选的底层声明对象
- `pseudoType`
  - 是否为 synthetic / non-class-like type symbol

inner class 在 scope 中的长期规则：

- local type-meta lookup 使用 `sourceName`
- 命中后携带的 `ScopeTypeMeta` 同时保留 `canonicalName` 与 `sourceName`
- top-level `ClassScope` 只发布 direct inner classes 的 type-meta
- inner `ClassScope` 只发布其 direct inner classes 的 type-meta
- 当前 class 自身可在其类型解析上下文中按 `sourceName` 参与 declared type 解析
- outer class 通过 lexical parent chain 贡献 outer type visibility
- inner class 不进入 value namespace，也不改变既有 function namespace 规则

---

## 3. Skeleton 与 Header 实现事实

### 3.1 Module header discovery

`FrontendClassSkeletonBuilder` 先执行 module 级 class header discovery，再构建 accepted shell。discovery 当前稳定负责：

- 收集 top-level class 与全部 inner class 的：
  - `sourceName`
  - `canonicalName`
  - `lexicalOwner`
  - `raw extends` 文本
  - source range
- 在 header 层拒绝：
  - top-level 重名
  - 同一 lexical owner 下 inner class `sourceName` 冲突
  - canonical name 冲突
  - 缺失 inner class 名
  - unsupported superclass source
  - inheritance cycle

discovery 的发布规则为：

- accepted candidate 进入后续 shell 构建
- rejected subtree 只跳过受影响子树
- 同一 module 中其他合法 subtree 继续构建

### 3.2 两阶段 skeleton 发布

accepted class skeleton 的发布顺序已经冻结为：

1. 构建 accepted relation shell
2. 为所有 accepted top-level / inner class 创建最小 `LirClassDef`
3. 先统一发布所有 class shell 到 `ClassRegistry`
4. 再填充成员签名

当前最小 shell 的稳定事实：

- `LirClassDef.name` 写 canonical class name
- `LirClassDef.superName` 写 canonical superclass name
- source file 在 shell 创建时写入
- inner class shell 发布时同步写入 `gdccClassSourceNameByCanonicalName`

成员填充阶段的长期约束：

- 只填充当前 class 自己的 signals / properties / functions / constructors
- inner class 自己拥有独立 shell，不会被并入父类成员表
- constructor 统一降到 `_init` 函数面
- duplicate `_init` 走 `diagnostic + skip duplicate`，不做硬失败

### 3.3 Declared type 解析

skeleton 成员填充当前不再把 `ClassRegistry#findType(...)` 当作 declared type 的主入口。当前实现稳定依赖：

- 基于 accepted relation 构建的最小 class-scope 链
- shared `ScopeTypeResolver` 的 strict no-mapper overload
- lexical type namespace 对 self / outer / direct inner / same-module gdcc class 的可见性

当前恢复策略：

- gdcc class declared type 命中后统一写回 canonical `GdObjectType`
- builtin / engine / strict container 继续走 strict resolver
- unknown declared type 发 `sema.type_resolution` warning
- unknown declared type 回退 `Variant`
- 不允许静默猜测为未知 object type

---

## 4. Scope / Analyzer / Resolver 集成事实

### 4.1 `FrontendScopeAnalyzer`

`FrontendScopeAnalyzer` 当前不再通过 source order 或 `moduleSkeleton.classDefs()` 索引对齐恢复 inner class 边界，而是直接消费 `FrontendSourceClassRelation` / `FrontendInnerClassRelation`：

- `SourceFile` 建立 top-level `ClassScope`
- nested `ClassDeclaration` 通过 relation 重开对应的 inner `ClassScope`
- 每个 class boundary 只发布 direct inner classes 的 type-meta
- 缺失 relation / classDef 的坏 inner class subtree 只被局部跳过

### 4.2 `ClassRegistry#resolveTypeMetaHere(...)`

registry 侧 type-meta 合同已经冻结为：

- 按 canonical name 命中 gdcc class
- 命中 inner class 时先查 `gdccClassSourceNameByCanonicalName`
- 返回的 `ScopeTypeMeta` 对 inner class 可满足：
  - `canonicalName != sourceName`
- side-table miss 默认视作：
  - `sourceName == canonicalName`

### 4.3 shared `ScopeTypeResolver`

shared resolver 与 inner class 的稳定集成点为：

- bare type name 走 `scope.resolveTypeMeta(...)`
- lexical scope 能先于 global root 命中 direct / outer 可见 inner class `sourceName`
- 命中 inner class 后统一回到 canonical `GdObjectType`
- 顶层 `Array[T]` / `Dictionary[K, V]` 在容器 leaf type 上复用同一 lexical type namespace

---

## 5. 诊断与恢复合同

inner class 相关恢复规则当前已经冻结为：

- recoverable 错误优先采用 `diagnostic + skip subtree`
- rejected inner class subtree 不得泄漏进：
  - `ClassRegistry`
  - `FrontendModuleSkeleton`
  - scope side-table
- 同一 module 中其他合法 top-level / inner class 必须继续产生产物
- declared type unknown 不允许 silent fallback 到 guessed object type
- human-facing 诊断若要展示源码里的类名，应优先使用：
  - `sourceName`
  - 用户实际写下的 raw text
- canonical name 主要用于：
  - registry / backend / shared resolver / cross-component 身份比较

---

## 6. 长期不变量

后续改动必须继续满足以下事实：

- inner class 已与 top-level gdcc class 一样进入 `ClassRegistry`
- gdcc class 的全局注册键始终是 canonical name
- inner class 的 source-facing 名字只能通过 relation、type-meta 或 registry side-table 恢复
- `ClassDef#getName()` 与 `LirClassDef#getName()` 始终表示 canonical class name
- top-level class 满足 `canonicalName == sourceName`
- inner class 满足 `canonicalName = Outer$Inner...`、`sourceName = 局部类名`
- lexical type namespace 只发布 immediate inner classes
- inner class 不进入 value/function namespace
- declared type 解析不再依赖 `findType(...)` 的 guessed-object 主路径
- `$` 当前允许原样进入 backend 标识符链路

---

## 7. 后续工程接口

若后续工程要扩展 inner class 语义，必须显式处理以下接口，而不是局部打补丁：

- 跨多个 gdcc module 的 inner class 可见性与稳定绑定协议
- inner class source-facing 名字是否需要新的显式导出/别名机制
- backend 在新工具链或平台约束下对 canonical `$` 的兼容策略
- source-facing 诊断在缺失 relation 时的降级协议
- 若后续 frontend 语义工作需要持久化更多 inner class provenance，应扩展 relation 或 side-table，而不是反向污染 `ClassDef`

任何扩展都必须同步更新本文档、相关实现注释和正反两类测试锚点。

---

## 8. 回归约束

涉及 inner class identity、注册时序或 scope/type-meta 规则的改动，至少应继续锚定以下回归点：

- relation 能同时恢复 top-level / inner class 的 source 与 canonical 名字
- inner class relation 能恢复正确的 immediate lexical owner
- member filling 开始前，accepted top-level / inner class 已可从 `ClassRegistry` 查询
- registry 对 top-level gdcc class 返回 `canonicalName == sourceName`
- registry 对 inner class 返回 `canonicalName != sourceName`
- lexical type namespace 只暴露 direct inner classes，不平铺全部后代
- inner class 可解析 self / outer / lexical 可见 sibling-inner 的 declared type
- duplicate inner class、bad subtree、unknown declared type 都有 negative-path 断言
- negative path 必须继续验证：
  - diagnostic category
  - 关键 message 事实
  - rejected subtree
  - sibling subtree / other source unit 继续工作
