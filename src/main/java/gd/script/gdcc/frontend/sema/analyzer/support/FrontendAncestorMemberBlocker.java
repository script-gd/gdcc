package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/// Terminal name blocker for declarations visible on a class's inheritance chain.
///
/// Constant initializers run before member filling completes, so ancestor members cannot be
/// probed through the partially filled `ClassDef` tables: script-ancestor names are collected
/// structurally from the source relation graph while the owning class is still being filled, and
/// the first non-module ancestor name (engine/native, or any class outside the module relation
/// map) is probed lazily through `ClassRegistry` metadata per name. The blocker is built once per
/// class and shared by every const/enum initializer evaluated inside that class.
public final class FrontendAncestorMemberBlocker {
    /// Which ancestor surface claimed the name — kept distinct so callers can phrase diagnostics
    /// without re-deriving the hit source.
    public enum HitKind {
        NONE,
        /// Named enum group or member names collected from script ancestors.
        SCRIPT_ENUM,
        /// `var`/`const`/function/`_init`/signal/inner-class names from script ancestors.
        SCRIPT_MEMBER,
        /// Any member, constant, enum value/group, or signal probed on the engine/native chain.
        ENGINE_MEMBER
    }

    private final @NotNull Set<String> scriptEnumNames;
    private final @NotNull Set<String> scriptMemberNames;
    private final @Nullable String engineRootClassName;
    private final @NotNull ClassRegistry classRegistry;

    /// @param engineRootClassName first ancestor name with no module class relation, or null when
    ///                            the whole superclass chain stayed inside the module
    public FrontendAncestorMemberBlocker(
            @NotNull Set<String> scriptEnumNames,
            @NotNull Set<String> scriptMemberNames,
            @Nullable String engineRootClassName,
            @NotNull ClassRegistry classRegistry
    ) {
        this.scriptEnumNames = Set.copyOf(Objects.requireNonNull(scriptEnumNames, "scriptEnumNames must not be null"));
        this.scriptMemberNames = Set.copyOf(Objects.requireNonNull(scriptMemberNames, "scriptMemberNames must not be null"));
        this.engineRootClassName = engineRootClassName;
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
    }

    public @NotNull HitKind check(@NotNull String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (scriptEnumNames.contains(name)) {
            return HitKind.SCRIPT_ENUM;
        }
        if (scriptMemberNames.contains(name)) {
            return HitKind.SCRIPT_MEMBER;
        }
        if (engineRootClassName != null && checkEngineMember(name)) {
            return HitKind.ENGINE_MEMBER;
        }
        return HitKind.NONE;
    }

    /// Engine ancestors never enter the module's script-constant table, so every member category
    /// is probed through registry metadata: the finders walk the whole engine hierarchy for
    /// properties, constants, enum values, and signals, while methods and enum type names are
    /// scanned layer by layer because no shared hierarchy finder exists for them.
    private boolean checkEngineMember(@NotNull String name) {
        if (classRegistry.findPropertyInHierarchy(engineRootClassName, name) != null
                || classRegistry.findEngineClassConstantInHierarchy(engineRootClassName, name) != null
                || classRegistry.findEngineClassEnumValueInHierarchy(engineRootClassName, name) != null
                || classRegistry.findEngineSignalInHierarchy(engineRootClassName, name) != null) {
            return true;
        }
        var visited = new HashSet<String>();
        for (var layer = classRegistry.resolveClassDefByName(engineRootClassName);
                layer != null && visited.add(layer.getName());
                layer = classRegistry.resolveSuperclass(layer)) {
            for (var function : layer.getFunctions()) {
                if (function.getName().equals(name)) {
                    return true;
                }
            }
            if (layer instanceof ExtensionGdClass engineClass) {
                for (var classEnum : engineClass.enums()) {
                    if (classEnum.name().equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
