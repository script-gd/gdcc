# Inner Class 注册与双名模型实施计划

> 本文档细化“将 inner class 统一注册到 `ClassRegistry`，并把类型名拆分为 `canonicalName` / `sourceName`”这一轮 frontend 改造的执行清单。本文档是本轮实施工作单，不替代 `scope_architecture_refactor_plan.md` 与 `scope_analyzer_implementation_plan.md` 的长期事实源地位。

## 文档状态

- 状态：待实施
- 更新时间：2026-03-12
- 基线提交：`c74d37e fix(scope): preserve container leaf types in text parsing`
- 本轮范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/lir/LirClassDef.java`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/scope/**`
- 明确非目标：
  - 本轮不修改 backend C 代码生成逻辑
  - 本轮不引入 `cSymbolName`
  - 本轮不为 inner class 建立全局 `sourceName` 别名
  - 本轮接受“`$` 直接进入 canonical 注册名，并暂时原样进入后续 C 标识符链路”的前提；该前提仅面向当前 `zig cc` / `clang` 工具链

---

## 1. 背景与问题界定

当前代码已经具备以下基础：

- `FrontendSourceClassRelation` 与 `FrontendInnerClassRelation` 已替代旧的“`SourceUnit` 与 `ClassDef` 靠索引对齐”模型，能够显式保存 source-local 的 class 归属关系。
- `FrontendScopeAnalyzer` 已能根据这些 relation 为顶层 class 与 inner class 建立 `ClassScope`，inner class 子树也不再被整棵跳过。
- `ClassRegistry` 已具备严格 `type-meta` 查询入口，例如 `resolveTypeMetaHere(...)` 与 `tryResolveDeclaredType(...)`。

但当前实现仍存在三个结构性缺口：

1. `FrontendClassSkeletonBuilder` 仍在“收集完所有顶层 class 后才批量注册顶层 class”，而 inner class 仍完全不注册到 `ClassRegistry`。
2. `FrontendClassSkeletonBuilder#resolveTypeOrVariant(...)` 仍直接走 `ClassRegistry#findType(...)`，这会把 declared type 解析与“未知名字猜测为对象类型”的兼容路径混在一起。
3. `ScopeTypeMeta` 仍只有单一 `name` 字段，无法同时表达：
   - backend / registry / cross-phase 应该依赖的稳定规范名
   - source code 与 diagnostics 需要保留的源代码名

这会直接导致以下风险：

- 字段、方法签名、signal 参数、constructor 参数中的 declared type 若引用：
  - 当前类自身
  - 同一 module 中其他 gdcc class
  - outer class
  - inner class
  在 skeleton build 阶段要么解析不稳，要么落入过宽松的 guessed object type 路径。
- inner class 即使已经有 `LirClassDef`，也无法作为统一的 Godot/GDCC class 元数据进入后续共享类型系统。
- 一旦 inner class 进入 registry，单一 `name` 字段就不再足以区分：
  - `Parent$Inner$Deep` 这样的全局规范注册名
  - 源代码里只写 `Deep` 的局部类型名

本轮计划的目标，是在不触碰 backend 的前提下，把 frontend 的 class 身份模型和注册时序先收紧到正确形态。

---

## 2. 关联文档

- `doc/module_impl/common_rules.md`
  - 通用异常与文档一致性约束，本轮若引入新的 guard rail 或 record，需要同步保持注释与文档一致。
- `doc/module_impl/frontend/frontend_rules.md`
  - frontend 必须优先采用“diagnostic + skip subtree”而非直接抛错，本轮 skeleton/ref resolver 的恢复行为必须服从这里的规则。
- `doc/module_impl/frontend/diagnostic_manager.md`
  - 诊断必须继续走 shared `DiagnosticManager`，不能把 manager 塞进 `Scope` 或 registry。
