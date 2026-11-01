# Frontend `const` 声明实现约定

> 本文档是 GDCC frontend `const` 声明特性的实现事实源。
> 只描述当前代码已承担的语义、合同、边界与回归锚点。
> 本文档替代原 `frontend_const_implementation_plan.md`，不保留分步骤实施、阶段状态、
> 验收清单或评审流水账；合同变化时直接改写当前状态描述，不以计划性措辞陈述未实现行为。

## 文档状态

- 状态：已实现并维护中。
- 适用范围（均为相对仓库根路径）：
  - `src/main/java/gd/script/gdcc/frontend/constant/**`（冻结值模型与求值事实）
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/**`（求值器与运算支撑）
  - `src/main/java/gd/script/gdcc/frontend/sema/**`（class skeleton 联合 prepass、local pipeline）
  - `src/main/java/gd/script/gdcc/frontend/scope/**`（ClassScope 常量绑定、失败名 mask 与限定访问 probe）
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`（CFG、body lowering 物化）
  - `src/main/java/gd/script/gdcc/lir/**`（literal / construct 序列化精度与 String 文本编码）
  - `src/main/java/gd/script/gdcc/backend/c/**`（复用已有 constructor emission 与 String 长度感知物化）
- 关联文档：
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
  - `doc/module_impl/frontend/frontend_match_statement_implementation.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/test_suite.md`

## 1. 目标与支持面

### 1.1 目标

- 为 GDCC AOT 编译的脚本提供 `const` 支持：类级与函数 local 级常量声明，编译期求值，
  在使用处用字面量与受控分量构造链物化冻结结果。
- 求值复用统一求值器 `FrontendConstantEvaluator`（`frontend/sema/analyzer/support/`），
  原 `FrontendEnumConstantEvaluator` 保留为面向 enum 的薄适配层。
- 目标是 GDCC AOT 语义，不要求与解释型 GDScript 全量 constant expression 一致，也不提供
  外部 ClassDB 常量可见性。浮点结果允许与 Godot 实现存在细微差异（double 中间计算、
  float 收窄的规范化值），但整数宽度 / 类型 / 内存布局不允许以浮点误差为由放宽。

### 1.2 当前支持面

- 声明形态：类体 `const` 与函数体 local `const`；**必须有初始化器**。`static const`、
  `@export const`、`@onready const` 不合法：`static const` 在 parser CST 即报错
  （`static` 成 `ErrorStatement`、const 恢复为非 static），无初始化器的 const 同样是
  `ErrorStatement`、不产出 declaration；`@export` / `@onready` 等注解 placement 由
  `FrontendAnnotationUsageAnalyzer` 按 `kind != VAR` 统一拒绝。
- 无标注时推断**精确类型**，不沿用 `var =` 的 Variant 默认。
- 支持类型（精确类型清单）：
  `int`、`float`、`bool`、`String`、`Vector2`、`Vector3`、`Vector4`、`Vector2i`、`Vector3i`、
  `Vector4i`、`Rect2`、`Rect2i`、`Plane`、`Quaternion`、`AABB`、`Basis`、`Transform2D`、
  `Transform3D`、`Projection`、`Color`。
- 显式标注只接受本清单的精确类型，以及已成功求值的 named enum 的 int 别名；
  标注类型与 initializer 结果类型不一致时按既有 implicit conversion 规则转换 payload
  （identity、`int → float`、同维 `Vector*i → Vector*` 等已认可边界），不新增转换路径。
- 不承诺：`Variant`、`Object`、`null`、`StringName`、`NodePath`、容器（Array/Dictionary）、
  资源 / `preload`、type alias、utility 调用 / user call、以及未注册或不满足
  注册准入的成员方法调用（可扩展 registry seam 见 §4.5，生产默认注册表为空）。
- 引擎方法调用者拿到的返回值仍走普通返回 ABI；const 不是公开常量字段，不产生
  ClassDB 常量 / property 注册、`LIRProperty`、static init 或 hotreload storage。

### 1.3 明确非目标

- 不修改解释型 GDScript 行为，不实现 ClassDB / 引擎常量查询。
- 不引入可变容器常量，因此无深度不可变容器问题。
- annotation export 参数保持现有 literal-only 合同，不扩展为 const 引用。
- 不修改 generated `godot_builtin_types.h`，不新增 ClassDB const / static property /
  `load_static` 运行时查值路径。
- `Variant` shortcut 构造不在 const 范围内。
- class const initializer 的前向 / 跨类依赖语义（依赖图、循环诊断、跨类声明 header
  inventory）是当前明确拒绝的范围，属于未来独立任务（见 §9）。

## 2. 语义矩阵

上下文分两类：**initializer 上下文**（const 声明的初始化器，class 侧受源码顺序
约束）与**普通读取上下文**（函数体 / 类 body 普通表达式，不受成员书写顺序限制）。

### 2.1 声明与标注

| 场景 | class const | local const | 说明 |
|---|---|---|---|
| 声明无 initializer | 拒绝 | 拒绝 | const 必须初始化 |
| `static const` / `@export` / `@onready` | 拒绝 | n/a | parser / annotation placement 各自诊断 |
| 无标注 | 推断精确类型 | 推断精确类型 | 不沿用 `var =` Variant 默认 |
| 标注为精确类型 / enum int 别名 | 允许，按 implicit conversion 转 payload | 同左 | 不新增转换路径 |
| 标注为 Variant/Object/容器/资源等 | 拒绝 | 拒绝 | 不在支持面 |
| same-scope 与 var / function / signal / const 同名 | 拒绝 | 拒绝 | `claimedMemberNames` 冲突诊断（§5）；合法继承 shadow 除外 |
| 声明后失败 const 名字 | 名 mask 终止查找（§5） | terminal invalid，不退外层 | 不伪造成功值 |

### 2.2 initializer 上下文引用

| 场景 | class const initializer | local const initializer | 说明 |
|---|---|---|---|
| 引用本源码更早已求值 const / enum | 允许 | 允许（前序可见） | class 见 §5；local 见 §6 |
| 引用本类后序 const | 拒绝（earlier-only） | n/a（按外层回退规则） | 见 §9 |
| 引用祖先 / 跨类 const | 拒绝（earlier-only） | 允许（已可见的继承 / qualified 普通路线） | 见 §9 |
| 引用全局数字常量 / 裸 enum / builtin 类型常量 | 允许 | 允许 | builtin 经 §4.4 decoder |
| 引用 mutable local / 参数 / capture | n/a | 拒绝 | 即使字面量赋值也不是常量 |
| 声明前裸名命中外层同名 | n/a | 允许（沿用 var 外层回退） | `const X = X + 1` 可读外层 X |
| 引用本类更早 named enum 成员 `State.IDLE` | 允许（attr 读 `GdScriptEnumGroup` member，int，不物化 Dictionary） | n/a | enum initializer 旧 int AST 不接受 attribute |
| 引用本类 qualified `Current.C` | 仅 bare（qualified 写法不支持） | 允许（已有 qualified 普通路线） | 与跨类访问明确区分 |
| enum initializer 引用前序类 int const | 允许 | n/a | enum 仍 int-only 白名单 |

### 2.3 普通读取与其它场景

| 场景 | class body / 其它函数体 | local / lambda | 说明 |
|---|---|---|---|
| bare 读本类 const（含继承） | 允许，不受书写顺序限制 | 允许 | nearest-layer |
| `Other.CONST` / inner canonical qualified | 允许 | 允许 | 经 `FrontendClassConstantLookup` probe |
| inner class 看 outer 未限定值 | 不无条件可见 | n/a | 按既有 scope 规则 |
| lambda 内读 outer const | n/a | 允许，不进 capture | 见 §6.4 |
| 默认参数 / property initializer 引用 | 允许（仅常量引用折叠） | 拒绝引用 local const / 参数 / capture | 保持 call/default 机制 |
| match key 引用 | 允许 | 允许 | 仅 `Resolved` 声明常量；enum Dictionary 下标不算 |
| annotation 参数引用 | 拒绝（literal-only 不变） | 拒绝 | 不扩展 |
| 写入 / `+=` / 成员 / 下标写回 | 拒绝 | 拒绝 | 见 §6.5 |

## 3. 常量值模型与求值接口

### 3.1 `GdConstantValue` 值族

- 中性共享包 `gd.script.gdcc.frontend.constant`，与 lowering/backend 解耦。
- `GdConstantValue` 为 sealed 值族：
  - `BoolValue(boolean)`
  - `IntValue(long)`
  - `FloatValue(double)`
  - `StringValue(String decoded)`（存解码后 payload）
  - `BuiltinValue(GdType, List<GdConstantValue> components)`
- `BuiltinValue` 约束（构造时强制，不只是约定）：
  - 只允许 §1.2 清单中的受控 builtin 类型；
  - components 为 flattened scalar leaves，个数 / 类别 / 范围精确校验并 `List.copyOf`；
  - float 几何 leaf 存 `(float)` 收窄后再提升 double 的规范化值；
  - int 几何 leaf 限 int32 范围。
- 禁止 payload：AST 节点、Scope 对象、C 字符串、Object 及任何可变结构。
- `type()` 由各 variant 确定，`BuiltinValue` 直接返回其 `GdType`；
  `isSupportedConstantType` / `leafCountOf` / `leafKindOf` 是支持面与布局的单一事实源。

### 3.2 声明与事实模型

- `GdScriptEvaluatedConstant(name, ownerCanonicalName, value)` 是 class 常量表的声明模型；
  与 AST 的来源关联另经 `declarationOrigins` 维护，不嵌回 record。
- local binding declaration 保持原 `VariableDeclaration` identity，不引入第二身份。
- `FrontendAnalysisData.constantFacts` 按**声明 AST 身份**存
  `Evaluation.Resolved(value)` / `Evaluation.Rejected(reason, offendingNode)`；
  freeze / guards / conflict / snapshot 机制与普通 side table 一致；只存事实、
  不复制重新求值。
- 失败 class const 的名称 mask 由 generation-owned `rejectedClassConstants`
  承载（§5），不进入 `constantFacts` / class table 的成功物化路径。

### 3.3 `FrontendConstantEvaluator` 与查找接口

- `FrontendConstantEvaluator.evaluate(Expression, LookupContext, EvaluationPolicy)`
  位于 `frontend/sema/analyzer/support/`。它是**两遍纯 evaluator**：
  validation pass 先对整棵树证明白名单 / 名字绑定 / 算子合法性 / 已注册 call
  签名（dead branch 不能藏未知名或未注册 call），evaluation pass 只沿实际
  选中分支计算（`and` / `or` 短路、三元只求值选中臂，dead branch 的除零等
  域错误不触发）。求值器不依赖 Godot 运行时、不修改 AST、不发诊断。
- 原 enum 入口 `FrontendEnumConstantEvaluator` 是薄适配层：enum 专用
  `LookupContext`（earlier 成员 → 同类 enum 常量 → ancestor blocker → globals，
  顺序与旧实现一致）+ `EvaluationPolicy.enumPrePass()`，旧
  `Resolved(long)` / `Rejected(reason)` 形状与拒绝措辞原样保留；
  **不并存两套整数运算**。`long` 最小负值字面量经 `Unary(-)` + 整数字面量
  形态在通用模式承载（`LONG_MIN_LITERAL` 能力门），enum 模式保持原拒绝行为。
- `LookupContext` 的名字解析结果 `LookupResult` 为五种 variant：
  `ResolvedConstant` / `ResolvedNamespace` / `NonConstant` / `Blocked` / `Unknown`，
  按真实 scope 身份判定，不按 String map 猜测；namespace 结果供 qualified
  名空间下行（如 `Vector2.ZERO`、`State.IDLE`）。
- `EvaluationPolicy` 是能力门：`general()` 开放全部已实现面（标量运算、几何
  运算、构造器、成员投影、条件表达式、registry dispatch）；`enumPrePass()`
  只开放 `INT_ARITHMETIC`；`domain()` 只影响拒绝措辞，不影响能力判定。
- 成员方法调用走 §4.5 `FrontendConstCallableRegistry` seam；生产默认注册表为
  空。`LookupContext.selectCall` 负责按 metadata 做普通 overload 选择并产出
  精确签名 key——当前两个生产 lookup context 均使用默认实现，无 override。

## 4. 求值规则

### 4.1 运算白名单

- int：一元 `+ - ~`，二元 `+ - * / % << >> & | ^`，及**非负整数幂 `**`**
  （整数重复平方法，不经 `Math.pow` double 回转）。int64 运算保留回绕语义
  （含 `MIN / -1` → `MIN` 回绕），shift 限定 0..63、除零 / 负幂拒绝。
- 比较：相同精确类型的 int/float 同型比较（`== != < <= > >=`）。
- float：`+ - * / **` 与一元 `+ -`。
- bool：严格 `not` / `and` / `or` 及 `== !=`（`&&` / `||` / `!` 别名同样接受）。
- String：`+` / `== !=`，只按解码 payload；不实现格式化 / 排序 / 长度等操作。
- 一元 `+ -`：所有 vector / `*i` vector、Color、Plane、Quaternion 按 metadata
  精确条目开放。
- vector / Color：同型分量 `+ - * /`；`*i` vector 额外 `%`；右侧标量支持 `* /`，
  左侧标量只支持 `*`；标量参数与返回类型以 metadata 确列为准（如
  `float * Vector2i => Vector2`）；不交换操作数、不支持 `scalar / Vector`，
  不触发仅 scalar-scalar 的 mixed guard；未实现的 pair 拒绝。
- `*i` vector / int 除法向零截断，余数符号同 dividend（同 Java）；除零 / int32
  溢出 fail-closed 拒绝。标量 int64 乘法经 `Math.multiplyExact` 预检，回绕进
  int32 窗口的乘积同样正确拒绝。
- 其余 geometry 算术（矩阵乘法等）不在支持面。
- geometry `== !=`：按分量 IEEE 语义比较（`-0.0 == +0.0`、`NaN != NaN`），
  不用 `record.equals`（避免 NaN / -0 语义差异）。
- int32 输入窄化 / 浮转整 / `*i` 算术越界一律 fail-closed 拒绝（不声称覆盖
  Godot 全部窄化 / UB）。
- 浮点：中间以 double 计算，几何每个 primitive 结果收窄 float32 再提升；
  `NAN` / `INF` / `-0` 保留分类与符号。
- scalar mixed int/float 二元运算仍拒绝（与 frontend 现状一致），用户须显式
  `float(...)` 转换。

### 4.2 短路求值与条件表达式

- `and` / `or` / 条件表达式：先递归验证**整棵树**的白名单、名字绑定、普通
  类型合法性与已注册 call 的形状 / 签名 / 名字，禁止 dead branch 隐藏未注册
  call / 未知名，再仅计算选中分支（未选中分支的除零不执行；已注册而未选中
  的 branch 只验证不执行 handler，不触发域错误 / 副作用）。
- 条件表达式只允许 bool 条件与两分支**同精确支持类型**，不设计 join。
- 类型层算子查询由纯 helper `FrontendBuiltinOperatorSupport` 承担（自
  `FrontendExpressionSemanticSupport` 行为等价抽取：unary/binary exact return
  type、operator owner、特殊返回类型、mixed scalar 判别），普通表达式与
  const 共用；保留 mixed guard、不交换 operands。
- 构造器走已有 overload 选择 + 显式 purity whitelist，不把 API 表当纯函数表。

### 4.3 构造器准确白名单

按类型穷举（`FrontendConstConstructorSupport`：普通
`FrontendConstructorResolutionSupport` overload 选择 + 声明参数类型精确匹配
白名单，无运行时 constructor fallback）：

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
- 不支持（各至少 1 反例锚定）：axis-angle、`Basis(Quaternion)`、`Quaternion(Basis)`、
  `Color(String)`、`Transform3D(Projection)`、`int(String)` / `float(String)` /
  `String(其它)` 等其余 API 构造重载。
- 零参默认值（`T()`，与隐式默认一致）：
  - `int` = 0、`float` = 0.0、`bool` = false、`String` = ""；
  - `VectorN` / `VectorNi` / `Rect2` / `Rect2i` / `Plane` / `AABB` 全零；
  - `Quaternion` = (0,0,0,1)；`Basis` / `Transform2D` / `Transform3D` /
    `Projection` = identity；`Color` = (0,0,0,1)。
- canonical flat component 布局（`BuiltinValue.components` 顺序与个数，
  `GdConstantValue.ComponentSpec` 承载）：
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

### 4.4 builtin metadata 常量与成员只读投影

- `FrontendBuiltinConstantDecoder`：不硬编码整 API palette，仅解析已选定 builtin
  metadata constants 的受限 constructor text（标量数值 / `inf` / `nan` + 固定 type +
  flat args），以 type/arity 映射 canonical 值。未知 metadata member 返回
  `Unknown`；成员存在但类型不支持或文本不能解码返回 `NonConstant`，不静默当不存在。
- metadata 允许 6/9/12/16 scalar 表示 `Transform2D` / `Basis` / `Transform3D` /
  `Projection` 的内部 flat 编码；`Basis` / `Transform3D` 的 flat 物理行主序在
  decoder 内转置到 canonical 逻辑列。该 flat 语法**不**开放给用户 source constructor。
- 不把 `CBuiltinBuilder`（C backend）依赖拉回 frontend；decoder 遇坏 shape 明确拒绝。
- 成员只读投影白名单（`FrontendConstMemberProjectionSupport`，均为确切 API 属性名）：
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
  - `Color`：r / g / b / a；`h` / `s` / `v` / `r8` / `ok_hsl` 等不支持。
- 属性取值结果再次规范化（float leaf 收窄语义同 §3.1）。
- `Basis` canonical 按**逻辑列**保存（9 leaves：xcol, ycol, zcol）；物化时按
  §7.1 固定分量 recipe 以逻辑列交 Godot 分量 ctor（不在 frontend 做列→行
  转置）；`Basis` 物理存储为 rows、`Transform2D` / `Projection` 为 columns 的
  ABI 事实只由 decoder 与物化 recipe 消化，验收用非对称数据防把 rows 误作 columns。

### 4.5 常量成员函数注册接口 `FrontendConstCallableRegistry`

- 定位：compiler 内部 registry，不是 `ClassRegistry`、不是运行时 ClassDB。
  `FrontendConstCallableRegistryBuilder` 注册（校验签名可由普通 selection 重现、
  returnType 一致、metadata 方法带 `is_const` 作为防误配证据而非纯度证明），
  `build()` 冻结为 `Map`-backed 不可变表；duplicate signature / 无效 metadata /
  冻后注册抛 IAE/ISE。registry 按单次 analysis generation 共享实例设计；
  当前两个生产 `LookupContext` 均在 `callableRegistry()` 中直接返回
  `FrontendConstCallableRegistry.empty()`，尚无 generation 级注册表注入。
  无全局 mutable registry、无反射 / 插件自动发现。**生产默认注册表为空**，
  测试经自定义 `LookupContext` 注入条目证明扩展有效。
- exact key：`ConstCallableKey(ownerType, methodName, invocationKind INSTANCE_METHOD,
  parameterTypes)`（`List.copyOf`）。owner 是**已选择**的 builtin 方法所属类型，
  参数为已选 declared 固定参数类型；不按 name 或 Godot hash 单独匹配。
  当前仅受控 builtin 实例方法；static / utility / 用户方法 / Object / Variant
  dispatch 仍拒绝；`invocationKind` 仅作身份区分，不开放其它 kind。
- Entry = key + `returnType` + pure `ConstMemberEvaluator` callback：
  `evaluate(receiver, normalizedArguments)` →
  `ConstCallResult.Resolved / Rejected`。handler 不接 AST / Scope / Godot 运行时；
  source-free `ConstCallResult` 由 AST evaluator 附 `offendingNode` /
  diagnostic anchor。unexpected exception / 错误返回 type 是 programmer
  invariant，不伪装源错误或 runtime fallback。handler 只做显式纯确定 Java
  计算：不读场景 / 资源 / 时钟 / 随机 / 外部状态、不改 receiver / args。
- 准入流程（`FrontendConstCallSelectionSupport`）：receiver 与所有参与计算的
  实参必须 const；先用 `ScopeMethodResolver` 底层 pure helper 与既有参数兼容 /
  重载规则选出确切方法并冻结 selection，把 actual payload 转换到 selected
  declared param types，再查 exact registry。当前 fixed arity、无 varargs、
  **不补省略默认参数**（有默认值的方法须显式传全实参）。return type 必须
  对应选中 metadata 且在支持 value 类型内。API `is_const` 只作防误配证据；
  不能把任意同名用户 method 套 builtin handler。
- 阶段约束：class const 在 skeleton 时不依赖稍后才发布的
  `resolvedCalls()` / `expressionTypes`——走 pure signature resolver 与
  source-name 遮蔽 / earlier-only context；`selectCall` 为 suite 侧预留了
  核对 effective CHAIN / EXPR exact selection 的 override 面（当前无生产
  override，见 §9），override 只许核对、不得二次选到不同方法或重写其
  facts。registry 不放开 earlier-only 的跨类脚本常量依赖。
- AST 形状：允许 `AttributeCallStep` 作为受控 chain step；已注册结果可作为
  下一 step 的 receiver（如 `normalized().length()` 逐条注册后），每 step 都
  需授权且精确类型合法。enum 专用 policy 仍旧 int AST，registry 不向 enum
  开放 method。
- 失败语义：未注册或 purity / 签名不满足 → `Rejected`，无 runtime call
  fallback。生产空表下 `V.length()` 仍不支持；`Vector2/3.length` /
  `length_squared` / `normalized` / `dot` 等为**候选、尚未注册**，新增条目
  不改 evaluator dispatch，且须逐 entry 补数值 / 边界测试。

## 5. class / enum 联合 prepass

- `FrontendClassSkeletonBuilder.runConstEnumJointPrePass` 在普通 member skeleton
  之前按 class body **statement 源码序**联合处理 `const` / `enum`；named enum
  成功时其 type-meta 仍先于普通 member type 解析发布（合同不变）。
- **冲突 claims 与求值 lookup blockers 分离**（`FrontendClassConstPrepassContext`
  承载两套名集，不混用）：
  - `claimedMemberNames`：初始仅本类 `VAR` / function / `_init` / signal /
    inner class；联合 prepass 按源码逐项处理 const / enum，先与不同声明已
    claim 名做冲突检查（排除自身 identity），再占名。const 即使求值失败也
    占名；enum 成功才按旧合同占组名 / 匿名 members，不把 named enum 内部
    成员提升类层。同名不同 identity 发 diagnostic，不抛 guard、不覆盖；
    合法继承 shadow 不一概拒绝。
  - `declaredMemberNames`：全量 AST 结构视图（VAR / CONST / enum 暴露名 /
    function / signal / inner），**只供 lookup mask**；不拿它做
    `contains(name)` 自冲突检查；同名多声明保留 identity / 先行存活者。
- `FrontendClassConstPrepassLookup`（双模式 `LookupContext`）的 bare-name 顺序：
  当前 enum 的 earlier 成员 → 本类已发布可用 const / enum → 失败 const
  blocker → declared-member blocker → ancestor blocker → global fallback。
  `FrontendAncestorMemberBlocker` 对 script 祖先沿 GDCC 继承关系 AST 结构扫
  enum / 其他成员名（不依赖 fill 顺序），对 engine/native 根祖先按
  `ClassRegistry` metadata 查 members / constant / type meta；一律明确拒绝
  而非穿透全局。class-const 模式允许 builtin / engine / class enum
  namespace；enum 模式只允许 int 表面且保留旧措辞。type-meta 兜底按
  `ScopeTypeMetaKind` 分流：只有 `BUILTIN` / `ENGINE_CLASS` 成为名空间；
  `GLOBAL_ENUM` / `GDCC_ENUM` / `GDCC_CLASS` 返回 `NonConstant` 精确措辞，
  堵死 `Error(1)` / `OtherTop.C` 类初始化器逃逸。
- enum 旧合法 lookup 优先级、受限 int AST 白名单、前序值语义、整枚原子性
  保留（失败不发布、子树 skip、单条 `sema.class_skeleton`）。
- class initializer 采用 **earlier-only** 规则（动机见 §9）：只允许引用本类
  源码更早已求值 const / enum、全局数字常量 / 裸 enum、builtin 类型常量；
  脚本的前向、自引、继承、跨类依赖**显式拒绝**，不回退 global、不静默按
  文件序选值。本类 qualified `Current.C` 写法不支持，与跨类访问明确区分。
- class body 内普通使用不受成员书写顺序限制，支持 bare、static、继承与
  `Other.CONST` / inner canonical qualified access。
- 失败 class const 的名称 mask（不改公共 `ClassDef` / `ClassRegistry` 协议）：
  - `FrontendAnalysisData.rejectedClassConstants`：owner canonical class name →
    immutable `name -> FrontendRejectedClassConstant`（`VariableDeclaration`
    声明身份、reason、diagnostic anchor）；
  - skeleton 为唯一 producer，经 prepass 私有 builder 收集，在 skeleton 结束、
    scope 阶段开始前 freeze 发布；跟随 analysisData snapshot / freeze /
    discard，bootstrap 默认空；
  - const 失败**仅当自身 claim 成功才进 mask**——conflict 拒绝不 mask
    （名属主仍在）；parse-damage / 标注 / 求值失败 mask + fact + skip；
  - 成功值 table 不受影响；mask 绝不进入 constant materialize，不伪造零值 /
    Variant 成功值。
- frontend-only `FrontendClassConstantLookup`（`frontend/scope` 包）：
  单层 `probe(classDef, name, rejectedClassConstants)` 返回
  `Usable` / `TerminalBlocked` / `Miss`；`probeInHierarchy` 做 nearest-layer
  查找，遇失败 const 或 usable row 即停，也阻止本层非 const member 让
  ancestor constant 泄漏。mask 先查，同层合法成员冲突仍由 skeleton 诊断；
  不能越过失败 mask 找 ancestor。
- `FrontendScopeAnalyzer` 创建 `ClassScope` 时注入该只读 mask view；
  `ClassScope.resolveValueHere` 直接命中优先后查 mask（terminal
  `FOUND_BLOCKED`）；`resolveInheritedValueMember` 每层在成员检查后接共享
  probe——`Usable` 按原 `CONSTANT` 绑定发布、`TerminalBlocked` 无条件 blocked；
  blocked `ScopeValue` 携 `VariableDeclaration` 声明身份 + `CONSTANT` kind +
  Variant 占位 + `isConstantBound`（从不 FOUND_ALLOWED / 成功物化，不算伪造
  可用 Variant 值）。
- 值枚举侧同步抑制：`Scope.blockedValueNamesHere()` 让
  `collectVisibleValues` / `FrontendVisibleValueEnumerator` 累计 blocked 名集；
  `ClassScope.valuesHere` 逐层应用自身与祖先 mask；
  `ClassScope.blockedValueNamesHere` 返回当前类 + 全部祖先层 mask union（与逐层 blocked
  等效），`identifierCandidates` 沿词法链吸收到 value/method 两套 claim——
  masked `PI` / `INF` 不再经 `collectGlobalNamespace` 回漏。instance 补全面
  不做 mask 过滤（`instance.member` 解析本就不查 const mask）。
- qualified 侧 `FrontendChainReductionHelper`：`reduceGdccStaticLoad` 的层序
  探针为 method → static property → 共享 probe；`Usable` 发
  `resolvedStaticLoadTrace` CONSTANT/GDCC，`TerminalBlocked` 发
  `rejectedConstantLoadTrace`（Status.BLOCKED + RouteKind.STATIC_LOAD +
  `FrontendResolvedMember.blocked` CONSTANT/TYPE_META/GDCC/VARIANT +
  declaration=const AST）并停止祖先搜索。usable 仅消费
  `GdScriptEnumConstant` / `GdScriptEnumGroup` / `GdScriptEvaluatedConstant`
  三种声明形状（防御性过滤保留）。`reduceStaticMethodStep` 前置同一逐层
  claim 走查——本层同名方法交给原 resolver，usable const 终止为 FAILED，
  masked const 终止为 BLOCKED，父类 static 同名方法不得穿透子类 const 占名。
- **mask 域边界**：失败 const 的毒化域 = 常量可见域——裸标识符
  （`resolveValue`）与限定 type-meta 面（`Child.C` / `Child.C()`）。实例成员面
  `instance.member` / `self.member` 不属于 mask 域：该面经
  `ScopePropertyResolver` / `ScopeMethodResolver` / `ScopeSignalResolver` 解析，
  本就不暴露任何常量（成功 const/enum 成员在 `self.C` / `d.C` 同样不可达），
  mask 域与常量可见域精确同构。因此父 `var hp` + 子 `const hp = MISSING` 时
  `child.hp` 仍解析 `Parent.hp`——`hp` 与 `self.hp` 的诊断差异是恢复模式下
  的已知边界，而非正确性漏洞（skeleton error 已致模块编译失败）。若未来
  实现 Godot 兼容的成功常量实例访问（`self.C` / `d.C`），mask 探针须随该面
  一并扩展到三个 resolver。
- 标注类型验证：class skeleton 是该声明标注的唯一验证 owner（local 侧由
  inventory 承担，见 §6.1）；`FrontendConstDeclaredTypeSupport` 三态结果
  （no annotation / resolved / rejected，只用 strict type lookup，不调会
  warning + Variant 兜底的 `resolveTypeOrVariant`），错误标注报 error 并存
  declaration validation rejection，不当未标注推断成功。
- 注解 placement 仍由 `FrontendAnnotationUsageAnalyzer` 唯一 owner；const
  prepass 不因注解 placement 单独 skip 整声明、不抢报。
- `FrontendInterfacePhase.recordPropertyInitializer` 带 kind `VAR` 条件：
  class `CONST` 不进入 propertyInitializer suite / synthetic field init。
- class resolved 值落地三处：class table（`GdScriptEvaluatedConstant`
  declaration）、`declarationOrigins`、`constantFacts`。
- `ClassScope` 与 `FrontendChainReductionHelper` 泛化到新 record，保持
  nearest-layer terminal 遮蔽语义。

## 6. local pipeline、只读与 lambda

### 6.1 inventory / certificate

- `FrontendVariableAnalyzer.bindLocalConst` 与 `var` 相同的 same-block /
  same-callable 冲突规则，`defineConstant` 产出 `CONSTANT` + read-only 绑定。
- 标注验证：local inventory 是该声明标注的唯一验证 owner。
  `FrontendConstDeclaredTypeSupport.checkDeclaredType`：无标注 → `Variant`
  baseline、Resolved → 标注类型、Rejected → error 诊断 + `constantFacts`
  Rejected + `Variant` baseline，不做 warning + fallback。未标注 const 用
  `Variant` 作为**仅结构 baseline**（不代表运行时动态成功）；显式错误标注
  绝不能当未标注推断成功。
- `FrontendInterfacePhase` 以 `ORDINARY_CONST` 入索引（
  `FrontendBodyLocalDeclaration.Kind`），只收 **surviving** local const
  （`binding.declaration() == declaration`），不走 initializer 子树；
  declaration index / certificate 校验 kind / identity / 顺序；保留 for
  iterator / match prefix 行为，`FrontendBodyStructuralCompleteness` 把
  `ORDINARY_CONST` 与 `ORDINARY_VAR` 同等对待。
- inventory / Interface 扫描遇 duplicate 只报一次并 skip rejected duplicate；
  **已绑定但 initializer 失败**的 const 仍 inventory + `Rejected`，与
  **重名根本未绑定**的 const 区分——后者不强行补结构槽，compile 沿既有
  upstream error 停止。

### 6.2 求值 owner 与 overlay

- `FrontendStatementResolver` 让 const initializer 走常规 TOP_BINDING →
  LOCAL_TYPE_STABILIZATION → CHAIN_BINDING → EXPR_TYPE pipeline。
- `FrontendSemanticStage.CONST_RESOLUTION` 位于 `EXPR_TYPE` 之后、
  `VAR_TYPE_POST` 之前（后者跳过 const 的类型写入）；
  `FrontendBodyOwnerProcedures.runConstResolution` 按声明序求值、写
  `putConstantFact` Resolved/Rejected、写 `putConstTypeUpdate` exact 类型；
  以 effective env 读取前序 const 结果。
- owner 将 value / rejection 及最终声明 slot type 放 pending，经 flush →
  `FrontendConstResolutionPatch` → callable export；stable side table 不允许
  body runner 直接写。已有 annotation-validation fact 或未保留 binding 的
  声明不重复求值。
- 嵌套 suite 沿 typed env parent 读取 parent pending/committed const 事实；
  lambda 例外（§6.4）：其 typed env parentless，改经受限
  `FrontendConstFactView` 链读取。
- 精确类型采用 **export 时只读回填**（不改所有 scope API）：
  - `BlockScope.resetConstantType(name, declaration, type)`：仅 `CONSTANT`
    且 declaration identity 匹配生效；仅允许 `Variant` baseline → 精确类型或
    同型幂等；保留 `constant=true` / `writable=false` / `staticMember` 等
    flags；不得调用 `resetLocalType`；
  - `FrontendConstResolutionPatch` 携带 `FrontendConstTypeUpdate` 列表；
    `FrontendAnalysisData.applyConstTypeUpdate` 在 callable export certificate
    完成后唯一调用（仅 `CONST_RESOLUTION` stage、declaration identity 与
    Variant-baseline 校验），并刷新已发布 `symbolBindings` 的
    `resolvedValue` payload；body runner 阶段仍仅 overlay，不提前写 scope；
  - `FrontendInterfacePhase` baseline / index 保留原 identity，不后来重跑
    certificate 把 export 精确类型误当 baseline；
  - `FrontendTypedLexicalEnvironment.effectiveScopeValue` 的 CONSTANT overlay
    （constSlotType）让后续语句即见 exact 类型——`var x := C` 推断 int、
    use-site LIR 槽类型与物化一致；scope 导出时才永久 reset；
  - 导出后 `Scope.resolveValue` / `valuesHere`、
    `FrontendVisibleValueEnumerator`、`FrontendSnapshotCompletionService`
    直接从 scope 读精确类型；`Rejected` 不回填成功 type。

### 6.3 失败遮蔽与 compile gate

- 失败 const：保留结构 inventory + `Rejected` 事实；名字仍遮蔽、传播失败
  但不产生二次根因 diagnostic；compile 停止。不在 body 之后删 scope。
- compile gate：`FrontendCompileCheckAnalyzer.handleVariableDeclaration` 与
  `scanSlotTypeCompileBlocks` 检查可达 local const 的 `Resolved` + final
  type 一致；class 全集（含 unused）经 skeleton validation / compile gate
  覆盖；unused const 仍验证，不延迟到首读。
- local const 名字规则：`const X = X + 1` 在声明前用 `X` 可命中外层（沿用
  var 外层回退），无外层则诊断；声明后失败 const terminal invalid，不退外层。
- 旧 enum 模式继续 int-only AST 白名单；允许前序类 const 的 int identifier，
  但不自动开放 constructor / 属性 / float / 条件表达式给 enum；整枚原子性保留。

### 6.4 CFG 与 lambda

- const 声明在 CFG 中不 `buildValue(initializer)`、不创建 mutable
  `LocalDeclarationItem` / slot，降级为 `SourceAnchorItem`（只留行号锚点）；
  每次读 materialize frozen payload。
- lowering `CONSTANT` 分派：class `GdScriptEvaluatedConstant` 直接取 value；
  local `VariableDeclaration` 经 stable `constantFacts[decl]`（必须
  `Resolved`）；统一经
  `FrontendBodyLoweringSession.materializeConstantValue(block, value,
  resultSlotId)` 物化（§7.1）；`bind.declarationSite` 不得错当 class record，
  否则 fail-fast。
- 非法 const initializer 内的 lambda：`FrontendLambdaIdentityAnalyzer` 与
  `FrontendLoweringFunctionPreparationPass.collectLambdaContexts` 跳过所有
  CONST initializer subtree；`FrontendVariableAnalyzer` 既有 lambda capture
  scanner 的 const skip 保留；const initializer 内 lambda 不建 lambda plan /
  ordinal / capture / HRX shell（报 `sema.unsupported_expression_route`，
  由单 owner 拒绝，不占 ordinal，其后合法 lambda ordinal 不漂移）。
  非 lambda 普通 const 表达式沿原 pipeline。
- lambda 与 const 事实视图：
  - `FrontendSuiteResolver.resolveCallableOwner` 对 lambda 创建 **parentless**
    typed env（普通 block child 有 parent），lambda 不能笼统声称共享 typed
    parent；
  - `FrontendConstFactView`：按 `VariableDeclaration` 身份读取 `Evaluation`
    结果的只读 view；`FrontendTypedLexicalEnvironment.constantFact` 查找序为
    current pending → committed → stable → parent → constFactView（仅
    parentless env 生效）；
  - nested lambda 构造时从 outer effective env 抓取该受限 const-only view
    链，支持跨多层 lambda 传递；use-site resolver 仍按声明顺序过滤
    （`FrontendLambdaCapturePlanner.firstVisibleValueHit` 按层应用
    `VariableDeclaration` 声明序过滤——裸 `resolveValue` 会越过源码序使
    lambda 之后的同名 const/var 遮蔽属性命中）；
  - lambda 通用 typed parent 保持 null；不允许 slotType / expressionType /
    mutable local 绕 capture 读取；只共享 immutable const 值 / 失败状态；
  - 同一 outer statement 的 pending const 在合规引用时可读，但不放开
    self-initializer；
  - 外层 const 不进 capture；真实 mutable locals 仍走原 capture 路径；
    不为读纯 const 捕获 self。

### 6.5 只读写回覆盖

- 覆盖 bare / qualified、`+=`、成员 / 下标的 POD writeback 链：沿最终
  receiver provenance 追到常量根即拒绝（`C.position.x = ...`、
  `C[0] = ...`），不只是 bare `writable=false`。
- 赋值目标身份：`FrontendAssignmentSemanticSupport.resolveIdentifierTarget`
  以当前 statement 的 pending-aware `symbolBindingResolver` 已发布 binding
  固定 declaration 身份及 `valueAccessStatus`（源码序过滤 + mask 已内嵌于
  binding），再校验 writable 与整条 POD provenance；不重新结构查同名后序
  const。binding 尚未发布时经同一 `FrontendVisibleValueResolver` / use-site
  restriction 路径取身份，不用 scope raw lookup 兜底。
- `frozenConstantTargetIssue` / `frozenConstantHeadIssue`：head identifier
  常量（`V.x` / `V[0]` / `V.position.x`）与非终末 `CONSTANT` 成员步
  （`Other.C.x`、`Vector3.ONE.x`）统一拒绝「frozen … cannot be assigned
  through a member or element write」；值产出调用切断写回溯源——head/mid
  常量检查只作用于最后一个 `suggestedCall` 步之后的区段（`V.normalized().x`
  不误判）；enum group（`GdScriptEnumGroup` / `EnumDeclaration`）保持容器
  语义不受影响。
- CFG `writableRoute` 使用同一已固定身份，lowering 不做第二次 AST 选名。
- 只读判定沿完整 `reduced.stepTraces` 各 prefix：TYPE_META `Other` /
  `Inner` / `Vector3` 等 head 之后中途出现**用户 const / builtin POD value
  const** 即只读 root；`SubscriptExpression.base` 若为 `AttributeExpression`
  同样检查其完整规约 trace。目标是需 reverse writeback 的 POD 路径，不无
  差别拒绝任何曾含 `CONSTANT` 的链（临时 copy、只读 method return 不借此
  前缀误判）。`Other.State["IDLE"] = ...` 维持既有 Dictionary 语义不变
  （写保护差异为既有行为，见 `frontend_enum_implementation.md`）；
  `State.IDLE = ...` 照旧拒绝。
- 验收例：类 property `n`，函数内 `n = 1; n += 1; const n = 2` —— 前两条
  写命中外层合法，声明之后 `=` 与 `+=` 拒绝；`var copy = C; copy.x = ...`
  独立值仍允许。

### 6.6 参数默认 island 边界

- `parameterDefaultIslandLocalHit` 同时识别 body 顶层 `LOCAL` 与 `CONSTANT`：
  无外层同名时 local const 产生 island visibility 违规，而非普通 unknown。
- 默认表达式处于 `CallableScope`、不在 body scope；合法命中 class / global
  常量按现有 var 相同规则使用外层——body 同名 const **不**先于 class /
  global 阻断（有意边界，见 §9）。
- `FrontendVisibleValueResolver` PARAMETER_DEFAULT：若实际 current lexical
  layer 有 block `CONSTANT`（local decl）与 `LOCAL` 一样 blocked；class
  constant 不按 `CONSTANT` kind 一刀切封禁。
- 验收：无外层 `func f(a = LOCAL_C): const LOCAL_C = 1` → 拒绝一条
  metadata / island 诊断；class `const X = 9` + `func f(a = X): const X = 1`
  → 默认 = 9 合法，不泄漏 body 值 1。

### 6.7 工具链与 match

- `FrontendAnalysisInspectionTool`：不把 const 祖先一律 hardcode
  unsupported；source const 按 published facts 展示，其 initializer 属
  intentionally not materialized（非 runtime CFG），不伪装未支持。
- `FrontendSnapshotQueryService`：local AST 声明 kind CONST 仍
  `DocSymbolKind.CONSTANT` 但 `ownerName = null`（直查 `scopesByAst`，
  `nearestScope` 的 `ReadOnlyScope` 包装会使 `instanceof BlockScope` 失效）；
  class 新 record 经 `declarationOrigins` 定位 owner；completion 刷新按
  §6.2 export 回填。
- match key 只开放**已 Resolved 的 identifier / qualified 声明常量**：class
  `GdScriptEvaluatedConstant` 须带成功 value；local CONST
  `VariableDeclaration` 须 `constantFacts` `Resolved`；`Rejected` 不得仅凭
  `binding.kind == CONSTANT` 成为常量 key
  （`FrontendMatchSupport.isConstantPatternOperand`）。不引入通用
  constantExpressionValues 表、不
  扩大 match 支持面；`MY_VEC.x` / `-1` 等 pattern 现支持规则原样（用户可
  `const K = MY_VEC.x` 后用 `K` 作 key）；enum Dictionary 下标仍非编译期
  常量。

## 7. LIR 物化、文本精度与 String 编码

### 7.1 常量值的 LIR 物化（复用现有指令，无新增 opcode）

- `GdConstantValue` 是唯一 truth；使用处统一经
  `FrontendBodyLoweringSession.materializeConstantValue(block, value,
  resultSlotId)` 物化冻结 payload，只读值模型、不重跑原 AST。
- scalar `BoolValue` / `IntValue` / `FloatValue` / `StringValue` → 已有
  `literal_bool` / `literal_int` / `literal_float` / `literal_string`。
- `BuiltinValue`：先把规范化 leaves 物化为精确 typed scalar 临时
  `LirVariable`（float leaf 发 `literal_float`——值已 `(float)` 规范化后提升
  double；int leaf 发 `literal_int`——int32 guard 在前端完成，scalar 类型本身
  int64），再按**固定 canonical construction recipe** 发
  `ConstructBuiltinInsn`：operands 全 `VariableOperand`，目标类型来自 result
  `LirVariable`，不把 typeName / 立即数塞 `construct_builtin`。
- 固定 recipe（全部优先现有 exact API 签名，不依赖 flat-float shim、不新增
  source 语法）：`Vector2/3/4` = 2/3/4 float；`Vector2i/3i/4i` = 2/3/4 int；
  `Rect2` / `Rect2i` = 两个 `Vector2` / `Vector2i`(position, size)；`Plane` =
  4 float(normal xyz, d)；`Quaternion` = 4 float(x,y,z,w)；`AABB` = 两个
  `Vector3`(position, size)；`Basis` = 3 `Vector3` 逻辑列 (x,y,z)；
  `Transform2D` = 3 `Vector2`(x,y,origin)；`Transform3D` = `Basis` +
  `Vector3`(origin)；`Projection` = 4 `Vector4` 逻辑列 (x,y,z,w)；
  `Color` = 4 float(rgba)。所有值（含 default / copy）都从 final payload 按
  recipe 重建，不再走 axis-angle / normalization / 矩阵运算。先物化
  component、再 parent，保证临时类型 / 声明与顺序正确。
- backend 只接受 exact args，不负责 frontend implicit conversions；不靠运行时
  重算补值或校验；mixed widths / error 不走 runtime 补救。
- `ConstructBuiltin` result 需 non-ref；若消费点需 ref target 或不同 typed
  boundary，先 non-ref scratch 构造再走既有 Assign / pack / unpack / owned
  copy 路径，不改变后端 non-ref 合同。临时 value 槽不是 const 声明 storage；
  const 本身不建 mutable local / property / static field。
- 运行时成本：生成 Godot `godot_new_*` ctor wrapper，runtime 需初始化
  Godot API 并有临时 / 调用开销；AOT 编译期 evaluator 不依赖 live Godot。
  这里只是**物化冻结结果**，不执行原 AST 的 operators / member / utility /
  user call；String 本来也 runtime 物化，同一边界。
- backend 不写 aggregate renderer、不改 generated headers；`Basis` 与
  `Transform3D.basis` 由现有 Godot 分量 ctor 处理物理 rows，不新增自己的
  列→行 C 转置；canonical 逻辑列 + metadata decoder 须保持准确（非对称
  测试防把 rows 误作 columns）。
- LIR 文法 / opcode / `ParsedLirInstruction` 不因 const 新增分派；class
  `scriptConstants` 表不序列化，只序列化使用处现有 literal + ctor 链。
- 已知优化方向（非当前合同）：若 profile 显示 construct 常量参数链有成本，
  可独立做 backend 到 C aggregate 的优化，不新增 `literal_builtin`。

### 7.2 LIR 文本精度与 roundtrip

- 解析精度：仅 `TokenKind.FLOAT` 分支下 `OperandKind.FLOAT` 与 `GENERIC` 用
  `Double.parseDouble`；`GENERIC` 的 INT 仍 `Long.parseLong` 不变。
- 非有限 canonical 输出沿用 serializer 的 Java `Double.toString`：`NaN` /
  `Infinity` / `-Infinity`；`-0.0` 保留；tokenizer 将其显式识别为 FLOAT
  operand（不被 IDENT / label 吞），兼容输入 `INF` / `NAN` 别名但
  serializer 唯一输出上述；普通 finite 不变。
- 分量标量 literal 的 operand 类型固定：整数分量用 `IntOperand`（含 int32
  端点）；float 分量用 `FloatOperand`，finite 整数值也序列化为 `1.0` 等
  浮点形而非 `1`；roundtrip 类型不靠猜。`construct_builtin` 的 operands
  全为指向这些精确类型临时值的 `VariableOperand`，不接受分量立即数。
- simple serializer / parser / DOM roundtrip 对上述 literal + construct 链
  互逆，无新 opcode。

### 7.3 String 长度感知与 LIR 字符串文本编码

- String 走现有 `literal_string` owning 物化路径，无 static cache。
- `NewDataInsnGen.emitStringLiteral` 统一使用
  `godot_new_String_with_utf8_chars_and_len` /
  `godot_string_new_with_utf8_chars_and_len`（含 inplace / ref target），
  长度参数为同一 **UTF-8 byte[] 长度**（不是 Java `String.length`）。
  `CBodyBuilder.valueOfCUtf8BytesLiteral` + `CUtf8BytesLiteralValue` 存预编码
  byte[]，literal 与长度同数组导出；StringName / NodePath / `GD_STATIC_S`
  路径不动，const 不绕 NUL 终止缓存。
- C literal 编码用 `StringUtil.escapeCUtf8BytesLiteral(String decoded)` /
  `escapeCUtf8Bytes(byte[])`——先取 UTF-8 bytes，每个非安全 byte 用**固定
  3 位八进制**（含 `\000`），ASCII quote / backslash 有专门 escape，后跟
  octal / hex 不贪婪。不复用 `escapeStringLiteral`（其对 NUL 输出 `\u0000`，
  而 C 禁止低码位 UCN）；`escapeStringLiteral` 其它用途语义不变。
- 保留 embedded NUL、非 ASCII、raw / 多行、转义；String copy / return /
  Variant boxing / lifecycle 稳定；使用处对象构造不算 const AST 运行期求值。
- LIR 文本序列化（simple `StringOperand` 与 DOM）XML-safe 转义与对称解析，
  由 `StringUtil.escapeLirStringLiteral` / `unescapeLirStringLiteral` 承担：
  - 兼容现有 `\n` `\r` `\t` `\\` `\"`；XML 1.0 禁用控制字符与未配对
    UTF-16 surrogate 用固定宽度 `\uXXXX`（4 hex）文本逃逸；真实反斜杠先
    转义，避免 literal 六字符 `\u0000` 与实际 NUL 混淆；
  - decoder 严格四位 ASCII hex 解析（显式校验、拒绝 `+`/`-` 号与非 ASCII
    数字），畸形 / 截断 / 未知转义 / 未闭合抛 `LirInsnParsingException`；
  - simple serializer 与 parser 互逆，DOM 只嵌入 XML-safe textual
    instructions，绝不把真实 U+0000 写进 XML；
  - AOT 源码 decoded string 仍遵守 `StringUtil` 既有 Unicode 合法性；LIR
    序列化不因 XML 丢值。LIR `\uXXXX` 文本逃逸与 C 八进制编码是两个不同
    格式，不混用。

## 8. 诊断与恢复

- 通用：每个 const 声明的源错误由**单 owner** 发诊断，锚定声明或最小
  offending 节点；源错误不得 throw programmer guard；坏声明不影响合法
  sibling（class 联合 prepass 按声明隔离，enum 整枚原子性除外）；parse
  damaged 子树不添加可用 facts。
- 纯 evaluator 只返回 `Rejected(reason, offendingNode)`；owner 用现有
  typed fact 状态与诊断 / skip ownership 决定发不发。
- 失败类别分流表：

  | 失败类别 | 诊断 owner | 类别 / 行为 |
  |---|---|---|
  | parser 错误 | 既有 parse owner | 不重复 |
  | TOP_BINDING 未知 / blocked 名 | 既有 binding owner | `CONST_RESOLUTION` 只存 `Rejected`，不重报 |
  | CHAIN / EXPR 类型失败 | 既有 chain / expr owner | 同上只存 `Rejected` |
  | 类型合法但不在 const 白名单 / 实际执行分支除零、越界 | const owner 首报 | local 类别 `sema.constant_resolution`；class const prepass 由 skeleton 锚定、类别 `sema.class_skeleton`；enum 仍 `sema.class_skeleton` 不变 |
  | 引用 `Rejected` const | 引用点 binding / chain owner | 一条「依赖常量无效」，不复述原根因；expr / const / compile 不重复 |
  | 参数默认 island | default metadata owner（唯一协调者） | 沿用现静默 blocked + metadata owner 回收机制：引用点 owner 已报 error 则不再补 root error；若按 island 合同由 metadata 首报则 binding 静默；总计一条。ordinary body default 不共享全局单 owner 开关 |

- 精确验收：`const BAD = Missing + 1; const NEXT = BAD + 1` —— `Missing`
  原始 binding error 一条 + `NEXT` 依赖 error 一条；`const BAD =
  some_pure_looking_user_call()`（类型合法）由 const owner 拒绝一条；选中
  分支除零 1 条、未选中 0 条、dead 分支未知名仍 1 条；class 失败被
  default 引用时 metadata 撤回且不重复 root 诊断。
- const initializer 内的下游复述抑制：`publishExpressionType` 的
  `suppressUpstreamConsequenceDiagnostics`（仅 const 初始化器）按**逐表达式**
  判定——子树内已有上游 error 的 entry 不再复述，独立 expr 失败照常上报；
  upstream 诊断集在循环前快照，防止同 pass 内已发诊断干扰迭代顺序。
- registry handler 失败分流：handler domain error → `Rejected` + const owner
  单条诊断；handler unexpected exception / 返回错误类型 → programmer
  invariant（非源错误）；未注册 / 签名不满足 → `Rejected`（无 runtime call
  fallback），按上表 owner 规则发。
- 权限 blocked 前的 poison 检查：`bindIdentifier` / chain owner 在发
  generic permission-block 错误前，按 declaration 身份先查
  `rejectedClassConstants` / `constantFacts`（`CONSTANT` kind +
  `VariableDeclaration` 声明 + Rejected fact 三重结构判别）；命中 poison
  报「依赖常量无效」而非「not accessible」；真正权限 blocked 保留原信息。
  常量失败的 root / typefact 不被当作可用 Variant。
- 短路预验证用纯 type / helper（`FrontendBuiltinOperatorSupport`），不把有
  诊断副作用的 body owner 当纯 validator。
- 拒绝理由区分：不支持形状（feature 拒绝）、求值失败（除零 / 越界 / 非法
  shift / 类型不符）、名字规则（前向 / 遮蔽 / blocked）；复用
  `FOUND_BLOCKED` 表达占名非法。

## 9. 设计取舍与已知限制

- **class const initializer 仅依赖同类更早常量（earlier-only）**：脚本声明
  常量之间的依赖——本类前向 / 自引 / 继承（祖先）/ 跨类 initializer 引用
  均显式拒绝；不撤销全局数字常量 / 裸 enum / builtin 类型常量白名单；
  普通读取（class body / local / qualified / 继承）不受此 initializer 依赖
  限制；local const 前序词法规则不变。动机：class/member 按源码填充
  skeleton，引用尚未求值的后序 const 时值未定；若按文件顺序或遮蔽规则
  静默回退，可能选错值且无诊断。
- **未选方案（未来独立任务）**：完整前向 / 跨类依赖语义——跨类声明 header
  inventory、const initializer 依赖图、继承拓扑 + DFS 三态求值、循环依赖
  诊断、named enum type-meta 早发表解耦（示例 `A.X = B.Y` / `B.Y = A.X`
  循环）。这是设计选择留下的扩展空间，不是当前门槛。
- **mask 域 = 常量可见域**：失败名毒化只覆盖裸标识符与限定 type-meta 面；
  实例成员面不经 const mask（该面本不暴露常量），`child.hp` 仍能命中
  `Parent.hp`（§5 边界条目）。若未来实现成功常量实例访问，mask 探针须
  随 `ScopePropertyResolver` / `ScopeMethodResolver` / `ScopeSignalResolver`
  一并扩展。
- **生产 registry 为空**：`FrontendConstCallableRegistry` seam 完整，但生产
  无任何注册项——`V.length()` 等候选方法仍 `Rejected`。逐条注册是独立的
  增量工作（每条须补数值 / 边界测试），不改变 evaluator dispatch 合同。
- **参数默认 island 有意边界**：body 同名 const 不先于 class / global 阻断
  （默认表达式在 `CallableScope`，源码探针在 ordinary lookup miss 后触发）。
- **annotation literal-only 不变**、**enum Dictionary 下标非编译期常量**、
  **`Other.State["IDLE"] = ...` 保持容器写语义**：均为既有行为，不在 const
  合同内改动。
- **`selectCall` 无生产 override**：两个生产 `LookupContext` 均用默认实现；
  「核对已发布 CHAIN/EXPR call facts」的 override seam 已留好但当前无
  生产消费者。
- **flat-float 内部编码不开放**：metadata 的 6/9/12/16 scalar 构造文本只对
  decoder 有效，不是用户 source 语法。
- **物化成本**：构造链在 runtime 走 `godot_new_*` wrapper；必要时后端可
  独立做 aggregate 优化（§7.1），不是当前合同。

## 10. 回归锚点

### 10.1 单元 / 集成测试类

- 值模型与求值：`GdConstantValueTest`、`FrontendConstantEvaluatorTest`、
  `FrontendConstCallableRegistryTest`、`FrontendBuiltinConstantDecoderTest`、
  `FrontendEnumConstantEvaluatorTest`（旧行为锚定）。
- parser 形状：`FrontendConstParseBehaviorTest`（gdparser 0.6.0 可表达
  形状锚定：`const X = v` / `const X: T = v` / `const X := v` 为
  `DeclarationKind.CONST`；`static const` CST 报错；无初始化器为
  `ErrorStatement`）。
- class / skeleton / lowering：`FrontendConstSkeletonTest`、
  `FrontendConstLoweringTest`、`FrontendEnumSkeletonTest`（联合 prepass 后
  断言锚定）、`FrontendEnumScopeTest`、`FrontendEnumBodyBindingExprTypeTest`、
  `FrontendEnumChainBindingTest`、`ClassScopeTest`、
  `FrontendScopeAnalyzerTest`、`CompletionServiceTest`。
- local pipeline：`FrontendLocalConstResolutionTest`（含 lambda / island /
  match / 只读 / compile gate 用例）、`FrontendVariableAnalyzerTest`、
  `FrontendVisibleValueResolverTest`、`FrontendTypedLexicalEnvironmentTest`、
  `FrontendLambdaCapturePlannerTest`、`FrontendCompileCheckAnalyzerTest`、
  `FrontendCfgGraphBuilderTest`、`FrontendLoweringBodyInsnPassTest`、
  `FrontendBodyStructuralCompletenessTest`。
- 物化与序列化：`FrontendConstantMaterializationTest`（含 simple/DOM
  roundtrip）、`CConstructInsnGenTest`（全 16 类型 recipe 对真实
  `extension_api_451.json` 校验 `godot_new_*_with_*` 发射）、
  `SimpleLirBlockInsnParserTest`、`SimpleLirBlockInsnSerializerTest`、
  `CNewDataInsnGenTest`、`CFloatLiteralSupportTest`、`StringUtilTest`、
  `CUtf8StringLiteralRuntimeProbeTest`（DOM roundtrip → C codegen →
  zig/tcc 编译运行，embedded NUL 与多字节 UTF-8 长度锚定）。

### 10.2 e2e 脚本配对与运行时 smoke

- `src/test/test_suite/unit_test/script/const/` 与
  `src/test/test_suite/unit_test/validation/const/` 共 6 对：
  `scalar_constants`（类级标量：推断精确类型 / 标注转换 / 前序引用 /
  白名单运算 / `PI`·`OK`·`Control.PRESET_CENTER` 全局与 engine 常量输入，
  validation 以 `get_property_list` + `class_get_integer_constant_list`
  断言 const 不进 ClassDB）、`local_constants`（local const 链、嵌套块
  作用域、`const X = X + 1` 外层回退与声明后遮蔽）、`builtin_constants`
  （非对称 `Basis` / `Transform3D` / `Transform2D` / `Projection` 列布局、
  `Vector4i` 独立物化、`Vector3.UP` metadata 常量投影、float 分量算术）、
  `usage_contexts`（named-enum 成员初始化、inner-class 限定读兼 local
  const 限定折叠、`match` const key、参数默认 island、lambda 读外层
  const 不捕获、static 方法读经 `ClassDB.class_call_static`）、
  `control_flow_consts`（if/elif/else、for-header `range(const)`、while、
  嵌套 match、两级嵌套块遮蔽、early-return、运行时 ternary）、
  `nested_class_consts`（inner class 前序链、限定读、qualified match
  key、qualified 默认 island、兄弟 inner class 互读、两级嵌套静态
  访问器——`Outer.Inner.N` 直接多级限定链不支持）。经
  `GdScriptUnitTestCompileRunnerTest.compilesAndValidatesConstScripts`
  factory 运行，`EXPECTED_SCRIPT_PATHS` 含 6 条 const 路径。
- `GdccConstRuntimeSmokeTest`（`backend/c/build`）：临时 Godot 工程
  运行时检查（非对称 Transform3D 逻辑列、继承限定读、跨类读、local
  const 限定折叠、match/default/lambda/static、双节点 property-leak）；
  `constReadsLowerToPayloadMaterializationOnly` 断言 const 区域只含纯
  物化（literal / `construct_builtin` / assign / 边界转换），无 unary /
  binary / call / static / property / variant-get/set / 对象与 lambda
  构造回放，`construct_builtin` 全 `VariableOperand`，local const 无
  具名槽；`constDeclarationsLeaveNoRuntimeState` 断言无 `LIRProperty` /
  `_field_init_*` / 常量名合成函数，scriptConstants 全为
  `GdScriptEvaluatedConstant`；`classConstInitializerRejectsFrozenDependencyMatrix`
  锚定 forward / self / dependent-poison / ancestor / inner-class /
  top-level cross-class 六向 `sema.class_skeleton` 拒绝；
  `failedConstNameMaskBlocksAncestorAndGlobalFallback` 断言失败名 mask
  在 scope 层阻断祖先与全局（`PI`）回退。Zig / `GODOT_BIN` 不可用时
  显式 skip，不算通过。

### 10.3 断言精度约定

- float 断言用 absolute + relative tolerance 或 `is_equal_approx` /
  `is_finite`；整数 / 类型 / 布局 exact；`NAN` 用 `isNaN`；`-0` 验
  raw bits（`doubleToRawLongBits` 区分 ±0）；不允许宽松比较掩盖问题。
- 引擎 validation 只经 `target.call` 方法拿值，不直接访问 AOT 类 const
  字段（验证 AOT-only 边界）。
