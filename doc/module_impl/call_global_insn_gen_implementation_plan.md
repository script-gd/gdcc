# CallGlobalInsnGen（仅 Utility Function）实施方案

## 1. 目标与范围

本方案用于落地 `CALL_GLOBAL` 指令的 C 后端生成，第一阶段仅支持调用 GDExtension 的 **utility functions**（来自 `ClassRegistry`）。

本次范围包含：
- `call_global "<function_name>" ...` 到 `godot_<function_name>(...)` 的生成。
- 参数数量/类型校验。
- 返回值数量/类型校验。
- vararg utility 的 `argv/argc` 形态生成。
- 失败时抛出带函数/块/指令位置信息的 `InvalidInsnException`。

本次范围不包含：
- `call_global` 调用非 utility 全局符号。
- 自动为非 `Variant` 参数做隐式 `pack_variant`。
- 修改构建脚本或 Gradle 配置。

---

## 2. 调研结论（已确认）

### 2.1 当前代码状态

- `CallGlobalInsnGen` 当前仍是 `TemplateInsnGen` 形态，且逻辑未完成（存在 `// TODO`）。
- `src/main/c/codegen/insn/call_global.ftl` 不存在。
- `CCodegen` 尚未注册 `CallGlobalInsnGen`，因此 `CALL_GLOBAL` 目前不会被处理。

### 2.2 命名约定来源

根据 `doc/gdextension-lite.md`：
- Utility 函数 C 符号命名为：`godot_<function_name>`。
- Variadic 方法/utility 采用 `argv/argc` 约定。

### 2.3 Utility 元数据与签名来源

- utility 元数据来自 `src/main/resources/extension_api_451.json` 的 `utility_functions`。
- `ClassRegistry.findUtilityFunctionSignature(name)` 使用 **无前缀名**（如 `print`、`deg_to_rad`）检索。
- `return_type` 缺失时在当前实现中会解析为 `null`，语义等价于无返回值（void）。

### 2.4 vararg 真实 C 声明形态

`tmp/test/c_build/include/gdextension-lite/generated/utility_functions.h` 已确认 vararg utility 声明形态，例如：

```c
void godot_print(const godot_Variant *arg1, const godot_Variant **argv, godot_int argc);
```

同类函数包括 `print*`、`push_*`、`str`、`max`、`min`。

---

## 3. 关键遗漏与约束（需在实现中显式处理）

1. **指令接入遗漏**：`CALL_GLOBAL` 未注册到 `CCodegen`。
2. **模板缺失**：当前模板路径不可用，应直接改为 `CBodyBuilder` 路径。
3. **utility 名称前缀差异**：IR 常用无前缀名（`print`），但 C 侧需要 `godot_print`。
4. **vararg ABI 细节**：需要显式构造 `const godot_Variant* argv[]` 与 `argc`。
5. **类型严格性**：本阶段不做隐式 pack，vararg 额外参数必须是 `Variant`（否则报错）。
6. **返回值规则**：void/null return 与 non-void return 的 resultId 约束必须严格校验。

---

## 4. 总体设计

## 4.1 生成器形态

将 `CallGlobalInsnGen` 从 `TemplateInsnGen<CallGlobalInsn>` 改为 `CInsnGen<CallGlobalInsn>`，实现：

- `getInsnOpcodes()` 返回 `CALL_GLOBAL`。
- `generateCCode(CBodyBuilder bodyBuilder)` 走 builder 统一语义（赋值、生命周期、错误定位）。

## 4.2 名称解析策略

定义内部解析流程（建议记录结构体/record）：

- 输入：IR 中 `instruction.functionName()`。
- 输出：
  - `lookupName`：用于 `ClassRegistry.findUtilityFunctionSignature` 的名称（优先无前缀）。
  - `cFunctionName`：最终 C 调用符号（`godot_` 前缀）。
  - `signature`：`FunctionSignature`。

