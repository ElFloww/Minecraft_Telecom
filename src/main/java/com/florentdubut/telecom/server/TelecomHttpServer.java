package com.florentdubut.telecom.server;

import com.florentdubut.telecom.network.NetworkEdge;
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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

/** HTTP configuration uses telecom.http.{enabled,bind,port,origins}; secrets only use TELECOM_HTTP_TOKEN. */
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
    private boolean publicBind;
    private byte[] token = new byte[0];
    private Set<String> origins = Set.of();
    private Set<String> hosts = Set.of();
    private final Map<Long, Tile> tiles = new LinkedHashMap<>(256, 0.75f, true);

    public synchronized void start(MinecraftServer ms) {
        if (server != null || !Boolean.parseBoolean(System.getProperty("telecom.http.enabled", "true"))) return;
        try {
            String bind = System.getProperty("telecom.http.bind", "127.0.0.1");
            int port = Integer.parseInt(System.getProperty("telecom.http.port", "8080"));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid HTTP port");
            InetAddress address = InetAddress.getByName(bind);
            publicBind = !address.isLoopbackAddress();
            String secret = System.getenv("TELECOM_HTTP_TOKEN");
            if (secret != null && !secret.isBlank()) {
                if (secret.length() > 1024 || !secret.matches("[\\x21-\\x7e]+")) {
                    throw new IllegalArgumentException("HTTP token must be 1-1024 printable ASCII characters without spaces");
                }
                token = secret.getBytes(StandardCharsets.UTF_8);
            }
            if (publicBind && token.length == 0) throw new IllegalArgumentException("Non-loopback HTTP requires TELECOM_HTTP_TOKEN");
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
        if (server != null) server.stop(0);
        server = null;
        if (workers != null) workers.shutdownNow();
        workers = null;
        synchronized (tiles) { tiles.clear(); }
        token = new byte[0];
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
            boolean mutation = path.equals("/api/speedtest");
            if (api && !Set.of("/api/network", "/api/player", "/api/tile", "/api/nperf_map", "/api/speedtest").contains(path)) {
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
                        if (!Set.of("authorization", "content-type").contains(header.trim().toLowerCase(java.util.Locale.ROOT))) {
                            throw new HttpFailure(403, "Preflight header not allowed");
                        }
                    }
                }
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", allowedMethod);
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!allowedMethod.equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", allowedMethod + ", OPTIONS");
                throw new HttpFailure(405, "Method not allowed");
            }
            if (api && (publicBind || mutation)) authenticate(exchange);
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
                case "/api/network" -> sendJson(exchange, onServer(this::networkSnapshot));
                case "/api/nperf_map" -> sendJson(exchange, onServer(this::coverageSnapshot));
                case "/api/player" -> sendJson(exchange, onServer((level, deadline) -> {
                    if (level.players().isEmpty()) throw new HttpFailure(404, "No player in overworld");
                    var player = level.players().getFirst();
                    return "{\"x\":" + player.getBlockX() + ",\"z\":" + player.getBlockZ() + "}";
                }));
                case "/api/tile" -> sendTile(exchange);
                case "/api/speedtest" -> startSpeedtest(exchange);
                case "/favicon.ico" -> exchange.sendResponseHeaders(204, -1);
                default -> sendStatic(exchange, path);
            }
        } catch (HttpFailure e) {
            if (e.status == 429 || e.status == 503) exchange.getResponseHeaders().set("Retry-After", "1");
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
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

    private void authenticate(HttpExchange exchange) {
        String auth = singleHeader(exchange, "Authorization");
        if (token.length == 0 || auth == null || !auth.startsWith("Bearer ") || auth.length() > 1031
                || !MessageDigest.isEqual(token, auth.substring(7).getBytes(StandardCharsets.UTF_8))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"telecom\"");
            throw new HttpFailure(401, "A valid session token is required");
        }
    }

    @FunctionalInterface
    private interface SnapshotJob<T> { T run(ServerLevel level, long deadline); }

    private <T> T onServer(SnapshotJob<T> work) {
        MinecraftServer ms = minecraftServer;
        Semaphore slot = jobSlot;
        if (!running || ms == null) throw new HttpFailure(503, "Minecraft unavailable");
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

    private void startSpeedtest(HttpExchange exchange) throws IOException {
        String contentType = singleHeader(exchange, "Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new HttpFailure(415, "Expected application/json");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new HttpFailure(413, "Body too large");
        final long pos;
        final int duration;
        try {
            JsonObject payload = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            pos = Long.parseLong(payload.get("pos").getAsString());
            duration = Integer.parseInt(payload.get("duration").getAsString());
            if (!DURATIONS.contains(duration)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new HttpFailure(400, "Expected pos as decimal string and duration in 300,600,1200,6000,12000");
        }
        sendJson(exchange, onServer((level, deadline) -> {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
            BlockPos position = BlockPos.of(pos);
            NetworkNode node = graph.getNode(position);
            if (node == null || node.getType() != NetworkNode.NodeType.ROUTER) throw new HttpFailure(404, "Router not found");
            String ip = node.getIpAddress();
            if (ip == null || ip.isBlank()) throw new HttpFailure(409, "Router has no IP");
            var before = graph.getSessionByIp(ip);
            if (before != null && !before.isPassive()) throw new HttpFailure(409, "Speedtest already active");
            checkBudget(deadline);
            graph.startSpeedtest(position, ip, node.getCapacityDown(), node.getCapacityUp(), 0, 0, duration, false, null);
            var after = graph.getSessionByIp(ip);
            if (after == null || after == before || after.isPassive()) throw new HttpFailure(409, "Speedtest rejected: capacity, session limit or route unavailable");
            return "{\"status\":\"started\"}";
        }));
    }

    private record Tile(byte[] png, long expires) { }

    private void sendTile(HttpExchange exchange) throws IOException {
        Semaphore generation = jobSlot;
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.length() > 256) throw new HttpFailure(400, "Expected cx and cz");
        Map<String, String> params = new HashMap<>();
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || params.putIfAbsent(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) {
                throw new HttpFailure(400, "Invalid tile query");
            }
        }
        if (!params.containsKey("cx") || !params.containsKey("cz")) throw new HttpFailure(400, "Expected cx and cz");
        int cx = Integer.parseInt(params.get("cx")), cz = Integer.parseInt(params.get("cz"));
        if (Math.abs((long) cx) > 1874999 || Math.abs((long) cz) > 1874999) throw new HttpFailure(400, "Chunk outside world bounds");
        long key = ChunkPos.asLong(cx, cz);
        Tile cached;
        synchronized (tiles) { cached = tiles.get(key); }
        if (cached != null && cached.expires > System.nanoTime()) {
            send(exchange, 200, "image/png", cached.png);
            return;
        }
        List<Integer> colors = onServer((level, deadline) -> {
            var chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) throw new HttpFailure(404, "Chunk not loaded");
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
        });
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 256; i++) image.setRGB(i % 16, i / 16, colors.get(i));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        byte[] png = output.toByteArray();
        synchronized (tiles) {
            if (running && jobSlot == generation) {
                tiles.put(key, new Tile(png, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)));
                while (tiles.size() > 256) tiles.remove(tiles.keySet().iterator().next());
            }
        }
        send(exchange, 200, "image/png", png);
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
        HttpFailure(int status, String message) { super(message); this.status = status; }
    }
}
