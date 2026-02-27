# 显式 C 继承布局实施清单（GDCC C Backend）

## 文档状态

- 状态：Completed（阶段 0-4 已完成）
- 更新时间：2026-02-27
- 适用范围：`backend.c` 中 GDCC 类实例布局、实例创建链路、对象转换与调用生成
- 关联文档：
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/call_method_implementation.md`
  - `doc/module_impl/cbodybuilder_implementation.md`

## 背景与结论

- GDScript 中显示的继承层级由 ClassDB 注册关系决定，而不是 C struct 布局本身。
- 运行时“GDCC 子类对象调用父类方法”会把同一个 extension instance 作为回调实例传入；若子类/父类 C 布局不具备可证明的上行兼容性，存在未定义行为风险。
- 当前实现中：
  - GDCC 类 struct 为平铺布局（无显式 `_super` 链）。
  - `create_instance` 直接 `construct_object2(superName)`，在 GDCC->GDCC 继承时存在绑定链路风险。
  - `valueOfCastedVar` 对 GDCC 对象类型切换存在“裸 cast”路径，需要收敛到可证明安全的上行转换。

## 目标与非目标

### 目标

- 建立 GDCC wrapper 的显式 C 继承布局，确保子类实例可安全上行解释为父类实例。
- 修复实例创建链路，保证扩展实例绑定在创建阶段只走单次、可预期路径。
- 将 GDCC 对象指针转换收敛为结构化 helper，避免依赖“所有类均有直接 `_object` 字段”的假设。
- 为布局/转换/运行时行为建立回归测试矩阵，覆盖父类方法调用与继承可见性。

### 非目标

- 不改变 Godot ClassDB 注册语义（`class_name` / `parent_class_name` 保持现状）。
- 不在本阶段重构全部指令生成器，只处理受影响的对象转换与 `CALL_METHOD` 路径。
- 不修改 Gradle 配置与构建脚本。

## 详细改动清单

### 阶段 0：文档基线（必须先完成）

1. 文件：`doc/gdcc_c_backend.md`
- 明确新增“强制转换基线”并立刻生效：
  - 废弃 `godot_object_from_gdcc_object_ptr`。
  - 所有 GDCC -> Godot 对象指针转换必须通过“按类生成的专用 helper”完成。
  - 禁止新增任何对该宏的依赖；存量用法纳入迁移清单并逐步清零。

1. 文件：`doc/module_impl/call_method_implementation.md`
- 将 `CALL_METHOD` 的 receiver 转换规范对齐到专用 helper 基线。
- 明确“GDCC 子类 -> 父类 owner”必须走可证明安全的上行表达式，不得裸 cast。

1. 文件：`doc/module_impl/explicit_c_inheritance_layout_plan.md`（本文档）
- 将“文档先行”定义为后续代码改动的前置门禁：
  - 未完成阶段 0，不进入模板与代码实现阶段。
  - 后续评审以阶段 0 规则作为唯一规范基线。

### 阶段 1：模板层建立显式继承布局

1. 文件：`src/main/c/codegen/template_451/entry.h.ftl`
- 为每个 GDCC 类生成 `struct` 时引入显式父类字段：
  - 根 GDCC 类：保留 `GDExtensionObjectPtr _object;`
  - 非根 GDCC 类：以父类为首字段，例如 `Parent _super;`
- 保证父类子对象位于偏移 0，形成稳定前缀布局。
- 为每个类额外生成对象访问 helper 声明：
  - `static inline GDExtensionObjectPtr <Class>_object_ptr(<Class>* self);`

1. 文件：`src/main/c/codegen/template_451/entry.c.ftl`
- 实现 `<Class>_object_ptr(...)`：
  - 根类直接返回 `self->_object`
  - 子类沿 `_super` 链委托到父类 helper
- 校正构造/析构中访问路径：
  - 当前类字段继续使用 `self->field`
  - 若需父类字段访问，统一通过 `_super` 链或父类 helper

1. 文件：`src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
- 移除 `godot_object_from_gdcc_object_ptr` 在生成代码路径中的使用。
- 提供/保留统一的 helper 调用入口（由模板生成的按类专用 helper 负责最终转换）。
- 统一入口宏为：`gdcc_object_to_godot_object_ptr(obj, Class_object_ptr)`。
- 明确注释：GDCC 子类不保证存在直接 `_object` 字段，必须通过生成 helper 取对象指针。

