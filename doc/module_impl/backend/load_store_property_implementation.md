# LOAD_PROPERTY / STORE_PROPERTY 实现说明

> 本文档作为 `load_property` / `store_property` 在 C Backend 的长期维护说明。  
> 仅保留当前实现事实与后续工程可复用约定；阶段实施过程与执行记录已清理。

## 文档状态

- 状态：Implemented / Maintained
- 更新时间：2026-04-11
- 适用范围：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/LoadPropertyInsnGen.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/StorePropertyInsnGen.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
- 关联文档：
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/module_impl/call_method_implementation.md`
  - `doc/module_impl/cbodybuilder_implementation.md`
  - `doc/module_impl/explicit_c_inheritance_layout.md`

---

## 1. 语义边界（长期有效）

### 1.1 指令语义

- `load_property` / `store_property` 语义是“按属性名访问对象属性”。
- 后端需要完整支持继承链可见性：子类 receiver 访问父类 owner 属性（GDCC/ENGINE）。

### 1.2 非目标

- 不修改 Low IR 指令定义。
- 不修改 `_super` 布局（布局约束由既有实现与文档维护）。
- 不扩展 `load_static` / `store_static` 行为。

---

## 2. 当前最终状态（与代码对齐）

### 2.1 解析与分派

- 对象类型路径通过 `PropertyAccessResolver.resolveObjectProperty(...)` 解析 `owner + property + ownerDispatchMode`。
- 分派以 `ownerDispatchMode` 为准，不再以 receiver 静态类型直接决定 GDCC/ENGINE 分支。
- `GenMode` 旧分派模型已移除；`findPropertyDef(...)` 旧入口已移除。

### 2.2 已支持的继承访问路径

- GDCC 子类 receiver -> GDCC 父类 owner：使用 `_super` 链安全上行。
- ENGINE 子类 receiver -> ENGINE 父类 owner：按 owner 显式 cast。
- GDCC receiver -> ENGINE owner：先 `gdcc_object_to_godot_object_ptr(...)`，再 owner-cast。
- 多级链（包括 `GDCC -> GDCC -> ENGINE`）按“最近命中 owner”解析并生成。

### 2.3 已支持的 fallback 路径

- receiver 为对象类型但 class metadata 未知：走 GENERAL fallback。
  - `LOAD_PROPERTY`：`godot_Object_get` + unpack
  - `STORE_PROPERTY`：pack Variant + `godot_Object_set`
- builtin receiver 保持独立路径，不共享对象 owner 解析。

### 2.4 self 直访优化（已加 owner 约束）

- getter-self 直读仅在以下条件同时满足时启用：
  - 当前函数是属性 getter
  - 当前类名 == 属性 owner 类名
  - receiver 静态类型是 owner 本类 GDCC 类型
- setter-self 直写同理，仅在 owner/函数/receiver 全匹配时启用。
- 以上约束避免继承场景下“同名函数误判”导致错误 `self->field` 路径。
- 对 value-semantic backing field：
  - getter-self 直读必须按 `&self->field` 做 copy-by-address，不得先 shallow-copy 到 temp 再 destroy temp
  - 若当前 target overwrite 在 Builder 的 sealed provenance 模型下属于 `may-alias`，getter-self 允许先用 copy ctor 从 `&self->field` 生成 stable carrier，再 destroy target 并 consume 该 carrier
  - setter-self 直写同理：对 `ref=true` parameter 这类 alias-open source，Builder 会先生成 stable carrier，再 destroy backing field，并把 carrier consume 到 field；但仍不得走“copy temp -> field = temp -> destroy temp”

### 2.5 可读写校验状态

- `LOAD_PROPERTY`：
  - builtin 属性：校验 `isReadable()`
  - engine 属性（对象路径）：校验 `isReadable()`
- `STORE_PROPERTY`：
  - builtin 属性：校验 `isWritable()`
  - engine 属性（对象路径）：校验 `isWritable()`

---

## 3. 架构与职责分离

### 3.1 `PropertyAccessResolver`

核心职责：

- 继承链属性解析（对象路径）
- builtin 属性解析
- owner 对齐 receiver 渲染（复用 `CBodyBuilder.valueOfCastedVar(...)`）

当前关键结构：

- `PropertyOwnerDispatchMode`：`GDCC` / `ENGINE`
- `ObjectPropertyLookup(ownerClass, property, ownerDispatchMode)`
- `resolveObjectProperty(...)`
- `renderOwnerReceiverValue(...)` / `renderReceiverValue(...)`

### 3.2 `LoadPropertyInsnGen`

职责：

- 完成指令级参数合法性检查（变量存在、result 可写、目标类型合法）
- 调用 `resolvePropertyRead(...)` 完成类型与可读性校验
- 根据对象 owner 分派发射 getter 调用或 self 直读
- unknown object 保持 `godot_Object_get` 路径

### 3.3 `StorePropertyInsnGen`

职责：

- 完成指令级参数合法性检查（变量存在、目标类型合法）
- 调用 `validatePropertyWrite(...)` 完成类型与可写性校验
- 根据对象 owner 分派发射 setter 调用或 self 直写
- unknown object 保持 `godot_Object_set` 路径

### 3.4 `CBodyBuilder` 协作边界

- 指针转换、上行表达式、对象写槽生命周期统一由 `CBodyBuilder` 承载。
- 生成器不复制 own/release 规则，不手工拼接对象表示转换逻辑。
- `valueOfCastedVar(...)` 是 receiver 对齐的唯一推荐入口。

---

## 4. 长期约定（必须保持）

### 4.1 继承链解析约定

- 对已知对象类型必须执行继承链查找，命中即停止（最近 owner 优先）。
- 允许同名遮蔽；解析结果固定为最近命中层，不做冲突拒绝。
- 已知对象类型下属性不存在必须 fail-fast，不允许静默回退 GENERAL。

### 4.2 防御性约定

- 继承环必须 fail-fast（错误信息需带层级链路）。
- 中途父类 metadata 缺失必须 fail-fast（错误信息需带 receiver/property/insn 上下文）。
- unsupported owner 分类必须 fail-fast（只接受 GDCC / ENGINE owner）。

### 4.3 分派与转换约定

- 属性调用符号名必须使用 owner 类名生成，不允许按 receiver 类名直拼。
- receiver 与 owner 类型不一致时，必须通过 `renderOwnerReceiverValue(...)` 对齐。
- 禁止在生成器中以手写 C cast 替代 `CBodyBuilder` 的上行/表示转换逻辑。

### 4.4 类型校验方向约定

- `LOAD_PROPERTY`：`checkAssignable(propertyType, resultType)`
- `STORE_PROPERTY`：`checkAssignable(valueType, propertyType)`
- 后续修改若触及校验方向，必须补负向测试，不允许只改断言文本。

### 4.5 生命周期约定

- self 直写/直读依然进入 `assignVar` / `assignExpr` 路径，遵循统一槽位语义。
- 对象槽写入顺序必须保持：
  - capture old -> assign -> own(BORROWED only) -> release old
- 不允许在生成器层绕开该顺序直接拼接生命周期调用。
- 非对象 destroyable/value-semantic 槽位写入也有对应硬约束：
  - `proven no-alias` 的 `BORROWED` source，继续生成 `slot = godot_new_<Type>_with_<Type>(source_ptr)`
  - `may-alias` 的 `BORROWED` source，必须先生成 stable carrier，再 destroy old slot，并把 carrier consume 到 slot
  - 不允许生成“copy temp -> plain `slot = temp` -> destroy temp”，因为 `slot = temp` 只做浅层 struct 赋值
- getter-self 读取 backing field 时，若后续 copy helper 需要地址，必须优先使用 `&self->field` 这类现有 storage 地址；
  不得通过 `tmp = self->field` 人工物化地址。

### 4.6 Extension API 兼容约定

- 对 engine class property 元数据，若 `is_readable` / `is_writable` 字段缺失，
  在加载层按默认 `true` 处理（兼容 Godot API 数据中缺省字段场景）。
- 若字段存在，则以显式值为准。

---

## 5. 回归测试基线

### 5.1 单元生成测试

- `src/test/java/dev/superice/gdcc/backend/c/gen/CLoadPropertyInsnGenTest.java`
  - 继承链 owner 解析（GDCC/ENGINE/跨类别）
  - 可读性校验（含 engine unreadable）
  - fallback 与 fail-fast 路径
  - getter-self backing-field copy 直接取 `&self->field`
  - value-semantic getter-self 不残留 shallow temp materialization
- `src/test/java/dev/superice/gdcc/backend/c/gen/CStorePropertyInsnGenTest.java`
  - 继承链 owner 解析（GDCC/ENGINE/跨类别）
  - 可写性与类型方向校验
  - setter-self owner 保护与生命周期顺序断言
  - value-semantic setter-self 不残留 temp lifetime leakage
- `src/test/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolverTest.java`
  - 命中即停止、遮蔽、unknown/fail-fast、环检测

### 5.2 引擎运行时集成测试

- `src/test/java/dev/superice/gdcc/backend/c/gen/LoadStorePropertyInsnGenEngineInheritanceTest.java`
  - ENGINE child -> ENGINE owner（LOAD/STORE）
  - GDCC receiver -> ENGINE owner（LOAD/STORE）
  - getter-self / setter-self 引擎继承专项（功能 + 生命周期顺序）

建议命令：

```bash
./gradlew test --tests PropertyAccessResolverTest --tests CLoadPropertyInsnGenTest --tests CStorePropertyInsnGenTest --tests LoadStorePropertyInsnGenEngineInheritanceTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

