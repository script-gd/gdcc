package dev.superice.gdcc.frontend.parse;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Immutable frontend module input snapshot.
///
/// The semantic pipeline now consumes one module carrier instead of threading `moduleName`,
/// `FrontendSourceUnit`s, and future module-level knobs through parallel parameters. The
/// top-level runtime-name map is stored here already so later identity work can start from a
/// stable input boundary, even though step 1 does not consume the mapping yet.
public record FrontendModule(
        @NotNull String moduleName,
        @NotNull List<FrontendSourceUnit> units,
        @NotNull Map<String, String> topLevelRuntimeNameMap
) {
    public FrontendModule {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
        topLevelRuntimeNameMap = Map.copyOf(Objects.requireNonNull(
                topLevelRuntimeNameMap,
                "topLevelRuntimeNameMap must not be null"
        ));
    }

    public FrontendModule(@NotNull String moduleName, @NotNull List<FrontendSourceUnit> units) {
        this(moduleName, units, Map.of());
    }

    public static @NotNull FrontendModule singleUnit(@NotNull String moduleName, @NotNull FrontendSourceUnit unit) {
        return new FrontendModule(moduleName, List.of(unit));
    }
}
