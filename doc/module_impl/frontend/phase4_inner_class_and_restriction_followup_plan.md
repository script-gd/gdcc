# Phase 4 后续整改计划：Inner Class 建模与 ResolveRestriction

> 本文档用于承接 `scope_architecture_refactor_plan.md` 在 Phase 4 审阅后的后续整改任务，聚焦两个问题：
> 1. inner class 的词法 type-meta 可见性与 value/function 隔离；
> 2. static / instance context 下的无 base 名字解析限制。
>
> 本文档的一个额外目标，是把“被 restriction 排除的当前层绑定是否继续构成 shadowing”这一点先对齐 Godot 官方实现，再反推 GDCC 的协议设计。

## 文档状态

- 状态：已完成（Step A-J 已实施）
- 更新时间：2026-03-07
- 关联文档：
  - `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
  - `doc/module_impl/frontend/frontend_implementation_plan.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
- 主要涉及代码：
  - `src/main/java/dev/superice/gdcc/scope/ResolveRestriction.java`
  - `src/main/java/dev/superice/gdcc/scope/ScopeLookupStatus.java`
  - `src/main/java/dev/superice/gdcc/scope/ScopeLookupResult.java`
  - `src/main/java/dev/superice/gdcc/scope/Scope.java`
  - `src/main/java/dev/superice/gdcc/exception/ScopeLookupException.java`
  - `src/main/java/dev/superice/gdcc/frontend/scope/AbstractFrontendScope.java`
  - `src/main/java/dev/superice/gdcc/frontend/scope/ClassScope.java`
  - `src/main/java/dev/superice/gdcc/frontend/scope/CallableScope.java`
  - `src/main/java/dev/superice/gdcc/frontend/scope/BlockScope.java`

---

## 1. 本次整改的明确决议

### 1.1 Inner class 方案

本轮先按以下方案推进：

- `type-meta` namespace：inner class 继续沿完整 lexical parent 链查找；
- `value/function` namespace：若 `ClassScope` 的 parent 也是 `ClassScope`，则在向外递归时跳过**连续的** `ClassScope ancestor`；
- 因而 inner class body：
  - 仍能看到 outer class 提供的 inner class / class enum / 其他 type-meta；
  - 但不会无条件继承 outer class 的 property / const / function 无 base 可见性。

### 1.2 Restriction 方案

本轮先按以下语义冻结：

- 直接把 `ResolveRestriction` 引入 `Scope` 接口方法签名；
- `static context` 下允许无 base 解析命中：
  - class const
  - static property
  - static method
- `instance context` 下允许无 base 解析命中：
  - class const
  - instance property / instance method
  - static property / static method
- `type-meta` 第一轮采用“统一签名 + 显式 always-allowed”策略：
  - `resolveTypeMeta(..., restriction)` 继续独立按 lexical type namespace 解析
  - 对当前 `ScopeTypeMetaKind` 集合只允许返回 `FOUND_ALLOWED` / `NOT_FOUND`
  - 当前 `type-meta` lookup 不应产生 `FOUND_BLOCKED`
  - `TYPE_META` 的后续合法性由 binder/static access / constructor / `load_static` 消费阶段负责，而不是由 `resolveTypeMeta(...)` 本身负责

---

## 2. Godot 官方实现对 shadowing 的结论

## 2.1 结论摘要

对“被 restriction 排除的当前层绑定是否继续构成 shadowing”这一点，Godot 当前实现给出的答案是：

> **会。**
>
> 如果标识符已经在 local/member 阶段解析到了当前层或当前类成员，即使它随后因为 `static_context` 被判定为非法访问，Godot 也会直接报错，而不会继续退回去尝试外层/global 的同名绑定。

这意味着：

- `restriction` 不能简单建模为“当前层不合法就当作 miss，继续查 parent”；
- 否则就会偏离 Godot 当前 analyzer 的 shadowing 语义。

## 2.2 事实源（Godot 官方仓库）

本结论基于 2026-03-07 查阅 Godot 官方仓库 `godotengine/godot` 的 `modules/gdscript/gdscript_analyzer.cpp`。

### 2.2.1 `reduce_identifier(...)`：先 local/member，后 global

Godot 在 `reduce_identifier(...)` 中先解析：

