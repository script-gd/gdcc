# Frontend Builtin Property Access Implementation

> Updated: 2026-04-07
>
> 本文档是 frontend builtin property access 的事实源。
> 不再记录阶段性步骤、完成进度或实施流水账；若合同变化，应直接改写当前状态。

## 1. 维护合同

- 本文档覆盖 shared metadata、frontend semantic、CFG/lowering、backend codegen 与 runtime ABI 之间关于 builtin property access 的长期合同。
- 本文档只描述已经冻结并由代码实现承担的事实，不描述历史修复步骤。
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_rules.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - 与 builtin property normalization / lowering / codegen 直接相关的代码注释

---

## 2. 当前支持面

frontend 当前正式支持的 builtin property surface 是：

- builtin instance property read
  - `vector.x`
  - `color.r`
  - `Vector3(1.0, 2.0, 3.0).y`
- builtin instance property write
  - `vector.x = 1.0`
  - `color.a = 0.5`
- type-meta static constant/property-like static load 继续按既有 static route 工作
  - `Vector3.ZERO`
  - `Color.RED`
  - `Basis.IDENTITY.x`

这条支持面当前覆盖的典型 builtin 类型包括：

- `Vector2` / `Vector2i`
- `Rect2` / `Rect2i`
- `Vector3` / `Vector3i`
- `Transform2D`
- `Vector4` / `Vector4i`
- `Plane`
- `Quaternion`
- `AABB`
- `Basis`
- `Transform3D`
- `Projection`
- `Color`

以下内容不属于当前合同：

- builtin keyed access
  - `vector["x"]`
- 把 builtin property route 放宽成任意 dynamic/keyed/property fallback
- 在 downstream 阶段重新解析 raw builtin JSON schema

换言之，当前支持的是“ordinary builtin property access 已 compile-ready”，不是“builtin member access 的所有语法糖都已支持”。

---

## 3. Shared Metadata Contract

### 3.1 Normalized property surface

`ExtensionBuiltinClass` 当前同时承担两类事实：

- 保留 raw builtin metadata truth
  - `members()`
  - `constructors()`
  - `methods()`
  - `constants()`
- 向 shared resolver 暴露 normalized property-like surface
  - `getProperties()`

Godot builtin JSON schema 中的 `members` 当前必须在 metadata ingestion 层就被 normalize 成 synthetic property surface，而不是留给下游按需补洞。

### 3.2 Synthetic builtin property contract

member-backed synthetic property 当前固定满足：

- `name = member name`
- `type = member type`
- `isReadable = true`
- `isWritable = true`

这条合同是 frontend 与 backend 共用的 shared truth。

### 3.3 Consumer contract

所有 consumer 当前都只允许读取 normalized property surface：

- `ScopePropertyResolver`
- chain reduction / assignment semantic
- CFG builder / body lowering
- backend property codegen

下游不得：

- 重新扫描 raw builtin `members`
- 增加第二套 schema-specific fallback
- 用“builtin property miss 后再尝试 JSON members”的方式补洞

核心约束是：normalize once, consume everywhere。

---

## 4. Frontend Semantic And Lowering Contract

### 4.1 Route classification

builtin instance property access 在 frontend 中当前固定属于 ordinary property route：

- `vector.x` / `color.r` 不是 keyed access
- 非 object receiver 的 property step 进入 builtin property reduction
- published member fact 继续使用普通 `FrontendResolvedMember`

### 4.2 Published facts

对 builtin property route，frontend 当前固定发布：

- chain binding 发布 `FrontendResolvedMember(status = RESOLVED)`
- expr typing 发布 exact result type
- compile gate 把该 route 视为 lowering-ready

`vector.missing` 这类无效 member 继续 fail-closed，不得被静默转成其他 route。

### 4.3 CFG and body lowering

CFG builder 与 body lowering 当前继续复用普通 member/property lowering surface：

- property read -> `MemberLoadItem` -> `LoadPropertyInsn`
- property write -> ordinary assignment/store-property route

这里没有 builtin-property-only CFG item，也没有 builtin-property-only LIR 指令。

