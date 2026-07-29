package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.ConstructionInstruction;
import gd.script.gdcc.lir.insn.LifecycleInstruction;
import gd.script.gdcc.lir.insn.TryOwnObjectInsn;
import gd.script.gdcc.lir.insn.TryReleaseObjectInsn;
import gd.script.gdcc.scope.RefCountedStatus;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

public final class OwnReleaseObjectInsnGen implements CInsnGen<ConstructionInstruction> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.TRY_OWN_OBJECT, GdInstruction.TRY_RELEASE_OBJECT);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        assertLifecycleProvenance(bodyBuilder, insn);
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
        bodyBuilder.emitObjectLifecycleCall(callee, bodyBuilder.valueOfVar(objectVar));
    }

    /// Own/release instructions are never auto-injected by backend, provenance should reflect that.
    private void assertLifecycleProvenance(@NotNull CBodyBuilder bodyBuilder,
                                           @NotNull ConstructionInstruction insn) {
        if (!(insn instanceof LifecycleInstruction lifecycleInsn)) {
            return;
        }
        if (lifecycleInsn.getProvenance() == LifecycleProvenance.AUTO_GENERATED) {
            throw bodyBuilder.invalidInsn("AUTO_GENERATED is not allowed for try_own_object/try_release_object");
        }
        if (lifecycleInsn.getProvenance() == LifecycleProvenance.UNKNOWN && bodyBuilder.helper().context().strictMode()) {
            throw bodyBuilder.invalidInsn("UNKNOWN lifecycle provenance is forbidden in strict mode");
        }
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
