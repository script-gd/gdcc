# Frontend Property Init Lowering Plan

> Updated: 2026-04-07
>
> 本文档记录 frontend property initializer lowering 的当前事实，以及围绕
> object-valued property initializer runtime crash 的后续修复计划。
> 前半部分保留当前实现合同；计划章节只描述尚未实施的修复任务、验收细则与风险边界。

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

### 6.3 Constructor-time property apply 合同

当前 backend 已经把“property initializer helper 产出值”和“constructor-time backing-field apply”拆成两层 contract：

- `_field_init_<property>` helper 只负责产出 initializer value
- `${Class}_class_apply_property_init_<property>(self)` 负责在 constructor-time 把该值应用到 backing field

当前 apply contract 固定为：

- apply helper 是 direct backing-field route，不是 setter route
- class constructor 只调用 apply helper，不再内联裸 `self->field = init_helper(self)` 语句
- apply helper 当前仍是 constructor-time first-write 路径；后续若扩张 object lifecycle 语义，应继续在这层收口，而不是回退到 class constructor 内联拼接

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

---

## 10. Object-Valued Property Initializer Crash Fix Plan

### 10.1 背景

当前 frontend 已经允许 compile-ready property initializer helper，例如：

- `var node: Node = Node.new()`
- `var child: Object = Node.new()`
- 其他 builtin / engine / gdcc object constructor 作为 property initializer expression 的写法

其中 frontend 侧 contract 已闭合：

- property initializer 会被 lowering 成真实 hidden `_field_init_<property>` helper
- helper 拥有真实 CFG / LIR body
- helper 返回类型与 property type 对齐

但这并不等于 backend 已经正确闭合“helper 返回值写回 backing field”的运行时语义。

### 10.2 已确认的问题链路

当前代码库中已经确认的断裂点如下：

1. object constructor route 会把 `Node.new()` 之类的结果当成 object-producing expression，并在 backend 里按 `OWNED` value source 处理。
2. `CBodyBuilder` 已经为 object slot write 固化统一语义：capture old -> convert ptr if needed -> assign -> own(BORROWED only) -> release old。
3. property initializer helper 的执行结果回写 property backing field 时，没有走 `CBodyBuilder` 的统一 slot-write 路径。
4. 模板 `entry.c.ftl` 当前仍直接生成：
   - `self->field = Class__field_init_field(self);`
5. 因此 object-valued property initializer 会绕开统一的 ownership / ptr-conversion / overwrite policy。
6. 这条路径与 backend 当前关于 object field slot write 的长期合同不一致，也是 `Node.new()` 用作字段初始化表达式时最可疑、最明确的 runtime crash source。

### 10.3 修复目标

本次修复的目标固定为：

- 保持 frontend 已有 property initializer lowering contract 不变
- 修复 backend 对 object-valued property initializer apply path 的生命周期与表示转换缺口
- 保持 property initializer 仍然是 direct backing-field initialization，而不是 setter invocation
- 为后续 custom getter / setter 实现预留正确的 direct-field route

本次修复不以“扩大 property initializer 对实例状态的语义支持面”为目标。

### 10.4 非目标

以下内容不在本计划范围内：

- 放开 property initializer 对同 class non-static property / method / signal / `self` 的依赖
- 改造 property initializer 成“统一走 setter”
- 借本次修复顺手扩张 `static var`、`@onready` 或 parameter default lowering
- 重写 constructor lowering 的 frontend graph/item 模型

### 10.5 设计约束

修复方案必须同时满足以下约束：

- 必须尊重 Godot-compatible member initialization 语义：property initializer 直接写 backing field，不得调用 property setter。
- backend 中 object field 也属于 slot；property initializer apply path 不得继续绕开统一 slot-write 语义。
- object-valued initializer result 若本身是 constructor-produced owned value，写回 field 时必须按 owned-source consume 语义处理，不能错误地再 own 一次。
- first-write constructor-time property initialization 与 ordinary overwrite store 必须区分：
  - constructor-time first write 不释放旧值
  - future overwrite path 继续保留 existing slot-write order
- 修复应尽量复用现有 `CBodyBuilder` ownership / ptr-conversion 基础设施，而不是在模板层手写第二套 object write lifecycle。

### 10.6 分步骤实施

#### Step 1. 冻结 constructor-time property-apply contract

状态：

- 已完成（2026-04-07）

目标：

- 明确 property initializer helper 的职责只到“产出 initializer value”。
- 明确“把 initializer value 应用到 backing field”属于 backend property-construction apply path，而不是 helper 自身 contract。
- 明确 apply path 的 direct-field / first-write / ownership-consume 语义。

