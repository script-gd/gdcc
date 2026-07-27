package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;

/// Compiler-only storage type for lowered Dictionary key `for-in` iterator state.
/// Holds a heap-shared keys snapshot box (non-atomic refcount), current index, and cached size.
/// Not direct-struct-assignment safe: `copy` must bump the shared box refcount.
public final class GdccForDictionaryIterType implements GdCompilerType {
    public static final @NotNull GdccForDictionaryIterType FOR_DICTIONARY_ITER = new GdccForDictionaryIterType();

    public static final @NotNull String LIR_TYPE_TEXT = "compiler::GdccForDictionaryIter";
    public static final @NotNull String C_STORAGE_TYPE_NAME = "gdcc_for_dictionary_iter";
    public static final @NotNull String C_INIT_HELPER_NAME = "gdcc_for_dictionary_iter_init";
    public static final @NotNull String C_DESTROY_HELPER_NAME = "gdcc_for_dictionary_iter_destroy";
    public static final @NotNull String C_COPY_HELPER_NAME = "gdcc_for_dictionary_iter_copy";

    @Override
    public @NotNull String getTypeName() {
        return "GdccForDictionaryIter";
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
