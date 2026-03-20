package dev.superice.gdcc.type;

import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirSignalDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GdSignalTypeTest {
    @Test
    void constructorCopiesParameterTypesAndReturnsUnmodifiableSignature() {
        var parameterTypes = new ArrayList<GdType>();
        parameterTypes.add(GdIntType.INT);

        var signalType = new GdSignalType(parameterTypes);
        parameterTypes.add(GdStringType.STRING);

        assertEquals(List.of(GdIntType.INT), signalType.parameterTypes());
        assertThrows(UnsupportedOperationException.class, () -> signalType.parameterTypes().add(GdStringType.STRING));
    }

    @Test
    void fromSignalBuildsStableSignatureAndEqualityUsesThatSignature() {
        var signal = new LirSignalDef("changed");
        signal.addParameter(new LirParameterDef("value", GdIntType.INT, null, signal));
        signal.addParameter(new LirParameterDef("label", GdStringType.STRING, null, signal));

        var signalType = GdSignalType.from(signal);

        assertEquals(List.of(GdIntType.INT, GdStringType.STRING), signalType.parameterTypes());
        assertEquals(new GdSignalType(List.of(GdIntType.INT, GdStringType.STRING)), signalType);
        assertNotEquals(new GdSignalType(List.of(GdIntType.INT)), signalType);
    }
}
