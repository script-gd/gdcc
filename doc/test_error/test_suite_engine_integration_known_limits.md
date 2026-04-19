# Test Suite Engine Integration 已知边界

## 背景

本轮为 `doc/test_suite.md` 驱动的真引擎端到端测试补充以下主题覆盖：

- 常见数组与字典操作
- 常见算法：斐波那契、BFS / DFS、数组求和、字符串处理
- inner class 运行时行为
- Node / RefCounted 派生类在场景中的协作

在设计这些正向样例时，确认了两条当前仍然成立、且会直接影响测试写法的边界。

## 1. `for` / `match` / `lambda` 仍不属于 frontend body semantic MVP

事实来源：

- `doc/module_impl/frontend/frontend_rules.md`
  - 当前明确写明 `lambda`、`match`、`for` 不在 frontend body semantic MVP 正式支持面
  - 相关子树仍按 deferred / unsupported boundary fail-closed

对 test suite 的直接影响：

- BFS / DFS、字符串扫描、数组求和等算法样例不能使用更自然的 `for item in items`
- 新增图遍历与字符串处理用例统一改写为：
  - `while` 循环
  - 显式 index / queue cursor
  - 递归 helper

当前处理结论：

- 这是 frontend 已知支持面边界，不是本轮新增测试暴露出的新回归
- 因此本轮不把这些 case 写成 failing resource test，而是将正向样例约束在当前正式支持面内

## 3. `Array` / `Dictionary` literal 在 compile mode 仍被 frontend compile-check 阻断

本轮第一次 targeted run 直接暴露出以下事实：

- `var sequence: Array = [0, 1]`
- `var scores: Dictionary = {}`
- `graph["A"] = ["B", "C"]`

在 `test_suite` 这条 compile/link/run 链路里都会先停在 compile surface，典型报错为：

- `Array literal is recognized by the frontend but is temporarily blocked in compile mode until lowering support lands`
- `Dictionary literal is recognized by the frontend but is temporarily blocked in compile mode until lowering support lands`

对 test suite 的直接影响：

- 新增数组/图算法用例不能直接用数组字面量初始化 seed 数据
- 新增字典/图算法用例不能直接用字典字面量搭图

当前处理结论：

- 正向资源脚本统一改写为 `Array()` / `Dictionary()` 无参构造，再用 `push_back(...)` 和 subscript 逐步填充
- 这是一条当前 compile surface 的真实边界，后续若 frontend 为 literal lowering 开口，应补专门 regression case

## 4. plain `Dictionary` keyed subscript 当前要求显式 `Variant` key slot

本轮第一次 targeted run 还暴露出 plain `Dictionary` 的 keyed route 现状：

- 直接写 `scores["alpha"] = 2`
- 直接读 `scores["alpha"]`

当前会在 compile surface 失败，错误形如：

- `subscript assignment target key/index type 'String' is not assignable to expected 'Variant' for receiver 'Dictionary'`

对 test suite 的直接影响：

- 不能把 plain `Dictionary` 的常见字符串 key 用法原样写进正向 compile-run 资源脚本

当前处理结论：

- 正向资源脚本改为显式 `var alpha_key: Variant = "alpha"` 后再做 `scores[alpha_key]`
- 这是当前实现的可工作 surface，不代表 Godot 原生最自然写法已经完全闭环

## 5. GDScript 可执行 body 中的 `CommentStatement` 目前会让 CFG builder fail-fast

本轮第一次把解释性注释放进 graph traversal 资源脚本的函数体后，lowering 直接在 CFG 阶段中止：

- `Frontend CFG builder reached an unsupported reachable statement: CommentStatement`

对 test suite 的直接影响：

- resource script 里的复杂说明性注释不能直接放在可执行语句块中
- 同一份说明应迁移到：
  - Java wrapper 注释
  - `doc/test_error` / module 文档
  - 或 GDScript 函数体外的更安全位置

当前处理结论：

- 本轮所有会进入 compile-run 的 resource script 都移除了可执行 body 内注释

## 6. 当前 string surface 仍存在实现偏差，不适合作为正向 compile-run 回归锚点

