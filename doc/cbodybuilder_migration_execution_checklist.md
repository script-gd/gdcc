# CBodyBuilder 迁移落地清单（含 `__finally__` 默认块实现方案）

## 1. 目标与范围

本清单用于将当前仍依赖 FTL 的 C 后端指令生成器迁移到 `CBodyBuilder`，并给出在 `CCodegen` 中自动生成默认 `__finally__` 基本块的可执行实现方案。

语义基线：
- `doc/gdcc_c_backend.md`
- `doc/cbodybuilder_implementation_guide.md`
- `doc/gdcc_low_ir.md`

迁移目标：
- 指令级语义从模板分散逻辑收敛到 `CBodyBuilder`。
- 强化 `__prepare__` / `__finally__` 控制流一致性。
- 消除对象生命周期、GDCC/Godot 指针转换在模板中的手写分叉。

## 2. 当前状态盘点（迁移输入）

### 2.1 已迁移到 `CBodyBuilder` 的生成器

- `LoadPropertyInsnGen`
- `StorePropertyInsnGen`

### 2.2 仍通过 FTL 实现且需要迁移的生成器

- `ControlFlowInsnGen` -> `insn/control_flow.ftl`
- `NewDataInsnGen` -> `insn/new_data.ftl`
- `OwnReleaseObjectInsnGen` -> `insn/own_release_object.ftl`
- `DestructInsnGen` -> `insn/destruct.ftl`

### 2.3 特殊说明

- `CallGlobalInsnGen` 当前是 `TemplateInsnGen` 形态，但未在 `CCodegen` 注册且仓库中缺失 `insn/call_global.ftl`，不纳入本清单执行范围。

## 3. 分阶段执行计划（可直接落地）

## 阶段 A：控制流迁移（最高优先）

目标：迁移 `ControlFlowInsnGen`，统一到 `__prepare__` / `__finally__` 语义。

执行项：
- [ ] 将 `ControlFlowInsnGen` 改为实现 `generateCCode(CBodyBuilder bodyBuilder)`。
- [ ] 保留 `getInsnOpcodes()`，移除 `TemplateInsnGen` 继承。
- [ ] `goto` 使用 `bodyBuilder.jump(target)`。
- [ ] `go_if` 使用 `bodyBuilder.jumpIf(valueOfVar(condition), trueBlock, falseBlock)`。
- [ ] `return` 使用 `bodyBuilder.returnVoid()` / `bodyBuilder.returnValue(...)`。
- [ ] 将原 `validateInstruction(...)` 校验迁入 builder 路径，统一抛 `bodyBuilder.invalidInsn(...)`。
- [ ] 当 `ReturnInsn.returnValueId == "_return_val"` 时，跳过变量存在性校验并走隐式返回槽特殊路径。

验收标准：
- [ ] 非 `__finally__` 块不再出现真实 `return` C 语句。
- [ ] 非 `__finally__` 的 return 转换为 `goto __finally__;`。
- [ ] `__finally__` 块产生真实 `return`。

建议测试：
- [ ] `void` 函数的 `return` 在普通块输出 `goto __finally__;`。
- [ ] 非 `void` 函数 `return value` 在普通块先写 `_return_val` 后跳转。
- [ ] `__finally__` 块输出真实返回。
- [ ] `ReturnInsn(\"_return_val\")` 在 `__finally__` 中可正确生成 `return _return_val;`，且不依赖 LIR 变量表。

## 阶段 B：对象 own/release 迁移（高优先）

目标：迁移 `OwnReleaseObjectInsnGen`，统一对象引用计数策略。

执行项：
- [ ] 改为 `generateCCode(CBodyBuilder)`。
- [ ] 保留并内联现有对象变量存在性与 `GdObjectType` 校验。
- [ ] 依据 `RefCountedStatus` 决策调用函数：
  - `YES` -> `own_object` / `release_object`
  - `UNKNOWN` -> `try_own_object` / `try_release_object`
  - `NO` -> no-op
- [ ] 统一通过 `bodyBuilder.callVoid(func, List.of(bodyBuilder.valueOfVar(objVar)))` 发射调用。

验收标准：
- [ ] GDCC 对象不再由生成器手工拼接 `->_object`。
- [ ] `YES/UNKNOWN/NO` 三分支行为符合预期。
- [ ] 对错误输入（非对象变量）仍可定位到函数/块/指令索引。

建议测试：
- [ ] GDCC RefCounted -> `own_object($obj->_object)`。
- [ ] engine unknown -> `try_*` 路径。
- [ ] non-refcounted -> 不生成代码。

## 阶段 C：destruct 迁移（高优先）

目标：迁移 `DestructInsnGen`，统一清理语义并避免模板分支歧义。

