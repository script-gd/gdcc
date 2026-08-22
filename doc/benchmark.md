# Benchmark Suite Guide

This document describes how to run, read, and extend the Godot-backed benchmark suite under
`src/test/java/gd/script/gdcc/test_suite/benchmark`.

Each benchmark case compares two execution paths for the same behavior:

- the compiled gdcc GDExtension class
- the interpreter-side GDScript class

The main metric is adjusted per-call body time. The runner measures a baseline call path and a
benchmark call path, then subtracts baseline overhead from the benchmark result.

## Prerequisites

Required runtime dependencies:

- `zig` must be available so the benchmark case can be compiled to a native artifact
- `GODOT_BIN` must point to a runnable Godot binary

Related optional environment variable:

- `GDCC_TEST_TIMING=1`
    - enables extra timing output for the existing unit-test suite
    - does not affect manual benchmark runtime execution

Benchmark contract tests use JUnit assumptions for missing external tools:

- missing `zig` skips the release-build benchmark compile tests

The manual benchmark main fails fast when `zig` or `GODOT_BIN` is unavailable.

## Targeted Commands

Run the pure Java contract tests from the repository root:

```bash
script/run-gradle-targeted-tests.sh --tests GdScriptBenchmarkRunnerTest
```

Run the release-build contract tests from the repository root:

```bash
script/run-gradle-targeted-tests.sh --tests GdScriptBenchmarkCompileTest
```

Run the Godot-backed runtime benchmark task from the repository root:

```bash
./gradlew benchmark --no-daemon --info --console=plain
```

The `benchmark` task launches `gd.script.gdcc.test_suite.benchmark.GdScriptBenchmarkMain` on the test runtime classpath.
No benchmark-specific opt-in environment variable is required; `GODOT_BIN` is still required.

What each entrypoint covers:

- `GdScriptBenchmarkRunnerTest`
    - resource-set validation
    - directive parsing
    - result-line parsing
    - statistics aggregation
    - JSON report serialization
    - does not require Godot or Zig
- `GdScriptBenchmarkCompileTest`
    - release native artifact compilation for bundled benchmark cases
- `GdScriptBenchmarkMain`
    - Godot-backed benchmark execution for bundled benchmark cases
    - requires `zig` and `GODOT_BIN`
    - writes the merged `tmp/test/test_suite/benchmark/report.json` file

During normal development, prefer targeted execution over running the full test suite.

## Console Output

The most important runtime lines are:

```text
GDCC_BENCHMARK_HEADER case=<path> name=<url-encoded-name> iterations=<n> warmups=<n> samples=<n> min_batch_us=<n>
GDCC_BENCHMARK_RESULT case=<path> path=<compiled|interpreter> sample=<index> iterations=<n> baseline_us=<n> benchmark_us=<n> body_ns=<n> check_ran=<true|false> check_passed=<true|false>
GDCC_BENCHMARK_PASS::<path>
Test stop.
```

Interpretation:

- `GDCC_BENCHMARK_HEADER`
    - reports the effective benchmark config for the case
- `GDCC_BENCHMARK_RESULT`
    - one line per measured sample and per path
    - `baseline_us` and `benchmark_us` are batch durations in microseconds
    - `body_ns` is the adjusted per-call body time in nanoseconds
    - negative adjusted samples are preserved and reported as warnings
- `GDCC_BENCHMARK_PASS::<path>`
    - indicates benchmark-side behavior validation succeeded for the case
- `Test stop.`
    - indicates the shared Godot test run reached shutdown

After successful parsing, Java prints a compact summary line:

```text
[gdcc-benchmark] case=<path> compiled.mean=<duration> compiled.stddev=<duration> interpreter.mean=<duration> interpreter.stddev=<duration> ratio=<number> samples=<n> iterations=<n>
```

Interpretation:

- `compiled.mean` and `interpreter.mean`
    - mean adjusted body time per call
- `compiled.stddev` and `interpreter.stddev`
    - sample standard deviation of adjusted body time per call
- `ratio`
    - compiled mean divided by interpreter mean
    - smaller than `1` means the compiled path is faster
    - `inf` means the interpreter mean was zero while the compiled mean was non-zero

The console summary is useful for quick diagnosis. The JSON report is the durable output.

## JSON Report

Default report paths:

```text
tmp/test/test_suite/benchmark/report.json
tmp/test/test_suite/benchmark/report-min.json
```

The reports are intended for scripts and post-run inspection. They merge all benchmark cases from a manual run into one
`cases` array and include:

- generation metadata such as schema version and timestamp
- environment details such as OS, Java, Godot, Zig, target platform, and optimization level
- per-case effective benchmark config such as warmups, samples, iterations, and `min_batch_us`
- per-case status, warnings, aggregated statistics, ratios, command, combined output, and raw samples

The `report-min.json` variant keeps the same top-level structure and per-case statistics, but drops the heavy runtime
fields:

- `raw_samples`
- `pass_marker_seen`
- `command`
- `combined_output`

Unit conventions:

- fields ending in `_ns` store numeric nanoseconds
- fields ending in `_us` store numeric microseconds
- JSON does not store formatted strings such as `1.23ms`

