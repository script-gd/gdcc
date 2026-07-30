# Frontend `is` / `is not`（TypeTestExpression）实施计划

> 本文档是 `is` / `is not` 类型测试表达式的**分步骤实施计划与验收细则**。
> 落地完成后，应将已冻结合同抽离/改写为长期事实源，并同步更新本目录下相关 docs 与 `frontend_rules.md` 中的 compile intercept 列表。
>
> 更新时间：2026-07-30  
> 状态：Phase 0–3 已完成；Phase 1 Shared semantic + Phase 2 unified body lowering + Phase 3 backend 分派/`IsInstanceOfInsnGen`/runtime helpers；Phase 4 compile gate 解封待实施；Phase 5 单元测试 + 端到端测试待实施

---

## 0. 目标与范围

### 0.1 目标

在 gdcc 中端到端支持 GDScript 的类型测试表达式：

```gdscript
x is T
x is not T
```

对齐 Godot 4.x 语义（见 §2），使 shared semantic、compile gate、CFG body lowering、LIR 与 C backend 形成可编译闭环。

### 0.2 LIR 核心原则（本修订冻结）

**单一 opcode 表示全部 `is`：**

```
$<result_id:bool> = is_instance_of "<type_name>" $<value_id>
```

| 原则 | 说明 |
|------|------|
| 统一表面 | frontend → LIR **只**发射 `is_instance_of`（或编译期 bool 常量），不按 builtin/object/container 拆成多种 LIR 指令 |
| 便于 HIR | 未来 HIR 优化/变形只需认识一条 type-test 抽象；路径选择是 codegen 细节，不是 IR 形状 |
| 禁止 LIR 展开 | **禁止** frontend 把 `is` 展开为 `get_variant_type` + 比较、`call_intrinsic` 多指令序列等“配方式” LIR |
| 后端分派 | C backend 根据 `$value_id` 静态类型 + `type_name` 解析结果选择：常量折叠 / Variant 类型枚举比较 / Object 继承链 / 参数化容器 helper；`UNRESOLVED_OBJECT` 目标强制运行时 |
| `is not` | 无独立 opcode；`unary_op NOT` 包一层（或与 type-test 一并折叠） |

`doc/gdcc_low_ir.md` 仅保留语法与一句式语义；**完整 lowering / codegen / 折叠合同以本文 §3 为准**。

### 0.3 明确非目标（本计划不实施）

| 非目标 | 说明 |
|--------|------|
| `CastExpression` / `as` | 同档 compile intercept，合同不同；**不**顺带解封 |
| `is_instance_of()` 全局函数 | 运行时可变类型参数 API；可共享 runtime helper，函数本体另立任务 |
| `not in` 运算符 | 平行设计，不在本任务范围 |
| path-based / autoload / global-script-class 作为 `T` | 沿用 `ScopeTypeResolver` MVP 支持面 |
| nested structured container 作为 `T` | 如 `Array[Array[int]]`；不得放宽 |
| 独立 HIR pass | 当前仍 frontend CFG → LIR；但 **IR 形状已为 HIR 预留单一 type-test 节点** |
| 把 `is` 塞进 `GodotOperator` / `binary_op` | **禁止** |

### 0.4 解封前置合同（三条件同时满足）

来自 `frontend_lowering_plan.md` §6：

1. frontend → LIR lowering 已稳定产出  
2. backend 已能消费该产物  
3. 文档与正反测试已同步  

**只有三条都满足后**，才允许从 `FrontendCompileCheckAnalyzer` 移除 `TypeTestExpression` 显式 compile block。  
不得“先解封 gate、再补 backend”。

---

## 1. 现状基线（调研结论）

### 1.1 已存在

| 层 | 资产 | 位置 |
|----|------|------|
| Parser AST | `TypeTestExpression(value, targetType, negated, range)` | gdparser `0.5.2`（**无需改解析器**） |
| CFG item | `TypeTestItem` | `frontend/lowering/cfg/item/TypeTestItem.java` |
| CFG builder | `buildTypeTestValue` | `FrontendCfgGraphBuilder` ~1066–1079 |
| 结果 materialize | 固定 `GdBoolType.BOOL` | `FrontendBodyLoweringSupport` ~231–235 |
| LIR 指令骨架 | `IsInstanceOfInsn` + `GdInstruction.IS_INSTANCE_OF` | 操作数已是 `(STRING, VARIABLE)`，**与统一表面兼容** |
| LIR 解析 | `ParsedLirInstruction` | 已映射 `is_instance_of` |
| LIR 文档 | `doc/gdcc_low_ir.md` | 已扩展为统一 type-test 语义 |
| 运行时原料 | `godot_object_get_class_name`、`godot_ClassDB_is_parent_class`、`godot_variant_get_type` | C bindings / helpers |
| 近似 helper | `gdcc_check_variant_type_object` | **unpack 语义，不可直接当 `is` 用**（null→true） |

### 1.2 三处拦截（必须全部解除）