建议规则：
1. 先按原名查签名。
2. 若失败且原名以 `godot_` 开头，则剥离前缀后再查。
3. 若仍失败，抛 `InvalidInsnException`（未找到 utility function）。
4. `cFunctionName` 统一输出为：
   - 原名已是 `godot_` 开头：直接用原名；
   - 否则：拼接 `godot_` + 原名。

---

## 5. 校验规则（实现核心）

## 5.1 参数存在性与数量校验

设：
- `provided = instruction.args().size()`
- `fixed = signature.parameterCount()`
- `isVararg = signature.isVararg()`

规则：
- 先校验每个参数变量 ID 在 `func` 中存在，不存在立即报错。
- 非 vararg：
  - 默认要求 `provided == fixed`。
  - 若未来 utility 元数据出现 default 值，可放宽为“缺失参数必须均有默认值”。
- vararg：
  - 要求 `provided >= fixed`。

## 5.2 参数类型校验

- 前 `fixed` 个参数：逐个用 `ClassRegistry.checkAssignable(argType, paramType)` 校验。
- vararg 额外参数（`i >= fixed`）：
  - 本阶段强制要求可赋给 `Variant`，即 `checkAssignable(argType, GdVariantType.VARIANT)` 为真。
  - 实际上当前实现下通常等价“必须就是 `Variant`”；非 `Variant` 需在上游先 `pack_variant`。

## 5.3 返回值数量与类型校验

设 `retType = signature.returnType()`：

- `retType == null` 或 `retType instanceof GdVoidType`：
  - `instruction.resultId()` 必须为 `null`，否则报错。
- 其他（non-void）：
  - `instruction.resultId()` 必须非空，否则报错。
  - result 变量必须存在。
  - result 变量必须可写（非 `ref`）。
  - `checkAssignable(retType, resultVar.type())` 必须为真。

---

## 6. 代码生成规则

## 6.1 非 vararg utility

- 构造参数列表：`List<ValueRef>`，每个参数使用 `bodyBuilder.valueOfVar(argVar)`。
- void：`bodyBuilder.callVoid(cFunctionName, args)`。
- non-void：`bodyBuilder.callAssign(target, cFunctionName, retType, args)`。

## 6.2 vararg utility

### 6.2.1 调用参数组织

utility 函数 C 形参为：
- 固定参数（来自签名）
- `argv`（`const godot_Variant**`）
- `argc`（`godot_int`）

对应生成：
1. 固定参数照常构建为 `ValueRef`。
2. 额外参数数量 `extraCount = provided - fixed`。
3. `extraCount == 0`：
   - `argvExpr = "NULL"`
   - `argcExpr = "0"`
4. `extraCount > 0`：
   - 先声明：
     ```c
     const godot_Variant *<tmp>[] = { <extra1_ptr>, <extra2_ptr>, ... };
     ```
   - 其中 `<extra_ptr>` 规则：
     - 变量是 ref：`$id`
     - 非 ref：`&$id`
   - `argvExpr = <tmp>`
   - `argcExpr = <extraCount>`

### 6.2.2 发射调用

将最终参数拼成：`fixedArgs + argv + argc`。

- void：`callVoid(cFunctionName, finalArgs)`。
- non-void：`callAssign(target, cFunctionName, retType, finalArgs)`。

> 说明：这里仍建议走 `CBodyBuilder.callAssign`，避免绕过统一赋值语义（旧值释放/析构、对象 own/release 等）。

## 6.3 CBodyBuilder 需要配合的增强（提升健壮性）

为保证 `CallGlobalInsnGen` 实现稳定，建议同步补齐以下 `CBodyBuilder` 能力：

1. **统一 utility 名称解析与签名查找**
   - 问题：现有 `validateCallArgs/resolveReturnType` 对 `godot_` 前缀处理不一致，容易出现“有符号但查不到签名”。
   - 建议：新增内部解析入口（如 `resolveUtilityCall`），统一产出：
     - `lookupName`（registry 查询名）
     - `cFunctionName`（最终 C 符号）
     - `signature`
   - 并让 `validateCallArgs`、`resolveReturnType`、`call*` 共用该入口。

