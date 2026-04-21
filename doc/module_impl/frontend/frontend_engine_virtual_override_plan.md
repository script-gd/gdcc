# Engine Virtual Override 与 Test Suite 实施计划

## 说明

本文档记录 `_ready`、`_process`、`_physics_process` 等 engine virtual override 支持闭环的实施计划，目标是把当前“部分支持、缺少稳定回归锚点”的状态，推进到“前端显式封口、backend 严格识别、test_suite 有真实 Godot 运行时锚点”的状态。

本文档只记录计划，不替代事实源。当前实现事实仍以代码、已有实现文档与已落地测试为准。

## 文档状态

- 状态：第一步已完成，其余步骤未实施
- 更新时间：2026-04-21
- 关联文档：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/backend/engine_method_bind_implementation.md`
  - `doc/test_suite.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`

---

## 1. 当前起点

当前已经确认的事实如下：

- `ExtensionApiLoader` 已导入 Godot class / method metadata，`ClassRegistry` 已能枚举 engine virtual。
- C backend 已为脚本类接通 `get_virtual_call_data_func` 与 `call_virtual_with_data_func`。
- `_ready` 已存在一条真实 Godot runtime 正向锚点：
  - `src/test/java/dev/superice/gdcc/backend/c/build/CProjectBuilderIntegrationTest.java`
  - 已确认编译产物在 Godot 中输出 `Camera ready.`。
- `test_suite` 当前缺少 `_ready`、`_process`、`_physics_process` 的资源级 runtime 回归锚点，因此 `GdScriptUnitTestCompileRunnerTest` 全绿不能证明这些 engine virtual 已被稳定覆盖。
- frontend 当前没有“命中 engine virtual 时必须校验 override 签名”的封口：
  - `FrontendClassSkeletonBuilder` 目前主要按源码头部发布 `LirFunctionDef`
  - 缺少“同名方法若覆盖父类 engine virtual，则按 metadata 校验参数与返回类型”的逻辑
- `FrontendDeclaredTypeSupport.resolveTypeOrVariant(...)` 会把缺失类型标注的声明回退为 `Variant`。这对 `_process(delta)`、`_physics_process(delta)` 这类 override 是真实风险，因为当前缺失显式类型时仍可能被当作“看起来能继续走”的函数头。
- backend 当前 virtual override 识别仍偏向 name-only：
  - `ClassRegistry.getVirtualMethods(...)` 当前返回 `Map<String, FunctionDef>`
  - `CGenHelper.checkVirtualMethod(...)` 只按 `containsKey(methodName)` 判断
- `src/main/c/codegen/template_451/entry.c.ftl` 中 `get_virtual_with_data(...)` 虽然收到了 `p_hash`，但当前没有消费它，这与 Godot 上游 virtual lookup 的更严格合同不一致。

当前结论：

- `_ready` 可以证明“已有一条正向链路跑通”，但还不能证明 test suite 已覆盖。
- `_process`、`_physics_process` 只能证明“设计链路存在”，还不能证明“当前仓库已稳定支持并被回归锚定”。
- 当前 gdcc 对 engine virtual override 还不能算“良好支持”，更准确的表述是“部分支持，但缺少签名封口与完整回归闭环”。

---

## 2. 目标与最终验收口径

本计划完成后，应同时满足以下四条：

1. `test_suite` 拥有 `_ready`、`_process`、`_physics_process` 三条真实 Godot runtime 正向锚点。
2. frontend 能在源码声明阶段拒绝错误的 engine virtual override 签名，不再把错误 case 留给 backend 或 runtime 暴露。
3. backend 不再仅按方法名识别 virtual override，`get_virtual_with_data(...)` 与 helper 判定需要消费更严格的 virtual 身份信息。
4. 相关文档、focused tests 与 runtime resource tests 同步更新，后续维护者可以直接从测试结果判断“engine virtual override 是否仍闭环”。

---

## 3. 总体实施顺序

建议按以下顺序推进，并保持每一步都可单独提交、单独回归：

1. 先补 `test_suite` 的正向 runtime 锚点，优先验证真实 Godot 行为，而不是先在实现层猜测。
2. 再在 frontend 封口 engine virtual override 的签名合同，避免错误声明继续流入 backend。
3. 再收紧 backend virtual lookup / dispatch 合同，移除 name-only 识别与未消费 `p_hash` 的漂移点。
4. 最后补 focused negative tests 与文档收口，把本轮形成的合同写进长期文档。

原因：

- runtime 正向锚点应先落地，这样后续 frontend/backend 改动始终有真实引擎行为作回归基线。
- frontend 签名封口优先于 backend 收紧，因为错误 override 应尽量在源码声明阶段失败，而不是在 C 模板生成或 Godot runtime 中才失败。
- backend 的严格化是必要收口，但它不应代替 frontend 的语义诊断职责。

---

## 4. 第一步：补齐 `test_suite` 正向运行时锚点

目标：

- 为 `_ready`、`_process`、`_physics_process` 建立真实、可长期维护的 Godot runtime resource regression。

建议实施内容：

- 在 `src/test/test_suite/unit_test/script/runtime/virtual/` 下新增至少三条 source script：
  - `ready_called_once.gd`
  - `process_called_and_delta_valid.gd`
  - `physics_process_called_and_delta_valid.gd`
- 在 `src/test/test_suite/unit_test/validation/runtime/virtual/` 下新增同路径 validation script。
- target script 的职责应尽量简单：
  - 维护计数器，例如 `ready_count`、`process_count`、`physics_process_count`
  - 记录最近一次 `delta`
  - 通过只读 getter 或公开只读状态让 validation script 读取观测值
- validation script 的职责是“等待引擎驱动结果出现后读取状态”，而不是主动驱动待测行为：
  - `_ready` case 允许在 validation 自己的 `_ready()` 中直接读取 target 状态
  - `_process` case 应显式等待若干个 `process_frame`
  - `_physics_process` case 应显式等待若干个 `physics_frame`
- 优先复用当前 runner 现有的默认帧预算：
  - `GodotGdextensionTestRunner` 已有 `DEFAULT_QUIT_AFTER_FRAMES = 10`
  - 第一版资源用例应优先在这条既有合同下稳定通过
- 只有当默认 10 帧预算不足以稳定覆盖 `_process` 或 `_physics_process` 时，才扩展 `GdScriptUnitTestCompileRunner` 的 validation directive：
  - 建议形式：`# gdcc-test: quit_after_frames=<n>`
  - 由 runner 解析后传给 `GodotGdextensionTestRunner.RunOptions`
  - 不要先行抽象出复杂的 test profile 体系
