package dev.superice.gdcc.api;

import dev.superice.gdcc.frontend.parse.FrontendModule;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiCanonicalNameMapTest {
    @Test
    void setTopLevelCanonicalNameMapFreezesOrderAndRemainsFrontendCompatible() {
        var api = new API();
        api.createModule("demo", "Demo");

        var mapping = new LinkedHashMap<String, String>();
        mapping.put("Hero", "RuntimeHero");
        mapping.put("Worker__subLeaf", "Runtime__subLeaf");

        var returned = api.setTopLevelCanonicalNameMap("demo", mapping);
        mapping.clear();
        mapping.put("Late", "LateRuntime");

        assertEquals(
                List.of(
                        java.util.Map.entry("Hero", "RuntimeHero"),
                        java.util.Map.entry("Worker__subLeaf", "Runtime__subLeaf")
                ),
                returned.entrySet().stream().toList()
        );
        assertEquals(returned, api.getTopLevelCanonicalNameMap("demo"));
        assertEquals(returned, api.getModule("demo").topLevelCanonicalNameMap());
        assertThrows(UnsupportedOperationException.class, () -> returned.put("Other", "RuntimeOther"));

        var frontendModule = new FrontendModule("demo_module", List.of(), returned);
        assertEquals(returned, frontendModule.topLevelCanonicalNameMap());

        api.deleteModule("demo");
        var recreated = api.createModule("demo", "Demo Recreated");

        assertEquals(java.util.Map.of(), recreated.topLevelCanonicalNameMap());
        assertEquals(java.util.Map.of(), api.getTopLevelCanonicalNameMap("demo"));
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void setTopLevelCanonicalNameMapRejectsInvalidBoundaryEntriesWithFrontendMessages() {
        var api = new API();
        api.createModule("demo", "Demo");

        var nullMap = assertThrows(
                NullPointerException.class,
                () -> api.setTopLevelCanonicalNameMap("demo", null)
        );
        assertEquals("topLevelCanonicalNameMap must not be null", nullMap.getMessage());

        var nullKeyMapping = new LinkedHashMap<String, String>();
        nullKeyMapping.put(null, "RuntimeHero");
        var nullKey = assertThrows(
                NullPointerException.class,
                () -> api.setTopLevelCanonicalNameMap("demo", nullKeyMapping)
        );
        assertEquals("topLevelCanonicalNameMap key must not be null", nullKey.getMessage());

        var blankKey = assertThrows(
                IllegalArgumentException.class,
                () -> api.setTopLevelCanonicalNameMap("demo", java.util.Map.of("", "RuntimeHero"))
        );
        assertEquals("topLevelCanonicalNameMap key must not be blank", blankKey.getMessage());

        var blankValue = assertThrows(
                IllegalArgumentException.class,
                () -> api.setTopLevelCanonicalNameMap("demo", java.util.Map.of("Hero", " "))
        );
        assertEquals("topLevelCanonicalNameMap value must not be blank", blankValue.getMessage());

        var reservedKey = assertThrows(
                IllegalArgumentException.class,
                () -> api.setTopLevelCanonicalNameMap("demo", java.util.Map.of("Hero__sub__Worker", "RuntimeHero"))
        );
        assertEquals(
                "topLevelCanonicalNameMap key must not contain reserved gdcc class-name sequence '__sub__'",
                reservedKey.getMessage()
        );

        var reservedValue = assertThrows(
                IllegalArgumentException.class,
                () -> api.setTopLevelCanonicalNameMap("demo", java.util.Map.of("Hero", "Runtime__sub__Worker"))
        );
        assertEquals(
                "topLevelCanonicalNameMap value must not contain reserved gdcc class-name sequence '__sub__'",
                reservedValue.getMessage()
        );
    }
}
