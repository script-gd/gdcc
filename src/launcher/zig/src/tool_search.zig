const builtin = @import("builtin");
const std = @import("std");

const java_min_major = 25;

/// Finds a Java 25+ runtime, preferring tools next to the launcher before
/// falling back to Java environment variables, PATH, and common system paths.
pub fn findJava25(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    exe_dir: []const u8,
) !?[]const u8 {
    const exe_name = executableName("java");

    if (try checkJavaPath(io, arena, env, try std.fs.path.join(arena, &.{ exe_dir, exe_name }))) |path| return path;
    if (try checkJavaPath(io, arena, env, try std.fs.path.join(arena, &.{ exe_dir, "java", "bin", exe_name }))) |path| return path;
    if (try checkJavaPath(io, arena, env, try std.fs.path.join(arena, &.{ exe_dir, "jre", "bin", exe_name }))) |path| return path;
    if (try checkJavaPath(io, arena, env, try std.fs.path.join(arena, &.{ exe_dir, "jdk", "bin", exe_name }))) |path| return path;

    if (try findJavaFromEnv(io, arena, env, exe_name)) |path| return path;
    if (try findJavaFromPath(io, arena, env, exe_name)) |path| return path;

    if (try findJavaFromCommonPaths(io, arena, env, exe_name)) |path| return path;

    return null;
}

/// Finds Zig for the launched JVM, preferring tools next to the launcher before
/// falling back to Zig environment variables, PATH, and common system paths.
pub fn findZigHome(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    exe_dir: []const u8,
) !?[]const u8 {
    const exe_name = executableName("zig");

    if (try checkExecutablePath(io, try std.fs.path.join(arena, &.{ exe_dir, exe_name }))) |path| return std.fs.path.dirname(path);
    if (try checkExecutablePath(io, try std.fs.path.join(arena, &.{ exe_dir, "zig", exe_name }))) |path| return std.fs.path.dirname(path);
    if (try checkExecutablePath(io, try std.fs.path.join(arena, &.{ exe_dir, "zig", "bin", exe_name }))) |path| return std.fs.path.dirname(path);

    if (try findToolFromEnv(io, arena, env, exe_name, &.{ "ZIG", "ZIG_HOME", "ZIG_ROOT", "zig" })) |path| return std.fs.path.dirname(path);
    if (try findExecutableFromPath(io, arena, env, exe_name)) |path| return std.fs.path.dirname(path);

    for (zigCommonPaths(arena, env, exe_name)) |path| {
        if (try checkExecutablePath(io, path)) |found| return std.fs.path.dirname(found);
    }

    return null;
}

fn findJavaFromEnv(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    exe_name: []const u8,
) !?[]const u8 {
    const keys = [_][]const u8{ "JAVA", "JAVA_HOME", "JDK_HOME", "JRE_HOME", "java" };
    for (keys) |key| {
        const value = env.get(key) orelse continue;
        if (std.mem.trim(u8, value, " \t\r\n").len == 0) continue;

        if (try checkJavaPath(io, arena, env, value)) |path| return path;
        if (try checkJavaPath(io, arena, env, try std.fs.path.join(arena, &.{ value, exe_name }))) |path| return path;
        if (try checkJavaPath(io, arena, env, try std.fs.path.join(arena, &.{ value, "bin", exe_name }))) |path| return path;
    }
    return null;
}

fn findJavaFromPath(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    exe_name: []const u8,
) !?[]const u8 {
    const path_value = env.get("PATH") orelse env.get("Path") orelse env.get("path") orelse return null;
    var parts = std.mem.splitScalar(u8, path_value, std.fs.path.delimiter);
    while (parts.next()) |part| {
        if (std.mem.trim(u8, part, " \t\r\n").len == 0 or !std.fs.path.isAbsolute(part)) continue;
        if (try checkJavaPath(io, arena, env, try std.fs.path.join(arena, &.{ part, exe_name }))) |path| return path;
    }
    return null;
}

fn findJavaFromCommonPaths(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    exe_name: []const u8,
) !?[]const u8 {
    var paths: std.ArrayList([]const u8) = .empty;
    try appendJavaCommonPathCandidates(io, arena, env, exe_name, &paths);
    for (paths.items) |path| {
        if (try checkJavaPath(io, arena, env, path)) |java_path| return java_path;
    }
    return null;
}

