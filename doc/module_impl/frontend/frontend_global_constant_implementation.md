# Frontend Global Constant Access Implementation

> Updated: 2026-08-21
>
> 本文档是「裸标识符访问全局枚举值、全局常量与 GDScript 语言预定义常量」的事实源。
> 不再记录阶段性步骤、完成进度或实施流水账；若合同变化，应直接改写当前状态。

## 1. 维护合同

- 本文档覆盖 shared metadata（`ClassRegistry` 全局值命名空间）、frontend semantic（top binding / 表达式类型）、CFG/body lowering（裸标识符物化）与 backend literal 生成的长期合同。
- 本文档只描述已经冻结并由代码实现承担的事实，不描述历史修复步骤。
- 冻结事实：
  - 全局枚举值、全局常量、GDScript 语言常量统一以 `ScopeValueKind.CONSTANT` 进入 value 命名空间，不引入新 `ScopeValueKind`。
  - 裸常量一律在 body lowering 物化为字面量指令（`LiteralIntInsn` / `LiteralFloatInsn`），不经过 `LoadStaticInsn`。
- 若以下任一事实发生变化，至少要同步更新：
  - 本文档
  - `frontend_rules.md`
  - `frontend_visible_value_resolver_implementation.md` §5
  - `scope_analyzer_implementation.md` §4.1
  - `doc/module_impl/backend/builtin_builder_implementation.md`（`CFloatLiteralSupport` 单一事实源）
  - 与 `ClassRegistry` 全局值命名空间、`GdScriptLanguageConstant`、body identifier lowering、`CFloatLiteralSupport` 直接相关的代码注释与测试锚点
- `diagnostic_manager.md`：本表面不新增 frontend 诊断与 diagnostic owner，解析失败仍走既有 `NOT_FOUND -> UNKNOWN binding` 链路。

---

## 2. 当前支持面

frontend 当前正式支持的裸全局常量 surface 包括：

| 类别 | 示例 | 数据来源 | 物化形态 | 表达式类型 |
|---|---|---|---|---|
| 全局枚举成员裸访问 | `TYPE_NIL`、`OK`、`KEY_A`、`SIDE_LEFT` | `global_enums[].values[]` 扁平索引 | `LiteralIntInsn`（`long`） | `int` |
| GDScript 语言常量 | `PI`、`TAU`、`INF`、`NAN` | 合成注册（对齐 `GDScriptLanguage::init`） | `LiteralFloatInsn`（`double`） | `float` |
| 独立全局常量 | JSON 提供时自动生效 | `global_constants[]` | `LiteralIntInsn` | `int` |
| 极值常量 | `INT8_MIN/MAX`、`INT16_MIN/MAX`、`INT32_MIN/MAX`、`INT64_MIN/MAX`、`UINT8_MAX`、`UINT16_MAX`、`UINT32_MAX` | 合成注册（值为 C++ 固定极限） | `LiteralIntInsn` | `int` |

- 全部类别仅覆盖 executable body / property initializer 等既有 `EXECUTABLE_BODY` 可见域。
- 作用域遮蔽规则不变：局部 / 类作用域命中先于全局 root，与 Godot 一致（例如 `var TYPE_NIL = 5` 合法遮蔽）。
- 极值常量在当前目标 API（Godot 4.5.1）上的额外支持是已确认需求：值为 C++ 数值极限、永不变更、物化为纯整数字面量、对 4.5.1 运行时零依赖。若未来升级 API dump（4.6+）且 JSON 自带同名条目，**JSON 数据优先**，合成条目自动让位（见 §3.2）。

### 2.1 当前覆盖的典型示例

- 裸 `TYPE_NIL` / `OK` / `KEY_A` / `SIDE_LEFT` 作为全局枚举成员常量
- 裸 `PI` / `TAU` / `INF` / `NAN` 作为 GDScript 语言常量
- 裸 `INT32_MAX` / `INT64_MIN` / `UINT32_MAX` 作为合成极值常量
- 函数内 `var TYPE_NIL = 5` 后引用 `TYPE_NIL` 解析为局部变量

### 2.2 不属于当前合同

