package gd.script.gdcc.frontend.sema.analyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendParseRecovery;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import gd.script.gdcc.frontend.sema.FrontendSourceClassRelation;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.PropertyDef;

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
        assertEquals(2, annotationUsageDiagnostics.size());
        assertTrue(annotationUsageDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.range() != null
        ));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(FrontendDiagnostic.sourcePathText(Path.of("tmp", "non_node_onready.gd")))
                        && diagnostic.message().contains("inherits from Node")
        ));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.sourcePath().equals(FrontendDiagnostic.sourcePathText(Path.of("tmp", "static_local_onready.gd")))
                        && diagnostic.message().contains("static property 'child'")
        ));
        var localErrors = diagnosticsByCategory(analyzedModule.analysisData().diagnostics().asList(),
                DiagnosticCodes.ANNOTATION_INVALID_LEVEL);
        assertEquals(1, localErrors.size());
        assertTrue(localErrors.getFirst().message().contains("@onready"));
        assertEquals("tmp/static_local_onready.gd", localErrors.getFirst().sourcePath());
        assertFalse(analyzedModule.analysisData().diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_annotation")
                        && diagnostic.message().contains("@onready")
        ));

        var nonNodeClass = findClassDef(analyzedModule.analysisData(), "NonNodeOnready");
        var staticLocalClass = findClassDef(analyzedModule.analysisData(), "StaticLocalOnready");
        assertEquals("", findProperty(nonNodeClass, "child").getAnnotations().get("onready"));
        assertEquals("", findProperty(staticLocalClass, "child").getAnnotations().get("onready"));
    }

    /// Godot rejects every repeat of `@onready` on the same variable ("can only be used once
    /// per variable"). Each annotation past the first reports the dedicated diagnostic at its
    /// own range, while a single `@onready` per property stays clean and the placement rules
    /// keep diagnosing the first annotation exactly once.
    @Test
    void analyzeReportsDuplicateOnreadyAnnotationsOnSameProperty() throws Exception {
        var analyzedModule = analyze("""
                class_name DuplicateOnready
                extends Node
                
                @onready var kept: Variant = null
                @onready
                @onready var child: Variant = null
                @onready
                @onready
                @onready var triple: Variant = null
                
                func ping():
                    @onready
                    @onready var local = null
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(3, annotationUsageDiagnostics.size(), annotationUsageDiagnostics::toString);
        assertTrue(annotationUsageDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.range() != null
        ));
        assertEquals(3, annotationUsageDiagnostics.stream()
                .filter(diagnostic -> diagnostic.message().contains("@onready can only be used once per variable"))
                .count());
        var localErrors = diagnosticsByCategory(analyzedModule.analysisData().diagnostics().asList(),
                DiagnosticCodes.ANNOTATION_INVALID_LEVEL);
        assertEquals(2, localErrors.size());
        assertTrue(localErrors.stream().allMatch(d -> d.message().contains("@onready")));

        var classDef = findClassDef(analyzedModule.analysisData(), "DuplicateOnready");
        assertEquals("", findProperty(classDef, "kept").getAnnotations().get("onready"));
        assertEquals("", findProperty(classDef, "child").getAnnotations().get("onready"));
        assertEquals("", findProperty(classDef, "triple").getAnnotations().get("onready"));
    }

    /// `@onready` requires `var` storage: a class-level `const` carries no per-instance field
    /// to initialize at ready time, so the placement check rejects it with the same
    /// class-property message used for local variables.
    @Test
    void analyzeReportsOnreadyOnConstPlacement() throws Exception {
        var analyzedModule = analyze("""
                class_name OnreadyConstPlacement
                extends Node
                
                @onready const CHILD = 1
                """);

        var sourceErrors = analyzedModule.analysisData().diagnostics().asList();
        assertEquals(1, sourceErrors.size());
        assertEquals(DiagnosticCodes.ANNOTATION_INVALID_TARGET, sourceErrors.getFirst().category());
        assertTrue(sourceErrors.getFirst().message().contains("cannot be applied to a constant"));

        var manager = new DiagnosticManager();
        var healthy = new GdScriptParserService().parseUnit(Path.of("tmp", "manual_onready.gd"), """
                extends Node
                const CHILD = 1
                """, manager);
        assertTrue(manager.isEmpty());
        var statements = new ArrayList<>(healthy.ast().statements());
        var constant = assertInstanceOf(VariableDeclaration.class, statements.removeLast());
        var annotation = new AnnotationStatement("onready", List.of(), constant.range());
        statements.add(annotation);
        statements.add(constant);
        var manual = new FrontendSourceUnit(healthy.path(), healthy.source(), new SourceFile(statements, healthy.ast().range()));
        var diagnostic = analyzeUnits(List.of(manual), manager).analysisData().diagnostics().asList().getFirst();
        assertEquals(1, manager.snapshot().asList().size());
        assertEquals("sema.annotation_usage", diagnostic.category());
        assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.message().contains("class properties declared with 'var'"));
        assertEquals(FrontendRange.fromAstRange(annotation.range()), diagnostic.range());
    }

    /// `@export` on a static var is rejected (Godot parity: export registers per-instance
    /// storage, which a static property never has). Retention in the property metadata is kept
    /// so the diagnostic stays the single source of truth for the rejection.
    @Test
    void analyzeReportsExportOnStaticPropertyWhileAllowingInstanceExport() throws Exception {
        var analyzedModule = analyze("""
                class_name ExportStaticPlacement
                extends RefCounted
                
                @export static var shared_total: int = 0
                @export var instance_total: int = 0
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(1, annotationUsageDiagnostics.size());
        var diagnostic = annotationUsageDiagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.message().contains("@export cannot be used on static property 'shared_total'"));
        assertNotNull(diagnostic.range());

        var classDef = findClassDef(analyzedModule.analysisData(), "ExportStaticPlacement");
        assertEquals("", findProperty(classDef, "shared_total").getAnnotations().get("export"));
        assertEquals("", findProperty(classDef, "instance_total").getAnnotations().get("export"));
    }

    /// The only legal `@tool` placement: zero arguments attached to the top-level `SourceFile`.
    @Test
    void analyzeAllowsToolAtScriptTop() throws Exception {
        var analyzedModule = analyze("""
                @tool
                class_name ToolAtTop
                extends Node
                """);

        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        ).isEmpty());
        assertTrue(findClassDef(analyzedModule.analysisData(), "ToolAtTop").isTool());
    }

    /// `@tool` on an inner class is rejected (Godot: `AnnotationInfo::SCRIPT`, not per-class).
    @Test
    void analyzeRejectsToolOnInnerClass() throws Exception {
        var analyzedModule = analyze("""
                class_name InnerToolOwner
                extends Node
                
                class Inner:
                    @tool
                    var x: int = 1
                """);

        assertParserOwnedToolPlacements(analyzedModule);

        assertFalse(findClassDef(analyzedModule.analysisData(), "InnerToolOwner").isTool());
        var innerClassDef = analyzedModule.analysisData().moduleSkeleton().sourceClassRelations().getFirst()
                .innerClassRelations().getFirst().classDef();
        assertFalse(innerClassDef.isTool());
    }

    /// `@tool` after a real statement (member position) or dangling at the end of a statement
    /// list is rejected; both placements must not mark any class as tool.
    @Test
    void analyzeRejectsToolOnMemberAndTrailingPositions() throws Exception {
        var analyzedModule = analyze(List.of(
                new SourceSpec("member_tool.gd", """
                        class_name MemberTool
                        extends Node
                        
                        var a: int = 0
                        @tool
                        var hp: int = 1
                        """),
                new SourceSpec("trailing_tool.gd", """
                        class_name TrailingToolUsage
                        extends Node
                        
                        var hp: int = 1
                        
                        @tool
                        """)
        ));

        assertParserOwnedToolPlacements(analyzedModule);
        assertFalse(findClassDef(analyzedModule.analysisData(), "MemberTool").isTool());
        assertFalse(findClassDef(analyzedModule.analysisData(), "TrailingToolUsage").isTool());
    }

    /// `@tool` never accepts arguments; a coexisting legal zero-argument `@tool` stays valid and
    /// still marks the script.
    @Test
    void analyzeRejectsToolWithArguments() throws Exception {
        var analyzedModule = analyze("""
                @tool
                @tool(1)
                class_name ToolWithArguments
                extends Node
                """);

        var diagnostics = analyzedModule.analysisData().diagnostics().asList();
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.stream().allMatch(d -> d.category().equals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN)
                && d.severity() == FrontendDiagnosticSeverity.ERROR));
        assertEquals(diagnostics.getFirst().range(), diagnostics.getLast().range());
        assertTrue(diagnostics.getFirst().message().contains("requires at most 0 arguments"));
        assertTrue(diagnostics.getLast().message().contains("can only be used once"));
        assertTrue(findClassDef(analyzedModule.analysisData(), "ToolWithArguments").isTool());
        var replay = new DiagnosticManager();
        new FrontendCompileCheckAnalyzer().analyze(analyzedModule.analysisData(), replay);
        assertEquals(diagnostics, replay.snapshot().asList());
    }

    /// `@tool` with arguments alone: exactly one arity error and no class is marked as tool.
    @Test
    void analyzeRejectsToolWithArgumentsOnly() throws Exception {
        var analyzedModule = analyze("""
                @tool(1)
                class_name ToolWithArgsOnly
                extends Node
                """);

        var diagnostics = analyzedModule.analysisData().diagnostics().asList();
        assertEquals(1, diagnostics.size());
        assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, diagnostics.getFirst().category());
        assertTrue(diagnostics.getFirst().message().contains("requires at most 0 arguments"));
        assertFalse(findClassDef(analyzedModule.analysisData(), "ToolWithArgsOnly").isTool());
        var fresh = analyzeUnits(analyzedModule.units(), new DiagnosticManager());
        var errors = fresh.analysisData().diagnostics().asList();
        assertEquals(1, errors.size());
        assertEquals("sema.annotation_usage", errors.getFirst().category());
        assertEquals(diagnostics.getFirst().range(), errors.getFirst().range());
        assertFalse(findClassDef(fresh.analysisData(), "ToolWithArgsOnly").isTool());
    }

    /// `@tool` directly before an inner `class` declaration attaches to that declaration (the
    /// preamble was already closed) and is rejected the same as inside the class body.
    @Test
    void analyzeRejectsToolBeforeInnerClassDeclaration() throws Exception {
        var analyzedModule = analyze("""
                class_name InnerToolPrefix
                extends Node
                
                @tool
                class Inner:
                    var x: int = 1
                """);

        assertParserOwnedToolPlacements(analyzedModule);
        assertFalse(findClassDef(analyzedModule.analysisData(), "InnerToolPrefix").isTool());
    }

    private static void assertParserOwnedToolPlacements(AnalyzedModule original) throws Exception {
        var fresh = analyzeUnits(original.units(), new DiagnosticManager());
        var originalDiagnostics = original.analysisData().diagnostics().asList();
        var freshDiagnostics = fresh.analysisData().diagnostics().asList();
        assertEquals(original.units().size(), originalDiagnostics.size());
        assertEquals(original.units().size(), freshDiagnostics.size());
        for (var unit : original.units()) {
            assertEquals(1, unit.parseRecoveries().size());
            var recovery = unit.parseRecoveries().getFirst();
            var annotation = assertInstanceOf(AnnotationStatement.class, recovery.owner());
            var range = FrontendRange.fromAstRange(annotation.range());
            var sourcePath = FrontendDiagnostic.sourcePathText(unit.path());
            assertEquals("tool", annotation.name());
            assertEquals("@tool", unit.source().substring(range.startByte(), range.endByte()));
            assertEquals("annotation.target", recovery.facet());
            assertEquals(DiagnosticCodes.ANNOTATION_INVALID_LEVEL, recovery.code());
            assertEquals(range, recovery.range());
            assertTrue(recovery.message().contains("must be at the top of the script"));

            var parserError = FrontendDiagnostic.error(recovery.code(), recovery.message(), sourcePath, range);
            assertTrue(originalDiagnostics.contains(parserError));
            assertEquals(parserError, original.analysisData().claimedParseRecoveries().get(recovery));
            assertTrue(original.analysisData().skippedSubtreeRoots().containsKey(annotation));

            var fallback = FrontendDiagnostic.error("sema.annotation_usage", "Invalid @tool syntax", sourcePath, range);
            assertTrue(freshDiagnostics.contains(fallback));
            assertEquals(fallback, fresh.analysisData().claimedParseRecoveries().get(recovery));
            assertTrue(fresh.analysisData().skippedSubtreeRoots().containsKey(annotation));
        }
    }

    /// When a list contains only `@export` + `@tool`, both trailing annotations anchor on their
    /// own `AnnotationStatement`: `@tool` is rejected as a non-top placement and the dangling
    /// `@export` is rejected as a non-property placement.
    @Test
    void analyzeRejectsTrailingToolAfterExportAtListEnd() throws Exception {
        var analyzedModule = analyze("""
                @export
                @tool
                """);

        var annotationUsageDiagnostics = analyzedModule.analysisData().diagnostics().asList();
        assertEquals(2, annotationUsageDiagnostics.size());
        assertTrue(annotationUsageDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR && diagnostic.range() != null
        ));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic -> diagnostic.category()
                .equals(DiagnosticCodes.ANNOTATION_INVALID_LEVEL) && diagnostic.message().contains("@tool")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic -> diagnostic.category()
                .equals(DiagnosticCodes.ANNOTATION_DANGLING) && diagnostic.message().contains("@export")));
    }

    /// Well-formed variants on compatible declared types: no diagnostics and the property
    /// metadata carries the Godot hint_string encoding.
    @Test
    void analyzeAllowsWellFormedExportVariantsOnCompatibleTypes() throws Exception {
        var analyzedModule = analyze("""
                class_name WellFormedExports
                extends Node
                
                @export_range(0, 20, 0.5) var speed: float
                @export_range(0, 100, 1, "or_greater") var level: int
                @export_enum("Warrior", "Mage") var archetype: String
                @export_flags("Fire", "Water") var elements: int
                @export_flags_2d_render var layers: int
                @export_file("*.png") var icon_path: String
                @export_file_path var rel_path: String
                @export_dir var folder: String
                @export_global_file var global_path: String
                @export_global_dir var global_folder: String
                @export_multiline var description: String
                @export_placeholder("Enter name...") var prompt: String
                @export_exp_easing("attenuation", "positive_only") var easing: float
                @export_color_no_alpha var tint: Color
                @export_node_path("Node2D", "Sprite2D") var target_path: NodePath
                """);

        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        ).isEmpty(), () -> analyzedModule.analysisData().diagnostics().asList().toString());
        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.unsupported_annotation"
        ).isEmpty());

        var classDef = findClassDef(analyzedModule.analysisData(), "WellFormedExports");
        assertEquals("0,20,0.5", findProperty(classDef, "speed").getAnnotations().get("export_range"));
        assertEquals("0,100,1,or_greater", findProperty(classDef, "level").getAnnotations().get("export_range"));
        assertEquals("Warrior,Mage", findProperty(classDef, "archetype").getAnnotations().get("export_enum"));
        assertEquals("Fire,Water", findProperty(classDef, "elements").getAnnotations().get("export_flags"));
        assertEquals("", findProperty(classDef, "layers").getAnnotations().get("export_flags_2d_render"));
        assertEquals("*.png", findProperty(classDef, "icon_path").getAnnotations().get("export_file"));
        assertEquals("", findProperty(classDef, "rel_path").getAnnotations().get("export_file_path"));
        assertEquals("", findProperty(classDef, "folder").getAnnotations().get("export_dir"));
        assertEquals("", findProperty(classDef, "global_path").getAnnotations().get("export_global_file"));
        assertEquals("", findProperty(classDef, "global_folder").getAnnotations().get("export_global_dir"));
        assertEquals("", findProperty(classDef, "description").getAnnotations().get("export_multiline"));
        assertEquals("Enter name...", findProperty(classDef, "prompt").getAnnotations().get("export_placeholder"));
        assertEquals("attenuation,positive_only", findProperty(classDef, "easing").getAnnotations().get("export_exp_easing"));
        assertEquals("", findProperty(classDef, "tint").getAnnotations().get("export_color_no_alpha"));
        assertEquals("Node2D,Sprite2D", findProperty(classDef, "target_path").getAnnotations().get("export_node_path"));
    }

    /// Script-enum-typed properties accept a bare `@export` end to end: the erased int type
    /// passes the usage checks and the skeleton-generated enum hint_string survives the full
    /// semantic pipeline on the property metadata.
    @Test
    void analyzeAllowsBareExportOnScriptEnumTypedProperty() throws Exception {
        var analyzedModule = analyze("""
                class_name EnumBareExport
                extends Node
                
                enum State { IDLE, JUMP = 5 }
                
                @export var current: State
                """);

        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        ).isEmpty(), () -> analyzedModule.analysisData().diagnostics().asList().toString());
        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.unsupported_annotation"
        ).isEmpty());

        var classDef = findClassDef(analyzedModule.analysisData(), "EnumBareExport");
        assertEquals("Idle:0,Jump:5", findProperty(classDef, "current").getAnnotations().get("export"));
    }

    /// Variant-declared properties fall back to the initializer's published type; a
    /// still-undetermined Variant skips the default type check (Godot parity) without the
    /// property type being rewritten.
    @Test
    void analyzeAllowsVariantFallbackAndUndeterminedVariant() throws Exception {
        var analyzedModule = analyze("""
                class_name VariantFallbackExports
                extends Node
                
                @export_range(0, 1) var inferred := 0.5
                @export_range(0, 1) var undetermined
                @export_placeholder("x") var prompt
                @export_flags("A") var float_flags: float
                @export_exp_easing var int_easing: int
                """);

        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        ).isEmpty(), () -> analyzedModule.analysisData().diagnostics().asList().toString());

        var classDef = findClassDef(analyzedModule.analysisData(), "VariantFallbackExports");
        assertEquals("0,1", findProperty(classDef, "inferred").getAnnotations().get("export_range"));
        assertEquals("0,1", findProperty(classDef, "undetermined").getAnnotations().get("export_range"));
        assertEquals("x", findProperty(classDef, "prompt").getAnnotations().get("export_placeholder"));
        assertEquals("A", findProperty(classDef, "float_flags").getAnnotations().get("export_flags"));
        assertEquals("", findProperty(classDef, "int_easing").getAnnotations().get("export_exp_easing"));
    }

    @Test
    void analyzeRejectsUndeterminableBareExportAndMultilineAndStringFallbackRange() throws Exception {
        var analyzedModule = analyze("""
                class_name UndeterminableExports
                extends Node
                
                @export var bare
                @export_multiline var text
                @export_range(0, 1) var inferred := "hi"
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(3, annotationUsageDiagnostics.size());
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export requires a determinable property type")
                        && diagnostic.message().contains("'bare'")
        ));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_multiline can only be used on properties of type String")
                        && diagnostic.message().contains("'text' is Variant")
        ));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_range can only be used on properties of type int or float")
                        && diagnostic.message().contains("'inferred'")
                        && diagnostic.message().contains("String")
        ));
        assertTrue(annotationUsageDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR && diagnostic.range() != null
        ));
    }

    @Test
    void analyzeRejectsMalformedExportArguments() throws Exception {
        var analyzedModule = analyze("""
                class_name MalformedExportArgs
                extends Node
                
                @export_range(0) var speed: float
                @export_enum var archetype: String
                @export_multiline("x") var description: String
                @export_range(MIN, 1) var level: float
                @export_enum("A,B") var joined: String
                @export_file("") var empty_filter: String
                @export_placeholder("") var placeholder: String
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(6, annotationUsageDiagnostics.size(), annotationUsageDiagnostics::toString);
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_range expects at least 2 arguments, but got 1 argument(s)")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_enum expects at least 1 argument, but got 0 argument(s)")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_multiline expects no arguments, but got 1 argument(s)")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_range argument 1 must be a number literal")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_enum argument 1 must not contain ','")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_file argument 1 must not be empty")));
        assertTrue(annotationUsageDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR && diagnostic.range() != null
        ));

        // Malformed arguments stay retention-only: no property metadata is written for them,
        // while the legal empty placeholder is written normally.
        var classDef = findClassDef(analyzedModule.analysisData(), "MalformedExportArgs");
        assertTrue(findProperty(classDef, "speed").getAnnotations().isEmpty());
        assertTrue(findProperty(classDef, "archetype").getAnnotations().isEmpty());
        assertTrue(findProperty(classDef, "description").getAnnotations().isEmpty());
        assertTrue(findProperty(classDef, "level").getAnnotations().isEmpty());
        assertTrue(findProperty(classDef, "joined").getAnnotations().isEmpty());
        assertTrue(findProperty(classDef, "empty_filter").getAnnotations().isEmpty());
        assertEquals("", findProperty(classDef, "placeholder").getAnnotations().get("export_placeholder"));
    }

    @Test
    void analyzeRejectsExportFamilyOnStaticLocalAndFunctionPlacements() throws Exception {
        var analyzedModule = analyze("""
                class_name ExportPlacements
                extends Node
                
                @export_range(0, 1) static var shared: int
                
                func ping():
                    @export var local := 1
                
                @export_enum("A")
                func pong():
                    pass
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(1, annotationUsageDiagnostics.size());
        assertTrue(annotationUsageDiagnostics.getFirst().message()
                .contains("@export_range cannot be used on static property 'shared'"));
        var errors = analyzedModule.analysisData().diagnostics().asList();
        assertEquals(3, errors.size());
        assertTrue(errors.stream().anyMatch(d -> d.category().equals(DiagnosticCodes.ANNOTATION_INVALID_LEVEL)
                && d.message().contains("Annotation \"@export\"")));
        assertTrue(errors.stream().anyMatch(d -> d.category().equals(DiagnosticCodes.ANNOTATION_INVALID_TARGET)
                && d.message().contains("cannot be applied to a function")));
        assertTrue(errors.stream().allMatch(d -> d.severity() == FrontendDiagnosticSeverity.ERROR && d.range() != null));
    }

    @Test
    void analyzeRejectsIncompatibleTypesAndContainerNarrowing() throws Exception {
        var analyzedModule = analyze("""
                class_name IncompatibleExportTypes
                extends Node
                
                @export_range(0, 1) var text: String
                @export_range(0, 1) var scores: Array[int]
                @export_enum("A") var choices: Array[int]
                @export_multiline var table: Dictionary
                @export_node_path var path_text: String
                @export_color_no_alpha var shade: String
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(6, annotationUsageDiagnostics.size(), annotationUsageDiagnostics::toString);
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_range can only be used on properties of type int or float, but 'text' is String")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("'scores' is Array[int]")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_enum can only be used on properties of type int, String, or Variant, but 'choices' is Array[int]")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_multiline can only be used on properties of type String, but 'table' is Dictionary")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_node_path can only be used on properties of type NodePath, but 'path_text' is String")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export_color_no_alpha can only be used on properties of type Color, but 'shade' is String")));
    }

    /// Bare `export` Object-family rules: Resource/Node-derived pass, other Object types fail,
    /// and Node exports require a Node-derived owner.
    @Test
    void analyzeValidatesBareExportObjectFamily() throws Exception {
        var analyzedModule = analyze(List.of(
                new SourceSpec("valid_object_exports.gd", """
                        class_name ValidObjectExports
                        extends Node
                        
                        @export var texture: Texture2D
                        @export var target: Node2D
                        """),
                new SourceSpec("invalid_object_exports.gd", """
                        class_name InvalidObjectExports
                        extends RefCounted
                        
                        @export var plain: RefCounted
                        @export var node: Node2D
                        """)
        ));

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(2, annotationUsageDiagnostics.size(), annotationUsageDiagnostics::toString);
        assertTrue(annotationUsageDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.sourcePath().equals(FrontendDiagnostic.sourcePathText(Path.of("tmp", "invalid_object_exports.gd")))
        ));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export can only export built-in, Resource, Node, or enum types, but 'plain' is RefCounted")));
        assertTrue(annotationUsageDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("@export of Node type is only supported in Node-derived classes, but 'node' is declared in 'InvalidObjectExports'")));

        var validClass = findClassDef(analyzedModule.analysisData(), "ValidObjectExports");
        assertEquals("", findProperty(validClass, "texture").getAnnotations().get("export"));
        assertEquals("", findProperty(validClass, "target").getAnnotations().get("export"));
    }

    /// When the initializer subtree already has an upstream blocking diagnostic, the
    /// determinability diagnostic is suppressed so the failure keeps a single owner.
    @Test
    void analyzeSuppressesDeterminabilityWhenInitializerResolutionFailed() throws Exception {
        var analyzedModule = analyze("""
                class_name UpstreamBlockedExport
                extends Node
                
                @export var value = missing_symbol
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertTrue(annotationUsageDiagnostics.isEmpty(), annotationUsageDiagnostics::toString);
        // The upstream pipeline still reports the unresolved symbol through its own category.
        assertFalse(analyzedModule.analysisData().diagnostics().asList().isEmpty());
        assertTrue(analyzedModule.analysisData().diagnostics().asList().stream().noneMatch(diagnostic ->
                diagnostic.message().contains("determinable")
        ));
    }

    /// Same-name variants repeat: last-wins Map semantics, no diagnostic (documented Godot
    /// difference).
    @Test
    void analyzeKeepsLastWinsForDuplicateSameNameVariants() throws Exception {
        var analyzedModule = analyze("""
                class_name DuplicateVariantExports
                extends Node
                
                @export_range(0, 1)
                @export_range(5, 10)
                var speed: float
                """);

        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        ).isEmpty(), () -> analyzedModule.analysisData().diagnostics().asList().toString());
        var classDef = findClassDef(analyzedModule.analysisData(), "DuplicateVariantExports");
        assertEquals("5,10", findProperty(classDef, "speed").getAnnotations().get("export_range"));
    }

    /// String literal argument forms (`'...'`, raw, triple-quoted, escapes) decode through the
    /// shared lexeme decoder before entering hint_string metadata.
    @Test
    void analyzeDecodesAllStringLiteralFormsInArguments() throws Exception {
        var analyzedModule = analyze("""
                class_name StringFormExports
                extends Node
                
                @export_enum('A', 'B') var single_quoted: String
                @export_file(r"res://a.txt") var raw_path: String
                @export_multiline var triple: String
                @export_placeholder(\"""triple "quoted" placeholder\""") var prompt: String
                @export_placeholder("tab\\tescape") var escaped: String
                @export_placeholder("\\U01F600") var emoji: String
                """);

        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        ).isEmpty(), () -> analyzedModule.analysisData().diagnostics().asList().toString());
        var classDef = findClassDef(analyzedModule.analysisData(), "StringFormExports");
        assertEquals("A,B", findProperty(classDef, "single_quoted").getAnnotations().get("export_enum"));
        assertEquals("res://a.txt", findProperty(classDef, "raw_path").getAnnotations().get("export_file"));
        assertEquals("triple \"quoted\" placeholder", findProperty(classDef, "prompt").getAnnotations().get("export_placeholder"));
        assertEquals("tab\tescape", findProperty(classDef, "escaped").getAnnotations().get("export_placeholder"));
        assertEquals("\uD83D\uDE00", findProperty(classDef, "emoji").getAnnotations().get("export_placeholder"));
    }

    /// Invalid escape sequences inside a string argument surface as `sema.annotation_usage`
    /// (never as an escaping exception).
    @Test
    void analyzeRejectsInvalidEscapeInStringArgument() throws Exception {
        var analyzedModule = analyze("""
                class_name BadEscapeExports
                extends Node
                
                @export_file("bad\\qescape") var path: String
                """);

        var errors = analyzedModule.analysisData().diagnostics().asList();
        assertEquals(1, errors.size());
        assertEquals("parse.lexical.error", errors.getFirst().category());
        assertEquals("Invalid escape in string.", errors.getFirst().message());
        assertTrue(findClassDef(analyzedModule.analysisData(), "BadEscapeExports")
                .getProperties().getFirst().getAnnotations().isEmpty());
    }

    @Test
    void discardedAccessorAnnotationUsesThePropertyRecoveryWithoutASecondError() throws Exception {
        var analyzed = analyze("""
                class_name AccessorAnnotationRecovery
                var prop: int:
                    get:
                        @onready
                        return 1
                var kept: int
                """);
        var unit = analyzed.units().getFirst();
        var variable = unit.ast().statements().stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .findFirst().orElseThrow();
        var annotation = variable.recoveryAnnotations().getFirst();
        var recovery = unit.parseRecoveries().getFirst();
        assertEquals("variable.property", recovery.facet());
        var errors = analyzed.analysisData().diagnostics().asList();
        assertEquals(1, errors.size());
        assertEquals(DiagnosticCodes.ANNOTATION_INVALID_LEVEL, errors.getFirst().category());
        assertTrue(analyzed.analysisData().skippedSubtreeRoots().containsKey(annotation));
        assertEquals(List.of("kept"), findClassDef(analyzed.analysisData(), "AccessorAnnotationRecovery")
                .getProperties().stream().map(PropertyDef::getName).toList());
        var fresh = analyzeUnits(List.of(unit), new DiagnosticManager()).analysisData().diagnostics().asList();
        assertEquals(1, fresh.size());
        assertEquals("sema.class_skeleton", fresh.getFirst().category());
    }

    @Test
    void projectedAccessorAnnotationsClaimOnlyTheirOwnPropertyAnnotationErrors() throws Exception {
        var count = 256;
        var input = prepareAnnotationUsageInput("many_projected_annotations.gd",
                "var prop: int:\n    get:\n" + "        @onready\n".repeat(count)
                        + "        return 1\nvar kept: int\n");
        var unit = input.unit();
        var property = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
        var kept = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getLast());
        var annotations = property.recoveryAnnotations();
        assertEquals(count, annotations.size());
        assertEquals(count, unit.parseRecoveries().size());
        var firstRange = unit.parseRecoveries().getFirst().range();
        var secondRange = unit.parseRecoveries().get(1).range();
        var spanningRange = new FrontendRange(firstRange.startByte(), secondRange.endByte(),
                firstRange.start(), secondRange.end());
        var sameStart = new FrontendParseRecovery(DiagnosticCodes.ANNOTATION_INVALID_TARGET,
                FrontendDiagnosticSeverity.ERROR, "another error for the second annotation", secondRange,
                property, "variable.property");
        var crossing = new FrontendParseRecovery(DiagnosticCodes.ANNOTATION_INVALID_LEVEL,
                FrontendDiagnosticSeverity.ERROR, "extends beyond the annotation", spanningRange,
                property, "variable.property");
        var wrongFamily = new FrontendParseRecovery(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                FrontendDiagnosticSeverity.ERROR, "non-annotation property error", firstRange,
                property, "variable.property");
        var wrongFacet = new FrontendParseRecovery(DiagnosticCodes.ANNOTATION_INVALID_LEVEL,
                FrontendDiagnosticSeverity.ERROR, "other property facet", firstRange,
                property, "variable.initializer");
        var wrongOwner = new FrontendParseRecovery(DiagnosticCodes.ANNOTATION_INVALID_LEVEL,
                FrontendDiagnosticSeverity.ERROR, "other property", firstRange, kept, "variable.property");
        var bucket = new ArrayList<>(unit.parseRecoveries());
        bucket.addAll(List.of(sameStart, crossing, wrongFamily, wrongFacet));
        var data = input.analysisData();
        data.parseRecoveriesByOwner().put(property, List.copyOf(bucket));
        data.parseRecoveriesByOwner().put(kept, List.of(wrongOwner));

        var live = new DiagnosticManager();
        var parserError = input.diagnosticManager().snapshot().asList().getFirst();
        live.report(parserError);
        new FrontendAnnotationUsageAnalyzer().analyze(input.classRegistry(), data, live);

        var errors = live.snapshot().asList();
        assertEquals(count, errors.size());
        assertEquals(parserError, errors.getFirst());
        for (var index = 0; index < count; index++) {
            var recovery = unit.parseRecoveries().get(index);
            assertSame(property, recovery.owner());
            assertEquals("variable.property", recovery.facet());
            assertEquals(DiagnosticCodes.ANNOTATION_INVALID_LEVEL, recovery.code());
            var range = FrontendRange.fromAstRange(annotations.get(index).range());
            assertEquals(range, recovery.range());
            var expected = index == 0 ? parserError : FrontendDiagnostic.error(
                    "sema.annotation_usage", "Invalid @onready syntax",
                    FrontendDiagnostic.sourcePathText(unit.path()), range);
            assertEquals(expected, errors.get(index));
            assertEquals(expected, data.claimedParseRecoveries().get(recovery));
        }
        assertEquals(errors.get(1), data.claimedParseRecoveries().get(sameStart));
        for (var independent : List.of(crossing, wrongFamily, wrongFacet, wrongOwner)) {
            assertNull(data.claimedParseRecoveries().get(independent));
        }
        assertTrue(data.scopesByAst().containsKey(kept));
    }

    /// Explicit `: Variant` is a determinate export type (Godot exports it as NIL_IS_VARIANT);
    /// variants skip their type check on Variant, and bare export stays unrestricted on
    /// container-typed properties.
    @Test
    void analyzeAllowsExplicitVariantAndContainerBareExport() throws Exception {
        var analyzedModule = analyze("""
                class_name ExplicitVariantExports
                extends Node
                
                @export var payload: Variant
                @export_range(0, 1) var ranged: Variant
                @export_enum("A", "B") var choice: Variant
                @export var scores: Array[int]
                """);

        assertTrue(diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        ).isEmpty(), () -> analyzedModule.analysisData().diagnostics().asList().toString());
        var classDef = findClassDef(analyzedModule.analysisData(), "ExplicitVariantExports");
        assertEquals("", findProperty(classDef, "payload").getAnnotations().get("export"));
        assertEquals("0,1", findProperty(classDef, "ranged").getAnnotations().get("export_range"));
        assertEquals("A,B", findProperty(classDef, "choice").getAnnotations().get("export_enum"));
        assertEquals("", findProperty(classDef, "scores").getAnnotations().get("export"));
    }

    /// The custom multiline check does not skip Variant (unlike the default check).
    @Test
    void analyzeRejectsMultilineOnExplicitVariant() throws Exception {
        var analyzedModule = analyze("""
                class_name MultilineVariantExport
                extends Node
                
                @export_multiline var text: Variant
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(1, annotationUsageDiagnostics.size());
        assertTrue(annotationUsageDiagnostics.getFirst().message()
                .contains("@export_multiline can only be used on properties of type String, but 'text' is Variant"));
    }

    /// Packed-array declared types hit the same container narrowing as `Array[T]`.
    @Test
    void analyzeRejectsVariantOnPackedArrayContainer() throws Exception {
        var analyzedModule = analyze("""
                class_name PackedContainerExport
                extends Node
                
                @export_enum("A") var names: PackedStringArray
                """);

        var annotationUsageDiagnostics = diagnosticsByCategory(
                analyzedModule.analysisData().diagnostics().asList(),
                "sema.annotation_usage"
        );
        assertEquals(1, annotationUsageDiagnostics.size());
        assertTrue(annotationUsageDiagnostics.getFirst().message().contains("'names' is PackedStringArray"));
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
        return analyzeUnits(units, diagnosticManager);
    }

    private static @NotNull AnalyzedModule analyzeUnits(
            @NotNull List<FrontendSourceUnit> units,
            @NotNull DiagnosticManager diagnosticManager
    ) throws Exception {
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", units),
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
        return analysisData.moduleSkeleton().sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::topLevelClassDef)
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
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        }
    }

    private record PreparedAnnotationUsageInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull ClassRegistry classRegistry
    ) {
        private PreparedAnnotationUsageInput {
            Objects.requireNonNull(unit, "unit must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            Objects.requireNonNull(classRegistry, "classRegistry must not be null");
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
                new FrontendModule("test_module", List.of(unit)),
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
