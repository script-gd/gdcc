import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Comparator
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

// Cross-builds all runtime bundles without depending on compiler classes or resources.
abstract class BuildTinyccBundle : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tinyccSource: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private data class Target(val key: String, val zigTarget: String, val cpu: String, val format: String) {
        val windows: Boolean get() = format == "PE"
        val macos: Boolean get() = format == "MACHO"
        val arm64: Boolean get() = cpu == "ARM64"
    }

    @TaskAction
    fun buildBundle() {
        val source = tinyccSource.get().asFile.toPath().toAbsolutePath()
        val output = outputDirectory.get().asFile.toPath().toAbsolutePath()
        val commit = Regex("Pinned commit: `([0-9a-f]{40})`")
            .find(Files.readString(source.resolve("GDCC_PIN.md")))?.groupValues?.get(1)
            ?: error("Missing TinyCC pin record")
        val version = Files.readString(source.resolve("VERSION")).trim()
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val hostWindows = os.contains("win")
        val hostCpu = if (arch == "aarch64" || arch == "arm64") "aarch64" else "x86_64"
        val hostTriple = hostCpu + when {
            hostWindows -> "-windows-gnu"
            os.contains("mac") -> "-macos"
            else -> "-linux-gnu"
        }
        val targets = listOf(
            Target("windows-x86_64", "x86_64-windows-gnu", "X86_64", "PE"),
            Target("linux-x86_64", "x86_64-linux-gnu.2.17", "X86_64", "ELF"),
            Target("linux-aarch64", "aarch64-linux-gnu.2.17", "ARM64", "ELF"),
            Target("macos-aarch64", "aarch64-macos.11.0", "ARM64", "MACHO"),
        )
        val workRoot = output.resolveSibling("tinycc-cross-work")
        Files.createDirectories(output.parent)
        val staging = Files.createTempDirectory(output.parent, ".tinycc-bundle-staging-")
        try {
            for (target in targets) {
                val work = workRoot.resolve(target.key)
                val bundle = staging.resolve(target.key)
                deleteTree(work)
                copyTree(source, work)
                Files.writeString(work.resolve("config.h"), configuration(target, version, true))
                if (target.windows) {
                    copyTree(source.resolve("win32/include"), work.resolve("include"))
                }
                val crossCompiler = work.resolve(if (hostWindows) "cross-tcc.exe" else "cross-tcc")
                val flags = mutableListOf("-DTCC_TARGET_${target.cpu}")
                if (target.format != "ELF") {
                    flags.add("-DTCC_TARGET_${target.format}")
                }
                run(
                    work, listOf("zig", "cc", "-target", hostTriple, "-fno-sanitize=undefined") +
                            flags + listOf("tcc.c", "-o", crossCompiler.toString())
                )
                Files.writeString(
                    work.resolve("abi-probe.c"), """
                    #include <stddef.h>
                    _Static_assert(sizeof(size_t) == 8, "size_t must match the 64-bit target ABI");
                    _Static_assert(sizeof(void *) == 8, "pointer size must match the target ABI");
                    _Static_assert(sizeof(long) == ${if (target.windows) 4 else 8}, "long must match LP64 or LLP64");
                """.trimIndent() + "\n"
                )
                run(
                    work, listOf(
                        crossCompiler.toString(),
                        "-B$work",
                        "-nostdinc",
                        "-std=c11",
                        "-I${work.resolve("include")}",
                        "-c",
                        "abi-probe.c",
                        "-o",
                        work.resolve("abi-probe.o").toString()
                    )
                )

                val runtime = mutableListOf(
                    if (target.arm64) "lib/lib-arm64.c" else "lib/libtcc1.c",
                    "lib/stdatomic.c", "lib/atomic.S", "lib/builtin.c", "lib/alloca.S", "lib/alloca-bt.S"
                )
                if (target.windows) {
                    runtime.addAll(
                        listOf(
                            "win32/lib/crt1.c", "win32/lib/crt1w.c", "win32/lib/wincrt1.c",
                            "win32/lib/wincrt1w.c", "win32/lib/dllcrt1.c", "win32/lib/dllmain.c",
                            "win32/lib/winex.c", "win32/lib/chkstk.S"
                        )
                    )
                } else if (!target.arm64) {
                    runtime.add("lib/va_list.c")
                }
                if (target.format == "ELF") {
                    runtime.add("lib/dsohandle.c")
                    if (target.arm64) {
                        runtime.add("lib/armflush.c")
                    }
                }
                val runtimeObjects = mutableListOf<String>()
                for (file in runtime) {
                    val name = Path.of(file).fileName.toString().replace(Regex("\\.[cS]$"), ".o")
                    val runtimeObject = work.resolve(name).toString()
                    run(work, listOf(crossCompiler.toString(), "-B$work", "-c", file, "-o", runtimeObject))
                    runtimeObjects.add(runtimeObject)
                }
                Files.createDirectories(bundle.resolve("bin"))
                val archive = bundle.resolve(if (target.windows) "lib/libtcc1.a" else "libtcc1.a")
                Files.createDirectories(archive.parent)
                run(work, listOf(crossCompiler.toString(), "-ar", archive.toString()) + runtimeObjects)

                Files.writeString(work.resolve("config.h"), configuration(target, version, false))
                val libraryName = when {
                    target.windows -> "libtcc.dll"
                    target.macos -> "libtcc.dylib"
                    else -> "libtcc.so"
                }
                val library = work.resolve(libraryName)
                val compile = mutableListOf(
                    "zig", "cc", "-target", target.zigTarget,
                    "-fno-sanitize=undefined", "-shared", "-fPIC"
                )
                compile.addAll(flags)
                when {
                    target.windows -> compile.add("-DLIBTCC_AS_DLL")
                    // Zig sets MH_TWOLEVEL by default and rejects -twolevel_namespace.
                    target.macos -> compile.add("-Wl,-install_name,@rpath/libtcc.dylib")
                    else -> compile.addAll(listOf("-ldl", "-lpthread", "-lm"))
                }
                compile.addAll(listOf("libtcc.c", "-o", library.toString()))
                run(work, compile)
                Files.copy(library, bundle.resolve("bin").resolve(libraryName))
                copyTree(source.resolve("include"), bundle.resolve("include"))
                Files.copy(source.resolve("tcclib.h"), bundle.resolve("include/tcclib.h"))
                if (target.windows) {
                    copyTree(source.resolve("win32/include"), bundle.resolve("include"))
                    Files.list(source.resolve("win32/lib")).use { definitions ->
                        for (file in definitions.filter { it.toString().endsWith(".def") }.toList()) {
                            Files.copy(
                                file,
                                bundle.resolve("lib").resolve(file.fileName),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        }
                    }
                }
                Files.copy(source.resolve("COPYING"), bundle.resolve("COPYING"))
                Files.copy(source.resolve("RELICENSING"), bundle.resolve("RELICENSING"))
                Files.writeString(
                    bundle.resolve("VERSION"),
                    "bundleFormat=1\ntinyccCommit=$commit\nplatform=${target.key}\n"
                )
                val manifest = Files.walk(bundle).use { files ->
                    files.filter { Files.isRegularFile(it) }.map { file ->
                        sha256(file) + "  " + bundle.relativize(file).toString().replace('\\', '/')
                    }.toList().sorted()
                }
                Files.write(bundle.resolve("MANIFEST"), manifest)
                logger.lifecycle("Built TinyCC bundle: ${target.key}")
            }
            publishDirectory(staging, output)
        } finally {
            deleteTree(staging)
        }
    }

    private fun configuration(target: Target, version: String, bootstrap: Boolean): String {
        var configuration = "#define TCC_VERSION \"$version\"\n" +
                "#define CONFIG_TCC_BCHECK 0\n#define CONFIG_TCC_BACKTRACE 0\n"
        if (target.format == "ELF") {
            val triplet = if (target.arm64) "aarch64-linux-gnu" else "x86_64-linux-gnu"
            configuration += "#define CONFIG_TRIPLET \"$triplet\"\n"
        } else if (target.macos) {
            val sdk = "/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk/usr"
            if (!bootstrap) {
                // Only the native macOS library signs final Mach-O outputs, never the host bootstrap.
                configuration += "#include <sys/wait.h>\n#define CONFIG_CODESIGN 1\n"
            }
            configuration += "#define CONFIG_TCC_SYSINCLUDEPATHS \"{B}/include\" PATHSEP \"$sdk/include\"\n" +
                    "#define CONFIG_TCC_LIBPATHS \"{B}\" PATHSEP \"$sdk/lib\" PATHSEP \"/usr/lib\"\n" +
                    "#define CONFIG_TCC_CRTPREFIX \"$sdk/lib\"\n"
        }
        return configuration
    }

    private fun run(directory: Path, command: List<String>) {
        logger.lifecycle(command.joinToString(" "))
        val builder = ProcessBuilder(command).directory(directory.toFile()).inheritIO()
        for (variable in listOf("CPATH", "C_INCLUDE_PATH", "CPLUS_INCLUDE_PATH", "LIBRARY_PATH")) {
            builder.environment().remove(variable)
        }
        val process = builder.start()
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            process.waitFor()
            error("Timed out: $command")
        }
        check(process.exitValue() == 0) { "Command failed: $command" }
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            for (path in paths.toList()) {
                val destination = target.resolve(source.relativize(path))
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) {
            return
        }
        Files.walk(root).use { paths ->
            for (path in paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path)
            }
        }
    }

    private fun sha256(file: Path): String {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
        } catch (exception: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is required", exception)
        }
    }

    // Replaces a completed artifact tree, restoring the old tree if publication fails.
    private fun publishDirectory(staging: Path, target: Path) {
        if (!Files.isDirectory(staging)) {
            throw IOException("Missing completed staging directory: $staging")
        }
        val backup = target.resolveSibling(".${target.fileName}-backup-${UUID.randomUUID()}")
        val hadTarget = Files.exists(target)
        if (hadTarget) {
            moveDirectory(target, backup)
        }
        try {
            moveDirectory(staging, target)
        } catch (exception: IOException) {
            if (hadTarget) {
                try {
                    moveDirectory(backup, target)
                } catch (rollbackFailure: IOException) {
                    exception.addSuppressed(rollbackFailure)
                }
            }
            throw exception
        }
        deleteTree(backup)
    }

    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }
}

val buildTinyccBundle by tasks.registering(BuildTinyccBundle::class) {
    group = "build"
    description = "Cross-builds all four TinyCC runtime bundles with Zig for packaging in the jar."
    tinyccSource.set(layout.projectDirectory.dir("src/main/c/tinycc"))
    outputDirectory.set(layout.buildDirectory.dir("tinycc-bundle"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildTinyccBundle)
    from(buildTinyccBundle.flatMap { it.outputDirectory }) {
        into("gdcc/tinycc-bundle")
    }
}
