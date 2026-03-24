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
    void constructorFreezesUnitsAndRuntimeNameMapWhilePreservingOrder() {
        var first = parse("first.gd", "class_name First\nextends RefCounted\n");
        var second = parse("second.gd", "class_name Second\nextends RefCounted\n");
        var units = new ArrayList<>(List.of(first, second));
        var runtimeNameMap = new LinkedHashMap<String, String>();
        runtimeNameMap.put("First", "FirstRuntime");
        runtimeNameMap.put("Second", "SecondRuntime");

        var module = new FrontendModule("test_module", units, runtimeNameMap);
        units.clear();
        runtimeNameMap.clear();

        assertEquals("test_module", module.moduleName());
        assertEquals(List.of(first, second), module.units());
        assertEquals(
                new LinkedHashMap<>(java.util.Map.of(
                        "First", "FirstRuntime",
                        "Second", "SecondRuntime"
                )),
                new LinkedHashMap<>(module.topLevelRuntimeNameMap())
        );
        assertThrows(UnsupportedOperationException.class, () -> module.units().add(first));
        assertThrows(
                UnsupportedOperationException.class,
                () -> module.topLevelRuntimeNameMap().put("Third", "ThirdRuntime")
        );
    }

    @Test
    void singleUnitFactoryBuildsModuleWithOneUnitAndEmptyFrozenMap() {
        var unit = parse("single.gd", "class_name Single\nextends RefCounted\n");

        var module = FrontendModule.singleUnit("single_module", unit);

        assertEquals("single_module", module.moduleName());
        assertEquals(1, module.units().size());
        assertSame(unit, module.units().getFirst());
        assertTrue(module.topLevelRuntimeNameMap().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> module.topLevelRuntimeNameMap().put("Single", "SingleRuntime")
        );
    }

    @Test
    void singleUnitFactoryFreezesProvidedRuntimeNameMap() {
        var unit = parse("single_with_map.gd", "class_name SingleWithMap\nextends RefCounted\n");
        var runtimeNameMap = new LinkedHashMap<String, String>();
        runtimeNameMap.put("SingleWithMap", "RuntimeSingleWithMap");

        var module = FrontendModule.singleUnit("single_module", unit, runtimeNameMap);
        runtimeNameMap.put("Late", "LateRuntime");

        assertEquals("single_module", module.moduleName());
        assertEquals(List.of(unit), module.units());
        assertEquals(Map.of("SingleWithMap", "RuntimeSingleWithMap"), module.topLevelRuntimeNameMap());
        assertThrows(
                UnsupportedOperationException.class,
                () -> module.topLevelRuntimeNameMap().put("Other", "OtherRuntime")
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
    void constructorRejectsBlankRuntimeMappingEntries() {
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

    private FrontendSourceUnit parse(String fileName, String source) {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.snapshot().isEmpty());
        return unit;
    }
}
