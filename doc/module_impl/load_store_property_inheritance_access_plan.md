# LOAD_PROPERTY / STORE_PROPERTY 继承属性访问支持方案（GDCC C Backend）

## 文档状态

- 状态：Implemented（Phase 0-4 Completed）
- 更新时间：2026-03-01
- 阶段进度：Phase 0-4 已完成
- 适用范围：`LoadPropertyInsnGen`、`StorePropertyInsnGen`、`PropertyAccessResolver`
- 关联文档：
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/call_method_implementation.md`
  - `doc/module_impl/explicit_c_inheritance_layout.md`

## 1. 背景与目标

### 1.1 背景

`load_property` / `store_property` 的 Low IR 语义是“按属性名访问对象属性”，语义上应与 GDScript 一致，包含继承链可见性。

当前 C backend 已经在 `CALL_METHOD` 路径实现了“子类 GDCC 对象到父类 owner 的安全上行”（`_super` 链），但属性访问路径仍按“接收者静态类型即 owner”处理，导致以下能力缺失：

- 使用子类对象读取父类属性（GDCC/ENGINE）
- 使用子类对象写入父类属性（GDCC/ENGINE）

### 1.2 目标

- 支持 `LoadPropertyInsnGen` / `StorePropertyInsnGen` 在对象继承链上解析属性 owner。
- 当 owner 与接收者静态类型不同，生成安全上行后的 receiver 参数。
- 保持 fail-fast 风格、保持现有 unknown object fallback 路径、保持生命周期语义不变。

### 1.3 非目标

- 不改 Low IR 指令定义。
- 不改模板层 struct 布局（`_super` 布局已在既有方案中完成）。
- 不扩展 `store_static` / `load_static` 行为。

### 1.4 与 Low IR 语义一致性

- `gdcc_low_ir.md` 中 `load_property` / `store_property` 的语义是“按属性名访问对象属性”。
- 本方案属于 backend 对既有 Low IR 语义的实现补全（补齐继承链可见性与 owner 派发），不是语义扩展。

## 2. 当前实现问题分析

### 2.1 属性解析仅查当前类，未查继承链

历史实现中的 `PropertyAccessResolver.findPropertyDef(...)` 只遍历 `classDef.getProperties()`，不沿 `getSuperName()` 继续查找。

直接影响：

- `LoadPropertyInsnGen.resolvePropertyType(...)` 在子类接收者访问父类属性时会报 `Property 'x' not found in class Child`。
- `StorePropertyInsnGen.validatePropertyWrite(...)` 同样在相同场景 fail-fast。

### 2.2 getter/setter 函数名按接收者类型拼接，owner 可能错误

当前 `LoadPropertyInsnGen` / `StorePropertyInsnGen` 在静态路径中直接使用接收者类型生成调用名：

- GDCC: `<ReceiverClass>_<getter/setter>`
- ENGINE: `godot_<ReceiverClass>_get/set_<property>`

若属性定义在父类，应使用父类 owner 生成符号名。

### 2.3 owner 参数未做上行转换

当前属性访问静态调用参数多为 `bodyBuilder.valueOfVar(objectVar)`。但当调用的是父类 owner getter/setter，receiver 是子类对象时，需要先转换到 owner 类型：

- GDCC -> GDCC：必须走 `_super` 链安全上行（由 `valueOfCastedVar` 提供）
- ENGINE -> ENGINE：需要显式 cast 到 owner 指针类型

`CALL_METHOD` 已实现该模式：

- `CallMethodInsnGen.renderReceiverValue(...)`
- `CBodyBuilder.valueOfCastedVar(...)`

属性路径可直接复用该成熟模式。

### 2.4 setter/getter self 直写路径在引入继承后需要 owner 约束

当前 `isLoadingInsideGetterSelf` / `isStoringInsideSetterSelf` 的判断未显式绑定“property owner class”。

在支持继承后，需要避免“同名函数误判为当前属性 getter/setter”导致错误地发射 `self->field`。

## 3. 设计原则

1. 复用既有能力，不重复实现上行转换逻辑。
2. 解析与生成解耦：先解析“owner + property”，再做代码生成。
3. Unknown object 类型保持现有 GENERAL fallback（`godot_Object_get/set`）。
4. 对可静态判定错误继续 fail-fast，不做静默降级。
5. 仅在必要点新增逻辑，不改变已有生命周期写槽顺序。

## 4. 详细改造方案

### 4.1 PropertyAccessResolver：新增“继承链属性 owner 解析”

新增记录类型（建议）：

```java
enum PropertyOwnerDispatchMode { GDCC, ENGINE }

record ObjectPropertyLookup(@NotNull ClassDef ownerClass,
                            @NotNull PropertyDef property,
                            @NotNull PropertyOwnerDispatchMode ownerDispatchMode) {}
```

新增方法（建议）：

```java
static @Nullable ObjectPropertyLookup resolveObjectProperty(
        @NotNull CBodyBuilder bodyBuilder,
        @NotNull GdObjectType receiverType,
        @NotNull String propertyName,
        @NotNull String insnName)
