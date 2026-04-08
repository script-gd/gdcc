# Frontend Complex Writable Target Plan

> 本文档是 frontend “复杂可写目标 / mutating receiver writeback” 工程的实施计划。
> 当前已冻结的事实仍以 `frontend_rules.md`、`frontend_dynamic_call_lowering_implementation.md`、`frontend_lowering_cfg_pass_implementation.md`、`frontend_lowering_(un)pack_implementation.md` 与 `gdcc_type_system.md` 为准；本文档负责把这组事实转写成可执行的实施顺序、验收细则与文档同步清单。

## 文档状态

- 状态：计划维护中（Step 1 共享合同冻结 / 漂移面清理已完成并通过回归验证；shared writable-route support 与后续 lowering / backend 接通仍待继续推进）
- 更新时间：2026-04-08
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
- `CallItem` 当前只稳定承载 ordinary receiver / arguments / result
- assignment target lowering 仍主要依赖 `targetOperandValueIds` 与 AST tail-step dispatch
- mutating receiver call 仍会先经 ordinary value path，导致值语义 receiver 可能先复制进 temp 再调用
- backend 已能对值语义类型按地址调用，但并不知道 frontend 原始 owner route

因此当前的真正缺口不是“backend 不会调方法”，而是：

- frontend 过早值化 receiver / target
- lowering 缺少“整条 writable access chain”的冻结与消费模型
- reverse writeback 规则仍是局部补丁，而不是 shared contract

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

### 当前状态（2026-04-08）

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

- 已完成：`frontend.lowering.pass.body` 中已落地 package-private 的 `FrontendWritableRouteSupport`，统一承接 leaf read / leaf write / reverse commit / gate hook。
- 已完成：assignment target、member load、subscript load，以及当前 `CallItem` 可表达的 direct-slot receiver leaf 都已改为复用同一套 shared support 入口。
- 已完成：原先 subscript assignment 的 property-backed ad-hoc writeback 补丁已被 shared route flow 替换，避免 assignment path 与 call path 各维护一套平行 reverse-commit 逻辑。
- 已完成：`FrontendWritableRouteSupportTest`、`FrontendLoweringBodyInsnPassTest`、`FrontendBodyLoweringSessionTest` 已作为 Step 2 的回归锚点验证通过。
- 未完成：CFG 仍未发布完整 writable-route payload；assignment 与 mutating receiver 当前仍是基于既有 published operand surface 组装 route，这部分继续由 Step 3 收口。

### 验收细则

- happy path：
  - assignment 与 call path 都能调用同一个 shared support 入口
  - support 能表达 root / step list / leaf / reverse commit 结构
  - malformed route 会在 support 层 fail-fast，而不是 silent fallback
- negative path：
  - 不引入新的 public API 面
  - 不出现 assignment 与 call 各自维护一套平行 reverse-commit 逻辑

---

## 7. Step 3: 扩展 CFG published surface，冻结整条 writable access chain

### 实施内容

- 扩展 `AssignmentItem` 与 `CallItem`，让它们可携带 writable access-chain payload
- payload 至少冻结：
  - route anchor
  - leaf value id
  - route operand value ids
  - explicit step descriptors
  - leaf kind
- CFG build 在 source-order 下从 AST 形状生成显式步骤，并绑定到已发布 value ids
- 对 mutating receiver call：
  - 同一个 `CallItem` 同时保留 ordinary receiver/arguments/result 合同
  - 若命中 writable route，则追加单个 route payload
- 对 assignment / compound assignment：
  - writable target chain 不再依赖“AST tail-step + targetOperandValueIds + lowering 时二次推断”
  - 后续应整体切向同一个 route payload

### payload 最小不变量（必须可在 lowering support 层 fail-fast 校验）

本步骤的目标不是“再发明一套 value 求值链”，而是冻结 *writeback/commit* 所需的 owner provenance，使 body lowering **不需要**回头解释 AST target/receiver 子树就能完成：

1. leaf selection（必要时 materialize 临时 leaf 值）
2. leaf 写入或 mutating-call 调用
3. reverse commit（按 route 反向写回外层 owner）

因此 payload 必须满足以下最小不变量，且 support 层必须把它们当成硬性协议校验：

