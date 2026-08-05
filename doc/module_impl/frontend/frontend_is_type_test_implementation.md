# Frontend `is` / `is not`（TypeTestExpression）实现说明

> 本文档作为 GDScript `is` / `is not` 类型测试表达式在 frontend、LIR、C backend 与 runtime helper 全链路的长期事实源，记录当前冻结的统一 `is_instance_of` 合同、类型目标发布、常量折叠、诊断边界与回归锚点。本文档替代原 `frontend_is_type_test_implementation_plan.md`，不保留分步骤实施、阶段状态、验收清单或已完成任务日志。

## 文档状态

- 状态：事实源维护中（shared semantic、统一 CFG/body lowering、`is_instance_of` codegen、runtime helpers、compile-only gate 与 `type_test` 端到端测试均已纳入当前实现）
- 更新时间：2026-08-05
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
  - 对应的 frontend、LIR、backend 与 test-suite 测试
- 关联文档：
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_lowering_plan.md`
  - `doc/module_impl/frontend/frontend_lowering_cfg_pass_implementation.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
- 明确非目标：
  - `CastExpression` / `as`、`is_instance_of()` 全局函数和 `not in` 不属于本合同
  - path-based、autoload、global-script-class 不作为本合同额外扩展的类型来源
  - nested structured container（例如 `Array[Array[int]]`）不属于支持面
  - 不增加独立 HIR pass，也不把 type test 拆成多个 LIR opcode

## 1. 当前定位与数据流

当前支持的源码形态为：

```gdscript
x is T
x is not T
```

`TypeTestExpression` 的 `value` 是普通表达式，`targetType` 是编译期类型引用，`negated` 标识 `is not`。表达式结果固定为 `bool`，`is not` 不会在 AST 中合成为普通 `UnaryExpression`。

完整数据流固定为：

```text
TypeTestExpression
  -> shared semantic: expressionTypes + typeTestTargets
  -> compile-only final gate
  -> TypeTestItem
  -> frontend body lowering: LiteralBoolInsn 或 IsInstanceOfInsn
  -> C backend: IsInstanceOfInsnGen
  -> builtin enum / object helper / typed-container helper / null check
```

shared semantic 负责解析和发布事实，compile gate 负责在进入 lowering 前封口，CFG/body lowering 只消费已经发布的事实，backend 负责根据 ordinary value 的静态类型选择 C 实现。后续阶段不得在 lowering 或 backend 侧重新承担 frontend 的 RHS 语义解析职责。

## 2. 统一 LIR 合同

所有未在 frontend 折叠的 type test 只使用一个 LIR opcode：

```text
$<result_id:bool> = is_instance_of "<type_name>" $<value_id>
```

固定约束如下：

- `GdInstruction.IS_INSTANCE_OF` 的操作数类型为 `(STRING, VARIABLE)`，返回类型为 required `bool`。
- `IsInstanceOfInsn.typeName` 是完整的编译期类型文本，可为 builtin、canonical object class 或参数化容器。
- `IsInstanceOfInsn.valueId` 保持 ordinary 静态值，不为 type test 强制 pack 成 `Variant` 或 cast 成 `Object`。
- frontend 只发射 `is_instance_of` 或已折叠的 bool 常量，不发射 `get_variant_type` 加比较、多个 intrinsic 或按目标类型族拆分的指令序列。
- `is not` 使用 `unary_op NOT` 包裹正向 `is_instance_of`，或者在常量折叠时直接取反；没有独立的 `is_not_instance_of` opcode。
- 路径选择属于 backend/codegen 合同，不改变 LIR 形状。

`doc/gdcc_low_ir.md` 是 LIR 语法和稳定表面的公共说明；本文档记录 frontend、backend 与 runtime 的具体实现合同。

## 3. RHS 解析与 published facts

### 3.1 支持的目标

