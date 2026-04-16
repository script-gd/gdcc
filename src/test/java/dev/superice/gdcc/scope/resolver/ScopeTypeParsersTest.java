package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopeTypeParsersTest {
    @Test
    void parseExtensionTypeMetadataShouldSupportTypedDictionaryMetadata() {
        assertEquals(
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                ScopeTypeParsers.parseExtensionTypeMetadata(
                        "typeddictionary::String;int",
                        "test typed-dictionary metadata"
                )
        );
        assertEquals(
                new GdDictionaryType(GdStringType.STRING, new GdArrayType(GdVariantType.VARIANT)),
                ScopeTypeParsers.parseExtensionTypeMetadata(
                        "typeddictionary::String;Array",
                        "test typed-dictionary array family leaf"
                )
        );
    }

    @Test
    void parseExtensionTypeMetadataShouldRejectMalformedTypedDictionaryMetadata() {
        var malformedSamples = List.of(
                "typeddictionary::",
                "typeddictionary::String",
                "typeddictionary::String;",
                "typeddictionary::;int",
                "typeddictionary::String;int;bool"
        );

        assertAll(malformedSamples.stream()
                .map(sample -> rejectTypeMetadata(sample, "test malformed typed-dictionary metadata")));
    }

    @Test
    void parseExtensionTypeMetadataShouldRejectUnsupportedTypedDictionaryLeaves() {
        var unsupportedSamples = List.of(
                "typeddictionary::String;Array[int]",
                "typeddictionary::String;Dictionary[int, String]",
                "typeddictionary::String;typedarray::StringName",
                "typeddictionary::String;void"
        );

        assertAll(unsupportedSamples.stream()
                .map(sample -> rejectTypeMetadata(sample, "test unsupported typed-dictionary leaf")));
    }

    private static Executable rejectTypeMetadata(String sample, String typeUseSite) {
        return () -> assertThrows(
                IllegalArgumentException.class,
                () -> ScopeTypeParsers.parseExtensionTypeMetadata(sample, typeUseSite),
                sample
        );
    }
}
