package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendParseRecovery;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void classifiesInferredTypeRefUsingTokenizerTrivia() {
        for (var text : List.of(
                ":=", ": =", ":\t=", " \r\n:\r\n=\r\n ", ": \\\n \\\r\n=",
                ": #\n=", ": # comment\rmore\n=", ": \\\r\n # continuation\r\n \\\n="
        )) {
            assertTrue(FrontendDeclaredTypeSupport.isInferredTypeRef(new TypeRef(text, SYNTHETIC_RANGE)), text);
        }
        for (var text : List.of(
                "", ":", ": # =", ":#comment=", ":\\=", ":\f=", ":==", ": int =",
                "\r:=", ":=\r", ":\r=", ":\\\r=", ": #a\r=", "\f:=\f",
                "# outside\n:=", "\\\n:=", ":= # outside\n", ":=\\\r\n"
        )) {
            assertFalse(FrontendDeclaredTypeSupport.isInferredTypeRef(new TypeRef(text, SYNTHETIC_RANGE)), text);
        }
        assertFalse(FrontendDeclaredTypeSupport.isInferredTypeRef(null));
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(SOURCE_PATH, "const C : \\\r\n= 1\n", diagnostics);
        var declaration = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
        assertNotNull(declaration.type());
        assertEquals(": \\\r\n=", declaration.type().sourceText());
        assertTrue(FrontendDeclaredTypeSupport.isInferredTypeRef(declaration.type()));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void recoveredTypeSlotClaimsOwnerAndLeafErrorsOnceAcrossPhases() throws IOException {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parser.parseUnit(SOURCE_PATH, "func f(x:, y: int):\n    pass\n", parseDiagnostics);
        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        var missing = function.parameters().getFirst();
        var healthy = function.parameters().getLast();
        assertNull(missing.type());
        var original = parseDiagnostics.snapshot().getFirst();
        var missingRecovery = unit.parseRecoveries().getFirst();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var data = FrontendAnalysisData.bootstrap();
        data.parseRecoveriesByOwner().put(missing, List.of(missingRecovery));
        assertEquals(GdVariantType.VARIANT, FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                null, registry, Map.of(), SOURCE_PATH, parseDiagnostics, data, missing, "parameter.type"
        ));
        assertEquals(List.of(original), parseDiagnostics.snapshot().asList());
        assertEquals(original, data.claimedParseRecoveries().get(missingRecovery));
        assertEquals(GdIntType.INT, FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                healthy.type(), registry, Map.of(), SOURCE_PATH, parseDiagnostics, data, healthy, "parameter.type"
        ));

        var leaf = healthy.type();
        assertNotNull(leaf);
        var range = FrontendRange.fromAstRange(leaf.range());
        var leafRecovery = new FrontendParseRecovery(
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, FrontendDiagnosticSeverity.ERROR,
                "damaged type argument", range, leaf, "type.argument"
        );
        var ownerRecovery = new FrontendParseRecovery(
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, FrontendDiagnosticSeverity.ERROR,
                "damaged parameter type", range, healthy, "parameter.type"
        );
        var independent = new FrontendParseRecovery(
                DiagnosticCodes.SLOT_PARAMETER_DEFAULT, FrontendDiagnosticSeverity.ERROR,
                "damaged default value", range, healthy, "parameter.default"
        );
        data.parseRecoveriesByOwner().put(healthy, List.of(ownerRecovery, independent));
        data.parseRecoveriesByOwner().put(leaf, List.of(leafRecovery));
        var fresh = new DiagnosticManager();
        var ownerDiagnostic = FrontendDiagnostic.error(
                ownerRecovery.code(), ownerRecovery.message(), original.sourcePath(), range
        );
        fresh.report(ownerDiagnostic);
        for (var phase = 0; phase < 2; phase++) {
            assertEquals(GdVariantType.VARIANT, FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                    leaf, registry, Map.of(), SOURCE_PATH, fresh, data, healthy, "parameter.type"
            ));
        }
        var fallback = fresh.snapshot().getLast();
        assertEquals(List.of(
                ownerDiagnostic,
                FrontendDiagnostic.error(
                        "sema.type_resolution", "Invalid declared type, fallback to Variant: damaged type argument",
                        original.sourcePath(), range
                )
        ), fresh.snapshot().asList());
        assertEquals(fallback, data.claimedParseRecoveries().get(ownerRecovery));
        assertEquals(fallback, data.claimedParseRecoveries().get(leafRecovery));
        assertNull(data.claimedParseRecoveries().get(independent));

        var otherManager = new DiagnosticManager();
        var leafDiagnostic = FrontendDiagnostic.error(
                leafRecovery.code(), leafRecovery.message(), original.sourcePath(), range
        );
        otherManager.reportAll(List.of(ownerDiagnostic, leafDiagnostic));
        assertEquals(GdVariantType.VARIANT, FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                leaf, registry, Map.of(), SOURCE_PATH, otherManager, data, healthy, "parameter.type"
        ));
        assertEquals(List.of(ownerDiagnostic, leafDiagnostic), otherManager.snapshot().asList());
        assertEquals(ownerDiagnostic, data.claimedParseRecoveries().get(ownerRecovery));
        assertEquals(leafDiagnostic, data.claimedParseRecoveries().get(leafRecovery));
        assertNull(data.claimedParseRecoveries().get(independent));
    }

    @Test
    void resolveTypeOrVariantReturnsVariantForMissingTypeRefWithoutDiagnostics() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var resolvedType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                null,
                registry,
                Map.of(),
                SOURCE_PATH,
                diagnostics
        );

        assertEquals(GdVariantType.VARIANT, resolvedType);
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void resolveTypeOrVariantTreatsBlankAndInferredMarkersAsDeferredVariantUntilExprTypingExists() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var inferredTypeRef = new TypeRef(" := ", SYNTHETIC_RANGE);

        var blankType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("   ", SYNTHETIC_RANGE),
                registry,
                Map.of(),
                SOURCE_PATH,
                diagnostics
        );
        var inferredType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                inferredTypeRef,
                registry,
                Map.of(),
                SOURCE_PATH,
                diagnostics
        );

        assertEquals(GdVariantType.VARIANT, blankType);
        assertEquals(GdVariantType.VARIANT, inferredType);
        assertTrue(FrontendDeclaredTypeSupport.isInferredTypeRef(inferredTypeRef));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void resolveTypeOrVariantUsesStrictSharedResolverForKnownDeclaredTypes() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = registryWithGdccClass("Helper");

        var intType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("  int  ", SYNTHETIC_RANGE),
                registry,
                Map.of(),
                SOURCE_PATH,
                diagnostics
        );
        var helperArrayType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("  Array[Helper]  ", SYNTHETIC_RANGE),
                registry,
                Map.of(),
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
                Map.of(),
                SOURCE_PATH,
                diagnostics
        );
        var diagnostic = diagnostics.snapshot().asList().getFirst();

        assertEquals(GdVariantType.VARIANT, resolvedType);
        assertEquals(1, diagnostics.snapshot().size());
        assertEquals(FrontendDiagnosticSeverity.WARNING, diagnostic.severity());
        assertEquals("sema.type_resolution", diagnostic.category());
        assertTrue(diagnostic.message().contains("MissingType"));
        assertEquals(FrontendDiagnostic.sourcePathText(SOURCE_PATH), diagnostic.sourcePath());
        assertEquals(FrontendRange.fromAstRange(SYNTHETIC_RANGE), diagnostic.range());
    }

    @Test
    void resolveTypeOrVariantWarnsAndFallsBackForUnsupportedStructuredTypeText() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var resolvedType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("Array[Array[int]]", SYNTHETIC_RANGE),
                registry,
                Map.of(),
                SOURCE_PATH,
                diagnostics
        );
        var diagnostic = diagnostics.snapshot().asList().getFirst();

        assertEquals(GdVariantType.VARIANT, resolvedType);
        assertEquals(1, diagnostics.snapshot().size());
        assertEquals("sema.type_resolution", diagnostic.category());
        assertTrue(diagnostic.message().contains("Array[Array[int]]"));
    }

    @Test
    void resolveTypeOrVariantWarnsAndFallsBackForCompilerOnlyDeclaredTypeTexts() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var compilerPrefixedType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef(GdccForRangeIterType.LIR_TYPE_TEXT, SYNTHETIC_RANGE),
                registry,
                Map.of(),
                SOURCE_PATH,
                diagnostics
        );
        var bareCompilerType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef(GdccForRangeIterType.FOR_RANGE_ITER.getTypeName(), SYNTHETIC_RANGE),
                registry,
                Map.of(),
                SOURCE_PATH,
                diagnostics
        );

        assertEquals(GdVariantType.VARIANT, compilerPrefixedType);
        assertEquals(GdVariantType.VARIANT, bareCompilerType);
        assertEquals(2, diagnostics.snapshot().size());
        assertTrue(diagnostics.snapshot().asList().getFirst().message().contains(GdccForRangeIterType.LIR_TYPE_TEXT));
        assertTrue(diagnostics.snapshot().asList().getLast().message().contains(GdccForRangeIterType.FOR_RANGE_ITER.getTypeName()));
    }

    @Test
    void resolveTypeOrVariantRemapsMappedTopLevelSourceNameAfterStrictLexicalMiss() throws IOException {
        var diagnostics = new DiagnosticManager();
        var registry = registryWithGdccClass("Game__sub__Player");

        var resolvedType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                new TypeRef("Player", SYNTHETIC_RANGE),
                registry,
                Map.of("Player", "Game__sub__Player"),
                SOURCE_PATH,
                diagnostics
        );

        assertEquals(new GdObjectType("Game__sub__Player"), resolvedType);
        assertTrue(diagnostics.isEmpty());
    }

    private ClassRegistry registryWithGdccClass(String className) throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef(className, "RefCounted"));
        return registry;
    }
}
