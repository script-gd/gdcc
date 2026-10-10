const std = @import("std");
const jar_search = @import("jar_search.zig");
const tool_search = @import("tool_search.zig");
const translate = @import("translate.zig");

pub fn main() !void {
    var arena_state = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena_state.deinit();
    const arena = arena_state.allocator();
    const args = try std.process.argsAlloc(arena);
    const exe_dir = try std.fs.selfExeDirPathAlloc(arena);
    var env = try std.process.getEnvMap(arena);
    const text = translate.fromEnvironment(arena, &env);
    const jar_path = switch (jar_search.find(arena, exe_dir) catch |err| {
        translate.printStderr(
            arena,
            "{s}: {s} {s}: {s}\n",
            .{ text.launcher_prefix, text.scan_launcher_dir_failed, exe_dir, @errorName(err) },
        );
        std.process.exit(1);
    }) {
        .found => |path| path,
        .none => {
            translate.printStderr(
                arena,
                "{s}: {s} {s}\n",
                .{ text.launcher_prefix, text.jar_not_found, exe_dir },
            );
            std.process.exit(1);
        },
        .multiple_jars => |paths| {
            translate.printStderr(
                arena,
                "{s}: {s} {s}\n",
                .{ text.launcher_prefix, text.multiple_jars_found, exe_dir },
            );
            for (paths) |path| {
                translate.printStderr(arena, "  {s}\n", .{path});
            }
            translate.printStderr(arena, "{s}\n", .{text.delete_extra_jars});
            std.process.exit(1);
        },
        .multiple_update_jars => |paths| {
            translate.printStderr(
                arena,
                "{s}: {s} {s}\n",
                .{ text.launcher_prefix, text.multiple_update_jars_found, exe_dir },
            );
            for (paths) |path| {
                translate.printStderr(arena, "  {s}\n", .{path});
            }
            translate.printStderr(arena, "{s}\n", .{text.delete_extra_jars});
            std.process.exit(1);
        },
    };
    const gdparser_resource_dir = try std.fmt.allocPrint(
        arena,
        "-Dgdparser.gdscript.resourceDir={s}/native",
        .{exe_dir},
    );
    const java_path = tool_search.findJava25(arena, &env, exe_dir) catch |err| {
        translate.printStderr(
            arena,
            "{s}: {s}: {s}\n",
            .{ text.launcher_prefix, text.java_search_failed, @errorName(err) },
        );
        std.process.exit(1);
    } orelse {
        translate.printStderr(
            arena,
            "{s}: {s}\n",
            .{ text.launcher_prefix, text.java_not_found },
        );
        std.process.exit(1);
    };

    const zig_home = tool_search.findZigHome(arena, &env, exe_dir) catch |err| {
        translate.printStderr(
            arena,
            "{s}: {s}: {s}\n",
            .{ text.launcher_prefix, text.zig_search_failed, @errorName(err) },
        );
        std.process.exit(1);
    } orelse {
        translate.printStderr(
            arena,
            "{s}: {s}\n",
            .{ text.launcher_prefix, text.zig_not_found },
        );
        std.process.exit(1);
    };
    try env.put("ZIG_HOME", zig_home);

    var child_args: std.ArrayList([]const u8) = .empty;
    try child_args.append(arena, java_path);
    try child_args.append(arena, "--enable-native-access=ALL-UNNAMED");
    try child_args.append(arena, gdparser_resource_dir);
    try child_args.append(arena, "-jar");
    try child_args.append(arena, jar_path);
    for (args[1..]) |arg| {
        try child_args.append(arena, arg);
    }

    var child = std.process.Child.init(child_args.items, arena);
    child.env_map = &env;
    try child.spawn();
    const term = try child.wait();
    exitWithChildTerm(term);
}

fn exitWithChildTerm(term: std.process.Child.Term) noreturn {
    switch (term) {
        .Exited => |code| std.process.exit(code),
        .Signal => std.process.exit(128),
        .Stopped => std.process.exit(128),
        .Unknown => std.process.exit(1),
    }
}
