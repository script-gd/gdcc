# GDScript 语言内置函数（len/char/ord/load/is_instance_of/range）与 assert/preload 实现

## 文档状态

- 状态：Implemented / Maintained（本主题的单一事实来源）
- 范围：frontend sema/lowering、LIR `assert` 指令、C backend 代码生成、`gdcc/**` runtime helper
- 更新时间：2026-08-30
- 目标读者：后续维护者、代码评审者
- 语义锚点来源：`godotengine/godot` 仓库 `modules/gdscript/gdscript_utility_functions.cpp`（4.5 分支，与 `GodotVersion.V451` 对齐；master 分支 `char` 校验已收紧，不可混用）
- 关联文档：`doc/gdcc_low_ir.md`（`assert` 指令合同）、`doc/gdcc_runtime_lib.md`（helper 清单）、`doc/module_impl/common_rules.md`

本文只保留当前已落地、可验证的实现事实与长期约定，不保留实施过程与阶段进度。

## 1. 覆盖范围

已实现 GDScript 语言级内置函数 `len`、`char`、`ord`、`load`、`is_instance_of`、`range`，以及 `assert` 语句与 `preload` 表达式：

- 这六个函数是 GDScript 语言专属 utility function，由 Godot 在 `GDScriptUtilityFunctions::register_functions()` 中注册，**不存在于 `extension_api_451.json`**，因此无法复用生成的 `godot_*` utility wrapper，由编译器合成元数据并提供 `gdcc_*` runtime helper。
- `assert` 通过 LIR 指令 `assert` 实现，失败走与 `assert_object_live` 一致的 runtime-error + default-return 路径（§6）。
- `load`/`preload` 不生成 `gdcc_*` helper，统一在 lowering 期改写为 `ResourceLoader` singleton 实例调用对（§7）。

## 2. Godot 语义锚点（4.5 分支单一事实来源）

| 函数 | 签名（Godot MethodInfo） | 运行时语义 | 错误行为 |
| --- | --- | --- | --- |
| `len` | `int len(Variant var)` | String/StringName 取字符数；Array/Dictionary 取 size；全部 Packed*Array 取 size | 其它类型：CallError + 错误消息 |
| `char` | `String char(int code)` | `String::chr(code)` | 4.5 仅校验 `code < 0 \|\| code > 0xFFFFFFFF`（UINT32_MAX）时报错；`code == 0` 合法（NUL 字符）；surrogate 与 `> 0x10FFFF` 由 `String::chr` 替换为 U+FFFD 并打印 unicode 错误，**不产生 CallError** |
| `ord` | `int ord(String char)` | 返回首字符的 Unicode code point | 字符串长度不为 1 时报错 |
| `range` | `Array range(...)` vararg，1..3 个 int | 物化一个元素全为 int 的**未参数化 Array**；`range(n)` 等价 `range(0, n, 1)` | `step == 0`、元素数超 `INT32_MAX`、参数无法严格转换为 int（Godot `can_convert_strict(INT)` 接受 INT/BOOL/FLOAT，其余类型拒绝）时报错 |
| `load` | `Resource load(String path)` | 等价 `ResourceLoader.load(path)` | 加载失败由 ResourceLoader 自行报错并返回 null |
| `is_instance_of` | `bool is_instance_of(Variant value, Variant type)` | `type` 为 int（`TYPE_*` 常量）时比较 `value` 的 Variant 类型；`type` 为 Object 时按 GDScriptNativeClass/Script 继承链判断 | value 为 freed 对象报错；value 为 null 返回 `false` |

补充锚点与 gdcc 决策：

- `assert(condition, message?)`：Godot 中 condition 采用 truthiness 契约（不要求严格 bool），message 为可选表达式；失败在 debug 构建报错并中断，release 构建被移除。**gdcc 决策**：始终保留检查（gdcc 暂无 debug/release 构建区分），失败走 runtime-error + default-return（§6.3）。
- `preload(path)`：Godot 中为编译期资源加载并作为常量嵌入。**gdcc 决策**：退化为求值点的 `ResourceLoader.load(path)`，路径必须是字符串字面量（§7）。
- `range` 在 `for` 循环头中已被 gdcc 特判（`RANGE_CALL` route + `gdcc.for_range_iter.*` intrinsic），本文只覆盖独立表达式调用 `var x = range(...)`，for 路径不变。
- 非目标：Godot 同文件注册的其它语言 utility（`type_exists`、`print_debug`、`print_stack`、`get_stack`、deprecated 的 `convert`/`inst_to_dict`/`dict_to_inst`/`Color8`）不在实现范围；其余非目标见 §8。

