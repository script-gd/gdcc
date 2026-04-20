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

## 5. 已修复：GDScript 可执行 body 中的 `CommentStatement` 不再阻断 CFG builder

- `CommentStatement` 现在被 frontend CFG builder 视为 executable lowering 的 lexical no-op
- comment 不再发布 `SequenceItem`，也不会额外生成 runtime `LineNumberInsn`
- compile-run resource script 不需要再因为“函数体里有注释”而迁移说明文字
- 若确实需要一个稳定、可执行的 source-level no-op 行，仍应使用真正的 `pass`

## 7. inner GDCC `Node` / `RefCounted` 子类与 engine scene API 的 compile surface 尚未闭环

本轮尝试增加 `scene/nested_node_refcounted_scene.gd`，目标是让 inner `SceneChild extends Node` 真正挂进场景树，并让 inner `SceneWorker extends RefCounted` 参与运行时协作。实测暴露出：

- inner `SceneChild` 目前不能赋给 `Node` typed slot
- `add_child(...)` / `get_node_or_null(...)` 这类 engine scene API 在这条 inner-class surface 上无法稳定解析
- 即便退回 `call(...)` 形式，当前 compile surface 也未闭环

这说明：

- inner class 身份模型本身已存在
- 但 inner GDCC class 与 engine `Node` API 的 assignability / callable route 仍未打通到可用于真场景 resource test 的程度

说明：

- 当前真实剩余问题已经收束为 backend 的 Godot-facing class-name strategy，而不是这里只列出的临时 runner 调整步骤。
- 详细盘点、成因链路与分步骤实施计划已迁移到 `doc/module_impl/frontend/godot_facing_class_name_surface_plan.md`。

## 8. exact engine route 剩余 resource gap

- `test_suite` 目前仍缺少稳定、真实、可长期维护的 stock exact engine static route resource。
  - backend 侧 static exact route 继续主要依赖：
    - `src/test/java/dev/superice/gdcc/backend/c/gen/CallMethodInsnGenEngineTest.java`
    - `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenEngineMethodBindHeaderTest.java`
- stock runtime API 目前仍缺少稳定、非 editor-only 的“object 位于 fixed prefix 的 vararg method”样本。
  - 这类 helper surface 继续由 focused regression 锚定：
    - `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenEngineMethodBindHeaderTest.java`
- bare utility default 在当前 stock `test_suite` 中仍无真实 Godot 锚点。
  - `extension_api_451.json` 当前没有带 `default_value` 的 stock utility function
  - 这条 coverage 继续由 focused tests 锚定，例如：
    - `src/test/java/dev/superice/gdcc/backend/c/gen/CallGlobalInsnGenTest.java`

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

## 已修复回顾

- 原问题 6“编译脚本内部 string literal surface 污染”已不再属于当前 known limit。
- 当前回归锚点包括：
  - `src/test/test_suite/unit_test/script/runtime/string_literal_internal_surface.gd`
  - `src/test/test_suite/unit_test/validation/runtime/string_literal_internal_surface.gd`
  - `src/test/java/dev/superice/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java` 中的 runtime category `@TestFactory`
  - `src/test/java/dev/superice/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java`
