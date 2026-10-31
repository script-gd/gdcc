package gd.script.gdcc.frontend.scope;

import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/// Single-layer class-constant probe shared by scope resolution and qualified chain reduction.
///
/// Both consumers must answer three questions per inheritance layer with identical semantics:
/// whether the layer publishes a usable constant, whether it claims the name through a failed
/// class `const` (a terminal blocker that must never fall through to ancestors or globals), or
/// whether the name is free to continue the walk. Centralizing the probe keeps the failed-const
/// mask and the public constant table aligned so neither side can drift on which name "exists".
public final class FrontendClassConstantLookup {
    private FrontendClassConstantLookup() {
    }

    /// One-layer probe result.
    public sealed interface ProbeResult {
        /// A published, usable constant row with this name lives on the probed layer.
        record Usable(@NotNull GdScriptClassConstant constant) implements ProbeResult {
            public Usable {
                Objects.requireNonNull(constant, "constant must not be null");
            }
        }

        /// The name is claimed by a failed class `const` on this layer; lookup ends here.
        record TerminalBlocked(@NotNull FrontendRejectedClassConstant rejected)
                implements ProbeResult {
            public TerminalBlocked {
                Objects.requireNonNull(rejected, "rejected must not be null");
            }
        }

        /// Neither a usable constant nor a failed-const claim exists on this layer.
        record Miss() implements ProbeResult {
        }
    }

    /// Probes `classDef` — and only that layer — for `name`.
    ///
    /// The failed-const mask is consulted before the published constant table: a const that lost
    /// its evaluation keeps its name poisoned even when a sibling declaration was diagnosed in
    /// the same conflict, so callers never resurrect the name through the usable row.
    public static @NotNull ProbeResult probe(
            @NotNull ClassDef classDef,
            @NotNull String name,
            @NotNull Map<String, Map<String, FrontendRejectedClassConstant>> rejectedClassConstants
    ) {
        Objects.requireNonNull(classDef, "classDef must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(rejectedClassConstants, "rejectedClassConstants must not be null");
        var rejected = rejectedClassConstants.getOrDefault(classDef.getName(), Map.of()).get(name);
        if (rejected != null) {
            return new ProbeResult.TerminalBlocked(rejected);
        }
        for (var constant : classDef.getScriptConstants()) {
            if (constant.name().equals(name)) {
                return new ProbeResult.Usable(constant);
            }
        }
        return new ProbeResult.Miss();
    }

    /// Nearest-layer-wins walk over the inheritance chain: each layer probes the failed-const
    /// mask and the usable table, and the first hit ends the walk. A layer that declares any
    /// non-constant member with the same name — function, property, or signal — shadows
    /// ancestor constants for qualified access, mirroring the unified member-namespace rule
    /// the chain reducer applies. `Miss` therefore means "no reachable constant", whether the
    /// walk ran out of layers or a nearer member claimed the name first.
    public static @NotNull ProbeResult probeInHierarchy(
            @NotNull ClassRegistry classRegistry,
            @NotNull ClassDef startClass,
            @NotNull String name,
            @NotNull Map<String, Map<String, FrontendRejectedClassConstant>> rejectedClassConstants
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(startClass, "startClass must not be null");
        var visited = new HashSet<String>();
        for (var current = startClass;
                current != null && visited.add(current.getName());
                current = classRegistry.resolveSuperclass(current)) {
            var result = probe(current, name, rejectedClassConstants);
            if (!(result instanceof ProbeResult.Miss)) {
                return result;
            }
            if (declaresNonConstantMemberOnLayer(current, name)) {
                return new ProbeResult.Miss();
            }
        }
        return new ProbeResult.Miss();
    }

    /// Any non-constant member declared directly on this layer claims the name for qualified
    /// access — an instance member can never be read through `Class.member` anyway, so the
    /// walk must not leak through to an ancestor constant.
    private static boolean declaresNonConstantMemberOnLayer(
            @NotNull ClassDef owner,
            @NotNull String memberName
    ) {
        for (var function : owner.getFunctions()) {
            if (function.getName().equals(memberName)) {
                return true;
            }
        }
        for (var property : owner.getProperties()) {
            if (property.getName().equals(memberName)) {
                return true;
            }
        }
        for (var signal : owner.getSignals()) {
            if (signal.getName().equals(memberName)) {
                return true;
            }
        }
        return false;
    }
}
