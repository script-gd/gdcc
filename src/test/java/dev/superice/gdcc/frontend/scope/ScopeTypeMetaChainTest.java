package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ScopeTypeMetaChainTest {
    @Test
    void classLocalTypeMetaOverridesGlobalTypeMeta() {
        var registry = FrontendScopeTestSupport.createRegistry();
        registry.addGdccClass(FrontendScopeTestSupport.createClass("SharedType", "Object", java.util.List.of(), java.util.List.of()));
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        var localTypeMeta = FrontendScopeTestSupport.createTypeMeta(
                "SharedType",
                GdStringType.STRING,
                ScopeTypeMetaKind.GLOBAL_ENUM,
                "class-local enum",
                true
        );
        classScope.defineTypeMeta(localTypeMeta);

        var classLocalTypeMeta = classScope.resolveTypeMeta("SharedType", ResolveRestriction.unrestricted()).requireValue();
        assertEquals(localTypeMeta, classLocalTypeMeta);
        var globalTypeMeta = registry.resolveTypeMeta("SharedType", ResolveRestriction.unrestricted()).requireValue();
        assertEquals("SharedType", globalTypeMeta.canonicalName());
        assertEquals("SharedType", globalTypeMeta.sourceName());
    }

    @Test
    void callableAndBlockResolveTypeMetaThroughParentChain() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        var outerTypeMeta = FrontendScopeTestSupport.createTypeMeta(
                "Hero__sub__InnerType",
                "InnerType",
                GdStringType.STRING,
                ScopeTypeMetaKind.GDCC_CLASS,
                "inner class",
                false
        );
        classScope.defineTypeMeta(outerTypeMeta);

        var callable = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        var block = new BlockScope(callable, BlockScopeKind.BLOCK_STATEMENT);

        assertEquals(CallableScopeKind.FUNCTION_DECLARATION, callable.kind());
        assertEquals(BlockScopeKind.BLOCK_STATEMENT, block.kind());
        assertEquals(outerTypeMeta, callable.resolveTypeMeta("InnerType"));
        assertEquals(outerTypeMeta, block.resolveTypeMeta("InnerType"));
        assertEquals("Hero__sub__InnerType", callable.resolveTypeMeta("InnerType").canonicalName());

        var localAlias = FrontendScopeTestSupport.createTypeMeta(
                "LocalAlias",
                GdIntType.INT,
                ScopeTypeMetaKind.GLOBAL_ENUM,
                "local enum",
                true
        );
        block.defineTypeMeta(localAlias);
        assertEquals(localAlias, block.resolveTypeMeta("LocalAlias"));
    }

    @Test
    void typeMetaLookupStaysRestrictionInvariantAcrossFrontendScopes() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        var outerTypeMeta = FrontendScopeTestSupport.createTypeMeta(
                "Hero__sub__InnerType",
                "InnerType",
                GdStringType.STRING,
                ScopeTypeMetaKind.GDCC_CLASS,
                "inner class",
                false
        );
        classScope.defineTypeMeta(outerTypeMeta);

        var callable = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        var block = new BlockScope(callable, BlockScopeKind.BLOCK_STATEMENT);

        var staticResult = block.resolveTypeMeta("InnerType", ResolveRestriction.staticContext());
        var instanceResult = block.resolveTypeMeta("InnerType", ResolveRestriction.instanceContext());

        assertFalse(staticResult.isBlocked());
        assertFalse(instanceResult.isBlocked());
        assertEquals(outerTypeMeta, staticResult.allowedValueOrNull());
        assertEquals(outerTypeMeta, instanceResult.allowedValueOrNull());
    }

    @Test
    void valueNamespaceAndTypeNamespaceStayIsolated() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        classScope.defineConstant("TypeOnly", GdIntType.INT, "const value");
        var localTypeMeta = FrontendScopeTestSupport.createTypeMeta(
                "TypeOnly",
                GdStringType.STRING,
                ScopeTypeMetaKind.GLOBAL_ENUM,
                "enum view",
                true
        );
        classScope.defineTypeMeta(localTypeMeta);

        var valueBinding = classScope.resolveValue("TypeOnly");
        assertNotNull(valueBinding);
        assertEquals(GdIntType.INT, valueBinding.type());

        var typeBinding = classScope.resolveTypeMeta("TypeOnly");
        assertEquals(localTypeMeta, typeBinding);
        assertNull(classScope.resolveValue("MissingTypeOnly"));
        assertNull(classScope.resolveTypeMeta("MissingTypeOnly"));
    }

    @Test
    void duplicateLocalTypeMetaBindingIsRejected() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var classDef = FrontendScopeTestSupport.createClass("Hero", "Object", java.util.List.of(), java.util.List.of());
        registry.addGdccClass(classDef);

        var classScope = new ClassScope(registry, registry, classDef);
        classScope.defineTypeMeta(FrontendScopeTestSupport.createTypeMeta(
                "Hero__sub__InnerType",
                "InnerType",
                GdStringType.STRING,
                ScopeTypeMetaKind.GDCC_CLASS,
                "inner class",
                false
        ));

        assertThrows(IllegalArgumentException.class, () -> classScope.defineTypeMeta(
                FrontendScopeTestSupport.createTypeMeta(
                        "Hero__sub__ConflictingInnerType",
                        "InnerType",
                        GdIntType.INT,
                        ScopeTypeMetaKind.GLOBAL_ENUM,
                        "duplicate",
                        true
                )
        ));
    }
}
