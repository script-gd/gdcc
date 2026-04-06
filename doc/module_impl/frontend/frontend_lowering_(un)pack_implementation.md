# Frontend Lowering `(un)pack` Implementation

> Updated: 2026-04-06
>
> 本文档是 frontend ordinary typed-boundary `(un)pack` materialization 的事实源。
> 它不再记录实施步骤、完成进度或验收流水账；若合同变化，应直接改写当前状态。

## 1. 维护合同

- 本文档覆盖 ordinary typed boundary 在 shared semantic、type check、body lowering 与 backend 之间的长期合同。
- `frontend_implicit_conversion_matrix.md` 是“哪些 source/target 边界被 frontend 接受”的唯一真源；本文档不再维护第二份 conversion 矩阵，只记录这些已允许边界如何被 consumer 消费、如何被 lowering 显式物化。
- 本文档描述的 ordinary typed boundary 当前包括：
  - `stable type -> Variant`
  - `stable Variant -> concrete target`
  - `Nil -> object`
- 本文档不覆盖：
  - condition truthiness normalization
  - `DYNAMIC` target 的 runtime-open assignment 语义
  - backend 对 `unpack_variant` 的运行时类型校验细节
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_implicit_conversion_matrix.md`
  - `frontend_rules.md`
  - `frontend_type_check_analyzer_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `FrontendVariantBoundaryCompatibility`
  - `FrontendAssignmentSemanticSupport`
  - `FrontendExpressionSemanticSupport`
  - `FrontendTypeCheckAnalyzer`
  - `FrontendBodyLoweringSession`

---

## 2. 当前支持面

frontend 当前已经把 ordinary typed-boundary `(un)pack` 收敛成 compile-ready executable lowering surface 的正式组成部分。

### 2.1 支持的 ordinary boundary

- target 是 exact `Variant`
  - source 已经是 `Variant` 时 direct flow
  - source 是其他 stable type 时显式插入 `PackVariantInsn`
- source 是 stable `Variant`，target 是 non-`Variant` concrete slot
  - frontend shared semantic 允许该边界
  - body lowering 显式插入 `UnpackVariantInsn`
- source 是 `Nil`，target 是 object family
  - body lowering 显式物化 object-typed `LiteralNullInsn`
- 其余 ordinary boundary
  - 继续回退 `ClassRegistry.checkAssignable(...)`
  - 若 strict assignability 不成立，则 frontend 直接拒绝

### 2.2 当前覆盖的 consumer

ordinary boundary materialization 当前已经接通：

- local initializer
- class property initializer
- ordinary assignment
- attribute-property assignment
- fixed call arguments
- vararg tail
- return slot

### 2.3 stable `Variant` source 的发布范围

这里的 stable `Variant` source 当前包括：

- `FrontendExpressionType(status = RESOLVED, publishedType = Variant)`
- `FrontendExpressionType(status = DYNAMIC, publishedType = Variant)`
- slot type 已稳定发布为 `Variant` 的 local / parameter / property / intermediate value

换言之，ordinary boundary 当前接受的是“已发布为 `Variant` 的稳定 frontend fact”，而不是“frontend 还能追溯 provenance 的某几类特殊来源”。

### 2.4 与其他合同的边界

- dynamic call 的 runtime-open dispatch 仍由 backend 承担
- 但 dynamic call 已发布的 `Variant` 结果若继续跨越 ordinary typed boundary，仍由 frontend ordinary boundary helper 做后续 `(un)pack`
- condition normalization 仍是另一条并列合同：
  - `bool` 直接 branch
  - `Variant` 走 `unpack_variant -> bool`
  - 其余 stable type 走 `pack_variant -> unpack_variant -> bool`

ordinary boundary helper 不接管也不替代这条 condition 合同。

---

## 3. 兼容性与语义合同

### 3.1 单一 compatibility 真源

ordinary typed boundary 的允许范围必须继续由 `frontend_implicit_conversion_matrix.md` 唯一维护。

实现侧当前对应的共享入口是：

- `FrontendVariantBoundaryCompatibility`
- `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)`
- `FrontendExpressionSemanticSupport.matchesCallableArguments(...)`
- `FrontendTypeCheckAnalyzer.TypeCheckAccess.checkAssignmentCompatible(...)`

这些 consumer 只能消费矩阵，不得各自维护局部 conversion 清单。

### 3.2 `FrontendVariantBoundaryCompatibility` 的决策模型

shared helper 当前固定发布以下几类 decision：

- `ALLOW_DIRECT`
- `ALLOW_WITH_PACK`
- `ALLOW_WITH_UNPACK`
- `ALLOW_WITH_LITERAL_NULL`
- `REJECT`

其中：

- `ALLOW_WITH_PACK` 表示 source-level 边界合法，但 LIR 需要显式 `PackVariantInsn`
- `ALLOW_WITH_UNPACK` 表示 source-level 边界合法，但 LIR 需要显式 `UnpackVariantInsn`
- `ALLOW_WITH_LITERAL_NULL` 表示 source-level 边界合法，但 LIR 需要显式 object-typed `LiteralNullInsn`

这套 decision 是 frontend ordinary boundary 的统一语义接口；shared semantic 与 lowering 都必须消费同一张 decision table，而不是分别推导。

### 3.3 frontend sema 的职责边界

frontend sema 当前只负责：

- 判断某条 ordinary typed boundary 是否属于 source language 允许面
- 把 stable `Variant -> concrete` 视为合法 source boundary，而不是提前当成 strict assignability failure

