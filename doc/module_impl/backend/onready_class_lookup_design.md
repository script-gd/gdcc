# `@onready` 类级查询表设计

## 1. 范围

本文约定非引擎 `_ready` 入口按需初始化的类级分发、查询失败处理与资源生命周期；ready-time lowering 的整体语义见 `onready_lowering_implementation.md` §3.2。

## 2. 设计理由与不变项

`@onready` 合法 owner 必须派生自 Node，但 Node 不一定已经入树，也可以离树存活；因此“通常随场景树使用”是取舍依据，不是对象数量或调用频率的硬上限。本设计不依赖这个假设保证正确性，也不声称未经测量的性能收益。

实例实际类型在继承的 `_ready` 中不能仅由声明类确定；编译期计算的是候选类与初始化链的对应关系，运行时只补充“当前是哪一类”。对象身份、类信息仍由 Godot 管理，不引入 instance ID 到函数地址的额外缓存。

保留如下合同：

- 构造期先 first-write 类型默认值；ready 应用仍经既有 ownership-aware overwrite helper，父链优先、类内声明顺序不变。
- 引擎组合实现每次 READY 都直接调用本类链函数，不因 DONE 已置位而跳过；无用户 `_ready` 仍可应用。
- 非引擎入口只尝试一次；构造完成前不尝试、不置 DONE。用户函数体仍照常执行。
- 普通 `_ready` 在 `goto entry` 前执行入口按需初始化；coroutine 只在 start thunk 最前执行，组合实现仍转发同步 shell 以保留状态引用释放。
- 不改 vtable planner、slot 布局、共享表规则、fat pointer、LIR/XML 格式及 annotation placement 规则。
- 跨模块 GDCC 父类、混合 GDScript 继承和构造期 add_child 的既有差异不扩大支持承诺。

## 3. 实例布局与 flags

根段顺序为 `_object`、可选 `_vtable`、`uint8_t _gdcc_flags`，然后才是本类属性；onready 实例类型分发不占用根段字段。派生类仍以直接父 wrapper `_super` 为首成员。

在既有字节中使用一个新位，不新增 class ID、表地址、函数指针或堆分配：

| 位 | 名称 | 定义 |
| --- | --- | --- |
| bit0 / 1 | 析构守卫 | 仍只以 `& 1` 读取、`|= 1` 写入 |
| bit1 / 2 | `GDCC_FLAG_ONREADY_DONE` | 本实例已尝试入口初始化或已经由引擎应用；不是成功证明 |
| bit2 / 4 | `GDCC_FLAG_CTOR_COMPLETE` | 完整构造链已返回，或 recreate 按既有策略视为构造完成 |
| bit3 / 8 | `GDCC_FLAG_HAS_ONREADY` | **实例实际类**的 frozen `chainHasOnreadyInitializer`，不是声明类或后代存在性 |

`HAS_ONREADY` 包含“本类无 initializer、祖先有”的情况；仅有无源码 initializer 的注解不算。HAS 是类型事实，DONE 是执行事实，两者不能互相替代或通过一次查询失败改写。

三个 flag 常量以匿名 enum 发射（`GDCC_FLAG_*` = 2/4/8 的匿名 enum）而非 `#define`：宏会文本改写合法的同名成员/函数/类标识符。enum 常量仍占普通文件作用域标识符，生成函数/typedef/表与其同名时由 `validateFileScopeSymbolsDisjoint` 统一登记后 fail-fast；用户成员名不受影响、保持合法。

| 路径 | flags 初值或变更 |
| --- | --- |
| create | 链事实真写 `HAS_ONREADY`，否则写 0；绑定实例及通知之前完成 |
| POSTINITIALIZE | 完整 constructor 返回后 `|= CTOR_COMPLETE` |
| recreate | 写 `CTOR_COMPLETE | ONREADY_DONE | (本代链事实 ? HAS_ONREADY : 0)` |
| 入口尝试 / 引擎组合实现 | `|= ONREADY_DONE`；不改 HAS、CTOR 或析构位 |
| destructor | `|= 1`；不清除其他位 |

同一基类的干净旁支即便继承了入口初始化代码，也因 HAS 为 0 直接退出；**不查类名、不扫描表、不调用 key getter**。整个模块无有效 onready 时不生成查询表、key getter 或查询 helper。

## 4. 编译期计划与表结构

### 4.1 冻结计划

在 prepare 阶段、默认值合成前，基于既有 frozen onready 计划生成不可变 `RootLookupPlan(rootClassName, classNames)`：

