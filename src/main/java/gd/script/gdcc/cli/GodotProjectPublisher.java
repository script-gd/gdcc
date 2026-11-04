package gd.script.gdcc.cli;

import gd.script.gdcc.api.CompileOptions;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.exception.GdccPublishException;
import gd.script.gdcc.exception.GodotConfigException;
import gd.script.gdcc.exception.GodotProjectException;
import gd.script.gdcc.util.FileUtil;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// Publishes one successful compile result into `<project>/bin`: installs the shared library
/// under a content-hashed name and merges `bin/gdcc.gdextension`, following the same descriptor
/// contract the editor addon maintains (`entry_symbol`, `compatibility_minimum`, `reloadable`,
/// `gdcc_managed`, `platform.arch` library keys and the `gdcc_libraries` ownership list).
///
/// Publishing is two-phase so every usage-level problem surfaces before the module exists:
/// [prepare] (run under the project + output locks, before `createModule`) validates `bin`, the
/// existing descriptor and the whole `[libraries]` merge, and snapshots the descriptor bytes;
/// [publish] (still under the locks, after a successful compile) re-verifies those bytes were not
/// touched by an outside writer, installs the new hash-named library, then commits the merged
/// descriptor through a same-directory temporary file plus an atomic replace. A failed publish
/// therefore leaves the previous installation intact — at worst an unreferenced new library
/// stays behind, which the plan explicitly permits.
final class GodotProjectPublisher {
    static final @NotNull String BIN_DIR_NAME = "bin";
    static final @NotNull String DESCRIPTOR_FILE_NAME = "gdcc.gdextension";

    private static final @NotNull String RES_PREFIX = "res://";
    private static final @NotNull String SECTION_CONFIGURATION = "configuration";
    private static final @NotNull String SECTION_LIBRARIES = "libraries";
    private static final @NotNull String KEY_ENTRY_SYMBOL = "entry_symbol";
    private static final @NotNull String KEY_COMPATIBILITY_MINIMUM = "compatibility_minimum";
    private static final @NotNull String KEY_RELOADABLE = "reloadable";
    private static final @NotNull String KEY_MANAGED = "gdcc_managed";
    private static final @NotNull String KEY_OWNED_LIBRARIES = "gdcc_libraries";
    private static final @NotNull String KEY_LIBRARY_ALIASES = "gdcc_library_aliases";

    private static final @NotNull String ENTRY_SYMBOL_VALUE = "gdextension_entry";
    private static final @NotNull String COMPATIBILITY_MINIMUM_VALUE = "4.5";

    /// Hex characters of the library content hash embedded in the file name (8 bytes).
    private static final int LIBRARY_HASH_BYTES = 8;

    /// Godot platform and architecture feature tags recognized when classifying `[libraries]`
    /// keys — broader than the targets gdcc compiles, since a shared descriptor may legitimately
    /// carry entries for platforms this CLI never produces.
    private static final @NotNull Set<String> PLATFORM_TAGS = Set.of(
            "windows", "linux", "macos", "android", "ios", "visionos", "web",
            "linuxbsd", "bsd", "freebsd", "netbsd", "openbsd"
    );
    private static final @NotNull Set<String> ARCHITECTURE_TAGS = Set.of(
            "x86_64", "x86_32", "arm64", "arm32", "rv64", "ppc64", "wasm32", "loongarch64"
    );
    private static final @NotNull Set<String> MODE_TAGS = Set.of("debug", "release");

    /// Test seam invoked once between the library install and the descriptor staging; must
    /// never be reassigned in production code.
    static volatile @NotNull Runnable midPublishBarrier = () -> {
    };

    private GodotProjectPublisher() {
    }

    /// The publish-side plan produced while holding the locks before module creation: all
    /// validation that can fail early already ran, and `descriptorSnapshot` is the byte content
    /// the descriptor is expected to still have at commit time.
    record PreparedPublish(
            @NotNull Path projectRoot,
            @NotNull Path binDir,
            @NotNull Path descriptorPath,
            byte @Nullable [] descriptorSnapshot,
            @NotNull GdccExtensionDescriptor descriptor,
            @NotNull LibraryMerge merge,
            @NotNull String projectFileName,
            @NotNull TargetPlatform targetPlatform,
            @NotNull COptimizationLevel optimizationLevel
    ) {
        /// Commits `result`'s library into `bin` + merges the descriptor atomically.
        /// Everything thrown is a [GdccPublishException] (exit 1): the previous installation —
        /// descriptor and already-mapped libraries — is never damaged by a failed publish.
        @NotNull Path publish(@NotNull CompileResult result) {
            return GodotProjectPublisher.publish(this, result);
        }
    }

