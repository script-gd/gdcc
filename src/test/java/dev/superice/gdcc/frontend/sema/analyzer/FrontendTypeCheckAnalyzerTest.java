package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendTypeCheckAnalyzerTest {
    @Test
    void analyzeRejectsMissingModuleSkeletonBoundary() throws Exception {
        var analyzer = new FrontendTypeCheckAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(classRegistry, analysisData, new DiagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("moduleSkeleton"));
    }

    @Test
    void analyzeRejectsMissingDiagnosticsBoundary() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_diagnostics.gd", """
                class_name MissingTypeCheckDiagnostics
                extends Node
                
                func ping() -> int:
                    return 1
                """);
        var analyzer = new FrontendTypeCheckAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateModuleSkeleton(preparedInput.analysisData().moduleSkeleton());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(preparedInput.classRegistry(), analysisData, preparedInput.diagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("diagnostics"));
    }

    @Test
    void analyzeRejectsMissingPublishedSourceScope() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_scope.gd", """
                class_name MissingTypeCheckScope
                extends Node
                
                func ping() -> int:
                    return 1
                """);
        var analyzer = new FrontendTypeCheckAnalyzer();
        preparedInput.analysisData().scopesByAst().remove(preparedInput.unit().ast());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains(preparedInput.unit().path().toString()));
    }

    @Test
    void analyzeRejectsMissingPublishedLocalInitializerExpressionType() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_local_initializer_type.gd", """
                class_name MissingTypeCheckLocalInitializerType
                extends RefCounted
                
                func ping():
                    var local: int = 1
                """);
        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var localDeclaration = findVariable(pingFunction.body().statements(), "local");
        preparedInput.analysisData().expressionTypes().remove(localDeclaration.value());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains("Local initializer for 'local'"));
        assertTrue(thrown.getMessage().contains("expression type has not been published"));
    }

    @Test
    void analyzeRejectsMissingPublishedPropertyInitializerExpressionType() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_property_initializer_type.gd", """
                class_name MissingTypeCheckPropertyInitializerType
                extends RefCounted
                
                var field: int = 1
                """);
        var fieldDeclaration = findVariable(preparedInput.unit().ast(), "field");
        preparedInput.analysisData().expressionTypes().remove(fieldDeclaration.value());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains("Property initializer for 'field'"));
        assertTrue(thrown.getMessage().contains("expression type has not been published"));
    }

    @Test
    void analyzeRejectsMissingPublishedReturnExpressionType() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_return_value_type.gd", """
                class_name MissingTypeCheckReturnValueType
                extends RefCounted
                
                func ping() -> int:
                    return 1
                """);
        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var returnStatement = findNode(
                pingFunction,
                dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                ignored -> true
        );
        preparedInput.analysisData().expressionTypes().remove(returnStatement.value());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains("Return value for Callable on class 'MissingTypeCheckReturnValueType'"));
        assertTrue(thrown.getMessage().contains("expression type has not been published"));
    }

    @Test
    void analyzeRejectsMissingPublishedConditionExpressionType() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_condition_type.gd", """
                class_name MissingTypeCheckConditionType
                extends RefCounted
                
                func ping():
                    if true:
                        pass
                """);
        var ifStatement = findNode(
                preparedInput.unit().ast(),
                dev.superice.gdparser.frontend.ast.IfStatement.class,
                ignored -> true
        );
        preparedInput.analysisData().expressionTypes().remove(ifStatement.condition());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains("IfStatement condition"));
        assertTrue(thrown.getMessage().contains("expression type has not been published"));
    }

    @Test
    void analyzeWalksTypedRootsWithExpectedContextWithoutMutatingPublishedFacts() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_context_probe.gd", """
                class_name TypeCheckContextProbe
                extends RefCounted
                
                var instance_field: int = 1
                static var static_field: int = 2
                
                class Inner:
                    static var inner_field: int = 3
                
                func _init():
                    return
                
                static func read(flag) -> int:
                    var local: int = 1
                    assert(flag, "still typed as a regular expression")
                    if flag:
                        return local
                    while flag:
                        return 2
                    return 3
                """);
        var analyzer = new RecordingTypeCheckAnalyzer();
        var symbolBindings = preparedInput.analysisData().symbolBindings();
        var resolvedMembers = preparedInput.analysisData().resolvedMembers();
        var resolvedCalls = preparedInput.analysisData().resolvedCalls();
        var expressionTypes = preparedInput.analysisData().expressionTypes();
        var symbolBindingCount = symbolBindings.size();
        var resolvedMemberCount = resolvedMembers.size();
        var resolvedCallCount = resolvedCalls.size();
        var expressionTypeCount = expressionTypes.size();
        var diagnosticsBefore = preparedInput.diagnosticManager().snapshot();

        analyzer.analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertSame(symbolBindings, preparedInput.analysisData().symbolBindings());
        assertSame(resolvedMembers, preparedInput.analysisData().resolvedMembers());
        assertSame(resolvedCalls, preparedInput.analysisData().resolvedCalls());
        assertSame(expressionTypes, preparedInput.analysisData().expressionTypes());
        assertEquals(symbolBindingCount, preparedInput.analysisData().symbolBindings().size());
        assertEquals(resolvedMemberCount, preparedInput.analysisData().resolvedMembers().size());
        assertEquals(resolvedCallCount, preparedInput.analysisData().resolvedCalls().size());
        assertEquals(expressionTypeCount, preparedInput.analysisData().expressionTypes().size());
        assertEquals(diagnosticsBefore, preparedInput.diagnosticManager().snapshot());

        assertEquals(11, analyzer.events().size());

        assertEvent(
                analyzer.events().get(0),
                "property",
                "instance_field",
                "TypeCheckContextProbe",
                null,
                ResolveRestriction.instanceContext(),
                false,
                0,
                "instance_field"
        );
        assertEvent(
                analyzer.events().get(1),
                "property",
                "static_field",
                "TypeCheckContextProbe",
                null,
                ResolveRestriction.staticContext(),
                true,
                0,
                "static_field"
        );
        assertEvent(
                analyzer.events().get(2),
                "property",
                "inner_field",
                "TypeCheckContextProbe__sub__Inner",
                null,
                ResolveRestriction.staticContext(),
                true,
                0,
                "inner_field"
        );
        assertEvent(
                analyzer.events().get(3),
                "return",
                "bare",
                "TypeCheckContextProbe",
                "void",
                ResolveRestriction.instanceContext(),
                false,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(4),
                "local",
                "local",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(5),
                "condition",
                "AssertStatement",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(6),
                "condition",
                "IfStatement",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(7),
                "return",
                "valued",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                2,
                null
        );
        assertEvent(
                analyzer.events().get(8),
                "condition",
                "WhileStatement",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(9),
                "return",
                "valued",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                2,
                null
        );
        assertEvent(
                analyzer.events().get(10),
                "return",
                "valued",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
    }

    @Test
    void analyzeReportsParameterizedGdccConstructorDeclarationAsSemanticError() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_parameterized_gdcc_constructor.gd", """
                class_name TypeCheckParameterizedCtor
                extends RefCounted
                
                class Worker:
                    func _init(value: int):
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );

        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("supports only zero parameters"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("Worker._init(...)"));
        assertEquals(Path.of("tmp", "type_check_parameterized_gdcc_constructor.gd"), typeCheckDiagnostics.getFirst().sourcePath());
        assertNotNull(typeCheckDiagnostics.getFirst().range());
    }

    @Test
    void analyzeDoesNotReportZeroArgGdccConstructorDeclaration() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_zero_arg_gdcc_constructor.gd", """
                class_name TypeCheckZeroArgCtor
                extends RefCounted
                
                class Worker:
                    func _init():
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        ).isEmpty());
    }

    @Test
    void analyzeChecksOnlyExplicitOrdinaryLocalDeclaredSlotsAndSkipsUnstableInitializerFacts() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_local_compatibility.gd", """
                class_name TypeCheckLocalCompatibility
                extends RefCounted
                
                class Worker:
                    pass
                
                var payload: int = 1
                
                static func ping(worker):
                    var accepts_variant: Variant = 1
                    var exact_variant_source: int = accepts_variant
                    var strict_float: float = 1
                    var dynamic_variant: Variant = worker.ping().length
                    var dynamic_int: int = worker.ping().length
                    var inferred := 1
                    var skipped_blocked: int = self.payload
                    var skipped_deferred: int = 1 + 2
                    var skipped_failed: int = Worker
                    var skipped_unsupported: int = func(offset: int):
                        return offset
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(pingFunction.body().statements(), "exact_variant_source", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DYNAMIC,
                requireInitializerType(pingFunction.body().statements(), "dynamic_variant", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DYNAMIC,
                requireInitializerType(pingFunction.body().statements(), "dynamic_int", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.BLOCKED,
                requireInitializerType(pingFunction.body().statements(), "skipped_blocked", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(pingFunction.body().statements(), "skipped_deferred", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                requireInitializerType(pingFunction.body().statements(), "skipped_failed", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.UNSUPPORTED,
                requireInitializerType(pingFunction.body().statements(), "skipped_unsupported", preparedInput).status()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(1, typeCheckDiagnostics.size());
        var typeCheckDiagnostic = typeCheckDiagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, typeCheckDiagnostic.severity());
        assertTrue(typeCheckDiagnostic.message().contains("strict_float"));
        assertTrue(typeCheckDiagnostic.message().contains("int"));
        assertTrue(typeCheckDiagnostic.message().contains("float"));
        assertEquals(Path.of("tmp", "type_check_local_compatibility.gd"), typeCheckDiagnostic.sourcePath());
        assertNotNull(typeCheckDiagnostic.range());

        assertTrue(diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_hint"
        ).isEmpty());
    }

    @Test
    void analyzeChecksPropertyInitializersAgainstPublishedSkeletonSlotsAndWarnsForMissingExplicitTypes()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_property_compatibility.gd", """
                class_name TypeCheckPropertyCompatibility
                extends RefCounted
                
                class Worker:
                    static func make():
                        return "value"
                
                    static func make_count():
                        return 1
                
                var accepts_variant: Variant = 1
                var accepts_variant_source: int = Worker.make_count()
                var wrong_type: int = "x"
                var inferred_int := 1
                var missing_type = 1
                var inferred_dynamic := Worker.make().length
                var skipped_blocked := self.payload
                var skipped_deferred: int = 1 + 2
                var skipped_failed: int = Worker
                var skipped_failed_hint = Worker
                var skipped_deferred_hint := 1 + 2
                var skipped_unsupported: int = Worker.VALUE
                static var blocked_field: int = self.payload
                var payload: int = 1
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "accepts_variant_source", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.BLOCKED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_blocked", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_deferred", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_failed", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_failed_hint", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_deferred_hint", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.UNSUPPORTED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_unsupported", preparedInput).status()
        );

        var diagnostics = preparedInput.diagnosticManager().snapshot();
        var typeCheckDiagnostics = diagnosticsByCategory(diagnostics, "sema.type_check");
        assertEquals(1, typeCheckDiagnostics.size());
        var typeCheckDiagnostic = typeCheckDiagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, typeCheckDiagnostic.severity());
        assertTrue(typeCheckDiagnostic.message().contains("wrong_type"));
        assertTrue(typeCheckDiagnostic.message().contains("String"));
        assertTrue(typeCheckDiagnostic.message().contains("int"));
        assertEquals(Path.of("tmp", "type_check_property_compatibility.gd"), typeCheckDiagnostic.sourcePath());
        assertNotNull(typeCheckDiagnostic.range());

        var typeHintDiagnostics = diagnosticsByCategory(diagnostics, "sema.type_hint");
        assertEquals(4, typeHintDiagnostics.size());
        assertTrue(typeHintDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.WARNING
                        && Path.of("tmp", "type_check_property_compatibility.gd").equals(diagnostic.sourcePath())
                        && diagnostic.range() != null
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("inferred_int")
                        && diagnostic.message().contains("':='")
                        && diagnostic.message().contains(": int")
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("missing_type")
                        && diagnostic.message().contains("no explicit type")
                        && diagnostic.message().contains(": int")
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("inferred_dynamic")
                        && diagnostic.message().contains("':='")
                        && diagnostic.message().contains(": Variant")
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("skipped_deferred_hint")
                        && diagnostic.message().contains("':='")
                        && diagnostic.message().contains(": int")
        ));

        var topLevelClass = preparedInput.analysisData().moduleSkeleton().sourceClassRelations().getFirst().classDef();
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "inferred_int").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "missing_type").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "inferred_dynamic").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "skipped_blocked").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "skipped_failed_hint").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "skipped_deferred_hint").getType());
    }

    @Test
    void analyzeSkipsInheritedPropertyInitializerBoundaryDiagnosticsOwnedByUpstreamPhases()
            throws Exception {
        var preparedInput = prepareTypeCheckInput(
                "type_check_inherited_property_initializer_boundary.gd",
                """
                        class_name TypeCheckInheritedPropertyInitializerBoundary
                        extends PropertyInitializerBase
                        
                        var skipped_blocked: int = payload
                        static var skipped_unsupported: int = PropertyInitializerBase.read()
                        static var allowed_helper: int = PropertyInitializerBase.helper()
                        """,
                FrontendAnalyzerTestRegistrySupport.registryWithInheritedPropertyInitializerBase()
        );

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertEquals(
                FrontendExpressionTypeStatus.BLOCKED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_blocked", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.UNSUPPORTED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_unsupported", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "allowed_helper", preparedInput).status()
        );
        assertEquals(
                "int",
                requireInitializerType(preparedInput.unit().ast(), "allowed_helper", preparedInput)
                        .publishedType()
                        .getTypeName()
        );
        assertTrue(diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        ).isEmpty());
    }

    @Test
    void analyzeChecksReturnCompatibilityAgainstPublishedCallableSlotsAndSkipsUnstableReturnValues()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_return_compatibility.gd", """
                class_name TypeCheckReturnCompatibility
                extends RefCounted
                
                class Worker:
                    pass
                
                func accepts_variant_expr() -> Variant:
                    return 1
                
                func accepts_variant_bare() -> Variant:
                    return
                
                func accepts_weak_bare():
                    return
                
                func accepts_exact_variant_source(value: Variant) -> int:
                    return value
                
                func accepts_dynamic_variant_source(worker) -> int:
                    return worker.ping().length
                
                func rejects_bare() -> int:
                    return
                
                func rejects_object_bare() -> Object:
                    return
                
                func rejects_type() -> int:
                    return "x"
                
                func skips_failed() -> int:
                    return Worker
                
                func skips_deferred() -> int:
                    return 1 + 2
                
                func _init():
                    return 1
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = preparedInput.diagnosticManager().snapshot();
        var typeCheckDiagnostics = diagnosticsByCategory(diagnostics, "sema.type_check");
        assertEquals(4, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && Path.of("tmp", "type_check_return_compatibility.gd").equals(diagnostic.sourcePath())
                        && diagnostic.range() != null
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Bare 'return'")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Bare 'return'")
                        && diagnostic.message().contains("Object")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Return value type 'String'")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("returns 'void'")
                        && diagnostic.message().contains("return expr")
        ));
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "accepts_exact_variant_source"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        ignored -> true).value()
                        )
                ).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DYNAMIC,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "accepts_dynamic_variant_source"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        ignored -> true).value()
                        )
                ).status()
        );

        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "skips_failed"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        ignored -> true).value()
                        )
                ).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "skips_deferred"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        ignored -> true).value()
                        )
                ).status()
        );
    }

    @Test
    void analyzeAcceptsNullAtObjectInitializerAndReturnBoundariesButKeepsNilToScalarRejected()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_null_object_boundaries.gd", """
                class_name TypeCheckNullObjectBoundaries
                extends RefCounted
                
                var accepted_obj: Object = null
                var rejected_int: int = null
                
                func ping() -> void:
                    var local_obj: Object = null
                    var local_i: int = null
                
                func ret_obj() -> Object:
                    return null
                
                func ret_int() -> int:
                    return null
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(3, diagnostics.size());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Property 'rejected_int'")
                        && diagnostic.message().contains("Nil")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Local variable 'local_i'")
                        && diagnostic.message().contains("Nil")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Return value type 'Nil'")
                        && diagnostic.message().contains("int")
        ));
    }

    @Test
    void analyzeReportsTypeMismatchWhenVoidUtilityFeedsTypedInitializerInsteadOfCrashing()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_void_utility_initializer.gd", """
                class_name TypeCheckVoidUtilityInitializer
                extends RefCounted
                
                func ping(value):
                    var strict_value: int = print(value)
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var strictValueType = requireInitializerType(
                pingFunction.body().statements(),
                "strict_value",
                preparedInput
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, strictValueType.status());
        assertEquals(GdVoidType.VOID, strictValueType.publishedType());

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("strict_value"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("void"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("int"));
    }

    @Test
    void analyzeRequiresStableConditionFactsButDoesNotEnforceStrictBoolConditionSlots()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_condition_contract.gd", """
                class_name TypeCheckConditionContract
                extends RefCounted
                
                class Worker:
                    pass
                
                func ping(payload):
                    assert(payload, "variant condition remains source-valid")
                    if 1:
                        pass
                    elif payload:
                        pass
                    while payload:
                        pass
                    if 1 + 2:
                        pass
                    if payload and 1:
                        pass
                    if payload or 0:
                        pass
                    if !true:
                        pass
                    if not payload:
                        pass
                    if Worker:
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = preparedInput.diagnosticManager().snapshot();
        var typeCheckDiagnostics = diagnosticsByCategory(diagnostics, "sema.type_check");
        assertTrue(typeCheckDiagnostics.isEmpty());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var ifStatements = findNodes(
                pingFunction,
                dev.superice.gdparser.frontend.ast.IfStatement.class,
                ignored -> true
        );
        var whileStatement = findNode(
                pingFunction,
                dev.superice.gdparser.frontend.ast.WhileStatement.class,
                ignored -> true
        );
        var firstIf = ifStatements.getFirst();
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(firstIf.condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(firstIf.elifClauses().getFirst().condition())
                ).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(whileStatement.condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(1).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(2).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(3).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(4).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DYNAMIC,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(5).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(6).condition())).status()
        );
    }

    private static void assertEvent(
            @NotNull ProbeEvent event,
            @NotNull String expectedKind,
            @NotNull String expectedName,
            @NotNull String expectedCurrentClassName,
            @Nullable String expectedReturnTypeName,
            @NotNull ResolveRestriction expectedRestriction,
            boolean expectedStaticContext,
            int expectedDepth,
            @Nullable String expectedPropertyInitializerName
    ) {
        assertEquals(expectedKind, event.kind());
        assertEquals(expectedName, event.name());
        assertEquals(expectedCurrentClassName, event.currentClassName());
        assertEquals(expectedReturnTypeName, event.currentReturnTypeName());
        assertEquals(expectedRestriction, event.restriction());
        assertEquals(expectedStaticContext, event.staticContext());
        assertEquals(expectedDepth, event.executableBodyDepth());
        assertEquals(expectedPropertyInitializerName, event.propertyInitializerName());
    }

    private static @NotNull PreparedTypeCheckInput prepareTypeCheckInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return prepareTypeCheckInput(fileName, source, new ClassRegistry(ExtensionApiLoader.loadDefault()));
    }

    private static @NotNull PreparedTypeCheckInput prepareTypeCheckInput(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull ClassRegistry classRegistry
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnosticManager);
        assertTrue(diagnosticManager.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnosticManager.snapshot());

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
        return new PreparedTypeCheckInput(unit, analysisData, diagnosticManager, classRegistry);
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static @NotNull dev.superice.gdcc.frontend.sema.FrontendExpressionType requireInitializerType(
            @NotNull List<Statement> statements,
            @NotNull String variableName,
            @NotNull PreparedTypeCheckInput preparedInput
    ) {
        return requireInitializerType(findVariable(statements, variableName), preparedInput);
    }

    private static @NotNull dev.superice.gdcc.frontend.sema.FrontendExpressionType requireInitializerType(
            @NotNull Node root,
            @NotNull String variableName,
            @NotNull PreparedTypeCheckInput preparedInput
    ) {
        return requireInitializerType(findVariable(root, variableName), preparedInput);
    }

    private static @NotNull dev.superice.gdcc.frontend.sema.FrontendExpressionType requireInitializerType(
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull PreparedTypeCheckInput preparedInput
    ) {
        var initializer = Objects.requireNonNull(variableDeclaration.value(), "initializer must not be null");
        var publishedType = preparedInput.analysisData().expressionTypes().get(initializer);
        return Objects.requireNonNull(
                publishedType,
                () -> "Initializer type not published for variable '" + variableDeclaration.name() + "'"
        );
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String functionName) {
        return findNode(root, FunctionDeclaration.class, function -> function.name().equals(functionName));
    }

    private static @NotNull VariableDeclaration findVariable(
            @NotNull List<Statement> statements,
            @NotNull String variableName
    ) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(variable -> variable.name().equals(variableName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + variableName));
    }

    private static @NotNull VariableDeclaration findVariable(@NotNull Node root, @NotNull String variableName) {
        return findNode(root, VariableDeclaration.class, variable -> variable.name().equals(variableName));
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
                // Continue searching remaining subtrees.
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectNodes(root, nodeType, predicate, matches);
        if (matches.isEmpty()) {
            throw new AssertionError("Node not found: " + nodeType.getSimpleName());
        }
        return List.copyOf(matches);
    }

    private static <T extends Node> void collectNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                matches.add(candidate);
            }
        }
        for (var child : root.getChildren()) {
            collectNodes(child, nodeType, predicate, matches);
        }
    }

    private static @NotNull PropertyDef findPropertyDef(
            @NotNull ClassDef classDef,
            @NotNull String propertyName
    ) {
        return classDef.getProperties().stream()
                .filter(property -> property.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + propertyName));
    }

    private record PreparedTypeCheckInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull ClassRegistry classRegistry
    ) {
    }

    private record ProbeEvent(
            @NotNull String kind,
            @NotNull String name,
            @Nullable String currentClassName,
            @Nullable String currentReturnTypeName,
            @NotNull ResolveRestriction restriction,
            boolean staticContext,
            int executableBodyDepth,
            @Nullable String propertyInitializerName
    ) {
        private ProbeEvent {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(restriction, "restriction must not be null");
        }
    }

    private static final class RecordingTypeCheckAnalyzer extends FrontendTypeCheckAnalyzer {
        private final List<ProbeEvent> events = new ArrayList<>();

        @Override
        protected void visitOrdinaryLocalInitializer(
                @NotNull TypeCheckAccess access,
                @NotNull dev.superice.gdparser.frontend.ast.VariableDeclaration variableDeclaration
        ) {
            events.add(toEvent("local", variableDeclaration.name(), access));
        }

        @Override
        protected void visitPropertyInitializer(
                @NotNull TypeCheckAccess access,
                @NotNull dev.superice.gdparser.frontend.ast.VariableDeclaration variableDeclaration
        ) {
            events.add(toEvent("property", variableDeclaration.name(), access));
        }

        @Override
        protected void visitReturnStatement(
                @NotNull TypeCheckAccess access,
                @NotNull dev.superice.gdparser.frontend.ast.ReturnStatement returnStatement
        ) {
            events.add(toEvent("return", returnStatement.value() == null ? "bare" : "valued", access));
        }

        @Override
        protected void visitConditionExpression(
                @NotNull TypeCheckAccess access,
                @NotNull dev.superice.gdparser.frontend.ast.Expression condition,
                @NotNull dev.superice.gdparser.frontend.ast.Node owner
        ) {
            events.add(toEvent("condition", owner.getClass().getSimpleName(), access));
        }

        private @NotNull ProbeEvent toEvent(
                @NotNull String kind,
                @NotNull String name,
                @NotNull TypeCheckAccess access
        ) {
            var context = access.context();
            var propertyInitializerContext = context.currentPropertyInitializerContext();
            return new ProbeEvent(
                    kind,
                    name,
                    context.currentClass() != null ? context.currentClass().getName() : null,
                    context.currentCallableReturnSlot() != null ? context.currentCallableReturnSlot().getTypeName() : null,
                    context.currentRestriction(),
                    context.currentStaticContext(),
                    context.executableBodyDepth(),
                    propertyInitializerContext != null ? propertyInitializerContext.declaration().name() : null
            );
        }

        private @NotNull List<ProbeEvent> events() {
            return List.copyOf(events);
        }
    }
}