| 层 | 当前行为 | 位置 |
|----|----------|------|
| Shared semantic | ~~`DEFERRED`~~ → **`RESOLVED(bool)`（P1 已完成）** | `FrontendExpressionSemanticSupport.resolveTypeTestExpressionType` |
| Compile gate | 显式 `Type-test expression` block（P4 才解封） | `FrontendCompileCheckAnalyzer` ~538–541 |
| Body lowering | ~~stub~~ → **`is_instance_of` / 常量 / `NOT`（P2 已完成）** | `FrontendTypeTestInsnLoweringProcessor` |

### 1.3 缺失 / 需对齐统一合同

| 层 | 缺口 |
|----|------|
| Semantic | ~~无 `RESOLVED(bool)`~~ → **已实现**；RHS 解析 + `typeTestTargets` side-table（`FrontendTypeTestTarget`） |
| LIR Java 命名 | ~~`className`/`objectId`~~ → **`typeName`/`valueId`（P2 已完成）** |
| Backend | ~~无 `IsInstanceOfInsnGen`~~ → **P3 已完成**（常量 / type-enum / object helper / typed container helper） |
| Runtime | ~~缺 helper~~ → **`gdcc_is_instance_of_object_*` + typed array/dictionary helpers**（null→false） |
| 测试 | body lowering + **`IsInstanceOfInsnGenTest` codegen 正反测（P3）** |

### 1.4 关联文档

- `doc/gdcc_low_ir.md` §`is_instance_of`（语法表面；详细合同见本文 §3）
- `doc/module_impl/frontend/frontend_rules.md`
- `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
- `doc/module_impl/frontend/frontend_lowering_plan.md` §6
- `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
- `doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md` §4.4
- `doc/module_impl/frontend/scope_type_resolver_implementation.md`
- `doc/module_impl/frontend/gdcc_facing_class_name_contract.md`
- `doc/gdcc_runtime_lib.md`

---

## 2. Godot 语义对齐（验收真源）

### 2.1 语法与 AST

- RHS 是编译期类型，不是表达式。
- gdparser：`TypeTestExpression.negated()` 表示 `is not`；**不**合成 UnaryExpression AST。
- 结果恒为 `bool`。

### 2.2 合法 / 非法 RHS

| RHS `T` | 合法 | 运行时检查概要 |
|---------|------|----------------|
| 非参数化 builtin（`int`、`String`、`Packed*Array`、裸 `Array`/`Dictionary`…） | ✅ | `GDExtensionVariantType` 精确相等 |
| `Array[T]` / `Dictionary[K,V]` | ✅ | typed metadata 全匹配 |
| Engine / gdcc Object 类（编译期已解析） | ✅ | 继承链（ClassDB / 等价） |
| 编译期不可知但语法合法的 Object 类名（标识符） | ✅（降级） | 仅运行时 ClassDB 继承链；禁止折叠（§3.4） |
| 变量表达式 / `null` 作类型 | ❌ | 编译错误 |
| nested structured container | ❌ | 类型面已拒绝 |

### 2.3 关键边界

| 场景 | 期望 |
|------|------|
| `null is *` | `false` |
| `1 is int` / `1.0 is int` | `true` / `false` |
| 子类 `is` 父类 | `true` |
| 硬类型确定不兼容 | 可编译期报错或折叠 `false`（Variant 不误报） |

### 2.4 `is not`

语义 `not (x is T)`；LIR 用 `unary_op NOT` 包 `is_instance_of`（或双折叠常量）。

---

## 3. 设计决策（本计划冻结）

### 3.1 不是 binary operator

- **禁止** `GodotOperator.IS` / `BinaryOpInsn`。
- 路径：`TypeTestExpression` → `TypeTestItem` → **`is_instance_of` 或 bool 常量**。

### 3.2 统一 LIR 形状（唯一 frontend 产物）

```
# 一般情况
$result = is_instance_of "<type_name>" $value

# is not
$tmp = is_instance_of "<type_name>" $value
$result = unary_op NOT $tmp

# 编译期可判定（frontend 或 backend 均可折叠；frontend 允许直接发常量 bool）
$result = <bool constant materialization>
```

**抽象语义（Godot 4.x `is`）：**

- 结果恒为 `bool`；`null` / nil object → `false`
- 非参数化 builtin / packed：`GDExtensionVariantType` **精确**匹配（无 `int`/`float` 互通）
- Object 类：继承链（子类 `is` 父类 → `true`）
- 参数化容器：typed metadata 全匹配（裸 `Array` 不是 `Array[int]`）
- **禁止** frontend 展开为 `get_variant_type`+比较、按族拆 opcode / multi-intrinsic 等 LIR 配方；路径选择只在 backend（或未来等价 HIR rewrite）

**`type_name` 字符串规则：**

