# Frontend Complex Writable Target Plan

> 本文档是 frontend “复杂可写目标 / mutating receiver writeback” 工程的实施计划。
> 当前已冻结的事实仍以 `frontend_rules.md`、`frontend_dynamic_call_lowering_implementation.md`、`frontend_lowering_cfg_pass_implementation.md`、`frontend_lowering_(un)pack_implementation.md` 与 `gdcc_type_system.md` 为准；本文档负责把这组事实转写成可执行的实施顺序、验收细则与文档同步清单。

## 文档状态

- 状态：计划维护中（Step 1-7 已落地；Step 8 当前轮的 frontend/body/codegen/engine 验收已补齐，文档继续作为维护中的实施/验收真源）
- 更新时间：2026-04-11
- 本计划覆盖范围：
  - `src/main/java/dev/superice/gdcc/frontend/lowering/cfg/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/pass/body/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/**`
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
- 当前关联事实源：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
- 本计划完成后的归档规则：
  - 当所有实施步骤、正反测试、引擎集成测试与关联文档同步都完成后，将本文档整理归档为 `frontend_complex_writable_target_implementation.md`

---

## 1. 背景与目标

当前 gdcc 的主 lowering 路线仍以“表达式先产出普通 value，再把 value 放进 slot”作为基础模型。它对只读值计算成立，但对以下语义不够：

- `self.position.x = 1`
- `arr[i].x += 1`
- `self.prop[index] = rhs`
- `foo.bar.push_back(1)`
- `self.items[i].push_back(1)`

这些写法的共同点不是“能算出一个值”，而是“左侧 target 或 receiver 背后存在真实 owner，leaf 变异后可能要继续 reverse commit 回外层 owner”。

本计划的目标不是重写整个 CFG value / LIR slot 架构，而是以较小改动面补齐这层 owner-route / reverse-writeback 语义：

1. ordinary read-only expression lowering 继续保留现有主路径
2. assignment target 与 mutating receiver 共享一套 frontend-only writable access-chain 模型
3. 对 `RESOLVED` 与 `DYNAMIC` instance call 都给出可落地的 receiver-side writeback 合同
4. 把 Godot `JUMP_IF_SHARED` 的可观测语义对齐到 gdcc 的 runtime-gated writeback，而不是复制其 bytecode 形状

---

## 2. 已冻结决策

后续实施必须遵守以下决策，不再回到分析阶段重新讨论：

1. `CallItem` 继续扩展，不新增新的 `XXXCallItem`
2. writable payload 必须由 CFG build 从 AST 冻结出显式步骤，不能依赖 lowering 重新解释 AST
3. mutating receiver 与 assignment route 的核心逻辑先抽到 package-private shared support 中，不新增公开抽象层
4. `isConst` 判定通过局部 helper 读取 gdextension metadata；非 gdextension callable 一律保守视为 may-mutate
5. “哪些 receiver family 需要 writeback” 必须提取成 shared helper，并以 `doc/gdcc_type_system.md` 为文档真源
6. 第一版就纳入 `DYNAMIC`、`Variant` 与弱类型 fallback-to-`Variant` 路线；method mutability 不确定时始终生成 commit plan
7. `JUMP_IF_SHARED` 对齐并入本次实施，但只对齐“unknown owner 在 runtime 决定是否跳过 writeback”的可观测行为
8. writable access chain 必须作为一个整体发布与消费，不能拆成多个 item 事后拼装
9. `IndexStoreInsnGen` 只是 backend codegen 生成器，不充当 writeback 真源

---

## 3. 当前起点

当前代码库已经形成的关键现实如下：

- ordinary CFG value 继续是 `valueId -> slotId` 主体系
- `CallItem` / `AssignmentItem` 当前都已能稳定附着单个 writable-route payload
- assignment final-store lowering 已切到 payload-only route；legacy `targetOperandValueIds` 仅继续服务 source-order sequencing 与 compound current-value read
- mutating receiver call 仍会先经 ordinary value path，导致值语义 receiver 可能先复制进 temp 再调用
- backend 已能对值语义类型按地址调用，但并不知道 frontend 原始 owner route

因此当前的真正缺口不是“backend 不会调方法”，而是：

- frontend 过早值化 receiver / target
- lowering 缺少“整条 writable access chain”的冻结与消费模型
- reverse writeback 规则仍是局部补丁，而不是 shared contract

---

### 引擎类上的 `Packed*Array` route 备忘（Godot 对齐）

本节只讨论“引擎类上的 `Packed*Array` 承载点”，例如 `Image`、`Curve2D`、`Polygon2D` 等导出的 property / method。

- 这里的 `builtin property / builtin method` 指“引擎类导出的 property / method”。
- 它不等同于 GDScript 语言内建值类型；`Packed*Array`、`Vector*`、`Color` 这类仍属于语言内建值语义 family。

需要先冻结的 Godot 事实是：

- `Packed*Array` 在普通局部 / 参数传递路径上表现为 shared alias。
- 但引擎类的 builtin property getter 与 builtin method result 返回 `Packed*Array` 时，语义上是 copy。
- 因此是否需要 writeback，不能只看“当前值是不是 `Packed*Array`”，还要看“它来自哪条 route”。

为避免后续实现把不同 provenance 混成同一种 writeback family，本计划把引擎类场景拆成以下几类：

1. 当前结果已可与 Godot 对齐的基线路线：
   - 直接修改引擎类 property 上的 `Packed*Array`：
     - 示例：`poly.polygon.push_back(Vector2.RIGHT)`
     - Godot 语义：`polygon` getter 先返回 copy，随后 VM 会把修改后的 copy 再写回 `poly.polygon`。
     - gdcc 目标语义：必须保持为 `load property -> mutate receiver -> store property back`，不能退化成“只改 temp 不回写”。
   - 先取出 property copy，再修改局部：
     - 示例：`var pts = poly.polygon; pts.push_back(Vector2.RIGHT)`
     - Godot 语义：只修改 `pts`，`poly.polygon` 不变。
     - gdcc 当前也应保持“不向 property 反向 writeback”。
   - 直接修改引擎 method 返回的 `Packed*Array` 结果：
     - 示例：`curve.get_baked_points().push_back(Vector2.RIGHT)`、`image.get_data().push_back(255)`
     - Godot 语义：只修改 method result copy，本体不变。
     - gdcc 当前也应保持 method result 只是 ordinary temp，不得伪造 owner writeback。

2. 当前明确存在的 Godot 差异：
   - widened index compatibility 尚未对齐。
   - 示例：
     - `poly.polygon[0.0] = Vector2.RIGHT`
     - `image.get_data()[0.0] = 255`
   - Godot 允许 `Array` / `packed array` 的 `float index` 宽化兼容；frontend 当前按 `ClassRegistry.checkAssignable(...)` 做最小 typed contract 校验，这类写法仍不属于 MVP 支持面。
   - 这是“引擎类上的 `Packed*Array` 修改”当前最明确、最稳定可复现的前端语义差异之一。

3. 当前必须继续防守的高风险漂移点：
   - `Packed*Array requires writeback` 只是 family 级近似，不足以单独表达引擎类 route 的真实合同。
   - property route 与 method-result route 必须继续区分：
     - `poly.polygon.push_back(...)` 需要 property writeback
     - `curve.get_baked_points().push_back(...)` 不得 write back 到 `curve`
   - 这两个例子“运行时当前 carrier 都是 `Packed*Array`”，但 Godot 语义不同，原因完全来自 provenance 不同。
   - 因此 Step 5/6/7 若只根据 `Packed*Array` family 做统一 commit-after-call，而丢掉“property leaf / method result / ordinary local”区分，就会开始偏离 Godot。

4. 当前尚未被引擎集成测试充分锚定、后续必须补齐的路线：
   - dynamic / `Variant` 持有引擎对象时的 property-backed `Packed*Array` mutating call。
   - 示例：`var any: Variant = poly; any.polygon.push_back(Vector2.RIGHT)`
   - 这条路线最终要与 Godot 对齐，必须同时满足：
     - property leaf provenance 仍被 payload 冻结
     - runtime gate 只负责判断“当前 carrier family 是否需要当前层 writeback”
     - runtime gate 不能替代 provenance 本身，不能把 method result copy 与 property leaf copy 视为同一路线
   - 若后续实现把 runtime gate 当成唯一真源，而 payload 不再携带 property leaf / reverse-commit step，则这类动态引擎对象场景会成为最容易产生 silent wrong 的漂移点。

本节对 Step 5/6/7 的直接约束如下：

- `Packed*Array` 的 family 分类可以继续作为“当前层是否需要 writeback”的 gate 输入，但不得单独充当引擎类 route 语义的唯一真源。
- 引擎类 property 上的 `Packed*Array` mutating call 必须有 end-to-end 锚点，覆盖 typed owner 与 dynamic / `Variant` owner 两条路径。
- 引擎类 method 返回 `Packed*Array` 的 mutating call 也必须有 end-to-end 锚点，确认 gdcc 不会把 method result 错误写回原 receiver。

---

## 4. 总体实施顺序

建议按以下顺序推进，并保持每一步都可单独提交、单独回归：

1. 冻结 shared contract 与文档/注释漂移面
2. 引入 frontend-only shared writable route support
3. 扩展 CFG published surface，使 writable access chain 以单个 payload 冻结
4. 让 assignment / compound assignment 改为消费 shared writable route
5. 让 `RESOLVED` mutating receiver call 改为消费 shared writable route
6. 让 `DYNAMIC` / `Variant` receiver route 接通 runtime-gated writeback
7. 在 backend/codegen 中接通 runtime helper 与 gate emission
8. 用单元测试、codegen 测试、引擎集成测试与文档同步完成验收

---

## 5. Step 1: 冻结 shared contract 与漂移面

### 实施内容

- 把 dynamic call 的事实源从“plain receiver pass-through”收紧为：
  - 继续复用 `CallMethodInsn` surface
  - 但 receiver side 可附着 writable access-chain payload
- 把 “IndexStore self legality” 与 “mutating-call writeback necessity” 明确分离
- 把 “writable access chain 必须整体冻结/整体消费” 写进 frontend 计划与事实源文档
- 把 runtime `Variant` writeback helper 的正向语义固定为 `gdcc_variant_requires_writeback(...)`
- 清理会误导实现者的旧代码注释

### 关联文档与注释同步

- `frontend_dynamic_call_lowering_implementation.md`
- `frontend_rules.md`
- `frontend_lowering_cfg_pass_implementation.md`
- `frontend_lowering_(un)pack_implementation.md`
- `gdcc_type_system.md`
- `FrontendSequenceItemInsnLoweringProcessors`
- `FrontendAssignmentTargetInsnLoweringProcessors`

### 当前状态（2026-04-10）

