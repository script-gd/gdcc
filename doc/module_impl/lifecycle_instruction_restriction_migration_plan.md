# Lifecycle Instruction Restriction Migration Plan

## 1. 背景与目标

当前 Low IR 同时支持：
- 显式生命周期指令（`destruct` / `try_own_object` / `try_release_object`）
- 后端 `__finally__` 自动插入析构

这会导致语义边界不清，存在重复析构/重复 release 风险（可能触发段错误）。

本方案目标是：
1. 将上述 3 条指令限定为“受控使用”。
2. 将资源生命周期主导权收敛到编译器内部机制。
3. 保留用户在 GDScript 源码层显式生命周期调用的能力（通过受控 lowering）。
4. 完整迁移文档、解析/校验逻辑与单元测试，确保行为一致可回归。

---

## 2. 方案总述（规范层）

### 2.1 新规范（核心约束）

`destruct` / `try_own_object` / `try_release_object` 仅允许用于以下场景：
- 编译器自动生成的析构路径（例如 `__finally__` 的自动析构）
- 编译器内部临时变量或内部变量生命周期操作
- 用户在 GDScript 源码中显式调用析构/引用计数相关 API，经前端识别后生成的“用户显式生命周期意图”指令

不允许：
- 普通用户变量在任意 LIR 手写/外部 IR 注入中无约束使用上述指令
- 在不具备 provenance（来源标记）的情况下直接发射生命周期指令

### 2.2 必要的来源标记（provenance）

为生命周期指令引入来源标记（枚举）：
- `AUTO_GENERATED`：后端自动插入
- `INTERNAL`：编译器内部操作
- `USER_EXPLICIT`：用户源码显式生命周期意图 lowering 结果
- `UNKNOWN`：默认值，应发出警告

实现方式：新增 `LifecycleInstruction`（继承 `ConstructionInstruction`）并提供 `getProvenance()`；
`destruct` / `try_own_object` / `try_release_object` 三条指令实现该接口并携带 provenance 字段。

### 2.3 与现有 finally 机制关系

- 继续允许 `__finally__` 自动析构。
- 自动析构应只对“托管变量集合”生效（排除参数、`ref`、`_return_val` 等既有特例）。
- 显式生命周期指令与自动析构的冲突由验证层阻断（或由状态机方案消解，但本计划先以前置验证为主）。

---

## 3. 非目标（本次不做）

- 不重写整个所有权模型。
- 不移除 3 条生命周期指令本身。
- 不一次性引入跨函数全程序生命周期分析。
- 不修改构建脚本/Gradle 配置。

---

## 4. 影响面清单

## 4.1 文档

必须更新：
- `doc/gdcc_low_ir.md`
  - `destruct`
  - `try_own_object`
  - `try_release_object`
  - 增加“Instruction Usage Restrictions”章节
- `doc/gdcc_ownership_lifecycle_spec.md`
  - 增加来源标记、允许/禁止矩阵
  - 明确与 `__prepare__` / `__finally__` 的交互
- `doc/gdcc_c_backend.md`
  - 更新生命周期约束落地描述
- `doc/module_impl/cbodybuilder_implemention.md`
  - 增加迁移后行为基线与回归项
- `AGENTS.md`（可选，但建议）
  - 增加对手写 LIR 生命周期指令限制说明

## 4.2 代码（主路径）

重点文件：
- `src/main/java/dev/superice/gdcc/backend/CodegenContext.java`
- `src/main/java/dev/superice/gdcc/lir/insn/LifecycleInstruction.java`
- `src/main/java/dev/superice/gdcc/enums/LifecycleProvenance.java`
- `src/main/java/dev/superice/gdcc/lir/insn/DestructInsn.java`
- `src/main/java/dev/superice/gdcc/lir/insn/TryOwnObjectInsn.java`
- `src/main/java/dev/superice/gdcc/lir/insn/TryReleaseObjectInsn.java`
- `src/main/java/dev/superice/gdcc/lir/parser/ParsedLirInstruction.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/DestructInsnGen.java`
- `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OwnReleaseObjectInsnGen.java`
- （新增）`src/main/java/dev/superice/gdcc/lir/validation/...` 生命周期约束校验器

