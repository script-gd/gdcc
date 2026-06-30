package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class CBodyBuilderAliasSafetySupportTest {
    @Test
    @DisplayName("compiler-only direct-assignment overwrite should not require a stable carrier")
    void compilerOnlyDirectAssignmentShouldNotRequireStableCarrier() {
        var func = createFunctionDef();
        var iter = new LirVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER, func);
        var target = new CBodyBuilder.VarTargetRef(iter);
        var value = new CBodyBuilder.VarValue(iter, CBodyBuilder.PtrKind.NON_OBJECT);

        var needsStableCarrier = CBodyBuilderAliasSafetySupport.requiresStableCarrier(
                false,
                true,
                target,
                value,
                iter.type(),
                false
        );

        assertFalse(needsStableCarrier, "compiler-only direct assignment must stay off the stable-carrier path");
    }

    @Test
    @DisplayName("String self overwrite should still require a stable carrier")
    void stringSelfOverwriteShouldRequireStableCarrier() {
        var func = createFunctionDef();
        var valueVar = new LirVariable("s", GdStringType.STRING, func);
        var target = new CBodyBuilder.VarTargetRef(valueVar);
        var value = new CBodyBuilder.VarValue(valueVar, CBodyBuilder.PtrKind.NON_OBJECT);

        var needsStableCarrier = CBodyBuilderAliasSafetySupport.requiresStableCarrier(
                false,
                true,
                target,
                value,
                valueVar.type(),
                true
        );

        assertTrue(needsStableCarrier, "destroyable wrapper self-overwrite still needs a stable carrier");
    }

    @Test
    @DisplayName("Variant self overwrite should still classify as may-alias")
    void variantSelfOverwriteShouldStillClassifyAsMayAlias() {
        var func = createFunctionDef();
        var valueVar = new LirVariable("payload", GdVariantType.VARIANT, func);
        var target = new CBodyBuilder.VarTargetRef(valueVar);
        var value = new CBodyBuilder.VarValue(valueVar, CBodyBuilder.PtrKind.NON_OBJECT);

        var aliasSafety = CBodyBuilderAliasSafetySupport.classifyNonObjectSlotWriteAliasSafety(target, value);

        assertSame(aliasSafety, CBodyBuilderAliasSafetySupport.NonObjectSlotWriteAliasSafety.MAY_ALIAS, "self overwrite must remain may-alias so wrapper types still stage a stable carrier");
    }

    private static LirFunctionDef createFunctionDef() {
        return new LirFunctionDef(
                "alias_safety",
                false,
                false,
                false,
                false,
                false,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyMap(),
                GdVoidType.VOID,
                Collections.emptyMap(),
                new LinkedHashMap<>()
        );
    }
}
