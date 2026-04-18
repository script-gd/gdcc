# Engine Method Bind 迁移计划

## 文档状态

- 状态：Planned
- 范围：`CALL_METHOD` 的 exact engine route 渐进式摆脱 `gdextension-lite`
- 本文包含两部分目标：
  - 第一阶段基础设施：记录已使用的 exact engine method，并生成专用 method bind 头文件
  - 后续切换阶段：将 exact engine call 的实际调用路径从 `gdextension-lite` wrapper 切到 direct method bind route
- 当前明确非目标：
  - 本计划只覆盖 `CALL_METHOD` 的 exact engine route
  - 不在本轮顺手迁移 `CALL_GLOBAL`、property、index、constructor、`CALL_STATIC_METHOD`
  - 不在本轮顺手迁移“direct static syntax”的 builtin / engine static method route
  - 不修改 Gradle / Zig / 资源提取脚本
- 作用域澄清：
  - 本计划包含 `CALL_METHOD` 现有合同里“实例语法命中 static engine method”这条 exact engine route
  - 它仍属于 `CALL_METHOD` 的迁移范围，不属于 `CALL_STATIC_METHOD` 非目标

## 背景与调研结论

### 已确认的当前问题

- `doc/test_error/test_suite_engine_integration_known_limits.md` 已记录：`gdextension-lite` 的 class-method object-parameter wrapper 会把 object 参数错误地直接放进 `ptrcall` 参数数组，而不是传入“保存 object pointer 的槽位地址”。
- 这不是 frontend exact metadata contract 的残留，而是 `gdextension-lite` wrapper ABI 本身的缺陷。
- `Node.add_child(Node, bool, enum)` 只是最容易撞到的入口，不是唯一问题点。

### 上游依据

- Godot `core/object/method_bind_common.h`
  - `MethodBind::ptrcall(...)` 最终走 `call_with_ptr_args(...)` 等模板路径。
- Godot `core/variant/method_ptrcall.h`
  - `PtrToArg<T *>` 会把 `p_args[i]` 当成 `T *const *` 槽位来解包。
  - 因此 object 参数传给 `ptrcall` 的必须是“对象指针变量的地址”，而不是对象指针值本身。
- `gilzoide/gdextension-lite`
  - `binding_generator/classes/method.py` 把 class method 绑定生成到 `GDEXTENSION_LITE_CLASS_METHOD_IMPL...`
  - `binding_generator/format_utils.py` 的 `format_value_to_ptr(...)` 对 object 参数返回原值，对 primitive / enum 返回地址
  - `gdextension-lite/implementation-macros.h` 的 `GDEXTENSION_LITE_DEFINE_ARGS(...)` 会把这些值直接放进 `_args[]`
- 结论：
  - 不能继续复用 `gdextension-lite` 的 exact engine wrapper ABI
  - 真正的切换目标应是：
    - non-vararg exact method -> `godot_object_method_bind_ptrcall(...)`
    - vararg exact method -> `godot_object_method_bind_call(...)`

## 当前代码基线

### 1. exact engine call 的完整信息只在 codegen 时刻存在

- `BackendMethodCallResolver.resolve(...)` 先走 shared `ScopeMethodResolver`
- `CallMethodInsnGen.generateCCode(...)` 再按 `DispatchMode` 分流
- exact engine / builtin / GDCC 路径最终都进入：
  - `CallMethodInsnGen.emitKnownSignatureCall(...)`
- 这意味着：
  - “当前 module 真正用到过哪些 exact engine method” 的最佳记录点就是 `emitKnownSignatureCall(...)`
  - dynamic / builtin / GDCC 路径都不应混入这一收集面

### 2. `ResolvedMethodCall` 目前缺少 bind lookup 身份

- 现有 `BackendMethodCallResolver.ResolvedMethodCall` 已保留：
  - `mode`
  - `methodName`
  - `ownerClassName`
  - `ownerType`
  - `returnType`
  - normalized 参数列表
  - `isVararg`
  - `isStatic`
- 但它还没有保留：
  - `godot_classdb_get_method_bind(...)` 所需的 `hash`
  - `hashCompatibility`
- 这些信息目前仍留在 extension metadata 的 `FunctionDef` 实现里，`toResolvedMethodCall(...)` 之后就丢失了

### 3. `CCodegen.generate()` 的渲染顺序决定了 collector 是否可用

- 当前 `CCodegen.generate()` 只生成：
  - `entry.c`
  - `entry.h`
- 当前顺序是：
  1. 渲染 `entry.c.ftl`
  2. 渲染 `entry.h.ftl`
- `entry.c.ftl` 内部会调用 `gen.generateFuncBody(...)`
- `generateFuncBody(...)` 才真正驱动 `CallMethodInsnGen`
- 结论：
  - 若采用“在 `CallMethodInsnGen` 中记录”的方案，method bind 头文件必须在 `entry.c` body 已经生成之后再渲染

### 4. 当前 `callAssign(...)` / `callVoid(...)` 仍是“普通 C 函数调用模型”

- `CBodyBuilder.callVoid(...)` / `callAssign(...)` 当前假定目标是：
  - 一个可直接写成 `func(args...)` 的普通 C 函数
- 它们负责：
  - 参数表达式渲染
  - object arg 的 raw ptr 转换
  - object return 的 raw ptr 结果识别与回填
- 这些能力当前靠函数名分类：
  - `checkGlobalFuncRequireGodotRawPtr(...)`
  - `checkGlobalFuncReturnGodotRawPtr(...)`
- 结论：
  - 实际切换调用路径时，不能只把 `resolved.cFunctionName()` 从 `godot_<Owner>_<method>` 改成另一个名字
  - 需要同时决定：
    - exact engine method 仍保持“普通 C helper”集成形态
    - 或新增专门的 builder primitive

### 5. 现有测试对生成文件数量与顺序有硬编码

- `CCodegenTest` 当前存在：
  - `assertEquals(2, files.size(), ...)`
  - `files.get(0)` 视为 `entry.c`
  - `files.get(1)` 视为 `entry.h`
- 一旦新增 `engine_method_binds.h`，这些断言都必须改为按 `filePath` 查找

### 6. `CALL_METHOD` 命中 static engine method 是现存 backend 合同

- `doc/module_impl/backend/call_method_implementation.md` 已记录：静态方法通过实例 `call_method` 时允许生成，但会输出 warning。
- 当前代码路径中，这不是偶然行为，而是显式分支：
  - `CallMethodInsnGen.emitKnownSignatureCall(...)` 遇到 `resolved.isStatic()` 会先走 `warnStaticMethodCall(...)`
  - `validateFixedArgsAndCompleteDefaults(...)` 在 `resolved.isStatic()` 时不会把 receiver 放进 `fixedArgs`
  - 因而当前最终发射的是“不带 receiver 的静态调用”，而不是伪实例调用
- 这条合同已经被 `CallMethodInsnGenTest.callMethodStaticShouldEmitWarningAndGenerateCall()` 锁定：
  - 生成文本应等价于 `godot_Node_make()`
  - 同时必须保留 warning
