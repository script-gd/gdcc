# Redundant Builtin Constructor Helper Implementation Plan

> 本文档记录 GitHub issue #46 的调研结论与实施计划。目标是让
> `int(value)` 在 `value` 已经静态为 `int` 时稳定通过 C backend codegen。
> 当前方案不再新增一组 frontend intrinsic route，而是在 GDCC runtime helper
> surface 中补齐 Extension API metadata 已声明、但 `godot_builtin.h` 生成器当前跳过的
> builtin constructor helper。

## 文档状态

- 状态：实施前计划
- 更新时间：2026-06-23
- 适用范围：
  - `src/main/c/codegen/include_451/gdcc/**`
  - `src/main/c/codegen/template_451/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/test/java/gd/script/gdcc/backend/c/**`
  - `doc/module_impl/backend/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/backend/builtin_builder_implementation.md`
  - `doc/module_impl/backend/godot_binding_implementation.md`
  - `doc/module_impl/backend/implicit_conversion_implementation.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
- 相关 issue：
  - `https://github.com/script-gd/gdcc/issues/46`

---

## 1. 问题定义

最小问题形状是：

```gdscript
func keep(value: int) -> int:
    return int(value)
```

当前 pipeline 的实际链路是：

1. bare `int(...)` 被解析为 builtin type-meta constructor route；
2. `FrontendConstructorResolutionSupport.resolveBuiltinConstructor(...)` 通过 metadata 选择
   `int(from: int)`；
3. lowering 生成普通 `ConstructBuiltinInsn(result:int, [value:int])`；
4. `CBuiltinBuilder.constructRegularBuiltin(...)` 看到 metadata 中确实存在 `int(int)`，
   因此按 `godot_new_<Type>_with_<Arg>` 命名规则渲染 `godot_new_int_with_int`；
5. `src/main/c/codegen/include_451/godot/godot_builtin.h` 中没有这个符号，因为
   `GodotBuiltinGenerator.shouldGenerateConstructor(...)` 会跳过 atomic 默认构造与
   atomic-to-atomic 构造。

因此当前缺口不是 frontend 不认识 `int(int)`，而是 backend helper surface 与
`CBuiltinBuilder.hasConstructor(...)` 的 exact metadata 合同不一致：metadata 认可的
constructor 可能被渲染成一个当前 include surface 没有提供的 C symbol。

---

## 2. 推荐合同

本 issue 采用 backend helper 补齐路线：

- 保持 `int(int)` 作为普通 builtin constructor route。
- 不新增 `c_int_to_int`，也不为同类 atomic constructor 扩展一批 frontend intrinsic。
- 在 `src/main/c/codegen/include_451/gdcc/gdcc_builtin_ctor.h` 中集中提供 metadata 中已声明、
  但 `godot_builtin.h` 当前跳过的 builtin constructor helper。
- `CBuiltinBuilder.constructRegularBuiltin(...)` 继续保持 exact metadata matching：
  只要 metadata 声明了 constructor，后端渲染出的 helper symbol 就必须在 runtime include
  surface 中存在。
- 原先放在 `gdcc_helper.h` 中的 `Transform2D`、`Transform3D`、`Basis`、`Projection`
  flat-float constructor shim 也迁移到 `gdcc_builtin_ctor.h`，让 builtin constructor
  helper surface 归口到同一个头文件。

这条路线的理由：

- 缺失符号对应的 constructor 本身已经存在于 `extension_api_451.json`；
- 这些 helper 是 backend C support surface，不改变 GDScript source 语义；
- 对 atomic 类型的实现都是平凡、可审计的 C 表达式；
- 避免一次性新增大量 frontend constructor classification、lowering 分支和 intrinsic 测试；
- 保持 `ConstructBuiltinInsn` 到 `godot_new_*` 的现有模型不被拆散。

---

## 3. 缺失 helper 清单

调研脚本按 `extension_api_451.json` 与当前 `godot_builtin.h` / `gdcc_helper.h` 做差集后，
得到 14 个 metadata 中存在但 runtime helper surface 中不存在的 constructor symbol：

