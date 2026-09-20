# Frontend Enum 实施计划（常量绑定路线）

> 本文档是 GDScript `enum` 特性的实施计划与验收细则。设计方向：**enum 作为常量绑定实现**——
> 匿名枚举成员与命名枚举组都注册为类作用域中的只读 `CONSTANT` 绑定，成员访问在编译期
> 直接物化为整数字面量，命名枚举组本身在值上下文物化为 Dictionary 构造。
> 无新 LIR 指令、无 C 模板/运行时改动，复用现有 LIR/backend surface；
> backend 改动仅限 Step 8 在 `CGenHelper` 新增一条 hint 映射规则（Java codegen 侧）。

- 状态：实施中（Step 1-7 已完成并通过验收；Step 8-9 尚未落地；已经过多轮评审修订；
  2026-09 修订：跨类枚举限定访问 `Other.State.IDLE` / `Other.IDLE` 由延后边界转为支持面，
  并入 Step 5-7，见 §1.3.5、§2.1、Step 5 修订记录）
- 适用范围：
  - `src/main/java/gd/script/gdcc/scope/**`
  - `src/main/java/gd/script/gdcc/frontend/scope/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/LirClassDef.java`（仅常量表扩展）
  - `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`（仅 Step 8 的 hint 映射规则）
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `frontend_global_constant_implementation.md`（全局枚举/常量裸访问事实源）
  - `frontend_top_binding_analyzer_implementation.md`
  - `frontend_visible_value_resolver_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `scope_architecture_refactor_plan.md`
  - `scope_analyzer_implementation.md`
  - `scope_type_resolver_implementation.md`
  - `inner_class_implementation.md`
  - `frontend_lowering_skeleton_pre_pass_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_container_literal_implementation.md`
  - `frontend_parameter_default_implementation.md`
  - `diagnostic_manager.md`

---

## 1. 背景与目标

### 1.1 Godot 4.x enum 语义（对齐目标）

- `enum {A, B = 5, C}`（匿名）：成员是注入当前类作用域的 int 常量（`A=0, B=5, C=6`），可裸访问。
- `enum State {IDLE, JUMP = 5}`（命名）：`State` 是一个 **Dictionary 常量**（等价于
  `const State = {"IDLE": 0, "JUMP": 5}`，key 为 String——Godot 4.5 源码
  `gdscript_analyzer.cpp` 以 `dictionary[String(...)] = value` 构建并 `make_read_only()`），
  成员经 `State.IDLE` 访问；
  命名枚举成员**不**注入当前作用域。
- 未赋值的成员 = 前一成员值 + 1，首成员默认 0；允许不同成员同值。
- 成员值必须是编译期可求值的 int 常量表达式（Godot 走完整 constant expression reduction）。
- 命名枚举可作类型标注：`var x: State` / `Array[State]`，实际类型为 `int`。
- 命名枚举支持 Dictionary 方法：`State.keys()` / `State.values()`。
- 枚举只在类体（含 inner class 体）合法；函数体内 `enum` 在 Godot 中是解析错误。
- 类常量/枚举沿继承链可见（子类可裸用父类枚举）。本计划 MVP 只继承 **body 值查找**；
  枚举 initializer 引用父类常量属延后边界（见 §2.2）。
- 跨类限定访问在 Godot 中是一等能力：`Other.State.IDLE`、继承后的 `Other.PARENT_IDLE`、
  `Other.State`（Dictionary 值）均合法。本计划将其纳入支持面（Step 5-7，编译期常量折叠
  路线）；class `const` 的限定访问不在此列（见 §2.2）。

### 1.2 现状差距（调研结论）

| 层 | 现状 |
|---|---|
| parser/AST | 外部 `gdparser:0.5.4` 已完整解析 `enum_definition` → `EnumDeclaration(@Nullable String name, List<EnumMember> members, Range)`；`EnumMember(String name, @Nullable Expression value, Range)`。匿名枚举 `name == null`。`ASTNodeHandler` 已提供 `handleEnumDeclaration` / `handleEnumMember` 回调 |
| skeleton | `FrontendClassSkeletonBuilder.fillClassMembers`（:285-358）对 `EnumDeclaration` 落 `default -> {}`，完全忽略 |
| scope | `ClassScope.defineConstant(...)`（:136-144）已存在但无生产调用；`indexDirectMembers`（:229-239）只索引 property/signal/function；`resolveInheritedValueMember`（:249-267）不继承常量 |
| 用户级 `const` | 类级被 skeleton 忽略；块级被 `FrontendVariableAnalyzer` 显式拒绝（`sema.unsupported_variable_inventory_subtree`）。本计划**不**承接 class `const`，仅为 enum 建立常量通道 |
| 函数体内 `enum` | **当前是静默 no-op**：`FrontendStatementResolver.resolveStatement`（:92）default → `runUnsupported`（`FrontendBodyOwnerProcedures`:818-831），其 `default -> {}` 不发任何诊断。本计划必须新增诊断路径，不能当成既有合同消费 |
| 全局枚举/常量 | 已闭环：`ClassRegistry → ScopeValue(CONSTANT) → FrontendBinding(CONSTANT) → OpaqueExprValueItem → LiteralIntInsn`，可复用 |
| chain | `reduceGdccStaticLoad`（`FrontendChainReductionHelper`:1056-1103）对 GDCC 类常量返回 UNSUPPORTED；`ReceiverState`（:187-197）不携带 declaration，须用 `ReductionRequest.chainExpression()` + `bindingLookup()`（:334-342）绕开。**Step 5 修订后**：GDCC 类常量分支落地枚举常量/枚举组两条子路线（见 Step 5 改动），ReceiverState 保持不变，组延续经「前一 step fact」拦截实现 |
| lowering | `FrontendOpaqueExprInsnLoweringProcessors` CONSTANT 分支（:149-169）只认 `ExtensionGlobalConstant` / `ExtensionEnumValue` / `GdScriptLanguageConstant`，其余 fail-fast |
| 既有测试 | `FrontendClassSkeletonTest.buildEmitsExplicitDiagnosticsForDeferredTypeMetaSources`（:654-701）断言 `var from_enum: LocalState` 回退 Variant + `sema.type_resolution`；命名枚举类型标注落地后该断言必须更新 |

### 1.3 总体设计

```text
EnumDeclaration（类体，含 inner class 体）
  └─ skeleton 枚举预 pass（FrontendClassSkeletonBuilder + FrontendEnumConstantEvaluator）
       ├─ 冲突校验 → 求值成员常量表达式（受限子集，见 §2.4）
       ├─ 全部成功才落地事实；失败发 sema.class_skeleton + skippedSubtreeRoots，不留半成品
       └─ 落地物：ClassDef 常量表条目 + declared-type scaffold 上的 GDCC_ENUM type-meta
            └─ scope phase
                 ├─ ClassScope 索引常量 → value 命名空间 ScopeValueKind.CONSTANT
                 ├─ 继承 walk 扩展到常量表
                 └─ 正式 ClassScope 注册命名枚举 type-meta
  消费端：
  ├─ 裸成员 `IDLE` / 裸组名 `State` → top binding CONSTANT binding（现有映射零改动）
  ├─ `State.IDLE` → chain binding 枚举成员 route → RESOLVED(CONSTANT, int)
  ├─ `Other.State.IDLE` / `Other.IDLE` → chain binding GDCC 类常量分支 → 同形 fact
  ├─ `var x: State` → ScopeTypeResolver → int（经 type-meta instanceType）
  └─ lowering：
       ├─ 裸成员 / `State.IDLE` / `Other.State.IDLE` / `Other.IDLE` → LiteralIntInsn（编译期字面量）
       └─ 裸 `State` / `Other.State` → LiteralStringInsn/LiteralIntInsn + ConstructContainerLiteralInsn
