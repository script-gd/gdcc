# GDScript 语言内置函数（len/char/ord/load/is_instance_of/range）与 assert/preload 实施计划

## 文档状态

- 状态：**In Progress**（阶段 A、B 已完成：LIR `assert` 指令模型 + 解析/序列化闭环；`assert` frontend + backend 闭环；阶段 C–F 尚未开始）
- 范围：frontend sema/lowering、LIR 新增 `assert` 指令、C backend 代码生成、`gdcc/**` runtime helper
- 更新时间：2026-08-29
- 目标读者：实施者、代码评审者
- 语义锚点来源：`godotengine/godot` 仓库 `modules/gdscript/gdscript_utility_functions.cpp`（4.5 分支现状）

## 1. 背景与目标

实现 GDScript 语言级内置函数 `len`、`char`、`ord`、`load`、`is_instance_of`、`range`，以及 `assert` 语句与 `preload` 表达式：

- 这六个函数是 **GDScript 语言专属 utility function**，由 Godot 在 `GDScriptUtilityFunctions::register_functions()` 中注册，**不存在于 `extension_api_451.json`**，因此无法复用现有 `godot_*` utility wrapper，必须由编译器自行注册元数据并提供 `gdcc_*` runtime helper。
- `assert` 通过**新增 LIR 指令** `assert` 实现，失败时走与 `assert_object_live` 一致的 runtime-error + default-return 路径。
- `preload` 与 `load` **共享运行时动态加载语义**（均等价于 `ResourceLoader.load(path)`），但 `preload` 要求编译期字符串字面量路径。
- **不实现**对 gdcc 脚本路径（`.gd`）的拦截与诊断：gdcc 源代码不走脚本系统，经 `load`/`preload` 加载会得到无意义的 `GDScript` 资源，属于调用方责任；本期不做任何编译期/运行时检查，后续确有需要再单独立项。

## 2. Godot 语义锚点（单一事实来源）

语义锚点以 **Godot 4.5 分支**（与 `GodotVersion.V451` 对齐）的 `gdscript_utility_functions.cpp` 为准；注意 master 分支 `char` 校验已收紧，不可混用。

| 函数 | 签名（Godot MethodInfo） | 运行时语义 | 错误行为 |
| --- | --- | --- | --- |
| `len` | `int len(Variant var)` | String/StringName 取字符数；Array/Dictionary 取 size；全部 Packed*Array 取 size | 其它类型：CallError + 错误消息 |
| `char` | `String char(int code)` | `String::chr(code)` | 4.5 仅校验 `code < 0 \|\| code > 0xFFFFFFFF`（UINT32_MAX）时报错；`code == 0` 合法（NUL 字符）；surrogate 与 `> 0x10FFFF` 由 `String::chr` 替换为 U+FFFD 并打印 unicode 错误，**不产生 CallError** |
| `ord` | `int ord(String char)` | 返回首字符的 Unicode code point | 字符串长度不为 1 时报错 |
| `range` | `Array range(...)` vararg，1..3 个 int | 物化一个元素全为 int 的**未参数化 Array**；`range(n)` 等价 `range(0, n, 1)` | `step == 0`、元素数超 `INT32_MAX`、参数非 int 时报错 |
| `load` | `Resource load(String path)` | 等价 `ResourceLoader.load(path)` | 加载失败由 ResourceLoader 自行报错并返回 null |
| `is_instance_of` | `bool is_instance_of(Variant value, Variant type)` | `type` 为 int（`TYPE_*` 常量）时比较 `value` 的 Variant 类型；`type` 为 Object 时按 GDScriptNativeClass/Script 继承链判断 | value 为 freed 对象报错；value 为 null 返回 `false` |

补充锚点：

- `assert(condition, message?)`：Godot 中 condition 采用 truthiness 契约（不要求严格 bool），message 为可选表达式；失败在 debug 构建报错并中断，release 构建被移除。**gdcc 决策（见 D7）**：始终保留检查，失败走 runtime-error + default-return。
- `preload(path)`：Godot 中为编译期资源加载并作为常量嵌入。**gdcc 决策（见 D6）**：退化为求值点的 `ResourceLoader.load(path)`，路径必须是字符串字面量。
- `range` 在 `for` 循环头中已被 gdcc 特判（`RANGE_CALL` route + `gdcc.for_range_iter.*` intrinsic），本计划**只处理独立表达式调用** `var x = range(...)`，不改变 for 路径。
- 非目标：Godot 同文件注册的其它语言 utility（`type_exists`、`print_debug`、`print_stack`、`get_stack`、deprecated 的 `convert`/`inst_to_dict`/`dict_to_inst`/`Color8`）不在本期范围；语言函数的一等值引用（`var f = len`）不支持（见 D1 禁令）；`const X = preload(...)` 依赖尚未立项的 class-constant 工作流，本期不解锁（见阶段 E）。

## 3. 现状与缺口（代码锚点）

本节描述**阶段 B 实施前**的基线快照（行号为当时位置），用于解释设计动机；当前已实现的部分以 §5 各阶段状态为准。

### 3.1 已有可复用链路

