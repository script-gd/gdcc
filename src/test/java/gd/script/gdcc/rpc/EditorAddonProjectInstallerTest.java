package gd.script.gdcc.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EditorAddonProjectInstallerTest {
    @Test
    void copyProjectExcludesCachesAndGameBuildsButKeepsAddonResources(@TempDir Path tempDir) throws IOException {
        var source = Files.createDirectories(tempDir.resolve("source"));
        Files.createDirectories(source.resolve(".godot"));
        Files.writeString(source.resolve(".godot/extension_list.cfg"), "res://bin/gdcc.gdextension");
        Files.createDirectories(source.resolve("bin"));
        Files.writeString(source.resolve("bin/gdcc.gdextension"), "game build");
        Files.writeString(source.resolve("bin/game.dll"), "game library");
        Files.createDirectories(source.resolve("addons/gdcc/bin"));
        Files.writeString(source.resolve("addons/gdcc/bin/editor.dll"), "editor library");
        Files.writeString(source.resolve("project.godot"), "project settings");
        var target = tempDir.resolve("target");

        EditorAddonProjectInstaller.copyProject(source, target);

        assertFalse(Files.exists(target.resolve(".godot")));
        assertFalse(Files.exists(target.resolve("bin")));
        assertEquals("editor library", Files.readString(target.resolve("addons/gdcc/bin/editor.dll")));
        assertEquals("project settings", Files.readString(target.resolve("project.godot")));
        assertEquals("game library", Files.readString(source.resolve("bin/game.dll")));
    }
}