| helper symbol | metadata constructor | 计划实现 |
| --- | --- | --- |
| `godot_new_Nil` | `Nil()` | 返回 `godot_new_Variant_nil()` |
| `godot_new_Nil_with_Variant` | `Nil(Variant)` | 返回 nil Variant；参数只用于匹配 metadata surface |
| `godot_new_bool` | `bool()` | 返回 `false` |
| `godot_new_bool_with_bool` | `bool(bool)` | 返回原值 |
| `godot_new_bool_with_int` | `bool(int)` | 返回 `value != 0` |
| `godot_new_bool_with_float` | `bool(float)` | 返回 `value != 0.0` |
| `godot_new_int` | `int()` | 返回 `0` |
| `godot_new_int_with_int` | `int(int)` | 返回原值 |
| `godot_new_int_with_float` | `int(float)` | 返回 `(godot_int)value` |
| `godot_new_int_with_bool` | `int(bool)` | 返回 `value ? 1 : 0` |
| `godot_new_float` | `float()` | 返回 `0.0` |
| `godot_new_float_with_float` | `float(float)` | 返回原值 |
| `godot_new_float_with_int` | `float(int)` | 返回 `(godot_float)value` |
| `godot_new_float_with_bool` | `float(bool)` | 返回 `value ? 1.0 : 0.0` |

同时迁移现有 4 个 helper shim：

- `godot_new_Transform2D_with_float_float_float_float_float_float`
- `godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float`
- `godot_new_Basis_with_float_float_float_float_float_float_float_float_float`
- `godot_new_Projection_with_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float`

这 4 个 flat-float shim 不来自缺失 metadata 差集，而是 `CBuiltinBuilder.resolveHelperShimCtorArgTypes(...)`
的既有 backend fallback。迁移它们的目标是归口 builtin constructor helper，而不是改变行为。

---

## 4. 非目标

- 不新增 `c_int_to_int` intrinsic。
- 不新增一批 `bool/int/float` constructor intrinsic。
- 不扩展 `frontend_implicit_conversion_matrix.md` 的 ordinary typed boundary 矩阵。
- 不把 `int(int)` 改写成 `as` cast、direct alias 或普通 assignment route。
- 不改变 parser、AST、type-meta binding 或 constructor overload resolution。
- 不放宽 `CBuiltinBuilder.hasConstructor(...)` 的 exact metadata matching。
- 不修改 `GodotBuiltinGenerator.shouldGenerateConstructor(...)` 让 atomic constructor 进入
  `godot_builtin.h` 主生成文件；本轮把补齐 helper 放在 GDCC-owned header 中。
- 不改变现有 builtin unary `Variant` special route：
  - `int(seed: Variant)` 继续走 `UnpackVariantInsn`；
  - sema 继续发 `sema.unsafe_call_argument` warning；
  - `godot_new_int_with_Variant` 仍由 `godot_builtin.h` 提供。
- 不重构 `gdcc_helper.h` 中与对象、调用、字符串、binding、operator 相关的其他 helper。

---

## 5. 当前代码路径

### 5.1 Frontend 与 lowering

- `FrontendTopBindingAnalyzer` 将裸 `int` 发布为 `TYPE_META`。
- `FrontendExpressionSemanticSupport.resolveCallExpressionType(...)` 对 builtin type-meta call
  进入 `resolveBareTypeMetaConstructorCallExpression(...)`。
- `FrontendConstructorResolutionSupport.resolveBuiltinConstructor(...)` 通过 metadata 选择
  `int(from: int)`。
- `FrontendSequenceItemInsnLoweringProcessors.lowerConstructorCall(...)` 对普通 builtin constructor
  生成 `ConstructBuiltinInsn`。

本方案不要求 frontend 为 `int(int)` 新增 materialization fact。`int(int)` 继续沿用普通
constructor route，问题在 backend helper surface 闭合。

### 5.2 Backend constructor symbol 渲染

`CBuiltinBuilder.constructRegularBuiltin(...)` 当前行为：

