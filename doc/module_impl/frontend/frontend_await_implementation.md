# Frontend Await / Coroutine Implementation

> Updated: 2026-08-26
>
> 本文档是 `await` / 协程实现的事实源：冻结 Godot 语义基线、架构决策、ABI 合同、
> 所有权与生命周期规则、失败边界、风险登记与回归锚点。
> 只记录当前状态；不记录实施步骤、完成进度或历史流水账。

## 1. 维护合同

- 任何 await/coroutine 相关的 semantic、compile gate、CFG/lowering、LIR/backend ABI、runtime
  合同变更，必须同步更新本文档与下列关联事实源。
- 关联事实源（各自拥有自己的合同，本文档不重复其细节）：
    - `gdcc_low_ir.md` §Coroutine Instructions：`AWAIT` 指令、`is_coroutine` 标记、
      `compiler::GdccCoroState` 的 LIR 级规则。
    - `gdcc_ownership_lifecycle_spec.md` §3.10：协程所有权状态机。
    - `gdcc_runtime_lib.md`：`gdcc_coroutine` runtime helper 登记。
    - `gdcc_facing_class_name_contract.md` §1.3：隐藏状态类命名公式。
    - `frontend_signal_support.md`：signal / Callable 侧合同。
    - `frontend_lambda_implementation.md`：lambda 与 capture 通用合同。
    - `frontend_compile_check_analyzer_implementation.md`：compile gate 总合同。
    - `frontend_rules.md`：frontend 支持面入口。
    - `backend/call_method_implementation.md`：调用指令 backend 总合同。
- 代码注释引用本文档时使用 `frontend_await_implementation.md`（可带 § 节号）。

## 2. 当前支持面

### 已闭环

- `await <signal>`：0 参数恢复 `Variant(nil)`；1 参数恢复该参数；多参数恢复 `Array[Variant]`。
- `await <instance coroutine call>` / `await <static coroutine call>`：done 状态走 typed fast
  path 直接复制结果；suspended 状态登记 typed waiter 并挂起。
- dynamic await：三层分派 —— `Signal`、GDCC 自己的协程状态对象、外部带 `completed`
  signal 的对象；其余 Variant 值立即穿透。
- statement-root fire-and-forget：instance 与 static 协程调用可直接作为
  `ExpressionStatement` 根表达式；调用后立刻 `INTERNAL destruct` detach，调用方**不**标记为协程。
- lambda body 内 `await`：协程 lambda 经 Callable ABI 进入 start thunk 并做 done/suspend
  分派；capture 逐调用拷贝入协程帧（§5、§9）。
- engine boundary（ClassDB wrapper，§7.5）：同步完成走 typed `move_result`；挂起时
  `Variant` 返回状态对象、`void` detach 后台运行、typed 非 `Variant` 返回零值 +
  runtime error 并 detach。

### 仍 fail-closed

- value-position 协程调用：赋值右值、实参、`return`、运算操作数、容器元素、链式调用的
  中间调用等，compile gate 报错（§8）。
- property initializer 内的 `await`。
- parameter default 内的 `await`。
- 静态已知非 Signal 纯值 `await x`：当前为 error，是有意偏离 Godot 的 warning（§12、§13）。

## 3. Godot 4.5.1 语义基线

- `await signal` 使用 `CONNECT_ONE_SHOT`（=4）连接：0 参数恢复 `null`，1 参数直接恢复该值，
  多参数恢复 `Array`。
- `await call`：同步函数立即返回其结果；协程函数真正挂起时等待其完成并恢复其返回值。
- 静态已知普通值的 await 属于 redundant await：Godot 发 warning 并立即穿透返回。
- 协程性按函数确定：statement 根表达式允许 fire-and-forget，value 位置必须显式 `await`。
  脚本层无法取得原生 GDScript function-state。
- 协程内部状态以 `completed(result)` signal 传递；void 协程也以单个 nil 参数完成。
- `OPCODE_AWAIT` 运行时先严格识别 engine 内部 function-state；普通非 Signal 值直接穿透；
  已释放对象报错。
- Godot 没有 done 检查：对已完成状态对象连接 `completed` 会永久等待。连接本身不保活
  emitter，保活 state 是绑定返回值的责任。

## 4. 设计决策

