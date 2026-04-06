package dev.superice.gdcc.frontend.sema;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Shared frontend contract for compiler-owned synthetic property helper names.
///
/// These prefixes are reserved because later lowering/backend phases materialize hidden helper
/// functions under the same namespace for property init/getter/setter support. Source members that
/// reuse them must be rejected before lowering starts.
public final class FrontendSyntheticPropertyHelperSupport {
    public static final @NotNull String PROPERTY_INIT_PREFIX = "_field_init_";
    public static final @NotNull String PROPERTY_GETTER_PREFIX = "_field_getter_";
    public static final @NotNull String PROPERTY_SETTER_PREFIX = "_field_setter_";
    public static final @NotNull List<String> RESERVED_PREFIXES = List.of(
            PROPERTY_INIT_PREFIX,
            PROPERTY_GETTER_PREFIX,
            PROPERTY_SETTER_PREFIX
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
        return Objects.requireNonNull(memberKind, "memberKind must not be null")
                + " '"
                + Objects.requireNonNull(memberName, "memberName must not be null").trim()
                + "' uses reserved synthetic property-helper prefix '"
                + Objects.requireNonNull(matchedPrefix, "matchedPrefix must not be null")
                + "' and will be skipped; prefixes "
                + String.join(", ", RESERVED_PREFIXES)
                + " are compiler-owned for synthetic property init/getter/setter helpers";
    }
}
