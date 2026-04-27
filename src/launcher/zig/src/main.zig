const std = @import("std");
const jar_search = @import("jar_search.zig");
const tool_search = @import("tool_search.zig");
const translate = @import("translate.zig");

pub fn main(init: std.process.Init) !void {
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    const exe_dir = try std.process.executableDirPathAlloc(init.io, arena);
    const text = translate.fromEnvironment(arena, init.environ_map);
    const jar_path = switch (jar_search.find(init.io, arena, exe_dir) catch |err| {
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
        .multiple => |paths| {
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
    };
    const gdparser_resource_dir = try std.fmt.allocPrint(
        arena,
        "-Dgdparser.gdscript.resourceDir={s}",
        .{exe_dir},
    );
    const java_path = tool_search.findJava25(init.io, arena, init.environ_map, exe_dir) catch |err| {
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

    var child_env = try init.environ_map.clone(arena);
    const zig_home = tool_search.findZigHome(init.io, arena, init.environ_map, exe_dir) catch |err| {
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
    try child_env.put("ZIG_HOME", zig_home);

    var child_args: std.ArrayList([]const u8) = .empty;
    try child_args.append(arena, java_path);
    try child_args.append(arena, "--enable-native-access=ALL-UNNAMED");
    try child_args.append(arena, gdparser_resource_dir);
    try child_args.append(arena, "-jar");
    try child_args.append(arena, jar_path);
    for (args[1..]) |arg| {
        try child_args.append(arena, arg);
    }

    var child = try std.process.spawn(init.io, .{
        .argv = child_args.items,
        .environ_map = &child_env,
    });
    const term = try child.wait(init.io);
    exitWithChildTerm(term);
}

fn exitWithChildTerm(term: std.process.Child.Term) noreturn {
    switch (term) {
        .exited => |code| std.process.exit(code),
        .signal => std.process.exit(128),
        .stopped => std.process.exit(128),
        .unknown => std.process.exit(1),
    }
}
