package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;

/// Compiler-only storage type for lowered String `for-in` iterator state.
/// Holds a copied source String, current index, and cached length.
/// Not direct-struct-assignment safe: contains refcounted String payload requiring deep copy.
public final class GdccForStringIterType implements GdCompilerType {
    public static final @NotNull GdccForStringIterType FOR_STRING_ITER = new GdccForStringIterType();

    public static final @NotNull String LIR_TYPE_TEXT = "compiler::GdccForStringIter";
    public static final @NotNull String C_STORAGE_TYPE_NAME = "gdcc_for_string_iter";
    public static final @NotNull String C_INIT_HELPER_NAME = "gdcc_for_string_iter_init";
    public static final @NotNull String C_DESTROY_HELPER_NAME = "gdcc_for_string_iter_destroy";
    public static final @NotNull String C_COPY_HELPER_NAME = "gdcc_for_string_iter_copy";

    @Override
    public @NotNull String getTypeName() {
        return "GdccForStringIter";
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

    @Override
    public @NotNull String getCCopyHelperName() {
        return C_COPY_HELPER_NAME;
    }

    @Override
    public boolean isDirectStructAssignmentSafe() {
        return false;
    }
}
