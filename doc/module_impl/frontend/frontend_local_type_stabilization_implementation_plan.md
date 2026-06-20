# Frontend Local Type Stabilization 实施计划

- 日期：2026-06-17
- 目标 issue：#40 `frontend: static property chain can degrade to DYNAMIC for typed local aliases`
- 状态：阶段 0-4 已完成
- 范围：frontend semantic phase 中 `:=` local slot type 稳定化

当前进度摘要：

- 已新增 `FrontendLocalTypeStabilizationAnalyzer`，并已接入 `FrontendSemanticAnalyzer` 主 pipeline。
- 已为该 analyzer 增加 package-private probe surface，仅用于阶段 1 单测锁定暂态求型合同。
- 已补阶段 0 回归基线测试，固定 write path、read path、alias 链、复杂 initializer 在当前 shared semantic 下的漂移现状。
- 已补阶段 1 单测，锁定 silent resolver 的正向求型、true dynamic fail-closed、unsupported subtree 不泄漏 shared facts/diagnostics。
- 已完成阶段 2 source-order `BlockScope.resetLocalType(...)` 写回，并已完成阶段 3 主 pipeline 接入与 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 收口。
- 已完成阶段 4 回归硬化，覆盖 true dynamic fail-closed、非目标结构封口、`FrontendVarTypePostAnalyzer` republish 与 bounded retry 验收。

---

## 1. 问题定义

issue #40 的失效模式是：`:=` local 的精确类型回填晚于 chain binding，导致后续 member/call chain 在第一次正式发布 facts 时读到的 receiver 仍是初始 `Variant`。

### 1.1 最小复现

```gdscript
class_name TypedLocalPropertyWrite
extends RefCounted

class Point:
    var next: Point = null
    var marker: int = -1

func make_point() -> Point:
    return Point.new()

func write_path(point: Point) -> void:
    var tail := make_point()
    tail.next = point

func read_path() -> bool:
    var point := make_point()
    return point.marker != -1
```

这个示例里各个名字的含义是：

- `make_point()`：返回一个 `Point` 的函数，用来模拟“initializer 本身已经能推导出精确类型”。
- `tail`：`:=` 声明的局部变量。它在 `FrontendVariableAnalyzer` 阶段先被种成 `Variant`，但它的 initializer 实际上可以推导出 `Point`。
- `point`：另一个 `:=` 局部变量，用来验证读路径。`point.marker` 应该在本应静态解析的情况下被发布成 `RESOLVED`，而不是 `DYNAMIC`。
- `next` / `marker`：`Point` 上的成员，用来观察 chain binding 是否看到了稳定后的 local type。

这两个最小场景分别对应：

- 写路径：`tail.next = point`
- 读路径：`return point.marker != -1`

在修复正确的情况下：

1. `make_point()` 的 call 本身应是 `RESOLVED`。
2. `make_point()` 的 initializer expression type 应是 `Point`。
3. `tail.next` 与 `point.marker` 都应是 `RESOLVED` member。
4. `point.marker != -1` 的 binary 结果应是 `bool`。

 当前 shared semantic 的关键顺序是：

```text
FrontendVariableAnalyzer
FrontendTopBindingAnalyzer
FrontendChainBindingAnalyzer
FrontendExprTypeAnalyzer
FrontendVarTypePostAnalyzer
```

实际链路如下：

```text
var tail := make_point()
```

1. `FrontendVariableAnalyzer` 把 `tail` 作为 inferred local 发布到 `BlockScope`，slot type 先是 `Variant`。
2. `FrontendChainBindingAnalyzer` 处理 `tail.next`，此时 `tail` 仍是 `Variant`，因此发布 `resolvedMembers()[tail.next] = DYNAMIC`。
3. `FrontendExprTypeAnalyzer` 后续才通过 `backfillInferredLocalType(...)` 把 `tail` 的 slot type 改成 `Point`。
4. 已经发布的 `resolvedMembers()` 不会被重新写入，stale `DYNAMIC` 继续向后传播。

因此 #40 的根因不是函数返回类型丢失，也不是 inner class 类型名解析失败，而是 `BlockScope` local slot 的稳定时机错位。

---

## 2. 约束合同

本计划必须遵守以下 frontend 事实所有权：

- `FrontendVariableAnalyzer` 负责 declaration inventory seed。
- `FrontendTopBindingAnalyzer` 负责 `symbolBindings()`。
- `FrontendChainBindingAnalyzer` 负责 `resolvedMembers()` 和 chain-step `resolvedCalls()`。
- `FrontendExprTypeAnalyzer` 负责 `expressionTypes()` 和 bare-call `resolvedCalls()`。
- `FrontendVarTypePostAnalyzer` 负责 `slotTypes()` republish。

新增 phase 不得写入：

- `analysisData.updateResolvedMembers(...)`
- `analysisData.updateResolvedCalls(...)`
- `analysisData.updateExpressionTypes(...)`
- `analysisData.updateSlotTypes(...)`

新增 phase 的唯一持久副作用应是：

```java
BlockScope.resetLocalType(...)
```

新增 phase 默认不得向共享 `DiagnosticManager` 发布最终 diagnostics。`FrontendSemanticAnalyzer` 每个 phase 后都会 snapshot diagnostics；一旦 provisional diagnostic 外泄，后续 facts 即使修正也无法撤回。

---

## 3. 目标与非目标

### 3.1 目标

- 在正式 chain binding 前稳定 supported executable body 内 `:=` local 的 `BlockScope` slot type。
- 支持直接 initializer：

