package gd.script.gdcc.util;

import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Direct contract coverage for the shared `is` / `is not` static fold tree.
class TypeTestFoldUtilTest {
    private static ClassRegistry registry;

    @BeforeAll
    static void setUp() throws Exception {
        registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
    }

    @Test
    void variantTargetAlwaysTrue() {
        assertEquals(TypeTestFoldResult.TRUE, TypeTestFoldUtil.fold(registry, GdIntType.INT, GdVariantType.VARIANT));
        assertEquals(TypeTestFoldResult.TRUE, TypeTestFoldUtil.fold(registry, GdNilType.NIL, GdVariantType.VARIANT));
        assertEquals(TypeTestFoldResult.TRUE, TypeTestFoldUtil.fold(registry, GdVariantType.VARIANT, GdVariantType.VARIANT));
    }

    @Test
    void variantOperandAgainstNonVariantStaysOpen() {
        assertEquals(TypeTestFoldResult.RUNTIME_OPEN, TypeTestFoldUtil.fold(registry, GdVariantType.VARIANT, GdIntType.INT));
    }

    @Test
    void nilAgainstNonVariantIsFalse() {
        assertEquals(TypeTestFoldResult.FALSE, TypeTestFoldUtil.fold(registry, GdNilType.NIL, GdIntType.INT));
        assertEquals(TypeTestFoldResult.FALSE, TypeTestFoldUtil.fold(registry, GdNilType.NIL, new GdObjectType("Node")));
    }

    @Test
    void exactNonObjectMatchIsTrue() {
        assertEquals(TypeTestFoldResult.TRUE, TypeTestFoldUtil.fold(registry, GdIntType.INT, GdIntType.INT));
        assertEquals(
                TypeTestFoldResult.TRUE,
                TypeTestFoldUtil.fold(registry, new GdArrayType(GdIntType.INT), new GdArrayType(GdIntType.INT))
        );
    }

    @Test
    void objectExactOrUpcastStaysOpenForNullCheck() {
        assertEquals(
                TypeTestFoldResult.RUNTIME_OPEN,
                TypeTestFoldUtil.fold(registry, new GdObjectType("Node"), new GdObjectType("Node"))
        );
        assertEquals(
                TypeTestFoldResult.RUNTIME_OPEN,
                TypeTestFoldUtil.fold(registry, new GdObjectType("Node2D"), new GdObjectType("Node"))
        );
    }

    @Test
    void objectParentToChildStaysOpen() {
        assertEquals(
                TypeTestFoldResult.RUNTIME_OPEN,
                TypeTestFoldUtil.fold(registry, new GdObjectType("Node"), new GdObjectType("Node2D"))
        );
    }

    @Test
    void disjointFamiliesFoldFalse() {
        assertEquals(TypeTestFoldResult.FALSE, TypeTestFoldUtil.fold(registry, GdIntType.INT, new GdObjectType("Node")));
        assertEquals(TypeTestFoldResult.FALSE, TypeTestFoldUtil.fold(registry, new GdObjectType("Node"), GdIntType.INT));
        assertEquals(TypeTestFoldResult.FALSE, TypeTestFoldUtil.fold(registry, GdIntType.INT, GdFloatType.FLOAT));
    }

    @Test
    void typedContainerIsBareTargetFoldsTrue() {
        assertEquals(
                TypeTestFoldResult.TRUE,
                TypeTestFoldUtil.fold(registry, new GdArrayType(GdIntType.INT), new GdArrayType(GdVariantType.VARIANT))
        );
        assertEquals(
                TypeTestFoldResult.TRUE,
                TypeTestFoldUtil.fold(
                        registry,
                        new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                        new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT)
                )
        );
    }

    @Test
    void bareContainerIsParameterizedTargetStaysRuntimeOpen() {
        assertEquals(
                TypeTestFoldResult.RUNTIME_OPEN,
                TypeTestFoldUtil.fold(registry, new GdArrayType(GdVariantType.VARIANT), new GdArrayType(GdIntType.INT))
        );
        assertEquals(
                TypeTestFoldResult.RUNTIME_OPEN,
                TypeTestFoldUtil.fold(
                        registry,
                        new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                        new GdDictionaryType(GdStringType.STRING, GdIntType.INT)
                )
        );
    }

    @Test
    void parameterizedElementMismatchFoldsFalse() {
        assertEquals(
                TypeTestFoldResult.FALSE,
                TypeTestFoldUtil.fold(registry, new GdArrayType(GdStringType.STRING), new GdArrayType(GdIntType.INT))
        );
    }
}
