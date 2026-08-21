# Frontend Global Constant Access Implementation

> Updated: 2026-08-21
> 本文档是「裸标识符访问全局枚举值、全局常量与 GDScript 语言预定义常量」的实施计划与事实源。
> 实施完成后本文档转为冻结合同，阶段性步骤记录以 git 历史为准。
>
> 进度：Step 1 已完成（2026-08-21，scope 层索引与解析扩展落地，`ClassRegistryScopeTest` 16 用例全绿）；Step 2 已完成（2026-08-21，body lowering 三分支物化 + `CFloatLiteralSupport` float 归一化落地，lowering/backend/sema 测试全绿）；Step 3 已完成（2026-08-21，表达式类型与遮蔽语义集成验证落地，全量回归通过）。

---

## 1. 维护合同

- 本文档覆盖 shared metadata（`ClassRegistry` 全局值命名空间）、frontend semantic（top binding / 表达式类型）、CFG/body lowering（裸标识符物化）与 backend literal 生成的长期合同。
- 冻结事实：
  - 全局枚举值、全局常量、GDScript 语言常量统一以 `ScopeValueKind.CONSTANT` 进入 value 命名空间，不引入新 `ScopeValueKind`。
  - 裸常量一律在 body lowering 物化为字面量指令（`LiteralIntInsn` / `LiteralFloatInsn`），不经过 `LoadStaticInsn`。
- 若本合同变化，至少同步更新：本文档、`frontend_rules.md`（MVP 支持约定）、`frontend_visible_value_resolver_implementation.md`、`scope_analyzer_implementation.md`、相关代码注释与测试锚点。

---

## 2. 现状与问题

### 2.1 引擎侧事实（Godot 4.5.1 溯源）

已对照 `godotengine/godot` 4.5.1-stable 源码与官方文档确认：

- 引擎在 `core/core_constants.cpp` 通过 `BIND_CORE_ENUM_CONSTANT` 等宏集中注册全局常量：同一名字同时写入扁平表 `_global_constants_map[name]`（裸访问）与分组表 `_global_enums[enum_name]`（限定访问）。后注册者覆盖先注册者（last-wins）。
- GDScript analyzer（`modules/gdscript/gdscript_analyzer.cpp`）对裸标识符调用 `CoreConstants::is_global_constant(name)`，直接命中扁平表，**无需 `Variant.Type.` 前缀**；`match typeof(x): TYPE_NIL` 是官方文档示例形态。
- GDExtension 转储（`core/extension/extension_api_dump.cpp`）按 `enum_name` 是否为空分流：`global_enums[]` 承载全部分组枚举成员（4.5.1 共 22 组），`global_constants[]` 承载无分组的独立常量（4.5.1 为空数组）。
- `src/main/resources/extension_api_451.json` 实测：`global_enums` 22 组、**512 个枚举值名全部唯一**（跨组零重名）；`Variant.Type` 组含 `TYPE_NIL=0 ... TYPE_MAX=39` 共 40 值。
- `PI` / `TAU` / `INF` / `NAN` 是 GDScript 语言级常量，由 `modules/gdscript/gdscript.cpp` `GDScriptLanguage::init()` 经 `_add_global(StringName("PI"), Math::PI)` 等硬编码注入，类型为 `float`，**不在 CoreConstants 与任何 GDExtension JSON 中**。
- `INT8/16/32/64_MIN/MAX`、`UINT8/16/32_MAX` 共 11 个极值常量是 Godot master（4.6+）新增的 `BIND_CORE_CONSTANT`（无分组，落在 `global_constants[]`）；**Godot 4.5.1 不存在这些常量**（4.5.1 `core_constants.cpp` 无任何 `BIND_CORE_CONSTANT`，4.5 官方文档 @GlobalScope 亦无此条目）。

### 2.2 gdcc 现状链路

裸标识符当前完整链路（已精读确认）：