执行项：
- [ ] 改为 `generateCCode(CBodyBuilder)`。
- [ ] 类型分流逻辑迁移为 Java switch，不再依赖模板字符串 `genMode`。
- [ ] `GdVoidType` 维持报错。
- [ ] 非对象 destroyable 类型走 `helper.renderDestroyFunctionName(type)` + `callVoid`。
- [ ] 对象类型依据 `RefCountedStatus` 使用 `release/try_release/try_destroy` 策略（团队确认后固定）。
- [ ] 明确 GDCC 对象与 engine 对象都通过 builder 参数渲染路径处理指针转换。

验收标准：
- [ ] `Variant/String/StringName/Array/Dictionary` 等类型清理函数正确。
- [ ] 对象类型行为与 `doc/gdcc_c_backend.md` 生命周期规范一致。

建议测试：
- [ ] 非对象 destroyable 类型发射正确 destroy 函数。
- [ ] RefCounted 对象发射 release 或 try_release。
- [ ] 非 RefCounted 对象发射 destroy 或 no-op（按策略）。

## 阶段 D：new_data 迁移（中优先）

目标：迁移 `NewDataInsnGen`，消除模板中 ref/non-ref 分叉拼接。

执行项：
- [ ] 改为 `generateCCode(CBodyBuilder)`。
- [ ] `literal_bool/int/float/null` 使用 `assignExpr`。
- [ ] `literal_nil/string/string_name` 按 ref/non-ref 分两种写入路径：
  - non-ref: `callAssign`
  - ref: `callVoid(initFunc, ...)`
- [ ] 保留 result 变量类型校验与空 resultId 校验。

验收标准：
- [ ] 所有 literal 指令都走 builder 统一错误定位。
- [ ] ref 与 non-ref 变量生成代码符合 Godot API 约定。

建议测试：
- [ ] 每种 literal 至少 1 个正例。
- [ ] 每种 literal 至少 1 个类型不匹配负例。

## 阶段 E：模板退役与回归

执行项：
- [ ] 删除不再使用的模板：
  - `src/main/c/codegen/insn/control_flow.ftl`
  - `src/main/c/codegen/insn/new_data.ftl`
  - `src/main/c/codegen/insn/own_release_object.ftl`
  - `src/main/c/codegen/insn/destruct.ftl`
- [ ] 检查 `TemplateInsnGen` 的剩余使用方。
- [ ] 清理文档中的旧路径引用。

验收标准：
- [ ] `CCodegen` 全部注册生成器可运行，无已删除模板依赖。

## 4. 在 `CCodegen` 中生成默认 `__finally__` 基本块的实现方法

本节给出可直接编码的实现方法，用于保证：
- 所有函数始终存在 `__finally__`。
- `__finally__` 清理函数内所有 LIR 非 ref 变量（按类型释放资源）。
- `__finally__` 统一完成最终返回。

### 4.1 语义约束

- `__prepare__` 中非 ref 变量被视为“初次初始化”。
- 非 `__finally__` 块中的 `return`/`returnValue` 不生成真实 return，只跳转到 `__finally__`。
- 仅 `__finally__` 生成真实 return。
- 非 `void` 函数在 `__prepare__` 声明 `_return_val`。
- `_return_val` 是仅存在于 C 代码生成阶段的隐式变量，不是 LIR 变量。
- `_return_val` 不参与自动销毁，也不会出现在 `LirFunctionDef#getVariables()` 中。
- 当 `ReturnInsn.returnValueId == "_return_val"` 时，生成器应跳过“变量存在性校验”，并走隐式返回槽特殊路径。

### 4.2 在 `CCodegen` 中的改造点

建议新增方法（命名可调整）：
- `private void ensureFunctionFinallyBlock()`
- 或合并为：`private void generateFunctionPrepareAndFinallyBlocks()`

调用时机：
- 在 `generate()` 中，`generateFunctionPrepareBlock()` 之后、`generateFuncBody(...)` 之前执行。

### 4.3 `__finally__` 块生成算法

对每个 `LirFunctionDef func`：

1) 若不存在 `__finally__`：创建并 `func.addBasicBlock(finallyBB)`。
2) 维护跳转收敛：
- 非 `__finally__` block 的 return 由 `CBodyBuilder.return*` 统一重写为 `goto __finally__`，无需在 LIR 端重写 return 指令。
3) 扫描 `func.getVariables()`：
   - 跳过参数变量。
   - 跳过 `ref=true` 变量。
   - 无需处理 `_return_val`（它不在 LIR 变量表中）。
   - 对其余变量，根据类型可清理性插入 `DestructInsn(variable.id())` 到 `__finally__`。
4) 在 `__finally__` 尾部追加：
   - `ReturnInsn(null)`（`void`）
   - `ReturnInsn("_return_val")`（non-void；显式使用哨兵值触发特殊路径）

### 4.3.1 `ReturnInsn(\"_return_val\")` 特殊路径规则

为支持 `_return_val` 隐式变量（不进入 LIR 变量表），在控制流生成器中增加如下约定：

