# Frontend Property Init Lowering Implementation

> Updated: 2026-04-06
>
> 本文档是 frontend property initializer lowering 的事实源。
> 不再记录实施步骤、完成进度或验收流水账；若合同变化，应直接改写当前状态。

## 1. 维护合同

- 本文档覆盖 `PROPERTY_INIT` 在 frontend pre-pass、compile gate、CFG build、body lowering 与 backend 之间的长期合同。
- 本文档只记录当前已经冻结并由实现承担的事实，不保留历史修复过程。
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_rules.md`
  - `frontend_lowering_func_pre_pass_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - 与 property-init helper / backend owner 边界直接相关的代码注释

---

## 2. 当前支持面

当前默认 frontend lowering pipeline 已正式支持 compile-ready property initializer：

- source 入口是 class property declaration 自带的 initializer expression
- lowering 单元使用独立的 `FunctionLoweringContext.Kind.PROPERTY_INIT`
- frontend 会为该 property 发布 hidden synthetic `_field_init_<property>` helper
- helper 在默认 pipeline 终态拥有真实 CFG、真实 LIR body、有效 `entryBlockId`

这条支持面当前只覆盖 property initializer island 已经放行的表达式子树。

以下内容不属于当前 compile-ready surface：

- 脚本类 `static var`
- property `:=` metadata 回写
- property initializer 对同 class non-static property / method / signal / `self` 的完整初始化时序语义
- parameter default lowering
- `@onready` ready-time 执行模型

换言之，当前支持的是“property initializer helper 的 lowering 已闭合”，不是“完整实例成员初始化语义已经闭合”。

---

## 3. Function-Shaped Lowering 合同

### 3.1 Preparation 产物

`FrontendLoweringFunctionPreparationPass` 当前固定发布两类默认 lowering 单元：

- `EXECUTABLE_BODY`
- `PROPERTY_INIT`

其中 `PROPERTY_INIT` 的稳定合同是：

- `sourceOwner` 必须是 property declaration
- `loweringRoot` 必须是该 declaration 的 initializer expression
- `targetFunction` 必须是 owning class 上的 hidden synthetic helper
- pre-pass 结束时该 helper 仍处于 shell/scaffold 状态，不包含 basic block 或 entry metadata

这里的 shell/scaffold 只表示 pre-pass 与 CFG/body 之间的中间态，不得外推成默认 lowering pipeline 的最终合同。

### 3.2 Helper signature

property-init helper 的 frontend/backend 共享合同当前固定为：

- helper 必须 hidden
- helper return type 必须等于 property type
- non-static property helper 继续显式声明 owning-class `self` parameter
- `LirPropertyDef.initFunc` 一旦指向某个 helper name，该 helper 就必须满足上面的命名与签名合同

---

## 4. CFG Build 合同

`FrontendLoweringBuildCfgPass` 对 `PROPERTY_INIT` 当前不再只是做 guard，而是发布真实 frontend CFG graph。

### 4.1 Graph shape

property initializer graph 当前冻结为 expression-rooted function graph：

- 不伪造 `Block`
- 直接复用现有 value / short-circuit graph core
- graph 以一个 `StopNode(kind = RETURN, returnValueId = ...)` 收口
- 不新增 property-init-only node kind

### 4.2 Published fact consumption

CFG build 对 property initializer 的职责固定为：

- 直接消费 compile gate 已放行的 published subtree facts
- 保持与 executable body 相同的 value-op / call / member / constructor 构图规则
- 缺失 published fact 时继续 fail-fast

CFG build 不承担以下职责：

- 为 property initializer 重跑语义分析
- 为 property initializer 发明第二套 graph/item 体系
- 把 property initializer 伪装成 callable `Block` body

---

## 5. Body Lowering 合同

`FrontendLoweringBodyInsnPass` 当前把 `PROPERTY_INIT` 与 `EXECUTABLE_BODY` 一起交给同一个 `FrontendBodyLoweringSession`。

### 5.1 Session 复用

property initializer 不拥有独立的 body-lowering 架构：

