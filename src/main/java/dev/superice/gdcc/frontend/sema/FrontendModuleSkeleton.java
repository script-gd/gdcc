package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.lir.LirClassDef;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Class skeleton extraction result for one frontend module.
public record FrontendModuleSkeleton(
        @NotNull String moduleName,
        @NotNull List<FrontendSourceClassRelation> sourceClassRelations,
        @NotNull DiagnosticSnapshot diagnostics
) {
    public FrontendModuleSkeleton {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        sourceClassRelations = List.copyOf(Objects.requireNonNull(sourceClassRelations, "sourceClassRelations must not be null"));
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    /// Compatibility view of source units in declaration order.
    ///
    /// Callers that need to understand which classes came from which file should prefer
    /// `sourceClassRelations()` instead of relying on this flattened view.
    public @NotNull List<FrontendSourceUnit> units() {
        return sourceClassRelations.stream()
                .map(FrontendSourceClassRelation::unit)
                .toList();
    }

    /// Compatibility view of top-level script classes in source order.
    ///
    /// This accessor intentionally exposes only one class per source file. Nested classes are kept
    /// on their owning `FrontendSourceClassRelation` and in `allClassDefs()`.
    public @NotNull List<LirClassDef> classDefs() {
        return sourceClassRelations.stream()
                .map(FrontendSourceClassRelation::topLevelClassDef)
                .toList();
    }

    /// Returns every class skeleton contributed by the module, including nested classes.
    public @NotNull List<LirClassDef> allClassDefs() {
        var classDefs = new ArrayList<LirClassDef>();
        for (var relation : sourceClassRelations) {
            classDefs.addAll(relation.allClassDefs());
        }
        return List.copyOf(classDefs);
    }
}