```gdscript
var point := make_point()
return point.marker
```

- 支持 source-order local alias 链：

```gdscript
var a := make_point()
var b := a
var c := b
return c.marker
```

- 支持复杂 initializer 中的 chain/call/argument typing：

```gdscript
var p := factory.make_point(arg).next
var q := p
return q.marker
```

- 保持 side-table owner 不变。
- 保持真实 dynamic receiver 的 runtime-open 语义，不把 `DYNAMIC` 当成 compile blocker。

### 3.2 非目标

- 不修 #41 dynamic member lowering contract drift。
- 不实现 dynamic member runtime named get/set lowering。
- 不做 property `:=` metadata backfill。
- 不做 whole-module fixed-point。
- 不做 CFG/control-flow merge aware local type refinement。
- 不打开 parameter default、lambda、capture、`for`、`match`、block-local `const`、class `const` 等当前 MVP 外结构。
- 不新增公共 frontend API；如需提取 helper，优先保持在 analyzer support 包内，且不拥有 phase facts。

---

## 4. 总体设计

新增一个窄职责 phase：

```text
FrontendLocalTypeStabilizationAnalyzer
```

插入位置：

```text
FrontendVariableAnalyzer
FrontendTopBindingAnalyzer
FrontendLocalTypeStabilizationAnalyzer
FrontendChainBindingAnalyzer
FrontendExprTypeAnalyzer
FrontendVarTypePostAnalyzer
```

该 phase 的输入：

- `analysisData.moduleSkeleton()`
- `analysisData.scopesByAst()`
- `analysisData.symbolBindings()`
- `ClassRegistry`
- 当前已发布的 `CallableScope` / `BlockScope`

该 phase 的输出：

- 更新 supported executable block 中 eligible inferred local 的 `BlockScope` slot type。

该 phase 不发布：

- `resolvedMembers()`
- `resolvedCalls()`
- `expressionTypes()`
- `slotTypes()`
- lowering-ready facts

该 phase 失败策略：

- 若 initializer 不能稳定求型，保留已有 slot type，通常是 `Variant`。
- 不发最终 diagnostics。
- 不猜测真实 dynamic receiver 的静态类型。

---

## 5. 文件级实施步骤

### 5.1 新增 `FrontendLocalTypeStabilizationAnalyzer`

路径：

```text
src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendLocalTypeStabilizationAnalyzer.java
```

职责：

- 遍历 module 中支持的 callable executable body。
- 只处理 `DeclarationKind.VAR` 且 `FrontendDeclaredTypeSupport.isInferredTypeRef(variableDeclaration.type())` 的 local declaration。
- 读取 `VariableDeclaration.value()` 作为 initializer。
- 用 silent expression evaluator 求 initializer type。
- 若结果稳定，则调用 `BlockScope.resetLocalType(variableName, variableDeclaration, inferredType)`。

建议内部结构：

```text
FrontendLocalTypeStabilizationAnalyzer
  analyze(classRegistry, analysisData)
  StabilizationWalker
    walkCallableBody(...)
    walkSupportedExecutableBlock(...)
    handleVariableDeclaration(...)
  SilentExpressionResolver
    resolveExpressionType(expression, finalizeWindow)
    resolveAttributeExpressionType(attributeExpression)
    resolveExpressionDependencyType(expression, finalizeWindow)
```

`SilentExpressionResolver` 优先作为 analyzer 的私有嵌套类实现，避免过早提取新公共 API。若实现过程中发现与 `FrontendChainBindingAnalyzer` / `FrontendExprTypeAnalyzer` 的 dispatch 重复不可控，再提取 package-private support helper。

### 5.2 在 `FrontendSemanticAnalyzer` 插入新 phase

修改点：

```text
src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java
```

实施要求：

- 新增 `localTypeStabilizationAnalyzer` field。
- 默认构造路径初始化该 analyzer。
- 在 `topBindingAnalyzer.analyze(...)` 之后、`chainBindingAnalyzer.analyze(...)` 之前调用：

```java
localTypeStabilizationAnalyzer.analyze(classRegistry, analysisData);
analysisData.updateDiagnostics(diagnosticManager.snapshot());
```

该 analyzer 不应写 diagnostics；保留 snapshot 是为了保持 phase 边界形式一致。

需要更新相关注释：

- chain binding 注释应说明它消费已经稳定的 local slot。
- var type post 注释应说明 slot type 已由 local stabilization 和 expression typing settle。

### 5.3 收口 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)`

修改点：

```text
src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzer.java
```

推荐过渡策略：

1. 初次实现时，保留 `backfillInferredLocalType(...)` 作为兜底/一致性检查。
2. 若当前 block slot 仍是 `Variant`，允许按旧逻辑回填，避免新 pass MVP 遗漏场景导致大面积回归。
3. 若当前 block slot 已是非 `Variant`：
   - initializer final type 相同：no-op。
   - initializer final type 不同：fail-fast 或记录内部协议错误，不要静默覆盖。

后续稳定后可删除兜底写回，使 `FrontendLocalTypeStabilizationAnalyzer` 成为 inferred local slot backfill 的唯一 owner。

### 5.4 保持 `FrontendChainBindingAnalyzer` 职责不变

修改点：

```text
src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendChainBindingAnalyzer.java
```

实施要求：

- 不把 local stabilization 混入 chain binding。
- 不修改 `publishReduction(...)` 的 owner 语义。
- 只更新注释或测试，确认 chain binding 依赖上游 local slot 已稳定。

