# Frontend Local Type Stabilization 实施计划

- 日期：2026-06-17
- 目标 issue：#40 `frontend: static property chain can degrade to DYNAMIC for typed local aliases`
- 状态：实施计划；尚未修改生产代码
- 范围：frontend semantic phase 中 `:=` local slot type 稳定化

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

### 6.3 Bounded fixed-point

若实现需要 retry，则限制在单个 supported block 的 eligible declarations 集合内。

收敛规则：

- 每个 eligible declaration 最多从 seed 类型稳定一次。
- 只允许 `Variant -> exact type` 的单调收敛。
- 不允许 exact type 之间来回覆盖。
- 最大有效更新次数不超过 eligible declarations 数量。
- 若一轮无变化则停止。

不建议实现 whole-module fixed-point。

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

#### 用例 E：true dynamic fail-closed

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
