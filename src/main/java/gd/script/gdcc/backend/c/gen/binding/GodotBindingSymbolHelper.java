package gd.script.gdcc.backend.c.gen.binding;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/// Validation and snapshot helpers for versioned Godot binding symbol sets.
///
/// The snapshot is a review artifact. It never discovers wrappers and never feeds symbols back
/// into the fixed list; the Java source list and Godot metadata remain the facts.
final class GodotBindingSymbolHelper {
    private GodotBindingSymbolHelper() {
    }

    static @NotNull List<GodotBindingSymbol> validate(@NotNull List<GodotBindingSymbol> symbols) {
        Objects.requireNonNull(symbols);
        var byCanonicalKey = new LinkedHashMap<String, GodotBindingSymbol>();
        var byCName = new LinkedHashMap<String, GodotBindingSymbol>();
        for (var symbol : symbols) {
            var canonicalKey = canonicalKey(symbol);
            var previousCanonical = byCanonicalKey.putIfAbsent(canonicalKey, symbol);
            if (previousCanonical != null) {
                throw new IllegalStateException(
                        "Duplicate Godot binding symbol for '" + symbol.cFunctionName()
                                + "': " + symbol.signatureKey()
                );
            }
            var previousName = byCName.putIfAbsent(symbol.cFunctionName(), symbol);
            if (previousName != null && !previousName.signatureKey().equals(symbol.signatureKey())) {
                throw new IllegalStateException(
                        "Godot binding C name conflict for '" + symbol.cFunctionName()
                                + "': " + previousName.signatureKey() + " vs " + symbol.signatureKey()
                );
            }
        }
        return List.copyOf(byCanonicalKey.values());
    }

    static void writeSnapshot(@NotNull List<GodotBindingSymbol> symbols, @NotNull Path out) throws IOException {
        Objects.requireNonNull(out);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, renderSnapshot(symbols));
    }

    static @NotNull String renderSnapshot(@NotNull List<GodotBindingSymbol> symbols) {
        var sorted = validate(symbols).stream()
                .sorted(Comparator.comparing(GodotBindingSymbol::cFunctionName))
                .toList();
        var out = new StringBuilder();
        out.append("[\n");
        for (var i = 0; i < sorted.size(); i++) {
            var symbol = sorted.get(i);
            out.append("  {\n");
            appendJson(out, "family", symbol.family().name(), true);
            appendJson(out, "owner", symbol.owner(), true);
            appendJson(out, "name", symbol.name(), true);
            appendJson(out, "cFunctionName", symbol.cFunctionName(), true);
            appendJson(out, "signature", symbol.signatureKey(), symbol.primaryHash() != null);
            if (symbol.primaryHash() != null) {
                out.append("    \"primaryHash\": ").append(symbol.primaryHash());
                if (!symbol.compatibilityHashes().isEmpty()) {
                    out.append(',');
                }
                out.append('\n');
            }
            if (!symbol.compatibilityHashes().isEmpty()) {
                out.append("    \"compatibilityHashes\": ").append(symbol.compatibilityHashes()).append('\n');
            }
            out.append("  }");
            if (i + 1 < sorted.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("]\n");
        return out.toString();
    }

    private static @NotNull String canonicalKey(@NotNull GodotBindingSymbol symbol) {
        return symbol.family() + "|" + symbol.owner() + "|" + symbol.name() + "|"
                + symbol.cFunctionName() + "|" + symbol.signatureKey();
    }

    private static void appendJson(
            @NotNull StringBuilder out,
            @NotNull String key,
            @NotNull String value,
            boolean comma
    ) {
        out.append("    \"").append(key).append("\": \"")
                .append(escape(value)).append('"');
        if (comma) {
            out.append(',');
        }
        out.append('\n');
    }

    private static @NotNull String escape(@NotNull String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
