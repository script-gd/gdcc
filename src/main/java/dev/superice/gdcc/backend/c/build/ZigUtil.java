package dev.superice.gdcc.backend.c.build;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ZigUtil {
    private ZigUtil() {
    }

    @SuppressWarnings("DuplicateExpressions")
    public static @Nullable Path findZig() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        var exeName = os.contains("win") ? "zig.exe" : "zig";

        // where / which
        var byTool = findByWhichOrWhere(exeName, os);
        if (byTool != null) return byTool;

        var env = System.getenv();

        // env
        for (var key : List.of("ZIG", "ZIG_HOME", "ZIG_ROOT", "zig")) {
            var val = env.get(key);
            if (val == null || val.isBlank()) continue;
            var p = Paths.get(val);
            if (Files.isExecutable(p)) return p;
            if (Files.isDirectory(p)) {
                var candidate = p.resolve(exeName);
                if (Files.isExecutable(candidate)) return candidate;
            }
        }

        // path
        var pathEnv = env.get("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            pathEnv = env.get("Path");
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            pathEnv = env.get("path");
        }
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (var part : pathEnv.split(File.pathSeparator)) {
                if (part.isBlank()) continue;
                var candidate = Paths.get(part).resolve(exeName);
                if (Files.isExecutable(candidate)) return candidate;
            }
        }

        // common locations
        var home = System.getProperty("user.home");
        var candidates = new ArrayList<Path>();

        if (os.contains("win")) {
            var prog = env.get("ProgramFiles");
            var progX86 = env.get("ProgramFiles(x86)");
            var localApp = env.get("LOCALAPPDATA");
            var programData = env.get("ProgramData");
            var userProfile = env.get("USERPROFILE");

            if (prog != null) candidates.add(Paths.get(prog, "Zig", exeName));
            if (progX86 != null) candidates.add(Paths.get(progX86, "Zig", exeName));
            if (localApp != null) candidates.add(Paths.get(localApp, "Programs", "Zig", exeName));
            if (programData != null) candidates.add(Paths.get(programData, "chocolatey", "bin", exeName));
            if (userProfile != null) candidates.add(Paths.get(userProfile, "scoop", "apps", "zig", "current", exeName));
            if (home != null) candidates.add(Paths.get(home, ".zigup", "bin", exeName));
        } else if (os.contains("mac")) {
            candidates.add(Paths.get("/usr/local/bin", exeName));
            candidates.add(Paths.get("/opt/homebrew/bin", exeName));
            candidates.add(Paths.get("/opt/homebrew/opt/zig/bin", exeName));
            if (home != null) candidates.add(Paths.get(home, ".zigup", "bin", exeName));
            if (home != null) candidates.add(Paths.get(home, ".local", "bin", exeName));
        } else { // linux / unix
            candidates.add(Paths.get("/usr/bin", exeName));
            candidates.add(Paths.get("/usr/local/bin", exeName));
            candidates.add(Paths.get("/snap/bin", exeName));
            if (home != null) candidates.add(Paths.get(home, ".zigup", "bin", exeName));
            if (home != null) candidates.add(Paths.get(home, ".local", "bin", exeName));
        }

        for (var c : candidates) {
            if (c != null && Files.isExecutable(c)) return c;
        }

        return null;
    }

    private static @Nullable Path findByWhichOrWhere(String exeName, String os) {
        var cmd = os.contains("win") ? List.of("where", exeName) : List.of("which", exeName);
        try {
            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            var p = pb.start();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (!out.isBlank()) {
                var first = out.split("\\r?\\n")[0].trim();
                var path = Paths.get(first);
                if (Files.isExecutable(path)) return path;
            }
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
