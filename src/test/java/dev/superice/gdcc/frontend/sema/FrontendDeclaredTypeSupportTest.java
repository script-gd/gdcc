package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendDeclaredTypeSupportTest {
    private static final Range SYNTHETIC_RANGE = new Range(
            2,
            12,
            new Point(0, 2),
            new Point(0, 12)
    );
    private static final Path SOURCE_PATH = Path.of("tmp", "declared_type_support.gd");

    @Test
    void resolveTypeOrVariantReturnsVariantForMissingTypeRefWithoutDiagnostics() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var resolvedType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                null,
                registry,
                SOURCE_PATH,
                diagnostics
        );

        assertEquals(GdVariantType.VARIANT, resolvedType);
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void resolveTypeOrVariantTreatsBlankAndInferredMarkersAsVariantWithoutDiagnostics() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var blankType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("   ", SYNTHETIC_RANGE),
                registry,
                SOURCE_PATH,
                diagnostics
        );
        var inferredType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef(" := ", SYNTHETIC_RANGE),
                registry,
                SOURCE_PATH,
                diagnostics
        );

        assertEquals(GdVariantType.VARIANT, blankType);
        assertEquals(GdVariantType.VARIANT, inferredType);
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void resolveTypeOrVariantUsesStrictSharedResolverForKnownDeclaredTypes() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = registryWithGdccClass("Helper");

        var intType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("  int  ", SYNTHETIC_RANGE),
                registry,
                SOURCE_PATH,
                diagnostics
        );
        var helperArrayType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("  Array[Helper]  ", SYNTHETIC_RANGE),
                registry,
                SOURCE_PATH,
                diagnostics
        );

        assertEquals(GdIntType.INT, intType);
        assertEquals(new GdArrayType(new GdObjectType("Helper")), helperArrayType);
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void resolveTypeOrVariantWarnsAndFallsBackForUnknownBareType() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var resolvedType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("MissingType", SYNTHETIC_RANGE),
                registry,
                SOURCE_PATH,
                diagnostics
        );
        var diagnostic = diagnostics.snapshot().asList().getFirst();

        assertEquals(GdVariantType.VARIANT, resolvedType);
        assertEquals(1, diagnostics.snapshot().size());
        assertEquals(FrontendDiagnosticSeverity.WARNING, diagnostic.severity());
        assertEquals("sema.type_resolution", diagnostic.category());
        assertTrue(diagnostic.message().contains("MissingType"));
        assertEquals(SOURCE_PATH, diagnostic.sourcePath());
        assertEquals(FrontendRange.fromAstRange(SYNTHETIC_RANGE), diagnostic.range());
    }

    @Test
    void resolveTypeOrVariantWarnsAndFallsBackForUnsupportedStructuredTypeText() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var resolvedType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("Array[Array[int]]", SYNTHETIC_RANGE),
                registry,
                SOURCE_PATH,
                diagnostics
        );
        var diagnostic = diagnostics.snapshot().asList().getFirst();

        assertEquals(GdVariantType.VARIANT, resolvedType);
        assertEquals(1, diagnostics.snapshot().size());
        assertEquals("sema.type_resolution", diagnostic.category());
        assertTrue(diagnostic.message().contains("Array[Array[int]]"));
    }

    private ClassRegistry registryWithGdccClass(String className) throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef(className, "RefCounted"));
        return registry;
    }
}