本轮尝试为 `algorithm/string_processing.gd` 增加字符串拼接、切片与比较覆盖时，观察到两类异常：

- `substr(...)` 结果出现明显偏移，返回值与 Godot 0-based 直觉不一致
- 字符串拼接结果出现带引号片段，例如：
  - `"gdcc""-engine-""suite""|bad"`

后续即便把用例收缩到更简单的字符串比较/参数分类，这条 string resource case 仍未稳定通过。

当前处理结论：

- 本轮不把 string case 继续保留在正向 `test_suite` resource set 中
- 相关异常作为已确认错误记录在此，后续若修复 string runtime / lowering surface，应再补独立的 compile-run regression case

## 7. inner GDCC `Node` / `RefCounted` 子类与 engine scene API 的 compile surface 尚未闭环

本轮尝试增加 `scene/nested_node_refcounted_scene.gd`，目标是让 inner `SceneChild extends Node` 真正挂进场景树，并让 inner `SceneWorker extends RefCounted` 参与运行时协作。实测暴露出：

- inner `SceneChild` 目前不能赋给 `Node` typed slot
- `add_child(...)` / `get_node_or_null(...)` 这类 engine scene API 在这条 inner-class surface 上无法稳定解析
- 即便退回 `call(...)` 形式，当前 compile surface 也未闭环

这说明：

- inner class 身份模型本身已存在
- 但 inner GDCC class 与 engine `Node` API 的 assignability / callable route 仍未打通到可用于真场景 resource test 的程度

当前处理结论：

- 本轮不把 `scene/nested_node_refcounted_scene.gd` 保留在正向 `test_suite` resource set 中
- 正向 suite 改为补 `runtime/engine_node_refcounted_workflow.gd`，先锚定真实 engine `Node.new()` / `RefCounted.new()` runtime 行为
- inner GDCC subclass scene interop 后续需要单独修复后再补回 compile-run regression

## 8. exact engine method default route 已从“已知缺口”转为正向 resource 回归锚点

这条问题最初需要先区分两条不同链路，不能再笼统写成“带参数的 engine `Node` method call 都会在 lowering 中空指针”。

### 8.1 plain `var` receiver 不会直接命中这条 lowering 崩溃链

例如：

- `var holder = Node.new()`
- `holder.add_child(Node.new())`

这里 `holder` 在 gdcc 当前合同里仍是 plain `Variant` local，不会因为 initializer 自动回填成 `Node` typed local。  
因此这条调用不会进入 exact engine instance route，而是先退到：

- `FrontendResolvedCall(status = DYNAMIC, callKind = DYNAMIC_FALLBACK)`

也就是说，这条写法当前暴露的是 dynamic fallback / runtime-open surface，不是本条记录里的 lowering 空指针根因。

### 8.2 显式 typed receiver 才会进入当时的 exact-lowering 崩溃链

真正稳定复现当前问题的形状是：

- `var holder: Node = Node.new()`
- `holder.add_child(Node.new())`

这时 call route 会先被 frontend 接受为：

- `FrontendResolvedCall(status = RESOLVED, callKind = INSTANCE_METHOD, receiverType = Node)`

随后 exact call 的 body lowering 在物化参数边界时重新读取 extension metadata，命中：

- `FrontendBodyLoweringSession.callBoundaryParameterTypes(...)`
- `ParameterDef::getType`
- `ExtensionFunctionArgument.getType(...)`

当时的 `ExtensionFunctionArgument.getType()` 仍走旧路径：

- `ClassRegistry.tryParseTextType(type)`

它不认识 extension API 导出的 compatibility spelling，例如：

- `enum::Node.InternalMode`
- `bitfield::Node.ProcessThreadMessages`
- `typedarray::Array`

因此当时最终表现为 lowering 阶段 fail-fast / 空指针，而不是一条稳定的 compile-time diagnostic。

这条 raw metadata spelling 缺口已在 2026-04-16 修复：

