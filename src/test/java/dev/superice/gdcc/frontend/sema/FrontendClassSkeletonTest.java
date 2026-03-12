package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.Statement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
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
        assertEquals("OuterWithInner", relation.topLevelClassDef().getName());
        assertEquals(List.of("InnerA", "Deep", "InnerB"), relation.innerClassRelations().stream()
                .map(innerClassRelation -> innerClassRelation.declaration().name())
                .toList());
        assertEquals(List.of("InnerA", "Deep", "InnerB"), relation.innerClassRelations().stream()
                .map(innerClassRelation -> innerClassRelation.classDef().getName())
                .toList());
        assertEquals(List.of("InnerA", "Deep", "InnerB"), relation.innerClassDefs().stream().map(LirClassDef::getName).toList());
        assertEquals(List.of("OuterWithInner"), result.classDefs().stream().map(LirClassDef::getName).toList());
        assertEquals(List.of("OuterWithInner", "InnerA", "Deep", "InnerB"), result.allClassDefs().stream().map(LirClassDef::getName).toList());

        var innerADeclaration = findStatement(unit.ast().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("InnerA"));
        var deepDeclaration = findStatement(innerADeclaration.body().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Deep"));
        var innerBDeclaration = findStatement(unit.ast().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("InnerB"));
        assertSame(innerADeclaration, relation.innerClassRelations().get(0).declaration());
        assertSame(deepDeclaration, relation.innerClassRelations().get(1).declaration());
        assertSame(innerBDeclaration, relation.innerClassRelations().get(2).declaration());

        var innerA = findClassByName(relation.innerClassDefs(), "InnerA");
        assertEquals("RefCounted", innerA.getSuperName());
        assertEquals("hp", innerA.getProperties().getFirst().getName());
        assertEquals("ping", innerA.getFunctions().getFirst().getName());

        var innerB = findClassByName(relation.innerClassDefs(), "InnerB");
        assertEquals(1, innerB.getSignals().size());
        assertEquals("changed", innerB.getSignals().getFirst().getName());

        var deep = findClassByName(relation.innerClassDefs(), "Deep");
        assertEquals("RefCounted", deep.getSuperName());
        assertEquals("nested", deep.getFunctions().getFirst().getName());

        assertNotNull(registry.findGdccClass("OuterWithInner"));
        assertNull(registry.findGdccClass("InnerA"));
        assertNull(registry.findGdccClass("Deep"));
        assertNull(registry.findGdccClass("InnerB"));
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

    private LirClassDef findClassByName(List<LirClassDef> classDefs, String className) {
        return classDefs.stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
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