```

解析规则：

1. `registry.getClassDef(receiverType)` 为 `null`：返回 `null`（表示 unknown object，沿用 GENERAL 路径）。
2. 若存在 classDef：从当前类开始，沿 `super` 链逐层向上查找同名 property（GDCC/ENGINE 统一执行该策略）。
3. 在任一层找到属性后立即返回，不再继续向祖先查找（最近 owner 优先，命中即停止）。
4. 字段同名（遮蔽）语义：允许隐式遮蔽，解析固定取最近命中的 owner，不做同名冲突 fail-fast。
5. 返回内容包含 owner class、property def、owner dispatch mode（由 owner class 分类推导，替代 receiver-based 分派）。
6. 当 receiver 为对象类型时，后续代码生成分支基于 `ownerDispatchMode`，不再直接依赖 `resolveGenMode(receiverType)`。
7. 对于 ENGINE 类，即使当前 Godot Extension API 可能仍为扁平化属性列表，也保持向上查找逻辑以兼容未来版本可能移除父类字段记录的变化。
8. 查找不到：`invalidInsn("Property 'x' not found in class hierarchy of 'Receiver'")`。
9. 增加防御性检查：
   - 继承环检测（visited set）
   - 中间父类 metadata 缺失时 fail-fast（仅在 receiver 已知 classDef 的前提下）

保留 `resolveBuiltinProperty(...)` 不变。
- `findPropertyDef(...)` 在 Phase 1 内收敛为 `private` 或直接删除（优先删除），避免后续绕过继承链解析路径。

### 4.2 LoadPropertyInsnGen：改为基于 owner 解析生成

#### 4.2.1 校验与类型解析

- 将当前 `resolvePropertyType(...)` 改造为“返回对象属性 lookup + 类型校验”的流程。
- 对对象类型：使用 `resolveObjectProperty(...)`。
- 对 builtin 类型：沿用 `resolveBuiltinProperty(...)`。
- 对对象类型分支：使用 `lookup.ownerDispatchMode()` 决定代码生成路径（owner-based dispatch）。

#### 4.2.2 对象路径分派与 receiver 转换（owner-based）

- 当 `ownerDispatchMode == GDCC`：
  - getter 符号名使用 `lookup.ownerClass().getName()`（`<OwnerClass>_<getter>`）。
  - receiver 参数通过统一 helper 渲染，优先复用 `valueOfCastedVar(receiver, ownerType)`。
  - GDCC receiver -> GDCC owner 使用 `_super` 链安全上行（例如 `&($child->_super)`）。
- 当 `ownerDispatchMode == ENGINE`：
  - getter 符号名使用 `godot_<OwnerClass>_get_<property>`。
  - receiver 参数同样通过统一 helper 渲染，必须支持跨类别路径：
    - GDCC receiver -> ENGINE owner：先 `gdcc_object_to_godot_object_ptr(...)`，再 cast 到 `godot_<Owner>*`。
    - ENGINE receiver -> ENGINE owner：按 owner 类型做显式 owner-cast。
- getter-self 直读 `self->field` 仅在以下条件全部满足时启用：
  - 当前函数确认为该属性 getter
  - `bodyBuilder.clazz().getName().equals(ownerClassName)`
  - receiver 变量静态类型与 owner 一致
  - 上述 owner 约束成立时，`self` 与 owner 同类，字段地址不需要 `_super` 中转，生命周期语义与现有实现一致

#### 4.2.3 GENERAL / BUILTIN 分支

- GENERAL：保持 `godot_Object_get` + unpack 逻辑不变。
- BUILTIN：保持现有逻辑。

### 4.3 StorePropertyInsnGen：与 load 对称改造

#### 4.3.1 写入校验

- `validatePropertyWrite(...)` 对对象类型改为基于 `resolveObjectProperty(...)`。
- valueType 与 `lookup.property().getType()` 使用 `checkAssignable`。
- ENGINE 属性 writability 校验仍保留。
- 对对象类型分支：使用 `lookup.ownerDispatchMode()` 决定代码生成路径（owner-based dispatch）。

#### 4.3.2 对象路径分派与 receiver 转换（owner-based）

- 当 `ownerDispatchMode == GDCC`：
  - setter 调用名使用 `<OwnerClass>_<setter>`。
  - receiver 参数使用 `renderPropertyReceiverValue(...)` 上行到 owner。
  - GDCC receiver -> GDCC owner 使用 `_super` 链安全上行。
- 当 `ownerDispatchMode == ENGINE`：
  - setter 调用名使用 `godot_<OwnerClass>_set_<property>`。
  - receiver 参数使用 `renderPropertyReceiverValue(...)` 上行到 owner，并支持：
    - GDCC receiver -> ENGINE owner：先 `gdcc_object_to_godot_object_ptr(...)` 再 owner-cast。
    - ENGINE receiver -> ENGINE owner：按 owner 类型 cast。
- setter-self 直写路径增加 owner 约束（同 load）。
  - 上述 owner 约束成立时，`self->field` 写入目标即 owner 字段本体，不引入额外上行步骤，生命周期写槽顺序保持不变

#### 4.3.3 GENERAL / BUILTIN 分支

- GENERAL：保持 pack Variant + `godot_Object_set`。
- BUILTIN：保持现有路径。
- 实施时需显式拆分 `StorePropertyInsnGen` 当前的 `case ENGINE, BUILTIN ->` 合并分支，避免 ENGINE owner 解析逻辑误入 BUILTIN 路径。

### 4.4 复用与抽取建议

为避免 `LoadPropertyInsnGen` / `StorePropertyInsnGen` 再次分叉，建议在 `PropertyAccessResolver`（或同包共用 helper）增加以下复用点：

- `resolveObjectProperty(...)`
- `renderOwnerReceiverValue(...)`（统一 receiver 上行与跨类别转换）
- `toOwnerObjectType(ObjectPropertyLookup)`（可选）

并在 `CallMethodInsnGen`、`LoadPropertyInsnGen`、`StorePropertyInsnGen` 中复用同一套 receiver 渲染逻辑，不复制 `CallMethodInsnGen.renderReceiverValue(...)` 私有实现，避免指针转换语义分叉。

## 5. 测试计划

### 5.1 新增/扩展 `CLoadPropertyInsnGenTest`

1. `GDCC` 子类 receiver 读取父类属性：
   - 断言调用父类 getter
   - 断言使用 `_super` 链上行（如 `&($child->_super)`）
   - 断言不存在裸 cast 路径
2. `GDCC` 子类 receiver 读取 `ENGINE` 父类属性（例如 `MyClass extends Node` 读取 `Node.name`）：
   - 断言走 `ownerDispatchMode == ENGINE`
   - 断言调用 `godot_<Owner>_get_<property>`
   - 断言先 `gdcc_object_to_godot_object_ptr(...)` 再 owner-cast
3. `ENGINE` 子类 receiver 读取父类属性：
   - 断言调用父类 `godot_<Owner>_get_<property>`
   - 断言 receiver 被 cast 到 owner
4. 多级继承链（含 ENGINE 层级）读取属性：
   - 断言在首个命中层返回（命中即停止），不继续向上查找
5. 三级跨类别继承链读取属性（`GDCC GrandChild -> GDCC Child -> ENGINE Parent`）：
   - 断言 GDCC 链上行与 ENGINE 转换拼接顺序正确
6. 属性在整条链上不存在：
   - 断言 fail-fast
   - 断言错误文案包含 receiver 与 class hierarchy 信息
7. 继承环检测：
   - 断言 fail-fast
   - 断言错误文案可定位环路
8. 现有场景回归：
   - getter-self 直读仍成立
   - unknown object fallback 不变

### 5.2 新增/扩展 `CStorePropertyInsnGenTest`

1. `GDCC` 子类 receiver 写父类属性：
   - 断言调用父类 setter
   - 断言 `_super` 链上行
2. `GDCC` 子类 receiver 写 `ENGINE` 父类属性：
   - 断言走 `ownerDispatchMode == ENGINE`
   - 断言调用 `godot_<Owner>_set_<property>`
   - 断言先 `gdcc_object_to_godot_object_ptr(...)` 再 owner-cast
3. `ENGINE` 子类 receiver 写父类属性：
   - 断言 `godot_<Owner>_set_<property>`
   - 断言 receiver owner-cast
4. 多级继承链（含 ENGINE 层级）写属性：
   - 断言在首个命中层返回（命中即停止），不继续向上查找
5. 三级跨类别继承链写属性（`GDCC GrandChild -> GDCC Child -> ENGINE Parent`）：
   - 断言 GDCC 链上行与 ENGINE 转换拼接顺序正确
6. 属性在整条链上不存在：
   - 断言 fail-fast
   - 断言错误文案包含 receiver 与 class hierarchy 信息
7. setter-self 直写保护：
   - 验证引入 owner 约束后不会误判

### 5.3 命令建议

```bash
./gradlew test --tests PropertyAccessResolverTest --no-daemon --info --console=plain
./gradlew test --tests CLoadPropertyInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CStorePropertyInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

