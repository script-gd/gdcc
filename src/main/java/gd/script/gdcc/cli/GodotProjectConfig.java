package gd.script.gdcc.cli;

import gd.script.gdcc.backend.c.build.CCompilerKind;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.exception.GodotConfigException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// The GDCC-relevant settings extracted from `project.godot`, mirroring the keys the editor addon
/// reads (`plugin.gd` registration + `gdcc_project_manager.gd` build reads), but strict instead of
/// silently coercing: a wrong type or a feature-override sibling (`key.tag`) that could change the
/// build is an error instead of a silently applied wrong value.
///
/// @param projectName       `application/config/name` — required, must stay non-blank after trim.
/// @param excludedGlobs     `gdcc/sync/excluded_globs` — auto-discovery exclusion globs in Godot
///                          `String.match` form; defaults to the addon's `addons/gdcc/*`.
/// @param optimizationLevel `gdcc/build/optimization_level` — `DEBUG`/`RELEASE`; default `DEBUG`.
/// @param strictMode        `gdcc/build/strict_mode` — default `false`.
/// @param cCompilerKind     `gdcc/build/c_compiler_kind` — `AUTO` (stored as `null`, the API's
///                          automatic selection), `ZIG_CC` or `TINY_CC`; default `AUTO`.
record GodotProjectConfig(
        @NotNull String projectName,
        @NotNull List<String> excludedGlobs,
        @NotNull COptimizationLevel optimizationLevel,
        boolean strictMode,
        @Nullable CCompilerKind cCompilerKind
) {
    private static final @NotNull List<String> DEFAULT_EXCLUDED_GLOBS = List.of("addons/gdcc/*");

    private static final @NotNull String APPLICATION_SECTION = "application";
    private static final @NotNull String NAME_KEY = "config/name";
    private static final @NotNull String GDCC_SECTION = "gdcc";
    private static final @NotNull String EXCLUDED_GLOBS_KEY = "sync/excluded_globs";
    private static final @NotNull String OPTIMIZATION_LEVEL_KEY = "build/optimization_level";
    private static final @NotNull String STRICT_MODE_KEY = "build/strict_mode";
    private static final @NotNull String C_COMPILER_KIND_KEY = "build/c_compiler_kind";

    GodotProjectConfig {
        Objects.requireNonNull(projectName, "projectName must not be null");
        if (projectName.isBlank()) {
            throw new IllegalArgumentException("projectName must not be blank");
        }
        excludedGlobs = List.copyOf(Objects.requireNonNull(excludedGlobs, "excludedGlobs must not be null"));
        Objects.requireNonNull(optimizationLevel, "optimizationLevel must not be null");
    }

    /// Extracts the five consumed settings from a parsed `project.godot`. Feature-override keys
    /// (`<key>.<tag>`) on consumed settings are rejected: ignoring them would silently build with
    /// different values than Godot would apply.
    static @NotNull GodotProjectConfig from(@NotNull GodotConfigFile config, @NotNull String origin) {
        checkNoOverrides(config, APPLICATION_SECTION, NAME_KEY, origin);
        checkNoOverrides(config, GDCC_SECTION, EXCLUDED_GLOBS_KEY, origin);
        checkNoOverrides(config, GDCC_SECTION, OPTIMIZATION_LEVEL_KEY, origin);
        checkNoOverrides(config, GDCC_SECTION, STRICT_MODE_KEY, origin);
        checkNoOverrides(config, GDCC_SECTION, C_COMPILER_KIND_KEY, origin);

        var projectName = extractProjectName(config, origin);
        var excludedGlobs = extractExcludedGlobs(config, origin);
        var optimizationLevel = extractOptimizationLevel(config, origin);
        var strictMode = extractStrictMode(config, origin);
        var cCompilerKind = extractCCompilerKind(config, origin);
        return new GodotProjectConfig(projectName, excludedGlobs, optimizationLevel, strictMode, cCompilerKind);
    }

    private static void checkNoOverrides(
            @NotNull GodotConfigFile config,
            @NotNull String section,
            @NotNull String key,
            @NotNull String origin
    ) {
        if (config.hasKeysWithPrefix(section, key + ".")) {
            throw new GodotConfigException(
                    origin + ": unsupported feature-override setting under '" + key
                            + "'; remove the override so the CLI sees one effective value"
            );
        }
    }

    private static @NotNull String extractProjectName(@NotNull GodotConfigFile config, @NotNull String origin) {
        var entry = config.findEntry(APPLICATION_SECTION, NAME_KEY).orElse(null);
        if (entry == null) {
            throw new GodotConfigException(origin + ": missing required setting 'application/config/name'");
        }
        if (!(entry.value() instanceof GodotConfigFile.StringValue stringValue) || stringValue.text().isBlank()) {
            throw new GodotConfigException(
                    origin + ": 'application/config/name' must be a non-empty string, got "
                            + describe(entry.value())
            );
        }
        return stringValue.text().trim();
    }

    private static @NotNull List<String> extractExcludedGlobs(@NotNull GodotConfigFile config, @NotNull String origin) {
        var entry = config.findEntry(GDCC_SECTION, EXCLUDED_GLOBS_KEY).orElse(null);
        if (entry == null) {
            return DEFAULT_EXCLUDED_GLOBS;
        }
        if (!(entry.value() instanceof GodotConfigFile.StringArrayValue arrayValue)) {
            throw new GodotConfigException(
                    origin + ": 'gdcc/sync/excluded_globs' must be a string array, got " + describe(entry.value())
            );
        }
        return arrayValue.items();
    }

    private static @NotNull COptimizationLevel extractOptimizationLevel(
            @NotNull GodotConfigFile config,
            @NotNull String origin
    ) {
        var entry = config.findEntry(GDCC_SECTION, OPTIMIZATION_LEVEL_KEY).orElse(null);
        if (entry == null) {
            return COptimizationLevel.DEBUG;
        }
        if (entry.value() instanceof GodotConfigFile.StringValue stringValue) {
            return switch (stringValue.text()) {
                case "DEBUG" -> COptimizationLevel.DEBUG;
                case "RELEASE" -> COptimizationLevel.RELEASE;
                default -> throw invalidLevel(origin, stringValue.text());
            };
        }
        throw new GodotConfigException(
                origin + ": 'gdcc/build/optimization_level' must be a string, got " + describe(entry.value())
        );
    }

    private static @NotNull GodotConfigException invalidLevel(@NotNull String origin, @NotNull String value) {
        return new GodotConfigException(
                origin + ": 'gdcc/build/optimization_level' must be DEBUG or RELEASE, got '" + value + "'"
        );
    }

    private static boolean extractStrictMode(@NotNull GodotConfigFile config, @NotNull String origin) {
        var entry = config.findEntry(GDCC_SECTION, STRICT_MODE_KEY).orElse(null);
        if (entry == null) {
            return false;
        }
        if (!(entry.value() instanceof GodotConfigFile.BoolValue boolValue)) {
            throw new GodotConfigException(
                    origin + ": 'gdcc/build/strict_mode' must be a bool, got " + describe(entry.value())
            );
        }
        return boolValue.flag();
    }

    private static @Nullable CCompilerKind extractCCompilerKind(
            @NotNull GodotConfigFile config,
            @NotNull String origin
    ) {
        var entry = config.findEntry(GDCC_SECTION, C_COMPILER_KIND_KEY).orElse(null);
        if (entry == null) {
            return null;
        }
        if (entry.value() instanceof GodotConfigFile.StringValue stringValue) {
            return switch (stringValue.text()) {
                case "AUTO" -> null;
                case "ZIG_CC" -> CCompilerKind.ZIG_CC;
                case "TINY_CC" -> CCompilerKind.TINY_CC;
                default -> throw new GodotConfigException(
                        origin + ": 'gdcc/build/c_compiler_kind' must be AUTO, ZIG_CC or TINY_CC, got '"
                                + stringValue.text() + "'"
                );
            };
        }
        throw new GodotConfigException(
                origin + ": 'gdcc/build/c_compiler_kind' must be a string, got " + describe(entry.value())
        );
    }

    private static @NotNull String describe(@NotNull GodotConfigFile.Value value) {
        return switch (value) {
            case GodotConfigFile.StringValue v -> "string '" + v.text() + "'";
            case GodotConfigFile.BoolValue v -> "bool " + v.flag();
            case GodotConfigFile.IntValue v -> "int " + v.number();
            case GodotConfigFile.FloatValue v -> "float " + v.number();
            case GodotConfigFile.StringArrayValue v -> "string array " + v.items();
            case GodotConfigFile.OpaqueValue v -> "unsupported value '" + v.raw() + "'";
        };
    }
}