- `rootClassName` 是物理嵌入链的 GDCC 根，不以第一个有 onready 的祖先代替。计划构建从已知 module `LirClassDef` 对象起步：`current = classDef`、`visited = {classDef.name}`；仅当 `registry.isGdccClass(current.superName)` 为真时，取该父类定义并继续向上，每次加入 visited 前检环。首跳不要求当前类已注册；引擎、空或未知父名终止于当前类。该规则镜像 `entry.h.ftl` 的父 wrapper 嵌入条件，并保留现有部分注册测试的容错，不修改 `resolveGdccRootClassName("Missing")` 对未知起始类名抛错的合同。
- 已知但未注册的 `UnregisteredParent extends Node` 以自身为根，仍可有自己的查询行；`ChildOfUnregistered extends UnregisteredParent` 在父名未注册时独立为根，不能继承前者链事实。未注册起始类若父名已注册，则从已知起始定义沿该父边继续，不回到要求起始类已注册的字符串入口。物理根可来自注册表中的模块外祖先；这只描述布局，表成员和 onready 链仍限制在本 module，不意味着支持跨模块继承。
- `classNames` 是当前 module 中物理根相同且链事实为真的类名列表，按 module 原顺序冻结，`List.copyOf`，不持有可变类列表的别名。
- 包含只继承祖先初始化器的中间类和抽象类；它们有自己的精确类名行，不能只列“本类有 initializer”的类。
- 不含引擎类、隐藏 coroutine 状态类、其他模块类或链事实为假的类。注册表中存在但不在本 module 的父类不扩大跨模块支持。
- 每个符合条件的类恰好一行；类名使用与 `godot_classdb_register_extension_class5` 完全相同的 canonical name，不使用源码别名或 C identifier 转义后的名字。
- prepare 后的注解变更不重选行；新的 codegen 会话重新从 provenance 正确的事实创建计划。

`COnreadyPlan` 持有这份只读表计划，`CGenHelper` 只查询和渲染，不额外缓存第二套动态类事实。

### 4.2 生成的 C 结构

对每个非空物理根计划 R 生成一个私有、非空的 `static const` 数组：

- entry typedef：`gdcc_<R>_onready_lookup_entry`。
- 数组：`gdcc_<R>_onready_lookup_table`。
- 每行两个签名固定的函数指针：`const godot_StringName* (*class_name)(void)`、`void (*apply)(<R>* root)`。
- key getter：`static const godot_StringName* <C>__gdcc_onready_class_name(void)`，体内只返回 `GD_STATIC_SN(u8"<canonical name>")`。
- apply 指向已有 `<C>__gdcc_onready_apply_root`；不存强转后的 `<C>*` 链函数，不把函数地址转为对象地址。
- 查询/应用 helper：`static void <R>__gdcc_onready_initialize_if_needed(<R>* root)`，包含 §5 全部逻辑。

key getter 使表本身完全是编译期可初始化数据，不在表里保存需要手动重建的 StringName 对象或其借用地址。**禁止在循环里调用 `GD_STATIC_SN(row.class_name)`**：该宏按展开点缓存，不按参数内容缓存，循环中的不同名字会错误共享同一个展开点。

不新增哈希表、动态注册、每实例缓存、class tag 或 ClassDB 继承遍历。首版按本根表线性查找并用 StringName 精确相等比较，复杂度为 O(本根有效类数)；匹配后立即结束。

新 getter、entry typedef、表、查询 helper 都经 `validateFileScopeSymbolsDisjoint` 显式登记；命名集中在 `CGenHelper`，不由模板和校验器各拼一份。

### 4.3 发射与测试复用

新增生成片段 `template_451/onready_lookup.ftl`，由 `entry.c.ftl` include，**仍属于同一个 entry TU**，不是新的 runtime `.c` 文件。片段承载 key getter、typed table 和查询 helper；位于根适配器的段首原型之后、`initialize` 之前，因此 derived-first 输入不会先用后声明。

`renderOnreadyEntryInitialization` 仍按有效 `_ready` 及本类/后代事实决定是否发射；非空时只物化物理根 wrapper 并调用本根 helper。所有字段访问使用参数化起始表达式的 helper。精确筛选和 flags 判断由根 helper 统一承担，普通函数与 coroutine 不生成两份算法。

该独立生成片段也供 native 测试 probe 直接渲染、编译并执行；测试必须运行同一份生成逻辑，不能手写一个相似查找算法替代。

## 5. 运行期算法与错误策略

根 helper 参数是按现有调用合同保证有效、存活的 `<R>*` wrapper；不改变普通方法 receiver 的存活性检查责任。

严格执行以下步骤：

1. DONE 已置位则返回。
2. CTOR_COMPLETE 未置位则返回，保持 DONE 未置位。
3. HAS_ONREADY 未置位则返回，保持 flags 不变。
4. 先 `|= ONREADY_DONE`，表示**这次初始化尝试包括动态类型解析**。重入和重复手调都不能再次查询。
5. 经 `<R>_object_ptr(root)` 获取 raw Godot 对象；为 NULL 时报告内部运行期错误并返回，不调用查询 API。
6. 调 `godot_object_get_class_name(raw, class_library, &actual_name)`，恰好一次。使用 GDExtension 的 library-aware 接口，不调用可被脚本影响的 `Object.call("get_class")`，也不使用 `object_cast_to`/class tag 替代。
7. 查询失败：报告内部运行期错误并返回；沿用现有 helper 的失败约定，不销毁未成功发布的输出。
8. 查询成功：在线性表中用 `godot_StringName_op_equal_StringName` 比较 `actual_name` 和每行 getter 返回的借用 key。
9. 命中后保存该行 typed apply 指针，**先销毁 actual_name，再调用 apply(root)**；只调用一条完整链，不继续匹配祖先或其他行。
10. 未命中：销毁 actual_name，报告“有 onready 的实例在本根表中无精确类名行”的内部运行期错误并返回；绝不静默退回声明类或父类链。

