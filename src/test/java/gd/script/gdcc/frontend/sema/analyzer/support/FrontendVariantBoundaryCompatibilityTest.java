package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_PRIMITIVE_CAST,
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
    void primitiveCastDecisionIsLimitedToIntToFloatBoundary() throws Exception {
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_PRIMITIVE_CAST,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        new GdIntType(),
                        GdFloatType.FLOAT
                )
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.REJECT,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdFloatType.FLOAT,
                        GdIntType.INT
                )
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.REJECT,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdBoolType.BOOL,
                        GdFloatType.FLOAT
                )
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.REJECT,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        GdIntType.INT,
                        GdBoolType.BOOL
                )
        );
    }
}