### 5.4 阶段内回归要求

1. Phase 2 每个子步骤后至少执行：
   - `CLoadPropertyInsnGenTest`
   - `CallMethodInsnGenTest`（冒烟）
2. Phase 3 每个子步骤后至少执行：
   - `CStorePropertyInsnGenTest`
   - `CLoadPropertyInsnGenTest`（防止 load/store 共享逻辑回归）

## 6. 风险与缓解

1. 风险：继承链 metadata 缺失导致行为变化。
- 缓解：在“receiver 已知类”场景明确 fail-fast，并在错误文案中包含链路信息。

1. 风险：owner 变化后触发现有测试文本断言不匹配。
- 缓解：先补新增继承用例，再更新旧断言最小化改动。

1. 风险：setter/getter-self 误判导致错误 field 访问表达式。
- 缓解：引入 owner class 一致性条件，并覆盖专项回归用例。

1. 风险：旧 `findPropertyDef(...)` 被误用，绕过继承链 owner 解析。
- 缓解：Phase 1 内将该方法收敛为 `private` 或直接删除，并在代码审查中禁止新增调用点。

1. 风险：`checkAssignable` 方向被误用，导致 load/store 类型校验逻辑反转。
- 缓解：在 Phase 2/3 Gate 中显式验收校验方向，并用负向测试覆盖不兼容赋值。