- 上游 `gdextension-lite` 对 engine class static method 也采取相同语义：
  - wrapper 内最终以 `NULL` 作为 bind receiver 进入 class-method bind
- 结论：
  - 后续计划不能只按 `ENGINE + isVararg / !isVararg` 分阶段
  - `isStatic()` 必须作为与 `isVararg` 正交的 helper ABI 维度单独建模

## 总体迁移策略

### 核心设计选择

- 第一阶段只生成 method bind accessor / internal helper 所需的头文件骨架，不切换调用发射。
- 实际切换时，优先继续保持 `CallMethodInsnGen` 使用“普通 helper 函数名 + `callAssign/callVoid`”的接入方式，而不是让 generator 自己手拼整段 `ptrcall` / `call` C 代码。
- 也就是说，后续 exact engine route 的切换目标不是“在 Java 里内联所有 bind call 细节”，而是：
  - 生成我们自己的 internal helper
  - helper 内部用 direct method bind ABI 正确实现调用
  - `CallMethodInsnGen` 只把实际参数继续交给该 helper
- 此次新增的 binding / helper C 函数名必须与 `gdextension-lite` 现有 class-method wrapper 名称彻底区分。
  - 尤其不能复用 `godot_<Owner>_<method>` 这一命名形状。
  - 即便签名不同，也不允许生成与 `gdextension-lite` 同名的 C 函数，以免调用来源、符号语义和 ABI 责任边界再次混淆。

### 这样做的原因

- 当前 backend 的参数校验、默认参数补齐、结果槽位赋值、object return ownership 语义已经围绕 `callAssign/callVoid` 稳定下来。
- 若直接把 `ptrcall` / `call` 的细节全部下沉到 `CallMethodInsnGen`，改动面会大幅扩大到：
  - 参数渲染
  - temp 生命周期
  - object raw ptr 转换
  - return materialization
- 继续走 internal helper 能把 diff 保持在：
  - `ResolvedMethodCall` 元数据
  - method bind 头文件模板
  - `CCodegen.generate()` 渲染顺序
  - `CBodyBuilder` 对新 helper 前缀的 raw ptr 合同
  - `BackendMethodCallResolver.renderMethodCFunctionName(...)`

## 分阶段实施与验收

## 阶段 1：补齐 `ResolvedMethodCall` 的 bind 身份信息

### 实施

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- 新增最小的 backend-only 记录结构，建议：
  - `EngineMethodBindSpec(long hash, List<Long> hashCompatibility)`
- 将其作为 `ResolvedMethodCall` 的一个可空字段保留出去
- 只在以下条件同时满足时填充：
  - `DispatchMode == ENGINE`
  - `ScopeResolvedMethod.function()` 来自 extension class method metadata
  - 当前 method 有有效主 `hash`
- 其他路径保持 `null`

1. 保持 `ResolvedMethodCall` 的参数 / 返回类型继续使用 shared-normalized 语义
- 不为 phase 1 再加第二套 raw type 解析
- `bitfield`、`typedarray`、`typeddictionary` 继续沿用当前 normalized 结果

### 验收

- exact engine method 的 `ResolvedMethodCall` 能直接提供 bind lookup 所需的 `hash`
- `hashCompatibility` 不再在 backend adapter 阶段丢失
- builtin / dynamic / GDCC 路径不会误带 engine bind spec
- 这一阶段不改变任何现有 C body 文本输出

## 阶段 2：在 `CallMethodInsnGen` 中记录“实际用到过的 exact engine method”

### 当前文档缺口

- 现计划把 collector 状态藏进 `CGenHelper` / `CallMethodInsnGen`，再只在 `CCodegen.generate()` 开头 `reset`。
- 但当前代码基线里：
  - `CCodegen.generateFuncBody(...)` 是 public render API
  - 大量 focused tests 直接调用它，而不是走 `generate()`
  - `prepare(...)` 只创建一次共享 `helper`，后续同一个 `CCodegen` 实例上的多次 render 会复用该 `helper`
- 若 collector 直接挂在共享 `helper` 上，则 `generateFuncBody(...)` 将从“近似纯函数式 body render”退化为“带隐式可变状态的过程”，其可观察行为会额外依赖：
  - 之前是否已经 render 过别的函数
  - 上一次 render 是否中途抛错
  - 调用者有没有先手动 reset 隐式状态
- 这属于架构变化，不是单纯的实现细节，文档必须显式处理。
- 另外，当前文档把记录时点放在 `callVoid(...)` / `callAssign(...)` 之前，也与现代码执行顺序不符：
  - `void + resultId` 的 fail-fast 发生在 `emitKnownSignatureCall(...)` 内 `callVoid(...)` 之前
  - non-void path 的 `resolveResultTarget(...)` 发生在记录点之后
  - `CBodyBuilder.callAssign(...)` 仍会校验 target assignability / return contract，并可能继续抛错
- 因此如果维持“先记录，后发射调用”的方案，当前文档自己的验收条件：
  - “失败不会留下脏记录”
  - 在字面上并不成立

### 阶段 2 的硬约束

- 本计划在此明确选定方案：
  - 显式传入 caller-owned session / sink
  - 单函数 local buffer
  - `generateFuncBody(...)` 成功返回后再 commit
- 强约束：
  - public `generateFuncBody(...)` 不能因为新增 exact-engine usage collector 而变成依赖隐式共享状态的 API
  - 同一 `CCodegen` 实例上，对同一输入重复调用 `generateFuncBody(...)`，结果不能依赖先前 render 历史
  - 一次失败的 body render 不能把部分“已使用 engine method”泄漏到下一次 render
  - collector 的提交点必须晚于当前 exact-call path 的全部 fail-fast 校验
  - `generate()` 取得 used-engine-method 集合的方式，不能建立在“调用 public `generateFuncBody(...)` 会偷偷改写 helper / codegen 全局状态”这一隐式合同上
- 原因：
  - 比“render result 显式回传”更局部，能更好保留当前模板组织
  - 比“独立 prepass”更不容易复制 exact engine route 语义
  - 最适合当前代码基线里大量 focused tests 直接调用 public `generateFuncBody(...)` 的现实
- 对 `emitKnownSignatureCall(...)` 的最低要求：
  - static warning 仍可在前面立即输出
  - 但 engine method usage 的“正式记账 / commit”必须放在该条 exact call 已成功完成当前分支全部校验之后
  - 对 non-void path，至少要晚于：
    - `resolveResultTarget(...)`
    - `callAssign(...)`
  - 对 void path，至少要晚于：
    - `void + resultId` 校验
    - `callVoid(...)`

### 实施

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- 在 backend 内部显式引入两层作用域：
  - module-scope `EngineMethodUsageSession`
  - function-scope `EngineMethodUsageSink`
- 二者都必须是 backend-private 实现细节：
  - 不新增 public 框架式抽象
  - 可以用 `CCodegen` 内部嵌套类型，或同包 package-private 小类型
- `EngineMethodUsageSession` 职责：
  - 持有 module 级 `LinkedHashMap<EngineMethodBindKey, ResolvedMethodCall>`
  - 提供稳定顺序去重后的 snapshot