- `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `ClassRegistry` 是 global scope root，`type-meta` 是独立 namespace，本轮 inner class source-level 可见性必须通过 lexical type namespace 暴露，而不是重新污染 value/function namespace。
- `doc/module_impl/frontend/scope_analyzer_implementation_plan.md`
  - 当前 scope analyzer 已为 inner class 建立 `ClassScope` 边界，本轮需要在此基础上补齐 inner class type-meta 发布与 source/canonical 双名语义。
- `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - 作为 frontend 阶段拆分的背景材料；本轮改造仍属于 skeleton -> scope -> binder 这条链路中的 skeleton/type-meta 基础设施完善。

---

## 3. 关联代码入口

- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
  - 当前 skeleton build 主入口；本轮改造的主战场。
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendSourceClassRelation.java`
  - 当前 source file 到 top-level class / inner class 的拥有关系记录；需要扩展为承载双名与 lexical owner 信息的稳定 relation。
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendInnerClassRelation.java`
  - 当前仅保存 `ClassDeclaration -> LirClassDef`；不足以表达 canonical/source 双名与 immediate lexical owner。
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendModuleSkeleton.java`
  - module 级 skeleton 结果容器；需要继续作为“所有已接受 class skeleton”的统一发布点。
- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendScopeAnalyzer.java`
  - 当前已基于 relation 建立 inner class `ClassScope`；本轮需要继续消费增强后的 relation 并发布正确的 type-meta。
- `src/main/java/dev/superice/gdcc/frontend/scope/AbstractFrontendScope.java`
  - 当前本地 `type-meta` namespace 的承载点；`defineTypeMeta(...)` 的 key 语义要与 `sourceName` 对齐。
- `src/main/java/dev/superice/gdcc/frontend/scope/ClassScope.java`
  - 当前 class 层的 lexical/value/function 策略已冻结；本轮只允许补 type-meta 绑定，不得破坏已有 value/function 可见性规则。
- `src/main/java/dev/superice/gdcc/scope/ScopeTypeMeta.java`
  - 当前只有单一 `name` 字段；本轮需要拆成 `canonicalName` / `sourceName`。
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
  - 当前 global root，同时承担 strict type-meta 查询；本轮只允许用 canonical 名注册 inner class，不建立 source-name alias。

测试入口：

- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonTest.java`
- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendScopeAnalyzerTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/FrontendInnerClassScopeIsolationTest.java`
- `src/test/java/dev/superice/gdcc/frontend/scope/FrontendNestedInnerClassScopeIsolationTest.java`
- `src/test/java/dev/superice/gdcc/scope/ClassRegistryTest.java`
- `src/test/java/dev/superice/gdcc/scope/ClassRegistryTypeMetaTest.java`
- `src/test/java/dev/superice/gdcc/scope/ClassRegistryGdccTest.java`

---

## 4. 本轮冻结目标与不变量

本轮落地后，frontend 必须满足以下不变量：

- 顶层 class：
  - `canonicalName == sourceName`
- inner class：
  - `canonicalName = Parent$Inner$Deep...`
  - `sourceName = 源代码声明中的局部类名`
- `ClassRegistry`：
  - 只用 `canonicalName` 注册 gdcc class
  - 不为 inner class 建立全局 `sourceName` alias
- lexical type namespace：
  - 由 frontend scope/relation 暴露 `sourceName`
  - outer class 只通过 type-meta parent 链贡献 outer type，可见性规则继续服从现有 `ClassScope`
- declared type 解析：
  - 不再把 `findType(...)` 当作 declared type 的主入口
  - declared type 解析必须优先使用“source-local / lexical 可见类型 + strict registry type-meta”
- skeleton build 时序：
  - 所有已接受 class 的最小 shell 必须先注册，再填充成员签名
  - 被拒绝的 class 及其子树不得泄漏进 `ClassRegistry`、`FrontendModuleSkeleton` 或 scope side-table
- backend：
  - 本轮不改 backend
  - inner class canonical 名中的 `$` 暂时视为后续链路允许携带的稳定名字片段