shared semantic 成功解析后发布 `FrontendExpressionType.RESOLVED(GdBoolType.BOOL)`，并在 `typeTestTargets` side-table 中发布一个 `FrontendTypeTestTarget`：

- `TargetKnown(GdType)`：已解析的非参数化 builtin、`Variant`、Engine/gdcc Object 类或单层参数化 `Array[T]` / `Dictionary[K,V]`。
- `TargetUnresolvedObject(name)`：RHS 是合法 Godot 标识符，但 `ScopeTypeResolver` 未找到对应类型。`name` 保留源码标识符文本，供 downstream 运行时路径使用。

`negated` 只保留在 AST 中，影响 lowering 时的常量取反或 `NOT` 包装，不改变表达式类型和 target side-table 的内容。

### 3.2 拒绝与降级边界

- 空类型文本、`null` 作为类型、非法标识符和不支持的 nested structured container 产生 `FAILED`，不发布 target fact。
- builtin、裸 `Array` / `Dictionary` 和参数化容器解析失败时产生错误，不得降级为 `TargetUnresolvedObject`。
- 只有合法标识符的未知 Object 名称允许降级为 `TargetUnresolvedObject`。
- `TypeTestExpression` 不属于 expression typing 的 deferred 集合；类型稳定时必须发布 `RESOLVED(bool)` 与 target fact。
- published target 经过 `FrontendPublishedFactTypeGuard` 检查，`TargetKnown` 不得携带 compiler-only 类型。

### 3.3 目标类型语义

- 非参数化 builtin、packed 类型、裸 `Array` / `Dictionary` 使用 `GDExtensionVariantType` 精确匹配；`int` 与 `float` 不互通。
- `Variant` 是顶类型；任意操作数（包括 `null`）为 `true`，`is not Variant` 为 `false`。
- `Array[T]` / `Dictionary[K,V]` 要求 runtime typed metadata 完全匹配；不做元素协变。静态类型为裸 `Array` / `Dictionary` 的操作数不能据此判定为“非参数化容器”——裸槽位在运行时仍可能持有带 typed metadata 的值。
- 已解析 Engine/gdcc Object 类使用 Object 继承关系；已知同类型/upcast 的 false 路径只有 null。
- 未解析但合法的 Object 名称产生 lint warning，进入运行时 Object 路径，不使用前端静态折叠。
- script 类的编译期可证明同类型/upcast 路径使用 null-check；运行时 ClassDB 路径对 script 实例当前返回 `false`。

### 3.4 Godot 对齐的关键边界

- `null is T` 对任意非 `Variant` 目标为 `false`。
- `null is Variant` 为 `true`。
- `1 is int` 为 `true`，`1.0 is int` 为 `false`。
- 子类 `is` 父类为 `true`；父类 `is` 子类不能仅凭静态类型折叠为 `false`。
- `is not` 等价于 `not (x is T)`。
- 硬类型确定不兼容的组合可以发出 warning 并在 lowering 中折叠为 `false`；Variant 操作数、Nil 操作数、Variant 目标和 unresolved Object 目标不得据此发出错误的静态不兼容诊断。

script 类运行时 `is` 当前不是完整支持面：native Object 的 ClassDB 名称不携带 gdscript script 继承链，参数化容器的 script-leaf metadata 也由 runtime helper 拒绝。该差异不通过编译期 warning 伪装解决；若未来支持，需要独立的 script inheritance/runtime metadata 合同。

## 4. CFG 与 frontend body lowering

`FrontendCfgGraphBuilder.buildTypeTestValue` 先构建 value operand，再发布一个 `TypeTestItem`。`TypeTestItem` 只携带 AST anchor、operand value id 和 bool result value id，不重新解析 RHS。

`FrontendTypeTestInsnLoweringProcessor` 的行为固定为：

