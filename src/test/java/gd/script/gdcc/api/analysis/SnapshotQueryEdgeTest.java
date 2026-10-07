package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendDeclarationOrigin;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static gd.script.gdcc.api.analysis.QueryTestSupport.offset;
import static gd.script.gdcc.api.analysis.QueryTestSupport.offsetInside;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Edge-shape tests for the snapshot query service built on HAND-CONSTRUCTED snapshots: real
/// GDScript sources cannot reliably produce collection-shaped provenance with mixed
/// normalizability or a `parse.internal` unit, so these cases assemble `FrontendAnalysisData`
/// directly and anchor the normalization contract element by element
/// (`frontend_lsp_foundation_implementation.md` §2.4).
class SnapshotQueryEdgeTest {
    private static final Path UNIT_PATH = Path.of("tmp", "edge.gd");
    private static final String DISPLAY_PATH = "/disp/edge.gd";
    private static final String SOURCE = """
            extends RefCounted
            
            func target() -> void:
                var marker = probe
            """;

    private record Harness(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull FrontendSourceUnit unit
    ) {
    }

    /// Parses SOURCE, lets `filler` seed facts into the analysis data, then publishes the
    /// snapshot (which freezes the data). Facts must be seeded BEFORE construction.
    private static @NotNull Harness harness(
            boolean parseFailed,
            @NotNull BiConsumer<FrontendAnalysisData, FrontendSourceUnit> filler
    ) throws java.io.IOException {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(UNIT_PATH, SOURCE, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "fixture must parse cleanly: " + diagnostics.snapshot());
        var module = new FrontendModule("edge_module", List.of(unit));
        var analysisData = FrontendAnalysisData.bootstrap();
        filler.accept(analysisData, unit);
        var snapshot = new ModuleAnalysisSnapshot(
                1,
                1,
                "edge_module",
                GodotVersion.V451,
                Map.of(),
                List.of(new ModuleAnalysisSnapshot.SourceView("tmp/edge.gd", DISPLAY_PATH, SOURCE, parseFailed)),
                new DiagnosticSnapshot(List.of()),
                module,
                analysisData,
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        );
        return new Harness(snapshot, unit);
    }

    private static @NotNull Node requireProbeIdentifier(@NotNull FrontendSourceUnit unit) {
        var node = QueryTestSupport.findNode(
                unit.ast(),
                candidate -> candidate instanceof IdentifierExpression identifier && identifier.name().equals("probe")
        );
        assertNotNull(node, "fixture must contain the probe identifier");
        return node;
    }

    private static @NotNull Node requireMarkerDeclaration(@NotNull FrontendSourceUnit unit) {
        var node = QueryTestSupport.findNode(
                unit.ast(),
                candidate -> candidate instanceof dev.superice.gdparser.frontend.ast.VariableDeclaration
        );
        assertNotNull(node);
        return node;
    }

    private static @NotNull Node requireFunctionDeclaration(@NotNull FrontendSourceUnit unit) {
        var node = QueryTestSupport.findNode(
                unit.ast(),
                candidate -> candidate instanceof dev.superice.gdparser.frontend.ast.FunctionDeclaration
        );
        assertNotNull(node);
        return node;
    }

    private static void bindProbe(FrontendAnalysisData data, FrontendSourceUnit unit, Object declarationSite) {
        data.symbolBindings().put(
                requireProbeIdentifier(unit),
                new FrontendBinding("probe", FrontendBindingKind.METHOD, declarationSite, null, null)
        );
    }