- `EngineMethodUsageSink` 职责：
  - 只持有单次 `generateFuncBody(...)` render 的 local buffer
  - 不直接暴露给模板层跨函数复用
  - 只允许在成功 render 后一次性 commit 到 session

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- 新增 internal overload，例如：
  - `generateFuncBody(clazz, func, usageSink)`
- 现有 public `generateFuncBody(clazz, func)` 保持兼容，但固定走：
  - no-op sink
  - 或 fresh local sink 且不外泄、不 commit
- 这样 focused tests 继续直接调用 public API 时：
  - 不会改写任何 module 级 used-engine-method 状态
  - 也不需要手动 reset collector

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
- 为 instruction gen 提供显式 sink 入口，建议二选一：
  - constructor 接收 `EngineMethodUsageSink`
  - 或新增窄接口 `recordUsedEngineMethodCall(...)`
- 关键约束：
  - `CBodyBuilder` 只持有“本次函数 render 的 sink”
  - 不回头引用 `helper` 或 `codegen` 上的共享 collector 状态

1. key 建议最小化为：
- `ownerClassName`
- `methodName`
- `hash`
- `isStatic`
- `isVararg`
- 说明：
  - `godot_classdb_get_method_bind(...)` 的 bind lookup 身份仍主要依赖 `ownerClassName + methodName + hash`
  - 但 generated helper 的 ABI surface 还受 `isStatic()` / `isVararg()` 影响
  - collector 若只按 bind identity 去重，后续 helper 发射可能把“无 receiver 的 static helper”和“有 receiver 的 instance helper”错误折叠

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`
- exact engine usage 的采集仍建议只锚定在 `emitKnownSignatureCall(...)`
- 但“正式记账”必须改为 success-only commit：
  - 不允许在 `callVoid(...)` / `callAssign(...)` 之前直接写入 module-visible session
  - 不允许在 `resolveResultTarget(...)` 之前就把该 method 视为已稳定使用
  - 推荐记录点放在 exact-call 分支末尾：
    - `callVoid(...)` / `callAssign(...)` 已成功返回
    - 临时参数销毁文本已发射完成
    - 随后才写入本函数的 local sink
- `CallMethodInsnGen` 只负责向当前函数 sink 追加 candidate：
  - 不直接操作 module session
  - 不承担 commit 事务边界

### 验收

- 同一个 engine method 在一个 module 内只记录一次
- 记录顺序与第一次命中顺序一致，输出可预测
- 参数校验失败、缺参失败、类型不兼容失败、`void + resultId` 失败、result target 失败都不会留下脏记录
- `OBJECT_DYNAMIC`、`VARIANT_DYNAMIC`、`BUILTIN`、`GDCC` 路径不会产生记录
- `CALL_METHOD` 命中 static engine method 的 warning path 也能被稳定记录，不会因为 receiver 被省略而漏收集
- static / instance / vararg helper surface 不会因为 collector key 过窄而错误合并
- public `generateFuncBody(...)` 的结果不依赖隐式共享 collector 历史
- 一次失败 render 不会污染同一个 `CCodegen` 实例后续的 body render
- public `generateFuncBody(...)` 固定不产生 module-visible used-engine-method 副作用
- 只有 `generate()` 持有的 module session 会累积 used-engine-method 集合

## 阶段 3：生成 `engine_method_binds.h` 并接入 `entry.h`

### 实施

1. 新增模板文件：
- `src/main/c/codegen/template_451/engine_method_binds.h.ftl`

1. 生成文件命名：
- `engine_method_binds.h`

1. phase 3 的头文件内容先做 bind accessor 骨架
- 为每个已用方法生成一个 `static inline` accessor：
  - 内部缓存 `static GDExtensionMethodBindPtr bind = NULL;`
  - 首次调用时执行 `godot_classdb_get_method_bind(...)`
  - 主 `hash` 失败后按 `hashCompatibility` 顺序回退
- accessor 命名建议：
  - `gdcc_engine_method_bind_<Owner>_<method>_<hash>()`
- accessor 名必须保持 `gdcc_engine_*` 前缀体系，不允许与 `gdextension-lite` 已生成函数同名
- 对 `isStatic() == true` 的 exact engine method，建议 accessor 符号也带显式 static 标记，例如：
  - `gdcc_engine_method_bind_static_<Owner>_<method>_<hash>()`
- 即便 bind lookup 最终仍使用同一组 class/method/hash 输入，生成符号也应让 review 时能直接区分“无 receiver 的 static helper route”

1. phase 3 不生成 `godot_<Owner>_<method>` 风格 public wrapper
- 也不立刻切换 `resolved.cFunctionName()`
- 目标是先把 bind lookup 骨架落地并进入生成文件 surface

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- 调整渲染顺序为：
  1. `entry.c.ftl`
  2. `engine_method_binds.h.ftl`
  3. `entry.h.ftl`
- `generate()` 在开始渲染前显式创建一个 module-scope `EngineMethodUsageSession`
- `generate()` 不允许直接依赖 public `generateFuncBody(...)` 收集 usage
- `generate()` 必须通过“绑定 session 的 body renderer”驱动 `entry.c` 渲染：
  - body renderer 每次处理一个函数时：
    - 创建 fresh local sink
    - 调 internal `generateFuncBody(clazz, func, sink)`
    - 只有当该调用成功返回时，才把 sink commit 到 module session
- `entry.c` 完成渲染后，`generate()` 再从 session snapshot 渲染：
  - `engine_method_binds.h`
  - `entry.h`
- 返回文件改为：
  - `entry.c`
  - `engine_method_binds.h`
  - `entry.h`

1. 文件：`src/main/c/codegen/template_451/entry.c.ftl`
- 将当前直接调用 public `gen.generateFuncBody(...)` 的位置切到绑定 renderer，例如：
  - `bodyRender.generateFuncBody(...)`
- 目的不是新增一层公共抽象，而是让模板显式使用“带 module session 的 render 入口”
- 强约束：
  - `entry.c.ftl` 不得继续直接走 public `generateFuncBody(...)`
  - 否则阶段 3 会得到空 usage 集合，`engine_method_binds.h` 与 `entry.c` 调用面失配

1. 文件：`src/main/c/codegen/template_451/entry.h.ftl`
- 加入：
  - `#include "engine_method_binds.h"`

### 验收

- 生成目录中稳定存在 `engine_method_binds.h`
- `entry.h` 稳定 include 新头文件
- 没有 exact engine call 时，仍生成合法空壳头文件
- 当前 exact engine call 的实际调用路径仍保持不变
- `entry.c` 所见的 body render 入口已切到 session-bound renderer，而不是 public `generateFuncBody(...)`
- `engine_method_binds.h` 使用的 used-engine-method 集合与 `entry.c` 本轮成功 render 的 exact engine call 集合同步

## 阶段 4：在 `engine_method_binds.h` 中生成 internal call helper

### 实施