---

## 5. 推荐数据模型

本轮建议采用以下最小模型，而不是再引入更大的 hierarchy 对象：

### 5.1 `ScopeTypeMeta`

建议把当前：

- `name`

拆分为：

- `canonicalName`
- `sourceName`
- `instanceType`
- `kind`
- `declaration`
- `pseudoType`

字段语义冻结为：

- `canonicalName`
  - registry / backend / cross-phase 的稳定身份名
- `sourceName`
  - 当前 lexical 上下文中使用的名字
- 对顶层 class、builtin、engine class、global enum：
  - `canonicalName == sourceName`
- 对 inner class：
  - `canonicalName != sourceName`

### 5.2 `FrontendInnerClassRelation`

当前 record 建议扩展为至少包含：

- `Node lexicalOwner`
  - immediate lexical owner，取值为 `SourceFile` 或 enclosing `ClassDeclaration`
- `ClassDeclaration declaration`
- `String sourceName`
- `String canonicalName`
- `LirClassDef classDef`

这样 `FrontendScopeAnalyzer` 与后续 binder 才能稳定回答：

- 这个 inner class 在源码里属于谁
- 它在源码里叫什么
- 它在 registry 里叫什么
- 它对应的 `LirClassDef` 是哪一个

### 5.3 `FrontendSourceClassRelation`

当前 record 建议显式补齐顶层 class 的双名语义：

- `FrontendSourceUnit unit`
- `String topLevelSourceName`
- `String topLevelCanonicalName`
- `LirClassDef topLevelClassDef`
- `List<FrontendInnerClassRelation> innerClassRelations`

同时补充以下 helper 语义：

- `allClassDefs()`
  - 返回顶层 + 全部 inner class 的 `LirClassDef`
- `findRelation(Node astOwner)`
  - 允许后续 phase 通过 AST owner 直接恢复 class relation
- `findImmediateInnerRelations(Node lexicalOwner)`
  - 允许 scope/binder 只取某个 lexical owner 的直接 inner classes，而不是把所有后代 inner class 一次性扁平暴露

### 5.4 `LirClassDef` / `ClassDef`

本轮建议维持现有单名接口不扩展：

- `ClassDef#getName()` 统一解释为 canonical name
- 对 inner class，`LirClassDef#getName()` 直接返回 `Parent$Inner...`

source-facing 名字不再试图塞回 `ClassDef`，而是由：

- `FrontendSourceClassRelation`
- `FrontendInnerClassRelation`
- `ScopeTypeMeta#sourceName`

承担。

这样可以把“对 backend 透明的稳定注册名”与“只服务于 source 语义的局部名字”分层，不需要在本轮引入 `cSymbolName`。

---

## 6. 分阶段实施计划

## Phase 1. 冻结双名模型与 relation 承载层

目标：

- 先把 class identity 模型收紧到可表达 canonical/source 双名，再继续动注册时序与 type 解析。

执行项：

1. 修改 `ScopeTypeMeta`，把单一 `name` 拆成 `canonicalName` / `sourceName`。
2. 扩展 `FrontendInnerClassRelation`，补齐 `lexicalOwner`、`sourceName`、`canonicalName`。
3. 扩展 `FrontendSourceClassRelation`，显式保存顶层 class 的 `sourceName` / `canonicalName`，并补齐按 AST owner 查询、按 immediate lexical owner 查询的 helper。
4. 全面审阅 `ClassDef` / `LirClassDef` / 相关注释，冻结“`getName()` 代表 canonical name”的说法。
5. 修改相关注释与文档，删除任何仍把 inner class 当作“只存在于 source-local relation、不进入统一类型系统”的表述。

验收清单：

- [ ] 任何一个 top-level / inner class 都能同时恢复 `sourceName` 与 `canonicalName`
- [ ] 任何一个 inner class 都能恢复 immediate lexical owner，而不是只能靠扁平列表顺序猜测
- [ ] 代码与文档中不再把 `ClassDef#getName()` 描述成“总是等于源代码类名”
- [ ] `ScopeTypeMeta` 的新字段语义在注释中被明确写清

