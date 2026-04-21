# Frontend GDScript 隐式转换兼容性矩阵

> 本文档汇总 GDScript 在 typed boundary 上的隐式转换兼容性矩阵，并并列标注 Godot 当前支持面与 GDCC 当前支持面。这里的 “typed boundary” 指赋值、参数传递、返回、property store、subscript key/index 等需要判断 source/target 类型兼容性的边界；不包含运算符自身的专用 contract，也不包含 condition truthiness normalization。

## 文档状态

- 状态：事实源维护中（Godot 规则已梳理，GDCC 当前支持面已对齐到现有实现）
- 更新时间：2026-04-21
- 适用范围：
  - `doc/module_impl/frontend/**`
  - `src/main/java/dev/superice/gdcc/frontend/**`
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/type/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `frontend_rules.md`
  - `frontend_lowering_(un)pack_implementation.md`
  - `frontend_chain_binding_expr_type_implementation.md`
  - `frontend_type_check_analyzer_implementation.md`
  - `frontend_lowering_cfg_pass_implementation.md`
  - `doc/gdcc_type_system.md`
- 主要事实来源：
  - Godot `GDScriptAnalyzer::check_type_compatibility(...)`
  - Godot `Variant::can_convert_strict(...)`
  - `ClassRegistry.checkAssignable(...)`
  - `FrontendVariantBoundaryCompatibility`

## 0. 维护合同

- `frontend_implicit_conversion_matrix.md` 是 frontend typed-boundary implicit conversion 的唯一真源。
- 任何会改变 frontend typed boundary 兼容性的实现、测试、诊断或 lowering materialization 行为，都必须先修改本文档，再修改代码与测试。
- 其他 frontend 事实源文档不得重新维护一份 source/target conversion 清单；它们只能：
  - 说明自己消费的是哪一类 boundary
  - 引用本文档中的矩阵与维护合同
- 代码注释同样不得私自扩写“当前支持哪些 implicit conversion”的平行规则表；关键入口只允许引用本文档，并说明自己在整条链路中的职责。
- 若未来新增 `int -> float`、`String <-> StringName`、packed array widened conversion 等支持面，验收顺序固定为：
  1. 更新本文档矩阵与摘要
  2. 更新 shared helper / consumer 实现
  3. 更新对应测试
  4. 最后再更新依赖本文档的其他事实源文档中的引用性描述

---

## 1. 阅读方式

### 1.1 记号

- `Y`：当前支持
- `N`：当前不支持
- `P`：部分支持，见备注

### 1.2 适用边界

本文档只覆盖 typed boundary compatibility：

- ordinary assignment
- local / property initializer
- fixed call arguments
- vararg tail boundary
- return slot
- subscript key/index

本文档不覆盖：

- 运算符重载自己的参数/返回契约
- `if` / `while` / `assert` 的 truthiness contract
- runtime-dynamic `DYNAMIC` target 的开放语义
- backend 对 `Variant` pack/unpack 的运行时校验
- builtin 单参数 stable `Variant` constructor special route
  - 该路径属于 constructor resolution / lowering 合同，而不是 ordinary typed-boundary widened conversion

### 1.3 当前 GDCC 的统一基线

当前 GDCC frontend 的 concrete-slot compatibility 主干仍然刻意收口：

- same type：支持
- object inheritance upcast：支持
- `Array` / `Dictionary` 的有限协变：支持
- stable `Variant` boundary：支持
- `Nil -> object`：支持
- 其余 typed boundary：原则上回退 `ClassRegistry.checkAssignable(...)`

这意味着：

- `int(seed: Variant)` / `Array(seed: Variant)` 这类 builtin constructor special route 的接受与 lower 方式，不由本文矩阵定义
- 若未来要调整这条 constructor special route，应同步更新 `frontend_builtin_constructor_variant_argument_plan.md` 及相关事实源文档，而不是把它误记成 matrix 扩面

这意味着 Godot 支持、但不属于以上五类的 builtin implicit conversion，在 GDCC 中默认都还是 `N`。

---

## 2. GDScript 类型全集

### 2.1 内建值类型

