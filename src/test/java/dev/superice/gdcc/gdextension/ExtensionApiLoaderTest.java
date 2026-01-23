package dev.superice.gdcc.gdextension;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class ExtensionApiLoaderTest {
    @Test
    void loadExtensionApiFromResource() throws IOException {
        var api = ExtensionApiLoader.loadFromResource();
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
}
