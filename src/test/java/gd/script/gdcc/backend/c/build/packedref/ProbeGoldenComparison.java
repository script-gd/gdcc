package gd.script.gdcc.backend.c.build.packedref;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/// Result of comparing one dual-run side's {@link ProbeOutput} against the golden
/// {@link ProbeOutput}. The comparison is a strict full-matrix contract — the golden file is
/// the single source of truth for the case inventory, so EVERY golden case must be present in
/// the actual output with an equal payload:
///
/// - missing cases (present in golden, absent from actual) indicate a crashed/truncated run;
/// - payload mismatches indicate a behavior divergence from the interpreter-locked baseline;
/// - unknown cases in the actual output (present in actual, absent from golden) indicate a
///   probe-library/golden desync;
/// - order violations indicate the actual run emitted known cases in a different relative
///   order than the golden, which breaks the deterministic `run_all` contract.
public record ProbeGoldenComparison(
        @NotNull List<String> missingCases,
        @NotNull List<String> payloadMismatches,
        @NotNull List<String> unknownActualCases,
        @NotNull List<String> orderViolations
) {
    public ProbeGoldenComparison {
        missingCases = List.copyOf(missingCases);
        payloadMismatches = List.copyOf(payloadMismatches);
        unknownActualCases = List.copyOf(unknownActualCases);
        orderViolations = List.copyOf(orderViolations);
    }

    public static @NotNull ProbeGoldenComparison compare(
            @NotNull ProbeOutput actual,
            @NotNull ProbeOutput golden
    ) {
        Objects.requireNonNull(actual, "actual must not be null");
        Objects.requireNonNull(golden, "golden must not be null");

        var missingCases = new ArrayList<String>();
        var payloadMismatches = new ArrayList<String>();
        for (var caseName : golden.caseNames()) {
            if (!actual.hasCase(caseName)) {
                missingCases.add(caseName);
                continue;
            }
            var expectedPayload = golden.requirePayload(caseName);
            var actualPayload = actual.requirePayload(caseName);
            if (!expectedPayload.equals(actualPayload)) {
                payloadMismatches.add(caseName + ": expected [" + expectedPayload + "] but was [" + actualPayload + "]");
            }
        }

        var goldenCaseNames = new LinkedHashSet<>(golden.caseNames());
        var unknownActualCases = actual.caseNames().stream()
                .filter(caseName -> !goldenCaseNames.contains(caseName))
                .toList();

        // Relative order of the cases present in both outputs must match the golden order;
        // this catches control-flow breakage (e.g. a case silently skipped then re-run later).
        var actualCaseNames = new LinkedHashSet<>(actual.caseNames());
        var expectedRelativeOrder = golden.caseNames().stream().filter(actualCaseNames::contains).toList();
        var actualRelativeOrder = actual.caseNames().stream().filter(goldenCaseNames::contains).toList();
        var orderViolations = expectedRelativeOrder.equals(actualRelativeOrder)
                ? List.<String>of()
                : List.of("expected relative order " + expectedRelativeOrder + " but was " + actualRelativeOrder);

        return new ProbeGoldenComparison(
                missingCases,
                payloadMismatches,
                unknownActualCases,
                orderViolations
        );
    }

    /// Whether every check passed (all four categories empty).
    public boolean matches() {
        return missingCases.isEmpty()
                && payloadMismatches.isEmpty()
                && unknownActualCases.isEmpty()
                && orderViolations.isEmpty();
    }

    /// Multi-line human-readable summary for assertion failure messages.
    public @NotNull String describe() {
        if (matches()) {
            return "probe output matches golden";
        }
        var description = new StringBuilder("probe output diverges from golden:");
        appendSection(description, "missing cases", missingCases);
        appendSection(description, "payload mismatches", payloadMismatches);
        appendSection(description, "unknown actual cases", unknownActualCases);
        appendSection(description, "order violations", orderViolations);
        return description.toString();
    }

    private static void appendSection(@NotNull StringBuilder description, @NotNull String title, @NotNull List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        description.append(System.lineSeparator()).append("  ").append(title).append(":");
        for (var entry : entries) {
            description.append(System.lineSeparator()).append("    - ").append(entry);
        }
    }
}