## 3. 合成语言函数注册（frontend 元数据）

### 3.1 合成注册表

`ClassRegistry` 持有独立的 `gdScriptLanguageFunctionByName: Map<String, ExtensionUtilityFunction>`，构造时注册六条合成 `ExtensionUtilityFunction` 记录（复用现有 record，无新建元数据类型）：

| 名称 | 返回类型 | 参数 | 备注 |
| --- | --- | --- | --- |
| `len` | `int` | `(var: Variant)` | |
| `char` | `String` | `(code: int)` | |
| `ord` | `int` | `(char: String)` | |
| `range` | `Array` | 无固定参数，`isVararg=true` | 与 Godot MethodInfo 一致；元素恒为 int 但不参数化返回类型；arity（1..3）由 frontend 诊断层兜底（vararg 匹配对参数数直接放行） |
| `is_instance_of` | `bool` | `(value: Variant, type: Variant)` | |
| `load` | `Resource` | `(path: String)` | 不进 backend `gdcc_*` 映射表（§4） |

公共字段：`category="GDScript"`、`hash=0`。`hash=0` 安全的前提是 hash 的消费者（`construct_standalone_callable` 经 `findUtilityFunction`、`godot_utility` wrapper 生成经 `getExtensionUtilityFunctionList`）只读原始 extension 表、永远看不到合成表（§3.2 的查询面收口保证了这一点）；若未来出现新消费者，需改为存放稳定伪 hash。

**同名冲突 fail-fast**：注册时若 `utilityByName` 已含同名 extension utility 直接 `IllegalStateException` 拒绝启动——解析查询给 extension 表优先权、backend 路由以合成表为准，同名冲突会让一个名字路由到两套后端。

### 3.2 查询面收口

- **回查合成表**：`resolveFunctionsHere`、`findUtilityFunctionSignature`、`isUtilityFunction`（object-type 猜测排除依赖后者）。
- **明确不回查**：`findUtilityFunction` 与 `getExtensionUtilityFunctionList`。前者被 `ConstructInsnGen.resolveUtilityStandaloneSpec` 用于一等函数引用 `var f = len` 的 `construct_standalone_callable`，其 C 侧 `gdcc_callable.h` 对 `utility_hash == 0` 直接返回 NULL，合成函数 hash=0 无法支持该路径；后者喂食 `GodotUtilityGenerator`/provided-symbols，合成条目进入会凭空生成不存在的 `godot_len` 等 wrapper。

### 3.3 一等函数引用禁令

`var f = len` / `var g = range` 等把语言函数当值引用的写法不支持。防线分两层：

1. frontend：值引用位置（`resolveIdentifierExpressionType` 的 `UTILITY_FUNCTION` 分支）对合成语言函数发 `sema.expression_resolution` 诊断；裸调用路径对合成语言函数 callee **跳过值解析**（与 TYPE_META 构造调用分支同形先例），避免误伤合法直接调用并产生重复诊断。
2. backend：`ConstructInsnGen` 经 `findUtilityFunction` 查不到合成条目时保持 fail-fast，不得静默降级。

遮蔽语义自然成立：用户同名函数在局部 scope 先于全局命中，与 Godot 一致；`for` 中 `range` 特判不受影响。

### 3.4 新增语言函数的落地约定

新增语言函数时，注册表条目、backend `gdcc_*` 映射表条目（或显式拒绝分支）、runtime helper、测试必须同批次落地，避免"已注册但 backend/helper 尚未落地"的窗口期 ICE。

## 4. backend `gdcc_*` 路由合同

`CGenHelper.resolveUtilityCall` 对合成语言函数按静态映射表生成 `gdcc_*` C 函数名，不加 `godot_` 前缀：

| 语言函数 | C helper |
| --- | --- |
| `len` | `gdcc_len` |
| `char` | `gdcc_char` |
| `ord` | `gdcc_ord` |
| `range` | `gdcc_range` |
| `is_instance_of` | `gdcc_is_instance_of_global` |