1. payload 表达的是 “writeback 路径”，而不是 “value 求值路径”。payload 只能引用 CFG 已发布的 value ids；不得承载“重新计算 receiver/base/key”的备用 AST 解释。
2. `routeOperandValueIds` 的顺序必须是 source-order，且每个 step descriptor 都能无歧义声明它消耗哪些 operand ids（例如：property step 消耗 1 个 receiver；subscript step 消耗 receiver + 1 个 key）。
3. step descriptor 的枚举集合必须足够闭合，至少包含：
   - property member（最终写回走 `StorePropertyInsn` / `StoreStaticInsn` 或变体 `variant_set_named`）
   - subscript indexed（最终写回走 `variant_set_indexed`）
   - subscript named（最终写回走 `variant_set_named`）
   - subscript keyed / generic（最终写回走 `variant_set_keyed` / `variant_set`）
4. payload 的 `leafKind` 必须明确告诉 support：leaf 是“哪一层的值”，从而支持生成 “leafSlotId” 以及后续 commit plan。
5. payload 不允许和现存的 `MemberLoadItem` / `SubscriptLoadItem` / `CallItem` 形成 “双账本”：
   - CFG 负责发布 value 求值 item；
   - payload 只负责发布 writeback route；
   - 两者共享同一批 frozen value ids 作为连接点。

### step descriptor 格式

为降低“operand 错位、step 解释漂移、嵌套链无法组合”的风险，payload 的 step descriptor 采用固定格式，且 support 层按本节规则机械解析并校验。

#### 术语约定

- `leafValueId`：本次 leaf mutation 的直接承载值 id。
  - 对 mutating call：它是 call receiver 的 leaf value。
  - 对 assignment：它是最终 writable leaf 的“容器值”（例如 `self.position.x = 1` 中的 `self.position`，而不是 `x`）。
- `routeOperandValueIds`：只用于描述 reverse commit 所需的“写回位置参数”，不得用于重新求值。
- `steps`：描述“写回到外层”的 commit steps 列表。执行顺序固定为：从 `steps.getLast()` 向前逆序执行，逐层把当前 `writtenBackValue` 写回到外层 owner。

#### `WritableRouteStepDescriptor` 必须包含的字段

每个 step descriptor 必须包含以下字段（名称是概念字段名，具体 Java record 命名可调整，但语义不得变）：

- `stepAnchor`：AST node identity，用于定位 diagnostics 与读取已发布 semantic fact（仅用于查表，不得用于重新求值）。
- `kind`：step kind，取值见下表。
- `memberNameOrNull`：
  - `PROPERTY`：必须非空，表示 property 名。
  - `SUBSCRIPT`：可为空；非空表示 attribute-subscript 的 named-base（等价于 “先 `get_named` 再对其做下标 set，再 `set_named` 写回”）。
- `subscriptAccessKindOrNull`：
  - `PROPERTY`：必须为空。
  - `SUBSCRIPT`：必须非空，且只能取：
    - `GENERIC`（`variant_set`）
    - `KEYED`（`variant_set_keyed`）
    - `NAMED`（`variant_set_named`）
    - `INDEXED`（`variant_set_indexed`）

`subscriptAccessKindOrNull` 的冻结目的是：避免 lowering/support 在后续阶段重新推断 “该用哪个 set 指令族”，从而把 payload 变成隐式推理点。

#### step kind 与 operand arity（必须可静态推导并校验）

step kind 的含义与其 operand arity 固定为：

- `PROPERTY`
  - 描述：把 `writtenBackValue` 写回到某个 property（instance 或 type-meta 由已发布 member fact 决定）。
  - route operands：1 个。
  - operand 语义：`receiverValueId`。
- `SUBSCRIPT`
  - 描述：把 `writtenBackValue` 写回到某个下标位置（可能先经 named-base）。
  - route operands：2 个。
  - operand 语义：`baseValueId`、`keyValueId`。

对应的 `routeOperandValueIds` 布局规则固定为：

- `routeOperandValueIds` 必须按 `steps` 顺序线性拼接每个 step 的 operands。
- `PROPERTY` step 贡献 1 个 operand：`receiverValueId`。
- `SUBSCRIPT` step 贡献 2 个 operands：`baseValueId`、`keyValueId`。
- 因此必须满足：`routeOperandValueIds.size() == steps.aritySum()`。
- support 层必须校验以上不变量，否则 fail-fast。

#### 两个示例（用于锁定格式，不是实现限制）