- 编译期常量类型文本；与 declared-type / `ScopeTypeResolver` 同一文本族
- Object 用 **canonical / Godot-facing** 名，禁止 source-only 别名
- 参数化容器写全：`"Array[int]"`、`"Dictionary[String, int]"`
- nested structured container 不得出现
- builtin / 参数化容器：frontend 写入前**必须**已成功解析为 `GdType`（side-table 为真源；字符串是 LIR 序列化）
- Object 类名：解析成功 → 正常发布 `GdType`；解析失败但为合法标识符 → 以 `UNRESOLVED_OBJECT(name)` 发布（§3.6），`type_name` 字符串原样透传，仅走运行时路径

**`value_id` 规则：**

- 保持操作数的 **ordinary 静态类型**，**不要**为了 `is` 强行统一 pack 成 Variant 或统一 cast 成 Object 再测（除非 boundary 合同本身要求）
- backend 根据静态类型选路径；frontend 不为分派预拆指令
- 与 `get_variant_type` / `get_class_name` / `object_cast` / `variant_is_nil` / `object_is_null` 独立；它们不是 `is` 的必经中间指令

### 3.3 Backend 分派矩阵（codegen，非 LIR 多 opcode）

| `$value_id` 静态类型 | `type_name` 解析结果 | Codegen 动作 |
|----------------------|----------------------|--------------|
| 精确类型，与 `T` 确定相同 / 可 upcast 的 Object 子类→父类 | 任意可判定 | **常量 `true`** |
| 精确类型，与 `T` 确定不相交 | 任意可判定 | **常量 `false`** |
| `Variant` 或 runtime-open | 非参数化 builtin / packed / 裸 Array·Dictionary | `godot_variant_get_type` **与 `GdExtensionTypeEnum` 比较**（可内联，不必单独 LIR） |
| Object 静态类型，或 Variant 且目标为 Object 类 | Object / class（已解析） | **继承链 helper**（`godot_ClassDB_is_parent_class` 等） |
| 任意 | unresolved object name（`UNRESOLVED_OBJECT`） | **强制运行时**继承链 helper，**禁止折叠**（无论 value 静态类型） |
| `Variant` 或容器值 | `Array[T]` / `Dictionary[K,V]` | **参数化容器 runtime helper** |
| 其它尚不支持组合 | — | fail-closed（诊断或 codegen 错误），禁止 silent `false` |

折叠表示例：

| 静态 `$value_id` | `type_name` | 折叠 |
|------------------|-------------|------|
| exact `int` | `"int"` | `true` |
| exact `float` | `"int"` | `false` |
| exact `Node2D` | `"Node"` | `true`（确定 upcast） |
| exact `Node` | `"Node2D"` | 不折 `true`；仅当层级证明不可能才折 `false`，否则 runtime |
| exact 非 object builtin | 某 object 类 | `false` |
| exact object | 非 object builtin | `false` |
| nil / 已知 null | 任意 | `false` |
| 任意 | `UNRESOLVED_OBJECT` 目标 | **不折叠**，强制 runtime |

### 3.4 编译期常量折叠（两层都允许）

| 层 | 职责 |
|----|------|
| Frontend lowering | 在操作数静态类型 + 目标 `GdType` 已能判定时，可直接 materialize `true`/`false`（含 `negated` 取反），**不必**发 `is_instance_of` |
| Backend codegen | 对仍发出的 `is_instance_of`，若变量静态类型 + `type_name` 可判定，同样折叠，作为第二道保险 |

典型折叠：

- object **upcast 确定为真**（`Node2D is Node` 且静态类型为 `Node2D`）→ `true`
- 精确 builtin 同名 → `true`；精确 builtin 异名 → `false`
- 精确 object vs 非 object builtin → `false`
- **不确定**（如静态 `Node` 测 `Node2D`，或 `Variant`）→ 运行时路径
- **`UNRESOLVED_OBJECT` 目标：永远不折叠**，无论 `$value_id` 静态类型如何，一律走运行时继承链 helper

### 3.5 Runtime helpers

```c
// Object：null → false；live → exact 或 is_parent_class
godot_bool gdcc_is_instance_of_object(/* object or variant object payload */, const char *type_name /* or StringName */);

// 参数化容器：typed metadata 匹配（Godot TYPE_TEST_ARRAY / DICTIONARY 对齐）
godot_bool gdcc_is_instance_of_typed_array(const godot_Variant *value, /* element type encoding */);
godot_bool gdcc_is_instance_of_typed_dictionary(const godot_Variant *value, /* key/value encoding */);
```

**禁止**复用 `gdcc_check_variant_type_object` 的 null→true 语义。

非参数化 builtin **优先内联** `get_type == enum`，不强制新 helper；若实现统一封装亦可，但 LIR 仍只有 `is_instance_of`。

### 3.6 RHS 解析与 publication

