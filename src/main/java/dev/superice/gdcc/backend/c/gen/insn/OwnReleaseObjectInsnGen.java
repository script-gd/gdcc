package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.ConstructionInstruction;
import dev.superice.gdcc.lir.insn.TryOwnObjectInsn;
import dev.superice.gdcc.lir.insn.TryReleaseObjectInsn;
import dev.superice.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Map;

public final class OwnReleaseObjectInsnGen extends TemplateInsnGen<ConstructionInstruction> {
    @Override
    protected @NotNull String getTemplatePath() {
        return "insn/own_release_object.ftl";
    }

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.TRY_OWN_OBJECT, GdInstruction.TRY_RELEASE_OBJECT);
    }

    @Override
    protected void validateInstruction(@NotNull CGenHelper helper,
                                       @NotNull LirClassDef clazz,
                                       @NotNull LirFunctionDef func,
                                       @NotNull LirBasicBlock block,
                                       int insnIndex,
                                       @NotNull ConstructionInstruction instruction) {
        var objectVar = tryGetObjectVar(func, block, insnIndex, instruction);
        if (!(objectVar.type() instanceof GdObjectType)) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Object variable ID is not of object type, but " + objectVar.type().getTypeName());
        }
    }

    @Override
    protected @NotNull Map<String, Object> getGenerationExtraData(@NotNull CGenHelper helper,
                                                                  @NotNull LirClassDef clazz,
                                                                  @NotNull LirFunctionDef func,
                                                                  @NotNull LirBasicBlock block,
                                                                  int insnIndex,
                                                                  @NotNull ConstructionInstruction instruction) {
        var objectVar = tryGetObjectVar(func, block, insnIndex, instruction);
        if (objectVar.type() instanceof GdObjectType gdObjectType) {
            var registry = helper.context().classRegistry();
            if (registry.checkAssignable(gdObjectType, new GdObjectType("RefCounted"))) {
                return Map.of("assertRefCounted", true);
            }
        }
        return Map.of("assertRefCounted", false);
    }

    private @NotNull LirVariable tryGetObjectVar(@NotNull LirFunctionDef func,
                                                 @NotNull LirBasicBlock block,
                                                 int insnIndex,
                                                 @NotNull ConstructionInstruction instruction) {
        LirVariable objectVar;
        if (instruction instanceof TryOwnObjectInsn(String objectId)) {
            objectVar = func.getVariableById(objectId);
        } else if (instruction instanceof TryReleaseObjectInsn(String objectId)) {
            objectVar = func.getVariableById(objectId);
        } else {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Unsupported instruction type " + instruction.opcode().opcode());
        }
        if (objectVar == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Object variable ID does not exist");
        }
        return objectVar;
    }
}