## 4.3 现有单元测试（需要迁移）

已识别直接相关：
- `src/test/java/dev/superice/gdcc/backend/c/gen/CDestructInsnGenTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/COwnReleaseObjectInsnGenTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CPhaseAControlFlowAndFinallyTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/CBodyBuilderPhaseCTest.java`
- `src/test/java/dev/superice/gdcc/lir/parser/SimpleLirBlockInsnSerializerTest.java`
- `src/test/java/dev/superice/gdcc/lir/parser/DomLirSerializerTest.java`

---

## 5. 分阶段实施计划

## Phase 0 - 设计冻结与规则落板

交付物：
- 生命周期指令“允许/禁止矩阵”
- provenance 枚举定义
- 验证报错规范（错误码/错误文案）

关键决策：
- provenance 存储位置（指令级优先）
- 是否启用兼容过渡开关（建议有）
- `CodegenContext` 增加 `strictMode`（默认 false）

### Phase 0 当前落地状态（已完成）

- [x] 新增 `LifecycleProvenance`：`AUTO_GENERATED` / `INTERNAL` / `USER_EXPLICIT` / `UNKNOWN`
- [x] 新增 `LifecycleInstruction` 接口，并定义 `getProvenance()`
- [x] `DestructInsn` / `TryOwnObjectInsn` / `TryReleaseObjectInsn` 接入 provenance，默认值为 `UNKNOWN`
- [x] `CodegenContext` 新增 `strictMode` 字段，保留二参构造兼容路径
- [x] `CCodegen` 自动注入 `DestructInsn` 时标记 `AUTO_GENERATED`

## Phase 1 - IR 元模型与解析扩展

任务：
1. 为 3 条生命周期指令接入 provenance。
2. 扩展 parser/serializer，支持来源标记序列化与反序列化。
3. 保持旧格式兼容（未标记时默认 `UNKNOWN` 或等价值，仅在过渡期允许）。

完成标准：
- 老测试可在兼容模式下通过
- 新增 round-trip 测试覆盖 provenance 字段

### Phase 1 当前落地状态（已完成）

- [x] 生命周期指令文本语法支持可选 provenance（例如 `destruct $v "AUTO_GENERATED";`）
- [x] `SimpleLirBlockInsnParser` / `ParsedLirInstruction` 支持 provenance 反序列化
- [x] `SimpleLirBlockInsnSerializer` 支持 provenance 序列化（`UNKNOWN` 保持旧格式兼容）
- [x] 增加 `LifecycleInstructionProvenanceParserTest` 覆盖 round-trip 与 legacy 兼容
- [x] parser/serializer 相关既有测试已通过

## Phase 2 - 校验层落地（核心）

任务：
1. 新增生命周期约束校验器：在 codegen 前执行。
2. 校验规则：
   - `UNKNOWN` 的生命周期指令在 strict 模式报错
   - `USER_EXPLICIT` 仅允许来自白名单 lowering 路径
   - `INTERNAL` 仅允许作用于内部临时变量或其他临时值等
   - `AUTO_GENERATED` 仅允许出现在自动生成流程（如 `__finally__` 注入）
3. 报错信息需包含：函数名、块 ID、指令索引、变量 ID、来源类型。

完成标准：
- 能准确拦截非法指令
- 错误信息可直接指导修复

### Phase 2 当前落地状态（已完成）

- [x] 新增 `LifecycleInstructionRestrictionValidator`，覆盖 `UNKNOWN` / `AUTO_GENERATED` / `INTERNAL` / `USER_EXPLICIT` 约束
- [x] `UNKNOWN` 在 strict 模式抛错，在 compat 模式告警放行
- [x] 校验异常统一使用 `InvalidInsnException(func, block, index, insn, reason)`，并在 reason 中包含 provenance 与变量信息
- [x] `CCodegen.generate()` 在 `__prepare__` / `__finally__` 注入后执行生命周期校验
- [x] `CCodegen.generateFuncBody()` 直出路径同样执行生命周期校验（覆盖单测直接调用场景）
- [x] 新增 `LifecycleInstructionRestrictionValidatorTest` 覆盖 allowed/forbidden 基础矩阵
- [x] 更新 `CPhaseAControlFlowAndFinallyTest`，断言自动注入析构 provenance 为 `AUTO_GENERATED`

