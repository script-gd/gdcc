package gd.script.gdcc.gdextension;

import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtensionMetadataTypeParsingTest {
    @Test
    void engineMethodMissingReturnValueMetadataShouldNormalizeToVoid() {
        var engineMethod = new ExtensionGdClass.ClassMethod(
                "add_to_group",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                null,
                List.of()
        );
        var engineMethodWithNullReturnType = new ExtensionGdClass.ClassMethod(
                "add_to_group",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn(null),
                List.of()
        );

        assertEquals(GdVoidType.VOID, engineMethod.getReturnType());
        assertEquals(GdVoidType.VOID, engineMethodWithNullReturnType.getReturnType());
    }

    @Test
    void extensionFunctionArgumentShouldUseSharedMetadataParser() {
        var flagsArgument = new ExtensionFunctionArgument(
                "flags",
                "bitfield::Node.ProcessThreadMessages",
                null,
                null
        );
        var nestedArrayFamilyArgument = new ExtensionFunctionArgument(
                "values",
                "typedarray::Array",
                null,
                null
        );
        var typedDictionaryArgument = new ExtensionFunctionArgument(
                "type_names",
                "typeddictionary::int;String",
                null,
                null
        );

        assertEquals(GdIntType.INT, flagsArgument.getType());
        assertEquals(
                new GdArrayType(new GdArrayType(GdVariantType.VARIANT)),
                nestedArrayFamilyArgument.getType()
        );
        assertEquals(
                new GdDictionaryType(GdIntType.INT, GdStringType.STRING),
                typedDictionaryArgument.getType()
        );
    }

    @Test
    void otherExtensionMetadataSurfacesShouldAlsoReuseSharedParser() {
        var builtinMethod = new ExtensionBuiltinClass.ClassMethod(
                "palette",
                "typeddictionary::Color;Color",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("typeddictionary::Color;Color")
        );
        var builtinProperty = new ExtensionBuiltinClass.PropertyInfo(
                "palette",
                "typeddictionary::Color;Color",
                true,
                true,
                null
        );
        var engineMethod = new ExtensionGdClass.ClassMethod(
                "type_names",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("typeddictionary::int;String"),
                List.of()
        );
        var engineProperty = new ExtensionGdClass.PropertyInfo(
                "type_names",
                "typeddictionary::int;String",
                true,
                true,
                null,
                "get_type_names",
                "set_type_names",
                null
        );
        var signalInfo = new ExtensionGdClass.SignalInfo("changed", List.of());
        var signalArgument = new ExtensionGdClass.SignalInfo.SignalArgument(
                "mapping",
                "typeddictionary::int;String",
                signalInfo
        );

        assertEquals(
                new GdDictionaryType(GdColorType.COLOR, GdColorType.COLOR),
                builtinMethod.getReturnType()
        );
        assertEquals(
                new GdDictionaryType(GdColorType.COLOR, GdColorType.COLOR),
                builtinProperty.getType()
        );
        assertEquals(
                new GdDictionaryType(GdIntType.INT, GdStringType.STRING),
                engineMethod.getReturnType()
        );
        assertEquals(
                new GdDictionaryType(GdIntType.INT, GdStringType.STRING),
                engineProperty.getType()
        );
        assertEquals(
                new GdDictionaryType(GdIntType.INT, GdStringType.STRING),
                signalArgument.getType()
        );
    }

    @Test
    void defaultApiTypedDictionaryPropertiesShouldExposeNormalizedDictionaryTypes() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var colorMap = api.classes().stream()
                .flatMap(extensionClass -> extensionClass.properties().stream())
                .filter(property ->
                        "color_map".equals(property.name())
                                && "typeddictionary::Color;Color".equals(property.type())
                )
                .findFirst()
                .orElseThrow();
        var typeNames = api.classes().stream()
                .flatMap(extensionClass -> extensionClass.properties().stream())
                .filter(property ->
                        "type_names".equals(property.name())
                                && "typeddictionary::int;String".equals(property.type())
                )
                .findFirst()
                .orElseThrow();

        assertEquals(
                new GdDictionaryType(GdColorType.COLOR, GdColorType.COLOR),
                colorMap.getType()
        );
        assertEquals(
                new GdDictionaryType(GdIntType.INT, GdStringType.STRING),
                typeNames.getType()
        );
    }
}