fn findToolFromEnv(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    exe_name: []const u8,
    keys: []const []const u8,
) !?[]const u8 {
    for (keys) |key| {
        const value = env.get(key) orelse continue;
        if (std.mem.trim(u8, value, " \t\r\n").len == 0) continue;

        if (try checkExecutablePath(io, value)) |path| return path;
        if (try checkExecutablePath(io, try std.fs.path.join(arena, &.{ value, exe_name }))) |path| return path;
        if (try checkExecutablePath(io, try std.fs.path.join(arena, &.{ value, "bin", exe_name }))) |path| return path;
    }
    return null;
}

fn findExecutableFromPath(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    exe_name: []const u8,
) !?[]const u8 {
    const path_value = env.get("PATH") orelse env.get("Path") orelse env.get("path") orelse return null;
    var parts = std.mem.splitScalar(u8, path_value, std.fs.path.delimiter);
    while (parts.next()) |part| {
        if (std.mem.trim(u8, part, " \t\r\n").len == 0 or !std.fs.path.isAbsolute(part)) continue;
        if (try checkExecutablePath(io, try std.fs.path.join(arena, &.{ part, exe_name }))) |path| return path;
    }
    return null;
}

fn checkJavaPath(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    path: []const u8,
) !?[]const u8 {
    const executable_path = (try checkExecutablePath(io, path)) orelse return null;
    return if (try isJava25OrNewer(io, arena, env, executable_path)) executable_path else null;
}

fn checkExecutablePath(io: std.Io, path: []const u8) !?[]const u8 {
    if (!std.fs.path.isAbsolute(path)) return null;
    const stat = std.Io.Dir.cwd().statFile(io, path, .{}) catch return null;
    if (stat.kind != .file) return null;
    std.Io.Dir.accessAbsolute(io, path, .{ .execute = true }) catch return null;
    return path;
}

fn isJava25OrNewer(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    path: []const u8,
) !bool {
    const result = std.process.run(arena, io, .{
        .argv = &.{ path, "--version" },
        .stdout_limit = .limited(4096),
        .stderr_limit = .limited(4096),
        .environ_map = env,
    }) catch return false;

    switch (result.term) {
        .exited => |code| if (code != 0) return false,
        else => return false,
    }
    return hasJavaMinimumMajor(result.stdout) or hasJavaMinimumMajor(result.stderr);
}

fn hasJavaMinimumMajor(output: []const u8) bool {
    return (firstUnsignedInteger(output) orelse return false) >= java_min_major;
}

fn firstUnsignedInteger(output: []const u8) ?u32 {
    var index: usize = 0;
    while (index < output.len and !std.ascii.isDigit(output[index])) : (index += 1) {}
    if (index == output.len) return null;

    var value: u32 = 0;
    while (index < output.len and std.ascii.isDigit(output[index])) : (index += 1) {
        value = std.math.mul(u32, value, 10) catch return null;
        value = std.math.add(u32, value, output[index] - '0') catch return null;
    }
    return value;
}

fn executableName(comptime base: []const u8) []const u8 {
    return if (builtin.os.tag == .windows) base ++ ".exe" else base;
}

fn appendJavaCommonPathCandidates(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    exe_name: []const u8,
    paths: *std.ArrayList([]const u8),
) !void {
    switch (builtin.os.tag) {
        .windows => try appendWindowsJavaCommonCandidates(io, arena, env, exe_name, paths),
        .macos => {
            try paths.append(arena, "/usr/bin/java");
            try paths.append(arena, "/usr/local/bin/java");
            try paths.append(arena, "/opt/homebrew/bin/java");
        },
        else => {
            try paths.append(arena, "/usr/bin/java");
            try paths.append(arena, "/usr/local/bin/java");
        },
    }
}

fn appendWindowsJavaCommonCandidates(
    io: std.Io,
    arena: std.mem.Allocator,
    env: *const std.process.Environ.Map,
    exe_name: []const u8,
    paths: *std.ArrayList([]const u8),
) !void {
    const program_file_keys = [_][]const u8{ "ProgramW6432", "ProgramFiles", "ProgramFiles(x86)" };
    for (program_file_keys) |key| {
        const program_files = envPath(env, key) orelse continue;
        try appendProgramFilesJavaCandidates(io, arena, program_files, exe_name, paths);
    }

    if (envPath(env, "USERPROFILE")) |user_profile| {
        try appendJavaHomesUnderRoot(io, arena, try std.fs.path.join(arena, &.{ user_profile, ".jdks" }), exe_name, paths);
    }
    if (envPath(env, "LOCALAPPDATA")) |local_app_data| {
        try appendJavaHomesUnderRoot(io, arena, try std.fs.path.join(arena, &.{ local_app_data, "Programs", "Java" }), exe_name, paths);
    }
}

