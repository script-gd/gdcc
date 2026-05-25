package gd.script.gdcc.backend.c.gen.binding;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class GdccHelperBindingScanner {
    private static final @NotNull Pattern GODOT_IDENTIFIER =
            Pattern.compile("\\bgodot_[A-Za-z0-9_]+\\b");
    private static final @NotNull Pattern LOCAL_FUNCTION =
            Pattern.compile("\\b(?:static\\s+)?(?:inline\\s+)?[A-Za-z_][A-Za-z0-9_\\s*]+\\s+(godot_[A-Za-z0-9_]+)\\s*\\(");

    private GdccHelperBindingScanner() {
    }

    static void checkFixedCoverage(
            @NotNull Path helperRoot,
            @NotNull Path templateRoot,
            @NotNull Set<String> providedSymbols
    ) throws IOException {
        var missing = scan(helperRoot, templateRoot, providedSymbols);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing fixed Godot binding symbols: " + missing);
        }
    }

    static @NotNull Set<String> scan(
            @NotNull Path helperRoot,
            @NotNull Path templateRoot,
            @NotNull Set<String> providedSymbols
    ) throws IOException {
        var missing = new LinkedHashSet<String>();
        for (var file : scanFiles(helperRoot, templateRoot)) {
            var text = Files.readString(file);
            var local = localFunctions(text);
            var matcher = GODOT_IDENTIFIER.matcher(text);
            while (matcher.find()) {
                var symbol = matcher.group();
                if (providedSymbols.contains(symbol) || local.contains(symbol) || isAllowedNonWrapper(symbol)) {
                    continue;
                }
                missing.add(symbol);
            }
        }
        return missing;
    }

    private static @NotNull List<Path> scanFiles(@NotNull Path helperRoot, @NotNull Path templateRoot)
            throws IOException {
        var files = new ArrayList<Path>();
        if (Files.exists(helperRoot)) {
            try (var stream = Files.walk(helperRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".h") || path.toString().endsWith(".c"))
                        .forEach(files::add);
            }
        }
        if (Files.exists(templateRoot)) {
            try (var stream = Files.walk(templateRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".ftl"))
                        .forEach(files::add);
            }
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private static @NotNull Set<String> localFunctions(@NotNull String text) {
        var local = new LinkedHashSet<String>();
        var matcher = LOCAL_FUNCTION.matcher(text);
        while (matcher.find()) {
            local.add(matcher.group(1));
        }
        return local;
    }

    private static boolean isAllowedNonWrapper(@NotNull String symbol) {
        if (symbol.equals("godot_Nil")
                || symbol.equals("godot_TypedArray")
                || symbol.equals("godot_TypedDictionary")
                || symbol.equals("godot_inf")) {
            return true;
        }
        if (symbol.equals("godot_bool")
                || symbol.equals("godot_int")
                || symbol.equals("godot_float")
                || symbol.equals("godot_real_t")) {
            return true;
        }
        if (symbol.matches("godot_[A-Z][A-Za-z0-9]*")) {
            return true;
        }
        if (symbol.matches("godot_[A-Z0-9_]+")) {
            return true;
        }
        return symbol.endsWith("_") || symbol.contains("__");
    }
}
