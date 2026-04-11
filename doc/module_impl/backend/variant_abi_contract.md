# Variant 外部 ABI 合同

> 本文档作为 backend `Variant` outward ABI 实现的长期事实源。  
> 只保留当前代码已经落地的合同、边界、测试锚点与长期风险，不记录阶段性实施流水账。

## 文档状态

- 状态：Implemented / Maintained
- 范围：
  - `src/main/java/dev/superice/gdcc/backend/c/**`
  - `src/main/c/codegen/**`
  - `src/test/java/dev/superice/gdcc/backend/c/**`
- 更新时间：2026-04-11
- 上游对齐基线：
  - Godot 4.x 对 source-level `Variant` outward slot 的合同：
    - `type = NIL`
    - `usage |= PROPERTY_USAGE_NIL_IS_VARIANT`
- 关联文档：
  - `doc/test_error/frontend_variant_and_typed_dictionary_abi.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/module_impl/backend/backend_ownership_lifecycle_contract.md`

## 当前最终状态

### 核心实现落点

- outward metadata helper：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
    - `renderBoundMetadata(...)`
    - `renderPropertyMetadata(...)`
- `call_func` wrapper cleanup helper：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`
    - `renderCallWrapperDestroyStmt(...)`
- method binding metadata / runtime gate / wrapper cleanup：
  - `src/main/c/codegen/template_451/entry.h.ftl`
- property registration metadata：
  - `src/main/c/codegen/template_451/entry.c.ftl`
- property helper surface：
  - `src/main/c/codegen/include_451/gdcc/gdcc_bind.h`

### 当前已锁定的实现结论

- frontend ordinary boundary 不拥有 outward ABI 语义：
  - frontend 继续只负责 ordinary `Variant` pack / unpack 与兼容判定
  - GDExtension outward metadata 与 generated wrapper contract 由 backend 独占
- ordinary outward `Variant` slot 统一编码为：
  - `GDEXTENSION_VARIANT_TYPE_NIL`
  - `godot_PROPERTY_USAGE_NIL_IS_VARIANT`
- `call_func` 只对非 `Variant` 参数保留精确 runtime type gate
- `call_func` wrapper 对自己物化出来的 destroyable 非对象局部值负责显式 cleanup
- `ptrcall` 物理 ABI 形状保持不变
- typed dictionary ABI fidelity 仍是独立后续工程，不属于当前合同

## 长期合同

### 1. 语义边界合同

- source-level `Variant` 在 frontend ordinary boundary 上继续由 `PackVariantInsn` / `UnpackVariantInsn` 建模
- outward ABI 不是 frontend / LIR 的职责：
  - 不允许为了发布 `Variant` outward metadata，把 `hint` / `usage` / `class_name` 字段回灌到 frontend / LIR
  - backend helper/template 是唯一合法的 outward ABI 收口点
- ordinary boundary 正确，不代表 outward ABI 正确：
  - method argument metadata
  - method return metadata
  - property registration metadata
  - generated `call_func` runtime gate
  以上四者必须由 backend 单独保证

### 2. outward metadata 编码合同

- ordinary outward `Variant` slot 的统一编码必须保持：
  - `type = GDEXTENSION_VARIANT_TYPE_NIL`
  - `usage = base_usage | godot_PROPERTY_USAGE_NIL_IS_VARIANT`
- 该编码同时适用于：
  - method argument metadata
  - method return metadata
  - property registration metadata
- non-`Variant` outward slot 继续沿用现有 `gdExtensionType` 与 base usage 行为
- 当前 outward ABI 只自定义 `type` / `usage`：
  - `hint`
  - `hint_string`
  - `class_name`
  继续保持现有 backend 默认/owner-class 形态
- `void` 不允许进入 outward metadata helper：
  - 缺失 outward metadata 的类型必须 fail-fast
  - 不能在模板层静默拼接无意义的 enum 字面量

### 3. `call_func` runtime gate 合同

- 非 `Variant` 参数：
  - generated `call_func` wrapper 必须继续做精确 `GDExtensionVariantType` 检查
- `Variant` 参数：
  - 不能执行 `actual_type == NIL` 的精确比较
  - 必须允许任意 Godot `Variant` payload 进入 wrapper
  - 之后按 `godot_new_Variant_with_Variant(...)` / `godot_new_<Type>_with_Variant(...)` 路径在 wrapper 内复制出本地值
- `ptrcall` ABI 不参与这条 runtime gate 合同：
  - 它继续保持当前的物理 C ABI 形状
  - 不因为 ordinary `Variant` outward ABI 调整而改变

### 4. `call_func` wrapper 局部 cleanup 合同

- generated `call_func` wrapper 自己物化出的以下局部值，属于 wrapper 自己的责任边界：
  - 参数局部 `argN`
  - 非 `void` 返回路径中的本地 `godot_Variant ret`
  - 非 `void` 返回路径中的本地 `r`
- 只有 destroyable 非对象 wrapper 进入 `destroy(&slot)` 路线：
  - `String`
  - `StringName`
  - `NodePath`
  - `Callable`
  - `Signal`
  - `Variant`
  - `Array`
  - `Dictionary`
  - `Packed*Array`
  - 其他 `isDestroyable()==true` 且非 object 的 value wrapper
- object 指针和 primitive 不属于这条 cleanup 规则：
  - object 参数/返回值在 wrapper 里只是普通指针局部
  - primitive 参数/返回值没有 `destroy(&slot)` 语义
- cleanup 顺序必须保持：
  1. `godot_variant_new_copy(r_return, &ret);`
  2. `godot_Variant_destroy(&ret);`
  3. destroyable 非对象 `r` cleanup
  4. destroyable 非对象参数局部按逆序 cleanup
- 若参数局部需要 cleanup，则该局部不能继续声明为 `const`
- 这套 wrapper cleanup 与普通函数体 slot lifecycle 不同：
  - 普通函数体 slot lifecycle 由 `CBodyBuilder` 管理
  - wrapper 局部 cleanup 由模板 + `renderCallWrapperDestroyStmt(...)` 管理

### 5. helper / template 收口合同

- outward ABI 相关逻辑必须继续收口在少数固定触点：
  - `CGenHelper.renderBoundMetadata(...)`
  - `CGenHelper.renderPropertyMetadata(...)`
  - `CGenHelper.renderCallWrapperDestroyStmt(...)`
  - `gdcc_make_property_full(...)`
  - `gdcc_bind_property_full(...)`
  - `template_451/entry.h.ftl`
  - `template_451/entry.c.ftl`
- 不允许在新的 generator/template 中再次散落硬编码分支去“顺手修 `Variant` ABI”
- property registration 当前仍保留 owner-class `class_name` 槽位形态：
  - 这不是 `Variant` ABI 的问题
  - 后续若要补 object/typed-container property metadata，必须作为独立合同扩展

### 6. typed dictionary 边界合同

- typed dictionary ABI fidelity 是明确的独立后续工程
- 它可以复用当前整理好的 helper/template 触点，但必须拥有独立的：
  - metadata 规则
  - `hint` / `hint_string` / `class_name` 合同
  - 回归测试
  - 风险归因
- 不能把 typed dictionary 修复混入 ordinary `Variant` ABI 回归面，否则会模糊问题来源：
  - `Variant` metadata
  - `Variant` runtime gate
  - wrapper cleanup
  - typed dictionary metadata fidelity

## 与 Godot 的对齐点

### 1. outward `Variant` 不是“nil-only surface”

Godot 对 source-level `Variant` outward slot 的约定是：

- outward type 发布为 `NIL`
- 通过 `PROPERTY_USAGE_NIL_IS_VARIANT` 恢复真实 `Variant` surface

因此 backend 不能把 `NIL` 当成“运行时只允许 nil payload”的 gate 条件。

### 2. dynamic `call_func` 必须接受任意 `Variant` payload

对 `target.call("accept_variant", PackedInt32Array(...))` 这类路径，Godot 语义要求：

- wrapper 允许 payload 进入 native body
- 错误不应表现为 `Cannot convert argument ... to Nil`

因此 `Variant` 参数不能复用 non-`Variant` 的精确 runtime type gate。

### 3. 纯 C wrapper 没有 RAII

上游 `godot-cpp` 可以依赖 C++ RAII 处理局部 wrapper 生命周期；GDCC 这里是纯 C 模板，因此：

- `call_func` wrapper 内部 materialize 出来的 destroyable 非对象局部值
- `ret`
- destroyable 非对象 `r`

都必须显式 cleanup，不能依赖离开作用域自动析构。

## 回归测试基线

- helper-level：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CGenHelperTest.java`
    - `renderBoundMetadata(...)`
    - `renderPropertyMetadata(...)`
    - `renderCallWrapperDestroyStmt(...)`
