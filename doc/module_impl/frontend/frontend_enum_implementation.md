# Frontend Enum 实现约定（常量绑定路线）

> 本文档是 GDScript `enum` 特性在 frontend / LIR / backend 的长期事实源。设计方向：**enum 作为
> 常量绑定实现**——匿名枚举成员与命名枚举组都注册为类作用域中的只读 `CONSTANT` 绑定，成员访问
> 在编译期直接物化为整数字面量，命名枚举组本身在值上下文物化为 Dictionary 构造。
> 无新 LIR 指令、无 C 模板/运行时改动；backend 侧仅有 `CGenHelper` 的一条 hint 映射规则
> （Java codegen 侧）。本文档取代原实施计划文档，不再保留分步骤实施、进度记录与验收流水账；
> 合同变化时直接改写当前状态。

## 文档状态

- 状态：事实源维护中
- 适用范围：
  - `src/main/java/gd/script/gdcc/scope/**`（常量元数据 record）
  - `src/main/java/gd/script/gdcc/frontend/scope/**`（ClassScope 常量索引与继承 walk）
  - `src/main/java/gd/script/gdcc/frontend/sema/**`（skeleton 枚举预 pass、求值器、各 analyzer）
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`（CFG 与 body lowering）
  - `src/main/java/gd/script/gdcc/lir/LirClassDef.java`（常量表承载）
  - `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`（`PROPERTY_HINT_ENUM` 映射规则）
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `diagnostic_manager.md`
  - `frontend_global_constant_implementation.md`（全局枚举/常量裸访问事实源）
  - `frontend_top_binding_analyzer_implementation.md`
  - `frontend_visible_value_resolver_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `scope_architecture_refactor_plan.md`
  - `scope_analyzer_implementation.md`
  - `scope_type_resolver_implementation.md`
  - `inner_class_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_parameter_default_implementation.md`
  - `frontend_annotation_implementation.md`（`@export` 家族合同）
  - `doc/test_suite.md`（枚举 e2e 分组）

## 1. 维护合同

- 本文档覆盖脚本枚举的求值、注册、绑定、lowering 与 `@export` hint 合同；通用的 phase owner
  划分、恢复约定与诊断 category 规范以 `frontend_rules.md` 与 `diagnostic_manager.md` 为准。
- 合同变化时必须同步：本文档、`FrontendClassSkeletonBuilder` / `FrontendEnumConstantEvaluator` /
  `ClassScope` / `FrontendChainReductionHelper` / `FrontendCfgGraphBuilder` /
  `FrontendBodyLoweringSession` / `CGenHelper` 的相关 `///` 注释，以及 §9 的测试锚点。
- Godot 行为基线固定为 4.5.x（`gdscript_parser.cpp` / `gdscript_analyzer.cpp` /
  `ustring.cpp` / `char_utils.h`）。

## 2. Godot 4.x enum 语义基线（对齐目标）

- `enum {A, B = 5, C}`（匿名）：成员是注入当前类作用域的 int 常量（`A=0, B=5, C=6`），可裸访问。
- `enum State {IDLE, JUMP = 5}`（命名）：`State` 是一个 **Dictionary 常量**（等价于
  `const State = {"IDLE": 0, "JUMP": 5}`，key 为 String——Godot 4.5 `gdscript_analyzer.cpp` 以
  `dictionary[String(...)] = value` 构建并 `make_read_only()`），成员经 `State.IDLE` 访问；
  命名枚举成员**不**注入当前作用域。
- 未赋值的成员 = 前一成员值 + 1，首成员默认 0；允许不同成员同值。
- 成员值必须是编译期可求值的 int 常量表达式（Godot 走完整 constant expression reduction）。
- 命名枚举可作类型标注：`var x: State` / `Array[State]`，实际类型为 `int`。
- 命名枚举支持 Dictionary 方法：`State.keys()` / `State.values()`。
- 枚举只在类体（含 inner class 体）合法；函数体内 `enum` 在 Godot 中是解析错误。
- 类常量/枚举沿继承链可见（子类可裸用父类枚举）；跨类限定访问（`Other.State.IDLE`、
  继承后的 `Other.PARENT_IDLE`、`Other.State` Dictionary 值）在 Godot 中是一等能力。

