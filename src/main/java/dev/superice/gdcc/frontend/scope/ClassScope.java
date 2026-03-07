package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeValue;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Frontend lexical scope for a class body and the member view seen from inside that class.
///
/// This scope has two parallel responsibilities:
/// - lexical scope chaining through `parentScope`, typically pointing at `ClassRegistry`
/// - member lookup for unqualified identifiers inside the current class, including inherited members
///
/// The important boundary is that only value/function members walk the inheritance chain here.
/// Type/meta lookup stays lexical on purpose, so a parent class's inner type or class-local enum does
/// not silently leak into the current class just because the current class inherits from it.
///
/// Nested-class shape in the current architecture:
/// - the outer class can register direct inner classes or class-local enums through `defineTypeMeta(...)`
/// - the inner class itself can still be modeled by creating another `ClassScope`
/// - however, `Scope` currently has a single parent chain shared by value/function/type namespaces
///
/// This means nested classes are supported best as **lexical type-meta declarations** in Phase 4.
/// If a future binder wants an inner class body to see outer type-meta names without also inheriting
/// outer value/function bindings, it will need an extra adapter layer instead of reusing the outer
/// `ClassScope` directly as the inner class's parent.
public final class ClassScope extends AbstractFrontendScope {
    private final ClassRegistry classRegistry;
    private final ClassDef currentClass;

    /// Direct value-side bindings declared on the current class layer only.
    ///
    /// What lives here:
    /// - direct properties declared by `currentClass`
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
    public @Nullable ScopeValue resolveValueHere(@NotNull String name) {
        Objects.requireNonNull(name, "name");

        var directValue = directValuesByName.get(name);
        if (directValue != null) {
            return directValue;
        }

        // Inherited members are part of the class scope layer, not separate lexical parents.
        //
        // This mirrors Godot's identifier reduction order: locals first, then current/inherited
        // members, then global names. We keep type/meta lookup separate from this inheritance walk.
        return resolveInheritedProperty(name);
    }

    @Override
    public @NotNull List<? extends FunctionDef> resolveFunctionsHere(@NotNull String name) {
        Objects.requireNonNull(name, "name");

        var directFunctions = directFunctionsByName.get(name);
        if (directFunctions != null && !directFunctions.isEmpty()) {
            return List.copyOf(directFunctions);
        }
        return resolveInheritedFunctions(name);
    }

    private void indexDirectMembers(@NotNull ClassDef classDef) {
        for (var property : classDef.getProperties()) {
            defineDirectValue(toPropertyScopeValue(property));
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

    private @Nullable ScopeValue resolveInheritedProperty(@NotNull String name) {
        var inheritedClasses = walkInheritedClasses(name, "property");
        for (var inheritedClass : inheritedClasses) {
            for (var property : inheritedClass.getProperties()) {
                if (property.getName().equals(name)) {
                    return toPropertyScopeValue(property);
                }
            }
        }
        return null;
    }

    private @NotNull List<? extends FunctionDef> resolveInheritedFunctions(@NotNull String name) {
        var inheritedClasses = walkInheritedClasses(name, "function");
        for (var inheritedClass : inheritedClasses) {
            var functions = inheritedClass.getFunctions().stream()
                    .filter(function -> function.getName().equals(name))
                    .toList();
            if (!functions.isEmpty()) {
                return functions;
            }
        }
        return List.of();
    }

    /// Walks the inheritance chain for member lookup.
    ///
    /// The walk stops when metadata becomes unavailable because the scope layer itself should remain
    /// a lightweight metadata adapter. The caller can still diagnose missing superclass metadata in a
    /// richer semantic phase. Cycles, however, indicate malformed metadata and are rejected eagerly.
    private @NotNull List<ClassDef> walkInheritedClasses(
            @NotNull String memberName,
            @NotNull String memberKind
    ) {
        var inheritedClasses = new ArrayList<ClassDef>();
        var visitedClassNames = new LinkedHashSet<String>();
        var nextClassName = currentClass.getSuperName();
        while (!nextClassName.isBlank()) {
            if (!visitedClassNames.add(nextClassName)) {
                throw new IllegalStateException(
                        "Detected inheritance cycle while resolving " + memberKind + " '" + memberName
                                + "' for class '" + currentClass.getName() + "'"
                );
            }

            var nextClass = classRegistry.getClassDef(new GdObjectType(nextClassName));
            if (nextClass == null) {
                break;
            }
            inheritedClasses.add(nextClass);
            nextClassName = nextClass.getSuperName();
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
}
