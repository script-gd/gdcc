package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirSignalDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CCodegenSignalRegistrationTest {
    @Test
    @DisplayName("zero-arg signal should register with NULL, 0 and skip a property-info array")
    void generateShouldRegisterZeroArgSignalWithNullAndZero() throws Exception {
        var host = new LirClassDef("SignalHost", "RefCounted");
        host.addSignal(new LirSignalDef("pinged"));

        var bindBody = generateBindMethodsBody(host, "SignalHost");

        assertContainsAll(
                bindBody,
                "// Signals",
                "godot_classdb_register_extension_class_signal(class_library, class_name, GD_STATIC_SN(u8\"pinged\"), NULL, 0);"
        );
        assertFalse(bindBody.contains("signal_args"), bindBody);
        assertFalse(bindBody.contains("gdcc_make_property_full"), bindBody);
        assertFalse(bindBody.contains("gdcc_destruct_property"), bindBody);
        assertEquals(1, countOccurrences(bindBody, "godot_classdb_register_extension_class_signal"));
    }

    @Test
    @DisplayName("parameterized signal should register named property info and free it after ClassDB copy")
    void generateShouldRegisterParameterizedSignalAndFreeMetadata() throws Exception {
        var host = new LirClassDef("SignalHost", "RefCounted");
        host.addSignal(createSignal(
                "payload",
                List.of(
                        new NamedType("count", GdIntType.INT),
                        new NamedType("data", GdVariantType.VARIANT),
                        new NamedType("target", new GdObjectType("Node")),
                        new NamedType("names", new GdArrayType(GdStringNameType.STRING_NAME)),
                        new NamedType("pairs", new GdDictionaryType(
                                GdStringNameType.STRING_NAME,
                                new GdObjectType("Node")
                        ))
                )
        ));

        var bindBody = generateBindMethodsBody(host, "SignalHost");

        assertContainsAll(
                bindBody,
                "// Signals",
                "GDExtensionPropertyInfo signal_args[] = {",
                "gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_INT, GD_STATIC_SN(u8\"count\"), godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8\"\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_DEFAULT)",
                "gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_NIL, GD_STATIC_SN(u8\"data\"), godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8\"\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT)",
                "gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_OBJECT, GD_STATIC_SN(u8\"target\"), godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8\"\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_DEFAULT)",
                "gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_ARRAY, GD_STATIC_SN(u8\"names\"), godot_PROPERTY_HINT_ARRAY_TYPE, GD_STATIC_S(u8\"StringName\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_DEFAULT)",
                "gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_DICTIONARY, GD_STATIC_SN(u8\"pairs\"), godot_PROPERTY_HINT_DICTIONARY_TYPE, GD_STATIC_S(u8\"StringName;Node\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_DEFAULT)",
                "godot_classdb_register_extension_class_signal(class_library, class_name, GD_STATIC_SN(u8\"payload\"), signal_args, 5);",
                "gdcc_destruct_property(&signal_args[0]);",
                "gdcc_destruct_property(&signal_args[1]);",
                "gdcc_destruct_property(&signal_args[2]);",
                "gdcc_destruct_property(&signal_args[3]);",
                "gdcc_destruct_property(&signal_args[4]);"
        );
        assertFalse(bindBody.contains("NULL, 0"), bindBody);
        assertFalse(bindBody.contains("godot_PROPERTY_USAGE_NO_EDITOR"), bindBody);
        assertEquals(5, countOccurrences(bindBody, "gdcc_destruct_property(&signal_args["));
        assertOrderedFragments(
                bindBody,
                "gdcc_make_property_full",
                "godot_classdb_register_extension_class_signal",
                "gdcc_destruct_property(&signal_args[0]);",
                "gdcc_destruct_property(&signal_args[1]);",
                "gdcc_destruct_property(&signal_args[2]);",
                "gdcc_destruct_property(&signal_args[3]);",
                "gdcc_destruct_property(&signal_args[4]);"
        );
    }

    @Test
    @DisplayName("child class should register only its own signals and skip inherited declarations")
    void generateShouldRegisterOnlyCurrentClassSignals() throws Exception {
        var parent = new LirClassDef("ParentHost", "RefCounted");
        parent.addSignal(new LirSignalDef("inherited_pinged"));
        var child = new LirClassDef("ChildHost", "ParentHost");
        child.addSignal(new LirSignalDef("child_pinged"));

        var files = generateEntryC(List.of(parent, child));
        var parentBody = resolveFunctionBodyByPrefix(files, "void ParentHost_class_bind_methods");
        var childBody = resolveFunctionBodyByPrefix(files, "void ChildHost_class_bind_methods");

        assertTrue(parentBody.contains(
                "godot_classdb_register_extension_class_signal(class_library, class_name, GD_STATIC_SN(u8\"inherited_pinged\"), NULL, 0);"
        ), parentBody);
        assertFalse(parentBody.contains("child_pinged"), parentBody);
        assertTrue(childBody.contains(
                "godot_classdb_register_extension_class_signal(class_library, class_name, GD_STATIC_SN(u8\"child_pinged\"), NULL, 0);"
        ), childBody);
        assertFalse(childBody.contains("inherited_pinged"), childBody);
        assertEquals(1, countOccurrences(parentBody, "godot_classdb_register_extension_class_signal"));
        assertEquals(1, countOccurrences(childBody, "godot_classdb_register_extension_class_signal"));
    }

    @Test
    @DisplayName("class without signals should not emit a ClassDB signal registration call")
    void generateShouldSkipSignalRegistrationWhenClassHasNoSignals() throws Exception {
        var host = new LirClassDef("PlainHost", "RefCounted");

        var bindBody = generateBindMethodsBody(host, "PlainHost");

        assertTrue(bindBody.contains("// Signals"), bindBody);
        assertFalse(bindBody.contains("godot_classdb_register_extension_class_signal"), bindBody);
    }

    private static @NotNull String generateBindMethodsBody(
            @NotNull LirClassDef classDef,
            @NotNull String className
    ) throws Exception {
        return resolveFunctionBodyByPrefix(generateEntryC(List.of(classDef)), "void " + className + "_class_bind_methods");
    }

    private static @NotNull String generateEntryC(@NotNull List<LirClassDef> classDefs) throws Exception {
        var module = new LirModule("signal_registration_module", classDefs);
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return generatedFileText(codegen.generate(), "entry.c");
    }

    private static @NotNull LirSignalDef createSignal(
            @NotNull String name,
            @NotNull List<NamedType> parameters
    ) {
        var signal = new LirSignalDef(name);
        for (var parameter : parameters) {
            signal.addParameter(new LirParameterDef(parameter.name(), parameter.type(), null, signal));
        }
        return signal;
    }

    private record NamedType(@NotNull String name, @NotNull GdType type) {
    }

    private static @NotNull String generatedFileText(
            @NotNull List<GeneratedFile> files,
            @NotNull String filePath
    ) {
        for (var file : files) {
            if (file.filePath().equals(filePath)) {
                return new String(file.contentWriter());
            }
        }
        throw new AssertionError("Missing generated file: " + filePath);
    }

    private static @NotNull String resolveFunctionBodyByPrefix(
            @NotNull String code,
            @NotNull String signaturePrefix
    ) {
        var signatureIndex = code.indexOf(signaturePrefix);
        assertTrue(signatureIndex >= 0, "Missing function prefix: " + signaturePrefix);
        var openBraceIndex = code.indexOf('{', signatureIndex);
        assertTrue(openBraceIndex >= 0, "Missing opening brace for " + signaturePrefix);
        var closeBraceIndex = findMatchingBrace(code, openBraceIndex);
        return code.substring(openBraceIndex + 1, closeBraceIndex);
    }

    private static int findMatchingBrace(@NotNull String text, int openBraceIndex) {
        var depth = 0;
        for (var i = openBraceIndex; i < text.length(); i++) {
            var ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AssertionError("Missing closing brace for function body");
    }

    private static void assertContainsAll(@NotNull String text, @NotNull String... needles) {
        for (var needle : needles) {
            assertTrue(text.contains(needle), () -> "Missing fragment `" + needle + "` in:\n" + text);
        }
    }

    private static void assertOrderedFragments(@NotNull String text, @NotNull String... fragments) {
        var cursor = -1;
        for (var fragment : fragments) {
            var next = text.indexOf(fragment, cursor + 1);
            assertTrue(next >= 0, () -> "Missing ordered fragment `" + fragment + "` in:\n" + text);
            cursor = next;
        }
    }

    private static int countOccurrences(@NotNull String text, @NotNull String needle) {
        var count = 0;
        var from = 0;
        while (true) {
            var index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }
}