| # | 决策点 | 结论与理由 |
|---|---|---|
| 1 | 协程运行时 | vendored `edubart/minicoro`（虚拟内存有栈协程，默认 1MB 栈预留）；不手写上下文切换、不用 C++ coroutine。 |
| 2 | LIR 形态 | 一等 `AWAIT` 指令 + CFG 普通 value `AwaitItem`；有栈协程无需 continuation CFG 拆分。 |
| 3 | 状态对象 | 每个协程函数生成一个隐藏 `RefCounted` 状态类，携带参数字段、typed return slot、done/cancel、`result_cache`、waiter 链与 `mco_coro*`，并暴露 `completed(result)`；wrapper 根字段为 `_object`；使用独立 binding token 与普通 class binding 隔离。命名公式冻结于 `gdcc_facing_class_name_contract.md` §1.3。 |
| 4 | 放弃路径 | `PREDELETE` 中 cancel-resume：置 cancel、resume minicoro，body 在 await 恢复点跳入 `__finally__`；不 finalize、不 emit、不恢复 waiter。 |
| 5 | 调用位置 | 协程调用仅允许 await operand 或 statement root；其余 value 位置由 compile gate 拒绝（§8）。 |
| 6 | 内部 ABI | 显式 `compiler::GdccCoroState`（C 存储 `godot_Object*`）；typed `copy_ret_slot` 传递结果，避免 Variant 往返与运行时类型检查。 |
| 7 | dynamic await | 三层分派（Signal / 自有状态对象 / 外部 completed 对象）；外部对象通过 connect 返回码检测 signal 是否存在。 |
| 8 | connect-after-done | 自有状态支持 done fast path；外部 completed-state 无缓存协议，connect-after-done 保持挂起语义（对齐 Godot）。 |
| 9 | static builtin wrapper | static builtin 方法无 instance receiver：wrapper 不生成 `self` 参数，ptrcall base 传 `NULL`。 |
| 10 | lambda capture | 协程 lambda 的 capture 逐调用拷贝入协程帧，由 `free_instance` 统一销毁；不复用普通 lambda 的 `_capture` prologue。 |

## 5. 协程函数模型

- 语义阶段把「直接含真实 await」或「await 协程调用」的函数记录进
  `FrontendAnalysisData.coroutineFunctions`；named/constructor 直接落到
  `LirFunctionDef.isCoroutine()`，lambda 以 AST identity 记录于 `coroutineLambdaOwners`，
  在 lowering function-preparation pass 桥接到合成 shell（§9）。
- 隐藏状态类不进入普通用户类注册循环，由 backend 专用模板循环生成。
- body 固定形态：`void <Class>_<name>__coro_body(mco_coro*)`；C 局部变量与
  `__prepare__/__finally__` 形态不变。
- 参数不生成 body 的 C 参数槽：参数唯一 owning storage 是状态帧字段
  （`_coro_param_<name>`），`free_instance` 恰好销毁一次。理由：`user_data` 必须跨
  start thunk 存活；minicoro 栈没有析构钩子；帧成为单一所有权表。
- 协程 lambda 的 capture 字段（`_coro_capture_<name>`）与参数字段同一纪律：start thunk
  从借用的 `_capture` block 逐字段做 per-call 拷贝，`free_instance` 恰好销毁一次；
  capture block 本身仍归 Callable userdata 所有。
- start thunk：创建状态对象、填充参数（与 capture）、`mco_create`、`mco_resume`。
  `mco_create` OOM 时走单通道：写入默认返回值、pack、置 done，返回 `co == NULL` 的
  done 状态对象。
- 内部调用始终返回 OWNED 状态对象（即使同步完成）。
- static 协程的 start thunk 无 receiver 参数；其状态对象与帧也不含隐式 `self` 字段。
- 禁止 vararg 协程。

## 6. 运行时结构

- runtime 由 vendored `minicoro.h/.c` 与 GDCC `gdcc_coroutine.h/.c` 组成；
  `CProjectBuilder` 必须把两个 runtime `.c` TU 纳入编译输入。
- 公共状态头：magic、类描述符、对象回指、`mco_coro*`、done/cancel、Variant
  `result_cache`、waiter 链；专用 binding token 与普通 class binding 隔离。
- signal await：构造 one-shot Callable，Callable 持有自身状态对象引用；连接失败 →
  runtime error、填 nil、直接返回，不挂起。
- static state await：done → 经 `copy_ret_slot` 立即复制；未完成 → 登记 typed waiter，
  并在 yield 前释放调用点持有的 callee OWNED 引用。
