package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.scope.RefCountedStatus;
import gd.script.gdcc.type.GdContainerType;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdMetaType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringLikeType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
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
            case GdObjectType objectType -> generateObjectDestruct(bodyBuilder, insn, objectType, variable);
            case GdCompilerType _ -> {
                var destroyFunc = bodyBuilder.helper().renderDestroyFunctionName(variable.type());
                bodyBuilder.callVoid(destroyFunc, List.of(bodyBuilder.valueOfVar(variable)));
            }
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
                                        @NotNull DestructInsn insn,
                                        @NotNull GdObjectType objectType,
                                        @NotNull LirVariable variable) {
        var refCountedStatus = bodyBuilder.classRegistry().getRefCountedStatus(objectType);
        if (insn.getProvenance() == LifecycleProvenance.AUTO_GENERATED && refCountedStatus == RefCountedStatus.NO) {
            // AUTO_GENERATED cleanup is about managed local slots still owned by the current function, not
            // about destroying every object value that happens to be live at scope exit. Definite
            // non-RefCounted objects stay under Godot's explicit lifetime contract and must be skipped here.
            return;
        }
        var cleanupFunction = switch (refCountedStatus) {
            case RefCountedStatus.YES -> "release_object";
            case RefCountedStatus.UNKNOWN -> "try_release_object";
            case RefCountedStatus.NO -> "try_destroy_object";
        };
        bodyBuilder.callVoid(cleanupFunction, List.of(bodyBuilder.valueOfVar(variable)));
    }
}