## Phase 3 - 后端注入与生成器对齐

任务：
1. `CCodegen` 自动注入的 `DestructInsn` 标记 `AUTO_GENERATED`。
2. `DestructInsnGen` / `OwnReleaseObjectInsnGen` 在生成前可做轻量来源断言（防御性编程）。
3. 保持 `__prepare__` / `__finally__` 框架不变，避免功能漂移。

完成标准：
- 现有自动析构路径行为不变
- 非法来源无法流入后端生成

## Phase 4 - 前端/Lowering 接入用户显式生命周期意图

任务：
1. 识别用户源码中的显式析构/引用计数调用。
2. lowering 时标记为 `USER_EXPLICIT`。
3. 对非显式场景禁止生成该三类指令。

完成标准：
- 用户显式生命周期语义可保留
- 其余路径由编译器自动生命周期机制接管

## Phase 5 - 文档迁移与示例更新

任务：
1. 全量更新文档（见 4.1）。
2. 将 `doc/gdcc_low_ir.md` 示例中“可引发歧义”的写法替换为受控示例。
3. 增加“合法/非法 IR 片段”对照。

完成标准：
- 文档不再出现“任意可用”的暗示
- 新旧行为边界清晰可执行

## Phase 6 - 测试迁移与回归

任务：
- 更新旧测试断言
- 新增约束与迁移测试（见第 6 节）
- 分批次跑 targeted tests

完成标准：
- 相关测试全部通过
- 迁移覆盖率满足验收标准

---

## 6. 单元测试迁移计划（全面覆盖）

## 6.1 现有测试迁移矩阵

1) `CDestructInsnGenTest`
- 现状：直接构造 `DestructInsn` 并断言 C 输出。
- 迁移：
  - 为合法 case 添加 provenance（`INTERNAL` 或 `AUTO_GENERATED` 测试上下文）
  - 新增非法 provenance case，断言在验证阶段失败（`assertThrows`）

2) `COwnReleaseObjectInsnGenTest`
- 现状：直接构造 `TryOwnObjectInsn`/`TryReleaseObjectInsn`。
- 迁移：
  - 按来源分组测试（合法/非法）
  - 增加“普通变量 + INTERNAL 来源”应失败的负例

3) `CPhaseAControlFlowAndFinallyTest`
- 现状：断言 `__finally__` 自动注入 `DestructInsn`。
- 迁移：
  - 断言自动注入的指令带 `AUTO_GENERATED`
  - 断言重复注入仍不追加（保持原语义）

4) `CBodyBuilderPhaseCTest`
- 现状：覆盖 return/discard/slot write 等生命周期行为。
- 迁移：
  - 增加“受限生命周期指令不能从普通路径流入”的校验用例
  - 保持既有 `_return_val`、discard 路径断言

5) `SimpleLirBlockInsnSerializerTest` / `DomLirSerializerTest`
- 现状：仅覆盖指令结构。
- 迁移：
  - 增加 provenance 字段 round-trip 用例
  - 增加兼容旧格式读取用例

## 6.2 需要新增的测试类

建议新增：
- `src/test/java/dev/superice/gdcc/lir/validation/LifecycleInstructionRestrictionValidatorTest.java`
  - 核心覆盖 allowed/forbidden matrix
- `src/test/java/dev/superice/gdcc/backend/c/gen/LifecycleProvenancePropagationTest.java`
  - 覆盖自动注入来源传播
- `src/test/java/dev/superice/gdcc/lir/parser/LifecycleInstructionProvenanceParserTest.java`
  - 覆盖 parser/serializer 的新字段兼容

