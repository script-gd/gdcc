# Scope 架构与共享 Resolver 重构计划

> 本文档定义 `dev.superice.gdcc.scope` 包的下一阶段目标架构，重点解决两件事：
> 1. 把目前埋在 backend `MethodCallResolver` / `PropertyAccessResolver` 中、未来前后端都需要复用的“元数据解析逻辑”抽到 `scope` 包。
> 2. 在 `scope` 包内建立真正可供 frontend 语义分析使用的作用域链模型，使 AST 语义分析可以对“每个会引入新作用域的节点”显式创建 `Scope`，并通过 parent 链完成标识符解析。

## 文档状态

- 状态：进行中（Phase 0 已完成，Phase 1+ 待实施）
- 更新时间：2026-03-06
- 适用范围：
  - `src/main/java/dev/superice/gdcc/scope/**`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/MethodCallResolver.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
  - 后续 `src/main/java/dev/superice/gdcc/frontend/sema/**`
- 关联文档：
  - `doc/module_impl/frontend_implementation_plan.md`
  - `doc/analysis/frontend_semantic_analyzer_research_report.md`
  - `doc/module_impl/call_method_implementation.md`
  - `doc/module_impl/load_store_property_implementation.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_low_ir.md`

---

## 1. 背景与问题

当前仓库已经有 `dev.superice.gdcc.scope` 包，但它本质上仍是“类/函数/属性/参数元数据接口 + `ClassRegistry` 全局注册表”的集合，还不是 frontend 语义分析可直接依赖的“作用域系统”。

与此同时，backend 中已经存在两段未来 frontend 也一定会复用的核心语义：

1. `MethodCallResolver`
   - 已实现对象/内建类型的方法候选收集、owner 距离比较、默认参数/vararg 兼容、`OBJECT_DYNAMIC` / `VARIANT_DYNAMIC` 回退边界。
2. `PropertyAccessResolver`
   - 已实现对象类型的继承链 owner 解析、属性 owner 分类、builtin/object 两条查询路径。

问题在于，这两套逻辑目前都放在 `backend.c.gen.insn` 包里，和 `CBodyBuilder`、`LirVariable`、`invalidInsn(...)`、receiver 渲染等 backend 专用概念耦合在一起，导致：

- frontend 无法直接复用，只能重新写一套近似逻辑；
- backend / frontend 容易形成两套候选筛选与成员解析规则；
- `ClassRegistry` 虽然已经是事实上的全局 metadata root，但没有统一 `Scope` 契约，frontend 很难自然建立“局部 -> 参数 -> capture -> 成员 -> 全局”的链式解析模型。

本次重构计划的目标，就是把这三个问题一次性拆清楚。

---

## 2. 已确认事实（来自现有代码与文档）

## 2.1 `ClassRegistry` 已经是全局语义根，但还不是 `Scope`

`ClassRegistry` 当前已经负责：

- builtin / engine / gdcc class 的注册与查询；
- utility function / global enum / singleton 的注册与查询；
- `ClassRegistry#checkAssignable(...)` 的全局可赋值语义；
- `getClassDef(...)`、`getVirtualMethods(...)` 等跨类查询；
- frontend M1 skeleton 注入后的跨脚本可见性。

这说明它天然适合作为 **global scope root**。但它目前缺少：

- parent scope 概念；
- 统一的 value/function lookup 入口；
- 与局部作用域/函数作用域/类作用域一致的查询协议。

## 2.2 `MethodCallResolver` 中真正可抽共享的部分

按当前实现与文档，对 frontend/backend 共用价值最大的并不是整个 `MethodCallResolver`，而是以下逻辑：

1. **receiver 静态类别判断**
   - known object
   - `Variant`
   - builtin receiver
2. **known object method 的继承链候选收集**
   - 沿 owner 层级向上找同名 method；
   - 记录 owner distance；
   - 区分 GDCC / ENGINE / BUILTIN owner 类别。
3. **builtin method metadata 读取与接收者名规范化**
   - 特别是 `Array` / `Dictionary` 这类 generic wrapper 的 lookup 名规范化。
4. **候选过滤与排序规则**
   - 参数数量 + 默认参数兼容；
   - `ClassRegistry#checkAssignable(...)` 类型兼容；
   - owner 距离优先；
   - 实例优先于 static；
   - non-vararg 优先于 vararg；
   - object receiver 歧义 -> `OBJECT_DYNAMIC`。

而以下内容 **不适合** 抽到 `scope` 包：

- `CBodyBuilder` / `invalidInsn(...)` 文本；
- `LirVariable` 层面的 receiver / result / arg 存在性与 ref 限制校验；
- `cFunctionName` 生成与 C 调用形态；
- dynamic path 的 pack/unpack 与临时变量管理；
- default literal 物化与 helper 调用。