```

关键架构决策：

1. **常量事实的唯一事实源是 `ClassDef` 常量表**，由 skeleton 写入、scope 阶段消费。
   成员值只在 skeleton 求值一次；body phase 与 lowering 只消费已发布事实，不重扫 AST、
   不重新求值（对齐「lowering 不得重新扫描 AST」合同）。
2. **命名枚举 = Dictionary 类型的 CONSTANT 值绑定**，不是 TYPE_META 值路线。
   `State` 在 value 命名空间命中 `ScopeValueKind.CONSTANT`；top binding 的
   value-wins-over-TYPE_META 规则（`frontend_top_binding_analyzer_implementation.md` §3.2）
   天然生效，`shouldPreferGlobalEnumTypeMeta` 只对 `ScopeValueKind.GLOBAL_ENUM` 生效，不受干扰。
3. **命名枚举同时在 type-meta 命名空间注册**（`ScopeTypeMetaKind.GDCC_ENUM`，
   `instanceType = GdIntType.INT`，`pseudoType = true`），供两处消费：
   类型标注解析（`ScopeTypeResolver.tryResolveDeclaredType` 取 `instanceType()`）与
   词法可见但值隔离上下文（如 inner class）中的 `State.IDLE` 静态成员 route。
4. **`State.IDLE` 两条入口**：
   - 声明类及子类内（value 路线）：`State` 绑 CONSTANT 值；chain reduction 在 property step 上
     识别「chain base 是 enum-group CONSTANT binding」并把**已存在的成员**解析为编译期常量；
     成员未命中时完全 fall through 到既有 Dictionary/builtin 路径（不得吞掉 `State.keys` 等
     Dictionary method-reference 合同）。
   - inner class 等词法可见但值隔离的上下文（type-meta 路线）：`State` 值查找 miss、
     type-meta 命中 GDCC_ENUM，`State.IDLE` 走 static-load 分支解析成员。
   两条路线发布同一形态的 member fact（`RESOLVED + CONSTANT + int + declaration=成员常量元数据`），
   lowering 统一物化 `LiteralIntInsn`，不构造 Dictionary、不产生 `LoadStaticInsn`。
5. **跨类限定访问（qualified 路线，Step 5 修订新增）**：`Other.State.IDLE` / `Other.IDLE`。
   chain 阶段晚于整个模块的 skeleton 完成点，目标类的常量表此时必然已填充完毕，因此
   跨类解析不存在求值序问题（该问题只影响 skeleton 期的枚举 initializer，见 §2.2）。
   - 链头 `Other` 经既有 type-meta head 路线解析为 GDCC_CLASS receiver，零改动；
   - `reduceGdccStaticLoad` 的 static 成员查找随本分支**重构为逐继承层统一 walk**
     （nearest layer wins，对齐 §2.5 遮蔽原则）：每一类层按「static 方法 → static property →
     枚举常量/枚举组（`getScriptConstants()`）」顺序查找，首层命中即停——
     命中 `GdScriptEnumConstant`（`Other.IDLE`，含继承成员）→ 直接发布同形 member fact；
     命中 `GdScriptEnumGroup`（`Other.State`）→ 发布组 fact（`CONSTANT` + Dictionary +
     declaration=组元数据），**outgoing receiver 为 Dictionary 实例**（不保留 type-meta）；
   - 组延续拦截：reduction 驱动在处理 property step 时检查**前一 step 已发布 fact** 的
     `declarationSite instanceof GdScriptEnumGroup`——成员存在则拦截并发布同形 member fact
     （`Other.State.IDLE` → 编译期常量）；成员不存在或当前为 call/subscript step 则不拦截，
     自然落到 Dictionary 路线。由此 `Other.State.keys()`、`Other.State["IDLE"]` 与裸
     `Other.State` 全部按 Dictionary 语义工作，与类内 value 路线的边缘语义完全一致
     （成员名优先于 Dictionary 方法名的 property 读取规则同样适用）；
   - 未命中任何枚举常量/组（含 class `const`，其声明不进入常量表、与未声明名不可区分）
     → 维持既有 UNSUPPORTED fallback 与 `sema.unsupported_chain_route`，既有锚定不变。

---

## 2. 语义合同

### 2.1 MVP 支持面

| 场景 | 示例 | 结果 | 验收锚点 |
|---|---|---|---|
| 匿名枚举成员裸访问 | `enum {IDLE, RUNNING}` → 函数体内 `IDLE` | `CONSTANT` binding，int，`LiteralIntInsn(0)` | Step 4/7 |
| 显式值与自动递增 | `enum {A = 5, B, C = A + 1}` | `A=5, B=6, C=6` | Step 2 |
| 一元/二元常量表达式 | `enum {MASK = 1 << 3, NEG = -1, ALL = 0xF0 \| MASK}` | 受限求值器（§2.4） | Step 2 |
| 引用同枚举前序成员 | `enum {A = 1, B = A + 1}` | 允许 | Step 2 |
| 引用同类前序枚举常量 | `enum {A} enum {B = A}` | 允许（查 ClassDef 已收集常量） | Step 2 |
| 引用全局常量/全局枚举裸成员 | `enum {NIL = TYPE_NIL, E = OK}` | 允许（查 `ClassRegistry`） | Step 2 |
| 命名枚举成员访问 | `State.IDLE` | 编译期 int 常量，`LiteralIntInsn` | Step 5/6/7 |
| 命名枚举作 Dictionary 值（声明类及子类内） | `print(State)`、`State.keys()`、`State["IDLE"]` | 每次求值物化新 Dictionary（key=String，对齐 Godot 源码；Godot 另将字典 `make_read_only()` 且共享单例，MVP 差异见 §2.2）；方法调用与 subscript 走既有 Dictionary route | Step 5/7 |
| 命名枚举作类型标注 | `var x: State`、`var a: Array[State]`、`func f(p: State)` | `int` / `Array[int]`；仅限声明类的词法作用域（含其 inner class 的词法链） | Step 2/3 |
| `@export` 枚举类型标注 | `@export var x: State` | skeleton 生成 `PROPERTY_HINT_ENUM` 用 hint_string（`Idle:0,Jump:5` 格式），编辑器出下拉 | Step 8 |
| 类型推断 | `var x := State.IDLE` | `int` | Step 4/5 |
| 继承可见性（值侧） | 子类裸用父类匿名成员 / 命名枚举 | `resolveInheritedValueMember` 扩展常量表（仅限 body 值查找；initializer 引用父类常量见 §2.2） | Step 3 |
| 跨类限定成员访问 | `Other.State.IDLE`、`Other.IDLE`（含目标类继承来的成员） | 编译期 int 常量，`LiteralIntInsn`；`reduceGdccStaticLoad` 枚举分支 + 组延续拦截（§1.3.5） | Step 5/6/7 |
| 跨类枚举组作 Dictionary 值 | `print(Other.State)`、`Other.State.keys()`、`Other.State["IDLE"]` | 每次求值物化新 Dictionary，方法调用与 subscript 走既有 Dictionary route | Step 5/6/7 |
| inner class 内访问外层命名枚举成员 | inner 体内 `State.IDLE` | type-meta 路线（§1.3.4） | Step 5 |
| static 上下文 | `static func f(): return IDLE` | `ResolveRestriction.allowClassConstants` 已允许（static/instance 均为 true） | Step 3 |
| property initializer / parameter default | `var x = State.IDLE`、`func f(x = IDLE)` | **有意放行**，两条路径分别成立：property initializer 经 shared `Scope.resolveValue(...)` class-scope lookup 命中静态只读枚举常量（不经 `FrontendVisibleValueResolver`；island 只拦截 self/实例成员，不拦截类常量）；parameter default 经 `PARAMETER_DEFAULT` domain 命中（拦截参数/局部/capture；`self` 与实例成员仅在 instance 方法默认值中允许，static 方法禁止——既有合同不变） | Step 4 |
| match pattern | `match s: State.IDLE:` / `IDLE:` | 走既有 LITERAL/EXPRESSION pattern 合同，零改动 | Step 5 |
| `is`/`as` 以枚举名为目标 | `x is State`、`x as State` | 擦除语义：经 declared-type 路径取 `instanceType` 得 int，等价于 `is int` / `as int`，不新增特判 | Step 3 |

### 2.2 明确不支持 / 延后（deferred boundary）

带恢复行为的边界（发诊断 + 跳过）必须在锚定 Step 落地 negative 测试
（正确 category + 子树跳过 + 兄弟存活）；延后/已知限制类边界以「文档化 +
行为不变锚点测试」验收（与 §6 DoD 1 同口径）：

| 场景 | 行为 | 验收锚点 |
|---|---|---|
| 函数体内 `enum` | **新增诊断**：`runUnsupported` 增加 `EnumDeclaration` 分支，发单条 `sema.unsupported_binding_subtree` error（锚定声明根）+ skip 子树；对齐 Godot（函数内 enum 是解析错误）。当前静默 no-op 是被修复对象 | Step 4 |
| 跨类限定访问：class `const` 与未声明名 | `Other.FOO`（`const FOO = 5`）、`Other.MISSING`：class `const` 整体延后且其声明不进入常量表，与未声明名在 chain 阶段不可区分，统一维持既有 UNSUPPORTED fallback + `sema.unsupported_chain_route`（`Worker.VALUE` 既有锚定不变）；枚举常量/枚举组已转为支持面（§1.3.5） | Step 5 |
| 嵌套类限定符 | `Outer.Inner.State.IDLE`：`.Inner` 在 GDCC static-load 路线中无 inner-class 成员分支，维持 UNSUPPORTED 延后 | Step 5 |
| 类型标注的继承可见 | 子类内 `var x: ParentEnum`：type-meta 查找纯词法不沿继承 walk，回退 Variant + `sema.type_resolution` warning；与 inner class 类型现状一致 | Step 3 |
| inner class 内的枚举 Dictionary 操作 | inner class 内仅 `State.IDLE` 可用；裸 `State` 值、`State.keys()`、`State["IDLE"]` 按既有值隔离/type-meta 合同拒绝（见 §2.6），**不**为本特性放开 inner-class 值隔离 | Step 5 |
| 成员值引用命名枚举成员 | `enum {A = State.IDLE}`：求值器子集不含 attribute 表达式，`sema.class_skeleton` error | Step 2 |
| 成员值引用 class `const` | `enum {A = FOO}`（`const FOO = 5`）：class `const` 整体延后，求值器查不到即发 `sema.class_skeleton` error | Step 2 |
| 成员值引用父类枚举常量 | `class Child extends Parent:` 内 `enum {NEXT = BASE + 1}`：skeleton 按源码序而非继承序填充类，求值器看不到父类常量表；发 `sema.class_skeleton` error（继承序求值属独立工作，不在 MVP 暗中解决） | Step 2 |
| 成员值为非 int / 非受支持形态 | 浮点、字符串、bool、调用、三元、`**`、attribute、subscript 等：`sema.class_skeleton` error + 跳过该枚举 | Step 2 |
| 匿名/命名枚举成员的重复值 | `enum {A = 1, B = 1}` | **允许**（对齐 Godot），非边界 | Step 2（正向用例防误判） |
| 空枚举 `enum State {}` | 先以解析测试锁定 gdparser 产物形态；若允许空成员列表则发 `sema.class_skeleton` error（Godot 要求至少一个成员） | Step 2 |
| 枚举 Dictionary 的运行时只读性 | Godot 对枚举字典 `make_read_only()` 且共享单例；MVP 每次求值（裸 `State` 或 `Other.State`）物化新 Dictionary，`State["X"] = 1` 写保护不做（Godot 为运行时错误） | Step 9 记入已知限制 |

### 2.3 数据模型（新增，均在 `gd.script.gdcc.scope` 包）

```java
/// 一个已求值的脚本枚举成员常量。匿名成员 groupName == null。
public record GdScriptEnumConstant(
        @NotNull String memberName,
        long value,
        @Nullable String groupName,
        @NotNull String ownerClassCanonicalName
) {}

/// 命名枚举组：命名枚举绑定的 declaration 载体，成员按源码顺序。
public record GdScriptEnumGroup(
        @NotNull String name,
        @NotNull List<GdScriptEnumConstant> members,
        @NotNull String ownerClassCanonicalName
) {
    public @Nullable GdScriptEnumConstant findMember(@NotNull String memberName) { ... }
}

