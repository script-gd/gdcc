# Frontend Exact Call Extension Metadata 合同

> 本文档作为 exact engine call extension metadata route split、shared normalized callable boundary 与 downstream 消费约束的长期事实源。本文档替代原 `frontend_exact_call_extension_metadata_plan.md`，不再保留实施阶段、进度记录或验收流水账。

## 文档状态

- 状态：事实源维护中（exact callable boundary 单次发布、lowering 复用已发布边界、shared extension metadata parser 收口已冻结）
- 更新时间：2026-04-17
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/lowering/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/resolver/**`
  - `src/test/java/dev/superice/gdcc/frontend/**`
  - `src/test/java/dev/superice/gdcc/scope/**`
- 关联文档：
  - `frontend_rules.md`
  - `scope_type_resolver_implementation.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`
  - `../backend/typed_dictionary_abi_contract.md`
  - `../backend/variant_abi_contract.md`
- 明确非目标：
  - 不把 plain `var holder = Node.new()` 的 dynamic fallback 改写为 exact route
  - 不在本轮把 `declarationSite` 改写成 `ScopeResolvedMethod`
  - 不引入新的 side-table 或把 `FunctionSignature` 扩写成 exact-call 通用 carrier
  - 不对所有 `ClassRegistry.tryParseTextType(...)` 入口做广义审计
  - 不把 runtime `ptrcall` object-parameter ABI 问题并入本文档负责范围
  - 不在当前合同中一并闭环 constructor / bare-call exact route 的全部 boundary 发布

---

## 1. 问题边界与 route split

这条工程约束起源于一个曾被混写的问题：`Node.add_child(...)` 既能暴露 frontend exact-call metadata 边界，也能暴露独立的 runtime ABI 问题。后续工程必须先把这两条链分开，再讨论修复。

### 1.1 plain `var` receiver 链

示例：

```gdscript
var holder = Node.new()
holder.add_child(Node.new())
```

当前 steady-state：

- plain `var` 不会因 initializer 自动回填成 exact local type
- `holder` 仍是 `Variant` carrier
- 该调用继续走 `DYNAMIC_FALLBACK`

这条链不是本文档处理的 exact metadata 主问题，也不应被后续实现偷偷抬升成 exact route。

### 1.2 显式 typed receiver 链

示例：

```gdscript
var holder: Node = Node.new()
holder.add_child(Node.new())
```

当前 steady-state：

- call route 会先发布为 `RESOLVED + INSTANCE_METHOD`
- shared resolver 已经可以接受 extension exported metadata，并产出 normalized callable signature
- body lowering 必须继续复用这份已发布的 normalized boundary，而不是回头再读 raw metadata

本文档约束的正是这条 exact route。

---

## 2. 分层模型

### 2.1 raw export layer 与 shared normalized layer 必须继续分层

后续 extension metadata 工程必须继续区分两层事实：

- raw JSON / exported spelling import layer
- shared compiler-facing normalized publication layer

Godot extension API JSON 中的 `enum::...`、`bitfield::...`、`typedarray::...`、`typeddictionary::...` 都属于 exported spelling。  
它们是导出表示，不是 frontend / lowering 应长期依赖的 primary type source。

### 2.2 parser / AST 不是这条规范化的归宿

`gdparser` 侧仍只保留 source text 与结构；extension metadata normalization 的责任继续停留在 shared semantic / scope resolver 层。  
后续工程不得把“需要理解 exported spelling”重新伪装成 parser 或 AST mapping 问题。

---

## 3. 冻结合同

### 3.1 shared resolver 是 exact callable boundary 的唯一真源

当前代码库已经冻结如下事实：

- `ScopeMethodResolver` 通过 `ScopeTypeParsers.parseExtensionTypeMetadata(...)` 解析 extension metadata
- `ScopeResolvedMethod.parameters()` 保存的是 normalized fixed-parameter boundary
- exact route downstream 不得再通过 `FunctionDef.getParameters()` 或 `ParameterDef::getType()` 重建另一套 signature

对同一个 selected extension callable，允许的主链只有：

1. shared resolver 规范化一次
2. `FrontendResolvedCall` 发布一次
3. 后续 frontend stages 只读这份 published boundary

### 3.2 `FrontendResolvedCall` 的字段语义已冻结

当前合同明确区分两类数据：

- `argumentTypes()`
  - 语义固定为“调用点实参类型快照”
- `exactCallableBoundary()`
  - 语义固定为“shared resolver 已发布的 exact callable fixed-parameter boundary”

后续工程不得再把 `argumentTypes()` 偷换成 callable signature，也不得用它替代 `exactCallableBoundary()`。

### 3.3 `ExactCallableBoundary` 的载荷范围已冻结

`FrontendResolvedCall.ExactCallableBoundary` 当前只承载：

- ordered fixed parameter types
- `isVararg`

这是一条有意收窄的合同。  
它服务 exact-call lowering 的 fixed-parameter 物化，不承担 default-parameter 切分、origin/hint/usage 传播或更广义 metadata family 的统一载体职责。

### 3.4 `declarationSite` 保持既有语义

当前多处逻辑仍依赖 `declarationSite` 的既有运行时形状，例如：

- mutability support 依赖 extension method metadata object
- constructor 路径依赖 constructor metadata owner
- 既有测试会直接观察 declaration metadata

因此 exact callable boundary 必须作为新增 published fact 附着在 `FrontendResolvedCall` 上，而不是替换 `declarationSite`。

### 3.5 lowering 消费面合同已冻结

当前 frontend lowering 必须满足：

- exact instance/static method route 优先消费 `exactCallableBoundary()`
- exact route 若缺失本应存在的 published boundary，必须 fail-fast 为明确 invariant violation
- exact route 不得静默回退到 raw callable metadata

`requireBoundaryCallableSignature(...)` / `callBoundaryParameterTypes(...)` 目前仍保留为窄 fallback，但它们不再是 exact engine method route 的正常入口。

### 3.6 shared-parser 收口是 hygiene，不是主合同替代品

`ExtensionFunctionArgument.getType()` 现已收口到 `ScopeTypeParsers.parseExtensionTypeMetadata(...)`。  
这是必要的 hygiene 修复，但它不改变主合同：

- exact route 的 callable boundary 真源仍是 shared resolver publication
- 不能把“旧 getter 现在也能解析”误当成 exact route 已经可以继续依赖 raw metadata

---

## 4. 当前覆盖的 metadata family

### 4.1 当前 exact-call 主命中面

已确认 exact route 的核心命中面至少包括：

- `enum::...`
- `bitfield::...`
- `typedarray::...`

本地 `extension_api_451.json` 中的稳定例子包括：

- `Node.add_child(...)`
  - 默认参数包含 `enum::Node.InternalMode`
- `Node.set_process_thread_messages(...)`
  - 参数包含 `bitfield::Node.ProcessThreadMessages`
- `ArrayMesh.add_surface_from_arrays(...)`
  - 参数/默认参数同时覆盖 `typedarray::Array` 与 `bitfield::Mesh.ArrayFormat`

### 4.2 `typeddictionary::...` 的当前定位

`typeddictionary::K;V` 不属于这轮 exact-call regression 的主命中面，但 shared parser 现在已经需要理解它的 flat-leaf normalization。  
后续工程必须继续把它视为独立合同，而不是因为 parser 已可读取 exported spelling，就误认为 typed-dictionary 工程已经完成。

它后续仍需要单独处理：

- shared typed contract 的允许子集
- outward metadata / runtime gate
- 是否保留额外 origin / hint / usage 级信息

---

## 5. 与相邻问题的边界

### 5.1 runtime object-parameter `ptrcall` ABI 不属于本文档负责范围

`Node.add_child(...)` 目前在真引擎 `test_suite` 中仍不适合作为正向 runtime anchor，并不是因为 exact metadata contract 仍未闭环，而是因为 gdextension-lite 的 class-method object-parameter `ptrcall` wrapper 仍有独立 ABI 缺口。

这条问题的事实源在：

- `doc/test_error/test_suite_engine_integration_known_limits.md`

后续工程不得再把这条 runtime ABI 缺口回写成“frontend exact metadata 计划没修完”。

### 5.2 constructor / bare-call exact route 的当前事实

当前代码库里，constructor route 与部分 bare-call exact route 仍可能依赖 declaration metadata fallback，而不是统一发布 `ExactCallableBoundary`。  
这属于尚未并入本文档主合同的相邻工作面。

后续若要扩大 exact boundary publication 的覆盖面，必须单独设计：

- 哪些 route 具备 shared-normalized callable truth source
- 哪些 route 仍只持有 owner metadata
- 如何在不漂移 `declarationSite` 语义的前提下扩展 publication

---

## 6. 长期不变量

仓库后续改动必须继续满足以下事实：

- exact engine method route 的 fixed-parameter boundary 只规范化一次、只发布一次
- `ScopeResolvedMethod.parameters()` 是 exact callable boundary 的唯一主真源
- `FrontendResolvedCall.argumentTypes()` 只表示调用点实参类型快照
- `FrontendResolvedCall.exactCallableBoundary()` 只表示 shared resolver 已发布的 exact callable boundary
- `declarationSite` 不因这条合同被改写成 `ScopeResolvedMethod`
- exact route lowering 不再回读 raw callable metadata
- exported spelling 只属于 raw export layer，不是 later frontend stages 的 primary type source
- plain `var` receiver 继续保持 `DYNAMIC_FALLBACK`
- runtime object-parameter `ptrcall` ABI 与本文档分离，不允许重新混写

---

## 7. 后续工程约束

若未来继续扩张 extension metadata 范围，必须显式处理以下问题，而不是沿用这次 exact-call 修复的窄 carrier 去包打天下：

- constructor / bare-call exact route 的 published boundary 合同
- property / signal / constant / class trait 等非 callable metadata family 的 shared 真相模型
- builtin operator metadata 的独立事实源
- outward metadata 与 runtime gate 的 family-specific contract
- typed dictionary 的完整 shared/outward/runtime 合同

尤其不能把“shared 真相”偷换成“任何事实最终都能压成一个 `GdType` 列表”。  
当前代码库里仍存在大量不属于 `GdType` 的 metadata family，例如：

- builtin operator metadata
- constructor availability / metadata
- constants
- property readable / writable flags
- class traits，例如 `isKeyed`、`isInstantiable`

---

## 8. 回归约束

涉及本文档合同的改动必须继续满足以下测试要求：

- route split 必须同时保留正反锚点：
  - plain `var` receiver 继续走 `DYNAMIC_FALLBACK`
  - typed receiver 继续走 exact resolved route
- exact route 必须覆盖至少三类 metadata family：
  - `enum::...`
  - `bitfield::...`
  - `typedarray::...`
- lowering 回归必须继续证明：
  - exact route 消费的是 published boundary
  - 不再从 raw metadata 重建 callable signature
- 复制/包装 call fact 的路径必须继续保留 `exactCallableBoundary()`
- synthetic 或错误构造的 exact route 若缺失 boundary，必须显式 fail-fast，而不是漂移成空指针或 silent fallback

任何扩展都必须同步更新本文档、相关实现注释与正反两类测试锚点。