## 2.3 `PropertyAccessResolver` 中真正可抽共享的部分

可供前后端共用的部分主要是：

1. **known object property 的 owner 解析**
   - 从 receiver type 对应的 `ClassDef` 开始；
   - 沿继承链查找 property owner；
   - 检测 missing metadata / inheritance cycle；
   - 区分 GDCC / ENGINE owner。
2. **builtin property metadata 查询**
   - builtin class 查找；
   - property 元数据存在性判断。

而以下内容仍应留在 backend：

- `renderReceiverValue(...)` / `renderOwnerReceiverValue(...)`；
- receiver 向 owner type 的 C 表示对齐；
- load/store 时与结果变量/值变量的类型兼容校验；
- 最终发射 `godot_Object_get/set`、builtin getter/setter、GDCC field 访问等 C 路径。

## 2.4 Godot 的直接启发：作用域链与成员表是两条并行轴线

从上游 Godot 代码可以确认两点：

1. `SuiteNode` 本身带 `parent_block`，`has_local(...)` / `get_local(...)` 会沿 `parent_block` 递归向上查找。
2. `ClassNode` 本身维护 `members_indices`，`GDScriptAnalyzer::reduce_identifier(...)` 的解析顺序也是“先局部，再成员，再全局”，并且 lambda capture 与 `use_self` 是在这套链上处理的。

这说明对 GDCC 来说，最自然的设计也应该是：

- **一条 lexical scope 链**：block / function / class / global；
- **一套成员解析 helper**：在 class metadata / receiver type 上做 method/property owner lookup。

也就是说：

> `Scope` 负责“无 base 的名字从哪里来”，共享 resolver 负责“有 receiver type 的成员从哪里来”。两者相关，但不能混成一个巨大的万能对象。

---

## 3. 目标架构

## 3.1 `Scope` 接口：只提供最小但稳定的名字解析协议

建议先引入一个最小版 `Scope`：

```java
public interface Scope {
    @Nullable Scope getParentScope();

    void setParentScope(@Nullable Scope parentScope);

    @Nullable ScopeValue resolveValueHere(@NotNull String name);

    @NotNull List<? extends FunctionDef> resolveFunctionsHere(@NotNull String name);

    default @Nullable ScopeValue resolveValue(@NotNull String name) {
        var value = resolveValueHere(name);
        if (value != null) {
            return value;
        }
        var parent = getParentScope();
        return parent != null ? parent.resolveValue(name) : null;
    }

    default @NotNull List<? extends FunctionDef> resolveFunctions(@NotNull String name) {
        var functions = resolveFunctionsHere(name);
        if (!functions.isEmpty()) {
            return functions;
        }
        var parent = getParentScope();
        return parent != null ? parent.resolveFunctions(name) : List.of();
    }
}
```

这里有两个关键约定：

1. `resolveValue(...)` 采用 **最近命中优先**。
2. `resolveFunctions(...)` 采用 **最近非空作用域层优先**，而不是把所有 parent 的同名函数简单拼接。

原因是：

- 对变量/字段/参数，典型语义就是 shadowing；
- 对函数，当前层若已经定义同名候选集，应先在这一层完成 overload 决议，再考虑更外层/global。

## 3.2 `ScopeValue`：不要把 `Object declaration` 直接暴露给所有调用方

建议同时引入一个最小 record，例如：

```java
public record ScopeValue(
        @NotNull String name,
        @NotNull GdType type,
        @NotNull ScopeValueKind kind,
        @Nullable Object declaration,
        boolean constant,
        boolean staticMember
) {
}
```

其中 `ScopeValueKind` 第一版建议至少覆盖：

- `LOCAL`
- `PARAMETER`
- `CAPTURE`
- `PROPERTY`
- `CONSTANT`
- `SINGLETON`
- `GLOBAL_ENUM`

注意：

- **`TYPE_META` 不建议在 `Scope` v1 强塞进 `resolveValue(...)`**。
- 当前 `doc/gdcc_type_system.md` 已明确项目没有显式建模 `TypeType`；因此 type/meta 名字空间更适合作为后续 `resolveTypeMeta(...)` 扩展，而不是一开始混进 value namespace。

这能避免第一版 `Scope` 因为同时处理 value/function/type 三个命名空间而过度复杂。

## 3.3 作用域实现建议

建议按下面的层次推进：

### 3.3.1 `ClassRegistry implements Scope`

作为 global scope root：

- `parentScope` 默认始终为 `null`；
- `resolveValueHere(...)` 至少处理：
  - singleton
  - global enum
- `resolveFunctionsHere(...)` 至少处理：
  - utility function

说明：

- `ClassRegistry` 当前是 `final class`，因此它不能继承抽象基类；
- 若后续引入 `AbstractScope` / `BaseScope` 共享 parent 字段实现，`ClassRegistry` 需要手动实现接口即可。