| 事实 | 合同 |
|------|------|
| `expressionTypes[TypeTestExpression]` | 成功 → `RESOLVED(BOOL)` |
| 目标 `GdType`（已解析） | 必须 side-table 发布（推荐 `resolvedTypeTestTargets` 或等价槽位） |
| 目标 Object 类名（未解析） | 以 `UNRESOLVED_OBJECT(name)` 发布至同一 side-table；`name` 为源码标识符原文 |
| `negated` | 只影响是否套 `NOT` / 折叠时取反 |
| chain binding | 不再把 type-test 当作 typing DEFERRED |

**`UNRESOLVED_OBJECT` 语义：**

- 仅适用于 RHS 为合法标识符但 `ScopeTypeResolver` 未命中的情况
- builtin / 参数化容器**不得**以此状态发布（必须解析成功，否则编译错误）
- 下游（lowering / backend）见到此标记时：`type_name` 原样透传，禁止折叠，强制运行时路径
- 触发 lint warning（§3.7）

### 3.7 诊断 category

| 情况 | category | 级别 |
|------|----------|------|
| RHS 非合法标识符（表达式、`null`、nested container） | `sema.expression_resolution` / type-ref 诊断 | **error** |
| RHS 合法标识符但 ScopeTypeResolver 未命中（builtin / 容器族） | `sema.expression_resolution` | **error**（builtin / 容器必须编译期已知） |
| RHS 合法标识符但 ScopeTypeResolver 未命中（Object 族降级） | `sema.type_test_unresolved_object` | **warning (lint)**：`"type name '<name>' not found in scope, will be checked at runtime"` |
| 硬类型确定不兼容 | `sema.type_check`（建议；Variant 跳过） | error / warning（视策略） |
| 当前 codegen 不支持的组合 | fail-closed，非 silent false | error |
| compile gate 临时拦截 | `sema.compile_check`（解封后删除） | error |

**lint warning 细则：**

- 仅在 RHS 降级为 `UNRESOLVED_OBJECT` 时触发，不阻塞编译
- 消息模板：`type name '{name}' not found in scope, will be checked at runtime`
- 附带 trade-off 说明：拼写错误将表现为运行时 `false` 而非编译错误
- 未来可选：提供 suppress 机制（如 `@warning_ignore("unresolved_type_test")`）

### 3.8 Java API 命名对齐（实施时）

将 `IsInstanceOfInsn` 字段语义对齐文档：

- `className` → `typeName`（完整类型文本，可含 `Array[int]`）
- `objectId` → `valueId`

文本序列化仍为 `is_instance_of "<type_name>" $value`；`GdInstruction` 操作数种类不变。

---

## 4. 分阶段实施与验收细则

### Phase 0 — 合同冻结（文档）

**工作内容**

1. 确认 `TypeTestExpression` 字段（已确认）。
2. 冻结 §0.2 / §3 与 `doc/gdcc_low_ir.md` §`is_instance_of`。
3. 明确：**不**解封 `CastExpression`；**不**在 LIR 增加第二套 type-test opcode。

**验收**

- [x] `gdcc_low_ir.md` 语法已扩展为 `is_instance_of "<type_name>" $value_id`（精简描述）。
- [x] 详细分派/折叠/helper 合同写在本文 §3。
- [x] 评审确认单一 opcode + backend 分派 + 常量折叠策略。
- [x] 评审确认不解封 Cast。

**评审确认记录（2026-07-30）**

1. **单一 opcode 确认**：frontend → LIR 仅发射 `is_instance_of`（或编译期 bool 常量），
   不按 builtin/object/container 拆成多种 LIR 指令。`GdInstruction.IS_INSTANCE_OF`
   操作数为 `(STRING, VARIABLE)`，与统一表面兼容。禁止 frontend 展开为
   `get_variant_type` + 比较、`call_intrinsic` 多指令序列等配方式 LIR。
2. **Backend 分派确认**：路径选择是 codegen 细节（§3.3 矩阵），不是 IR 形状。
   C backend 根据 `$value_id` 静态类型 + `type_name` 解析结果选择：
   常量折叠 / Variant 类型枚举比较 / Object 继承链 / 参数化容器 helper。
3. **常量折叠确认**：frontend lowering 与 backend codegen 两层均允许折叠（§3.4），
   不确定时（如静态 `Node` 测 `Node2D`，或 `Variant`）走运行时路径，禁止过早折叠。
4. **不解封 Cast 确认**：`CastExpression` 与 `TypeTestExpression` 共享 compile gate
   注册模式但合同不同；本计划仅解封 TypeTest，Cast 保持拦截直至其独立任务完成。
5. **不增加第二套 opcode 确认**：禁止在 LIR 增加 `is_builtin_type`、`is_object_class`、
   `is_typed_container` 等分叉 opcode；`not in` 平行备忘仍适用。
6. **Unresolved Object 降级修订（2026-07-30）**：允许编译期不可知但语法合法的 Object
   类名作为 RHS，以 `UNRESOLVED_OBJECT(name)` 发布，强制运行时继承链检查、禁止折叠；
   触发 lint warning `"type name '<name>' not found in scope, will be checked at runtime"`。
   builtin / 参数化容器仍必须编译期解析，不享受降级。