### 5.5 保持 `FrontendVariableAnalyzer` seed 行为不变

修改点：

```text
src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendVariableAnalyzer.java
```

实施要求：

- `:=` local 继续通过 `FrontendDeclaredTypeSupport.resolveTypeOrVariant(...)` 以 `Variant` seed 入 `BlockScope`。
- 不在 variable phase 中读取 initializer。
- 可更新注释，明确 variable analyzer 只负责 inventory seed，不负责 inferred local final type。

### 5.6 保持 `FrontendVarTypePostAnalyzer` republish 行为不变

修改点：

```text
src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendVarTypePostAnalyzer.java
```

实施要求：

- 不新增推断逻辑。
- 继续从当前 `BlockScope` / `CallableScope` 读取最终 slot type，并发布 `slotTypes()`。
- 更新注释，说明 local slot 可能已经由 `FrontendLocalTypeStabilizationAnalyzer` 稳定。

### 5.7 复用现有纯计算 helper

优先复用：

- `FrontendExpressionSemanticSupport`
- `FrontendChainReductionFacade`
- `FrontendChainReductionHelper`
- `FrontendChainHeadReceiverSupport`
- `FrontendChainStatusBridge`
- `FrontendExecutableInventorySupport`
- `FrontendDeclaredTypeSupport`

不得直接调用：

- `FrontendChainBindingAnalyzer.analyze(...)`
- `FrontendChainBindingAnalyzer.publishReduction(...)`
- `FrontendExprTypeAnalyzer.analyze(...)`
- `FrontendExprTypeAnalyzer.publishExpressionType(...)`
- `FrontendExprTypeAnalyzer.publishResolvedExpressionType(...)`
- `FrontendExprTypeAnalyzer.publishBareResolvedCall(...)`
- `FrontendVarTypePostAnalyzer.analyze(...)`

原因：这些入口会发布正式 side table 或 diagnostics，不适合 pre-chain provisional stabilization。

---

## 6. Stabilization 算法细则

### 6.1 Eligible declaration

一个 declaration 只有同时满足以下条件才参与：

- AST 节点是 `VariableDeclaration`。
- `kind() == DeclarationKind.VAR`。
- `value() != null`。
- `FrontendDeclaredTypeSupport.isInferredTypeRef(type()) == true`。
- declaration scope 是 `BlockScope`。
- `FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind()) == true`。

### 6.2 Source-order 处理

第一版优先实现 source-order 单遍：

```text
for statement in block.statements:
  if statement is eligible var:
    inferred = resolveInitializer(statement.value)
    if inferred is stable:
      blockScope.resetLocalType(...)
```

这已经覆盖最关键 alias 链：

```gdscript
var a := make_point()
var b := a
var c := b
return c.marker
```

因为处理 `b := a` 时，`a` 已经被同一 pass 写回为 `Point`。

### 6.3 Bounded fixed-point（未来扩展保留项）

当前阶段不实现 retry / fixed-point。

原因是 supported local `:=` initializer 中的 local-to-local 依赖受 declaration-order 可见性约束：

- GDScript local 不能引用之后才声明的 local。
- `FrontendVisibleValueResolver` 会过滤 declaration-after-use 与 initializer self-reference。
- 因此 eligible declaration 之间的有效依赖边只能指向同 block 的前序 visible local。
- Source-order 单遍天然就是当前依赖图的拓扑序；处理 `b := a` 时，`a` 若能稳定，已经被前序 statement 写回。

未来只有在打开下列范围时，才重新评估 bounded fixed-point 或更完整的数据流分析：

- forward local reference。
- assignment-based local type refinement。
- CFG branch merge / loop body refinement。
- `for` / `match` / lambda / capture local inventory。
- block-local `const`、class `const`、property `:=` metadata backfill。
- 函数返回类型由函数体推断或跨 callable / whole-module 推断。

若未来确实需要 bounded retry，应仍限制在单个 supported block 的 eligible declarations 集合内，并保持以下收敛规则：

- 每个 eligible declaration 最多从 seed 类型稳定一次。
- 只允许 `Variant -> exact type` 的单调收敛。
- 不允许 exact type 之间来回覆盖。
- 最大有效更新次数不超过 eligible declarations 数量。
- 若一轮无变化则停止。

仍不建议实现 whole-module fixed-point 作为本 phase 的默认行为。

### 6.4 Stable result 判定

允许写回：

- `FrontendExpressionType.status() == RESOLVED` 且 `publishedType() != null`。

第一版建议不把 `DYNAMIC(Variant)` 作为“更精确”写回目标；真实 dynamic 保持 `Variant` 即可。

不写回：

- `BLOCKED`
- `DEFERRED`
- `FAILED`
- `UNSUPPORTED`
- `publishedType() == null`
- value-required `void`
- route-head-only `TYPE_META`

### 6.5 Fail-closed 边界

以下场景保持 `Variant`，不猜测：

- parameter default
- lambda / capture
- `for`
- `match`
- unsupported subtree
- block-local `const` initializer
- class `const`
- control-flow merge / join
- true dynamic receiver
- initializer 中无法稳定解析的 call/member/subscript

---

## 7. Silent Expression Resolver 细则

### 7.1 基本原则

silent resolver 是本 pass 内部的 provisional evaluator。

它可以计算：

- literal
- `self`
- identifier
- attribute chain
- bare call
- subscript
- unary/binary
- assignment expression 的 value-required 结果
- remaining explicit expression 的已支持部分

