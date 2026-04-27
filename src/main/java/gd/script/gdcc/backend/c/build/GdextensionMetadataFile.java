package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class GdextensionMetadataFile {
    public static final @NotNull String ENTRY_SYMBOL = "gdextension_entry";
    public static final @NotNull String COMPATIBILITY_MINIMUM = "4.5";

    private GdextensionMetadataFile() {
    }

    public static @NotNull Path write(
            @NotNull Path metadataPath,
            @NotNull Path libraryPath,
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform
    ) throws IOException {
        var metadataDir = Objects.requireNonNull(metadataPath, "metadataPath must not be null")
                .toAbsolutePath()
                .normalize()
                .getParent();
        if (metadataDir != null) {
            Files.createDirectories(metadataDir);
        }
        var libraryText = metadataDir == null
                ? pathText(libraryPath)
                : pathText(metadataDir.relativize(libraryPath.toAbsolutePath().normalize()));
        Files.writeString(
                metadataPath,
                render(libraryText, optimizationLevel, targetPlatform),
                StandardCharsets.UTF_8
        );
        return metadataPath;
    }

    public static @NotNull String render(
            @NotNull String libraryPath,
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform
    ) {
        return """
                [configuration]
                
                entry_symbol = "%s"
                compatibility_minimum = "%s"
                
                [libraries]
                %s = "%s"
                """.formatted(
                ENTRY_SYMBOL,
                COMPATIBILITY_MINIMUM,
                libraryKey(optimizationLevel, targetPlatform),
                Objects.requireNonNull(libraryPath, "libraryPath must not be null")
        );
    }

    public static @NotNull String libraryKey(
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform
    ) {
        return platformFeature(targetPlatform)
                + "."
                + Objects.requireNonNull(optimizationLevel, "optimizationLevel must not be null")
                .name()
                .toLowerCase(Locale.ROOT);
    }

    private static @NotNull String platformFeature(@NotNull TargetPlatform targetPlatform) {
        return switch (Objects.requireNonNull(targetPlatform, "targetPlatform must not be null")) {
            case WINDOWS_X86_64, WINDOWS_AARCH64 -> "windows";
            case LINUX_X86_64, LINUX_AARCH64, LINUX_RISCV64 -> "linux";
            case ANDROID_X86_64, ANDROID_AARCH64 -> "android";
            case WEB_WASM32 -> "web";
        };
    }

    private static @NotNull String pathText(@NotNull Path path) {
        return path.toString().replace('\\', '/');
    }
}