1. 风险：receiver 上行后对 setter-self 生命周期语义理解不一致。
- 缓解：文档明确 owner 约束推理链，并在 setter-self 回归用例中锁定生命周期写槽顺序。

## 7. 验收标准（DoD）

- 子类 object 值可读写父类属性（GDCC/ENGINE）且生成代码可编译。
- 属性 owner 解析在继承链上稳定且可诊断（错误信息可定位）。
- 现有 unknown object fallback、builtin 路径、生命周期顺序无回归。
- `CLoadPropertyInsnGenTest` 与 `CStorePropertyInsnGenTest` targeted 用例通过。

## 8. 分阶段实施与阶段验收方案

### 8.1 阶段划分总览

1. `Phase 0`：基线冻结  
主要产出：实施边界、复用边界、回归基线冻结  
Gate：`G0`  
状态：`Completed`
2. `Phase 1`：Resolver 能力落地  
主要产出：继承链属性 owner 解析能力  
Gate：`G1`  
状态：`Completed`
3. `Phase 2`：LOAD_PROPERTY 接入  
主要产出：load 路径 owner 解析与 receiver 上行  
Gate：`G2`  
状态：`Completed`
4. `Phase 3`：STORE_PROPERTY 接入  
主要产出：store 路径 owner 解析与 receiver 上行  
Gate：`G3`  
状态：`Completed`
5. `Phase 4`：回归与收敛  
主要产出：全链路回归、文档状态更新、风险收口  
Gate：`G4`  
状态：`Completed`

### 8.2 Phase 0：基线冻结（Completed）

实施目标：

- 明确“仅支持对象属性继承可见性补齐”，不扩展 Low IR 指令语义。
- 明确复用边界：复用 `CallMethodInsnGen` 的 receiver 渲染策略，不引入新的指针转换策略。
- 冻结回归基线测试清单，避免中途范围漂移。

实施项：

1. 文档内冻结以下技术约束：
   - owner 解析失败继续 fail-fast（已知类型场景）。
   - unknown object 继续 GENERAL fallback。
   - lifecycle 写槽顺序不改。
2. 确认实现文件范围：
   - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
   - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/LoadPropertyInsnGen.java`
   - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/StorePropertyInsnGen.java`
   - `src/test/java/dev/superice/gdcc/backend/c/gen/CLoadPropertyInsnGenTest.java`
   - `src/test/java/dev/superice/gdcc/backend/c/gen/CStorePropertyInsnGenTest.java`

Gate G0（准出条件）：

- 本文档中“目标/非目标/风险/DoD”不再变化。
- 测试基线与实现文件列表冻结。

#### Phase 0 完成同步（2026-03-01）

完成项：

1. 已冻结实施边界：
   - 仅补齐 `load_property` / `store_property` 的继承属性可见性。
   - 不改 Low IR 指令定义，不改模板布局，不扩展 `load_static` / `store_static`。
2. 已冻结复用边界：
   - receiver 上行转换复用 `CallMethodInsnGen` + `CBodyBuilder.valueOfCastedVar(...)` 语义。
   - 不新增独立的对象指针转换策略。
3. 已冻结回归基线：
   - 主回归用例锁定为 `CLoadPropertyInsnGenTest`、`CStorePropertyInsnGenTest`。
   - 冒烟回归锁定为 `CallMethodInsnGenTest`。
4. 已冻结实现文件范围：
   - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
   - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/LoadPropertyInsnGen.java`
   - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/StorePropertyInsnGen.java`
   - `src/test/java/dev/superice/gdcc/backend/c/gen/CLoadPropertyInsnGenTest.java`
   - `src/test/java/dev/superice/gdcc/backend/c/gen/CStorePropertyInsnGenTest.java`

G0 验收结果：

- [x] “目标/非目标/风险/DoD”已锁定为实施基线。
- [x] 测试基线已锁定。
- [x] 实现文件范围已锁定。
- [x] 当前工作区仅包含本方案文档变更，未出现范围外代码改动。

备注：

- Phase 0 为文档冻结阶段，无代码实现变更，不执行 Gradle 测试。

### 8.3 Phase 1：Resolver 能力落地（Completed）

实施目标：

- 提供统一的继承链属性解析能力，返回 owner + property + owner dispatch mode 元信息。

