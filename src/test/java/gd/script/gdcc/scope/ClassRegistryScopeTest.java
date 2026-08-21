package gd.script.gdcc.scope;

import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionEnumValue;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.gdextension.ExtensionGlobalEnum;
import gd.script.gdcc.gdextension.ExtensionHeader;
import gd.script.gdcc.gdextension.ExtensionSingleton;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClassRegistryScopeTest {
    @Test
    void classRegistryRemainsGlobalRootScope() {
        var registry = new ClassRegistry(createScopeFixtureApi());

        assertNull(registry.getParentScope());
        assertDoesNotThrow(() -> registry.setParentScope(null));
        assertNull(registry.getParentScope());
        assertThrows(IllegalArgumentException.class, () -> registry.setParentScope(new ParentScopeStub()));
    }

    @Test
    void resolveValueAndFunctionsExposeGlobalBindings() {
        var registry = new ClassRegistry(createScopeFixtureApi());

        var singletonValue = registry.resolveValue("GameSingleton");
        assertNotNull(singletonValue);
        assertEquals(ScopeValueKind.SINGLETON, singletonValue.kind());
        assertEquals("Node", singletonValue.type().getTypeName());
        assertTrue(singletonValue.constant());
        assertFalse(singletonValue.writable());
        assertInstanceOf(ExtensionSingleton.class, singletonValue.declaration());

        var enumValue = registry.resolveValue("GameFlags");
        assertNotNull(enumValue);
        assertEquals(ScopeValueKind.GLOBAL_ENUM, enumValue.kind());
        assertEquals(GdIntType.INT, enumValue.type());
        assertTrue(enumValue.constant());
        assertFalse(enumValue.writable());
        assertInstanceOf(ExtensionGlobalEnum.class, enumValue.declaration());

        var globalConstantValue = registry.resolveValue("GDCC_TEST_BIG_FLAG");
        assertNotNull(globalConstantValue);
        assertEquals(ScopeValueKind.CONSTANT, globalConstantValue.kind());
        assertEquals(GdIntType.INT, globalConstantValue.type());
        assertTrue(globalConstantValue.constant());
        assertFalse(globalConstantValue.writable());
        var globalConstant = assertInstanceOf(ExtensionGlobalConstant.class, globalConstantValue.declaration());
        assertEquals(4_294_967_296L, globalConstant.value());

        var utilityFunctions = registry.resolveFunctions("print_line");
        assertEquals(1, utilityFunctions.size());
        var utilityFunction = assertInstanceOf(ExtensionUtilityFunction.class, utilityFunctions.getFirst());
        assertEquals("print_line", utilityFunction.getName());
        assertEquals(GdStringType.STRING, utilityFunction.getReturnType());

        assertNull(registry.resolveValue("print_line"));
        assertTrue(registry.resolveFunctions("GameSingleton").isEmpty());
        assertNull(registry.resolveTypeMeta("print_line"));
        assertNull(registry.resolveTypeMeta("GDCC_TEST_BIG_FLAG"));
        assertNull(registry.findType("GDCC_TEST_BIG_FLAG"));
    }

    @Test
    void restrictionAwareLookupKeepsGlobalBindingsAllowed() {
        var registry = new ClassRegistry(createScopeFixtureApi());

        var singletonResult = registry.resolveValue("GameSingleton", ResolveRestriction.staticContext());
        assertTrue(singletonResult.isAllowed());
        assertEquals(ScopeValueKind.SINGLETON, singletonResult.requireValue().kind());

        var functionResult = registry.resolveFunctions("print_line", ResolveRestriction.staticContext());
        assertTrue(functionResult.isAllowed());
        assertEquals("print_line", functionResult.requireValue().getFirst().getName());

        var missingResult = registry.resolveValue("Missing", ResolveRestriction.staticContext());
        assertTrue(missingResult.isNotFound());
    }

    @Test
    void restrictionAwareTypeMetaLookupStaysAllowedAtGlobalRoot() {
        var registry = new ClassRegistry(createScopeFixtureApi());
        registry.addGdccClass(new LirClassDef("InventoryItem", "Object"));

        var unrestrictedResult = registry.resolveTypeMeta("InventoryItem", ResolveRestriction.unrestricted());
        var staticResult = registry.resolveTypeMeta("InventoryItem", ResolveRestriction.staticContext());
        var instanceResult = registry.resolveTypeMeta("InventoryItem", ResolveRestriction.instanceContext());

        assertTrue(unrestrictedResult.isAllowed());
        assertTrue(staticResult.isAllowed());
        assertTrue(instanceResult.isAllowed());
        assertFalse(unrestrictedResult.isBlocked());
        assertFalse(staticResult.isBlocked());
        assertFalse(instanceResult.isBlocked());
        assertEquals("InventoryItem", unrestrictedResult.requireValue().canonicalName());
        assertEquals("InventoryItem", unrestrictedResult.requireValue().sourceName());
        assertEquals("InventoryItem", staticResult.requireValue().canonicalName());
        assertEquals("InventoryItem", instanceResult.requireValue().canonicalName());
    }

    @Test
    void resolveTypeMetaUsesStrictGlobalTypeNamespace() {
        var registry = new ClassRegistry(createScopeFixtureApi());
        registry.addGdccClass(new LirClassDef("InventoryItem", "Object"));

        var builtinMeta = registry.resolveTypeMeta("String");
        assertNotNull(builtinMeta);
        assertEquals(ScopeTypeMetaKind.BUILTIN, builtinMeta.kind());
        assertEquals(GdStringType.STRING, builtinMeta.instanceType());

        var engineMeta = registry.resolveTypeMeta("Node");
        assertNotNull(engineMeta);
        assertEquals(ScopeTypeMetaKind.ENGINE_CLASS, engineMeta.kind());
        var engineType = assertInstanceOf(GdObjectType.class, engineMeta.instanceType());
        assertEquals("Node", engineType.getTypeName());

        var gdccMeta = registry.resolveTypeMeta("InventoryItem");
        assertNotNull(gdccMeta);
        assertEquals(ScopeTypeMetaKind.GDCC_CLASS, gdccMeta.kind());
        assertEquals("InventoryItem", gdccMeta.instanceType().getTypeName());

        var enumMeta = registry.resolveTypeMeta("GameFlags");
        assertNotNull(enumMeta);
        assertEquals(ScopeTypeMetaKind.GLOBAL_ENUM, enumMeta.kind());
        assertEquals(GdIntType.INT, enumMeta.instanceType());
        assertTrue(enumMeta.pseudoType());

        var dictionaryMeta = registry.resolveTypeMeta("Dictionary[String, InventoryItem]");
        assertNotNull(dictionaryMeta);
        assertEquals(ScopeTypeMetaKind.BUILTIN, dictionaryMeta.kind());
        var dictionaryType = assertInstanceOf(GdDictionaryType.class, dictionaryMeta.instanceType());
        assertEquals(GdStringType.STRING, dictionaryType.getKeyType());
        assertEquals("InventoryItem", dictionaryType.getValueType().getTypeName());
    }

    @Test
    void sameNameCanResolveIndependentlyInValueAndTypeNamespaces() {
        var registry = new ClassRegistry(createScopeFixtureApi());
        registry.addGdccClass(new LirClassDef("SharedSymbol", "Object"));

        var valueBinding = registry.resolveValue("SharedSymbol");
        assertNotNull(valueBinding);
        assertEquals(ScopeValueKind.SINGLETON, valueBinding.kind());
        assertEquals("Node", valueBinding.type().getTypeName());
        assertNotSame(valueBinding.kind(), ScopeValueKind.TYPE_META);

        var typeBinding = registry.resolveTypeMeta("SharedSymbol");
        assertNotNull(typeBinding);
        assertEquals(ScopeTypeMetaKind.GDCC_CLASS, typeBinding.kind());
        assertEquals("SharedSymbol", typeBinding.instanceType().getTypeName());

        assertNull(registry.resolveValue("MissingSymbol"));
        assertTrue(registry.resolveFunctions("MissingSymbol").isEmpty());
        assertNull(registry.resolveTypeMeta("MissingSymbol"));
    }

    @Test
    void bareGlobalEnumMemberNamesResolveToIntConstants() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var typeNil = registry.resolveValue("TYPE_NIL");
        assertNotNull(typeNil);
        assertEquals(ScopeValueKind.CONSTANT, typeNil.kind());
        assertEquals(GdIntType.INT, typeNil.type());
        assertTrue(typeNil.constant());
        assertFalse(typeNil.writable());
        var typeNilDeclaration = assertInstanceOf(ExtensionEnumValue.class, typeNil.declaration());
        assertEquals("TYPE_NIL", typeNilDeclaration.name());
        assertEquals(0L, typeNilDeclaration.value());

        var okValue = requireBareConstantValue(registry, "OK", GdIntType.INT);
        var ok = assertInstanceOf(ExtensionEnumValue.class, okValue.declaration());
        assertEquals(0L, ok.value());
        var keyAValue = requireBareConstantValue(registry, "KEY_A", GdIntType.INT);
        var keyA = assertInstanceOf(ExtensionEnumValue.class, keyAValue.declaration());
        assertEquals(65L, keyA.value());
        var sideLeftValue = requireBareConstantValue(registry, "SIDE_LEFT", GdIntType.INT);
        var sideLeft = assertInstanceOf(ExtensionEnumValue.class, sideLeftValue.declaration());
        assertEquals(0L, sideLeft.value());

        assertNull(registry.resolveTypeMeta("TYPE_NIL"));
        assertNull(registry.resolveTypeMeta("KEY_A"));
    }

    @Test
    void bareGdScriptLanguageConstantsResolveToFloatConstants() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var pi = registry.resolveValue("PI");
        assertNotNull(pi);
        assertEquals(ScopeValueKind.CONSTANT, pi.kind());
        assertEquals(GdFloatType.FLOAT, pi.type());
        assertTrue(pi.constant());
        assertFalse(pi.writable());
        assertEquals(Math.PI, assertInstanceOf(GdScriptLanguageConstant.class, pi.declaration()).value());

        var tau = requireBareConstantValue(registry, "TAU", GdFloatType.FLOAT);
        assertEquals(Math.TAU, assertInstanceOf(GdScriptLanguageConstant.class, tau.declaration()).value());

        var inf = requireBareConstantValue(registry, "INF", GdFloatType.FLOAT);
        assertEquals(Double.POSITIVE_INFINITY, assertInstanceOf(GdScriptLanguageConstant.class, inf.declaration()).value());

        var nan = requireBareConstantValue(registry, "NAN", GdFloatType.FLOAT);
        assertTrue(Double.isNaN(assertInstanceOf(GdScriptLanguageConstant.class, nan.declaration()).value()));
    }

    @Test
    void syntheticExtremeConstantsResolveThroughGlobalConstantNamespace() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var int32Max = registry.resolveValue("INT32_MAX");
        assertNotNull(int32Max);
        assertEquals(ScopeValueKind.CONSTANT, int32Max.kind());
        assertEquals(GdIntType.INT, int32Max.type());
        var int32MaxDeclaration = assertInstanceOf(ExtensionGlobalConstant.class, int32Max.declaration());
        assertEquals(2147483647L, int32MaxDeclaration.value());
        assertFalse(int32MaxDeclaration.isBitfield());

        var int64Min = requireBareConstantValue(registry, "INT64_MIN", GdIntType.INT);
        assertEquals(-9223372036854775808L, assertInstanceOf(ExtensionGlobalConstant.class, int64Min.declaration()).value());
        var uint32Max = requireBareConstantValue(registry, "UINT32_MAX", GdIntType.INT);
        assertEquals(4294967295L, assertInstanceOf(ExtensionGlobalConstant.class, uint32Max.declaration()).value());

        assertTrue(registry.isGlobalConstant("INT32_MAX"));
        assertNotNull(registry.findGlobalConstant("UINT8_MAX"));
    }

    @Test
    void jsonProvidedGlobalConstantsKeepPriorityOverSyntheticExtremeConstants() {
        var api = new ExtensionAPI(
                new ExtensionHeader(4, 6, 0, "dev", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalConstant("INT32_MAX", 123L, true)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var registry = new ClassRegistry(api);

        var resolved = registry.resolveValue("INT32_MAX");
        assertNotNull(resolved);
        var declaration = assertInstanceOf(ExtensionGlobalConstant.class, resolved.declaration());
        assertEquals(123L, declaration.value());
        assertTrue(declaration.isBitfield());
    }

    @Test
    void globalEnumNamesAndUnknownNamesKeepExistingResolution() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var variantType = registry.resolveValue("Variant.Type");
        assertNotNull(variantType);
        assertEquals(ScopeValueKind.GLOBAL_ENUM, variantType.kind());
        assertEquals(GdIntType.INT, variantType.type());
        assertInstanceOf(ExtensionGlobalEnum.class, variantType.declaration());

        assertNull(registry.resolveValue("TYPE_WHATEVER"));
        assertTrue(registry.resolveValue("GDCC_TEST_MISSING_BARE_NAME", ResolveRestriction.staticContext()).isNotFound());
        assertNull(registry.findGlobalEnumValueByBareName("GDCC_TEST_MISSING_BARE_NAME"));
        assertNull(registry.findGdScriptLanguageConstant("GDCC_TEST_MISSING_BARE_NAME"));
    }

    @Test
    void defaultApiKeepsBareGlobalValueNamespacesUniqueAndDisjoint() throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        var bareEnumValueNames = new HashSet<String>();
        for (var globalEnum : api.globalEnums()) {
            for (var value : globalEnum.values()) {
                assertTrue(
                        bareEnumValueNames.add(value.name()),
                        () -> "Duplicate bare global enum value name: " + value.name()
                );
            }
        }

        for (var singleton : api.singletons()) {
            assertFalse(bareEnumValueNames.contains(singleton.name()), () -> "Enum value shadows singleton: " + singleton.name());
        }
        for (var globalEnum : api.globalEnums()) {
            assertFalse(bareEnumValueNames.contains(globalEnum.name()), () -> "Enum value shadows enum group: " + globalEnum.name());
        }
        for (var globalConstant : api.globalConstants()) {
            assertFalse(bareEnumValueNames.contains(globalConstant.name()), () -> "Enum value shadows global constant: " + globalConstant.name());
        }
        for (var languageConstantName : List.of("PI", "TAU", "INF", "NAN")) {
            assertFalse(bareEnumValueNames.contains(languageConstantName), () -> "Enum value shadows language constant: " + languageConstantName);
        }
    }

    @Test
    void anonymousGlobalEnumGroupsStillExposeMembersByBareName() {
        var api = new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalEnum(null, false, List.of(
                        new ExtensionEnumValue(null, 9L),
                        new ExtensionEnumValue("GDCC_TEST_ORPHAN", 7L)
                ))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var registry = new ClassRegistry(api);

        var orphan = registry.resolveValue("GDCC_TEST_ORPHAN");
        assertNotNull(orphan);
        assertEquals(ScopeValueKind.CONSTANT, orphan.kind());
        assertEquals(GdIntType.INT, orphan.type());
        assertEquals(7L, assertInstanceOf(ExtensionEnumValue.class, orphan.declaration()).value());
        assertNull(registry.findGlobalEnumValueByBareName("GDCC_TEST_MISSING_BARE_NAME"));
    }

    @Test
    void duplicateBareEnumValueNamesFollowLastWinsEngineSemantics() {
        var api = new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(
                        new ExtensionGlobalEnum("FirstGroup", false, List.of(new ExtensionEnumValue("GDCC_TEST_DUP", 1L))),
                        new ExtensionGlobalEnum("SecondGroup", false, List.of(new ExtensionEnumValue("GDCC_TEST_DUP", 2L)))
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var registry = new ClassRegistry(api);

        var resolved = registry.resolveValue("GDCC_TEST_DUP");
        assertNotNull(resolved);
        assertEquals(2L, assertInstanceOf(ExtensionEnumValue.class, resolved.declaration()).value());
    }

    @Test
    void overlappingGlobalValueNamespacesFailFastAtConstruction() {
        var singletonCollisionApi = new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalEnum("Group", false, List.of(new ExtensionEnumValue("GameSingleton", 1L)))),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionSingleton("GameSingleton", "Node")),
                List.of()
        );
        var singletonCollision = assertThrows(IllegalStateException.class, () -> new ClassRegistry(singletonCollisionApi));
        assertTrue(singletonCollision.getMessage().contains("GameSingleton"));

        var languageConstantCollisionApi = new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalEnum("Group", false, List.of(new ExtensionEnumValue("PI", 1L)))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var languageConstantCollision = assertThrows(IllegalStateException.class, () -> new ClassRegistry(languageConstantCollisionApi));
        assertTrue(languageConstantCollision.getMessage().contains("PI"));
    }

    @Test
    void bareNameFindersExposeReadOnlyNamespaceQueries() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var typeNil = registry.findGlobalEnumValueByBareName("TYPE_NIL");
        assertNotNull(typeNil);
        assertEquals(0L, typeNil.value());
        assertNull(registry.findGlobalEnumValueByBareName("Variant.Type"));

        var pi = registry.findGdScriptLanguageConstant("PI");
        assertNotNull(pi);
        assertEquals(Math.PI, pi.value());
        assertNull(registry.findGdScriptLanguageConstant("INT32_MAX"));
    }

    private static @NotNull ScopeValue requireBareConstantValue(
            @NotNull ClassRegistry registry,
            @NotNull String name,
            @NotNull GdType expectedType
    ) {
        var value = registry.resolveValue(name);
        assertNotNull(value, () -> "Expected bare global constant to resolve: " + name);
        assertEquals(ScopeValueKind.CONSTANT, value.kind());
        assertEquals(expectedType, value.type());
        assertTrue(value.constant());
        assertFalse(value.writable());
        return value;
    }

    private static @NotNull ExtensionAPI createScopeFixtureApi() {
        return new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalConstant("GDCC_TEST_BIG_FLAG", 4_294_967_296L, true)),
                List.of(new ExtensionGlobalEnum("GameFlags", false, List.of(new ExtensionEnumValue("READY", 1)))),
                List.of(new ExtensionUtilityFunction("print_line", "String", "debug", false, 1, List.of())),
                List.of(),
                List.of(new ExtensionGdClass("Node", false, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of())),
                List.of(
                        new ExtensionSingleton("GameSingleton", "Node"),
                        new ExtensionSingleton("SharedSymbol", "Node")
                ),
                List.of()
        );
    }

    private static final class ParentScopeStub implements Scope {
        @Override
        public @Nullable Scope getParentScope() {
            return null;
        }

        @Override
        public void setParentScope(@Nullable Scope parentScope) {
        }

        @Override
        public @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
                @NotNull String name,
                @NotNull ResolveRestriction restriction
        ) {
            return ScopeLookupResult.foundAllowed(
                    new ScopeValue(name, GdVariantType.VARIANT, ScopeValueKind.LOCAL, null, false, true, false)
            );
        }

        @Override
        public @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctionsHere(
                @NotNull String name,
                @NotNull ResolveRestriction restriction
        ) {
            return ScopeLookupResult.notFound();
        }

        @Override
        public @NotNull ScopeLookupResult<ScopeTypeMeta> resolveTypeMetaHere(
                @NotNull String name,
                @NotNull ResolveRestriction restriction
        ) {
            return ScopeLookupResult.notFound();
        }
    }
}
