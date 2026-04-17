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
- method-call backend 现也补齐了与这组修复直接相关的一段 ABI 适配：
  - shared resolver 继续把 `bitfield::...` 规约成 `int` 供语义层统一判断
  - 但 wrapper 调用前会保留 raw bitfield leaf 的 C 类型名，并把 addressable temp cast 成 `const godot_<Owner>_<Flag> *`
  - 这样既不把 frontend/shared resolver 拉回二次解析，也避免 `ArrayMesh.add_surface_from_arrays(...)` 这类 stock wrapper 在 compile surface 因 `godot_int` / enum-pointer 形状不匹配而失败
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
- `runtime/engine_node_refcounted_workflow.gd` 继续保留
  - 它现在是零参数 smoke anchor
  - 不再承担“代替 exact engine method default route”的职责

### 8.4 `Node.add_child(...)` 仍不适合作为当前 `test_suite` 的真引擎正向锚点

这次在把 `Node.add_child(...)` 写成 resource case 后，又额外暴露出一条**独立于 frontend exact metadata 修复**的旧问题：

- gdextension-lite 当前的 class-method object-parameter ptrcall wrapper 仍有 ABI 缺口
- 典型表现就是：
  - `godot_Node_get_child_count(bool)` 这类无对象参数的 wrapper 可以正常工作
  - `godot_Node_add_child(Node, bool, enum)` 这类带对象参数的 wrapper 会在真引擎运行时崩溃

这条问题的完整成因链需要单独写清，否则它很容易继续被误读成“frontend 计划还没修干净”：

1. `frontend_exact_call_extension_metadata_contract.md` 约束并记录的是 exact route 对 extension exported metadata 的二次 raw parse 修复：
   - shared resolver 先把 `enum::...` / `bitfield::...` / `typedarray::...` 规范化
   - lowering 改为复用 published callable boundary
   - backend 再基于这份已规范化边界去物化默认参数并生成 C 调用
2. 因此 `Node.add_child(...)` 在本轮修复后，frontend / lowering 已经不再因为 `enum::Node.InternalMode` 这类 metadata spelling 失败：
   - 剩下的失败点发生在生成后的 runtime C surface，而不是 exact metadata route 本身
3. gdcc backend 当前对 `godot_*` 调用走的是“先把 GDCC object ptr 转成 Godot raw object ptr，再调用已发布的 gdextension-lite wrapper”：
   - 也就是说，gdcc 此处消费的是 wrapper public ABI，而不是自己直接拼 `godot_object_method_bind_ptrcall(...)`
4. `godot_Node_add_child(...)` 的 public wrapper 签名是：
   - `void godot_Node_add_child(godot_Node *self, const godot_Node *node, godot_bool force_readable_name, godot_Node_InternalMode internal);`
   - 这里第二个参数 `node` 表面上是“对象指针值”
5. 但 gdextension-lite 在 wrapper 内部桥接到 `ptrcall` 时，当前生成的是：
   - `_args[] = { node, &force_readable_name, &internal }`
   - 也就是 object 参数直接放 `node`，而 primitive / enum 参数放各自局部槽位地址
6. Godot 上游的 `MethodBind::ptrcall(...)` / `call_with_ptr_args(...)` 契约并不是“`p_args[i]` 直接等于参数值”：
   - 每个 `p_args[i]` 都是“指向参数值存储槽的地址”
   - 对 `bool` / `int` / `enum` 来说，这正是 `&local_value`
   - 对 `Node *` 这类 object 参数来说，slot 里保存的值本身是一个对象指针，所以传给 `ptrcall` 的应当是 `&node`
7. Godot 上游 `PtrToArg<T *>` 的解包方式会把 `p_args[i]` 当成 `T **` 槽位来再解引用一次：
   - 正确形状：`p_args[i] == &node`，引擎读出 `*reinterpret_cast<T *const *>(p_args[i])` 后得到真实 `node`
   - 当前错误形状：`p_args[i] == node`，引擎会把对象实例内存起始地址误当成“保存了 `Node *` 的槽位”来读
8. 于是 `godot_Node_add_child(...)` 崩溃的根因并不是 `Node.add_child(...)` 这个 API 本身，而是 wrapper 把：
   - public C ABI 中的 object pointer argument
   - 和 Godot `ptrcall` 需要的 object-pointer slot address
   - 错误地当成了同一层
9. 这也解释了为什么：
   - `godot_Node_get_child_count(bool)` 没有 object 参数，所以 `_args[] = { &include_internal }` 形状正确，调用正常
   - `godot_Node_add_child(Node, bool, enum)` 一旦出现 object 参数，就会在 `_args[0]` 这一位把错误形状送进 `ptrcall`
10. 因而它也不是 `Node.add_child(...)` 单点特例：
   - `remove_child(Node)`、`reparent(Node, bool)`、`TreeItem.add_child(TreeItem)` 这类 class-method object-parameter wrapper 理论上都共享同一风险面

换句话说，本轮 frontend exact metadata 修复把 `Node.add_child(...)` 从“前端先失败”推进到了“runtime 暴露真实 ABI 缺口”的阶段；它没有解决这条问题，不是因为方案遗漏，而是因为这本来就是另一条位于 gdextension-lite wrapper 层的独立缺陷。

因此当前处理结论是：

- `Node.add_child(...)` 仍然保留为 frontend sema / lowering focused regression anchor
- 真引擎 `test_suite` 则改用 `ArrayMesh.add_surface_from_arrays(...)` 作为 exact metadata default 的 runtime anchor
- 等 gdextension-lite 的 object-parameter ptrcall 包装修复后，再把 `Node.add_child(...)` 补回 resource suite 会更干净

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
