package gd.script.gdcc.api;

import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Runner-level coverage for the fault-tolerance contract (LSP foundation plan Phase 2 acceptance
/// 4-6): parse errors no longer starve semantic facts, lowering verification runs isolated from the
/// shared generation, and unexpected exceptions collapse into `INTERNAL_FAILED` without publishing
/// half-committed payloads (R14).
class AnalysisRunnerRecoveryTest {
    private static final @NotNull String HEADER = """
            class_name RunnerRecover
            extends RefCounted
            
            """;
    private static final @NotNull String BROKEN_SOURCE = HEADER + """
            func f():
                if :
                    pass
                var y = 2
            """;
    private static final @NotNull String CLEAN_SOURCE = HEADER + """
            enum State { IDLE, RUNNING }
            var hp = 1
            var mystery: NotAType
            func f():
                var y = 2
                return
            """;

    @Test
    void parseErrorsStillPublishSemanticPayloadWithoutLowering() {
        var runResult = newRunner().analyzeRich(frozenRequest(BROKEN_SOURCE), AnalyzeOptions.defaults());

        var result = runResult.result();
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome()),
                () -> assertEquals(AnalysisResult.LoweringStatus.NOT_REQUESTED, result.loweringStatus()),
                () -> assertTrue(hasError(result.diagnostics(), "parse.lowering")),
                () -> assertTrue(categories(result.diagnostics()).noneMatch("sema.compile_check"::equals)),
                () -> assertHealthyPayload(runResult)
        );
    }

    @Test
    void parseErrorsSkipLoweringButKeepSemanticPayload() {
        var runResult = newRunner().analyzeRich(frozenRequest(BROKEN_SOURCE), loweringOptions());

        var result = runResult.result();
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome()),
                () -> assertEquals(AnalysisResult.LoweringStatus.FAILED, result.loweringStatus()),
                () -> assertTrue(hasError(result.diagnostics(), "parse.lowering")),
                () -> assertTrue(categories(result.diagnostics()).noneMatch("sema.compile_check"::equals),
                        "parse-error runs must not surface compile-only diagnostics"),
                () -> assertTrue(categories(payload(runResult).snapshotDiagnostics())
                        .noneMatch("sema.compile_check"::equals)),
                () -> assertHealthyPayload(runResult)
        );
    }

    @Test
    void cleanModuleLoweringVerificationUsesIsolatedRegistry() {
        var runResult = newRunner().analyzeRich(frozenRequest(CLEAN_SOURCE), loweringOptions());

        var result = runResult.result();
        var payload = payload(runResult);
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome()),
                () -> assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, result.loweringStatus(),
                        () -> result.diagnostics().asList().toString()),
                () -> assertTrue(categories(result.diagnostics()).noneMatch("sema.compile_check"::equals)),
                () -> assertTrue(categories(payload.snapshotDiagnostics()).noneMatch("sema.compile_check"::equals)),
                // Non-vacuous dedup anchor: the unknown type produces exactly one
                // `sema.type_resolution` warning per diagnostics view (the lowering run imports
                // parse diagnostics once and reruns sema once).
                () -> assertEquals(1, result.diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.type_resolution"))
                        .count()),
                () -> assertEquals(
                        result.diagnostics().asList().stream().map(AnalysisRunnerRecoveryTest::diagnosticKey).distinct().count(),
                        (long) result.diagnostics().asList().size(),
                        "lowering rerun must not duplicate shared semantic diagnostics"
                )
        );
        // The lowering verification reruns the skeleton on its own registry; the shared registry
        // must still hold the exact class object the shared run created (identity anchor for the
        // generation-isolation rule of plan §2.2.6).
        var sharedClassDef = payload.analysisData()
                .moduleSkeleton()
                .sourceClassRelations()
                .getFirst()
                .topLevelClassDef();
        assertAll(
                () -> assertSame(
                        sharedClassDef,
                        payload.classRegistry().findGdccClass(sharedClassDef.getName()),
                        "lowering verification must not replace shared-run registry classes"
                ),
                () -> assertTrue(payload.analysisData().declarationOrigins().containsKey(sharedClassDef))
        );
        // Declaration provenance identity anchors (plan §2.4): every source-created declaration
        // model resolves back to its AST declaration node in the same unit.
        var origins = payload.analysisData().declarationOrigins();
        var propertyDef = sharedClassDef.getProperties().stream()
                .filter(property -> property.getName().equals("hp"))
                .findFirst()
                .orElseThrow();
        var functionDef = sharedClassDef.getFunctions().stream()
                .filter(function -> function.getName().equals("f"))
                .findFirst()
                .orElseThrow();
        var stateGroupConstant = sharedClassDef.getScriptConstants().stream()
                .filter(constant -> constant.name().equals("State"))
                .findFirst()
                .orElseThrow();
        var idleConstant = sharedClassDef.getScriptConstants().stream()
                .flatMap(constant -> constant.declaration() instanceof GdScriptEnumGroup group
                        ? group.members().stream()
                        : Stream.empty())
                .filter(member -> member.memberName().equals("IDLE"))
                .findFirst()
                .orElseThrow();
        assertAll(
                () -> assertInstanceOf(VariableDeclaration.class, origins.get(propertyDef).declarationNode()),
                () -> assertInstanceOf(FunctionDeclaration.class, origins.get(functionDef).declarationNode()),
                () -> assertInstanceOf(EnumDeclaration.class, origins.get(stateGroupConstant.declaration()).declarationNode()),
                () -> assertTrue(origins.get(propertyDef).sourcePath().toString().endsWith("main.gd"))
        );
        // The enum member provenance must point at the exact `IDLE` member node, not just any node.
        var enumDeclaration = payload.module().units().getFirst().ast().statements().stream()
                .filter(EnumDeclaration.class::isInstance)
                .map(EnumDeclaration.class::cast)
                .findFirst()
                .orElseThrow();
        var idleMember = enumDeclaration.members().stream()
                .filter(member -> member.name().equals("IDLE"))
                .findFirst()
                .orElseThrow();
        assertSame(idleMember, origins.get(idleConstant).declarationNode());
    }

    @Test
    void blankMatchBindingNameNeverEscapesIntoVariableInventory() {
        // gdparser maps `var :` to a blank-named PatternBindingExpression (not an error node); the
        // annotator must mark the enclosing match damaged so the run stays COMPLETED instead of
        // collapsing into INTERNAL_FAILED on the blank name.
        var runResult = newRunner().analyzeRich(frozenRequest(HEADER + """
                func f():
                    match 1:
                        var :
                            pass
                    var y = 2
                """), AnalyzeOptions.defaults());

        var result = runResult.result();
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome()),
                () -> assertTrue(hasError(result.diagnostics(), "parse.lowering")),
                () -> assertHealthyPayload(runResult)
        );
    }

    @Test
    void blankNestedMatchBindingNameNeverEscapesIntoVariableInventory() {
        var runResult = newRunner().analyzeRich(frozenRequest(HEADER + """
                func f():
                    match {0: 1}:
                        {0: var }:
                            pass
                    var y = 2
                """), AnalyzeOptions.defaults());

        var result = runResult.result();
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome()),
                () -> assertTrue(hasError(result.diagnostics(), "parse.lowering")),
                () -> assertHealthyPayload(runResult)
        );
    }

    @Test
    void semanticCrashCollapsesToInternalFailedWithoutPayload() {
        var runner = new AnalysisRunner(
                new GdScriptParserService(),
                (_, _, _) -> {
                    throw new IllegalStateException("simulated sema crash");
                },
                (_, _, _) -> null
        );
        var runResult = runner.analyzeRich(frozenRequest(CLEAN_SOURCE), AnalyzeOptions.defaults());

        var result = runResult.result();
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.INTERNAL_FAILED, result.outcome()),
                () -> assertNull(runResult.payload(), "no half-committed payload may escape (R14)"),
                () -> assertNotNull(result.failureMessage()),
                () -> assertTrue(result.failureMessage().contains("Semantic analysis failed unexpectedly"))
        );
    }

    @Test
    void loweringCrashKeepsCompletedOutcomeAndSharedPayload() {
        var runner = new AnalysisRunner(
                new GdScriptParserService(),
                (module, registry, manager) -> new FrontendSemanticAnalyzer().analyze(module, registry, manager),
                (_, _, _) -> {
                    throw new IllegalStateException("simulated lowering crash");
                }
        );
        var runResult = runner.analyzeRich(frozenRequest(CLEAN_SOURCE), loweringOptions());

        var result = runResult.result();
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome()),
                () -> assertEquals(AnalysisResult.LoweringStatus.FAILED, result.loweringStatus()),
                // The crash stays observable: lowering-side diagnostics carry the failure reason.
                () -> assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                                diagnostic.category().equals("sema.lowering")
                                        && diagnostic.message().contains("simulated lowering crash")),
                        () -> "lowering crash must surface a sema.lowering diagnostic: "
                                + result.diagnostics().asList()),
                () -> assertHealthyPayload(runResult)
        );
    }

    /// Anchors that the shared semantic run published facts for the healthy sibling statement
    /// (`var y = 2` in `f`) even when another statement is parser-damaged.
    private static void assertHealthyPayload(@NotNull AnalysisRunResult runResult) {
        var payload = payload(runResult);
        var function = payload.module().units().getFirst().ast().statements().stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .filter(candidate -> candidate.name().equals("f"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("function 'f' not found"));
        var bodyScope = assertInstanceOf(
                BlockScope.class,
                payload.analysisData().scopesByAst().get(function.body())
        );
        assertNotNull(bodyScope.resolveValueHere("y"), "healthy sibling local must be bound");
    }

    private static AnalysisRunResult.@NotNull Payload payload(@NotNull AnalysisRunResult runResult) {
        return Objects.requireNonNull(runResult.payload(), "expected a semantic payload");
    }

    private static boolean hasError(@NotNull DiagnosticSnapshot diagnostics, @NotNull String category) {
        return diagnostics.asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals(category)
                        && diagnostic.severity() == FrontendDiagnosticSeverity.ERROR);
    }

    private static Stream<String> categories(@NotNull DiagnosticSnapshot diagnostics) {
        return diagnostics.asList().stream().map(FrontendDiagnostic::category);
    }

    private static @NotNull String diagnosticKey(@NotNull FrontendDiagnostic diagnostic) {
        return diagnostic.category() + "|" + diagnostic.severity() + "|" + diagnostic.sourcePath()
                + "|" + diagnostic.range() + "|" + diagnostic.message();
    }

    private static @NotNull AnalysisRunner newRunner() {
        return new AnalysisRunner(new GdScriptParserService());
    }

    private static @NotNull AnalyzeOptions loweringOptions() {
        return new AnalyzeOptions(true);
    }

    private static @NotNull ModuleState.CompileRequest frozenRequest(@NotNull String source) {
        var state = new ModuleState("recovery_module", "recovery_module", Clock.systemUTC(), 1L);
        state.putFile(VirtualPath.parse("/main.gd"), source);
        return state.freezeCompileRequest();
    }
}