1. local / parameter / local constant / local bind
2. member lookup（通过 `reduce_identifier_from_base(...)`）
3. 只有当前两步都没命中时，才进入 global/builtin/class/singleton 路径

参考：
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4363-L4524`

### 2.2.2 `static_context` 检查发生在“已命中”之后

在 `reduce_identifier(...)` 中，Godot 先得到 `found_source = true`，然后才判断：

- `source_is_instance_variable`
- `source_is_instance_function`
- `source_is_signal`

若当前是 `static_context`，就直接 `push_error(...)`，但不会把这个命中改写成 miss，也不会继续尝试 global 名字。

参考：
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4458-L4484`
- 同一函数随后直接 `return`，不会再进入 global lookup：
  - `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4523-L4549`

### 2.2.3 member lookup 一旦命中当前类成员，也不会继续向 global 退回

`reduce_identifier_from_base(...)` 对脚本类成员的解析是：

- 找到 `CONSTANT` / `ENUM_VALUE` / `ENUM` / `VARIABLE` / `SIGNAL` / `FUNCTION` / `CLASS` 后直接 `return`
- 并不会在“找到了但当前上下文不合法”时改为继续 global lookup

参考：
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L4160-L4251`

## 2.3 对 GDCC 的直接约束

若 GDCC 要与 Godot 当前实现保持一致，则：

- 当前层绑定若因 restriction 被判定为“不可合法消费”，它**仍然构成 shadowing**；
- lookup 协议必须能表达至少三种状态：
  1. `FOUND_ALLOWED`
  2. `FOUND_BLOCKED`
  3. `NOT_FOUND`

仅靠当前的返回形状：

- `@Nullable ScopeValue`
- `List<? extends FunctionDef>`
- `@Nullable ScopeTypeMeta`

不足以同时表达 `FOUND_BLOCKED` 与 `NOT_FOUND` 的区别。

---

## 3. Godot 对 inner class / outer class 可见性的启发

## 3.1 官方实现现状

Godot 当前在 `get_class_node_current_scope_classes(...)` 中，会把“当前类作用域相关的 class chain”组织为：

1. 当前类
2. base type
3. outer class

并明确写了：

- `Prioritize node base type over its outer class`

参考：
- `https://github.com/godotengine/godot/blob/220b0b2f74d8e089481b140c42a42992a76dd6fc/modules/gdscript/gdscript_analyzer.cpp#L320-L344`

这说明：

- Godot 当前并不是“inner class 绝不看 outer class 成员”；
- 它是把 outer class 纳入当前 class-scope 相关链，只是顺位晚于 base type。

## 3.2 对本轮 GDCC 方案的含义

本轮 GDCC 既定方案“跳过连续 `ClassScope ancestor` 的 value/function lookup”与 Godot 当前实现**并不完全一致**。

这是一条**有意识的设计分歧**，它的优点是：

- 能在不引入双 parent / scoped view 的前提下，快速满足“inner class 继承 outer type-meta，但不继承 outer value/function”这一目标；

但代价是：

- 它与 Godot 当前 outer-class member visibility 语义并不完全对齐；
- 后续如果 GDCC 想进一步靠拢 Godot，需要重新评估是否引入更细粒度的 scoped view / namespace-specific parent。

因此，本轮应把该差异明确写入风险项，而不是假装已经做到 Godot parity。

---

## 4. 具体实施步骤清单

## 4.1 Step A：先补协议与术语（已完成）

1. 新增 `src/main/java/dev/superice/gdcc/scope/ResolveRestriction.java`
2. 在注释中冻结三条语义：
   - restriction 参与 lexical lookup
   - `type-meta` 第一版采用统一签名但显式 always-allowed；对当前 kinds 只允许 `FOUND_ALLOWED` / `NOT_FOUND`
   - restriction 不是 diagnostics，本身只表达“当前上下文允许哪些成员类别停下”
3. 建议至少提供：
   - `ResolveRestriction.unrestricted()`
   - `ResolveRestriction.instanceContext()`
   - `ResolveRestriction.staticContext()`

## 4.2 Step B：升级 `Scope` 接口签名（已完成）

1. 修改 `src/main/java/dev/superice/gdcc/scope/Scope.java`：
   - `resolveValueHere(name, restriction)`
   - `resolveFunctionsHere(name, restriction)`
   - `resolveTypeMetaHere(name, restriction)`
   - `resolveValue(name, restriction)`
   - `resolveFunctions(name, restriction)`
   - `resolveTypeMeta(name, restriction)`
