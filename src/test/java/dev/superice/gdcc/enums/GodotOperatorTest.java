package dev.superice.gdcc.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GodotOperatorTest {
    private record MetadataCase(String name, GodotOperator expected) {
    }

    private record SourceCase(String lexeme, GodotOperator.OperatorArity arity, GodotOperator expected) {
    }

    @Test
    void fromMetadataNameSupportsCurrentCanonicalOperatorSet() {
        var cases = List.of(
                new MetadataCase("==", GodotOperator.EQUAL),
                new MetadataCase("!=", GodotOperator.NOT_EQUAL),
                new MetadataCase("unary-", GodotOperator.NEGATE),
                new MetadataCase("unary+", GodotOperator.POSITIVE),
                new MetadataCase("~", GodotOperator.BIT_NOT),
                new MetadataCase("and", GodotOperator.AND),
                new MetadataCase("or", GodotOperator.OR),
                new MetadataCase("xor", GodotOperator.XOR),
                new MetadataCase("not", GodotOperator.NOT),
                new MetadataCase("<", GodotOperator.LESS),
                new MetadataCase(">", GodotOperator.GREATER),
                new MetadataCase("<=", GodotOperator.LESS_EQUAL),
                new MetadataCase(">=", GodotOperator.GREATER_EQUAL),
                new MetadataCase("+", GodotOperator.ADD),
                new MetadataCase("-", GodotOperator.SUBTRACT),
                new MetadataCase("*", GodotOperator.MULTIPLY),
                new MetadataCase("/", GodotOperator.DIVIDE),
                new MetadataCase("%", GodotOperator.MODULE),
                new MetadataCase("**", GodotOperator.POWER),
                new MetadataCase("<<", GodotOperator.SHIFT_LEFT),
                new MetadataCase(">>", GodotOperator.SHIFT_RIGHT),
                new MetadataCase("&", GodotOperator.BIT_AND),
                new MetadataCase("|", GodotOperator.BIT_OR),
                new MetadataCase("^", GodotOperator.BIT_XOR),
                new MetadataCase("in", GodotOperator.IN)
        );

        for (var caseDef : cases) {
            assertEquals(caseDef.expected(), GodotOperator.fromMetadataName(caseDef.name()), caseDef.name());
        }
    }

    @Test
    void fromSourceLexemeSupportsFrontendAliasesAndArityDisambiguation() {
        var cases = List.of(
                new SourceCase("and", GodotOperator.OperatorArity.BINARY, GodotOperator.AND),
                new SourceCase("&&", GodotOperator.OperatorArity.BINARY, GodotOperator.AND),
                new SourceCase("or", GodotOperator.OperatorArity.BINARY, GodotOperator.OR),
                new SourceCase("||", GodotOperator.OperatorArity.BINARY, GodotOperator.OR),
                new SourceCase("not", GodotOperator.OperatorArity.UNARY, GodotOperator.NOT),
                new SourceCase("!", GodotOperator.OperatorArity.UNARY, GodotOperator.NOT),
                new SourceCase("-", GodotOperator.OperatorArity.UNARY, GodotOperator.NEGATE),
                new SourceCase("-", GodotOperator.OperatorArity.BINARY, GodotOperator.SUBTRACT),
                new SourceCase("+", GodotOperator.OperatorArity.UNARY, GodotOperator.POSITIVE),
                new SourceCase("+", GodotOperator.OperatorArity.BINARY, GodotOperator.ADD),
                new SourceCase("~", GodotOperator.OperatorArity.UNARY, GodotOperator.BIT_NOT),
                new SourceCase("*", GodotOperator.OperatorArity.BINARY, GodotOperator.MULTIPLY),
                new SourceCase("/", GodotOperator.OperatorArity.BINARY, GodotOperator.DIVIDE),
                new SourceCase("%", GodotOperator.OperatorArity.BINARY, GodotOperator.MODULE),
                new SourceCase("**", GodotOperator.OperatorArity.BINARY, GodotOperator.POWER),
                new SourceCase("<<", GodotOperator.OperatorArity.BINARY, GodotOperator.SHIFT_LEFT),
                new SourceCase(">>", GodotOperator.OperatorArity.BINARY, GodotOperator.SHIFT_RIGHT),
                new SourceCase("&", GodotOperator.OperatorArity.BINARY, GodotOperator.BIT_AND),
                new SourceCase("|", GodotOperator.OperatorArity.BINARY, GodotOperator.BIT_OR),
                new SourceCase("^", GodotOperator.OperatorArity.BINARY, GodotOperator.BIT_XOR),
                new SourceCase("==", GodotOperator.OperatorArity.BINARY, GodotOperator.EQUAL),
                new SourceCase("!=", GodotOperator.OperatorArity.BINARY, GodotOperator.NOT_EQUAL),
                new SourceCase("<", GodotOperator.OperatorArity.BINARY, GodotOperator.LESS),
                new SourceCase("<=", GodotOperator.OperatorArity.BINARY, GodotOperator.LESS_EQUAL),
                new SourceCase(">", GodotOperator.OperatorArity.BINARY, GodotOperator.GREATER),
                new SourceCase(">=", GodotOperator.OperatorArity.BINARY, GodotOperator.GREATER_EQUAL),
                new SourceCase("in", GodotOperator.OperatorArity.BINARY, GodotOperator.IN)
        );

        for (var caseDef : cases) {
            assertEquals(
                    caseDef.expected(),
                    GodotOperator.fromSourceLexeme(caseDef.lexeme(), caseDef.arity()),
                    caseDef.lexeme() + "@" + caseDef.arity()
            );
        }
    }

    @Test
    void factoriesFailClosedAcrossMetadataAndSourceProtocols() {
        assertThrows(IllegalArgumentException.class, () -> GodotOperator.fromMetadataName("&&"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GodotOperator.fromSourceLexeme("not in", GodotOperator.OperatorArity.BINARY)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GodotOperator.fromSourceLexeme("!", GodotOperator.OperatorArity.BINARY)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GodotOperator.fromSourceLexeme("in", GodotOperator.OperatorArity.UNARY)
        );
    }
}
