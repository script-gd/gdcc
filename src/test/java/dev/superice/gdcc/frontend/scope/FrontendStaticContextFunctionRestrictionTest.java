package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrontendStaticContextFunctionRestrictionTest {
    @Test
    void staticContextAllowsStaticMethodAndFiltersMixedOverloadSet() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var instanceTick = FrontendScopeTestSupport.createFunction("tick", GdStringType.STRING, false);
        var staticMake = FrontendScopeTestSupport.createFunction("make", GdIntType.INT, true);
        var mixedStatic = FrontendScopeTestSupport.createFunction("mix", GdBoolType.BOOL, true);
        var mixedInstance = FrontendScopeTestSupport.createFunction("mix", GdStringType.STRING, false);
        var classDef = FrontendScopeTestSupport.createClass(
                "Hero",
                "Object",
                java.util.List.of(),
                java.util.List.of(instanceTick, staticMake, mixedStatic, mixedInstance)
        );
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);

        var blockedTick = classScope.resolveFunctions("tick", ResolveRestriction.staticContext());
        assertTrue(blockedTick.isBlocked());
        assertEquals(1, blockedTick.requireValue().size());
        assertEquals(instanceTick, blockedTick.requireValue().getFirst());

        var staticMakeResult = classScope.resolveFunctions("make", ResolveRestriction.staticContext());
        assertTrue(staticMakeResult.isAllowed());
        assertEquals(1, staticMakeResult.requireValue().size());
        assertEquals(staticMake, staticMakeResult.requireValue().getFirst());

        var mixedStaticResult = classScope.resolveFunctions("mix", ResolveRestriction.staticContext());
        assertTrue(mixedStaticResult.isAllowed());
        assertEquals(1, mixedStaticResult.requireValue().size());
        assertEquals(mixedStatic, mixedStaticResult.requireValue().getFirst());

        var mixedInstanceResult = classScope.resolveFunctions("mix", ResolveRestriction.instanceContext());
        assertTrue(mixedInstanceResult.isAllowed());
        assertEquals(2, mixedInstanceResult.requireValue().size());
    }

    @Test
    void blockedDirectInstanceMethodStillShadowsInheritedStaticMethod() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var parentStatic = FrontendScopeTestSupport.createFunction("shared", GdStringType.STRING, true);
        var childInstance = FrontendScopeTestSupport.createFunction("shared", GdIntType.INT, false);
        var parentClass = FrontendScopeTestSupport.createClass("BaseHero", "Object", java.util.List.of(), java.util.List.of(parentStatic));
        var childClass = FrontendScopeTestSupport.createClass("Hero", "BaseHero", java.util.List.of(), java.util.List.of(childInstance));
        registry.addGdccClass(parentClass);
        registry.addGdccClass(childClass);

        var classScope = new ClassScope(registry, registry, childClass);
        var result = classScope.resolveFunctions("shared", ResolveRestriction.staticContext());

        assertTrue(result.isBlocked());
        assertEquals(1, result.requireValue().size());
        assertEquals(childInstance, result.requireValue().getFirst());
    }
}