2. 为兼容现有调用方，保留无 restriction 的桥接默认方法：
   - `resolveValue(name)` -> `resolveValue(name, ResolveRestriction.unrestricted())`
   - `resolveFunctions(name)` -> `resolveFunctions(name, ResolveRestriction.unrestricted())`
   - `resolveTypeMeta(name)` -> `resolveTypeMeta(name, ResolveRestriction.unrestricted())`
3. 更新接口注释：
   - 删除或改写“`Scope` 只是 lexical skeleton，不是 binder-ready architecture”一类表述；
   - 改为：`Scope` 现在是“带最小上下文限制能力的 lexical binding protocol”。

## 4.3 Step C：补齐“blocked hit”表达能力（已完成）

为保持 Godot shadowing 语义，仅新增 restriction 参数还不够，必须让协议能区分：

- `FOUND_ALLOWED`
- `FOUND_BLOCKED`
- `NOT_FOUND`

建议从下面两个方向选一个落地：（已选C1）

### 方案 C1：升级 `Scope` 返回协议（推荐）

新增结果类型，例如：

- `ScopeValueLookupResult`
- `ScopeFunctionLookupResult`
- `ScopeTypeMetaLookupResult`

优点：

- 语义最清晰；
- 可以在 scope 层就准确表达 Godot 的 “illegal but shadowing” 行为。

缺点：

- 改动面较大，会波及所有 `Scope` 实现与测试。

## 4.4 Step D：改造 `ClassRegistry`（已完成）

1. 让 `ClassRegistry` 实现新的 restriction-aware 签名；
2. 第一轮保持 global root 基本不受 restriction 影响：
   - singleton / global enum / utility / global type-meta 继续按既有规则工作；
3. 明确 restriction 的主要作用域是 frontend class/member 层，而不是 global namespace。

## 4.5 Step E：改造 `AbstractFrontendScope`（已完成）

1. 适配新的抽象方法签名；
2. `resolveTypeMetaHere(name, restriction)` 第一轮采用显式 always-allowed 契约：
   - 继续接受 `restriction` 以统一 `Scope` 三个 namespace 的调用形状
   - 但对当前 `type-meta` kinds 只允许返回 `FOUND_ALLOWED` / `NOT_FOUND`，不产生 `FOUND_BLOCKED`；
3. 在注释中写清：
   - base class 只管理 parent + local type-meta table；
   - value/function 的 restriction 语义由具体子类决定。

## 4.6 Step F：改造 `ClassScope`（已完成）

这是本轮最核心的代码改动点。

### F1. 改造当前层 value/function lookup

- `resolveValueHere(name, restriction)`：
  - direct property / const 命中后，按 `staticMember` 与 restriction 计算状态；
  - inherited property 命中后，同样要计算状态；
- `resolveFunctionsHere(name, restriction)`：
  - direct overload set 先过滤 instance/static；
  - 若 direct 层存在同名 overload 但过滤后为空，则应返回 `FOUND_BLOCKED`，而不是简单当作 miss；
  - inherited overload set 同理。

### F2. 改造 parent 递归行为

对 `value/function`：

- 若当前 `ClassScope` miss，且 parent 也是 `ClassScope`，则跳过**连续的** `ClassScope ancestor`；
- 然后从第一个非 `ClassScope` ancestor 继续递归。

对 `type-meta`：

- 保持完整 lexical parent 链，不跳过 outer class。

### F3. 注释中明确本轮与 Godot 的差异

- `type-meta` 继承 outer lexical chain；
- `value/function` 刻意隔离 outer class；
- 这是 GDCC 当前的工程决策，不是 Godot 当前实现的 1:1 平移。

## 4.7 Step G：改造 `CallableScope`（已完成）

1. 适配新的 restriction-aware 签名；
2. parameter / capture 第一轮仍可视为不受 static-vs-instance member restriction 影响；
3. 继续把 `self` 的建模与 diagnostics 保留给后续 binder；
4. 但文档表述要改成：
   - `CallableScope` 现在参与 restriction-aware lookup，
   - 只是不负责 `SelfExpression` 的语义和报错文本。

## 4.8 Step H：改造 `BlockScope`（已完成）

