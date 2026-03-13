package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.scope.SignalDef;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.Statement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class FrontendClassSkeletonTest {
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

        var result = classSkeletonBuilder.build("test_module", units, registry, diagnostics, analysisData);
        assertEquals(3, result.sourceClassRelations().size());
        assertEquals(3, result.classDefs().size());
        assertEquals(3, result.allClassDefs().size());
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertTrue(result.diagnostics().isEmpty());

        var childClass = findClassByName(result.classDefs(), "ChildClass");
        assertEquals("BaseClass", childClass.getSuperName());
        assertEquals(1, childClass.getProperties().size());
        assertEquals("hp", childClass.getProperties().getFirst().getName());
        assertEquals(1, childClass.getFunctions().size());
        assertEquals("_ready", childClass.getFunctions().getFirst().getName());

        var baseClass = findClassByName(result.classDefs(), "BaseClass");
        assertEquals("RefCounted", baseClass.getSuperName());
        assertEquals(1, baseClass.getSignals().size());
        var changedSignal = baseClass.getSignals().getFirst();
        assertEquals("changed", changedSignal.getName());
        assertEquals(1, changedSignal.getParameterCount());
        assertEquals("value", changedSignal.getParameter(0).getName());
        assertEquals(GdIntType.INT, changedSignal.getParameter(0).getType());

        var derivedNameClass = findClassByName(result.classDefs(), "NoNameScript");
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

        var result = classSkeletonBuilder.build("test_module", List.of(unit), registry, diagnostics, analysisData);
        var relation = result.sourceClassRelations().getFirst();

        assertEquals(1, result.sourceClassRelations().size());
        assertSame(unit, relation.unit());
        assertEquals("OuterWithInner", relation.name());
        assertEquals("OuterWithInner", relation.topLevelClassDef().getName());
        assertEquals(List.of("InnerA", "Deep", "InnerB"), relation.innerClassRelations().stream()
                .map(FrontendInnerClassRelation::sourceName)
                .toList());
        assertEquals(List.of(
                "OuterWithInner$InnerA",
                "OuterWithInner$InnerA$Deep",
                "OuterWithInner$InnerB"
        ), relation.innerClassRelations().stream()
                .map(FrontendInnerClassRelation::canonicalName)
                .toList());
        assertEquals(List.of(
                "OuterWithInner$InnerA",
                "OuterWithInner$InnerA$Deep",
                "OuterWithInner$InnerB"
        ), relation.innerClassDefs().stream().map(LirClassDef::getName).toList());
        assertEquals(List.of("OuterWithInner"), result.classDefs().stream().map(LirClassDef::getName).toList());
        assertEquals(List.of(
                "OuterWithInner",
                "OuterWithInner$InnerA",
                "OuterWithInner$InnerA$Deep",
                "OuterWithInner$InnerB"
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
        assertEquals("OuterWithInner$InnerA$Deep", deepOwned.canonicalName());
        assertNull(relation.findRelation(unit.ast().statements().getFirst()));

        assertEquals(List.of("InnerA", "InnerB"), relation.findImmediateInnerRelations(unit.ast()).stream()
                .map(FrontendInnerClassRelation::sourceName)
                .toList());
        assertEquals(List.of("Deep"), relation.findImmediateInnerRelations(innerADeclaration).stream()
                .map(FrontendInnerClassRelation::sourceName)
                .toList());
        assertTrue(relation.findImmediateInnerRelations(innerBDeclaration).isEmpty());

        var innerA = findClassByName(relation.innerClassDefs(), "OuterWithInner$InnerA");
        assertEquals("RefCounted", innerA.getSuperName());
        assertEquals("hp", innerA.getProperties().getFirst().getName());
        assertEquals("ping", innerA.getFunctions().getFirst().getName());

        var innerB = findClassByName(relation.innerClassDefs(), "OuterWithInner$InnerB");
        assertEquals(1, innerB.getSignals().size());
        assertEquals("changed", innerB.getSignals().getFirst().getName());

        var deep = findClassByName(relation.innerClassDefs(), "OuterWithInner$InnerA$Deep");
        assertEquals("RefCounted", deep.getSuperName());
        assertEquals("nested", deep.getFunctions().getFirst().getName());

        assertNotNull(registry.findGdccClass("OuterWithInner"));
        assertNotNull(registry.findGdccClass("OuterWithInner$InnerA"));
        assertNotNull(registry.findGdccClass("OuterWithInner$InnerA$Deep"));
        assertNotNull(registry.findGdccClass("OuterWithInner$InnerB"));
        assertNull(registry.findGdccClassSourceNameOverride("OuterWithInner"));
        assertEquals("InnerA", registry.findGdccClassSourceNameOverride("OuterWithInner$InnerA"));
        assertEquals("Deep", registry.findGdccClassSourceNameOverride("OuterWithInner$InnerA$Deep"));
        assertEquals("InnerB", registry.findGdccClassSourceNameOverride("OuterWithInner$InnerB"));

        var innerAMeta = registry.resolveTypeMeta("OuterWithInner$InnerA");
        assertNotNull(innerAMeta);
        assertEquals("OuterWithInner$InnerA", innerAMeta.canonicalName());
        assertEquals("InnerA", innerAMeta.sourceName());
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
                "test_module",
                List.of(outerUnit, moduleItemUnit),
                registry,
                diagnostics,
                analysisData
        );
        var outer = findClassByName(result.classDefs(), "OuterTypes");
        var directInner = findClassByName(result.allClassDefs(), "OuterTypes$DirectInner");
        var deep = findClassByName(result.allClassDefs(), "OuterTypes$DirectInner$Deep");

        assertObjectTypeName(findPropertyByName(outer, "direct_inner").getType(), "OuterTypes$DirectInner");
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
        assertObjectTypeName(wrapFunction.getParameter(0).getType(), "OuterTypes$DirectInner");
        assertObjectTypeName(wrapFunction.getReturnType(), "ModuleItem");

        assertObjectTypeName(findPropertyByName(directInner, "self_ref").getType(), "OuterTypes$DirectInner");
        assertObjectTypeName(findPropertyByName(directInner, "outer_ref").getType(), "OuterTypes");
        assertObjectTypeName(findPropertyByName(directInner, "module_ref").getType(), "ModuleItem");
        assertObjectTypeName(findPropertyByName(directInner, "helper_ref").getType(), "OuterTypes$Helper");
        var helpersType = assertInstanceOf(
                GdArrayType.class,
                findPropertyByName(directInner, "helpers").getType()
        );
        assertObjectTypeName(helpersType.getValueType(), "OuterTypes$Helper");

        assertObjectTypeName(findPropertyByName(deep, "self_ref").getType(), "OuterTypes$DirectInner$Deep");
        assertObjectTypeName(findPropertyByName(deep, "parent_ref").getType(), "OuterTypes$DirectInner");
        assertObjectTypeName(findPropertyByName(deep, "outer_ref").getType(), "OuterTypes");
        assertObjectTypeName(findPropertyByName(deep, "helper_ref").getType(), "OuterTypes$Helper");

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
                "test_module",
                List.of(outerUnit, consumerUnit),
                registry,
                diagnostics,
                analysisData
        );
        var consumer = findClassByName(result.classDefs(), "ExternalConsumer");

        assertEquals(GdVariantType.VARIANT, findPropertyByName(consumer, "leaked").getType());
        assertObjectTypeName(findPropertyByName(consumer, "allowed").getType(), "OuterVisibility");
        assertNotNull(registry.findGdccClass("OuterVisibility$HiddenInner"));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.type_resolution")
                        && diagnostic.message().contains("HiddenInner")
                        && diagnostic.sourcePath() != null
                        && diagnostic.sourcePath().endsWith("external_consumer.gd")
        ));
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

        var result = classSkeletonBuilder.build("test_module", List.of(unit), registry, diagnostics, analysisData);
        var unknownTypeSurface = findClassByName(result.classDefs(), "UnknownTypeSurface");
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

        var result = classSkeletonBuilder.build("test_module", List.of(unit), registry, diagnostics, analysisData);
        var topLevel = findClassByName(result.classDefs(), "ConstructorMembers");
        var inner = findClassByName(result.allClassDefs(), "ConstructorMembers$Inner");

        var topLevelInit = findFunctionByName(topLevel, "_init");
        assertEquals(GdVoidType.VOID, topLevelInit.getReturnType());
        assertEquals(2, topLevelInit.getParameterCount());
        assertEquals("seed", topLevelInit.getParameter(0).getName());
        assertEquals(GdIntType.INT, topLevelInit.getParameter(0).getType());
        assertEquals("alias", topLevelInit.getParameter(1).getName());
        assertEquals(GdVariantType.VARIANT, topLevelInit.getParameter(1).getType());

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

        var result = classSkeletonBuilder.build("test_module", List.of(unit), registry, diagnostics, analysisData);
        var topLevel = findClassByName(result.classDefs(), "DuplicateInitConstructor");
        var inner = findClassByName(result.allClassDefs(), "DuplicateInitConstructor$Inner");

        assertEquals(List.of("_init", "ping"), topLevel.getFunctions().stream()
                .map(function -> function.getName())
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
    void phase3PublishesAcceptedTopLevelAndInnerShellsBeforeMemberFilling() throws Exception {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var builder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var unit = parserService.parseUnit(Path.of("tmp", "phase3_probe.gd"), """
                class_name Phase3Probe
                extends Node
                
                signal changed()
                
                func _init(seed: int):
                    pass
                
                class Inner:
                    var hp: int = 1
                    func _init():
                        pass
                    func ping():
                        pass
                """, diagnostics);

        var discovery = invokeBuilderMethod(
                builder,
                "discoverModuleClassHeaders",
                new Class<?>[]{List.class, DiagnosticManager.class},
                List.of(unit),
                diagnostics
        );
        @SuppressWarnings("unchecked")
        var sourceUnitGraphs = (List<Object>) invokeAccessor(discovery, "sourceUnitGraphs");
        var shellContext = newSkeletonBuildContext(registry, diagnostics, unit.path(), analysisData);
        var shellRelations = new ArrayList<FrontendSourceClassRelation>();
        for (var sourceUnitGraph : sourceUnitGraphs) {
            if (invokeAccessor(sourceUnitGraph, "topLevelHeader") == null) {
                continue;
            }
            shellRelations.add((FrontendSourceClassRelation) invokeBuilderMethod(
                    builder,
                    "buildSourceClassRelationShell",
                    new Class<?>[]{sourceUnitGraph.getClass(), shellContext.getClass()},
                    sourceUnitGraph,
                    shellContext
            ));
        }

        assertEquals(1, shellRelations.size());
        var shellRelation = shellRelations.getFirst();
        assertTrue(shellRelation.topLevelClassDef().getSignals().isEmpty());
        assertTrue(shellRelation.topLevelClassDef().getProperties().isEmpty());
        assertTrue(shellRelation.topLevelClassDef().getFunctions().isEmpty());
        assertEquals(1, shellRelation.innerClassRelations().size());
        var innerShell = shellRelation.innerClassRelations().getFirst().classDef();
        assertTrue(innerShell.getSignals().isEmpty());
        assertTrue(innerShell.getProperties().isEmpty());
        assertTrue(innerShell.getFunctions().isEmpty());

        invokeBuilderMethod(
                builder,
                "publishClassShells",
                new Class<?>[]{List.class, ClassRegistry.class},
                shellRelations,
                registry
        );
        assertNotNull(registry.findGdccClass("Phase3Probe"));
        assertNotNull(registry.findGdccClass("Phase3Probe$Inner"));
        assertNull(registry.findGdccClassSourceNameOverride("Phase3Probe"));
        assertEquals("Inner", registry.findGdccClassSourceNameOverride("Phase3Probe$Inner"));

        var innerTypeMeta = registry.resolveTypeMeta("Phase3Probe$Inner");
        assertNotNull(innerTypeMeta);
        assertEquals("Phase3Probe$Inner", innerTypeMeta.canonicalName());
        assertEquals("Inner", innerTypeMeta.sourceName());

        invokeBuilderMethod(
                builder,
                "fillSourceClassRelationMembers",
                new Class<?>[]{FrontendSourceClassRelation.class, shellContext.getClass()},
                shellRelation,
                shellContext
        );
        assertEquals("changed", shellRelation.topLevelClassDef().getSignals().getFirst().getName());
        assertEquals("_init", shellRelation.topLevelClassDef().getFunctions().getFirst().getName());
        assertEquals("hp", innerShell.getProperties().getFirst().getName());
        assertEquals(List.of("_init", "ping"), innerShell.getFunctions().stream()
                .map(function -> function.getName())
                .toList());
    }

    /// Verifies the shared parse->skeleton pipeline keeps the original parse diagnostics exactly
    /// once. Parse diagnostics live only in the shared manager, so the builder must not invent
    /// an extra diagnostic source or duplicate what parse already published earlier.
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

        var result = classSkeletonBuilder.build("test_module", List.of(unit), registry, diagnostics, analysisData);
        var classDef = findClassByName(result.classDefs(), "BrokenSharedPipeline");

        assertEquals("BrokenSharedPipeline", classDef.getName());
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals(parseSnapshot, result.diagnostics());
        assertEquals(parseSnapshot.size(), result.diagnostics().size());
        assertTrue(result.diagnostics().asList().stream().allMatch(diagnostic -> diagnostic.category().equals("parse.lowering")));
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
                "duplicate_module",
                List.of(original, duplicate, unique),
                registry,
                diagnostics,
                analysisData
        );

        assertEquals(List.of("SharedName", "UniqueName"), result.classDefs().stream().map(LirClassDef::getName).toList());
        assertEquals(2, result.sourceClassRelations().size());
        assertEquals("from_first", findClassByName(result.classDefs(), "SharedName").getFunctions().getFirst().getName());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("Duplicate class name 'SharedName'")
                        && diagnostic.message().contains("second_shared.gd")
        ));

        assertNotNull(registry.findGdccClass("SharedName"));
        assertNotNull(registry.findGdccClass("UniqueName"));
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

        var result = classSkeletonBuilder.build("test_module", List.of(manualUnit), registry, diagnostics, analysisData);

        assertEquals(1, result.classDefs().size());
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

        var result = classSkeletonBuilder.build("test_module", List.of(unit), registry, diagnostics, analysisData);
        var relation = result.sourceClassRelations().getFirst();

        assertEquals(List.of("OuterCycle", "OuterCycle$StableInner"), result.allClassDefs().stream()
                .map(LirClassDef::getName)
                .toList());
        assertEquals(List.of("StableInner"), relation.innerClassRelations().stream()
                .map(FrontendInnerClassRelation::sourceName)
                .toList());
        assertNotNull(registry.findGdccClass("OuterCycle"));
        assertNotNull(registry.findGdccClass("OuterCycle$StableInner"));
        assertNull(registry.findGdccClass("OuterCycle$Alpha"));
        assertNull(registry.findGdccClass("OuterCycle$Beta"));
        assertNull(registry.findGdccClassSourceNameOverride("OuterCycle$Alpha"));
        assertNull(registry.findGdccClassSourceNameOverride("OuterCycle$Beta"));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.inheritance_cycle")
                        && diagnostic.message().contains("OuterCycle$Alpha")
                        && diagnostic.message().contains("OuterCycle$Beta")
        ));
    }

    private LirClassDef findClassByName(List<LirClassDef> classDefs, String className) {
        return classDefs.stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private PropertyDef findPropertyByName(LirClassDef classDef, String propertyName) {
        return classDef.getProperties().stream()
                .filter(propertyDef -> propertyDef.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + propertyName));
    }

    private SignalDef findSignalByName(LirClassDef classDef, String signalName) {
        return classDef.getSignals().stream()
                .filter(signalDef -> signalDef.getName().equals(signalName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Signal not found: " + signalName));
    }

    private LirFunctionDef findFunctionByName(LirClassDef classDef, String functionName) {
        return classDef.getFunctions().stream()
                .filter(functionDef -> functionDef.getName().equals(functionName))
                .findFirst()
                .map(functionDef -> assertInstanceOf(LirFunctionDef.class, functionDef))
                .orElseThrow(() -> new AssertionError("Function not found: " + functionName));
    }

    private void assertObjectTypeName(GdType type, String expectedTypeName) {
        var objectType = assertInstanceOf(GdObjectType.class, type);
        assertEquals(new GdObjectType(expectedTypeName), objectType);
    }

    private Object newSkeletonBuildContext(
            ClassRegistry classRegistry,
            DiagnosticManager diagnostics,
            Path sourcePath,
            FrontendAnalysisData analysisData
    ) throws Exception {
        var contextClass = Class.forName(
                "dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder$SkeletonBuildContext"
        );
        var constructor = contextClass.getDeclaredConstructor(
                ClassRegistry.class,
                DiagnosticManager.class,
                Path.class,
                FrontendAnalysisData.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(classRegistry, diagnostics, sourcePath, analysisData);
    }

    private Object invokeBuilderMethod(
            FrontendClassSkeletonBuilder builder,
            String methodName,
            Class<?>[] parameterTypes,
            Object... args
    ) throws Exception {
        var method = FrontendClassSkeletonBuilder.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(builder, args);
    }

    private Object invokeAccessor(Object target, String methodName) throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
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
