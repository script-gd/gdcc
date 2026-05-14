package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeTypeMetaKind;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendConstructorResolutionSupportTest {
    @Test
    void resolveConstructorUsesBuiltinUnaryVariantShortcutBeforeGenericRanking() {
        var builtinClass = stringBuiltinWithAmbiguousConstructors();
        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(
                newRegistry(builtinClass),
                builtinTypeMeta(builtinClass),
                List.of(GdVariantType.VARIANT)
        );

        assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolution.status()),
                () -> assertEquals(ScopeOwnerKind.BUILTIN, resolution.ownerKind()),
                () -> assertSame(builtinClass, resolution.declarationSite()),
                () -> assertNull(resolution.detailReason())
        );
    }

    @Test
    void resolveConstructorKeepsMultiArgumentBuiltinRankingFailClosed() {
        var builtinClass = stringBuiltinWithAmbiguousPairConstructors();
        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(
                newRegistry(builtinClass),
                builtinTypeMeta(builtinClass),
                List.of(GdVariantType.VARIANT, GdVariantType.VARIANT)
        );

        assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolution.status()),
                () -> assertEquals(ScopeOwnerKind.BUILTIN, resolution.ownerKind()),
                () -> assertSame(builtinClass, resolution.declarationSite()),
                () -> assertTrue(resolution.detailReason().contains("Ambiguous constructor overload"))
        );
    }

    @Test
    void resolveConstructorPrefersExactVectoriConstructorOverVectorWideningAndVariantPackForAllVectorDimensions() {
        assertAll(
                () -> assertExactVectoriWinsOverWideningAndVariant("Vector2", "Vector2i", GdIntVectorType.VECTOR2I),
                () -> assertExactVectoriWinsOverWideningAndVariant("Vector3", "Vector3i", GdIntVectorType.VECTOR3I),
                () -> assertExactVectoriWinsOverWideningAndVariant("Vector4", "Vector4i", GdIntVectorType.VECTOR4I)
        );
    }

    @Test
    void resolveConstructorPrefersVectorWideningOverVariantPackForAllVectorDimensions() {
        assertAll(
                () -> assertVectorWideningWinsOverVariant("Vector2", GdIntVectorType.VECTOR2I),
                () -> assertVectorWideningWinsOverVariant("Vector3", GdIntVectorType.VECTOR3I),
                () -> assertVectorWideningWinsOverVariant("Vector4", GdIntVectorType.VECTOR4I)
        );
    }

    @Test
    void resolveConstructorRejectsReverseAndWrongDimensionVectorWidening() {
        var reverseTarget = builtinWithUnaryConstructors("Vector3i", List.of("Vector3i"));
        var reverseResolution = FrontendConstructorResolutionSupport.resolveConstructor(
                newRegistry(reverseTarget),
                builtinTypeMeta(reverseTarget),
                List.of(GdFloatVectorType.VECTOR3)
        );

        var wrongDimensionTarget = builtinWithUnaryConstructors("Vector3", List.of("Vector3"));
        var wrongDimensionResolution = FrontendConstructorResolutionSupport.resolveConstructor(
                newRegistry(wrongDimensionTarget),
                builtinTypeMeta(wrongDimensionTarget),
                List.of(GdIntVectorType.VECTOR2I)
        );

        assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, reverseResolution.status()),
                () -> assertTrue(reverseResolution.detailReason().contains("Vector3")),
                () -> assertTrue(reverseResolution.detailReason().contains("Vector3i")),
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, wrongDimensionResolution.status()),
                () -> assertTrue(wrongDimensionResolution.detailReason().contains("Vector2i")),
                () -> assertTrue(wrongDimensionResolution.detailReason().contains("Vector3"))
        );
    }

    private static void assertExactVectoriWinsOverWideningAndVariant(
            @NotNull String constructorType,
            @NotNull String exactParameterType,
            @NotNull GdType sourceType
    ) {
        var builtinClass = builtinWithUnaryConstructors(
                constructorType,
                List.of(constructorType, "Variant", exactParameterType)
        );

        var selected = assertResolvedConstructor(builtinClass, List.of(sourceType));

        assertEquals(
                exactParameterType,
                selected.arguments().getFirst().type(),
                constructorType + " constructor should prefer exact " + exactParameterType
                        + " over vector widening and Variant pack"
        );
    }

    private static void assertVectorWideningWinsOverVariant(
            @NotNull String constructorType,
            @NotNull GdType sourceType
    ) {
        var builtinClass = builtinWithUnaryConstructors(constructorType, List.of("Variant", constructorType));

        var selected = assertResolvedConstructor(builtinClass, List.of(sourceType));

        assertEquals(
                constructorType,
                selected.arguments().getFirst().type(),
                constructorType + " constructor should prefer vector widening over Variant pack"
        );
    }

    private static @NotNull ExtensionBuiltinClass.ConstructorInfo assertResolvedConstructor(
            @NotNull ExtensionBuiltinClass builtinClass,
            @NotNull List<GdType> argumentTypes
    ) {
        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(
                newRegistry(builtinClass),
                builtinTypeMeta(builtinClass),
                argumentTypes
        );

        assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolution.status()),
                () -> assertEquals(ScopeOwnerKind.BUILTIN, resolution.ownerKind()),
                () -> assertNull(resolution.detailReason())
        );
        return assertInstanceOf(ExtensionBuiltinClass.ConstructorInfo.class, resolution.declarationSite());
    }

    private static @NotNull ClassRegistry newRegistry(@NotNull ExtensionBuiltinClass builtinClass) {
        return new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(builtinClass),
                List.of(),
                List.of(),
                List.of()
        ));
    }

    private static @NotNull ScopeTypeMeta builtinTypeMeta(@NotNull ExtensionBuiltinClass builtinClass) {
        var instanceType = Objects.requireNonNull(
                ClassRegistry.tryParseTextType(builtinClass.name()),
                "synthetic builtin test type must be parseable"
        );
        return new ScopeTypeMeta(
                builtinClass.name(),
                builtinClass.name(),
                instanceType,
                ScopeTypeMetaKind.BUILTIN,
                builtinClass,
                false
        );
    }

    private static @NotNull ExtensionBuiltinClass builtinWithUnaryConstructors(
            @NotNull String className,
            @NotNull List<String> parameterTypes
    ) {
        var constructors = new ArrayList<ExtensionBuiltinClass.ConstructorInfo>();
        for (var index = 0; index < parameterTypes.size(); index++) {
            constructors.add(new ExtensionBuiltinClass.ConstructorInfo(
                    className,
                    index,
                    List.of(new ExtensionFunctionArgument("value", parameterTypes.get(index), null, null))
            ));
        }
        return new ExtensionBuiltinClass(
                className,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.copyOf(constructors),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionBuiltinClass stringBuiltinWithAmbiguousConstructors() {
        return new ExtensionBuiltinClass(
                "String",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ExtensionBuiltinClass.ConstructorInfo(
                                "String",
                                0,
                                List.of(new ExtensionFunctionArgument("value", "int", null, null))
                        ),
                        new ExtensionBuiltinClass.ConstructorInfo(
                                "String",
                                1,
                                List.of(new ExtensionFunctionArgument("value", "String", null, null))
                        )
                ),
                List.of(),
                List.of()
        );
    }

    /// Keep one synthetic multi-arg ambiguity so the unary Variant shortcut cannot silently widen
    /// into a generic “all Variant arguments are fine” relaxation.
    private static @NotNull ExtensionBuiltinClass stringBuiltinWithAmbiguousPairConstructors() {
        return new ExtensionBuiltinClass(
                "String",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ExtensionBuiltinClass.ConstructorInfo(
                                "String",
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("first", "int", null, null),
                                        new ExtensionFunctionArgument("second", "String", null, null)
                                )
                        ),
                        new ExtensionBuiltinClass.ConstructorInfo(
                                "String",
                                1,
                                List.of(
                                        new ExtensionFunctionArgument("first", "String", null, null),
                                        new ExtensionFunctionArgument("second", "int", null, null)
                                )
                        )
                ),
                List.of(),
                List.of()
        );
    }
}
