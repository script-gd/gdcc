package gd.script.gdcc.backend.c.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdObjectType;

/// Immutable `@onready` session plan for the module's classes, frozen before default-value
/// synthesis backfills `initFunc` (a source initializer requires a non-null, unmarked
/// `initFunc`, so later sessions over the same module objects keep source and synthesized
/// defaults apart). Membership, chain/descendant facts and the resolved `_ready` owner are
/// frozen; the referenced LIR property/function objects stay shared, not copied. A superclass
/// edge requires the super name both registered as GDCC and present in the module list;
/// cyclic ancestry fails fast.
public final class COnreadyPlan {
    /// Frozen per-class onready facts.
    ///
    /// @param onreadyInitializers own non-static `onready` properties with a source
    ///        initializer, in declaration order
    /// @param chainHasOnreadyInitializer the class or a same-module GDCC ancestor contributes
    ///        one
    /// @param classOrDescendantHasOnreadyInitializer the own chain fact or a same-module
    ///        descendant's chain fact holds
    /// @param userReadyOwnerClassName canonical name of the class declaring the chain's
    ///        nearest valid user `_ready`, or null
    /// @param userReadyFunction that `_ready` function object, or null
    public record ClassPlan(
            @NotNull List<LirPropertyDef> onreadyInitializers,
            boolean chainHasOnreadyInitializer,
            boolean classOrDescendantHasOnreadyInitializer,
            @Nullable String userReadyOwnerClassName,
            @Nullable LirFunctionDef userReadyFunction
    ) {
        public ClassPlan {
            onreadyInitializers = List.copyOf(
                    Objects.requireNonNull(onreadyInitializers, "onreadyInitializers")
            );
            if ((userReadyOwnerClassName == null) != (userReadyFunction == null)) {
                throw new IllegalArgumentException(
                        "userReadyOwnerClassName and userReadyFunction must be both present or both absent");
            }
        }
    }

    /// One physical root's class-lookup rows: every same-module class whose frozen chain
    /// fact is true and that shares the wrapper-embedding root.
    ///
    /// @param rootClassName canonical name of the physical GDCC wrapper root
    /// @param classNames canonical names of the member rows in module order
    public record RootLookupPlan(@NotNull String rootClassName, @NotNull List<String> classNames) {
        public RootLookupPlan {
            Objects.requireNonNull(rootClassName, "rootClassName");
            classNames = List.copyOf(Objects.requireNonNull(classNames, "classNames"));
        }
    }

    private static final ClassPlan EMPTY_CLASS_PLAN =
            new ClassPlan(List.of(), false, false, null, null);

    private final @NotNull Map<String, ClassPlan> planByClassName;
    private final @NotNull List<RootLookupPlan> rootLookupPlans;

    /// The factory transfers its privately built collections; no mutable alias escapes, so
    /// unmodifiable wrappers are sufficient without copying.
    private COnreadyPlan(
            @NotNull Map<String, ClassPlan> planByClassName,
            @NotNull List<RootLookupPlan> rootLookupPlans
    ) {
        this.planByClassName = Collections.unmodifiableMap(planByClassName);
        this.rootLookupPlans = rootLookupPlans;
    }

    /// Per-class facts keyed by canonical class name; unknown names get the immutable empty
    /// plan, matching the backend's default-answer convention for out-of-module classes.
    public @NotNull ClassPlan classPlan(@NotNull String className) {
        var plan = planByClassName.get(className);
        return plan == null ? EMPTY_CLASS_PLAN : plan;
    }

    /// Lookup rows per physical root in first-seen module order; empty when no class in the
    /// module carries a true chain fact.
    public @NotNull List<RootLookupPlan> rootLookupPlans() {
        return rootLookupPlans;
    }

