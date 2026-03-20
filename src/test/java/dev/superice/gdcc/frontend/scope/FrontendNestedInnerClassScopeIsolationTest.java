package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrontendNestedInnerClassScopeIsolationTest {
    @Test
    void nestedInnerClassSeesAllOuterTypeMetaButNoOuterValueOrFunction() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var outerClass = FrontendScopeTestSupport.createClass(
                "Outer",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("outer_prop", GdIntType.INT)),
                java.util.List.of(FrontendScopeTestSupport.createFunction("outer_call", GdStringType.STRING))
        );
        var middleClass = FrontendScopeTestSupport.createClass(
                "Middle",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("middle_prop", GdStringType.STRING)),
                java.util.List.of(FrontendScopeTestSupport.createFunction("middle_call", GdBoolType.BOOL))
        );
        var innerClass = FrontendScopeTestSupport.createClass("Inner", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(outerClass);
        registry.addGdccClass(middleClass);
        registry.addGdccClass(innerClass);

        var outerScope = new ClassScope(registry, registry, outerClass);
        outerScope.defineTypeMeta(FrontendScopeTestSupport.createTypeMeta(
                "Outer$OuterType",
                "OuterType",
                GdIntType.INT,
                ScopeTypeMetaKind.GLOBAL_ENUM,
                "outer type",
                true
        ));

        var middleScope = new ClassScope(outerScope, registry, middleClass);
        middleScope.defineTypeMeta(FrontendScopeTestSupport.createTypeMeta(
                "Outer$Middle",
                "MiddleType",
                GdStringType.STRING,
                ScopeTypeMetaKind.GDCC_CLASS,
                "middle type",
                false
        ));

        var innerScope = new ClassScope(middleScope, registry, innerClass);

        assertEquals("OuterType", innerScope.resolveTypeMeta("OuterType").sourceName());
        assertEquals("Outer$OuterType", innerScope.resolveTypeMeta("OuterType").canonicalName());
        assertEquals("MiddleType", innerScope.resolveTypeMeta("MiddleType").sourceName());
        assertEquals("Outer$Middle", innerScope.resolveTypeMeta("MiddleType").canonicalName());
        assertNull(innerScope.resolveValue("outer_prop"));
        assertNull(innerScope.resolveValue("middle_prop"));
        assertTrue(innerScope.resolveFunctions("outer_call").isEmpty());
        assertTrue(innerScope.resolveFunctions("middle_call").isEmpty());
    }
}
