package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionBuiltinClassMemberOffsets;
import gd.script.gdcc.gdextension.ExtensionBuiltinClassSizes;
import gd.script.gdcc.gdextension.ExtensionNativeStructure;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Command-line support for versioned Godot binding support generation.
///
/// This is intentionally a small local tool: it generates ABI declaration headers
/// from the already-loaded `ExtensionAPI` model and does not participate in module codegen.
public final class GodotBindingTool {
    private static final @NotNull List<String> SUPPORTED_BUILD_CONFIGURATIONS = List.of("float_64");
    private static final @NotNull Set<String> PRIMITIVE_NATIVE_TYPES = Set.of(
            "bool",
            "char",
            "char16_t",
            "char32_t",
            "double",
            "float",
            "int",
            "int8_t",
            "int16_t",
            "int32_t",
            "int64_t",
            "size_t",
            "uint8_t",
            "uint16_t",
            "uint32_t",
            "uint64_t",
            "void"
    );

    private GodotBindingTool() {
    }

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (IllegalArgumentException | IllegalStateException | IOException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) throws IOException {
        if (args.length == 0) {
            throw usage();
        }
        var command = args[0];
        var options = parseOptions(args);
        switch (command) {
            case "generate-abi-support" -> {
                var out = requirePathOption(options, "--out");
                var api = options.containsKey("--api-resource")
                        ? ExtensionApiLoader.loadFromResource(options.get("--api-resource"))
                        : ExtensionApiLoader.loadVersion(parseGodotVersion(options.get("--gde")));
                generateAbiSupport(api, out);
            }
            case "generate-interface" -> {
                var out = requirePathOption(options, "--out");
                var header = requirePathOption(options, "--header");
                checkGodotVersionOption(options.get("--gde"), "interface generation");
                GodotInterfaceGenerator.generateInterfaceSupport(header, out);
            }
            case "generate-binding" -> {
                var out = requirePathOption(options, "--out");
                checkGodotVersionOption(options.get("--gde"), "binding aggregation");
                GodotInterfaceGenerator.generateBindingSupport(out);
            }
            case "generate-builtin" -> {
                var out = requirePathOption(options, "--out");
                var api = loadApi(options, "builtin generation");
                GodotBuiltinGenerator.generateBuiltinSupport(api, out);
            }
            case "generate-utility" -> {
                var out = requirePathOption(options, "--out");
                var api = loadApi(options, "utility generation");
                GodotUtilityGenerator.generateUtilitySupport(api, out);
            }
            case "generate-fixed" -> {
                var out = requirePathOption(options, "--out");
                var api = loadApi(options, "fixed binding generation");
                FixedGodotBindings.generateFixedSupport(api, out);
            }
            case "check-fixed" -> {
                var helperRoot = requirePathOption(options, "--helper-root");
                var templateRoot = requirePathOption(options, "--template-root");
                var api = loadApi(options, "fixed binding check");
                GdccHelperBindingScanner.checkFixedCoverage(
                        helperRoot,
                        templateRoot,
                        providedSymbols(api, requireInterfaceHeader(options, helperRoot))
                );
            }
            case "dump-fixed-manifest" -> {
                var out = requirePathOption(options, "--out");
                var api = loadApi(options, "fixed manifest dump");
                GodotBindingSymbolHelper.writeSnapshot(FixedGodotBindings.symbols(api), out);
            }
            default -> throw usage();
        }
    }

    static void generateAbiSupport(@NotNull ExtensionAPI api, @NotNull Path out) throws IOException {
        Objects.requireNonNull(api);
        Objects.requireNonNull(out);
        Files.createDirectories(out);
        for (var entry : renderAbiHeaders(api).entrySet()) {
            Files.writeString(out.resolve(entry.getKey()), entry.getValue());
        }
    }

    static @NotNull Map<String, String> renderAbiHeaders(@NotNull ExtensionAPI api) {
        Objects.requireNonNull(api);
        var exportedGlobalNames = new LinkedHashMap<String, String>();
        var files = new LinkedHashMap<String, String>();
        files.put("godot_macros.h", renderMacrosHeader());
        files.put("godot_global_enums.h", renderGlobalEnums(api, exportedGlobalNames));
        files.put("godot_global_constants.h", renderGlobalConstants(api, exportedGlobalNames));
        files.put("godot_builtin_sizes.h", renderBuiltinSizes(api));
        files.put("godot_builtin_layout.h", renderBuiltinLayout(api));
        files.put("godot_builtin_types.h", renderBuiltinTypes(api));
        files.put("godot_native_structures.h", renderNativeStructures(api));
        files.put("godot_abi.h", renderAbiHeader(api));
        return files;
    }

