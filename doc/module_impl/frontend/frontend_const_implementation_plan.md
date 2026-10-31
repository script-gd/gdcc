# Frontend const 常量支持实施计划（实施中）

> 本文档是实施计划，不是事实源。日期：2026-10-28（2026-10-29 起进入实施）。
> 文中除「§2 核实的代码基线」与「§13 执行记录」外均为建议方案，
> 建议与事实严格分开，不以本文档内容回写任何事实源文档。
> §12 的 A1 已由用户确认 earlier-only，不再是待确认项。
> 阶段状态：P0–P5 已实施（见 §10 各阶段条目与 §13 记录）；P6–P7 未实施。

- 适用范围（计划触及，均为相对仓库根路径）：
  - `src/main/java/gd/script/gdcc/frontend/constant/**`（新增值族与求值事实模型）
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/**`（求值器与运算支撑）
  - `src/main/java/gd/script/gdcc/frontend/sema/**`（skeleton 联合预 pass、local pipeline）
  - `src/main/java/gd/script/gdcc/frontend/scope/**`（ClassScope 常量索引与限定访问）
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`（CFG、body lowering、opaque expr 处理）
  - `src/main/java/gd/script/gdcc/lir/**`（现有 literal / construct 序列化精度与 String 修复，不新增 opcode）
  - `src/main/java/gd/script/gdcc/backend/c/**`（复用已有 constructor emission 与 String 长度感知物化，不新增 C 聚合 renderer）
- 关联文档（只读参考，不修改）：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/frontend/frontend_enum_implementation.md`
  - `doc/module_impl/frontend/frontend_global_constant_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_parameter_default_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/test_suite.md`

## 1. 目标与边界

### 1.1 目标

- 为 GDCC AOT 编译的脚本提供 `const` 支持：类级与函数 local 级常量声明，编译期求值并在
  使用处用现有字面量与受控分量构造链物化冻结结果。
- 复用并拓展现有 `FrontendEnumConstantEvaluator`（`src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/`），
  支持数值 / 布尔 / `String` / 全部向量与几何值类型。
- 目标是 GDCC AOT 语义，不要求与解释型 GDScript 全量 constant expression 一致，也不提供
  外部 ClassDB 常量可见性。浮点结果允许与 Godot 实现存在细微差异（double 中间计算、
  float 收窄的规范化值），但整数宽度 / 类型 / 内存布局不允许以浮点误差为由放宽。

### 1.2 v1 显式范围（决策 A）

- 声明形态：类体 `const` 与函数体 local `const`；**必须有初始化器**；`static const`、
  `@export const`、`@onready const` 不合法（按 parser 可表达形状检查并诊断）。
- 无标注时推断**精确类型**，不沿用 `var =` 的 Variant 默认。
- 支持类型（精确类型清单）：
  `int`、`float`、`bool`、`String`、`Vector2`、`Vector3`、`Vector4`、`Vector2i`、`Vector3i`、
  `Vector4i`、`Rect2`、`Rect2i`、`Plane`、`Quaternion`、`AABB`、`Basis`、`Transform2D`、
  `Transform3D`、`Projection`、`Color`。
- 显式标注只接受本清单的精确类型，以及已成功求值的命名 enum 的 int 别名；
  标注类型与 initializer 结果类型不一致时按既有 implicit conversion 规则转换 payload，
  不在本计划新增转换路径。
- 不承诺：`Variant`、`Object`、`null`、`StringName`、`NodePath`、容器（Array/Dictionary）、
  资源 / `preload`、type alias、utility 调用 / user call、以及未注册或不满足
  注册准入的成员方法调用（可扩展 registry seam 见 §5.5，生产默认注册表为空）。
- 引擎方法调用者拿到的返回值仍走普通返回 ABI；const 不是公开常量字段，不等于
  对引擎暴露 property/常量注册。

### 1.3 明确非目标

- 不修改解释型 GDScript 行为，不实现 ClassDB / 引擎常量查询。
- 不引入可变容器常量，因此无深度不可变容器问题。
- annotation export 参数保持现有 literal-only 合同，本计划**不**顺便扩展为 const 引用。
- 不修改 generated `godot_builtin_types.h`，不新增 ClassDB const / static property /
  `load_static` 运行时查值路径。
- `Variant` shortcut 构造不在本计划 const 范围内。

## 2. 核实的代码基线与文档差异

本节为当前代码事实（2026-10-28 复读源码核实），后续各节方案以本节为基线。

### 2.1 enum 求值器与类 skeleton

- `FrontendEnumConstantEvaluator` 位于 `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/`。
  返回 `Resolved(long)` / `Rejected(String)`；仅支持 integer 字面量、一元 `+ - ~`、
  二元 `+ - * / % << >> & | ^`，以及同 enum 前序成员与全局 int 常量标识符。
  shift 限定 0..63、除零拒绝、`long` 溢出回绕。现有 enum 兼容行为必须原样保留。
- `FrontendClassSkeletonBuilder.runEnumConstantPrePass` 在普通 member skeleton 之前运行：
  按 enum 源码顺序、仅可见同 enum 前序值；整枚成功才发布，失败不留半成品。
  named enum 组发布为 Dictionary 形状常量，type-meta = int。
- 常量表承载：`ClassDef.getScriptConstants` / `LirClassDef.addScriptConstant` 与
  `GdScriptClassConstant(name, GdType, Object declaration)`，当前仅 enum 使用。
- `ClassScope` 已有 `CONSTANT` 绑定：只读、静态可见、继承按 nearest-layer。
  qualified 链 `FrontendChainReductionHelper.findEnumConstantOnLayer` 显式只接受 enum
  declaration 形状，必须泛化；lowering 侧 `FrontendOpaqueExprInsnLoweringProcessors`
  及 member 处理同样按 declaration 形状分发，需同步改。

### 2.2 类/局部普通 const 未贯通点

- 类 skeleton 只处理 `VAR` member；`const` member 不进入 name reservation 与常量表。
- local 侧：`FrontendVariableAnalyzer` 对 `const` 报 unsupported；
  `FrontendInterfacePhase`（body local declaration 接口）只收 `VAR`；
  `FrontendBodyLocalDeclaration` 只有 `ORDINARY_VAR` / `ITERATOR` / `PATTERN_BIND`；
  `FrontendVisibleValueResolver` 将 const 设为 deferred 边界；
  `FrontendStatementResolver` 只处理 `VAR`；`FrontendCompileCheckAnalyzer` 跳过非 `VAR`；
  `FrontendCfgGraphBuilder` 只处理 `VAR`。

### 2.3 共享 pipeline 与 stable side table

- Shared 阶段顺序：skeleton → parser-error annotator → scope → variable inventory →
  Interface → lambda identity → SuiteResolver → await fixedpoint → diagnostics-only →
  compile check。
- Suite owner 顺序：`TOP_BINDING` → `LOCAL_TYPE_STABILIZATION` → `CHAIN_BINDING` →
  `EXPR_TYPE` → feature owner → `VAR_TYPE_POST` → flush。
- stable side table 不允许在 body runner 直接写：必须经 pending/committed overlay →
  单 owner patch → callable export。嵌套 suite 继承 typed env；export 非原子，
  失败丢弃 generation。
- `FrontendTypedLexicalEnvironment` 当前仅 `LOCAL` 支持类型有效视图/更新；不能直接把
  `CONSTANT` 丢进 var 的 resetLocalType 路径。
- `FrontendSuiteResolver.resolveCallableOwner` 对 lambda 创建 **parentless** typed
  env（普通 block child 有 parent）；lambda 不能笼统声称共享 typed parent 读取。

### 2.4 默认参数 / annotation / match

- 默认参数是普通表达式、每次缺参调用时执行（现状机制，非 const-only）；
  参数默认表达式不能引用 body local、参数、captures。
- annotation export 参数现为 literal-only。
- match key 当前可读取已有 literal 与 enum `CONSTANT`；普通 const 接通后按 §7 扩展。
  enum Dictionary 下标（`State["IDLE"]`）不是编译期常量。

### 2.5 ABI 与 builtin 布局

- ABI：标量 `int` = int64、`float` = double；`Vector*i` / `Rect2i` 组件为 int32；
  几何 `real_t` 与 `Color` 组件为 float32。只有 `float_64` 平台（指针 64 位，
  **不**表示 `real_t` 为 double）。
- generated `godot_builtin_types.h` 禁止手改。
- `Basis` 物理存储为 rows；`Transform2D` / `Projection` 为 columns。构造语义的
  `Basis` 轴向量与物理行不能照抄，必须以非对称数据验收。

### 2.6 LIR / C backend / 词法

- LIR 现有 `literal_bool` / `literal_int` / `literal_float` / `literal_string` 与
  `ConstructBuiltinInsn` 等，无 geometry literal。`construct_builtin` 是运行时
  API 构造（参数可为已求值分量）：本方案用它物化冻结结果，不等于重新执行
  原 AST。当前无成员函数 registry；API `is_const` 不自动证明纯度。
- `SimpleLirBlockInsnParser` 的 `FLOAT` / `GENERIC` 用 `Float.parseFloat`，完整 double
  会丢精度。
- `NewDataInsnGen` / `CFloatLiteralSupport` 已处理普通字面量与 `NAN` / `INF`。
- `String` 在 runtime 是 opaque，物化仍需 Godot 构造（这不是重新执行 AST）；
  现有 `utf8_chars` 以 NUL 终止，会截断嵌入 NUL。
- `CBodyBuilder` 的拥有 / 借用 / 拷贝 / 销毁合同必须保留。

### 2.7 metadata 与既有工具

- API 元数据：`src/main/resources/extension_api_451.json`，gdparser 0.6.0。
- 现状：scalar mixed int/float 二元运算被 frontend 主动拒绝，const 不能私自放行。
- 构造器选择可复用同包 `FrontendConstructorResolutionSupport` 的 exact metadata 选择。
- 词法工具可复用 `StringUtil.parseGdIntegerLexeme` / `parseGdFloatLexeme` /
  `decodeGdStringLexeme`。signed 数是 `Unary` 节点：必须处理 `long` 最小负值词法边缘，
  不可先 parse 绝对值导致溢出后错误拒绝。

### 2.8 文档差异说明

- 旧文档中「match 未完成」「default island 未完成」「旧 gdparser 版本」等描述属历史差异；
  本计划不修改这些事实源、不让它们假装已支持 const。以 §2.1–§2.7 的代码事实为准。

## 3. 语义矩阵

上下文分两类：**initializer 上下文**（const 声明的初始化器，class 侧受源码顺序
约束）与**普通读取上下文**（函数体 / 类 body 普通表达式，不受成员书写顺序限制）。

### 3.1 声明与标注

| 场景 | class const | local const | 说明 |
|---|---|---|---|
| 声明无 initializer | 拒绝 | 拒绝 | const 必须初始化 |
| `static const` / `@export` / `@onready` | 拒绝 | n/a | 按 parser 可表达形状诊断 |
| 无标注 | 推断精确类型 | 推断精确类型 | 不沿用 `var =` Variant 默认 |
| 标注为本清单精确类型 / enum int 别名 | 允许，按 implicit conversion 转 payload | 同左 | 不新增转换路径 |
| 标注为 Variant/Object/容器/资源等 | 拒绝 | 拒绝 | 不在 v1 |
| same-scope 与 var / function / signal / const 同名 | 拒绝 | 拒绝 | `claimedMemberNames` 冲突诊断（§6）；合法继承 shadow 除外 |
| 声明后失败 const 名字 | 名 mask 终止查找（§6） | terminal invalid，不退外层 | 不伪造成功值 |

### 3.2 initializer 上下文引用

| 场景 | class const initializer | local const initializer | 说明 |
|---|---|---|---|
| 引用本源码更早已求值 const / enum | 允许 | 允许（前序可见） | class 见 §6；local 见 §7 |
| 引用本类后序 const | 拒绝（A1 已确认） | n/a（按外层回退规则） | 见 §12 |
| 引用祖先 / 跨类 const | 拒绝（A1 已确认） | 允许（已可见的继承 / qualified 普通路线） | 见 §12 |
| 引用全局数字常量 / 裸 enum / builtin 类型常量 | 允许 | 允许 | builtin 经 §5.4 decoder |
| 引用 mutable local / 参数 / capture | n/a | 拒绝 | 即使字面量赋值也不是常量 |
| 声明前裸名命中外层同名 | n/a | 允许（沿用 var 外层回退） | `const X = X + 1` 可读外层 X |
| 引用本类更早 named enum 成员 `State.IDLE` | 允许（attr 读 `GdScriptEnumGroup` member，int，不物化 Dictionary） | n/a | enum initializer 旧 int AST 不接受 attribute |
| 引用本类 qualified `Current.C` | v1 仅 bare（qualified 写法不支持） | 允许（已有 qualified 普通路线） | 与跨类访问明确区分 |
| enum initializer 引用前序类 int const | 允许 | n/a | enum 仍 int-only 白名单 |

### 3.3 普通读取与其它场景

| 场景 | class body / 其它函数体 | local / lambda | 说明 |
|---|---|---|---|
| bare 读本类 const（含继承） | 允许，不受书写顺序限制 | 允许 | nearest-layer |
| `Other.CONST` / inner canonical qualified | 允许 | 允许 | qualified helper 泛化后 |
| inner class 看 outer 未限定值 | 不无条件可见 | n/a | 按既有 scope 规则 |
| lambda 内读 outer const | n/a | 允许，不进 capture | 见 §7.4 |
| 默认参数 / property initializer 引用 | 允许（仅常量引用折叠） | 拒绝引用 local const / 参数 / capture | 保持 call/default 机制 |
| match key 引用 | 允许 | 允许 | enum Dictionary 下标不算 |
| annotation 参数引用 | 拒绝（literal-only 不变） | 拒绝 | 本计划不扩展 |
| 写入 / `+=` / 成员 / 下标写回 | 拒绝 | 拒绝 | 见 §7.5 |

## 4. 类型值模型与求值接口（决策 D、E）

### 4.1 `GdConstantValue` 值族

- 新增中性共享包 `gd.script.gdcc.frontend.constant`，与 lowering/backend 解耦。
- `GdConstantValue` 为 sealed 值族：
  - `BoolValue(boolean)`
  - `IntValue(long)`
  - `FloatValue(double)`
  - `StringValue(String decoded)`（存解码后 payload）
  - `BuiltinValue(GdType, List<GdConstantValue> components)`
- `BuiltinValue` 约束：
  - 只允许 §1.2 清单中的受控 builtin 类型；
  - components 为 flattened scalar leaves，个数 / 类别 / 范围精确校验，`List.copyOf`；
  - float 几何 leaf 存 `(float)` 收窄后再提升 double 的规范化值；
  - int 几何 leaf 限 int32 范围。
- 禁止 payload：AST 节点、Scope 对象、C 字符串、Object 及任何可变结构。
- `type()` 由各 variant 确定，`BuiltinValue` 直接返回其 `GdType`。

### 4.2 声明与事实模型

- `GdScriptEvaluatedConstant(name, ownerCanonicalName, value)` 作为 class table declaration
  的新模型（record 字段不写注释，用类型与 `@param` 表达）；与 AST 的来源关联另经
  `declarationOrigins` 维护，不嵌回 record。
- local binding declaration 保持原 `VariableDeclaration` identity，不引入第二身份。
- `FrontendAnalysisData.constantFacts` 按**声明 AST 身份**存
  `Evaluation.Resolved(value)` / `Evaluation.Rejected(reason, offendingNode)`；
  freeze / guards / conflict / snapshot 机制完整接入；只存事实、不复制重新求值。
- 失败 class const 的名称 mask 另由 generation-owned `rejectedClassConstants`
  承载（§6），不进入 `constantFacts` / class table 的成功物化路径。

### 4.3 `FrontendConstantEvaluator` 接口

- 新求值器 `FrontendConstantEvaluator.evaluate(Expression, LookupContext, EvaluationPolicy)`
  放在原 support 包（`frontend/sema/analyzer/support/`）。
- 原 enum 入口 `FrontendEnumConstantEvaluator` 确定保留为薄适配层，转发到新
  evaluator 的 enum 专用 `EvaluationPolicy`，**不并存两套整数运算**；enum 旧
  AST 白名单与 lookup 行为原样保留。
- `long` 最小负值字面量按 §2.7 词法边缘在新通用模式处理；enum 模式先保持原
  拒绝行为，若同步放宽须显式新增回归预期与理由，不悄悄改。
- `LookupContext` resolver 对名字返回 resolved constant / nonconstant / blocked / unknown
  四态，按真实 scope 身份判定，不是仅按 String map 猜测；enum 前序视图为专用
  `EvaluationPolicy`。
- 求值器不依赖 Godot 运行时执行、不修改 AST、不发诊断；诊断由上层 owner 拥有。
- 成员方法调用走 §5.5 `FrontendConstCallableRegistry` seam；生产默认注册表为
  空，不得把「所有方法 AST 永远拒绝」写死导致 seam 无法运作。

## 5. 求值规则（决策 F、G、H、I）

### 5.1 运算白名单

- int：保留旧操作（一元 `+ - ~`，二元 `+ - * / % << >> & | ^`），新增**非负整数幂 `**`**，
  用整数重复平方法实现，不能经 `Math.pow` double 回转。
- 比较：相同精确类型的 int/float 同型比较（`== != < <= > >=`）。
- float：`+ - * / **` 与一元 `+ -`。
- 全部除法 / 取模零、非法 shift 一律拒绝。
- bool：严格 `not` / `and` / `or` 及 `== !=`。
- String：`+` / `== !=`，只按解码 payload；不实现格式化 / 排序 / 长度等操作。
- 一元 `+ -`：所有 vector / `*i` vector、Color、Plane、Quaternion 按各 metadata
  支持开放。
- vector / Color：同型分量 `+ - * /`；`*i` vector 额外 `%`；右侧标量支持 `* /`，
  左侧标量只支持 `*`；标量参数按 metadata 确列的 int/float 与返回类型（如
  `float * Vector2i => Vector2`）；不交换操作数、不支持 `scalar / Vector`，
  不触发仅 scalar-scalar 的 mixed guard；未实现的 pair 拒绝。
- `*i` vector / int 除法向零截断，余数符号同 dividend；除零 / 溢出仍拒绝。
- 其余 geometry 算术（矩阵乘法等）不扩展。
- geometry `== !=`：按分量 IEEE 语义比较，不用 `record.equals`（避免 Double NaN / -0
  语义差异）。
- 其余（几何乘法、矩阵求逆、旋转构造/转换）非 v1；未注册或不满足注册准入的
  方法 call 拒绝（§5.5），生产默认注册表为空。
- 整数语义：int64 运算保留旧回绕；int32 输入窄化 / 浮转整 / `*i` 算术溢出在 v1
  fail-closed 拒绝越界（不声称覆盖 Godot 全部窄化 / UB），测试覆盖端点。
- 浮点：中间以 double 计算，几何每个 primitive 结果收窄 float；`NAN` / `INF` / `-0`
  保留分类与符号并测试。
- scalar mixed int/float 二元运算仍拒绝（与现状一致），用户显式 `float(...)` 转换。

### 5.2 短路求值与条件表达式

- `and` / `or` / 条件表达式：先递归验证**整棵树**的白名单、名字绑定、普通
  类型合法性与已注册 call 的形状 / 签名 / 名字，禁止 dead branch 隐藏未注册
  call / 未知名，再仅计算选中分支（未选中分支的除零不执行；已注册而未选中
  的 branch 只验证不执行 handler，不触发 domain error / side effect）。
- 条件表达式只允许 bool 条件与两分支**同精确支持类型**，不新设计 join；该 subset 明示，
  不以全局优化名义改其它表达式。
- 将现有 private exact builtin operator type helpers 抽到同包纯
  `FrontendBuiltinOperatorSupport`，普通表达式与 const 共用；保留 mixed guard、
  不交换 operands。
- 构造器使用已有选择器 + 显式 purity whitelist，不把 API 表当纯函数表。

### 5.3 构造器准确白名单

按类型穷举，不能只写「所有构造器」：

- 每个几何类型：`T()` 与同型 copy `T(T)`。
- `VectorN`：N 个 float 分量；`VectorNi`：N 个 int（int32 范围内）；同维
  `VectorNi → VectorN` 与 `VectorN → VectorNi`（后者仅有限范围）转换。
- `Rect2` / `Rect2i`：两个同型 vectors、或四个对应 scalars、或互转（有限范围）。
- `Plane`：`(Vector3 normal, float d)` 与 4 float。
- `Quaternion`：4 float。
- `AABB`：`(Vector3 position, Vector3 size)`。
- `Basis`：3 `Vector3` axes。
- `Transform2D`：3 `Vector2` columns。
- `Transform3D`：`(Basis, Vector3)` 与 4 `Vector3`。
- `Projection`：4 `Vector4` columns。
- `Color`：3 float（alpha=1）/ 4 float / `(Color, float alpha)`。
- 标量构造：`bool()` / `int()` / `float()` 零参、同型 copy、数字与 bool 间转换
  （浮转整仅有限且范围内，toward zero）。
- `String`：`String()` / `String(String)`；不做字符串解析数字、不做泛型格式化。
- 不支持（各至少 1 反例）：axis-angle、`Basis(Quaternion)`、`Quaternion(Basis)`、
  `Color(String)`、`Transform3D(Projection)` 等其余 API 构造重载。
- 零参默认值（`T()`，与隐式默认一致）：
  - `int` = 0、`float` = 0.0、`bool` = false、`String` = ""；
  - `VectorN` / `VectorNi` / `Rect2` / `Rect2i` / `Plane` / `AABB` 全零；
  - `Quaternion` = (0,0,0,1)；`Basis` / `Transform2D` / `Transform3D` /
    `Projection` = identity；`Color` = (0,0,0,1)。
- canonical flat component 布局（`BuiltinValue.components` 顺序与个数）：
  - `Vector2/3/4(/i)`：coords（2/3/4）；
  - `Rect2` / `Rect2i`：position 然后 size（4）；
  - `Plane`：normal.x/y/z 然后 d（4）；
  - `Quaternion`：x, y, z, w（4）；
  - `AABB`：position 然后 size（6）；
  - `Basis`：逻辑 x / y / z columns（9）；
  - `Transform2D`：x / y / origin columns（6）；
  - `Transform3D`：basis（9）然后 origin（3），共 12；
  - `Projection`：x / y / z / w columns（16）；
  - `Color`：r, g, b, a（4）。

### 5.4 builtin metadata 常量与成员只读投影

- `FrontendBuiltinConstantDecoder`：不硬编码整 API palette，仅解析已选定 builtin
  metadata constants 的受限 constructor text（标量数值 / `inf` / `nan` + 固定 type +
  flat args），以 type/arity 映射 canonical 值。
- metadata 允许 6/9/12/16 scalar 表示 `Transform2D` / `Basis` / `Transform3D` /
  `Projection` 的内部 flat 编码；该语法**不**开放给用户 source constructor。
- 禁止把 `CBuiltinBuilder`（C backend）依赖拉回 frontend。
- decoder 遇到坏 shape 报明确 feature 拒绝 / 可定位 guard，不 eval 未知文本。
- 成员只读投影白名单（均为确切 API 属性名，不写 coords / axes 当 API 名）：
  - vectors：x / y / z / w；
  - `Rect2` / `Rect2i`：position / size / end（`Rect2i.end` 为 int32 逐分量加，
    超范围拒绝）；
  - `AABB`：position / size / end；
  - `Plane`：normal / x / y / z / d；
  - `Quaternion`：x / y / z / w；
  - `Basis`：x / y / z（轴列向量）；
  - `Transform2D`：x / y / origin；
  - `Transform3D`：basis / origin；
  - `Projection`：x / y / z / w；
  - `Color`：r / g / b / a；`h` / `s` / `v` / `r8` / `ok_hsl` 等**不**在 v1。
- 属性取值结果再次规范化（float leaf 收窄语义同 §4.1）。
- `Basis` canonical 按**逻辑列**保存（9 leaves：xcol, ycol, zcol）；物化时按
  §8.1 固定分量 recipe 以逻辑列交 Godot 分量 ctor（不在 frontend 做列→行
  转置）；各类型 flat component 的 arity 与顺序以 §5.3 canonical 表为准。

### 5.5 常量成员函数注册接口 `FrontendConstCallableRegistry`

- 定位：compiler 内部 registry，不是 `ClassRegistry`、不是运行时 ClassDB。
  `Builder` 注册，duplicate signature / 无效 metadata 为 programmer guard；
  `build()` / freeze 后 immutable；单次 analysis generation 共享同一实例
  （bootstrap / semantic driver 向 skeleton `LookupContext` 与 suite const
  `LookupContext` 显式传入）。无全局 mutable registry、无反射 / 插件自动
  发现。**生产默认注册表为空**，测试可注入条目证明扩展有效。
- exact key：`ConstCallableKey(ownerType GdType, methodName String,
  invocationKind INSTANCE_METHOD, List<GdType> parameterTypes)`（`List.copyOf`）。
  owner 是**已选择**的 builtin 方法所属类型，参数为已选 declared 固定参数
  类型；不按 name 或 Godot hash 单独匹配。当前仅受控 builtin 实例方法；
  static / utility / 用户方法 / Object / Variant dispatch 仍拒绝；
  `invocationKind` 仅作身份区分，不开放其它 kind。
- Entry = key + `returnType GdType` + pure `ConstMemberEvaluator` callback：
  `evaluate(GdConstantValue receiver, List<GdConstantValue> normalizedArguments)`
  → `ConstCallResult.Resolved(GdConstantValue)` / `Rejected(String reason)`。
  handler 不接 AST / Scope / Godot 运行时；source-free `ConstCallResult` 由
  AST evaluator 附 `offendingNode` / diagnostic anchor，源错误由上层 owner
  按 §9 发。unexpected exception / 错误返回 type 是 programmer invariant，
  不伪装源错误或 runtime fallback。handler 只做显式纯确定 Java 计算：不读
  场景 / 资源 / 时钟 / 随机 / 外部状态、不改 receiver / args；内建 trusted
  实现，不声称仅接口即可证明纯度。
- 准入流程：receiver 与所有参与计算的实参必须 const；先用
  `ScopeMethodResolver` 等底层 pure helper 与既有参数兼容 / 重载规则选出
  确切方法并冻结 selection，把 actual payload 转换到 selected declared
  param types，再查 exact registry。v1 fixed arity、无 varargs、**不补省略
  默认参数**（有默认值的方法可显式传全实参；省略支持以后单独加）。return
  type 必须对应选中 metadata 且在支持 value 类型内。API `is_const` 只作
  防误配证据，不是自动注册 / 纯度证明；不能把任意同名用户 method 套
  builtin handler。
- 阶段约束：class const 在 skeleton 时不能依赖稍后才发布的
  `resolvedCalls()` / `expressionTypes`——走 pure signature resolver 与
  source-name 遮蔽 / earlier-only context；local `CONST_RESOLUTION` 可核对
  effective CHAIN / EXPR 的 exact selection，不能二次选到不同方法或重写其
  facts。registry 不放开 A1 的跨类脚本常量依赖。
- AST 形状：允许 `AttributeCallStep` 作为受控 chain step；已注册结果可作为
  下一 step 的 receiver（如 `normalized().length()` 未来逐条注册后），但每
  step 都需授权且精确类型合法。enum 专用 policy 仍旧 int AST，registry 不向
  enum 开放 method。
- 失败语义：未注册或 purity / 签名不满足 → `Rejected`，无 runtime call
  fallback。生产空表下 `V.length()` 仍不支持；新增条目不改 evaluator
  dispatch。`Vector2/3.length` / `length_squared` / `normalized` / `dot` 等列为
  **候选、尚未实现 / 注册**，不作现阶段支持声明。

## 6. class/enum 联合预处理（决策 B、J）

- 将 enum prepass 扩展为 **const + enum 按原 statement source order 联合 prepass**；
  named enum 成功时其 type-meta 仍先于普通 member type 解析发布（合同不变）。
- **冲突 claims 与求值 lookup blockers 分离**（两套名集不混用）：
  - `claimedMemberNames`：初始仅本类 `VAR` / function / `_init` / signal /
    inner class；联合 prepass 按源码逐项处理 const / enum，先与不同声明已
    claim 名做冲突检查（排除自身 identity），再占名。const 即使求值失败也
    占名；enum 成功才按旧合同占组名 / 匿名 members，不把 named enum 内部
    成员提升类层。同名不同 identity 发 diagnostic，不抛 guard、不覆盖；
    合法继承 shadow 不一概拒绝。
  - `allDeclaredMemberNames`：全量 AST 结构视图（VAR / CONST / enum 暴露名 /
    function / signal / inner），**只供 lookup mask**；不拿它做 `contains(name)`
    自冲突检查；同名多声明保留 identity / 先行存活者。
- `LookupContext` blocker 语义：先 enum 前序 + 本类已发布可用 const / enum，
  再若本类全部 declared（含后序 / 失败 / nonconstant）命中即 terminal 拒绝，
  不落 global；祖先 blockers 扩到 const / enum / VAR / function / signal /
  inner names，沿 GDCC 继承关系 AST 结构扫、不依赖 fill 顺序；engine/native
  祖先从现有 `ClassRegistry` metadata 找同名 members / constant / type meta，
  冻结规则下不支持取值一律明确拒绝而非穿透全局。原
  `collectAncestorEnumNames` guard 已包含于新 blocker，不能删。source-name /
  builtin constructor 查找也尊重遮蔽身份。
- enum 旧合法 lookup 优先级、受限 int AST 白名单、前序值语义、整枚原子性保留；
  新增声明形状与漏拦截名字的 terminal guard 属**显式安全收紧**，新增对应
  负例，不靠删旧测试掩盖（不绝对声称 lookup 行为原样）。
- class initializer 已确认规则（A1 earlier-only，§12）：只允许引用本类源码更早已求值
  const / enum、全局数字常量 / 裸 enum、builtin 类型常量；脚本的前向、自引、
  继承、跨类依赖**显式拒绝**，不回退 global、不静默按文件序选值。
  - 允许引用本类更早 named enum 成员 `State.IDLE`（AST attr 读
    `GdScriptEnumGroup` member，类型 int，不物化 Dictionary）；enum
    initializer 旧 int AST 仍不接受 attribute；
  - 本类 qualified `Current.C` v1 仅支持 bare（qualified 写法不支持），与跨类访问
    明确区分；local / 普通 qualified const 读取支持全部已发布值。
- class body 内普通使用不受成员书写顺序限制，支持 bare、static、继承与
  `Other.CONST` / inner canonical qualified access（经泛化后的 qualified helper）。
- 本类自引 / 循环 / 后序名 / 失败声明 / 与 var / function / signal 同名：必须阻断
  global fallback；不得删除已有继承 enum blocker。
- inner class 不无条件看见 outer unqualified value。
- class const 求值失败的名称 mask（确切载体，不改公共 `ClassDef` /
  `ClassRegistry` 协议）：
  - `FrontendAnalysisData` 新增 generation-owned `rejectedClassConstants`：
    owner canonical class name → immutable `name -> FrontendRejectedClassConstant`
    （`VariableDeclaration` 声明身份、reason、diagnostic anchor）；
  - skeleton 为唯一 producer，经 prepass 私有 builder 收集，在 skeleton 结束、
    scope 阶段开始前 freeze 发布；跟随 analysisData snapshot / freeze / discard，
    bootstrap 默认空；
  - 成功值 table 不受影响；mask 绝不进入 constant materialize，不伪造零值 /
    Variant 成功值。
- 新增 frontend-only `FrontendClassConstantLookup` helper：单层
  `probe(classDef, name, rejectedClassConstants)` 返回 usable / terminal-blocked /
  miss；mask 先查，同层合法成员冲突仍由 skeleton 诊断；不能越过失败 mask 找
  ancestor。
- `FrontendScopeAnalyzer` 创建 `ClassScope` 时注入该只读 mask view（新增重载 /
  默认空保持其它现有构造用法）；`ClassScope` direct / inherited 每层 probe 命中
  mask 转现有 `FOUND_BLOCKED`；blocked `ScopeValue` 仅携 name + AST declaration
  身份 + `CONSTANT` / read-only + 恢复用 Variant 占位（从不 FOUND_ALLOWED /
  成功物化，不算伪造可用 Variant 值）。
- qualified 侧 `FrontendChainReductionHelper.ReductionRequest` 本有 analysisData：
  `reduceGdccStaticLoad` 传入同一 mask view、用同一单层 helper 结果；blocked 时
  发 BLOCKED trace、停止祖先搜索；`reduceStaticMethodStep` 前置同一逐层
  claim 走查——本层同名方法交给原 resolver，usable const 终止为 FAILED，
  masked const 终止为 BLOCKED，父类 static 同名方法不得穿透子类 const 占名。
- **mask 域边界（已确认）**：失败 const 的毒化域 = 常量可见域——裸标识符
  （`resolveValue`）与限定 type-meta 面（`Child.C`/`Child.C()`）。实例成员面
  `instance.member`/`self.member` 不属于 mask 域：该面经 `ScopePropertyResolver`/
  `ScopeMethodResolver`/`ScopeSignalResolver` 解析，本就不暴露任何常量
  （成功 const/enum 成员在 `self.C`/`d.C` 同样不可达），mask 域与常量可见域
  精确同构。因此父 `var hp` + 子 `const hp = MISSING` 时 `child.hp` 仍解析
  `Parent.hp`——`hp` 与 `self.hp` 的诊断差异是恢复模式下的已知边界，而非
  正确性漏洞（skeleton error 已致模块编译失败，分歧事实不进产物）。若未来
  实现 Godot 兼容的成功常量实例访问（`self.C`/`d.C`），mask 探针须随该面
  一并扩展到三个 resolver——属彼时的自然组成而非本阶段扩界。
- 不只在局部 reservedNames 保留 mask；不改 shared `ScopeLookupStatus`。
- 该项是 frontend 局部数据接线，不引入公共 registry 新体系，不扩大为 A1。
- 标注类型验证：class skeleton 是该声明标注的唯一验证 owner（local 侧由
  inventory 承担，规则见 §7.1）；`FrontendConstDeclaredTypeSupport` 三态结果
  下，错误标注报 error 并存 declaration validation rejection，不当未标注
  推断成功。
- 注解 placement 仍由既有 `FrontendAnnotationUsageAnalyzer` 唯一 owner
  （`@export` / `@onready` 及其已覆盖的其它注解）；class const prepass 不因
  注解 placement 单独 skip 整声明、不抢报；可独立计算合法 initializer 的值
  供 inspection，annotation error 仍挡编译且永不造 property；坏 initializer
  是独立根因可另报，但同一 placement 只一条。`static const` 若 parser 可
  表达，由 const 声明 owner 负责诊断。
- `FrontendInterfacePhase.recordPropertyInitializer` 增加 kind `VAR` 条件：
  class `CONST` 不进入 propertyInitializer suite / synthetic field init。
- class resolved 值落地三处：class table（`GdScriptEvaluatedConstant` declaration）、
  `declarationOrigins`、`constantFacts`。
- `ClassScope` 与 `FrontendChainReductionHelper` 泛化到新 record，保持 nearest-layer
  terminal 遮蔽语义。
- enum 引用 earlier class int const 需新增测试；前向依赖按 §12 冻结规则拒绝。

## 7. local pipeline、只读与 lambda（决策 C、K、L）

### 7.1 inventory / FrontendInterfacePhase / certificate

- variable inventory 绑定 `ScopeValue` 为 `CONSTANT` / read-only。
- 标注验证：local inventory 是该声明标注的唯一验证 owner（class 侧由 skeleton
  承担，见 §6）。新纯 `FrontendConstDeclaredTypeSupport.resolveDeclaredType(...)`
  返回 no annotation / resolved / rejected 三态，区分未标注 / 显式 `Variant` /
  类型未找到；只用 strict type lookup，不调会 warning + Variant 兜底的
  `resolveTypeOrVariant`。错误标注报 error 并存 declaration validation
  rejection；结构 baseline 必要时 `Variant`，但显式错误标注绝不能当未标注
  推断成功；`CONST_RESOLUTION` owner 只传播不重复，标注兼容 payload 转换在
  类型解析成功后由 const owner 执行。
- 未标注 const 用 `Variant` 作为**仅结构 baseline**（不代表运行时动态成功）。
- `FrontendInterfacePhase` 增加 `ORDINARY_CONST`；declaration index / certificate
  仅含 **surviving** local const（`scope.resolveValueHere(name).declaration() == decl`），
  校验 kind / identity / 顺序；保留 for iterator / match prefix 行为。inventory /
  Interface 扫描遇 duplicate 只报一次并 skip rejected duplicate；**已绑定但
  initializer 失败**的 const 仍 inventory + `Rejected`，与**重名根本未绑定**的
  const 区分——后者不强行补结构槽，compile 沿既有 upstream error 停止。
- 现有「支持位置 deferred 边界」只在完整链路接好后才删除转正，不能提前移除。

### 7.2 求值 owner 与 overlay

- `FrontendStatementResolver` 让 const initializer 走常规 TOP_BINDING →
  LOCAL_TYPE_STABILIZATION → CHAIN_BINDING → EXPR_TYPE pipeline。
- 新增 `CONST_RESOLUTION` feature owner，位置在 `EXPR_TYPE` 之后、`VAR_TYPE_POST` 之前
  （后者跳过 const 的类型写入）；以 effective env 读取前序 const 结果。
- owner 将 value / rejection 及最终声明 slot type 放 pending，经 flush → 单 owner
  patch → callable export。
- 嵌套 suite 沿 typed env parent 读取 parent pending/committed const 事实；lambda
  例外（§7.4）：其 typed env parentless，改经受限 `FrontendConstFactView` 链读取。
- 不能往 stable side table 直接写；不能让原 var `VAR_TYPE_POST` 改写 const。
- 精确类型采用 **export 时只读回填**（不改所有 scope API）：
  - `BlockScope` 新增 `resetConstantType(name, declaration, type)`：仅 `CONSTANT`
    且 declaration identity 匹配生效；仅允许 `Variant` baseline → 精确类型或
    同型幂等；保留 `constant=true` / `writable=false` / `staticMember` 等 flags；
    不得调用 `resetLocalType`；
  - `FrontendConstResolutionPatch` 携带独立 constant type update 列表；
    `FrontendAnalysisData.applyConstTypeUpdate` 在 callable export certificate
    完成后唯一调用，并刷新已发布 `symbolBindings` 的 `resolvedValue` payload；
    body runner 阶段仍仅 overlay，不提前写 scope；
  - 现有 `FrontendInterfacePhase` baseline / index 保留原 identity，不后来重跑
    certificate 把 export 精确类型误当 baseline；generation 分析流程同 var；
  - 导出后 `Scope.resolveValue` / `valuesHere`、`FrontendVisibleValueEnumerator`、
    `FrontendSnapshotCompletionService` 直接从 scope 读精确类型；`Rejected`
    不回填成功 type。

### 7.3 失败遮蔽与 compile gate

- 失败 const：保留结构 inventory + `Rejected` 事实；名字仍遮蔽、传播失败但不产生
  二次根因 diagnostic；compile 停止。
- 不在 body 之后删 scope，避免 certificate 失配。
- compile gate：`FrontendCompileCheckAnalyzer.handleVariableDeclaration` 与
  `scanSlotTypeCompileBlocks` 新增可达 local const 的 `Resolved` + final type
  一致检查；class 全集（含 unused）经 skeleton validation / compile gate 覆盖；
  不能只声明目标而仍 skip CONST。
- local const 名字规则：`const X = X + 1` 在声明前用 `X` 可命中外层（沿用 var 外层
  回退），无外层则诊断；声明后失败 const terminal invalid，不退外层。
- 旧 enum 模式继续 int-only AST 白名单；允许前序类 const 的 int identifier，但不自动
  开放 constructor / 属性 / float / 条件表达式给 enum；整枚原子性保留。

### 7.4 CFG 与 lambda

- const 声明在 CFG 中不 `buildValue(initializer)`、不创建 mutable
  `LocalDeclarationItem` / slot，可留 `SourceAnchor`；每次读 materialize frozen payload。
- lowering `CONSTANT` 分派：class `GdScriptEvaluatedConstant` 直接取 value；
  local `VariableDeclaration` 经 stable `constantFacts[decl]`（必须 `Resolved`）；
  统一经 `FrontendBodyLoweringSession.materializeConstantValue(block, value,
  resultSlotId)` 物化（§8.1）；`bind.declarationSite` 不得错当 class record，
  否则 fail-fast。
- 非法 const initializer 内的 lambda：`FrontendLambdaIdentityAnalyzer` 与
  `FrontendLoweringFunctionPreparationPass.collectLambdaContexts` 跳过所有 CONST
  initializer subtree；`FrontendVariableAnalyzer` 既有 lambda capture scanner 的
  const skip 保留；const initializer 内 lambda 不建 lambda plan / ordinal /
  capture / HRX shell。owner traversal 遇 lambda const-subtree 在纯形状检查
  定位后由单 owner 拒绝，不让 TOP_BINDING + CHAIN 分别 unsupported 同一
  lambda；非 lambda 普通 const 表达式沿原 pipeline；其它禁止的对象构造 /
  未注册或不满足 §5.5 准入的 call 只做 const 形状拒绝，不创建 runtime initializer。
- lambda 与 const 事实视图（兑现现有方案，不改 capture 总架构）：
  - 现有 `FrontendSuiteResolver.resolveCallableOwner` 对 lambda 创建 parentless
    typed env（§2.3），不能笼统说 lambda 共享 typed parent；
  - 新增只读 `FrontendConstFactView`：按 `VariableDeclaration` 身份读取
    `Evaluation` 结果；普通 suite 从 current pending → committed → stable →
    parent const view 取；
  - nested lambda 构造时从 outer effective env 抓取该受限 const-only view 链，
    支持跨多层 lambda 传递；use-site resolver 仍按声明顺序过滤；
  - lambda 通用 typed parent 保持 null；不允许 slotType / expressionType /
    mutable local 绕 capture 读取；只共享 immutable const 值 / 失败状态，
    不共享可变 slot；
  - 同一 outer statement 的 pending const 在合规引用时可读，但不放开
    self-initializer；
  - 外层 const 不进 capture；真实 mutable locals 仍走原 capture 路径；不能为读
    纯 const 捕获 self。

### 7.5 只读写回覆盖

- 覆盖 bare / qualified、`+=`、成员 / 下标的 POD writeback 链：沿最终 receiver
  provenance 追到常量根即拒绝（`C.position.x = ...`、`C[0] = ...`），不只是
  bare `writable=false`。
- 赋值目标身份：`FrontendAssignmentSemanticSupport.resolveIdentifierTarget` 当前
  直接 `currentScope.resolveValue`，不是 use-site binding；实施须以当前 statement
  的 pending-aware `symbolBindingResolver` 已发布 binding 固定 declaration 身份及
  `valueAccessStatus`，再校验 writable 与整条 POD provenance；不得重新结构查同名
  后序 const。binding 尚未发布时经同一 `FrontendVisibleValueResolver` / use-site
  restriction 路径取身份，不用 scope raw lookup 兜底。
- CFG `writableRoute` 使用同一已固定身份，lowering 不做第二次 AST 选名。
- 只读判定沿完整 `reduced.stepTraces` 各 prefix：TYPE_META `Other` / `Inner` /
  `Vector3` 等 head 之后中途出现**用户 const / builtin POD value const** 即
  只读 root；`SubscriptExpression.base` 若为 `AttributeExpression` 同样检查其
  完整规约 trace。目标是需 reverse writeback 的 POD 路径，不无差别拒绝任何
  曾含 `CONSTANT` 的链（临时 copy、只读 method return 不借此前缀误判）。
  例：`Other.C.x` / `Other.C.position.x` / `Other.C[0]` / `Vector3.ONE.x`
  拒绝；`Other.State["IDLE"] = ...` 维持既有 Dictionary 语义不变（事实源
  `doc/module_impl/frontend/frontend_enum_implementation.md` 已确认该写保护
  差异为既有行为，本计划不改变容器 const）；`State.IDLE = ...` 照旧拒绝。
- 验收例：类 property `n`，函数内 `n = 1; n += 1; const n = 2` —— 前两条写命中
  外层合法，声明之后 `=` 与 `+=` 拒绝；`var copy = C; copy.x = ...` 独立值仍允许。
- const initializer 中无可变容器，无深不可变容器问题。

### 7.6 参数默认 island 边界

- `parameterDefaultIslandLocalHit` 须同时识别 body 顶层 `LOCAL` 与 `CONSTANT`：
  无外层同名时 local const 产生 island visibility 违规，而非普通 unknown。
- 不采纳「函数体同名 const 应先于 class / global 阻断」：默认表达式处于
  `CallableScope`、不在 body scope；合法命中 class / global 常量按现有 var
  相同规则使用外层。源码探针在 ordinary lookup miss 后触发，是有意边界，
  不能先全 body 名字扫描阻断合法 class 值。
- `FrontendVisibleValueResolver` PARAMETER_DEFAULT：若实际 current lexical
  layer 有 block `CONSTANT`（local decl）应与 `LOCAL` 一样 blocked；class
  constant 不按 `CONSTANT` kind 一刀切封禁。
- 验收：无外层 `func f(a = LOCAL_C): const LOCAL_C = 1` → 拒绝一条
  metadata / island 诊断；class `const X = 9` + `func f(a = X): const X = 1`
  → 默认 = 9 合法，不泄漏 body 值 1。

### 7.7 工具链与 match

- `FrontendAnalysisInspectionTool`：不把 const 祖先一律 hardcode unsupported；
  source const 按 published facts 展示，其 initializer 属 intentionally not
  materialized（非 runtime CFG），不伪装未支持。
- `FrontendSnapshotQueryService`：local AST 声明 kind CONST 仍
  `DocSymbolKind.CONSTANT` 但 `ownerName = null`；class 新 record 经
  `declarationOrigins` 定位 owner；completion 刷新按 §7.2 export 回填。
- match key 只开放**已 Resolved 的 identifier / qualified 声明常量**：class
  `GdScriptEvaluatedConstant` 须带成功 value；local CONST `VariableDeclaration`
  须 `constantFacts` `Resolved`；`Rejected` 不得仅凭 `binding.kind == CONSTANT`
  成为常量 key。不引入通用 constantExpressionValues 表、不扩大 match 支持面；
  `MY_VEC.x` / `-1` 等 pattern 现支持规则原样（用户可 `const K = MY_VEC.x`
  后用 `K` 作 key）；enum Dictionary 下标仍非编译期常量。

## 8. LIR、C backend 与 String（决策 M、N）

### 8.1 常量值的 LIR 物化（复用现有指令，不新增 opcode）

- `GdConstantValue` 是唯一 truth；使用处统一经计划新 helper
  `FrontendBodyLoweringSession.materializeConstantValue(block, value, resultSlotId)`
  （内部可 private helper）物化冻结 payload，只读值模型、不重跑原 AST。
- scalar `BoolValue` / `IntValue` / `FloatValue` / `StringValue` → 已有
  `literal_bool` / `literal_int` / `literal_float` / `literal_string`。
- `BuiltinValue`：先把规范化 leaves 物化为精确 typed scalar 临时
  `LirVariable`（float leaf 发 `literal_float`——值已 `(float)` 规范化后提升
  double；int leaf 发 `literal_int`——int32 guard 在前端完成，scalar 类型本身
  int64），再按**固定 canonical construction recipe** 发
  `ConstructBuiltinInsn`：operands 全 `VariableOperand`，目标类型来自 result
  `LirVariable`，不把 typeName / 立即数塞 `construct_builtin`。
- 固定 recipe（全部优先现有 exact API 签名，已核实存在，不依赖 flat-float
  shim、不新增 source 语法）：`Vector2/3/4` = 2/3/4 float；`Vector2i/3i/4i` =
  2/3/4 int；`Rect2` / `Rect2i` = 两个 `Vector2` / `Vector2i`(position, size)；
  `Plane` = 4 float(normal xyz, d)；`Quaternion` = 4 float(x,y,z,w)；
  `AABB` = 两个 `Vector3`(position, size)；`Basis` = 3 `Vector3` 逻辑列
  (x,y,z)；`Transform2D` = 3 `Vector2`(x,y,origin)；`Transform3D` = `Basis` +
  `Vector3`(origin)；`Projection` = 4 `Vector4` 逻辑列 (x,y,z,w)；
  `Color` = 4 float(rgba)。所有值（含 default / copy；未来扩展也只取最终结果）都
  从 final payload 按 recipe 重建，不再走 axis-angle / normalization / 矩阵
  运算。先物化 component、再 parent，保证临时类型 / 声明与顺序正确。
- backend 只接受 exact args，不负责 frontend implicit conversions；不靠运行时
  重算补值或校验；mixed widths / error 不走 runtime 补救。
- `ConstructBuiltin` result 现需 non-ref；若消费点需 ref target 或不同 typed
  boundary，先 non-ref scratch 构造再走既有 Assign / pack / unpack / owned
  copy 路径，不改变后端 non-ref 合同。临时 value 槽不是 const 声明 storage；
  const 本身仍不建 mutable local / property / static field。
- 运行时成本明确：生成 Godot `godot_new_*` ctor wrapper，runtime 需初始化
  Godot API 并有临时 / 调用开销；AOT 编译期 evaluator 仍不依赖 live Godot。
  这里只是**物化冻结结果**，不执行原 AST 的 operators / member / utility /
  user call；String 本来也 runtime 物化，同一边界。
- backend 不新写 aggregate renderer、不改 generated headers；`Basis` 与
  `Transform3D.basis` 由现有 Godot 分量 ctor 处理物理 rows，不新增自己的
  列→行 C 转置；canonical 逻辑列 + metadata decoder 仍需准确，用非对称测试
  防把 rows 误作 columns。
- 后续若 profile 显示有成本，可独立做 backend 现有 construct 常量参数链到
  C aggregate 的优化；**不是本轮实现 / 验收条件，不新增 `literal_builtin`**。
- LIR 文法 / opcode / `ParsedLirInstruction` 不因 const 新增分派；class
  `scriptConstants` 表仍不序列化，只序列化使用处现有 literal + ctor 链。

### 8.2 LIR 文本精度与 roundtrip 修复（既有 scalar / 链必需）

- 解析精度：仅 `TokenKind.FLOAT` 分支下 `OperandKind.FLOAT` 与 `GENERIC` 改
  `Double.parseDouble`；`GENERIC` 的 INT 仍 `Long.parseLong` 不变。
- 非有限 canonical 输出沿用现 serializer 的 Java `Double.toString`：`NaN` /
  `Infinity` / `-Infinity`；`-0.0` 保留；tokenizer 将其显式识别为 FLOAT
  operand（不被 IDENT / label 吞），可兼容输入 `INF` / `NAN` 别名但
  serializer 唯一输出上述；普通 finite 不变。
- 分量标量 literal 的 operand 类型固定：整数分量用 `IntOperand`（含 int32
  端点）；float 分量用 `FloatOperand`，finite 整数值也序列化为 `1.0` 等
  浮点形而非 `1`；roundtrip 类型不靠猜。随后 `construct_builtin` 的 operands
  全为指向这些精确类型临时值的 `VariableOperand`，不接受分量立即数。
- simple serializer / parser / DOM roundtrip 对上述 literal + construct 链
  保持互逆，无新 opcode。

### 8.3 String 长度感知

- String 继续走现有 `literal_string` owning 物化路径，不引入新的 static cache。
- 统一该路径使用 Godot 现有 `utf8_chars_and_len` wrapper（含 inplace / ref target），
  参数为 **UTF-8 bytes 长度**（不是 Java `String.length`）。
- C literal 编码不能复用现有 `StringUtil.escapeStringLiteral`（对 NUL 输出
  `\u0000`，而 C 禁止低码位 UCN）：新增 `StringUtil.escapeCUtf8BytesLiteral(String decoded)`
  （或等价静态 helper）——先取 UTF-8 bytes，每个非安全 byte 用**固定 3 位
  八进制**（含 `\000`），ASCII quote / backslash 有专门 escape；后跟
  octal / hex 不贪婪。长度从同一 UTF-8 `byte[]` 计算，不从 literal 文本
  count。不改 `escapeStringLiteral` 对其它格式用途的语义；`NewDataInsnGen`
  新 literal String 路径用新 helper。控制字符与多字节 Unicode 编译后字节
  完全一致，C 里无 `\u0000`。这与 LIR `\uXXXX` 是两个不同格式，不混用。
- 不手改 generated binding header，只改已有 handwritten emission / helper。
- 其它 `GD_STATIC_S` 路径若本 const 不用则不强制改，但测试确保 const 不绕到
  NUL 终止缓存。
- 保留 embedded NUL、非 ASCII、raw / 多行、转义；String copy / return / Variant
  boxing / lifecycle 稳定；使用处对象构造不算 const AST 运行期求值。
- LIR 文本序列化（simple `StringOperand` 与 DOM）XML-safe 转义与对称解析：
  - 兼容现有 `\n` `\r` `\t` `\\` `\"`；XML 1.0 禁用控制字符与未配对 UTF-16
    surrogate 用固定宽度 `\uXXXX`（4 hex）文本逃逸；真实反斜杠先转义，避免
    literal 六字符 `\u0000` 与实际 NUL 混淆；
  - decoder 严格四位解析，畸形 / 截断抛 `LirInsnParsingException`，不把未知
    `\uX` 序列默默变 `u`；
  - 在 `StringUtil` 新增 / 复用通用 escape / unescape helper，不新造散落业务
    helper；simple serializer 与 parser 互逆，DOM 只嵌入 XML-safe textual
    instructions，绝不把真实 U+0000 写进 XML；
  - AOT 源码 decoded string 仍遵守 `StringUtil` 既有 Unicode 合法性；LIR 序列化
    不得因 XML 丢值。

## 9. 诊断与恢复

- 通用：每个 const 声明的源错误由**单 owner** 发诊断，锚定声明或最小 offending
  节点；源错误不得 throw programmer guard；坏声明不影响合法 sibling（class 联合
  prepass 按声明隔离，enum 整枚原子性除外）；parse damaged 子树不添加可用 facts。
- 纯 evaluator 只返回 `Rejected(reason, offendingNode)`；owner 用现有 typed fact
  状态与诊断 / skip ownership 决定发不发。
- 失败类别分流表：

  | 失败类别 | 诊断 owner | 类别 / 行为 |
  |---|---|---|
  | parser 错误 | 既有 parse owner | 不重复 |
  | TOP_BINDING 未知 / blocked 名 | 既有 binding owner | `CONST_RESOLUTION` 只存 `Rejected`，不重报 |
  | CHAIN / EXPR 类型失败 | 既有 chain / expr owner | 同上只存 `Rejected` |
  | 类型合法但不在 const 白名单 / 实际执行分支除零、越界 | const owner 首报 | local 拟新类别 `sema.constant_resolution`；class const prepass 由 skeleton 锚定、类别 `sema.class_skeleton`；enum 仍 `sema.class_skeleton` 不变 |
  | 引用 `Rejected` const | 引用点 binding / chain owner | 一条「依赖常量无效」，不复述原根因；expr / const / compile 不重复 |
  | 参数默认 island | default metadata owner（唯一协调者） | 沿用现静默 blocked + metadata owner 回收机制：引用点 owner 已报 error 则不再补 root error；若按 island 合同由 metadata 首报则 binding 静默；总计一条。ordinary body default 不共享全局单 owner 开关 |

- 精确验收：`const BAD = Missing + 1; const NEXT = BAD + 1` —— `Missing` 原始
  binding error 一条 + `NEXT` 依赖 error 一条；`const BAD = some_pure_looking_user_call()`
  （类型合法）由 const owner 拒绝一条；选中分支除零 1 条、未选中 0 条、dead
  分支未知名仍 1 条；class 失败被 default 引用时 metadata 撤回且不重复 root
  诊断。
- registry handler 失败分流（§5.5）：handler domain error → `Rejected` + const
  owner 单条诊断；handler unexpected exception / 返回错误类型 → programmer
  invariant（非源错误）；未注册 / 签名不满足 → `Rejected`（无 runtime call
  fallback），按上表 owner 规则发。
- 权限 blocked 前的 poison 检查：`bindIdentifier` / chain owner 在发 generic
  permission-block 错误前，按 declaration 身份先查 `rejectedClassConstants` /
  `constantFacts`；命中 poison 报「依赖常量无效」而非「not accessible」；
  真正权限 blocked 保留原信息。常量失败的 root / typefact 不被当作可用
  Variant。
- 短路预验证用纯 type / helper（§5.2 `FrontendBuiltinOperatorSupport`），不把有
  诊断副作用的 body owner 当纯 validator。
- 拒绝理由区分：不支持形状（feature 拒绝）、求值失败（除零 / 越界 / 非法 shift /
  类型不符）、名字规则（前向 / 遮蔽 / blocked）；复用 `FOUND_BLOCKED` 表达占名非法。

## 10. 实施阶段（依赖有序，每阶段独立验收）

- 通用约束：每次 patch 不超过 5 个文件；Java 25 / JUnit 5；不改 build 配置、不跑 clean；
  每阶段完成后才能进入依赖它的后续阶段；完成证据须为真实命令输出，不虚报。
- A1 已由用户确认 earlier-only（§12），原 P4 / P7 待确认依赖解除；各阶段仍
  全部未实施。
- 下述「文件定位」列的是类名，默认在包前缀 `src/main/java/gd/script/gdcc/`
  下；不是可点击的完整文件路径，定位以类名检索为准。
- P0 契约与 parse fixtures / 基线冻结 —— **已实施（2026-10-29）**
  - 目标：确定 `GdConstantValue` / `Evaluation` / `LookupContext` / `EvaluationPolicy`
    契约与 parser 可表达的 const 形状 fixtures；`ConstCallableKey` / registry
    Entry / `ConstMemberEvaluator` callback 契约与 immutable 单次 analysis
    generation 共享方式（§5.5）；earlier-only 规则已按用户确认冻结（§12）。
  - 文件定位：`src/main/java/gd/script/gdcc/frontend/constant/`（新包骨架）、
    `frontend/sema/analyzer/support/`（接口）。
  - 完成证据：契约编译通过；fixtures 落盘 `src/test/resources` 或对应测试目录。
  - 退出门槛：冻结通用接口与 earlier-only 规则；所有阶段仍未实施。
  - 实施结果：新增 `frontend.constant` 包（`GdConstantValue` sealed 值族、`Evaluation`、
    `ConstCallResult`、`GdScriptEvaluatedConstant`）与 support 契约
    （`LookupContext` 含四态 `LookupResult` + namespace 下行 + `selectCall`
    精确签名选择面及其 `SelectionResult`、`EvaluationPolicy`
    能力门、`ConstCallableKey`、`ConstMemberEvaluator`、
    `FrontendConstCallableRegistry` 读侧接口 + `empty()` 生产默认、
    `FrontendConstantEvaluator` 冻结签名，实现属 P2）。parse fixtures 以
    `FrontendConstParseBehaviorTest` 落盘并锁定 gdparser 0.6.0 实际形状：
    `const X`/`const X: T`/`const X := v` 为 `DeclarationKind.CONST`；
    `static const` 在 CST 即报错、`static` 成 `ErrorStatement`、const 恢复为
    非 static；无初始化器 const 为 `ErrorStatement`、不产出 declaration。
- P1 值模型 + 既有 LIR 常量物化 + roundtrip —— **已实施（2026-10-29）**
  - 目标：值族与 `FrontendBodyLoweringSession.materializeConstantValue`（§8.1
    固定 recipe、exact 参数类型、non-ref scratch + Assign / pack 路径）；先直接
    构造 LIR 验证，不开放 const syntax；无 `GdInstruction` 新 opcode / 新
    visitor / `ParsedLirInstruction` 新分派代码任务，无 C aggregate renderer。
  - 文件定位：`gd/script/gdcc/frontend/constant/`、`frontend/lowering/pass/body/`
    （session helper）、`lir/parser/`（现有 FLOAT / String 精度修复）、
    `backend/c/`（复用已有 construct / literal emission）；测试落点
    `FrontendConstantMaterializationTest`（planned）+ `CConstructInsnGenTest` +
    `SimpleLirBlockInsnParserTest` / `SimpleLirBlockInsnSerializerTest`
    （roundtrip）。
  - 完成证据：planned `GdConstantValueTest`、`FrontendConstantMaterializationTest`
    通过；simple / DOM roundtrip 对 literal + construct 链往返一致。
  - 退出门槛：每类型 recipe 的 exact 参数类型与 variable-only operands 正确；
    非对称 `Basis` / `Transform3D` 经逻辑列分量顺序验证；错类型 / 错个数 /
    int 越界在 frontend 拒绝。
  - 实施结果：`GdConstantValue.BuiltinValue` 完成 16 个受控 builtin 的精确 leaf
    数/族校验、float32 收窄规范化、int32 leaf 边界、不可变拷贝与 null leaf
    拒绝（`GdConstantValueTest`）。`materializeConstantValue` 按 §8.1 固定 recipe
    物化：scalar 直达 `literal_*`；BuiltinValue 深度优先——先 leaf literal 进
    `cfg_const_leaf_*`/`cfg_const_part_*`（独立前缀 + skip-occupied 防冲突 +
    exact-typed non-ref scratch），再 variable-only `construct_builtin`；结果槽
    类型必须等于 `value.type()`，ref 槽 fail-fast，供后续 Assign / pack / unpack
    边界路径消费。§8.2：`literal_float` 的 FLOAT token 改 `Double.parseDouble`
    （GENERIC 分支同步；INT token 保持原路径），tokenizer 显式识别
    `NaN`/`Infinity`/`-Infinity` 并兼容 `INF`/`NAN` 别名（不被 IDENT/label 吞），
    serializer 显式 `Double.toString` 规范输出。测试：`GdConstantValueTest`、
    `FrontendConstantMaterializationTest`（含 simple/DOM roundtrip）、parser /
    serializer 精度与非有限值专项、`CConstructInsnGenTest` 以真实
    `extension_api_451.json` 校验全部 16 个 recipe 的 `godot_new_*_with_*` 发射。
- P2 通用 evaluator + enum 适配 + 标量规则 + registry seam —— **已实施（2026-10-29）**
  - 目标：`FrontendConstantEvaluator` 标量全集（int/float/bool/String）+ 短路 /
    条件表达式 + `FrontendBuiltinOperatorSupport` 抽取；enum 旧入口薄适配；
    `FrontendConstCallableRegistry` Builder / freeze、纯方法 selection /
    dispatch seam（生产默认空表）与测试注入机制（§5.5）。
  - 文件定位：`frontend/sema/analyzer/support/`。
  - 完成证据：planned `FrontendConstantEvaluatorTest`、
    `FrontendConstCallableRegistryTest` 通过；现有
    `FrontendEnumConstantEvaluatorTest` 全绿（旧测试不改语义）；`long` 最小值、
    整数幂不丢精度；无 runtime 副作用（evaluator 不触 Godot 运行时）；
    dispatch seam 测试注入 entry 证明扩展有效、生产空表拒绝。
  - 退出门槛：mixed scalar 仍拒绝；除零 / 非法 shift / 死分支未知名全拒绝。
  - 实施结果：`FrontendConstantEvaluator` 为两遍纯 evaluator——validation
    pass 先对整树证明白名单 / 名字绑定 / 算子合法性 / 已注册 call 签名（死
    分支不能藏未知名或未注册 call），evaluation pass 只沿已采取值的分支计算
    （`and`/`or` 短路、三元只求值选中臂，死分支除零等域错误不触发）。标量
    运算表按类型精确配对：int `+ - * / % << >> & | ^ **`（long 回绕、`MIN/-1`
    回绕、`**` 平方乘不丢精度、shift 0..63 fail-closed、负幂拒绝）与比较；
    float `+ - * / **` 与比较；bool `== != and or`（`&&`/`||`/`!` 别名）；
    String `+ == !=`（不含 `<`/`%`，比 metadata 窄）；`in`/`not in`、
    mixed int/float 对标量一律拒绝。`Unary(-) + 整数字面量` 承载
    `Long.MIN_VALUE` 边例（bare 溢出字面量仍拒绝），经 `LONG_MIN_LITERAL`
    能力门。`EvaluationPolicy` 增加 `domain()` 与 `general()` /
    `enumPrePass()` 共享工厂；`LookupContext` 增加 `classRegistry()` 并把
    `selectCall` 变为委托共享 helper 的 default 方法；`GdConstantValue`
    增加 `isSupportedConstantType` 单一事实源。
    `FrontendBuiltinOperatorSupport` 自 `FrontendExpressionSemanticSupport`
    行为等价抽取（unary/binary exact return type、operator owner、特殊返回
    类型、mixed scalar 判别）；`FrontendConstCallSelectionSupport` 以
    `ScopeMethodResolver` 做普通 overload 选择，产出 declared 参数
    `ConstCallableKey`（非 const 表面 receiver/参数、static、省略默认参数、
    未注册变体均确定性拒绝）。`FrontendConstCallableRegistry.Builder` 注册时
    校验 key 恰为普通 selection 结果、returnType 一致、metadata 方法带
    `is_const`（当前 metadata 无反例，作为防误配纵深保留）、`build()` 冻结
    为 `Map`-backed 不可变表；重复 / 冻后注册抛 IAE/ISE。
    `FrontendEnumConstantEvaluator` 改为薄适配：enum 专用 `LookupContext`
    （earlier 成员 → 同类 enum 常量 → ancestor blocker → globals 顺序与旧
    实现一致）+ `EvaluationPolicy.enumPrePass()`，旧 `Evaluation`
    Resolved(long)/Rejected(reason) 形状与拒绝措辞不变，既有测试零修改全绿。
    测试：`FrontendConstantEvaluatorTest`（52 用例正反双向——标量/回绕/
    `MIN/-1`/幂精度/短路死分支验证不执行/三元全槽先验/名字四态/namespace
    下行/registry dispatch 注入与空表拒绝/参数规范化 int→float 与
    Vector2i→Vector2/能力门枚举）、`FrontendConstCallableRegistryTest`
    （builder/freeze/重复/冻后/假签名/静态方法/非 const 表面参数/省略默认/
    非 builtin owner/selection helper 直测）；`FrontendEnumConstantEvaluatorTest`
    及 `FrontendBodyOwnerProceduresExprTypeTest`、`FrontendTypeCheckAnalyzerTest`、
    `FrontendCompileCheckAnalyzerTest`、`FrontendEnumBodyBindingExprTypeTest`、
    `FrontendEnumChainBindingTest` 回归全绿。顺带修复：旧 enum 求值器对
    `MIN/-1` 会抛 `ArithmeticException` 逃逸，现按 int64 回绕语义统一为
    `Resolved(MIN)`。评审复核后加固：`evaluate()` 入口先跑一次整树
    `checkExpression`（后序兄弟验证失败时 handler 不会先行执行、嵌套短路
    链验证从 O(n²) 降为 O(n)）；`Long.MIN_VALUE` magnitude 判别共享
    `StringUtil.parseGdUnsignedIntegerLexeme` 的词法校验（`0x_8000…` 等
    畸形下划线位置按普通 malformed 拒绝），且边例同时要求
    `INT_ARITHMETIC`；enum 域所有 legacy 可见拒绝措辞经
    `unsupportedSuffix`/`expressionSuffix` 保持逐字一致
    （`FrontendEnumConstantEvaluatorTest` 精确字符串断言锚定）。
- P3 geometry 构造器 / 运算 / 成员投影 / metadata decoder（已实施）
  - 目标：§5.3 构造白名单、§5.1 vector/Color/geometry 运算、§5.4 投影与
    `FrontendBuiltinConstantDecoder`；不要求实现 length 等生产 registry
    entries（候选见 §5.5）。
  - 文件定位：`frontend/sema/analyzer/support/`（evaluator geometry 分支、
    `FrontendBuiltinOperatorSupport`、`FrontendBuiltinConstantDecoder`）；builtin
    元数据来源 `src/main/resources/extension_api_451.json`；测试落点
    `FrontendConstantEvaluatorTest`（planned）。
  - 完成证据：构造矩阵每签名 1 正例 / 排除组 1 反例；非对称 Basis / Transform /
    Projection 数据通过；decoder 坏 shape 明确拒绝。
  - 退出门槛：float assert 用 absolute+relative tolerance，整数 / 类型 / 布局 exact；
    `NAN` 用 `isNaN`、`-0` 验 raw bits。
  - 实施结果：
    - `FrontendConstConstructorSupport`：普通
      `FrontendConstructorResolutionSupport` overload 选择 + 显式白名单（声明参数
      类型精确匹配）；§5.3 全部签名——标量 `int/float/bool/String`（`int(float)`
      有限范围 + toward-zero、`int(bool)`、`float(int/bool)`、`bool(int/float)`，
      `int(String)`/`float(String)`/`String(其它)` 排除）、VectorN(i) 分量/copy/
      同维互转、Rect2(i) 三形态 + 互转、Plane/Quaternion/AABB/Basis/Transform2D/
      Transform3D/Projection/Color 列明签名、`T()` 文档化默认值（identity vs zero）；
      axis-angle、`Basis(Quaternion)`、`Quaternion(Basis)`、`Color(String)`、
      `Transform3D(Projection)` 等 metadata 存在但白名单外的签名全部
      `whitelist` 拒绝。实参先经 `normalizeArgument`（identity / int→float /
      同维 Vector*i→Vector*）再入 recipe；`VectorNi`/`Rect2i` int64 实参
      int32 预检（`BuiltinValue` guard 不充当源错误）。
    - `FrontendConstMemberProjectionSupport`：§5.4 精确 API 名白名单——
      vectors/Quaternion `x/y/z/w`、Rect2(i)/AABB `position/size/end`、Plane
      `normal/x/y/z/d`、Basis/Transform/Projection 列切片、Color `r/g/b/a`；
      `end` 为 pos+size 逐分量计算（`Rect2i.end` int32 越界拒绝）；`h`/`s`/`v`/
      `r8`/`ok_hsl`/scalar 接收者全拒绝。
    - `FrontendBuiltinConstantDecoder`：受限 `TypeName(arg, ...)` 文法——
      固定类型名 + flat scalar 参数（十进制/指数 + `inf`/`inf_neg`/`nan`）；
      arity 按 §5.3 canonical leaf 数精确匹配（2/3/4/6/9/12/16），Basis/
      Transform3D flat 物理行主序转置到 canonical 逻辑列；坏 shape / 错类型 /
      超 arity / int32 越界 / 不支持类型全拒绝；`LookupContext.resolveMember`
      默认实现把 `GdType` namespace identity 接到此 decoder——`Vector2.ZERO` /
      `Basis.IDENTITY` / `Transform3D.FLIP_Y` / `Color.RED` 等 metadata 常量
      直接求值，builtin class enum 成员（`AXIS_X`）保持 Unknown。
    - evaluator：CONSTRUCTOR 下 `CallExpression` 全形状——callee 必须解析为
      `GdType` namespace token、实参经 operand pass；BUILTIN_OPERATORS 下
      unary `±`（metadata 精确条目，恰覆盖 9 类型）、同型分量 `+ - * /`
      （`*i` 额外 `%`，toward-zero/dividend-sign 同 Java）、`==`/`!=` 逐叶
      IEEE 比较（`record.equals` 的 NaN/-0 差异显式规避）、右标量 `* /` 与
      左标量 `*`（返回类型以 metadata 为准，如 `Vector2i*float→Vector2`）；
      `*i` 算术 int64 计算 + int32 fail-closed（`MIN/-1`、分量溢出、负化全
      拒绝）；int64 标量乘经 `Math.multiplyExact` 防回绕——`MAX*2` 等
      回绕进 int32 窗口的乘积也正确拒绝；矩阵乘/四元数/变换等 metadata
      存在但白名单外的 pair 全部拒绝。
      `EvaluationPolicy.general()` 增 CONSTRUCTOR/BUILTIN_OPERATORS/
      MEMBER_PROJECTION；enum policy 不变（CONSTRUCTOR-off 的
      `CallExpression` 仍走 `rejectedShape` 旧措辞）。
    - 测试：`FrontendConstantEvaluatorTest` 新增 14 用例（~110 断言点）——
      构造矩阵每签名正例 + 排除组反例、非对称 Basis/Transform/Projection 列
      布局、`Vector2i` int32 端点与溢出、IEEE `==`（`-0.0==+0.0`、`NaN!=NaN`）、
      全类型投影表 + `end` 计算 + 越界、metadata 常量解码、callee/参数形状
      校验、三能力门独立拒绝、dead-branch 整树验证；float leaf 断言改
      `doubleToRawLongBits`（-0.0/+0.0 可区分）；新增
      `FrontendBuiltinConstantDecoderTest`（decoder 正反：标量/flat 布局/
      Basis·Transform3D 转置/坏 shape/真实 metadata/`AXIS_X` Unknown）。
      `GdConstantValue` 增 `leafCountOf`/`leafKindOf` 公开访问器。
      `FrontendConstCallableRegistryTest`、`FrontendEnumConstantEvaluatorTest`、
      `GdConstantValueTest`、`FrontendConstantMaterializationTest`、
      `FrontendBodyOwnerProceduresExprTypeTest`、
      `FrontendTypeCheckAnalyzerTest`、`FrontendCompileCheckAnalyzerTest`、
      `FrontendEnumBodyBindingExprTypeTest`、`FrontendEnumChainBindingTest`
      回归全绿。
- P4 class 联合 prepass / binding / qualified / lowering（已实施）
  - 目标：§6 全部接线（name reservations、联合 prepass、ClassScope 泛化、
    `FrontendChainReductionHelper`、lowering 使用处物化）；class
    `LookupContext` 接同一 immutable registry（§5.5）；无引擎注册、无
    `LIRProperty`。
  - 文件定位：`FrontendClassSkeletonBuilder`（联合 prepass / name reservations /
    `rejectedClassConstants` producer）、`FrontendAnalysisData`、
    `FrontendScopeAnalyzer` + `ClassScope`（mask view 注入 / `FOUND_BLOCKED`）、
    `FrontendChainReductionHelper`（qualified mask probe）、
    `FrontendOpaqueExprInsnLoweringProcessors` 与 member lowering 处理（使用处
    物化）、`FrontendConstDeclaredTypeSupport`（标注验证，local 共用）、
    `FrontendAnnotationUsageAnalyzer`（placement 唯一 owner）、
    `FrontendInterfacePhase.recordPropertyInitializer`（kind VAR 条件）；
    测试落点 `FrontendConstSkeletonTest` / `FrontendConstLoweringTest`
    （planned）。
  - 完成证据：planned `FrontendConstSkeletonTest`、`FrontendConstLoweringTest` 通过；
    现有 `FrontendEnumSkeletonTest` / `FrontendEnumScopeTest` /
    `FrontendEnumBodyBindingExprTypeTest` / `FrontendEnumChainBindingTest` 全绿。
  - 退出门槛：失败声明名称 mask 生效（`Parent.C` 成功 / `Child.C` 失败场景）；
    earlier class int const 被 enum 引用成功；前向 / 祖先 / 跨类按 §12 冻结
    拒绝矩阵断言拒绝。
  - 实施结果：
    - 新文件：`FrontendRejectedClassConstant`（mask 记录：AST 声明身份 +
      reason + anchor）、`FrontendClassConstPrepassContext`（每类共享名视图：
      live usable 常量表 + 全量已声明名 mask + live 失败名集 + 祖先 blocker）、
      `FrontendAncestorMemberBlocker`（script enum 名 / script 其他成员 /
      engine/native 三桶探测，`findEngineClassConstantInHierarchy` +
      `findEngineClassEnumValueInHierarchy` + 属性/方法/信号 metadata 查询）、
      `FrontendConstDeclaredTypeSupport`（严格三态标注验证 + 隐式矩阵
      `ALLOW_DIRECT`/`ALLOW_WITH_INTRINSIC_CAST` 负载转换，int→float 与
      同维 Vector*i→Vector*）、`FrontendClassConstantLookup`（scope/chain 共享
      单层 probe：mask 先于 usable，`Usable`/`TerminalBlocked`/`Miss`）、
      `FrontendClassConstPrepassLookup`（双模式 `LookupContext`——
      CLASS_CONST_INITIALIZER 全开 + 名空间下行；ENUM_PREP_PASS int-only 保
      legacy 措辞；自引用/后声明/enum 暴露名/非 const 成员按声明节点形状分措辞）。
    - `FrontendAnalysisData`：`constantFacts`（`FrontendAstSideTable<Evaluation>`
      按声明 AST 身份，冻结入 bootstrap/freeze/update 全通道）+
      `rejectedClassConstants`（owner canonical → name → record 不可变快照，
      skeleton 成员填充完成后一次性 publish）。
    - `FrontendClassSkeletonBuilder`：`runConstEnumJointPrePass` 以语句源码序
      联合处理 `const`/`enum`；`claimedMemberNames`（VAR/func/`_init`/signal/
      inner 预置 + const/enum 处理后占名）与 `declaredMemberNames`（全量已声明
      名含 const/enum 暴露名，纯 lookup mask）分离；enum 保持 per-enum 原子性
      （失败不发布、子树 skip、单条 `sema.class_skeleton`）；const 失败也占名，
      但**仅当自身 claim 成功才进 mask**——conflict 拒绝不 mask（名属主仍在），
      parse-damage/标注/求值失败 mask + fact + skip；`GdScriptEvaluatedConstant`
      经 `recordDeclarationOrigin` 记录 provenance 后入常量表（行类型 =
      标注类型或值类型）；enum evaluator 改接 `classContext` + self member。
    - `ClassScope`：4 参构造接 mask；`resolveValueHere` 直接命中优先后查 mask
      （terminal `FOUND_BLOCKED`，不经 restriction）；`resolveInheritedValueMember`
      每层在成员检查后接共享 probe——`Usable` 按原 CONSTANT 绑定发布、
      `TerminalBlocked` 无条件 blocked；blocked `ScopeValue` 携
      `VariableDeclaration` 声明 + `CONSTANT` kind + Variant 占位 + `isConstantBound`。
    - `FrontendScopeAnalyzer`：两处 `ClassScope` 构造注入
      `analysisData.rejectedClassConstants()`。
    - `FrontendChainReductionHelper.reduceGdccStaticLoad`：层序探针替换
      enum-only 查找——method → static property → 共享 probe（`Usable` 发
      `resolvedStaticLoadTrace` CONSTANT/GDCC，`TerminalBlocked` 发新
      `rejectedConstantLoadTrace` Status.BLOCKED + RouteKind.STATIC_LOAD +
      `FrontendResolvedMember.blocked` CONSTANT/TYPE_META/GDCC/VARIANT +
      declaration=const AST）→ 非静态成员遮蔽 break；usable 仅消费
      `GdScriptEnumConstant`/`GdScriptEnumGroup`/`GdScriptEvaluatedConstant`
      三种声明形状（防御性过滤保留）。
    - `FrontendBodyOwnerProcedures.bindIdentifier`：FOUND_BLOCKED 在通用
      accessibility 诊断前先查 poison——`CONSTANT` kind + `VariableDeclaration`
      声明 + `constantFacts` Rejected 三重结构判别（P5 local const 亦安全），
      命中发「depends on an invalid constant」，否则保留原「not accessible」。
    - `FrontendInterfacePhase.recordPropertyInitializer`：kind==VAR 门——
      class `const` initializer 永不再走 property-initializer 通道。
    - `FrontendOpaqueExprInsnLoweringProcessors` CONSTANT 分派新增
      `GdScriptEvaluatedConstant` → `session.materializeConstantValue`；
      `FrontendMemberLoadInsnLoweringProcessor` 新增同形状拦截（`Other.C` /
      内层类 / 继承限定路由同样物化为字面量/受控构造链，绝不发
      `LoadStaticInsn`）。CFG 侧 `buildTypeMetaHeadMemberStep` 的 receiverless
      `MemberLoadItem` 形状已直接兼容，无需新 CFG 接线。
    - `FrontendAnnotationUsageAnalyzer` 无需改动：`@onready`/`@export`/`@tool`
      placement 校验已按 `kind()!=VAR` 拒绝 const 上的 annotation（唯一
      owner 合同满足）。
    - 测试：新 `FrontendConstSkeletonTest`（12 用例——标量/几何构造/源码序双向/
      mask 收集/自引用/conflict/标注转换/祖先屏蔽/全局与 engine 名空间/named
      enum group 名空间/provenance）与新 `FrontendConstLoweringTest`（10 用例——
      bare/限定/继承/static/表达式内物化、builtin 构造链、依赖无效 vs
      accessibility vs unknown 三向诊断区分、运行时成员初始化拒绝）；
      `FrontendEnumSkeletonTest` 两处断言按新合同修订（`const FOO=5`+`C=FOO`
      现为成功；`A=B,B=1` 前向改为 declared-not-usable 措辞）。回归
      `FrontendEnumConstantEvaluatorTest`/`FrontendEnumBodyBindingExprTypeTest`/
      `FrontendEnumChainBindingTest`/`FrontendLoweringBodyInsnPassTest`/
      `FrontendLoweringAnalysisPassTest`/`FrontendBodyOwnerProceduresExprTypeTest`/
      `FrontendTypeCheckAnalyzerTest`/`FrontendCompileCheckAnalyzerTest`/
      `FrontendConstantMaterializationTest`/`ClassScopeTest`/
      `FrontendScopeAnalyzerTest`/`FrontendConstantEvaluatorTest`/
      `FrontendBuiltinConstantDecoderTest`/`FrontendConstCallableRegistryTest`/
      `GdConstantValueTest` 全绿。
    - 评审修复（review-expert-c 首轮 4 条 medium 全确认）：
      - `FrontendClassConstPrepassLookup.resolveGlobalOrNamespace` 的
        type-meta 兜底按 `ScopeTypeMetaKind` 分流——只有 `BUILTIN`/
        `ENGINE_CLASS` 才成为名空间；`GLOBAL_ENUM`/`GDCC_ENUM`（instance
        type 是 int，`Error(1)` 会伪装成 `int` 构造）与 `GDCC_CLASS`
        返回 `NonConstant` 精确措辞，堵死 `Error(1)`/`OtherTop.C`
        类初始化器逃逸。
      - `FrontendChainReductionHelper.reduceStaticMethodStep` 前置
        `classConstCallClaimTrace` 逐层 claim 走查——本层有任何同名方法
        则交给原 resolver；usable const 终止为 FAILED「not a method」、
        masked const 终止为 BLOCKED「invalid constant」，父类 static
        同名方法不再能穿透子类 const 占名（`Child.C()` 回归测试锚定）。
      - 值枚举泄漏修复——`Scope.blockedValueNamesHere()` 新默认方法 +
        `collectVisibleValues`/`FrontendVisibleValueEnumerator` 累计
        抑制；`ClassScope.valuesHere` 逐层应用自身与祖先 mask；IDE 侧
        `FrontendSnapshotCompletionService` static/instance 两条走查都
        吸收 rejected 名集。
      - `FrontendClassConstPrepassLookup` 的 `earlierEnumMembers` 改
        `Collections.unmodifiableMap` 借读视图，消掉 per-initializer
        `Map.copyOf` 的 Θ(n²) 累计拷贝。
      - 复审补充（identifier 补全路径）：
        `ClassScope.blockedValueNamesHere` 升级为当前类 + 全部祖先层 mask
        union（与 `resolveInheritedValueMember` 的逐层 blocked 等效），
        `ReadOnlyScope` 补转发，`identifierCandidates` 在值枚举后沿同一
        词法链吸收 blocked 名集到 value/method 两套 claim——masked
        `PI`/`INF` 不再经 `collectGlobalNamespace` 回漏；snapshot 级
        `CompletionServiceTest` 两个回归锚定（自身+继承 mask、更近合法
        声明胜过失败祖先名）。
      - 三轮复审补充：instance 补全面不做 mask 过滤——`instance.member`
        解析走 `ScopePropertyResolver`/`ScopeMethodResolver`（不查 const
        mask），补全必须与解析一致；
        `memberAccessBelowFailedConstStaysConsistentWithInstanceResolution`
        锚定（父 `var hp` + 子 `const hp = MISSING` → `c.hp` 仍给出
        PROPERTY 候选）。**决策已确认**：mask 域 = 常量可见域（裸/限定），
        不扩至实例成员面——见 §6 mask 边界条目。
- P5 local 全链路（已实施，2026-10-31，四轮 review-expert-c 后 APPROVE）
  - 目标：§7.1–§7.7（inventory、`ORDINARY_CONST`、index/certificate、
    `CONST_RESOLUTION` owner、overlay、invalid masking、lambda const view、CFG、
    赋值身份、island / match / 工具链）；suite const `LookupContext` 接同一
    immutable registry 并核对 CHAIN / EXPR exact selection（§5.5）；只在整链
    成功后删除 deferred 边界。
  - 文件定位：`FrontendVariableAnalyzer`（inventory）、`FrontendInterfacePhase` /
    `FrontendBodyLocalDeclaration`（`ORDINARY_CONST`）、`FrontendVisibleValueResolver`、
    `FrontendStatementResolver`、`FrontendSuiteResolver`（`CONST_RESOLUTION` owner +
    `FrontendConstFactView` 接线）、`FrontendConstResolutionPatch` /
    `FrontendAnalysisData.applyConstTypeUpdate` / `BlockScope.resetConstantType`
    （export 回填）、`FrontendLambdaIdentityAnalyzer` /
    `FrontendLoweringFunctionPreparationPass.collectLambdaContexts`（const-subtree
    skip）、`FrontendTypedLexicalEnvironment`、
    `FrontendCompileCheckAnalyzer`（gate）、`FrontendCfgGraphBuilder` /
    `writableRoute`、`FrontendAssignmentSemanticSupport`（use-site 身份）；
    测试落点 `FrontendLocalConstResolutionTest`（planned）+ 既有 targeted 类。
  - 完成证据：planned `FrontendLocalConstResolutionTest` 通过；现有
    `FrontendVariableAnalyzerTest` / `FrontendVisibleValueResolverTest` /
    `FrontendTypedLexicalEnvironmentTest` / `FrontendLambdaCapturePlannerTest` /
    `FrontendCompileCheckAnalyzerTest` / `FrontendCfgGraphBuilderTest` /
    `FrontendLoweringBodyInsnPassTest` 全绿。
  - 退出门槛：compile gate 要求所有可达 const `Resolved` 且类型一致；const 声明
    无 mutable local 槽；nested suite / lambda 读到正确值且不捕获 self；赋值
    身份经 use-site binding 固定（`n` 验收例通过）。
  - 实施进度：
    - §7.1 inventory / index / certificate（已实施）：`FrontendVariableAnalyzer`
      新增 `bindLocalConst`——与 `var` 相同的 same-block / same-callable
      冲突规则，`defineConstant` 产出 `CONSTANT`+readonly 绑定；baseline 为
      `Variant` 或严格校验后的显式标注类型（
      `FrontendConstDeclaredTypeSupport.checkDeclaredType`：无标注→`Variant`
      baseline、Resolved→标注类型、Rejected→error 诊断 + `constantFacts`
      Rejected + `Variant` baseline，不做 warning+fallback）；
      `FrontendInterfacePhase.recordLocalConstDeclaration` 以
      `ORDINARY_CONST` 入索引且只收 surviving 绑定（`binding.declaration()
      == declaration`），不走 initializer 子树；
      `FrontendBodyLocalDeclaration.Kind` 增 `ORDINARY_CONST`；
      `FrontendBodyStructuralCompleteness` 的 declaration 校验把
      ORDINARY_CONST 与 ORDINARY_VAR 同等对待，`requireScopeInventoryPublished`
      反向检查扩到 `ScopeValueKind.CONSTANT`（decl 必须 VariableDeclaration），
      match 前缀规则下 ORDINARY_CONST 视同普通 local。
    - §7.2/§7.4 pipeline 骨架 + patch 基础设施（已实施）：
      `FrontendSemanticStage.CONST_RESOLUTION`（`EXPR_TYPE` 之后）；
      `FrontendStatementResolver` 把 `const` 声明送入 supported root 并在
      expr-type 与 var-type-post 之间调 `runConstResolution` hook（接口带
      default 空实现）；`FrontendVisibleValueResolver` 删除 block-local const
      deferred 边界（classifyBoundaryEdge / detect* 三件套）、
      `isCallableLocalKind`/`checkPublishedLocalInventory` 覆盖 `CONSTANT`；
      `FrontendConstTypeUpdate` / `FrontendConstResolutionPatch` /
      `FrontendConstFactView` 新类型；`FrontendOwnerPatch` permits + 两个
      accessor 默认实现；`FrontendPatchTransaction` 序插入
      `CONST_RESOLUTION`；`FrontendTypedLexicalEnvironment` 增
      `constantFact`（pending→committed→stable→parent→constFactView）、
      `putConstantFact`、`addConstTypeUpdate` + 校验（CONSTANT kind +
      声明身份 + Variant-baseline/幂等）、`constFactView` 仅 parentless
      env 生效；`FrontendAnalysisData` `applyPatch` 接入 constFacts 合并
      （`sameEvaluation` 冲突检测）+ `applyConstTypeUpdate` 唯一导出入口
      + `refreshPublishedBindingPayloads` 共享载荷刷新；
      `BlockScope.resetConstantType`；`FrontendPublishedFactTypeGuard`
      `checkConstantFacts`/`checkConstTypeUpdates` 接入 `checkOwnerPatch`。
    - §7.3 求值与绑定集成（已实施）：`FrontendLocalConstLookupContext`（suite
      `LookupContext`：local→block→callable→class 祖先→globals，共享
      `decodeEngineMemberConstant`）；`FrontendBodyOwnerProcedures.runConstResolution`
      按声明序求值、`putConstantFact` Resolved/Rejected、`putConstTypeUpdate`
      写 exact 类型；`isRejectedConstantBinding` 改为 overlay-aware（pending/
      committed/factView 链）使 FOUND_BLOCKED 与 FOUND_ALLOWED 两侧都能
      毒化；`FrontendTypedLexicalEnvironment.effectiveScopeValue` 扩展
      CONSTANT overlay（constSlotType），绑定在后续语句即见 exact 类型而
      scope 导出时才永久 reset——`var x := C` 推断 int、use-site LIR 槽类型
      与物化一致；TOP_BINDING/CHAIN 与 identity/capture 收集跳过 const
      initializer 子树（lambda 在 const 初始化器内报
      `sema.unsupported_expression_route`）。
    - §7.5 只读写回覆盖（已实施）：`FrontendAssignmentSemanticSupport`
      `resolveIdentifierTarget` 改用已发布 use-site binding 为首路径
      （源码序过滤+mask 已内嵌于 binding——`n = 1`/`n += 1` 在 `const n`
      之前合法写 property、之后命中 `scopeValue.constant()` 拒绝）；
      新增 `frozenConstantTargetIssue`/`frozenConstantHeadIssue`——head
      identifier 常量（`V.x`/`V[0]`/`V.position.x`/`CV.*`）与非终末
      `CONSTANT` 成员步（`Other.C.x`、`Vector3.ONE.x`）统一拒绝
      「frozen … cannot be assigned through a member or element write」；
      enum group（`GdScriptEnumGroup`/`EnumDeclaration`）保持容器语义
      不受新增保护影响。
    - §7.6 参数默认 island（已实施）：`parameterDefaultIslandLocalHit`
      同时识别 `LOCAL`/`CONSTANT` 顶层 body local；resolver 的
      `isCallableLocalKind` 含 CONSTANT 使 PARAMETER_DEFAULT 域在
      BlockScope 层即 blocked；class const 经循环外层正常放行——
      `func f(a = X)` 读外层 class `X = 9` 而不受 body 同名 const 干扰。
    - §7.7 工具链与 match（已实施）：`FrontendAnalysisInspectionTool`
      移除 const 祖先 hardcode（initializer 按 published facts 展示）；
      `FrontendSnapshotQueryService` `isBodyLocalDeclaration`——local
      `const` 声明符号保持 `DocSymbolKind.CONSTANT` 但 `ownerName = null`
      （BlockScope 归属不再误报 class 名）；`FrontendMatchSupport.
      isConstantPatternOperand` 对 local const（`VariableDeclaration`
      declSite）要求 `constantFacts` `Resolved`，Rejected 退回 runtime
      比较而非凭 `binding.kind == CONSTANT` 误当常量 key。
    - §7.2 CFG/lowering（已实施）：`FrontendCfgGraphBuilder` local const
      声明降级为 `SourceAnchorItem`（只留行号锚点、无 `LocalDeclarationItem`
      无 mutable 槽）；`collectLambdaContexts` 跳过 const initializer
      子树；lambda body const 走同一 supported-root 管线；use-site 经
      `GdScriptEvaluatedConstant` → `materializeConstantValue` 物化
      （测试验证 LiteralInsn-only、无 AssignInsn/LoadStaticInsn）。
    - 测试（已实施）：`FrontendLocalConstResolutionTest` 30 用例——
      scalar/typed/inferred、源码序、if/for/while/match/elif 嵌套、
      lambda 读 outer const（无 capture、不多捕 self、factView 链）、
      失败初始化/未知标识符/不支持表达式/lambda 初始化器/依赖失效、
      只读赋值、`n` 验收例、frozen-root 成员与下标写、参数默认 island、
      match const key 正反、`sema.compile_check` blocker、名称遮蔽
      不回落；`FrontendConstLoweringTest` +3 local const 物化用例；
      既有 resolver/analyzer/compile-check/match/assignment 测试按新
      契约更新且全量受影响面（~380 用例）零回归。
    - 评审记录（review-expert-c）：首轮 1 high + 5 medium 全确认修复；
      第二轮确认前五项并指出抑制过宽（initializer 级会吞独立 expr 失
      败），改为逐表达式判定；第三轮指出同 pass 诊断污染迭代顺序，改
      为循环前快照；第四轮 `APPROVE`。修复明细：
      - 捕获计划的裸 `resolveValue` 越过源码序——lambda 之后的 `const`/
        `var` 会遮蔽属性命中导致漏捕 `self`、lowering 崩 `requireSelfSlot`。
        `IdentifierUse` 增加 `useSite`，`FrontendLambdaCapturePlanner.
        firstVisibleValueHit` 按层应用 `VariableDeclaration` 声明序过滤，
        `captureFromUse`/`transferredCapture(AlongParents)`/`usesImplicitInstanceMember`
        统一走该过滤（内层 lambda 节点作转移锚点）；`n`-遮蔽用例 +
        `var`-遮蔽变体 + lowering 端到端回归。
      - `FrontendLocalConstLookupContext.resolveMember` 对 `ClassDef` 只探
        直接层——`Child.C`（`C` 在 `Base`）被误拒。新增
        `FrontendClassConstantLookup.probeInHierarchy` 共享分层探针
        （mask → usable → 非常量成员遮蔽终止），与普通链同语义。
      - `frozenConstantTargetIssue` 误判 `V.normalized().x`——值产出调用
        切断写回溯源，head/mid 常量检查只作用于最后一个 `suggestedCall`
        步之后的区段；方法返回值成员写回归。
      - `upstreamErrorInside` 只比字节范围未比文件——跨文件同段错误会吞掉
        本文件常量诊断；改为先按 `FrontendDiagnostic.sourcePathText` 归一
        过滤，双文件模块回归。
      - `isBodyLocalDeclaration` 经 `nearestScope` 拿到 `ReadOnlyScope`
        包装使 `instanceof BlockScope` 恒 false——改直查 `scopesByAst`；
        `documentationAt` 验证 local const `ownerName = null`、class const
        保留类名归属。
      - `const BAD = Missing + 1` 报 binding + expression_resolution ×2，
        超出 §9「Missing 原始 binding error 一条」——`publishExpressionType`
        增 `suppressUpstreamConsequenceDiagnostics`（仅 const 初始化器）；
        经两轮复核细化为**逐表达式**抑制——子树内已有上游 error 的 entry
        不再复述，独立 expr 失败（`(1+1.0)+Missing` 的混合操作数）照常上
        报——且循环前快照 upstream 诊断集防止同 pass 内已发诊断干扰迭代
        顺序（`(1+1.0)+1` 嵌套失败确定性双报）；`Missing` 恰一条、`NEXT`
        依赖恰一条、独立失败各归其主验收用例落地。
- P6 String 长度感知 + LIR 文本序列化 + consumer 集成（可与 P3 并行，端到端
  门槛依赖 P4/P5）
  - 目标：§8.3 全部（含 simple `StringOperand` / DOM XML-safe 转义与对称解析）；
    default / match / writability 集成。
  - 文件定位：`lir/parser/`（`ParsedLirInstruction` StringOperand、
    `SimpleLirBlockInsnSerializer` / `SimpleLirBlockInsnParser` / DOM）、
    `StringUtil`（通用 escape/unescape helper）、`backend/c` handwritten emission /
    `NewDataInsnGen`；测试落点 `SimpleLirBlockInsnParserTest` /
    `SimpleLirBlockInsnSerializerTest` / `CNewDataInsnGenTest` /
    `FrontendConstantMaterializationTest`（String 物化相关）。
  - 完成证据：`"a\u0000b"` 长度 3 且能取末尾 `b`（经 DOM roundtrip 后跑 runtime，
    非仅内存 LIR 直出 C）；多字节 UTF-8 bytes 长度正确；真实 NUL vs literal
    六字符 `\u0000`、NUL 后数字、控制字符用例通过；simple→parse 与 DOM→parse
    payload 完全相等；`FrontendConstantMaterializationTest` 与 String 相关
    `CNewDataInsnGenTest` / `CFloatLiteralSupportTest` 全绿。
  - 退出门槛：const 不绕 `GD_STATIC_S` NUL 终止路径；copy / return / Variant /
    ref target / registry shutdown 无寿命问题；LIR 序列化不因 XML 丢值。
- P7 targeted AOT smoke + 负例/回归 + 文档转正
  - 目标：planned `GdccConstRuntimeSmokeTest` 端到端；负例回归；实现完成后才把
    相关事实写入事实源文档（届时另案，不改本计划为事实源）。
  - 文件定位：`src/test/test_suite/unit_test/script/const/` 与配对
    `src/test/test_suite/unit_test/validation/const/`（新增脚本与期望输出对），
    并按 `doc/test_suite.md` 要求更新
    `GdScriptUnitTestCompileRunnerTest.EXPECTED_SCRIPT_PATHS`；
    `GdccConstRuntimeSmokeTest`（planned）按现有 runtime smoke 模式在 tmp
    生成临时 Godot 工程，不改 shared `test_project/` 源文件（权限仅限
    src/doc/tmp）。
  - 引擎 validation 只经 `target.call` 方法拿值，不直接访问 AOT 类 const
    字段（验证 AOT-only 边界）；Godot / Zig / TCC 依赖不可用时显式 skip，
    不算通过；只跑定向测试类 / 方法，不跑整个 suite 引擎项目。
  - 退出门槛：const lowered region 只允许纯 payload 物化的 literal /
    `construct_builtin` / assign 及既有边界转换；禁止原 AST 的 unary /
    binary 求值、`CallMethod` / `CallGlobal` / 未授权 call 回放（Godot
    ctor wrapper 与 String 物化不算违规）；const 声明无 runtime
    initializer、不建 mutable 槽；class 无 `LIRProperty` / static init /
    hotreload storage / ClassDB registration；class 相关用例按 §12 冻结
    拒绝矩阵断言拒绝。

## 11. 测试矩阵与命令

### 11.1 验收矩阵

- enum 回归：当前全部支持 / 拒绝用例、ancestor shadow blocker、整枚原子性与后序
  拒绝；**新增** earlier class int const 引用。
- 标量：`long` radix / underscore / minmax；溢出回绕；负除负余；shift `-1` / `0` /
  `63` / `64`；整数幂大整数不走 double；float 指数 / `INF` / `NAN` / `-0`；
  mixed scalar 失败；numeric conversions 窄化 / 非有限拒绝。
- 构造器：白名单每类型每签名至少 1 正例、每排除组 1 反例；default identity vs
  zero 正确；`Vector*i` int32 端点；非对称 `Basis` 逻辑列 `(1,2,3),(4,5,6),(7,8,10)`
  按分量顺序物化并 runtime `target.call` 取 `.basis.x/y/z` 与 origin 验证
  （不再强制匹配 C `.rows` aggregate 片段）；`Transform` / `Projection` 用
  非对称数据区分列；每个支持的 operator pair 与 zero/negative 用例；
  `Transform3D.basis` 经同一 recipe 物化 —— metadata 非对称 flat text 与两种
  source 构造产出相同 `Basis`。
- 物化（§8.1）：全类型 recipe 的 exact param types / variable-only operands /
  non-ref 与 ref scratch + assign / packing / lifetime；simple + DOM 往返构造
  链无新 opcode；const 声明无 mutable 槽；mixed widths / error 不走 runtime
  补救。
- 断言精度：float 用 absolute + relative tolerance；整数 / 类型 / 布局 exact；
  `NAN` 用 `isNaN`；`-0` 验 raw bits；不允许宽松比较掩盖问题。
- 短路 / 条件：`false and (1/0 == 0)` 不计算除法；dead branch 中未知名 /
  未注册 call 仍拒绝，已注册 call 只验证而不执行 handler（§5.2 / §5.5）；
  条件表达式两分支类型合法性验证。
- 名字规则：local forward / self 外层回退、same-scope duplicate const/var、nested
  shadows、失败 const 名不退 global；class forward / cross init 按冻结规则
  拒绝；`Other.C` / 继承 / static / inner 隔离 / 参数默认与 property
  initializer；Variant runtime 值不被冒称常量。claims：独立 class const /
  enum 不与自身冲突；`const A = OK; const OK = 99`、`const A = OK; var OK = 99`、
  父类 const/var/function `OK` 遮住全局 `OK = 0` 全部拒绝；同名不同
  identity 诊断不抛 guard。
- lowering 区域：const lowered region 只允许纯 payload 物化（literal /
  `construct_builtin` / assign / 边界转换），禁止原 AST 的 unary / binary
  求值、`CallMethod` / `CallGlobal` / 未授权 call 回放（Godot ctor wrapper
  与 String 物化不算违规）；class 无 `LIRProperty` / static init /
  hotreload storage / ClassDB registration；const 声明无 mutable local 槽 /
  runtime initializer；unused const 仍验证不延迟到首读；未来已 fold 的
  scalar 结果（如 `length`）只生成对应 literal，无 runtime 调用。
- registry（§5.5）：生产空表下 `V.length()` 拒绝（即使 `is_const = true`）；
  测试注入 `Vector2.length` 纯 handler：输入 (3,4) 输出 5，且 class / local
  同一 selected signature 命中；wrong owner / arity / types、duplicate key、
  freeze 后修改拒绝；handler 返回错误类型为 programmer guard、domain error
  为 `Rejected` + 单 owner 诊断；receiver / args 非 const 拒绝；dead branch
  已注册 call 不执行 handler（计数 0）、未注册 / unknown 即使 dead 仍拒绝；
  省略默认参数与 varargs 拒绝；compile eval 不触 Godot 引擎。未来新增生产
  方法必须逐 entry 补数值 / 边界测试（0、NaN、Inf、normalize zero 等另
  明示），不是仅注册即算验证通过。
- 只读：`C` / `C +=` / `Other.C` / `C.x` / `C.position.x` / `C[0]` /
  `Other.C.position.x` / `Other.C[0]` / `Vector3.ONE.x` / compound receiver
  writeback 全拒绝（沿完整 stepTraces prefix 到 POD 常量 root）；
  `Other.State["IDLE"] = ...` 维持既有 Dictionary 语义（回归例）；
  赋值身份固定验收 `n = 1; n += 1; const n = 2`（前两条写外层合法、
  声明后 `=` / `+=` 拒绝）；`var copy` 独立可变。
- class 失败 mask：`Parent.C = 7`、`Child.C` 求值失败 —— Child 函数内 bare `C`
  与其它脚本 `Child.C` 均命中 terminal-blocked 而非 7；失败 PI / const 名不退
  global；`FOUND_BLOCKED` 携带声明身份。
- lambda：混合 lambda 同读 outer const 与 mutable var —— const 无 capture、
  mutable 仍正确 capture、不从 typed parent 偷读；nested lambda 经
  `FrontendConstFactView` 链读 const 且不多捕 self。
- String：`"a\u0000b"` 长度 3 且取到末尾 `b`，须经 DOM roundtrip 再跑 runtime
  （非仅内存 LIR 直出 C）；真实 NUL vs literal 六字符 `\u0000`、NUL 后跟数字、
  多字节 UTF-8 按 bytes、控制字符；simple→parse 与 DOM→parse payload 完全相等；
  转义后跟 octal/hex 字符、raw / 多行；copy / return / Variant / ref 目标 /
  registry shutdown 无寿命问题；不走 `GD_STATIC_S` NUL 终止路径。
- 集成：default 仅冻结 const read、普通 call 仍按缺参执行；annotation 保持
  literal-only；match const key 有效、enum Dictionary 下标仍非编译期常量；
  参数默认 island：无外层同名 `const LOCAL_C` → 一条 island 诊断，class
  `X = 9` 时 `f(a = X)` 取 9 不泄漏 body `X = 1`。
- 注解与 lambda：`FrontendAnnotationUsageAnalyzerTest` 保持单条
  `sema.annotation_usage`；UnknownType / 显式 Variant 错误标注不退推断；
  const initializer 内 lambda 不占 ordinal（其后合法 lambda ordinal 不漂移）；
  class const 无 property initializer。
- export 回填：snapshot `C = Vector2` 最终类型 `Vector2` 非 `Variant`；
  nested lambda 精确成员可读；`Rejected` 不回填成功 type。
- match / 工具链：仅 `Resolved` 声明常量可作 key；`Rejected` 不凭 kind 入 key；
  inspection tool 按 published facts 展示；snapshot local const `ownerName = null`。
- 诊断：按 §9 分流表 —— `const BAD = Missing + 1; const NEXT = BAD + 1` 仅
  `Missing` binding error 一条 + `NEXT` 依赖 error 一条；类型合法的
  `const BAD = user_call()` 由 const owner 拒绝一条；选中除零 1 / 未选中 0 /
  dead 未知仍 1；class 失败被 default 引用时 metadata 撤回、不重复 root 诊断；
  源错误锚定声明或最小 offending 节点、不 throw programmer guard；坏声明不影响
  合法 sibling；parse damaged 不加 facts。

### 11.2 测试类

- 新增（均为 planned，尚未创建）：`GdConstantValueTest`、
  `FrontendConstantEvaluatorTest`、`FrontendConstSkeletonTest`、
  `FrontendLocalConstResolutionTest`、`FrontendConstLoweringTest`、
  `FrontendConstantMaterializationTest`、`FrontendConstCallableRegistryTest`、
  `GdccConstRuntimeSmokeTest`。另计划结构性完整性类 `FrontendBodyStructuralCompletenessTest`
  （当前无同名测试类，按新增 planned 处理）。
- 现有（按阶段引用）：`FrontendEnumConstantEvaluatorTest`、`FrontendEnumSkeletonTest`、
  `FrontendEnumScopeTest`、`FrontendEnumBodyBindingExprTypeTest`、
  `FrontendEnumChainBindingTest`、`FrontendVariableAnalyzerTest`、
  `FrontendVisibleValueResolverTest`、`FrontendTypedLexicalEnvironmentTest`、
  `FrontendLambdaCapturePlannerTest`、`FrontendCompileCheckAnalyzerTest`、
  `FrontendCfgGraphBuilderTest`、`FrontendLoweringBodyInsnPassTest`、
  `SimpleLirBlockInsnParserTest`、`SimpleLirBlockInsnSerializerTest`、
  `CNewDataInsnGenTest`、`CAssignInsnGenTest`、`CFloatLiteralSupportTest`、
  `CConstructInsnGenTest`。

### 11.3 命令（Linux）

- 形态：`script/run-gradle-targeted-tests.sh --tests Class1,Class2`（wrapper 已带
  no-daemon / info / console=plain）。
- 只跑目标类，不全 suite、不 clean。示例：
  - P2 回归：`script/run-gradle-targeted-tests.sh --tests FrontendEnumConstantEvaluatorTest,FrontendConstantEvaluatorTest,FrontendConstCallableRegistryTest`
  - P1：`script/run-gradle-targeted-tests.sh --tests GdConstantValueTest,FrontendConstantMaterializationTest,CConstructInsnGenTest,SimpleLirBlockInsnParserTest,SimpleLirBlockInsnSerializerTest`
  - P4：`script/run-gradle-targeted-tests.sh --tests FrontendConstSkeletonTest,FrontendEnumSkeletonTest,FrontendEnumScopeTest,FrontendEnumBodyBindingExprTypeTest,FrontendEnumChainBindingTest`
  - P5：`script/run-gradle-targeted-tests.sh --tests FrontendLocalConstResolutionTest,FrontendVariableAnalyzerTest,FrontendVisibleValueResolverTest,FrontendTypedLexicalEnvironmentTest,FrontendLambdaCapturePlannerTest,FrontendCompileCheckAnalyzerTest,FrontendCfgGraphBuilderTest`

## 12. A1 已确认：class const initializer 仅依赖同类更早常量

- 用户确认（2026-10-28）：「仅允许初始化器引用同类更早定义的常量」——
  earlier-only 选项已选定，A1 关闭。
- 适用范围：仅限制 class const initializer 中**脚本声明常量之间**的依赖——
  本类前向 / 自引 / 继承（祖先）/ 跨类 initializer 引用均显式拒绝；
  - 不撤销全局数字常量 / 裸 enum / builtin 类型常量白名单（§3.2 / §6）；
  - 普通读取（class body / local / qualified / 继承）不受此 initializer
    依赖限制；
  - local const 前序词法规则不变。
- 语义示例：`const A = B + 1; const B = 2` 中 `A` 引用后序 `B` 拒绝；
  initializer 中 `Base.C` / `Other.C` 拒绝；class body 普通读取
  `Base.C` / `Other.C` 允许。
- 背景风险（决策动机）：class/member 按源码填充 skeleton，引用尚未求值的
  后序 const 时值未定；若按文件顺序或遮蔽规则静默回退，可能选错值且无诊断。
- 未选方案（完整前向 / 跨类依赖语义：跨类声明 header inventory、const
  initializer 依赖图、继承拓扑 + DFS 三态求值、循环依赖诊断、named enum
  type-meta 早发表解耦）记录为未来独立任务，**不是**本计划待确认项或门槛；
  示例 `A.X = B.Y` / `B.Y = A.X` 循环用于说明设计选择。

## 13. 调研 / 审阅执行记录

- 审阅历史说明：以下 2026-10-28 第一轮 / 第二轮修订与最终复核记录对应
  **旧方案**（`literal_builtin` 新 opcode + C aggregate renderer）；本轮
  （见末尾条目）§8 已改为复用现有 LIR 物化方案，A-6 等 layout 目标改由
  固定 recipe + runtime 验收保障；旧 APPROVE 记录不覆盖本轮修改。
- 2026-10-28：完成 §2 代码基线复读核实、完成计划初稿；设计决策与验收口径已
  审定，本文档仅如实组织，未重新推导、未实现任何新能力。
- 基线测试（本计划撰写前唯一运行项，共 2 个现有测试类）：
  - 命令：`script/run-gradle-targeted-tests.sh --tests FrontendEnumConstantEvaluatorTest,StringUtilTest`
  - 完整 stdout/stderr 保存于 `tmp/const-plan-baseline-20261028.log`。
  - 结果：exit 0，`BUILD SUCCESSFUL in 11s`（wrapper 过滤后输出）。
  - 本文档不声称任何新功能测试已跑；planned 测试类均未创建。
- 2026-10-28 第一轮修订：review-expert-c 提出 4 项中风险 —— C-1 赋值身份
  （§7.5）、C-2 String 完整序列化链（§8.1 / §8.3）、C-3 失败 mask 确切载体
  （§6）、C-4 诊断分流（§9）——经复读源码核实均成立并已修订，另并入 lambda
  const-only view 补强（§7.2 / §7.4）与文案准确性修正（关联路径、
  `NewDataInsnGen`、`ParsedLirInstruction.toConcrete`、§2.4 事实口径、§3 矩阵
  上下文拆分、§5 默认值与 canonical 布局表、§10 文件落点、A1 措辞统一）。
  随后复核已闭环（见下方最终复核记录）。
- 2026-10-28 第二轮修订：review-expert-a 返回 10 项技术发现（A-1..A-10，
  原始评级为 plan gap 而非已存在生产 bug）与低风险 A-11 / A-12，逐项核实
  后处置如下（与 C 编号关联一并标注）：
  - A-1 采纳（关联 C-2）：§8.1 精确 FLOAT/GENERIC 解析分支与 operand 类型；
    §8.3 新增 `escapeCUtf8BytesLiteral` 固定 3 位八进制 C 编码。
  - A-2 采纳（关联 C-3）：§9 permission-block 前 poison 检查。
  - A-3 采纳：§6 分离 `claimedMemberNames` / `allDeclaredMemberNames` 与
    LookupContext blockers；enum 收紧措辞与新增负例。
  - A-4 采纳：§7.1 surviving index、§7.2 export 时只读类型回填、§7.4
    lowering 统一 materialize；lambda const-only view 沿用 R1。
  - A-5 采纳：§6 / §7.1 标注验证唯一 owner、§6 annotation placement 不抢报
    与 `recordPropertyInitializer` VAR 条件、§7.4 const-subtree lambda skip。
  - A-6 采纳：§8.2 `Basis` / `Transform3D.basis` 共用 canonical→physical
    renderer。
  - A-7 采纳（v1 子集明确化）：§5.1 operator / §5.4 投影名精确白名单。
  - A-8 部分采纳：只读 trace prefix 检查入 §7.5；**不采纳** enum Dictionary
    写保护改动——事实源 `frontend_enum_implementation.md` 已确认
    `State["X"] = 1` 不做写保护为既有差异，非本计划目标。
  - A-9 部分采纳：§7.6 island 双向识别 LOCAL / CONSTANT；**不采纳**「body
    同名先于 class / global 阻断」——默认表达式在 `CallableScope`，合法
    外层命中按现有 var 规则。
  - A-10 部分采纳：§7.3 / §7.7 compile gate、inspection / snapshot 展示、
    match 仅 `Resolved` 声明常量；**不采纳**泛化任意 match key 常量表达式
    （独立工作）。
  - A-11 采纳：类名统一（`FrontendInterfacePhase` /
    `FrontendBodyLocalDeclaration` / `FrontendStatementResolver` /
    `FrontendTypedLexicalEnvironment`）、关联文档与 §10 文件落点措辞、
    P7 测试落点改 `src/test/test_suite/unit_test/{script,validation}/const/`。
  - A-12 部分采纳：P0 退出条件仅冻结 A1 无关接口、不批准 class
    initializer 语义；named enum 成员与 `Current.C` 边界入 §3.2 / §6 / §12。
- 环境限制：本会话 MCP server 列表为空，`intellij-idea` 的
  `get_file_problems` / `create_new_file` / `reformat_file` 均不可用；文件检查、
  创建、格式化以终端与内置文件工具替代。
- 最终复核：review-expert-a 对 A-1..A-12 逐项复核，结论为 `APPROVE`；
  技术项与文档偏差已解决，A-12 对应的架构项 A1 当时保留待用户确认。
- 最终复核：review-expert-c 对 C-1..C-4 及直接依赖修改复核，结论为
  `APPROVE`（限本次复核范围），四项均已在计划层面闭合。
- 审阅状态：两位原审阅者均确认其发现的中高风险 / 实施阻塞问题已解决；
  这仅表示计划审阅完成，不表示 const 功能已实现或 planned 测试已通过。
- 2026-10-28 A1 冻结：用户确认「仅允许初始化器引用同类更早定义的常量」，
  A1 关闭，P4 / P7 相关待确认依赖解除；本次仅更新计划语义状态（§3.2 /
  §6 / §10 / §11 / §12 / §13），未实现源码、未新增或运行测试。上述两位
  reviewer 的 APPROVE 为此初审阶段历史记录，不表示其审阅过本次状态更新。
- 2026-10-29 第三轮修订：按用户确认的「可扩展常量成员函数 registry +
  复用现有 LIR（不新增 `literal_builtin`）」方案更新 §1 / §2.6 / §4.3 /
  §5.1–§5.5 / §7.4 / §8 / §9 / §10 / §11：新增 §5.5
  `FrontendConstCallableRegistry`（生产默认空表、测试注入）、§8.1 改为
  `materializeConstantValue` + 固定 recipe 经既有 literal /
  `ConstructBuiltinInsn` 物化、§8.2 保留既有 scalar / String 文本精度修复；
  planned 测试 `LiteralBuiltinInsnContractTest` / `CConstBuiltinLiteralTest`
  更名为 `FrontendConstantMaterializationTest` /
  `FrontendConstCallableRegistryTest`。A1 确认仍关闭、所有阶段仍未实施。
  本轮仅使用 review-expert-c 审阅，旧版 APPROVE 记录不覆盖本轮修改。
- 2026-10-29 本轮仅由 review-expert-c 审阅并返回 `APPROVE`；未发现新引入的
  中高风险 / 实施阻塞问题；已核实既有构造路径、registry 阶段边界、dead
  branch 及 P0–P7 / §11 一致性。此结论只覆盖计划；实现、planned tests
  均未完成，旧 baseline 不代表新能力已验证。
