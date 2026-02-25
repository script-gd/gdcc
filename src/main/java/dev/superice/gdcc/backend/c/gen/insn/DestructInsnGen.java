package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.DestructInsn;
import dev.superice.gdcc.scope.RefCountedStatus;
import dev.superice.gdcc.type.GdContainerType;
import dev.superice.gdcc.type.GdMetaType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringLikeType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;

public final class DestructInsnGen implements CInsnGen<DestructInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.DESTRUCT);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        assertLifecycleProvenance(bodyBuilder, insn);
        var variable = resolveVariable(bodyBuilder, insn.variableId());
        switch (variable.type()) {
            case GdVoidType _ ->
                    throw bodyBuilder.invalidInsn("Cannot destruct variable of type " + variable.type().getTypeName());
            case GdObjectType objectType -> generateObjectDestruct(bodyBuilder, objectType, variable);
            case GdVariantType _, GdStringLikeType _, GdMetaType _, GdContainerType _ -> {
                var destroyFunc = bodyBuilder.helper().renderDestroyFunctionName(variable.type());
                bodyBuilder.callVoid(destroyFunc, List.of(bodyBuilder.valueOfVar(variable)));
            }
            default -> {
            }
        }
    }

    /// Lightweight defensive checks to avoid silently generating invalid lifecycle code paths.
    private void assertLifecycleProvenance(@NotNull CBodyBuilder bodyBuilder, @NotNull DestructInsn insn) {
        if (insn.getProvenance() == LifecycleProvenance.AUTO_GENERATED && !bodyBuilder.checkInFinallyBlock()) {
            throw bodyBuilder.invalidInsn("AUTO_GENERATED destruct is only valid in __finally__ block");
        }
        if (insn.getProvenance() == LifecycleProvenance.UNKNOWN && bodyBuilder.helper().context().strictMode()) {
            throw bodyBuilder.invalidInsn("UNKNOWN lifecycle provenance is forbidden in strict mode");
        }
    }

    private @NotNull LirVariable resolveVariable(@NotNull CBodyBuilder bodyBuilder, @NotNull String variableId) {
        var variable = bodyBuilder.func().getVariableById(variableId);
        if (variable == null) {
            throw bodyBuilder.invalidInsn("Variable ID '" + variableId + "' not found in function");
        }
        return variable;
    }

    private void generateObjectDestruct(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull GdObjectType objectType,
                                        @NotNull LirVariable variable) {
        var releaseFunc = switch (bodyBuilder.classRegistry().getRefCountedStatus(objectType)) {
            case RefCountedStatus.YES -> "release_object";
            case RefCountedStatus.UNKNOWN -> "try_release_object";
            case RefCountedStatus.NO -> "try_destroy_object";
        };
        bodyBuilder.callVoid(releaseFunc, List.of(bodyBuilder.valueOfVar(variable)));
    }
}