- dynamic await：经 token+magic 识别自有状态对象；yield 前释放 operand 引用并置 nil，避免环。
- finalize 固定顺序：pack `result_cache`（拷贝而非 move）→ 置 done → 逐 waiter 复制结果
  并先 resume 后释放引用 → emit `completed`；必须可重入。
- cancel 与 finalize 互斥：`PREDELETE` 触发 cancel-resume；waiter 保持挂起，只释放引用边；
  `free_instance` 统一销毁 typed return slot、`result_cache`、参数字段、capture 字段与
  minicoro。

## 7. await 分派路径

LIR 形状：`$result = await $operand`。

### 7.1 signal 路径

调用 `gdcc_coro_await_signal`；结果经 Variant 临时值与既有 unpack 边界物化。

### 7.2 static coroutine-call 路径（instance / static）

- await 指令不 pattern-match 调用：call generator（`CallMethodInsnGen` /
  `CallStaticMethodInsnGen`）生成 start thunk 调用并产出状态对象；`AwaitInsnGen` 按静态
  operand 类型走 state 路径。
- codegen：识别状态头（专用 binding token）→ 将调用点 OWNED 引用交给
  `gdcc_coro_await_state`（其内部释放，done fast path 也释放）→ 源槽置 moved-from
  `NULL` → typed 结果经 `out_typed` 写出。
- 不做运行时类型检查：类型正确性由 LIR 合同保证。

### 7.3 dynamic 路径

- `Signal`：走 signal helper。
- 自有状态对象：done → 从 `result_cache` 复制；未完成 → 登记 Variant waiter。
- 外部对象：连接 `completed`；connect 返回码表明无该 signal 时按穿透处理。
- 其他类型：立即穿透返回 operand 本身。

### 7.4 statement root（fire-and-forget）

statement-root 协程调用仍写 `GdccCoroState` 结果槽，随后紧跟 `INTERNAL destruct` 释放调用点
引用完成 detach；协程由自身 wait 边保活，完成后自然释放。

### 7.5 engine boundary

- 同步完成：wrapper 取出 typed 结果（`move_result`），释放 state。
- 挂起 + `Variant` 返回：返回 `Variant(Object)` 状态对象，外部可对其 await `completed`。
- 挂起 + `void`：detach，后台继续运行。
- 挂起 + typed 非 `Variant`：detach、返回零值并报 runtime error（有意偏离：typed ptrcall
  无法携带状态；engine-facing 协程应使用 `void`/`Variant`）。

## 8. frontend：operand 分类、协程标记与 compile gate

### 8.1 operand 分类（owner：`FrontendExpressionSemanticSupport` 的 await classifier）

| operand | await 结果类型 | 调用方标记协程？ |
|---|---|---|
| `GdSignalType` | 0 参数 → `Variant`；1 参数 → 参数类型；多参数 → `Array[Variant]` | 是 |
| 协程 instance/static call | 非 void → 声明返回类型；void → `Variant`(nil) | 是 |
| 非协程 call 返回 Signal | 按 signal 行 | 是 |
| 非协程 call 返回 Variant / 未标注 | `Variant`（dynamic await） | 是 |
| 非协程 call 返回其他硬类型 | —— | 否；发 `sema.redundant_await` warning（对齐 Godot `REDUNDANT_AWAIT`），穿透 |
| dynamic operand（Variant） | `Variant` | 是 |
| 其他静态非 Signal 纯值 | —— | 否；当前 error（有意偏离，§12/§13） |

- 协程调用表达式的 source 静态类型是 callee 声明返回类型；`compiler::GdccCoroState`
  只在 lowering 时出现于 call 结果槽，不进入 source 类型系统。
- lambda body 与所在 owner 使用同一套分类规则；property initializer 保持 fail-closed；
  parameter default 不进入 body 表达式 typing。

### 8.2 协程标记

- `await <call>` operand 在 `EXPR_TYPE` 时 callee 协程性可能未知，记录为 pending
  （`FrontendAwaitCallPending`；owner 用 `FrontendAwaitCoroutineOwner` identity handle 表示）。
- `FrontendAwaitCoroutineAnalyzer` 在 suite 结束后做 fixed-point 传播：callee 是协程 →
  caller 标记协程；static 与 instance callee 一视同仁。传播后只有硬类型非协程 call 才发
  `sema.redundant_await`。
- lambda caller 加入 identity-keyed owner 集合，lowering 时桥接到合成 shell。

