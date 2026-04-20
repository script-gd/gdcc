package dev.superice.gdcc.frontend.parse;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendModuleTest {
    private final GdScriptParserService parserService = new GdScriptParserService();

    @Test
    void constructorFreezesUnitsAndCanonicalNameMapWhilePreservingOrder() {
        var first = parse("first.gd", "class_name First\nextends RefCounted\n");
        var second = parse("second.gd", "class_name Second\nextends RefCounted\n");
        var units = new ArrayList<>(List.of(first, second));
        var canonicalNameMap = new LinkedHashMap<String, String>();
        canonicalNameMap.put("First", "FirstRuntime");
        canonicalNameMap.put("Second", "SecondRuntime");

        var module = new FrontendModule("test_module", units, canonicalNameMap);
        units.clear();
        canonicalNameMap.clear();

        assertEquals("test_module", module.moduleName());
        assertEquals(List.of(first, second), module.units());
        assertEquals(
                new LinkedHashMap<>(java.util.Map.of(
                        "First", "FirstRuntime",
                        "Second", "SecondRuntime"
                )),
                new LinkedHashMap<>(module.topLevelCanonicalNameMap())
        );
        assertThrows(UnsupportedOperationException.class, () -> module.units().add(first));
        assertThrows(
                UnsupportedOperationException.class,
                () -> module.topLevelCanonicalNameMap().put("Third", "ThirdRuntime")
        );
    }

    @Test
    void singleUnitFactoryBuildsModuleWithOneUnitAndEmptyFrozenMap() {
        var unit = parse("single.gd", "class_name Single\nextends RefCounted\n");

        var module = FrontendModule.singleUnit("single_module", unit);

        assertEquals("single_module", module.moduleName());
        assertEquals(1, module.units().size());
        assertSame(unit, module.units().getFirst());
        assertTrue(module.topLevelCanonicalNameMap().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> module.topLevelCanonicalNameMap().put("Single", "SingleRuntime")
        );
    }

    @Test
    void singleUnitFactoryFreezesProvidedCanonicalNameMap() {
        var unit = parse("single_with_map.gd", "class_name SingleWithMap\nextends RefCounted\n");
        var canonicalNameMap = new LinkedHashMap<String, String>();
        canonicalNameMap.put("SingleWithMap", "RuntimeSingleWithMap");

        var module = FrontendModule.singleUnit("single_module", unit, canonicalNameMap);
        canonicalNameMap.put("Late", "LateRuntime");

        assertEquals("single_module", module.moduleName());
        assertEquals(List.of(unit), module.units());
        assertEquals(Map.of("SingleWithMap", "RuntimeSingleWithMap"), module.topLevelCanonicalNameMap());
        assertThrows(
                UnsupportedOperationException.class,
                () -> module.topLevelCanonicalNameMap().put("Other", "OtherRuntime")
        );
    }

    @Test
    void constructorRejectsMissingBoundaryInputs() {
        var unit = parse("boundary.gd", "class_name Boundary\nextends RefCounted\n");

        assertThrows(NullPointerException.class, () -> new FrontendModule(null, List.of(unit)));
        assertThrows(NullPointerException.class, () -> new FrontendModule("test_module", null));
        assertThrows(
                NullPointerException.class,
                () -> new FrontendModule("test_module", List.of(unit), null)
        );
    }

    @Test
    void constructorRejectsBlankCanonicalMappingEntries() {
        var unit = parse("invalid_map.gd", "class_name InvalidMap\nextends RefCounted\n");

        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendModule("test_module", List.of(unit), Map.of("", "RuntimeName"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendModule("test_module", List.of(unit), Map.of("InvalidMap", " "))
        );
    }

    @Test
    void constructorRejectsReservedSequenceInCanonicalMappingEntries() {
        var unit = parse("reserved_map.gd", "class_name ReservedMap\nextends RefCounted\n");

        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendModule(
                        "test_module",
                        List.of(unit),
                        Map.of("Hero__sub__Worker", "RuntimeHero")
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendModule(
                        "test_module",
                        List.of(unit),
                        Map.of("ReservedMap", "Runtime__sub__Worker")
                )
        );
    }

    @Test
    void constructorAcceptsMappingEntriesThatOnlyApproximateReservedSequence() {
        var unit = parse("near_reserved_map.gd", "class_name NearReservedMap\nextends RefCounted\n");

        var module = new FrontendModule(
                "test_module",
                List.of(unit),
                Map.of(
                        "Hero__subLeaf",
                        "Runtime__subLeaf"
                )
        );

        assertEquals(
                Map.of(
                        "Hero__subLeaf",
                        "Runtime__subLeaf"
                ),
                module.topLevelCanonicalNameMap()
        );
    }

    private FrontendSourceUnit parse(String fileName, String source) {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.snapshot().isEmpty());
        return unit;
    }
}