Example 1: `self.items[i].push_back(1)`（leaf mutation 是 mutating call，reverse commit 写回 `items[i]`，再尝试写回 `self.items`）

- `leafValueId`：`self.items[i]` 的 value id
- `steps`（outer-to-inner）：
  - `PROPERTY(name = "items")`，operand：`self`
  - `SUBSCRIPT(access = INDEXED, memberNameOrNull = null)`，operands：`self.items`, `i`
- 执行 reverse commit 时倒序执行：
  - 先对 `self.items[i]` 写回到 `self.items[i] = writtenBackValue`（必要时 runtime gate）
  - 再尝试写回 `self.items = writtenBackValue`（shared 值通过 gate 跳过）

Example 2: `self.position.x = 1`（leaf mutation 是 leaf property store，reverse commit 写回 `self.position`）

- `leafValueId`：`self.position` 的 value id
- `leafKind`：`STORE_PROPERTY(name = "x")`（leaf store 发生在 leaf 容器值上）
- `steps`（outer-to-inner）：
  - `PROPERTY(name = "position")`，operand：`self`

### 嵌套表达（payload 不递归嵌套，靠 CFG item 顺序组合）

writable access chain 的 payload 只描述“一条 receiver/target 的 writeback 路径”，它本身不需要、也不应该递归嵌套表达其子表达式内部的另一条 writable chain。

当出现嵌套形态，例如：

```gdscript
# index 表达式内部还有一条链式访问，并且最后调用了可能 mutate receiver 的方法
self.items[self.cursor.next().pop_back()].push_back(1)
```

计划中的正确表达方式是“组合而非嵌套”：

1. CFG build 仍按 source-order 先 build `self.items` 的 receiver value id（冻结为外层 payload 的第一个 operand）。
2. CFG build 递归 build index 子表达式 `self.cursor.next().pop_back()`：
   - 其中 `pop_back()` 对其 receiver 若命中 mutating receiver route，则它自身对应的 `CallItem` 也发布一个 *独立的* writable-route payload，并在该 `CallItem` 周围完成 leaf selection 与 post-call commit。
   - 该子表达式最终只对外发布一个稳定的 result value id，作为外层 payload 的 key operand value id。
3. 最后 CFG build append 外层 mutating call 的 `CallItem`，并在该 `CallItem` 上发布外层 writable-route payload。

因此：嵌套链上的“内层 writeback”通过其 own `CallItem` payload 先闭合，外层 payload 只引用内层求值产出的 key value id，不承担内层 route 的描述责任。

这种组合方式的收益是：

- 保持 “body lowering 不重跑 AST” 的硬合同：每条 payload 都只引用 frozen value ids。
- 避免 payload 变成第二套递归解释器，从而产生双账本与漂移风险。

同时这也引入一个必须显式承认的约束：payload 的 step descriptors 必须使用“消费 operand value ids 的位置/数量”而不是“重新解释 key AST”，否则无法在组合模式下可靠引用内层结果。

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
  - payload 能无歧义表达 `root + step list + leaf kind`
  - route operand ids 与 explicit steps 顺序稳定且可预测
  - compound assignment 能重用同一条 target chain，不需要第二份 ad-hoc target 解释
- negative path：
  - 对缺失 operand、逆序引用、未知 leaf kind 的 payload fail-fast
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
- 逐步缩减 assignment-only step registry 在 writable target 上的职责

### 迁移期去重约束（避免双写回与 setter 副作用重复触发）

引入 shared writable-route support 之后，旧的 assignment 路径上存在的“窄 writeback 补丁”必须被整体替换掉，而不是与新 route 并存。最低限度需要满足：

1. 当某个 assignment target 已经改为消费 writable-route payload 时，禁止继续调用 `FrontendSubscriptInsnSupport.writeBackPropertyBaseIfNeeded(...)` 或其他 ad-hoc writeback 辅助逻辑。
2. 必须有回归测试锚定：同一条链式写入只触发一次 setter/一次外层回写，而不是“route reverse commit + 旧补丁回写”的双发。

### 重点覆盖

- bare binding assignment
- property assignment
- subscript assignment
- property + subscript mixed chain
- nested chain compound assignment

### 关联文档与注释同步

- `frontend_lowering_cfg_pass_implementation.md`
- `frontend_rules.md`
- `FrontendAssignmentTargetInsnLoweringProcessors`
- `FrontendBodyLoweringSession`

