package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeTypeMetaKind;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendCallableLiteralArgumentSupportTest {
    @Test
    void encodeLiteralRankTreatsEmptyAggregateAsBestAndRejectAsZero() {
        var empty = FrontendContainerLiteralSemanticSupport.CandidateRank.EMPTY;
        var rejected = new FrontendContainerLiteralSemanticSupport.CandidateRank(true, 0, 0);
        var direct = new FrontendContainerLiteralSemanticSupport.CandidateRank(false, 4, 4);

        assertAll(
                () -> assertEquals(Integer.MAX_VALUE, FrontendCallableLiteralArgumentSupport.encodeLiteralRank(empty)),
                () -> assertEquals(0, FrontendCallableLiteralArgumentSupport.encodeLiteralRank(rejected)),
                () -> assertTrue(FrontendCallableLiteralArgumentSupport.encodeLiteralRank(direct) > 0),
                () -> assertTrue(
                        FrontendCallableLiteralArgumentSupport.encodeLiteralRank(empty)
                                > FrontendCallableLiteralArgumentSupport.encodeLiteralRank(direct)
                )
        );
    }

    @Test
    void mergeCandidateRanksUsesMinWorstAndSumTotal() {
        var left = new FrontendContainerLiteralSemanticSupport.CandidateRank(false, 2, 8);
        var right = new FrontendContainerLiteralSemanticSupport.CandidateRank(false, 3, 3);
        var merged = FrontendCallableLiteralArgumentSupport.mergeCandidateRanks(left, right);

        assertAll(
                () -> assertEquals(2, merged.worstRank()),
                () -> assertEquals(11, merged.totalRank()),
                () -> assertTrue(!merged.rejected())
        );
        var higherTotal = new FrontendContainerLiteralSemanticSupport.CandidateRank(false, 2, 25);
        assertTrue(FrontendContainerLiteralSemanticSupport.compareCandidateRanks(higherTotal, merged) < 0);
    }

    @Test
    void literalAggregateRankUsesFixedParameterTypeListForChainStyleViews() {
        var registry = emptyRegistry();
        var literal = arrayLiteralWithOneInt();
        FrontendCallableLiteralArgumentSupport.ChildSourceResolver childSources =
                _expression -> FrontendExpressionType.resolved(GdIntType.INT);
        var typedArrayRank = FrontendCallableLiteralArgumentSupport.literalAggregateRank(
                registry,
                List.of(new GdArrayType(GdIntType.INT)),
                List.of(literal),
                List.of(new GdArrayType(GdVariantType.VARIANT)),
                childSources
        );
        var stringArrayRank = FrontendCallableLiteralArgumentSupport.literalAggregateRank(
                registry,
                List.of(new GdArrayType(gd.script.gdcc.type.GdStringType.STRING)),
                List.of(literal),
                List.of(new GdArrayType(GdVariantType.VARIANT)),
                childSources
        );

        assertAll(
                () -> assertTrue(!typedArrayRank.rejected()),
                () -> assertTrue(stringArrayRank.rejected()
                        || FrontendContainerLiteralSemanticSupport.compareCandidateRanks(
                        typedArrayRank,
                        stringArrayRank
                ) < 0)
        );
        assertTrue(FrontendCallableLiteralArgumentSupport.isStrictlyMoreSpecificByLiteralAggregate(
                registry,
                List.of(new GdArrayType(GdIntType.INT)),
                List.of(new GdArrayType(GdVariantType.VARIANT)),
                List.of(literal),
                List.of(new GdArrayType(GdVariantType.VARIANT)),
                childSources,
                () -> false
        ));
    }

    @Test
    void rewriteArgumentTypesReplacesGenericArrayWithSelectedTypedArray() {
        var literal = emptyArrayLiteral();
        var rewritten = FrontendCallableLiteralArgumentSupport.rewriteArgumentTypes(
                List.of(literal),
                List.of(new GdArrayType(GdVariantType.VARIANT)),
                List.of(new GdArrayType(GdIntType.INT))
        );

        assertEquals("Array[int]", rewritten.getFirst().getTypeName());
    }

    @Test
    void rewriteArgumentTypesKeepsGenericWhenParameterIsVariant() {
        var literal = emptyArrayLiteral();
        var rewritten = FrontendCallableLiteralArgumentSupport.rewriteArgumentTypes(
                List.of(literal),
                List.of(new GdArrayType(GdVariantType.VARIANT)),
                List.of(GdVariantType.VARIANT)
        );

        assertEquals("Array", rewritten.getFirst().getTypeName());
    }

    @Test
    void resolveConstructorKeepsEmptyLiteralAgainstTypedArrayOverloadsAmbiguous() {
        var builtinClass = builtinWithUnaryConstructors(
                "String",
                List.of("Array[int]", "Array[String]")
        );
        var registry = newRegistry(builtinClass);
        var emptyLiteral = emptyArrayLiteral();
        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(
                registry,
                builtinTypeMeta(builtinClass),
                List.of(emptyLiteral),
                List.of(new GdArrayType(GdVariantType.VARIANT)),
                _expression -> FrontendExpressionType.resolved(GdIntType.INT)
        );

        assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolution.status()),
                () -> assertEquals(ScopeOwnerKind.BUILTIN, resolution.ownerKind()),
                () -> assertTrue(resolution.detailReason().contains("Ambiguous constructor overload"))
        );
    }

    @Test
    void resolveConstructorSelectsArrayIntWhenLiteralElementsAreInts() {
        var builtinClass = builtinWithUnaryConstructors(
                "String",
                List.of("Array[int]", "Array[String]")
        );
        var registry = newRegistry(builtinClass);
        var literal = arrayLiteralWithOneInt();
        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(
                registry,
                builtinTypeMeta(builtinClass),
                List.of(literal),
                List.of(new GdArrayType(GdVariantType.VARIANT)),
                _expression -> FrontendExpressionType.resolved(GdIntType.INT)
        );

        var selected = assertInstanceOf(ExtensionBuiltinClass.ConstructorInfo.class, resolution.declarationSite());
        assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolution.status()),
                () -> assertEquals("Array[int]", selected.arguments().getFirst().type())
        );
    }

    @Test
    void resolveConstructorRejectsIncompatibleLiteralElements() {
        var builtinClass = builtinWithUnaryConstructors("String", List.of("Array[String]"));
        var registry = newRegistry(builtinClass);
        var literal = arrayLiteralWithOneInt();
        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(
                registry,
                builtinTypeMeta(builtinClass),
                List.of(literal),
                List.of(new GdArrayType(GdVariantType.VARIANT)),
                _expression -> FrontendExpressionType.resolved(GdIntType.INT)
        );

        assertEquals(FrontendCallResolutionStatus.FAILED, resolution.status());
        assertTrue(resolution.detailReason().contains("No applicable constructor overload")
                || resolution.detailReason().contains("Cannot assign"));
    }

    private static @NotNull ArrayExpression emptyArrayLiteral() {
        return new ArrayExpression(List.of(), false, null);
    }

    private static @NotNull ArrayExpression arrayLiteralWithOneInt() {
        Expression element = new LiteralExpression("number", "1", null);
        return new ArrayExpression(List.of(element), false, null);
    }

    private static @NotNull ClassRegistry emptyRegistry() {
        return new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
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
                    List.of(new ExtensionFunctionArgument("arg0", parameterTypes.get(index), null, null))
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
}