2. **提供 utility 专用调用 API（避免在生成器中拼接细节）**
   - 建议新增：
     - `callUtilityVoid(String funcName, List<ValueRef> args)`
     - `callUtilityAssign(TargetRef target, String funcName, List<ValueRef> args)`
   - 由 `CBodyBuilder` 内部完成：
     - 参数数量/类型校验
     - vararg 组包
     - 调用发射
   - 这样 `CallGlobalInsnGen` 仅保留 IR 变量解析与错误上下文，降低重复逻辑和漂移风险。

3. **内建 vararg argv/argc 组包能力**
   - 建议在 builder 内实现专用渲染流程（如 `renderUtilityArgs`）：
     - 按 `signature.parameterCount()` 分离 fixed 与 extra。
     - `extra == 0` 时统一发射 `NULL, (godot_int)0`。
     - `extra > 0` 时统一声明唯一临时数组：
       ```c
       const godot_Variant* __gdcc_tmp_argv_N[] = { ... };
       ```
       并发射 `__gdcc_tmp_argv_N, (godot_int)<extraCount>`。
   - `argc` 建议显式转为 `godot_int`，避免字面量类型歧义。

4. **严格约束 vararg extra 参数类型**
   - 约束：extra 参数必须可赋给 `Variant`（当前阶段等价要求 `Variant`）。
   - 报错应在 builder 内统一输出，减少各生成器重复构造错误文案。
   - 对表达式 extra 参数应明确拒绝或要求先落地为变量，避免生命周期与地址稳定性问题。

5. **补充“原样参数”通道，避免错误地被 `renderArgument` 二次处理**
   - `argv` 与 `argc` 不是普通 GDScript 值语义参数，不应再走自动 `&`/对象指针转换。
   - 建议新增内部参数模型（例如 `RenderedArg` 或 `RawArg`），支持“已渲染 C 片段直接拼接”。
   - 这样可以防止 `NULL`、数组名、`(godot_int)N` 被误判成需取地址或做对象转换。

6. **增强 utility 路径的返回类型解析**
   - `resolveReturnType` 需支持 `godot_` 前缀名和无前缀名双向解析。
   - 保证 `callUtilityAssign` 在仅传 IR 函数名时也能稳定推导返回类型，避免把 utility 误判为 non-utility。

7. **新增针对 CBodyBuilder 的回归测试**
   - 在 `CBodyBuilderPhaseCTest`（或新增专用测试类）补充：
     - 前缀/无前缀 utility 名解析一致性；
     - vararg `extra=0` => `NULL,(godot_int)0`；
     - vararg `extra>0` => 生成 argv 临时数组且命名唯一；
     - vararg extra 非 `Variant` 报错；
     - `callUtilityAssign` 对 void utility 报错、对 non-void utility 正常赋值。

---

## 7. 异常信息规范

统一通过 `bodyBuilder.invalidInsn(...)` 抛出，消息建议包含：
- utility 名称
- 参数位置（第几个参数）
- 期望/实际类型
- 期望/实际参数数量
- 返回值规则冲突信息

示例：
- `Global utility function 'foo' not found in registry`
- `Too few arguments for utility 'print': expected at least 1, got 0`
- `Vararg argument #2 of utility 'print' must be Variant, got 'String'`
- `Utility 'deg_to_rad' returns 'float' but resultId is missing`
- `Utility 'print' has no return value but resultId is provided`

---

## 8. 具体改动清单

1. `src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallGlobalInsnGen.java`
   - 改为 `CInsnGen` 实现。
   - 移除模板依赖逻辑。
   - 增加 utility 解析、校验、vararg 组参与调用发射逻辑。