---

### Phase 1 — Shared semantic

**工作内容**

1. `FrontendExpressionSemanticSupport`：type-test → 解析 value + RHS → `RESOLVED(BOOL)`。
2. 发布目标 `GdType` side-table；Object 类名解析失败时发布 `UNRESOLVED_OBJECT(name)`。
3. （建议）hard-typed 不兼容 `sema.type_check`。
4. 更新 DEFERRED 相关测试与 chain-binding 文档集合。
5. 实现 lint warning：`UNRESOLVED_OBJECT` 时发出 `type name '<name>' not found in scope, will be checked at runtime`。

**验收**

- [x] `x is Node` / `x is not int` → `RESOLVED(bool)`；`negated` 保留。
- [x] 非法 RHS（表达式、`null`、nested container）→ 诊断 error，无假 `RESOLVED(bool)`。
- [x] RHS 为合法标识符但 ScopeTypeResolver 未命中 → `UNRESOLVED_OBJECT(name)` + lint warning，仍 `RESOLVED(bool)`。
- [x] builtin / 容器族未解析 → 仍为 error（不降级）。
- [x] 目标测试通过；compile gate **仍拦截** type-test。

**Phase 1 落地记录（2026-07-30）**

| 项 | 实现 |
|----|------|
| 语义解析 | `FrontendExpressionSemanticSupport.resolveTypeTestExpressionType`：value 依赖传播 + RHS `tryResolveSourceFacingDeclaredType` |
| 结果类型 | `expressionTypes[TypeTestExpression] = RESOLVED(bool)`；`negated` 仅保留在 AST |
| 目标 side-table | `FrontendAnalysisData.typeTestTargets()`：`FrontendTypeTestTarget.TargetKnown(GdType)` / `TargetUnresolvedObject(name)` |
| 发布路径 | `BodyExpressionResolver` 捕获 target → `TypedLexicalEnvironment.putTypeTestTarget` → `FrontendExprTypePatch` |
| 诊断 | 非法/nested RHS → `sema.expression_resolution` error；unresolved object → `sema.type_test_unresolved_object` warning |
| 非目标 | hard-typed 不兼容 `sema.type_check` **未做**（留 P6）；compile gate / lowering **未解封** |
| 测试 | `FrontendExpressionSemanticSupportTest` 正反路径 + e2e + compile-gate 仍拦截 |

---

### Phase 2 — Body lowering：统一发射 `is_instance_of` / 常量

**工作内容**

1. 实现 `FrontendTypeTestInsnLoweringProcessor`：
   - 读取已解析目标 `GdType` → 序列化为 `type_name` 字符串。
   - 若为 `UNRESOLVED_OBJECT(name)`：`type_name` 原样透传，**禁止折叠**，直接发射 `is_instance_of`。
   - 若静态可判定（仅限已解析目标）：直接 materialize bool（含 `negated` 取反）。
   - 否则：发射 **一条** `IsInstanceOfInsn(result, typeName, valueId)`；`negated` 时再 `unary_op NOT`。
2. **禁止**按目标族拆成 `get_variant_type` / 多 intrinsic 的 LIR 序列。
3. 重命名 insn 字段（可选但推荐与本阶段同批）。
4. 操作数保持 ordinary 类型；boundary 仅走既有 helper。

**验收**

- [x] Object / builtin / 参数化容器目标在 LIR 中均为 `is_instance_of "..."` 或常量 bool。
- [x] `UNRESOLVED_OBJECT` 目标：始终发射 `is_instance_of`，不折叠。
- [x] `is not` 有 NOT 或折叠取反。
- [x] 无可判定时不错误折叠；可判定 upcast / 同 builtin 能折叠。
- [x] 相关 lowering 单测通过。

**Phase 2 落地记录（2026-07-30）**

| 项 | 实现 |
|----|------|
| Processor | `FrontendTypeTestInsnLoweringProcessor`：读 `typeTestTargets` + operand 静态类型 |
| Runtime 路径 | 一条 `IsInstanceOfInsn(result, typeName, valueSlot)`；`negated` 时先写 positive temp 再 `unary_op NOT` |
| 折叠 | exact match → true；`Nil` 字面量 → false；object upcast → true；typed Array/Dictionary → bare Array/Dictionary → true；object↔非 object → false；exact 非 object 异名（含 bare→typed 容器）→ false；`Variant` / parent→child object / `UNRESOLVED_OBJECT` **不折** |
| 命名 | `IsInstanceOfInsn` 字段 `className`/`objectId` → `typeName`/`valueId` |
| 禁止项 | 未展开 `get_variant_type`；未解封 compile gate；未做 backend |
| 测试 | `FrontendTypeTestInsnLoweringTest` 正反路径 + gate 仍拦截；`IsInstanceOfInsnContractTest` 字段重命名 |

---

### Phase 3 — Backend：`IsInstanceOfInsnGen` 分派 + helpers

