# Engine Method Bind 实现说明（最终合并版）

> 本文档是 `CALL_METHOD` exact engine route 在 C Backend 的唯一事实源。  
> 只保留当前实现、长期约定、测试锚点和仍对后续工程有价值的反思。

## 文档状态

- 状态：Implemented / Maintained
- 更新时间：2026-04-19
- 范围：`CALL_METHOD` 的 exact engine route
- 作用域澄清：
  - 包含“实例语法命中 static engine method”这条现存 `CALL_METHOD` 合同
  - 不覆盖 `CALL_GLOBAL`、property、index、constructor、`CALL_STATIC_METHOD`
- 关联文档：
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/module_impl/frontend/frontend_exact_call_extension_metadata_contract.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`

## 当前最终状态

### 生成产物与调用面

- `CCodegen.generate()` 稳定生成：
  - `entry.c`
  - `engine_method_binds.h`
  - `entry.h`
- `entry.h` 无条件包含 `engine_method_binds.h`
- exact engine `CALL_METHOD` 已不再走 `gdextension-lite` public wrapper
- exact engine route 当前统一发布 backend-owned helper：
  - non-vararg：`gdcc_engine_call_<owner>_<method>_<symbolId>(...)`
  - vararg：`gdcc_engine_callv_<owner>_<method>_<symbolId>(...)`
  - static non-vararg：`gdcc_engine_call_static_<owner>_<method>_<symbolId>(...)`
  - static vararg：`gdcc_engine_callv_static_<owner>_<method>_<symbolId>(...)`
- bind accessor 当前统一发布：
  - instance：`gdcc_engine_method_bind_<owner>_<method>_<symbolId>()`
  - static：`gdcc_engine_method_bind_static_<owner>_<method>_<symbolId>()`

### 路由事实

- non-vararg exact engine route：
  - direct method-bind lookup
  - direct `godot_object_method_bind_ptrcall(...)`
- vararg exact engine route：
  - direct method-bind lookup
  - direct `godot_object_method_bind_call(...)`
- 缺失非零 bind hash 的 exact engine route 现为显式失败：
  - 不再静默回退 `godot_<Owner>_<method>`
  - 不再静默回退 `gdextension-lite` wrapper

### 当前行为锚点

- non-vararg runtime 锚点：
  - typed `Node.add_child(...)`
  - typed `Node.add_child(child, false, 1)`
  - `ArrayMesh.add_surface_from_arrays(...)`
- vararg runtime 锚点：
  - `Object.call(...)` / `Node.call(...)`
  - `SceneTree.call_group_flags(...)`
- symbol 稳定性锚点：
  - 只改 metadata 主 `hash` / `hashCompatibility` 时，helper / accessor / usage key 不漂移

## 上游 ABI 依据

- Godot `core/object/method_bind_common.h`
  - `MethodBind::ptrcall(...)` 最终进入 `call_with_ptr_args(...)` 等模板路径
- Godot `core/variant/method_ptrcall.h`
  - `PtrToArg<T *>` 将 `p_args[i]` 解释为 `T *const *`
  - 因此 object 参数传给 `ptrcall` 的必须是“对象指针变量的地址”，而不是对象指针值本身
- 这条上游语义直接约束 gdcc 的 exact engine helper 分层：
  - caller 必须继续保持 normalized callable surface
  - helper 才是唯一允许把 normalized 参数落实成 `ptrcall` 槽位表达式的一层
- `gdextension-lite` 仍保留自己的 wrapper ABI 责任面，但它已经不是 migrated exact engine route 的事实来源

## 架构与职责边界

### 1. `ResolvedMethodCall` 提供 lookup 身份，但不负责 generated symbol 身份

- `BackendMethodCallResolver.ResolvedMethodCall` 继续承载：
  - `DispatchMode`
  - owner / method / return / normalized parameter types
  - `isStatic`
  - `isVararg`
  - `EngineMethodBindSpec`
- `EngineMethodBindSpec` 只服务 bind lookup：
  - 主 `hash`
  - `hashCompatibility`
- 这层身份不参与 helper / accessor 命名，也不参与 usage 去重

### 2. generated symbol 身份由稳定 ABI surface 驱动

- `EngineMethodSymbolKey` 是 exact engine helper / accessor 的稳定身份
- 最小字段：
  - `ownerClassName`
  - `methodName`
  - `isStatic`
  - `EngineMethodAbiSignature`
- generated symbol identity 与 lookup identity 必须严格分层：
  - lookup 看 `EngineMethodBindSpec`
  - 命名 / 去重看 `EngineMethodSymbolKey`
- 维护约束：
  - 不允许再把 metadata 主 `hash` 直接拼进 helper/accessor 名
  - 不允许把 `hashCompatibility` 偷塞回 symbol key

### 3. ABI descriptor 是命名与去重的唯一协议

- `EngineMethodAbiCodec` / `EngineMethodAbiSignature` 是 ABI descriptor 的唯一事实源
- descriptor 语法固定为：
  - `P<paramDescriptors>_R<returnDescriptor>[_Xv]`
- 语义：
  - `P...`：参数序列
  - `_R...`：返回类型
  - 无 `_Xv`：non-vararg
  - 有 `_Xv`：vararg
- 类型编码规则：
  - 非 `Object` 叶子类型使用 codec 中央维护的一字母编码
  - `Array[T]`：`A<elementDescriptor>_`
  - `Dictionary[K, V]`：`D<keyDescriptor><valueDescriptor>_`
  - `Object`：`L<length><ClassName>_`
- 设计目标：
  - descriptor 可稳定生成
  - descriptor 可稳定解析
  - reviewer 可从 helper 名直接看出 callable ABI
- 维护约束：
  - 不允许并行维护第二套 descriptor 拼接逻辑
  - 不允许用摘要算法、`hashCode()`、默认 `toString()` 充当长期 symbol identity

### 4. usage 收集必须是 caller-owned session，而不是隐式共享状态

- `EngineMethodUsageSession` 持有 module 级 used-engine-method snapshot
- `EngineMethodUsageBuffer` 只服务单函数 render
- `CCodegen.generateFuncBody(clazz, func)` 的 public 入口固定走 no-op buffer
- `CCodegen.generate()` 才持有 module session，并在单函数 render 成功后 commit
- 这是稳定合同，不是实现偏好：
  - focused tests 直接调用 `generateFuncBody(...)` 时不能被隐式共享状态污染
  - 失败的 body render 不能向 module session 留下脏 usage

### 5. helper 参数面与 slot mode 必须由 backend 共享逻辑统一发布

- `CGenHelper.collectEngineMethodHelperParameters(...)` 负责 helper surface 与 slot metadata
- `engine_method_binds.h.ftl` 只消费共享参数面与 slot mode 渲染 helper body
- 不允许在模板、resolver、collector、测试里各写一套“看起来相同”的 helper 参数推导逻辑

## exact engine helper 合同

### Non-vararg helper

- call site 继续通过普通 helper 调用模型接入：
  - `CallMethodInsnGen` 仍调用 `callAssign(...)` / `callVoid(...)`
  - `CBodyBuilder` 继续统一处理 raw Godot object ptr 转换
- caller-side fixed args 必须继续保持 shared-normalized / backend-normalized callable surface
- 这条 normalized caller surface 是显式 non-regression 合同：
  - 不允许在 `CallMethodInsnGen` 侧重做 `ptrcall` 数组构造
  - 不允许在 helper 边界前重做 wrapper-era pointer shaping
- helper 内部 slot materialization 规则：
  - object：以 normalized pointer variable 进入 helper，并向 `ptrcall` 提交 `&argN`
  - enum / bitfield：helper 内部本地物化 raw slot，再提交 `&local_slot`
  - value-semantic wrapper：由 helper slot mode 决定最终是 `argN` 还是 `&local_slot`
- static helper 合同保持不变：
  - call site 继续输出 warning
  - helper 签名不接收 receiver
  - bind 调用固定传 `NULL`

### Vararg helper

- helper 只拥有 fixed prefix packed `Variant` 与本地 return `Variant`
- caller 继续拥有：
  - extra `const godot_Variant **argv`
  - `godot_int argc`
  - default temp
  - call-site temp
- helper 只 pack fixed prefix，不重新 pack caller-owned vararg tail
- `_error.error` 检查必须先于 typed unpack
- helper-owned temps 在 success / error 两条路径都必须完整清理
- `void` helper 不允许把 `NULL r_ret` 传给 `godot_object_method_bind_call(...)`

## 命名与兼容性约定

### helper / accessor 命名

- helper/accessor 名称必须同时满足：
  - backend-owned namespace
  - owner / method 可读
  - static / vararg 语义可读
  - ABI descriptor 可读且可逆
- 不允许：
  - 回退为 `godot_<Owner>_<method>`
  - 与 `gdextension-lite` public wrapper 同名
  - 用 metadata 主 `hash` 作为 helper/accessor 身份后缀

### 兼容性来源

- helper surface 兼容性只能来自：
  - 参数 family
  - normalized type
  - 必要的 leaf raw metadata
- 不允许：
  - 按单个 API 名称写分支
  - 按 owner / method 名字硬编码 slot 规则
  - 为 key / naming 另造一套与 helper surface 不一致的“兼容性模型”

## 回归测试基线

- `MethodResolverParityTest`
  - bind spec 发布
  - helper slot metadata 发布
  - missing-hash exact route 显式失败
- `CCodegenEngineMethodUsageSessionTest`
  - module session / local buffer / success-only commit 合同
- `CCodegenEngineMethodBindHeaderTest`
  - non-vararg `ptrcall` slot contract
  - enum / bitfield local slot materialization
  - mixed fixed-prefix vararg helper surface
  - helper-owned cleanup / error-decoding 顺序
- `CallMethodInsnGenTest`
  - caller-side normalized helper surface
  - 不再发旧 wrapper-compatible cast
  - static engine method warning + receiver-free helper 合同
- `CallMethodInsnGenEngineTest`
  - `Node.add_child(...)`
  - `Node.add_child(child, false, 1)`
  - `ArrayMesh.add_surface_from_arrays(...)`
  - `Object.call(...)`
  - `SceneTree.call_group_flags(...)`
  - helper 路由不再落回 legacy wrapper
- `CCodegenTest`
  - 生成文件集合与 `engine_method_binds.h` 接入
- `GdScriptUnitTestCompileRunnerTest`
  - `test_suite` 中 exact engine runtime anchors 的资源集合

## 已知边界

- 本文档只覆盖 migrated exact engine `CALL_METHOD` route
- 仍消费 `gdextension-lite` wrapper 的其他路径，其 ABI debt 继续按各自事实面处理
- stock runtime API 目前仍缺少稳定、非 editor-only 的“object 位于 fixed prefix 的 vararg method”样本
  - 当前由 focused header regression 补足该类 helper surface 的覆盖
- 与回归脚本、test suite 暴露出的剩余不足相关的记录，统一留在：
  - `doc/test_error/test_suite_engine_integration_known_limits.md`

## 工程反思

1. helper/accessor 命名一旦与 lookup `hash` 绑定，就会把无关 metadata 漂移放大成测试和审阅噪音；symbol identity 必须只反映 callable ABI。
2. `PtrToArg<T *>` 的 `T *const *` 语义说明“caller 保持 normalized surface、helper 负责 slot shaping”不是实现分层偏好，而是 ABI 正确性本身。
3. usage 收集如果偷偷挂在共享 helper/codegen 状态上，会把 `generateFuncBody(...)` 从近似纯函数 API 变成历史相关 API；这类隐式状态必须继续避免。
4. 迁移阶段的时间线、进度同步和临时过渡态不应长期保留在实现文档中；长期文档只应陈述当前事实、稳定约束和仍对未来维护有价值的经验。