- 已完成：Step 1 要求的事实源与实现注释已同步到当前代码库。
- 已完成：dynamic call 不再被定义为永久 receiver direct-pass-through 路线；receiver side writable access-chain payload、`IndexStoreInsnGen` 的非真源地位、以及 `Packed*Array` 的 requires-writeback family 分类都已写入对应事实源。
- 已完成：以 `FrontendCfgGraphTest`、`FrontendLoweringBodyInsnPassTest` 与 `FrontendCompileCheckAnalyzerTest` 作为 Step 1 的 happy/negative 回归锚点重新验证通过。

### 验收细则

- happy path：
  - 上述文档不再把 `DYNAMIC_FALLBACK` 定义成永久 receiver direct pass-through
  - 文档明确 `CallItem` 可承载 writable access-chain payload
  - 文档明确 `IndexStoreInsnGen` 不是 writeback 真源
  - 文档明确 `Packed*Array` 属于需要 writeback 的 value-semantic family
- negative path：
  - 仓库中不再残留把 writable chain 拆成 step item 再在 mutating call path 重组的旧合同表述
  - 不再残留把 runtime helper 写成 `is_shared` 负向语义、而其他文档使用 `requires_writeback` 正向语义的双重标准

---

## 6. Step 2: 引入 frontend-only shared writable route support

### 实施内容

- 在 `frontend.lowering.pass.body` 内新增 package-private shared support，例如：
  - `FrontendWritableRouteSupport`
  - `FrontendWritableAccessChain`
  - `FrontendWritableLeaf`
- 支持层最小职责固定为：
  - 解释 frozen payload
  - 校验 route 结构
  - materialize leaf read
  - 执行 leaf write
  - 执行 reverse commit
  - 为 runtime-gated writeback 预留 gate hook
- 明确这套 support 同时服务：
  - assignment target
  - compound assignment target current-value read + final commit
  - mutating receiver call

### 结构约束

- 不引入新的 public interface / 抽象层
- 不把这套 route 提升为公共 LIR model
- 不在 support 中重做 scope / type / callable resolution
- 不允许 support 重新求值 AST 子表达式

### 关联文档与注释同步

- `frontend_lowering_cfg_pass_implementation.md`
- `frontend_dynamic_call_lowering_implementation.md`
- `FrontendBodyLoweringSession`
- shared support 自身类注释

### 当前状态（2026-04-08）

- 已完成：`frontend.lowering.pass.body` 中已落地 package-private 的 `FrontendWritableRouteSupport`，统一承接 leaf read / leaf write / reverse commit / gate hook；static carrier family matrix 则已抽到 public `FrontendWritableTypeWritebackSupport`，供 Step 4 / Step 7 以及后续 runtime-gated path 共同复用。
- 已完成：assignment target、member load、subscript load，以及当前 `CallItem` 可表达的 direct-slot receiver leaf 都已改为复用同一套 shared support 入口。
- 已完成：原先 subscript assignment 的 property-backed ad-hoc writeback 补丁已被 shared route flow 替换，避免 assignment path 与 call path 各维护一套平行 reverse-commit 逻辑。
- 已完成：`FrontendWritableRouteSupportTest`、`FrontendLoweringBodyInsnPassTest`、`FrontendBodyLoweringSessionTest` 已作为 Step 2 的回归锚点验证通过。
- 已完成：shared support 的 payload 消费入口已经就位；后续 CFG published surface 的冻结工作由 Step 3 继续闭合。
- 已完成：`FrontendWritableRouteSupport.reverseCommit(...)` 已升级为逐层 carrier-threaded 模型；每个 step 都返回下一层 outer owner 的 carrier，gate 观察 current carrier 且返回 `false` 时只跳过当前层 writeback，但仍会把 carrier 提升到下一层 outer owner 并继续向外层 step 传播。

### 验收细则

- happy path：
  - assignment 与 call path 都能调用同一个 shared support 入口
  - support 能表达 root / step list / leaf / reverse commit 结构
  - malformed route 会在 support 层 fail-fast，而不是 silent fallback
- negative path：
  - 不引入新的 public route-assembly API 面
  - 不出现 assignment 与 call 各自维护一套平行 reverse-commit 逻辑

---

## 7. Step 3: 扩展 CFG published surface，冻结整条 writable access chain

### 实施内容

- 扩展 `AssignmentItem` 与 `CallItem`，让它们可携带单个 `FrontendWritableRoutePayload`
- payload 当前冻结为四层结构：
  - `routeAnchor`
  - `root = RootDescriptor(kind, anchor, valueIdOrNull)`
  - `leaf = LeafDescriptor(kind, anchor, containerValueIdOrNull, operandValueIds, memberNameOrNull, subscriptAccessKindOrNull)`
  - `reverseCommitSteps = List<StepDescriptor(kind, anchor, containerValueIdOrNull, operandValueIds, memberNameOrNull, subscriptAccessKindOrNull)>`
- CFG builder 按 source order 冻结 route，并复用同一 sequence 中已发布的 value ids 作为 payload 与 ordinary value items 的连接点
- `CallItem` 继续保留 ordinary `receiverValueIdOrNull + argumentValueIds + resultValueId` 合同；writable route payload 只额外承接 owner/leaf/writeback 语义
- `AssignmentItem` 当前仍同时保留 legacy `targetOperandValueIds`，但它们只继续服务 source-order sequencing 与 compound current-value read；final store / reverse commit 的真源已经收口到 mandatory payload

### payload 当前合同

当前实现不再使用“单个线性 `routeOperandValueIds` + step arity 事后切片”的编码方式，而是把每个 leaf/step 自己需要的 container / key operands 显式冻结在 descriptor 上。这样可以覆盖以下事实，而不必让 body lowering 重新解释 AST：

1. root provenance
2. 当前直接 leaf 是 direct-slot / property / subscript 哪一类
3. leaf 读写时需要的 container / key operand
4. reverse commit 每一层要写回到哪里
5. subscript route 预先冻结好的 access family（`GENERIC` / `KEYED` / `NAMED` / `INDEXED`）

### payload 最小不变量

- payload 只表达 writeback route，不表达 value 求值逻辑；不得承载 AST 重新求值的备用方案
- payload 只能引用同一 `SequenceNode` 中更早已发布的 value ids
- `RootDescriptor` 当前只允许：
  - `DIRECT_SLOT`
  - `SELF_CONTEXT`
  - `STATIC_CONTEXT`
  - `VALUE_ID`
- `LeafDescriptor` 当前只允许：
  - `DIRECT_SLOT`
  - `PROPERTY`
  - `SUBSCRIPT`
- `StepDescriptor` 当前只允许：
  - `PROPERTY`
  - `SUBSCRIPT`
- `SUBSCRIPT` leaf/step 当前固定只支持 1 个 key operand；若后续要支持多 key，必须同时扩展 payload、support 与 graph validation 合同
- `AttributeSubscriptStep` 的 access family 按 named-base / `Variant` 语义冻结，而不是按 prefix 静态类型事后推断
- payload 与 ordinary CFG items 共享 value ids，但不形成第二套 value 求值账本

### graph-level 校验合同

- `FrontendWritableRoutePayload` 构造器负责校验 root / leaf / step descriptor 的局部 shape 合同
- `FrontendCfgGraph` 在 publication 阶段额外校验：
  - `AssignmentItem` / `CallItem` 上挂载的 payload 只能引用同 sequence 中更早已发布的 value ids
  - 不允许 payload 引用“稍后才发布”或“来自别的 sequence”的值
- 这条 fail-fast 合同属于 graph publication，不下放到 body lowering 补救

### 当前状态（2026-04-08）

- 已完成：`FrontendWritableRoutePayload` 已落地为 Step 3 的 frozen CFG surface
- 已完成：`CallItem` 与 `AssignmentItem` 已支持携带 payload，并强制 route anchor 与 item anchor 对齐
- 已完成：CFG builder 已为 direct-slot / self / property / subscript / attribute-subscript / call receiver route 发布 payload
- 已完成：`FrontendCfgGraph` 已在 graph publication 阶段校验 payload 的局部 value-id 引用顺序
- 已完成：body lowering 的 call receiver leaf materialization 在 payload 存在时已直接消费 frozen route，而不是回退为旧的 receiver provenance 重建
- 已完成：assignment final-store consumer 已切到 payload-only route；legacy `targetOperandValueIds` 只继续保留给 source-order sequencing 与 compound current-target read

### 明确禁止

- 不为同一条 writable chain 再发布若干额外 `MemberLoadItem` / `SubscriptLoadItem` / step item 供 body lowering 事后拼接
- 不允许 lowering 把 AST 当作备用 receiver/target 事实源

### 关联文档与注释同步

- `frontend_lowering_cfg_pass_implementation.md`
- `frontend_dynamic_call_lowering_implementation.md`
- `FrontendCfgGraphBuilder`
- `CallItem`
- `AssignmentItem`

### 验收细则

- happy path：
  - payload 能无歧义表达 `root + leaf + reverseCommitSteps`
  - descriptor 上的 `containerValueIdOrNull + operandValueIds + member/access metadata` 足以支撑后续 lowering，不需要回头解释 AST
  - `CallItem` 与 `AssignmentItem` 可以在保留 ordinary operand surface 的同时附着同一条 frozen writable route
- negative path：
  - 对缺失 operand、逆序引用、未知 leaf/step kind 的 payload fail-fast
  - getter / key expression / receiver base 不会因为 route 构建而重复求值

---

## 8. Step 4: 让 assignment 与 compound assignment 改为消费 shared writable route

### 实施内容

- 把 assignment lowering 从“按 AST target 类型 + tail-step 分派”迁到“消费 single writable route payload”
- compound assignment 的读改写固定为：
  1. 解析 target route
  2. 读取当前 leaf value
  3. 求值 RHS
  4. 计算 compound binary result
  5. 对同一条 route 执行 leaf write + reverse commit
- 对静态已知 carrier family 的 reverse commit 引入 static gate：
  - `Array` / `Dictionary` / `Object` 这类 shared family 跳过当前层 writeback
  - 但仍必须把 carrier 提升到下一层 outer owner，并继续处理更外层 step
  - 不能等到 Step 7 的 runtime gate 才修正这类“静态已知 shared”路径
- 对已迁移为 payload consumer 的 assignment / compound assignment 形态执行硬切：
  - 禁止再回落到 legacy `targetOperandValueIds` 或旧 ad-hoc helper
  - 若检测到“双路径可能同时写回”的迁移残留，应 fail-fast 暴露，而不是 silent 兼容
- 移除 assignment-only AST tail-step registry；payload-backed assignment 不再走 body-lowering 期 AST replay

### 迁移期去重约束（避免双写回与 setter 副作用重复触发）

引入 shared writable-route support 之后，旧的 assignment 路径上存在的“窄 writeback 补丁”必须被整体替换掉，而不是与新 route 并存。最低限度需要满足：

