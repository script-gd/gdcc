# Frontend 函数参数默认值实施计划

> 本文档是“函数参数默认值（`func f(a, b = 5):`，调用点可省略尾部实参）”的阶段性实施计划。
> 本文档不是长期事实源；功能完整落地并稳定后，应整理为事实源文档并移除分步骤清单。

## 文档状态

- 状态：计划维护中（已经过 review-expert-a 多轮审阅修订，全部发现闭合；constructor 默认值确认为 GDExtension 内在限制、永久非目标）
- 更新时间：2026-09-02
- 适用范围：GDScript source function（instance / static）声明的参数默认值；覆盖 frontend sema、lowering 与 backend 接线。
- 关联文档：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_resolution_pipeline_implementation.md`
  - `doc/module_impl/frontend/frontend_visible_value_resolver_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_property_init_lowering_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_plan.md`
  - `doc/module_impl/frontend/frontend_lowering_func_pre_pass_implementation.md`（§7 Parameter Default 冻结合同、§8 shell-only 合同）
  - `doc/module_impl/frontend/frontend_exact_call_extension_metadata_contract.md`
  - `doc/module_impl/frontend/frontend_dynamic_call_lowering_implementation.md`
  - `doc/module_impl/frontend/scope_analyzer_implementation.md`
  - `doc/gdcc_low_ir.md`（`default_value_func` 参数元数据）
  - `doc/gdcc_type_system.md`
- 明确非目标：
  - **constructor / `_init` 参数默认值（永久非目标）**：有参 `_init` 本身是 GDExtension 的内在限制——GDExtension class constructor 回调没有实参通道，runtime 入口只调用零参 `_init`。不支持参数即自然不支持参数默认值，无需实现（见 §10）。
  - 默认表达式引用**先行参数**（`func f(a, b = a)`；Godot 允许；需要 synthetic default function ABI 再携带先行参数值，见 §10）。`self`/实例成员引用在 instance 方法中**受支持**（见 §2.5）。
  - **ClassDB `method_info.default_arguments` 注册**：默认值不注册为 bind 期 Variant 常量（`default_argument_count` 恒 0）；引擎侧/dynamic 路由省略实参改由 §5.6 的 argc 感知 callee-prologue wrapper 承载。
  - signal 声明参数默认值、`@rpc` 参数、`Callable.bind`/callable-value invocation 的参数省略。
  - 无类型参数（`func f(x = 42)`）从默认值**推导参数类型**（Godot 会推导为 `int`；MVP 保持 `Variant`，见 §10）。
  - 默认表达式内的 `await`（维持既有 fail-closed 边界）。
  - lambda 参数默认值（维持 fail-closed）。
  - override 默认值继承/合并语义（见 §3.4 与 §10）。

---

## 1. 现状盘点与既有合同

参数默认值不是从零开始；仓库已存在一条“半成品链路”，本计划的工作是把各环节接通并按 Godot 语义补齐语义层。

### 1.1 已就绪的部分

- **AST**：gdparser 0.5.3 的 `Parameter` record 已暴露 `@Nullable Expression defaultValue()`，`Parameter.getChildren()` 包含默认表达式；`CstToAstMapper` 已映射 `default_parameter` / `typed_default_parameter`。
- **Scope 图**：`FrontendScopeAnalyzer` 已遍历参数默认表达式，并把参数与其默认表达式记录进同一个 `CallableScope`（`scope_analyzer_implementation.md` §3.3 / §4.3）。
- **共享方法解析视图**：`ScopeMethodParameter` 已具备 `ScopeDefaultArgKind.NONE/LITERAL/FUNCTION`、`defaultFunctionName` 与 `hasDefaultValue()`；`ScopeMethodResolver.matchesArguments()` / `canOmitTrailingParameters()` 已实现“尾部参数有默认值即可省略”的 arity 规则（含 too-few/too-many 精确消息），其默认值事实来自 `ParameterDef.getDefaultValueFunc()`。
- **调用点 arity 检查**：bare call（`FrontendExpressionSemanticSupport`）与 constructor call（`FrontendConstructorResolutionSupport`，仅对 builtin/extension constructor 生效）已用 `ParameterDef.getDefaultValueFunc() != null` 判定尾部可省略。
- **LIR**：`LirParameterDef.defaultValueFunc` 与序列化属性 `default_value_func` 已存在；`LirFunctionDef` 提供 `addParameter(index, ...)` / `removeParameter(index)`，允许在 sema 阶段原地改写参数元数据。`gdcc_low_ir.md` 规定“缺实参时调用 default function，否则报错”。
- **Backend**：`CallMethodInsnGen.validateFixedArgsAndCompleteDefaults()` / `CGenHelper` 已实现基于 `default_value_func` / literal metadata 的调用点补全与过多/过少实参报错（bare source call 降低为 `CallMethodInsn` / `CallStaticMethodInsn`，同样走该路径；`CallGlobalInsnGen` 只服务 utility literal default，与本特性无关）。
- **Lowering context 模型与 CFG 范式**：`FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT` 已保留；`frontend_lowering_func_pre_pass_implementation.md` §7 冻结了“每个默认表达式降低为 hidden synthetic function，经 `LirParameterDef.defaultValueFunc` 引用，不允许永久 call-site inline-only”的合同。`FrontendCfgGraphBuilder.buildPropertyInitializer()` 已提供“表达式求值 → 合成 `RETURN` stop”的 expression-rooted CFG 范式，可直接复用。

### 1.2 当前 fail-closed 边界（本计划要解除/改写的部分）

- `FrontendVariableAnalyzer.bindParameter()` 对每个带默认值的参数报告 `sema.unsupported_parameter_default_value`，且**不分析**默认表达式（参数本身仍正常登记）。
- `FrontendSuiteResolver` 对默认表达式子树报告 `sema.unsupported_binding_subtree` / `sema.unsupported_chain_route`。
- `FrontendVisibleValueResolver` 双重封口：`classifyBoundaryEdge()` 把 `Parameter.defaultValue` AST 边识别为 structural deferred boundary；`classifyRequestDomainBoundary()` 对一切非 `EXECUTABLE_BODY` 的 request domain 返回 `DEFERRED_UNSUPPORTED`。`FrontendBodySemanticSupportPolicy.PARAMETER_DEFAULT(false, false, ...)`。
- `FrontendClassSkeletonBuilder.fillFunctionParameters()` 创建 `LirParameterDef` 时 `defaultValueFunc` 恒为 `null` → 所有带默认值参数在 arity 检查中被当作 required。
- `FrontendLoweringFunctionPreparationPass` 不发布 `PARAMETER_DEFAULT_INIT` context；`FrontendLoweringBuildCfgPass` / `FrontendLoweringBodyInsnPass` 遇到该 kind 直接 `IllegalStateException`。
- `FrontendCompileCheckAnalyzer` 的 compile surface 只 walk callable body（`walkCallableBody`），不触及参数默认表达式（`frontend_rules.md` 明确要求 compile gate 不得重新深入 parameter default）。
- lambda 参数创建（preparation pass）同样恒写 `null` default metadata；lambda 参数默认值维持 fail-closed（见 §3.4）。
- `_default_` 前缀未列入 compiler-owned 保留前缀（`FrontendSyntheticPropertyHelperSupport.RESERVED_PREFIXES` 当前只有 `_field_init_` / `_lambda_` 等），存在与用户成员撞名风险。

---

## 2. 语义规则（对齐 Godot 4.x）

以 godotengine/godot `master` 的 `modules/gdscript` 为准，gdcc 必须复现以下用户可观察语义：

1. **顺序规则**：必填参数前缀 + 带默认值参数的连续后缀 + 可选 variadic 尾部。带默认值参数之后不允许再出现无默认值参数；variadic 参数必须最后且不能有默认值。违反即 error diagnostic（对齐 Godot parser 错误 “Cannot have mandatory parameters after optional parameters.” / “The rest parameter cannot have a default value.”）。
2. **默认值是普通表达式，不要求常量**：字面量、常量/枚举引用、容器字面量、builtin 构造器、函数调用等均合法；每次调用且对应参数被省略时**重新求值**（数组/字典等可变默认值不得共享静态实例）。
3. **求值时机**：callee 语义上是“调用发生且缺参时按声明顺序求值”。gdcc 采用 frozen 合同：默认表达式降低为 hidden synthetic function；静态路由由 backend 在调用点对省略参数逐一调用对应 synthetic function（caller-side 补全），dynamic/引擎侧路由由 §5.6 的 argc 感知 callee-prologue wrapper 补全——两条路径都与 Godot callee prologue 在用户可观察语义上等价（每次调用重新求值、声明顺序求值），且满足 LIR 既有 `default_value_func` 合同。
4. **类型规则**：
   - 显式类型参数：默认值表达式类型必须与声明类型赋值兼容（复用既有赋值兼容/隐式转换规则）；Object 类型允许 `null` 默认值。
   - 无显式类型参数（`x = 42`）：MVP 不推导，参数类型保持 `Variant`（与 Godot 的推导行为存在已知偏差，见 §10）；`x = null` 同样为 `Variant`（Godot 也退化为 Variant）。
5. **可见性（MVP 受限）**：
   - **instance 方法**：默认表达式可引用字面量、常量/枚举/类型/singleton、builtin 构造器、utility/global function 调用，以及 **`self` 与实例成员**（实例属性/方法调用）；此时 synthetic default 函数首参携带 `self`（§5.2），与 Godot 语义一致。
   - **static 方法**：禁止引用 `self`/实例成员（对齐 Godot static 限制），只允许调用帧无关的名字。
   - 两者均**不允许**引用参数（含先行参数）、局部变量、capture；source `const`（类常量/局部常量）仍属 deferred 边界，不在 MVP。违反时 fail-closed 诊断（见 §6）。
6. **arity 规则**：省略只能发生在带默认值后缀；实参数 `< required 数` → too-few error；非 vararg 时实参数 `>` 参数总数 → too-many error。该规则已在 resolver 层实现，本计划只需让 source function 的默认值元数据流入既有检查。
7. **默认值中调用的函数**自身仍需满足全部调用规则；void 调用的结果不得作为默认值。

---

## 3. 目标支持面与路由边界

### 3.1 声明侧支持面

- `FunctionDeclaration`（instance / static）参数允许默认值。
- 参数为 variadic 时不允许默认值（诊断）。
- constructor（`_init`）参数默认值：不支持（GDExtension 内在限制，永久非目标），维持有参 `_init` 的既有拒绝路径。
- lambda 参数默认值：维持 fail-closed。

### 3.2 默认表达式 MVP 形态

在 §2.5 的可见性限制内，默认表达式复用既有表达式语义分析全能力（字面量、容器字面量、构造器、链式成员/调用、三元、运算符等）。超出可见性限制的子树按 §6 诊断并跳过，不影响同 module 其他合法子树。

### 3.3 调用侧支持面（允许省略实参的路由）

- bare call 解析到本 class 可见的 source function（ lowering 为 `CallMethodInsn` / `CallStaticMethodInsn`）。
- exact instance method call（`FrontendResolvedCall` + `ExactCallableBoundary` 路由）。
- static method call。
- **dynamic / Variant 接收者调用与引擎侧入口**（`godot_Object_call` → `Object::callp` → `GDExtensionMethodBind::call`）：frontend/backend 编译期无法获得 callee 元数据，实参列表原样透传，缺参由 §5.6 的 argc 感知 callee-prologue wrapper 在运行时补全——该路径天然对齐逐调用求值语义。
- utility function 的 literal default 已工作，不属于本计划。
- 静态路由的 arity 放行逻辑已存在，本计划只负责让 source function 参数携带 `defaultValueFunc` 元数据。

### 3.4 明确不支持（保持既有行为或 fail-closed）

- 默认表达式引用先行参数：发明确 error diagnostic（instance/static 均禁止）。
- static 方法的默认表达式引用 `self` / 实例成员：发明确 error diagnostic（instance 方法允许，见 §2.5）。
- 默认表达式内 `await`：维持 fail-closed。
- lambda 参数默认值、constructor 参数默认值：维持 fail-closed。
- signal 声明参数默认值、`@rpc` 参数、`Callable.bind` / callable-value invocation 的参数省略。
- **GDScript 侧静态分析分歧**：`method_info.default_argument_count` 恒 0（§5.5），因此 GDScript 调用者对 GDCC 方法省略实参会被 GDScript analyzer 报静态 too-few——这是 GDExtension ABI 的固有分歧（注册真实默认值会让 VM 用 bind 期常量先行填充、prologue 失效），文档化不解决。
- **override 默认值语义**：MVP 只做“按静态解析到的 `FunctionDef` 的 `defaultValueFunc` 补全”；不继承/合并父类默认值，子类 override 可声明不同默认值但只对静态类型为子类的调用生效；engine virtual override 的签名匹配不因默认值差异额外报错（签名仍不含 defaults）。dynamic/prologue 路径按实际注册类的 wrapper 执行。

---

## 4. Pipeline 集成与 owner 分工

遵循 `frontend_resolution_pipeline_implementation.md` 的阶段顺序，不新增依赖 typed readiness 的 body-entry gate。

| 阶段 | 类 | 本计划新增/修改的职责 |
| --- | --- | --- |
| parse | `GdScriptParserService` + gdparser | 无改动（AST 已暴露 `Parameter.defaultValue()`） |
| scope graph | `FrontendScopeAnalyzer` | 无改动（默认表达式已挂入 `CallableScope`） |
| lexical inventory | `FrontendVariableAnalyzer` | 移除 source function 的 `sema.unsupported_parameter_default_value`（lambda 维持）；照常登记参数 binding |
| class skeleton | `FrontendClassSkeletonBuilder` | 把 `_default_` 加入保留前缀并对用户成员发诊断；`fillFunctionParameters()` **维持 `defaultValueFunc = null`**（元数据由 §4.1 的默认值分析 owner 统一写入） |
| interface phase | `FrontendInterfacePhase` | 无改动（参数 baseline 不变） |
| **默认值分析（新 owner，body 阶段开始前统一执行）** | `FrontendParameterDefaultMetadataOwner`（新增，由 `FrontendSuiteResolver` 驱动）+ chain/expr 分析 owner | 按 §4.1 三阶段 sweep：结构校验 → 占位写入 `defaultValueFunc` → island 分析 → 失败回收；之后才允许任何 callable body 解析 |
| body 语义 | `FrontendSuiteResolver` + chain/expr 分析 owner | 不再对默认表达式根发 unsupported_binding/chain 诊断；body 中的调用 arity 检查自然读取已就位的 `defaultValueFunc` |
| visible value | `FrontendVisibleValueResolver` + `FrontendBodySemanticSupportPolicy` | `PARAMETER_DEFAULT` domain 从双重封口转为受限支持 island（§4.2） |
| 类型检查 | `FrontendTypeCheckAnalyzer` | 校验默认表达式类型与参数声明类型赋值兼容（§2.4） |
| compile gate | `FrontendCompileCheckAnalyzer` | compile surface 纳入**已接受**默认表达式根（§4.3） |
| lowering prep | `FrontendLoweringFunctionPreparationPass` | 为每个带 `defaultValueFunc` 的参数物化 hidden synthetic shell 并发布 `PARAMETER_DEFAULT_INIT` context（§5.2） |
| CFG | `FrontendLoweringBuildCfgPass` | 支持 `PARAMETER_DEFAULT_INIT`：复用 `buildPropertyInitializer()` 范式构建“求值默认表达式并 return”的 CFG（§5.3） |
| body lowering | `FrontendLoweringBodyInsnPass` | 支持 `PARAMETER_DEFAULT_INIT`：复用 property-init 的 return-stop body lowering（§5.3） |
| backend | `CGenHelper` / `CallMethodInsnGen` / binding 生成 | 验证既有 default completion 对 source function 生效；`defaultVariables`/`method_info` 通道恒空（§5.5）；`defaultValueFunc` 经 `defaultSlotCount` + 注册点 userdata 通道进入 prologue wrapper（§5.6） |

### 4.1 默认值分析 owner（单 owner、三阶段 sweep 合同）

默认表达式语义与元数据写入由**同一个新 owner**（命名为 `FrontendParameterDefaultMetadataOwner`，由 `FrontendSuiteResolver` 在 body 阶段驱动、但不经由 `resolveSuite()`）负责；它是唯一允许改写 `LirParameterDef.defaultValueFunc` 的组件（pipeline 文档同步登记该 owner）。skeleton 阶段恒写 `null`，消除“提前发布、失败后无法撤回”的悬空元数据窗口。

**触发时机与范围**：body 阶段、任何 callable body 解析开始**之前**统一执行三阶段 sweep。sweep **只**处理非 `_init` 的 `FunctionDeclaration`——`ConstructorDeclaration`、名为 `_init` 的函数、`LambdaExpression` 一律跳过（lambda 参数默认值维持现有 unsupported 诊断；有参 `_init` 维持既有拒绝路径；lambda 在 suite 阶段尚无 `LirFunctionDef`，对其改写参数元数据会 fail-fast）：

1. **结构校验与占位**：先做 §2.1 顺序规则校验（默认值后缀连续性、variadic 无默认值），违规参数发 `sema.invalid_parameter_default_order` 且永不进入后续阶段。对结构合法且 `defaultValue() != null` 的参数，立即通过 `LirFunctionDef.removeParameter(index)` + `addParameter(index, new LirParameterDef(name, type, "_default_<func>$<param>", functionDef))` 成对原地改写，写入确定性 synthetic 名（此时默认表达式尚未分析）。
2. **island 语义分析**：逐个默认表达式根按 §4.2 的 island 设计做 chain/expr 分析，发布 `symbolBindings()` / `expressionTypes()` / `resolvedCalls()` facts 到既有 AST-identity side tables——与 body 表达式同表同构，**不得**新建平行 side table 或第二语义 owner。占位元数据已就位，因此默认表达式中对其他 source function 的**交叉缺省调用**（`func g(x = f(1))`，`f` 带默认值）能读到 `f` 的 `defaultValueFunc` 并正确放行。
3. **失败回收**：分析失败（可见性违规、表达式 facts 不完整）的参数发 `sema.unsupported_parameter_default_expression`（单条同级诊断，锚定默认表达式根），并以同样的 `removeParameter`/`addParameter` 对把 `defaultValueFunc` 清回 `null`。

**闭包与已知角落**：

- body 阶段的 bare/exact call arity 检查（`getDefaultValueFunc() != null`）只看到已定案的元数据——失败参数自然按 required 处理，`f(x)` 缺参产生 too-few 诊断。shared `analyze(...)` 路径同样满足该闭包，不依赖 compile gate 兜底。
- 交叉引用角落：若 `f` 的默认值在阶段 3 被回收，而 `g` 的默认表达式已在阶段 2 带着省略实参解析了对 `f` 的调用，该 call fact 不做追溯失效——模块已因 `f` 的诊断 fail-closed，不会进入 compile/lowering。此角落写入测试锚点。
- “被接受”的判定 = 可见性合规 + 表达式 facts 完整，**不含** 类型检查（type-check 在 suite 之后运行，见 §4.4）；类型不兼容的默认值保留 `defaultValueFunc`，由类型诊断 fail-closed 该模块。

### 4.2 `PARAMETER_DEFAULT` visible-value island

只改 `FrontendBodySemanticSupportPolicy` 的两个 boolean 不足以接通 resolver；必须按 island 设计改造（对标 property-init 的独立 expression-root 上下文，不复用 callable body context）：

1. **独立入口**：新增 `resolveParameterDefaultIsland(...)` 式独立分析入口；`PARAMETER_DEFAULT` 的 `entersSuiteResolver` 保持 `false`，**不**走 `resolveSuite()` / `FrontendBodyStructuralCompleteness`。
2. **专用 suite context**：`FrontendSuiteContext` 增加显式 `visibleValueDomain`（或 island kind）字段——默认表达式 island 置为 `PARAMETER_DEFAULT`，**不得**依赖 `currentBlockRoot == null` 的 fallback（该 fallback 会错误得到 `EXECUTABLE_BODY`）；resolve restriction **继承 enclosing callable 的 instance/static 上下文**（instance 方法的默认表达式可命中 `self`/实例成员；static 方法的默认表达式按 static restriction 禁掉实例成员），对齐 §2.5。**island 的 `propertyInitializerContext` 必须恒为 `null`**——property-init 检查会把同类非 static 方法/`self` 误杀（`FrontendPropertyInitializerSupport`、`FrontendChainReductionHelper` 的 instance-method 拦截只认 property-init context），从 property-init 路径抄 context 会直接违反 §2.5 正路径。
3. **owner 身份**：island 的 `callableOwner` 保持为 **enclosing `FunctionDeclaration`**（await/get-node 等 owner hook 依赖该形状，塞入 `Parameter` 会触发 `IllegalStateException`）；只有 lowering 的 `FunctionLoweringContext.sourceOwner` 才是 `Parameter` 节点。
4. **resolver 双重封口开放**：
   - `classifyBoundaryEdge()`：`Parameter.defaultValue` 边不再无条件 structural-deferred，按 island 上下文放行进入 lookup。
   - `classifyRequestDomainBoundary()`：允许 `PARAMETER_DEFAULT` domain 进入 lookup，不再是 `UNSUPPORTED_DOMAIN`。
5. **域内过滤器（参数/局部停在本层）**：`filterInvisibleCurrentLayerHit` 当前对 `Parameter` 无条件放行，且被过滤的命中会记入 `filteredHits` 后**继续向外层 scope 查找**——`static var a = 1` + `func f(a, b = a)` 会把 `a` 绑到外层 static 属性，与 Godot 语义相反。因此 `PARAMETER_DEFAULT` 域命中 PARAMETER / CAPTURE / LOCAL 时必须**立即停在本层**（`FOUND_BLOCKED` 等价语义），禁止继续外查；实现需要把 `request.domain` 传入过滤器。**实例成员命中不受此限**：instance 方法的默认表达式经 ClassScope 正常命中实例属性/方法（§2.5）。诊断由 owner 升为恰好一条 `sema.unsupported_parameter_default_expression`，不得退化为 `NOT_FOUND` 或绑到外层同名成员。配套地，`bindIdentifier` 在该 island 收到 `FOUND_BLOCKED` 时**不得**先发 `sema.binding`（现有行为会直接 `reportBindingError`）——由 owner 统一发锚定默认根的那一条诊断；`bindSelf` 仅在 **static** 方法的 island 中发该诊断，instance 方法的 island 中 `self` 正常绑定。
6. **await 边界**：`awaitFailClosedBoundaryReason()` 当前只识别 property-init island；必须扩展为同时识别 parameter-default island 并返回默认值专用原因，由 owner 升为 `sema.unsupported_parameter_default_expression`，确保默认表达式内的 `await` 维持 fail-closed 且不误入 coroutine 分类。
7. **get-node 与 self 链式路径**：`resolveGetNodeExpressionType()` 与 `resolveSelfReceiver()` 当前只特判 property-init island；两者必须增加 parameter-default 分支——`$`/`%` get-node 维持 fail-closed（并入 `sema.unsupported_parameter_default_expression`，不借用“static function”文案）；`self` 链式在 **instance** 方法的 island 中正常走 instance receiver 解析（不再 boundary 拦截），在 **static** 方法的 island 中由 `resolveSelfReceiver`/`bindSelf` 统一汇入单条 `sema.unsupported_parameter_default_expression`，避免再发第二条 `sema.binding`。
8. **lambda 嵌套**：island context 的 `nestedLambdaResolver` 恒为 `null`（对标 property-init，不从 enclosing callable 抄 trigger）；默认表达式内的 lambda 值维持现有 `tryResolveRecordedLambda` fail-closed 路径，不新增合同。
9. **expected type 接线**：island 分析入口必须把参数 slot 类型（interface baseline / `CallableScope` PARAMETER binding 的类型）作为 `expectedType` 传入表达式类型发布（裸 `Expression` 根当前 `expectedType=null`，会导致 `func f(a: Array[int] = [1, 2])` 的容器字面量失去目标类型）；`Variant`/无类型参数按既有 `contextualExpectedOrNull` 规则丢弃。
10. 该 domain 的开放范围只覆盖 §3.2 形态；任何新形态必须先扩展 domain 规则再开放，不得静默放行。
11. 同步修订 `frontend_visible_value_resolver_implementation.md`（domain 章节）与 `frontend_resolution_pipeline_implementation.md` §4.8 / R8（parameter default 不再是 structural deferred boundary；登记新 owner）。

### 4.3 compile surface

- `FrontendCompileCheckAnalyzer` 在 `walkCallableBody(...)` 中对每个**已接受**的参数默认表达式根显式 `walkExpression()`，重扫其 published facts（exprTypes 及存在的 bindings / resolvedCalls 均为 lowering-ready）。
- **“已接受”谓词**：compile gate 只消费已发布 facts、不回读 LIR 元数据——对 `parameter.defaultValue() != null` 且该根已存在 published `expressionTypes` 的参数执行 walk（`resolvedCalls` 可选——纯字面量默认没有 call facts，不得把它当必需条件）；被 §4.1 拒绝的根（无 published facts，已有上游诊断）不进入 compile surface，不重复包装。compile visitor 当前拿不到 class skeleton，**不得**按 name/static/arity 反查 `LirParameterDef`。
- 同步修订 `frontend_rules.md` 中“compile gate 不得重新深入 parameter default”的条款为“compile gate 必须重扫已接受的 parameter default 根”，以及 `frontend_compile_check_analyzer_implementation.md` 对应章节。

### 4.4 类型检查

- `FrontendTypeCheckAnalyzer` 增加与 `visitPropertyInitializer` 同构的 hook：在 callable context 已设置时遍历该 callable 已接受的默认表达式根，校验默认表达式类型与参数声明类型赋值兼容（§2.4），不兼容时经既有赋值兼容通道发诊断、锚定默认表达式。
- 类型检查在 suite sweep **之后**运行；类型不兼容**不**回收 `defaultValueFunc`（默认值仍存在，只是类型坏了），arity 检查不受影响；模块由类型诊断 fail-closed。

---

## 5. Synthetic default function 合同

### 5.1 命名与元数据接线

- 命名：`_default_<func>$<param>`（对齐 `gdcc_low_ir.md` demo 的 `_default_get_pitch$to_radius`）。名字按 (owning class, function name, parameter name) 确定性生成，class 内唯一。
- `_default_` 加入 `FrontendSyntheticPropertyHelperSupport.RESERVED_PREFIXES`；skeleton 对以该前缀命名的用户函数发 `sema.class_skeleton` 诊断并 skip，杜绝与 synthetic shell 撞名。
- 元数据由 §4.1 owner（`FrontendParameterDefaultMetadataOwner`）按三阶段 sweep 写入/回收 `LirParameterDef.defaultValueFunc`；skeleton 阶段恒为 `null`。shared `analyze(...)` 路径下“参数无 `defaultValueFunc`”即“无默认值”，语义一致。
- **同名 static+instance 撞名**：若类内允许同名 static 与 instance 函数共存（`ClassScope` 按名维护 overload set），两个同名函数的 synthetic 名会相同；写入方必须在 `owningClass.hasFunction(name)` / 同名参数元数据冲突时，为 static 函数改用 `_default_s_<func>$<param>`（或等价 static 位编码），保证 synthetic 名唯一。若 skeleton 已禁止同类同名函数，则该分支不可达，保留 fail-fast 即可。
- **C 标识符**：`_default_f$b` 依赖 GNU `$` 扩展（zig cc / clang / gcc 接受），与 `gdcc_low_ir.md` demo 一致。步骤 7 必须用 zig 端到端验证 `Class__default_f$b` 形式的符号可编译链接，并验证定义点（`func.ftl`）、调用点（`CallMethodInsnGen`）与 file-scope 冲突检查（`CCodegen`）使用同一拼写；若验证失败，统一改为同一 sanitize 映射（定义/调用/冲突检查同路径），并补撞名测试。

### 5.2 物化（preparation pass 阶段）

- `FrontendLoweringFunctionPreparationPass` 为每个带 `defaultValueFunc` 的参数：
  - 在 owning `LirClassDef` 上追加 hidden synthetic shell：`setHidden(true)`、return type = 参数类型；**static flag 与 owning function 一致**——static 方法的 shell 无参；instance 方法的 shell 非 static 且**首参为 `self`（类型为 owning class）**，形状对齐 instance property-init helper（func_pre_pass 合同：instance property init 恰好声明一个 `self` 参数）。名字必须等于对应 `LirParameterDef.defaultValueFunc`，不一致即 programmer error（fail-fast）。
  - 发布 `FunctionLoweringContext(Kind.PARAMETER_DEFAULT_INIT, ...)`：`sourceOwner` = `Parameter` 节点，`loweringRoot` = `parameter.defaultValue()`，`targetFunction` = synthetic shell（满足 func_pre_pass §7 冻结合同）；island 语义中 `self` 的绑定类型即该 shell 的 `self` 参数。
- 元数据存在但 AST 侧无 `defaultValue()`（不变量损坏）：fail-fast。
- shell-only 合同（func_pre_pass §8）同步扩展：pre-pass 允许追加的 synthetic shell 种类从 property-init/lambda 扩展到 parameter-default。

### 5.3 CFG / body lowering

- `FrontendLoweringBuildCfgPass` 支持 `PARAMETER_DEFAULT_INIT`：**直接复用** `FrontendCfgGraphBuilder.buildPropertyInitializer()`（表达式求值 → 合成 `StopKind.RETURN` stop，不伪造 `Block`、不发布结构化 region），与 `PROPERTY_INIT` 的 `publishPropertyInitializerGraph` 分支同构。
- `FrontendLoweringBodyInsnPass` 支持 `PARAMETER_DEFAULT_INIT`：复用 property-init 的 return-stop body lowering，将表达式 value 发射为 synthetic function 的 `ReturnInsn`；类型边界转换/Variant packing 复用既有 return 路径。
- 两个 pass 现有的 `PARAMETER_DEFAULT_INIT -> IllegalStateException` 分支移除，替换为真实处理；lambda shell 路径不受影响。

### 5.4 Backend 调用点补全

- 既有 `CallMethodInsnGen.validateFixedArgsAndCompleteDefaults()` / `CGenHelper` 已按 `default_value_func` 在调用点补全省略实参；本计划验证/补齐其对 **source GDScript function**（而非仅 extension/utility）生效：
  - static method / static 上下文中的调用：生成对 `_default_<func>$<param>()` 的无参调用并压入实参列表；
  - **exact instance call / instance 上下文中的 bare call**：synthetic 函数首参为 `self`，补全时把**当前接收者**（owner fat self）作为首实参传入——`CallMethodInsnGen.materializeFunctionDefault` 路径需要按 synthetic shell 的 static flag 区分是否传接收者；
  - 过多实参仍报 compile error；少于 required 仍报 compile error；
  - 多次调用各自重新调用 synthetic function（语义 §2.2）。
- synthetic function 作为 hidden 函数经既有 C 代码生成路径输出（与 `_field_init_<property>` / `_lambda_<k>` 同路径；instance flavor 的 `self` 参数走 owner fat 类型），hidden 函数不参与 binding。

### 5.5 ClassDB binding 与 `method_info` 隔离

- `CGenHelper` 有**三条**从 `defaultValueFunc` 收集 `defaultVariables` 的路径：`collectBindingData()`（307-329）、`renderFuncBindName(ClassDef, FunctionDef)`（951-971）、`renderFuncBindName(FunctionDef)` static 重载（1036-1055）。`entry.h.ftl` 据此为 bind helper 生成 `default_N_value` 形参并设置 `method_info.default_argument_count`，而 `entry.c.ftl` 的注册调用点不传这些实参——一旦 source function 携带 `defaultValueFunc`，生成的 C 直接无法编译（或 helper 名与调用点错位）。
- 合同：三条路径的 `defaultVariables` / `method_info.default_arguments` 通道对 GDCC source function 恒为空（`default_argument_count == 0`）；extension/utility 的 literal default 路径不受影响。**理由**：注册真实默认值会让 GDScript VM 在调用点用 bind 期 Variant 常量先行填充，§5.6 的 prologue 将永远收不到缺参，且常量语义与 §2.2 的逐调用求值不等价。
- source function 的 `defaultValueFunc` 不再被 binding 层丢弃，而是经 §5.6 的独立通道（`BindingData.defaultSlotCount` + 注册点 userdata 结构）流入 call wrapper 生成——两条通道（method_info 注册 vs wrapper 填充）必须明确分离，不得复用 `defaultVariables`。
- 回归：`defaultVariables` 通道永不产生 `_N_default_` bind 名后缀；`defaultSlotCount > 0` 使用方法独立的 `_K_defslot` 后缀（步骤 7 为中间态、尚无 defslot；步骤 8 起生效）；注册调用点可链接、`default_argument_count == 0`。

### 5.6 argc 感知 callee-prologue wrapper（dynamic/引擎侧补全）

为带默认值参数的 source 方法改造既有 `call<bindName>` wrapper（`entry.h.ftl`）。**关键约束**：wrapper 按 `BindingData` ABI shape 去重共享（`bindingDataList` + `HashSet`），bind 名编码 arity/类型/static（本计划新增 `defaultSlotCount`，见下），`method_userdata` 当前直存 impl 函数指针——因此默认函数名**不得**硬编码进模板，必须经 userdata 传入。

1. **shape 与元数据通道**：`BindingData` 新增 `defaultSlotCount` 字段（int，默认 0；因 Godot 顺序规则默认值必为连续后缀，slot 数即可定位填充区间）。该字段参与 record 相等性与 bind 名编码（`_K_defslot` 后缀），保证同 shape 但默认值槽数不同的方法不共享 wrapper；`defaultSlotCount` **不**进 `method_info.default_argument_count`（§5.5 隔离不变）。命名终态合同：`defaultVariables` 通道永不产生 `_N_default_` 后缀；`_K_defslot` 仅由 `defaultSlotCount` 产生——`renderFuncBindName` 三处与 `collectBindingData` 必须使用同一计数，否则 wrapper 符号与 `entry.c.ftl` 注册点对不上。`collectBindingData` 从 `LirParameterDef.defaultValueFunc` 统计该值。
2. **userdata 由注册点独占创建并填充**：`defaultSlotCount > 0` 的方法在 `_class_bind_methods` 注册点（`entry.c.ftl`，per-method 生成）建立自己的 `static` userdata 实例（文件作用域或函数内 `static`，生命周期覆盖注册后全程），结构类型按 wrapper flavor（shape，含 static/instance 位）共享、实例按方法独占：`{ void* impl; <T0> (*def0)(<self?>); <T1> (*def1)(<self?>); ... }`——默认函数指针**按槽位类型化**（`<Ti>` = 该槽参数的 C storage 类型），因为同一方法的不同默认值返回类型异构（如 `godot_int` vs `godot_String`），禁止单一 typedef 直接赋值；**instance flavor 的 `defK` 首参为 owner fat self 类型**（synthetic 函数非 static、首参 `self`，§5.2），static flavor 无参。**结构类型的 named typedef 必须与 wrapper 同文件生成**（`entry.h.ftl`，随 `bindingDataList` 一起发射——wrapper 在 header 中需要把 `method_userdata` 转型为该结构，类型只在 `.c` 定义会编不过）；`entry.c.ftl` 只声明并初始化该类型的 per-method `static` 实例。注册点填好实例后把 `&ud` 传入 bind helper **现有的 `void* function` 形参**——helper 签名不变、不新增 default-fn 形参（那是 `defaultVariables` 通道，§5.5 恒空），helper 也不得填充任何共享静态块（helper 按 shape 共享，填充会被同 shape 方法互相覆盖）。`defaultSlotCount == 0` 的方法维持 `method_userdata = function`（实参即 impl 指针），零回归。wrapper 按 flavor 分支：`defaultSlotCount > 0` 的 wrapper 把 userdata 解释为结构体。
3. **固定执行顺序**（替换现有“全参守卫 + 全下标解包”流程，仅对 `defaultSlotCount > 0` 的 wrapper flavor 生效）：
   - argc 守卫：`p_argument_count < required_count`（= `param_count - defaultSlotCount`）→ `TOO_FEW_ARGUMENTS`，`expected = required_count`；非 vararg 且 `p_argument_count > param_count` → `TOO_MANY_ARGUMENTS`，`expected = param_count`（现有模板两种错误的 `expected` 都是 `param_count`，TOO_FEW 的 `expected` 由本项修正，测试钉死）。
   - 类型门 + typed Array/Dictionary probe：**仅对 `i < p_argument_count`** 的实参执行，保持在任何 unpack/fill 之前（中途 return 不涉及已物化 `argN` 的清理）。
   - 逐槽物化：默认后缀槽 `i` 一律生成**非 const** 声明（现有无 destructor 类型走 `const argN = unpack(...)`，无法二次赋值）；`p_argument_count > i` 时走既有 unpack，否则调用默认函数——instance flavor `argN = ud->def<i - required_count>(self_fat)`，static flavor `argN = ud->def<i - required_count>()`。按槽位类型化的函数指针（§5.6.2）返回内部 C storage 类型，与 `argN` 同型直接赋值，**不做 Variant 往返**。按声明顺序填充，满足 §2.2/§2.3。非默认槽维持现状（可继续 const + 无条件 unpack）。instance flavor 要求 `self_fat` 的求值点先于任何默认填充（模板中 `self_fat` 当前在函数调用处才构造，需前移）。
4. **生命周期**：填充产生的 object/容器/Variant-owned 值纳入 wrapper 既有逆序 cleanup epilogue（`renderCallWrapperDestroyStmt` 段），与实参解包值同等对待。
5. **static / instance**：`BindingData.staticMethod` 已参与 shape/flavor 区分；instance flavor 的默认函数指针带 `self` 首参，填充时传入 `self_fat`（§5.6.3）；static flavor 无参直调。
6. **vararg 边界**：`BindingData` 当前无 `isVararg`，wrapper 对 `p_argument_count > param_count` 一律 TOO_MANY——引擎/dynamic 路径的 vararg rest 透传是**既有缺口**，不在本计划；prologue 只承诺固定前缀填充（写入 §10）。
7. **ptrcall**：ABI 固定全参，不做 argc 守卫与默认填充；但 `defaultSlotCount > 0` flavor 的 ptrcall wrapper 必须与 call wrapper **同一套 userdata 解包**（`function = ud->impl` 后再调），否则会把结构体当函数地址调用（UB）。`defaultSlotCount == 0` 的 ptrcall 维持 `method_userdata` 直存 impl 不变。仓库无独立 validated_call 路径。
8. **获益路径**：gdcc 内部 dynamic 路由（`godot_Object_call`）、`Object.call`、ClassDB 对**已注册方法**的分发统一经 `GDExtensionMethodBind::call` 到达本 wrapper，缺参自动补全——frontend 与 exact 路由零改动。virtual 回调（`_process` 等走 `get_virtual_with_data`）不经过本 wrapper，不在获益范围。
9. **ABI 扩展点**：未来支持先行参数引用时，synthetic 函数在 `self`（instance）之后再追加先行参数值，改动集中在 wrapper 填充循环与 caller-side 补全两处（传入已物化的前序 `argN`）。

---

## 6. 诊断与恢复

- 移除：source function 的 `sema.unsupported_parameter_default_value`；`FrontendSuiteResolver` 对默认表达式根的 `sema.unsupported_binding_subtree` / `sema.unsupported_chain_route`。
- 新增（命名遵循既有 `sema.*` 风格，最终名以实现为准）：
  - `sema.invalid_parameter_default_order`：带默认值参数后出现无默认值参数，或 variadic 参数带默认值（锚定违规参数；违规参数不获得 `defaultValueFunc`）。
  - `sema.unsupported_parameter_default_expression`：默认表达式引用参数（含先行参数）/局部变量/capture，含 `await`，使用 `$`/`%` get-node，或在 **static** 方法中引用 `self`/实例成员（锚定默认表达式根，单条同级诊断；identifier/self/get-node/await 各路径在 island 内不得另发 `sema.binding` 等前置诊断）。instance 方法引用 `self`/实例成员为合法形态，不发诊断。
  - 类型不兼容：复用既有赋值兼容诊断通道，锚定默认表达式。
  - `sema.class_skeleton`：用户成员使用 `_default_` 保留前缀（沿用既有保留前缀诊断通道）。
- 保留：lambda 参数默认值维持 `sema.unsupported_parameter_default_value`；有参 `_init` 维持既有拒绝诊断。
- 恢复策略：诊断 + skip 默认表达式子树；参数按无默认值参与 arity 检查；同 module 其他子树继续。普通源码错误不抛异常；phase-order/side-table 不变量损坏（如 §5.2 名字不一致、§5.2 元数据悬空）才 fail-fast。

---

## 7. 分步骤实施

每一步独立可验证、独立提交；单批次改动不超过 5 个文件，超出则拆批。除特别说明外，测试改动与实现同批。

- [ ] **步骤 1：保留前缀与 inventory 开放**
  - `FrontendSyntheticPropertyHelperSupport.RESERVED_PREFIXES` 加入 `_default_`；skeleton 对用户 `_default_*` 函数发诊断并 skip。
  - `FrontendVariableAnalyzer.bindParameter()`：移除 source function 的 `sema.unsupported_parameter_default_value`（lambda 参数维持诊断）；照常登记参数 binding。
  - 验收：保留前缀负路径恰好一条诊断；`func f(a, b = 5)` 参数正常登记且不再发 unsupported 诊断；`FrontendVariableAnalyzerTest`（250-290）等锚点更新。
- [ ] **步骤 2：默认值分析 owner（island 语义 + 三阶段 sweep）**
  - 新增 `FrontendParameterDefaultMetadataOwner`：由 `FrontendSuiteResolver` 驱动、在任何 body 解析前执行 §4.1 三阶段 sweep（结构校验 → 占位写入 → island 分析 → 失败回收）。
  - `FrontendVisibleValueResolver` / `FrontendBodySemanticSupportPolicy` / `FrontendSuiteContext` / `FrontendBodyOwnerProcedures`：按 §4.2 落地 island 全部机制（独立入口、显式 domain、owner = enclosing `FunctionDeclaration`、双重封口开放、PARAMETER/CAPTURE/LOCAL 停在本层 + `bindIdentifier`/`bindSelf` 单条诊断、await 边界、get-node 与 self 链式分支、lambda 嵌套 fail-closed、expected type 接线）。
  - `FrontendTypeCheckAnalyzer`：按 §4.4 增加默认值类型兼容 hook。
  - 同步修订 `frontend_visible_value_resolver_implementation.md` 与 `frontend_resolution_pipeline_implementation.md` §4.8/R8（登记新 owner）。
  - 验收：`f(a, b = 5)` / `f(a, b = Vector2(1, 2))` / `f(a, b = make_default())`（static utility）的默认表达式获得完整 bindings/exprTypes/resolvedCalls 且参数携带 `defaultValueFunc`；**交叉缺省调用** `func g(x = f(1))`（`f` 带默认值）正路径通过；**instance 方法的 `b = self.x` / `b = self` / `b = inst_method()` 正路径**（self 正常绑定、实例成员正常解析）；`f(a: Array[int] = [1, 2])` 的容器字面量按参数声明类型获得 expected type；`b = a`、`b = x`（local）、`await`、`$Child`、static 方法中的 `b = self.x` 负路径各自恰好一条 `sema.unsupported_parameter_default_expression` 且参数回收为无默认值；`static var a = 1` + `func f(a, b = a)` 必须诊断参数引用而**不得**绑到外层 static 属性（§4.2 停在本层）；`func f(a = 1, b)` / `func f(...args = 1)` 恰好一条顺序诊断且违规参数无 `defaultValueFunc`；sweep 跳过 `_init` / constructor / lambda；默认值分析失败时同模块 `f(x)` 调用报 too-few（验证 §4.1 后果闭包）；类型不兼容默认值保留 `defaultValueFunc` 并发恰好一条赋值兼容诊断；`FrontendVisibleValueResolverTest`（387-409）、`FrontendSemanticAnalyzerFrameworkTest`（386-520）等锚点更新。
- [ ] **步骤 3：compile surface 开放**
  - `FrontendCompileCheckAnalyzer.walkCallableBody(...)` 对已接受默认根显式 `walkExpression()`（§4.3）；同步修订 `frontend_rules.md` 与 `frontend_compile_check_analyzer_implementation.md` 对应条款。
  - 验收：含合法默认值的模块通过 compile gate；纯字面量默认（无 call facts）同样被扫描；默认表达式内含 failed call 的模块在 compile surface 产生恰好一条诊断且不进入 lowering；`FrontendCompileCheckAnalyzerTest`（923-969、1575-1601、2335-2364）锚点更新。
- [ ] **步骤 4：preparation pass 物化 synthetic shell**
  - 按 §5.2 追加 shell + 发布 `PARAMETER_DEFAULT_INIT` context；扩展 shell-only 合同与名字一致性 fail-fast。
  - 验收：pre-pass 产物中每个带 `defaultValueFunc` 的参数存在同名 hidden shell——static 方法的 shell 无参且 static；instance 方法的 shell 非 static 且首参为 `self`（owning class 类型）；context 的 `sourceOwner`/`loweringRoot` 形状满足冻结合同；`FrontendLoweringFunctionPreparationPassTest`（322-380 形状锚点保持、866-900 转正）。
- [ ] **步骤 5：CFG pass 支持 `PARAMETER_DEFAULT_INIT`**
  - 按 §5.3 复用 `buildPropertyInitializer()` 范式。
  - 验收：默认表达式生成正确 value producer 与 RETURN stop；instance 默认表达式含 `self` / `self.x` / 裸实例方法调用时 CFG 有对应 self 读与成员/调用 value producer，static 方法的 shell CFG 无 self；`FrontendLoweringBuildCfgPassTest`（365-401 转正）。
- [ ] **步骤 6：body lowering 支持 `PARAMETER_DEFAULT_INIT`**
  - 按 §5.3 复用 return-stop lowering 发射 `ReturnInsn`。
  - 验收：synthetic function 的 LIR body 含表达式求值与 return；instance flavor 的 `self` 引用正确读 synthetic 的 `self` 参数槽（`declareSelfSlotIfNeeded` 路径），static flavor 无 `self` 槽；`FrontendLoweringBodyInsnPassTest`（9979-10016 转正）。
- [ ] **步骤 7：backend 接线验证、bind 隔离与端到端**
  - 验证/补齐 §5.4 各路由调用点补全（exact instance / static / bare→method|static-method）；按 §5.5 隔离 `method_info` 注册通道（三条收集路径的 `defaultVariables` 对 source function 恒空）并补回归；验证 §5.1 的 C 标识符（zig 端到端编译 `Class__default_f$b`；定义/调用/冲突检查同拼写；同名 static/instance 撞名分支测试）。
  - 验收（端到端，zig 可用时）：`f(1)` 与 `f(1, 2)` 结果正确；省略多个尾部参数按声明顺序求值；每次调用重新求值（可变默认值不共享）；too-few/too-many 仍 compile error；带默认值方法的 class 生成的 bind C 代码可编译、helper 名不含 default suffix、`default_argument_count == 0`。
- [ ] **步骤 8：argc 感知 callee-prologue wrapper（dynamic/引擎侧补全）**
  - `BindingData` 增加 `defaultSlotCount`（参与相等性与 `_K_defslot` bind 名编码，三处 `renderFuncBindName` 与 `collectBindingData` 同一计数）；`entry.c.ftl` 注册点为每个带默认值方法建立独占 `static` userdata 结构（impl + 按槽位类型化的默认函数指针字段），经 bind helper **现有 `void* function` 形参** 传入（helper 签名不变、不填共享静态块）；`entry.h.ftl` 新增 `defaultSlotCount > 0` 的 call/ptrcall wrapper flavor：call 侧 argc 守卫（TOO_FEW `expected = required_count` / TOO_MANY `expected = param_count`）→ 仅 `i < p_argument_count` 的类型门/probe → 逐槽 unpack-or-default（默认槽非 const 声明）→ cleanup 纳入；ptrcall 侧同一套 userdata 解包（`function = ud->impl`）、不做填充。
  - 验收：bind 层 C 测试覆盖 `p_arg_count` 缺参/恰好 required/超参边界与两种 `GDExtensionCallError` 的 `expected` 字段；ptrcall 全参回归（经 `ud->impl` 调用）；异构默认值返回类型（`int` + `String`）逐槽正确赋值；instance flavor 填充时 `self_fat` 正确传入且求值点先于任何默认填充；同 shape 不同 `defaultSlotCount` 的方法不共享 wrapper（bind 名区分）且 userdata 实例互不覆盖；`defaultSlotCount == 0` 方法保持 `method_userdata = function` 零回归；端到端（zig 可用时）gdcc 内部 dynamic 调用与 `Object.call` 省略实参结果正确且逐调用重新求值；`method_info.default_argument_count == 0` 回归保持。
- [ ] **步骤 9：文档收尾**
  - 本计划转为事实源（移除分步清单）；同步修订 `frontend_lowering_func_pre_pass_implementation.md` §7/§10、`frontend_lowering_plan.md` §7、`frontend_exact_call_extension_metadata_contract.md`（说明 source function 默认值经 `defaultValueFunc` 元数据承载、不改 `ExactCallableBoundary` 载荷范围）、`gdcc_low_ir.md` 的 arity 验证条款。

---

## 8. 验收准则

1. `func f(a, b = <MVP 形态表达式>)` 在 instance / static 两种声明下均可编译，且 `f(x)` 与 `f(x, y)` 两种调用结果符合 Godot 语义。
2. 省略多个连续尾部参数时，默认值按声明顺序、逐调用重新求值；可变默认值（数组/字典字面量）跨调用不共享实例。
3. 顺序规则违规（默认值参数后接必填参数、variadic 带默认值）产生恰好一条 `sema.invalid_parameter_default_order`，锚定违规参数，违规参数不获得 `defaultValueFunc`，其余子树照常分析。
4. 默认表达式引用参数（含先行参数）/局部变量/capture、含 `await`、使用 get-node，或在 static 方法中引用 `self`/实例成员时，产生恰好一条 `sema.unsupported_parameter_default_expression`，该参数回收为无默认值参与 arity 检查（同模块 `f(x)` 随之报 too-few，包括调用点源码顺序在前的情形），module 其余部分照常分析；instance 方法引用 `self`/实例成员为合法形态且结果正确。
5. 显式类型参数的默认值类型不兼容时，经既有赋值兼容通道发恰好一条诊断，锚定默认表达式；该参数保留 `defaultValueFunc`（arity 仍允许省略），模块由类型诊断 fail-closed。
6. bare / exact instance / static 路由省略合法尾部参数时不再产生 arity 失败；省略后仍缺 required 参数时产生精确 too-few 诊断；非 vararg 超参产生精确 too-many 诊断。
7. dynamic / Variant 路由实参列表维持原样透传，不新增 frontend 猜测逻辑；缺参由 §5.6 prologue wrapper 在运行时补全，结果与静态路由一致（逐调用重新求值、声明顺序），`Object.call` 与 ClassDB 已注册方法分发路径同样生效（不含 virtual 回调）。
8. shared `analyze(...)` 路径不产生异常、不暴露悬空 `defaultValueFunc`；lowering 只消费 `analyzeForCompile(...)` 发布的事实；默认表达式 facts 与 body facts 同表同构。
9. compile-only 路径中，默认表达式子树存在未解决事实时模块被 compile gate 拦截且不进入 lowering。
10. 带默认值 source 方法的 ClassDB bind 生成代码可编译，`default_argument_count == 0`（method_info 通道恒空）；prologue wrapper 的 arity 守卫与错误语义对齐 Godot VM（TOO_FEW `expected = required_count`、TOO_MANY `expected = param_count`）；同 shape 不同默认值槽数的方法生成不同 wrapper；`_default_` 保留前缀对用户成员生效。
11. 全部既有测试在按计划更新锚点后通过；新增正负路径测试覆盖 §7 各步验收点；`./gradlew clean build --no-daemon --info --console=plain` 全绿。

---

## 9. 测试锚点

- 更新（原 fail-closed 锚点转正或改写）：
  - `src/test/java/gd/script/gdcc/frontend/sema/FrontendVariableAnalyzerTest.java`（250-290）
  - `src/test/java/gd/script/gdcc/frontend/sema/FrontendScopeAnalyzerTest.java`（默认表达式 scope 记录断言保持）
  - `src/test/java/gd/script/gdcc/frontend/sema/resolver/FrontendVisibleValueResolverTest.java`（387-409）
  - `src/test/java/gd/script/gdcc/frontend/sema/FrontendSemanticAnalyzerFrameworkTest.java`（386-520）
  - `src/test/java/gd/script/gdcc/frontend/sema/FrontendClassSkeletonTest.java`（758-793，断言默认值参数在 skeleton 阶段仍为 `defaultValueFunc = null`）
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`（923-969、1575-1601、2335-2364）
  - `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringFunctionPreparationPassTest.java`（322-380 形状锚点保持、866-900 转正）
  - `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBuildCfgPassTest.java`（365-401 转正）
  - `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`（9979-10016 转正）