fn appendProgramFilesJavaCandidates(
    io: std.Io,
    arena: std.mem.Allocator,
    program_files: []const u8,
    exe_name: []const u8,
    paths: *std.ArrayList([]const u8),
) !void {
    const vendor_dirs = [_][]const u8{
        "Java",
        "Eclipse Adoptium",
        "Microsoft",
        "Amazon Corretto",
        "BellSoft",
        "Zulu",
        "GraalVM",
    };
    for (vendor_dirs) |vendor_dir| {
        try appendJavaHomesUnderRoot(io, arena, try std.fs.path.join(arena, &.{ program_files, vendor_dir }), exe_name, paths);
    }
}

fn appendJavaHomesUnderRoot(
    io: std.Io,
    arena: std.mem.Allocator,
    root: []const u8,
    exe_name: []const u8,
    paths: *std.ArrayList([]const u8),
) !void {
    if (!std.fs.path.isAbsolute(root)) return;

    var dir = std.Io.Dir.openDirAbsolute(io, root, .{ .iterate = true }) catch return;
    defer dir.close(io);

    var iterator = dir.iterate();
    while (try iterator.next(io)) |entry| {
        if (entry.kind != .directory) continue;

        const candidate = try std.fs.path.join(arena, &.{ root, entry.name, "bin", exe_name });
        const stat = std.Io.Dir.cwd().statFile(io, candidate, .{}) catch continue;
        if (stat.kind != .file) continue;
        try paths.append(arena, candidate);
    }
}

fn envPath(env: *const std.process.Environ.Map, key: []const u8) ?[]const u8 {
    const value = env.get(key) orelse return null;
    const trimmed = std.mem.trim(u8, value, " \t\r\n");
    if (trimmed.len == 0 or !std.fs.path.isAbsolute(trimmed)) return null;
    return trimmed;
}

fn zigCommonPaths(arena: std.mem.Allocator, env: *const std.process.Environ.Map, exe_name: []const u8) []const []const u8 {
    var paths: std.ArrayList([]const u8) = .empty;
    switch (builtin.os.tag) {
        .windows => {
            if (env.get("ProgramFiles")) |program_files| {
                paths.append(arena, std.fs.path.join(arena, &.{ program_files, "Zig", exe_name }) catch return paths.items) catch return paths.items;
            }
            if (env.get("ProgramFiles(x86)")) |program_files_x86| {
                paths.append(arena, std.fs.path.join(arena, &.{ program_files_x86, "Zig", exe_name }) catch return paths.items) catch return paths.items;
            }
            if (env.get("ProgramData")) |program_data| {
                paths.append(arena, std.fs.path.join(arena, &.{ program_data, "chocolatey", "bin", exe_name }) catch return paths.items) catch return paths.items;
            }
        },
        .macos => {
            paths.append(arena, "/usr/local/bin/zig") catch return paths.items;
            paths.append(arena, "/opt/homebrew/bin/zig") catch return paths.items;
            paths.append(arena, "/opt/homebrew/opt/zig/bin/zig") catch return paths.items;
        },
        else => {
            paths.append(arena, "/usr/bin/zig") catch return paths.items;
            paths.append(arena, "/usr/local/bin/zig") catch return paths.items;
            paths.append(arena, "/snap/bin/zig") catch return paths.items;
        },
    }
    return paths.items;
}

test "detects Java major versions from version output" {
    try std.testing.expect(hasJavaMinimumMajor("openjdk 25.0.1 2025-10-21"));
    try std.testing.expect(hasJavaMinimumMajor("java version \"26\""));
    try std.testing.expect(!hasJavaMinimumMajor("openjdk 24.0.2"));
    try std.testing.expect(!hasJavaMinimumMajor("not a java version"));
}

test "collects Windows Java candidates from Program Files roots" {
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();

    try touchPath(tmp.dir, "Program Files/Java/jdk-25/bin/java.exe");
    try touchPath(tmp.dir, "Program Files/Eclipse Adoptium/temurin-25/bin/java.exe");
    try touchPath(tmp.dir, "Program Files (x86)/Java/jdk-25-x86/bin/java.exe");
    try tmp.dir.createDirPath(std.testing.io, "Program Files/Java/not-a-jdk");

    const root = try tmpDirPath(std.testing.allocator, tmp);
    defer std.testing.allocator.free(root);

    var env = std.process.Environ.Map.init(std.testing.allocator);
    defer env.deinit();
    try putJoinedEnvPath(&env, root, "ProgramFiles", &.{"Program Files"});
    try putJoinedEnvPath(&env, root, "ProgramFiles(x86)", &.{"Program Files (x86)"});

    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    var paths: std.ArrayList([]const u8) = .empty;
    try appendWindowsJavaCommonCandidates(std.testing.io, arena.allocator(), &env, "java.exe", &paths);

    try expectContainsPath(paths.items, try std.fs.path.join(std.testing.allocator, &.{ root, "Program Files", "Java", "jdk-25", "bin", "java.exe" }));
    try expectContainsPath(paths.items, try std.fs.path.join(std.testing.allocator, &.{ root, "Program Files", "Eclipse Adoptium", "temurin-25", "bin", "java.exe" }));
    try expectContainsPath(paths.items, try std.fs.path.join(std.testing.allocator, &.{ root, "Program Files (x86)", "Java", "jdk-25-x86", "bin", "java.exe" }));
    try expectMissingPath(paths.items, try std.fs.path.join(std.testing.allocator, &.{ root, "Program Files", "Java", "not-a-jdk", "bin", "java.exe" }));
}