- 全局 utility 解析链路完整：`ClassRegistry.resolveFunctionsHere`（`scope/ClassRegistry.java:424`）→ `FrontendBodyOwnerProcedures.classifyFunctionBinding`（`frontend/sema/analyzer/FrontendBodyOwnerProcedures.java:1602`，全部为 `ExtensionUtilityFunction` 时分类为 `UTILITY_FUNCTION`）→ `FrontendExpressionSemanticSupport.bareCallRoute`（`frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java:1672`，映射为 receiverType=null 的 STATIC_METHOD route）→ `FrontendCfgGraphBuilder.buildBareCallValue`（`frontend/lowering/cfg/FrontendCfgGraphBuilder.java:2381`）→ `FrontendSequenceItemInsnLoweringProcessors.lowerStaticMethodCall`（`frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java:801`，receiverType==null 时生成 `CallGlobalInsn`）→ `CallGlobalInsnGen`（`backend/c/gen/insn/CallGlobalInsnGen.java`）→ `CGenHelper.resolveUtilityCall`（`backend/c/gen/CGenHelper.java:1717`，统一加 `godot_` 前缀）。
- 合成语言常量的注册先例：`ClassRegistry.registerSyntheticGdScriptLanguageConstants`（`ClassRegistry.java:151`，PI/TAU/INF/NAN），证明"编译器合成全局命名空间条目"模式已存在。
- `assert` 的 shared semantic 已就绪：condition/message 类型发布（`FrontendBodyOwnerProcedures.java:1180`）、type-check 的 condition 契约（`FrontendTypeCheckAnalyzer.java:1219`，`visitConditionExpression` 明确"Godot-compatible truthiness 是 lowering/runtime 关注点"）。
- 条件 truthiness 归一化已有唯一实现点：`FrontendCfgNodeInsnLoweringProcessors.emitConditionBranch`（`frontend/lowering/pass/body/FrontendCfgNodeInsnLoweringProcessors.java:90`）：bool 直接用；Variant 用 `UnpackVariantInsn`；其它类型先 `PackVariantInsn` 再 unpack 为 bool。（阶段 B 起已按 D5 抽取为共享 helper `FrontendBodyLoweringSupport.materializeTruthinessToBool`，branch 与 assert lowering 共用。）
- `assert_object_live` 指令全链路是新增无结果指令的模板：`lir/insn/AssertObjectLiveInsn.java`、`backend/c/gen/insn/AssertObjectLiveInsnGen.java`、`CBodyBuilder.emitAssertObjectLiveGuard`（`backend/c/gen/CBodyBuilder.java:685`，含 `GDCC_PRINT_RUNTIME_ERROR` + `returnDefault()`）。
- `for` 中 range 的归一化（缺省 start=0/step=1）已有先例：`FrontendForLoopSupport.isBareRangeCall` 与 `gdcc.for_range_iter.*` intrinsic（`doc/gdcc_lir_intrinsic.md`）。
- engine **singleton 实例方法**调用链完整：singleton 值经 `LoadStaticInsn("@GlobalScope", "<name>")` 物化（`LoadStaticInsnGen` singleton 分支，BORROWED），实例方法经 `CallMethodInsnGen` + `BackendMethodCallResolver` ENGINE 模式发射，缺省参数由 backend 按 metadata 物化。`ResourceLoader` 在 `extension_api_451.json:337351` 注册为 singleton，其 `load` 是 **实例方法**（`extension_api_451.json:247523`，`is_static=false`），因此只能走 singleton 实例调用，不能走 `call_static_method`。
- runtime 已有 `GDCC_PRINT_RUNTIME_ERROR` 宏（`src/main/c/codegen/include_451/gdcc/gdcc_helper.h:27`）、`godot_variant_booleanize` 接口绑定、`godot_String_chr`/`godot_String_length` 生成绑定（`godot/godot_builtin.h`）。
- `gdcc/**` runtime helper 以 header-only（static inline）形式存在（`gdcc_helper.h` 等），编译源清单 `CProjectBuilder.GDCC_RUNTIME_SOURCE_PATHS`（`backend/c/build/CProjectBuilder.java:27`）只列 `.c` 文件，**新增 header-only helper 不需要改构建脚本**。

### 3.2 缺口

- `len`/`char`/`ord`/`load`/`range`/`is_instance_of` 未注册，bare call 解析直接 not-found。
- `assert` 被 compile gate 显式阻断：`FrontendCompileCheckAnalyzer.handleAssertStatement`（`FrontendCompileCheckAnalyzer.java:450`）。
- `preload` 类型 deferred（`FrontendExpressionSemanticSupport.java:878`）且被 compile gate 阻断（`FrontendCompileCheckAnalyzer.java:675`）；lowering 侧为 `OpaqueExprHandling.DEFER`（`FrontendSequenceItemInsnLoweringProcessors.java:646`）。
- CFG 语句分派无 `AssertStatement` 分支（`FrontendCfgGraphBuilder.processStatement`，`FrontendCfgGraphBuilder.java:237`）。
- 无 `ResourceLoader` 的固定绑定（`godot/godot_fixed_binding.h` 目前只有 `Engine`/`ClassDB` singleton 等）；但 singleton 物化走 `LoadStaticInsn`，不需要固定绑定。
- 生成 C 的 include 图是封闭的：`template_451/entry.h.ftl:8-14` 只包含 `godot_binding.h`、`gdcc_helper.h`（协程模块另含 `gdcc_coroutine.h`）；新增 header-only helper 文件**必须**显式挂入 include 图，`GdccHelperBindingScanner` 只做 binding 用量扫描，不负责接入。

## 4. 总体设计决策

### D1：合成"GDScript 语言函数"注册表（frontend 元数据）

- 在 `ClassRegistry` 新增 `gdScriptLanguageFunctionByName: Map<String, ExtensionUtilityFunction>`，构造时注册六条合成 `ExtensionUtilityFunction` 记录（复用现有 record，不新建元数据类型）：
  - `len`：returnType `"int"`，参数 `[("var", "Variant")]`
  - `char`：returnType `"String"`，参数 `[("code", "int")]`
  - `ord`：returnType `"int"`，参数 `[("char", "String")]`
  - `range`：returnType `"Array"`，`isVararg=true`，无固定参数（与 Godot MethodInfo 一致；参数元素恒为 int 的事实写入文档，不做 `Array[int]` 参数化）
  - `is_instance_of`：returnType `"bool"`，参数 `[("value", "Variant"), ("type", "Variant")]`
  - `load`：returnType `"Resource"`，参数 `[("path", "String")]`
  - 公共字段：`category="GDScript"`、`hash=0`（gdcc helper 不使用 hash；实施时验证无消费者要求 utility hash 非零）。
