# CALL_GLOBAL 生成器渐进式实施草案（v1）

## 状态

- 状态：草案
- 目标模块：`backend.c`（`CallGlobalInsnGen` + `CBodyBuilder` + `CGenHelper`）
- 本文用途：指导后续实施，不代表代码已完成

## 1. 调研结论（现状）

### 1.1 现有代码能力

- `CALL_GLOBAL` 已注册到 C 后端指令分发：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`。
- 当前 `CallGlobalInsnGen` 只保留 utility 解析和基础校验框架，实际调用发射仍为 TODO：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallGlobalInsnGen.java`。
- `CGenHelper` 已具备 utility 解析基础能力：
  - `normalizeUtilityLookupName`
  - `toUtilityCFunctionName`
  - `resolveUtilityCall`
  - 位置：`src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`。
- `ClassRegistry.findUtilityFunctionSignature` 可提供 utility 参数与返回类型元数据，包含 `isVararg` 与 defaultValue 文本：`src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`。
- `CBodyBuilder` 当前仅有通用 `callVoid/callAssign`，无 utility 专用路径，也无“raw 参数片段”能力：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`。

### 1.2 与本次目标的差距

本次要求是：
1. 同时支持 non-vararg / vararg 全局函数调用；
2. 校验在生成器（`CallGlobalInsnGen`）完成；
3. vararg 在调用前先由 Builder 构建临时参数。

当前差距：
- 生成器尚未完成 non-vararg/vararg 的最终发射。
- Builder 还缺少“构建并返回 vararg 临时参数（argv/argc）”的通用接口。
- 目前 `callVoid/callAssign` 参数渲染会做自动地址/指针处理，不适合直接塞入 `argv/argc` 这类 raw 片段。

## 2. 设计原则（吸取上次重构经验）

1. **校验集中在生成器**：参数数量、参数类型、resultId 合法性、vararg extra 约束统一在 `CallGlobalInsnGen`。
2. **Builder 只做构建与发射**：不重复做 CALL_GLOBAL 语义校验，避免职责重叠。
3. **禁止手工拼接复杂调用语义**：生成器不直接拼生命周期相关语句，不复制 `assignVar/callAssign` 语义。
4. **先复用再扩展**：优先复用 `CGenHelper.resolveUtilityCall`、`CBodyBuilder.callAssign`，缺口通过小 API 补齐。
5. **分阶段落地**：每阶段都有可验证目标，避免“大爆炸式重写”。

## 3. 目标范围与非目标

### 3.1 目标范围（本轮方案）

- `CALL_GLOBAL` 的 utility 路径（来自 class registry）支持：
  - non-vararg
  - vararg
- 生成器负责完整校验。
- vararg extra 在调用前先经 Builder 生成临时 argv 结构。

### 3.2 非目标（本草案不立即实现）

- 非 utility 的 global symbol 调用（如未来外部 C 符号分流）。
- IR 层改造（如将 `CallGlobalInsn.args` 收窄到 `List<VariableOperand>`）。
- 大规模改写 Builder 现有调用链。

## 4. 分阶段实施计划

## 阶段 A：最小可行 non-vararg 路径

### A.1 实施内容

- 在 `CallGlobalInsnGen` 中完成以下校验与发射：
  - 解析 utility（支持 `foo` / `godot_foo`）
  - 解析并校验所有 operand 必须为变量且变量存在
  - 校验参数数量（含 default 参数最小数量）
  - 校验 fixed 参数类型可赋值
  - 校验返回值与 `resultId` 关系（void/non-void）
  - 发射 non-vararg 调用：
    - void -> `bodyBuilder.callVoid(cFunctionName, args)`
    - non-void -> `bodyBuilder.callAssign(target, cFunctionName, returnType, args)`

### A.2 预期收益

- 快速恢复 `CALL_GLOBAL` 主路径可用性。
- 不引入 vararg 复杂性，先把职责边界稳定下来。

## 阶段 B：vararg 调用能力（按“生成器校验 + Builder构建临时参数”）

### B.1 实施内容

- 生成器新增 vararg 规则：
  - `provided >= fixedRequired`
  - extra 参数必须来自变量
  - extra 参数类型必须可赋值给 `Variant`
- Builder 新增“仅构建临时参数”的小接口（建议）：
  - 示例命名：`prepareVariantVararg(List<ValueRef> extras)`
  - 返回结构建议：
    - `preCallLines`（argv 声明等）
    - `argvExpr`（`NULL` 或临时数组名）
    - `argcExpr`（如 `(godot_int)2`）
- 生成器流程：
  1. 先组 fixed 参数 `List<ValueRef>`；
  2. 调 Builder 构建 vararg 临时参数；
  3. 合并为最终调用参数（fixed + argv + argc）并发射。

### B.2 关键约束

- vararg `argv/argc` 追加顺序必须与 gdextension-lite 约定一致。
- 临时变量命名必须函数内唯一，避免多次调用冲突。
- 生成器不直接声明 `const godot_Variant* ...[]`，由 Builder 负责。

## 阶段 C：默认参数补全（可选增强）

### C.1 实施内容

- 在生成器侧决定“是否缺少可由 default 补齐的 fixed 参数”。
- 缺失参数的具体 C 值物化交给 Builder（避免字符串拼接散落在生成器）。
- default 补全与 vararg 互不干扰：先补齐 fixed，再处理 extra。

### C.2 风险控制

- default 文本解析只保留一处实现，避免多个模块各自 parse。
- 先覆盖最常见 default（数值、字符串、null、常见 builtin 构造）再逐步扩展。

## 5. 生成器校验规范（建议固化）

1. utility 不存在：报错包含原始名 + lookup key。
2. operand 非变量：`Argument #N ... must be a variable operand`。
3. 变量不存在：`Argument variable ID 'x' not found ...`。
4. 参数数量：
   - non-vararg：`minRequired <= provided <= fixedCount`
   - vararg：`provided >= minRequired`（其中 extra 可为 0）
