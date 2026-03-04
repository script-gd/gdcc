# IndexLoadInsnGen / IndexStoreInsnGen 实现计划

> 本文档为 Indexing Instructions 分类下 8 条 LIR 指令的 C Backend 代码生成器实施计划。

## 文档状态

- 状态：`Plan / Pending`
- 文档类型：`实施计划`
- 创建时间：`2026-03-04`
- 适用范围：`backend.c` 中 8 条 `variant_get*` / `variant_set*` 指令的 C 代码生成

## 1. 背景与目标

### 1.1 覆盖指令

Indexing Instructions 分类包含 8 条指令，分为 Load（4 条 GET）和 Store（4 条 SET）两组：

| LIR 指令 | GdInstruction 枚举 | ReturnKind | 操作数签名 | 语义 |
|---|---|---|---|---|
| `variant_get` | `VARIANT_GET` | REQUIRED | `(VARIABLE, VARIABLE)` | 通用 Variant 键读取 |
| `variant_get_keyed` | `VARIANT_GET_KEYED` | REQUIRED | `(VARIABLE, VARIABLE)` | 字典式键读取 |
| `variant_get_named` | `VARIANT_GET_NAMED` | REQUIRED | `(VARIABLE, STRING)` | 属性名读取（StringName） |
| `variant_get_indexed` | `VARIANT_GET_INDEXED` | REQUIRED | `(VARIABLE, INT)` | 整数索引读取 |
| `variant_set` | `VARIANT_SET` | NONE | `(VARIABLE, VARIABLE, VARIABLE)` | 通用 Variant 键写入 |
| `variant_set_keyed` | `VARIANT_SET_KEYED` | NONE | `(VARIABLE, VARIABLE, VARIABLE)` | 字典式键写入 |
| `variant_set_named` | `VARIANT_SET_NAMED` | NONE | `(VARIABLE, STRING, VARIABLE)` | 属性名写入（StringName） |
| `variant_set_indexed` | `VARIANT_SET_INDEXED` | NONE | `(VARIABLE, INT, VARIABLE)` | 整数索引写入 |

### 1.2 LIR 指令类（已实现）

所有 8 个 LIR 指令 record 类均已定义在 `src/main/java/dev/superice/gdcc/lir/insn/` 下：

- `VariantGetInsn(resultId, variantId, keyId)` → `IndexingInstruction`
- `VariantGetKeyedInsn(resultId, keyedVariantId, keyId)` → `IndexingInstruction`
- `VariantGetNamedInsn(resultId, namedVariantId, name)` → `IndexingInstruction`
- `VariantGetIndexedInsn(resultId, variantId, index)` → `IndexingInstruction`
- `VariantSetInsn(variantId, keyId, valueId)` → `IndexingInstruction`
- `VariantSetKeyedInsn(keyedVariantId, keyId, valueId)` → `IndexingInstruction`
- `VariantSetNamedInsn(namedVariantId, name, valueId)` → `IndexingInstruction`
- `VariantSetIndexedInsn(variantId, index, valueId)` → `IndexingInstruction`

公共标记接口：`IndexingInstruction extends LirInstruction`。

### 1.3 GDExtension C API 函数（gdextension-lite 已提供）

所有 8 个 GDExtension C API 函数已通过 `gdextension-lite` 在 `extension_interface.h` 中声明可用：

**GET 函数签名：**

```c
// variant_get / variant_get_keyed — 签名相同，内部行为不同
void godot_variant_get(
    GDExtensionConstVariantPtr p_self,
    GDExtensionConstVariantPtr p_key,
    GDExtensionUninitializedVariantPtr r_ret,
    GDExtensionBool *r_valid);

void godot_variant_get_keyed(
    GDExtensionConstVariantPtr p_self,
    GDExtensionConstVariantPtr p_key,
    GDExtensionUninitializedVariantPtr r_ret,
    GDExtensionBool *r_valid);

void godot_variant_get_named(
    GDExtensionConstVariantPtr p_self,
    GDExtensionConstStringNamePtr p_key,
    GDExtensionUninitializedVariantPtr r_ret,
    GDExtensionBool *r_valid);

void godot_variant_get_indexed(
    GDExtensionConstVariantPtr p_self,
    GDExtensionInt p_index,
    GDExtensionUninitializedVariantPtr r_ret,
    GDExtensionBool *r_valid,
    GDExtensionBool *r_oob);          // ← 独有 out-of-bounds 标志
```

**SET 函数签名：**

```c
void godot_variant_set(
    GDExtensionVariantPtr p_self,      // ← 可变
    GDExtensionConstVariantPtr p_key,
    GDExtensionConstVariantPtr p_value,
    GDExtensionBool *r_valid);

void godot_variant_set_keyed(
    GDExtensionVariantPtr p_self,
    GDExtensionConstVariantPtr p_key,
    GDExtensionConstVariantPtr p_value,
    GDExtensionBool *r_valid);

void godot_variant_set_named(
    GDExtensionVariantPtr p_self,
    GDExtensionConstStringNamePtr p_key,
    GDExtensionConstVariantPtr p_value,
    GDExtensionBool *r_valid);

void godot_variant_set_indexed(
    GDExtensionVariantPtr p_self,
    GDExtensionInt p_index,
    GDExtensionConstVariantPtr p_value,
    GDExtensionBool *r_valid,
    GDExtensionBool *r_oob);          // ← 独有 out-of-bounds 标志
```

**关键差异汇总：**
- GET 的 `p_self` 为 `Const`；SET 的 `p_self` 为可变。
- GET 的返回值写入 `GDExtensionUninitializedVariantPtr r_ret`（未初始化输出参数）。
- SET 的值通过 `GDExtensionConstVariantPtr p_value` 传入。
- `_indexed` 变体多一个 `GDExtensionBool *r_oob` 输出参数。
- `_named` 变体的 key 类型为 `GDExtensionConstStringNamePtr`，其余为 `GDExtensionConstVariantPtr` 或 `GDExtensionInt`。
- 所有操作都通过 `Variant` 指针进行——调用方需确保操作数被 pack 为 Variant。

### 1.4 设计目标