### 4.4 Separation from keyed access

后续实现必须继续维持以下边界：

- `vector.x` 是 ordinary property access
- `vector["x"]` 仍是 builtin keyed access，且不属于当前 compile-ready surface

不得因为 builtin property access 已闭合，就放宽 keyed / dynamic / variant 路线。

---

## 5. Backend And Runtime ABI Contract

### 5.1 Property codegen contract

backend 对 builtin member-backed property 当前固定生成：

- read -> `godot_<Builtin>_get_<member>`
- write -> `godot_<Builtin>_set_<member>`

例如：

- `godot_Vector3_get_x`
- `godot_Vector3_set_x`
- `godot_Color_get_r`
- `godot_Color_set_a`

### 5.2 Builtin getter/setter ABI contract

对 builtin 值类型参数，generated C 当前必须保持 pointer ABI：

- 用户方法签名接收 `godot_Color*` / `godot_Vector3*` 一类参数
- GDExtension method bind wrapper 以 builtin value 的地址调用用户方法
- 进入用户方法体后，参数可以先 materialize 为局部值槽，再对局部值槽取地址调用 getter/setter

因此，最终 getter/setter 调用点必须始终消费“builtin value 的地址”，而不是把 builtin struct 按值误传给 ABI。

### 5.3 Unit body generation vs end-to-end ABI

这里要明确区分两类观察层级：

- unit 级 body generation
  - 观察已有 receiver slot 上如何发出 getter/setter call
  - 例如 `CLoadPropertyInsnGenTest` 中可见 `godot_Color_get_r($color)` 这类片段
- end-to-end ABI
  - 观察 method wrapper、用户方法签名、函数体参数物化和最终 getter/setter 调用组合后的真实合同

判断 ABI 是否正确，必须以后者为准。

### 5.4 Non-ref receiver contract

对构造结果、局部变量、临时值这类非 ref receiver：

- 最终 emitted getter/setter call 必须显式走 `&slot`
- 不得把非 ref builtin value 直接按值传入 getter/setter

---

## 6. 回归锚点

涉及本文档合同的修改，至少要继续覆盖以下回归锚点：

- `ScopePropertyResolverTest`
  - default API 上 `Vector3.x` / `Color.r` 可解析
  - missing builtin member 继续报缺失
- `FrontendExprTypeAnalyzerTest`
  - `vector.x`
  - `Basis.IDENTITY.x`
- `FrontendAssignmentSemanticSupportTest`
  - builtin member-backed property assignment
  - missing member 与类型不匹配继续 fail-closed
- `CLoadPropertyInsnGenTest`
  - builtin getter codegen
- `CStorePropertyInsnGenTest`
  - builtin setter codegen
- `FrontendLoweringToCProjectBuilderIntegrationTest`
  - builtin property compile-ready runtime chain
  - builtin getter ABI end-to-end contract

这些回归锚点的职责分工当前固定为：

- resolver / semantic tests 锚定 normalized metadata 与 published fact
- backend unit tests 锚定 getter/setter symbol route
- Godot integration test 锚定真实 runtime ABI 合同

---

## 7. 架构反思

本区域已经沉淀出的长期结论是：

- builtin property access 的关键不是在 frontend、backend、resolver 各补一层特判，而是把 raw builtin `members` 上收为 shared metadata contract
- ordinary property route 与 builtin keyed access 必须严格分离，不能因为语法都“像成员访问”就混成一条语义路径
- frontend published fact、CFG/lowering surface 与 backend symbol route 应继续复用现有普通 property 体系，而不是额外发明 builtin-property-only 专用机制
- builtin getter/setter ABI 必须以后端到端 generated C + Godot runtime 为事实源，不能只凭某个中间代码片段下结论

后续若继续扩张 builtin property surface，应继续按以下顺序推进：

1. 先冻结 shared metadata contract
2. 再冻结 frontend published fact 与 lowering-ready surface
3. 最后再扩张 backend/codegen/runtime coverage

不得反过来用下游补洞去倒逼上游 metadata 或 semantic side table 追加临时规则。