When the JSON report exists, prefer it over scraping console output.

## Adding a Benchmark Case

Benchmark resources live under `src/test/test_suite/benchmark`:

```text
src/test/test_suite/benchmark/
├── script/
├── interpreter/
├── measurement/
└── template/
```

To add a new benchmark case:

1. Add a compiled source script under `script/<category>/<case>.gd`.
2. Add an interpreter script under `interpreter/<category>/<case>.gd`.
3. Add a measurement descriptor under `measurement/<category>/<case>.gd`.
4. Keep the same relative path across all three roots.

Example:

```text
src/test/test_suite/benchmark/script/algorithm/int_loop.gd
src/test/test_suite/benchmark/interpreter/algorithm/int_loop.gd
src/test/test_suite/benchmark/measurement/algorithm/int_loop.gd
```

Script contract for both `script/**` and `interpreter/**`:

- the script should be a `Node`-based benchmark target
- implement `baseline()`
- implement `benchmark()`
- implement `prepare()` when the case needs state reset before each batch
- implement `check(result)` when the result needs behavior validation

Use the same callable surface on both sides so the runner can measure them symmetrically.

Measurement descriptor contract:

- `measurement/**` is metadata only
- do not put benchmark execution logic in the descriptor
- use `# gdcc-benchmark:` directives to configure the shared measurement template

Supported directives:

- `name=...`
- `iterations=...`
- `warmups=...`
- `samples=...`
- `min_batch_us=...`
- `output_contains=...`
- `output_not_contains=...`

Important notes:

- unknown directives fail the benchmark contract tests
- empty values and invalid integers fail the benchmark contract tests
- if `name` is omitted, the runner derives a readable name from the file path
- current defaults are `iterations=1000`, `warmups=3`, `samples=10`, and `min_batch_us=1000`

Current layout conventions:

- existing categories are `algorithm`, `collection`, `math`, and `runtime`
- new categories are picked up automatically when all three resource roots contain matching paths

Bundled benchmark case families currently cover:

- `algorithm/int_loop.gd`: scalar integer loop overhead
- `collection/array_mutation.gd`: mutable array update and traversal
- `collection/dictionary_lookup.gd`: dictionary lookup from a reusable key set
- `collection/linked_list.gd`: GDScript singly linked list append, traversal, and relink
- `collection/tree_heap.gd`: binary min-heap push/pop and sift operations
- `collection/bloom_filter.gd`: Bloom filter insert and membership checks
- `collection/quadtree_lookup.gd`: fixed-grid quadtree-style 2D spatial lookup
- `math/newton_sqrt.gd`: iterative floating-point square root
- `math/vector3_transform.gd`: `Vector3` transform loop
- `math/matrix_ops.gd`: scalar-backed matrix multiply/add and vector transform
- `math/series_recurrence.gd`: geometric sum, Fibonacci-style recurrence, and alternating recurrence
- `math/sliding_variance.gd`: fixed-window variance update without retaining historical samples
- `runtime/stringname_roundtrip.gd`: `StringName` to `String` conversion
- `runtime/node_batch_ops.gd`: batch `Node` add, rename, remove, and free operations

Authoring constraints:

- keep compiled benchmark scripts within the currently supported frontend and backend feature set
- `for`, recorded `lambda`, and `match` (all six pattern routes, including ARRAY/DICTIONARY destructuring) are now compile-ready, but keep new cases on already-proven surfaces unless the benchmark is specifically exercising them
- for stateful cases, reset reusable state in `prepare()` instead of relying on cross-sample carry-over
- when `prepare()` rebuilds state, reset auxiliary counters and cached derived data there as well so
  `baseline()`, `benchmark()`, and `check()` all observe the same per-batch state

## Build Mode and Interpretation Boundaries

Benchmark measurements use release native artifacts:

- compiled benchmark cases are built with `COptimizationLevel.RELEASE`
- runtime report `environment.optimization` is expected to be `RELEASE`

Debug builds are only for troubleshooting:

- use them to investigate compile or runtime failures
- do not compare debug benchmark numbers with interpreter results
- do not treat debug numbers as benchmark output

Local benchmark numbers are not CI regression thresholds:

- they are intended for local investigation and directional comparison
- warnings in the report are visible diagnostics, not automatic performance gate failures

Current warning categories include:

- `negative_adjusted_sample`
- `batch_below_min_duration`
- `missing_behavior_check`

## Troubleshooting

Common failure and skip causes:

- `zig` not found
    - ensure `zig` is on `PATH` or discoverable through the environment locations checked by `ZigUtil`
- `GODOT_BIN` not found
    - export `GODOT_BIN` to a runnable Godot binary
- Godot timeout
    - inspect the combined output stored in the JSON report
    - check whether the case printed the benchmark pass marker
    - check whether `Test stop.` was reached
- malformed or missing benchmark result lines
    - verify the case still emits the expected benchmark protocol through the shared measurement template
- frontend compile failure
    - verify the benchmark script stays inside the currently supported feature set

Generated runtime artifacts and reports are written under:

```text
tmp/test/test_suite/benchmark/
```
