package dev.superice.gdcc.gdextension;

public record ExtensionHeader(
        int versionMajor,
        int versionMinor,
        int versionPatch,
        String versionStatus,
        String versionBuild,
        String versionFullName,
        String precision) {
}
