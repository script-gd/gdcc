package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.ConstructionInstruction;
import dev.superice.gdcc.lir.insn.TryOwnObjectInsn;
import dev.superice.gdcc.lir.insn.TryReleaseObjectInsn;
import dev.superice.gdcc.scope.RefCountedStatus;
import dev.superice.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;

public final class OwnReleaseObjectInsnGen implements CInsnGen<ConstructionInstruction> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.TRY_OWN_OBJECT, GdInstruction.TRY_RELEASE_OBJECT);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var func = bodyBuilder.func();
        var objectVar = resolveObjectVar(bodyBuilder, insn);
        var objectType = objectVar.type();
        if (!(objectType instanceof GdObjectType gdObjectType)) {
            throw bodyBuilder.invalidInsn("Object variable ID is not of object type, but " + objectType.getTypeName());
        }

        var refCountedStatus = bodyBuilder.classRegistry().getRefCountedStatus(gdObjectType);
        var callee = resolveCallee(insn, refCountedStatus);
        if (callee == null) {
            return;
        }
        bodyBuilder.callVoid(callee, List.of(bodyBuilder.valueOfVar(objectVar)));
    }

    private @NotNull LirVariable resolveObjectVar(@NotNull CBodyBuilder bodyBuilder,
                                                  @NotNull ConstructionInstruction insn) {
        var func = bodyBuilder.func();
        var objectId = switch (insn) {
            case TryOwnObjectInsn(var id, var _) -> id;
            case TryReleaseObjectInsn(var id, var _) -> id;
            default ->
                    throw bodyBuilder.invalidInsn("Unsupported instruction type " + insn.getClass().getSimpleName());
        };
        var objectVar = func.getVariableById(objectId);
        if (objectVar == null) {
            throw bodyBuilder.invalidInsn("Object variable ID does not exist");
        }
        return objectVar;
    }

    private String resolveCallee(@NotNull ConstructionInstruction insn, @NotNull RefCountedStatus status) {
        return switch (status) {
            case YES -> switch (insn) {
                case TryOwnObjectInsn _ -> "own_object";
                case TryReleaseObjectInsn _ -> "release_object";
                default -> null;
            };
            case UNKNOWN -> switch (insn) {
                case TryOwnObjectInsn _ -> "try_own_object";
                case TryReleaseObjectInsn _ -> "try_release_object";
                default -> null;
            };
            case NO -> null;
        };
    }
}
