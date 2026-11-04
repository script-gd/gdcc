package gd.script.gdcc.cli;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// Ordered read-modify-write model of a `.gdextension` descriptor (`bin/gdcc.gdextension`).
///
/// The model sits on top of [GodotConfigFile]: same-name sections fold into one and duplicate
/// keys collapse to their last assignment, mirroring how Godot's `ConfigFile` loads documents.
/// Entries that GDCC does not touch keep their exact source text ([GodotConfigFile.Value.raw]),
/// so unrelated sections and keys survive a publish round-trip unchanged.
///
/// [render] emits the byte layout `ConfigFile.save` produces — `[section]` headers followed by a
/// blank line, `key=value` pairs without padding, blank lines between sections — so descriptor
/// files stay interchangeable with what the editor addon writes.
final class GdccExtensionDescriptor {
    private final @NotNull LinkedHashMap<String, LinkedHashMap<String, GodotConfigFile.Value>> sections =
            new LinkedHashMap<>();

    private GdccExtensionDescriptor() {
    }

    static @NotNull GdccExtensionDescriptor createEmpty() {
        return new GdccExtensionDescriptor();
    }

    static @NotNull GdccExtensionDescriptor fromConfig(@NotNull GodotConfigFile file) {
        var descriptor = new GdccExtensionDescriptor();
        for (var section : file.sections()) {
            var entries = descriptor.sections.computeIfAbsent(section.name(), _ -> new LinkedHashMap<>());
            for (var entry : section.entries()) {
                entries.put(entry.key(), entry.value());
            }
        }
        return descriptor;
    }

    /// Last-assignment value of `key` inside `section`, or null when absent.
    @Nullable GodotConfigFile.Value get(@NotNull String section, @NotNull String key) {
        var entries = sections.get(section);
        return entries == null ? null : entries.get(key);
    }

    /// Entries of `section` in file order (empty when the section does not exist).
    @NotNull List<Map.Entry<String, GodotConfigFile.Value>> entries(@NotNull String section) {
        var entries = sections.get(section);
        return entries == null ? List.of() : new ArrayList<>(entries.entrySet());
    }

    /// Sets `key` under `section`, updating the existing position when present and appending
    /// otherwise; the section itself is appended when new — both mirroring `ConfigFile.set_value`.
    void set(@NotNull String section, @NotNull String key, @NotNull GodotConfigFile.Value value) {
        sections.computeIfAbsent(section, _ -> new LinkedHashMap<>()).put(key, value);
    }

    void remove(@NotNull String section, @NotNull String key) {
        var entries = sections.get(section);
        if (entries != null) {
            entries.remove(key);
        }
    }

    /// Serializes in `ConfigFile.save` layout: sections separated by blank lines, `[name]` + a
    /// blank line for named sections, `key=value` without padding. Trailing newline included.
    @NotNull String render() {
        var text = new StringBuilder();
        var first = true;
        for (var section : sections.entrySet()) {
            if (!first) {
                text.append('\n');
            }
            first = false;
            if (!section.getKey().isEmpty()) {
                text.append('[').append(encodeSectionName(section.getKey())).append("]\n\n");
            }
            for (var entry : section.getValue().entrySet()) {
                text.append(encodeKey(entry.getKey())).append('=').append(entry.getValue().raw()).append('\n');
            }
        }
        return text.toString();
    }

    /// `ConfigFile` escapes `]` inside section names when saving.
    private static @NotNull String encodeSectionName(@NotNull String name) {
        return name.replace("]", "\\]");
    }

    /// Port of `String::property_name_encode`: plain ASCII keys stay bare; keys containing
    /// `=` `"` `;` `[` `]` or non-printable/non-ASCII characters are written quoted with only
    /// `\` and `"` escaped (Godot's multiline-safe form).
    private static @NotNull String encodeKey(@NotNull String key) {
        for (var i = 0; i < key.length(); i++) {
            var c = key.charAt(i);
            if (c == '=' || c == '"' || c == ';' || c == '[' || c == ']' || c < 33 || c > 126) {
                return "\"" + cEscapeMultiline(key) + "\"";
            }
        }
        return key;
    }

    /// `String::c_escape` — the escape set `VariantWriter` uses for quoted string values.
    private static @NotNull String cEscape(@NotNull String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\u0007", "\\a")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\u000B", "\\v")
                .replace("'", "\\'")
                .replace("\"", "\\\"");
    }

    /// `String::c_escape_multiline` — used by `property_name_encode` for quoted keys.
    private static @NotNull String cEscapeMultiline(@NotNull String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static @NotNull GodotConfigFile.Value stringValue(@NotNull String text) {
        return new GodotConfigFile.StringValue("\"" + cEscape(text) + "\"", text);
    }

    static @NotNull GodotConfigFile.Value boolValue(boolean flag) {
        return new GodotConfigFile.BoolValue(flag ? "true" : "false", flag);
    }

    /// `PackedStringArray("a", "b")` — the form `ConfigFile` serializes string arrays to.
    static @NotNull GodotConfigFile.Value packedStringArrayValue(@NotNull List<String> items) {
        var raw = items.isEmpty()
                ? "PackedStringArray()"
                : items.stream()
                        .map(item -> "\"" + cEscape(item) + "\"")
                        .collect(Collectors.joining(", ", "PackedStringArray(", ")"));
        return new GodotConfigFile.StringArrayValue(raw, List.copyOf(items));
    }
}