1. 当某个 assignment target 已经改为消费 writable-route payload 时，禁止继续调用 `FrontendSubscriptInsnSupport.writeBackPropertyBaseIfNeeded(...)` 或其他 ad-hoc writeback 辅助逻辑。
2. 必须有回归测试锚定：同一条链式写入只触发一次 setter/一次外层回写，而不是“route reverse commit + 旧补丁回写”的双发。
3. 对“已命中 payload consumer，但 legacy targetOperand / 旧 helper 仍准备参与写回”的组合，必须 fail-fast，而不是允许两条路径同时产出 IR。
4. LIR 级回归必须覆盖：迁移后同一条 route 的 outer store 次数不增，setter / 副作用触发次数不因双路径并存而上升。

### 重点覆盖

- bare binding assignment
- property assignment
- subscript assignment
- property + subscript mixed chain
- nested chain compound assignment

### 多层 reverse commit 的 carrier 合同

Step 3 已经允许 payload 发布多层 `reverseCommitSteps`，因此 Step 4 不能再把 reverse commit 当作“对同一个 written-back slot 重复执行若干 store”。shared support 必须冻结以下合同：

1. `writeLeaf(...)` 返回的是“当前 leaf mutation 完成后，最内层 outer owner 的 carrier slot”，它只够驱动第一层 reverse commit。
2. reverse commit 必须按 `reverseCommitSteps` 的逆序执行，也就是先执行最靠近 leaf 的那一层，再向外层 owner 逐步传播。
3. 每个已应用的 reverse-commit step 都必须返回“下一层 outer owner 的 carrier slot”，供下一步继续使用；不得让外层 step 继续复用 leaf 阶段的 carrier。
4. `appendReverseCommitStep(...)` 的实现语义必须升级为“emit + return next carrier”，而不是当前的 `void` fire-and-forget 形式。最小返回合同固定为：
   - `InstancePropertyCommitStep` -> 返回 `receiverSlotId`
   - `SubscriptCommitStep(memberNameOrNull == null)` -> 返回 `baseOrReceiverSlotId`
   - `SubscriptCommitStep(memberNameOrNull != null)` -> 在完成 named-base store 与 `VariantSetNamedInsn` 后返回 `baseOrReceiverSlotId`
   - `StaticPropertyCommitStep` -> 视为 terminal step；payload 不得在其外侧再发布额外 reverse-commit step
5. 示例 `self.items[i].x += 1` 的 reverse commit 必须是：
   - leaf write: 把 `x` 写进 element，carrier = `element`
   - step 1 (`items[i]`): 把 `element` 写回 `items[i]`，carrier 更新为 `items`
   - step 2 (`self.items`): 把 `items` 写回 `self.items`
   当前那种“两个 step 都拿 `element` 作为 `writtenBackValueSlotId`”的实现必须被视为错误模型，不允许继续沿用到 Step 4/5。

### gate hook 的传播语义

reverse commit 的 gate hook 不是一个“任意跳过某些 step 的装饰器”，而是“当前 carrier 是否需要继续写回到外层 owner”的判定，因此它也必须绑定逐层 carrier：

1. gate 看到的参数必须是“本层准备写回的 current carrier slot”，而不是 leaf 初始 carrier 的常量副本。
2. 只有当某一步真正执行完成后，下一步 gate 才能看到更新后的 outer carrier。
3. 若某一步 gate 返回 false，该层 writeback 必须被跳过，但 reverse commit 仍要把 carrier 提升到下一层 outer owner，并继续判断更外层 step。
4. Step 7 的 runtime-gated writeback 必须直接复用这套“skip current layer, promote carrier, continue outer”语义，不能在 dynamic route 上再发明第二套 gate 传播规则。

### 静态 gate 与 current-carrier family 合同

Step 4 即使还未进入 `DYNAMIC` / `Variant` runtime gate，也不能对静态已知 shared family 无条件执行全部 reverse commit。必须冻结以下合同：

1. static gate 判断的输入只能是“当前 carrier 的语义类型家族”，不得根据 step kind、anchor 文本、outer owner 类型或 leaf 原始类型做替代推断。
2. `Array` / `Dictionary` / `Object` 这类静态已知 shared family，必须跳过当前层 writeback，但仍继续把 carrier 提升到外层 owner。
3. `String` / `Vector*` / `Packed*Array` 等静态已知 requires-writeback family，必须保留当前层 writeback。
4. `current carrier -> type/family` 的取值合同必须稳定且可单测：它来源于当前 carrier slot 在 lowering 阶段已经解析出的语义类型，而不是某个易漂移的 step 元数据或 anchor 约定。
5. Step 7 的 runtime gate 只是为 runtime-unknown carrier 补齐同一合同；不得让 Step 4 与 Step 7 各自维护一套 family 判定标准。

### 迁移风险：`StaticPropertyCommitStep` terminal fail-fast

`StaticPropertyCommitStep` 只能作为 terminal reverse-commit step 是正确合同，因为 static store 不会产出下一层 outer carrier。当前 shared support 已在 reverse commit 阶段对“non-terminal static step”直接 fail-fast，这修复了 silent wrong，但也意味着 Step 4 迁移 assignment / compound assignment consumer 时，任何“手工拼 `reverseCommitSteps`”或“从 legacy operand surface 临时重建链条”的 bug 都会暴露为 runtime crash。

### 应对措施

1. Step 4 迁移期间，assignment / compound assignment lowering 不得在 body lowering 内自行拼装或重排 `reverseCommitSteps`；只能消费 CFG builder 已冻结好的 payload。
2. 若迁移中新增或调整 payload 构造点，必须把 “`StaticPropertyCommitStep` 必须 terminal” 纳入 payload constructor / graph publication 的显式校验清单，尽量在进入 body lowering 前拦截 malformed chain。
3. shared support 当前的 fail-fast 必须保留，作为 publication 校验之外的最后防线；不得为了“兼容迁移期 payload”而回退为 silent skip 或 partial writeback。
4. Step 4 的负向测试必须覆盖这类 malformed chain：要么在 publication 阶段被拒绝，要么至少在 shared support 层抛错且 block 中不得残留部分 IR。

### compound assignment 的 RMW 序列合同

compound assignment 在 Step 4 中仍保持“current-value read item + `CompoundAssignmentBinaryOpItem` + payload-backed final store”的冻结结构，不得改变已冻结的 read-modify-write 形状，也不得引入额外的 getter/subscript get 或 boundary materialization：

1. current-value leaf read 只能 materialize 一次；禁止为 binary 计算、最终 store 或调试性补救再次读取同一 leaf。
2. RHS 的求值顺序必须保持在 current-value read 之后、final store 之前，不得因 payload consumer 重构而重排。
3. pack/unpack boundary 仍只允许出现在“最终写回到声明目标 / 调用边界”的位置；不得因为 compound assignment 改走 payload 而把 pack/unpack 提前到 current-value read 或中间 carrier store。
4. 回归测试必须锚定：`VariantGet*` / property getter / subscript get 次数不增，且 pack/unpack 指令数与位置不发生漂移。

### Godot 对齐事实（reverse-commit gate）

以下 Godot 事实是本节 gate 语义的漂移锚点。当前文档中的源码路径引用统一 pin 到 `Godot 4.5.1 stable` 源码树；若后续切换到别的 Godot 版本或具体 commit，必须连同本文档中的对齐描述一起复核并更新：

1. `modules/gdscript/gdscript_compiler.cpp` 在链式 writeback 循环中，会为每一层单独发出 `write_jump_if_shared(assigned)`、当前层 writeback 与 `write_end_jump_if_shared()`，然后继续把 `assigned` 提升为外层 `info.base`。
2. `modules/gdscript/gdscript_byte_codegen.cpp` 中 `write_jump_if_shared(...)` / `write_end_jump_if_shared()` 只包裹当前层 writeback block，不包裹整条链。
3. `modules/gdscript/gdscript_vm.cpp` 中 `OPCODE_JUMP_IF_SHARED` 只跳过当前 block；跳转落点之后 VM 仍继续执行后续字节码，因此外层链路仍会被处理。

### Godot GitHub 边界案例（避免把“对齐 Godot”误读成“Godot 这块没有历史缺陷”）

- `godotengine/godot#17558` 记录了 Godot 3 开始明确支持 `position.x += 42` 这类直接属性链写法，因此 Step 4 必须把 value-semantic builtin chained property writeback 当作一等 surface，而不是偶然兼容。
- `godotengine/godot#41319` 说明 Godot 自身历史上也出现过 chained assignment / intermediate route 的实际缺陷；因此 gdcc 不能仅凭“语义目标是 Godot”就省略回归测试。
- `godotengine/godot#85578` 说明 nested mutation / setter observability 在 Godot 中也存在边界争议或 bug；因此 Step 4 只对齐当前可复核的 per-layer writeback 语义，不把“setter 一定如何触发”当作未经验证的推论。

### 关联文档与注释同步

- `frontend_lowering_cfg_pass_implementation.md`
- `frontend_rules.md`
- `FrontendAssignmentTargetInsnLoweringProcessors`
- `FrontendBodyLoweringSession`

### 当前状态（2026-04-08）

- 已完成：`FrontendAssignmentTargetInsnLoweringProcessors` 不再重放 assignment target AST；最终 store 仅消费 mandatory `AssignmentItem.writableRoutePayload`。
- 已完成：`FrontendCfgGraphBuilder.buildAssignmentTargetOperands(...)` 已与 body lowering 合同收口到一致：所有 lowering-ready assignment target 都必须发布 writable-route payload，builder 不再保留 `null` 条件分支。
- 已完成：`FrontendBodyLoweringSession.lowerAssignmentTarget(...)` 对 payload-backed assignment 不再保留 legacy fallback；`targetOperandValueIds` 仅继续服务 CFG sequencing 与 compound current-value read。
- 已完成：Step 4 static gate 已接入 shared reverse commit；`Array` / `Dictionary` / `Object` carrier 现在按 current carrier slot type 跳过当前层 writeback，但仍继续向外层 promoted carrier 传播。
- 已完成：`FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(...)` 已作为 current-carrier family 合同的 public 代码锚点，并由独立单元测试直接锁定极性。
- 已完成：`FrontendCfgGraph` graph publication 会在进入 body lowering 之前拒绝 non-terminal static property commit step；shared support 中的 runtime fail-fast 继续保留为最后防线。
- 已完成：`FrontendLoweringBodyInsnPassTest` 已锚定 shared-property `Array` / `Dictionary` route 的 assignment 与 compound assignment 都不会再多发 outer `StorePropertyInsn`。
- 已完成：`FrontendLoweringBodyInsnPassTest#runWritesBackValueSemanticBuiltinPropertyChainAssignmentRoute` 已在 LIR 层锚定 `position.x = seed` 会先写 leaf `x`，再把 promoted carrier 写回 `self.position`。
- 已完成：`FrontendLoweringToCProjectBuilderIntegrationTest#lowerFrontendBuiltinChainedPropertyWritebackBuildNativeLibraryAndRunInGodot` 已用真实 Godot 运行时锚定 `position.x = value` 与 `position.x += delta` 都会把变更写回外层 `position` property。
- 当前实现固定为：compound current-value read 继续由 CFG 预先发布的 `MemberLoadItem` / `SubscriptLoadItem` 表达；Step 4 闭合的是“最终 store/commit 改为 payload-only，且 getter / `VariantGet*` / pack/unpack 次数不漂移”这一合同。