- `ExtensionFunctionArgument.getType()` 现统一走 shared `ScopeTypeParsers.parseExtensionTypeMetadata(...)`
- shared parser 已覆盖 `enum::...` / `bitfield::...` / `typedarray::...` / flat-leaf `typeddictionary::K;V`
- nested structured container leaf 仍保持 fail-fast，不会被静默放宽

### 8.3 当前真实根因与影响面

shared resolver 侧其实已经有正确的规范化逻辑：

- `ScopeMethodResolver` 会把 extension metadata 交给 `ScopeTypeParsers.parseExtensionTypeMetadata(...)`
- `enum::...` / `bitfield::...` 会先规约成 `int`
- `typedarray::T` 会先规约成 packed array 或 `Array[T]`

真正脱节的是：

- sema / shared resolver 已经拿到了规范化后的 callable signature
- exact body lowering 却没有复用这份 shared 结果
- 它重新回到 `FunctionDef.getParameters()`，再经 `ParameterDef::getType` 读取 raw extension metadata

因此问题的真实影响面并不只限于 `Node.add_child(...)`，而是：

- 任何 exact engine method route
- 只要其签名中含有 extension-only metadata 文本
- 例如 `enum::...`、`bitfield::...`、`typedarray::...`

`Node.add_child(...)` 只是最容易撞到的入口，因为它的第三个默认参数就是 `enum::Node.InternalMode`。

当前处理结论已更新为：

- `doc/module_impl/frontend/frontend_exact_call_extension_metadata_contract.md` 中记录的 exact-call metadata 合同已经落地，`test_suite` 不再需要只退回零参数 engine method 来绕开这条链路
- method-call backend 现也补齐了与这组修复直接相关的 exact-route ABI 收敛：
  - shared resolver 继续把 `bitfield::...` 规约成 `int` 供语义层统一判断
  - generated `gdcc_engine_call_<...>` helper 继续保留 raw bitfield / enum leaf 的 C 类型名
  - local slot materialization 已下沉到 helper 内部，而不是回退到 caller-side wrapper-compatible cast
  - exact engine route 现直接走 method bind helper + bind call，不再依赖 `gdextension-lite` public wrapper ABI
- 新增 `runtime/engine_array_mesh_exact_default_args.gd`
  - 用 `ArrayMesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arrays)` 直接锚定 exact instance route
  - 这条签名会真实触及：
    - `enum::Mesh.PrimitiveType`
    - `typedarray::Array`
    - `bitfield::Mesh.ArrayFormat`
  - 因此它比只看 `Node.add_child(...)` 更适合作为当前 `test_suite` 的真引擎正向锚点：既命中本计划修复的 metadata family，又避开了无关的对象参数 ABI 噪音
  - 当前这条 case 应保持为可通过的 compile/link/run regression，不再属于单独 known limit
  - 由于 compile surface 目前还不能稳定解析 `Mesh.PRIMITIVE_TRIANGLES` / `Mesh.ARRAY_VERTEX` / `Mesh.ARRAY_MAX` 这组 engine class constants，resource case 现阶段使用与 stock API 对应的稳定数值字面量 `3` / `0` / `13`；这条限制与 exact metadata 修复本身无关
- 新增 `runtime/engine_option_button_default_args.gd`
  - 用 `OptionButton.add_separator()` 省略 `text: String = ""` 默认参数
  - 这条 case 补上另一种常见 gdextension 默认值物化路径，确保 `test_suite` 不只覆盖复杂 metadata family，也覆盖 stock string-default instance route
- 新增 `runtime/engine_node_add_child_exact_typed_receiver.gd`
  - 用 `var holder: Node = Node.new(); holder.add_child(Node.new())` 重新把 object-parameter exact route 锚定到 `test_suite`
  - 这条 resource 明确只认 typed receiver 形状，不把 plain `var holder = Node.new()` 的 dynamic fallback 混进 exact route 验收