    /// Builds the frozen plan for one helper session: the name-keyed fact table plus the
    /// physical-root lookup rows, all resolved directly over the authoritative module
    /// class list and the registry.
    static @NotNull COnreadyPlan create(
            @NotNull CGenHelper helper,
            @NotNull List<? extends ClassDef> moduleClassDefs
    ) {
        Objects.requireNonNull(helper, "helper");
        Objects.requireNonNull(moduleClassDefs, "moduleClassDefs");
        var registry = helper.context().classRegistry();

        // Pass 1: each LIR class's own ordered source-initializer onready list.
        var plans = new HashMap<String, ClassPlan>();
        for (var classDef : moduleClassDefs) {
            if (!(classDef instanceof LirClassDef lirClassDef)) {
                continue;
            }
            var initializers = new ArrayList<LirPropertyDef>();
            for (var property : lirClassDef.getProperties()) {
                if (!property.isStatic()
                        && helper.checkOnreadyProperty(property)
                        && property.getInitFunc() != null
                        && !property.isInitializerSynthesizedByBackend()) {
                    initializers.add(property);
                }
            }
            plans.put(lirClassDef.getName(), new ClassPlan(initializers, false, false, null, null));
        }

        // Pass 2: chain facts + nearest valid `_ready` per class. A chain carrying onready
        // initializers contributes that fact to every member (itself plus same-module
        // ancestors), so collecting member names yields the own-or-descendant fact.
        var onreadyChainMembers = new HashSet<String>();
        for (var classDef : moduleClassDefs) {
            if (!(classDef instanceof LirClassDef lirClassDef)) {
                continue;
            }
            var chain = collectModuleChain(moduleClassDefs, registry, lirClassDef);
            var chainHas = chain.stream().anyMatch(
                    member -> !plans.get(member.getName()).onreadyInitializers().isEmpty());
            if (chainHas) {
                for (var member : chain) {
                    onreadyChainMembers.add(member.getName());
                }
            }
            var existing = plans.get(lirClassDef.getName());
            plans.put(lirClassDef.getName(), createClassPlan(
                    helper, registry, chain, existing.onreadyInitializers(), chainHas));
        }

        // Descendant facts are only complete once every chain is known; fold the marked
        // member set into the finished plans.
        plans.replaceAll((name, plan) -> new ClassPlan(
                plan.onreadyInitializers(),
                plan.chainHasOnreadyInitializer(),
                onreadyChainMembers.contains(name),
                plan.userReadyOwnerClassName(),
                plan.userReadyFunction()
        ));

        // Pass 3: physical-root lookup plans. Module classes with a true chain fact are
        // grouped by their wrapper-embedding root (object-start walk, so a module class
        // that is not itself registered still resolves); rows stay in module order.
        var rowsByRoot = new LinkedHashMap<String, List<String>>();
        for (var classDef : moduleClassDefs) {
            if (!(classDef instanceof LirClassDef lirClassDef)
                    || !plans.get(lirClassDef.getName()).chainHasOnreadyInitializer()) {
                continue;
            }
            rowsByRoot.computeIfAbsent(
                    helper.resolveGdccRootClassName(lirClassDef), key -> new ArrayList<>()
            ).add(lirClassDef.getName());
        }
        var rootLookupPlans = rowsByRoot.entrySet().stream()
                .map(entry -> new RootLookupPlan(entry.getKey(), entry.getValue()))
                .toList();
        return new COnreadyPlan(plans, rootLookupPlans);
    }

    /// Same-module ancestry leaf-to-root, starting with `classDef` itself; the walk stops
    /// before a parent that is blank, unregistered as GDCC, or absent from the module list.
    /// The visited set is the cycle defense for a class graph mutated after helper
    /// construction (the plan may build lazily).
    private static @NotNull List<LirClassDef> collectModuleChain(
            @NotNull List<? extends ClassDef> moduleClassDefs,
            @NotNull ClassRegistry registry,
            @NotNull LirClassDef classDef
    ) {
        var chain = new ArrayList<LirClassDef>();
        var visited = new HashSet<String>();
        var current = classDef;
        while (current != null) {
            if (!visited.add(current.getName())) {
                throw new IllegalStateException(
                        "Detected GDCC inheritance cycle involving class '" + classDef.getName() + "'");
            }
            chain.add(current);
            var superName = current.getSuperName();
            current = superName.isEmpty() || !registry.isGdccClass(superName)
                    ? null
                    : findModuleLirClass(moduleClassDefs, superName);
        }
        return chain;
    }

    /// Linear module-list membership lookup: the module list, not the registry, decides
    /// whether a same-named class participates in this session's chains.
    private static @Nullable LirClassDef findModuleLirClass(
            @NotNull List<? extends ClassDef> moduleClassDefs,
            @NotNull String name
    ) {
        for (var candidate : moduleClassDefs) {
            if (candidate instanceof LirClassDef lirClassDef && lirClassDef.getName().equals(name)) {
                return lirClassDef;
            }
        }
        return null;
    }

    /// One class's finished facts minus the descendant flag: the own initializer list and
    /// chain fact given, plus the nearest `_ready` on the chain (leaf to root) that satisfies
    /// the engine virtual signature check; failing declarations are skipped and the walk
    /// continues upward.
    private static @NotNull ClassPlan createClassPlan(
            @NotNull CGenHelper helper,
            @NotNull ClassRegistry registry,
            @NotNull List<LirClassDef> chain,
            @NotNull List<LirPropertyDef> onreadyInitializers,
            boolean chainHas
    ) {
        for (var ownerDef : chain) {
            for (var function : ownerDef.getFunctions()) {
                if (!"_ready".equals(function.getName())) {
                    continue;
                }
                // checkVirtualMethod walks the owner's registry ancestry without a cycle
                // guard; preflight that walk so a malformed registered-but-outside-module
                // cycle fails fast instead of recursing into a stack overflow.
                requireAcyclicRegistryAncestry(registry, ownerDef.getName());
                if (helper.checkVirtualMethod(ownerDef, function)) {
                    return new ClassPlan(
                            onreadyInitializers, chainHas, false, ownerDef.getName(), function);
                }
            }
        }
        return new ClassPlan(onreadyInitializers, chainHas, false, null, null);
    }

    /// Walks `superName` links under a visited set using the same engine-then-GDCC resolution
    /// `ClassRegistry.findEngineVirtualMethod` performs, so the signature check that follows
    /// can never recurse into a cyclic registry graph.
    private static void requireAcyclicRegistryAncestry(
            @NotNull ClassRegistry registry,
            @NotNull String className
    ) {
        var visited = new HashSet<String>();
        String current = className;
        while (current != null && !current.isEmpty()) {
            if (!visited.add(current)) {
                throw new IllegalStateException(
                        "Detected cyclic class ancestry involving '" + className + "'");
            }
            var classDef = registry.getClassDef(new GdObjectType(current));
            current = classDef == null ? null : classDef.getSuperName();
        }
    }
}
