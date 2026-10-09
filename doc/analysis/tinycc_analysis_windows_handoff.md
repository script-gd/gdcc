# TinyCC Native Compiler Backend Handoff

Date: 2026-10-09. Repository: `E:/Projects/gdcc`.

## 1. Status and Scope

**Research and temporary probes are complete; production integration has not
started.** TinyCC is feasible as another native C compiler implementation
under the existing C backend. It is not a replacement frontend, LIR, or C
code generator.

Verified on Windows x86_64:

- The pinned TinyCC compiles the adapted GDCC sample and links a real DLL.
- Minicoro's Win64 ASM context-switch path works, including static TLS.
- Java 25 can call a freshly built `libtcc` DLL through FFM, compile five C
  translation units in-process, and write the GDCC DLL without `tcc.exe`.
- Compiler errors can be delivered to Java through an FFM upcall. Deleting
  the failed state and starting a fresh one permits subsequent compilation.
- The CLI-built and FFM-built artifacts both load and execute successfully in
  an isolated Godot 4.5.1 runtime test.
- The Java-created native test EXE also compiles through FFM and passes.

Not implemented: compiler selection in API/CLI/editor, a production
`CCompiler` implementation, packaged TinyCC resources, cancellation policy,
release/debug capability mapping, or broad backend regression coverage.

This handoff records observations and proposed follow-up work. It does not
amend the authoritative backend/runtime contracts or make undecided API
choices. Existing unrelated worktree changes were not made or reverted by
this investigation.

## 2. Exact Environment

| Component                | Tested version / pin                                                        |
|--------------------------|-----------------------------------------------------------------------------|
| TinyCC                   | `mob` snapshot `43c7708b85681a2fd4451c8a541af4494a8919b2`                   |
| TinyCC version output    | `tcc version 0.9.28rc 2026-10-03_HEAD@43c7708b (x86_64 Windows)`            |
| Minicoro                 | Vendored `02dad0f8b7cbb12fe6e216ae7a76db15ca55cd7b`; header banner `v0.2.0` |
| Java                     | Oracle GraalVM JDK `25.0.1+8-LTS-jvmci-b01`, x64                            |
| Zig bootstrap compiler   | Zig `0.16.0`, `E:/zigup/zig/0.16.0/zig.exe`                                 |
| Godot                    | `4.5.1.stable.official.f62fdbde1`, Windows x64                              |
| Godot console executable | `C:/Application/Godot/4.5.1/Godot_v4.5.1-stable_win64_console.exe`          |

Pinned upstream sources:

- [TinyCC commit](https://github.com/TinyCC/tinycc/commit/43c7708b85681a2fd4451c8a541af4494a8919b2)
- [libtcc public API](https://github.com/TinyCC/tinycc/blob/43c7708b85681a2fd4451c8a541af4494a8919b2/libtcc.h)
- [Windows build script](https://github.com/TinyCC/tinycc/blob/43c7708b85681a2fd4451c8a541af4494a8919b2/win32/build-tcc.bat)
- [Windows headers/import-definition documentation](https://github.com/TinyCC/tinycc/blob/43c7708b85681a2fd4451c8a541af4494a8919b2/win32/tcc-win32.txt)
- [Pinned minicoro header](https://github.com/edubart/minicoro/blob/02dad0f8b7cbb12fe6e216ae7a76db15ca55cd7b/minicoro.h)

Do not infer compatibility with other TinyCC snapshots, JVM architectures,
operating systems, or cross-targets from these results.

## 3. Evidence and Temporary Artifacts

The original generated sample was copied in full from:

`src/editor_addon/.godot/gdcc/gdcc_editor_compile_92477979_48140`

to:

`tmp/gdcc_editor_compile_92477979_48140`

The copy contained 1,962 files, approximately 81.3 MiB, including the original
Zig cache and binaries. Those copied objects/caches/DLLs were not used for
the TinyCC builds. The sample defines `Test2 : MeshInstance3D`; its source
fixture is `src/editor_addon/src/test2.gd3`.

| Path relative to repository root                             | Contents                                                             |
|--------------------------------------------------------------|----------------------------------------------------------------------|
| `tmp/tcc-research/tinycc/`                                   | Checkout of the pinned TinyCC source                                 |
| `tmp/tcc-research/tinycc/win32/`                             | Built CLI/compiler library and TinyCC header/runtime tree            |
| `tmp/tcc-research/RESULTS.md`                                | Initial ASM, TLS, Fiber, and bootstrap findings                      |
| `tmp/tcc-research/probes/`                                   | Standalone minicoro/TLS/Fiber sources and binaries                   |
| `tmp/gdcc_editor_compile_92477979_48140/TINYCC_PROBE.md`     | Generated-C CLI probe, all 11 source adaptations, results            |
| `tmp/gdcc_editor_compile_92477979_48140/TINYCC_FFM_PROBE.md` | Java FFM build and runtime verification                              |
| `tmp/gdcc_editor_compile_92477979_48140/TinyCcFfmProbe.java` | Actual Java FFM implementation                                       |
| `tmp/gdcc_editor_compile_92477979_48140/ffm-probe.log`       | FFM calls/timings, expected error callback, native and Godot results |

`tmp/` is gitignored and disposable. A fresh clone will not contain these
scripts, logs, binaries, or sample copies. This document preserves the
findings, required adaptations, and API flow; it is not a complete archived
copy of the generated sample. Preserve/promote focused test sources before
cleaning `tmp`, if they are needed for production work. Do not promote the
81 MiB compiler-cache copy as a test fixture.

## 4. Minicoro Findings

### 4.1 Windows x64 ASM is executable byte arrays

The Win64 implementation in `minicoro.h` uses `_mco_wrap_main_code[]` and
`_mco_switch_code[]`, containing already encoded machine instructions. It
places them in `.text` and calls them through function pointers. It does not
require TinyCC to parse the non-Windows GNU assembler text.

The relevant TinyCC predefined macros are:

```text
__TINYC__ = 928
_WIN32, _WIN64, __x86_64__ are defined
__GNUC__ and _MSC_VER are not defined
```

GDCC's `minicoro.c` explicitly locks `MCO_USE_ASM` and
`MCO_USE_VMEM_ALLOCATOR`. The original header only defines `_MCO_ASM_BLOB`
under GCC or MSVC. Thus the unadapted compile fails at the first machine-code
array declaration, rather than silently falling back to Fiber.

Adding `defined(__TINYC__)` to the GCC `.text` attribute branch fixes this:
TinyCC supports `__attribute__((section(".text")))`, and execution was tested
in both EXE and dynamically loaded DLL forms. Do not define the attribute
macro to an empty value: placing code in ordinary non-executable data is not
a valid fix. No machine instructions were changed and GCC was not spoofed.

### 4.2 TLS detection must also recognize TinyCC

TinyCC implements both `__thread` and `_Thread_local`; separate native tests
verified per-thread initialization/isolation. However, minicoro's original
detection misses TinyCC and uses a process-global `mco_current_co`.

Even under `-std=c11`, TinyCC defines `__STDC_NO_THREADS__`. This causes the
standard-thread detection branch to be skipped; absence of the GCC/MSVC
macros then triggers the no-TLS fallback. This macro is not proof that the
compiler lacks static TLS.

Add `defined(__TINYC__)` to the existing `__thread` selection branch. The
unadapted default DLL failed a forced four-thread race with minicoro
assertions; the TLS-enabled DLL passed. The generated-C probe also passed
four concurrent threads, each performing 100 yields with TLS identity,
stack-array, and floating-local checks.

Retain the runtime's POD TLS/lifetime contract; adding TLS destructors is
not part of this investigation.

### 4.3 Fiber works after supplying a missing import, but is not recommended

The pinned TinyCC WinAPI headers declare the Fiber APIs, but the bundled
`kernel32.def` lacks `CreateFiberEx`. The Fiber build therefore fails at
linking. A local supplemental import definition containing:

```text
LIBRARY kernel32.dll
EXPORTS
CreateFiberEx
```

allowed the test to compile and run. Ordinary threads, worker threads, and
a thread already converted with `ConvertThreadToFiber` all passed.

There is a documented discrepancy: `doc/gdcc_runtime_lib.md` states Fiber
is forbidden because converting an already-Fiber Godot thread fails. The
pinned implementation first checks `GetCurrentFiber` and reuses an existing
Fiber, and the standalone pre-converted-thread test did not reproduce that
failure. This does **not** validate Godot's complete Fiber/FLS interaction or
hot reload, and is not permission to relax the existing runtime contract.

Recommendation for the first TinyCC integration: retain ASM + VMEM, which
already works. Resolve the explanatory discrepancy separately with source
and engine evidence; do not silently switch to Fiber.

## 5. Generated-C Compatibility Changes

Exactly 11 files in the temporary generated copy were modified. Original
UTF-8 BOM and newline styles were restored after editing.

| Copied file                       | Minimal adaptation                                            |
|-----------------------------------|---------------------------------------------------------------|
| `entry.c`                         | Remove 20 `u8` literal prefixes on 15 lines                   |
| `entry.h`                         | Remove 8 prefixes on 3 lines                                  |
| `engine_method_binds.h`           | Remove 4 prefixes                                             |
| `include/gdcc/gdcc_bind.h`        | Remove 3 prefixes on 2 lines                                  |
| `include/godot/godot_macros.h`    | Add `<stdbool.h>` centrally                                   |
| `include/gdcc/gdcc_string.h`      | Registry initializer `{nullptr}` -> `{NULL}`                  |
| `include/gdcc/gdcc_string_name.h` | Registry initializer `{nullptr}` -> `{NULL}`                  |
| `include/gdcc/gdcc_callable.h`    | Registry initializer `{nullptr}` -> `{NULL}`                  |
| `include/gdcc/gdcc_operator.h`    | Two `pow_int` intermediates: `__int128` -> `uint64_t`         |
| `include/gdcc/minicoro.h`         | Recognize `__TINYC__` for executable blobs and TLS            |
| `include/gdcc/gdcc_call.h`        | Recognize `__TINYC__` for the GNU statement-expression branch |

### 5.1 Strings, booleans, and null pointers

The 35 removed prefixes were on ASCII literals. Removing them preserves the
sample's bytes but does not prove Unicode/escape correctness. Comments and
`decode_u8` / `encode_u8` identifiers were unchanged.

Use `-std=c11` for the probe. Including `<stdbool.h>` supplies `bool`, `true`,
and `false`; it also keeps the generated C11 layout assertions active.
The three `NULL` changes preserve null-pointer and remaining zero-field
initialization. There was no global `#define nullptr` or macro spoofing.

### 5.2 Integer power

The original `pow_int` already used exponentiation by squaring. Its algorithm
and negative-exponent special cases were retained; only the two intermediate
declarations changed to `uint64_t`, with the base converted to `uint64_t`.

Unsigned multiplication is modulo `2^64`, retaining the intended low bits
without signed-overflow UB. Do not substitute signed `int64_t` intermediates.
For computations where the original arithmetic was defined, the low bits
are preserved; sufficiently large powers could already overflow signed
`__int128` in the old implementation. The final out-of-range conversion to
signed `godot_int` is implementation-defined in C11 and was tested on this
target, not proven portable by the experiment.

The native test covers 14 cases: zero/negative exponents, negative bases,
`2^63`, `2^64`, `3^40`, and `INT64_MIN`/`INT64_MAX` boundaries.

### 5.3 Dynamic-call macro selection

Without `__TINYC__` recognition, `gdcc_call.h` selects its non-GNU variadic
fallback. It passes contiguous Variants to a helper expecting a pointer
array, producing warnings in three translation units. TinyCC supports the
existing statement expressions and `_Generic`, so selecting that branch
removed the warnings without casting them away or suppressing diagnostics.

`GD_OBJECT_CALL1` was separately compile-checked, but the Godot sample does
not exercise `GD_OBJECT_CALL*`; actual dynamic-call behavior still needs a
runtime test.

### 5.4 Production adaptation must reach the producers

Do not apply the temporary edits as a post-generation search/replace and
call the feature implemented. `CProjectBuilder` re-extracts packaged runtime
files each build; local edits can be replaced. Generated literals originate
in both Java helpers and FreeMarker templates.

Relevant production locations, all under `src/main`:

- `c/codegen/include_451/gdcc/`: the seven adapted runtime headers above.
- `c/codegen/include_451/godot/godot_macros.h`.
- `c/codegen/template_451/entry.c.ftl`, `entry.h.ftl`, and `engine_method_binds.h.ftl`.
- `java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`: String/StringName and direct literal emission.
- `java/gd/script/gdcc/backend/c/gen/CGenHelper.java`: binding/property metadata literal expressions.
- `java/gd/script/gdcc/backend/c/gen/CBuiltinBuilder.java`: NodePath string construction.
- `java/gd/script/gdcc/backend/c/gen/insn/ConstructInsnGen.java`: Callable identity literals.

Decide whether portable syntax is shared by both compilers or a compiler-aware
codegen surface is needed. Preserve Zig behavior and extend Unicode coverage
before choosing. No such design choice was implemented by the probe.

## 6. Dynamic Compiler Library and Resource Requirements

### 6.1 Build the library

The official Windows script builds `libtcc.dll` from `libtcc.c` with
`LIBTCC_AS_DLL`. The experiment rebuilt a separate DLL, leaving the CLI
toolchain intact. The command shape is:

```text
zig cc -target x86_64-windows-gnu -fno-sanitize=undefined -shared
  -DTCC_TARGET_PE -DTCC_TARGET_X86_64 -DLIBTCC_AS_DLL
  <tinycc-source>/libtcc.c -o <probe>/libtcc_gdcc_ffm.dll
```

For the tested Zig bootstrap, omitting `-fno-sanitize=undefined` caused an
alignment panic in TinyCC's PE linker (`pe_build_imports`, unaligned 8-byte
store). The workaround changes bootstrap flags, not TinyCC source.

The rebuilt compiler library is 536,064 bytes and imports Windows UCRT API
sets plus `KERNEL32.dll`. Those dependencies were available on this machine;
distribution requirements for supported Windows installations need checking.

### 6.2 A compiler DLL alone is not a complete distribution

No separately installed Zig sysroot, MSVC SDK, or MinGW toolchain was required
by the runtime compilation. **TinyCC's own resource tree was still required.**

Recommended bundle shape, not yet packaged by GDCC:

```text
tinycc/
  libtcc.dll
  include/
    <TinyCC C headers, including tccdefs.h>
    winapi/
      <compatible Windows headers>
  lib/
    libtcc1.a
    msvcrt.def
    kernel32.def
    user32.def
    gdi32.def
    <other bundled runtime/import resources>
```

`libtcc1.a` contains compiler support and Windows startup code. `.def` files
provide import definitions, not the actual system DLLs. For DLL output,
TinyCC's default runtime setup looks for `msvcrt`, `kernel32`, `user32`, and
`gdi32`; do not shrink the package just because the final sample only imports
`msvcrt.dll` and `kernel32.dll`.

Set `tcc_set_lib_path` to this root **before** `tcc_set_output_type` expands
`{B}` into include/library paths. The probe used
`tmp/tcc-research/tinycc/win32`; its `tcc.exe` is not used by the FFM path.
Add generated GDCC/Godot directories with `tcc_add_include_path` separately.

The input program still needs its generated `.c`/`.h` and GDCC/Godot runtime
sources. `godot_binding.c` aggregates its four binding sources; do not compile
those four individually again. No Godot import library was needed by this
sample because it resolves GDExtension functions at runtime.

If resources ship in a JAR, extract them to real files first. Neither the
tested loader nor include/link APIs accept JAR resource URLs as file paths.
Version/architecture-keyed extraction, concurrent extraction, license notices,
and resource-cache cleanup remain implementation work. Review the upstream
licenses of TinyCC and its bundled dependencies before distribution.

## 7. Java 25 FFM Contract

The probe uses only JDK classes: `java.lang.foreign`, method handles, and
ordinary file/hash utilities. No JNI bridge or JNA was needed.

| C API                  | FFM return | FFM arguments               |
|------------------------|------------|-----------------------------|
| `tcc_new`              | `ADDRESS`  | none                        |
| `tcc_delete`           | void       | `ADDRESS`                   |
| `tcc_set_lib_path`     | void       | `ADDRESS, ADDRESS`          |
| `tcc_set_error_func`   | void       | `ADDRESS, ADDRESS, ADDRESS` |
| `tcc_set_options`      | `JAVA_INT` | `ADDRESS, ADDRESS`          |
| `tcc_set_output_type`  | `JAVA_INT` | `ADDRESS, JAVA_INT`         |
| `tcc_add_include_path` | `JAVA_INT` | `ADDRESS, ADDRESS`          |
| `tcc_add_file`         | `JAVA_INT` | `ADDRESS, ADDRESS`          |
| `tcc_compile_string`   | `JAVA_INT` | `ADDRESS, ADDRESS`          |
| `tcc_output_file`      | `JAVA_INT` | `ADDRESS, ADDRESS`          |

The native library, JVM, and configured target are Windows x64. C pointers
are `MemorySegment`; C `int` is 32 bits. Windows C `long` is not Java `long`.
Resolve symbols using `SymbolLookup.libraryLookup(Path, Arena)`, bind with
`Linker.nativeLinker()`, and match `invokeExact` signatures precisely.

### 7.1 Build sequence and cleanup

1. Load the compiler library and bind the functions.
2. Create a fresh state and reject NULL.
3. Install a live diagnostic upcall stub.
4. Set the TinyCC runtime root, `-std=c11`, output type, and include paths.
5. Call `tcc_add_file` for each of the five inputs, checking every status.
6. On success, call `tcc_output_file`; `TCC_OUTPUT_DLL` is 4.
7. Validate the artifact before reporting success.
8. Call `tcc_delete` in `finally`, while the library/callback arena is alive.
9. Close the arena only after all native state has been deleted.

One state supports multiple independent TUs; the compiler internally resets
per-TU parsing/preprocessing state and merges symbols. No external object
stage is required. The CLI probe separately used `-c`; its intermediate
objects are ELF, while final Windows output is PE. Do not mix TinyCC objects
with Zig/MSVC objects or reuse the copied Zig cache.

This is disk artifact generation, not `TCC_OUTPUT_MEMORY` JIT execution.
Do not call `tcc_relocate` before `tcc_output_file`.

### 7.2 Diagnostics and failed states

The upcall signature is `void (*)(void*, const char*)`. Copy its transient
message to a Java String before returning; libtcc frees the native string
after the callback. Catch exceptions inside the upcall and report callback
failure to the caller, rather than unwinding across native frames.

The intentional test input `int broken = ;` returned -1 and delivered:

```text
ffm_invalid_probe.c:1: error: expression expected before ';'
```

The failed state was deleted; later fresh states built successfully in the
same JVM. Do not continue/link after a failed add/compile: `tcc_output_file`
resets the error count, so its return value alone cannot establish that all
earlier operations succeeded.

The four probe attempts were: invalid-code diagnostic, DLL build, fresh-state
DLL repeat, and a two-TU smoke EXE build. The repeated DLLs had identical
hashes. Every valid build had an empty diagnostic list.

### 7.3 Native access, threading, and process isolation

The standalone classpath probe uses:

```text
javac --release 25
java --enable-native-access=ALL-UNNAMED --illegal-native-access=deny ...
```

No preview option is needed. The project declares `open module gdcc`; a
named-module deployment needs `--enable-native-access=gdcc`, whereas a
classpath/fat-JAR deployment may need `ALL-UNNAMED`. Verify the actual launcher
before changing flags; this is a JVM deployment requirement, not a C option.

The probe is single-threaded and owns an `Arena.ofConfined()`. Production
thread/arena ownership and library lifetime must be explicit. TinyCC has
process-global state and a native compilation lock; that is not a tested
guarantee that an arbitrary concurrent API sequence is safe. No concurrent
FFM requests, callback reentrancy, or prolonged leak stress were tested.

An interrupt cannot be assumed to stop an active native downcall. Existing
Zig child-process cancellation is not reusable as-is. FFM also removes the
subprocess fault boundary: a compiler memory fault or native fatal path can
terminate the JVM. Ordinary syntax-error recovery is not native fault
isolation. Decide whether this tradeoff is acceptable or requires a worker
process before promising equivalent cancellation/timeouts.

## 8. Recorded Verification

| Check                      | Result and limit                                                                    |
|----------------------------|-------------------------------------------------------------------------------------|
| Five-TU CLI and FFM builds | PASS; final valid builds had no warnings/errors                                     |
| Native load/export         | PASS; `gdextension_entry` present                                                   |
| Integer power              | PASS; 14 edge cases                                                                 |
| Minicoro ASM/TLS           | PASS; native EXE and initial DLL probes; four-thread checks                         |
| Godot registration         | PASS; `Test2 : MeshInstance3D`, instance creation                                   |
| Properties/engine calls    | PASS; default/set/get speed, `_process`, Vector3 argument/return ABI, fmod wrapping |
| Metadata                   | PASS; JSON/static Dictionary result and deep-duplicate isolation                    |
| Shutdown                   | PASS; normal initialization/deinitialization and exit 0                             |
| FFM diagnostics            | PASS; expected syntax error delivered to Java                                       |
| Fresh-state repeat         | PASS; two FFM DLL builds byte-identical in one JVM                                  |
| FFM-generated smoke EXE    | PASS; no TinyCC subprocess                                                          |
| PE output                  | AMD64 DLL, TLS directory present; imports only `msvcrt.dll` and `kernel32.dll`      |
| Fiber alternative          | PASS after supplemental `CreateFiberEx` import; not a Godot Fiber integration test  |

Artifacts under `tmp/gdcc_editor_compile_92477979_48140`:

| Artifact                    | Bytes     | SHA256                                                             |
|-----------------------------|-----------|--------------------------------------------------------------------|
| `gdcc_tcc_probe_x86_64.dll` | 1,217,536 | `42ACC4411C89498A411445FED5FADE40F962B01D9926A72F35AF44EAEB3586EF` |
| `gdcc_ffm_probe_x86_64.dll` | 1,217,536 | `18D9385385FE97C06B872BED853BA640E5DE64254BA2232E7BC3BF7F1BC70A20` |
| `libtcc_gdcc_ffm.dll`       | 536,064   | `B7B88DA9C67D57CD7E7CEEB576D116D0EC1E0F1FAC12BE9199F66B96E06D1C77` |
| `native-smoke-ffm.exe`      | 14,848    | `6599DCE0559DB1AEC008A0C2623BF6EA99622B217574B7FC49599EC78F9A96A0` |

The CLI and FFM DLLs have different names/hashes; do not claim they are
byte-identical to each other. Repeats within each recorded path were identical.
The generated binaries ran after the Java compiler process/library lifetime
ended and do not depend on `libtcc_gdcc_ffm.dll`.

### Timing interpretation

| Local observation                             | Time     |
|-----------------------------------------------|----------|
| CLI five serial compiles + link, recorded run | 748 ms   |
| CLI final repeat                              | 277 ms   |
| FFM first DLL build                           | 589 ms   |
| FFM fresh-state repeat                        | 470 ms   |
| FFM smoke EXE build                           | 87 ms    |
| Measured Java FFM session                     | 1,375 ms |
| One-time compiler-library bootstrap           | 6,059 ms |

These are not controlled speed comparisons. Every input was recompiled.
The FFM session timer starts inside `main` before library loading; it includes
binding/upcall setup, diagnostic failure, valid builds, hashing, and cleanup.
It excludes JVM startup, `javac`, bootstrap, smoke execution, and Godot.
The per-build timers also include artifact checks/hashing.

## 9. Reproduction on the Existing Machine

Prerequisites: the temporary artifacts listed in section 3, pinned TinyCC
source and built `win32/include` + `win32/lib`, Java/Javac 25, pwsh 7, and
the Godot console executable. Zig is needed to bootstrap/rebuild libtcc,
not to compile C from Java at runtime.

From the workspace root, run commands separately:

```powershell
pwsh -NoProfile -File .\tmp\gdcc_editor_compile_92477979_48140\build-tcc-probe.ps1
pwsh -NoProfile -File .\tmp\gdcc_editor_compile_92477979_48140\test-tcc-probe.ps1
```

FFM path:

```powershell
pwsh -NoProfile -File .\tmp\gdcc_editor_compile_92477979_48140\build-libtcc-ffm.ps1
pwsh -NoProfile -File .\tmp\gdcc_editor_compile_92477979_48140\run-ffm-probe.ps1
```

The library-build step can be skipped when the matching DLL already exists.
Overrides: `build-libtcc-ffm.ps1 -Zig <exe>`;
`run-ffm-probe.ps1 -Java <exe> -Javac <exe> -Godot <console-exe>`;
the CLI test also accepts `-Godot`. Scripts resolve paths from `$PSScriptRoot`.

If the TinyCC tree is missing, clone `https://github.com/TinyCC/tinycc.git`
into `tmp/tcc-research/tinycc`, detach at the exact commit in section 2, and
run this official script from its `win32` working directory:

```powershell
.\build-tcc.bat -c "zig cc -target x86_64-windows-gnu -fno-sanitize=undefined"
```

This builds the compiler, bundled headers, and runtime archive. It does not
restore a deleted GDCC probe. If the probe/source copy is gone, regenerate
an equivalent sample, apply the section 5 adaptations, and recreate/promote
the Java/test fixtures; the disposable script paths alone are not an archive.

Inspect these logs for evidence, not only output-file existence:

- CLI: `tcc-baseline.log`, `tcc-build.log`, `tcc-final-build.log`, `tcc-tests.log`, `tcc-readobj.log`.
- FFM: `libtcc-ffm-build.log`, `ffm-probe.log`.
- Expected FFM markers: `FFM_LIBRARY_LOAD_PASS`, `FFM_DIAGNOSTIC_CALLBACK_PASS`, `FFM_DLL_PASS`, `FFM_REPEAT_PASS`,
  `FFM_SMOKE_PASS`, `FFM_ALL_PASS`.
- Native/Godot markers: `DLL_LOAD_EXPORT_PASS`, `POW_14_CASES_PASS`, `MINICORO_4_THREAD_ASM_TLS_PASS`,
  `GODOT_TCC_PROBE_PASS`.

The single syntax-error diagnostic in `ffm-probe.log` is intentional. The
isolated `ffi-godot` project references only the FFM DLL, preventing success
from accidentally loading the retained CLI or original Zig DLL.

## 10. Current Production Integration Surface

Read these current source files before implementation; the worktree is
already dirty with unrelated edits, so recheck rather than assuming a clean
historical version.

| File under `src/main/java/gd/script/gdcc/`              | Relevant responsibility                                              |
|---------------------------------------------------------|----------------------------------------------------------------------|
| `backend/c/build/CCompiler.java`                        | Native compiler SPI                                                  |
| `backend/c/build/CCompileResult.java`                   | `success`, `buildLog`, immutable `artifacts`                         |
| `backend/c/build/CBuildResult.java`                     | Generated files and phase timings                                    |
| `backend/c/build/CProjectBuilder.java`                  | Runtime extraction, generated input collection, compiler injection   |
| `backend/c/build/ZigCcCompiler.java`                    | Existing implementation, locking, cancellation, publication, PCH/LTO |
| `backend/c/build/TargetPlatform.java`                   | Target architecture/platform and shared-library naming               |
| `backend/c/build/GdextensionMetadataFile.java`          | Artifact paths and `.gdextension` metadata                           |
| `backend/c/gen/CCodegen.java` and section 5.4 producers | C generation surface                                                 |
| `api/CompileOptions.java`, `api/API.java`               | Public compile options and default builder wiring                    |
| `util/ResourceExtractor.java`                           | Extraction with content comparison and atomic file replacement       |

`CCompiler.compile` currently receives `projectDir`, `includeDirs`, `cFiles`,
`outputBaseName`, `COptimizationLevel`, and `TargetPlatform`, and returns a
`CCompileResult`. `CProjectBuilder` already supports injection/setter, but its
default remains `new ZigCcCompiler()` and API default construction uses it.
`CompileOptions` has no compiler-selection field.

The native input order is generated `.c` files (currently `entry.c`), then
`godot_binding.c`, `minicoro.c`, `gdcc_coroutine.c`, `gdcc_hrx.c`. Include roots
may be project-local, `GDCC_SHARED_INCLUDE`, or sibling `shared-include`.
`ResourceExtractor` replaces differing packaged files but deliberately leaves
stale/unrelated files. Keep explicit input lists; do not discover stale C or
object files by globbing at compile time.

The existing output basename is
`<projectName>_<debug|release>_<architecture>`, with the platform extension.
Consumers expect the shared library first in `artifacts`; intermediates do
not belong there. Zig may return a `.pdb` as an additional Windows artifact.
The result record itself does not enforce that ordering. Preserve downstream
metadata, VFS publication, and build timings when adding another compiler.

Zig currently uses a per-project in-JVM build lock and child-process
cancellation. Its linker writes directly to the final artifact path; atomic
publication should not be claimed as an existing compiler guarantee.
DEBUG is `-O0`, RELEASE is `-O2` with LTO policy. None of PCH, ThinLTO, PDB,
cross-compilation, or equivalent release optimization was validated for
TinyCC. Do not silently advertise parity or feed Zig flags to libtcc.

Authoritative documents to consult, not amend from this handoff alone:

- `doc/gdcc_c_backend.md`
- `doc/gdcc_runtime_lib.md`
- `doc/module_impl/backend/backend_build_system_implementation.md`
- `doc/module_impl/backend/operator_insn_implementation.md`
- `doc/module_impl/backend/hot_reload_implementation.md`
- `doc/module_impl/api/rpc_api_implementation.md`
- `doc/module_impl/cli/cli_implementation.md`

## 11. Decisions and Recommended Next Steps

These remain decisions, not an approved implementation plan:

1. Confirm initial support scope. Windows x64 in-process compilation is the
   only demonstrated combination; reject unvalidated targets explicitly.
2. Decide compiler selection/defaults and API/CLI/editor exposure. The
   existing `CCompiler` injection is useful, but a public field/name and any
   fallback policy have not been chosen.
3. Decide shared portable C syntax versus compiler-aware emission, then
   migrate the proven adaptations to the real resource/generation sources.
4. Package a pinned compiler DLL and its complete header/runtime resources,
   with licenses, target/version isolation, and real-file extraction.
5. Implement the FFM wrapper with explicit library/state/callback ownership,
   full status checking, diagnostics propagation, and host/target validation.
6. Define serialization, project locks, cancellation, timeouts, and acceptable
   native failure isolation. Do not equate Thread interruption with native
   cancellation or assume the native parser lock solves all API concurrency.
7. Define DEBUG/RELEASE capabilities, supported artifacts, and failed-build
   publication behavior without implying Zig optimization/debug parity.
8. Promote focused fixtures and add targeted regression coverage through
   `CProjectBuilder`, API/CLI, and Godot before calling the backend complete.

Repository permission boundary: modifications under `src`, `doc`, and `tmp`
are permitted; build scripts, launcher configuration, workflows, or other
paths require explicit approval. JVM native-access deployment changes may
cross that boundary. Do not make commits/branches/PRs without authorization.

## 12. Acceptance Gaps and Test Starting Points

Before shipping, cover the following beyond the existing sample:

- Generated `await`/nested coroutine cancellation and Godot lifecycle.
- Editor HRX hot reload, cross-generation Callable lifetime, and unload.
- Actual dynamic calls and `_Generic` macro paths, not just parse success.
- Unicode/escape-heavy String, StringName, NodePath, class/method/property
  names; non-ASCII filesystem paths and diagnostic encoding on Windows.
- Stateful failure cases: missing file/header/library, invalid options,
  compile failure, link failure, output failure, cleanup, and retry.
- JNI-free FFM native access under the actual production launcher/module,
  packaged extraction, concurrency, cancellation policy, and repeated-build
  memory/lifetime stress.
- Runtime correctness and performance for release-sized programs, plus
  supported-target rejection and unchanged Zig behavior.

Existing targeted tests to inspect/extend, not evidence already run for
TinyCC:

- `CProjectBuilderCoroutineRuntimeInputTest`, `CProjectBuilderSharedIncludeTest`, `CProjectBuilderIntegrationTest`.
- `CCoroutineGeneratedCSyntaxSmokeTest`, `GdccCoroutineRuntimeSmokeTest`.
- `COperatorInsnGenTest`, `COperatorInsnGenEngineTest`.
- `GodotEditorHotReloadIntegrationTest`.
- `GdextensionMetadataFileTest`, `TargetPlatformTest`.
- `ZigCcCompilerTest`, `ZigCcCompilerCommandTest`, `ZigCcCompilerProjectLockTest`, `ApiZigCcCompilerCancellationTest`
  for preserved behavior and contrasting cancellation contracts.

Run only relevant classes/methods with the project-required flags, for example:

```powershell
.\gradlew.bat test --tests "CProjectBuilderCoroutineRuntimeInputTest" --no-daemon --info --console=plain
```

Bottom line: **the native library/FFM compilation chain and Win64 minicoro
path are proven feasible for this sample. The remaining work is production
integration, capability policy, distribution, and broader correctness/lifetime
validation, not finding a replacement coroutine implementation.**
