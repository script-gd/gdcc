package dev.superice.gdcc.backend;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record GeneratedFile(
        byte[] contentWriter,
        @NotNull String filePath
) {
    public @NotNull Path saveTo(@NotNull Path directory) throws IOException {
        var fullPath = directory.resolve(this.filePath);
        Files.createDirectories(fullPath.getParent());
        Files.write(fullPath, this.contentWriter);
        return fullPath;
    }
}
