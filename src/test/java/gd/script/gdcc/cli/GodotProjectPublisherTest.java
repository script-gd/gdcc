package gd.script.gdcc.cli;

import gd.script.gdcc.api.CompileOptions;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.GdccPublishException;
import gd.script.gdcc.exception.GodotConfigException;
import gd.script.gdcc.exception.GodotProjectException;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GodotProjectPublisherTest {
    @TempDir
    Path tempDir;

    private static final @NotNull String DESCRIPTOR = GodotProjectPublisher.DESCRIPTOR_FILE_NAME;

    // ---------------------------------------------------------------- helpers

    private @NotNull GodotProjectInput newProject(@NotNull String name) throws IOException {
        // The project name may hold filename-hostile characters that NTFS rejects outright
        // (`:`, `?`, `<`, `|`, ...); they belong in `config/name`, not in the directory.
        var dirName = "project-" + name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_");
        var projectDir = Files.createDirectories(tempDir.resolve(dirName));
        var escapedName = name.replace("\\", "\\\\").replace("\"", "\\\"");
        Files.writeString(
                projectDir.resolve(GodotProjectInput.PROJECT_FILE_NAME),
                "[application]\n\nconfig/name=\"" + escapedName + "\"\n"
        );
        return GodotProjectInput.load(projectDir.resolve(GodotProjectInput.PROJECT_FILE_NAME));
    }

    private static @NotNull CompileOptions options(
            @NotNull COptimizationLevel level,
            @NotNull TargetPlatform target
    ) {
        return new CompileOptions(
                GodotVersion.V451, null, level, target, false, CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT);
    }

    private @NotNull Path writeArtifact(@NotNull String name, @NotNull String content) throws IOException {
        var artifact = tempDir.resolve(name);
        Files.writeString(artifact, content);
        return artifact;
    }

    private static @NotNull CompileResult successResult(
            @NotNull CompileOptions options,
            @NotNull List<Path> artifacts
    ) {
        return new CompileResult(
                CompileResult.Outcome.SUCCESS,
                options,
                Map.of(),
                List.of(),
                new DiagnosticSnapshot(List.of()),
                null,
                "log",
                List.of(),
                artifacts,
                List.of()
        );
    }

    private @NotNull Path writeDescriptor(@NotNull Path projectRoot, @NotNull String text) throws IOException {
        var descriptor = Files.createDirectories(projectRoot.resolve("bin")).resolve(DESCRIPTOR);
        Files.writeString(descriptor, text);
        return descriptor;
    }

    private static @NotNull GdccExtensionDescriptor readDescriptor(@NotNull Path descriptor) throws IOException {
        return GdccExtensionDescriptor.fromConfig(
                GodotConfigFile.parse(Files.readString(descriptor), descriptor.toString()));
    }

    private static void assertString(
            @NotNull GdccExtensionDescriptor descriptor,
            @NotNull String key,
            @NotNull String expected
    ) {
        var value = descriptor.get("configuration", key);
        var string = assertInstanceOf(GodotConfigFile.StringValue.class, value);
        assertEquals(expected, string.text());
    }

    private static void assertBool(
            @NotNull GdccExtensionDescriptor descriptor,
            @NotNull String key,
            boolean expected
    ) {
        var value = descriptor.get("configuration", key);
        var bool = assertInstanceOf(GodotConfigFile.BoolValue.class, value);
        assertEquals(expected, bool.flag());
    }

    private static @NotNull String libraryMapping(@NotNull GdccExtensionDescriptor descriptor, @NotNull String key) {
        var value = descriptor.get("libraries", key);
        var string = assertInstanceOf(GodotConfigFile.StringValue.class, value);
        return string.text();
    }

    private static @NotNull List<String> ownedLibraries(@NotNull GdccExtensionDescriptor descriptor) {
        var value = descriptor.get("configuration", "gdcc_libraries");
        var array = assertInstanceOf(GodotConfigFile.StringArrayValue.class, value);
        return array.items();
    }

    /// A minimal descriptor that already satisfies the addon-managed contract.
    private static @NotNull String managedDescriptor(@NotNull String extraSections) {
        return """
                [configuration]

                entry_symbol="gdextension_entry"
                compatibility_minimum="4.5"
                reloadable=true
                gdcc_managed=true
                %s
                """.formatted(extraSections);
    }

    /// Godot hex-digests lowercase (`FileAccess.get_sha256`), and so do published library names.
    private static @NotNull String hashPrefix(@NotNull String content) {
        return StringUtil.toHex(StringUtil.sha256(content.getBytes(StandardCharsets.UTF_8)), 8)
                .toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------- new install

    @Test
    void newInstallPublishesHashedLibraryAndManagedDescriptor() throws IOException {
        var project = newProject("TestGame");
        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        var artifact = writeArtifact("libout.so", "dummy-bytes");

        var plan = GodotProjectPublisher.prepare(project, compileOptions);
        plan.publish(successResult(compileOptions, List.of(artifact)));

        var binDir = project.projectRoot().resolve("bin");
        var expectedHash = hashPrefix("dummy-bytes");
        var libName = "libTestGame_linux_debug_x86_64-" + expectedHash + ".so";
        assertTrue(Files.isRegularFile(binDir.resolve(libName)));
        assertEquals("dummy-bytes", Files.readString(binDir.resolve(libName)));

        var descriptor = readDescriptor(binDir.resolve(DESCRIPTOR));
        assertString(descriptor, "entry_symbol", "gdextension_entry");
        assertString(descriptor, "compatibility_minimum", "4.5");
        assertBool(descriptor, "reloadable", true);
        assertBool(descriptor, "gdcc_managed", true);
        assertEquals("res://bin/" + libName, libraryMapping(descriptor, "linux.x86_64"));
        assertEquals(List.of("res://bin/" + libName), ownedLibraries(descriptor));
    }

    @Test
    void windowsAndArmTargetsFollowAddonNamingAndKeys() throws IOException {
        var project = newProject("Game");
        var binDir = project.projectRoot().resolve("bin");

        var windowsOptions = options(COptimizationLevel.RELEASE, TargetPlatform.WINDOWS_AARCH64);
        GodotProjectPublisher.prepare(project, windowsOptions)
                .publish(successResult(windowsOptions, List.of(writeArtifact("out.dll", "w"))));
        var descriptor = readDescriptor(binDir.resolve(DESCRIPTOR));
        var windowsLib = "Game_windows_release_arm64-" + hashPrefix("w") + ".dll";
        assertTrue(Files.isRegularFile(binDir.resolve(windowsLib)));
        assertEquals("res://bin/" + windowsLib, libraryMapping(descriptor, "windows.arm64"));

        var webOptions = options(COptimizationLevel.DEBUG, TargetPlatform.WEB_WASM32);
        GodotProjectPublisher.prepare(project, webOptions)
                .publish(successResult(webOptions, List.of(writeArtifact("out.wasm", "e"))));
        descriptor = readDescriptor(binDir.resolve(DESCRIPTOR));
        var webLib = "Game_web_debug_wasm32-" + hashPrefix("e") + ".wasm";
        assertTrue(Files.isRegularFile(binDir.resolve(webLib)));
        assertEquals("res://bin/" + webLib, libraryMapping(descriptor, "web.wasm32"));
        assertEquals("res://bin/" + windowsLib, libraryMapping(descriptor, "windows.arm64"));
    }

    /// Godot `validate_filename` replaces `:/\?*"|%<>` with `_` but keeps spaces — the library
    /// name must mirror exactly what the addon would produce for the same project name.
    @Test
    void projectNameIsSanitizedLikeGodotValidateFilename() throws IOException {
        var project = newProject("My Game: X?%<|>");
        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);

        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "x"))));

        var binDir = project.projectRoot().resolve("bin");
        // Six underscores: five sanitized characters plus the `<name>_<platform>` separator.
        var libName = "libMy Game_ X______linux_debug_x86_64-" + hashPrefix("x") + ".so";
        assertTrue(Files.isRegularFile(binDir.resolve(libName)), "expected " + libName);
    }

    // ------------------------------------------------------- managed updates

    @Test
    void updateKeepsOtherTargetsAndUnrelatedContent() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                gdcc_libraries=PackedStringArray("res://bin/libGame_windows_release_x86_64-aaaa0000bbbb1111.so")

                [dependencies]

                "res://other.gdextension"=""

                [libraries]

                windows.x86_64="res://bin/libGame_windows_release_x86_64-aaaa0000bbbb1111.so"
                macos="res://bin/libother.dylib"
                custom_tag="res://bin/libcustom.so"
                """));
        // The owned library still exists on disk, so it stays in the ownership list.
        Files.writeString(
                project.projectRoot().resolve("bin/libGame_windows_release_x86_64-aaaa0000bbbb1111.so"),
                "w"
        );

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))));

        var descriptor = readDescriptor(descriptorPath);
        var linuxLib = libraryMapping(descriptor, "linux.x86_64");
        assertTrue(linuxLib.startsWith("res://bin/libGame_linux_debug_x86_64-"));
        assertEquals(
                "res://bin/libGame_windows_release_x86_64-aaaa0000bbbb1111.so",
                libraryMapping(descriptor, "windows.x86_64")
        );
        // Single-tag and unrecognized keys cannot shadow the two-tag canonical entries — kept.
        assertEquals("res://bin/libother.dylib", libraryMapping(descriptor, "macos"));
        assertEquals("res://bin/libcustom.so", libraryMapping(descriptor, "custom_tag"));
        // The unrelated [dependencies] section survives verbatim.
        var dep = descriptor.get("dependencies", "res://other.gdextension");
        assertEquals("\"\"", dep.raw());
        // Ownership list keeps the still-present windows library and gains the new one.
        var owned = ownedLibraries(descriptor);
        assertTrue(owned.contains("res://bin/libGame_windows_release_x86_64-aaaa0000bbbb1111.so"));
        assertTrue(owned.contains(linuxLib));
    }

    @Test
    void debugAndReleaseReplaceTheSameCanonicalTarget() throws IOException {
        var project = newProject("Game");
        var binDir = project.projectRoot().resolve("bin");

        var debugOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, debugOptions)
                .publish(successResult(debugOptions, List.of(writeArtifact("d.so", "dbg"))));
        var descriptor = readDescriptor(binDir.resolve(DESCRIPTOR));
        var debugLib = libraryMapping(descriptor, "linux.x86_64");
        assertTrue(debugLib.contains("linux_debug_x86_64-" + hashPrefix("dbg")));

        var releaseOptions = options(COptimizationLevel.RELEASE, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, releaseOptions)
                .publish(successResult(releaseOptions, List.of(writeArtifact("r.so", "rel"))));
        descriptor = readDescriptor(binDir.resolve(DESCRIPTOR));
        var releaseLib = libraryMapping(descriptor, "linux.x86_64");
        assertTrue(releaseLib.contains("linux_release_x86_64-" + hashPrefix("rel")));

        // Old libraries are never deleted by publishing; ownership still lists both.
        var binFiles = Files.list(binDir).map(p -> p.getFileName().toString()).toList();
        assertEquals(2, binFiles.stream().filter(n -> n.endsWith(".so")).count());
        var owned = ownedLibraries(descriptor);
        assertTrue(owned.contains(debugLib));
        assertTrue(owned.contains(releaseLib));
    }

    @Test
    void identicalContentReusesTheSameLibraryName() throws IOException {
        var project = newProject("Game");
        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);

        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("a.so", "same"))));
        var binDir = project.projectRoot().resolve("bin");
        var descriptor = readDescriptor(binDir.resolve(DESCRIPTOR));
        var firstLib = libraryMapping(descriptor, "linux.x86_64");

        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("b.so", "same"))));
        descriptor = readDescriptor(binDir.resolve(DESCRIPTOR));
        assertEquals(firstLib, libraryMapping(descriptor, "linux.x86_64"));
        assertEquals(1, Files.list(binDir).filter(p -> p.getFileName().toString().endsWith(".so")).count());
    }

    // -------------------------------------------------------- legacy migration

    @Test
    void legacyModeKeysMigrateToCanonicalKeys() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                [libraries]

                windows.debug.x86_64="res://bin/libGame_windows_debug_x86_64-old.dll"
                macos.debug.arm64="res://bin/libGame_macos_debug_arm64-m1.dylib"
                macos.release.arm64="res://bin/libGame_macos_debug_arm64-m1.dylib"
                """));

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))));

        var descriptor = readDescriptor(descriptorPath);
        // Single-mode legacy migrates; two modes with the same library migrate too.
        assertEquals(
                "res://bin/libGame_windows_debug_x86_64-old.dll",
                libraryMapping(descriptor, "windows.x86_64")
        );
        assertEquals(
                "res://bin/libGame_macos_debug_arm64-m1.dylib",
                libraryMapping(descriptor, "macos.arm64")
        );
        assertNull(descriptor.get("libraries", "windows.debug.x86_64"));
        assertNull(descriptor.get("libraries", "macos.debug.arm64"));
        assertNull(descriptor.get("libraries", "macos.release.arm64"));
        assertTrue(libraryMapping(descriptor, "linux.x86_64").startsWith("res://bin/libGame_linux_debug_"));
    }

    @Test
    void legacyKeysLoseToAnExistingCanonicalEntry() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                [libraries]

                macos.arm64="res://bin/libGame_macos_release_arm64-new.dylib"
                macos.debug.arm64="res://bin/libGame_macos_debug_arm64-old.dylib"
                """));

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))));

        var descriptor = readDescriptor(descriptorPath);
        assertEquals(
                "res://bin/libGame_macos_release_arm64-new.dylib",
                libraryMapping(descriptor, "macos.arm64")
        );
        assertNull(descriptor.get("libraries", "macos.debug.arm64"));
    }

    /// Godot escapes inside strings decode before comparison: `g\u0061me` and `game` name the
    /// same library, so two legacy modes written that way are NOT an ambiguity.
    @Test
    void legacyModesWithEquivalentEscapedPathsAreNotAmbiguous() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                [libraries]

                macos.debug.arm64="res://bin/libGame_macos_debug_arm64-old.dylib"
                macos.release.arm64="res://bin/libGame_macos_debug_arm64-old.dylib"
                """));
        // Rewrite one side with a unicode-escaped char to prove decode-level comparison.
        var text = Files.readString(descriptorPath);
        Files.writeString(descriptorPath,
                text.replace("macos.release.arm64=\"res://bin/libGame", "macos.release.arm64=\"res://bin/libG\\u0061me"));

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))));

        var descriptor = readDescriptor(descriptorPath);
        assertEquals(
                "res://bin/libGame_macos_debug_arm64-old.dylib",
                libraryMapping(descriptor, "macos.arm64")
        );
        assertNull(descriptor.get("libraries", "macos.debug.arm64"));
        assertNull(descriptor.get("libraries", "macos.release.arm64"));
    }

    /// Both legacy modes exist but disagree; without an editor-mode context there is no safe
    /// choice, so the whole publish is refused before any file is touched.
    @Test
    void ambiguousLegacyModesAreRejectedBeforeModuleCreation() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                [libraries]

                macos.debug.arm64="res://bin/debug.dylib"
                macos.release.arm64="res://bin/release.dylib"
                """));
        var originalText = Files.readString(descriptorPath);

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        var exception = assertThrows(
                GodotProjectException.class,
                () -> GodotProjectPublisher.prepare(project, compileOptions)
        );

        assertTrue(exception.getMessage().contains("ambiguous"));
        assertEquals(originalText, Files.readString(descriptorPath));
    }

    @Test
    void alternateCanonicalSpellingsAreNormalized() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                [libraries]

                x86_64.windows="res://bin/Game_windows_release_x86_64-w.dll"
                """));

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))));

        var descriptor = readDescriptor(descriptorPath);
        assertEquals("res://bin/Game_windows_release_x86_64-w.dll", libraryMapping(descriptor, "windows.x86_64"));
        assertNull(descriptor.get("libraries", "x86_64.windows"));
    }

    @Test
    void conflictingSpellingsOfOneTargetAreRejected() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                [libraries]

                windows.x86_64="res://bin/a.dll"
                x86_64.windows="res://bin/b.dll"
                """));
        var originalText = Files.readString(descriptorPath);

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        assertThrows(
                GodotProjectException.class,
                () -> GodotProjectPublisher.prepare(project, compileOptions)
        );
        assertEquals(originalText, Files.readString(descriptorPath));
    }

    // ------------------------------------------------------------- rejections

    @Test
    void unmanagedDescriptorsAreRejectedWithoutTouchingThem() throws IOException {
        for (var text : List.of(
                "[configuration]\n\nentry_symbol=\"gdextension_entry\"\n",
                managedDescriptor("").replace("gdcc_managed=true\n", ""),
                managedDescriptor("").replace("gdcc_managed=true", "gdcc_managed=false"),
                managedDescriptor("").replace("gdcc_managed=true", "gdcc_managed=\"true\""),
                managedDescriptor("").replace("gdcc_managed=true", "gdcc_managed=1")
        )) {
            var project = newProject("Game");
            var descriptorPath = writeDescriptor(project.projectRoot(), text);
            var original = Files.readString(descriptorPath);

            assertThrows(
                    GodotProjectException.class,
                    () -> GodotProjectPublisher.prepare(
                            project, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
            );
            assertEquals(original, Files.readString(descriptorPath));

            // Recreate the fixture dir for the next candidate.
            deleteRecursively(project.projectRoot());
        }
    }

    @Test
    void malformedDescriptorIsRejected() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), "[configuration\n\ngdcc_managed=true\n");
        var original = Files.readString(descriptorPath);

        assertThrows(
                GodotConfigException.class,
                () -> GodotProjectPublisher.prepare(
                        project, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
        );
        assertEquals(original, Files.readString(descriptorPath));
    }

    @Test
    void ownershipFieldsWithWrongTypesAreRejected() throws IOException {
        for (var brokenLine : List.of("gdcc_libraries=42", "gdcc_libraries=\"res://bin/x.so\"")) {
            var project = newProject("Game");
            var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor(brokenLine));
            var original = Files.readString(descriptorPath);

            assertThrows(
                    GodotProjectException.class,
                    () -> GodotProjectPublisher.prepare(
                            project, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
            );
            assertEquals(original, Files.readString(descriptorPath));
            deleteRecursively(project.projectRoot());
        }
    }

    /// A `[libraries]` entry GDCC carries forward or merges must hold a string path — anything
    /// else is broken managed metadata, not an unrelated field to preserve. That applies even to
    /// entries the merge would replace or discard: the current target's own key, and a legacy
    /// mode key shadowed by an existing canonical mapping.
    @Test
    void nonStringLibraryEntriesAreRejected() throws IOException {
        for (var librariesSection : List.of(
                "windows.x86_64=42",
                "x86_64.windows=[1,2]",
                "linux.x86_64=42",
                """
                windows.x86_64="res://bin/w.dll"
                windows.debug.x86_64=42
                """,
                """
                windows.debug.x86_64="res://bin/wd.dll"
                windows.release.x86_64=42
                """)) {
            var project = newProject("Game");
            var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                    [libraries]

                    %s
                    """.formatted(librariesSection.strip())));
            var original = Files.readString(descriptorPath);
            assertThrows(
                    GodotProjectException.class,
                    () -> GodotProjectPublisher.prepare(
                            project, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
            );
            assertEquals(original, Files.readString(descriptorPath));
            deleteRecursively(project.projectRoot());
        }
    }

    /// Managed mappings must resolve inside `bin/`: a foreign-platform entry pointing elsewhere
    /// in the project, one escaping through `..`, or one passing through a symlinked component
    /// are all broken managed metadata.
    @Test
    void libraryMappingsOutsideBinAreRejected() throws IOException {
        var project = newProject("Game");
        var binDir = Files.createDirectories(project.projectRoot().resolve("bin"));
        var outsideDir = Files.createDirectory(project.projectRoot().resolve("outside"));
        Files.writeString(outsideDir.resolve("library.dll"), "x");
        Files.createSymbolicLink(binDir.resolve("redirect"), outsideDir);

        for (var badPath : List.of(
                "res://libs/w.dll",
                "res://bin/../outside/library.dll",
                "res://bin/redirect/library.dll")) {
            var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                    [libraries]

                    windows.x86_64="%s"
                    """.formatted(badPath)));
            var original = Files.readString(descriptorPath);
            assertThrows(
                    GodotProjectException.class,
                    () -> GodotProjectPublisher.prepare(
                            project, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64)),
                    "expected rejection for " + badPath
            );
            assertEquals(original, Files.readString(descriptorPath));
        }
    }

    /// Carried mappings are revalidated at commit time: a `bin` subdirectory turned into a
    /// symlink during compilation must not let a prepared mapping escape `bin`.
    @Test
    void carriedMappingIsRevalidatedAtCommit() throws IOException {
        var project = newProject("Game");
        var binDir = Files.createDirectories(project.projectRoot().resolve("bin"));
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                [libraries]

                windows.x86_64="res://bin/sub/library.dll"
                """));
        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        var plan = GodotProjectPublisher.prepare(project, compileOptions);

        // A foreign-platform library may legitimately be absent — but if the directory that
        // would hold it becomes a symlink during compilation, committing the mapping is unsafe.
        var outside = Files.createDirectory(project.projectRoot().resolve("outside"));
        Files.createSymbolicLink(binDir.resolve("sub"), outside);

        var original = Files.readString(descriptorPath);
        assertThrows(
                GdccPublishException.class,
                () -> plan.publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))))
        );
        assertEquals(original, Files.readString(descriptorPath));
    }

    /// An ownership entry resolving through a symlinked directory is dropped from the published
    /// `gdcc_libraries` — only real regular files inside bin keep their ownership.
    @Test
    void ownedLibraryBehindSymlinkedComponentIsDropped() throws IOException {
        var project = newProject("Game");
        var binDir = Files.createDirectories(project.projectRoot().resolve("bin"));
        var outsideDir = Files.createDirectory(project.projectRoot().resolve("outside"));
        Files.writeString(outsideDir.resolve("library.dll"), "x");
        Files.createSymbolicLink(binDir.resolve("redirect"), outsideDir);
        var kept = "res://bin/libGame_windows_debug_x86_64-0123456789abcdef.dll";
        Files.writeString(binDir.resolve("libGame_windows_debug_x86_64-0123456789abcdef.dll"), "w");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                gdcc_libraries=PackedStringArray("%s", "res://bin/redirect/library.dll")
                """.formatted(kept)));

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))));

        var descriptor = readDescriptor(descriptorPath);
        assertEquals(List.of(kept), ownedLibraries(descriptor).stream()
                .filter(item -> !item.contains("linux_debug")).toList());
        assertTrue(ownedLibraries(descriptor).contains(kept));
    }

    @Test
    void libraryAliasesAreTypeCheckedThenRemoved() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                gdcc_library_aliases=PackedStringArray("res://bin/old_alias.so")
                """));

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))));

        var descriptor = readDescriptor(descriptorPath);
        assertNull(descriptor.get("configuration", "gdcc_library_aliases"));

        // A wrongly-typed aliases field is rejected before anything is written.
        var project2 = newProject("Game2");
        var descriptorPath2 = writeDescriptor(project2.projectRoot(),
                managedDescriptor("gdcc_library_aliases=\"res://bin/x.so\""));
        var original2 = Files.readString(descriptorPath2);
        assertThrows(
                GodotProjectException.class,
                () -> GodotProjectPublisher.prepare(
                        project2, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
        );
        assertEquals(original2, Files.readString(descriptorPath2));
    }

    /// An injected key that can match the managed target under Godot's most-tags-win rule must be
    /// refused; a key carrying a foreign platform/architecture can never shadow, so it stays.
    @Test
    void injectedShadowingKeysAreRejectedForeignTaggedKeysKept() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor("""
                [libraries]

                linux.x86_64.steamdeck="res://bin/evil.so"
                """));
        var original = Files.readString(descriptorPath);
        assertThrows(
                GodotProjectException.class,
                () -> GodotProjectPublisher.prepare(
                        project, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
        );
        assertEquals(original, Files.readString(descriptorPath));

        deleteRecursively(project.projectRoot());
        var project2 = newProject("Game");
        descriptorPath = writeDescriptor(project2.projectRoot(), managedDescriptor("""
                [libraries]

                linux.arm64.steamdeck="res://bin/other.so"
                """));
        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project2, compileOptions)
                .publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))));
        var descriptor = readDescriptor(descriptorPath);
        assertEquals("res://bin/other.so", libraryMapping(descriptor, "linux.arm64.steamdeck"));
    }

    /// A same-named library file whose bytes differ from what this build produced is a hash
    /// collision on disk — the publish must fail without overwriting it or the descriptor.
    @Test
    void hashNamedLibraryCollisionFailsWithoutReplacing() throws IOException {
        var project = newProject("Game");
        var binDir = Files.createDirectories(project.projectRoot().resolve("bin"));
        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        var collidingName = "libGame_linux_debug_x86_64-" + hashPrefix("new") + ".so";
        Files.writeString(binDir.resolve(collidingName), "attacker bytes");

        var plan = GodotProjectPublisher.prepare(project, compileOptions);
        assertThrows(
                GdccPublishException.class,
                () -> plan.publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "new"))))
        );

        assertEquals("attacker bytes", Files.readString(binDir.resolve(collidingName)));
        assertFalse(Files.exists(binDir.resolve(DESCRIPTOR)));
    }

    /// A symlink at the would-be library path must not be adopted even when its target holds the
    /// same bytes — the installation would silently depend on a file outside bin/.
    @Test
    void symlinkedHashNamedLibraryIsRejectedNotAdopted() throws IOException {
        var project = newProject("Game");
        var binDir = Files.createDirectories(project.projectRoot().resolve("bin"));
        var outsideLib = tempDir.resolve("outside.so");
        Files.writeString(outsideLib, "new");
        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        var linkName = "libGame_linux_debug_x86_64-" + hashPrefix("new") + ".so";
        Files.createSymbolicLink(binDir.resolve(linkName), outsideLib);

        var plan = GodotProjectPublisher.prepare(project, compileOptions);
        assertThrows(
                GdccPublishException.class,
                () -> plan.publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "new"))))
        );
        assertFalse(Files.exists(binDir.resolve(DESCRIPTOR)));
    }

    @Test
    void binAndDescriptorPathsMustNotEscapeThroughLinks() throws IOException {
        // bin as a symlink
        var linkedProject = newProject("LinkedBin");
        var outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.createSymbolicLink(linkedProject.projectRoot().resolve("bin"), outside);
        assertThrows(
                GodotProjectException.class,
                () -> GodotProjectPublisher.prepare(
                        linkedProject, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
        );

        // bin as a regular file
        var fileProject = newProject("FileBin");
        Files.writeString(fileProject.projectRoot().resolve("bin"), "not a dir");
        assertThrows(
                GodotProjectException.class,
                () -> GodotProjectPublisher.prepare(
                        fileProject, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
        );

        // descriptor as a symlink out of bin
        var linkProject = newProject("LinkedDescriptor");
        var binDir = Files.createDirectories(linkProject.projectRoot().resolve("bin"));
        var outsideDescriptor = tempDir.resolve("outside-descriptor");
        Files.writeString(outsideDescriptor, managedDescriptor(""));
        Files.createSymbolicLink(binDir.resolve(DESCRIPTOR), outsideDescriptor);
        assertThrows(
                GodotProjectException.class,
                () -> GodotProjectPublisher.prepare(
                        linkProject, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
        );
        assertEquals(managedDescriptor(""), Files.readString(outsideDescriptor));

        // descriptor as a directory
        var dirProject = newProject("DirDescriptor");
        Files.createDirectories(dirProject.projectRoot().resolve("bin").resolve(DESCRIPTOR));
        assertThrows(
                GodotProjectException.class,
                () -> GodotProjectPublisher.prepare(
                        dirProject, options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64))
        );
    }

    // ------------------------------------------------------- commit integrity

    /// The snapshot taken at prepare time guards the commit: a descriptor mutated by an outside
    /// writer while compiling is detected and the publish aborts, leaving that writer's file as-is.
    @Test
    void externalDescriptorMutationBetweenPrepareAndPublishAbortsCommit() throws IOException {
        var project = newProject("Game");
        var descriptorPath = writeDescriptor(project.projectRoot(), managedDescriptor(""));

        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        var plan = GodotProjectPublisher.prepare(project, compileOptions);
        Files.writeString(descriptorPath, managedDescriptor("custom_key=1\n"));

        assertThrows(
                GdccPublishException.class,
                () -> plan.publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))))
        );
        assertEquals(managedDescriptor("custom_key=1\n"), Files.readString(descriptorPath));
    }

    /// A failed publish may leave an unreferenced staged library behind, but the previously
    /// installed descriptor and the library it references must stay byte-identical.
    @Test
    void failedPublishKeepsThePriorInstallationIntact() throws IOException {
        var project = newProject("Game");
        var binDir = project.projectRoot().resolve("bin");

        var firstOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        GodotProjectPublisher.prepare(project, firstOptions)
                .publish(successResult(firstOptions, List.of(writeArtifact("libout.so", "v1"))));
        var descriptorPath = binDir.resolve(DESCRIPTOR);
        var installedDescriptor = Files.readString(descriptorPath);
        var installedLib = libraryMapping(readDescriptor(descriptorPath), "linux.x86_64");

        // Sabotage the second commit by mutating the descriptor after prepare.
        var secondOptions = options(COptimizationLevel.RELEASE, TargetPlatform.LINUX_X86_64);
        var plan = GodotProjectPublisher.prepare(project, secondOptions);
        Files.writeString(descriptorPath, managedDescriptor("intruder=1\n"));
        assertThrows(
                GdccPublishException.class,
                () -> plan.publish(successResult(secondOptions, List.of(writeArtifact("lib2.so", "v2"))))
        );

        assertEquals(managedDescriptor("intruder=1\n"), Files.readString(descriptorPath));
        assertTrue(Files.exists(binDir.resolve(installedLib.substring("res://bin/".length()))));
        assertEquals("v1", Files.readString(binDir.resolve(installedLib.substring("res://bin/".length()))));
        assertTrue(installedDescriptor.contains("linux.x86_64"));
    }

    @Test
    void missingOrMultipleLibraryArtifactsAreRejected() throws IOException {
        var project = newProject("Game");
        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);

        var plan = GodotProjectPublisher.prepare(project, compileOptions);
        assertThrows(
                GdccPublishException.class,
                () -> plan.publish(successResult(compileOptions, List.of()))
        );

        var first = writeArtifact("a.so", "1");
        var second = writeArtifact("b.so", "2");
        var plan2 = GodotProjectPublisher.prepare(project, compileOptions);
        assertThrows(
                GdccPublishException.class,
                () -> plan2.publish(successResult(compileOptions, List.of(first, second)))
        );
        assertFalse(Files.exists(project.projectRoot().resolve("bin").resolve(DESCRIPTOR)));

        // A sibling debug database (Windows-style .pdb) next to the library is fine.
        var dll = writeArtifact("a.dll", "1");
        var pdb = writeArtifact("a.pdb", "pdb");
        var windowsOptions = options(COptimizationLevel.DEBUG, TargetPlatform.WINDOWS_X86_64);
        var project3 = newProject("Game3");
        GodotProjectPublisher.prepare(project3, windowsOptions)
                .publish(successResult(windowsOptions, List.of(dll, pdb)));
        var descriptor3 = readDescriptor(project3.projectRoot().resolve("bin").resolve(DESCRIPTOR));
        assertTrue(libraryMapping(descriptor3, "windows.x86_64").endsWith(".dll"));
    }

    /// The pre-commit interruption check: a publish on an interrupted thread must fail before
    /// the atomic replace, leaving neither descriptor nor library behind.
    @Test
    void publishOnInterruptedThreadAbortsBeforeCommit() throws IOException {
        var project = newProject("Game");
        var compileOptions = options(COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        var plan = GodotProjectPublisher.prepare(project, compileOptions);

        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    GdccPublishException.class,
                    () -> plan.publish(successResult(compileOptions, List.of(writeArtifact("libout.so", "n"))))
            );
        } finally {
            // Do not leak the interrupt flag into the shared JUnit worker thread.
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted();
        }

        var binDir = project.projectRoot().resolve("bin");
        assertFalse(Files.exists(binDir.resolve(DESCRIPTOR)));
        assertTrue(Files.notExists(binDir)
                || Files.list(binDir).noneMatch(p -> p.getFileName().toString().endsWith(".so")));
    }

    private void deleteRecursively(@NotNull Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (var path : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.delete(path);
            }
        }
    }
}
