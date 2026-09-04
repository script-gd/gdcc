# Frontend 函数参数默认值实现说明

> 本文档是 source function 参数默认值（`func f(a, b = 5):`，调用点可省略尾部实参）的长期事实源，
> 覆盖 frontend sema、lowering 与 C backend binding/wrapper 的完整链路。
> 本文档取代已归档的 `frontend_parameter_default_plan.md`，不保留分步骤实施记录与进度状态。

## 文档状态

- 状态：事实源维护中（语义、pipeline、synthetic shell、backend 接线与 argc 感知 wrapper 均已落地并端到端闭环）
- 更新时间：2026-09-04
- 适用范围：GDScript source function（instance / static）声明的参数默认值。
- 关联文档：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_variable_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_func_pre_pass_implementation.md`（§7 Parameter Default 冻结合同、§8 shell-only 合同）
  - `doc/module_impl/frontend/frontend_exact_call_extension_metadata_contract.md`
  - `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/module_impl/backend/godot_binding_implementation.md`（wrapper/userdata 的 C 侧细节事实源）
  - `doc/module_impl/backend/call_method_implementation.md`（调用点补全的 C 侧细节事实源）
  - `doc/gdcc_low_ir.md`（`default_value_func` 参数元数据）
  - `doc/gdcc_type_system.md`
- 明确非目标：
  - **constructor / `_init` 参数默认值（永久非目标）**：GDExtension class constructor 回调没有实参通道，runtime 入口只调用零参 `_init`；有参 `_init` 本身不可实现，参数默认值自然不支持（见 §7）。
  - 默认表达式引用**先行参数**（`func f(a, b = a)`；Godot 允许，gdcc 当前发诊断，见 §7）。
  - **ClassDB `method_info.default_arguments` 注册**：默认值不注册为 bind 期 Variant 常量（`default_argument_count` 恒 0）；引擎侧/dynamic 路由的缺参由 argc 感知 callee-prologue wrapper 承载（§5）。
  - signal 声明参数默认值、`@rpc` 参数、`Callable.bind` / callable-value invocation 的参数省略。
  - 无类型参数（`func f(x = 42)`）从默认值**推导参数类型**（Godot 会推导为 `int`；gdcc 保持 `Variant`，见 §7）。
  - 默认表达式内的 `await`（fail-closed）。
  - lambda 参数默认值（fail-closed）。
  - override 默认值继承/合并语义（见 §2.4 与 §7）。

---

## 1. 语义规则（对齐 Godot 4.x）

以 godotengine/godot 的 `modules/gdscript` 为准，gdcc 复现以下用户可观察语义：

1. **顺序规则**：必填参数前缀 + 带默认值参数的连续后缀 + 可选 variadic 尾部。带默认值参数之后不允许再出现无默认值参数；variadic 参数必须最后且不能有默认值。违反即 error diagnostic（对齐 Godot parser 错误 "Cannot have mandatory parameters after optional parameters." / "The rest parameter cannot have a default value."）。
2. **默认值是普通表达式，不要求常量**：字面量、常量/枚举引用、容器字面量、builtin 构造器、函数调用等均合法；每次调用且对应参数被省略时**重新求值**（数组/字典等可变默认值不得共享静态实例）。
3. **求值时机**：语义上是"调用发生且缺参时按声明顺序求值"。实现合同：默认表达式降低为 hidden synthetic function；静态路由由 backend 在调用点对省略参数逐一调用对应 synthetic function（caller-side 补全），dynamic/引擎侧路由由 argc 感知 callee-prologue wrapper 补全（§5）——两条路径在用户可观察语义上与 Godot callee prologue 等价（每次调用重新求值、声明顺序求值），且满足 LIR 既有 `default_value_func` 合同。
4. **类型规则**：
   - 显式类型参数：默认值表达式类型必须与声明类型赋值兼容（复用既有赋值兼容/隐式转换规则）；Object 类型允许 `null` 默认值。
   - 无显式类型参数（`x = 42`）：不推导，参数类型保持 `Variant`（与 Godot 的推导行为存在已知偏差，见 §7）；`x = null` 同样为 `Variant`（Godot 也退化为 Variant）。
5. **可见性（受限）**：
   - **instance 方法**：默认表达式可引用字面量、常量/枚举/类型/singleton、builtin 构造器、utility/global function 调用，以及 **`self` 与实例成员**（实例属性/方法调用）；此时 synthetic default 函数首参携带 `self`（§4.2）。
   - **static 方法**：禁止引用 `self` / 实例成员（对齐 Godot static 限制），只允许调用帧无关的名字。
   - 两者均**不允许**引用参数（含先行参数）、局部变量、capture；source `const`（类常量/局部常量）仍属 deferred 边界。违反时 fail-closed 诊断（见 §6）。
6. **arity 规则**：省略只能发生在带默认值后缀；实参数 `< required 数` → too-few error；非 vararg 时实参数 `>` 参数总数 → too-many error。该规则在 resolver 层由 `ScopeMethodResolver` 实现，source function 的默认值元数据流入既有检查。
7. **默认值中调用的函数**自身仍需满足全部调用规则；void 调用的结果不得作为默认值。

---

## 2. 支持面与路由边界

### 2.1 声明侧支持面

- `FunctionDeclaration`（instance / static）参数允许默认值。
- 参数为 variadic 时不允许默认值（诊断）。
- constructor（`_init`）参数默认值：不支持（GDExtension 内在限制，永久非目标），维持有参 `_init` 的既有拒绝路径。
- lambda 参数默认值：维持 fail-closed。

### 2.2 默认表达式形态

在 §1.5 的可见性限制内，默认表达式复用既有表达式语义分析全能力（字面量、容器字面量、构造器、链式成员/调用、三元、运算符等）。超出可见性限制的子树按 §6 诊断并跳过，不影响同 module 其他合法子树。任何新形态必须先扩展 domain 规则再开放，不得因普通表达式管线已存在而静默放行。

### 2.3 调用侧支持面（允许省略实参的路由）

- bare call 解析到本 class 可见的 source function（lowering 为 `CallMethodInsn` / `CallStaticMethodInsn`）。
- exact instance method call（`FrontendResolvedCall` + `ExactCallableBoundary` 路由）。
- static method call。
- **dynamic / Variant 接收者调用与引擎侧入口**（`godot_Object_call` → `Object::callp` → `GDExtensionMethodBind::call`）：frontend/backend 编译期无法获得 callee 元数据，实参列表原样透传，缺参由 §5.3 的 argc 感知 callee-prologue wrapper 在运行时补全——该路径天然对齐逐调用求值语义。
- utility function 的 literal default 是既有独立能力，不属于本特性。

### 2.4 明确不支持（保持 fail-closed）

- 默认表达式引用先行参数：发明确 error diagnostic（instance/static 均禁止）。
- static 方法的默认表达式引用 `self` / 实例成员：发明确 error diagnostic（instance 方法允许，见 §1.5）。
- 默认表达式内 `await`：fail-closed。
- lambda 参数默认值、constructor 参数默认值：fail-closed。
- signal 声明参数默认值、`@rpc` 参数、`Callable.bind` / callable-value invocation 的参数省略。
- **GDScript 侧静态分析分歧**：`method_info.default_argument_count` 恒 0（§5.2），因此 GDScript 调用者对 GDCC 方法省略实参会被 GDScript analyzer 报静态 too-few——这是 GDExtension ABI 的固有分歧（注册真实默认值会让 VM 用 bind 期常量先行填充、prologue 失效），文档化不解决。
- **override 默认值语义**：只按静态解析到的 `FunctionDef` 的 `defaultValueFunc` 补全；不继承/合并父类默认值，子类 override 可声明不同默认值但只对静态类型为子类的调用生效；engine virtual override 的签名匹配不因默认值差异额外报错（签名仍不含 defaults）。dynamic/prologue 路径按实际注册类的 wrapper 执行。

---

## 3. Pipeline 集成与 owner 分工

遵循 `frontend_resolution_pipeline_implementation.md` 的阶段顺序，不引入依赖 typed readiness 的 body-entry gate。各阶段对参数默认值的职责：

| 阶段 | 类 | 职责 |
| --- | --- | --- |
| parse | `GdScriptParserService` + gdparser | AST 暴露 `Parameter.defaultValue()`，默认表达式属于参数 AST 子树 |
| scope graph | `FrontendScopeAnalyzer` | 遍历默认表达式，把参数与其默认表达式记录进同一个 `CallableScope` |
| lexical inventory | `FrontendVariableAnalyzer` | source function 参数照常登记 binding、不发诊断；lambda 参数默认值维持 `sema.unsupported_parameter_default_value` |
| class skeleton | `FrontendClassSkeletonBuilder` | `_default_` 列入 compiler-owned 保留前缀，用户成员撞名发 `sema.class_skeleton` 并 skip；`fillFunctionParameters()` 恒写 `defaultValueFunc = null`（元数据由 §3.1 owner 统一写入） |
| interface phase | `FrontendInterfacePhase` | 参数 baseline 不变 |
| 默认值分析（body 阶段开始前统一执行） | `FrontendParameterDefaultMetadataOwner`（由 `FrontendSuiteResolver` 驱动）+ chain/expr 分析 owner | §3.1 三阶段 sweep：结构校验 → 占位写入 `defaultValueFunc` → island 分析 → 失败回收 |
| body 语义 | `FrontendSuiteResolver` + chain/expr 分析 owner | 不再对默认表达式根发 unsupported 诊断；body 中的调用 arity 检查读取已定案的 `defaultValueFunc` |
| visible value | `FrontendVisibleValueResolver` + `FrontendBodySemanticSupportPolicy` | `PARAMETER_DEFAULT` domain 按 §3.2 island 受限开放 |
| 类型检查 | `FrontendTypeCheckAnalyzer` | 校验默认表达式类型与参数声明类型赋值兼容（§3.4） |
| compile gate | `FrontendCompileCheckAnalyzer` | compile surface 纳入**已接受**默认表达式根（§3.3） |
| lowering prep | `FrontendLoweringFunctionPreparationPass` | 为每个带 `defaultValueFunc` 的参数物化 hidden synthetic shell 并发布 `PARAMETER_DEFAULT_INIT` context（§4.2） |
| CFG | `FrontendLoweringBuildCfgPass` | `PARAMETER_DEFAULT_INIT`：复用 `buildPropertyInitializer()` 范式（§4.3） |
| body lowering | `FrontendLoweringBodyInsnPass` | `PARAMETER_DEFAULT_INIT`：复用 property-init 的 return-stop body lowering（§4.3） |
| backend | `CGenHelper` / `CallMethodInsnGen` / binding 生成 | 静态路由调用点补全（§5.1）；`defaultVariables`/`method_info` 通道恒空（§5.2）；`defaultValueFunc` 经 `defaultSlotCount` + 注册点 userdata 通道进入 prologue wrapper（§5.3） |

### 3.1 默认值分析 owner（单 owner、三阶段 sweep 合同）

默认表达式语义与元数据写入由 `FrontendParameterDefaultMetadataOwner` 唯一负责（由 `FrontendSuiteResolver` 在 body 阶段驱动、但不经由 `resolveSuite()`）；它是唯一允许改写 `LirParameterDef.defaultValueFunc` 的组件。skeleton 阶段恒写 `null`，消除"提前发布、失败后无法撤回"的悬空元数据窗口。

**触发时机与范围**：body 阶段、任何 callable body 解析开始**之前**统一执行三阶段 sweep。sweep **只**处理非 `_init` 的 `FunctionDeclaration`——`ConstructorDeclaration`、名为 `_init` 的函数、`LambdaExpression` 一律跳过（lambda 参数默认值维持现有 unsupported 诊断；有参 `_init` 维持既有拒绝路径；lambda 在 suite 阶段尚无 `LirFunctionDef`，对其改写参数元数据会 fail-fast）：

1. **结构校验与占位**：先做顺序规则校验（默认值后缀连续性、variadic 无默认值），违规参数发 `sema.invalid_parameter_default_order` 且永不进入后续阶段。对结构合法且 `defaultValue() != null` 的参数，立即通过 `LirFunctionDef.removeParameter(index)` + `addParameter(index, ...)` 成对原地改写，写入确定性 synthetic 名（此时默认表达式尚未分析）。**同名兄弟函数 fail-closed 前置**：若类内存在同名（任意 static 位/arity）兄弟函数，该函数的每个**结构合法**默认参数直接发 `sema.unsupported_parameter_default_expression`（锚定默认表达式根）并整体跳过占位写入与 island 分析；结构违规参数仍只携带顺序诊断，不重复发同名诊断。
2. **island 语义分析**：逐个默认表达式根按 §3.2 的 island 设计做 chain/expr 分析，发布 `symbolBindings()` / `expressionTypes()` / `resolvedCalls()` facts 到既有 AST-identity side tables——与 body 表达式同表同构，**不得**新建平行 side table 或第二语义 owner。占位元数据已就位，因此默认表达式中对其他 source function 的**交叉缺省调用**（`func g(x = f(1))`，`f` 带默认值）能读到 `f` 的 `defaultValueFunc` 并正确放行。
3. **失败回收**：分析失败（可见性违规、表达式 facts 不完整）的参数发 `sema.unsupported_parameter_default_expression`（单条同级诊断，锚定默认表达式根），并以同样的 `removeParameter`/`addParameter` 对把 `defaultValueFunc` 清回 `null`。

**闭包与已知角落**：

- body 阶段的 bare/exact call arity 检查（`getDefaultValueFunc() != null`）只看到已定案的元数据——失败参数自然按 required 处理，`f(x)` 缺参产生 too-few 诊断。shared `analyze(...)` 路径同样满足该闭包，不依赖 compile gate 兜底。
- 交叉引用角落：若 `f` 的默认值在阶段 3 被回收，而 `g` 的默认表达式已在阶段 2 带着省略实参解析了对 `f` 的调用，该 call fact 不做追溯失效——模块已因 `f` 的诊断 fail-closed，不会进入 compile/lowering。
- "被接受"的判定 = 可见性合规 + 表达式 facts 完整，**不含** 类型检查（type-check 在 suite 之后运行，见 §3.4）；类型不兼容的默认值保留 `defaultValueFunc`，由类型诊断 fail-closed 该模块。

### 3.2 `PARAMETER_DEFAULT` visible-value island

默认表达式经独立 island 分析（对标 property-init 的独立 expression-root 上下文，不复用 callable body context）：

1. **独立入口**：`resolveParameterDefaultIsland(...)` 独立分析入口；`PARAMETER_DEFAULT` 的 `entersSuiteResolver` 保持 `false`，**不**走 `resolveSuite()` / `FrontendBodyStructuralCompleteness`。
2. **专用 suite context**：`FrontendSuiteContext` 携带显式 `visibleValueDomain`——默认表达式 island 置为 `PARAMETER_DEFAULT`，**不得**依赖 `currentBlockRoot == null` 的 fallback（该 fallback 会错误得到 `EXECUTABLE_BODY`）；resolve restriction **继承 enclosing callable 的 instance/static 上下文**（instance 方法的默认表达式可命中 `self`/实例成员；static 方法的默认表达式按 static restriction 禁掉实例成员）。**island 的 `propertyInitializerContext` 必须恒为 `null`**——property-init 检查会把同类非 static 方法/`self` 误杀（`FrontendPropertyInitializerSupport`、`FrontendChainReductionHelper` 的 instance-method 拦截只认 property-init context）。
3. **owner 身份**：island 的 `callableOwner` 保持为 **enclosing `FunctionDeclaration`**（await/get-node 等 owner hook 依赖该形状，塞入 `Parameter` 会触发 `IllegalStateException`）；只有 lowering 的 `FunctionLoweringContext.sourceOwner` 才是 `Parameter` 节点。
4. **resolver 双重封口开放**：`classifyBoundaryEdge()` 对 island 请求的 `Parameter.defaultValue` 边放行进入 lookup；`classifyRequestDomainBoundary()` 允许 `PARAMETER_DEFAULT` domain 进入 lookup。非 island 请求穿越该边仍封口为 structural deferred boundary。
5. **域内过滤器（参数/局部停在本层）**：`PARAMETER_DEFAULT` 域命中 PARAMETER / CAPTURE / LOCAL 时**立即停在本层**（`FOUND_BLOCKED` 等价语义），禁止记入 `filteredHits` 后继续外查——否则 `static var a = 1` + `func f(a, b = a)` 会把 `a` 绑到外层 static 属性，与 Godot 语义相反。**实例成员命中不受此限**：instance 方法的默认表达式经 ClassScope 正常命中实例属性/方法。诊断由 owner 升为恰好一条 `sema.unsupported_parameter_default_expression`，不得退化为 `NOT_FOUND` 或绑到外层同名成员。配套地，`bindIdentifier` 在该 island 收到 `FOUND_BLOCKED` 时**不得**先发 `sema.binding`——由 owner 统一发锚定默认根的那一条诊断；`bindSelf` 仅在 **static** 方法的 island 中发该诊断，instance 方法的 island 中 `self` 正常绑定。
6. **await 边界**：`awaitFailClosedBoundaryReason()` 识别 parameter-default island 并返回默认值专用原因，由 owner 升为 `sema.unsupported_parameter_default_expression`，确保默认表达式内的 `await` 维持 fail-closed 且不误入 coroutine 分类。
7. **get-node 与 self 链式路径**：`resolveGetNodeExpressionType()` 与 `resolveSelfReceiver()` 特判 parameter-default island——`$`/`%` get-node 维持 fail-closed（并入 `sema.unsupported_parameter_default_expression`，不借用 "static function" 文案）；`self` 链式在 **instance** 方法的 island 中正常走 instance receiver 解析，在 **static** 方法的 island 中由 `resolveSelfReceiver`/`bindSelf` 统一汇入单条 `sema.unsupported_parameter_default_expression`，避免再发第二条 `sema.binding`。
8. **lambda 嵌套**：island context 的 `nestedLambdaResolver` 恒为 `null`；默认表达式内的 lambda 值维持 `tryResolveRecordedLambda` fail-closed 路径。
9. **expected type 接线**：island 分析入口把参数 slot 类型（interface baseline / `CallableScope` PARAMETER binding 的类型）作为 `expectedType` 传入表达式类型发布（否则 `func f(a: Array[int] = [1, 2])` 的容器字面量失去目标类型）；`Variant`/无类型参数按既有 `contextualExpectedOrNull` 规则丢弃。
10. 该 domain 的开放范围只覆盖 §2.2 形态；任何新形态必须先扩展 domain 规则再开放，不得静默放行。

### 3.3 compile surface

- `FrontendCompileCheckAnalyzer` 在 `walkCallableBody(...)` 中对每个**已接受**的参数默认表达式根显式 `walkExpression()`，重扫其 published facts（exprTypes 及存在的 bindings / resolvedCalls 均为 lowering-ready）。
- **"已接受"谓词**：compile gate 只消费已发布 facts、不回读 LIR 元数据——对 `parameter.defaultValue() != null` 且该根已存在 published `expressionTypes`、且默认根 range 跨度内无任何阻断性上游诊断的参数执行 walk（`resolvedCalls` 可选——纯字面量默认没有 call facts，不得把它当必需条件）；被 §3.1 拒绝的根（无 published facts，或 island 已在子树内发出诊断）不进入 compile surface，不重复包装。compile visitor 拿不到 class skeleton，**不得**按 name/static/arity 反查 `LirParameterDef`。

### 3.4 类型检查

- `FrontendTypeCheckAnalyzer` 有与 `visitPropertyInitializer` 同构的 hook：在 callable context 已设置时遍历该 callable 已接受的默认表达式根，校验默认表达式类型与参数声明类型赋值兼容（§1.4），不兼容时经既有赋值兼容通道发诊断、锚定默认表达式。
- 类型检查在 suite sweep **之后**运行；类型不兼容**不**回收 `defaultValueFunc`（默认值仍存在，只是类型坏了），arity 检查不受影响；模块由类型诊断 fail-closed。

---

## 4. Synthetic default function 合同

### 4.1 命名与元数据接线

- 命名：instance 函数 `_default_<func>$<param>`，static 函数恒为 `_default_s_<func>$<param>`。名字按 (owning class, function staticness, function name, parameter name) 确定性生成，class 内唯一。
- `_default_` 列入 `FrontendSyntheticPropertyHelperSupport.RESERVED_PREFIXES`（`_default_s_` 位于该命名空间下，自动覆盖）；skeleton 对以该前缀命名的用户函数发 `sema.class_skeleton` 诊断并 skip，杜绝与 synthetic shell 撞名。
- 元数据由 §3.1 owner 按三阶段 sweep 写入/回收 `LirParameterDef.defaultValueFunc`；skeleton 阶段恒为 `null`。shared `analyze(...)` 路径下"参数无 `defaultValueFunc`"即"无默认值"，语义一致。
- **同名函数一律 fail-closed**：Godot GDScript 的成员表按名唯一，同一 class 内任何同名函数声明（static+instance 对、同 static 位 overload）均为 parse error。gdcc 的 overload set 容忍主要面向 builtin/extension 类，source function 的默认值不对同名形态扩展命名合同：只要类内存在同名兄弟函数（无论 static 位与 arity、无论兄弟是否带默认值），带默认值的函数每个默认参数发恰好一条 `sema.unsupported_parameter_default_expression`（锚定默认表达式根），且不写入任何元数据、不进入 island 分析。该规则同时消除 `(name, static, arity)` skeleton 匹配的重载歧义崩溃路径。
- **C 标识符**：`_default_f$b` 依赖 GNU `$` 扩展（zig cc / clang / gcc 接受），与 `gdcc_low_ir.md` demo 一致。定义点（`func.ftl`）、调用点（`CallMethodInsnGen`）、wrapper userdata（`entry.c.ftl`）与 file-scope 冲突检查（`CCodegen`）必须使用同一拼写；若未来工具链不接受 `$`，必须统一改为同一 sanitize 映射，不能只改单一路径。

### 4.2 物化（preparation pass 阶段）

- `FrontendLoweringFunctionPreparationPass` 为每个带 `defaultValueFunc` 的参数：
  - 在 owning `LirClassDef` 上追加 hidden synthetic shell：`setHidden(true)`、return type = 参数 slot 类型；**static flag 与 owning function 一致**——static 方法的 shell 无参；instance 方法的 shell 非 static 且**首参为 `self`（类型为 owning class）**，形状对齐 instance property-init helper。名字必须等于对应 `LirParameterDef.defaultValueFunc`，不一致即 programmer error（fail-fast）。
  - 发布 `FunctionLoweringContext(Kind.PARAMETER_DEFAULT_INIT, ...)`：`sourceOwner` = `Parameter` 节点，`loweringRoot` = `parameter.defaultValue()`，`targetFunction` = synthetic shell（冻结形状见 `frontend_lowering_func_pre_pass_implementation.md` §7）；island 语义中 `self` 的绑定类型即该 shell 的 `self` 参数。
- 元数据存在但 AST 侧无 `defaultValue()`（不变量损坏）：fail-fast。
- shell-only 合同（func_pre_pass §8）覆盖 parameter-default：pre-pass 允许追加的 synthetic shell 种类包括 property-init/lambda/parameter-default。

### 4.3 CFG / body lowering

- `FrontendLoweringBuildCfgPass` 支持 `PARAMETER_DEFAULT_INIT`：直接复用 `FrontendCfgGraphBuilder.buildPropertyInitializer()`（表达式求值 → 合成 `StopKind.RETURN` stop，不伪造 `Block`、不发布结构化 region），与 `PROPERTY_INIT` 的 `publishPropertyInitializerGraph` 分支同构。
- `FrontendLoweringBodyInsnPass` 支持 `PARAMETER_DEFAULT_INIT`：复用 property-init 的 return-stop body lowering，将表达式 value 发射为 synthetic function 的 `ReturnInsn`；类型边界转换/Variant packing 复用既有 return 路径；instance flavor 的 `self` 引用读 synthetic 的 `self` 参数槽（`declareSelfSlotIfNeeded` 路径）。

---

## 5. 调用侧补全与 binding 隔离

### 5.1 静态路由调用点补全

`CallMethodInsnGen.validateFixedArgsAndCompleteDefaults()` / `CGenHelper` 按 `default_value_func` 元数据在调用点补全省略实参（bare source call 降低为 `CallMethodInsn` / `CallStaticMethodInsn` 后共享同一补全程式）：

- static method / static 上下文中的调用：生成对 `_default_s_<func>$<param>()` 的无参调用并压入实参列表。
- **exact instance call / instance 上下文中的 bare call**：synthetic 函数首参为 `self`，补全时把**当前接收者**（owner fat self）作为首实参传入——按 synthetic shell 的 static flag 区分是否传接收者。
- 过多实参仍报 compile error；少于 required 仍报 compile error。
- 多个省略默认参数按声明顺序逐个物化；多次调用各自重新调用 synthetic function，无缓存（§1.2）。
- synthetic function 作为 hidden 函数经既有 C 代码生成路径输出（与 `_field_init_<property>` / `_lambda_<k>` 同路径；instance flavor 的 `self` 参数走 owner fat 类型），hidden 函数不参与 binding。

C 侧实现细节的事实源为 `call_method_implementation.md`。

### 5.2 ClassDB binding 与 `method_info` 隔离

- `defaultVariables` / `method_info.default_arguments` 通道对 GDCC source function **恒为空**（`default_argument_count == 0`）；extension/utility 的 literal default 路径不受影响。**理由**：注册真实默认值会让 GDScript VM 在调用点用 bind 期 Variant 常量先行填充，§5.3 的 prologue 将永远收不到缺参，且常量语义与 §1.2 的逐调用求值不等价。
- `BindingData` 构造器对非空 `defaultVariables` fail-fast，把该隔离钉为不变量。
- source function 的 `defaultValueFunc` 经独立通道（`BindingData.defaultSlotCount` + 注册点 userdata 结构，§5.3）流入 call wrapper 生成——两条通道（method_info 注册 vs wrapper 填充）完全分离，不得复用 `defaultVariables`。
- 命名终态合同：`defaultVariables` 通道永不产生 `_N_default_` bind 名后缀；`_K_defslot` 仅由 `defaultSlotCount` 产生——三处 `renderFuncBindName` 与 `collectBindingData` 统一经 `CGenHelper.countDefaultSlots` 计数，否则 wrapper 符号与 `entry.c.ftl` 注册点对不上。

### 5.3 argc 感知 callee-prologue wrapper（dynamic/引擎侧补全）

带默认值参数的 source 方法使用专用 `call<bindName>` wrapper flavor（`entry.h.ftl`）。wrapper 按 `BindingData` ABI shape 去重共享（`bindingDataList` + `HashSet`），bind 名编码 arity/类型/static/`defaultSlotCount`，`method_userdata` 通道按 flavor 区分——默认函数名**不得**硬编码进模板，必须经 userdata 传入。

1. **shape 与元数据通道**：`BindingData.defaultSlotCount`（int，默认 0；顺序规则保证默认值必为连续后缀，slot 数即可定位填充区间）参与 record 相等性与 `_K_defslot` bind 名编码，保证同 shape 但默认值槽数不同的方法不共享 wrapper；**不**进 `method_info.default_argument_count`（§5.2 隔离不变）。
2. **userdata 由注册点独占创建并填充**：`defaultSlotCount > 0` 的方法在 `entry.c.ftl` **文件作用域**建立 per-method 独占 `static` 实例 `<Class>_<method>$default_ud`（`$` 分隔符使合法用户标识符不可能撞名，并登记进 `CCodegen` file-scope 冲突检查兜底），结构类型按 wrapper shape 以 named typedef 随 `bindingDataList` 发射进 `entry.h`（`gdcc_default_ud<bindName>`）：`impl` 为精确函数指针类型（不引入 function-pointer↔`void*` 往返）+ 按槽位类型化的默认函数指针（`defK` 返回该槽参数的 C storage 类型，异构默认值禁止单一 typedef；instance flavor 的 `defK` 首参为 owner fat self，static flavor 无参）。注册点经 bind helper **现有 `void* function` 形参**以 `&ud` 传入——helper 签名不变、不新增 default-fn 形参、不填充任何共享静态块（helper 按 shape 共享，填充会被同 shape 方法互相覆盖）。engine virtual dispatch（`get_virtual_with_data` / `call_virtual_with_data`）与 ClassDB 注册共享同一实例——裸 impl 地址永远不会被 defslot wrapper 当作结构体解引用。`defaultSlotCount == 0` 的方法维持 `method_userdata = function`（实参即 impl 指针），零回归。
3. **固定执行顺序**（仅对 `defaultSlotCount > 0` 的 call wrapper flavor）：
   - argc 守卫：`p_argument_count < required_count`（= `param_count - defaultSlotCount`）→ `TOO_FEW_ARGUMENTS`，`expected = required_count`；非 vararg 且 `p_argument_count > param_count` → `TOO_MANY_ARGUMENTS`，`expected = param_count`。
   - 类型门 + typed Array/Dictionary probe：**仅对 `i < p_argument_count`** 的实参执行，保持在任何 unpack/fill 之前（中途 return 不涉及已物化 `argN` 的清理）。
   - 逐槽物化：默认后缀槽 `i` 一律生成**非 const** 声明；`p_argument_count > i` 时走既有 unpack，否则调用默认函数——instance flavor `argN = ud->def<i - required_count>(self_fat)`，static flavor `argN = ud->def<i - required_count>()`。按槽位类型化的函数指针返回内部 C storage 类型，与 `argN` 同型直接赋值，**不做 Variant 往返**。按声明顺序填充（§1.2/§1.3）。非默认槽维持 const + 无条件 unpack。instance flavor 的 `self_fat` 求值点先于任何默认填充。
4. **生命周期**：填充产生的 object/容器/Variant-owned 值纳入 wrapper 既有逆序 cleanup epilogue，与实参解包值同等对待；object 类型默认槽经 `argN_from_default` 运行期标记区分来源，epilogue 仅释放 default 分支产生的 OWNED 引用（Variant 解包参数保持 BORROWED 不释放）。
5. **vararg 边界**：`BindingData` 无 `isVararg`，wrapper 对 `p_argument_count > param_count` 一律 TOO_MANY——引擎/dynamic 路径的 vararg rest 透传是既有缺口（§7）；prologue 只承诺固定前缀填充。
6. **ptrcall**：ABI 固定全参，不做 argc 守卫与默认填充；但 `defaultSlotCount > 0` flavor 的 ptrcall wrapper 必须与 call wrapper **同一套 userdata 解包**（`function = ud->impl` 后再调），否则会把结构体当函数地址调用（UB）。`defaultSlotCount == 0` 的 ptrcall 维持 `method_userdata` 直存 impl 不变。仓库无独立 validated_call 路径。
7. **获益路径**：gdcc 内部 dynamic 路由（`godot_Object_call`）、`Object.call`、ClassDB 对**已注册方法**的分发统一经 `GDExtensionMethodBind::call` 到达本 wrapper，缺参自动补全——frontend 与 exact 路由零改动。virtual 回调（`_process` 等走 `get_virtual_with_data`）不经过本 wrapper，不在获益范围。前提：Godot 4.5 `GDExtensionMethodBind::call` 不做前置 argc 校验，透传实参数并把 `GDExtensionCallError`（含 `expected`）回译给调用方（已核对 godotengine/godot 源码）。
8. **ABI 扩展点**：未来支持先行参数引用时，synthetic 函数在 `self`（instance）之后再追加先行参数值，改动集中在 wrapper 填充循环与 caller-side 补全两处（传入已物化的前序 `argN`）。

C 模板层细节的事实源为 `godot_binding_implementation.md` 的参数默认值绑定合同节。

---

## 6. 诊断与恢复

当前诊断集合：

- `sema.invalid_parameter_default_order`：带默认值参数后出现无默认值参数，或 variadic 参数带默认值（锚定违规参数；违规参数不获得 `defaultValueFunc`）。
- `sema.unsupported_parameter_default_expression`：默认表达式引用参数（含先行参数）/局部变量/capture，含 `await`，使用 `$`/`%` get-node，在 **static** 方法中引用 `self`/实例成员，或类内存在同名兄弟函数（锚定默认表达式根，单条同级诊断；identifier/self/get-node/await 各路径在 island 内不得另发 `sema.binding` 等前置诊断）。instance 方法引用 `self`/实例成员为合法形态，不发诊断。
- 类型不兼容：复用既有赋值兼容诊断通道，锚定默认表达式。
- `sema.class_skeleton`：用户成员使用 `_default_` 保留前缀（沿用既有保留前缀诊断通道）。
- lambda 参数默认值：维持 `sema.unsupported_parameter_default_value`。
- 有参 `_init`：维持既有拒绝诊断链（声明侧 `FrontendTypeCheckAnalyzer` 拒绝 → 调用侧 `FrontendConstructorResolutionSupport` 拒绝实参 → compile gate 拦截 → lowering 不传 `_init` 实参）。

恢复策略：诊断 + skip 默认表达式子树；参数按无默认值参与 arity 检查；同 module 其他子树继续。普通源码错误不抛异常；phase-order/side-table/metadata 不变量损坏（如 shell 名不一致、元数据悬空）才 fail-fast。

---

## 7. 已知限制与后续方向

- **constructor / `_init` 参数默认值（永久非目标，无需实现）**：GDExtension 的 class constructor 回调没有实参通道，runtime 入口只在零参时调用 `_init`，因此有参 `_init` 本身不可实现，参数默认值自然也不支持。sweep 范围排除 `_init` 的条款（§3.1）即由此而来。
- **先行参数引用**：Godot 允许 `b = a`，当前排除。`self`/实例成员已支持（instance synthetic 首参为 `self`）；后续方向是在 `self` 之后再向 synthetic 追加先行参数值（§5.3 扩展点），需同步修订 func_pre_pass §7 合同与 `ExactCallableBoundary` 载荷范围。
- **无类型参数从默认值推导类型**：需在参数 typed baseline 之后引入 post-patch 推导师（参照 `FrontendVarTypePostPatch` 机制），属独立特性。
- **override 默认值**：只按静态解析到的 `FunctionDef` 补全（§2.4）；Godot 级继承语义另开。
- **引擎/dynamic 路径的 vararg rest 透传**：`BindingData` 无 `isVararg`，call wrapper 对超参一律 TOO_MANY——`func f(a = 1, ...rest)` 经引擎/`Object.call` 传入 rest 实参是既有缺口，prologue 只承诺固定前缀填充；如需支持，另行立项（`BindingData.isVararg` + 放宽 TOO_MANY + rest → Array）。
- **lambda 参数默认值**：随 lambda 参数默认值元数据接通单独开放。
- **默认表达式内 `await`**：维持 fail-closed；与 coroutine 合同联动评估。
- **GDScript 源码直调缺参被静态拒绝**：`method_info.default_argument_count == 0` 使 GDScript analyzer 对 GDCC 方法的省略显式调用报编译期 too-few（§2.4），经 `Object.call` / Variant 接收者的动态调用不受此限。

---

## 8. 长期不变量与维护要求

- `FrontendClassSkeletonBuilder.fillFunctionParameters()` 创建 `LirParameterDef` 时 `defaultValueFunc` 恒为 `null`；只有 `FrontendParameterDefaultMetadataOwner` 能写入或回收该元数据，不得出现第二 owner 或平行 side table。
- lambda 参数创建（preparation pass）恒写 `null` default metadata；lambda 参数默认值维持 fail-closed。
- compile gate 只消费 published facts，不回读 LIR 元数据，不重扫被拒绝的默认表达式根。
- `method_info.default_argument_count` 恒为 0；`defaultVariables` 非空即 `BindingData` 构造器 fail-fast。
- `defaultSlotCount == 0` 的 wrapper 必须继续把 `method_userdata` 当 impl 指针直存直用，不得误解释为 userdata 结构。
- 默认函数指针必须按槽位返回类型分别类型化；instance flavor 必须携带 owner fat self 首参。
- `_default_<func>$<param>` 与 `<Class>_<method>$default_ud` 的 `$` 拼写在定义点、调用点、注册点与冲突检查中必须一致；工具链变更时统一改 sanitize 映射。
- 默认表达式 domain 的新形态必须先显式扩展 §3.2 的 domain 规则再开放。
- 元数据与 AST 不一致、shell 名不一致、shell 形状损坏等内部不变量问题必须 fail-fast；普通源码语义错误采用诊断、跳过子树并继续分析。

---

## 9. 参考实现位置

- AST：`dev.superice.gdparser.frontend.ast.Parameter#defaultValue()`（gdparser）
- 默认值分析 owner：`src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendParameterDefaultMetadataOwner.java`
- 参数登记：`FrontendVariableAnalyzer`（`bindParameter`）
- skeleton 参数元数据：`FrontendClassSkeletonBuilder`（`fillFunctionParameters`）
- 保留前缀：`FrontendSyntheticPropertyHelperSupport`（`RESERVED_PREFIXES`）
- visible-value 边界：`FrontendVisibleValueResolver`（`classifyBoundaryEdge` / `classifyRequestDomainBoundary` / `filterInvisibleCurrentLayerHit`）、`FrontendBodySemanticSupportPolicy`、`FrontendSuiteContext`
- arity/默认值判定：`ScopeMethodResolver`（`matchesArguments` / `canOmitTrailingParameters`）、`ScopeMethodParameter`、`FrontendExpressionSemanticSupport`、`FrontendConstructorResolutionSupport`
- 类型检查 hook：`FrontendTypeCheckAnalyzer`
- compile gate：`FrontendCompileCheckAnalyzer`（`walkCallableBody`）
- lowering context/物化：`FunctionLoweringContext`、`FrontendLoweringFunctionPreparationPass`、`FrontendLoweringBuildCfgPass`、`FrontendLoweringBodyInsnPass`、`FrontendCfgGraphBuilder`（`buildPropertyInitializer`）
- backend 补全：`CallMethodInsnGen`（`validateFixedArgsAndCompleteDefaults` / `materializeFunctionDefault`）、`CGenHelper`（`collectBindingData` / `countDefaultSlots` / `renderFuncBindName` / `renderDefaultUserdataTypeName` / `renderDefaultUserdataInstanceName`）、`BindingData`
- binding 模板：`src/main/c/codegen/template_451/entry.h.ftl`（call/ptrcall wrapper、userdata typedef）、`entry.c.ftl`（file-scope userdata 实例与注册点）、`func.ftl`
- 测试锚点：`FrontendParameterDefaultMetadataOwnerTest`、`FrontendCompileCheckAnalyzerTest`、`FrontendLoweringFunctionPreparationPassTest`、`FrontendLoweringBuildCfgPassTest`、`FrontendLoweringBodyInsnPassTest`、`CallMethodInsnGenTest`、`CallStaticMethodInsnGenTest`、`CGenHelperTest`、`CCodegenTest`、`FrontendLoweringToCProjectBuilderIntegrationTest`、test_suite `default_args/` 资源对
- Godot 参照：`godotengine/godot` `core/extension/gdextension.cpp`（`GDExtensionMethodBind::call` 原样转发 argc、不做默认值填充）、`modules/gdscript/gdscript_parser.cpp` / `gdscript_analyzer.cpp` / `gdscript_compiler.cpp` / `gdscript_vm.cpp`