### 3.3.2 `ClassScope`

负责当前类上下文：

- 当前类属性 / 常量 / 信号 / 方法；
- 可按需要决定是否把继承链并入 `resolveValueHere(...)` / `resolveFunctionsHere(...)`；
- 推荐直接复用后文的 `ScopePropertyResolver` / `ScopeMethodResolver`，避免类作用域和 receiver member lookup 出现两套继承链逻辑。

### 3.3.3 `CallableScope`

负责函数 / 构造器 / lambda：

- 参数；
- capture；
- `self`（若未来决定将其视为显式 value binding）；
- 未来可扩展 ownerFunction / staticContext / loopDepth 等上下文。

### 3.3.4 `BlockScope`

负责块级局部：

- local variable
- for iterator
- match pattern bind
- 未来的临时 named binding

frontend 在 AST 语义分析时，可以对每个引入新局部作用域的节点创建一个新的 `BlockScope`，并设置 parent 为外层 scope。

## 3.4 共享 resolver：放在 `scope` 包，而不是 frontend/backend 任一侧

建议新增两个真正“中立”的 resolver：

### 3.4.1 `ScopeMethodResolver`

职责：

- 基于 `ClassRegistry` + receiver `GdType` + method name + arg `GdType` 列表；
- 收集 method candidates；
- 应用与 backend 当前一致的过滤/排序规则；
- 返回与 frontend/backend 都能消费的 metadata 结果。

建议最小输出模型：

```java
public record ScopeResolvedMethod(
        @NotNull ScopeOwnerKind ownerKind,
        @NotNull ClassDef ownerClass,
        @NotNull FunctionDef function,
        int ownerDistance,
        boolean dynamicFallbackAllowed
) {
}
```

其中：

- `dynamicFallbackAllowed` 仅表示“当前 receiver 类型下允许交给 caller 走 dynamic policy”；
- 它 **不负责** 决定 caller 最终发 `OBJECT_DYNAMIC` 还是 `VARIANT_DYNAMIC`，因为那取决于 caller 看到的 receiver 运行形态。

### 3.4.2 `ScopePropertyResolver`

职责：

- 基于 `ClassRegistry` + receiver `GdType` + property name；
- 解析 known object / builtin property owner；
- 返回 owner metadata；
- 区分“metadata 不可用”与“property 明确不存在”。

建议最小输出模型：

```java
public record ScopeResolvedProperty(
        @NotNull ScopeOwnerKind ownerKind,
        @NotNull ClassDef ownerClass,
        @NotNull PropertyDef property
) {
}
```

以及 builtin 路径可以单独保留 builtin lookup record，或统一通过 `ClassDef == ExtensionBuiltinClass` 建模。

### 3.4.3 支撑类型

建议同时新增：

```java
public enum ScopeOwnerKind {
    GDCC,
    ENGINE,
    BUILTIN
}
```

这样 frontend/backend 都不必再各自维护 owner 分类枚举。

## 3.5 明确边界：哪些东西不应该进入 `scope` 包

以下职责建议继续留在 backend 或 frontend：

1. **backend-only**
   - `renderReceiverValue(...)`
   - C 指针/包装对象表示转换
   - `invalidInsn(...)` 错误文本装配
   - `cFunctionName` / helper 调用名生成
   - dynamic call/property 的 pack/unpack
   - default literal 物化
2. **frontend-only**
   - AST side-table
   - `ResolvedSymbol` / `FrontendTypeFact` / `ResolvedCall` 等 frontend 语义结果
   - 诊断级 unsafe reason / fallback reason 文案
   - scopeByNode 建图与 AST 遍历顺序

一句话说：

> `scope` 包承载“名字和成员元数据如何被找到”，frontend/backend 各自承载“找到之后如何解释并消费”。

---

## 4. 推荐落地步骤

## 4.1 Step A：先补协议，不动现有后端行为

新增：

- `Scope`
- `ScopeValue`
- `ScopeValueKind`
- `ScopeOwnerKind`

并为 `ClassRegistry` 增加 `Scope` 实现，但不改变现有 `findType/findGlobalEnum/findSingletonType/findUtilityFunctionSignature` 等 API 的外部行为。

### 验收

- 新增 `ClassRegistryScopeTest`
  - singleton 能通过 `resolveValue(...)` 命中
  - global enum 能通过 `resolveValue(...)` 命中
  - utility function 能通过 `resolveFunctions(...)` 命中
  - `parentScope` 为 `null`

## 4.2 Step B：建立 frontend 真正需要的链式 scope 实现

新增：

- `ClassScope`
- `CallableScope`
- `BlockScope`

要求：

- 只做 metadata/lookup，不携带 AST 节点；
- AST -> Scope 的关联仍由 frontend side-table 维护；
- 每个 scope 都支持 parent 链递归查找；
- shadowing 规则可通过测试稳定固化。

