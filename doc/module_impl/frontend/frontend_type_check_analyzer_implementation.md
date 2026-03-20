# FrontendTypeCheckAnalyzer 实现说明

> 本文档作为 `FrontendTypeCheckAnalyzer` 及其相邻 typed-contract / annotation-usage 工程的长期事实源，定义当前 phase 位置、输入输出合同、diagnostic owner、typed slot contract、property initializer 输入边界、utility void contract 与 `@onready` usage contract。本文档替代旧的实施计划与进度记录，不再保留阶段拆分、验收流水账或已完成任务日志。

## 文档状态

- 状态：事实源维护中（diagnostics-only type check、utility void normalization、Godot-compatible condition contract、unary/binary stable-fact consumption、property initializer boundary consumption、`@onready` usage validation 已落地）
- 更新时间：2026-03-20
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `diagnostic_manager.md`
  - `frontend_top_binding_analyzer_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_variable_analyzer_implementation.md`
  - `frontend_visible_value_resolver_implementation.md`
  - `scope_analyzer_implementation.md`
  - `scope_type_resolver_implementation.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
- 明确非目标：
  - 不在这里新增 `FrontendAnalysisData` side table
  - 不在这里重做表达式求值、binding、member/call 解析或 scope 构建
  - 不在这里补 suite merge、missing-return、all-path return exhaustiveness 分析
  - 不在这里转正 `lambda`、`for`、`match`、parameter default、block-local `const`、class `const` 的正式 body 语义
  - 不在这里实现 property-side inference/backfill，或放宽 `int -> float`、`StringName` / `String`、`null -> Object` 等当前未支持的更宽隐式兼容
  - 不在这里实现 frontend -> LIR 的 truthiness lowering 或 `@onready` 的 runtime / ready-time 语义

---

## 1. 当前职责与集成位置

### 1.1 主链路位置

当前 `FrontendSemanticAnalyzer` 的稳定顺序是：

1. skeleton
2. scope
3. variable inventory
4. top binding
5. chain binding
6. expr typing
7. annotation usage
8. type check

每个 phase 结束后，`FrontendSemanticAnalyzer` 都会调用 `analysisData.updateDiagnostics(...)` 刷新共享诊断边界快照；`FrontendTypeCheckAnalyzer` 与 `FrontendAnnotationUsageAnalyzer` 都运行在已发布的 frontend 事实之上，而不是在自己的 analyze 过程中重新建模更早 phase 的语义。

### 1.2 当前职责

`FrontendTypeCheckAnalyzer` 当前只负责 diagnostics-only 的 typed contract：

- ordinary local 显式 declared initializer compatibility
- class property initializer compatibility
- bare `return` / `return expr` 与 callable return slot 的兼容性
- condition root 是否已经发布稳定 typed fact
- property `:=` / 未声明显式类型 property 的 `sema.type_hint`

这里的 “稳定 typed fact” 当前已经包含 unary / binary 根节点：

- `UnaryExpression` 不再被默认视为 deferred gap
- `BinaryExpression` 不再被默认视为 deferred gap
- `not in` 例外地保持显式 `UNSUPPORTED`，因此继续留给 upstream owner

`FrontendAnnotationUsageAnalyzer` 当前只负责 diagnostics-only 的 annotation placement contract：

- `@onready` 只能用于 class property `var`
- owner class 必须派生自 `Node`
- `@onready` 不可用于 `static` property

### 1.3 当前不负责

这两个 analyzer 当前都不负责：

- 发布新的 side table
- 回写已有 side table、property metadata 或 callable metadata
- 重新解析 member / call / expression type
- 把 upstream `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED` 结果改写成 type-check 自己的错误
- 实现 runtime timing、lowering 或 codegen 语义

---

## 2. 输入事实、输出与 diagnostic owner

### 2.1 依赖的已发布事实

当前 type-check / annotation-usage 工程稳定依赖以下输入：

- `moduleSkeleton`
- `scopesByAst()`
- `symbolBindings()`
- `resolvedMembers()`
- `resolvedCalls()`
- `expressionTypes()`
- `annotationsByAst()`
- skeleton 中冻结的 property / function / constructor metadata
- shared `ClassRegistry`

这里有两条 fail-fast 边界：

- 若 source file 对应的 `ClassScope` 尚未发布，analyzer 会抛异常，而不是把缺失 scope 当作可恢复 source error
- 若 type check 需要消费的 expression root 没有 `expressionTypes()` 条目，analyzer 会抛异常，而不是静默跳过

### 2.2 当前输出

当前 analyzer 只输出 diagnostics：

- `FrontendTypeCheckAnalyzer` 不创建也不更新新的 semantic side table
- `FrontendAnnotationUsageAnalyzer` 同样只发 diagnostic，不改写 annotation retention 或 property skeleton metadata

换言之，这里没有新的“typed facts table”；稳定 typed contract 仍然通过已发布的 `expressionTypes()` 与 skeleton/property metadata 消费。

### 2.3 当前 diagnostic owner

当前 owner / category 合同已经冻结为：

- `sema.type_check`
  - local / class property / return typed contract 的真实不兼容
  - severity 固定为 `error`
- `sema.type_hint`
  - property `:=` / 未声明显式类型 property 的手动显式类型提醒
  - severity 固定为 `warning`
- `sema.annotation_usage`
  - `@onready` 的 placement / owner-class / staticness 非法用法
  - severity 固定为 `error`

以下 category 继续保持 upstream owner，不由 type-check/annotation-usage 重发：

- `sema.binding`
- `sema.member_resolution`
- `sema.call_resolution`
- `sema.expression_resolution`
- `sema.unsupported_*`
- `sema.deferred_*`

---

## 3. 当前 typed contract

### 3.1 稳定表达式事实规则

type-check 当前只消费稳定 expression fact：

- `RESOLVED`
- `DYNAMIC`

以下状态一律视为“保持 upstream owner”，当前 phase 直接跳过：

- `BLOCKED`
- `DEFERRED`
- `FAILED`
- `UNSUPPORTED`

这条规则的含义是：

- type-check 不负责解释 upstream 为什么失败
- type-check 只负责“当表达式已经稳定时，它是否满足 slot contract”
- 同一根源错误不得被翻译成第二条同级 `sema.type_check`

### 3.2 concrete-slot compatibility 的唯一入口

当前所有 concrete-slot compatibility 都必须统一复用：

- `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(slotType, valueType)`

当前冻结行为是：

- exact `Variant` slot 接受任意已解析值
- 其余 slot 继续回退 `ClassRegistry.checkAssignable(...)`

因此，local/property/return typed gate 不得直接手写 `Variant` 分支，也不得直接拿 `ClassRegistry.checkAssignable(...)` 替代 shared helper。

### 3.3 ordinary local 显式 initializer

ordinary local initializer 当前合同是：

- 只检查带显式 declared type 的 ordinary local
- slot 从 `BlockScope` 已发布的 local inventory 中恢复
- RHS root 只有在 `RESOLVED` / `DYNAMIC` 时才进入 compatibility check
- 若 initializer root 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`，当前 phase 保持跳过

