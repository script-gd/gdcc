# GDCC-facing Class Name 合同

> 本文档作为 gdcc class name 的 canonical/source 边界、Godot-facing class-name surface 分层合同与 backend symbol carry-through 边界的长期事实源。本文档替代原 `godot_facing_class_name_surface_plan.md`，不再保留实施阶段、验收流水账或已完成记录。

## 文档状态

- 状态：事实源维护中（`__sub__` 保留序列、top-level mapping 输入边界、source-facing `extends` 边界、Godot-facing surface 分层合同与 backend symbol carry-through 已冻结；并明确 `cIdentifier()` 将 `__sub__` 折叠为 `_sub_`）
- 更新时间：2026-07-28
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/**`
  - `src/main/java/gd/script/gdcc/scope/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
  - `src/test/java/gd/script/gdcc/**`
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

### 1.3 `_gdcc_coro_state_` 是 compiler-owned class 级保留前缀

协程隐藏状态类（见 `frontend_await_implementation.md` §5）的 canonical name 派生公式冻结为：

- `_gdcc_coro_state_<canonicalClass>__coro__<func>`

其中 `<canonicalClass>` 是宿主类已经派生完成的 canonical name（宿主为 inner class 时自然携带 `__sub__` 拼接），`<func>` 是 LIR 函数名。分隔符 `__coro__` 是 gdcc 保留序列：只要 canonicalClass 一侧不含 `__coro__`，第一个 `__coro__` 出现位置即分隔点，公式对 (class, func) 单射（单下划线拼接会碰撞：`Foo`+`bar_baz` 与 `Foo_bar`+`baz`）。

冻结约束：

1. `_gdcc_coro_state_` 是 **class 级**保留前缀（既有 `RESERVED_PREFIXES` 只覆盖 member 级）；用户声明的 top-level / inner class `sourceName` 一旦以该前缀开头，skeleton 必须发 `sema.class_skeleton` 并跳过该 subtree，与 `__sub__` 违规同策略（§2.1）。
2. 用户声明的 top-level / inner class `sourceName` 与 `topLevelCanonicalNameMap` 的 key / value 均不得包含 `__coro__`（与 `__sub__` 相同的输入边界，§1.2 / §2.1）；函数名不受此限——分隔点由 class 侧的不含性唯一确定。
3. 隐藏状态类只有 canonical name：无 `sourceName`、不可被 source-facing `extends` 引用、不进入 source-facing registry，也不进入 `module.classDefs` 的用户类注册循环；由 backend 按 `is_coroutine="true"` 函数集合单独生成。
4. 状态类 identity 继续服从 §2.3 downstream canonical-only 合同；C 标识符表面经既有 `cIdentifier()` 清洗，不新增清洗规则（§2.4）。
5. hidden state 的 Godot instance binding 只使用模块私有 coroutine token，payload 为公共 header；`class_library` binding 不参与状态识别。wrapper 仍由独立 `object_set_instance` 保存，故 canonical identity 与 notification/free_instance ABI 不变。

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

对 compiler-owned class 级保留前缀 `_gdcc_coro_state_` 与保留序列 `__coro__`（§1.3）的输入边界同样冻结：用户源码类名命中该前缀或包含该序列时，按上面「用户源码类名违规」的同一条路径处理；`topLevelCanonicalNameMap` 包含该序列时按上面 mapping 违规的同一条路径处理。

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

- 协程隐藏状态类（`_gdcc_coro_state_` 前缀，§1.3）从创建起就只有 canonical identity，是本合同的天然实例
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

backend 对 canonical class name 的消费必须区分两层 surface，**不得**再假设“所有 C 符号都原样保留 `__sub__`”：

1. **Godot / identity surface（保留 canonical `__sub__`）**
   - 注册名、`GD_STATIC_SN(...)`、bind owner class、instance attach
   - typed array / typed dictionary object leaf 的 hint string
   - GDCC wrapper struct / 部分 layout helper 等仍直接拼接 canonical name 的路径
     （例如 `Outer__sub__Inner`、`Outer__sub__Inner_object_ptr`）
   - 主要模板：`entry.c.ftl`、`entry.h.ftl`、`func.ftl`

2. **C identifier surface（经 `GodotBindingSupport.cIdentifier()` 清洗）**
   - 规则：非法字符 → `_`，且**连续下划线折叠为单个 `_`**
   - 因此 inner canonical `Outer__sub__Inner` 在 C 标识符中变为 `Outer_sub_Inner`
   - 当前强制走该路径的 surface 包括但不限于：
     - fat pointer typedef：`gdcc_Outer_sub_Inner_fat_ptr`
     - fat pointer per-type / upcast helper：
       `gdcc_Outer_sub_Child_fat_ptr_upcast_to_Outer_sub_GrandParent`
   - 实现锚点：
     - `GodotBindingSupport.cIdentifier(...)`
     - `ObjectFatPtrSpec.cIdentifier` / `ObjectFatPtrSpec.fatPtrTypeName`
     - `ObjectFatPtrUpcastSpec.forPair(...).helperName`
     - `CBodyBuilder.renderObjectFatPtrUpcastHelperName(...)` 必须与 upcast spec 使用同一套 `cIdentifier`

这条边界表达两个冻结事实：

- Godot-facing class identifier 问题已经通过 canonical contract 收口（identity 层继续是 `Outer__sub__Inner`）
- backend **没有**为 class identity 引入额外 alias 字段；但 fat-pointer 等必须合法作 C 标识符的符号会经 `cIdentifier()` 做机械清洗，**不是**新的语义层

此外还有第三层 static storage/codegen symbol surface：

3. **static 符号 surface（raw canonical 拼接，不经 `cIdentifier()`）**
   - static backing 变量（`gdcc_static_<Class>_<name>`）与 static defaults/initializers 入口
     （`gdcc_static_defaults_<Class>` / `gdcc_static_initializers_<Class>`）
     直接拼接 raw canonical class name，与 `<Class>_object_ptr` 同一 raw 拼接 surface；
     static 清理没有独立 file-scope 符号，由 `deinitialize()` 按初始化逆序内联销毁
   - 该 surface 不提供全局单射保证：raw 拼接存在理论歧义（如 `A_B.c` vs `A.B_c`），与存量
     `${class}_${func}` 函数命名面（`call_<Class>_<argc>_arg_ret_<type>` 等同样不含完整方法名）
     的已知碰撞风险同级；主防线是 `CCodegen.validateFileScopeSymbolsDisjoint` 的全模块符号冲突校验，
     冲突时报告双方符号与来源并 fail-fast
   - load/store gen、module lifecycle 模板与冲突校验共享 `CGenHelper` 发布的同一命名公式，
     任何消费端不得自行重新拼写

后续开发约束：

- 写测试时，Godot 字符串 / registry 期望用 `__sub__`；fat pointer 类型名 / upcast helper 期望用 `_sub_`
- static backing/init 符号期望 raw canonical 拼接（保留 `__sub__`），不得对其套用 `cIdentifier()`
- call site 与 declaration site 的 helper 名必须同形；禁止一边 `cIdentifier`、一边 `canonicalClassName`
- 若未来工具链或平台对内部 `__` / `_sub_` 再提出新约束，应另行立项做 C symbol portability 设计，而不是回退当前 class-name contract

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
- typed-container 路径的 `BoundMetadata.classNameExpr` 继续保持空值；typed-container object leaf identity 停留在 `hint_string`

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

- typed-container 路径的 `BoundMetadata.classNameExpr` 继续保持空值，不得顺手填成 canonical
- 裸 `@export` Object property 的 `class_name` 槽携带的是 property 类型类名（export 注解合同），不属于本合同的 container leaf identity 通道
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
- 把 typed-container 路径的 `BoundMetadata.classNameExpr` 填上 leaf identity（裸 `@export` Object property 的 property 类型类名是 export 注解合同批准的既定例外）
- 把 C symbol portability 问题与当前 class-name contract 混成同一个修复议题

只要系统继续满足本文件中的边界，inner canonical name 就可以保持 `Outer__sub__Inner`，并同时作为 gdcc 内部 identity 与 Godot-facing class identifier 使用，而不需要额外 alias 层。