- `Nil`
- `Variant`
- `bool`
- `int`
- `float`
- `String`
- `Vector2`
- `Vector2i`
- `Rect2`
- `Rect2i`
- `Vector3`
- `Vector3i`
- `Transform2D`
- `Vector4`
- `Vector4i`
- `Plane`
- `Quaternion`
- `AABB`
- `Basis`
- `Transform3D`
- `Projection`
- `Color`
- `StringName`
- `NodePath`
- `RID`
- `Callable`
- `Signal`
- `Dictionary`
- `Array`
- `PackedByteArray`
- `PackedInt32Array`
- `PackedInt64Array`
- `PackedFloat32Array`
- `PackedFloat64Array`
- `PackedStringArray`
- `PackedVector2Array`
- `PackedVector3Array`
- `PackedColorArray`
- `PackedVector4Array`

### 2.2 对象类类型

- `Object`
- 任意 engine native class，例如 `Node`、`RefCounted`
- 任意 script class

### 2.3 本文额外记录但当前 GDCC 未正式建模为一等 `GdType` 的类型

- enum type
- type-meta / class-meta route

---

## 3. 全局规则矩阵

这些规则跨越整张矩阵，优先级高于后文的 builtin family 表。

| 规则 | Godot | GDCC | 备注 |
| --- | --- | --- | --- |
| same type -> same type | Y | Y | 最基础兼容 |
| 任意 stable type -> `Variant` | Y | Y | GDCC 通过 `pack_variant` materialize |
| stable `Variant` -> concrete target | Y | Y | GDCC 当前已接通 ordinary `Variant` boundary，并通过 `unpack_variant` materialize |
| 任意 object subclass -> object superclass | Y | Y | 例如 `Sprite2D -> Node -> Object` |
| `null` / `Nil` -> object target | Y | Y | Godot 接受；GDCC frontend 通过 boundary helper 显式物化 object-typed `LiteralNullInsn` |
| `enum` value -> `int` | Y | N | GDCC 当前没有 enum 一等类型模型 |
| `int` -> enum target | Y | N | Godot 允许但通常伴随 warning/显式语义讨论；GDCC 当前未建模 |

---

## 4. 标量 / 字符串 / 杂项转换矩阵

下表只列出 “非 identity 且非 `Variant` / object-upcast 全局规则” 的 builtin strict implicit conversion。

| Source | Target | Godot | GDCC | 备注 |
| --- | --- | --- | --- | --- |
| `bool` | `int` | Y | N | Godot scalar family strict convert |
| `bool` | `float` | Y | N | Godot scalar family strict convert |
| `int` | `bool` | Y | N | 与 condition truthiness 不是一回事 |
| `int` | `float` | Y | N | 当前文档与测试已明确锚定 GDCC 拒绝 |
| `float` | `bool` | Y | N | Godot scalar family strict convert |
| `float` | `int` | Y | N | Godot 会截断；GDCC 当前不支持 |
| `String` | `StringName` | Y | N | Godot 文档明确说明常见 API 会自动转换 |
| `StringName` | `String` | Y | N | Godot strict convert table 支持 |
| `String` | `NodePath` | Y | N | Godot strict convert table 支持 |
| `NodePath` | `String` | Y | N | Godot strict convert table 支持 |
| `String` | `Color` | Y | N | 例如颜色文本到 `Color` |
| `int` | `Color` | Y | N | Godot strict convert table 支持 |
| `Object` | `RID` | Y | N | Godot strict convert table 支持 |

### 4.1 当前没有额外 builtin implicit conversion 的类型

以下 builtin family 当前在 Godot strict convert table 中没有额外 widened conversion；它们最多只依赖：

- same type
- `Variant` boundary
- 若该类型属于 object family，则再额外适用 object hierarchy 规则

对应类型：

- `Plane`
- `AABB`
- `Callable`
- `Signal`
- `Dictionary`

---

## 5. 几何 / 复合值类型转换矩阵

