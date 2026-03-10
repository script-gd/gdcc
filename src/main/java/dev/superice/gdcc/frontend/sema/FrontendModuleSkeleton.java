package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.lir.LirClassDef;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Class skeleton extraction result for one frontend module.
public record FrontendModuleSkeleton(
        @NotNull String moduleName,
        @NotNull List<FrontendSourceUnit> units,
        @NotNull List<LirClassDef> classDefs,
        @NotNull DiagnosticSnapshot diagnostics
) {
    public FrontendModuleSkeleton {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
        classDefs = List.copyOf(Objects.requireNonNull(classDefs, "classDefs must not be null"));
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }
}
