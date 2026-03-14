package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.scope.SignalDef;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/// Shared receiver-based signal metadata resolver.
///
/// Responsibilities deliberately mirror the existing shared member resolvers while keeping signal
/// semantics isolated from property/method lookup:
/// - known object receivers resolve through class metadata + inheritance walk
/// - builtin receivers are classified as confirmed non-signal paths using builtin class metadata
/// - metadata-missing object receivers surface `MetadataUnknown`
/// - malformed metadata and confirmed misses surface structured `Failed`
///
/// The resolver only answers metadata facts: "is this member a signal, who owns it, and what is
/// its signature?" It does not perform property fallback, `.emit(...)` overload matching, or any
/// runtime signal behavior decisions.
public final class ScopeSignalResolver {
    private ScopeSignalResolver() {
    }

    /// Stable failure categories exposed to frontend/backend callers.
    public enum FailureKind {
        SIGNAL_MISSING,
        MISSING_SUPER_METADATA,
        INHERITANCE_CYCLE,
        UNSUPPORTED_OWNER,
        UNSUPPORTED_RECEIVER_KIND
    }

    /// Common result surface for instance-style signal lookup.
    public sealed interface Result permits ObjectResult {
    }

    /// Object-receiver resolution result.
    public sealed interface ObjectResult extends Result permits Resolved, MetadataUnknown, Failed {
    }

    /// Successful signal lookup.
    public record Resolved(@NotNull ScopeResolvedSignal signal) implements ObjectResult {
        public Resolved {
            Objects.requireNonNull(signal, "signal");
        }
    }

    /// Receiver type is object-like, but there is no class metadata for it yet.
    ///
    /// This remains intentionally distinct from `SIGNAL_MISSING`: callers may still choose a
    /// deferred or dynamic path when script metadata has not been loaded yet.
    public record MetadataUnknown(@NotNull GdObjectType receiverType,
                                  @NotNull String signalName) implements ObjectResult {
        public MetadataUnknown {
            Objects.requireNonNull(receiverType, "receiverType");
            Objects.requireNonNull(signalName, "signalName");
        }
    }

    /// Structured failure used to preserve policy boundaries between shared lookup and callers.
    ///
    /// The neutral resolver reports *what* failed and enough context to rebuild caller-specific
    /// diagnostics, but it deliberately does not decide whether the caller should raise an error,
    /// defer to a dynamic path, or surface a recoverable diagnostic.
    public record Failed(@NotNull FailureKind kind,
                         @NotNull GdType receiverType,
                         @NotNull String signalName,
                         @Nullable String ownerClassName,
                         @Nullable String relatedClassName,
                         @NotNull List<String> hierarchy) implements ObjectResult {
        public Failed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(receiverType, "receiverType");
            Objects.requireNonNull(signalName, "signalName");
            hierarchy = List.copyOf(hierarchy);
        }
    }

    /// Resolve a signal from an arbitrary instance receiver type.
    ///
    /// Dispatch policy is intentionally shallow and explicit:
    /// - known object receivers delegate to `resolveObjectSignal(...)`
    /// - all remaining receiver kinds stop immediately with a structured resolution failure
    /// - `Variant` still uses the same unsupported-receiver failure, but we keep the explicit branch
    ///   to document that the resolver must not guess dynamic signals
    public static @NotNull Result resolveInstanceSignal(@NotNull ClassRegistry registry,
                                                        @NotNull GdType receiverType,
                                                        @NotNull String signalName) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(receiverType, "receiverType");
        Objects.requireNonNull(signalName, "signalName");

        if (receiverType instanceof GdObjectType objectType) {
            return resolveObjectSignal(registry, objectType, signalName);
        }
        if (receiverType instanceof GdVariantType) {
            return new Failed(
                    FailureKind.UNSUPPORTED_RECEIVER_KIND,
                    receiverType,
                    signalName,
                    null,
                    null,
                    List.of()
            );
        }
        return new Failed(
                FailureKind.UNSUPPORTED_RECEIVER_KIND,
                receiverType,
                signalName,
                null,
                null,
                List.of()
        );
    }

    /// Resolve signal metadata for a known object receiver.
    ///
    /// Result policy matches the signal S2 plan:
    /// - missing root metadata -> `MetadataUnknown`
    /// - malformed hierarchy metadata -> `Failed`
    /// - signal absent in a known hierarchy -> `Failed(SIGNAL_MISSING)`
    public static @NotNull ObjectResult resolveObjectSignal(@NotNull ClassRegistry registry,
                                                            @NotNull GdObjectType receiverType,
                                                            @NotNull String signalName) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(receiverType, "receiverType");
        Objects.requireNonNull(signalName, "signalName");

        var classDef = registry.getClassDef(receiverType);
        if (classDef == null) {
            return new MetadataUnknown(receiverType, signalName);
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
                        signalName,
                        current.getName(),
                        null,
                        hierarchyNames
                );
            }

            var signalDef = findOwnSignalDef(current, signalName);
            if (signalDef != null) {
                var ownerKind = resolveOwnerKind(registry, current.getName());
                if (ownerKind == null || ownerKind == ScopeOwnerKind.BUILTIN) {
                    return new Failed(
                            FailureKind.UNSUPPORTED_OWNER,
                            receiverType,
                            signalName,
                            current.getName(),
                            null,
                            hierarchyNames
                    );
                }
                return new Resolved(new ScopeResolvedSignal(ownerKind, current, signalDef));
            }

            var superCanonicalName = current.getSuperName();
            if (superCanonicalName.isBlank()) {
                break;
            }
            var superClassDef = registry.getClassDef(new GdObjectType(superCanonicalName));
            if (superClassDef == null) {
                return new Failed(
                        FailureKind.MISSING_SUPER_METADATA,
                        receiverType,
                        signalName,
                        current.getName(),
                        superCanonicalName,
                        hierarchyNames
                );
            }
            current = superClassDef;
        }
        return new Failed(
                FailureKind.SIGNAL_MISSING,
                receiverType,
                signalName,
                classDef.getName(),
                null,
                hierarchyNames
        );
    }

    private static @Nullable SignalDef findOwnSignalDef(@NotNull ClassDef classDef,
                                                        @NotNull String signalName) {
        for (var signal : classDef.getSignals()) {
            if (signal.getName().equals(signalName)) {
                return signal;
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