1. 在同一模板中，为每个已用 exact engine method 追加 internal helper
- 命名建议：
  - instance non-vararg: `gdcc_engine_call_<Owner>_<method>_<hash>(...)`
  - static non-vararg: `gdcc_engine_call_static_<Owner>_<method>_<hash>(...)`
  - instance vararg: `gdcc_engine_callv_<Owner>_<method>_<hash>(...)`
  - static vararg: `gdcc_engine_callv_static_<Owner>_<method>_<hash>(...)`
- helper 仍是 internal 符号，不对齐 `gdextension-lite` public ABI
- helper 名称是强约束，不是可选建议：
  - 不允许回退为 `godot_<Owner>_<method>` 或其他会与 `gdextension-lite` wrapper 重名的形状
  - 后续若要调整命名，也必须继续满足“与 `gdextension-lite` 名称空间隔离”的约束

1. non-vararg helper 的实现合同
- 内部通过 accessor 拿到 `GDExtensionMethodBindPtr`
- `isStatic() == false`：
  - receiver 用 `GDExtensionObjectPtr` 或 owner engine raw pointer 进入 helper
  - helper body 通过该 receiver 调 `godot_object_method_bind_ptrcall(...)`
- `isStatic() == true`：
  - helper signature 不接受 receiver 形参
  - helper body 必须以 `NULL` 作为 bind receiver 调 `godot_object_method_bind_ptrcall(...)`
  - 禁止为了复用签名而引入“未使用 receiver 占位参数”
- 调用核心使用：
  - `godot_object_method_bind_ptrcall(...)`
- 参数槽位合同必须显式遵守 Godot `PtrToArg` 规则：
  - primitive / enum / bitfield：传值变量地址
  - object：传“对象指针变量”的地址
  - 非对象 builtin wrapper：传 wrapper storage 地址
- return 处理：
  - `void`：直接 `ptrcall(..., NULL)`
  - non-void：本地 materialize 返回槽并返回 typed C 值

1. vararg helper 的实现合同
- 调用核心使用：
  - `godot_object_method_bind_call(...)`
- helper signature 保持：
  - `isStatic() == false`：
    - receiver + typed fixed args + `const godot_Variant **argv` + `godot_int argc`
  - `isStatic() == true`：
    - typed fixed args + `const godot_Variant **argv` + `godot_int argc`
    - helper body 以 `NULL` 作为 bind receiver
- helper 内部负责：
  - 只把 fixed args pack 成 helper-owned 本地 `godot_Variant`
  - 与 caller 提供的 extra vararg tail 一起组装 `GDExtensionConstVariantPtr[]`
  - 逆序 destroy helper-owned fixed-arg variants
  - 只在 `GDExtensionCallError.error == GDEXTENSION_CALL_OK` 后做 typed unpack / copy return
- ownership split 必须冻结为：
  - caller 继续拥有 default temp、extra vararg tail、以及 call-site expression temps
  - helper 只拥有 fixed-arg packed `Variant` 与本地 return `Variant`
  - helper 不 repack、不 destroy caller 传入的 `argv`
- fixed args 的 pack 规则应统一：
  - 包括 fixed `Variant` 参数在内，都先复制到 helper-owned 本地 `godot_Variant`
  - 不再依赖“fixed `Variant` 实参直接借用 caller slot”这种特例

1. 默认参数边界保持在 Java 侧
- `CallMethodInsnGen.validateFixedArgsAndCompleteDefaults(...)` 继续负责默认参数补齐
- internal helper 不负责“缺参补默认值”
- 这样避免把 shared semantic default 规则复制到模板层

1. runtime failure 合同
- bind lookup 失败不能直接空指针调用
- bind lookup failure 时，建议 helper 内统一：
  - 输出 `GDCC_PRINT_RUNTIME_ERROR(...)`
  - `void` helper 直接 `return`
  - non-void helper 返回 `helper.renderDefaultValueExprInC(returnType)` 对应的默认值
- vararg `call(...)` 路径若 `GDExtensionCallError.error != GDEXTENSION_CALL_OK`：
  - 也走同样的 runtime error 报告与默认返回
- vararg helper 必须采用 single-exit / cleanup-label 结构：
  - 只要 `godot_object_method_bind_call(...)` 已执行，helper-owned fixed-arg `Variant` 与本地 return `Variant` 都必须在退出前完成 destroy
  - cleanup 顺序固定为：先销毁本地 return `Variant`，再逆序销毁 fixed-arg packed `Variant`
- `void` vararg helper 也不得把 `NULL` 传给 `r_ret`
  - 计划必须按 Godot `object_method_bind_call(...)` 的实现假定“call 后本地 return `Variant` 已 materialize”
  - 因而 `void` helper 也要准备本地 `godot_Variant ret` 并在 cleanup 中销毁，而不是照抄 `gdextension-lite` variadic void macro
- 不允许直接复制 `gdextension-lite` 的 variadic class-method 宏作为模板
  - 其现有实现会在 `_error` 检查前直接做 typed unpack
  - 且 variadic void 宏会把 `NULL` 传给 `r_ret`
  - 两者都不符合本计划要求的安全 cleanup / error-path 合同

1. 模板与 helper 职责收口
- 不要在 `CallMethodInsnGen` 里重新拼一套 `ptrcall` / `call` 数组构造文本
- 若模板里需要统一渲染“某个 helper 形参在 ptrcall 中应该提交什么槽位表达式”，可以在 `CGenHelper` 新增一个窄 helper，例如：
  - `renderPtrcallSlotExpr(...)`
- 不要把 object / primitive / wrapper 三套规则散落到多个 FTL 片段
- `warnStaticMethodCall(...)` 保持在 Java 侧：
  - generated helper 只承接 ABI 责任
  - 不承接“实例语法命中 static method”的诊断责任

### 验收

- `engine_method_binds.h` 已经不仅有 bind accessor，还包含真正可调用的 internal helper
- non-vararg helper 不再依赖 `gdextension-lite` wrapper
- vararg helper 也不再依赖 `gdextension-lite` class-method variadic wrapper
- helper 本身具备 bind lookup failure guard，不会因为 `NULL bind` 崩溃
- 新生成的 binding / helper C 函数名与 `gdextension-lite` 不重名，代码审查时可直接从文本上区分调用来源
- static helper 与 instance helper 在签名层面明确区分：
  - static helper 无 receiver 形参
  - instance helper 保留 receiver 形参

## 阶段 5/6 的拆分依据

- 现在 exact engine route 不只是“函数名还指向 `godot_<Owner>_<method>` wrapper”。
- 还存在一段专门为 `gdextension-lite` wrapper ABI 保留的 bitfield 末跳补丁：
  - `BackendMethodCallResolver` 会为 bitfield 参数发布 `BitfieldPassByRefExtraParamSpecData`
  - `CallMethodInsnGen.renderProvidedFixedArg(...)` / `renderFixedTempArg(...)` 会把 `godot_int` temp 改写成：
    - `(const godot_<Owner>_<Flag> *)&temp`
  - 也就是说，当前 Java 侧最后交给 `callVoid(...)` / `callAssign(...)` 的并不是 shared-normalized surface，而是“为旧 wrapper 定制过的 pointer-shaped surface”
