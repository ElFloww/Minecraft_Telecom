package com.florentdubut.telecom.server;

import com.florentdubut.telecom.network.NetworkEdge;
import com.florentdubut.telecom.network.CoverageService;
import com.florentdubut.telecom.network.SignalPropagator;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTP is unauthenticated; bind to loopback unless access is restricted externally. */
public class TelecomHttpServer {
    private static final int MAX_BODY = 4096;
    private static final long WAIT_MS = 750;
    private static final long JOB_BUDGET_NS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final Set<Integer> DURATIONS = Set.of(300, 600, 1200, 6000, 12000);
    private HttpServer server;
    private ThreadPoolExecutor workers;
    private volatile MinecraftServer minecraftServer;
    private volatile boolean running;
    private volatile FutureTask<?> pendingJob;
    // Held until the queued wrapper actually runs, NOT until the HTTP request times out.
    private volatile Semaphore jobSlot = new Semaphore(1);
    private volatile long nextJobAt;
    private Set<String> origins = Set.of();
    private Set<String> hosts = Set.of();
    private final Map<Long, byte[]> tiles = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<Long, Long> unavailableTiles = new LinkedHashMap<>(1024, 0.75f, true);
    private HttpReadQueue reads = new HttpReadQueue();
    private final Object[] tileLocks = new Object[64];
    private volatile TerrainTileStore tileStore;
    private volatile WorldMapImageStore worldMap;
    private volatile TerrainCaptureQueue terrainCapture;
    private volatile ZoneJobManager zoneJobs;

    public TelecomHttpServer() {
        java.util.Arrays.setAll(tileLocks, ignored -> new Object());
    }

