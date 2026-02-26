package dev.superice.gdcc.gdextension;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

public class ExtensionApiLoaderTest {
    @Test
    void loadExtensionApiFromResource() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        assertNotNull(api, "api should not be null");
        // basic header checks
        assertNotNull(api.header(), "header should not be null");
        assertEquals(4, api.header().versionMajor());
        assertEquals(5, api.header().versionMinor());
        assertTrue(api.globalEnums() != null && !api.globalEnums().isEmpty());

        // Round-trip: serialize then ensure serialization produces non-empty JSON
        var json = ExtensionApiLoader.toJson(api);
        assertNotNull(json);
        assertTrue(json.length() > 100);

        // Check builtin class 'String' has constructors
        var builtinString = api.builtinClasses().stream().filter(c -> "String".equals(c.name())).findFirst();
        assertTrue(builtinString.isPresent(), "builtin class String should be present");
        var stringClass = builtinString.get();
        assertNotNull(stringClass.constructors(), "String constructors should not be null");
        assertFalse(stringClass.constructors().isEmpty());

        // Check a strong example class: AESContext in classes (has enums, constructors, hasDestructor)
        var aesOpt = api.classes().stream().filter(c -> "AESContext".equals(c.name())).findFirst();
        assertTrue(aesOpt.isPresent(), "AESContext class should be present");
        var aes = aesOpt.get();
        assertNotNull(aes.enums(), "AES enums should not be null");
        assertFalse(aes.enums().isEmpty());

        // properties/constants fields exist (may be empty)
        assertNotNull(stringClass.properties());
        assertNotNull(stringClass.constants());
    }

    @Test
    void checkDefaultValueParsing() throws IOException {
        var defaultValues = new HashSet<String>();
        var api = ExtensionApiLoader.loadDefault();
        for (var cls : api.classes()) {
            for (var method : cls.methods()) {
                for (var arg : method.arguments()) {
                    if (arg.defaultValue() != null && !arg.defaultValue().isEmpty()) {
                        defaultValues.add(arg.defaultValue());
                        if (arg.defaultValue().equals("null")) {
                            IO.println("Found null default value in " + cls.name() + "." + method.name());
                        }
                    }
                }
            }
        }
    }

    @Test
    void builtinConstantTypeShouldBeParsed() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var vector3Class = api.builtinClasses().stream()
                .filter(builtinClass -> "Vector3".equals(builtinClass.name()))
                .findFirst()
                .orElseThrow();
        var backConstant = vector3Class.constants().stream()
                .filter(constant -> "BACK".equals(constant.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("Vector3", backConstant.type());
        assertEquals("Vector3(0, 0, 1)", backConstant.value());
    }
}