1. 创建 `IndexLoadInsnGen`（处理 4 条 GET 指令）和 `IndexStoreInsnGen`（处理 4 条 SET 指令）。
2. 遵循现有 `ConstructInsnGen` / `OperatorInsnGen` 的架构模式：直接实现 `CInsnGen<IndexingInstruction>`，使用 `CBodyBuilder` API。
3. 在 `CCodegen` 中注册两个新生成器。
4. 编写完整的单元测试。
5. 核心约束：所有 Variant 操作数需要正确的生命周期管理（pack/unpack/destroy）。

---

## 2. 架构设计

### 2.1 生成器拆分策略

拆分为两个生成器而非一个统一生成器，原因：

1. **返回值语义完全不同**：GET 有 `resultId`（REQUIRED），SET 没有（NONE）。
2. **C API 参数模式不同**：GET 使用未初始化输出参数 `r_ret`；SET 使用输入参数 `p_value`。
3. **操作数解析逻辑不同**：GET 读取键/索引 + 接收返回值；SET 读取键/索引 + 提供值。
4. **生命周期管理不对称**：GET 需要管理返回的 Variant 的所有权；SET 需要管理传入的 value 的打包。

```
IndexLoadInsnGen  implements CInsnGen<IndexingInstruction>
  ├── getInsnOpcodes(): {VARIANT_GET, VARIANT_GET_KEYED, VARIANT_GET_NAMED, VARIANT_GET_INDEXED}
  └── generateCCode(CBodyBuilder): switch(insn) 分派 4 条 GET 指令

IndexStoreInsnGen implements CInsnGen<IndexingInstruction>
  ├── getInsnOpcodes(): {VARIANT_SET, VARIANT_SET_KEYED, VARIANT_SET_NAMED, VARIANT_SET_INDEXED}
  └── generateCCode(CBodyBuilder): switch(insn) 分派 4 条 SET 指令
```

### 2.2 类型策略表（SET 的 self）

`variant_set*` 的关键不是“self 是否是 `GdVariantType`”，而是 **self 运行时 Variant 的可变语义**。  
在实现层面，按下表对 self 进行分类并选择生成策略：

| self 类别 | 典型类型（gdcc） | 允许策略 | 是否需要 self 回写 |
|---|---|---|---|
| 已是 Variant 变量 | `GdVariantType` | 直接传 `&$self` 给 `godot_variant_set*` | 否 |
| 引用语义容器/对象 | `GdArrayType` / `GdDictionaryType` / `GdPacked*ArrayType` / `GdObjectType` | 可临时 pack 为 Variant 后调用 set；底层对象共享，修改可见 | 否 |
| 值语义但支持 set 的类型 | `GdStringType`、向量/颜色/矩阵等值类型 | 临时 pack 后调用 set；随后必须 unpack 写回原 self 变量 | 是 |
| 不支持 set 的类型 | `GdIntType`/`GdFloatType`/`GdBoolType`/`GdNilType`/`GdStringNameType`/`GdNodePathType`/`GdRidType`/`GdCallableType`/`GdSignalType`/`GdVoidType` | 编译期 fail-fast | - |

> 注：`GDExtensionVariantPtr p_self` 仅表示“传入的是可变 Variant 指针”，不等价于“源 IR 变量必须是 `GdVariantType`”。

### 2.3 指令能力矩阵（Godot runtime）

`variant_set*` 在 Godot 引擎内部并非对所有 Variant 类型都有效，能力如下：

| 指令 | 运行时有效 self（核心） | 说明 |
|---|---|---|
| `variant_set` | `Dictionary` / `Object`（keyed）；其余类型依 key 类型分派到 named/indexed | key 为 `StringName`/`String`/`int`/`float` 时可能转入 named/indexed 路径 |
| `variant_set_keyed` | `Dictionary` / `Object` | 其他类型 `r_valid=false` |
| `variant_set_named` | 支持命名成员的内建值类型、`Object`、`Dictionary` | 例如向量、颜色、变换、矩形、AABB、Plane 等 |
| `variant_set_indexed` | 支持索引写入的类型 | 例如 `String`、向量、`Color`、`Transform2D`、`Basis`、`Projection`、`Array`、`Dictionary`、`Packed*Array` |

编译器策略：  
1. 对“明确不支持 set”的 self 类型做编译期 fail-fast。  
2. 对“可能支持但需运行时判定”的情况生成 `r_valid`/`r_oob` 检查，运行时兜底报错。

### 2.4 代码生成模式总览

#### 2.4.1 GET 指令通用生成模式

```
1. 校验 resultId 存在且对应非 ref 变量
2. 校验源 variant 变量存在
3. 将源 variant 打包为 Variant（如非 Variant 类型）→ 创建临时变量
4. 准备 key/index 参数
5. 声明未初始化 Variant 临时变量作为 r_ret
6. 声明 r_valid (GDExtensionBool)
7. [仅 _indexed] 声明 r_oob (GDExtensionBool)
8. 发射 godot_variant_get* 调用
9. 发射 r_valid 检查 → 失败时打印运行时错误并安全返回
10. [仅 _indexed] 发射 r_oob 检查 → 失败时打印运行时错误并安全返回
11. 将 r_ret Variant 拆包到 result 变量（如果 result 不是 Variant 类型）
12. 销毁 r_ret 临时 Variant
13. 销毁步骤 3 中的临时打包 Variant（如有）
```

#### 2.4.2 SET 指令通用生成模式

```
1. 校验无 resultId（SET 指令 returnKind=NONE）
2. 校验 self 变量存在，并判定 self 类型策略（直接 Variant / 引用语义 pack / 值语义 pack+回写 / 不支持）
3. 准备 key/index 参数
4. 将 value 打包为 Variant（如非 Variant 类型）→ 创建临时变量
5. 声明 r_valid (GDExtensionBool)
6. [仅 _indexed] 声明 r_oob (GDExtensionBool)
7. 发射 godot_variant_set* 调用
8. 发射 r_valid 检查 → 失败时打印运行时错误并安全返回
9. [仅 _indexed] 发射 r_oob 检查 → 失败时打印运行时错误并安全返回
10. 若 self 属于“值语义 pack+回写”策略：将修改后的临时 self Variant unpack 回原 self 变量
11. 销毁临时 value Variant（如有）
12. 销毁临时 self Variant（如有）
```

