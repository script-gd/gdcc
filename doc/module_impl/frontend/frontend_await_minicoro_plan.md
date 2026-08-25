# Frontend Await（minicoro 有栈协程）实施计划

> 本文档记录基于 minicoro 虚拟内存有栈协程的 `await` 功能实施路线图。起始调研时仓库尚无 await/coroutine 的任何实现；本文档只保留尚未落地的计划内容，落地后的事实应转写为独立 implementation 文档并从本文档移除。

## 文档状态

- 方案说明：2026-08-23 已切换为「`completed` 信号 + Godot 可见状态对象」方案，并经一轮 review 修订。
- 更新时间：2026-08-23
- 当前事实源：
  - `frontend_rules.md`
  - `frontend_signal_support.md`
  - `frontend_lambda_implementation.md`
  - `frontend_lowering_plan.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `frontend_compile_check_analyzer_implementation.md`
  - `gdcc_facing_class_name_contract.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/gdcc_runtime_lib.md`

---

## 1. 背景与当前起点

调研结论（2026-08-23，基于子代理并行调研）：

- AST 层已有 `dev.superice.gdparser.frontend.ast.AwaitExpression`（外部 `gdparser` 依赖提供，形状为「一个 operand expression + range」），本仓库无自己的 lexer/grammar 分支，**词法与语法层面无需任何工作**。
- 语义层：`FrontendExpressionSemanticSupport.resolveExplicitDeferredExpressionType(...)`（`FrontendExpressionSemanticSupport.java:793-823,876-891`）把 `AwaitExpression` 统一发布为 `DEFERRED`；`FrontendCompileCheckAnalyzer.java:699-737` 拦截 compile surface 上的 `DEFERRED` 表达式并发 `sema.compile_check`，`FrontendLoweringAnalysisPass` 随后 `requestStop()`，await 永远无法到达 CFG/lowering。
- CFG 层：`FrontendCfgGraphBuilder.buildValue(...)`（`FrontendCfgGraphBuilder.java:2006-2069`）没有 `AwaitExpression` case；`requireLoweringReadyExpressionType`（`:4162-4194`）对 `DEFERRED` fail-fast。
- signal 基础设施已闭环：`construct_signal` / `SignalLoadItem` / `GdSignalType`（携带不可变参数类型列表）/ `ScopeSignalResolver`；`.connect` / `.emit` 走既有 builtin `CallMethodInsn` 路径。
- Callable 基础设施已闭环：`gdcc_callable.h` 提供 lambda/standalone custom Callable（`godot_callable_custom_create2`），可作为协程恢复回调的模板。
- LIR 无任何挂起/恢复语义指令；所有 `call_*` 均为同步调用。`call_intrinsic` 机制可扩展但合同定位为「单次调用、同步返回一个值」的 backend helper（见 `gdcc_lir_intrinsic.md` 新增 Intrinsic Checklist）。
- C 后端函数形态：普通同步函数 + `goto __prepare__` / `__finally__` 框架（`gdcc_c_backend.md` §`__prepare__/__finally__` Control Flow）；return 统一写 `_return_val` 后跳 `__finally__` 清理局部变量再真实 `return`。
- 运行时：`src/main/c/codegen/include_451/` 下 `gdcc/**` 为 header-only 手写 helper；`CProjectBuilder.java:85-96` 收集编译输入（生成 `.c` + 硬编码的 `include/godot/godot_binding.c`），`CProjectBuilder.java:145-152` 负责提取 `gdcc/`、`godot/` 资源树；`ZigCcCompiler` 以 `zig cc -std=c23 -shared -flto` 直接编译所有 C 输入。仓库中不存在任何 `minicoro` / `coroutine` / `mco_` 引用。
- GDExtension 类注册已核实（4.5.1 ABI）：`GDExtensionClassCreationInfo5`（`gdextension_interface.h:368-402`）带 `is_exposed` / `is_runtime` 布尔字段；`godot_classdb_register_extension_class5` 为当前推荐注册入口；`godot_classdb_register_extension_class_signal`（`gdextension_interface.h:3083-3096`）注册信号；`gdcc_ref_counted_init_raw`（`gdcc_helper.h:686-697`）已有「init_ref + POSTINITIALIZE」的现成模式；`CGenHelper.renderSignalParameterMetadata`（`CGenHelper.java:1167`）对 `Variant` 参数自动合成 `... | godot_PROPERTY_USAGE_NIL_IS_VARIANT`（`:1144`）；现有用户类注册循环在 `entry.c.ftl:39-59`（`is_exposed` 硬编码为 true）；用户类 wrapper struct 首字段为 `_super` / `_object`（`entry.h.ftl:30-41`）。
- 现有测试锚点（实施到对应步骤时必须翻转或保留）：
  - `FrontendCompileCheckAnalyzerTest.analyzeForCompileBlocksAwaitSignal`（`:1924-1950`）当前断言 `await pinged` 编译失败。
  - `FrontendExpressionSemanticSupportTest`（`:1498-1539`）当前断言 `AwaitExpression` 为 `DEFERRED`。

关键架构判断：**有栈协程下函数体 C 代码形状基本不变**。挂起只是运行时的栈切换（`mco_yield`），C 栈帧（局部变量、`_return_val`、`__finally__` 清理路径）整体存活在 minicoro 栈上，恢复后继续执行。因此 CFG 层**不需要** continuation 拆分，LIR 层只需要一个外观上同步的 `await` 指令，复杂度集中在「协程函数的入口包装」「runtime 恢复协议」与「放弃路径的清理」（§3.6 cancel-resume）三处。

---

## 2. Godot 4.5.1 await 语义基线

- `await <signal>`：以 `CONNECT_ONE_SHOT`（flags 值 = 4）连接 signal 并挂起；signal 触发时恢复，恢复值规则为：
  - 0 个 signal 参数 → `null`
  - 1 个 signal 参数 → 直接返回该参数值
  - 多个 signal 参数 → 返回包含全部参数的 `Array`
- `await <call>`：调用目标函数；若目标不是协程（从不挂起），await 直接返回其返回值，**不发生挂起**；若目标是协程且挂起，awaiter 挂起直到目标完成，恢复值为其返回值。
- `await` 一个静态已知的非 signal、非 call 表达式：Godot 发 `REDUNDANT_AWAIT` warning 并立即返回该值（不挂起）。
- 协程性是按函数判定的：只有函数体**直接**包含 await 的函数才是协程函数（Godot 以「语法上存在 AwaitExpression」标记 `is_coroutine`，含 `await 1` 这类冗余写法）；调用协程而不 await 的调用方自身不会变成协程，而是立即拿到一个 function-state 值。
- 解释执行层通过 `GDScriptFunctionState`（RefCounted，带 `completed` signal）表示挂起的协程；engine 边界调用一个挂起的 void 方法（如 `_ready` 中 await）时，返回值被丢弃，协程由 signal 连接保活并在后台继续。
- 非 await 位置调用协程（来源 `gdscript_analyzer.cpp` `reduce_call`、`gdscript_vm.cpp` `OPCODE_AWAIT`、`gdscript_function.cpp`）：
  - statement（root expression）位置：**合法**，fire-and-forget；裸调用先同步执行到第一个真正挂起点，之后由 signal 连接绑定的 state 引用保活并在触发时恢复；脚本 reload / 实例销毁时经 pending state 列表清理。
  - value 位置（`var x = foo()`、实参、运算等）：分析器报错 `Function "foo()" is a coroutine, so it must be called with "await".`（判定条件 `call_type.is_coroutine && !p_is_await && !p_is_root`）。
  - 同步完成路径不创建 state，直接返回普通返回值；state 只在真正挂起时创建。
  - `GDScriptFunctionState.completed` 固定为单参数信号 `completed(result)`（参数注册为 `Variant::NIL + PROPERTY_USAGE_NIL_IS_VARIANT`），void 协程也以 `nil` 单参发射；协程链上的后续 state 继承前一 state 的 `completed`，最终返回值沿链传播。
- `OPCODE_AWAIT` 的精确分派（`gdscript_vm.cpp:2530-2638`，本文动态路径的直接对齐对象）：
  - 操作数是 Object 且通过 `GDScriptFunctionState` **严格类判定**（`is_class_ptr`）→ 改写为 `Signal(obj, "completed")` 后走 signal 路径；**任意其他带 `completed` 信号的普通 Object 不命中该判定，直接穿透**；
  - 操作数不是 Signal（含普通 Object、int、nil 等一切类型）→ **立即把操作数本身写入结果**，不挂起、不报错（redundant await 的运行时形态）；
  - 操作数是已释放对象 → runtime error `"Trying to await on a freed object."`；
  - **没有** `is_valid()` / done 检查：await 一个已完成的状态对象时 connect 成功但信号永不再发射，awaiter 永久静默挂起——connect-after-done 在 Godot 中就是挂起语义；
  - 挂起期间被 await 的 state 由 signal 连接上 `bind(retvalue)` 的引用保活（`gdscript_vm.cpp:2610`）；`Object::connect` 本身不保活 emitter 或 target（`CONNECT_REFERENCE_COUNTED = 8` 只控制重复连接）。

---

## 3. 总体设计

### 3.1 设计决策总表

| 决策点 | 选择 | 备选（拒绝理由） |
| --- | --- | --- |
| 协程库 | vendored `edubart/minicoro`（MIT，单头库 + `MINICORO_IMPL` TU，虚拟内存栈） | 手写 ucontext/汇编（维护成本）；C++20 coroutine（无栈/需重写函数体形状） |
| IR 表达 | 新增一等 `await` LIR 指令 | `call_intrinsic`（intrinsic 合同是同步单值 helper，await 具有挂起语义，应为一等指令并写入 `gdcc_low_ir.md`） |
| CFG 表达 | 新增普通 value item `AwaitItem`，不拆分控制流 | continuation 拆分（有栈协程下无必要） |
| 协程函数 codegen | 每个协程函数生成「入口 thunk + minicoro body 函数 + 隐藏状态类」 | 调用点内联建栈（违背封装，无法支撑 engine 边界） |
| 挂起状态表示 | **每个协程函数（未来含 lambda）生成一个隐藏 RefCounted 类**（`_gdcc_coro_state_` 前缀，`is_exposed = false`、`is_runtime = true`），其实例 wrapper 即协程帧：携带参数、返回槽、`done` 标志、结果 Variant 缓存、waiter 链表、`mco_coro*`；对外暴露 `completed(result)` 单参信号（逐字对齐 `GDScriptFunctionState` 合同）。**wrapper 根字段为 `_object`（无 GDCC `_super` 链），公共头不占 offset 0，帧公共头经独立 instance binding token（非 `class_library`）暴露**，与 `explicit_c_inheritance_layout_contract.md` 不冲突 | 编译器内部纯 C struct（无法跨 engine/动态边界观察，gdcc↔gdcc 动态 await 与解释层互操作无法闭环）；单一全局状态类（无法按函数携带类型化参数字段）；magic 放 wrapper offset 0（破坏首字段合同，`identify` 与普通 wrapper 互相误判） |
| 挂起协程的放弃路径（emitter 死亡等原因导致引用归零） | **cancel-resume**：状态类的 `NOTIFICATION_PREDELETE`（destructor 触碰帧字段之前）发现 `MCO_SUSPENDED` 时置 cancel 标志并 `mco_resume`，body 在每个 await 恢复点检查标志并直跳 `__finally__`（默认值已在 `__prepare__` 就绪），清理栈上 OWNED 值后以 `MCO_DEAD` 返回；cancel 与 finalize **互斥**（不置 done、不 emit、不 resume waiter——waiter 仅释放引用，awaiter 永久挂起，对齐 Godot `cancel_pending_functions`）；`free_instance` 只处理 DEAD 或 `co == NULL`（OOM）协程 | 直接 `mco_destroy`（丢弃协程栈 = 栈上 OWNED 值永不消费，违反 `gdcc_ownership_lifecycle_spec.md`）；把 managed local 镜像到帧（放弃 C 形状不变）；挂在 `free_instance`（对象已在拆解，resume 时机过晚且与 finalize 时序冲突） |
| 协程函数调用点 | await operand 位置或 statement 位置（fire-and-forget，对齐 Godot root-expression 合同）；其余 value 位置由 compile gate 拒绝 | 允许 value 位置返回 state 值（语义放行本身简单——state 已是 Variant 值——但需类型系统配套，留作 Post-MVP 候选） |
| 内部调用 ABI | **单通道 + 显式类型**：协程 `call_method` 的 result 变量以 compiler-only 类型 `compiler::GdccCoroState`（C 存储 `godot_Object*`）声明；内部 coroutine-start thunk 总是交还 OWNED 状态对象引用（同步完成时状态已 `done`）；await 侧静态分派纯由 operand 静态类型驱动：done fast path + C 层直接 waiter 登记均经状态类 desc 的 `copy_ret_slot` 生成回调取值（类型化通道，零 Variant 往返），**零运行时类型检测、零跨指令簿记**；无状态 fast path 仅保留在 engine 边界 wrapper 内部 | Variant 载体 + 隐藏临时对（多一次 pack/unpack，且表达不了 done 语义）；名称派生临时对 + out 参数双通道（backend 需维护跨指令 context，违反统一类型系统目标）；内部路径统一走 `completed` 信号（不必要的 Signal/connect/emit 开销） |
| 动态/Variant await | `gdcc_coro_await_dynamic` 三层分派（§3.4）：Signal → signal 路径；自己的状态对象（独立 binding token 识别）→ done 检查 / C 层直接 waiter 通道；外部对象 → `Signal(obj, "completed")` connect，**以 connect 错误码作为存在性检测**，失败即穿透 | 编译期 fail-closed（放弃解释层 interop 与 Callable 路径）；`has_signal` 显式检测（多一次动态查询，与 connect 结果冗余） |
| 外部对象的 `completed` duck-type | **有意宽于 Godot**（Godot 严格类判定只认 `GDScriptFunctionState`，其余穿透；我们对任何带 `completed` 信号的对象都等待）：这是覆盖解释层协程与 Callable 路径的必要扩展，已在 §2 标注基线差异；其代价是 await 一个恰好带 `completed` 信号的无关对象会等待该信号（含 connect-after-done 挂起），作为已接受的偏离文档化 | 严格 `is_class("GDScriptFunctionState")` 判定（多一次动态调用，且未来第三方 GDExtension 协程无法互操作） |
| connect-after-done | 三层处理（§3.4 dynamic 路径）：静态路径由 `done` 标志 + `copy_ret_slot` 类型化拷贝立即返回；dynamic 自己状态对象路径由 `done` 标志 + `result_cache` Variant 拷贝立即返回；外部对象路径挂起（对 `GDScriptFunctionState` 逐字对齐 Godot，见 §2） | 全局统一挂起（放弃已完成 fast path）；全局报错（偏离 Godot 语义） |
| lambda / property init 内 await | MVP 继续 fail-closed；Callable 的 ABI 阻碍已被状态对象方案解除（`call_func` 挂起时返回状态对象 Variant 即可），剩余工作为捕获引用计数（R6），列为 MVP 后第一跟进项 | 同步开放（capture 生命周期需独立设计） |

### 3.2 协程函数模型

- frontend 在语义阶段判定「函数直接包含真实 await」（见 §3.5 分类），把函数集合发布到 `FrontendAnalysisData` 新 side table；`FrontendLoweringClassSkeletonPass` 消费该事实，在 `LirFunctionDef` 上写入新属性（`isCoroutine()`，XML 序列化为 `is_coroutine` 函数属性）。
- 隐藏状态类**不是**普通 `LirClassDef`，不进入 `module.classDefs` 用户类注册循环（该循环 `is_exposed` 硬编码为 true）；由 backend 按 `is_coroutine="true"` 函数集合在 `entry.c.ftl` / `entry.h.ftl` 另开专用生成循环。状态类只有 canonical 名、无 sourceName、不可 `extends`、不进 source-facing registry（R1/R15）。
- backend 对每个 `is_coroutine="true"` 的函数生成（模板落点：`func.ftl` / `entry.c.ftl` / `entry.h.ftl`；wrapper struct 与 create/free 回调的生成方式参照现有用户类与 lambda capture struct，`entry.h.ftl:30-41,68-126`、`entry.c.ftl:39-59`）：
  1. **隐藏状态类** `_gdcc_coro_state_<canonicalClass>__coro__<func>`（**直接继承 `RefCounted`**，`is_exposed = false`、`is_runtime = true`；C 标识符映射与现有用户类模板走同一套 helper，不新增清洗规则）：wrapper struct 首字段为 `_object`（无 GDCC `_super` 链），帧字段紧随其后：公共头（magic 常量 + 类描述符指针 + `GDExtensionObjectPtr obj` 回指 + `mco_coro *` + `done` + `cancel` + 结果 Variant 缓存 + waiter 链表）+ 类型化参数字段 + 类型化返回槽。创建时设置**两个** instance binding（callbacks 均与 `entry.h.ftl:46-50` 一致为全 `NULL`，防止 get 时惰性创建）：标准 `class_library` binding（与其他 gdcc 类一致）+ 专用协程 token binding（指向公共头，供 `gdcc_coro_state_identify` 使用）。
  2. **body 函数**：`void <Class>_<name>__coro_body(mco_coro *_co)`，函数体与现有同步形态**完全一致**（同样的 `__prepare__` / `__finally__`、同样的指令生成），区别仅是：
     - 参数访问：**不生成参数 C 槽**。body 内对参数变量的读写由 codegen 直接映射到帧的类型化参数字段（`mco_get_user_data(_co)` 取得 wrapper，参数 operand 渲染为帧字段访问表达式）；帧字段是唯一 owning 存储，参数写入按普通 slot-write 规则作用于帧字段；`__prepare__` 不初始化参数（thunk 已填充），`__finally__` 不清理参数；参数字段由 `free_instance` 恰好清理一次（cancel 路径不触碰；cancel-resume 后协程即为 `MCO_DEAD`，同样汇入 `free_instance`）——杜绝参数槽与帧字段双份存储导致的双重释放；
     - 非 void 时 `__finally__` 把 `_return_val` **consume** 进帧的类型化返回槽而不是 `return`（此后 `_return_val` 视为已清空，与普通 move-return 合同一致）；
     - 每个 await 恢复点之后检查帧的 `cancel` 标志：置位则直跳 `__finally__`（cancel-resume，见 §3.6）；
     - 函数结尾自然 return → minicoro 状态转为 `MCO_DEAD`。

      > **设计理由：参数为什么住状态对象帧而不是协程私有栈**（2026-08-24 复审轮澄清，防遗忘）：
      > 1. **ABI 强制**：minicoro body 签名固定为 `void body(mco_coro *_co)`，thunk→body 唯一数据通道是 `mco_desc.user_data`（body 内经 `mco_get_user_data` 取回）；该指针指向的内存必须在 thunk 返回后仍有效，堆上状态对象是唯一合法载体。body 内声明的局部变量本就在私有栈上（C 编译器自动安排），跨 yield 天然存活，无需特殊处理。
      > 2. **栈无析构钩子**：`mco_destroy` 只释放栈内存，不会为栈上 String/Array 调 `godot_*_destroy`、不会 release object fat ptr；而参数填充纪律（拷贝构造/retain）要求成对的销毁动作。销毁点必须覆盖全部终态——OOM（`mco_create` 失败，栈不存在但参数已拷入）、创建后从未 resume 即 free、cancel（destructor 合同不触碰帧字段）——其中唯一稳定存在且自带析构时机的就是状态对象的 `free_instance`。
      > 3. **单一所有权表**：字段声明（`entry.h.ftl`）、thunk 填充（`entry.c.ftl`）、`free_instance` 释放三侧遍历同一份 `func.parameters`，无「部分构造」簿记。
      > 4. **不做帧→栈二次拷贝**：帧字段已是 owning 可写存储（GDScript 参数语义即可写局部量），再拷一层只有双倍存储与写回分歧（lambda capture、嵌套协程读的都是帧字段），无收益。
   3. **入口 thunk**：对外保持两类入口（§3.4 engine 边界），职责为：经生成的 `create_instance2` 创建状态对象（遵守 `gdcc_ref_counted_init_raw` 的 init_ref + POSTINITIALIZE 模式）→ 按 slot-write 规则填入参数（borrowed 参数 retain / destroyable 拷贝）→ `mco_create`（`mco_desc` 由 `mco_desc_init(body_func, stack_size)` 初始化并设 `user_data` = 帧；`stack_size` 集中为常量，默认 **1MB 预留**——vmem 分配器下预留与提交分离，物理开销跟随实际使用高水位，1MB 仅占 64 位地址空间；取大值是因为引擎调用（Variant/ClassDB/正则/JSON 等）在协程栈上执行、栈深度不可控；第三步 vendor 时验证 minicoro vmem 提交时机语义；`mco_create` 失败属 OOM：发 runtime error → 类型化返回槽写入声明类型默认值（void 写 nil）→ `pack_result` → 置 `done` → 返回 OWNED done 状态（`co == NULL`，单通道不破坏；`free_instance` 容忍 `co == NULL`，跳过 `mco_destroy` 但照常销毁各字段））→ `mco_resume` → 按结果分派：
     - **内部 coroutine-start thunk**（GDCC 内部调用方）：总是交还 OWNED 状态对象引用——`mco_status(co) == MCO_DEAD` 时若尚未 finalize 则先 finalize（§3.3），状态对象以 `done` 形态返回（同步完成不再零状态，换取单通道 ABI；R8 懒物化留作 Post-MVP 优化）；
     - **ClassDB call/ptrcall wrapper**（engine 边界）：内部调 start thunk；若状态已 `done`（同步完成 fast path），经状态类自身生成的 `move_result` 访问器从类型化返回槽 **move** 出返回值（槽留合法 moved-from 态），释放状态对象引用，按声明类型直接返回——**engine 边界不实际挂起时零协程状态外泄**；
     - 挂起：按调用边界处理（§3.4）。

### 3.3 运行时结构（新增 `gdcc_coroutine`）

- `src/main/c/codegen/include_451/gdcc/minicoro.h` + `src/main/c/codegen/include_451/gdcc/minicoro.c`：vendor minicoro（`.c` 中 `#define MINICORO_IMPL` 后 include，保留上游 LICENSE 头注释；锁定使用汇编/虚拟内存后端，不使用 fiber 后端，见 R11）。
- `src/main/c/codegen/include_451/gdcc/gdcc_coroutine.h` + `gdcc_coroutine.c`：GDCC 自有协程 helper，`gdcc_*` 命名，按 `gdcc_runtime_lib.md` §Registering New Runtime-Provided Functions 登记。**通用逻辑全部在此；每函数特有的状态类 wrapper/注册代码由模板生成（R9）**：
  - `gdcc_coro_state_header`：所有状态类 wrapper 内的帧公共头（magic 常量、类描述符指针、`GDExtensionObjectPtr obj` 回指（创建时回填，供需要持有/发射对象的路径使用）、`mco_coro *co`、`done`、`cancel`、`godot_Variant result_cache`、waiter 链表头）。
  - `gdcc_coro_state_identify(godot_Object *obj)`：`godot_object_get_instance_binding(obj, gdcc_coro_binding_token, NULL)` + magic 校验 → 命中返回 header 指针，否则 `NULL`。专用 token（`gdcc_coroutine.c` 内的全局变量地址）保证与普通 `class_library` binding 互不干扰，纯 C、O(1)。
  - `gdcc_coro_await_signal(const godot_Signal *sig, godot_Variant *out, mco_coro *co, gdcc_coro_state_header *self)`：构造一次性 custom Callable（复用 `gdcc_callable.h` 的 `godot_callable_custom_create2` 模式，userdata 携带 `{co, out}` 并**持有 self 状态对象引用**——挂起协程的最终保活边，`free_func` 释放）→ `godot_Signal_connect(sig, callable, 4 /* CONNECT_ONE_SHOT */)` → 连接失败发 runtime error、填 nil、**不挂起直接返回**（对 Godot「连接失败后永久挂起」行为的有意偏离，写入文档）→ 成功则 `mco_yield(co)`；Callable 回调按 §2 参数规则写 `*out` 后 `mco_resume(co)`。yield 返回后先查 `cancel`（由生成的检查代码消费）。
  - `gdcc_coro_await_state(gdcc_coro_state_header *callee, void *out_typed, mco_coro *co, gdcc_coro_state_header *self)`：静态路径。`callee->done` → 经 `desc->copy_ret_slot(callee, out_typed)` 类型化拷贝结果直接返回（connect-after-done fast path）；否则把 `{co, out_typed, self 引用}` 登记进 callee 的 waiter 链表（typed-waiter 种类；callee → awaiter 引用边）。**consume 合同**：helper 总是消费调用点对 callee 的 OWNED 引用——登记路径在 `mco_yield(co)` **之前**释放（callee 由它自己的 wait 边保活，awaiter 不反向持有，保活图保持无环）；恢复时 `out_typed` 已被 finalize 经同一回调拷入。
  - `gdcc_coro_await_dynamic(godot_Variant *operand, godot_Variant *out, mco_coro *co, gdcc_coro_state_header *self)`：动态路径，三层分派见 §3.4；自己的状态对象命中且需挂起时，在 yield 前释放 operand 持有的 callee 引用并把 operand 重置为 nil（同上的无环规则）。
  - `gdcc_coro_finalize(gdcc_coro_state_header *state)`：仅完成路径的 `mco_resume` 返回点（thunk / signal 回调 / waiter 级联）检查 `MCO_DEAD` 并统一走此处；`gdcc_coro_cancel` 驱动的 resume **禁止**进入本函数。**不变量顺序**：① `desc->pack_result` 把类型化返回槽**拷贝**进 `result_cache`（Variant；copy 不 move，槽保持存活供类型化通道与 done fast path）；② 置 `done = true`——`done` 与 `result_cache` 必须在任何 resume/emit 之前对外可见；③ 逐个弹出 waiter：typed waiter 经 `desc->copy_ret_slot` 拷入其 `out_typed`、Variant waiter 经 `godot_variant_new_copy` 从 `result_cache` 拷入，**先 `mco_resume` waiter，再释放 waiter 引用边**（防止恢复前断开最后一条保活边）；④ 最后向外部监听者 `emit("completed", result)`。finalize 必须可重入（waiter 的 resume 可能级联触发嵌套 finalize）；级联导致的原生栈深度增长与 Godot VM 的 resume 链同构，接受并文档化（R7）。
  - `gdcc_coro_cancel(gdcc_coro_state_header *state)`：与 `gdcc_coro_finalize` **互斥**的放弃路径，挂接在状态类的 `NOTIFICATION_PREDELETE`（状态类 destructor 不得触碰帧字段）：`done` 或已 `cancel` 则直接返回；置 `cancel = true`；若 `mco_status(co) == MCO_SUSPENDED` 则 `mco_resume(co)`（body 在最近一个 await 恢复点直跳 `__finally__`，清理栈上 OWNED 值后以 `MCO_DEAD` 返回）；**禁止 finalize**：不置 `done`、不 pack `result_cache`、不 emit、不 resume waiter；waiter 链表只弹出并释放 awaiter 引用边（awaiter 永久挂起，对齐 Godot `cancel_pending_functions`）；类型化返回槽若被 cancel 路径的 `__finally__` 写入默认值也不进 `result_cache`，与所有路径一样由 `free_instance` 经 `desc->destroy_ret_slot` 恰好销毁一次。`free_instance` 只处理 `MCO_DEAD` 或 `co == NULL`（OOM）后的 `mco_destroy`（`co == NULL` 跳过）、始终构造态的 `result_cache`、返回槽（经 desc）与参数字段（生成代码）及 wrapper 释放。
  - PREDELETE 挂接的具体实现（复用现有用户类 notification 接线，`entry.c.ftl:53,164-229`）：
    - 模板为每个状态类生成 `create_instance2`（同构用户类创建路径，额外设置协程 token binding，callbacks 全 `NULL`）、`_class_notification`（`GDExtensionClassNotification2` 签名；`POSTINITIALIZE` → 构造 header/零初始化字段；`PREDELETE` → 只调 `gdcc_coro_cancel`，**不**走用户类的 `PREDELETE → _class_destructor` 路径）与 `free_instance`（`co != NULL` 断言 DEAD 并 `mco_destroy`，`co == NULL` 跳过 → 清 `result_cache`/经 desc 清返回槽/参数字段 → `mem_free`）。
    - 类型化字段的析构与取值不进通用 helper：类描述符 `gdcc_coro_state_desc` 携带生成的回调（`pack_result` / `copy_ret_slot` / `destroy_ret_slot` / `emit_completed`），finalize/await_state/free_instance 经 desc 调用；参数字段清理由生成的 per-class 代码在 `free_instance` 直接执行。
    - 通知常量经既有生成的 `godot_Object_NOTIFICATION_PREDELETE()` / `..._POSTINITIALIZE()` 访问器获取；`p_reversed` 忽略（状态类无 GDCC 继承链）。
- `CProjectBuilder` 需要把 `include/gdcc/minicoro.c` 与 `include/gdcc/gdcc_coroutine.c` 加入编译输入收集（落点 `CProjectBuilder.java:85-96`）；`gdcc_runtime_lib.md` 中「`gdcc/**` 为 header-only」的表述需同步改为允许这两个 `.c` TU。

### 3.4 await 的分派路径

LIR 新增指令（外观同步，写入 `gdcc_low_ir.md` 并配套 serializer/parser round-trip）：

```text
$<result_id> = await $<operand_id>
```

- **signal 路径**：operand 静态类型为 `GdSignalType`。frontend 按既有 signal 读取路径物化 Signal 值后接 `await` 指令；backend `AwaitInsnGen` 渲染 `gdcc_coro_await_signal(...)`；结果经既有 (un)pack 边界从 `Variant` 物化到 frontend 发布的结果类型。
- **static call 路径**：operand 是对已标记协程函数的 **instance 调用**（MVP 不含静态方法调用，见 §3.5）。`call_method` 指令本身不变；backend 的 call 生成器（`BackendMethodCallResolver` 的 `GDCC` dispatch）识别 callee 的 `isCoroutine` 标记，改走内部协程 ABI：调用 coroutine-start thunk，result 变量（必须存在，否则 fail-fast）以 `compiler::GdccCoroState` 声明并接收 OWNED 状态对象引用（同步完成时状态已 `done`）。紧随的 `await` 指令按 operand 静态类型分派渲染 `gdcc_coro_await_state(...)`，结果槽形态固定为：

  ```c
  // $state 的 C 槽：godot_Object*（compiler::GdccCoroState）
  gdcc_coro_state_header *callee = gdcc_coro_state_identify($state);
  $state = NULL; // 所有权转移给 await；源槽留 moved-from NULL
  // await 结果槽类型 = callee 声明返回类型（frontend 发布；void callee 为 Variant nil）
  gdcc_coro_await_state(callee, &<typed result slot>, _co, <self header>);
  // done fast path 与 waiter 恢复值均经 desc->copy_ret_slot 类型化写入结果槽；
  // await 返回后检查 cancel 标志（§3.6）
  ```

  void 协程：恢复通道写入合法 nil/默认值（不得为未初始化存储），由 `copy_ret_slot` 的 void 特化承载。backend validation：operand 既非 Signal/Variant 又非 `compiler::GdccCoroState` → `InvalidInsnException`；协程 call 缺 result → `InvalidInsnException`。
- **statement 位置调用协程函数（fire-and-forget）**：走同一内部协程 ABI——call 照常写入 `compiler::GdccCoroState` result 变量（命名 `__coro_state_<valueId>`，满足 `INTERNAL` provenance 命名限制），随后以 `INTERNAL` provenance `destruct` 该变量（释放调用点最后引用即 detach）——协程帧由自身 wait 边保活（对齐 Godot 由 connection 绑定 state 保活的语义），完成时引用归零自然销毁；无特殊 raw 指针通道，全部走普通生命周期机制。
- **dynamic 路径**：operand 为 `DYNAMIC`（Variant）时，frontend 发布 `Variant` 结果并强制标记协程；backend 渲染 `gdcc_coro_await_dynamic`，运行时三层分派：
  1. `TYPE_SIGNAL` → 提取 Signal 走 `gdcc_coro_await_signal` 路径；
  2. `TYPE_OBJECT` → 先校验对象存活（已释放 → runtime error，对齐 Godot `"Trying to await on a freed object."`）；再 `gdcc_coro_state_identify`：
     - 命中（自己的状态对象）→ `done` 则拷贝缓存结果立即返回（不触碰 operand），否则 C 层直接 waiter 登记（**不经过 Signal 机制**，单线程下 check-then-register 天然原子，connect-after-done 窗口不存在），并在 yield 前释放 operand 的 callee 引用、把 operand 重置为 nil；
     - 未命中（外部对象）→ 构造 `Signal(obj, "completed")` 并 connect one-shot，**connect 返回错误码即存在性检测**（无 `completed` 信号 → 穿透返回操作数本身，对齐 Godot redundant await 运行时形态；但 connect 之前的 OOM 分配失败不得穿透——发 runtime error、填 nil、不挂起）。注意这是**有意宽于 Godot 严格类判定**的 duck-type（§3.1）：对解释层 `GDScriptFunctionState` 行为与 Godot 逐字一致（含 connect-after-done 挂起）；对恰好带 `completed` 信号的无关对象会等待该信号，属已接受偏离。await 外部状态期间，awaiter 帧持有该对象 Variant（Signal 不保活 emitter，对齐 Godot `bind(retvalue)` 的保活方向）。
  3. 其他一切类型（含 nil）→ 穿透：`out` 拷贝 `operand` 立即返回。
- **engine 边界**（`entry.c.ftl` 的 call/ptrcall wrapper 调用协程入口）：存在**两类入口**——内部 coroutine-start thunk（GDCC 内部调用方专用，总是返回 OWNED 状态对象）与 ClassDB 注册的 call/ptrcall wrapper（保持 Godot 方法签名，内部调 start thunk 并自行处理同步完成 fast path）。挂起时按返回通道分派：
  - Variant call wrapper：把状态对象包成 `Variant(OBJECT)` 返回——外部可观察（解释层可 `await state.completed`，C++ 可手动 connect）；同步完成返回普通值。
  - void 方法（含 engine virtual，如 `_ready`）：释放状态引用（detach），协程后台继续，对齐 Godot 行为。
  - typed 非 Variant 返回：**detach + 零初始化值 + runtime error**（有意偏离：协程副作用在后台继续，但调用方既拿不到结果也拿不到状态）；文档建议 engine 可触达的协程方法声明为 void 或 `Variant` 返回，frontend 不做强制（无法静态知晓调用方）。

### 3.5 frontend await operand 分类（MVP）

| operand | 结果类型 | 诊断 | 是否标记协程 |
| --- | --- | --- | --- |
| RESOLVED `GdSignalType` | 0 参 → `Variant`；1 参 → 声明参数类型（经 unpack 边界）；多参 → `Array[Variant]`；参数类型未知 → `Variant` | 无 | 是 |
| RESOLVED instance call 且 callee 为已标记 GDCC 协程函数 | 非 void → callee 声明返回类型；void → `Variant`（恢复值为 nil，对齐 Godot `completed(nil)`） | 无 | 是 |
| RESOLVED 静态方法 call 且 callee 为已标记 GDCC 协程函数（**任何位置**，含 statement 根表达式） | — | compile gate error（backend `CallStaticMethodInsn` 无生成器，既有缺口；Post-MVP 解除） | — |
| RESOLVED call 且 callee 静态已知非协程 | callee 返回类型 | `sema.redundant_await` warning（对齐 Godot `REDUNDANT_AWAIT`），await 退化为纯穿透 | 否 |
| 其他静态已知非 signal 值 | — | error（fail-closed，diagnostic owner 为 expr analyzer 既有 category；对齐 Godot 的 warning 放宽留作 Post-MVP） | — |
| `DYNAMIC` operand | `Variant` | 无（运行时分派，见 §3.4 dynamic 路径） | 是 |
| lambda / property init / parameter default 内任何 await | — | error（既有 fail-closed 边界不变） | — |
| statement 位置调用协程函数（fire-and-forget，仅 instance） | —（结果丢弃） | 无（对齐 Godot root-expression 放行） | 否（调用方不挂起） |
| value 位置（非 await operand）调用协程函数 | — | compile gate error（对齐 Godot `must be called with "await"`） | — |

有意偏离：await 静态已知非协程调用**不**把 enclosing 函数标记为协程（无可挂起点，省协程开销）。该偏离仅影响 GDCC 调用方；解释层看 GDCC 方法时以 ClassDB 元数据为准，本 MVP 不发布 coroutine 标志，故解释层对这类函数的裸调/`await` 行为与 GDCC 一致（都立即拿到值）。

### 3.6 所有权与生命周期约束（对接 `gdcc_ownership_lifecycle_spec.md`）

- 协程帧与状态对象合一；**引用计数委托给引擎 RefCounted 机制**（`Variant(Object)` 存取自动 retain/release），不再发明手动 frame refcount。
- 保活边方向固定（无环）：
  - signal 等待：connection 的 custom Callable 持有**自己**状态对象的引用（根边，对齐 Godot `bind(retvalue)`）；
  - 协程链等待：callee 的 waiter 节点持有 awaiter 状态对象引用（callee → awaiter）；调用点对 callee 的 OWNED 引用由 `gdcc_coro_await_state` 在 waiter 登记完成后、yield 之前释放（dynamic 自己状态对象路径：yield 前释放 operand 持有的 callee 引用并重置 operand 为 nil）——callee 由它自己的 wait 边保活，awaiter 不反向持有，保活图保持无环；
  - dynamic 外部对象等待：awaiter 帧在整个挂起期间持有 operand Variant（即 emitter 对象）——Signal 连接不保活 emitter，对齐 Godot `bind(retvalue)` 的保活方向；自己的状态对象路径**禁止**这条边（否则回到保活环）；
  - 挂起链在最终完成时逐边释放；永久挂起的链泄漏（与 Godot 语义一致）。
- 挂起**不触发**返回路径；`__finally__` 只在 body 函数真实返回（含 cancel-resume 路径）时执行一次，局部变量整个生命周期由 minicoro 栈承载，backend 不得为协程函数发明第二套清理逻辑。
- 参数：帧的类型化参数字段是唯一 owning 存储（thunk 按 slot-write 规则填入）；**不生成参数 C 槽**——body 内参数 operand 直接映射帧字段，参数写入按普通 slot-write 规则作用于帧字段；`__prepare__` 不初始化参数，`__finally__` 不清理参数；参数字段由 `free_instance` 恰好清理一次，cancel 路径不触碰。
- **返回值存储状态机（不可违反）**：
  1. body `__finally__` 把 `_return_val` consume 进类型化返回槽（此后 `_return_val` 视为已清空）；
  2. `finalize`：`desc->pack_result` **拷贝**（不 move）进 `result_cache`，再置 `done`；类型化返回槽保持存活；
  3. 内部 start thunk：`MCO_DEAD` 时若尚未 finalize 先 finalize，然后**原样返回 OWNED done 状态**（不动返回槽）；仅 ClassDB wrapper 做同步 fast path：经状态类生成的 `move_result` 访问器从类型化返回槽 move 出结果（槽留合法 moved-from 态），然后 `release` 状态对象并按声明类型返回；
  4. `free_instance` 销毁始终构造态的 `result_cache`、经 `desc->destroy_ret_slot` 恰好销毁一次类型化返回槽（容忍未写入/moved-from）、参数字段、`mco_coro`。
- signal 恢复回调中的参数 Variant 只做拷贝，不 consume 引擎传入的 argv；Variant waiter 恢复值每人一份 `godot_variant_new_copy`，typed waiter 经 `desc->copy_ret_slot` 拷贝。
- `_return_val` 合同不变：协程 body 内仍只有 `__finally__` 写真实返回（写入帧返回槽视为该函数的「真实返回」）。
- **cancel-resume（放弃路径）**：挂接状态类的 `NOTIFICATION_PREDELETE`（非 `free_instance`）；`gdcc_coro_cancel` 与 finalize 互斥：置 `cancel` → `mco_resume` → body 在最近一个 await 恢复点检查到 `cancel` 直跳 `__finally__`（`_return_val` 已在 `__prepare__` 默认初始化）→ 清理栈上 OWNED 值后以 `MCO_DEAD` 返回；不置 `done`、不 pack、不 emit、不 resume waiter（waiter 仅释放引用，awaiter 永久挂起，对齐 Godot `cancel_pending_functions`）；返回槽被 cancel 路径写入的默认值留给 `free_instance` 的统一 `destroy_ret_slot` 销毁。每个 await 恢复点之后的 cancel 检查由 `AwaitInsnGen` 统一生成；cancel 路径不得调用任何用户代码。

### 3.7 风险登记与对策

| 编号 | 风险 | 对策 | 状态 |
| --- | --- | --- | --- |
| R1 | 隐藏状态类进入 ClassDB 全局命名空间，可能与用户 top-level 类撞名；现有 `RESERVED_PREFIXES`（`FrontendSyntheticPropertyHelperSupport.java:14-26`）只覆盖成员级 | 新增 class 级保留前缀 `_gdcc_coro_state_`，skeleton 拒绝用户类使用；状态类只有 canonical 名（派生公式 `_gdcc_coro_state_<canonicalClass>__coro__<func>`——保留序列 `__coro__` 分隔以消除单下划线拼接碰撞，宿主 inner class 已含 `__sub__` 拼接），无 sourceName、不可 extends、不进 source-facing registry；同步更新 `gdcc_facing_class_name_contract.md` 与 `frontend_rules.md` | 已纳入第一步 |
| R2 | 状态对象 ↔ 帧所有权环；wrapper 首字段合同与 `identify` 冲突 | 帧与状态对象合一，引用计数委托引擎 RefCounted；保活边方向固定（§3.6），无环；公共头经**独立 binding token** 暴露，wrapper 根字段为 `_object`（无 GDCC `_super` 链），公共头不占 offset 0 | 已纳入设计 |
| R3 | 挂起期间 `self` 等引擎对象死亡，恢复后访问已死实例 | MVP：依赖既有 `assert_object_live` 硬失败 + 文档化偏离；后续可用 `NOTIFICATION_PREDELETE` + 每实例 pending 帧列表实现主动取消（entry.h.ftl 已有 notification callback 挂点） | MVP 接受偏离，列 Post-MVP |
| R4 | connect-after-done | 静态路径由 `done` 标志 + `copy_ret_slot` 类型化拷贝立即返回，dynamic 自己状态对象路径由 `done` 标志 + `result_cache` 拷贝立即返回（均优于 Godot 的静默挂起）；finalize 顺序不变量保证 `done`/`result_cache` 先于任何 resume/emit 可见 | 已纳入设计 |
| R5 | 动态路径识别可等待对象 | 自己的状态对象：`gdcc_coro_state_identify`（专用 token + magic，纯 C）；外部对象：`connect` 返回错误码即存在性检测，失败穿透；duck-type 宽于 Godot 属有意偏离（§3.1） | 已纳入设计 |
| R6 | lambda 捕获语义（创建时拷贝）与帧活过 Callable 释放的矛盾 | capture block 改引用计数，Callable userdata 与每个在飞状态对象各持一份；语义不变，生命周期闭合；同一 Callable 并发调用各自独立状态对象 | MVP 后第一跟进项（lambda await 前提） |
| R7 | finalize 中恢复 waiter 的级联导致原生 C 栈深度增长 | 与 Godot VM resume 链同构，接受并文档化；必要时引入 trampoline 队列压平（不改语义） | 接受，列 Post-MVP 备选 |
| R8 | 协程调用统一创建 Godot 状态对象的开销（内部通道同步完成也创建，换取单通道 ABI） | MVP 统一创建（简单正确）；后续可做「Godot 对象懒物化」：帧先以纯堆 struct 存在，仅在挂起/外部边界需要时补建 Object 与 binding（类型合同不变，仅改 thunk 内部） | 列 Post-MVP 优化 |
| R9 | 每函数状态类的字段布局依赖模块 LIR，通用 runtime helper 无法承载 | 注册代码与 wrapper 由 `entry.c.ftl`/`entry.h.ftl` 生成（**专用生成循环**，不进 `module.classDefs` 用户循环）；`gdcc_coroutine.h/.c` 只承载通用逻辑 | 已纳入设计 |
| R10 | typed 非 Variant 返回的协程方法经 engine ptrcall 挂起时无法携带状态对象 | detach + 零初始化值 + runtime error（协程副作用后台继续，调用方拿不到状态——有意偏离）；文档建议 engine 可触达协程声明 void/Variant 返回；frontend 不强制 | 已纳入设计 |
| R11 | minicoro 后端选择：Windows 主线程若为 fiber 则 fiber 后端失败；引擎调用（Variant/ClassDB 等）在协程栈上执行，栈深度不可控 | vendor 时锁定汇编/虚拟内存后端（不用 fiber）；`stack_size` 集中为常量、默认 1MB vmem 预留（预留≠提交，物理开销跟随实际高水位；vmem 提交时机语义在 vendor 时对照源码验证）；e2e 含栈压测（await 前后多个局部 Array/Dictionary） | 已纳入第二、三、八步 |

---

## 4. 分步实施

每一步都必须保持可运行、可回归、可单独提交；前一步未验收不得进入下一步。**compile gate 的解封只发生在第八步**；第六、七步落地期间 gate 以 await 专用 blocker 继续拦截，lowering 单测在 pass 级构造输入，不依赖 gate 放行。

### 第一步：合同冻结与文档草案

- 状态：**已完成**（2026-08-23）
- 完成内容：合同落点为 `gdcc_low_ir.md` §Coroutine Instructions / §Functions（`is_coroutine` 属性）、`gdcc_runtime_lib.md` §Coroutine Runtime、`gdcc_ownership_lifecycle_spec.md` §3.10、`gdcc_facing_class_name_contract.md` §1.3、`frontend_rules.md`（class 级保留前缀）。2026-08-24 补登：`compiler::GdccCoroState` 类型边界入 `gdcc_type_system.md` §Compiler-only Types 与 `frontend_gdcompiler_type_implementation.md` §2.3（含 `godot_Object*` 存储例外与 move-only 合同）；五处旧文档 await 冲突表述已加 superseded 指针（`frontend_rules.md`、`frontend_signal_support.md`、`frontend_lowering_plan.md`、`frontend_chain_binding_expr_type_implementation.md`、`scope_architecture_refactor_plan.md`）。

目标：

- 冻结 LIR `await` 指令语法、`LirFunctionDef.isCoroutine` 属性（XML `is_coroutine`）、隐藏状态类命名与生成合同、runtime helper 命名、所有权状态机与 cancel-resume 合同。

建议实施内容：

- 在 `doc/gdcc_low_ir.md` 增加 `await` 指令章节与函数 `is_coroutine` 属性说明（标注为新增合同）。
- 在 `doc/gdcc_runtime_lib.md` 登记 `gdcc/minicoro.*` 与 `gdcc/gdcc_coroutine.*` 的布局、真实 minicoro API（`mco_desc_init` / `desc.user_data` / `mco_status` / `mco_create` 失败语义）、专用 binding token 与编译接线计划；修订「`gdcc/**` header-only」表述。
- 在 `doc/gdcc_ownership_lifecycle_spec.md` 增补协程状态对象/返回值存储状态机/参数 operand 直接映射帧 owning 字段（无参数 C 槽）/cancel-resume 的所有权条款（对应 §3.6）。
- 在 `gdcc_facing_class_name_contract.md` 与 `frontend_rules.md` 增补 class 级保留前缀 `_gdcc_coro_state_` 与状态类命名派生公式（R1/R15）。

验收细则：

- happy path：
  - 五份文档（`gdcc_low_ir.md`、`gdcc_runtime_lib.md`、`gdcc_ownership_lifecycle_spec.md`、`gdcc_facing_class_name_contract.md`、`frontend_rules.md`）的新章节与本计划 §3 无矛盾；命名遵守 `gdcc_*` 约定与 `common_rules.md`。
- negative path：
  - 不修改任何代码；`./gradlew classes --no-daemon --info --console=plain` 通过。

### 第二步：runtime 基础设施（minicoro + gdcc_coroutine + 构建接线）

- 状态：**已完成**（2026-08-23 初版；2026-08-24 合同迁移随第四步完成）：初版与新合同的差异（desc 增 `copy_ret_slot`、waiter 分 typed/Variant 种类、`gdcc_coro_await_state` 改 `void *out_typed`、`pack_result` 从「打包并清零返回槽」改为「拷贝进 `result_cache` 并保留返回槽」、finalize 按 waiter 种类分派、cancel 不再经 `destroy_ret_slot`、`free_instance` 统一销毁返回槽且容忍 `co == NULL`）已全部迁移完毕，迁移锚点见第四步状态。
- 完成内容（初版）：产出 `include_451/gdcc/minicoro.h` / `minicoro.c`（vendored；pinned commit 与锁定配置见 `gdcc_runtime_lib.md` §Coroutine Runtime）、`gdcc_coroutine.h` / `gdcc_coroutine.c`、`CProjectBuilder` 编译接线。测试覆盖 `CProjectBuilderCoroutineRuntimeInputTest`（编译输入锚点）、`GdccCoroutineRuntimeSmokeTest`（zig-gated 纯 C 层：minicoro 往返、`mco_create` OOM、finalize 不变量与级联、cancel 链上放弃、identify 拒绝、dynamic 非 engine 分支）；既有 `CProjectBuilderSharedIncludeTest` / `ApiCompilePipelineTest` 的 `cFiles` 精确列表断言已按新合同翻转。

目标：

- 生成项目能够编译并链接 minicoro 与 `gdcc_coroutine` helper，纯 C 层协程往返、finalize 不变量与 cancel-resume 可用。

建议实施内容：

- vendor `minicoro.h` / `minicoro.c`（确认上游许可证并保留 LICENSE 头注释；锁定 vendored 版本 commit 与汇编/虚拟内存后端，记录在 `gdcc_runtime_lib.md`）。
- 实现 `gdcc_coroutine.h` / `gdcc_coroutine.c`：公共头布局、专用 binding token、`gdcc_coro_state_identify`、`gdcc_coro_await_signal`、`gdcc_coro_await_state`（含 done fast path 与调用点释放纪律）、`gdcc_coro_await_dynamic`（三层分派）、`gdcc_coro_finalize`（§3.3 不变量顺序、可重入）、`gdcc_coro_cancel`。
- 修改 `CProjectBuilder`（`:85-96`），把 `include/gdcc/minicoro.c`、`include/gdcc/gdcc_coroutine.c` 加入编译输入。

验收细则：

- happy path：
  - `CProjectBuilder` 单测断言两个新 TU 出现在 `cFiles`；资源提取后文件存在于生成项目 `include/gdcc/`。
  - 新增 zig-gated C 层 smoke 测试（无 zig 时 assumption skip）：手工 C fixture 创建协程 → 内部 `mco_yield` → resume 完成 → 断言状态序列与返回值。
  - 纯 C 层覆盖：`gdcc_coro_await_state` 的 done fast path；finalize 顺序不变量（done/result 先于 resume/emit 可见）；typed waiter 经 `copy_ret_slot`、Variant waiter 每人一份 `godot_variant_new_copy`，且 resume 先于边释放；嵌套 finalize 重入；cancel-resume 能跑到清理逻辑；**链上放弃 fixture**：`A await B` 后释放 B 的最后引用 → B 经 cancel-resume 清理，A 永不恢复、无 emit、无泄漏；`gdcc_coro_state_identify` 对非状态对象（无 token binding）的拒绝。需要真实 Godot 对象的分支（signal connect、dynamic 外部层）留到第八步 e2e。
- negative path：
  - 无 zig 环境时测试 skip 而非失败；不改动既有 `godot_binding.c` 聚合结构；`mco_create` 失败返回 OOM 错误码且不残留半创建协程的 fixture 断言（thunk 侧的「runtime error → 默认值写槽 → `pack_result` → 置 `done` → 返回 `co == NULL` 状态对象」生成代码断言在第四步补齐，见第四步 negative path）。

### 第三步：LIR 模型与序列化

- 状态：**已完成**（2026-08-23；2026-08-24 补 `compiler::GdccCoroState` 类型落地）
- 完成内容：`GdInstruction.AWAIT`（`ReturnKind.REQUIRED`、单 `VARIABLE` operand，`GdInstruction.java` Coroutine 段）；`lir/insn/AwaitInsn.java`（result + operand，record 紧凑构造函数 NPE 拒绝 null；实现分组标记接口 `lir/insn/CoroutineInstruction.java`——与既有指令家族分组约定对齐，且不可为 `ControlFlowInstruction`）；`ParsedLirInstruction` 新增 AWAIT 分支（缺 result 时 `LirInsnParsingException` fail-fast，同 `BUILTIN_CAST` 先例）；`LirFunctionDef.isCoroutine` 属性（不入 bulk 构造函数、默认 `false`，同 `entryBlockId` 先例，setter/getter 读写）；`DomLirSerializer`/`DomLirParser` 读写
- 2026-08-24 增补（显式状态类型落地）：`type/GdccCoroStateType.java` 单例（擦除标记类型；`godot_Object*` 存储例外；move-only）；`GdCompilerType` 增 `isCopyable()` 与 move-only 校验分支、permits 登记；`DomLirParser.tryParseCompilerOnlyType` 注册；`CGenHelper.renderCopyAssignFunctionName` 与 `CBodyBuilderAliasSafetySupport` 增 move-only fail-fast；runtime 增 `gdcc_coro_state_slot_init`（nullary call-and-assign）/ `gdcc_coro_state_slot_destroy`（按地址、容忍 NULL）并登记入 `gdcc_runtime_lib.md`；backend 注册 `CCoroStateRawInitIntrinsic`（prepare 块 `$slot = gdcc_coro_state_slot_init();`）。
- 测试锚点：`AwaitInsnContractTest`（opcode/operand 形状/精确序列化文本/解析字段/round-trip/分组接口/非 terminator 与块结构完整性/null 字段 NPE/缺 result、缺 operand、多 operand、错误 operand 类型四类 parse fail-fast）；`DomLirSerializerTest.serialize_module_writesCoroutineAttributeAndAwaitInsnRoundTrip`（`is_coroutine="true"` 属性 + await 指令同函数 XML round-trip）与 `serialize_module_coroutineDefaultsToFalseAndRoundTrips`（默认 `false` 往返）与 `serialize_module_coroStateVariableRoundTripsThroughXml`（`compiler::GdccCoroState` 变量 round-trip）；`DomLirParserTest.parse_legacyXmlWithoutCoroutineAttributeReadsAsFalse`（缺失属性的向后兼容锚点）、`parse_allowsCoroStateTypeOnFunctionVariables` / `parse_rejectsCoroStateTypeOnPublicAbiSurfaces`（类型使用位正反例）、`parse_explicitCoroutineFalseAttributeReadsAsFalse` / `parse_wronglyNamedCoroutineAttributeDoesNotEnableMarker`（属性名精确性与 lenient 约定锚点）、`parse_awaitInsnFromXml` / `parse_awaitWithoutResultFailsFast`（XML 层 await 正反例）；`GdccCoroStateTypeTest`（单例协议/存储例外/move-only 合同）；`GdCompilerTypeTest`（permits 集合、storage 例外、move-only 校验分支）；`LirPublicAbiValidatorTest.coroStateTypeFollowsSameCompilerOnlyBoundaries`；`CDestructInsnGenTest.destructCoroStateShouldCallSlotDestroyHelper`；`CPackUnpackVariantInsnGenTest`（pack 源/unpack 目标两反例）；`CAssignInsnGenTest.assignCoroStateShouldFailFast`（move-only assign 负例）；`CCodegenTest.generateShouldEmitCoroStatePrepareInitCallForLocalVariables`（prepare 初始化正例）；`GdccCoroutineRuntimeSmokeTest.coroStateSlotHelpersShouldHonorNullContract`（zig-gated C 层 slot helper NULL 合同）。

目标：

- `LirFunctionDef` 支持 `is_coroutine` 属性；新增 `await` 指令对象、opcode 注册、XML 序列化/解析。

建议实施内容：

- `GdInstruction` 增加 `AWAIT`；新增 `gd/script/gdcc/lir/insn/AwaitInsn.java`（result + operand）。
- `LirFunctionDef` 增加 `is_coroutine` 属性；XML 读写、LIR validation（operand 必须存在；result 类型规则委托 backend generator 校验）。

验收细则：

- happy path：
  - serializer/parser round-trip 测试同时覆盖 `await` 指令与 `is_coroutine` 函数属性。
  - `entryBlockId`、terminator 完整性测试不受影响（await 不是 terminator）。
- negative path：
  - 缺 operand / 缺 result 的非法形式在解析或 validation 阶段 fail-fast。

### 第四步：backend 协程函数 codegen 与隐藏状态类生成

- 状态：**已完成**（2026-08-24；review-expert-a 三轮复审，第 3 轮 APPROVE）
- 完成内容：
  - **runtime 合同迁移**（第二步初版 → 新合同）：`gdcc_coro_state_desc` 增 `copy_ret_slot`；waiter 节点分 `GDCC_CORO_WAITER_TYPED` / `GDCC_CORO_WAITER_VARIANT`（`out` 改 `void *`）；`gdcc_coro_await_state` 签名改 `void *out_typed`（done fast path 经 `desc->copy_ret_slot`；失败路径保留 out 默认值，不再写 nil Variant）；`gdcc_coro_finalize` 按 waiter 种类分派（typed 经 `copy_ret_slot`，Variant 经 `godot_variant_new_copy`）；`gdcc_coro_cancel` 移除 `destroy_ret_slot` 调用；`GdccCoroutineRuntimeSmokeTest` 按新合同翻转（fixture 改 `int64_t` typed 槽 + `copy_ret_calls` 计数；finalize 混合 typed/Variant waiter；pack 保留返回槽锚点；cancel 的 destroy 归属 free_instance 的相分离锚点；NULL callee typed 失败路径负例）。
  - **隐藏状态类生成循环**（`entry.h.ftl` / `entry.c.ftl`，与 `module.classDefs` 用户类循环并列，`helper.hasCoroutineFunctions()` 门控保证 sync-only 模块输出逐字节不变）：wrapper struct（`_object` 根字段 + `gdcc_coro_state_header` + `_coro_param_*` 类型化参数字段 + `_coro_ret`/`_coro_ret_initialized` 返回槽，非 void 才有返回槽）；`create_instance2`（双 binding：标准 `class_library` + `gdcc_coro_binding_token()`）；`_class_notification`（POSTINITIALIZE → `gdcc_coro_state_header_init` + flag 清零；PREDELETE → 仅 `gdcc_coro_cancel`，无用户 destructor 路径）；`free_instance`（参数字段逐一释放 → `destroy_ret_slot` 恰好一次 → `gdcc_coro_state_free` → `mem_free`）；desc 四回调（`pack_result` 拷贝进 `result_cache` 保留槽 / `copy_ret_slot` 按 primitive/value-semantic/object 三类 slot-write / void 特化写 nil Variant / `destroy_ret_slot` 容忍未写入与 moved-from / `emit_completed` 经 `godot_Signal_emit`）；`move_result`（move 出返回槽并清 flag）；ClassDB 注册（`is_exposed = false`、`is_runtime = true`、`godot_classdb_register_extension_class5`、父类 `RefCounted`）；`completed(result)` 信号（`renderCoroCompletedSignalMetadata()` → `NIL_IS_VARIANT`）。
  - **body 函数与参数帧映射**：`void <Class>_<func>__coro_body(mco_coro *_co)`；新增 `CCoroutineFrameContext`（帧拼写单一来源：`_coro_state`/`_co`/`_coro_header`/`_coro_ret`/`_coro_ret_initialized`/`_coro_param_` 前缀）；`CBodyBuilder` 持可空上下文，`renderVariableStorageExpr`/`isEffectivelyRef` 把参数 operand 渲染为帧字段（无参数 C 槽、可写、`&` 取址按非 ref 规则）；`__finally__` 的 `returnTerminal` 改为 consume `_return_val` 进 `_coro_ret`（move + flag）后 `return;`；`__prepare__/__finally__` 既有参数跳过逻辑自然生效。
  - **入口 thunk 与 engine 边界**：`godot_Object* <Class>_<func>__coro_start(...)`（create_instance2 + `gdcc_ref_counted_init_raw` → 参数按 borrowed retain/拷贝填入帧 → `mco_create`（OOM → runtime error → 默认值写槽 → pack → 置 done → 返回 `co == NULL` 的 OWNED 状态对象）→ `mco_resume` → `MCO_DEAD` 时 `gdcc_coro_finalize` → 返回 OWNED 状态对象）；engine 入口函数**保持与同步函数同名同签名**（`<Class>_<func>`），内部调 start thunk 后三分支（done → `move_result`；挂起 + void → detach；挂起 + Variant → 状态对象包 Variant；挂起 + typed 非 Variant → detach + 零值 + runtime error），因此 BindingData、call/ptrcall wrapper、virtual 接线零变化。
  - **R1 class 级保留前缀**：`FrontendClassNameContract` 增 `CORO_STATE_CLASS_PREFIX`/`CORO_STATE_CLASS_SEPARATOR` 与 `reservedSequenceOrNull`/`startsWithCoroStateClassPrefix`（`__coro__` 与 `__sub__` 同输入边界）；`FrontendClassSkeletonBuilder` 的 top-level/inner header discovery 拒绝 `_gdcc_coro_state_` 前缀 sourceName（`sema.class_skeleton` + 跳过 subtree）。
  - **Java 支撑**：`CGenHelper` 协程命名/拼写 helper 与 `renderCoroParamFillStmt`/`renderCoroCopyRetStmt`/`renderObjectRetainStmt`/`renderCoroCompletedSignalMetadata`；`CCodegen` 注入帧上下文 + `validateCoroutineMarkers`（lambda 协程标记 fail-fast）；`gdcc_c_backend.md` compiler-only 存储表述缩窄（`godot_Object*` 例外）。
  - **复审修复轮**（review-expert-a 两轮，均已修复并由测试/编译锚定）：第 1 轮——① `entry.c.ftl` desc 定义移到 `create_instance` 之前（notification 取其地址；原顺序「先用后声明」无法通过 C 编译）→ 由 zig 实际编译锚定；② `.ref()` 借引用拒绝漏网两处 → 帧参数豁免（`IndexStoreInsnGen` 的 value-semantic self 写回路径、lambda capture 的 `"$"+id` 槽拼写 → 改经 `bodyBuilder.valueOfVar` 帧感知渲染 + `isEffectivelyRef`；`CGenHelper.renderLambdaCaptureCopyFromSlot` 签名改 `(type, sourceExpr, effectivelyRef)`；`CBodyBuilder.isCoroutineFrameParameter`/`isEffectivelyRef` 转 public）；③ `renderCoroCopyRetStmt` object 分支改 alias-safe 顺序（capture-old → assign → retain-new → release-old，与 `CBodyBuilder.emitObjectSlotWrite` 同一纪律）。第 2 轮——④ `ConstructInsnGen` 的 lambda `object_id` 残留 `$self.instance_id` 硬编码 → 改 `bodyBuilder.valueOfVar(selfVar) + ".instance_id"`（方法 lambda 必捕获 self，协程 body 无 `$self` 槽，原样会生成未声明标识符）。
- 测试锚点：`CCoroutineStateClassCodegenTest`（状态类注册/双 binding/信号 metadata/desc 回调/body 帧映射/thunk 三分支与 OOM/engine 入口三分支/void 特化/同模块同步函数不变/sync-only 模块零协程表面；**顺序锚点**：int/Variant engine done 分支 `move_result` 先于 `release_object`、thunk self 填入先于 retain、String `copy_ret_slot` destroy 先于 copy、object `copy_ret_slot` capture→assign→retain→release 严格顺序；**帧参数正向锚点**：String 参数 `variant_set` 写回不触借引用拒绝且 pack/writeback 寻址帧字段、lambda 捕获 self/String 参数经帧字段 + `object_id` 取 `_coro_param_self.instance_id`，同步形态 `$self.instance_id`/`$self` 槽不出现在协程 body；负例：lambda 协程标记 IAE、shell 协程 `InvalidControlFlowGraphException`、builder 级参数帧映射）；`CCoroutineGeneratedCSyntaxSmokeTest`（zig 门控：全分支 fixture 模块——含 lambda 捕获协程——的生成 `entry.c` 以 `zig cc -c` 实际编译通过，锚定声明顺序/类型正确性；zig 0.16 的 `-fsyntax-only` 对 quoted include 误报 `FileNotFound`，故用 `-c`）；`GdccCoroutineRuntimeSmokeTest`（新合同翻转，7 tests，zig 环境 skipped=0）；`FrontendClassHeaderDiscoveryTest`（top-level/inner 前缀拒绝、`__coro__` 序列拒绝）；`ApiCanonicalNameMapTest`（mapping key/value 的 `__coro__` IAE）；既有同步 codegen 测试全部保持绿色。
- 已知跟进项（复审第 3 轮遗留 SUGGESTION，当前不可达、非行为错误）：各 insn gen 对**指令 result 槽**的 `resultVar.ref()` 直判未统一为 `bodyBuilder.isEffectivelyRef`（`ConstructInsnGen`/`CallGlobalInsnGen`/`CallMethodInsnGen`/`OperatorInsnGen`/`IndexLoadInsnGen`/`LoadPropertyInsnGen`/`NewDataInsnGen`）。frontend lowering 写参数恒走 temp + `AssignInsn`（已帧感知），result 不会直指参数槽；手写 LIR 若违反该合同，result 槽 fail-fast 是保守安全方向。若未来 lowering 允许 result 直指参数，需统一改 `isEffectivelyRef` 并让 `NewData` in-place 分支不对已初始化帧字段跳 destroy。

目标：

- `is_coroutine="true"` 的函数生成「入口 thunk + body 函数 + 隐藏状态类」；同步函数 codegen 零变化。

建议实施内容：

- `func.ftl` / `entry.c.ftl` / `entry.h.ftl` 增加协程**专用生成循环**（与 `module.classDefs` 用户类循环并列）：状态类 wrapper struct（`_object` 根字段 + 帧字段）、`create_instance2`/`free_instance`（双 binding；`free_instance` 接受 `MCO_DEAD` 或 `co == NULL`（OOM）：`co != NULL` 断言 DEAD 并 `mco_destroy`，`co == NULL` 跳过，其余字段照常清理）、`NOTIFICATION_PREDELETE` 开头调用 `gdcc_coro_cancel`（destructor 不触碰帧字段）、ClassDB 注册（`is_exposed = false`、`is_runtime = true`）、`completed(result)` 信号注册（经 `CGenHelper.renderSignalParameterMetadata(Variant)`，自动携带 `NIL_IS_VARIANT`）、body 函数签名与参数 operand 到帧字段的直接映射、返回槽写回、入口 thunk（双入口签名 + `mco_create` / `mco_resume` / `MCO_DEAD` fast path / 边界分派）。
- **runtime 合同迁移**（第二步初版 → 新合同）：`gdcc_coro_state_desc` 增 `copy_ret_slot`；waiter 节点分 typed/Variant 两种；`gdcc_coro_await_state` 签名改 `void *out_typed`；`pack_result` 从「打包并清零返回槽」改为「拷贝进 `result_cache` 并保留返回槽」（typed waiter 与 done fast path 正确性的前提）；`gdcc_coro_finalize` 按 waiter 种类分派（typed 经 `copy_ret_slot`，Variant 经 `godot_variant_new_copy`）；`gdcc_coro_cancel` 移除对返回槽的 `destroy_ret_slot` 调用；`free_instance` 统一销毁返回槽（恰好一次）并容忍 `co == NULL`；`GdccCoroutineRuntimeSmokeTest` 按新合同翻转（typed waiter 拷贝、finalize 后返回槽仍存活可供 typed waiter 与 done fast path 复制、cancel 不触碰返回槽、OOM done 状态、`co == NULL` free_instance、返回槽全程仅由 `free_instance` 销毁一次）。
- `CCodegen` / `CBodyBuilder` 提供「当前函数为协程 body」上下文，供后续 `AwaitInsnGen` 渲染 `_co` 与帧访问。
- engine 边界 wrapper 对协程函数接入口 thunk，按 §3.4 engine 边界三分支处理挂起返回。
- skeleton 拒绝用户类名使用 `_gdcc_coro_state_` 前缀（R1）。

验收细则：

- happy path：
  - backend 单测用手写 LIR（无 frontend）断言：状态类注册含 `is_exposed = false` 与 `is_runtime = true`、走 `godot_classdb_register_extension_class5`、`completed` 信号参数经 `renderSignalParameterMetadata`（断言含 `godot_PROPERTY_USAGE_NIL_IS_VARIANT`，不与裸整数比相等）、类名前缀 `_gdcc_coro_state_`、创建路径设置两个 instance binding、body 函数以 `mco_coro *_co` 签名生成、thunk 含 `mco_status(...) == MCO_DEAD` 分支、`__prepare__`/`__finally__` 仍只在 body 函数内出现一次、参数 operand 直接映射帧字段（无参数 C 槽、无 memcpy）、cancel 挂接在 `NOTIFICATION_PREDELETE` 且状态类 destructor 不触碰帧字段。
  - 同步函数的既有 codegen 测试全部保持绿色（逐字节不变）。
- negative path：
  - 协程函数缺 entry block / shell-only 时沿用既有 fail-fast；不得为协程函数静默补 body。
  - 用户类名使用 `_gdcc_coro_state_` 前缀 → skeleton 发既有 category 的诊断并跳过该 subtree。
  - thunk 的 `mco_create` 失败路径锚点（第二步只验证了 OOM 返回语义，本步补「runtime error → 默认值写槽 → `pack_result` → `done` → 返回 OWNED 状态对象（`co == NULL`）」的生成代码断言）。

### 第五步：`AwaitInsnGen`（signal / static call / dynamic 三路径 + cancel 检查）

- 状态：**已完成**（2026-08-24）
- 完成内容：
  - **新增 `AwaitInsnGen`**（注册进 `CCodegen` opcode 表）：三路径纯静态类型分派——`Signal` operand 渲染 `gdcc_coro_await_signal(&$sig, &out_temp, _co, &_coro_state->_coro_header)`（resume 值经未初始化 Variant temp 中转，helper 是 raw-overwrite 语义）；`compiler::GdccCoroState` operand 渲染 `gdcc_coro_state_identify($state)` → `$state = NULL;`（call 前置 moved-from，cancel-resume 不会观察到悬垂引用）→ `gdcc_coro_await_state(callee, &<typed result slot>, _co, ...)`；`Variant` operand 渲染 `gdcc_coro_await_dynamic(&$operand, &out_temp, _co, ...)`。每条路径 helper 调用后立即渲染 cancel 检查 `if (_coro_state->_coro_header.cancel) { goto __finally__; }`（新增 `CCoroutineFrameContext.cancelFlagExpr()` 拼写单一来源），signal/dynamic 路径的结果物化在 cancel 检查之后（cancel 时结果通道未写入）；Variant 结果经 `godot_new_Variant_with_Variant` callAssign（既有 slot-write：先 destroy 旧值）、typed 结果经既有 `unpackVariantAssign` unpack 边界、state 路径由 desc `copy_ret_slot` 直接写 typed 槽（无 Variant 往返）。
  - **协程调用 ABI**：`BackendMethodCallResolver.ResolvedMethodCall` 新增 `coroutine` 字段（GDCC + `LirFunctionDef.isCoroutine()` 时 `cFunctionName` 渲染为 `renderCoroStartThunkName(...)`）；`CallMethodInsnGen` 新增 `emitCoroutineStartCall` 分支——调 start thunk 并以 `GdccCoroStateType.CORO_STATE` 写 result；fail-fast：static 协程（Post-MVP）、vararg 协程（thunk 定参签名）、缺 result、result 非 `compiler::GdccCoroState`。statement 位置 fire-and-forget 走普通生命周期：call 写 `__coro_state_<valueId>`（`__` 前缀满足 INTERNAL 命名限制）+ INTERNAL provenance `destruct` → `gdcc_coro_state_slot_destroy` 释放即 detach。
  - **await 负例 fail-fast**：非协程函数内 await；operand 非 Signal/Variant/GdccCoroState；compiler-only result；dynamic 路径 result 非 Variant 或 result 与 operand 别名（helper 会 reset operand 槽）；state 路径 ref operand（单消费者 owning 槽会被置 NULL）。
  - 测试锚点：`AwaitInsnGenTest`（16 tests：三路径字符串锚点 + 顺序锚点（identify<NULL 重置<await_state<cancel 检查；helper<cancel 检查<结果物化<temp destroy）+ overwrite 旧值 destroy 锚点 + 帧参数 result/operand 寻址 + 10 个负例）；`CallMethodInsnGenTest` 新增 7 tests（start thunk 调用/参数透传/overwrite 先 `gdcc_coro_state_slot_destroy`/statement-detach 顺序锚点/4 负例；`assertOrdered` 为 forward-scan 严格顺序语义，正确处理 overwrite destroy 与 detach destroy 同串两次出现）；`CCoroutineGeneratedCSyntaxSmokeTest` fixture 新增 `run_all` 协程（signal/state(Variant+int)/dynamic/INTERNAL destruct 全路径），zig 实际编译通过（skipped=0）。既有同步 codegen 测试全绿。
  - 说明：`godot_Signal_connect(..., 4)` 与 `mco_yield` 按冻结设计位于 runtime TU（`gdcc_coroutine.c`），由第二步 `GdccCoroutineRuntimeSmokeTest` 锚定；生成代码侧锚点为 `gdcc_coro_await_*` 调用与 cancel 检查分支（`awaitShouldNotLeakSyncOnlyShapes` 反向断言生成代码不含 `godot_Signal_connect`/`mco_yield`）。

目标：

- backend 消费 `await` 指令生成各路径的 C 代码，并在每个恢复点生成 cancel 检查。

建议实施内容：

- 新增 `AwaitInsnGen`（注册进 `CCodegen` opcode 表）：signal 路径渲染 `gdcc_coro_await_signal`；static call 路径按 §3.4 渲染 `gdcc_coro_await_state`（operand 为 `compiler::GdccCoroState`，typed `out_typed` 通道）；dynamic 路径（operand 为 `Variant`）渲染 `gdcc_coro_await_dynamic`；每条路径在恢复点后渲染 `cancel` 检查（置位则跳 `__finally__`）。
- `CallMethodInsnGen` / `BackendMethodCallResolver` 的 `GDCC` dispatch 识别 callee `isCoroutine` 标记，改走内部协程 ABI（调 coroutine-start thunk，result 变量必须存在且以 `compiler::GdccCoroState` 声明）；**静态方法协程调用不在本步**（无 `CallStaticMethodInsn` backend；本步 generator 不接该形态，手写 LIR 中出现协程相关 `CallStaticMethodInsn` 一律 fail-fast）。
- statement 位置的协程调用：call 照常写入 `compiler::GdccCoroState` result 变量（命名 `__coro_state_<valueId>`，满足 `LifecycleInstructionRestrictionValidator` 对 `INTERNAL` provenance 的 `__` 前缀/纯数字命名限制；该值是 compiler-only 单消费者值，不走 `cfg_tmp_*` 命名），随后以 `INTERNAL` provenance `destruct` 该变量即 detach——无特殊 raw 指针通道，全部走普通生命周期机制。
- 结果的类型化物化复用 `CBodyBuilder` 既有 slot-write helper（static call 路径为 `copy_ret_slot` 直接写入类型化槽，无 Variant pack/unpack）。

验收细则：

- happy path：
  - 生成代码字符串锚点测试：`godot_Signal_connect(..., 4)`、`mco_yield`、每个 await 后的 `cancel` 检查分支、`gdcc_coro_await_state` 的 typed `out_typed` 通道、await 后源状态槽清为 moved-from `NULL`、statement-detach（`destruct` + `INTERNAL` provenance → release）均出现；结果物化走既有 slot-write helper。
  - 手写 LIR → C 的端到端 codegen 测试覆盖 signal / 同步完成 call / 挂起 call / dynamic / statement-detach 五种形态；done fast path 与 waiter resume 均覆盖目标槽预含非默认值（需按 slot-write 纪律覆盖旧值）的用例。
- negative path：
  - `await` 出现在非协程函数 → `InvalidInsnException`；operand 既非 Signal/Variant 又非 `compiler::GdccCoroState` → `InvalidInsnException`；协程 call 缺 result → `InvalidInsnException`；`compiler::GdccCoroState` 被 assign/pack/传参/return/存储/ref 消费 → `InvalidInsnException`；未注册 opcode fail-fast。

### 第六步：frontend 语义与诊断分类（compile gate 保持拦截）

- 状态：**已完成**（2026-08-25）
- 完成内容：
  - **await 专用分类器**：`FrontendExpressionSemanticSupport.resolveAwaitExpressionType(...)`（纯函数，不发诊断不写表）——operand 先经 nested resolver 解析、非稳定结果原样传播；`DYNAMIC`/静态 `Variant` operand → `RESOLVED(Variant)`（对齐 unary/binary 的 Variant runtime-dynamic 先例）；`GdSignalType` → 0 参 `Variant`/1 参声明类型/多参 `GdArrayType(Variant)`（本类型系统中即 generic Array）；exact call → 立即发布 callee 返回类型（void callee 一律 `Variant`）并携带 `FrontendResolvedCall` 交给 owner；其余静态已知值 → `UNSUPPORTED`。`resolveRemainingExplicitExpressionType` 的 await 分支已移除并转入 dedicated-resolver 拒绝清单。
  - **owner 接线**（`FrontendBodyOwnerProcedures`）：`computeExpressionType` 新增 `AwaitExpression` 分支；fail-closed 边界（lambda body / property initializer → `sema.unsupported_expression_route`，由既有 `reportExpressionDiagnostic` 自动发射）；signal/dynamic 路由经 `requireEnclosingCallableFunction()`（name+static+arity 匹配 skeleton，同 `FrontendCallableReturnTypeSupport` 模式）把 enclosing `LirFunctionDef` 记入 `FrontendAnalysisData.coroutineFunctions`；exact-call operand 一律记录 `FrontendAwaitCallPending`（新 record：await 节点/enclosing/callee/callKind/sourcePath）——**不做现场协程判定**，因为 callee body 可能晚于 caller 解析。
  - **跨 owner 不动点**：新 `FrontendAwaitCoroutineAnalyzer` 接入 `FrontendSemanticAnalyzer` 管线（suite resolution 之后、annotation usage 之前；内部实例化，无注入构造签名变更）：单调集合上迭代至无进展，callee 已标记 → 标记 enclosing（传递链、前向引用均覆盖）；static coroutine call 消耗但不标记不告警（compile gate 拥有该诊断）；其余剩余 pending → `sema.redundant_await` warning（engine/builtin/utility/GDCC 非协程 callee 统一单出口；非 `FunctionDef` 锚点的 exact call 由 owner 立即告警）。跨模块场景不存在（当前无跨模块元数据共享，`ClassRegistry` 仅含本模块 GDCC 类），无需处理。
  - **compile gate**（`FrontendCompileCheckAnalyzer`）：`walkExpression` 新增 `AwaitExpression` 专用 blocker（分类合法但 lowering 未就绪；跳过 operand 子树避免重复诊断）；新增语句根位置跟踪（`walkingStatementRootExpression`，仅 `ExpressionStatement` 直接根表达式消费）+ `checkCoroutineCallPosition`：callee 经 `resolvedCalls`（bare call 键 `CallExpression`、链式键末位 `AttributeCallStep`）命中 `coroutineFunctions` 时——static → 任意位置报 `sema.compile_check`；instance 非语句根 → 报「is a coroutine, so it can't be called without 'await'…」（对齐 Godot）；语句根 instance → 放行（fire-and-forget）。
  - **side table**：`FrontendAnalysisData` 新增 `coroutineFunctions`（`LirFunctionDef` identity 单调集合，键选型理由：所有消费方持有的都是 `FunctionDef`——resolvedCall declarationSite / lowering shell；非 AST-keyed 因 `FrontendAstSideTable` 拒绝非 Node 键且无需 patch 冲突检测）与 `awaitCallPendings`（transient 工作列表，post-pass 消费并清空，非 lowering 事实）。
  - **文档**：`diagnostic_manager.md` 登记 `sema.redundant_await`。
  - 测试：`FrontendAwaitSemanticTest`（新，14 tests：signal 0/1/多参与 engine signal、dynamic operand、协程调用（caller-before-callee 前向引用）、void 协程 Variant 结果、三级传递链、GDCC/engine 非协程 warning 穿透且不标记、静态纯值/lambda/property-init 负例（category + 坏子树跳过 + 兄弟子树继续）、compile gate 四例（专用 blocker、value 位置、语句根放行、static 任意位置））；`FrontendExpressionSemanticSupportTest` 翻转 deferred 断言并新增 5 个分类器纯单测（signal 参数计数、dynamic/Variant、exact call pending 携带、纯值/边界拒绝、依赖传播 rootOwnsOutcome=false）。frontend 包 1399 tests 全绿。
  - 已知边界说明：property initializer 内 await 若 operand 本身被 property-init MVP 规则 BLOCKED（如实例 signal），诊断由既有上游规则承担，boundary error 不再重复发射（依赖传播优先）；`_init` 内 await 未特殊禁止（计划未列），沿用一般规则。参数默认值不经过 body 表达式 typing，既有 fail-closed 边界不变。
- review-expert-a 一轮复核修复（2026-08-25，REQUEST_CHANGES → 全项处理）：
  - **[BLOCKER]** 分类顺序修正：exact call 路由（含 callee 返回 `Variant`，即未标注返回类型的常态）现在先于静态 Variant 值判定；仅 `DYNAMIC` status 或无 call 事实的静态 `Variant` 值走 DYNAMIC 路由，且 pending 只记录 `RESOLVED` call。修复前 `func inner(): return 1` + `await inner()` 会被误标协程且漏发 `sema.redundant_await`。
  - **[HIGH]** compile gate 位置检查改按 call 锚点驱动：链式中间 `AttributeCallStep`（如 `inner().name`、`obj.coro().other()`）在 nested walk 中以 value 位置检查；锚点 identity 去重集防止链根末步被语句根检查与 nested walk 重复诊断。
  - **[HIGH]** `walkingStatementRootExpression` 改为 walkExpression 入口即消费到局部变量并立即清零，lambda body 等嵌套形态不再继承语句根特权（防御性修复：裸 lambda 语句根当前在前端为 unsupported 形态）。
  - **[MEDIUM]** `await` 静态协程调用补发专用 static blocker：gate 的 `AwaitExpression` 分支在通用 await blocker 之外，对 operand 的 static coroutine call 锚点补发「Static coroutine function」诊断（不动点 pass 对该 pending 静默消耗的合同不变）。
  - **[MEDIUM]** 补齐负例组合测试：未标注返回/`-> Variant` callee 的 call 路由、await 静态协程、lambda body 内协程调用（`signal.connect(func(): ...)` 支持形态）、容器/return 嵌套 value 位置、链式中间步、链式语句根放行。
  - **[SUGGESTION]** `awaitCallPendings()` 改为 unmodifiable view + `drainAwaitCallPendings()` 单消费者语义。
  - 顺带发现（预存问题，未在本步修复）：**裸 lambda 作为语句根**（`func(): ...` 直接作为 ExpressionStatement）会在 `FrontendSuiteResolver` 崩溃（`IllegalStateException: Function skeleton has not been published for <anonymous>`），与 await 无关，需另行立项。
- review-expert-a 二轮复核（2026-08-25）：**APPROVE**，五条原发现全部 RESOLVED；按复核建议补 `analyzeForCompileRejectsIntermediateAttributeCallStep`（`self.inner().name` 真正命中中间 `AttributeCallStep` 路径；`inner().name` 实际覆盖的是 CallExpression-as-object 形态，两测例并存）。复核确认留给第八步的已知点：await 整棵 operand 被 skip，解封 await 时需 walk operand 子树但跳过主 call 锚点。

目标：

- `AwaitExpression` 从 `DEFERRED` 改为按 §3.5 分类发布类型化事实与协程函数标记；**compile gate 以 await 专用 blocker 继续拦截**，直至第八步解封。

建议实施内容：

- `FrontendExpressionSemanticSupport` / `FrontendBodyOwnerProcedures` 增加 await 分支：解析 operand、按分类表发布 `expressionTypes` 结果类型、把 enclosing function 记入 `FrontendAnalysisData` 新 side table（协程函数集合）。
- 新增 `sema.redundant_await` warning；`diagnostic_manager.md` 同步登记；诊断 owner 遵守 `frontend_rules.md` 既有分工（expr analyzer 拥有 deferred/unsupported route，compile gate 拥有协程调用位置校验）。
- `FrontendCompileCheckAnalyzer`：把 await 的通用 `DEFERRED` 拦截替换为 await 专用 compile blocker（分类合法但因 lowering 未就绪而拦截），并对「value 位置调用协程函数」「静态方法协程调用」按 §3.5 发对应诊断。

验收细则：

- happy path：
  - `await sig`（0/1/多参、engine signal）、`await 协程调用`、`await 非协程调用`（warning + 穿透）的 sema 测试断言结果类型与协程标记（经普通 `analyze(...)` 验证，不经 compile gate）。
  - `DYNAMIC` operand 的 sema 测试断言结果类型为 `Variant` 且 enclosing function 被标记协程。
  - 翻转 `FrontendExpressionSemanticSupportTest` 的 DEFERRED 断言为新合同。
- negative path：
  - lambda / property init 内 await、静态非 signal 值 await、value 位置调用协程函数、静态方法协程调用：各自断言正确 diagnostic category、坏 subtree 被跳过、同 module 其他 subtree 继续工作。
  - `analyzeForCompile` 对**分类合法**的 await 仍发 await 专用 blocker 并停止 pipeline（防止未就绪 lowering 被触发）。

### 第七步：CFG `AwaitItem` 与 body lowering

- 状态：未开始

目标：

- await 进入 frontend CFG 并物化为 LIR（pass 级测试，不依赖 compile gate 放行）。

建议实施内容：

- `FrontendCfgGraphBuilder.buildValue(...)` 增加 `AwaitExpression` case：先构建 operand value，再发布 `AwaitItem`（普通 value item，result value id 沿用 `cfg_tmp_*` 命名合同，不拆分控制流）。
- `FrontendLoweringClassSkeletonPass` 消费协程函数集合并写 `LirFunctionDef.isCoroutine`。
- body lowering processor registry 新增 `AwaitItem` 处理器：signal 路径复用既有 Signal 物化 + `AwaitInsn`；call 路径在普通 call 物化后接 `AwaitInsn`；typed boundary 复用 `materializeFrontendBoundaryValue(...)`。

验收细则：

- happy path：
  - CFG 测试断言 `AwaitItem` 形状与 value id 合同；body lowering 测试断言 LIR 中 signal 物化指令序列 + `await` 指令；`LirFunctionDef.isCoroutine` 正确传播。
  - `entryBlockId`、terminator 完整性、serializer/parser round-trip 测试同步覆盖含 await 的函数。
- negative path：
  - 缺失 published fact（结果类型、协程标记、callee 元数据）一律 invariant fail-fast，禁止 lowering 侧重新推导语义。

### 第八步：compile gate 解封、文档同步与 e2e

- 状态：未开始

目标：

- await 全链路闭环（本步是 gate 唯一放行点），文档与回归锚点同步。

建议实施内容：

- 移除第六步的 await 专用 blocker；翻转/新增 `FrontendCompileCheckAnalyzerTest` 相关断言（`analyzeForCompileBlocksAwaitSignal` 按新合同改写为放行合法 signal await、保留非法路径拦截）。
- 新增 `test_suite` e2e（zig + `GODOT_BIN` 可用时运行，否则 assumption skip），建议最小集合：
  - `coroutine/await_signal_basic.gd`（`_ready` 中 `await` 自定义 signal，emit 后继续执行）
  - `coroutine/await_signal_args.gd`（0/1/多参恢复值规则）
  - `coroutine/await_engine_signal.gd`（如 `get_tree().process_frame`）
  - `coroutine/await_call_immediate.gd`（被 await 的函数同步完成，断言无挂起且结果正确——fast path）
  - `coroutine/await_call_suspend.gd`（被 await 的函数内部 await signal，断言恢复值与执行顺序；含多层嵌套覆盖级联 finalize）
  - `coroutine/await_loop.gd`（循环内多次 await；局部多 Array/Dictionary 兼作栈压测）
  - `coroutine/await_dynamic_signal.gd`（Variant 持有的 signal 上动态 await）
  - `coroutine/await_dynamic_late.gd`（Variant 持有的自己的状态对象在完成后再 await → done fast path 立即返回缓存结果）
  - `coroutine/await_interop_interpreted.gd`（await 解释脚本的协程返回值，经 `Signal(state, "completed")` 连接）
  - `coroutine/interop_state_completed_signal.gd`（解释层 GDScript 侧 `await obj.coro().completed` 或手动 connect gdcc 状态对象的 `completed`）
  - `coroutine/await_fire_and_forget.gd`（statement 位置调用协程，signal 触发后协程仍继续执行）
  - 负例 e2e / 集成锚点：emitter 释放导致挂起协程放弃（cancel-resume，无泄漏、无崩溃）；typed 非 Variant engine 调用挂起的偏离行为（零值 + runtime error，协程后台跑完）；signal connect 失败不挂起。
- 同步更新：`frontend_rules.md`（MVP 约定 await 条款 + class 级保留前缀）、`frontend_signal_support.md`（「仍拒绝 await signal」条款与 compile gate 清单）、`frontend_lowering_plan.md`（Post-MVP 中移除 `await signal`）、`frontend_lambda_implementation.md`（lambda await 维持 fail-closed 的交叉引用）、`frontend_compile_check_analyzer_implementation.md`、`gdcc_facing_class_name_contract.md`、`diagnostic_manager.md`、`README.md`（如列有协程缺口）。

验收细则：

- happy path：
  - 上述 e2e 全部通过（或被环境正确 skip）；`./gradlew clean build --no-daemon --info --console=plain` 全绿。
- negative path：
  - 既有全部测试无回归；文档中的「仍拒绝」清单不再包含已放行项。

---

## 5. 明确不纳入（Post-MVP Backlog）

- **lambda 内 await（MVP 后第一跟进项）**：Callable ABI 阻碍已被状态对象方案解除（`call_func` 挂起时返回状态对象 Variant，动态 await 天然消费），剩余前提是 R6（capture block 引用计数化）与 per-lambda 状态类生成。
- 静态方法协程调用（`await Worker.static_coro()`）：前提是补 `CallStaticMethodInsn` 的 backend 生成器（既有缺口，见 `frontend_signal_support.md`）。
- value 位置调用协程函数（状态对象已是合法 Variant 值，放行本身简单，但需类型系统与诊断配套；statement 位置 fire-and-forget 已在 MVP 内）。
- 静态已知非 signal 纯值的 `await x` 放宽为 warning + 穿透（对齐 Godot `REDUNDANT_AWAIT` 全集）；同期可评估「语法存在 AwaitExpression 即标记协程」的 Godot 严格对齐（§3.5 偏离的可选消除）。
- 性能优化：Godot 状态对象懒物化（R8）、`mco_create`/栈复用池、`stack_size` 用户可配置项。
- R3 主动取消：`NOTIFICATION_PREDELETE` + 每实例 pending 帧列表。
- R7 trampoline 恢复队列（仅在恢复级联栈深度成为实际问题时）。
- 多线程/多 `_Thread_local` 协程调度（MVP 假设引擎主线程单线程恢复）。

---

## 6. 测试规则

- 每个新 pass / 新指令 / 新 runtime helper 至少覆盖 happy path 与 negative path；negative path 锚定 diagnostic category、pipeline 是否 stop、是否禁止继续产生产物。
- 涉及 basic block 的改动必须同时测试 `entryBlockId`、terminator 完整性、serializer/parser round-trip。
- backend codegen 测试使用生成 C 字符串锚点；runtime/e2e 测试保持环境感知（无 zig / 无 `GODOT_BIN` 时 assumption skip）。
- 解除 compile gate blocker 的提交必须同步更新 `frontend_rules.md`、`frontend_compile_check_analyzer_implementation.md`、本文档与对应测试。