- `load` 不在映射表中：frontend lowering 已将其改写为 singleton 实例调用（§7），坏 IR 出现 `call_global "load"` 时 `resolveUtilityCall` 对其**显式按名 fail-fast**。
- **按名合同**：映射表 = 合成注册表 \ {load}。对命中合成注册表的每个名字先查映射表再取签名——映射缺失即 fail-fast，且 `load` 的拒绝不依赖周围 registry 能否解析 `Resource` 返回类型元数据（最小测试 fixture 无该类时也必须命中合同消息而非类型解析错误）。映射查询由包内可见的 `CGenHelper.requireGdScriptLanguageFunctionCName` 承担，可直接单测。**不做映射表与注册表的全表相等检查**，以兼容按 §3.4 分批落地的新增函数。
- `CallGlobalInsnGen` 无需特判：固定参数类型校验、Variant vararg 校验、default 物化、`callAssign`/`callVoid` 路径全部复用（`range` 无固定参数时全部实参走 Variant vararg tail，frontend `materializeCallArguments` 负责 pack）。
- 命名防御：所有相关 C 符号使用 `gdcc_` 前缀，禁止命名为 `godot_*`（`char` 同时是 C 关键字，更不允许裸名）。

## 5. runtime helper：`gdscript_builtins.h`

`src/main/c/codegen/include_451/gdcc/gdscript_builtins.h` 为 header-only（static inline，模式对齐 `gdcc_helper.h`），编译源清单 `CProjectBuilder.GDCC_RUNTIME_SOURCE_PATHS` 只列 `.c` 文件，header-only helper 不需要改构建脚本。当前函数清单：

- `gdcc_assert_failed(const godot_String *message_or_null, const char *func, const char *file, int line)`：`assert` 失败打印通道（§6.3）。
- `gdcc_len(const godot_Variant *value)`：按 Variant 类型分派。结构为两层——per-type helper（`gdcc_len_string`/`gdcc_len_string_name`/`gdcc_len_array`/`gdcc_len_dictionary`/`gdcc_len_packed_*_array`，接受具体载荷指针）加 `gdcc_len` 动态分派（unpack → 分派 → destroy）；类型编译期已知时后续可由 intrinsic 通道静态直调对应 per-type helper，跳过 Variant 类型检查与临时解包。未支持类型 `GDCC_PRINT_RUNTIME_ERROR` 后返回 `0`。
- `gdcc_char(godot_int code)`：4.5 语义——仅 `code < 0 || code > 0xFFFFFFFF` 时报错并返回空 String；其余委托 `godot_String_chr`（surrogate 与 `> 0x10FFFF` 的替换行为随 `String.chr`，不做额外拦截）。
- `gdcc_ord(const godot_String *value)`：`godot_String_length != 1` 报错返回 `0`；否则取首字符 code point。
- `gdcc_range(const godot_Variant **argv, godot_int argc)`：校验 `1 <= argc <= 3`、参数经 Godot `can_convert_strict(INT)` 规则（接受 INT/BOOL/FLOAT，经 `godot_new_int_with_Variant` 转换，其余类型拒绝）、`step != 0`、元素数 `<= INT32_MAX`。1 参 `[0,count)`、2 参 `[from,to)`、3 参方向感知空集 + ceil-division 计数；1/2 参形式经 `step` 默认 1 并入统一分派。元素计数使用无符号 distance/stride ceil-division，填充循环按预计算 count 迭代且不在末元素后再自增——这是对引擎参考实现的有意加固，消除 `INT64_MIN/MAX`、`step == INT64_MIN` 等极值输入下的有符号溢出与潜在死循环，普通输入语义逐位一致。任一校验失败 `GDCC_PRINT_RUNTIME_ERROR` 并返回空 Array（返回值为调用方 OWNED，discard 路径销毁由 backend 测试锚定）。
- `gdcc_is_instance_of_global(const godot_Variant *value, const godot_Variant *type)`：`type` 为 INT 时比较 `godot_Variant_get_type(value)`（有意不做对象存活探测，与 Godot 一致）；越界枚举值（`<0` 或 `>= VARIANT_MAX`）报错返回 `false`（对齐 Godot debug 校验）；`type` 为其它种类时报"class/script type argument 暂不支持"并返回 `false`（§8）。

通用约定：