- 收集实参类型；
- 调用 `hasConstructor(targetType, ctorArgTypes)` 做 exact metadata matching；
- metadata 存在时调用 `renderConstructorFunctionNameByTypes(...)`；
- 生成 `godot_new_<Type>_with_<Arg...>`；
- 少量 helper shim 只覆盖 `Transform2D`、`Transform3D`、`Basis`、`Projection` 的 flat-float
  fallback。

`hasConstructor(...)` 与 `renderConstructorFunctionNameByTypes(...)` 的组合意味着：
只要 metadata exact match 成功，runtime include surface 就应提供同名 helper。

### 5.3 生成器跳过规则

`GodotBuiltinGenerator.shouldGenerateConstructor(...)` 当前跳过：

- `Nil` 的所有 constructor；
- `bool`、`int`、`float` 的默认 constructor；
- `bool`、`int`、`float` 之间的 atomic-to-atomic constructor。

这个跳过规则本身可以保留。新增 `gdcc_builtin_ctor.h` 后，跳过的 helper 不进入
`godot_builtin.h` 主生成文件，但仍由 GDCC runtime header 提供。

---

## 6. 实施计划

### Phase A：新增 `gdcc_builtin_ctor.h`

目标：新增一个 GDCC-owned builtin constructor helper header，集中承载缺失 constructor helper
与既有 flat-float shim。

实施步骤：

1. 新增 `src/main/c/codegen/include_451/gdcc/gdcc_builtin_ctor.h`。
2. 头文件使用独立 include guard，例如 `GDCC_BUILTIN_CTOR_H`。
3. 头文件只依赖必要 include，优先包含 `<godot_binding.h>`，避免反向依赖 `gdcc_helper.h`。
4. 在该头文件中添加 14 个缺失 helper。
5. 将 `gdcc_helper.h` 中 4 个 Transform/Basis/Projection flat-float helper 原样迁移到该文件。
6. 所有 helper 使用 `static inline` 或保持当前 project header helper 的 `static` 风格；同一文件内保持一致。
7. 对 `Nil(Variant)` 这类参数未使用的 helper，用 `(void)value;` 明确避免编译告警。

验收细则：

- `gdcc_builtin_ctor.h` 可单独在 C 编译单元中 include。
- `godot_new_int_with_int` 在新头文件中存在。
- 14 个 metadata 缺失 helper 全部存在。
- 4 个 flat-float shim 全部从 `gdcc_helper.h` 迁出。
- `gdcc_helper.h` 中不再直接定义上述 4 个 flat-float shim。

### Phase B：include 接线

目标：保证现有生成出来的 module C code 不需要额外修改就能看到新 helper。

实施步骤：

1. 在 `src/main/c/codegen/include_451/gdcc/gdcc_helper.h` 中增加：

   ```c
   #include <gdcc_builtin_ctor.h>
   ```

2. include 位置放在 `<godot_binding.h>` 之后、依赖这些 constructor helper 的宏/函数之前。
3. `src/main/c/codegen/template_451/entry.h.ftl` 可保持只 include `<gdcc_helper.h>`；
   如果实现时选择显式 include `<gdcc_builtin_ctor.h>`，必须确保 include 顺序仍为
   `godot_binding.h` 在前。
4. 不修改 `godot_binding.h` 聚合策略，避免 Godot-owned/generated header 反向包含 GDCC helper。

验收细则：

- 现有 generated `entry.h` 通过 `<gdcc_helper.h>` 间接获得新 constructor helper。
- 没有形成 `gdcc_helper.h` 与 `gdcc_builtin_ctor.h` 的 include cycle。
- `gdcc_builtin_ctor.h` 不依赖 module-local class declaration。

### Phase C：同步 backend provided symbol 集合

目标：usage scanner / module-local binding collector 认识新 helper，避免把它们误判为待生成或未提供符号。

实施步骤：

1. 更新 `GodotBindingProvidedSymbols` 中的 helper symbol 列表。
2. 将原 `GDCC_HELPER_C_FUNCTION_NAMES` 重命名为更准确的 runtime helper 名称，如
   `GDCC_RUNTIME_C_FUNCTION_NAMES`，因为符号已不全来自 `gdcc_helper.h`。
3. 列表中保留：
   - `godot_new_gdcc_Object_with_Variant`
   - `godot_Variant_call`
   - 4 个迁移后的 flat-float shim
