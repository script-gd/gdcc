package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdObjectType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrontendModuleSkeletonTest {
    @Test
    void resolveSourceFacingTypeMetaRemapsMappedTopLevelCanonicalNameAfterLexicalMiss() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef("RuntimeWorker", "RefCounted"), "MappedWorker");
        var ownerClass = new LirClassDef("Owner", "RefCounted");
        registry.addGdccClass(ownerClass);
        var scope = new ClassScope(registry, registry, ownerClass);
        var moduleSkeleton = moduleSkeleton(Map.of("MappedWorker", "RuntimeWorker"));

        var resolvedTypeMeta = moduleSkeleton.resolveSourceFacingTypeMeta(
                scope,
                "MappedWorker",
                ResolveRestriction.unrestricted()
        ).requireValue();

        assertEquals("RuntimeWorker", resolvedTypeMeta.canonicalName());
        assertEquals("MappedWorker", resolvedTypeMeta.sourceName());
    }

    @Test
    void resolveSourceFacingTypeMetaKeepsLexicalHitAheadOfMappedTopLevelRetry() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef("RuntimeWorker", "RefCounted"), "MappedWorker");
        var ownerClass = new LirClassDef("Owner", "RefCounted");
        registry.addGdccClass(ownerClass);
        var scope = new ClassScope(registry, registry, ownerClass);
        scope.defineTypeMeta(typeMeta("Owner__sub__MappedWorker", "MappedWorker"));
        var moduleSkeleton = moduleSkeleton(Map.of("MappedWorker", "RuntimeWorker"));

        var resolvedTypeMeta = moduleSkeleton.resolveSourceFacingTypeMeta(
                scope,
                "MappedWorker",
                ResolveRestriction.unrestricted()
        ).requireValue();

        assertEquals("Owner__sub__MappedWorker", resolvedTypeMeta.canonicalName());
        assertEquals("MappedWorker", resolvedTypeMeta.sourceName());
    }

    @Test
    void tryResolveSourceFacingDeclaredTypeRemapsTopLevelContainerLeafAfterStrictMiss() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef("RuntimeWorker", "RefCounted"), "MappedWorker");
        var ownerClass = new LirClassDef("Owner", "RefCounted");
        registry.addGdccClass(ownerClass);
        var scope = new ClassScope(registry, registry, ownerClass);
        var moduleSkeleton = moduleSkeleton(Map.of("MappedWorker", "RuntimeWorker"));

        var resolvedType = assertInstanceOf(
                GdArrayType.class,
                moduleSkeleton.tryResolveSourceFacingDeclaredType(scope, "Array[MappedWorker]")
        );

        assertEquals(new GdObjectType("RuntimeWorker"), resolvedType.getValueType());
    }

    @Test
    void constructorFreezesTopLevelCanonicalNameMap() {
        var moduleSkeleton = moduleSkeleton(Map.of("MappedWorker", "RuntimeWorker"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> moduleSkeleton.topLevelCanonicalNameMap().put("Other", "RuntimeOther")
        );
    }

    private FrontendModuleSkeleton moduleSkeleton(Map<String, String> topLevelCanonicalNameMap) {
        return new FrontendModuleSkeleton(
                "test_module",
                List.of(),
                topLevelCanonicalNameMap,
                new DiagnosticSnapshot(List.of())
        );
    }

    private ScopeTypeMeta typeMeta(String canonicalName, String sourceName) {
        return new ScopeTypeMeta(
                canonicalName,
                sourceName,
                new GdObjectType(canonicalName),
                ScopeTypeMetaKind.GDCC_CLASS,
                new LirClassDef(canonicalName, "RefCounted"),
                false
        );
    }
}