2. `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
   - 在静态注册块加入 `registerInsnGen(new CallGlobalInsnGen())`。

3. `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`
   - 增加 utility 名称统一解析入口（前缀/无前缀兼容）。
   - 增加 utility 专用调用 API（建议 `callUtilityVoid/callUtilityAssign`）。
   - 增加 vararg 组包能力（argv 临时数组 + `godot_int` argc）。
   - 增加 raw 参数通道，避免 `argv/argc` 被普通参数渲染逻辑误处理。

4. （可选优化，不是本期必须）
   - 若后续多个组件都需要复用 utility 名称归一化逻辑，可在 `CGenHelper` 抽取公共解析辅助方法。

---

## 9. 测试计划（JUnit5）

建议新增：
- `src/test/java/dev/superice/gdcc/backend/c/gen/CallGlobalInsnGenTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CBodyBuilderCallUtilityTest.java`（或并入 `CBodyBuilderPhaseCTest`）

最小覆盖矩阵：

1. **成功路径**
- `call_global "deg_to_rad" $x`：non-vararg + 有返回值。
- `call_global "print" $v1`：vararg + 无额外参数，生成 `NULL, 0`。
- `call_global "print" $v1 $v2 $v3`：vararg + 生成 `argv` 数组与 `argc=2`。
- `call_global "godot_print" ...`：带前缀输入也可解析。

2. **失败路径**
- utility 不存在。
- 参数变量不存在。
- 非 vararg 参数数量不匹配。
- vararg 参数不足 fixed 部分。
- 固定参数类型不匹配。
- vararg 额外参数非 `Variant`。
- void utility 却给了 resultId。
- non-void utility 没有 resultId。
- result 变量不存在。
- result 变量为 `ref`。
- result 类型不兼容。

3. **接入验证**
- `CCodegen` 遇到 `CALL_GLOBAL` 不再抛“Unsupported instruction opcode”。

4. **CBodyBuilder 协同验证**
- utility 名称前缀归一化正确（`print`/`godot_print`）。
- vararg `extra=0` 时发射 `NULL,(godot_int)0`。
- vararg `extra>0` 时发射 argv 临时数组且命名唯一。
- vararg extra 非 `Variant` 时抛出 `InvalidInsnException`。

建议执行命令（按仓库约定）：

```bash
./gradlew test --tests CallGlobalInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CBodyBuilderCallUtilityTest --no-daemon --info --console=plain
./gradlew test --tests CCodegenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

---

## 10. 分阶段落地步骤

### Phase A：生成器改造与注册
- [ ] `CallGlobalInsnGen` 改为 `CBodyBuilder` 路径。
- [ ] 在 `CCodegen` 完成注册。

### Phase B：CBodyBuilder 能力补齐
- [x] utility 名称归一化解析入口落地。
- [x] `callUtilityVoid/callUtilityAssign`（或等价 API）落地。
- [x] vararg argv/argc 组包和 raw 参数通道落地。

### Phase C：完整校验落地
- [ ] utility 名称解析与签名查找。
- [ ] 参数数量/类型校验。
- [ ] 返回值数量/类型校验。

### Phase D：vararg 代码生成
- [ ] `argv/argc` 组装。
- [ ] `extraCount=0` 的 `NULL,0` 分支。
- [ ] `extraCount>0` 的局部数组分支。

### Phase E：测试与回归
- [ ] 新增 `CallGlobalInsnGenTest`。
- [x] 新增/补充 CBodyBuilder utility 调用专项测试。
- [ ] 跑针对性测试并修复。

---

## 11. 风险与应对

1. **风险：直接用 `godot_` 名调用时签名查不到**
- 应对：在生成器内做双路径查找（原名/去前缀名）。

2. **风险：vararg 额外参数类型来源不规范**
- 应对：本期严格要求 `Variant`，明确报错提示“先 pack_variant”。

3. **风险：绕过 builder 导致生命周期语义丢失**
- 应对：调用发射尽量走 `callVoid/callAssign`。

4. **风险：未来 utility 元数据引入 default 参数**
- 应对：数量校验逻辑预留 default 分支，不阻塞后续扩展。

---

## 12. 完成标准（DoD）

- `CALL_GLOBAL` 在 C 后端可用，且仅支持 utility 函数。
- `CBodyBuilder` 具备 utility 专用调用能力，`CallGlobalInsnGen` 不再手写 vararg 组包细节。
- 命名转换符合 `godot_<function_name>` 约定。
- vararg utility 正确生成 `argv/argc` 调用。
- 参数与返回值数量/类型校验完整，错误定位到函数/块/指令。
- 对应单测通过，且 `CCodegen` 不再因 `CALL_GLOBAL` 未注册失败。