test "collects Windows Java candidates from user jdks and local app data" {
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();

    try touchPath(tmp.dir, "Users/alice/.jdks/openjdk-25/bin/java.exe");
    try touchPath(tmp.dir, "Users/alice/AppData/Local/Programs/Java/graalvm-jdk-25/bin/java.exe");

    const root = try tmpDirPath(std.testing.allocator, tmp);
    defer std.testing.allocator.free(root);

    var env = std.process.Environ.Map.init(std.testing.allocator);
    defer env.deinit();
    try putJoinedEnvPath(&env, root, "USERPROFILE", &.{ "Users", "alice" });
    try putJoinedEnvPath(&env, root, "LOCALAPPDATA", &.{ "Users", "alice", "AppData", "Local" });
    try putJoinedEnvPath(&env, root, "ProgramFiles", &.{"missing"});

    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    var paths: std.ArrayList([]const u8) = .empty;
    try appendWindowsJavaCommonCandidates(std.testing.io, arena.allocator(), &env, "java.exe", &paths);

    try expectContainsPath(paths.items, try std.fs.path.join(std.testing.allocator, &.{ root, "Users", "alice", ".jdks", "openjdk-25", "bin", "java.exe" }));
    try expectContainsPath(paths.items, try std.fs.path.join(std.testing.allocator, &.{ root, "Users", "alice", "AppData", "Local", "Programs", "Java", "graalvm-jdk-25", "bin", "java.exe" }));
}

test "ignores blank and relative Windows Java common roots" {
    var env = std.process.Environ.Map.init(std.testing.allocator);
    defer env.deinit();
    try env.put("ProgramFiles", " ");
    try env.put("USERPROFILE", "relative-user");

    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    var paths: std.ArrayList([]const u8) = .empty;
    try appendWindowsJavaCommonCandidates(std.testing.io, arena.allocator(), &env, "java.exe", &paths);

    try std.testing.expectEqual(@as(usize, 0), paths.items.len);
}

fn touchPath(dir: std.Io.Dir, path: []const u8) !void {
    if (std.fs.path.dirname(path)) |dirname| {
        try dir.createDirPath(std.testing.io, dirname);
    }
    var file = try dir.createFile(std.testing.io, path, .{});
    file.close(std.testing.io);
}

fn putJoinedEnvPath(
    env: *std.process.Environ.Map,
    root: []const u8,
    key: []const u8,
    components: []const []const u8,
) !void {
    var parts: std.ArrayList([]const u8) = .empty;
    try parts.append(std.testing.allocator, root);
    defer parts.deinit(std.testing.allocator);
    for (components) |component| {
        try parts.append(std.testing.allocator, component);
    }

    const value = try std.fs.path.join(std.testing.allocator, parts.items);
    defer std.testing.allocator.free(value);
    try env.put(key, value);
}

fn tmpDirPath(allocator: std.mem.Allocator, tmp: std.testing.TmpDir) ![]const u8 {
    const cwd = try std.process.currentPathAlloc(std.testing.io, allocator);
    defer allocator.free(cwd);
    return std.fs.path.join(allocator, &.{ cwd, ".zig-cache", "tmp", &tmp.sub_path });
}

fn expectContainsPath(paths: []const []const u8, expected: []const u8) !void {
    defer std.testing.allocator.free(expected);
    for (paths) |path| {
        if (std.mem.eql(u8, path, expected)) return;
    }
    return error.TestUnexpectedResult;
}

fn expectMissingPath(paths: []const []const u8, expected: []const u8) !void {
    defer std.testing.allocator.free(expected);
    for (paths) |path| {
        if (std.mem.eql(u8, path, expected)) return error.TestUnexpectedResult;
    }
}
