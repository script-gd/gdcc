package gd.script.gdcc.scope;

import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.type.GdIntType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyDefAccessSupportTest {
    @Test
    void enginePropertyWritableFlagShouldWinOverRawAccessorPresence() {
        var getterOnly = new ExtensionGdClass.PropertyInfo(
                "locked",
                "int",
                true,
                false,
                null,
                "get_locked",
                "set_locked",
                null
        );

        assertFalse(PropertyDefAccessSupport.isDirectlyWritable(getterOnly));
    }

    @Test
    void builtinPropertyShouldUseNormalizedWritableFlag() {
        var writableBuiltin = new ExtensionBuiltinClass.PropertyInfo("x", "float", true, true, null);
        var readOnlyBuiltin = new ExtensionBuiltinClass.PropertyInfo("x", "float", true, false, null);

        assertTrue(PropertyDefAccessSupport.isDirectlyWritable(writableBuiltin));
        assertFalse(PropertyDefAccessSupport.isDirectlyWritable(readOnlyBuiltin));
    }

    @Test
    void gdccPropertyShouldStayConservativelyWritable() {
        var gdccProperty = new LirPropertyDef(
                "value",
                GdIntType.INT,
                false,
                null,
                null,
                null,
                Map.of()
        );

        assertTrue(PropertyDefAccessSupport.isDirectlyWritable(gdccProperty));
    }
}