实施项：

1. 在 `PropertyAccessResolver` 新增：
   - `ObjectPropertyLookup` record。
   - `PropertyOwnerDispatchMode`（或等效 helper）。
   - `resolveObjectProperty(...)`。
   - `PropertyAccessResolverTest`（独立单元测试）。
2. 解析策略：
   - `getClassDef(receiverType) == null` 时返回 `null`（交给调用方走 GENERAL）。
   - 有 classDef 时沿 `super` 链查找首个 property（GDCC/ENGINE 均执行）。
   - 任一层命中后立即返回（命中即停止，不继续祖先遍历）。
   - 允许隐式字段遮蔽（同名时最近命中优先）。
   - 继承环与中途 metadata 缺失 fail-fast。
3. Godot Extension API 属性模型验证：
   - Phase 1 实施前验证当前 `extension_api.json` 的 `classes[].properties` 是扁平化还是仅本类。
   - 无论验证结果如何，最终实现保持向上查找策略，确保对未来 Godot 版本（可能不再记录父类字段）向后兼容。
4. 旧接口收敛：
  - `findPropertyDef(...)` 收敛为 `private` 或直接删除。
5. 保持 builtin 解析接口不变，避免非对象路径受影响。

建议验证命令：

```bash
./gradlew test --tests PropertyAccessResolverTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

Gate G1（准出条件）：

- 编译通过。
- `resolveObjectProperty(...)` 对三类场景行为明确：
  - 找到 owner（返回 lookup）
  - unknown object（返回 null）
  - known class 但未找到 property（抛 InvalidInsnException）
- `PropertyAccessResolverTest` 通过以下场景：
  - 命中即停止
  - 允许隐式字段遮蔽（最近命中优先）
  - 属性不存在 fail-fast
  - 继承环 fail-fast
- 已完成 Godot Extension API 属性模型验证记录，并确认实现保持“向上查找 + 命中即停止”的兼容策略。
- `findPropertyDef(...)` 已完成收敛（`private` 或删除）。

#### Phase 1 完成同步（2026-03-01）

完成项：

1. 已在 `PropertyAccessResolver` 落地对象属性继承链解析能力：
   - 新增 `PropertyOwnerDispatchMode` 与 `ObjectPropertyLookup`。
   - 新增 `resolveObjectProperty(...)`，支持：
     - unknown object 返回 `null`
     - 继承链逐层查找
     - 命中即停止（最近 owner 优先）
     - 隐式字段遮蔽（同名取最近命中）
     - 继承环检测 fail-fast
     - 中间父类 metadata 缺失 fail-fast
2. 已完成旧接口收敛：
   - `findPropertyDef(...)` 已删除，避免后续绕过继承链解析入口。
3. 已新增并通过独立单元测试 `PropertyAccessResolverTest`，覆盖：
   - 命中即停止
   - 隐式字段遮蔽
   - unknown object 返回 `null`
   - 属性不存在 fail-fast
   - 继承环 fail-fast
   - 父类 metadata 缺失 fail-fast
4. 已完成 Godot Extension API 属性模型验证记录：
   - 当前仓库未内置固定 `extension_api.json` 样本，无法绑定单一“扁平化/仅本类”假设。
   - 已按兼容策略实现：ENGINE 路径始终支持向上查找，并在首命中后停止，兼容未来版本字段记录变化。

G1 验收结果：

- [x] `resolveObjectProperty(...)` 行为满足 Gate 要求。
- [x] `PropertyAccessResolverTest` targeted 用例通过。
- [x] `findPropertyDef(...)` 已完成最终收敛（已删除）。
- [x] `./gradlew classes --no-daemon --info --console=plain` 通过。

### 8.4 Phase 2：LOAD_PROPERTY 接入

实施目标：

- `LoadPropertyInsnGen` 在对象场景使用 owner 解析结果生成调用，支持子类读取父类属性。

实施项：

1. 解析改造：
   - 用 `resolveObjectProperty(...)` 替换原先局部 `findPropertyDef(...)` 直查。
   - 对 result type 做 `checkAssignable(propertyType, resultType)` 校验。
2. GDCC 分支改造：
   - getter 符号名从 `<ReceiverClass>_...` 改为 `<OwnerClass>_...`。
   - receiver 参数改为 owner 对齐值（必要时 `valueOfCastedVar`）。
3. ENGINE 分支改造：
   - getter 符号名改为 `godot_<OwnerClass>_get_<property>`。
   - receiver 参数改为 owner 对齐值。
4. getter-self 直读路径加 owner 约束：
   - 当前类、当前函数、receiver 静态类型都必须与 owner 一致。

建议验证命令：

```bash
./gradlew test --tests CLoadPropertyInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

Gate G2（准出条件）：

- 新增用例通过：
  - GDCC 子类读父类属性（含 `_super` 上行断言）。
  - ENGINE 子类读父类属性（含 owner getter 符号断言）。
  - 三级跨类别继承链读取。
  - 属性不存在 fail-fast。