| Source | Target | Godot | GDCC | 备注 |
| --- | --- | --- | --- | --- |
| `Vector2i` | `Vector2` | Y | N | int-vector / float-vector 互转 |
| `Vector2` | `Vector2i` | Y | N | 反向也支持 |
| `Rect2i` | `Rect2` | Y | N | rect family 互转 |
| `Rect2` | `Rect2i` | Y | N | 反向也支持 |
| `Vector3i` | `Vector3` | Y | N | int-vector / float-vector 互转 |
| `Vector3` | `Vector3i` | Y | N | 反向也支持 |
| `Vector4i` | `Vector4` | Y | N | int-vector / float-vector 互转 |
| `Vector4` | `Vector4i` | Y | N | 反向也支持 |
| `Transform3D` | `Transform2D` | Y | N | Godot strict convert table 支持 |
| `Transform2D` | `Transform3D` | Y | N | Godot strict convert table 支持 |
| `Basis` | `Quaternion` | Y | N | 旋转表示互转 |
| `Quaternion` | `Basis` | Y | N | 反向也支持 |
| `Quaternion` | `Transform3D` | Y | N | `Transform3D` target widened source |
| `Basis` | `Transform3D` | Y | N | `Transform3D` target widened source |
| `Projection` | `Transform3D` | Y | N | `Transform3D` target widened source |
| `Transform3D` | `Projection` | Y | N | 反向也支持 |

### 5.1 当前没有额外 widened conversion 的几何值类型

- `Plane`
- `AABB`

---

## 6. 容器 / Packed Array 转换矩阵

### 6.1 `Array` 与 packed array family

