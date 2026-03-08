package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrontendStaticContextShadowingTest {
    @Test
    void blockedCurrentClassMemberDoesNotFallBackToGlobalBinding() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass(
                "Hero",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("GlobalPlayer", GdIntType.INT, false)),
                java.util.List.of(FrontendScopeTestSupport.createFunction("global_tick", GdStringType.STRING, false))
        );
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);

        var valueResult = classScope.resolveValue("GlobalPlayer", ResolveRestriction.staticContext());
        assertTrue(valueResult.isBlocked());
        assertEquals(ScopeValueKind.PROPERTY, valueResult.requireValue().kind());
        assertEquals(GdIntType.INT, valueResult.requireValue().type());

        var functionResult = classScope.resolveFunctions("global_tick", ResolveRestriction.staticContext());
        assertTrue(functionResult.isBlocked());
        assertEquals(1, functionResult.requireValue().size());
        assertEquals("global_tick", functionResult.requireValue().getFirst().getName());
    }
}
