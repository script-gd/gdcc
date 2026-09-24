package gd.script.gdcc.backend.c.build.packedref;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Result of comparing one dual-run side's {@link ProbeOutput} against the golden
/// {@link ProbeOutput}. Payload equality is checked only for the given `checkedCases`
/// (the Phase-A-enabled subset); structural checks apply unconditionally so that harness
/// bugs surface even in phases where a case is still disabled:
///
/// - unknown cases in the actual output (present in actual, absent from golden) indicate a
///   probe-library/registry/golden desync;
/// - order violations indicate the actual run emitted known cases in a different relative
///   order than the golden, which breaks the deterministic `run_all` contract;
/// - checked cases missing from the golden indicate a registry/golden desync.
///
/// Unchecked (disabled) cases are deliberately ignored for payload and missing checks: they
/// run on both sides but their gdcc-side values are scheduled for a later phase.
public record ProbeGoldenComparison(
        @NotNull List<String> missingCheckedCases,
        @NotNull List<String> payloadMismatches,
        @NotNull List<String> checkedCasesMissingFromGolden,
        @NotNull List<String> unknownActualCases,
        @NotNull List<String> orderViolations
) {
    public ProbeGoldenComparison {
        missingCheckedCases = List.copyOf(missingCheckedCases);
        payloadMismatches = List.copyOf(payloadMismatches);
        checkedCasesMissingFromGolden = List.copyOf(checkedCasesMissingFromGolden);
        unknownActualCases = List.copyOf(unknownActualCases);
        orderViolations = List.copyOf(orderViolations);
    }

    public static @NotNull ProbeGoldenComparison compare(
            @NotNull ProbeOutput actual,
            @NotNull ProbeOutput golden,
            @NotNull Set<String> checkedCases
    ) {
        Objects.requireNonNull(actual, "actual must not be null");
        Objects.requireNonNull(golden, "golden must not be null");
        Objects.requireNonNull(checkedCases, "checkedCases must not be null");

        var missingCheckedCases = new ArrayList<String>();
        var payloadMismatches = new ArrayList<String>();
        var checkedCasesMissingFromGolden = new ArrayList<String>();
        for (var caseName : checkedCases) {
            if (!golden.hasCase(caseName)) {
                checkedCasesMissingFromGolden.add(caseName);
                continue;
            }
            if (!actual.hasCase(caseName)) {
                missingCheckedCases.add(caseName);
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
                missingCheckedCases,
                payloadMismatches,
                checkedCasesMissingFromGolden,
                unknownActualCases,
                orderViolations
        );
    }

    /// Whether every check passed (all five categories empty).
    public boolean matches() {
        return missingCheckedCases.isEmpty()
                && payloadMismatches.isEmpty()
                && checkedCasesMissingFromGolden.isEmpty()
                && unknownActualCases.isEmpty()
                && orderViolations.isEmpty();
    }

    /// Multi-line human-readable summary for assertion failure messages.
    public @NotNull String describe() {
        if (matches()) {
            return "probe output matches golden";
        }
        var description = new StringBuilder("probe output diverges from golden:");
        appendSection(description, "missing checked cases", missingCheckedCases);
        appendSection(description, "payload mismatches", payloadMismatches);
        appendSection(description, "checked cases missing from golden", checkedCasesMissingFromGolden);
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