1. 适配新的 restriction-aware 签名；
2. block local / local const 第一轮仍不受 class-member restriction 影响；
3. 允许 restriction 继续沿 parent 链上传递。

## 4.9 Step I：补测试（已完成）

### I1. 协议测试

更新并扩展：

- `ScopeProtocolTest`
- `ClassRegistryScopeTest`
- `ScopeTypeMetaChainTest`

至少覆盖：

- unrestricted 兼容旧行为；
- restriction-aware lookup 的 `FOUND_ALLOWED / FOUND_BLOCKED / NOT_FOUND` 语义；
- function lookup 的“最近过滤后非空层优先”；
- `type-meta` 在 `unrestricted` / `staticContext` / `instanceContext` 下维持 restriction-invariant 行为；
- 当前 `type-meta` lookup 只允许 `FOUND_ALLOWED / NOT_FOUND`，不产生 `FOUND_BLOCKED`。

### I2. Inner class 测试

建议新增：

- `FrontendInnerClassScopeIsolationTest`
- `FrontendNestedInnerClassScopeIsolationTest`

覆盖：

- outer type-meta 可见；
- outer property / const / function 不可见；
- 多层 inner class 不泄露任意 outer class value/function。

### I3. Static / instance restriction 测试

建议新增：

- `FrontendStaticContextValueRestrictionTest`
- `FrontendStaticContextFunctionRestrictionTest`
- `FrontendStaticContextShadowingTest`

覆盖：

- static context 允许 class const / static prop / static method；
- static context 禁止 instance prop / instance method；
- instance context 允许 static prop / static method；
- **当前层存在被 restriction 阻止的绑定时，不继续退回 outer/global**（Godot parity 的关键测试）。

## 4.10 Step J：同步文档（已完成）

至少同步以下文档：

1. `doc/module_impl/frontend/scope_architecture_refactor_plan.md`
   - 改写 “lexical skeleton only” 相关表述；
   - 明确 `ResolveRestriction` 已进入 `Scope` 协议；
   - 明确 inner class 的新规则与 Godot 差异；
   - 明确 static/instance context 下无 base 解析的允许集合。
2. `doc/module_impl/frontend/frontend_implementation_plan.md`
   - 把 binder 的无 base 解析流程更新为：先构造 restriction，再调用 scope；
   - 写清当前类成员的合法性由 restriction 决定。
3. `doc/analysis/frontend_semantic_analyzer_research_report.md`
   - 将 Godot 的 static-context shadowing 事实补进结论；
   - 把该事实与 GDCC 的协议升级方案对应起来。

---

## 4.11 实际验收结果（2026-03-07）

- 总结果：通过。
- Step A-C 已完成：
  - 新增 `ResolveRestriction`、`ScopeLookupStatus`、`ScopeLookupResult`。
  - `Scope` 协议升级为 restriction-aware + tri-state lookup，明确区分 `FOUND_ALLOWED` / `FOUND_BLOCKED` / `NOT_FOUND`。
- Step D-H 已完成：
  - `ClassRegistry`、`AbstractFrontendScope`、`ClassScope`、`CallableScope`、`BlockScope` 全部切换到新的协议签名。
  - `ClassScope` 对 value/function lookup 在 lexical miss 时跳过连续 `ClassScope ancestor`，而 type-meta 继续沿完整 lexical parent 链查找。
  - `ClassScope` 已把 static/instance context 下的 class const / property / method 过滤下沉到 scope lookup，并保持 blocked hit 继续构成 shadowing。
- Step I 已完成：新增并通过以下 targeted tests：
  - `src/test/java/dev/superice/gdcc/scope/ScopeProtocolTest.java`
  - `src/test/java/dev/superice/gdcc/scope/ClassRegistryScopeTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/scope/FrontendInnerClassScopeIsolationTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/scope/FrontendNestedInnerClassScopeIsolationTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/scope/FrontendStaticContextValueRestrictionTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/scope/FrontendStaticContextFunctionRestrictionTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/scope/FrontendStaticContextShadowingTest.java`
- Step J 已完成：
  - 已同步 `scope_architecture_refactor_plan.md`、`frontend_implementation_plan.md`、`frontend_semantic_analyzer_research_report.md` 的术语与语义边界。
- 已执行命令：