**工作内容**

1. `IsInstanceOfInsnGen`：解析 `type_name`（或消费 frontend 已解析信息的旁路；**以变量静态类型 + type 字符串/类型表为准**）。
2. 实现 §3.3 五条路径：常量 / Variant 枚举比较 / Object 继承链（已解析） / Object 继承链（unresolved，强制运行时） / 参数化容器 helper。
3. `CCodegen` 注册；`gdcc_runtime_lib.md` 记录 helpers。
4. 单测覆盖各分派（参照 `ObjectIsNullInsnGenTest`）。

**验收**

- [x] 任意合法 `is_instance_of` LIR 不再 `UnsupportedOperationException`。
- [x] null object → false；子类 is 父类 → true（helper 路径 / 可折叠 upcast）。
- [x] Variant is int：生成 type-enum 比较而非错误的 ClassDB 调用。
- [x] `Array[int]`：走 typed helper，而非裸 `ARRAY` 枚举误判。
- [x] `UNRESOLVED_OBJECT` 目标：无论 value 静态类型，均走运行时继承链 helper，不折叠。
- [x] `IsInstanceOfInsnGenTest`（及必要辅助测）通过。

**Phase 3 落地记录（2026-07-30）**

| 项 | 实现 |
|----|------|
| InsnGen | `IsInstanceOfInsnGen`：解析 `type_name`（`tryResolveDeclaredType` / unresolved 标识符降级）+ 操作数静态类型分派 |
| 折叠 | 镜像 frontend `tryFoldKnownTypeTest`：exact / upcast / Nil→false / 族不相交→false / bare Array·Dictionary 接受 typed；`Variant` 与 parent→child / `UNRESOLVED_OBJECT` 不折 |
| Builtin 路径 | `godot_variant_get_type(...) == GDEXTENSION_VARIANT_TYPE_*`（非参数化 builtin / packed / 裸 Array·Dictionary） |
| Object 路径 | `gdcc_is_instance_of_object_raw_and_id`（fat ptr）/ `gdcc_is_instance_of_object_variant`；**禁止**复用 `gdcc_check_variant_type_object`（null→true） |
| 参数化容器 | codegen 生成 typed leaf 常量（builtin enum + class `StringName`）；`gdcc_is_instance_of_typed_{array,dictionary}[_variant]` |
| 注册 | `CCodegen` 注册 `IsInstanceOfInsnGen` |
| Runtime 文档 | `gdcc_runtime_lib.md` 记录上述 helpers |
| 测试 | `IsInstanceOfInsnGenTest` 正反路径（折叠、Variant 枚举、object helper、typed container、object-leaf 容器、bare Array/Dictionary、unresolved、fail-closed） |
| 开放问题 #4 决策 | **不在运行时再解析 `"Array[int]"` 字符串**；codegen 侧生成 typed metadata 常量传入 helper |
| UNRESOLVED + 精确非 object 值 | 禁止“真”折叠 / 禁止当 object 实例；对 `int is FutureEnemy` 等**可发常量 false**（语义正确，不强制无意义 ClassDB 调用） |

---

### Phase 4 — 解封 compile gate（仅 TypeTest）

**前置**：P1–P3 验收通过。

**工作内容**

1. 移除 `TypeTestExpression` 显式 compile block。
2. 同步 `frontend_rules.md`、compile-check 文档、lowering_plan 第 4 项说明、diagnostic_manager、chain-binding deferred 列表。
3. compile-check 测试：`is` 放行；`as` 仍拦截。

**验收**

- [ ] `var b = x is Node` 可过 compile gate 并完成 codegen（在支持路径内）。
- [ ] `x as Node` 仍拦截。
- [ ] fact 级 blocker 仍生效。

---

### Phase 5 — 单元测试补全 + test_suite 端到端验证

**前置**：P4 验收通过（compile gate 已解封，`is` 可完整走通 codegen）。

**工作内容**

1. **Frontend 集成单测补全**：
   - 在 `FrontendLoweringBodyInsnPassTest` 中补充 `is` / `is not` 的 body lowering 集成路径，确保 type-test 与其它表达式在统一 pass 中协同工作。
   - 在 `FrontendCompileCheckAnalyzerTest` 中补充 gate 解封后的正向放行断言：`is` 通过、`as` 仍拦截。
   - 补充 `FrontendBodyOwnerProceduresExprTypeTest`（或等价 procedure-level 测试）中 `is` 表达式的类型发布验证。

2. **Backend 集成单测补全**：
   - 在 `CBodyBuilderPhaseCTest`（或等价全函数体 codegen 测试）中补充包含 `is_instance_of` 的完整函数体 C 输出验证。
   - 验证 `is not` 路径（`is_instance_of` + `unary_op NOT`）在完整函数体中的 C 输出正确性。

