package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Narrow facade that exposes the runtime-provided `godot_*` symbol universe to usage collectors.
///
/// The concrete interface/builtin/utility/fixed generators stay package-private in `binding`; the
/// `usage` subpackage only needs their C function-name snapshot for module-local de-duplication.
public final class GodotBindingProvidedSymbols {
    private static final @NotNull String INTERFACE_HEADER_RESOURCE =
            "/include_451/godot/gdextension/gdextension_interface.h";
    private static final @NotNull List<String> GDCC_HELPER_C_FUNCTION_NAMES = List.of(
            "godot_new_gdcc_Object_with_Variant",
            "godot_new_Transform2D_with_float_float_float_float_float_float",
            "godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float",
            "godot_new_Basis_with_float_float_float_float_float_float_float_float_float",
            "godot_new_Projection_with_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float",
            "godot_Variant_call"
    );
    private static volatile Set<String> defaultRuntimeProvidedCFunctionNames;

    private GodotBindingProvidedSymbols() {
    }

    public static @NotNull Set<String> forRegistry(@NotNull ClassRegistry registry) {
        Objects.requireNonNull(registry);
        var provided = new LinkedHashSet<>(defaultRuntimeProvidedCFunctionNames());
        for (var utility : registry.getExtensionUtilityFunctionList()) {
            if (utility.name() != null && !utility.name().isBlank()) {
                provided.add("godot_" + GodotBindingSupport.cIdentifier(utility.name()));
            }
        }
        var registryApi = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                registry.getExtensionUtilityFunctionList(),
                registry.getExtensionBuiltinClassList(),
                List.of(),
                List.of(),
                List.of()
        );
        GodotBuiltinGenerator.collectSymbols(registryApi).stream()
                .map(GodotBindingSymbol::cFunctionName)
                .forEach(provided::add);
        GodotUtilityGenerator.collectSymbols(registryApi).stream()
                .map(GodotBindingSymbol::cFunctionName)
                .forEach(provided::add);
        return Set.copyOf(provided);
    }

    private static @NotNull Set<String> defaultRuntimeProvidedCFunctionNames() {
        var cached = defaultRuntimeProvidedCFunctionNames;
        if (cached != null) {
            return cached;
        }
        synchronized (GodotBindingProvidedSymbols.class) {
            cached = defaultRuntimeProvidedCFunctionNames;
            if (cached == null) {
                cached = collectDefaultRuntimeProvidedCFunctionNames();
                defaultRuntimeProvidedCFunctionNames = cached;
            }
            return cached;
        }
    }

    private static @NotNull Set<String> collectDefaultRuntimeProvidedCFunctionNames() {
        try {
            var api = ExtensionApiLoader.loadDefault();
            var provided = new LinkedHashSet<String>();
            for (var function : GodotInterfaceGenerator.parseInterfaceFunctions(readResource(INTERFACE_HEADER_RESOURCE))) {
                provided.add(function.wrapperName());
            }
            provided.add("godot_initialize_interface");
            GodotBuiltinGenerator.collectSymbols(api).stream()
                    .map(GodotBindingSymbol::cFunctionName)
                    .forEach(provided::add);
            GodotUtilityGenerator.collectSymbols(api).stream()
                    .map(GodotBindingSymbol::cFunctionName)
                    .forEach(provided::add);
            FixedGodotBindings.symbols(api).stream()
                    .map(GodotBindingSymbol::cFunctionName)
                    .forEach(provided::add);
            provided.addAll(GDCC_HELPER_C_FUNCTION_NAMES);
            return Set.copyOf(provided);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load default Godot binding provided set", exception);
        }
    }

    private static @NotNull String readResource(@NotNull String resourcePath) throws IOException {
        var stream = GodotBindingProvidedSymbols.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        try (var in = stream;
             var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            var out = new StringBuilder();
            var buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, read);
            }
            return out.toString();
        }
    }
}