它不能：

- 写 `analysisData.expressionTypes()`。
- 写 `analysisData.resolvedMembers()`。
- 写 `analysisData.resolvedCalls()`。
- 发最终 diagnostics。

### 7.2 Attribute chain

对 `AttributeExpression`：

1. 使用 `FrontendChainReductionFacade` 做本 pass 内部缓存。
2. `FrontendChainReductionFacade` 通过 `FrontendChainHeadReceiverSupport` 解析 head receiver。
3. 通过本 resolver 解析 fallback expression 与 argument expression。
4. 从 `ReductionResult` 转成 transient `FrontendExpressionType`。
5. 不发布 `StepTrace.suggestedMember()` 或 `StepTrace.suggestedCall()`。

### 7.3 Call arguments

对 bare call 或 attribute call：

- 复用 `FrontendExpressionSemanticSupport.resolveCallExpressionType(...)`。
- 参数表达式通过本 resolver 递归求型。
- overload selection 继续复用现有 boundary compatibility 与 ranking 规则。
- `publishedCallOrNull()` 只能作为 transient route 信息，不能写入 `resolvedCalls()`。

### 7.4 Identifier local alias

对 identifier：

- 通过既有 `symbolBindings()` 确认 binding kind。
- 对 `LOCAL_VAR` / `PARAMETER` / `CAPTURE` 等值 binding，读取当前 scope 中的 `ScopeValue.type()`。
- 因为本 pass 会立即 `resetLocalType(...)`，后续 alias 可读取前序 local 的最新类型。

---

## 8. 测试计划

### 8.1 新增 phase 单元测试

建议新增：

```text
src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendLocalTypeStabilizationAnalyzerTest.java
```

覆盖：

- source-order local alias。
- 复杂 initializer。
- true dynamic fail-closed。
- unsupported subtree 不发最终 diagnostics。

### 8.2 扩展 semantic analyzer tests

建议扩展：

```text
src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendChainBindingAnalyzerTest.java
src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendExprTypeAnalyzerTest.java
src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendVarTypePostAnalyzerTest.java
src/test/java/gd/script/gdcc/frontend/sema/FrontendSemanticAnalyzerFrameworkTest.java
```

### 8.3 必测用例

#### 用例 A：#40 write/read path

```gdscript
class Point:
    var next: Point = null
    var marker: int = -1

func make_point() -> Point:
    return Point.new()

func write_path(point: Point) -> void:
    var tail := make_point()
    tail.next = point

func read_path() -> bool:
    var point := make_point()
    return point.marker != -1
```

验收：

- `make_point()` call 是 `RESOLVED`。
- initializer expression 是 `RESOLVED(Point)`。
- `tail.next` 是 `RESOLVED` member，不是 `DYNAMIC`。
- `point.marker` 是 `RESOLVED` member，不是 `DYNAMIC`。
- comparison result 是 `bool`。
- 不产生 member/expression diagnostics。

#### 用例 B：parameter alias

```gdscript
class Point:
    var marker: int = -1

func ping(value: Point) -> int:
    var a := value
    return a.marker
```

验收：

- `a` 的最终 slot type 是 `Point`。
- `a.marker` 是 `RESOLVED` member。
- return expression type 是 `int`。
- `2026-06-20`：已由 `FrontendLocalTypeStabilizationAnalyzerTest.analyzeStabilizesParameterAliasFromCallableParameter`
  与 `FrontendSemanticAnalyzerFrameworkTest.analyzePublishesParameterAliasFactsAcrossBodyPhases` 补齐测试闭环。

#### 用例 C：local alias 链

```gdscript
class Point:
    var marker: int = -1

func make_point() -> Point:
    return Point.new()

func ping() -> int:
    var a := make_point()
    var b := a
    var c := b
    return c.marker
```

验收：

- `a` / `b` / `c` 的最终 `slotTypes()` 都是 `Point`。
- `c.marker` 是 `RESOLVED` member。
- 不产生 member/expression diagnostics。

#### 用例 D：复杂 initializer + 参数调用

```gdscript
class Point:
    var marker: int = -1

class Box:
    var next: Point = Point.new()

class Factory:
    func make_point(arg: int) -> Box:
        return Box.new()

func ping(factory: Factory, arg: int) -> int:
    var p := factory.make_point(arg).next
    var q := p
    return q.marker
```

验收：

- `factory.make_point(arg).next` initializer 是 `RESOLVED(Point)`。
- `p` / `q` 的最终 slot type 是 `Point`。
- `q.marker` 是 `RESOLVED` member。

#### 用例 E：子 block 读取父 block 已稳定 local

```gdscript
class Point:
    var marker: int = -1

func make_point() -> Point:
    return Point.new()

func ping(toggle: bool):
    var a := make_point()
    if toggle:
        var b := a
        return b.marker
    return a.marker
```

验收：

- 父 block 中 `a` 会先稳定为 `Point`。
- `if` body 中 `b := a` 能直接读取父 block 已稳定的 `Point`，不会回退成 `Variant`。
- `b.marker` 与 block 外 `a.marker` 都继续走 `RESOLVED` member 路径。

#### 用例 F：子 block shadow 不污染父 block

```gdscript
class Point:
    var marker: int = -1

func make_point() -> Point:
    return Point.new()

func ping(toggle: bool, dynamic_host):
    var a := make_point()
    if toggle:
        var a := dynamic_host.next
        a
    var after := a
    return after.marker
```