---

## 6. 当前残余风险与后续建议

1. `resolveObjectProperty(...)` 终止条件当前使用 `superName.isBlank()`。若后续需要更严格元数据约束，可评估收敛为 `isEmpty()` + 显式格式校验。
2. `resolveOwnerDispatchMode(...)` 依赖 `ClassRegistry` 对根类（如 `Object`）分类完整性。若 registry 策略变化，应优先补回归测试再调整分派逻辑。
3. self 直访优化依赖“当前函数名 == getter/setter 名称”这一契约。若未来支持属性访问器重命名映射或多态访问器，需要同步升级判定规则。

---

## 7. 工程反思（保留长期价值）

1. 属性访问在继承场景中必须先解析 owner 再生成代码；直接按 receiver 静态类型发射符号名会系统性出错。
2. receiver 上行和指针表示转换应长期维持单点实现（`PropertyAccessResolver` + `CBodyBuilder`），否则 load/store/call_method 很容易再次语义分叉。
3. self 直访优化必须带 owner 约束；仅靠函数名匹配会在继承链场景产生隐蔽错误。
4. 实现文档应保持“当前事实 + 长期约定”最小集合，阶段执行日志应留在提交历史，不应长期保留在 module 实现文档中。

---

## 8. 非目标（当前不做）

1. 改造 Low IR 指令层语义或指令集。
2. 扩展 `load_static` / `store_static` 的语义边界。
3. 为属性访问引入新的独立生命周期模型（继续复用 `CBodyBuilder` 统一模型）。
