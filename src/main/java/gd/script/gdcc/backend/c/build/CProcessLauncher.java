package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Starts one child OS process for a native build round. Every zig invocation of a round (all
/// TU compiles and the final link) funnels through this seam so the round can register each
/// started process in a [CProcessRegistry] and tests can drive the real [ZigCcCompiler] logic
/// with fake processes.
///
/// Contract: implementations apply the shared process contract of the round (working directory,
/// merged stderr/stdout where applicable, environment overrides) and throw [IOException] only
/// when the process could not be started at all — a returned [Process] counts as started and
/// must be registered by the caller immediately.
@FunctionalInterface
interface CProcessLauncher {
    @NotNull Process start(@NotNull List<String> cmd, @NotNull Path workingDir, @NotNull Map<String, String> environmentOverrides) throws IOException;

    /// Default production launcher backed by a real [ProcessBuilder]: working directory is the
    /// project dir, stderr is merged into stdout (one drained stream per process), and the zig
    /// cache roots are injected as environment overrides.
    static @NotNull CProcessLauncher processBuilder() {
        return (cmd, workingDir, environmentOverrides) -> {
            var processBuilder = new ProcessBuilder(cmd);
            processBuilder.directory(workingDir.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.environment().putAll(environmentOverrides);
            return processBuilder.start();
        };
    }
}
