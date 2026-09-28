package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.GdccVersion;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/// Phase 6 compile-time class metadata (editor-integration plan §7 Phase 6).
///
/// Every compiled class — including each nested inner class under its canonical
/// `Outer__sub__Inner` registration name — gets one synthesized static method
/// `_gdcc_get_metadata() -> Dictionary`. The C body lazily parses an embedded JSON literal
/// exactly once (via the engine's `JSON.parse_string`, reached through the runtime's generic
/// `gdcc_classdb_class_call_static`) and caches the resulting Dictionary. Every call returns a
/// DEEP duplicate of that cache: the payload itself contains a nested `gdcc` Dictionary, and a
/// shallow copy would share it — a mutating caller would poison the cache for later readers.
///
/// The synthesized function is a normal `LirFunctionDef` on the class, so binding registration,
/// forward declarations, and `validateFileScopeSymbolsDisjoint` all flow through the existing
/// machinery; only the emitted C body is intrinsic (LIR cannot express a function-local C
/// `static` cache). The stub carries no basic-block semantics — `CCodegen.generateFuncBody`
/// replaces the body wholesale for functions recorded here.
///
/// The cached Dictionary is intentionally never destroyed: a function-local C `static` cannot
/// hook the module `deinitialize` path, so one Dictionary per class per library generation
/// stays referenced until process exit. That bounded leak is the accepted price of the
/// parse-once contract (editor hot reload is the only repeat payer).
public final class CClassMetadataSupport {
    /// Bound method name; the frontend reserves the `_gdcc_` member prefix so source code can
    /// never collide with this synthesized accessor (see
    /// `FrontendSyntheticPropertyHelperSupport.GDCC_INTERNAL_PREFIX`).
    public static final @NotNull String METADATA_FUNCTION_NAME = "_gdcc_get_metadata";
    /// Metadata format version stamped into every payload (`gdcc.format`), reserved for future
    /// evolution such as debugger line tables.
    public static final int METADATA_FORMAT = 1;

    private CClassMetadataSupport() {
    }

    /// Result of synthesizing one module's metadata accessors: canonical class name → raw JSON.
    public record Synthesis(@NotNull Map<String, String> metadataJsonByClass) {
    }

