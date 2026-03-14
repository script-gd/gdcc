package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.exception.ScopeLookupException;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeLookupResult;
import dev.superice.gdcc.scope.ScopeValue;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.scope.SignalDef;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdSignalType;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Frontend lexical scope for a class body and the unqualified member view seen from inside that class.
///
/// This scope carries three intertwined policies:
/// - lexical chaining for type/meta lookup
/// - current/inherited class-member lookup for values/functions
/// - minimal static-vs-instance restrictions for unqualified member access
///
/// Important boundaries:
/// - type/meta lookup stays purely lexical, so outer classes may still contribute inner types/enums
/// - value/function lookup walks the current class plus inheritance, but skips continuous outer
///   `ClassScope` ancestors when it recurses lexically
/// - restriction blocks still shadow outer/global names, matching Godot's static-context behavior
///
/// This means GDCC intentionally diverges from Godot on one axis:
/// - inner classes keep outer lexical type-meta visibility
/// - inner classes do not inherit outer unqualified value/function bindings
///
/// The difference is deliberate and treated as an engineering compromise.
public final class ClassScope extends AbstractFrontendScope {
    private final ClassRegistry classRegistry;
    private final ClassDef currentClass;

    /// Direct value-side bindings declared on the current class layer only.
    ///
    /// What lives here:
    /// - direct properties declared by `currentClass`
    /// - direct signals declared by `currentClass`
    /// - class constants explicitly registered through `defineConstant(...)`
    ///
    /// What intentionally does **not** live here:
    /// - inherited properties from super classes
    /// - global names from `ClassRegistry`
    /// - type/meta bindings such as inner classes or class enums
    ///
    /// Why this table exists separately:
    /// - direct class members must win before we even consider inherited members
    /// - inherited property lookup should only run on miss, so we keep the current layer cached
    ///   independently and make the lookup order explicit
    /// - nested-class/type declarations must not be flattened into value lookup, so they stay in the
    ///   dedicated type-meta namespace provided by `AbstractFrontendScope`
    private final Map<String, ScopeValue> directValuesByName = new LinkedHashMap<>();

    /// Direct function overload sets declared on the current class layer only.
    ///
    /// The key is the source-level method name, and the value is the overload set owned by the
    /// current class for that name.
    ///
    /// This table does not include inherited overloads. That separation is important because class
    /// member lookup follows the same "nearest non-empty layer wins" rule as lexical function lookup:
    /// - if the current class declares one or more overloads named `foo`, those direct candidates are
    ///   the only set returned from this class layer
    /// - inherited overloads are consulted only when the current class contributes no direct `foo`
    ///
    /// Keeping direct overloads isolated also makes it possible to represent nested classes cleanly:
    /// inner-class declarations belong to type/meta lookup, while current-class methods stay in this
    /// value-adjacent member table and do not get conflated with type declarations.
    private final Map<String, List<FunctionDef>> directFunctionsByName = new LinkedHashMap<>();

    public ClassScope(
            @NotNull Scope parentScope,
            @NotNull ClassRegistry classRegistry,
            @NotNull ClassDef currentClass
    ) {
        super(Objects.requireNonNull(parentScope, "parentScope"));
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry");
        this.currentClass = Objects.requireNonNull(currentClass, "currentClass");
        indexDirectMembers(currentClass);
    }

    /// Returns the class whose lexical/member view this scope represents.
    public @NotNull ClassDef getCurrentClass() {
        return currentClass;
    }

    /// Registers a direct property belonging to the current class scope.
    ///
    /// The registration is local to the current class layer only. Outer lexical scopes remain on the
    /// parent chain, and inherited properties are still resolved separately when direct lookup misses.
    public void defineProperty(@NotNull PropertyDef property) {
        Objects.requireNonNull(property, "property");
        defineDirectValue(toPropertyScopeValue(property));
    }

    /// Registers a direct signal belonging to the current class scope.
    ///
    /// Signals live in the value namespace on purpose: unqualified `my_signal` should be resolved by
    /// `resolveValue(...)`, participate in current-class shadowing, and surface a value-side
    /// `GdSignalType` instead of pretending to be a function overload set.
    public void defineSignal(@NotNull SignalDef signal) {
        Objects.requireNonNull(signal, "signal");
        defineDirectValue(toSignalScopeValue(signal));
    }