/// ClassDef 常量表条目。type 当前只出现 int（成员）与 Dictionary（命名组）；
/// declaration 为 GdScriptEnumConstant 或 GdScriptEnumGroup，未来 class const 复用本表。
public record GdScriptClassConstant(
        @NotNull String name,
        @NotNull GdType type,
        @NotNull Object declaration
) {}
```

- `ClassDef` 接口新增 `default @NotNull List<? extends GdScriptClassConstant> getScriptConstants() { return List.of(); }`
  ——该表是脚本源常量通道（当前为 enum 事实；未来 class `const` 复用）。`ExtensionBuiltinClass` /
  `ExtensionGdClass` 走 default 空表，**引擎/builtin 常量不进入此表**，继续走既有
  extension metadata 通道（`findEngineClassConstantInHierarchy` 等）；只有
  `LirClassDef` 覆写并新增 `addScriptConstant(...)`。backend 不消费该表。
- `ScopeTypeMetaKind` 新增 `GDCC_ENUM`：「GDCC 类声明的命名枚举用于类型位置」，
  `instanceType` 恒为 `GdIntType.INT`，`pseudoType = true`，`declaration` 为 `GdScriptEnumGroup`（非空）。
  该枚举值的全部穷尽分派点见 Step 3.4。

### 2.4 枚举常量求值器（`FrontendEnumConstantEvaluator`）

新 helper，放在 `gd.script.gdcc.frontend.sema.analyzer.support`（与 `FrontendExportAnnotationSupport`
同包；skeleton 编译期求值已有 `@export` 参数求值先例）。输入为 `EnumMember.value()` 表达式与
「同枚举已求值成员 + 同类已收集常量 + `ClassRegistry`」只读视图，输出 `long`。

支持的表达式形态（递归）：

| AST | 语义 |
|---|---|
| `LiteralExpression`（integer） | 按 gdparser int lexeme 解析（`0x`/`0b`/`0o` 前缀与 `_` 分隔符）。**提取共享 helper 到 `gd.script.gdcc.util.StringUtil`**（建议 `parseGdIntegerLexeme(String): Long`，malformed/溢出返回 null；不含符号位——符号由 `UnaryExpression` 承担）：该 lexeme 解析当前在 `FrontendContainerLiteralSemanticSupport.parseIntLiteral` 与 `FrontendExportAnnotationSupport.renderIntegerLiteral` 各有一份私有实现，按 `common_rules.md` 合并并更新两处调用点；同时迁移 body literal lowering 中两处裸 `Long.parseLong(sourceText)`（`FrontendOpaqueExprInsnLoweringProcessors` integer/integral-number 分支），使普通整数字面量与枚举求值共享同一 lexeme 语义。LIR 文本解析器的十进制 parse 属不同语法域，不动 |
| `UnaryExpression` | `-` / `+` / `~` 作用于 int |
| `BinaryExpression` | `+ - * / % << >> & \| ^` 作用于 int；`/`、`%` 遇除数 0 报错 |
| `IdentifierExpression` | 依次查：同枚举前序成员 → 同类已收集常量（int 才可用）→ **祖先枚举名 blocker**（见下）→ `ClassRegistry.findGlobalEnumValueByBareName` / 全局 int 常量；命中 GDScript 语言常量（PI 等 float）须报「enum 值必须是 int」 |
| 其他 | 报错（`sema.class_skeleton`，锚定成员节点），整枚枚举跳过 |

自动递增：未赋值成员 = 前成员 + 1（首成员 0），按 `long` 语义。

**祖先枚举名 blocker（防穿透）**：skeleton 按源码序而非继承序填充类，求值器看不到父类
常量值；但父类枚举常量名若与全局常量同名（如父类 `enum { OK = 123 }`，全局有 `OK`），
直接落到全局 fallback 会静默求出错误的值。因此求值器在全局 fallback 之前必须先查
「祖先枚举名集合」：预 pass 启动时，对本类的 GDCC 祖先类（经 header discovery 的类图与
sourceClassRelations 拿 AST，不要求祖先已完成求值）做一次纯结构扫描，收集其枚举声明的
组名与匿名成员名；identifier 命中该集合即报 deferred-boundary 的 `sema.class_skeleton`
error（「暂不支持引用继承的枚举常量」），不落到全局查找。引擎祖先不收集（其常量本就
不可裸访问）。

### 2.5 命名空间注册矩阵

| 绑定 | 命名空间 | kind | type | declaration | 注册点 |
|---|---|---|---|---|---|
| 匿名成员 `IDLE` | ClassScope value | `CONSTANT` | `int` | `GdScriptEnumConstant` | `ClassScope` 索引 `ClassDef.getScriptConstants()` |
| 命名枚举 `State` | ClassScope value | `CONSTANT` | `Dictionary`（generic） | `GdScriptEnumGroup` | 同上 |
| 命名枚举 `State` | ClassScope type-meta | `GDCC_ENUM` | instanceType=`int` | `GdScriptEnumGroup` | skeleton 枚举预 pass 注册到 declared-type scaffold；`FrontendScopeAnalyzer` 在 `handleSourceFile`（顶层类）与 `handleClassDeclaration`（inner class）两处注册到正式 ClassScope |

- value 侧冲突校验在 skeleton 枚举预 pass 完成（见 Step 2），保证 scope 阶段 `defineDirectValue` /
  `defineTypeMeta` 的 fail-fast 永不因用户代码触发。
- 继承：值侧沿 `ClassDef` 常量表 walk（`resolveInheritedValueMember` 扩展）；type-meta 侧不继承
  （与 inner class 类型-meta 现状一致）。
- 遮蔽：callable-local `var`/参数/for iterator 遮蔽枚举常量沿用既有逐层 lookup；类常量遮蔽全局
  同名常量沿用「ClassScope 先于 ClassRegistry root」；枚举常量遮蔽父类同名成员合法（nearest wins）。
- 边缘语义：枚举成员名与 Dictionary 方法同名（如 `enum State {keys}`）时，`State.keys`
  命中枚举成员（枚举分支先于 builtin fallback）；`State.keys()` 调用步不受枚举分支影响，
  仍走 Dictionary 方法 route。跨类 qualified 路线沿用同一规则：`Other.State.keys` 命中
  枚举成员（组延续拦截），`Other.State.keys()` 走 Dictionary 方法 route。
- 跨类限定访问不引入新的注册：qualified 路线只消费目标类已发布的常量表事实，
  不向当前作用域注入任何绑定；`Other` 链头的 source-facing → canonical 解析沿用既有
  type-meta head 路线（`resolveSourceFacingTypeMeta`），不新增别名通道。

### 2.6 诊断 owner 与 category

不新增 category。同步更新 `diagnostic_manager.md` 相应条目语义：

| category | owner | 场景 |
|---|---|---|
| `sema.class_skeleton` | skeleton（枚举预 pass） | 枚举成员重名、成员/组名与同类 property/signal/function/常量/inner class 冲突、值表达式不可求值或非 int、空枚举 |
| `sema.unsupported_binding_subtree` | top binding（**本计划新增** `runUnsupported` 的 `EnumDeclaration` 分支；当前该 statement 被静默忽略） | 函数体内 `enum` 语句 |
| `sema.member_resolution` | chain binding（既有 FAILED member trace 路径） | `State.MISSING`：枚举分支只拦截已存在成员，miss fall through 到既有 Dictionary/builtin miss 路径，由该路径发此类目；跨类 `Other.State.MISSING` 同路径（组延续拦截不命中 → Dictionary miss） |
| `sema.unsupported_chain_route` | chain binding（既有 UNSUPPORTED route 路径） | 跨类 `Other.FOO` / `Other.MISSING`：class `const` 与未声明名的 GDCC static-load 延后边界（枚举常量/枚举组已转出本类目） |
| `sema.call_resolution` | chain binding（既有 FAILED call trace 路径） | inner class 内 `State.keys()` 等 pseudo-type 调用：`ScopeMethodResolver` 对 pseudoType 返回 `Failed(UNSUPPORTED_STATIC_RECEIVER)`，chain 映射为 `Status.FAILED` 后发此类目（与 `Variant.Type.keys()` 同类） |
| `sema.expression_resolution` | expr analyzer（既有 `TYPE_META` ordinary-value failed 路径，`FrontendExpressionSemanticSupport`） | 值位置消费枚举 type-meta（如 inner class 内裸 `State`）。注：`frontend_rules.md` 书面合同称 bare TYPE_META misuse 首条 `sema.binding` 由 top binding 发出，但现状代码（`tryPublishTypeMetaBinding` 不发诊断、`FrontendExpressionSemanticSupport` failed、expr analyzer 发 `sema.expression_resolution`）与之存在**先于本计划的偏差**；本计划沿用现状、不扩大也不修复该偏差（偏差修复是独立工作，见 §5 风险 9） |

恢复合同按枚举位置区分两类：

- **类体（含 inner class 体）内的非法枚举**：skeleton 枚举预 pass 发诊断 +
  记入 `skippedSubtreeRoots()`（scope phase 消费该 side table），同类其他成员与同 module
  其他类不受影响。
- **函数体内的枚举**：body 阶段 `runUnsupported` 发诊断后由 statement resolver 结构性停止
  下钻（不为该子树发布任何 fact），**不写 `skippedSubtreeRoots()`**——该 side table 是
  skeleton→scope 的阶段间协议，body 阶段晚于 scope phase，写入无消费者。兄弟 statement
  照常发布事实。

### 2.7 与现有 route 的兼容性

- 全局枚举/常量五级 `resolveValueHere` 顺序不变；本计划只向 **ClassScope 层**注入命中，
  全局 root 命名空间零改动。
- `Variant.Type.TYPE_NIL` 等限定式 `load_static` 路线不变；`GLOBAL_ENUM` kind 语义不变；
  引擎类枚举裸名禁令（如裸 `MOUSE_MODE_VISIBLE`）不变。
- GDCC 类 static-load 的其余边界不变：static property/method 路线零改动；class `const`、
  未声明名与嵌套类限定符维持 UNSUPPORTED；跨类枚举分支只在常量表命中时接管，
  miss 时完全落回既有 fallback，不改变 `Worker.VALUE` 式既有锚定行为。
- static var 仍为 `PROPERTY` binding kind；枚举常量为 `CONSTANT`，两者不混淆。
- 常量不可写：裸 `IDLE = 5` 由 `ScopeValue.constant()` 经既有左值校验拒绝；
  `State.IDLE = 5` 由 assignment 语义对 `bindingKind == CONSTANT` 的 member target 分类拒绝
  （`FrontendAssignmentSemanticSupport`），两条路径都已存在，CFG 不为枚举开写路径。
- compile-only gate 零改动：枚举成员 fact 为 `RESOLVED(CONSTANT)`，不命中既有
  BLOCKED/DEFERRED/FAILED/UNSUPPORTED 阻断面，也不命中既有 RESOLVED feature blocker
  （Dictionary method-reference、builtin type-meta static method-reference）；类级
  `EnumDeclaration` 不是 executable statement，不进入 compile surface。
- 命名枚举 value 绑定为 `CONSTANT`（非 `GLOBAL_ENUM`），不触发 dual-role type-meta 交换。

---

## 3. 端到端链路（实施后）

```text
enum State { IDLE, JUMP = 5 }            # skeleton 枚举预 pass: IDLE=0, JUMP=5
enum { RED, GREEN = State.JUMP }         # ❌ 拒绝：求值器不含 attribute 表达式
@export var weapon: State                # skeleton 生成 annotations["export"]="Idle:0,Jump:5"
                                         #   → backend 注册 PROPERTY_HINT_ENUM，编辑器出下拉
func f():
    var a = RED                          # （若匿名成员合法）OpaqueExprValueItem → literal_int
    var b = State.JUMP                   # MemberLoadItem（无 receiver）→ literal_int 5
    var c = State                        # OpaqueExprValueItem → literal_string_name×2 + literal_int×2
                                         #   → construct_container_literal
    var d: State = State.IDLE            # 类型标注解析为 int
    var e = Other.State.JUMP             # 跨类：组延续拦截 → literal_int 5
    var f2 = Other.RED                   # 跨类匿名成员（含继承）→ literal_int
    var g = Other.State                  # 跨类组值 → 与裸 `State` 同形的 Dictionary 物化