验收：

- 父 block 的 `a` 保持 `Point`。
- `if` body 内 same-callable shadow declaration 继续由 variable phase 发出 `sema.variable_binding` 并跳过绑定。
- local stabilization 不额外发布 diagnostics，也不会因为该 rejected shadow 去改写父 block slot。
- `var after := a` 读取的仍然是父 block 已稳定的 `a`，不会被子 block shadow 污染。

#### 用例 G：true dynamic fail-closed

```gdscript
func ping(dynamic_host):
    var p := dynamic_host.next
    return p.member
```

验收：

- `p` 保持 `Variant`。
- dynamic member 仍按 frontend runtime-open 规则处理。
- 不把 true dynamic receiver 猜成 exact type。
- 不要求 lowering 通过；#41 不属于本计划。

### 8.4 运行命令

新增独立 phase 测试后：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendLocalTypeStabilizationAnalyzerTest,FrontendChainBindingAnalyzerTest,FrontendExprTypeAnalyzerTest,FrontendVarTypePostAnalyzerTest,FrontendSemanticAnalyzerFrameworkTest
```

迭代单类：

```bash
script/run-gradle-targeted-tests.sh --tests FrontendLocalTypeStabilizationAnalyzerTest
script/run-gradle-targeted-tests.sh --tests FrontendChainBindingAnalyzerTest
script/run-gradle-targeted-tests.sh --tests FrontendExprTypeAnalyzerTest
script/run-gradle-targeted-tests.sh --tests FrontendVarTypePostAnalyzerTest
script/run-gradle-targeted-tests.sh --tests FrontendSemanticAnalyzerFrameworkTest
```

---

## 9. 验收细则

### 9.1 功能验收

- typed local `:=` 后续 member access 不再错误降级为 `DYNAMIC`。
- source-order alias 链可以传播 exact type。
- complex chain initializer 可以通过 provisional reduction 稳定 local slot。
- 参数参与 initializer call overload selection 时可以使用已发布 parameter type。

### 9.2 Owner 验收

- `FrontendLocalTypeStabilizationAnalyzer` 只写 `BlockScope`。
- `FrontendChainBindingAnalyzer` 仍是 `resolvedMembers()` / chain-step `resolvedCalls()` 唯一 owner。
- `FrontendExprTypeAnalyzer` 仍是 `expressionTypes()` / bare-call `resolvedCalls()` owner。
- `FrontendVarTypePostAnalyzer` 仍是 `slotTypes()` owner。

### 9.3 Diagnostics 验收

- 新 pass 不发最终 diagnostics。
- provisional failure 不污染 shared `DiagnosticManager`。
- 原有正式 diagnostics 仍由 chain binding、expression typing、type check、compile check 产生。
- 同一源码错误不产生重复 diagnostics。

### 9.4 边界验收

- true dynamic receiver 仍保持 `DYNAMIC` / `Variant` 路径。
- unsupported subtree 不被新 pass 打开。
- control-flow merge 不做推断。
- 子 block 可以读取父 block 已稳定 local，但 child-local writeback 不回写父 block。
- route-head-only `TYPE_META` 不进入 ordinary `:=` value backfill。

---

## 10. 风险与缓解

### 10.1 Owner 漂移

风险：新 pass 如果写 `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()`，会形成双 owner。

缓解：

- 测试中加入 side-table owner 验收。
- 新 pass 内部 cache 使用 private map，不暴露给 `analysisData`。
- code review 明确禁止调用 `analysisData.updateResolvedMembers(...)` 等发布入口。

### 10.2 Provisional diagnostics 泄漏

风险：新 pass 如果复用正式 analyzer path，会把 provisional 错误写入 shared `DiagnosticManager`。

缓解：

- 不直接调用 `FrontendChainBindingAnalyzer.analyze(...)` 或 `FrontendExprTypeAnalyzer.analyze(...)`。
- 只复用纯 helper。
- 对 notes 使用 no-op sink。

### 10.3 复杂 initializer 规则漂移

风险：新 pass 自己复制 expression dispatch，未来与正式 analyzer 规则漂移。

缓解：

- 优先复用 `FrontendExpressionSemanticSupport` 与 `FrontendChainReductionFacade`。
- 初版可把 resolver 作为新 analyzer 私有嵌套类；若重复扩大，再提取 package-private `FrontendExpressionResolutionSessionSupport`。
- 不新增独立 overload / member / conversion 规则。

### 10.4 `FrontendExprTypeAnalyzer` 旧 backfill 覆盖新结果

风险：旧 `backfillInferredLocalType(...)` 在 expression typing 阶段静默覆盖新 pass 结果。

缓解：

- 过渡期改成 no-op / 一致性检查 / `Variant` 兜底。
- 非 `Variant` 且类型不一致时 fail-fast，暴露内部协议漂移。

### 10.5 #41 范围污染

风险：实现 #40 时顺手修改 dynamic member lowering，使计划扩大且混淆根因。

缓解：

- 本计划只修 typed local alias precision。
- true dynamic member lowering 仍由 #41 单独处理。
- 测试只做 sema 层 fail-closed guard，不要求 lowering 通过。

---

## 11. 回滚策略

建议按提交层次回滚，不引入运行时开关。

最小回滚点：

1. 移除 `FrontendSemanticAnalyzer` 中的新 phase 调用。
2. 保留或恢复 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 的旧兜底行为。
3. 删除或停用新增 `FrontendLocalTypeStabilizationAnalyzer` 相关测试。

如果新 pass 已经稳定：

- 再单独提交删除旧 backfill 兜底。
- 这样任何回归都可以回滚到“旧 expr analyzer late backfill”状态。

---

## 12. 实施顺序检查清单

1. 新增 failing tests，覆盖 #40 write path/read path、alias 链、复杂 initializer、true dynamic fail-closed。
2. 新增 `FrontendLocalTypeStabilizationAnalyzer` MVP，先完成 source-order 单遍。
3. 插入 `FrontendSemanticAnalyzer` phase 顺序。
4. 运行 targeted frontend semantic tests。
5. 增加 bounded retry 或确认 source-order 单遍已覆盖目标样例。
6. 收口 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)`。
7. 更新注释与计划文档中的完成状态。
8. 再次运行 targeted tests。

