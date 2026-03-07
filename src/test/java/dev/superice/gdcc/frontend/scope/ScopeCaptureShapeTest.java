package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ScopeCaptureShapeTest {
    @Test
    void captureWinsOverClassAndGlobalBindings() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass(
                "Hero",
                "Object",
                java.util.List.of(FrontendScopeTestSupport.createProperty("GlobalPlayer", GdIntType.INT)),
                java.util.List.of()
        );
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        var callable = new CallableScope(classScope);
        var lambda = FrontendScopeTestSupport.createFunction("lambda", GdStringType.STRING);
        callable.defineCapture(FrontendScopeTestSupport.createCapture("GlobalPlayer", GdStringType.STRING, lambda));

        var resolved = callable.resolveValue("GlobalPlayer");
        assertNotNull(resolved);
        assertEquals(ScopeValueKind.CAPTURE, resolved.kind());
        assertEquals(GdStringType.STRING, resolved.type());
    }

    @Test
    void nestedBlocksResolveCaptureThroughParentChain() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        var callable = new CallableScope(classScope);
        var lambda = FrontendScopeTestSupport.createFunction("lambda", GdStringType.STRING);
        callable.defineCapture(FrontendScopeTestSupport.createCapture("outerName", GdStringType.STRING, lambda));

        var outerBlock = new BlockScope(callable);
        var innerBlock = new BlockScope(outerBlock);

        var resolved = innerBlock.resolveValue("outerName");
        assertNotNull(resolved);
        assertEquals(ScopeValueKind.CAPTURE, resolved.kind());
        assertEquals(GdStringType.STRING, resolved.type());
    }

    @Test
    void duplicateParameterAndCaptureNamesAreRejected() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(classDef);

        var callable = new CallableScope(new ClassScope(registry, registry, classDef));
        var owner = FrontendScopeTestSupport.createFunction("lambda", GdStringType.STRING);
        callable.defineParameter(FrontendScopeTestSupport.createParameter("dup", GdIntType.INT, owner));

        assertThrows(IllegalArgumentException.class, () -> callable.defineCapture(
                FrontendScopeTestSupport.createCapture("dup", GdStringType.STRING, owner)
        ));
    }
}
