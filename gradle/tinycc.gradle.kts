// TinyCC CLI build orchestration for the test suite.
//
// The vendored sources (src/main/c/tinycc, including the local transparent u8"..." patch) are
// compiled into build/tinycc/<platformKey>/ by build-script/build-tinycc-cli-<platformKey>.sh|bat.
// Tests discover the CLI there via TinyCcCliTestSupport; `test` depends on `buildTinyccCli`, so a
// missing or stale CLI (e.g. after `clean`) is rebuilt before the suite starts. The task is keyed
// on the vendored source tree and the script's own outputs — including the `cli-verified` marker
// the scripts write only after their u8 patch probe passes — so an interrupted or half-finished
// build is always redone, and an up-to-date one is skipped.

/// The platform key segment used by the work tree and script names, or null when no tinycc CLI
/// build exists for this host (tests then skip via their assumptions).
fun tinyccHostPlatformKey(): String? {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    // Matches TargetPlatform.parseArchitecture's x86_64 spellings so Gradle and the test-side
    // discovery agree on the platform key.
    val isX64 = arch == "x86_64" || arch == "amd64" || arch == "x64"
    return when {
        os.contains("linux") && isX64 -> "linux-x86_64"
        os.contains("windows") && isX64 -> "windows-x86_64"
        else -> null
    }
}

/// The CLI build work tree for a platform key: `build/tinycc/<platformKey>/`.
fun tinyccCliWorkTree(platformKey: String) = layout.buildDirectory.dir("tinycc/$platformKey")

/// The CLI binary inside the work tree (`tcc` / `tcc.exe`).
fun tinyccCliBinaryName(platformKey: String) = if (platformKey.startsWith("windows")) "tcc.exe" else "tcc"

/// The platform build script: `build-script/build-tinycc-cli-<platformKey>.sh|bat`.
fun tinyccCliBuildScript(platformKey: String) =
    layout.projectDirectory.file("build-script/build-tinycc-cli-$platformKey." + (if (platformKey.startsWith("windows")) "bat" else "sh"))

val tinyccCliPlatformKey = tinyccHostPlatformKey()

val buildTinyccCli by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the vendored TinyCC CLI for the host platform (skipped when up-to-date or unsupported)."

    // Unsupported hosts skip quietly: tcc-dependent tests degrade to assumptions there.
    onlyIf { tinyccCliPlatformKey != null }

    if (tinyccCliPlatformKey != null) {
        val platformKey = tinyccCliPlatformKey!!
        val workTree = tinyccCliWorkTree(platformKey)

        // The whole vendored tree is the input: any change (including the local patch set)
        // forces a rebuild.
        inputs.dir(layout.projectDirectory.dir("src/main/c/tinycc"))
        inputs.files(tinyccCliBuildScript(platformKey))
        // The marker is written last by the script, after its u8 probe passes; a killed build
        // leaves binary/archive without it and is therefore never considered up-to-date.
        // include/tccdefs.h (and lib/kernel32.def on Windows) are declared because the tests'
        // `-B` runtime root contract needs them — deleting one must trigger a rebuild.
        outputs.files(
            workTree.map { it.file(tinyccCliBinaryName(platformKey)) },
            workTree.map { it.file("cli-verified") },
            workTree.map { it.file("include/tccdefs.h") },
            workTree.map { it.file(if (platformKey.startsWith("windows")) "lib/libtcc1.a" else "libtcc1.a") },
        )
        if (platformKey.startsWith("windows")) {
            outputs.file(workTree.map { it.file("lib/kernel32.def") })
        }

        doFirst {
            val script = tinyccCliBuildScript(platformKey).asFile
            commandLine(
                // cmd.exe re-parses everything after /c, so the script path needs explicit quotes.
                if (platformKey.startsWith("windows")) listOf("cmd.exe", "/c", "\"${script.absolutePath}\"")
                else listOf("bash", script.absolutePath)
            )
        }
    }
}

tasks.named("test") {
    dependsOn(buildTinyccCli)
}
