# Static String/StringName 同映像重复初始化修复计划

> 本文档是 "macOS 等平台同映像重复初始化导致静态 String/StringName 失效" 问题的实施计划。
> 实施完成后按仓库惯例将结论回填 `hot_reload_implementation.md` 与 `gdcc_runtime_lib.md`，并删除本文。

## 文档状态

- 状态：In Progress（S1 已完成，S2 已完成，S3 已完成，待 S4 macOS E2E 复测）
- 关联文档：
  - `doc/module_impl/backend/hot_reload_implementation.md`：热重载合同唯一事实源（§4 Entry 生命周期、§12 已知限制）
  - `doc/gdcc_runtime_lib.md`：runtime 库切片
- 涉及源码：
  - `src/main/c/codegen/include_451/gdcc/gdcc_string_name.h`（`GD_STATIC_SN` / `GD_STATIC_SN_HASH` / `g_sn_registry`）
  - `src/main/c/codegen/include_451/gdcc/gdcc_string.h`（`GD_STATIC_S` / `g_n_registry`）
  - `src/main/c/codegen/template_451/entry.c.ftl`（deinitialize 销毁顺序，第 222-223 行调用 registry destroy）

## 1. 问题成因链路

CI 证据：run 334（commit `767bbbf`）macOS aarch64 job 中 `GodotEditorHotReloadIntegrationTest` 12 个用例全部失败，Linux x64 与 Windows x64 同用例通过。

链路：

1. 测试/编辑器触发热重载：新库原子 rename 到同一路径，Godot `dlclose` 旧库后 `dlopen` 同一路径。
2. macOS dyld 对已加载 dylib 按路径缓存，`dlclose` 不保证真正卸载映像；同路径 `dlopen` 直接复用**同一映像**（Windows 走 `~xxx.dll` 改名副本所以是新映像；Linux `dlclose` 真正卸载所以也是新映像——这解释了平台差异）。
3. `GD_STATIC_SN` / `GD_STATIC_S` 宏（`gdcc_string_name.h:49-63`、`gdcc_string.h:45-59`）用函数内 `static bool _gd_sn_inited / _gd_sn_registered` 做一次性初始化门控。同映像复用时这些 static 保持旧值 `true`。
4. 上一代的 `deinitialize()` 已经执行过 `gdcc_sn_registry_destroy_all()` / `gdcc_s_registry_destroy_all()`（`entry.c.ftl:222-223`），销毁了所有 StringName/String 值并清空了 registry；但函数内 static 标志不在 registry 可达范围内，无法被复位。
5. 新代 `initialize()` 重注册类时，宏直接返回已销毁的 `godot_StringName` 存储 → Godot 读到空字符串 → 大量 `ERROR: Attempt to register extension class '', which is not a valid class identifier.`。
6. 级联后果：类/方法/属性注册全部落空 → marker 协议超时（`HR_PHASE2_OK` 未到）、语义断言失败（`HR_FAIL: swapped lambdas must invalidate`），以及一例 SIGSEGV（`deferredLambdaCallsFollowRebindAndInvalidationSemanticsAfterReload` 中 `Object::get_instance_binding` 崩溃，exit 134；注册表半成品状态下的继发症状，本计划修复后需复测确认是否独立缺陷）。

`hot_reload_implementation.md` §12 已将"同映像重复初始化加固"列为已知非目标，本计划将其收敛为正式修复。

## 2. 方案设计

核心思路：**把"是否已初始化"的判定权从函数内 static 标志收回到 registry，用 registry 持有的代际号（generation）做门控**。registry 在 `destroy_all` 时递增代际号；同映像复用时旧代际号残留在 static 中，与 registry 新代际号不等即触发重建与重注册；全新映像则因 static 哨兵初值必然不等而正常初始化。

### 2.1 registry 增加代际号

```c
#define GDCC_REGISTRY_GEN_NEVER UINT64_MAX

typedef struct StringNameDestroyRegistry {
    godot_StringName** items;
    uint32_t count;
    uint32_t capacity;
    uint64_t generation; // destroy_all 时递增；永不等于 GDCC_REGISTRY_GEN_NEVER
} StringNameDestroyRegistry;

static StringNameDestroyRegistry g_sn_registry = {nullptr};
```

`gdcc_sn_registry_destroy_all()` 末尾递增代际号并跳过哨兵：

```c
g_sn_registry.generation++;
if (g_sn_registry.generation == GDCC_REGISTRY_GEN_NEVER) {
    // uint64 递增到哨兵需 2^64 次 reload，视为不可能；归零跳过保证 NEVER 永不与真实代际相等
    g_sn_registry.generation = 0;
}
```

