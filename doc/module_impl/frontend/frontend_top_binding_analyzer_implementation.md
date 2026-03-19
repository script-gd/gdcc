# FrontendTopBindingAnalyzer 实现说明

> 本文档作为 `FrontendTopBindingAnalyzer` 的长期事实源，定义当前职责边界、`symbolBindings()` 发布合同、命名空间分流规则、遍历与恢复语义，以及后续工程必须遵守的接线约束。本文档替代旧的实施计划与进度记录，不再保留阶段清单、验收流水账或已完成任务日志。

## 文档状态

- 状态：事实源维护中（`symbolBindings()` 重建、builtin / global enum / class-like top-level `TYPE_META` 规则、value-position bare callable / bare `TYPE_META` ordinary-value misuse 合同、root-level skipped-subtree 恢复合同、usage-agnostic binding 模型与核心单元测试已落地）
- 更新时间：2026-03-18
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/**`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/dev/superice/gdcc/frontend/scope/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/test/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `frontend_variable_analyzer_implementation.md`
  - `frontend_visible_value_resolver_implementation.md`
  - `scope_analyzer_implementation.md`
  - `scope_architecture_refactor_plan.md`
  - `scope_type_resolver_implementation.md`
  - `diagnostic_manager.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
- 明确非目标：
  - 不在这里发布 `resolvedMembers()`、`resolvedCalls()` 或 `expressionTypes()`
  - 不在这里解析显式 receiver 尾部成员或调用步骤
  - 不在这里建模 read / write / call / assignable / lvalue 语义
  - 不在这里实现 parameter default、lambda、`for`、`match`、block-local `const` 的正式 binding
  - 不在这里扩展 shared `Scope` 协议
  - 不在这里处理 class constant binding；该能力仍延后到 MVP 之后

---

## 1. 当前职责与集成位置

### 1.1 主链路位置

当前 `FrontendSemanticAnalyzer` 的稳定顺序是：

1. `FrontendClassSkeletonBuilder.build(...)`
2. `analysisData.updateModuleSkeleton(...)`
3. `analysisData.updateDiagnostics(...)`
4. `FrontendScopeAnalyzer.analyze(...)`
5. `analysisData.updateDiagnostics(...)`
6. `FrontendVariableAnalyzer.analyze(...)`
7. `analysisData.updateDiagnostics(...)`
8. `FrontendTopBindingAnalyzer.analyze(...)`
9. `analysisData.updateDiagnostics(...)`
10. `FrontendChainBindingAnalyzer.analyze(...)`
11. `analysisData.updateDiagnostics(...)`

这意味着：

- `FrontendTopBindingAnalyzer` 只运行在 skeleton、scope graph 与 supported callable-local inventory 已发布之后
- 它消费 `moduleSkeleton`、`diagnostics`、`scopesByAst()` 与 `FrontendVisibleValueResolver`
- 它不会重建 scope graph，也不会修改 scope 对象中已经发布的 declaration inventory

### 1.2 当前职责

`FrontendTopBindingAnalyzer` 当前冻结负责：

- 从 `FrontendModuleSkeleton.sourceClassRelations()` 中 accepted 的 source file 出发遍历 AST
- 每次运行都从零重建一张新的 `FrontendAstSideTable<FrontendBinding>`
- 为 supported executable subtree 中的 bare value identifier 发布 symbol category binding
- 为 bare callee identifier 通过 function namespace 发布 symbol category binding
- 为 qualified static access / call chain 的最外层 chain head 发布 ordinary value 或 `TYPE_META` binding
- 为 `SelfExpression` 与 `LiteralExpression` 直接发布 binding
- 对 unsupported / skipped subtree 发出显式 `sema.unsupported_binding_subtree`
- 将 blocked / unknown / shadowing / unsupported-source 情形翻译为 `sema.binding`

### 1.3 当前不负责

`FrontendTopBindingAnalyzer` 明确不负责：

- 解析 `a.xxx` 中 `xxx` 的属性、signal、method 或 static member
- 解析 `a.foo()`、`ClassName.build()` 的尾部调用步骤本身
- 发布成员解析结果、调用解析结果或表达式类型
- 判定一个 use-site 是读取、写入还是调用
- 给 assignment 左值、bare callee、value use-site 建立不同的数据模型
- 放宽 `FrontendVisibleValueResolver` 已经封口的 deferred / unsupported 语义域

---

## 2. 输出合同与 binding 模型

### 2.1 当前唯一正式产物

当前 analyzer 唯一正式发布的 side-table 是：

- `symbolBindings()`

稳定合同为：

- 每次运行都重新构建整张表，然后通过 `analysisData.updateSymbolBindings(...)` 一次性发布
- `resolvedMembers()`、`resolvedCalls()`、`expressionTypes()` 不在这里写入
- analyzer 自己不会保留跨 run 的增量状态

### 2.2 `FrontendBinding` 的当前模型

`FrontendBinding` 当前只承载三类事实：

- `symbolName`
- `kind`
- `declarationSite`

这里必须冻结一条当前实现事实：

- `FrontendBinding` 当前是 usage-agnostic 模型
- 它只表达“这个 AST use-site 绑定到了哪一类符号”
- 它不表达该 use-site 是 read / write / call / assignable

因此以下 use-site 当前都可能进入同一张 `symbolBindings()`：

- 普通 value position 读取
- bare callee
- assignment 左值链头
- 显式 receiver 链头

后续如果 frontend 需要记录完整用法，必须扩展 `FrontendBinding` 模型本身，而不能依赖当前 `FrontendBindingKind` 反推 usage。

### 2.3 当前正式支持的 binding kind

当前 analyzer 的正式支持面至少包括：

- `SELF`
- `LITERAL`
- `LOCAL_VAR`
- `PARAMETER`
- `PROPERTY`
- `SIGNAL`
- `SINGLETON`
- `GLOBAL_ENUM`
- `TYPE_META`
- `METHOD`
- `STATIC_METHOD`
- `UTILITY_FUNCTION`
- `UNKNOWN`

需要明确：

- `UTILITY_FUNCTION` 表示 global utility function 对应的 function-like symbol category，可被 bare callee 与 value use-site 共同消费
- `METHOD` 表示实例方法 overload set 的 function-like symbol category，可被 bare callee 与 value use-site 共同消费
- `STATIC_METHOD` 表示静态方法 overload set 的 function-like symbol category，可被 bare callee 与 value use-site 共同消费
- `CONSTANT` 仍保留在枚举中，但 class-level `const` 当前不属于本 analyzer 的正式支持面
- `CAPTURE` 在当前支持面下通常不会正式产出，因为 lambda capture 仍 deferred

### 2.4 当前明确不发布的事实

以下结果当前都不属于本 analyzer 的产物：

- `resolvedMembers()` 中的任何成员绑定
- `resolvedCalls()` 中的任何调用绑定
- `expressionTypes()` 中的任何表达式类型
- 显式 receiver 尾部成员的 binding，例如 `player.hp` 中的 `hp`
- 显式 receiver 尾部调用步骤的 binding，例如 `player.move()` 中的 `move`
- subscript operation 本身的 binding，例如 `arr[i]` 这一步索引操作

---

## 3. 命名空间分流规则

### 3.1 value position

对 bare value-position `IdentifierExpression`：

- 必须通过 `FrontendVisibleValueResolver` 做解析
- 当前 request 固定使用 `FrontendVisibleValueDomain.EXECUTABLE_BODY`
- ordinary local initializer 右侧 use-site 也必须复用这条路径

`FrontendVisibleValueResolution` 的消费合同固定为：

- `FOUND_ALLOWED`
  - 发布对应的 value binding
- `FOUND_BLOCKED`
  - 仍发布对应 binding
  - 同时发 `sema.binding`
- `NOT_FOUND`
  - 继续按固定 fallback 顺序竞争其他命名空间：
    - `resolveFunctions(...)`
    - `resolveTypeMeta(...)` ordinary-value misuse
    - generic unknown value miss
- `DEFERRED_UNSUPPORTED`
  - 不发布 binding
  - 发 `sema.unsupported_binding_subtree`

当 value namespace 返回 `NOT_FOUND` 时，value-position 的最终收口顺序已经冻结为：

- ordinary value winner
- function namespace winner（发布 `METHOD` / `STATIC_METHOD` / `UTILITY_FUNCTION`）
- supported `TYPE_META` ordinary-value misuse
- generic unknown value miss

这意味着：

- bare function name 现在是合法的 ordinary value use-site；top binding 负责发布 function-like symbol category，而不是误退化成 unknown value
- bare `TYPE_META` ordinary-value misuse 不再退化成 generic unknown miss；top binding 必须发布真实 `TYPE_META`，并由自己发出首条精确 `sema.binding`
- value namespace 一旦已有 allowed / blocked winner，function/type-meta namespace 都不得反抢，blocked local winner 仍继续遮蔽 outer/global function

当前 `ScopeValueKind -> FrontendBindingKind` 映射为：

- `LOCAL` -> `LOCAL_VAR`
- `PARAMETER` -> `PARAMETER`
- `CAPTURE` -> `CAPTURE`
- `PROPERTY` -> `PROPERTY`
- `SIGNAL` -> `SIGNAL`
- `CONSTANT` -> `CONSTANT`
- `SINGLETON` -> `SINGLETON`
- `GLOBAL_ENUM` -> `GLOBAL_ENUM`
- `TYPE_META` -> `TYPE_META`

### 3.2 top-level `TYPE_META` chain head

对 qualified static access / call chain 最外层的 bare `IdentifierExpression`：

- 必须先按 ordinary value use-site 调用 `FrontendVisibleValueResolver`
- 只有在 value resolution 返回 `NOT_FOUND` 时，才允许尝试 `Scope.resolveTypeMeta(...)`

这是当前已经冻结的优先级规则：

- ordinary value namespace 优先于 top-level `TYPE_META`
- analyzer 不能先消费 `TYPE_META` 再回退到 value
- deferred / unsupported value 结果也不能被伪装成普通 type-meta miss

若 value 结果为：

- `FOUND_ALLOWED`
  - ordinary value binding 直接成为最终结果
- `FOUND_BLOCKED`
  - ordinary value binding 仍成为最终结果
  - 同时发 blocked diagnostic
- `DEFERRED_UNSUPPORTED`
  - 直接发 deferred / unsupported diagnostic
  - 不得继续尝试 `TYPE_META`

唯一的当前例外是 global enum chain head：

- 若 ordinary value resolution 的 winning value 是 `GLOBAL_ENUM`
- 且 `Scope.resolveTypeMeta(...)` 同名命中受支持的 global enum type-meta
- analyzer 必须优先发布 `TYPE_META`
- 这样 `EnumType.VALUE` 才能进入后续 static-load route，而不会被 ordinary value binding 永久吃掉

若 ordinary value 赢了，且同名受支持 `TYPE_META` 同时可见：

- 只有当 winning value 属于 `LOCAL` / `PARAMETER` / `CAPTURE`
  - 才额外发 local-like shadowing diagnostic
- property / signal / singleton / global enum 与 `TYPE_META` 重名
  - 当前不额外制造 shadowing 诊断

当前 top-level `TYPE_META` 只支持以下来源：

- `ClassRegistry` 已注册的 class-like 类型
- `ClassRegistry` 已注册的 builtin static receiver
- `ClassRegistry` 已注册的 global enum
- 当前 lexical scope 可见的 inner class

实现上，这对应以下约束：

- `ScopeTypeMeta.kind()` 为 `GDCC_CLASS` / `ENGINE_CLASS` / `BUILTIN` 时
  - `ScopeTypeMeta.pseudoType()` 必须为 `false`
- `ScopeTypeMeta.kind()` 为 `GLOBAL_ENUM` 时
  - `declaration()` 必须非空

当前明确不支持的 type-meta 来源包括：

- preload alias
- const-based type alias
- scope-local pseudo type-meta / local enum alias
- 其他未来通过 `defineTypeMeta(...)` 接入、但当前未冻结消费合同的来源

### 3.3 bare callee

对 `foo()` 这类 bare callee：

- 必须从当前 scope 调用 `resolveFunctions(name, restriction)`
- 不能退回 `resolveValue(...)`

返回 overload set 后的分类规则固定为：

- 全部是实例方法 -> `METHOD`
- 全部是静态方法 -> `STATIC_METHOD`
- 全部是 utility function -> `UTILITY_FUNCTION`

其他情形的处理合同为：

- `FOUND_BLOCKED`
  - 仍发布 `METHOD` 或 `STATIC_METHOD`
  - 同时发 `sema.binding`
- `NOT_FOUND`
  - 发布 `UNKNOWN`
  - 同时发 `sema.binding`
- surviving overload set 混有 utility function 与 member method
  - fail-closed
  - 发 `sema.binding`
  - 不发布误导性的 function kind
- surviving overload set 混有 static 与 non-static
  - fail-closed
  - 发 `sema.binding`
  - 不发布误导性的 function kind

需要额外冻结一条 use-site 规则：

- function namespace winner 不再只服务 bare callee
- 对 bare value-position `IdentifierExpression`，只要 ordinary value namespace 返回 `NOT_FOUND`，top binding 也允许发布 `METHOD` / `STATIC_METHOD` / `UTILITY_FUNCTION`
- 这样 expression typing、chain head receiver 与后续 `Callable` materialization 都能消费同一份 published symbol category，而不需要扩张 `FrontendBinding` 数据模型

### 3.4 `self`

`self` 不走 `Scope.resolveValue(...)`，由 analyzer 直接发布：

- instance context -> `SELF`
- static context -> 仍发布 `SELF`，同时发 `sema.binding`

这里必须保持：

- `self` 不是 unknown name
- static context 下的问题是“上下文非法”，不是“名字不存在”

### 3.5 literal

`LiteralExpression` 由 analyzer 直接发布：

- `kind = LITERAL`
- `symbolName = literal.sourceText()`
- `declarationSite = null`

---

## 4. 遍历范围与支持面

### 4.1 当前支持的 executable subtree

当前 analyzer 正式支持以下 executable subtree：

- function body
- constructor body
- 普通嵌套 `Block`
- `if` body
- `elif` body
- `else` body
- `while` body
- ordinary local `var` initializer expression subtree

需要明确：

- class body 仍会继续遍历 callable / inner class 声明
- 但 class body 本身不是 value-binding executable region
- ordinary local initializer 已经是正式支持面，不得再视为 deferred 子树

### 4.2 当前显式封口的 subtree

以下位置当前都必须显式封口，不能伪装成正常 binding miss：

- parameter default subtree
- lambda subtree
- `for` subtree
- `match` subtree
- block-local `const` initializer subtree
- 任何缺少稳定 `scopesByAst()` 记录的 skipped subtree

### 4.3 显式 receiver 的遍历规则

当前 analyzer 对显式 receiver 只解析链头及其嵌套参数表达式，不解析尾部 segment。

规则固定为：

- `AttributeExpression`
  - 继续访问 base expression
  - 对 attribute-call / attribute-subscript step 的 argument expression 继续递归
  - 不为 member step 写 binding
- `SubscriptExpression`
  - 继续访问 base expression
  - 继续访问每个 index argument expression
  - 不为 subscript operation 本身写 binding
- `CallExpression`
  - callee 若是 bare `IdentifierExpression`，按 bare callee 处理
  - callee 若是 `AttributeExpression`，继续分析链头与其中 step arguments
  - 不为 attribute call step 本身写 binding

当前行为因此固定为：

- `player.hp`
  - 只绑定 `player`
- `player.move(i + 1)`
  - 绑定 `player`、`i`、`1`
  - 不绑定 `move`
- `ClassName.build()`
  - 链头 `ClassName` 可绑定为 ordinary value 或 `TYPE_META`
  - 不绑定 `build`
- `arr[i + 1]`
  - 绑定 `arr`、`i`、`1`
  - 不绑定 subscript operation

### 4.4 assignment 与通用表达式递归

当前 assignment 的处理固定为：

- 左值表达式继续进入递归
- 右值表达式继续进入递归

这是因为当前 binding 模型只发布 symbol category，不区分 read / write。

其他复杂表达式的递归合同固定为：

- generic expression traversal 必须继续向下寻找嵌套 `Expression`
- 即使中间包了一层非表达式 AST 容器，例如 `DictionaryExpression -> DictEntry`
  - 也必须继续下探，直到命中真正的嵌套表达式 use-site

---

## 5. 诊断与恢复合同

### 5.1 允许抛异常的 guard rail

只有以下情况允许 fail-fast：

- `moduleSkeleton` 尚未发布
- diagnostics boundary 尚未发布
- accepted source file 在 analyzer 启动时缺少顶层 `SourceFile -> Scope` 记录

这些都属于 framework 或共享 side-table 不变量损坏，而不是普通源码错误。

### 5.2 `sema.binding`

当前 `sema.binding` 覆盖以下语义：

- unknown value binding
- unknown bare callee binding
- blocked property / signal / method / static-context `self`
- local-like value 遮蔽受支持 top-level `TYPE_META`
- mixed utility/member overload set
- mixed static/non-static overload set
- unsupported top-level type-meta source被显式命中

### 5.3 `sema.unsupported_binding_subtree`

当前 `sema.unsupported_binding_subtree` 覆盖以下语义：

- parameter default subtree
- lambda subtree
- `for` subtree
- `match` subtree
- block-local `const` initializer subtree
- missing-scope / skipped subtree

### 5.4 root-level 恢复合同

当前 `sema.unsupported_binding_subtree` 的 root-level 恢复语义固定为：

- 明确 unsupported 的 feature boundary 优先发 root-level error
- missing-scope / skipped subtree 继续允许发 root-level warning
- 同一棵 skipped / unsupported subtree 当前只发 1 条边界诊断
- subtree 内部 use-site 不再逐个降级成 `UNKNOWN`
- subtree 内部也不再逐个单独补边界诊断

这条 root-level 合同同样适用于：

- callable body 缺 scope
- nested executable block 缺 scope
- 其他已经识别出更大 skipped root 的 published subtree 缺口

如果只能观察到“单个 use-site AST 节点自身缺 scope”，而无法恢复出更大的 skipped root：

- 允许把该 use-site 节点视为最小 skipped root
- 但依然要走 `sema.unsupported_binding_subtree`
- 不得把它降级成普通 `UNKNOWN`

### 5.5 blocked-hit 不能回退

当前 blocked-hit 处理必须保持如下合同：

- blocked binding 仍然是最终 binding 结论
- analyzer 必须保留这个 binding 并发 diagnostic
- 不得为了“找一个能用的名字”继续回退 outer/global

这条规则当前适用于：

- static context 下的实例 property
- static context 下的实例 signal
- static context 下的实例 method
- static context 下的 `self`

---

## 6. 与其他前端组件的接线约束

### 6.1 与 `FrontendVisibleValueResolver` 的约束

`FrontendTopBindingAnalyzer` 与 `FrontendVisibleValueResolver` 的稳定分工为：

- analyzer 负责语法位置分流、binding 发布与 diagnostics
- resolver 负责 bare value 名称可见性、declaration-order、自引用过滤与 deferred boundary

因此 analyzer 不得：

- 绕过 resolver 直接自行实现 ordinary local 可见性规则
- 把 resolver 的 `DEFERRED_UNSUPPORTED` 降级成普通 miss
- 在 ordinary local initializer 上另起一套“binder 特判”

### 6.2 与 `FrontendVariableAnalyzer` 的约束

当前 binding 支持面依赖 `FrontendVariableAnalyzer` 已经发布的 callable-local inventory。

后续若扩展 binding 支持域，必须同时确认：

- `FrontendVariableAnalyzer` 是否真的发布了该域所需 inventory
- `FrontendVisibleValueResolver` 是否真的允许在该域正常 lookup
- `FrontendTopBindingAnalyzer` 的 walker 是否真的进入了该域
- 对应测试是否已经补齐

### 6.3 与 shared `Scope` / type namespace 的约束

当前 analyzer 对 shared `Scope` 的消费边界固定为：

- ordinary value -> `FrontendVisibleValueResolver`
- bare callee -> `Scope.resolveFunctions(...)`
- top-level chain-head type-meta fallback -> `Scope.resolveTypeMeta(...)`

其他能力仍不在本 analyzer 的消费边界内：

- 一般 type position binding
- member namespace binding
- tail static member binding
- class constant binding

---

## 7. 示例锚点

### 7.1 当前支持的情形

```gdscript
func ping(seed):
    var answer = seed
```

- `seed` 绑定为 `PARAMETER`

```gdscript
func move():
    pass

static func build():
    pass

func ping():
    move()
    build()
    abs(-1)
```

- `move` 绑定为 `METHOD`
- `build` 绑定为 `STATIC_METHOD`
- `abs` 绑定为 `UTILITY_FUNCTION`

```gdscript
class Inner:
    static func build():
        pass

func ping(seed):
    var Inner = seed
    Inner.build()
```

- initializer 里的 `seed` 绑定为 `PARAMETER`
- 链头 `Inner` 绑定为 `LOCAL_VAR`
- 同时发出“local value 遮蔽可见 type-meta”的 `sema.binding`

```gdscript
func ping(player, i):
    player.move(i + 1)
```

- `player` 绑定
- `i` 绑定
- `1` 绑定为 `LITERAL`
- `move` 不绑定

```gdscript
func ping(arr, row, col):
    arr[row][col + 1]
```

- `arr`、`row`、`col` 都会绑定
- `1` 会绑定为 `LITERAL`
- 两次 subscript operation 本身都不绑定

```gdscript
func ping(value, matrix, row, col):
    value = matrix[row][col + 1]
```

- `value`、`matrix`、`row`、`col` 都会进入 `symbolBindings()`
- 当前不区分 `value` 是左值写入、其余名字是读取 use-site

### 7.2 当前不支持或只支持链头的情形

```gdscript
func ping(value = seed):
    pass
```

- parameter default subtree 当前 deferred

```gdscript
func ping(values):
    for item in values:
        print(item)
```

- `for` subtree 当前 deferred

```gdscript
func ping(value):
    var f = func():
        return value
```

- lambda subtree 当前 deferred

```gdscript
func ping():
    const answer = seed
```

- block-local `const` initializer subtree 当前 deferred

```gdscript
func ping(player):
    print(player.hp)
```

- `player` 会绑定
- `hp` 当前不绑定

```gdscript
const EnemyType = preload("res://enemy.gd")

func ping():
    EnemyType.build()
```

- `EnemyType` 当前不能作为受支持的 top-level `TYPE_META` 来源

---

## 8. 测试锚点

后续若修改本 analyzer，至少要继续锚定以下事实：

- ordinary value binding 覆盖 local / parameter / property / signal / singleton / global enum
- bare callee binding 覆盖 method / static method / utility function / blocked / unknown / mixed-overload-set
- top-level chain head 的 ordinary-value-first 规则与 local-like shadowing diagnostic
- `self` 与 `LiteralExpression` 的直接发布合同
- explicit receiver 只绑定链头与 step/index arguments，不绑定尾部 segment
- assignment 左右两侧都会递归进入 binding
- ordinary local initializer 继续属于支持面
- parameter default / lambda / `for` / `match` / block-local `const` 当前继续走 root-level unsupported error
- skipped executable subtree 的 warning 当前按 root-level 发布，而不是静默跳过或逐 use-site 降级