## Phase 2. 建立 module 级 class header 发现与验证 pass

目标：

- 在真正发布 shell 之前，先拿到完整、可验证的 module 级 class header 图。

执行项：

1. 在 `FrontendClassSkeletonBuilder` 中引入独立的 class header discovery pass。
2. discovery pass 对每个 source unit 收集：
   - 顶层 class source/canonical 名
   - 所有 inner class 的 source/canonical 名
   - immediate lexical owner
   - 原始 `extends` 文本
   - source range
3. 在 discovery 阶段完成以下验证：
   - 顶层 class 重名
   - 同一 lexical owner 下 inner class `sourceName` 冲突
   - canonical name 冲突
   - 缺失 inner class 名
   - 可在 header 层确定的继承循环
4. discovery 产出必须显式区分：
   - accepted candidate
   - rejected candidate
   - rejected subtree
5. 被拒绝 subtree 必须继续符合 frontend 恢复策略：
   - 发出 diagnostic
   - 仅跳过该 subtree
   - 同 module 中其他合法 class 继续工作

验收清单：

- [ ] `FrontendClassSkeletonBuilder` 在填充成员前就能拿到完整的 module 级 class header 图
- [ ] 重名、缺名、循环等错误都能锚定到对应 AST range，并保持“diagnostic + skip subtree”
- [ ] rejected class 不会进入后续 shell publish 列表
- [ ] 仍然保留现有 top-level duplicate / inheritance-cycle 的恢复能力，且 inner class 新增错误不会打断整 module

## Phase 3. 两阶段 skeleton 发布：先注册 shell，再填充成员

目标：

- 解决“引用自身 / 同 module gdcc class / inner class 时尚未注册”的时序漏洞。

执行项：

1. 在 `FrontendClassSkeletonBuilder` 中把 skeleton build 改为两个子阶段：
   - publish shells
   - fill members
2. publish shells 阶段对每个 accepted candidate：
   - 创建最小 `LirClassDef`
   - 把 `name` 设为 canonical name
   - 设置 source file
   - 解析并写入 superclass 的最终名字
   - 立即注入 `ClassRegistry`
3. fill members 阶段复用这些已注册 shell：
   - signal
   - property
   - function
   - constructor 相关签名
4. `FrontendModuleSkeleton` 最终只发布 accepted class 的 relation 与 `allClassDefs()`。
5. 若 publish shells 之后的 fill 阶段遇到某个 class 自身无法继续恢复的错误：
   - 只诊断并跳过该 class 的成员填充
   - 不回滚其他已接受 class 的 skeleton

验收清单：

- [ ] 在开始填充任意一个 class 的成员前，当前 module 中所有 accepted top-level / inner class 都已经可从 `ClassRegistry` 查询到
- [ ] inner class 也进入 `FrontendModuleSkeleton#allClassDefs()`
- [ ] 没有 rejected shell 泄漏在 `ClassRegistry`
- [ ] 现有 `FrontendScopeAnalyzer` 仍能通过 relation 正确恢复 `ClassDef`

## Phase 4. 把 declared type 解析收紧为 strict frontend 路径

目标：

- 让 declared type 解析不再依赖 `findType(...)` 的宽松猜测路径。

执行项：

1. 替换 `FrontendClassSkeletonBuilder#resolveTypeOrVariant(...)` 的主解析策略：
   - 优先查当前 lexical class 可见的 gdcc type
   - 再查 module/global `ClassRegistry` strict type-meta
   - 只有 builtin / engine / global enum / strict container 继续通过 strict resolver 落地
2. 对 gdcc class declared type：
   - 一律归一化为 canonical name
   - 生成的 `GdObjectType` 必须持有 canonical name