`gdcc_string.h` 的 `g_n_registry` 同理。

### 2.2 宏改造（以 `GD_STATIC_SN` 为例）

```c
#define GD_STATIC_SN(U8_LIT)                                                       \
    ({                                                                             \
        static godot_StringName _gd_sn;                                            \
        static uint64_t _gd_sn_gen = GDCC_REGISTRY_GEN_NEVER;                      \
        if (unlikely(_gd_sn_gen != g_sn_registry.generation)) {                    \
            _gd_sn = godot_new_StringName_with_utf8_chars((const char*)(U8_LIT));  \
            gdcc_sn_registry_add(&_gd_sn);                                         \
            _gd_sn_gen = g_sn_registry.generation;                                 \
        }                                                                          \
        &_gd_sn;                                                                   \
    })
```

要点：

- 原来的 `_gd_sn_inited` / `_gd_sn_registered` 两个 bool 由单一 `_gd_sn_gen` 代际戳取代：代际不等时同时完成重建与重注册（registry 已被清空，必须重注册，二者不可拆分）。
- 全新映像：static 初值 `GDCC_REGISTRY_GEN_NEVER` ≠ registry 代际 0 → 正常初始化。
- 同映像复用：static 残留旧代际 n，registry 已递增到 n+1 → 不等 → 重建。
- 快速路径仍为一次 64 位比较，热路径无额外开销；不引入线程/TLS（遵守 `hot_reload_implementation.md` §3.3 "runtime 不引入线程/TLS"）。

`GD_STATIC_SN_HASH` 有自己的函数内 `_gd_sn_hash` 缓存，**必须使用独立的代际戳**（读 registry 代际号，不能复用内层 `GD_STATIC_SN` 的函数内变量——C 无法跨语句块访问另一展开点的 static）：

```c
#define GD_STATIC_SN_HASH(U8_LIT)                                                  \
    ({                                                                             \
        static godot_int _gd_sn_hash = 0;                                          \
        static uint64_t _gd_sn_hash_gen = GDCC_REGISTRY_GEN_NEVER;                 \
        godot_StringName *_gd_sn_ptr = GD_STATIC_SN((const char*)(U8_LIT));        \
        if (unlikely(_gd_sn_hash_gen != g_sn_registry.generation)) {               \
            _gd_sn_hash = godot_StringName_hash(_gd_sn_ptr);                       \
            _gd_sn_hash_gen = g_sn_registry.generation;                            \
        }                                                                          \
        (gdcc_StringNameWithHash){ .name = _gd_sn_ptr, .hash = _gd_sn_hash };      \
    })
```

`GD_STATIC_S`（`gdcc_string.h`）按 `GD_STATIC_SN` 同款模式改造，门控读 `g_n_registry.generation`。

### 2.3 不变量与边界

- **per-TU registry 边界**：两个 registry 都是头文件内 `static`，每个包含该头的 TU 各持一份副本与独立代际号。合同：**产生函数内 static 的宏调用必须写入与 `destroy_all` 调用相同 TU 的 registry 副本**。当前满足该合同的 live 调用点全部在 entry TU（`entry.c.ftl` 及其包含的 `entry.h.ftl`、`engine_method_binds.h.ftl`），`entry.c.ftl:222-223` 的 `destroy_all` 恰好作用于同一 TU 副本。注意区分预处理展开与实际调用：`gdcc_bind.h` 的 static helper（`gdcc_make_property` / `gdcc_bind_property`）内含 `GD_STATIC_S` / `GD_STATIC_SN`，会随 `#include <gdcc_helper.h>` 在每个 runtime TU（`gdcc_hrx.c`、`gdcc_coroutine.c`）中展开，但这些 helper 当前未从 runtime `.c` 被调用，对应 TU-local registry 恒为空，不构成缺陷。**禁止** runtime `.c` 新增对这些 helper 或宏本身的调用（S1 审计复核现状，S2 落地时在头文件注释中写明）。
- `deinitialize()` 内 registry destroy 之后**禁止**再使用 `GD_STATIC_SN` / `GD_STATIC_S`，该硬合同维持不变（当前成立：`gdcc_hrx.c` / `gdcc_callable.h` / `gdcc_coroutine.c` 均未使用这两个宏）。S2 实施时用 grep 复核全部展开点位置，S5 回填时只追加代际语义，不放宽此禁令。
- 同映像复用场景的其他映像驻留可变状态（`g_hrx_mode` / `g_hrx_hub` / `gdcc_active_head` / `gdcc_classdb_class_call_static_bind` / `_gd_engine` 等）需在步骤 S1 逐一审计分类：凡由 `initialize()` 显式重置的无需处理；凡跨代持久属设计的（如 `g_hrx_orphaned_hub_count`）标注理由；其余按本方案同思路修复或列入后续接缝。