4. 列表中新增 14 个 metadata 缺失 helper。
5. 不把这些 helper 加入 `GodotBuiltinGenerator.collectSymbols(...)`，因为它们不是
   `godot_builtin.h/.c` 生成器产物。

验收细则：

- `GodotBindingProvidedSymbols.forRegistry(...)` 返回的 set 包含 `godot_new_int_with_int`。
- 返回的 set 包含 14 个新增 helper 与 4 个迁移 helper。
- usage scanner 不再把这些 helper 视为 module-local missing symbol。

### Phase D：保持 `CBuiltinBuilder` 合同并更新注释

目标：让 backend constructor route 自然使用新 helper surface，不引入新的分支。

实施步骤：

1. `CBuiltinBuilder.constructRegularBuiltin(...)` 不需要为 atomic constructor 新增特殊分支。
2. 保持 `hasConstructor(...)` exact metadata matching。
3. `resolveHelperShimCtorArgTypes(...)` 可保持现有逻辑；只更新注释说明 flat-float shim
   由 `gdcc_builtin_ctor.h` 提供。
4. 不新增 `CIntToIntIntrinsic`，不修改 `CIntrinsicManager`。
5. 若已有 `c_int_to_float` 路线仍被 frontend/lowering 使用，本轮不强制迁移；新增
   `godot_new_float_with_int` 只是补齐 constructor helper surface。

验收细则：

- `ConstructBuiltinInsn(result:int, arg:int)` codegen 可生成对 `godot_new_int_with_int` 的调用。
- C 编译阶段能从 `gdcc_builtin_ctor.h` 找到该 symbol。
- `ConstructBuiltinInsn(result:float, arg:int)` 即使经普通 constructor route 触达，也有
  `godot_new_float_with_int` helper 可用。
- 现有 `c_int_to_float` 测试不因新增 helper 被迫重写。

### Phase E：同步测试

目标：用 focused tests 锁住 helper surface、迁移和 issue #46 关键链路。

实施步骤：

1. 扩展或新增 C header 编译测试：
   - 直接 include `<gdcc_builtin_ctor.h>`；
   - 调用 14 个新增 helper 的最小表达式；
   - 调用 4 个迁移 flat-float shim；
   - 确认 header 可独立通过 C 编译。
2. 扩展 `CConstructInsnGenTest`：
   - `ConstructBuiltinInsn(result:int, arg:int)` 生成 `godot_new_int_with_int`；
   - `ConstructBuiltinInsn(result:bool, arg:int)` 生成 `godot_new_bool_with_int`；
   - `ConstructBuiltinInsn(result:float, arg:bool)` 生成 `godot_new_float_with_bool`；
   - Transform/Basis/Projection flat-float 构造仍生成原 helper 名。
3. 扩展 `GodotBindingUsageSessionTest`：
   - provided set 包含 `godot_new_int_with_int`；
   - provided set 仍包含迁移后的 Transform/Basis/Projection shim。
4. 扩展 `CProjectBuilderSharedIncludeTest`：
   - shared include/project include 中存在 `gdcc/gdcc_builtin_ctor.h`；
   - reset/copy 行为覆盖新文件。
5. 视现有测试形状扩展 `GodotAbiHeaderCompileTest`：
   - 添加 direct include `<gdcc_builtin_ctor.h>` 或通过 `<gdcc_helper.h>` 验证间接 include。

验收细则：

- issue #46 的 `int(int)` backend codegen 不再引用未声明 symbol。
- `gdcc_helper.h` 迁移后没有重复定义 flat-float shim。
- helper symbol provided set 与实际 header surface 保持一致。
- 新增测试均为 targeted，不跑全量 suite 作为日常迭代入口。

### Phase F：同步文档

目标：把 helper surface 归口规则写入长期事实源。

实施步骤：

1. 更新 `doc/module_impl/backend/godot_binding_implementation.md`：
   - 记录 `gdcc_builtin_ctor.h` 是 GDCC-owned builtin constructor helper surface；
   - 记录 `entry.h` 通过 `<gdcc_helper.h>` 间接获得该头文件；
   - 记录 `GodotBindingProvidedSymbols` 需要纳入该头文件中的 runtime-provided symbols。