### 验收细则

- happy path：
  - `self.position.x = 1` 会执行 leaf write 并回写 `self.position`，且有 LIR + Godot engine 双重回归锚点
  - `self.prop[index] = rhs` 会对 `prop[index]` 写入，并在需要时回写 `self.prop`
  - `arr[i].x += 1` 会在同一条 route 上完成 read-modify-write 与外层 commit
  - `self.items[i].x += 1` 这类双层及以上 route 会在每一层 reverse commit 后更新 carrier，而不是把 leaf carrier 复用到所有外层 step
  - 静态已知 `Array` / `Dictionary` / `Object` carrier 会跳过当前层 writeback，但外层 step 仍继续按 promoted carrier 处理
  - compound assignment 迁移到 payload leaf-read 后，getter / `VariantGet*` / subscript get 次数不增，pack/unpack 仍只出现在最终写回边界
- negative path：
  - route malformed 时 fail-fast
  - `AssignmentItem` 缺失 writable-route payload 时必须在 CFG item 构造阶段直接失败，而不是等 body lowering 再发现合同缺洞
  - 不再依赖“只对 property identifier base 做窄 writeback patch”这类局部补丁
  - runtime gate 一旦拒绝某一层 step，不得执行该层 writeback；shared support 仍必须把 carrier 提升到下一层 outer owner 并继续判定后续 outer step
  - 对已迁移 payload consumer 仍触发 legacy targetOperand / 旧 helper 写回的路径，必须 fail-fast，而不是产出双写回 IR
  - gate / family 判定不得依赖 step kind、anchor 或不稳定的 carrier-type 旁路映射

---

## 9. Step 5: 实现 `RESOLVED` mutating receiver route

### 实施内容

- 为 `RESOLVED` instance call 增加 mutating receiver 判定：
  - gdextension metadata `isConst == false` -> may-mutate
  - 非 gdextension declaration site -> conservative may-mutate
- 对命中 writeback-sensitive family 且 receiver 可冻结为 writable route 的 call：
  - 先通过 shared support 取 `leafSlotId`
  - 再发同一条 `CallMethodInsn`
  - call 后生成并执行 commit plan
- 对静态已知不需要 writeback 的 family：
  - 允许保留 ordinary route 或省略 reverse commit

### 启用条件（必须同时满足，避免过度 writeback）

mutating receiver route 不是 “所有 instance call 的统一后处理”，它只能在满足以下条件时启用：

1. call site 已发布为 lowering-ready 的 instance route（`RESOLVED` 或 `DYNAMIC` 的 instance 形态）；lowering 不得补救缺失事实。
2. 该方法在语义上被判定为 may-mutate receiver（`isConst == false` 或 conservative may-mutate）。
3. receiver 静态家族属于 requires-writeback family（例如：`String` / `Vector*` / `Packed*Array` 等），否则禁止走 value-style reverse commit。
4. receiver provenance 可被冻结为 writable-route payload（即：CFG 已发布该 payload）；禁止 body lowering 回退成重跑 AST receiver 解释来“拼凑一条 route”。

### call lowering 的顺序约束（避免破坏可观测求值顺序）

当某个 call site 命中 mutating receiver route 时，body lowering 内部必须按以下顺序组织逻辑：

1. 解析 frozen writable-route payload，并 materialize “用于本次调用的 receiver leafSlotId”：
   - 若 CFG 已经发布 dedicated `receiverValueIdOrNull`，必须优先复用该已发布 slot，不得为了 call 自己再重读一次 property/subscript leaf。
   - payload-backed call 若缺失 dedicated receiver value slot，必须直接视为 CFG publication / body-lowering invariant violation；不得按 payload 临时补做 leaf read。
2. 再 materialize argument boundary（exact route 做 ordinary pack/unpack；dynamic route 仅保留已求值 argument slot，不新增 fixed-parameter boundary）。
3. 发出同一条 `CallMethodInsn`。
4. 根据 commit plan 执行 reverse commit（必要时带 runtime gate）。

这里的 “commit plan” 明确指 Step 4 已冻结好的 carrier-threaded reverse-commit core。call path 不得实现一套 receiver 专用的“多层 writeback 但 carrier 不更新”的平行逻辑；若 route 有两层及以上 reverse-commit step，post-call commit 也必须逐层消费每一步返回的 next carrier。

另外，`CallItem.writableRoutePayload` 对 mutating receiver call 的合同进一步冻结为：

- payload 的 `leaf` 继续描述“本次调用直接作用的 receiver leaf”
- `reverseCommitSteps` 必须额外包含“当前 leaf 提升后的第一层 commit step”
- 否则 property/subscript receiver 虽然保留了 provenance，却没有真正可执行的 post-call writeback plan

### 非目标

- 不引入新的 call LIR instruction
- 不把所有 instance call 一律改成 commit-after-call
- 不为 non-gdextension callable 新建完整的静态 mutability 推导系统

### 关联文档与注释同步

- `frontend_dynamic_call_lowering_implementation.md`
- `frontend_lowering_cfg_pass_implementation.md`
- `FrontendSequenceItemInsnLoweringProcessors`
- shared mutability helper 注释

### 当前状态（2026-04-10）

- 已完成：新增 `FrontendCallMutabilitySupport`，gdextension `isConst == true` 明确关闭 mutating receiver route，非 gdextension declaration site 继续按 conservative may-mutate 处理。
- 已完成：`FrontendCfgGraphBuilder` 发布 attribute-call `CallItem` 时，会把当前 receiver leaf 提升进 `reverseCommitSteps`，因此 property/subscript receiver call 不再只有 provenance 而缺失实际 post-call commit plan。
- 已完成：graph publication 现在把“payload-backed call 缺失 dedicated receiverValueIdOrNull”视为 invariant violation；`FrontendBodyLoweringSession.materializeCallReceiverLeaf(...)` 也不再保留按 payload 临时补做 leaf read 的静默回退。
- 已完成：`FrontendBodyLoweringSession.materializeCallReceiverLeaf(...)` 现在对 payload-backed call 优先复用已发布 receiver value slot，仅 direct-slot payload 才回到真实源 slot；property/subscript receiver 不再为 call 本身重复 materialize 一次 leaf。
- 已完成：`FrontendSequenceItemInsnLoweringProcessors` 的 exact `INSTANCE_METHOD` call 现在会在 declaration may-mutate 且 payload 已发布时，于同一条 `CallMethodInsn` 后复用 shared static gate + reverse-commit core 执行 post-call writeback。
- 已完成：新增/更新测试锚点：
  - `FrontendCallMutabilitySupportTest`
  - `FrontendCfgGraphBuilderTest`
  - `FrontendLoweringBodyInsnPassTest`

### 验收细则

- happy path：
  - simple binding value-semantic receiver 不再先复制到 temp 再 mutate
  - property-backed value-semantic receiver 会在 call 后回写 property
  - nested property/subscript receiver 会沿 owner route reverse commit
  - `const` method 不进入 mutating receiver route
  - 非 gdextension declaration site 会保守进入 may-mutate 路线
- negative path：
  - `Object` / `Array` / `Dictionary` 不会被错误拉入 value-style writeback
  - 不会为了 mutating receiver call 重新做 overload 选择或 callable resolution
  - payload-backed call 缺失 dedicated receiverValueIdOrNull 时，必须在 graph publication 或 body-lowering invariant 处 fail-fast，而不是静默重读 receiver leaf

---

## 10. Step 6: 引入 direct-slot alias value surface，消除 receiver dead temp

### 背景

当前 Step 5 已经修复了 mutating receiver route 的求值顺序与 writeback 合同，并把“payload-backed call 缺失 dedicated receiver slot”收紧为 fail-fast。但 direct-slot receiver 仍存在一个结构性不对齐：

- CFG builder 先把 `IdentifierExpression` / `SelfExpression` 这类 direct-slot receiver 作为 ordinary `OpaqueExprValueItem` 发布成 `vX`
- body lowering 再把该 `vX` 物化成 `cfg_tmp_vX`
- 真正的 mutating call lowering 又因为 direct-slot payload 要求“原位作用到真实源 slot”，绕过 `cfg_tmp_vX` 直接对源 slot 调用

结果是：

- CFG surface 看起来认为 receiver 已经被求值并冻结到 dedicated `vX`
- call lowering 实际消费的却不是 `cfg_tmp_vX`，而是另一路 direct-slot source slot
- `cfg_tmp_vX` 因此退化成 dead value，但当前 backend 没有通用 dead-temp 清理，它仍会进入 LIR variable 表、`__prepare__` 初始化，某些类型后续还可能进入销毁流程

这一形态虽然在当前 direct-slot 窄面上通常不会立刻产出错语义，但它违反了“dedicated receiver value slot 应该是 call 真源”的长期合同，也会给后续复杂 writable target / inplace lowering 带来双账本风险。

### 修改目的

- 保留 Step 5 已冻结的 dedicated `receiverValueIdOrNull` 合同，不退回到“payload 代替 receiver operand”的弱模型
- 让 direct-slot receiver 的 published `valueId` 真正表示“一个可消费的 receiver surface”，而不是“先发一个不会被真正使用的 temp”
- 从根源上消除 `cfg_tmp_vX` 这类 dead temp，而不是把问题留给 backend 或未来优化 pass
- 为后续更复杂的 writable/inplace lowering 奠定统一的 `valueId -> materialization kind` 基础，但第一版严格收敛在 direct-slot mutating receiver 这一个窄场景

### 总体策略

新增一种 frontend-only 的 published value 形态，用来表达“这个 `valueId` 绑定到某个真实 source/local/self slot”，而不是“这个 `valueId` 必须先物化为 `cfg_tmp_vX`”。这一 alias value 仍然是 ordinary CFG value surface，可被：

- `CallItem.receiverValueIdOrNull`
- `operandValueIds`
- 现有 graph publication 的本地 value-id 顺序校验

等现有机制正常引用；但在 body lowering 内部，它会解析成 source-slot alias，而不是 temp slot。

### Sub-step 6.1: 引入 alias value 的 CFG surface

#### 当前状态（2026-04-10）

- 已完成：frontend CFG 已新增 `DirectSlotAliasValueItem`，用于表达“published value id 直接别名到可信 direct slot”这一窄面 surface。
- 已完成：当前 alias publication source 已明确收口为 explicit `SelfExpression` 与 `IdentifierExpression + LOCAL_VAR/PARAMETER`；`IdentifierExpression + SELF` 现在在 builder 与 body lowering 双侧都直接 fail-fast，implicit self fallback 继续留在 call execution 路径。
- 已完成：`IdentifierExpression + CAPTURE` 已从 alias eligibility 中移出。当前 lambda/capture lowering 与 capture storage 语义尚未冻结，`CAPTURE` 继续只作为前端绑定/作用域模型里的预留类别存在，不提前承诺 Step 6 alias surface。

