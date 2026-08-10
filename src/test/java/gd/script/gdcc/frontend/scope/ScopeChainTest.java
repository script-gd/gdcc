package gd.script.gdcc.frontend.scope;

import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        var callable = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        var ownerFunction = FrontendScopeTestSupport.createFunction("tick", GdStringType.STRING);
        callable.defineParameter(FrontendScopeTestSupport.createParameter("score", GdIntType.INT, ownerFunction));

        var block = new BlockScope(callable, BlockScopeKind.BLOCK_STATEMENT);
        block.defineLocal("score", GdBoolType.BOOL, "local score");

        assertEquals(CallableScopeKind.FUNCTION_DECLARATION, callable.kind());
        assertEquals(BlockScopeKind.BLOCK_STATEMENT, block.kind());
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
        var callable = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        var ownerFunction = FrontendScopeTestSupport.createFunction("tick", GdStringType.STRING);
        callable.defineParameter(FrontendScopeTestSupport.createParameter("name", GdIntType.INT, ownerFunction));

        assertEquals(CallableScopeKind.FUNCTION_DECLARATION, callable.kind());
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

        var block = new BlockScope(
                new CallableScope(
                        new ClassScope(registry, registry, classDef),
                        CallableScopeKind.FUNCTION_DECLARATION
                ),
                BlockScopeKind.BLOCK_STATEMENT
        );
        block.defineLocal("dup", GdIntType.INT, "first");

        assertEquals(BlockScopeKind.BLOCK_STATEMENT, block.kind());
        assertThrows(IllegalArgumentException.class, () -> block.defineLocal("dup", GdBoolType.BOOL, "second"));
    }

    @Test
    void owningClassOrNullWalksFromBlockThroughCallableToClassScope() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        var callable = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        var block = new BlockScope(callable, BlockScopeKind.BLOCK_STATEMENT);

        assertNull(registry.currentClassOrNull());
        assertNull(registry.owningClassOrNull());
        assertSame(classDef, classScope.currentClassOrNull());
        assertSame(classDef, classScope.owningClassOrNull());
        assertNull(callable.currentClassOrNull());
        assertSame(classDef, callable.owningClassOrNull());
        assertNull(block.currentClassOrNull());
        assertSame(classDef, block.owningClassOrNull());
    }
}