3. 对 inner class declared type：
   - 允许通过 sourceName 在合法 lexical 上下文解析
   - 解析成功后写回 canonical name
4. 明确禁止 frontend declared type 位置继续直接依赖 `findType(...)`。
5. 对无法解析的 declared type：
   - 发出 diagnostic
   - 保持当前 tolerant 策略，必要时回退 `Variant`
   - 不得静默猜测为未知 object type

验收清单：

- [ ] 字段、方法参数、返回类型、signal 参数、constructor 参数中的 gdcc 类型解析不再依赖 `findType(...)`
- [ ] inner class 在合法 lexical 上下文内可通过 sourceName 解析，并最终归一到 canonical name
- [ ] 非法或未知 declared type 会发出诊断，而不是静默猜测为任意 object type
- [ ] 现有 builtin / engine / strict container 的 declared type 行为不回退

## Phase 5. 将 inner class 双名语义接入 scope/type-meta

目标：

- 让 scope graph 不只知道 inner class “是哪一个 `ClassDef`”，还知道它在源码里应以什么名字进入 lexical type namespace。

执行项：

1. 更新 `ClassRegistry#resolveTypeMetaHere(...)`，使其在返回 gdcc class 时构造新的 `ScopeTypeMeta` 形态。
2. 更新 `AbstractFrontendScope#defineTypeMeta(...)` 与相关调用点：
   - frontend 本地 type-meta namespace 用 `sourceName` 做 lookup key
   - `ScopeTypeMeta` 本体保留 canonical/source 双名
3. 更新 `FrontendScopeAnalyzer`：
   - 每个 `SourceFile` 顶层 `ClassScope` 需要为其直接 inner classes 定义 `type-meta`
   - 每个 inner `ClassDeclaration` 对应的 `ClassScope` 只定义其直接 inner classes
   - 当前 class 自身也应在其类型解析上下文中可由 `sourceName` 恢复
4. 保持现有 `ClassScope` 价值/函数 namespace 规则不变：
   - 不把 inner class 混入 value namespace
   - 不把 outer class value/function 重新暴露给 inner class
5. 继续用 relation，而不是 list 顺序，驱动 AST owner -> class identity 的恢复。

验收清单：

- [ ] 在 outer class 的 lexical type namespace 中，`Inner` 可解析为 `ScopeTypeMeta(canonicalName = "Outer$Inner", sourceName = "Inner", ...)`
- [ ] 在 inner class 内部，当前 class 自身可通过 sourceName 参与 declared type 解析
- [ ] outer class 仅通过 type-meta parent 链提供 outer type，可见性规则不破坏现有 `ClassScope` 设计
- [ ] inner class 不会被错误地放入 value/function namespace
- [ ] scope analyzer 的 AST -> scope 绑定在引入新 relation 字段后仍保持稳定

## Phase 6. 测试矩阵补齐与文档收敛

目标：

- 用正反两类测试把行为锚定到文档，而不是让实现再次先行漂移。

执行项：

1. 补齐 skeleton 相关正向测试：
   - 顶层 class 引用同 module 中稍后声明的顶层 class
   - top-level class 引用其直接 inner class
   - inner class 引用自身
   - inner class 引用 outer class
   - 多级 inner class 互相引用
2. 补齐 scope/type-meta 相关正向测试：
   - outer class 能看到直接 inner class
   - inner class 能看到自身与 outer class 的 type-meta
   - 只暴露 immediate inner classes，不一次性平铺全部后代 inner classes
3. 补齐 negative path：
   - 同一 lexical owner 下 inner class 重名
   - 不合法 lexical 上下文下引用 inner class sourceName
   - unknown declared type 发诊断并保持恢复
   - 坏 inner class subtree 被跳过后，同 module 其他 class 仍工作
4. 审阅并同步文档与注释：
   - `scope_architecture_refactor_plan.md`
   - `scope_analyzer_implementation_plan.md`
   - 如诊断类别或恢复策略描述发生变化，再同步 `diagnostic_manager.md`
