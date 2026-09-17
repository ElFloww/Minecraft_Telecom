package com.florentdubut.telecom.server;

import com.florentdubut.telecom.network.CoverageService;
import com.florentdubut.telecom.network.RadioTerrainCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Explicit administrative work. All methods are called on the Minecraft server thread. */
final class ZoneJobManager implements AutoCloseable {
    static final int MAX_TERRAIN_CHUNKS = 4096;
    static final int MAX_COVERAGE_TILES = 1024;
    private static final TicketType ZONE_TICKET = new TicketType(1200L, TicketType.FLAG_LOADING);
    private final MinecraftServer server;
    private final TerrainCaptureQueue captures;
    private final BiConsumer<ServerLevel, LevelChunk> observe;
    private final ArrayDeque<Job> history = new ArrayDeque<>();
    private Job current;
    private boolean closed;

    record Request(String kind, int minX, int minZ, int maxX, int maxZ, int step,
                   String height, String antenna, String technology, String band) {
        Request {
            if (!kind.equals("terrain") && !kind.equals("coverage")) throw new IllegalArgumentException("Unknown job kind");
            if (minX < -30_000_000 || minZ < -30_000_000 || maxX > 29_999_999 || maxZ > 29_999_999
                    || minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Invalid world bounds");
            if (kind.equals("terrain")) {
                step = 16;
                height = "surface";
                antenna = technology = band = "all";
            } else if (step != 1 && step != 8 && step != 16) throw new IllegalArgumentException("Expected step 1, 8 or 16");
            int span = kind.equals("terrain") ? 16 : step * 16;
            long count = ((long) Math.floorDiv(maxX, span) - Math.floorDiv(minX, span) + 1)
                    * ((long) Math.floorDiv(maxZ, span) - Math.floorDiv(minZ, span) + 1);
            if (count > (kind.equals("terrain") ? MAX_TERRAIN_CHUNKS : MAX_COVERAGE_TILES)) throw new IllegalArgumentException("Zone exceeds job limit");
            if (kind.equals("coverage")) {
                new CoverageService.Request(Math.floorDiv(minX, span), Math.floorDiv(minZ, span), step,
                        height, antenna, technology, band, step == 1 ? -3 : step == 8 ? 0 : 1);
            }
        }

        int span() { return kind.equals("terrain") ? 16 : step * 16; }
        int width() { return Math.floorDiv(maxX, span()) - Math.floorDiv(minX, span()) + 1; }
        int total() { return width() * (Math.floorDiv(maxZ, span()) - Math.floorDiv(minZ, span()) + 1); }
        int level() { return step == 1 ? -3 : step == 8 ? 0 : 1; }
    }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final Request request;
        final JsonArray coverageTiles = new JsonArray();
        String state = "queued", message = "Waiting for a server tick";
        int completed, waitingTicks, unknown;
        ChunkPos ticket;
        ServerLevel level;
        CompletableFuture<ChunkResult<ChunkAccess>> generation;
        CompletableFuture<Boolean> capture;

        Job(Request request) {
            this.request = request;
            if (request.kind().equals("coverage")) for (int i = 0; i < request.total(); i++) {
                ChunkPos position = position(i);
                JsonObject tile = new JsonObject();
                tile.addProperty("x", position.x); tile.addProperty("z", position.z);
                tile.addProperty("level", request.level()); tile.addProperty("step", request.step());
                coverageTiles.add(tile);
            }
        }
        boolean active() { return state.equals("queued") || state.equals("running"); }
        ChunkPos position(int index) {
            return new ChunkPos(Math.floorDiv(request.minX(), request.span()) + index % request.width(),
                    Math.floorDiv(request.minZ(), request.span()) + index / request.width());
        }
        CoverageService.Request coverage(int index) {
            ChunkPos pos = position(index);
            return new CoverageService.Request(pos.x, pos.z, request.step(), request.height(), request.antenna(),
                    request.technology(), request.band(), request.level());
        }
    }

    ZoneJobManager(MinecraftServer server, TerrainCaptureQueue captures) {
        this(server, captures, RadioTerrainCache::capture);
    }

    ZoneJobManager(MinecraftServer server, TerrainCaptureQueue captures, BiConsumer<ServerLevel, LevelChunk> observe) {
        this.server = Objects.requireNonNull(server);
        this.captures = Objects.requireNonNull(captures);
        this.observe = Objects.requireNonNull(observe);
    }

    String start(Request request) {
        if (closed) throw new IllegalStateException("Zone manager stopped");
        if (current != null && current.active()) throw new IllegalStateException("A zone job is already active");
        ServerLevel level = server.overworld();
        if (level == null) throw new IllegalStateException("Overworld unavailable");
        if (!request.height().equals("surface")) {
            int height = Integer.parseInt(request.height());
            if (height < level.getMinY() || height > level.getMaxY()) throw new IllegalArgumentException("Height outside dimension");
        }
        if (request.kind().equals("coverage")) CoverageService.invalidateArea(level, request.minX(), request.minZ(), request.maxX(), request.maxZ());
        current = new Job(request);
        current.level = level;
        history.addLast(current);
        if (history.size() > 8) history.removeFirst();
        return current.id;
    }