实施点：

- 更新本文档与相关 backend / frontend 文档，使 property initializer helper contract 与 property apply contract 分开描述。
- 补充 direct backing-field initialization 与 setter route 的边界说明。

验收：

- 文档中能明确区分：
  - helper produces value
  - constructor-time property apply writes backing field
- 不再出现把 property initializer helper 与 setter route 混写的描述。

#### Step 2. 提炼 backend property backing-field apply abstraction

状态：

- 已完成（2026-04-07）

目标：

- 在 backend 中提供一条专用的“constructor-time property backing-field first-write”抽象。

实施点：

- 选择一个统一 owner：
  - 优先复用 `CBodyBuilder`
  - 若模板仍需参与，仅允许模板调用单一 helper/abstraction，而不是手写 object field assignment
- 该 abstraction 至少要显式表达：
  - target field C lvalue
  - target field type
  - rhs expr
  - rhs ptr kind
  - rhs object static type
  - rhs ownership kind
  - first-write / release-old policy

验收：

- backend 代码中 property initializer apply path 不再直接手写 `self->field = helper(self)` 作为最终 object write 语义。
- object 与 non-object property apply path 都能映射到统一、可检查的 helper contract。

#### Step 3. 接通 object-valued property initializer write semantics

目标：

- 让 `Node.new()`、`RefCounted.new()`、gdcc class `.new()` 等 initializer expression 在写回字段时遵守统一 slot-write 规则。

实施点：

- object-valued initializer result 写回 backing field 时：
  - 必要时做 GDCC/Godot ptr conversion
  - rhs 为 `OWNED` 时直接 consume，不重复 own
  - first-write 不释放旧值
- non-object property initializer path 保持现有 direct write 行为，但也要通过同一 abstraction 收口。

验收：

- 运行时不再因 `var node: Node = Node.new()` 这类字段初始化而崩溃。
- 生成代码中 property initializer apply path 不再与 `assignVar` / `emitObjectSlotWrite(...)` 的生命周期语义冲突。

#### Step 4. 锁定 direct-field vs setter contract

目标：

- 防止后续引入 custom getter/setter 时，再次把 property initializer route 误接到 setter。

实施点：

- 文档中明确：
  - property initializer apply 是 backing-field write
  - 不经 setter
- 若实现中需要新增 helper 名称或注释，必须把这条 contract 写死。

验收：

- 对 property initializer 的实现和文档检查时，可以明确看出它不是 setter route。
- 不存在“为了复用现有 setter path 而改变 initializer semantics”的残留实现。

#### Step 5. 补齐单元测试与引擎集成测试

目标：

- 从正反两方面把行为锚定，防止 future getter/setter 与 ownership 调整再次打破这条路径。

单元测试至少覆盖：

- 生成代码不再走裸模板直赋值语义
- object-valued property initializer apply path 的 ownership / ptr-kind contract
- direct-field route 与 setter route 的边界

引擎集成测试至少覆盖：

- `var node: Node = Node.new()` 可正常构造并调用 `get_class()`
- `var obj: Object = Node.new()` 可正常返回并按 `Object` 使用
- `var rc: RefCounted = RefCounted.new()` 可正常运行
- 一个 gdcc 自定义类 object-valued property initializer 的正向链路

负向/回归测试至少覆盖：

- property initializer 不应经由 setter 执行
- future custom accessor 场景下 initializer 仍保持 direct backing-field route

验收：

- 单元测试与引擎集成测试均稳定通过。
- 测试命名和断言能够直接表达“constructor-time property apply uses direct backing-field first-write semantics”。

### 10.7 验收总则

本计划完成时，必须同时满足以下标准：

1. `Node.new()` 作为 property initializer expression 不再导致运行时崩溃。
2. property initializer helper contract 与 property apply contract 在文档和实现中都已分离。
3. backend property apply path 不再绕开统一 object slot-write 语义。
4. property initializer 仍然不调用 setter，且该事实被文档与测试双向锁定。
5. 新实现不会破坏既有的 plain property initializer、null-object initializer 和 default helper 路径。

### 10.8 风险与后续注意事项

- 本计划优先修复的是“initializer value -> backing field apply”缺口，不自动解决 property initializer 访问实例状态的完整时序问题。
- object field destructor 当前对非-RefCounted engine object 的处理仍是相邻风险点；若本次修复引入更明确的 field ownership contract，应同步重新审视该路径，但不要在没有文档冻结的前提下顺手扩张问题范围。
- 后续若真正落地 custom getter / setter，必须先验证 property initializer route 没有被错误复用到 setter path。