`:=` local 不在这里生成新的 compatibility diagnostic；它继续沿用已有的 variable/expr contract。

### 3.4 class property initializer 与 `sema.type_hint`

class property initializer 当前合同是：

- 只覆盖 `VariableDeclaration(kind == VAR && value != null)` 且 declaration scope 为 `ClassScope` 的支持岛
- property slot 从当前 declaring class 已发布的 skeleton metadata 中恢复
- 显式 declared property type 与稳定 RHS 不兼容时，发 `sema.type_check`
- property 使用 `:=` 或缺失显式类型，且 RHS 已稳定时，发 `sema.type_hint`

`sema.type_hint` 当前只表达：

- MVP 不会把 property `:=` 或 missing-type property 回写成真正推导出来的 property metadata
- 建议用户手动补显式类型，例如 `: int` 或 `: Variant`

当前 property metadata 仍保持：

- `:=` property -> `Variant`
- missing-type property -> `Variant`

type-check 不得把 warning 暗中升级成 metadata rewrite。

### 3.5 return contract

return contract 当前冻结为：

- callable return slot 直接消费 skeleton 已发布的 metadata
- constructor 与 `_init` 当前统一建模为 `void`
- `return expr` on `void` callable -> `sema.type_check`
- bare `return` on non-`void` callable -> 把返回值按 synthetic `Nil` 参与 compatibility check
- `return expr` 只有在 RHS root 已稳定时才进入 compatibility check

