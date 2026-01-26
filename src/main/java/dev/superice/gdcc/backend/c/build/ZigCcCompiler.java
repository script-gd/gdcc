package dev.superice.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ZigCcCompiler implements CCompiler {
    @Override
    public CBuildResult compile(@NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull COptimizationLevel optimizationLevel, @NotNull TargetPlatform targetPlatform) throws IOException {
        var zig = ZigUtil.findZig();
        if (zig == null) {
            return new CBuildResult(false, "Zig executable not found on PATH or known locations", List.of());
        }

        if (cFiles.isEmpty()) {
            return new CBuildResult(false, "No C files to compile", List.of());
        }

        // Build command: zig cc -shared -I<includeDir> -o <output> <cFiles...>
        var outName = outputBaseName;
        if (targetPlatform == TargetPlatform.WINDOWS_X64) {
            outName = outputBaseName + ".dll";
        } else {
            // default to unix-like
            outName = "lib" + outputBaseName + ".so";
        }

        var outputPath = projectDir.resolve(outName).toAbsolutePath();

        var cmd = new ArrayList<String>();
        cmd.add(zig.toString());
        cmd.add("cc");
        //noinspection SwitchStatementWithTooFewBranches
        switch (targetPlatform) {
            case WINDOWS_X64 -> {
                cmd.add("-target");
                cmd.add("x86_64-windows-msvc");
                cmd.add("-D_MSC_VER=1900");
                cmd.add("-D_MSC_FULL_VER=190000000");
            }
        }
        cmd.add("-std=c23");
        cmd.add("-shared");

        // optimization mapping
        switch (optimizationLevel) {
            case DEBUG -> cmd.add("-O0");
            case RELEASE -> cmd.add("-O2");
            case RELEASE_WITH_DEBUG_INFO -> cmd.add("-O2");
        }

        // primary include dir (use -I<path> form)
        for (var inc : includeDirs) {
            cmd.add("-I" + inc.toAbsolutePath());
        }

        cmd.add("-o");
        cmd.add(outputPath.toString());
        for (var f : cFiles) cmd.add(f.toAbsolutePath().toString());

        try {
            var pb = new ProcessBuilder(cmd);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            var p = pb.start();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var exit = p.waitFor();
            boolean success = exit == 0 && Files.exists(outputPath);
            var artifacts = new ArrayList<Path>(3);
            if (success) {
                artifacts.add(outputPath);
                if (targetPlatform == TargetPlatform.WINDOWS_X64) {
                    var pdbPath = projectDir.resolve(outputBaseName + ".pdb").toAbsolutePath();
                    if (Files.exists(pdbPath)) {
                        artifacts.add(pdbPath);
                    }
                }
            }
            if (!success) {
                var cmdStr = String.join(" ", cmd);
                out = "Command: " + cmdStr + "\n" + out;
            }
            return new CBuildResult(success, out, artifacts);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CBuildResult(false, "Failed to run zig: " + e.getMessage(), List.of());
        }
    }
}
