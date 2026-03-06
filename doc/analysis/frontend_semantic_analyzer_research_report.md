# GDCC 前端语义分析器调研报告

- 日期：2026-03-06
- 作者：Codex
- 目标：基于 `gdcc` 当前代码、`doc/` 中的实现文档、本地 `gdparser` 最新 AST 现状，以及 GitHub 上 `godotengine/godot` / `godotengine/godot-docs` 的最新内容，给出一份可直接指导 `frontend` 语义分析器设计与分阶段落地的调研结论。

---

## 1. 执行摘要

这次重写后的核心结论可以先压缩成 12 条：

1. **GDCC 前端仍然应该采用 Godot 同款阶段顺序**：`inheritance -> interface -> body -> lowering`。上游 `GDScriptAnalyzer` 到 2026-03-06 仍然保持 `resolve_inheritance()`、`resolve_interface()`、`resolve_body()`、`resolve_dependencies()` 的整体结构。
2. **Godot 仍然使用“语义可写 AST”**。`gdscript_parser.h` 里的节点直接挂 `datatype`、`reduced_value`、`resolved_interface`、`resolved_body`、`resolved_signature`、`default_arg_values`、`captures`、`usages` 等字段；GDCC 不能照搬，仍应采用 side-table。
3. **GDCC 当前前端落地状态没有本质变化**：仍只有 parse 服务、统一诊断、class skeleton builder，以及少量 parse/skeleton 测试；还没有真正的 body-level semantic analyzer。
4. **GDCC backend 仍然是前端的事实源**。调用语义以 `MethodCallResolver` 为准，属性语义以 `PropertyAccessResolver` 为准，索引/操作符/所有权规则以 `doc/` 与现有代码为准；front-end 不应自行创造第二套语义。
5. **“已知对象缺失 method” 与 “已知对象缺失 property” 的策略必须区分**：
   - method：当前 backend 已支持 `OBJECT_DYNAMIC`，前端不必强行先 pack 成 `Variant`。
   - property：当前 backend 已知对象缺失 property 时仍 fail-fast，前端若选择 warning + 动态回退，就必须主动 lower 成 `variant_get_named` / `variant_set_named`。
6. **`ClassRegistry.findType(...)` 仍然过于宽松，不能直接拿来做绑定结论**。它适合做类型解析辅助，不适合当成 identifier binder 的最终判定器。
7. **与旧版报告最大的变化是：`gdparser` AST 已经明显补齐。** 当前本地 `gdparser` 已有 `SelfExpression`、`GetNodeExpression`、`PreloadExpression`、`CastExpression`、`TypeTestExpression`、`AwaitExpression`、`AnnotationStatement`、`AssertStatement`、`ClassDeclaration`、`ConstructorDeclaration`、`BaseCallExpression` 等节点，且 lowering 与测试已接入。
8. **但 `gdparser` 仍不等于 Godot 原生 parser AST**。几个关键结构差异仍然存在：
   - 注解在 `gdparser` 中是独立的 `AnnotationStatement`，不是挂在目标节点上的 `annotations` 列表。
   - 类型提示是 `TypeRef`，不是 Godot `TYPE` 节点。
   - `match` 的 `PATTERN` / `MATCH_BRANCH` / `SUITE` 被简化成 `MatchSection` + `PatternBindingExpression` + `Block`。
   - 这意味着语义分析器需要自己恢复“注解归属”“模式绑定”“block/suite 语义”。
9. **新增 AST 节点并不等于新增 feature 已经可编译。** 例如 `AwaitExpression` 现在已经能进 AST，但 GDCC 当前 LIR / backend 文档并没有 coroutine 方案，因此前端要么显式报“暂不支持”，要么把它明确列为后续里程碑。
10. **前端设计重点应从“AST 还缺什么”转向“如何消费已经补齐的 AST”**。尤其是 `AnnotationStatement`、`ClassDeclaration`、`ConstructorDeclaration`、`BaseCallExpression`、`SelfExpression`、`CastExpression`、`TypeTestExpression` 这些节点，已经足够影响 semantic analyzer 的数据结构设计。
11. **最稳妥的实现路径仍然不是直接写 lowering，而是先把语义 side-table 设计完整。** 至少应有：绑定表、表达式类型表、调用决议表、成员访问表、注解归属表、作用域树、控制流合流结果表、构造器/基类调用语义表。
12. **相对于旧版报告，GDCC 第一阶段的边界应当被重新定义**：不是“AST 缺口很大，所以只能做极小 MVP”，而是“AST 主干已经足够支撑更完整的 binder/type/call/member 设计，但 backend/LIR 能力仍决定近期真正可 lower 的子集”。

---

## 2. 相对旧版报告的关键修订

本次重写最重要的不是改措辞，而是修正了几项已经变化的事实。

### 2.1 `gdparser` AST 覆盖面已不再适合被描述为“明显小于 Godot 原生 parser AST”