因此：

- `Variant` return slot 可以接受 bare `return`
- strict numeric / object / bool slot 不会因为 bare `return` 被误判为可接受
- 当前 frontend 仍未放宽 `null -> Object`

### 3.6 condition contract

condition 当前采用 Godot-compatible source contract：

- `assert`
- `if`
- `elif`
- `while`

这些 condition root 当前只要求：

- 已经在 `expressionTypes()` 中发布稳定 typed fact
- `publishedType` 非空

这意味着 unary / binary 的已发布结果会被 type-check 直接消费，而不是再按“表达式家族尚未实现”额外跳过。例如：

- `RESOLVED(bool)` 的 `!true`
- `RESOLVED(bool)` 的 `payload and 1`
- `DYNAMIC(Variant)` 的 `not payload`

当前 type-check 不再把 condition 当作 strict `bool` slot 去做 assignment gate。也就是说：

- `RESOLVED(int)` condition 不会仅因非 `bool` 被 frontend 拒绝
- `RESOLVED(Variant)` / `DYNAMIC(Variant)` condition 也不会仅因非 `bool` 被 frontend 拒绝
- `DEFERRED` / `FAILED` / `BLOCKED` / `UNSUPPORTED` condition 保持 upstream owner

但 downstream 约束仍然存在：

- backend/LIR 的 control-flow 仍是 bool-only 边界
- 当未来接上 frontend -> LIR lowering 时，必须在 lowering 侧补 truthiness / condition normalization
- type-check 不得反向把 source frontend 收紧成 undocumented strict-bool dialect

### 3.7 utility 空返回类型合同

与 type-check 紧邻的 shared contract 还包括：

- Godot utility metadata 缺失 `return_type` 时，shared consumer 统一把它解释为 `void`
- `ExtensionUtilityFunction.getReturnType()` 与 `ClassRegistry.findUtilityFunctionSignature(...)` 都遵守这条合同

这条合同的直接结果是：

- `print(...)` 等 bare utility call 可以稳定发布 `RESOLVED(void)`
- value-required site 会继续收到普通 typed diagnostic
- frontend pipeline 不再因为 `return_type == null` 在 expr typing / visible-value 相关回归中提前崩溃

---

## 4. Property initializer 输入边界

### 4.1 当前支持岛

property initializer 当前只是 frontend body-phase 的最小支持岛，不是完整 member-initializer semantic domain：

- 只覆盖 class body 中的 `var` initializer
- class `const` initializer 仍不属于正式 body support surface
- shared `Scope.resolveValue(...)` / `resolveFunctions(...)` / `resolveTypeMeta(...)` 只负责候选查找，不自动等价于“initializer 可以合法消费该成员”

### 4.2 当前边界合同

当前 initializer boundary 已冻结为：

- 不支持访问当前实例层级可达的 non-static property / method / signal / `self`
- 允许 literal、普通表达式子树、global callable、type-meta static route、继承链上的 static member 等非当前实例依赖

owner 分工固定为：

- bare identifier / bare callee / `self` 的首个 boundary -> top-binding owner
- chain suffix / explicit route 的首个 boundary -> chain-binding owner
- expr typing 与 type check 只传播上游 status，不重发第二条同级错误

因此 type-check 对 property initializer 的当前职责只是：

- 读取已发布的 property slot
- 读取已发布的 expression root status
- 对稳定 RHS 做 slot compatibility 或 type hint
- 对 upstream `BLOCKED` / `UNSUPPORTED` / `DEFERRED` / `FAILED` 保持跳过

