const std = @import("std");

pub const Result = union(enum) {
    found: []const u8,
    none,
    multiple: []const []const u8,
};

pub fn find(io: std.Io, arena: std.mem.Allocator, exe_dir: []const u8) !Result {
    var dir = try std.Io.Dir.openDirAbsolute(io, exe_dir, .{ .iterate = true });
    defer dir.close(io);

    var matches: std.ArrayList([]const u8) = .empty;
    var iterator = dir.iterate();
    while (try iterator.next(io)) |entry| {
        if (entry.kind != .file or !isGdccJarName(entry.name)) continue;

        const candidate_path = try std.fs.path.join(arena, &.{ exe_dir, entry.name });
        try matches.append(arena, candidate_path);
    }

    return switch (matches.items.len) {
        0 => .none,
        1 => .{ .found = matches.items[0] },
        else => .{ .multiple = matches.items },
    };
}

fn isGdccJarName(name: []const u8) bool {
    const prefix = "gdcc-";
    const suffix = ".jar";
    if (!std.mem.startsWith(u8, name, prefix) or !std.mem.endsWith(u8, name, suffix)) return false;

    const version = name[prefix.len .. name.len - suffix.len];
    var dot_count: usize = 0;
    var segment_len: usize = 0;
    for (version) |char| {
        if (char == '.') {
            if (segment_len == 0) return false;
            dot_count += 1;
            segment_len = 0;
            continue;
        }
        segment_len += 1;
    }

    return dot_count == 2 and segment_len > 0;
}
