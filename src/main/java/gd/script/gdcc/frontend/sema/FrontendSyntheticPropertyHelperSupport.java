package gd.script.gdcc.frontend.sema;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Shared frontend contract for compiler-owned synthetic member names.
///
/// These prefixes are reserved because later lowering/backend phases materialize hidden helper
/// functions under the same namespace for property init/getter/setter support and synthesized
/// lambda shells. Source members that reuse them must be rejected before lowering starts.
public final class FrontendSyntheticPropertyHelperSupport {
    public static final @NotNull String PROPERTY_INIT_PREFIX = "_field_init_";
    public static final @NotNull String PROPERTY_GETTER_PREFIX = "_field_getter_";
    public static final @NotNull String PROPERTY_SETTER_PREFIX = "_field_setter_";
    /// Compiler-owned namespace for synthesized lambda shells. Source members reusing it collide
    /// with the hidden `LirFunctionDef` materialized per `LambdaExpression`.
    public static final @NotNull String LAMBDA_FUNCTION_PREFIX = "_lambda_";
    /// Compiler-owned namespace for synthesized parameter-default functions
    /// (`_default_<func>$<param>` for instance functions, `_default_s_<func>$<param>` for static
    /// ones — the static variant sits under the same prefix). Source members reusing it collide
    /// with the hidden `LirFunctionDef` materialized per defaulted `Parameter`.
    public static final @NotNull String PARAMETER_DEFAULT_PREFIX = "_default_";
    public static final @NotNull List<String> RESERVED_PREFIXES = List.of(
            PROPERTY_INIT_PREFIX,
            PROPERTY_GETTER_PREFIX,
            PROPERTY_SETTER_PREFIX,
            LAMBDA_FUNCTION_PREFIX,
            PARAMETER_DEFAULT_PREFIX
    );

    private FrontendSyntheticPropertyHelperSupport() {
    }

    public static @Nullable String reservedPrefixOrNull(@NotNull String memberName) {
        var normalizedName = Objects.requireNonNull(memberName, "memberName must not be null").trim();
        for (var reservedPrefix : RESERVED_PREFIXES) {
            if (normalizedName.startsWith(reservedPrefix)) {
                return reservedPrefix;
            }
        }
        return null;
    }

    public static @NotNull String reservedPrefixDiagnosticMessage(
            @NotNull String memberKind,
            @NotNull String memberName,
            @NotNull String matchedPrefix
    ) {
        var trimmedName = Objects.requireNonNull(memberName, "memberName must not be null").trim();
        var prefix = Objects.requireNonNull(matchedPrefix, "matchedPrefix must not be null");
        if (prefix.equals(LAMBDA_FUNCTION_PREFIX)) {
            return Objects.requireNonNull(memberKind, "memberKind must not be null")
                    + " '"
                    + trimmedName
                    + "' uses reserved synthetic lambda-function prefix '"
                    + prefix
                    + "' and will be skipped; the '"
                    + LAMBDA_FUNCTION_PREFIX
                    + "' prefix is compiler-owned for synthesized lambda functions";
        }
        if (prefix.equals(PARAMETER_DEFAULT_PREFIX)) {
            return Objects.requireNonNull(memberKind, "memberKind must not be null")
                    + " '"
                    + trimmedName
                    + "' uses reserved synthetic parameter-default prefix '"
                    + prefix
                    + "' and will be skipped; the '"
                    + PARAMETER_DEFAULT_PREFIX
                    + "' prefix is compiler-owned for synthesized parameter-default functions";
        }
        return Objects.requireNonNull(memberKind, "memberKind must not be null")
                + " '"
                + trimmedName
                + "' uses reserved synthetic property-helper prefix '"
                + prefix
                + "' and will be skipped; prefixes "
                + String.join(", ", List.of(PROPERTY_INIT_PREFIX, PROPERTY_GETTER_PREFIX, PROPERTY_SETTER_PREFIX))
                + " are compiler-owned for synthetic property init/getter/setter helpers";
    }
}