- 对 non-void 函数：
  - 若 `returnValueId == null`，报错（缺少返回值）。
  - 若 `returnValueId.equals(\"_return_val\")`，跳过 `func.getVariableById(...)` 存在性校验，直接走 `bodyBuilder.returnVoid()`。
    - 在 `__finally__` 中：`returnVoid()` 生成 `return _return_val;`
- 在非 `__finally__` 中：会生成 `goto __finally__;`（若出现该路径通常视为不规范 IR，但逻辑可闭合）
  - 若 `returnValueId` 是普通变量 id，维持现有变量存在性和类型校验，随后 `bodyBuilder.returnValue(bodyBuilder.valueOfVar(returnValueId))`。
- 对 void 函数：
  - 若 `returnValueId != null`，报错。
  - 否则走 `bodyBuilder.returnVoid()`。

### 4.4 变量清理范围定义（建议）

建议使用以下规则判定“应加入 `__finally__` 清理”：
- `!isParameter`
- `!variable.ref()`
- `variable.type().isDestroyable()`

备注：
- 对 primitive 类型（通常 `isDestroyable=false`）不会生成 destruct。
- 对对象/Variant/字符串/容器等按既有 `DestructInsnGen` 语义执行。
- `_return_val` 因为不属于 LIR 变量集合，不会被扫描到，无需额外排除条件。

### 4.5 参考伪代码（可直接翻译为 Java）

```java
private void ensureFunctionFinallyBlock() {
    for (var classDef : module.getClassDefs()) {
        for (var func : classDef.getFunctions()) {
            var finallyBlock = func.getBasicBlock("__finally__");
            if (finallyBlock == null) {
                finallyBlock = new LirBasicBlock("__finally__");
                func.addBasicBlock(finallyBlock);
            } else {
                finallyBlock.instructions().clear();
            }

            var parameterNames = func.getParameters().stream()
                    .map(ParameterDef::getName)
                    .collect(java.util.stream.Collectors.toSet());

            for (var variable : func.getVariables().values()) {
                if (parameterNames.contains(variable.id())) {
                    continue;
                }
                if (variable.ref()) {
                    continue;
                }
                if ("_return_val".equals(variable.id())) {
                    continue;
                }
                if (!variable.type().isDestroyable()) {
                    continue;
                }
                finallyBlock.instructions().add(new DestructInsn(variable.id()));
            }

            if (func.getReturnType() instanceof GdVoidType) {
                finallyBlock.instructions().add(new ReturnInsn(null));
            } else {
                // _return_val 是代码生成期隐式变量，不进入 LIR 变量表。
                // 这里使用 "_return_val" 作为 ReturnInsn 的哨兵值，
                // 由 ControlFlowInsnGen 特殊处理并映射到 returnVoid() 的 finally 返回路径。
                finallyBlock.instructions().add(new ReturnInsn("_return_val"));
            }
        }
    }
}
```

## 5. 建议提交切分（Commit Plan）

- Commit 1: `ControlFlowInsnGen` 迁移 + 测试。
- Commit 2: `OwnReleaseObjectInsnGen` 迁移 + 测试。
- Commit 3: `DestructInsnGen` 迁移 + 测试。
- Commit 4: `NewDataInsnGen` 迁移 + 测试。
- Commit 5: `CCodegen` 自动 `__finally__` 生成 + 相关测试。
- Commit 6: 删除废弃 FTL + 文档更新。

## 6. 回归测试清单（执行命令）

- `./gradlew test --tests CBodyBuilderPhaseBTest --no-daemon --info --console=plain`
- `./gradlew test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain`
- `./gradlew test --tests "*ControlFlow*" --no-daemon --info --console=plain`
- `./gradlew test --tests "*OwnReleaseObject*" --no-daemon --info --console=plain`
- `./gradlew test --tests "*Destruct*" --no-daemon --info --console=plain`
- `./gradlew test --tests "*NewData*" --no-daemon --info --console=plain`
- `./gradlew classes --no-daemon --info --console=plain`

## 7. 风险点与防护

- 风险：`__finally__` 自动清理可能与手工 `destruct` 重叠。
  - 防护：先按 `type.isDestroyable()` + 非 ref + 非参数约束；新增重复析构场景测试。
- 风险：对象清理策略（`release` vs `try_destroy`）不一致。
  - 防护：在 `DestructInsnGen` 迁移前明确团队策略并固化到单测。
- 风险：迁移后模板残留引用导致运行时模板加载失败。
  - 防护：删除模板前先检索 `getTemplatePath()` 与 classpath 引用。

## 8. 完成判定（Definition of Done）

- [ ] 四个目标生成器全部改为 `CBodyBuilder` 路径。
- [ ] `CCodegen` 自动生成 `__finally__` 且通过针对性测试。
- [ ] 不再存在对四个 retired 模板的加载。
- [ ] 生命周期/返回控制流语义与 `doc/gdcc_c_backend.md` 一致。
- [ ] 最终编译通过，关键测试通过。