---

## 13. 实施阶段细化

本节把上面的检查清单展开成可执行阶段。每一阶段都应具备明确输入、输出和验收门槛，避免实现时只看到“大目标”而缺少落地顺序。

### 13.1 阶段 0: 建立回归基线

状态：已完成

目标：

- 先把 issue #40 的失败样例固定成测试，再开始改实现。

步骤：

1. 选择测试落点。
   - 优先 `FrontendChainBindingAnalyzerTest`
   - 其次 `FrontendExprTypeAnalyzerTest`
   - 再补 `FrontendVarTypePostAnalyzerTest`
   - 最后补 `FrontendSemanticAnalyzerFrameworkTest`
2. 写入最小复现样例。
   - write path：`var tail := make_point(); tail.next = point`
   - read path：`var point := make_point(); return point.marker != -1`
   - alias 链：`var a := make_point(); var b := a; var c := b`
   - 复杂 initializer：`var p := factory.make_point(arg).next`
3. 让测试先明确当前错误状态。
   - 现状应能观察到 `DYNAMIC` 或 `Variant` 漂移。
   - 目的不是修复，而是把问题固定成可重复回归。

完成记录：

- [x] 任务 0.1：测试落点选在 `FrontendChainBindingAnalyzerTest`，保持回归面直接锚定第一次正式发布的 member/call facts。
- [x] 任务 0.2：已补四类样例：
  - `analyzeCurrentTypedLocalPropertyWritePathStillDriftsToDynamicBeforeStabilizationPhase()`
  - `analyzeCurrentTypedLocalPropertyReadPathStillDriftsToDynamicBeforeStabilizationPhase()`
  - `analyzeCurrentTypedLocalAliasChainStillDriftsToDynamicBeforeStabilizationPhase()`
  - `analyzeCurrentComplexInitializerAliasStillDriftsToDynamicBeforeStabilizationPhase()`
- [x] 任务 0.3：上述测试当前断言的是“现状漂移”而不是“修复后行为”，这样可以在不破坏现有测试套件的前提下，把 #40 的回归面固定下来，供阶段 2/3 反向翻转断言。

阶段产出：

- 具备 4 个可重复的 shared-semantic 回归基线测试。
- 文档与测试对复现语义一致，不再依赖变量名猜测。

阶段验收：

- 读者只看测试片段就能理解 `tail`、`point`、`a/b/c` 分别代表什么。
- 当前实现下，这些测试会稳定暴露 `DYNAMIC` / `Variant` 漂移，证明回归面真实存在。

### 13.2 阶段 1: 提取 silent 求型编排

状态：已完成

目标：

- 准备可复用的“静默表达式求型”编排层，但不改变任何正式 facts。

步骤：

1. 在 `FrontendLocalTypeStabilizationAnalyzer` 内部先搭出私有 resolver/walker。
2. 复用 `FrontendExpressionSemanticSupport` 处理普通表达式。
3. 复用 `FrontendChainReductionFacade` / `FrontendChainReductionHelper` 处理复杂 attribute chain。
4. 复用 `FrontendChainHeadReceiverSupport` 处理 head receiver。
5. 统一把 provisional 失败降级为 `Variant`，不发最终 diagnostics。

完成记录：

- [x] 任务 1.1：`FrontendLocalTypeStabilizationAnalyzer` 已具备 walker + `SilentExpressionResolver` scaffold，仍保持 analyzer 私有实现，没有提取新公共 API。
- [x] 任务 1.2：silent resolver 已复用 `FrontendExpressionSemanticSupport` 处理 literal / identifier / unary / binary / bare call / remaining explicit expression。
- [x] 任务 1.3：silent resolver 已复用 `FrontendChainReductionFacade`、`FrontendChainReductionHelper`、`FrontendChainStatusBridge` 与 `FrontendAssignmentSemanticSupport` 处理 attribute chain、复杂 initializer 与 assignment expression。
- [x] 任务 1.4：已新增 package-private probe surface 和 `FrontendLocalTypeStabilizationAnalyzerTest`，锁定以下合同：
  - 复杂 initializer 可在单元层被静默求出暂态 `RESOLVED` type。
  - true dynamic receiver 保持 `DYNAMIC(Variant)`，不被误收窄。
  - `for` / `match` 等当前非目标子树不会被静默打开。
  - `analysisData.resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` / `slotTypes()` 不被写入。
  - 共享 `DiagnosticManager` 不泄漏新 diagnostics。

阶段产出：

- 新 phase 的内部求型路径已可单独运行。
- 仍未接入正式 pipeline。

阶段验收：