- codegen-level：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/CCodegenTest.java`
    - method metadata
    - property metadata
    - non-`Variant` runtime gate 保留
    - `call_func` wrapper cleanup contract
- integration-level：
  - `src/test/java/dev/superice/gdcc/backend/c/build/FrontendLoweringToCProjectBuilderIntegrationTest.java`
    - `target.call(...)` 对 `Variant` 参数的可达性
    - direct method return 的 `Variant` surface
    - property get/set 的 `Variant` surface
- 调查基线：
  - `doc/test_error/frontend_variant_and_typed_dictionary_abi.md`

建议命令：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests CGenHelperTest,CCodegenTest,FrontendLoweringToCProjectBuilderIntegrationTest
```

## 长期风险与维护提醒

1. 最容易出现的半修复是“只补 `PROPERTY_USAGE_NIL_IS_VARIANT`，但忘了去掉 `call_func` 对 `NIL` 的精确 gate”。这种情况下 metadata 看起来正确，但 `target.call(...)` 仍然会在进入 native body 前失败。
2. 另一种半修复是“只改 runtime gate，不改 return/property metadata”。这样动态调用会恢复，但 direct method/property outward surface 仍偏离 Godot 语义。
3. 还有一种高风险回归是“忘记 `call_func` wrapper 自己物化出来的本地值 cleanup”。这样 outward ABI 表面正确，但动态调用路径会持续泄漏 `ret`、destroyable 参数局部值和 destroyable 返回局部值。
4. 若有人把 outward ABI 需求上移到 frontend / LIR 去承载 `hint` / `usage` / `class_name`，会显著增加 typed-container 后续工程的维护成本，并让 ordinary boundary 与 outward ABI 再次耦合。
5. typed dictionary 若再次被混入当前合同，会让 runtime 异常与 metadata fidelity 问题难以切分归因。

## 工程反思

1. ordinary boundary 与 outward ABI 是两层不同合同。前者正确，不代表后者自动正确。
2. `Variant` outward ABI 的真实收口点不是单一 metadata，也不是单一 wrapper，而是：
   - metadata type/usage
   - dynamic `call_func` runtime gate
   - wrapper 局部值 cleanup
   三者必须同时成立。
3. 这类问题更适合用结构感知的 codegen 测试锚定关键语义，而不是长期依赖整行字符串匹配。
4. 阶段计划文档不应长期充当事实源；实现稳定后应收敛为合同文档，只保留当前代码已经成立的约束与回归面。

## 非目标

- 修改 frontend ordinary boundary compatibility 规则
- 修改 `PackVariantInsn` / `UnpackVariantInsn` 语义
- 为 frontend / LIR 引入新的 outward metadata 字段
- 改动 `ptrcall` 物理 ABI 形状
- 在同一合同中顺手解决 typed dictionary metadata fidelity