- 继续复用统一的 block creation / block lowering / slot declaration 机制
- 继续复用 ordinary typed-boundary materialization
- graph 中没有 local declaration 时，相关 local-slot 声明自然为空操作

### 5.2 Final LIR shape

默认 pipeline 终态下，property-init helper 必须满足：

- 拥有至少一个 `LirBasicBlock`
- 拥有有效 `entryBlockId`
- 拥有真实 `ReturnInsn`
- 返回值通过现有 typed-boundary 路径与 property/helper return type 对齐

这条合同的核心结论是：

- property initializer 已属于 compile-ready executable lowering surface
- `PROPERTY_INIT` 不是长期 shell-only 特例

---

## 6. Backend Owner 合同

backend 对 property initializer 的责任边界当前已经收紧为 fail-closed owner 模型。

### 6.1 `initFunc == null`

若 property 没有 frontend 发布的 `initFunc`：

- backend 可以继续走默认值 helper 路径

### 6.2 `initFunc != null`

若 property 已经指向某个 `initFunc`：

- backend 只接受一个真实存在的 helper
- 该 helper 必须满足 hidden/internal wiring 合同
- 该 helper 必须满足 property-type return 与 owning-class parameter 合同
- 该 helper 必须已经拥有真实 body 与有效 entry block

backend 不再承担以下职责：

- 修补 frontend 发布但仍无 body 的 property-init shell
- 把 shell-only helper 悄悄 graft 成 prepare/finally 控制流
- 因为 `initFunc` 已有名字就默认它必然 executable

因此 owner 边界当前冻结为：

- frontend 决定 property initializer 是否存在，以及它的语义事实
- lowering 把 helper materialize 为真实函数体
- backend 只消费 lowering 完成后的 helper，或在 `initFunc == null` 时生成默认值 helper

---

## 7. 边界与非目标

property initializer 当前仍只是 frontend body-phase 的最小支持岛，不是完整 member-initializer semantic domain。

当前明确未闭合的边界包括：

- 同 class non-static property / method / signal / `self` 的可见性与初始化时序
- declaration order / default-state / cycle-aware member initialization semantics
- property `:=` 推导回写
- `static var` 的 compile-ready lowering

因此，不得把“property-init helper 已有真实 lowering body”误写成“完整实例初始化模型已经完成”。

---

## 8. 回归锚点

涉及本文档合同的修改，至少要继续覆盖以下回归锚点：

- `FrontendLoweringFunctionPreparationPassTest`
  - preparation 阶段的 property-init scaffold 仍保持 shell/scaffold 中间态
- `FrontendLoweringBuildCfgPassTest`
  - `PROPERTY_INIT` graph publication
- `FrontendLoweringBodyInsnPassTest`
  - property initializer lowering
  - 缺失 published fact 的 fail-fast
- `FrontendLoweringPassManagerTest`
  - 默认 pipeline 终态下 property-init helper 拥有真实 CFG/LIR body
- `CCodegenTest`
  - shell-only helper fail-fast
  - frontend-lowered helper / backend default helper 的 owner 边界
- `FrontendLoweringToCProjectBuilderIntegrationTest`
  - property initializer frontend lowering -> C project build/runtime 正向链路

---

## 9. 架构反思

本区域已经沉淀出的长期结论是：

- property initializer 应被视为 class-level implicit helper，而不是 backend 临时补洞逻辑
- `PROPERTY_INIT` 的关键不是单独发明一套 lowering 机制，而是让它复用统一的 function-shaped lowering scaffold
- pre-pass shell/scaffold 中间态与默认 pipeline executable 终态必须分开描述，不能混成同一合同
- backend 必须只消费“已 lowering 的 helper”或“backend 自己生成的默认值 helper”，不能再兼任补洞者

后续若继续扩张 property initializer 语义，必须继续按以下顺序推进：

1. 先冻结 compile gate / published fact / ownership 合同
2. 再冻结 CFG graph 与 function context 形状
3. 最后扩张 body lowering 与 backend 消费面

不得反过来用 backend 或模板层的局部补洞去倒逼 frontend lowering 补状态。
