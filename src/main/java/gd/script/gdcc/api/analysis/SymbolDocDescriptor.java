package gd.script.gdcc.api.analysis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Symbol documentation descriptor produced by `documentationAt` (plan §2.4). It is a pure
/// projection — symbol kind + documentation namespace + declaring owner + member name + (for
/// GDCC symbols) every normalized source candidate — because engine metadata carries no
/// documentation text. The editor adapter layer turns it into official documentation URLs or
/// ownership display.
///
/// Contract highlights:
/// - `ownerName` is the ACTUAL declaring class (inheritance walks to the declaring class, never
///   the receiver's static type); for multi-candidate GDCC provenance it is the declaring class
///   of the first located candidate (provenance order = resolution order, i.e. the nearest
///   declaration); it is `null` for globals and locals where no meaningful class owner exists;
/// - `sourceCandidates` mirrors `definitionAt` normalization element by element: every
///   normalizable element contributes its source location; elements that cannot be normalized
///   keep their slot with a `null` location — nothing is silently picked or dropped;
/// - unbound identifiers and `FAILED`/`DEFERRED`/`UNSUPPORTED`/`DYNAMIC`/`BLOCKED` sites
///   (including `FOUND_BLOCKED` value bindings) produce no descriptor at all.
public record SymbolDocDescriptor(
        @NotNull DocSymbolKind symbolKind,
        @NotNull DocNamespace namespace,
        @Nullable String ownerName,
        @NotNull String memberName,
        @NotNull List<SourceCandidate> sourceCandidates
) {
    public SymbolDocDescriptor {
        Objects.requireNonNull(symbolKind, "symbolKind must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(memberName, "memberName must not be null");
        sourceCandidates = List.copyOf(Objects.requireNonNull(sourceCandidates, "sourceCandidates must not be null"));
        var locatedCount = sourceCandidates.stream().filter(candidate -> candidate.location() != null).count();
        if (namespace == DocNamespace.GDCC && locatedCount == 0) {
            throw new IllegalArgumentException("GDCC descriptors must carry at least one located source candidate");
        }
        if (namespace != DocNamespace.GDCC && !sourceCandidates.isEmpty()) {
            throw new IllegalArgumentException("external descriptors must not carry source candidates");
        }
    }

    /// The located source positions in provenance order (drops explicit null markers).
    public @NotNull List<QuerySourceRange> sourceLocations() {
        return sourceCandidates.stream()
                .map(SourceCandidate::location)
                .filter(Objects::nonNull)
                .toList();
    }

    /// One normalized source candidate. A `null` location explicitly marks "no source
    /// position" for that provenance element.
    public record SourceCandidate(@Nullable QuerySourceRange location) {
    }
}