1. `FrontendVisibleValueResolver.resolve`（`frontend/sema/resolver/FrontendVisibleValueResolver.java:55-122`）逐层 `resolveValueHere`，逃逸后落到全局 root `ClassRegistry.resolveValueHere`（`scope/ClassRegistry.java:284-316`）。
2. `ClassRegistry.resolveValueHere` 解析顺序固定为 `singletonByName` → `globalEnumByName`（key 为枚举名如 `"Variant.Type"`）→ `globalConstantByName`，均未命中返回 `NOT_FOUND`。**不存在枚举成员名的扁平索引**。
3. top binding（`FrontendBodyOwnerProcedures.publishScopeValueBinding:714-734`）将 `ScopeValue` 映射为 `FrontendBinding`（`toBindingKind:1402-1414`，`CONSTANT -> FrontendBindingKind.CONSTANT`），`declarationSite = resolvedValue.declaration()`。
4. 表达式类型（`FrontendExpressionSemanticSupport.resolveValueIdentifierExpressionType:1152-1183`）直接采用 `binding.resolvedValue().type()`。
5. body lowering（`FrontendOpaqueExprInsnLoweringProcessors` 的 identifier processor `:77-147`）`CONSTANT` 分支仅接受 `declarationSite instanceof ExtensionGlobalConstant` → `LiteralIntInsn`；其余 declaration 形态 `throw unsupportedSequenceItem`（`IllegalStateException`）。
6. 后端 `NewDataInsnGen` 已支持 `LiteralIntInsn`（`long` → `godot_int` 十进制，不截断）与 `LiteralFloatInsn`（`double` → `godot_float`）。

### 2.3 缺口

- 裸 `TYPE_NIL` / `OK` / `KEY_A` 等全局枚举成员：`resolveValueHere` NOT_FOUND → binding `UNKNOWN`，最终 unsupported / 无法编译。
- 裸 `PI` / `TAU` / `INF` / `NAN`：数据源缺失（不在 JSON），同上失败。
- `global_constants[]` 裸常量：loader 与注册链已就绪，但 4.5.1 JSON 为空，实际无可用条目；`INT*_MAX` 等 master 常量在 4.5.1 目标下缺失。
- 已支持且不受影响的对照面：限定式 `Variant.Type.TYPE_NIL`（chain `reduceGlobalEnumStaticLoad` → `load_static "Variant" "TYPE_NIL"`，后端 `LoadStaticInsnGen` 第三类分发）与裸 `@GlobalScope` global constant（`ExtensionGlobalConstant` → `LiteralIntInsn`，现有测试 `runLowersGlobalConstantIdentifierIntoInt64Literal` 锚定）。

---

## 3. 目标支持面

### 3.1 目标

| 类别 | 示例 | 数据来源 | 物化形态 | 表达式类型 |
|---|---|---|---|---|
| 全局枚举成员裸访问 | `TYPE_NIL`、`OK`、`KEY_A`、`SIDE_LEFT` | `global_enums[].values[]` 扁平索引 | `LiteralIntInsn`（`long`） | `int` |
| GDScript 语言常量 | `PI`、`TAU`、`INF`、`NAN` | 合成注册（对齐 `GDScriptLanguage::init`） | `LiteralFloatInsn`（`double`） | `float` |
| 独立全局常量 | JSON 提供时自动生效 | `global_constants[]` | `LiteralIntInsn`（现有路径） | `int` |
| 极值常量（含较早版本额外支持，已确认） | `INT8_MIN/MAX`、`INT16_MIN/MAX`、`INT32_MIN/MAX`、`INT64_MIN/MAX`、`UINT8_MAX`、`UINT16_MAX`、`UINT32_MAX` | 合成注册（值为 C++ 固定极限，永不变化） | `LiteralIntInsn`（现有路径） | `int` |

- 全部类别仅覆盖 executable body / property initializer 等既有 `EXECUTABLE_BODY` 可见域；作用域遮蔽规则不变（局部/类作用域命中先于全局 root，与 Godot 一致，例如 `var TYPE_NIL = 5` 合法遮蔽）。
- 极值常量在较早版本（含当前目标 4.5.1）的额外支持是**已确认的需求**（2026-08-20 由项目维护者批准）：尽管 Godot 4.5.1 引擎本身没有这些常量，gdcc 基于「值为 C++ 数值极限、永不变更、物化为纯整数字面量、对 4.5.1 运行时零依赖」的判断提前支持，并与 master 行为对齐。若未来升级 API dump（4.6+），JSON 自带同名条目时 **JSON 数据优先**，合成条目自动让位（见 4.3）。

