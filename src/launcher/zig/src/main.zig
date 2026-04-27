const std = @import("std");
const build_options = @import("build_options");

pub fn main(init: std.process.Init) !void {
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    const exe_dir = try std.process.executableDirPathAlloc(init.io, arena);
    const jar_path = findJar(init.io, arena, exe_dir) catch |err| {
        std.debug.print(
            "gdcc launcher: unable to find {s} near executable: {s}\n",
            .{ build_options.gdcc_jar_name, @errorName(err) },
        );
        std.process.exit(1);
    };

    var child_args: std.ArrayList([]const u8) = .empty;
    try child_args.append(arena, "java");
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

fn findJar(io: std.Io, arena: std.mem.Allocator, exe_dir: []const u8) ![]const u8 {
    const root_layout = try std.fs.path.join(arena, &.{ exe_dir, "..", "..", build_options.gdcc_jar_name });
    if (canOpen(io, root_layout)) return root_layout;

    const sibling_layout = try std.fs.path.join(arena, &.{ exe_dir, "..", build_options.gdcc_jar_name });
    if (canOpen(io, sibling_layout)) return sibling_layout;

    const local_layout = try std.fs.path.join(arena, &.{ exe_dir, build_options.gdcc_jar_name });
    if (canOpen(io, local_layout)) return local_layout;

    return error.JarNotFound;
}

fn canOpen(io: std.Io, absolute_path: []const u8) bool {
    const file = std.Io.Dir.openFileAbsolute(io, absolute_path, .{}) catch return false;
    file.close(io);
    return true;
}

fn exitWithChildTerm(term: std.process.Child.Term) noreturn {
    switch (term) {
        .exited => |code| std.process.exit(code),
        .signal => std.process.exit(128),
        .stopped => std.process.exit(128),
        .unknown => std.process.exit(1),
    }
}
