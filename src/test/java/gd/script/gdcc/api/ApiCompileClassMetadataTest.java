package gd.script.gdcc.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Phase 6 pipeline acceptance (plan §7 Phase 6): every compiled class — top-level and nested —
/// emits exactly one static `_gdcc_get_metadata` accessor whose embedded JSON reflects the
/// source snapshot facts that flowed through `vfs.putFile` (displayPath / optional absolutePath).
class ApiCompileClassMetadataTest {
    @Test
    void compileEmitsMetadataAccessorsWithSourceFacts(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("metadata-project");

        api.createModule("demo", "Metadata Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(projectPath));
        api.putFile("demo", "/src/meta_outer.gd3", """
                class_name MetaOuter
                extends RefCounted

                class Inner extends RefCounted:
                    pass
                """, "res://src/meta_outer.gd3", "E:/work/demo/src/meta_outer.gd3");
        api.putFile("demo", "/src/plain.gd3", """
                class_name MetaPlain
                extends RefCounted
                """, "MetaPlainDisplay");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome(), result.failureMessage());

        var entryC = Files.readString(projectPath.resolve("entry.c"));
        var entryH = Files.readString(projectPath.resolve("entry.h"));

        // One accessor definition per class, including the nested class under its canonical name.
        var outerJson = readMetadataJson(entryC, "MetaOuter");
        var innerJson = readMetadataJson(entryC, "MetaOuter__sub__Inner");
        var plainJson = readMetadataJson(entryC, "MetaPlain");

        var outer = outerJson.getAsJsonObject("gdcc");
        assertEquals(1, outer.get("format").getAsInt());
        assertEquals("res://src/meta_outer.gd3", outer.get("source_res_path").getAsString());
        assertEquals("E:/work/demo/src/meta_outer.gd3", outer.get("source_path").getAsString());
        assertEquals("MetaOuter", outer.get("source_name").getAsString());
        assertEquals("Metadata Demo", outer.get("module").getAsString());
        // compiled_at is an RFC-3339 instant; the exact value is wall-clock by design.
        Instant.parse(outer.get("compiled_at").getAsString());
        assertFalse(outer.get("version").getAsString().isBlank());

        var inner = innerJson.getAsJsonObject("gdcc");
        assertEquals("MetaOuter.Inner", inner.get("source_name").getAsString());
        assertEquals("res://src/meta_outer.gd3", inner.get("source_res_path").getAsString());

        // displayPath without the res:// shape keeps the class self-describing but omits
        // source_res_path, and a missing absolutePath omits source_path (plan item 3).
        var plain = plainJson.getAsJsonObject("gdcc");
        assertFalse(plain.has("source_res_path"));
        assertFalse(plain.has("source_path"));
        assertEquals("MetaPlain", plain.get("source_name").getAsString());

        // Static binding: the shared 0-arg Dictionary wrapper carries the STATIC flag, and each
        // class binds its own accessor under the reserved name.
        assertTrue(entryH.contains("gdcc_bind_method_0_arg_ret_Dictionary_static"), entryH);
        var staticBind = entryH.substring(entryH.indexOf("gdcc_bind_method_0_arg_ret_Dictionary_static"));
        assertTrue(staticBind.contains("GDEXTENSION_METHOD_FLAG_STATIC"), staticBind);
        assertEquals(1, countOccurrences(entryH, "GDEXTENSION_METHOD_FLAG_STATIC"), entryH);
        assertTrue(entryC.contains("GD_STATIC_SN(u8\"_gdcc_get_metadata\")"), entryC);
        assertEquals(3, countOccurrences(entryC, "GD_STATIC_SN(u8\"_gdcc_get_metadata\")"), entryC);
    }

    /// Retention contract (mirrors displayPath): re-uploading content without an absolutePath
    /// must not drop the provenance the earlier upload established.
    @Test
    void reuploadWithoutAbsolutePathPreservesEarlierSourcePath(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("metadata-retention-project");

        api.createModule("demo", "Metadata Retention Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(projectPath));
        api.putFile("demo", "/src/meta_keep.gd3", """
                class_name MetaKeep
                extends RefCounted
                """, "res://src/meta_keep.gd3", "E:/work/demo/src/meta_keep.gd3");
        // Content-only re-upload: no displayPath and no absolutePath.
        api.putFile("demo", "/src/meta_keep.gd3", """
                class_name MetaKeep
                extends RefCounted

                func still_there() -> int:
                    return 1
                """);

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome(), result.failureMessage());

        var entryC = Files.readString(projectPath.resolve("entry.c"));
        var gdcc = readMetadataJson(entryC, "MetaKeep").getAsJsonObject("gdcc");
        assertEquals("res://src/meta_keep.gd3", gdcc.get("source_res_path").getAsString());
        assertEquals("E:/work/demo/src/meta_keep.gd3", gdcc.get("source_path").getAsString());
    }

    /// API-level anchor for the absolutePath-only upload shape: displayPath null → the VFS keeps
    /// its virtual-path fallback (never res://, so `source_res_path` stays absent) while
    /// `source_path` is still emitted.
    @Test
    void metadataFromAbsolutePathOnlyUpload(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("metadata-abs-only-project");

        api.createModule("demo", "Metadata Abs-Only Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(projectPath));
        api.putFile("demo", "/src/meta_abs.gd3", """
                class_name MetaAbs
                extends RefCounted
                """, null, "/abs/path/to/meta_abs.gd3");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome(), result.failureMessage());

        var entryC = Files.readString(projectPath.resolve("entry.c"));
        var gdcc = readMetadataJson(entryC, "MetaAbs").getAsJsonObject("gdcc");
        assertEquals("/abs/path/to/meta_abs.gd3", gdcc.get("source_path").getAsString());
        assertFalse(gdcc.has("source_res_path"));
        assertEquals("MetaAbs", gdcc.get("source_name").getAsString());
    }

    /// Extracts the embedded JSON literal of `<className>__gdcc_get_metadata` from entry.c,
    /// undoes the C literal layer, and parses the JSON (the literal layer is itself anchored by
    /// `CClassMetadataSupportTest`'s escaping matrix).
    private static JsonObject readMetadataJson(String entryC, String className) {
        var pattern = Pattern.compile(
                Pattern.quote(className) + "__gdcc_get_metadata\\s*\\(\\s*\\)[^\\{]*\\{.*?godot_new_String_with_utf8_chars\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)",
                Pattern.DOTALL
        );
        var matcher = pattern.matcher(entryC);
        assertTrue(matcher.find(), "metadata accessor not found for " + className + " in:\n" + entryC);
        return JsonParser.parseString(cUnescape(matcher.group(1))).getAsJsonObject();
    }

    private static int countOccurrences(String haystack, String needle) {
        var count = 0;
        var index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
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
                    out.appendCodePoint(Integer.parseInt(literal.substring(i + 2, i + 10), 16));
                    i += 8;
                }
                default -> throw new IllegalStateException("unexpected C escape: \\" + kind);
            }
            i += 2;
        }
        return out.toString();
    }
}
