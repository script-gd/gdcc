package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Minimal, read-only PE/COFF parser used to assert exports of Windows artifacts without any
/// external tooling (`dumpbin` needs MSVC and is unavailable on CI runners; `nm -D` does not read
/// PE). Supports PE32+ x86-64 images — the only shape gdcc produces for Windows targets — and
/// reports the machine type, the DLL characteristic, the export-table symbol names and the
/// imported module names.
///
/// Fail-closed contract: every structural inconsistency (truncated headers, out-of-file offsets,
/// RVAs landing in a section's zero-filled virtual tail, truncated data directories, missing or
/// partially-zero import terminators) throws [IllegalArgumentException] instead of producing
/// partial data. All range math is done in `long` so 32-bit overflow cannot forge a valid image.
final class PeExportReader {

    /// Parsed view of one PE image.
    ///
    /// @param machine         COFF machine field (0x8664 = AMD64)
    /// @param dll             whether the DLL characteristic bit (0x2000) is set
    /// @param exportedSymbols names from the export directory's name pointer table
    /// @param importedModules module names from the import descriptor table (e.g. `KERNEL32.dll`)
    record PeInfo(int machine, boolean dll, @NotNull List<String> exportedSymbols,
                  @NotNull List<String> importedModules) {
    }

    private static final int PE32_PLUS_MAGIC = 0x20B;
    /// PE32+ optional-header fixed part; data directories follow immediately after it.
    private static final int PE32_PLUS_FIXED_OPTIONAL_HEADER_SIZE = 112;
    private static final int DLL_CHARACTERISTIC = 0x2000;
    private static final int EXPORT_DIRECTORY_SIZE = 40;
    private static final int IMPORT_DESCRIPTOR_SIZE = 20;

