package gd.script.gdcc.scope;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// A named enum group declared by a GDCC class.
///
/// The group is the declaration carrier of the enum name itself: in value position the name
/// behaves like a read-only `Dictionary` constant (member name -> int value), and in type position
/// it erases to `int`. Members stay in source order so Dictionary materialization and editor hint
/// rendering remain deterministic.
public record GdScriptEnumGroup(
        @NotNull String name,
        @NotNull List<GdScriptEnumConstant> members,
        @NotNull String ownerClassCanonicalName
) {
    public GdScriptEnumGroup {
        Objects.requireNonNull(name, "name must not be null");
        members = List.copyOf(Objects.requireNonNull(members, "members must not be null"));
        Objects.requireNonNull(ownerClassCanonicalName, "ownerClassCanonicalName must not be null");
    }

    /// Finds one member by source name, or null when the group has no such member.
    public @Nullable GdScriptEnumConstant findMember(@NotNull String memberName) {
        Objects.requireNonNull(memberName, "memberName must not be null");
        for (var member : members) {
            if (member.memberName().equals(memberName)) {
                return member;
            }
        }
        return null;
    }
}