- 限定式 `Variant.Type.TYPE_NIL` 等 chain 路径已支持，本表面不改变其 `load_static` 行为。
- `match` pattern 中的全局常量/枚举裸访问：`match` 已进入 shared semantic，LITERAL / EXPRESSION 叶子走普通 `EXPRESSION` 合同（本表面的 `CONSTANT` binding 在 pattern 位置同样生效）。互通说明见 `frontend_match_statement_implementation.md`。
- 类枚举成员的裸访问（如裸 `MOUSE_MODE_VISIBLE` 不带 `Input.` 前缀）：Godot 同样禁止，不支持。
- class constant 的收集与绑定：整体延后；`CONSTANT` 分支对非全局 declaration 保持 fail-fast。
- block-local `const` initializer、parameter default 等 deferred boundary 内使用裸全局常量：沿用既有 deferred 合同，不扩展。
- `store_static` / 对常量赋值：本就拒绝，不变。
- 不新增任何 frontend 诊断与 diagnostic owner。
- 不提供 `UINT64_MAX` / `UINT*_MIN`（引擎同样不存在，且 `UINT64_MAX` 超出 Godot `int64` 值域）。

---

## 3. ClassRegistry 全局值命名空间

### 3.1 引擎侧事实（Godot 4.5.1）

对照 `godotengine/godot` 4.5.1-stable 源码与官方文档：

- 引擎在 `core/core_constants.cpp` 通过 `BIND_CORE_ENUM_CONSTANT` 等宏集中注册全局常量：同一名字同时写入扁平表 `_global_constants_map[name]`（裸访问）与分组表 `_global_enums[enum_name]`（限定访问）。后注册者覆盖先注册者（last-wins）。
- GDScript analyzer 对裸标识符调用 `CoreConstants::is_global_constant(name)`，直接命中扁平表，**无需 `Variant.Type.` 前缀**。
- GDExtension 转储按 `enum_name` 是否为空分流：`global_enums[]` 承载全部分组枚举成员（4.5.1 共 22 组），`global_constants[]` 承载无分组的独立常量（4.5.1 为空数组）。
- `src/main/resources/extension_api_451.json`：`global_enums` 22 组、512 个枚举值名全部唯一（跨组零重名）；`Variant.Type` 组含 `TYPE_NIL=0 ... TYPE_MAX=39` 共 40 值。
- `PI` / `TAU` / `INF` / `NAN` 是 GDScript 语言级常量，由 `GDScriptLanguage::init()` 经 `_add_global` 硬编码注入，类型为 `float`，不在 CoreConstants 与任何 GDExtension JSON 中。
- `INT8/16/32/64_MIN/MAX`、`UINT8/16/32_MAX` 共 11 个极值常量是 Godot master（4.6+）新增的无分组 `BIND_CORE_CONSTANT`；Godot 4.5.1 引擎本身不存在这些常量。

### 3.2 元数据与合成注册

`ClassRegistry` 是全局 root 的 value 命名空间唯一 owner。相关字段：

- `globalEnumByName`：枚举组名（如 `"Variant.Type"`）→ `ExtensionGlobalEnum`
- `globalConstantByName`：JSON `global_constants[]` + 合成极值常量 → `ExtensionGlobalConstant`
- `globalEnumValueByBareName`：全部全局枚举组成员的裸名扁平索引 → `ExtensionEnumValue`
- `gdScriptLanguageConstantByName`：合成 GDScript 语言常量 → `GdScriptLanguageConstant`
- `singletonByName`：engine singleton 名

扁平展开与组名登记必须拆成两个独立步骤，不得耦合同一个 `put` 分支。loader 允许 `ExtensionGlobalEnum.name() == null`，无名组的成员在引擎中同样进入 `_global_constants_map`，必须可裸解析；仅跳过 `value.name() == null` 的项。重名按引擎 last-wins 覆盖，不发诊断。

`GdScriptLanguageConstant(String name, double value)` 放在 `gd.script.gdcc.scope` 包（语言级合成事实，不属于 GDExtension 元数据，不放入 `gdextension` 包）。构造期注册：

- `PI = Math.PI`
- `TAU = Math.TAU`
- `INF = Double.POSITIVE_INFINITY`
- `NAN = Double.NaN`

极值常量复用 `ExtensionGlobalConstant(name, longValue, false)`，以 `putIfAbsent` 写入 `globalConstantByName`，**仅当 JSON 未提供同名条目时**：

