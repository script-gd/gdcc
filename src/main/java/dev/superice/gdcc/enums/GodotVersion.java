package dev.superice.gdcc.enums;

import org.jetbrains.annotations.NotNull;

public enum GodotVersion {
    V451("4.5.1", true),
    ;

    public final @NotNull String version;
    public final boolean isStable;

    GodotVersion(@NotNull String version, boolean isStable) {
        this.version = version;
        this.isStable = isStable;
    }

    public @NotNull String getShortVersion() {
        return version.replace(".", "");
    }
}