## 3. 总体架构

```text
EnumDeclaration（类体，含 inner class 体）
  └─ skeleton 枚举预 pass（FrontendClassSkeletonBuilder + FrontendEnumConstantEvaluator）
       ├─ 冲突校验 → 求值成员常量表达式（受限子集，见 §4.4）
       ├─ 全部成功才落地事实；失败发 sema.class_skeleton + skippedSubtreeRoots，不留半成品
       └─ 落地物：ClassDef 常量表条目 + declared-type scaffold 上的 GDCC_ENUM type-meta
            └─ scope phase
                 ├─ ClassScope 索引常量 → value 命名空间 ScopeValueKind.CONSTANT
                 ├─ 继承 walk 扩展到常量表
                 └─ 正式 ClassScope 注册命名枚举 type-meta
  消费端：
  ├─ 裸匿名成员 `IDLE` / 裸组名 `State` → top binding CONSTANT binding
  ├─ `State.IDLE` → chain binding 枚举成员 route → RESOLVED(CONSTANT, int)
  ├─ `Other.State.IDLE` / `Other.IDLE` → chain binding GDCC 类常量分支 → 同形 fact
  ├─ `var x: State` → ScopeTypeResolver → int（经 type-meta instanceType）
  └─ lowering：
       ├─ 裸成员 / `State.IDLE` / `Other.State.IDLE` / `Other.IDLE` → LiteralIntInsn
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
     成员未命中时完全 fall through 到既有 Dictionary/builtin 路径（不吞掉 `State.keys` 等
     Dictionary method-reference 合同）。
   - inner class 等词法可见但值隔离的上下文（type-meta 路线）：`State` 值查找 miss、
     type-meta 命中 GDCC_ENUM，`State.IDLE` 走 static-load 分支解析成员。
   两条路线发布同一形态的 member fact（`RESOLVED + CONSTANT + int + declaration=成员常量元数据`），
   lowering 统一物化 `LiteralIntInsn`，不构造 Dictionary、不产生 `LoadStaticInsn`。
5. **跨类限定访问（qualified 路线）**：`Other.State.IDLE` / `Other.IDLE`。
   chain 阶段晚于整个模块的 skeleton 完成点，目标类的常量表此时必然已填充完毕，因此
   跨类解析不存在求值序问题（该问题只影响 skeleton 期的枚举 initializer，见 §4.2）。
   - 链头 `Other` 经既有 type-meta head 路线解析为 GDCC_CLASS receiver，零改动；
   - `reduceGdccStaticLoad` 的 static 成员查找按**逐继承层统一 walk**（nearest layer wins，
     对齐 §4.5 遮蔽原则）：每一类层按「static 方法 → static property → 枚举常量/枚举组
     （`getScriptConstants()`）」顺序查找，首层命中即停——
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
     → 维持既有 UNSUPPORTED fallback 与 `sema.unsupported_chain_route`。

## 4. 语义合同

### 4.1 支持面

| 场景 | 示例 | 结果 |
|---|---|---|
| 匿名枚举成员裸访问 | `enum {IDLE, RUNNING}` → 函数体内 `IDLE` | `CONSTANT` binding，int，`LiteralIntInsn(0)` |
| 显式值与自动递增 | `enum {A = 5, B, C = A + 1}` | `A=5, B=6, C=6` |
| 一元/二元常量表达式 | `enum {MASK = 1 << 3, NEG = -1, ALL = 0xF0 \| MASK}` | 受限求值器（§4.4） |
| 引用同枚举前序成员 | `enum {A = 1, B = A + 1}` | 允许 |
| 引用同类前序枚举常量 | `enum {A} enum {B = A}` | 允许（查 ClassDef 已收集常量） |
| 引用全局常量/全局枚举裸成员 | `enum {NIL = TYPE_NIL, E = OK}` | 允许（查 `ClassRegistry`） |
| 命名枚举成员访问 | `State.IDLE` | 编译期 int 常量，`LiteralIntInsn` |
| 命名枚举作 Dictionary 值（声明类及子类内） | `print(State)`、`State.keys()`、`State["IDLE"]` | 每次求值物化新 Dictionary（key=String，对齐 Godot 源码；Godot 另将字典 `make_read_only()` 且共享单例，差异见 §8）；方法调用与 subscript 走既有 Dictionary route |
| 命名枚举作类型标注 | `var x: State`、`var a: Array[State]`、`func f(p: State)` | `int` / `Array[int]`；仅限声明类的词法作用域（含其 inner class 的词法链） |
| `@export` 枚举类型标注 | `@export var x: State` | skeleton 生成 `PROPERTY_HINT_ENUM` 用 hint_string（`Idle:0,Jump:5` 格式），编辑器出下拉（§6） |
| 类型推断 | `var x := State.IDLE` | `int` |
| 继承可见性（值侧） | 子类裸用父类匿名成员 / 命名枚举 | `resolveInheritedValueMember` 覆盖常量表（仅限 body 值查找；initializer 引用父类常量见 §4.2） |
| 跨类限定成员访问 | `Other.State.IDLE`、`Other.IDLE`（含目标类继承来的成员） | 编译期 int 常量，`LiteralIntInsn`；`reduceGdccStaticLoad` 枚举分支 + 组延续拦截（§3 决策 5） |
| 跨类枚举组作 Dictionary 值 | `print(Other.State)`、`Other.State.keys()`、`Other.State["IDLE"]` | 每次求值物化新 Dictionary，方法调用与 subscript 走既有 Dictionary route |
| inner class 内访问外层命名枚举成员 | inner 体内 `State.IDLE` | type-meta 路线（§3 决策 4） |
| static 上下文 | `static func f(): return IDLE` | `ResolveRestriction.allowClassConstants` 允许（static/instance 均为 true） |
| property initializer / parameter default | `var x = State.IDLE`、`func f(x = IDLE)` | **有意放行**，两条路径分别成立：property initializer 经 shared `Scope.resolveValue(...)` class-scope lookup 命中静态只读枚举常量（不经 `FrontendVisibleValueResolver`；island 只拦截 self/实例成员，不拦截类常量）；parameter default 经 `PARAMETER_DEFAULT` domain 命中（拦截参数/局部/capture；`self` 与实例成员仅在 instance 方法默认值中允许，static 方法禁止——既有合同不变） |
| match pattern | `match s: State.IDLE:` / `IDLE:` | 走既有 LITERAL/EXPRESSION pattern 合同 |
| `is`/`as` 以枚举名为目标 | `x is State`、`x as State` | 擦除语义：经 declared-type 路径取 `instanceType` 得 int，等价于 `is int` / `as int`，不新增特判 |

### 4.2 明确不支持 / 延后（deferred boundary）

| 场景 | 行为 |
|---|---|
| 函数体内 `enum` | top binding `runUnsupported` 的 `EnumDeclaration` 分支发单条 `sema.unsupported_binding_subtree` error（锚定声明根）+ skip 子树；对齐 Godot（函数内 enum 是解析错误） |
| 跨类限定访问：class `const` 与未声明名 | `Other.FOO`（`const FOO = 5`）、`Other.MISSING`：class `const` 整体延后且其声明不进入常量表，与未声明名在 chain 阶段不可区分，统一维持既有 UNSUPPORTED fallback + `sema.unsupported_chain_route` |
| 嵌套类限定符 | `Outer.Inner.State.IDLE`：`.Inner` 在 GDCC static-load 路线中无 inner-class 成员分支，维持 UNSUPPORTED 延后 |
| 类型标注的继承可见 | 子类内 `var x: ParentEnum`：type-meta 查找纯词法不沿继承 walk，回退 Variant + `sema.type_resolution` warning；与 inner class 类型现状一致 |
| inner class 内的枚举 Dictionary 操作 | inner class 内仅 `State.IDLE` 可用；裸 `State` 值、`State.keys()`、`State["IDLE"]` 按既有值隔离/type-meta 合同拒绝（见 §4.6），**不**为枚举放开 inner-class 值隔离 |
| 成员值引用命名枚举成员 | `enum {A = State.IDLE}`：求值器子集不含 attribute 表达式，`sema.class_skeleton` error |
| 成员值引用 class `const` | `enum {A = FOO}`（`const FOO = 5`）：class `const` 整体延后，求值器查不到即发 `sema.class_skeleton` error |
| 成员值引用父类枚举常量 | `class Child extends Parent:` 内 `enum {NEXT = BASE + 1}`：skeleton 按源码序而非继承序填充类，求值器看不到父类常量表；发 `sema.class_skeleton` error（继承序求值属独立工作） |
| 成员值为非 int / 非受支持形态 | 浮点、字符串、bool、调用、三元、`**`、attribute、subscript 等：`sema.class_skeleton` error + 跳过该枚举 |
| 匿名/命名枚举成员的重复值 | `enum {A = 1, B = 1}`：**允许**（对齐 Godot），非边界 |
| 空枚举 `enum State {}` | `sema.class_skeleton` error（Godot 要求至少一个成员） |
| 枚举 Dictionary 的运行时只读性 | Godot 对枚举字典 `make_read_only()` 且共享单例；当前每次求值（裸 `State` 或 `Other.State`）物化新 Dictionary，`State["X"] = 1` 不做写保护（Godot 为运行时错误）；见 §8 |

### 4.3 数据模型（`gd.script.gdcc.scope` 包）

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

- `ClassDef` 的 `default getScriptConstants()` 返回空表——该表是脚本源常量通道（当前为 enum
  事实；未来 class `const` 复用）。`ExtensionBuiltinClass` / `ExtensionGdClass` 走 default 空表，
  **引擎/builtin 常量不进入此表**，继续走既有 extension metadata 通道
  （`findEngineClassConstantInHierarchy` 等）；只有 `LirClassDef` 覆写并提供
  `addScriptConstant(...)`。backend 不消费该表。
- `ScopeTypeMetaKind.GDCC_ENUM`：「GDCC 类声明的命名枚举用于类型位置」，`instanceType` 恒为
  `GdIntType.INT`，`pseudoType = true`，`declaration` 为 `GdScriptEnumGroup`（非空）。

### 4.4 枚举常量求值器（`FrontendEnumConstantEvaluator`）

位于 `gd.script.gdcc.frontend.sema.analyzer.support`。输入为 `EnumMember.value()` 表达式与
「同枚举已求值成员 + 同类已收集常量 + `ClassRegistry`」只读视图，输出 `long`。
整体 fail-closed：只有不携带任何运行期语义、可归约为 64 位 int 的形态被接受。

支持的表达式形态（递归）：

| AST | 语义 |
|---|---|
| `LiteralExpression`（integer） | 按 gdparser int lexeme 解析（`0x`/`0b`/`0o` 前缀与 `_` 分隔符），共享 helper 为 `StringUtil.parseGdIntegerLexeme(String): Long`（malformed/溢出返回 null；不含符号位——符号由 `UnaryExpression` 承担）。body literal lowering 的整数字面量与该求值共用同一 lexeme 语义；LIR 文本解析器的十进制 parse 属不同语法域 |
| `UnaryExpression` | `-` / `+` / `~` 作用于 int |
| `BinaryExpression` | `+ - * / % << >> & \| ^` 作用于 int；`/`、`%` 遇除数 0 报错 |
| `IdentifierExpression` | 依次查：同枚举前序成员 → 同类已收集常量（int 才可用）→ 祖先枚举名 blocker（见下）→ `ClassRegistry.findGlobalEnumValueByBareName` / 全局 int 常量；命中 GDScript 语言常量（PI 等 float）报「enum 值必须是 int」 |
| 其他 | 报错（`sema.class_skeleton`，锚定成员节点），整枚枚举跳过 |

- 自动递增：未赋值成员 = 前成员 + 1（首成员 0），按 `long` 语义。
- 算术遵循 Java `long` 语义（与 Godot int64 一致）：`+`/`-`/`*` 溢出回绕，`/` 向零截断，
  `%` 取被除数符号；除零拒绝。shift fail-closed：负数或 `> 63` 的 shift count 拒绝，
  与运行时 shift guard 对齐，不采用 Java 的距离掩码。
- **祖先枚举名 blocker（防穿透）**：skeleton 按源码序而非继承序填充类，求值器看不到父类
  常量值；但父类枚举常量名若与全局常量同名（如父类 `enum { OK = 123 }`，全局有 `OK`），
  直接落到全局 fallback 会静默求出错误的值。因此求值器在全局 fallback 之前先查
  「祖先枚举名集合」：预 pass 启动时对本类的 GDCC 祖先类（经 header discovery 的类图与
  sourceClassRelations 拿 AST，不要求祖先已完成求值）做一次纯结构扫描，收集其枚举声明的
  组名与匿名成员名；identifier 命中该集合即报 `sema.class_skeleton` error
  （「暂不支持引用继承的枚举常量」），不落到全局查找。引擎祖先不收集（其常量本就不可裸访问）。

### 4.5 命名空间注册矩阵

| 绑定 | 命名空间 | kind | type | declaration | 注册点 |
|---|---|---|---|---|---|
| 匿名成员 `IDLE` | ClassScope value | `CONSTANT` | `int` | `GdScriptEnumConstant` | `ClassScope` 索引 `ClassDef.getScriptConstants()` |
| 命名枚举 `State` | ClassScope value | `CONSTANT` | `Dictionary`（generic） | `GdScriptEnumGroup` | 同上 |
| 命名枚举 `State` | ClassScope type-meta | `GDCC_ENUM` | instanceType=`int` | `GdScriptEnumGroup` | skeleton 枚举预 pass 注册到 declared-type scaffold；`FrontendScopeAnalyzer` 在 `handleSourceFile`（顶层类）与 `handleClassDeclaration`（inner class）两处注册到正式 ClassScope |

- value 侧冲突校验在 skeleton 枚举预 pass 完成，保证 scope 阶段 `defineDirectValue` /
  `defineTypeMeta` 的 fail-fast 永不因用户代码触发。
- 继承：值侧沿 `ClassDef` 常量表 walk（`resolveInheritedValueMember` 覆盖常量）；type-meta 侧
  不继承（与 inner class 类型-meta 现状一致）。
- 遮蔽：callable-local `var`/参数/for iterator 遮蔽枚举常量沿用既有逐层 lookup；类常量遮蔽全局
  同名常量沿用「ClassScope 先于 ClassRegistry root」；枚举常量遮蔽父类同名成员合法
  （nearest wins）。
- 边缘语义：枚举成员名与 Dictionary 方法同名（如 `enum State {keys}`）时，`State.keys`
  命中枚举成员（枚举分支先于 builtin fallback）；`State.keys()` 调用步不受枚举分支影响，
  仍走 Dictionary 方法 route。跨类 qualified 路线沿用同一规则：`Other.State.keys` 命中
  枚举成员（组延续拦截），`Other.State.keys()` 走 Dictionary 方法 route。
- 跨类限定访问不引入新的注册：qualified 路线只消费目标类已发布的常量表事实，
  不向当前作用域注入任何绑定；`Other` 链头的 source-facing → canonical 解析沿用既有
  type-meta head 路线，不新增别名通道（见 `superclass_canonical_name_contract.md` §4.3 与
  `gdcc_facing_class_name_contract.md` §2.1）。

### 4.6 诊断 owner 与 category

不新增 category。各 category 语义以 `diagnostic_manager.md` 为准：

| category | owner | 场景 |
|---|---|---|
| `sema.class_skeleton` | skeleton（枚举预 pass） | 枚举成员重名、成员/组名与同类 property/signal/function/常量/inner class 冲突、值表达式不可求值或非 int、空枚举 |
| `sema.unsupported_binding_subtree` | top binding（`runUnsupported` 的 `EnumDeclaration` 分支） | 函数体内 `enum` 语句 |
| `sema.member_resolution` | chain binding（既有 FAILED member trace 路径） | `State.MISSING`：枚举分支只拦截已存在成员，miss fall through 到既有 Dictionary/builtin miss 路径；跨类 `Other.State.MISSING` 同路径（组延续拦截不命中 → Dictionary miss） |
| `sema.unsupported_chain_route` | chain binding（既有 UNSUPPORTED route 路径） | 跨类 `Other.FOO` / `Other.MISSING`：class `const` 与未声明名的 GDCC static-load 延后边界（枚举常量/枚举组不在本类目） |
| `sema.call_resolution` | chain binding（既有 FAILED call trace 路径） | inner class 内 `State.keys()` 等 pseudo-type 调用：`ScopeMethodResolver` 对 pseudoType 返回 `Failed(UNSUPPORTED_STATIC_RECEIVER)`，chain 映射为 `Status.FAILED` 后发此类目（与 `Variant.Type.keys()` 同类） |
| `sema.expression_resolution` | expr analyzer（既有 `TYPE_META` ordinary-value failed 路径，`FrontendExpressionSemanticSupport`） | 值位置消费枚举 type-meta（如 inner class 内裸 `State`）。该 owner 与 `frontend_rules.md` 冻结的 bare TYPE_META misuse 合同存在已知临时偏差，见 `frontend_rules.md` 与 §8 |

恢复合同按枚举位置区分两类：

- **类体（含 inner class 体）内的非法枚举**：skeleton 枚举预 pass 发诊断 +
  记入 `skippedSubtreeRoots()`（scope phase 消费该 side table），同类其他成员与同 module
  其他类不受影响。
- **函数体内的枚举**：body 阶段 `runUnsupported` 发诊断后由 statement resolver 结构性停止
  下钻（不为该子树发布任何 fact），**不写 `skippedSubtreeRoots()`**——该 side table 是
  skeleton→scope 的阶段间协议，body 阶段晚于 scope phase，写入无消费者。兄弟 statement
  照常发布事实。

### 4.7 与现有 route 的兼容性

- 全局枚举/常量五级 `resolveValueHere` 顺序不变；本特性只向 **ClassScope 层**注入命中，
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

## 5. 端到端链路

```text
enum State { IDLE, JUMP = 5 }            # skeleton 枚举预 pass: IDLE=0, JUMP=5
enum { RED, GREEN = State.JUMP }         # ❌ 拒绝：求值器不含 attribute 表达式
@export var weapon: State                # skeleton 生成 annotations["export"]="Idle:0,Jump:5"
                                         #   → backend 注册 PROPERTY_HINT_ENUM，编辑器出下拉
