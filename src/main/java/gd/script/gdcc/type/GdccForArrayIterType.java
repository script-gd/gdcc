package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;

/// Compiler-only storage type for lowered Array `for-in` iterator state.
/// Holds a copied source Array, current index, and cached size.
/// Not direct-struct-assignment safe: contains refcounted Array payload requiring deep copy.
public final class GdccForArrayIterType implements GdCompilerType {
    public static final @NotNull GdccForArrayIterType FOR_ARRAY_ITER = new GdccForArrayIterType();

    public static final @NotNull String LIR_TYPE_TEXT = "compiler::GdccForArrayIter";
    public static final @NotNull String C_STORAGE_TYPE_NAME = "gdcc_for_array_iter";
    public static final @NotNull String C_INIT_HELPER_NAME = "gdcc_for_array_iter_init";
    public static final @NotNull String C_DESTROY_HELPER_NAME = "gdcc_for_array_iter_destroy";
    public static final @NotNull String C_COPY_HELPER_NAME = "gdcc_for_array_iter_copy";

    @Override
    public @NotNull String getTypeName() {
        return "GdccForArrayIter";
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