成功查询后的 actual_name 由当前调用持有，所有后续正常出口恰销毁一次；getter key 归 TU-local StringName registry，借用者不销毁。只在 actual_name 生命周期结束后进入可能触发用户逻辑的 apply，避免重入扩大临时资源存活期。

查询失败/缺行不清 DONE、不重试，不表示初始化成功；用户 `_ready` 继续执行（不伪造新的异常 ABI）。后续引擎 READY 仍不看 DONE，因而仍可直接应用。查询错误必须有可识别 runtime diagnostic，不能被当作正常“无 onready”情况。

## 6. 查询次数与双入口

| 场景 | 本设计新增类名查询 |
| --- | --- |
| 干净 Base，只有某个后代有 onready | 0，Base 实例 HAS=0 |
| 干净旁支继承 Base 的 `_ready` | 0，旁支实例 HAS=0 |
| 注解无源码 initializer、全链均无有效 initializer | 0，HAS=0 |
| 仅继承祖先 onready 的派生实例 | 首次合格非引擎入口 1 次，匹配派生类自己的行 |
| `_init` 中手调 `_ready` 或显式发 READY | 0，构造未完成；组合实现同样跳过 apply |
| 正常引擎首次 READY，以及 `request_ready()` 重放 | 0，组合实现直接调本类链；进入用户函数时 DONE 已置位 |
| 第一次引擎 READY 之前手调 `_ready` | 最多 1 次，后续手调/super 因 DONE 跳过 |
| lookup API 失败或表缺行后再次手调 | 第一次 1 次，后续 0；仍保持 HAS 类型事实 |
| recreate 后手调或经混合 GDScript super 进入 | 0，recreate 的 DONE 已置位 |

查询表只替代非引擎入口的实例分发。引擎 get/call virtual 的组合入口、构造 fail-closed、无用户 `_ready` 和 coroutine shell/start thunk 路由不改。

## 7. 热重载与资源生命周期

### 7.1 没有跨代查询状态

表只含本映像的 getter/适配器地址，存活实例、Godot instance binding、HRX hub、协程帧均不保存表行、表地址或查询结果。正常退出、reload 和同映像重初始化都不新增一套对象级清理。

旧代顺序仍为取消协程、销毁 static backing、注销类并 free 旧 wrapper、销毁 StringName/String registry，最后 HRX deinitialize。表在整个旧代调用仍可执行期间有效，随映像卸载；它不是 static backing，不在 static backing 清理阶段逐项销毁。

新映像中的 `static const` 表自然包含本代地址；同映像重初始化时地址仍属于同一映像，不需要重写表。key getter 的固定 `GD_STATIC_SN` 展开点用既有 registry generation 重建名字，不能新增 init-once 布尔、缓存已销毁的 StringName 指针或把宏移到其他 TU。

沿用卸载前 quiescence：停止新调用并等待已有调用结束；只读表和 generation 戳不提供并发 dlclose 安全，也不解决同路径加载器复用旧代码的问题。

### 7.2 recreate 与语义变化

旧 wrapper 按旧库布局释放，新 wrapper 按新库 `sizeof` 分配；不把旧 bytes 原地解释为删除函数字段后的新布局。父类变更等既有不支持的 reload 不在本设计中开放。

recreate 只依据**本代实际类的冻结链事实**重算 HAS，写入 CTOR_COMPLETE 和 DONE，不拷贝旧 flags，不查类名，不运行真实 onready initializer；随后 init_fields、引擎 storage setter 恢复流程不变。

| 两代变化 | 重建后普通 `_ready` | 引擎 `request_ready()` 后重放 |
| --- | --- | --- |
| 有 onready，initializer 改变 | DONE 阻止覆盖恢复值，不查询 | 新组合实现直接跑新初始化器 |
| 从无到有 onready | HAS=1、DONE=1，不补执行、不查询 | 新组合实现应用新链 |
| 从有到无 onready（全链变空） | HAS=0、DONE=1，不查询 | 不再有本类 onready 组合入口，仅保留用户 ready 行为 |
| 去掉本类 onready，但祖先仍有 | HAS=1、DONE=1，不查询 | 直接应用本代祖先链 |
| 只恢复 storage，不再进入 READY | 不覆盖已恢复 storage | 不自动补发 READY |

混合 GDScript 子类调用 GDCC `super._ready()` 时，仍遵守既有“晚执行、DONE 后不重放”的边界。library-aware 类名查询必须取得本库对应的 GDCC wrapper 类名；将其作为真实 Godot 验收项，不能假设脚本 `class_name`、动态方法返回值或任意祖先名字可以替代。未知扩展后代和跨模块链仍 fail-closed，不通过父类回退假装支持。