- 这条补丁正是 `ArrayMesh.add_surface_from_arrays(...)` 当前 compile/runtime anchor 能成立的一部分原因：
  - shared resolver 继续把 `enum::...` / `bitfield::...` 规约成 `int`
  - 但最后一跳仍会把 bitfield 实参改成 wrapper 接受的 `const godot_Mesh_ArrayFormat *`
- 因此计划采用渐进式的两阶段 non-vararg 迁移，而不是把 object 参数 ABI 修复和 bitfield surface ABI 收敛绑在同一个阶段里：
  - 阶段 5：
    - 先把 non-vararg exact engine route 切到 generated helper + direct `ptrcall`
    - 同时临时保留现有 bitfield pointer-shaped surface，避免破坏 `ArrayMesh.add_surface_from_arrays(...)` 锚点
    - 这一阶段的主目标是修掉 object-parameter wrapper 崩溃面，恢复 `Node.add_child(...)` 一类真实 runtime 路径
  - 阶段 6：
    - 再把 helper surface 收敛到 shared-normalized / backend-normalized callable surface
    - 去掉 Java 侧针对 engine helper route 的 wrapper-compatible bitfield cast
    - 让 enum / bitfield 的 slot-address materialization 真正下沉到 helper 内部
- 这样拆分后：
  - 阶段 5 负责“先把调用路径切对”
  - 阶段 6 负责“再把 ABI surface 做干净”
  - `ArrayMesh.add_surface_from_arrays(...)` 既能在阶段 5 保持通过，也能在阶段 6 成为“已脱离旧 cast”的验收锚点

## 阶段 5：切换 exact non-vararg engine route，并保留过渡期 bitfield helper surface

### 实施

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- 将 ENGINE route 的 `cFunctionName` 切到新的 internal helper 名
- 切换范围先限定为：
  - `DispatchMode == ENGINE`
  - `isVararg == false`
- 切换后的函数名必须继续指向 `gdcc_engine_call_<...>` 命名空间，不能重新落回 `godot_<Owner>_<method>` 形状
- 这一阶段仍允许保留当前 bitfield helper surface：
  - bitfield 参数继续沿用 `BitfieldPassByRefExtraParamSpecData`
  - generated helper 的 bitfield 形参暂时仍可使用 `const godot_<Owner>_<Flag> *`
  - 但 object 参数 route 必须已经完全脱离 `gdextension-lite` wrapper ABI
- `isStatic() == true` 的 non-vararg exact engine method 同样在这一阶段切换：
  - `cFunctionName` 应切到 `gdcc_engine_call_static_<...>`
  - generated helper 不能接收 receiver 形参
  - helper body 必须以 `NULL` receiver 调 bind

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
- 扩展以下判定，让 internal helper 继续复用现有 object raw ptr 中央转换：
  - `checkGlobalFuncRequireGodotRawPtr(...)`
  - `checkGlobalFuncReturnGodotRawPtr(...)`
- 新 helper 前缀建议纳入：
  - `gdcc_engine_call_`
  - 这样 exact engine object 参数和 object 返回值仍能走当前中央转换路径

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`
- `emitKnownSignatureCall(...)` 不需要重写为手工 `ptrcall` 生成器
- 对 non-vararg exact engine method，继续通过 `callVoid(...)` / `callAssign(...)` 调 internal helper
- 这样最大化复用当前：
  - receiver owner 对齐
  - fixed 参数校验
  - 默认参数 materialization
  - 结果槽位赋值与 object ownership 处理
- 但阶段 5 只保留“当前这一个过渡态”：
  - ENGINE non-vararg helper route 暂时继续允许 `(const godot_<...> *)&temp` 末跳改写
  - 这个兼容层只服务阶段 5 的过渡 surface，不得扩展成按方法名的额外特例
- `resolved.isStatic()` 的现有 `CALL_METHOD` 合同必须保持：
  - 继续保留 `warnStaticMethodCall(...)`
  - `validateFixedArgsAndCompleteDefaults(...)` 继续在 static path 省略 receiver
  - Java 侧不允许为了迁移 helper 而重新把 receiver 补进 fixed args

1. 文件：`src/main/c/codegen/template_451/engine_method_binds.h.ftl`
- non-vararg helper 在阶段 5 的合同改为：
  - object 参数按 Godot slot-address 规则修正
  - bitfield 参数暂时继续吃 pointer-shaped helper surface
  - 不允许再生成任何 `gdextension-lite` 同名 wrapper
- 也就是说，阶段 5 的 helper 是：
  - 调用路径已经切走 `gdextension-lite`
  - 但 bitfield surface 还处于兼容态
  - `isStatic() == true` 时仍无 receiver 形参，helper 内部固定传 `NULL`

### 验收

- non-vararg exact engine route 已不再调用 `gdextension-lite` 的 `godot_<Owner>_<method>` wrapper
- object 参数 exact route 的 runtime 行为不再受旧 wrapper ABI 影响
- 当前所有 non-vararg exact engine 现有正例保持通过
- 若此前因为 object-parameter wrapper ABI 崩溃的路径在 non-vararg 场景上可表达，应恢复为正向回归
- codegen 文本中可直接确认 non-vararg exact route 调用的是 `gdcc_engine_call_<...>`，而不是任何 `gdextension-lite` 同名函数
- `Node.add_child(...)` 应在这一阶段恢复为真实 runtime 回归锚点
  - 该锚点的写法必须冻结为 typed receiver exact route，例如：
    - `var holder: Node = Node.new()`
    - `holder.add_child(Node.new())`
  - 不接受使用 plain `var holder = Node.new()` 作为这条验收锚点：
    - 那条写法当前仍会先退到 dynamic fallback
    - 不能证明 exact engine instance route 已修复
  - 这条锚点的证明范围也必须收窄为：
    - `gdcc` 的 exact engine `CALL_METHOD` route 已不再消费 `gdextension-lite` class-method wrapper
    - 不等于 `gdextension-lite` 自身的 shared-generator object-parameter ABI debt 已被消除
- `ArrayMesh.add_surface_from_arrays(...)` 在这一阶段必须保持通过，但允许暂时继续依赖阶段 5 的 bitfield 兼容 surface
- `CALL_METHOD` 命中 static engine method 的既有合同保持不变：
  - 仍会输出 warning
  - generated call 不会把 receiver 作为第一个 fixed arg 传入 helper
  - static helper 内部以 `NULL` receiver 调 bind

## 阶段 6：收敛 non-vararg helper surface，移除 bitfield wrapper ABI 遗产

### 实施

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- 将当前仅服务旧 wrapper 的 `BitfieldPassByRefExtraParamSpecData` 升级为更通用的 engine helper slot metadata
- 目标是让 resolver 为 generated helper 提供统一的 slot 信息，而不是继续发布 wrapper-compatible pointer cast 事实
- 最低要求：
  - enum / bitfield 保留 raw exported leaf 对应的 slot CType
  - object 保留 slot-address 所需的 helper surface / slot mode
  - 其他 value-semantic wrapper 也能按统一规则进入 helper

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`
- ENGINE non-vararg helper route 停止 `(const godot_<...> *)&temp` 末跳改写
- fixed 参数继续使用 shared-normalized / backend-normalized surface：
  - enum / bitfield 继续以 `godot_int` 之类的 normalized 形状进入 helper
  - object / typedarray / dictionary 等继续走现有统一校验与默认值补齐