### 2.5 操作数类型约束

#### GET 指令操作数约束

| 指令 | self 操作数 | key/index 操作数 | result 变量 |
|---|---|---|---|
| `variant_get` | 任意类型（pack 为 Variant） | 任意类型（pack 为 Variant） | 任意类型（unpack 自 Variant） |
| `variant_get_keyed` | 任意类型（pack 为 Variant） | 任意类型（pack 为 Variant） | 任意类型（unpack 自 Variant） |
| `variant_get_named` | 任意类型（pack 为 Variant） | STRING 字面量 → StringName | 任意类型（unpack 自 Variant） |
| `variant_get_indexed` | 任意类型（pack 为 Variant） | INT 字面量 | 任意类型（unpack 自 Variant） |

#### SET 指令操作数约束

| 指令 | self 操作数 | key/index 操作数 | value 操作数 | 额外规则 |
|---|---|---|---|---|
| `variant_set` | `Variant` / 可 pack 且支持 set 的类型 | 任意类型（pack 为 Variant） | 任意类型（pack 为 Variant） | 值语义 self 需回写；不支持类型 fail-fast |
| `variant_set_keyed` | `Variant` / `Object` / `Dictionary`（或可 pack 到这些运行时类型） | 任意类型（pack 为 Variant） | 任意类型（pack 为 Variant） | 非 keyed 支持类型 fail-fast |
| `variant_set_named` | `Variant` / 支持 named-set 的类型 | STRING 字面量 → StringName | 任意类型（pack 为 Variant） | 值语义 self 需回写 |
| `variant_set_indexed` | `Variant` / 支持 indexed-set 的类型 | INT 字面量 | 任意类型（pack 为 Variant） | 值语义 self 需回写 |

### 2.6 GET 指令的 Variant 打包/拆包策略

GDExtension 的 variant_get* API 全部通过 Variant 指针操作。当 LIR 操作数不是 Variant 类型时，需要进行 pack/unpack 转换。

**self 操作数 pack 策略（GET 的 p_self 为 const）：**
- 如果 self 已经是 `GdVariantType`：直接取地址 `&$self`
- 如果 self 不是 `GdVariantType`：创建临时 Variant，调用 `godot_new_Variant_with_<Type>` pack

**key 操作数 pack 策略（仅 variant_get / variant_get_keyed）：**
- 如果 key 已经是 `GdVariantType`：直接取地址 `&$key`
- 如果 key 不是 `GdVariantType`：创建临时 Variant，pack

**result unpack 策略：**
- 如果 result 变量为 `GdVariantType`：直接通过 `callAssign` 以 `godot_new_Variant_with_Variant` 构造拷贝写回（遵循 OperatorInsnGen 的 Variant 回写规则）
- 如果 result 变量为非 `GdVariantType`：调用 `godot_new_<Type>_with_Variant` unpack 到目标类型

### 2.7 SET 指令的 self 回写策略

对于 `variant_set*`：

- `self` 为 `GdVariantType`：直接传 `&$self`，无需回写。
- `self` 为引用语义类型（`Array`/`Dictionary`/`Packed*Array`/`Object`）：可 pack 为临时 Variant，执行 set 后无需回写。
- `self` 为值语义但支持 set 的类型（例如 `String`、向量、`Color`、`Transform2D`、`Basis`、`Projection` 等）：执行 set 后必须将临时 self Variant unpack 回原 self 变量。
- `self` 为不支持 set 的类型：编译期 fail-fast，不生成调用。

### 2.8 错误处理策略

所有 GDExtension variant_get*/set* 调用都返回 `r_valid` 标志。`_indexed` 变体额外返回 `r_oob` 标志。

**错误处理模式**（参照 `OperatorInsnGen` 的 `godot_variant_evaluate` 错误处理）：

```c
// 示例：variant_get 的错误处理
godot_Variant __gdcc_tmp_idx_ret_0;
GDExtensionBool __gdcc_tmp_idx_valid_1 = false;
godot_variant_get(&$self, &$key, &__gdcc_tmp_idx_ret_0, &__gdcc_tmp_idx_valid_1);
if (!__gdcc_tmp_idx_valid_1) {
    GDCC_PRINT_RUNTIME_ERROR("variant_get failed", __func__, __FILE__, __LINE__);
    __gdcc_tmp_idx_ret_0 = godot_new_Variant_nil();  // 初始化以便安全析构
    godot_variant_destroy(&__gdcc_tmp_idx_ret_0);
    // 销毁临时 pack 变量...
    return;  // 或 return 默认值
}
// ... 继续 unpack / 赋值
```

**`_indexed` 变体的额外 OOB 检查：**

```c
GDExtensionBool __gdcc_tmp_idx_oob_2 = false;
godot_variant_get_indexed(&$self, index, &__gdcc_tmp_idx_ret_0, &__gdcc_tmp_idx_valid_1, &__gdcc_tmp_idx_oob_2);
if (!__gdcc_tmp_idx_valid_1) {
    GDCC_PRINT_RUNTIME_ERROR("variant_get_indexed failed", __func__, __FILE__, __LINE__);
    // ... 安全返回
}
if (__gdcc_tmp_idx_oob_2) {
    GDCC_PRINT_RUNTIME_ERROR("variant_get_indexed: index out of bounds", __func__, __FILE__, __LINE__);
    // ... 安全返回（此时 r_ret 已被写入，需销毁）
}
```

**安全返回规则**（与 OperatorInsnGen 一致）：
- 销毁所有已初始化的临时变量
- 如果函数返回 void：`goto __finally__`
- 如果函数返回非 void：`goto __finally__`（通过 `_return_val` 带默认值返回）

---

## 3. 分步实施计划

### 步骤 1：创建 IndexLoadInsnGen

