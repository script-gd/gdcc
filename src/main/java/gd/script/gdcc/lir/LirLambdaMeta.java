package gd.script.gdcc.lir;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Lambda-only hot-reload identity metadata of a `LirFunctionDef`. Serialized as the
/// `<meta>` child element of `<function>`; absent `<meta>` ⇔ `null` meta on the function.
///
/// @param sourceIdentityKey stable source identity for HRX rebinding:
///                          `<Class>::<enclosingFunc>#<ordinal>`, derived by the frontend from
///                          `FrontendLambdaPlan.sourceIdentityKey()`; the C backend's rebind-table
///                          emission refuses keyless lambdas.
/// @param callSiteContext   normalized call-site context descriptor used during rebinding
///                          (after impl_key and schema_desc), derived from
///                          `FrontendLambdaPlan.callSiteContext()`; `null` means "no context
///                          recorded" (hand-built LIR fixtures) and the runtime treats NULL-safe
///                          equality as the rule (standalone Callable identities always carry
///                          NULL).
public record LirLambdaMeta(@NotNull String sourceIdentityKey, @Nullable String callSiteContext) {
    public LirLambdaMeta {
        Objects.requireNonNull(sourceIdentityKey, "sourceIdentityKey must not be null");
        if (sourceIdentityKey.isBlank()) {
            throw new IllegalArgumentException("sourceIdentityKey must not be blank");
        }
        if (callSiteContext != null && callSiteContext.isBlank()) {
            throw new IllegalArgumentException("callSiteContext must be null or non-blank");
        }
    }
}
