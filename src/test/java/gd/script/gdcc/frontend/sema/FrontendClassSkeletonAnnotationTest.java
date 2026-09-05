package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.scope.ClassRegistry;
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
        assertEquals(
                FrontendDiagnostic.sourcePathText(Path.of("tmp", "unsupported_property_annotation.gd")),
                diagnostic.sourcePath()
        );
        assertNotNull(diagnostic.range());
    }

    /// A zero-argument `@tool` at the script top marks the whole script: mirroring Godot's
    /// `_prepare_compilation`, the flag propagates to every class compiled from the file, while
    /// only the top-level class records the `tool` class annotation for LIR roundtrip.
    @Test
    void buildMarksToolScriptOnTopLevelAndInnerClasses() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "tool_script.gd"), """
                @tool
                class_name ToolScript
                extends Node
                
                class Inner:
                    var x := 1
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var relation = result.sourceClassRelations().getFirst();
        var topLevel = relation.topLevelClassDef();
        var inner = relation.innerClassRelations().getFirst().classDef();

        assertTrue(topLevel.isTool());
        assertEquals("", topLevel.getAnnotation("tool"));
        assertTrue(inner.isTool());
        assertFalse(inner.hasAnnotation("tool"));
        assertEquals(List.of("tool"), annotationNames(analysisData.annotationsByAst().get(unit.ast())));
        assertTrue(result.diagnostics().isEmpty());
    }

    /// Comments and a leading docstring are transparent trivia: a top-level `@tool` written after
    /// them still attaches to the `SourceFile` and marks the script.
    @Test
    void buildMarksToolScriptAfterLeadingCommentAndDocstring() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "tool_after_trivia.gd"), """
                # leading comment
                \"\"\"Script documentation.\"\"\"
                @tool
                class_name TriviaTool
                extends Node
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );

        assertTrue(result.sourceClassRelations().getFirst().topLevelClassDef().isTool());
        assertEquals(List.of("tool"), annotationNames(analysisData.annotationsByAst().get(unit.ast())));
        assertTrue(result.diagnostics().isEmpty());
    }

    /// A mixed batch is classified per annotation instead of all-or-nothing: `@tool` before
    /// `@export var hp` still attaches to the `SourceFile` while `@export` attaches to the
    /// property.
    @Test
    void buildSplitsMixedToolAndExportBatch() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "mixed_batch.gd"), """
                @tool
                @export var hp: int
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = result.sourceClassRelations().getFirst().topLevelClassDef();
        var hpVariable = findVariableByName(unit, "hp");

        assertTrue(classDef.isTool());
        assertEquals("", findPropertyByName(classDef, "hp").getAnnotations().get("export"));
        assertEquals(List.of("export"), annotationNames(analysisData.annotationsByAst().get(hpVariable)));
        assertTrue(result.diagnostics().isEmpty());
    }

    /// A member-target annotation closes the owner preamble: `@tool` written after `@export` is
    /// no longer script-level, attaches to the member, and must not mark the class. The skeleton
    /// keeps it retention-only; the placement diagnostic is owned by the usage analyzer.
    @Test
    void buildDoesNotMarkToolWrittenAfterMemberAnnotation() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "tool_after_export.gd"), """
                @export
                @tool
                var hp: int
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = result.sourceClassRelations().getFirst().topLevelClassDef();
        var hpVariable = findVariableByName(unit, "hp");

        assertFalse(classDef.isTool());
        assertFalse(classDef.hasAnnotation("tool"));
        assertTrue(analysisData.annotationsByAst().getOrDefault(unit.ast(), List.of()).isEmpty());
        assertEquals(List.of("export", "tool"), annotationNames(analysisData.annotationsByAst().get(hpVariable)));
        assertEquals("", findPropertyByName(classDef, "hp").getAnnotations().get("export"));
        assertFalse(findPropertyByName(classDef, "hp").getAnnotations().containsKey("tool"));
        assertTrue(result.diagnostics().isEmpty());
    }

    /// Class/root-compatible annotations do not close the owner preamble (Godot `parse_program`
    /// parity): `@tool` after `@abstract` or `@warning_ignore` is still script-level.
    @Test
    void buildMarksToolAfterClassRootCompatibleAnnotations() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var abstractUnit = parserService.parseUnit(Path.of("tmp", "abstract_tool.gd"), """
                @abstract
                @tool
                extends Node
                """, diagnostics);
        var warningUnit = parserService.parseUnit(Path.of("tmp", "warning_tool.gd"), """
                @warning_ignore("unused_variable")
                @tool
                extends Node
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(abstractUnit, warningUnit)),
                registry,
                diagnostics,
                analysisData
        );

        for (var relation : result.sourceClassRelations()) {
            assertTrue(relation.topLevelClassDef().isTool(), relation.canonicalName());
        }
        assertEquals(List.of("tool"), annotationNames(analysisData.annotationsByAst().get(abstractUnit.ast())));
        assertEquals(List.of("tool"), annotationNames(analysisData.annotationsByAst().get(warningUnit.ast())));
        assertTrue(result.diagnostics().isEmpty());
    }

    /// `@tool` on an inner class stays retention-only on the `ClassDeclaration`: it never marks
    /// any class and never reaches the unsupported-annotation diagnostic path.
    @Test
    void buildKeepsInnerClassToolAsRetentionOnly() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "inner_tool.gd"), """
                class_name InnerTool
                extends Node
                
                class Inner:
                    @tool
                    var x := 1
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var relation = result.sourceClassRelations().getFirst();
        var innerDeclaration = relation.innerClassRelations().getFirst().declaration();

        assertFalse(relation.topLevelClassDef().isTool());
        assertFalse(relation.innerClassRelations().getFirst().classDef().isTool());
        assertEquals(List.of("tool"), annotationNames(analysisData.annotationsByAst().get(innerDeclaration)));
        assertTrue(result.diagnostics().isEmpty());
    }

    /// A trailing `@tool` at the end of a non-empty list anchors on its own `AnnotationStatement`
    /// instead of being dropped or mis-attached to the `SourceFile` / previous statement.
    @Test
    void buildAnchorsTrailingToolOnItsOwnAnnotationStatement() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "trailing_tool.gd"), """
                class_name TrailingTool
                extends Node
                
                var hp := 1
                
                @tool
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = result.sourceClassRelations().getFirst().topLevelClassDef();
        var hpVariable = findVariableByName(unit, "hp");
        var trailingAnnotationStatement = unit.ast().statements().stream()
                .filter(dev.superice.gdparser.frontend.ast.AnnotationStatement.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Trailing annotation statement not found"));

        assertFalse(classDef.isTool());
        assertTrue(analysisData.annotationsByAst().getOrDefault(unit.ast(), List.of()).isEmpty());
        assertTrue(analysisData.annotationsByAst().getOrDefault(hpVariable, List.of()).isEmpty());
        assertEquals(
                List.of("tool"),
                annotationNames(analysisData.annotationsByAst().get(trailingAnnotationStatement))
        );
        assertTrue(result.diagnostics().isEmpty());
    }

    /// `@tool` with arguments never marks the script, but a coexisting legal zero-argument `@tool`
    /// still does; the arity diagnostic is owned by the usage analyzer.
    @Test
    void buildMarksToolWhenValidAndArgumentCarryingToolCoexist() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "mixed_tool.gd"), """
                @tool
                @tool(1)
                class_name MixedTool
                extends Node
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );

        assertTrue(result.sourceClassRelations().getFirst().topLevelClassDef().isTool());
        assertEquals(2, analysisData.annotationsByAst().get(unit.ast()).size());
        assertTrue(result.diagnostics().isEmpty());
    }

    /// `@tool` / `@icon` mis-attached to a member are retention-only: no property metadata and no
    /// `sema.unsupported_annotation` (their placement diagnostics belong to the usage analyzer).
    @Test
    void buildKeepsToolAndIconOnMemberAsRetentionOnly() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "member_tool_icon.gd"), """
                class_name MemberToolIcon
                extends Node
                
                var a := 0
                @tool
                @icon("res://icon.svg")
                var hp := 1
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = result.sourceClassRelations().getFirst().topLevelClassDef();
        var hpVariable = findVariableByName(unit, "hp");

        assertFalse(classDef.isTool());
        assertTrue(findPropertyByName(classDef, "hp").getAnnotations().isEmpty());
        assertEquals(List.of("tool", "icon"), annotationNames(analysisData.annotationsByAst().get(hpVariable)));
        assertTrue(result.diagnostics().isEmpty());
    }

    /// `@tool` with arguments alone never marks the script: the skeleton consumes only the
    /// zero-argument form, and the arity diagnostic belongs to the usage analyzer.
    @Test
    void buildDoesNotMarkToolWithArgumentsOnly() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "tool_with_args_only.gd"), """
                @tool(1)
                class_name ToolWithArgsOnly
                extends Node
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = result.sourceClassRelations().getFirst().topLevelClassDef();

        assertFalse(classDef.isTool());
        assertFalse(classDef.hasAnnotation("tool"));
        assertEquals(List.of("tool"), annotationNames(analysisData.annotationsByAst().get(unit.ast())));
        assertTrue(result.diagnostics().isEmpty());
    }

    /// When a list contains only `@export` + `@tool`, the member-target `@export` closes the
    /// preamble during the list-end flush, so `@tool` anchors on its own `AnnotationStatement`
    /// instead of the `SourceFile` (which would wrongly mark the script).
    @Test
    void buildAnchorsToolAfterExportAtListEndOnOwnStatement() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "export_then_tool_only.gd"), """
                @export
                @tool
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = result.sourceClassRelations().getFirst().topLevelClassDef();
        var statements = unit.ast().statements();

        assertFalse(classDef.isTool());
        assertFalse(classDef.hasAnnotation("tool"));
        assertTrue(analysisData.annotationsByAst().getOrDefault(unit.ast(), List.of()).isEmpty());
        assertEquals(2, statements.size());
        assertEquals(List.of("export"), annotationNames(analysisData.annotationsByAst().get(statements.get(0))));
        assertEquals(List.of("tool"), annotationNames(analysisData.annotationsByAst().get(statements.get(1))));
        assertTrue(result.diagnostics().isEmpty());
    }

    /// Well-formed export variants map to their Godot hint_string metadata during skeleton build.
    @Test
    void buildWritesExportVariantHintStringMetadata() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "export_variants.gd"), """
                class_name ExportVariants
                extends Node
                
                @export_range(0, 20, 0.5) var speed: float
                @export_enum("A", 'B') var choice: String
                @export_range(0, 1) var undetermined
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "ExportVariants");

        assertEquals("0,20,0.5", findPropertyByName(classDef, "speed").getAnnotations().get("export_range"));
        assertEquals("A,B", findPropertyByName(classDef, "choice").getAnnotations().get("export_enum"));
        // Undetermined Variant keeps its declared type and the hint_string only joins the
        // explicitly written arguments (no materialized default step).
        var undetermined = findPropertyByName(classDef, "undetermined");
        assertEquals("0,1", undetermined.getAnnotations().get("export_range"));
        assertEquals("Variant", undetermined.getType().getTypeName());
        assertTrue(result.diagnostics().isEmpty());
    }

    /// Malformed export arguments stay retention-only during skeleton build: no metadata, no
    /// diagnostic (the usage analyzer owns argument diagnostics).
    @Test
    void buildRetainsMalformedExportArgumentsWithoutDiagnostics() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "malformed_export.gd"), """
                class_name MalformedExport
                extends Node
                
                @export_range(0)
                var speed: float
                """, diagnostics);

        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "MalformedExport");
        var speedVariable = findVariableByName(unit, "speed");

        assertTrue(findPropertyByName(classDef, "speed").getAnnotations().isEmpty());
        assertEquals(List.of("export_range"), annotationNames(analysisData.annotationsByAst().get(speedVariable)));
        assertTrue(result.diagnostics().isEmpty());
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
