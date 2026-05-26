package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

abstract class FixedGodotBindings {
    static @NotNull FixedGodotBindings forVersion(@NotNull GodotVersion version) {
        return switch (version) {
            case V451 -> Godot451FixedBindings.INSTANCE;
        };
    }

    static @NotNull FixedGodotBindings forApi(@NotNull ExtensionAPI api) {
        Objects.requireNonNull(api);
        var header = api.header();
        if (header != null
                && header.versionMajor() == 4
                && header.versionMinor() == 5
                && header.versionPatch() == 1) {
            return forVersion(GodotVersion.V451);
        }
        throw new IllegalArgumentException("Unsupported Godot version for fixed binding generation: "
                + GodotBindingSupport.versionLabel(api));
    }

    protected abstract @NotNull String versionLabel();

    protected abstract @NotNull List<FixedFunction> functions();

    protected abstract void appendDefinitions(@NotNull FixedRenderer renderer, @NotNull StringBuilder out);

    protected FixedGodotBindings() {
    }

    static void generateFixedSupport(@NotNull ExtensionAPI api, @NotNull Path out) throws IOException {
        FixedGodotBindings.forApi(api).writeFixedSupport(api, out);
    }

    static @NotNull Map<String, String> renderFixedSupport(@NotNull ExtensionAPI api) {
        return FixedGodotBindings.forApi(api).renderSupport(api);
    }

    static @NotNull List<GodotBindingSymbol> symbols(@NotNull ExtensionAPI api) {
        return FixedGodotBindings.forApi(api).collectSymbols(api);
    }

    private void writeFixedSupport(@NotNull ExtensionAPI api, @NotNull Path out) throws IOException {
        Files.createDirectories(out);
        for (var entry : renderSupport(api).entrySet()) {
            Files.writeString(out.resolve(entry.getKey()), entry.getValue());
        }
    }

    private @NotNull Map<String, String> renderSupport(@NotNull ExtensionAPI api) {
        GodotBindingSymbolHelper.validate(collectSymbols(api));
        var renderer = new FixedRenderer(api);
        var files = new LinkedHashMap<String, String>();
        files.put("godot_fixed_binding.h", renderer.renderHeader());
        files.put("godot_fixed_binding.c", renderer.renderSource());
        return files;
    }

    private @NotNull List<GodotBindingSymbol> collectSymbols(@NotNull ExtensionAPI api) {
        Objects.requireNonNull(api);
        var symbols = new ArrayList<GodotBindingSymbol>();
        for (var function : functions()) {
            var params = function.parameters().stream()
                    .map(parameter -> new GodotBindingSymbol.Parameter(
                            parameter.name(), parameter.cType(), parameter.abi()
                    ))
                    .toList();
            symbols.add(GodotBindingSupport.symbol(
                    GodotBindingSymbol.Family.FIXED,
                    function.owner(),
                    function.name(),
                    function.cFunctionName(),
                    function.returnType(),
                    params,
                    params.stream().anyMatch(parameter -> parameter.abi() == GodotBindingSymbol.Abi.VARIANT_VARARG),
                    null,
                    List.of()
            ));
        }
        return GodotBindingSymbolHelper.validate(symbols);
    }

    protected final class FixedRenderer {
        private final @NotNull ExtensionAPI api;

        private FixedRenderer(@NotNull ExtensionAPI api) {
            this.api = api;
        }

        private @NotNull String renderHeader() {
            var out = new StringBuilder(GodotBindingSupport.headerPreamble("GDCC_GODOT_FIXED_BINDING_H"));
            out.append("""
                    
                    #include <godot_utility.h>
                    
                    #ifdef __cplusplus
                    extern "C" {
                    #endif
                    
                    """);
            for (var function : functions()) {
                out.append("GDCC_GODOT_DECL ")
                        .append(function.returnType())
                        .append(' ')
                        .append(function.cFunctionName())
                        .append('(')
                        .append(renderParameters(function.parameters()))
                        .append(");\n");
            }
            out.append("""
                    
                    #ifdef __cplusplus
                    }
                    #endif
                    
                    #endif
                    """);
            return out.toString();
        }

