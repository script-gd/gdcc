package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
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

    /// Returns every class skeleton contributed by the module, including nested classes.
    public @NotNull List<LirClassDef> allClassDefs() {
        var classDefs = new ArrayList<LirClassDef>();
        for (var relation : sourceClassRelations) {
            classDefs.addAll(relation.allClassDefs());
        }
        return List.copyOf(classDefs);
    }
}