- 既有用例通过：
  - getter-self 直读场景。
  - unknown object fallback 场景。
- `checkAssignable(propertyType, resultType)` 方向性验证通过（含至少 1 个负向断言）。

#### Phase 2 完成同步（2026-03-01）

完成项：

1. 已完成 `LoadPropertyInsnGen` owner-based 接入：
   - 对对象路径改为基于 `resolveObjectProperty(...)` 解析 owner。
   - 代码生成分派改为使用 `lookup.ownerDispatchMode()`，不再按 receiver 静态类型直拼 getter。
2. 已完成 receiver 上行渲染复用：
   - 在 `PropertyAccessResolver` 新增 `renderOwnerReceiverValue(...)` 与 `toOwnerObjectType(...)`。
   - `LOAD_PROPERTY` 统一复用 `valueOfCastedVar(...)` 路径，覆盖：
     - GDCC -> GDCC `_super` 链上行
     - GDCC -> ENGINE `gdcc_object_to_godot_object_ptr(...)` + owner-cast
     - ENGINE -> ENGINE owner-cast
3. 已完成 getter-self 直读 owner 约束：
   - 仅当当前类、receiver 静态类型、owner class 三者一致且函数名匹配 getter 时才走 `self->field`。
4. 已补齐并通过 `CLoadPropertyInsnGenTest` 的 Phase 2 关键场景：
   - GDCC 子类读 GDCC 父类属性（`_super` 上行）
   - GDCC 子类读 ENGINE 父类属性（helper 转换 + owner-cast）
   - ENGINE 子类读 ENGINE 父类属性（owner-cast）
   - 三级跨类别继承链读取
   - 属性不存在 fail-fast（class hierarchy 文案）
   - `checkAssignable(propertyType, resultType)` 负向断言
5. 已完成回归与编译验证：
   - `./gradlew test --tests CLoadPropertyInsnGenTest --no-daemon --info --console=plain`
   - `./gradlew test --tests CallMethodInsnGenTest --no-daemon --info --console=plain`
   - `./gradlew classes --no-daemon --info --console=plain`

G2 验收结果：

- [x] 新增场景与既有场景均通过。
- [x] owner getter 符号与 receiver 上行转换符合预期。
- [x] `checkAssignable(propertyType, resultType)` 方向性负向断言已覆盖并通过。

### 8.5 Phase 3：STORE_PROPERTY 接入（Completed）

实施目标：

- `StorePropertyInsnGen` 在对象场景使用 owner 解析结果生成调用，支持子类写父类属性。

实施项：

1. 校验改造：
   - `validatePropertyWrite(...)` 使用 `resolveObjectProperty(...)`。
   - 保持 engine property writable 校验。
2. GDCC 分支改造：
   - setter 符号名改为 `<OwnerClass>_<setter>`。
   - receiver 参数改为 owner 对齐值。
   - setter-self 直写路径加 owner 约束。
3. ENGINE 分支改造：
   - setter 符号名改为 `godot_<OwnerClass>_set_<property>`。
   - receiver 参数改为 owner 对齐值。
4. GENERAL / BUILTIN 路径保持不变，避免副作用扩散。

建议验证命令：

```bash
./gradlew test --tests CStorePropertyInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

Gate G3（准出条件）：

- 新增用例通过：
  - GDCC 子类写父类属性（含 `_super` 上行断言）。
  - ENGINE 子类写父类属性（含 owner setter 符号断言）。
  - 三级跨类别继承链写入。
  - 属性不存在 fail-fast。
- 既有用例通过：
  - setter-self 直写生命周期顺序断言。
  - subtype assignable 场景断言。
- `checkAssignable(valueType, propertyType)` 方向性验证通过（含至少 1 个负向断言）。

#### Phase 3 完成同步（2026-03-01）

完成项：

1. 已完成 `StorePropertyInsnGen` owner-based 接入：
   - 对对象路径改为基于 `resolveObjectProperty(...)` 解析 owner。
   - 代码生成分派改为使用 `lookup.ownerDispatchMode()`，不再按 receiver 静态类型直拼 setter。
2. 已完成 receiver 上行渲染复用：
   - 复用 `PropertyAccessResolver.renderOwnerReceiverValue(...)`，统一走 `valueOfCastedVar(...)` 路径。
   - 覆盖 GDCC -> GDCC `_super` 链上行、GDCC -> ENGINE helper 转换 + owner-cast、ENGINE -> ENGINE owner-cast。
3. 已完成 setter-self 直写 owner 约束：
   - 仅当当前类、receiver 静态类型、owner class 三者一致且函数名匹配 setter 时才走 `self->field`。
4. 已补齐并通过 `CStorePropertyInsnGenTest` 的 Phase 3 关键场景：
   - GDCC 子类写 GDCC 父类属性（`_super` 上行）
   - GDCC 子类写 ENGINE 父类属性（helper 转换 + owner-cast）
   - ENGINE 子类写 ENGINE 父类属性（owner-cast）
   - 三级跨类别继承链写入
   - 属性不存在 fail-fast（class hierarchy 文案）
   - `checkAssignable(valueType, propertyType)` 负向断言
   - setter-self owner 保护（owner 为父类时不误判直写）
5. 已完成阶段回归与编译验证：
   - `./gradlew test --tests CStorePropertyInsnGenTest --no-daemon --info --console=plain`
   - `./gradlew classes --no-daemon --info --console=plain`

G3 验收结果：

- [x] 新增场景与既有场景均通过。
- [x] owner setter 符号与 receiver 上行转换符合预期。
- [x] `checkAssignable(valueType, propertyType)` 方向性负向断言已覆盖并通过。

### 8.6 Phase 4：回归与收敛（Completed）

实施目标：

- 确保对既有指令行为零回归，并完成文档验收收口。

实施项：

1. 组合回归：
   - `CLoadPropertyInsnGenTest` + `CStorePropertyInsnGenTest` 全量 targeted。
   - `CallMethodInsnGenTest` targeted 冒烟（验证共享上行语义未被破坏）。
2. 文档收口：
   - 本文档状态从 `Planned` 更新为 `Implemented`（仅在代码与测试完成后）。
   - 在“风险与缓解”后追加“实施完成日期与验证命令”。
3. 变更审计：
   - 仅允许修改 Phase 0 冻结文件范围。
   - 不引入 build script/config 变更。

建议验证命令：

```bash
./gradlew test --tests CLoadPropertyInsnGenTest --tests CStorePropertyInsnGenTest --tests CallMethodInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