### 验收

- `ScopeChainTest`
  - block local 覆盖 parameter
  - parameter 覆盖 class property
  - class member 覆盖 global singleton / utility
- `ScopeCaptureShapeTest`
  - lambda/callable scope 能显式放入 capture，并优先于 class/global

## 4.3 Step C：先抽 `ScopePropertyResolver`

优先抽 property，是因为它比 method 更简单，且当前 frontend 文档已经明确要求 known object missing property 与 dynamic fallback 分离。

抽取内容：

- owner hierarchy walk
- owner kind classification
- builtin property metadata lookup
- inheritance cycle / missing metadata 检查

保留在 backend 的内容：

- `renderReceiverValue(...)`
- result/value 变量的类型兼容校验
- load/store 最终发射路径

### 验收

- 新增 `ScopePropertyResolverTest`
- 现有 `PropertyAccessResolverTest` 继续通过，且主要变成 adapter 回归测试

## 4.4 Step D：再抽 `ScopeMethodResolver`

method 比 property 更复杂，因为它涉及 overload / default args / vararg / dynamic fallback 边界。

抽取内容：

- object/builtin candidate 收集
- owner distance
- 参数数量与默认参数兼容
- `ClassRegistry#checkAssignable(...)` 类型过滤
- static / vararg 优先级
- ambiguity 归类

保留在 backend 的内容：

- receiver/result/arg `LirVariable` 校验
- `ResolvedMethodCall` 中与 C 发射强相关的字段
- `OBJECT_DYNAMIC` / `VARIANT_DYNAMIC` 的最终 backend 形态

### 验收

- 新增 `ScopeMethodResolverTest`
- 现有 `CallMethodInsnGenTest` / `CallMethodInsnGenEngineTest` 继续通过
- 新增 parity 测试，保证 backend adapter 与 shared resolver 选出的 owner/function 一致

## 4.5 Step E：frontend 才开始真正消费 `Scope`

当前顺序建议是：

1. 先把 shared lookup 抽稳；
2. 再让 frontend binder/call/member analysis 基于 `Scope` 和 shared resolver 落地。

frontend 使用方式建议：

- `ClassRegistry` 作为 global root；
- 进入类时创建 `ClassScope(parent = classRegistry)`；
- 进入函数/构造器/lambda 时创建 `CallableScope(parent = classScope)`；
- 进入 block/if/while/match branch 时创建 `BlockScope(parent = outerScope)`；
- 每个 AST 节点对应的当前 scope 写入 `scopeByNode` side-table。

这样 AST 语义阶段才能自然支持：

- 局部遮蔽
- capture 识别
- 当前类成员查找
- singleton / utility / global enum 回退
- 未来的 inner class / annotation target / type meta 扩展

---

## 4.6 细化的分阶段执行草案

下面这版执行草案比 Step A-E 更细，目的是把“协议补齐、共享 resolver 抽取、frontend 接入”拆成可以按周或按 PR 批次推进的阶段，并给每阶段设置明确的完成门槛。

### 4.6.1 Phase 0：冻结现状与回归基线

**当前状态（2026-03-06）**

- 状态：已完成
- 结论：当前仓库的“共享查找逻辑”与“backend 专属发射逻辑”边界已经冻结，可作为后续 Phase 1+ 的行为基线。

**本阶段产出**

1. 已盘点并冻结当前主要入口与现有调用点：
   - `ClassRegistry`
     - 公共查询入口保持以 `findType(...)`、`findUtilityFunctionSignature(...)`、`findGlobalEnum(...)`、`findSingletonType(...)`、`getClassDef(...)`、`checkAssignable(...)` 为主；其中 `checkAssignable(...)` 明确冻结为后续 shared resolver 的类型兼容事实源。
   - `MethodCallResolver`
     - 当前共享抽取候选入口实际集中在 `resolve(...)` 内部的 `tryResolveKnownObjectMethod(...)`、`collectMethodCandidates(...)`、`chooseBestCandidate(...)`、`matchesArguments(...)`、`resolveOwnerDispatchMode(...)`、`normalizeBuiltinReceiverLookupName(...)`。
     - 当前生产调用点已冻结为 `src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`。
   - `PropertyAccessResolver`
     - 当前共享抽取候选入口实际集中在 `resolveObjectProperty(...)`、`resolveBuiltinProperty(...)`，以及 backend 专属的 `renderOwnerReceiverValue(...)`、`renderReceiverValue(...)`、`toOwnerObjectType(...)`。
     - 当前生产调用点已冻结为 `src/main/java/dev/superice/gdcc/backend/c/gen/insn/LoadPropertyInsnGen.java`、`src/main/java/dev/superice/gdcc/backend/c/gen/insn/StorePropertyInsnGen.java`，以及 `src/main/java/dev/superice/gdcc/backend/c/gen/insn/CallMethodInsnGen.java` 中的 receiver 渲染路径。
