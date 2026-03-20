package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.sema.debug.FrontendAnalysisInspectionTool;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAnalysisInspectionToolTest {
    @Test
    void inspectSingleScriptMatchesDirectRenderEntry() throws Exception {
        var tool = new FrontendAnalysisInspectionTool(defaultRegistry());
        var result = tool.inspectSingleScript(
                "test_module",
                Path.of("tmp", "inspection_basic.gd"),
                """
                        class_name InspectionBasic
                        extends Node
                        
                        func helper(seed) -> String:
                            return "ok"
                        
                        func ping(seed):
                            helper(seed)
                        """
        );

        assertEquals(
                result.report(),
                tool.renderSingleUnitReport("test_module", result.unit(), result.analysisData())
        );
    }

    @Test
    void reportShowsPublishedAndDerivedCallsAndRouteHeadUnpublishedExpressions() throws Exception {
        var report = inspect(
                "published_and_derived_calls.gd",
                """
                        class_name PublishedAndDerivedCalls
                        extends RefCounted
                        
                        class Worker:
                            static func build(seed) -> Worker:
                                return Worker.new()
                        
                        func helper(seed) -> String:
                            return "ok"
                        
                        func ping(seed):
                            Worker.build(seed)
                            helper(seed)
                        """
        ).report();

        assertTrue(report.contains("FORMAT frontend-analysis-text-v1"));
        assertTrue(report.contains("== func ping(seed) =="));
        assertTrue(report.contains("IdentifierExpression `Worker`"));
        assertTrue(report.contains("type.status = UNPUBLISHED"));
        assertTrue(report.contains("route-head TYPE_META is intentionally not published as ordinary value expression"));
        assertTrue(report.contains("call.source = published"));
        assertTrue(report.contains("callKind = STATIC_METHOD"));
        assertTrue(report.contains("call.source = derived"));
        assertTrue(report.contains("callKind = BARE_CALL_DERIVED"));
        assertTrue(report.contains("calleeBinding = METHOD"));
    }

    @Test
    void reportShowsDisplayOnlyUnpublishedAttributeCallFactsInsideConstInitializer() throws Exception {
        var report = inspect(
                "const_initializer_unpublished_call.gd",
                """
                        class_name ConstInitializerUnpublishedCall
                        extends RefCounted
                        
                        class Worker:
                            static func build() -> Worker:
                                return Worker.new()
                        
                        const Alias = Worker.build()
                        """
        ).report();

        assertTrue(report.contains("call.source = display"));
        assertTrue(report.contains("status = UNPUBLISHED"));
        assertTrue(report.contains("callKind = UNPUBLISHED_CALL_FACT"));
    }

    @Test
    void reportShowsDiagnosticsGloballyAndInlineForPropertyInitializerBoundary() throws Exception {
        var report = inspect(
                "property_initializer_diagnostic.gd",
                """
                        class_name PropertyInitializerDiagnostic
                        extends RefCounted
                        
                        var payload: int = 1
                        var copy := self.payload
                        """
        ).report();

        assertTrue(report.contains("DIAGNOSTICS"));
        assertTrue(report.contains("D0001 ERROR sema.unsupported_binding_subtree"));
        assertTrue(report.contains("self"));
        assertTrue(report.contains("diagnostics = [D0001]"));
        assertTrue(report.contains("type.status = BLOCKED"));
    }

    @Test
    void inspectSingleScriptKeepsCompileOnlyGateOutOfSharedInspectionPipeline() throws Exception {
        var inspected = inspect(
                "inspection_compile_gate_split.gd",
                """
                        class_name InspectionCompileGateSplit
                        extends Node
                        
                        func ping():
                            assert(1, "inspection stays on shared semantic facts")
                            [1]
                        """
        );

        assertFalse(inspected.analysisData().diagnostics().hasErrors());
        assertTrue(inspected.analysisData().diagnostics().asList().stream().noneMatch(diagnostic ->
                diagnostic.category().equals("sema.compile_check")
        ));
        assertFalse(inspected.report().contains("sema.compile_check"));
    }

    @Test
    void reportKeepsUtf8ExpressionSnippetsIntact() throws Exception {
        var report = inspect(
                "utf8_expression_snippet.gd",
                """
                        class_name Utf8ExpressionSnippet
                        extends Node
                        
                        func helper(text: String) -> String:
                            return text
                        
                        func ping():
                            helper("中文")
                        """
        ).report();

        assertTrue(report.contains("`helper(\"中文\")`"));
        assertTrue(report.contains("`\"中文\"`"));
    }

    private static @NotNull InspectionOutput inspect(@NotNull String fileName, @NotNull String source) throws Exception {
        var tool = new FrontendAnalysisInspectionTool(defaultRegistry());
        var result = tool.inspectSingleScript("test_module", Path.of("tmp", fileName), source);
        return new InspectionOutput(result.unit(), result.analysisData(), result.report());
    }

    private static @NotNull ClassRegistry defaultRegistry() throws Exception {
        return new ClassRegistry(ExtensionApiLoader.loadDefault());
    }

    private record InspectionOutput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull String report
    ) {
    }
}
