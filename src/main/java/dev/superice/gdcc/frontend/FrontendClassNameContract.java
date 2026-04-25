package dev.superice.gdcc.frontend;

import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Shared frontend class-name contract.
///
/// `__sub__` is a gdcc-owned reserved sequence. Source-side guard rails, injected top-level
/// canonical mapping, and inner canonical derivation all read this one fact so source/canonical
/// spaces stay disjoint.
public final class FrontendClassNameContract {
    public static final String INNER_CLASS_CANONICAL_SEPARATOR = "__sub__";

    private FrontendClassNameContract() {
    }

    public static boolean containsReservedSequence(@NotNull String name) {
        Objects.requireNonNull(name, "name must not be null");
        return name.contains(INNER_CLASS_CANONICAL_SEPARATOR);
    }

    /// Derives the implicit top-level source class name used for scripts without `class_name`.
    /// Keeping this rule here lets CLI prefix expansion and semantic skeleton discovery share one
    /// filename contract instead of drifting through duplicated string logic.
    public static @NotNull String deriveDefaultTopLevelSourceName(@NotNull Path sourcePath) {
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        var fileName = sourcePath.getFileName() != null ? sourcePath.getFileName().toString() : "script";
        var extensionIndex = fileName.lastIndexOf('.');
        var baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        var tokens = baseName.split("[^A-Za-z0-9]+");

        var classNameBuilder = new StringBuilder();
        for (var token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            classNameBuilder.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                classNameBuilder.append(token.substring(1));
            }
        }

        if (classNameBuilder.isEmpty()) {
            classNameBuilder.append("Script");
        }
        if (!Character.isJavaIdentifierStart(classNameBuilder.charAt(0))) {
            classNameBuilder.insert(0, "Gd");
        }
        for (var index = 1; index < classNameBuilder.length(); index++) {
            var currentChar = classNameBuilder.charAt(index);
            if (!Character.isJavaIdentifierPart(currentChar)) {
                classNameBuilder.setCharAt(index, '_');
            }
        }
        return classNameBuilder.toString();
    }

    /// Mapping injection is a public frontend boundary, so invalid reserved-sequence entries must
    /// fail fast here instead of leaking into later registry/backend identity paths.
    public static @NotNull Map<String, String> freezeTopLevelCanonicalNameMap(
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        Objects.requireNonNull(topLevelCanonicalNameMap, "topLevelCanonicalNameMap must not be null");

        var frozenEntries = new LinkedHashMap<String, String>(topLevelCanonicalNameMap.size());
        for (var entry : topLevelCanonicalNameMap.entrySet()) {
            var sourceName = requireNoReservedSequence(
                    Objects.requireNonNull(entry.getKey(), "topLevelCanonicalNameMap key must not be null"),
                    "topLevelCanonicalNameMap key"
            );
            var canonicalName = requireNoReservedSequence(
                    Objects.requireNonNull(entry.getValue(), "topLevelCanonicalNameMap value must not be null"),
                    "topLevelCanonicalNameMap value"
            );
            frozenEntries.put(sourceName, canonicalName);
        }
        return Collections.unmodifiableMap(frozenEntries);
    }

    public static @NotNull String requireNoReservedSequence(@NotNull String name, @NotNull String label) {
        var nonBlankName = StringUtil.requireNonBlank(name, label);
        if (containsReservedSequence(nonBlankName)) {
            throw new IllegalArgumentException(
                    label + " must not contain reserved gdcc class-name sequence '"
                            + INNER_CLASS_CANONICAL_SEPARATOR + "'"
            );
        }
        return nonBlankName;
    }
}
