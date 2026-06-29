package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public sealed interface GdType
        permits GdContainerType, GdCompilerType, GdMetaType, GdNilType, GdObjectType, GdPrimitiveType, GdRidType, GdStringLikeType, GdVariantType, GdVectorType, GdVoidType {
    @NotNull String getTypeName();

    boolean isNullable();

    @Nullable GdExtensionTypeEnum getGdExtensionType();

    default boolean isDestroyable() {
        return false;
    }
}