- **分阶段增量注册**：为避免"已注册但 backend/helper 尚未落地"的窗口期 ICE，注册按阶段推进——阶段 C 只注册 `len`/`char`/`ord`，阶段 D 追加 `range`/`is_instance_of`，阶段 E 追加 `load` 并同一步上线 lowering 改写；每阶段的注册表、映射表、helper、测试保持同批次一致。
- 查询面收口（避免隐藏消费者漏接）：
  - **回查合成表**：`resolveFunctionsHere`（`ClassRegistry.java:424`）、`findUtilityFunctionSignature`（`:838`）、`isUtilityFunction`（`:286`，`:832` 的 object-type 猜测排除依赖它）。
  - **明确不回查**：`findUtilityFunction`（`:291`）与 `getExtensionUtilityFunctionList`（`:1105`）。前者被 `ConstructInsnGen.resolveUtilityStandaloneSpec`（`ConstructInsnGen.java:330`）用于一等函数引用 `var f = len` 的 `construct_standalone_callable`，其 C 侧 `gdcc_callable.h:162` 对 `utility_hash == 0` 直接返回 NULL——合成函数 hash=0 无法支持该路径；后者喂食 `GodotUtilityGenerator`/provided-symbols，合成条目进入会凭空生成不存在的 `godot_len` 等 wrapper。
- **一等函数引用禁令（v1）**：`var f = len` / `var g = range` 等把语言函数当值引用的写法不支持。防线分两层：frontend 在发现标识符解析为合成语言函数且处于值引用位置时给出 `sema.expression_resolution` 诊断；backend `ConstructInsnGen` 经 `findUtilityFunction` 查不到合成条目时保持 fail-fast（不得静默降级）。两条防线都要配负向测试。
- 遮蔽语义自然成立：用户同名函数在局部 scope 先于全局命中，与 Godot 一致；`for` 中 `range` 特判不受影响。

### D2：backend 路由到 `gdcc_*` runtime helper

- `CGenHelper.resolveUtilityCall` 增加分支：命中合成语言函数时，按静态映射表生成 `gdcc_*` C 函数名，不再加 `godot_` 前缀：
  - `len` → `gdcc_len`，`char` → `gdcc_char`，`ord` → `gdcc_ord`，`range` → `gdcc_range`，`is_instance_of` → `gdcc_is_instance_of_global`
  - `load` 不在映射表中，见 D6（frontend 在 lowering 期改写为 singleton 实例调用，不会以 `call_global "load"` 到达 backend）；若坏 IR 出现 `call_global "load"`，`resolveUtilityCall` 对其**显式按名 fail-fast**。
- 一致性合同按名生效：**映射表 = 合成注册表 \ {load}**；对命中合成注册表的每个名字，`resolveUtilityCall` 查映射表，无映射即 fail-fast（`load` 走显式拒绝分支并给出指向 D6 的错误消息）。**不做全表相等检查**，以兼容分阶段增量注册（见 §5 阶段 C/D/E 的增量约定）。
- `CallGlobalInsnGen` 无需改动：现有固定参数类型校验、Variant vararg 校验、default 物化、`callAssign`/`callVoid` 路径全部复用（`range` 无固定参数时全部实参走 Variant vararg tail，frontend `materializeCallArguments` 负责 pack，`validateVarargTypes` 可通过）。

### D3：runtime helper 新增 header-only 文件

- 新增 `src/main/c/codegen/include_451/gdcc/gdscript_builtins.h`（static inline，模式对齐 `gdcc_helper.h`）：
  - `godot_int gdcc_len(const godot_Variant *value)`：按 Variant 类型分派到 String/StringName length、Array/Dictionary size、各 Packed*Array size；其余类型 `GDCC_PRINT_RUNTIME_ERROR` 后返回 `0`。
  - `godot_String gdcc_char(godot_int code)`：4.5 语义——仅 `code < 0 || code > 0xFFFFFFFF` 时 `GDCC_PRINT_RUNTIME_ERROR` 并返回空 String；其余委托 `godot_String_chr`（surrogate 与 `> 0x10FFFF` 的替换行为随 `String.chr`，不做额外拦截）。
  - `godot_int gdcc_ord(const godot_String *value)`：`godot_String_length != 1` 报错返回 `0`；否则经 String codec 取首字符 code point。
  - `godot_Array gdcc_range(const godot_Variant **argv, godot_int argc)`：校验 `1 <= argc <= 3`、逐参数必须是 INT、`step != 0`、元素数 `<= INT32_MAX`；按 Godot 规则物化 Array[int 元素]；任一校验失败 `GDCC_PRINT_RUNTIME_ERROR` 并返回空 Array。
  - `godot_bool gdcc_is_instance_of_global(const godot_Variant *value, const godot_Variant *type)`：`type` 为 INT 时比较 `godot_Variant_get_type(value)`；`type` 为其它种类时 `GDCC_PRINT_RUNTIME_ERROR`（"class/script type argument 暂不支持"）并返回 `false`（见 §6 待确认项 R2）。
- 错误处理统一为"helper 内打印 runtime error + 返回类型默认值"，不回传错误给调用点；这与 gdcc 对可恢复运行时错误的现有风格一致，且避免在 `CallGlobalInsnGen` 引入新的控制流。
- **include 接入（必须）**：在 `gdcc/gdcc_helper.h` 中 `GDCC_PRINT_RUNTIME_ERROR` 宏定义**之后**（约 `:31` 之后，新 helper 依赖该宏与 `godot_*` 类型）增加 `#include <gdscript_builtins.h>`，使 `entry.h.ftl` 经 `gdcc_helper.h` 传递包含；**禁止**放在文件顶部 include 块（宏尚未定义会导致编译失败），`gdscript_builtins.h` 自身不得回引 `gdcc_helper.h`（避免循环包含）。`gdcc_assert_failed` 同样放入 `gdscript_builtins.h`，保持单一接入点。
- `GdccHelperBindingScanner` 递归扫描 `gdcc/**` 的 `.h/.c/.ftl` 收集 `godot_*` 用量，新文件会被自动覆盖；相关回归：`FixedGodotBindingsTest`、`GodotBindingTool` 的 check-fixed 等价校验。

### D4：`assert` 新增 LIR 指令

- 文本形态：
  ```text
  assert $cond;
  assert $cond $msg;
  ```
