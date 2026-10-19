package gd.script.gdcc.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/// Desktop system paths following Godot's `OS.get_cache_dir()` platform rules.
public final class SystemPathUtil {
    private SystemPathUtil() {
    }

    /// Returns the global cache directory without creating it. Uses the process environment,
    /// not JVM `user.home` or `java.io.tmpdir`, so Godot and gdcc agree in redirected environments.
    public static @NotNull Path getCacheDir() {
        return getCacheDir(System.getProperty("os.name", ""), System.getenv(), SystemPathUtil::windowsTempDir);
    }

    static @NotNull Path getCacheDir(@NotNull String osName, @NotNull Map<String, String> environment,
                                     @NotNull Supplier<@Nullable Path> windowsTempDir) {
        var os = osName.toLowerCase(Locale.ROOT);
        var home = environment.get("HOME");
        if (os.contains("mac") || os.contains("darwin")) {
            return home != null ? Path.of(home).resolve("Library/Caches") : Path.of(".");
        }
        if (os.contains("win")) {
            var localAppData = environment.get("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isEmpty()) {
                return Path.of(localAppData);
            }
            var temp = windowsTempDir.get();
            if (temp != null) {
                return temp;
            }
            var appData = environment.get("APPDATA");
            return appData != null && !appData.isEmpty() ? Path.of(appData) : Path.of(".");
        }
        if (os.contains("linux") || os.contains("bsd")) {
            var cacheHome = environment.get("XDG_CACHE_HOME");
            if (cacheHome != null && isGodotAbsolutePath(cacheHome)) {
                return Path.of(cacheHome);
            }
            if (home != null) {
                return Path.of(home).resolve(".cache");
            }
            var configHome = environment.get("XDG_CONFIG_HOME");
            return configHome != null && isGodotAbsolutePath(configHome) ? Path.of(configHome) : Path.of(".");
        }
        return Path.of(".");
    }

    /// Godot's `String.is_absolute_path()` is platform-independent, unlike `Path.isAbsolute()`.
    private static boolean isGodotAbsolutePath(@NotNull String path) {
        return path.startsWith("/") || path.startsWith("\\") || path.contains(":/") || path.contains(":\\");
    }

    /// Mirrors Godot's Win32 temporary-directory fallback, including short-name expansion.
    /// Query Win32 directly: `-Djava.io.tmpdir` must not redirect the system cache.
    static @Nullable Path windowsTempDir() {
        try (var arena = Arena.ofConfined()) {
            var lookup = SymbolLookup.libraryLookup("kernel32.dll", arena);
            var linker = Linker.nativeLinker();
            var getTempPath = linker.downcallHandle(lookup.find("GetTempPathW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            var getLongPathName = linker.downcallHandle(lookup.find("GetLongPathNameW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            var capacity = 262;
            var buffer = arena.allocate(ValueLayout.JAVA_CHAR, capacity);
            var length = (int) getTempPath.invokeExact(capacity, buffer);
            if (length <= 0 || length >= capacity) {
                return null;
            }
            var path = buffer.getString(0, StandardCharsets.UTF_16LE);
            var longLength = (int) getLongPathName.invokeExact(buffer, buffer, capacity);
            if (longLength > 0 && longLength < capacity) {
                path = buffer.getString(0, StandardCharsets.UTF_16LE);
            }
            return Path.of(path);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Failed to query the Windows temporary directory", exception);
        }
    }
}
