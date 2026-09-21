package gd.script.gdcc.scope;

import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// One entry of the script-source constant table (`ClassDef.getScriptConstants()`).
///
/// The table currently carries script enum facts only: `declaration` is either a
/// `GdScriptEnumConstant` (anonymous enum member, `type` is int) or a `GdScriptEnumGroup`
/// (named enum group, `type` is the generic Dictionary). User-level class `const` stays deferred
/// but is expected to reuse this same table once supported.
public record GdScriptClassConstant(
        @NotNull String name,
        @NotNull GdType type,
        @NotNull Object declaration
) {
    public GdScriptClassConstant {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(declaration, "declaration must not be null");
    }
}