### 3.2 非目标（Non-Targets）

- 限定式 `Variant.Type.TYPE_NIL` 等 chain 路径已支持，本计划不改变其行为。
- `match` 语句及其 pattern 中的常量引用：`match` 整体仍是 deferred boundary（`frontend_rules.md` MVP 约定），本计划不扩展。
- 类枚举成员的裸访问（如裸 `MOUSE_MODE_VISIBLE` 不带 `Input.` 前缀）：Godot 同样禁止，不支持。
- class constant 的收集与绑定：`frontend_rules.md` L72 已明确整体延后，本计划不涉及；`CONSTANT` 分支对非全局 declaration 保持 fail-fast。
- block-local `const` initializer、parameter default、`match` 等 deferred boundary 内使用裸全局常量：沿用既有 deferred 合同，不扩展。
- `store_static` / 对常量赋值：本就拒绝，不变。
- 不新增任何 frontend 诊断与 diagnostic owner：解析失败仍走现有 `NOT_FOUND -> UNKNOWN binding` 链路。

---

## 4. 设计合同

### 4.1 `ClassRegistry` 全局枚举值扁平索引

- 新增字段 `private final Map<String, ExtensionEnumValue> globalEnumValueByBareName = new HashMap<>()`（`scope/ClassRegistry.java` 字段区 L77-78 附近）。
- 构造函数（L96-118）中，扁平展开与组名登记**拆成两个独立步骤**，不得耦合同一个 `put` 分支（loader 允许 `ExtensionGlobalEnum.name() == null`，无名组的成员在引擎中同样进入 `_global_constants_map`，必须可解析）：

  ```java
  for (var ge : api.globalEnums()) {
      if (ge == null) continue;
      if (ge.name() != null) globalEnumByName.put(ge.name(), ge);      // 组名登记（现状不变）
      if (ge.values() == null) continue;
      for (var value : ge.values()) {                                  // 扁平索引：与组名是否为空无关
          if (value != null && value.name() != null) {
              globalEnumValueByBareName.put(value.name(), value);      // last-wins
          }
      }
  }
  ```

- 冲突策略：**last-wins**，对齐引擎 `_global_constants_map[name] = size - 1` 的覆盖语义；不发诊断。4.5.1 数据实测零重名，该策略仅为未来版本兜底。

### 4.2 `resolveValueHere` 解析顺序扩展

在现有 `singletonByName -> globalEnumByName -> globalConstantByName` 之后、`return notFound()` 之前，按序追加两级查询：

1. `globalEnumValueByBareName.get(name)` 命中 → `ScopeLookupResult.foundAllowed(new ScopeValue(name, GdIntType.INT, ScopeValueKind.CONSTANT, enumValue, true, false, false))`。
2. `gdScriptLanguageConstantByName.get(name)`（见 4.3）命中 → `foundAllowed(new ScopeValue(name, GdFloatType.FLOAT, ScopeValueKind.CONSTANT, gdScriptConstant, true, false, false))`。

- 顺序安全性（4.5.1 数据实测）：512 个枚举值名与 singleton 名（`Engine`/`Input` 等）、枚举名（`Variant.Type` 等）、JSON 全局常量名、`PI/TAU/INF/NAN` 均不交叉；追加级只扩大支持面，不改变任何既有命中。
- `resolveTypeMetaHere` 不变：枚举成员不进入 type-meta 命名空间，`Variant.Type` 枚举名解析保持不变。

### 4.3 合成 GDScript 语言常量与极值常量

- 新增 record `GdScriptLanguageConstant(String name, double value)`，放在 `gd.script.gdcc.scope` 包（它是语言级合成事实，不属于 GDExtension 元数据，不放入 `gdextension` 包）。
- `ClassRegistry` 新增字段 `private final Map<String, GdScriptLanguageConstant> gdScriptLanguageConstantByName = new HashMap<>()`，构造函数末尾注册 4 项：
  - `PI = 3.141592653589793`（`Math.PI`）
  - `TAU = 6.283185307179586`（`Math.TAU`）
  - `INF = Double.POSITIVE_INFINITY`
  - `NAN = Double.NaN`
