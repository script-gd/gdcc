package gd.script.gdcc.backend.c.gen;

import com.google.gson.JsonParser;
import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.SourceFileFacts;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.util.GdccVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit anchors for the Phase 6 metadata payload shape and the JSON→C double-escaping contract
/// (plan §7 Phase 6 acceptance / R26). The pipeline-level readback lives in
/// `ApiCompileClassMetadataTest`; this class pins the builder itself.
class CClassMetadataSupportTest {
    private static final String LOGICAL_KEY = "vfs/demo/src/meta_outer.gd3";

    private static CodegenContext contextWithFacts(Map<String, SourceFileFacts> facts) throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        return new CodegenContext(
                new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
                },
                new ClassRegistry(api),
                false,
                facts
        );
    }

    private static LirClassDef topLevelClass() {
        var classDef = new LirClassDef("MetaOuter", "RefCounted");
        classDef.setSourceFile(LOGICAL_KEY);
        classDef.setSourceClassName("MetaOuter");
        return classDef;
    }

    @Test
    void metadataJsonCarriesAllKeysWhenFactsAreComplete() throws Exception {
        var module = new LirModule("game", List.of());
        var ctx = contextWithFacts(Map.of(
                LOGICAL_KEY,
                new SourceFileFacts("res://src/meta_outer.gd3", "E:/games/demo/src/meta_outer.gd3")
        ));

        var json = CClassMetadataSupport.buildMetadataJson(
                module, ctx, topLevelClass(), Instant.parse("2026-09-28T01:02:03Z"));
        var gdcc = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("gdcc");

        assertEquals(1, gdcc.get("format").getAsInt());
        assertEquals("res://src/meta_outer.gd3", gdcc.get("source_res_path").getAsString());
        assertEquals("E:/games/demo/src/meta_outer.gd3", gdcc.get("source_path").getAsString());
        assertEquals("MetaOuter", gdcc.get("source_name").getAsString());
        assertEquals("game", gdcc.get("module").getAsString());
        assertEquals("2026-09-28T01:02:03Z", gdcc.get("compiled_at").getAsString());
        assertEquals(GdccVersion.current().version(), gdcc.get("version").getAsString());
    }

    @Test
    void metadataJsonOmitsOptionalKeysWhenFactsAreMissing() throws Exception {
        var module = new LirModule("game", List.of());

        // Non-res:// display label: source_res_path must be omitted (plan item 3).
        var ctxAbsoluteOnly = contextWithFacts(Map.of(
                LOGICAL_KEY,
                new SourceFileFacts("/home/dev/src/meta_outer.gd3", "/home/dev/src/meta_outer.gd3")
        ));
        var gdccAbsoluteOnly = JsonParser.parseString(CClassMetadataSupport.buildMetadataJson(
                        module, ctxAbsoluteOnly, topLevelClass(), Instant.parse("2026-09-28T00:00:00Z")))
                .getAsJsonObject().getAsJsonObject("gdcc");
        assertFalse(gdccAbsoluteOnly.has("source_res_path"));
        assertEquals("/home/dev/src/meta_outer.gd3", gdccAbsoluteOnly.get("source_path").getAsString());

        // res:// label without an absolute path: source_path is omitted (consumers globalize
        // source_res_path as the fallback).
        var ctxResOnly = contextWithFacts(Map.of(
                LOGICAL_KEY,
                new SourceFileFacts("res://src/meta_outer.gd3", null)
        ));
        var gdccResOnly = JsonParser.parseString(CClassMetadataSupport.buildMetadataJson(
                        module, ctxResOnly, topLevelClass(), Instant.parse("2026-09-28T00:00:00Z")))
                .getAsJsonObject().getAsJsonObject("gdcc");
        assertEquals("res://src/meta_outer.gd3", gdccResOnly.get("source_res_path").getAsString());
        assertFalse(gdccResOnly.has("source_path"));

        // No facts at all (hand-built LIR): both source path keys are omitted; the remaining
        // keys keep the payload self-describing.
        var gdccNoFacts = JsonParser.parseString(CClassMetadataSupport.buildMetadataJson(
                        module, contextWithFacts(Map.of()), topLevelClass(), Instant.parse("2026-09-28T00:00:00Z")))
                .getAsJsonObject().getAsJsonObject("gdcc");
        assertFalse(gdccNoFacts.has("source_res_path"));
        assertFalse(gdccNoFacts.has("source_path"));
        assertEquals("MetaOuter", gdccNoFacts.get("source_name").getAsString());
    }

    @Test
    void nestedClassSourceNameKeepsDottedSourcePath() throws Exception {
        var module = new LirModule("game", List.of());
        var inner = new LirClassDef("MetaOuter__sub__Inner", "RefCounted");
        inner.setSourceFile(LOGICAL_KEY);
        inner.setSourceClassName("MetaOuter.Inner");

        var gdcc = JsonParser.parseString(CClassMetadataSupport.buildMetadataJson(
                        module, contextWithFacts(Map.of()), inner, Instant.parse("2026-09-28T00:00:00Z")))
                .getAsJsonObject().getAsJsonObject("gdcc");

        assertEquals("MetaOuter.Inner", gdcc.get("source_name").getAsString());
    }

    /// The escaping matrix (R26): quotes, backslashes (Windows-path double layer), newline, tab,
    /// CJK and a non-BMP emoji must survive JSON escaping → C literal escaping → C unescape →
    /// JSON parse with the exact original text.
    @Test
    void accessorBodyRoundTripsEscapedJsonThroughTheCLiteralLayer() throws Exception {
        var nastyPath = "E:\\games \"quoted\"\\玩家\\😀\\meta.gd3";
        var module = new LirModule("ga\"me", List.of());
        var ctx = contextWithFacts(Map.of(
                LOGICAL_KEY,
                new SourceFileFacts("res://src/meta_outer.gd3", nastyPath)
        ));
        var classDef = topLevelClass();
        classDef.setSourceClassName("MetaOuter\nInner\tName");
        var json = CClassMetadataSupport.buildMetadataJson(
                module, ctx, classDef, Instant.parse("2026-09-28T00:00:00Z"));

        var body = CClassMetadataSupport.renderAccessorBody(json);
        var cLiteral = extractFirstCStringLiteral(body);
        var unescaped = cUnescape(cLiteral);
        var gdcc = JsonParser.parseString(unescaped).getAsJsonObject().getAsJsonObject("gdcc");

        assertEquals(nastyPath, gdcc.get("source_path").getAsString());
        assertEquals("MetaOuter\nInner\tName", gdcc.get("source_name").getAsString());
        assertEquals("ga\"me", gdcc.get("module").getAsString());
    }

    @Test
    void accessorBodyLazilyParsesAndCachesThroughTheJsonClass() {
        var body = CClassMetadataSupport.renderAccessorBody("{\"gdcc\":{\"format\":1}}");

        // The behavioral anchors of the C contract: one-time lazy parse guarded by a static flag,
        // parse through the runtime's generic ClassDB static-call helper, and a defensive copy on
        // every return so callers can never mutate the shared cache.
        assertTrue(body.contains("static godot_bool gdcc_meta_ready = false;"), body);
        assertTrue(body.contains("static godot_Dictionary gdcc_meta_cache;"), body);
        assertTrue(body.contains("gdcc_classdb_class_call_static(\"JSON\", \"parse_string\""), body);
        assertTrue(body.contains("gdcc_meta_cache = godot_new_Dictionary_with_Variant(&gdcc_meta_parsed);"), body);
        assertTrue(body.contains("return godot_Dictionary_duplicate(&gdcc_meta_cache, true);"), body);
    }

    private static String extractFirstCStringLiteral(String body) {
        var start = body.indexOf('"');
        assertTrue(start >= 0, body);
        var out = new StringBuilder();
        var escaped = false;
        for (var i = start + 1; i < body.length(); i++) {
            var ch = body.charAt(i);
            if (escaped) {
                out.append('\\').append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                return out.toString();
            } else {
                out.append(ch);
            }
        }
        throw new IllegalStateException("unterminated C string literal in: " + body);
    }

    /// Inverse of `StringUtil.escapeStringLiteral` for exactly the escape forms it emits.
    private static String cUnescape(String literal) {
        var out = new StringBuilder(literal.length());
        var i = 0;
        while (i < literal.length()) {
            var ch = literal.charAt(i);
            if (ch != '\\') {
                out.append(ch);
                i++;
                continue;
            }
            var kind = literal.charAt(i + 1);
            switch (kind) {
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(literal.substring(i + 2, i + 6), 16));
                    i += 4;
                }
                case 'U' -> {
                    var codePoint = Integer.parseInt(literal.substring(i + 2, i + 10), 16);
                    out.appendCodePoint(codePoint);
                    i += 8;
                }
                default -> throw new IllegalStateException("unexpected C escape: \\" + kind);
            }
            i += 2;
        }
        return out.toString();
    }
}
