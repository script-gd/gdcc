package gd.script.gdcc.cli;

import gd.script.gdcc.exception.GodotConfigException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Parser and ordered model for Godot `ConfigFile` text (`project.godot`, `.gdextension`).
///
/// The grammar mirrors what Godot's ConfigFile reads and writes: `[section]` headers,
/// `key = value` assignments (the `=` padding is optional), `;` line comments, and Variant-typed
/// values. Values GDCC consumes are decoded eagerly — quoted strings (with Godot escapes,
/// including multi-line literals), bools, integers, floats, and string arrays in either
/// `PackedStringArray("a", "b")` or `["a", "b"]` form. Every other well-formed value (vectors,
/// colors, dictionaries, `ExtResource`-style calls, bare identifiers, …) is kept as
/// [Value.OpaqueValue] holding the exact source text, so unrelated settings survive a
/// read-modify-write round-trip untouched.
///
/// Malformed syntax (unterminated strings, unclosed containers, keys without `=`, trailing
/// garbage after a value) is rejected with [GodotConfigException]: silently guessing a value
/// would risk building with wrong settings.
final class GodotConfigFile {
    private final @NotNull List<Section> sections;

    private GodotConfigFile(@NotNull List<Section> sections) {
        this.sections = sections;
    }

    /// Parses `text` as a Godot config document; `origin` labels errors (usually the file path).
    static @NotNull GodotConfigFile parse(@NotNull String text, @NotNull String origin) {
        return new Parser(text, origin).parse();
    }

    /// Sections in file order, including the leading unnamed `""` section for keys written before
    /// the first header (e.g. `config_version=5`).
    @NotNull List<Section> sections() {
        return sections;
    }

