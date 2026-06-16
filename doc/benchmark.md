# Benchmark Suite Operator Guide

This document describes how to run and interpret the Godot-backed benchmark suite under
`src/test/java/gd/script/gdcc/test_suite/benchmark`.

The benchmark suite compares two execution paths for the same benchmark case:

- the compiled gdcc GDExtension class
- the interpreter-side GDScript class

The reported metric is per-call function-body time after subtracting a separately measured
baseline call overhead for each path.

## Prerequisites

Required runtime dependencies:

- `zig` must be available so the benchmark case can be compiled to a native artifact
- `GODOT_BIN` must point to a runnable Godot binary

Required opt-in for Godot-backed benchmark execution:

- `GDCC_RUN_BENCHMARKS=1`
  - accepted enabled values are `1`, `true`, `yes`, and `on`

Related optional environment variables:

- `GDCC_TEST_TIMING=1`
  - enables extra timing output for the existing unit-test suite
  - this flag does not enable benchmark runtime execution

Runtime benchmark tests use JUnit assumptions for missing external tools:

- missing `zig` skips the Godot-backed runtime benchmark tests
- missing `GODOT_BIN` skips the Godot-backed runtime benchmark tests
- missing `GDCC_RUN_BENCHMARKS` skips the Godot-backed runtime benchmark tests

## Targeted Commands

Run the pure Java contract tests from the repository root:

```bash
script/run-gradle-targeted-tests.sh --tests GdScriptBenchmarkRunnerTest
```

Run the benchmark release-build and Godot-backed runtime entrypoint from the repository root:

```bash
GDCC_RUN_BENCHMARKS=1 script/run-gradle-targeted-tests.sh --tests GdScriptBenchmarkRuntimeTest
```

What each entrypoint covers:

- `GdScriptBenchmarkRunnerTest`
  - resource-set validation
  - directive parsing
  - result-line parsing
  - statistics aggregation
  - JSON report serialization
  - does not require Godot or Zig
- `GdScriptBenchmarkRuntimeTest`
  - release native artifact compilation for bundled benchmark cases
  - Godot-backed benchmark execution when `zig`, `GODOT_BIN`, and `GDCC_RUN_BENCHMARKS` are all available

During normal development, prefer targeted execution over running the full test suite.

## Runtime Workflow

For each selected benchmark case, the runtime workflow is:

1. Discover the paired compiled, interpreter, and measurement resources.
2. Compile the benchmark case into a release native artifact.
3. Prepare an isolated Godot project under `tmp/test/test_suite/benchmark/runtime/<case>/`.
4. Generate the measurement script from the shared template, then install it with the compiled
   target and interpreter script into that project.
5. Launch Godot and wait for the benchmark pass marker plus the shared `Test stop.` signal.
6. Parse machine-readable benchmark lines from the combined Godot output.
7. Aggregate statistics and write a JSON report under `tmp/test/test_suite/benchmark/`.

If output parsing or validation fails after Godot returns, the runner still writes the per-case JSON
report before rethrowing the JUnit failure. The failed report preserves the command, combined output,
pass-marker state, and diagnostic text so the report remains useful for post-failure inspection.

The stop signal comes from `test_project/root.gd`, which prints `Test stop.` from `_exit_tree()`.

## Console Output

The executable measurement script is generated from `benchmark/template/measurement.gd` plus the
per-case metadata descriptor under `benchmark/measurement/**`. It emits machine-readable lines for
Java-side parsing. The most important ones are:

```text
GDCC_BENCHMARK_HEADER case=<path> name=<url-encoded-name> iterations=<n> warmups=<n> samples=<n> min_batch_us=<n>
GDCC_BENCHMARK_RESULT case=<path> path=<compiled|interpreter> sample=<index> iterations=<n> baseline_us=<n> benchmark_us=<n> body_ns=<n> check_ran=<true|false> check_passed=<true|false>
GDCC_BENCHMARK_PASS::<path>
Test stop.
```

Interpretation:

- `GDCC_BENCHMARK_HEADER`
  - reports the effective config that Java expects for this case
- `GDCC_BENCHMARK_RESULT`
  - one line per measured sample and per path
  - `baseline_us` and `benchmark_us` are Godot-side batch durations in microseconds
  - `body_ns` is the adjusted per-call body time in nanoseconds
  - negative adjusted samples are reported as-is and become warnings instead of being silently clamped
- `GDCC_BENCHMARK_PASS::<path>`
  - indicates benchmark-side behavior validation succeeded for the case
- `Test stop.`
  - indicates the shared Godot root node has exited and the Java runner can conclude the run

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

Default report path:

```text
tmp/test/test_suite/benchmark/report-<case>.json
```

Current report contract:

- `schema_version`
  - current value is `1`
- `generated_at`
  - UTC ISO-8601 timestamp
- `environment`
  - includes OS, architecture, Java version, `godot_bin`, Godot version, Zig path,
    target platform, and optimization level
- `config`
  - warmups, samples, iterations, and `min_batch_us`
- `cases`
  - includes per-case status, warnings, optional failure text, compiled/interpreter statistics,
    ratios, executed command, pass-marker state, combined output, and raw samples
  - failed cases may omit compiled/interpreter statistics and ratios when the run failed before those
    values could be computed

Unit conventions:

- fields ending in `_ns` store numeric nanoseconds
- fields ending in `_us` store numeric microseconds
- JSON does not store formatted strings such as `1.23ms`

Raw samples:

- `compiled.raw_samples` and `interpreter.raw_samples` are included by default
- each raw sample preserves the original `baseline_us`, `benchmark_us`, and adjusted `body_ns`

The report is designed for scripts and tooling. Do not scrape console output when the report file is available.

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

## Fixture Limits

Benchmark fixtures must stay within the compiler and runtime support surface already documented by the project.

Important current limits:

- compiled benchmark sources must avoid `for`, `match`, and `lambda`
- compiled benchmark sources must avoid array and dictionary literals that compile mode still rejects
- stateful fixtures should restore reusable state through `prepare()` before each warmup batch and sample
- per-case `benchmark/measurement/**` resources must contain only `# gdcc-benchmark:` directives;
  protocol output, warmup/sample loops, overhead subtraction, and `check()` invocation belong to the
  shared measurement template
- typed dictionary overload patterns remain risky because helper-name collisions are not fully closed

When a benchmark fixture needs seed data for arrays or dictionaries, construct it with supported
constructors and mutating methods instead of literals.

## Troubleshooting

Common failure and skip causes:

- `zig` not found
  - ensure `zig` is on `PATH` or discoverable through the environment locations checked by `ZigUtil`
- `GODOT_BIN` not found
  - export `GODOT_BIN` to a runnable Godot binary
- benchmark runtime tests are skipped unexpectedly
  - confirm `GDCC_RUN_BENCHMARKS=1`
- Godot timeout
  - inspect the combined output stored in the JSON report
  - check whether the case printed the benchmark pass marker
  - check whether `Test stop.` was reached
- malformed or missing benchmark result lines
  - verify the shared measurement template still emits `GDCC_BENCHMARK_HEADER` and
    `GDCC_BENCHMARK_RESULT` lines in the expected format
- frontend compile failure
  - verify the benchmark script stays inside the currently supported frontend feature set

If a case fails after the pass marker but before the stop signal, remember that the Java runner
waits for `Test stop.` from the shared root node rather than only for a normal process exit.