**文件**：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/IndexLoadInsnGen.java`

**类结构**：

```java
public final class IndexLoadInsnGen implements CInsnGen<IndexingInstruction> {

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(
            GdInstruction.VARIANT_GET,
            GdInstruction.VARIANT_GET_KEYED,
            GdInstruction.VARIANT_GET_NAMED,
            GdInstruction.VARIANT_GET_INDEXED
        );
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        switch (instruction) {
            case VariantGetInsn insn -> emitVariantGet(bodyBuilder, insn);
            case VariantGetKeyedInsn insn -> emitVariantGetKeyed(bodyBuilder, insn);
            case VariantGetNamedInsn insn -> emitVariantGetNamed(bodyBuilder, insn);
            case VariantGetIndexedInsn insn -> emitVariantGetIndexed(bodyBuilder, insn);
            default -> throw bodyBuilder.invalidInsn("Unsupported index load instruction: "
                + instruction.getClass().getSimpleName());
        }
    }
}
```

**实现要点**：

1. **`resolveRequiredResultVariable`**：与 OperatorInsnGen 一致——校验 resultId 非空，变量存在且非 ref。
2. **`resolveOperandVariable`**：按 variableId 查找操作数变量，不存在则 fail-fast。
3. **`materializeVariantOperand`**：参照 OperatorInsnGen 的同名方法——如果操作数已是 Variant 则直接引用，否则创建临时 Variant 并 pack。
4. **`emitVariantGet` / `emitVariantGetKeyed`**：
   - 两者代码结构几乎相同（API 签名一致），仅 C 函数名不同（`godot_variant_get` vs `godot_variant_get_keyed`）。
   - 可抽取公共方法 `emitVariantGetCommon(bodyBuilder, cFuncName, selfVar, keyVar, resultVar)`。
5. **`emitVariantGetNamed`**：
   - key 为 STRING 字面量，使用 `bodyBuilder.valueOfStringNamePtrLiteral(name)` 生成静态 StringName 指针。
   - C 调用为 `godot_variant_get_named(&self_variant, GD_STATIC_SN(u8"name"), &r_ret, &r_valid)`。
6. **`emitVariantGetIndexed`**：
   - index 为 INT 字面量，直接作为 `(GDExtensionInt)index` 传入。
   - 额外声明 `r_oob` 标志并检查。
7. **result 回写**：
   - Variant→Variant：构造拷贝（`godot_new_Variant_with_Variant` + `callAssign`）。
   - Variant→非 Variant：调用 unpack 函数（`bodyBuilder.helper().renderUnpackFunctionName(resultType)`）+ `callAssign`。
8. **错误返回路径**：使用与 OperatorInsnGen 一致的模式——销毁临时变量后 return void 或 return 默认值。

#### 验收标准

- [ ] 编译通过，无 warning
- [ ] 4 条 GET 指令均可生成正确的 C 代码
- [ ] self 为 Variant 类型时无多余 pack/unpack
- [ ] self 为非 Variant 类型时正确 pack 并在结束后销毁
- [ ] key 为 Variant 类型时无多余 pack
- [ ] result 为 Variant 类型时使用构造拷贝回写
- [ ] result 为非 Variant 类型时正确 unpack
- [ ] r_valid 检查失败时正确打印错误并安全返回
- [ ] variant_get_indexed 的 r_oob 检查失败时正确打印错误并安全返回
- [ ] 所有临时变量在所有路径（正常/错误）上均被正确销毁

---

### 步骤 2：创建 IndexStoreInsnGen

**文件**：`src/main/java/dev/superice/gdcc/backend/c/gen/insn/IndexStoreInsnGen.java`

**类结构**：

```java
public final class IndexStoreInsnGen implements CInsnGen<IndexingInstruction> {

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(
            GdInstruction.VARIANT_SET,
            GdInstruction.VARIANT_SET_KEYED,
            GdInstruction.VARIANT_SET_NAMED,
            GdInstruction.VARIANT_SET_INDEXED
        );
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        switch (instruction) {
            case VariantSetInsn insn -> emitVariantSet(bodyBuilder, insn);
            case VariantSetKeyedInsn insn -> emitVariantSetKeyed(bodyBuilder, insn);
            case VariantSetNamedInsn insn -> emitVariantSetNamed(bodyBuilder, insn);
            case VariantSetIndexedInsn insn -> emitVariantSetIndexed(bodyBuilder, insn);
            default -> throw bodyBuilder.invalidInsn("Unsupported index store instruction: "
                + instruction.getClass().getSimpleName());
        }
    }
}
```

**实现要点**：

1. **self 分类与策略**：按 2.2 的策略将 self 分为：直接 Variant、引用语义 pack、值语义 pack+回写、不支持类型 fail-fast。
2. **`materializeVariantValue`**：将 value 操作数打包为 Variant（如非 Variant 类型），使用 `renderPackFunctionName` + `callAssign` 到临时变量。
3. **`emitVariantSet` / `emitVariantSetKeyed`**：
   - 两者代码结构几乎相同，仅 C 函数名不同。
   - key 打包策略与 GET 一致。
   - 可抽取公共方法 `emitVariantSetCommon(bodyBuilder, cFuncName, selfVar, keyVar, valueVar)`。
4. **`emitVariantSetNamed`**：
   - key 为 STRING 字面量，生成静态 StringName 指针。
5. **`emitVariantSetIndexed`**：
   - index 为 INT 字面量。
   - 额外声明并检查 `r_oob` 标志。
6. **self 回写**：仅当 self 属于“值语义 pack+回写”策略时，将临时 self Variant unpack 回原 self 变量。
7. **无 result 回写**：SET 指令 `ReturnKind=NONE`，无 resultId。
8. **错误返回路径**：与 GET 一致。

#### 验收标准

- [ ] 编译通过，无 warning
- [ ] 4 条 SET 指令均可生成正确的 C 代码
- [ ] self 分类策略正确：直接 Variant / 引用语义 pack / 值语义 pack+回写 / 不支持类型 fail-fast
- [ ] key 为 Variant 类型时无多余 pack
- [ ] value 为 Variant 类型时无多余 pack
- [ ] value 为非 Variant 类型时正确 pack 并在结束后销毁
- [ ] 值语义 self 在 set 后正确 unpack 回写
- [ ] r_valid 检查失败时正确打印错误并安全返回
- [ ] variant_set_indexed 的 r_oob 检查失败时正确打印错误并安全返回
- [ ] 所有临时变量在所有路径（正常/错误）上均被正确销毁
- [ ] 不意外设置 resultId（SET 不返回值）

---

### 步骤 3：注册生成器到 CCodegen

**文件**：`src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`

**修改内容**：在 static 块中添加两行注册：

```java
static {
    // ... 现有注册 ...
    registerInsnGen(new LoadStaticInsnGen());
    registerInsnGen(new StoreStaticInsnGen());
    // ↓ 新增
    registerInsnGen(new IndexLoadInsnGen());
    registerInsnGen(new IndexStoreInsnGen());
}
```

**导入**：添加 `import dev.superice.gdcc.backend.c.gen.insn.IndexLoadInsnGen;` 和 `import dev.superice.gdcc.backend.c.gen.insn.IndexStoreInsnGen;`（如未使用通配符导入）。

#### 验收标准

- [ ] 编译通过
- [ ] `INSN_GENS` 包含 8 条新的 opcode → generator 映射
- [ ] 不影响现有 15 个生成器的注册

---

### 步骤 4：创建 IndexLoadInsnGen 单元测试

**文件**：`src/test/java/dev/superice/gdcc/backend/c/gen/IndexLoadInsnGenTest.java`

**测试类结构**（参照 `COperatorInsnGenTest` / `CConstructInsnGenTest` 模式）：

```java
class IndexLoadInsnGenTest {
    // Helper methods: generateBody, newTestClass, newFunction, entry, newCodegen, emptyApi
    // Record: VariableSpec(String id, GdType type, boolean ref)
}
```

**测试用例清单**：

**variant_get 正向用例：**
1. `variant_get_variant_key_variant_result` — self=Variant, key=Variant, result=Variant → 不需要 pack/unpack，生成 `godot_variant_get` 调用 + 构造拷贝回写
2. `variant_get_non_variant_self_pack` — self=Array, key=Variant, result=Variant → self 需 pack
3. `variant_get_non_variant_key_pack` — self=Variant, key=int, result=Variant → key 需 pack
4. `variant_get_non_variant_result_unpack` — self=Variant, key=Variant, result=int → 需 unpack

**variant_get 错误用例：**
5. `variant_get_missing_result_fails` — resultId 为 null → `InvalidInsnException`
6. `variant_get_ref_result_fails` — result 变量为 ref → `InvalidInsnException`
7. `variant_get_missing_variant_operand_fails` — variantId 不存在 → `InvalidInsnException`
8. `variant_get_missing_key_operand_fails` — keyId 不存在 → `InvalidInsnException`

**variant_get_keyed 正向用例：**
9. `variant_get_keyed_basic` — 类似 variant_get，验证生成 `godot_variant_get_keyed`

**variant_get_named 正向用例：**
10. `variant_get_named_basic` — self=Variant, name="property", result=Variant → 生成 `godot_variant_get_named` + `GD_STATIC_SN`
11. `variant_get_named_non_variant_self` — self=Dictionary, result=int → pack self, unpack result

**variant_get_indexed 正向用例：**
12. `variant_get_indexed_basic` — self=Variant, index=0, result=Variant → 生成 `godot_variant_get_indexed` + r_oob 检查
13. `variant_get_indexed_non_variant_self` — self=Array, index=2, result=int → pack self, unpack result

**r_valid / r_oob 错误处理验证：**
14. `variant_get_emits_valid_check` — 验证生成的代码包含 r_valid 检查和 GDCC_PRINT_RUNTIME_ERROR
15. `variant_get_indexed_emits_oob_check` — 验证生成的代码包含 r_oob 检查

**生命周期验证：**
16. `variant_get_destroys_temp_pack_vars` — 验证临时 pack Variant 被正确析构
17. `variant_get_destroys_ret_variant` — 验证 r_ret Variant 被正确析构

#### 验收标准

- [ ] 全部测试用例通过
- [ ] 覆盖 4 种 GET 指令
- [ ] 覆盖 Variant/非 Variant self、key、result 组合
- [ ] 覆盖错误路径（缺失变量、ref 变量）
- [ ] 覆盖 r_valid 和 r_oob 检查
- [ ] 覆盖临时变量生命周期

---

### 步骤 5：创建 IndexStoreInsnGen 单元测试

**文件**：`src/test/java/dev/superice/gdcc/backend/c/gen/IndexStoreInsnGenTest.java`

**测试用例清单**：

**variant_set 正向用例：**
1. `variant_set_variant_key_variant_value` — self=Variant, key=Variant, value=Variant → 无 pack 开销
2. `variant_set_non_variant_key_pack` — self=Variant, key=int, value=Variant → key 需 pack
3. `variant_set_non_variant_value_pack` — self=Variant, key=Variant, value=int → value 需 pack
4. `variant_set_array_self_pack_succeeds` — self=Array, key=int, value=Variant → self 允许 pack 且无需回写
5. `variant_set_value_semantic_self_emits_writeback` — self=Vector3（或 Color），set 后应生成 unpack 回写

**variant_set 错误用例：**
1. `variant_set_unsupported_self_fails` — self=int（或 bool）→ `InvalidInsnException`
2. `variant_set_missing_variant_operand_fails` — variantId 不存在 → `InvalidInsnException`
3. `variant_set_missing_key_operand_fails` — keyId 不存在 → `InvalidInsnException`
4. `variant_set_missing_value_operand_fails` — valueId 不存在 → `InvalidInsnException`

**variant_set_keyed 正向用例：**
1. `variant_set_keyed_basic` — 验证生成 `godot_variant_set_keyed`

**variant_set_named 正向用例：**
1. `variant_set_named_basic` — self=Variant, name="property", value=Variant → 生成 `godot_variant_set_named` + `GD_STATIC_SN`
2. `variant_set_named_non_variant_value` — value=int → pack value

**variant_set_named 错误用例：**
1. `variant_set_named_unsupported_self_fails` — self=int（不支持 named-set）→ fail-fast

**variant_set_indexed 正向用例：**
1. `variant_set_indexed_basic` — self=Variant, index=0, value=Variant → 生成 `godot_variant_set_indexed` + r_oob 检查
2. `variant_set_indexed_non_variant_value` — value=int → pack value
3. `variant_set_indexed_array_self_pack_succeeds` — self=Array, index=0, value=Variant → 引用语义路径

**r_valid / r_oob 错误处理验证：**
1. `variant_set_emits_valid_check` — 验证 r_valid 检查
2. `variant_set_indexed_emits_oob_check` — 验证 r_oob 检查

**生命周期验证：**
1. `variant_set_destroys_temp_pack_vars` — 验证临时 pack 的 key/value Variant 被正确析构
2. `variant_set_value_semantic_writeback_path_destroys_self_temp` — 验证值语义回写路径中的 self 临时 Variant 被正确析构

#### 验收标准

- [ ] 全部测试用例通过
- [ ] 覆盖 4 种 SET 指令
- [ ] 覆盖 self 分类策略（直接 Variant / 引用语义 / 值语义回写 / 不支持类型）
- [ ] 覆盖 Variant/非 Variant key、value 组合
- [ ] 覆盖错误路径
- [ ] 覆盖 r_valid 和 r_oob 检查
- [ ] 覆盖临时变量生命周期

---

### 步骤 6：编译验证与现有测试回归

**执行命令**：

```bash
./gradlew classes --no-daemon --info --console=plain
./gradlew test --tests IndexLoadInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests IndexStoreInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CCodegenTest --no-daemon --info --console=plain
./gradlew test --no-daemon --info --console=plain
```

#### 验收标准

- [ ] 编译无错误
- [ ] 新增测试全部通过
- [ ] 现有测试无回归

---

## 4. 生成的 C 代码示例

### 4.1 variant_get（self=Variant, key=Variant, result=Variant）

LIR:
```
$result = variant_get $dict $key
```

生成 C 代码：
```c
godot_Variant __gdcc_tmp_idx_ret_0;
GDExtensionBool __gdcc_tmp_idx_valid_1 = false;
godot_variant_get(&$dict, &$key, &__gdcc_tmp_idx_ret_0, &__gdcc_tmp_idx_valid_1);
if (!__gdcc_tmp_idx_valid_1) {
    GDCC_PRINT_RUNTIME_ERROR("variant_get failed", __func__, __FILE__, __LINE__);
    __gdcc_tmp_idx_ret_0 = godot_new_Variant_nil();
    godot_variant_destroy(&__gdcc_tmp_idx_ret_0);
    goto __finally__;
}
$result = godot_new_Variant_with_Variant(&__gdcc_tmp_idx_ret_0);
godot_variant_destroy(&__gdcc_tmp_idx_ret_0);
```

### 4.2 variant_get（self=Array, key=int, result=String）

LIR:
```
$result = variant_get $arr $idx
```

生成 C 代码：
```c
godot_Variant __gdcc_tmp_idx_self_0 = godot_new_Variant_with_Array(&$arr);
godot_Variant __gdcc_tmp_idx_key_1 = godot_new_Variant_with_int($idx);
godot_Variant __gdcc_tmp_idx_ret_2;
GDExtensionBool __gdcc_tmp_idx_valid_3 = false;
godot_variant_get(&__gdcc_tmp_idx_self_0, &__gdcc_tmp_idx_key_1, &__gdcc_tmp_idx_ret_2, &__gdcc_tmp_idx_valid_3);
if (!__gdcc_tmp_idx_valid_3) {
    GDCC_PRINT_RUNTIME_ERROR("variant_get failed", __func__, __FILE__, __LINE__);
    __gdcc_tmp_idx_ret_2 = godot_new_Variant_nil();
    godot_variant_destroy(&__gdcc_tmp_idx_ret_2);
    godot_variant_destroy(&__gdcc_tmp_idx_key_1);
    godot_variant_destroy(&__gdcc_tmp_idx_self_0);
    goto __finally__;
}
$result = godot_new_String_with_Variant(&__gdcc_tmp_idx_ret_2);
godot_variant_destroy(&__gdcc_tmp_idx_ret_2);
godot_variant_destroy(&__gdcc_tmp_idx_key_1);
godot_variant_destroy(&__gdcc_tmp_idx_self_0);
```

### 4.3 variant_get_named（self=Variant, name="position", result=Vector3）

LIR:
```
$result = variant_get_named $obj "position"
```

生成 C 代码：
```c
godot_Variant __gdcc_tmp_idx_ret_0;
GDExtensionBool __gdcc_tmp_idx_valid_1 = false;
godot_variant_get_named(&$obj, GD_STATIC_SN(u8"position"), &__gdcc_tmp_idx_ret_0, &__gdcc_tmp_idx_valid_1);
if (!__gdcc_tmp_idx_valid_1) {
    GDCC_PRINT_RUNTIME_ERROR("variant_get_named 'position' failed", __func__, __FILE__, __LINE__);
    __gdcc_tmp_idx_ret_0 = godot_new_Variant_nil();
    godot_variant_destroy(&__gdcc_tmp_idx_ret_0);
    goto __finally__;
}
$result = godot_new_Vector3_with_Variant(&__gdcc_tmp_idx_ret_0);
godot_variant_destroy(&__gdcc_tmp_idx_ret_0);
```

### 4.4 variant_get_indexed（self=Variant, index=2, result=Variant）

LIR:
```
$result = variant_get_indexed $arr 2
```

生成 C 代码：
```c
godot_Variant __gdcc_tmp_idx_ret_0;
GDExtensionBool __gdcc_tmp_idx_valid_1 = false;
GDExtensionBool __gdcc_tmp_idx_oob_2 = false;
godot_variant_get_indexed(&$arr, (GDExtensionInt)2, &__gdcc_tmp_idx_ret_0, &__gdcc_tmp_idx_valid_1, &__gdcc_tmp_idx_oob_2);
if (!__gdcc_tmp_idx_valid_1) {
    GDCC_PRINT_RUNTIME_ERROR("variant_get_indexed failed", __func__, __FILE__, __LINE__);
    __gdcc_tmp_idx_ret_0 = godot_new_Variant_nil();
    godot_variant_destroy(&__gdcc_tmp_idx_ret_0);
    goto __finally__;
}
if (__gdcc_tmp_idx_oob_2) {
    GDCC_PRINT_RUNTIME_ERROR("variant_get_indexed: index out of bounds", __func__, __FILE__, __LINE__);
    godot_variant_destroy(&__gdcc_tmp_idx_ret_0);
    goto __finally__;
}
$result = godot_new_Variant_with_Variant(&__gdcc_tmp_idx_ret_0);
godot_variant_destroy(&__gdcc_tmp_idx_ret_0);
```

### 4.5 variant_set（self=Variant, key=Variant, value=Variant）

LIR:
```
variant_set $dict $key $value
```

生成 C 代码：
```c
GDExtensionBool __gdcc_tmp_idx_valid_0 = false;
godot_variant_set(&$dict, &$key, &$value, &__gdcc_tmp_idx_valid_0);
if (!__gdcc_tmp_idx_valid_0) {
    GDCC_PRINT_RUNTIME_ERROR("variant_set failed", __func__, __FILE__, __LINE__);
    goto __finally__;
}
```

### 4.6 variant_set_named（self=Variant, name="health", value=int）

LIR:
```
variant_set_named $obj "health" $hp
```

生成 C 代码：
```c
godot_Variant __gdcc_tmp_idx_val_0 = godot_new_Variant_with_int($hp);
GDExtensionBool __gdcc_tmp_idx_valid_1 = false;
godot_variant_set_named(&$obj, GD_STATIC_SN(u8"health"), &__gdcc_tmp_idx_val_0, &__gdcc_tmp_idx_valid_1);
if (!__gdcc_tmp_idx_valid_1) {
    GDCC_PRINT_RUNTIME_ERROR("variant_set_named 'health' failed", __func__, __FILE__, __LINE__);
    godot_variant_destroy(&__gdcc_tmp_idx_val_0);
    goto __finally__;
}
godot_variant_destroy(&__gdcc_tmp_idx_val_0);
```

### 4.7 variant_set_indexed（self=Variant, index=0, value=int）

LIR:
```
variant_set_indexed $arr 0 $val
```

生成 C 代码：
```c
godot_Variant __gdcc_tmp_idx_val_0 = godot_new_Variant_with_int($val);
GDExtensionBool __gdcc_tmp_idx_valid_1 = false;
GDExtensionBool __gdcc_tmp_idx_oob_2 = false;
godot_variant_set_indexed(&$arr, (GDExtensionInt)0, &__gdcc_tmp_idx_val_0, &__gdcc_tmp_idx_valid_1, &__gdcc_tmp_idx_oob_2);
if (!__gdcc_tmp_idx_valid_1) {
    GDCC_PRINT_RUNTIME_ERROR("variant_set_indexed failed", __func__, __FILE__, __LINE__);
    godot_variant_destroy(&__gdcc_tmp_idx_val_0);
    goto __finally__;
}
if (__gdcc_tmp_idx_oob_2) {
    GDCC_PRINT_RUNTIME_ERROR("variant_set_indexed: index out of bounds", __func__, __FILE__, __LINE__);
    godot_variant_destroy(&__gdcc_tmp_idx_val_0);
    goto __finally__;
}
godot_variant_destroy(&__gdcc_tmp_idx_val_0);
```

---

## 5. 关键实现锚点

| 用途 | 文件路径 |
|---|---|
| CInsnGen 接口 | `src/main/java/dev/superice/gdcc/backend/c/gen/CInsnGen.java` |
| CBodyBuilder（代码构建器） | `src/main/java/dev/superice/gdcc/backend/c/gen/CBodyBuilder.java` |
| CGenHelper（类型渲染/pack-unpack 函数名） | `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java` |
| CCodegen（注册入口） | `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java` |
| IndexingInstruction 接口 | `src/main/java/dev/superice/gdcc/lir/insn/IndexingInstruction.java` |
| GdInstruction 枚举 | `src/main/java/dev/superice/gdcc/enums/GdInstruction.java` |
| OperatorInsnGen（参考：Variant evaluate 模式） | `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OperatorInsnGen.java` |
| ConstructInsnGen（参考：pattern match 分派） | `src/main/java/dev/superice/gdcc/backend/c/gen/insn/ConstructInsnGen.java` |
| C helper 头文件 | `src/main/c/codegen/include_451/gdcc/gdcc_helper.h` |
| GDExtension C API 声明（gdextension-lite） | `tmp/inspect_gdlite/generated/extension_interface.h` |

---

## 6. 关键 CBodyBuilder API 使用指南

### 6.1 用于 GET 指令的核心 API

```java
// 获取当前指令（类型安全）
var insn = bodyBuilder.getCurrentInsn(this);