- 错误处理统一为"helper 内打印 runtime error + 返回类型默认值"，不回传错误给调用点；这与 gdcc 对可恢复运行时错误的现有风格一致，避免在 `CallGlobalInsnGen` 引入新的控制流。
- **include 接入**：`gdcc/gdcc_helper.h` 在 `GDCC_PRINT_RUNTIME_ERROR` 宏定义**之后**包含 `gdscript_builtins.h`（新 helper 依赖该宏与 `godot_*` 类型），使 `entry.h.ftl` 经 `gdcc_helper.h` 传递包含；禁止放在 `gdcc_helper.h` 顶部 include 块（宏尚未定义会导致编译失败），`gdscript_builtins.h` 自身不得回引 `gdcc_helper.h`（避免循环包含）。生成 C 的 include 图是封闭的，`GdccHelperBindingScanner` 只做 binding 用量扫描、不负责接入，新 header-only helper 文件必须显式挂入 include 图。
- `GdccHelperBindingScanner` 递归扫描 `gdcc/**` 的 `.h/.c/.ftl` 收集 `godot_*` 用量，helper 新文件被自动覆盖；相关回归：`FixedGodotBindingsTest`、`GodotBindingTool` 的 check-fixed 等价校验。
- ownership：`gdcc_range` 返回 destroyable Array（OWNED，discard 必须 destroy）；`ResourceLoader.load` 返回 RefCounted Resource（OWNED，discard 必须 release）。

## 6. `assert` 全链路

### 6.1 LIR 指令

```text
assert $cond;
assert $cond $msg;
```

- `GdInstruction.ASSERT("assert", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.VARIABLE), 1, 2)`，Misc 分组；`lir/insn/AssertInsn.java`（record：`conditionId`、`@Nullable messageId`）实现 `MiscInstruction`，**不**携带 lifecycle provenance（非生命周期指令）。
- `ParsedLirInstruction.toConcrete()` 有 `ASSERT` 分支（该 switch 是 exhaustive 的）；通用 parser/serializer 无需改动。
- LIR 合同：condition 槽位**必须已是 bool**（truthiness 归一化是 frontend lowering 职责，§6.2）；message 槽位若存在必须是 `String` 可赋值类型；backend 对违规 fail-fast。
- result 前缀合同：`ReturnKind.NONE` 不被通用 parser 强制（`$r = assert $c;` 在文本层可解析），正式合同是**静默丢弃**——`toConcrete()` 不读取解析到的 result 前缀，`AssertInsn.resultId()` 恒为 `null`，backend 不对 result 前缀 fail-fast。与 `assert_object_live` 现状一致。
- 指令合同同步登记在 `doc/gdcc_low_ir.md` 的 Misc Instructions 节。

### 6.2 frontend 解锁与 lowering

- compile gate：`handleAssertStatement` 走 `markCompileSurfaceNode` + 递归检查 condition/message，无显式阻断。
- type-check：`FrontendTypeCheckAnalyzer.handleAssertStatement` 校验 message 必须为 String 可赋值类型（诊断类别 `sema.type_check`）；condition 契约沿用 `visitConditionExpression` 的既有立场（Godot-compatible truthiness 是 lowering/runtime 关注点）。
- CFG：`FrontendCfgGraphBuilder.processStatement` 分派 `AssertStatement`，按词法顺序物化 condition 与可选 message 后向当前 sequence 追加 `AssertItem`（`frontend/lowering/cfg/item/AssertItem.java`，承载 conditionValueId、messageValueIdOrNull、源节点）。
- sealed 接线：`SequenceItem` 的 `permits` 包含 `AssertItem`；`AssertItem` 直接实现 `SequenceItem`，**不得**实现 `ValueOpItem`（无结果值，避免撞上 `requireProducedValueMaterialization` 的 exhaustive 分派）；processor 在 `FrontendInsnLoweringProcessorRegistry`（精确类型匹配、无父类回退）注册。
- truthiness 归一化的唯一实现点是共享 helper `FrontendBodyLoweringSupport.materializeTruthinessToBool(session, block, valueId, type) -> boolSlotId`（bool 直接用；Variant 用 `UnpackVariantInsn`；其它类型先 `PackVariantInsn` 再 unpack 为 bool）。helper **只返回 bool slot、不调用 `setTerminator`**：branch processor 调用后自行 `GoIfInsn`，assert processor 调用后发射 `AssertInsn`；两处共用，禁止复制实现。
- `FrontendLoopControlFlowAnalyzer.handleAssertStatement` 对 condition 与 message 都执行 `scanNestedCallableBoundaries`。