- `GdInstruction` 新增：`ASSERT("assert", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.VARIABLE), 1, 2)`，归入 Misc 分组。
- 新增 `lir/insn/AssertInsn.java`（record：`conditionId`、`@Nullable messageId`），实现 `MiscInstruction`；**不**携带 lifecycle provenance（非生命周期指令）。
- `ParsedLirInstruction.toConcrete()` 增加 `ASSERT` 分支（该 switch 是 exhaustive 的，不加会编译失败）；通用 parser/serializer 无需改动。
- LIR 合同：condition 槽位**必须已是 bool**（truthiness 归一化是 frontend lowering 职责，见 D5）；message 槽位若存在必须是 `String` 可赋值类型。backend 对违规 fail-fast。
- `ReturnKind.NONE` 不会被通用 parser 强制（`SimpleLirBlockInsnParser` 不看 returnKind，`$r = assert $c;` 在文本层可解析）。正式合同是 **静默丢弃**：`toConcrete()` 不读取解析到的 result 前缀，`AssertInsn.resultId()` 恒为 `null`，backend **不** 对 result 前缀 fail-fast。与 `assert_object_live` 现状一致。contract 测试覆盖「带 `$r =` 前缀仍解析为无结果指令」。

### D5：`assert` frontend 解锁与 lowering

- compile gate：`handleAssertStatement` 改为 `markCompileSurfaceNode` + `walkExpression(condition)` + 可选 `walkExpression(message)`，删除阻断。
- CFG：`processStatement` 新增 `case AssertStatement`：用 `buildValue` 物化 condition 与可选 message，向当前 sequence 追加新的 `AssertItem`（`frontend/lowering/cfg/item/AssertItem.java`，承载 conditionValueId、messageValueIdOrNull、源节点）。
- **sealed 类型接线**：`SequenceItem` 是 sealed interface（`frontend/lowering/cfg/item/SequenceItem.java:12`，当前仅 `permits SourceAnchorItem, ValueOpItem`），必须为 `AssertItem` 扩展 `permits`；`AssertItem` 直接实现 `SequenceItem`，**不得**实现 `ValueOpItem`（无结果值，避免撞上 `requireProducedValueMaterialization` 的 exhaustive 分派）；processor 在 `FrontendInsnLoweringProcessorRegistry`（精确类型匹配、无父类回退）中注册。
- body lowering：新增 `AssertItem` 的 lowering processor：
  - 将 `emitConditionBranch` 中的 truthiness 归一化逻辑抽取为共享 helper（`frontend/lowering/FrontendBodyLoweringSupport.java`，如 `materializeTruthinessToBool(session, block, valueId, type) -> boolSlotId`）；helper **只返回 bool slot、不得调用 `setTerminator`**，branch processor 调用后再自行 `GoIfInsn`，assert processor 调用后发射 `AssertInsn`；禁止复制实现。
  - `block.appendNonTerminatorInstruction(new AssertInsn(boolSlotId, messageSlotIdOrNull))`。
- 配套扫描修正：`FrontendLoopControlFlowAnalyzer.handleAssertStatement`（`FrontendLoopControlFlowAnalyzer.java:200-203`）当前只扫 `condition()` 的嵌套 callable 边界，解锁后必须对 `message()` 同样 `scanNestedCallableBoundaries`，与 `FrontendTypeCheckAnalyzer.java:1225-1229` 对齐。
- message 契约 v1：必须为可赋值到 `String` 的类型（type-check 阶段补充校验）；Variant message 留作后续扩展（见 §6 R3）。

### D6：`load`/`preload` 统一改写为 ResourceLoader singleton 实例调用

- 关键事实：`ResourceLoader.load` 在 `extension_api_451.json:247523` 是**实例方法**（`is_static=false`），`ScopeMethodResolver.resolveStaticMethod` 只保留 `isStatic()` 候选，`call_static_method` 路线在 backend 必然 fail-fast；但 `ResourceLoader` 是注册 singleton（`extension_api_451.json:337351`），已有 singleton 物化链路可直接复用。
- 因此不新增 `gdcc_*` C helper，frontend 在 lowering 时把已解析的合成 `load` 调用改写为两条已有指令：
  ```text
  $rl_tmp = load_static "@GlobalScope" "ResourceLoader";
  $res    = call_method "load" $rl_tmp $path;
  ```
  从而完整复用：`LoadStaticInsnGen` singleton BORROWED 物化、`CallMethodInsnGen` + `BackendMethodCallResolver` ENGINE 实例调用、缺省参数 `type_hint=""`/`cache_mode=1` 的 backend 物化、RefCounted 返回值的 OWNED 所有权处理。
- 改写锚点：`FrontendSequenceItemInsnLoweringProcessors.lowerStaticMethodCall` 中检测 resolved function 是合成语言函数 `load`（`ClassRegistry.isGdScriptLanguageFunction` + 名称判定）时改发上述指令对；singleton 接收者临时槽位经 `session.ensureVariable(...)` 分配，类型 `GdObjectType("ResourceLoader")`；`call_method` 的 resultId 透传原 call 的 resultId（无结果调用同样合法——两条指令的 ReturnKind 都支持）。
- 实施时先验证：`CallMethodInsnGen` 对 engine 实例方法缺省参数的物化已有测试覆盖；若无，先补该测试再接入改写。
- `preload` sema：在 `FrontendExpressionSemanticSupport` 为 `PreloadExpression` 新增专用解析（替换 deferred 分支）：
  - 路径参数必须是字符串字面量，否则诊断（`sema.expression_resolution`）；
  - 路径**原样透传**给 `ResourceLoader.load`，编译期不做相对路径归一化（与 Godot `preload` 的相对路径解析是有意差异，与"共享运行时动态加载语义"一致；运行时由 ResourceLoader 自行处理/报错）；
  - 成功后发布类型 `RESOLVED(Resource)`（`GdObjectType("Resource")`）。