```

---

## 4. 分步实施与验收细则

> 每步独立可验证；除 Step 9 列出的契约变更外，任一步不得让既有测试变红。
> 遵守「单批修改不超过 5 个文件」的分批约束。

### Step 1：常量元数据与 `ClassDef` 常量表（已完成）

改动：

- 新增 `gd/script/gdcc/scope/GdScriptEnumConstant.java`、`GdScriptEnumGroup.java`、
  `GdScriptClassConstant.java`（record，§2.3）。
- `gd/script/gdcc/scope/ClassDef.java`：新增 `default getScriptConstants()` 返回 `List.of()`，
  注释写明该表只服务脚本源常量，引擎/builtin 常量继续走 extension metadata 通道。
- `gd/script/gdcc/lir/LirClassDef.java`：覆写 `getScriptConstants()` + 新增 `addScriptConstant(...)`。

验收：

- 新增常量表单元测试：default 为空表、`addScriptConstant` 保序、只读视图不可变。
- 回归：`./gradlew classes --no-daemon --info --console=plain` 通过。

### Step 2：skeleton 枚举预 pass 与常量求值（已完成）

实施记录（与原文档的偏差，均已按计划验收口径落地）：

- `ScopeTypeMetaKind.GDCC_ENUM` 提前到本步落地（原文列在 Step 3）：预 pass 注册 scaffold
  type-meta 必需该枚举值。6 处穷尽分派点已按 Step 3.4 语义全部补齐；其中
  `reduceStaticLoadStep` 的 `GDCC_ENUM` 成员解析分支（`reduceGdccEnumStaticLoad`）也一并实现
  （新增枚举值导致无 default 的 switch 无法编译，且该分支语义在 §1.3.4 已完整定义），其
  chain 层验收测试仍在 Step 5 归属范围内补充。
- `buildEmitsExplicitDiagnosticsForDeferredTypeMetaSources` 已在本步更新（行为翻转点）：
  `from_enum` 现解析为 int，`sema.type_resolution` 断言从 3 条降为 2 条（Alias/Preloaded
  保留），§4 Step 9 的对应条目届时只需复核。
- 空枚举产物形态已由 `FrontendEnumParseBehaviorTest` 锁定：gdparser 0.5.4 对空枚举体产出
  **一个空名字的幽灵成员**（解析诊断为空），非空成员列表；预 pass 据此以「成员名 blank 或
  成员列表为空」判定空枚举并发 `sema.class_skeleton`。
- 字符串/float/bool/三元作为枚举成员值**不会到达求值器**：gdparser 直接报
  `parse.lowering` 诊断并丢弃初值（值位置只剩 null）。因此 §2.2「非 int 字面量」边界由
  parser 层兜底：skeleton 检测到枚举子树范围内存在 `parse.*` 诊断时，跳过该枚举子树且
  **不发布任何常量/type-meta**、不重复发 skeleton 诊断（单一 owner 合同）；
  求值器层的 negative 锚点覆盖 call/attribute/subscript 等语法面。
- `fillSourceClassRelationMembers` 新增模块级 `relationsByCanonicalName` 形参（祖先枚举名
  blocker 需要跨 unit 类图），既有反射探针测试的调用点已适配。

改动：

- 提取共享 int lexeme 解析 helper 到 `StringUtil.parseGdIntegerLexeme`（§2.4），合并全部
  四处调用点：`FrontendContainerLiteralSemanticSupport.parseIntLiteral`、
  `FrontendExportAnnotationSupport.renderIntegerLiteral`、`FrontendOpaqueExprInsnLoweringProcessors`
  的 integer 与 integral-number 两个分支。
- 新增 `frontend/sema/analyzer/support/FrontendEnumConstantEvaluator.java`（§2.4）。
- `FrontendClassSkeletonBuilder.fillClassMembers`：在主循环**之前**执行枚举预 pass
  （对每个类，含 inner class）。前置重构：`fillClassMembers` 与 `requireDeclaredTypeScope`
  的 `declaredTypeScope` 形参/返回类型由 `Scope` 收窄为 `ClassScope`——`defineTypeMeta`
  定义在 `AbstractFrontendScope` 而非 `Scope` 接口，scaffold map 本身就是
  `IdentityHashMap<Node, ClassScope>`，收窄符合真实数据形态：
  1. 预收集本类全部成员名（property/signal/function/inner class source name）作为初始冲突表
     ——主循环尚未填充 classDef，不能依赖它；
  2. 逐枚 `EnumDeclaration`：组内成员重名（单枚内部校验）、与冲突表冲突（**匿名枚举只查
     成员名，命名枚举只查组名**；命名成员不注入类级命名空间，不参与查表）、空枚举校验
     → 求值（§2.4）；
  3. **单枚全部成功才落地**：写 `LirClassDef` 常量表（匿名成员逐条 `GdScriptEnumConstant`；
     命名枚举一条 `GdScriptEnumGroup`），向 `declaredTypeScope`（scaffold `ClassScope`）
     `defineTypeMeta(GDCC_ENUM, instanceType=int)`，并立刻并入冲突表供后续枚举校验——
     **匿名枚举只并入成员名，命名枚举只并入组名**（命名成员不注入类级命名空间，
     `enum State {IDLE}` 与 `enum {IDLE}`、与 `var IDLE` 均可并存；组内成员重名在单枚
     内部校验）。枚举按枚独立成败，不做全类 all-or-nothing；
  4. 任一失败：发 `sema.class_skeleton`（锚定该枚 `EnumDeclaration`）+ `markSkippedSubtreeRoots`，
     常量表与 type-meta 均不落地（禁止"先挂 type-meta、失败后掏空值绑定"的半成品状态）。
- 主循环新增 `case EnumDeclaration -> {}`（预 pass 已处理，显式忽略）。
- 该预 pass 保证 `var x: State` 与声明顺序无关（类型标注经 scaffold 在 member fill 期间解析）。

验收（新增 `FrontendEnumSkeletonTest` 或并入 `FrontendClassSkeletonTest`）：

- happy：匿名自动递增、显式值（含负数、`0x`/`0b`/`_` lexeme、移位/位运算）、引用前序成员、
  引用同类前序枚举常量、引用全局常量（`TYPE_NIL`/`OK`）、命名枚举组写入常量表、
  `var x: State` property 类型为 int（含先声明属性后声明枚举的顺序无关用例）、
  枚举声明在 inner class 体、命名成员与类级名字并存（`enum State {IDLE}` 与
  `var IDLE` / `func IDLE()` / `enum {IDLE}` 均合法）。
- negative（逐条锚定 category + 跳过子树 + 兄弟存活）：
  - 组内成员重名；组名与 property/inner class 同名；**匿名**成员名与同类 property 同名
  - 值表达式非法：调用、attribute（`State.IDLE` 引用）、字符串字面量、float、除零、
    引用未声明名、引用 class `const`、引用 GDScript 语言常量（PI → 非 int）、
    引用父类枚举常量（`enum {NEXT = BASE + 1}`，继承序求值延后）
  - 枚举间冲突：`enum {A} enum {A}`（匿名成员重名）、`enum State{IDLE} enum State{JUMP}`
    （组名重名）、`enum State{IDLE} enum {State}`（组名 vs 匿名成员）
  - 祖先枚举名防穿透：父类 `enum { OK = 123 }` + 子类 `enum { NEXT = OK }` 必须报
    `sema.class_skeleton`（不得静默落到全局 `OK`）
  - 引用后序成员（`enum {A = B, B = 1}`）报错
  - 空枚举：先以解析测试锁定 AST 产物形态，再按结论锚定诊断
  - 求值失败的枚举：`var x: State` 不得解析为 int（回退 Variant + `sema.type_resolution`），
    锚定「无半成品 type-meta」
- 回归：`FrontendClassSkeletonTest`、`FrontendClassSkeletonAnnotationTest`、
  `FrontendInheritanceCycleTest` 不变红；lexeme helper 合并后，普通 body 整数字面量的
  `0x`/`0b`/`0o`/`_` 与溢出行为有针对性回归（四处调用点共享同一实现）。

### Step 3：scope phase 接线（已完成）

实施记录（与原文档的偏差，均已按计划验收口径落地）：

- `GDCC_ENUM` 的 6 处穷尽分派点在 Step 2 已提前落地，本步逐一复核确认语义齐备（含
  `reduceGdccEnumStaticLoad`、`resolveStaticMethodReference` 返回 null、superclass 判定拒绝、
  `resolveStaticOwnerClass` 拒绝），全量 grep 无遗漏分派点，本步无新增改动。
- `extends State`（枚举名作超类）实际经 header 发现阶段的 `REJECTED_UNRESOLVED` 路径拒绝：
  命名枚举 type-meta 只注册在 ClassScope 词法链，不进入 `ClassRegistry` 根命名空间，
  因此 superclass 判定的 `GLOBAL_ENUM, GDCC_ENUM -> REJECTED_ENUM` 分支对脚本枚举保持
  防御性语义；用户可观察行为（单条 `sema.class_skeleton` error + 跳过该 class 子树 +
  兄弟存活）与计划一致，已由 `FrontendEnumScopeTest.enumNameRejectedAsSuperclassTarget` 锚定。
- gdparser 探针结论：枚举成员值位置的 lambda 无法存活（`enum { X = func(): return 1 }` 与
  `enum { X = [func(): return 1] }` 均产生 parse 诊断且初值被丢弃为 null），因此
  `FrontendInterfacePhase` / `FrontendVariableAnalyzer` 两个 walker 的
  `handleEnumDeclaration -> SKIP_CHILDREN` 是防御性合同，不存在可区分的行为差异，不单独
  立测试；可观察锚点改为 scope phase 的跳过——合法枚举的成员值表达式不产生任何
  `scopesByAst` 事实（`enumSubtreePublishesNoScopeFactsForMemberValueExpressions`）。
- 评审后补强两类 class boundary 的区分性锚点：`innerClassPublishesOwnEnumConstantsAndTypeMeta`
  让枚举只声明在 inner class 体内（删除 `handleClassDeclaration` 的注册调用即失败）；
  `isAndAsEnumTargetsEraseToInt` 跑完整语义管线，直接断言 `typeTestTargets` 的
  `TargetKnown(int)` 与 cast 表达式 publishedType=int，替代仅靠裸 resolver 的间接锚定。
- `FrontendVariableAnalyzer` 主 binder 按原计划零改动（`handleNode` 默认 SKIP_CHILDREN）。

改动：

- `ClassScope`：`indexDirectMembers` 在 functions 之后追加索引 `classDef.getScriptConstants()`
  （经 `defineConstant`）；`resolveInheritedValueMember` 追加沿继承链查 `getScriptConstants()`。
- `ScopeTypeMetaKind`：新增 `GDCC_ENUM`。
- `FrontendScopeAnalyzer`：
  - 枚举 type-meta 注册必须覆盖**两类 class boundary**：顶层脚本类的 `ClassScope` 由
    `handleSourceFile`（:146-158）创建，inner class 由 `handleClassDeclaration`（:284-295）
    创建。抽取共享 helper `defineEnumTypeMetas(ClassScope, ClassDef)`：为
    `classDef.getScriptConstants()` 中 `declaration instanceof GdScriptEnumGroup` 的条目
    `defineTypeMeta(...)`（`canonicalName = ownerCanonical + "." + name`，
    `sourceName = name`，`pseudoType = true`），并在两处 handler 都调用——只挂
    `handleClassDeclaration` 会让顶层类的命名枚举 type-meta 缺失，函数体内
    `var x: State` 与 inner class 的 `State.IDLE` 都会失效；
  - 显式 `handleEnumDeclaration -> SKIP_CHILDREN`（回调由 gdparser `ASTNodeHandler` 提供）：
    成员值表达式已由 skeleton 求值，scope phase 不得为其记录无用 scope 事实。
- `FrontendInterfacePhase` 与 `FrontendVariableAnalyzer` 的枚举子树跳过（回调
  `handleEnumDeclaration` 由 gdparser `ASTNodeHandler` 提供），按 walker 分列：
  - `FrontendInterfacePhase` 主 walker（`handleNode` 默认 CONTINUE，且不咨询
    `skippedSubtreeRoots`）：显式 `handleEnumDeclaration -> SKIP_CHILDREN`，防止枚举成员
    表达式内的 lambda 被 callable inventory 收录；
  - `FrontendVariableAnalyzer` 主 binder（`handleNode` 默认 SKIP_CHILDREN）：无需改动，
    以测试锚定该现状；
  - `FrontendVariableAnalyzer` 的 `UnsupportedVariableBoundaryReporter`（`handleNode`
    默认 CONTINUE）：显式 `handleEnumDeclaration -> SKIP_CHILDREN`；
  - `FrontendVariableAnalyzer` 的 `LambdaCaptureSourceScanner`（`handleNode` 默认
    CONTINUE）：显式 `handleEnumDeclaration -> SKIP_CHILDREN`，防止函数体/lambda 体内
    枚举成员表达式被收为 capture 或 nested-lambda 事件。
- **`GDCC_ENUM` 穷尽分派点逐一补齐**（新增枚举值会让无 default 的 switch 编译失败，
  以下每处须按语义补齐，不得只为过编译）：
  1. `FrontendDualRoleTypeMetaRouteSupport.supportsTopLevelTypeMeta`（:94-98）：补齐穷尽分派
     （`GDCC_ENUM` 与 `GLOBAL_ENUM` 同规则，`declaration() != null`）。注意该函数只服务
     dual-role bias 判定；inner class 的 `State.IDLE` route 实际由正式 ClassScope 的
     type-meta 注册 + `tryPublishTypeMetaBinding` 启用，与本函数无关；
  2. `FrontendConstructorResolutionSupport.resolveConstructor`（:81-96）：
     `GDCC_ENUM` 拒绝构造（`State.new()` 非法，对齐 `GLOBAL_ENUM`）；
  3. `FrontendChainReductionHelper.reduceStaticLoadStep`（:904-912）：新增 `GDCC_ENUM` 成员解析分支
     （Step 5 实施）；
  4. `FrontendChainReductionHelper.resolveStaticMethodReference`（:2569-2579）：
     `GDCC_ENUM` 返回 null（枚举无静态方法）；
  5. `FrontendClassSkeletonBuilder` superclass 判定（:1625-1657）：`GDCC_ENUM` 拒绝
     （枚举名不能作 `extends` 目标）；
  6. `ScopeMethodResolver.resolveStaticOwnerClass`（:1096-1125）：`GDCC_ENUM` 维持
     pseudoType 拒绝/UNSUPPORTED 语义。
  实施时先全量 grep `ScopeTypeMetaKind` 确认无遗漏分派点。

验收（已落地 `FrontendEnumScopeTest`，14 个用例）：

- scope 测试新增：类内裸成员命中 `CONSTANT`（`constant=true, writable=false`）、
  子类继承命中、static restriction 下允许、inner class 值隔离（裸 `State` 不命中 value）
  与 type-meta 词法可见（`State` 命中 `GDCC_ENUM`）。
- negative：被 skeleton 跳过的坏枚举不产生 scope 事实；同名局部 `var` 遮蔽枚举成员；
  `extends State`（枚举名作超类）被拒绝。
- 行为不变锚点：子类内 `var x: ParentEnum` 维持 Variant 回退 + `sema.type_resolution`
  warning（类型标注不沿继承）。
- type-meta 注册覆盖两类 class boundary：顶层类函数体内 `var x: State` 解析为 int；
  inner class 内 `var y: State` 同样成立（词法链）；`x is State` / `x as State` 按
  擦除语义等价于 int target。
- 回归：`FrontendScopeAnalyzerTest`、`ScopeProtocolTest`、
  `FrontendStaticContextValueRestrictionTest`、`FrontendInnerClassScopeIsolationTest`、
  `ClassRegistryScopeTest`、`ScopeTypeMetaChainTest` 不变红。

### Step 4：top binding / visible resolver / 表达式类型接通（已完成）

实施记录（与原文档的偏差，均已按计划验收口径落地）：

- `FrontendVisibleValueResolver`、`publishScopeValueBinding`、`FrontendExpressionSemanticSupport`
  确认零改动：ClassScope 常量经 shared `Scope.resolveValue(...)` 命中后按既有
  `CONSTANT -> CONSTANT` 映射发布（`FrontendBodyOwnerProcedures`:1765-1776），
  `declarationSite` 原样透传（`FrontendBinding` 对 declaration 类型无白名单），expr type
  直接取 `resolvedValue.type()`。property initializer 与 parameter default 两个 island
  同样零改动放行（island 拦截集合不含 CONSTANT；static/instance restriction 对类常量均
  allowed）。
- island 正向锚点使用裸成员形态（`var x = IDLE`、`func f(x = IDLE)`）：原文验收列举的
  `var x = State.IDLE` 是 qualified chain 路线，其 `reducePropertyStep` 枚举分支属 Step 5；
  qualified island 锚点由 Step 5 验收的「跨类 domain 锚点」条款统一覆盖
  （`var x = Other.State.IDLE`、`func f(x = Other.IDLE)`），本步不重复。
- 诊断消息采用与 bare lambda 一致的描述式文案（"Enum declaration is only supported at
  class body level"），经 `reportUnsupportedBindingMessage` 发出。

改动：

- `FrontendBodyOwnerProcedures.runUnsupported`（:819-843）新增 `case EnumDeclaration`（:835-840）：
  发单条 `sema.unsupported_binding_subtree` error（锚定声明根），覆盖函数体内 `enum`。
  这是新诊断路径，不是既有行为。该分支不写 `skippedSubtreeRoots()`（body 阶段晚于
  scope phase，写入无消费者）；statement resolver 结构性停止下钻，子树不发布任何 fact。
- 预期 `FrontendVisibleValueResolver`、`publishScopeValueBinding`
  （`CONSTANT -> CONSTANT` 映射已存在）、`FrontendExpressionSemanticSupport` 无需改动；
  若发现 class-constant 命中被既有 fail-fast 分支拦截，按最小改动修复并记录原因。

验收（已落地 `FrontendEnumBodyBindingExprTypeTest`，9 个用例）：

- top binding 测试：裸匿名成员与裸 `State` 发布 `FrontendBindingKind.CONSTANT`，
  `declarationSite` 为对应枚举元数据；局部 `var` 遮蔽枚举成员。
- expr type 测试：`IDLE` → int；`State` → Dictionary。
- negative：函数体内 `enum` → 单条 `sema.unsupported_binding_subtree` error + 子树无
  任何 published facts + 兄弟 statement 正常发布事实；未知名仍走 `sema.binding` + `UNKNOWN`。
- property initializer 与 parameter default island 消费枚举常量的正向用例
  （`var x = IDLE`、`func f(x = IDLE)` 裸成员形态；qualified 形态 `var x = State.IDLE`
  由 Step 5 的 chain 枚举分支与 domain 锚点覆盖，见实施记录）。
- 回归：`FrontendBodyOwnerProceduresExprTypeTest`、`FrontendVisibleValueResolverTest`、
  `FrontendSuiteResolverTest`、`FrontendInterfacePhaseTest`、`FrontendVariableAnalyzerTest` 不变红。

### Step 5：chain binding 枚举成员 route（含跨类限定访问，2026-09 修订纳入）

修订记录：原合同将跨类 `Other.State.IDLE` / `Other.IDLE` 整体延后（UNSUPPORTED +
`sema.unsupported_chain_route`）。2026-09 修订将脚本枚举的跨类限定访问转入支持面
（方案：chain 层扩展 + 复用编译期常量物化，§1.3.5）；class `const`、未声明名与
嵌套类限定符仍维持 UNSUPPORTED 边界。调研确认无既有测试锚定被翻转行为
（`Worker.VALUE` 用例锚定的是未声明名 fallback，修订后保持不变）。

改动（`FrontendChainReductionHelper`）：

- `reducePropertyStep`（:670-710）在 TYPE_META 早退（:677-679）之后、读取 `receiverType` 之前
  插入枚举分支，条件：**`stepIndex == 0`** 且 `request.chainExpression().base()` 为
  `IdentifierExpression` 且 `request.bindingLookup()` 命中 `CONSTANT` 且
  `declarationSite instanceof GdScriptEnumGroup`（`stepIndex` 限制必需：reduction 对每个
  step 都会进入本函数，而 `chainExpression().base()` 始终是链头；缺了它，
  `State.IDLE.JUMP` 的第二 step 会把 `JUMP` 误解析为枚举成员而不是按 int receiver 拒绝）：
  - 成员存在 → 发布 `RESOLVED`、`bindingKind=CONSTANT`、`resultType=int`、
    `declarationSite=GdScriptEnumConstant`，outgoing receiver 为 int；
  - **成员不存在 → 完全 fall through 到既有 Dictionary/builtin 路径**（不定制诊断，
    不吞掉 `State.keys` 等 Dictionary method-reference 的既有合同）；该路径成员 miss
    的诊断固定为 chain binding 持有的 `sema.member_resolution`。
- `reduceStaticLoadStep`（:904-912）新增 `case GDCC_ENUM`，委托给新私有 helper
  `reduceGdccEnumStaticLoad(...)`：从 `receiverTypeMeta.declaration()`（`GdScriptEnumGroup`）
  解析成员——成员存在 → `resolvedStaticLoadTrace(..., CONSTANT, int, 成员元数据)`；
  成员不存在或 declaration 形态异常 → `failedStaticLoadTrace`（`sema.member_resolution`）。
  注意该 switch 是 switch expression，不存在可 fall-through 的公共尾部，必须自成完整分支。
- **跨类枚举分支（修订新增）**：`reduceGdccStaticLoad`（:1088-1134）的 static 成员查找
  重构为**逐继承层统一 walk**（nearest layer wins）：对每一类层（目标类 → 父类 → …）按
  「static 方法 → static property → 枚举常量/枚举组（`getScriptConstants()`）」顺序查找，
  首层命中即停，全部层 miss 时落入既有 UNSUPPORTED fallback：
  - 命中 `GdScriptEnumConstant`（`Other.IDLE`，含继承成员）→ 发布同形 member fact
    （`RESOLVED + CONSTANT + int + declaration=成员元数据`），outgoing receiver 为 int；
  - 命中 `GdScriptEnumGroup`（`Other.State`）→ 发布组 fact（`RESOLVED + CONSTANT +
    Dictionary + declaration=组元数据`），**outgoing receiver 为 Dictionary 实例**
    （不保留 type-meta，使后续 call/subscript/miss 全部落到 Dictionary 语义）；
  - UNSUPPORTED fallback 语义不变（class `const` 与未声明名边界不变）；
  - 注意：现状是「方法整链优先、再 property 整链」的类别优先顺序
    （`resolveStaticMethodReference` → `findStaticPropertyInHierarchy` 各自遍历整条继承链），
    逐层化会把跨类别跨层遮蔽（如父类 static property 与子类枚举常量同名）归一化为
    nearest layer wins——既更符合 Godot 成员遮蔽语义，也让「子类枚举遮蔽父类同名成员」
    在 qualified 路线下成立。实施时必须先核对无既有测试锚定旧的类别优先顺序
    （`Worker.VALUE`、`SubWorker.shared` 等同类同类别用例不受影响），并新增跨类别遮蔽
    锚点；既有 helper 可复用其单层查找形态。
- **组延续拦截（修订新增）**：reduction 驱动在分派 property step 前检查前一 step 已发布
  fact 的 `declarationSite instanceof GdScriptEnumGroup`（在 reduction 循环内以局部状态
  跟踪上一 step fact，不改 `ReceiverState` 数据模型）：
  - 当前 step 为 `AttributePropertyStep` 且组成员存在 → 拦截并发布同形 member fact，
    outgoing receiver 为 int（`Other.State.IDLE` → 编译期常量）；
  - 成员不存在、或当前为 call/subscript step → 不拦截，落入既有 Dictionary 路线
    （`Other.State.MISSING` → `sema.member_resolution`；`Other.State.keys()` → Dictionary
    方法 route）——与类内 value 路线的拦截语义逐条对齐，包括成员名与 Dictionary 方法
    同名时 property 读取命中成员、call 步不受影响的边缘规则；
  - 拦截只在「紧邻组 step 之后」发生一次：`Other.State.IDLE.JUMP` 的 `.JUMP` 按 int
    receiver 继续，不再命中枚举分支。
- 嵌套类限定符（`Outer.Inner.State.IDLE`）不在本步支持：`.Inner` 在 GDCC static-load
  路线中无 inner-class 成员分支，维持 UNSUPPORTED。

实施记录（2026-09-20，production 部分）：

- `reduceStaticLoadStep` 的 `GDCC_ENUM` case 与 `reduceGdccEnumStaticLoad`
  （`FrontendChainReductionHelper`:954、:967-992）在 Step 2/3 已提前落地，核对与 Step 5
  合同逐条一致（成员命中 → `CONSTANT + int + GdScriptEnumConstant`；miss/畸形 declaration →
  FAILED + `sema.member_resolution`），本步零改动复用。
- `reducePropertyStep`（:697-727，value 路线分支 :708-727）在 TYPE_META 早退之后、
  `receiverType` 读取之前插入 value 路线枚举分支。两个防护均为实测必需：`stepIndex == 0`
  阻止 `State.IDLE.JUMP` 的第二 step 误命中组成员；step 恒等检查
  （`steps().getFirst() == step`）阻止 `reduceSubscriptStep` 以同 stepIndex 合成的
  property step 误触发（`State["IDLE"]` 经 parser 为 `SubscriptExpression`，本就不经
  chain；恒等防护锚定 helper 直测构造的 subscript-first chain 形态）。
- 新增共享 fact helper `resolvedEnumMemberTrace`：value 路线与组延续拦截发布同一形态
  （`RESOLVED + CONSTANT + int + declaration=GdScriptEnumConstant`、INSTANCE receiver、
  route `INSTANCE_PROPERTY`、outgoing int）。
- `reduceGdccStaticLoad`（:1135-1192）重构为逐继承层统一 walk（nearest layer wins）：每层按
  「static 方法（`resolveStaticMethodReferenceOnLayer`）→ static property
  （`findStaticPropertyOnLayer`）→ 枚举常量/组（`findEnumConstantOnLayer` 读
  `getScriptConstants()`，仅接受 `GdScriptEnumConstant`/`GdScriptEnumGroup` declaration
  形态）」顺序探测，首层命中即停；三类探测全 miss 但该层声明了任意同名成员（实例方法 /
  实例 property / 信号，`declaresAnyMemberOnLayer` :1266-1288）时**终止 walk** 落入
  UNSUPPORTED——近层非静态成员是终端遮蔽命中，不得泄漏到祖先枚举常量（对齐 Godot 统一
  成员命名空间遮蔽与值侧 `resolveInheritedValueMember` 的分层语义，同时保持 class 限定
  访问实例成员的既有 UNSUPPORTED 边界）；全部层 miss 落入既有 UNSUPPORTED fallback
  （detail 文案前缀 `Static load route on GDCC class` 保持不变，尾部补枚举常量类别）。
  `resolveMethodReference` 与 `ClassRegistry.findStaticPropertyInHierarchy` 保持原样
  （前者仍服务 engine/builtin static load 与 call 路线，后者仍服务 backend 静态存储），
  既有回归确认无测试锚定旧的整链类别优先顺序。
- 组延续拦截在 `reduce` 主循环内以局部变量 `enumGroupContinuation` 实现（不改
  `ReceiverState` 数据模型）：仅在前一 step 的已发布 fact `declarationSite instanceof
  GdScriptEnumGroup` 且当前为 `AttributePropertyStep` 且成员存在时拦截；拦截 fact 携带
  成员 declaration，状态自行清除（`Other.State.IDLE.JUMP` 的 `.JUMP` 按 int receiver
  失败）；状态为循环局部，天然保证跨 chain 隔离。
- `FrontendMatchSupport.isConstantPatternOperand` 已消费末 step fact 的
  `bindingKind == CONSTANT`，match pattern 零改动获得 `Other.State.IDLE` 常量操作数识别。
- `FrontendAssignmentSemanticSupport` 已将 `CONSTANT` bindingKind 的 member fact 分类为
  不可赋值目标，`State.IDLE = 5` 拒绝路径零改动获得。

测试记录（2026-09-20）：

- `FrontendChainReductionHelperTest` 新增 12 个 helper 级用例（总计 51）：value 路线常量
  命中 / 成员 miss 完整 fall through / subscript 合成 step 恒等防护 / 非组绑定遮蔽不触发 /
  跨类常量与组解析 / 继承常量 + 跨类别 nearest-layer-wins 遮蔽（父类 static property 与
  static method 两种）/ 组延续拦截恰好一次 / 组延续 miss 与 call 走 Dictionary route /
  未知名与嵌套限定符维持 UNSUPPORTED / 近层实例成员终端遮蔽 / 同层探测顺序
  （static 方法优先于枚举常量）/ 继承环 visited 防护终止。
- 新增 `FrontendEnumChainBindingTest`（22 个集成用例）：类内 value 路线（成员常量 fact +
  `:=` 推断 int）、Dictionary 方法 route 与 method-reference 不吞没、成员名遮蔽 Dictionary
  方法的边缘规则（读命中成员、调用走 Dictionary）、成员 miss 的 `sema.member_resolution`、
  int receiver 后续失败、inner class type-meta 路线、inner class 裸组值隔离与
  `State.keys()` 的 `sema.call_resolution` 边界、CONSTANT member 赋值拒绝、跨类
  `Other.State.IDLE`/`Other.IDLE`/继承成员/组链尾、跨 module 两 unit 用例、跨类组
  Dictionary route（含 `Other.State["IDLE"]` subscript 不折叠）、跨类 miss、组延续拦截后
  int receiver 失败（`+ 1` 二元后缀对照）、跨类别遮蔽锚点、近层实例成员终端遮蔽
  （`Other.VALUE` 不泄漏祖先枚举常量、`Grand.VALUE` 直达作对照）、`Other.MISSING` 与
  `Outer.Inner.State.IDLE` 的 UNSUPPORTED 边界、组延续状态跨 chain 隔离、
  property initializer / parameter default / match pattern 常量操作数三个 domain 锚点。
- 回归：`gd.script.gdcc.frontend.sema.**` 与 `gd.script.gdcc.frontend.lowering.**`
  全绿（含 `Worker.VALUE` UNSUPPORTED 锚定不变）。

验收：

- chain 测试（`FrontendBodyOwnerProceduresChainBindingTest` 或新类）：
  - `State.IDLE` RESOLVED int + declaration 正确；
  - `State.keys()` 仍走 Dictionary 方法 route，`State.keys`（无括号）维持既有
    Dictionary method-reference 事实（不被枚举分支吞没）；
  - `State.MISSING` → `sema.member_resolution`（既有 Dictionary/builtin miss 路径）；
  - `State.IDLE.JUMP`、`State.IDLE.keys`：首步后按 int receiver 继续，不得再命中枚举分支；
  - inner class 内 `State.IDLE` 经 GDCC_ENUM static 分支 RESOLVED；
  - inner class 内裸 `State` 维持值隔离拒绝；inner class 内 `State.keys()` →
    `sema.call_resolution`（pseudoType `UNSUPPORTED_STATIC_RECEIVER` 既有映射）；
  - 跨类正向（修订新增）：
    - `Other.State.IDLE` → RESOLVED int + `declaration=GdScriptEnumConstant`；
    - `Other.IDLE` → RESOLVED int；`Other.PARENT_IDLE`（成员声明在 `Other` 父类）→ RESOLVED；
    - 跨 module 文件的两类用例（`Other` 在不同 source unit）；
    - `Other.State` 链尾 → RESOLVED Dictionary + `declaration=GdScriptEnumGroup`；
    - `Other.State.keys()` 走 Dictionary 方法 route；`enum State {keys}` 场景下
      `Other.State.keys` 命中枚举成员、`Other.State.keys()` 走 Dictionary 调用；
    - `Other.State.IDLE + 1` 等 suffix 继续按 int receiver 解析；
    - 跨类别遮蔽：父类声明 static property/方法与同名子类枚举常量并存时，
      `Other.NAME` 命中子类枚举常量（nearest layer wins，见改动条款的顺序归一说明）；
  - 跨类 negative（修订新增）：
    - `Other.State.MISSING` → `sema.member_resolution`；
    - `Other.State.IDLE.JUMP`：`.IDLE` 拦截后 `.JUMP` 必须按 int receiver 继续并失败，
      不得再次从组解析——锚定组延续拦截状态即时清除（`Other.State.IDLE + 1` 是二元
      表达式，不经第三 attribute step，不能替代本锚点）；
    - 拦截状态跨 chain 隔离：前一条 chain 以枚举组结尾（`var g = Other.State`，最后 fact 为
      `GdScriptEnumGroup`），后一条无关 chain 的首个 property step 恰与组成员同名
      （`var v = Other.GROUP_ONLY`，类级无此常量）→ 后一条必须维持 UNSUPPORTED +
      `sema.unsupported_chain_route`，不得被残留组状态误拦截为成员命中
      （两条都以 `Other.State` 开头的用例不具区分性：各自的首 step 会覆盖残留状态）；
    - `Other.MISSING`（未声明名）→ 维持 `sema.unsupported_chain_route`（既有边界锚定）；
    - `Outer.Inner.State.IDLE` → 维持 `sema.unsupported_chain_route`（嵌套类限定符延后）；
  - 跨类 domain 锚点（修订新增，复用既有 island/domain 合同，不放宽任何边界）：
    - property initializer：`var x = Other.State.IDLE` → RESOLVED int；
    - parameter default：`func f(x = Other.IDLE)` → RESOLVED int；
    - match pattern：`match s: Other.State.IDLE:` → 被 `FrontendMatchSupport` 识别为
      常量操作数（末 step fact `bindingKind == CONSTANT`），而非普通运行时 expression pattern；
  - `State.IDLE = 5` 在 sema 拒绝（CONSTANT member target 分类）。
- 回归：`FrontendChainReductionHelperTest`、`FrontendBodyOwnerProceduresChainBindingTest` 不变红
  （特别核对 `analyzeSealsUnsupportedGdccStaticLoadAtBoundary` 的 `Worker.VALUE` 断言不变）。

### Step 6：CFG 物化

改动（`FrontendCfgGraphBuilder`）：

- `buildAttributeExpressionValue`（:2154-2175）在 type-meta head 判断之外新增「枚举常量组 head」
  判断：base 为 `IdentifierExpression` 且其 `symbolBindings()` 为 `CONSTANT` +
  `declarationSite instanceof GdScriptEnumGroup`，且首 step 为 `AttributePropertyStep` 且其
  `resolvedMembers()` fact 为 `RESOLVED` + `declarationSite instanceof GdScriptEnumConstant` 时，
  **不物化 base**，直接发 `MemberLoadItem(step, memberName, null, resultValueId)`
  （receiverless，与 type-meta head 同形），后续 step 从 int receiver 继续。
- 该分支不得挂 writable route；`State.IDLE = 5` 已在 sema 拒绝，CFG 不新增赋值路径。
- 裸 `State` / 裸匿名成员维持 `OpaqueExprValueItem` 不变。
- type-meta 路线（inner class）已天然走既有 `buildTypeMetaHeadMemberStep` receiverless 形态，零改动。
- **跨类 qualified 路线（2026-09 修订新增）**：`Other` 为 GDCC_CLASS type-meta head，首 step
  fact 为 `declarationSite instanceof GdScriptEnumGroup` 时：
  - 若紧随其后的 property step fact 为 `RESOLVED + declarationSite instanceof
    GdScriptEnumConstant`（组延续拦截命中），则**不物化组 Dictionary**，直接为该成员 step 发
    receiverless `MemberLoadItem`（枚举组 step 消除），后续 step 从 int receiver 继续；
  - 否则（链尾、call/subscript 延续）正常为组 step 发 receiverless `MemberLoadItem`，
    由 lowering 物化 Dictionary（`Other.State`、`Other.State.keys()` 等路线）；
  - 消除判断只消费已发布的相邻 step facts，不重新解析成员关系，不违反「lowering/CFG
    不得重扫语义」合同。
- 同步在 `frontend_lowering_cfg_pass_implementation.md` 补记 receiverless `MemberLoadItem`
  新增「枚举常量成员」与「跨类枚举组」两个来源（现合同只覆盖 type-meta 路线）。

验收：

- `FrontendCfgGraphBuilderTest` 新增：`State.IDLE` 无 base value item、产出 receiverless
  `MemberLoadItem`；裸 `State` 保持 opaque 表面；`Other.State.IDLE` 经组 step 消除产出单个
  receiverless `MemberLoadItem`（无 Dictionary 物化）；`Other.IDLE` 与 `Other.PARENT_IDLE`
  产出单个 receiverless `MemberLoadItem`；`Other.State` 链尾与 `Other.State.keys()` 保留组
  `MemberLoadItem`（不消除）；`Other.State["IDLE"]` 保留组物化并接续 subscript item。
- 回归：既有 CFG 测试不变红（含全局枚举 `Side.SIDE_LEFT` 路线）。

实施记录（2026-09-20）：

- value 路线：`buildAttributeExpressionValue`（`FrontendCfgGraphBuilder`:2167-2169）在
  type-meta 判断后接入 `isEnumGroupHeadAttributeExpression`（:4470-4478），命中即转入
  `buildEnumGroupHeadAttributeExpressionValue`（:2179-2191）：不物化组 base，经共享的
  `emitReceiverlessEnumConstantMemberLoad`（:2216-2230）发 receiverless
  `MemberLoadItem`，返回的 `ValueBuild` 携带 **null writable route**（计划合同）；后续
  step 经新提取的 `applyAttributeStepsFrom`（:2193-2209，三条路线共用）从 int receiver 继续。
- 跨类 qualified 路线：`buildTypeMetaHeadAttributeExpressionValue`（:2249-2265）在首 step
  分派前执行组消除——只读相邻两个已发布 step facts（`isResolvedScriptEnumGroupMember` /
  `isResolvedScriptEnumConstantMember`，:4479-4491），命中即为成员 step 发 receiverless
  load 并从 index 2 继续；链尾/call 延续走既有 type-meta 路径保留组 load。
- **与原文档的偏差（AST 形态修正）**：`Other.State["IDLE"]` 经 parser 为单个
  `AttributeSubscriptStep("State", ["IDLE"])`（组 fact 锚定在 subscript step 上），而非
  「property step + subscript step」。因此：
  - `reduceSubscriptStep`（`FrontendChainReductionHelper`:2271-2291）的容器 fact 发布过滤
    从「仅 PROPERTY」放宽为「PROPERTY 或 `declarationSite instanceof GdScriptEnumGroup`」，
    否则 subscript step 上没有任何组 fact 可消费（波及面已核对：body lowering 各消费点均以
    PROPERTY/PropertyDef 为条件，`resolveSubscriptContainerFacts` 在 `memberNameOrNull ==
    null` 时早退，不受影响）；
  - compile gate 不变量（`FrontendCompileCheckAnalyzer`:856-864）同步放宽为「RESOLVED
    container property **or script enum group** provenance」；
  - `buildTypeMetaHeadSubscriptStep`（:2320-2352）新增枚举组容器分支：receiverless 组
    `MemberLoadItem` + 普通 key `SubscriptLoadItem`，不挂 writable route（组为编译期常量）。
- `FrontendMatchSupport.isConstantPatternOperand`（:131-146）收窄末 step 形态为
  `AttributePropertyStep`：subscript step 上新增的组容器 fact（CONSTANT +
  `GdScriptEnumGroup`）描述的是容器而非下标结果，`Other.State["IDLE"]` / 运行时 key 下标
  不得被误判为 match 常量操作数（既有行为在放宽前由「subscript 从不发布 CONSTANT fact」
  隐式保证，现需显式排除）。
- 裸 `State` / 裸匿名成员维持 `OpaqueExprValueItem` 零改动；`Other.IDLE` /
  `Other.PARENT_IDLE` 经既有 `buildTypeMetaHeadMemberStep` 天然闭合，零改动。
- 文档同步：`frontend_lowering_cfg_pass_implementation.md` 8.2 节补记 receiverless
  `MemberLoadItem` 的「枚举常量成员」与「跨类枚举组」来源及组保留路线。

测试记录（2026-09-20）：

- `FrontendCfgGraphBuilderTest` 新增 8 个用例：`State.IDLE` 单 receiverless
  `MemberLoadItem` 且无 base item；裸 `State` 保持 `OpaqueExprValueItem`；
  `Other.State.IDLE` 组消除（含 inner class `Inner.Mode.ON` 同形锚点）；`Other.IDLE` 与
  `Other.PARENT_IDLE` receiverless；`Other.State` 链尾保留组 load；`Other.State.keys()`
  保留组 load + `CallItem(keys)` receiver 接续；`Other.State["IDLE"]` 保留组物化 +
  subscript item（锚定真实 AST 形态与 `memberNameOrNull == null` 的 plain subscript）；
  `Variant.Type.TYPE_NIL` 的两级访问 sema 未解析（维持既有 FAILED 边界），全局枚举回归锚点
  改用受支持的 `Side.SIDE_LEFT`（GLOBAL_ENUM type-meta 路线不触发任何脚本枚举分支）。
- `FrontendEnumChainBindingTest` 新增 `matchPatternRejectsEnumGroupSubscriptAsConstantOperand`：
  `match` 中 `Other.State["IDLE"]` 不得分类为常量操作数（正向 `Other.State.IDLE` 常量
  操作数锚点既有）。
- `FrontendCompileCheckAnalyzerTest` 的 subscript 不变量用例更名为
  `analyzeFailsFastWhenSubscriptStepMemberFactIsNotResolvedContainerProvenance`，消息断言随
  合同放宽同步（fail-fast 行为不变，仍锚定 DYNAMIC 事实被拒）。
- 回归：`gd.script.gdcc.frontend.**` 全绿（含 type-meta subscript 容器既有用例与
  `Worker.values[0]` 锚点）。

### Step 7：body lowering

改动：

- `FrontendOpaqueExprInsnLoweringProcessors` CONSTANT 分支（:149-169）新增：
  - `declarationSite instanceof GdScriptEnumConstant` → `LiteralIntInsn(resultSlotId, value)`；
  - `declarationSite instanceof GdScriptEnumGroup` → 按源码顺序为每个成员发射
    `LiteralStringInsn`（key，对齐 Godot 的 String key 事实）与 `LiteralIntInsn`（value）
    到临时 slot，最后
    `ConstructContainerLiteralInsn(resultSlotId, operands)`：
    - `ConstructContainerLiteralInsn` 操作数为 `List<LirInstruction.Operand>` 且必须全部是
      `VariableOperand`（key/value 交替），result slot 类型为 generic Dictionary；
    - 现有 session temp 分配器均为专用（`allocateGdScriptLanguageFunctionTemp` /
      `allocateWritableRouteTemp` / `materializeForLoopIntConstant`），**不得挪用**；
      新增专用分配 helper（如 `allocateEnumGroupLiteralTemp(purpose, type)`）；
    - 该物化路线作为 OpaqueExprValueItem 的常量展开，不经过 `containerLiteralPlans`
      （该 side table 只约束 AST `DictionaryExpression`），不违反既有合同；
  - 其余 declaration 形态保持 fail-fast。
- `FrontendSequenceItemInsnLoweringProcessors.FrontendMemberLoadInsnLoweringProcessor.lower`
  （:1131 起）在 receiverKind 分派**之前**插入：
  `RESOLVED && declarationSite instanceof GdScriptEnumConstant` → `LiteralIntInsn`。
  该分支同时覆盖 Step 6 的 value 路线 receiverless item、type-meta 路线的 receiverless item
  与跨类 qualified 路线的 receiverless item（三者 fact 形态一致）。
- 同处新增（2026-09 修订新增）：`RESOLVED && declarationSite instanceof GdScriptEnumGroup` →
  按与 opaque 分支**共享的发射 helper**物化 Dictionary（String key + int value 字面量序列 +
  `ConstructContainerLiteralInsn`）；覆盖跨类 `Other.State` 链尾与 Dictionary 延续路线的组
  `MemberLoadItem`。提取共享 helper 时同步改造 opaque 分支调用点，两处共用同一临时 slot
  分配器（`allocateEnumGroupLiteralTemp`）。MemberLoad 中其余 declaration 形态维持既有
  分派与 fail-fast 合同，不受枚举分支影响。

验收：

- lowering 测试（`FrontendLoweringBodyInsnPassTest` 等）：
  - 裸匿名成员 → `LiteralIntInsn`；`State.JUMP` → `LiteralIntInsn(5)`；
  - 裸 `State` → 成员数量的 String/int literal + 单条 `construct_container_literal`，
    操作数为 VariableOperand 交替序列；
  - 跨类（修订新增）：`Other.State.JUMP` → `LiteralIntInsn(5)`（无 Dictionary 物化）；
    `Other.IDLE` / `Other.PARENT_IDLE` → `LiteralIntInsn`；
    `Other.State` 链尾 → 与裸 `State` 同形的 literal 序列 + `construct_container_literal`；
    `Other.State["IDLE"]` → 组物化后接 subscript 读取（不经常量折叠，走 Dictionary 语义）；
  - 断言只针对 insn opcode 与 payload（对齐既有 lowering 测试风格）。
- 回归：`FrontendLoweringBodyInsnPassTest`、`CBodyBuilderPhaseCTest`、
  `CNewDataInsnGenTest`、`CLoadStaticInsnGenTest` 不变红。

实施记录（2026-09-21，production 部分）：

- 共享物化 helper 落于 `FrontendBodyLoweringSession.materializeEnumGroupDictionary`：与既有
  `materializeForLoopIntConstant` 同类（session 拥有 temp 分配与发射），opaque 与 MemberLoad
  两处共用。发射序列按 `GdScriptEnumGroup.members()` 源码序为每成员发射
  `LiteralStringInsn(key temp)` + `LiteralIntInsn(value temp)`，最后
  `ConstructContainerLiteralInsn(resultSlotId, 交替 VariableOperand)`；result slot 必须为
  `GdDictionaryType`（组常量在 skeleton 即注册为 generic Dictionary 类型，`construct` 的
  容器族由 result slot 类型推导），漂移即 fail-fast。不经过 `containerLiteralPlans`。
- 专用分配器 `allocateEnumGroupLiteralTemp(purpose, type)`（前缀 `cfg_enum_group_`）采用与
  `allocateGdScriptLanguageFunctionTemp` 相同的 skip-occupied 循环（该前缀是合法源码标识符，
  不得静默覆盖用户变量），不挪用 writable-route / boundary / for-range 分配器。
- opaque 侧：`FrontendOpaqueExprInsnLoweringProcessors` 的 CONSTANT 分支新增
  `GdScriptEnumConstant` → `LiteralIntInsn`、`GdScriptEnumGroup` → 共享 helper 两个形态，
  其余 declaration 维持 fail-fast。
- MemberLoad 侧：`FrontendMemberLoadInsnLoweringProcessor.lower` 在 DYNAMIC 早退之后、
  receiverKind 分派之前插入两个 RESOLVED 分支（`GdScriptEnumConstant` → `LiteralIntInsn`；
  `GdScriptEnumGroup` → 共享 helper）。统一覆盖 Step 6 三条 receiverless 路线
  （value / 跨类 type-meta / 组链尾），引擎枚举成员维持既有 TYPE_META `LoadStaticInsn`。

测试记录（2026-09-21）：

- `FrontendLoweringBodyInsnPassTest` 新增 7 个用例，共享断言 helper
  `assertEnumGroupDictionaryEmission`（String key / int value literal 数量与源码序、
  交替 VariableOperand 与 literal producer 连接、key/value 临时 slot 的 String/int 类型登记）：
  - 裸匿名成员 `JUMP` → `LiteralIntInsn(5)`（opaque CONSTANT 分支）；
  - `State.JUMP` → `LiteralIntInsn(5)`，且无 `construct_container_literal`、无
    `LoadStaticInsn`（value 路线折叠，不物化组、不碰运行时静态存储）；
  - 裸 `State` → 完整 Dictionary 物化序列，construct result 连接 return 且 slot 类型为
    `GdDictionaryType`；
  - `Other.State.JUMP` → `LiteralIntInsn(5)` 且无组物化（跨类消除在 body 层闭环）；
  - `Other.IDLE` / `Other.PARENT_IDLE` → `LiteralIntInsn(3/7)`（直接 + 继承常量）；
  - `Other.State` 链尾 → 与裸 `State` 完全同形的 Dictionary 物化序列；
  - `Other.State["IDLE"]` → 组物化后接 GENERIC `VariantGetInsn`（key pack 成 Variant 是该
    链形既有 subscript 物化形态，非 KEYED），`variantId` 锚定读取来源为物化的 Dictionary，
    且只有两个组值 literal、无折叠常量——Dictionary 语义保持。
- 评审整改后增补 3 个锚点：
  - `Side.SIDE_LEFT` → 唯一 `LoadStaticInsn("SIDE_LEFT")` 且无组物化（引擎枚举成员不被
    脚本枚举折叠分支改道）；
  - 同函数两次 `State` 物化 → 2 条 construct、8 个互不相同且已注册的 key/value 临时
    slot（专用分配器 counter 不回退）；
  - `Other.State.keys()` → 组物化 + `CallMethodInsn("keys")`，`objectId` 锚定调用发生在
    物化的 Dictionary slot 上，call result 连接 return（call 延续路线端到端闭环）。
- 评审整改：`materializeEnumGroupDictionary` 的 result slot 守卫强化为「generic
  `Dictionary[Variant, Variant]`」（`isGenericDictionary`），fail-fast 消息补 slot id；
  共享断言 helper 同步锚定 generic；生产/测试注释去除对计划步骤号的依赖。
- 回归：`gd.script.gdcc.frontend.**` 全绿；`CBodyBuilderPhaseCTest` /
  `CNewDataInsnGenTest` / `CLoadStaticInsnGenTest`（`gd.script.gdcc.backend.c.gen` 包）
  全绿。

### Step 8：`@export` 枚举 hint_string 生成（编辑器下拉支持）

依赖 Step 2/3（枚举元数据与 `GDCC_ENUM` type-meta 已可用）。前端生成 annotation value，
后端消费该 value 生成 `PROPERTY_HINT_ENUM` 注册——链路其余部分（annotations 透传、
`export_enum` → hint 映射、模板注册）均已存在。

改动：

- 前端 skeleton（`FrontendClassSkeletonBuilder.toLirProperty` / `applyPropertyAnnotations`
  附近）：
  - 场景：裸 `@export`（不含 `export_range` 等其他 variant）且 declared type 文本经
    `declaredTypeScope` 解析命中 `ScopeTypeMetaKind.GDCC_ENUM` 的 property；
  - 从 type-meta `declaration`（`GdScriptEnumGroup`）取成员表，按 Godot 格式
    （`gdscript_parser.cpp` `export_annotations()` 的 ENUM 分支）生成 hint_string：
    `capitalize(成员名):值` 逗号拼接；capitalize 对齐 Godot 行为（下划线转空格、
    逐词首字母大写），新增 `StringUtil` helper 承载；
  - 写入 `LirPropertyDef.annotations`：`"export" -> "<hint_string>"`（保持诚实 key，
    不合成用户未写的 `export_enum`）；
  - 时机保证：枚举预 pass（Step 2）先于成员填充，同类枚举组此时已可查；枚举求值失败
    （type-meta 未注册）→ 类型回退 Variant → 维持既有行为，不生成 hint；
  - 时机说明：不在 lowering 生成——lowering 时声明类型已擦除为 int，且 lowering 不得
    重新解释 AST 语义；lowering 透传同一 `LirPropertyDef` 对象，skeleton 写入即自动可见。
- 后端 `CGenHelper.renderPropertyMetadata`：裸 `export` 分支新增规则——property 类型为
  int 且 annotation value 非空 → `godot_PROPERTY_HINT_ENUM` + `GD_STATIC_S(hint_string)`；
  value 为空串维持现状（按类型推导/NONE）。若 `PROPERTY_USAGE_CLASS_IS_ENUM` 常量可用
  （`godot_global_enums.h` 由 extension API dump 生成），一并设置 usage 与
  class_name=枚举名以对齐 Godot PropertyInfo；不可用则只设 hint/hint_string
  （编辑器下拉仅依赖这两者），差异记入已知限制。
- 非目标：`@export_enum(EnumName)` 自动展开（Godot 同样不支持，注解参数是字符串）；
  引擎全局枚举（`GLOBAL_ENUM`）类型标注的 export hint（机制相同，留作扩展）；
  `@export_range` 等其他 variant 与枚举类型组合的行为不变。

验收：

- skeleton/annotation 测试：`@export var x: State`（`enum State {IDLE, JUMP = 5}`）→
  `annotations["export"] == "Idle:0,Jump:5"`；成员名含下划线的用例锚定 capitalize 规则
  （如 `STATE_IDLE` → `State Idle:0`）。
- backend 测试（`CGenHelperTest`）：裸 export + int + 非空 value →
  `godot_PROPERTY_HINT_ENUM` + hint_string 文本；value 为空串 → 维持现状映射。
- negative：求值失败的枚举 + `@export var x: State` → 无 hint、既有诊断不变；
  `@export_range` 与枚举类型组合行为不变。
- 回归：`FrontendClassSkeletonAnnotationTest`、`FrontendAnnotationUsageAnalyzerTest`、
  `CGenHelperTest` 不变红。
- 文档同步：`frontend_annotation_implementation.md`（:160）把「脚本 enum 导出当前不支持」
  改写为已支持并记录 hint_string 生成规则与 `StringUtil` capitalize 合同；
  `gdcc_c_backend.md` 若涉及属性注册 hint 映射则补记。

### Step 9：compile 全链路、既有测试更新与文档同步

改动：

- 更新 `FrontendClassSkeletonTest.buildEmitsExplicitDiagnosticsForDeferredTypeMetaSources`：
  `var from_enum: LocalState` 现解析为 int（不再是 deferred type-meta source），
  从该用例中移除枚举相关断言（`Alias`/`Preloaded` 断言保留），并新增独立的
  「枚举类型标注转正」正向用例。
- 端到端：`FrontendCompileCheckAnalyzer` 相关测试补「枚举 surface 不产生 compile blocker」；
  视环境可用性在 `src/test/test_suite` 增补枚举 compile/run 锚点（Zig 不可用时按既有约定跳过）。
  （2026-09-21 提前落地 test_suite 部分：新增 `enum/` 分组 7 对资源——
  `anonymous_member_values`（auto-increment/显式/负数/位运算/前序引用）、`named_member_access`
  （折叠常量参与算术/比较/for-range）、`group_dictionary_access`（`State["JUMP"]`/`keys()`/
  组 Dictionary roundtrip，Dictionary 形状断言在 validation 侧）、`cross_class_access`
  （inner class `Other.State.JUMP`/直接/继承常量/跨类 subscript 与 keys）、`type_erasure`
  （标注局部/参数/`is`/`Array[State]`）、`initializer_and_defaults`（属性初始化器与参数
  默认值）、`match_constant_patterns`（`match` 枚举常量 pattern 含跨类）；runner 新增
  `ENUM_SCRIPT_PATHS` 与 `compilesAndValidatesEnumScripts` 工厂，`EXPECTED_SCRIPT_PATHS`
  同步；本机 zig + Godot 4.5.2 下 8/8 通过。Step 9 其余条目不变。）
- 文档同步（同一批提交；实施时逐份核实再改，不只凭行号）：
  - `frontend_rules.md`：MVP 约定中「class constant 整体延后」拆写——类级枚举常量进入支持面，
    跨类枚举常量/枚举组限定访问同步支持（§1.3.5）；class `const`（含其跨类限定访问）仍延后；
    补枚举条目；并记录 bare TYPE_META misuse 诊断 owner 的
    **已知临时偏差**（现状由 expr analyzer 发 `sema.expression_resolution`，与 :18 冻结的
    `sema.binding` 合同不一致；后续独立任务以对齐 frozen 规则为目标迁移，本说明不是新的
    冻结 owner 合同）；
  - `diagnostic_manager.md`：`sema.class_skeleton` 补枚举声明诊断；
    `sema.unsupported_binding_subtree` 补函数体内枚举；交叉引用上述 TYPE_META owner 偏差；
  - `frontend_global_constant_implementation.md`：§5 的 CONSTANT 物化 declaration 白名单补
    `GdScriptEnumConstant` / `GdScriptEnumGroup`；§2.2 只改「class constant 收集与绑定整体延后」
    表述并交叉引用本文档——引擎类枚举裸名禁令行（如 `MOUSE_MODE_VISIBLE`）不动；
  - `frontend_chain_binding_expr_type_implementation.md`：改写「GDCC script class static load 与
    class-level `const` / enum 继承必须继续 UNSUPPORTED」条款（:298-302 等）——枚举成员 route
    与跨类枚举限定访问均已落地，class `const`、未声明名与嵌套类限定符仍延后；同文件其余
    class-constant 封口表述逐一审阅；补记组延续拦截的事实形态（前一 step fact 驱动）；
  - `frontend_top_binding_analyzer_implementation.md`：§2.3/非目标中 class constant binding
    延后表述更新为「类枚举常量已支持」；§5.3 category 覆盖补函数体内枚举；§6.3 消费边界更新；
  - `frontend_visible_value_resolver_implementation.md`：类常量可见性来源更新；
  - `scope_analyzer_implementation.md` / `scope_architecture_refactor_plan.md`：ClassScope 常量索引、
    继承 walk、`GDCC_ENUM` type-meta kind；
  - `scope_type_resolver_implementation.md`：枚举 type-meta 作为新的类型解析来源；
  - `inner_class_implementation.md`：词法 type-meta 可见性新增命名枚举来源，值隔离边界不变；
  - `frontend_parameter_default_implementation.md`（:51）：「source `const` 仍 deferred」
    拆写为「class `const` 仍 deferred；类枚举常量经既有 island 放行」；
  - `frontend_type_check_analyzer_implementation.md` / `frontend_compile_check_analyzer_implementation.md`
    / `frontend_container_literal_implementation.md` / `frontend_singleton_implementation.md`
    / `frontend_variable_analyzer_implementation.md`：逐一核实其中「class constant/enum 延后」
    表述，只拆写枚举部分，class `const` 边界保持原样；
  - `frontend_match_statement_implementation.md`：枚举成员 pattern 经 EXPRESSION 合同转正；
  - `frontend_static_var_implementation.md`：CONSTANT 与 PROPERTY 区分不变，补交叉引用；
  - `frontend_lowering_cfg_pass_implementation.md`：receiverless `MemberLoadItem` 合同补枚举来源；
  - `frontend_annotation_implementation.md`：见 Step 8（脚本 enum 导出 hint 已转正，
    该步承担其改写）；
  - `frontend_global_constant_implementation.md`（:54）：parameter default 行的 deferred
    表述与枚举常量放行对齐（裸全局常量合同本身不变）；
  - `frontend_implicit_conversion_matrix.md`（:174-175）：补记 GDCC 脚本枚举不是一等类型、
    声明类型直接擦除为 int，矩阵中 enum 行的 `N` 仅指一等 enum 转换模型；
  - `frontend_cast_expression_implementation.md` / `frontend_is_type_test_implementation.md`：
    补记枚举名 target 经 declared-type 擦除为 int；
  - `diagnostic_manager.md`：`sema.unsupported_chain_route` 的适用场景收窄为 class `const`、
    未声明名与嵌套类限定符的 GDCC static-load；`sema.member_resolution` 补跨类枚举组
    成员 miss 场景（与类内 `State.MISSING` 同路径）；
  - `superclass_canonical_name_contract.md` / `gdcc_facing_class_name_contract.md`：补记跨类
    枚举访问中 `Other` 链头的 source-facing → canonical 解析位置（既有 type-meta head 路线，
    不新增别名通道），交叉引用本文档 §1.3.5；
  - `frontend_type_check_analyzer_implementation.md` / `frontend_compile_check_analyzer_implementation.md`：
    补记跨类枚举成员 fact 为 `RESOLVED(CONSTANT, int)`，不产生 compile blocker，两 analyzer
    只消费其 int 事实不新增诊断。
- 本文档状态由「实施计划」改写为事实源（冻结已实现合同，移除步骤流水账）。

验收：

- 行为不变锚点：`State["X"] = 1` 不产生编译期写保护诊断（维持 subscript 写入现状）；
  `Worker.VALUE`（未声明名的 GDCC static-load）维持 `UNSUPPORTED` +
  `sema.unsupported_chain_route`（跨类枚举分支不吞掉既有 fallback）。
- `./gradlew clean build --no-daemon --info --console=plain` 全量通过。
- 定向回归清单（`script/run-gradle-targeted-tests.sh`，实施时以实际类名为准）：
  `FrontendClassSkeletonTest`、`FrontendScopeAnalyzerTest`、`FrontendSemanticAnalyzerFrameworkTest`、
  `FrontendBodyOwnerProceduresExprTypeTest`、`FrontendBodyOwnerProceduresChainBindingTest`、
  `FrontendVisibleValueResolverTest`、`FrontendChainReductionHelperTest`、`FrontendCfgGraphBuilderTest`、
  `FrontendLoweringBodyInsnPassTest`、`FrontendCompileCheckAnalyzerTest`、`FrontendVariableAnalyzerTest`、
  `FrontendInterfacePhaseTest`、`FrontendSuiteResolverTest`、`FrontendStaticContextValueRestrictionTest`、
  `FrontendInnerClassScopeIsolationTest`、`ScopeProtocolTest`、`ClassRegistryScopeTest`、
  `ScopeTypeMetaChainTest`、`FrontendTypeCheckAnalyzerTest`、`FrontendMatchSemanticsTest`、
  `FrontendMatchSupportTest`、`FrontendCfgGraphBuilderMatchTest`。

---

## 5. 风险与边界

1. **`MemberLoadItem` receiverless 形态与各入口路线的 receiverKind 语义**：枚举成员 fact 的
   `receiverKind` 按入口路线区分——类内 value 路线（`State.IDLE` 经 `reducePropertyStep`
   枚举分支）与跨类组延续成员 step（`Other.State.IDLE` 的 `.IDLE`）为 `INSTANCE`
   （源级 receiver 是值）；type-meta 路线（inner class `State.IDLE`）、跨类直接成员
   （`Other.IDLE`）与跨类组 fact（`Other.State`）经 `resolvedStaticLoadTrace` 发布，固定为
   `TYPE_META`。两条路线下 CFG 都产出无 base 的 receiverless item，因此 lowering 的
   枚举分支必须位于 receiverKind 分派之前（fail-fast 合同：INSTANCE 缺 base 会抛错）。
   该 item 形态扩展必须在 `frontend_lowering_cfg_pass_implementation.md` 补记。
2. **`ScopeTypeMetaKind.GDCC_ENUM` 穷尽分派**：新增枚举值会使所有无 default 的 switch
   编译失败（已确认 6 处，见 Step 3.4）。每处按语义补齐，不允许只补 case 过编译；
   实施第一步先 grep 全量分派点。
3. **求值器子集与 Godot 完整常量表达式的差距**：调用、attribute、三元、`**`、float 均拒绝。
   这是有意的 fail-closed 边界；拓宽时必须先扩展求值器测试，再放开语法面。
4. **类型标注的继承盲区**：子类 `var x: ParentEnum` 回退 Variant + warning。
   若未来补齐，需让 declared-type scaffold 与正式 ClassScope 的 type-meta 注册都沿继承链收集
   枚举 type-meta——这属于 type-meta 继承策略的独立决策（inner class 类型同样不继承），
   不在本计划内暗中解决。
5. **枚举 Dictionary 的可变性**：Godot 对命名枚举字典 `make_read_only()` 并共享单例；
   MVP 每次裸引用物化新 Dictionary，运行时改写不会影响其他引用点，与 Godot 存在
   可观察差异；MVP 接受该差异并文档化（若后续要对齐，需共享存储 + 只读标记，属独立工作）。
6. **声明顺序**：枚举值只允许引用同枚举前序成员与同类已收集（源码序更早）常量；
   Godot 对类级声明为两阶段、允许前向引用，本计划按源码序求值是有意收窄，诊断信息需明确。
7. **parser 依赖**：`EnumDeclaration`/`EnumMember` 形态由外部 `gdparser:0.5.4` 决定；
   空枚举、trailing comma、成员值缺失等边界形态需在 Step 2 先用解析测试锁定 AST 产物，
   再定诊断行为。
8. **半成品事实防范**：枚举预 pass 必须「单枚全部成功才落地」。若 type-meta 已注册而求值失败，
   `var x: State` 会静默解析为 int 而值绑定不存在——Step 2 的 negative 验收专门锚定该场景。
9. **bare TYPE_META misuse 诊断 owner 的既有偏差**：`frontend_rules.md` 冻结「top binding 发
   首条 `sema.binding`」，但现状代码由 expr analyzer 发 `sema.expression_resolution`
   （`tryPublishTypeMetaBinding` 不发诊断，`FrontendExpressionSemanticSupport` 产 FAILED fact）。
   该偏差先于本计划存在，影响所有 TYPE_META misuse（inner class 名误用等），不是枚举特有问题。
   本计划不扩大也不修复该偏差；是否把 owner 对齐回 frozen 规则属于独立决策，须单独评估
   既有测试基线后另行实施。
10. **跨类组延续拦截的次序敏感性（2026-09 修订新增）**：`Other.State.IDLE` 的正确性依赖
    「前一 step fact 为枚举组」的拦截判断；若 reduction 驱动的事实跟踪与 step 推进不同步，
    会把 Dictionary property miss 误折叠为成员命中或反之。必须由 Step 5 的正反用例锚定
    （成员命中折叠、`MISSING` 落 Dictionary miss、call 步不拦截、同名 `keys` 边缘规则、
    `Other.State.IDLE.JUMP` 第三 step 按 int receiver 失败、两条独立 chain 的状态隔离），
    且拦截状态不得跨链泄漏（每条 chain 独立）。
11. **跨类访问的 skeleton 就绪性前提（2026-09 修订新增）**：qualified 路线的安全性依赖
    「chain 阶段晚于模块级 skeleton 完成点」这一流水线顺序——届时目标类常量表必然已填充。
    该前提只覆盖 body 期消费；skeleton 期求值器（枚举 initializer）不在其保护范围内，
    继续按 §2.2 延后。若未来调整 phase 顺序（如 skeleton/body 交错），必须重新评估本路线。

---

## 6. 完成定义（DoD）

1. §2.1 支持面全部通过对应测试；§2.2 中带恢复行为的边界（发诊断 + 跳过的场景）在其锚定
   Step 有 negative 测试（正确 category + 子树跳过 + 兄弟存活）；延后/已知限制类边界
   （`@export` hint、Dictionary 运行时只读性等）以「文档化 + 行为不变锚点测试」验收，
   不要求诊断三件套。
2. §4 全部 Step 的验收细则执行完毕，回归清单不变红，全量 `clean build` 通过。
3. §4 Step 9 的文档同步清单全部落地；本文档改写为事实源。
4. 无新增 compiler-only 类型、无新 LIR 指令、无 backend 改动、无新 diagnostic category
   （若实施中发现必须新增，回到本文档修订并说明）。