#### 修改任务

- 新增一个 frontend CFG value item，表达“published value id 直接别名到真实 storage slot”
- 第一版的 alias eligibility 必须按“语法入口 / published binding / runtime fallback”拆开定义，不能把不同概念混写成一张允许列表：
  - `IdentifierExpression` + `LOCAL_VAR`
  - `IdentifierExpression` + `PARAMETER`
  - explicit `SelfExpression`
- `IdentifierExpression` + `CAPTURE` 当前不属于 alias eligibility：
  - lambda/capture lowering 仍 deferred
  - capture 的真实 storage / rebinding / callable-side effect contract 尚未冻结
  - 因此 Step 6 不应提前把它并入 live-slot alias root；待 lambda 实现时再单独评估
- `IdentifierExpression` + binding kind `SELF` 不是一个独立的当前 source category：
  - 当前代码库里 `FrontendTopBindingAnalyzer` 只会对 `SelfExpression` 发布 `FrontendBindingKind.SELF`
  - 因此 builder 与 body lowering 都必须把它当作 contract violation 直接 fail-fast，而不是把它当成可恢复的 receiver/value surface
- implicit self receiver 也不是 alias value category：
  - 这里指 instance call 的 `receiverValueIdOrNull == null`，随后在 body lowering 由 `resolveInstanceCallReceiver(...)` fallback 到 `self`
  - 这条路没有 dedicated published receiver `valueId`，因此它属于 call execution fallback，而不是 Step 6.1 的 alias publication surface
- `RootKind.SELF_CONTEXT` 继续只表示 property/subscript route 的 owner provenance；不得与 explicit `SelfExpression` 的 direct-slot alias root 混同
- 该 item 仍发布 ordinary `resultValueId`
- graph publication 必须保证：
  - alias value 仍属于 ordinary value surface，可参与同 sequence 的 value-id 顺序合同
  - alias root 必须是 storage-backed binding；禁止 property / subscript / call result / type-meta root 混入

#### 验收标准

- CFG graph 能区分：
  - 普通 temp-producing value
  - merge value
  - direct-slot alias value
- 计划文本必须显式区分：
  - explicit `SelfExpression`
  - 非法的 `IdentifierExpression + SELF` surface，以及 builder / body lowering 都会拒绝它这一事实
  - implicit self receiver fallback
- alias value 若绑定到非 storage-backed root，必须在 CFG surface / graph publication fail-fast
- 现有 `receiverValueIdOrNull` 合同保持不变；不允许为了支持 alias value 而放宽 dedicated receiver slot 约束

### Sub-step 6.2: 升级 body lowering 的 value materialization 模型

#### 当前状态（2026-04-10）

- 已完成：body lowering 已把 `valueId -> GdType` 的旧模型升级为显式 `CfgValueMaterialization(type, kind, aliasSourceAnchor)`。
- 已完成：ordinary value / merge value / source-slot alias value 三种 materialization shape 现在都由 `FrontendBodyLoweringSupport.collectCfgValueMaterializations(...)` 统一发布；alias value 不再声明 `cfg_tmp_*` 变量。

#### 修改任务

- 把当前“`valueId -> GdType` + `slotIdForValue(...)` 默认映射到 `cfg_tmp_*/cfg_merge_*`”的模型升级为显式 materialization kind
- 最小形态至少要能区分：
  - temp-backed value
  - merge-backed value
  - source-slot alias value
- `slotIdForValue(...)` 对 alias value 必须直接返回真实源 slot
- `declareCfgValueSlots()` 必须跳过 alias value，不得再为其创建 `cfg_tmp_vX`

#### 验收标准

- direct-slot alias value 不再在 LIR function variable 表中生成对应 `cfg_tmp_vX`
- backend 不再因为该 `valueId` 自动产出多余的 `__prepare__` 初始化或后续销毁逻辑
- 普通 temp value 与 merge value 的既有路径不受影响

### Sub-step 6.3: 让 CFG builder 在 direct-slot mutating receiver route 上发布 alias value

#### 当前状态（2026-04-10）

- 已完成：CFG builder 现在只会在 direct-slot mutating receiver 路径上把原先的 `OpaqueExprValueItem(identifier/self)` 收口成 `DirectSlotAliasValueItem`；property/subscript/call-result receiver 未扩散。
- 已完成：builder 已把 direct-slot receiver alias 的安全性改成显式语义分类，而不再把“参数 AST 里有没有 `AssignmentExpression`”当成唯一代理条件。
- 已完成：当前策略明确区分 root category：
  - explicit `SelfExpression` 视为稳定 root，可继续发布 alias
  - `IdentifierExpression + LOCAL_VAR/PARAMETER` 只有在后续 argument subtree 全部落在已证明 no-rebinding 的子集里时才发布 alias
  - `IdentifierExpression + CAPTURE` 当前不在 alias root 支持面内，若 future binding layer 正式产出 capture 也必须先 fail-fast，待 lambda/capture lowering 与 storage semantics 冻结后再评估
  - `IdentifierExpression + SELF` 在 builder / body lowering 双侧仍 fail-fast
  - implicit self fallback 仍留在 call execution 路径，不属于 alias publication
- 已完成：对 identifier-backed root，一旦 argument 求值包含 nested `CallExpression` / `AttributeCallStep` 或其它当前尚未证明 no-rebinding 的 effect-open surface，builder 会直接回退到 ordinary temp snapshot，而不是继续发布 live-slot alias。

#### 修改任务

- 在 direct-slot mutating receiver 的 builder 路径上，不再先发布 ordinary `OpaqueExprValueItem(identifier/self)` 再让 body lowering 绕开它
- 改为直接发布 alias value，再由 `CallItem.receiverValueIdOrNull` 指向该 alias `valueId`
- direct-slot 以外的 property / subscript / call-result / type-meta receiver 保持现有 published surface，不在本步扩散
- builder 仍必须保证 receiver/base/argument 的 source order 不变
- builder 只允许在以下语义前提成立时发布 direct-slot receiver alias value：
  - receiver expression 本身是 direct-slot root
  - receiver 仍有 dedicated published `valueId`
  - 从 receiver publication 到 call 真正发出之间，后续 argument 求值不可能把该 direct slot 重绑定成另一个 storage
- 这条前提必须收口成显式 eligibility helper / invariant checklist，而不是散落在“当前实现碰巧安全”的隐式假设里
- eligibility helper 必须先按 root category 做分类，而不是把“允许的 root”混写成一张扁平列表：
  - explicit `SelfExpression`
  - `IdentifierExpression + LOCAL_VAR`
  - `IdentifierExpression + PARAMETER`
- `IdentifierExpression + CAPTURE` 当前明确不在 alias root 支持面内：
  - CAPTURE 仍可能出现在 binding/type/scope 相关模型里
  - 但这不代表 Step 6 alias publication 已开放 capture-backed receiver
  - 在 lambda/capture lowering 落地前，这类 root 必须继续 fail-fast
- explicit `SelfExpression` 的 alias 安全性来自“`self` slot 不可被用户代码重绑定”，不是来自 argument subtree 没有某个特定语法节点
- 对 `IdentifierExpression + LOCAL_VAR/PARAMETER`，当前第一版 no-rebinding 子集只允许：
  - ordinary leaf read
  - 由已分类安全子表达式组合而成的 unary / binary / cast / type-test / conditional / property-read / subscript-read
- 对这些 identifier-backed root，当前必须把以下 argument surface 视为 effect-open，并回退 ordinary temp snapshot：
  - bare `CallExpression`
  - `AttributeExpression` 内的 `AttributeCallStep`
  - 任何当前未被显式列入 no-rebinding 子集的 expression kind
- 当前代码库调查表明，这个陷阱暂时不会触发，但只能算“现状证据”，不能算永久豁免：
  - attribute-call 路径先冻结 receiver，再通过 `buildArgumentValues(...)` 继续求值 arguments
  - `buildValue(...)` 当前会直接拒绝 `AssignmentExpression` value surface，因此诸如“在 call argument 里通过赋值表达式重绑定 receiver local”的形式尚未进入 lowering-ready compile surface
- 但这条现状证据并不足以证明 alias soundness 已经封闭：
  - `AssignmentExpression` 只是当前最直观的 rebinding surface 示例，不是完整合同
  - 即使未来不新增新的 rebinding AST 节点，只要普通-looking `CallExpression` 后续能通过 lambda/capture/callable surface 间接重绑同一 capture/local，alias 也会被穿透
- 但 Step 6 计划必须把这类 trap 视为高危检查项：
  - 典型例子是 future surface 中的 `values.push_back(values = other)` 或其它“argument 内重绑定 receiver local”形式
  - 另一类更隐蔽的例子是 future lambda/capture surface 中的 `values.push_back(rebind())`，其中 `rebind()` 在被调用体里把同一个 capture/local 重绑到新 storage
  - 一旦 compile surface 后续允许 assignment expression 或其它 value-level rebinding forms，builder 必须 fail-closed，或退回 ordinary temp snapshot 路线，直到 alias soundness 合同被重新冻结

#### 验收标准

- 对 `values.push_back(seed)` 这类 direct-slot mutating receiver：
  - CFG 仍发布 dedicated receiver `valueId`
  - 但该 `valueId` 不再对应 `cfg_tmp_vX`
  - call lowering 仍通过 `receiverValueIdOrNull` 复用 receiver，而不是绕过 ordinary call surface 另走旁路
- alias publication 的实施计划必须显式包含：
  - “receiver 在 call 执行前不可能被重绑定”的 eligibility 说明
  - “遇到 effect-open / 未分类 argument surface 时回退 ordinary temp snapshot，而不是继续 alias”这一保守策略
  - 对 explicit `SelfExpression` / identifier binding / implicit self fallback 的分类讨论
- property-backed / subscript-backed receiver 的 Step 5 行为保持不变

### Sub-step 6.4: 清理 direct-slot receiver lowering 的特殊绕行逻辑

#### 当前状态（2026-04-10）

- 已完成：`materializeCallReceiverLeaf(...)` 不再保留“payload 是 direct-slot 时自己回读真实源 slot”的特殊绕行逻辑。
- 已完成：direct-slot mutating receiver 之所以仍能拿到真实源 slot，是因为 `receiverValueIdOrNull` 现在已经是 alias-backed value；body lowering 只消费 frozen value materialization，不再补做第二套 receiver 解释。

#### 修改任务

- 在 alias value 正常工作后，收敛 `materializeCallReceiverLeaf(...)` 中“direct-slot payload 特判回到真实源 slot”的实现路径
- 目标形态是：
  - direct-slot call receiver 仍优先消费 dedicated `receiverValueIdOrNull`
  - 之所以拿到真实源 slot，是因为该 `valueId` 本身就是 alias value，而不是 call lowering 自己另起一条解释路径
