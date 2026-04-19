package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralFloatInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.LiteralNilInsn;
import dev.superice.gdcc.lir.insn.LiteralNullInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.NewDataInstruction;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import static dev.superice.gdcc.util.StringUtil.escapeStringLiteral;

public final class NewDataInsnGen implements CInsnGen<NewDataInstruction> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(
                GdInstruction.LITERAL_STRING_NAME,
                GdInstruction.LITERAL_STRING,
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
            case LiteralFloatInsn(_, var value) ->
                    bodyBuilder.assignExpr(requireWritableTarget(bodyBuilder, resultVar), Double.toString(value), GdFloatType.FLOAT);
            case LiteralBoolInsn(_, var value) ->
                    bodyBuilder.assignExpr(requireWritableTarget(bodyBuilder, resultVar), Boolean.toString(value), GdBoolType.BOOL);
            case LiteralIntInsn(_, var value) ->
                    bodyBuilder.assignExpr(requireWritableTarget(bodyBuilder, resultVar), Integer.toString(value), GdIntType.INT);
            case LiteralNullInsn _ ->
                    bodyBuilder.assignExpr(requireWritableTarget(bodyBuilder, resultVar), "NULL", resultVar.type());
            case LiteralNilInsn _ -> emitNilLiteral(bodyBuilder, resultVar);
            default -> throw bodyBuilder.invalidInsn("Unsupported new-data instruction: " + insn.opcode().opcode());
        }
    }

    private void emitStringLiteral(@NotNull CBodyBuilder bodyBuilder, @NotNull LirVariable resultVar, @NotNull String value) {
        var utf8Literal = utf8Literal(value);
        if (resultVar.ref()) {
            bodyBuilder.callVoid(
                    "godot_string_new_with_utf8_chars",
                    List.of(bodyBuilder.valueOfVar(resultVar), bodyBuilder.valueOfExpr(utf8Literal, GdObjectType.OBJECT))
            );
            return;
        }
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                "godot_new_String_with_utf8_chars",
                resultVar.type(),
                List.of(bodyBuilder.valueOfExpr(utf8Literal, GdObjectType.OBJECT))
        );
    }

    private void emitStringNameLiteral(@NotNull CBodyBuilder bodyBuilder, @NotNull LirVariable resultVar, @NotNull String value) {
        var normalizedValue = value;
        if (value.length() >= 3 && value.startsWith("&\"") && value.endsWith("\"")) {
            normalizedValue = StringUtil.unescapeQuoted(value.substring(2, value.length() - 1));
        }
        var utf8Literal = utf8Literal(normalizedValue);
        if (resultVar.ref()) {
            bodyBuilder.callVoid(
                    "godot_string_name_new_with_utf8_chars",
                    List.of(bodyBuilder.valueOfVar(resultVar), bodyBuilder.valueOfExpr(utf8Literal, GdObjectType.OBJECT))
            );
            return;
        }
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                "godot_new_StringName_with_utf8_chars",
                resultVar.type(),
                List.of(bodyBuilder.valueOfExpr(utf8Literal, GdObjectType.OBJECT))
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
            default -> throw bodyBuilder.invalidInsn("Unsupported new-data instruction: " + instruction.opcode().opcode());
        }
    }

    private @NotNull String utf8Literal(@NotNull String value) {
        return "u8\"" + escapeStringLiteral(value) + "\"";
    }
}
