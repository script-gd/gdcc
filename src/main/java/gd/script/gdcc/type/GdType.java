package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public sealed interface GdType
        permits GdContainerType, GdMetaType, GdNilType, GdObjectType, GdPrimitiveType, GdRidType, GdStringLikeType, GdVariantType, GdVectorType, GdVoidType, GdccForRangeIterType {
    @NotNull String getTypeName();

    boolean isNullable();

    @Nullable GdExtensionTypeEnum getGdExtensionType();

    default boolean isDestroyable() {
        return false;
    }
}
