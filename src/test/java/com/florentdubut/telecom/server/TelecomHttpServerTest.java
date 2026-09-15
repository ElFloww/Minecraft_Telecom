package com.florentdubut.telecom.server;

import com.sun.net.httpserver.HttpServer;
import com.florentdubut.telecom.network.NetworkEdge;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.network.TrafficSession;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class TelecomHttpServerTest {
    private final TelecomHttpServer server = new TelecomHttpServer();
    private HttpClient client;
    private String base;
    private int port;

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

    private String bearer() {
        String token = System.getenv("TELECOM_HTTP_TOKEN");
        assumeTrue(token != null && !token.isBlank(), "Run also with TELECOM_HTTP_TOKEN to test authenticated paths");
        return "Bearer " + token;
    }

    @Test
    void defaultsToLoopbackAndRequiresMutationToken() throws Exception {
        assertTrue(((HttpServer) field("server")).getAddress().getAddress().isLoopbackAddress());
        assertEquals(503, request("GET", "/api/network", null).statusCode());
        assertEquals(401, request("POST", "/api/speedtest", "{}").statusCode());
        assertEquals(401, request("POST", "/api/speedtest", "{}", "Authorization", "Bearer wrong").statusCode());
        var page = request("GET", "/", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("session-token"));
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void rejectsOriginsHostsMethodsAndTraversal() throws Exception {
        assertEquals(403, request("GET", "/api/network", null, "Origin", "https://evil.example").statusCode());
        assertEquals(403, request("GET", "/api/network", null, "Origin", "null").statusCode());
        assertEquals(403, request("GET", "/api/network", null, "Host", "evil.example").statusCode());
        assertEquals(405, request("DELETE", "/api/network", null).statusCode());
        assertEquals(405, request("GET", "/api/speedtest", null).statusCode());
        assertEquals(404, request("GET", "/api/network/extra", null).statusCode());
        assertEquals(404, request("GET", "/%2e%2e/build.gradle", null).statusCode());
    }

    @Test
    void preflightChecksExactOriginMethodAndHeaders() throws Exception {
        var response = request("OPTIONS", "/api/speedtest", null, "Origin", base,
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization,content-type");
        assertEquals(204, response.statusCode());
        assertEquals(base, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(403, request("OPTIONS", "/api/speedtest", null, "Origin", base,
                "Access-Control-Request-Method", "DELETE").statusCode());
        assertEquals(403, request("OPTIONS", "/api/network", null, "Origin", base,
                "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "x-untrusted").statusCode());
    }

    @Test
    void authenticatedBodiesAndDurationsAreBounded() throws Exception {
        String auth = bearer();
        assertEquals(415, request("POST", "/api/speedtest", "{}", "Authorization", auth).statusCode());
        assertEquals(413, request("POST", "/api/speedtest", "x".repeat(4097), "Authorization", auth,
                "Content-Type", "application/json").statusCode());
        var chunked = HttpRequest.newBuilder(URI.create(base + "/api/speedtest"))
                .header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[4097]))).build();
        assertEquals(413, client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode());
        for (String body : new String[]{"[]", "{}", "{", "{\"pos\":\"1\",\"duration\":301}",
                "{\"pos\":\"1\",\"duration\":300.5}", "{\"pos\":\"9223372036854775808\",\"duration\":300}"}) {
            assertEquals(400, request("POST", "/api/speedtest", body, "Authorization", auth,
                    "Content-Type", "application/json").statusCode(), body);
        }
        for (int ticks : new int[]{300, 600, 1200, 6000, 12000}) {
            assertEquals(503, request("POST", "/api/speedtest", "{\"pos\":\"-9223372036854775808\",\"duration\":"
                    + ticks + ",\"maxDown\":\"ignored\",\"maxUp\":-1}", "Authorization", auth,
                    "Content-Type", "application/json").statusCode());
        }
    }

    @Test
    void malformedTileCoordinatesNeverReachMinecraft() throws Exception {
        for (String query : new String[]{"", "?cx=1", "?cx=x&cz=0", "?cx=1&cx=2&cz=0", "?cx=2147483647&cz=0"}) {
            assertEquals(400, request("GET", "/api/tile" + query, null).statusCode());
        }
        assertEquals(503, request("GET", "/api/tile?cx=-1&cz=0", null).statusCode());
    }

    @Test
    void publicBindingFailsClosedOrRequiresAuthenticationForEveryDataRoute() throws Exception {
        server.stop();
        System.setProperty("telecom.http.bind", "0.0.0.0");
        System.setProperty("telecom.http.origins", base);
        server.start(null);
        String token = System.getenv("TELECOM_HTTP_TOKEN");
        if (token == null || token.isBlank()) {
            assertNull(field("server"));
            return;
        }
        assertNotNull(field("server"));
        for (String route : new String[]{"network", "player", "nperf_map", "tile?cx=0&cz=0"}) {
            assertEquals(401, request("GET", "/api/" + route, null).statusCode());
            assertEquals(503, request("GET", "/api/" + route, null, "Authorization", bearer()).statusCode());
        }
        assertEquals(200, request("GET", "/", null).statusCode());
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
    void expiredJobsDoNotAccumulateOrReadTheWorldLater() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ArrayBlockingQueue<Runnable> jobs = new ArrayBlockingQueue<>(2);
        doAnswer(invocation -> { jobs.add(invocation.getArgument(0)); return null; }).when(minecraft).execute(any(Runnable.class));
        server.stop();
        server.start(minecraft);
        assertEquals(504, request("GET", "/api/network", null).statusCode());
        assertEquals(1, jobs.size());
        for (int i = 0; i < 20; i++) assertEquals(429, request("GET", "/api/network", null).statusCode());
        assertEquals(1, jobs.size());
        jobs.remove().run();
        verify(minecraft, never()).overworld();
        assertEquals(1, ((java.util.concurrent.Semaphore) field("jobSlot")).availablePermits());
    }

    @Test
    void unloadedTileOnlyLooksUpLoadedChunksOnScheduledThread() throws Exception {
        MinecraftServer minecraft = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(minecraft.overworld()).thenReturn(level);
        when(level.getChunkSource()).thenReturn(chunks);
        ArrayBlockingQueue<Runnable> jobs = new ArrayBlockingQueue<>(1);
        doAnswer(invocation -> { jobs.add(invocation.getArgument(0)); return null; }).when(minecraft).execute(any(Runnable.class));
        server.stop();
        server.start(minecraft);
        var response = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/tile?cx=3&cz=-4")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Runnable job = jobs.poll(2, TimeUnit.SECONDS);
        assertNotNull(job);
        verify(minecraft, never()).overworld();
        job.run();
        assertEquals(404, response.get(2, TimeUnit.SECONDS).statusCode());
        verify(chunks).getChunkNow(3, -4);
        verifyNoMoreInteractions(chunks);
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
    void speedtestUsesRouterCapsAndReportsOnlyConfirmedSessions() throws Exception {
        String auth = bearer();
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
        when(graph.getSessionByIp("10.1.0.2")).thenReturn(null, session);
        ArrayBlockingQueue<Runnable> jobs = new ArrayBlockingQueue<>(1);
        doAnswer(invocation -> { jobs.add(invocation.getArgument(0)); return null; }).when(minecraft).execute(any(Runnable.class));
        server.stop();
        server.start(minecraft);
        var request = HttpRequest.newBuilder(URI.create(base + "/api/speedtest"))
                .header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"pos\":\"" + position.asLong()
                        + "\",\"duration\":300,\"maxDown\":999999,\"maxUp\":999999}")).build();
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            Runnable job = jobs.poll(2, TimeUnit.SECONDS);
            assertNotNull(job);
            verify(graph, never()).getNode(any());
            job.run();
            assertEquals(200, response.get(2, TimeUnit.SECONDS).statusCode());
            verify(graph).startSpeedtest(position, "10.1.0.2", 1234, 567, 0, 0, 300, false, null);
            Thread.sleep(30);
            when(graph.getSessionByIp("10.1.0.2")).thenReturn(null);
            response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            job = jobs.poll(2, TimeUnit.SECONDS);
            assertNotNull(job);
            job.run();
            assertEquals(409, response.get(2, TimeUnit.SECONDS).statusCode());
        }
    }
}