- `TargetUnresolvedObject`：保留原始 `typeName`，不按已知 target 静态折叠，生成 `IsInstanceOfInsn`；`is not` 追加 `UnaryOpInsn(NOT)`。
- `TargetKnown`：调用共享 `TypeTestFoldUtil.fold`。结果为 `TRUE` / `FALSE` 时生成 `LiteralBoolInsn`，并在 `negated` 情况下取反；`RUNTIME_OPEN` 时生成统一 `IsInstanceOfInsn`。
- `Variant` 目标的折叠守卫位于 Nil 和 Variant 操作数判断之前，任何操作数都直接折叠为 `true`。
- Object 同类型/upcast 不折叠为字面量 `true`，因为 Object 值可能为 null；保留 `IsInstanceOfInsn`，由 backend 生成 null-check。
- Variant 操数测试非 Variant 目标保持 runtime-open。
- 已知 Nil 对非 Variant 目标折叠为 `false`。
- 精确 non-object 类型相同折叠为 `true`，明确不相交的类型族折叠为 `false`。
- 裸 `Array` / `Dictionary` 目标接受同族 typed 或 bare value，可折叠为 `true`。
- 反向的裸容器 *value* 测试参数化目标（`Array is Array[T]` / `Dictionary is Dictionary[K,V]`）保持 **runtime-open**：裸槽位可能在运行时携带 typed metadata，不得折叠为常量 `false`（也不得折叠为 `true`）；backend 走 typed-metadata helper。
- 已参数化但元素类型不相等的组合（如 `Array[String] is Array[int]`）仍可折叠为 `false`（静态元素约束下不可能匹配）。
- parent-to-child Object 测试、Variant 操数测试非 Variant 目标和其它无法证明的 Object 关系保持运行时路径。

`FrontendCompileCheckAnalyzer` 已不再为 `TypeTestExpression` 建立显式 blocker；TypeTest 进入默认 compile surface 递归路径。其它尚未闭合的 expression surface 仍由 compile-only final gate 在 lowering 前封口。

## 5. Backend 分派与折叠

静态折叠决策统一由 `TypeTestFoldUtil.fold` 给出 `TypeTestFoldResult`（`TRUE` / `FALSE` / `RUNTIME_OPEN`）。frontend body lowering 与 `IsInstanceOfInsnGen` 都消费同一决策树；backend 对仍然存在的 `is_instance_of` 再做一层保险折叠和路径选择。手写或未来直接生成的 LIR 也必须遵守同一合同。

- 任意 value / `Variant` target：`TRUE`；不进入 runtime dispatch，不生成 `is_instance_of "Variant"` 的稳定 LIR。
- non-object exact same type：`TRUE`。
- Nil / 非 Variant target：`FALSE`。
- Object same type 或已证明 upcast：`RUNTIME_OPEN`，生成 `!(object_is_null(...))`；不直接生成字面量 `true`。
- Object parent -> child 或其它 runtime-open Object 关系：`RUNTIME_OPEN`，使用 `gdcc_is_instance_of_object_raw_and_id` 或 `gdcc_is_instance_of_object_variant`。
- unresolved Object target：进入 Object runtime route；Object/Variant value 使用继承链 helper，明确的 non-object value 生成 `false`，不使用已知静态 target 折叠规则。
- 非参数化 builtin / packed / 裸容器 *目标*：比较 `godot_variant_get_type(...)` 与 `GDExtensionVariantType` 枚举。
- 参数化 Array / Dictionary 目标：使用 typed metadata helper。静态 value 为裸 `Array` / `Dictionary` 时同样走该路径（`gdcc_is_instance_of_typed_array` / `gdcc_is_instance_of_typed_dictionary`）；`TypeTestFoldUtil` 必须返回 `RUNTIME_OPEN`，不得按静态类型折叠为 `FALSE`。
- 其它不支持组合：fail-closed，抛出明确的 codegen invalid-instruction 错误，禁止静默返回 `false`。

`GdVariantType.getGdExtensionType()` 的值为 `NIL`，因此 `Variant` target 绝不能进入 builtin enum 路径或 ClassDB 路径。`TypeTestFoldUtil` 的顶类型守卫位于 Nil / Variant-operand 判断之前，防止稳定 LIR 以外的手写/遗留 LIR 触发错误路径。

