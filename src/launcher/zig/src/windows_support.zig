const std = @import("std");
const translation_text = @import("translation_text.zig");

const windows = std.os.windows;

/// Detects the Windows user interface language, then falls back to the user locale.
pub fn detectLanguage(arena: std.mem.Allocator) ?translation_text.Language {
    if (detectPreferredUiLanguage(arena)) |language| return language;
    return detectLocaleLanguage();
}

/// Writes UTF-8 text to stderr, using UTF-16 console output when stderr is a Windows console.
pub fn writeStderrUtf8(arena: std.mem.Allocator, bytes: []const u8) void {
    const handle = (GetStdHandle(std_error_handle) orelse {
        std.debug.print("{s}", .{bytes});
        return;
    });
    if (handle == windows.INVALID_HANDLE_VALUE) {
        std.debug.print("{s}", .{bytes});
        return;
    }

    var mode: windows.DWORD = 0;
    if (GetConsoleMode(handle, &mode).toBool()) {
        const utf16 = std.unicode.utf8ToUtf16LeAlloc(arena, bytes) catch {
            std.debug.print("{s}", .{bytes});
            return;
        };
        if (writeConsoleUtf16(handle, utf16)) return;
    } else if (writeFileBytes(handle, bytes)) {
        return;
    }

    std.debug.print("{s}", .{bytes});
}

/// Reads the ordered Windows UI language list and returns the first supported language.
fn detectPreferredUiLanguage(arena: std.mem.Allocator) ?translation_text.Language {
    var language_count: windows.ULONG = 0;
    var buffer_len: windows.ULONG = 0;
    if (!GetUserPreferredUILanguages(mui_language_name, &language_count, null, &buffer_len).toBool() or buffer_len == 0) return null;

    const buffer = arena.alloc(u16, buffer_len) catch return null;
    if (!GetUserPreferredUILanguages(mui_language_name, &language_count, buffer.ptr, &buffer_len).toBool()) return null;

    var index: usize = 0;
    while (index < buffer_len and buffer[index] != 0) {
        const start = index;
        while (index < buffer_len and buffer[index] != 0) : (index += 1) {}
        if (parseLanguageUtf16Ascii(buffer[start..index])) |language| return language;
        index += 1;
    }
    return null;
}

/// Reads the Windows locale name when the UI language list is unavailable.
fn detectLocaleLanguage() ?translation_text.Language {
    var buffer: [locale_name_max_length]u16 = undefined;
    const len = GetUserDefaultLocaleName(&buffer, buffer.len);
    if (len <= 1) return null;
    return parseLanguageUtf16Ascii(buffer[0..@intCast(len - 1)]);
}

/// Parses the ASCII language prefix of a Windows UTF-16 locale name.
fn parseLanguageUtf16Ascii(value: []const u16) ?translation_text.Language {
    if (startsWithIgnoreCaseUtf16Ascii(value, "zh")) return .chinese;
    if (startsWithIgnoreCaseUtf16Ascii(value, "en")) return .english;
    return null;
}

fn startsWithIgnoreCaseUtf16Ascii(value: []const u16, prefix: []const u8) bool {
    if (value.len < prefix.len) return false;
    for (value[0..prefix.len], prefix) |actual, expected| {
        if (actual > std.math.maxInt(u8)) return false;
        if (std.ascii.toLower(@intCast(actual)) != std.ascii.toLower(expected)) return false;
    }
    return true;
}

fn writeConsoleUtf16(handle: windows.HANDLE, bytes: []const u16) bool {
    var index: usize = 0;
    while (index < bytes.len) {
        const chunk_len: windows.DWORD = @intCast(@min(bytes.len - index, std.math.maxInt(windows.DWORD)));
        var written: windows.DWORD = 0;
        if (!WriteConsoleW(handle, bytes[index..].ptr, chunk_len, &written, null).toBool() or written == 0) return false;
        index += written;
    }
    return true;
}

fn writeFileBytes(handle: windows.HANDLE, bytes: []const u8) bool {
    var index: usize = 0;
    while (index < bytes.len) {
        const chunk_len: windows.DWORD = @intCast(@min(bytes.len - index, std.math.maxInt(windows.DWORD)));
        var written: windows.DWORD = 0;
        if (!WriteFile(handle, bytes[index..].ptr, chunk_len, &written, null).toBool() or written == 0) return false;
        index += written;
    }
    return true;
}

const std_error_handle: windows.DWORD = @bitCast(@as(i32, -12));
const mui_language_name: windows.DWORD = 0x8;
const locale_name_max_length = 85;

extern "kernel32" fn GetStdHandle(
    nStdHandle: windows.DWORD,
) callconv(.winapi) ?windows.HANDLE;

extern "kernel32" fn GetConsoleMode(
    hConsoleHandle: windows.HANDLE,
    lpMode: *windows.DWORD,
) callconv(.winapi) windows.BOOL;

extern "kernel32" fn WriteConsoleW(
    hConsoleOutput: windows.HANDLE,
    lpBuffer: [*]const u16,
    nNumberOfCharsToWrite: windows.DWORD,
    lpNumberOfCharsWritten: *windows.DWORD,
    lpReserved: ?*anyopaque,
) callconv(.winapi) windows.BOOL;

extern "kernel32" fn WriteFile(
    hFile: windows.HANDLE,
    lpBuffer: [*]const u8,
    nNumberOfBytesToWrite: windows.DWORD,
    lpNumberOfBytesWritten: *windows.DWORD,
    lpOverlapped: ?*anyopaque,
) callconv(.winapi) windows.BOOL;

extern "kernel32" fn GetUserPreferredUILanguages(
    dwFlags: windows.DWORD,
    pulNumLanguages: *windows.ULONG,
    pwszLanguagesBuffer: ?[*]windows.WCHAR,
    pcchLanguagesBuffer: *windows.ULONG,
) callconv(.winapi) windows.BOOL;

extern "kernel32" fn GetUserDefaultLocaleName(
    lpLocaleName: [*]windows.WCHAR,
    cchLocaleName: c_int,
) callconv(.winapi) c_int;
