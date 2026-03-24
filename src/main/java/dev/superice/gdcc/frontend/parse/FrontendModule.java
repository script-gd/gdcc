package dev.superice.gdcc.frontend.parse;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Immutable frontend module input snapshot.
///
/// The semantic pipeline now consumes one module carrier instead of threading `moduleName`,
/// `FrontendSourceUnit`s, and module-level runtime-name mapping through parallel parameters. The
/// top-level runtime-name map is frozen here at the public boundary so skeleton/header work can
/// always start from one stable module snapshot.
public record FrontendModule(
        @NotNull String moduleName,
        @NotNull List<FrontendSourceUnit> units,
        @NotNull Map<String, String> topLevelRuntimeNameMap
) {
    public FrontendModule {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
        topLevelRuntimeNameMap = freezeTopLevelRuntimeNameMap(Objects.requireNonNull(
                topLevelRuntimeNameMap,
                "topLevelRuntimeNameMap must not be null"
        ));
    }

    public FrontendModule(@NotNull String moduleName, @NotNull List<FrontendSourceUnit> units) {
        this(moduleName, units, Map.of());
    }

    public static @NotNull FrontendModule singleUnit(@NotNull String moduleName, @NotNull FrontendSourceUnit unit) {
        return singleUnit(moduleName, unit, Map.of());
    }

    public static @NotNull FrontendModule singleUnit(
            @NotNull String moduleName,
            @NotNull FrontendSourceUnit unit,
            @NotNull Map<String, String> topLevelRuntimeNameMap
    ) {
        return new FrontendModule(moduleName, List.of(unit), topLevelRuntimeNameMap);
    }

    private static @NotNull Map<String, String> freezeTopLevelRuntimeNameMap(
            @NotNull Map<String, String> topLevelRuntimeNameMap
    ) {
        var frozenEntries = new LinkedHashMap<String, String>(topLevelRuntimeNameMap.size());
        for (var entry : topLevelRuntimeNameMap.entrySet()) {
            var sourceName = Objects.requireNonNull(entry.getKey(), "topLevelRuntimeNameMap key must not be null");
            var runtimeName = Objects.requireNonNull(entry.getValue(), "topLevelRuntimeNameMap value must not be null");
            if (sourceName.isBlank()) {
                throw new IllegalArgumentException("topLevelRuntimeNameMap key must not be blank");
            }
            if (runtimeName.isBlank()) {
                throw new IllegalArgumentException("topLevelRuntimeNameMap value must not be blank");
            }
            frozenEntries.put(sourceName, runtimeName);
        }
        return Collections.unmodifiableMap(frozenEntries);
    }
}