- `INT8_MIN=-128`、`INT8_MAX=127`
- `INT16_MIN=-32768`、`INT16_MAX=32767`
- `INT32_MIN=-2147483648`、`INT32_MAX=2147483647`
- `INT64_MIN=-9223372036854775808L`、`INT64_MAX=9223372036854775807L`
- `UINT8_MAX=255`、`UINT16_MAX=65535`、`UINT32_MAX=4294967295L`

`isGlobalConstant("INT32_MAX")` 等查询对合成条目返回 `true`，这是有意行为。只读查询：

- `findGlobalEnumValueByBareName(String)` / `findGdScriptLanguageConstant(String)`：`@Nullable`
- 组限定式枚举访问仍走 `findGlobalEnum`

五个命名空间（枚举值裸名 / 语言常量 / 全局常量 / singleton / 枚举组名）两两无交；构造期 guard 违反即 `IllegalStateException`（metadata 破坏属 programmer error，不发诊断）。4.5.1 默认 dump 下该 guard 恒不触发。

### 3.3 `resolveValueHere` 五级顺序

`ClassRegistry.resolveValueHere` 固定为：

1. `singletonByName` → `ScopeValueKind.SINGLETON`（`GdObjectType`，仅当 `findSingletonType != null`）
2. `globalEnumByName` → `ScopeValueKind.GLOBAL_ENUM`（`GdIntType.INT`，declaration 为 `ExtensionGlobalEnum`）
3. `globalConstantByName` → `ScopeValueKind.CONSTANT`（`GdIntType.INT`，declaration 为 `ExtensionGlobalConstant`）
4. `globalEnumValueByBareName` → `ScopeValueKind.CONSTANT`（`GdIntType.INT`，declaration 为 `ExtensionEnumValue`）
5. `gdScriptLanguageConstantByName` → `ScopeValueKind.CONSTANT`（`GdFloatType.FLOAT`，declaration 为 `GdScriptLanguageConstant`）
6. `NOT_FOUND`

全部全局命中均为 `FOUND_ALLOWED`（`constant=true, writable=false`），restriction 不过滤全局 value。五个命名空间两两无交，因此后追加级只扩大支持面，不改变任何既有命中。

`resolveTypeMetaHere` 不受影响：枚举成员 / 语言常量 / 独立全局常量不进入 type-meta 命名空间，`Variant.Type` 等枚举名解析保持不变。frontend 逐层 lookup 与全局 fallback 的消费合同以 `frontend_visible_value_resolver_implementation.md` §5 为准。

---

## 4. Binding 与表达式类型

裸标识符完整链路：

1. `FrontendVisibleValueResolver.resolve` 逐层 `resolveValueHere`，逃逸后落到全局 root `ClassRegistry.resolveValueHere`。
2. top binding（`FrontendBodyOwnerProcedures.publishScopeValueBinding`）将 `ScopeValue` 映射为 `FrontendBinding`（`toBindingKind`：`CONSTANT -> FrontendBindingKind.CONSTANT`），`declarationSite = resolvedValue.declaration()`。
3. 表达式类型（`FrontendExpressionSemanticSupport.resolveValueIdentifierExpressionType`）直接采用 `binding.resolvedValue().type()`：枚举值 / 极值常量为 `int`，`PI/TAU/INF/NAN` 为 `float`。
4. CFG builder 对裸常量保持 `OpaqueExprValueItem` 表面。
5. body lowering 按 §5 物化为字面量。

以下路径无需为本表面增加特判：

- `toBindingKind` / `publishScopeValueBinding` 已覆盖 `CONSTANT`
- `FrontendDualRoleTypeMetaRouteSupport.shouldPreferGlobalEnumTypeMeta` 只对 `ScopeValueKind.GLOBAL_ENUM`（枚举名本身）生效；新增 `CONSTANT` 命中不影响 dual-role 判定
- 未知裸名仍走 `sema.binding` error + `UNKNOWN` binding；compile-only gate 因 `hasErrors` 拦截进入 lowering。body pass 若收到 `UNKNOWN` 会 `unsupportedSequenceItem` fail-fast，这是实现不变量保护而非用户可见恢复路径。

---

## 5. Body lowering

`FrontendOpaqueExprInsnLoweringProcessors` 的 identifier processor 只消费已发布 binding，不得重新做 scope lookup。`CONSTANT` 分支按 declaration 形态三路物化，命中即返回：

