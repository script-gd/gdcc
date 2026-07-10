package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Callable-scoped batch of suite export transactions.
///
/// Nested suites contribute their transactions here without mutating stable side tables. The root
/// callable applies the complete batch only after every supported child suite has resolved.
public final class FrontendCallableExportBatch {
    private final @NotNull List<FrontendPatchTransaction> transactions = new ArrayList<>();

    public void accumulate(@NotNull FrontendPatchTransaction transaction) {
        transactions.add(Objects.requireNonNull(transaction, "transaction must not be null"));
    }

    public void applyTo(@NotNull FrontendAnalysisData analysisData) {
        var checkedAnalysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
        for (var transaction : transactions) {
            transaction.applyTo(checkedAnalysisData);
        }
    }
}
