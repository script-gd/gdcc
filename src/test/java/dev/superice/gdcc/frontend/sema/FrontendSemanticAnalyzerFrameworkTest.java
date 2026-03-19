package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendChainBindingAnalyzer;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendTopBindingAnalyzer;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionHeader;
import dev.superice.gdcc.gdextension.ExtensionSingleton;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.scope.resolver.ScopeTypeResolver;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendSemanticAnalyzerFrameworkTest {
    private static final Range SYNTHETIC_RANGE = new Range(
            0,
            1,
            new Point(0, 0),
            new Point(0, 1)
    );

    @Test
    void analyzeBootstrapsSideTablesAndCollectsSemanticallyRelevantAnnotations() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "annotated_player.gd"), """
                @tool
                class_name AnnotatedPlayer
                extends Node
                
                @export var hp: int = 1
                
                @rpc("authority")
                func ping(value):
                    var local := value
                
                @warning_ignore_start("unused_variable")
                var tmp := 1
                
                @warning_ignore_restore("unused_variable")
                var keep := 2
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);
        var topLevelStatements = unit.ast().statements();
        var hpProperty = findVariable(topLevelStatements, "hp");
        var tmpProperty = findVariable(topLevelStatements, "tmp");
        var keepProperty = findVariable(topLevelStatements, "keep");
        var pingFunction = findFunction(topLevelStatements, "ping");

        assertEquals(1, result.moduleSkeleton().classDefs().size());
        assertEquals("AnnotatedPlayer", result.moduleSkeleton().classDefs().getFirst().getName());
        assertEquals(List.of("tool"), annotationNames(result.annotationsByAst().get(unit.ast())));
        assertEquals(List.of("export"), annotationNames(result.annotationsByAst().get(hpProperty)));
        assertEquals(List.of("rpc"), annotationNames(result.annotationsByAst().get(pingFunction)));
        assertNull(result.annotationsByAst().get(tmpProperty));
        assertNull(result.annotationsByAst().get(keepProperty));

        assertFalse(result.scopesByAst().isEmpty());
        assertTrue(result.scopesByAst().containsKey(unit.ast()));
        assertTrue(result.scopesByAst().containsKey(pingFunction));
        assertTrue(result.scopesByAst().containsKey(pingFunction.body()));
        assertTrue(result.scopesByAst().containsKey(pingFunction.parameters().getFirst()));
        var pingScope = assertInstanceOf(CallableScope.class, result.scopesByAst().get(pingFunction));
        var pingBodyScope = assertInstanceOf(BlockScope.class, result.scopesByAst().get(pingFunction.body()));
        var parameterBinding = pingScope.resolveValue("value");
        assertNotNull(parameterBinding);
        assertEquals(GdVariantType.VARIANT, parameterBinding.type());
        assertEquals(ScopeValueKind.PARAMETER, parameterBinding.kind());
        assertSame(pingFunction.parameters().getFirst(), parameterBinding.declaration());
        var localBinding = pingBodyScope.resolveValue("local");
        assertNotNull(localBinding);
        assertEquals(GdVariantType.VARIANT, localBinding.type());
        assertEquals(ScopeValueKind.LOCAL, localBinding.kind());
        var localInitializerUseSite = assertInstanceOf(
                IdentifierExpression.class,
                findVariable(pingFunction.body().statements(), "local").value()
        );
        var localInitializerBinding = result.symbolBindings().get(localInitializerUseSite);
        assertNotNull(localInitializerBinding);
        assertEquals(FrontendBindingKind.PARAMETER, localInitializerBinding.kind());
        assertSame(pingFunction.parameters().getFirst(), localInitializerBinding.declarationSite());
        assertFalse(result.symbolBindings().isEmpty());
        var localInitializerType = result.expressionTypes().get(localInitializerUseSite);
        assertNotNull(localInitializerType);
        assertEquals("Variant", localInitializerType.publishedType().getTypeName());
        assertTrue(result.resolvedMembers().isEmpty());
        assertTrue(result.resolvedCalls().isEmpty());
        assertNotNull(result.diagnostics());
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals(result.moduleSkeleton().diagnostics(), result.diagnostics());
    }

    @Test
    void analyzeCollectsNestedBlockAnnotationsAndStillIgnoresRegionAnnotations() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "nested_annotations.gd"), """
                class_name NestedAnnotations
                extends Node
                
                func ping(value):
                    @warning_ignore("unused_variable")
                    var inner := 1
                    @warning_ignore_start("unused_variable")
                    var region_ignored := 2
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);
        var pingFunction = findFunction(unit.ast().statements(), "ping");
        var bodyStatements = pingFunction.body().statements();
        var innerVariable = findVariable(bodyStatements, "inner");
        var regionIgnoredVariable = findVariable(bodyStatements, "region_ignored");

        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals(List.of("warning_ignore"), annotationNames(result.annotationsByAst().get(innerVariable)));
        assertNull(result.annotationsByAst().get(regionIgnoredVariable));
    }

    @Test
    void analyzePublishesTopBindingsForChainHeadsWhileKeepingDeferredWarningsExplicit() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "framework_top_binding.gd"), """
                class_name FrameworkTopBinding
                extends Node
                
                func helper():
                    pass
                
                func get_player():
                    pass
                
                func ping(player, i, seed = helper()):
                    player.hp
                    player.move(i + 1)
                    var f = func():
                        return player
                    return get_player().hp
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var result = new FrontendSemanticAnalyzer().analyze("test_module", List.of(unit), registry, diagnostics);

        var pingFunction = findFunction(unit.ast().statements(), "ping");
        var headRead = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var stepCall = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var lambdaHolder = findVariable(pingFunction.body().statements(), "f");
        var outerReturn = assertInstanceOf(ReturnStatement.class, pingFunction.body().statements().getLast());

        assertEquals(
                FrontendBindingKind.PARAMETER,
                Objects.requireNonNull(
                        result.symbolBindings().get(findNode(headRead, IdentifierExpression.class, id -> id.name().equals("player")))
                ).kind()
        );
        assertEquals(
                FrontendBindingKind.PARAMETER,
                Objects.requireNonNull(
                        result.symbolBindings().get(findNode(stepCall, IdentifierExpression.class, id -> id.name().equals("player")))
                ).kind()
        );
        assertEquals(
                FrontendBindingKind.PARAMETER,
                Objects.requireNonNull(
                        result.symbolBindings().get(findNode(stepCall, IdentifierExpression.class, id -> id.name().equals("i")))
                ).kind()
        );
        assertEquals(
                FrontendBindingKind.LITERAL,
                Objects.requireNonNull(result.symbolBindings().get(findLiteral(stepCall, "1"))).kind()
        );
        assertEquals(
                FrontendBindingKind.METHOD,
                Objects.requireNonNull(
                        result.symbolBindings().get(findNode(outerReturn, IdentifierExpression.class, id -> id.name().equals("get_player")))
                ).kind()
        );

        var helperUseSite = findNode(
                pingFunction.parameters().getLast().defaultValue(),
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("helper")
        );
        assertNull(result.symbolBindings().get(helperUseSite));

        var lambdaPlayerUseSite = findNode(
                lambdaHolder.value(),
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("player")
        );
        assertNull(result.symbolBindings().get(lambdaPlayerUseSite));

        assertEquals(5, result.symbolBindings().size());
        assertEquals(2, result.resolvedMembers().size());
        assertEquals(1, result.resolvedCalls().size());
        assertEquals(
                2,
                result.diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.unsupported_binding_subtree"))
                        .count()
        );
        assertEquals(
                1,
                result.diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.deferred_chain_resolution"))
                        .count()
        );
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_binding_subtree")
                        && diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.message().contains("parameter default")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_binding_subtree")
                        && diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.message().contains("lambda subtree")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.deferred_chain_resolution")
                        && diagnostic.message().contains("Argument #1 type is still deferred")
        ));
    }

    @Test
    void analyzeDoesNotInventParseDiagnosticsForManualUnitsOutsideSharedManagerPipeline() throws Exception {
        var parserService = new GdScriptParserService();
        var parsed = parserService.parseUnit(Path.of("tmp", "manual_unit.gd"), """
                class_name ManualUnit
                extends Node
                
                func ping():
                    pass
                """, new DiagnosticManager());
        var unit = new FrontendSourceUnit(
                parsed.path(),
                parsed.source(),
                parsed.ast()
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();
        var diagnostics = new DiagnosticManager();

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);

        assertEquals(1, result.moduleSkeleton().classDefs().size());
        assertTrue(diagnostics.isEmpty());
        assertTrue(result.moduleSkeleton().diagnostics().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    /// Anchors the phase-boundary snapshot rule: the analysis data captures the shared
    /// manager state once, and later manager mutations must not retroactively rewrite either
    /// `FrontendAnalysisData` or its nested `FrontendModuleSkeleton`.
    @Test
    void analyzePublishesStableDiagnosticsSnapshotEvenIfManagerChangesLater() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "stable_snapshot.gd"), """
                class_name StableSnapshot
                extends Node
                
                func _ready(
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);
        var parseSnapshot = diagnostics.snapshot();
        var beforeMutation = result.diagnostics();
        diagnostics.error("sema.synthetic", "late diagnostic", unit.path(), null);

        assertFalse(beforeMutation.isEmpty());
        assertEquals(parseSnapshot.asList(), beforeMutation.asList());
        assertEquals(beforeMutation, result.diagnostics());
        assertEquals(beforeMutation, result.moduleSkeleton().diagnostics());
        assertEquals(beforeMutation.size() + 1, diagnostics.snapshot().size());
    }

    @Test
    void analyzePublishesPhaseBoundariesBeforeTopBindingPhaseAndRefreshesDiagnosticsAfterEachPhase() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "variable_phase_probe.gd"), """
                class_name VariablePhaseProbe
                extends Node
                
                func ping(value):
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var probeScopeAnalyzer = new RecordingScopeAnalyzer();
        var probeVariableAnalyzer = new RecordingVariableAnalyzer();
        var probeTopBindingAnalyzer = new RecordingTopBindingAnalyzer();
        var probeChainBindingAnalyzer = new RecordingChainBindingAnalyzer();
        var analyzer = new FrontendSemanticAnalyzer(
                new FrontendClassSkeletonBuilder(),
                probeScopeAnalyzer,
                probeVariableAnalyzer,
                probeTopBindingAnalyzer,
                probeChainBindingAnalyzer
        );

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);

        assertTrue(probeScopeAnalyzer.invoked);
        assertTrue(probeScopeAnalyzer.moduleSkeletonPublished);
        assertTrue(probeScopeAnalyzer.preScopeDiagnosticsMatchedManager);
        assertTrue(probeVariableAnalyzer.invoked);
        assertTrue(probeVariableAnalyzer.scopeBoundaryPublished);
        assertTrue(probeVariableAnalyzer.preVariableDiagnosticsMatchedManager);
        assertTrue(probeTopBindingAnalyzer.invoked);
        assertTrue(probeTopBindingAnalyzer.scopeBoundaryPublished);
        assertTrue(probeTopBindingAnalyzer.preTopBindingDiagnosticsMatchedManager);
        assertTrue(probeTopBindingAnalyzer.stableSymbolBindingsReferencePreserved);
        assertTrue(probeTopBindingAnalyzer.symbolBindingsPublicationClearedProbeEntry);
        assertTrue(probeChainBindingAnalyzer.invoked);
        assertTrue(probeChainBindingAnalyzer.topBindingBoundaryPublished);
        assertTrue(probeChainBindingAnalyzer.preChainBindingDiagnosticsMatchedManager);
        assertTrue(probeChainBindingAnalyzer.stableResolvedMembersReferencePreserved);
        assertTrue(probeChainBindingAnalyzer.stableResolvedCallsReferencePreserved);
        assertTrue(probeChainBindingAnalyzer.memberPublicationClearedProbeEntry);
        assertTrue(probeChainBindingAnalyzer.callPublicationClearedProbeEntry);
        assertEquals(probeScopeAnalyzer.preScopeDiagnostics.size() + 1, probeVariableAnalyzer.preVariableDiagnostics.size());
        assertTrue(probeVariableAnalyzer.preVariableDiagnostics.asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.scope_phase_probe")
        ));
        assertEquals(
                probeVariableAnalyzer.preVariableDiagnostics.size() + 1,
                probeTopBindingAnalyzer.preTopBindingDiagnostics.size()
        );
        assertTrue(probeTopBindingAnalyzer.preTopBindingDiagnostics.asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.variable_phase_probe")
        ));
        assertEquals(
                probeTopBindingAnalyzer.preTopBindingDiagnostics.size() + 1,
                probeChainBindingAnalyzer.preChainBindingDiagnostics.size()
        );
        assertTrue(probeChainBindingAnalyzer.preChainBindingDiagnostics.asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.top_binding_phase_probe")
        ));
        assertEquals(probeChainBindingAnalyzer.preChainBindingDiagnostics.size() + 1, result.diagnostics().size());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.scope_phase_probe")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.variable_phase_probe")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.top_binding_phase_probe")
        ));
        assertEquals("sema.chain_binding_phase_probe", result.diagnostics().getLast().category());
        assertEquals(probeScopeAnalyzer.preScopeDiagnostics, result.moduleSkeleton().diagnostics());
        assertEquals(result.diagnostics(), diagnostics.snapshot());
        assertTrue(result.symbolBindings().isEmpty());
        assertTrue(result.resolvedMembers().isEmpty());
        assertTrue(result.resolvedCalls().isEmpty());
    }

    @Test
    void analyzeKeepsPipelineAliveWhenSkeletonReportsRecoverableDiagnostics() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var duplicateA = parserService.parseUnit(Path.of("tmp", "duplicate_a.gd"), """
                class_name SharedName
                extends Node
                
                func from_a():
                    pass
                """, diagnostics);
        var duplicateB = parserService.parseUnit(Path.of("tmp", "duplicate_b.gd"), """
                class_name SharedName
                extends Node
                
                func from_b():
                    pass
                """, diagnostics);
        var stable = parserService.parseUnit(Path.of("tmp", "stable.gd"), """
                class_name StableAfterError
                extends Node
                
                func ok():
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzer.analyze("test_module", List.of(duplicateA, duplicateB, stable), registry, diagnostics);

        assertEquals(List.of("SharedName", "StableAfterError"), result.moduleSkeleton().classDefs().stream().map(LirClassDef::getName).toList());
        assertFalse(result.scopesByAst().isEmpty());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("Duplicate class name 'SharedName'")
        ));
        assertNotNull(registry.findGdccClass("SharedName"));
        assertNotNull(registry.findGdccClass("StableAfterError"));
    }

    @Test
    void semanticAnalysisKeepsSharedTypeResolverAlignedWithSkeletonDeclaredTypes() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "scope_type_resolver_parity.gd"), """
                class_name ScopeTypeResolverParity
                extends RefCounted
                
                var inner_ref: Inner
                
                class Inner:
                    var helpers: Array[Helper]
                
                class Helper:
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = new FrontendSemanticAnalyzer().analyze("test_module", List.of(unit), registry, diagnostics);

        var topLevel = findClassByName(result.moduleSkeleton().classDefs(), "ScopeTypeResolverParity");
        var inner = findClassByName(result.moduleSkeleton().allClassDefs(), "ScopeTypeResolverParity$Inner");
        var sourceScope = assertInstanceOf(ClassScope.class, result.scopesByAst().get(unit.ast()));

        assertEquals(
                findPropertyByName(topLevel, "inner_ref").getType(),
                ScopeTypeResolver.tryResolveDeclaredType(sourceScope, "Inner")
        );

        var innerDeclaration = findClass(unit.ast().statements(), "Inner");
        var innerScope = assertInstanceOf(ClassScope.class, result.scopesByAst().get(innerDeclaration));
        assertEquals(
                findPropertyByName(inner, "helpers").getType(),
                ScopeTypeResolver.tryResolveDeclaredType(innerScope, "Array[Helper]")
        );
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void semanticAnalysisCurrentlyPrefersOuterTypeMetaOverBaseTypeMetaWhenNamesCollide() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var baseUnit = parserService.parseUnit(Path.of("tmp", "base_leaf_precedence.gd"), """
                class_name BaseLeaf
                extends RefCounted
                
                class Shared:
                    pass
                """, diagnostics);
        var outerUnit = parserService.parseUnit(Path.of("tmp", "outer_precedence.gd"), """
                class_name OuterPrecedence
                extends RefCounted
                
                class Shared:
                    pass
                
                class Leaf extends BaseLeaf:
                    var picked: Shared
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = new FrontendSemanticAnalyzer().analyze(
                "test_module",
                List.of(baseUnit, outerUnit),
                registry,
                diagnostics
        );

        var leaf = findClassByName(result.moduleSkeleton().allClassDefs(), "OuterPrecedence$Leaf");
        var pickedType = assertInstanceOf(GdObjectType.class, findPropertyByName(leaf, "picked").getType());
        assertEquals("OuterPrecedence$Shared", pickedType.getTypeName());

        var leafDeclaration = findClass(outerUnit.ast().statements(), "Leaf");
        var leafScope = assertInstanceOf(ClassScope.class, result.scopesByAst().get(leafDeclaration));
        var resolvedShared = assertInstanceOf(
                GdObjectType.class,
                ScopeTypeResolver.tryResolveDeclaredType(leafScope, "Shared")
        );
        assertEquals("OuterPrecedence$Shared", resolvedShared.getTypeName());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void semanticAnalysisCanonicalizesHeaderExtendsWhileKeepingFrontendSuperclassFacts() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "header_super_shared_resolver_gap.gd"), """
                class_name HeaderResolverGap
                extends RefCounted
                
                class Shared:
                    pass
                
                class Leaf extends Shared:
                    var picked: Shared
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = new FrontendSemanticAnalyzer().analyze("test_module", List.of(unit), registry, diagnostics);

        var leaf = findClassByName(result.moduleSkeleton().allClassDefs(), "HeaderResolverGap$Leaf");
        var leafDeclaration = findClass(unit.ast().statements(), "Leaf");
        var sourceRelation = result.moduleSkeleton().sourceClassRelations().getFirst();
        var leafRelation = assertInstanceOf(
                FrontendInnerClassRelation.class,
                sourceRelation.findRelation(leafDeclaration)
        );
        assertEquals(
                new FrontendSuperClassRef("Shared", "HeaderResolverGap$Shared"),
                leafRelation.superClassRef()
        );
        assertEquals("HeaderResolverGap$Shared", leaf.getSuperName());
        assertNotNull(registry.findGdccClass(leaf.getSuperName()));

        var pickedType = assertInstanceOf(GdObjectType.class, findPropertyByName(leaf, "picked").getType());
        assertEquals("HeaderResolverGap$Shared", pickedType.getTypeName());

        var leafScope = assertInstanceOf(ClassScope.class, result.scopesByAst().get(leafDeclaration));
        var resolvedShared = assertInstanceOf(
                GdObjectType.class,
                ScopeTypeResolver.tryResolveDeclaredType(leafScope, "Shared")
        );
        assertEquals("HeaderResolverGap$Shared", resolvedShared.getTypeName());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void semanticAnalysisRejectsCanonicalSuperclassSpellingAtFrontendBoundary() throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new FrontendSourceUnit(
                Path.of("tmp", "canonical_super_boundary.gd"),
                "",
                new SourceFile(
                        List.of(
                                new ClassNameStatement("CanonicalBoundary", "RefCounted", SYNTHETIC_RANGE),
                                new ClassDeclaration(
                                        "Shared",
                                        null,
                                        new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE),
                                        SYNTHETIC_RANGE
                                ),
                                new ClassDeclaration(
                                        "Leaf",
                                        "CanonicalBoundary$Shared",
                                        new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE),
                                        SYNTHETIC_RANGE
                                )
                        ),
                        SYNTHETIC_RANGE
                )
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = new FrontendSemanticAnalyzer().analyze("test_module", List.of(unit), registry, diagnostics);

        var leafDeclaration = findClass(unit.ast().statements(), "Leaf");
        var sourceRelation = result.moduleSkeleton().sourceClassRelations().getFirst();
        assertEquals(
                List.of("CanonicalBoundary", "CanonicalBoundary$Shared"),
                result.moduleSkeleton().allClassDefs().stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of("Shared"),
                sourceRelation.innerClassRelations().stream().map(FrontendInnerClassRelation::sourceName).toList()
        );
        assertNull(sourceRelation.findRelation(leafDeclaration));
        assertNull(result.scopesByAst().get(leafDeclaration));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("CanonicalBoundary$Leaf")
                        && diagnostic.message().contains("canonical '$' spelling")
        ));
        assertNull(registry.findGdccClass("CanonicalBoundary$Leaf"));
        assertNotNull(registry.findGdccClass("CanonicalBoundary$Shared"));
    }

    @Test
    void semanticAnalysisRejectsSingletonSuperclassSourceAtFrontendBoundary() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "singleton_super.gd"), """
                class_name SingletonBoundary
                extends GameSingleton
                
                func ping():
                    pass
                """, diagnostics);
        var registry = createRegistryWithSingleton("GameSingleton");
        var result = new FrontendSemanticAnalyzer().analyze("test_module", List.of(unit), registry, diagnostics);

        assertTrue(result.moduleSkeleton().classDefs().isEmpty());
        assertTrue(result.moduleSkeleton().sourceClassRelations().isEmpty());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("SingletonBoundary")
                        && diagnostic.message().contains("autoload/singleton superclasses")
        ));
        assertNull(registry.findGdccClass("SingletonBoundary"));
        assertNull(result.scopesByAst().get(unit.ast()));
    }

    private VariableDeclaration findVariable(List<?> statements, String name) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(variableDeclaration -> variableDeclaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + name));
    }

    private FunctionDeclaration findFunction(List<?> statements, String name) {
        return statements.stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .filter(functionDeclaration -> functionDeclaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + name));
    }

    private ClassDeclaration findClass(List<?> statements, String name) {
        return statements.stream()
                .filter(ClassDeclaration.class::isInstance)
                .map(ClassDeclaration.class::cast)
                .filter(classDeclaration -> classDeclaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + name));
    }

    private ClassDef findClassByName(List<? extends ClassDef> classDefs, String name) {
        return classDefs.stream()
                .filter(classDef -> classDef.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ClassDef not found: " + name));
    }

    private PropertyDef findPropertyByName(ClassDef classDef, String name) {
        return classDef.getProperties().stream()
                .filter(propertyDef -> propertyDef.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + name));
    }

    private LiteralExpression findLiteral(Node root, String sourceText) {
        return findNode(
                root,
                LiteralExpression.class,
                literalExpression -> literalExpression.sourceText().equals(sourceText)
        );
    }

    private <T extends Node> T findNode(
            Node root,
            Class<T> nodeType,
            Predicate<T> predicate
    ) {
        return findNodes(root, nodeType, predicate).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private <T extends Node> List<T> findNodes(
            Node root,
            Class<T> nodeType,
            Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return List.copyOf(matches);
    }

    private <T extends Node> void collectMatchingNodes(
            Node node,
            Class<T> nodeType,
            Predicate<T> predicate,
            List<T> matches
    ) {
        if (nodeType.isInstance(node)) {
            var candidate = nodeType.cast(node);
            if (predicate.test(candidate)) {
                matches.add(candidate);
            }
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private List<String> annotationNames(List<FrontendGdAnnotation> annotations) {
        assertNotNull(annotations);
        return annotations.stream().map(FrontendGdAnnotation::name).toList();
    }

    private ClassRegistry createRegistryWithSingleton(String singletonName) {
        return new ClassRegistry(new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ExtensionGdClass("Object", false, true, "", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("Node", false, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("RefCounted", true, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of())
                ),
                List.of(new ExtensionSingleton(singletonName, "Node")),
                List.of()
        ));
    }

    /// Scope-phase probe used to prove that the framework refreshes diagnostics before the
    /// variable phase starts.
    private static final class RecordingScopeAnalyzer extends FrontendScopeAnalyzer {
        private boolean invoked;
        private boolean moduleSkeletonPublished;
        private boolean preScopeDiagnosticsMatchedManager;
        private DiagnosticSnapshot preScopeDiagnostics;

        @Override
        public void analyze(
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            moduleSkeletonPublished = analysisData.moduleSkeleton().sourceClassRelations().size() == 1
                    && analysisData.moduleSkeleton().classDefs().size() == 1;
            preScopeDiagnostics = analysisData.diagnostics();
            preScopeDiagnosticsMatchedManager = preScopeDiagnostics.equals(diagnosticManager.snapshot());
            diagnosticManager.warning(
                    "sema.scope_phase_probe",
                    "scope phase probe diagnostic",
                    null,
                    null
            );
            super.analyze(classRegistry, analysisData, diagnosticManager);
        }
    }

    /// Variable-phase probe used to lock the new `scope -> variable` hand-off contract.
    private static final class RecordingVariableAnalyzer extends FrontendVariableAnalyzer {
        private boolean invoked;
        private boolean scopeBoundaryPublished;
        private boolean preVariableDiagnosticsMatchedManager;
        private DiagnosticSnapshot preVariableDiagnostics;

        @Override
        public void analyze(
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            preVariableDiagnostics = analysisData.diagnostics();
            preVariableDiagnosticsMatchedManager = preVariableDiagnostics.equals(diagnosticManager.snapshot());
            scopeBoundaryPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(sourceClassRelation -> analysisData.scopesByAst().containsKey(sourceClassRelation.unit().ast()));
            super.analyze(analysisData, diagnosticManager);
            diagnosticManager.warning(
                    "sema.variable_phase_probe",
                    "variable phase probe diagnostic",
                    null,
                    null
            );
        }
    }

    /// Top-binding probe used to prove that the framework publishes the new phase boundary by
    /// rebuilding `symbolBindings()` instead of leaving bootstrap leftovers in place.
    private static final class RecordingTopBindingAnalyzer extends FrontendTopBindingAnalyzer {
        private boolean invoked;
        private boolean scopeBoundaryPublished;
        private boolean preTopBindingDiagnosticsMatchedManager;
        private boolean stableSymbolBindingsReferencePreserved;
        private boolean symbolBindingsPublicationClearedProbeEntry;
        private DiagnosticSnapshot preTopBindingDiagnostics;

        @Override
        public void analyze(
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            preTopBindingDiagnostics = analysisData.diagnostics();
            preTopBindingDiagnosticsMatchedManager = preTopBindingDiagnostics.equals(diagnosticManager.snapshot());
            scopeBoundaryPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(sourceClassRelation -> analysisData.scopesByAst().containsKey(sourceClassRelation.unit().ast()));
            var publishedSymbolBindings = analysisData.symbolBindings();
            var probeNode = new PassStatement(SYNTHETIC_RANGE);
            publishedSymbolBindings.put(probeNode, new FrontendBinding("__probe__", FrontendBindingKind.UNKNOWN, null));

            super.analyze(analysisData, diagnosticManager);

            stableSymbolBindingsReferencePreserved = publishedSymbolBindings == analysisData.symbolBindings();
            symbolBindingsPublicationClearedProbeEntry = analysisData.symbolBindings().isEmpty()
                    && !analysisData.symbolBindings().containsKey(probeNode);
            diagnosticManager.warning(
                    "sema.top_binding_phase_probe",
                    "top-binding phase probe diagnostic",
                    null,
                    null
            );
        }
    }

    private static final class RecordingChainBindingAnalyzer extends FrontendChainBindingAnalyzer {
        private boolean invoked;
        private boolean topBindingBoundaryPublished;
        private boolean preChainBindingDiagnosticsMatchedManager;
        private boolean stableResolvedMembersReferencePreserved;
        private boolean stableResolvedCallsReferencePreserved;
        private boolean memberPublicationClearedProbeEntry;
        private boolean callPublicationClearedProbeEntry;
        private DiagnosticSnapshot preChainBindingDiagnostics;

        @Override
        public void analyze(
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            preChainBindingDiagnostics = analysisData.diagnostics();
            preChainBindingDiagnosticsMatchedManager = preChainBindingDiagnostics.equals(diagnosticManager.snapshot());
            topBindingBoundaryPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(sourceClassRelation -> analysisData.scopesByAst().containsKey(sourceClassRelation.unit().ast()))
                    && analysisData.symbolBindings().isEmpty();
            var publishedMembers = analysisData.resolvedMembers();
            var publishedCalls = analysisData.resolvedCalls();
            var probeMemberNode = new PassStatement(SYNTHETIC_RANGE);
            var probeCallNode = new ExpressionStatement(new LiteralExpression("integer", "0", SYNTHETIC_RANGE), SYNTHETIC_RANGE);
            publishedMembers.put(
                    probeMemberNode,
                    FrontendResolvedMember.failed(
                            "__probe_member__",
                            FrontendBindingKind.PROPERTY,
                            FrontendReceiverKind.INSTANCE,
                            null,
                            GdVariantType.VARIANT,
                            null,
                            "probe"
                    )
            );
            publishedCalls.put(
                    probeCallNode,
                    FrontendResolvedCall.failed(
                            "__probe_call__",
                            FrontendCallResolutionKind.INSTANCE_METHOD,
                            FrontendReceiverKind.INSTANCE,
                            null,
                            GdVariantType.VARIANT,
                            List.of(),
                            null,
                            "probe"
                    )
            );

            super.analyze(classRegistry, analysisData, diagnosticManager);

            stableResolvedMembersReferencePreserved = publishedMembers == analysisData.resolvedMembers();
            stableResolvedCallsReferencePreserved = publishedCalls == analysisData.resolvedCalls();
            memberPublicationClearedProbeEntry = analysisData.resolvedMembers().isEmpty()
                    && !analysisData.resolvedMembers().containsKey(probeMemberNode);
            callPublicationClearedProbeEntry = analysisData.resolvedCalls().isEmpty()
                    && !analysisData.resolvedCalls().containsKey(probeCallNode);
            diagnosticManager.warning(
                    "sema.chain_binding_phase_probe",
                    "chain-binding phase probe diagnostic",
                    null,
                    null
            );
        }
    }
}