### 6.3 失败语义与 backend

始终保留检查；失败时：

```c
if (!<cond>) {
    gdcc_assert_failed(<msg_or_NULL>, __func__, __FILE__, __LINE__);
    <returnDefault() 发射>
}
```

- runtime helper `gdcc_assert_failed` 固定放在 `gdscript_builtins.h`（单一接入点），内部复用 `GDCC_PRINT_RUNTIME_ERROR` 的打印通道。
- backend 入口：`CBodyBuilder.emitAssertGuard(conditionVar, messageVarOrNull)`（镜像 `emitAssertObjectLiveGuard`，含 `__finally__` 禁用校验）；`AssertInsnGen` 只做 IR 校验（condition 存在且 bool、message 存在且 String 可赋值、非 `__finally__`）并委托，不校验 resultId（§6.1）。
- 与 Godot 的差异（release 移除、debugger 中断）属非目标（§8）。

## 7. `load`/`preload`：ResourceLoader singleton 改写

### 7.1 改写设计

关键事实：`ResourceLoader.load` 在 `extension_api_451.json` 是**实例方法**（`is_static=false`），`ScopeMethodResolver.resolveStaticMethod` 只保留 `isStatic()` 候选，`call_static_method` 路线在 backend 必然 fail-fast；但 `ResourceLoader` 是注册 singleton，已有 singleton 物化链路可直接复用。因此不新增 `gdcc_*` C helper，frontend 在 lowering 时把已解析的 `load`/`preload` 调用改写为两条已有指令：

```text
$rl_tmp = load_static "@GlobalScope" "ResourceLoader";
$res    = call_method "load" $rl_tmp $path;
```

从而完整复用：`LoadStaticInsnGen` singleton BORROWED 物化、`CallMethodInsnGen` + `BackendMethodCallResolver` ENGINE 实例调用、缺省参数 `type_hint=""`/`cache_mode=1` 的 backend 物化（`CallMethodInsnGen.validateFixedArgsAndCompleteDefaults`，已有 `Node.add_child` 等测试覆盖）、RefCounted 返回值的 OWNED 所有权处理。

### 7.2 `load` 改写锚点

`FrontendSequenceItemInsnLoweringProcessors.lowerStaticMethodCall` 中 `isSyntheticLoadCall` 检测 resolved function 是合成语言函数 `load`（要求 `declarationSite() instanceof ExtensionUtilityFunction` 且经 `ClassRegistry.isGdScriptLanguageFunction` 确认）时改发上述指令对；declaration-site 检查使用户同名函数（static 或 instance）天然落在各自的路由上。singleton 接收者临时槽位经 session 专用分配器 `allocateGdScriptLanguageFunctionTemp` 分配（槽位前缀 `cfg_lang_fn_<purpose>_<n>`，与 `cfg_writable_*`/`cfg_match_*` 等既有用途前缀同级隔离），类型 `GdObjectType("ResourceLoader")`；`call_method` 的 resultId 透传原 call 的 resultId（无结果调用同样合法——两条指令的 ReturnKind 都支持）。

### 7.3 `preload` sema 与 lowering