- 新增 `runtime/engine_node_add_child_exact_explicit_internal_args.gd`
  - 用 `holder.add_child(child, false, 1)` + `get_child_count() + get_child_count(true)` 把 `Node.add_child(...)` 从“只验证默认参数窄形状”扩展到“显式 `bool/enum` 全参 internal route”
  - 这条 resource 额外锁定：
    - object 参数 exact route 在显式 `bool + enum::Node.InternalMode` 形状下继续走正向 helper
    - runtime 结果必须区分“child 被作为 internal child 挂入”和“错误退回成普通 child / 调用失败”两类行为
- 新增三条 `Node.call(...)` exact vararg resource：
  - `runtime/engine_node_call_exact_vararg_success.gd`
  - `runtime/engine_node_call_exact_vararg_discard_return.gd`
  - `runtime/engine_node_call_exact_vararg_error_path.gd`
  - 它们分别锚定：
    - fixed prefix + tail-bearing `StringName` 参数经 helper pack 后仍能稳定成功返回
    - discard-return 场景在成功的 tail-bearing exact vararg call 后仍完成统一 cleanup
    - error-path 输出稳定诊断后仍可继续执行后续 exact engine method
  - `success` / `discard_return` 对应的 validation 额外要求：
    - 输出中不得出现 `engine method call failed: Object.call`
    - 避免把“中途已经触发 runtime error，但最终返回值碰巧满足断言”的假阳性记成通过
- 新增 `runtime/engine_scene_tree_call_group_flags_exact_vararg.gd`
  - 用 `SceneTree.call_group_flags(0, group, "add_child", child, false, 1)` 把第二条 stock exact vararg family 拉进 `test_suite`
  - 这条 resource 额外覆盖：
    - 非 `Object.call(...)` 的 exact vararg helper
    - fixed prefix `int + StringName + StringName`
    - caller-owned tail 中 `Object + bool + enum` 混排
  - validation 同样要求输出中不得出现 `engine method call failed: SceneTree.call_group_flags`
  - 编写这条 resource 时额外暴露出一个前端老问题：
    - `add_to_group(...)` 这种 bare self inherited void engine call 会在语义分析阶段走到 `ExtensionGdClass.ClassMethod#getReturnType()`，而 `extension_api_451.json` 对 void engine method 通常不提供 `return_value`
    - 当前会因此触发 `NullPointerException`
    - 为了不让这条 vararg 锚点被无关缺陷阻塞，resource 改为等价的 `self.add_to_group(...)`
- stock runtime API 目前仍缺少稳定、非 editor-only 的“object 位于 fixed prefix 的 vararg method”样本
  - `src/main/resources/extension_api_451.json` 中这类 surface 主要落在 `EditorUndoRedoManager.add_do_method(...)` / `add_undo_method(...)`
  - 它们不适合当前 scene-mounted runtime harness
  - 因此 `object + enum + bitfield + wrapper` 混合 fixed prefix 的 helper pack surface，继续由 `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenEngineMethodBindHeaderTest.java` 这类 focused regression 锁定
- `runtime/engine_node_refcounted_workflow.gd` 继续保留
  - 它现在是零参数 smoke anchor
  - 不再承担“代替 exact engine method default route”的职责

### 8.4 `gdextension-lite` object-parameter ptrcall wrapper debt 仍存在，但不再覆盖 gdcc 的 migrated exact engine route

需要保留的事实源不是“`Node.add_child(...)` 仍然阻断 gdcc exact engine `CALL_METHOD`”，而是：

- `gdextension-lite` 当前的 class-method object-parameter ptrcall wrapper debt 仍然存在
- 但 gdcc backend 的 exact engine `CALL_METHOD` route 已不再消费这组 public wrapper ABI
- 因此这条 known limit 的剩余适用面必须缩窄为：
  - 仍直接消费 `gdextension-lite` stock wrapper 的调用面
  - 或任何仍复用这组 shared generator 产物的 surface

当前已经失效的旧前提是：

- 先前 `Node.add_child(...)` 被挂在这里，是因为 gdcc exact engine route 当时仍经过 wrapper-facing surface
- 现在 backend 已迁移到 generated helper：
  - non-vararg exact route 走 `gdcc_engine_call_<...>` + direct `godot_object_method_bind_ptrcall(...)`
  - vararg exact route 走 `gdcc_engine_callv_<...>` + direct `godot_object_method_bind_call(...)`
