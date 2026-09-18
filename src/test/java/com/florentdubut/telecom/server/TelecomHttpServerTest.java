package com.florentdubut.telecom.server;

import com.sun.net.httpserver.HttpServer;
import com.florentdubut.telecom.network.CoverageService;
import com.florentdubut.telecom.network.NetworkEdge;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.network.TrafficSession;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelecomHttpServerTest {
    private final TelecomHttpServer server = new TelecomHttpServer();
    private HttpClient client;
    private String base;
    private int port;
    @TempDir
    Path worldDirectory;

    @BeforeEach
    void start() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        base = "http://127.0.0.1:" + port;
        System.setProperty("telecom.http.port", Integer.toString(port));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        server.start(null); // HTTP validation must not need a running world.
    }

    @AfterEach
    void stop() {
        server.stop();
        client.close();
        for (String key : new String[]{"enabled", "bind", "port", "origins"}) System.clearProperty("telecom.http." + key);
    }

    private HttpResponse<String> request(String method, String path, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3));
        if (headers.length > 0) builder.headers(headers);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Object field(String name) throws Exception {
        var field = TelecomHttpServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(server);
    }

    @Test
    void removedSessionEndpointReturnsNotFoundWithoutSchedulingMinecraft() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        var jobs = queueWorldServer(minecraft);
        for (String method : new String[]{"GET", "POST", "OPTIONS"}) {
            assertEquals(404, request(method, "/api/session", null, "Origin", base).statusCode());
        }
        assertTrue(jobs.isEmpty());
        verify(minecraft, never()).execute(any(Runnable.class));
        verify(minecraft, never()).overworld();
    }

    private void startWorldServer(MinecraftServer minecraft) {
        when(minecraft.getWorldPath(LevelResource.ROOT)).thenReturn(worldDirectory);
        server.stop();
        server.start(minecraft);
    }

    private ArrayBlockingQueue<Runnable> queueWorldServer(MinecraftServer minecraft) {
        ArrayBlockingQueue<Runnable> jobs = new ArrayBlockingQueue<>(1);
        doAnswer(invocation -> { jobs.add(invocation.getArgument(0)); return null; }).when(minecraft).execute(any(Runnable.class));
        startWorldServer(minecraft);
        return jobs;
    }

    private static void assertPending(HttpResponse<String> response) {
        assertEquals(202, response.statusCode(), response.body());
        assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        var json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("pending", json.get("status").getAsString());
        assertFalse(json.has("error"));
    }

    private static void assertAbsent(HttpResponse<String> response) {
        assertEquals(204, response.statusCode(), response.body());
        assertEquals("30", response.headers().firstValue("Retry-After").orElseThrow());
        assertEquals("", response.body());
    }

    private static void runQueuedJob(ArrayBlockingQueue<Runnable> jobs) throws Exception {
        Runnable job = jobs.poll(2, TimeUnit.SECONDS);
        assertNotNull(job, "Expected a Minecraft callback");
        job.run(); // Static Minecraft mocks are deliberately confined to the JUnit thread.
        assertTrue(jobs.isEmpty());
    }

    private AtomicLong advanceableReadClock() throws Exception {
        AtomicLong offset = new AtomicLong();
        var reads = TelecomHttpServer.class.getDeclaredField("reads");
        reads.setAccessible(true);
        reads.set(server, new HttpReadQueue(() -> System.nanoTime() + offset.get()));
        return offset;
    }

    @Test
    void defaultsToIpv4LoopbackAndValidatesMutationsWithoutCredentials() throws Exception {
        assertEquals("127.0.0.1", ((HttpServer) field("server")).getAddress().getAddress().getHostAddress());
        assertEquals(503, request("GET", "/api/network", null).statusCode());
        assertEquals(415, request("POST", "/api/speedtest", "{}").statusCode());
        assertEquals(400, request("POST", "/api/speedtest", "{}", "Content-Type", "application/json").statusCode());
        var page = request("GET", "/", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void rejectsOriginsHostsMethodsAndTraversal() throws Exception {
        assertEquals(403, request("GET", "/api/network", null, "Origin", "https://evil.example").statusCode());
        assertEquals(403, request("GET", "/api/network", null, "Origin", "null").statusCode());
        assertEquals(403, request("GET", "/api/network", null, "Host", "evil.example").statusCode());
        assertEquals(405, request("DELETE", "/api/network", null).statusCode());
        assertEquals(405, request("GET", "/api/speedtest", null).statusCode());
        assertEquals(405, request("DELETE", "/api/zone-jobs", null).statusCode());
        assertEquals(405, request("GET", "/api/zone-jobs/cancel", null).statusCode());
        assertEquals(404, request("GET", "/api/network/extra", null).statusCode());
        assertEquals(404, request("GET", "/%2e%2e/build.gradle", null).statusCode());
    }

    @Test
    void preflightChecksExactOriginMethodAndHeaders() throws Exception {
        var response = request("OPTIONS", "/api/speedtest", null, "Origin", base,
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type");
        assertEquals(204, response.statusCode());
        assertEquals(base, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("POST", response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
        for (String path : new String[]{"/api/zone-jobs", "/api/zone-jobs/cancel"}) {
            assertEquals(204, request("OPTIONS", path, null, "Origin", base,
                    "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type").statusCode());
        }
        assertEquals(403, request("OPTIONS", "/api/speedtest", null, "Origin", base,
                "Access-Control-Request-Method", "DELETE").statusCode());
        assertEquals(403, request("OPTIONS", "/api/network", null, "Origin", base,
                "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "x-untrusted").statusCode());
        assertEquals(403, request("OPTIONS", "/api/speedtest", null, "Origin", "https://evil.example",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type").statusCode());
    }

    @Test
    void bodiesAndDurationsAreBoundedWithoutCredentials() throws Exception {
        assertEquals(415, request("POST", "/api/speedtest", "{}").statusCode());
        assertEquals(413, request("POST", "/api/speedtest", "x".repeat(4097),
                "Content-Type", "application/json").statusCode());
        var chunked = HttpRequest.newBuilder(URI.create(base + "/api/speedtest"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[4097]))).build();
        assertEquals(413, client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode());
        for (String body : new String[]{"[]", "{}", "{", "{\"pos\":\"1\",\"duration\":301}",
                "{\"pos\":\"1\",\"duration\":300.5}", "{\"pos\":\"9223372036854775808\",\"duration\":300}"}) {
            assertEquals(400, request("POST", "/api/speedtest", body,
                    "Content-Type", "application/json").statusCode(), body);
        }
        for (int ticks : new int[]{300, 600, 1200, 6000, 12000}) {
            assertEquals(503, request("POST", "/api/speedtest", "{\"pos\":\"-9223372036854775808\",\"duration\":"
                    + ticks + ",\"maxDown\":\"ignored\",\"maxUp\":-1}",
                    "Content-Type", "application/json").statusCode());
        }
    }

    @Test
    void malformedTileCoordinatesNeverReachMinecraft() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        var jobs = queueWorldServer(minecraft);
        for (String query : new String[]{"", "?cx=1", "?cx=x&cz=0", "?cx=1&cx=2&cz=0", "?cx=2147483647&cz=0"}) {
            assertEquals(400, request("GET", "/api/tile" + query, null).statusCode());
        }
        verify(minecraft, never()).execute(any(Runnable.class));
        verify(minecraft, never()).overworld();
        assertTrue(jobs.isEmpty());
        server.stop();
        server.start(null);
        assertEquals(503, request("GET", "/api/tile?cx=-1&cz=0", null).statusCode());
    }

    @Test
    void coverageFiltersAreValidatedBeforeScheduling() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        var jobs = queueWorldServer(minecraft);
        for (String query : new String[]{"", "?tx=0", "?tx=0&tz=0&step=1", "?tx=0&tz=0&tx=1",
                "?tx=2147483647&tz=0", "?tx=0&tz=0&technology=6G", "?tx=0&tz=0&band=missing",
                "?tx=0&tz=0&technology=4G&band=G5_700", "?tx=0&tz=0&y=oops", "?tx=0&tz=0&unknown=true"}) {
            assertEquals(400, request("GET", "/api/coverage" + query, null).statusCode(), query);
        }
        verify(minecraft, never()).execute(any(Runnable.class));
        verify(minecraft, never()).overworld();
        assertTrue(jobs.isEmpty());
        server.stop();
        server.start(null);
        assertEquals(503, request("GET", "/api/coverage?tx=-1&tz=0", null).statusCode());
        assertEquals(503, request("GET", "/api/coverage/options", null).statusCode());
        assertEquals(405, request("POST", "/api/coverage?tx=0&tz=0", null).statusCode());
    }

    @Test
    void coverageRequestsOnlyScheduleBoundedJobsAndReportPending() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            String path = "/api/coverage?tx=-1&tz=0&step=64&y=70";
            assertPending(request("GET", path, null));
            verify(minecraft, never()).overworld();
            verify(level, never()).getChunkSource();
            runQueuedJob(jobs);
            var result = request("GET", path, null);
            assertEquals(200, result.statusCode());
            var json = JsonParser.parseString(result.body()).getAsJsonObject();
            assertEquals("pending", json.get("status").getAsString());
            assertEquals(-128, json.get("originX").getAsInt());
            assertTrue(json.get("revision").getAsJsonPrimitive().isString());
            verify(level, never()).getChunkSource();
            verify(minecraft, times(1)).execute(any(Runnable.class));
        } finally {
            CoverageService.clear();
        }
    }

    @Test
    void getBodiesAndUnknownEndpointsAreRejectedBeforeQueueing() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        var jobs = queueWorldServer(minecraft);
        for (String path : new String[]{"/api/network", "/api/player", "/api/nperf_map",
                "/api/tile?cx=0&cz=0", "/api/coverage?tx=0&tz=0", "/api/coverage/options", "/api/map-image", "/api/zone-jobs", "/api/speedtest/servers?pos=0"}) {
            assertEquals(400, request("GET", path, "{}").statusCode(), path);
            assertEquals(413, request("GET", path, "x".repeat(4097)).statusCode(), path);
            var chunked = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3))
                    .method("GET", HttpRequest.BodyPublishers.ofInputStream(
                            () -> new ByteArrayInputStream(new byte[]{1}))).build();
            assertEquals(400, client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode(), path);
        }
        for (String path : new String[]{"/api/missing", "/api/network/extra", "/api/tile/extra",
                "/api/coverage/extra", "/api/coverage/options/extra"}) {
            assertEquals(404, request("GET", path, null).statusCode(), path);
        }
        verify(minecraft, never()).execute(any(Runnable.class));
        verify(minecraft, never()).overworld();
        assertTrue(jobs.isEmpty());
    }

    @Test
    void invalidBodiesNeverQueueEvenWithAWorldWithoutCredentials() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        var jobs = queueWorldServer(minecraft);
        assertEquals(415, request("POST", "/api/speedtest", "{}").statusCode());
        assertEquals(413, request("POST", "/api/speedtest", "x".repeat(4097),
                "Content-Type", "application/json").statusCode());
        for (String body : new String[]{"{}", "[]", "{", "{\"pos\":\"1\",\"duration\":301}",
                "{\"pos\":\"1\",\"duration\":300.5}", "{\"pos\":\"9223372036854775808\",\"duration\":300}"}) {
            assertEquals(400, request("POST", "/api/speedtest", body,
                    "Content-Type", "application/json").statusCode(), body);
        }
        verify(minecraft, never()).execute(any(Runnable.class));
        verify(minecraft, never()).overworld();
        assertTrue(jobs.isEmpty());
    }

    @Test
    void coverageAdvertisesFineStepsAndIndependentTechnologies() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
        var jobs = queueWorldServer(minecraft);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            assertPending(request("GET", "/api/coverage/options", null));
            runQueuedJob(jobs);
            var options = JsonParser.parseString(request("GET", "/api/coverage/options", null).body()).getAsJsonObject();
            assertEquals(-3, options.get("minLevel").getAsInt());
            assertEquals(16, options.get("maxSamplesPerSide").getAsInt());
            assertTrue(options.get("sharedTechnologies").getAsBoolean());
            for (int step : new int[]{1, 8, 16}) assertTrue(options.getAsJsonArray("steps").contains(new com.google.gson.JsonPrimitive(step)));
            assertEquals(4, options.getAsJsonArray("technologies").size());
            String path = "/api/coverage?tx=-1&tz=0&step=1&level=-3&y=64&technology=4G";
            assertPending(request("GET", path, null));
            runQueuedJob(jobs);
            var response = request("GET", path, null);
            assertEquals(200, response.statusCode());
            var tile = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(16, tile.get("tileSize").getAsInt());
            assertEquals(1, tile.get("step").getAsInt());
            assertEquals("4G", tile.get("technologyFilter").getAsString());
            assertEquals(options.get("modelRevision"), tile.get("modelRevision"));
        } finally {
            CoverageService.clear();
        }
    }

    @Test
    void aPausedOldWorldCannotBlockReadsAfterRestart() throws Exception {
        MinecraftServer previous = mock(MinecraftServer.class);
        var oldJobs = queueWorldServer(previous);
        assertPending(request("GET", "/api/network", null));

        MinecraftServer current = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(current.overworld()).thenReturn(level);
        var currentJobs = queueWorldServer(current);
        assertPending(request("GET", "/api/network", null));
        assertEquals(1, currentJobs.size());

        runQueuedJob(oldJobs);
        verify(previous, never()).overworld();
        assertPending(request("GET", "/api/network", null));
        verify(current, times(1)).execute(any(Runnable.class));
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(new TelecomNetworkGraph());
            runQueuedJob(currentJobs);
            assertEquals(200, request("GET", "/api/network", null).statusCode());
        }
    }

    @Test
    void readySnapshotsRemainConsumableWhileAnotherKeyIsQueued() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(new TelecomNetworkGraph());
            assertPending(request("GET", "/api/network", null));
            assertPending(request("GET", "/api/nperf_map", null));
            assertPending(request("GET", "/api/player", null));
            verify(minecraft, times(1)).execute(any(Runnable.class));
            runQueuedJob(jobs);

            assertPending(request("GET", "/api/nperf_map", null));
            var network = request("GET", "/api/network", null);
            assertEquals(200, network.statusCode());
            assertEquals(((TerrainTileStore) field("tileStore")).id(),
                    JsonParser.parseString(network.body()).getAsJsonObject().get("mapId").getAsString());
            assertPending(request("GET", "/api/network", null));
            verify(minecraft, times(2)).execute(any(Runnable.class));
            assertEquals(1, jobs.size());
            runQueuedJob(jobs);
            var coverage = request("GET", "/api/nperf_map", null);
            assertEquals(200, coverage.statusCode());
            assertTrue(JsonParser.parseString(coverage.body()).getAsJsonArray().isEmpty());

            assertPending(request("GET", "/api/player", null));
            runQueuedJob(jobs);
            assertAbsent(request("GET", "/api/player", null));
            assertPending(request("GET", "/api/network", null));
            runQueuedJob(jobs);
            assertEquals(200, request("GET", "/api/network", null).statusCode());
            verify(minecraft, times(4)).execute(any(Runnable.class));
            verify(minecraft, times(4)).overworld();
        }
    }

    @Test
    void playerSnapshotIsDetachedAndOnlyReadOnTheQueuedThread() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        var player = mock(net.minecraft.server.level.ServerPlayer.class);
        when(minecraft.overworld()).thenReturn(level);
        when(level.players()).thenReturn(java.util.List.of(player));
        when(player.getBlockX()).thenReturn(-123);
        when(player.getBlockZ()).thenReturn(456);
        var jobs = queueWorldServer(minecraft);
        assertPending(request("GET", "/api/player", null));
        verifyNoInteractions(level, player);
        runQueuedJob(jobs);
        when(player.getBlockX()).thenReturn(999);
        clearInvocations(level, player);
        var response = request("GET", "/api/player", null);
        assertEquals(200, response.statusCode());
        var json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(-123, json.get("x").getAsInt());
        assertEquals(456, json.get("z").getAsInt());
        verifyNoInteractions(level, player);
        verify(minecraft, times(1)).execute(any(Runnable.class));
    }

    @Test
    void completedReadsAreRetainedUntilConsumptionButExpireAfterThirtySeconds() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        AtomicLong offset = advanceableReadClock();
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(new TelecomNetworkGraph());
            assertPending(request("GET", "/api/network", null));
            runQueuedJob(jobs);
            offset.addAndGet(TimeUnit.SECONDS.toNanos(29));
            assertEquals(200, request("GET", "/api/network", null).statusCode());
            verify(minecraft, times(1)).execute(any(Runnable.class));
            assertPending(request("GET", "/api/network", null));
            runQueuedJob(jobs);
            offset.addAndGet(TimeUnit.SECONDS.toNanos(30));
            assertPending(request("GET", "/api/network", null));
            verify(minecraft, times(3)).execute(any(Runnable.class));
            runQueuedJob(jobs);
            assertEquals(200, request("GET", "/api/network", null).statusCode());
        }
    }

    @Test
    void delayedCoverageDeliverySubtractsSnapshotAgeAndClampsValidityAtZero() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        AtomicLong offset = advanceableReadClock();
        String path = "/api/coverage?tx=0&tz=0&step=64";
        try (var coverage = mockStatic(CoverageService.class)) {
            coverage.when(() -> CoverageService.request(eq(level), any(CoverageService.Request.class), anyLong()))
                    .thenReturn("{\"status\":\"ready\",\"validForMs\":1000,\"generatedAt\":12345,\"revision\":\"42\"}");
            assertPending(request("GET", path, null));
            coverage.verifyNoInteractions();
            runQueuedJob(jobs);
            offset.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
            var response = request("GET", path, null);
            assertEquals(200, response.statusCode());
            var json = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals("ready", json.get("status").getAsString());
            assertTrue(json.get("validForMs").getAsLong() <= 750);
            assertTrue(json.get("validForMs").getAsLong() > 0);
            assertEquals(12345, json.get("generatedAt").getAsLong());
            assertEquals("42", json.get("revision").getAsString());

            assertPending(request("GET", path, null));
            runQueuedJob(jobs);
            offset.addAndGet(TimeUnit.SECONDS.toNanos(2));
            response = request("GET", path, null);
            assertEquals(200, response.statusCode());
            json = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals("ready", json.get("status").getAsString());
            assertEquals(0, json.get("validForMs").getAsLong());
            coverage.verify(() -> CoverageService.request(eq(level), any(CoverageService.Request.class), anyLong()), times(2));
            verify(minecraft, times(2)).execute(any(Runnable.class));
        }
    }

    @Test
    void explicitPublicBindingAllowsReadsAndMutationValidationButKeepsHostAndOriginChecks() throws Exception {
        server.stop();
        System.setProperty("telecom.http.bind", "0.0.0.0");
        String origin = "http://localhost:" + port;
        System.setProperty("telecom.http.origins", base + "," + origin);
        server.start(null);
        assertTrue(((HttpServer) field("server")).getAddress().getAddress().isAnyLocalAddress());
        for (String route : new String[]{"network", "player", "nperf_map", "tile?cx=0&cz=0", "map-image",
                "coverage?tx=0&tz=0", "coverage/options", "zone-jobs", "speedtest/servers?pos=0"}) {
            assertEquals(503, request("GET", "/api/" + route, null).statusCode(), route);
            assertEquals(503, request("GET", "/api/" + route, null, "Origin", origin).statusCode(), route);
            assertEquals(403, request("GET", "/api/" + route, null, "Origin", "https://evil.example").statusCode(), route);
            assertEquals(403, request("GET", "/api/" + route, null, "Host", "evil.example", "Origin", origin).statusCode(), route);
        }
        for (String route : new String[]{"speedtest", "zone-jobs", "zone-jobs/cancel"}) {
            assertEquals(400, request("POST", "/api/" + route, "{}", "Content-Type", "application/json", "Origin", origin).statusCode(), route);
            assertEquals(403, request("POST", "/api/" + route, "{}", "Content-Type", "application/json", "Origin", "https://evil.example").statusCode(), route);
            assertEquals(403, request("POST", "/api/" + route, "{}", "Content-Type", "application/json", "Host", "evil.example", "Origin", origin).statusCode(), route);
        }
        assertEquals(200, request("GET", "/", null).statusCode());
        assertEquals(200, request("GET", "/", null, "Host", "localhost:" + port, "Origin", origin).statusCode());
        assertEquals(404, request("GET", "/api/session", null, "Origin", origin).statusCode());
    }

    @Test
    void workersQueueShutdownAndRestartAreBounded() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field("workers");
        assertEquals(4, executor.getMaximumPoolSize());
        assertEquals(32, executor.getQueue().remainingCapacity());
        Object oldGate = field("jobSlot");
        server.stop();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        server.start(null);
        assertNotSame(oldGate, field("jobSlot"));
        assertEquals(200, request("GET", "/", null).statusCode());
    }

    @Test
    void invalidConfigurationAndDisabledModeDoNotListen() throws Exception {
        server.stop();
        System.setProperty("telecom.http.enabled", "false");
        server.start(null);
        assertNull(field("server"));
        System.setProperty("telecom.http.enabled", "true");
        System.setProperty("telecom.http.origins", "*");
        server.start(null);
        assertNull(field("server"));
        System.clearProperty("telecom.http.origins");
        System.setProperty("telecom.http.port", "65536");
        server.start(null);
        assertNull(field("server"));
    }

    @Test
    void pausedReadsReturnImmediatelyAndShareOneCallbackAcrossAllKeys() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        assertEquals(200, request("GET", "/", null).statusCode());
        assertTimeout(Duration.ofMillis(500), () -> assertPending(request("GET", "/api/network", null)));
        assertEquals(1, jobs.size());
        // More than 100 reads, with simultaneous distinct keys, while Minecraft is paused.
        // Small waves exercise concurrency without testing the separate HTTP admission limit.
        assertTimeout(Duration.ofSeconds(10), () -> {
            for (int wave = 0; wave < 16; wave++) {
                var responses = new ArrayList<CompletableFuture<HttpResponse<String>>>();
                for (int i = 0; i < 8; i++) {
                    String path = switch (i % 4) {
                        case 0 -> "/api/network";
                        case 1 -> "/api/coverage?tx=" + (wave * 8 + i) + "&tz=0";
                        case 2 -> "/api/tile?cx=" + (wave * 8 + i) + "&cz=0";
                        default -> "/api/coverage/options";
                    };
                    responses.add(client.sendAsync(HttpRequest.newBuilder(URI.create(base + path))
                            .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString()));
                }
                for (var response : responses) assertPending(response.get(2, TimeUnit.SECONDS));
                assertEquals(1, jobs.size());
            }
        });
        Thread.sleep(800); // A paused read must survive the former 750 ms mutation deadline.
        assertPending(request("GET", "/api/network", null));
        assertEquals(1, jobs.size());
        verify(minecraft, never()).overworld();
        verifyNoInteractions(level);
        verify(minecraft, times(1)).execute(any(Runnable.class));
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(new TelecomNetworkGraph());
            runQueuedJob(jobs);
            assertEquals(200, request("GET", "/api/network", null).statusCode());
            assertPending(request("GET", "/api/coverage/options", null));
            runQueuedJob(jobs);
            assertEquals(200, request("GET", "/api/coverage/options", null).statusCode());
            verify(minecraft, times(2)).execute(any(Runnable.class));
        } finally {
            CoverageService.clear();
        }
    }

    @Test
    void unloadedTileOnlyLooksUpLoadedChunksOnScheduledThread() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(minecraft.overworld()).thenReturn(level);
        when(level.getChunkSource()).thenReturn(chunks);
        var jobs = queueWorldServer(minecraft);
        String path = "/api/tile?cx=3&cz=-4";
        assertPending(request("GET", path, null));
        verify(minecraft, never()).overworld();
        verifyNoInteractions(level, chunks);
        runQueuedJob(jobs);
        long before = System.nanoTime();
        assertAbsent(request("GET", path, null));
        long after = System.nanoTime();
        verify(chunks).getChunkNow(3, -4);
        verifyNoMoreInteractions(chunks);
        var absent = (Map<?, ?>) field("unavailableTiles");
        long retryAt = (Long) absent.get(net.minecraft.world.level.ChunkPos.asLong(3, -4));
        assertTrue(retryAt >= before + TimeUnit.SECONDS.toNanos(30));
        assertTrue(retryAt <= after + TimeUnit.SECONDS.toNanos(30));
        assertNull(((TerrainTileStore) field("tileStore")).read(3, -4));
        assertTrue(((Map<?, ?>) field("tiles")).isEmpty());
        try (var files = Files.walk(worldDirectory)) {
            assertFalse(files.anyMatch(file -> file.toString().endsWith(".png")));
        }
        clearInvocations(minecraft, level, chunks);
        for (int i = 0; i < 5; i++) assertAbsent(request("GET", path, null));
        assertEquals(retryAt, absent.get(net.minecraft.world.level.ChunkPos.asLong(3, -4)));
        assertTrue(jobs.isEmpty());
        verifyNoInteractions(minecraft, level, chunks);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableTilesAreBoundedAndExpiredEntriesCanBeRetried() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(minecraft.overworld()).thenReturn(level);
        when(level.getChunkSource()).thenReturn(chunks);
        var jobs = queueWorldServer(minecraft);
        var unavailable = (Map<Long, Long>) field("unavailableTiles");
        // Seed the full cache rather than issuing 2048 unrelated HTTP round trips.
        synchronized (field("tiles")) {
            for (int cx = 0; cx < 1024; cx++) {
                unavailable.put(net.minecraft.world.level.ChunkPos.asLong(cx, 0),
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            }
        }
        String path = "/api/tile?cx=1024&cz=0";
        assertPending(request("GET", path, null));
        runQueuedJob(jobs);
        assertAbsent(request("GET", path, null));
        synchronized (field("tiles")) {
            assertEquals(1024, unavailable.size());
            assertFalse(unavailable.containsKey(net.minecraft.world.level.ChunkPos.asLong(0, 0)));
            assertTrue(unavailable.containsKey(net.minecraft.world.level.ChunkPos.asLong(1024, 0)));
            unavailable.put(net.minecraft.world.level.ChunkPos.asLong(1024, 0), System.nanoTime() - 1);
        }
        assertPending(request("GET", path, null));
        runQueuedJob(jobs);
        assertAbsent(request("GET", path, null));
        verify(chunks, times(2)).getChunkNow(1024, 0);
        verifyNoMoreInteractions(chunks);
        assertNull(((TerrainTileStore) field("tileStore")).read(1024, 0));
        assertEquals(1024, unavailable.size());
    }

    @Test
    void stoppedReadCallbacksCannotReadTheWorldOrPublishIntoTheRestartedServer() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        assertPending(request("GET", "/api/network", null));
        startWorldServer(minecraft);
        assertEquals(1, jobs.size());
        verify(minecraft, times(1)).execute(any(Runnable.class));
        runQueuedJob(jobs);
        verify(minecraft, never()).overworld();
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(new TelecomNetworkGraph());
            assertPending(request("GET", "/api/network", null));
            runQueuedJob(jobs);
            assertEquals(200, request("GET", "/api/network", null).statusCode());
            verify(minecraft, times(2)).execute(any(Runnable.class));
            verify(minecraft, times(1)).overworld();
        }
    }

    @Test
    void terrainIsRenderedOnceAndSurvivesMemoryEvictionAndRestart() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        var chunk = mock(net.minecraft.world.level.chunk.LevelChunk.class);
        when(minecraft.overworld()).thenReturn(level);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(-1, 2)).thenReturn(chunk);
        when(chunk.getHeight(any(), anyInt(), anyInt())).thenReturn(64);
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        var jobs = queueWorldServer(minecraft);
        TerrainTileStore store = spy((TerrainTileStore) field("tileStore"));
        var storeField = TelecomHttpServer.class.getDeclaredField("tileStore");
        storeField.setAccessible(true);
        storeField.set(server, store);
        String mapId = store.id();
        var tileRequest = HttpRequest.newBuilder(URI.create(base + "/api/tile?cx=-1&cz=2&map=" + mapId)).GET().build();
        var first = client.sendAsync(tileRequest, HttpResponse.BodyHandlers.ofString());
        var duplicate = client.sendAsync(tileRequest, HttpResponse.BodyHandlers.ofString());
        assertPending(first.get(2, TimeUnit.SECONDS));
        assertPending(duplicate.get(2, TimeUnit.SECONDS));
        assertEquals(1, jobs.size());
        verifyNoInteractions(chunks);
        verify(minecraft, never()).overworld();
        verify(store, never()).storeIfAbsent(anyInt(), anyInt(), any(byte[].class));
        runQueuedJob(jobs);
        var firstReady = client.sendAsync(tileRequest, HttpResponse.BodyHandlers.ofByteArray());
        var duplicateReady = client.sendAsync(tileRequest, HttpResponse.BodyHandlers.ofByteArray());
        var generated = firstReady.get(3, TimeUnit.SECONDS);
        assertEquals(200, generated.statusCode());
        assertEquals("image/png", generated.headers().firstValue("Content-Type").orElseThrow());
        var repeated = duplicateReady.get(3, TimeUnit.SECONDS);
        assertEquals(200, repeated.statusCode());
        assertArrayEquals(generated.body(), repeated.body());
        verify(minecraft, times(1)).execute(any(Runnable.class));
        verify(chunks, times(1)).getChunkNow(-1, 2);
        verify(store, times(1)).storeIfAbsent(eq(-1), eq(2), any(byte[].class));
        assertArrayEquals(generated.body(), store.read(-1, 2));
        var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(generated.body()));
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());

        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
        clearInvocations(minecraft, level, chunks, chunk);
        var memoryHit = assertTimeout(Duration.ofMillis(500),
                () -> client.send(tileRequest, HttpResponse.BodyHandlers.ofByteArray()));
        assertEquals(200, memoryHit.statusCode());
        assertArrayEquals(generated.body(), memoryHit.body());
        verifyNoInteractions(minecraft, level, chunks, chunk);

        // The world no longer has the chunk: neither disk nor memory hits may ask Minecraft.
        when(chunks.getChunkNow(-1, 2)).thenReturn(null);
        clearInvocations(minecraft, level, chunks, chunk);
        ((java.util.Map<?, ?>) field("tiles")).clear();
        var diskHit = assertTimeout(Duration.ofMillis(500),
                () -> client.send(tileRequest, HttpResponse.BodyHandlers.ofByteArray()));
        assertEquals(200, diskHit.statusCode());
        assertArrayEquals(generated.body(), diskHit.body());
        verifyNoInteractions(minecraft, level, chunks, chunk);
        startWorldServer(minecraft);
        assertEquals(mapId, ((TerrainTileStore) field("tileStore")).id());
        var restored = client.send(tileRequest, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, restored.statusCode());
        assertArrayEquals(generated.body(), restored.body());
        verify(minecraft, never()).execute(any(Runnable.class));
        verifyNoInteractions(level, chunks, chunk);
        assertTrue(jobs.isEmpty());
    }

    @Test
    void oneGlobalImageUsesSavedSourcesAndSupportsConditionalFetches() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        startWorldServer(minecraft);
        var store = (TerrainTileStore) field("tileStore");
        var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff00ff00);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        store.storeIfAbsent(-1, 0, bytes.toByteArray());
        String path = "/api/map-image?map=" + store.id();
        var map = (WorldMapImageStore) field("worldMap");
        map.invalidate(-1, 0);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        WorldMapImageStore.Snapshot ready = null;
        while (ready == null && System.nanoTime() < deadline) {
            try {
                var candidate = map.snapshot();
                if (!candidate.empty()) ready = candidate;
            } catch (WorldMapImageStore.Pending ignored) { }
            if (ready == null) Thread.sleep(10);
        }
        assertNotNull(ready);
        var response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(response.body()));
        assertEquals(16, decoded.getWidth());
        assertEquals(0xff00ff00, decoded.getRGB(0, 0));
        assertEquals("-16", response.headers().firstValue("X-Map-Origin-X").orElseThrow());
        assertEquals("1", response.headers().firstValue("X-Map-Scale").orElseThrow());
        assertEquals(ready.revision(), response.headers().firstValue("X-Map-Revision").orElseThrow());
        String etag = response.headers().firstValue("ETag").orElseThrow();
        var unchanged = client.send(HttpRequest.newBuilder(URI.create(base + path)).header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(304, unchanged.statusCode());
        assertEquals(0, unchanged.body().length);
        assertEquals(409, request("GET", "/api/map-image?map=wrong-world", null).statusCode());
        assertEquals(410, request("GET", "/api/terrain?level=0&x=0&z=0", null).statusCode());
        for (String query : new String[]{"?level=0", "?map=one&map=two", "?x=0"}) {
            assertEquals(400, request("GET", "/api/map-image" + query, null).statusCode());
        }
        verify(minecraft, never()).execute(any(Runnable.class));
        verify(minecraft, never()).overworld();
    }

    @Test
    void worldSwitchRejectsOldMapIdsBeforeReadingMinecraft() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        startWorldServer(minecraft);
        String firstId = ((TerrainTileStore) field("tileStore")).id();
        worldDirectory = Files.createDirectory(worldDirectory.resolve("another-world"));
        startWorldServer(minecraft);
        assertNotEquals(firstId, ((TerrainTileStore) field("tileStore")).id());
        assertEquals(409, request("GET", "/api/tile?cx=0&cz=0&map=" + firstId, null).statusCode());
        verify(minecraft, never()).execute(any(Runnable.class));
    }

    @Test
    void corruptSavedTileIsNotSilentlyRecomputed() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        startWorldServer(minecraft);
        Path region = Files.createDirectory(worldDirectory.resolve("telecom-map/minecraft/overworld/0_0"));
        Path tile = region.resolve("0_0.png");
        byte[] invalid = {1, 2, 3};
        Files.write(tile, invalid);
        assertEquals(503, request("GET", "/api/tile?cx=0&cz=0", null).statusCode());
        assertArrayEquals(invalid, Files.readAllBytes(tile));
        verify(minecraft, never()).execute(any(Runnable.class));
    }

    @Test
    void networkSnapshotIncludesPersistentMapIdentity() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        startWorldServer(minecraft);
        var method = TelecomHttpServer.class.getDeclaredMethod("networkSnapshot", ServerLevel.class, long.class);
        method.setAccessible(true);
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            var json = JsonParser.parseString((String) method.invoke(server, level, Long.MAX_VALUE)).getAsJsonObject();
            assertEquals(((TerrainTileStore) field("tileStore")).id(), json.get("mapId").getAsString());
        }
    }

    @Test
    void networkCapacitiesAndActualUsageAreDetachedAuthoritativeDirectionalAndShared() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        TelecomNetworkGraph graph = mock(TelecomNetworkGraph.class);
        var nodes = new ArrayList<NetworkNode>();
        for (var type : NetworkNode.NodeType.values()) {
            NetworkNode node = new NetworkNode(new BlockPos(type.ordinal(), 64, 0), type);
            node.setCapacityDown(1000);
            node.setCapacityUp(100);
            node.setCurrentUsageDown(0);
            node.setCurrentUsageUp(100);
            nodes.add(node);
        }
        NetworkEdge fiber = new NetworkEdge(BlockPos.ZERO, new BlockPos(1, 64, 0), 100, 250,
                NetworkEdge.EdgeType.FIBER, java.util.List.of());
        NetworkEdge copper = new NetworkEdge(BlockPos.ZERO, new BlockPos(2, 64, 0), 1000, 250,
                NetworkEdge.EdgeType.COPPER, java.util.List.of());
        fiber.setCurrentUsageDown(50);
        fiber.setCurrentUsageUp(50);
        copper.setCurrentUsageDown(200);
        copper.setCurrentUsageUp(300);
        when(graph.getNodes()).thenReturn(nodes);
        when(graph.getEdges()).thenReturn(java.util.List.of(fiber, copper));
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            assertPending(request("GET", "/api/network", null));
            verifyNoInteractions(graph);
            runQueuedJob(jobs);
            // Changes after capture cannot affect the queued HTTP snapshot.
            nodes.forEach(node -> { node.setCapacityUp(1); node.setCurrentUsageUp(0); });
            fiber.setCurrentUsageDown(0);
            copper.setCurrentUsageUp(0);
            var response = request("GET", "/api/network", null);
            assertEquals(200, response.statusCode());
            var json = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(nodes.size(), json.getAsJsonArray("nodes").size());
            for (var value : json.getAsJsonArray("nodes")) {
                var node = value.getAsJsonObject();
                assertEquals(1000, node.get("capacityDown").getAsInt());
                assertEquals(100, node.get("capacityUp").getAsInt());
                assertEquals(1000, node.get("capacity").getAsInt());
                assertEquals("DIRECTIONAL", node.get("capacityMode").getAsString());
                assertEquals(0, node.get("usageDown").getAsInt());
                assertEquals(100, node.get("usageUp").getAsInt());
            }
            var fiberJson = json.getAsJsonArray("edges").get(0).getAsJsonObject();
            assertEquals(100, fiberJson.get("capacity").getAsInt());
            assertEquals(100, fiberJson.get("nominalCapacity").getAsInt());
            assertEquals("SHARED", fiberJson.get("capacityMode").getAsString());
            assertEquals(50, fiberJson.get("usageDown").getAsInt());
            assertEquals(50, fiberJson.get("usageUp").getAsInt());
            var copperJson = json.getAsJsonArray("edges").get(1).getAsJsonObject();
            assertEquals(250, copperJson.get("length").getAsInt());
            assertEquals(500, copperJson.get("capacity").getAsInt());
            assertEquals(1000, copperJson.get("nominalCapacity").getAsInt());
            assertEquals("SHARED", copperJson.get("capacityMode").getAsString());
            assertEquals(200, copperJson.get("usageDown").getAsInt());
            assertEquals(300, copperJson.get("usageUp").getAsInt());
            verify(minecraft, times(1)).execute(any(Runnable.class));
        }
    }

    @Test
    void snapshotIdentifiersPreserveAllLongBits() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        TelecomNetworkGraph graph = mock(TelecomNetworkGraph.class);
        BlockPos a = new BlockPos(-29999999, -64, 29999999);
        BlockPos b = a.offset(1, 1, -1);
        NetworkNode node = mock(NetworkNode.class);
        when(node.getPosition()).thenReturn(a);
        when(node.getType()).thenReturn(NetworkNode.NodeType.ROUTER);
        when(graph.getNodes()).thenReturn(java.util.List.of(node));
        when(graph.getEdges()).thenReturn(java.util.List.of(new NetworkEdge(a, b, 1000, 2, NetworkEdge.EdgeType.FIBER, java.util.List.of())));
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            var snapshot = TelecomHttpServer.class.getDeclaredMethod("networkSnapshot", ServerLevel.class, long.class);
            snapshot.setAccessible(true);
            String json = (String) snapshot.invoke(server, level, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            var id = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("nodes").get(0).getAsJsonObject().get("id");
            assertTrue(id.getAsJsonPrimitive().isString());
            assertEquals(a.asLong(), Long.parseLong(id.getAsString()));
            var edge = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("edges").get(0).getAsJsonObject();
            assertTrue(edge.get("source").getAsJsonPrimitive().isString());
            assertTrue(edge.get("target").getAsJsonPrimitive().isString());
            assertEquals(Long.toString(a.asLong()), edge.get("source").getAsString());
            assertEquals(Long.toString(b.asLong()), edge.get("target").getAsString());
        }
    }

    @Test
    void antennaSnapshotUsesConfiguredRadioCapacityRatherThanBackhaul() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        NetworkNode node = new NetworkNode(new BlockPos(1, 64, 1), NetworkNode.NodeType.ANTENNA);
        var frequency = com.florentdubut.telecom.network.TelecomFrequency.G4_1800;
        node.setFrequenciesMask(1 << frequency.ordinal());
        node.setRadioConfig(new com.florentdubut.telecom.network.AntennaRadioConfig(3, 0, 0, 30, 50));
        graph.addNode(node);
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            var snapshot = TelecomHttpServer.class.getDeclaredMethod("networkSnapshot", ServerLevel.class, long.class);
            snapshot.setAccessible(true);
            var json = JsonParser.parseString((String) snapshot.invoke(server, level, Long.MAX_VALUE)).getAsJsonObject();
            var antenna = json.getAsJsonArray("nodes").get(0).getAsJsonObject();
            var radio = antenna.getAsJsonArray("frequencies").get(0).getAsJsonObject();
            assertEquals(75, radio.get("max").getAsInt());
            assertEquals(10, radio.get("widthMhz").getAsDouble());
            assertEquals("AIRTIME_DOWN_EQUIVALENT", radio.get("usageMode").getAsString());
            assertEquals(0, radio.get("usage").getAsInt());
            assertEquals(node.getCapacityDown(), antenna.get("capacityDown").getAsInt());
        }
    }

    @Test
    void microwaveSnapshotSeparatesFailedDiagnosticsFromEdgesAndPreservesIdsAndConfiguration() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        TelecomNetworkGraph graph = mock(TelecomNetworkGraph.class);
        BlockPos source = new BlockPos(-29999999, 64, 29999999), target = source.east(100);
        var config = new com.florentdubut.telecom.network.MicrowaveConfig(target, 2, 18, 90, 5, true);
        NetworkNode dish = new NetworkNode(source, NetworkNode.NodeType.MICROWAVE_DISH);
        dish.setMicrowaveConfig(config);
        when(graph.getNodes()).thenReturn(java.util.List.of(dish));
        var cable = new NetworkEdge(source, target, 1000, 100, NetworkEdge.EdgeType.FIBER, java.util.List.of());
        when(graph.getEdges()).thenReturn(java.util.List.of(cable));
        var blocker = source.east(50);
        var broken = new com.florentdubut.telecom.network.MicrowaveLinkService.LinkStatus(source, target, "blocked", 0, 1000, 2, blocker);
        try (var graphs = mockStatic(TelecomNetworkGraph.class);
             var microwave = mockStatic(com.florentdubut.telecom.network.MicrowaveLinkService.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            microwave.when(() -> com.florentdubut.telecom.network.MicrowaveLinkService.links(level))
                    .thenReturn(java.util.List.of(broken));
            var snapshot = TelecomHttpServer.class.getDeclaredMethod("networkSnapshot", ServerLevel.class, long.class);
            snapshot.setAccessible(true);
            var json = JsonParser.parseString((String) snapshot.invoke(server, level, Long.MAX_VALUE)).getAsJsonObject();
            assertEquals(1, json.getAsJsonArray("edges").size());
            assertEquals("FIBER", json.getAsJsonArray("edges").get(0).getAsJsonObject().get("type").getAsString());
            var nodeConfig = json.getAsJsonArray("nodes").get(0).getAsJsonObject().getAsJsonObject("microwave");
            assertTrue(nodeConfig.get("peer").getAsJsonPrimitive().isString());
            assertEquals(Long.toString(target.asLong()), nodeConfig.get("peer").getAsString());
            assertEquals(18, nodeConfig.get("frequencyGhz").getAsInt());
            assertTrue(nodeConfig.get("enabled").getAsBoolean());
            var link = json.getAsJsonArray("microwaveLinks").get(0).getAsJsonObject();
            assertTrue(link.get("source").getAsJsonPrimitive().isString());
            assertEquals(Long.toString(source.asLong()), link.get("source").getAsString());
            assertEquals(Long.toString(target.asLong()), link.get("target").getAsString());
            assertEquals("blocked", link.get("state").getAsString());
            assertEquals(0, link.get("capacityMbps").getAsInt());
            assertEquals(2, link.get("latencyMs").getAsDouble());
            assertEquals(blocker.getX(), link.getAsJsonObject("blocker").get("x").getAsInt());
            assertEquals(target.getZ(), link.getAsJsonObject("targetPos").get("z").getAsInt());
            var unpaired = new com.florentdubut.telecom.network.MicrowaveLinkService.LinkStatus(source, source, "unpaired", 0, 1000, 0, null);
            microwave.when(() -> com.florentdubut.telecom.network.MicrowaveLinkService.links(level))
                    .thenReturn(java.util.Collections.nCopies(129, unpaired));
            json = JsonParser.parseString((String) snapshot.invoke(server, level, Long.MAX_VALUE)).getAsJsonObject();
            assertEquals(128, json.getAsJsonArray("microwaveLinks").size());
            assertTrue(json.getAsJsonArray("microwaveLinks").get(0).getAsJsonObject().get("blocker").isJsonNull());
            verify(level, never()).getBlockEntity(any());
        }
    }

    @Test
    void speedtestDestinationFormatsAndCatalogueRoutesAreValidatedBeforeScheduling() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        var jobs = queueWorldServer(minecraft);
        for (String selection : new String[]{"0", "-1", "1.5", "true", "null", "[]", "{}",
                "\"+1\"", "\"01\"", "\"-0\"", "\" 1\"", "\"1e2\"", "\"9223372036854775808\"", "\"-9223372036854775809\""}) {
            var response = request("POST", "/api/speedtest", "{\"pos\":\"0\",\"duration\":300,\"serverId\":" + selection + "}",
                    "Content-Type", "application/json");
            assertEquals(400, response.statusCode(), selection);
            assertEquals("invalid_request", JsonParser.parseString(response.body()).getAsJsonObject().get("errorCode").getAsString());
        }
        assertEquals(400, request("POST", "/api/speedtest", "{\"pos\":0,\"duration\":300}", "Content-Type", "application/json").statusCode());
        for (String query : new String[]{"", "?pos=", "?pos=x", "?pos=1&pos=2", "?pos=0&unknown=1", "?pos=9223372036854775808", "?pos=%2B1"}) {
            assertEquals(400, request("GET", "/api/speedtest/servers" + query, null).statusCode(), query);
        }
        assertEquals(405, request("POST", "/api/speedtest/servers?pos=0", "{}", "Content-Type", "application/json").statusCode());
        assertEquals(204, request("OPTIONS", "/api/speedtest/servers?pos=0", null, "Origin", base,
                "Access-Control-Request-Method", "GET").statusCode());
        assertEquals(403, request("OPTIONS", "/api/speedtest/servers?pos=0", null, "Origin", base,
                "Access-Control-Request-Method", "POST").statusCode());
        verify(minecraft, never()).execute(any(Runnable.class));
        assertTrue(jobs.isEmpty());
        server.stop();
        server.start(null);
        for (String selection : new String[]{"", "0", "-1", "-9223372036854775808", "9223372036854775807"}) {
            assertEquals(503, request("POST", "/api/speedtest", "{\"pos\":\"0\",\"duration\":300,\"serverId\":\"" + selection + "\"}",
                    "Content-Type", "application/json").statusCode(), selection);
        }
    }

    @Test
    void speedtestCatalogueIsDetachedBoundedAndKeyedByRouterIncludingUnavailableServers() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        BlockPos a = new BlockPos(2, 64, 0), b = new BlockPos(4, 64, 0);
        BlockPos negative = BlockPos.of(Long.MIN_VALUE);
        graph.addNode(new NetworkNode(a, NetworkNode.NodeType.ROUTER));
        graph.addNode(new NetworkNode(b, NetworkNode.NodeType.ROUTER));
        graph.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.SERVER));
        graph.addNode(new NetworkNode(negative, NetworkNode.NodeType.SERVER));
        graph.addEdge(new NetworkEdge(a, BlockPos.ZERO, 1000, 20, NetworkEdge.EdgeType.FIBER, java.util.List.of()));
        String first = "/api/speedtest/servers?pos=" + a.asLong(), second = "/api/speedtest/servers?pos=" + b.asLong();
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            assertPending(request("GET", first, null));
            assertPending(request("GET", second, null));
            verify(minecraft, never()).overworld();
            verify(minecraft, times(1)).execute(any(Runnable.class));
            runQueuedJob(jobs);
            // The completed first router must not change when the live graph changes.
            graph.removeEdgeBetween(a, BlockPos.ZERO);
            var json = JsonParser.parseString(request("GET", first, null).body()).getAsJsonObject();
            assertEquals(Long.toString(a.asLong()), json.get("pos").getAsString());
            assertFalse(json.get("truncated").getAsBoolean());
            var options = json.getAsJsonArray("servers");
            assertEquals(2, options.size());
            assertEquals("0", options.get(0).getAsJsonObject().get("id").getAsString());
            assertTrue(options.get(0).getAsJsonObject().get("available").getAsBoolean());
            assertTrue(options.get(0).getAsJsonObject().get("estimatedPingMs").getAsInt() >= 0);
            assertTrue(options.get(0).getAsJsonObject().get("bandwidthMbps").getAsInt() > 0);
            var unavailable = options.get(1).getAsJsonObject();
            assertTrue(unavailable.get("id").getAsJsonPrimitive().isString());
            assertEquals(Long.toString(Long.MIN_VALUE), unavailable.get("id").getAsString());
            assertEquals(-1, unavailable.get("estimatedPingMs").getAsInt());
            assertEquals("server_unavailable", unavailable.get("reason").getAsString());
            assertPending(request("GET", second, null));
            runQueuedJob(jobs);
            json = JsonParser.parseString(request("GET", second, null).body()).getAsJsonObject();
            assertEquals(Long.toString(b.asLong()), json.get("pos").getAsString());
            for (var option : json.getAsJsonArray("servers")) assertFalse(option.getAsJsonObject().get("available").getAsBoolean());
            for (int i = 0; i < 129; i++) graph.addNode(new NetworkNode(new BlockPos(100 + i, 64, 0), NetworkNode.NodeType.SERVER));
            assertPending(request("GET", first, null));
            runQueuedJob(jobs);
            json = JsonParser.parseString(request("GET", first, null).body()).getAsJsonObject();
            assertEquals(128, json.getAsJsonArray("servers").size());
            assertTrue(json.get("truncated").getAsBoolean());
            assertNull(graph.getSessionByDeviceId(TrafficSession.routerDeviceId(a)));
            String wrongType = "/api/speedtest/servers?pos=0";
            assertPending(request("GET", wrongType, null));
            runQueuedJob(jobs);
            assertEquals(404, request("GET", wrongType, null).statusCode());
        }
    }

    @Test
    void catalogueWorkLimitReturnsExplicitServiceError() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        TelecomNetworkGraph graph = mock(TelecomNetworkGraph.class);
        when(graph.getNode(BlockPos.ZERO)).thenReturn(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.ROUTER));
        when(graph.getSpeedtestServers(BlockPos.ZERO, 0)).thenThrow(new TelecomNetworkGraph.SpeedtestCatalogueLimitException());
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            String path = "/api/speedtest/servers?pos=0";
            assertPending(request("GET", path, null));
            runQueuedJob(jobs);
            var response = request("GET", path, null);
            assertEquals(503, response.statusCode());
            assertEquals("catalogue_limit", JsonParser.parseString(response.body()).getAsJsonObject().get("errorCode").getAsString());
        }
    }

    @Test
    void networkBudgetRefusalIsReportedWithoutConfirmingOrRetryingAMutation() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        var graph = mock(TelecomNetworkGraph.class);
        NetworkNode router = new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.ROUTER);
        router.setIpAddress("10.0.0.1");
        when(graph.getNode(BlockPos.ZERO)).thenReturn(router);
        when(graph.startSpeedtest(BlockPos.ZERO, "10.0.0.1", 1000, 1000, 0, 0, 300, false, null, ""))
                .thenReturn(new TelecomNetworkGraph.SpeedtestStartResult(null, "network_limit"));
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            var response = postSpeedtest(jobs, BlockPos.ZERO, "");
            assertEquals(503, response.statusCode());
            var body = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals("network_limit", body.get("errorCode").getAsString());
            assertFalse(body.has("sessionId"));
            assertTrue(body.get("error").getAsString().contains("budget"));
            verify(graph, times(1)).startSpeedtest(BlockPos.ZERO, "10.0.0.1", 1000, 1000, 0, 0, 300, false, null, "");
        }
    }

    private HttpResponse<String> postSpeedtest(ArrayBlockingQueue<Runnable> jobs, BlockPos router, String serverId) throws Exception {
        Thread.sleep(30); // Respect the mutation admission budget between independent assertions.
        String body = "{\"pos\":\"" + router.asLong() + "\",\"duration\":300"
                + (serverId == null ? "" : ",\"serverId\":\"" + serverId + "\"") + "}";
        var response = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/speedtest"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        runQueuedJob(jobs);
        return response.get(2, TimeUnit.SECONDS);
    }

    @Test
    void explicitZeroAndNegativeServersAreAuthoritativeAndTerminalSnapshotsRetainDestination() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        var jobs = queueWorldServer(minecraft);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        BlockPos a = new BlockPos(2, 64, 0), b = new BlockPos(4, 64, 0), isolated = new BlockPos(6, 64, 0);
        BlockPos negative = BlockPos.of(Long.MIN_VALUE);
        graph.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.SERVER));
        graph.addNode(new NetworkNode(negative, NetworkNode.NodeType.SERVER));
        for (BlockPos pos : java.util.List.of(a, b, isolated)) {
            NetworkNode router = new NetworkNode(pos, NetworkNode.NodeType.ROUTER);
            router.setIpAddress("10.0.0.2");
            graph.addNode(router);
        }
        graph.addEdge(new NetworkEdge(a, BlockPos.ZERO, 1000, 20, NetworkEdge.EdgeType.FIBER, java.util.List.of()));
        graph.addEdge(new NetworkEdge(a, negative, 1000, 200, NetworkEdge.EdgeType.FIBER, java.util.List.of()));
        graph.addEdge(new NetworkEdge(b, a, 1000, 20, NetworkEdge.EdgeType.FIBER, java.util.List.of()));
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            for (BlockPos router : java.util.List.of(a, b, isolated, a)) {
                String destination = router.equals(a) ? Long.toString(Long.MIN_VALUE) : "0";
                var response = postSpeedtest(jobs, router, destination);
                var json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (router.equals(isolated)) {
                    assertEquals(409, response.statusCode());
                    assertEquals("server_unavailable", json.get("errorCode").getAsString());
                } else if (router.equals(a) && graph.getSessionByDeviceId(TrafficSession.routerDeviceId(b)) != null) {
                    assertEquals(409, response.statusCode());
                    assertEquals("device_busy", json.get("errorCode").getAsString());
                } else {
                    assertEquals(200, response.statusCode(), response.body());
                    assertEquals(destination, json.get("serverId").getAsString());
                    var session = graph.getSessionByDeviceId(TrafficSession.routerDeviceId(router));
                    assertEquals(session.getSessionId().toString(), json.get("sessionId").getAsString());
                    assertEquals(session.getServerName(), json.get("serverName").getAsString());
                }
            }
            graph.removeNode(negative);
            graph.tickTraffic(level);
            assertPending(request("GET", "/api/network", null));
            runQueuedJob(jobs);
            var snapshot = JsonParser.parseString(request("GET", "/api/network", null).body()).getAsJsonObject();
            var failed = java.util.stream.StreamSupport.stream(snapshot.getAsJsonArray("nodes").spliterator(), false)
                    .map(com.google.gson.JsonElement::getAsJsonObject)
                    .filter(node -> node.get("id").getAsString().equals(Long.toString(a.asLong())))
                    .findFirst().orElseThrow().getAsJsonObject("speedtest");
            assertEquals("FAILED", failed.get("state").getAsString());
            assertFalse(failed.get("active").getAsBoolean());
            assertEquals(Long.toString(Long.MIN_VALUE), failed.get("serverId").getAsString());
            assertEquals("route_lost", failed.get("errorCode").getAsString());
            assertEquals(TrafficSession.routerDeviceId(a), failed.get("deviceId").getAsString());
            assertNotNull(graph.getSessionByDeviceId(TrafficSession.routerDeviceId(b)));
        }
    }

    @Test
    void disappearedWrongTypeAndUnreachableServersNeverFallbackButOmittedSelectionRemainsAutomatic() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        BlockPos pos = new BlockPos(2, 64, 0), fallback = new BlockPos(-2, 64, 0);
        NetworkNode router = new NetworkNode(pos, NetworkNode.NodeType.ROUTER);
        router.setIpAddress("10.0.0.2");
        graph.addNode(router);
        for (BlockPos destination : java.util.List.of(BlockPos.ZERO, fallback)) {
            graph.addNode(new NetworkNode(destination, NetworkNode.NodeType.SERVER));
            graph.addEdge(new NetworkEdge(pos, destination, 1000, 20, NetworkEdge.EdgeType.FIBER, java.util.List.of()));
        }
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            String path = "/api/speedtest/servers?pos=" + pos.asLong();
            assertPending(request("GET", path, null));
            runQueuedJob(jobs);
            assertEquals(2, JsonParser.parseString(request("GET", path, null).body()).getAsJsonObject().getAsJsonArray("servers").size());
            graph.removeNode(BlockPos.ZERO);
            for (int stage = 0; stage < 3; stage++) {
                if (stage == 1) graph.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.NRO));
                if (stage == 2) graph.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.SERVER));
                var response = postSpeedtest(jobs, pos, "0");
                assertEquals(409, response.statusCode());
                var json = JsonParser.parseString(response.body()).getAsJsonObject();
                assertEquals("server_unavailable", json.get("errorCode").getAsString());
                assertFalse(json.has("sessionId"));
                assertNull(graph.getSessionByDeviceId(TrafficSession.routerDeviceId(pos)));
            }
            graph.removeEdgeBetween(pos, fallback);
            var none = postSpeedtest(jobs, pos, "");
            assertEquals(409, none.statusCode());
            assertEquals("no_server", JsonParser.parseString(none.body()).getAsJsonObject().get("errorCode").getAsString());
            graph.addEdge(new NetworkEdge(pos, fallback, 1000, 20, NetworkEdge.EdgeType.FIBER, java.util.List.of()));
            var automatic = postSpeedtest(jobs, pos, null);
            assertEquals(200, automatic.statusCode());
            assertEquals(Long.toString(fallback.asLong()), JsonParser.parseString(automatic.body()).getAsJsonObject().get("serverId").getAsString());
        }
    }

    @Test
    void publicSpeedtestUsesRouterAuthorityAndReportsOnlyConfirmedSessionsWithoutCredentials() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        TelecomNetworkGraph graph = mock(TelecomNetworkGraph.class);
        when(minecraft.overworld()).thenReturn(level);
        BlockPos position = new BlockPos(-29999999, 64, 1);
        NetworkNode node = new NetworkNode(position, NetworkNode.NodeType.ROUTER);
        node.setIpAddress("10.1.0.2");
        node.setCapacityDown(1234);
        node.setCapacityUp(567);
        when(graph.getNode(position)).thenReturn(node);
        TrafficSession session = mock(TrafficSession.class);
        String deviceId = TrafficSession.routerDeviceId(position);
        when(session.getDeviceId()).thenReturn(deviceId);
        when(session.getSessionId()).thenReturn(new java.util.UUID(0, 1));
        when(session.getServerId()).thenReturn("0");
        when(session.getServerName()).thenReturn("Server (0,0,0)");
        when(graph.startSpeedtest(position, "10.1.0.2", 1234, 567, 0, 0, 300, false, null, ""))
                .thenReturn(new TelecomNetworkGraph.SpeedtestStartResult(session, ""),
                        new TelecomNetworkGraph.SpeedtestStartResult(null, "session_limit"));
        ArrayBlockingQueue<Runnable> jobs = new ArrayBlockingQueue<>(1);
        doAnswer(invocation -> { jobs.add(invocation.getArgument(0)); return null; }).when(minecraft).execute(any(Runnable.class));
        System.setProperty("telecom.http.bind", "0.0.0.0");
        String origin = "http://localhost:" + port;
        System.setProperty("telecom.http.origins", origin);
        startWorldServer(minecraft);
        assertTrue(((HttpServer) field("server")).getAddress().getAddress().isAnyLocalAddress());
        var request = HttpRequest.newBuilder(URI.create(base + "/api/speedtest"))
                .header("Content-Type", "application/json").header("Origin", origin).header("Host", "localhost:" + port)
                .POST(HttpRequest.BodyPublishers.ofString("{\"pos\":\"" + position.asLong()
                        + "\",\"duration\":300,\"maxDown\":999999,\"maxUp\":999999,\"clientIp\":\"192.0.2.1\",\"deviceId\":\"client-selected\"}")).build();
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            Runnable job = jobs.poll(2, TimeUnit.SECONDS);
            assertNotNull(job);
            assertFalse(response.isDone(), "POST must still wait for its Minecraft callback");
            verify(graph, never()).getNode(any());
            job.run();
            var result = response.get(2, TimeUnit.SECONDS);
            assertEquals(200, result.statusCode());
            assertEquals(origin, result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            var json = JsonParser.parseString(result.body()).getAsJsonObject();
            assertEquals(deviceId, json.get("deviceId").getAsString());
            assertEquals(new java.util.UUID(0, 1).toString(), json.get("sessionId").getAsString());
            assertEquals("0", json.get("serverId").getAsString());
            assertEquals("Server (0,0,0)", json.get("serverName").getAsString());
            verify(graph).startSpeedtest(position, "10.1.0.2", 1234, 567, 0, 0, 300, false, null, "");
            Thread.sleep(30);
            response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            job = jobs.poll(2, TimeUnit.SECONDS);
            assertNotNull(job);
            job.run();
            var rejected = response.get(2, TimeUnit.SECONDS);
            assertEquals(409, rejected.statusCode());
            assertEquals("session_limit", JsonParser.parseString(rejected.body()).getAsJsonObject().get("errorCode").getAsString());
        }
    }

    @Test
    void twoRoutersSharingAnIpCanRunIndependentWebTests() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        var jobs = queueWorldServer(minecraft);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        BlockPos a = new BlockPos(-1, 64, 0), b = new BlockPos(1, 64, 0), destination = new BlockPos(0, 64, 4);
        graph.addNode(new NetworkNode(destination, NetworkNode.NodeType.SERVER));
        for (BlockPos pos : java.util.List.of(a, b)) {
            NetworkNode router = new NetworkNode(pos, NetworkNode.NodeType.ROUTER);
            router.setIpAddress("10.0.0.2");
            graph.addNode(router);
            graph.addEdge(new com.florentdubut.telecom.network.NetworkEdge(pos, destination, 1000, 4,
                    com.florentdubut.telecom.network.NetworkEdge.EdgeType.FIBER, java.util.List.of(new BlockPos(0, 64, 2))));
        }
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            java.util.Set<String> sessions = new java.util.HashSet<>();
            for (BlockPos pos : java.util.List.of(a, b, a)) {
                Thread.sleep(30);
                var request = HttpRequest.newBuilder(URI.create(base + "/api/speedtest"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"pos\":\"" + pos.asLong() + "\",\"duration\":300}")).build();
                var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                runQueuedJob(jobs);
                var result = response.get(2, TimeUnit.SECONDS);
                if (sessions.size() == 2) assertEquals(409, result.statusCode());
                else {
                    assertEquals(200, result.statusCode());
                    var json = JsonParser.parseString(result.body()).getAsJsonObject();
                    assertEquals(TrafficSession.routerDeviceId(pos), json.get("deviceId").getAsString());
                    assertTrue(sessions.add(json.get("sessionId").getAsString()));
                }
            }
            assertNotNull(graph.getSessionByDeviceId(TrafficSession.routerDeviceId(a)));
            assertNotNull(graph.getSessionByDeviceId(TrafficSession.routerDeviceId(b)));
            var snapshot = TelecomHttpServer.class.getDeclaredMethod("networkSnapshot", ServerLevel.class, long.class);
            snapshot.setAccessible(true);
            var json = JsonParser.parseString((String) snapshot.invoke(server, level, Long.MAX_VALUE)).getAsJsonObject();
            assertEquals(2, java.util.stream.StreamSupport.stream(json.getAsJsonArray("nodes").spliterator(), false)
                    .filter(node -> node.getAsJsonObject().has("speedtest") && node.getAsJsonObject().getAsJsonObject("speedtest").get("active").getAsBoolean()).count());
        }
    }

    @Test
    void publicZoneGenerationAndCancellationKeepValidationAndReleaseTicketsWithoutCredentials() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(minecraft.overworld()).thenReturn(level);
        System.setProperty("telecom.http.bind", "0.0.0.0");
        String origin = "http://localhost:" + port;
        System.setProperty("telecom.http.origins", base + "," + origin);
        var jobs = queueWorldServer(minecraft);
        assertTrue(((HttpServer) field("server")).getAddress().getAddress().isAnyLocalAddress());
        String mapId = ((TerrainTileStore) field("tileStore")).id();
        String body = "{\"kind\":\"terrain\",\"minX\":0,\"minZ\":0,\"maxX\":15,\"maxZ\":15,\"mapId\":\"" + mapId + "\"}";
        assertEquals(400, request("POST", "/api/zone-jobs", body, "Content-Type", "application/json").statusCode());
        for (String confirmation : new String[]{"false", "\"true\"", "1", "null"}) {
            assertEquals(400, request("POST", "/api/zone-jobs", body.substring(0, body.length() - 1)
                    + ",\"allowGeneration\":" + confirmation + "}", "Content-Type", "application/json").statusCode());
        }
        for (String path : new String[]{"/api/zone-jobs", "/api/zone-jobs/cancel"}) {
            assertEquals(415, request("POST", path, "{}").statusCode());
            assertEquals(413, request("POST", path, "x".repeat(4097), "Content-Type", "application/json").statusCode());
            var chunked = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[4097]))).build();
            assertEquals(413, client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode());
            for (String invalid : new String[]{"{}", "[]", "{"}) {
                assertEquals(400, request("POST", path, invalid, "Content-Type", "application/json").statusCode());
            }
        }
        verify(minecraft, never()).execute(any(Runnable.class));
        assertTrue(jobs.isEmpty());
        body = body.substring(0, body.length() - 1) + ",\"allowGeneration\":true}";
        assertEquals(403, request("POST", "/api/zone-jobs", body, "Content-Type", "application/json", "Origin", "https://evil.example").statusCode());
        var post = HttpRequest.newBuilder(URI.create(base + "/api/zone-jobs"))
                .header("Content-Type", "application/json").header("Origin", origin).header("Host", "localhost:" + port);
        var wrongWorld = client.sendAsync(post.POST(HttpRequest.BodyPublishers.ofString(body.replace(mapId, "another-world"))).build(), HttpResponse.BodyHandlers.ofString());
        runQueuedJob(jobs);
        assertEquals(409, wrongWorld.get(2, TimeUnit.SECONDS).statusCode());
        Thread.sleep(30);
        var start = client.sendAsync(post.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        runQueuedJob(jobs);
        var result = start.get(2, TimeUnit.SECONDS);
        assertEquals(200, result.statusCode());
        String id = JsonParser.parseString(result.body()).getAsJsonObject().get("id").getAsString();
        Thread.sleep(30);
        var duplicate = client.sendAsync(post.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        runQueuedJob(jobs);
        assertEquals(409, duplicate.get(2, TimeUnit.SECONDS).statusCode());
        verify(level, never()).getChunkSource();
        assertPending(request("GET", "/api/zone-jobs", null));
        runQueuedJob(jobs);
        assertEquals("queued", JsonParser.parseString(request("GET", "/api/zone-jobs", null).body()).getAsJsonObject().getAsJsonObject("job").get("state").getAsString());
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunks);
        CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>> generation = new CompletableFuture<>();
        when(chunks.getChunkFuture(0, 0, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true)).thenReturn(generation);
        server.tickZoneJobs(minecraft);
        var ticket = org.mockito.ArgumentCaptor.forClass(net.minecraft.server.level.TicketType.class);
        var position = new net.minecraft.world.level.ChunkPos(0, 0);
        verify(chunks).addTicketWithRadius(ticket.capture(), eq(position), eq(0));
        Thread.sleep(30);
        assertEquals(403, request("POST", "/api/zone-jobs/cancel", "{\"id\":\"" + id + "\"}",
                "Content-Type", "application/json", "Origin", "https://evil.example").statusCode());
        verify(chunks, never()).removeTicketWithRadius(any(), any(), anyInt());
        var cancel = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/zone-jobs/cancel"))
                .header("Content-Type", "application/json").header("Origin", origin).header("Host", "localhost:" + port)
                .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + id + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        runQueuedJob(jobs);
        assertEquals(200, cancel.get(2, TimeUnit.SECONDS).statusCode());
        verify(chunks).removeTicketWithRadius(ticket.getValue(), position, 0);
        assertFalse(generation.isCancelled());
        assertPending(request("GET", "/api/zone-jobs", null));
        runQueuedJob(jobs);
        assertEquals("cancelled", JsonParser.parseString(request("GET", "/api/zone-jobs", null).body()).getAsJsonObject().getAsJsonObject("job").get("state").getAsString());
        Thread.sleep(30);
        start = client.sendAsync(post.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        runQueuedJob(jobs);
        assertEquals(200, start.get(2, TimeUnit.SECONDS).statusCode());
        server.tickZoneJobs(minecraft);
        server.stop();
        verify(chunks, times(2)).removeTicketWithRadius(ticket.getValue(), position, 0);
        assertFalse(generation.isCancelled());
        clearInvocations(chunks);
        server.tickZoneJobs(minecraft);
        verifyNoInteractions(chunks);
    }

    @Test
    void expiredMutationsDoNotAccumulateOrExecuteAfterTheirTimeout() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        var jobs = queueWorldServer(minecraft);
        String body = "{\"pos\":\"1\",\"duration\":300}";
        var response = request("POST", "/api/speedtest", body,
                "Content-Type", "application/json");
        assertEquals(504, response.statusCode());
        assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
        assertEquals(1, jobs.size());
        for (int i = 0; i < 20; i++) {
            response = request("POST", "/api/speedtest", body,
                    "Content-Type", "application/json");
            assertEquals(429, response.statusCode());
            assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
        }
        assertEquals(1, jobs.size());
        runQueuedJob(jobs);
        verify(minecraft, never()).overworld();
        verify(minecraft, times(1)).execute(any(Runnable.class));
        assertEquals(1, ((java.util.concurrent.Semaphore) field("jobSlot")).availablePermits());
    }
}