    /// The last `key` entry under `section`, mirroring Godot's last-assignment-wins behavior.
    @NotNull Optional<Entry> findEntry(@NotNull String section, @NotNull String key) {
        Entry found = null;
        for (var candidate : sections) {
            if (!candidate.name().equals(section)) {
                continue;
            }
            for (var entry : candidate.entries()) {
                if (entry.key().equals(key)) {
                    found = entry;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    /// True when `section` holds a key starting with `keyPrefix`, e.g. a Godot feature-override
    /// (`config/name.web`) sharing the consumed key's prefix (`config/name.`).
    boolean hasKeysWithPrefix(@NotNull String section, @NotNull String keyPrefix) {
        for (var candidate : sections) {
            if (!candidate.name().equals(section)) {
                continue;
            }
            for (var entry : candidate.entries()) {
                if (entry.key().startsWith(keyPrefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    record Section(@NotNull String name, @NotNull List<Entry> entries) {
    }

    /// One `key = value` assignment. `value().raw()` is the exact source text of the value, so a
    /// rewritten document can re-emit unrecognized values verbatim.
    record Entry(@NotNull String key, @NotNull Value value) {
    }

    sealed interface Value {
        /// Exact source text of the value (quotes, escapes and container delimiters included).
        @NotNull String raw();
    }

    record StringValue(@NotNull String raw, @NotNull String text) implements Value {
    }

    record BoolValue(@NotNull String raw, boolean flag) implements Value {
    }

    record IntValue(@NotNull String raw, long number) implements Value {
    }

    record FloatValue(@NotNull String raw, double number) implements Value {
    }

    /// A `PackedStringArray("a", "b")` call or a `["a", "b"]` array literal whose elements are all
    /// strings — the two spellings `gdcc/sync/excluded_globs` legitimately takes.
    record StringArrayValue(@NotNull String raw, @NotNull List<String> items) implements Value {
    }

    /// Any other syntactically valid value GDCC does not decode; carried only through `raw()`.
    record OpaqueValue(@NotNull String raw) implements Value {
    }

    private static final class Parser {
        private final @NotNull String text;
        private final @NotNull String origin;
        private int cursor;
        private int containerDepth;

        Parser(@NotNull String text, @NotNull String origin) {
            this.text = text;
            this.origin = origin;
        }

        @NotNull GodotConfigFile parse() {
            var sections = new ArrayList<Section>();
            var currentEntries = new ArrayList<Entry>();
            sections.add(new Section("", currentEntries));
            while (true) {
                skipTrivia();
                if (eof()) {
                    return new GodotConfigFile(sections);
                }
                if (peek() == '[') {
                    var name = parseSectionHeader();
                    currentEntries = new ArrayList<>();
                    sections.add(new Section(name, currentEntries));
                } else {
                    currentEntries.add(parseAssignment());
                }
            }
        }

        /// Whitespace/newlines plus `;`-to-end-of-line comments between items.
        private void skipTrivia() {
            while (!eof()) {
                var c = peek();
                switch (c) {
                    case ' ', '\t', '\n', '\r' -> cursor++;
                    case ';' -> skipComment();
                    default -> {
                        return;
                    }
                }
            }
        }

        private void skipComment() {
            while (!eof() && peek() != '\n') {
                cursor++;
            }
        }

        /// Whitespace inside a value expression: newlines are insignificant within container
        /// literals (and after `=` for leniency), matching Variant-parser behavior.
        private void skipValueWhitespace() {
            while (!eof()) {
                var c = peek();
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    cursor++;
                } else {
                    return;
                }
            }
        }

        /// Same-line whitespace only; used where a newline still terminates an entry.
        private void skipHorizontalWhitespace() {
            while (!eof() && (peek() == ' ' || peek() == '\t')) {
                cursor++;
            }
        }

        private @NotNull String parseSectionHeader() {
            cursor++; // consume '['
            var start = cursor;
            while (!eof() && peek() != ']' && peek() != '\n' && peek() != '\r') {
                cursor++;
            }
            if (eof() || peek() != ']') {
                throw fail("unterminated section header");
            }
            var name = text.substring(start, cursor).trim();
            cursor++; // consume ']'
            expectEndOfEntry();
            return name;
        }

        private @NotNull Entry parseAssignment() {
            var keyStart = cursor;
            while (!eof()) {
                var c = peek();
                if (c == '=') {
                    break;
                }
                if (c == '\n' || c == '\r' || c == '[' || c == ';') {
                    throw fail("expected '=' after key '" + text.substring(keyStart, cursor).trim() + "'");
                }
                cursor++;
            }
            if (eof()) {
                throw fail("expected '=' after key '" + text.substring(keyStart, cursor).trim() + "'");
            }
            var key = text.substring(keyStart, cursor).trim();
            if (key.isEmpty()) {
                throw fail("empty key before '='");
            }
            cursor++; // consume '='
            var value = parseValue();
            expectEndOfEntry();
            return new Entry(key, value);
        }

        /// After a section header or value only horizontal space and an optional comment may
        /// precede the line end — anything else is malformed config syntax.
        private void expectEndOfEntry() {
            while (!eof() && (peek() == ' ' || peek() == '\t')) {
                cursor++;
            }
            if (eof()) {
                return;
            }
            var c = peek();
            if (c == ';') {
                skipComment();
                return;
            }
            if (c == '\n' || c == '\r') {
                cursor++;
                return;
            }
            throw fail("unexpected content after value");
        }

        private @NotNull Value parseValue() {
            skipValueWhitespace();
            if (eof()) {
                throw fail("missing value");
            }
            var start = cursor;
            var c = peek();
            var value = switch (valueKind(c)) {
                case STRING -> parseString(start);
                case PREFIXED_LITERAL -> {
                    cursor++; // consume '&'/'^'/'@'
                    parseString(start);
                    yield new OpaqueValue(raw(start));
                }
                case ARRAY -> parseContainer(start, '[', ']');
                case DICTIONARY -> parseDictionary(start);
                case CALL_OR_IDENTIFIER -> parseIdentifierOrCall(start);
                case SCALAR -> parseScalar(start);
                case NONE -> throw fail("unexpected character '" + c + "' where a value is required");
            };
            return value;
        }

        private @NotNull ValueKind valueKind(char c) {
            if (c == '"') {
                return ValueKind.STRING;
            }
            // Godot serializes NodePath/RID-style literals as `&"x"`, `^"x"`, `@"x"`.
            if ((c == '&' || c == '^' || c == '@') && peekAt(cursor + 1) == '"') {
                return ValueKind.PREFIXED_LITERAL;
            }
            if (c == '[') {
                return ValueKind.ARRAY;
            }
            if (c == '{') {
                return ValueKind.DICTIONARY;
            }
            if (isIdentifierStart(c)) {
                return ValueKind.CALL_OR_IDENTIFIER;
            }
            if (isScalarStart(c)) {
                return ValueKind.SCALAR;
            }
            return ValueKind.NONE;
        }

        private enum ValueKind {
            STRING,
            PREFIXED_LITERAL,
            ARRAY,
            DICTIONARY,
            CALL_OR_IDENTIFIER,
            SCALAR,
            NONE
        }

        /// Quoted string; literal newlines inside quotes are valid (multi-line values), so an
        /// unterminated literal is only discovered at EOF — report its opening line instead.
        private @NotNull StringValue parseString(int start) {
            cursor++; // consume '"'
            var decoded = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw failAt(start, "unterminated string literal");
                }
                var c = text.charAt(cursor++);
                if (c == '"') {
                    return new StringValue(raw(start), decoded.toString());
                }
                if (c == '\\') {
                    decodeEscape(decoded, start);
                } else {
                    decoded.append(c);
                }
            }
        }

        /// Godot escape sequences: standard single-char escapes, x-hex (up to 2 digits),
        /// u (4 hex digits), U (8) and octal (up to 3 digits). Unknown escapes keep the bare
        /// char, matching `String::unescape` leniency.
        private void decodeEscape(@NotNull StringBuilder decoded, int stringStart) {
            if (eof()) {
                throw failAt(stringStart, "unterminated string literal");
            }
            var c = text.charAt(cursor++);
            switch (c) {
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'v' -> decoded.append('\u000B');
                case 'a' -> decoded.append('\u0007');
                case '\\', '"', '\'', '?' -> decoded.append(c);
                case 'x' -> decoded.appendCodePoint(parseHexDigits(2, false));
                case 'u' -> decoded.appendCodePoint(parseHexDigits(4, true));
                case 'U' -> decoded.appendCodePoint(parseHexDigits(8, true));
                default -> {
                    if (c >= '0' && c <= '7') {
                        decoded.appendCodePoint(parseOctalDigits(c));
                    } else {
                        decoded.append(c);
                    }
                }
            }
        }

        private int parseHexDigits(int maxDigits, boolean exact) {
            var digits = new StringBuilder();
            while (!eof() && digits.length() < maxDigits && isHexDigit(peek())) {
                digits.append(text.charAt(cursor++));
            }
            if (digits.isEmpty() || (exact && digits.length() != maxDigits)) {
                throw fail("invalid unicode escape sequence");
            }
            return Integer.parseInt(digits.toString(), 16);
        }

        private int parseOctalDigits(char first) {
            var digits = new StringBuilder().append(first);
            while (!eof() && digits.length() < 3 && peek() >= '0' && peek() <= '7') {
                digits.append(text.charAt(cursor++));
            }
            return Integer.parseInt(digits.toString(), 8);
        }

        private static boolean isHexDigit(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        /// `[v1, v2, ...]`; becomes [StringArrayValue] only when every element is a string —
        /// anything else stays opaque so type-strict consumers can reject it.
        private @NotNull Value parseContainer(int start, char open, char close) {
            var elements = parseElementList(open, close, false, start);
            if (elements.stream().allMatch(StringValue.class::isInstance)) {
                var items = elements.stream().map(element -> ((StringValue) element).text()).toList();
                return new StringArrayValue(raw(start), items);
            }
            return new OpaqueValue(raw(start));
        }

        /// `Type(arg, ...)` or a bare identifier/`true`/`false`. Constructor calls other than
        /// `PackedStringArray` stay opaque: decoding them needs the full Variant type system and
        /// no consumed setting uses them.
        private @NotNull Value parseIdentifierOrCall(int start) {
            var identifier = readIdentifier();
            // A `(` on the same line starts a call (`Type (...)`), but a newline at entry level
            // ends the assignment: `x=true` above a `name(...)` key line is not a call. Inside
            // container literals newlines stay insignificant.
            if (containerDepth == 0) {
                skipHorizontalWhitespace();
            } else {
                skipValueWhitespace();
            }
            // Typed containers serialize as `Array[int](...)`: the bracketed type sits between
            // the identifier and the argument list. Always opaque — a `[...]` suffix must also
            // poison the identifier path, so `true[false]` cannot smuggle a BoolValue into the
            // consumed settings.
            var typedSuffix = false;
            if (!eof() && peek() == '[') {
                parseElementList('[', ']', false, cursor);
                typedSuffix = true;
                if (containerDepth == 0) {
                    skipHorizontalWhitespace();
                } else {
                    skipValueWhitespace();
                }
            }
            if (!eof() && peek() == '(') {
                var elements = parseElementList('(', ')', true, start);
                if (!typedSuffix && "PackedStringArray".equals(identifier)
                        && elements.stream().allMatch(StringValue.class::isInstance)) {
                    var items = elements.stream().map(element -> ((StringValue) element).text()).toList();
                    return new StringArrayValue(raw(start), items);
                }
                return new OpaqueValue(raw(start));
            }
            if (typedSuffix) {
                return new OpaqueValue(raw(start));
            }
            return switch (identifier) {
                case "true" -> new BoolValue(raw(start), true);
                case "false" -> new BoolValue(raw(start), false);
                default -> new OpaqueValue(raw(start));
            };
        }

        /// Elements of a `[...]` array or `(...)` call arguments; trailing commas tolerated.
        /// `allowNamed` permits Godot's serialized `Object(Type,"prop":value, ...)` form inside
        /// call parens — a `"k":v` pair yields an opaque element so string-typed containers can
        /// never silently decode a named argument as a plain element. `openStart` anchors
        /// unterminated errors at the opening delimiter's line.
        private @NotNull List<Value> parseElementList(char open, char close, boolean allowNamed, int openStart) {
            cursor++; // consume open
            containerDepth++;
            try {
                var elements = new ArrayList<Value>();
                while (true) {
                    skipValueWhitespace();
                    if (eof()) {
                        throw failAt(openStart, "unterminated '" + open + "' expression");
                    }
                    if (peek() == close) {
                        cursor++;
                        return elements;
                    }
                    var element = parseValue();
                    skipValueWhitespace();
                    if (!eof() && peek() == ':') {
                        if (!allowNamed) {
                            throw fail("unexpected ':' inside '" + open + "' expression");
                        }
                        cursor++;
                        parseValue();
                        element = new OpaqueValue(":");
                    }
                    elements.add(element);
                    skipValueWhitespace();
                    if (eof()) {
                        throw failAt(openStart, "unterminated '" + open + "' expression");
                    }
                    if (peek() == ',') {
                        cursor++;
                        continue;
                    }
                    if (peek() == close) {
                        cursor++;
                        return elements;
                    }
                    throw fail("expected ',' or '" + close + "' inside '" + open + "' expression");
                }
            } finally {
                containerDepth--;
            }
        }

        private @NotNull Value parseDictionary(int start) {
            cursor++; // consume '{'
            containerDepth++;
            try {
                while (true) {
                    skipValueWhitespace();
                    if (eof()) {
                        throw failAt(start, "unterminated '{' expression");
                    }
                    if (peek() == '}') {
                        cursor++;
                        return new OpaqueValue(raw(start));
                    }
                    parseValue();
                    skipValueWhitespace();
                    if (eof() || peek() != ':') {
                        throw fail("expected ':' inside '{' expression");
                    }
                    cursor++;
                    parseValue();
                    skipValueWhitespace();
                    if (!eof() && peek() == ',') {
                        cursor++;
                        continue;
                    }
                    if (!eof() && peek() == '}') {
                        cursor++;
                        return new OpaqueValue(raw(start));
                    }
                    throw fail("expected ',' or '}' inside '{' expression");
                }
            } finally {
                containerDepth--;
            }
        }

        /// Numbers (and number-looking tokens) end at structural delimiters; anything not cleanly
        /// parseable as int64/double stays opaque instead of being silently truncated.
        private @NotNull Value parseScalar(int start) {
            while (!eof() && !isScalarTerminator(peek())) {
                cursor++;
            }
            var token = text.substring(start, cursor);
            var number = parseNumber(token);
            if (number != null) {
                return number;
            }
            return new OpaqueValue(raw(start));
        }

        private @Nullable Value parseNumber(@NotNull String token) {
            try {
                if (token.matches("[+-]?0x[0-9a-fA-F]+")) {
                    var negative = token.startsWith("-");
                    var digits = token.replaceFirst("^[+-]?0x", "");
                    return new IntValue(token, negative
                            ? -Long.parseUnsignedLong(digits, 16)
                            : Long.parseUnsignedLong(digits, 16));
                }
                if (token.matches("[+-]?0b[01]+")) {
                    var negative = token.startsWith("-");
                    var digits = token.replaceFirst("^[+-]?0b", "");
                    return new IntValue(token, negative ? -Long.parseLong(digits, 2) : Long.parseLong(digits, 2));
                }
                if (token.matches("[+-]?[0-9]+")) {
                    return new IntValue(token, Long.parseLong(token));
                }
                if (token.matches("[+-]?([0-9]+\\.[0-9]*|\\.[0-9]+|[0-9]+[eE][+-]?[0-9]+|[0-9]+\\.[0-9]*[eE][+-]?[0-9]+)")) {
                    return new FloatValue(token, Double.parseDouble(token));
                }
            } catch (NumberFormatException _) {
                // Oversized literals stay opaque — GDCC consumes no numeric settings that large.
            }
            return null;
        }

        private static boolean isScalarTerminator(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                    || c == ',' || c == ')' || c == ']' || c == '}' || c == ':' || c == ';';
        }

        private @NotNull String readIdentifier() {
            var start = cursor;
            while (!eof() && isIdentifierPart(peek())) {
                cursor++;
            }
            return text.substring(start, cursor);
        }

        private static boolean isIdentifierStart(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }

        private static boolean isIdentifierPart(char c) {
            return isIdentifierStart(c) || (c >= '0' && c <= '9');
        }

        private static boolean isScalarStart(char c) {
            return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.';
        }

        private @NotNull String raw(int start) {
            return text.substring(start, cursor);
        }

        private boolean eof() {
            return cursor >= text.length();
        }

        private char peek() {
            return text.charAt(cursor);
        }

        private char peekAt(int position) {
            return position < text.length() ? text.charAt(position) : ' ';
        }

        private @NotNull GodotConfigException fail(@NotNull String message) {
            return failAt(cursor, message);
        }

        /// Errors that only surface at EOF (unterminated constructs) point at the construct's
        /// opening position instead of the end of file.
        private @NotNull GodotConfigException failAt(int position, @NotNull String message) {
            return new GodotConfigException(origin + ":" + lineAt(position) + ": " + message);
        }

        private int lineAt(int position) {
            var line = 1;
            for (var index = 0; index < position; index++) {
                if (text.charAt(index) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }
}
