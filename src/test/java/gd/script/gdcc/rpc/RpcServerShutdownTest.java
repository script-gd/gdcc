package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gd.script.gdcc.api.API;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the `server.shutdown` timing contract (editor-addon plan §2.8): the method handler only
/// records the exit intent and returns `{}`; the process exit happens strictly after the
/// response exchange is written and closed, on a thread outside the request executor.
///
/// Three levels, because each catches a different regression:
/// - dispatcher level: result shape + idempotency of the recorded intent;
/// - in-process HTTP level (injected non-exiting latch): 200 + `{}` reaches the client BEFORE
///   the exit action fires, the exit runs exactly once, off the request executor, and the
///   notification (204) path also triggers it;
/// - real-process level (`gdcc serve` in a child JVM): the client reads 200 + body, and only
///   afterwards the process exits with code 0 — a dispatcher-only assertion can never prove the
///   write-back ordering, and a non-zero exit would mean the shutdown hook did not run.
class RpcServerShutdownTest {
    private static final String SHUTDOWN_REQUEST =
            "{\"jsonrpc\": \"2.0\", \"method\": \"server.shutdown\", \"id\": 1}";
    private static final String SHUTDOWN_NOTIFICATION =
            "{\"jsonrpc\": \"2.0\", \"method\": \"server.shutdown\"}";

    private final HttpClient client = HttpClient.newHttpClient();

    /// The serving-exchange marker is thread-scoped: a request thread that never invoked
    /// `server.shutdown` must not observe the mark, and the requesting thread observes it
    /// exactly once. This is the mechanism that stops an unrelated concurrent exchange from
    /// triggering the exit ahead of the shutdown response.
    @Test
    void servingExchangeMarkerIsScopedToTheRequestingThread() throws Exception {
        var shutdown = RpcServerShutdown.forTesting(() -> {
        });
        assertFalse(shutdown.consumeServingExchange(), "fresh latch must not report a serving mark");

        var requesterServed = new AtomicBoolean();
        var requesterServedTwice = new AtomicBoolean(true);
        var requesterRan = new CountDownLatch(1);
        var requester = Thread.ofPlatform().start(() -> {
            shutdown.requestExit();
            requesterServed.set(shutdown.consumeServingExchange());
            requesterServedTwice.set(shutdown.consumeServingExchange());
            requesterRan.countDown();
        });
        assertTrue(requesterRan.await(10, TimeUnit.SECONDS));
        assertTrue(requesterServed.get(), "the requesting thread must observe its own serving mark");
        assertFalse(requesterServedTwice.get(), "the serving mark must be consumed exactly once");
        assertFalse(shutdown.consumeServingExchange(),
                "an unrelated thread must never observe the serving mark");
        assertTrue(shutdown.isExitRequested());
        requester.join(10_000);
    }

    @Test
    void dispatcherReturnsEmptyObjectAndRecordsIntentIdempotently() {
        var shutdown = RpcServerShutdown.forTesting(() -> {
        });
        try (var api = new API()) {
            var dispatcher = new JsonRpcDispatcher(api, shutdown);

            var first = dispatcher.dispatch(SHUTDOWN_REQUEST);
            var second = dispatcher.dispatch(SHUTDOWN_REQUEST);

            for (var response : new JsonObject[]{first, second}) {
                assertNotNull(response);
                assertFalse(response.has("error"), "server.shutdown must not fail");
                assertTrue(response.has("result"));
                assertTrue(response.getAsJsonObject("result").keySet().isEmpty(),
                        "server.shutdown result must be exactly {}");
            }
            assertTrue(shutdown.isExitRequested());
            // The dispatcher never triggers the exit itself; that is the transport's job.
        }
    }

