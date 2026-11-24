package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/// Independent synthetic output for TinyCC native fakes. The ELF bytes are only a header
/// fixture, not a loadable shared library or a replacement for testing real linked output.
final class TinyCcElfTestFixtures {
    private TinyCcElfTestFixtures() {
    }

    /// ELF64 shared-object header followed by one PT_LOAD and the supplied GNU_STACK entries.
    static @NotNull ByteBuffer linuxX8664SharedObject(int... stackFlags) {
        var bytes = ByteBuffer.allocate(64 + (1 + stackFlags.length) * 56).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(0, new byte[]{0x7f, 'E', 'L', 'F', 2, 1, 1});
        bytes.putShort(16, (short) 3); // ET_DYN
        bytes.putShort(18, (short) 62); // EM_X86_64
        bytes.putInt(20, 1); // e_version
        bytes.putLong(32, 64); // e_phoff
        bytes.putShort(52, (short) 64); // e_ehsize
        bytes.putShort(54, (short) 56); // e_phentsize
        bytes.putShort(56, (short) (1 + stackFlags.length)); // e_phnum
        bytes.putInt(64, 1); // PT_LOAD
        for (var index = 0; index < stackFlags.length; index++) {
            var offset = 64 + (1 + index) * 56;
            bytes.putInt(offset, 0x6474e551); // PT_GNU_STACK
            bytes.putInt(offset + 4, stackFlags[index]);
        }
        return bytes;
    }

    /// Lock tests only care that the round writes a distinct artifact. Match the final Linux
    /// x86-64 gate there, without changing their marker bytes for objects or other platforms.
    static void writeLockTestArtifact(@NotNull String file, @NotNull TargetPlatform target) throws IOException {
        var path = Path.of(file);
        if (target == TargetPlatform.LINUX_X86_64 && file.endsWith(".so")) {
            Files.write(path, linuxX8664SharedObject(6).array());
        } else {
            Files.writeString(path, "fake shared object");
        }
    }
}