## 6.3 建议执行顺序（targeted）

```bash
./gradlew.bat test --tests LifecycleInstructionRestrictionValidatorTest --no-daemon --info --console=plain
./gradlew.bat test --tests CDestructInsnGenTest --tests COwnReleaseObjectInsnGenTest --no-daemon --info --console=plain
./gradlew.bat test --tests CPhaseAControlFlowAndFinallyTest --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
./gradlew.bat test --tests SimpleLirBlockInsnSerializerTest --tests DomLirSerializerTest --no-daemon --info --console=plain
./gradlew.bat classes --no-daemon --info --console=plain
```

---

## 7. 文档迁移任务分解（逐文件）

## 7.1 `doc/gdcc_low_ir.md`

- 在三条生命周期指令下新增“Restrictions”小节：
  - 允许来源
  - 禁止场景
  - 违规后果（验证失败）
- 在 Demo 中说明：示例中的 `destruct` 仅用于编译器受控路径，不代表任意外部 IR 可自由使用。

## 7.2 `doc/gdcc_ownership_lifecycle_spec.md`

新增：
- `LifecycleProvenance` 定义
- Allowed/Forbidden Matrix
- strict/compat 模式说明
- 与 `__finally__` 自动析构的冲突处理策略

## 7.3 `doc/gdcc_c_backend.md`

新增：
- 生成器假设：生命周期指令已经过验证
- 非法来源将 fail-fast

## 7.4 `doc/module_impl/cbodybuilder_implemention.md`

新增：
- 迁移后基线行为
- 关键回归命令
- 已知边界（例如 `_return_val`）

---

## 8. 兼容与发布策略

建议两阶段开关：
- 阶段 A（兼容模式）
  - 未标记 provenance 仅告警
  - CI 中统计告警数量
- 阶段 B（严格模式）
  - 未标记/非法来源直接报错

建议至少经历一个完整迭代周期后切 strict。

---

## 9. 风险清单与缓解

1. 误杀合法场景
- 缓解：白名单先宽后紧，先告警后报错。

2. 迁移期间测试雪崩
- 缓解：按第 6.3 分批迁移，先 parser/validator，再 backend。

3. 来源标记遗漏
- 缓解：新增统一工厂方法创建生命周期指令，禁止裸 new。

4. 文档与实现漂移
- 缓解：每个阶段 PR 必须同时更新对应文档段落。

5. 行为回归（泄漏或过早释放）
- 缓解：在关键测试中加入“重复销毁防护 + 未销毁检测”断言组合。

---

## 10. 验收标准

满足以下条件即视为完成：
1. 三条生命周期指令仅在受控来源下可通过验证。
2. `__finally__` 自动析构行为稳定，且与显式路径无冲突。
3. 文档更新完成并通过 review。
4. 相关迁移测试全部通过（含新增验证器测试）。
5. 兼容模式告警清零后可切 strict。

---

## 11. 执行清单（Checklist）

- [x] 冻结规范（来源模型 + 允许矩阵）
- [x] 实现 provenance 数据结构
- [x] parser/serializer 支持 provenance
- [x] 引入 lifecycle restriction validator
- [x] 后端自动注入来源标记
- [ ] 迁移现有测试
- [ ] 新增验证器与来源传播测试
- [ ] 更新 low_ir / ownership / c_backend / module_impl 文档
- [ ] 跑完 targeted tests 与 classes
- [ ] 切 strict 前清零兼容告警

---

## 12. 附：允许/禁止矩阵（建议稿）

- `AUTO_GENERATED`
  - 允许：`__finally__` 自动析构
  - 禁止：普通业务块手写注入

- `INTERNAL`
  - 允许：编译器内部生命周期维护
  - 禁止：普通命名变量

- `USER_EXPLICIT`
  - 允许：由用户源码显式生命周期调用 lowering 而来
  - 禁止：无对应源码语义的“伪显式”注入

- `UNKNOWN`
  - 允许：compat 模式下告警放行
  - 禁止：strict 模式