- 不把 `ptrcall` 数组构造搬回 Java
- static path 继续：
  - 保留 warning
  - 省略 receiver
  - 调到 `gdcc_engine_call_static_<...>`，而不是实例 helper

1. 文件：`src/main/c/codegen/template_451/engine_method_binds.h.ftl`
- non-vararg helper 的形参 surface 收敛为统一 helper surface
- helper 内部自己完成 slot materialization，例如：
  - enum / bitfield：本地 `slot` 变量 + `&slot`
  - object：对象指针槽位地址
  - value-semantic wrapper：storage 地址
- 明确约束：
  - 兼容性来源只能是“参数 family + normalized type + leaf raw metadata”
  - 不允许按类名、方法名、单个 API 写额外兼容分支

### 验收

- ENGINE non-vararg helper route 已不再依赖 Java 侧的 wrapper-compatible bitfield cast
- generated helper 的 fixed-arg surface 已与 shared-normalized / backend-normalized callable surface 对齐
- `ArrayMesh.add_surface_from_arrays(...)` 在不依赖旧 cast 的前提下保持通过
- codegen 文本中不再出现针对 ENGINE helper route 的 `(const godot_<...> *)&temp` 末跳改写
- 兼容性规则不依赖额外手工兼容表，也不依赖按方法名补丁
- static non-vararg helper 仍保持“无 receiver 形参 + `NULL` bind receiver”合同，不因 surface 收敛回退为实例签名

## 阶段 7：切换 exact vararg engine route

### 复杂度判断

- 这一阶段不能被视为“把 non-vararg helper 换成 `call(...)` 版本”的线性平移。
- non-vararg route 主要处理 slot-address ABI；vararg route 的核心复杂度则在于：
  - fixed-arg `Variant` pack
  - fixed prefix + extra vararg tail 的 argv 拼接
  - `GDExtensionCallError` 分支
  - typed return unpack
  - success / error 双路径下的一致 cleanup
- 当前 backend 已有一套稳定语义可直接复用：
  - Java 侧 exact resolve、fixed 参数校验、default 补齐、`resultId` / discard 校验
  - frontend 已冻结的 vararg tail `Variant` boundary
  - `CGenHelper.renderPackFunctionName(...)` / `renderUnpackFunctionName(...)` 对不同类型 family 的现有 pack/unpack 规则
- 因而阶段 7 的正确方向不是再发明第二套 vararg materialization 语义，而是把“谁拥有什么临时值”写死。

### 实施

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/BackendMethodCallResolver.java`
- ENGINE + `isVararg == true` 的 `cFunctionName` 切到：
  - `gdcc_engine_callv_<...>`
- 同样不允许复用 `gdextension-lite` 的 variadic wrapper 命名
- `isStatic() == true` 的 vararg engine method 则切到：
  - `gdcc_engine_callv_static_<...>`

1. `CallMethodInsnGen.emitKnownSignatureCall(...)`
- 继续保留现有 fixed 参数校验、default 补齐、extra vararg 校验
- extra vararg tail 继续复用当前 ordinary `Variant` boundary 合同：
  - caller 仍然把 extra args 作为 `const godot_Variant **argv, godot_int argc` 传给 helper
  - helper 不 repack、不 destroy caller 提供的 `argv`
- 不把固定参数 pack 逻辑搬回 Java
- 让 internal helper 自己 pack fixed prefix，并在 helper 内部与 `argv/argc` 合并
- static path 继续保留 warning，并且不把 receiver 补回 fixed args

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
- `gdcc_engine_callv_<...>` / `gdcc_engine_callv_static_<...>` 必须纳入 helper 前缀合同：
  - `checkGlobalFuncRequireGodotRawPtr(...)`
  - `checkGlobalFuncReturnGodotRawPtr(...)`
- 目标是继续复用现有 object receiver / object fixed arg / object return 的 raw ptr 渲染与回填语义
- 阶段 7 不引入一套只服务 vararg helper 的特殊 caller-side object 路径

1. `engine_method_binds.h.ftl`
- vararg helper signature 必须显式区分：
  - instance: `receiver + typed fixed args + const godot_Variant **argv + godot_int argc`
  - static: `typed fixed args + const godot_Variant **argv + godot_int argc`
- static vararg helper 继续遵守：
  - helper 不接收 receiver 形参
  - helper 内部固定以 `NULL` 作为 bind receiver
- vararg helper 内部必须：
  - 仅将 fixed prefix 统一 pack 为 helper-owned 本地 `godot_Variant`
  - fixed `Variant` 参数也先复制到 helper-owned 本地 `godot_Variant`
  - 与传入 `argv` 一起组成完整 Variant argv
  - 调 `godot_object_method_bind_call(...)`
  - 先检查 `GDExtensionCallError`
  - 仅在 `_error.error == GDEXTENSION_CALL_OK` 后做 typed unpack
  - 对 helper-owned temp variant 做逆序 destroy
- typed return unpack 必须复用 backend 现有 pack/unpack helper family
  - 不在模板里再复制一套 `godot_Variant_extract(...)` 风格的专用类型矩阵
  - 目标是与当前 dynamic path 的 unpack 规则保持同一事实来源
- helper 必须采用 single-exit / cleanup-label 结构：
  - bind lookup 失败直接走 runtime error + 默认返回
  - call 执行后，无论 `_error.error` 是否成功，都要先进入统一 cleanup，再离开 helper
  - cleanup 中只销毁 helper-owned fixed-arg packed `Variant` 和本地 return `Variant`
- 即使 helper 的 C surface 是 `void`，也必须为 `godot_object_method_bind_call(...)` 准备本地 `godot_Variant ret`
  - 不允许把 `NULL` 传给 `r_ret`
  - cleanup 中照样销毁该本地 return `Variant`
- 明确禁止直接套用 `gdextension-lite` variadic class-method 宏：
  - 不能复用其“先 unpack、后看 `_error`”的顺序
  - 不能复用其 variadic void 的 `NULL r_ret` 传法
- 当前 `Object.call(...)` / `Node.call(...)` 一类 exact vararg 正例应继续作为 helper ownership split 的主锚点：
  - fixed prefix 由 helper pack 并销毁
  - extra `Variant` tail 由 caller 提供并保留 caller ownership

### 验收

- exact engine vararg route 已不再走 `gdextension-lite` variadic wrapper
- fixed prefix 与 extra tail 的 ownership split 已明确且稳定：
  - helper 只 pack / destroy fixed prefix
  - caller 继续拥有 extra vararg tail、default temp 与 call-site temp
- 固定参数 pack / destroy 语义稳定，不泄漏 `Variant`
- 现有 engine vararg 正例保持通过
- 运行期 call error 能输出稳定诊断且不会留下未销毁 temp
- `void` vararg helper 文本中不再出现 `godot_object_method_bind_call(..., NULL, &_error)` 这类 `NULL r_ret` 传法
- typed return unpack 只会发生在 `_error.error == GDEXTENSION_CALL_OK` 之后
- vararg helper 不会销毁 caller 提供的 `argv` tail，也不会在 helper 内重新 pack extra vararg tail
- codegen 文本中可直接确认 vararg exact route 调用的是 `gdcc_engine_callv_<...>`，与 `gdextension-lite` 名称明确隔离
- static vararg helper 与 instance vararg helper 在签名层面明确区分，并保持“无 receiver + `NULL` bind receiver”合同

## 阶段 8：回归矩阵与运行时锚点升级

### 代码生成层

1. 文件：`src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`
- 所有生成文件检查改为按 `GeneratedFile.filePath()` 查找
- 新增断言：
  - `engine_method_binds.h` 存在
  - `entry.h` 包含 `#include "engine_method_binds.h"`
  - accessor 只对 exact engine route 生成
  - non-vararg internal helper 生成正确
  - vararg internal helper 生成正确
  - 同一 method 只生成一次 helper

