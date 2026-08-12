# Test Suite Engine Integration 已知边界

## 背景

本轮为 `doc/test_suite.md` 驱动的真引擎端到端测试补充以下主题覆盖：

- 常见数组与字典操作
- 常见算法：斐波那契、BFS / DFS、数组求和、字符串处理
- inner class 运行时行为
- Node / RefCounted 派生类在场景中的协作

在设计这些正向样例时，确认了多条当前仍然成立、且会直接影响测试写法的边界，以及少量已经修复、但需要从旧测试写法中清理掉的历史回归。

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

## 3. 已修复：`Array` / `Dictionary` literal 已 compile-ready

历史记录：早期 suite 路径中 `var sequence: Array = [0, 1]` / `var scores: Dictionary = {}` 等会在
compile-check 以 `Array/Dictionary literal is ... temporarily blocked` 失败；作者一度改用
`Array()` / `Dictionary()` + `push_back` 规避。

现已修复（阶段 6）：

- compile gate 不再显式拦截 `ArrayExpression` / `DictionaryExpression`
- 全链路走 `FrontendContainerLiteralPlan` → `ContainerLiteralItem` → `construct_container_literal`
- 回归 suite：`collection/array_literal_roundtrip.gd`、`collection/dictionary_literal_roundtrip.gd`、
  `collection/container_literal_evaluation_order.gd`、`collection/typed_container_literal_boundaries.gd`
- 新用例可直接使用字面量；仍可选用 `Array()` / `Dictionary()` 构造路径（empty construct 合同不变）

## 4. 已修复：plain `Dictionary` keyed subscript 不再要求显式 `Variant` key slot

- shared subscript semantic gate 现在统一复用 `FrontendVariantBoundaryCompatibility`
- plain `Dictionary`（`Dictionary[Variant, Variant]`）的字符串 key 正向写法已经恢复为：
  - `scores["alpha"] = 2`
  - `int(scores["alpha"])`
- keyed lowering 路由保持不变：plain `Dictionary` + `String` key 继续冻结为 `VariantSetKeyedInsn` / `VariantGetKeyedInsn`，backend codegen 再把 key 物化到真实 `Variant` 调用面
- test-suite 资源脚本已去掉历史 workaround，不再需要显式 `var alpha_key: Variant = "alpha"`
- 当前剩余 gap 只在 Godot 更宽、且不属于 ordinary typed-boundary helper 的 keyed/index widened conversion，例如 builtin keyed metadata route、以及 `Array` / packed array 的 float index；`Dictionary[StringName, V]` / `Dictionary[String, V]` 的 `String` / `StringName` key 互通由 ordinary boundary feature gate 覆盖

当前回归锚点包括：

- `src/test/test_suite/unit_test/script/collection/dictionary_mutation_and_lookup.gd`
- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendSubscriptSemanticSupportTest.java`
- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`
- `src/test/java/gd/script/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java` 的 collection category

## 5. 已修复：GDScript 可执行 body 中的 `CommentStatement` 不再阻断 CFG builder

- `CommentStatement` 现在被 frontend CFG builder 视为 executable lowering 的 lexical no-op
- comment 不再发布 `SequenceItem`，也不会额外生成 runtime `LineNumberInsn`
- compile-run resource script 不需要再因为“函数体里有注释”而迁移说明文字
- 若确实需要一个稳定、可执行的 source-level no-op 行，仍应使用真正的 `pass`

## 7. 已修复：inner GDCC `Node` / `RefCounted` 子类已能走通 scene API 正向路径

`scene/nested_node_refcounted_scene.gd` 现在可以稳定覆盖以下组合 surface：

- inner `SceneChild extends Node` 可赋给 `Node` typed slot
- `add_child(...)` / `get_node_or_null(...)` 这类 engine scene API 可以在这条 inner-class 路径上稳定工作
- `var mounted_child = self.get_node_or_null(child_path); mounted_child == null` 这条 compiled GDCC source 路径也已恢复稳定
- inner `SceneWorker extends RefCounted` 可以作为 `SceneChild` 的对象字段参与同一条 runtime workflow