1. `declarationSite instanceof ExtensionGlobalConstant` → `LiteralIntInsn(resultSlotId, value)`（JSON 全局常量 + 合成极值常量）
2. `declarationSite instanceof ExtensionEnumValue` → `LiteralIntInsn(resultSlotId, value)`（裸全局枚举成员）
3. `declarationSite instanceof GdScriptLanguageConstant` → `LiteralFloatInsn(resultSlotId, value)`（`PI` / `TAU` / `INF` / `NAN`）

其余 declaration 形态继续 `throw unsupportedSequenceItem(...)`（class constant / block-local const 等 deferred 面保持 fail-fast）。

对照面（本表面不得改动）：

- 裸 singleton 仍是 `LoadStaticInsn("@GlobalScope", name)`
- 限定式 `Variant.Type.TYPE_NIL` 仍走 chain `reduceGlobalEnumStaticLoad` → `LoadStaticInsn("Variant", "TYPE_NIL")`，后端 `LoadStaticInsnGen` 全局枚举分发
- JSON 非空时的 `@GlobalScope` 独立全局常量若走 `load_static`，仍由 `CBodyBuilder.assignGlobalConstant` 消费；裸访问不经过这条路径

---

## 6. 后端 float 字面量归一化

`NewDataInsnGen` 处理：

- `LITERAL_INT`：`Long.toString` → `godot_int`，不截断 int64
- `LITERAL_FLOAT`：必须经 `CFloatLiteralSupport.renderFloatLiteral`，不得直接嵌入 `Double.toString(value)`

`CFloatLiteralSupport`（`public final` + `public static`）是非有限 float 值转合法 C 字面量的**唯一事实源**，必须保持 `public`：调用方 `CBuiltinBuilder` 在 `gd.script.gdcc.backend.c.gen`，`NewDataInsnGen` 在 `gd.script.gdcc.backend.c.gen.insn`，package-private 无法跨包共享。不允许第二套平行实现。

两种输入形态：

- **IEEE double 值分类**（`LiteralFloatInsn`）：`NaN -> "NAN"`、`+Inf -> "godot_inf"`、`-Inf -> "-godot_inf"`，其余 `Double.toString(value)`
- **源文字符串分类**（`CBuiltinBuilder`）：`inf` / `+inf` / `infinity` / `+infinity` → `godot_inf`，`-inf` / `-infinity` → `-godot_inf`，`nan` → `NAN`；大小写与首尾空白归一，其余字面量原样透传

`godot_inf` 宏由 `src/main/c/codegen/include_451/godot/godot_builtin_types.h` 提供（`#define godot_inf INFINITY`，同文件已 `#include <math.h>`）。`NAN` 直接用 `<math.h>` 标准宏，不新增 `godot_nan`。测试必须断言归一化后的 C 文本（`godot_inf` / `NAN`），禁止断言 `"Infinity"` / `"NaN"` 原文。

更完整的 builtin literal 合同见 `doc/module_impl/backend/builtin_builder_implementation.md`。`load_static` 的 `@GlobalScope` / 全局枚举 / builtin constant 分发见 `doc/module_impl/backend/load_static_implementation.md`。

---

## 7. 遮蔽与可见性

- `FrontendVisibleValueResolver.resolve` 逐层查询 Block / Callable scope 后才逃逸到全局 root；局部 `var` / `const` / 参数、`for` iterator、capture 命中均先于全局常量，天然对齐 Godot「局部遮蔽全局」语义。
- `FOUND_BLOCKED` 与 declaration-order 过滤合同不变。
- 全局枚举成员裸名 / 语言常量 / 全局常量 / singleton 等 non-callable-local binding 不受 statement-order 过滤影响。
- 全局常量 `constant=true, writable=false`，赋值左值链路的既有拒绝路径不受影响。

---

## 8. 风险与边界

- **极值常量的版本偏差**：gdcc 目标 API 为 4.5.1，而 `INT*_MAX` 家族是 master 新增。残余风险仅是用户代码在 4.5.1 Godot 编辑器内不可移植（反向可移植）。值为纯字面量无运行时依赖；JSON 优先策略保证未来 dump 升级后自动对齐。
- **未来版本重名**：若未来 Godot 版本出现跨组重名枚举值，last-wins 与引擎 `_global_constants_map` 覆盖语义一致；JSON 数组顺序是否严格等于注册顺序依赖 `extension_api_dump.cpp` 按索引顺序输出（当前成立）。升级 API dump 时必须重跑 `ClassRegistryScopeTest` 唯一性回归。
- **`INF` / `NAN` C 文本边界**：`Double.toString` 对非有限值产出非法 C 文本。归一化必须收敛在共享 `CFloatLiteralSupport` 单一入口，`CBuiltinBuilder` 与 `NewDataInsnGen` 共同复用。
- **`globalEnums` name 可空**：扁平展开与组名登记已解耦，无名组成员照常进入扁平索引。
- **与 dual-role singleton / type-meta 的交互**：新增命中全部为 `CONSTANT` kind，不参与 `GLOBAL_ENUM` / `TYPE_META` 路由判定；无名字同时是枚举名与枚举值名。

