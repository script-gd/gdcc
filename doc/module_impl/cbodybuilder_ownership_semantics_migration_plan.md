# CBodyBuilder 所有权语义改造设计（最小改造、全库覆盖）

> 对应规范：`doc/gdcc_ownership_lifecycle_spec.md`  
> 范围：后端代码生成层（不改 LIR 结构）

## 1. 设计目标

在尽量少改现有代码结构的前提下，完成以下统一：

- 函数返回对象值视为 `OWNED`
- 存储槽写入遵循“旧值 release + 新值按类别 own/consume”
- 丢弃可析构返回值时立即清理
- `_return_val` 路径与 finally 自动析构协作无泄漏

## 2. 约束与非目标

### 2.1 约束

- 不修改 Gradle / 构建配置
- 不改变 LIR 指令文本格式
- 保持 `CInsnGen` 分层：生成器负责校验，Builder 负责生命周期语义

### 2.2 非目标

- 不引入新 IR 指令如 `move_object`
- 不在本阶段重写所有 FTL 模板

## 3. 全库覆盖策略

### 3.1 以 Builder 为中心统一语义

优先改造 `CBodyBuilder`，让所有调用 Builder 的指令生成器自动继承新语义。

受益路径：

- `CallGlobalInsnGen`
- `NewDataInsnGen`
- `LoadPropertyInsnGen`
- `StorePropertyInsnGen`（通过 Builder 的分支）
- `PackUnpackVariantInsnGen`
- `ControlFlowInsnGen`（return 路径）

### 3.2 仍需人工对齐的路径

- `OwnReleaseObjectInsnGen`（语义本就显式）
- `template_451/entry.c.ftl` 的构造/析构代码
- 未迁移模板中手写生命周期逻辑

## 4. 代码改造明细

## 4.1 `CBodyBuilder`（核心）

文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`

### 4.1.1 数据模型最小扩展

在 `ValueRef` 语义中加入 ownership 维度：

- 新增 `OwnershipKind` 枚举：`BORROWED`、`OWNED`
- `ValueRef` 新增 `ownership()`
- 默认实现返回 `BORROWED`（减少存量调用改动）

建议初始映射：

- `VarValue` -> `BORROWED`
- `ExprValue` -> `BORROWED`（除显式 Owned 工厂）
- `StringNamePtrLiteralValue` / `StringPtrLiteralValue` -> `BORROWED`
- `TempVar` -> `BORROWED`（其生命周期由声明/销毁管理）

### 4.1.2 工厂方法

新增：

- `valueOfOwnedExpr(String code, GdType type, PtrKind ptrKind)`

用于标记“调用结果对象值”或“明确 move 源”场景。

### 4.1.3 对象写入统一入口

抽出私有方法（命名可调整）：

- `emitObjectSlotWrite(TargetRef target, String rhsCode, PtrKind rhsPtrKind, OwnershipKind ownership)`

行为：

1. 按 initialized 状态决定是否 release 旧值
2. 执行必要指针转换后赋值
3. `ownership==BORROWED` 时 own 新值
4. `ownership==OWNED` 时不 own（消费）
5. 标记 target initialized

### 4.1.4 `assignVar` 改造

- 非对象保持现有逻辑
- 对象改为调用 `emitObjectSlotWrite(...)`
- 关键变化：不再无条件 own，而是由 RHS ownership 决定

### 4.1.5 `callAssign` / `emitCallResultAssignment` 改造

- 对象返回值默认按 `OWNED` 处理
- 赋值到对象 target 时：不再额外 own
- 非对象不变
- 指针表示转换保持现有规则

### 4.1.6 discard 清理

`callAssign` 的 discard 分支：

- `returnType == null`：保持原行为（兼容）
- 非 void 且 `isDestroyable()==true`：
  - 物化临时接收返回值
  - 对对象执行 release/try_release
  - 对非对象执行 destroy

### 4.1.7 `returnValue` 与 `_return_val`

新增 `_return_val` 代码生成期状态跟踪（仅 Builder 内部）：

- `private boolean returnSlotInitialized`

规则：

- 在 `__prepare__` 中声明 `_return_val` 后，若返回类型为对象，初始化为 `NULL` 并标记 initialized
- 非 finally `returnValue` 写 `_return_val` 时，复用槽位写入语义（含旧值 release）
- finally 块 `return _return_val` 不附加析构

## 4.2 `CCodegen`（协作层）

文件：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`