    /// The validated `[libraries]` merge: canonical `platform.arch` entries to (re)write for
    /// surviving targets, every key that must be deleted (legacy mode keys plus alternate
    /// canonical spellings), and this build's canonical target key.
    record LibraryMerge(
            @NotNull LinkedHashMap<String, GodotConfigFile.Value> canonicalValues,
            @NotNull List<String> keysToRemove,
            @NotNull String targetKey
    ) {
    }

    /// Validates `bin`, the existing descriptor (when present) and the full `[libraries]` merge;
    /// called under the project/output locks before the compile module is created so usage
    /// errors stay pre-module failures (exit 2).
    static @NotNull PreparedPublish prepare(
            @NotNull GodotProjectInput projectInput,
            @NotNull CompileOptions compileOptions
    ) throws IOException {
        var projectRoot = projectInput.projectRoot();
        var binDir = projectRoot.resolve(BIN_DIR_NAME);
        var descriptorPath = binDir.resolve(DESCRIPTOR_FILE_NAME);
        check(binDirectoryProblem(binDir));
        check(descriptorPathProblem(descriptorPath));

        byte[] snapshot = Files.exists(descriptorPath, LinkOption.NOFOLLOW_LINKS)
                ? Files.readAllBytes(descriptorPath)
                : null;
        var descriptor = GdccExtensionDescriptor.createEmpty();
        if (snapshot != null) {
            var parsed = GodotConfigFile.parse(decodeUtf8(snapshot, descriptorPath), descriptorPath.toString());
            descriptor = GdccExtensionDescriptor.fromConfig(parsed);
            requireManaged(descriptor, descriptorPath);
        }

        var platform = compileOptions.targetPlatform().godotPlatformFeature();
        var architecture = compileOptions.targetPlatform().godotArchitectureFeature();
        var merge = planLibraryMerge(descriptor, platform, architecture, descriptorPath, projectInput);
        return new PreparedPublish(
                projectRoot,
                binDir,
                descriptorPath,
                snapshot,
                descriptor,
                merge,
                projectFileName(projectInput.config().projectName()),
                compileOptions.targetPlatform(),
                compileOptions.optimizationLevel()
        );
    }

