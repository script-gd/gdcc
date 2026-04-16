package dev.superice.gdcc.gdextension;

import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdColorType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtensionMetadataTypeParsingTest {
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
