package dev.superice.gdcc.frontend.parse;

import dev.superice.gdcc.frontend.FrontendClassNameContract;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Immutable frontend module input snapshot.
///
/// The semantic pipeline now consumes one module carrier instead of threading `moduleName`,
/// `FrontendSourceUnit`s, and module-level canonical-name mapping through parallel parameters. The
/// top-level canonical-name map is frozen here at the public boundary so skeleton/header work can
/// always start from one stable module snapshot.
public record FrontendModule(
        @NotNull String moduleName,
        @NotNull List<FrontendSourceUnit> units,
        @NotNull Map<String, String> topLevelCanonicalNameMap
) {
    public FrontendModule {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
        topLevelCanonicalNameMap = FrontendClassNameContract.freezeTopLevelCanonicalNameMap(Objects.requireNonNull(
                topLevelCanonicalNameMap,
                "topLevelCanonicalNameMap must not be null"
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
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        return new FrontendModule(moduleName, List.of(unit), topLevelCanonicalNameMap);
    }
}