- 因而对 typed receiver exact route：
  - `var holder: Node = Node.new()`
  - `holder.add_child(Node.new())`
  - 这条形状现在应视为 gdcc backend-focused 正向 runtime regression anchor，而不是 known limit 示例
- 并且同一类 exact route 现已补上显式全参 internal 形状：
  - `holder.add_child(child, false, 1)`
  - `holder.get_child_count() + holder.get_child_count(true) == 1`
  - 这说明当前锚点不再只停留在“默认参数窄形状”
- plain `var holder = Node.new()` 仍只代表 dynamic fallback / runtime-open surface，不能拿来推导 exact route 结论

剩余的 known-limit 结论应改写为：

- `Node.add_child(...)` 必须从“gdcc 当前 exact engine `CALL_METHOD` 仍受 wrapper ABI 缺口阻断”的示例中移出
- `ArrayMesh.add_surface_from_arrays(...)` 仍可继续作为 resource suite 中覆盖 metadata family / default route 的正向锚点
- 但这条 resource 选择不再意味着 `Node.add_child(...)` 对 migrated gdcc exact route 仍不可用
- 本文档保留的只是 `gdextension-lite` stock wrapper debt 本身，而不是把它泛化到已经迁移后的 gdcc exact engine helper route

### 8.5 builtin static gdextension method 暂时也不适合用来补这轮 resource 回归

本轮还尝试过用 `Color.from_rgba8(...)` 这类 stock builtin static method 补另一条默认值正向 case。  
实测暴露出另一条独立边界：

- backend 当前仍未闭环 `CALL_STATIC_METHOD` 的 C codegen

因此当前处理结论是：

- 这轮 `test_suite` 不使用 builtin static gdextension method 作为默认值锚点
- 后续若 backend static-call codegen 闭环，再补这类 compile/link/run case

### 8.6 bare utility default 在当前 stock test_suite 中仍无真实 Godot 锚点

这里需要把“没有补资源用例”与“没有修功能”明确区分开。

事实来源：

- `src/main/resources/extension_api_451.json`
  - 当前 stock `utility_functions` surface 中没有带 `default_value` 的条目

这意味着：

- `doc/test_suite.md` 这条 resource-driven harness 无法在不伪造元数据的前提下，写出一个“真实 Godot stock bare utility call + omitted default args”的 case
- 如果硬造一个 custom utility 名称，只会把测试锚点从“真实引擎行为”退化成“测试自造 API 行为”

当前处理结论：