- sema（`FrontendExpressionSemanticSupport.resolvePreloadExpressionType`）：先嵌套解析 path 子节点并原样传播非稳定事实；路径必须是 `LiteralExpression` 且 `kind == "string"`（`StringName` 字面量不接受），否则 `FrontendExpressionType.failed`（诊断类别 `sema.expression_resolution`）；成功发布 `RESOLVED(GdObjectType("Resource"))`。
- 路径**原样透传**给 `ResourceLoader.load`，编译期不做相对路径归一化（与 Godot `preload` 的相对路径解析是有意差异，与"共享运行时动态加载语义"一致；运行时由 ResourceLoader 自行处理/报错）。
- **不发布** `FrontendResolvedCall`：`resolvedCalls` 的 key 空间被多处冻结为 `CallExpression`/`AttributeCallStep`（call 缓存以 `CallExpression` 为键、`requireCallAnchor`/`describeCallAnchor` 只认两类节点、多份 frontend 合同文档已写明），把 `PreloadExpression` 塞进去属于不必要的架构扩张。
- compile gate：无显式阻断分支，走默认 `walkExpression` 路径，由 generic published-fact scan 承接；表达式分派当前不保留任何显式拦截（`GetNodeExpression` 已解除，见 `frontend_node_literal_implementation.md`）。
- CFG `buildValue` 的 `PreloadExpression` 分支与 `IdentifierExpression` 同形，产生 `OpaqueExprValueItem`；`classifyOpaqueExpression` 将其分类为 `HANDLE_NOW`。
- `FrontendOpaqueExprInsnLoweringProcessors.FrontendPreloadOpaqueExprInsnLoweringProcessor`：从 AST 直接取已校验的字面量（opaque item 无子操作数），经 `StringUtil.decodeGdStringLexeme` 解码、路径原样透传，物化 `literal_string` 槽位与 ResourceLoader 临时槽位，连续发射 `LiteralStringInsn` + `LoadStaticInsn("@GlobalScope", "ResourceLoader")` + `CallMethodInsn("load", ...)`；结果槽 `cfg_tmp_<valueId>` 由 `declareCfgValueSlots` 以已发布的 `Resource` 类型预声明。

### 7.4 引擎行为备忘（测试相关）

判定加载是否成功不能用 `is_instance_of(res, TYPE_OBJECT)` 组合：`Variant(const Object*)` 对 null 也置 `type=OBJECT`（payload 为 null），且全局 `is_instance_of` 的 INT 路径只做 `get_type()` 比较、不做 null 检查，typed null 与 NIL 在该路径上语义不同。引擎集成测试以 `x is Resource`（typed-null 感知）判定加载结果。

## 8. 非目标与已知限制

- **gdcc 脚本路径（`.gd`）拦截/诊断不实现**：gdcc 源代码不走脚本系统，经 `load`/`preload` 加载会得到无意义的 `GDScript` 资源，属于调用方责任；无编译期/运行时检查，无路径归一化 util。确有需要时单独立项。
- **`is_instance_of` 仅支持类型枚举数字**（`type` 为 `TYPE_*` int 常量）：class/script 形式依赖"类作为一等值"（GDScriptNativeClass 等价物），当前类型系统无此概念，运行时报错拒绝、不在 compile gate 阻断（type 参数是 Variant，编译期无法判定）。如需支持须先设计 class-value 表示，属较大架构议题。
- **`assert` message 限定 String 可赋值**：Godot 允许任意表达式（按 Variant 字符串化）。如需放宽，后续把 message 槽位改为 Variant 并在 helper 内字符串化。
- **语言函数的一等值引用不支持**：`var f = len` 被双层防线拒绝（§3.3）。
- **`const X = preload(...)` 不解锁**：依赖尚未立项的 class-constant 工作流；负向锚点保留在 `FrontendClassSkeletonTest`。类级 `var icon: Resource = preload(...)` 走 supported property initializer 已端到端可用。
- **`is_instance_of` 硬边界**：全局 `is_instance_of()` 是 `call_global`，与 `x is T` 表达式的 `IsInstanceOfInsn`（`is_instance_of "<type_name>" $value`）是两套独立合同，禁止互相 lower 或复用 `IsInstanceOfInsnGen`/`gdcc_is_instance_of_object_*` helper；全局函数专用 helper 命名为 `gdcc_is_instance_of_global` 以示区隔。负向测试锚定 `is_instance_of(x, TYPE_INT)` 生成的 C 不含 `gdcc_is_instance_of_object_`。
- **`assert` 的 Godot 差异**：release 构建移除、debugger 中断均不实现。

## 9. 风险与防线

- **元数据漂移**：合成注册表（frontend）与 `gdcc_*` 映射表（backend）分离，靠 `resolveUtilityCall` 的双向 fail-fast 与注册表单测兜底。
- **`range` 与 for 路径串扰**：for 特判发生在 header 解析期，早于普通 bare-call 解析；改动 `range` 相关逻辑必须回归全部 for-range 测试。
- **engine 实例方法缺省参数**：`ResourceLoader.load` 依赖 backend 对 engine 实例方法 default 的物化（`CallMethodInsnGen.validateFixedArgsAndCompleteDefaults`），改动该路径需同步回归 singleton 调用用例。
- **防御性校验**：所有新 `CInsnGen` 先完整校验再发射，错误一律 `InvalidInsnException`；helper 内不做静默降级。
- **编码与换行**：新增 C 头文件保持 UTF-8 无 BOM、与既有 runtime 文件一致的行尾。

