package dev.superice.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendClassSkeletonAnnotationTest {
    @Test
    void buildPreservesExportAndOnreadyPropertyAnnotationsFromLeadingAnnotations() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "annotated_props.gd"), """
                class_name AnnotatedProps
                extends Node
                
                @export var hp: int = 1
                @onready var target = $Node
                var plain := 3
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "AnnotatedProps");
        var hpProperty = findPropertyByName(classDef, "hp");
        var targetProperty = findPropertyByName(classDef, "target");
        var plainProperty = findPropertyByName(classDef, "plain");
        var hpVariable = findVariableByName(unit, "hp");
        var targetVariable = findVariableByName(unit, "target");

        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals("", hpProperty.getAnnotations().get("export"));
        assertFalse(hpProperty.getAnnotations().containsKey("onready"));
        assertEquals(List.of("export"), annotationNames(analysisData.annotationsByAst().get(hpVariable)));

        assertEquals("", targetProperty.getAnnotations().get("onready"));
        assertFalse(targetProperty.getAnnotations().containsKey("export"));
        assertEquals(List.of("onready"), annotationNames(analysisData.annotationsByAst().get(targetVariable)));

        assertTrue(plainProperty.getAnnotations().isEmpty());
    }

    @Test
    void buildIgnoresRegionAndUnrelatedAnnotationsForPropertyRetention() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "ignored_annotations.gd"), """
                class_name IgnoredAnnotations
                extends Node
                
                @warning_ignore_start("unused_variable")
                var tmp := 1
                
                @warning_ignore_restore("unused_variable")
                var keep := 2
                
                @rpc("authority")
                func ping(value):
                    pass
                
                var after := 3
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "IgnoredAnnotations");

        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertTrue(findPropertyByName(classDef, "tmp").getAnnotations().isEmpty());
        assertTrue(findPropertyByName(classDef, "keep").getAnnotations().isEmpty());
        assertTrue(findPropertyByName(classDef, "after").getAnnotations().isEmpty());
    }

    @Test
    void buildReportsUnsupportedPropertyAnnotationsButKeepsThemInSharedAnalysisData() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "unsupported_property_annotation.gd"), """
                class_name UnsupportedPropertyAnnotation
                extends Node
                
                @warning_ignore("unused_variable")
                var hp := 1
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "UnsupportedPropertyAnnotation");
        var hpProperty = findPropertyByName(classDef, "hp");
        var hpVariable = findVariableByName(unit, "hp");

        assertTrue(hpProperty.getAnnotations().isEmpty());
        assertEquals(List.of("warning_ignore"), annotationNames(analysisData.annotationsByAst().get(hpVariable)));
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals(1, result.diagnostics().size());

        var diagnostic = result.diagnostics().getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("sema.unsupported_annotation", diagnostic.category());
        assertTrue(diagnostic.message().contains("@warning_ignore"));
        assertTrue(diagnostic.message().contains("hp"));
        assertEquals(Path.of("tmp", "unsupported_property_annotation.gd"), diagnostic.sourcePath());
        assertNotNull(diagnostic.range());
    }

    private LirClassDef findClassByName(List<LirClassDef> classDefs, String className) {
        return classDefs.stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private List<LirClassDef> topLevelClassDefs(FrontendModuleSkeleton result) {
        return result.sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::topLevelClassDef)
                .toList();
    }

    private LirPropertyDef findPropertyByName(LirClassDef classDef, String propertyName) {
        return classDef.getProperties().stream()
                .filter(propertyDef -> propertyDef.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + propertyName));
    }

    private VariableDeclaration findVariableByName(FrontendSourceUnit unit, String variableName) {
        return unit.ast().statements().stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(variableDeclaration -> variableDeclaration.name().equals(variableName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + variableName));
    }

    private List<String> annotationNames(List<FrontendGdAnnotation> annotations) {
        return annotations.stream().map(FrontendGdAnnotation::name).toList();
    }
}