2. 已冻结 shared resolver 需要遵守的失败协议：
   - `metadata unknown`
     - method：对象接收者在 `ClassRegistry#getClassDef(...)` 无法取到 metadata 时，当前行为是返回 unresolved，随后由 caller 回落到 `OBJECT_DYNAMIC`。
     - property：对象接收者在 `ClassRegistry#getClassDef(...)` 无法取到 metadata 时，当前行为是返回 `null`，随后由 caller 走 `godot_Object_get/set` 的运行时路径。
   - `inheritance cycle / malformed metadata`
     - property：当前 `resolveObjectProperty(...)` 会 fail-fast，直接抛出 `invalidInsn(...)`。
     - method：当前 `collectMethodCandidates(...)` 对继承环仅停止向上遍历；Phase 1+ 不应偷偷改变这一行为，若未来要升级为显式 hard failure，必须在独立阶段连同 parity 测试一起调整。
   - `member missing`
     - method：已知对象缺失 method 时，当前行为冻结为 `OBJECT_DYNAMIC`，而不是强制先转 `Variant`。
     - property：已知对象缺失 property 时，当前行为冻结为 fail-fast，不做 backend 自动动态回退。
   - `ambiguous overload`
     - object receiver：当前 `MethodCallResolver` 在同优先级最佳候选冲突时返回 unresolved，caller 最终走 `OBJECT_DYNAMIC`。
     - builtin receiver：当前保持 compile-time hard failure，不做动态回退。
3. 已冻结本次重构的边界：
   - shared：只承载 metadata 查找、候选收集、owner 解析、参数兼容与排序规则。
   - backend：继续承载 `invalidInsn(...)` 文案装配、receiver 渲染、pack/unpack、默认值物化、最终 C 发射。
   - frontend：后续承载 AST side-table、诊断分类、`scopeByNode`、binding/call/member 语义解释。
4. 已建立并验证最小回归测试基线。

**目标**

- 在开始抽取前，先把当前 `scope` / backend resolver 的真实行为冻结成基线，避免后续重构时不知道哪里属于行为变化、哪里只是代码搬迁。

**具体任务**

1. 盘点现有 `ClassRegistry` / `MethodCallResolver` / `PropertyAccessResolver` 的公开入口与调用点。
2. 明确并记录 shared resolver 的失败协议：
   - metadata unknown
   - inheritance cycle / malformed metadata
   - member missing
   - ambiguous overload
3. 为后续 parity 重构列出必须保持不变的行为：
   - `ClassRegistry#checkAssignable(...)`
   - known object method -> `OBJECT_DYNAMIC`
   - known object missing property -> 当前 backend fail-fast
4. 建立最小回归测试清单，作为后续每一阶段的守门基线。

**验收标准**

- 文档中已有的行为边界与代码一致，不存在明显自相矛盾的描述。
- 以下现有测试被明确列为回归基线，并在后续每阶段保持通过：
  - `ClassRegistryTest`
  - `ClassRegistryGdccTest`
  - `PropertyAccessResolverTest`
  - `CallMethodInsnGenTest`
  - `CallMethodInsnGenEngineTest`
- `scope_architecture_refactor_plan.md` 中的 shared / backend / frontend 边界已经固定，不再在实施中反复改口。

**实际验收结果（2026-03-06）**

- 结果：通过。
- 已执行命令：

```powershell
.\gradlew.bat test \
  --tests dev.superice.gdcc.scope.ClassRegistryTest \
  --tests dev.superice.gdcc.scope.ClassRegistryGdccTest \
  --tests dev.superice.gdcc.backend.c.gen.insn.PropertyAccessResolverTest \
  --tests dev.superice.gdcc.backend.c.gen.CallMethodInsnGenTest \
  --tests dev.superice.gdcc.backend.c.gen.CallMethodInsnGenEngineTest \
  --no-daemon --info --console=plain
```

- 结果摘要：
  - `ClassRegistryTest`：通过
  - `ClassRegistryGdccTest`：通过
  - `PropertyAccessResolverTest`：通过
  - `CallMethodInsnGenTest`：通过
  - `CallMethodInsnGenEngineTest`：通过
- 基线结论：Phase 1 之前不得改变上述测试所覆盖的行为；若后续阶段必须调整行为，需要先在本节补充新的迁移说明与 parity 策略。

### 4.6.2 Phase 1：补齐 `Scope` 协议层

**目标**

- 先建立最小但稳定的 `Scope` 抽象，确保后续所有 scope 实现与 frontend 作用域树都能站在同一套协议之上。

**具体任务**

1. 新增：
   - `Scope`
   - `ScopeValue`
   - `ScopeValueKind`
   - `ScopeOwnerKind`