- 极值常量复用现有 `ExtensionGlobalConstant(name, longValue, false)` 记录，直接注册进 `globalConstantByName`，**仅当 JSON 未提供同名条目时**（`putIfAbsent` 语义，JSON 优先）：
  - `INT8_MIN=-128`、`INT8_MAX=127`、`INT16_MIN=-32768`、`INT16_MAX=32767`、`INT32_MIN=-2147483648`、`INT32_MAX=2147483647`、`INT64_MIN=-9223372036854775808L`、`INT64_MAX=9223372036854775807L`、`UINT8_MAX=255`、`UINT16_MAX=65535`、`UINT32_MAX=4294967295L`。
  - 不提供 `UINT64_MAX` / `UINT*_MIN`（引擎同样不存在，且 `UINT64_MAX` 超出 Godot `int64` 值域）。
  - 注册处必须以 `///` 注释标明这些条目是「编译器合成的 Godot 4.6+/master 前向事实，非当前 JSON dump 数据」；`ExtensionGlobalConstant` record 的文档注释同步补充「可能承载编译器合成条目」。副作用声明：`isGlobalConstant("INT32_MAX")` 等查询将对合成条目返回 true，这是有意行为。
- 合成注册与既有命名空间的交叉重名 guard：构造期检查扁平枚举值表 ∩ 语言常量表 ∩ `globalConstantByName`（JSON+合成后）∩ `singletonByName` ∩ `globalEnumByName`（组名）两两无交叉；出现交叉即 `IllegalStateException`（metadata 破坏属 programmer error guard rail，符合 `frontend_rules.md` 恢复约定，不新增诊断）。4.5.1 数据下该 guard 恒不触发。

### 4.4 Binding 与表达式类型流通（零改动确认）

- `toBindingKind`（`FrontendBodyOwnerProcedures.java:1402-1414`）`CONSTANT -> FrontendBindingKind.CONSTANT` 已覆盖，无需修改。
- `publishScopeValueBinding:714-734` 自动携带新 declaration 与 `resolvedValue`，无需修改。
- 表达式类型（`FrontendExpressionSemanticSupport.java:1162-1183`）采用 `resolvedValue().type()`：枚举值/极值常量自动为 `int`，`PI/TAU/INF/NAN` 自动为 `float`，无需修改。
- `FrontendDualRoleTypeMetaRouteSupport.shouldPreferGlobalEnumTypeMeta:101-117` 只对 `ScopeValueKind.GLOBAL_ENUM`（枚举名本身）生效；新增 `CONSTANT` 命中不影响 dual-role 判定（无名字同时是枚举名与枚举值名）。
- CFG builder 对裸常量保持 `OpaqueExprValueItem` 表面（现有测试 `buildExecutableBodyKeepsGlobalConstantIdentifierOnOpaqueValueSurface` 锚定），无需修改。

### 4.5 Body lowering 物化扩展

`FrontendOpaqueExprInsnLoweringProcessors` 的 identifier processor `CONSTANT` 分支（`:131-139`）由单一 `instanceof ExtensionGlobalConstant` 扩展为三分支，命中即 `return block`：

1. `declarationSite instanceof ExtensionGlobalConstant globalConstant` → `LiteralIntInsn(resultSlotId, globalConstant.value())`（现有，含合成极值常量）。
2. `declarationSite instanceof ExtensionEnumValue enumValue` → `LiteralIntInsn(resultSlotId, enumValue.value())`（新增）。
3. `declarationSite instanceof GdScriptLanguageConstant languageConstant` → `LiteralFloatInsn(resultSlotId, languageConstant.value())`（新增）。

其余 declaration 形态继续 `throw unsupportedSequenceItem(item, "constant binding is not supported ...")`（class constant / block-local const 等 deferred 面保持 fail-fast 不变）。

### 4.6 后端 float 字面量归一化（本计划唯一触及后端的点，Step 2 必做项）

