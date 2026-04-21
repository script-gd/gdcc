package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendSubscriptSemanticSupportTest {
    @Test
    void resolveSubscriptTypeResolvesSupportedContainerFamilies() {
        var support = new FrontendSubscriptSemanticSupport(newRegistry(List.of()));

        var arrayResult = support.resolveSubscriptType(
                new GdArrayType(GdIntType.INT),
                List.of(GdIntType.INT),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, arrayResult.status());
        assertEquals("int", Objects.requireNonNull(arrayResult.publishedType()).getTypeName());

        var dictionaryResult = support.resolveSubscriptType(
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                List.of(GdStringType.STRING),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, dictionaryResult.status());
        assertEquals("int", Objects.requireNonNull(dictionaryResult.publishedType()).getTypeName());

        var packedResult = support.resolveSubscriptType(
                GdPackedNumericArrayType.PACKED_INT32_ARRAY,
                List.of(GdIntType.INT),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, packedResult.status());
        assertEquals("int", Objects.requireNonNull(packedResult.publishedType()).getTypeName());
    }

    @Test
    void resolveSubscriptTypePublishesDynamicVariantAndAcceptsSharedVariantBoundary() {
        var support = new FrontendSubscriptSemanticSupport(newRegistry(List.of()));

        var dynamicResult = support.resolveSubscriptType(
                GdVariantType.VARIANT,
                List.of(GdIntType.INT),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicResult.status());
        assertEquals(GdVariantType.VARIANT, dynamicResult.publishedType());

        var plainDictionaryResult = support.resolveSubscriptType(
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                List.of(GdStringType.STRING),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, plainDictionaryResult.status());
        assertEquals(GdVariantType.VARIANT, plainDictionaryResult.publishedType());

        var variantKeyResult = support.resolveSubscriptType(
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                List.of(GdVariantType.VARIANT),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, variantKeyResult.status());
        assertEquals("int", Objects.requireNonNull(variantKeyResult.publishedType()).getTypeName());
    }

    @Test
    void resolveSubscriptTypeRejectsBadKeysOutsideSharedVariantBoundary() {
        var support = new FrontendSubscriptSemanticSupport(newRegistry(List.of()));

        var badKeyResult = support.resolveSubscriptType(
                new GdArrayType(GdIntType.INT),
                List.of(GdStringType.STRING),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, badKeyResult.status());
        assertTrue(Objects.requireNonNull(badKeyResult.detailReason()).contains("not frontend-boundary compatible"));
    }

    @Test
    void resolveSubscriptTypeDistinguishesUnsupportedKeyedAndPlainNonContainerRoutes() {
        var support = new FrontendSubscriptSemanticSupport(newRegistry(List.of(keyedStringBuiltin())));

        var keyedBuiltinResult = support.resolveSubscriptType(
                GdStringType.STRING,
                List.of(GdIntType.INT),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, keyedBuiltinResult.status());
        assertTrue(Objects.requireNonNull(keyedBuiltinResult.detailReason()).contains("keyed access metadata"));

        var scalarResult = support.resolveSubscriptType(
                GdIntType.INT,
                List.of(GdIntType.INT),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, scalarResult.status());
        assertTrue(Objects.requireNonNull(scalarResult.detailReason()).contains("does not support"));

        var multiArgumentResult = support.resolveSubscriptType(
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                List.of(GdStringType.STRING, GdStringType.STRING),
                "subscript expression"
        );
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, multiArgumentResult.status());
        assertTrue(Objects.requireNonNull(multiArgumentResult.detailReason()).contains("exactly one key/index argument"));
    }

    private static @NotNull ClassRegistry newRegistry(@NotNull List<ExtensionBuiltinClass> builtins) {
        return new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                builtins,
                List.of(),
                List.of(),
                List.of()
        ));
    }

    private static @NotNull ExtensionBuiltinClass keyedStringBuiltin() {
        return new ExtensionBuiltinClass(
                "String",
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