旧版报告中将 `SELF / PRELOAD / CAST / TYPE_TEST / AWAIT / ASSERT / ANNOTATION / CLASS(inner class)` 视为缺失节点；这在当前本地 `gdparser` 已经不成立。

截至本次调研，本地 `E:/Projects/gdparser` 已包含并接入 lowering 的节点至少有：

- `SelfExpression`
- `GetNodeExpression`
- `PreloadExpression`
- `CastExpression`
- `TypeTestExpression`
- `AwaitExpression`
- `AnnotationStatement`
- `AssertStatement`
- `ClassDeclaration`
- `ConstructorDeclaration`
- `BaseCallExpression`
- `PatternBindingExpression`
- `RegionDirectiveStatement`
- `BreakpointStatement`

而且 `CstToAstMapperTest` 已有针对这些节点的覆盖，说明它们不是“仅定义 AST record 未接线”的半成品。

### 2.2 真正需要强调的，不再是“节点有没有”，而是“结构差异怎么影响语义分析”

`gdparser` 虽然补齐了很多节点，但与 Godot 原生 parser 的建模差异依旧显著：

1. **注解归属模型不同**
   - Godot：注解挂在 `stmt->annotations` 上。
   - `gdparser`：注解会以独立 `AnnotationStatement` 出现在被注解语句之前。
   - 含义：GDCC semantic analyzer 需要显式做“注解收集并附着到下一个声明/语句”的预处理，不能像 Godot 一样直接从节点字段读取。

2. **类型引用模型不同**
   - Godot：`TYPE` 是 parser node 体系中的一等节点。
   - `gdparser`：类型提示统一是 `TypeRef(String sourceText, Range range)`。
   - 含义：`resolve_datatype(...)` 一类逻辑在 GDCC 中更适合做成 `TypeRef -> FrontendTypeFact` 的 side-table，而不是 AST node mutation。

3. **match/pattern/suite 模型更轻量**
   - Godot：有 `PATTERN`、`MATCH_BRANCH`、`SUITE` 等更细的节点层次。
   - `gdparser`：`MatchStatement` + `MatchSection` + `List<Expression> patterns` + `PatternBindingExpression` + `Block`。
   - 含义：GDCC 做基础绑定和类型流没问题，但想做 Godot 那种细粒度 pattern diagnostics / exhaustiveness，会更依赖自建语义中间结构。

### 2.3 “AST 已支持”不等于“近期 frontend 应立即支持 lowering”

几个现在需要明确写入报告的边界：

- `AwaitExpression`：AST 已支持，但 `doc/module_impl/frontend_implementation_plan.md` 仍把 `await/yield` 放在 coroutine 范畴，当前 `gdcc` LIR / backend 没有配套设计。
- `PreloadExpression`：AST 已支持，但项目已确认“第一阶段不把 `preload(...)` 当作类型来源”。
- `GetNodeExpression`：AST 已支持，但当前 GDCC 没有场景树元数据，静态类型通常只能保守降级。
- `ClassDeclaration` / `ConstructorDeclaration` / `BaseCallExpression`：AST 已支持，但当前 `FrontendClassSkeletonBuilder` 仍完全没有消费这些节点。

所以本报告的更新方向不是“把一切 feature 都标成已可做”，而是“哪些现在该纳入 semantic 设计，哪些仍应先诊断或延后 lowering”。

---

## 3. 调研范围与方法

本次调研覆盖 3 个事实源。

### 3.1 `gdcc` 本地仓库与文档

重点阅读了：

- `doc/module_impl/frontend_implementation_plan.md`
- `doc/gdcc_low_ir.md`
- `doc/gdcc_type_system.md`
- `doc/gdcc_c_backend.md`
- `doc/gdcc_ownership_lifecycle_spec.md`
- `doc/module_impl/call_method_implementation.md`
- `doc/module_impl/load_store_property_implementation.md`
- `doc/module_impl/index_insn_implementation.md`
- `doc/module_impl/operator_insn_implementation.md`
- `src/main/java/dev/superice/gdcc/frontend/parse/GdScriptParserService.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendBindingKind.java`
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/MethodCallResolver.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
- 相关 backend / frontend 测试

### 3.2 本地 `gdparser` 副本

重点确认：

- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/*`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/lowering/CstToAstMapper.java`
- `E:/Projects/gdparser/src/test/java/dev/superice/gdparser/frontend/lowering/CstToAstMapperTest.java`

重点问题是：

- 现在 AST 实际覆盖了哪些 Godot 语义分析会用到的节点？
- 新节点是否已经被 lowering 使用，而不是只有 record 定义？
- 这些节点的结构和 Godot 原生 parser 有哪些不一致？

### 3.3 GitHub 上游 Godot / Godot Docs 最新事实

本次通过 GitHub 原始文件核对了：

- `godotengine/godot`：`modules/gdscript/gdscript_analyzer.cpp`
- `godotengine/godot`：`modules/gdscript/gdscript_parser.h`
- `godotengine/godot-docs`：`tutorials/scripting/gdscript/static_typing.rst`

核对时间为 **2026-03-06**，使用的是当前 `master` 分支内容。

---

## 4. 当前 GDCC 代码库的真实状态

## 4.1 已落地能力仍只到“解析 + 类骨架”

`gdcc` 当前 frontend 实际已经落地的部分仍然很小：

1. `GdScriptParserService`
   - 调 `gdparser` 做 CST -> AST。
   - 把 lowering diagnostics 统一转成 `FrontendDiagnostic`。

2. `FrontendClassSkeletonBuilder`
   - 提取 `class_name / extends / signal / var / func` 的浅层骨架。
   - 注入 `ClassRegistry.addGdccClass(...)`。
   - 检测重复类名和继承环。

3. frontend 测试
   - 目前只有 parse smoke、class skeleton、inheritance cycle 三类。

还没有实现的关键能力包括：

- identifier binding
- scope tree / capture analysis
- assignable analysis
- expression type inference
- call resolution
- property/index semantics
- CFG / suite / return merge
- AST -> LIR body lowering

也就是说，当前 frontend 仍更接近“parser + declaration collector”，还没有进入真正的 `semantic analyzer` 阶段。

## 4.2 当前 skeleton builder 的边界比 AST 能力更窄

`FrontendClassSkeletonBuilder` 当前只消费：

- `ClassNameStatement`
- `ExtendsStatement`
- `SignalStatement`
- `VariableDeclaration(kind == VAR)`
- `FunctionDeclaration`

它 **不会处理** 以下已经存在于 AST 中的重要节点：

- `ClassDeclaration`
- `ConstructorDeclaration`
- `EnumDeclaration`
- `AnnotationStatement`
- `AssertStatement`
- `ReturnStatement` / 其它 body-level 语句
- `BaseCallExpression`
- `SelfExpression` / `CastExpression` / `TypeTestExpression` 等所有 body-level expression

这意味着一个非常重要的结论：

> 当前 frontend 的限制主要已经不是 `gdparser` AST 的限制，而是 `gdcc` 自己尚未消费这些 AST 节点。

## 4.3 `FrontendBindingKind` 仍然不完整

当前 `src/main/java/dev/superice/gdcc/frontend/sema/FrontendBindingKind.java` 里有：

- `LOCAL_VAR`
- `PARAMETER`
- `CAPTURE`
- `PROPERTY`
- `UTILITY_FUNCTION`
- `CONSTANT`
- `SINGLETON`
- `GLOBAL_ENUM`
- `UNKNOWN`

但仍然缺少一个在 frontend 设计里很关键的绑定种类：

- `TYPE_META`

而 `gdparser` 现在已经有 `SelfExpression`、`CastExpression`、`TypeTestExpression`、`PreloadExpression` 等会直接推动“类型对象 / 类型引用”分析的节点；没有 `TYPE_META` 会让 binder 很快变得混乱。

## 4.4 `ClassRegistry` 仍是重要基础设施，但不能直接当 binder

`ClassRegistry` 对 frontend 很重要，但必须明确它的适用边界。

### 4.4.1 `findType(...)` 很宽松

当前行为依旧是：

1. builtin / container 尽量解析成具体 `GdType`
2. engine class / GDCC class -> `GdObjectType`
3. global enum / utility function -> `null`
4. 未知名字 -> 仍返回 `new GdObjectType(name)`

这对“宽松类型引用解析”很好，但对“identifier 到底是不是 type meta”并不可靠。否则会造成：

- 拼写错误类型名被误当成对象类型
- singleton 与 type name 混淆
- 未来 preload/global class/type import 路径难以区分

### 4.4.2 `checkAssignable(...)` 是 backend 事实，不等于 Godot 编辑器规则

当前 `ClassRegistry.checkAssignable(...)` 仍是：

- 同类型可赋值
- 对象支持继承上行
- `Array` / `Dictionary` 有限协变

这与 Godot typed collection 的静态规则不完全一致；frontend 若服务于 lowering，应优先对齐 backend 事实，而不是机械照搬 Godot 的编辑器诊断标准。

## 4.5 backend 仍然定义了 frontend 必须遵守的语义合同

### 4.5.1 方法调用：`MethodCallResolver`

当前 backend 明确支持：

- `GDCC`
- `ENGINE`
- `BUILTIN`
- `OBJECT_DYNAMIC`
- `VARIANT_DYNAMIC`

且测试已覆盖：

- 已知 engine 类型调用未知 method -> `OBJECT_DYNAMIC`
- 已知 GDCC 类型调用未知 method -> `OBJECT_DYNAMIC`
- 父类静态类型变量调用子类方法 -> `OBJECT_DYNAMIC`

因此：

> “已知对象缺失 method 必须先 pack 成 Variant 才能避免 fail-fast”这条说法，已经不符合当前 backend 事实。

### 4.5.2 属性访问：`PropertyAccessResolver`

当前 backend 明确是：

- 已知对象类型会沿继承链静态找 property
- 缺 metadata / inheritance cycle / owner 非法 / property 不存在时，直接 fail-fast
- 只有 object type 本身未知时，frontend/backend 才能退回更动态的路径

因此：

> 如果 frontend 仍坚持“已知对象缺失 property -> warning + 动态回退”，那就不能直接发 `load_property` / `store_property`，而必须主动改写为 `variant_get_named` / `variant_set_named` 路径。

---

## 5. Godot `GDScriptAnalyzer` 最新设计要点

## 5.1 上游阶段结构没有变，而且仍然非常值得直接借鉴

截至本次调研，`gdscript_analyzer.cpp` 仍然有：

- `resolve_inheritance()`
- `resolve_interface()`
- `resolve_body()`
- `resolve_dependencies()`
- `analyze()` 串联整体流程

这说明 GDCC 继续采用 `inheritance -> interface -> body -> lowering` 是正确方向，不需要重新发明阶段划分。

还有一个工程上很重要的观察：

- `analyze()` 在 `resolve_inheritance()` 失败时会立即返回；
- 但 `resolve_interface()` 即便产生错误，也不会立刻短路 `resolve_body()`。

这说明 Godot 的策略是：

> inheritance 是硬前置；interface/body 则尽量继续跑，以收集更多诊断。

GDCC 若希望做“tolerant but explainable” 的 analyzer，这一点很值得学。

## 5.2 Godot 仍然把大量语义事实直接写回 AST

从 `gdscript_parser.h` 仍能看到这些字段：

- 所有 `Node` 上的 `datatype`
- `ExpressionNode` 上的 `is_constant` / `reduced_value`
- class/function 上的 `resolved_interface` / `resolved_body` / `resolved_signature`
- function 上的 `default_arg_values`
- lambda 上的 `captures`
- 多种声明节点上的 `usages`

这再次证明：

> Godot analyzer 的实现方式本质上是“边遍历 AST，边把语义结果写回 AST 节点”。

GDCC 无法这样做，因为 `gdparser` AST 是 immutable record 风格；因此 side-table 仍然是正确方案。

## 5.3 `resolve_assignable(...)` 仍然是 Godot 里极其关键的统一入口

上游当前仍然通过 `resolve_assignable(...)` 统一处理：

- variable
- constant
- parameter

它仍然负责：

- 显式类型提示
- initializer 降解/归约
- typed array / dictionary literal 元素类型传播
- `:=` 推断
- `null` 推断失败
- 常量表达式约束
- 指定类型与 initializer 的兼容性检查
- `use_conversion_assign`
- `INFERRED_DECLARATION` / `UNTYPED_DECLARATION` / `NARROWING_CONVERSION` 等诊断

对 GDCC 的直接启发仍然是：

> 不应为“局部变量 / 常量 / 参数 / 字段”各写一套彼此分叉的类型规则，而应抽象出统一的 assignable analyzer。

## 5.4 identifier 解析顺序与 capture/static-context 处理仍然很有参考价值

当前上游仍然体现出大体顺序：

1. enum 当前作用域
2. local / parameter / bind / iterator / local constant
3. member / inherited member / signal / member class
4. builtin meta type
5. native class
6. global class
7. singleton / autoload
8. global constant
9. global enum
10. utility function
11. 未找到则错误

同时它还明确处理了：

- static context 禁止访问 instance variable / instance function / signal
- lambda 引用 outer local / parameter / bind 时，加入 capture
- lambda 访问 member 时视为“使用 self”，而不是 capture

这些规则对 GDCC 仍可直接映射。

## 5.5 `get_function_signature(...)` + `validate_call_arg(...)` 的分工仍然是最佳参考

上游当前仍然采用两步法：

1. 先取签名：构造器、builtin、native、script、meta type、utility 等
2. 再做参数校验：数量、默认参数、vararg、hard mismatch、unsafe warning、窄化 warning

GDCC 当前 backend 的 `MethodCallResolver` 已经承担了大量“签名挑选”工作；frontend 需要补足的是：

- 面向诊断的 `unsafe reason`
- 面向 lowering 的默认参数补全计划
- 更丰富的 AST-side semantic facts

## 5.6 suite 类型合流与 lambda 延迟分析依旧成立

上游当前仍保留：

- `decide_suite_type(...)`
- `resolve_suite(...)`
- `resolve_pending_lambda_bodies()`

关键点没有变化：

1. `if / for / match / return / while` 的 suite 类型会被归并；混合不兼容时回退成 `Variant`
2. lambda body 会被延迟解析，等外层 suite 推进后再统一处理
3. capture 会在后续被物化进 lambda 参数前部

对 GDCC 来说，这仍然是最值得借鉴的工程策略。

---

## 6. `gdparser` 当前 AST 的最新状态与直接影响

## 6.1 当前 AST 已经覆盖前端语义分析的大部分主干节点

本地 `gdparser` 当前至少已经有：

### 6.1.1 声明/语句类

- `VariableDeclaration`
- `FunctionDeclaration`
- `ConstructorDeclaration`
- `ClassDeclaration`
- `EnumDeclaration`
- `SignalStatement`
- `AnnotationStatement`
- `AssertStatement`
- `IfStatement` / `ForStatement` / `WhileStatement` / `MatchStatement`
- `ReturnStatement` / `ExpressionStatement` / `PassStatement`
- `BreakStatement` / `ContinueStatement` / `BreakpointStatement`
- `RegionDirectiveStatement`
- `UnknownStatement`

### 6.1.2 表达式类

- `IdentifierExpression`
- `SelfExpression`
- `LiteralExpression`
- `GetNodeExpression`
- `PreloadExpression`
- `CallExpression`
- `BaseCallExpression`
- `AttributeExpression`
- `SubscriptExpression`
- `BinaryExpression`
- `CastExpression`
- `TypeTestExpression`
- `UnaryExpression`
- `ConditionalExpression`
- `AssignmentExpression`
- `ArrayExpression`
- `DictionaryExpression`
- `LambdaExpression`
- `AwaitExpression`
- `PatternBindingExpression`
- `UnknownExpression`

从 frontend semantic analyzer 的角度看，这个覆盖面已经足以支撑比旧版报告更完整的设计。

## 6.2 这些节点不只是定义了 record，而是已经接入 lowering 与测试

`CstToAstMapper` 当前已明确把：

- `annotation` / `annotations` -> `AnnotationStatement`
- `class_definition` -> `ClassDeclaration`
- `constructor_definition` -> `ConstructorDeclaration`
- `assert(...)` -> `AssertStatement`
- `$...` / `%...` -> `GetNodeExpression`
- `preload(...)` -> `PreloadExpression`
- identifier `self` -> `SelfExpression`
- `await_expression` -> `AwaitExpression`
- binary `as` -> `CastExpression`
- binary `is` / `is not` -> `TypeTestExpression`
- `.foo(...)` -> `BaseCallExpression`

而 `CstToAstMapperTest` 已有针对这些节点的存在性断言。这说明：

> GDCC frontend 现在完全可以把这些节点纳入语义设计假设，不应该继续把它们当成“未来 AST 扩展后再说”。

## 6.3 但它与 Godot parser 的结构差异仍然会直接塑造 frontend 设计

### 6.3.1 `AnnotationStatement` 是独立语句，而不是节点附属属性

这会直接影响 semantic analyzer：

- 需要在 parse 结果上做一层“annotation attachment”预处理
- 或者在 body/interface 遍历时维护一个 pending annotation buffer
- 语义结果里最好显式有一张 `attachedAnnotations` side-table

否则 `@export`、`@onready`、未来的其它 annotation 都会变得很难正确消费。

### 6.3.2 `TypeRef` 是文本型类型引用，不是可变 AST type node

这意味着：

- `TypeRef` 解析应成为独立阶段/独立 helper
- 结果适合存成 `IdentityHashMap<TypeRef, FrontendTypeFact>` 或等价结构
- 不能期待像 Godot 那样直接对 `TYPE` node 做状态推进

### 6.3.3 `match` / pattern 的 AST 仍然是语义轻量化版本

当前 `MatchSection` 只包含：

- `List<Expression> patterns`
- `@Nullable Expression guard`
- `Block body`

`var` 模式绑定通过 `PatternBindingExpression` 表达。

这足以做：

- 基础绑定
- guard 表达式分析
- 局部作用域建立

但不适合一开始就承诺：

- 复杂 pattern exhaustiveness
- 精细化 pattern type narrowing
- 与 Godot 原生 analyzer 完全同级别的 match diagnostics

### 6.3.4 `GetNodeExpression` 只保留 `sourceText`

当前 `GetNodeExpression` 是：

- `String sourceText`
- `Range range`

它没有进一步拆出：

- `$` 还是 `%`
- `NodePath` 分段结构
- 目标节点的已知场景类型

所以 frontend 即便开始支持这个节点，也应默认采用保守策略，除非未来引入 scene metadata。

### 6.3.5 `PreloadExpression` 已有独立节点，但仍不等于项目现在就支持 preload-based typing

当前节点是 `PreloadExpression(Expression path, Range range)`，说明 parser 已经能表达它。

但结合项目现有策略，本阶段更合理的处理仍然是：

- 先把它当作独立语义节点处理
- 允许常量字符串路径检查
- 暂不把它纳入类型来源
- 若 lowering 暂无对应路径，则给出稳定的 unsupported diagnostic

## 6.4 新节点对 frontend 设计的具体影响

### 6.4.1 `SelfExpression`

这意味着 frontend 不再需要把 `self` 当普通 `IdentifierExpression("self")` 特判；可以直接：

- 绑定为当前类实例
- 在 static context 下报错
- 在 lambda 中标记“uses self”而不是 capture

### 6.4.2 `BaseCallExpression` + `ConstructorDeclaration`

这两者会直接推动以下设计需求：

- `_init` 的 skeleton / signature 收集
- 基类构造调用语义
- 基类方法调用（super/base call）语义
- constructor 默认参数与 base arguments 分析

### 6.4.3 `CastExpression` / `TypeTestExpression`

这两者已经足以支持：

- `as` 的结果类型 = target type
- `is` / `is not` 的结果类型 = `bool`
- 将来在 `if` / `match guard` 中做类型收窄

哪怕第一版不做完整 flow-sensitive narrowing，也应该在语义结果结构上预留扩展位。

### 6.4.4 `AwaitExpression`

这说明 parser 层面已经准备好了 coroutine 语法，但 GDCC 当前后端链路还没有。

因此推荐做法是：

- analyzer 识别它
- 不把它降成 `UnknownExpression`
- 在当前 milestone 发出明确的“语义已识别但 lowering 尚不支持”的诊断

这会比“静默忽略”或“unknown node”更稳定。

---

## 7. 面向 GDCC 的推荐语义分析架构（更新版）

## 7.1 总体阶段顺序

仍建议固定为：

### Phase 0：Parse

输出：

- `FrontendSourceUnit`
- AST
- parse/lowering diagnostics

### Phase 1：Inheritance

负责：

- `class_name`
- `extends`
- 稳定类名派生
- class skeleton 注入 `ClassRegistry`
- inheritance cycle
- 顶层 / inner class 的最小命名与挂接策略

### Phase 2：Interface

负责：

- property / const / signal / function / constructor / enum 的签名面
- 注解归属（至少 declaration-level）
- 类型引用解析（`TypeRef`）
- `_field_init_*` / `_default_*` / `_init` 隐式函数声明策略

### Phase 3：Body

负责：

- scope tree
- identifier binding
- assignable analysis
- expression typing
- call resolution
- property/index/member semantics
- suite/block/return merge
- lambda capture
- recognized-but-not-yet-lowerable feature diagnostics（如 `await`）

### Phase 4：Lowering

只消费 `AnalysisResult`，不重新发明语义。

## 7.2 `AnalysisResult` 应当比旧版设计再补两类表

旧版报告中的 side-table 思路仍然正确，但在当前 `gdparser` 形态下，建议至少补充：

1. **注解归属表**
2. **`TypeRef` 解析结果表**
3. **构造器 / base call 语义表**

建议字段至少包括：

```java
public record FrontendAnalysisResult(
        @NotNull String moduleName,
        @NotNull List<FrontendSourceUnit> units,
        @NotNull List<FrontendDiagnostic> diagnostics,
        @NotNull FrontendModuleSkeleton skeleton,
        @NotNull IdentityHashMap<Expression, FrontendTypeFact> expressionTypes,
        @NotNull IdentityHashMap<TypeRef, FrontendTypeFact> resolvedTypeRefs,
        @NotNull IdentityHashMap<IdentifierExpression, ResolvedSymbol> symbolBindings,
        @NotNull IdentityHashMap<Node, ResolvedCall> resolvedCalls,
        @NotNull IdentityHashMap<Node, ResolvedMemberAccess> resolvedMembers,
        @NotNull IdentityHashMap<Node, FrontendScope> scopeByNode,
        @NotNull IdentityHashMap<Expression, Object> constantValues,
        @NotNull IdentityHashMap<Node, List<AnnotationStatement>> attachedAnnotations,
        @NotNull IdentityHashMap<ConstructorDeclaration, ResolvedConstructor> resolvedConstructors,
        @NotNull IdentityHashMap<BaseCallExpression, ResolvedCall> resolvedBaseCalls
) {}
```

这里不要求名字完全一致，但职责应明确存在。

## 7.3 绑定优先级建议（GDCC 版，更新）

建议最终固定为：

1. local variable
2. parameter
3. capture
4. current class property / constant / function / signal / enum / inner class
5. singleton
6. global enum
7. utility function
8. type meta（builtin / engine / gdcc class）
9. unknown

并把 `FrontendBindingKind` 至少补成：

- `LOCAL_VAR`
- `PARAMETER`
- `CAPTURE`
- `PROPERTY`
- `CONSTANT`
- `SINGLETON`
- `GLOBAL_ENUM`
- `UTILITY_FUNCTION`
- `TYPE_META`
- `UNKNOWN`

如果后续要显式支持类内 `enum` / `signal` / `function-as-callable`，可以继续细分，但 `TYPE_META` 现在就该补。

## 7.4 assignable analyzer 仍应保持统一入口

建议抽象成：

```java
public interface FrontendAssignableAnalyzer {
    @NotNull AnalyzedAssignable analyze(@NotNull AssignableInput input);
}
```

统一覆盖：

- property declaration
- local var
- const
- parameter
- constructor parameter

并在输出中至少给出：

- 最终类型
- 类型来源（显式 / 推断 / 回退）
- 是否常量
- 是否需要 conversion assign
- 诊断

## 7.5 新 AST 节点对应的语义建议

### 7.5.1 `AnnotationStatement`

建议做一个 very-early pass：

- 线性扫描 `SourceFile.statements()` / `Block.statements()`
- 收集连续的 `AnnotationStatement`
- 绑定到其后的第一个非注解 statement
- 若注解后没有有效目标，发诊断

这样后续 interface/body phase 都可以像“目标节点自带注解列表”那样消费。

### 7.5.2 `ClassDeclaration`

建议第一版先支持：

- 识别 inner class
- 给稳定名字 / FQCN
- 注入 module skeleton 或 class tree

如果暂不做 lowering，也应显式给出 supported-but-not-emitted 的边界，而不是静默忽略。

### 7.5.3 `ConstructorDeclaration`

建议第一版至少完成：

- `_init` 签名采集
- 参数类型与默认参数分析
- `baseArguments` 的类型检查
- 与 `BaseCallExpression` 的一致性约束

### 7.5.4 `BaseCallExpression`

建议把它视为独立的 call kind，而不是普通 `CallExpression` 特判。

例如：

```java
public enum ResolvedCallKind {
    GLOBAL,
    METHOD,
    STATIC_METHOD,
    BASE_METHOD,
    CONSTRUCTOR_BUILTIN,
    CONSTRUCTOR_OBJECT,
    LAMBDA,
    OBJECT_DYNAMIC,
    VARIANT_DYNAMIC
}
```

### 7.5.5 `AwaitExpression`

当前建议：

- analyzer 识别节点
- body phase 标记 async requirement / unsupported feature
- lowering 阶段在当前 milestone 直接拒绝

不要把它当 `UnknownExpression`，否则未来切入 coroutine 会很痛苦。

## 7.6 调用与成员访问策略仍然以后端 resolver 为准

### 7.6.1 调用

- 能静态决议时，与 `MethodCallResolver` 对齐
- 已知对象缺失 method：warning + `OBJECT_DYNAMIC`
- `Variant` receiver：`VARIANT_DYNAMIC`
- 明显参数不兼容：error

### 7.6.2 property

- 已知对象类型 + property 存在：`load_property` / `store_property`
- 已知对象类型 + property 不存在：
  - `strictMemberAccess=true` -> error
  - `strictMemberAccess=false` -> warning + `variant_get_named` / `variant_set_named`

### 7.6.3 index

按当前 `gdcc_low_ir.md` 与 `index_insn_implementation.md`：

- `variant_get_indexed` / `variant_set_indexed`
- `variant_get_keyed` / `variant_set_keyed`
- `variant_get_named` / `variant_set_named`
- `variant_get` / `variant_set`

frontend 要做的是根据 receiver type + key type 选择最合适的语义通道，而不是只看语法外形。

---

## 8. 当前实现与计划中的关键风险（更新版）

## 8.1 `gdparser` 已经认识的新节点，`gdcc` frontend 目前会“静默掉过去”一部分

这比旧版报告中的“节点缺失”更危险。

例如：

- `ClassDeclaration`
- `ConstructorDeclaration`
- `AnnotationStatement`

这些节点现在已在 AST 中稳定存在，但当前 `FrontendClassSkeletonBuilder` 不会消费它们。如果继续不显式处理，就会出现“语法被成功解析，但 frontend 没有任何语义反馈”的静默遗漏。

## 8.2 `frontend_implementation_plan.md` 关于 method 动态回退的表述仍与 backend 现状不一致

当前 frontend plan 仍保留“method 动态回退要先把 receiver 降成 Variant”的旧说法，而 backend 代码与测试已经证明：

- 已知对象缺失 method -> 可以直接 `OBJECT_DYNAMIC`

这一点建议尽快修订，否则会误导后续实现者。

## 8.3 property fallback 与 backend 现状的冲突仍然存在

这一点没有变化：

- backend 的已知对象 property 缺失 -> fail-fast
- frontend plan 的宽松策略 -> warning + 动态回退

所以 frontend 必须主动 lower 为 `variant_get_named` / `variant_set_named`，不能把问题留给 backend。

## 8.4 `AwaitExpression` 是“已可解析，但尚无 lowering 合同”的典型节点

这类节点最容易造成设计混乱：

- 如果忽略，会让用户误以为编译器支持了它
- 如果当 unknown，会污染未来扩展路径

建议尽快建立“recognized but unsupported” 的诊断分类。

## 8.5 `GetNodeExpression` / `PreloadExpression` 的静态类型能力仍受项目元数据边界限制

即便 AST 已有它们，frontend 也不能凭空知道：

- `$Node` 最终对应什么节点类型
- `preload("res://...")` 资源到底是什么 script / scene / resource

所以近期更合理的是：

- 保守 typing
- 限制为 warning / unsupported / runtime path
- 不承诺 Godot 编辑器级别的静态精度

## 8.6 当前诊断体系仍缺少稳定的 warning category 命名空间

在引入更多已识别 AST 节点后，这个问题更明显了。至少应规划：

- `sema.unsafe_method_access`
- `sema.unsafe_property_access`
- `sema.inferred_declaration`
- `sema.untyped_declaration`
- `sema.narrowing_conversion`
- `sema.unsupported.await`
- `sema.unsupported.annotation_target`

---

## 9. 建议新增或调整的测试组

除了旧版报告中的绑定/调用/返回合流测试，这次建议再补 6 组与新 AST 相关的测试。

### 9.1 前端语义测试

1. `FrontendAnnotationAttachmentTest`
   - 连续注解绑定到下一个 declaration/statement
   - 无目标注解报错

2. `FrontendSelfBindingTest`
   - `SelfExpression` 在 instance context 绑定为当前类实例
   - static context 报错

3. `FrontendCastAndTypeTestTest`
   - `as` 的结果类型
   - `is` / `is not` 的结果类型
   - 未来可逐步扩到 branch narrowing

4. `FrontendConstructorAndBaseCallTest`
   - `_init` 参数类型
   - `BaseCallExpression` 的 owner / signature 选择

5. `FrontendAwaitUnsupportedDiagnosticTest`
   - `AwaitExpression` 应给稳定 unsupported diagnostic，而不是 unknown node

6. `FrontendInnerClassSkeletonTest`
   - `ClassDeclaration` 至少要么被纳入 skeleton，要么显式报 unsupported；不能静默丢失

### 9.2 现有 parity / fallback 测试仍然应该保留

1. `FrontendCallResolutionParityTest`
2. `FrontendDynamicPropertyFallbackTest`
3. `FrontendKnownMethodDynamicFallbackTest`
4. `FrontendReturnTypeMergeTest`
5. `FrontendScopeCaptureTest`
6. `FrontendTypedCollectionCompatibilityTest`

---

## 10. 最终建议

如果目标是“参考 Godot 当前 `GDScriptAnalyzer` 创建 GDCC 前端语义分析器”，我建议你按下面这条路线推进：

1. **继续借鉴 Godot 的阶段结构与安全思想**，不要照搬其 mutable AST 实现方式。
2. **把注意力从“gdparser 还缺什么 AST 节点”转移到“如何消费已经补齐的 AST”**。
3. **立即把 `AnnotationStatement`、`TypeRef`、`ClassDeclaration`、`ConstructorDeclaration`、`BaseCallExpression` 纳入 side-table 设计**。
4. **调用、属性、索引、所有权语义继续以后端现有 resolver/document 为事实源**。
5. **尽快修正文档中关于 unknown method fallback 的旧说法**。
6. **对 `AwaitExpression`、`PreloadExpression`、`GetNodeExpression` 采取“已识别、边界明确”的策略**：
   - 能分析就分析
   - 暂不能 lower 的就稳定报 unsupported
   - 不要再退回 unknown node 思路

一句话总结：

> 现在最适合 GDCC 的 frontend 方案，已经不再是“围绕 AST 缺口做极小 MVP”，而是“利用已经补齐的 `gdparser` AST，构建一个以 side-table 为核心、以后端语义合同为边界、阶段划分对齐 Godot 的语义分析器”。

---

## 11. 参考资料

### 11.1 `gdcc` 本地仓库

- `doc/module_impl/frontend_implementation_plan.md`
- `doc/gdcc_low_ir.md`
- `doc/gdcc_type_system.md`
- `doc/gdcc_c_backend.md`
- `doc/gdcc_ownership_lifecycle_spec.md`
- `doc/module_impl/call_method_implementation.md`
- `doc/module_impl/load_store_property_implementation.md`
- `doc/module_impl/index_insn_implementation.md`
- `doc/module_impl/operator_insn_implementation.md`
- `src/main/java/dev/superice/gdcc/frontend/parse/GdScriptParserService.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
- `src/main/java/dev/superice/gdcc/frontend/sema/FrontendBindingKind.java`
- `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/MethodCallResolver.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CallMethodInsnGenTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolverTest.java`

### 11.2 本地 `gdparser`

- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/Expression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/Statement.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/TypeRef.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/SelfExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/GetNodeExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/PreloadExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/CastExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/TypeTestExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/AwaitExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/AnnotationStatement.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/AssertStatement.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/ClassDeclaration.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/ConstructorDeclaration.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/BaseCallExpression.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/ast/MatchSection.java`
- `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/lowering/CstToAstMapper.java`
- `E:/Projects/gdparser/src/test/java/dev/superice/gdparser/frontend/lowering/CstToAstMapperTest.java`

### 11.3 Godot / Godot Docs（2026-03-06 核对）

- `godotengine/godot`：`modules/gdscript/gdscript_analyzer.cpp`
- `godotengine/godot`：`modules/gdscript/gdscript_parser.h`
- `godotengine/godot-docs`：`tutorials/scripting/gdscript/static_typing.rst`