## 10. 实现锚点（文件索引）

- LIR：`enums/GdInstruction.java`（`ASSERT`）、`lir/insn/AssertInsn.java`、`lir/parser/ParsedLirInstruction.java`（`ASSERT` 分支）
- frontend：`scope/ClassRegistry.java`（合成注册表与查询面）、`frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`（一等引用禁令、range arity、preload 解析）、`frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`、`frontend/sema/analyzer/FrontendTypeCheckAnalyzer.java`（assert message 校验）、`frontend/sema/analyzer/FrontendLoopControlFlowAnalyzer.java`、`frontend/lowering/cfg/FrontendCfgGraphBuilder.java`、`frontend/lowering/cfg/item/SequenceItem.java`、`frontend/lowering/cfg/item/AssertItem.java`、`frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`（assert processor、`isSyntheticLoadCall` 改写、`classifyOpaqueExpression`）、`frontend/lowering/pass/body/FrontendOpaqueExprInsnLoweringProcessors.java`（preload processor）、`frontend/lowering/pass/body/FrontendCfgNodeInsnLoweringProcessors.java`（branch 侧 truthiness 调用方）、`frontend/lowering/FrontendBodyLoweringSupport.java`（`materializeTruthinessToBool`）、`frontend/lowering/pass/body/FrontendBodyLoweringSession.java`（`allocateGdScriptLanguageFunctionTemp`）
- backend：`backend/c/gen/CGenHelper.java`（`resolveUtilityCall`、`requireGdScriptLanguageFunctionCName`）、`backend/c/gen/CBodyBuilder.java`（`emitAssertGuard`）、`backend/c/gen/CCodegen.java`（注册 `AssertInsnGen`）、`backend/c/gen/insn/AssertInsnGen.java`、`backend/c/gen/insn/CallGlobalInsnGen.java`（复用，无特判）
- runtime：`src/main/c/codegen/include_451/gdcc/gdscript_builtins.h`、`src/main/c/codegen/include_451/gdcc/gdcc_helper.h`（include 接入点）

## 11. 测试基线

- LIR：`AssertInsnContractTest`（opcode/操作数/序列化/解析/result 前缀静默丢弃往返）、`SimpleLirBlockInsnParserTest`、`SimpleLirBlockInsnSerializerTest`。
- frontend：`ClassRegistryGdScriptLanguageFunctionTest`（注册表合同、查询面收口、同名冲突 fail-fast）、`FrontendGdScriptLanguageFunctionSemaTest`（解析/遮蔽/一等引用禁令/range arity/preload 字面量）、`FrontendGdScriptLanguageFunctionLoweringTest`（`call_global` 形态、Variant pack、load/preload 改写、硬边界负向断言）、`FrontendAssertLoweringTest`（truthiness 归一化、不支持位置）、`FrontendExpressionSemanticSupportTest`（preload 非 deferred 合同）、`FrontendLoweringPassManagerTest`（类级 preload property initializer 端到端）。
- backend 单测：`AssertInsnGenTest`、`CallGlobalInsnGenTest`（`gdcc_*` 路由、按名 fail-fast、`load` 端到端拒绝、硬边界）、`CConstructInsnGenTest`（一等引用禁令第二层防线）、`CallMethodInsnGenTest`（ResourceLoader singleton 调用对、缺省参数物化、OWNED 写槽与 discard release）、`CLoadStaticInsnGenTest`（回归）、`FixedGodotBindingsTest`。
- 引擎集成测试（真实 zig + Godot，环境缺失时按约定跳过）：`CallGlobalInsnGenEngineTest`（len/char/ord 以引擎为 oracle；range happy-path 以引擎 `range.callv` 为 oracle、错误路径断言空 Array 合同；`is_instance_of` 以引擎为 oracle、错误路径断言 `false`；极值溢出用例仅锚定 GDCC 合同、不用引擎 oracle——引擎自身在极值输入下有 UB）、`CallMethodInsnGenEngineTest`（`load` 以引擎全局函数为 oracle、缺失路径锚定 ResourceLoader 报错 + null 返回、discard release；成功判定用 `x is Resource`，见 §7.4）。