2. 固化 `Scope` 默认语义：
   - `resolveValue(...)` 最近命中优先
   - `resolveFunctions(...)` 最近非空层优先
3. 明确 `Scope` v1 只覆盖 value/function lookup，不纳入 `TYPE_META`。
4. 若需要共享 parent 字段实现，评估是否引入 `AbstractScope`；若无必要则延后，避免过早抽象。

**验收标准**

- 新增协议类后，现有生产代码行为不变。
- `Scope` 接口语义在文档中有稳定说明，不依赖调用方自行猜测。
- 新增最小协议测试（可合并进后续 `ClassRegistryScopeTest`），覆盖：
  - value lookup 递归规则
  - function lookup 递归规则
  - parent 为 `null` 时的空结果语义

### 4.6.3 Phase 2：让 `ClassRegistry` 成为真正的 global scope

**目标**

- 把 `ClassRegistry` 从“只有若干 findXxx 辅助方法”的全局注册表，提升为 frontend/backend 都能直接消费的 global scope root。

**具体任务**

1. 让 `ClassRegistry` 实现 `Scope`。
2. 在不破坏既有 API 的前提下，为其补充：
   - `getParentScope()`
   - `setParentScope(...)`
   - `resolveValueHere(...)`
   - `resolveFunctionsHere(...)`
3. `resolveValueHere(...)` 第一版至少覆盖：
   - singleton
   - global enum
4. `resolveFunctionsHere(...)` 第一版至少覆盖：
   - utility function
5. 保持 `findType(...)`、`findSingletonType(...)`、`findGlobalEnum(...)`、`findUtilityFunctionSignature(...)` 继续可用，避免一次性强迫所有调用方改造。

**验收标准**

- `ClassRegistry` 作为 `Scope` 使用时，仍保持 parent 为 `null` 的 global root 语义。
- 现有 `ClassRegistryTest` / `ClassRegistryGdccTest` 继续通过。
- 新增 `ClassRegistryScopeTest`，至少覆盖：
  - singleton 可通过 `resolveValue(...)` 命中
  - global enum 可通过 `resolveValue(...)` 命中
  - utility function 可通过 `resolveFunctions(...)` 命中
  - 未命中时返回空结果而不是抛异常

### 4.6.4 Phase 3：补齐 frontend 可用的链式 scope 实现

**目标**

- 在 `scope` 包中提供 frontend 真正会实例化的类作用域 / 可调用作用域 / 块级作用域，实现“按 AST 节点建 scope，再通过 parent 链递归解析”的模型。

**具体任务**

1. 新增：
   - `ClassScope`
   - `CallableScope`
   - `BlockScope`
2. 为这些作用域定义最小注册 API，例如：
   - `defineLocal(...)`
   - `defineParameter(...)`
   - `defineCapture(...)`
   - `defineProperty(...)`
   - `defineConstant(...)`
   - `defineFunction(...)`
3. 固化 shadowing 规则：
   - block local > parameter > capture > class member > global
4. 对 `CallableScope` 明确是否显式建模 `self`；若第一版不建模，也要写清由 caller 负责。
5. 保证这些 scope 实现本身不依赖 AST 节点，只负责 metadata 注册与查询。

**验收标准**

- 新增 `ScopeChainTest`，覆盖：
  - block local 覆盖 parameter
  - parameter 覆盖 class property
  - class scope 覆盖 global singleton / utility
- 新增 `ScopeCaptureShapeTest`，覆盖：
  - callable scope 中 capture 的优先级
  - 多层 callable / block parent 链查询
- 新增 `ClassScopeResolutionTest`，覆盖：
  - 当前类成员解析
  - 当前类优先于继承成员
  - 同名方法/属性在类作用域中的组织方式

### 4.6.5 Phase 4：抽取 `ScopePropertyResolver`

**目标**

- 先把 property owner lookup 从 backend 中抽出来，因为它逻辑更线性，也最容易形成 frontend/backend 的共享事实源。

**具体任务**

1. 新增 `ScopePropertyResolver`，承接：
   - known object property owner 解析
   - owner kind 分类
   - builtin property metadata 查询
   - inheritance cycle / missing metadata 识别
2. 设计 `ScopePropertyResolver` 的返回协议，区分：
   - 成功命中 owner + property
   - metadata unknown
   - property missing
   - hard failure（如 inheritance cycle）
3. 把 `PropertyAccessResolver` 改造成 backend adapter：
   - 共享 lookup 委托给 `ScopePropertyResolver`
   - backend 仅保留 receiver 渲染、类型校验、C 发射
4. 保持现有 fail-fast 文本尽量稳定，避免无必要的测试噪声。

**验收标准**

- 新增 `ScopePropertyResolverTest`，至少覆盖：
  - GDCC/ENGINE owner 解析
  - builtin property lookup
  - property missing
  - missing metadata
  - inheritance cycle
