package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScopeChainTest {
    @Test
    void blockLocalOverridesParameterAndClassMember() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass(
                "Hero",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("score", GdStringType.STRING)),
                java.util.List.of()
        );
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        var callable = new CallableScope(classScope);
        var ownerFunction = FrontendScopeTestSupport.createFunction("tick", GdStringType.STRING);
        callable.defineParameter(FrontendScopeTestSupport.createParameter("score", GdIntType.INT, ownerFunction));

        var block = new BlockScope(callable);
        block.defineLocal("score", GdBoolType.BOOL, "local score");

        var resolved = block.resolveValue("score");
        assertNotNull(resolved);
        assertEquals(ScopeValueKind.LOCAL, resolved.kind());
        assertEquals(GdBoolType.BOOL, resolved.type());
    }

    @Test
    void parameterOverridesClassProperty() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass(
                "Hero",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("name", GdStringType.STRING)),
                java.util.List.of()
        );
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        var callable = new CallableScope(classScope);
        var ownerFunction = FrontendScopeTestSupport.createFunction("tick", GdStringType.STRING);
        callable.defineParameter(FrontendScopeTestSupport.createParameter("name", GdIntType.INT, ownerFunction));

        var resolved = callable.resolveValue("name");
        assertNotNull(resolved);
        assertEquals(ScopeValueKind.PARAMETER, resolved.kind());
        assertEquals(GdIntType.INT, resolved.type());
    }

    @Test
    void classScopeOverridesGlobalSingletonAndUtilityButFallsBackWhenMissing() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var globalOverrideFunction = FrontendScopeTestSupport.createFunction("global_tick", GdIntType.INT);
        var classDef = FrontendScopeTestSupport.createClass(
                "Hero",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("GlobalPlayer", GdIntType.INT)),
                java.util.List.of(globalOverrideFunction)
        );
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);

        var classValue = classScope.resolveValue("GlobalPlayer");
        assertNotNull(classValue);
        assertEquals(ScopeValueKind.PROPERTY, classValue.kind());
        assertEquals(GdIntType.INT, classValue.type());

        var classFunctions = classScope.resolveFunctions("global_tick");
        assertEquals(1, classFunctions.size());
        assertEquals(globalOverrideFunction, classFunctions.getFirst());

        var globalValue = classScope.resolveValue("GlobalFlags");
        assertNotNull(globalValue);
        assertEquals(ScopeValueKind.GLOBAL_ENUM, globalValue.kind());

        var globalFunctions = classScope.resolveFunctions("missing_function");
        assertTrue(globalFunctions.isEmpty());
        assertEquals("global_tick", registry.resolveFunctions("global_tick").getFirst().getName());
    }

    @Test
    void duplicateBlockLocalBindingIsRejected() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(classDef);

        var block = new BlockScope(new CallableScope(new ClassScope(registry, registry, classDef)));
        block.defineLocal("dup", GdIntType.INT, "first");

        assertThrows(IllegalArgumentException.class, () -> block.defineLocal("dup", GdBoolType.BOOL, "second"));
    }
}
