package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;

/// Compiler-only storage type for lowered generic Variant `for-in` iterator state.
/// Holds a copied source Variant, the GDExtension iterator Variant, and validity flags.
/// Not direct-struct-assignment safe: contains refcounted Variant payloads requiring deep copy.
public final class GdccForVariantIterType implements GdCompilerType {
    public static final @NotNull GdccForVariantIterType FOR_VARIANT_ITER = new GdccForVariantIterType();

    public static final @NotNull String LIR_TYPE_TEXT = "compiler::GdccForVariantIter";
    public static final @NotNull String C_STORAGE_TYPE_NAME = "gdcc_for_variant_iter";
    public static final @NotNull String C_INIT_HELPER_NAME = "gdcc_for_variant_iter_init";
    public static final @NotNull String C_DESTROY_HELPER_NAME = "gdcc_for_variant_iter_destroy";
    public static final @NotNull String C_COPY_HELPER_NAME = "gdcc_for_variant_iter_copy";

    @Override
    public @NotNull String getTypeName() {
        return "GdccForVariantIter";
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