- 不实现 gdcc 脚本路径拦截（见 §1）：无需注入模块源路径集合，无新增路径归一化 util。
- `preload` compile gate：删除阻断分支，改走默认 `walkExpression` 路径。
- `preload` lowering 走 **`OpaqueExprValueItem` 专用处理器**路线，不新增 LIR 指令、不新增 SequenceItem 类型、**不扩大 `resolvedCalls` key 空间**：
  - 决策理由：`resolvedCalls` 的 key 空间被多处冻结为 `CallExpression`/`AttributeCallStep`（`FrontendBodyOwnerProcedures` 的 call 缓存以 `CallExpression` 为键、`FrontendCompileCheckAnalyzer.requireCallAnchor/describeCallAnchor` 只认两类节点、三份 frontend 合同文档已写明），把 `PreloadExpression` 塞进去需要同步改动发布缓存、compile gate 与文档合同，属于不必要的架构扩张；专用 opaque 处理器自包含。
  - `FrontendSequenceItemInsnLoweringProcessors` 的 `classifyOpaqueExpression`：`PreloadExpression` 从 `DEFER` 改为 `HANDLE_NOW`。
  - CFG `buildValue` 新增 `PreloadExpression` 分支：与 `IdentifierExpression` 同形，产生 `OpaqueExprValueItem`（携带结果 value id）。
  - `FrontendOpaqueExprInsnLoweringProcessors` 新增 `FrontendPreloadOpaqueExprInsnLoweringProcessor`（按表达式类型注册）：从 `PreloadExpression` 取已校验的路径字面量，物化 `literal_string` 槽位，分配 `GdObjectType("ResourceLoader")` 临时槽位，连续发射 `LoadStaticInsn("@GlobalScope", "ResourceLoader")` 与 `CallMethodInsn("load", rlTmp, [pathSlot], resultSlot)`；结果槽位类型为 `GdObjectType("Resource")`。
- preload sema 只需发布 `expressionTypes = RESOLVED(Resource)` 与字面量校验结果，**不发布** `FrontendResolvedCall`。
- `load(...)` 除 D1 注册与 D6 改写外无其它前端特殊处理（参数路径任意表达式，无编译期检查）。

### D7：`assert` 失败语义

- 始终保留检查（gdcc 暂无 debug/release 构建区分）；失败时：
  ```c
  if (!<cond>) {
      gdcc_assert_failed(<msg_or_NULL>, __func__, __FILE__, __LINE__);
      <returnDefault() 发射>
  }
  ```
- 新增 runtime helper `gdcc_assert_failed(const godot_String *message_or_null, const char *func, const char *file, int line)`（固定放入 `gdscript_builtins.h`，见 D3 接入点），内部复用 `GDCC_PRINT_RUNTIME_ERROR` 的打印通道。
- backend 入口：`CBodyBuilder.emitAssertGuard(conditionVar, messageVarOrNull)`（镜像 `emitAssertObjectLiveGuard`，含 `__finally__` 禁用校验），新增 `AssertInsnGen` 只做 IR 校验并委托。
- 与 Godot 的差异（release 移除、debugger 中断）记入文档非目标。

### D8：`is_instance_of` 与 `range` 的取舍

- `is_instance_of` v1 **仅支持类型枚举数字**（`type` 为 `TYPE_*` int 常量，helper 内 `type` Variant 必须是 INT，比较 `value` 的 Variant 类型）；class/script 等其它形式运行时报错（见 D3），不在 compile gate 阻断（type 参数是 Variant，编译期无法判定）。
- **硬边界**：全局 `is_instance_of()` 是 `call_global`，与 `x is T` 表达式的 `IsInstanceOfInsn`（`is_instance_of "<type_name>" $value`）是两套独立合同，禁止互相 lower 或复用 `IsInstanceOfInsnGen`/`gdcc_is_instance_of_object_*` helper；全局函数专用 helper 命名为 `gdcc_is_instance_of_global` 以示区隔。补负向测试：`is_instance_of(x, TYPE_INT)` 生成的 C 不含 `gdcc_is_instance_of_object_`。
- `range` 独立调用返回未参数化 `Array`（与 Godot MethodInfo 对齐）；for 循环路径不变。
- `range` arity 前端兜底：合成记录为 vararg 且无固定参数，`matchesCallableArguments` 对 vararg 直接放行，`range()` 零参会漏到运行时。在 `resolveBareIdentifierCallWithLiteralContext` 选中合成语言函数后增加 arity 检查（`range` 限定 1..3），诊断类别 `sema.expression_resolution`；4 个及以上参数同理拦截。

### D9：命名与冲突防御

- 所有新 C 符号使用 `gdcc_` 前缀，禁止命名为 `godot_*`（`char` 同时是 C 关键字，更不允许裸名）。
- 合成函数 `hash=0`：实施第一步先验证 `ExtensionUtilityFunction.hash` 无消费者（`godot_utility` wrapper 生成只消费 JSON 条目）；若有，改为在合成记录中存放稳定伪 hash 并注明。

## 5. 分阶段实施计划与验收细则

每阶段完成后运行对应定向测试；除阶段 F 外不强制全量 build。定向测试统一使用：

```powershell
pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests <TestClassA,TestClassB>
```

### 阶段 A：LIR `assert` 指令（纯 LIR 层）

状态：**已完成**（2026-08-29）

目标：指令模型 + 枚举 + 解析/序列化闭环，不触碰 frontend/backend。

修改文件：

1. `src/main/java/gd/script/gdcc/enums/GdInstruction.java`：新增 `ASSERT`（见 D4）。
2. `src/main/java/gd/script/gdcc/lir/insn/AssertInsn.java`：新建 record（参照 `AssertObjectLiveInsn`）。
3. `src/main/java/gd/script/gdcc/lir/parser/ParsedLirInstruction.java`：`toConcrete()` 增加 `ASSERT` 分支。
4. `doc/gdcc_low_ir.md`：Misc Instructions 节补充 `assert` 语法与合同（condition 必须 bool、message 可选 String、无结果、无 provenance）。

验收细则：

- 新增 `src/test/java/gd/script/gdcc/lir/insn/AssertInsnContractTest.java`（参照 `AwaitInsnContractTest`）：opcode/操作数结构/分类接口/序列化/解析/`checkEquals` 往返/message 缺省与双操作数两种形态。
- `SimpleLirBlockInsnParserTest`/`SimpleLirBlockInsnSerializerTest` 补充 `assert` 用例：正常解析、操作数缺失（0 个）、操作数超量（3 个）、非变量操作数。
- 回归：`LifecycleInstructionProvenanceParserTest`、`DomLirParserTest`、`DomLirSerializerTest`、`LirBasicBlockTest`。
- 命令：`pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests AssertInsnContractTest,SimpleLirBlockInsnParserTest,SimpleLirBlockInsnSerializerTest,LifecycleInstructionProvenanceParserTest,DomLirParserTest,DomLirSerializerTest,LirBasicBlockTest`
- 完成判定：以上全绿；`gradlew classes` 编译通过（证明 exhaustive switch 已闭合）。

