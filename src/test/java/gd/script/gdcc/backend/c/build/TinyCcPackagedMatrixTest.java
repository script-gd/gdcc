package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TinyCcPackagedMatrixTest {
    @ParameterizedTest
    @ValueSource(strings = {"windows-x86_64", "linux-x86_64", "linux-aarch64", "macos-aarch64"})
    void allFourBundlesPassAdmissionAndMatchTheirTarget(String platform, @TempDir Path tempDir) throws Exception {
        var root = TinyCcBundle.requireBundleRoot(null, tempDir.resolve("cache"),
                TinyCcBundle.classpathBundleSource(platform, getClass().getClassLoader()), platform, "matrix");
        assertTrue(Files.isRegularFile(root.resolve(".ready")));
        var library = platform.startsWith("windows") ? "libtcc.dll"
                : platform.startsWith("macos") ? "libtcc.dylib" : "libtcc.so";
        var bytes = Files.readAllBytes(root.resolve("bin").resolve(library));
        var binary = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (platform.startsWith("windows")) {
            assertEquals(0x5a4d, Short.toUnsignedInt(binary.getShort(0)));
            var pe = binary.getInt(0x3c);
            assertEquals(0x4550, binary.getInt(pe));
            assertEquals(0x8664, Short.toUnsignedInt(binary.getShort(pe + 4)));
            assertTrue((binary.getShort(pe + 22) & 0x2000) != 0, "PE must be a DLL");
        } else if (platform.startsWith("linux")) {
            assertEquals(0x464c457f, binary.getInt(0));
            assertEquals(2, bytes[4], "ELF must be 64-bit");
            assertEquals(3, binary.getShort(16), "ELF must be a shared library");
            assertEquals(platform.endsWith("aarch64") ? 183 : 62, binary.getShort(18));
        } else {
            assertEquals(0xfeedfacf, binary.getInt(0));
            assertEquals(0x0100000c, binary.getInt(4));
            assertEquals(6, binary.getInt(12), "Mach-O must be a dylib");
            assertTrue((binary.getInt(24) & 0x80) != 0, "MH_TWOLEVEL must be set without the unsupported linker flag");
            var signature = false;
            var installName = false;
            var offset = 32;
            for (var index = 0; index < binary.getInt(16); index++) {
                var command = binary.getInt(offset);
                var size = binary.getInt(offset + 4);
                if (command == 0x1d) {
                    var signatureOffset = binary.getInt(offset + 8);
                    var signatureSize = binary.getInt(offset + 12);
                    assertTrue(signatureOffset > 0 && signatureSize > 0 && signatureOffset + signatureSize <= bytes.length);
                    assertEquals(0xfade0cc0, ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(signatureOffset));
                    signature = true;
                } else if (command == 0xd) {
                    var start = offset + binary.getInt(offset + 8);
                    var end = start;
                    while (bytes[end] != 0) {
                        end++;
                    }
                    assertEquals("@rpath/libtcc.dylib", new String(bytes, start, end - start, StandardCharsets.UTF_8));
                    installName = true;
                }
                offset += size;
            }
            assertTrue(signature, "ARM64 Mach-O must contain an ad-hoc code signature");
            assertTrue(installName, "Mach-O must contain its portable install name");
        }

        var archive = Files.readAllBytes(root.resolve(platform.startsWith("windows") ? "lib/libtcc1.a" : "libtcc1.a"));
        assertEquals("!<arch>\n", new String(archive, 0, 8, StandardCharsets.US_ASCII));
        var contents = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        var offset = 8;
        var objects = 0;
        while (offset < archive.length) {
            var name = new String(archive, offset, 16, StandardCharsets.US_ASCII).strip();
            var size = Integer.parseInt(new String(archive, offset + 48, 10, StandardCharsets.US_ASCII).strip());
            var body = offset + 60;
            if (!name.startsWith("/")) {
                assertEquals(0x464c457f, contents.getInt(body), "Runtime objects must be TinyCC ELF, including PE/Mach-O targets");
                assertEquals(platform.endsWith("aarch64") ? 183 : 62, contents.getShort(body + 18));
                objects++;
            }
            offset = body + size + (size & 1);
        }
        assertTrue(objects > 0, "Runtime archive must contain target objects");
    }
}