    public synchronized void start(MinecraftServer ms) {
        if (server != null || !Boolean.parseBoolean(System.getProperty("telecom.http.enabled", "true"))) return;
        try {
            String bind = System.getProperty("telecom.http.bind", "127.0.0.1");
            int port = Integer.parseInt(System.getProperty("telecom.http.port", "8080"));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid HTTP port");
            InetAddress address = InetAddress.getByName(bind);
            Set<String> allowedOrigins = new HashSet<>();
            Set<String> allowedHosts = new HashSet<>();
            String authority = new URI("http", null, address.getHostAddress(), port, null, null, null).getRawAuthority();
            allowedHosts.add(authority);
            allowedOrigins.add("http://" + authority);
            if (port == 80) {
                String defaultAuthority = new URI("http", null, address.getHostAddress(), -1, null, null, null).getRawAuthority();
                allowedHosts.add(defaultAuthority);
                allowedOrigins.add("http://" + defaultAuthority);
            }
            if (address.isLoopbackAddress()) {
                for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
                    allowedHosts.add(host + ":" + port);
                    allowedOrigins.add("http://" + host + ":" + port);
                    if (port == 80) {
                        allowedHosts.add(host);
                        allowedOrigins.add("http://" + host);
                    }
                }
            }
            for (String value : System.getProperty("telecom.http.origins", "").split(",")) {
                if (value.isBlank()) continue;
                URI origin = URI.create(value.trim());
                if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                        || origin.getHost() == null || origin.getRawUserInfo() != null
                        || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                        || origin.getRawQuery() != null || origin.getRawFragment() != null) {
                    throw new IllegalArgumentException("HTTP origins must be exact http(s) origins, without path or wildcard");
                }
                allowedOrigins.add(origin.toString());
                allowedHosts.add(origin.getRawAuthority());
            }
            origins = Set.copyOf(allowedOrigins);
            hosts = Set.copyOf(allowedHosts);
            jobSlot = new Semaphore(1);
            nextJobAt = 0;
            reads.clear();
            reads = new HttpReadQueue();
            var worldPath = ms == null ? null : ms.getWorldPath(LevelResource.ROOT);
            if (worldPath != null) {
                try {
                    tileStore = new TerrainTileStore(worldPath.resolve("telecom-map/minecraft/overworld"));
                    WorldMapImageStore image = new WorldMapImageStore(tileStore);
                    worldMap = image;
                    terrainCapture = new TerrainCaptureQueue(ms, tileStore, image::invalidate);
                    zoneJobs = new ZoneJobManager(ms, terrainCapture);
                    if (ms.isSameThread()) terrainCapture.seedLoadedChunks();
                } catch (IOException e) {
                    System.err.println("Telecom terrain tiles disabled: " + e.getMessage());
                }
            }
            // JDK HTTP server limits are JVM-wide and read at first HttpServer initialization.
            defaultProperty("sun.net.httpserver.maxReqTime", "10");
            defaultProperty("sun.net.httpserver.maxRspTime", "10");
            defaultProperty("sun.net.httpserver.maxConnections", "128");
            defaultProperty("sun.net.httpserver.maxReqHeaders", "32");
            defaultProperty("sun.net.httpserver.maxReqHeaderSize", "16384");
            defaultProperty("sun.net.httpserver.idleInterval", "15");
            server = HttpServer.create(new InetSocketAddress(address, port), 32);
            workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "telecom-http");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            server.setExecutor(workers);
            server.createContext("/", this::handle);
            minecraftServer = ms;
            running = true;
            server.start();
            System.out.println("Telecom HTTP listening on " + authority);
            if (!address.isLoopbackAddress()) System.err.println("Telecom HTTP has no authentication: reachable clients can modify the world. Restrict network access.");
        } catch (Exception e) {
            stop();
            System.err.println("Telecom HTTP disabled: " + e.getMessage());
        }
    }

    private static void defaultProperty(String name, String value) {
        if (System.getProperty(name) == null) System.setProperty(name, value);
    }

    public synchronized void stop() {
        running = false;
        minecraftServer = null;
        FutureTask<?> job = pendingJob;
        if (job != null) job.cancel(false);
        reads.clear();
        ZoneJobManager zones = zoneJobs;
        zoneJobs = null;
        if (zones != null) zones.close();
        TerrainCaptureQueue capture = terrainCapture;
        terrainCapture = null;
        if (capture != null) capture.close();
        WorldMapImageStore image = worldMap;
        worldMap = null;
        if (image != null) image.close();
        if (server != null) server.stop(0);
        server = null;
        if (workers != null) workers.shutdownNow();
        workers = null;
        synchronized (tiles) { tiles.clear(); unavailableTiles.clear(); }
        tileStore = null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            if (!running) throw new HttpFailure(503, "HTTP stopping");
            if (!hosts.contains(singleHeader(exchange, "Host"))) throw new HttpFailure(403, "Host not allowed");
            String origin = singleHeader(exchange, "Origin");
            if (origin != null) {
                if (!origins.contains(origin)) throw new HttpFailure(403, "Origin not allowed");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().set("Vary", "Origin");
            }
            String path = exchange.getRequestURI().getPath();
            boolean api = path.startsWith("/api/");
            String intendedMethod = "OPTIONS".equals(exchange.getRequestMethod())
                    ? singleHeader(exchange, "Access-Control-Request-Method") : exchange.getRequestMethod();
            boolean mutation = path.equals("/api/speedtest") || path.equals("/api/zone-jobs/cancel")
                    || path.equals("/api/zone-jobs") && "POST".equals(intendedMethod);
            if (api && !Set.of("/api/network", "/api/player", "/api/tile", "/api/terrain", "/api/map-image", "/api/nperf_map", "/api/speedtest", "/api/speedtest/servers", "/api/coverage", "/api/coverage/options", "/api/zone-jobs", "/api/zone-jobs/cancel").contains(path)) {
                throw new HttpFailure(404, "Unknown endpoint");
            }
            String allowedMethod = mutation ? "POST" : "GET";
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                if (origin == null || !allowedMethod.equals(singleHeader(exchange, "Access-Control-Request-Method"))) {
                    throw new HttpFailure(403, "Preflight method not allowed");
                }
                String headers = singleHeader(exchange, "Access-Control-Request-Headers");
                if (headers != null) {
                    for (String header : headers.split(",")) {
                        if (!Set.of("content-type", "if-none-match").contains(header.trim().toLowerCase(java.util.Locale.ROOT))) {
                            throw new HttpFailure(403, "Preflight header not allowed");
                        }
                    }
                }
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", allowedMethod);
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, If-None-Match");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!allowedMethod.equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", allowedMethod + ", OPTIONS");
                throw new HttpFailure(405, "Method not allowed");
            }
            // Reject framed bodies even on GET; POST reads at most MAX_BODY + 1 bytes.
            String length = singleHeader(exchange, "Content-Length");
            if (length != null) {
                long size = Long.parseLong(length);
                if (size < 0) throw new HttpFailure(400, "Invalid Content-Length");
                if (size > MAX_BODY) throw new HttpFailure(413, "Body too large");
                if (!mutation && size != 0) throw new HttpFailure(400, "Body not allowed");
            }
            if (!mutation && exchange.getRequestHeaders().containsKey("Transfer-Encoding")) throw new HttpFailure(400, "Body not allowed");
            switch (path) {
                case "/api/network" -> sendJson(exchange, readOnServer("network", this::networkSnapshot).value());
                case "/api/nperf_map" -> sendJson(exchange, readOnServer("nperf", this::coverageSnapshot).value());
                case "/api/player" -> sendJson(exchange, readOnServer("player", (level, deadline) -> {
                    if (level.players().isEmpty()) throw new HttpFailure(204, "No player in overworld");
                    var player = level.players().getFirst();
                    return "{\"x\":" + player.getBlockX() + ",\"z\":" + player.getBlockZ() + "}";
                }).value());
                case "/api/tile" -> sendTile(exchange);
                case "/api/terrain" -> throw new HttpFailure(410, "Terrain atlas API replaced; reload the dashboard");
                case "/api/map-image" -> sendMapImage(exchange);
                case "/api/coverage/options" -> sendJson(exchange, readOnServer("coverage-options", this::coverageOptions).value());
                case "/api/coverage" -> sendCalculatedCoverage(exchange);
                case "/api/speedtest" -> startSpeedtest(exchange);
                case "/api/speedtest/servers" -> sendSpeedtestServers(exchange);
                case "/api/zone-jobs" -> {
                    if (mutation) startZoneJob(exchange);
                    else sendJson(exchange, readOnServer("zone-jobs", (level, deadline) -> zoneManager().status()).value());
                }
                case "/api/zone-jobs/cancel" -> cancelZoneJob(exchange);
                case "/favicon.ico" -> exchange.sendResponseHeaders(204, -1);
                default -> sendStatic(exchange, path);
            }
        } catch (HttpFailure e) {
            if (e.status == 204) {
                exchange.getResponseHeaders().set("Retry-After", "30");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (e.status == 202 || e.status == 429 || e.status == 503 || e.status == 504) {
                exchange.getResponseHeaders().set("Retry-After", "1");
            }
            JsonObject error = new JsonObject();
            if (e.status == 202) {
                error.addProperty("status", "pending");
                error.addProperty("message", e.getMessage());
            } else {
                error.addProperty("error", e.getMessage());
                if (e.code != null) error.addProperty("errorCode", e.code);
            }
            send(exchange, e.status, "application/json; charset=utf-8", error.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "application/json", "{\"error\":\"Invalid request\"}".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            send(exchange, 500, "application/json", "{\"error\":\"Internal HTTP error\"}".getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    private static String singleHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        if (values == null) return null;
        if (values.size() != 1) throw new HttpFailure(400, "Duplicate header");
        return values.getFirst();
    }

    @FunctionalInterface
    private interface SnapshotJob<T> { T run(ServerLevel level, long deadline); }

    private <T> HttpReadQueue.Completed<T> readOnServer(String key, SnapshotJob<T> work) {
        MinecraftServer ms;
        Semaphore generation;
        HttpReadQueue queue;
        synchronized (this) {
            ms = minecraftServer;
            generation = jobSlot;
            queue = reads;
            if (!running || ms == null) throw new HttpFailure(503, "Minecraft unavailable");
        }
        try {
            return queue.request(key, ms, (level, deadline) -> {
                if (!running || minecraftServer != ms || jobSlot != generation) throw new HttpFailure(503, "Minecraft stopping");
                return work.run(level, deadline);
            });
        } catch (HttpReadQueue.Pending e) {
            throw new HttpFailure(202, "Waiting for a Minecraft tick; the game may be paused or busy");
        } catch (HttpReadQueue.Unavailable e) {
            throw new HttpFailure(503, "Minecraft unavailable");
        }
    }

    private <T> T onServer(SnapshotJob<T> work) {
        MinecraftServer ms;
        Semaphore slot;
        synchronized (this) {
            ms = minecraftServer;
            slot = jobSlot;
            if (!running || ms == null) throw new HttpFailure(503, "Minecraft unavailable");
        }
        if (System.nanoTime() < nextJobAt || !slot.tryAcquire()) throw new HttpFailure(429, "Minecraft HTTP budget busy");
        long expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        FutureTask<T> task = new FutureTask<>(() -> {
            if (!running || minecraftServer != ms || jobSlot != slot) throw new HttpFailure(503, "Minecraft stopping");
            checkBudget(expires);
            ServerLevel level = ms.overworld();
            if (level == null) throw new HttpFailure(503, "Overworld unavailable");
            return work.run(level, Math.min(expires, System.nanoTime() + JOB_BUDGET_NS));
        });
        pendingJob = task;
        try {
            ms.execute(() -> {
                try { task.run(); }
                finally {
                    if (pendingJob == task) pendingJob = null;
                    if (jobSlot == slot) nextJobAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
                    slot.release();
                }
            });
        } catch (RejectedExecutionException e) {
            if (pendingJob == task) pendingJob = null;
            slot.release();
            throw new HttpFailure(503, "Minecraft stopping");
        }
        try {
            return task.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(false);
            throw new HttpFailure(504, "Minecraft job expired; mutation outcome may be unknown if already running");
        } catch (InterruptedException e) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new HttpFailure(503, "HTTP stopping");
        } catch (CancellationException e) {
            throw new HttpFailure(503, "Minecraft job cancelled");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpFailure failure) throw failure;
            throw new HttpFailure(500, "Minecraft snapshot failed");
        }
    }

    private static void checkBudget(long deadline) {
        if (System.nanoTime() >= deadline) throw new HttpFailure(503, "Minecraft snapshot budget exceeded");
    }

    // Only detached immutable values (String / List.copyOf) leave the server thread.
    private String networkSnapshot(ServerLevel level, long deadline) {
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
        JsonObject response = new JsonObject();
        TerrainTileStore store = tileStore;
        response.addProperty("mapId", store == null ? null : store.id());
        response.add("mapImage", mapImageMetadata());
        JsonArray nodes = new JsonArray();
        for (NetworkNode node : graph.getNodes()) {
            checkBudget(deadline);
            if (nodes.size() >= 8192) throw new HttpFailure(503, "Network snapshot too large");
            JsonObject item = new JsonObject();
            item.addProperty("id", Long.toString(node.getPosition().asLong()));
            item.addProperty("x", node.getPosition().getX());
            item.addProperty("y", node.getPosition().getY());
            item.addProperty("z", node.getPosition().getZ());
            item.addProperty("type", node.getType().name());
            item.addProperty("ip", node.getIpAddress() == null ? "" : node.getIpAddress());
            item.addProperty("cidr", node.getNetworkCidr() == null ? "" : node.getNetworkCidr());
            item.addProperty("usageDown", node.getCurrentUsageDown());
            item.addProperty("usageUp", node.getCurrentUsageUp());
            if (node.getType() == NetworkNode.NodeType.ROUTER) {
                var session = graph.getLatestSessionByDeviceId(com.florentdubut.telecom.network.TrafficSession.routerDeviceId(node.getPosition()));
                if (session != null) item.add("speedtest", speedtestSnapshot(session));
            }
            int down = 1000, up = 1000;
            switch (node.getType()) {
                case SERVER, NRO -> { down = 1000000; up = 1000000; }
                case NRA, PM -> { down = 100000; up = 100000; }
                case SR -> { down = 10000; up = 10000; }
                case ROUTER -> { down = node.getCapacityDown(); up = node.getCapacityUp(); }
                default -> { }
            }
            item.addProperty("capacityDown", down);
            item.addProperty("capacityUp", up);
            item.addProperty("capacity", Math.max(down, up));
            if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                JsonArray techs = new JsonArray();
                JsonArray frequencies = new JsonArray();
                var utilization = graph.getAntennaUtilization(node.getPosition());
                for (TelecomFrequency freq : TelecomFrequency.values()) {
                    if ((node.getFrequenciesMask() & (1 << freq.ordinal())) == 0) continue;
                    techs.add(freq.getTechnology());
                    JsonObject f = new JsonObject();
                    f.addProperty("technology", freq.getTechnology());
                    f.addProperty("label", freq.getFrequencyLabel());
                    f.addProperty("max", freq.getMaxSpeedMb());
                    var stats = utilization.get(freq);
                    f.addProperty("usage", stats == null ? 0 : stats.actualMbps());
                    frequencies.add(f);
                }
                item.add("technologies", techs);
                item.add("frequencies", frequencies);
            }
            nodes.add(item);
        }
        response.add("nodes", nodes);
        JsonArray edges = new JsonArray();
        for (NetworkEdge edge : graph.getEdges()) {
            checkBudget(deadline);
            if (edges.size() >= 16384) throw new HttpFailure(503, "Network snapshot too large");
            JsonObject item = new JsonObject();
            item.addProperty("source", Long.toString(edge.getNodeA().asLong()));
            item.addProperty("target", Long.toString(edge.getNodeB().asLong()));
            item.addProperty("type", edge.getType().name());
            item.addProperty("usageDown", edge.getCurrentUsageDown());
            item.addProperty("usageUp", edge.getCurrentUsageUp());
            item.addProperty("capacity", edge.getBandwidthMax());
            item.addProperty("length", edge.getLength());
            edges.add(item);
        }
        response.add("edges", edges);
        String snapshot = response.toString();
        checkBudget(deadline);
        return snapshot;
    }

    private String coverageSnapshot(ServerLevel level, long deadline) {
        JsonArray points = new JsonArray();
        for (var entry : TelecomNetworkGraph.get(level).getRecordedCoverage().entrySet()) {
            checkBudget(deadline);
            if (points.size() >= 65536) throw new HttpFailure(503, "Coverage snapshot too large");
            BlockPos pos = BlockPos.of(entry.getKey());
            JsonObject point = new JsonObject();
            point.addProperty("x", pos.getX());
            point.addProperty("z", pos.getZ());
            point.addProperty("t", entry.getValue() >> 8);
            point.addProperty("s", entry.getValue() & 255);
            points.add(point);
        }
        String snapshot = points.toString();
        checkBudget(deadline);
        return snapshot;
    }

    private String coverageOptions(ServerLevel level, long deadline) {
        JsonObject options = new JsonObject();
        options.addProperty("tileSize", CoverageService.TILE_SIZE);
        options.addProperty("minLevel", -3);
        options.addProperty("maxLevel", 6);
        options.addProperty("maxSamplesPerSide", 16);
        options.addProperty("sharedTechnologies", true);
        options.addProperty("minY", level.getMinY());
        options.addProperty("maxY", level.getMaxY());
        options.addProperty("maxRange", SignalPropagator.MAX_RANGE);
        options.addProperty("modelRevision", CoverageService.modelRevision(level));
        JsonArray steps = new JsonArray();
        for (int step : List.of(1, 8, 16, 32, 64, 128, 256, 512, 1024, 2048)) steps.add(step);
        options.add("steps", steps);
        JsonArray technologies = new JsonArray();
        for (String technology : List.of("2G", "3G", "4G", "5G")) technologies.add(technology);
        options.add("technologies", technologies);
        JsonArray bands = new JsonArray();
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            JsonObject band = new JsonObject();
            band.addProperty("id", frequency.name());
            band.addProperty("technology", frequency.getTechnology());
            band.addProperty("label", frequency.getFrequencyLabel());
            bands.add(band);
        }
        options.add("bands", bands);
        return options.toString();
    }

    private void sendCalculatedCoverage(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.length() > 512) throw new HttpFailure(400, "Expected coverage tile and filters");
        Map<String, String> params = new HashMap<>();
        Set<String> allowed = Set.of("tx", "tz", "step", "y", "antenna", "technology", "band", "level");
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || !allowed.contains(pair[0])
                    || params.putIfAbsent(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) {
                throw new HttpFailure(400, "Invalid coverage query");
            }
        }
        if (!params.containsKey("tx") || !params.containsKey("tz")) throw new HttpFailure(400, "Expected tx and tz");
        CoverageService.Request request = new CoverageService.Request(
                Integer.parseInt(params.get("tx")), Integer.parseInt(params.get("tz")),
                Integer.parseInt(params.getOrDefault("step", "32")), params.getOrDefault("y", "surface"),
                params.getOrDefault("antenna", "all"), params.getOrDefault("technology", "all"), params.getOrDefault("band", "all"),
                Integer.parseInt(params.getOrDefault("level", "0")));
        HttpReadQueue.Completed<String> snapshot = readOnServer("coverage:" + request, (level, deadline) -> {
            try {
                return CoverageService.request(level, request, deadline);
            } catch (CoverageService.BusyException busy) {
                throw new HttpFailure(503, busy.getMessage());
            } catch (IllegalArgumentException invalid) {
                throw new HttpFailure(400, invalid.getMessage());
            }
        });
        JsonObject body = JsonParser.parseString(snapshot.value()).getAsJsonObject();
        body.addProperty("validForMs", Math.max(0, body.get("validForMs").getAsLong()
                - TimeUnit.NANOSECONDS.toMillis(snapshot.ageNanos())));
        sendJson(exchange, body.toString());
    }

    private static long decimalLong(String value) {
        if (!value.matches("-?[0-9]{1,19}")) throw new IllegalArgumentException();
        long parsed = Long.parseLong(value);
        if (!Long.toString(parsed).equals(value)) throw new IllegalArgumentException();
        return parsed;
    }

    private void sendSpeedtestServers(HttpExchange exchange) throws IOException {
        final long pos;
        try {
            String query = exchange.getRequestURI().getRawQuery();
            if (query == null || query.length() > 128 || !query.startsWith("pos=") || query.contains("&")) {
                throw new IllegalArgumentException();
            }
            pos = decimalLong(URLDecoder.decode(query.substring(4), StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            throw new HttpFailure(400, "Expected pos as a decimal long string", "invalid_request");
        }
        sendJson(exchange, readOnServer("speedtest-servers:" + pos, (level, deadline) -> {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
            BlockPos position = BlockPos.of(pos);
            NetworkNode router = graph.getNode(position);
            if (router == null || router.getType() != NetworkNode.NodeType.ROUTER) {
                throw new HttpFailure(404, "Router not found", "invalid_request");
            }
            checkBudget(deadline);
            final java.util.List<com.florentdubut.telecom.network.SpeedtestServerOption> options;
            try {
                options = graph.getSpeedtestServers(position, 0);
            } catch (TelecomNetworkGraph.SpeedtestCatalogueLimitException limit) {
                throw new HttpFailure(503, "Network exceeds speedtest catalogue limits", "catalogue_limit");
            }
            JsonArray servers = new JsonArray();
            for (var option : options) {
                checkBudget(deadline);
                if (servers.size() >= TelecomNetworkGraph.MAX_SPEEDTEST_SERVERS) break;
                JsonObject item = new JsonObject();
                item.addProperty("id", option.id());
                item.addProperty("name", option.name());
                item.addProperty("estimatedPingMs", option.estimatedPingMs());
                item.addProperty("available", option.available());
                item.addProperty("bandwidthMbps", option.bandwidthMbps());
                item.addProperty("reason", option.reason());
                servers.add(item);
            }
            int count = 0;
            for (NetworkNode node : graph.getNodes()) {
                checkBudget(deadline);
                if (node.getType() == NetworkNode.NodeType.SERVER && ++count > TelecomNetworkGraph.MAX_SPEEDTEST_SERVERS) break;
            }
            JsonObject result = new JsonObject();
            result.addProperty("pos", Long.toString(pos));
            result.add("servers", servers);
            result.addProperty("truncated", count > TelecomNetworkGraph.MAX_SPEEDTEST_SERVERS);
            return result.toString();
        }).value());
    }

    private static HttpFailure speedtestFailure(String code) {
        String message = switch (code) {
            case "device_busy" -> "Speedtest already active on this router";
            case "session_limit" -> "Speedtest session limit reached";
            case "server_unavailable" -> "Selected server disappeared or is unreachable; no automatic fallback";
            case "no_server" -> "No reachable speedtest server";
            default -> "Invalid speedtest request";
        };
        return new HttpFailure(code.equals("invalid_request") ? 400 : 409, message, code);
    }

    private void startSpeedtest(HttpExchange exchange) throws IOException {
        String contentType = singleHeader(exchange, "Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new HttpFailure(415, "Expected application/json");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new HttpFailure(413, "Body too large");
        final long pos;
        final int duration;
        final String serverId;
        try {
            JsonObject payload = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!payload.get("pos").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            pos = decimalLong(payload.get("pos").getAsString());
            if (payload.has("serverId") && !payload.get("serverId").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            serverId = payload.has("serverId") ? payload.get("serverId").getAsString() : "";
            if (!serverId.isEmpty()) decimalLong(serverId);
            duration = Integer.parseInt(payload.get("duration").getAsString());
            if (!DURATIONS.contains(duration)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new HttpFailure(400, "Expected pos and optional serverId as decimal long strings (empty serverId means auto), and duration in 300,600,1200,6000,12000", "invalid_request");
        }
        sendJson(exchange, onServer((level, deadline) -> {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
            BlockPos position = BlockPos.of(pos);
            NetworkNode node = graph.getNode(position);
            if (node == null || node.getType() != NetworkNode.NodeType.ROUTER) throw new HttpFailure(404, "Router not found", "invalid_request");
            String ip = node.getIpAddress();
            if (ip == null || ip.isBlank()) throw new HttpFailure(409, "Router has no IP", "invalid_request");
            checkBudget(deadline);
            var started = graph.startSpeedtest(position, ip, node.getCapacityDown(), node.getCapacityUp(), 0, 0, duration, false, null, serverId);
            if (!started.accepted()) throw speedtestFailure(started.error());
            var after = started.session();
            JsonObject result = new JsonObject();
            result.addProperty("status", "started");
            result.addProperty("deviceId", after.getDeviceId());
            result.addProperty("sessionId", after.getSessionId().toString());
            result.addProperty("serverId", after.getServerId());
            result.addProperty("serverName", after.getServerName());
            return result.toString();
        }));
    }

    private ZoneJobManager zoneManager() {
        ZoneJobManager jobs = zoneJobs;
        if (jobs == null) throw new HttpFailure(503, "Zone processing unavailable");
        return jobs;
    }

    public void tickZoneJobs(MinecraftServer server) {
        ZoneJobManager jobs = zoneJobs;
        if (running && server == minecraftServer && jobs != null) jobs.tick();
    }

    private JsonObject zoneBody(HttpExchange exchange) throws IOException {
        String contentType = singleHeader(exchange, "Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw new HttpFailure(415, "Expected application/json");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new HttpFailure(413, "Body too large");
        try { return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw new HttpFailure(400, "Expected a JSON object"); }
    }

    private void startZoneJob(HttpExchange exchange) throws IOException {
        JsonObject body = zoneBody(exchange);
        final ZoneJobManager.Request request;
        final String mapId;
        try {
            String kind = body.get("kind").getAsString();
            if (kind.equals("terrain") && (!body.has("allowGeneration") || !body.get("allowGeneration").isJsonPrimitive()
                    || !body.get("allowGeneration").getAsJsonPrimitive().isBoolean() || !body.get("allowGeneration").getAsBoolean())) {
                throw new IllegalArgumentException("Explicit generation confirmation required");
            }
            mapId = body.get("mapId").getAsString();
            request = new ZoneJobManager.Request(kind, Integer.parseInt(body.get("minX").getAsString()),
                    Integer.parseInt(body.get("minZ").getAsString()), Integer.parseInt(body.get("maxX").getAsString()),
                    Integer.parseInt(body.get("maxZ").getAsString()), body.has("step") ? Integer.parseInt(body.get("step").getAsString()) : 16,
                    body.has("height") ? body.get("height").getAsString() : "surface", body.has("antenna") ? body.get("antenna").getAsString() : "all",
                    body.has("technology") ? body.get("technology").getAsString() : "all", body.has("band") ? body.get("band").getAsString() : "all");
        } catch (RuntimeException invalid) { throw new HttpFailure(400, "Invalid zone, limits or generation confirmation"); }
        sendJson(exchange, onServer((level, deadline) -> {
            TerrainTileStore store = tileStore;
            if (store == null || !store.id().equals(mapId)) throw new HttpFailure(409, "Map changed; reload before generating terrain");
            try {
                JsonObject result = new JsonObject();
                result.addProperty("id", zoneManager().start(request));
                result.addProperty("status", "queued");
                return result.toString();
            } catch (IllegalArgumentException invalid) { throw new HttpFailure(400, invalid.getMessage()); }
            catch (IllegalStateException conflict) { throw new HttpFailure(409, conflict.getMessage()); }
        }));
    }

    private void cancelZoneJob(HttpExchange exchange) throws IOException {
        JsonObject body = zoneBody(exchange);
        final String id;
        try { id = java.util.UUID.fromString(body.get("id").getAsString()).toString(); }
        catch (RuntimeException invalid) { throw new HttpFailure(400, "Expected a zone job ID"); }
        sendJson(exchange, onServer((level, deadline) -> {
            if (!zoneManager().cancel(id)) throw new HttpFailure(404, "No active job with that ID");
            return "{\"status\":\"cancelled\"}";
        }));
    }

    private static JsonObject speedtestSnapshot(com.florentdubut.telecom.network.TrafficSession session) {
        JsonObject value = new JsonObject();
        value.addProperty("deviceId", session.getDeviceId());
        value.addProperty("sessionId", session.getSessionId().toString());
        value.addProperty("serverId", session.getServerId());
        value.addProperty("serverName", session.getServerName());
        value.addProperty("errorCode", session.getFailureReason());
        String state = session.getState().name();
        value.addProperty("state", state);
        value.addProperty("active", !state.equals("FINISHED") && !state.equals("FAILED"));
        value.addProperty("pingMs", session.getPingMs());
        value.addProperty("actualBandwidth", session.isTerminal() ? 0 : session.getActualBandwidth());
        value.addProperty("ticksElapsed", session.getTicksElapsed());
        value.addProperty("totalTicksPerPhase", session.getTotalTicksPerPhase());
        value.addProperty("downloadBandwidth", session.getFinalDownBw());
        value.addProperty("uploadBandwidth", session.getFinalUpBw());
        return value;
    }

    private void sendTile(HttpExchange exchange) throws IOException {
        Semaphore generation = jobSlot;
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.length() > 256) throw new HttpFailure(400, "Expected cx and cz");
        Map<String, String> params = new HashMap<>();
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || !Set.of("cx", "cz", "map").contains(pair[0])
                    || params.putIfAbsent(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) {
                throw new HttpFailure(400, "Invalid tile query");
            }
        }
        if (!params.containsKey("cx") || !params.containsKey("cz")) throw new HttpFailure(400, "Expected cx and cz");
        int cx = Integer.parseInt(params.get("cx")), cz = Integer.parseInt(params.get("cz"));
        if (Math.abs((long) cx) > 1874999 || Math.abs((long) cz) > 1874999) throw new HttpFailure(400, "Chunk outside world bounds");
        TerrainTileStore store = tileStore;
        if (store == null) throw new HttpFailure(503, "Terrain tile storage unavailable");
        if (params.containsKey("map") && !store.id().equals(params.get("map"))) {
            throw new HttpFailure(409, "Map belongs to a different world; refresh network data");
        }
        long key = ChunkPos.asLong(cx, cz);
        byte[] png;
        // Coalesce duplicate misses without ever holding a lock on the Minecraft thread.
        synchronized (tileLocks[Math.floorMod(Long.hashCode(key), tileLocks.length)]) {
            checkTileSession(generation, store);
            synchronized (tiles) { png = tiles.get(key); }
            if (png == null) {
                synchronized (tiles) {
                    Long retry = unavailableTiles.get(key);
                    if (retry != null && System.nanoTime() < retry) throw new HttpFailure(204, "Terrain not loaded");
                    unavailableTiles.remove(key);
                }
                try {
                    png = store.read(cx, cz);
                } catch (IOException e) {
                    throw new HttpFailure(503, "Saved terrain tile cannot be read; check map storage");
                }
            }
            if (png == null) {
                try {
                    png = renderTile(cx, cz, generation, store);
                } catch (HttpFailure missing) {
                    if (missing.status == 204) {
                        synchronized (tiles) {
                            checkTileSession(generation, store);
                            unavailableTiles.put(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
                            while (unavailableTiles.size() > 1024) unavailableTiles.remove(unavailableTiles.keySet().iterator().next());
                        }
                    }
                    throw missing;
                }
                // No old HTTP session may publish into the cache after a world switch.
                synchronized (this) {
                    checkTileSession(generation, store);
                    try {
                        png = store.storeIfAbsent(cx, cz, png);
                        WorldMapImageStore image = worldMap;
                        if (image != null) image.invalidate(cx, cz);
                    } catch (IOException e) {
                        throw new HttpFailure(503, "Terrain tile cannot be saved; check map storage");
                    }
                }
            }
            synchronized (tiles) {
                checkTileSession(generation, store);
                tiles.put(key, png);
                while (tiles.size() > 256) tiles.remove(tiles.keySet().iterator().next());
            }
        }
        checkTileSession(generation, store);
        send(exchange, 200, "image/png", png);
    }

    private void checkTileSession(Semaphore generation, TerrainTileStore store) {
        if (!running || jobSlot != generation || tileStore != store) throw new HttpFailure(503, "Map session changed");
    }

    public void captureChunk(ServerLevel level, ChunkPos position) {
        TerrainCaptureQueue capture = terrainCapture;
        if (running && capture != null && level.getServer() == minecraftServer
                && level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            capture.offer(position.x, position.z);
        }
    }

    private JsonObject mapImageMetadata() {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("ready", false);
        WorldMapImageStore image = worldMap;
        if (image == null) return metadata;
        try {
            WorldMapImageStore.Snapshot snapshot = image.snapshot();
            metadata.addProperty("ready", true);
            metadata.addProperty("revision", snapshot.revision());
            metadata.addProperty("originX", snapshot.originX());
            metadata.addProperty("originZ", snapshot.originZ());
            metadata.addProperty("blocksPerPixel", snapshot.blocksPerPixel());
            metadata.addProperty("width", snapshot.width());
            metadata.addProperty("height", snapshot.height());
            metadata.addProperty("empty", snapshot.empty());
            metadata.addProperty("updatedAt", snapshot.updatedAt());
        } catch (WorldMapImageStore.Pending pending) {
            metadata.addProperty("pending", true);
        } catch (java.io.UncheckedIOException | IllegalStateException failure) {
            metadata.addProperty("error", "Global map could not be built; check saved terrain images");
        }
        return metadata;
    }

    private void sendMapImage(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && query.length() > 128) throw new HttpFailure(400, "Invalid map query");
        Map<String, String> params = new HashMap<>();
        for (String part : query == null || query.isBlank() ? new String[0] : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || !pair[0].equals("map")
                    || params.putIfAbsent(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) {
                throw new HttpFailure(400, "Invalid map query");
            }
        }
        Semaphore generation = jobSlot;
        TerrainTileStore store = tileStore;
        WorldMapImageStore image = worldMap;
        if (store == null || image == null) throw new HttpFailure(503, "Terrain storage unavailable");
        checkTileSession(generation, store);
        if (params.containsKey("map") && !params.get("map").equals(store.id())) throw new HttpFailure(409, "Map belongs to a different world");
        try {
            WorldMapImageStore.Snapshot result = image.snapshot();
            checkTileSession(generation, store);
            if (result.empty()) throw new HttpFailure(204, "No captured terrain yet");
            var headers = exchange.getResponseHeaders();
            String etag = "\"" + store.id() + ":" + result.revision() + "\"";
            headers.set("ETag", etag);
            headers.set("X-Map-Id", store.id());
            headers.set("X-Map-Revision", result.revision());
            headers.set("X-Map-Origin-X", Integer.toString(result.originX()));
            headers.set("X-Map-Origin-Z", Integer.toString(result.originZ()));
            headers.set("X-Map-Scale", Integer.toString(result.blocksPerPixel()));
            headers.set("X-Map-Width", Integer.toString(result.width()));
            headers.set("X-Map-Height", Integer.toString(result.height()));
            headers.set("Access-Control-Expose-Headers", "ETag, X-Map-Id, X-Map-Revision, X-Map-Origin-X, X-Map-Origin-Z, X-Map-Scale, X-Map-Width, X-Map-Height");
            String condition = singleHeader(exchange, "If-None-Match");
            if (condition != null && java.util.Arrays.stream(condition.split(",")).map(String::trim)
                    .anyMatch(value -> value.equals(etag) || value.equals("W/" + etag) || value.equals("*"))) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            send(exchange, 200, "image/png", result.png());
        } catch (WorldMapImageStore.Pending pending) {
            throw new HttpFailure(202, "Preparing the global map image");
        } catch (java.io.UncheckedIOException | IllegalStateException failure) {
            throw new HttpFailure(503, "Global map unavailable; check saved terrain images");
        }
    }

    private byte[] renderTile(int cx, int cz, Semaphore generation, TerrainTileStore store) throws IOException {
        List<Integer> colors = readOnServer("tile:" + store.id() + ":" + cx + "," + cz, (level, deadline) -> {
            checkTileSession(generation, store);
            var chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) throw new HttpFailure(204, "Chunk not loaded");
            List<Integer> pixels = new ArrayList<>(256);
            for (int z = 0; z < 16; z++) {
                checkBudget(deadline);
                for (int x = 0; x < 16; x++) {
                    int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    BlockPos position = new BlockPos(cx * 16 + x, y, cz * 16 + z);
                    var state = chunk.getBlockState(position);
                    int color = state.getMapColor(chunk, position).col;
                    if ((y & 1) == 0) color = ((color & 0xff0000) * 9 / 10 & 0xff0000)
                            | ((color & 0xff00) * 9 / 10 & 0xff00) | ((color & 0xff) * 9 / 10);
                    pixels.add(0xff000000 | color);
                }
            }
            return List.copyOf(pixels);
        }).value();
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 256; i++) image.setRGB(i % 16, i / 16, colors.get(i));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static void sendStatic(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (!path.matches("/[a-zA-Z0-9_./-]+") || path.contains("..") || path.contains("//")) throw new HttpFailure(404, "Not found");
        try (var input = TelecomHttpServer.class.getResourceAsStream("/web" + path)) {
            if (input == null) throw new HttpFailure(404, "Not found");
            String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : path.endsWith(".css") ? "text/css; charset=utf-8"
                    : path.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
            send(exchange, 200, type, input.readAllBytes());
        }
    }

    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        send(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static final class HttpFailure extends RuntimeException {
        final int status;
        final String code;
        HttpFailure(int status, String message) { this(status, message, null); }
        HttpFailure(int status, String message, String code) { super(message); this.status = status; this.code = code; }
    }
}