    static @NotNull PeInfo parse(@NotNull Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    static @NotNull PeInfo parse(byte[] image) {
        // ---- DOS header + PE signature ----
        if (image.length < 0x40 || image[0] != 'M' || image[1] != 'Z') {
            throw new IllegalArgumentException("not a PE image: missing MZ header");
        }
        var peOffsetLong = u32(image, 0x3C);
        if (peOffsetLong > (long) image.length - 24) {
            throw new IllegalArgumentException("not a PE image: e_lfanew out of range");
        }
        var peOffset = (int) peOffsetLong;
        if (image[peOffset] != 'P' || image[peOffset + 1] != 'E' || image[peOffset + 2] != 0 || image[peOffset + 3] != 0) {
            throw new IllegalArgumentException("not a PE image: missing PE\\0\\0 signature");
        }

        // ---- COFF header ----
        var coff = peOffset + 4;
        var machine = u16(image, coff);
        var sectionCount = u16(image, coff + 2);
        var optionalHeaderSize = u16(image, coff + 16);
        var characteristics = u16(image, coff + 18);
        var optional = coff + 20;
        if (optionalHeaderSize < PE32_PLUS_FIXED_OPTIONAL_HEADER_SIZE
                || (long) optional + optionalHeaderSize > image.length) {
            throw new IllegalArgumentException("malformed PE: optional header out of range");
        }
        var magic = u16(image, optional);
        if (magic != PE32_PLUS_MAGIC) {
            throw new IllegalArgumentException("not a PE32+ image: unexpected optional header magic 0x" + Integer.toHexString(magic));
        }
        var numberOfRvaAndSizes = u32(image, optional + 108);

        // ---- section table (RVA -> file offset translation) ----
        var sectionsOffset = optional + optionalHeaderSize;
        if ((long) sectionsOffset + (long) sectionCount * 40 > image.length) {
            throw new IllegalArgumentException("malformed PE: section table out of range");
        }
        var sections = new ArrayList<Section>(sectionCount);
        for (var i = 0; i < sectionCount; i++) {
            var base = sectionsOffset + i * 40;
            sections.add(new Section(u32(image, base + 8), u32(image, base + 12), u32(image, base + 16), u32(image, base + 20)));
        }

        var exportDir = dataDirectory(image, optional, optionalHeaderSize, numberOfRvaAndSizes, 0);
        var importDir = dataDirectory(image, optional, optionalHeaderSize, numberOfRvaAndSizes, 1);
        return new PeInfo(
                machine,
                (characteristics & DLL_CHARACTERISTIC) != 0,
                parseExports(image, sections, exportDir),
                parseImports(image, sections, importDir)
        );
    }

    /// Export directory layout: NumberOfNames @+24, AddressOfNames (RVA of the name pointer
    /// table) @+32; each table entry is a 4-byte RVA to a NUL-terminated name string.
    private static @NotNull List<String> parseExports(byte[] image, List<Section> sections, DataDirectory directory) {
        if (directory.rva() == 0) {
            return List.of();
        }
        if (directory.size() < EXPORT_DIRECTORY_SIZE) {
            throw new IllegalArgumentException("malformed PE: export directory truncated by its own size field");
        }
        var directoryOffset = locate(image, sections, directory.rva(), EXPORT_DIRECTORY_SIZE).offset();
        var nameCount = u32(image, directoryOffset + 24);
        var namesRva = u32(image, directoryOffset + 32);
        if (nameCount == 0) {
            return List.of();
        }
        // The name pointer table must be fully file-backed before any name is dereferenced.
        var tableBytes = Math.multiplyExact(nameCount, 4L);
        var table = locate(image, sections, namesRva, tableBytes);
        var names = new ArrayList<String>((int) Math.min(nameCount, 1 << 20));
        for (long i = 0; i < nameCount; i++) {
            var entry = (int) (table.offset() + i * 4);
            var nameRva = u32(image, entry);
            var nameRegion = locate(image, sections, nameRva, 1);
            names.add(cString(image, nameRegion.offset(), nameRegion.maxEndExclusive()));
        }
        return List.copyOf(names);
    }

    /// Import descriptors (20 bytes each) scanned within the declared directory extent; the table
    /// ends at a fully zero descriptor. A partially-zero or missing terminator is corruption, not
    /// end-of-table.
    private static @NotNull List<String> parseImports(byte[] image, List<Section> sections, DataDirectory directory) {
        if (directory.rva() == 0) {
            return List.of();
        }
        if (directory.size() < IMPORT_DESCRIPTOR_SIZE) {
            throw new IllegalArgumentException("malformed PE: import directory too small for one descriptor");
        }
        var tableStart = locate(image, sections, directory.rva(), directory.size());
        // The scan is bounded by the DECLARED directory extent, not the whole section: running
        // past it would read unrelated data as descriptors until something looks like a
        // terminator. (locate already proved the extent is fully file-backed.)
        var tableEnd = tableStart.offset() + directory.size();
        var modules = new ArrayList<String>();
        var terminated = false;
        for (long offset = tableStart.offset(); offset + IMPORT_DESCRIPTOR_SIZE <= tableEnd; offset += IMPORT_DESCRIPTOR_SIZE) {
            var descriptor = (int) offset;
            var originalFirstThunk = u32(image, descriptor);
            var timeDateStamp = u32(image, descriptor + 4);
            var forwarderChain = u32(image, descriptor + 8);
            var nameRva = u32(image, descriptor + 12);
            var firstThunk = u32(image, descriptor + 16);
            if ((originalFirstThunk | timeDateStamp | forwarderChain | nameRva | firstThunk) == 0) {
                terminated = true;
                break;
            }
            var nameRegion = locate(image, sections, nameRva, 1);
            modules.add(cString(image, nameRegion.offset(), nameRegion.maxEndExclusive()));
        }
        if (!terminated) {
            throw new IllegalArgumentException("malformed PE: import table has no zero terminator inside its directory");
        }
        return List.copyOf(modules);
    }

    /// Reads a data directory entry (RVA + size). An index at or beyond `NumberOfRvaAndSizes` is a
    /// genuinely absent directory; an index that IS declared must be backed by the optional header.
    private static @NotNull DataDirectory dataDirectory(byte[] image, int optional, int optionalHeaderSize, long numberOfRvaAndSizes, int index) {
        if (index >= numberOfRvaAndSizes) {
            return new DataDirectory(0, 0);
        }
        var entry = (long) optional + PE32_PLUS_FIXED_OPTIONAL_HEADER_SIZE + (long) index * 8;
        if (entry + 8 > (long) optional + optionalHeaderSize || entry + 8 > image.length) {
            throw new IllegalArgumentException("malformed PE: data directory " + index + " declared but truncated");
        }
        return new DataDirectory(u32(image, (int) entry), u32(image, (int) entry + 4));
    }

    /// Maps an RVA range to its file-backed region. The section's virtual tail
    /// (`virtualSize > rawSize`) is zero-filled by the loader and has NO file bytes, so the whole
    /// [rva, rva+length) window must fit inside `rawSize` — treating the virtual extent as backed
    /// would read the next section's bytes as if they were this one's data.
    private static @NotNull Region locate(byte[] image, @NotNull List<Section> sections, long rva, long length) {
        if (length <= 0 || rva < 0) {
            throw new IllegalArgumentException("malformed PE: invalid range rva=0x" + Long.toHexString(rva) + " length=" + length);
        }
        for (var section : sections) {
            var delta = rva - section.virtualAddress();
            if (delta < 0 || delta >= Math.max(section.virtualSize(), section.rawSize())) {
                continue;
            }
            if (delta + length > section.rawSize()) {
                throw new IllegalArgumentException("malformed PE: RVA 0x" + Long.toHexString(rva)
                        + " lands in the zero-filled virtual tail of a section (no file backing)");
            }
            var offset = section.rawPointer() + delta;
            if (offset < 0 || offset + length > image.length) {
                throw new IllegalArgumentException("malformed PE: RVA 0x" + Long.toHexString(rva) + " is not backed by the file");
            }
            return new Region((int) offset, section.rawPointer() + section.rawSize());
        }
        throw new IllegalArgumentException("malformed PE: RVA 0x" + Long.toHexString(rva) + " is not covered by any section");
    }

    private static @NotNull String cString(byte[] image, int offset, long maxEndExclusive) {
        // Both bounds matter: the section's raw end is the structural limit, and the file length
        // is the physical one — a section may CLAIM raw bytes beyond the file end.
        var limit = Math.min(maxEndExclusive, image.length);
        long end = offset;
        while (end < limit && image[(int) end] != 0) {
            end++;
        }
        if (end >= limit) {
            throw new IllegalArgumentException("malformed PE: unterminated string at " + offset);
        }
        return new String(image, offset, (int) end - offset, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int u16(byte[] image, int offset) {
        return (image[offset] & 0xFF) | ((image[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] image, int offset) {
        return ((long) (image[offset] & 0xFF))
                | ((long) (image[offset + 1] & 0xFF) << 8)
                | ((long) (image[offset + 2] & 0xFF) << 16)
                | ((long) (image[offset + 3] & 0xFF) << 24);
    }

    private record Section(long virtualSize, long virtualAddress, long rawSize, long rawPointer) {
    }

    private record DataDirectory(long rva, long size) {
    }

    /// A validated file-backed window: `offset` plus the exclusive end of the owning section's
    /// raw data (the bound for NUL-terminated strings).
    private record Region(int offset, long maxEndExclusive) {
    }
}