- `GdScriptUnitTestCompileRunnerTest.EXPECTED_SCRIPT_PATHS` 必须同步增加上述资源路径，确保 discovery contract 明确锚定。

实施注意事项：

- validation script 可以读取 target 暴露的观测值，但不得通过普通方法调用去“模拟” `_ready` / `_process` / `_physics_process` 自己被触发。
- validation script 不得调用 `set_process(true)` 或 `set_physics_process(true)` 作为“证明 override 生效”的手段；否则会把“引擎正确识别 override”与“测试主动开启 processing”混在一起。
- `_ready` 已有 integration 正向锚点，不要把它外推为 `_process` / `_physics_process` 已同样可靠。

验收细则：

- happy path：
  - `_ready` case 中，target 记录的 `ready_count` 恰好为 1。
  - `_process` case 中，`process_count > 0` 且记录到的 `delta > 0`。
  - `_physics_process` case 中，`physics_process_count > 0` 且记录到的 `delta > 0`。
  - 三条用例都由引擎自动驱动，不依赖 validation 主动调用待测 virtual。
  - `GdScriptUnitTestCompileRunnerTest` 能稳定覆盖新资源路径。
- negative path：
  - 如果默认帧预算 10 不足，才允许补 `quit_after_frames` 指令；若不需要，就不要为“未来可能需要”而预先扩展 runner。
  - 不允许把“validation 主动拉起行为”误记为 virtual override 成功。

建议回归命令：