### 阶段 B：`assert` frontend + backend 闭环

状态：**已完成**（2026-08-29）

目标：`assert(cond)` / `assert(cond, "msg")` 可编译到 C。

修改文件：

1. `frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`：解锁 assert（见 D5）。
2. `frontend/lowering/cfg/item/AssertItem.java`：新建（直接实现 `SequenceItem`，不实现 `ValueOpItem`）。
3. `frontend/lowering/cfg/item/SequenceItem.java`：`permits` 增加 `AssertItem`。
4. `frontend/lowering/cfg/FrontendCfgGraphBuilder.java`：`processStatement` 增加 assert 分支。
5. `frontend/lowering/FrontendBodyLoweringSupport.java`：抽取 truthiness 归一化共享 helper（只返回 bool slot）。
6. `frontend/lowering/pass/body/FrontendCfgNodeInsnLoweringProcessors.java`：branch processor 改调共享 helper（行为不变重构）。
7. `frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`：新增 AssertItem processor。
8. `frontend/lowering/pass/body/FrontendInsnLoweringProcessorRegistry.java`（或实际承担注册职责的类）：注册 AssertItem processor。
9. `frontend/sema/analyzer/FrontendLoopControlFlowAnalyzer.java`：对 `message()` 补 `scanNestedCallableBoundaries`。
10. `backend/c/gen/insn/AssertInsnGen.java`：新建（IR 校验：condition 存在且 bool、message 存在且 String 可赋值、非 `__finally__`。不校验 resultId：文本 `$r = assert ...` 已在 `toConcrete()` 静默丢弃，见 D4 / R4）。
11. `backend/c/gen/CBodyBuilder.java`：新增 `emitAssertGuard(...)`。
12. `backend/c/gen/CCodegen.java`：注册 `AssertInsnGen`。
13. `src/main/c/codegen/include_451/gdcc/gdscript_builtins.h`：新建，先含 `gdcc_assert_failed`。
14. `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`：在 `GDCC_PRINT_RUNTIME_ERROR` 宏定义**之后**增加 `#include <gdscript_builtins.h>`（禁止放顶部 include 块、禁止新头回引本文件，见 D3）。
15. type-check：message 必须为 String 可赋值的校验（`FrontendTypeCheckAnalyzer.handleAssertStatement` 补充，诊断类别 `sema.type_check`）。

验收细则：

- 前端测试：`assert(true)`、`assert(x)`（Variant 条件）、`assert(i)`（int 条件，验证 pack/unpack 归一化）、`assert(false, "m")` 均不再产生 compile-block 诊断且 LIR 中出现合法 `assert` 指令；`assert(1, 123)` 产生 message 类型诊断。
- 负向回归：assert 出现在 property initializer 等不支持位置仍被既有 gate 拦截。
- 后端测试：新增 `AssertInsnGenTest`：无 message/有 message 的 C 文本断言（含 `gdcc_assert_failed` 与 default return）；condition 非 bool、message 非 String、`__finally__` 中出现三类 fail-fast。不测携带 resultId（已在 LIR `toConcrete()` 静默丢弃，见 D4 / R4）。
- 行为不变验证：`buildCondition` 相关既有测试全绿（truthiness 抽取为纯重构）。
- 既有阻断断言翻新（解锁副作用，必须同步更新期望）：`FrontendCompileCheckAnalyzerTest`（assert 不再计入 compile-block，GetNode/preload 仍阻断）、`FrontendSemanticAnalyzerFrameworkTest`、`FrontendLoweringPassManagerTest`、`FrontendLoweringAnalysisPassTest`。实施时另发现两个以 assert 为阻断探针的既有用例并一并改用 preload 探针：`FrontendLoweringFunctionPreparationPassTest`（2 处）、`ApiCompileTaskFailureStageTest`（LOWERING 阶段失败锚点）；`FrontendTypeCheckAnalyzerTest.analyzeWalksRecordedLambdaBodiesWithInheritedCallableContext` 的 lambda-in-message fixture 命中新 message 校验，期望由 3 条更新为 4 条并显式锚定新诊断。
- 命令：`pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests AssertInsnGenTest,FrontendAssertLoweringTest,CBodyBuilderPhaseCTest,FrontendCompileCheckAnalyzerTest,FrontendSemanticAnalyzerFrameworkTest,FrontendLoweringPassManagerTest,FrontendLoweringAnalysisPassTest`（实施时追加 `FrontendLoweringFunctionPreparationPassTest,ApiCompileTaskFailureStageTest,FrontendLoweringBodyInsnPassTest,AssertObjectLiveInsnGenTest,FixedGodotBindingsTest,FrontendTypeCheckAnalyzerTest,FrontendAnalysisInspectionToolTest`，544 个测试全绿）
- 完成判定：以上全绿；抽查生成 C 文本中 assert 失败路径含 runtime error 与 default return。

### 阶段 C：合成语言函数注册 + len/char/ord

目标：三个纯函数端到端可用。

修改文件：

1. `scope/ClassRegistry.java`：合成注册表 + 查询 API + 按 D1 查询面收口（`resolveFunctionsHere`/`findUtilityFunctionSignature`/`isUtilityFunction` 回查；`findUtilityFunction`/`getExtensionUtilityFunctionList` 不回查）；本阶段只注册 `len`/`char`/`ord` 三条。
2. `backend/c/gen/CGenHelper.java`：`resolveUtilityCall` 增加 `gdcc_*` 映射分支（见 D2，含按名 fail-fast）。
3. `frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`：一等函数引用禁令——`resolveIdentifierExpressionType` 的 `UTILITY_FUNCTION` 分支对合成语言函数在值引用位置发 `sema.expression_resolution` 诊断（D1 第一层防线；现有 `UTILITY_FUNCTION` 标识符会被发布为 Callable，必须拦截）。
4. `src/main/c/codegen/include_451/gdcc/gdscript_builtins.h`：新增 `gdcc_len`/`gdcc_char`/`gdcc_ord`（include 接入已在阶段 B 完成）。
5. `doc/gdcc_runtime_lib.md`：登记新 helper 文件与函数清单。