### 4.3 当前测试锚点与后续检查项

当前回归已经覆盖：

- direct / inherited instance property、method、signal 的 boundary
- direct / inherited type-meta route
- direct / inherited suffix route
- 允许的 static helper 与 global utility callable

若后续继续调整 property initializer boundary，优先补以下 receiver-sensitive 回归：

- 显式 `TYPE_META` route 在同名静态遮蔽下的行为
- 继承静态 value route 的正向样本
- “同一 receiver 家族中静态成员允许、实例成员封口” 的并行锚点

---

## 5. `@onready` usage contract

当前 `@onready` 的实现范围需要分层理解：

- skeleton phase 保留 annotation retention
- `FrontendAnnotationUsageAnalyzer` 做最小 placement validation
- backend/lowering 目前不消费 `@onready` 的 ready-time 语义

当前合法性规则固定为：

- 只能用于 class property `var`
- owner class 必须派生自 `Node`
- 不可用于 `static` property

当前明确未做的事：

- `_ready()` timing
- delayed evaluation
- dependency ordering
- 任何额外的 `@onready` lowering / runtime codegen

也就是说，当前“已实现”的 `@onready` 只代表 frontend retention + usage validation 已完成，不得被外推成全编译链 ready-time 语义已完成。

---

## 6. 测试锚点

当前与本事实源直接相关的核心测试包括：

- `FrontendTypeCheckAnalyzerTest`
  - local / property / return typed contract
  - property `:=` / missing-type `sema.type_hint`
  - `void` utility 进入 value-required slot 的显式错误
  - Godot-compatible condition contract
  - property initializer upstream boundary 的 skip 语义
- `FrontendAnnotationUsageAnalyzerTest`
  - `@onready` owner-class / staticness / placement validation
- `FrontendSemanticAnalyzerFrameworkTest`
  - phase 顺序与 diagnostics boundary refresh
- `ClassRegistryTest`
  - utility 空 `return_type` -> `void` shared contract
- `FrontendVisibleValueResolverTest`
  - `print(...)` 相关崩溃回归已恢复为正常 resolver contract 测试
- `FrontendTopBindingAnalyzerTest`
- `FrontendChainBindingAnalyzerTest`
- `FrontendExprTypeAnalyzerTest`
  - property initializer boundary 的上游 owner 与 direct/inherited route 行为

这些测试锚定的是“当前合同”；后续若扩大支持面，必须先改合同，再改测试，再改实现，不能只靠改测试吞掉语义漂移。

---

## 7. 当前未完成项与后续接线约束

当前仍明确未完成的工程包括：

- missing-return / all-path return completeness
- property-side inference / metadata backfill
- frontend -> LIR 的 truthiness / condition normalization
- `@onready` runtime / ready-time lowering
- `lambda`、`for`、`match`、parameter default、block-local `const`、class `const` 的正式 body semantics

后续工程若继续扩展本区域，必须遵守以下约束：

1. type-check 继续保持 diagnostics-only，不新增 side table。
2. slot compatibility 继续只走 `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)`。
3. property initializer boundary 继续保持 upstream owner 单一责任，不把 boundary 错误重新翻译成 type-check 错误。
4. condition source contract 与 downstream bool-only lowering contract 必须同时写清楚，不允许再次出现“代码 strict bool、文档默认兼容 GDScript、backend 另有要求”的分叉。
5. `@onready` 的 frontend usage validation 与 runtime semantics 必须继续分层记录，不得混写成“已完成 annotation 就等于已完成 ready-time 语义”。

---

当前 `FrontendTypeCheckAnalyzer` / `FrontendAnnotationUsageAnalyzer` 已进入长期维护阶段。后续若继续扩张 typed contract、initializer semantic domain 或 annotation runtime 语义，应以本文档记录的 owner 边界、输入事实、共享 helper 真源与 downstream 接线约束为准，而不是恢复旧的阶段性实现假设。