5. 审阅代码注释，删除所有与新模型冲突的说法：
   - “inner class 不注册到 registry”
   - “`ClassDef#getName()` 就是源码名”
   - “type-meta 只有单一名字”

验收清单：

- [ ] `FrontendClassSkeletonTest` 覆盖 module/top-level/inner/self/outer 多种 declared type 组合
- [ ] `FrontendScopeAnalyzerTest` 与 inner-class scope tests 覆盖 sourceName -> canonicalName 的 lexical 绑定
- [ ] 至少有一组 negative test 锚定“diagnostic + skip subtree + sibling subtree continues”
- [ ] 文档、实现注释、测试断言三者不再互相冲突

---

## 7. 建议测试矩阵

建议至少覆盖以下测试样例，避免只验证 happy path：

- 正向：
  - 一个 module 两个 source unit，`A` 的字段类型引用 `B`
  - 一个 module 两个 source unit，`B` 的方法返回类型引用 `A`
  - `Outer` 的字段或方法签名引用 `Inner`
  - `Inner` 的字段或方法签名引用自身 `Inner`
  - `Inner` 的字段或方法签名引用 `Outer`
  - `Outer.Inner.Deep` 多级嵌套下，`Deep` 能解析 `Deep`、`Inner`、`Outer`
  - scope analyzer 为 `SourceFile`、`ClassDeclaration`、callable、lambda、control-flow block 继续建立稳定 scope side-table
- 反向：
  - 同一 outer 下两个 `class Inner`
  - 在 outer 外部 unqualified 写 `Inner`
  - unknown declared type
  - 缺名 inner class
  - inner class subtree 诊断后，同文件其他函数、其他 inner class、其他 source unit 顶层 class 仍继续构建

---

## 8. 主要风险与应对措施

风险 1：eager shell publish 可能让“最终会被 reject 的 class”提前污染 `ClassRegistry`

- 应对：
  - 必须先完成 Phase 2 的 discovery/validation，再进入 Phase 3 的 shell publish
  - publish 阶段只处理 accepted candidate

风险 2：`ScopeTypeMeta` 字段变更会波及 scope 测试、resolver、注释与断言

- 应对：
  - 优先批量审阅 `scope/**` 与 `frontend/scope/**` 的构造点
  - 先改 record 与构造点，再统一修测试与注释，避免中间态语义不清

风险 3：inner class 一旦改用 canonical name，diagnostic 若直接打印 `ClassDef#getName()` 会变得不友好

- 应对：
  - source-local 诊断优先打印 `sourceName`
  - 只在 registry/backend/跨 phase 身份比较时使用 canonical name

风险 4：declared type 解析若直接全面切到 strict 路径，可能误伤现有 builtin/container 行为

- 应对：
  - 继续复用 `ClassRegistry#tryResolveDeclaredType(...)` 的 strict builtin / engine / container 逻辑
  - 只禁止 declared type 位置继续走 `findType(...)` 的 guessed-object 分支

风险 5：scope analyzer 若一次性把所有后代 inner classes 都塞进当前 class 的 type namespace，会破坏 lexical 粒度

- 应对：
  - relation helper 必须提供“按 immediate lexical owner 取直接 inner classes”的能力
  - scope analyzer 只定义直接 inner classes，不平铺全部后代

---

## 9. 本轮完成定义

满足以下条件，才算本轮前端改造完成：

- inner class 已进入 `ClassRegistry`
- inner class 的 `LirClassDef#getName()` 已使用 canonical name
- `ScopeTypeMeta` 已拆分 `canonicalName` / `sourceName`
- declared type 解析已不再把 `findType(...)` 作为 frontend declared position 的主入口
- outer / inner / self / same-module class 的 declared type 与 scope 可见性拥有正反测试锚定
- 相关 frontend 文档、注释、测试断言已全部同步，不再出现“inner class deferred but code actually skips”这类描述漂移
