package gd.script.gdcc.util;

/// Compile-time outcome of a static `value is T` type test.
///
/// Shared by frontend body lowering and C backend codegen so both sides decide fold vs
/// runtime-open with the same contract (see `frontend_is_type_test_implementation.md`).
public enum TypeTestFoldResult {
    /// Static types prove the test always holds (e.g. `int is int`, any operand `is Variant`).
    TRUE,
    /// Static types prove the test never holds (e.g. `int is float`, `null is Node`).
    FALSE,
    /// Static types cannot decide; emit a runtime check (object null-check path, ClassDB path,
    /// typed-container metadata helper, or Variant-operand open path).
    RUNTIME_OPEN
}