    boolean cancel(String id) {
        if (current == null || !current.id.equals(id) || !current.active()) return false;
        finish(current, "cancelled", "Stopped; already generated chunks are kept");
        return true;
    }

    void tick() {
        Job job = current;
        if (closed || job == null || !job.active()) return;
        job.state = "running";
        try {
            if (++job.waitingTicks > 1200) throw new IllegalStateException("No progress for 1200 game ticks; reduce the zone or check capture storage");
            if (job.request.kind().equals("terrain")) tickTerrain(job);
            else tickCoverage(job);
            if (job.completed == job.request.total()) finish(job, "completed", job.request.kind().equals("terrain")
                    ? "Terrain captured; the global image updates progressively"
                    : "Coverage calculated; unknown points: " + job.unknown);
        } catch (CoverageService.BusyException busy) {
            job.message = busy.getMessage();
        } catch (RuntimeException failure) {
            finish(job, "failed", failure.getMessage() == null ? "Zone processing failed" : failure.getMessage());
        }
    }

    private void tickTerrain(Job job) {
        var chunks = job.level.getChunkSource();
        if (job.generation == null) {
            job.ticket = job.position(job.completed);
            chunks.addTicketWithRadius(ZONE_TICKET, job.ticket, 0);
            job.generation = chunks.getChunkFuture(job.ticket.x, job.ticket.z, ChunkStatus.FULL, true);
            job.message = "Generating/loading chunk " + job.ticket.x + ", " + job.ticket.z;
            return;
        }
        chunks.addTicketWithRadius(ZONE_TICKET, job.ticket, 0);
        if (!job.generation.isDone()) return;
        var result = job.generation.getNow(null);
        if (result == null || !result.isSuccess()) throw new IllegalStateException(result == null ? "Chunk generation failed" : result.getError());
        if (job.capture == null) {
            LevelChunk chunk = chunks.getChunkNow(job.ticket.x, job.ticket.z);
            if (chunk == null) return;
            observe.accept(job.level, chunk);
            job.capture = captures.captureAsync(job.ticket.x, job.ticket.z);
            job.message = "Saving captured chunk " + job.ticket.x + ", " + job.ticket.z;
            return;
        }
        if (!job.capture.isDone()) return;
        if (!Boolean.TRUE.equals(job.capture.getNow(false))) throw new IllegalStateException("Terrain capture failed");
        release(job);
        job.completed++;
        job.waitingTicks = 0;
    }

    private void tickCoverage(Job job) {
        long deadline = System.nanoTime() + 2_000_000L;
        JsonObject result = JsonParser.parseString(CoverageService.request(job.level, job.coverage(job.completed), deadline)).getAsJsonObject();
        job.message = "Computing selected coverage tile " + (job.completed + 1);
        if (!result.get("status").getAsString().equals("ready")) return;
        for (var cell : result.getAsJsonArray("cells")) {
            if (cell.getAsJsonObject().get("state").getAsString().equals("unknown")) job.unknown++;
        }
        job.completed++;
        job.waitingTicks = 0;
    }

    private void release(Job job) {
        if (job.ticket != null) job.level.getChunkSource().removeTicketWithRadius(ZONE_TICKET, job.ticket, 0);
        job.ticket = null;
        // Vanilla may share the generation future: remove only our ticket, never cancel its work.
        job.generation = null;
        job.capture = null;
    }

    private void finish(Job job, String state, String message) {
        job.state = state;
        job.message = message;
        release(job);
    }

    String status() {
        JsonObject root = new JsonObject();
        if (current == null) { root.add("job", com.google.gson.JsonNull.INSTANCE); return root.toString(); }
        Job job = current;
        Request r = job.request;
        JsonObject value = new JsonObject(), bounds = new JsonObject();
        value.addProperty("id", job.id);
        value.addProperty("kind", r.kind());
        value.addProperty("state", job.state);
        value.addProperty("total", r.total());
        value.addProperty("completed", job.completed);
        value.addProperty("progress", (double) job.completed / r.total());
        value.addProperty("message", job.message);
        value.addProperty("unknownPoints", job.unknown);
        bounds.addProperty("minX", r.minX()); bounds.addProperty("minZ", r.minZ());
        bounds.addProperty("maxX", r.maxX()); bounds.addProperty("maxZ", r.maxZ());
        value.add("bounds", bounds);
        value.addProperty("step", r.step()); value.addProperty("height", r.height());
        value.addProperty("antenna", r.antenna()); value.addProperty("technology", r.technology()); value.addProperty("band", r.band());
        value.add("coverageTiles", job.coverageTiles);
        root.add("job", value);
        return root.toString();
    }

    @Override public void close() {
        closed = true;
        if (current != null && current.active()) finish(current, "cancelled", "Server stopped");
        history.clear();
    }
}