2. 更新 `doc/module_impl/backend/builtin_builder_implementation.md`：
   - 说明 exact metadata constructor helper 不只来自 `godot_builtin.h`，还可能来自
     `gdcc_builtin_ctor.h`；
   - 说明 atomic constructors 由 GDCC-owned helper 补齐。
3. 本计划文档保持在 `doc/module_impl/frontend` 下，是因为 issue #46 的用户可见入口是
   frontend `int(value)`；但最终实现主战场在 backend include/helper surface。

验收细则：

- 文档中不再建议新增 `c_int_to_int` intrinsic。
- 文档中明确 `godot_new_int_with_int` 是 runtime helper surface 的合法成员。
- 文档中明确 `gdcc_helper.h` 不再直接承载 Transform/Basis/Projection flat-float constructor shim。

---

## 7. 测试命令

Linux 下优先运行 targeted wrapper：

```bash
script/run-gradle-targeted-tests.sh --tests "CConstructInsnGenTest,GodotBindingUsageSessionTest,CProjectBuilderSharedIncludeTest,GodotAbiHeaderCompileTest"
```

如新增或扩展 binding generator 快照/片段测试，再补充：

```bash
script/run-gradle-targeted-tests.sh --tests "GodotBuiltinGeneratorTest,GodotBindingGeneratedCScannerTest"
```

实现期间若某个测试失败，先把根因和判断写回计划或实现备注，再缩小到单类/单方法验证。

---

## 8. 风险与回滚点

- `Nil(Variant)` 的 helper 语义需要保持保守：计划中按 nil materialization 处理，不从参数复制非 nil
  Variant 内容。
- `int(float)` 使用 C cast，需接受其与 Godot constructor 行为的一致性假设；如果后续发现 Godot
  对特殊浮点值有不同规则，再单独补 runtime parity 测试或改为调用 Godot ptr constructor。
- `bool(float)` 使用 `value != 0.0`，需留意 `NaN` 的 C 比较行为；这与普通 truthiness 直觉一致，
  但如果 Godot 有特殊语义，需要用集成测试确认。
- 新 header 如果不加入 provided symbol set，usage scanner 可能误判 helper 未提供。
- 新 header 如果只被 `entry.h.ftl` include，而不是由 `gdcc_helper.h` 间接 include，部分直接 include
  `gdcc_helper.h` 的测试或用户代码仍可能缺 helper。
- 如果把 helper 加进 `godot_builtin.h` 生成器主输出，会扩大 generated Godot wrapper surface，并改变
  `shouldGenerateConstructor(...)` 的既有职责；本轮不采用。

回滚点：

- 若 `gdcc_builtin_ctor.h` 拆分影响 include 顺序，可临时让 `gdcc_helper.h` 继续 include 并 re-export
  新头文件，保持 `entry.h.ftl` 不变。
- 若某个 atomic helper 的 runtime 语义无法确认，可先保留 header surface 并用最小 C 表达式实现，
  后续通过 Godot integration test 校准；不要退回新增一批 frontend intrinsic。

---

## 9. 完成定义

issue #46 可视为完成，当且仅当：

- `gdcc_builtin_ctor.h` 新增并被 `gdcc_helper.h` 纳入；
- 14 个 metadata 缺失 builtin constructor helper 全部存在；
- 4 个 Transform/Basis/Projection flat-float shim 已从 `gdcc_helper.h` 迁移到
  `gdcc_builtin_ctor.h`；
- `GodotBindingProvidedSymbols` 包含新 header 提供的 runtime helper symbols；
- `ConstructBuiltinInsn(result:int, arg:int)` 生成的 `godot_new_int_with_int` 在 C 编译时可解析；
- `int(Variant)` 的现有 `UnpackVariantInsn` + unsafe warning 路线保持不变；
- 不新增 `c_int_to_int` 或其他批量 atomic intrinsic；
- targeted 测试命令通过；
- 相关 backend 文档同步记录 `gdcc_builtin_ctor.h` 的职责与 include 关系。
