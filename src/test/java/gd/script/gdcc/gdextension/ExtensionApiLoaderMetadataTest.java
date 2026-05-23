package gd.script.gdcc.gdextension;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionApiLoaderMetadataTest {
    private static final Set<String> BUILD_CONFIGURATIONS = Set.of(
            "float_32",
            "float_64",
            "double_32",
            "double_64"
    );

    @Test
    void builtinSizeMetadataShouldCoverAllGodotBuildConfigurations() throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        assertEquals(
                BUILD_CONFIGURATIONS,
                api.builtinClassSizes().stream()
                        .map(ExtensionBuiltinClassSizes::buildConfiguration)
                        .collect(Collectors.toSet())
        );
        for (var buildConfiguration : BUILD_CONFIGURATIONS) {
            for (var builtinName : List.of("Variant", "String", "Vector2", "Array", "Dictionary")) {
                assertTrue(
                        api.requireBuiltinClassSize(buildConfiguration, builtinName) > 0,
                        () -> builtinName + " size missing for " + buildConfiguration
                );
            }
        }
    }

    @Test
    void builtinMemberOffsetsShouldKeepLayoutMetaAcrossBuildConfigurations() throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        assertEquals(
                BUILD_CONFIGURATIONS,
                api.builtinClassMemberOffsets().stream()
                        .map(ExtensionBuiltinClassMemberOffsets::buildConfiguration)
                        .collect(Collectors.toSet())
        );
        assertEquals(
                new ExtensionAPI.BuiltinClassMemberLayout(0, "float"),
                api.requireBuiltinClassMemberLayout("float_32", "Vector2", "x")
        );
        assertEquals(
                new ExtensionAPI.BuiltinClassMemberLayout(8, "float"),
                api.requireBuiltinClassMemberLayout("float_32", "Vector3", "z")
        );
        assertEquals(
                new ExtensionAPI.BuiltinClassMemberLayout(8, "float"),
                api.requireBuiltinClassMemberLayout("float_64", "Vector3", "z")
        );
        assertEquals(
                new ExtensionAPI.BuiltinClassMemberLayout(16, "double"),
                api.requireBuiltinClassMemberLayout("double_32", "Vector3", "z")
        );
        assertEquals(
                new ExtensionAPI.BuiltinClassMemberLayout(16, "double"),
                api.requireBuiltinClassMemberLayout("double_64", "Vector3", "z")
        );
        assertEquals(
                new ExtensionAPI.BuiltinClassMemberLayout(0, "float"),
                api.requireBuiltinClassMemberLayout("double_64", "Color", "r")
        );
    }

    @Test
    void builtinLayoutQueriesShouldReturnNullForMissingMetadataKeys() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var emptyApi = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertAll(
                () -> assertNull(api.findBuiltinClassSize("missing_config", "Vector3")),
                () -> assertNull(api.findBuiltinClassSize("float_32", "MissingBuiltin")),
                () -> assertNull(api.findBuiltinClassMemberLayout("missing_config", "Vector3", "z")),
                () -> assertNull(api.findBuiltinClassMemberLayout("float_32", "MissingBuiltin", "z")),
                () -> assertNull(api.findBuiltinClassMemberLayout("float_32", "Vector3", "missing_axis")),
                () -> assertNull(emptyApi.findBuiltinClassSize("float_32", "Vector3")),
                () -> assertNull(emptyApi.findBuiltinClassMemberLayout("float_32", "Vector3", "z"))
        );
    }

    @Test
    void builtinLayoutRequireQueriesShouldReportMissingMetadataKeys() throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        var sizeFailure = assertThrows(
                IllegalStateException.class,
                () -> api.requireBuiltinClassSize("missing_config", "Vector3")
        );
        var memberFailure = assertThrows(
                IllegalStateException.class,
                () -> api.requireBuiltinClassMemberLayout("float_32", "Vector3", "missing_axis")
        );

        assertAll(
                () -> assertTrue(sizeFailure.getMessage().contains("missing_config")),
                () -> assertTrue(sizeFailure.getMessage().contains("Vector3")),
                () -> assertTrue(memberFailure.getMessage().contains("float_32")),
                () -> assertTrue(memberFailure.getMessage().contains("Vector3")),
                () -> assertTrue(memberFailure.getMessage().contains("missing_axis"))
        );
    }

    @Test
    void globalConstantsShouldBePresentAndKeepLargeValues() throws IOException {
        var defaultApi = ExtensionApiLoader.loadDefault();
        assertNotNull(defaultApi.globalConstants());
        assertTrue(defaultApi.globalConstants().isEmpty(), "Godot 4.5.1 currently has no global constants");

        var fixtureApi = ExtensionApiLoader.loadFromResource("/extension_api_metadata_fixture.json");
        var globalConstant = assertDoesNotThrow(() -> fixtureApi.globalConstants().getFirst());
        assertEquals("GDCC_TEST_BIG_FLAG", globalConstant.name());
        assertEquals(4_294_967_296L, globalConstant.value());
        assertTrue(globalConstant.isBitfield());
    }

    @Test
    void enumMetadataShouldKeepGodotInt64Values() throws IOException {
        var defaultApi = ExtensionApiLoader.loadDefault();
        var defaultWideValue = gdClassEnumValueOf(
                defaultApi,
                "RenderingServer",
                "ArrayFormat",
                "ARRAY_FLAG_FORMAT_VERSION_2"
        );
        assertAll(
                () -> assertEquals(34_359_738_368L, defaultWideValue),
                () -> assertNotEquals(0L, defaultWideValue, "legacy int parsing truncated this Godot 4.5.1 value")
        );
        assertEquals(
                34_359_738_368L,
                gdClassEnumValueOf(
                        defaultApi,
                        "RenderingServer",
                        "ArrayFormat",
                        "ARRAY_FLAG_FORMAT_CURRENT_VERSION"
                )
        );

        var fixtureApi = ExtensionApiLoader.loadFromResource("/extension_api_metadata_fixture.json");
        assertEquals(
                34_359_738_368L,
                globalEnumValueOf(fixtureApi, "GdccWideGlobalFlags", "GDCC_WIDE_GLOBAL_FLAG")
        );
        assertEquals(
                -2_147_483_648L,
                globalEnumValueOf(fixtureApi, "GdccWideGlobalFlags", "GDCC_NEGATIVE_INT_BOUNDARY")
        );
        assertEquals(
                4_294_967_296L,
                builtinEnumValueOf(fixtureApi, "GdccWideBuiltin", "WideBuiltinFlags", "GDCC_WIDE_BUILTIN_FLAG")
        );
        assertEquals(
                34_359_738_368L,
                gdClassEnumValueOf(fixtureApi, "FixtureNode", "WideNodeFlags", "FIXTURE_NODE_WIDE_FLAG")
        );
    }

    @Test
    void builtinClassMetadataShouldKeepDestructorIndexingAndConstructorIndexes() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var stringClass = builtinClass(api, "String");
        var arrayClass = builtinClass(api, "Array");
        var dictionaryClass = builtinClass(api, "Dictionary");
        var boolClass = builtinClass(api, "bool");

        assertTrue(stringClass.hasDestructor());
        assertEquals("String", stringClass.indexingReturnType());
        assertTrue(arrayClass.hasDestructor());
        assertEquals("Variant", arrayClass.indexingReturnType());
        assertFalse(arrayClass.isKeyed());
        assertTrue(dictionaryClass.hasDestructor());
        assertEquals("Variant", dictionaryClass.indexingReturnType());
        assertTrue(dictionaryClass.isKeyed());
        assertFalse(boolClass.hasDestructor());
        assertNull(boolClass.indexingReturnType());

        assertTrue(constructorIndexes(stringClass).containsAll(List.of(0, 1)));
        assertTrue(constructorIndexes(arrayClass).contains(0));
        assertTrue(constructorIndexes(dictionaryClass).contains(0));
    }

    @Test
    void nativeStructureMetadataShouldKeepRepresentativeRawFormats() throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        assertAll(
                () -> assertEquals("float left;float right", nativeStructure(api, "AudioFrame").format()),
                () -> assertEquals("uint64_t id = 0", nativeStructure(api, "ObjectID").format()),
                () -> assertTrue(
                        nativeStructure(api, "Glyph").format().contains("uint16_t flags = 0"),
                        "Glyph should keep primitive fields and default literals"
                ),
                () -> assertTrue(
                        nativeStructure(api, "CaretInfo").format().contains("TextServer::Direction leading_direction"),
                        "CaretInfo should keep scoped enum references for C header generation"
                )
        );
    }

    @Test
    void enginePropertyMetadataShouldKeepRawAccessorsIndexesAndExplicitFlags() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var nodeName = propertyOf(api, "Node", "name");
        assertEquals("StringName", nodeName.type());
        assertEquals("get_name", nodeName.getter());
        assertEquals("set_name", nodeName.setter());
        assertNull(nodeName.index());
        assertTrue(nodeName.isReadable());
        assertTrue(nodeName.isWritable());

        var unresizable = propertyOf(api, "Window", "unresizable");
        assertEquals("get_flag", unresizable.getter());
        assertEquals("set_flag", unresizable.setter());
        assertEquals(0, unresizable.index());

        var currentAnimationLength = propertyOf(api, "AnimationPlayer", "current_animation_length");
        assertEquals("get_current_animation_length", currentAnimationLength.getter());
        assertNull(currentAnimationLength.setter());
        assertTrue(currentAnimationLength.isReadable());
        assertFalse(currentAnimationLength.isWritable());

        var fixtureApi = ExtensionApiLoader.loadFromResource("/extension_api_metadata_fixture.json");
        var rawCount = propertyOf(fixtureApi, "FixtureNode", "raw_count");
        assertTrue(rawCount.isReadable());
        assertTrue(rawCount.isWritable());
        var forcedCount = propertyOf(fixtureApi, "FixtureNode", "forced_count");
        assertFalse(forcedCount.isReadable());
        assertTrue(forcedCount.isWritable());
        assertEquals("get_forced_count", forcedCount.getter());
        assertNull(forcedCount.setter());
    }

    private static ExtensionBuiltinClass builtinClass(ExtensionAPI api, String className) {
        return api.builtinClasses().stream()
                .filter(builtinClass -> className.equals(builtinClass.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing builtin class " + className));
    }

    private static ExtensionNativeStructure nativeStructure(ExtensionAPI api, String name) {
        return api.nativeStructures().stream()
                .filter(structure -> name.equals(structure.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing native structure " + name));
    }

    private static List<Integer> constructorIndexes(ExtensionBuiltinClass builtinClass) {
        return builtinClass.constructors().stream()
                .map(ExtensionBuiltinClass.ConstructorInfo::index)
                .toList();
    }

    private static long globalEnumValueOf(ExtensionAPI api, String enumName, String valueName) {
        return api.globalEnums().stream()
                .filter(globalEnum -> enumName.equals(globalEnum.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing global enum " + enumName))
                .values()
                .stream()
                .filter(value -> valueName.equals(value.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing global enum value " + enumName + "." + valueName))
                .value();
    }

    private static long builtinEnumValueOf(ExtensionAPI api, String className, String enumName, String valueName) {
        return builtinClass(api, className).enums().stream()
                .filter(classEnum -> enumName.equals(classEnum.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing builtin enum " + className + "." + enumName))
                .values()
                .stream()
                .filter(value -> valueName.equals(value.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing builtin enum value " + className + "." + enumName + "." + valueName
                ))
                .value();
    }

    private static long gdClassEnumValueOf(ExtensionAPI api, String className, String enumName, String valueName) {
        return api.classes().stream()
                .filter(clazz -> className.equals(clazz.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing class " + className))
                .enums()
                .stream()
                .filter(classEnum -> enumName.equals(classEnum.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing engine enum " + className + "." + enumName))
                .values()
                .stream()
                .filter(value -> valueName.equals(value.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing engine enum value " + className + "." + enumName + "." + valueName
                ))
                .value();
    }

    private static ExtensionGdClass.PropertyInfo propertyOf(ExtensionAPI api, String className, String propertyName) {
        return api.classes().stream()
                .filter(clazz -> className.equals(clazz.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing class " + className))
                .properties()
                .stream()
                .filter(property -> propertyName.equals(property.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing property " + className + "." + propertyName));
    }

}