1. 如有必要，新增 focused codegen test
- 断言 object 参数在 generated helper 中以“地址槽位”传给 `ptrcall`
- 断言不会再出现 `gdextension-lite` 风格的 `_args[] = { node, &force_readable_name, ... }` 形状
- 断言 static engine helper 不接收 receiver 形参
- 断言 static engine helper 内部以 `NULL` receiver 进入 bind
- 断言 `CALL_METHOD` 命中 static engine method 时，call site 仍不把 receiver 传给 helper
- 断言 public `generateFuncBody(...)` 直接调用不会隐式累积 module usage
- 断言 `generate()` 通过 session-bound body renderer 收集 usage，而不是依赖 public `generateFuncBody(...)`
- 断言 vararg helper 先检查 `_error.error`，再做 typed unpack
- 断言 vararg helper 即使是 `void` surface，也不会把 `NULL` 传给 `r_ret`
- 断言 vararg helper 只销毁 helper-owned fixed prefix / local return temp，不销毁 caller `argv`

### build / integration

1. 保留并扩展现有 anchor
- `Node.get_child_count(...)`
  - 继续作为 non-object-parameter smoke anchor
- `ArrayMesh.add_surface_from_arrays(...)`
  - 继续覆盖 enum / bitfield / typedarray metadata family
- `CALL_METHOD` 命中 static engine method
  - 继续保留单独 anchor，验证“warning + 无 receiver helper 调用 + `NULL` bind receiver”三者同时成立
- 现有 engine vararg case
  - 继续覆盖 `bind_call(...)` 变体
  - 明确冻结其语义边界：
    - fixed prefix 由 helper pack / destroy
    - extra `Variant` tail 由 caller 提供并保持 caller ownership

1. 恢复 `Node.add_child(...)` 作为真实 runtime 回归锚点
- 一旦阶段 5 落地，就应把 `Node.add_child(...)` 补回 exact engine runtime regression
- 这条 case 的意义不再是 metadata，而是直接验证：
  - object 参数 exact route 的 slot-address ABI 已修复
- 这条 runtime anchor 必须遵守与 `doc/test_error/test_suite_engine_integration_known_limits.md` 一致的前提：
  - receiver 必须显式声明为 engine typed local
  - 推荐冻结写法：
    - `var holder: Node = Node.new()`
    - `holder.add_child(Node.new())`
  - 不允许把下列写法当作 exact route 验收：
    - `var holder = Node.new()`
    - `holder.add_child(Node.new())`
  - 原因是 plain `var` receiver 当前仍可能走 dynamic fallback，而不是 exact instance route
- 若测试框架允许观察 codegen / lowering 侧信号，建议额外断言这条 case 没有退回 dynamic fallback
- 同时必须明确这条 anchor 的语义边界：
  - 它验证的是 migrated exact engine route 已绕开 `gdextension-lite` wrapper
  - 不是在宣称 `gdextension-lite` 共享 generator 里的 object-parameter ABI 组合缺陷已经消失

1. 推荐新增或升级的测试面
- `CallMethodInsnGenEngineTest`
  - non-vararg exact method
  - object-parameter exact method
  - 其中 `Node.add_child(...)` 相关 case 需显式强调“typed receiver required”，不能用 plain `var` receiver 形状替代
  - static exact method through `CALL_METHOD`
  - vararg exact method
  - vararg exact method runtime-error path
    - 推荐继续使用 `Object.call(...)` / `Node.call(...)` 这类 exact vararg route
    - 通过非法 method name 或非法 tail 组合触发 `GDExtensionCallError`
    - 验证 helper 输出诊断后流程仍可继续，而不是因未销毁 temp 崩溃
  - vararg exact method discard-return path
    - 验证 non-void vararg helper 在 discard 场景下仍完成统一 cleanup
  - static vararg engine method through `CALL_METHOD`
    - 若 extension metadata 中存在稳定样本，则补充“warning + 无 receiver helper 调用 + `NULL` bind receiver”联动回归
- `FrontendLoweringToCProjectBuilderIntegrationTest`
  - 验证生成工程稳定包含 `engine_method_binds.h`

### 文档同步

1. 文件：`doc/module_impl/backend/call_method_implementation.md`
- exact engine route 的实现说明需要改为：
  - 当前 exact engine route 优先走 generated method bind internal helper
  - `gdextension-lite` wrapper 不再是 exact engine route 的事实来源

1. 文件：`doc/gdcc_c_backend.md`
- 补充 direct method bind route 的 object-slot ABI 规则
- 明确：
  - non-vararg exact method 使用 `ptrcall`
  - vararg exact method 使用 `call`

1. 文件：`doc/test_error/test_suite_engine_integration_known_limits.md`
- 不应简单删除 `gdextension-lite` ABI known limit 这条事实源
- 应改为缩窄适用面，明确区分：
  - `gdcc` 的 exact engine `CALL_METHOD` route 一旦迁移到 generated helper + direct bind call，将不再受这条 known limit 制约
  - 但 `gdextension-lite` shared generator 本身的 object-parameter ABI debt 仍然存在，不能被文档表述抹掉
- 推荐写法：
  - 把 `Node.add_child(...)` 从“`gdcc` 当前 exact engine `CALL_METHOD` 仍受该缺口阻断”的示例中移出
  - 同时保留并重写 known limit 段落，明确其剩余适用面是：
    - 仍消费 `gdextension-lite` stock wrapper 的路径
    - 或任何仍依赖这组 shared generator 积木的调用面

## 完成定义（DoD）

- `ResolvedMethodCall` 已稳定保留 exact engine method bind 身份信息
- `CallMethodInsnGen` 能记录本 module 真正用到的 exact engine method
- used-engine-method 收集已按方案 B 落地：
  - public `generateFuncBody(...)` 走 no-op sink
  - `generate()` 持有 module session
  - 单函数 render 使用 local sink，并在成功返回后 commit
- `CCodegen.generate()` 稳定生成：
  - `entry.c`
  - `engine_method_binds.h`
  - `entry.h`
