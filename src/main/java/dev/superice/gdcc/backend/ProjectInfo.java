package dev.superice.gdcc.backend;

import dev.superice.gdcc.enums.GodotVersion;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

public abstract class ProjectInfo {
    private final @NotNull String projectName;
    private final @NotNull GodotVersion godotVersion;
    private final @NotNull Path projectPath;

    public ProjectInfo(
            @NotNull String projectName,
            @NotNull GodotVersion godotVersion,
            @NotNull Path projectPath
    ) {
        this.projectName = projectName;
        this.godotVersion = godotVersion;
        this.projectPath = projectPath;
    }

    public @NotNull String projectName() {
        return projectName;
    }

    public @NotNull GodotVersion godotVersion() {
        return godotVersion;
    }

    public @NotNull Path projectPath() {
        return projectPath;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (ProjectInfo) obj;
        return Objects.equals(this.projectName, that.projectName) &&
                Objects.equals(this.godotVersion, that.godotVersion) &&
                Objects.equals(this.projectPath, that.projectPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectName, godotVersion, projectPath);
    }

    @Override
    public String toString() {
        return "ProjectInfo[" +
                "projectName=" + projectName + ", " +
                "godotVersion=" + godotVersion + ", " +
                "projectPath=" + projectPath + ']';
    }

}
