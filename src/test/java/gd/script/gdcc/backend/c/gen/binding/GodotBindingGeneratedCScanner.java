package gd.script.gdcc.backend.c.gen.binding;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/// Test-only helper for checking generated C snippets in unit tests.
///
/// Production codegen does not run this scanner. Runtime generation relies on explicit usage
/// collection and template output; tests use this helper to catch fixtures or generated samples that
/// reference a `godot_*` wrapper outside the expected provided/module-local/local sets.
final class GodotBindingGeneratedCScanner {
    private static final @NotNull Pattern GODOT_IDENTIFIER =
            Pattern.compile("\\bgodot_[A-Za-z0-9_]+\\b");
    private static final @NotNull Pattern LOCAL_FUNCTION =
            Pattern.compile("(?m)^\\s*(?:static\\s+)?(?:inline\\s+)?"
                    + "(?:[A-Za-z_][A-Za-z0-9_]*\\s+)+(?:\\*\\s*)?"
                    + "(godot_[A-Za-z0-9_]+)\\s*\\(");

    private GodotBindingGeneratedCScanner() {
    }

    static @NotNull Set<String> scan(
            @NotNull Map<String, String> generatedSources,
            @NotNull Set<String> providedSymbols,
            @NotNull Set<String> moduleLocalSymbols
    ) {
        var localFunctions = new LinkedHashSet<String>();
        for (var text : generatedSources.values()) {
            var localMatcher = LOCAL_FUNCTION.matcher(text);
            while (localMatcher.find()) {
                localFunctions.add(localMatcher.group(1));
            }
        }

        var missing = new LinkedHashSet<String>();
        for (var text : generatedSources.values()) {
            var matcher = GODOT_IDENTIFIER.matcher(text);
            while (matcher.find()) {
                var symbol = matcher.group();
                if (providedSymbols.contains(symbol)
                        || moduleLocalSymbols.contains(symbol)
                        || localFunctions.contains(symbol)
                        || isAllowedNonWrapper(symbol)) {
                    continue;
                }
                missing.add(symbol);
            }
        }
        return missing;
    }

    static void check(
            @NotNull Map<String, String> generatedSources,
            @NotNull Set<String> providedSymbols,
            @NotNull Set<String> moduleLocalSymbols
    ) {
        var missing = scan(generatedSources, providedSymbols, moduleLocalSymbols);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Generated C references unknown Godot binding wrappers: " + missing);
        }
    }

    private static boolean isAllowedNonWrapper(@NotNull String symbol) {
        if (symbol.equals("godot_Nil")
                || symbol.equals("godot_TypedArray")
                || symbol.equals("godot_TypedDictionary")
                || symbol.equals("godot_inf")
                || symbol.equals("godot_binding")
                || symbol.equals("godot_builtin")
                || symbol.equals("godot_fixed_binding")
                || symbol.equals("godot_interface")
                || symbol.equals("godot_utility")
                || symbol.equals("godot_Variant_call")
                || symbol.equals("godot_new_gdcc_Object_with_Variant")) {
            return true;
        }
        if (symbol.equals("godot_bool")
                || symbol.equals("godot_int")
                || symbol.equals("godot_float")
                || symbol.equals("godot_real_t")) {
            return true;
        }
        if (symbol.matches("godot_[A-Z][A-Za-z0-9]*(?:_[A-Z][A-Za-z0-9]*)*")) {
            return true;
        }
        if (symbol.matches("godot_[A-Z0-9_]+")) {
            return true;
        }
        return symbol.endsWith("_") || symbol.contains("__");
    }
}
