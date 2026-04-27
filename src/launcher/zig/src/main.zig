const std = @import("std");
const jar_search = @import("jar_search.zig");

pub fn main(init: std.process.Init) !void {
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    const exe_dir = try std.process.executableDirPathAlloc(init.io, arena);
    const jar_path = switch (jar_search.find(init.io, arena, exe_dir) catch |err| {
        std.debug.print(
            "gdcc launcher: unable to scan launcher directory {s}: {s}\n",
            .{ exe_dir, @errorName(err) },
        );
        std.process.exit(1);
    }) {
        .found => |path| path,
        .none => {
            std.debug.print(
                "gdcc launcher: unable to find gdcc-*.*.*.jar in launcher directory {s}\n",
                .{exe_dir},
            );
            std.process.exit(1);
        },
        .multiple => |paths| {
            std.debug.print(
                "gdcc launcher: found multiple gdcc-*.*.*.jar files in launcher directory {s}\n",
                .{exe_dir},
            );
            for (paths) |path| {
                std.debug.print("  {s}\n", .{path});
            }
            std.debug.print("Please delete all but one matching jar file.\n", .{});
            std.process.exit(1);
        },
    };
    const gdparser_resource_dir = try std.fmt.allocPrint(
        arena,
        "-Dgdparser.gdscript.resourceDir={s}",
        .{exe_dir},
    );

    var child_args: std.ArrayList([]const u8) = .empty;
    try child_args.append(arena, "java");
    try child_args.append(arena, "--enable-native-access=ALL-UNNAMED");
    try child_args.append(arena, gdparser_resource_dir);
    try child_args.append(arena, "-jar");
    try child_args.append(arena, jar_path);
    for (args[1..]) |arg| {
        try child_args.append(arena, arg);
    }

    var child = try std.process.spawn(init.io, .{
        .argv = child_args.items,
        .environ_map = init.environ_map,
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
