package gd.script.gdcc.frontend.constant;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// One successfully frozen script `const` declaration published on the class constant table
/// (const plan §4.2).
///
/// The declaration's AST source association is maintained separately through
/// `declarationOrigins` — it is deliberately not embedded in this record.
///
/// @param name               declared source name
/// @param ownerCanonicalName canonical name of the owning script class
/// @param value              frozen payload; the `GdConstantValue` type is the declared type
public record GdScriptEvaluatedConstant(
        @NotNull String name,
        @NotNull String ownerCanonicalName,
        @NotNull GdConstantValue value
) {
    public GdScriptEvaluatedConstant {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(ownerCanonicalName, "ownerCanonicalName must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