    /// Commits a successful compile result: re-checks every target path and the descriptor
    /// snapshot inside the locks, installs the new hash-named library, then atomically replaces
    /// the descriptor. Only `result`'s own artifacts are used — no work-directory scanning.
    private static @NotNull Path publish(@NotNull PreparedPublish plan, @NotNull CompileResult result) {
        try {
            if (!result.success()) {
                throw new GdccPublishException(plan.descriptorPath() + ": only a successful compile result can be published");
            }
            // The merge was planned against the options seen at prepare time; a result built on
            // different ones would name/key the library inconsistently, so that is a defect, not
            // a request to retarget.
            var resultOptions = result.compileOptions();
            if (resultOptions.targetPlatform() != plan.targetPlatform()
                    || resultOptions.optimizationLevel() != plan.optimizationLevel()) {
                throw new GdccPublishException(
                        plan.descriptorPath() + ": compile result options diverged from the prepared publish plan");
            }
            var target = plan.targetPlatform();
            var platform = target.godotPlatformFeature();
            var architecture = target.godotArchitectureFeature();
            var level = plan.optimizationLevel().name().toLowerCase(Locale.ROOT);
            var artifact = selectLibraryArtifact(plan.descriptorPath(), result.artifacts(), libraryExtension(target));

            // A canceled run must not leave a half-installed library behind: the first flag
            // check precedes any bin/ write, the second guards the descriptor commit itself.
            if (Thread.currentThread().isInterrupted()) {
                throw new GdccPublishException(plan.descriptorPath()
                        + ": publish interrupted before any file was installed; installation unchanged");
            }
            Files.createDirectories(plan.binDir());
            publishCheck(binDirectoryProblem(plan.binDir()));

            var resLibrary = RES_PREFIX + BIN_DIR_NAME + "/"
                    + installLibrary(plan, target, platform, architecture, level, artifact);

            // Integration tests interrupt deterministically between the library install and the
            // descriptor commit; production keeps the no-op barrier.
            midPublishBarrier.run();

            var descriptor = plan.descriptor();
            descriptor.set(SECTION_CONFIGURATION, KEY_ENTRY_SYMBOL, GdccExtensionDescriptor.stringValue(ENTRY_SYMBOL_VALUE));
            descriptor.set(SECTION_CONFIGURATION, KEY_COMPATIBILITY_MINIMUM,
                    GdccExtensionDescriptor.stringValue(COMPATIBILITY_MINIMUM_VALUE));
            descriptor.set(SECTION_CONFIGURATION, KEY_RELOADABLE, GdccExtensionDescriptor.boolValue(true));
            descriptor.set(SECTION_CONFIGURATION, KEY_MANAGED, GdccExtensionDescriptor.boolValue(true));
            descriptor.remove(SECTION_CONFIGURATION, KEY_LIBRARY_ALIASES);
            descriptor.set(SECTION_CONFIGURATION, KEY_OWNED_LIBRARIES,
                    GdccExtensionDescriptor.packedStringArrayValue(retainedOwnedLibraries(plan, resLibrary)));
            var merge = plan.merge();
            for (var key : merge.keysToRemove()) {
                descriptor.remove(SECTION_LIBRARIES, key);
            }
            for (var canonical : merge.canonicalValues().entrySet()) {
                descriptor.set(SECTION_LIBRARIES, canonical.getKey(), canonical.getValue());
            }
            descriptor.set(SECTION_LIBRARIES, merge.targetKey(),
                    GdccExtensionDescriptor.stringValue(resLibrary));

            // Same-directory temporary file, then an atomic replace — never delete-then-create.
            var temporaryDescriptor = Files.createTempFile(plan.binDir(), "gdcc-gdextension-", ".tmp");
            try {
                Files.writeString(temporaryDescriptor, descriptor.render(), StandardCharsets.UTF_8);
                // The whole check+commit runs under the access-denied retry: re-validating on
                // every attempt keeps a slow external write or a late interrupt from slipping
                // past a check that went stale during backoff. The snapshot comparison detects a
                // descriptor an outside writer touched while we staged files — either failure
                // leaves the previous installation untouched.
                FileUtil.runWithAccessDeniedRetry(() -> {
                    publishCheck(binDirectoryProblem(plan.binDir()));
                    publishCheck(descriptorPathProblem(plan.descriptorPath()));
                    // Carried library paths were validated at prepare time, but the filesystem
                    // may have changed during compilation — a `bin` subdirectory turned into a
                    // symlink would smuggle a managed mapping outside `bin` if committed now.
                    for (var carried : merge.canonicalValues().entrySet()) {
                        var carriedText = carried.getValue() instanceof GodotConfigFile.StringValue stringValue
                                ? stringValue.text()
                                : null;
                        if (carriedText == null
                                || resolveManagedLibraryPath(plan.projectRoot(), carriedText) == null) {
                            throw new GdccPublishException(plan.descriptorPath() + ": libraries key '"
                                    + carried.getKey() + "' no longer resolves inside res://bin/ — "
                                    + "the project layout changed during compilation");
                        }
                    }
                    var currentSnapshot = Files.exists(plan.descriptorPath(), LinkOption.NOFOLLOW_LINKS)
                            ? Files.readAllBytes(plan.descriptorPath())
                            : null;
                    if (!Arrays.equals(currentSnapshot, plan.descriptorSnapshot())) {
                        throw new GdccPublishException(plan.descriptorPath()
                                + ": descriptor changed while publishing; refusing to commit over an external writer's changes");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new GdccPublishException(plan.descriptorPath()
                                + ": publish interrupted before the descriptor commit; installation unchanged");
                    }
                    FileUtil.moveAtomicReplacing(temporaryDescriptor, plan.descriptorPath());
                });
            } finally {
                Files.deleteIfExists(temporaryDescriptor);
            }
            return plan.descriptorPath();
        } catch (GdccPublishException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new GdccPublishException(
                    plan.descriptorPath() + ": cannot publish build result: " + exception.getMessage(), exception);
        }
    }