---

## 13. 附：本次调研确认的事实清单

- `utility_functions` 中 vararg utility 共 113 个（当前 Godot 4.5.1 API 资源）。
- vararg utility 的固定参数部分均在 API 中显式给出，额外参数通过 `argv/argc` 传入。
- `print`/`printerr` 在 API 中无 `return_type` 字段，当前等价 void。
- `entry.c.ftl` 里已有 `godot_print(&msg_variant, NULL, 0);` 现成范式，可作为实现对照。

---

## 14. 阶段进度记录（2026-02-19）

### 已完成（第一阶段）

- `CBodyBuilder` 已新增 utility 名称归一化解析，`print` / `godot_print` 可统一识别。
- `CBodyBuilder` 已新增 utility 专用 API：
  - `callUtilityVoid(String funcName, List<ValueRef> args)`
  - `callUtilityAssign(TargetRef target, String funcName, List<ValueRef> args)`
- 已落地 utility vararg 组包能力：
  - `extra=0` 生成 `NULL, (godot_int)0`
  - `extra>0` 生成 `const godot_Variant* __gdcc_tmp_argv_N[] = {...}` + `(godot_int)N`
- 已新增 raw 参数通道，避免 `argv/argc` 被普通参数渲染逻辑（自动 `&` / 对象指针转换）二次处理。
- 已新增并通过单元测试：`CBodyBuilderCallUtilityTest`。

### 已完成（代码审阅阶段，2026-02-19）

审阅范围：对照本方案及 `doc/gdcc_c_backend.md`，检查 `CBodyBuilder` utility 相关改动的缺陷与可改进之处。

**修复的缺陷与改进**：

1. **移除 `resolveUtilityCall` 中的死代码回退分支**
   - `ClassRegistry.utilityByName` 以无前缀名为 key，normalize 后查询已覆盖全部合法输入，回退分支（用原名再查）永远不会命中。

2. **消除 `renderVarargVariantPointer` 与 `validateCallArgs` 的重复校验**
   - 类型校验和变量性校验已在 `validateCallArgs` 中完成，render 阶段不再重复，避免双重维护负担。改为注释说明前置条件。

3. **新增 `rejectVarargUtilityViaNonUtilityPath` 防误用守卫**
   - 在 `callVoid` / `callAssign` 入口处检测 vararg utility，立即抛错，防止误用生成缺少 argv/argc 的非法 C 代码。

4. **提取 `emitCallResultAssignment` 公共方法**
   - `callAssign` 与 `callUtilityAssign` 原本各自重复了"destroy 旧值 → ptr 转换 → 赋值 → own 新值 → 标记初始化"逻辑，现提取为私有方法统一维护。

5. **统一 utility 路径错误消息风格**
   - 消息中一律使用 "utility function" 而非 "function"，以区分 utility 和普通函数调用错误。
   - vararg 场景的 "too few" 消息加入 "at least" 前缀。

6. **补充 `renderUtilityArgs` 和 `renderVarargVariantPointer` 的文档注释**
   - 说明 argv 指针的生命周期假设（依赖 validateCallArgs 保证 extra 参数是变量引用）。

**此次审阅未修改（低优先级或待后续处理）**：

- `callUtilityVoid` 不校验 non-void utility 返回值被丢弃（方案允许有意忽略）。
- non-vararg utility 允许通过通用 `callVoid`/`callAssign` 调用，当前不强制走专用路径。
- 已知 `checkGlobalFuncReturnGodotRawPtr` 对所有 utility 永远返回 true（因 cFunctionName 总以 `godot_` 开头），但因为后续有 `instanceof GdObjectType` 守卫所以不产生错误，语义注释已在 §12.5 补充。

### 本阶段执行过的验证命令

```bash
./gradlew test --tests CBodyBuilderCallUtilityTest --no-daemon --info --console=plain
./gradlew test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```


