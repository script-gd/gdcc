package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/// Shared instance-property metadata resolver.
///
/// The resolver deliberately covers only two runtime receiver families:
/// - known object receivers resolved through class metadata + inheritance walk
/// - builtin receivers resolved through builtin-class metadata
///
/// It does **not** handle `TypeMeta`-based static access such as enum items, builtin constants, or
/// engine integer constants. Those remain on the frontend static-binding + `load_static` path.
public final class ScopePropertyResolver {
    private ScopePropertyResolver() {
    }

    /// Stable failure categories exposed to backend/frontend callers.
    public enum FailureKind {
        INHERITANCE_CYCLE,
        MISSING_SUPER_METADATA,
        PROPERTY_MISSING,
        UNSUPPORTED_OWNER,
        BUILTIN_CLASS_NOT_FOUND,
        BUILTIN_PROPERTY_MISSING
    }

    /// Object-receiver resolution result.
    public sealed interface ObjectResult permits Resolved, MetadataUnknown, Failed {
    }

    /// Builtin-receiver resolution result.
    public sealed interface BuiltinResult permits Resolved, Failed {
    }

    /// Successful property lookup.
    public record Resolved(@NotNull ScopeResolvedProperty property) implements ObjectResult, BuiltinResult {
        public Resolved {
            Objects.requireNonNull(property, "property");
        }
    }

    /// Receiver type is object-like, but there is no class metadata for it yet.
    ///
    /// This is intentionally distinct from `PROPERTY_MISSING`: callers may still choose a dynamic
    /// runtime fallback when metadata is missing entirely.
    public record MetadataUnknown(@NotNull GdObjectType receiverType,
                                  @NotNull String propertyName) implements ObjectResult {
        public MetadataUnknown {
            Objects.requireNonNull(receiverType, "receiverType");
            Objects.requireNonNull(propertyName, "propertyName");
        }
    }

    /// Structured failure used to preserve policy boundaries between shared lookup and callers.
    ///
    /// The neutral resolver reports *what* failed and enough context to rebuild caller-specific
    /// diagnostics, but it does not create backend-only `invalidInsn(...)` exceptions itself.
    public record Failed(@NotNull FailureKind kind,
                         @NotNull GdType receiverType,
                         @NotNull String propertyName,
                         @Nullable String ownerClassName,
                         @Nullable String relatedClassName,
                         @NotNull List<String> hierarchy) implements ObjectResult, BuiltinResult {
        public Failed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(receiverType, "receiverType");
            Objects.requireNonNull(propertyName, "propertyName");
            hierarchy = List.copyOf(hierarchy);
        }
    }

    /// Resolve property metadata for a known object receiver.
    ///
    /// Result policy matches the current backend baseline:
    /// - missing root metadata -> `MetadataUnknown`
    /// - malformed hierarchy metadata -> `Failed`
    /// - property absent in a known hierarchy -> `Failed`
    public static @NotNull ObjectResult resolveObjectProperty(@NotNull ClassRegistry registry,
                                                              @NotNull GdObjectType receiverType,
                                                              @NotNull String propertyName) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(receiverType, "receiverType");
        Objects.requireNonNull(propertyName, "propertyName");

        var classDef = registry.getClassDef(receiverType);
        if (classDef == null) {
            return new MetadataUnknown(receiverType, propertyName);
        }

        ClassDef current = classDef;
        var visited = new LinkedHashSet<String>();
        var hierarchyNames = new ArrayList<String>();
        while (true) {
            hierarchyNames.add(current.getName());
            if (!visited.add(current.getName())) {
                return new Failed(
                        FailureKind.INHERITANCE_CYCLE,
                        receiverType,
                        propertyName,
                        current.getName(),
                        null,
                        hierarchyNames
                );
            }

            var propertyDef = findOwnPropertyDef(current, propertyName);
            if (propertyDef != null) {
                var ownerKind = resolveOwnerKind(registry, current.getName());
                if (ownerKind == null || ownerKind == ScopeOwnerKind.BUILTIN) {
                    return new Failed(
                            FailureKind.UNSUPPORTED_OWNER,
                            receiverType,
                            propertyName,
                            current.getName(),
                            null,
                            hierarchyNames
                    );
                }
                return new Resolved(new ScopeResolvedProperty(ownerKind, current, propertyDef));
            }

            var superName = current.getSuperName();
            if (superName.isBlank()) {
                break;
            }
            var superClassDef = registry.getClassDef(new GdObjectType(superName));
            if (superClassDef == null) {
                return new Failed(
                        FailureKind.MISSING_SUPER_METADATA,
                        receiverType,
                        propertyName,
                        current.getName(),
                        superName,
                        hierarchyNames
                );
            }
            current = superClassDef;
        }
        return new Failed(
                FailureKind.PROPERTY_MISSING,
                receiverType,
                propertyName,
                classDef.getName(),
                null,
                hierarchyNames
        );
    }

    /// Resolve builtin property metadata.
    public static @NotNull BuiltinResult resolveBuiltinProperty(@NotNull ClassRegistry registry,
                                                                @NotNull GdType receiverType,
                                                                @NotNull String propertyName) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(receiverType, "receiverType");
        Objects.requireNonNull(propertyName, "propertyName");

        var builtinClass = registry.findBuiltinClass(receiverType.getTypeName());
        if (builtinClass == null) {
            return new Failed(
                    FailureKind.BUILTIN_CLASS_NOT_FOUND,
                    receiverType,
                    propertyName,
                    receiverType.getTypeName(),
                    null,
                    List.of()
            );
        }
        for (var property : builtinClass.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return new Resolved(new ScopeResolvedProperty(ScopeOwnerKind.BUILTIN, builtinClass, property));
            }
        }
        return new Failed(
                FailureKind.BUILTIN_PROPERTY_MISSING,
                receiverType,
                propertyName,
                builtinClass.getName(),
                null,
                List.of()
        );
    }

    private static @Nullable PropertyDef findOwnPropertyDef(@NotNull ClassDef classDef,
                                                            @NotNull String propertyName) {
        for (var property : classDef.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return property;
            }
        }
        return null;
    }

    private static @Nullable ScopeOwnerKind resolveOwnerKind(@NotNull ClassRegistry registry,
                                                             @NotNull String ownerClassName) {
        if (registry.isGdccClass(ownerClassName)) {
            return ScopeOwnerKind.GDCC;
        }
        if (registry.isGdClass(ownerClassName)) {
            return ScopeOwnerKind.ENGINE;
        }
        if (registry.isBuiltinClass(ownerClassName)) {
            return ScopeOwnerKind.BUILTIN;
        }
        return null;
    }
}