        private @NotNull String renderSource() {
            var out = new StringBuilder("""
                    /* This file was generated by FixedGodotBindings for Godot %s. */
                    /* Do not edit by hand. */
                    #include "godot_fixed_binding.h"
                    
                    """.formatted(versionLabel()));
            out.append(GodotBindingSupport.sourceBindingMacros());
            out.append("""
                    static GDExtensionMethodBindPtr gdcc_fixed_resolve_method(
                            const char *class_name_text,
                            const char *method_name_text,
                            GDExtensionInt hash,
                            const char *function_name
                    ) {
                        godot_StringName class_name;
                        godot_StringName method_name;
                        godot_string_name_new_with_utf8_chars((GDExtensionUninitializedStringNamePtr)&class_name, class_name_text);
                        godot_string_name_new_with_utf8_chars((GDExtensionUninitializedStringNamePtr)&method_name, method_name_text);
                        GDExtensionMethodBindPtr resolved = godot_classdb_get_method_bind(&class_name, &method_name, hash);
                        godot_StringName_destroy(&method_name);
                        godot_StringName_destroy(&class_name);
                        if (resolved == NULL) {
                            gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){
                                    .kind = "fixed_method_bind",
                                    .function_name = function_name,
                                    .lookup_name = method_name_text,
                                    .owner = class_name_text,
                                    .has_primary_hash = true,
                                    .primary_hash = hash,
                            });
                        }
                        return resolved;
                    }
                    
                    """);
            FixedGodotBindings.this.appendDefinitions(this, out);
            return out.toString().stripTrailing() + "\n";
        }

        protected void appendSingletonDefinition(@NotNull StringBuilder out, @NotNull String className) {
            var functionName = "godot_" + className + "_singleton";
            var cacheName = "gdcc_fixed_singleton_" + className;
            out.append("GDCC_DEFINE_FIXED_SINGLETON(godot_")
                    .append(className)
                    .append(", \"")
                    .append(GodotBindingSupport.escapeCString(className))
                    .append("\", ")
                    .append(functionName)
                    .append(", ")
                    .append(cacheName)
                    .append(")\n\n");
        }

        protected void appendClassMethodDefinition(
                @NotNull StringBuilder out,
                @NotNull String className,
                @NotNull String methodName,
                @NotNull String functionName,
                @NotNull String returnType,
                @NotNull String parameters,
                @NotNull List<FixedMethodArg> args,
                @NotNull String selfExpression
        ) {
            var method = requireClassMethod(className, methodName);
            var cacheName = "gdcc_fixed_method_" + className + "_" + methodName;
            out.append("static GDExtensionMethodBindPtr ").append(cacheName).append(" = NULL;\n\n");
            out.append(returnType).append(' ').append(functionName).append('(').append(parameters).append(") {\n")
                    .append("    GDCC_RESOLVE_FIXED_METHOD_CACHE(")
                    .append(cacheName)
                    .append(", \"")
                    .append(GodotBindingSupport.escapeCString(className))
                    .append("\", \"")
                    .append(GodotBindingSupport.escapeCString(methodName))
                    .append("\", ")
                    .append(GodotBindingSupport.renderUnsignedInt(method.hash()))
                    .append(", \"")
                    .append(GodotBindingSupport.escapeCString(functionName))
                    .append("\", ");
            if (returnType.equals("void")) {
                out.append("return");
            } else {
                out.append("return ").append(GodotBindingSupport.zeroReturnByCType(returnType));
            }
            out.append(");\n");
            appendFixedPtrcallArgs(out, args);
            if (!returnType.equals("void")) {
                if (functionName.equals("godot_Object_get_instance_id")) {
                    out.append("    ").append(GodotBindingSupport.initializedCarrierDeclaration("uint64_t", "result"))
                            .append(";\n");
                } else {
                    out.append("    ").append(GodotBindingSupport.initializedCarrierDeclaration(returnType, "result"))
                            .append(";\n");
                }
            }
            out.append("    godot_object_method_bind_ptrcall(")
                    .append(cacheName).append(", (GDExtensionObjectPtr)").append(selfExpression)
                    .append(", ")
                    .append(args.isEmpty() ? "NULL" : "args")
                    .append(", ")
                    .append(returnType.equals("void") ? "NULL" : "(GDExtensionTypePtr)&result")
                    .append(");\n");
            if (!returnType.equals("void")) {
                if (functionName.equals("godot_Object_get_instance_id")) {
                    out.append("    return (godot_int)result;\n");
                } else {
                    out.append("    return result;\n");
                }
            }
            out.append("}\n\n");
        }

