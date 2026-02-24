# GDCC 对象指针转换重构清单（按文件精确版）

> 目标：将 **GDCC 对象指针 -> Godot 对象指针** 的生成路径统一为 `godot_object_from_gdcc_object_ptr(...)`，消除 `NULL->_object` 风险。  
> 基线时间：2026-02-24  
> 范围：`src/main/java`、`src/main/c/codegen`、`src/test/java`、`doc`

## 执行状态（2026-02-24）

- [x] 生产代码已完成替换：
  - `CBodyBuilder` 的 GDCC -> Godot 转换统一改为 `godot_object_from_gdcc_object_ptr(...)`。
  - `LoadPropertyInsnGen` 注释已同步为 helper 宏语义。
- [x] 单测断言已完成替换：
  - `CBodyBuilderPhaseCTest`、`CDestructInsnGenTest`、`COwnReleaseObjectInsnGenTest`、`CPackUnpackVariantInsnGenTest` 已改为 helper 宏相关断言。
- [x] 文档已完成同步：
  - `doc/gdcc_c_backend.md`、`doc/gdcc_ownership_lifecycle_spec.md`、`doc/module_impl/cbodybuilder_implementation_guide.md` 已同步到 helper 宏写法。
  - `doc/module_impl/gdcc_lifecycle_codebase_analysis.md` 已按“归档策略”标注为修复前分析文档。

## 0. 基线与结论

- 你已完成 `gdcc_helper.h` 关键改动：
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h:131`
    - 已是 NULL-safe 宏：`godot_object_from_gdcc_object_ptr(obj)`。
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h:138`
    - `godot_new_Variant_with_gdcc_Object(obj)` 已复用该宏。
- 当前剩余需要改动的核心是：
  1) Java 代码生成器仍在拼接 `->_object`；
  2) 对应单测断言仍期望 `->_object`；
  3) 文档仍以 `->_object` 为主叙述。

---

## 1. 必改清单（生产代码）

### 1.1 `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java`

- `:865`
  - 现状：`return varCode + "->_object";`
  - 改为：`return "godot_object_from_gdcc_object_ptr(" + varCode + ")";`
- `:884`
  - 现状：`return code + "->_object";`
  - 改为：`return "godot_object_from_gdcc_object_ptr(" + code + ")";`
- 文档注释同步（语义说明要与实现一致）：
  - `:648`（`renderArgument` 注释）
  - `:861`（`toGodotObjectPtr` 注释）
  - `:873`（`convertPtrIfNeeded` 注释）

### 1.2 `src/main/java/dev/superice/gdcc/backend/c/gen/insn/LoadPropertyInsnGen.java`

- `:85`（注释）
  - 现状注释写“via `->_object`”。
  - 改为“via `godot_object_from_gdcc_object_ptr(...)`”。

---

## 2. 必改清单（测试）

### 2.1 `src/test/java/dev/superice/gdcc/backend/c/gen/CBodyBuilderPhaseCTest.java`

以下行当前包含 `->_object` 期望或文案，需逐条改为 helper 宏语义（当前行号）：

- `:413` DisplayName 文案
- `:423` 注释文案
- `:424` 断言字符串 `$myObj->_object`
- `:839` DisplayName 文案
- `:846` 期望字符串 `godot_some_func($myObj->_object);`
- `:879` 期望字符串 `try_own_object($myObj->_object);`
- `:893` 期望字符串 `godot_Object_set($myObj->_object, &$name);`
- `:964` 断言字符串 `release_object($myObj->_object)`
- `:966` 断言字符串 `own_object($myObj->_object)`
- `:982` 断言字符串 `$input->_object`
- `:1010` DisplayName 文案
- `:1023` 断言字符串 `$obj = $myObj->_object`
- `:1024` 提示文案 `via ->_object`
- `:1042` 负向断言字符串 `$source->_object;`
- `:1043` 提示文案 `Should NOT use ->_object`
- `:1059` 负向断言字符串 `->_object`
- `:1060` 提示文案 `Should NOT use ->_object`
- `:1064` DisplayName 文案
- `:1073` 注释文案
- `:1074` 断言字符串 `release_object($myObj->_object)`
- `:1075` 提示文案 `via ->_object`
- `:1076` 断言字符串 `own_object($myObj->_object)`
- `:1077` 提示文案 `via ->_object`
- `:1113` DisplayName 文案
- `:1125` 断言字符串 `$rc = $myObj->_object`
- `:1126` 提示文案 `via ->_object`
- `:1139` `indexOf("release_object($myObj->_object)")`
- `:1141` `indexOf("own_object($myObj->_object)")`