---

## 9. Test Coverage

涉及本文档合同的修改，至少要继续覆盖以下回归锚点。

### 9.1 单元测试入口

- `src/test/java/gd/script/gdcc/scope/ClassRegistryScopeTest.java`
  - `bareGlobalEnumMemberNamesResolveToIntConstants`
  - `bareGdScriptLanguageConstantsResolveToFloatConstants`（`NAN` 必须用 `Double.isNaN`，不得 `assertEquals(Double.NaN, ...)`）
  - `syntheticExtremeConstantsResolveThroughGlobalConstantNamespace`
  - `jsonProvidedGlobalConstantsKeepPriorityOverSyntheticExtremeConstants`
  - `globalEnumNamesAndUnknownNamesKeepExistingResolution`
  - `defaultApiKeepsBareGlobalValueNamespacesUniqueAndDisjoint`
  - `anonymousGlobalEnumGroupsStillExposeMembersByBareName`
  - `duplicateBareEnumValueNamesFollowLastWinsEngineSemantics`
  - `bareNameFindersExposeReadOnlyNamespaceQueries`
- `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`
  - `runLowersBareGlobalEnumValueIdentifierIntoIntLiteral`（`TYPE_NIL -> LiteralIntInsn(0)`）
  - `runLowersBareGdScriptLanguageConstantIdentifierIntoFloatLiteral`（`PI -> LiteralFloatInsn`）
  - `runLowersBareExtremeConstantIdentifierIntoInt64Literal`（`INT64_MAX` 宽度）
  - lowering 测试只断言 insn opcode + payload，不夹带 `expressionTypes`
- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendBodyOwnerProceduresExprTypeTest.java`
  - `analyzePublishesBareGlobalConstantExpressionTypes`（`PI -> float`、`TYPE_NIL -> int`）
  - `analyzePublishesUnknownBindingForUnresolvedBareIdentifierWhileSiblingFactsSurvive`（`sema.binding` error + `UNKNOWN` + 兄弟 `OK` 事实存活）
- `src/test/java/gd/script/gdcc/frontend/sema/analyzer/FrontendCompileCheckAnalyzerTest.java`
  - `analyzeForCompileKeepsUnresolvedBareIdentifierAsUpstreamBindingErrorBlockingLowering`
- `src/test/java/gd/script/gdcc/frontend/sema/resolver/FrontendVisibleValueResolverTest.java`
  - `resolvePrefersCallableLocalShadowingBareGlobalEnumValue`
- `src/test/java/gd/script/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilderTest.java`
  - `buildExecutableBodyKeepsGlobalConstantIdentifierOnOpaqueValueSurface`
- `src/test/java/gd/script/gdcc/backend/c/gen/CNewDataInsnGenTest.java`
  - `INF -> godot_inf`、`NAN -> NAN`、`-INF -> -godot_inf`；禁止 `"Infinity"` / `"NaN"` 原文
- `src/test/java/gd/script/gdcc/backend/c/gen/CFloatLiteralSupportTest.java`
  - 源文分类合同（`infinity` / `nan` 家族、大小写 / 空白归一、普通数字透传、有限 IEEE 值渲染）

### 9.2 既有回归基线（不得变红）

- `ClassRegistryScopeTest`
- `FrontendLoweringBodyInsnPassTest`
- `FrontendCfgGraphBuilderTest`（opaque 表面合同）
- `CLoadStaticInsnGenTest`（限定式 `load_static` 不受影响）
- `CNewDataInsnGenTest`
- `ExtensionApiLoaderMetadataTest`（loader 未因本表面改动）

负向未知名用例不得放进 lowering 测试类：现状链路是 top binding 发 `sema.binding` error + `UNKNOWN`，compile-only gate 拦截进入 lowering。
