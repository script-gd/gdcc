package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendVarTypePostAnalyzerTest {
    @Test
    void analyzePublishesParameterAndCallableLocalSlotTypesFromSharedPipeline() throws Exception {
        var analyzed = analyzeShared(
                "var_type_post_shared_pipeline.gd",
                """
                        class_name VarTypePostSharedPipeline
                        extends Node
                        
                        var property_value := 1
                        
                        func ping(seed: int, alias):
                            var inferred := seed
                            var typed: int
                            return inferred
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast().statements(), "ping");
        var inferred = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("inferred")
        );
        var typed = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("typed")
        );
        var propertyValue = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("property_value")
        );

        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(pingFunction.parameters().getFirst()));
        assertEquals(GdVariantType.VARIANT, analyzed.analysisData().slotTypes().get(pingFunction.parameters().getLast()));
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(inferred));
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(typed));
        assertNull(analyzed.analysisData().slotTypes().get(propertyValue));
        assertFalse(analyzed.diagnostics().hasErrors(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
    }

    @Test
    void analyzePublishesPreStabilizedCallableLocalSlotTypesWithoutRewritingThem() throws Exception {
        var analyzed = analyzeShared(
                "var_type_post_pre_stabilized_locals.gd",
                """
                        class_name VarTypePostPreStabilizedLocals
                        extends RefCounted

                        class Point:
                            var marker: int = -1

                        func make_point() -> Point:
                            return Point.new()

                        func ping():
                            var point := make_point()
                            var alias := point
                            return alias.marker
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast().statements(), "ping");
        var point = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("point")
        );
        var alias = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("alias")
        );

        var pointType = analyzed.analysisData().slotTypes().get(point);
        var aliasType = analyzed.analysisData().slotTypes().get(alias);
        assertNotNull(pointType);
        assertNotNull(aliasType);
        assertTypeNameEndsWith(pointType, "Point");
        assertTypeNameEndsWith(aliasType, "Point");
        assertTrue(analyzed.diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.category().equals(FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY)));
    }

    @Test
    void analyzeRepublishesSettledAliasSlotsAndClearsStaleSlotFacts() throws Exception {
        var prepared = preparePublishedInput(
                "var_type_post_republish_settled_alias_slots.gd",
                """
                        class_name VarTypePostRepublishSettledAliasSlots
                        extends RefCounted

                        class Point:
                            var marker: int = -1

                        func make_point() -> Point:
                            return Point.new()

                        func ping():
                            var point := make_point()
                            var alias := point
                            return alias.marker
                        """
        );

        var pingFunction = findFunction(prepared.unit().ast().statements(), "ping");
        var point = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("point")
        );
        var alias = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("alias")
        );
        prepared.analysisData().slotTypes().put(point, GdVariantType.VARIANT);
        prepared.analysisData().slotTypes().put(alias, GdVariantType.VARIANT);

        new FrontendVarTypePostAnalyzer().analyze(prepared.analysisData(), prepared.diagnosticManager());

        var pointType = prepared.analysisData().slotTypes().get(point);
        var aliasType = prepared.analysisData().slotTypes().get(alias);
        assertNotNull(pointType);
        assertNotNull(aliasType);
        assertTypeNameEndsWith(pointType, "Point");
        assertTypeNameEndsWith(aliasType, "Point");
        assertTrue(prepared.diagnosticManager().snapshot().asList().stream()
                .noneMatch(diagnostic -> diagnostic.category().equals(
                        FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY
                )));
    }

    @Test
    void analyzePublishesTrueDynamicInferredLocalSlotsAsVariant() throws Exception {
        var analyzed = analyzeShared(
                "var_type_post_dynamic_fail_closed_slots.gd",
                """
                        class_name VarTypePostDynamicFailClosedSlots
                        extends RefCounted

                        func ping(dynamic_host):
                            var point := dynamic_host.next
                            var alias := point
                            return alias.member
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast().statements(), "ping");
        var point = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("point")
        );
        var alias = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("alias")
        );

        assertEquals(GdVariantType.VARIANT, analyzed.analysisData().slotTypes().get(point));
        assertEquals(GdVariantType.VARIANT, analyzed.analysisData().slotTypes().get(alias));
        assertFalse(analyzed.diagnostics().hasErrors(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        assertTrue(analyzed.diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.category().equals(
                        FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY
                )));
    }

    @Test
    void analyzeKeepsUnsupportedForBodyLocalsOutOfPublishedSlotTypeTable() throws Exception {
        var analyzed = analyzeShared(
                "var_type_post_unsupported_for_local.gd",
                """
                        class_name VarTypePostUnsupportedForLocal
                        extends Node
                        
                        func ping(values):
                            for value in values:
                                var from_for := value
                            return values
                        """
        );

        var fromFor = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("from_for")
        );
        var pingFunction = findFunction(analyzed.unit().ast().statements(), "ping");

        assertNull(analyzed.analysisData().slotTypes().get(fromFor));
        assertEquals(
                GdVariantType.VARIANT,
                analyzed.analysisData().slotTypes().get(pingFunction.parameters().getFirst())
        );
    }

    @Test
    void analyzeWarnsWhenDuplicateLocalCouldNotPublishSlotType() throws Exception {
        var analyzed = analyzeShared(
                "var_type_post_duplicate_local.gd",
                """
                        class_name VarTypePostDuplicateLocal
                        extends Node
                        
                        func ping():
                            var stable := 1
                            var stable := 2
                            return stable
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast().statements(), "ping");
        var stable = assertInstanceOf(VariableDeclaration.class, pingFunction.body().statements().getFirst());
        var duplicateStable = assertInstanceOf(VariableDeclaration.class, pingFunction.body().statements().get(1));

        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(stable));
        assertNull(analyzed.analysisData().slotTypes().get(duplicateStable));
        assertTrue(analyzed.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals(FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY)
                        && diagnostic.message().contains("Local variable 'stable'")
                        && diagnostic.message().contains("surviving slot currently resolves to another accepted local declaration")
        ));
    }

    @Test
    void analyzeWarnsWhenShadowingLocalCouldNotPublishSlotTypeButKeepsValidFacts() throws Exception {
        var analyzed = analyzeShared(
                "var_type_post_shadowing_local.gd",
                """
                        class_name VarTypePostShadowingLocal
                        extends Node
                        
                        func ping(seed: int):
                            var stable := seed
                            if seed > 0:
                                var stable := 1
                            return stable
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast().statements(), "ping");
        var stable = assertInstanceOf(VariableDeclaration.class, pingFunction.body().statements().getFirst());
        var shadowingStable = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration != stable && variableDeclaration.name().equals("stable")
        );

        assertTrue(analyzed.diagnostics().hasErrors());
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(pingFunction.parameters().getFirst()));
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(stable));
        assertNull(analyzed.analysisData().slotTypes().get(shadowingStable));
        assertTrue(analyzed.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals(FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY)
                        && diagnostic.message().contains("Local variable 'stable'")
                        && diagnostic.message().contains("if-body of function 'ping'")
                        && diagnostic.message().contains("surviving slot currently resolves to another accepted local declaration")
        ));
    }

    @Test
    void analyzeFailsFastWhenSupportedParameterScopeIsMissing() throws Exception {
        var prepared = preparePublishedInput(
                "var_type_post_missing_parameter_scope.gd",
                """
                        class_name VarTypePostMissingParameterScope
                        extends Node
                        
                        func ping(seed: int):
                            return seed
                        """
        );
        var parameter = findNode(
                prepared.unit().ast(),
                Parameter.class,
                candidate -> candidate.name().equals("seed")
        );
        prepared.analysisData().scopesByAst().remove(parameter);

        var error = assertThrows(
                IllegalStateException.class,
                () -> new FrontendVarTypePostAnalyzer().analyze(prepared.analysisData(), prepared.diagnosticManager())
        );

        assertTrue(error.getMessage().contains("Parameter 'seed'"));
        assertTrue(error.getMessage().contains(prepared.unit().path().toString()));
    }

    @Test
    void analyzeFailsFastWhenSupportedLocalInventorySlotIsMissing() throws Exception {
        var prepared = preparePublishedInput(
                "var_type_post_missing_local_slot.gd",
                """
                        class_name VarTypePostMissingLocalSlot
                        extends Node
                        
                        func ping():
                            var local := 1
                            return local
                        """
        );
        var local = findNode(
                prepared.unit().ast(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("local")
        );
        var brokenCallableScope = new CallableScope(
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                CallableScopeKind.FUNCTION_DECLARATION
        );
        var brokenBlockScope = new BlockScope(brokenCallableScope, BlockScopeKind.FUNCTION_BODY);
        prepared.analysisData().scopesByAst().put(local, brokenBlockScope);

        var error = assertThrows(
                IllegalStateException.class,
                () -> new FrontendVarTypePostAnalyzer().analyze(prepared.analysisData(), prepared.diagnosticManager())
        );

        assertTrue(error.getMessage().contains("Local variable 'local'"));
        assertTrue(error.getMessage().contains(prepared.unit().path().toString()));
    }

    private static @NotNull AnalyzedScript analyzeShared(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());

        var diagnosticManager = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnosticManager
        );
        return new AnalyzedScript(unit, analysisData, diagnosticManager.snapshot());
    }

    private static @NotNull PreparedVarTypeInput preparePublishedInput(
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
        new FrontendTopBindingAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendLocalTypeStabilizationAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendChainBindingAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendExprTypeAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return new PreparedVarTypeInput(unit, analysisData, diagnosticManager);
    }

    private static @NotNull FunctionDeclaration findFunction(
            @NotNull List<Statement> statements,
            @NotNull String functionName
    ) {
        for (var statement : statements) {
            if (statement instanceof FunctionDeclaration functionDeclaration
                    && functionDeclaration.name().equals(functionName)) {
                return functionDeclaration;
            }
        }
        throw new AssertionError("Function not found: " + functionName);
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
                // Keep scanning remaining siblings until one subtree matches.
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private static void assertTypeNameEndsWith(@NotNull GdType type, @NotNull String suffix) {
        assertNotNull(type);
        assertTrue(
                type.getTypeName().endsWith(suffix),
                () -> "Expected type ending with '" + suffix + "', but was '" + type.getTypeName() + "'"
        );
    }

    private record AnalyzedScript(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
    }

    private record PreparedVarTypeInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
    }
}
