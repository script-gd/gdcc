# Inner Class Canonical `__sub__` 迁移与 Godot-facing 类名面实施计划

## 文档状态

- 状态：Planned
- 更新时间：2026-04-20
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/lir/**`
  - `src/main/java/dev/superice/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
  - `src/test/java/dev/superice/gdcc/**`
  - `doc/module_impl/frontend/**`
- 关联文档：
  - `doc/module_impl/frontend/runtime_name_mapping_implementation.md`
  - `doc/module_impl/frontend/inner_class_implementation.md`
  - `doc/module_impl/frontend/superclass_canonical_name_contract.md`
  - `doc/module_impl/frontend/scope_type_resolver_implementation.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`

## 背景

当前仓库已经冻结了两个长期事实：

- downstream 继续消费 canonical-only identity
- inner class canonical name 继续由 frontend skeleton/header 阶段一次性派生并冻结

旧版 `godot_facing_class_name_surface_plan.md` 基于另一个前提展开：

- 保留 inner canonical 中的 `$`
- 只在 Godot-facing class-name surface 上引入 backend-only alias

这条路线能修 Godot ClassDB 的 identifier 限制，但会让系统在 canonical 之外再多出一层 backend-only 名字视图。当前决策已经改成另一条路线：

- 直接把 inner class canonical separator 从 `$` 迁移为 `__sub__`
- 把 `__sub__` 提升为 gdcc 保留序列
- 禁止 `__sub__` 出现在用户自定义 gdcc 类名和 top-level canonical mapping value 中

这样做的目标不是收缩双名模型，而是让 canonical identity 本身同时满足：

- frontend / scope / LIR / backend 继续把它当稳定身份
- Godot-facing surface 可以继续直接透传 canonical，而不再额外发明 alias

## 核心结论

本轮方案不是 backend alias 修补，而是 canonical contract migration。

需要迁移的不是一处模板字符串，而是以下整体合同：

- inner class canonical 生成规则
- source-facing `extends` 对 canonical spelling 的拒绝规则
- top-level canonical mapping 输入边界
- registry / scope / LIR / backend / tests / docs 中所有显式写死 `$` canonical 语义的锚点

同时，本轮不再引入第三套名字层：

- `sourceName`：继续服务 frontend source-facing lookup
- `canonicalName`：继续服务 registry / LIR / backend / Godot-facing surface
- 不新增持久化 `runtimeName`
- 不新增 backend-only Godot alias

## 为什么 `__sub__` 仍能保住双名模型的“不相交”

`_sub_` 路线最大的问题是 separator 是合法源码标识符片段，若不额外约束，canonical inner name 会与 top-level source/canonical 空间混在一起。

这次改成 `__sub__` 后，必须把下面三条一起视为同一个合同：

1. 用户声明的 gdcc top-level / inner `sourceName` 不允许包含 `__sub__`
2. `topLevelCanonicalNameMap` 的 value 也不允许包含 `__sub__`
3. inner canonical 固定派生为 `parentCanonicalName + "__sub__" + sourceName`

只要这三条同时成立，就仍然能保持当前双名模型最关键的性质：

- source-facing 名字空间中不会自然出现 `__sub__`
- mapped top-level canonical 也不会自然出现 `__sub__`
- 因此任何带 `__sub__` 的 gdcc class canonical 都可以被稳定识别为“canonical inner spelling”，而不是合法源码类名

典型冲突例子：

- 顶层类：`Hero__sub__Worker`
- inner 类：`Hero` 的 `Worker`

如果不把 `Hero__sub__Worker` 判为非法源码名，这两个 identity 会冲突。
因此 `__sub__` 方案能成立的前提不是“换个更长的分隔符”，而是“把 `__sub__` 升格成 gdcc 保留 canonical separator”。

## 当前代码库中的类名使用面

### A. 生成 canonical identity 的入口点

真正决定 inner canonical spelling 的行为入口当前很少，核心只有两类：

- `FrontendClassSkeletonBuilder`
  - inner canonical 生成当前已经切到 `parentCanonicalName + "__sub__" + innerClassName`
  - header `extends` 当前显式拒绝源码里出现 canonical `__sub__` spelling
- `FrontendModule`
  - `freezeTopLevelCanonicalNameMap(...)` 当前统一经 `FrontendClassNameContract` 冻结
  - 已在 public boundary 上拒绝包含保留 canonical separator 的 key/value

这意味着：

- 迁移入口点少
- canonical 扩散面大

## B. canonical-only 内部消费面，不会把类名传给 Godot

这些位置继续应该把类名视为 opaque canonical identity；它们不是 Godot-facing surface，但都要跟随 canonical spelling 重基线：

- frontend relation / skeleton
  - `FrontendSourceClassRelation`
  - `FrontendInnerClassRelation`
  - `FrontendOwnedClassRelation`
  - `FrontendSuperClassRef`
- scope / registry / resolver
  - `ScopeTypeMeta`
  - `ClassRegistry`
  - `ScopeMethodResolver`
  - `ScopePropertyResolver`
  - `ScopeSignalResolver`
- LIR / serializer / parser
  - `LirClassDef`
  - `DomLirSerializer`
  - `DomLirParser`
- backend 内部 canonical 消费
  - `CBodyBuilder`
  - `BackendPropertyAccessResolver`
  - `LoadPropertyInsnGen`
  - 其他只把 `GdObjectType.getTypeName()` 当内部 identity 的路径
- backend 内部 C 符号链路
  - `entry.c.ftl`
  - `entry.h.ftl`
  - `func.ftl`

这些位置大多不需要新语义，只需要：

- 接受 `Outer__sub__Inner` 作为新的 canonical spelling
- 删除对 `$` canonical 的硬编码期待

### C. 会把类名继续交给 Godot 的 surface

这些位置仍然必须单独盘清，因为它们是 issue 7 的真实爆点。但这里不能再把它们视为一个同构平面；它们至少分成四种不同合同，不同合同要用不同验收口径。

#### C1. 注册身份面

这层 surface 的职责是把 gdcc class identity 注册进 Godot，并保证后续绑定与 attach 使用同一个 class name：

- extension class 注册
  - `template_451/entry.c.ftl`
  - `godot_classdb_register_extension_class5(...)`
- method / property owner class
  - `template_451/entry.c.ftl`
  - `godot_StringName* class_name = GD_STATIC_SN(...)`
- instance attach
  - `template_451/entry.c.ftl`
  - `godot_object_set_instance(...)`

这层的验收口径不是“字符串看起来都像类名”，而是：

- 注册名、owner class、attach class name 必须是同一个 canonical identity
- GDCC 父类注册关系与子类注册关系必须继续可追溯
- scene integration 不再报 `not a valid class identifier`

#### C2. Outward metadata 面

这层 surface 的职责不是注册类，而是把 object leaf 身份编码进 Godot 可见的 outward metadata：

- typed array outward hint
  - `CGenHelper.renderContainerHintAtom(...)`
- typed dictionary outward hint
  - `CGenHelper.renderContainerHintAtom(...)`

这层的验收口径不是“Godot 注册成功”，而是：

- object leaf hint string 必须稳定产出 canonical `Outer__sub__Inner`
- engine leaf 与 generic leaf 的既有 outward metadata 不能被误改
- typed array 与 typed dictionary 的 hint grammar 继续保持各自现有规则，而不是因为都承载类名就被当成同一种 surface

#### C3. Runtime compare 面

这层 surface 的职责是在 runtime 把“外部返回的 class name”与“编译期期望的 class name”做比较：

- typed array runtime guard
  - `CGenHelper.renderTypedArrayGuardClassNameExpr(...)`
- typed dictionary runtime guard
  - `CGenHelper.renderTypedDictionaryGuardClassNameExpr(...)`
- `Variant -> Object` runtime type check
  - `OperatorInsnGen.renderVariantObjectTypeCheckExpr(...)`
  - `gdcc_check_variant_type_object(...)`

这层的验收口径不是“hint string 正确”或“注册名正确”本身，而是：

- runtime compare 使用的 expected class name 与实际注册名一致
- typed container guard 与 `Variant -> Object` check 分别覆盖 exact-match 路径
- engine object 的 subclass-match 逻辑保持原样，不因 gdcc inner canonical 改写而漂移

#### C4. Dormant / 预留面

这层 surface 当前并不承载实际的 gdcc object identity，但未来很容易被误填：

- outward property info 的 `class_name` 槽位
  - `BoundMetadata.classNameExpr`
  - `CGenHelper.renderBoundMetadata(...)`
  - `template_451/entry.h.ftl`

这层的验收口径是“保持不变且留下注释边界”，不是“顺手也填成 canonical”：

- 当前继续保持空 `class_name`
- 若后续要启用，必须单独立项，并显式归属到某个 Godot-facing surface 合同中

在旧方案里，上述各层 surface 需要一个 backend alias 把 `$` canonical 翻译成 Godot 可接受名字。
在新方案里，这些位置不再需要单独 alias 层，但仍必须按分层口径分别重验收，因为它们消费的是同一 canonical name，却不共享同一种错误模式。

### D. 继续只承载 engine/native 类名的 Godot-facing surface

这些位置仍然是 Godot-facing，但当前不承载 gdcc inner canonical：

- `godot_classdb_construct_object2(...)`
  - 继续使用最近 native ancestor 名
- engine method bind lookup
  - 继续使用 engine/native owner class

本轮对这些位置不做语义变更，只做回归验证，确保 canonical separator 迁移没有误伤 engine 路径。

## 改动面评估

### 1. 行为改动入口点少

行为级主改动集中在：

- `FrontendClassSkeletonBuilder`
  - inner canonical 生成
  - canonical spelling 诊断
  - source class name 保留序列校验
- `FrontendModule`
  - top-level canonical mapping 输入校验

### 2. 合同扩散面大

虽然真正改行为的主入口不多，但 canonical name 在仓库中是跨阶段稳定 identity：

- `ClassDef`
- `ScopeTypeMeta`
- `ClassRegistry`
- `LirClassDef`
- DOM/LIR parser / serializer
- backend codegen / templates
- 大量 frontend / scope / LIR / backend 测试
- 多份 implementation/fact-source 文档

因此这个改动的特征是：

- 代码入口少
- 测试、文档、断言基线扩散大
- 不适合“先改一处试试看”

已有扫描结果表明，仓库里至少有数十个文件直接写死了 `Outer$Inner` 风格或“canonical '$' spelling”相关事实；真正的工程成本主要在这里，而不是在 separator 常量本身。

## 执行策略：先隔离基线噪音，再观察真实回归

这次迁移最容易失败的地方，不是实现改不动，而是“机械基线替换”和“真实行为回归”同时发生，导致 diff 和失败输出失去信号。

实施时必须执行以下策略：

### 1. 先建立语义锚点，再做机械重基线

在批量替换 `$ -> __sub__` 之前，先锁定最小语义锚点：

- inner canonical 生成
- `__sub__` 保留序列拒绝
- source-facing `extends` canonical-text 拒绝
- registry / type-meta / LIR roundtrip
- 注册身份面
- outward metadata 面
- runtime compare 面

只有这些锚点已经有稳定断言后，才允许去改大面积 display text、测试名、fixture 注释、文档事实源。

### 2. 把变更分成“语义批次”而不是“文件批次”

每一批变更只允许引入一个行为面上的真实变化：

- 批次 A：frontend canonical 生成与保留序列 guard rail
- 批次 B：source-facing `extends` 与 registry / LIR 合同
- 批次 C：注册身份面
- 批次 D：outward metadata 面
- 批次 E：runtime compare 面
- 批次 F：纯机械基线与文档收敛

不允许把 “frontend 行为修改 + backend surface 修改 + 测试重命名 + 文档统一替换” 混在一个无法定位的批次里。

### 3. 先跑哨兵集，再跑扩散集

每个语义批次都先验证一组小而稳定的哨兵测试，再放大到扩散集：

- 哨兵集
  - 只覆盖当前批次负责的合同
  - 失败时必须能直接判断回归落在哪个面
- 扩散集
  - 用来发现是否波及相邻模块
  - 不能替代哨兵集做定位

如果一开始就跑大范围重基线后的全量相关测试，失败输出会同时混入：

- 旧字符串期待未更新
- display name / diagnostic 文案变化
- 真正的 identity mismatch

这样会直接掩盖真实回归。

### 4. 机械替换必须后置，并保持可归因

以下变更归类为机械基线噪音：

- `Outer$Inner` 文本字面量改成 `Outer__sub__Inner`
- `canonical '$' spelling` 改成 `canonical '__sub__' spelling`
- 纯说明性 display name / 文档句子 / 测试标题更新

这些改动必须后置到对应语义面已经稳定之后，再成批处理。否则 reviewer 和测试输出都无法判断当前失败是：

- 行为逻辑真的错了
- 还是只是旧字面量还没一起改完

### 5. 文档与实现同步，但不能抢在行为锚点之前统一替换

事实源文档必须同步更新，但同步的粒度应跟语义批次走，而不是先全局文档替换再回头补实现。

文档更新顺序应遵循：

- 先更新当前批次直接约束的事实源
- 再更新引用该事实的周边说明文档
- 最后清理剩余纯文本噪音

### 6. 批次验收必须要求“失败可归类”

一批变更完成后，验收不只是“测试都过”，还必须满足：

- 任何新增失败都能被明确归类到某个语义面
- 不能出现同时跨 frontend / registry / metadata / runtime compare 多面失真但无法定位的失败集合
- 不能依赖人工目测大 diff 去猜哪一层先坏了

## 约束与非目标

本计划的强约束：

- 继续保持 downstream canonical-only contract
- 不恢复持久化三名模型
- 不把 `sourceName` 重新带回 backend / LIR / registry
- 不引入 backend-only Godot alias
- 不把 `__sub__` 的语义偷偷分叉成“frontend 一个规则、backend 一个规则”

本计划的非目标：

- 不改变 caller-side remap-on-miss 的总体模型
- 不为 inner class 建立全局 source alias
- 不顺带重做 C symbol mangling
- 不在本轮支持 path-based `extends`、autoload superclass、global-script-class superclass 绑定

## 分步骤实施计划

### 阶段 1：冻结 `__sub__` 作为 canonical separator 与 gdcc 保留序列

实施内容：

- 在 frontend 侧集中定义 canonical separator 常量 `__sub__`
- 把 `__sub__` 明确提升为 gdcc 保留序列
- 对以下输入边界加 guard rail：
  - top-level 源码类名
  - inner 源码类名
  - `topLevelCanonicalNameMap` key
  - `topLevelCanonicalNameMap` value
- 输入边界策略分两类：
  - 用户源码中的 class name 违规：`diagnostic + skip subtree`
  - `FrontendModule` 外部注入的 mapping 违规：public API boundary fail-fast

执行状态：

- [x] 1.1 集中定义 `__sub__` 保留序列事实源，并把 `FrontendModule` / `FrontendModuleSkeleton` 的 mapping 冻结逻辑收敛到同一 helper
- [x] 1.2 为 top-level / inner 源码类名接入 `diagnostic + skip subtree` guard rail
- [x] 1.3 增补哨兵测试并完成阶段 1 验收

阶段 1 验收同步（2026-04-20）：

- mapping 输入边界：
  - `FrontendModuleTest` 新增正反锚点，确认 `topLevelCanonicalNameMap` 的 key/value 含 `__sub__` 时 public boundary fail-fast
  - 同时确认仅近似 `__sub__`、但不包含完整 `__sub__` 序列的名字不会被误拒绝
- 源码类名边界：
  - `FrontendClassHeaderDiscoveryTest` 新增 top-level negative path，确认 `class_name Hero__sub__Worker` 会发 `sema.class_skeleton`、记录 skipped root，并且不会把坏 subtree 泄漏进 relation / registry
  - 同文件新增 inner-class negative path，确认 `class Worker__sub__Leaf` 会被局部跳过，但 sibling inner class 与其他 source unit 继续工作
  - 同文件新增 near-miss positive path，确认规则锚定的是完整 `__sub__` 序列，而不是泛化成“所有类似名字都拒绝”
- 已跑测试：
  - 哨兵集：`FrontendModuleTest`、`FrontendClassHeaderDiscoveryTest`、`FrontendClassSkeletonTest`
  - 扩散集：`FrontendClassSkeletonAnnotationTest`、`FrontendInheritanceCycleTest`、`FrontendSemanticAnalyzerFrameworkTest`

验收细则：

- 源码声明 `class_name Hero__sub__Worker` 或 `class Worker__sub__Leaf` 时，skeleton phase 发 `sema.class_skeleton`，并跳过坏 subtree
- `FrontendModule` 若收到包含 `__sub__` 的 mapping key/value，会在冻结输入时直接失败，而不是把坏 canonical 推迟到 registry / backend
- 文档里明确写出：`__sub__` 是 gdcc 规则，不是 Godot 规则

### 阶段 2：迁移 inner canonical 生成与 header superclass canonical-text 拒绝规则

实施内容：

- 把 `FrontendClassSkeletonBuilder` 中 inner canonical 生成从：
  - `parentCanonicalName + "$" + innerClassName`
  - 改为 `parentCanonicalName + "__sub__" + innerClassName`
- 把 header `extends` 中“canonical text 不属于 source-facing syntax”的拒绝规则从 `$` 迁移到 `__sub__`
- 保持 source-facing `extends` 继续要求用户写局部 `sourceName`，而不是 canonical inner spelling

执行状态：

- [x] 2.1 把 `FrontendClassSkeletonBuilder` 的 inner canonical 派生切换到 `__sub__`
- [x] 2.2 把 header `extends` canonical-text 拒绝规则与诊断文案同步迁移到 `__sub__`
- [x] 2.3 增补阶段 2 哨兵/扩散测试并完成验收同步

阶段 2 验收同步（2026-04-20）：

- 实现锚点：
  - `FrontendClassSkeletonBuilder` 现在通过 `FrontendClassNameContract.INNER_CLASS_CANONICAL_SEPARATOR` 派生 inner canonical，不再直接拼接 `$`
  - header `extends` canonical-text 拒绝改为识别 `__sub__`，并把诊断文案统一成 `canonical '__sub__' spelling`
- 测试锚点：
  - `FrontendClassSkeletonTest` 已把普通 inner、mapped top-level + inner、lexical superclass canonical 写回新的 `Outer__sub__Inner` 合同
  - `FrontendClassHeaderDiscoveryTest` 已把 canonical-super negative path 切到 `extends Outer__sub__Inner`，并新增 near-miss case，确认 `CanonicalNearMiss__sub_Shared` 不会被误判成 canonical spelling
  - `FrontendSemanticAnalyzerFrameworkTest` 已同步验证 semantic pipeline 下的 source-facing `extends Shared` 正常工作，而 canonical `extends Outer__sub__Inner` 继续在 frontend boundary 被拒绝
  - 原“top-level 直接撞上 inner canonical namespace”的 header-discovery fixture 已删除，因为阶段 1 已经禁止源码类名和 mapping value 产生 `__sub__`；继续保留只会制造基线假红，掩盖真实阶段 2 回归
- 已跑测试：
  - 哨兵集：`FrontendClassHeaderDiscoveryTest`、`FrontendClassSkeletonTest`、`FrontendSemanticAnalyzerFrameworkTest`
  - 扩散集：`FrontendModuleTest`、`FrontendInheritanceCycleTest`、`FrontendClassSkeletonAnnotationTest`

验收细则：

- 普通 inner class 的 canonical 从 `Outer$Inner` 变成 `Outer__sub__Inner`
- mapped top-level + inner class 组合场景中，inner canonical 前缀继续跟随 mapped top-level canonical
  - 例如 `RuntimeOuter__sub__Inner`
- `extends Inner` 继续可用
- `extends Outer__sub__Inner` 必须被显式拒绝，并给出与当前 `$` 版本等价的 canonical-text 诊断
- 诊断文案不再提 `$`，而是提 `canonical '__sub__' spelling`

### 阶段 3：重基线 frontend / scope / LIR 的 canonical identity 合同

实施内容：

- 让 relation、`ScopeTypeMeta`、`ClassRegistry`、`ClassDef`、`LirClassDef`、DOM/LIR parser / serializer 继续把 canonical 当 opaque identity
- 统一删除实现、注释、测试中把 inner canonical 写死为 `$` 的事实
- 更新所有“canonical '$' spelling”“Outer$Inner”等断言、fixtures 与文案

执行状态：

- [x] 3.1 重基线 `ClassRegistry` / shared resolver / frontend scope 的 canonical inner-class 断言与注释
- [x] 3.2 重基线 frontend analyzer / lowering 中消费 canonical class identity 的断言与 fixtures
- [x] 3.3 重基线 DOM/LIR parser / serializer roundtrip 与相关事实源文档

阶段 3 执行同步（2026-04-21，已完成）：

- 3.1 已完成：
  - `ClassDef` / `ScopeTypeMeta` 的 canonical 示例注释已改为 `Outer__sub__Inner`
  - `ClassRegistryGdccTest`、`ClassRegistryTypeMetaTest`、`ScopeTypeResolverTest` 已把 inner canonical 和 mapped-inner canonical 全部重锚到 `__sub__`
  - `ScopePropertyResolverTest`、`ScopeMethodResolverTest`、`ScopeSignalResolverTest` 继续分别覆盖：
    - canonical inner superclass happy path
    - stale source-styled superclass negative path
    - mapped canonical inner superclass happy / negative path
  - `ClassScopeResolutionTest`、`ClassScopeSignalResolutionTest`、`FrontendInnerClassScopeIsolationTest`、`FrontendNestedInnerClassScopeIsolationTest`、`ScopeTypeMetaChainTest` 已把 lexical type-meta / inherited member 可见性的 canonical 断言切到 `__sub__`
- 3.1 已跑测试：
  - `ClassRegistryGdccTest`
  - `ClassRegistryTypeMetaTest`
  - `ScopeTypeResolverTest`
  - `ScopePropertyResolverTest`
  - `ScopeMethodResolverTest`
  - `ScopeSignalResolverTest`
  - `ClassScopeResolutionTest`
  - `ClassScopeSignalResolutionTest`
  - `FrontendInnerClassScopeIsolationTest`
  - `FrontendNestedInnerClassScopeIsolationTest`
  - `ScopeTypeMetaChainTest`
- 3.2 已完成：
  - `FrontendDeclaredTypeSupportTest`、`FrontendModuleSkeletonTest` 已把 mapped top-level retry 与 lexical-hit 优先级断言重锚到 `__sub__` canonical
  - `FrontendScopeAnalyzerTest` 已把 nested class scope、materialized inner class 边界与跨单元 nested LIR fixture 的 canonical identity 断言切到 `__sub__`
  - `FrontendExprTypeAnalyzerTest`、`FrontendTypeCheckAnalyzerTest`、`FrontendChainBindingAnalyzerTest`、`FrontendChainHeadReceiverSupportTest`、`FrontendCompileCheckAnalyzerTest` 已把 analyzer/support 链路中构造器返回类型、静态路由链、type-meta receiver 与 compile-check ctor regression 的 canonical object type 断言切到 `__sub__`
  - `FrontendLoweringClassSkeletonPassTest`、`FrontendLoweringPassManagerTest`、`FrontendLoweringFunctionPreparationPassTest` 已把 lowering 产出的 inner class name、context owner canonical 与 DOM/LIR 快照断言切到 `__sub__`
  - 本批次刻意保留非 canonical `$` surface 不变：
    - GDScript `$Node`
    - LIR operand `$0`
    - synthetic helper function `_default_ping$alias`
    - 这样做是为了把失败继续约束在 canonical identity 面，避免基线噪音掩盖真实回归
- 3.2 已跑测试：
  - `FrontendDeclaredTypeSupportTest`
  - `FrontendModuleSkeletonTest`
  - `FrontendScopeAnalyzerTest`
  - `FrontendExprTypeAnalyzerTest`
  - `FrontendTypeCheckAnalyzerTest`
  - `FrontendChainBindingAnalyzerTest`
  - `FrontendChainHeadReceiverSupportTest`
  - `FrontendCompileCheckAnalyzerTest`
  - `FrontendLoweringClassSkeletonPassTest`
  - `FrontendLoweringPassManagerTest`
  - `FrontendLoweringFunctionPreparationPassTest`
- 3.3 已完成：
  - `DomLirSerializerTest`、`DomLirParserTest` 已把 inner class / superclass roundtrip 锚点改为 `Outer__sub__Leaf` 与 `Outer__sub__Shared`
  - 两个 roundtrip 测试都补了最小注释，明确它们验证的是“canonical class identity 透明保留”，不是 LIR operand `$` 语法
  - 对 `doc/module_impl/frontend/**` 和阶段 3 代码面重新扫描后，未再发现把 `$` 当作“当前 canonical inner-class spelling”写死的事实源残留
  - 剩余 `$` 命中仅属于允许保留的语法/说明面：
    - GDScript `$Node`
    - LIR operand `$0`
    - Java/测试中的非 canonical helper 名
- 3.3 已跑测试：
  - `DomLirSerializerTest`
  - `DomLirParserTest`

验收细则：

- registry 继续只以 canonical 做 gdcc class key
- `sourceName != canonicalName` 的双名模型继续成立
- `displayName()` 仍然从 canonical 派生
- assignability / superclass walk / source-facing type-meta 恢复逻辑在 `__sub__` canonical 下继续稳定
- DOM/LIR roundtrip 后仍保留 `Outer__sub__Inner`

### 阶段 4：按分层合同重验收 Godot-facing class-name surface，不再引入 alias

实施内容：

- 保持 `entry.c.ftl`、`CGenHelper`、`OperatorInsnGen` 继续直接消费 canonical name
- 不再新增 backend-only alias helper
- 按 surface 分层分别重验收，而不是只看“Godot-facing 全链路”这一个模糊结果：
  - 注册身份面
  - outward metadata 面
  - runtime compare 面
  - dormant / 预留面
  - engine-only 非目标面

验收细则：

- 注册身份面：
  - 生成的 `entry.c` 中，注册名、bind owner class、`godot_object_set_instance(...)` 都使用同一 canonical `Outer__sub__Inner`
  - GDCC 父类注册关系继续使用父类 canonical `__sub__` 名字
- outward metadata 面：
  - typed array hint string 中的 object leaf 输出 canonical `Outer__sub__Inner`
  - typed dictionary hint string 中的 key/value object leaf 输出 canonical `Outer__sub__Inner`
  - engine leaf 与 generic leaf 的 outward metadata 不回归
- runtime compare 面：
  - typed array guard 的 expected class name 与实际注册名一致
  - typed dictionary guard 的 expected class name 与实际注册名一致
  - `gdcc_check_variant_type_object(...)` 的 expected class name 与实际注册名一致
- dormant / 预留面：
  - `BoundMetadata.classNameExpr` 继续保持空值合同，不被顺手改成 canonical
- engine-only 非目标面：
  - native construct / engine method bind lookup 行为不变
- 不新增 `renderGodotFacingClassName(...)` 之类新的 alias 层

执行状态：

- [x] 4.1 重验收注册身份面：注册名、bind owner class、instance attach 继续共用同一 canonical `__sub__` 名字
- [x] 4.2 重验收 outward metadata 面：typed array / typed dictionary object leaf hint 继续直接输出 canonical `__sub__`
- [x] 4.3 重验收 runtime compare 面：typed container guard 与 `gdcc_check_variant_type_object(...)` 继续直接比较 canonical `__sub__`
- [x] 4.4 重验收 dormant / engine-only 面：`BoundMetadata.classNameExpr` 继续保持空值合同，native construct / engine lookup 不回归

阶段 4 执行同步（2026-04-21，已完成）：

- 4.1 已完成：
  - `entry.c.ftl` 已补注释，明确 class registration、bind owner class 与 instance attach 三处 Godot-facing surface 都直接复用同一 canonical class name，不引入 backend-only alias
  - `CCodegenTest` 已新增 mapped-top-level + inner canonical 哨兵，确认：
    - `godot_classdb_register_extension_class5(...)` 对 inner class 直接注册 `RuntimeOuter__sub__Shared` / `RuntimeOuter__sub__Leaf`
    - `class_bind_methods()` 中的 owner `class_name` 继续使用同一 canonical `__sub__` 名字
    - `godot_object_set_instance(...)` 继续使用同一 canonical `__sub__` 名字
    - GDCC 父类注册关系继续写成父类 canonical `RuntimeOuter__sub__Shared`
    - native construct 仍然走最近 native ancestor `RefCounted`，没有把 canonical inner name 误塞回 engine-only construct surface
  - `FrontendLoweringToCProjectBuilderIntegrationTest` 已新增 mapped-top-level + inner class 的 Godot runtime 锚点，确认：
    - mapped outer `RuntimeMappedInnerRuntimeProbe` 可作为 scene node 实际挂进 Godot 场景
    - scene-mounted inner lookup 现在直接用 `var mounted_child = self.get_node_or_null(child_path); if mounted_child == null:` 作为真引擎锚点，不再只靠 `has_node(...)` 绕过 runtime compare
    - mounted inner `Node` 的 `get_class()` / `is_class(...)` 会直接暴露 canonical `RuntimeMappedInnerRuntimeProbe__sub__SceneChild`，不会回落到 source-facing `SceneChild`
    - inner `RefCounted` 继承链的 `get_class()` / `is_class(...)` 会直接使用 canonical `RuntimeMappedInnerRuntimeProbe__sub__Leaf` / `RuntimeMappedInnerRuntimeProbe__sub__Shared`
- 4.1 已跑测试：
  - `CCodegenTest`
  - `FrontendLoweringToCProjectBuilderIntegrationTest`
- 4.2 已完成：
  - `CGenHelper` 已补注释，明确 typed array / typed dictionary outward metadata 会直接透传 engine/GDCC 的注册 class name，包括 canonical inner `Outer__sub__Inner`
  - `CGenHelperTest` 已新增 inner canonical 哨兵，确认：
    - typed array hint string 会直接输出 `RuntimeOuter__sub__Worker`
    - typed dictionary hint string 会直接输出 `RuntimeOuter__sub__Worker;Variant`
    - `BoundMetadata.classNameExpr` 在这些 typed-container surface 上继续保持空 `GD_STATIC_SN(u8"")`，没有把 dormant class slot 顺手填成 canonical
  - 同批次保留并复用既有 negative/回归锚点，继续约束：
    - engine leaf 仍输出 `Node`
    - generic `Array` / `Dictionary` 仍保持未 typed 的 outward metadata
    - nested typed leaf 继续按既有合同 fail-fast
- 4.3 已完成：
  - `CGenHelper` 已补注释，明确 typed-container runtime guard 的 object leaf 继续直接比较注册时使用的 exact class name
  - `CGenHelperTest` 已新增 inner canonical guard 哨兵，确认 typed array / typed dictionary 的 expected class name 都是 `GD_STATIC_SN(u8"RuntimeOuter__sub__Worker")`
  - `test_suite` 已新增 resource-driven runtime 锚点，确认 typed array / typed dictionary 的 GDCC inner canonical object leaf 不再只停留在 helper/codegen 断言：
    - validation 脚本先从编译产物拿到真实 inner object，再用 Godot 原生 `Array(...)` / `Dictionary(...)` typed constructor 构造 exact container
    - `get_typed_class_name()` / `get_typed_value_class_name()` 会继续返回 canonical `Outer__sub__Inner` 风格的 `__sub__` 名字
    - method 参数/返回与 property get/set 的 runtime guard 都继续按 exact canonical class name 锁定行为，没有回落到 source-facing inner name
  - `OperatorInsnGen` 已补注释，明确：
    - GDCC object 只做 exact canonical match
    - engine/native object 继续保留 subclass-compatible fallback
  - `COperatorInsnGenTest` 已新增 GDCC inner canonical 哨兵，确认 `Variant -> Object` type check 对 `RuntimeOuter__sub__Worker` 不会生成 engine-only subclass fallback
- 4.4 已完成：
  - dormant / 预留面：
    - `BoundMetadata.classNameExpr` 的空值合同已被新的 typed-container inner-canonical 哨兵重新锚定
  - engine-only 非目标面：
    - `CCodegenTest` 的注册身份面哨兵同步确认 inner class create-instance 仍然构造最近 native ancestor `RefCounted`
    - `COperatorInsnGenTest` 中既有 engine object subtype test 继续通过，说明 engine-only subclass fallback 没有被 GDCC inner canonical 路线误伤
  - 阶段 4 范围内未新增 `renderGodotFacingClassName(...)` 或等价 alias helper
- 4.2/4.3/4.4 已跑测试：
  - `CGenHelperTest`
  - `COperatorInsnGenTest`
  - `CCodegenTest`
  - `FrontendLoweringToCProjectBuilderIntegrationTest`

### 阶段 5：重基线 backend 内部 C 符号链路与代码生成断言

实施内容：

- 接受 backend 内部 C 符号继续直接沿用 canonical name
- 把所有依赖 `$` canonical 的 codegen 断言改为 `__sub__`
- 重点回归以下路径：
  - C struct / helper / function 命名
  - property / method adapter owner class 断言
  - object cast / property resolution / type check 中的 canonical name 比较

验收细则：

- 生成的 C 符号不再出现 `$`
- backend 不因 inner canonical 从 `$` 变成 `__sub__` 而引入新的 symbol alias 层
- 与 engine/native object type 相关的既有行为保持不变

执行状态：

- [x] 5.1 backend canonical-consumer 测试已重基线到 `__sub__`
  - `CBodyBuilderPhaseCTest` 的 inner-class upcast / fail-fast case 已切到 `Outer__sub__...`
  - `PropertyResolverParityTest` 与 `MethodResolverParityTest` 的 mapped-canonical owner 断言已切到 `RuntimeOuter__sub__...`
  - 已新增负向测试，明确 `Leaf` / `Shared` 这类 source-facing inner 名不会被 backend 当成 global alias
  - 保留 `$self`、`$obj` 这类 C 局部槽位命名不变，避免把非目标 `$` 噪音误计入迁移面
- [x] 5.2 backend 第 5 阶段哨兵测试已通过
  - `CGenHelperTest`
  - `COperatorInsnGenTest`
  - `CCodegenTest`
  - `CBodyBuilderPhaseCTest`
  - `PropertyResolverParityTest`
  - `MethodResolverParityTest`

### 阶段 6：测试与文档同步收敛

实施内容：

- 前端/作用域/LIR/后端测试统一重基线
- 更新以下事实源文档中的 `$` 合同：
  - `runtime_name_mapping_implementation.md`
  - `inner_class_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `scope_type_resolver_implementation.md`
- 清理 `test_suite_engine_integration_known_limits.md` 中与旧 alias 路线绑定的计划描述

验收细则：

- 事实源文档之间不再互相矛盾
- 新文档统一表述：
  - inner canonical separator 是 `__sub__`
- `__sub__` 是 gdcc 保留序列
- downstream 继续 canonical-only
- 测试名称、断言文本、fixture 注释里不再保留过时的 `$` canonical 结论

执行状态：

- [x] 6.1 事实源文档残留已重新审计
  - `runtime_name_mapping_implementation.md`、`inner_class_implementation.md`、`superclass_canonical_name_contract.md` 已与 `__sub__` 合同保持一致，无需额外改写
  - `scope_type_resolver_implementation.md` 中剩余的 ``$ canonical raw text`` 已修正为 ``__sub__ canonical raw text``
- [x] 6.2 known-limits 文档已收口为 symptom record
  - `test_suite_engine_integration_known_limits.md` 的第 7 条不再承载历史性替名方案或实施步骤
  - 文案改为显式指向 `Godot-facing class-name surface` 的分层合同与专项计划文档

## 最小实施顺序建议

建议按以下顺序落地，避免半迁移：

1. 先建立哨兵锚点，明确哪些失败属于 canonical 生成、哪些属于注册身份面、哪些属于 metadata / runtime compare 面
2. 再加 `__sub__` guard rail 和 mapping 输入校验
3. 再改 inner canonical 生成与 `extends` canonical-text 诊断
4. 之后按“注册身份面 -> outward metadata 面 -> runtime compare 面”的顺序推进和验收
5. 最后再处理机械基线、文档统一替换和 Godot-facing integration 回归

不建议的顺序：

- 只改 canonical 生成，不改 `extends` 诊断
- 只改实现，不先封住 `__sub__` 输入边界
- 先全局把 `$` 文本替换成 `__sub__`，再试图从大面积失败里找真实回归
- 只看 class registration 成功，不验证 metadata / runtime compare 面

## 最小验收矩阵

至少要覆盖以下样本：

1. top-level 合法类名
   - 例如 `MyNode extends Node`
   - 预期：canonical 仍为 `MyNode`

2. 普通 inner class
   - 例如 `Outer` 的 `Inner`
   - 预期：canonical 为 `Outer__sub__Inner`

3. mapped top-level + inner class
   - 例如 top-level 映射为 `RuntimeOuter`
   - 预期：inner canonical 为 `RuntimeOuter__sub__Inner`

4. source-facing extends
   - `extends Inner`
   - 预期：继续走 source-facing local name

5. canonical-text extends negative path
   - `extends Outer__sub__Inner`
   - 预期：显式 diagnostic，提示 canonical spelling 不属于 frontend extends syntax

6. reserved-sequence negative path
   - 源码类名或 mapping value 含 `__sub__`
   - 预期：被 boundary guard rail 拒绝

7. 注册身份面
   - inner class scene integration
   - 预期：不再出现 `not a valid class identifier`
   - 预期：注册名、bind owner class、instance attach 使用同一 canonical `__sub__` 名字

8. outward metadata 面
   - `Array[Outer__sub__Inner]`
   - `Dictionary[Outer__sub__Inner, Variant]`
   - 预期：hint string 正确输出 canonical `__sub__` object leaf
   - 预期：不要求通过这组样本同时替代 runtime compare 验收

9. runtime compare 面
   - typed array guard
   - typed dictionary guard
   - `Variant -> Object` runtime type check
   - 预期：expected class name 与实际注册名一致
   - 预期：engine object subclass-match 行为不回归

## 风险与应对

### 风险 1：只改 separator，不封保留序列

后果：

- `__sub__` 会退化成普通源码可写文本
- canonical/source 空间重新相交
- 顶层类与 inner class 可能发生 identity 冲突

应对：

- 必须把 `__sub__` 作为 gdcc 规则写入输入边界
- 必须同时覆盖源码类名与 top-level canonical mapping value

### 风险 2：只改 frontend，不重基线文档/测试

后果：

- 代码行为与事实源文档冲突
- 断言仍然围绕 `$` spelling，导致大面积假红

应对：

- 把文档更新视为同一迁移的一部分，而不是收尾工作
- 先全局盘点 `$` canonical 文本，再按模块重基线

### 风险 3：机械基线噪音掩盖真实行为回归

后果：

- 大量 `$ -> __sub__` 文本替换会把测试失败、生成代码 diff、文档更新混成同一种“红”
- reviewer 无法判断当前失败是 canonical 合同断裂、Godot-facing 注册失配，还是单纯旧字面量没更新

应对：

- 先建立语义哨兵锚点，再做大面积机械替换
- 每一批只允许一个语义面发生真实行为变化
- 哨兵集先行，扩散集后置
- 机械 display / 文本 / 文档噪音统一放到最后一批

### 风险 4：看到 Godot 注册成功就误判迁移完成

后果：

- outward metadata 面
- runtime compare 面

这些路径仍可能和注册名不一致

应对：

- Godot-facing 验收必须按“注册身份面 / outward metadata 面 / runtime compare 面”分别给出结论

### 风险 5：把 `__sub__` 继续扩散成新的多名模型

后果：

- backend 又引入一层 Godot alias
- frontend / backend 对 canonical 的理解再次分叉

应对：

- 本轮只允许保留两层持久化名字：
  - `sourceName`
- `canonicalName`
- Godot-facing surface 继续直接使用 canonical

### 风险 6：backend 内部 C 符号链路的后续可移植性

当前仓库里 canonical name 也会进入生成的 C 符号名。把 `$` 改为 `__sub__` 后，Godot-facing identifier 问题会消失，但 backend 仍然需要对以下事实保持清醒：

- 本轮解决的是 Godot class identifier 约束
- 不是一次完整的 C symbol portability 设计

应对：

- 本轮先保持“canonical 直接进入 backend 内部符号链路”的既有合同
- 若未来 toolchain / 语言边界对内部 `__` 符号再提出新约束，再单独立项做 C symbol mangling

## 明确保持不改的边界

下列事实在迁移完成后仍必须成立：

- downstream 继续 canonical-only
- `ClassDef.getName()` / `LirClassDef.getName()` 继续返回 canonical
- `ClassRegistry` 继续只以 canonical 注册 gdcc class
- caller-side remap-on-miss 继续只处理 top-level mapping
- inner class 不建立全局 source alias
- backend 不引入独立 Godot alias 层

## 最终目标

迁移完成后的系统应满足以下最终状态：

- inner canonical spelling 从 `Outer$Inner` 迁移为 `Outer__sub__Inner`
- `__sub__` 被明确视为 gdcc 保留 canonical separator
- frontend source-facing 语义继续不接受 canonical inner spelling
- Godot-facing class-name surface 继续直接消费 canonical，但 canonical 现在已是 Godot 可接受标识符
- issue 7 不再需要 backend-only 修补层，而是通过 canonical contract 一次性解决