// 解析变量
var resultVar = bodyBuilder.func().getVariableById(resultId);
var selfVar = bodyBuilder.func().getVariableById(variantId);

// 创建变量引用
bodyBuilder.valueOfVar(variable)           // 读取变量值
bodyBuilder.targetOfVar(variable)          // 写入目标

// 临时变量（未初始化，用于 r_ret 输出参数）
var retTemp = bodyBuilder.newTempVariable("idx_ret", GdVariantType.VARIANT);
bodyBuilder.declareUninitializedTempVar(retTemp);

// 临时变量（初始化，用于 r_valid 标志）
var validTemp = bodyBuilder.newTempVariable("idx_valid", GdBoolType.BOOL, "false");
bodyBuilder.declareTempVar(validTemp);

// Pack 操作（通过 callAssign 到临时变量）
var packFunc = bodyBuilder.helper().renderPackFunctionName(selfVar.type());
bodyBuilder.callAssign(selfTemp, packFunc, GdVariantType.VARIANT, List.of(bodyBuilder.valueOfVar(selfVar)));

// Unpack 操作（通过 callAssign 到 result 目标）
var unpackFunc = bodyBuilder.helper().renderUnpackFunctionName(resultVar.type());
bodyBuilder.callAssign(bodyBuilder.targetOfVar(resultVar), unpackFunc, resultVar.type(), List.of(retTemp));

