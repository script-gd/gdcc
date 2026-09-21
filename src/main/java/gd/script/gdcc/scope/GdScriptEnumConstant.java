package gd.script.gdcc.scope;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// One evaluated script enum member constant.
///
/// GDCC models GDScript enums as compile-time constant bindings instead of a dedicated runtime
/// type: each member resolves to a 64-bit int value frozen once at skeleton time. Members of an
/// anonymous enum carry `groupName == null`; members of a named enum group carry the group name so
/// consumers can recover which `GdScriptEnumGroup` they belong to.
///
/// - `memberName`: source-level member identifier.
/// - `value`: evaluated constant value (auto-incremented from the previous member when the source
///   omits an initializer; the first member defaults to 0).
/// - `groupName`: owning named enum group name, or null for anonymous enum members.
/// - `ownerClassCanonicalName`: canonical name of the GDCC class that declares the enum.
public record GdScriptEnumConstant(
        @NotNull String memberName,
        long value,
        @Nullable String groupName,
        @NotNull String ownerClassCanonicalName
) {
    public GdScriptEnumConstant {
        Objects.requireNonNull(memberName, "memberName must not be null");
        Objects.requireNonNull(ownerClassCanonicalName, "ownerClassCanonicalName must not be null");
    }
}