    /// Adds one static `_gdcc_get_metadata` stub per class and records its JSON payload.
    ///
    /// Runs at `CCodegen.prepare` time — before `CGenHelper` collects binding shapes — so the
    /// static `() -> Dictionary` wrapper shape is generated like any user function's.
    public static @NotNull Synthesis synthesize(
            @NotNull LirModule module,
            @NotNull CodegenContext ctx,
            @NotNull Instant compiledAt
    ) {
        var metadataJsonByClass = new LinkedHashMap<String, String>();
        for (var classDef : module.getClassDefs()) {
            var json = buildMetadataJson(module, ctx, classDef, compiledAt);
            metadataJsonByClass.put(classDef.getName(), json);
            // Idempotent under re-prepare: some compile-pipeline fixtures swap in a second
            // CCodegen over the same module, and a duplicate accessor would trip
            // validateFileScopeSymbolsDisjoint on the re-run.
            if (classDef.hasFunction(METADATA_FUNCTION_NAME)) {
                continue;
            }

            var func = new LirFunctionDef(METADATA_FUNCTION_NAME);
            func.setStatic(true);
            func.setReturnType(new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT));
            // Minimal well-formed stub: the generic prepare/finally transforms and validators
            // treat the function as an ordinary empty body; the real C body is intrinsic and
            // replaces whatever these blocks would render.
            var entry = new LirBasicBlock("entry");
            entry.setTerminator(new ReturnInsn(null));
            func.addBasicBlock(entry);
            func.setEntryBlockId("entry");
            classDef.addFunction(func);
        }
        return new Synthesis(Map.copyOf(metadataJsonByClass));
    }

    /// Renders the intrinsic C body of `_gdcc_get_metadata` for one class. The JSON literal is
    /// double-escaped: JSON string escaping first, then `StringUtil.escapeStringLiteral` for the
    /// C source layer (R26 — Windows paths exercise both layers: `E:\x` → JSON `E:\\x` → C
    /// `E:\\\\x`; non-ASCII becomes UCN escapes, which C99 decodes to UTF-8).
    ///
    /// The one-shot latch is deliberately unsynchronized: the editor consumes this on the main
    /// thread (ClassDB static calls from script), and a hypothetical race only pays a duplicate
    /// parse plus one leaked Dictionary. The latch is also armed on the empty-Dictionary
    /// fallback: the literal is compiler-generated, so a parse failure cannot heal by
    /// re-parsing — retrying every call would only buy per-call cost.
    public static @NotNull String renderAccessorBody(@NotNull String metadataJson) {
        var cLiteral = StringUtil.escapeStringLiteral(metadataJson);
        return """
                static godot_bool gdcc_meta_ready = false;
                static godot_Dictionary gdcc_meta_cache;
                if (!gdcc_meta_ready) {
                    godot_String gdcc_meta_json = godot_new_String_with_utf8_chars("%s");
                    godot_Variant gdcc_meta_arg = godot_new_Variant_with_String(&gdcc_meta_json);
                    godot_String_destroy(&gdcc_meta_json);
                    const GDExtensionConstVariantPtr gdcc_meta_args[1] = { (GDExtensionConstVariantPtr)&gdcc_meta_arg };
                    GDExtensionCallError gdcc_meta_error = { 0 };
                    godot_Variant gdcc_meta_parsed = gdcc_classdb_class_call_static("JSON", "parse_string", gdcc_meta_args, 1, &gdcc_meta_error);
                    godot_Variant_destroy(&gdcc_meta_arg);
                    if (gdcc_meta_error.error == GDEXTENSION_CALL_OK && godot_variant_get_type(&gdcc_meta_parsed) == GDEXTENSION_VARIANT_TYPE_DICTIONARY) {
                        gdcc_meta_cache = godot_new_Dictionary_with_Variant(&gdcc_meta_parsed);
                    } else {
                        // Compiler-generated JSON must always parse; the empty-Dictionary fallback
                        // keeps the outward return-type contract intact even if it ever does not.
                        gdcc_meta_cache = godot_new_Dictionary();
                    }
                    godot_Variant_destroy(&gdcc_meta_parsed);
                    gdcc_meta_ready = true;
                }
                // Deep duplicate: the payload nests a `gdcc` Dictionary; a shallow copy would
                // share it with the cache and let a mutating caller poison later reads.
                return godot_Dictionary_duplicate(&gdcc_meta_cache, true);
                """.formatted(cLiteral);
    }

    /// Builds the `{"gdcc": {...}}` payload for one class. `source_res_path` is emitted only
    /// when the display label is `res://`-shaped; `source_path` only when the caller uploaded an
    /// absolute path (plan §7 Phase 6 item 3 — consumers globalize `source_res_path` as the
    /// fallback). `source_name` keeps the dotted source-level name (`Outer.Inner`) recorded by
    /// the frontend skeleton, never the canonical registration identity.
    public static @NotNull String buildMetadataJson(
            @NotNull LirModule module,
            @NotNull CodegenContext ctx,
            @NotNull LirClassDef classDef,
            @NotNull Instant compiledAt
    ) {
        var gdcc = new StringBuilder();
        appendJsonIntField(gdcc, "format", METADATA_FORMAT, true);
        var facts = classDef.getSourceFile() != null
                ? ctx.sourceFileFacts().get(classDef.getSourceFile())
                : null;
        if (facts != null && facts.displayPath().startsWith("res://")) {
            appendJsonStringField(gdcc, "source_res_path", facts.displayPath(), false);
        }
        if (facts != null && facts.absolutePath() != null) {
            appendJsonStringField(gdcc, "source_path", facts.absolutePath(), false);
        }
        appendJsonStringField(gdcc, "source_name", classDef.getSourceClassName(), false);
        appendJsonStringField(gdcc, "module", module.getModuleName(), false);
        appendJsonStringField(gdcc, "compiled_at", compiledAt.toString(), false);
        appendJsonStringField(gdcc, "version", GdccVersion.current().version(), false);
        return "{\"gdcc\":{" + gdcc + "}}";
    }

    private static void appendJsonIntField(
            @NotNull StringBuilder out,
            @NotNull String key,
            long value,
            boolean first
    ) {
        if (!first) {
            out.append(',');
        }
        out.append('"').append(key).append("\":").append(value);
    }

    private static void appendJsonStringField(
            @NotNull StringBuilder out,
            @NotNull String key,
            @NotNull String value,
            boolean first
    ) {
        if (!first) {
            out.append(',');
        }
        out.append('"').append(key).append("\":\"").append(escapeJsonStringContent(value)).append('"');
    }

    /// Minimal JSON string-content escaping: the mandatory quote/backslash escapes plus all
    /// control characters below U+0020. Non-ASCII passes through raw (JSON is UTF-8 text); the
    /// C literal layer above re-escapes it as UCNs.
    static @NotNull String escapeJsonStringContent(@NotNull String value) {
        var out = new StringBuilder(value.length() + 8);
        for (var i = 0; i < value.length(); i++) {
            var ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20) {
                        out.append("\\u%04x".formatted((int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    /// True when `func` on `clazz` is the synthesized metadata accessor whose body must be
    /// replaced by the intrinsic render. Gated on the per-class JSON map (not just the name) so
    /// hand-built LIR fixtures that never ran synthesis keep their ordinary body rendering.
    public static boolean isMetadataAccessor(
            @NotNull Map<String, String> metadataJsonByClass,
            @NotNull LirClassDef clazz,
            @NotNull LirFunctionDef func
    ) {
        return METADATA_FUNCTION_NAME.equals(func.getName())
                && metadataJsonByClass.containsKey(clazz.getName());
    }
}
