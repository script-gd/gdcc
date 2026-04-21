# GDCC-facing Class Name 合同

> 本文档作为 gdcc class name 的 canonical/source 边界、Godot-facing class-name surface 分层合同与 backend symbol carry-through 边界的长期事实源。本文档替代原 `godot_facing_class_name_surface_plan.md`，不再保留实施阶段、验收流水账或已完成记录。

## 文档状态

- 状态：事实源维护中（`__sub__` 保留序列、top-level mapping 输入边界、source-facing `extends` 边界、Godot-facing surface 分层合同与 backend symbol carry-through 已冻结）
- 更新时间：2026-04-21
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/lir/**`
  - `src/main/java/dev/superice/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
  - `src/test/java/dev/superice/gdcc/**`
  - `src/test/test_suite/**`
  - `doc/module_impl/frontend/**`
- 关联文档：
  - `runtime_name_mapping_implementation.md`
  - `inner_class_implementation.md`
  - `superclass_canonical_name_contract.md`
  - `scope_type_resolver_implementation.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`
- 明确非目标：
  - 不恢复持久化三名模型，不新增 `runtimeName`
  - 不把 `sourceName` 重新带回 backend / LIR / registry
  - 不为 inner class 建立全局 source alias
  - 不把 canonical `__sub__` spelling 反向暴露成 frontend `extends` 语义
  - 不在这里启用 outward property info 的 `class_name` 槽位
  - 不在当前支持 path-based `extends`、autoload superclass、global-script-class superclass 绑定
  - 不在当前合同内重做 C symbol mangling 或跨 toolchain portability 设计

---

## 1. 问题边界与命名模型

### 1.1 当前系统只承认两层持久化名字

当前仓库中，gdcc class identity 只允许持久化为两层：

- `sourceName`
  - 只服务 frontend source-facing lookup、局部显示与早期诊断
- `canonicalName`
  - 作为 frontend steady-state、scope、registry、LIR、backend 与 Godot-facing surface 的统一 identity

后续工程不得再把这两层偷偷扩写成三层，更不得在 backend 再补一层专供 Godot 使用的 alias。

### 1.2 `__sub__` 是 gdcc 保留 canonical separator

inner class canonical spelling 已冻结为：

- `parentCanonicalName + "__sub__" + innerSourceName`

这里的 `__sub__` 是 gdcc 自己定义的保留序列，不是 Godot 语法，也不是 source-facing class name 语法的一部分。

它成立的前提不是“分隔符更长”，而是 source/canonical 空间继续保持不相交：

1. 用户声明的 top-level / inner `sourceName` 不允许包含 `__sub__`
2. `topLevelCanonicalNameMap` 的 key / value 不允许包含 `__sub__`
3. inner canonical 只能由 frontend 以 `parentCanonicalName + "__sub__" + sourceName` 派生

只要这三条同时成立，任何带 `__sub__` 的 gdcc class name 都可以被稳定识别为 canonical inner spelling，而不是合法源码类名。

---

## 2. 冻结合同

### 2.1 source boundary 与 mapping boundary

当前边界已经冻结为：

- top-level gdcc class 在无 mapping 时满足 `sourceName == canonicalName`
- top-level gdcc class 若命中 `topLevelCanonicalNameMap`，则允许 `sourceName != canonicalName`
- inner class 的 `sourceName` 继续是局部源码名
- inner class 的 `canonicalName` 固定派生为 `Outer__sub__Inner...`

对 `__sub__` 的输入边界也已经冻结：

- 用户源码类名违规：
  - 发 `sema.class_skeleton` diagnostic
  - 跳过坏 subtree
  - 保留同一 source unit 中其他合法 sibling 的继续发现
- `FrontendModule` 外部注入的 `topLevelCanonicalNameMap` 违规：
  - 在 public API boundary fail-fast
  - 不允许把坏 canonical 延后泄漏到 registry / backend

### 2.2 source-facing `extends` 协议

header `extends` 继续是 frontend 自己的 source-facing 绑定协议，而不是 canonical text 输入口。

当前 accepted surface：

- 当前编译目标内 lexical 可见的 gdcc inner class `sourceName`
- 当前编译目标内的 gdcc top-level class `sourceName`
- `sourceName == canonicalName` 的 engine/native class name
- 缺省父类场景下的隐式默认 superclass

当前 rejected surface：

- `extends Outer__sub__Inner` 这类 canonical inner spelling
- 任何把 canonical raw text 当成 frontend 作者可写语法的写法

后续工程不得把 canonical `__sub__` spelling 偷偷转正为 `extends` 新语法，也不得为 inner class 建立“全局 source alias”来绕开这一边界。

### 2.3 downstream canonical-only identity

下游 steady-state 继续只消费 canonical identity，不再保留 source-facing class spelling：

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
- backend canonical consumers
  - `CBodyBuilder`
  - `BackendPropertyAccessResolver`
  - `LoadPropertyInsnGen`
  - 其他仅消费 `GdObjectType.getTypeName()` 的路径

这层合同包含两个关键要求：

- canonical class name 必须继续被当作 opaque identity，而不是再解释回 source-facing 文本
- `displayName()` 若需要用户可见文本，仍然从 canonical identity 派生，而不是新增持久化字段

### 2.4 backend symbol carry-through

backend 内部 C 符号链路继续直接携带 canonical name，包括 `__sub__`：

- `entry.c.ftl`
- `entry.h.ftl`
- `func.ftl`

这条边界当前只表达一个事实：

- Godot-facing class identifier 问题已经通过 canonical contract 收口
- backend 尚未引入额外的 symbol alias 或 mangling 层

若未来工具链或平台对内部 `__` 符号再提出新约束，应另行立项做 C symbol portability 设计，而不是回退当前 class-name contract。

### 2.5 非目标 `$` surface 保持不变

这条合同只约束 canonical class identity，不涉及其他仍合法存在的 `$` surface，例如：

- GDScript `$Node`
- LIR operand `$0`
- backend / test helper 中非 canonical 的局部符号命名

后续工程不得把“canonical separator 已迁移”为理由，误伤这些本来就不属于 class identity 的 `$` 语义。

---

## 3. Godot-facing class-name surface 分层合同

Godot-facing surface 继续直接消费 canonical class name，但这不是一个单一平面。后续工程必须继续按分层合同分别分析和验收，而不是把所有“碰到类名的地方”混写成一个结论。

### 3.1 注册身份面

这层 surface 负责把 gdcc class identity 注册进 Godot，并让后续绑定与 attach 使用同一个名字：

- extension class 注册
  - `template_451/entry.c.ftl`
  - `godot_classdb_register_extension_class5(...)`
- method / property owner class
  - `template_451/entry.c.ftl`
  - `godot_StringName* class_name = GD_STATIC_SN(...)`
- instance attach
  - `template_451/entry.c.ftl`
  - `godot_object_set_instance(...)`

当前合同：

- 注册名、bind owner class、instance attach 必须使用同一个 canonical identity
- GDCC 父类注册关系继续写成父类 canonical name
- scene-mounted gdcc inner `Node` / `RefCounted` 的 runtime class identity 继续暴露 canonical `Outer__sub__Inner`
- native construct 继续只使用最近 native ancestor 名，不回退到 gdcc inner canonical

### 3.2 outward metadata 面

这层 surface 负责把 object leaf 身份编码进 Godot 可见 metadata，而不是负责类注册：

- typed array hint string
  - `CGenHelper.renderContainerHintAtom(...)`
- typed dictionary hint string
  - `CGenHelper.renderContainerHintAtom(...)`

当前合同：

- typed array / typed dictionary object leaf 继续直接输出 canonical `Outer__sub__Inner`
- engine leaf 与 generic leaf 的 outward grammar 保持各自既有规则
- nested typed leaf 继续按当前 ABI 边界 fail-fast
- `BoundMetadata.classNameExpr` 继续保持空值；typed-container object leaf identity 停留在 `hint_string`

### 3.3 runtime compare 面

这层 surface 负责在 runtime 比较“编译期期望类名”与“外部返回类名”：

- typed array runtime guard
  - `CGenHelper.renderTypedArrayGuardClassNameExpr(...)`
- typed dictionary runtime guard
  - `CGenHelper.renderTypedDictionaryGuardClassNameExpr(...)`
- `Variant -> Object` runtime type check
  - `OperatorInsnGen.renderVariantObjectTypeCheckExpr(...)`
  - `gdcc_check_variant_type_object(...)`

当前合同：

- typed array / typed dictionary guard 的 expected class name 必须与注册时实际 class name 完全一致
- typed container guard 继续是 exact class-name compare surface
- `Variant -> Object` check 继续使用 canonical expected class name，并保留 subclass-compatible fallback
- engine object 与 GDCC object 的 subclass-match 行为都必须继续与 ClassDB 继承关系一致

### 3.4 dormant / engine-only 面

有两类 surface 仍然需要明确保持不变：

- dormant / 预留面
  - `BoundMetadata.classNameExpr`
  - `CGenHelper.renderBoundMetadata(...)`
  - `template_451/entry.h.ftl`
- engine-only 面
  - `godot_classdb_construct_object2(...)`
  - engine method bind lookup

当前合同：

- `BoundMetadata.classNameExpr` 继续保持空值，不得顺手填成 canonical
- native construct / engine lookup 继续只使用 engine/native owner class 名

---

## 4. 后续工程最小回归锚点

后续若继续改动 class-name contract，至少必须重新锚定以下事实：

1. top-level 合法类名
   - 例如 `class_name MyNode`
   - 预期：canonical 仍为 `MyNode`

2. 普通 inner class
   - 例如 `Outer` 的 `Inner`
   - 预期：canonical 为 `Outer__sub__Inner`

3. mapped top-level + inner class
   - 例如 top-level canonical mapping 命中 `RuntimeOuter`
   - 预期：inner canonical 为 `RuntimeOuter__sub__Inner`

4. source-facing `extends`
   - `extends Inner`
   - 预期：继续绑定 source-facing local name

5. canonical-text `extends` negative path
   - `extends Outer__sub__Inner`
   - 预期：明确 diagnostic，指出 canonical `__sub__` spelling 不属于 frontend `extends` 语义

6. reserved-sequence negative path
   - 源码类名或 mapping key / value 含 `__sub__`
   - 预期：在 source boundary 或 public API boundary 被拒绝

7. 注册身份面
   - inner class scene/runtime integration
   - 预期：注册名、bind owner class、instance attach 使用同一 canonical `__sub__` 名字

8. outward metadata 面
   - typed array / typed dictionary object leaf
   - 预期：hint string 正确输出 canonical `__sub__` object leaf

9. runtime compare 面
   - typed array guard
   - typed dictionary guard
   - `Variant -> Object` runtime type check
   - 预期：expected class name 与实际注册名一致；subclass-match 语义不回归

---

## 5. 稳定锚点测试

下列测试当前共同充当这份合同的稳定回归锚点：

- frontend 输入边界与 header 协议
  - `FrontendModuleTest`
  - `FrontendClassHeaderDiscoveryTest`
  - `FrontendClassSkeletonTest`
  - `FrontendSemanticAnalyzerFrameworkTest`
- scope / registry / LIR canonical identity
  - `ClassRegistryGdccTest`
  - `ScopeTypeResolverTest`
  - `DomLirParserTest`
  - `DomLirSerializerTest`
- backend metadata / runtime compare / codegen
  - `CGenHelperTest`
  - `COperatorInsnGenTest`
  - `CCodegenTest`
  - `CBodyBuilderPhaseCTest`
  - `PropertyResolverParityTest`
  - `MethodResolverParityTest`
- 真引擎 / resource 锚点
  - `FrontendLoweringToCProjectBuilderIntegrationTest`
  - `src/test/test_suite/unit_test/validation/abi/typed_array/gdcc_inner_object_roundtrip.gd`
  - `src/test/test_suite/unit_test/validation/abi/typed_dictionary/gdcc_inner_object_roundtrip.gd`
  - `src/test/test_suite/unit_test/script/scene/nested_node_refcounted_scene.gd`

这些测试的职责不是重复记录迁移过程，而是持续证明当前 steady-state 仍然成立。

---

## 6. 后续工程禁区

后续若继续扩展这条链路，必须避免以下设计回退：

- 只看 class registration 成功，就误判整个 Godot-facing class-name surface 已收口
- 把 outward metadata 正确误写成 runtime compare 也已正确
- 放松 `__sub__` 输入边界，重新让 source/canonical 空间相交
- 为 backend 或 Godot-facing surface 新增第三套持久化名字层
- 把 inner class source-facing 可见性扩写成全局 alias
- 在未单独立项的情况下启用 `BoundMetadata.classNameExpr`
- 把 C symbol portability 问题与当前 class-name contract 混成同一个修复议题

只要系统继续满足本文件中的边界，inner canonical name 就可以保持 `Outer__sub__Inner`，并同时作为 gdcc 内部 identity 与 Godot-facing class identifier 使用，而不需要额外 alias 层。