Gate G4（最终验收）：

- 所有 targeted 测试通过。
- 代码生成输出满足以下验收断言：
  - 子类 receiver 访问父类属性时，调用符号 owner 正确。
  - GDCC 上行不出现裸 cast 回退。
  - GENERAL fallback 与 BUILTIN 路径行为不变。
- 文档状态与实现状态一致。

#### Phase 4 完成同步（2026-03-01）

完成项：

1. 已完成组合回归：
   - `CLoadPropertyInsnGenTest`、`CStorePropertyInsnGenTest`、`CallMethodInsnGenTest` targeted 全部通过。
2. 已完成文档收口：
   - 顶部状态更新为 `Implemented（Phase 0-4 Completed）`。
   - 阶段总览中 Phase 3 / Phase 4 状态更新为 `Completed`。
   - 增补 Phase 3 / Phase 4 完成同步记录与 Gate 验收结果。
3. 已完成变更审计：
   - 变更仅位于 Phase 0 冻结范围内的实现/测试文件与本方案文档。
   - 未引入 build script/config 变更。

G4 验收结果：

- [x] 所有 targeted 测试通过。
- [x] 代码生成关键断言满足预期（owner 符号、GDCC 上行、GENERAL/BUILTIN 稳定）。
- [x] 文档状态与实现状态一致。

## 9. 里程碑验收清单（执行用）

### 9.1 阶段准入检查

- 分支工作区仅包含本需求相关文件。
- 已确认不修改 Gradle 构建配置。
- 已确认 `CallMethodInsnGen` 复用接口不变更语义。

### 9.2 阶段准出检查

- 每阶段结束时记录：
  - 变更文件列表
  - 执行命令
  - 通过结果摘要
  - 未决风险项

### 9.3 最终交付检查

- DoD 全部满足。
- 回归命令与结果可复现。
- PR 描述可直接引用本节作为验收依据。

### 9.4 Phase 0 执行记录（2026-03-01）

变更文件：

- `doc/module_impl/load_store_property_inheritance_access_plan.md`

执行结果摘要：

1. 已将 Phase 0 状态更新为 Completed。
2. 已将阶段总览表加入“状态”列并同步当前状态。
3. 已补充 Phase 0 完成同步清单与 G0 验收结果。
4. 已记录 Phase 0 为文档冻结阶段，后续实现从 Phase 1 开始。

### 9.5 Phase 1 执行记录（2026-03-01）

变更文件：

- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolverTest.java`
- `doc/module_impl/load_store_property_inheritance_access_plan.md`

执行命令：

```bash
./gradlew test --tests PropertyAccessResolverTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

执行结果摘要：

1. `PropertyAccessResolver` Phase 1 目标能力已实现并通过 targeted 单测。
2. `classes` 编译检查通过，G1 达成。
3. Phase 状态已同步更新：Phase 1 Completed，后续实施从 Phase 2 开始。

### 9.6 Phase 3 执行记录（2026-03-01）

变更文件：

- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/StorePropertyInsnGen.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CStorePropertyInsnGenTest.java`
- `doc/module_impl/load_store_property_inheritance_access_plan.md`

执行命令：

```bash
./gradlew test --tests CStorePropertyInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

执行结果摘要：

1. `StorePropertyInsnGen` Phase 3 目标能力已实现并通过 targeted 单测。
2. `classes` 编译检查通过，G3 达成。
3. Phase 状态已同步更新：Phase 3 Completed，后续实施从 Phase 4 开始。