当前合同需要注意的是：

- scene-mounted inner GDCC node 的 `get_class()` 不会退回 stock `Node`
- 它会返回 Godot-facing canonical class name，例如本例中的 `NestedNodeRefcountedSceneSmoke__sub__SceneChild`
- 因此 test-suite fixture 应以 canonical runtime class name 为锚点，而不是把 inner node 当作 plain engine `Node`
- 上面这条 `== null` 修复的根因在 backend `Nil -> Variant` 物化，而不是 Godot scene API 本身；引擎侧真实 nil `Variant` 构造函数始终是 `godot_new_Variant_nil()`

说明：

- 真正需要单独验收的仍然是 Godot-facing class-name surface 的分层合同，而不是把所有“会碰到类名的地方”压成一个平面：
  - 注册身份面
  - outward metadata 面
  - runtime compare 面
  - dormant / 预留面
- 详细盘点与当前冻结合同统一维护在 `doc/module_impl/frontend/gdcc_facing_class_name_contract.md`。

## 8. exact engine route resource 覆盖现状

- `test_suite` 现已包含多条真实 Godot runtime resource，覆盖 exact/default/vararg 路径：
  - `runtime/engine_node_add_child_exact_explicit_internal_args.gd`
  - `runtime/engine_node_add_child_exact_typed_receiver.gd`
  - `runtime/engine_node_call_exact_vararg_success.gd`
  - `runtime/engine_node_call_exact_vararg_error_path.gd`
  - `runtime/engine_node_call_exact_vararg_discard_return.gd`
  - `runtime/engine_scene_tree_call_group_flags_exact_vararg.gd`
  - `runtime/engine_array_mesh_exact_default_args.gd`
  - `runtime/engine_option_button_default_args.gd`
- backend 侧 exact route 的文本/metadata 矩阵仍由 focused regression 维护：
  - `src/test/java/gd/script/gdcc/backend/c/gen/CallMethodInsnGenEngineTest.java`
  - `src/test/java/gd/script/gdcc/backend/c/gen/CCodegenEngineMethodBindHeaderTest.java`
- bare utility default 在当前 stock `test_suite` 中仍无真实 Godot 锚点。
  - `extension_api_451.json` 当前没有带 `default_value` 的 stock utility function
  - 这条 coverage 继续由 focused tests 锚定，例如：
    - `src/test/java/gd/script/gdcc/backend/c/gen/CallGlobalInsnGenTest.java`
- 当前 ABI resource 分类还包括：
  - `abi/variant`
  - `abi/typed_array`
  - `abi/typed_dictionary`

## 9. 已修复：engine virtual runtime anchors 已补齐

- `test_suite` 现已拥有三条真实 Godot runtime 锚点：
  - `src/test/test_suite/unit_test/script/runtime/virtual/ready_called_once.gd`
  - `src/test/test_suite/unit_test/script/runtime/virtual/process_called_and_delta_valid.gd`
  - `src/test/test_suite/unit_test/script/runtime/virtual/physics_process_called_and_delta_valid.gd`
- 对应 validation script 统一通过观测计数器与 `delta` 来验收引擎驱动结果，而不是主动模拟 virtual 调用。
- `_process` / `_physics_process` validation 当前通过等待少量 `process_frame` / `physics_frame` 完成验证。
- `GodotGdextensionTestRunner.DEFAULT_QUIT_AFTER_FRAMES` 保持 `10`，避免所有 Godot runtime 测试都被迫多跑帧。
- `_physics_process` validation 通过 Java 侧 `RunOptions.withQuitAfterFrames(60)` 单独提高预算；`--quit-after` 按 idle frame 计数，headless 运行中 10 个 idle frame 可能早于 validation 等到 3 个 `physics_frame`。
- 错误签名 negative path 继续留在 frontend focused tests，而不是回塞进 `test_suite` resource：
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendVirtualOverrideAnalyzerTest.java`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`
- 当前回归锚点包括：
  - `src/test/java/gd/script/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java`
  - `src/test/java/gd/script/gdcc/test_suite/GdScriptEngineVirtualOverrideRuntimeTest.java`