package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScopeTypeResolverTest {
    @Test
    void declaredTypeResolutionPrefersLexicalTypeMetaOverGlobalRegistry() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef("SharedType", "Object"));

        var outerClass = new LirClassDef("Outer", "Object");
        registry.addGdccClass(outerClass);
        var outerScope = new ClassScope(registry, registry, outerClass);
        outerScope.defineTypeMeta(gdccTypeMeta("Outer$SharedType", "SharedType"));

        assertEquals(
                new GdObjectType("Outer$SharedType"),
                ScopeTypeResolver.tryResolveDeclaredType(outerScope, "SharedType")
        );
        assertEquals(
                new GdObjectType("SharedType"),
                registry.tryResolveDeclaredType("SharedType")
        );
    }

    @Test
    void declaredTypeResolutionParsesContainersUsingLexicalTypeMeta() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var outerClass = new LirClassDef("Outer", "Object");
        registry.addGdccClass(outerClass);
        var outerScope = new ClassScope(registry, registry, outerClass);
        outerScope.defineTypeMeta(gdccTypeMeta("Outer$Inner", "Inner"));

        var arrayType = assertInstanceOf(
                GdArrayType.class,
                ScopeTypeResolver.tryResolveDeclaredType(outerScope, "Array[Inner]")
        );
        assertEquals(new GdObjectType("Outer$Inner"), arrayType.getValueType());

        var dictionaryType = assertInstanceOf(
                GdDictionaryType.class,
                ScopeTypeResolver.tryResolveDeclaredType(outerScope, "Dictionary[String, Inner]")
        );
        assertEquals(GdStringType.STRING, dictionaryType.getKeyType());
        assertEquals(new GdObjectType("Outer$Inner"), dictionaryType.getValueType());
    }

    @Test
    void declaredTypeResolutionRejectsUnknownAndUnsupportedStructuredTexts() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var ownerClass = new LirClassDef("Owner", "Object");
        registry.addGdccClass(ownerClass);
        var scope = new ClassScope(registry, registry, ownerClass);

        assertNull(ScopeTypeResolver.tryResolveTypeMeta(scope, " "));
        assertNull(ScopeTypeResolver.tryResolveDeclaredType(scope, "MissingType"));
        assertNull(ScopeTypeResolver.tryResolveDeclaredType(scope, "Dictionary[String]"));
        assertNull(ScopeTypeResolver.tryResolveDeclaredType(scope, "Array[Array[int]]"));
        assertNull(ScopeTypeResolver.tryResolveDeclaredType(scope, "Dictionary[String, Array[int]]"));
    }

    @Test
    void declaredTypeResolutionAllowsOptionalUnresolvedMapperForBareAndContainerLeafTypes() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var ownerClass = new LirClassDef("Owner", "Object");
        registry.addGdccClass(ownerClass);
        var scope = new ClassScope(registry, registry, ownerClass);

        assertEquals(
                new GdObjectType("Recovered_FutureEnemy"),
                ScopeTypeResolver.tryResolveDeclaredType(scope, "FutureEnemy", this::recoverUnresolvedType)
        );
        assertEquals(
                new GdArrayType(new GdObjectType("Recovered_FutureEnemy")),
                ScopeTypeResolver.tryResolveDeclaredType(scope, "Array[FutureEnemy]", this::recoverUnresolvedType)
        );
        assertEquals(
                new GdDictionaryType(GdStringType.STRING, new GdObjectType("Recovered_FutureEnemy")),
                ScopeTypeResolver.tryResolveDeclaredType(scope, "Dictionary[String, FutureEnemy]", this::recoverUnresolvedType)
        );
    }

    @Test
    void declaredTypeResolutionDoesNotInvokeMapperForMalformedStructuredTexts() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var ownerClass = new LirClassDef("Owner", "Object");
        registry.addGdccClass(ownerClass);
        var scope = new ClassScope(registry, registry, ownerClass);
        var mapperCalls = new AtomicInteger();

        var mapper = new UnresolvedTypeMapper() {
            @Override
            public GdObjectType mapUnresolvedType(dev.superice.gdcc.scope.Scope ignoredScope, String unresolvedTypeText) {
                mapperCalls.incrementAndGet();
                return new GdObjectType("Recovered_" + unresolvedTypeText);
            }
        };

        assertNull(ScopeTypeResolver.tryResolveDeclaredType(scope, "Dictionary[String]", mapper));
        assertNull(ScopeTypeResolver.tryResolveDeclaredType(scope, "Array[Array[int]]", mapper));
        assertNull(ScopeTypeResolver.tryResolveDeclaredType(scope, "Dictionary[String, Array[int]]", mapper));
        assertEquals(0, mapperCalls.get());
    }

    @Test
    void classRegistryDeclaredTypeResolutionDelegatesToSharedResolver() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef("InventoryItem", "Object"));

        assertEquals(
                ScopeTypeResolver.tryResolveDeclaredType(registry, "InventoryItem"),
                registry.tryResolveDeclaredType("InventoryItem")
        );
        assertEquals(
                ScopeTypeResolver.tryResolveDeclaredType(registry, "Array[InventoryItem]"),
                registry.tryResolveDeclaredType("Array[InventoryItem]")
        );
        assertEquals(
                ScopeTypeResolver.tryResolveDeclaredType(registry, "Dictionary[String, InventoryItem]"),
                registry.tryResolveDeclaredType("Dictionary[String, InventoryItem]")
        );
        assertEquals(
                ScopeTypeResolver.tryResolveDeclaredType(registry, "FutureEnemy", this::recoverUnresolvedType),
                registry.tryResolveDeclaredType("FutureEnemy", this::recoverUnresolvedType)
        );
    }

    private ScopeTypeMeta gdccTypeMeta(String canonicalName, String sourceName) {
        return new ScopeTypeMeta(
                canonicalName,
                sourceName,
                new GdObjectType(canonicalName),
                ScopeTypeMetaKind.GDCC_CLASS,
                new LirClassDef(canonicalName, "Object"),
                false
        );
    }

    private GdObjectType recoverUnresolvedType(dev.superice.gdcc.scope.Scope ignoredScope, String unresolvedTypeText) {
        return new GdObjectType("Recovered_" + unresolvedTypeText);
    }
}
