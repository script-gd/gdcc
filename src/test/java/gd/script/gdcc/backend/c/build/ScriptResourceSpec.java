package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Managed script resource that should be written into the Godot project as-is.
public record ScriptResourceSpec(
        @NotNull String resourcePath,
        @NotNull String scriptContent
) {
    public ScriptResourceSpec {
        resourcePath = validateResourcePath(resourcePath);
        scriptContent = Objects.requireNonNull(scriptContent);
    }

    public @NotNull Path resolveProjectPath(@NotNull Path testProjectDir) {
        var relativePath = resourceRelativePath();
        return Objects.requireNonNull(testProjectDir).resolve(relativePath).normalize();
    }

    public @NotNull Path managedScriptRoot(@NotNull Path testProjectDir) {
        var segments = resourceRelativePath().split("/");
        var rootRelativePath = switch (segments.length) {
            case 0 -> "";
            case 1 -> segments[0];
            default -> segments[0] + "/" + segments[1];
        };
        return Objects.requireNonNull(testProjectDir).resolve(rootRelativePath).normalize();
    }

    public void writeToProject(@NotNull Path testProjectDir) throws IOException {
        var scriptPath = resolveProjectPath(testProjectDir);
        var parentDir = scriptPath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        Files.writeString(scriptPath, scriptContent, StandardCharsets.UTF_8);
    }

    private @NotNull String resourceRelativePath() {
        return resourcePath.substring("res://".length());
    }

    static @NotNull String validateResourcePath(@NotNull String resourcePath) {
        var value = StringUtil.requireNonBlank(resourcePath, "resourcePath");
        if (!value.startsWith("res://")) {
            throw new IllegalArgumentException("resourcePath must start with res:// : " + value);
        }
        var relativePath = value.substring("res://".length());
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        return value;
    }
}