- 新增：各路由省略参数正路径、顺序规则负路径、受限可见性负路径（static 方法中 `b = self` 单条诊断）、instance 方法 `self`/实例成员引用正路径（含 synthetic 首参 `self` 与调用点/wrapper 传接收者）、类型不兼容负路径（元数据保留）、§4.1 后果闭包（call-before-callee 顺序洞）、交叉缺省调用正路径与回收角落、sweep 内 await 边界、bind method_info 通道隔离回归、C 标识符与同名 static/instance 撞名、逐调用重新求值端到端用例、§5.6 prologue wrapper 的 bind 层 argc 边界用例（缺参/恰好 required/超参、错误 `expected` 字段、shape 去重区分、`defaultSlotCount == 0` 零回归）与 dynamic/`Object.call` 端到端用例。

---

## 10. 已知限制与后续方向

- **constructor / `_init` 参数默认值（永久非目标，无需实现）**：GDExtension 的 class constructor 回调没有实参通道，runtime 入口（`entry.c.ftl`）只在零参时调用 `_init`，因此有参 `_init` 本身不可实现，参数默认值自然也不支持。现有拒绝链保持不变：声明侧 `FrontendTypeCheckAnalyzer` 拒绝有参 `_init` → 调用侧 `FrontendConstructorResolutionSupport.resolveGdccConstructor()` 拒绝任何实参 → compile gate `shouldBlockParameterizedGdccConstructor` 拦截 → lowering `ConstructObjectInsn` 不传 `_init` 实参。sweep 范围排除 `_init` 的条款（§4.1）即由此而来。
- **先行参数引用**：Godot 允许 `b = a`，MVP 排除。`self`/实例成员已支持（instance synthetic 首参为 `self`，§5.2/§5.4/§5.6）；后续方向是在 `self` 之后再向 synthetic 追加先行参数值（§5.6.9），需同步修订 func_pre_pass §7 合同与 `ExactCallableBoundary` 载荷范围。
- **无类型参数从默认值推导类型**：需在参数 typed baseline 之后引入 post-patch 推导师（参照 `FrontendVarTypePostPatch` 机制），属独立特性。
- **override 默认值**：MVP 只按静态解析到的 `FunctionDef` 补全（§3.4）；Godot 级继承语义另开。
- **引擎/dynamic 路径的 vararg rest 透传**：`BindingData` 无 `isVararg`，call wrapper 对超参一律 TOO_MANY——`func f(a = 1, ...rest)` 经引擎/`Object.call` 传入 rest 实参是既有缺口，prologue 只承诺固定前缀填充；如需支持，另行立项（`BindingData.isVararg` + 放宽 TOO_MANY + rest → Array）。
- **lambda 参数默认值**：随 lambda 参数默认值元数据接通单独开放。
- **默认表达式内 `await`**：维持 fail-closed；与 coroutine 合同联动评估。