- 保留必要的 invariant 注释，明确 body lowering 仍然只消费 frozen facts，不重新解释 AST

#### 验收标准

- 代码层面不再出现“同一个 payload-backed direct-slot receiver，CFG 发布一条 ordinary receiver value，call lowering 再专门绕开它”的双真源结构
- `materializeCallReceiverLeaf(...)` 的 direct-slot 分支只剩“复用 alias-backed receiver value”语义，不再承担“修正 builder 已发布 dead temp”的职责

### Sub-step 6.5: 用测试把“没有 dead temp”锚定下来

#### 当前状态（2026-04-10）

- 已完成：builder/support/body 三层单测都已覆盖 direct-slot alias value 的 happy/negative path。
- 已完成：当前测试同时锚定了“mutating direct-slot receiver 无 dead temp”“explicit self receiver 无 dead temp”“non-mutating/dynamic/property-backed receiver 仍停留在 ordinary temp/payload surface”这三类边界。

#### 修改任务

- 新增 frontend lowering 单测，直接锚定：
  - direct-slot mutating receiver call 的 receiver `valueId` 不再声明 `cfg_tmp_vX`
  - 不再发 `AssignInsn cfg_tmp_vX, <sourceSlot>`
- 新增更低层或 backend/codegen 测试，锚定：
  - 目标函数变量表中不存在该 dead temp
  - 生成的 C 中不再出现对应 dead local / dead assignment
- 保留 Step 5 的既有正向行为测试，确保本步只消除 dead temp，不改变调用语义

#### 验收标准

- happy path：
  - `values.push_back(seed)` 不再生成 receiver dead temp
  - `self` direct-slot receiver 也不再生成等价 dead temp
  - property/subscript receiver 仍继续复用 ordinary published receiver slot
- negative path：
  - 不能通过“删掉 dedicated receiverValueIdOrNull”来伪造通过
  - 不能通过后端偷偷删代码掩盖 frontend 双账本仍然存在的事实

### 潜在风险点与避免方式

1. snapshot 语义被误伤。
   - 成因：若把 alias value 泛化到所有 identifier/self ordinary read，就会把“当时的值”错误地降级成“live slot 视图”。
   - 避免方式：第一版 alias value 只允许出现在 direct-slot mutating receiver 这一条窄 surface；普通 ordinary read 继续保留 temp/merge 语义，不做全局替换。
2. builder 与 body 陷入双路径并存。
   - 成因：builder 已开始发布 alias value，但 call lowering 仍保留旧的 direct-slot 绕行旁路，导致同一个 receiver 既能经 alias `valueId` 进入，又能经 payload 自己回到 source slot。
   - 避免方式：把 Sub-step 6.4 视为本步必做收口项；alias value 一旦启用，对 direct-slot receiver 的 call lowering 必须只走 dedicated receiver `valueId`。
3. alias value 渗透到 property/subscript/call result。
   - 成因：为了“多消灭几个 temp”而把非 storage-backed root 也塞进 alias 模型，会重新引入重复读取、副作用顺序漂移、链式来源丢失等问题。
   - 避免方式：graph publication 对 alias root 做硬约束，只允许 storage-backed direct-slot root；所有非 direct-slot route 继续走现有 ordinary published value + payload 合同。
4. 只删 `AssignInsn` 没删 variable，造成“逻辑上没用、后端仍有成本”的半吊子修复。
   - 成因：如果实现只停留在 lowering 不发 assignment，但 `declareCfgValueSlots()` 仍为 alias `valueId` 创建变量，那么 dead temp 只是在指令级消失，并未在 backend 成本上消失。
   - 避免方式：本步必须显式升级 `valueId -> materialization kind` 模型，并在 variable declaration 阶段跳过 alias value。
5. 测试只锚定 LIR 指令，没锚定 variable/backend 形态，后续容易回归成“没有 assignment 但仍有 dead local”。
   - 避免方式：同时添加 LIR 级和 backend/codegen 级断言；前者锚定没有 `AssignInsn cfg_tmp_vX, ...`，后者锚定不存在对应 variable/C local。
6. alias publication 的 soundness 被 argument 求值窗口破坏。
   - 成因：receiver 已被 builder 发布为 live-slot alias，但后续 argument 求值若允许 assignment expression 或其他 rebinding form，就可能在 call 真正执行前把同一个 local/parameter/capture 重新绑定到新值，导致 call object 不再代表 receiver publication 时的快照。
   - 更隐蔽的成因：即使 argument AST 自身没有新的 rebinding node，只要某个 ordinary-looking `CallExpression` 在 future lambda/capture/callable surface 下能于被调用体内重绑同一 capture/local，alias 仍会被静默穿透。
   - 避免方式：第一版必须把“receiver 在 call 执行前不可能被重绑定”写成显式 eligibility 规则和检测清单；identifier-backed root 只允许 proven no-rebinding argument 子集，effect-open / 未分类 surface 一律回退 ordinary temp snapshot。这样即使 compile surface 后续扩展，alias 也不会因为漏改一处 `instanceof AssignmentExpression` 而继续复用 live slot。

### 关联文档与注释同步

- `frontend_dynamic_call_lowering_implementation.md`
- `frontend_lowering_cfg_pass_implementation.md`
- `FrontendCfgGraphBuilder`
- `FrontendBodyLoweringSession`
- `FrontendSequenceItemInsnLoweringProcessors`
- 任何描述 `valueId -> cfg_tmp_*` 默认关系的注释，都必须更新为“并非所有 ordinary published value 都物化为 temp；direct-slot alias value 是有意保留的特例”

### 完成标志

- direct-slot mutating receiver route 仍保留 dedicated `receiverValueIdOrNull`
- body lowering 不再依赖“direct-slot payload 特判绕回 source slot”来纠正 dead temp
- direct-slot receiver 的 published `valueId` 不再在 LIR/backend 中生成 dead temp
- property/subscript/dynamic route 的既有行为与合同不被本步扰动

---

## 11. Step 7: 实现 `DYNAMIC` / `Variant` route 与 runtime-gated writeback

### 实施内容

- 对 `DYNAMIC` instance call：
  - 继续复用 `CallMethodInsn` surface
  - receiver 侧允许保守生成 commit plan
  - 是否真正执行某一层 reverse commit 由 runtime helper 决定
- shared writable-route support 必须同时保留两条 reverse-commit API：
  - `reverseCommit(..., ReverseCommitGateHook)`：静态 gate 版本，只接受编译期布尔决策，不负责发 CFG 分支
  - `reverseCommitWithRuntimeGate(...)`：动态 gate 版本，负责在 body lowering 中发出 per-layer branch，并返回 continuation block 给后续 outer lowering 继续附着指令
- 静态 helper 负责：
  - 判断已知 `GdType` family 是否 requires writeback
- runtime helper 负责：
  - `gdcc_variant_requires_writeback(const godot_Variant *value)`
  - 对 runtime `Variant` 承载值做 family 分类
- codegen 在 unknown owner 上发出 gate：
  - `if (gdcc_variant_requires_writeback(&written_back_value_variant)) { ... }`

### Godot 对齐范围

- 对齐的是 `JUMP_IF_SHARED` 的可观测写回决策
- 不对齐其 bytecode opcode 形式

### gate 的判定对象与粒度（必须与 Godot 可观测语义对齐）

为避免实现错位，本计划把 runtime gate 的语义钉死为：

1. gate 判断的是“当前准备写回到外层的那个值”是否 requires writeback，而不是外层 base/owner 的类型是否 shared。
2. gate 的粒度是“每一层 reverse commit step”：当某一层要写回的值是 runtime-unknown（通常是 `Variant`）时，该层 writeback 必须被 `gdcc_variant_requires_writeback(&written_back_value_variant)` 包裹。
3. 这与 Godot 的 `JUMP_IF_SHARED(assigned)` 在可观测层面等价：
   - Godot：shared 值跳过 writeback
   - gdcc：只有 requires-writeback 的值才执行 writeback
4. gate 判断与 step 应用都必须基于“当前层 carrier”逐层推进：
   - step 应用前先对 current carrier 做 gate
   - step 应用后把 carrier 更新为该 step 的 outer owner
   - 若 gate 为 false，则跳过当前层 writeback，但仍把 carrier 提升到下一层 outer owner，以便继续判断更外层 step
5. shared support 中的 runtime-gated API 必须先走静态快捷路径：
   - `Array` / `Dictionary` / `Object` / primitive 等静态已知不需要 writeback 的 carrier 直接跳过当前层，不得为它们额外发 runtime branch
   - 静态已知需要 writeback 的 concrete carrier 直接 inline 应用当前层，不得为了“统一形状”而强制包一层 branch
  - 只有 `Variant` 这类 runtime-open carrier 才允许调用 runtime gate emitter；runtime helper 只是补齐 `FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(...)` 不能回答的未知分支，而不是第二套独立策略

### Sub-step 7.1: 收口 backend value-semantic slot write 的 alias-safe copy 规则

#### 背景与问题链路

- Step 7 runtime-gated writeback 的 integration test 已经证明：dynamic `Variant` receiver route 本身可以闭环。
- 但这轮集成同时暴露了 backend 另一个更底层的问题：non-object / value-semantic slot write 若把 borrowed source 统一降成
  `destroy(target) -> target = copy(source_ptr)`，
  那么在 source 与 target 指向同一底层 storage 时会读到已被 destroy 的值。
- 显式例子是 direct self-assign：
  - `s = s`
  - 若生成
    `godot_String_destroy(&$s); $s = godot_new_String_with_String(&$s);`
    则 copy ctor 读取的是已被销毁的 source slot。
- 更隐蔽的例子是 indirect alias：
  - target 是 backing field / local slot
  - RHS 是 `ref=true` parameter、其它 pointer-like alias surface，或 future 其它可间接指向同一 slot 的来源
  - 即使 `source` 与 `target` 不是同一个标识符，`source_ptr` 仍可能等于 `&target_slot`
- 因此这里只修 direct self-assign 不够；Step 7 需要的是一条更通用的 backend alias-safety 合同。

#### 修改任务

- 在 `CBodyBuilder` 的 non-object value-semantic 写槽路径上，把“如何 copy RHS”从单纯的 type-based 规则升级为：
  - copy policy
  - alias safety policy
- 对 `proven no-alias` 的 borrowed RHS，继续保留当前优化形状：
  - `destroy(target); target = godot_new_<Type>_with_<Type>(source_ptr);`
- 对 `may-alias` 的 borrowed RHS，必须先把 RHS 稳定到独立 carrier，再 destroy target：
  - 不允许回到旧的“浅赋值 temp + destroy temp”错误形状
  - 必须保证 stable carrier 的生命周期独立于 target 的旧 storage
  - 这条路径的正确性不依赖“alias 是否真的发生”，而依赖“即使真的 alias 也仍然安全”