- `test_suite` 本轮先补真实可表达的 method-default compile/link/run 回归
- bare utility default 继续由 compiler-side focused tests 锚定，例如：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CallGlobalInsnGenTest.java`
- 若后续 Godot stock API 新增带默认值的 utility function，或 `test_suite` 支持挂入额外测试 extension，再补对应的真引擎 resource case

### 8.7 `test_suite` 目前仍缺少 stock exact engine static route 的资源锚点

本轮在补 `engine_method_bind_plan.md` 的 resource 回归时，还暴露出另一条剩余不足：

- `test_suite` 这条 stock compile/link/run harness 目前没有一个稳定、真实、可长期维护的 Godot API 样本，能在“不伪造 metadata”的前提下锚定：
  - 实例语法命中 static engine method
  - 同时还能证明这条 route 继续保持“warning + 无 receiver helper 调用 + NULL bind receiver”合同

这不是说 backend 的 static exact route 没有覆盖，而是：

- 当前可稳定表达的 stock resource 更适合锚定：
  - non-vararg exact instance route
  - object-parameter exact instance route
  - vararg exact route的 success / discard / error-path
  - 第二条 stock vararg family：`SceneTree.call_group_flags(...)`
- static exact route 仍主要依赖 focused integration / codegen tests：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CallMethodInsnGenEngineTest.java`
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenEngineMethodBindHeaderTest.java`

当前处理结论：

- `test_suite` 不为覆盖 static exact route 去引入自造 engine metadata 或偏门 API 样本
- 后续若 Godot stock API 出现更稳定的实例语法 static-anchor，或 harness 支持同时观察 warning / generated helper 信号，再补对应 resource case

### 8.8 本轮 compile-run 资源测试额外暴露并修复了 quoted `StringName` literal codegen 泄漏

这条记录不再是 remaining known limit，但需要保留测试事实链，避免后续误判。

本轮最初把以下 stock GDScript 形状加入 `test_suite`：

- `var probe: Node = Node.new()`
- `var has_method: bool = probe.call(&"has_method", &"queue_free")`
- `probe.call(&"has_method", &"queue_free")`
- 再读取 `probe.get_child_count()`

第一次定向执行 `GdScriptUnitTestCompileRunnerTest` 时暴露出：

- `runtime/engine_node_call_exact_vararg_success.gd`
- `runtime/engine_node_call_exact_vararg_discard_return.gd`

都会在输出里混入：

- `engine method call failed: Object.call`

进一步检查 generated `entry.c` 后确认，真实根因不是 `Object.call` helper 本身，而是：

- frontend source surface 产出的 `LiteralStringNameInsn` 会把 quoted spelling 原样带到 backend
- 例如 `&"get_child_count"` 被落成：
  - `godot_new_StringName_with_utf8_chars(u8"&\\\"get_child_count\\\"")`
- 这会把 GDScript 语法前缀一起写进真实 `StringName` 值中
- 因而 method bind lookup / `Object.call` 正向路径也会被误打成 runtime error

本轮已修复：

- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/NewDataInsnGen.java`
  - 在发射 `LiteralStringNameInsn` 时归一化 frontend 传入的 quoted spelling
  - 并保留转义字符语义
- `src/test/java/dev/superice/gdcc/backend/c/gen/CNewDataInsnGenTest.java`
  - 新增 focused regression，锚定 `&"hero"` 必须生成 `u8"hero"`
- `runtime/engine_node_call_exact_vararg_success.gd`
- `runtime/engine_node_call_exact_vararg_discard_return.gd`
  - 继续保留 tail-bearing positive anchors
  - validation 同时保留 `output_not_contains=engine method call failed: Object.call`，防止将来再出现“返回值正确但中途已报错”的假阳性

当前结论：

- “带尾参 exact vararg 正向 resource case” 现已恢复为稳定正向锚点
- 这条问题不应继续记录为 `test_suite` 的 remaining limitation
- 但 compile-run resource validation 中的 `output_not_contains` guard rail 应继续保留

## 2. `test_suite` 当前只能把 `Node` 根脚本挂进场景树

事实来源：

- `doc/test_suite.md`
  - 明确要求 compiled script 必须 `extends Node`
  - 原因是 `GdScriptUnitTestCompileRunner` 通过 scene node 安装编译产物
  - 若根脚本不是 `Node`，Godot 会创建 placeholder node，验证脚本看到的将不再是目标运行时类

对 test suite 的直接影响：

- 不能把纯 `extends RefCounted` 的顶层脚本直接作为 scene-mounted resource case
- “真实 RefCounted 派生类在场景中的操作” 只能通过以下方式覆盖：
  - 顶层脚本仍 `extends Node`
  - 在该脚本内部实例化并操作 `RefCounted` 派生类
  - 如需同时验证场景树行为，再让真实 `Node` 派生 inner class 挂进树内

当前处理结论：

- 新增 `scene/nested_node_refcounted_scene.gd` 采用 Node-root + inner `Node` + inner `RefCounted` 的组合路径
- 该用例覆盖的是当前 harness 可表达的最接近真实场景协作的正向 surface

## 后续建议

- 若后续要为纯 `RefCounted` 顶层脚本补真引擎 compile/link/run case，需要先扩展 `test_suite` harness，而不是继续往现有 scene-mounted 合同里硬塞
- 若 frontend 后续正式支持 `for` / `match` / `lambda`，应补一轮更贴近 Godot 习惯写法的算法端到端样例，并把当前基于 `while` 的替代写法保留为 regression anchor