### 8.3 compile gate（owner：`FrontendCompileCheckAnalyzer`，category `sema.compile_check`）

- 合法位置仅两种：statement 根表达式（fire-and-forget）；await 顶层 operand（裸
  `CallExpression` 或链尾 `AttributeCallStep`）。
- statement-root 判定为一次性标志，只在直接根表达式上成立；嵌套表达式一律 value position。
- value-position 协程调用报错：
  `Function '<name>' is a coroutine, so it can't be called without 'await' outside a statement root expression`。
- 链式调用中间位置的协程调用同样是 value position。
- gate 只消费 `FrontendAwaitCoroutineAnalyzer` 发布的稳定事实，不自行推断协程性。

## 9. CFG / lowering

- `AwaitItem` 表示一个 await 挂起点，operand 先按普通值物化（signal 读取、call 结果或
  Variant）。
- 协程调用的结果物化为专用 `CORO_STATE_SLOT`（`__coro_state_<valueId>`），类型
  `compiler::GdccCoroState`；即使声明 `void` 返回也保留 state 结果槽。
- await-consumed value id 集合按整张 CFG 收集；未被 await 消费的协程调用结果在调用后
  立刻追加 `DestructInsn(..., LifecycleProvenance.INTERNAL)`（fire-and-forget detach）。
- lambda coroutine bridge：shell 在 function-preparation pass 合成，而 skeleton pass 早已
  消费 `coroutineFunctions`；因此 sema 按 AST identity 记录 owner，这里同时设置
  `setCoroutine` 与 `coroutineFunctions` membership。
- 普通 frontend boundary materialization 拒绝 compiler-only 类型作为 source/target ——
  `GdccCoroState` 不会流出协程调用槽。

## 10. LIR / backend ABI

- `AWAIT` 指令、`is_coroutine="true"` 函数标记与 `<captures>` 均可序列化 round-trip；
  `is_lambda` 与 `is_coroutine` 是正交标记，可共存于同一 shell。
- `compiler::GdccCoroState`：compiler-only、move-only、C 存储 `godot_Object*`。只能由协程
  `call_method` / `call_static_method`（`is_coroutine="true"`，result 必须为该类型）产生；
  合法消费者只有 `await`（消费后源槽置 `NULL`）与 `destruct`（释放引用）。禁止
  assign/copy、Variant pack/unpack、作参数、return、存入 property/容器、`ref`。
- backend 将 GDCC 协程 callee 的 C 函数名解析为 `<Class>_<method>__coro_start`（与
  ClassDB entry 同参数形状）；static 路由无 receiver。
- `CallStaticMethodInsnGen` 承载 `CALL_STATIC_METHOD`：无 dynamic fallback；static
  builtin wrapper 无 `self`、ptrcall base 为 `NULL`（决策 9）。
- `DestructInsnGen` 对 `GdccCoroState` 生成 `gdcc_coro_state_slot_destroy(&slot)`；
  重复 cleanup 安全（槽已置 `NULL`）。
- 状态类、body、start thunk、engine entry 由 `entry.h.ftl` / `entry.c.ftl` / `func.ftl`
  模板生成；runtime 只保留通用逻辑。

## 11. 所有权与生命周期

- 协程帧与状态对象合一，引用计数交给 Godot `RefCounted`。
- 引用边方向：signal 等待边由 Callable 持有自身状态；协程链由 callee waiter 持有
  awaiter；调用点 callee 引用在登记后释放，避免环。
- 挂起不触发 `__finally__`；真实返回或 cancel-resume 才执行一次清理。
- 参数与 lambda capture 的唯一 owning storage 是状态帧字段，`free_instance` 恰好销毁一次。
- 返回值状态机：body 写 `_return_val` → finalize 拷贝进 `result_cache` 并置 done →
  engine wrapper 可 move typed 槽 → free 时销毁所有构造态字段。
- cancel-resume：不置 done、不 pack、不 emit、不恢复 waiter；await 恢复点统一检查
  cancel，且该路径禁止调用用户代码。

更细的状态机以 `gdcc_ownership_lifecycle_spec.md` §3.10 为准。

## 12. 风险登记与已知限制

