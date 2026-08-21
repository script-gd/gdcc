package gd.script.gdcc.backend.c.gen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors the shared float literal normalization contract: recognized non-finite source-text
/// spellings map onto the header-provided C macros, every other literal passes through unchanged,
/// and IEEE values always render as valid C literals.
public class CFloatLiteralSupportTest {
    @Test
    @DisplayName("source-text inf family should map to godot_inf")
    void normalizeSourceShouldMapInfFamily() {
        assertEquals("godot_inf", CFloatLiteralSupport.normalizeSourceFloatLiteral("inf"));
        assertEquals("godot_inf", CFloatLiteralSupport.normalizeSourceFloatLiteral("+inf"));
        assertEquals("godot_inf", CFloatLiteralSupport.normalizeSourceFloatLiteral("infinity"));
        assertEquals("godot_inf", CFloatLiteralSupport.normalizeSourceFloatLiteral("+infinity"));
    }

    @Test
    @DisplayName("source-text negative inf family should map to -godot_inf")
    void normalizeSourceShouldMapNegativeInfFamily() {
        assertEquals("-godot_inf", CFloatLiteralSupport.normalizeSourceFloatLiteral("-inf"));
        assertEquals("-godot_inf", CFloatLiteralSupport.normalizeSourceFloatLiteral("-infinity"));
    }

    @Test
    @DisplayName("source-text nan should map to the math.h NAN macro")
    void normalizeSourceShouldMapNan() {
        assertEquals("NAN", CFloatLiteralSupport.normalizeSourceFloatLiteral("nan"));
    }

    @Test
    @DisplayName("source-text mapping should be case-insensitive and trim whitespace")
    void normalizeSourceShouldBeCaseInsensitiveAndTrim() {
        assertEquals("godot_inf", CFloatLiteralSupport.normalizeSourceFloatLiteral(" INF "));
        assertEquals("godot_inf", CFloatLiteralSupport.normalizeSourceFloatLiteral("Infinity"));
        assertEquals("NAN", CFloatLiteralSupport.normalizeSourceFloatLiteral("NaN"));
    }

    @Test
    @DisplayName("ordinary numeric source-text literals should pass through unchanged")
    void normalizeSourceShouldPassThroughNumericLiterals() {
        assertEquals("1.5", CFloatLiteralSupport.normalizeSourceFloatLiteral("1.5"));
        assertEquals("-2e3", CFloatLiteralSupport.normalizeSourceFloatLiteral("-2e3"));
        assertEquals("42", CFloatLiteralSupport.normalizeSourceFloatLiteral("42"));
    }

    @Test
    @DisplayName("isNonFiniteFloatLiteral should recognize the full non-finite spelling family")
    void isNonFiniteShouldRecognizeSpellingFamily() {
        assertTrue(CFloatLiteralSupport.isNonFiniteFloatLiteral("inf"));
        assertTrue(CFloatLiteralSupport.isNonFiniteFloatLiteral("-infinity"));
        assertTrue(CFloatLiteralSupport.isNonFiniteFloatLiteral("nan"));
        assertFalse(CFloatLiteralSupport.isNonFiniteFloatLiteral("1.5"));
        assertFalse(CFloatLiteralSupport.isNonFiniteFloatLiteral("infini"));
    }

    @Test
    @DisplayName("IEEE rendering should keep finite values as valid C literals")
    void renderShouldKeepFiniteValuesAsValidCLiterals() {
        assertEquals("1.5", CFloatLiteralSupport.renderFloatLiteral(1.5));
        assertEquals("-0.0", CFloatLiteralSupport.renderFloatLiteral(-0.0));
        assertEquals("1.0E308", CFloatLiteralSupport.renderFloatLiteral(1.0E308));
    }
}