- `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdScriptUnitTestCompileRunnerTest`
- 如需保留现有 `_ready` 真引擎锚点，再跑：
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CProjectBuilderIntegrationTest`

### 当前实施状态（2026-04-21）

- [x] 已新增三组 resource pair：
  - `src/test/test_suite/unit_test/script/runtime/virtual/ready_called_once.gd`
  - `src/test/test_suite/unit_test/script/runtime/virtual/process_called_and_delta_valid.gd`
  - `src/test/test_suite/unit_test/script/runtime/virtual/physics_process_called_and_delta_valid.gd`
  - 以及对应 `validation/runtime/virtual/` 脚本
- [x] 已把三条新路径加入 `GdScriptUnitTestCompileRunnerTest.EXPECTED_SCRIPT_PATHS`
- [x] 已新增 focused test：
  - `src/test/java/dev/superice/gdcc/test_suite/GdScriptEngineVirtualOverrideRuntimeTest.java`
  - 该测试同时锚定正向 compile-run 入口与负向 fixture 约束
- [x] 已确认默认 `DEFAULT_QUIT_AFTER_FRAMES = 10` 足以稳定覆盖 `_process` / `_physics_process`
- [x] 已完成 targeted test run：
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdScriptEngineVirtualOverrideRuntimeTest`
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdScriptUnitTestCompileRunnerTest`
  - `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CProjectBuilderIntegrationTest`
- [x] 本轮未引入 `quit_after_frames` 指令；默认 runner 合同已满足当前三条 virtual 用例

---

## 5. 第二步：在 frontend 封口 engine virtual override 签名

目标：

- 当源码方法名命中父类 engine virtual 时，frontend 必须按 extension metadata 校验 override 签名。
- 错误签名必须在 frontend 阶段直接失败，而不是继续进入 backend 或运行时。

建议实施内容：

- 优先在 `FrontendClassSkeletonBuilder` 所在的 declaration / skeleton 路径完成封口：
  - 此阶段已经掌握方法名、参数列表、返回类型、所属类和父类信息
  - 与“函数头是否合法”这一职责边界更一致
- 复用 `ClassRegistry` 中已经导入的 engine virtual metadata，不要为 override 校验再造一套独立 metadata 表。
- 对命中 engine virtual 的 source method，按“精确兼容”而不是“宽松兼容”处理：
  - 必须是 instance method
  - 参数个数必须一致
  - 参数类型必须与 metadata 一致
  - 返回类型必须与 metadata 一致
- 对 `_process(delta)`、`_physics_process(delta)` 这类声明，若参数缺失显式类型而当前会被回退成 `Variant`，必须在 engine virtual override 路径上直接报错，而不是继续接受这条声明。
- 诊断 owner 建议优先沿用 `sema.class_skeleton`：
  - 这是 declaration-level 头部合同
  - 不建议为本轮只创建一个单独 analyzer 再引入额外 owner/category
- 普通非-virtual 方法的 `resolveTypeOrVariant(...) -> Variant` 现有行为不应被本轮一起改掉；本轮只收紧命中 engine virtual 的 override 路径。

建议测试锚点：

- `src/test/java/dev/superice/gdcc/frontend/sema/FrontendClassSkeletonTest.java`
- 如需要补更小粒度的类型回退对照，可补充：
  - `src/test/java/dev/superice/gdcc/frontend/sema/FrontendDeclaredTypeSupportTest.java`

验收细则：

- happy path：
  - `func _ready() -> void` 可以正常通过。
  - `_process`、`_physics_process` 的正确声明可以正常通过。
  - 普通非-virtual 方法缺失类型标注时，现有 `Variant` 回退合同不被误伤。
- negative path：
  - `func _ready(arg) -> void` 产生明确 frontend diagnostic。
  - `func _ready() -> int` 产生明确 frontend diagnostic。
  - `func _process(delta)` 因缺失显式类型而产生明确 frontend diagnostic。
  - `func _physics_process(delta: int) -> void` 因参数类型不匹配产生明确 frontend diagnostic。
  - 错误 case 不再进入 backend codegen 或 runtime 碰运气。

建议回归命令：

- `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests FrontendClassSkeletonTest`

---

## 6. 第三步：在 backend 收紧 virtual lookup / dispatch 合同

目标：

- backend 不再只按方法名把 source method 视为 virtual override。
- `get_virtual_with_data(...)` 应消费 `p_hash`，并与更严格的 virtual 身份信息对齐。

建议实施内容：

- 先收紧 `ClassRegistry` 对 virtual metadata 的表达能力：
  - 不能继续只暴露“方法名 -> FunctionDef”这一级信息
  - 至少要让 consumer 能读取 virtual 的签名与 hash 身份
- `CGenHelper.checkVirtualMethod(...)` 不再做 name-only `containsKey` 判定，而要基于“同名且签名兼容”的严格结果工作。
- `src/main/c/codegen/template_451/entry.c.ftl` 中 `get_virtual_with_data(...)` 必须实际消费 `p_hash`，不要再保留未使用参数。
- `call_virtual_with_data(...)` 的 userdata 识别仍可沿用当前函数指针路径，但其来源必须是前一步严格筛出的 virtual。
- 如果需要新增 helper，请保持内聚：
  - 优先把合同收在 `ClassRegistry` / `CGenHelper` 的既有职责里
  - 不要为同一判断链路再引入只有一个实现的新抽象层

建议测试锚点：

- `src/test/java/dev/superice/gdcc/scope/ClassRegistryTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CGenHelperTest.java`
- 如需校验生成代码文本，可补 focused codegen test：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`