| Source | Target | Godot | GDCC | 备注 |
| --- | --- | --- | --- | --- |
| `PackedByteArray` | `Array` | Y | N | Godot strict convert table 支持 |
| `PackedInt32Array` | `Array` | Y | N | Godot strict convert table 支持 |
| `PackedInt64Array` | `Array` | Y | N | Godot strict convert table 支持 |
| `PackedFloat32Array` | `Array` | Y | N | Godot strict convert table 支持 |
| `PackedFloat64Array` | `Array` | Y | N | Godot strict convert table 支持 |
| `PackedStringArray` | `Array` | Y | N | Godot strict convert table 支持 |
| `PackedVector2Array` | `Array` | Y | N | Godot strict convert table 支持 |
| `PackedVector3Array` | `Array` | Y | N | Godot strict convert table 支持 |
| `PackedColorArray` | `Array` | Y | N | Godot strict convert table 支持 |
| `PackedVector4Array` | `Array` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedByteArray` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedInt32Array` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedInt64Array` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedFloat32Array` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedFloat64Array` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedStringArray` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedVector2Array` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedVector3Array` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedColorArray` | Y | N | Godot strict convert table 支持 |
| `Array` | `PackedVector4Array` | Y | N | Godot strict convert table 支持 |

### 6.2 `Dictionary`

Godot strict implicit conversion 表里没有 `Dictionary` 到其他 builtin container 的 widened conversion。当前只依赖：

- same type
- `Variant` boundary

### 6.3 subscript key/index 的 frontend 基线与剩余 Godot widened conversion

当前 GDCC subscript key/index 已复用 ordinary typed-boundary helper `FrontendVariantBoundaryCompatibility`。这意味着：

- same type / object hierarchy / recursive container assignability 继续按 shared frontend boundary 处理
- ordinary `Variant` boundary 也已经适用于 subscript key/index
- 因此 plain `Dictionary`（`Dictionary[Variant, Variant]`）现在接受 `String` 等 stable key 写入 `Variant` key slot；对应 keyed lowering / backend codegen 会继续把 key 物化到真实 `Variant` 调用面

当前仍未支持的，是 Godot keyed/index 路径上的额外 widened compatibility：

| 场景 | Godot | GDCC | 备注 |
| --- | --- | --- | --- |
| `String` key 用于 `StringName` keyed access | Y | N | GDCC subscript 当前只复用 ordinary boundary helper，不单独追加 keyed widened conversion |
| `float` index 用于 `Array` / packed array | Y | N | 本质上依赖 `float -> int` 兼容 |

---

## 7. Object / Script Class 兼容矩阵

### 7.1 对象继承层次

| Source | Target | Godot | GDCC | 备注 |
| --- | --- | --- | --- | --- |
| concrete engine class | its superclass | Y | Y | 例如 `Sprite2D -> Node` |
| concrete script class | its script superclass | Y | Y | 例如 `Enemy -> Character` |
| 任意 object class | `Object` | Y | Y | 最宽 object upcast |
| `null` | 任意 object class | Y | Y | frontend sema 接受，lowering 统一物化 object-typed `LiteralNullInsn` |

### 7.2 当前 GDCC 尚未覆盖的对象相关 widened conversion

- `Object -> RID`

---

## 8. GDCC 当前额外存在、但不属于 Godot strict implicit conversion 表的兼容

下面这些是 GDCC 当前已经允许的 typed boundary compatibility，但它们更接近 shared assignability / frontend boundary contract，而不是 Godot `can_convert_strict(...)` 意义上的 builtin implicit conversion：

| 规则 | Godot strict implicit conversion | GDCC | 备注 |
| --- | --- | --- | --- |
| `Array[T] -> Array[Variant]` | N | Y | GDCC backend/global assignability 的有限 container covariance |
| `Array[Sub] -> Array[Super]` | N | Y | 当前是递归 assignability，不是 Godot strict convert |
| `Dictionary[K1, V1] -> Dictionary[K2, V2]`（递归可赋值） | N | Y | 当前是 shared assignability，不是 Godot strict convert |
| stable `Variant` -> concrete target | 不属于 `can_convert_strict`，但 source language 接受 | Y | 由 frontend `(un)pack` materialization 闭合 |

---

## 9. 现有实现锚点

### 9.1 GDCC 当前支持面的实现位置

- `ClassRegistry.checkAssignable(...)`
- `FrontendVariantBoundaryCompatibility`
- `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)`
- `FrontendExpressionSemanticSupport.matchesCallableArguments(...)`
- `FrontendSubscriptSemanticSupport.resolveSubscriptType(...)`
- `FrontendTypeCheckAnalyzer`
- `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`
  - ordinary `(un)pack` consumer/materialization 的长期合同以 `frontend_lowering_(un)pack_implementation.md` 为准

### 9.2 当前明确拒绝 widened conversion 的文档锚点

- `frontend_rules.md`
- `frontend_chain_binding_expr_type_implementation.md`
- `frontend_type_check_analyzer_implementation.md`
- `frontend_lowering_cfg_pass_implementation.md`

### 9.3 实现职责分层

- `FrontendVariantBoundaryCompatibility`
  - shared semantic helper
  - 定义 frontend ordinary typed boundary 的 compatibility decision
  - 其支持面必须与本文矩阵保持一一对应
- `FrontendAssignmentSemanticSupport.checkAssignmentCompatible(...)`
  - assignment / initializer / return 的 public semantic gate
  - 不得重新定义矩阵，只能委托 shared helper
- `FrontendExpressionSemanticSupport.matchesCallableArguments(...)`
  - call-site fixed argument compatibility gate
  - 只能复用 shared helper，不得在 call path 单独放宽 conversion
- `FrontendSubscriptSemanticSupport.resolveSubscriptType(...)`
  - subscript key/index 的 public semantic gate
  - 只能复用 `FrontendVariantBoundaryCompatibility` 提供的 ordinary boundary compatibility
  - 不得在 subscript path 单独追加 keyed/index widened conversion
- `FrontendTypeCheckAnalyzer`
  - 只消费已发布稳定类型事实，并通过 shared helper 执行 typed gate
  - 不得在 type-check phase 私自添加平行 conversion 规则
- `FrontendBodyLoweringSession.materializeFrontendBoundaryValue(...)`
  - 只负责把“已经被 shared semantic helper 允许”的 ordinary boundary 物化成显式 LIR
  - 不得独立决定新的 compatibility 规则；materialization 分支必须与本文矩阵和 shared helper 保持同构

---

## 10. 结论摘要

当前 Godot typed boundary 兼容面可以粗分为五层：

1. same type
2. `Variant` boundary
3. object hierarchy
4. builtin strict implicit conversion
5. keyed/index route 的额外语义

而当前 GDCC frontend 已正式闭合前三层，并额外补上了一条高频 object-family 特判：

- same type：已支持
- `Variant` boundary：已支持
- object hierarchy：已支持
- `Nil -> object`：已支持
- builtin strict implicit conversion：除 identity 外基本未支持
- keyed/index widened compatibility：未支持

因此，若后续要把 GDCC 的 typed boundary 行为推进到接近 Godot，优先级最高的缺口依次是：

1. scalar family：`bool` / `int` / `float`
2. string family：`String` / `StringName` / `NodePath`
3. geometry value family
4. `Array <-> Packed*Array`