    /// The single artifact that is the compiled library for this target (matching the platform's
    /// shared-library extension, e.g. `.dll` vs a Windows `.pdb` sibling); exactly one must exist.
    private static @NotNull Path selectLibraryArtifact(
            @NotNull Path descriptorPath,
            @NotNull List<Path> artifacts,
            @NotNull String extension
    ) {
        var suffix = "." + extension;
        var matches = artifacts.stream()
                .filter(artifact -> {
                    var fileName = artifact.getFileName();
                    return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(suffix);
                })
                .toList();
        if (matches.isEmpty()) {
            throw new GdccPublishException(descriptorPath + ": compile result contains no " + extension + " library");
        }
        if (matches.size() > 1) {
            throw new GdccPublishException(descriptorPath + ": compile result contains multiple " + extension + " libraries");
        }
        var artifact = matches.getFirst();
        if (!Files.isRegularFile(artifact)) {
            throw new GdccPublishException(descriptorPath + ": compiled library is not accessible: " + artifact);
        }
        return artifact;
    }

    /// Copies the artifact to a temporary file inside `bin`, hashes the copied content, and links
    /// it to `bin/<lib><project>_<platform>_<level>_<arch>-<hash16>.<ext>`. The hard link is the
    /// only genuinely no-replace install primitive: unlike an atomic rename it can never
    /// overwrite a destination that appeared meanwhile — a same-named file is adopted only when
    /// it is a regular file whose full hash matches, anything else fails the publish.
    private static @NotNull String installLibrary(
            @NotNull PreparedPublish plan,
            @NotNull TargetPlatform target,
            @NotNull String platform,
            @NotNull String architecture,
            @NotNull String level,
            @NotNull Path artifact
    ) throws IOException {
        var baseName = plan.projectFileName() + "_" + platform + "_" + level + "_" + architecture;
        var temporary = Files.createTempFile(plan.binDir(), "gdcc-library-", ".tmp");
        try {
            Files.copy(artifact, temporary, StandardCopyOption.REPLACE_EXISTING);
            // Hashing the staged copy ties the published name to the bytes that actually land.
            var contentHash = StringUtil.sha256(Files.readAllBytes(temporary));
            var hashText = StringUtil.toHex(contentHash, LIBRARY_HASH_BYTES).toLowerCase(Locale.ROOT);
            var libraryName = target.sharedLibraryFileName(baseName + "-" + hashText);
            var libraryTarget = plan.binDir().resolve(libraryName);
            if (Files.exists(libraryTarget, LinkOption.NOFOLLOW_LINKS)) {
                verifyHashNamedLibrary(libraryTarget, contentHash);
                Files.delete(temporary);
            } else {
                try {
                    Files.createLink(libraryTarget, temporary);
                    Files.delete(temporary);
                } catch (FileAlreadyExistsException _) {
                    // A non-protocol writer landed the same name first — adopt only identical content.
                    verifyHashNamedLibrary(libraryTarget, contentHash);
                    Files.delete(temporary);
                }
            }
            return libraryName;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// A hash-named path is adoptable only as a real, non-linked file holding the exact bytes —
    /// adopting a symlink would silently depend on a target outside the controlled bin/.
    private static void verifyHashNamedLibrary(@NotNull Path libraryTarget, byte @NotNull [] contentHash) throws IOException {
        if (Files.isSymbolicLink(libraryTarget)
                || !Files.isRegularFile(libraryTarget)
                || !Arrays.equals(StringUtil.sha256(Files.readAllBytes(libraryTarget)), contentHash)) {
            throw new GdccPublishException("hash-named library has unexpected contents: " + libraryTarget);
        }
    }

    /// `gdcc_libraries` keeps every previously-owned library still present on disk (the owner
    /// list also covers libraries no longer referenced) and adds the freshly published one.
    /// Entries resolving outside `bin/` (`res://../x`, absolute paths) are dropped instead of
    /// retained — the ownership list must never claim files it does not manage.
    private static @NotNull List<String> retainedOwnedLibraries(
            @NotNull PreparedPublish plan,
            @NotNull String resLibrary
    ) {
        var owned = plan.descriptor().get(SECTION_CONFIGURATION, KEY_OWNED_LIBRARIES);
        var retained = new ArrayList<String>();
        if (owned instanceof GodotConfigFile.StringArrayValue arrayValue) {
            for (var entry : arrayValue.items()) {
                if (ownedLibraryExists(plan, entry) && !retained.contains(entry)) {
                    retained.add(entry);
                }
            }
        }
        if (!retained.contains(resLibrary)) {
            retained.add(resLibrary);
        }
        return retained;
    }

    /// An ownership entry is retained only when it still points at a real regular file inside
    /// `bin` — stale or suspicious entries are dropped rather than carried forward.
    private static boolean ownedLibraryExists(@NotNull PreparedPublish plan, @NotNull String entry) {
        var resolved = resolveManagedLibraryPath(plan.projectRoot(), entry);
        return resolved != null && Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS);
    }

    /// The gdcc ownership contract on an existing descriptor: `gdcc_managed` must literally be
    /// boolean true — anything else means a user-owned file we must not overwrite — and the two
    /// ownership arrays must be string arrays when present.
    private static void requireManaged(@NotNull GdccExtensionDescriptor descriptor, @NotNull Path descriptorPath) {
        if (!(descriptor.get(SECTION_CONFIGURATION, KEY_MANAGED) instanceof GodotConfigFile.BoolValue managed)
                || !managed.flag()) {
            throw new GodotProjectException(
                    descriptorPath + ": refusing to overwrite an unmanaged or invalid gdextension descriptor");
        }
        for (var key : List.of(KEY_OWNED_LIBRARIES, KEY_LIBRARY_ALIASES)) {
            var value = descriptor.get(SECTION_CONFIGURATION, key);
            if (value != null && !(value instanceof GodotConfigFile.StringArrayValue)) {
                throw new GodotProjectException(
                        descriptorPath + ": invalid ownership metadata ('" + key + "' must be a string array)");
            }
        }
    }

    /// Classifies every `[libraries]` key and builds the merge for this publish:
    ///
    /// - `platform.arch` keys (tag set is exactly one platform + one architecture) are canonical;
    ///   alternate spellings fold into the `platform.arch` spelling, and conflicting values for
    ///   the same non-current target are rejected.
    /// - `platform.<debug|release>.arch` keys are the legacy format. They migrate to their
    ///   canonical key unless that key already exists; for a target other than the current one
    ///   both mode keys pointing at different libraries is an ambiguity error (the CLI cannot
    ///   guess which editor mode the legacy entry served).
    /// - Any other key with two or more feature tags is rejected when none of its tags is a
    ///   foreign platform or architecture relative to a managed target: Godot picks the library
    ///   whose matching key has the most tags, so such a key could shadow a managed entry.
    ///
    /// Single-tag keys and keys carrying an unrelated platform/architecture are kept verbatim —
    /// they cannot outrank the two-tag canonical entries.
    private static @NotNull LibraryMerge planLibraryMerge(
            @NotNull GdccExtensionDescriptor descriptor,
            @NotNull String platform,
            @NotNull String architecture,
            @NotNull Path descriptorPath,
            @NotNull GodotProjectInput project
    ) {
        var targetKey = platform + "." + architecture;
        var keysToRemove = new ArrayList<String>();
        var canonicalValues = new LinkedHashMap<String, GodotConfigFile.Value>();
        var canonicalSeen = new LinkedHashMap<String, String>();
        var managedTargets = new LinkedHashSet<String>();
        var legacyByTarget = new LinkedHashMap<String, List<Map.Entry<String, GodotConfigFile.Value>>>();
        var otherKeys = new ArrayList<Map.Entry<String, GodotConfigFile.Value>>();

        managedTargets.add(targetKey);
        for (var entry : descriptor.entries(SECTION_LIBRARIES)) {
            var tags = entry.getKey().split("\\.", -1);
            if (isLegacyModeKey(tags)) {
                // Validate before bucketing: a malformed legacy entry must fail the publish even
                // when a canonical key will discard it anyway.
                requireLibraryPath(descriptorPath, project, entry);
                keysToRemove.add(entry.getKey());
                legacyByTarget.computeIfAbsent(tags[0] + "." + tags[2], _ -> new ArrayList<>()).add(entry);
                continue;
            }
            var canonicalKey = canonicalKey(tags);
            if (canonicalKey != null) {
                managedTargets.add(canonicalKey);
                var path = requireLibraryPath(descriptorPath, project, entry);
                if (canonicalKey.equals(targetKey)) {
                    // All spellings of the current target are rewritten to the new library.
                    keysToRemove.add(entry.getKey());
                    continue;
                }
                var previous = canonicalSeen.putIfAbsent(canonicalKey, path);
                if (previous != null && !previous.equals(path)) {
                    throw new GodotProjectException(descriptorPath
                            + ": conflicting libraries entries for target '" + canonicalKey + "'");
                }
                if (!entry.getKey().equals(canonicalKey)) {
                    keysToRemove.add(entry.getKey());
                }
                canonicalValues.putIfAbsent(canonicalKey, entry.getValue());
                continue;
            }
            otherKeys.add(entry);
        }

        for (var group : legacyByTarget.entrySet()) {
            var legacyTarget = group.getKey();
            managedTargets.add(legacyTarget);
            if (legacyTarget.equals(targetKey) || canonicalValues.containsKey(legacyTarget)) {
                continue;
            }
            var entries = group.getValue();
            var first = entries.getFirst();
            var firstPath = requireLibraryPath(descriptorPath, project, first);
            // Two modes may point at the same library written with different escapes — decode
            // before comparing so equivalent spellings are not misread as a conflict.
            if (entries.size() > 1 && !requireLibraryPath(descriptorPath, project, entries.get(1)).equals(firstPath)) {
                throw new GodotProjectException(descriptorPath
                        + ": ambiguous legacy libraries for '" + legacyTarget + "' — '"
                        + first.getKey() + "' and '" + entries.get(1).getKey()
                        + "' point at different libraries; write the '" + legacyTarget
                        + "' key explicitly or remove one mode key");
            }
            canonicalValues.put(legacyTarget, first.getValue());
        }

        for (var entry : otherKeys) {
            var tags = entry.getKey().split("\\.", -1);
            if (tags.length < 2) {
                continue;
            }
            for (var managed : managedTargets) {
                var managedTags = managed.split("\\.", -1);
                if (!containsForeignTag(tags, managedTags[0], managedTags[1])) {
                    throw new GodotProjectException(descriptorPath
                            + ": unsupported libraries key '" + entry.getKey()
                            + "' — its feature tags can match managed target '" + managed
                            + "' and shadow it; remove the key or use a '<platform>.<arch>' entry");
                }
            }
        }
        return new LibraryMerge(canonicalValues, keysToRemove, targetKey);
    }

    /// Decoded `res://bin/...` path of a libraries entry GDCC carries forward or compares. Only
    /// strings are meaningful library paths, and a managed mapping must resolve inside `bin` —
    /// anything else is broken managed metadata, not user content to preserve.
    private static @NotNull String requireLibraryPath(
            @NotNull Path descriptorPath,
            @NotNull GodotProjectInput project,
            @NotNull Map.Entry<String, GodotConfigFile.Value> entry
    ) {
        if (entry.getValue() instanceof GodotConfigFile.StringValue stringValue) {
            var text = stringValue.text();
            requireManagedLibraryPath(descriptorPath, entry.getKey(), project, text);
            return text;
        }
        throw new GodotProjectException(descriptorPath
                + ": libraries key '" + entry.getKey() + "' must hold a string library path");
    }

    /// Resolves a managed `res://bin/...` reference to a host path, or null when it is not a
    /// valid managed library reference: it must literally start `res://bin/`, stay inside `bin`
    /// after normalization (ruling out `../` escapes), and no existing component of the resolved
    /// path may be a symlink — an intermediate link could redirect the file outside `bin` while
    /// the lexical path still looks contained. The file itself need not exist: carried mappings
    /// for foreign platforms stay valid even when their library is absent locally.
    private static @Nullable Path resolveManagedLibraryPath(
            @NotNull Path projectRoot,
            @NotNull String resPath
    ) {
        if (!resPath.startsWith(RES_PREFIX + BIN_DIR_NAME + "/")) {
            return null;
        }
        final Path resolved;
        try {
            resolved = projectRoot.resolve(resPath.substring(RES_PREFIX.length())).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!resolved.startsWith(projectRoot.resolve(BIN_DIR_NAME))) {
            return null;
        }
        var cursor = projectRoot;
        for (var name : projectRoot.relativize(resolved)) {
            cursor = cursor.resolve(name);
            if (Files.isSymbolicLink(cursor)) {
                return null;
            }
        }
        return resolved;
    }

    /// [resolveManagedLibraryPath] variant for carried `[libraries]` mappings — a mapping that
    /// is not a managed bin path is broken managed metadata and fails the whole publish.
    private static @NotNull Path requireManagedLibraryPath(
            @NotNull Path descriptorPath,
            @NotNull String key,
            @NotNull GodotProjectInput project,
            @NotNull String resPath
    ) {
        var resolved = resolveManagedLibraryPath(project.projectRoot(), resPath);
        if (resolved == null) {
            throw new GodotProjectException(descriptorPath + ": libraries key '" + key
                    + "' is not a managed res://bin/ library path ('" + resPath + "')");
        }
        return resolved;
    }

    /// `platform.<debug|release>.arch` — the shape the addon migrates away from.
    private static boolean isLegacyModeKey(String @NotNull [] tags) {
        return tags.length == 3
                && PLATFORM_TAGS.contains(tags[0])
                && MODE_TAGS.contains(tags[1])
                && ARCHITECTURE_TAGS.contains(tags[2]);
    }

    /// A key whose tag set is exactly {platform, architecture}; returns its normalized
    /// `platform.arch` spelling regardless of the order the file used.
    private static @Nullable String canonicalKey(String @NotNull [] tags) {
        if (tags.length != 2) {
            return null;
        }
        if (PLATFORM_TAGS.contains(tags[0]) && ARCHITECTURE_TAGS.contains(tags[1])) {
            return tags[0] + "." + tags[1];
        }
        if (ARCHITECTURE_TAGS.contains(tags[0]) && PLATFORM_TAGS.contains(tags[1])) {
            return tags[1] + "." + tags[0];
        }
        return null;
    }

    /// A tag set can never match a `(platform, arch)` editor when it carries another platform or
    /// another architecture feature — those tags are mutually exclusive in Godot.
    private static boolean containsForeignTag(
            String @NotNull [] tags,
            @NotNull String platform,
            @NotNull String architecture
    ) {
        for (var tag : tags) {
            if ((PLATFORM_TAGS.contains(tag) && !tag.equals(platform))
                    || (ARCHITECTURE_TAGS.contains(tag) && !tag.equals(architecture))) {
                return true;
            }
        }
        return false;
    }

    /// `bin` must never be followed through a link: publishing into a symlinked `bin` would
    /// silently drop the installation somewhere outside the project resource tree.
    private static @Nullable String binDirectoryProblem(@NotNull Path binDir) {
        if (!Files.exists(binDir, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(binDir)) {
            return binDir + ": bin must be a real directory, not a symbolic link";
        }
        if (!Files.isDirectory(binDir, LinkOption.NOFOLLOW_LINKS)) {
            return binDir + ": bin exists but is not a directory";
        }
        return null;
    }

    private static @Nullable String descriptorPathProblem(@NotNull Path descriptorPath) {
        if (!Files.exists(descriptorPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(descriptorPath)) {
            return descriptorPath + ": descriptor must be a real file, not a symbolic link";
        }
        if (!Files.isRegularFile(descriptorPath, LinkOption.NOFOLLOW_LINKS)) {
            return descriptorPath + ": descriptor exists but is not a regular file";
        }
        return null;
    }

    /// The platform's shared-library extension, probed through the same naming rule the backend
    /// uses (`a.dll`, `liba.so`, ...).
    private static @NotNull String libraryExtension(@NotNull TargetPlatform target) {
        var probe = target.sharedLibraryFileName("a");
        return probe.substring(probe.lastIndexOf('.') + 1);
    }

    /// Port of the addon's `_project_filename`: Godot `String.validate_filename` semantics
    /// (strip edges, replace each of `:/\?*"|%<>` with `_`) plus the extra pass turning
    /// U+0000–U+001F and U+007F control characters into `_`.
    private static @NotNull String projectFileName(@NotNull String projectName) {
        var validated = projectName.strip();
        var safe = new StringBuilder(validated.length());
        for (var i = 0; i < validated.length(); i++) {
            var c = validated.charAt(i);
            safe.append(":/\\?*\"|%<>".indexOf(c) >= 0 || c < 32 || c == 127 ? '_' : c);
        }
        return safe.toString();
    }

    private static @NotNull String decodeUtf8(byte @NotNull [] bytes, @NotNull Path descriptorPath) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new GodotConfigException(descriptorPath + ": file is not valid UTF-8", exception);
        }
    }

    private static void check(@Nullable String problem) {
        if (problem != null) {
            throw new GodotProjectException(problem);
        }
    }

    private static void publishCheck(@Nullable String problem) {
        if (problem != null) {
            throw new GdccPublishException(problem);
        }
    }
}