### 阶段 2：实例创建链路修正（避免绑定风险）

1. 文件：`src/main/c/codegen/template_451/entry.c.ftl`
- `*_class_create_instance` 不再无条件构造 `superName`。
- 生成“最近可构造原生祖先”名称（最近 engine/native 祖先）并用于 `godot_classdb_construct_object2(...)`。
- 保持 `godot_object_set_instance(...)` 与 `godot_object_set_instance_binding(...)` 仅在当前最派生扩展创建路径执行一次。

1. Java 侧辅助（如模板数据不足）
- 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`、`src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`（按实际结构调整）
- 为模板提供 `classDef` 对应的“最近 native ancestor”字段，避免模板内复杂推导。

#### 阶段 2 实施同步（2026-02-27）

- 已完成：`src/main/c/codegen/template_451/entry.c.ftl`
  - `*_class_create_instance` 的 `godot_classdb_construct_object2(...)` 已切换为“最近 native ancestor”而非直接 `superName`。
  - 具体模板表达式：`${helper.resolveNearestNativeAncestorName(classDef)}`。
- 已完成：`src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
  - 新增 `resolveNearestNativeAncestorName(ClassDef classDef)`，沿 GDCC 继承链上溯并返回首个非 GDCC 祖先。
  - 包含防御性检查：继承环检测、缺失父类定义检测、空祖先 fail-fast。
- 已完成：`src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`
  - 新增/增强断言，覆盖：
    - GDCC 子类 `create_instance` 构造目标为最近 native ancestor（例如 `Node`）。
    - 深层 GDCC 继承链（`Leaf -> Mid -> Root -> Node`）仍稳定构造 `Node`。
    - `create_instance` 中 `godot_object_set_instance(...)` 与 `godot_object_set_instance_binding(...)` 在最派生创建函数内各出现一次。

### 阶段 3：收敛 GDCC 上行转换语义

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
- 调整 `valueOfCastedVar(...)`：
  - GDCC->GDCC：仅允许“可证明上行”的转换，生成 `_super` 链表达式或调用已生成 helper。
  - 禁止以裸 C cast 处理 GDCC 子类到父类转换。
  - 对非上行或不可证明安全的转换 fail-fast。

1. 文件：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`
- 保持 `renderReceiverValue(...)` 的 `checkAssignable` 预校验。
- 对 owner/receiver 为 GDCC 对象且存在继承关系时，依赖新的安全上行表达式。

#### 阶段 3 实施同步（2026-02-27）

- 已完成：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
  - `valueOfCastedVar(...)` 新增 GDCC->GDCC 专用路径：
    - 仅允许可证明的上行转换（`ClassRegistry#checkAssignable(from, to)` 必须为 true）。
    - 上行表达式通过 `_super` 前缀链生成（例如 `&($child->_super)`、`&($child->_super._super)`）。
    - 不再使用 `(<Parent*>)$child` 裸 cast 表达 GDCC 子类->父类转换。
  - 对非上行/不可证明路径 fail-fast，抛出 `InvalidInsnException`。
  - 对继承链异常（循环、缺失定义、路径断裂）增加防御性 fail-fast。