| # | 风险/限制 | 现状与对策 |
|---|---|---|
| R1 | 隐藏状态类命名冲突 | class 级保留前缀 `_gdcc_coro_state_` + `__coro__` 分隔（`gdcc_facing_class_name_contract.md` §1.3）。 |
| R2 | 状态/帧环与 wrapper 首字段冲突 | RefCounted + 定向引用边 + 独立 binding token + `_object` 根字段。 |
| R3 | `self` 死亡后恢复 | 接受既有 live assertion；主动取消（per-instance pending 帧列表）列入 Post-MVP（§13）。 |
| R4 | connect-after-done | 自有状态走 done fast path；外部对象保持挂起语义（对齐 Godot）。 |
| R5 | dynamic 对象识别 | 专用 token+magic 识别自有状态；外部对象以 connect 返回码判断。 |
| R6 | lambda capture 生命周期 | 逐调用拷贝入帧已落地。已知限制：共享容器回持 state 形成的引用环可使 `PREDELETE`/cancel 失效；主动取消（R3）是其兜底。 |
| R7 | finalize waiter 级联恢复导致 C 栈增长 | 接受；必要时引入 trampoline 恢复队列（§13）。 |
| R8 | 每次调用都创建状态对象的开销 | 接受；未来可懒物化（§13）。 |
| R9 | 每函数状态类布局依赖 LIR | wrapper/注册代码全部由模板生成；runtime 只保留通用逻辑。 |
| R10 | typed 非 `Variant` engine ptrcall 挂起无法携带状态 | detach + 零值 + runtime error（§7.5）；engine-facing 协程应使用 `void`/`Variant`。 |
| R11 | minicoro 后端与栈深度 | 锁定汇编/虚拟内存后端，默认 1MB 虚拟预留；以栈压测验证。 |
| R12 | 静态已知非 Signal 纯值 `await x` | 当前为 error 而非 Godot 的 warning + 穿透；属有意偏离，放宽列入 Post-MVP（§13）。 |

## 13. Post-MVP backlog

1. value-position 协程调用：允许调用表达式作为普通值（语义上返回 state 值）；需要
   source-facing 类型合同、所有权/生命周期规则与 Variant boxing 配套，现状见 §8。
2. 静态已知纯值 `await x` 放宽为 warning + 穿透（R12），并评估「存在 `AwaitExpression`
   即标记协程」的严格 Godot 对齐。
3. 性能：状态对象懒物化、`mco_create`/栈复用池、可配置 `stack_size`（R8）。
4. 主动取消：per-instance pending 帧列表（R3），同时作为 R6 共享容器引用环的兜底取消。
5. finalize waiter 恢复的 trampoline 队列（R7）。
6. 多线程与多个 `_Thread_local` 协程调度。

## 14. 测试约定与回归锚点

### 通用约定

- 每个 pass、指令与 runtime helper 都要有 happy path 与 negative path；negative path
  锚定诊断 category、pipeline 是否停止、是否禁止生成产物。
- basic block 改动必须测试 `entryBlockId`、terminator 完整性与 serializer/parser round-trip。
- backend 测试以生成 C 字符串锚点为主；runtime/e2e 在无 zig 或无 `GODOT_BIN` 时按环境
  assumption skip。
- 修改 compile gate 放行面时必须同步更新 `frontend_rules.md`、
  `frontend_compile_check_analyzer_implementation.md`、本文档与相关测试。

### 回归锚点

- frontend 语义/gate：`FrontendAwaitSemanticTest`、`FrontendCompileCheckAnalyzerTest`、
  `FrontendExpressionSemanticSupportTest`。
- CFG/lowering：`FrontendCfgGraphBuilderTest`、`FrontendAwaitInsnLoweringTest`、
  `FrontendLambdaLoweringTest`、`FrontendLoweringClassSkeletonPassTest`。
- LIR：`AwaitInsnContractTest`、`DomLirParserTest` / `DomLirSerializerTest`、
  `GdccCoroStateTypeTest`、`GdCompilerTypeTest`、`LirPublicAbiValidatorTest`。
- backend codegen：`AwaitInsnGenTest`、`CallMethodInsnGenTest`、`CallStaticMethodInsnGenTest`、
  `CCoroutineStateClassCodegenTest`、`CDestructInsnGenTest`、`GodotBuiltinGeneratorTest`。
- C 编译 smoke / runtime smoke：`CCoroutineGeneratedCSyntaxSmokeTest`、
  `GdccCoroutineRuntimeSmokeTest`、`CProjectBuilderCoroutineRuntimeInputTest`。
- e2e fixtures：`src/test/test_suite/unit_test/script/coroutine/`（经
  `GdScriptUnitTestCompileRunnerTest` 驱动）。