- non-vararg exact engine route 已在阶段 5 切到 generated internal helper + direct `ptrcall`
- non-vararg exact engine route 已在阶段 6 收敛到统一 helper surface，不再依赖 wrapper-compatible bitfield cast
- vararg exact engine route 已在阶段 7 切到 generated internal helper + direct `call`
- vararg exact engine route 的 ownership split 已冻结：
  - helper 只拥有 fixed-arg packed `Variant` 与本地 return `Variant`
  - caller 继续拥有 extra vararg tail、default temp 与 call-site temp
- `CBodyBuilder` 已识别新 helper 前缀的 raw ptr 参数 / 返回值合同
- 新生成的 binding / helper C 函数名与 `gdextension-lite` 名称空间严格隔离，不存在同名函数
- 项目级 DoD 不允许停留在阶段 5 的过渡态
- `ArrayMesh.add_surface_from_arrays(...)` 已在阶段 6 的最终 helper surface 下稳定通过，不再依赖 engine route 的 wrapper-compatible bitfield cast
- `Node.add_child(...)` 已重新成为真引擎正向 runtime 回归锚点
  - 该锚点只认 typed receiver exact route 形状：
    - `var holder: Node = Node.new()`
    - `holder.add_child(Node.new())`
  - plain `var holder = Node.new()` 不计入这条 DoD
  - 这条 DoD 只证明 `gdcc` migrated exact engine route 已不再受 `gdextension-lite` wrapper 限制
  - 不构成“`gdextension-lite` shared-generator ABI known limit 已消失”的结论
- `CALL_METHOD` 命中 static engine method 的现存 backend 合同保持不变：
  - 仍输出 warning
  - 不把 receiver 传进 generated helper
  - generated helper 以内建 `NULL` receiver 调 bind
- 现有 exact engine 正例无行为回退
- vararg exact route 的 runtime failure path 已稳定：
  - `_error.error` 检查先于 typed unpack
  - `void` helper 不把 `NULL` 传给 `r_ret`
  - call-error / success 两条路径都不会遗漏 helper-owned temp cleanup
- 失败的 `generateFuncBody(...)` render 不会向 module session 留下脏 usage

## 风险与缓解

### 风险 1：只切 non-vararg，不切 vararg，形成双轨长期悬挂

- 缓解：
  - 在计划里把 vararg 切换单独列为阶段 7
  - 测试矩阵中强制保留 engine vararg anchor

### 风险 2：internal helper 前缀没有纳入 `CBodyBuilder` 的 raw ptr 合同

- 缓解：
  - 阶段 5 明确要求同步更新：
    - `checkGlobalFuncRequireGodotRawPtr(...)`
    - `checkGlobalFuncReturnGodotRawPtr(...)`

### 风险 3：模板重新复制出另一套错误 ABI

- 缓解：
  - non-vararg helper 明确按 Godot `PtrToArg<T *>` 槽位合同实现
  - 为 object 参数专门加 codegen 文本断言

### 风险 4：bind lookup 失败路径未处理，导致 `NULL` bind 崩溃

- 缓解：
  - helper 内统一加 `GDCC_PRINT_RUNTIME_ERROR(...)`
  - non-void helper 返回类型默认值

### 风险 4.1：直接照抄 `gdextension-lite` variadic macro，带入错误的 error / cleanup 顺序

- 缓解：
  - 阶段 4 与阶段 7 明确禁止复用 `gdextension-lite` variadic class-method 宏
  - 文本断言必须覆盖：
    - `_error.error` 检查先于 typed unpack
    - `void` vararg helper 不会把 `NULL` 传给 `r_ret`

### 风险 4.2：helper 与 caller 对 vararg tail 的 ownership 边界不清，造成 double-destroy 或漏销毁

- 缓解：
  - 阶段 7 明确冻结 ownership split：
    - helper 只销毁 fixed prefix 与本地 return `Variant`
    - caller 继续销毁 extra vararg tail / default temp / call-site temp
  - 用 `Object.call(...)` / `Node.call(...)` exact vararg anchor 锁定“fixed prefix helper-own、tail caller-own”合同

### 风险 5：默认参数逻辑被复制到模板，造成 shared semantic 漂移

- 缓解：
  - 默认参数补齐保持在 `CallMethodInsnGen.validateFixedArgsAndCompleteDefaults(...)`
  - helper 只处理“已经给全的实际参数”

### 风险 6：`Node.add_child(...)` 虽切了 exact route，但测试没有把它补回

- 缓解：
  - 在 DoD 中明确要求恢复 `Node.add_child(...)` runtime regression
  - 并明确这条 regression 必须使用 typed receiver，而不是 dynamic fallback 形状

### 风险 6.1：`Node.add_child(...)` 回归通过后，被误写成“`gdextension-lite` ABI known limit 已删除”

- 缓解：
  - 在文档同步阶段明确要求“缩窄 known limit 适用面”，而不是删除事实源
  - 把结论严格限定为：
    - `gdcc` exact engine `CALL_METHOD` route 已脱离这条限制
    - `gdextension-lite` shared generator debt 仍需保留记录

### 风险 7：阶段 5 停在 object 修复后，bitfield 仍长期挂在旧 wrapper-compatible surface

- 缓解：
  - 在 DoD 中明确阶段 5 只是过渡态，不能视为项目完成
  - `ArrayMesh.add_surface_from_arrays(...)` 必须作为阶段 6 的主锚点保留

### 风险 8：static engine method 被错误套进实例 helper ABI

- 缓解：
  - collector key / helper 命名显式纳入 `isStatic`
  - 阶段 4 明确区分 static / instance helper 签名
  - 阶段 5/6/7 明确保留 `warnStaticMethodCall(...)` 与“static path 不传 receiver”的现有合同

### 风险 9：方案 B 落地时模板仍继续走 public `generateFuncBody(...)`

- 缓解：
  - 阶段 3 明确要求 `entry.c.ftl` 改走 session-bound body renderer
  - 测试中显式断言 `engine_method_binds.h` 的 used-method 集合与 `entry.c` 成功 render 的 exact engine call 对齐

### 风险 10：方案 B 被偷简化回共享全局 session，重新引入隐式状态污染

- 缓解：
  - 阶段 2 明确 session 为 `generate()` caller-owned、sink 为单函数 local buffer
  - public `generateFuncBody(...)` 固定 no-op sink，不允许隐式绑定 module session
  - 失败 render 只有丢弃 sink，不存在 reset 共享 collector 的补救合同

## 执行顺序建议

1. 阶段 1：补齐 `ResolvedMethodCall` bind 身份
2. 阶段 2：记录已使用 exact engine method
3. 阶段 3：生成并接入 `engine_method_binds.h`
4. 阶段 4：在头文件中生成 internal helper
5. 阶段 5：切 non-vararg exact route，并保留过渡期 bitfield helper surface
6. 阶段 6：收敛 non-vararg helper surface，移除 bitfield wrapper ABI 遗产
7. 阶段 7：切 vararg exact route
8. 阶段 8：补全 `Node.add_child(...)`、`ArrayMesh.add_surface_from_arrays(...)` 与 build/runtime 回归，随后同步文档
