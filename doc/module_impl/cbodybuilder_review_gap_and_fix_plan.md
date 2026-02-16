# CBodyBuilder / C 后端生成器实现审阅结果与修复方案

## 1. 审阅范围

- 语义基线文档：
  - `doc/gdcc_c_backend.md`
  - `doc/module_impl/cbodybuilder_implementation_guide.md`
  - `doc/module_impl/cbodybuilder_migration_execution_checklist.md`
- 代码范围：
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/*`
  - `src/main/c/codegen/insn/*`
- 测试范围：
  - `src/test/java/dev/superice/gdcc/backend/c/gen/*`

---

## 2. 当前发现的问题（按严重度）

## P0（高优先级）

- `__prepare__` 错误地初始化了 `ref` 变量。
  - 现状：`generateFunctionPrepareBlock()` 只跳过参数变量，没有跳过 `variable.ref() == true` 的变量。
  - 影响：与文档“`__prepare__` 仅对非 ref 变量做首次初始化”语义不一致，可能误写引用变量。
  - 证据：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java:140`。
  - 对比：`ensureFunctionFinallyBlock()` 已有 `ref` 变量跳过逻辑，见 `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java:185`。

- `PackUnpackVariantInsnGen` 仍使用旧式字符串拼接路径，绕过 `CBodyBuilder` 赋值语义。
  - 现状：`generateCCode(...)` 直接拼接 `$var = ...`。
  - 影响：不会走 builder 的统一语义（旧值 destroy、对象 own/release、GDCC/Godot 指针转换）。覆盖写入 destroyable/object 变量时存在生命周期风险。
  - 证据：
    - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PackUnpackVariantInsnGen.java:26`
    - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PackUnpackVariantInsnGen.java:53`
    - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PackUnpackVariantInsnGen.java:68`

## P1（中优先级）

- `CALL_GLOBAL` 路径处于不可用/半成品状态。
  - 现状：
    - `CallGlobalInsnGen` 未注册到 `CCodegen`。
    - 依赖的模板 `insn/call_global.ftl` 缺失。
    - 现有校验分支逻辑对 void 返回存在不一致（void + 无 result 的合法分支会落入后续必填 result 检查）。
  - 影响：遇到 `CALL_GLOBAL` opcode 时会运行时失败或不符合预期。
  - 证据：
    - 注册缺失：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java:36`
    - 模板路径：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallGlobalInsnGen.java:20`
    - 模板文件缺失：`src/main/c/codegen/insn` 下不存在 `call_global.ftl`
    - 校验分支：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallGlobalInsnGen.java:42` 与 `src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallGlobalInsnGen.java:48`

- `ReturnInsn("_return_val")` 在非 `__finally__` 块被硬错误拦截。
  - 现状：`ControlFlowInsnGen` 对哨兵值直接调用 `returnTerminal()`，而 `returnTerminal()` 要求当前块必须是 `__finally__`。
  - 影响：与迁移清单中“非 finally 下路径可闭合（跳转 finally）”建议不一致。
  - 证据：
    - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/ControlFlowInsnGen.java:62`
    - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/ControlFlowInsnGen.java:63`
    - `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java:399`

## P2（中低优先级）

- 自动生成 `__finally__` 会清空已有块内容，且无重复析构去重策略。
  - 现状：若 IR 已有手工 finally 指令，`ensureFunctionFinallyBlock()` 会直接清空并重建。
  - 影响：手工逻辑可能丢失；若显式 `destruct` 与自动 destruct 重叠，可能重复析构。
  - 证据：
    - `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java:175`
    - `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java:191`

- 已迁移模板仍保留，存在误用风险。
  - 现状：`control_flow/new_data/own_release_object/destruct` 四个模板仍在。
  - 特别问题：`destruct.ftl` 本身存在明显错误写法（`<#switch gen>` + `own_object` 分支语义异常）。
  - 证据：
    - `src/main/c/codegen/insn/destruct.ftl:7`
    - `src/main/c/codegen/insn/destruct.ftl:9`
    - `src/main/c/codegen/insn/destruct.ftl:12`

---

## 3. 单元测试未覆盖点（最小增量测试）

以下是“最小增量”而非全面重写测试：只补关键缺口，优先覆盖 P0/P1 风险。

## 3.1 P0 必补

- 缺口 A：`__prepare__` 不应初始化 `ref` 变量。
  - 建议新增测试类/方法（可并入 `CPhaseAControlFlowAndFinallyTest`）：
    - `prepareShouldSkipRefVariables()`
  - 最小断言：
    - 构造函数含一个 `ref String` 局部变量。
    - 调用 `codegen.generate()` 后断言 `__prepare__` 不包含该变量对应的 `literal_string/construct_*` 初始化指令。

- 缺口 B：`PackUnpackVariantInsnGen` 的生命周期语义未覆盖。
  - 建议新增测试类：`CPackUnpackVariantInsnGenTest`
  - 最小测试用例：
    - `unpackVariantToStringShouldUseAssignmentSemantics()`：目标为 `String` 非 ref，断言包含旧值 destroy + 新值赋值。
    - `unpackVariantToRefCountedObjectShouldReleaseAndOwn()`：目标为 `RefCounted`，断言 release/own 路径。
    - `packVariantFromGdccObjectShouldUseObjectPackPath()`：GDCC 对象打包时断言使用 GDCC object 对应 pack 路径。

## 3.2 P1 建议补

- 缺口 C：`ControlFlowInsnGen` 的 `go_if/goto` 负路径覆盖不足。
  - 建议新增方法（可并入 `CPhaseAControlFlowAndFinallyTest`）：
    - `goIfWithMissingConditionVarShouldThrow()`
    - `goIfWithNonBoolConditionShouldThrow()`
    - `gotoWithMissingTargetBlockShouldThrow()`

- 缺口 D：显式 `destruct` 与自动 finally destruct 的重叠场景未覆盖。
  - 建议新增方法：
    - `explicitDestructAndAutoFinallyDestructShouldNotDoubleDestruct()`
  - 说明：该测试可以先标注当前行为（若当前会重复），再在修复去重策略后更新期望。

- 缺口 E：`CALL_GLOBAL` 行为无测试（当前不可用）。
  - 建议在修复策略明确后补：
    - 若短期禁用：增加“遇到 CALL_GLOBAL 给出清晰错误信息”的测试。
    - 若启用实现：增加返回值/void/参数校验三类最小用例。

## 3.3 低成本回归守护测试

- 建议新增一个“opcode 注册覆盖”测试（例如 `CCodegenInsnRegistryTest`）：
  - 校验所有已实现且应启用的 `CInsnGen` opcode 已注册。
  - 对允许未实现 opcode 建立白名单，避免 silent regression。

---

## 4. 分步骤解决方案（可执行）

## 阶段 0：先冻结基线（不改实现）

- [ ] 记录当前失败/风险项与现有测试状态。
- [ ] 先补“最小增量测试”中的 P0 用例（期望先暴露问题）。

建议命令：

- `./gradlew test --tests "CPhaseAControlFlowAndFinallyTest" --no-daemon --info --console=plain`
- `./gradlew test --tests "CPackUnpackVariantInsnGenTest" --no-daemon --info --console=plain`

## 阶段 1：修复 `__prepare__` 跳过 ref 变量

目标文件：
- `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`

步骤：
- [ ] 在 `generateFunctionPrepareBlock()` 变量扫描中加入 `if (variable.ref()) continue;`。
- [ ] 保持参数跳过逻辑不变。
- [ ] 回归确认 `__finally__` 行为不受影响。

验收：
- [ ] `prepareShouldSkipRefVariables()` 通过。

## 阶段 2：迁移 `PackUnpackVariantInsnGen` 到 `CBodyBuilder`

目标文件：
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PackUnpackVariantInsnGen.java`

步骤：
- [ ] 重写为 `generateCCode(CBodyBuilder bodyBuilder)` 路径。
- [ ] 保留并迁移所有变量存在性/类型校验，统一通过 `bodyBuilder.invalidInsn(...)` 报错。
- [ ] `pack_variant`：使用 `callAssign(targetOfVar(result), packFunc, GdVariantType.VARIANT, ...)`。
- [ ] `unpack_variant`：使用 `callAssign(targetOfVar(result), unpackFunc, resultType, ...)`。
- [ ] 避免直接拼接 `$var = ...`，全部走 builder API。

验收：
- [ ] `CPackUnpackVariantInsnGenTest` 中三个最小用例通过。
- [ ] 现有 `CLoadPropertyInsnGenTest/CStorePropertyInsnGenTest` 不回归。

## 阶段 3：明确 `ReturnInsn("_return_val")` 非 finally 语义

目标文件：
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/ControlFlowInsnGen.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`

步骤（两选一，需团队先定策略）：
- [ ] 方案 A（推荐闭合路径）：
  - 非 finally 遇到 `_return_val` -> 生成 `goto __finally__;`。
  - finally 遇到 `_return_val` -> 生成 `return _return_val;`。
- [ ] 方案 B（保持严格）：
  - 非 finally 遇到 `_return_val` 继续报错，但补文档与测试明确该约束。

验收：
- [ ] 对应策略测试全部通过。

## 阶段 4：处理 `CALL_GLOBAL` 技术债

目标文件：
- `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallGlobalInsnGen.java`
- （若启用模板）`src/main/c/codegen/insn/call_global.ftl`

步骤：
- [ ] 先定策略：禁用并显式报错，或补齐并启用。
- [ ] 若禁用：删除未使用实现或添加清晰异常提示，避免半成品悬挂。
- [ ] 若启用：补模板/迁移到 builder，并修复 void/result 校验分支。

验收：
- [ ] 对应测试覆盖通过。

## 阶段 5：处理 finally 重建与重复析构风险

目标文件：
- `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CPhaseAControlFlowAndFinallyTest.java`

步骤：
- [ ] 明确策略：
  - 是否允许保留手工 finally 指令；
  - 是否需要自动 destruct 去重（按变量 ID）。
- [ ] 若启用去重：维护一个已销毁变量集合，避免插入重复 `DestructInsn`。
- [ ] 增加显式 destruct + 自动 finally destruct 的回归测试。

验收：
- [ ] 重复析构风险测试通过。

## 阶段 6：退役过时模板 + 文档收敛

目标文件：
- `src/main/c/codegen/insn/control_flow.ftl`
- `src/main/c/codegen/insn/new_data.ftl`
- `src/main/c/codegen/insn/own_release_object.ftl`
- `src/main/c/codegen/insn/destruct.ftl`
- `doc/module_impl/cbodybuilder_migration_execution_checklist.md`（必要时）

步骤：
- [ ] 检索确认无运行时引用后删除。
- [ ] 文档更新为 builder 现状。

验收：
- [ ] 无遗留模板依赖。
- [ ] 相关测试全部通过。

---

## 5. 建议测试执行顺序

- `./gradlew test --tests "CPhaseAControlFlowAndFinallyTest" --no-daemon --info --console=plain`
- `./gradlew test --tests "CPackUnpackVariantInsnGenTest" --no-daemon --info --console=plain`
- `./gradlew test --tests "CDestructInsnGenTest" --no-daemon --info --console=plain`
- `./gradlew test --tests "COwnReleaseObjectInsnGenTest" --no-daemon --info --console=plain`
- `./gradlew test --tests "CNewDataInsnGenTest" --no-daemon --info --console=plain`
- `./gradlew test --tests "CLoadPropertyInsnGenTest" --no-daemon --info --console=plain`
- `./gradlew test --tests "CStorePropertyInsnGenTest" --no-daemon --info --console=plain`
- `./gradlew classes --no-daemon --info --console=plain`

---

## 6. 完成标准（DoD）

- [ ] `__prepare__` 不再初始化 `ref` 变量。
- [ ] `PackUnpackVariantInsnGen` 完成 builder 化并通过新增最小测试。
- [ ] `_return_val` 哨兵行为与团队策略和文档一致。
- [ ] `CALL_GLOBAL` 不再处于不可用半成品状态（明确禁用或完整实现）。
- [ ] 显式 destruct 与自动 finally destruct 策略明确且有测试守护。
- [ ] 已迁移模板退役完成且无残留引用。
