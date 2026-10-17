package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises interpreted setup scripts in an isolated real editor, without rebuilding the native addon.
class EditorAddonSetupEngineTest {
    private final Path temporary = Path.of("tmp/test/editor_addon_setup", UUID.randomUUID().toString()).toAbsolutePath();

    @Test
    void setupInstallsAndValidatesCachedCompilersWithoutBlockingEditor() throws Exception {
        var godot = GodotGdextensionTestRunner.findGodotBinaryFromEnv();
        Assumptions.assumeTrue(godot != null, "GODOT_BIN is required");
        var addon = temporary.resolve("addons/gdcc");
        Files.createDirectories(addon.resolve("util"));
        for (var name : List.of("gdcc_dock.gd", "gdcc_setup_dialog.gd", "gdcc_install_service.gd", "util/gdcc_install_util.gd")) {
            Files.copy(Path.of("src/editor_addon/addons/gdcc").resolve(name), addon.resolve(name));
        }
        Files.writeString(temporary.resolve("project.godot"), """
                config_version=5
                [application]
                config/name="GDCC setup test"
                [editor_plugins]
                enabled=PackedStringArray("res://addons/setup_test/plugin.cfg")
                """);
        var driverDirectory = temporary.resolve("addons/setup_test");
        Files.createDirectories(driverDirectory);
        Files.writeString(driverDirectory.resolve("plugin.gd"), DRIVER);
        Files.writeString(driverDirectory.resolve("plugin.cfg"), """
                [plugin]
                name="Setup Test"
                description="Isolated setup acceptance driver"
                author="GDCC"
                version="1.0"
                script="plugin.gd"
                """);
        var fixture = temporary.resolve("fixture");
        Files.createDirectories(fixture.resolve("lib"));
        var mainSource = fixture.resolve("FixtureMain.java");
        Files.writeString(mainSource, """
                public class FixtureMain {
                    public static void main(String[] args) throws Exception {
                        if (args.length > 0 && args[0].equals("--hang")) {
                            Thread.sleep(30000);
                        }
                        System.out.println("gdcc 1.2.3 (fixture abcdef12)");
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", fixture.toString(), mainSource.toString()));
        var jar = jar(Files.readAllBytes(fixture.resolve("FixtureMain.class")));
        Files.write(fixture.resolve("compiler.jar"), jar);
        Files.write(fixture.resolve("lib/runtime.jar"), new byte[]{1, 2, 3});
        Files.write(fixture.resolve("lib/gdparser-fixture.jar"), new byte[]{1, 2, 3});
        var libraries = zipEntries(List.of(new Entry("lib/runtime.jar", new byte[]{1, 2, 3}),
                new Entry("lib/gdparser-fixture.jar", new byte[]{1, 2, 3})));
        var noLibraries = temporary.resolve("missing");
        Files.createDirectories(noLibraries);
        Files.write(noLibraries.resolve("compiler.jar"), jar);
        Files.write(fixture.resolve("unsafe.zip"), zip("../escaped.txt", new byte[]{1}));
        var preview = zipEntries(List.of(new Entry("compiler.jar", jar), new Entry("compiler-libs.zip", libraries)));
        var windowsRuntime = Path.of("tmp/jre-verify/gdcc-jre-1.0.0-windows-x64.zip").toAbsolutePath();
        var linuxRuntime = Path.of("tmp/jre-verify/gdcc-jre-linux-x64.tar.gz").toAbsolutePath();
        var distributionSmoke = System.getProperty("os.name").startsWith("Windows")
                && Files.isRegularFile(windowsRuntime) && Files.isRegularFile(linuxRuntime);
        var runtimeBytes = distributionSmoke ? Files.readAllBytes(windowsRuntime) : new byte[0];
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            var base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            server.createContext("/", exchange -> {
                var path = exchange.getRequestURI().getPath();
                byte[] body;
                if (path.equals("/redirect")) {
                    exchange.getResponseHeaders().add("Location", base + "compiler.jar");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                } else if (path.equals("/compiler.jar")) {
                    body = jar;
                } else if (path.equals("/libs.zip")) {
                    body = libraries;
                } else if (path.equals("/preview.zip")) {
                    body = preview;
                } else if (path.equals("/gdcc-jre/releases/latest")) {
                    body = ("""
                            {"assets":[{"name":"gdcc-jre-1.0.0-windows-x64.zip","browser_download_url":"%sruntime.zip","size":%d},
                            {"name":"SHA256SUMS","browser_download_url":"%sSHA256SUMS"}]}
                            """.formatted(base, runtimeBytes.length, base)).getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/runtime.zip")) {
                    exchange.sendResponseHeaders(200, runtimeBytes.length);
                    for (var offset = 0; offset < runtimeBytes.length; offset += 65536) {
                        exchange.getResponseBody().write(runtimeBytes, offset, Math.min(65536, runtimeBytes.length - offset));
                        exchange.getResponseBody().flush();
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    exchange.close();
                    return;
                } else if (path.equals("/SHA256SUMS")) {
                    body = (sha256(runtimeBytes) + "  gdcc-jre-1.0.0-windows-x64.zip\n").getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/gdcc/releases")) {
                    var count = exchange.getRequestURI().getQuery().endsWith("page=2") ? 1 : 20;
                    var releases = new com.google.gson.JsonArray();
                    for (var index = 0; index < count; index++) {
                        var identifier = index + (count == 1 ? 20 : 0);
                        var release = JsonParser.parseString("""
                                {"id":%d,"tag_name":"v1.%d","name":"Fixture %d","published_at":"2026-10-17T09:30:00Z","assets":[
                                  {"name":"gdcc.jar","browser_download_url":"%sredirect","digest":"sha256:%s"},
                                  {"name":"gdcc-libs.zip","browser_download_url":"%slibs.zip"}]}
                                """.formatted(identifier, identifier, identifier, base, sha256(jar), base));
                        releases.add(release);
                    }
                    body = releases.toString().getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/gdcc/actions/artifacts")) {
                    body = """
                            {"artifacts":[{"id":123,"name":"gdcc-jar-and-libs","expired":false,"created_at":"2026-10-17T09:30:00Z",
                            "workflow_run":{"head_branch":"feature/setup","head_sha":"abcdef123456"}}]}
                            """.getBytes(StandardCharsets.UTF_8);
                } else {
                    body = "not found".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                var config = new JsonObject();
                config.addProperty("api", base);
                config.addProperty("java", Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString().replace('\\', '/'));
                if (distributionSmoke) {
                    assertEquals("d2a44cfb6762e270b37c8394aa006747db58bf6c9d7405c1d0825ef12b541877", sha256(Files.readAllBytes(windowsRuntime)));
                    assertEquals("8805bd0507604f1065372250621d02660104fe16e31db09ede08f8dda13a52bb", sha256(Files.readAllBytes(linuxRuntime)));
                    config.addProperty("windows_runtime", windowsRuntime.toString().replace('\\', '/'));
                    config.addProperty("linux_runtime", linuxRuntime.toString().replace('\\', '/'));
                }
                Files.writeString(temporary.resolve("config.json"), config.toString());
                var processBuilder = new ProcessBuilder(godot.toString(), "--headless", "--editor", "--path", temporary.toString(), "--quit-after", "5000");
                var home = temporary.resolve("home");
                Files.createDirectories(home);
                for (var key : List.of("APPDATA", "LOCALAPPDATA", "HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME")) {
                    processBuilder.environment().put(key, home.toString());
                }
                var outputPath = temporary.resolve("godot.log");
                var process = processBuilder.redirectErrorStream(true).redirectOutput(outputPath.toFile()).start();
                if (!process.waitFor(90, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                    fail("Godot setup test timed out:\n" + Files.readString(outputPath));
                }
                var output = Files.readString(outputPath);
                assertEquals(0, process.exitValue(), output);
                var result = output.lines().filter(line -> line.startsWith("GDCC_SETUP_RESULT: ")).findFirst();
                assertTrue(result.isPresent(), output);
                var steps = JsonParser.parseString(result.orElseThrow().substring("GDCC_SETUP_RESULT: ".length())).getAsJsonArray();
                var expected = new ArrayList<>(List.of("disconnected", "connected", "timeline", "window_bounds",
                        "java_parser", "java_process", "java_scan", "java_check_stays", "header_alignment", "next_navigation", "load_more_item", "action_layout", "action_alignment",
                        "process_timeout", "user_cancellation", "safe_paths", "manual_proxy", "jar_metadata", "zip_traversal",
                        "release_pagination", "release_dates", "nightly_link", "preview_dates", "release_install", "preview_nested_libraries",
                        "local_import_reuse", "local_dependency_sentinel", "missing_dependency_dialog", "checksum_failure", "cache_inventory"));
                if (distributionSmoke) {
                    expected.addAll(List.of("real_windows_jre", "real_linux_tar", "jre_publish", "jre_repair", "all_java_list", "download_size", "jre_phases", "locked_jre_copy", "incomplete_runtime_hidden", "hidden_cleanup"));
                }
                expected.addAll(List.of("finish_enabled", "apply_configuration", "connected_apply_rejected", "busy_cleanup"));
                var actual = new ArrayList<String>();
                for (var step : steps) {
                    actual.add(step.getAsJsonObject().get("name").getAsString());
                    assertTrue(step.getAsJsonObject().get("ok").getAsBoolean(), step + "\n" + output);
                }
                assertEquals(expected, actual, output);
                assertFalse(output.contains("ERROR:"), output);
                assertFalse(output.contains("Unicode parsing error"), output);
            } finally {
                server.stop(0);
            }
        }
    }

    private static byte[] jar(byte[] mainClass) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib/runtime.jar");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FixtureMain");
        var bytes = new ByteArrayOutputStream();
        try (var archive = new JarOutputStream(bytes, manifest)) {
            archive.putNextEntry(new ZipEntry("gdcc-version.properties"));
            archive.write("version=1.2.3\nbranch=fixture\ncommit=abcdef12\n".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("FixtureMain.class"));
            archive.write(mainClass);
            archive.closeEntry();
        }
        return bytes.toByteArray();
    }

    private record Entry(String name, byte[] bytes) {}

    private static byte[] zip(String name, byte[] bytes) throws Exception {
        return zipEntries(List.of(new Entry(name, bytes)));
    }

    private static byte[] zipEntries(List<Entry> entries) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(output)) {
            for (var entry : entries) {
                archive.putNextEntry(new ZipEntry(entry.name()));
                archive.write(entry.bytes());
                archive.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final String DRIVER = """
            @tool
            extends EditorPlugin
            const Util = preload("res://addons/gdcc/util/gdcc_install_util.gd")
            const Dock = preload("res://addons/gdcc/gdcc_dock.gd")
            const Setup = preload("res://addons/gdcc/gdcc_setup_dialog.gd")
            var steps: Array = []

            class Owner extends Node:
                var busy := 0
                func _report_busy(delta: int) -> void:
                    busy += delta

            class Manager extends Node:
                signal state_changed
                signal log_message(message: String)
                var connected := false
                func status_text() -> String:
                    return "fixture status"
                func is_server_connected() -> bool:
                    return connected
                func can_start_action() -> bool:
                    return true
                func auto_setup_module() -> void:
                    pass

            func _enter_tree() -> void:
                _run.call_deferred()

            func check_step(name: String, ok: bool, details: String = "") -> void:
                steps.append({"name": name, "ok": ok, "details": details})

            func _run() -> void:
                var config: Dictionary = JSON.parse_string(FileAccess.get_file_as_string("res://config.json"))
                var owner := Owner.new()
                get_tree().root.add_child(owner)
                var manager := Manager.new()
                owner.add_child(manager)
                var dock := Dock.new()
                dock.setup(manager)
                owner.add_child(dock)
                check_step("disconnected", not dock._log_output.visible and dock._disconnected.visible and not dock._clear_button.visible)
                manager.log_message.emit("retained output")
                manager.connected = true
                manager.state_changed.emit()
                check_step("connected", dock._log_output.visible and not dock._disconnected.visible and dock._log_output.text.contains("retained output"))
                var dialog := Setup.new()
                dialog.setup(manager)
                owner.add_child(dialog)
                check_step("timeline", dialog._java_step is LinkButton and dialog._exit_button is LinkButton and dialog._compiler_step.disabled and dialog._tabs.get_tab_count() == 2 and dialog._exit_button.get_parent() == dialog._java_step.get_parent().get_parent())
                dialog._busy = true
                dialog.open_setup()
                await get_tree().process_frame
                await get_tree().process_frame
                check_step("window_bounds", dialog._java_page.visible and not dialog._compiler_page.visible and dialog.size.y <= int(get_tree().root.size.y * 0.8) + 1 and dialog._next_button.get_global_rect().end.y <= dialog.size.y and abs(dialog._next_button.size.x - dialog._next_button.get_parent().size.x) <= 1, str(dialog.size))
                dialog._busy = false
                var service := dialog._service
                service.cache_root = ProjectSettings.globalize_path("res://cache")
                service.api_base = config["api"]
                service.set_busy(true)
                check_step("java_parser", Util.java_major('openjdk 25.0.2 2026-01-20') == 25 and Util.java_major('java version "21.0.1"') == 21 and Util.java_major("garbage 99") == 0)
                var java_result := await service.check_java(config["java"])
                check_step("java_process", java_result)
                OS.set_environment("JAVA", config["java"])
                var detected := await service.scan_java()
                check_step("java_scan", detected.any(func(info: Dictionary) -> bool: return info["path"] == config["java"] and info["usable"]), str(detected))
                service.set_busy(false)
                dialog._java_versions = detected
                dialog._refresh_java_list(config["java"])
                dialog._java_path.text = config["java"]
                await dialog._validate_java()
                check_step("java_check_stays", dialog._current_step == 0 and dialog._java_page.visible and not dialog._next_button.disabled and dialog._java_check.visible)
                await get_tree().process_frame
                var java_rect: Rect2 = dialog._java_step.get_global_rect()
                var check_rect: Rect2 = dialog._java_check.get_global_rect()
                var arrow_rect: Rect2 = dialog._step_arrow.get_global_rect()
                var compiler_rect: Rect2 = dialog._compiler_step.get_global_rect()
                var exit_rect: Rect2 = dialog._exit_button.get_global_rect()
                check_step("header_alignment", abs(java_rect.get_center().y - check_rect.get_center().y) <= 1 and abs(java_rect.get_center().y - arrow_rect.get_center().y) <= 1 and abs(java_rect.get_center().y - exit_rect.get_center().y) <= 1 and check_rect.position.x - java_rect.end.x <= 3 and arrow_rect.position.x - check_rect.end.x >= 10 and compiler_rect.position.x - arrow_rect.end.x >= 10)
                dialog._next_button.pressed.emit()
                while dialog._busy:
                    await get_tree().process_frame
                check_step("next_navigation", dialog._current_step == 1 and dialog._compiler_page.visible and dialog._next_button.text == "Finish compiler configuration" and dialog._next_button.disabled)
                var remote_list: ItemList = dialog._lists[0]
                var had_more := remote_list.item_count == 21 and remote_list.get_item_text(20) == "Load more"
                remote_list.select(20)
                remote_list.item_selected.emit(20)
                while dialog._busy:
                    await get_tree().process_frame
                check_step("load_more_item", had_more and dialog._entries[0].size() == 21 and remote_list.item_count == 21 and not dialog._more[0])
                await get_tree().process_frame
                var remote_button: Button
                var local_button: Button
                for button in dialog._buttons:
                    if button.get("text") == "Install selected version":
                        remote_button = button
                    if button.get("text") == "Install from local...":
                        local_button = button
                check_step("action_layout", remote_button.get_parent() == local_button.get_parent() and remote_button.size.x >= remote_button.get_parent().size.x * 0.5 and local_button.size.x <= local_button.get_parent().size.x / 3.0 + 1)
                var divider: Label
                for child in remote_button.get_parent().get_children():
                    if child is Label and child.text == "or":
                        divider = child
                check_step("action_alignment", divider != null and divider.autowrap_mode == TextServer.AUTOWRAP_OFF and divider.size.x >= divider.get_minimum_size().x and abs(divider.get_global_rect().get_center().y - remote_button.get_global_rect().get_center().y) <= 1 and divider.get_global_rect().end.x < local_button.get_global_rect().position.x and abs(remote_button.size.y - remote_button.get_combined_minimum_size().y) <= 1 and abs(local_button.size.y - local_button.get_combined_minimum_size().y) <= 1)
                service.set_busy(true)
                var timed := await service.run_process(config["java"], PackedStringArray(["-cp", ProjectSettings.globalize_path("res://fixture"), "FixtureMain", "--hang"]), 0.1)
                check_step("process_timeout", timed["code"] == -1 and service._process_id == -1)
                dialog._set_busy(true)
                get_tree().create_timer(0.1).timeout.connect(dialog._close, CONNECT_ONE_SHOT)
                var cancelled := await service.run_process(config["java"], PackedStringArray(["-cp", ProjectSettings.globalize_path("res://fixture"), "FixtureMain", "--hang"]), 5.0)
                check_step("user_cancellation", cancelled["code"] == -1 and service.is_cancelled() and service._process_id == -1)
                dialog._set_busy(false)
                service.set_busy(true)
                check_step("safe_paths", not Util.check_relative_path("../escape") and not Util.check_relative_path("C:/escape") and not Util.check_relative_path("a/../../escape") and Util.check_relative_path("lib/a.jar"))
                var proxy := Util.parse_windows_proxy("  ProxyEnable    REG_DWORD    0x1\\n  ProxyServer    REG_SZ    http=127.0.0.1:7890;https=[::1]:8080\\n")
                check_step("manual_proxy", proxy.get("http") == ["127.0.0.1", 7890] and proxy.get("https") == ["::1", 8080] and Util.parse_windows_proxy("ProxyEnable REG_DWORD 0x0\\nProxyServer REG_SZ x:1").is_empty(), str(proxy))
                var info := Util.jar_info(ProjectSettings.globalize_path("res://fixture/compiler.jar"))
                check_step("jar_metadata", info.get("version") == "1.2.3" and Util.check_dependencies(info))
                check_step("zip_traversal", Util.extract_zip(ProjectSettings.globalize_path("res://fixture/unsafe.zip"), ProjectSettings.globalize_path("res://unsafe")) != OK and not FileAccess.file_exists("res://escaped.txt"))
                var release := await service.compiler_page(false, 1)
                var second := await service.compiler_page(false, 2)
                check_step("release_pagination", release.get("entries", []).size() == 20 and release.get("more", false) and second.get("entries", []).size() == 1 and not second.get("more", true), service.last_error)
                check_step("release_dates", release["entries"][0]["label"].ends_with("2026-10-17"))
                var previews := await service.compiler_page(true, 1)
                check_step("nightly_link", previews.get("entries", []).size() == 1 and previews["entries"][0]["url"] == "https://nightly.link/script-gd/gdcc/actions/artifacts/123.zip", str(previews))
                var preview_label: String = previews["entries"][0]["label"]
                check_step("preview_dates", preview_label.begins_with("feature/setup · abcdef12 · ") and not preview_label.contains("T09:") and not preview_label.ends_with("Z") and not preview_label.contains(" / "), preview_label)
                var installed := await service.install_compiler(release["entries"][0])
                check_step("release_install", Util.check_dependencies(installed) and installed.get("path", "").begins_with(service.cache_root), service.last_error)
                var preview_entry := {"id": "actions-123", "url": config["api"] + "preview.zip", "archive": true}
                var preview_install := await service.install_compiler(preview_entry)
                check_step("preview_nested_libraries", Util.check_dependencies(preview_install), service.last_error)
                var imported := await service.import_compiler(ProjectSettings.globalize_path("res://fixture/compiler.jar"))
                var imported_again := await service.import_compiler(ProjectSettings.globalize_path("res://fixture/compiler.jar"))
                check_step("local_import_reuse", Util.check_dependencies(imported) and Util.check_dependencies(imported_again) and imported["path"] == imported_again["path"], service.last_error)
                var original_cache: String = service.cache_root
                service.cache_root = original_cache + "-sentinel"
                var dependency := ProjectSettings.globalize_path("res://fixture/lib/runtime.jar")
                DirAccess.rename_absolute(dependency, dependency + ".backup")
                var sentinel_import := await service.import_compiler(ProjectSettings.globalize_path("res://fixture/compiler.jar"))
                check_step("local_dependency_sentinel", Util.check_dependencies(sentinel_import) and sentinel_import.get("local", false) and not FileAccess.file_exists(str(sentinel_import.get("path", "")).get_base_dir().path_join("lib/runtime.jar")), service.last_error)
                DirAccess.rename_absolute(dependency + ".backup", dependency)
                service.cache_root = original_cache
                await dialog._import_jar(ProjectSettings.globalize_path("res://missing/compiler.jar"))
                await get_tree().process_frame
                await get_tree().process_frame
                check_step("missing_dependency_dialog", dialog._error_dialog.visible and dialog._error_dialog.dialog_text.contains("gdparser-*.jar"))
                dialog._error_dialog.hide()
                var corrupted := {"id": "corrupted", "url": config["api"] + "compiler.jar", "archive": false, "digest": "sha256:" + "0".repeat(64)}
                var rejected := await service.install_compiler(corrupted)
                check_step("checksum_failure", rejected.is_empty() and not DirAccess.dir_exists_absolute(service.cache_root.path_join("compilers/corrupted")), service.last_error)
                check_step("cache_inventory", service.cached_compilers().size() == 3)
                if config.has("windows_runtime"):
                    var windows_output := ProjectSettings.globalize_path("res://runtime-windows")
                    var windows_error: Variant = await service.work(func() -> int: return Util.extract_zip(config["windows_runtime"], windows_output))
                    var java := windows_output.path_join("gdcc-jre-1.0.0-windows-x64/jre/bin/java.exe")
                    check_step("real_windows_jre", windows_error == OK and await service.check_java(java), str(windows_error))
                    var linux_output := ProjectSettings.globalize_path("res://runtime-linux")
                    var linux_error: Variant = await service.work(func() -> int: return Util.extract_tar_gzip(config["linux_runtime"], linux_output))
                    check_step("real_linux_tar", linux_error == OK and FileAccess.file_exists(linux_output.path_join("gdcc-jre-1.0.0-linux-x64/jre/bin/java")), str(linux_error))
                    service.set_busy(false)
                    service.last_error = ""
                    var published := await service.install_jre()
                    check_step("jre_publish", not published.is_empty() and FileAccess.file_exists(published) and published.begins_with(service.cache_root.path_join("runtimes")), service.last_error)
                    var runtime_root: String = service.cache_root.path_join("runtimes/gdcc-jre-1.0.0-windows-x64")
                    await service.work(func() -> void: Util.remove_tree(runtime_root))
                    DirAccess.make_dir_recursive_absolute(runtime_root)
                    var phases: Array[String] = []
                    var phase_ui := {"matches": true}
                    service.phase_changed.connect(func(message: String) -> void:
                        phases.append(message)
                        phase_ui["matches"] = phase_ui["matches"] and dialog._status.text == message)
                    dialog._busy = true
                    dialog.open_setup()
                    dialog._busy = false
                    dialog._show_step(0)
                    await dialog._install_jre()
                    check_step("jre_repair", FileAccess.file_exists(dialog._valid_java) and dialog._valid_java.begins_with(runtime_root) and await service.check_java(dialog._valid_java) and dialog._current_step == 0 and not dialog._next_button.disabled, service.last_error)
                    var all_java := await service.scan_java()
                    check_step("all_java_list", all_java.any(func(info: Dictionary) -> bool: return info["path"] == config["java"]) and all_java.any(func(info: Dictionary) -> bool: return info["path"].begins_with(runtime_root)) and dialog._java_versions.size() >= 2, str(all_java))
                    var runtime_size := FileAccess.get_file_as_bytes(config["windows_runtime"]).size()
                    check_step("download_size", service.download_size == runtime_size and dialog._progress_text.text.contains(String.humanize_size(runtime_size)), dialog._progress_text.text)
                    check_step("jre_phases", phase_ui["matches"] and phases == ["Reading JRE release information...", "Reading JRE checksums...", "Downloading file...", "Verifying download...", "Extracting JRE...", "Checking extracted Java...", "Installing JRE...", "Copying and verifying JRE files...", "Checking installed Java...", "Cleaning temporary files...", "JRE installation complete."], str(phases))
                    var blocked := service.cache_root.path_join("runtimes/blocked-runtime")
                    var blocker := FileAccess.open(blocked, FileAccess.WRITE)
                    blocker.store_string("preserve the existing destination")
                    blocker.close()
                    var locked := FileAccess.open(blocked, FileAccess.READ)
                    var copied := await service._publish_jre(windows_output.path_join("gdcc-jre-1.0.0-windows-x64"), "blocked-runtime", "jre/bin/java.exe")
                    locked.close()
                    check_step("locked_jre_copy", not copied.is_empty() and copied.contains("blocked-runtime-") and await service.check_java(copied) and DirAccess.dir_exists_absolute(windows_output.path_join("gdcc-jre-1.0.0-windows-x64")) and FileAccess.get_file_as_string(blocked) == "preserve the existing destination", copied + " | " + service.last_error)
                    var marker_path: String = copied.get_base_dir().get_base_dir().get_base_dir().path_join(".installing")
                    var hidden_runtime := FileAccess.open(marker_path, FileAccess.WRITE)
                    hidden_runtime.close()
                    var visible_java := await service.scan_java()
                    check_step("incomplete_runtime_hidden", not visible_java.any(func(info: Dictionary) -> bool: return info["path"] == copied))
                    var copied_root: String = marker_path.get_base_dir()
                    await service.work(func() -> void: Util.remove_tree(copied_root))
                    check_step("hidden_cleanup", not DirAccess.dir_exists_absolute(copied_root))
                service.set_busy(false)
                dialog._refresh_installed(installed["path"])
                dialog._loaded[0] = true
                dialog._show_step(1)
                check_step("finish_enabled", dialog._compiler_check.visible and not dialog._next_button.disabled and dialog._next_button.text == "Finish compiler configuration")
                manager.connected = false
                dialog._valid_java = config["java"]
                dialog._selected_jar = installed
                await dialog._apply_configuration()
                var saved_command: String = ProjectSettings.get_setting("gdcc/compilation_server/launch_command", "")
                var saved := ConfigFile.new()
                var loaded := saved.load("res://project.godot")
                check_step("apply_configuration", loaded == OK and saved_command.contains(installed["path"]) and saved_command.contains("--enable-native-access=ALL-UNNAMED") and saved.get_value("gdcc", "server/host", "") == "127.0.0.1", saved_command)
                manager.connected = true
                await dialog._apply_configuration()
                check_step("connected_apply_rejected", ProjectSettings.get_setting("gdcc/compilation_server/launch_command", "") == saved_command and not dialog._busy)
                check_step("busy_cleanup", owner.busy == 0)
                owner.queue_free()
                await get_tree().process_frame
                print("GDCC_SETUP_RESULT: " + JSON.stringify(steps))
                get_tree().quit.call_deferred()
            """;
}
