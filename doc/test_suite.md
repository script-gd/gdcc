# Test Suite Guide

## Overview

The `test_suite` package provides a resource-driven end-to-end test harness for GDScript frontend and native build validation.

Its current entry point is `test_suite.GdScriptUnitTestCompileRunner`, which:

1. Recursively scans bundled test-case scripts under `unit_test/script`.
2. Parses and lowers each script through the frontend.
3. Builds the lowered module into a native GDExtension library.
4. Launches Godot through `GodotGdextensionTestRunner`.
5. Executes the paired validation script under `unit_test/validation`.

This suite is intended to verify the whole chain instead of one isolated compiler stage.

## Directory Layout

The suite content lives under `src/test/test_suite` and is exposed to the test runtime as test resources.

Current layout:

```text
src/test/test_suite/
└── unit_test/
    ├── script/
    │   └── ...
    └── validation/
        └── ...
```

Rules:

- `unit_test/script` contains the source GDScript files to compile.
- `unit_test/validation` contains the Godot-side validation scripts.
- Each compiled script must have a validation script with the same relative path.
- Resource discovery is recursive, so nested directories are allowed and encouraged for grouping.

Example pair:

```text
src/test/test_suite/unit_test/script/smoke/basic_arithmetic.gd
src/test/test_suite/unit_test/validation/smoke/basic_arithmetic.gd
```

## How Discovery Works

`ResourceExtractor.listResourceFilesRecursively(...)` is used to enumerate all files under `unit_test/script`.

Important details:

- Returned paths are relative to the resource root.
- Path separators are normalized to `/`.
- Directories are not returned.
- Duplicate resource entries from multiple classpath roots are collapsed.
- The final list is sorted for deterministic execution order.

## Validation Script Contract

Validation scripts are ordinary Godot GDScript files executed inside the shared `test_project`.

Before a validation script is installed into the test project, the runner replaces two placeholders:

- `__UNIT_TEST_TARGET_NODE_NAME__`
  - Replaced with the scene node name of the compiled runtime class instance.
- `__UNIT_TEST_PASS_MARKER__`
  - Replaced with a unique success marker derived from the test-case resource path.

Each validation script should:

1. Find the compiled target node by name.
2. Call one or more methods on the compiled class.
3. Print the injected pass marker when validation succeeds.
4. Use `push_error(...)` when validation fails.

Minimal example:

```gdscript
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if int(target.call("compute")) == 10:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Validation failed.")
```

## Runtime and Build Prerequisites

This suite depends on the same external tools as the existing end-to-end native integration tests.

Required:

- `zig` must be available for native library build.
- `GODOT_BIN` must point to a runnable Godot binary.

If these prerequisites are not available, the JUnit wrapper test will be skipped by assumption rather than fail the whole suite.

## Running the Suite

The current JUnit entry is:

- `test_suite.GdScriptUnitTestCompileRunnerTest`

Recommended targeted command:

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdScriptUnitTestCompileRunnerTest
```

During normal development, prefer targeted execution instead of running the full test suite.

## Adding a New Test Case

To add a new end-to-end unit case:

1. Create a source script under `src/test/test_suite/unit_test/script`.
2. Create a validation script under `src/test/test_suite/unit_test/validation` using the same relative path.
3. Make the compiled script class inherit from `Node`.
4. Keep the source script within currently supported frontend behavior.
5. Make the validation script print `__UNIT_TEST_PASS_MARKER__` only on success.
6. Use `push_error(...)` for failure paths so Godot output remains diagnosable.

Why `extends Node` is mandatory:

- `GdScriptUnitTestCompileRunner` installs the compiled runtime class as a scene node through `GodotGdextensionTestRunner.SceneNodeSpec`.
- If the compiled class does not inherit from `Node`, Godot cannot instantiate it into the scene tree and will create a placeholder node instead.
- Once that happens, the validation script will observe a generic placeholder `Node` and all target-method calls become invalid, which hides the real behavior under test.

Recommended conventions:

- Use small, focused cases.
- Group cases by topic using directories such as `smoke`, `control_flow`, `variant`, `constructor`, and so on.
- Keep one behavior contract per case when possible.
- Avoid mixing multiple unrelated features into one script.

## Design Intent

This suite is intentionally resource-driven instead of hardcoding every case in Java test code.

That design keeps:

- test-case authoring close to actual GDScript,
- validation logic close to actual Godot runtime behavior,
- the Java-side runner focused on orchestration rather than embedding many inline scripts.

When extending the suite, prefer adding new resource pairs first. Only change the Java runner when the suite contract itself needs to evolve.