        protected void appendObjectCall(@NotNull StringBuilder out) {
            var method = requireClassMethod("Object", "call");
            out.append("static GDExtensionMethodBindPtr gdcc_fixed_method_Object_call = NULL;\n\n")
                    .append("""
                            godot_Variant godot_Object_call(
                                    GDExtensionObjectPtr self,
                                    const godot_StringName *method,
                                    const godot_Variant **argv,
                                    godot_int argc
                            ) {
                            """)
                    .append("    GDCC_RESOLVE_FIXED_METHOD_CACHE(gdcc_fixed_method_Object_call, \"Object\", \"call\", ")
                    .append(GodotBindingSupport.renderUnsignedInt(method.hash()))
                    .append(", \"godot_Object_call\", return godot_new_Variant_nil());\n")
                    .append("    godot_Variant method_variant = godot_new_Variant_with_StringName(method);\n")
                    .append("    GDExtensionConstVariantPtr args[1 + argc];\n")
                    .append("    args[0] = (GDExtensionConstVariantPtr)&method_variant;\n")
                    .append("    for (godot_int index = 0; index < argc; index++) {\n")
                    .append("        args[1 + index] = (GDExtensionConstVariantPtr)argv[index];\n")
                    .append("    }\n")
                    .append("    godot_Variant result;\n")
                    .append("    GDExtensionCallError error = { 0 };\n")
                    .append("    godot_object_method_bind_call(gdcc_fixed_method_Object_call, self, args, 1 + argc, ")
                    .append("(GDExtensionUninitializedVariantPtr)&result, &error);\n")
                    .append("    godot_Variant_destroy(&method_variant);\n")
                    .append("    if (error.error != GDEXTENSION_CALL_OK) {\n")
                    .append("        return godot_new_Variant_nil();\n")
                    .append("    }\n")
                    .append("    return result;\n")
                    .append("}\n\n");
        }

        protected void appendConstantDefinition(
                @NotNull StringBuilder out,
                @NotNull String className,
                @NotNull String constantName
        ) {
            var value = requireConstant(className, constantName);
            out.append("GDCC_DEFINE_FIXED_CONSTANT(godot_")
                    .append(className)
                    .append('_')
                    .append(constantName)
                    .append(", ")
                    .append(value)
                    .append(")\n\n");
        }

        private @NotNull ExtensionGdClass.ClassMethod requireClassMethod(
                @NotNull String className,
                @NotNull String methodName
        ) {
            var clazz = api.classes().stream()
                    .filter(candidate -> candidate.name().equals(className))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing fixed class metadata: " + className));
            return GodotBindingSupport.list(clazz.methods()).stream()
                    .filter(method -> method.name().equals(methodName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing fixed class method metadata: " + className + "." + methodName
                    ));
        }

        private @NotNull String requireConstant(@NotNull String className, @NotNull String constantName) {
            var clazz = api.classes().stream()
                    .filter(candidate -> candidate.name().equals(className))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing fixed class metadata: " + className));
            return GodotBindingSupport.list(clazz.constants()).stream()
                    .filter(constant -> constant.name().equals(constantName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing fixed class constant metadata: " + className + "." + constantName
                    ))
                    .value();
        }

        private static void appendFixedPtrcallArgs(
                @NotNull StringBuilder out,
                @NotNull List<FixedMethodArg> args
        ) {
            if (args.isEmpty()) {
                return;
            }
            out.append("    const GDExtensionConstTypePtr args[] = { ");
            var rendered = new ArrayList<String>();
            for (var arg : args) {
                rendered.add(GodotBindingSupport.typePtrArgument(arg.type(), arg.name()));
            }
            out.append(String.join(", ", rendered)).append(" };\n");
        }

        private static @NotNull String renderParameters(@NotNull List<FixedParam> parameters) {
            if (parameters.isEmpty()) {
                return "void";
            }
            return parameters.stream()
                    .map(parameter -> parameter.cType() + " " + parameter.name())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("void");
        }
    }

    protected record FixedFunction(
            @NotNull String owner,
            @NotNull String name,
            @NotNull String cFunctionName,
            @NotNull String returnType,
            @NotNull List<FixedParam> parameters
    ) {
    }

    protected record FixedParam(
            @NotNull String name,
            @NotNull String cType,
            @NotNull GodotBindingSymbol.Abi abi
    ) {
    }

    protected record FixedMethodArg(@NotNull String type, @NotNull String name) {
    }
}
