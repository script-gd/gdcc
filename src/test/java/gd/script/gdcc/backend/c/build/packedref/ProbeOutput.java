package gd.script.gdcc.backend.c.build.packedref;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/// Parsed model of the `PROBE|<CASE>|<payload>` lines emitted by the packed-array reference
/// semantics probe library (`packed_ref_probes.gd`). Both sides of the dual-run harness
/// (Godot interpreter project and gdcc-compiled project) print these lines; the harness parses
/// raw process output into this model before any comparison happens.
///
/// Strictness contract (anchored by `ProbeOutputTest`):
/// - only lines starting with `PROBE|` are considered; every other line is engine noise;
/// - a PROBE line must split into exactly three `|`-separated segments, the case name must
///   match `[A-Z0-9_]+`, and the payload must be non-empty (a malformed PROBE line indicates
///   a generator bug, so it is rejected instead of silently ignored);
/// - each case may appear at most once per output (emission order is preserved).
public final class ProbeOutput {
    /// Line prefix recognized as probe output; everything else in a process transcript is noise.
    public static final @NotNull String PREFIX = "PROBE|";

    private final @NotNull LinkedHashMap<String, String> payloadsByCase;

    private ProbeOutput(@NotNull LinkedHashMap<String, String> payloadsByCase) {
        this.payloadsByCase = payloadsByCase;
    }

    /// Parses raw process output, throwing `IllegalArgumentException` on any malformed PROBE line.
    public static @NotNull ProbeOutput parse(@NotNull String rawOutput) {
        Objects.requireNonNull(rawOutput, "rawOutput must not be null");
        var payloadsByCase = new LinkedHashMap<String, String>();
        var lineNumber = 0;
        for (var line : rawOutput.lines().toList()) {
            lineNumber++;
            if (!line.startsWith(PREFIX)) {
                continue;
            }
            var segments = line.split("\\|", -1);
            if (segments.length != 3 || segments[1].isEmpty() || segments[2].isEmpty()) {
                throw new IllegalArgumentException(
                        "Malformed PROBE line " + lineNumber + " (expected PROBE|<CASE>|<payload>): " + line);
            }
            var caseName = segments[1];
            if (!caseName.matches("[A-Z0-9_]+")) {
                throw new IllegalArgumentException(
                        "Malformed PROBE case name at line " + lineNumber + " (expected [A-Z0-9_]+): " + line);
            }
            if (payloadsByCase.putIfAbsent(caseName, segments[2]) != null) {
                throw new IllegalArgumentException(
                        "Duplicate PROBE case " + caseName + " at line " + lineNumber);
            }
        }
        return new ProbeOutput(payloadsByCase);
    }

    /// Case names in emission order.
    public @NotNull List<String> caseNames() {
        return List.copyOf(payloadsByCase.keySet());
    }

    public boolean hasCase(@NotNull String caseName) {
        return payloadsByCase.containsKey(Objects.requireNonNull(caseName, "caseName must not be null"));
    }

    /// Returns the payload for `caseName`, throwing `IllegalArgumentException` when absent.
    public @NotNull String requirePayload(@NotNull String caseName) {
        var payload = payloadsByCase.get(Objects.requireNonNull(caseName, "caseName must not be null"));
        if (payload == null) {
            throw new IllegalArgumentException("Missing PROBE case " + caseName + " (present: " + payloadsByCase.keySet() + ")");
        }
        return payload;
    }

    /// Full `PROBE|<CASE>|<payload>` lines in emission order, for diagnostics and transcripts.
    public @NotNull List<String> probeLines() {
        return payloadsByCase.entrySet().stream()
                .map(entry -> PREFIX + entry.getKey() + "|" + entry.getValue())
                .toList();
    }
}
