package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionHeader;
import dev.superice.gdcc.gdextension.ExtensionSingleton;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendClassHeaderDiscoveryTest {
    private static final Range SYNTHETIC_RANGE = new Range(
            0,
            1,
            new Point(0, 0),
            new Point(0, 1)
    );

    @Test
    void buildRejectsDuplicateInnerClassSubtreeButKeepsSiblingInnerClassAndOtherSources() throws IOException {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var duplicateInnerUnit = parserService.parseUnit(
                Path.of("tmp", "outer_duplicate_inner.gd"),
                """
                        class_name Outer
                        extends RefCounted
                        
                        class GoodInner:
                            func ok():
                                pass
                        
                        class SharedInner:
                            func first():
                                pass
                        
                        class SharedInner:
                            func second():
                                pass
                        
                            class HiddenChild:
                                func hidden():
                                    pass
                        """,
                diagnostics
        );
        var stableUnit = parserService.parseUnit(
                Path.of("tmp", "stable_module.gd"),
                """
                        class_name StableModule
                        extends RefCounted
                        """,
                diagnostics
        );

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = buildSkeleton("header_discovery", List.of(duplicateInnerUnit, stableUnit), registry, diagnostics);

        var outerRelation = findSourceRelation(result, "Outer");
        assertEquals(
                List.of("Outer", "StableModule"),
                topLevelClassDefs(result).stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of(
                        "Outer",
                        "Outer$GoodInner",
                        "Outer$SharedInner",
                        "StableModule"
                ),
                result.allClassDefs().stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of("GoodInner", "SharedInner"),
                outerRelation.innerClassRelations().stream()
                        .map(FrontendInnerClassRelation::sourceName)
                        .toList()
        );
        assertFalse(
                outerRelation.innerClassRelations().stream()
                        .map(FrontendInnerClassRelation::canonicalName)
                        .anyMatch(name -> name.contains("HiddenChild"))
        );
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("Duplicate inner class name 'SharedInner'")
                        && diagnostic.message().contains("Outer")
        ));
        assertNotNull(registry.findGdccClass("Outer"));
        assertNotNull(registry.findGdccClass("StableModule"));
        assertNotNull(registry.findGdccClass("Outer$GoodInner"));
        assertNotNull(registry.findGdccClass("Outer$SharedInner"));
        assertEquals("GoodInner", registry.findGdccClassSourceNameOverride("Outer$GoodInner"));
        assertEquals("SharedInner", registry.findGdccClassSourceNameOverride("Outer$SharedInner"));
        assertNull(registry.findGdccClass("Outer$SharedInner$HiddenChild"));
    }

    @Test
    void buildRejectsCanonicalNameConflictBetweenInnerClassAndTopLevelClass() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var outerUnit = parserService.parseUnit(
                Path.of("tmp", "outer_canonical_conflict.gd"),
                """
                        class_name Outer
                        extends RefCounted
                        
                        class Inner:
                            func ok():
                                pass
                        
                        class Sibling:
                            pass
                        """,
                diagnostics
        );
        var conflictingTopLevelUnit = new FrontendSourceUnit(
                Path.of("tmp", "outer_inner_top_level.gd"),
                "",
                new SourceFile(
                        List.of(new ClassNameStatement("Outer$Inner", null, SYNTHETIC_RANGE)),
                        SYNTHETIC_RANGE
                )
        );
        var stableUnit = parserService.parseUnit(
                Path.of("tmp", "keep_alive.gd"),
                """
                        class_name KeepAlive
                        extends RefCounted
                        """,
                diagnostics
        );

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = buildSkeleton(
                "header_discovery",
                List.of(outerUnit, conflictingTopLevelUnit, stableUnit),
                registry,
                diagnostics
        );

        var outerRelation = findSourceRelation(result, "Outer");
        assertEquals(
                List.of("Outer", "KeepAlive"),
                topLevelClassDefs(result).stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of(
                        "Outer",
                        "Outer$Inner",
                        "Outer$Sibling",
                        "KeepAlive"
                ),
                result.allClassDefs().stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of("Inner", "Sibling"),
                outerRelation.innerClassRelations().stream()
                        .map(FrontendInnerClassRelation::sourceName)
                        .toList()
        );
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("Canonical class name conflict 'Outer$Inner'")
        ));
        assertNotNull(registry.findGdccClass("Outer"));
        assertNotNull(registry.findGdccClass("KeepAlive"));
        assertNotNull(registry.findGdccClass("Outer$Inner"));
        assertEquals("Inner", registry.findGdccClassSourceNameOverride("Outer$Inner"));
        assertEquals("Sibling", registry.findGdccClassSourceNameOverride("Outer$Sibling"));
    }

    @Test
    void buildRejectsInnerInheritanceCycleAndDependentsWhileKeepingStableSiblings() throws IOException {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var outerUnit = parserService.parseUnit(
                Path.of("tmp", "outer_inner_cycle.gd"),
                """
                        class_name OuterCycle
                        extends RefCounted
                        
                        class Alpha extends Beta:
                            func alpha():
                                pass
                        
                        class Beta extends Alpha:
                            func beta():
                                pass
                        
                        class Gamma extends Alpha:
                            func gamma():
                                pass
                        
                        class StableInner:
                            func ok():
                                pass
                        """,
                diagnostics
        );
        var stableUnit = parserService.parseUnit(
                Path.of("tmp", "stable_root.gd"),
                """
                        class_name StableRoot
                        extends RefCounted
                        """,
                diagnostics
        );

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = buildSkeleton("header_discovery", List.of(outerUnit, stableUnit), registry, diagnostics);

        var outerRelation = findSourceRelation(result, "OuterCycle");
        assertEquals(
                List.of("OuterCycle", "StableRoot"),
                topLevelClassDefs(result).stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of("StableInner"),
                outerRelation.innerClassRelations().stream()
                        .map(FrontendInnerClassRelation::sourceName)
                        .toList()
        );
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.inheritance_cycle")
                        && diagnostic.message().contains("OuterCycle$Alpha")
                        && diagnostic.message().contains("OuterCycle$Beta")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("OuterCycle$Gamma")
                        && diagnostic.message().contains("OuterCycle$Alpha")
        ));
        assertNotNull(registry.findGdccClass("OuterCycle"));
        assertNotNull(registry.findGdccClass("StableRoot"));
    }

    @Test
    void buildRejectsCanonicalInnerSuperclassSpellingAndKeepsSourceFacingSiblings() throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new FrontendSourceUnit(
                Path.of("tmp", "canonical_super_boundary.gd"),
                "",
                new SourceFile(
                        List.of(
                                new ClassNameStatement("CanonicalSuperBoundary", "RefCounted", SYNTHETIC_RANGE),
                                new ClassDeclaration(
                                        "Shared",
                                        null,
                                        new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE),
                                        SYNTHETIC_RANGE
                                ),
                                new ClassDeclaration(
                                        "Leaf",
                                        "CanonicalSuperBoundary$Shared",
                                        new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE),
                                        SYNTHETIC_RANGE
                                ),
                                new ClassDeclaration(
                                        "Stable",
                                        null,
                                        new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE),
                                        SYNTHETIC_RANGE
                                )
                        ),
                        SYNTHETIC_RANGE
                )
        );

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = buildSkeleton("header_discovery", List.of(unit), registry, diagnostics);

        var relation = findSourceRelation(result, "CanonicalSuperBoundary");
        assertEquals(
                List.of(
                        "CanonicalSuperBoundary",
                        "CanonicalSuperBoundary$Shared",
                        "CanonicalSuperBoundary$Stable"
                ),
                result.allClassDefs().stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of("Shared", "Stable"),
                relation.innerClassRelations().stream()
                        .map(FrontendInnerClassRelation::sourceName)
                        .toList()
        );
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("CanonicalSuperBoundary$Leaf")
                        && diagnostic.message().contains("canonical '$' spelling")
                        && diagnostic.message().contains("Shared")
        ));
        assertNotNull(registry.findGdccClass("CanonicalSuperBoundary$Shared"));
        assertNotNull(registry.findGdccClass("CanonicalSuperBoundary$Stable"));
        assertNull(registry.findGdccClass("CanonicalSuperBoundary$Leaf"));
    }

    @Test
    void buildRejectsPathBasedSuperclassSourceAtHeaderBoundary() throws Exception {
        var pathBasedUnit = new FrontendSourceUnit(
                Path.of("tmp", "path_based_super.gd"),
                "",
                new SourceFile(
                        List.of(new ClassNameStatement(
                                "PathBasedChild",
                                "preload(\"res://base.gd\")",
                                SYNTHETIC_RANGE
                        )),
                        SYNTHETIC_RANGE
                )
        );
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var stableUnit = parserService.parseUnit(
                Path.of("tmp", "keep_alive.gd"),
                """
                        class_name KeepAlive
                        extends RefCounted
                        """,
                diagnostics
        );

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = buildSkeleton("header_discovery", List.of(pathBasedUnit, stableUnit), registry, diagnostics);

        assertEquals(List.of("KeepAlive"), topLevelClassDefs(result).stream().map(LirClassDef::getName).toList());
        assertEquals(List.of("KeepAlive"), result.sourceClassRelations().stream().map(FrontendSourceClassRelation::sourceName).toList());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("PathBasedChild")
                        && diagnostic.message().contains("path-based extends targets")
        ));
        assertNull(registry.findGdccClass("PathBasedChild"));
        assertNotNull(registry.findGdccClass("KeepAlive"));
    }

    @Test
    void buildRejectsExternalGdccSuperclassSource() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(
                Path.of("tmp", "uses_external_base.gd"),
                """
                        class_name UsesExternalBase
                        extends ExternalBase
                        """,
                diagnostics
        );
        var stableUnit = parserService.parseUnit(
                Path.of("tmp", "local_keep_alive.gd"),
                """
                        class_name LocalKeepAlive
                        extends RefCounted
                        """,
                diagnostics
        );

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef("ExternalBase", "RefCounted"));
        var result = buildSkeleton("header_discovery", List.of(unit, stableUnit), registry, diagnostics);

        assertEquals(List.of("LocalKeepAlive"), topLevelClassDefs(result).stream().map(LirClassDef::getName).toList());
        assertEquals(List.of("LocalKeepAlive"), result.sourceClassRelations().stream().map(FrontendSourceClassRelation::sourceName).toList());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("UsesExternalBase")
                        && diagnostic.message().contains("gdcc superclass source")
                        && diagnostic.message().contains("current module")
        ));
        assertNotNull(registry.findGdccClass("ExternalBase"));
        assertNull(registry.findGdccClass("UsesExternalBase"));
        assertNotNull(registry.findGdccClass("LocalKeepAlive"));
    }

    @Test
    void buildRejectsSingletonSuperclassSourceAtHeaderBoundary() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var singletonUnit = parserService.parseUnit(
                Path.of("tmp", "singleton_super.gd"),
                """
                        class_name SingletonSuperUser
                        extends GameSingleton
                        """,
                diagnostics
        );
        var stableUnit = parserService.parseUnit(
                Path.of("tmp", "keep_alive.gd"),
                """
                        class_name KeepAlive
                        extends RefCounted
                        """,
                diagnostics
        );

        var registry = createRegistryWithSingleton("GameSingleton");
        var result = buildSkeleton("header_discovery", List.of(singletonUnit, stableUnit), registry, diagnostics);

        assertEquals(List.of("KeepAlive"), topLevelClassDefs(result).stream().map(LirClassDef::getName).toList());
        assertEquals(List.of("KeepAlive"), result.sourceClassRelations().stream().map(FrontendSourceClassRelation::sourceName).toList());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("SingletonSuperUser")
                        && diagnostic.message().contains("autoload/singleton superclasses")
        ));
        assertNull(registry.findGdccClass("SingletonSuperUser"));
        assertNotNull(registry.findGdccClass("KeepAlive"));
    }

    @Test
    void buildRejectsInnerClassWithMissingNameAndKeepsValidSiblingInnerClass() throws Exception {
        var missingNameInner = new ClassDeclaration(
                "",
                null,
                new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE),
                SYNTHETIC_RANGE
        );
        var goodInner = new ClassDeclaration(
                "GoodInner",
                null,
                new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE),
                SYNTHETIC_RANGE
        );
        var sourceFile = new SourceFile(
                List.of(
                        new ClassNameStatement("ManualMissingInner", null, SYNTHETIC_RANGE),
                        missingNameInner,
                        goodInner
                ),
                SYNTHETIC_RANGE
        );
        var unit = new FrontendSourceUnit(
                Path.of("tmp", "manual_missing_inner.gd"),
                "",
                sourceFile
        );

        var diagnostics = new DiagnosticManager();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = buildSkeleton("header_discovery", List.of(unit), registry, diagnostics);

        var relation = findSourceRelation(result, "ManualMissingInner");
        assertEquals(
                List.of("GoodInner"),
                relation.innerClassRelations().stream()
                        .map(FrontendInnerClassRelation::sourceName)
                        .toList()
        );
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("missing a class name")
        ));
        assertNotNull(registry.findGdccClass("ManualMissingInner"));
    }

    @Test
    void buildRejectsMappedTopLevelCanonicalConflictWhileKeepingStableSources() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var alphaUnit = parserService.parseUnit(
                Path.of("tmp", "alpha_source.gd"),
                """
                        class_name AlphaSource
                        extends RefCounted
                        """,
                diagnostics
        );
        var betaUnit = parserService.parseUnit(
                Path.of("tmp", "beta_source.gd"),
                """
                        class_name BetaSource
                        extends RefCounted
                        """,
                diagnostics
        );
        var keepAliveUnit = parserService.parseUnit(
                Path.of("tmp", "keep_alive.gd"),
                """
                        class_name KeepAlive
                        extends RefCounted
                        """,
                diagnostics
        );

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = buildSkeleton(
                "header_discovery",
                List.of(alphaUnit, betaUnit, keepAliveUnit),
                Map.of(
                        "AlphaSource", "SharedRuntime",
                        "BetaSource", "SharedRuntime"
                ),
                registry,
                diagnostics
        );

        assertEquals(
                List.of("SharedRuntime", "KeepAlive"),
                topLevelClassDefs(result).stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of("AlphaSource", "KeepAlive"),
                result.sourceClassRelations().stream().map(FrontendSourceClassRelation::sourceName).toList()
        );
        assertEquals(
                List.of("SharedRuntime", "KeepAlive"),
                result.sourceClassRelations().stream().map(FrontendSourceClassRelation::canonicalName).toList()
        );
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("Canonical class name conflict 'SharedRuntime'")
                        && diagnostic.message().contains("AlphaSource")
                        && diagnostic.message().contains("BetaSource")
        ));
        assertNotNull(registry.findGdccClass("SharedRuntime"));
        assertNotNull(registry.findGdccClass("KeepAlive"));
        assertNull(registry.findGdccClass("BetaSource"));
    }

    private FrontendSourceClassRelation findSourceRelation(
            FrontendModuleSkeleton result,
            String className
    ) {
        return result.sourceClassRelations().stream()
                .filter(relation -> relation.sourceName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Source relation not found: " + className));
    }

    private List<LirClassDef> topLevelClassDefs(FrontendModuleSkeleton result) {
        return result.sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::topLevelClassDef)
                .toList();
    }

    private FrontendModuleSkeleton buildSkeleton(
            String moduleName,
            List<FrontendSourceUnit> units,
            ClassRegistry registry,
            DiagnosticManager diagnostics
    ) {
        return buildSkeleton(moduleName, units, Map.of(), registry, diagnostics);
    }

    private FrontendModuleSkeleton buildSkeleton(
            String moduleName,
            List<FrontendSourceUnit> units,
            Map<String, String> topLevelRuntimeNameMap,
            ClassRegistry registry,
            DiagnosticManager diagnostics
    ) {
        return new FrontendClassSkeletonBuilder().build(
                new FrontendModule(moduleName, units, topLevelRuntimeNameMap),
                registry,
                diagnostics,
                FrontendAnalysisData.bootstrap()
        );
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
}