    /// Registers a direct class constant.
    ///
    /// GDScript constants are modeled as class-member bindings here, so they participate in the
    /// same lexical priority slot as direct properties and shadow inherited properties/globals.
    public void defineConstant(
            @NotNull String name,
            @NotNull GdType type,
            @Nullable Object declaration
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        defineDirectValue(new ScopeValue(name, type, ScopeValueKind.CONSTANT, declaration, true, true));
    }

    /// Registers a direct method owned by the current class.
    ///
    /// Methods keep overload sets per class layer. A same-name direct method extends the current
    /// class overload set, while inherited overloads are only consulted when the current class has no
    /// direct candidates with the same name.
    public void defineFunction(@NotNull FunctionDef function) {
        Objects.requireNonNull(function, "function");
        directFunctionsByName.computeIfAbsent(function.getName(), _ -> new ArrayList<>()).add(function);
    }

    @Override
    public @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");

        var directValue = directValuesByName.get(name);
        if (directValue != null) {
            return toClassValueResult(directValue, restriction);
        }

        // Inherited members are part of the class scope layer, not separate lexical parents.
        //
        // This mirrors Godot's identifier reduction order: locals first, then current/inherited
        // members, then global names. We keep type/meta lookup separate from this inheritance walk.
        return resolveInheritedValueMember(name, restriction);
    }

    @Override
    public @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctionsHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");

        var directFunctions = directFunctionsByName.get(name);
        if (directFunctions != null && !directFunctions.isEmpty()) {
            return toFunctionLookupResult(List.copyOf(directFunctions), restriction);
        }
        return resolveInheritedFunctions(name, restriction);
    }

    @Override
    public @NotNull ScopeLookupResult<ScopeValue> resolveValue(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");
        var value = resolveValueHere(name, restriction);
        if (value.isFound()) {
            return value;
        }
        var parentScope = findFirstNonClassScopeAncestor();
        return parentScope != null ? parentScope.resolveValue(name, restriction) : ScopeLookupResult.notFound();
    }

    @Override
    public @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctions(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");
        var functions = resolveFunctionsHere(name, restriction);
        if (functions.isFound()) {
            return functions;
        }
        var parentScope = findFirstNonClassScopeAncestor();
        return parentScope != null ? parentScope.resolveFunctions(name, restriction) : ScopeLookupResult.notFound();
    }

    private void indexDirectMembers(@NotNull ClassDef classDef) {
        for (var property : classDef.getProperties()) {
            defineDirectValue(toPropertyScopeValue(property));
        }
        for (var signal : classDef.getSignals()) {
            defineSignal(signal);
        }
        for (var function : classDef.getFunctions()) {
            defineFunction(function);
        }
    }

    private void defineDirectValue(@NotNull ScopeValue value) {
        Objects.requireNonNull(value, "value");
        var previous = directValuesByName.putIfAbsent(value.name(), value);
        if (previous != null) {
            throw duplicateNamespaceBinding("class value", value.name());
        }
    }

    private @NotNull ScopeLookupResult<ScopeValue> resolveInheritedValueMember(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        var inheritedClasses = walkInheritedClasses(name, "value member");
        for (var inheritedClass : inheritedClasses) {
            for (var property : inheritedClass.getProperties()) {
                if (property.getName().equals(name)) {
                    return toClassValueResult(toPropertyScopeValue(property), restriction);
                }
            }
            for (var signal : inheritedClass.getSignals()) {
                if (signal.getName().equals(name)) {
                    return toClassValueResult(toSignalScopeValue(signal), restriction);
                }
            }
        }
        return ScopeLookupResult.notFound();
    }

    private @NotNull ScopeLookupResult<List<FunctionDef>> resolveInheritedFunctions(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        var inheritedClasses = walkInheritedClasses(name, "function");
        for (var inheritedClass : inheritedClasses) {
            var functions = inheritedClass.getFunctions().stream()
                    .filter(function -> function.getName().equals(name))
                    .map(function -> (FunctionDef) function)
                    .toList();
            if (!functions.isEmpty()) {
                return toFunctionLookupResult(functions, restriction);
            }
        }
        return ScopeLookupResult.notFound();
    }

    /// Walks the inheritance chain for member lookup.
    ///
    /// The walk stops when metadata becomes unavailable because the scope layer itself should remain
    /// a lightweight metadata adapter. The caller can still diagnose missing superclass metadata in a
    /// richer semantic analysis layer. Cycles, however, indicate malformed metadata and are rejected eagerly.
    private @NotNull List<ClassDef> walkInheritedClasses(
            @NotNull String memberName,
            @NotNull String memberKind
    ) {
        var inheritedClasses = new ArrayList<ClassDef>();
        var visitedClassNames = new LinkedHashSet<String>();
        var nextSuperCanonicalName = currentClass.getSuperName();
        while (!nextSuperCanonicalName.isBlank()) {
            if (!visitedClassNames.add(nextSuperCanonicalName)) {
                throw new ScopeLookupException(
                        "Detected inheritance cycle while resolving " + memberKind + " '" + memberName
                                + "' for class '" + currentClass.getName() + "'"
                );
            }

            // `ClassDef#getSuperName()` is canonical, so the class registry can use it directly as
            // the inheritance-walk lookup key even for inner classes.
            var nextClass = classRegistry.getClassDef(new GdObjectType(nextSuperCanonicalName));
            if (nextClass == null) {
                break;
            }
            inheritedClasses.add(nextClass);
            nextSuperCanonicalName = nextClass.getSuperName();
        }
        return inheritedClasses;
    }

    private @NotNull ScopeValue toPropertyScopeValue(@NotNull PropertyDef property) {
        return new ScopeValue(
                property.getName(),
                property.getType(),
                ScopeValueKind.PROPERTY,
                property,
                false,
                property.isStatic()
        );
    }

    /// Converts signal metadata into the value-side binding shape expected by the scope protocol.
    ///
    /// Signals are treated as read-only instance members for the current S1 scope phase:
    /// - `kind = SIGNAL` anchors the semantic category explicitly
    /// - `type = GdSignalType` carries the stable parameter signature
    /// - `declaration = SignalDef` lets later frontend stages recover owner-specific metadata
    private @NotNull ScopeValue toSignalScopeValue(@NotNull SignalDef signal) {
        return new ScopeValue(
                signal.getName(),
                GdSignalType.from(signal),
                ScopeValueKind.SIGNAL,
                signal,
                true,
                false
        );
    }

    /// Finds the lexical continuation point for value/function lookup.
    ///
    /// The scope model keeps a single parent chain for all namespaces, so inner-class isolation is
    /// implemented by explicitly skipping continuous `ClassScope` ancestors here instead of changing
    /// the shared parent pointer itself. Type/meta lookup continues to use the ordinary parent chain.
    private @Nullable Scope findFirstNonClassScopeAncestor() {
        var parentScope = getParentScope();
        while (parentScope instanceof ClassScope classScope) {
            parentScope = classScope.getParentScope();
        }
        return parentScope;
    }

    /// Applies the current restriction to a class-owned value binding.
    ///
    /// A blocked hit still returns `FOUND_BLOCKED` instead of `NOT_FOUND`, because the current class
    /// member must continue to shadow outer/global names even when the current context cannot legally
    /// consume it.
    private @NotNull ScopeLookupResult<ScopeValue> toClassValueResult(
            @NotNull ScopeValue value,
            @NotNull ResolveRestriction restriction
    ) {
        return isClassValueAllowed(value, restriction)
                ? ScopeLookupResult.foundAllowed(value)
                : ScopeLookupResult.foundBlocked(value);
    }

    /// Applies the current restriction to a same-name overload set owned by one class layer.
    ///
    /// The result must distinguish three cases:
    /// - some overloads remain legal -> `FOUND_ALLOWED`
    /// - overloads exist but all are illegal -> `FOUND_BLOCKED`
    /// - no overloads at this layer -> `NOT_FOUND`
    private @NotNull ScopeLookupResult<List<FunctionDef>> toFunctionLookupResult(
            @NotNull List<FunctionDef> functions,
            @NotNull ResolveRestriction restriction
    ) {
        if (functions.isEmpty()) {
            return ScopeLookupResult.notFound();
        }
        var allowedFunctions = functions.stream()
                .filter(function -> isFunctionAllowed(function, restriction))
                .toList();
        return !allowedFunctions.isEmpty()
                ? ScopeLookupResult.foundAllowed(allowedFunctions)
                : ScopeLookupResult.foundBlocked(List.copyOf(functions));
    }

    private boolean isClassValueAllowed(@NotNull ScopeValue value, @NotNull ResolveRestriction restriction) {
        return switch (value.kind()) {
            case CONSTANT -> restriction.allowClassConstants();
            case PROPERTY, SIGNAL -> value.staticMember()
                    ? restriction.allowStaticProperties()
                    : restriction.allowInstanceProperties();
            default -> true;
        };
    }

    private boolean isFunctionAllowed(@NotNull FunctionDef function, @NotNull ResolveRestriction restriction) {
        return function.isStatic() ? restriction.allowStaticMethods() : restriction.allowInstanceMethods();
    }
}