- alias safety 判断不能只靠“source variable 与 target variable 同名”：
  - direct self-assign 必须命中
  - `ref=true` / address-bearing / pointer-like source 只要无法证明与 target 不别名，就必须保守视为 `may-alias`
  - future 新增的 alias-open value source 若未被显式证明 no-alias，也必须默认落入 `may-alias`
- `LoadPropertyInsnGen` / `StorePropertyInsnGen` 等已改成“直接从 source address copy 到 target slot”的路径不得被回滚；
  Sub-step 7.1 解决的是 alias-safe fallback，而不是撤销当前 backing-field 生命周期修复。

#### no-alias / may-alias 分类合同（按当前代码库冻结）

- 这一步不追求“最激进地多判 no-alias”，而追求 soundness：
  - 只有当当前 provenance 足以证明 `source_ptr` 不会落到 target 的旧 storage 上时，才能判 `proven no-alias`
  - 只要证明链路断了，就必须判 `may-alias`
- 分类必须集中在单点 helper 中完成，例如 `classifyNonObjectSlotWriteAliasSafety(TargetRef target, ValueRef value)`；
  不允许把 alias 判断散落到 `assignVar(...)` / `prepareRhsValue(...)` / individual generator 的局部 if-else 中
- 分类 helper 必须对当前 sealed surface 做穷尽分析，而不是留“默认乐观”分支：
  - `ValueRef` 当前是 sealed：`VarValue` / `ExprValue` / `AddressableExprValue` / literal values / `TempVar`
  - `TargetRef` 当前是 sealed：`VarTargetRef` / `ExprTargetRef` / `TempVar` / `DiscardRef`
  - future 若新增新的 `ValueRef` / `TargetRef` 子类，alias 分类逻辑必须同步扩展；未分类的新 surface 一律视为 `may-alias`

#### 当前代码库里可直接依赖的证明事实

- `targetOfVar(...)` 明确拒绝 `ref=true` target：
  - 因此 `VarTargetRef` 总是本函数拥有的 concrete slot，而不是外部 alias pointer
- backend 文档已冻结：
  - function parameters 永远 `ref=true`
  - function locals 永远 `ref=false`
- `renderValueAddress(...)` 的 source-address 形状已经固定：
  - `VarValue(ref=false)` -> `&$var`
  - `VarValue(ref=true)` -> `$var`
  - `AddressableExprValue` -> `&(expr)`
  - `ExprValue` -> 先 materialize fresh temp，再取 `&temp`
- `ExprTargetRef` 当前只是 raw C lvalue expression，不携带结构化 field/index provenance：
  - 不能靠解析字符串去“猜测”它与某个 RHS 必然不别名
  - 因此只要 target 落到 `ExprTargetRef`，分类必须更保守

#### `proven no-alias` 的允许列表与证明链路

- `VarTargetRef(local/ref=false)` + `VarValue(otherLocal/ref=false)`，且 source/target 不是同一个 variable：
  - 证明链路：
    - target 是本地拥有的 concrete slot
    - source address 形状是 `&$otherLocal`
    - 两个 local slot 在当前 C lowering 里是不同 storage
  - 示例：
    - `a = b`
    - `AssignInsnGen -> targetOfVar(a) + valueOfVar(b)`
- `VarTargetRef(...)` / `TempVar target` + `ExprValue source`：
  - 证明链路：
    - `ExprValue` 在 `renderValueAddress(...)` 中先 materialize 成 fresh temp
    - target old storage 的 destroy 发生在这个 temp 已经稳定之后
    - 因此 copy helper 读取的是 temp，不是 target 本身
  - 示例：
    - builder 内部某个 casted / composed expression 赋给 local slot
- 任意 target + literal-like source：
  - 包括 `StringPtrLiteralValue` / `CStringLiteralValue` / `StringNamePtrLiteralValue`
  - 证明链路：
    - 这些 source 不代表已有 managed storage，不存在“与 target old storage 同址”的问题
- `TempVar target` + `TempVar source`，且两者不是同一个 temp：
  - 证明链路：
    - temp 名称与对象 identity 都由 builder fresh 分配
    - 不同 temp 即不同 storage

#### `may-alias` 的必保守列表与证明链路

- `VarTargetRef(x)` + `VarValue(x)`：
  - 这是 direct self-assign
  - 证明链路：
    - source address 形状就是 `&$x`
    - target old storage 也是 `$x`
    - 若按 no-alias 走 `destroy(x) -> copy(&x)`，会读已销毁的 source
  - 示例：
    - `s = s`
- 任意 non-object target + `VarValue(ref=true parameter)`：
  - 这是第一优先级的 indirect alias-open surface
  - 证明链路：
    - parameter 在当前代码库永远是 `ref=true`
    - `renderValueAddress(...)` 对这类 source 直接返回 `$param`
    - `$param` 指向 caller 提供的 storage，可能正是 target 的地址
  - 示例：
    - setter-self `self.payload = value`
    - 其中 `value` 可能就是 `&self->payload`
- 任意 non-object target + `AddressableExprValue source`：
  - 证明链路：
    - 这类 source 明确表示“借用现有 lvalue storage 的地址”
    - 当前 builder 没有结构化 provenance 去证明该 storage 与 target 不同
    - 因此除非 future 引入更强的 place/reference model，否则必须保守
  - 示例：
    - `valueOfAddressableExpr("$self->field", ...)` 这类 surface
- `ExprTargetRef target` + 任意 address-bearing source：
  - address-bearing source 包括：
    - `VarValue(ref=true)`
    - `AddressableExprValue`
    - future 任何可直接代表现有 storage 地址的 `ValueRef`
  - 证明链路：
    - `ExprTargetRef` 当前没有结构化 provenance
    - builder 无法可靠证明 target 与 source-address 指向不同 storage
  - 示例：
    - setter-self backing field write
    - future 若有 index/store helper 直接把 element lvalue 作为 `ExprTargetRef` 也必须同样保守
- 任何 future 新增但尚未被 alias 分类逻辑显式证明的 source/target 形状：
  - 当前计划明确要求 fail-closed：
    - 不允许“因为忘记扩分类表而自动落到 no-alias 快路”

#### `may-alias` 写槽路径的安全不变量（必须实现到 builder 协议）

- 一旦分类结果是 `may-alias`，后端必须改走 alias-immune write path，而不是继续复用
  `destroy(target) -> target = copy(source_ptr)` 的 no-alias 快路
- 这条 alias-immune write path 至少要满足以下顺序不变量：
  1. 先用 copy ctor 从 `source_ptr` 复制出 stable carrier
  2. stable carrier 准备完成之后，才允许 destroy old target
  3. destroy old target 之后，把 stable carrier 写入 target
  4. stable carrier 一旦被 target 接管，就不得再按普通 temp 路径 destroy 一次
- 第 1 步中的 stable carrier 必须是真正独立 owner，而不是浅层 struct 副本：
  - 正确例子：
    - `stable = godot_new_String_with_String(source_ptr)`
    - `stable = godot_new_Variant_with_Variant(source_ptr)`
  - 错误例子：
    - `stable = target_or_source_struct_copy`
    - 任何“先普通 `=` 得到 temp，再把 temp 当独立 owner destroy”的形状
- 第 4 步必须在 builder 层显式编码，而不是只靠注释约定：
  - 若 stable carrier 仍被放进普通 `tempsToDestroy` / `emitTempDestroys(...)` 路径，
    那么即使 copy 顺序修正了，target 接管后仍会因为 temp 的二次 destroy 重新触发提前释放
  - 因此 `prepareRhsValue(...)` 及其返回协议需要能区分：
    - ordinary temp（写入后仍应 destroy）
    - consumed stable carrier（写入 target 后不得再 destroy）

#### `may-alias` 路径的典型例子（用于指导实现）

- direct self-assign：
  - source：`VarValue(x)`
  - target：`VarTargetRef(x)`
  - 若错误走 no-alias，会生成
    `destroy(x); x = copy(&x);`
    从而在 destroy 后再读取同槽位
  - 正确的 `may-alias` 路径必须等价于：
    - `stable = copy(&x)`
    - `destroy(x)`
    - `x = stable`
    - stable 被 consume，不再 destroy
- indirect alias via `ref=true` parameter：
  - source：`VarValue(param)`，且 `param.ref() == true`
  - target：`ExprTargetRef("$self->payload")` 或其它 concrete slot
  - `renderValueAddress(...)` 会把 source address 渲染成 `$param`
  - 即使当前看不见 caller 侧实参，`$param` 仍可能正是 `&target_slot`
  - 因此必须保守走与 direct self-assign 同样的 stable-carrier 路径

#### 实施上的保守边界

- 第一版允许的 `proven no-alias` 必须是“当前代码库能直接从 type/provenance 证明”的窄集合；
  不要为了少生成一个 temp 而引入基于字符串或 AST 还原的脆弱推断
- 特别禁止以下伪证明：
  - “source 变量名和 target 变量名不同，所以必然 no-alias”
  - “target 是 `self->a`、source 是 `self->b`，看起来字段不同，所以必然 no-alias”
  - “当前测试里没构造出别名，所以默认 no-alias”
- 只有在 future 真正引入结构化 place/reference provenance，并且 builder 能拿到“同 owner 上不同 field/index slot 不重合”的形式化事实后，
  才允许把 `ExprTargetRef` / `AddressableExprValue` 的部分组合从 `may-alias` 提升到 `proven no-alias`

#### 验收标准

- happy path：
  - ordinary no-alias assignment 继续保持当前 direct-copy-to-slot 形状，不平白退化成 temp-heavy code
  - backing-field getter/self-setter 的 current fix 仍成立，不重新出现 shallow temp materialization
  - direct self-assign（例如 `String` / `Variant` / `Array` / `Dictionary` / `Packed*Array`）不会在 destroy target 后再从同槽位读取 source
- negative path：
  - 不能只修“`s = s` 同名变量”这一条窄路径，而放过 indirect alias
  - 不能通过恢复旧的 `tmp = copy(source); target = tmp; destroy(tmp);` 伪修复 alias 问题
  - 不能因为 alias-safe 修复而把 non-alias fast path 全部打回 temp materialization

#### 测试要求

- backend unit tests 必须至少新增/覆盖：
  - direct self-assign regression（`String` / `Variant` 至少各一例）
  - indirect alias regression：
    - 通过 `ref=true` source 或其它现有 address-bearing surface 构造“RHS 间接指向 target storage”的场景
  - no-alias fast path regression：
    - 证明 `a = b` 这类普通赋值不会因 alias-safe 修复而退化回 temp-heavy 旧形状

#### 当前状态（2026-04-11）

- 已完成：backend 已新增单点 `CBodyBuilderAliasSafetySupport.classifyNonObjectSlotWriteAliasSafety(...)`，集中对当前 sealed `ValueRef` / `TargetRef` surface 判定 `PROVEN_NO_ALIAS` 与 `MAY_ALIAS`。
- 已完成：destroyable non-object overwrite 现在区分两条路线：
  - `PROVEN_NO_ALIAS` 保持原有 direct-copy-to-slot 快路
  - `MAY_ALIAS` 先用 copy ctor 生成 stable carrier，再 destroy target，并把 carrier consume 到 target，carrier 不再进入普通 temp destroy 路径