## 6. Runtime helper 合同

runtime helper 的完整 ABI 说明以 `doc/gdcc_runtime_lib.md` 和 `gdcc_helper.h` 为准。type-test 只依赖以下语义：

- Object helper 对 null 或 freed object 返回 `false`；live object 按自身 class name 和 ClassDB inheritance 判断。
- Object ordinary value 使用 `gdcc_is_instance_of_object_raw_and_id`，Variant value 使用 `gdcc_is_instance_of_object_variant`。
- typed Array 使用 `gdcc_is_instance_of_typed_array` 或其 Variant 入口；typed Dictionary 使用对应 dictionary helper。
- typed container helper 同时检查 builtin leaf 和 class-name metadata；script-leaf metadata 当前不匹配。
- 非参数化 builtin 可以在 codegen 中内联 `godot_variant_get_type` 比较，不要求额外的 LIR helper。
- 不得复用 `gdcc_check_variant_type_object` 作为 type-test helper；该 helper 的 unpack 语义允许 null→true，与 `is` 的 null→false 不同。

## 7. Diagnostic 与 compile-only 边界

诊断 owner 固定在 shared semantic publisher 和 compile-only gate 的职责边界内：

- RHS 为空、非法、`null` 或 nested structured container：`sema.expression_resolution` / error；不发布 target fact。
- builtin 或容器目标无法解析：`sema.expression_resolution` / error；不允许 Object 降级。
- 合法未知 Object 标识符：`sema.type_test_unresolved_object` / lint warning；消息为 `type name '<name>' not found in scope, will be checked at runtime`，不阻塞编译。
- hard-typed value 与已知 target 确定不兼容：`sema.type_check` / warning；使用 Godot 风格消息，跳过 Variant、Nil、unresolved Object 和 Variant target。
- codegen 不支持的组合：codegen invalid instruction / error；fail-closed，不静默返回 `false`。

`sema.compile_check` 不再负责 TypeTest blocker。该 category 仍覆盖其它未接通 lowering 的 expression surface、声明边界和缺失 published facts，并且只属于 `analyzeForCompile(...)`，不污染 shared analyze、inspection 或未来 LSP 入口。

## 8. 核心实现落点

- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
  - 解析 value 和 RHS，返回 `RESOLVED(bool)`、`TargetKnown` 或 `TargetUnresolvedObject`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendTypeTestTarget.java`
  - 定义 target side-table 的 sealed target 形态和幂等合并规则
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendAnalysisData.java`
  - 保存并合并 `typeTestTargets`
- `src/main/java/gd/script/gdcc/frontend/sema/FrontendPublishedFactTypeGuard.java`
  - 校验 target fact 不泄漏 compiler-only 类型
- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilder.java`
  - 构建 `TypeTestItem`
- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/item/TypeTestItem.java`
  - 固定 CFG value-op 形状
- `src/main/java/gd/script/gdcc/util/TypeTestFoldResult.java`
  - 共享折叠结果：`TRUE` / `FALSE` / `RUNTIME_OPEN`
- `src/main/java/gd/script/gdcc/util/TypeTestFoldUtil.java`
  - frontend 与 backend 共用的静态 `is` 折叠决策树
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`
  - 统一 lowering、消费 `TypeTestFoldUtil` 和 `is not` 的 NOT 包装
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzer.java`
  - 保证 TypeTest 不再被显式 compile blocker 拦截
- `src/main/java/gd/script/gdcc/lir/insn/IsInstanceOfInsn.java`
  - 统一 LIR 指令和 `typeName` / `valueId` 命名
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/IsInstanceOfInsnGen.java`
  - 二次消费 `TypeTestFoldUtil`、路径分派和 fail-closed 校验
- `src/main/java/gd/script/gdcc/backend/c/gen/CCodegen.java`
  - 注册 `IsInstanceOfInsnGen`
- `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
  - Object、typed Array 和 typed Dictionary runtime helpers

