package gd.script.gdcc.api.analysis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Result of a `definitionAt` lookup (plan §2.4 declaration-source normalization).
///
/// Published `declarationSite` payloads are not guaranteed to be ranged AST nodes — they can be
/// declaration model objects (`PropertyDef`, overload `List<? extends FunctionDef>`, synthetic
/// constructors) or external engine/builtin metadata. The lookup therefore reports one of four
/// kinds instead of a bare range:
///
/// - `SINGLE_SOURCE`: exactly one source declaration was normalized; `candidates` holds one
///   entry whose location is non-null.
/// - `MULTIPLE_CANDIDATES`: a collection-shaped provenance (e.g. an overload set) normalized
///   element-wise. Every normalizable element contributes its source location; elements that
///   cannot be normalized stay in the list with a `null` location — the lookup never silently
///   picks one element and never drops the remainder.
/// - `EXTERNAL`: the declaration exists but has no source position in this module
///   (engine/builtin metadata, or a collection whose elements all failed to normalize).
/// - `NONE`: no binding fact or no declaration provenance at the cursor (e.g. unbound
///   identifier).
public record DeclarationLookupResult(
        @NotNull Kind kind,
        @NotNull List<Candidate> candidates
) {
    public DeclarationLookupResult {
        Objects.requireNonNull(kind, "kind must not be null");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
        switch (kind) {
            case SINGLE_SOURCE -> {
                if (candidates.size() != 1 || candidates.getFirst().location() == null) {
                    throw new IllegalArgumentException("SINGLE_SOURCE requires exactly one located candidate");
                }
            }
            case MULTIPLE_CANDIDATES -> {
                if (candidates.isEmpty()) {
                    throw new IllegalArgumentException("MULTIPLE_CANDIDATES requires at least one candidate");
                }
            }
            case EXTERNAL, NONE -> {
                if (!candidates.isEmpty()) {
                    throw new IllegalArgumentException(kind + " requires empty candidates");
                }
            }
        }
    }

    public static @NotNull DeclarationLookupResult single(@NotNull QuerySourceRange location) {
        return new DeclarationLookupResult(Kind.SINGLE_SOURCE, List.of(new Candidate(location)));
    }

    public static @NotNull DeclarationLookupResult multiple(@NotNull List<Candidate> candidates) {
        return new DeclarationLookupResult(Kind.MULTIPLE_CANDIDATES, candidates);
    }

    public static @NotNull DeclarationLookupResult external() {
        return new DeclarationLookupResult(Kind.EXTERNAL, List.of());
    }

    public static @NotNull DeclarationLookupResult none() {
        return new DeclarationLookupResult(Kind.NONE, List.of());
    }

    public enum Kind {
        SINGLE_SOURCE,
        MULTIPLE_CANDIDATES,
        EXTERNAL,
        NONE
    }

    /// One normalized declaration candidate. A `null` location explicitly marks "no source
    /// position" inside a `MULTIPLE_CANDIDATES` result.
    public record Candidate(@Nullable QuerySourceRange location) {
    }
}
