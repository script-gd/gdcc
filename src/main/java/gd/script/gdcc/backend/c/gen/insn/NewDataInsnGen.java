package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CFloatLiteralSupport;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralFloatInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralNilInsn;
import gd.script.gdcc.lir.insn.LiteralNodePathInsn;
import gd.script.gdcc.lir.insn.LiteralNullInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LiteralStringNameInsn;
import gd.script.gdcc.lir.insn.NewDataInstruction;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

public final class NewDataInsnGen implements CInsnGen<NewDataInstruction> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(
                GdInstruction.LITERAL_STRING_NAME,
                GdInstruction.LITERAL_STRING,
                GdInstruction.LITERAL_NODE_PATH,
                GdInstruction.LITERAL_FLOAT,
                GdInstruction.LITERAL_BOOL,
                GdInstruction.LITERAL_INT,
                GdInstruction.LITERAL_NULL,
                GdInstruction.LITERAL_NIL
        );
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var resultVar = resolveResultVariable(bodyBuilder, insn);
        switch (insn) {
            case LiteralStringNameInsn(_, var value) -> emitStringNameLiteral(bodyBuilder, resultVar, value);
            case LiteralStringInsn(_, var value) -> emitStringLiteral(bodyBuilder, resultVar, value);
            case LiteralNodePathInsn(_, var value) -> emitNodePathLiteral(bodyBuilder, resultVar, value);
            case LiteralFloatInsn(_, var value) ->
                    // Non-finite values must go through the shared normalizer: `Double.toString`
                    // emits `Infinity` / `NaN`, which are not valid C literals.
                    bodyBuilder.assignExpr(
                            requireWritableTarget(bodyBuilder, resultVar),
                            CFloatLiteralSupport.renderFloatLiteral(value),
                            GdFloatType.FLOAT
                    );
            case LiteralBoolInsn(_, var value) ->
                    bodyBuilder.assignExpr(requireWritableTarget(bodyBuilder, resultVar), Boolean.toString(value), GdBoolType.BOOL);
            case LiteralIntInsn(_, var value) ->
                    bodyBuilder.assignExpr(requireWritableTarget(bodyBuilder, resultVar), Long.toString(value), GdIntType.INT);
            case LiteralNullInsn _ -> {
                if (!(resultVar.type() instanceof GdObjectType objectType)) {
                    throw bodyBuilder.invalidInsn("literal_null requires an object result type, got '" +
                            resultVar.type().getTypeName() + "'");
                }
                bodyBuilder.assignExpr(
                        requireWritableTarget(bodyBuilder, resultVar),
                        bodyBuilder.helper().renderDefaultValueExprInC(objectType),
                        resultVar.type()
                );
            }
            case LiteralNilInsn _ -> emitNilLiteral(bodyBuilder, resultVar);
            default -> throw bodyBuilder.invalidInsn("Unsupported new-data instruction: " + insn.opcode().opcode());
        }
    }

    private void emitStringLiteral(@NotNull CBodyBuilder bodyBuilder, @NotNull LirVariable resultVar, @NotNull String value) {
        // Ordinary String payloads may legitimately contain leading/trailing quotes, so backend keeps
        // this path payload-only instead of guessing whether the producer accidentally passed a raw
        // source lexeme.
        // UTF-8 C string literals are direct `const char*`, not object values.
        var utf8Literal = bodyBuilder.valueOfCStringLiteral(value);
        if (resultVar.ref()) {
            bodyBuilder.callVoid(
                    "godot_string_new_with_utf8_chars",
                    List.of(bodyBuilder.valueOfVar(resultVar), utf8Literal)
            );
            return;
        }
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                "godot_new_String_with_utf8_chars",
                resultVar.type(),
                List.of(utf8Literal)
        );
    }

    private void emitStringNameLiteral(@NotNull CBodyBuilder bodyBuilder, @NotNull LirVariable resultVar, @NotNull String value) {
        if (looksLikeRawStringNameLexeme(value)) {
            throw bodyBuilder.invalidInsn(
                    "LiteralStringNameInsn must carry normalized runtime payload, not raw lexeme: " + value
            );
        }
        var utf8Literal = bodyBuilder.valueOfCStringLiteral(value);
        if (resultVar.ref()) {
            bodyBuilder.callVoid(
                    "godot_string_name_new_with_utf8_chars",
                    List.of(bodyBuilder.valueOfVar(resultVar), utf8Literal)
            );
            return;
        }
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                "godot_new_StringName_with_utf8_chars",
                resultVar.type(),
                List.of(utf8Literal)
        );
    }

    private void emitNodePathLiteral(@NotNull CBodyBuilder bodyBuilder, @NotNull LirVariable resultVar, @NotNull String value) {
        // Payload-only path like emitStringLiteral: a decoded payload may legitimately look like a
        // raw lexeme (`^"foo"`), so no shape rejection happens here. GDExtension has no in-place
        // NodePath constructor, so ref results stay fail-closed (frontend lowering only produces
        // non-ref temporary slots for literals; this branch is defensive).
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("Result variable ID " + resultVar.id() + " cannot be a reference");
        }
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                "godot_new_NodePath_with_utf8_chars",
                resultVar.type(),
                List.of(bodyBuilder.valueOfCStringLiteral(value))
        );
    }

    private void emitNilLiteral(@NotNull CBodyBuilder bodyBuilder, @NotNull LirVariable resultVar) {
        if (resultVar.ref()) {
            bodyBuilder.callVoid("godot_variant_new_nil", List.of(bodyBuilder.valueOfVar(resultVar)));
            return;
        }
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                "godot_new_Variant_nil",
                resultVar.type(),
                List.of()
        );
    }

    private @NotNull CBodyBuilder.TargetRef requireWritableTarget(@NotNull CBodyBuilder bodyBuilder,
                                                                  @NotNull LirVariable resultVar) {
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("Result variable ID " + resultVar.id() + " cannot be a reference");
        }
        return bodyBuilder.targetOfVar(resultVar);
    }

    private @NotNull LirVariable resolveResultVariable(@NotNull CBodyBuilder bodyBuilder,
                                                       @NotNull NewDataInstruction instruction) {
        if (instruction.resultId() == null) {
            throw bodyBuilder.invalidInsn("New data instruction missing result variable ID");
        }
        var resultVariable = bodyBuilder.func().getVariableById(Objects.requireNonNull(instruction.resultId()));
        if (resultVariable == null) {
            throw bodyBuilder.invalidInsn("Result variable ID " + instruction.resultId() + " does not exist");
        }
        validateResultType(bodyBuilder, instruction, resultVariable);
        return resultVariable;
    }

    private void validateResultType(@NotNull CBodyBuilder bodyBuilder,
                                    @NotNull NewDataInstruction instruction,
                                    @NotNull LirVariable resultVariable) {
        switch (instruction) {
            case LiteralStringNameInsn _ -> {
                if (!(resultVariable.type() instanceof GdStringNameType)) {
                    throw bodyBuilder.invalidInsn("Result variable ID " + resultVariable.id() + " is not of string name type");
                }
            }
            case LiteralStringInsn _ -> {
                if (!(resultVariable.type() instanceof GdStringType)) {
                    throw bodyBuilder.invalidInsn("Result variable ID " + resultVariable.id() + " is not of string type");
                }
            }
            case LiteralNodePathInsn _ -> {
                if (!(resultVariable.type() instanceof GdNodePathType)) {
                    throw bodyBuilder.invalidInsn("Result variable ID " + resultVariable.id() + " is not of node path type");
                }
            }
            case LiteralFloatInsn _ -> {
                if (!(resultVariable.type() instanceof GdFloatType)) {
                    throw bodyBuilder.invalidInsn("Result variable ID " + resultVariable.id() + " is not of float type");
                }
            }
            case LiteralBoolInsn _ -> {
                if (!(resultVariable.type() instanceof GdBoolType)) {
                    throw bodyBuilder.invalidInsn("Result variable ID " + resultVariable.id() + " is not of bool type");
                }
            }
            case LiteralIntInsn _ -> {
                if (!(resultVariable.type() instanceof GdIntType)) {
                    throw bodyBuilder.invalidInsn("Result variable ID " + resultVariable.id() + " is not of int type");
                }
            }
            case LiteralNullInsn _ -> {
                if (!(resultVariable.type() instanceof GdObjectType)) {
                    throw bodyBuilder.invalidInsn("Result variable ID " + resultVariable.id() + " is not of object type");
                }
            }
            case LiteralNilInsn _ -> {
                if (!(resultVariable.type() instanceof GdVariantType || resultVariable.type() instanceof GdNilType)) {
                    throw bodyBuilder.invalidInsn("Result variable ID " + resultVariable.id() + " is not of variant/nil type");
                }
            }
            default ->
                    throw bodyBuilder.invalidInsn("Unsupported new-data instruction: " + instruction.opcode().opcode());
        }
    }

    private boolean looksLikeRawStringNameLexeme(@NotNull String value) {
        return value.length() >= 3 && value.startsWith("&\"") && value.endsWith("\"");
    }
}
