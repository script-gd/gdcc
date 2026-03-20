package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrontendInnerClassScopeIsolationTest {
    @Test
    void innerClassKeepsOuterTypeMetaButSkipsOuterValueAndFunctionBindings() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var outerFunction = FrontendScopeTestSupport.createFunction("outer_call", GdStringType.STRING);
        var outerClass = FrontendScopeTestSupport.createClass(
                "Outer",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("outer_prop", GdIntType.INT)),
                java.util.List.of(outerFunction)
        );
        var innerClass = FrontendScopeTestSupport.createClass("Inner", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(outerClass);
        registry.addGdccClass(innerClass);

        var outerScope = new ClassScope(registry, registry, outerClass);
        outerScope.defineConstant("OUTER_CONST", GdIntType.INT, "outer const");
        var outerTypeMeta = FrontendScopeTestSupport.createTypeMeta(
                "Outer$OuterInnerType",
                "OuterInnerType",
                GdStringType.STRING,
                ScopeTypeMetaKind.GDCC_CLASS,
                "outer inner type",
                false
        );
        outerScope.defineTypeMeta(outerTypeMeta);

        var innerScope = new ClassScope(outerScope, registry, innerClass);

        assertNull(innerScope.resolveValue("outer_prop"));
        assertNull(innerScope.resolveValue("OUTER_CONST"));
        assertTrue(innerScope.resolveFunctions("outer_call").isEmpty());
        assertEquals(outerTypeMeta, innerScope.resolveTypeMeta("OuterInnerType"));
        assertEquals("Outer$OuterInnerType", innerScope.resolveTypeMeta("OuterInnerType").canonicalName());

        var globalValue = innerScope.resolveValue("GlobalPlayer");
        assertNotNull(globalValue);
        assertEquals("GlobalPlayer", globalValue.name());
    }
}