3. **test_suite 端到端用例**（遵循 `doc/test_suite.md` 合同）：
   - 在 `src/test/test_suite/unit_test/script/` 下新建 `type_test/` 分组目录，创建编译脚本：
     - `builtin_type_test.gd`：覆盖 `is int`、`is float`、`is String`、`is PackedInt32Array` 等非参数化 builtin 精确匹配与不匹配。
     - `object_type_test.gd`：覆盖 `is Node`、`is Node2D`（子类 is 父类 → true；父类 is 子类 → runtime）、null is Node → false。
     - `container_type_test.gd`：覆盖 `is Array[int]`、`is Dictionary[String, int]`、裸 `Array` / `Dictionary` 与参数化容器的区分。
     - `is_not_test.gd`：覆盖 `is not` 的 negated 语义（builtin、object、container 各至少一例）。
     - `variant_type_test.gd`：覆盖 `Variant` 操作数的运行时路径（builtin 枚举比较、object helper、typed container helper）。
   - 在 `src/test/test_suite/unit_test/validation/` 下创建同路径验证脚本：
     - 每个验证脚本通过 `target.call(...)` 调用编译脚本的公共方法，断言返回值。
     - 成功时打印 `__UNIT_TEST_PASS_MARKER__`，失败时 `push_error(...)`。
   - 更新 `GdScriptUnitTestCompileRunnerTest.EXPECTED_SCRIPT_PATHS`，加入新增资源对路径。

4. **编译脚本约束**（遵循 test_suite 合同）：
   - 所有编译脚本 `extends Node` + `class_name`。
   - 每个公共方法返回 `bool` 或 `int`，便于验证脚本断言。
   - 保持单一行为合同：每个脚本聚焦一个 type-test 子族。
   - 不在编译脚本中使用 `set_process(true)` 等非必要 toggle。

**验收**

- [ ] `FrontendLoweringBodyInsnPassTest` 包含 `is` / `is not` 集成路径且通过。
- [ ] `FrontendCompileCheckAnalyzerTest` 包含 gate 解封后 `is` 放行 + `as` 仍拦截断言。
- [ ] 完整函数体 codegen 测试覆盖 `is_instance_of` + `unary_op NOT` 组合。
- [ ] `type_test/` 下至少 5 组 script/validation 资源对，覆盖 §2.2 全部合法 RHS 族。
- [ ] `GdScriptUnitTestCompileRunnerTest` 动态生成用例包含新增路径且通过（Zig + Godot 可用时）。
- [ ] 新增用例不破坏既有 test_suite 用例。
- [ ] Zig / Godot 不可用时，JUnit assumption skip 仍正常工作。

**Phase 5 测试矩阵（最小覆盖）**

| 场景 | 编译脚本方法 | 验证断言 |
|------|-------------|----------|
| `1 is int` | `exact_builtin_match() -> bool` | `true` |
| `1.0 is int` | `builtin_mismatch() -> bool` | `false` |
| `"s" is String` | `string_match() -> bool` | `true` |
| `PackedInt32Array() is PackedInt32Array` | `packed_match() -> bool` | `true` |
| `node is Node` | `object_exact() -> bool` | `true` |
| `node2d is Node` | `object_upcast() -> bool` | `true` |
| `null is Node` | `null_is_object() -> bool` | `false` |
| `variant_val is int`（运行时） | `variant_builtin_runtime(v: Variant) -> bool` | 按实际类型 |
| `variant_val is Node`（运行时） | `variant_object_runtime(v: Variant) -> bool` | 按实际类型 |
| `arr is Array[int]` | `typed_array_match() -> bool` | `true` |
| `bare_arr is Array[int]` | `bare_is_typed_array() -> bool` | `false` |
| `dict is Dictionary[String, int]` | `typed_dict_match() -> bool` | `true` |
| `1 is not int` | `negated_exact_builtin() -> bool` | `false` |
| `node2d is not Node` | `negated_upcast() -> bool` | `false` |
| `null is not Node` | `negated_null() -> bool` | `true` |

---

### Phase 6 — 折叠与兼容性 hardening

| 项 | 说明 |
|----|------|
| Frontend 折叠覆盖面 | 常量字面量操作数、更多 hard-typed 不相交/相同 |
| Backend 折叠 | 与 frontend 双保险，不重复错误 |
| 硬类型诊断文案 | 对齐 Godot |
| freed instance | 与 Godot 对齐或文档化差异 |

**验收**：独立增量；不破坏 P1–P5。

---

## 5. 建议实现顺序

```
P0 文档/合同（已完成）
 → P1 semantic（已完成：RESOLVED(bool) + typeTestTargets + unresolved lint）
 → P2 unified lowering (is_instance_of | const)（已完成）
 → P3 backend dispatch + runtime helpers
 → P4 解封 TypeTest compile gate
 → P5 单元测试补全 + test_suite 端到端验证
 → P6 hardening
```

**禁止**：先删 compile block；禁止 LIR 多 opcode 分叉“优化”。

**与 Cast**：仅共享 RHS 解析与 Object 表示经验；gate 分开。

---

## 6. 关键文件清单

