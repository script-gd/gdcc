package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                "TypeCheckContextProbe$Inner",
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
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnosticManager);
        assertTrue(diagnosticManager.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnosticManager.snapshot());

        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                "test_module",
                List.of(unit),
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
