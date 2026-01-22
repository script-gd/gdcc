package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.exception.LirInsnParsingException;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirInstruction.*;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/// Simple LIR text parser for basic-block instruction lists.
public final class SimpleLirBlockInsnParser implements LirBlockInsnParser {

    // opcode -> enum map for O(1) lookup
    private static final Map<String, GdInstruction> OPCODE_MAP;

    static {
        var m = new HashMap<String, GdInstruction>();
        for (var gi : GdInstruction.values()) m.put(gi.opcode(), gi);
        OPCODE_MAP = Map.copyOf(m);
    }

    @Override
    public @NotNull List<LirInstruction> parse(@NotNull Reader reader) {
        try (var br = new BufferedReader(reader)) {
            var out = new ArrayList<LirInstruction>();
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty()) continue;
                try {
                    var insn = parseLine(line, lineNo);
                    out.add(insn);
                } catch (LirInsnParsingException e) {
                    throw e;
                } catch (Exception e) {
                    throw new LirInsnParsingException(lineNo, 1, line, e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private static LirInstruction parseLine(@NotNull String line, int lineNo) {
        var tr = new Tokenizer(line);
        tr.skipWhitespace();

        String resultId = null;
        if (tr.peek() == '$') {
            tr.consume();
            var id = tr.consumeWhile(Character::isJavaIdentifierPart);
            if (id.isEmpty()) {
                throw new LirInsnParsingException(lineNo, tr.column(), line, "Expected variable id after '$'");
            }
            resultId = id;
            tr.skipWhitespace();
            if (tr.peek() != '=') {
                throw new LirInsnParsingException(lineNo, tr.column(), line, "Expected '=' after result id");
            }
            tr.consume(); // '='
            tr.skipWhitespace();
        }

        int opcodeCol = tr.column();
        var opcode = tr.consumeWhile(c -> !Character.isWhitespace(c) && c != ';');
        if (opcode.isEmpty()) {
            throw new LirInsnParsingException(lineNo, tr.column(), line, "Missing opcode");
        }

        // collect operand tokens until ';' or end
        var tokens = new ArrayList<Token>();
        while (true) {
            tr.skipWhitespace();
            if (tr.eof() || tr.peek() == ';') break;
            var t = tr.nextToken();
            if (t == null) break;
            tokens.add(t);
        }

        // optional semicolon
        tr.skipWhitespace();
        if (!tr.eof() && tr.peek() == ';') {
            tr.consume();
        }

        // find instruction using map
        var instr = OPCODE_MAP.get(opcode);
        if (instr == null) {
            throw new LirInsnParsingException(lineNo, 1 + line.indexOf(opcode), line, "Unknown opcode: " + opcode);
        }

        // validate operand count
        if (tokens.size() < instr.minOperands() || tokens.size() > instr.maxOperands()) {
            throw new LirInsnParsingException(lineNo, tr.column(), line,
                    "Invalid operand count for '" + opcode + "' (got " + tokens.size() + ", expected " + instr.minOperands() + ".." + instr.maxOperands() + ")");
        }

        // map tokens to Operand
        var outOperands = new ArrayList<Operand>();
        var pattern = instr.operandKinds();
        boolean hasVarargs = false;
        int fixedCount = pattern.size();
        GdInstruction.OperandKind varargElemKind = GdInstruction.OperandKind.GENERIC; // default
        if (!pattern.isEmpty() && pattern.getLast() == GdInstruction.OperandKind.VARARGS) {
            hasVarargs = true;
            fixedCount = Math.max(0, pattern.size() - 1);
            // determine element kind by inspecting the last fixed kind and special-case opcodes
            if (fixedCount > 0) {
                var lastFixed = pattern.get(fixedCount - 1);
                if (lastFixed == GdInstruction.OperandKind.VARIABLE) {
                    varargElemKind = GdInstruction.OperandKind.VARIABLE;
                } else if (lastFixed == GdInstruction.OperandKind.STRING) {
                    // In some instructions (call-like, construct_lambda) the varargs are variables
                    switch (instr) {
                        case CALL_GLOBAL, CALL_INTRINSIC, CONSTRUCT_LAMBDA, CALL_STATIC_METHOD -> varargElemKind = GdInstruction.OperandKind.VARIABLE;
                    }
                }
                // otherwise keep GENERIC
            }
        }

        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            GdInstruction.OperandKind expected;
            if (i < fixedCount) {
                expected = pattern.get(i);
            } else if (hasVarargs) {
                expected = varargElemKind;
            } else {
                throw new LirInsnParsingException(lineNo, token.column, line, "Too many operands");
            }

            var operand = mapTokenToOperand(expected, token, lineNo, line);
            outOperands.add(operand);
        }

        var parsed = new ParsedLirInstruction(resultId, instr, List.copyOf(outOperands), lineNo, opcodeCol, line);
        return parsed.toConcrete();
    }

    private static Operand mapTokenToOperand(GdInstruction.OperandKind expected, Token token, int lineNo, String line) {
        try {
            switch (expected) {
                case VARIABLE -> {
                    if (token.kind != TokenKind.VARIABLE) {
                        throw new LirInsnParsingException(lineNo, token.column, line, "Expected variable operand (e.g. $0)");
                    }
                    return new VariableOperand(token.text);
                }
                case STRING -> {
                    if (token.kind != TokenKind.STRING) {
                        throw new LirInsnParsingException(lineNo, token.column, line, "Expected string operand (quoted)");
                    }
                    return new StringOperand(token.text);
                }
                case INT -> {
                    if (token.kind == TokenKind.INT) {
                        return new IntOperand(Integer.parseInt(token.text));
                    }
                    throw new LirInsnParsingException(lineNo, token.column, line, "Expected integer operand");
                }
                case FLOAT -> {
                    if (token.kind == TokenKind.FLOAT || token.kind == TokenKind.INT) {
                        return new FloatOperand(Float.parseFloat(token.text));
                    }
                    throw new LirInsnParsingException(lineNo, token.column, line, "Expected float operand");
                }
                case OPERATOR -> {
                    if (token.kind != TokenKind.STRING) {
                        throw new LirInsnParsingException(lineNo, token.column, line, "Expected operator name as quoted string");
                    }
                    var name = token.text;
                    try {
                        var op = GodotOperator.valueOf(name);
                        return new GdOperatorOperand(op);
                    } catch (IllegalArgumentException e) {
                        throw new LirInsnParsingException(lineNo, token.column, line, "Unknown operator: " + name);
                    }
                }
                case LABEL -> {
                    if (token.kind == TokenKind.IDENT || token.kind == TokenKind.INT) {
                        return new BasicBlockOperand(token.text);
                    }
                    throw new LirInsnParsingException(lineNo, token.column, line, "Expected label identifier");
                }
                case BOOL -> {
                    if (token.kind == TokenKind.BOOL) {
                        return new BooleanOperand(Boolean.parseBoolean(token.text));
                    }
                    throw new LirInsnParsingException(lineNo, token.column, line, "Expected boolean literal 'true' or 'false'");
                }
                case GENERIC -> {
                    // accept several kinds; map based on token kind
                    return switch (token.kind) {
                        case VARIABLE -> new VariableOperand(token.text);
                        case STRING -> new StringOperand(token.text);
                        case INT -> new IntOperand(Integer.parseInt(token.text));
                        case FLOAT -> new FloatOperand(Float.parseFloat(token.text));
                        case BOOL -> new BooleanOperand(Boolean.parseBoolean(token.text));
                        case IDENT -> new BasicBlockOperand(token.text);
                    };
                }
                default -> throw new LirInsnParsingException(lineNo, token.column, line, "Unsupported operand kind: " + expected);
            }
        } catch (NumberFormatException e) {
            throw new LirInsnParsingException(lineNo, token.column, line, "Invalid numeric literal: " + token.text);
        }
    }


    // Simple tokenizer
    private static final class Tokenizer {
        private final String s;
        private int pos = 0;
        private int col = 1;

        Tokenizer(String s) {
            this.s = s;
        }

        char peek() {
            if (pos >= s.length()) return '\0';
            return s.charAt(pos);
        }

        boolean eof() {
            return pos >= s.length();
        }

        void consume() {
            if (!eof()) {
                pos++;
                col++;
            }
        }

        int column() { return col; }

        void skipWhitespace() {
            while (!eof() && Character.isWhitespace(peek())) consume();
        }

        String consumeWhile(java.util.function.IntPredicate pred) {
            var sb = new StringBuilder();
            while (!eof()) {
                var c = peek();
                if (!pred.test(c)) break;
                sb.append(c);
                consume();
            }
            return sb.toString();
        }

        Token nextToken() {
            skipWhitespace();
            if (eof()) return null;
            int tokenCol = col;
            char c = peek();
            if (c == '$') {
                consume();
                var id = consumeWhile(Character::isJavaIdentifierPart);
                return new Token(TokenKind.VARIABLE, id, tokenCol);
            } else if (c == '"') {
                consume();
                var sb = new StringBuilder();
                boolean escaped = false;
                while (!eof()) {
                    var ch = peek();
                    consume();
                    if (escaped) {
                        switch (ch) {
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case '\\' -> sb.append('\\');
                            case '"' -> sb.append('"');
                            default -> sb.append(ch);
                        }
                        escaped = false;
                    } else {
                        if (ch == '\\') {
                            escaped = true;
                        } else if (ch == '"') {
                            break;
                        } else {
                            sb.append(ch);
                        }
                    }
                }
                return new Token(TokenKind.STRING, sb.toString(), tokenCol);
            } else {
                // unquoted token
                var txt = consumeWhile(c2 -> !Character.isWhitespace(c2) && c2 != ';');
                // classify
                if (txt.equals("true") || txt.equals("false")) {
                    return new Token(TokenKind.BOOL, txt, tokenCol);
                }
                // integer or float
                if (txt.matches("[+-]?\\d+")) {
                    return new Token(TokenKind.INT, txt, tokenCol);
                }
                if (txt.matches("[+-]?\\d*\\.\\d+([eE][+-]?\\d+)?") || txt.matches("[+-]?\\d+[eE][+-]?\\d+")) {
                    return new Token(TokenKind.FLOAT, txt, tokenCol);
                }
                // identifier/label
                return new Token(TokenKind.IDENT, txt, tokenCol);
            }
        }
    }

    private record Token(TokenKind kind, String text, int column) {
    }

    private enum TokenKind {
        VARIABLE,
        STRING,
        INT,
        FLOAT,
        BOOL,
        IDENT
    }
}
