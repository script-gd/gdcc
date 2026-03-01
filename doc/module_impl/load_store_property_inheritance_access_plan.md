# LOAD_PROPERTY / STORE_PROPERTY 继承属性访问支持方案（GDCC C Backend）

## 文档状态

- 状态：In Progress（Phase 0 Completed）
- 更新时间：2026-03-01
- 阶段进度：Phase 0 已完成，Phase 1-4 待实施
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

## 2. 当前实现问题分析

### 2.1 属性解析仅查当前类，未查继承链

`PropertyAccessResolver.findPropertyDef(...)` 只遍历 `classDef.getProperties()`，不沿 `getSuperName()` 继续查找。

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
record ObjectPropertyLookup(@NotNull ClassDef ownerClass,
                            @NotNull PropertyDef property,
                            int ownerDistance) {}
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
2. 若存在 classDef：沿 `super` 链向上查找首个同名 property。
3. 查找到：返回 owner class + property def + distance。
4. 查找不到：`invalidInsn("Property 'x' not found in class hierarchy of 'Receiver'")`。
5. 增加防御性检查：
   - 继承环检测（visited set）
   - 中间父类 metadata 缺失时 fail-fast（仅在 receiver 已知 classDef 的前提下）

保留 `resolveBuiltinProperty(...)` 不变。

### 4.2 LoadPropertyInsnGen：改为基于 owner 解析生成

#### 4.2.1 校验与类型解析

- 将当前 `resolvePropertyType(...)` 改造为“返回对象属性 lookup + 类型校验”的流程。
- 对对象类型：使用 `resolveObjectProperty(...)`。
- 对 builtin 类型：沿用 `resolveBuiltinProperty(...)`。

#### 4.2.2 GDCC 分支

- 使用 `lookup.ownerClass().getName()` 构造 getter 函数名。
- receiver 参数通过统一 helper（建议新增私有方法 `renderPropertyReceiverValue(...)`）生成：
  - 若 receiver type 与 owner type 不同：`valueOfCastedVar(receiver, ownerType)`
  - 否则 `valueOfVar(receiver)`
- getter-self 直读 `self->field` 仅在以下条件全部满足时启用：
  - 当前函数确认为该属性 getter
  - `bodyBuilder.clazz().getName().equals(ownerClassName)`
  - receiver 变量静态类型与 owner 一致

#### 4.2.3 ENGINE 分支

- getter 符号名改为 `godot_<OwnerClass>_get_<property>`。
- receiver 参数同样走 `renderPropertyReceiverValue(...)`，确保 `(godot_Parent*)$child` 或等效安全表达式。

#### 4.2.4 GENERAL / BUILTIN 分支

- GENERAL：保持 `godot_Object_get` + unpack 逻辑不变。
- BUILTIN：保持现有逻辑。

### 4.3 StorePropertyInsnGen：与 load 对称改造

#### 4.3.1 写入校验

- `validatePropertyWrite(...)` 对对象类型改为基于 `resolveObjectProperty(...)`。
- valueType 与 `lookup.property().getType()` 使用 `checkAssignable`。
- ENGINE 属性 writability 校验仍保留。

#### 4.3.2 GDCC 分支

- setter 调用名使用 owner class。
- receiver 参数使用 `renderPropertyReceiverValue(...)` 上行到 owner。
- setter-self 直写路径增加 owner 约束（同 load）。

#### 4.3.3 ENGINE 分支

- setter 调用改为 `godot_<OwnerClass>_set_<property>`。
- receiver 参数上行到 owner。

#### 4.3.4 GENERAL / BUILTIN 分支

- GENERAL：保持 pack Variant + `godot_Object_set`。
- BUILTIN：保持现有路径。

### 4.4 复用与抽取建议

为避免 `LoadPropertyInsnGen` / `StorePropertyInsnGen` 再次分叉，建议在 `PropertyAccessResolver` 增加以下复用点：

- `resolveObjectProperty(...)`
- `toOwnerObjectType(ObjectPropertyLookup)`（可选）

并在两个生成器中统一 receiver 渲染逻辑（可复制 `CallMethodInsnGen.renderReceiverValue(...)` 并局部适配属性上下文错误文案）。

## 5. 测试计划

### 5.1 新增/扩展 `CLoadPropertyInsnGenTest`

1. `GDCC` 子类 receiver 读取父类属性：
   - 断言调用父类 getter
   - 断言使用 `_super` 链上行（如 `&($child->_super)`）
   - 断言不存在裸 cast 路径
2. `ENGINE` 子类 receiver 读取父类属性：
   - 断言调用父类 `godot_<Owner>_get_<property>`
   - 断言 receiver 被 cast 到 owner
3. 现有场景回归：
   - getter-self 直读仍成立
   - unknown object fallback 不变

### 5.2 新增/扩展 `CStorePropertyInsnGenTest`

1. `GDCC` 子类 receiver 写父类属性：
   - 断言调用父类 setter
   - 断言 `_super` 链上行
2. `ENGINE` 子类 receiver 写父类属性：
   - 断言 `godot_<Owner>_set_<property>`
   - 断言 receiver owner-cast
3. setter-self 直写保护：
   - 验证引入 owner 约束后不会误判

### 5.3 命令建议

```bash
./gradlew test --tests CLoadPropertyInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CStorePropertyInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

## 6. 风险与缓解

1. 风险：继承链 metadata 缺失导致行为变化。
- 缓解：在“receiver 已知类”场景明确 fail-fast，并在错误文案中包含链路信息。

1. 风险：owner 变化后触发现有测试文本断言不匹配。
- 缓解：先补新增继承用例，再更新旧断言最小化改动。

1. 风险：setter/getter-self 误判导致错误 field 访问表达式。
- 缓解：引入 owner class 一致性条件，并覆盖专项回归用例。

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
状态：`Pending`
3. `Phase 2`：LOAD_PROPERTY 接入  
主要产出：load 路径 owner 解析与 receiver 上行  
Gate：`G2`  
状态：`Pending`
4. `Phase 3`：STORE_PROPERTY 接入  
主要产出：store 路径 owner 解析与 receiver 上行  
Gate：`G3`  
状态：`Pending`
5. `Phase 4`：回归与收敛  
主要产出：全链路回归、文档状态更新、风险收口  
Gate：`G4`  
状态：`Pending`

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

### 8.3 Phase 1：Resolver 能力落地

实施目标：

- 提供统一的继承链属性解析能力，返回 owner + property 元信息。

实施项：

1. 在 `PropertyAccessResolver` 新增：
   - `ObjectPropertyLookup` record。
   - `resolveObjectProperty(...)`。
2. 解析策略：
   - `getClassDef(receiverType) == null` 时返回 `null`（交给调用方走 GENERAL）。
   - 有 classDef 时沿 `super` 链查找首个 property。
   - 继承环与中途 metadata 缺失 fail-fast。
3. 保持 builtin 解析接口不变，避免非对象路径受影响。

建议验证命令：

```bash
./gradlew classes --no-daemon --info --console=plain
```

Gate G1（准出条件）：

- 编译通过。
- `resolveObjectProperty(...)` 对三类场景行为明确：
  - 找到 owner（返回 lookup）
  - unknown object（返回 null）
  - known class 但未找到 property（抛 InvalidInsnException）

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
- 既有用例通过：
  - getter-self 直读场景。
  - unknown object fallback 场景。

### 8.5 Phase 3：STORE_PROPERTY 接入

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
- 既有用例通过：
  - setter-self 直写生命周期顺序断言。
  - subtype assignable 场景断言。

### 8.6 Phase 4：回归与收敛

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