- 复杂 initializer 能在单元层面被求出暂态 type。
- `analysisData.resolvedMembers()` / `analysisData.resolvedCalls()` / `analysisData.expressionTypes()` 不被写入。
- 无共享 diagnostics 泄漏。

### 13.3 阶段 2: 实现 source-order local stabilization MVP

目标：

- 先在 supported executable body 内完成最小闭环。

步骤：

1. 遍历 supported executable body 的 statement 顺序。
2. 只处理 eligible `var :=` declaration。
3. 对 initializer 调 silent resolver。
4. 一旦得到稳定 exact type，立即 `BlockScope.resetLocalType(...)`。
5. 支持 `a := ...; b := a; c := b` 这种同 block alias 链和复杂初始化表达式。

完成记录：

- [x] 任务 2.1：已在 `FrontendLocalTypeStabilizationAnalyzerTest` 增补阶段 2 独立单测，直接调用 `analyze(...)` 锚定真实 `BlockScope` 写回行为：
  - source-order alias 链：`a := make_point(); b := a; c := b` 应在同一 block 内从前到后稳定为 `Point`。
  - 复杂 initializer + alias：`p := factory.make_point(seed).next; q := p` 应稳定为 `Point`，同时不发布 `resolvedMembers()` / `resolvedCalls()` / `expressionTypes()` / `slotTypes()`。
  - true dynamic fail-closed：`dynamic_host.next` 和后续 alias 继续保持 `Variant`。
  - unsupported control-flow subtree：`for` / `match` 内 local 在前置 variable phase 中本就不绑定，因此阶段 2 断言它们不会进入 stabilization probe/writeback；普通 supported block 与 `if` block 仍可稳定写回。
- [x] 任务 2.2：`FrontendLocalTypeStabilizationAnalyzer.analyze(...)` 已启用 source-order writeback，`probe(...)` 继续保持阶段 1 的只读探针合同。
- [x] 任务 2.3：eligible `var :=` initializer 求型后会立即按稳定结果写回当前 `BlockScope`，因此同一 supported block 后续 `b := a` / `c := b` 能读到前序 local 的最新 exact type。
- [x] 任务 2.4：稳定写回标准已按阶段 2 收窄为 `RESOLVED` exact type；`DYNAMIC(Variant)`、失败/unsupported/deferred/blocked 结果和 value-required `void` 均保持 fail-closed，不写回。
- [x] 任务 2.5：已运行 targeted 验证：
  - `script/run-gradle-targeted-tests.sh --tests FrontendLocalTypeStabilizationAnalyzerTest`
  - `script/run-gradle-targeted-tests.sh --tests FrontendLocalTypeStabilizationAnalyzerTest,FrontendChainBindingAnalyzerTest,FrontendExprTypeAnalyzerTest,FrontendVarTypePostAnalyzerTest`
  - `script/run-gradle-targeted-tests.sh --tests FrontendSemanticAnalyzerFrameworkTest`
  - `git diff --check`

阶段产出：

- `FrontendLocalTypeStabilizationAnalyzer` 的 MVP 行为完成。
- 仍未接入 `FrontendSemanticAnalyzer` 主链路。

阶段验收：

- 单元测试中，alias 链可以从前到后稳定传播。
- `tail.next` / `point.marker` / `c.marker` 等后续 member 访问不再因为 local slot 初始 `Variant` 退化为 `DYNAMIC`。
- 真 dynamic receiver 仍保持 `Variant`。

### 13.4 阶段 3: 接入主 pipeline 并收口旧 backfill

状态：已完成

目标：

- 让新 phase 成为唯一的 inferred local slot primary owner。

步骤：

