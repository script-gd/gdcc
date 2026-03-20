package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.exception.ScopeLookupException;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.scope.SignalDef;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdSignalType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassScopeSignalResolutionTest {
    @Test
    void directSignalResolvesAsSignalValueWithStableSignature() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var changed = FrontendScopeTestSupport.createSignal("changed", GdIntType.INT, GdStringType.STRING);
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", List.of(changed), List.of(), List.of());
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        var result = classScope.resolveValue("changed", ResolveRestriction.instanceContext());

        assertTrue(result.isAllowed());
        var binding = result.requireValue();
        assertEquals(ScopeValueKind.SIGNAL, binding.kind());
        assertTrue(binding.constant());
        assertFalse(binding.writable());
        assertFalse(binding.staticMember());
        assertSame(changed, assertInstanceOf(SignalDef.class, binding.declaration()));

        var signalType = assertInstanceOf(GdSignalType.class, binding.type());
        assertEquals(List.of(GdIntType.INT, GdStringType.STRING), signalType.parameterTypes());
    }

    @Test
    void inheritedSignalsRemainVisibleAndDirectSignalWinsOnNameConflict() {
        var engineBase = FrontendScopeTestSupport.createEngineClass(
                "EmitterBase",
                "Node",
                List.of(FrontendScopeTestSupport.createEngineSignal("engine_changed", GdStringType.STRING))
        );
        var registry = FrontendScopeTestSupport.createRegistry(List.of(engineBase));
        var parentClass = FrontendScopeTestSupport.createClass(
                "BaseHero",
                "EmitterBase",
                List.of(
                        FrontendScopeTestSupport.createSignal("changed", GdBoolType.BOOL),
                        FrontendScopeTestSupport.createSignal("parent_only", GdStringType.STRING)
                ),
                List.of(),
                List.of()
        );
        var childSignal = FrontendScopeTestSupport.createSignal("changed", GdIntType.INT);
        var childClass = FrontendScopeTestSupport.createClass(
                "Hero",
                "BaseHero",
                List.of(childSignal),
                List.of(),
                List.of()
        );
        registry.addGdccClass(parentClass);
        registry.addGdccClass(childClass);

        var classScope = new ClassScope(registry, registry, childClass);

        var directSignal = assertInstanceOf(
                GdSignalType.class,
                classScope.resolveValue("changed", ResolveRestriction.instanceContext()).requireValue().type()
        );
        assertEquals(List.of(GdIntType.INT), directSignal.parameterTypes());

        var inheritedGdccSignal = assertInstanceOf(
                GdSignalType.class,
                classScope.resolveValue("parent_only", ResolveRestriction.instanceContext()).requireValue().type()
        );
        assertEquals(List.of(GdStringType.STRING), inheritedGdccSignal.parameterTypes());

        var inheritedEngineSignal = assertInstanceOf(
                GdSignalType.class,
                classScope.resolveValue("engine_changed", ResolveRestriction.instanceContext()).requireValue().type()
        );
        assertEquals(List.of(GdStringType.STRING), inheritedEngineSignal.parameterTypes());
    }

    @Test
    void staticContextBlocksSignalAndStillShadowsGlobalSingletonAndEnum() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass(
                "Hero",
                "Object",
                List.of(
                        FrontendScopeTestSupport.createSignal("GlobalPlayer"),
                        FrontendScopeTestSupport.createSignal("GlobalFlags")
                ),
                List.of(),
                List.of()
        );
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);

        var singletonShadow = classScope.resolveValue("GlobalPlayer", ResolveRestriction.staticContext());
        assertTrue(singletonShadow.isBlocked());
        assertEquals(ScopeValueKind.SIGNAL, singletonShadow.requireValue().kind());

        var enumShadow = classScope.resolveValue("GlobalFlags", ResolveRestriction.staticContext());
        assertTrue(enumShadow.isBlocked());
        assertEquals(ScopeValueKind.SIGNAL, enumShadow.requireValue().kind());
    }

    @Test
    void sourceStyledInnerSuperclassNamesFailFastForInheritedSignals() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var parentSignal = FrontendScopeTestSupport.createSignal("changed", GdStringType.STRING);
        var parentClass = FrontendScopeTestSupport.createClass(
                "Outer$Shared",
                "Object",
                List.of(parentSignal),
                List.of(),
                List.of()
        );
        var childClass = FrontendScopeTestSupport.createClass(
                "Outer$Leaf",
                "Shared",
                List.of(),
                List.of(),
                List.of()
        );
        registry.addGdccClass(parentClass, "Shared");
        registry.addGdccClass(childClass, "Leaf");

        var classScope = new ClassScope(registry, registry, childClass);
        assertThrows(
                ScopeLookupException.class,
                () -> classScope.resolveValueHere("changed", ResolveRestriction.instanceContext())
        );
    }

    @Test
    void missingSuperclassMetadataFailsFastForSignalsAndBlocksGlobalFallback() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var missingSuperClass = FrontendScopeTestSupport.createClass("Hero", "MissingBase", List.of(), List.of(), List.of());
        registry.addGdccClass(missingSuperClass);

        var missingSuperScope = new ClassScope(registry, registry, missingSuperClass);

        assertThrows(
                ScopeLookupException.class,
                () -> missingSuperScope.resolveValueHere("missing_signal", ResolveRestriction.instanceContext())
        );
        assertThrows(
                ScopeLookupException.class,
                () -> missingSuperScope.resolveValue("GlobalPlayer", ResolveRestriction.instanceContext())
        );

        var cycleA = FrontendScopeTestSupport.createClass("CycleA", "CycleB", List.of(), List.of(), List.of());
        var cycleB = FrontendScopeTestSupport.createClass("CycleB", "CycleA", List.of(), List.of(), List.of());
        registry.addGdccClass(cycleA);
        registry.addGdccClass(cycleB);
        var cycleScope = new ClassScope(registry, registry, cycleA);

        assertThrows(ScopeLookupException.class, () -> cycleScope.resolveValueHere("anything", ResolveRestriction.instanceContext()));
    }
}
