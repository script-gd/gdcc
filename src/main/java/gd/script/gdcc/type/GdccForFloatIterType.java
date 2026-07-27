package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;

/// Compiler-only storage type for lowered float shorthand `for-in` iterator state.
/// Holds current float counter and exclusive end bound, matching Godot
/// `Variant::iter_*` FLOAT semantics (`0.0, 1.0, ...` while `current < end`).
/// Direct-struct-assignment safe: POD float fields only.
public final class GdccForFloatIterType implements GdCompilerType {
    public static final @NotNull GdccForFloatIterType FOR_FLOAT_ITER = new GdccForFloatIterType();

    public static final @NotNull String LIR_TYPE_TEXT = "compiler::GdccForFloatIter";
    public static final @NotNull String C_STORAGE_TYPE_NAME = "gdcc_for_float_iter";
    public static final @NotNull String C_INIT_HELPER_NAME = "gdcc_for_float_iter_init";
    public static final @NotNull String C_DESTROY_HELPER_NAME = "gdcc_for_float_iter_destroy";

    @Override
    public @NotNull String getTypeName() {
        return "GdccForFloatIter";
    }

    @Override
    public @NotNull String getLirTypeText() {
        return LIR_TYPE_TEXT;
    }

    @Override
    public @NotNull String getCStorageTypeName() {
        return C_STORAGE_TYPE_NAME;
    }

    @Override
    public @NotNull String getCInitHelperName() {
        return C_INIT_HELPER_NAME;
    }

    @Override
    public @NotNull String getCDestroyHelperName() {
        return C_DESTROY_HELPER_NAME;
    }
}
