package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.PropertyDef;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAnnotationUsageAnalyzerTest {
    @Test
    void analyzeRejectsMissingPublishedSourceScopeBoundary() throws Exception {
        var preparedInput = prepareAnnotationUsageInput("missing_annotation_usage_scope.gd", """
                class_name MissingAnnotationUsageScope
                extends Node

                @onready var child: Variant = null
                """);
        preparedInput.analysisData().scopesByAst().remove(preparedInput.unit().ast());

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new FrontendAnnotationUsageAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains(preparedInput.unit().path().toString()));
    }

    @Test
    void analyzeAllowsOnreadyOnNonStaticNodePropertyWithoutChangingRetention() throws Exception {
        var analyzedModule = analyze("""
                class_name ValidOnreadyOwner
                extends Node

                @onready var child: Variant = null
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertTrue(annotationUsageDiagnostics.isEmpty());
        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.unsupported_annotation"
        ).isEmpty());

        var classDef = findClassDef(analyzedModule.analysisData(), "ValidOnreadyOwner");
        var propertyDef = findProperty(classDef, "child");
        assertEquals("", propertyDef.getAnnotations().get("onready"));
    }

    @Test
    void analyzeReportsOnreadyUsageForNonNodeStaticAndNonPropertyPlacements() throws Exception {
        var analyzedModule = analyze(List.of(
                new SourceSpec("non_node_onready.gd", """
                        class_name NonNodeOnready
                        extends RefCounted

                        @onready var child: Variant = null
                        """),
                new SourceSpec("static_local_onready.gd", """
                        class_name StaticLocalOnready
                        extends Node

                        @onready static var child: Variant = null

                        func ping():
                            @onready var local = null
                        """)
        ));

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(3, annotationUsageDiagnostics.size());
        assertTrue(annotationUsageDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.range() != null
        ));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(Path.of("tmp", "non_node_onready.gd"))
                        && diagnostic.message().contains("inherits from Node")
        ));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(Path.of("tmp", "static_local_onready.gd"))
                        && diagnostic.message().contains("static property 'child'")
        ));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(Path.of("tmp", "static_local_onready.gd"))
                        && diagnostic.message().contains("class properties declared with 'var'")
        ));
        assertFalse(analyzedModule.analysisData().diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_annotation")
                        && diagnostic.message().contains("@onready")
        ));

        var nonNodeClass = findClassDef(analyzedModule.analysisData(), "NonNodeOnready");
        var staticLocalClass = findClassDef(analyzedModule.analysisData(), "StaticLocalOnready");
        assertEquals("", findProperty(nonNodeClass, "child").getAnnotations().get("onready"));
        assertEquals("", findProperty(staticLocalClass, "child").getAnnotations().get("onready"));
    }

    private static @NotNull AnalyzedModule analyze(@NotNull String source) throws Exception {
        return analyze(List.of(new SourceSpec("annotation_usage.gd", source)));
    }

    private static @NotNull AnalyzedModule analyze(@NotNull List<SourceSpec> sources) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var units = sources.stream()
                .map(sourceSpec -> parseUnit(parserService, sourceSpec, diagnosticManager))
                .toList();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                "test_module",
                units,
                classRegistry,
                diagnosticManager
        );
        return new AnalyzedModule(units, analysisData, diagnosticManager, classRegistry);
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
        return analysisData.moduleSkeleton().classDefs().stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private static @NotNull PropertyDef findProperty(
            @NotNull ClassDef classDef,
            @NotNull String propertyName
    ) {
        return classDef.getProperties().stream()
                .filter(property -> property.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + propertyName));
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

    private record PreparedAnnotationUsageInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull ClassRegistry classRegistry
    ) {
        private PreparedAnnotationUsageInput {
            unit = Objects.requireNonNull(unit, "unit must not be null");
            analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        }
    }

    private static @NotNull PreparedAnnotationUsageInput prepareAnnotationUsageInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnosticManager);
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
        return new PreparedAnnotationUsageInput(unit, analysisData, diagnosticManager, classRegistry);
    }
}
