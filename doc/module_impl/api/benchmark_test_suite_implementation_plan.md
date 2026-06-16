# Benchmark Test Suite Implementation Plan

> This document is the implementation plan for the Godot-backed benchmark system under
> `src/test/java/gd/script/gdcc/test_suite`. The system compares functions executed by Godot's
> built-in GDScript interpreter with functions executed through gdcc-generated GDExtension classes,
> and reports function-body timing after subtracting measured single-call overhead.

## Document Status

- Status: implementation plan
- Updated: 2026-06-14
- Scope:
  - `src/test/java/gd/script/gdcc/test_suite/**`
  - `src/test/test_suite/benchmark/**`
  - shared runtime test infrastructure in `src/test/java/gd/script/gdcc/backend/c/build/**`
  - benchmark-facing compile and GDExtension project preparation paths
- Direct fact sources:
  - `AGENTS.md`
  - `doc/test_suite.md`
  - `doc/test_error/test_suite_engine_integration_known_limits.md`
  - `doc/test_error/string_stringname_test_suite_exposed_limits.md`
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/api/rpc_api_implementation.md`
  - `doc/module_impl/cli/cli_implementation.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_ownership_lifecycle_spec.md`
  - `doc/module_impl/backend/godot_binding_implementation.md`
  - `doc/module_impl/backend/cbodybuilder_implementation.md`
  - `doc/module_impl/backend/backend_ownership_lifecycle_contract.md`
  - `src/test/java/gd/script/gdcc/test_suite/GdScriptUnitTestCompileRunner.java`
  - `src/test/java/gd/script/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java`
  - `src/test/java/gd/script/gdcc/test_suite/GdScriptEngineVirtualOverrideRuntimeTest.java`
  - `src/test/java/gd/script/gdcc/backend/c/build/GodotGdextensionTestRunner.java`
  - `src/main/java/gd/script/gdcc/backend/c/build/CProjectBuilder.java`
  - `src/main/java/gd/script/gdcc/backend/c/build/GdextensionMetadataFile.java`
  - `src/main/java/gd/script/gdcc/backend/c/build/ZigUtil.java`
  - `src/main/java/gd/script/gdcc/util/ResourceExtractor.java`
- Explicit non-goals:
  - Do not introduce JMH or a new build plugin.
  - Do not modify Gradle build scripts.
  - Do not measure frontend or native build performance in the same benchmark metric.
  - Do not replace the existing correctness-oriented `unit_test` suite.
  - Do not depend on the removed `gdextension-lite` vendor layout.
  - Do not treat benchmark numbers as stable cross-machine regression thresholds in ordinary CI.

---

## 1. Purpose

The benchmark suite provides a repeatable local harness for runtime performance comparisons:

1. compile a benchmark GDScript source through gdcc into a native GDExtension artifact
2. install the artifact into the reusable Godot test project
3. install a matching interpreter-side GDScript script into the same project
4. launch Godot through the existing Java runner
5. run benchmark functions through both execution paths
6. subtract measured single-call overhead from each sample
7. aggregate multiple samples into mean, standard deviation, and per-path comparison data

The benchmark system must stay inside the existing test-suite architecture. It should reuse the
current resource discovery, native build, `.gdextension` generation, `extension_list.cfg` preparation,
Godot process management, and skip behavior instead of creating another end-to-end runner stack.

---

## 2. Terminology

- **Benchmark case**
  - One resource-backed benchmark identified by a relative path under `benchmark/script`.
- **Compiled target**
  - The gdcc-compiled class mounted through GDExtension and invoked from Godot.
- **Interpreter target**
  - A regular GDScript class loaded by Godot's built-in interpreter.
- **Measurement script**
  - The Godot-side script that calls both targets, measures durations, prints machine-readable
    results, and prints the shared stop signal path.
- **Invocation overhead**
  - The measured cost of crossing the same call path with a no-op or equivalent baseline function.
- **Body time**
  - `measured_duration - invocation_overhead`, clamped only by explicit reporting rules. Negative
    raw adjusted samples must be reported, not silently hidden, until the caller decides whether a
    benchmark is too small.
- **Batch**
  - One group of repeated calls measured as a single duration to reduce timer noise.
- **Sample**
  - One adjusted body-time value computed from a target batch and its overhead batch.
- **Run**
  - The full execution of all configured warmup batches and measurement samples for one case.

---

## 3. Placement

### 3.1 Java Code

Benchmark Java code belongs under `src/test/java/gd/script/gdcc/test_suite`.

The planned package is:

- `gd.script.gdcc.test_suite.benchmark`

The package should contain cohesive internals rather than many thin files:

- `GdScriptBenchmarkRunner`
  - discovers benchmark resources
  - compiles the gdcc target
  - prepares the Godot project
  - runs Godot
  - parses benchmark result lines
  - returns typed benchmark summaries
- `GdScriptBenchmarkRunnerTest`
  - JUnit entry point and resource-set contract
  - skips when `zig` or `GODOT_BIN` is unavailable
  - runs selected benchmark cases as dynamic tests
- Small nested records inside the runner for metadata, samples, summaries, and parsed result lines.

Do not add a service interface for the runner while there is only one implementation.

### 3.2 Resource Layout

Benchmark resources belong under `src/test/test_suite/benchmark`.

Initial layout:

- `src/test/test_suite/benchmark/script/**`
  - gdcc-compiled benchmark sources
- `src/test/test_suite/benchmark/interpreter/**`
  - interpreter-side scripts with matching relative paths
- `src/test/test_suite/benchmark/measurement/**`
  - per-case measurement metadata descriptors with matching relative paths
- `src/test/test_suite/benchmark/template/measurement.gd`
  - shared Godot-side measurement template used to generate the executable script installed into
    each runtime project

The runner should require the three files to exist for each benchmark case. A missing pair is a
fixture error, not a skipped benchmark.

The runner keeps the interpreter target explicit so it can represent interpreter-only workarounds
without changing compiled source semantics. Measurement descriptors contain only `# gdcc-benchmark:`
metadata; protocol output, warmup/sample loops, overhead subtraction, and behavior-check invocation
come from the shared template.

---

## 4. Benchmark Case Contract

Each case must expose the same public benchmark surface from both targets:

- `benchmark()`
  - performs the work being measured
  - returns a value or mutates state that the measurement script can validate
- `baseline()`
  - has the same receiver and call style as `benchmark()`
  - performs no benchmark work
  - returns a value compatible with the call path when needed
- optional `prepare()`
  - initializes reusable state before warmup and measurement
- optional `check(result)`
  - validates caller-visible behavior if the benchmark returns or mutates data

Benchmark source must respect current frontend and backend limits:

- avoid `for`, `match`, and `lambda` in compiled sources until frontend support lands
- avoid array and dictionary literals in compiled sources where compile mode still rejects them
- construct seed data with supported constructors and mutating methods
- avoid typed dictionary overload patterns that can collide in generated wrapper helper names
- validate caller-visible mutation when the measured function mutates state

The benchmark case contract is intentionally function-level. Engine startup, resource loading,
GDExtension initialization, and scene construction are excluded from the reported body-time metric.

---

## 5. Execution Contract

### 5.1 Compile and Build

The runner should follow the existing `GdScriptUnitTestCompileRunner` path:

1. read the compiled source resource
2. parse with `GdScriptParserService`
3. lower with `FrontendLoweringPassManager`
4. validate that the mounted root class is Godot-instantiable when the case needs a scene node
5. build release native artifacts with `CCodegen` and `CProjectBuilder`
6. assert successful build and keep build logs in failure output

Runtime benchmark artifacts must use `COptimizationLevel.RELEASE`. Debug builds are acceptable only
for troubleshooting compile or runtime failures outside benchmark measurement, and debug-run numbers
must not be written to the benchmark JSON report or compared with interpreter timings.

The generated native project must use the current runtime layout:

- `entry.c`
- `entry.h`
- `engine_method_binds.h`
- `godot/godot_binding.c`
- extracted `godot/**` and `gdcc/**` helper sources

The benchmark suite must not rely on `gdextension-lite`.

### 5.2 Godot Project Preparation

The runner should reuse `GodotGdextensionTestRunner.prepareProject(...)`:

- copy native artifacts into `test_project/bin`
- regenerate `GDExtensionTest.gdextension`
- regenerate `.godot/extension_list.cfg`
- regenerate `main.tscn`
- install the measurement script as a `res://` resource

The measurement scene should mount:

- one compiled target node using the gdcc runtime class name
- one interpreter target node using the interpreter script
- one measurement script node that owns timing and reporting

If the first implementation cannot attach an interpreter script through `SceneNodeSpec` properties
without changing `GodotGdextensionTestRunner`, add the smallest necessary runner extension to support
extra script resources and explicit scene-node `script` properties. Keep that extension generic and
runtime-test oriented; benchmark semantics stay in `GdScriptBenchmarkRunner`.

### 5.3 Godot Run

The runner should call `GodotGdextensionTestRunner.run(...)` with configurable `RunOptions`.

Default runtime behavior:

- headless by default
- process timeout higher than unit tests, because benchmark cases intentionally run repeated work
- frame budget controlled by benchmark configuration
- JUnit assumption skip when `GODOT_BIN` is missing

Benchmark output must include a pass marker and the shared `TEST_STOP_SIGNAL`. Missing either marker
is a test failure with full combined Godot output.

---

## 6. Measurement Contract

### 6.1 Timer Source

Godot-side timing should use Godot's monotonic time API from the measurement script. Java process
timing is not acceptable for function-body measurements because it includes process scheduling,
stream collection, and Godot lifecycle overhead.

The measurement script must print all raw timing values needed to recompute summaries on the Java
side. Java-side aggregation is preferred so parsing, validation, and report formatting remain under
JUnit.

### 6.2 Warmup

Each execution path must run warmup batches before samples are recorded.

Warmup requirements:

- run compiled and interpreter paths in the same Godot process
- run baseline and benchmark functions during warmup
- do not include warmup data in mean or standard deviation
- print warmup configuration in the result header

### 6.3 Invocation Overhead Removal

For each measured path and sample:

1. run a baseline batch with the same receiver and call mechanism
2. compute `overhead_per_call = baseline_duration / iterations`
3. run a benchmark batch with the same iteration count
4. compute `measured_per_call = benchmark_duration / iterations`
5. compute `body_per_call = measured_per_call - overhead_per_call`

The baseline must be measured independently for compiled and interpreter paths because their call
mechanisms can differ. A single global overhead value is not valid.

For very small benchmarks, `body_per_call` may be close to zero or negative after subtraction. The
runner should report the raw adjusted value and mark the case as unstable if configured stability
rules are violated; it should not silently clamp values to zero.

### 6.4 Sampling

Initial configuration should be explicit and easy to override:

- warmup batches: 3
- measurement samples: 10
- iterations per batch: case-configurable, default 1,000
- minimum batch duration warning: 1 ms

Configuration can come from `# gdcc-benchmark:` directives in the measurement or benchmark source.
The parser should mirror the existing `# gdcc-test:` style: strip directives before installing any
script that Godot executes, keep unknown directives as Java-side fixture errors, and require
non-blank directive values.

Supported initial directives:

- `name=<display-name>`
- `iterations=<positive-int>`
- `warmups=<non-negative-int>`
- `samples=<positive-int>`
- `min_batch_us=<positive-int>`
- `output_contains=<text>`
- `output_not_contains=<text>`

Avoid fixed enum-style benchmark categories unless they are used by multiple cases immediately.

### 6.5 Statistics

Java-side aggregation must compute:

- per-path sample count
- per-path mean body time
- per-path standard deviation using sample standard deviation
- per-path minimum and maximum body time
- compiled/interpreter ratio using mean body time
- raw overhead mean for each path
- warning flags for samples below minimum batch duration, negative adjusted values, and missing
  behavior validation

Use nanoseconds as the Java internal unit after parsing Godot output. Report human-readable values in
microseconds or milliseconds only at formatting boundaries.

---

## 7. Output Contract

Godot output should contain machine-readable result lines with stable prefixes.

Initial line shape:

```text
GDCC_BENCHMARK_RESULT case=<path> path=<compiled|interpreter> sample=<index> iterations=<n> baseline_us=<n> benchmark_us=<n> body_ns=<n>
```

Java summary line shape:

```text
[gdcc-benchmark] case=<path> compiled.mean=<duration> compiled.stddev=<duration> interpreter.mean=<duration> interpreter.stddev=<duration> ratio=<number> samples=<n> iterations=<n>
```

The Java runner must also write a machine-readable JSON report after a successful benchmark run.
The report is the durable data product; console lines are for fast local diagnosis.

Default report path:

```text
tmp/test/test_suite/benchmark/report-<case>.json
```

The path should be configurable through a benchmark runner option or environment variable after the
initial implementation, but the first implementation must keep the default under `tmp` so generated
benchmark data never mixes with source fixtures. The `<case>` portion is derived from the benchmark
resource path by removing `.gd` and replacing path separators with `-`, e.g.
`math/newton_sqrt.gd -> report-math-newton_sqrt.json`.

Initial JSON shape:

```json
{
  "schema_version": 1,
  "generated_at": "2026-06-14T00:00:00Z",
  "environment": {
    "os": "Linux",
    "arch": "x86_64",
    "java_version": "25",
    "godot_bin": "/path/to/godot",
    "godot_version": "4.5.1",
    "zig": "/path/to/zig",
    "target_platform": "LINUX_X86_64",
    "optimization": "RELEASE"
  },
  "config": {
    "warmups": 3,
    "samples": 10,
    "iterations": 1000,
    "min_batch_us": 1000
  },
  "cases": [
    {
      "case": "algorithm/int_loop.gd",
      "name": "Integer loop",
      "status": "passed",
      "warnings": [],
      "compiled": {
        "samples": 10,
        "mean_body_ns": 120.0,
        "stddev_body_ns": 8.5,
        "min_body_ns": 108,
        "max_body_ns": 134,
        "mean_overhead_ns": 35.0,
        "raw_samples": [
          {
            "sample": 0,
            "iterations": 1000,
            "baseline_us": 35,
            "benchmark_us": 155,
            "body_ns": 120
          }
        ]
      },
      "interpreter": {
        "samples": 10,
        "mean_body_ns": 950.0,
        "stddev_body_ns": 50.0,
        "min_body_ns": 880,
        "max_body_ns": 1010,
        "mean_overhead_ns": 42.0,
        "raw_samples": []
      },
      "ratio": {
        "compiled_to_interpreter_mean": 0.1263,
        "interpreter_to_compiled_mean": 7.9167
      }
    }
  ]
}
```

JSON field rules:

- `schema_version` is a positive integer and must change on incompatible report-shape changes.
- `generated_at` uses UTC ISO-8601 text.
- `environment` captures enough toolchain context to compare local runs without guessing.
- `cases[*].status` is `passed`, `failed`, or `skipped`; failed cases may omit runtime statistics
  and ratios but must include a `failure` diagnostic text to identify the failure.
- `raw_samples` contains adjusted sample inputs and outputs. It may be omitted only when a later
  explicit `include_raw_samples=false` option is added.
- all durations inside JSON use numeric nanoseconds or microseconds according to the field suffix;
  JSON must not store formatted duration strings such as `"1.23ms"`.
- paths inside JSON use forward slashes for stable cross-platform comparison.

The JUnit test should fail for malformed result lines, missing samples, inconsistent iteration
counts, missing pass markers, failed output expectations, and Godot timeout. It should not fail
solely because one runtime path is slower than the other unless an explicit per-case threshold is
added later.

---

## 8. Implementation Steps

### Step 1: Define Fixture Layout and Resource Discovery

Implement benchmark resource roots and discovery in `GdScriptBenchmarkRunner`.

Implementation status:

- Status: completed on 2026-06-14
- Deliverables:
  - added `gd.script.gdcc.test_suite.benchmark.GdScriptBenchmarkRunner`
  - added benchmark resource roots under `src/test/test_suite/benchmark/{script,interpreter,measurement}`
  - added `GdScriptBenchmarkRunnerTest` resource-set contract coverage for:
    - non-empty bundled discovery with baseline case coverage
    - empty benchmark directory failure
    - missing interpreter counterpart failure
    - missing measurement counterpart failure
    - unexpected counterpart resource failure
    - stable sorted ordering across duplicate classpath roots
- Notes:
  - counterpart validation currently enforces exact set equality between compiled script paths and
    interpreter / measurement paths so fixture drift fails before compile or runtime work starts
  - bundled discovery tests intentionally avoid pinning the complete benchmark script list; new cases
    are accepted when they provide matching compiled, interpreter, and measurement resources
  - resource discovery keeps the existing `ResourceExtractor.listResourceFilesRecursively(...)`
    ordering contract instead of introducing benchmark-specific sorting logic

Tasks:

- add constants for `benchmark/script`, `benchmark/interpreter`, and `benchmark/measurement`
- list compiled benchmark scripts with `ResourceExtractor.listResourceFilesRecursively(...)`
- require matching interpreter and measurement resources for every relative path
- add a JUnit resource-set contract in `GdScriptBenchmarkRunnerTest`

Acceptance:

- an empty benchmark directory fails with a clear fixture message only when the benchmark test is
  explicitly run
- a missing interpreter or measurement counterpart reports the exact relative path
- resource ordering is stable across runs

### Step 2: Reuse Compile and Native Build Flow

Extract only the needed logic from `GdScriptUnitTestCompileRunner` or keep it private in the new
runner if sharing would create awkward abstractions.

Implementation status:

- Status: completed on 2026-06-14
- Deliverables:
  - reused parse + lower flow inside `GdScriptBenchmarkRunner`
  - reused `CCodegen` + `CProjectBuilder` native build path with `COptimizationLevel.RELEASE`
  - preserved build timing and build log in `GdScriptBenchmarkRunner.CaseBuildResult`
  - added minimal benchmark fixture `algorithm/int_loop.gd` for compiled / interpreter /
    measurement resource pairing
  - added `GdScriptBenchmarkRunnerTest` coverage for:
    - release optimization selection
    - dynamic library artifact creation
    - current runtime layout generation via `entry.h`
    - native build failure message including build log
    - targeted release-build execution when Zig is available
- Notes:
  - the new runner keeps benchmark-specific state in nested records instead of introducing another
    public compile/build abstraction
  - the release-build tests inject a recording `CCompiler` to lock the optimization contract
    without depending on Godot runtime execution
  - runtime project preparation and measurement execution remain for later steps; Step 2 stops at
    producing validated release artifacts with retained diagnostics

Tasks:

- parse and lower each compiled benchmark source
- build a release native library through `CProjectBuilder` with `COptimizationLevel.RELEASE`
- preserve build timing and build log in result records
- require Zig with the same assumption style as existing runtime tests

Acceptance:

- a minimal benchmark source produces a dynamic library artifact
- benchmark measurement artifacts are built with release optimization, not debug optimization
- build failures include the native build log
- generated project outputs use the current runtime layout, not `gdextension-lite`
- no new public abstraction is introduced for a single implementation

### Step 3: Prepare a Dual-Target Godot Scene

Extend or reuse `GodotGdextensionTestRunner` so one Godot project contains both execution paths.

Implementation status:

- Status: completed on 2026-06-14
- Deliverables:
  - extended `src/test/java/gd/script/gdcc/backend/c/build/GodotGdextensionTestRunner.java`
    to support:
    - explicit `COptimizationLevel` selection for generated `.gdextension` metadata
    - managed script resources installed as `res://` files before scene generation
    - scene nodes that attach scripts through declared resource paths instead of inline property text
    - cleanup of stale managed benchmark script roots between project preparations
  - extended `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunner.java`
    to prepare a dual-target `ProjectSetup` containing:
    - one compiled target node mounted by gdcc runtime class name
    - one interpreter node backed by the paired interpreter script resource
    - one measurement node backed by the paired measurement script resource
  - upgraded `src/test/test_suite/benchmark/measurement/algorithm/int_loop.gd`
    from a placeholder into a per-case measurement descriptor that carries Step 4 metadata without
    duplicating protocol logic
  - added focused Step 3 tests in:
    - `src/test/java/gd/script/gdcc/backend/c/build/GodotGdextensionTestRunnerTest.java`
    - `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunnerTest.java`
- Notes:
  - benchmark directives are now validated and stripped before interpreter scripts are installed;
    measurement descriptors are validated as metadata-only inputs so the shared template owns the
    executable protocol logic
  - the shared project writer now removes stale generated benchmark script roots before installing
    new managed resources, so one case cannot accidentally reuse another case's interpreter or
    measurement script
  - Step 3 intentionally stops at project preparation and scene wiring; it does not yet launch
    Godot or parse benchmark output, which remain Step 4 and Step 5 work

Tasks:

- mount the compiled target by runtime class name
- install the interpreter target script as a resource
- mount an interpreter node with that script attached
- install and mount the measurement script
- keep project preparation destructive only inside generated files and `bin`

Acceptance:

- Godot loads the generated GDExtension through regenerated `.gdextension` and `extension_list.cfg`
- the measurement script can call both compiled and interpreter targets in one process
- stale artifacts from prior benchmark cases do not affect the current case

### Step 4: Implement Godot-Side Measurement Protocol

Create the initial measurement script template and per-case contract.

Implementation status:

- Status: completed on 2026-06-14
- Deliverables:
  - added `src/test/test_suite/benchmark/template/measurement.gd`
    as the shared Step 4 measurement protocol template:
    - emits one `GDCC_BENCHMARK_HEADER` line with encoded case metadata
    - runs warmup batches for compiled and interpreter targets in the same Godot process
    - measures baseline and benchmark batches independently per path
    - emits one `GDCC_BENCHMARK_RESULT` line per sample with
      `case/path/sample/iterations/baseline_us/benchmark_us/body_ns/check_*`
    - prints the benchmark pass marker and exits through the shared `root.gd` shutdown path
  - extended `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunner.java`
    so benchmark directives are parsed into structured config and substituted into the
    installed measurement script generated from the shared template
  - added focused protocol tests in
    `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunnerTest.java`
    for:
    - directive parsing and validation
    - measurement script placeholder substitution
    - malformed / missing result protocol failures
- Notes:
  - header `name` is URL-encoded so machine-readable fields remain whitespace-safe without
    inventing a second delimiter layer
  - per-case resources under `benchmark/measurement/**` are descriptors only; the runner rejects
    executable GDScript bodies there so protocol and batch logic cannot drift between cases
  - the protocol reports negative adjusted body samples as-is; warning/instability handling stays
    in Java-side aggregation rather than clamping in GDScript
  - behavior checks are evaluated per sample and reported as structured booleans so Java can fail
    precisely on a protocol violation instead of scraping free-form text

Tasks:

- run warmup batches for both paths
- measure baseline and benchmark batches independently for each path
- print `GDCC_BENCHMARK_RESULT` lines for every sample
- print a per-case pass marker after behavior validation succeeds
- print or trigger the shared stop path expected by `GodotGdextensionTestRunner`

Acceptance:

- Java can reconstruct every sample without relying on human-readable text
- missing or malformed result lines fail the JUnit test
- behavior validation happens before the pass marker
- baseline and benchmark calls use the same receiver and call mechanism for each path

### Step 5: Parse Results and Compute Statistics

Implement Java-side parsing, aggregation, and JSON report generation in the benchmark runner.

Implementation status:

- Status: completed on 2026-06-14
- Deliverables:
  - extended `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunner.java`
    with:
    - compile + prepare + Godot run orchestration for benchmark cases
    - machine-readable result parsing and validation
    - per-path aggregation of mean / sample stddev / min / max / overhead mean
    - warning generation for negative adjusted samples, short batches, and missing checks
    - summary-line formatting
    - per-case JSON report writing to `tmp/test/test_suite/benchmark/report-<case>.json`
  - added deterministic parser/statistics/report tests in
    `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunnerTest.java`
    covering:
    - successful aggregation with warnings
    - malformed numeric field failure
    - missing pass marker failure
    - inconsistent sample-count failure
    - failed behavior-check failure
    - JSON field shape and numeric duration serialization
  - added a real runtime benchmark integration test in
    `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunnerTest.java`
    that compiles, runs, parses, and persists the bundled benchmark case when Zig and `GODOT_BIN`
    are available
  - updated `src/main/java/gd/script/gdcc/backend/c/build/GdextensionMetadataFile.java`
    and `src/test/java/gd/script/gdcc/backend/c/build/GodotGdextensionTestRunnerTest.java`
    so runtime launches can resolve the generated benchmark extension through both platform
    debug/release keys while still measuring release-built native artifacts
- Notes:
  - report JSON now uses snake_case field names where the plan requires them, while Java records
    keep concise camelCase accessors
  - summary lines stay human-oriented and duration-unit formatted; JSON remains numeric-only
  - benchmark runtime tests still rely on JUnit assumptions for missing Zig or `GODOT_BIN`

Tasks:

- parse all result lines from combined Godot output
- group samples by case and path
- validate sample counts and iteration consistency
- compute mean, sample standard deviation, min, max, overhead mean, and ratio
- report warnings for short batches, negative adjusted samples, and missing checks
- write `tmp/test/test_suite/benchmark/report-<case>.json` with `schema_version`, environment, config,
  per-case statistics, ratio data, warnings, and raw samples

Acceptance:

- a deterministic fake output unit test validates parsing and statistics without launching Godot
- a deterministic report writer unit test validates JSON shape and numeric duration fields without
  launching Godot
- standard deviation uses the sample formula when sample count is greater than one
- one-sample cases report zero or unavailable standard deviation consistently
- malformed numeric fields produce actionable failure messages
- report JSON can be parsed back by Java tests and contains every benchmark case that was selected
  for the run
- generated JSON uses forward-slash paths and does not contain formatted duration strings

### Step 6: Add Initial Benchmark Cases

Add a minimal set of representative fixtures.

Implementation status:

- Status: completed on 2026-06-15
- Deliverables:
  - expanded bundled benchmark fixtures under `src/test/test_suite/benchmark/**` with:
    - `algorithm/int_loop.gd`
    - `runtime/stringname_roundtrip.gd`
    - `collection/array_mutation.gd`
    - `collection/dictionary_lookup.gd`
    - `math/vector3_transform.gd`
    - `math/newton_sqrt.gd`
  - kept each case in compiled / interpreter / measurement-descriptor triplets with identical
    relative paths
  - added explicit caller-visible mutation validation in `collection/array_mutation.gd`
  - updated benchmark resource contract coverage in
    `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunnerTest.java`
    so the bundled fixture list is pinned in deterministic order
- Notes:
  - `runtime/stringname_roundtrip.gd` uses direct `String -> StringName -> String` boundary flows
    instead of typed-dictionary fixtures so it stays clear of the current helper-name collision gap
  - container fixtures keep seed construction in `prepare()` with `Array()` / `Dictionary()`
    population and `while` loops only, matching current compile-mode support limits
  - `collection/array_mutation.gd` validates caller-visible mutation and now relies on the
    measurement contract to rerun `prepare()` before every warmup batch and sample so stateful
    paths do not drift after warmup
  - faster cases now raise case-local `iterations` values so `batch_below_min_duration` remains a
    diagnostic warning instead of dominating ordinary local runs:
    - `algorithm/int_loop.gd`: 50,000
    - `collection/array_mutation.gd`: 50,000
    - `collection/dictionary_lookup.gd`: 10,000
    - `math/newton_sqrt.gd`: 20,000
    - `math/vector3_transform.gd`: 10,000
    - `runtime/stringname_roundtrip.gd`: 20,000

Initial cases:

- integer arithmetic loop using `while`
- string or `StringName` operation that avoids known typed-dictionary helper collisions
- object or container mutation case that validates caller-visible state
- vector math operation, e.g. 3-body problem simulation
- float math operation, e.g. solving equations using Newton's method
- container operation

Acceptance:

- cases avoid unsupported frontend constructs
- each case has compiled, interpreter, and measurement resources
- each case prints both compiled and interpreter samples
- at least one case validates mutation visible to the caller

### Step 7: Add Focused JUnit Entrypoints

Add targeted tests rather than running benchmarks as part of every ordinary unit-test pass.

Implementation status:

- Status: completed on 2026-06-15
- Deliverables:
  - kept parser / statistics / JSON contract tests in
    `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunnerTest.java`
    so they run without Zig or Godot
  - added `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRuntimeTest.java`
    as the focused Godot-backed benchmark entrypoint
  - split runtime dynamic tests by benchmark category:
    - algorithm
    - collection
    - math
    - runtime
  - added `GDCC_RUN_BENCHMARKS` gating so runtime benchmark tests skip unless:
    - Zig is available
    - `GODOT_BIN` is configured
    - `GDCC_RUN_BENCHMARKS` is enabled with `1/true/yes/on`
  - added focused unit coverage for:
    - output expectation failure paths
    - report writer directory creation and persisted JSON shape
- Notes:
  - benchmark release-build dynamic tests stay in the runtime-focused class because they still
    depend on Zig and belong to the external-tool entry surface rather than the pure parser tests
  - the pure Java test class now cleanly covers resource contracts, directive parsing, output
    parsing, statistics, and JSON serialization without any Godot process requirement
  - benchmark runtime execution now copies the checked-in `test_project` fixture into a per-case
    directory under `tmp/test/test_suite/benchmark/runtime/<case>` before launch so one case cannot
    rewrite another case's scene or managed script resources while an earlier Godot process is
    still finishing shutdown after `Test stop.`
  - focused unit tests pin the per-batch `prepare()` contract in rendered measurement scripts and
    the per-case runtime project directory mapping

Tasks:

- add a dynamic-test class for benchmark cases
- add a parser/statistics unit test that runs without Godot
- skip runtime benchmark tests when Zig or `GODOT_BIN` is unavailable or no env flag is provided

Acceptance:

- `script/run-gradle-targeted-tests.sh --tests GdScriptBenchmarkRunnerTest` runs the benchmark
  harness on machines with Zig and `GODOT_BIN`
- machines without Zig or `GODOT_BIN` skip runtime benchmark tests via JUnit assumptions
- parser/statistics tests run without external tools

### Step 8: Document Operator Workflow

Add a short operator note after implementation lands.

Implementation status:

- Status: completed on 2026-06-15
- Deliverables:
  - added `doc/benchmark.md` as the benchmark operator guide covering:
    - required runtime environment variables and opt-in gate
    - targeted Gradle commands for pure Java contract tests and Godot-backed runtime tests
    - machine-readable output line formats, summary-line interpretation, and stop-signal behavior
    - JSON report path, schema version, unit conventions, retained raw samples, and durable-data guidance
    - release-vs-debug interpretation rules and current warning categories
    - fixture limits and troubleshooting notes for Zig, `GODOT_BIN`, timeout, and unsupported constructs
  - added focused Step 8 contract tests in:
    - `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunnerTest.java`
    - `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRuntimeTest.java`
- Notes:
  - the operator guide is intentionally user-facing and documents current contracts instead of
    re-explaining the whole implementation pipeline
  - runtime benchmark execution remains opt-in through `GDCC_RUN_BENCHMARKS`; missing Zig or
    `GODOT_BIN` continue to skip runtime tests through JUnit assumptions
  - console summary lines are documented as quick diagnostics only; the per-case JSON report remains
    the durable artifact for scripts and follow-up analysis

Tasks:

- document required environment variables
- document targeted Gradle command
- document output line format and interpretation
- document JSON report path, schema version, unit conventions, and whether raw samples are included
- document that benchmark measurements use release native artifacts and debug builds are only for
  troubleshooting
- document that local benchmark numbers are not CI regression thresholds
- an overall detail document at doc/benchmark.md

Acceptance:

- a developer can run one benchmark class from repository root
- output includes case name, mean, standard deviation, and ratio
- `tmp/test/test_suite/benchmark/report-<case>.json` can be consumed by scripts without scraping console
  output
- troubleshooting notes mention Godot binary, Zig, timeout, and unsupported frontend constructs

---

## 9. Validation Matrix

Implementation status:

- Status: completed on 2026-06-15
- Validation command:
  - `script/run-gradle-targeted-tests.sh --tests GdScriptBenchmarkRunnerTest,GodotGdextensionTestRunnerTest`
- Deliverables:
  - added focused Step 9 validation coverage in
    `src/test/java/gd/script/gdcc/test_suite/benchmark/GdScriptBenchmarkRunnerTest.java`
  - added artifact-copy validation coverage in
    `src/test/java/gd/script/gdcc/backend/c/build/GodotGdextensionTestRunnerTest.java`
  - added `GdScriptBenchmarkRunner.assertStopSignalSeen(...)` so timeout / missing-stop-signal
    diagnostics are unit-testable without launching Godot
  - added concise comments at the output-protocol and environment-snapshot boundaries
- Notes:
  - GitHub / Godot upstream source lookup was not needed for this step: the existing repository
    docs and runtime tests already define the `.gdextension`, stop-signal, script-resource, and
    benchmark fixture contracts used by the validation matrix
  - runtime benchmark execution remains opt-in through `GDCC_RUN_BENCHMARKS`; Step 9 pure Java
    validation does not require Zig or Godot

Validation evidence:

- Resource contract:
  - completed: compiled, interpreter, and measurement resources are paired by relative path
  - evidence:
    - `GdScriptBenchmarkRunnerTest.listsExpectedBundledBenchmarkScripts`
    - `GdScriptBenchmarkRunnerTest.benchmarkResourceOrderingStaysStableAcrossDuplicateClasspathRoots`
  - completed: missing pair failures identify the exact file
  - evidence:
    - `GdScriptBenchmarkRunnerTest.failsWhenInterpreterCounterpartIsMissing`
    - `GdScriptBenchmarkRunnerTest.failsWhenMeasurementCounterpartIsMissing`
    - `GdScriptBenchmarkRunnerTest.rejectsUnexpectedInterpreterFixtureWithoutCompiledCounterpart`
    - `GdScriptBenchmarkRunnerTest.rejectsUnexpectedMeasurementFixtureWithoutCompiledCounterpart`
- Compile path:
  - completed: frontend diagnostics fail the case
  - evidence:
    - `GdScriptBenchmarkRunnerTest.compileBenchmarkCaseShouldFailOnFrontendDiagnostics`
  - completed: native benchmark artifacts use `COptimizationLevel.RELEASE`
  - evidence:
    - `GdScriptBenchmarkRunnerTest.releaseBuildUsesReleaseOptimizationAndPreservesBuildLog`
    - `GdScriptBenchmarkRuntimeTest.compilesBundledBenchmarkScriptsToReleaseArtifacts`
  - completed: native build diagnostics fail the case with build log
  - evidence:
    - `GdScriptBenchmarkRunnerTest.buildFailureIncludesNativeBuildLog`
  - completed: generated artifacts are copied into the Godot project
  - evidence:
    - `GodotGdextensionTestRunnerTest.prepareProjectShouldCopyGeneratedArtifactsIntoProjectBin`
    - `GodotGdextensionTestRunnerTest.prepareProjectShouldRejectMissingGeneratedArtifact`
- Runtime path:
  - completed: Godot process starts from `GODOT_BIN`
  - evidence:
    - `GodotGdextensionTestRunner.findGodotBinaryFromEnv`
    - `GdScriptBenchmarkRuntimeTest.compilesRunsAndReports*`
  - completed: stop signal is seen
  - evidence:
    - `GdScriptBenchmarkRuntimeTest.compilesRunsAndReports*`
    - `GdScriptBenchmarkRunner.assertStopSignalSeen`
  - completed: pass marker is seen
  - evidence:
    - `GdScriptBenchmarkRuntimeTest.compilesRunsAndReports*`
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldRejectMissingPassMarker`
  - completed: timeout fails with combined output
  - evidence:
    - `GdScriptBenchmarkRunnerTest.assertStopSignalSeenShouldReportTimeoutWithCombinedOutput`
- Measurement path:
  - completed: each runtime path has the configured sample count
  - evidence:
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldRejectInconsistentSampleCount`
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldComputeStatisticsWarningsAndReportShape`
  - completed: each sample contains baseline and benchmark durations
  - evidence:
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldRejectMalformedNumericField`
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldKeepPerPathPerSampleOverheadSubtraction`
  - completed: stateful fixtures re-enter `prepare()` before every warmup batch and every recorded sample
  - evidence:
    - `GdScriptBenchmarkRunnerTest.compileBenchmarkCaseShouldRenderPerBatchPrepareIntoMeasurementScript`
  - completed: call overhead is subtracted per path and per sample
  - evidence:
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldKeepPerPathPerSampleOverheadSubtraction`
  - completed: mean and standard deviation are computed on adjusted body times
  - evidence:
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldComputeStatisticsWarningsAndReportShape`
- Reporting path:
  - completed: summary line is stable enough for log collection
  - evidence:
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldComputeStatisticsWarningsAndReportShape`
    - `GdScriptBenchmarkRunnerTest.summaryLineShouldRenderInfiniteRatioWhenInterpreterMeanIsZero`
  - completed: JSON report is written to `tmp/test/test_suite/benchmark/report-<case>.json` by default
  - evidence:
    - `GdScriptBenchmarkRunnerTest.reportPathShouldBePerCaseStableAndUnderBenchmarkWorkRoot`
    - `GdScriptBenchmarkRunnerTest.writeReportShouldCreateParentDirectoriesAndPersistJson`
  - completed: JSON report contains schema version, environment, config, case summaries, ratios,
    warnings, and raw samples
  - evidence:
    - `GdScriptBenchmarkRunnerTest.renderReportJsonShouldUseNumericDurationFieldsAndForwardSlashPaths`
    - `GdScriptBenchmarkRunnerTest.renderReportJsonShouldRetainWarningsRawSamplesAndCombinedOutput`
  - completed: JSON report records `environment.optimization` as `RELEASE` for measured compiled results
  - evidence:
    - `GdScriptBenchmarkRunnerTest.renderReportJsonShouldUseNumericDurationFieldsAndForwardSlashPaths`
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldCaptureReleaseEnvironmentForReport`
  - completed: JSON report stores numeric duration values with explicit unit suffixes rather than
    formatted strings
  - evidence:
    - `GdScriptBenchmarkRunnerTest.renderReportJsonShouldUseNumericDurationFieldsAndForwardSlashPaths`
  - completed: JSON report can be parsed by a focused unit test without launching Godot
  - evidence:
    - `GdScriptBenchmarkRunnerTest.writeReportShouldCreateParentDirectoriesAndPersistJson`
  - completed: warnings are visible but do not become hard failures unless the case opts in
  - evidence:
    - `GdScriptBenchmarkRunnerTest.parseCaseOutputShouldComputeStatisticsWarningsAndReportShape`
    - `GdScriptBenchmarkRunnerTest.renderReportJsonShouldRetainWarningsRawSamplesAndCombinedOutput`
- Environment behavior:
  - completed: missing Zig skips runtime benchmark tests
  - evidence:
    - `GdScriptBenchmarkRuntimeTest.compilesBundledBenchmarkScriptsToReleaseArtifacts`
    - `GdScriptBenchmarkRuntimeTest.compilesRunsAndReports*`
  - completed: missing `GODOT_BIN` skips runtime benchmark tests
  - evidence:
    - `GdScriptBenchmarkRuntimeTest.compilesRunsAndReports*`
    - `GodotGdextensionTestRunner.findGodotBinaryFromEnv`
  - completed: parser/statistics unit tests do not require Zig or Godot
  - evidence:
    - `GdScriptBenchmarkRunnerTest`

---

## 10. Risks and Controls

- Timer noise:
  - measure batches instead of single calls
  - warn when batch duration is too small
  - raise per-case `iterations` rather than weakening the warning into a silent clamp
  - keep raw samples available in failure output
- Godot startup and scene overhead:
  - measure inside Godot after scene setup
  - exclude Java process timing from body-time metrics
- Call path asymmetry:
  - measure separate baselines for compiled and interpreter targets
  - require baseline and benchmark to use the same receiver and invocation style per path
- Fixture drift:
  - keep an explicit resource-set contract test
  - fail on missing counterpart resources
- Existing compiler limits:
  - restrict initial benchmark sources to supported constructs
  - keep interpreter workarounds in interpreter resources, not in compiled sources
- Lifecycle-sensitive benchmark cases:
  - validate caller-visible mutation and object/container state after timing
  - restore mutable benchmark fixture state through `prepare()` at each batch boundary rather than
    relying on one whole-run initialization
  - do not use benchmark cases as a substitute for ownership correctness tests
- Shared runtime project races:
  - isolate benchmark runs into per-case generated Godot project directories under `tmp`
  - keep the checked-in `test_project` as a template only, not as a mutable shared run directory
- Runtime dependency instability:
  - use JUnit assumptions for missing tools
  - keep benchmark tests targeted and opt-in for local performance work

---

## 11. Open Decisions

- Whether benchmark runtime tests should always run when targeted, or require an opt-in environment
  variable such as `GDCC_RUN_BENCHMARKS=1`. A: GDCC_RUN_BENCHMARKS required
- Whether result summaries should also be written to a file under `tmp/test/test_suite/benchmark` A: yes
- Whether JSON report history should keep timestamped files in addition to the default per-case
  `report-<case>.json`. A: no
- Whether raw sample arrays should be optional for very large benchmark runs after the initial
  implementation. A: no
- Whether benchmark thresholds should ever become CI gates. A: no
