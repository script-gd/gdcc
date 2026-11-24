package gd.script.gdcc.frontend.sema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendParseRecovery;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.SignalDef;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;

class FrontendClassSkeletonTest {
    @Test
    void buildAcceptsFrontendModuleAndKeepsStepOneBehaviorStable() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var builder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var mappedUnit = parserService.parseUnit(Path.of("tmp", "mapped_candidate.gd"), """
                class_name MappedCandidate
                extends RefCounted
                """, diagnostics);
        var plainUnit = parserService.parseUnit(Path.of("tmp", "plain_candidate.gd"), """
                class_name PlainCandidate
                extends RefCounted
                """, diagnostics);
        var module = new FrontendModule(
                "test_module",
                List.of(mappedUnit, plainUnit),
                java.util.Map.of("MappedCandidate", "MappedRuntimeCandidate")
        );

        var result = builder.build(module, registry, diagnostics, analysisData);

        assertEquals("test_module", result.moduleName());
        assertEquals(List.of(mappedUnit, plainUnit), sourceUnits(result));
        assertEquals(
                List.of("MappedRuntimeCandidate", "PlainCandidate"),
                topLevelClassDefs(result).stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of("MappedCandidate", "PlainCandidate"),
                result.sourceClassRelations().stream().map(FrontendSourceClassRelation::sourceName).toList()
        );
        assertEquals(
                List.of("MappedRuntimeCandidate", "PlainCandidate"),
                result.sourceClassRelations().stream().map(FrontendSourceClassRelation::canonicalName).toList()
        );
    }

    @Test
    void buildInjectsClassSkeletonsIntoRegistry() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();

        var baseSource = """
                class_name BaseClass
                extends RefCounted
                
                signal changed(value: int)
                var speed: float = 1.0
                
                func ping(x: int) -> int:
                    return x
                """;
        var childSource = """
                class_name ChildClass
                extends BaseClass
                
                var hp: int = 100
                
                func _ready():
                    pass
                """;
        var anonymousSource = """
                var flag := true
                
                func tick():
                    pass
                """;

        var units = List.of(
                parserService.parseUnit(Path.of("tmp", "base_class.gd"), baseSource, diagnostics),
                parserService.parseUnit(Path.of("tmp", "child_class.gd"), childSource, diagnostics),
                parserService.parseUnit(Path.of("tmp", "no_name_script.gd"), anonymousSource, diagnostics)
        );

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", units),
                registry,
                diagnostics,
                analysisData
        );
        assertEquals(3, result.sourceClassRelations().size());
        assertEquals(3, topLevelClassDefs(result).size());
        assertEquals(3, result.allClassDefs().size());
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertTrue(result.diagnostics().isEmpty());

        var childClass = findClassByName(topLevelClassDefs(result), "ChildClass");
        assertEquals("BaseClass", childClass.getSuperName());
        assertEquals(1, childClass.getProperties().size());
        assertEquals("hp", childClass.getProperties().getFirst().getName());
        assertEquals(1, childClass.getFunctions().size());
        assertEquals("_ready", childClass.getFunctions().getFirst().getName());

        var baseClass = findClassByName(topLevelClassDefs(result), "BaseClass");
        assertEquals("RefCounted", baseClass.getSuperName());
        assertEquals(1, baseClass.getSignals().size());
        var changedSignal = baseClass.getSignals().getFirst();
        assertEquals("changed", changedSignal.getName());
        assertEquals(1, changedSignal.getParameterCount());
        assertEquals("value", changedSignal.getParameter(0).getName());
        assertEquals(GdIntType.INT, changedSignal.getParameter(0).getType());

        var derivedNameClass = findClassByName(topLevelClassDefs(result), "NoNameScript");
        assertEquals("RefCounted", derivedNameClass.getSuperName());
        assertEquals(1, derivedNameClass.getProperties().size());
        assertEquals("flag", derivedNameClass.getProperties().getFirst().getName());

        assertNotNull(registry.findGdccClass("BaseClass"));
        assertNotNull(registry.findGdccClass("ChildClass"));
        assertNotNull(registry.findGdccClass("NoNameScript"));
    }

    @Test
    void buildRecordsSourceUnitToTopLevelAndInnerClassRelationsExplicitly() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "outer_with_inner.gd"), """
                class_name OuterWithInner
                extends Node
                
                class InnerA:
                    var hp: int = 1
                    func ping():
                        pass
                
                    class Deep:
                        func nested():
                            pass
                
                class InnerB:
                    signal changed(value: int)
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var relation = result.sourceClassRelations().getFirst();

        assertEquals(1, result.sourceClassRelations().size());
        assertSame(unit, relation.unit());
        assertEquals("OuterWithInner", relation.sourceName());
        assertEquals("OuterWithInner", relation.canonicalName());
        assertEquals("OuterWithInner", relation.topLevelClassDef().getName());
        assertEquals(List.of("InnerA", "Deep", "InnerB"), relation.innerClassRelations().stream()
                .map(FrontendInnerClassRelation::sourceName)
                .toList());
        assertEquals(List.of(
                "OuterWithInner__sub__InnerA",
                "OuterWithInner__sub__InnerA__sub__Deep",
                "OuterWithInner__sub__InnerB"
        ), relation.innerClassRelations().stream()
                .map(FrontendInnerClassRelation::canonicalName)
                .toList());
        assertEquals(List.of(
                "OuterWithInner__sub__InnerA",
                "OuterWithInner__sub__InnerA__sub__Deep",
                "OuterWithInner__sub__InnerB"
        ), innerClassDefs(relation).stream().map(LirClassDef::getName).toList());
        assertEquals(List.of("OuterWithInner"), topLevelClassDefs(result).stream().map(LirClassDef::getName).toList());
        assertEquals(List.of(
                "OuterWithInner",
                "OuterWithInner__sub__InnerA",
                "OuterWithInner__sub__InnerA__sub__Deep",
                "OuterWithInner__sub__InnerB"
        ), result.allClassDefs().stream().map(LirClassDef::getName).toList());

        var innerADeclaration = findStatement(unit.ast().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("InnerA"));
        var deepDeclaration = findStatement(innerADeclaration.body().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Deep"));
        var innerBDeclaration = findStatement(unit.ast().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("InnerB"));
        assertSame(innerADeclaration, relation.innerClassRelations().get(0).declaration());
        assertSame(deepDeclaration, relation.innerClassRelations().get(1).declaration());
        assertSame(innerBDeclaration, relation.innerClassRelations().get(2).declaration());
        assertSame(unit.ast(), relation.innerClassRelations().get(0).lexicalOwner());
        assertSame(innerADeclaration, relation.innerClassRelations().get(1).lexicalOwner());
        assertSame(unit.ast(), relation.innerClassRelations().get(2).lexicalOwner());

        var topLevelOwned = relation.findRelation(unit.ast());
        assertNotNull(topLevelOwned);
        assertSame(relation, topLevelOwned);
        assertSame(unit.ast(), topLevelOwned.astOwner());
        assertSame(unit.ast(), topLevelOwned.lexicalOwner());
        assertEquals("OuterWithInner", topLevelOwned.sourceName());
        assertEquals("OuterWithInner", topLevelOwned.canonicalName());

        var deepOwned = relation.findRelation(deepDeclaration);
        assertNotNull(deepOwned);
        assertSame(relation.innerClassRelations().get(1), deepOwned);
        assertSame(innerADeclaration, deepOwned.lexicalOwner());
        assertEquals("Deep", deepOwned.sourceName());
        assertEquals("OuterWithInner__sub__InnerA__sub__Deep", deepOwned.canonicalName());
        assertNull(relation.findRelation(unit.ast().statements().getFirst()));

        assertEquals(List.of("InnerA", "InnerB"), relation.findImmediateInnerRelations(unit.ast()).stream()
                .map(FrontendInnerClassRelation::sourceName)
                .toList());
        assertEquals(List.of("Deep"), relation.findImmediateInnerRelations(innerADeclaration).stream()
                .map(FrontendInnerClassRelation::sourceName)
                .toList());
        assertTrue(relation.findImmediateInnerRelations(innerBDeclaration).isEmpty());

        var innerA = findClassByName(innerClassDefs(relation), "OuterWithInner__sub__InnerA");
        assertEquals("RefCounted", innerA.getSuperName());
        assertEquals("hp", innerA.getProperties().getFirst().getName());
        assertEquals("ping", innerA.getFunctions().getFirst().getName());

        var innerB = findClassByName(innerClassDefs(relation), "OuterWithInner__sub__InnerB");
        assertEquals(1, innerB.getSignals().size());
        assertEquals("changed", innerB.getSignals().getFirst().getName());

        var deep = findClassByName(innerClassDefs(relation), "OuterWithInner__sub__InnerA__sub__Deep");
        assertEquals("RefCounted", deep.getSuperName());
        assertEquals("nested", deep.getFunctions().getFirst().getName());

        assertNotNull(registry.findGdccClass("OuterWithInner"));
        assertNotNull(registry.findGdccClass("OuterWithInner__sub__InnerA"));
        assertNotNull(registry.findGdccClass("OuterWithInner__sub__InnerA__sub__Deep"));
        assertNotNull(registry.findGdccClass("OuterWithInner__sub__InnerB"));
        assertNull(registry.findGdccClassSourceNameOverride("OuterWithInner"));
        assertEquals("InnerA", registry.findGdccClassSourceNameOverride("OuterWithInner__sub__InnerA"));
        assertEquals("Deep", registry.findGdccClassSourceNameOverride("OuterWithInner__sub__InnerA__sub__Deep"));
        assertEquals("InnerB", registry.findGdccClassSourceNameOverride("OuterWithInner__sub__InnerB"));

        var innerAMeta = registry.resolveTypeMeta("OuterWithInner__sub__InnerA");
        assertNotNull(innerAMeta);
        assertEquals("OuterWithInner__sub__InnerA", innerAMeta.canonicalName());
        assertEquals("InnerA", innerAMeta.sourceName());
        assertEquals("OuterWithInner__sub__InnerA", innerAMeta.displayName());
    }

    @Test
    void buildPreservesFrontendSuperclassFactsWhileWritingCanonicalSuperclassToClassDef() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "superclass_contract.gd"), """
                class_name SuperclassContract
                extends RefCounted
                
                class Shared:
                    pass
                
                class LexicalLeaf extends Shared:
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var relation = result.sourceClassRelations().getFirst();
        var lexicalLeafRelation = relation.innerClassRelations().stream()
                .filter(innerRelation -> innerRelation.canonicalName().equals("SuperclassContract__sub__LexicalLeaf"))
                .findFirst()
                .orElseThrow();

        assertEquals(new FrontendSuperClassRef("RefCounted", "RefCounted"), relation.superClassRef());
        assertEquals(
                new FrontendSuperClassRef("Shared", "SuperclassContract__sub__Shared"),
                lexicalLeafRelation.superClassRef()
        );
        assertEquals("SuperclassContract__sub__Shared", lexicalLeafRelation.classDef().getSuperName());
        assertEquals(
                "SuperclassContract__sub__Shared",
                findClassByName(result.allClassDefs(), "SuperclassContract__sub__LexicalLeaf").getSuperName()
        );
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void buildMapsTopLevelAndInnerCanonicalNamesFromFrontendModuleCanonicalNameMap() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "mapped_outer.gd"), """
                class_name MappedOuter
                extends RefCounted
                
                class Inner:
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule(
                        "mapped_module",
                        List.of(unit),
                        java.util.Map.of("MappedOuter", "RuntimeOuter")
                ),
                registry,
                diagnostics,
                analysisData
        );
        var relation = result.sourceClassRelations().getFirst();

        assertEquals("MappedOuter", relation.sourceName());
        assertEquals("RuntimeOuter", relation.canonicalName());
        assertEquals("RuntimeOuter", relation.displayName());
        assertEquals("RuntimeOuter", relation.topLevelClassDef().getName());
        assertEquals(
                List.of("RuntimeOuter__sub__Inner"),
                relation.innerClassRelations().stream()
                        .map(FrontendInnerClassRelation::canonicalName)
                        .toList()
        );
        assertEquals(
                List.of("RuntimeOuter", "RuntimeOuter__sub__Inner"),
                result.allClassDefs().stream().map(LirClassDef::getName).toList()
        );
        assertNotNull(registry.findGdccClass("RuntimeOuter"));
        assertNotNull(registry.findGdccClass("RuntimeOuter__sub__Inner"));
        assertEquals("MappedOuter", registry.findGdccClassSourceNameOverride("RuntimeOuter"));
        assertEquals("Inner", registry.findGdccClassSourceNameOverride("RuntimeOuter__sub__Inner"));
        var mappedTopLevelMeta = registry.resolveTypeMeta("RuntimeOuter");
        assertNotNull(mappedTopLevelMeta);
        assertEquals("MappedOuter", mappedTopLevelMeta.sourceName());
        assertEquals("RuntimeOuter", mappedTopLevelMeta.displayName());
        assertNull(registry.findGdccClass("MappedOuter"));
    }

    @Test
    void buildResolvesMappedTopLevelSelfDeclaredTypesViaCallerSideRemap() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "mapped_self_declared_type.gd"), """
                class_name MappedWorker
                extends RefCounted
                
                var peer: MappedWorker
                
                func copy(value: MappedWorker) -> MappedWorker:
                    return value
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule(
                        "mapped_module",
                        List.of(unit),
                        java.util.Map.of("MappedWorker", "RuntimeWorker")
                ),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = result.sourceClassRelations().getFirst().topLevelClassDef();
        var peerProperty = classDef.getProperties().getFirst();
        var copyFunction = classDef.getFunctions().getFirst();

        assertEquals(new GdObjectType("RuntimeWorker"), peerProperty.getType());
        assertEquals(new GdObjectType("RuntimeWorker"), copyFunction.getReturnType());
        assertEquals(new GdObjectType("RuntimeWorker"), copyFunction.getParameters().getFirst().getType());
        assertTrue(diagnostics.snapshot().isEmpty(), () -> "Unexpected diagnostics: " + diagnostics.snapshot());
    }

    @Test
    void buildResolvesMappedTopLevelDeclaredTypesAcrossSourceUnitsViaCallerSideRemap() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var workerUnit = parserService.parseUnit(Path.of("tmp", "mapped_worker_decl.gd"), """
                class_name MappedWorker
                extends RefCounted
                """, diagnostics);
        var consumerUnit = parserService.parseUnit(Path.of("tmp", "consumer_decl.gd"), """
                class_name Consumer
                extends RefCounted
                
                var worker: MappedWorker
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule(
                        "mapped_module",
                        List.of(workerUnit, consumerUnit),
                        java.util.Map.of("MappedWorker", "RuntimeWorker")
                ),
                registry,
                diagnostics,
                analysisData
        );
        var consumerRelation = result.sourceClassRelations().stream()
                .filter(relation -> relation.sourceName().equals("Consumer"))
                .findFirst()
                .orElseThrow();
        var workerProperty = consumerRelation.topLevelClassDef().getProperties().getFirst();

        assertEquals(new GdObjectType("RuntimeWorker"), workerProperty.getType());
        assertTrue(diagnostics.snapshot().isEmpty(), () -> "Unexpected diagnostics: " + diagnostics.snapshot());
    }

    @Test
    void buildKeepsLexicalDeclaredTypeHitAheadOfMappedTopLevelRetry() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "mapped_shadowed_declared_type.gd"), """
                class_name MappedWorker
                extends RefCounted
                
                class MappedWorker:
                    pass
                
                var local_worker: MappedWorker
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule(
                        "mapped_module",
                        List.of(unit),
                        java.util.Map.of("MappedWorker", "RuntimeWorker")
                ),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = result.sourceClassRelations().getFirst().topLevelClassDef();
        var localWorkerProperty = classDef.getProperties().getFirst();

        assertEquals(new GdObjectType("RuntimeWorker__sub__MappedWorker"), localWorkerProperty.getType());
        assertTrue(diagnostics.snapshot().isEmpty(), () -> "Unexpected diagnostics: " + diagnostics.snapshot());
    }

    @Test
    void buildResolvesMappedTopLevelSuperclassBySourceNameButPublishesMappedCanonicalSuperclass() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var baseUnit = parserService.parseUnit(Path.of("tmp", "base_map.gd"), """
                class_name BaseBySource
                extends RefCounted
                """, diagnostics);
        var childUnit = parserService.parseUnit(Path.of("tmp", "child_map.gd"), """
                class_name ChildBySource
                extends BaseBySource
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule(
                        "mapped_super_module",
                        List.of(baseUnit, childUnit),
                        java.util.Map.of("BaseBySource", "RuntimeBase")
                ),
                registry,
                diagnostics,
                analysisData
        );
        var childRelation = result.sourceClassRelations().stream()
                .filter(relation -> relation.sourceName().equals("ChildBySource"))
                .findFirst()
                .orElseThrow();

        assertEquals(new FrontendSuperClassRef("BaseBySource", "RuntimeBase"), childRelation.superClassRef());
        assertEquals("RuntimeBase", childRelation.topLevelClassDef().getSuperName());
        assertNotNull(registry.findGdccClass("RuntimeBase"));
        assertEquals(
                List.of("RuntimeBase", "ChildBySource"),
                topLevelClassDefs(result).stream().map(LirClassDef::getName).toList()
        );
    }

    @Test
    void buildResolvesDeclaredTypesThroughLexicalAndStrictRegistryPaths() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var outerUnit = parserService.parseUnit(Path.of("tmp", "outer_types.gd"), """
                class_name OuterTypes
                extends RefCounted
                
                var direct_inner: DirectInner
                var module_item: ModuleItem
                var node_lookup: Dictionary[String, Node]
                signal produced(payload: ModuleItem)
                
                func wrap(value: DirectInner) -> ModuleItem:
                    pass
                
                class DirectInner:
                    var self_ref: DirectInner
                    var outer_ref: OuterTypes
                    var module_ref: ModuleItem
                    var helper_ref: Helper
                    var helpers: Array[Helper]
                    var helper_lookup: Dictionary[String, Helper]
                
                    class Deep:
                        var self_ref: Deep
                        var parent_ref: DirectInner
                        var outer_ref: OuterTypes
                        var helper_ref: Helper
                
                class Helper:
                    var marker: int = 1
                """, diagnostics);
        var moduleItemUnit = parserService.parseUnit(Path.of("tmp", "module_item.gd"), """
                class_name ModuleItem
                extends RefCounted
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(outerUnit, moduleItemUnit)),
                registry,
                diagnostics,
                analysisData
        );
        var outer = findClassByName(topLevelClassDefs(result), "OuterTypes");
        var directInner = findClassByName(result.allClassDefs(), "OuterTypes__sub__DirectInner");
        var deep = findClassByName(result.allClassDefs(), "OuterTypes__sub__DirectInner__sub__Deep");

        assertObjectTypeName(findPropertyByName(outer, "direct_inner").getType(), "OuterTypes__sub__DirectInner");
        assertObjectTypeName(findPropertyByName(outer, "module_item").getType(), "ModuleItem");
        var nodeLookupType = assertInstanceOf(
                GdDictionaryType.class,
                findPropertyByName(outer, "node_lookup").getType()
        );
        assertEquals(GdStringType.STRING, nodeLookupType.getKeyType());
        assertObjectTypeName(nodeLookupType.getValueType(), "Node");

        var producedSignal = findSignalByName(outer, "produced");
        assertObjectTypeName(producedSignal.getParameter(0).getType(), "ModuleItem");

        var wrapFunction = findFunctionByName(outer, "wrap");
        assertObjectTypeName(wrapFunction.getParameter(0).getType(), "OuterTypes__sub__DirectInner");
        assertObjectTypeName(wrapFunction.getReturnType(), "ModuleItem");

        assertObjectTypeName(findPropertyByName(directInner, "self_ref").getType(), "OuterTypes__sub__DirectInner");
        assertObjectTypeName(findPropertyByName(directInner, "outer_ref").getType(), "OuterTypes");
        assertObjectTypeName(findPropertyByName(directInner, "module_ref").getType(), "ModuleItem");
        assertObjectTypeName(findPropertyByName(directInner, "helper_ref").getType(), "OuterTypes__sub__Helper");
        var helpersType = assertInstanceOf(
                GdArrayType.class,
                findPropertyByName(directInner, "helpers").getType()
        );
        assertObjectTypeName(helpersType.getValueType(), "OuterTypes__sub__Helper");
        var helperLookupType = assertInstanceOf(
                GdDictionaryType.class,
                findPropertyByName(directInner, "helper_lookup").getType()
        );
        assertEquals(GdStringType.STRING, helperLookupType.getKeyType());
        assertObjectTypeName(helperLookupType.getValueType(), "OuterTypes__sub__Helper");

        assertObjectTypeName(findPropertyByName(deep, "self_ref").getType(), "OuterTypes__sub__DirectInner__sub__Deep");
        assertObjectTypeName(findPropertyByName(deep, "parent_ref").getType(), "OuterTypes__sub__DirectInner");
        assertObjectTypeName(findPropertyByName(deep, "outer_ref").getType(), "OuterTypes");
        assertObjectTypeName(findPropertyByName(deep, "helper_ref").getType(), "OuterTypes__sub__Helper");

        assertTrue(diagnostics.isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void buildDoesNotResolveInnerClassSourceNamesOutsideOwningLexicalChain() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var outerUnit = parserService.parseUnit(Path.of("tmp", "outer_visibility.gd"), """
                class_name OuterVisibility
                extends RefCounted
                
                class HiddenInner:
                    pass
                """, diagnostics);
        var consumerUnit = parserService.parseUnit(Path.of("tmp", "external_consumer.gd"), """
                class_name ExternalConsumer
                extends RefCounted
                
                var leaked: HiddenInner
                var allowed: OuterVisibility
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(outerUnit, consumerUnit)),
                registry,
                diagnostics,
                analysisData
        );
        var consumer = findClassByName(topLevelClassDefs(result), "ExternalConsumer");

        assertEquals(GdVariantType.VARIANT, findPropertyByName(consumer, "leaked").getType());
        assertObjectTypeName(findPropertyByName(consumer, "allowed").getType(), "OuterVisibility");
        assertNotNull(registry.findGdccClass("OuterVisibility__sub__HiddenInner"));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.type_resolution")
                        && diagnostic.message().contains("HiddenInner")
                        && diagnostic.sourcePath() != null
                        && diagnostic.sourcePath().endsWith("external_consumer.gd")
        ));
    }

    @Test
    void buildEmitsExplicitDiagnosticsForDeferredTypeMetaSources() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var helperUnit = parserService.parseUnit(Path.of("tmp", "helper_script.gd"), """
                class_name HelperScript
                extends RefCounted
                """, diagnostics);
        var deferredUnit = parserService.parseUnit(Path.of("tmp", "deferred_type_sources.gd"), """
                class_name DeferredTypeSources
                extends RefCounted

                const Alias = HelperScript
                const Preloaded = preload("res://helper_script.gd")

                var direct: HelperScript
                var from_alias: Alias
                var from_preload: Preloaded
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(helperUnit, deferredUnit)),
                registry,
                diagnostics,
                analysisData
        );
        var deferredClass = findClassByName(topLevelClassDefs(result), "DeferredTypeSources");

        assertObjectTypeName(findPropertyByName(deferredClass, "direct").getType(), "HelperScript");
        assertEquals(GdVariantType.VARIANT, findPropertyByName(deferredClass, "from_alias").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyByName(deferredClass, "from_preload").getType());

        var typeResolutionDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_resolution"))
                .filter(diagnostic -> diagnostic.sourcePath() != null
                        && diagnostic.sourcePath().endsWith("deferred_type_sources.gd"))
                .toList();
        assertEquals(2, typeResolutionDiagnostics.size());
        assertTrue(typeResolutionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Alias")));
        assertTrue(typeResolutionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Preloaded")));
    }

    @Test
    void buildResolvesEnumTypedPropertyAnnotationToInt() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "enum_typed_properties.gd"), """
                class_name EnumTypedProperties
                extends RefCounted

                enum LocalState { IDLE, RUNNING }

                var from_enum: LocalState
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var enumClass = findClassByName(topLevelClassDefs(result), "EnumTypedProperties");

        assertEquals(GdIntType.INT, findPropertyByName(enumClass, "from_enum").getType());
        assertTrue(result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_resolution"))
                .filter(diagnostic -> diagnostic.sourcePath() != null
                        && diagnostic.sourcePath().endsWith("enum_typed_properties.gd"))
                .noneMatch(diagnostic -> diagnostic.message().contains("LocalState")));
    }

    @Test
    void buildReportsUnknownDeclaredTypesAcrossMemberSurfacesAndFallsBackToVariant() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "unknown_type_surface.gd"), """
                class_name UnknownTypeSurface
                extends RefCounted
                
                signal changed(payload: MissingSignal)
                var field: MissingField
                var bag: Array[MissingArray]
                
                func ping(arg: MissingParam) -> MissingReturn:
                    pass
                
                func _init(seed: MissingCtor):
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var unknownTypeSurface = findClassByName(topLevelClassDefs(result), "UnknownTypeSurface");
        var changedSignal = findSignalByName(unknownTypeSurface, "changed");
        var pingFunction = findFunctionByName(unknownTypeSurface, "ping");
        var initFunction = findFunctionByName(unknownTypeSurface, "_init");

        assertEquals(GdVariantType.VARIANT, changedSignal.getParameter(0).getType());
        assertEquals(GdVariantType.VARIANT, findPropertyByName(unknownTypeSurface, "field").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyByName(unknownTypeSurface, "bag").getType());
        assertEquals(GdVariantType.VARIANT, pingFunction.getParameter(0).getType());
        assertEquals(GdVariantType.VARIANT, pingFunction.getReturnType());
        assertEquals(GdVariantType.VARIANT, initFunction.getParameter(0).getType());

        var typeResolutionDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_resolution"))
                .toList();
        assertEquals(6, typeResolutionDiagnostics.size());
        assertTrue(typeResolutionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("MissingSignal")));
        assertTrue(typeResolutionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("MissingField")));
        assertTrue(typeResolutionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("MissingArray")));
        assertTrue(typeResolutionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("MissingParam")));
        assertTrue(typeResolutionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("MissingReturn")));
        assertTrue(typeResolutionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("MissingCtor")));
    }

    @Test
    void damagedMembersDoNotClaimNamesOrRegisterPropertiesDuringPrepass() throws IOException {
        var parser = new GdScriptParserService();
        var original = new DiagnosticManager();
        var unit = parser.parseUnit(Path.of("tmp", "damaged_members.gd"), """
                class:
                    pass
                var nested_bag: Array[Array[int]]
                var kept: int = 1
                enum { nested_bag = 9 }
                """, original);
        var malformedClass = assertInstanceOf(ClassDeclaration.class, unit.ast().statements().getFirst());
        var damagedProperty = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().get(1));
        var kept = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().get(2));
        var parsed = original.snapshot().asList();
        assertEquals(List.of(DiagnosticCodes.DECLARATION_MISSING_NAME, DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN),
                parsed.stream().map(FrontendDiagnostic::category).toList());
        var api = ExtensionApiLoader.loadDefault();
        for (var active : List.of(original, new DiagnosticManager())) {
            var data = new FrontendSemanticAnalyzer().analyze(
                    FrontendModule.singleUnit("test", unit), new ClassRegistry(api), active);
            var relation = data.moduleSkeleton().sourceClassRelations().getFirst();
            assertTrue(relation.innerClassRelations().isEmpty());
            assertEquals(List.of("kept"), relation.classDef().getProperties().stream().map(PropertyDef::getName).toList());
            assertEquals(List.of("nested_bag"), relation.classDef().getScriptConstants().stream().map(GdScriptClassConstant::name).toList());
            assertTrue(data.skippedSubtreeRoots().containsKey(malformedClass));
            assertTrue(data.skippedSubtreeRoots().containsKey(damagedProperty));
            assertFalse(data.scopesByAst().containsKey(damagedProperty));
            assertTrue(data.scopesByAst().containsKey(kept));
            assertEquals(unit.parseRecoveries().size(), data.claimedParseRecoveries().size());
            if (active == original) {
                assertEquals(parsed, active.snapshot().asList());
            } else {
                var fallback = active.snapshot().asList();
                assertEquals(List.of("sema.class_skeleton", "sema.class_skeleton"),
                        fallback.stream().map(FrontendDiagnostic::category).toList());
                assertTrue(fallback.getFirst().message().contains("missing a class name"));
            }
        }
    }

    @Test
    void damagedLambdaInitializerRetainsItsReturnErrorWithoutASkeletonEcho() throws IOException {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "damaged_lambda_property.gd"), """
                var callback = func():
                    return 1 +
                var kept: int = 2
                """, diagnostics);
        var variable = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, variable.value());
        var damaged = assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
        assertEquals(1, unit.parseRecoveries().size());
        assertDamagedReturnRecovery(unit, 0, damaged, "+");
        assertRejectedInitializerWithParserDiagnostics(unit, diagnostics, variable, unit.parseRecoveries());
    }

    @Test
    void distinctNestedReturnErrorsStaySeparateWithoutClaimingAnUnrelatedInitializer() throws IOException {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "two_damaged_returns.gd"), """
                var callback = func(flag):
                    if flag:
                        return 1 +
                    return 2 -
                    var unrelated = flag is Array[Array[int]]
                var kept: int = 2
                """, diagnostics);
        var variable = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, variable.value());
        var ifStatement = assertInstanceOf(IfStatement.class, lambda.body().statements().getFirst());
        var first = assertInstanceOf(ReturnStatement.class, ifStatement.body().statements().getFirst());
        var second = assertInstanceOf(ReturnStatement.class, lambda.body().statements().get(1));
        var unrelated = assertInstanceOf(VariableDeclaration.class, lambda.body().statements().getLast());
        assertEquals(3, unit.parseRecoveries().size());
        assertDamagedReturnRecovery(unit, 0, first, "+");
        assertDamagedReturnRecovery(unit, 1, second, "-");
        var independentRecovery = unit.parseRecoveries().getLast();
        assertSame(unrelated, independentRecovery.owner());
        assertEquals("variable.initializer", independentRecovery.facet());
        assertRejectedInitializerWithParserDiagnostics(unit, diagnostics, variable, unit.parseRecoveries().subList(0, 2));
    }

    @Test
    void nestedInitializerErrorsFollowTheDamagedValueSlotAndKeepIndependentErrors() throws IOException {
        var original = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "nested_initializer_slots.gd"), """
                var callback = func(flag):
                    var local = 1 +
                    2 -
                    if 3 +:
                        pass
                    if true:
                        var in_body = 4 -
                    var independent = flag is Array[Array[int]]
                var kept: int = 2
                """, original);
        var callback = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, callback.value());
        var statements = lambda.body().statements();
        assertEquals(5, statements.size());
        var local = assertInstanceOf(VariableDeclaration.class, statements.getFirst());
        var expression = assertInstanceOf(ExpressionStatement.class, statements.get(1));
        var condition = assertInstanceOf(IfStatement.class, statements.get(2));
        var healthyIf = assertInstanceOf(IfStatement.class, statements.get(3));
        var inBody = assertInstanceOf(VariableDeclaration.class, healthyIf.body().statements().getFirst());
        var independent = assertInstanceOf(VariableDeclaration.class, statements.getLast());
        assertEquals(5, unit.parseRecoveries().size());
        assertMissingOperandRecovery(unit, 0, local, "variable.initializer", "+");
        assertMissingOperandRecovery(unit, 1, expression, "expression", "-");
        assertMissingOperandRecovery(unit, 2, condition, "if.condition", "+");
        assertMissingOperandRecovery(unit, 3, inBody, "variable.initializer", "-");
        var independentRecovery = unit.parseRecoveries().getLast();
        assertSame(independent, independentRecovery.owner());
        assertEquals("variable.initializer", independentRecovery.facet());
        assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, independentRecovery.code());
        assertRejectedInitializerWithParserDiagnostics(
                unit, original, callback, unit.parseRecoveries().subList(0, 4));
    }

    @Test
    void damagedLambdaValuesKeepTheirExactOwnedRecoveryAcrossControlAndArgumentSlots() throws IOException {
        var cases = List.of(
                new LambdaDamageCase("while", "():\n    while 1 +:\n        pass\n",
                        "while.condition", WhileStatement.class),
                new LambdaDamageCase("elif", "():\n    if false:\n        pass\n    elif 1 +:\n        pass\n",
                        "elif.condition", ElifClause.class),
                new LambdaDamageCase("for", "():\n    for i in 1 +:\n        pass\n",
                        "for.iterable", ForStatement.class),
                new LambdaDamageCase("assert_condition", "():\n    assert(1 +, \"msg\")\n",
                        "assert.condition", AssertStatement.class),
                new LambdaDamageCase("assert_message", "():\n    assert(true, 1 +)\n",
                        "assert.message", AssertStatement.class),
                new LambdaDamageCase("match_subject", "():\n    match 1 +:\n        _:\n            pass\n",
                        "match.value", MatchStatement.class),
                new LambdaDamageCase("match_guard", "():\n    match 1:\n        _ when 2 +:\n            pass\n",
                        "match.guard", MatchSection.class),
                new LambdaDamageCase("match_pattern", "():\n    match 1:\n        2 +:\n            pass\n",
                        "match.pattern", MatchSection.class),
                new LambdaDamageCase("multiple_patterns", "():\n    match 1:\n        2 +, 3 -:\n            pass\n",
                        "match.pattern", MatchSection.class, List.of("+", "-")),
                new LambdaDamageCase("match_dict_key", "():\n    match {}:\n        {1 +: _}:\n            pass\n",
                        "match.pattern", MatchSection.class),
                new LambdaDamageCase("lambda_default", "(x = 1 +):\n    pass\n",
                        "parameter.default", Parameter.class),
                new LambdaDamageCase("nested_lambda_default", "():\n    var cb = func(x = 1 +):\n        pass\n",
                        "parameter.default", Parameter.class),
                new LambdaDamageCase("annotation_argument", "():\n    @warning_ignore(1 +)\n    var x: int = 2\n",
                        "annotation.arguments", AnnotationStatement.class),
                new LambdaDamageCase("multiple_annotation_arguments",
                        "():\n    @warning_ignore(1 +, 2 -)\n    var x: int = 2\n",
                        "annotation.arguments", AnnotationStatement.class, List.of("+", "-"))
        );
        var parser = new GdScriptParserService();
        for (var fixture : cases) {
            var original = new DiagnosticManager();
            var unit = parser.parseUnit(Path.of("tmp", fixture.name() + ".gd"),
                    "var callback = func" + fixture.lambdaTail() + "var kept: int = 2\n", original);
            var callback = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
            assertEquals(fixture.operators().size(), unit.parseRecoveries().size(), fixture.name());
            var owner = assertInstanceOf(fixture.ownerType(), unit.parseRecoveries().getFirst().owner(), fixture.name());
            assertTrue(containsNode(callback, owner), fixture.name());
            for (var index = 0; index < fixture.operators().size(); index++) {
                assertMissingOperandRecovery(unit, index, owner, fixture.facet(), fixture.operators().get(index));
            }
            assertRejectedInitializerWithParserDiagnostics(unit, original, callback, unit.parseRecoveries());
        }
    }

    @Test
    void oneRetainedRecoveryCannotCoverTwoBrokenPatternsInTheSameSection() throws IOException {
        var parsedDiagnostics = new DiagnosticManager();
        var parsed = new GdScriptParserService().parseUnit(Path.of("tmp", "partial_pattern_recoveries.gd"), """
                var callback = func():
                    match 1:
                        2 +, 3 -:
                            pass
                var kept: int = 2
                """, parsedDiagnostics);
        var callback = assertInstanceOf(VariableDeclaration.class, parsed.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, callback.value());
        var match = assertInstanceOf(MatchStatement.class, lambda.body().statements().getFirst());
        var section = match.sections().getFirst();
        assertEquals(2, section.patterns().size());
        assertEquals(2, parsed.parseRecoveries().size());
        assertMissingOperandRecovery(parsed, 0, section, "match.pattern", "+");
        assertMissingOperandRecovery(parsed, 1, section, "match.pattern", "-");
        assertEachSharedRecoveryAloneLeavesASiblingUncovered(parsed, parsedDiagnostics, callback);
    }

    @Test
    void oneRetainedRecoveryCannotCoverTwoBrokenAnnotationArguments() throws IOException {
        var parsedDiagnostics = new DiagnosticManager();
        var parsed = new GdScriptParserService().parseUnit(Path.of("tmp", "partial_annotation_arguments.gd"), """
                var callback = func():
                    @warning_ignore(1 +, 2 -)
                    var x: int = 2
                var kept: int = 2
                """, parsedDiagnostics);
        var callback = assertInstanceOf(VariableDeclaration.class, parsed.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, callback.value());
        var annotation = assertInstanceOf(AnnotationStatement.class, lambda.body().statements().getFirst());
        assertEquals(2, annotation.arguments().size());
        assertEquals(2, parsed.parseRecoveries().size());
        assertMissingOperandRecovery(parsed, 0, annotation, "annotation.arguments", "+");
        assertMissingOperandRecovery(parsed, 1, annotation, "annotation.arguments", "-");
        assertEachSharedRecoveryAloneLeavesASiblingUncovered(parsed, parsedDiagnostics, callback);
    }

    private static void assertEachSharedRecoveryAloneLeavesASiblingUncovered(
            FrontendSourceUnit parsed, DiagnosticManager parsedDiagnostics, VariableDeclaration callback
    ) throws IOException {
        var parsedErrors = parsedDiagnostics.snapshot().asList();
        for (var index : List.of(0, 1)) {
            var retained = List.of(parsed.parseRecoveries().get(index));
            var original = new DiagnosticManager();
            original.report(parsedErrors.get(index));
            var partial = new FrontendSourceUnit(parsed.path(), parsed.source(), parsed.ast(), false, retained);
            assertRejectedInitializerDiagnostics(partial, original, callback, retained, true);
        }
    }

    @Test
    void sharedPatternRecoveriesKeepAllErrorsInADamagedChildButDoNotClaimItsHealthySibling() throws IOException {
        var original = new DiagnosticManager();
        var parsed = new GdScriptParserService().parseUnit(Path.of("tmp", "pattern_recovery_ranges.gd"), """
                var callback = func():
                    match 1:
                        [2 +, 3 -], 4:
                            pass
                var kept: int = 2
                """, original);
        var callback = assertInstanceOf(VariableDeclaration.class, parsed.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, callback.value());
        var match = assertInstanceOf(MatchStatement.class, lambda.body().statements().getFirst());
        var section = match.sections().getFirst();
        assertEquals(2, parsed.parseRecoveries().size());
        assertMissingOperandRecovery(parsed, 0, section, "match.pattern", "+");
        assertMissingOperandRecovery(parsed, 1, section, "match.pattern", "-");
        var damaged = FrontendRange.fromAstRange(section.patterns().getFirst().range());
        var healthy = FrontendRange.fromAstRange(section.patterns().getLast().range());
        assertNotNull(damaged);
        assertNotNull(healthy);
        assertTrue(damaged.endByte() < healthy.startByte());
        var edge = new FrontendRange(damaged.endByte(), damaged.endByte(), damaged.end(), damaged.end());
        var separator = new FrontendRange(damaged.endByte(), healthy.startByte(), damaged.end(), healthy.start());
        var atEnd = patternRecovery(section, edge, "missing token at the damaged pattern edge");
        var between = patternRecovery(section, separator, "separator does not belong to a pattern");
        var other = patternRecovery(section, healthy, "independent issue in the healthy pattern");
        var noRange = patternRecovery(section, null, "issue without a parser range");
        var retained = List.of(parsed.parseRecoveries().getFirst(), parsed.parseRecoveries().getLast(), atEnd);
        var all = List.of(retained.getFirst(), retained.get(1), atEnd, between, other, noRange);
        for (var recovery : List.of(atEnd, between, other, noRange)) {
            original.report(parserDiagnostic(parsed, recovery));
        }
        var unit = new FrontendSourceUnit(parsed.path(), parsed.source(), parsed.ast(), false, all);
        assertRejectedInitializerWithParserDiagnostics(unit, original, callback, retained);
    }

    @Test
    void zeroWidthRecoveryAtATouchingPatternBoundaryRemainsIndependent() throws IOException {
        var parserDiagnostics = new DiagnosticManager();
        var parsed = new GdScriptParserService().parseUnit(Path.of("tmp", "touching_patterns.gd"), """
                var callback = func():
                    match 1:
                        2 +:
                            pass
                var kept: int = 2
                """, parserDiagnostics);
        var sourceCallback = assertInstanceOf(VariableDeclaration.class, parsed.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, sourceCallback.value());
        var match = assertInstanceOf(MatchStatement.class, lambda.body().statements().getFirst());
        var section = match.sections().getFirst();
        var binary = assertInstanceOf(BinaryExpression.class, section.patterns().getFirst());
        var operand = assertInstanceOf(ErrorExpression.class, binary.right());
        assertEquals(binary.range().endByte(), operand.range().startByte());
        assertEquals(operand.range().startByte(), operand.range().endByte());
        var adjacent = new ErrorExpression(CstIssueKind.MISSING, "match.pattern", "", operand.range());
        var newSection = new MatchSection(List.of(binary, adjacent), section.guard(), section.body(), section.range());
        var newMatch = new MatchStatement(match.value(), List.of(newSection), match.range());
        var newBody = new Block(List.of(newMatch), lambda.body().range());
        var newLambda = new LambdaExpression(lambda.name(), lambda.parameters(), lambda.returnType(), newBody, lambda.range());
        var callback = new VariableDeclaration(sourceCallback.kind(), sourceCallback.name(), sourceCallback.type(), newLambda,
                sourceCallback.isStatic(), sourceCallback.sourceNodeType(), sourceCallback.range());
        var tree = new SourceFile(List.of(callback, parsed.ast().statements().getLast()), parsed.ast().range());
        var former = parsed.parseRecoveries().getFirst();
        var first = new FrontendParseRecovery(former.code(), former.severity(), former.message(), former.range(),
                newSection, former.facet());
        var ambiguous = patternRecovery(newSection, FrontendRange.fromAstRange(adjacent.range()), "ambiguous shared edge");
        var unit = new FrontendSourceUnit(parsed.path(), parsed.source(), tree, false, List.of(first, ambiguous));
        var original = new DiagnosticManager();
        original.reportAll(List.of(parserDiagnostic(unit, first), parserDiagnostic(unit, ambiguous)));
        assertRejectedInitializerDiagnostics(unit, original, callback, List.of(first), true);
    }

    private static FrontendParseRecovery patternRecovery(
            MatchSection section, @Nullable FrontendRange range, String message
    ) {
        return new FrontendParseRecovery(DiagnosticCodes.SLOT_MATCH_PATTERN, FrontendDiagnosticSeverity.ERROR,
                message, range, section, "match.pattern");
    }

    private static FrontendDiagnostic parserDiagnostic(FrontendSourceUnit unit, FrontendParseRecovery recovery) {
        return FrontendDiagnostic.error(recovery.code(), recovery.message(),
                FrontendDiagnostic.sourcePathText(unit.path()), recovery.range());
    }

    @Test
    void assertDelimiterErrorStaysIndependentFromTheDamagedConditionOnTheSameOwner() throws IOException {
        var original = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "damaged_assert_delimiter.gd"), """
                var callback = func():
                    assert 1 +
                var kept: int = 2
                """, original);
        var callback = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, callback.value());
        var assertion = assertInstanceOf(AssertStatement.class, lambda.body().statements().getFirst());
        assertEquals(2, unit.parseRecoveries().size());
        var delimiter = unit.parseRecoveries().getFirst();
        assertSame(assertion, delimiter.owner());
        assertEquals("assert.arguments", delimiter.facet());
        assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, delimiter.code());
        assertEquals("assert", unit.source().substring(delimiter.range().startByte(), delimiter.range().endByte()));
        assertMissingOperandRecovery(unit, 1, assertion, "assert.condition", "+");
        assertRejectedInitializerWithParserDiagnostics(
                unit, original, callback, List.of(unit.parseRecoveries().getLast()));
    }

    private record LambdaDamageCase(
            String name, String lambdaTail, String facet, Class<? extends Node> ownerType, List<String> operators
    ) {
        private LambdaDamageCase(String name, String lambdaTail, String facet, Class<? extends Node> ownerType) {
            this(name, lambdaTail, facet, ownerType, List.of("+"));
        }
    }

    private static boolean containsNode(Node node, Node expected) {
        if (node == expected) {
            return true;
        }
        for (var child : node.getChildren()) {
            if (containsNode(child, expected)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void missingRecoveryForAnotherDamagedSlotKeepsTheSkeletonFallback() throws IOException {
        var parsedDiagnostics = new DiagnosticManager();
        var parsedUnit = new GdScriptParserService().parseUnit(Path.of("tmp", "unowned_initializer_slot.gd"), """
                var callback = func():
                    if true:
                        var local = 1 +
                    match 2:
                        _ when 3 -:
                            pass
                var kept: int = 2
                """, parsedDiagnostics);
        var callback = assertInstanceOf(VariableDeclaration.class, parsedUnit.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, callback.value());
        var healthyIf = assertInstanceOf(IfStatement.class, lambda.body().statements().getFirst());
        var local = assertInstanceOf(VariableDeclaration.class, healthyIf.body().statements().getFirst());
        var match = assertInstanceOf(MatchStatement.class, lambda.body().statements().getLast());
        var section = match.sections().getFirst();
        assertEquals(2, parsedUnit.parseRecoveries().size());
        assertMissingOperandRecovery(parsedUnit, 0, local, "variable.initializer", "+");
        assertMissingOperandRecovery(parsedUnit, 1, section, "match.guard", "-");
        // Keep the damaged guard but simulate a caller that has only the local initializer recovery.
        var retained = parsedUnit.parseRecoveries().getFirst();
        var unit = new FrontendSourceUnit(
                parsedUnit.path(), parsedUnit.source(), parsedUnit.ast(), false, List.of(retained));
        var expectedParserError = parsedDiagnostics.snapshot().asList().getFirst();
        var original = new DiagnosticManager();
        original.report(expectedParserError);
        var api = ExtensionApiLoader.loadDefault();
        for (var live : List.of(original, new DiagnosticManager())) {
            var data = new FrontendSemanticAnalyzer().analyze(
                    FrontendModule.singleUnit("test", unit), new ClassRegistry(api), live);
            var errors = live.snapshot().asList();
            assertEquals(2, errors.size());
            assertEquals(expectedParserError, errors.getFirst());
            assertEquals("sema.class_skeleton", errors.getLast().category());
            assertEquals(FrontendRange.fromAstRange(callback.range()), errors.getLast().range());
            assertEquals(1, data.claimedParseRecoveries().size());
            assertEquals(expectedParserError, data.claimedParseRecoveries().get(retained));
            assertTrue(data.skippedSubtreeRoots().containsKey(callback));
            assertEquals(List.of("kept"), data.moduleSkeleton().sourceClassRelations().getFirst().classDef()
                    .getProperties().stream().map(PropertyDef::getName).toList());
        }
    }

    @Test
    void damagedClassConstantInitializerRetainsItsReturnErrorWithoutASkeletonEcho() throws IOException {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "damaged_lambda_constant.gd"), """
                const CALLBACK = func():
                    return 1 +
                const KEPT = 2
                var kept: int = 2
                """, diagnostics);
        var constant = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, constant.value());
        var damaged = assertInstanceOf(ReturnStatement.class, lambda.body().statements().getFirst());
        assertEquals(1, unit.parseRecoveries().size());
        assertDamagedReturnRecovery(unit, 0, damaged, "+");
        assertRejectedInitializerWithParserDiagnostics(unit, diagnostics, constant, unit.parseRecoveries());
    }

    private static void assertDamagedReturnRecovery(
            FrontendSourceUnit unit, int index, ReturnStatement owner, String operator
    ) {
        assertMissingOperandRecovery(unit, index, owner, "return.value", operator);
    }

    private static void assertMissingOperandRecovery(
            FrontendSourceUnit unit, int index, Node owner, String facet, String operator
    ) {
        var recovery = unit.parseRecoveries().get(index);
        assertSame(owner, recovery.owner());
        assertEquals(facet, recovery.facet());
        assertEquals(DiagnosticCodes.SLOT_BINARY_RIGHT, recovery.code());
        assertEquals(operator, unit.source().substring(recovery.range().startByte(), recovery.range().endByte()));
    }

    private static void assertRejectedInitializerWithParserDiagnostics(
            FrontendSourceUnit unit, DiagnosticManager original, VariableDeclaration rejected,
            List<FrontendParseRecovery> coveredRecoveries
    ) throws IOException {
        assertRejectedInitializerDiagnostics(unit, original, rejected, coveredRecoveries, false);
    }

    private static void assertRejectedInitializerDiagnostics(
            FrontendSourceUnit unit, DiagnosticManager original, VariableDeclaration rejected,
            List<FrontendParseRecovery> coveredRecoveries, boolean wantsSkeletonFallback
    ) throws IOException {
        var parsed = original.snapshot().asList();
        assertEquals(unit.parseRecoveries().size(), parsed.size());
        var kept = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getLast());
        var api = ExtensionApiLoader.loadDefault();
        for (var active : List.of(original, new DiagnosticManager())) {
            var data = new FrontendSemanticAnalyzer().analyze(
                    FrontendModule.singleUnit("test", unit), new ClassRegistry(api), active);
            var owner = data.moduleSkeleton().sourceClassRelations().getFirst().classDef();
            assertEquals(List.of("kept"), owner.getProperties().stream().map(PropertyDef::getName).toList());
            if (rejected.name().equals("CALLBACK")) {
                assertEquals(List.of("KEPT"), owner.getScriptConstants().stream().map(GdScriptClassConstant::name).toList());
            }
            assertTrue(data.skippedSubtreeRoots().containsKey(rejected));
            assertFalse(data.scopesByAst().containsKey(rejected));
            assertTrue(data.scopesByAst().containsKey(kept));
            var errors = active.snapshot().asList();
            assertEquals(parsed.size() + (wantsSkeletonFallback ? 1 : 0), errors.size(), errors::toString);
            var fallbacks = errors.stream().filter(error -> error.category().equals("sema.class_skeleton")).toList();
            if (wantsSkeletonFallback) {
                assertEquals(1, fallbacks.size());
                assertEquals(FrontendRange.fromAstRange(rejected.range()), fallbacks.getFirst().range());
            } else {
                assertTrue(fallbacks.isEmpty());
            }
            var parserErrors = errors.stream().filter(error -> error.category().startsWith("parse.")).toList();
            assertEquals(Set.copyOf(parsed), Set.copyOf(parserErrors));
            assertEquals(coveredRecoveries.size(), data.claimedParseRecoveries().size());
            for (var index = 0; index < unit.parseRecoveries().size(); index++) {
                var recovery = unit.parseRecoveries().get(index);
                var claim = data.claimedParseRecoveries().get(recovery);
                if (coveredRecoveries.stream().anyMatch(covered -> recovery == covered)) {
                    assertEquals(parsed.get(index), claim);
                } else {
                    assertNull(claim);
                }
            }
        }
    }

    @Test
    void depthPlaceholderNeverReportsMissingClassNameInEitherManager() throws IOException {
        var source = new StringBuilder("var kept: int = 1\n");
        for (var nesting = 0; nesting < 263; nesting++) {
            source.repeat("    ", nesting).append("class Deep:\n");
        }
        source.repeat("    ", 263).append("pass\n");
        var original = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "deep_classes.gd"), source.toString(), original);
        var parsed = original.snapshot().asList();
        assertEquals(1, parsed.size());
        assertEquals(DiagnosticCodes.NESTING_DEPTH_EXCEEDED, parsed.getFirst().category());
        var api = ExtensionApiLoader.loadDefault();
        for (var active : List.of(original, new DiagnosticManager())) {
            var data = new FrontendSemanticAnalyzer().analyze(
                    FrontendModule.singleUnit("test", unit), new ClassRegistry(api), active);
            var relation = data.moduleSkeleton().sourceClassRelations().getFirst();
            assertEquals(parsed, active.snapshot().asList());
            assertTrue(data.claimedParseRecoveries().isEmpty());
            assertTrue(data.skippedSubtreeRoots().keySet().stream().anyMatch(node ->
                    node instanceof ClassDeclaration declaration && declaration.name() == null));
            assertEquals(GdIntType.INT, findPropertyByName(relation.classDef(), "kept").getType());
        }
    }

    @Test
    void damagedSignatureSlotsDoNotEraseIntSiblingsOrFunctionBody() throws IOException {
        var original = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "damaged_signature.gd"), """
                signal changed(broken: Array[Array[int]], kept: int)
                func ping(x:, y: int) -> Array[Array[int]]:
                    return y
                """, original);
        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getLast());
        var parsed = original.snapshot().asList();
        assertEquals(3, parsed.size());
        var api = ExtensionApiLoader.loadDefault();
        for (var active : List.of(original, new DiagnosticManager())) {
            var data = new FrontendSemanticAnalyzer().analyze(
                    FrontendModule.singleUnit("test", unit), new ClassRegistry(api), active);
            var classDef = data.moduleSkeleton().sourceClassRelations().getFirst().classDef();
            var signal = findSignalByName(classDef, "changed");
            var ping = findFunctionByName(classDef, "ping");
            assertEquals(GdVariantType.VARIANT, signal.getParameter(0).getType());
            assertEquals(GdIntType.INT, signal.getParameter(1).getType());
            assertEquals(GdVariantType.VARIANT, ping.getParameter(0).getType());
            assertEquals(GdIntType.INT, ping.getParameter(1).getType());
            assertEquals(GdVariantType.VARIANT, ping.getReturnType());
            assertTrue(data.scopesByAst().containsKey(function.body()));
            assertFalse(data.skippedSubtreeRoots().containsKey(function));
            assertEquals(3, data.claimedParseRecoveries().size());
            if (active == original) {
                assertEquals(parsed, active.snapshot().asList());
            } else {
                assertEquals(List.of("sema.type_resolution", "sema.type_resolution", "sema.type_resolution"),
                        active.snapshot().asList().stream().map(FrontendDiagnostic::category).toList());
            }
        }
    }

    @Test
    void buildLowersConstructorDeclarationsIntoInitFunctionsPerClass() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "constructor_members.gd"), """
                class_name ConstructorMembers
                extends RefCounted
                
                func _init(seed: int, alias = seed):
                    pass
                
                class Inner:
                    func _init(label: String):
                        pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var topLevel = findClassByName(topLevelClassDefs(result), "ConstructorMembers");
        var inner = findClassByName(result.allClassDefs(), "ConstructorMembers__sub__Inner");

        var topLevelInit = findFunctionByName(topLevel, "_init");
        assertEquals(GdVoidType.VOID, topLevelInit.getReturnType());
        assertEquals(2, topLevelInit.getParameterCount());
        assertEquals("seed", topLevelInit.getParameter(0).getName());
        assertEquals(GdIntType.INT, topLevelInit.getParameter(0).getType());
        assertEquals("alias", topLevelInit.getParameter(1).getName());
        assertEquals(GdVariantType.VARIANT, topLevelInit.getParameter(1).getType());
        // Skeleton keeps default metadata empty; the parameter-default metadata owner writes or
        // reclaims `defaultValueFunc` during the suite phase.
        assertNull(topLevelInit.getParameter(0).getDefaultValueFunc());
        assertNull(topLevelInit.getParameter(1).getDefaultValueFunc());

        var innerInit = findFunctionByName(inner, "_init");
        assertEquals(GdVoidType.VOID, innerInit.getReturnType());
        assertEquals(1, innerInit.getParameterCount());
        assertEquals("label", innerInit.getParameter(0).getName());
        assertEquals(GdStringType.STRING, innerInit.getParameter(0).getType());

        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void buildSkipsDuplicateInitConstructorsWithinSameClassButKeepsOtherClassesAlive() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "duplicate_init_constructor.gd"), """
                class_name DuplicateInitConstructor
                extends RefCounted
                
                func _init(seed: int):
                    pass
                
                func _init(name: String):
                    pass
                
                func ping():
                    pass
                
                class Inner:
                    func _init():
                        pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var topLevel = findClassByName(topLevelClassDefs(result), "DuplicateInitConstructor");
        var inner = findClassByName(result.allClassDefs(), "DuplicateInitConstructor__sub__Inner");

        assertEquals(List.of("_init", "ping"), topLevel.getFunctions().stream()
                .map(LirFunctionDef::getName)
                .toList());
        var topLevelInit = findFunctionByName(topLevel, "_init");
        assertEquals(1, topLevelInit.getParameterCount());
        assertEquals("seed", topLevelInit.getParameter(0).getName());
        assertEquals(GdIntType.INT, topLevelInit.getParameter(0).getType());

        var innerInit = findFunctionByName(inner, "_init");
        assertEquals(0, innerInit.getParameterCount());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("DuplicateInitConstructor")
                        && diagnostic.message().contains("more than one '_init'")
        ));
    }

    @Test
    void buildResolvesMemberTypesAgainstLaterTopLevelAndInnerClasses() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var builder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "phase3_probe.gd"), """
                class_name Phase3Probe
                extends Node
                
                signal changed(target: LaterScript)
                
                func _init(seed: int):
                    pass
                
                func create(target: LaterScript) -> Inner:
                    pass

                class Inner:
                    var hp: int = 1
                    var outer: Phase3Probe
                    func _init():
                        pass
                    func ping():
                        pass
                """, diagnostics);
        var later = parserService.parseUnit(Path.of("tmp", "later_script.gd"), """
                class_name LaterScript
                extends RefCounted
                var peer: Phase3Probe
                """, diagnostics);
        assertTrue(diagnostics.isEmpty());
        var result = builder.build(new FrontendModule("phase3_probe_module", List.of(unit, later)),
                registry, diagnostics, analysisData);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics()::toString);

        var outerRelation = result.sourceClassRelations().getFirst();
        assertSame(unit, outerRelation.unit());
        assertEquals(1, outerRelation.innerClassRelations().size());
        var outer = outerRelation.topLevelClassDef();
        var inner = outerRelation.innerClassRelations().getFirst().classDef();
        var laterClass = result.sourceClassRelations().getLast().topLevelClassDef();
        assertSame(outer, registry.findGdccClass("Phase3Probe"));
        assertSame(inner, registry.findGdccClass("Phase3Probe__sub__Inner"));
        assertSame(laterClass, registry.findGdccClass("LaterScript"));
        assertNull(registry.findGdccClassSourceNameOverride("Phase3Probe"));
        assertEquals("Inner", registry.findGdccClassSourceNameOverride("Phase3Probe__sub__Inner"));

        var innerTypeMeta = registry.resolveTypeMeta("Phase3Probe__sub__Inner");
        assertNotNull(innerTypeMeta);
        assertEquals("Phase3Probe__sub__Inner", innerTypeMeta.canonicalName());
        assertEquals("Inner", innerTypeMeta.sourceName());

        var signal = outer.getSignals().getFirst();
        assertEquals("changed", signal.getName());
        assertObjectTypeName(signal.getParameters().getFirst().getType(), "LaterScript");
        assertEquals(GdIntType.INT, findFunctionByName(outer, "_init").getParameters().getFirst().getType());
        var create = findFunctionByName(outer, "create");
        assertObjectTypeName(create.getParameters().getFirst().getType(), "LaterScript");
        assertObjectTypeName(create.getReturnType(), "Phase3Probe__sub__Inner");
        assertEquals(GdIntType.INT, findPropertyByName(inner, "hp").getType());
        assertObjectTypeName(findPropertyByName(inner, "outer").getType(), "Phase3Probe");
        assertObjectTypeName(findPropertyByName(laterClass, "peer").getType(), "Phase3Probe");
        assertEquals(List.of("_init", "ping"), inner.getFunctions().stream()
                .map(LirFunctionDef::getName)
                .toList());
    }

    /// Verifies the shared parse->skeleton pipeline keeps the original parse diagnostics exactly
    /// once. The builder must not duplicate either the attributed signature recovery or the
    /// separate unexpected token reported without an owner.
    @Test
    void buildKeepsSharedParseDiagnosticsWithoutDuplicatingThem() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();

        var unit = parserService.parseUnit(Path.of("tmp", "broken_shared_pipeline.gd"), """
                class_name BrokenSharedPipeline
                extends Node
                
                func _ready(
                    pass
                """, diagnostics);
        var parseSnapshot = diagnostics.snapshot();

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "BrokenSharedPipeline");

        assertEquals("BrokenSharedPipeline", classDef.getName());
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals(parseSnapshot, result.diagnostics());
        assertEquals(2, parseSnapshot.size());
        assertEquals(2, unit.parseRecoveries().size());
        var missingCloser = unit.parseRecoveries().getFirst();
        var unexpectedIndent = unit.parseRecoveries().getLast();
        var function = findStatement(unit.ast().statements(), FunctionDeclaration.class, f -> f.name().equals("_ready"));
        assertSame(function, missingCloser.owner());
        assertEquals("function.signature", missingCloser.facet());
        assertEquals(DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST, missingCloser.code());
        assertEquals(FrontendRange.fromAstRange(function.range()).end(), missingCloser.range().start());
        assertEquals(missingCloser.range().startByte(), missingCloser.range().endByte());
        assertNull(unexpectedIndent.owner());
        assertNull(unexpectedIndent.facet());
        assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, unexpectedIndent.code());
        assertEquals(unit.source().indexOf("    pass"), unexpectedIndent.range().startByte());
        assertEquals(unit.source().indexOf("pass"), unexpectedIndent.range().endByte());
        var path = FrontendDiagnostic.sourcePathText(unit.path());
        assertEquals(FrontendDiagnostic.error(DiagnosticCodes.MISSING_CLOSER_PARAMETER_LIST,
                "Expected closing \")\" after function parameters.", path, missingCloser.range()),
                parseSnapshot.asList().getFirst());
        assertEquals(FrontendDiagnostic.error(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN,
                "Unexpected \"Indent\" in class body.", path, unexpectedIndent.range()),
                parseSnapshot.asList().getLast());
    }

    @Test
    void buildReportsDuplicateTopLevelClassAndSkipsDuplicateSourceSubtree() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();

        var original = parserService.parseUnit(Path.of("tmp", "first_shared.gd"), """
                class_name SharedName
                extends Node
                
                func from_first():
                    pass
                """, diagnostics);
        var duplicate = parserService.parseUnit(Path.of("tmp", "second_shared.gd"), """
                class_name SharedName
                extends Node
                
                func from_second():
                    pass
                """, diagnostics);
        var unique = parserService.parseUnit(Path.of("tmp", "unique.gd"), """
                class_name UniqueName
                extends Node
                
                func ok():
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("duplicate_module", List.of(original, duplicate, unique)),
                registry,
                diagnostics,
                analysisData
        );

        assertEquals(List.of("SharedName", "UniqueName"), topLevelClassDefs(result).stream().map(LirClassDef::getName).toList());
        assertEquals(2, result.sourceClassRelations().size());
        assertEquals("from_first", findClassByName(topLevelClassDefs(result), "SharedName").getFunctions().getFirst().getName());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("Duplicate top-level class source name 'SharedName'")
                        && diagnostic.message().contains("second_shared.gd")
        ));

        assertNotNull(registry.findGdccClass("SharedName"));
        assertNotNull(registry.findGdccClass("UniqueName"));
    }

    @Test
    void buildRejectsReservedSyntheticPropertyHelperPrefixesButKeepsBoundaryNamesAlive() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "reserved_property_helper_names.gd"), """
                class_name ReservedPropertyHelperNames
                extends RefCounted
                
                signal _field_init_changed()
                signal _field_setter
                
                var _field_getter_value: int = 1
                var _field_getter: int = 2
                
                func _field_setter_value():
                    pass
                
                func _field_init():
                    pass
                
                func ok():
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "ReservedPropertyHelperNames");
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertNull(findSignalByNameOrNull(classDef, "_field_init_changed"));
        assertNotNull(findSignalByNameOrNull(classDef, "_field_setter"));
        assertNull(findPropertyByNameOrNull(classDef, "_field_getter_value"));
        assertNotNull(findPropertyByNameOrNull(classDef, "_field_getter"));
        assertNull(findFunctionByNameOrNull(classDef, "_field_setter_value"));
        assertNotNull(findFunctionByNameOrNull(classDef, "_field_init"));
        assertNotNull(findFunctionByNameOrNull(classDef, "ok"));
        assertEquals(3, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.message().contains("reserved synthetic property-helper prefix")
        ));
    }

    /// The `_onready_field_default_` namespace is compiler-owned for the backend-synthesized
    /// onready default-value helpers, so source members reusing it are rejected like the other
    /// reserved prefixes. Boundary names that lack the trailing separator stay alive.
    @Test
    void buildRejectsReservedOnreadyFieldDefaultPrefixButKeepsBoundaryNamesAlive() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "reserved_onready_default_names.gd"), """
                class_name ReservedOnreadyDefaultNames
                extends RefCounted

                signal _onready_field_default_changed()

                var _onready_field_default_value: int = 1
                var _onready_field_default: int = 2
                var _onready_field: int = 3

                func _onready_field_default_value():
                    pass

                func ok():
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "ReservedOnreadyDefaultNames");
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertNull(findSignalByNameOrNull(classDef, "_onready_field_default_changed"));
        assertNull(findPropertyByNameOrNull(classDef, "_onready_field_default_value"));
        assertNotNull(findPropertyByNameOrNull(classDef, "_onready_field_default"));
        assertNotNull(findPropertyByNameOrNull(classDef, "_onready_field"));
        assertNull(findFunctionByNameOrNull(classDef, "_onready_field_default_value"));
        assertNotNull(findFunctionByNameOrNull(classDef, "ok"));
        assertEquals(3, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.message().contains("reserved synthetic onready-default prefix '_onready_field_default_'")
        ));
    }

    /// The `_lambda_` namespace is compiler-owned for synthesized lambda
    /// shells, so source members reusing it are rejected like `_field_*` helpers. Boundary names
    /// that merely contain the prefix (without the trailing separator) stay alive.
    @Test
    void buildRejectsReservedLambdaFunctionPrefixButKeepsBoundaryNamesAlive() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "reserved_lambda_names.gd"), """
                class_name ReservedLambdaNames
                extends RefCounted
                
                func _lambda_0():
                    pass
                
                func _lambda():
                    pass
                
                func ok():
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "ReservedLambdaNames");
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertNull(findFunctionByNameOrNull(classDef, "_lambda_0"));
        assertNotNull(findFunctionByNameOrNull(classDef, "_lambda"));
        assertNotNull(findFunctionByNameOrNull(classDef, "ok"));
        assertEquals(1, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.getFirst().message().contains("reserved synthetic lambda-function prefix"));
        assertTrue(skeletonDiagnostics.getFirst().message().contains("_lambda_"));
    }

    /// The `_default_` namespace is compiler-owned for synthesized parameter-default functions
    /// (`_default_<func>$<param>`), so source members reusing it are rejected like the other
    /// reserved prefixes. Boundary names without the trailing separator stay alive.
    @Test
    void buildRejectsReservedParameterDefaultPrefixButKeepsBoundaryNamesAlive() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "reserved_parameter_default_names.gd"), """
                class_name ReservedParameterDefaultNames
                extends RefCounted
                
                func _default_ping():
                    pass
                
                func _default():
                    pass
                
                func ok():
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "ReservedParameterDefaultNames");
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertNull(findFunctionByNameOrNull(classDef, "_default_ping"));
        assertNotNull(findFunctionByNameOrNull(classDef, "_default"));
        assertNotNull(findFunctionByNameOrNull(classDef, "ok"));
        assertEquals(1, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.getFirst().message().contains("reserved synthetic parameter-default prefix"));
        assertTrue(skeletonDiagnostics.getFirst().message().contains("_default_"));
    }

    /// The `_gdcc_` member namespace is compiler-owned for backend-synthesized accessors
    /// (`_gdcc_get_metadata`): a source function with that exact name would collide
    /// with the synthesized C symbol and its ClassDB registration. Boundary names that lack the
    /// trailing separator (`_gdcc`, `_gdccx`) stay alive.
    @Test
    void buildRejectsReservedGdccInternalPrefixButKeepsBoundaryNamesAlive() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "reserved_gdcc_internal_names.gd"), """
                class_name ReservedGdccInternalNames
                extends RefCounted

                signal _gdcc_changed()

                var _gdcc_state: int = 1

                func _gdcc_get_metadata():
                    pass

                func _gdcc_helper():
                    pass

                func _gdcc():
                    pass

                func _gdccx():
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "ReservedGdccInternalNames");
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertNull(findSignalByNameOrNull(classDef, "_gdcc_changed"));
        assertNull(findPropertyByNameOrNull(classDef, "_gdcc_state"));
        assertNull(findFunctionByNameOrNull(classDef, "_gdcc_get_metadata"));
        assertNull(findFunctionByNameOrNull(classDef, "_gdcc_helper"));
        assertNotNull(findFunctionByNameOrNull(classDef, "_gdcc"));
        assertNotNull(findFunctionByNameOrNull(classDef, "_gdccx"));
        assertEquals(4, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.message().contains("reserved gdcc-internal prefix '_gdcc_'")
        ));
    }

    @Test
    void buildRejectsGdccSignalThatShadowsInheritedEngineSignalButKeepsOtherMembersAlive() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "engine_signal_conflict.gd"), """
                class_name EngineSignalConflict
                extends Node
                
                signal ready()
                signal pinged(value: int)
                
                func ok():
                    pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "EngineSignalConflict");
        var readyStatement = findStatement(
                unit.ast().statements(),
                SignalStatement.class,
                statement -> statement.name().equals("ready")
        );
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertNull(findSignalByNameOrNull(classDef, "ready"));
        var pinged = findSignalByName(classDef, "pinged");
        assertEquals(1, pinged.getParameterCount());
        assertEquals("value", pinged.getParameter(0).getName());
        assertEquals(GdIntType.INT, pinged.getParameter(0).getType());
        assertNotNull(findFunctionByNameOrNull(classDef, "ok"));
        assertEquals(1, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.getFirst().message().contains("ready"));
        assertTrue(skeletonDiagnostics.getFirst().message().contains("Node.ready"));
        assertTrue(analysisData.skippedSubtreeRoots().containsKey(readyStatement));
    }

    @Test
    void buildAllowsGdccChildToShadowInheritedGdccSignal() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var parentUnit = parserService.parseUnit(Path.of("tmp", "parent_signal.gd"), """
                class_name ParentSignalOwner
                extends RefCounted
                
                signal pinged(value: String)
                """, diagnostics);
        var childUnit = parserService.parseUnit(Path.of("tmp", "child_signal.gd"), """
                class_name ChildSignalOwner
                extends ParentSignalOwner
                
                signal pinged(value: int)
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(parentUnit, childUnit)),
                registry,
                diagnostics,
                analysisData
        );
        var parentClass = findClassByName(topLevelClassDefs(result), "ParentSignalOwner");
        var childClass = findClassByName(topLevelClassDefs(result), "ChildSignalOwner");

        assertTrue(result.diagnostics().isEmpty());
        assertEquals("String", findSignalByName(parentClass, "pinged").getParameter(0).getType().getTypeName());
        assertEquals(GdIntType.INT, findSignalByName(childClass, "pinged").getParameter(0).getType());
    }

    /// A source `static var` must not redeclare any same-named property on the inheritance chain.
    /// The conflict check runs after every class shell is filled, so it must hold even when the
    /// subclass source unit is filled before its base (source order is not inheritance order).
    @Test
    void buildRejectsStaticPropertyConflictingWithInheritedGdccProperty() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        // Deliberately list the child unit first: member filling follows source order, so the
        // conflict check must still see the base class properties afterwards.
        var childUnit = parserService.parseUnit(Path.of("tmp", "static_child.gd"), """
                class_name StaticChild
                extends StaticBase
                
                static var shared: int = 2
                var hp: int = 3
                """, diagnostics);
        var baseUnit = parserService.parseUnit(Path.of("tmp", "static_base.gd"), """
                class_name StaticBase
                extends RefCounted
                
                static var shared: int = 1
                var base_only: float = 1.0
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(childUnit, baseUnit)),
                registry,
                diagnostics,
                analysisData
        );
        var childClass = findClassByName(topLevelClassDefs(result), "StaticChild");
        var baseClass = findClassByName(topLevelClassDefs(result), "StaticBase");
        var sharedStatement = findStatement(
                childUnit.ast().statements(),
                VariableDeclaration.class,
                statement -> statement.name().equals("shared")
        );
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertEquals(1, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.getFirst().message().contains("shared"));
        assertTrue(skeletonDiagnostics.getFirst().message().contains("StaticBase.shared"));
        // The conflicting member is removed while sibling members of both classes survive.
        assertNull(findPropertyByNameOrNull(childClass, "shared"));
        assertNotNull(findPropertyByNameOrNull(childClass, "hp"));
        assertNotNull(findPropertyByNameOrNull(baseClass, "shared"));
        assertNotNull(findPropertyByNameOrNull(baseClass, "base_only"));
        assertTrue(analysisData.skippedSubtreeRoots().containsKey(sharedStatement));
    }

    /// Engine instance properties on the inheritance chain also collide with a source static var.
    @Test
    void buildRejectsStaticPropertyConflictingWithInheritedEngineProperty() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "engine_static_conflict.gd"), """
                class_name EngineStaticConflict
                extends Node2D
                
                static var position: Vector2 = Vector2.ZERO
                var hp: int = 3
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var classDef = findClassByName(topLevelClassDefs(result), "EngineStaticConflict");
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertEquals(1, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.getFirst().message().contains("position"));
        assertTrue(skeletonDiagnostics.getFirst().message().contains("Node2D.position"));
        assertNull(findPropertyByNameOrNull(classDef, "position"));
        assertNotNull(findPropertyByNameOrNull(classDef, "hp"));
    }

    /// Positive controls: distinct static names are fine, inner-class statics are checked too,
    /// and non-static property shadowing keeps its existing (allowed) behavior untouched.
    @Test
    void buildAllowsDistinctStaticPropertiesAndKeepsInstanceShadowingBehavior() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var baseUnit = parserService.parseUnit(Path.of("tmp", "static_ok_base.gd"), """
                class_name StaticOkBase
                extends RefCounted
                
                static var shared: int = 1
                """, diagnostics);
        var childUnit = parserService.parseUnit(Path.of("tmp", "static_ok_child.gd"), """
                class_name StaticOkChild
                extends StaticOkBase
                
                static var child_static: int = 2
                var shared: int = 3
                
                class Inner:
                    static var inner_static: int = 4
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(baseUnit, childUnit)),
                registry,
                diagnostics,
                analysisData
        );
        var childClass = findClassByName(topLevelClassDefs(result), "StaticOkChild");

        assertTrue(result.diagnostics().isEmpty());
        assertNotNull(findPropertyByNameOrNull(childClass, "child_static"));
        // Existing behavior: an instance property may shadow an inherited (static) member.
        assertNotNull(findPropertyByNameOrNull(childClass, "shared"));
        var innerClass = findClassByName(result.allClassDefs(), "StaticOkChild__sub__Inner");
        assertNotNull(findPropertyByNameOrNull(innerClass, "inner_static"));
    }

    /// Inner classes participate in the same static conflict rule through their own hierarchy.
    @Test
    void buildRejectsInnerClassStaticPropertyConflictingWithInheritedMember() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "inner_static_conflict.gd"), """
                class_name InnerStaticConflictOuter
                extends RefCounted
                
                class Base:
                    static var shared: int = 1
                
                class Sub extends Base:
                    static var shared: int = 2
                    var keep_me: int = 3
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var subClass = findClassByName(result.allClassDefs(), "InnerStaticConflictOuter__sub__Sub");
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertEquals(1, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.getFirst().message().contains(
                "InnerStaticConflictOuter__sub__Base.shared"
        ));
        assertNull(findPropertyByNameOrNull(subClass, "shared"));
        assertNotNull(findPropertyByNameOrNull(subClass, "keep_me"));
    }

    @Test
    void buildRejectsEngineSignalShadowThroughGdccParentChain() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var parentUnit = parserService.parseUnit(Path.of("tmp", "node_parent.gd"), """
                class_name NodeParent
                extends Node
                
                signal pinged()
                """, diagnostics);
        var childUnit = parserService.parseUnit(Path.of("tmp", "node_child.gd"), """
                class_name NodeChild
                extends NodeParent
                
                signal ready()
                signal pinged()
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(parentUnit, childUnit)),
                registry,
                diagnostics,
                analysisData
        );
        var childClass = findClassByName(topLevelClassDefs(result), "NodeChild");
        var skeletonDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertNull(findSignalByNameOrNull(childClass, "ready"));
        assertNotNull(findSignalByNameOrNull(childClass, "pinged"));
        assertEquals(1, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.getFirst().message().contains("Node.ready"));
    }

    @Test
    void buildDoesNotSynthesizeParseDiagnosticsForManualUnitsWithoutSharedManagerState() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var analysisData = FrontendAnalysisData.bootstrap();

        var parsed = parserService.parseUnit(Path.of("tmp", "manual_unit.gd"), """
                class_name ManualParseSnapshot
                extends Node
                
                func ping():
                    pass
                """, new DiagnosticManager());
        var manualUnit = new FrontendSourceUnit(
                parsed.path(),
                parsed.source(),
                parsed.ast()
        );
        var diagnostics = new DiagnosticManager();

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(manualUnit)),
                registry,
                diagnostics,
                analysisData
        );

        assertEquals(1, topLevelClassDefs(result).size());
        assertTrue(diagnostics.isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void buildDoesNotPublishRejectedInnerClassShellsIntoRegistry() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "rejected_inner_cycle.gd"), """
                class_name OuterCycle
                extends RefCounted
                
                class Alpha extends Beta:
                    func alpha():
                        pass
                
                class Beta extends Alpha:
                    func beta():
                        pass
                
                class StableInner:
                    func ok():
                        pass
                """, diagnostics);

        var result = classSkeletonBuilder.build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        var relation = result.sourceClassRelations().getFirst();

        assertEquals(List.of("OuterCycle", "OuterCycle__sub__StableInner"), result.allClassDefs().stream()
                .map(LirClassDef::getName)
                .toList());
        assertEquals(List.of("StableInner"), relation.innerClassRelations().stream()
                .map(FrontendInnerClassRelation::sourceName)
                .toList());
        assertNotNull(registry.findGdccClass("OuterCycle"));
        assertNotNull(registry.findGdccClass("OuterCycle__sub__StableInner"));
        assertNull(registry.findGdccClass("OuterCycle__sub__Alpha"));
        assertNull(registry.findGdccClass("OuterCycle__sub__Beta"));
        assertNull(registry.findGdccClassSourceNameOverride("OuterCycle__sub__Alpha"));
        assertNull(registry.findGdccClassSourceNameOverride("OuterCycle__sub__Beta"));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.inheritance_cycle")
                        && diagnostic.message().contains("OuterCycle__sub__Alpha")
                        && diagnostic.message().contains("OuterCycle__sub__Beta")
        ));
    }

    private LirClassDef findClassByName(List<LirClassDef> classDefs, String className) {
        return classDefs.stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private List<FrontendSourceUnit> sourceUnits(FrontendModuleSkeleton result) {
        return result.sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::unit)
                .toList();
    }

    private List<LirClassDef> topLevelClassDefs(FrontendModuleSkeleton result) {
        return result.sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::topLevelClassDef)
                .toList();
    }

    private List<LirClassDef> innerClassDefs(FrontendSourceClassRelation relation) {
        return relation.innerClassRelations().stream()
                .map(FrontendInnerClassRelation::classDef)
                .toList();
    }

    private PropertyDef findPropertyByName(LirClassDef classDef, String propertyName) {
        return classDef.getProperties().stream()
                .filter(propertyDef -> propertyDef.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + propertyName));
    }

    private PropertyDef findPropertyByNameOrNull(LirClassDef classDef, String propertyName) {
        return classDef.getProperties().stream()
                .filter(propertyDef -> propertyDef.getName().equals(propertyName))
                .findFirst()
                .orElse(null);
    }

    private SignalDef findSignalByName(LirClassDef classDef, String signalName) {
        return classDef.getSignals().stream()
                .filter(signalDef -> signalDef.getName().equals(signalName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Signal not found: " + signalName));
    }

    private SignalDef findSignalByNameOrNull(LirClassDef classDef, String signalName) {
        return classDef.getSignals().stream()
                .filter(signalDef -> signalDef.getName().equals(signalName))
                .findFirst()
                .orElse(null);
    }

    private LirFunctionDef findFunctionByName(LirClassDef classDef, String functionName) {
        return classDef.getFunctions().stream()
                .filter(functionDef -> functionDef.getName().equals(functionName))
                .findFirst()
                .map(functionDef -> assertInstanceOf(LirFunctionDef.class, functionDef))
                .orElseThrow(() -> new AssertionError("Function not found: " + functionName));
    }

    private LirFunctionDef findFunctionByNameOrNull(LirClassDef classDef, String functionName) {
        return classDef.getFunctions().stream()
                .filter(functionDef -> functionDef.getName().equals(functionName))
                .findFirst()
                .map(functionDef -> assertInstanceOf(LirFunctionDef.class, functionDef))
                .orElse(null);
    }

    private void assertObjectTypeName(GdType type, String expectedTypeName) {
        var objectType = assertInstanceOf(GdObjectType.class, type);
        assertEquals(new GdObjectType(expectedTypeName), objectType);
    }

    private <T extends Statement> T findStatement(
            List<Statement> statements,
            Class<T> statementType,
            Predicate<T> predicate
    ) {
        return statements.stream()
                .filter(statementType::isInstance)
                .map(statementType::cast)
                .filter(predicate)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Statement not found: " + statementType.getSimpleName()));
    }
}
