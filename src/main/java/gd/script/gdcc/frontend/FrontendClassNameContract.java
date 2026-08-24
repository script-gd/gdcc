package gd.script.gdcc.frontend;

import gd.script.gdcc.util.StringUtil;
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
///
/// `_gdcc_coro_state_` is the compiler-owned class-level prefix of hidden coroutine state classes
/// (canonical derivation formula `_gdcc_coro_state_<canonicalClass>__coro__<func>`, contract:
/// `doc/module_impl/frontend/gdcc_facing_class_name_contract.md` §1.3); `__coro__` is the reserved
/// separator inside that formula and is rejected at the same input boundaries as `__sub__`.
public final class FrontendClassNameContract {
    public static final String INNER_CLASS_CANONICAL_SEPARATOR = "__sub__";
    public static final String CORO_STATE_CLASS_PREFIX = "_gdcc_coro_state_";
    public static final String CORO_STATE_CLASS_SEPARATOR = "__coro__";

    private FrontendClassNameContract() {
    }

    public static boolean containsReservedSequence(@NotNull String name) {
        return reservedSequenceOrNull(name) != null;
    }

    /// Returns the first reserved sequence found in `name` (`__sub__` or `__coro__`), or null.
    /// Function names are NOT subject to this check: the `__coro__` split point of the state-class
    /// formula is uniquely determined by the class side not containing it (contract §1.3 rule 2).
    public static String reservedSequenceOrNull(@NotNull String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.contains(INNER_CLASS_CANONICAL_SEPARATOR)) {
            return INNER_CLASS_CANONICAL_SEPARATOR;
        }
        if (name.contains(CORO_STATE_CLASS_SEPARATOR)) {
            return CORO_STATE_CLASS_SEPARATOR;
        }
        return null;
    }

    /// Class-level reserved prefix for compiler-generated coroutine state classes; user-declared
    /// class sourceNames must never start with it (contract §1.3 rule 1).
    public static boolean startsWithCoroStateClassPrefix(@NotNull String name) {
        Objects.requireNonNull(name, "name must not be null");
        return name.startsWith(CORO_STATE_CLASS_PREFIX);
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
        var reservedSequence = reservedSequenceOrNull(nonBlankName);
        if (reservedSequence != null) {
            throw new IllegalArgumentException(
                    label + " must not contain reserved gdcc class-name sequence '" + reservedSequence + "'"
            );
        }
        return nonBlankName;
    }
}