### 2.4 S1 审计结论（已提前完成）

判定准则：同映像复用下映像保持映射，因此只有命中以下任一条件的 static 才会出错——(a) 持有 Godot 堆对象且在 `deinitialize()` 中被销毁；(b) 带 init-once 门控阻止下代重建；(c) 被 `deinitialize()` 改写但 `initialize()` 不重置。

- **RISK（命中 a+b）**：`GD_STATIC_SN` / `GD_STATIC_SN_HASH` / `GD_STATIC_S` 及其两个 registry——即本计划修复对象。
- **SAFE（映像内函数指针，`static const` 或运行时不再改写，同映像下保持有效；真实换映像的重载由类注销 / HRX 堆驻留 thunk / RELOADED_SHELL 合同覆盖，Linux E2E 已验证）**：默认参数 userdata 实例（`entry.c.ftl:253-260`）、vtable 实例（`:281`）、HRX rebind 表（`:40-54`）、协程描述符（`:649-654`）。
- **SAFE（每代重置）**：GDScript static backing（initialize 重跑 defaults/initializers，无 once 门控）、各 `class_library`、`_gd_engine`（各 TU 副本均由 `gdcc_init()` 重赋值）、`godot_interface.c` 接口表（每次 init 前清空）、`gdcc_standalone_callable_registry`（deinit 显式销毁、无 once 门控、按需重建）、协程 `gdcc_hot_reload_active` / `gdcc_active_head`（每代重置 + `cancel_all` 清空）、minicoro `mco_current_co` TLS（cancel 后归 NULL）。
- **INTENDED PERSISTENT（指向 Godot 进程或设计内跨代）**：builtin/utility/fixed/引擎 MethodBind 缓存、operator evaluator 缓存、`g_hrx_orphaned_hub_count`、协程 binding token。
- **仅诊断影响（不阻塞，列入后续接缝）**：`g_hrx_unavailable_reported` 一旦置位后续代不再重复报告不可用诊断，无正确性影响。

## 3. 分步骤实施与验收

### S1 映像驻留可变状态审计（已完成，结论见 §2.4）

- 改动：无代码改动；产出审计结论并更新本文 §2.3 清单。
- 方法：按符号全量审计，而不是文本行匹配（函数内 static 有缩进，`grep '^static '` 会漏掉本缺陷类型本身）。必须覆盖：
  - `src/main/c/codegen/include_451/gdcc/*.h` 与 `*.c` 中全部文件作用域非常量 `static` 变量（`g_sn_registry`、`g_n_registry`、`g_standalone_callable_registry`、`g_hrx_*`、`gdcc_active_head`、`gdcc_classdb_class_call_static_bind`、`_gd_engine` 等）；
  - 三个宏的全部函数内 static 展开点（`grep -rn 'GD_STATIC_SN\|GD_STATIC_S(' src/main/c` 逐点确认所在 TU）；
  - 每个 `#include` 这两个头文件的 TU 清单（复核 per-TU 副本不变量）；
  - 全部 `destroy_all` 调用点。
- 验收：审计表覆盖全部命中项；每项结论为"每代重置 / 跨代持久（含理由）/ 需修复（纳入 S2 范围）"。宏展开点审计表必须区分三类：(a) live 调用点所在 TU；(b) 头文件内 static helper 的预处理展开（`gdcc_bind.h` 的 `gdcc_make_property` / `gdcc_bind_property`）；(c) 仅 include、无 live 使用的 runtime TU。验收条件：(a) 与 `destroy_all` 调用同 TU；(b)(c) 无实际写入其 TU-local registry 的调用。

### S2 registry 代际号与宏改造（已完成）

- 改动：`gdcc_string_name.h`、`gdcc_string.h`（registry 结构体、`destroy_all`、三个宏）；`gdcc_bind.h` 与两个宏头补充 §2.3 per-TU 合同的禁止性注释（禁止 runtime `.c` 新增对 `gdcc_make_property` / `gdcc_bind_property` 或宏本身的调用）；如 S1 审计发现同类缺陷一并修复。
- 实施记录：S1 未发现其他同类缺陷，改动按计划收敛于两个宏头 + `gdcc_bind.h` 合同注释。`GDCC_REGISTRY_GEN_NEVER` 以 `#ifndef` 保护在两个宏头各定义一次（两床头可独立包含，禁止新增公共头文件以免扩大资源清单）。
- 验收（全部通过）：
  - `./gradlew classes` 编译通过；
  - `script/run-gradle-targeted-tests.sh --tests CCodegenTest,CCoroutineStateClassCodegenTest,GodotAbiHeaderCompileTest,GdccHrxRuntimeSmokeTest,GdccCoroutineRuntimeSmokeTest` 全绿（快照断言只命中生成的宏调用点与 destroy 顺序，宏体形态变化不影响生成代码语义契约）。

