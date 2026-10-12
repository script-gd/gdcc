package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

/// The C toolchain backends a project build can dispatch to.
///
/// `ZIG_CC` shells out to a discovered `zig cc`: per-TU object compilation with caching/PCH plus a
/// final link step, and it is the only backend that cross-compiles. `TINY_CC` drives an in-process
/// libtcc through the FFM API: one merged compilation straight to the shared library with no object
/// or link stage, restricted to host-native targets and without meaningful optimization passes.
public enum CCompilerKind {
    ZIG_CC,
    TINY_CC;

    /// The backend used when the caller did not pick one. Debug builds go to tinycc: its
    /// in-process single-pass compilation skips subprocess spawn, object caching and link overhead,
    /// which is what dominates edit-compile iteration. Release builds go to zig: tinycc has no
    /// effective optimizer, so shippable artifacts keep the optimizing toolchain.
    public static @NotNull CCompilerKind defaultFor(@NotNull COptimizationLevel optimizationLevel) {
        return optimizationLevel == COptimizationLevel.RELEASE ? ZIG_CC : TINY_CC;
    }
}
