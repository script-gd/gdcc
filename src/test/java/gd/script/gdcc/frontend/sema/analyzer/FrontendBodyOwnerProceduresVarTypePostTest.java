package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendBodyOwnerProceduresVarTypePostTest {
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
                .noneMatch(diagnostic -> diagnostic.category().equals(
                        FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY
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
                        FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY
                )));
    }

    @Test
    void analyzePublishesSupportedForBodyLocalSlotType() throws Exception {
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

        assertEquals(GdVariantType.VARIANT, analyzed.analysisData().slotTypes().get(fromFor));
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
                diagnostic.category().equals(FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY)
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
                diagnostic.category().equals(FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY)
                        && diagnostic.message().contains("Local variable 'stable'")
                        && diagnostic.message().contains("if-body of function 'ping'")
                        && diagnostic.message().contains("surviving slot currently resolves to another accepted local declaration")
        ));
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
}
