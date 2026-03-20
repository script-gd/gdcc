package dev.superice.gdcc.scope;

import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Shared direct-property access helper used by scope publication and frontend assignment typing.
///
/// This helper intentionally models only the current direct-write contract:
/// - engine and builtin metadata honor their explicit writable flag
/// - other property definitions stay conservatively writable until a richer property access model lands
///
/// It does not try to encode receiver-sensitive aliasing or container/property mutation semantics.
public final class PropertyDefAccessSupport {
    private PropertyDefAccessSupport() {
    }

    public static boolean isDirectlyWritable(@NotNull PropertyDef propertyDef) {
        return switch (Objects.requireNonNull(propertyDef, "propertyDef")) {
            case ExtensionBuiltinClass.PropertyInfo builtinProperty -> builtinProperty.isWritable();
            case ExtensionGdClass.PropertyInfo engineProperty -> engineProperty.isWritable();
            default -> true;
        };
    }
}