- `NewDataInsnGen`（`backend/c/gen/insn/NewDataInsnGen.java:44-69`）已处理 `LITERAL_INT`（`Long.toString` → `godot_int`，不截断）与 `LITERAL_FLOAT`，`validateResultType:163-177` 校验目标类型。
- **已核实的事实**：`LiteralFloatInsn` 当前经 `CBodyBuilder.assignExpr` 直接嵌入 `Double.toString(value)`（`NewDataInsnGen.java:50-51`）；`Double.toString(Double.POSITIVE_INFINITY)` / `Double.toString(Double.NaN)` 产出 `"Infinity"` / `"NaN"`，**不是合法 C 字面量**。因此支持 `INF` / `NAN` 裸常量必须同步修复 float 物化，不允许「零改动冻结现状」。
- `CBuiltinBuilder.normalizeFloatLiteral`（`CBuiltinBuilder.java:742-760`）是 private，且只按**源文字符串**映射 `"inf"/"+inf"/"-inf"`，不识别 `"Infinity"`/`"NaN"`，不能直接复用。
- 修复合同（单一事实源，不做第二套实现）：
  - 从 `CBuiltinBuilder` 抽出 float 字面量归一化 helper `src/main/java/gd/script/gdcc/backend/c/gen/CFloatLiteralSupport.java`（`public final` + `public static` 方法）。**必须是 `public`**：调用方 `CBuiltinBuilder` 在 `gd.script.gdcc.backend.c.gen`，`NewDataInsnGen` 在 `gd.script.gdcc.backend.c.gen.insn`，Java 包可见性不跨层级，package-private 无法共享（同目录已有 public 先例 `CBodyBuilderAliasSafetySupport`）。该 helper 同时承载两种输入形态：
    - **IEEE double 值分类**（供 `NewDataInsnGen` 的 `LiteralFloatInsn` 分支）：`Double.POSITIVE_INFINITY -> "godot_inf"`、`Double.NEGATIVE_INFINITY -> "-godot_inf"`、`Double.isNaN -> "NAN"`（`<math.h>` 宏，既有 include 已覆盖）、其余 `Double.toString(value)`。
    - **源文字符串分类**（供 `CBuiltinBuilder` 现有 `normalizeFloatLiteral` 路径）：在 `"inf"/"+inf"/"-inf"` 基础上补充 `"infinity"/"+infinity"/"-infinity"/"nan"` 映射，避免 load_static 与 literal 两条路径分叉。
  - 抽取后 `CBuiltinBuilder.isInfinityLiteral` 语义变宽（连 `"nan"` 一起命中），实施时顺手更名为 `isNonFiniteFloatLiteral` 并更新既有调用点。
  - `godot_inf` 宏已存在于 `src/main/c/codegen/include_451/godot/godot_builtin_types.h:21`（`#define godot_inf INFINITY`，同文件已 `#include <math.h>`）；`NAN` 直接用 `<math.h>` 标准宏，不新增 `godot_nan`（避免改动绑定头文件）。
- `CNewDataInsnGenTest` 必须断言归一化后的 C 文本（`godot_inf` / `NAN`），禁止断言 `"Infinity"` / `"NaN"` 原文。

### 4.7 遮蔽与可见性规则（不变）

- `FrontendVisibleValueResolver.resolve` 逐层查询 Block/Callable scope 后才逃逸到全局 root；局部 `var`/`const`/参数、`for` iterator、capture 命中均先于全局常量，天然对齐 Godot「局部遮蔽全局」语义。
- `FOUND_BLOCKED` 与 declaration-order 过滤合同不变；全局常量 `constant=true, writable=false`，赋值左值链路的既有拒绝路径不受影响。

---

## 5. 分步骤实施

### Step 1：scope 层索引与解析扩展（已完成 2026-08-21）

改动文件：

- 新增 `src/main/java/gd/script/gdcc/scope/GdScriptLanguageConstant.java`（record，`name`/`double value`，`@NotNull` 注解按现有风格）。
- `src/main/java/gd/script/gdcc/scope/ClassRegistry.java`：
  - 字段区新增 `globalEnumValueByBareName` 与 `gdScriptLanguageConstantByName`（附 `///` 文档注释，说明 last-wins 与合成来源）。
  - 构造函数：枚举值展开、`PI/TAU/INF/NAN` 注册、极值常量 `putIfAbsent` 注册、交叉重名 guard。
  - `resolveValueHere`：按 4.2 追加两级查询。
  - 新增只读查询方法 `findGlobalEnumValueByBareName(String)` / `findGdScriptLanguageConstant(String)`（`@Nullable` 返回，命名遵循 `findXxx` 既有风格）。

验收（本步完成标准）：