### 验收细则

- happy path：
  - `self.position.x = 1` 会执行 leaf write 并回写 `self.position`
  - `self.prop[index] = rhs` 会对 `prop[index]` 写入，并在需要时回写 `self.prop`
  - `arr[i].x += 1` 会在同一条 route 上完成 read-modify-write 与外层 commit
- negative path：
  - route malformed 时 fail-fast
  - 不再依赖“只对 property identifier base 做窄 writeback patch”这类局部补丁

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

1. 解析 frozen writable-route payload，并 materialize “用于本次调用的 receiver leafSlotId”（必要时创建临时 leaf 值）。
2. 再 materialize argument boundary（exact route 做 ordinary pack/unpack；dynamic route 仅保留已求值 argument slot，不新增 fixed-parameter boundary）。
3. 发出同一条 `CallMethodInsn`。
4. 根据 commit plan 执行 reverse commit（必要时带 runtime gate）。

### 非目标

- 不引入新的 call LIR instruction
- 不把所有 instance call 一律改成 commit-after-call
- 不为 non-gdextension callable 新建完整的静态 mutability 推导系统

### 关联文档与注释同步

- `frontend_dynamic_call_lowering_implementation.md`
- `frontend_lowering_cfg_pass_implementation.md`
- `FrontendSequenceItemInsnLoweringProcessors`
- shared mutability helper 注释

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

---

## 10. Step 6: 实现 `DYNAMIC` / `Variant` route 与 runtime-gated writeback

### 实施内容

- 对 `DYNAMIC` instance call：
  - 继续复用 `CallMethodInsn` surface
  - receiver 侧允许保守生成 commit plan
  - 是否真正执行某一层 reverse commit 由 runtime helper 决定
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

## 11. Step 7: 测试与集成验收

### 单元测试

- frontend lowering 单测至少覆盖：
  - simple binding receiver
  - property-backed receiver
  - nested property/subscript receiver
  - nested mutating call inside subscript key (inner call writeback must complete before outer route consumes the key)
  - `const` method negative path
  - non-gdextension may-mutate conservative path
  - `DYNAMIC` / `Variant` route 的 runtime-gated plan
  - receiver/base/key 只求值一次
  - receiver 与 arguments 的求值顺序不被破坏

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

## 12. 关联文档与注释同步清单

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

## 13. 非目标与升级条件

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

## 14. 最终完成条件

本计划只有在以下条件同时满足后，才算完成：

1. assignment 与 mutating receiver route 都已切到 shared writable-route core
2. `RESOLVED` 与 `DYNAMIC` instance mutating call 都有稳定行为
3. runtime `Variant` writeback gate 已落地
4. 单元测试、backend/codegen 测试、引擎集成测试都覆盖正反路径
5. 关联文档与注释同步完成
6. 本文档可归档为 implementation fact source，而不再保留计划口吻

---

## 15. 四个潜在风险澄清（必须显式承认并锁定为回归锚点）

本节不是“恐吓清单”，而是把本计划在工程上最容易翻车的四个点写死，避免实现阶段用隐式假设掩盖风险。

1. setter 重入 / 递归风险：reverse commit 可能触发 property setter。若 setter 内部又间接触发同一条 writable route（例如修改自身 property 或调用会回写的 mutating call），可能造成重入或递归。即便本计划不复刻 Godot 的全部 setter-in-setter guard，也必须通过测试与 fail-fast 观察点确保该风险可见且可定位。
2. runtime helper 覆盖面风险：`gdcc_variant_requires_writeback(...)` 必须明确其对未列举 Variant 类型的默认策略（保守 true 还是保守 false）。否则“新增一种 builtin 值语义类型”时会出现 silent 行为偏差，且难以从单点定位。
3. payload 双账本风险：writable-route payload 与现有 CFG value items 必须保持职责分离（payload 只描述 writeback，items 描述求值）。若 payload 开始承载求值解释或 AST 回退路径，将造成两套解释器漂移，最终只能靠补丁粘合，复现当前问题。
4. 性能与 code size 风险：对 `DYNAMIC` route 的保守 commit plan + runtime gate 会增加指令与分支。必须通过 gate 粒度收敛（仅在 runtime-unknown 时包裹，且 shared 快速跳过）把开销限制在“必要时才付费”的范围内，并通过 codegen 测试锚定生成形状不发生无界膨胀。
