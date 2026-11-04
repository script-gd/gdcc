package gd.script.gdcc.cli;

import gd.script.gdcc.exception.GodotConfigException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GodotConfigFileTest {
    @Test
    void parsesSectionsKeysAndComments() {
        var config = parse("""
                ; header comment
                config_version=5

                [application]

                config/name="GdccTest" ; trailing comment
                config/icon="res://icon.svg"
                """);

        assertInstanceOf(GodotConfigFile.IntValue.class, config.findEntry("", "config_version").orElseThrow().value());
        var name = config.findEntry("application", "config/name").orElseThrow().value();
        assertEquals("GdccTest", assertInstanceOf(GodotConfigFile.StringValue.class, name).text());
        assertEquals("res://icon.svg",
                assertInstanceOf(GodotConfigFile.StringValue.class,
                        config.findEntry("application", "config/icon").orElseThrow().value()).text());
    }

    @Test
    void keepsValueRawTextForRoundTrip() {
        var config = parse("""
                [gdcc]
                sync/excluded_globs=PackedStringArray("addons/gdcc/*", "tools/*")
                misc=Color(0.3, 0.4, 0.5)
                """);

        var globs = config.findEntry("gdcc", "sync/excluded_globs").orElseThrow().value();
        assertEquals(List.of("addons/gdcc/*", "tools/*"),
                assertInstanceOf(GodotConfigFile.StringArrayValue.class, globs).items());
        assertEquals("PackedStringArray(\"addons/gdcc/*\", \"tools/*\")", globs.raw());
        var misc = config.findEntry("gdcc", "misc").orElseThrow().value();
        assertEquals("Color(0.3, 0.4, 0.5)", assertInstanceOf(GodotConfigFile.OpaqueValue.class, misc).raw());
    }

    @Test
    void decodesStringEscapesAndMultilineStrings() {
        var config = parse("""
                [a]
                esc="tab\\tnewline\\nquote\\"backslash\\\\"
                multi="line1
                line2"
                uni="\\u0041"
                """);

        assertEquals("tab\tnewline\nquote\"backslash\\",
                assertInstanceOf(GodotConfigFile.StringValue.class,
                        config.findEntry("a", "esc").orElseThrow().value()).text());
        assertEquals("line1\nline2",
                assertInstanceOf(GodotConfigFile.StringValue.class,
                        config.findEntry("a", "multi").orElseThrow().value()).text());
        assertEquals("A",
                assertInstanceOf(GodotConfigFile.StringValue.class,
                        config.findEntry("a", "uni").orElseThrow().value()).text());
    }

    @Test
    void decodesScalarsAndContainers() {
        var config = parse("""
                [s]
                plain=42
                negative=-7
                hex=0x2A
                binary=0b101
                floating=3.25
                exp=1e3
                yes=true
                no=false
                array=["x", "y"]
                empty=PackedStringArray()
                dict={"k": 1}
                bare=nil
                """);

        assertEquals(42L, assertInstanceOf(GodotConfigFile.IntValue.class,
                config.findEntry("s", "plain").orElseThrow().value()).number());
        assertEquals(-7L, assertInstanceOf(GodotConfigFile.IntValue.class,
                config.findEntry("s", "negative").orElseThrow().value()).number());
        assertEquals(42L, assertInstanceOf(GodotConfigFile.IntValue.class,
                config.findEntry("s", "hex").orElseThrow().value()).number());
        assertEquals(5L, assertInstanceOf(GodotConfigFile.IntValue.class,
                config.findEntry("s", "binary").orElseThrow().value()).number());
        assertEquals(3.25, assertInstanceOf(GodotConfigFile.FloatValue.class,
                config.findEntry("s", "floating").orElseThrow().value()).number());
        assertEquals(1000.0, assertInstanceOf(GodotConfigFile.FloatValue.class,
                config.findEntry("s", "exp").orElseThrow().value()).number());
        assertTrue(assertInstanceOf(GodotConfigFile.BoolValue.class,
                config.findEntry("s", "yes").orElseThrow().value()).flag());
        assertFalse(assertInstanceOf(GodotConfigFile.BoolValue.class,
                config.findEntry("s", "no").orElseThrow().value()).flag());
        assertEquals(List.of("x", "y"),
                assertInstanceOf(GodotConfigFile.StringArrayValue.class,
                        config.findEntry("s", "array").orElseThrow().value()).items());
        assertEquals(List.of(),
                assertInstanceOf(GodotConfigFile.StringArrayValue.class,
                        config.findEntry("s", "empty").orElseThrow().value()).items());
        assertInstanceOf(GodotConfigFile.OpaqueValue.class,
                config.findEntry("s", "dict").orElseThrow().value());
        assertInstanceOf(GodotConfigFile.OpaqueValue.class,
                config.findEntry("s", "bare").orElseThrow().value());
    }

    @Test
    void containerLiteralsMaySpanLines() {
        var config = parse("""
                [gdcc]
                sync/excluded_globs=PackedStringArray(
                    "addons/gdcc/*",
                    "generated/*",
                )
                """);

        assertEquals(List.of("addons/gdcc/*", "generated/*"),
                assertInstanceOf(GodotConfigFile.StringArrayValue.class,
                        config.findEntry("gdcc", "sync/excluded_globs").orElseThrow().value()).items());
    }

    @Test
    void serializedObjectsAndLiteralPrefixesStayOpaque() {
        // The shape Godot writes for [input] actions: Object(...) calls carrying "prop":value
        // pairs, prefixed literals (&/^/@), typed containers — none consumed by GDCC, all kept.
        var config = parse("""
                [input]
                jump={
                "deadzone": 0.5,
                "events": [Object(InputEventKey,"physical_keycode":32,"pressed":false)]
                }
                path=&"res://scenes"
                node=^"/root/Main"
                ref=@"1_abc"
                typed=Array[int](1, 2, 3)
                """);

        assertInstanceOf(GodotConfigFile.OpaqueValue.class,
                config.findEntry("input", "jump").orElseThrow().value());
        assertInstanceOf(GodotConfigFile.OpaqueValue.class,
                config.findEntry("input", "path").orElseThrow().value());
        assertInstanceOf(GodotConfigFile.OpaqueValue.class,
                config.findEntry("input", "node").orElseThrow().value());
        assertInstanceOf(GodotConfigFile.OpaqueValue.class,
                config.findEntry("input", "ref").orElseThrow().value());
        assertInstanceOf(GodotConfigFile.OpaqueValue.class,
                config.findEntry("input", "typed").orElseThrow().value());
    }

    @Test
    void namedArgumentInsideConsumedContainerStaysOpaque() {
        // PackedStringArray("k":v) must not silently decode as ["k"]: the named pair poisons
        // the element list so strict consumers see an opaque value and reject the setting.
        var config = parse("""
                [gdcc]
                sync/excluded_globs=PackedStringArray("k":1)
                """);

        assertInstanceOf(GodotConfigFile.OpaqueValue.class,
                config.findEntry("gdcc", "sync/excluded_globs").orElseThrow().value());
    }

    @Test
    void lastAssignmentWinsAndOverrideSiblingsAreVisible() {
        var config = parse("""
                [gdcc]
                build/optimization_level="DEBUG"
                build/optimization_level="RELEASE"
                build/optimization_level.release="DEBUG"
                """);

        var value = config.findEntry("gdcc", "build/optimization_level").orElseThrow().value();
        assertEquals("RELEASE", assertInstanceOf(GodotConfigFile.StringValue.class, value).text());
        assertTrue(config.hasKeysWithPrefix("gdcc", "build/optimization_level."));
        assertFalse(config.hasKeysWithPrefix("gdcc", "build/strict_mode."));
        assertFalse(config.hasKeysWithPrefix("application", "build/optimization_level."));
    }

    @Test
    void repeatedSectionsKeepAllEntriesInOrder() {
        var config = parse("""
                [a]
                one=1
                [b]
                two=2
                [a]
                three=3
                """);

        var names = config.sections().stream().map(GodotConfigFile.Section::name).toList();
        assertEquals(List.of("", "a", "b", "a"), names);
    }

    @Test
    void mixedArrayStaysOpaqueSoConsumersCanRejectIt() {
        var config = parse("""
                [gdcc]
                sync/excluded_globs=["ok/*", 3]
                """);

        assertInstanceOf(GodotConfigFile.OpaqueValue.class,
                config.findEntry("gdcc", "sync/excluded_globs").orElseThrow().value());
    }

    @Test
    void malformedDocumentsFailWithOriginAndLine() {
        var unterminatedString = """
                [a]
                s="unclosed
                """;
        var exception = assertThrows(GodotConfigException.class, () -> parse(unterminatedString));
        assertTrue(exception.getMessage().contains("test.godot:2"), exception.getMessage());

        assertThrows(GodotConfigException.class, () -> parse("[a\nx=1\n"));
        assertThrows(GodotConfigException.class, () -> parse("[a]\nx\n"));
        assertThrows(GodotConfigException.class, () -> parse("[a]\nx=1 trailing\n"));
        assertThrows(GodotConfigException.class, () -> parse("[a]\nx=[1,\n"));
        assertThrows(GodotConfigException.class, () -> parse("[a]\nx=PackedStringArray(\"a\"\n"));
        assertThrows(GodotConfigException.class, () -> parse("[a]\nx={\"k\" 1}\n"));
        assertThrows(GodotConfigException.class, () -> parse("[a]\nx=@\n"));
    }

    @Test
    void emptyAndCommentOnlyDocumentsParseToNoEntries() {
        var config = parse("; only a comment\n\n");
        assertTrue(config.findEntry("application", "config/name").isEmpty());
        assertEquals(1, config.sections().size());
    }

    private static GodotConfigFile parse(String text) {
        return GodotConfigFile.parse(text, "test.godot");
    }
}