- 已完成：`LoadPropertyInsnGen` / `StorePropertyInsnGen` 继续保持“从真实 source address copy”的修复方向，但在 getter-self / setter-self 的 alias-open overwrite 场景下已切到 stable-carrier path，避免回到 shallow temp materialization 或 destroy-after-copy-temp 的旧 bug。
- 已完成：Step 7.1 回归测试已补齐并通过：
  - `CBodyBuilderPhaseCTest`
  - `CAssignInsnGenTest`
  - `CLoadPropertyInsnGenTest`
  - `CStorePropertyInsnGenTest`
  - `FrontendLoweringToCProjectBuilderIntegrationTest.lowerFrontendDynamicVariantReceiverWritebackBuildNativeLibraryAndRunInGodot`

### 当前状态（2026-04-10）

- 已完成：`FrontendWritableRouteSupport.reverseCommit(..., ReverseCommitGateHook)` 继续作为 Step 4 静态 gate 入口保留，assignment payload lowering 不受影响。
- 已完成：shared support 已新增 `reverseCommitWithRuntimeGate(...)`，会在当前 carrier 为 `Variant` 时发出 per-layer `GoIfInsn` 分支，并返回 continuation block 供 outer lowering 继续写入。
- 已完成：新 API 先走 `FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(...)` 静态快捷路径；shared/reference family 不会平白多出 runtime branch，concrete value-semantic family 也不会被降级成“总是动态判断”。
- 已完成：`FrontendBodyLoweringSession` 已提供 writable-route synthetic block 分配 helper，保证 runtime-gated reverse commit 生成的 `apply/skip/continue` block 真正进入目标 LIR function。
- 已完成：`FrontendWritableRouteSupportTest` 已锚定 concrete fast path、shared fast-skip 与 `Variant` per-layer branch + promoted-carrier continuation 三类行为。
- 已完成：body lowering 的 processor/registry/session 契约已改为返回并传递当前 continuation block；Step 7 现在已经把 dynamic call path 真正接到 `reverseCommitWithRuntimeGate(...)`，后续 sequence item 会继续附着到返回的新 block。
- 已完成：`FrontendCallMutabilitySupport` 现已把 `DYNAMIC_FALLBACK + INSTANCE` 冻结为 conservative may-mutate route，避免 dynamic direct-slot mutating receiver 退化回 temp snapshot 语义。
- 已完成：backend 已把 `gdcc_variant_requires_writeback(...)` 作为 backend-owned helper 接入 `CallGlobalInsn` 解析，`gdcc_helper.h` 也已落地对应实现，并把未列举 future `Variant` kind 的默认策略固定为保守 `true`。
- 已完成：Step 7 当前轮的 unit/codegen/runtime 验收已经补齐，覆盖 frontend block-threading、backend helper emission，以及 Godot runtime 下 `PackedInt32Array` / `Array` 两条 dynamic `Variant` receiver 路线。

### 关联文档与注释同步

- `frontend_dynamic_call_lowering_implementation.md`
- `gdcc_type_system.md`
- `gdcc_c_backend.md`
- `gdcc_helper.h`
- 若 backend 文档已有 writeback/helper 章节，也要同步更新

### 验收细则

- happy path：
  - `DYNAMIC` / `Variant` receiver route 会生成 runtime-gated commit plan
  - runtime type 为 `String` / `Vector*` / `Packed*Array` 时 gate 保留 writeback
  - runtime type 为 `Array` / `Dictionary` / `Object` 时 gate 跳过 writeback
  - 弱类型变量上的 mutating call 在后续 read 中能观测到与 Godot 一致的结果
- negative path：
  - 不把 `DYNAMIC` route 回退成 plain receiver pass-through
  - 不出现 unknown owner 的保守总写回
  - 不为 `DYNAMIC` route 臆造 fixed callable signature

---

## 12. Step 8: 测试与集成验收

### 当前状态（2026-04-10）

- 已完成：frontend body/support 单测已锚定 dynamic receiver runtime gate 的 block-threading 与 per-layer writeback 结构。
- 已完成：backend/codegen 单测已锚定 `CallGlobalInsn("gdcc_variant_requires_writeback", ...)` 会落到 backend-owned helper，而不是被当成缺失的 registry utility。
- 已完成：backend backing-field getter/self-setter 的 value-semantic copy 语义已收口为“直接从 source address 复制到目标 slot”，不再残留 shallow temp materialization；这条修复已经被 `Variant` regression test 与 integration test 双重锚定。
- 已完成：Godot integration test 已覆盖 `Variant` property 持有 `PackedInt32Array` 与 `Array` 时的 dynamic mutating receiver 行为，确认 runtime helper 会在 requires-writeback / shared family 上给出与文档一致的可观测结果。

### 单元测试

- frontend lowering 单测至少覆盖：
  - simple binding receiver
  - property-backed receiver
  - nested property/subscript receiver
  - multi-step reverse commit 会在每一层后更新 carrier，而不是把 leaf carrier 复用到所有 step
  - runtime gate 在 inner step 返回 false、outer step 返回 true 时，会跳过当前层 writeback，但 outer 仍必须观察到 promoted carrier 并按该 carrier 生成最终指令序列
  - 静态已知 `Array` / `Dictionary` / `Object` carrier 会跳过当前层 writeback，但 outer step 仍继续执行，且 store 次数不增
  - `current carrier -> type/family` 判定合同有独立单测，确保 gate 只看 current carrier 而不是 step kind / anchor
  - nested mutating call inside subscript key (inner call writeback must complete before outer route consumes the key)
  - `const` method negative path
  - non-gdextension may-mutate conservative path
  - `DYNAMIC` / `Variant` route 的 runtime-gated plan
  - receiver/base/key 只求值一次
  - receiver 与 arguments 的求值顺序不被破坏
  - compound assignment 迁移后 getter / subscript get / `VariantGet*` 次数不增，pack/unpack 仍只在最终 store 边界出现

### backend / codegen 测试

- `Variant` owner gate emission
- runtime helper 对 `Array` / `Dictionary` / `Object` 返回 false
- runtime helper 对 `String` / `Vector*` / `Packed*Array` 返回 true

### 引擎集成测试

- `PackedInt32Array.push_back(...)`
- `String` / `Vector*` 上可观测 mutating method
- property-backed value-semantic receiver 的真实运行结果
- receiver 经无类型变量或 `Variant` 变量流转后的 mutating call
- getter/setter 有副作用的链式 route
- key/index 有副作用的 subscript route

### 验收细则

- happy path：
  - 结果与 Godot 引擎行为一致
  - mutating receiver 改动不会破坏 ordinary read-only call
- negative path：
  - 失败测试必须能指出是 route publication、lowering、runtime helper 还是 codegen gate 出错
  - 不允许通过放宽测试断言掩盖行为偏差

---

## 13. 关联文档与注释同步清单

按本计划实施时，以下文档与注释必须作为同步验收面，而不是事后补写：

- `frontend_dynamic_call_lowering_implementation.md`
  - dynamic route 不再等同于 direct receiver pass-through
  - 记录 writable receiver access-chain payload 的 receiver-side 合同
- `frontend_rules.md`
  - compile gate / CFG / body lowering 的 dynamic route 摘要合同
- `frontend_lowering_cfg_pass_implementation.md`
  - `CallItem` / `AssignmentItem` 的 writable chain payload 合同
  - “整条 route 冻结/消费” 约束
- `frontend_lowering_(un)pack_implementation.md`
  - ordinary boundary helper 与 receiver-side writable-route logic 的职责分离
- `gdcc_type_system.md`
  - writeback family 规则与 `Packed*Array` 分类
- `gdcc_c_backend.md`
  - runtime helper / gated writeback 的 backend 合同
- 相关 Java 注释
  - `FrontendSequenceItemInsnLoweringProcessors`
  - `FrontendAssignmentTargetInsnLoweringProcessors`
  - future `FrontendWritableRouteSupport`
  - `CallItem` / `AssignmentItem`
- `gdcc_helper.h`
  - runtime helper 注释必须与文档同名同极性，不得一处写 `is_shared`、另一处写 `requires_writeback`

---

## 14. 非目标与升级条件

本计划当前明确不做：

- 立即把 writable route 提升成公共 LIR place/reference model
- 为所有 ordinary expression 去掉 `valueId -> slotId` 主体系
- 引入新的 dynamic-call 专用 LIR instruction
- 复制 Godot 的 bytecode / VM opcode 形状

只有在以下条件出现时，才考虑升级到更重的 LIR place/ref 路线：

- frontend-only writable route 无法覆盖复杂 target 与 mutating receiver 语义
- optimizer 必须跨 instruction 理解原位别名
- backend 需要直接消费更强的 place/reference 语义

---

## 15. 最终完成条件

本计划只有在以下条件同时满足后，才算完成：

1. assignment 与 mutating receiver route 都已切到 shared writable-route core
2. `RESOLVED` 与 `DYNAMIC` instance mutating call 都有稳定行为
3. runtime `Variant` writeback gate 已落地
4. 单元测试、backend/codegen 测试、引擎集成测试都覆盖正反路径
5. 关联文档与注释同步完成
6. 本文档可归档为 implementation fact source，而不再保留计划口吻

---

## 16. 四个潜在风险澄清（必须显式承认并锁定为回归锚点）

本节不是“恐吓清单”，而是把本计划在工程上最容易翻车的四个点写死，避免实现阶段用隐式假设掩盖风险。

1. setter 重入 / 递归风险：reverse commit 可能触发 property setter。若 setter 内部又间接触发同一条 writable route（例如修改自身 property 或调用会回写的 mutating call），可能造成重入或递归。即便本计划不复刻 Godot 的全部 setter-in-setter guard，也必须通过测试与 fail-fast 观察点确保该风险可见且可定位。
2. runtime helper 覆盖面风险：`gdcc_variant_requires_writeback(...)` 必须明确其对未列举 Variant 类型的默认策略（保守 true 还是保守 false）。否则“新增一种 builtin 值语义类型”时会出现 silent 行为偏差，且难以从单点定位。
3. payload 双账本风险：writable-route payload 与现有 CFG value items 必须保持职责分离（payload 只描述 writeback，items 描述求值）。若 payload 开始承载求值解释或 AST 回退路径，将造成两套解释器漂移，最终只能靠补丁粘合，复现当前问题。
4. 性能与 code size 风险：对 `DYNAMIC` route 的保守 commit plan + runtime gate 会增加指令与分支。必须通过 gate 粒度收敛（仅在 runtime-unknown 时包裹，且 shared 快速跳过）把开销限制在“必要时才付费”的范围内，并通过 codegen 测试锚定生成形状不发生无界膨胀。
