package gd.script.gdcc.backend.c.gen.binding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Generates the fixed Godot interface lookup layer from Godot's exported C ABI header.
///
/// The generated wrapper identity comes from `@name`; the following typedef only supplies the
/// C function pointer type and ABI signature. This preserves upstream spelling quirks while
/// keeping lookup names aligned with Godot's proc-address table.
final class GodotInterfaceGenerator {
    private static final @NotNull Pattern NAME_PATTERN =
            Pattern.compile("\\*\\s*@name\\s+([A-Za-z0-9_]+)\\b");
    private static final @NotNull Pattern SINCE_PATTERN =
            Pattern.compile("\\*\\s*@since\\s+(\\S+)");
    private static final @NotNull Pattern FUNCTION_TYPEDEF_PATTERN = Pattern.compile(
            "typedef\\s+(.+?)\\s*\\(\\s*\\*\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*\\((.*)\\)\\s*;",
            Pattern.DOTALL
    );
    private static final @NotNull Pattern PARAMETER_NAME_PATTERN =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\[[^]]*])?\\s*$");
    private static final @NotNull Pattern WHITESPACE_PATTERN =
            Pattern.compile("\\s+");
    private static final @NotNull Pattern NON_C_IDENTIFIER_CHAR_PATTERN =
            Pattern.compile("[^A-Za-z0-9_]");
    private static final @NotNull Pattern UNDERSCORE_RUN_PATTERN =
            Pattern.compile("_+");

    private GodotInterfaceGenerator() {
    }

    static void generateInterfaceSupport(@NotNull Path header, @NotNull Path out) throws IOException {
        Objects.requireNonNull(header);
        Objects.requireNonNull(out);
        Files.createDirectories(out);
        for (var entry : renderInterfaceSupport(header).entrySet()) {
            Files.writeString(out.resolve(entry.getKey()), entry.getValue());
        }
    }

    static @NotNull Map<String, String> renderInterfaceSupport(@NotNull Path header) throws IOException {
        Objects.requireNonNull(header);
        return renderInterfaceSupport(Files.readString(header));
    }

    static @NotNull Map<String, String> renderInterfaceSupport(@NotNull String header) {
        Objects.requireNonNull(header);
        var functions = parseInterfaceFunctions(header);
        var files = new LinkedHashMap<String, String>();
        files.put("godot_interface.h", renderInterfaceHeader(functions));
        files.put("godot_interface.c", renderInterfaceSource(functions));
        return files;
    }

    static @NotNull Set<String> collectWrapperNames(@NotNull Path header) throws IOException {
        Objects.requireNonNull(header);
        var names = new LinkedHashSet<String>();
        for (var function : parseInterfaceFunctions(Files.readString(header))) {
            names.add(function.wrapperName());
        }
        return Set.copyOf(names);
    }

    static void generateBindingSupport(@NotNull Path out) throws IOException {
        Objects.requireNonNull(out);
        Files.createDirectories(out);
        for (var entry : renderBindingSupport().entrySet()) {
            Files.writeString(out.resolve(entry.getKey()), entry.getValue());
        }
    }

    static @NotNull Map<String, String> renderBindingSupport() {
        var files = new LinkedHashMap<String, String>();
        files.put("godot_binding.h", renderBindingHeader());
        files.put("godot_binding.c", renderBindingSource());
        return files;
    }

    static @NotNull List<InterfaceFunction> parseInterfaceFunctions(@NotNull String header) {
        Objects.requireNonNull(header);
        var functions = new ArrayList<InterfaceFunction>();
        PendingInterface pending = null;
        var lines = header.split("\\R", -1);
        for (var index = 0; index < lines.length; index++) {
            var line = lines[index];
            var lineNumber = index + 1;
            var nameMatcher = NAME_PATTERN.matcher(line);
            if (nameMatcher.find()) {
                if (pending != null) {
                    throw new IllegalStateException(
                            "Missing interface typedef for @name '" + pending.name()
                                    + "' before line " + lineNumber
                    );
                }
                pending = new PendingInterface(nameMatcher.group(1), null, lineNumber);
                continue;
            }
            if (pending == null) {
                continue;
            }
            var sinceMatcher = SINCE_PATTERN.matcher(line);
            if (sinceMatcher.find()) {
                pending = new PendingInterface(pending.name(), sinceMatcher.group(1), pending.lineNumber());
            }
            if (line.stripLeading().startsWith("typedef ")) {
                var typedef = new StringBuilder(line.strip());
                while (!typedef.toString().contains(";")) {
                    index++;
                    if (index >= lines.length) {
                        throw new IllegalStateException(
                                "Unterminated interface typedef for @name '" + pending.name() + "'"
                        );
                    }
                    typedef.append(' ').append(lines[index].strip());
                }
                functions.add(parseFunctionTypedef(pending, typedef.toString()));
                pending = null;
            }
        }
        if (pending != null) {
            throw new IllegalStateException("Missing interface typedef for @name '" + pending.name() + "'");
        }
        return validateInterfaceFunctions(functions);
    }

    private static @NotNull List<InterfaceFunction> validateInterfaceFunctions(
            @NotNull List<InterfaceFunction> functions
    ) {
        var seenLookupNames = new LinkedHashSet<String>();
        var seenWrapperNames = new LinkedHashMap<String, InterfaceFunction>();
        for (var function : functions) {
            if (!seenLookupNames.add(function.lookupName())) {
                throw new IllegalStateException("Duplicate Godot interface @name: " + function.lookupName());
            }
            var previous = seenWrapperNames.putIfAbsent(function.wrapperName(), function);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate Godot interface wrapper name '" + function.wrapperName()
                                + "' for @name '" + previous.lookupName()
                                + "' and '" + function.lookupName() + "'"
                );
            }
        }
        return List.copyOf(functions);
    }

    private static @NotNull InterfaceFunction parseFunctionTypedef(
            @NotNull PendingInterface pending,
            @NotNull String rawTypedef
    ) {
        var matcher = FUNCTION_TYPEDEF_PATTERN.matcher(rawTypedef);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "Expected function pointer typedef after @name '" + pending.name()
                            + "', got: " + normalizeWhitespace(rawTypedef)
            );
        }
        var returnType = normalizeWhitespace(matcher.group(1));
        var typedefName = matcher.group(2);
        var parameters = normalizeWhitespace(matcher.group(3));
        var parameterList = splitParameters(parameters);
        var argumentNames = new ArrayList<String>();
        if (!(parameterList.size() == 1 && parameterList.getFirst().equals("void"))) {
            for (var parameter : parameterList) {
                argumentNames.add(parseParameterName(parameter, pending.name()));
            }
        }
        return new InterfaceFunction(
                pending.name(),
                pending.since(),
                typedefName,
                returnType,
                parameterList,
                List.copyOf(argumentNames)
        );
    }

    private static @NotNull List<String> splitParameters(@NotNull String parameters) {
        if (parameters.isBlank()) {
            return List.of("void");
        }
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        var parenDepth = 0;
        var bracketDepth = 0;
        for (var index = 0; index < parameters.length(); index++) {
            var ch = parameters.charAt(index);
            switch (ch) {
                case '(' -> parenDepth++;
                case ')' -> parenDepth--;
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth--;
                case ',' -> {
                    if (parenDepth == 0 && bracketDepth == 0) {
                        result.add(normalizeWhitespace(current.toString()));
                        current.setLength(0);
                        continue;
                    }
                }
                default -> {
                }
            }
            current.append(ch);
        }
        result.add(normalizeWhitespace(current.toString()));
        return List.copyOf(result);
    }

    private static @NotNull String parseParameterName(@NotNull String parameter, @NotNull String lookupName) {
        var matcher = PARAMETER_NAME_PATTERN.matcher(parameter);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Cannot parse parameter name for interface @name '" + lookupName + "': " + parameter
            );
        }
        return matcher.group(1);
    }

    private static @NotNull String renderInterfaceHeader(@NotNull List<InterfaceFunction> functions) {
        var out = new StringBuilder(generatedPreamble("GDCC_GODOT_INTERFACE_H"));
        out.append("""

                #include <godot_abi.h>

                #ifdef __cplusplus
                extern "C" {
                #endif

                /*
                 * Shared lookup-miss context. NULL strings and an empty hash span mean the field
                 * is not applicable for that wrapper kind; they are not fallback lookup values.
                 * Leave suppress_internal_error zeroed for the default Godot/stderr diagnostic.
                 */
                typedef struct gdcc_binding_lookup_context {
                    const char *kind;
                    const char *function_name;
                    const char *lookup_name;
                    const char *owner;
                    const char *type;
                    GDExtensionBool has_primary_hash;
                    GDExtensionInt primary_hash;
                    const GDExtensionInt *compatibility_hashes;
                    GDExtensionInt compatibility_hash_count;
                    GDExtensionBool suppress_internal_error;
                } gdcc_binding_lookup_context;

                GDCC_GODOT_DECL GDExtensionBool godot_initialize_interface(GDExtensionInterfaceGetProcAddress get_proc_address);
                GDCC_GODOT_DECL GDExtensionBool gdcc_binding_lookup_fail(
                        const gdcc_binding_lookup_context *context);

                """);
        for (var function : functions) {
            out.append("GDCC_GODOT_DECL extern ")
                    .append(function.typedefName())
                    .append(' ')
                    .append(function.cacheName())
                    .append(";\n");
        }
        out.append("""

                /*
                 * Each wrapper is header-local code, but it reads the single shared pointer table
                 * defined in godot_interface.c. Do not move gdcc_interface_* storage into this
                 * header, or each translation unit would observe its own uninitialized cache.
                 */
                """);
        for (var function : functions) {
            appendInlineWrapper(out, function);
        }
        out.append("""

                #ifdef __cplusplus
                }
                #endif

                #endif
                """);
        return out.toString();
    }

    private static @NotNull String renderInterfaceSource(@NotNull List<InterfaceFunction> functions) {
        var out = new StringBuilder("""
                /* This file was generated by GodotInterfaceGenerator. */
                /* Do not edit by hand. */
                #include "godot_interface.h"

                #include <stdarg.h>
                #include <stdio.h>

                /*
                 * This pointer table is declared in the header so inline wrappers across all
                 * translation units share the eager-resolved state initialized below.
                 */
                static GDExtensionInterfacePrintError gdcc_lookup_fail_print_error = NULL;
                static GDExtensionInterfacePrintErrorWithMessage gdcc_lookup_fail_print_error_with_message = NULL;
                """);
        for (var function : functions) {
            out.append("GDCC_GODOT_DECL ")
                    .append(function.typedefName())
                    .append(' ')
                    .append(function.cacheName())
                    .append(" = NULL;\n");
        }
        out.append("""

                static void gdcc_clear_interface_pointers(void) {
                    gdcc_lookup_fail_print_error = NULL;
                    gdcc_lookup_fail_print_error_with_message = NULL;
                """);
        for (var function : functions) {
            out.append("    ").append(function.cacheName()).append(" = NULL;\n");
        }
        out.append("""
                }

                static const char *gdcc_lookup_text(const char *value) {
                    return value != NULL ? value : "<none>";
                }

                static size_t gdcc_append_lookup_message(
                        char *message,
                        size_t message_size,
                        size_t offset,
                        const char *format,
                        ...
                ) {
                    if (offset >= message_size) {
                        return message_size - 1;
                    }
                    va_list args;
                    va_start(args, format);
                    int written = vsnprintf(message + offset, message_size - offset, format, args);
                    va_end(args);
                    if (written < 0) {
                        return offset;
                    }
                    if ((size_t)written >= message_size - offset) {
                        return message_size - 1;
                    }
                    return offset + (size_t)written;
                }

                static void gdcc_render_lookup_message(
                        const gdcc_binding_lookup_context *context,
                        char *message,
                        size_t message_size
                ) {
                    size_t offset = gdcc_append_lookup_message(
                            message,
                            message_size,
                            0,
                            "kind=%s, function=%s, lookup=%s, owner=%s, type=%s",
                            gdcc_lookup_text(context != NULL ? context->kind : NULL),
                            gdcc_lookup_text(context != NULL ? context->function_name : NULL),
                            gdcc_lookup_text(context != NULL ? context->lookup_name : NULL),
                            gdcc_lookup_text(context != NULL ? context->owner : NULL),
                            gdcc_lookup_text(context != NULL ? context->type : NULL));
                    if (context != NULL && context->has_primary_hash) {
                        offset = gdcc_append_lookup_message(
                                message,
                                message_size,
                                offset,
                                ", primary_hash=%lld",
                                (long long)context->primary_hash);
                    } else {
                        offset = gdcc_append_lookup_message(message, message_size, offset, ", primary_hash=<none>");
                    }
                    if (context != NULL && context->compatibility_hash_count > 0) {
                        if (context->compatibility_hashes == NULL) {
                            gdcc_append_lookup_message(message, message_size, offset, ", compatibility_hashes=<missing>");
                            return;
                        }
                        offset = gdcc_append_lookup_message(message, message_size, offset, ", compatibility_hashes=[");
                        for (GDExtensionInt index = 0; index < context->compatibility_hash_count; index++) {
                            offset = gdcc_append_lookup_message(
                                    message,
                                    message_size,
                                    offset,
                                    index == 0 ? "%lld" : ", %lld",
                                    (long long)context->compatibility_hashes[index]);
                        }
                        gdcc_append_lookup_message(message, message_size, offset, "]");
                    } else {
                        gdcc_append_lookup_message(message, message_size, offset, ", compatibility_hashes=[]");
                    }
                }

                GDCC_GODOT_DECL GDExtensionBool gdcc_binding_lookup_fail(
                        const gdcc_binding_lookup_context *context
                ) {
                    if (context != NULL && context->suppress_internal_error) {
                        return false;
                    }
                    char message[1024];
                    gdcc_render_lookup_message(context, message, sizeof(message));
                    const char *function_name = gdcc_lookup_text(context != NULL ? context->function_name : NULL);
                    const char *lookup_name = gdcc_lookup_text(context != NULL ? context->lookup_name : NULL);
                    if (gdcc_lookup_fail_print_error_with_message != NULL) {
                        gdcc_lookup_fail_print_error_with_message(
                                "GDCC Godot binding lookup failed",
                                message,
                                function_name,
                                lookup_name,
                                0,
                                true);
                    } else if (gdcc_lookup_fail_print_error != NULL) {
                        gdcc_lookup_fail_print_error(
                                message,
                                function_name,
                                lookup_name,
                                0,
                                true);
                    }
                    fprintf(stderr, "GDCC Godot binding lookup failed: %s\\n", message);
                    return false;
                }

                static void gdcc_prepare_lookup_diagnostics(GDExtensionInterfaceGetProcAddress get_proc_address) {
                    gdcc_lookup_fail_print_error_with_message =
                            (GDExtensionInterfacePrintErrorWithMessage)get_proc_address("print_error_with_message");
                    gdcc_lookup_fail_print_error = (GDExtensionInterfacePrintError)get_proc_address("print_error");
                }

                #define GDCC_RESOLVE_INTERFACE(cache_name, typedef_name, wrapper_literal, lookup_literal) \\
                    do { \\
                        cache_name = (typedef_name)get_proc_address(lookup_literal); \\
                        if (cache_name == NULL) { \\
                            if (!gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){ \\
                                    .kind = "interface", \\
                                    .function_name = wrapper_literal, \\
                                    .lookup_name = lookup_literal, \\
                            })) { \\
                                goto gdcc_initialize_interface_failed; \\
                            } \\
                        } \\
                    } while (0)

                GDCC_GODOT_DECL GDExtensionBool godot_initialize_interface(
                        GDExtensionInterfaceGetProcAddress get_proc_address
                ) {
                    gdcc_clear_interface_pointers();
                    if (get_proc_address == NULL) {
                        return gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){
                                .kind = "interface",
                                .function_name = "godot_initialize_interface",
                                .lookup_name = "get_proc_address",
                        });
                    }
                    gdcc_prepare_lookup_diagnostics(get_proc_address);
                """);
        for (var function : functions) {
            out.append("    GDCC_RESOLVE_INTERFACE(")
                    .append(function.cacheName())
                    .append(", ")
                    .append(function.typedefName())
                    .append(", \"")
                    .append(function.wrapperName())
                    .append("\", \"")
                    .append(function.lookupName())
                    .append("\");\n");
        }
        out.append("""
                    return true;

                gdcc_initialize_interface_failed:
                    gdcc_clear_interface_pointers();
                    return false;
                }

                #undef GDCC_RESOLVE_INTERFACE

                """);
        return out.toString();
    }

    private static void appendInlineWrapper(@NotNull StringBuilder out, @NotNull InterfaceFunction function) {
        out.append("GDCC_GODOT_INLINE ")
                .append(renderFunctionPrefix(function.returnType(), function.wrapperName()))
                .append('(')
                .append(function.renderParameters())
                .append(") {\n");
        if (function.returnType().equals("void")) {
            out.append("    ").append(function.cacheName()).append('(').append(function.renderArguments()).append(");\n");
        } else {
            out.append("    return ").append(function.cacheName()).append('(')
                    .append(function.renderArguments())
                    .append(");\n");
        }
        out.append("}\n\n");
    }

    private static @NotNull String renderBindingHeader() {
        return generatedPreamble("GDCC_GODOT_BINDING_H") + """

                #include <godot_abi.h>
                #include <godot_interface.h>
                #include <godot_builtin.h>
                #include <godot_utility.h>
                #include <godot_fixed_binding.h>

                #endif
                """;
    }

    private static @NotNull String renderBindingSource() {
        return """
                /* This file was generated by GodotInterfaceGenerator. */
                /* Do not edit by hand. */
                #include "godot_binding.h"
                #include "godot_interface.c"
                #include "godot_builtin.c"
                #include "godot_utility.c"
                #include "godot_fixed_binding.c"
                """;
    }

    private static @NotNull String renderFunctionPrefix(@NotNull String returnType, @NotNull String functionName) {
        return returnType.endsWith("*") ? returnType + functionName : returnType + " " + functionName;
    }

    private static @NotNull String normalizeWhitespace(@NotNull String text) {
        return WHITESPACE_PATTERN.matcher(text.strip()).replaceAll(" ");
    }

    private static @NotNull String cIdentifier(@NotNull String raw) {
        var identifier = NON_C_IDENTIFIER_CHAR_PATTERN.matcher(raw).replaceAll("_");
        identifier = UNDERSCORE_RUN_PATTERN.matcher(identifier).replaceAll("_");
        if (identifier.isBlank()) {
            throw new IllegalArgumentException("Cannot render blank C identifier from '" + raw + "'");
        }
        if (Character.isDigit(identifier.charAt(0))) {
            identifier = "_" + identifier;
        }
        return identifier;
    }

    private static @NotNull String generatedPreamble(@NotNull String guard) {
        return """
                /* This file was generated by GodotInterfaceGenerator. */
                /* Do not edit by hand. */
                #ifndef %s
                #define %s
                """.formatted(guard, guard);
    }

    private record PendingInterface(
            @NotNull String name,
            @Nullable String since,
            int lineNumber
    ) {
    }

    record InterfaceFunction(
            @NotNull String lookupName,
            @Nullable String since,
            @NotNull String typedefName,
            @NotNull String returnType,
            @NotNull List<String> parameters,
            @NotNull List<String> argumentNames
    ) {
        InterfaceFunction {
            parameters = List.copyOf(parameters);
            argumentNames = List.copyOf(argumentNames);
        }

        @NotNull String wrapperName() {
            return "godot_" + cIdentifier(lookupName);
        }

        @NotNull String cacheName() {
            return "gdcc_interface_" + cIdentifier(lookupName);
        }

        @NotNull String renderParameters() {
            return String.join(", ", parameters);
        }

        @NotNull String renderArguments() {
            return String.join(", ", argumentNames);
        }
    }
}