func f():
    var a = RED                          # 匿名成员：OpaqueExprValueItem → literal_int
    var b = State.JUMP                   # MemberLoadItem（无 receiver）→ literal_int 5
    var c = State                        # OpaqueExprValueItem → literal_string×2 + literal_int×2
                                         #   → construct_container_literal
    var d: State = State.IDLE            # 类型标注解析为 int
    var e = Other.State.JUMP             # 跨类：组延续拦截 → literal_int 5
    var f2 = Other.RED                   # 跨类匿名成员（含继承）→ literal_int
    var g = Other.State                  # 跨类组值 → 与裸 `State` 同形的 Dictionary 物化
```

## 6. `@export` 枚举 hint_string

- **触发条件**：裸 `@export`（无任何 `export_*` variant，即使堆叠显式 variant 也由其自行接管
  metadata），且声明类型文本经 source-facing type-meta 查找命中 `GDCC_ENUM`。
  `Other.State`、`Array[State]` 等限定/容器文本不生成 hint（无 hint_string 的裸 export 编码）。
- **生成时机**：skeleton（`applyScriptEnumExportHint`）——枚举预 pass 此时已发布 `GDCC_ENUM`
  type-meta，而 lowering 只能看到擦除后的 `int`。hint_string 写在诚实的 `"export"` key 下，
  不合成 `export_enum`。
- **格式**（Godot `gdscript_parser.cpp` `export_annotations` ENUM 分支 parity）：
  `capitalize(成员名):值`，按声明顺序逗号拼接，如 `"Idle:0,Jump:5"`。
- **capitalize 语义**：`StringUtil.capitalize` 是 Godot `ustring.cpp` `capitalize()` 的忠实移植——
  `_`/hyphen（`-`、U+2010、U+2011）/whitespace 归一为空格并分词；复合词边界拆分
  （aA、AAa·2Aa、A2·a2 三类，2aa 不拆）；逐词首字母大写；边缘 strip 去除 code point ≤ 32。
  遍历按 code point、大小写判定用 Unicode 分类（tokenizer 遵循 UAX#31），digit 判定为 ASCII。
- **后端映射**（`CGenHelper.renderBareExportPropertyMetadata`）：property 类型为 int 且
  `"export"` value 非空 → `PROPERTY_HINT_ENUM` + 该 hint_string；空值维持按类型推导的原映射。
- **已知限制**：Godot 额外 OR `PROPERTY_USAGE_CLASS_IS_ENUM` 并把 class_name 设为枚举限定名；
  gdcc 的 LIR 在类型擦除后不再携带枚举名，只发布 hint/hint_string（编辑器下拉仅依赖这两者）。
  该差异已记录在 `frontend_annotation_implementation.md`。
- 求值失败的枚举不发布 type-meta，`var x: State` 回退 Variant，自然无 hint。

## 7. CFG 与 body lowering 合同

- **receiverless `MemberLoadItem`**：枚举成员 fact 的 `receiverKind` 按入口路线区分——
  类内 value 路线（`State.IDLE`）与跨类组延续成员 step（`Other.State.IDLE` 的 `.IDLE`）为
  `INSTANCE`（源级 receiver 是值）；type-meta 路线（inner class `State.IDLE`）、跨类直接成员
  （`Other.IDLE`）与跨类组 fact（`Other.State`）经 `resolvedStaticLoadTrace` 发布，固定为
  `TYPE_META`。两条路线下 CFG 都产出无 base 的 receiverless item，因此 lowering 的枚举分支
  必须位于 receiverKind 分派之前（fail-fast 合同：INSTANCE 缺 base 会抛错）。
  该 item 形态扩展已在 `frontend_lowering_cfg_pass_implementation.md` 登记。
- **value 路线组 head 消除**：`State.IDLE` 不物化组 base，直接为成员 step 发 receiverless
  `MemberLoadItem`；返回的 build 不携带 writable route（`State.IDLE = 5` 已由 sema 拒绝，
  CFG 不新增赋值路径）。
- **跨类组消除**：首 step fact 为枚举组且紧随 property step fact 为组成员时，不物化组
  Dictionary，直接为该成员 step 发 receiverless item；否则（链尾、call/subscript 延续）
  为组 step 保留 receiverless load 供 lowering 物化 Dictionary。消除判断只消费已发布的
  相邻 step facts，不重新解析成员关系（「lowering/CFG 不得重扫语义」合同）。
- **裸 `State` / 裸匿名成员**维持 `OpaqueExprValueItem` 表面。
- **组 Dictionary 物化**（`FrontendBodyLoweringSession.materializeEnumGroupDictionary`）：
  成员按 `GdScriptEnumGroup.members()` 源码序，key 为 `String` 字面量（对齐 Godot
  `dictionary[String(name)] = value`）；结果槽必须是 skeleton 为枚举组注册的 generic
  `Dictionary[Variant, Variant]`——`construct_container_literal` 从结果槽类型推导容器族，
  类型漂移即协议违约 fail-fast。key/value scratch 槽使用专用分配器（`cfg_enum_group_*`
  前缀），与已发布 CFG value id、writable-route scratch、语言函数 temp 相互隔离；
  skip-occupied 循环保证合成字面量永不静默覆盖用户同名变量。
- 枚举组 subscript（`State["IDLE"]`、`Other.State["IDLE"]`）走普通 Dictionary subscript 语义：
  attribute-subscript step 携带 RESOLVED 容器 provenance fact，组 load 以 receiverless 容器
  形态发出后接普通 `base[key]` subscript。

## 8. 已知限制与设计权衡

1. **求值器子集与 Godot 完整常量表达式的差距**：调用、attribute、三元、`**`、float 均拒绝。
   这是有意的 fail-closed 边界；拓宽时必须先扩展求值器测试，再放开语法面。
2. **类型标注的继承盲区**：子类 `var x: ParentEnum` 回退 Variant + warning。
   若未来补齐，需让 declared-type scaffold 与正式 ClassScope 的 type-meta 注册都沿继承链收集
   枚举 type-meta——这属于 type-meta 继承策略的独立决策（inner class 类型同样不继承）。
3. **枚举 Dictionary 的可变性**：Godot 对命名枚举字典 `make_read_only()` 并共享单例；
   当前每次裸引用物化新 Dictionary，运行时改写不会影响其他引用点，与 Godot 存在
   可观察差异；接受该差异并文档化（对齐需共享存储 + 只读标记，属独立工作）。
   行为锚点：`State["X"] = 1` 不产生编译期写保护诊断，维持普通 subscript 写入语义。
4. **声明顺序**：枚举值只允许引用同枚举前序成员与同类已收集（源码序更早）常量；
   Godot 对类级声明为两阶段、允许前向引用，当前按源码序求值是有意收窄，诊断信息需明确。
5. **parser 依赖**：`EnumDeclaration`/`EnumMember` 形态由外部 `gdparser:0.5.5` 决定；
   空枚举、trailing comma、成员值缺失等边界形态由解析测试锁定 AST 产物后再定诊断行为。
6. **半成品事实防范**：枚举预 pass「单枚全部成功才落地」。type-meta 已注册而求值失败的
   场景不存在——失败枚举既不落常量行也不落 type-meta，下游 phase 永不观察到半求值枚举。
7. **bare TYPE_META misuse 诊断 owner 的既有偏差**：`frontend_rules.md` 冻结「top binding 发
   首条 `sema.binding`」，但现状代码由 expr analyzer 发 `sema.expression_resolution`
   （`tryPublishTypeMetaBinding` 不发诊断，`FrontendExpressionSemanticSupport` 产 FAILED fact）。
   该偏差先于本特性存在，影响所有 TYPE_META misuse（inner class 名误用等），不是枚举特有问题；
   是否把 owner 对齐回 frozen 规则属于独立决策，须单独评估既有测试基线后另行实施。
8. **跨类组延续拦截的次序敏感性**：`Other.State.IDLE` 的正确性依赖「前一 step fact 为枚举组」
   的拦截判断；reduction 驱动的事实跟踪与 step 推进必须同步，否则会把 Dictionary property
   miss 误折叠为成员命中或反之。拦截状态不得跨链泄漏（每条 chain 独立）。
9. **跨类访问的 skeleton 就绪性前提**：qualified 路线的安全性依赖「chain 阶段晚于模块级
   skeleton 完成点」这一流水线顺序——届时目标类常量表必然已填充。该前提只覆盖 body 期消费；
   skeleton 期求值器（枚举 initializer）不在其保护范围内，继续按 §4.2 延后。若未来调整
   phase 顺序（如 skeleton/body 交错），必须重新评估本路线。
10. **`ScopeTypeMetaKind.GDCC_ENUM` 穷尽分派**：新增枚举值会使所有无 default 的 switch
    编译失败；每处必须按语义补齐，不允许只补 case 过编译。修改该枚举前先 grep 全量分派点。

## 9. 回归锚点

- 单元/语义测试类：
  - `FrontendEnumSkeletonTest`（枚举预 pass：求值、冲突、失败恢复）
  - `FrontendEnumScopeTest`（ClassScope 常量索引、type-meta 发布、`is`/`as` 擦除）
  - `FrontendEnumBodyBindingExprTypeTest`（top binding / 表达式类型接通）
  - `FrontendEnumChainBindingTest`（chain 枚举成员 route、跨类限定访问、组延续拦截正反锚点）
  - `FrontendClassSkeletonTest`（deferred type-meta 来源诊断、枚举类型标注转正）
  - `FrontendClassSkeletonAnnotationTest`（`@export` hint_string 正反用例）
  - `FrontendCompileCheckAnalyzerTest`（枚举 surface 无 compile blocker、`State["X"] = 1`
    行为锚点）
  - `FrontendCfgGraphBuilderTest` / `FrontendLoweringBodyInsnPassTest`（receiverless item 形态、
    组消除、Dictionary 物化）
  - `StringUtilTest`（capitalize / int lexeme 的 Godot 边界）
  - `CGenHelperTest`（`PROPERTY_HINT_ENUM` 映射）
  - `FrontendBodyOwnerProceduresChainBindingTest.analyzeSealsUnsupportedGdccStaticLoadAtBoundary`
    （`Worker.VALUE` 既有 fallback 锚点）
- e2e：`src/test/test_suite/unit_test/{script,validation}/enum/` 8 对资源
  （anonymous_member_values / named_member_access / group_dictionary_access /
  cross_class_access / type_erasure / initializer_and_defaults / match_constant_patterns /
  export_hint），runner 工厂 `GdScriptUnitTestCompileRunnerTest.compilesAndValidatesEnumScripts`；
  合同见 `doc/test_suite.md`。
