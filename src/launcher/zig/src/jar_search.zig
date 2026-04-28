const std = @import("std");

pub const Result = union(enum) {
    found: []const u8,
    none,
    multiple: []const []const u8,
};

const JarCandidate = struct {
    name: []const u8,
    path: []const u8,
};

pub fn find(io: std.Io, arena: std.mem.Allocator, exe_dir: []const u8) !Result {
    var dir = try std.Io.Dir.openDirAbsolute(io, exe_dir, .{ .iterate = true });
    defer dir.close(io);

    var jar_matches: std.ArrayList(JarCandidate) = .empty;
    var update_matches: std.ArrayList(JarCandidate) = .empty;
    var iterator = dir.iterate();
    while (try iterator.next(io)) |entry| {
        if (entry.kind != .file) continue;

        const name = try arena.dupe(u8, entry.name);
        const candidate_path = try std.fs.path.join(arena, &.{ exe_dir, entry.name });
        const candidate: JarCandidate = .{
            .name = name,
            .path = candidate_path,
        };
        if (isGdccUpdateJarName(entry.name)) {
            try update_matches.append(arena, candidate);
        } else if (isGdccJarName(entry.name)) {
            try jar_matches.append(arena, candidate);
        }
    }

    if (update_matches.items.len > 1) {
        return .{ .multiple = try candidatePaths(arena, update_matches.items) };
    }
    if (update_matches.items.len == 1) {
        return .{ .found = try applyUpdateJar(io, arena, dir, exe_dir, update_matches.items[0], jar_matches.items) };
    }

    return switch (jar_matches.items.len) {
        0 => .none,
        1 => .{ .found = jar_matches.items[0].path },
        else => .{ .multiple = try candidatePaths(arena, jar_matches.items) },
    };
}

fn applyUpdateJar(
    io: std.Io,
    arena: std.mem.Allocator,
    dir: std.Io.Dir,
    exe_dir: []const u8,
    update_jar: JarCandidate,
    jar_matches: []const JarCandidate,
) ![]const u8 {
    for (jar_matches) |jar| {
        try dir.deleteFile(io, jar.name);
    }

    const target_name = try updatedJarName(arena, update_jar.name);
    try dir.rename(update_jar.name, dir, target_name, io);
    return std.fs.path.join(arena, &.{ exe_dir, target_name });
}

fn candidatePaths(arena: std.mem.Allocator, candidates: []const JarCandidate) ![]const []const u8 {
    var paths: std.ArrayList([]const u8) = .empty;
    for (candidates) |candidate| {
        try paths.append(arena, candidate.path);
    }
    return paths.items;
}

fn isGdccJarName(name: []const u8) bool {
    if (std.mem.endsWith(u8, name, "-update.jar")) return false;
    const version = jarVersion(name) orelse return false;
    return isThreeSegmentVersion(version);
}

fn isGdccUpdateJarName(name: []const u8) bool {
    const suffix = "-update.jar";
    if (!std.mem.endsWith(u8, name, suffix)) return false;

    const prefix = "gdcc-";
    if (!std.mem.startsWith(u8, name, prefix)) return false;

    const version = name[prefix.len .. name.len - suffix.len];
    return isThreeSegmentVersion(version);
}

fn updatedJarName(arena: std.mem.Allocator, name: []const u8) ![]const u8 {
    const suffix = "-update.jar";
    std.debug.assert(std.mem.endsWith(u8, name, suffix));
    return std.fmt.allocPrint(arena, "{s}.jar", .{name[0 .. name.len - suffix.len]});
}

fn jarVersion(name: []const u8) ?[]const u8 {
    const prefix = "gdcc-";
    const suffix = ".jar";
    if (!std.mem.startsWith(u8, name, prefix) or !std.mem.endsWith(u8, name, suffix)) return null;

    return name[prefix.len .. name.len - suffix.len];
}

