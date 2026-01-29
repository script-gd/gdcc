package dev.superice.gdcc.type;

import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

public record GdObjectType(@NotNull String className) implements GdType {
    public final static GdObjectType OBJECT = new GdObjectType();

    public GdObjectType(@NotNull String className) {
        this.className = className;
    }

    public GdObjectType() {
        this("Object");
    }

    public boolean checkEngineType(@NotNull ClassRegistry classRegistry) {
        // Engine types are those provided by the GD extension API (gdClassByName)
        return classRegistry.isGdClass(this.className);
    }

    public boolean checkGdccType(@NotNull ClassRegistry classRegistry) {
        // User-defined gdcc classes are stored in the registry's gdccClassByName
        return classRegistry.isGdccClass(this.className);
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
