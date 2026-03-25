# Superclass Canonical Name 合同

> 本文档作为 superclass canonical/source 边界、header `extends` 绑定协议与 downstream 消费约束的长期事实源。本文档替代原 `superclass_canonical_name_plan.md`，不再保留实施阶段、进度记录或回滚流水账。

## 文档状态

- 状态：事实源维护中（frontend relation 双名、mapped top-level canonical header identity、canonical superclass contract、header binding boundary 已冻结）
- 更新时间：2026-03-24
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/resolver/**`
  - `src/main/java/dev/superice/gdcc/lir/**`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/**`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/scope/**`
  - `src/test/java/dev/superice/gdcc/lir/**`
  - `src/test/java/dev/superice/gdcc/backend/**`
- 关联文档：
  - `frontend_rules.md`
  - `scope_type_resolver_implementation.md`
  - `inner_class_implementation.md`
  - `scope_architecture_refactor_plan.md`
  - `scope_analyzer_implementation.md`
  - `../backend/explicit_c_inheritance_layout.md`
- 明确非目标：
  - 不重命名 `ClassDef#getSuperName()`、`LirClassDef#setSuperName(...)` 等既有接口
  - 不把 superclass 的 `sourceName` / `declaredText` 放进 `LirClassDef`
  - 不在本轮引入 `cSymbolName`、mangling、额外 backend symbol alias
  - 不把 header superclass 绑定重写成 shared declared-type resolver 的一部分
  - 不在当前 MVP 支持 path-based `extends`、autoload superclass、global-script-class superclass 绑定
  - 不在当前 MVP 支持跨多个 gdcc module 的 superclass 绑定
  - 不把 canonical `$` spelling 反向暴露成 frontend `extends` 语义

---

## 1. 目标与职责边界

当前仓库已经冻结如下边界：

- frontend steady-state 负责保留 superclass 的 `sourceName` 与 `canonicalName`
- `ClassDef` / `LirClassDef` / LIR / backend 只消费 canonical superclass name
- human-facing 的 header 原始写法只允许停留在 discovery / diagnostic 需要的短生命周期对象中

这意味着 superclass 信息被明确拆成两层：

- source-facing layer：
  - 只服务 frontend lexical 语义、source-local relation 和早期诊断
- canonical layer：
  - 作为 registry、LIR、serializer/parser 与 backend 的统一身份

后续工程不得再把这两层重新混回单一字符串字段，也不得把 backend/registry 的 canonical identity 当作 frontend `extends` 写法来解释。

---

## 2. 冻结合同

### 2.1 `ClassDef` / `LirClassDef`

- `ClassDef#getName()` 返回 canonical class name
- `ClassDef#getSuperName()` 返回 canonical superclass name
- `LirClassDef.superName` 虽保留旧字段名，但语义已经冻结为 canonical superclass name
- 空字符串仍表示“没有更高可追溯的 superclass”

任何新代码若通过 `ClassDef` 读取 superclass，必须把它当作 canonical identity，而不是 header source text。

### 2.2 Frontend relation

- `FrontendSuperClassRef` 是 frontend steady-state 中唯一承载 superclass 双名的稳定载体
- `FrontendSourceClassRelation` 与 `FrontendInnerClassRelation` 保留：
  - superclass `sourceName`
  - superclass `canonicalName`
- top-level gdcc class 在无 mapping 时满足 `sourceName == canonicalName`
- top-level gdcc class 若命中 runtime-name mapping，则 relation 会保留 `sourceName != canonicalName`
- inner class 继续满足：
  - `canonicalName = Outer$Inner...`
  - `sourceName = 源代码中的局部类名`

`FrontendSuperClassRef.canonicalName` 只服务内部 canonical contract，不构成 frontend 作者可见的 superclass spelling 合同。

### 2.3 `rawExtendsText`

- `rawExtendsText` 只允许存在于 header discovery / rejected-header / diagnostic 需要的短生命周期对象中
- 它不是 `ClassDef`、`LirClassDef` 或 relation 的长期字段
- skeleton 之后的 phase 若只有 `ClassDef`，默认只能看到 canonical superclass name

### 2.4 DOM / LIR

- 从当前版本起，LIR 是面向后端的 canonical IR
- DOM/LIR 序列化中的 `super` 属性统一表示 canonical superclass name
- parser / serializer / LIR tests 都必须围绕该语义建立

### 2.5 C backend

- backend 不对 superclass 名字再做 mangling
- canonical name 中的 `$` 允许原样进入 backend 标识符与模板链路
- 该前提仅绑定当前 `zig cc` 工具链；若未来工具链或平台约束变化，应另行立项处理 backend 兼容性

---

## 3. Header `extends` 绑定协议

header `extends` 仍是 frontend 自己的 source-facing 绑定协议，不等同于 shared declared-type resolver。

### 3.1 当前支持面

当前 accepted header super 只允许以下来源进入 canonical contract：

- 当前编译目标内唯一 gdcc module 的 lexical 可见 inner class `sourceName`
- 当前编译目标内唯一 gdcc module 的 top-level class `sourceName`
- `sourceName == canonicalName` 的 engine/native class 名字
- 缺省父类场景下的隐式默认 superclass

### 3.2 当前明确不支持的来源

以下 raw text 必须在 skeleton/header phase 发显式 diagnostic，并拒绝进入 accepted subtree：

- canonical `$` spelling
- path-based `extends`
- autoload / singleton superclass
- 当前 module 之外的 gdcc superclass source
- global-script-class superclass 绑定
- builtin type
- global enum
- 其他 unresolved raw text

### 3.3 当前绑定实现事实

- `FrontendClassSkeletonBuilder` 通过结构化 `HeaderSuperBindingDecision` 统一发布 header-super 绑定结果
- accepted freeze、unsupported diagnostic、inheritance-cycle walk 与 rejected-super 传播都消费同一结构化结果
- 不允许再回退到多处各自拼接字符串启发式

---

## 4. Frontend / Scope / Registry 集成事实

### 4.1 Frontend relation 与 `ClassDef`

- relation 保留 superclass 双名
- `classDef.getSuperName()` 与 `relation.superClassRef().canonicalName()` 必须一致
- later frontend phases 若需要 source-facing superclass 信息，必须显式消费 relation，而不是试图从 `ClassDef` 反推

### 4.2 `ClassRegistry`

- gdcc class 只按 canonical name 注册
- inner class 的 source-facing name 只通过 side-table 与 lexical type namespace 恢复
- global namespace 不为 inner class 建立 `sourceName` alias

### 4.3 与 shared resolver 的边界

- header `extends` 解析仍独立于 shared declared-type resolver
- member declared type 的覆盖面仍强于 header `extends`
- 后续若要让 header inheritance 与 shared resolver 严格对齐，必须单独设计新的 super path 绑定协议，不能默认复用当前字符串字段

---

## 5. 诊断与恢复合同

当前 superclass 相关恢复规则已经冻结为：

- unsupported superclass source 必须发 `sema.class_skeleton` diagnostic
- 坏 subtree 必须被拒绝，但同一 module 中其他合法 subtree 仍继续工作
- inheritance cycle 必须发 `sema.inheritance_cycle` diagnostic，并拒绝相关 subtree
- 不允许 silent fallback
- 不允许把 raw text 直接写入 canonical 字段伪装成“已成功 canonicalize”
- 对只知道 `ScopeTypeMetaKind.GDCC_CLASS`、但没有更细 provenance 的场景，diagnostic 必须保持中性，不得把它直接说成 global-script-class

human-facing 诊断若需要展示用户实际写下的 `extends` 文本，必须在 skeleton/header phase 完成；后续 phase 若只掌握 `ClassDef`，默认展示 canonical name。

---

## 6. 长期不变量

仓库后续改动必须继续满足以下事实：

- `ClassDef#getName()` 是 canonical class name
- `ClassDef#getSuperName()` 是 canonical superclass name
- `LirClassDef` 不承担任何 superclass source-facing 数据
- frontend accepted relation 可以恢复 superclass 的 `sourceName` 与 `canonicalName`
- frontend source-facing superclass 合同不暴露 canonical `$` spelling
- MVP 不支持 path-based `extends`、autoload superclass、global-script-class superclass 绑定
- MVP 仅处理单个 gdcc module，不支持跨 gdcc module 的 superclass 绑定
- `rawExtendsText` 只存在于 discovery / diagnostic 的短生命周期对象
- backend / LIR / registry / shared resolver 不依赖 superclass source text
- `$` 原样进入 backend 标识符链路，但该前提仅绑定当前 `zig cc` 工具链

---

## 7. 后续工程接口

若后续工程要扩展 superclass 语义，必须显式处理以下接口，而不是局部打补丁：

- 多 gdcc module 的 header superclass 绑定协议
- path-based `extends` 的 source-facing 语义与 canonical 产物
- autoload / singleton superclass 的 provenance 与稳定绑定结果
- global-script-class superclass 绑定的显式模型
- header inheritance 与 shared declared-type resolver 的对齐设计
- backend 在非 `zig cc` 工具链下对 `$` 的兼容策略

任何扩展都必须同步更新本文档、相关实现注释和正反两类测试锚点。

---

## 8. 回归约束

涉及 superclass contract 的改动必须继续满足以下测试约束：

- frontend relation 与 `ClassDef` 的断言必须拆开写，分别验证 source/canonical 与 canonical-only contract
- unsupported superclass source 必须覆盖 happy path 与 negative path
- negative path 至少锚定：
  - diagnostic category
  - 关键 message 事实
  - rejected subtree
  - 同 module 合法 subtree 继续产生产物
- singleton/autoload 边界测试必须使用确定性的 fixture，不得依赖外部 API 列表的偶然内容
