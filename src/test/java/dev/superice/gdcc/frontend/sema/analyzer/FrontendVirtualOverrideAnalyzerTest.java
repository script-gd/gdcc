package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import dev.superice.gdcc.frontend.sema.FrontendSourceClassRelation;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendVirtualOverrideAnalyzerTest {
    @Test
    void analyzeRejectsMissingPublishedSourceScopeBoundary() throws Exception {
        var preparedInput = prepareVirtualOverrideInput(
                "missing_virtual_override_scope.gd",
                """
                        class_name MissingVirtualOverrideScope
                        extends Node
                        
                        func _ready() -> void:
                            pass
                        """
        );
        preparedInput.analysisData().scopesByAst().remove(preparedInput.units().getFirst().ast());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendVirtualOverrideAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains(preparedInput.units().getFirst().path().toString()));
    }

    @Test
    void analyzeAllowsExactEngineVirtualOverridesAndLeavesNonVirtualVariantFallbackUntouched() throws Exception {
        var preparedInput = prepareVirtualOverrideInput(
                "valid_virtual_override.gd",
                """
                        class_name ValidVirtualOverride
                        extends Node
                        
                        func _ready() -> void:
                            pass
                        
                        func _process(delta: float) -> void:
                            pass
                        
                        func _physics_process(delta: float) -> void:
                            pass
                        
                        func helper(value):
                            var copy := value
                        """
        );

        new FrontendVirtualOverrideAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var overrideDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot().asList(),
                "sema.virtual_override"
        );
        assertTrue(overrideDiagnostics.isEmpty());

        var classDef = findClassDef(preparedInput.analysisData(), "ValidVirtualOverride");
        var helperFunction = findFunctionDef(classDef, "helper");
        assertEquals(GdVariantType.VARIANT, Objects.requireNonNull(helperFunction.getParameter(0)).getType());
        assertEquals(GdVariantType.VARIANT, helperFunction.getReturnType());
    }

    @Test
    void analyzeReportsEngineVirtualSignatureMismatchesWithoutChangingVariantFallbackContract() throws Exception {
        var preparedInput = prepareVirtualOverrideInput(List.of(
                new SourceSpec("ready_with_arg.gd", """
                        class_name ReadyWithArg
                        extends Node
                        
                        func _ready(arg: int) -> void:
                            pass
                        """),
                new SourceSpec("ready_returns_int.gd", """
                        class_name ReadyReturnsInt
                        extends Node
                        
                        func _ready() -> int:
                            return 1
                        """),
                new SourceSpec("process_delta_variant.gd", """
                        class_name ProcessDeltaVariant
                        extends Node
                        
                        func _process(delta) -> void:
                            pass
                        """),
                new SourceSpec("physics_process_int.gd", """
                        class_name PhysicsProcessInt
                        extends Node
                        
                        func _physics_process(delta: int) -> void:
                            pass
                        """),
                new SourceSpec("static_ready.gd", """
                        class_name StaticReady
                        extends Node
                        
                        static func _ready() -> void:
                            pass
                        """)
        ));

        new FrontendVirtualOverrideAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var overrideDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot().asList(),
                "sema.virtual_override"
        );
        assertEquals(5, overrideDiagnostics.size());
        assertTrue(overrideDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.range() != null
        ));
        assertTrue(overrideDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(Path.of("tmp", "ready_with_arg.gd"))
                        && diagnostic.message().contains("_ready")
                        && diagnostic.message().contains("declares 1 parameter(s); expected 0")
        ));
        assertTrue(overrideDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(Path.of("tmp", "ready_returns_int.gd"))
                        && diagnostic.message().contains("returns 'int'; expected 'void'")
        ));
        assertTrue(overrideDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(Path.of("tmp", "process_delta_variant.gd"))
                        && diagnostic.message().contains("parameter #1 'delta'")
                        && diagnostic.message().contains("Variant")
                        && diagnostic.message().contains("float")
        ));
        assertTrue(overrideDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(Path.of("tmp", "physics_process_int.gd"))
                        && diagnostic.message().contains("_physics_process")
                        && diagnostic.message().contains("int")
                        && diagnostic.message().contains("float")
        ));
        assertTrue(overrideDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(Path.of("tmp", "static_ready.gd"))
                        && diagnostic.message().contains("declared static")
        ));

        var processClass = findClassDef(preparedInput.analysisData(), "ProcessDeltaVariant");
        var processFunction = findFunctionDef(processClass, "_process");
        assertEquals(GdVariantType.VARIANT, Objects.requireNonNull(processFunction.getParameter(0)).getType());
    }

    @Test
    void sharedSemanticPipelineStillPublishesBodyFactsWhenOverrideSignatureIsWrong() throws Exception {
        var analyzedModule = analyze(
                "body_continue_virtual_override.gd",
                """
                        class_name BodyContinueVirtualOverride
                        extends Node
                        
                        func _process(delta) -> void:
                            var alias := delta
                            var strict: int = "bad"
                        """
        );

        var overrideDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.virtual_override"
        );
        assertEquals(1, overrideDiagnostics.size());
        var typeCheckDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.type_check"
        );
        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("strict"));

        var processFunction = findFunction(analyzedModule.units().getFirst().ast(), "_process");
        var aliasVariable = findVariable(processFunction.body().statements(), "alias");
        var aliasInitializer = assertInstanceOf(IdentifierExpression.class, aliasVariable.value());

        assertFalse(analyzedModule.analysisData().skippedSubtreeRoots().containsKey(processFunction));
        assertTrue(analyzedModule.analysisData().scopesByAst().containsKey(processFunction));

        var aliasBinding = analyzedModule.analysisData().symbolBindings().get(aliasInitializer);
        assertNotNull(aliasBinding);
        assertEquals(FrontendBindingKind.PARAMETER, aliasBinding.kind());

        var aliasType = analyzedModule.analysisData().expressionTypes().get(aliasInitializer);
        assertNotNull(aliasType);
        assertEquals(GdVariantType.VARIANT, aliasType.publishedType());
        assertEquals(GdVariantType.VARIANT, analyzedModule.analysisData().slotTypes().get(aliasVariable));
    }

    private static @NotNull AnalyzedModule analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyze(List.of(new SourceSpec(fileName, source)));
    }

    private static @NotNull AnalyzedModule analyze(@NotNull List<SourceSpec> sources) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var units = sources.stream()
                .map(sourceSpec -> parseUnit(parserService, sourceSpec, diagnosticManager))
                .toList();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", units),
                classRegistry,
                diagnosticManager
        );
        return new AnalyzedModule(units, analysisData, diagnosticManager, classRegistry);
    }

    private static @NotNull PreparedVirtualOverrideInput prepareVirtualOverrideInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return prepareVirtualOverrideInput(List.of(new SourceSpec(fileName, source)));
    }

    private static @NotNull PreparedVirtualOverrideInput prepareVirtualOverrideInput(
            @NotNull List<SourceSpec> sources
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var units = sources.stream()
                .map(sourceSpec -> parseUnit(parserService, sourceSpec, diagnosticManager))
                .toList();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", units),
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
        new FrontendVarTypePostAnalyzer().analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendAnnotationUsageAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return new PreparedVirtualOverrideInput(units, analysisData, diagnosticManager, classRegistry);
    }

    private static @NotNull FrontendSourceUnit parseUnit(
            @NotNull GdScriptParserService parserService,
            @NotNull SourceSpec sourceSpec,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        return parserService.parseUnit(Path.of("tmp", sourceSpec.fileName()), sourceSpec.source(), diagnosticManager);
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull List<FrontendDiagnostic> diagnostics,
            @NotNull String category
    ) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static @NotNull ClassDef findClassDef(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull String className
    ) {
        return analysisData.moduleSkeleton().sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::topLevelClassDef)
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private static @NotNull FunctionDef findFunctionDef(
            @NotNull ClassDef classDef,
            @NotNull String functionName
    ) {
        return classDef.getFunctions().stream()
                .filter(function -> function.getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + functionName));
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

    private record SourceSpec(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }

    private record AnalyzedModule(
            @NotNull List<FrontendSourceUnit> units,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull ClassRegistry classRegistry
    ) {
        private AnalyzedModule {
            units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
            analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        }
    }

    private record PreparedVirtualOverrideInput(
            @NotNull List<FrontendSourceUnit> units,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull ClassRegistry classRegistry
    ) {
        private PreparedVirtualOverrideInput {
            units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
            analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        }
    }
}
