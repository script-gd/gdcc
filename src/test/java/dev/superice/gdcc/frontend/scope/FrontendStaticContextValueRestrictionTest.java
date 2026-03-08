package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrontendStaticContextValueRestrictionTest {
    @Test
    void staticContextAllowsConstAndStaticPropertyButBlocksInstanceProperty() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass(
                "Hero",
                "Object",
                java.util.List.of(
                        FrontendScopeTestSupport.createProperty("hp", GdIntType.INT, false),
                        FrontendScopeTestSupport.createProperty("LIMIT", GdBoolType.BOOL, true)
                ),
                java.util.List.of()
        );
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        classScope.defineConstant("ANSWER", GdStringType.STRING, "const");

        var blockedHp = classScope.resolveValue("hp", ResolveRestriction.staticContext());
        assertTrue(blockedHp.isBlocked());
        assertEquals("hp", blockedHp.requireValue().name());
        assertNull(blockedHp.allowedValueOrNull());

        var staticProperty = classScope.resolveValue("LIMIT", ResolveRestriction.staticContext());
        assertTrue(staticProperty.isAllowed());
        assertTrue(staticProperty.requireValue().staticMember());

        var classConst = classScope.resolveValue("ANSWER", ResolveRestriction.staticContext());
        assertTrue(classConst.isAllowed());
        assertEquals("ANSWER", classConst.requireValue().name());

        assertNotNull(classScope.resolveValue("hp", ResolveRestriction.instanceContext()).allowedValueOrNull());
        assertNotNull(classScope.resolveValue("LIMIT", ResolveRestriction.instanceContext()).allowedValueOrNull());
        assertNotNull(classScope.resolveValue("ANSWER", ResolveRestriction.instanceContext()).allowedValueOrNull());
    }

    @Test
    void blockedDirectInstancePropertyStillShadowsInheritedStaticProperty() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var parentClass = FrontendScopeTestSupport.createClass(
                "BaseHero",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("shared", GdStringType.STRING, true)),
                java.util.List.of()
        );
        var childClass = FrontendScopeTestSupport.createClass(
                "Hero",
                "BaseHero",
                java.util.List.of(FrontendScopeTestSupport.createProperty("shared", GdIntType.INT, false)),
                java.util.List.of()
        );
        registry.addGdccClass(parentClass);
        registry.addGdccClass(childClass);

        var classScope = new ClassScope(registry, registry, childClass);
        var result = classScope.resolveValue("shared", ResolveRestriction.staticContext());

        assertTrue(result.isBlocked());
        assertEquals(GdIntType.INT, result.requireValue().type());
    }
}