| 文件 | 阶段 | 动作 |
|------|------|------|
| `doc/gdcc_low_ir.md` | P0 | 精简语法面（已做）；细则在本计划 |
| `FrontendExpressionSemanticSupport.java` | P1 | type-test 类型 |
| analysis data / type-check（按需） | P1 | 目标类型 publication |
| `FrontendSequenceItemInsnLoweringProcessors.java` | P2 | type-test processor |
| `IsInstanceOfInsn.java` | P2 | 命名对齐 typeName/valueId |
| `IsInstanceOfInsnGen.java` | P3 | **新建** 分派 codegen |
| `CCodegen.java` | P3 | 注册 |
| `gdcc_helper.h` / runtime | P3 | object + typed container helpers |
| `FrontendCompileCheckAnalyzer.java` | P4 | 移除 block |
| 关联 frontend docs | P4 | 同步 intercept 列表 |
| `FrontendLoweringBodyInsnPassTest.java` | P5 | 补充 `is` / `is not` 集成路径 |
| `FrontendCompileCheckAnalyzerTest.java` | P5 | gate 解封后正向放行断言 |
| `CBodyBuilderPhaseCTest.java`（或等价） | P5 | 完整函数体 `is_instance_of` C 输出 |
| `unit_test/script/type_test/*.gd` | P5 | test_suite 编译脚本 |
| `unit_test/validation/type_test/*.gd` | P5 | test_suite 验证脚本 |
| `GdScriptUnitTestCompileRunnerTest.java` | P5 | 更新 `EXPECTED_SCRIPT_PATHS` |

**测试**

| 测试 | 阶段 |
|------|------|
| `FrontendExpressionSemanticSupportTest` | P1 |
| body lowering type-test | P2 |
| `IsInstanceOfInsnGenTest` | P3 |
| `FrontendCompileCheckAnalyzerTest` | P4 / P5 |
| `FrontendLoweringBodyInsnPassTest` | P5 |
| `CBodyBuilderPhaseCTest`（或等价） | P5 |
| `GdScriptUnitTestCompileRunnerTest` | P5 |

```bash
script/run-gradle-targeted-tests.sh --tests <TestClass>
```

---

## 7. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 再次把 `is` 拆成多 LIR 指令 | 代码审查以 `gdcc_low_ir.md` 为准拒绝 |
| unpack helper 污染 `is` null 语义 | 独立 helper + null 单测 |
| type_name source/canonical 混用 | facing-name 合同 + inner class 测例 |
| backend 把 `Array[int]` 当成裸 `ARRAY` | 分派矩阵强制参数化走 helper |
| `is not` 丢否定 | 强制 negated 测例 |
| 过早折叠 `Node is Node2D` 为 false | 仅在层级证明不相交时折 false；否则 runtime |
| `UNRESOLVED_OBJECT` 拼写错误变运行时 `false` | lint warning 提示；未来可加 suppress 机制 |

---

## 8. 开放问题

1. Freed instance：runtime error vs false？  
2. gdcc script class：仅 ClassDB 名是否足够，是否需要 script 指针链？  
3. Frontend vs Backend 折叠责任边界：P2 是否强制做满折叠，还是 P2 最小发指令、P3/P6 补折叠？  
4. ~~参数化容器 type 编码如何传入 C helper？~~ → **P3 决策：codegen 生成 typed 常量（builtin enum + class name），不在运行时解析 type 字符串。**  

---

## 9. 完成定义（DoD）

**MVP DoD（P0–P5）**

1. 源码 `x is T` / `x is not T` 经 semantic → compile → lowering → C codegen 闭环。  
2. LIR 仅见 `is_instance_of` 或 bool 常量（+ 可选 `unary_op NOT`），无配方式多指令 type-test。  
3. Backend 按 §3.3 正确分派；null→false；继承链与非参数化 builtin 路径可用。  
4. 参数化容器至少 fail-closed 或已实现 helper 路径之一（推荐 MVP 含 helper 或明确 UNSUPPORTED 诊断）。  
5. TypeTest 已解封；Cast 仍拦截；文档同步；目标测试通过。
6. test_suite 端到端用例覆盖 §2.2 全部合法 RHS 族；Zig + Godot 可用时通过。

**完整 Godot 对齐**：参数化容器 + 折叠/hard-typed 诊断 + hardening 全部达标。

---

## 10. 附录：与旧“多路径 LIR”方案的差异

| 旧方案风险 | 本修订 |
|------------|--------|
| builtin 用 `get_variant_type`+比较、object 用 `is_instance_of`、容器用 intrinsic → HIR 难统一 | 全部 `is_instance_of`；路径仅 codegen |
| 为每种检查加 opcode → IR 膨胀 | 单一 opcode |
| frontend 为后端“预拆” | frontend 只保证抽象语义 |

`not in` 平行备忘仍适用：保持 AST 根、实现复合语义、不丢否定；`is not` 用 `negated` + `NOT` 即可。
