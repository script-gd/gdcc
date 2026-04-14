package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        return new ScopeTypeMeta(
                builtinClass.name(),
                builtinClass.name(),
                GdStringType.STRING,
                ScopeTypeMetaKind.BUILTIN,
                builtinClass,
                false
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
