package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;

class FrontendVariantBoundaryCompatibilityTest {
    @Test
    void decideSeparatesDirectPackUnpackAndRejectedPairs() throws Exception {
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_PACK,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdIntType.INT,
                        GdVariantType.VARIANT
                )
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_UNPACK,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdVariantType.VARIANT,
                        GdIntType.INT
                )
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_DIRECT,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdVariantType.VARIANT,
                        GdVariantType.VARIANT
                )
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_DIRECT,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdIntType.INT,
                        GdIntType.INT
                )
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_LITERAL_NULL,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdNilType.NIL,
                        GdObjectType.OBJECT
                )
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_INTRINSIC_CAST,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdIntType.INT,
                        GdFloatType.FLOAT
                )
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.REJECT,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdStringType.STRING,
                        GdIntType.INT
                )
        );
    }

    @Test
    void intrinsicCastDecisionAllowsOnlyDocumentedScalarAndVectorWidening() throws Exception {
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertAll(
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_INTRINSIC_CAST,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                new GdIntType(),
                                GdFloatType.FLOAT
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_INTRINSIC_CAST,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdIntVectorType.VECTOR2I,
                                GdFloatVectorType.VECTOR2
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_INTRINSIC_CAST,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdIntVectorType.VECTOR3I,
                                GdFloatVectorType.VECTOR3
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_INTRINSIC_CAST,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdIntVectorType.VECTOR4I,
                                GdFloatVectorType.VECTOR4
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdFloatType.FLOAT,
                                GdIntType.INT
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdBoolType.BOOL,
                                GdFloatType.FLOAT
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdIntType.INT,
                                GdBoolType.BOOL
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdFloatVectorType.VECTOR3,
                                GdIntVectorType.VECTOR3I
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdIntVectorType.VECTOR2I,
                                GdFloatVectorType.VECTOR3
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                new GdIntVectorType(5),
                                new GdFloatVectorType(5)
                        )
                )
        );
    }

    @Test
    void vectorWideningDoesNotReplaceVariantBoundaryDecisions() throws Exception {
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertAll(
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_PACK,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdIntVectorType.VECTOR3I,
                                GdVariantType.VARIANT
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_UNPACK,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdVariantType.VARIANT,
                                GdFloatVectorType.VECTOR3
                        )
                )
        );
    }

    @Test
    void stringAndStringNameUseOnlyDocumentedConstructorMaterializationBoundaries() throws Exception {
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertAll(
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_BUILTIN_CONSTRUCTOR,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdStringType.STRING,
                                GdStringNameType.STRING_NAME
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_BUILTIN_CONSTRUCTOR,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdStringNameType.STRING_NAME,
                                GdStringType.STRING
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdStringType.STRING,
                                GdNodePathType.NODE_PATH
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdNodePathType.NODE_PATH,
                                GdStringType.STRING
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdStringType.STRING,
                                GdIntType.INT
                        )
                ),
                () -> assertEquals(
                        FrontendVariantBoundaryCompatibility.Decision.REJECT,
                        FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                                classRegistry,
                                GdStringNameType.STRING_NAME,
                                GdIntType.INT
                        )
                )
        );
    }

    @Test
    void decisionSpecificityRankOrdersConstructorMaterializationWithIntrinsicCast() {
        assertAll(
                () -> assertEquals(
                        4,
                        FrontendVariantBoundaryCompatibility.decisionSpecificityRank(
                                FrontendVariantBoundaryCompatibility.Decision.ALLOW_DIRECT
                        )
                ),
                () -> assertEquals(
                        3,
                        FrontendVariantBoundaryCompatibility.decisionSpecificityRank(
                                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_LITERAL_NULL
                        )
                ),
                () -> assertEquals(
                        2,
                        FrontendVariantBoundaryCompatibility.decisionSpecificityRank(
                                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_INTRINSIC_CAST
                        )
                ),
                () -> assertEquals(
                        2,
                        FrontendVariantBoundaryCompatibility.decisionSpecificityRank(
                                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_BUILTIN_CONSTRUCTOR
                        )
                ),
                () -> assertEquals(
                        1,
                        FrontendVariantBoundaryCompatibility.decisionSpecificityRank(
                                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_PACK
                        )
                ),
                () -> assertEquals(
                        1,
                        FrontendVariantBoundaryCompatibility.decisionSpecificityRank(
                                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_UNPACK
                        )
                ),
                () -> assertEquals(
                        0,
                        FrontendVariantBoundaryCompatibility.decisionSpecificityRank(
                                FrontendVariantBoundaryCompatibility.Decision.REJECT
                        )
                )
        );
    }

    @Test
    void frontendBoundarySpecificityRankMatchesRepresentativeDecisionRanks() throws Exception {
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertAll(
                () -> assertEquals(
                        4,
                        FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                                classRegistry,
                                GdStringType.STRING,
                                GdStringType.STRING
                        )
                ),
                () -> assertEquals(
                        3,
                        FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                                classRegistry,
                                GdNilType.NIL,
                                GdObjectType.OBJECT
                        )
                ),
                () -> assertEquals(
                        2,
                        FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                                classRegistry,
                                GdIntType.INT,
                                GdFloatType.FLOAT
                        )
                ),
                () -> assertEquals(
                        2,
                        FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                                classRegistry,
                                GdStringType.STRING,
                                GdStringNameType.STRING_NAME
                        )
                ),
                () -> assertEquals(
                        1,
                        FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                                classRegistry,
                                GdIntType.INT,
                                GdVariantType.VARIANT
                        )
                ),
                () -> assertEquals(
                        1,
                        FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                                classRegistry,
                                GdVariantType.VARIANT,
                                GdIntType.INT
                        )
                ),
                () -> assertEquals(
                        0,
                        FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                                classRegistry,
                                GdStringNameType.STRING_NAME,
                                GdIntType.INT
                        )
                )
        );
    }
}