- 现有 `PropertyAccessResolverTest` 继续通过。
- `load_store_property_implementation.md` 中描述的长期事实仍然成立，不因抽取 shared resolver 而漂移。

### 4.6.6 Phase 5：抽取 `ScopeMethodResolver`

**目标**

- 在 property resolver 稳定后，再抽 method lookup 与候选排序，把最容易前后端漂移的规则集中到 `scope` 包。

**具体任务**

1. 新增 `ScopeMethodResolver`，承接：
   - known object/builtin method candidate 收集
   - builtin receiver lookup 名规范化
   - owner distance 计算
   - 参数数量 / 默认参数 / vararg 兼容判断
   - `ClassRegistry#checkAssignable(...)` 类型过滤
   - 实例优先 / non-vararg 优先 / object ambiguity 动态回退判定
2. 设计 method resolver 的返回协议，区分：
   - 成功命中唯一候选
   - 无静态候选但允许 dynamic fallback
   - ambiguity
   - hard failure（metadata malformed）
3. 把 `MethodCallResolver` 改造成 backend adapter：
   - shared candidate selection 委托给 `ScopeMethodResolver`
   - backend 仅保留 `LirVariable` 形状校验、C 发射字段组装、dynamic path 形态决策
4. 尽量保留现有 `ResolvedMethodCall` 结构，减少 backend 侧改动面。

**验收标准**

- 新增 `ScopeMethodResolverTest`，至少覆盖：
  - ownerDistance 选择
  - 默认参数兼容
  - vararg 兼容
  - ambiguity -> object dynamic / hard error 的分流
  - builtin receiver 查找规范化
- 现有 `CallMethodInsnGenTest` / `CallMethodInsnGenEngineTest` 继续通过。
- 新增 parity 测试，保证 backend adapter 与 shared resolver 选出的 owner/function 一致。

### 4.6.7 Phase 6：frontend 接入准备与最小试点

**目标**

- 在 shared resolver 与 scope 实现稳定后，先让 frontend 以最小试点方式接入，而不是一口气全量改完。

**具体任务**

1. 在 frontend 侧定义 `scopeByNode` side-table 使用约定。
2. 明确 AST -> Scope 的建图策略：
   - module/root -> `ClassRegistry`
   - class -> `ClassScope`
   - function/constructor/lambda -> `CallableScope`
   - block/if/while/match branch -> `BlockScope`
3. 先在 binder 原型中只接入无 base identifier 解析：
   - local / parameter / capture / class member / global
4. 再在 call/member analysis 中试点接入：
   - known object method -> `ScopeMethodResolver`
   - known object property -> `ScopePropertyResolver`
5. 保持 `TYPE_META` 仍由 `ClassRegistry` 的专门 API 处理，避免第一轮 frontend 接入过重。

**验收标准**

- frontend 计划文档与本重构文档的术语和职责边界保持一致。
- 新增 frontend 侧试点测试（可以先从 mock/最小 stub 开始）：
  - `FrontendScopeBindingTest`
  - `FrontendScopeCaptureTest`
  - `FrontendCallResolutionParityTest`
  - `FrontendDynamicPropertyFallbackTest`
- frontend 在试点阶段不需要完成完整 lowering，但必须能基于 `Scope` 链稳定产出绑定结论。

### 4.6.8 阶段切换门槛

为避免重构中途“半抽不抽”的状态长期存在，建议每阶段切换前都满足以下门槛：

1. 当前阶段新增的 targeted tests 全部通过。
2. 该阶段涉及的旧回归基线测试仍全部通过。
3. 文档与代码边界一致：
   - shared resolver 不依赖 backend 专用概念
   - backend adapter 不重新维护第二套排序/查找规则
4. 若某阶段引入新协议但 frontend 尚未消费，必须保证旧调用方仍可无迁移成本继续工作。

---

## 5. 对 frontend 的直接影响

## 5.1 绑定器不应再直接四处手写 `ClassRegistry` 分支

当前 frontend 计划中，binding 优先级虽然已经写清，但还没有一个真正的“协议层”承接。

引入 `Scope` 后，前端 binder 的基本策略应变成：

1. 先在当前 `Scope` 链中解 unqualified identifier；
2. 若语法上存在 receiver/base，再调用 `ScopeMethodResolver` / `ScopePropertyResolver`；
3. type/meta 名字空间暂时仍通过 `ClassRegistry` 的专门 API 处理；
4. 未来若 type/meta 需求增多，再扩展 `Scope` 为多命名空间接口。

## 5.2 `ClassRegistry` 仍然不能被直接当成“完整 binder”

即使它实现了 `Scope`，也只能说明：

- 它是 global root；
- 它能提供全局 value/function 元数据。

它依然 **不能单独代表**：