```powershell
.\gradlew.bat test \
  --tests ScopeProtocolTest \
  --tests ClassRegistryScopeTest \
  --tests ClassRegistryTypeMetaTest \
  --tests ClassRegistryTest \
  --tests ClassRegistryGdccTest \
  --tests ScopeChainTest \
  --tests ScopeTypeMetaChainTest \
  --tests ScopeCaptureShapeTest \
  --tests ClassScopeResolutionTest \
  --tests FrontendInnerClassScopeIsolationTest \
  --tests FrontendNestedInnerClassScopeIsolationTest \
  --tests FrontendStaticContextValueRestrictionTest \
  --tests FrontendStaticContextFunctionRestrictionTest \
  --tests FrontendStaticContextShadowingTest \
  --no-daemon --info --console=plain
```

## 5. 风险与挑战

## 5.1 最大协议风险：仅加 restriction 参数但不补 `FOUND_BLOCKED` 状态会做错

如果接口仍然只返回：

- `ScopeValue?`
- `List<FunctionDef>`
- `ScopeTypeMeta?`

那么“当前层被 restriction 阻止”与“当前层根本不存在”无法区分。

结果将导致两种错误之一：

1. 把 blocked 当 miss，错误地继续 fallback 到 outer/global；
2. 把 blocked 当 allowed，无法表达 static-context 非法访问。

这会直接偏离 Godot 当前行为。

## 5.2 Inner class 方案与 Godot 当前 outer-class member 可见性并不完全一致

Godot 当前会把 outer class 纳入 current-scope class chain，而且顺位在 base type 之后。

而本轮 GDCC 方案选择“跳过连续 `ClassScope ancestor` 的 value/function lookup”，是一个更保守、也更易工程落地的策略，但它不是 Godot 当前行为的直接镜像。

因此：

- 这必须作为**已知语义差异**被显式记录；
- 后续若要追求更高 Godot parity，需要重新评估是否引入 namespace-specific parent / scoped view。

## 5.3 Function overload 的 restriction 语义比 value 更容易实现错误

function lookup 当前遵循“最近非空层优先”。

引入 restriction 后，正确语义应变成：

- 最近 **过滤后非空** 的 allowed overload set 优先；
- 但如果当前层存在同名 overload，只是全部被 restriction 阻止，则仍应视为 `FOUND_BLOCKED`，不允许继续向外退回。

这点若实现错，会让 static-context 下的调用语义与 Godot 出现系统性偏差。

## 5.4 `self` / signal 仍未纳入本轮完整建模

Godot 的 static-context 限制同时覆盖：

- instance variable
- instance function
- signal

但 GDCC 当前 frontend scope 里：

- `self` 还不是显式 binding
- signal 也还不是现成的 `ScopeValue` / `FunctionDef`

因此本轮 restriction 第一版只能先覆盖：

- const
- property
- method

这意味着：

- 协议升级后仍不能宣称“Phase 4 的静态上下文语义已完全对齐 Godot”；
- `SelfExpression` / signal 仍需在 Phase 7 binder 中补齐。

## 5.5 文档清理不彻底会导致后续 Phase 7 再次认知漂移

如果仍保留“`Scope` 只负责名字能否找到，不负责合法性”之类表述，而代码又已经把 restriction 引入 `Scope` 协议，那么：

- 实现者会误判哪些职责该放在 binder，哪些该放在 scope；
- 后续 Phase 7 很容易再次出现“双重过滤”或“漏过滤”。

因此，本轮必须把相关旧表述系统性替换掉，而不是只改一两处。

---

## 6. 推荐的实施顺序

1. 先补 `ResolveRestriction` 与 `Scope` 协议升级设计；
2. 先决定 `FOUND_BLOCKED` 的协议表达方式；
3. 再改 `ClassRegistry` / `AbstractFrontendScope` / `ClassScope` / `CallableScope` / `BlockScope`；
4. 先补 inner class 隔离与 static-context shadowing 的 targeted tests；
5. 最后再统一清理 `scope_architecture_refactor_plan.md` / `frontend_implementation_plan.md` / `frontend_semantic_analyzer_research_report.md` 中的旧表述。

一句话总结：

> 本轮整改的难点不在“加一个 boolean restriction”，而在“如何让 restriction 进入 `Scope` 协议后，仍然保持 Godot 当前的 shadowing 语义，并同时允许 GDCC 在 inner class 上保留一条有意识的工程化差异”。
