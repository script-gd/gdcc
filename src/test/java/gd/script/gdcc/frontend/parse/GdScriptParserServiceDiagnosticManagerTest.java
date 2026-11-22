package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.AstDiagnostic;
import dev.superice.gdparser.frontend.ast.AstDiagnosticSeverity;
import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.RecoveryLink;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendPoint;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendSourceClassRelation;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdScriptParserServiceDiagnosticManagerTest {
    private static final String MIXED_TOOL_SOURCE = """
            @tool
            @tool(1)
            class_name MixedTool
            extends Node
            """;
    private static final Path MIXED_TOOL_PATH = Path.of("tmp", "mixed_tool.gd");

    @Test
    void parseUnitKeepsManagerAndRecoveriesEmptyForWellFormedScript() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();

        var unit = parserService.parseUnit(Path.of("tmp", "ok.gd"), """
                class_name Player
                extends Node
                
                func _ready():
                    print("ok")
                """, diagnostics);

        assertNotNull(unit.ast());
        assertFalse(unit.parseFailed());
        assertTrue(unit.parseRecoveries().isEmpty());
        assertTrue(diagnostics.isEmpty());
        assertFalse(diagnostics.hasErrors());
    }

    @Test
    void parseUnitPublishesMissingInitializerUnderItsOriginalOwner() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "broken.gd"), "var x =\n", diagnostics);
        var expected = FrontendDiagnostic.error(
                DiagnosticCodes.SLOT_VARIABLE_INITIALIZER, "Expected expression for variable initial value after \"=\".",
                "tmp/broken.gd", new FrontendRange(6, 7, new FrontendPoint(1, 7), new FrontendPoint(1, 8))
        );
        var owner = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());

        assertInstanceOf(ErrorExpression.class, owner.value());
        assertFalse(unit.parseFailed());
        assertEquals(List.of(expected), diagnostics.snapshot().asList());
        assertEquals(
                List.of(new FrontendParseRecovery(
                        expected.category(), expected.severity(), expected.message(), expected.range(), owner, "variable.initializer"
                )),
                unit.parseRecoveries()
        );
        assertSame(owner, unit.parseRecoveries().getFirst().owner());
    }

    @Test
    void parseUnitKeepsOrphanCloserStandaloneAndDoesNotRewriteEarlierManagerSnapshots() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();

        parserService.parseUnit(Path.of("tmp", "first.gd"), "var x =\n", diagnostics);
        var firstSnapshot = diagnostics.snapshot();

        var second = parserService.parseUnit(Path.of("tmp", "second.gd"), "var a = 1; )\nvar after = 1\n", diagnostics);
        var expected = FrontendDiagnostic.error(
                DiagnosticCodes.UNEXPECTED_CLOSER, "Unexpected closing \")\".",
                "tmp/second.gd", new FrontendRange(11, 12, new FrontendPoint(1, 12), new FrontendPoint(1, 13))
        );

        assertEquals(1, firstSnapshot.size());
        assertEquals(DiagnosticCodes.SLOT_VARIABLE_INITIALIZER, firstSnapshot.getFirst().category());
        assertEquals("tmp/first.gd", firstSnapshot.getFirst().sourcePath());
        assertEquals(List.of(firstSnapshot.getFirst(), expected), diagnostics.snapshot().asList());
        assertEquals(2, second.ast().statements().size());
        assertEquals("after", assertInstanceOf(VariableDeclaration.class, second.ast().statements().getLast()).name());
        assertEquals(
                List.of(new FrontendParseRecovery(
                        expected.category(), expected.severity(), expected.message(), expected.range(), null, null
                )),
                second.parseRecoveries()
        );
    }

    @Test
    void parseUnitRejectsLegacyBaseArgumentsButRetainsTheConstructorHeader() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var sourcePath = Path.of("tmp", "legacy_constructor_header.gd");

        var unit = parserService.parseUnit(sourcePath, """
                class_name LegacyConstructorHeader
                extends Node
                
                func _init(seed).(seed):
                    pass
                """, diagnostics);
        var constructor = assertInstanceOf(ConstructorDeclaration.class, unit.ast().statements().get(2));
        assertEquals("seed", constructor.parameters().getFirst().name());
        assertTrue(constructor.body().statements().isEmpty());
        assertEquals(
                List.of(
                        FrontendDiagnostic.error(
                                DiagnosticCodes.STATEMENT_TRAILING,
                                "Expected end of statement after bodyless function declaration, found \".\" instead.",
                                "tmp/legacy_constructor_header.gd",
                                new FrontendRange(65, 73, new FrontendPoint(4, 17), new FrontendPoint(4, 25))
                        ),
                        FrontendDiagnostic.error(
                                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, "Unexpected \"Indent\" in class body.",
                                "tmp/legacy_constructor_header.gd",
                                new FrontendRange(74, 78, new FrontendPoint(5, 1), new FrontendPoint(5, 5))
                        )
                ),
                diagnostics.snapshot().asList()
        );
        assertEquals(2, unit.parseRecoveries().size());
        assertSame(constructor, unit.parseRecoveries().getFirst().owner());
        assertEquals("statement.trailing", unit.parseRecoveries().getFirst().facet());
        assertNull(unit.parseRecoveries().getLast().owner());
        assertNull(unit.parseRecoveries().getLast().facet());
    }

    @Test
    void parseUnitPreservesDiagnosticOrderAndSeverityAndFallsBackOnlyForMissingCodes() {
        var source = "var x = 1\n";
        var ast = new GdScriptParserFacade().parse(source).ast();
        var owner = ast.statements().getFirst();
        var range = new Range(4, 5, new Point(0, 4), new Point(0, 5));
        var parserService = new GdScriptParserService(input -> {
            assertEquals(source, input);
            return new AstMappingResult(ast, List.of(
                    new AstDiagnostic(AstDiagnosticSeverity.ERROR, "owned error", "ERROR", range,
                            DiagnosticCodes.SLOT_VARIABLE_INITIALIZER, new RecoveryLink(owner, "variable.initializer")),
                    new AstDiagnostic(AstDiagnosticSeverity.WARNING, "standalone warning", "WARNING", null,
                            "parse.test.warning", null),
                    new AstDiagnostic(AstDiagnosticSeverity.ERROR, "missing code", "ERROR", range),
                    new AstDiagnostic(AstDiagnosticSeverity.WARNING, "blank code", "WARNING", range, " \t", null)
            ));
        });
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "injected.gd"), source, diagnostics);
        var sourcePath = "tmp/injected.gd";
        var frontendRange = new FrontendRange(4, 5, new FrontendPoint(1, 5), new FrontendPoint(1, 6));
        var expected = List.of(
                FrontendDiagnostic.error(DiagnosticCodes.SLOT_VARIABLE_INITIALIZER, "owned error", sourcePath, frontendRange),
                FrontendDiagnostic.warning("parse.test.warning", "standalone warning", sourcePath, null),
                FrontendDiagnostic.error("parse.lowering", "missing code", sourcePath, frontendRange),
                FrontendDiagnostic.warning("parse.lowering", "blank code", sourcePath, frontendRange)
        );

        assertSame(ast, unit.ast());
        assertFalse(unit.parseFailed());
        assertEquals(expected, diagnostics.snapshot().asList());
        assertEquals(expected.size(), unit.parseRecoveries().size());
        for (var i = 0; i < expected.size(); i++) {
            var diagnostic = expected.get(i);
            var recovery = unit.parseRecoveries().get(i);
            assertEquals(diagnostic.category(), recovery.code());
            assertEquals(diagnostic.severity(), recovery.severity());
            assertEquals(diagnostic.message(), recovery.message());
            assertEquals(diagnostic.range(), recovery.range());
            assertSame(i == 0 ? owner : null, recovery.owner());
            assertEquals(i == 0 ? "variable.initializer" : null, recovery.facet());
        }
    }

    @Test
    void parseUnitWrapsParserBackendFailureAsInternalDiagnostic() {
        var diagnostics = new DiagnosticManager();

        var unit = brokenParserService().parseUnit(Path.of("tmp", "internal_failure.gd"), "class_name Broken", diagnostics);

        assertEquals(List.of(FrontendDiagnostic.error(
                "parse.internal", "Unexpected parser failure: boom", "tmp/internal_failure.gd", null
        )), diagnostics.snapshot().asList());
        assertTrue(unit.parseFailed());
        assertTrue(unit.ast().statements().isEmpty());
        assertTrue(unit.parseRecoveries().isEmpty());
    }

    @Test
    void sourceUnitCopiesRecoveriesAndRejectsInvalidAttribution() {
        var path = Path.of("tmp", "unit.gd");
        var source = "var x =\n";
        var parsed = new GdScriptParserService().parseUnit(path, source, new DiagnosticManager());
        var owner = parsed.ast().statements().getFirst();
        var recovery = parsed.parseRecoveries().getFirst();
        var mutableRecoveries = new ArrayList<>(parsed.parseRecoveries());
        var unit = new FrontendSourceUnit(path, source, parsed.ast(), false, mutableRecoveries);

        mutableRecoveries.clear();
        assertEquals(List.of(recovery), unit.parseRecoveries());
        assertThrows(UnsupportedOperationException.class, () -> unit.parseRecoveries().add(recovery));
        assertThrows(IllegalArgumentException.class,
                () -> new FrontendSourceUnit(path, source, parsed.ast(), true, List.of(recovery)));
        assertThrows(IllegalArgumentException.class, () -> new FrontendParseRecovery(
                recovery.code(), recovery.severity(), recovery.message(), recovery.range(), owner, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new FrontendParseRecovery(
                recovery.code(), recovery.severity(), recovery.message(), recovery.range(), null, recovery.facet()
        ));
    }

    @Test
    void analyzeRestoresMissingErrorsAndFreezesOwnerBucketsWithoutLosingFailedUnits() throws IOException {
        var parserService = new GdScriptParserService();
        var parseManager = new DiagnosticManager();
        var owned = parserService.parseUnit(MIXED_TOOL_PATH, MIXED_TOOL_SOURCE, parseManager);
        var ownedDiagnostics = parseManager.snapshot();
        assertEquals(2, ownedDiagnostics.size());
        var standalone = parserService.parseUnit(
                Path.of("tmp", "standalone.gd"), "var a = 1; )\nvar after = 1\n", parseManager
        );
        var failed = brokenParserService().parseUnit(
                Path.of("tmp", "failed.gd"), "class_name Failed", new DiagnosticManager()
        );
        var units = List.of(owned, standalone, failed);
        var fresh = new DiagnosticManager();
        // Leave only the standalone and failed-unit errors missing at the analysis boundary.
        fresh.reportAll(ownedDiagnostics.asList());
        var analysis = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("recovery", units), new ClassRegistry(ExtensionApiLoader.loadDefault()), fresh
        );
        var expected = new ArrayList<>(parseManager.snapshot().asList());
        expected.add(FrontendDiagnostic.error("parse.internal", "Unexpected parser failure.", "tmp/failed.gd", null));
        assertEquals(expected, parseDiagnostics(analysis.diagnostics()));
        assertEquals(analysis.diagnostics(), fresh.snapshot());
        assertEquals(units, analysis.sourceUnits());
        assertEquals(units, analysis.effectiveSourceUnits());
        var skeletonUnits = List.of(owned, standalone);
        assertEquals(
                skeletonUnits,
                analysis.moduleSkeleton().sourceClassRelations().stream().map(FrontendSourceClassRelation::unit).toList()
        );

        var handBuilt = FrontendAnalysisData.bootstrap();
        handBuilt.updateModuleSkeleton(analysis.moduleSkeleton());
        assertEquals(skeletonUnits, handBuilt.effectiveSourceUnits());
        handBuilt.updateSourceUnits(List.of());
        assertTrue(handBuilt.effectiveSourceUnits().isEmpty());

        var owner = owned.ast().statements().get(1);
        var equalOwner = new GdScriptParserFacade().parse(MIXED_TOOL_SOURCE).ast().statements().get(1);
        assertEquals(owner, equalOwner);
        assertNotSame(owner, equalOwner);
        assertNull(analysis.parseRecoveriesByOwner().get(equalOwner));
        assertEquals(1, analysis.parseRecoveriesByOwner().size());

        var bucket = analysis.parseRecoveriesByOwner().get(owner);
        assertNotNull(bucket);
        assertEquals(owned.parseRecoveries(), bucket);
        var first = owned.parseRecoveries().getFirst();
        assertSame(first, bucket.getFirst());
        assertSame(owned.parseRecoveries().getLast(), bucket.getLast());
        assertSame(owner, first.owner());

        var propagatedNode = standalone.ast().statements().getLast();
        analysis.recordPropagatedFailureOrigin(propagatedNode, owner, first);
        analysis.claimParseRecovery(first, expected.getFirst());
        var claims = analysis.claimedParseRecoveries();
        analysis.freeze();
        assertSame(owner, analysis.propagatedFailureOrigins().get(propagatedNode).primaryAnchor());
        assertSame(first, analysis.propagatedFailureOrigins().get(propagatedNode).originRecovery());
        assertThrows(UnsupportedOperationException.class, bucket::clear);
        assertThrows(UnsupportedOperationException.class, claims::clear);
        assertThrows(IllegalStateException.class, () -> analysis.updateSourceUnits(List.of()));
        assertThrows(IllegalStateException.class, () -> analysis.claimParseRecovery(first, expected.getFirst()));
        assertThrows(IllegalStateException.class, () -> analysis.parseRecoveriesByOwner().put(owner, List.of()));
        assertThrows(IllegalStateException.class, () -> analysis.propagatedFailureOrigins().remove(propagatedNode));
    }

    @Test
    void restoringWithDifferentManagersUsesExactActiveErrorsAndDoesNotRewriteClaims() {
        var parsedDiagnostics = new DiagnosticManager();
        var parsed = new GdScriptParserService().parseUnit(MIXED_TOOL_PATH, MIXED_TOOL_SOURCE, parsedDiagnostics);
        var first = parsed.parseRecoveries().getFirst();
        var second = parsed.parseRecoveries().getLast();
        var original = parsedDiagnostics.snapshot().asList();
        assertEquals(2, original.size());
        assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, first.code());
        assertEquals(first.code(), second.code());
        assertEquals(first.range(), second.range());
        assertEquals("Annotation \"@tool\" requires at most 0 arguments, but 1 were given.", first.message());
        assertEquals("\"@tool\" annotation can only be used once.", second.message());

        var analysis = FrontendAnalysisData.bootstrap();
        var warningRecovery = new FrontendParseRecovery(
                first.code(), FrontendDiagnosticSeverity.WARNING, "standalone warning", first.range(), null, null
        );
        analysis.updateSourceUnits(List.of(new FrontendSourceUnit(
                parsed.path(), parsed.source(), parsed.ast(), false, List.of(first, second, warningRecovery)
        )));

        var sourcePath = "tmp/mixed_tool.gd";
        var claimed = FrontendDiagnostic.error("sema.annotation_usage", "covers both", sourcePath, first.range());
        var claimedWarning = FrontendDiagnostic.warning("sema.annotation_usage", "covers both", sourcePath, first.range());
        analysis.claimParseRecovery(first, claimedWarning);
        assertNull(FrontendParseRecovery.findActiveCoveringDiagnostic(first, analysis, Set.of(claimedWarning), sourcePath));
        analysis.claimParseRecovery(first, claimed);
        analysis.claimParseRecovery(second, claimed);
        var equalFirst = new FrontendParseRecovery(
                first.code(), first.severity(), first.message(), first.range(), first.owner(), first.facet()
        );
        assertEquals(first, equalFirst);
        assertNull(analysis.claimedParseRecoveries().get(equalFirst));
        assertNull(FrontendParseRecovery.findActiveCoveringDiagnostic(second, analysis, Set.of(original.getFirst()), sourcePath));

        var managerA = new DiagnosticManager();
        managerA.report(claimed);
        FrontendParseRecovery.ensureUncoveredParseDiagnosticsReported(analysis, managerA);
        assertEquals(List.of(claimed), managerA.snapshot().asList());

        var managerB = new DiagnosticManager();
        var parseWarning = FrontendDiagnostic.warning(first.code(), first.message(), sourcePath, first.range());
        managerB.report(parseWarning);
        var activeB = new HashSet<>(managerB.snapshot().asList());
        FrontendParseRecovery.ensureUncoveredParseDiagnosticsReported(analysis, managerB, activeB);
        assertEquals(List.of(parseWarning, original.getFirst(), original.getLast()), managerB.snapshot().asList());
        assertEquals(Set.copyOf(managerB.snapshot().asList()), activeB);
        FrontendParseRecovery.ensureUncoveredParseDiagnosticsReported(analysis, managerB, activeB);
        assertEquals(3, managerB.snapshot().size());

        analysis.updateDiagnostics(managerB.snapshot());
        FrontendParseRecovery.ensureUncoveredParseDiagnosticsReported(analysis, managerA);
        assertEquals(List.of(claimed), managerA.snapshot().asList());
        assertSame(claimed, analysis.claimedParseRecoveries().get(first));
        assertSame(claimed, analysis.claimedParseRecoveries().get(second));
    }

    @Test
    void restoringFailedUnitsPrefersFirstPublishedErrorForThatFile() {
        var parserService = brokenParserService();
        var path = Path.of("tmp", "first.gd");
        var previous = new DiagnosticManager();
        var warning = FrontendDiagnostic.warning("parse.internal", "not blocking", "tmp/first.gd", null);
        previous.report(warning);
        var first = parserService.parseUnit(path, "", previous);
        var original = previous.snapshot().getLast();
        previous.report(FrontendDiagnostic.error("parse.internal", "later error", "tmp/first.gd", null));
        var second = new FrontendSourceUnit(Path.of("tmp", "second.gd"), "", first.ast(), true);

        var analysis = FrontendAnalysisData.bootstrap();
        analysis.updateSourceUnits(List.of(first, second));
        analysis.updateDiagnostics(previous.snapshot());
        var fresh = new DiagnosticManager();
        fresh.report(warning);
        FrontendParseRecovery.ensureUncoveredParseDiagnosticsReported(analysis, fresh);
        FrontendParseRecovery.ensureUncoveredParseDiagnosticsReported(analysis, fresh);

        assertEquals(List.of(
                warning,
                original,
                FrontendDiagnostic.error("parse.internal", "Unexpected parser failure.", "tmp/second.gd", null)
        ), fresh.snapshot().asList());
    }

    private static List<FrontendDiagnostic> parseDiagnostics(DiagnosticSnapshot snapshot) {
        return snapshot.asList().stream().filter(diagnostic -> diagnostic.category().startsWith("parse.")).toList();
    }

    private static GdScriptParserService brokenParserService() {
        return new GdScriptParserService(_ -> {
            throw new IllegalStateException("boom");
        });
    }
}
