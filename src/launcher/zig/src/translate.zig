const builtin = @import("builtin");
const std = @import("std");
const translation_text = @import("translation_text.zig");
const windows_support = if (builtin.os.tag == .windows) @import("windows_support.zig") else struct {};

pub const Text = translation_text.Text;

/// Selects launcher text from environment overrides, platform locale, then English fallback.
pub fn fromEnvironment(arena: std.mem.Allocator, env: *const std.process.Environ.Map) Text {
    return translation_text.get(detectLanguage(arena, env));
}

/// Prints UTF-8 text to stderr, using Windows console-safe output when needed.
pub fn printStderr(arena: std.mem.Allocator, comptime format: []const u8, args: anytype) void {
    if (builtin.os.tag == .windows) {
        const message = std.fmt.allocPrint(arena, format, args) catch {
            std.debug.print(format, args);
            return;
        };
        windows_support.writeStderrUtf8(arena, message);
        return;
    }

    std.debug.print(format, args);
}

/// Detects the launcher language from explicit environment settings and platform defaults.
fn detectLanguage(arena: std.mem.Allocator, env: *const std.process.Environ.Map) translation_text.Language {
    if (detectEnvironmentLanguage(env)) |language| return language;
    if (detectPlatformLanguage(arena)) |language| return language;
    return .english;
}

/// Reads POSIX-style language environment variables as explicit launcher overrides.
fn detectEnvironmentLanguage(env: *const std.process.Environ.Map) ?translation_text.Language {
    const keys = [_][]const u8{ "LC_ALL", "LC_MESSAGES", "LANG", "LANGUAGE" };
    for (keys) |key| {
        const value = env.get(key) orelse continue;
        if (parseLanguage(value)) |language| return language;
    }
    return null;
}

/// Reads platform-native language settings when environment overrides are absent.
fn detectPlatformLanguage(arena: std.mem.Allocator) ?translation_text.Language {
    return switch (builtin.os.tag) {
        .windows => windows_support.detectLanguage(arena),
        else => null,
    };
}

/// Parses a UTF-8 locale or language tag and maps supported prefixes to translations.
fn parseLanguage(value: []const u8) ?translation_text.Language {
    const trimmed = std.mem.trim(u8, value, " \t\r\n");
    if (trimmed.len == 0) return null;

    const first_locale = std.mem.sliceTo(trimmed, ':');
    if (startsWithIgnoreCase(first_locale, "zh")) return .chinese;
    if (startsWithIgnoreCase(first_locale, "en")) return .english;
    return null;
}

fn startsWithIgnoreCase(value: []const u8, prefix: []const u8) bool {
    if (value.len < prefix.len) return false;
    for (value[0..prefix.len], prefix) |actual, expected| {
        if (std.ascii.toLower(actual) != std.ascii.toLower(expected)) return false;
    }
    return true;
}

test "detects supported launcher languages" {
    try std.testing.expectEqual(translation_text.Language.chinese, parseLanguage("zh_CN.UTF-8").?);
    try std.testing.expectEqual(translation_text.Language.chinese, parseLanguage("ZH-Hans").?);
    try std.testing.expectEqual(translation_text.Language.english, parseLanguage("en_US.UTF-8").?);
    try std.testing.expect(parseLanguage("fr_FR.UTF-8") == null);
}