    private static @NotNull Map<String, String> parseOptions(String[] args) {
        var options = new LinkedHashMap<String, String>();
        for (var i = 1; i < args.length; i++) {
            var name = args[i];
            if (!name.startsWith("--")) {
                throw usage();
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for option " + name);
            }
            options.put(name, args[++i]);
        }
        return options;
    }

    private static @NotNull Path requirePathOption(@NotNull Map<String, String> options, @NotNull String name) {
        var value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option " + name);
        }
        return Path.of(value);
    }

    private static @NotNull GodotVersion parseGodotVersion(@Nullable String value) {
        if (value == null || value.isBlank() || value.equals(GodotVersion.V451.version)) {
            return GodotVersion.V451;
        }
        throw new IllegalArgumentException("Unsupported Godot version for ABI support generation: " + value);
    }

    private static @NotNull ExtensionAPI loadApi(
            @NotNull Map<String, String> options,
            @NotNull String commandName
    ) throws IOException {
        return options.containsKey("--api-resource")
                ? ExtensionApiLoader.loadFromResource(options.get("--api-resource"))
                : ExtensionApiLoader.loadVersion(parseGodotVersionForCommand(options.get("--gde"), commandName));
    }

    private static @NotNull GodotVersion parseGodotVersionForCommand(
            @Nullable String value,
            @NotNull String commandName
    ) {
        if (value == null || value.isBlank() || value.equals(GodotVersion.V451.version)) {
            return GodotVersion.V451;
        }
        throw new IllegalArgumentException("Unsupported Godot version for " + commandName + ": " + value);
    }

    private static @NotNull Path requireInterfaceHeader(
            @NotNull Map<String, String> options,
            @NotNull Path helperRoot
    ) {
        if (options.containsKey("--header")) {
            return Path.of(options.get("--header"));
        }
        var includeRoot = helperRoot.getParent();
        if (includeRoot != null) {
            var inferred = includeRoot.resolve("godot").resolve("gdextension").resolve("gdextension_interface.h");
            if (Files.isRegularFile(inferred)) {
                return inferred;
            }
        }
        throw new IllegalArgumentException(
                "Missing required option --header and cannot infer godot/gdextension/gdextension_interface.h"
        );
    }

    private static @NotNull Set<String> providedSymbols(
            @NotNull ExtensionAPI api,
            @NotNull Path interfaceHeader
    ) throws IOException {
        var provided = new LinkedHashSet<>(GodotInterfaceGenerator.collectWrapperNames(interfaceHeader));
        GodotBuiltinGenerator.collectSymbols(api).stream()
                .map(GodotBindingSymbol::cFunctionName)
                .forEach(provided::add);
        GodotUtilityGenerator.collectSymbols(api).stream()
                .map(GodotBindingSymbol::cFunctionName)
                .forEach(provided::add);
        FixedGodotBindings.symbols(api).stream()
                .map(GodotBindingSymbol::cFunctionName)
                .forEach(provided::add);
        return Set.copyOf(provided);
    }

    private static void checkGodotVersionOption(@Nullable String value, @NotNull String commandName) {
        if (value == null || value.isBlank() || value.equals(GodotVersion.V451.version)) {
            return;
        }
        throw new IllegalArgumentException("Unsupported Godot version for " + commandName + ": " + value);
    }

    private static @NotNull IllegalArgumentException usage() {
        return new IllegalArgumentException("""
                Usage:
                  GodotBindingTool generate-abi-support --gde 4.5.1 --out <dir>
                  GodotBindingTool generate-abi-support --api-resource /extension_api_451.json --out <dir>
                  GodotBindingTool generate-interface --gde 4.5.1 --header <gdextension_interface.h> --out <dir>
                  GodotBindingTool generate-binding --gde 4.5.1 --out <dir>
                  GodotBindingTool generate-builtin --gde 4.5.1 --out <dir>
                  GodotBindingTool generate-utility --gde 4.5.1 --out <dir>
                  GodotBindingTool generate-fixed --gde 4.5.1 --out <dir>
                  GodotBindingTool check-fixed --gde 4.5.1 --helper-root <dir> --template-root <dir> [--header <gdextension_interface.h>]
                  GodotBindingTool dump-fixed-manifest --gde 4.5.1 --out <file>
                """.strip());
    }

    private static @NotNull String renderMacrosHeader() {
        return generatedHeaderPreamble("GDCC_GODOT_MACROS_H") + """
                
                #include <stddef.h>
                
                #if !defined(GDE_EXPORT)
                #if defined(_WIN32)
                #define GDE_EXPORT __declspec(dllexport)
                #elif defined(__GNUC__)
                #define GDE_EXPORT __attribute__((visibility("default")))
                #else
                #define GDE_EXPORT
                #endif
                #endif
                
                #if defined(__cplusplus)
                #define GDCC_GODOT_STATIC_ASSERT(condition, message) static_assert(condition, message)
                #elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
                #include <assert.h>
                #define GDCC_GODOT_STATIC_ASSERT(condition, message) static_assert(condition, message)
                #else
                #define GDCC_GODOT_STATIC_ASSERT(condition, message)
                #endif
                
                #if defined(_MSC_VER)
                #define GDCC_GODOT_DECL
                #elif defined(__GNUC__)
                #define GDCC_GODOT_DECL __attribute__((__visibility__("hidden")))
                #else
                #define GDCC_GODOT_DECL
                #endif
                
                #define GDCC_GODOT_INLINE static inline
                #define GDCC_GODOT_OPAQUE_STRUCT(name) typedef struct name name
                #define GDCC_GODOT_ASSERT_SIZE(type) \\
                    GDCC_GODOT_STATIC_ASSERT(sizeof(godot_##type) == GDCC_GODOT_SIZE_##type, \\
                                             "Incompatible size for " #type)
                #define GDCC_GODOT_ASSERT_LAYOUT(type, member) \\
                    GDCC_GODOT_STATIC_ASSERT(offsetof(godot_##type, member) == GDCC_GODOT_OFFSET_##type##_##member, \\
                                             "Incompatible offset for " #type "." #member); \\
                    GDCC_GODOT_STATIC_ASSERT(sizeof(((godot_##type *)0)->member) == sizeof(GDCC_GODOT_META_##type##_##member), \\
                                             "Incompatible meta layout for " #type "." #member)
                
                #endif
                """;
    }

    private static @NotNull String renderGlobalEnums(
            @NotNull ExtensionAPI api,
            @NotNull LinkedHashMap<String, String> exportedGlobalNames
    ) {
        var out = new StringBuilder(generatedHeaderPreamble("GDCC_GODOT_GLOBAL_ENUMS_H"));
        out.append("""
                
                #ifdef __cplusplus
                extern "C" {
                #endif
                
                """);
        for (var globalEnum : api.globalEnums()) {
            var enumTypeName = godotSymbol(globalEnum.name());
            out.append("typedef enum ").append(enumTypeName).append(" {\n");
            for (var value : globalEnum.values()) {
                var valueName = godotSymbol(value.name());
                recordExportedGlobalName(exportedGlobalNames, valueName, "global enum " + globalEnum.name());
                out.append("    ").append(valueName).append(" = ").append(value.value()).append(",\n");
            }
            out.append("} ").append(enumTypeName).append(";\n\n");
        }
        out.append("""
                #ifdef __cplusplus
                }
                #endif
                
                #endif
                """);
        return out.toString();
    }

    private static @NotNull String renderGlobalConstants(
            @NotNull ExtensionAPI api,
            @NotNull LinkedHashMap<String, String> exportedGlobalNames
    ) {
        var out = new StringBuilder(generatedHeaderPreamble("GDCC_GODOT_GLOBAL_CONSTANTS_H"));
        if (api.globalConstants().isEmpty()) {
            out.append("\n/* Godot 4.5.1 exports no standalone global constants. */\n");
        } else {
            out.append('\n');
        }
        for (var constant : api.globalConstants()) {
            var name = godotSymbol(constant.name());
            recordExportedGlobalName(exportedGlobalNames, name, "global constant");
            out.append("#define ").append(name).append(' ').append(renderLongLiteral(constant.value())).append('\n');
        }
        out.append("\n#endif\n");
        return out.toString();
    }

    private static @NotNull String renderBuiltinSizes(@NotNull ExtensionAPI api) {
        var sizesByConfiguration = requireSizesByConfiguration(api);
        var out = new StringBuilder(generatedHeaderPreamble("GDCC_GODOT_BUILTIN_SIZES_H"));
        out.append("""
                
                #include <stdint.h>
                
                /* GDCC supports only Godot float_64 ABI: 64-bit pointers with single-precision real_t. */
                #ifdef REAL_T_IS_DOUBLE
                #error "GDCC C backend supports only single-precision Godot real_t builds"
                #endif
                
                #if INTPTR_MAX == INT64_MAX
                #define GDCC_GODOT_BUILD_FLOAT_64
                #elif INTPTR_MAX == INT32_MAX
                #error "GDCC C backend does not support 32-bit Godot builtin ABI"
                #else
                #error "Unsupported pointer width for Godot builtin ABI sizes"
                #endif
                
                """);
        for (var buildConfiguration : SUPPORTED_BUILD_CONFIGURATIONS) {
            out.append("#ifdef ").append(buildMacro(buildConfiguration)).append('\n');
            for (var size : sizesByConfiguration.get(buildConfiguration)) {
                out.append("#define GDCC_GODOT_SIZE_")
                        .append(GodotBindingSupport.cIdentifier(size.name()))
                        .append(' ')
                        .append(size.size())
                        .append('\n');
            }
            out.append("#endif\n\n");
        }
        out.append("#endif\n");
        return out.toString();
    }

    private static @NotNull String renderBuiltinLayout(@NotNull ExtensionAPI api) {
        var offsetsByConfiguration = requireOffsetsByConfiguration(api);
        var out = new StringBuilder(generatedHeaderPreamble("GDCC_GODOT_BUILTIN_LAYOUT_H"));
        out.append("""
                
                #include <godot_builtin_sizes.h>
                
                """);
        for (var buildConfiguration : SUPPORTED_BUILD_CONFIGURATIONS) {
            out.append("#ifdef ").append(buildMacro(buildConfiguration)).append('\n');
            for (var classData : offsetsByConfiguration.get(buildConfiguration)) {
                var className = GodotBindingSupport.cIdentifier(classData.name());
                var seenMembers = new LinkedHashSet<String>();
                for (var member : classData.members()) {
                    var memberName = GodotBindingSupport.cIdentifier(member.member());
                    if (!seenMembers.add(memberName)) {
                        throw new IllegalStateException(
                                "Duplicate builtin member layout metadata: buildConfiguration='"
                                        + buildConfiguration + "', builtinClass='" + classData.name()
                                        + "', member='" + member.member() + "'"
                        );
                    }
                    out.append("#define GDCC_GODOT_OFFSET_")
                            .append(className)
                            .append('_')
                            .append(memberName)
                            .append(' ')
                            .append(member.offset())
                            .append('\n');
                    out.append("#define GDCC_GODOT_META_")
                            .append(className)
                            .append('_')
                            .append(memberName)
                            .append(' ')
                            .append(renderMetaType(member.meta()))
                            .append('\n');
                }
            }
            out.append("#endif\n\n");
        }
        out.append("#endif\n");
        return out.toString();
    }

    private static @NotNull String renderBuiltinTypes(@NotNull ExtensionAPI api) {
        var out = new StringBuilder(generatedHeaderPreamble("GDCC_GODOT_BUILTIN_TYPES_H"));
        out.append("""
                
                #include <gdextension/gdextension_interface.h>
                #include <godot_macros.h>
                #include <godot_builtin_sizes.h>
                #include <godot_builtin_layout.h>
                #include <math.h>
                #include <stdint.h>
                
                #ifdef __cplusplus
                typedef bool godot_bool;
                #else
                typedef GDExtensionBool godot_bool;
                #endif
                typedef GDExtensionInt godot_int;
                typedef double godot_float;
                typedef float godot_real_t;
                #define godot_inf INFINITY
                
                typedef struct godot_String { uint8_t _[GDCC_GODOT_SIZE_String]; } godot_String;
                typedef struct godot_StringName { uint8_t _[GDCC_GODOT_SIZE_StringName]; } godot_StringName;
                typedef struct godot_NodePath { uint8_t _[GDCC_GODOT_SIZE_NodePath]; } godot_NodePath;
                typedef struct godot_RID { uint8_t _[GDCC_GODOT_SIZE_RID]; } godot_RID;
                typedef struct godot_Callable { uint8_t _[GDCC_GODOT_SIZE_Callable]; } godot_Callable;
                typedef struct godot_Signal { uint8_t _[GDCC_GODOT_SIZE_Signal]; } godot_Signal;
                typedef struct godot_Dictionary { uint8_t _[GDCC_GODOT_SIZE_Dictionary]; } godot_Dictionary;
                typedef struct godot_Array { uint8_t _[GDCC_GODOT_SIZE_Array]; } godot_Array;
                typedef struct godot_PackedByteArray { uint8_t _[GDCC_GODOT_SIZE_PackedByteArray]; } godot_PackedByteArray;
                typedef struct godot_PackedInt32Array { uint8_t _[GDCC_GODOT_SIZE_PackedInt32Array]; } godot_PackedInt32Array;
                typedef struct godot_PackedInt64Array { uint8_t _[GDCC_GODOT_SIZE_PackedInt64Array]; } godot_PackedInt64Array;
                typedef struct godot_PackedFloat32Array { uint8_t _[GDCC_GODOT_SIZE_PackedFloat32Array]; } godot_PackedFloat32Array;
                typedef struct godot_PackedFloat64Array { uint8_t _[GDCC_GODOT_SIZE_PackedFloat64Array]; } godot_PackedFloat64Array;
                typedef struct godot_PackedStringArray { uint8_t _[GDCC_GODOT_SIZE_PackedStringArray]; } godot_PackedStringArray;
                typedef struct godot_PackedVector2Array { uint8_t _[GDCC_GODOT_SIZE_PackedVector2Array]; } godot_PackedVector2Array;
                typedef struct godot_PackedVector3Array { uint8_t _[GDCC_GODOT_SIZE_PackedVector3Array]; } godot_PackedVector3Array;
                typedef struct godot_PackedColorArray { uint8_t _[GDCC_GODOT_SIZE_PackedColorArray]; } godot_PackedColorArray;
                typedef struct godot_PackedVector4Array { uint8_t _[GDCC_GODOT_SIZE_PackedVector4Array]; } godot_PackedVector4Array;
                typedef struct godot_Variant { uint8_t _[GDCC_GODOT_SIZE_Variant]; } godot_Variant;
                typedef struct godot_Object godot_Object;
                
                typedef struct godot_Vector2 {
                    union {
                        godot_real_t coord[2];
                        struct { godot_real_t x, y; };
                        struct { godot_real_t r, g; };
                        struct { godot_real_t s, t; };
                        struct { godot_real_t u, v; };
                        struct { godot_real_t width, height; };
                    };
                } godot_Vector2;
                
                typedef struct godot_Vector2i {
                    union {
                        int32_t coord[2];
                        struct { int32_t x, y; };
                        struct { int32_t width, height; };
                    };
                } godot_Vector2i;
                
                typedef struct godot_Rect2 {
                    union {
                        struct { godot_real_t x, y, width, height; };
                        struct { godot_Vector2 position, size; };
                    };
                } godot_Rect2;
                
                typedef struct godot_Rect2i {
                    union {
                        struct { int32_t x, y, width, height; };
                        struct { godot_Vector2i position, size; };
                    };
                } godot_Rect2i;
                
                typedef struct godot_Vector3 {
                    union {
                        godot_real_t coord[3];
                        struct { godot_real_t x, y, z; };
                        struct { godot_Vector2 xy; godot_real_t _0; };
                        struct { godot_real_t _1; godot_Vector2 yz; };
                        struct { godot_real_t r, g, b; };
                        struct { godot_Vector2 rg; godot_real_t _2; };
                        struct { godot_real_t _3; godot_Vector2 gb; };
                        struct { godot_real_t s, t, p; };
                        struct { godot_Vector2 st; godot_real_t _4; };
                        struct { godot_real_t _5; godot_Vector2 tp; };
                        struct { godot_real_t width, height, depth; };
                    };
                } godot_Vector3;
                
                typedef struct godot_Vector3i {
                    union {
                        int32_t coord[3];
                        struct { int32_t x, y, z; };
                        struct { godot_Vector2i xy; int32_t _0; };
                        struct { int32_t _1; godot_Vector2i yz; };
                        struct { int32_t width, height, depth; };
                    };
                } godot_Vector3i;
                
                typedef struct godot_Transform2D {
                    union {
                        godot_Vector2 columns[3];
                        struct { godot_Vector2 x, y, origin; };
                    };
                } godot_Transform2D;
                
                typedef struct godot_Vector4 {
                    union {
                        godot_real_t coord[4];
                        struct { godot_real_t x, y, z, w; };
                        struct { godot_Vector2 xy, zw; };
                        struct { godot_Vector3 xyz; godot_real_t _0; };
                        struct { godot_real_t _1; godot_Vector3 yzw; };
                        struct { godot_real_t r, g, b, a; };
                        struct { godot_Vector2 rg, ba; };
                        struct { godot_Vector3 rgb; godot_real_t _2; };
                        struct { godot_real_t _3; godot_Vector3 gba; };
                    };
                } godot_Vector4;
                
                typedef struct godot_Vector4i {
                    union {
                        int32_t coord[4];
                        struct { int32_t x, y, z, w; };
                        struct { godot_Vector2i xy, zw; };
                        struct { godot_Vector3i xyz; int32_t _0; };
                        struct { int32_t _1; godot_Vector3i yzw; };
                    };
                } godot_Vector4i;
                
                typedef struct godot_Plane {
                    union {
                        godot_real_t elements[4];
                        struct { godot_Vector3 normal; godot_real_t d; };
                    };
                } godot_Plane;
                
                typedef struct godot_Quaternion {
                    union {
                        godot_real_t elements[4];
                        struct { godot_real_t x, y, z, w; };
                        struct { godot_Vector2 xy, zw; };
                        struct { godot_Vector3 xyz; godot_real_t _0; };
                        struct { godot_real_t _1; godot_Vector3 yzw; };
                    };
                } godot_Quaternion;
                
                typedef struct godot_AABB {
                    godot_Vector3 position;
                    godot_Vector3 size;
                } godot_AABB;
                
                typedef struct godot_Basis {
                    union {
                        godot_Vector3 rows[3];
                        struct { godot_Vector3 x, y, z; };
                    };
                } godot_Basis;
                
                typedef struct godot_Transform3D {
                    godot_Basis basis;
                    godot_Vector3 origin;
                } godot_Transform3D;
                
                typedef struct godot_Projection {
                    union {
                        godot_real_t columns[4][4];
                        struct { godot_Vector4 x, y, z, w; };
                    };
                } godot_Projection;
                
                typedef struct godot_Color {
                    union {
                        float components[4];
                        struct { float r, g, b, a; };
                    };
                } godot_Color;
                
                """);
        appendBuiltinSizeAsserts(api, out);
        appendBuiltinLayoutAsserts(api, out);
        out.append("\n#endif\n");
        return out.toString();
    }

    private static @NotNull String renderNativeStructures(@NotNull ExtensionAPI api) {
        var out = new StringBuilder(generatedHeaderPreamble("GDCC_GODOT_NATIVE_STRUCTURES_H"));
        out.append("""
                
                #include <godot_builtin_types.h>
                #include <stdint.h>
                
                #ifdef __cplusplus
                extern "C" {
                #endif
                
                """);
        appendNativeScopedEnums(api, out);
        for (var structure : api.nativeStructures()) {
            out.append("typedef struct godot_")
                    .append(GodotBindingSupport.cIdentifier(structure.name()))
                    .append(" {\n");
            for (var field : parseNativeFields(structure)) {
                out.append("    ")
                        .append(renderNativeType(field.type()))
                        .append(' ')
                        .append(GodotBindingSupport.cIdentifier(field.name()))
                        .append(field.arraySuffix() == null ? "" : field.arraySuffix())
                        .append(";\n");
            }
            out.append("} godot_")
                    .append(GodotBindingSupport.cIdentifier(structure.name()))
                    .append(";\n\n");
        }
        out.append("""
                #ifdef __cplusplus
                }
                #endif
                
                #endif
                """);
        return out.toString();
    }

    private static @NotNull String renderAbiHeader(@NotNull ExtensionAPI api) {
        var out = new StringBuilder(generatedHeaderPreamble("GDCC_GODOT_ABI_H"));
        out.append("""
                
                #include <gdextension/gdextension_interface.h>
                #include <godot_macros.h>
                #include <godot_global_enums.h>
                #include <godot_global_constants.h>
                #include <godot_builtin_sizes.h>
                #include <godot_builtin_layout.h>
                #include <godot_builtin_types.h>
                #include <godot_native_structures.h>
                
                """);
        appendEngineClassTypedefs(api, out);
        appendEngineClassEnums(api, out);
        out.append("""
                #endif
                """);
        return out.toString();
    }

    private static void appendEngineClassTypedefs(@NotNull ExtensionAPI api, @NotNull StringBuilder out) {
        for (var clazz : api.classes()) {
            if (clazz.name().equals("Object")) {
                continue;
            }
            var className = GodotBindingSupport.cIdentifier(clazz.name());
            out.append("typedef struct godot_")
                    .append(className)
                    .append(" godot_")
                    .append(className)
                    .append(";\n");
        }
        out.append('\n');
    }

    private static void appendEngineClassEnums(@NotNull ExtensionAPI api, @NotNull StringBuilder out) {
        var nativeScopedEnumRefs = nativeScopedEnumRefs(api);
        for (var clazz : api.classes()) {
            var ownerName = GodotBindingSupport.cIdentifier(clazz.name());
            for (var classEnum : GodotBindingSupport.list(clazz.enums())) {
                if (nativeScopedEnumRefs.contains(new ScopedEnumRef(clazz.name(), classEnum.name()))) {
                    continue;
                }
                var enumTypeName = "godot_" + ownerName + "_" + GodotBindingSupport.cIdentifier(classEnum.name());
                out.append("typedef enum ").append(enumTypeName).append(" {\n");
                for (var value : GodotBindingSupport.list(classEnum.values())) {
                    out.append("    godot_")
                            .append(ownerName)
                            .append("_")
                            .append(GodotBindingSupport.cIdentifier(value.name()))
                            .append(" = ")
                            .append(value.value())
                            .append(",\n");
                }
                out.append("} ").append(enumTypeName).append(";\n\n");
            }
        }
    }

    private static void appendBuiltinSizeAsserts(@NotNull ExtensionAPI api, @NotNull StringBuilder out) {
        var firstConfiguration = requireSizesByConfiguration(api).get(SUPPORTED_BUILD_CONFIGURATIONS.getFirst());
        for (var size : firstConfiguration) {
            var name = size.name();
            if (name.equals("Nil") || name.equals("Object")) {
                continue;
            }
            out.append("GDCC_GODOT_ASSERT_SIZE(").append(GodotBindingSupport.cIdentifier(name)).append(");\n");
        }
    }

    private static void appendBuiltinLayoutAsserts(@NotNull ExtensionAPI api, @NotNull StringBuilder out) {
        var seen = new LinkedHashSet<String>();
        var supportedConfiguration = requireOffsetsByConfiguration(api).get(SUPPORTED_BUILD_CONFIGURATIONS.getFirst());
        for (var classData : supportedConfiguration) {
            for (var member : classData.members()) {
                var key = classData.name() + "." + member.member();
                if (!seen.add(key)) {
                    continue;
                }
                out.append("GDCC_GODOT_ASSERT_LAYOUT(")
                        .append(GodotBindingSupport.cIdentifier(classData.name()))
                        .append(", ")
                        .append(GodotBindingSupport.cIdentifier(member.member()))
                        .append(");\n");
            }
        }
    }

    private static void appendNativeScopedEnums(@NotNull ExtensionAPI api, @NotNull StringBuilder out) {
        var references = nativeScopedEnumRefs(api);
        for (var ref : references) {
            var classData = api.classes().stream()
                    .filter(clazz -> ref.owner().equals(clazz.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing native structure enum owner: " + ref.owner()));
            var enumData = classData.enums().stream()
                    .filter(classEnum -> ref.enumName().equals(classEnum.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing native structure enum metadata: " + ref.owner() + "::" + ref.enumName()
                    ));
            var typeName = "godot_"
                    + GodotBindingSupport.cIdentifier(ref.owner())
                    + "_"
                    + GodotBindingSupport.cIdentifier(ref.enumName());
            out.append("typedef enum ").append(typeName).append(" {\n");
            for (var value : enumData.values()) {
                out.append("    godot_")
                        .append(GodotBindingSupport.cIdentifier(ref.owner()))
                        .append("_")
                        .append(GodotBindingSupport.cIdentifier(value.name()))
                        .append(" = ")
                        .append(value.value())
                        .append(",\n");
            }
            out.append("} ").append(typeName).append(";\n\n");
        }
    }

    private static @NotNull LinkedHashSet<ScopedEnumRef> nativeScopedEnumRefs(@NotNull ExtensionAPI api) {
        var references = new LinkedHashSet<ScopedEnumRef>();
        for (var structure : api.nativeStructures()) {
            for (var field : parseNativeFields(structure)) {
                var type = field.type().replace("*", "").trim();
                if (type.contains("::")) {
                    var parts = type.split("::", 2);
                    references.add(new ScopedEnumRef(parts[0], parts[1]));
                }
            }
        }
        return references;
    }

    private static @NotNull List<NativeField> parseNativeFields(@NotNull ExtensionNativeStructure structure) {
        if (structure.format() == null || structure.format().isBlank()) {
            throw new IllegalStateException("Missing native structure format: " + structure.name());
        }
        var fields = new ArrayList<NativeField>();
        for (var rawField : structure.format().split(";")) {
            var withoutDefault = rawField.split("=", 2)[0].trim();
            if (withoutDefault.isBlank()) {
                continue;
            }
            var separator = withoutDefault.lastIndexOf(' ');
            if (separator < 0) {
                throw new IllegalStateException(
                        "Invalid native structure field: " + structure.name() + " -> " + rawField
                );
            }
            var type = new StringBuilder(withoutDefault.substring(0, separator).trim());
            var name = withoutDefault.substring(separator + 1).trim();
            while (name.startsWith("*")) {
                type.append(" *");
                name = name.substring(1).trim();
            }
            String arraySuffix = null;
            var arrayStart = name.indexOf('[');
            if (arrayStart >= 0) {
                arraySuffix = name.substring(arrayStart);
                name = name.substring(0, arrayStart);
            }
            fields.add(new NativeField(type.toString(), name, arraySuffix));
        }
        return fields;
    }

    private static @NotNull String renderNativeType(@NotNull String rawType) {
        var type = rawType.trim();
        if (type.endsWith("*")) {
            return renderNativeType(type.substring(0, type.length() - 1).trim()) + " *";
        }
        if (type.equals("real_t")) {
            return "godot_real_t";
        }
        if (type.contains("::")) {
            var parts = type.split("::", 2);
            return "godot_"
                    + GodotBindingSupport.cIdentifier(parts[0])
                    + "_"
                    + GodotBindingSupport.cIdentifier(parts[1]);
        }
        if (PRIMITIVE_NATIVE_TYPES.contains(type)) {
            return type;
        }
        return "godot_" + GodotBindingSupport.cIdentifier(type);
    }

    private static @NotNull LinkedHashMap<String, List<ExtensionBuiltinClassSizes.ClassSizeInfo>>
    requireSizesByConfiguration(@NotNull ExtensionAPI api) {
        var byConfiguration = new LinkedHashMap<String, List<ExtensionBuiltinClassSizes.ClassSizeInfo>>();
        for (var sizes : api.builtinClassSizes()) {
            if (byConfiguration.put(sizes.buildConfiguration(), sizes.sizes()) != null) {
                throw new IllegalStateException(
                        "Duplicate builtin class size metadata: buildConfiguration='" + sizes.buildConfiguration() + "'"
                );
            }
            var names = new LinkedHashSet<String>();
            for (var size : sizes.sizes()) {
                if (!names.add(size.name())) {
                    throw new IllegalStateException(
                            "Duplicate builtin class size metadata: buildConfiguration='"
                                    + sizes.buildConfiguration() + "', builtinClass='" + size.name() + "'"
                    );
                }
            }
        }
        requireBuildConfigurations(byConfiguration.keySet(), "builtin class size");
        return byConfiguration;
    }

    private static @NotNull LinkedHashMap<String, List<ExtensionBuiltinClassMemberOffsets.ClassMemberData>>
    requireOffsetsByConfiguration(@NotNull ExtensionAPI api) {
        var byConfiguration =
                new LinkedHashMap<String, List<ExtensionBuiltinClassMemberOffsets.ClassMemberData>>();
        for (var offsets : api.builtinClassMemberOffsets()) {
            if (byConfiguration.put(offsets.buildConfiguration(), offsets.classes()) != null) {
                throw new IllegalStateException(
                        "Duplicate builtin class member layout metadata: buildConfiguration='"
                                + offsets.buildConfiguration() + "'"
                );
            }
            var names = new LinkedHashSet<String>();
            for (var classData : offsets.classes()) {
                if (!names.add(classData.name())) {
                    throw new IllegalStateException(
                            "Duplicate builtin class member layout metadata: buildConfiguration='"
                                    + offsets.buildConfiguration() + "', builtinClass='" + classData.name() + "'"
                    );
                }
            }
        }
        requireBuildConfigurations(byConfiguration.keySet(), "builtin member layout");
        return byConfiguration;
    }

    private static void requireBuildConfigurations(@NotNull Set<String> actual, @NotNull String metadataName) {
        for (var buildConfiguration : SUPPORTED_BUILD_CONFIGURATIONS) {
            if (!actual.contains(buildConfiguration)) {
                throw new IllegalStateException(
                        "Missing " + metadataName + " metadata: buildConfiguration='" + buildConfiguration + "'"
                );
            }
        }
    }

    private static void recordExportedGlobalName(
            @NotNull LinkedHashMap<String, String> names,
            @NotNull String name,
            @NotNull String source
    ) {
        var previous = names.putIfAbsent(name, source);
        if (previous != null) {
            throw new IllegalStateException(
                    "Duplicate Godot global C symbol '" + name + "' from " + source + " and " + previous
            );
        }
    }

    private static @NotNull String renderMetaType(@NotNull String meta) {
        return switch (meta) {
            case "bool" -> "godot_bool";
            case "float" -> "float";
            case "double" -> "double";
            case "int32" -> "int32_t";
            case "int64" -> "int64_t";
            case "uint32" -> "uint32_t";
            case "uint64" -> "uint64_t";
            default -> "godot_" + GodotBindingSupport.cIdentifier(meta);
        };
    }

    private static @NotNull String buildMacro(@NotNull String buildConfiguration) {
        return "GDCC_GODOT_BUILD_" + buildConfiguration.toUpperCase(Locale.ROOT);
    }

    private static @NotNull String godotSymbol(@NotNull String raw) {
        return "godot_" + GodotBindingSupport.cIdentifier(raw);
    }

    private static @NotNull String renderLongLiteral(long value) {
        if (value == Long.MIN_VALUE) {
            return "(-9223372036854775807LL - 1LL)";
        }
        return value + "LL";
    }

    private static @NotNull String generatedHeaderPreamble(@NotNull String guard) {
        return """
                /* This file was generated by GodotBindingTool. */
                /* Do not edit by hand. */
                #ifndef %s
                #define %s
                """.formatted(guard, guard);
    }

    private record NativeField(
            @NotNull String type,
            @NotNull String name,
            @Nullable String arraySuffix
    ) {
    }

    private record ScopedEnumRef(
            @NotNull String owner,
            @NotNull String enumName
    ) {
    }
}