- lexical scope
- class local/member shadowing
- lambda capture
- block local 生命周期

所以 frontend 仍然需要独立的 `ClassScope` / `CallableScope` / `BlockScope`。

## 5.3 shared resolver 抽取后，frontend/backend 的边界会更清晰

抽取完成后，职责应变成：

- `scope`：元数据查找与候选选择
- `frontend`：语义解释 + 诊断 + side-table
- `backend`：C 发射 + runtime fallback 具体形态

这正好与分析报告中“不要在 lowering 阶段再猜一次语义”的原则一致。

---

## 6. 测试计划

建议新增测试组：

1. `ClassRegistryScopeTest`
   - global scope 能解析 singleton / global enum / utility
2. `ScopeChainTest`
   - parent 递归与 shadowing
3. `ClassScopeResolutionTest`
   - 类成员、继承成员、当前类优先级
4. `ScopePropertyResolverTest`
   - property owner 解析、missing metadata、inheritance cycle
5. `ScopeMethodResolverTest`
   - ownerDistance、default args、vararg、ambiguity、builtin receiver
6. `MethodResolverParityTest`
   - shared resolver 与 backend `MethodCallResolver` 结果一致
7. `PropertyResolverParityTest`
   - shared resolver 与 backend `PropertyAccessResolver` 结果一致
8. 后续 frontend 测试
   - `FrontendScopeBindingTest`
   - `FrontendScopeCaptureTest`
   - `FrontendCallResolutionParityTest`
   - `FrontendDynamicPropertyFallbackTest`

---

## 7. 风险与决策点

## 7.1 不要把 value/function/type 三个命名空间一次性塞进 `Scope`

这是最容易把第一版做崩的地方。

建议：

- `Scope` v1 只承载 value/function；
- `TYPE_META` 暂时继续由 `ClassRegistry` 专用 API 支持；
- 等 frontend binder 真正进入 `TYPE_META` 实现时，再决定是否扩展 `Scope`。

## 7.2 不要把 backend receiver 渲染逻辑错误地下沉到 `scope`

`renderReceiverValue(...)`、GDCC/ENGINE 指针表示转换、pack/unpack 等都不是 scope 概念。把它们放进 `scope` 会再次把前后端耦合起来。

## 7.3 不要让 shared resolver 直接依赖 `LirVariable`

一旦 shared resolver 以 `LirVariable` 为输入，frontend 仍然无法复用。shared resolver 应只依赖：

- `ClassRegistry`
- `ClassDef` / `FunctionDef` / `PropertyDef`
- `GdType`
- 简单的名字与参数类型列表

## 7.4 要先定义“错误 vs unresolved”的协议

shared resolver 至少要明确区分：

- receiver metadata 根本未知：caller 可选择 dynamic fallback
- hierarchy metadata 自相矛盾 / inheritance cycle / malformed signature：应视为 hard failure
- member 不存在：由 caller 决定是 fail-fast 还是 dynamic fallback

否则 frontend/backend 仍会各自解释同一种 lookup 失败。

---

## 8. 推荐实施顺序（结论）

1. 先定义 `Scope` / `ScopeValue` / `ScopeOwnerKind` 等协议；
2. 让 `ClassRegistry` 成为真正的 global scope；
3. 补齐 `ClassScope` / `CallableScope` / `BlockScope`；
4. 先抽 `ScopePropertyResolver`；
5. 再抽 `ScopeMethodResolver`；
6. 最后再让 frontend 绑定器与 body analyzer 全面切到 `Scope` 链。

一句话总结：

> 这次重构不应该被实现为“把 backend resolver 平移到 `scope` 包”，而应该实现为“在 `scope` 包建立统一的名字解析协议与共享成员查找能力，然后让 frontend/backend 分别站在这套协议之上消费结果”。

---

## 9. 参考事实源

- 本仓库：
  - `src/main/java/dev/superice/gdcc/scope/ClassRegistry.java`
  - `src/main/java/dev/superice/gdcc/scope/ClassDef.java`
  - `src/main/java/dev/superice/gdcc/scope/FunctionDef.java`
  - `src/main/java/dev/superice/gdcc/scope/PropertyDef.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/MethodCallResolver.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/PropertyAccessResolver.java`
  - `doc/module_impl/call_method_implementation.md`
  - `doc/module_impl/load_store_property_implementation.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_c_backend.md`
- Godot 参考：
  - `https://github.com/godotengine/godot/blob/master/modules/gdscript/gdscript_parser.h`
  - `https://github.com/godotengine/godot/blob/master/modules/gdscript/gdscript_parser.cpp`
  - `https://github.com/godotengine/godot/blob/master/modules/gdscript/gdscript_analyzer.cpp`
  - `https://github.com/godotengine/godot-docs/blob/master/tutorials/scripting/gdscript/static_typing.rst`