## 9. 回归锚点

涉及本文档合同的改动至少应保持以下测试覆盖：

- `src/test/java/gd/script/gdcc/lir/insn/IsInstanceOfInsnContractTest.java`
  - 单一 opcode、操作数结构、序列化/解析 round-trip，以及不存在 `is not` 或类型族分叉 opcode
- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupportTest.java`
  - known/unresolved target、非法 RHS、target publication、依赖传播、诊断和 hard-typed warning
- `src/test/java/gd/script/gdcc/frontend/sema/FrontendAnalysisDataTest.java`
  - `typeTestTargets` 的幂等合并和冲突保护
- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`
  - TypeTest 不产生显式 compile blocker，其它 compile surface 仍被正确封口
- `src/test/java/gd/script/gdcc/util/TypeTestFoldUtilTest.java`
  - 共享折叠决策树的直接合同（含裸容器 vs 参数化目标的 `RUNTIME_OPEN`）
- `src/test/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendTypeTestInsnLoweringTest.java`
  - builtin、Object、Variant、Nil、typed container、unresolved target、`is not` 和 frontend 折叠矩阵
- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`
  - type test 与统一 body pass 中其它表达式的组合，以及完整 `is not` lowering
- `src/test/java/gd/script/gdcc/backend/c/gen/IsInstanceOfInsnGenTest.java`
  - builtin enum、Object helper、Object null-check、typed container、unresolved target、Variant target、NOT 组合和 fail-closed 路径
- `src/test/java/gd/script/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java`
  - `type_test/` test-suite 资源的编译与运行注册
- `src/test/test_suite/unit_test/script/type_test/` 与 `src/test/test_suite/unit_test/validation/type_test/`
  - builtin、object、container、packed、variant 和 `is_not` 的端到端 script/validation 对

针对性测试使用仓库约定的 PowerShell 脚本，例如：

```text
pwsh -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests TypeTestFoldUtilTest,IsInstanceOfInsnContractTest,FrontendExpressionSemanticSupportTest,FrontendTypeTestInsnLoweringTest,IsInstanceOfInsnGenTest
```

## 10. 长期维护约束

- 任何新增 type-test 目标族都必须先定义 `TargetKnown` 的解析事实、frontend 折叠边界、backend 分派和正反测试；不能只在 backend 增加字符串分支。
- 不能把 runtime-open 的 value 或 unresolved target 误折叠为 `true`，也不能用 silent `false` 掩盖不支持的组合。
- Object 同类型/upcast 的 null-check 不是普通常量折叠优化，任何重构都必须保留 null/freed 语义。
- `Variant` target 的顶类型守卫必须优先于 Nil、Variant operand 和 builtin enum 路径判断；禁止生成稳定的 `is_instance_of "Variant"`。
- helper 的 null 语义和 unpack helper 的 null 语义不同；新增复用必须同时审查 ownership、freed instance 和 typed metadata 行为。
- RHS 的 canonical/facing name 必须与 `ScopeTypeResolver`、declared type 和 Godot-facing class-name 合同一致；不能在 lowering 侧拼接 source-only alias。
- 若 type-test 合同变化，必须同步本文档、`gdcc_low_ir.md`、`gdcc_runtime_lib.md`、frontend compile/deferred/gate 文档和对应 targeted/test-suite 测试。

## 11. 工程反思

type test 的稳定抽象是“一个带有编译期类型文本的 LIR predicate”，而不是 builtin、Object、container 各自拥有一条指令。frontend 只发布类型事实并保持 ordinary value，backend 才根据静态类型和目标形态选择 enum、继承链、typed metadata 或 null-check 路径。这样既避免 LIR 形状被 C backend 细节污染，也让 `Variant` 顶类型和 runtime-open 组合可以在统一 predicate 合同下显式处理。