    @Test
    void definitionAtListsEveryCandidateOfCollectionProvenance() throws Exception {
        var modelA = new Object();
        var modelB = new Object();
        var harness = harness(false, (data, unit) -> {
            data.declarationOrigins().put(modelA, new FrontendDeclarationOrigin(requireMarkerDeclaration(unit), UNIT_PATH));
            data.declarationOrigins().put(modelB, new FrontendDeclarationOrigin(requireFunctionDeclaration(unit), UNIT_PATH));
            bindProbe(data, unit, List.of(modelA, modelB));
        });

        var result = FrontendSnapshotQueryService.definitionAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0));
        assertEquals(DeclarationLookupResult.Kind.MULTIPLE_CANDIDATES, result.kind());
        assertEquals(2, result.candidates().size());
        assertEquals(
                requireMarkerDeclaration(harness.unit()).range().startByte(),
                result.candidates().get(0).location().startByte()
        );
        assertEquals(
                requireFunctionDeclaration(harness.unit()).range().startByte(),
                result.candidates().get(1).location().startByte()
        );
        assertEquals(DISPLAY_PATH, result.candidates().get(0).location().displayPath());
    }

    @Test
    void definitionAtKeepsUnlocalizableElementsExplicitInMixedCollections() throws Exception {
        var localizable = new Object();
        var external = new Object();
        var harness = harness(false, (data, unit) -> {
            data.declarationOrigins().put(localizable, new FrontendDeclarationOrigin(requireMarkerDeclaration(unit), UNIT_PATH));
            // `external` deliberately has no provenance entry.
            bindProbe(data, unit, List.of(localizable, external));
        });

        var result = FrontendSnapshotQueryService.definitionAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0));
        assertEquals(DeclarationLookupResult.Kind.MULTIPLE_CANDIDATES, result.kind());
        assertEquals(2, result.candidates().size());
        assertNotNull(result.candidates().get(0).location(), "localizable element keeps its position");
        assertNull(result.candidates().get(1).location(), "un-localizable element is explicitly marked, not dropped");
    }

    @Test
    void definitionAtReportsExternalWhenNoCollectionElementNormalizes() throws Exception {
        var harness = harness(false, (data, unit) -> bindProbe(data, unit, List.of(new Object(), new Object())));
        var result = FrontendSnapshotQueryService.definitionAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0));
        assertEquals(DeclarationLookupResult.Kind.EXTERNAL, result.kind());
    }

    @Test
    void usagesAtCountsCollectionSitesTowardEveryNormalizableElement() throws Exception {
        var modelA = new Object();
        var modelB = new Object();
        var harness = harness(false, (data, unit) -> {
            data.declarationOrigins().put(modelA, new FrontendDeclarationOrigin(requireMarkerDeclaration(unit), UNIT_PATH));
            data.declarationOrigins().put(modelB, new FrontendDeclarationOrigin(requireFunctionDeclaration(unit), UNIT_PATH));
            // The probe identifier references the (A, B) collection; the marker declaration
            // node references A alone. Group A must therefore see both sites, group B only the
            // collection site.
            bindProbe(data, unit, List.of(modelA, modelB));
            data.symbolBindings().put(
                    requireMarkerDeclaration(unit),
                    new FrontendBinding("marker", FrontendBindingKind.LOCAL_VAR, modelA, null, null)
            );
        });

        // Querying the collection site unions both groups: A has {probe, marker}, B has {probe}.
        var usages = FrontendSnapshotQueryService.usagesAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0));
        assertEquals(2, usages.size());

        // Querying the single-element site sees only A's group — which still includes the
        // collection site because the collection counts toward every element.
        var markerUsages = FrontendSnapshotQueryService.usagesAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "marker", 0));
        assertEquals(2, markerUsages.size());
        assertTrue(markerUsages.stream().anyMatch(site -> site.startByte() == offset(SOURCE, "probe", 0)));
    }

    @Test
    void documentationAtKeepsMixedCandidatesInElementOrder() throws Exception {
        // Mixed collection provenance: the descriptor must carry the located candidate AND the
        // explicit null-location slot at its original position (§2.4 GDCC rule).
        var localizable = new Object();
        var external = new Object();
        var harness = harness(false, (data, unit) -> {
            data.declarationOrigins().put(localizable, new FrontendDeclarationOrigin(requireMarkerDeclaration(unit), UNIT_PATH));
            bindProbe(data, unit, List.of(localizable, external));
        });

        var descriptor = FrontendSnapshotQueryService.documentationAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0)).orElseThrow();
        assertEquals(DocNamespace.GDCC, descriptor.namespace());
        assertEquals(2, descriptor.sourceCandidates().size());
        assertNotNull(descriptor.sourceCandidates().get(0).location());
        assertNull(descriptor.sourceCandidates().get(1).location(), "un-localizable element keeps an explicit slot");
        assertEquals(
                requireMarkerDeclaration(harness.unit()).range().startByte(),
                descriptor.sourceCandidates().get(0).location().startByte()
        );
    }

    @Test
    void classConstantWrapperNormalizesAndClassifiesByItsEnumDeclaration() throws Exception {
        // The skeleton records provenance for GdScriptEnumConstant, not for the
        // GdScriptClassConstant wrapper: normalization unwraps it, and the descriptor kind must
        // follow the wrapped declaration (ENUM_VALUE), not the wrapper (CONSTANT).
        var enumConstant = new GdScriptEnumConstant("IDLE", 0L, null, "edge_module");
        var wrapper = new GdScriptClassConstant("IDLE", GdVariantType.VARIANT, enumConstant);
        var harness = harness(false, (data, unit) -> {
            data.declarationOrigins().put(enumConstant, new FrontendDeclarationOrigin(requireMarkerDeclaration(unit), UNIT_PATH));
            bindProbe(data, unit, wrapper);
        });

        var definition = FrontendSnapshotQueryService.definitionAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0));
        assertEquals(DeclarationLookupResult.Kind.SINGLE_SOURCE, definition.kind());
        assertEquals(
                requireMarkerDeclaration(harness.unit()).range().startByte(),
                definition.candidates().getFirst().location().startByte()
        );

        var descriptor = FrontendSnapshotQueryService.documentationAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0)).orElseThrow();
        assertEquals(DocSymbolKind.ENUM_VALUE, descriptor.symbolKind());
        assertEquals(DocNamespace.GDCC, descriptor.namespace());
    }

    @Test
    void blockedValueBindingsProduceNoDescriptorButRemainUsages() throws Exception {
        // Statement-level declaration-before-use publishes UNKNOWN/null-site bindings; the
        // FOUND_BLOCKED-with-provenance shape (parameter-default islands, static contexts) is
        // seeded directly: it stays in usages (usage intent) but produces no descriptor.
        var harness = harness(false, (data, unit) -> {
            var declaration = requireMarkerDeclaration(unit);
            // FOUND_BLOCKED requires resolvedValue/valueAccessStatus recorded together.
            var resolvedValue = new gd.script.gdcc.scope.ScopeValue(
                    "probe", GdVariantType.VARIANT, gd.script.gdcc.scope.ScopeValueKind.LOCAL, declaration, false, true, false);
            data.symbolBindings().put(
                    requireProbeIdentifier(unit),
                    new FrontendBinding("probe", FrontendBindingKind.LOCAL_VAR, declaration, resolvedValue,
                            gd.script.gdcc.scope.ScopeLookupStatus.FOUND_BLOCKED)
            );
        });
        var descriptor = FrontendSnapshotQueryService.documentationAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0));
        assertTrue(descriptor.isEmpty(), "FOUND_BLOCKED bindings must produce no descriptor");
        var usages = FrontendSnapshotQueryService.usagesAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0));
        assertEquals(1, usages.size(), () -> "the blocked site remains a usage, got " + usages);
        assertEquals(offset(SOURCE, "probe", 0), usages.getFirst().startByte());
    }

    @Test
    void parseFailedUnitsAnswerEveryQueryWithEmptyResults() throws Exception {
        // The fixture parses cleanly; the parseFailed flag simulates a parse.internal unit,
        // whose synthetic AST must never leak into query results
        // (`frontend_lsp_foundation_implementation.md` §2.2.5).
        var harness = harness(true, (data, unit) -> bindProbe(data, unit, new Object()));

        assertNull(FrontendSnapshotQueryService.nodeAt(harness.snapshot(), DISPLAY_PATH, 0));
        assertEquals(
                DeclarationLookupResult.Kind.NONE,
                FrontendSnapshotQueryService.definitionAt(harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0)).kind()
        );
        assertTrue(FrontendSnapshotQueryService.usagesAt(harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0)).isEmpty());
        assertTrue(FrontendSnapshotQueryService.typeAt(harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0)).isEmpty());
        assertTrue(FrontendSnapshotQueryService.documentationAt(harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0)).isEmpty());
    }

    @Test
    void snapshotKeepsSeededFactsQueryableAfterFreeze() throws Exception {
        // Sanity anchor: the structural freeze at publication must not break reads seeded
        // before publication (the query service relies on frozen-but-readable tables).
        var model = new Object();
        var harness = harness(false, (data, unit) -> {
            data.declarationOrigins().put(model, new FrontendDeclarationOrigin(requireMarkerDeclaration(unit), UNIT_PATH));
            bindProbe(data, unit, model);
        });
        var result = FrontendSnapshotQueryService.definitionAt(
                harness.snapshot(), DISPLAY_PATH, offsetInside(SOURCE, "probe", 0));
        assertEquals(DeclarationLookupResult.Kind.SINGLE_SOURCE, result.kind());
        assertEquals(
                requireMarkerDeclaration(harness.unit()).range().startByte(),
                result.candidates().getFirst().location().startByte()
        );
    }
}