// Variant 回写（构造拷贝）
bodyBuilder.callAssign(bodyBuilder.targetOfVar(resultVar), "godot_new_Variant_with_Variant",
    GdVariantType.VARIANT, List.of(retTemp));

// 销毁临时变量
bodyBuilder.destroyTempVar(retTemp);

// StringName 字面量
bodyBuilder.valueOfStringNamePtrLiteral("property_name")
// 渲染为 GD_STATIC_SN(u8"property_name")

// 手动行发射（用于低级 C 调用和条件检查）
bodyBuilder.appendLine("godot_variant_get(&..., &..., &..., &...);");
bodyBuilder.appendLine("if (!__gdcc_tmp_idx_valid_0) {");

// 错误报告
bodyBuilder.appendLine("GDCC_PRINT_RUNTIME_ERROR(\"...\", __func__, __FILE__, __LINE__);");

// 安全返回
bodyBuilder.returnVoid();  // void 函数
bodyBuilder.returnValue(bodyBuilder.valueOfExpr(CBodyBuilder.renderDefaultValueExpr(returnType), returnType));
```

### 6.2 `renderStaticStringNameLiteral` 用法

```java
// 在 CBodyBuilder 上有静态方法
CBodyBuilder.renderStaticStringNameLiteral("property_name")
// → GD_STATIC_SN(u8"property_name")
```

---

## 7. 风险与防线

### 7.1 风险：Variant 生命周期泄漏

- **描述**：GET 指令的 `r_ret` 是未初始化 Variant 输出参数，GDExtension API 会写入一个新的 Variant。如果不在所有路径上销毁，会导致内存泄漏。
- **防线**：
  1. 正常路径：unpack 后立即销毁 `r_ret`。
  2. 错误路径（r_valid=false）：先将 `r_ret` 赋为 `godot_new_Variant_nil()` 使其安全可析构，再销毁。
  3. 错误路径（r_oob=true）：此时 `r_ret` 已被 API 写入有效值，直接销毁。
  4. 单元测试显式验证生成代码中包含 `godot_variant_destroy` 调用。

### 7.2 风险：SET 指令的 self 类型误用

- **描述**：若不区分 self 的语义类别，容易出现两类问题：
  1. 把不支持 set 的类型放进 `variant_set*`，导致运行时 `r_valid=false`；
  2. 对值语义类型执行 set 后未回写，导致修改丢失。
- **防线**：
  1. IndexStoreInsnGen 在生成代码前执行 self 分类（Variant / 引用语义 / 值语义 / 不支持）。
  2. 不支持类型编译期 fail-fast 并给出清晰错误信息。
  3. 值语义路径强制执行 unpack 回写，并通过单元测试覆盖。

### 7.3 风险：`variant_get_indexed` / `variant_set_indexed` 的 r_oob 语义

- **描述**：`_indexed` 变体在 r_valid=true 但 r_oob=true 时的行为需要明确。Godot 引擎在 OOB 时仍会写入 `r_ret`（对 GET 来说是默认值）。
- **防线**：
  1. 先检查 `r_valid`，再检查 `r_oob`，保持与 Godot 内部检查顺序一致。
  2. OOB 时打印运行时错误并安全返回。
  3. OOB 路径销毁 `r_ret`（因为 API 已写入值）。

### 7.4 风险：与 OperatorInsnGen 的 Variant 回写策略不一致

- **描述**：GET 指令的 result 回写必须与 OperatorInsnGen 的 `variant_evaluate` result 回写保持一致：Variant→Variant 使用构造拷贝，Variant→非 Variant 使用 unpack。
- **防线**：
  1. 实现代码直接参照 OperatorInsnGen 的 `emitBinaryVariantEvaluate` 中的回写模式。
  2. 单元测试验证 Variant→Variant 路径生成 `godot_new_Variant_with_Variant`。

---

## 8. 长期约定（实现后必须保持）

### 8.1 SET 指令的 self 类型约束

- `variant_set*` 指令的 self 按策略分类处理：`GdVariantType` 直传、引用语义类型可 pack、值语义类型 pack+回写。
- 对不支持 set 的类型保持编译期 fail-fast。
- 该约定优先于“self 必须是 Variant”这一旧规则。

### 8.2 GET 指令的 r_ret 生命周期

- `r_ret` 使用 `declareUninitializedTempVar` 声明。
- 所有代码路径（正常/错误）必须在退出前销毁 `r_ret`。
- 错误路径中如果 `r_ret` 未被 API 写入（r_valid=false 时，Godot 可能未初始化 r_ret），需要先赋 `godot_new_Variant_nil()` 再销毁。

### 8.3 生成器拆分维护

- GET 和 SET 分属两个独立生成器（`IndexLoadInsnGen` / `IndexStoreInsnGen`）。
- 如果未来增加新的 indexing 指令，按 ReturnKind（REQUIRED/NONE）分配到对应生成器。

---

## 9. 回归测试基线

### 9.1 新增测试文件

- `src/test/java/dev/superice/gdcc/backend/c/gen/IndexLoadInsnGenTest.java`
- `src/test/java/dev/superice/gdcc/backend/c/gen/IndexStoreInsnGenTest.java`

### 9.2 建议执行命令

```bash
# 编译
./gradlew classes --no-daemon --info --console=plain

# 新增测试
./gradlew test --tests IndexLoadInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests IndexStoreInsnGenTest --no-daemon --info --console=plain

# 回归测试
./gradlew test --tests CCodegenTest --no-daemon --info --console=plain
./gradlew test --tests COperatorInsnGenTest --no-daemon --info --console=plain
./gradlew test --tests CConstructInsnGenTest --no-daemon --info --console=plain

# 全量测试
./gradlew test --no-daemon --info --console=plain
```

---

## 10. 非目标（当前不做）

- 不修改现有 8 个 LIR `IndexingInstruction` record 类。
- 不修改 `GdInstruction` 枚举定义。
- 不修改 `IndexingInstruction` 接口。
- 不在 `gdcc_helper.h` 中添加 C helper 包装函数（直接使用 gdextension-lite 提供的 API）。
- 不添加引擎集成测试（本计划仅覆盖单元测试；引擎集成测试作为后续单独任务）。
- 不处理 variant_get*/set* 结果类型的编译期类型检查（运行时 r_valid 检查已足够）。
