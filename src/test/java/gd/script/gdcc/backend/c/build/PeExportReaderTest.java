package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Parser contract of [PeExportReader], anchored through synthetic PE images so no toolchain or
/// fixture file is needed: a hand-assembled PE32+ image parses positively, and every structural
/// corruption fails closed with [IllegalArgumentException] (never an index/overflow exception and
/// never silently-partial data). The positive path against the real `libtcc.dll` is covered by
/// [TinyCcWindowsBundleParityTest].
class PeExportReaderTest {

    @Test
    void parsesSyntheticImageWithExportsAndImports() {
        var info = PeExportReader.parse(new SyntheticPe().build());
        assertEquals(0x8664, info.machine());
        assertTrue(info.dll());
        assertEquals(List.of("gdcc_probe"), info.exportedSymbols());
        assertEquals(List.of("KERNEL32.dll"), info.importedModules());
    }

    @Test
    void rejectsNonPeBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> PeExportReader.parse("definitely not a PE image".getBytes()));
    }

    @Test
    void rejectsTruncatedDosHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> PeExportReader.parse(new byte[]{'M', 'Z', 0, 0}));
    }

    @Test
    void rejectsOutOfRangePeOffsetWithoutOverflow() {
        var image = new byte[128];
        image[0] = 'M';
        image[1] = 'Z';
        writeU32(image, 0x3C, 0xFFFFFFFFL); // e_lfanew beyond any int range: must not overflow
        assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(image));
    }

    @Test
    void rejectsMissingPeSignature() {
        var image = new SyntheticPe().build();
        image[0x40] = 0x7F; // ELF-flavored bytes where the signature belongs
        image[0x41] = 'E';
        image[0x42] = 'L';
        image[0x43] = 'F';
        assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(image));
    }

    @Test
    void rejectsPe32OptionalHeaderMagic() {
        var builder = new SyntheticPe();
        builder.optionalMagic = 0x10B; // 32-bit optional header; only PE32+ (0x20B) is supported
        var ex = assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(builder.build()));
        assertTrue(ex.getMessage().contains("PE32+"), ex.getMessage());
    }

    @Test
    void rejectsOptionalHeaderTooSmallForPe32Plus() {
        var builder = new SyntheticPe();
        builder.optionalHeaderSize = 16; // not even the PE32+ fixed part (112) fits
        assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(builder.build()));
    }

    @Test
    void rejectsDeclaredButTruncatedDataDirectory() {
        var builder = new SyntheticPe();
        // Two directories declared, but the optional header ends right after the fixed part.
        builder.numberOfRvaAndSizes = 2;
        builder.optionalHeaderSize = 0x70; // 112 bytes: data directory entries do not fit
        assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(builder.build()));
    }

    @Test
    void rejectsExportDirectoryInZeroFilledVirtualTail() {
        var builder = new SyntheticPe();
        builder.sectionVirtualSize = 0x200;
        builder.sectionRawSize = 0x100; // loader zero-fills [rawSize, virtualSize): no file backing
        builder.exportRva = 0x1000 + 0x100; // first RVA of the virtual tail
        assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(builder.build()));
    }

    @Test
    void rejectsExportDirectoryClaimingMoreThanItsSize() {
        var builder = new SyntheticPe();
        builder.exportSize = 8; // smaller than the 40-byte export directory structure
        assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(builder.build()));
    }

    @Test
    void rejectsPartiallyZeroImportDescriptorAsTerminator() {
        var builder = new SyntheticPe();
        // Corrupt the terminator: only the two LAST dwords zeroed, the first three stay non-zero.
        // A correct parser treats it as a real (corrupt) entry and fails on its null name RVA.
        builder.importTerminatorAllZero = false;
        assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(builder.build()));
    }

    @Test
    void rejectsImportTableWithoutTerminator() {
        var builder = new SyntheticPe();
        builder.importHasTerminator = false; // second descriptor stays a copy of the first
        var ex = assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(builder.build()));
        assertTrue(ex.getMessage().contains("terminator"), ex.getMessage());
    }

    @Test
    void rejectsUnterminatedExportName() {
        var builder = new SyntheticPe();
        builder.exportNameTerminated = false;
        // The section claims raw bytes beyond the file end, so the scan hits EOF before the
        // section boundary: must fail closed, never read out of bounds.
        builder.sectionRawSize = 0x300;
        assertThrows(IllegalArgumentException.class, () -> PeExportReader.parse(builder.build()));
    }

    // ---------------------------------------------------------------------------

    /// Hand-assembled PE32+ image: DOS header at 0, PE signature at 0x40, one `.rdata` section
    /// (RVA 0x1000, raw data at file offset 0x200) holding a 1-name export directory, a 1-module
    /// import descriptor plus terminator, and the referenced strings. Mutators flip exactly one
    /// contract violation each.
    private static final class SyntheticPe {
        int optionalMagic = 0x20B;
        int optionalHeaderSize = 0xF0;
        long numberOfRvaAndSizes = 16;
        long exportRva = 0x1000;
        long exportSize = 40;
        long importRva = 0x1080;
        long importSize = 40;
        long sectionVirtualSize = 0x200;
        long sectionVirtualAddress = 0x1000;
        long sectionRawSize = 0x200;
        long sectionRawPointer = 0x200;
        boolean importTerminatorAllZero = true;
        boolean importHasTerminator = true;
        boolean exportNameTerminated = true;

        byte[] build() {
            var image = new byte[0x400];
            image[0] = 'M';
            image[1] = 'Z';
            writeU32(image, 0x3C, 0x40);
            var pe = 0x40;
            image[pe] = 'P';
            image[pe + 1] = 'E';
            var coff = pe + 4;
            writeU16(image, coff, 0x8664);              // AMD64
            writeU16(image, coff + 2, 1);               // one section
            writeU16(image, coff + 16, optionalHeaderSize);
            writeU16(image, coff + 18, 0x2002);         // DLL | executable image
            var optional = coff + 20;
            writeU16(image, optional, optionalMagic);
            // The builder always emits the directory entries; the parser is what must reject the
            // image when the declared optional header is too small to hold them.
            writeU32(image, optional + 108, numberOfRvaAndSizes);
            writeU32(image, optional + 112, exportRva);
            writeU32(image, optional + 116, exportSize);
            writeU32(image, optional + 120, importRva);
            writeU32(image, optional + 124, importSize);
            var section = optional + optionalHeaderSize;
            writeAscii(image, section, ".rdata");
            writeU32(image, section + 8, sectionVirtualSize);
            writeU32(image, section + 12, sectionVirtualAddress);
            writeU32(image, section + 16, sectionRawSize);
            writeU32(image, section + 20, sectionRawPointer);

            var base = (int) sectionRawPointer;
            // Export directory at RVA 0x1000: one name, pointer table at RVA 0x1040. Each
            // structure occupies its own non-overlapping file range.
            writeU32(image, base, 0);
            writeU32(image, base + 24, 1);              // NumberOfNames
            writeU32(image, base + 32, 0x1040);         // AddressOfNames
            writeU32(image, base + 0x40, 0x1050);       // name pointer table -> name string
            writeAscii(image, base + 0x50, "gdcc_probe\0");
            // Import table at RVA 0x1080: one descriptor + terminator; module name at RVA 0x10C0.
            var importTable = base + 0x80;
            writeU32(image, importTable, 0);            // OriginalFirstThunk
            writeU32(image, importTable + 12, 0x10C0);  // module name RVA
            writeU32(image, importTable + 16, 1);       // FirstThunk (non-zero)
            var terminator = importTable + 20;
            if (importHasTerminator) {
                if (!importTerminatorAllZero) {
                    writeU32(image, terminator, 1);     // partial-zero corruption
                }
            } else {
                writeU32(image, terminator, 0);
                writeU32(image, terminator + 12, 0x10C0);
                writeU32(image, terminator + 16, 1);
            }
            writeAscii(image, base + 0xC0, "KERNEL32.dll\0");
            if (!exportNameTerminated) {
                // No NUL before the section's raw end: fill the whole tail with non-zero bytes so
                // the parser cannot find a terminator inside the file-backed region.
                java.util.Arrays.fill(image, base + 0x50, image.length, (byte) 'x');
                writeAscii(image, base + 0x50, "gdcc_probe");
            }
            return image;
        }
    }

    private static void writeAscii(byte[] image, int offset, String text) {
        for (var i = 0; i < text.length(); i++) {
            image[offset + i] = (byte) text.charAt(i);
        }
    }

    private static void writeU16(byte[] image, int offset, int value) {
        image[offset] = (byte) value;
        image[offset + 1] = (byte) (value >> 8);
    }

    private static void writeU32(byte[] image, int offset, long value) {
        image[offset] = (byte) value;
        image[offset + 1] = (byte) (value >> 8);
        image[offset + 2] = (byte) (value >> 16);
        image[offset + 3] = (byte) (value >> 24);
    }
}