### 9.7 Phase 4 执行记录（2026-03-01）

变更文件：

- `doc/module_impl/load_store_property_inheritance_access_plan.md`

执行命令：

```bash
./gradlew test --tests CLoadPropertyInsnGenTest --tests CStorePropertyInsnGenTest --tests CallMethodInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

执行结果摘要：

1. 组合回归通过，未发现 load/store/call_method 共享路径回归。
2. 文档状态与阶段总览完成收口，G4 达成。

### 9.8 审阅问题 1.2 / 1.3 收敛记录（2026-03-01）

变更文件：

- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/LoadPropertyInsnGen.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/StorePropertyInsnGen.java`
- `doc/module_impl/load_store_property_inheritance_access_plan.md`

执行命令：

```bash
./gradlew test --tests CLoadPropertyInsnGenTest --tests CStorePropertyInsnGenTest --tests CallMethodInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

执行结果摘要：

1. 已删除 `PropertyAccessResolver.GenMode` 与 `resolveGenMode(...)`，对象分派改为由 `resolveObjectProperty(...)` 结果驱动。
2. 已删除 `findPropertyDef(...)`，避免新增调用绕过继承链解析入口。
3. `LoadPropertyInsnGen` / `StorePropertyInsnGen` 已清理 `GenMode` 相关遗留与“known object + lookup null”防御分支，保留对象 owner 路径、unknown object GENERAL fallback、builtin 路径三类行为。
4. targeted 测试与 `classes` 编译检查通过。

### 9.9 审阅问题 1.1 / 1.4 收敛记录（2026-03-01）

变更文件：

- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/LoadPropertyInsnGen.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CLoadPropertyInsnGenTest.java`
- `doc/module_impl/load_store_property_inheritance_access_plan.md`

执行命令：

```bash
./gradlew test --tests CLoadPropertyInsnGenTest --tests CStorePropertyInsnGenTest --tests CallMethodInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

执行结果摘要：

1. 已在 `LoadPropertyInsnGen.resolvePropertyRead(...)` 对对象路径补齐 ENGINE `isReadable()` 防御校验（`ExtensionGdClass.PropertyInfo`）。
2. 已补充 `CLoadPropertyInsnGenTest.unreadableEnginePropertyShouldThrow` 负向用例，覆盖 ENGINE 不可读属性 fail-fast。
3. 已将 `CallMethodInsnGen` 的 receiver 上行渲染切换为复用 `PropertyAccessResolver.renderReceiverValue(...)`，消除与属性路径重复逻辑。
4. targeted 测试与 `classes` 编译检查通过。

### 9.10 审阅第 2 部分补测记录（2026-03-01）
变更文件：
- `src/test/java/dev/superice/gdcc/backend/c/gen/CLoadPropertyInsnGenTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CStorePropertyInsnGenTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/LoadStorePropertyInsnGenEngineInheritanceTest.java`
- `src/main/java/dev/superice/gdcc/gdextension/ExtensionApiLoader.java`

执行命令：
```bash
./gradlew test --tests CLoadPropertyInsnGenTest --tests CStorePropertyInsnGenTest --tests LoadStorePropertyInsnGenEngineInheritanceTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

执行结果摘要：
1. 已补齐 `2.2` 纯 GDCC 三级继承链端到端断言：
   - `CLoadPropertyInsnGenTest.threeLevelGdccChainShouldCallTopParentGetterViaDoubleSuperUpcast`
   - `CStorePropertyInsnGenTest.threeLevelGdccChainShouldCallTopParentSetterViaDoubleSuperUpcast`
2. 已补齐 `2.3` 继承链中间层遮蔽属性端到端断言：
   - `CLoadPropertyInsnGenTest.shadowedPropertyShouldResolveNearestOwnerGetterOnInheritanceChain`
   - `CStorePropertyInsnGenTest.shadowedPropertyShouldResolveNearestOwnerSetterOnInheritanceChain`
3. 已新增 `2.5` 端到端引擎继承集成测试类 `LoadStorePropertyInsnGenEngineInheritanceTest`，并拆分为 4 个测试函数覆盖：
   - ENGINE child -> ENGINE owner 的 LOAD / STORE 两条路径
   - GDCC receiver -> ENGINE owner 的 LOAD / STORE 两条路径
4. 集成测试属性已从 `name` 切换为 `process_mode`，并同步更新 C 生成断言与运行时脚本断言，避免 `StringName` 路径的类型歧义。
5. 为匹配真实 `extension_api_451.json`（类属性缺省 `is_readable`/`is_writable` 字段）更新解析约定：
   - `ExtensionApiLoader` 在字段缺失时按约定默认 `isReadable=true`、`isWritable=true`；
   - 若字段存在，仍以显式 `is_readable`/`is_writable` 为准。
6. 上述 targeted 测试与 `classes` 编译检查均通过。