---

## 11. 参考实现位置

- AST：`dev.superice.gdparser.frontend.ast.Parameter#defaultValue()`（gdparser 0.5.3）
- 参数登记/诊断：`src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendVariableAnalyzer.java`（`bindParameter` 592-628、`reportUnsupportedDefaultValue` 864-875）
- skeleton 参数元数据：`src/main/java/gd/script/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`（`fillFunctionParameters` 486-510）
- 保留前缀：`src/main/java/gd/script/gdcc/frontend/sema/FrontendSyntheticPropertyHelperSupport.java`（15-33）
- visible-value 边界：`src/main/java/gd/script/gdcc/frontend/sema/resolver/FrontendVisibleValueResolver.java`（`classifyBoundaryEdge` 167-181、`classifyRequestDomainBoundary` 183-193）、`FrontendBodySemanticSupportPolicy.java`（32）、`FrontendSuiteContext.java`（92-104）
- arity/默认值判定：`src/main/java/gd/script/gdcc/scope/resolver/ScopeMethodResolver.java`（773-825、852-935）、`ScopeMethodParameter.java`、`src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`（2200-2235）、`.../support/FrontendConstructorResolutionSupport.java`（475-529）
- compile gate：`src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`（`walkCallableBody` 371-391、`handleFunctionDeclaration` / `handleConstructorDeclaration`）
- lowering context/物化：`src/main/java/gd/script/gdcc/frontend/lowering/FunctionLoweringContext.java`、`pass/FrontendLoweringFunctionPreparationPass.java`（lambda shell 范式 373-419）、`FrontendLoweringBuildCfgPass.java`、`FrontendLoweringBodyInsnPass.java`、`cfg/FrontendCfgGraphBuilder.java`（`buildPropertyInitializer` 204-225）
- backend 补全：`src/main/java/gd/script/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`（217-474）、`CGenHelper.java`（binding data 307-330）
- binding 模板：`src/main/c/codegen/template_451/entry.h.ftl`（call wrapper 解包/调用段 560-650、bind helper 与 method_info 段 695-775）、`entry.c.ftl`（注册调用点 157-167）、`func.ftl`（4-11）
- Godot GDExtension 参照：`godotengine/godot` `core/extension/gdextension.cpp`（`GDExtensionMethodBind::call` 原样转发 argc、不做默认值填充；`update()` 经 `set_default_arguments` 存 bind 期常量）
- Godot 参照：`godotengine/godot` `modules/gdscript/gdscript_parser.cpp`（1529-1555、1704-1755）、`gdscript_analyzer.cpp`（2164-2260、6134-6150）、`gdscript_compiler.cpp`（2337-2359、2439-2457）、`gdscript_vm.cpp`（501-575）