验收细则：

- 注册表测试：本阶段三条合成签名逐一断言（名称/参数类型/返回类型/vararg 标记）；与 `utilityByName` 无键冲突；`findUtilityFunction`/`getExtensionUtilityFunctionList` 不含合成条目。
- backend 一致性测试按 D2 **按名合同**编写：命中合成名但映射表无条目 → fail-fast（阶段 E 再补 `call_global "load"` 显式拒绝用例）；**禁止**编写映射表与注册表全表相等的断言（分阶段增量注册下必然误伤）。
- 前端解析测试：`len("abc")`→int、`char(65)`→String、`ord("A")`→int；参数类型错误（如 `char("s")`）产生诊断；用户自定义同名函数遮蔽生效。
- 后端测试（`CallGlobalInsnGenTest` 扩展或新增）：生成调用名为 `gdcc_len`/`gdcc_char`/`gdcc_ord` 而非 `godot_*`；String 实参经 Variant pack 传入 `gdcc_len`；未知语言函数名 fail-fast；命中合成名但该名无映射条目 fail-fast（按名合同，同 :259）。
- 一等函数引用禁令：`var f = len` 产生明确诊断或 backend fail-fast（按 D1 双层防线各一条负向测试）；`GodotUtilityGenerator`/provided-symbols 测试证明合成函数未生成 `godot_*` wrapper。
- 命令：`pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CallGlobalInsnGenTest,<新增注册表测试>,<新增前端测试>,FixedGodotBindingsTest`
- 完成判定：全绿；`GdccHelperBindingScanner` 相关测试/校验通过（新 helper 被扫描且不破坏 provided-symbols 合同）。

### 阶段 D：range（独立调用）+ is_instance_of

修改文件：

1. `scope/ClassRegistry.java`：增量注册 `range`/`is_instance_of`（D1 分阶段约定）。
2. `src/main/c/codegen/include_451/gdcc/gdscript_builtins.h`：新增 `gdcc_range`、`gdcc_is_instance_of_global`；`backend/c/gen/CGenHelper.java` 映射表同步追加两项。
3. `frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`：`resolveBareIdentifierCallWithLiteralContext` 选中合成语言函数后的 `range` arity（1..3）诊断（D8）。

验收细则：

- 前端：`range(3)`/`range(1,5)`/`range(1,10,2)` 解析为 vararg 调用、返回 Array；`range()` 与 `range(1,2,3,4)` 产生 `sema.expression_resolution` arity 诊断（D8）；`for i in range(3)` 仍走 `RANGE_CALL` route。
- 后端：`call_global "range"` 生成 `gdcc_range(argv, argc)`；int 实参经 Variant pack；非 Variant 可赋值 vararg 的既有 fail-fast 不变。
- `is_instance_of(x, TYPE_INT)`：解析为 `gdcc_is_instance_of_global` 调用，返回 bool；`TYPE_INT` 作为全局枚举 bare value 的既有物化路径（`LiteralIntInsn`）回归；负向断言生成 C 中**不含** `gdcc_is_instance_of_object_`（D8 硬边界）。
- 命令：`pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CallGlobalInsnGenTest,<range/is_instance_of 新测试>,FrontendCfgGraphBuilderForLoopTest,FrontendForLoopSupportTest,GdccForRangeIterIntrinsicTest`
- 完成判定：全绿。

### 阶段 E：load + preload

目标：`load(path)` 与 `preload("字面量")` 均 lower 为 `load_static "@GlobalScope" "ResourceLoader"` + `call_method "load"` 指令对（见 D6）。

修改文件：

1. `scope/ClassRegistry.java`：增量注册 `load`（不进 `CGenHelper` 映射表）。
2. `frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`：新增 preload 专用解析（字面量校验、路径原样透传、发布 `RESOLVED(Resource)` 类型；**不发布** `FrontendResolvedCall`）。
3. `frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`：删除 preload 阻断。
4. `frontend/lowering/cfg/FrontendCfgGraphBuilder.java`：`buildValue` 增加 `PreloadExpression` 分支，与 `IdentifierExpression` 同形产生 `OpaqueExprValueItem`。
5. `frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`：`lowerStaticMethodCall` 增加合成 `load` → `load_static`+`call_method` 改写；`classifyOpaqueExpression` 中 preload 从 `DEFER` 改为 `HANDLE_NOW`。
6. `frontend/lowering/pass/body/FrontendOpaqueExprInsnLoweringProcessors.java`：新增 `FrontendPreloadOpaqueExprInsnLoweringProcessor`，发射 `LoadStaticInsn("@GlobalScope", "ResourceLoader")` + `CallMethodInsn("load", ...)` 指令对（见 D6）。
7. 视验证结果：`backend/c/gen/insn/CallMethodInsnGen.java` 仅在 engine 实例方法缺省参数物化存在缺口时修补（已有 `CallMethodInsnGenTest` 的 `Node.add_child` 缺省物化用例，预期无需改动）。

验收细则：

- sema 测试：
  - `preload("res://icon.svg")` 类型为 Resource；
  - `preload(someVar)` / `preload("a" + "b")` 诊断（非字面量路径）。
- 类级 preload 用例：
  - 类级 `var x = preload("res://icon.svg")` 走 supported property initializer 路径端到端通过（本期主用例）。
  - `const ICON = preload(...)` **非本期目标**：class constant 的收集/注册/binding 不在 MVP 范围（`frontend_rules.md` 明示），`FrontendPropertyInitializerSupport` 仅放行 `DeclarationKind.VAR`；该形态维持既有阻断行为并保留负向测试。若未来需要，单独立项解锁 class-constant 工作流。
  - `FrontendCompileCheckAnalyzerTest` 中既有 preload blocker 用例（`:110-114` 附近）期望更新：函数体内 preload 解除阻断、GetNode 保持阻断、类级 const preload 维持现状。