    @Test
    void httpResponseReachesClientBeforeExitActionRunsExactlyOnce() throws Exception {
        var exitCalls = new AtomicInteger();
        var exitThreadName = new AtomicReference<String>();
        var exitThreadVirtual = new AtomicReference<Boolean>();
        var exitLatch = new CountDownLatch(1);
        var shutdown = RpcServerShutdown.forTesting(() -> {
            exitThreadName.set(Thread.currentThread().getName());
            exitThreadVirtual.set(Thread.currentThread().isVirtual());
            exitCalls.incrementAndGet();
            exitLatch.countDown();
        });
        try (var api = new API();
                var server = JsonRpcServer.start(
                        new JsonRpcDispatcher(api, shutdown),
                        "127.0.0.1", 0, JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            // 1) Request form: 200 with a `{}` result — the response provably reaches the client
            //    because the exit latch can only trip after the exchange has been closed.
            var response = post(server, SHUTDOWN_REQUEST);
            assertEquals(200, response.statusCode());
            var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            assertTrue(envelope.getAsJsonObject("result").keySet().isEmpty(),
                    "result must be the empty object: " + response.body());
            assertTrue(exitLatch.await(10, TimeUnit.SECONDS),
                    "exit action must run after the response was delivered");
            assertEquals("gdcc-rpc-exit", exitThreadName.get());
            assertEquals(Boolean.FALSE, exitThreadVirtual.get(),
                    "exit must run on a platform thread, not a request-executor virtual thread");

            // 2) Notification form: 204, no body — the exit intent must still be honored.
            var notification = post(server, SHUTDOWN_NOTIFICATION);
            assertEquals(204, notification.statusCode());

            // 3) Idempotency: repeated requests still return the same `{}` and the exit action
            //    runs exactly once no matter how many exchanges complete.
            var repeated = post(server, SHUTDOWN_REQUEST);
            assertEquals(200, repeated.statusCode());
            assertTrue(JsonParser.parseString(repeated.body()).getAsJsonObject()
                    .getAsJsonObject("result").keySet().isEmpty());
            assertEquals(1, exitCalls.get(), "exit action must run exactly once");
        }
    }

    @Test
    void serveProcessExitsWithCodeZeroAfterShutdownResponse() throws Exception {
        var port = findFreePort();
        var javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        var process = new ProcessBuilder(
                javaBin.toString(),
                "-cp", System.getProperty("java.class.path"),
                "gd.script.gdcc.Main", "serve",
                "--host", "127.0.0.1", "--port", String.valueOf(port))
                .redirectErrorStream(true)
                .start();
        var childOutput = new StringBuffer();
        var outputGobbler = Thread.ofVirtual().start(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                var buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    childOutput.append(buffer, 0, read);
                }
            } catch (IOException exception) {
                childOutput.append("[child output read failed: ").append(exception.getMessage()).append(']');
            }
        });
        try {
            awaitListening(port, childOutput);

            // The full response (200 + `{}` body) must be readable before the process exits:
            // an exit-before-write would surface as a connection reset instead of a clean body.
            var response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/rpc"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(SHUTDOWN_REQUEST))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            assertTrue(envelope.getAsJsonObject("result").keySet().isEmpty(),
                    "result must be the empty object: " + response.body());

            assertTrue(process.waitFor(60, TimeUnit.SECONDS),
                    () -> "serve process did not exit after server.shutdown; output:\n" + childOutput);
            assertEquals(0, process.exitValue(),
                    () -> "graceful shutdown must exit 0 (shutdown hook completed server.close"
                            + " + API.close); output:\n" + childOutput);
            assertFalse(isListening(port), "port must be released after the serve process exits");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            outputGobbler.join(5_000);
        }
    }

    private HttpResponse<String> post(JsonRpcServer server, String body) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://" + server.host() + ":" + server.port() + "/rpc"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static int findFreePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean isListening(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void awaitListening(int port, StringBuffer childOutput) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (isListening(port)) {
                return;
            }
            //noinspection BusyWait
            Thread.sleep(100);
        }
        throw new AssertionError("serve process did not start listening on 127.0.0.1:" + port
                + "; output:\n" + childOutput);
    }
}
