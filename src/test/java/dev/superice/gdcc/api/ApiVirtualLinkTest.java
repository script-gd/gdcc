package dev.superice.gdcc.api;

import dev.superice.gdcc.exception.ApiBrokenLinkException;
import dev.superice.gdcc.exception.ApiEntryTypeMismatchException;
import dev.superice.gdcc.exception.ApiLinkCycleException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVirtualLinkTest {
    private static final Instant FIXED_TIME = Instant.parse("2026-04-22T10:15:30Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

    @Test
    void virtualAndLocalLinksExposeStableMetadataAndVirtualContentAccess() {
        var api = new API(FIXED_CLOCK);
        api.createModule("demo", "Demo");
        api.putFile("demo", "/src/main.gd", "extends Node\n");
        api.putFile("demo", "/src/generated/util.gd", "func util():\n\tpass\n");

        var mainLink = api.createLink("demo", "/main-link", VfsEntrySnapshot.LinkKind.VIRTUAL, "/src/main.gd");
        var srcLink = api.createLink("demo", "/src-link", VfsEntrySnapshot.LinkKind.VIRTUAL, "/src");
        var artifactLink = api.createLink("demo", "/artifact", VfsEntrySnapshot.LinkKind.LOCAL, "C:/build/demo.dll");

        assertEquals("/main-link", mainLink.path());
        assertEquals("main-link", mainLink.name());
        assertEquals(VfsEntrySnapshot.Kind.LINK, mainLink.kind());
        assertEquals(VfsEntrySnapshot.LinkKind.VIRTUAL, mainLink.linkKind());
        assertEquals("/src/main.gd", mainLink.target());
        assertFalse(mainLink.broken());
        assertNull(mainLink.brokenReason());

        assertEquals(VfsEntrySnapshot.LinkKind.VIRTUAL, srcLink.linkKind());
        assertFalse(srcLink.broken());
        assertEquals(VfsEntrySnapshot.LinkKind.LOCAL, artifactLink.linkKind());
        assertEquals("C:/build/demo.dll", artifactLink.target());
        assertFalse(artifactLink.broken());

        assertEquals("extends Node\n", api.readFile("demo", "/main-link"));
        api.putFile("demo", "/src-link/linked-write.gd", "linked");
        assertEquals("linked", api.readFile("demo", "/src/linked-write.gd"));

        var linkedDirectoryEntries = api.listDirectory("demo", "/src-link");
        assertEquals(
                List.of("generated", "linked-write.gd", "main.gd"),
                linkedDirectoryEntries.stream().map(VfsEntrySnapshot::name).toList()
        );
        assertEquals(
                List.of("/src-link/generated", "/src-link/linked-write.gd", "/src-link/main.gd"),
                linkedDirectoryEntries.stream().map(VfsEntrySnapshot::path).toList()
        );

        var surfacedEntry = assertInstanceOf(
                VfsEntrySnapshot.FileEntrySnapshot.class,
                api.readEntry("demo", "/src-link/main.gd")
        );
        assertEquals("/src-link/main.gd", surfacedEntry.path());

        var rootEntries = api.listDirectory("demo", "/");
        assertEquals(
                List.of("artifact", "main-link", "src", "src-link"),
                rootEntries.stream().map(VfsEntrySnapshot::name).toList()
        );

        var rootArtifact = rootEntries.stream()
                .filter(entry -> entry.name().equals("artifact"))
                .map(entry -> assertInstanceOf(VfsEntrySnapshot.LinkEntrySnapshot.class, entry))
                .findFirst()
                .orElseThrow();
        assertEquals(VfsEntrySnapshot.LinkKind.LOCAL, rootArtifact.linkKind());
    }

    @Test
    void brokenLinksAndCyclesAreVisibleInMetadataAndBlockedOnDereference() {
        var api = new API(FIXED_CLOCK);
        api.createModule("demo", "Demo");

        var brokenLink = api.createLink("demo", "/broken", VfsEntrySnapshot.LinkKind.VIRTUAL, "/missing.gd");
        assertTrue(brokenLink.broken());
        assertEquals(VfsEntrySnapshot.BrokenReason.MISSING_TARGET, brokenLink.brokenReason());

        var brokenEntry = assertInstanceOf(VfsEntrySnapshot.LinkEntrySnapshot.class, api.readEntry("demo", "/broken"));
        assertTrue(brokenEntry.broken());
        assertEquals(VfsEntrySnapshot.BrokenReason.MISSING_TARGET, brokenEntry.brokenReason());

        var brokenError = assertThrows(ApiBrokenLinkException.class, () -> api.readFile("demo", "/broken"));
        assertEquals(
                "Virtual link '/broken' in module 'demo' points to missing path '/missing.gd'",
                brokenError.getMessage()
        );

        api.createLink("demo", "/cycle-a", VfsEntrySnapshot.LinkKind.VIRTUAL, "/cycle-b");
        api.createLink("demo", "/cycle-b", VfsEntrySnapshot.LinkKind.VIRTUAL, "/cycle-a");

        var cycleEntry = assertInstanceOf(VfsEntrySnapshot.LinkEntrySnapshot.class, api.readEntry("demo", "/cycle-a"));
        assertTrue(cycleEntry.broken());
        assertEquals(VfsEntrySnapshot.BrokenReason.CYCLE, cycleEntry.brokenReason());

        var cycleError = assertThrows(ApiLinkCycleException.class, () -> api.readFile("demo", "/cycle-a"));
        assertEquals(
                "Virtual link cycle detected in module 'demo': /cycle-a -> /cycle-b -> /cycle-a",
                cycleError.getMessage()
        );
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void linkBoundaryFailuresStayExplicit() {
        var api = new API(FIXED_CLOCK);
        api.createModule("demo", "Demo");
        api.putFile("demo", "/src/main.gd", "main");
        api.createLink("demo", "/file-link", VfsEntrySnapshot.LinkKind.VIRTUAL, "/src/main.gd");
        api.createLink("demo", "/artifact", VfsEntrySnapshot.LinkKind.LOCAL, "C:/build/demo.dll");

        var localFileError = assertThrows(ApiEntryTypeMismatchException.class, () -> api.readFile("demo", "/artifact"));
        assertEquals("Path '/artifact' in module 'demo' is a local link, not a file", localFileError.getMessage());

        var fileAsDirectoryError = assertThrows(
                ApiEntryTypeMismatchException.class,
                () -> api.listDirectory("demo", "/file-link")
        );
        assertEquals(
                "Path '/file-link' in module 'demo' resolves to a file, not a directory",
                fileAsDirectoryError.getMessage()
        );

        var createDirectoryError = assertThrows(
                ApiEntryTypeMismatchException.class,
                () -> api.createDirectory("demo", "/file-link")
        );
        assertEquals(
                "Path '/file-link' in module 'demo' is a virtual link, not a directory",
                createDirectoryError.getMessage()
        );

        var intermediateLinkError = assertThrows(
                ApiEntryTypeMismatchException.class,
                () -> api.putFile("demo", "/file-link/nested.gd", "nested")
        );
        assertEquals(
                "Path '/file-link' in module 'demo' resolves to a file, not a directory",
                intermediateLinkError.getMessage()
        );

        var rootLinkError = assertThrows(
                IllegalArgumentException.class,
                () -> api.createLink("demo", "/", VfsEntrySnapshot.LinkKind.VIRTUAL, "/src")
        );
        assertEquals("path '/' cannot be used as a link path", rootLinkError.getMessage());

        var invalidTargetError = assertThrows(
                IllegalArgumentException.class,
                () -> api.createLink("demo", "/bad", VfsEntrySnapshot.LinkKind.VIRTUAL, "src/main.gd")
        );
        assertTrue(invalidTargetError.getMessage().contains("path must start with '/'"));

        var nullKindError = assertThrows(
                NullPointerException.class,
                () -> api.createLink("demo", "/bad", null, "/src/main.gd")
        );
        assertEquals("linkKind must not be null", nullKindError.getMessage());

        var nullTargetError = assertThrows(
                NullPointerException.class,
                () -> api.createLink("demo", "/bad", VfsEntrySnapshot.LinkKind.LOCAL, null)
        );
        assertEquals("target must not be null", nullTargetError.getMessage());
    }
}