### S3 同映像重复初始化 C 层单元测试（已完成）

- 改动：新增 `GdccStaticStringRuntimeSmokeTest`（沿用 `GdccHrxRuntimeSmokeTest` 的 zig 编译可执行文件形态，链接真实 `godot_binding.c`）。probe TU 直接包含两个宏头、充当 registry 属主 TU（镜像生成的 entry TU），在同一进程内执行 `initialize → destroy_all ×2 → initialize` 序列模拟同映像复用，以构造/析构/hash 调用计数与 registry 状态为断言依据（不比较 static 存储指针）。fake engine 在 8 字节不透明负载中存放拥有的 utf8 副本，析构时毒化调用方存储，使卡死的门控必然显式失败而非静默读到旧值。
- 断言覆盖：
  - 两代 `GD_STATIC_SN` / `GD_STATIC_S` 构造次数相等，第二代返回内容非空、utf8 与 hash 语义相等；
  - 同代重复进入不重建、不重注册、不重算 hash（快速路径锚定）；
  - `destroy_all` 后 `count` / `capacity` / `items` 归零、代际号递增，析构计数等于累计构造计数；
  - 第二次 `destroy_all` 析构计数与内存余额不变（幂等，不 double-free）；
  - 第二代 registry `count` 与第一代一致（重注册完整）；
  - `GD_STATIC_SN_HASH` 的 hash 调用次数随代际增加（重算而非沿用旧缓存）；
  - 空 registry 上 `destroy_all` 安全且仍递增代际，NEVER 哨兵 statics 之后对任意代际号正常初始化（独立 probe）。
- 验收：`script/run-gradle-targeted-tests.sh --tests GdccStaticStringRuntimeSmokeTest` 两个用例在 Linux x64 通过且非跳过；另做变异验证——临时把 `GD_STATIC_SN` 门控改回 init-once 语义后测试按预期失败（`FAIL hash of destroyed StringName`），确认测试对原缺陷敏感。Windows x64 / macOS aarch64 由 CI 覆盖（S4）；现有 smoke 套件同样未启用 ASan/valgrind，内存正确性由 fake allocator 余额断言锚定，与既有手段一致。

### S4 macOS 热重载 E2E 复测

- 改动：无（验证步骤）。
- 验收：
  - macOS aarch64 CI 的 `GodotEditorHotReloadIntegrationTest` 12 个用例全部通过；
  - `deferredLambdaCallsFollowRebindAndInvalidationSemanticsAfterReload` 的 SIGSEGV 消失；若仍崩溃，则将崩溃列为独立缺陷另行立项（不得在本计划内扩大范围）；
  - Linux x64 / Windows x64 热重载用例无回归。

### S5 文档回填与收尾

- 改动：`hot_reload_implementation.md` §4 生命周期合同补"同映像重复初始化"段、§12 移除该已知限制、§10 回归基线加入 S3 新测试；`gdcc_runtime_lib.md` 更新 registry 语义；删除本文。
- 验收：文档状态改为 Implemented；grep 确认无指向本文的残留引用。

## 4. 风险与回退

- 风险 1：宏展开形态变化导致 codegen 快照测试大量失败。缓解：S2 验收中显式包含快照测试；快照差异仅限 `_gd_sn_gen` 门控形式，语义不变。
- 风险 2：审计遗漏其他映像驻留状态导致 macOS 仍有残余失败。缓解：S1 审计表是全量 grep 驱动；S4 复测兜底。
- 回退：本方案改动集中在两个头文件，git revert 即可回退；无 ABI / 生成代码布局变更。

## 5. 非目标

- 不解决 dyld 缓存本身（无法在 GDCC 侧改变 Godot 的 dlclose/dlopen 行为）。
- 不引入 "测试 harness 在 macOS 上改名副本换库" 的规避方案——真实编辑器热重载在 macOS 同样命中同映像复用，runtime 加固是唯一正确修法。
- deferred-lambda SIGSEGV 若在本计划修复后仍存在，另行立项。