验收细则：

- happy path：
  - 正确签名的 `_ready` / `_process` / `_physics_process` 仍能被 backend 识别为 virtual override。
  - 生成的 `get_virtual_with_data(...)` 不再忽略 `p_hash`。
  - backend 与 frontend 使用同一份 virtual metadata 事实源，不出现“两套签名判断标准”。
- negative path：
  - 同名但错误签名的方法不再被 backend 误判成 virtual override。
  - 不允许继续保留“只要名字一样就注册 virtual”的旧路径。
  - 不允许再让 `p_hash` 形参长期处于未消费状态。

建议回归命令：

- `rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests ClassRegistryTest,CGenHelperTest,CCodegenTest`

---

## 7. 第四步：补 focused negative tests 与文档收口

目标：

- 为本轮新合同建立长期、清晰、可读的负向回归锚点。
- 把“支持边界”和“测试写法要求”同步写回长期文档，避免实现与文档漂移。

建议实施内容：

- compile-fail 场景优先放在 frontend focused tests，而不是塞进 `test_suite`：
  - `test_suite` 的主职责是 compile / link / run 正向链路
  - 错误 override 签名属于 frontend declaration diagnostic，更适合由 `FrontendClassSkeletonTest` 这类 focused tests 锚定
- 若第一步实际扩展了 runner directive，还需要同步更新：
  - `doc/test_suite.md`
- 在 `doc/test_error/test_suite_engine_integration_known_limits.md` 中收口“当前缺少 engine virtual runtime 锚点”的描述，避免文档继续陈述已经补齐的 gap。
- 在 `doc/module_impl/frontend/frontend_rules.md` 中补充 engine virtual override 的共享合同：
  - 命中 engine virtual 时必须使用 metadata 精确签名
  - 错误签名由 frontend declaration 路径 fail-closed
- 待实现真正落地后，再回写本文档状态与验收结果，不要让计划文档长期停留在“已实施但未改状态”。

验收细则：

- happy path：
  - 新增规则在 focused test 与 runtime test 两侧都有锚点。
  - 文档中不再同时存在“已实现”与“仍缺口”的冲突描述。
- negative path：
  - compile-fail 用例不应依赖 runtime 才暴露问题。
  - 文档更新不能只改一处，至少要同步覆盖 frontend 规则文档和 test suite 合同文档中受影响的部分。

---

## 8. 非目标

本轮计划明确不把以下内容混入同一实施批次：

- 为所有 Godot engine virtual 一次性做完穷举回归；本轮优先以 `_ready`、`_process`、`_physics_process` 建立闭环。
- 顺手重构整个 override 架构或引入新的大层级 abstraction。
- 扩展 `test_suite` 去支持顶层 `RefCounted` 根脚本挂场景树；这与本轮 engine virtual override 验收不是同一问题。
- 把普通方法的“缺失类型标注回退到 `Variant`”语义整体改写；本轮只收紧 engine virtual override 路径。

---

## 9. 风险与实施注意事项

- `_process(delta)`、`_physics_process(delta)` 若继续允许缺失参数类型，会天然滑向 `Variant` 路径；这与 engine virtual override 的精确合同冲突，必须正面封口。
- `_ready` 已有一条 integration 正向锚点，不代表 `_process` / `_physics_process` 已自动等价成立。
- 若 validation script 主动开启 processing，测试会失去“引擎是否正确识别 override”这一核心验证意义。
- backend 收紧 virtual lookup 不能代替 frontend 诊断；否则错误签名仍会以“代码生成副作用”而不是“源码错误”形式暴露。
- 若第一步资源锚点显示默认 10 帧预算已经稳定足够，就不要为了假想需求提前扩展 runner 合同。

---

## 10. 建议提交切分

建议按以下提交粒度推进：

1. `test_suite` 正向 runtime 锚点
2. frontend engine virtual override 签名封口
3. backend virtual lookup / `p_hash` 收紧
4. focused negative tests 与文档收口

这样每一步都能独立回归，也更容易在评审中判断回归来源。