5. fixed 参数类型：`checkAssignable(argType, paramType)`。
6. vararg extra 类型：必须可赋值给 `Variant`。
7. 返回值：
   - void 返回不允许 `resultId`
   - non-void 的 `resultId` 若存在，必须存在/非 ref/类型兼容
   - non-void 允许无 `resultId`（discard）

## 6. 为更好实施建议同步改动的其他部分

以下不是“立即实施”，而是建议在进入编码前完成设计确认。

### 6.1 `CBodyBuilder`（建议新增最小 API）

- 新增 vararg 临时参数构建接口（仅构建，不校验语义）：
  - 输入：`List<ValueRef> extras`
  - 输出：可用于调用发射的 `argv/argc` 表达式 + 预发射语句
- 新增 raw 参数能力（可选）：
  - 目前 `callVoid/callAssign` 会对参数进行地址/指针规则处理。
  - `argv/argc` 更适合“按原样注入”的参数通道。
  - 可选方案：引入 `RawArg` 或 `callVoidWithRawSuffix` 这类小接口。

### 6.2 `CGenHelper`（建议）

- 补充 utility 参数数量辅助：
  - `requiredParamCount(signature)`（根据 defaultValue 推导）
- 补充 utility 诊断文本构造 helper（统一错误消息风格）。

### 6.3 `FunctionSignature` / `ClassRegistry`（建议）

- 若 default 值解析需求增长，建议引入结构化 default 表达（而非仅字符串）。
- 为 vararg utility 补充更明确的元数据注释，减少调用端猜测逻辑。

### 6.4 测试体系（建议）

- 将 `CallGlobalInsnGenTest` 分层：
  - `CallGlobalInsnGenValidationTest`（纯校验）
  - `CallGlobalInsnGenEmissionTest`（发射形态）
  - `CallGlobalInsnGenVarargTest`（argv/argc/临时变量）
- 继续使用 focused 测试命令，避免全量回归成本过高。

### 6.5 文档协同（建议）

- `doc/module_impl` 维护阶段状态（草案/实施中/归档）。
- `doc/gdcc_c_backend.md` 仅在阶段稳定后更新“稳定契约”。

## 7. 风险与防护

1. **风险：再次把 utility 语义塞进 Builder 导致耦合回潮**  
   防护：Builder 仅提供“构建临时参数 + 通用发射”能力，语义校验保留在生成器。
2. **风险：vararg raw 参数被现有渲染流程错误处理**  
   防护：明确 raw 参数注入接口，禁止通过 hack 式 `ExprValue` 混入。
3. **风险：default 补全在多处实现导致行为漂移**  
   防护：default 物化逻辑单点实现，并补充回归测试。
4. **风险：错误信息风格不统一**  
   防护：统一通过生成器格式化错误文本，并沿用 `invalidInsn(...)` 定位。

## 8. 里程碑与验收标准

### 里程碑 M1（阶段 A）

- non-vararg utility `CALL_GLOBAL` 全部可用。
- 校验覆盖成功/失败主路径。
- 不新增 Builder 耦合点。

### 里程碑 M2（阶段 B）

- vararg utility 路径可用（含 extra=0 与 extra>0）。
- vararg 临时参数由 Builder 构建，生成器不手拼声明。

### 里程碑 M3（阶段 C，可选）

- fixed 参数 default 补全稳定，行为可测且无重复实现。

## 9. 建议实施顺序（执行时）

1. 先完成 M1（non-vararg），并通过 focused tests。
2. 再引入 Builder vararg 临时参数 API，完成 M2。
3. 最后评估是否进入 default 补全（M3）。

---

如无额外约束，后续可基于本文进入“详细设计稿（接口签名级别）”，再开始代码改造。

## 10. 本次实现记录（CBodyBuilder）

### 10.1 已完成改动

- `CBodyBuilder.callVoid` 新增重载：
  - `callVoid(String funcName, List<ValueRef> args, List<ValueRef> varargs)`
  - 旧签名转发到新签名，并在 `varargs.isEmpty()` 时回退到原有调用路径（`renderArgs`）。
- `CBodyBuilder.callAssign` 新增重载：
  - `callAssign(TargetRef target, String funcName, GdType returnType, List<ValueRef> args, List<ValueRef> varargs)`
  - 旧签名全部转发到新签名，并在 `varargs.isEmpty()` 时回退到原有路径。
- 新增 `renderArgsWithVarargs(...)` 与 `renderVarargArgv(...)`：
  - 固定参数继续走现有 `renderArgs`。
  - vararg 尾部由 Builder 生成 `argv` 临时数组与 `argc` 片段。
  - 调用参数最终拼为：`fixed..., argv, (godot_int)<extraCount>`。
- 删除未使用记录类型：
  - `UtilityDefaultArgsResult`
  - `UtilityArgsRenderResult`
- `RenderResult` 增加字段：
  - `@Nullable String preCode`
  - 用于承载 vararg 渲染前置代码（如 `argv` 声明）。

### 10.2 行为约束

- 当 `varargs` 为空时，`callVoid`/`callAssign` 仍使用原有渲染路径，避免影响非 vararg 历史行为。
- 当 `varargs` 非空时，Builder 会在发射调用前输出 `preCode`，再发射调用语句。
- Builder 对 vararg 参数保留防御性检查：要求可赋值给 `Variant`。

### 10.3 本次验证

- 已执行：
  - `./gradlew classes --no-daemon --info --console=plain`
- 结果：`BUILD SUCCESSFUL`。
