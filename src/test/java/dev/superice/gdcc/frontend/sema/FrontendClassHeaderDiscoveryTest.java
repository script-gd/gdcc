package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
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
        var result = new FrontendClassSkeletonBuilder().build(
                "header_discovery",
                List.of(duplicateInnerUnit, stableUnit),
                registry,
                diagnostics,
                FrontendAnalysisData.bootstrap()
        );

        var outerRelation = findSourceRelation(result, "Outer");
        assertEquals(
                List.of("Outer", "StableModule"),
                result.classDefs().stream().map(LirClassDef::getName).toList()
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
        assertNull(registry.findGdccClass("Outer$SharedInner"));
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
        var result = new FrontendClassSkeletonBuilder().build(
                "header_discovery",
                List.of(outerUnit, conflictingTopLevelUnit, stableUnit),
                registry,
                diagnostics,
                FrontendAnalysisData.bootstrap()
        );

        var outerRelation = findSourceRelation(result, "Outer");
        assertEquals(
                List.of("Outer", "KeepAlive"),
                result.classDefs().stream().map(LirClassDef::getName).toList()
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
        assertNull(registry.findGdccClass("Outer$Inner"));
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
        var result = new FrontendClassSkeletonBuilder().build(
                "header_discovery",
                List.of(outerUnit, stableUnit),
                registry,
                diagnostics,
                FrontendAnalysisData.bootstrap()
        );

        var outerRelation = findSourceRelation(result, "OuterCycle");
        assertEquals(
                List.of("OuterCycle", "StableRoot"),
                result.classDefs().stream().map(LirClassDef::getName).toList()
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
        var result = new FrontendClassSkeletonBuilder().build(
                "header_discovery",
                List.of(unit),
                registry,
                diagnostics,
                FrontendAnalysisData.bootstrap()
        );

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

    private FrontendSourceClassRelation findSourceRelation(
            FrontendModuleSkeleton result,
            String className
    ) {
        return result.sourceClassRelations().stream()
                .filter(relation -> relation.name().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Source relation not found: " + className));
    }
}
