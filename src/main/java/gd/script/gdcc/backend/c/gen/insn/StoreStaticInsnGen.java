package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.insn.StoreStaticInsn;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

public final class StoreStaticInsnGen implements CInsnGen<StoreStaticInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.STORE_STATIC);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var valueId = insn.valueId();
        if (valueId.isBlank()) {
            throw bodyBuilder.invalidInsn("Store static instruction missing value variable ID");
        }
        var valueVar = bodyBuilder.func().getVariableById(valueId);
        if (valueVar == null) {
            throw bodyBuilder.invalidInsn("Value variable ID '" + valueId + "' not found in function");
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, valueVar, "static store value");

        var classRegistry = bodyBuilder.classRegistry();
        var className = insn.className();
        var staticName = insn.staticName();
        // Only GDCC script class static properties are writable; engine/builtin/global static
        // members stay read-only and keep the explicit rejection.
        if (!(classRegistry.getClassDef(new GdObjectType(className)) instanceof LirClassDef)) {
            throw bodyBuilder.invalidInsn(
                    "Unsupported static store: 'store_static' receiver '" + className
                            + "' is not a GDCC script class; only GDCC static properties are writable"
            );
        }
        // The frontend publishes only the access-start class name; resolve the declaring owner
        // along the inheritance chain so inherited statics write the owner's shared storage.
        var staticPropertyLookup = classRegistry.findStaticPropertyInHierarchy(className, staticName);
        if (staticPropertyLookup == null) {
            throw bodyBuilder.invalidInsn(
                    "Static property '" + staticName + "' not found in GDCC class '"
                            + className + "' or its superclasses"
            );
        }
        var property = staticPropertyLookup.property();
        if (!classRegistry.checkAssignable(valueVar.type(), property.getType())) {
            throw bodyBuilder.invalidInsn(
                    "Static store value type '" + valueVar.type().getTypeName()
                            + "' is not assignable to static property type '"
                            + property.getType().getTypeName() + "'"
            );
        }
        // Runtime overwrite of long-lived storage: the unified slot-write path performs
        // release/destroy of the old backing value and retains a BORROWED source, mirroring
        // instance property stores. The backing storage outlives every instance, so no
        // first-write shortcut is allowed here.
        var backingExpr = bodyBuilder.helper().renderStaticBackingSymbol(
                staticPropertyLookup.ownerClass().getName(), staticName
        );
        bodyBuilder.assignVar(
                bodyBuilder.targetOfExpr(backingExpr, property.getType()),
                bodyBuilder.valueOfVar(valueVar)
        );
    }
}
