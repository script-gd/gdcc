package dev.superice.gdcc.backend;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface ProjectBuilder<P extends ProjectInfo, C extends Codegen, R> {
    void initProject(@NotNull P projectInfo) throws IOException;

    R buildProject(@NotNull P projectInfo, @NotNull C codegen) throws IOException;
}
