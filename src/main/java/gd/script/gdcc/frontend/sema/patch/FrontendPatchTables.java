package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

final class FrontendPatchTables {
    private FrontendPatchTables() {
    }

    static <V> @NotNull FrontendAstSideTable<V> emptySideTable() {
        return new FrontendAstSideTable<>();
    }

    static <V> @NotNull FrontendAstSideTable<V> copySideTable(
            @NotNull FrontendAstSideTable<V> source,
            @NotNull String fieldName
    ) {
        var checkedSource = Objects.requireNonNull(source, fieldName + " must not be null");
        var copy = new FrontendAstSideTable<V>();
        copy.putAll(checkedSource);
        return copy;
    }
}
