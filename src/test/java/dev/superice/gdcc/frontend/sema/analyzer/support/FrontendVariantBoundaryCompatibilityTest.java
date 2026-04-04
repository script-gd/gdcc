package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
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
                FrontendVariantBoundaryCompatibility.Decision.REJECT,
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
}
