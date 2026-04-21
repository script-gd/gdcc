package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendCompileCheckAnalyzerTest {
    @Test
    void analyzeRejectsMissingModuleSkeletonBoundary() {
        var analyzer = new FrontendCompileCheckAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(analysisData, new DiagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("moduleSkeleton"));
    }

    @Test
    void analyzeRejectsMissingDiagnosticsBoundary() throws Exception {
        var preparedInput = prepareCompileCheckInput("missing_compile_check_diagnostics.gd", """
                class_name MissingCompileCheckDiagnostics
                extends Node
                
                func ping():
                    pass
                """);
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateModuleSkeleton(preparedInput.analysisData().moduleSkeleton());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCompileCheckAnalyzer().analyze(analysisData, preparedInput.diagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("diagnostics"));
    }

    @Test
    void analyzeForCompileReportsExplicitCompileBlocksWhileAnalyzeLeavesSharedDiagnosticsUntouched() throws Exception {
        var source = """
                class_name CompileCheckExplicitBlocks
                extends Node
                
                var property_array = [1]
                var property_preload = preload("res://icon.svg")
                
                func ping(value):
                    assert(value, "compile-only gate")
                    1 if value else 0
                    {"hp": 1}
                    $Camera3D
                    value as String
                    value is String
                """;

        var sharedAnalyzed = analyzeShared("compile_check_explicit_blocks.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.type_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.unsupported_expression_route").isEmpty());

        var compiled = analyzeForCompile("compile_check_explicit_blocks.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertEquals(8, compileDiagnostics.size());
        assertTrue(compileDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && Path.of("tmp", "compile_check_explicit_blocks.gd").equals(diagnostic.sourcePath())
                        && diagnostic.range() != null
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("assert statement")));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Conditional expression")));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Array literal")));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Dictionary literal")));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Preload expression")));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Get-node expression")));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Cast expression")));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Type-test expression")));
        assertEquals(compiled.diagnostics(), compiled.diagnosticManager().snapshot());
    }

    @Test
    void analyzeForCompileBlocksAssertWithoutReclassifyingSharedConditionContract() throws Exception {
        var source = """
                class_name CompileCheckAssertContract
                extends RefCounted
                
                func ping():
                    assert(1, "frontend still accepts truthy source conditions")
                """;

        var sharedAnalyzed = analyzeShared("compile_check_assert_contract.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.type_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_assert_contract.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertEquals(1, compileDiagnostics.size());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.type_check").isEmpty());
        assertTrue(compileDiagnostics.getFirst().message().contains("assert statement"));
    }

    @Test
    void analyzeForCompileTreatsVariableInventoryErrorsAsCompileBlockingWithoutSynthesizingCompileCheckDuplicates()
            throws Exception {
        var compiled = analyzeForCompile("compile_check_duplicate_local.gd", """
                class_name CompileCheckDuplicateLocal
                extends RefCounted
                
                func ping():
                    var value := 1
                    var value := 2
                    return value
                """);

        var variableDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.variable_binding");
        var slotPublicationWarnings = diagnosticsByCategory(
                compiled.diagnostics(),
                FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY
        );
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, variableDiagnostics.size());
        assertTrue(variableDiagnostics.getFirst().message().contains("Duplicate local variable 'value'"));
        assertTrue(variableDiagnostics.getFirst().message().contains(Path.of("tmp", "compile_check_duplicate_local.gd").toString()));
        assertEquals(1, slotPublicationWarnings.size());
        assertTrue(slotPublicationWarnings.getFirst().message().contains("has no lowering-ready published slot type"));
        assertEquals(1, compileDiagnostics.size());
        assertTrue(compileDiagnostics.getFirst().message().contains("missing a lowering-ready published slot type"));
    }

    @Test
    void analyzeForCompileEscalatesShadowingLocalSlotTypeWarningIntoCompileBlock() throws Exception {
        var compiled = analyzeForCompile("compile_check_shadowing_local.gd", """
                class_name CompileCheckShadowingLocal
                extends RefCounted
                
                func ping(seed: int):
                    var value := seed
                    if seed > 0:
                        var value := 1
                    return value
                """);

        var slotPublicationWarnings = diagnosticsByCategory(
                compiled.diagnostics(),
                FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY
        );
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, slotPublicationWarnings.size());
        assertTrue(slotPublicationWarnings.getFirst().message().contains("if-body of function 'ping'"));
        assertEquals(1, compileDiagnostics.size());
        assertTrue(compileDiagnostics.getFirst().message().contains("Local variable 'value'"));
        assertTrue(compileDiagnostics.getFirst().message().contains("missing a lowering-ready published slot type"));
    }

    @Test
    void analyzeForCompileBlocksStaticPropertyDeclarationsWhileAnalyzeLeavesSharedDiagnosticsUntouched() throws Exception {
        var source = """
                class_name CompileCheckStaticPropertyDeclaration
                extends RefCounted
                
                static var shared: int = 1
                
                static func build() -> int:
                    return shared
                """;

        var sharedAnalyzed = analyzeShared("compile_check_static_property_declaration.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_static_property_declaration.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertEquals(1, compileDiagnostics.size());
        assertEquals(
                FrontendRange.fromAstRange(findVariable(compiled.unit().ast().statements(), "shared").range()),
                compileDiagnostics.getFirst().range()
        );
        assertTrue(compileDiagnostics.getFirst().message().contains("Static property 'shared'"));
        assertTrue(compileDiagnostics.getFirst().message().contains("does not support script static fields"));
    }

    @Test
    void analyzeForCompileBlocksStaticPropertyDeclarationsWithoutInitializer() throws Exception {
        var source = """
                class_name CompileCheckStaticPropertyWithoutInitializer
                extends RefCounted
                
                static var shared: int
                
                static func set_shared(value: int):
                    shared = value
                """;

        var sharedAnalyzed = analyzeShared("compile_check_static_property_without_initializer.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_static_property_without_initializer.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertEquals(1, compileDiagnostics.size());
        assertEquals(
                FrontendRange.fromAstRange(findVariable(compiled.unit().ast().statements(), "shared").range()),
                compileDiagnostics.getFirst().range()
        );
        assertTrue(compileDiagnostics.getFirst().message().contains("Static property 'shared'"));
    }

    @Test
    void analyzeForCompileStopsAtStaticPropertyDeclarationInsteadOfRecursingIntoInitializerSubtree() throws Exception {
        var source = """
                class_name CompileCheckStaticPropertyInitializerSubtree
                extends Node
                
                static var shared = [1]
                """;

        var sharedAnalyzed = analyzeShared("compile_check_static_property_initializer_subtree.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_static_property_initializer_subtree.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertEquals(1, compileDiagnostics.size());
        assertTrue(compileDiagnostics.getFirst().message().contains("Static property 'shared'"));
        assertFalse(compileDiagnostics.getFirst().message().contains("Array literal"));
    }

    @Test
    void analyzeForCompileLeavesResolvedUnaryAndEagerBinaryExpressionsOutOfCompileBlocks() throws Exception {
        var source = """
                class_name CompileCheckUnaryBinaryResolved
                extends RefCounted
                
                func ping(
                    items_a: Array[int],
                    items_b: Array[int],
                    typed_variant: Variant
                ):
                    var negated: int = -1
                    var logical_not: bool = !true
                    var dynamic_not := not typed_variant
                    var sum: int = 1 + 2
                    var typed_merge := items_a + items_b
                    var dynamic_sum := typed_variant + 1
                """;

        var sharedAnalyzed = analyzeShared("compile_check_unary_binary_resolved.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.deferred_chain_resolution").isEmpty());

        var compiled = analyzeForCompile("compile_check_unary_binary_resolved.gd", source);
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.deferred_chain_resolution").isEmpty());
    }

    @Test
    void analyzeForCompileLeavesShortCircuitBinaryExpressionsOnCompileSurface() throws Exception {
        var source = """
                class_name CompileCheckShortCircuitBinary
                extends RefCounted
                
                func helper(value):
                    return value
                
                func ping(left, right):
                    var both := left and helper(right)
                    return left or right
                """;

        var sharedAnalyzed = analyzeShared("compile_check_short_circuit_binary.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_short_circuit_binary.gd", source);
        assertFalse(compiled.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.deferred_chain_resolution").isEmpty());
    }

    @Test
    void analyzeForCompileAllowsCompoundAssignmentOnceBodyLoweringContractLands() throws Exception {
        var source = """
                class_name CompileCheckCompoundAssignment
                extends RefCounted
                
                var hp: int = 0
                
                func ping():
                    hp += 1
                """;

        var sharedAnalyzed = analyzeShared("compile_check_compound_assignment.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.unsupported_expression_route").isEmpty());

        var compiled = analyzeForCompile("compile_check_compound_assignment.gd", source);
        assertFalse(compiled.diagnostics().hasErrors(), () -> "Unexpected compile diagnostics: " + compiled.diagnostics().asList());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.unsupported_expression_route").isEmpty());
    }

    @Test
    void analyzeForCompileLeavesStaticMethodRoutesOutOfStaticPropertyCompileBlocks() throws Exception {
        var source = """
                class_name CompileCheckStaticMethodRoute
                extends RefCounted
                
                class Worker:
                    static func build() -> Worker:
                        return Worker.new()
                
                var worker := Worker.build()
                """;

        var sharedAnalyzed = analyzeShared("compile_check_static_method_route.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_static_method_route.gd", source);
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileLeavesMappedTopLevelStaticMethodRoutesOutOfCompileBlocks() throws Exception {
        var source = """
                class_name MappedWorker
                extends RefCounted
                
                static func build() -> MappedWorker:
                    return MappedWorker.new()
                
                var worker := MappedWorker.build()
                """;

        var sharedAnalyzed = analyzeShared(
                "compile_check_mapped_static_method_route.gd",
                source,
                Map.of("MappedWorker", "RuntimeWorker")
        );
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile(
                "compile_check_mapped_static_method_route.gd",
                source,
                Map.of("MappedWorker", "RuntimeWorker")
        );
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileBlocksParameterizedGdccConstructorRoutes() throws Exception {
        var compiled = analyzeForCompile("compile_check_parameterized_gdcc_constructor.gd", """
                class_name CompileCheckParameterizedCtor
                extends RefCounted
                
                class Worker:
                    func _init(value: int):
                        pass
                
                func build(seed):
                    return Worker.new(seed)
                """);

        var callDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.call_resolution");
        var typeCheckDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.type_check");
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, callDiagnostics.size());
        assertTrue(callDiagnostics.getFirst().message().contains("does not support arguments"));
        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("supports only zero parameters"));
        assertEquals(1, compileDiagnostics.size());
        assertTrue(
                compileDiagnostics.getFirst().message().contains("supports only zero-argument custom object construction"),
                compileDiagnostics.getFirst().message()
        );
    }

    @Test
    void analyzeForCompileKeepsDedicatedGuardForResolvedParameterizedGdccConstructorRegression() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_parameterized_gdcc_constructor_regression.gd", """
                        class_name CompileCheckParameterizedCtorRegression
                        extends RefCounted
                
                        class Worker:
                            func _init(value: int):
                                pass
                
                        func build(seed):
                            return Worker.new(seed)
                """);
        var buildFunction = findFunction(preparedInput.unit().ast().statements(), "build");
        var newStep = findNode(buildFunction, AttributeCallStep.class, step -> step.name().equals("new"));
        preparedInput.analysisData().expressionTypes().clear();
        preparedInput.analysisData().resolvedCalls().clear();
        preparedInput.analysisData().resolvedCalls().put(
                newStep,
                FrontendResolvedCall.resolved(
                        "new",
                        FrontendCallResolutionKind.CONSTRUCTOR,
                        FrontendReceiverKind.TYPE_META,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("CompileCheckParameterizedCtorRegression__sub__Worker"),
                        new GdObjectType("CompileCheckParameterizedCtorRegression__sub__Worker"),
                        List.of(GdVariantType.VARIANT),
                        new Object()
                )
        );
        preparedInput.analysisData().updateDiagnostics(new DiagnosticSnapshot(List.of()));
        var cleanDiagnosticManager = new DiagnosticManager();

        runCompileCheck(new PreparedCompileCheckInput(
                preparedInput.unit(),
                preparedInput.analysisData(),
                cleanDiagnosticManager
        ));

        var compileDiagnostics = diagnosticsByCategory(
                preparedInput.analysisData().diagnostics(),
                "sema.compile_check"
        );
        assertEquals(1, compileDiagnostics.size());
        assertTrue(
                compileDiagnostics.getFirst().message().contains("supports only zero-argument custom object construction"),
                compileDiagnostics.getFirst().message()
        );
    }

    @Test
    void analyzeForCompileSkipsExplicitCompileBlocksOutsideCompileSurface() throws Exception {
        var source = """
                class_name CompileCheckSkippedSurface
                extends Node
                
                func helper():
                    pass
                
                func ping(seed = [1]):
                    var body_local = 0
                    var f = func():
                        {"hp": body_local}
                        preload("res://icon.svg")
                        $Camera3D
                        body_local as int
                        body_local is int
                        assert(body_local)
                    const answer = [body_local]
                    for item in [body_local]:
                        {"item": item}
                        preload("res://icon.svg")
                        $Camera3D
                        item as int
                        item is int
                        assert(item)
                    match body_local:
                        var bound when bound > 0:
                            [bound]
                            preload("res://icon.svg")
                            $Camera3D
                            bound as int
                            bound is int
                            assert(bound)
                    return body_local
                """;

        var compiled = analyzeForCompile("compile_check_skipped_surface.gd", source);

        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        var unsupportedBindingDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_binding_subtree"
        );
        assertEquals(5, unsupportedBindingDiagnostics.size());
    }

    @Test
    void analyzeSkipsCompileCheckWhenAnchorAlreadyHasPublishedError() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_existing_error.gd", """
                class_name CompileCheckExistingError
                extends Node
                
                func ping():
                    [1]
                """);
        var arrayExpression = findNode(preparedInput.unit().ast(), ArrayExpression.class, ignored -> true);
        preparedInput.diagnosticManager().error(
                "sema.synthetic",
                "synthetic upstream error",
                preparedInput.unit().path(),
                FrontendRange.fromAstRange(arrayExpression.range())
        );
        preparedInput.analysisData().updateDiagnostics(preparedInput.diagnosticManager().snapshot());

        new FrontendCompileCheckAnalyzer().analyze(
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.compile_check").isEmpty());
        assertEquals(1, diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.synthetic").size());
    }

    @Test
    void analyzeReportsGenericCompileBlocksForPublishedCompileSurfaceFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_published_facts.gd", """
                class_name CompileCheckPublishedFacts
                extends RefCounted
                
                class Worker:
                    var payload: int = 1
                
                    func read() -> int:
                        return 1
                
                func ping(worker: Worker):
                    var copy = worker
                    var payload_copy = worker.payload
                    var read_value = worker.read()
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var copyDeclaration = findVariable(pingFunction.body().statements(), "copy");
        var payloadCopyDeclaration = findVariable(pingFunction.body().statements(), "payload_copy");
        var readValueDeclaration = findVariable(pingFunction.body().statements(), "read_value");
        var copyIdentifier = assertInstanceOf(dev.superice.gdparser.frontend.ast.IdentifierExpression.class, copyDeclaration.value());
        var payloadStep = findNode(payloadCopyDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("payload"));
        var readStep = findNode(readValueDeclaration.value(), AttributeCallStep.class, step -> step.name().equals("read"));

        preparedInput.analysisData().expressionTypes().put(
                copyIdentifier,
                FrontendExpressionType.deferred("synthetic deferred expression")
        );
        var originalMember = Objects.requireNonNull(preparedInput.analysisData().resolvedMembers().get(payloadStep));
        preparedInput.analysisData().resolvedMembers().put(
                payloadStep,
                FrontendResolvedMember.failed(
                        originalMember.memberName(),
                        originalMember.bindingKind(),
                        originalMember.receiverKind(),
                        originalMember.ownerKind(),
                        originalMember.receiverType(),
                        originalMember.declarationSite(),
                        "synthetic failed member"
                )
        );
        var originalCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(readStep));
        preparedInput.analysisData().resolvedCalls().put(
                readStep,
                FrontendResolvedCall.unsupported(
                        originalCall.callableName(),
                        originalCall.callKind(),
                        originalCall.receiverKind(),
                        originalCall.ownerKind(),
                        originalCall.receiverType(),
                        originalCall.argumentTypes(),
                        originalCall.declarationSite(),
                        "synthetic unsupported call"
                )
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(3, compileDiagnostics.size());
        assertTrue(compileDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
        ));
        assertEquals(
                Set.of(
                        FrontendRange.fromAstRange(copyIdentifier.range()),
                        FrontendRange.fromAstRange(payloadStep.range()),
                        FrontendRange.fromAstRange(readStep.range())
                ),
                compileDiagnostics.stream().map(FrontendDiagnostic::range).collect(java.util.stream.Collectors.toSet())
        );
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic deferred expression")
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic failed member")
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic unsupported call")
        ));
    }

    @Test
    void analyzeDeduplicatesGenericCompileBlocksAtSharedAttributeFinalStepAnchor() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_shared_anchor.gd", """
                class_name CompileCheckSharedAnchor
                extends RefCounted
                
                class Worker:
                    func read() -> int:
                        return 1
                
                func ping(worker: Worker):
                    var value = worker.read()
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var valueDeclaration = findVariable(pingFunction.body().statements(), "value");
        var attributeExpression = assertInstanceOf(AttributeExpression.class, valueDeclaration.value());
        var readStep = findNode(attributeExpression, AttributeCallStep.class, step -> step.name().equals("read"));
        var originalCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(readStep));

        preparedInput.analysisData().expressionTypes().put(
                attributeExpression,
                FrontendExpressionType.failed("synthetic failed attribute expression")
        );
        preparedInput.analysisData().resolvedCalls().put(
                readStep,
                FrontendResolvedCall.failed(
                        originalCall.callableName(),
                        originalCall.callKind(),
                        originalCall.receiverKind(),
                        originalCall.ownerKind(),
                        originalCall.receiverType(),
                        originalCall.argumentTypes(),
                        originalCall.declarationSite(),
                        "synthetic failed call step"
                )
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(1, compileDiagnostics.size());
        assertEquals(FrontendRange.fromAstRange(readStep.range()), compileDiagnostics.getFirst().range());
        assertTrue(compileDiagnostics.getFirst().message().contains("synthetic failed attribute expression"));
    }

    @Test
    void analyzeReportsCompileBlocksForPublishedBareCallFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_bare_call_fact.gd", """
                class_name CompileCheckBareCallFact
                extends RefCounted
                
                func helper(value: int) -> int:
                    return value
                
                static func ping_static(value: int):
                    helper(value)
                """);
        var pingStaticFunction = findFunction(preparedInput.unit().ast().statements(), "ping_static");
        var bareCall = findNode(
                assertInstanceOf(ExpressionStatement.class, pingStaticFunction.body().statements().getFirst()),
                CallExpression.class,
                ignored -> true
        );
        var publishedBareCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(bareCall));

        assertEquals(FrontendCallResolutionStatus.BLOCKED, publishedBareCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, publishedBareCall.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, publishedBareCall.receiverKind());
        preparedInput.analysisData().expressionTypes().put(
                bareCall,
                FrontendExpressionType.resolved(publishedBareCall.returnType())
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(1, compileDiagnostics.size());
        assertEquals(FrontendRange.fromAstRange(bareCall.range()), compileDiagnostics.getFirst().range());
        assertTrue(compileDiagnostics.getFirst().message().contains("Call expression 'helper(...)'"));
        assertTrue(compileDiagnostics.getFirst().message().contains("not accessible in the current context"));
    }

    @Test
    void analyzeUsesCompileGateForUnsupportedPublishedAttributeSubscriptStepFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_attribute_subscript_step.gd", """
                class_name CompileCheckAttributeSubscriptStep
                extends RefCounted
                
                class Worker:
                    var payloads: Dictionary[int, int]
                
                func ping(worker: Worker, seed: int):
                    var value = worker.payloads[seed]
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var valueDeclaration = findVariable(pingFunction.body().statements(), "value");
        var payloadsStep = findNode(
                valueDeclaration.value(),
                AttributeSubscriptStep.class,
                step -> step.name().equals("payloads")
        );

        assertTrue(diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.type_check").isEmpty());
        preparedInput.analysisData().expressionTypes().put(
                payloadsStep,
                FrontendExpressionType.unsupported("synthetic unsupported attribute subscript step")
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(1, compileDiagnostics.size());
        assertEquals(FrontendRange.fromAstRange(payloadsStep.range()), compileDiagnostics.getFirst().range());
        assertTrue(compileDiagnostics.getFirst().message().contains("Subscript step 'payloads[...]'"));
        assertTrue(compileDiagnostics.getFirst().message().contains("synthetic unsupported attribute subscript step"));
    }

    @Test
    void analyzeReportsGenericCompileBlocksForPublishedPropertyInitializerFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_property_initializer_facts.gd", """
                class_name CompileCheckPropertyInitializerFacts
                extends RefCounted
                
                class Handle:
                    func read() -> int:
                        return 1
                
                class Worker:
                    var handle: Handle = Handle.new()
                
                    static func build() -> Worker:
                        return Worker.new()
                
                var expr_value: int = 1
                var member_value := Worker.build().handle
                var call_value := Worker.build().handle.read()
                """);
        var exprValueDeclaration = findVariable(preparedInput.unit().ast().statements(), "expr_value");
        var memberValueDeclaration = findVariable(preparedInput.unit().ast().statements(), "member_value");
        var callValueDeclaration = findVariable(preparedInput.unit().ast().statements(), "call_value");
        var exprLiteral = assertInstanceOf(LiteralExpression.class, exprValueDeclaration.value());
        var handleStep = findNode(
                memberValueDeclaration.value(),
                AttributePropertyStep.class,
                step -> step.name().equals("handle")
        );
        var readStep = findNode(
                callValueDeclaration.value(),
                AttributeCallStep.class,
                step -> step.name().equals("read")
        );

        preparedInput.analysisData().expressionTypes().put(
                exprLiteral,
                FrontendExpressionType.failed("synthetic property initializer expression")
        );
        var originalMember = Objects.requireNonNull(preparedInput.analysisData().resolvedMembers().get(handleStep));
        preparedInput.analysisData().resolvedMembers().put(
                handleStep,
                FrontendResolvedMember.failed(
                        originalMember.memberName(),
                        originalMember.bindingKind(),
                        originalMember.receiverKind(),
                        originalMember.ownerKind(),
                        originalMember.receiverType(),
                        originalMember.declarationSite(),
                        "synthetic property initializer member"
                )
        );
        var originalCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(readStep));
        preparedInput.analysisData().resolvedCalls().put(
                readStep,
                FrontendResolvedCall.unsupported(
                        originalCall.callableName(),
                        originalCall.callKind(),
                        originalCall.receiverKind(),
                        originalCall.ownerKind(),
                        originalCall.receiverType(),
                        originalCall.argumentTypes(),
                        originalCall.declarationSite(),
                        "synthetic property initializer call"
                )
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(3, compileDiagnostics.size());
        assertEquals(
                Set.of(
                        FrontendRange.fromAstRange(exprLiteral.range()),
                        FrontendRange.fromAstRange(handleStep.range()),
                        FrontendRange.fromAstRange(readStep.range())
                ),
                compileDiagnostics.stream().map(FrontendDiagnostic::range).collect(java.util.stream.Collectors.toSet())
        );
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic property initializer expression")
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic property initializer member")
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic property initializer call")
        ));
    }

    @Test
    void analyzeSkipsGenericCompileBlocksOutsideCompileSurface() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_outside_surface.gd", """
                class_name CompileCheckOutsideSurface
                extends Node
                
                func ping(seed = 1):
                    var callback = func():
                        return 2
                    pass
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var defaultLiteral = assertInstanceOf(LiteralExpression.class, pingFunction.parameters().getFirst().defaultValue());
        var callbackDeclaration = findVariable(pingFunction.body().statements(), "callback");
        var lambdaExpression = assertInstanceOf(LambdaExpression.class, callbackDeclaration.value());
        var lambdaLiteral = findNode(lambdaExpression, LiteralExpression.class, ignored -> true);

        preparedInput.analysisData().expressionTypes().put(
                defaultLiteral,
                FrontendExpressionType.deferred("synthetic default-value deferred expression")
        );
        preparedInput.analysisData().expressionTypes().put(
                lambdaLiteral,
                FrontendExpressionType.failed("synthetic lambda failure")
        );

        runCompileCheck(preparedInput);

        assertTrue(diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeSkipsDynamicPublishedFactsInsideCompileSurface() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_dynamic_surface.gd", """
                class_name CompileCheckDynamicSurface
                extends RefCounted
                
                class Worker:
                    var payload: int = 1
                
                    func read() -> int:
                        return 1
                
                func ping(worker: Worker):
                    var copy = worker
                    var payload_copy = worker.payload
                    var read_value = worker.read()
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var copyDeclaration = findVariable(pingFunction.body().statements(), "copy");
        var payloadCopyDeclaration = findVariable(pingFunction.body().statements(), "payload_copy");
        var readValueDeclaration = findVariable(pingFunction.body().statements(), "read_value");
        var copyIdentifier = assertInstanceOf(dev.superice.gdparser.frontend.ast.IdentifierExpression.class, copyDeclaration.value());
        var payloadStep = findNode(payloadCopyDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("payload"));
        var readStep = findNode(readValueDeclaration.value(), AttributeCallStep.class, step -> step.name().equals("read"));
        var originalMember = Objects.requireNonNull(preparedInput.analysisData().resolvedMembers().get(payloadStep));
        var originalCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(readStep));

        preparedInput.analysisData().expressionTypes().put(
                copyIdentifier,
                FrontendExpressionType.dynamic("synthetic dynamic expression")
        );
        preparedInput.analysisData().resolvedMembers().put(
                payloadStep,
                FrontendResolvedMember.dynamic(
                        originalMember.memberName(),
                        originalMember.bindingKind(),
                        originalMember.receiverKind(),
                        originalMember.ownerKind(),
                        originalMember.receiverType(),
                        originalMember.declarationSite(),
                        "synthetic dynamic member"
                )
        );
        preparedInput.analysisData().resolvedCalls().put(
                readStep,
                FrontendResolvedCall.dynamic(
                        originalCall.callableName(),
                        originalCall.receiverKind(),
                        originalCall.ownerKind(),
                        originalCall.receiverType(),
                        originalCall.argumentTypes(),
                        originalCall.declarationSite(),
                        "synthetic dynamic call"
                )
        );

        runCompileCheck(preparedInput);

        assertTrue(diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileUpgradesDeferredWarningsIntoCompileBlockingErrors() throws Exception {
        var source = """
                class_name DeferredCompileCheck
                extends RefCounted
                
                func build(value: int) -> String:
                    return ""
                
                func ping(flag):
                    self.build(1 if flag else 2).length
                """;

        var shared = analyzeShared("deferred_compile_check.gd", source);
        assertFalse(shared.diagnostics().hasErrors());
        assertEquals(1, diagnosticsByCategory(shared.diagnostics(), "sema.deferred_chain_resolution").size());
        assertTrue(diagnosticsByCategory(shared.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("deferred_compile_check.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertFalse(compileDiagnostics.isEmpty());
        assertTrue(compiled.diagnostics().hasErrors());
        assertTrue(compileDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("remains deferred")
                        || diagnostic.message().contains("Conditional expression")
        ));
    }

    private static @NotNull AnalyzedScript analyzeShared(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyzeShared(fileName, source, Map.of());
    }

    private static @NotNull AnalyzedScript analyzeShared(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());

        var diagnosticManager = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit), topLevelCanonicalNameMap),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnosticManager
        );
        return new AnalyzedScript(unit, analysisData.diagnostics(), diagnosticManager);
    }

    private static @NotNull AnalyzedScript analyzeForCompile(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyzeForCompile(fileName, source, Map.of());
    }

    private static @NotNull AnalyzedScript analyzeForCompile(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());

        var diagnosticManager = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                new FrontendModule("test_module", List.of(unit), topLevelCanonicalNameMap),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnosticManager
        );
        return new AnalyzedScript(unit, analysisData.diagnostics(), diagnosticManager);
    }

    private static @NotNull PreparedCompileCheckInput prepareCompileCheckInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnosticManager);
        assertTrue(diagnosticManager.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnosticManager.snapshot());

        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                classRegistry,
                diagnosticManager,
                analysisData
        );
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendScopeAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendVariableAnalyzer().analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendTopBindingAnalyzer().analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendChainBindingAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendExprTypeAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendAnnotationUsageAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendTypeCheckAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return new PreparedCompileCheckInput(unit, analysisData, diagnosticManager);
    }

    private static void runCompileCheck(@NotNull PreparedCompileCheckInput preparedInput) {
        Objects.requireNonNull(preparedInput, "preparedInput must not be null");
        new FrontendCompileCheckAnalyzer().analyze(
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );
        preparedInput.analysisData().updateDiagnostics(preparedInput.diagnosticManager().snapshot());
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static @NotNull FunctionDeclaration findFunction(
            @NotNull List<Statement> statements,
            @NotNull String name
    ) {
        for (var statement : Objects.requireNonNull(statements, "statements must not be null")) {
            if (statement instanceof FunctionDeclaration functionDeclaration
                    && functionDeclaration.name().equals(Objects.requireNonNull(name, "name must not be null"))) {
                return functionDeclaration;
            }
        }
        throw new AssertionError("Function not found: " + name);
    }

    private static @NotNull VariableDeclaration findVariable(
            @NotNull List<Statement> statements,
            @NotNull String name
    ) {
        for (var statement : Objects.requireNonNull(statements, "statements must not be null")) {
            if (statement instanceof VariableDeclaration variableDeclaration
                    && variableDeclaration.name().equals(Objects.requireNonNull(name, "name must not be null"))) {
                return variableDeclaration;
            }
        }
        throw new AssertionError("Variable not found: " + name);
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        for (var child : root.getChildren()) {
            try {
                return findNode(child, nodeType, predicate);
            } catch (AssertionError ignored) {
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private record AnalyzedScript(
            @NotNull FrontendSourceUnit unit,
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        private AnalyzedScript {
            Objects.requireNonNull(unit, "unit must not be null");
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
            Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        }
    }

    private record PreparedCompileCheckInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        private PreparedCompileCheckInput {
            Objects.requireNonNull(unit, "unit must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        }
    }
}
