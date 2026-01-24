package dev.superice.gdcc.type;

import org.jetbrains.annotations.NotNull;

public final class GdObjectType implements GdType {
    public final static GdObjectType OBJECT = new GdObjectType();

    public final String className;
    public boolean isEngineType = false;

    public GdObjectType(@NotNull String className) {
        this.className = className;
        this.isEngineType = false;
    }

    public GdObjectType(@NotNull String className, boolean isEngineType) {
        this.className = className;
        this.isEngineType = isEngineType;
    }

    public GdObjectType() {
        this.className = "Object";
    }

    @Override
    public @NotNull String getTypeName() {
        return this.className;
    }

    @Override
    public boolean isNullable() {
        return true;
    }

    @Override
    public @NotNull GdExtensionTypeEnum getGdExtensionType() {
        return GdExtensionTypeEnum.OBJECT;
    }
}
