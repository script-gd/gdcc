const std = @import("std");

const LauncherTarget = struct {
    name: []const u8,
    triple: []const u8,
};

const default_targets = [_]LauncherTarget{
    .{ .name = "windows-x86_64", .triple = "x86_64-windows" },
    .{ .name = "linux-x86_64", .triple = "x86_64-linux" },
    .{ .name = "linux-aarch64", .triple = "aarch64-linux" },
};

pub fn build(b: *std.Build) void {
    const optimize = b.standardOptimizeOption(.{
        .preferred_optimize_mode = .ReleaseSmall,
    });
    const jar_name = b.option([]const u8, "jar-name", "Name of the gdcc jar next to the launcher bin directory") orelse "gdcc.jar";

    const options = b.addOptions();
    options.addOption([]const u8, "gdcc_jar_name", jar_name);

    const install_step = b.getInstallStep();
    for (default_targets) |launcher_target| {
        const target_query = std.Target.Query.parse(.{ .arch_os_abi = launcher_target.triple }) catch
            @panic("invalid launcher target triple");
        const root_module = b.createModule(.{
            .root_source_file = b.path("src/main.zig"),
            .target = b.resolveTargetQuery(target_query),
            .optimize = optimize,
        });
        root_module.addOptions("build_options", options);

        const exe = b.addExecutable(.{
            .name = "gdcc",
            .root_module = root_module,
        });

        const dest_sub_path = if (std.mem.startsWith(u8, launcher_target.name, "windows"))
            b.fmt("{s}/gdcc.exe", .{launcher_target.name})
        else
            b.fmt("{s}/gdcc", .{launcher_target.name});
        const install_artifact = b.addInstallArtifact(exe, .{
            .dest_sub_path = dest_sub_path,
            .pdb_dir = .disabled,
            .implib_dir = .disabled,
        });
        install_step.dependOn(&install_artifact.step);
    }
}