1. 在 `FrontendSemanticAnalyzer` 中插入新 phase。
2. 更新相关注释，明确 chain binding 依赖 local stabilization 完成。
3. 将 `FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 降级为兜底或一致性检查。
4. 确认 `FrontendVarTypePostAnalyzer` 仍只做 republish。

完成记录：

- [x] 任务 3.1：已在 `FrontendSemanticAnalyzer` 中新增 `localTypeStabilizationAnalyzer` 字段，并保持既有构造器签名兼容；默认构造路径会创建 `FrontendLocalTypeStabilizationAnalyzer`，测试可通过新增长构造器注入 recording analyzer。
- [x] 任务 3.2：主 shared semantic pipeline 已调整为 `FrontendTopBindingAnalyzer -> FrontendLocalTypeStabilizationAnalyzer -> FrontendChainBindingAnalyzer`，并在 local stabilization 后继续刷新 diagnostics snapshot，保持 phase boundary 形式一致。
- [x] 任务 3.3：已更新 `FrontendSemanticAnalyzer`、`FrontendLocalTypeStabilizationAnalyzer`、`FrontendExprTypeAnalyzer` 与 `FrontendVarTypePostAnalyzer` 的注释，明确 chain binding 消费已稳定 local slot，var type post 只 republish 已 settle 的 lexical inventory。
- [x] 任务 3.4：`FrontendExprTypeAnalyzer.backfillInferredLocalType(...)` 已降级为 guarded fallback：只允许 untouched `Variant` slot 被补写；非 `Variant` slot 与 initializer final type 相同则 no-op，不一致则 fail-fast，避免 silent double-owner overwrite。
- [x] 任务 3.5：已补阶段 3 单元测试与集成断言：
  - `FrontendChainBindingAnalyzerTest` 翻转 #40 write path/read path/alias 链/复杂 initializer 旧漂移基线，确认 chain binding 看到稳定 receiver。
  - `FrontendSemanticAnalyzerFrameworkTest` 新增 local stabilization phase-boundary recording，并补完整 pipeline 的 typed local write/read 集成断言。
  - `FrontendExprTypeAnalyzerTest` 增补 pre-stabilized slot no-op 与 conflicting backfill fail-fast 覆盖。
  - `FrontendVarTypePostAnalyzerTest` 增补稳定 alias slot republish 覆盖，并同步 helper 的手工 pipeline 顺序。
- [x] 任务 3.6：已完成阶段 3 targeted 验证：
  - `script/run-gradle-targeted-tests.sh --tests FrontendLocalTypeStabilizationAnalyzerTest,FrontendChainBindingAnalyzerTest,FrontendExprTypeAnalyzerTest,FrontendVarTypePostAnalyzerTest,FrontendSemanticAnalyzerFrameworkTest`
  - IDE targeted build for modified implementation/test files
  - `git diff --check`

阶段产出：

- 主 pipeline 已按新顺序运行。
- 旧 backfill 不再是 primary owner。

阶段验收：

- `FrontendSemanticAnalyzerFrameworkTest` 能确认 phase 顺序正确。
- `FrontendChainBindingAnalyzer` 看到的是稳定 local slot，而不是 `Variant` seed。
- `FrontendExprTypeAnalyzer` 不再静默改写已经稳定的 local slot。

### 13.5 阶段 4: 回归硬化与边界验证

状态：已完成

目标：

- 防止新 pass 只修了主样例，却引入新的边界回归。

步骤：

1. 增补 true dynamic fail-closed 测试。
2. 增补 unsupported subtree / 非目标结构的保守行为测试。
3. 增补 `FrontendVarTypePostAnalyzer` republish 预期。
4. 根据需要加 bounded retry 验收。
5. 运行 targeted tests 并清理注释歧义。

当前执行状态：

- [x] 任务 4.1：已增补 true dynamic fail-closed 的完整 pipeline 覆盖，确认 initializer 与后续 alias member route 均保持 `DYNAMIC` / `Variant`，并已运行：
  - `script/run-gradle-targeted-tests.sh --tests FrontendChainBindingAnalyzerTest`
- [x] 任务 4.2：已增补 lambda body 非目标结构封口测试，确认 local stabilization 不进入 lambda 内部局部、不收窄 lambda initializer，并已运行：
  - `script/run-gradle-targeted-tests.sh --tests FrontendLocalTypeStabilizationAnalyzerTest`
- [x] 任务 4.2a：已补子 block 边界回归测试，直接锁定两条 block-scope 合同：
  - `if` body 可读取父 block 已稳定 local，例如 `var b := a`
  - 被 variable phase 拒绝的子 block 同名 shadow declaration 不污染父 block，后续 `var after := a` 仍读取父 block slot
- [x] 任务 4.3：已增补 `FrontendVarTypePostAnalyzer` republish 预期，确认陈旧 slot facts 会被清理后重发稳定 alias type，true dynamic local 最终仍发布为 `Variant`，并已运行：
  - `script/run-gradle-targeted-tests.sh --tests FrontendVarTypePostAnalyzerTest`
- [x] 任务 4.4：已复核 bounded retry 验收，现有 `FrontendChainBindingAnalyzerTest` 覆盖一次 finalize-window retry 成功与 retry 后仍 deferred 的封口路径，并已补测试注释说明对应合同。
- [x] 任务 4.5：已清理 `FrontendChainBindingAnalyzer` / `FrontendVarTypePostAnalyzer` 注释歧义，并完成阶段 4 targeted 验证：
  - `script/run-gradle-targeted-tests.sh --tests FrontendLocalTypeStabilizationAnalyzerTest,FrontendChainBindingAnalyzerTest,FrontendExprTypeAnalyzerTest,FrontendVarTypePostAnalyzerTest,FrontendSemanticAnalyzerFrameworkTest`
  - `git diff --check`

阶段产出：

- 计划中的最小样例与边界样例均已稳定。
- 计划与测试之间没有语义歧义。
- 完整 pipeline 下，true dynamic local initializer 与 alias member access 均保持 `DYNAMIC` / `Variant`。
- lambda body、`for`、`match` 等非目标结构不会被 local stabilization 悄悄打开。
- `FrontendVarTypePostAnalyzer` 只 republish settled callable-local inventory，陈旧 `slotTypes()` facts 会被清理后重发。

阶段验收：

- 不新增重复 diagnostics。
- 不把 `DYNAMIC` 真语义误收窄成 exact type。
- 不把 control-flow merge / lambda / `for` / `match` 等 MVP 外场景悄悄打开。
- `if` / `while` 等 supported child block 能消费父 block 已稳定 local，且 rejected shadow declaration 不影响父 block slot。
- 仍然保持 side-table owner 不变。

### 13.6 阶段间切换条件

从一个阶段进入下一个阶段之前，至少满足：

- 阶段 0 -> 阶段 1：最小复现测试已固定，且当前错误状态可重复。
- 阶段 1 -> 阶段 2：silent resolver 已能稳定返回可用的暂态 type。
- 阶段 2 -> 阶段 3：alias 链与复杂 initializer 样例已在独立测试中通过。
- 阶段 3 -> 阶段 4：主 pipeline 接入后，#40 样例仍通过，且旧 backfill 不再覆盖新结果。
- 阶段 4 -> 完成：边界样例通过，且测试覆盖了 write path、read path、alias 链、复杂 initializer、true dynamic fail-closed。