- 已完成：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`
  - `renderReceiverValue(...)` 继续保留 `checkAssignable` 预校验。
  - 当静态分派 owner 为 GDCC 父类、receiver 为 GDCC 子类时，已通过 `valueOfCastedVar(...)` 生成 `_super` 链上行表达式参与调用。

- 已完成：测试同步
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CBodyBuilderPhaseCTest.java`
    - 新增 GDCC 多级上行转换 `_super` 链生成断言。
    - 新增 GDCC 非上行转换 fail-fast 断言。
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CallMethodInsnGenTest.java`
    - 新增 `CALL_METHOD` 子类 receiver 调父类 owner 静态分派时的 `_super` 链上行断言。
    - 断言不存在裸 cast 回退路径。

### 阶段 4：文档复核与收敛

1. 文件：`doc/gdcc_c_backend.md`
- 对照实现复核阶段 0 规则无偏移、无回退。
- 更新“显式 `_super` 布局 + 专用 helper 转换”的最终描述。

1. 文件：`doc/module_impl/call_method_implementation.md`
- 复核 `CALL_METHOD` receiver 上行转换文档与实现保持一致，无宏回退路径。

#### 阶段 4 实施同步（2026-02-27）

- 已完成：`doc/gdcc_c_backend.md`
  - 复核并固化 “GDCC -> Godot 指针转换必须使用按类 helper（`gdcc_object_to_godot_object_ptr(value, Type_object_ptr)`）” 基线。
  - 补充实现对齐说明，明确 `CBodyBuilder`/`CALL_METHOD` 相关路径已切换到专用 helper 入口。
- 已完成：`doc/module_impl/call_method_implementation.md`
  - `CALL_METHOD` receiver 的对象指针转换与上行转换描述已对齐实现：
    - GDCC receiver -> ENGINE owner 先做 helper 转换再 cast。
    - GDCC 子类 receiver -> GDCC 父类 owner 通过 `_super` 链安全上行，不允许裸 cast。
  - 文档约束与回归测试断言一致，`CALL_METHOD` 路径无 `godot_object_from_gdcc_object_ptr(...)` 回退。
- 已完成：代码与测试收敛（支撑阶段 4 文档结论）
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
    - GDCC->Godot 转换统一为 `gdcc_object_to_godot_object_ptr(value, <Type>_object_ptr)`。
    - `convertPtrIfNeeded(...)` 增加源类型约束，缺失 GDCC 静态类型时 fail-fast。
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
    - 新增 `renderGdccObjectPtrHelperName(...)`，统一渲染按类 helper 符号名。
  - 回归测试已同步并通过：
    - `CBodyBuilderPhaseCTest`
    - `CallMethodInsnGenTest`
    - `CallMethodInsnGenEngineTest`

## 测试清单（必须完成）

### A. 代码生成单元测试（模板/文本）

1. 文件：`src/test/java/dev/superice/gdcc/backend/c/gen/...`（按现有测试目录归档）
- 断言子类 struct 包含父类前缀字段 `_super`。
- 断言为每个类生成 `<Class>_object_ptr(...)` helper。
- 断言 `create_instance` 构造目标为“最近 native ancestor”，而不是 GDCC 父类名。

1. `CALL_METHOD` 相关回归
- receiver 为子类、owner 为父类时，断言生成代码使用安全上行表达式，不出现裸 cast。
- 非法转换路径保持 fail-fast（`InvalidInsnException`）。

### B. 引擎行为集成测试（运行时）

1. 新增引擎继承行为测试类（建议：`CallMethodInsnGenEngineInheritanceTest`）
- 场景：`B extends A`，在 `A` 中定义访问 A 字段的方法，`B.new().a_method()` 返回值正确。
- 验证 GDScript 侧继承关系可见：`B is A`、ClassDB parent 查询为 true。

1. 环境策略
- 依赖 Zig/引擎运行环境的测试需保留“不可用时跳过”策略，不得误报失败。

## 验收标准（DoD）

- 所有模板生成代码在多级继承下可编译通过。
- `CALL_METHOD` 子类调用父类方法链路稳定，无裸 cast 造成的布局 UB 风险。
- 目标测试集通过：
  - `CallMethodInsnGenTest`
  - `CallMethodInsnGenEngineTest`
  - 新增继承行为测试类
  - 必要的 `CBodyBuilderPhaseCTest` 子集
- 文档同步完成，`doc/gdcc_c_backend.md` 与 `module_impl` 中无冲突性描述。

## 风险与回滚策略

- 风险 1：模板改动影响面大，可能破坏现有对象转换宏。
  - 缓解：先引入并切换到 helper，再移除旧宏语义。
- 风险 2：`create_instance` 祖先选择错误导致实例化失败。
  - 缓解：新增“祖先选择”单测 + 引擎集成用例双重校验。
- 风险 3：`valueOfCastedVar` 收紧后触发历史路径失败。
  - 缓解：明确 fail-fast 文案并补齐测试，逐步修复调用点。

## 执行顺序建议

1. 完成阶段 0（文档基线）并在评审中冻结为强制标准。
2. 完成阶段 1（布局 + helper）并补 A 类测试。
3. 完成阶段 2（create_instance 修正）并补 create_instance 断言。
4. 完成阶段 3（CBodyBuilder / CallMethod 对齐）并补 `CALL_METHOD` 回归。
5. 完成阶段 4（文档复核）与引擎继承行为测试收尾。