frontend sema 当前不负责：

- 证明某个 `Variant` 运行时 tag 一定能拆成目标 concrete type
- 生成 unpack 失败时的 runtime handling
- 放宽 backend/global `ClassRegistry.checkAssignable(...)`

因此，`ClassRegistry.checkAssignable(...)` 继续保持 strict assignability 基线；frontend 允许的 widening 只通过 shared helper 明确外加，不得反写进全局 strict contract。

---

## 4. Lowering Materialization 合同

### 4.1 单一 materialization 入口

`FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)` 当前是 ordinary boundary materialization 的唯一收口点。

local / assignment / call / return consumer 都必须走这条 helper，而不是各自在 processor 内部重写：

- `if (target instanceof GdVariantType) ...`
- `if (source instanceof GdVariantType) ...`
- `if (source instanceof GdNilType && target instanceof GdObjectType) ...`

### 4.2 helper 的当前合同

该 helper 当前只做四类结果：

- direct：直接返回原 slot id
- pack：分配新 temp，并追加 `PackVariantInsn`
- unpack：分配新 temp，并追加 `UnpackVariantInsn`
- null-object：分配新 temp，并追加 object-typed `LiteralNullInsn`

它不得：

- 独立新增 matrix 之外的 conversion
- 重做 source-level 兼容性判断
- 猜测 dynamic-dispatch callable signature
- 与 condition normalization helper 机械合并

### 4.3 call boundary 合同

ordinary call boundary 当前固定为：

- exact `RESOLVED` route
  - fixed parameters 按 selected callable signature 做 ordinary boundary materialization
  - vararg tail 统一按 `Variant` tail 处理
- `DYNAMIC_FALLBACK` route
  - body lowering 不读取 exact callable signature
  - 已求值的 argument slot 直接透传给 `CallMethodInsn`
  - 该 route 本身不插入 fixed-parameter ordinary boundary `(un)pack`

dynamic route 的 ordinary boundary 只会发生在它发布出来的 `Variant` 结果后续再次流入 typed boundary 时。

### 4.4 return boundary 合同

stop-node lowering 当前必须按当前函数 return slot type 走同一套 ordinary boundary helper，然后再发最终 `ReturnInsn`。

这意味着：

- `concrete -> Variant return` 由显式 `PackVariantInsn` 闭合
- `stable Variant -> concrete return` 由显式 `UnpackVariantInsn` 闭合
- `Nil -> object return` 由显式 object-typed `LiteralNullInsn` 闭合

---

## 5. Backend 边界

backend 当前继续只消费已经满足 LIR contract 的结果，而不是替 frontend 猜 ordinary boundary。

### 5.1 frontend 的 owner 责任

只要某条 source-level ordinary boundary 已被 frontend shared semantic 放行：

- frontend lowering 就必须在进入 backend 前把它物化成显式 LIR
- backend 不负责回推“这里本来应该 pack / unpack”

### 5.2 backend 的 owner 责任

backend 当前继续承担：

- runtime-open dynamic dispatch 的实际调用分派
- `PackUnpackVariantInsnGen` 路径上的运行时代码生成
- 后续对 `unpack_variant` 增补真实类型校验与错误处理

因此，这里的职责分层固定为：

- matrix / shared semantic：决定 boundary 是否被允许
- frontend body lowering：把已允许 boundary 物化成显式 LIR
- backend：消费显式 LIR，并承担运行时检查与执行

---

## 6. 非目标与明确边界

本合同当前明确不包含：

- `int -> float` 等 widened scalar conversion
- `StringName` / `String` 互转
- `String` / `NodePath` 等 builtin strict implicit conversion
- 一般性的 container element widening
- `cast` / `as` / `is` 路径的专用语义
- backend 自动帮 frontend 猜 ordinary `(un)pack` 点

若未来要支持这些能力，必须先更新 `frontend_implicit_conversion_matrix.md`，再更新 shared helper、lowering helper、测试与本文档。

---

## 7. 回归锚点

涉及本文档合同的改动，至少要继续覆盖以下回归锚点：

- `FrontendVariantBoundaryCompatibilityTest`
- `FrontendAssignmentSemanticSupportTest`
- `FrontendTypeCheckAnalyzerTest`
- `ScopeMethodResolverTest`
- `FrontendBodyLoweringSessionTest`
- `FrontendLoweringBodyInsnPassTest`

这些测试共同锚定：

- shared semantic 对 ordinary boundary 的允许面
- type-check 不私自复制第二套 conversion 规则
- call applicability 对 stable `Variant` boundary 的消费
- body lowering 对 pack / unpack / null-object 的显式物化

---

## 8. 架构反思

这个区域当前已经沉淀出的长期结论是：

- compatibility 与 materialization 必须分层建模
- `frontend_implicit_conversion_matrix.md` 负责“允许哪些 boundary”，implementation 文档负责“这些 boundary 由谁消费、如何显式落到 LIR”
- ordinary typed boundary、condition normalization、dynamic dispatch 是三条并列合同，不能继续混写成同一层责任
- 只要 pack/unpack 决策集中在 shared helper 与 body-lowering helper 两个点上，未来新增 widened conversion 时就不需要在 assignment / call / return / backend 路径逐处补洞

后续若继续扩展 frontend implicit conversion，顺序必须保持为：

1. 先修改 `frontend_implicit_conversion_matrix.md`
2. 再修改 shared semantic consumer
3. 再修改 `materializeFrontendBoundaryValue(...)` 等 lowering materialization
4. 最后同步本文档与测试锚点