- 新增/扩展 `ClassRegistryScopeTest` 用例并全部通过：
  - 裸 `TYPE_NIL` -> `CONSTANT` + `GdIntType.INT` + declaration 为 `ExtensionEnumValue(0)`；
  - 裸 `OK`（`Error` 组）、`KEY_A`（`Key` 组）、`SIDE_LEFT`（`Side` 组）跨组抽样同上；
  - 裸 `PI` -> `CONSTANT` + `GdFloatType.FLOAT` + `assertEquals(Math.PI, ...)`；`TAU` 同理；`INF` 用 `assertEquals(Double.POSITIVE_INFINITY, ...)`；**`NAN` 必须用 `assertTrue(Double.isNaN(...))`**（`Double.NaN != Double.NaN`，`assertEquals(Double.NaN, ...)` 会假失败）；
  - 裸 `INT32_MAX` -> `CONSTANT` + `int` + `2147483647`；`INT64_MIN` 保持 `long` 负值不截断；
  - 枚举名 `Variant.Type` 仍解析为 `GLOBAL_ENUM`（回归）；未知名仍 `NOT_FOUND`（回归）；
  - 扁平索引唯一性回归：默认 API 的全部枚举值名插入 `HashSet`，重复即失败；并断言与 singleton 名、枚举组名、语言常量名、`global_constants` 名两两无交（把「512 名全唯一」从一次性叙述固化为回归）；
  - 无名组兜底：用手写 fixture（`ExtensionGlobalEnum(null, false, List.of(new ExtensionEnumValue("GDCC_TEST_ORPHAN", 7L)))`）验证无名组成员仍可裸解析。
- 运行命令：`./gradlew test --tests ClassRegistryScopeTest --no-daemon --info --console=plain`。

### Step 2：body lowering 物化扩展与后端 float 字面量归一化（已完成 2026-08-21）

改动文件：

- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendOpaqueExprInsnLoweringProcessors.java`：按 4.5 扩展 `CONSTANT` 分支。
- `src/main/java/gd/script/gdcc/backend/c/gen/CBuiltinBuilder.java` + 新增 `src/main/java/gd/script/gdcc/backend/c/gen/CFloatLiteralSupport.java`（**public** helper，两调用方分属 `backend.c.gen` 与 `backend.c.gen.insn` 不同包）：按 4.6 落地 float 字面量归一化（**必做项**，非条件性验证）；`NewDataInsnGen` 的 `LiteralFloatInsn` 分支改为经该 helper 分类输出。

验收（本步完成标准）：

- 新增 `FrontendLoweringBodyInsnPassTest` 用例（复用 `createGlobalConstantFixtureApi` 夹具模式与 `requireOnlyInstruction` 断言风格）并全部通过：
  - 裸 `TYPE_NIL` 返回 -> 唯一 `LiteralIntInsn(0)` 且无 diagnostics error；
  - 裸 `PI` 返回 -> 唯一 `LiteralFloatInsn(Math.PI)`；
  - 裸 `INT64_MAX` -> `LiteralIntInsn` 保留 int64 宽度；
  - lowering 测试只断言 insn opcode + payload，不夹带 `expressionTypes` 断言（见 Step 3 归属）。
- 新增 `CNewDataInsnGenTest` 用例并全部通过：`LiteralFloatInsn(Double.POSITIVE_INFINITY)` -> `godot_inf`、`LiteralFloatInsn(Double.NaN)` -> `NAN`、`LiteralFloatInsn(Double.NEGATIVE_INFINITY)` -> `-godot_inf`；禁止断言 `"Infinity"`/`"NaN"` 原文。
- 负向用例（未知名如 `TYPE_WHATEVER`）**不放在** `FrontendLoweringBodyInsnPassTest`：现状链路是 top binding 发 `sema.binding` error（`Unable to resolve value binding ...`）+ 发布 `FrontendBindingKind.UNKNOWN`，compile-only gate 因 error 拦截进入 lowering；body pass 若收到 `UNKNOWN` 会 `unsupportedSequenceItem` fail-fast（`IllegalStateException`），这是实现不变量保护而非用户可见恢复路径。负向验收按 `frontend_rules.md` 测试约定锚定在 sema / compile-check 层（如 top-binding 或 compile-check 既有测试类）：
  1. `TYPE_WHATEVER` 用点产生 `sema.binding` error 且 binding 为 `UNKNOWN`；
  2. 同 module 其它合法语句仍正常分析与发布 facts；
  3. compile-only 入口因 error 不进入 lowering。
- 运行命令：
  - `./gradlew test --tests FrontendLoweringBodyInsnPassTest --no-daemon --info --console=plain`
  - `./gradlew test --tests CNewDataInsnGenTest --no-daemon --info --console=plain`

实施记录：

- lowering 三用例落在 `FrontendLoweringBodyInsnPassTest`（`runLowersBareGlobalEnumValueIdentifierIntoIntLiteral` / `runLowersBareGdScriptLanguageConstantIdentifierIntoFloatLiteral` / `runLowersBareExtremeConstantIdentifierIntoInt64Literal`），直接复用默认 API registry（`TYPE_NIL` 来自真实 `globalEnums`，`PI` / `INT64_MAX` 来自 Step 1 合成注册）。
- 负向三件套拆分落点：`FrontendBodyOwnerProceduresExprTypeTest.analyzePublishesUnknownBindingForUnresolvedBareIdentifierWhileSiblingFactsSurvive`（1+2：`sema.binding` error + `UNKNOWN` + 兄弟 `OK` 事实存活）与 `FrontendCompileCheckAnalyzerTest.analyzeForCompileKeepsUnresolvedBareIdentifierAsUpstreamBindingErrorBlockingLowering`（3：upstream error 拥有 anchor、无重复 `sema.compile_check`、`hasErrors` 阻断 lowering）。
- 审阅补强：新增 `CFloatLiteralSupportTest` 直接锚定 4.6 源文分类合同（`infinity` / `nan` 家族映射、大小写/空白归一、普通数字透传、有限 IEEE 值渲染），与 `CNewDataInsnGenTest` 的 IEEE 端到端断言互补不重复。

### Step 3：表达式类型与遮蔽语义集成、全量回归（已完成 2026-08-21）

实施记录：

- 表达式类型断言落在 `FrontendBodyOwnerProceduresExprTypeTest.analyzePublishesBareGlobalConstantExpressionTypes`（`PI -> float`、`TYPE_NIL -> int`，证明 4.4 零改动成立）。
- 遮蔽语义断言落在 `FrontendVisibleValueResolverTest.resolvePrefersCallableLocalShadowingBareGlobalEnumValue`（`var TYPE_NIL = 5` 后引用解析为 `LOCAL_VAR`，无冲突诊断，`findSameCallableConflict` 不达全局命名空间）。
- 全量回归 `./gradlew clean build`：3227 用例中计划列名的既有基线与全部新增用例全绿；引擎集成测试 `IndexStoreInsnGenEngineTest` / `LoadStorePropertyInsnGenEngineInheritanceTest` 在全量构建高负载下偶发 `AccessDeniedException`（`test_project/bin` 内前一测试的 DLL 尚未被其后台清理虚拟线程释放），单独/顺序重跑稳定通过，与本计划改动无关（失败点在 `prepareProject` 文件删除，早于任何 codegen 逻辑）。

改动文件：无（纯验证步）；若 Step 1/2 暴露类型流通问题再定点修复。

验收（本步完成标准）：

- 表达式类型断言放在 **sema 层测试类**（如 expression/variable analyzer 既有测试类），**不放进** `FrontendLoweringBodyInsnPassTest`（该类的 `expressionTypes()` 多为注入而非断言对象）：
  - `PI` 用点 `expressionTypes` 为 `GdFloatType.FLOAT`；`TYPE_NIL` 用点为 `GdIntType.INT`（证明 4.4 零改动成立）。
- 遮蔽语义用例放在 variable / visible-value 相关 sema 测试类：函数内 `var TYPE_NIL = 5` 后引用 `TYPE_NIL` 解析为 `LOCAL_VAR` 而非全局常量（`findSameCallableConflict` 只看 callable 内，不会把全局 `CONSTANT` 误判为冲突）。
- 全量回归：`./gradlew clean build --no-daemon --info --console=plain` 全绿（重点既有基线：`ClassRegistryScopeTest`、`FrontendLoweringBodyInsnPassTest`、`FrontendCfgGraphBuilderTest`、`CLoadStaticInsnGenTest`、`CNewDataInsnGenTest`、`ExtensionApiLoaderMetadataTest`）。
- `get_file_problems` 对所有改动文件无 error/warning 残留。

---

## 6. 验收细则汇总

### 6.1 新增单元测试锚点

| 层 | 测试类 | 锚点内容 |
|---|---|---|
| scope | `ClassRegistryScopeTest` | 裸枚举值/语言常量/极值常量解析、类型与 declaration、枚举名回归、NOT_FOUND 回归、扁平名唯一性与跨命名空间无交、无名组兜底 |
| frontend lowering | `FrontendLoweringBodyInsnPassTest` | `TYPE_NIL -> LiteralIntInsn(0)`、`PI -> LiteralFloatInsn`、`INT64_MAX` 宽度 |
| frontend sema | top-binding / expression / variable analyzer 既有测试类 | 未知名 `sema.binding` error + `UNKNOWN` + 同 module 其它语句存活（负向）；`PI -> float`、`TYPE_NIL -> int` 表达式类型；局部遮蔽 |
| backend | `CNewDataInsnGenTest` | `INF -> godot_inf`、`NAN -> NAN`、`-INF -> -godot_inf`，禁止 `"Infinity"`/`"NaN"` 原文 |

### 6.2 既有回归基线（不得变红）

`ClassRegistryScopeTest`、`FrontendLoweringBodyInsnPassTest`、`FrontendCfgGraphBuilderTest`（opaque 表面合同）、`CLoadStaticInsnGenTest`（限定式 load_static 不受影响）、`CNewDataInsnGenTest`、`ExtensionApiLoaderMetadataTest`（loader 未动）。

### 6.3 文档同步清单

- 本文档：实施完成后将 Step 状态转为冻结事实。
- `frontend_rules.md` MVP 支持约定：追加一条「全局枚举成员、全局常量与 GDScript 语言常量（`PI/TAU/INF/NAN`、极值常量）的裸访问已进入 compile-ready 支持面；`match` pattern 等 deferred boundary 内使用仍保持 deferred」。
- `frontend_visible_value_resolver_implementation.md` 与 `scope_analyzer_implementation.md`：在全局值解析章节补充 `ClassRegistry` 五级解析顺序与新索引合同。
- `diagnostic_manager.md`：无新诊断，无需更新（显式声明）。

---

## 7. 风险与边界

- **极值常量的版本偏差**：gdcc 目标 API 为 4.5.1，而 `INT*_MAX` 家族是 master 新增；在较早版本额外支持它们是已确认的需求（见 3.1）。残余风险仅是用户代码在 4.5.1 Godot 编辑器内不可移植（反向可移植）。缓解：文档显式声明；值为纯字面量无运行时依赖；JSON 优先策略保证未来 dump 升级后自动对齐。
- **未来版本重名**：若未来 Godot 版本出现跨组重名枚举值，last-wins 与引擎 `_global_constants_map` 覆盖语义一致；但 JSON 数组顺序是否严格等于注册顺序依赖 `extension_api_dump.cpp` 按索引顺序输出（当前成立）。升级 API dump 时必须重跑重名检测（已固化为 `ClassRegistryScopeTest` 唯一性回归，见 Step 1 验收）。
- **`INF`/`NAN` C 文本边界**：`Double.toString` 对非有限值产出非法 C 文本，float 归一化是 Step 2 必做项（合同见 4.6）；归一化收敛在共享 `CFloatLiteralSupport`（public，跨 `backend.c.gen` / `backend.c.gen.insn` 两包调用）单一入口，`CBuiltinBuilder` 与 `NewDataInsnGen` 共同复用，不得出现第二套平行实现。
- **`globalEnums` name 可空**：`ExtensionApiLoader.parseGlobalEnums` 允许 `name == null` / `value.name() == null`；扁平展开与组名登记已解耦（4.1），无名组成员照常进入扁平索引，仅跳过 `value.name() == null` 的项。
- **与 dual-role singleton/type-meta 的交互**：新增命中全部为 `CONSTANT` kind，不参与 `GLOBAL_ENUM`/`TYPE_META` 路由判定；已确认无名字交叉，无需扩展 `tryApplyDualRoleTypeMetaBias`。
