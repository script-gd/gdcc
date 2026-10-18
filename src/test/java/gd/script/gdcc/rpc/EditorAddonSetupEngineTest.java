package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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

/// Exercises the interpreted setup UI against compiled installation services in an isolated editor.
@Tag("editor_addon")
class EditorAddonSetupEngineTest {
    private final Path temporary = Path.of("tmp/test/editor_addon_setup", UUID.randomUUID().toString()).toAbsolutePath();

    @Test
    void setupInstallsAndValidatesCachedCompilersWithoutBlockingEditor() throws Exception {
        var godot = GodotGdextensionTestRunner.findGodotBinaryFromEnv();
        Assumptions.assumeTrue(godot != null, "GODOT_BIN is required");
        Assumptions.assumeTrue(ZigUtil.findZig() != null, "Zig is required");
        var addon = temporary.resolve("addons/gdcc");
        Files.createDirectories(addon.resolve("util"));
        for (var name : List.of("gdcc_dock.gd", "gdcc_setup_dialog.gd")) {
            Files.copy(Path.of("src/editor_addon/addons/gdcc").resolve(name), addon.resolve(name));
        }
        var compiled = EditorAddonProjectInstaller.compileClientLibrary(temporary.resolve("build"), TargetPlatform.getNativePlatform());
        assertTrue(compiled.success(), () -> compiled.diagnostics().toString());
        Files.writeString(temporary.resolve("build/.gdignore"), "");
        EditorAddonProjectInstaller.installExtension(temporary, EditorAddonProjectInstaller.EXTENSION_SUB_DIR,
                compiled.artifacts(), EditorAddonProjectInstaller.EXTENSION_FILE_NAME,
                COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform(), true);
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
                } else if (path.equals("/gdcc-jre/releases/latest") || path.equals("/bad/gdcc-jre/releases/latest")) {
                    body = ("""
                            {"assets":[{"name":"gdcc-jre-1.0.0-windows-x64.zip","browser_download_url":"%sruntime.zip","size":%d},
                            {"name":"SHA256SUMS","browser_download_url":"%sSHA256SUMS"}]}
                            """.formatted(base, runtimeBytes.length, base + (path.startsWith("/bad/") ? "bad/" : ""))).getBytes(StandardCharsets.UTF_8);
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
                    body = (sha256(runtimeBytes).toUpperCase(Locale.ROOT) + "  gdcc-jre-1.0.0-windows-x64.zip\n").getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/bad/SHA256SUMS")) {
                    body = ("0".repeat(64) + "  gdcc-jre-1.0.0-windows-x64.zip\n").getBytes(StandardCharsets.UTF_8);
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
                var expected = new ArrayList<>(List.of("disconnected", "connected", "timeline", "window_bounds", "footer_bounds",
                        "native_coroutine_bridge", "proxy_initialization", "java_parser", "java_process", "java_scan", "java_check_stays", "header_alignment", "next_navigation", "load_more_item", "action_layout", "action_alignment",
                        "process_timeout", "user_cancellation", "safe_paths", "manual_proxy", "jar_metadata", "zip_traversal",
                        "release_pagination", "release_dates", "nightly_link", "preview_dates", "release_install", "preview_nested_libraries",
                        "local_import_reuse", "local_dependency_sentinel", "missing_dependency_dialog", "checksum_failure", "cache_inventory"));
                if (distributionSmoke) {
                    expected.addAll(List.of("real_windows_jre", "real_linux_tar", "jre_publish", "jre_reuse", "jre_repair", "download_window_bounds", "all_java_list", "download_size", "jre_phases", "jre_copy_reuse", "jre_changed_checksum", "locked_jre_copy", "incomplete_runtime_hidden", "hidden_cleanup"));
                }
                expected.addAll(List.of("finish_enabled", "apply_configuration", "connected_apply_restarts", "busy_apply_rejected", "restart_failure_reported",
                        "process_teardown", "download_teardown", "worker_teardown", "busy_cleanup"));
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

    private record Entry(String name, byte[] bytes) {
    }

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
                var busy := false
                var restarts := 0
                var restart_error := OK
                var previous_endpoint: Array = []
                func status_text() -> String:
                    return "fixture status"
                func is_server_connected() -> bool:
                    return connected
                func can_start_action() -> bool:
                    return not busy
                func server_endpoint() -> Array:
                    return ["127.0.0.1", ProjectSettings.get_setting("gdcc/compilation_server/port", 6099)]
                func restart_server(previous: Array) -> int:
                    previous_endpoint = previous
                    restarts += 1
                    connected = false
                    await get_tree().process_frame
                    connected = restart_error == OK
                    return restart_error

            func _enter_tree() -> void:
                _run.call_deferred()

            func check_step(name: String, ok: bool, details: String = "") -> void:
                steps.append({"name": name, "ok": ok, "details": details})
                print("GDCC_SETUP_STEP: " + name + " " + str(ok))

            func _capture_result(operation: Variant, state: Dictionary) -> void:
                state["result"] = await Setup.await_result(operation)
                state["done"] = true

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
                var idle_size: Vector2i = dialog.size
                var idle_status_height := dialog._status.get_minimum_size().y
                dialog._set_busy(true)
                dialog._status.text = "Downloading and installing Java... ".repeat(100)
                dialog._progress_text.text = "Downloaded 123.45 MiB of 234.56 MiB"
                dialog._progress_text.modulate.a = 1.0
                await get_tree().process_frame
                await get_tree().process_frame
                check_step("footer_bounds", dialog.size == idle_size and dialog._status.get_minimum_size().y <= idle_status_height + 1 and dialog._status.tooltip_text == dialog._status.text, str({"idle": idle_size, "size": dialog.size, "minimum": dialog._status.get_minimum_size(), "idle_status_height": idle_status_height, "tooltip_matches": dialog._status.tooltip_text == dialog._status.text}))
                dialog._set_busy(false)
                dialog._busy = false
                var service := dialog._service
                service.cache_root = ProjectSettings.globalize_path("res://cache")
                service.api_base = config["api"]
                service.set_busy(true)
                var operation: Variant = service.work(func() -> int: return 7)
                var suspended: bool = operation is Object and operation.has_signal("completed")
                var resumed: Variant = await Setup.await_result(operation)
                var immediate: Variant = service.check_java(config["java"] + "-missing")
                check_step("native_coroutine_bridge", suspended and resumed == 7 and immediate is bool and not (await Setup.await_result(immediate)))
                await Setup.await_result(service.initialize_proxy())
                check_step("proxy_initialization", service._proxies is Dictionary)
                service._proxies = {} # Local HTTP fixtures must not depend on the machine's proxy.
                check_step("java_parser", GdccInstallUtil.java_major('openjdk 25.0.2 2026-01-20') == 25 and GdccInstallUtil.java_major('java version "21.0.1"') == 21 and GdccInstallUtil.java_major("garbage 99") == 0)
                var java_result: bool = await Setup.await_result(service.check_java(config["java"]))
                check_step("java_process", java_result)
                OS.set_environment("JAVA", config["java"])
                var detected: Array = await Setup.await_result(service.scan_java())
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
                var timed: Dictionary = await Setup.await_result(service.run_process(config["java"], PackedStringArray(["-cp", ProjectSettings.globalize_path("res://fixture"), "FixtureMain", "--hang"]), 0.1))
                check_step("process_timeout", timed["code"] == -1 and service._process_id == -1)
                dialog._set_busy(true)
                get_tree().create_timer(0.1).timeout.connect(dialog._close, CONNECT_ONE_SHOT)
                var cancelled: Dictionary = await Setup.await_result(service.run_process(config["java"], PackedStringArray(["-cp", ProjectSettings.globalize_path("res://fixture"), "FixtureMain", "--hang"]), 5.0))
                check_step("user_cancellation", cancelled["code"] == -1 and service.is_cancelled() and service._process_id == -1)
                dialog._set_busy(false)
                service.set_busy(true)
                check_step("safe_paths", not GdccInstallUtil.check_relative_path("../escape") and not GdccInstallUtil.check_relative_path("C:/escape") and not GdccInstallUtil.check_relative_path("a/../../escape") and GdccInstallUtil.check_relative_path("lib/a.jar"))
                var proxy := GdccInstallUtil.parse_windows_proxy("  ProxyEnable    REG_DWORD    0x1\\n  ProxyServer    REG_SZ    http=127.0.0.1:7890;https=[::1]:8080\\n")
                check_step("manual_proxy", proxy.get("http") == ["127.0.0.1", 7890] and proxy.get("https") == ["::1", 8080] and GdccInstallUtil.parse_windows_proxy("ProxyEnable REG_DWORD 0x0\\nProxyServer REG_SZ x:1").is_empty(), str(proxy))
                var info := GdccInstallUtil.jar_info(ProjectSettings.globalize_path("res://fixture/compiler.jar"))
                check_step("jar_metadata", info.get("version") == "1.2.3" and GdccInstallUtil.check_dependencies(info))
                check_step("zip_traversal", GdccInstallUtil.extract_zip(ProjectSettings.globalize_path("res://fixture/unsafe.zip"), ProjectSettings.globalize_path("res://unsafe")) != OK and not FileAccess.file_exists("res://escaped.txt"))
                var release: Dictionary = await Setup.await_result(service.compiler_page(false, 1))
                var second: Dictionary = await Setup.await_result(service.compiler_page(false, 2))
                check_step("release_pagination", release.get("entries", []).size() == 20 and release.get("more", false) and second.get("entries", []).size() == 1 and not second.get("more", true), service.last_error)
                check_step("release_dates", release["entries"][0]["label"].ends_with("2026-10-17"))
                var previews: Dictionary = await Setup.await_result(service.compiler_page(true, 1))
                check_step("nightly_link", previews.get("entries", []).size() == 1 and previews["entries"][0]["url"] == "https://nightly.link/script-gd/gdcc/actions/artifacts/123.zip", str(previews))
                var preview_label: String = previews["entries"][0]["label"]
                check_step("preview_dates", preview_label.begins_with("feature/setup · abcdef12 · ") and not preview_label.contains("T09:") and not preview_label.ends_with("Z") and not preview_label.contains(" / "), preview_label)
                var installed: Dictionary = await Setup.await_result(service.install_compiler(release["entries"][0]))
                check_step("release_install", GdccInstallUtil.check_dependencies(installed) and installed.get("path", "").begins_with(service.cache_root), service.last_error)
                var preview_entry := {"id": "actions-123", "url": config["api"] + "preview.zip", "archive": true}
                var preview_install: Dictionary = await Setup.await_result(service.install_compiler(preview_entry))
                check_step("preview_nested_libraries", GdccInstallUtil.check_dependencies(preview_install), service.last_error)
                var imported: Dictionary = await Setup.await_result(service.import_compiler(ProjectSettings.globalize_path("res://fixture/compiler.jar")))
                var imported_again: Dictionary = await Setup.await_result(service.import_compiler(ProjectSettings.globalize_path("res://fixture/compiler.jar")))
                check_step("local_import_reuse", GdccInstallUtil.check_dependencies(imported) and GdccInstallUtil.check_dependencies(imported_again) and imported["path"] == imported_again["path"], service.last_error)
                var original_cache: String = service.cache_root
                service.cache_root = original_cache + "-sentinel"
                var dependency := ProjectSettings.globalize_path("res://fixture/lib/runtime.jar")
                DirAccess.rename_absolute(dependency, dependency + ".backup")
                var sentinel_import: Dictionary = await Setup.await_result(service.import_compiler(ProjectSettings.globalize_path("res://fixture/compiler.jar")))
                check_step("local_dependency_sentinel", GdccInstallUtil.check_dependencies(sentinel_import) and sentinel_import.get("local", false) and not FileAccess.file_exists(str(sentinel_import.get("path", "")).get_base_dir().path_join("lib/runtime.jar")), service.last_error)
                DirAccess.rename_absolute(dependency + ".backup", dependency)
                service.cache_root = original_cache
                await dialog._import_jar(ProjectSettings.globalize_path("res://missing/compiler.jar"))
                await get_tree().process_frame
                await get_tree().process_frame
                check_step("missing_dependency_dialog", dialog._error_dialog.visible and dialog._error_dialog.dialog_text.contains("gdparser-*.jar"))
                dialog._error_dialog.hide()
                var corrupted := {"id": "corrupted", "url": config["api"] + "compiler.jar", "archive": false, "digest": "sha256:" + "0".repeat(64)}
                var rejected: Dictionary = await Setup.await_result(service.install_compiler(corrupted))
                check_step("checksum_failure", rejected.is_empty() and not DirAccess.dir_exists_absolute(service.cache_root.path_join("compilers/corrupted")), service.last_error)
                check_step("cache_inventory", service.cached_compilers().size() == 3)
                if config.has("windows_runtime"):
                    var windows_output := ProjectSettings.globalize_path("res://runtime-windows")
                    var windows_error: Variant = await Setup.await_result(service.work(func() -> int: return GdccInstallUtil.extract_zip(config["windows_runtime"], windows_output)))
                    var java := windows_output.path_join("gdcc-jre-1.0.0-windows-x64/jre/bin/java.exe")
                    check_step("real_windows_jre", windows_error == OK and await Setup.await_result(service.check_java(java)), str(windows_error))
                    var linux_output := ProjectSettings.globalize_path("res://runtime-linux")
                    var linux_error: Variant = await Setup.await_result(service.work(func() -> int: return GdccInstallUtil.extract_tar_gzip(config["linux_runtime"], linux_output)))
                    check_step("real_linux_tar", linux_error == OK and FileAccess.file_exists(linux_output.path_join("gdcc-jre-1.0.0-linux-x64/jre/bin/java")), str(linux_error))
                    service.set_busy(false)
                    service.last_error = ""
                    var published: String = await Setup.await_result(service.install_jre())
                    check_step("jre_publish", not published.is_empty() and FileAccess.file_exists(published) and published.begins_with(service.cache_root.path_join("runtimes")), service.last_error)
                    var runtime_root: String = service.cache_root.path_join("runtimes").path_join(FileAccess.get_sha256(config["windows_runtime"]))
                    var runtime_count := DirAccess.get_directories_at(service.cache_root.path_join("runtimes")).size()
                    service.download_size = 0
                    var reused: String = await Setup.await_result(service.install_jre())
                    check_step("jre_reuse", reused == published and service.download_size == 0 and DirAccess.get_directories_at(service.cache_root.path_join("runtimes")).size() == runtime_count, reused)
                    await Setup.await_result(service.work(func() -> void: GdccInstallUtil.remove_tree(published.get_base_dir().get_base_dir().get_base_dir())))
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
                    await get_tree().process_frame
                    await get_tree().process_frame
                    var before_download: Vector2i = dialog.size
                    var download_layout := {"height": dialog.size.y, "seen": false}
                    var watch_layout := func() -> void:
                        download_layout["height"] = maxi(download_layout["height"], dialog.size.y)
                        if service.http != null and not service.http.download_file.is_empty():
                            download_layout["seen"] = true
                    get_tree().process_frame.connect(watch_layout)
                    await dialog._install_jre()
                    get_tree().process_frame.disconnect(watch_layout)
                    check_step("jre_repair", FileAccess.file_exists(dialog._valid_java) and dialog._valid_java.begins_with(runtime_root) and await Setup.await_result(service.check_java(dialog._valid_java)) and dialog._current_step == 0 and not dialog._next_button.disabled, service.last_error)
                    check_step("download_window_bounds", download_layout["seen"] and download_layout["height"] <= before_download.y + 1 and dialog.size.y <= int(get_tree().root.size.y * 0.8) + 1, str(download_layout))
                    var all_java: Array = await Setup.await_result(service.scan_java())
                    check_step("all_java_list", all_java.any(func(info: Dictionary) -> bool: return info["path"] == config["java"]) and all_java.any(func(info: Dictionary) -> bool: return info["path"].begins_with(runtime_root)) and dialog._java_versions.size() >= 2, str(all_java))
                    var runtime_size := FileAccess.get_file_as_bytes(config["windows_runtime"]).size()
                    check_step("download_size", service.download_size == runtime_size and dialog._progress_text.text.contains(String.humanize_size(runtime_size)), dialog._progress_text.text)
                    check_step("jre_phases", phase_ui["matches"] and phases == ["Reading JRE release information...", "Reading JRE checksums...", "Downloading file...", "Verifying download...", "Extracting JRE...", "Checking extracted Java...", "Installing JRE...", "Copying and verifying JRE files...", "Checking installed Java...", "Cleaning temporary files...", "JRE installation complete."], str(phases))
                    var repaired: String = dialog._valid_java
                    runtime_count = DirAccess.get_directories_at(service.cache_root.path_join("runtimes")).size()
                    service.download_size = 0
                    await dialog._install_jre()
                    check_step("jre_copy_reuse", dialog._valid_java == repaired and service.download_size == 0 and DirAccess.get_directories_at(service.cache_root.path_join("runtimes")).size() == runtime_count, dialog._valid_java)
                    service.api_base = config["api"] + "bad/"
                    var wrong_jre: String = await Setup.await_result(service.install_jre())
                    check_step("jre_changed_checksum", wrong_jre.is_empty() and service.last_error.contains("SHA-256") and DirAccess.get_directories_at(service.cache_root.path_join("runtimes")).size() == runtime_count, service.last_error)
                    service.api_base = config["api"]
                    var blocked := service.cache_root.path_join("runtimes/blocked-runtime")
                    var blocker := FileAccess.open(blocked, FileAccess.WRITE)
                    blocker.store_string("preserve the existing destination")
                    blocker.close()
                    var locked := FileAccess.open(blocked, FileAccess.READ)
                    var copied: String = await Setup.await_result(service._publish_jre(windows_output.path_join("gdcc-jre-1.0.0-windows-x64"), "blocked-runtime", "jre/bin/java.exe"))
                    locked.close()
                    check_step("locked_jre_copy", not copied.is_empty() and copied.contains("blocked-runtime-") and await Setup.await_result(service.check_java(copied)) and DirAccess.dir_exists_absolute(windows_output.path_join("gdcc-jre-1.0.0-windows-x64")) and FileAccess.get_file_as_string(blocked) == "preserve the existing destination", copied + " | " + service.last_error)
                    var marker_path: String = copied.get_base_dir().get_base_dir().get_base_dir().path_join(".installing")
                    var hidden_runtime := FileAccess.open(marker_path, FileAccess.WRITE)
                    hidden_runtime.close()
                    var visible_java: Array = await Setup.await_result(service.scan_java())
                    check_step("incomplete_runtime_hidden", not visible_java.any(func(info: Dictionary) -> bool: return info["path"] == copied))
                    var copied_root: String = marker_path.get_base_dir()
                    await Setup.await_result(service.work(func() -> void: GdccInstallUtil.remove_tree(copied_root)))
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
                check_step("apply_configuration", loaded == OK and saved_command.contains(installed["path"]) and saved_command.contains("--enable-native-access=ALL-UNNAMED") and saved.get_value("gdcc", "compilation_server/host", "") == "127.0.0.1", saved_command)
                var old_port: int = ProjectSettings.get_setting("gdcc/compilation_server/port")
                manager.connected = true
                dialog._selected_jar = imported
                await dialog._apply_configuration()
                saved_command = ProjectSettings.get_setting("gdcc/compilation_server/launch_command", "")
                check_step("connected_apply_restarts", saved_command.contains(imported["path"]) and manager.restarts == 2 and manager.previous_endpoint == ["127.0.0.1", old_port] and ProjectSettings.get_setting("gdcc/compilation_server/port") != old_port and manager.connected and not dialog._busy)
                manager.busy = true
                await dialog._apply_configuration()
                check_step("busy_apply_rejected", ProjectSettings.get_setting("gdcc/compilation_server/launch_command", "") == saved_command and manager.restarts == 2 and not dialog._busy)
                manager.busy = false
                manager.restart_error = ERR_CANT_CONNECT
                await dialog._apply_configuration()
                check_step("restart_failure_reported", manager.restarts == 3 and not dialog._busy and dialog._status.text.contains("configuration was saved") and not manager.connected)
                var process_service := GdccInstallService.new()
                process_service.cache_root = service.cache_root
                process_service.report_busy = Callable(owner, "_report_busy")
                owner.add_child(process_service)
                process_service.set_busy(true)
                var process_state := {"done": false}
                _capture_result(process_service.run_process(config["java"], PackedStringArray(["-cp", ProjectSettings.globalize_path("res://fixture"), "FixtureMain", "--hang"]), 5.0), process_state)
                process_service.free()
                await get_tree().process_frame
                check_step("process_teardown", process_state["done"] and process_state["result"]["code"] == -1 and owner.busy == 0)
                var download_service := GdccInstallService.new()
                var download_root := ProjectSettings.globalize_path("res://teardown-download")
                download_service.cache_root = download_root
                download_service.report_busy = Callable(owner, "_report_busy")
                owner.add_child(download_service)
                download_service.set_busy(true)
                var download_state := {"done": false}
                _capture_result(download_service.install_compiler({"id": "teardown", "url": config["api"] + "compiler.jar", "archive": false}), download_state)
                download_service.free()
                await get_tree().process_frame
                check_step("download_teardown", download_state["done"] and download_state["result"].is_empty() and DirAccess.get_directories_at(download_root.path_join("staging")).is_empty() and owner.busy == 0)
                var worker_service := GdccInstallService.new()
                var worker_root := ProjectSettings.globalize_path("res://teardown-worker")
                worker_service.cache_root = worker_root
                worker_service.report_busy = Callable(owner, "_report_busy")
                owner.add_child(worker_service)
                worker_service.set_busy(true)
                var worker_state := {"done": false}
                _capture_result(worker_service.import_compiler(ProjectSettings.globalize_path("res://fixture/compiler.jar")), worker_state)
                var worker_started := worker_service._worker != null
                worker_service.free()
                await get_tree().process_frame
                check_step("worker_teardown", worker_started and worker_state["done"] and worker_state["result"].is_empty() and DirAccess.get_directories_at(worker_root.path_join("staging")).is_empty() and owner.busy == 0)
                check_step("busy_cleanup", owner.busy == 0)
                owner.queue_free()
                await get_tree().process_frame
                print("GDCC_SETUP_RESULT: " + JSON.stringify(steps))
                get_tree().quit.call_deferred()
            """;
}