- lowering 测试：`load`（bare call 改写）与 `preload`（opaque 处理器）的 LIR 均为 `load_static "@GlobalScope" "ResourceLoader"` + `call_method "load"` 指令对，无 `call_global "load"` 残留；坏 IR `call_global "load"` 在 backend fail-fast；preload 不触碰 `resolvedCalls`（key 空间保持 `CallExpression`/`AttributeCallStep`），compile gate 对 preload 节点无 call-anchor 校验失败。
- 后端测试：生成的 C 经 singleton 接收者 + ENGINE 实例 dispatch 调用 `load`，缺省参数 `type_hint`/`cache_mode` 物化出现；返回 Resource 写入目标槽位（OWNED 消费路径）与 discard 路径各有断言。
- 回归：`CLoadStaticInsnGenTest`（确认 `load_static` 语义未被混淆）、`ApiCompileDiagnosticsTest`、`ApiCompileTaskFailureStageTest`、既有 engine 实例方法缺省参数测试。
- 命令：`pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests <preload/load 新测试类>,CLoadStaticInsnGenTest,FrontendCompileCheckAnalyzerTest,ApiCompileDiagnosticsTest,ApiCompileTaskFailureStageTest`
- 完成判定：全绿。

### 阶段 F：文档同步与全量验证

1. 文档更新：
   - `doc/gdcc_low_ir.md`（`assert` 指令；`call_global` 覆盖 GDScript 语言函数的说明）
   - `doc/gdcc_runtime_lib.md`（`gdscript_builtins.h` 全量函数与错误语义）
   - `doc/module_impl/frontend/frontend_rules.md`（assert/preload 解除 intercept 的事实更新；`OpaqueExprValueItem` 承载面扩为"leaf / eager unary·binary / 已 HANDLE_NOW 的 PreloadExpression"；明确类级 `const preload` 与 `GetNodeExpression` 仍拦截）
   - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`（`PreloadExpression` 从 remaining unsupported 移除的事实更新）
   - 本文件收敛为"当前事实 + 长期合同"（实施流水账移除）。
2. 全量验证：`./gradlew clean build --no-daemon --info --console=plain`。
3. 环境允许时运行依赖 zig/Godot 的集成测试（不存在则按约定跳过）。

## 6. 待确认项（不阻塞本计划启动，实施到对应阶段前需拍板）

- **R2**：`is_instance_of` 的 class/script 形式依赖"类作为一等值"（GDScriptNativeClass 等价物），当前类型系统无此概念，v1 仅支持类型枚举数字（`TYPE_*` int 常量），其它形式运行时报错拒绝；如需支持须先设计 class-value 表示，属较大架构议题。
- **R3**：`assert` message v1 限定 String 可赋值；Godot 允许任意表达式（按 Variant 字符串化）。如需放宽，后续把 message 槽位改为 Variant 并在 helper 内字符串化。
（已关闭项：gdcc 脚本路径（`.gd`）拦截/诊断经决策**不实现**，见 §1；如需恢复，单独立项。**R4**：`$r = assert $c;` 的 result 前缀定为静默丢弃——`toConcrete()` 丢弃、`AssertInsn.resultId()` 恒 `null`、阶段 B 不测/不 fail-fast resultId，与 `assert_object_live` 一致。）

## 7. 风险与防御策略

- **元数据漂移**：合成注册表（frontend）与 `gdcc_*` 映射表（backend）分离，靠 `resolveUtilityCall` 的双向 fail-fast 与注册表单测兜底。
- **`range` 与 for 路径串扰**：for 特判发生在 header 解析期，早于普通 bare-call 解析；阶段 D 必须回归全部 for-range 测试。
- **engine 实例方法缺省参数**：`ResourceLoader.load` 依赖 backend 对 engine 实例方法 default 的物化（`CallMethodInsnGen.validateFixedArgsAndCompleteDefaults`，已有 `Node.add_child` 等测试覆盖）；阶段 E 第一步仍先确认该路径测试存在。
- **ownership**：`gdcc_range` 返回 destroyable Array（OWNED，discard 必须 destroy）；`ResourceLoader.load` 返回 RefCounted Resource（OWNED，discard 必须 release）。两条 discard 路径都要有后端测试断言。
- **防御性校验**：所有新 `CInsnGen` 先完整校验再发射，错误一律 `InvalidInsnException`；helper 内不做静默降级。
- **编码与换行**：新增 C 头文件保持 UTF-8 无 BOM、与既有 runtime 文件一致的行尾。

## 8. 关键实现文件索引

- LIR：`enums/GdInstruction.java`、`lir/insn/AssertInsn.java`（新）、`lir/parser/ParsedLirInstruction.java`
- frontend：`scope/ClassRegistry.java`、`frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`、`frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`、`frontend/sema/analyzer/FrontendTypeCheckAnalyzer.java`、`frontend/sema/analyzer/FrontendLoopControlFlowAnalyzer.java`、`frontend/lowering/cfg/FrontendCfgGraphBuilder.java`、`frontend/lowering/cfg/item/SequenceItem.java`、`frontend/lowering/cfg/item/AssertItem.java`（新）、`frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`、`frontend/lowering/pass/body/FrontendCfgNodeInsnLoweringProcessors.java`、`frontend/lowering/pass/body/FrontendOpaqueExprInsnLoweringProcessors.java`、`frontend/lowering/FrontendBodyLoweringSupport.java`
- backend：`backend/c/gen/CGenHelper.java`、`backend/c/gen/CBodyBuilder.java`、`backend/c/gen/CCodegen.java`、`backend/c/gen/insn/AssertInsnGen.java`（新）、`backend/c/gen/insn/CallGlobalInsnGen.java`（复用，不改）
- runtime：`src/main/c/codegen/include_451/gdcc/gdscript_builtins.h`（新）、`src/main/c/codegen/include_451/gdcc/gdcc_helper.h`（复用宏）
- 测试：`src/test/java/gd/script/gdcc/lir/insn/AssertInsnContractTest.java`（新）、`SimpleLirBlockInsnParserTest`、`SimpleLirBlockInsnSerializerTest`、`AssertInsnGenTest`（新）、`CallGlobalInsnGenTest`、`CLoadStaticInsnGenTest`、`FixedGodotBindingsTest` 及新增 frontend sema/lowering 测试类
