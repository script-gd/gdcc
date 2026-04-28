pub const Language = enum {
    english,
    chinese,
};

pub const Text = struct {
    launcher_prefix: []const u8,
    scan_launcher_dir_failed: []const u8,
    jar_not_found: []const u8,
    multiple_jars_found: []const u8,
    multiple_update_jars_found: []const u8,
    delete_extra_jars: []const u8,
    java_search_failed: []const u8,
    java_not_found: []const u8,
    zig_search_failed: []const u8,
    zig_not_found: []const u8,
};

pub fn get(language: Language) Text {
    return switch (language) {
        .english => .{
            .launcher_prefix = "gdcc",
            .scan_launcher_dir_failed = "unable to scan gdcc install directory",
            .jar_not_found = "unable to find gdcc-*.*.*.jar in gdcc install directory",
            .multiple_jars_found = "found multiple gdcc-*.*.*.jar files in gdcc install directory",
            .multiple_update_jars_found = "found multiple gdcc-*.*.*-update.jar files in gdcc install directory",
            .delete_extra_jars = "Please delete all but one matching jar file.",
            .java_search_failed = "unable to search for a Java 25+ runtime",
            .java_not_found = "unable to find a Java 25+ runtime. Put java, jre, or jdk in gdcc install directory, or add Java 25+ to PATH/JAVA_HOME.",
            .zig_search_failed = "unable to search for a Zig runtime",
            .zig_not_found = "unable to find Zig. Put zig or a zig directory in gdcc install directory, or add Zig to PATH/ZIG_HOME.",
        },
        .chinese => .{
            .launcher_prefix = "gdcc",
            .scan_launcher_dir_failed = "无法扫描gdcc安装目录",
            .jar_not_found = "无法在gdcc安装目录中找到 gdcc-*.*.*.jar",
            .multiple_jars_found = "gdcc安装目录中存在多个 gdcc-*.*.*.jar 文件",
            .multiple_update_jars_found = "gdcc安装目录中存在多个 gdcc-*.*.*-update.jar 文件",
            .delete_extra_jars = "请删除多余的匹配 jar 文件，只保留一个。",
            .java_search_failed = "无法搜索 Java 25+ 运行时",
            .java_not_found = "无法找到 Java 25+ 运行时。请将 java、jre 或 jdk 放在gdcc安装目录，或将 Java 25+ 加入 PATH/JAVA_HOME。",
            .zig_search_failed = "无法搜索 Zig 运行时",
            .zig_not_found = "无法找到 Zig。请将 zig 或 zig 目录放在gdcc安装目录，或将 Zig 加入 PATH/ZIG_HOME。",
        },
    };
}
