package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.insn.ObjectIsNullInsn;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Objects;

/// Lowers `object_is_null` with `gdcc_object_is_null_raw_and_id(raw, instance_id)`.
/// It does not emit an `assert_object_live` guard because null checks are user-visible queries.
public final class ObjectIsNullInsnGen implements CInsnGen<ObjectIsNullInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.OBJECT_IS_NULL);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var objectVariable = bodyBuilder.func().getVariableById(insn.objectId());
        if (objectVariable == null) {
            throw bodyBuilder.invalidInsn("object_is_null object variable not found: " + insn.objectId());
        }
        if (!(objectVariable.type() instanceof GdObjectType)) {
            throw bodyBuilder.invalidInsn("object_is_null target '" + objectVariable.id() + "' must be an object type, got '" +
                    objectVariable.type().getTypeName() + "'");
        }
        var resultVariable = bodyBuilder.func().getVariableById(Objects.requireNonNull(insn.resultId()));
        if (resultVariable == null) {
            throw bodyBuilder.invalidInsn("object_is_null result variable not found: " + insn.resultId());
        }
        var objectCode = bodyBuilder.valueOfVar(objectVariable).generateCode();
        bodyBuilder.assignExpr(
                bodyBuilder.targetOfVar(resultVariable),
                "(" + bodyBuilder.renderObjectIsNullExpr(objectCode) + ")",
                GdBoolType.BOOL
        );
    }
}