fn isThreeSegmentVersion(version: []const u8) bool {
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

test "classifies gdcc jar names" {
    try std.testing.expect(isGdccJarName("gdcc-1.2.3.jar"));
    try std.testing.expect(!isGdccJarName("gdcc-1.2.3-update.jar"));
    try std.testing.expect(isGdccUpdateJarName("gdcc-1.2.3-update.jar"));
    try std.testing.expect(!isGdccUpdateJarName("gdcc-1.2.3.jar"));
    try std.testing.expect(!isGdccJarName("gdcc-1.2.jar"));
    try std.testing.expect(!isGdccJarName("gdcc-1.2.3.4.jar"));
    try std.testing.expect(!isGdccJarName("other-1.2.3.jar"));
}

test "renames unique update jar and removes old jars" {
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();

    try touch(tmp.dir, "gdcc-1.2.2.jar");
    try touch(tmp.dir, "gdcc-1.2.3-update.jar");
    try touch(tmp.dir, "other.jar");

    const exe_dir = try tmpDirPath(std.testing.allocator, tmp);
    defer std.testing.allocator.free(exe_dir);

    var find_arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer find_arena.deinit();
    const result = try find(std.testing.io, find_arena.allocator(), exe_dir);
    const expected_path = try std.fs.path.join(std.testing.allocator, &.{ exe_dir, "gdcc-1.2.3.jar" });
    defer std.testing.allocator.free(expected_path);
    switch (result) {
        .found => |path| try std.testing.expectEqualStrings(expected_path, path),
        else => return error.TestUnexpectedResult,
    }

    try expectFileExists(tmp.dir, "gdcc-1.2.3.jar");
    try expectFileMissing(tmp.dir, "gdcc-1.2.2.jar");
    try expectFileMissing(tmp.dir, "gdcc-1.2.3-update.jar");
    try expectFileExists(tmp.dir, "other.jar");
}

test "reports multiple ordinary jars without update jar" {
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();

    try touch(tmp.dir, "gdcc-1.2.2.jar");
    try touch(tmp.dir, "gdcc-1.2.3.jar");

    const exe_dir = try tmpDirPath(std.testing.allocator, tmp);
    defer std.testing.allocator.free(exe_dir);

    var find_arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer find_arena.deinit();
    const result = try find(std.testing.io, find_arena.allocator(), exe_dir);
    switch (result) {
        .multiple => |paths| try std.testing.expectEqual(@as(usize, 2), paths.len),
        else => return error.TestUnexpectedResult,
    }
}

test "reports multiple update jars" {
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();

    try touch(tmp.dir, "gdcc-1.2.2-update.jar");
    try touch(tmp.dir, "gdcc-1.2.3-update.jar");

    const exe_dir = try tmpDirPath(std.testing.allocator, tmp);
    defer std.testing.allocator.free(exe_dir);

    var find_arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer find_arena.deinit();
    const result = try find(std.testing.io, find_arena.allocator(), exe_dir);
    switch (result) {
        .multiple => |paths| try std.testing.expectEqual(@as(usize, 2), paths.len),
        else => return error.TestUnexpectedResult,
    }
}

fn touch(dir: std.Io.Dir, name: []const u8) !void {
    var file = try dir.createFile(std.testing.io, name, .{});
    file.close(std.testing.io);
}

fn tmpDirPath(allocator: std.mem.Allocator, tmp: std.testing.TmpDir) ![]const u8 {
    const cwd = try std.process.currentPathAlloc(std.testing.io, allocator);
    defer allocator.free(cwd);
    return std.fs.path.join(allocator, &.{ cwd, ".zig-cache", "tmp", &tmp.sub_path });
}

fn expectFileExists(dir: std.Io.Dir, name: []const u8) !void {
    _ = try dir.statFile(std.testing.io, name, .{});
}

fn expectFileMissing(dir: std.Io.Dir, name: []const u8) !void {
    _ = dir.statFile(std.testing.io, name, .{}) catch |err| switch (err) {
        error.FileNotFound => return,
        else => return err,
    };
    return error.TestUnexpectedResult;
}
