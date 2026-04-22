# Engine Virtual Override 实现约定

## 文档状态

- 状态：事实源维护中
- 更新时间：2026-04-22
- 适用范围：
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendVirtualOverrideAnalyzer.java`
  - `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendSemanticAnalyzer.java`
  - `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
  - `src/main/java/dev/superice/gdcc/scope/VirtualMethodInfo.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
  - `src/main/c/codegen/template_451/entry.c.ftl`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendVirtualOverrideAnalyzerTest.java`
  - `src/test/java/dev/superice/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`
  - `src/test/java/dev/superice/gdcc/test_suite/GdScriptEngineVirtualOverrideRuntimeTest.java`
  - `src/test/java/dev/superice/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java`
- 关联文档：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/backend/engine_method_bind_implementation.md`
  - `doc/test_suite.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`

---

## 1. 背景

gdcc 当前需要同时解决两件事：

- source method 若命中父类 engine virtual 名称，必须按 Godot extension metadata 的真实签名合同进行 override 校验
- `_ready`、`_process`、`_physics_process` 这类由引擎驱动的 virtual，需要在 `test_suite` 中拥有真实 Godot runtime 锚点

这两件事不能互相替代：

- runtime 正向跑通只能证明“当前链路可用”，不能代替 frontend declaration error 的 fail-closed
- backend 严格识别 virtual override 只能防止 name-only 漂移，不能代替 frontend 在源码阶段给出诊断

本文档收口的正是这条闭环：shared semantic 负责 declaration-level 合同，backend 负责严格消费共享 metadata，`test_suite` 负责真实 runtime 正向锚点。

---

## 2. 当前事实

当前代码库中的稳定事实如下：

- `ClassRegistry` 已导入 engine virtual metadata，并同时提供：
  - 对 frontend/backend 共享的 virtual metadata surface
  - engine-only virtual lookup，避免被 gdcc-only 同名声明遮蔽
- `VirtualMethodInfo` 是 frontend/backend 共享的 virtual 签名事实载体：
  - `ownerClassName`
  - `engineMethod`
  - `function`
  - `checkOverrideSignature(...)`
- `FrontendVirtualOverrideAnalyzer` 挂在 shared semantic 路径：
  - phase 顺序固定为 `annotation usage -> virtual override -> type check -> loop control`
  - 它只发 `sema.virtual_override`
  - 它不把坏 override subtree 提前记入 skipped roots
- `FrontendSemanticAnalyzer.analyzeForCompile(...)` 不会把已有 `sema.virtual_override` 重新包装成 `sema.compile_check`
- backend 对 engine virtual override 的识别已不再是 name-only：
  - `CGenHelper` 基于共享 virtual metadata 做严格签名匹配
  - `entry.c.ftl` 保留 `p_hash` ABI 形参，但不依赖它做 gdcc 侧 override 判定
- `test_suite` 已拥有三条 engine virtual runtime 锚点：
  - `_ready`
  - `_process`
  - `_physics_process`

---

## 3. 分层模型

### 3.1 frontend declaration contract 与 backend dispatch contract 必须分层

同一条 engine virtual override 问题被分成两层事实：

- declaration-level：
  - source method 是否命中 inherited engine virtual
  - source method 的 instance/static、参数个数、参数类型、返回类型是否与 metadata 完全一致
- dispatch/codegen-level：
  - 只有严格匹配的 method 才能被 backend 视为 engine virtual override
  - 只有这类 method 才能参与 `get_virtual_with_data(...)` / `call_virtual_with_data(...)`

后续工程不得把 backend 的严格识别误当成 frontend 诊断的替代品，也不得把 frontend 已识别的错误 header 留给 runtime 暴露。

### 3.2 shared semantic 与 compile-only gate 必须分层

`FrontendVirtualOverrideAnalyzer` 属于 shared semantic。

它的职责固定为：

- 读取 skeleton 已发布的类/函数头信息
- 读取 `ClassRegistry` 中的 engine virtual metadata
- 发 `sema.virtual_override`

它不负责：

- 阻断函数体后续分析
- 把 subtree 提前记为 skipped
- 在 compile-only gate 中重新分类错误

compile-only gate 的职责固定为：

- 保留 shared semantic 已经产出的 error 作为 lowering 阻断条件
- 不得为 engine virtual override 错误重复制造 `sema.compile_check`

### 3.3 test_suite runtime anchor 与 frontend compile-fail anchor 必须分层

`test_suite` 的职责固定为 compile / link / run 正向链路。

因此：

- `_ready`、`_process`、`_physics_process` 的正向行为应放在 resource-driven runtime tests
- 错误 override 签名必须留在 frontend focused tests

后续工程不得把坏 override 签名写成“期待 compile-run 失败”的 resource case。那会把 declaration-level boundary 和 runtime 验证面混在一起。

---

## 4. 冻结合同

### 4.1 命中 engine virtual 时必须精确匹配 metadata 签名

当 source method 命中 inherited engine virtual 名称时，frontend/backend 都必须以共享 metadata 为真源。

当前精确匹配合同包括：

- 必须是 instance method
- 参数个数必须一致
- 参数类型必须一致
- 返回类型必须一致
- vararg 形状必须一致

`_process(delta)`、`_physics_process(delta)` 这类缺失显式类型的声明，不能再沿普通 fallback 继续接受为合法 override。

### 4.2 普通非-virtual 方法继续保留 Variant fallback

`resolveTypeOrVariant(...) -> Variant` 的现有合同仍然保留给普通非-virtual 方法。

只有命中 engine virtual override 路径时，才额外 fail-closed。

后续工程不得把这份 engine virtual special contract 偷偷外溢成“所有缺失类型标注的方法都变严格”。

### 4.3 坏 override header 不得打断函数体 facts 发布

坏 override header 仍然保留函数头与函数体的已发布语义事实。

当前冻结合同是：

- `sema.virtual_override` 只负责报错
- 同一函数体后续的 binding / expr / type-check facts 仍继续发布
- inspection/LSP 与 shared semantic 消费者仍可读取这些 facts

这条合同是有意设计的，不是临时妥协。后续工程不得再把它改回 skeleton 直接拒绝函数头的模式。

### 4.4 backend 不依赖 `p_hash` 判定 override 成立

Godot 上游传给 `get_virtual_with_data(...)` 的 `p_hash` 表达的是 virtual compatibility hash。

但对 gdcc 当前实现来说：

- 这是理论背景，不是判定前提
- 第三方方法与 gdcc 方法都无法稳定取得同源 compatibility hash
- backend 必须基于共享 metadata 中可稳定取得的 virtual 身份和签名信息工作

后续工程不得把普通 engine method bind 的 `hash` / `hashCompatibility` 冒充成这条 virtual override 判断链上的事实源。

### 4.5 engine-only virtual lookup 是必须的共享 surface

`ClassRegistry` 需要同时维护：

- visible virtual map
  - 让类查询仍能看到 local gdcc abstract methods 与 inherited engine virtual 的统一视图
- engine-only virtual map
  - 让 frontend/backend 仍能取到底层 engine contract，而不会被 gdcc-only 同名声明遮蔽

后续工程不得再回退为只暴露 `Map<String, FunctionDef>` 这种把 virtual metadata 擦薄到 name-only 的形式。

---

## 5. runtime test suite 合同

### 5.1 当前 runtime anchors

`test_suite` 当前的 engine virtual runtime anchors 固定为：

- `runtime/virtual/ready_called_once.gd`
- `runtime/virtual/process_called_and_delta_valid.gd`
- `runtime/virtual/physics_process_called_and_delta_valid.gd`

对应 validation script 通过观测 target 发布的状态完成验证，而不是主动模拟 virtual 调用。

### 5.2 validation script 写法要求

engine virtual observation case 必须满足：

- validation 只读取 target 已发布状态
- `_process` / `_physics_process` 可等待少量 `process_frame` / `physics_frame`
- 不得在 compiled script 或 validation script 中调用：
  - `set_process(true)`
  - `set_physics_process(true)`
- 不得把主动调用 helper 当作“engine virtual 已生效”的证据

### 5.3 默认帧预算合同

当前三条 runtime anchors 在 `GodotGdextensionTestRunner.DEFAULT_QUIT_AFTER_FRAMES = 10` 下已稳定通过。

因此当前合同是：

- 不需要 `quit_after_frames` directive
- 除非未来新增用例证明确实不足，否则不要扩展 runner 合同

---

## 6. 测试与回归锚点

### 6.1 frontend focused tests

engine virtual override 的 frontend focused regression 必须继续覆盖：

- happy path：
  - `_ready() -> void`
  - `_process(delta: float) -> void`
  - `_physics_process(delta: float) -> void`
- negative path：
  - 多余参数
  - 返回类型错误
  - 缺失显式参数类型导致的 `Variant` fallback
  - 参数类型错误
  - static override
- body facts continue：
  - 坏 override header 不会导致函数 subtree 提前 skipped
  - 同一函数体内其他 semantic facts 仍继续发布
- compile-only boundary：
  - `analyzeForCompile(...)` 下仍只保留 `sema.virtual_override`
  - 不额外制造 `sema.compile_check`

当前主要锚点：

- `FrontendVirtualOverrideAnalyzerTest`
- `FrontendCompileCheckAnalyzerTest`
- `FrontendSemanticAnalyzerFrameworkTest`

### 6.2 backend / shared metadata tests

backend 与 shared metadata 侧必须继续覆盖：

- `ClassRegistryTest`
- `CGenHelperTest`
- `CCodegenTest`

这些测试至少要证明：

- frontend/backend 消费同一份 virtual metadata 真源
- 同名但错误签名的方法不再被 backend 误判成 virtual override
- `p_hash` 不被用作 gdcc override 判定条件

### 6.3 runtime tests

runtime 回归至少要继续证明：

- `_ready` 只触发一次
- `_process` 能被引擎驱动，且 `delta > 0`
- `_physics_process` 能被引擎驱动，且 `delta > 0`
- resource discovery 仍包含三条 virtual runtime fixtures
- fixture 不会重新引入主动 processing toggle 或 runner 指令漂移

当前主要锚点：

- `GdScriptEngineVirtualOverrideRuntimeTest`
- `GdScriptUnitTestCompileRunnerTest`

---

## 7. 已知边界

本文档只覆盖 engine virtual override 相关合同，不覆盖以下相邻问题：

- 所有 Godot engine virtual 的穷举回归
- 顶层 `RefCounted` 根脚本的 scene-mounted harness 扩展
- 普通方法缺失类型标注 fallback 合同的整体改写
- `p_hash` 的完整上游复刻与对齐

与 `test_suite` 其余引擎集成剩余边界相关的记录，统一留在：

- `doc/test_error/test_suite_engine_integration_known_limits.md`

---

## 8. 工程反思

1. engine virtual override 的 declaration contract 必须由 shared semantic 明确拥有。若把这件事留给 backend 或 runtime，错误会以更晚、更吵、更难定位的方式暴露。
2. “坏 header 仍保留函数体 facts” 对 inspection/LSP 很重要。frontend 若在 skeleton 直接拒绝函数头，会让下游工具丢失本可恢复的 body 信息。
3. runtime 正向 anchor 和 frontend compile-fail anchor 必须分层。它们共同构成闭环，但验证的是不同事实。
4. 共享 virtual metadata 如果只剩 name-only 视图，frontend/backend 很快就会漂移出两套 override 判断标准。`VirtualMethodInfo` 这类共享事实载体是必要的，不是多余抽象。
5. 长期实现文档不应保留按步骤推进的流水账、完成状态列表和临时决策轨迹。真正需要维护的是当前事实、稳定边界、测试锚点和未来不能回退的约束。
