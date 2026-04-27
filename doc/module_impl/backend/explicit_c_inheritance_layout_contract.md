# 显式 C 继承布局合同

> 本文档作为 backend 显式 C 继承布局实现的长期事实源。  
> 只保留当前代码已经落地的合同、约束、测试锚点、长期风险与工程反思，不记录阶段性实施流水账。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - `src/main/java/gd/script/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
  - `src/test/java/gd/script/gdcc/backend/c/**`
- 更新时间：2026-04-27
- 上游对齐基线：
  - Godot ClassDB 继承关系决定 source-visible / runtime-visible 继承层级
  - GDExtension extension instance 回调会把同一个实例指针传给 GDCC 父类/子类方法路径
- 关联文档：
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/backend/call_method_implementation.md`
  - `doc/module_impl/backend/cbodybuilder_implementation.md`
  - `doc/module_impl/backend/backend_ownership_lifecycle_contract.md`

## 当前最终状态

### 核心实现落点

- GDCC wrapper struct 布局：
  - `src/main/c/codegen/template_451/entry.h.ftl`
- per-class object pointer helper 与 create/bind 链路：
  - `src/main/c/codegen/template_451/entry.c.ftl`
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
- 最近 native ancestor 解析与 helper 符号渲染：
  - `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
    - `resolveNearestNativeAncestorName(...)`
    - `renderGdccObjectPtrHelperName(...)`
- GDCC/Godot 对象指针表示转换与 GDCC 上行转换：
  - `src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
    - `convertPtrIfNeeded(...)`
    - `valueOfCastedVar(...)`
- `CALL_METHOD` receiver / owner 转换：
  - `src/main/java/gd/script/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`

### 当前已锁定的实现结论

- GDCC 类 wrapper 使用显式 `_super` 链：
  - 根 GDCC 类直接持有 `GDExtensionObjectPtr _object`
  - 非根 GDCC 类的首字段是父类 wrapper：`Parent _super`
- 父类子对象必须位于偏移 0，保证从 GDCC 子类到 GDCC 父类的 C-level 上行路径可证明。
- GDCC -> Godot object pointer 转换必须通过 per-class helper：
  - `<Class>_object_ptr(<Class>* self)`
  - `gdcc_object_to_godot_object_ptr(value, <Class>_object_ptr)`
- GDCC 子类不保证存在直接 `_object` 字段，任何新代码都不能重新依赖 `self->_object` 作为通用对象指针访问方式。
- `*_class_create_instance(...)` 构造最近 native ancestor，不构造直接 GDCC 父类。
- `godot_object_set_instance(...)` 与 `godot_object_set_instance_binding(...)` 只在最派生 GDCC 创建路径执行一次。
- GDCC->GDCC 转换只允许可证明的上行转换，生成 `_super` 链表达式；不可证明路径必须 fail-fast。
- `CALL_METHOD` 在 GDCC 子类 receiver 调用 GDCC 父类 owner 方法时，必须复用上述安全上行路径，不能退回裸 C cast。

## 长期合同

### 1. wrapper 布局合同

- 根 GDCC 类 struct 继续直接包含 `_object` 字段。
- 非根 GDCC 类 struct 的第一个字段必须是父类 wrapper，字段名固定为 `_super`。
- `_super` 字段必须保持首字段位置，不能被调试字段、metadata 字段或当前类字段挤到后面。
- 当前类字段继续放在当前 wrapper 层；父类字段访问必须通过 `_super` 链或父类 helper。
- 任何修改 struct 模板的变更都必须同时验证：
  - 多级继承前缀布局仍稳定
  - per-class object pointer helper 仍能从任意子类层取到根 `_object`
  - GDCC 子类调用 GDCC 父类方法时不需要裸 cast

### 2. object pointer helper 合同

- 每个 GDCC 类必须生成：
  - `<Class>_object_ptr(<Class>* self)`
  - `<Class>_set_object_ptr(<Class>* self, GDExtensionObjectPtr obj)`
- 根类 helper 直接读写 `self->_object`。
- 子类 helper 必须沿 `_super` 链委托到父类 helper。
- GDCC -> Godot object pointer 转换固定通过：
  - `gdcc_object_to_godot_object_ptr(value, <Class>_object_ptr)`
- 禁止新增对旧式“所有 GDCC wrapper 都有直接 `_object` 字段”假设的依赖。
- helper 是指针表示转换边界，不承担 ownership transfer；retain/release 规则继续由 `backend_ownership_lifecycle_contract.md` 约束。

### 3. instance 创建合同

- `*_class_create_instance(...)` 必须用最近 native ancestor 调用 `godot_classdb_construct_object2(...)`。
- 最近 native ancestor 的定义是：沿 GDCC 继承链向上查找第一个非 GDCC 父类。
- 祖先解析必须 fail-fast：
  - 继承链循环
  - 父类定义缺失
  - 找不到任何 native ancestor
- 创建路径顺序保持：
  1. construct native object
  2. allocate GDCC wrapper
  3. set object pointer through `<Class>_set_object_ptr(...)`
  4. bind extension instance
  5. bind instance binding
  6. send post-initialize notification when required
- extension instance / binding 绑定只属于最派生创建路径；父 wrapper 作为子对象存在，不再单独创建或重复绑定。

### 4. `RefCounted` 创建边界合同

- `RefCounted` 初始化不能硬编码进共享 `*_class_create_instance(...)`。
- 通过 Godot / GDScript 创建继承 `RefCounted` 的 GDCC 类时，初始引用计数由 Godot 自身创建路径负责。
- C backend 显式创建继承 `RefCounted` 的 GDCC 类对象时，生成代码负责在外层补齐初始化：
  - 非 `RefCounted` 目标继续直接调用 `XXX_class_create_instance(NULL, true)`
  - `RefCounted` 目标先调用 `XXX_class_create_instance(NULL, false)`
  - 然后对返回值执行 `gdcc_ref_counted_init_raw(..., true)`
- 这条边界必须继续保持 shared create hook 与 Godot/GDExtension 自身创建路径解耦。

### 5. GDCC 上行转换合同

- GDCC->GDCC 转换只支持可证明的上行转换。
- 可证明条件必须来自 `ClassRegistry#checkAssignable(from, to)` 以及完整的 GDCC 父链解析。
- 生成表达式必须使用 `_super` 链，例如：
  - `&($child->_super)`
  - `&($child->_super._super)`
- 裸 C cast 不能用于表达 GDCC 子类到 GDCC 父类转换。
- 以下情况必须 fail-fast：
  - source / target 不是可证明的 GDCC 上行关系
  - 继承链循环
  - 父类定义缺失
  - 目标父类不在 source 的 GDCC 父链上
- engine/native object 表示转换与 GDCC wrapper 上行转换是不同边界，不能用一个裸 cast 同时表达两者。

### 6. `CALL_METHOD` receiver 合同

- `CALL_METHOD` 继续先由 `checkAssignable(...)` 校验 receiver 能赋给 owner。
- GDCC receiver -> engine/native owner 时，必须先通过 per-class object pointer helper 取得 Godot raw pointer，再按目标 owner 表示转换。
- GDCC 子类 receiver -> GDCC 父类 owner 时，必须通过 `valueOfCastedVar(...)` 生成 `_super` 链表达式。
- 该路径不允许回退到：
  - 裸 C cast
  - 旧式通用 `_object` 宏
  - 假设所有 GDCC wrapper 物理形状相同的临时转换

## 与 Godot 的对齐点

### 1. ClassDB 继承关系不是 C struct 布局

Godot 运行时看到的类继承关系来自 ClassDB 注册信息。  
C struct 布局只决定 backend 自己传入 extension method callback 的实例指针能否被安全解释。

因此 backend 必须同时满足两条独立约束：

- ClassDB 注册继续发布正确的 `class_name` / `parent_class_name`
- GDCC wrapper 的 C-level 前缀布局能支撑父类方法读取父 wrapper 字段

### 2. extension instance 指针在父/子路径间共享

GDCC 子类对象调用父类方法时，Godot 仍会把同一个 extension instance 交给 callback。  
如果父类方法把该指针解释为父 wrapper，而子类 wrapper 不以前缀形式嵌入父 wrapper，就会重新打开布局未定义行为风险。

### 3. Godot object pointer 是 wrapper 内部事实，不是通用字段名合同

`_object` 只存在于根 GDCC wrapper 层。  
非根 wrapper 通过 `_super` 链间接持有同一个 Godot object pointer，因此所有 Godot object pointer 访问都必须走生成 helper。

## 回归测试基线

- `src/test/java/gd/script/gdcc/backend/c/gen/CCodegenTest.java`
  - 子类 struct 包含父类 `_super` 前缀字段
  - 每个类生成 object pointer getter/setter helper
  - `create_instance` 构造最近 native ancestor
  - 多级 GDCC 继承链仍稳定构造同一个 native ancestor
  - extension instance / binding 在最派生 create path 中各绑定一次
- `src/test/java/gd/script/gdcc/backend/c/gen/CBodyBuilderPhaseCTest.java`
  - GDCC 多级上行转换生成 `_super` 链
  - 非上行或不可证明转换 fail-fast
  - GDCC -> Godot pointer conversion 使用 per-class helper
- `src/test/java/gd/script/gdcc/backend/c/gen/CallMethodInsnGenTest.java`
  - GDCC 子类 receiver 调 GDCC 父类 owner 方法使用 `_super` 链
  - 不出现裸 cast 回退路径
- `src/test/java/gd/script/gdcc/backend/c/gen/CallMethodInsnGenEngineTest.java`
  - GDCC receiver -> engine/native owner 先经 helper 转换到 Godot raw pointer
- `src/test/java/gd/script/gdcc/backend/c/build/CallMethodInsnGenEngineInheritanceTest.java`
  - GDCC 子类调用父类静态 owner 方法
  - 动态 receiver 通过 generated helper 转成 Godot raw pointer
  - Godot runtime 侧 `child is Base` / `ClassDB.is_parent_class(...)` 可见性

依赖 Zig / Godot 运行环境的集成测试必须保持环境不可用时跳过，不得误报失败。

## 长期风险与维护提醒

1. struct 模板改动最容易破坏 `_super` 首字段不变量；任何 wrapper 字段调整都必须覆盖多级继承生成测试。
2. helper 看似只是小型 inline 函数，但它是“非根类没有直接 `_object` 字段”这一事实的唯一收口点；绕过 helper 会让子类路径重新依赖错误布局假设。
3. 最近 native ancestor 解析错误会让 `godot_classdb_construct_object2(...)` 构造 GDCC 父类名，进而破坏 Godot 创建链路。
4. 把 `RefCounted` 初始化塞进 shared create hook 会让 Godot/GDExtension 自己的创建路径和 C backend 显式构造路径混在一起。
5. 重新引入裸 C cast 可能让简单单级继承样例看起来通过，但在多级继承或父类字段访问时重新打开未定义行为风险。
6. `CALL_METHOD` receiver 转换同时涉及 static owner、runtime object pointer 和 wrapper layout，后续维护时必须分别验证这三层，不要用单个 cast 表达全部语义。

## 工程反思

1. ClassDB 继承和 C struct 继承是两套不同机制；只修正注册关系不能证明 callback instance 指针安全。
2. 生成式 C 后端里，布局不变量应尽量体现在模板和 helper 名称上，而不是靠调用点记住字段细节。
3. “能编译的 cast”不是“语义正确的上行转换”。对于 GDCC wrapper，必须让表达式形状直接体现 `_super` 路径。
4. create/bind 链路必须明确“谁负责构造 native object、谁负责绑定 extension instance、谁负责引用计数补齐”；三者混在一个 helper 里会让 Godot 创建路径和 C 显式创建路径互相污染。
5. 阶段计划文档不应长期充当事实源；最终实现文档必须只保留当前合同、回归锚点和仍有长期价值的边界说明。

## 非目标

- 修改 Godot ClassDB 注册语义
- 修改 `class_name` / `parent_class_name` 的来源规则
- 支持 GDCC 子类到父类之外的任意 wrapper reinterpret cast
- 把 GDCC/Godot pointer conversion 做成 ownership transfer 边界
- 在 `CALL_METHOD` 中引入第二套 receiver 上行转换实现
- 修改 Gradle 配置、构建脚本或测试执行策略