最小化改动建议：

- 继续由 `ensureFunctionFinallyBlock()` 只 destruct LIR 变量，不纳入 `_return_val`
- 不额外引入 `_return_val` 到变量表
- 修复默认 setter 自动生成中对象 own 的对象名误用（应为 `value`，不是 `self`）

## 4.3 生成器逐项核对

### 4.3.1 `CallGlobalInsnGen`

- 继续向 Builder 传入 `returnType`
- discard 非 void 路径由 Builder 清理

### 4.3.2 `LoadPropertyInsnGen`

- `callAssign(target, getter, propertyType, ...)` 自动获得对象返回 ownership 语义

### 4.3.3 `PackUnpackVariantInsnGen`

- `unpack` 返回对象路径自动纳入 `OWNED` 返回处理

### 4.3.4 `StorePropertyInsnGen`

- 依赖 Builder 的路径自动生效
- 对 `appendLine` 直写字段分支做一次语义审计（避免绕过生命周期）

## 5. 测试改造计划

## 5.1 必增单测（优先）

文件：`src/test/java/dev/superice/gdcc/backend/c/gen/CBodyBuilderPhaseCTest.java`

新增用例：

1. `callAssign` 对象返回赋值：不重复 own
2. `returnValue`（对象 Borrowed 源）写 `_return_val` 后可安全 return
3. 多分支 return 覆盖 `_return_val`：旧值 release 顺序正确
4. discard 对象返回：YES/UNKNOWN 状态都释放
5. discard String/Variant/Array 返回：立即 destroy

## 5.2 协同回归

- `CallGlobalInsnGenTest`：non-void discard 清理
- `CPhaseAControlFlowAndFinallyTest`：`_return_val` 行为稳定
- `CPackUnpackVariantInsnGenTest`：对象路径无回归

## 5.3 执行命令

```bash
./gradlew test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
./gradlew test --tests CallGlobalInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CPhaseAControlFlowAndFinallyTest --no-daemon --info --console=plain
./gradlew test --tests CPackUnpackVariantInsnGenTest --no-daemon --info --console=plain
./gradlew classes --no-daemon --info --console=plain
```

## 6. 分阶段实施（建议）

### 阶段 A：语义底座

- 完成 `ValueRef` ownership 扩展
- 完成 `assignVar` / `emitCallResultAssignment` 对象路径改造
- 新增 discard 清理

### 阶段 B：返回槽与 finally 协作

- `_return_val` initialized 管理
- return 覆盖写路径正确释放
- finally 回归

### 阶段 C：全库审计

- 逐个 `InsnGen` 审计是否绕开 Builder
- 审计 `entry.c.ftl` 生命周期片段
- 同步文档与 TODO

## 7. 风险与缓解

### 风险 1：对象返回路径行为变化导致历史测试失效

- 缓解：先更新语义测试，再修正文案断言（强调顺序与调用次数）

### 风险 2：`_return_val` 初始化与首写判断不一致

- 缓解：将 `_return_val` 初始化逻辑集中在 Builder，避免 CCodegen/Builder 双重状态

### 风险 3：模板路径与 Java 路径语义分叉

- 缓解：列出模板清单并做对照审计，必要时在文档标注“语义来源优先 Builder”

## 8. 完成标准（DoD）

- `OWNED/BORROWED` 模型在 Builder 落地并覆盖对象写入核心路径
- discard 泄漏问题关闭
- `_return_val` 对象返回路径可重复覆盖且无泄漏
- 目标测试集通过，且无新增已知生命周期回归
- 文档与实现一致（本文件 + `doc/gdcc_ownership_lifecycle_spec.md`）
