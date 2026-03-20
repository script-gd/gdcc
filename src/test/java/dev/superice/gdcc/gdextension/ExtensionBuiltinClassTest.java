package dev.superice.gdcc.gdextension;

import dev.superice.gdcc.enums.GodotOperator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ExtensionBuiltinClassTest {
    @Test
    void classOperatorUsesMetadataOperatorFactory() {
        var unary = new ExtensionBuiltinClass.ClassOperator("unary-", "", "int");
        var binary = new ExtensionBuiltinClass.ClassOperator("in", "Array", "bool");

        assertEquals(GodotOperator.NEGATE, unary.operator());
        assertEquals(GodotOperator.IN, binary.operator());
    }

    @Test
    void classOperatorRejectsSourceOnlyAliases() {
        var classOperator = new ExtensionBuiltinClass.ClassOperator("&&", "bool", "bool");

        var ex = assertThrows(IllegalArgumentException.class, classOperator::operator);

        assertEquals("Unknown metadata operator: &&", ex.getMessage());
    }
}