> 说明：`gdcc_object_from_godot_object_ptr(...)` 相关断言（如 `:920/:1005/:1079/:1092/:1140`）属于反向转换路径，不应改。

### 2.2 `src/test/java/dev/superice/gdcc/backend/c/gen/CDestructInsnGenTest.java`

- `:71`
  - `release_object($obj->_object);` -> 改为 helper 宏版本。

### 2.3 `src/test/java/dev/superice/gdcc/backend/c/gen/COwnReleaseObjectInsnGenTest.java`

- `:53`
  - `own_object($obj->_object);` -> 改为 helper 宏版本。
- `:54`
  - `try_own_object($obj->_object);` -> 改为 helper 宏版本（用于负向断言）。

### 2.4 `src/test/java/dev/superice/gdcc/backend/c/gen/CPackUnpackVariantInsnGenTest.java`

- `:114`
  - 负向断言 `godot_new_Variant_with_gdcc_Object($value->_object);` -> 改为 helper 宏版本。

---

## 3. 必改清单（文档同步）

### 3.1 `doc/gdcc_c_backend.md`

- `:36`
  - 从“using `gdcc_object->_object` first”改为“using `godot_object_from_gdcc_object_ptr(gdcc_object)`”。
- `:60`
  - 从“pass `gdcc_object->_object`”改为“pass `godot_object_from_gdcc_object_ptr(gdcc_object)`”。

### 3.2 `doc/gdcc_ownership_lifecycle_spec.md`

- `:39`
  - 示例 `->_object` 改为 `godot_object_from_gdcc_object_ptr(...)`。

### 3.3 `doc/module_impl/cbodybuilder_implementation_guide.md`

- `:52`
  - 从 `obj->_object` 改为 `godot_object_from_gdcc_object_ptr(obj)`。
- `:113`
  - “禁止手写 `->_object`”可保留，但建议补充“统一通过 helper 宏转换”。

### 3.4 `doc/module_impl/gdcc_lifecycle_codebase_analysis.md`

该文档当前是“缺陷分析结论（修复前叙述）”，包含大量 `->_object` 风险描述：
- `:29`, `:34`, `:40`, `:42`, `:44`, `:50`, `:81`, `:98`, `:127`, `:133`, `:138`, `:140`

建议二选一（避免文档与现状分叉）：
1. **归档策略**：在文首新增“本文件描述的是修复前状态”并保留原文；
2. **同步策略**：按新实现改写为“已通过 helper 宏修复”，并更新示例代码。

---

## 4. 明确不改（避免误改）

### 4.1 `src/main/c/codegen/template_451/entry.c.ftl`

- `:99` `self->_object = obj;`
  - 这是结构体字段赋值（对象封装初始化），**不是**“GDCC ptr -> Godot ptr 的读取转换”，不应替换。
- `:136` 已使用 `godot_object_from_gdcc_object_ptr(self->${property.name})`，无需改。

### 4.2 `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`

- `:131` 宏体内部 `_o->_object` 属 helper 内部实现，不属于调用侧手写访问。
- `:138` 已改为调用 helper 宏，无需再改。

---

## 5. 验证清单（建议按顺序执行）

- `./gradlew.bat test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain`
- `./gradlew.bat test --tests CDestructInsnGenTest --no-daemon --info --console=plain`
- `./gradlew.bat test --tests COwnReleaseObjectInsnGenTest --no-daemon --info --console=plain`
- `./gradlew.bat test --tests CPackUnpackVariantInsnGenTest --no-daemon --info --console=plain`
- `./gradlew.bat classes --no-daemon --info --console=plain`

### 验证结果（2026-02-24）

- [x] `./gradlew.bat test --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain`
- [x] `./gradlew.bat test --tests CDestructInsnGenTest --no-daemon --info --console=plain`
- [x] `./gradlew.bat test --tests COwnReleaseObjectInsnGenTest --no-daemon --info --console=plain`
- [x] `./gradlew.bat test --tests CPackUnpackVariantInsnGenTest --no-daemon --info --console=plain`
- [x] `./gradlew.bat classes --no-daemon --info --console=plain`

---

## 6. 风险提示（本次改造相关）

- 你当前宏实现用了 GNU 语法 `({ ... })` + `__typeof__`：
  - 优点：可避免参数双求值，行为安全；
  - 风险：依赖 GNU 扩展，若未来切换到不支持 GNU 扩展的纯 MSVC 编译链需另行处理。
- 若后续出现“将复杂表达式直接包给 `godot_object_from_gdcc_object_ptr(...)`”的代码生成变更，需回归确认生成的 C 仍兼容目标编译器。
