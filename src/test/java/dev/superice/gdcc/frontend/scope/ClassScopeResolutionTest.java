package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.exception.ScopeLookupException;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ClassScopeResolutionTest {
    @Test
    void currentClassDirectMembersAreExposedImmediately() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var ping = FrontendScopeTestSupport.createFunction("ping", GdStringType.STRING);
        var classDef = FrontendScopeTestSupport.createClass(
                "Hero",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("hp", GdIntType.INT)),
                java.util.List.of(ping)
        );
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);

        var property = classScope.resolveValueHere("hp");
        assertNotNull(property);
        assertEquals(ScopeValueKind.PROPERTY, property.kind());
        assertEquals(GdIntType.INT, property.type());
        assertTrue(property.writable());

        var functions = classScope.resolveFunctionsHere("ping");
        assertEquals(1, functions.size());
        assertEquals(ping, functions.getFirst());
    }

    @Test
    void inheritedMembersAreVisibleButCurrentClassWinsOnNameConflict() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var inheritedRender = FrontendScopeTestSupport.createFunction("render", GdStringType.STRING);
        var parentClass = FrontendScopeTestSupport.createClass(
                "BaseHero",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("speed", GdStringType.STRING)),
                java.util.List.of(inheritedRender, FrontendScopeTestSupport.createFunction("only_parent", GdStringType.STRING))
        );
        var childRender = FrontendScopeTestSupport.createFunction("render", GdBoolType.BOOL);
        var childClass = FrontendScopeTestSupport.createClass(
                "Hero",
                "BaseHero",
                java.util.List.of(FrontendScopeTestSupport.createProperty("speed", GdIntType.INT)),
                java.util.List.of(childRender)
        );
        registry.addGdccClass(parentClass);
        registry.addGdccClass(childClass);

        var classScope = new ClassScope(registry, registry, childClass);

        var property = classScope.resolveValueHere("speed");
        assertNotNull(property);
        assertEquals(GdIntType.INT, property.type());

        var directFunctions = classScope.resolveFunctionsHere("render");
        assertEquals(1, directFunctions.size());
        assertEquals(childRender, directFunctions.getFirst());

        var inheritedFunctions = classScope.resolveFunctionsHere("only_parent");
        assertEquals(1, inheritedFunctions.size());
        assertEquals("only_parent", inheritedFunctions.getFirst().getName());
    }

    @Test
    void inheritedReadonlyEnginePropertyPublishesNonWritableBinding() {
        var readonlyBase = new ExtensionGdClass(
                "ReadonlyBase",
                false,
                true,
                "Object",
                "core",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(new ExtensionGdClass.PropertyInfo("locked", "int", true, false, "0")),
                java.util.List.of()
        );
        var registry = FrontendScopeTestSupport.createRegistry(java.util.List.of(readonlyBase));
        var childClass = FrontendScopeTestSupport.createClass("Hero", "ReadonlyBase", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(childClass);

        var classScope = new ClassScope(registry, registry, childClass);
        var propertyResult = classScope.resolveValue("locked", ResolveRestriction.instanceContext());

        assertTrue(propertyResult.isAllowed());
        var property = propertyResult.requireValue();
        assertEquals(ScopeValueKind.PROPERTY, property.kind());
        assertEquals(GdIntType.INT, property.type());
        assertFalse(property.writable());
    }

    @Test
    void canonicalInnerSuperclassNamesKeepInheritedMembersVisible() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var parentPing = FrontendScopeTestSupport.createFunction("ping", GdStringType.STRING);
        var parentClass = FrontendScopeTestSupport.createClass(
                "Outer__sub__Shared",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("hp", GdIntType.INT)),
                java.util.List.of(parentPing)
        );
        var childClass = FrontendScopeTestSupport.createClass(
                "Outer__sub__Leaf",
                "Outer__sub__Shared",
                java.util.List.of(),
                java.util.List.of()
        );
        registry.addGdccClass(parentClass, "Shared");
        registry.addGdccClass(childClass, "Leaf");

        var classScope = new ClassScope(registry, registry, childClass);

        var property = classScope.resolveValueHere("hp");
        assertNotNull(property);
        assertEquals(GdIntType.INT, property.type());

        var inheritedFunctions = classScope.resolveFunctionsHere("ping");
        assertEquals(1, inheritedFunctions.size());
        assertEquals(parentPing, inheritedFunctions.getFirst());
    }

    @Test
    void sourceStyledInnerSuperclassNamesFailFastForInheritedValueAndFunctionLookup() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var parentPing = FrontendScopeTestSupport.createFunction("ping", GdStringType.STRING);
        var parentClass = FrontendScopeTestSupport.createClass(
                "Outer__sub__Shared",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("hp", GdIntType.INT)),
                java.util.List.of(parentPing)
        );
        var childClass = FrontendScopeTestSupport.createClass(
                "Outer__sub__BrokenLeaf",
                "Shared",
                java.util.List.of(),
                java.util.List.of()
        );
        registry.addGdccClass(parentClass, "Shared");
        registry.addGdccClass(childClass, "BrokenLeaf");

        var classScope = new ClassScope(registry, registry, childClass);

        assertThrows(ScopeLookupException.class, () -> classScope.resolveValueHere("hp"));
        assertThrows(ScopeLookupException.class, () -> classScope.resolveFunctionsHere("ping"));
        assertEquals(
                new GdObjectType("Outer__sub__Shared"),
                registry.resolveTypeMeta("Outer__sub__Shared").instanceType()
        );
    }

    @Test
    void directOverloadsStayGroupedAtCurrentClassLayer() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var parentOverload = FrontendScopeTestSupport.createFunction("act", GdStringType.STRING);
        var currentOverloadA = FrontendScopeTestSupport.createFunction("act", GdIntType.INT);
        var currentOverloadB = FrontendScopeTestSupport.createFunction("act", GdBoolType.BOOL);
        var parentClass = FrontendScopeTestSupport.createClass(
                "BaseHero",
                "Object",
                java.util.List.of(),
                java.util.List.of(parentOverload)
        );
        var childClass = FrontendScopeTestSupport.createClass(
                "Hero",
                "BaseHero",
                java.util.List.of(),
                java.util.List.of(currentOverloadA, currentOverloadB)
        );
        registry.addGdccClass(parentClass);
        registry.addGdccClass(childClass);

        var classScope = new ClassScope(registry, registry, childClass);
        var directOverloads = classScope.resolveFunctionsHere("act");
        assertEquals(2, directOverloads.size());
        assertEquals(currentOverloadA, directOverloads.getFirst());
        assertEquals(currentOverloadB, directOverloads.getLast());
    }

    @Test
    void classScopeSupportsDirectTypeMetaButDoesNotInheritParentClassTypeMetaLexically() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var parentClass = FrontendScopeTestSupport.createClass("BaseHero", "Object", java.util.List.of(), java.util.List.of());
        var childClass = FrontendScopeTestSupport.createClass("Hero", "BaseHero", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(parentClass);
        registry.addGdccClass(childClass);

        var parentScope = new ClassScope(registry, registry, parentClass);
        var parentInnerType = FrontendScopeTestSupport.createTypeMeta(
                "ParentInner",
                GdStringType.STRING,
                ScopeTypeMetaKind.GDCC_CLASS,
                "parent inner class",
                false
        );
        parentScope.defineTypeMeta(parentInnerType);

        var childScope = new ClassScope(registry, registry, childClass);
        var childEnumType = FrontendScopeTestSupport.createTypeMeta(
                "ChildEnum",
                GdIntType.INT,
                ScopeTypeMetaKind.GLOBAL_ENUM,
                "child enum",
                true
        );
        childScope.defineTypeMeta(childEnumType);

        assertEquals(childEnumType, childScope.resolveTypeMetaHere("ChildEnum"));
        assertNull(childScope.resolveTypeMeta("ParentInner"));
    }

    @Test
    void missingSuperclassMetadataFailsFastAndBlocksGlobalFallbackButCycleIsRejected() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var missingSuperClass = FrontendScopeTestSupport.createClass("Hero", "MissingBase", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(missingSuperClass);
        var missingSuperScope = new ClassScope(registry, registry, missingSuperClass);

        assertThrows(ScopeLookupException.class, () -> missingSuperScope.resolveValueHere("missing_property"));
        assertThrows(ScopeLookupException.class, () -> missingSuperScope.resolveFunctionsHere("missing_function"));
        assertThrows(ScopeLookupException.class, () -> missingSuperScope.resolveValue("GlobalPlayer"));
        assertThrows(ScopeLookupException.class, () -> missingSuperScope.resolveFunctions("global_tick"));

        var cycleA = FrontendScopeTestSupport.createClass("CycleA", "CycleB", java.util.List.of(), java.util.List.of());
        var cycleB = FrontendScopeTestSupport.createClass("CycleB", "CycleA", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(cycleA);
        registry.addGdccClass(cycleB);
        var cycleScope = new ClassScope(registry, registry, cycleA);

        assertThrows(ScopeLookupException.class, () -> cycleScope.resolveValueHere("anything"));
    }
}
