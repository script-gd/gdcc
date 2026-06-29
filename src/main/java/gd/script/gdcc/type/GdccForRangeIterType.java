package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;

/// Compiler-only storage type for future lowered `for i in range(...)` iterator state.
/// It is intentionally not source-facing and must only be serialized through LIR-only text.
public final class GdccForRangeIterType implements GdCompilerType {
    public static final @NotNull GdccForRangeIterType FOR_RANGE_ITER = new GdccForRangeIterType();

    public static final @NotNull String LIR_TYPE_TEXT = "compiler::GdccForRangeIter";
    public static final @NotNull String C_STORAGE_TYPE_NAME = "gdcc_for_range_iter";
    public static final @NotNull String C_INIT_HELPER_NAME = "gdcc_for_range_iter_init";
    public static final @NotNull String C_DESTROY_HELPER_NAME = "gdcc_for_range_iter_destroy";

    @Override
    public @NotNull String getTypeName() {
        return "GdccForRangeIter";
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
