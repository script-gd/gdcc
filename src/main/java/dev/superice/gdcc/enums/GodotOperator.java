package dev.superice.gdcc.enums;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public enum GodotOperator {
    EQUAL,
    NOT_EQUAL,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    NEGATE,
    POSITIVE,
    MODULE,
    POWER,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    BIT_AND,
    BIT_OR,
    BIT_XOR,
    BIT_NOT,
    AND,
    OR,
    XOR,
    NOT,
    IN;

    public enum OperatorArity {
        UNARY,
        BINARY
    }

    public static @NotNull GodotOperator fromMetadataName(@NotNull String name) {
        return switch (Objects.requireNonNull(name, "name")) {
            case "==" -> EQUAL;
            case "!=" -> NOT_EQUAL;
            case "unary-" -> NEGATE;
            case "unary+" -> POSITIVE;
            case "~" -> BIT_NOT;
            case "and" -> AND;
            case "or" -> OR;
            case "xor" -> XOR;
            case "not" -> NOT;
            case "<" -> LESS;
            case ">" -> GREATER;
            case "<=" -> LESS_EQUAL;
            case ">=" -> GREATER_EQUAL;
            case "+" -> ADD;
            case "-" -> SUBTRACT;
            case "*" -> MULTIPLY;
            case "/" -> DIVIDE;
            case "%" -> MODULE;
            case "**" -> POWER;
            case "<<" -> SHIFT_LEFT;
            case ">>" -> SHIFT_RIGHT;
            case "&" -> BIT_AND;
            case "|" -> BIT_OR;
            case "^" -> BIT_XOR;
            case "in" -> IN;
            default -> throw new IllegalArgumentException("Unknown metadata operator: " + name);
        };
    }

    /// Source lexemes keep unary/binary syntax, so callers must pass arity to disambiguate `+` and `-`.
    public static @NotNull GodotOperator fromSourceLexeme(@NotNull String lexeme, @NotNull OperatorArity arity) {
        return switch (Objects.requireNonNull(arity, "arity")) {
            case UNARY -> fromUnarySourceLexeme(lexeme);
            case BINARY -> fromBinarySourceLexeme(lexeme);
        };
    }

    private static @NotNull GodotOperator fromUnarySourceLexeme(@NotNull String lexeme) {
        return switch (Objects.requireNonNull(lexeme, "lexeme")) {
            case "not", "!" -> NOT;
            case "-" -> NEGATE;
            case "+" -> POSITIVE;
            case "~" -> BIT_NOT;
            default -> throw new IllegalArgumentException("Unknown unary source operator: " + lexeme);
        };
    }

    private static @NotNull GodotOperator fromBinarySourceLexeme(@NotNull String lexeme) {
        return switch (Objects.requireNonNull(lexeme, "lexeme")) {
            case "and", "&&" -> AND;
            case "or", "||" -> OR;
            case "+" -> ADD;
            case "-" -> SUBTRACT;
            case "*" -> MULTIPLY;
            case "/" -> DIVIDE;
            case "%" -> MODULE;
            case "**" -> POWER;
            case "<<" -> SHIFT_LEFT;
            case ">>" -> SHIFT_RIGHT;
            case "&" -> BIT_AND;
            case "|" -> BIT_OR;
            case "^" -> BIT_XOR;
            case "==" -> EQUAL;
            case "!=" -> NOT_EQUAL;
            case "<" -> LESS;
            case "<=" -> LESS_EQUAL;
            case ">" -> GREATER;
            case ">=" -> GREATER_EQUAL;
            case "in" -> IN;
            default -> throw new IllegalArgumentException("Unknown binary source operator: " + lexeme);
        };
    }
}
