package com.florentdubut.telecom.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/** Server-thread-only, demand-driven coverage. Each ray resumes within a shared tick budget. */
public final class CoverageService {
    public static final int TILE_SIZE = 128;
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_PENDING = 16;
    private static final TelecomFrequency[] FREQUENCIES = TelecomFrequency.values();
    private static final List<String> TECHNOLOGIES = List.of("2G", "3G", "4G", "5G");
    private static final long TICK_BUDGET = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long READY_IDLE_TTL = TimeUnit.MINUTES.toNanos(5);
    private static final long IDLE_TTL = TimeUnit.SECONDS.toNanos(45);
    // Four times the former work cap for four times as many cells, still sliced into 2 ms ticks.
    private static final int MAX_TRACE_QUANTA = 32768;
    private static final Map<ServerLevel, State> STATES = new WeakHashMap<>();
    private static long revision;

    private CoverageService() { }

    public record Request(int tileX, int tileZ, int step, String height, String antenna,
                          String technology, String band, int level) {
        public Request(int tileX, int tileZ, int step, String height, String antenna, String technology, String band) {
            this(tileX, tileZ, step, height, antenna, technology, band, 0);
        }

        public int tileSize() { return level >= 0 ? TILE_SIZE << level : TILE_SIZE >> -level; }

        private Request geometryKey() {
            return new Request(tileX, tileZ, step, height, antenna, "all", "all", level);
        }

        public Request {
            if (level < -3 || level > 6
                    || !Set.of(1, 8, 16, 32, 64, 128, 256, 512, 1024, 2048).contains(step)
                    || !Set.of("all", "2G", "3G", "4G", "5G").contains(technology)) {
                throw new IllegalArgumentException("Invalid coverage tile or filter");
            }
            int span = level >= 0 ? TILE_SIZE << level : TILE_SIZE >> -level;
            long originX = (long) tileX * span, originZ = (long) tileZ * span;
            if (originX < -29_999_999L || originZ < -29_999_999L
                    || originX + span - 1 > 29_999_999L || originZ + span - 1 > 29_999_999L
                    || step > span || span % step != 0 || span / step > 16) {
                throw new IllegalArgumentException("Invalid coverage extent or precision");
            }
            if (!height.equals("surface")) Integer.parseInt(height);
            if (!antenna.equals("all")) Long.parseLong(antenna);
            if (!band.equals("all")) {
                TelecomFrequency frequency = TelecomFrequency.valueOf(band);
                if (!technology.equals("all") && !frequency.getTechnology().equals(technology)) {
                    throw new IllegalArgumentException("Band does not match technology");
                }
            }
        }
    }

    public static final class BusyException extends RuntimeException {
        private final boolean retryable;

        public BusyException(String message) { this(message, true); }
        public BusyException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }
        public boolean retryable() { return retryable; }
    }

    private record Source(BlockPos position, int mask, boolean service, AntennaRadioConfig config,
                          List<TelecomFrequency> frequencies) {
        Source(BlockPos position, int mask, boolean service, AntennaRadioConfig config) {
            this(position, mask, service, config, List.copyOf(java.util.Arrays.stream(FREQUENCIES)
                    .filter(frequency -> (mask & (1 << frequency.ordinal())) != 0).toList()));
        }
    }
    private record Hit(Source source, TelecomFrequency frequency, float power) { }
    private record Cell(BlockPos position, Hit[] bestHits, int unknownBandMask, boolean geometryUnknown) {
        void selection(JsonObject item, String technology, String band) {
            Hit best = null;
            boolean unknown = geometryUnknown;
            for (TelecomFrequency frequency : FREQUENCIES) {
                if ((!technology.equals("all") && !technology.equals(frequency.getTechnology()))
                        || (!band.equals("all") && !band.equals(frequency.name()))) continue;
                unknown |= (unknownBandMask & (1 << frequency.ordinal())) != 0;
                Hit hit = bestHits[frequency.ordinal()];
                if (hit != null && (best == null || RadioSelection.compare(hit.frequency(), hit.power(), hit.source().position(),
                        best.frequency(), best.power(), best.source().position()) > 0)) best = hit;
            }
            String status = best != null ? "signal" : unknown ? "unknown" : "none";
            item.addProperty("state", status);
            item.addProperty("powerDbm", best == null ? null : best.power());
            item.addProperty("technology", best == null ? null : best.frequency().getTechnology());
            item.addProperty("band", best == null ? null : best.frequency().name());
            item.addProperty("antenna", best == null ? null : Long.toString(best.source().position().asLong()));
            item.addProperty("service", best == null ? (unknown ? "unknown" : "unavailable")
                    : best.source().service() ? "available" : "unavailable");
        }
    }

    private static final class State {
        final String identity = java.util.UUID.randomUUID().toString();
        final Map<Request, Job> entries = new LinkedHashMap<>(32, 0.75f, true);
        List<Source> sources = List.of();
        long topology = -1;
        long modelRevision;
        long terrainRevision;
        boolean spectrumDirty = true;
    }

    private static final class Job {
        final Request request;
        final List<Source> sources;
        final long revision = ++CoverageService.revision;
        final List<Cell> cells = new ArrayList<>();
        final Set<Long> chunks = new HashSet<>();
        final int cellCount;
        long requestedAt = System.nanoTime();
        long completedAt;
        long generatedAt;
        String failure;
        BlockPos receiver;
        int sourceIndex;
        int traceQuanta;
        SignalPropagator.MultiTrace trace;
        Hit[] bestHits = new Hit[FREQUENCIES.length];
        int unknownBandMask;

        Job(Request request, List<Source> sources) {
            this.request = request;
            this.sources = sources;
            cellCount = (request.tileSize() / request.step()) * (request.tileSize() / request.step());
        }

        boolean done() { return cells.size() == cellCount || failure != null; }

        void advance(ServerLevel level, long deadline) {
            if (done()) return;
            if (receiver == null) {
                int width = request.tileSize() / request.step();
                int x = Math.toIntExact((long) request.tileX() * request.tileSize() + cells.size() % width * request.step() + request.step() / 2);
                int z = Math.toIntExact((long) request.tileZ() * request.tileSize() + cells.size() / width * request.step() + request.step() / 2);
                chunks.add(ChunkPos.asLong(x >> 4, z >> 4));
                var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                var cached = chunk == null ? RadioTerrainCache.snapshot(level, x >> 4, z >> 4) : null;
                int y = request.height().equals("surface")
                        ? (chunk != null ? chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 2
                        : cached != null ? cached.surfaceY(x, z) + 2 : level.getMinY())
                        : Integer.parseInt(request.height());
                receiver = new BlockPos(x, y, z);
                if ((chunk == null && cached == null) || y < level.getMinY() || y > level.getMaxY()) {
                    finishCell(true);
                    return;
                }
            }

            if (sourceIndex >= sources.size()) {
                finishCell(false);
                return;
            }

            Source source = sources.get(sourceIndex);
            if (trace == null) {
                trace = new SignalPropagator.MultiTrace(source.position(), receiver, source.frequencies(), source.config());
            }
            if (++traceQuanta > MAX_TRACE_QUANTA) {
                failure = "Coverage work limit exceeded; use a coarser grid or select a single antenna";
                completedAt = System.nanoTime();
                return;
            }
            boolean finished = trace.advance(level, 64, deadline);
            chunks.addAll(trace.visitedChunks());
            if (chunks.size() > 4096) {
                failure = "Coverage crosses too many chunks; select a single antenna";
                completedAt = System.nanoTime();
                return;
            }
            if (!finished) return;
            for (SignalPropagator.SignalResult result : trace.results()) {
                TelecomFrequency frequency = result.frequency;
                int index = frequency.ordinal();
                if (!result.known) unknownBandMask |= 1 << index;
                Hit best = bestHits[index];
                if (result.known && result.powerDbm > SignalPropagator.MIN_SIGNAL
                        && (best == null || RadioSelection.compare(frequency, result.powerDbm, source.position(),
                        best.frequency(), best.power(), best.source().position()) > 0)) {
                    bestHits[index] = new Hit(source, frequency, result.powerDbm);
                }
            }
            trace = null;
            sourceIndex++;
        }

        private void finishCell(boolean geometryUnknown) {
            cells.add(new Cell(receiver, bestHits, unknownBandMask, geometryUnknown));
            receiver = null;
            bestHits = new Hit[FREQUENCIES.length];
            unknownBandMask = 0;
            sourceIndex = 0;
            trace = null;
            if (done()) {
                completedAt = System.nanoTime();
                generatedAt = System.currentTimeMillis();
            }
        }

        String snapshot(Request originalRequest, String modelRevision) {
            if (failure != null) throw new BusyException(failure, false);
            JsonObject result = new JsonObject();
            result.addProperty("status", done() ? "ready" : "pending");
            result.addProperty("revision", Long.toString(revision));
            result.addProperty("modelRevision", modelRevision);
            result.addProperty("tileX", request.tileX());
            result.addProperty("tileZ", request.tileZ());
            result.addProperty("originX", (long) request.tileX() * request.tileSize());
            result.addProperty("originZ", (long) request.tileZ() * request.tileSize());
            result.addProperty("tileSize", request.tileSize());
            result.addProperty("level", request.level());
            result.addProperty("step", request.step());
            result.addProperty("height", request.height());
            result.addProperty("technologyFilter", originalRequest.technology());
            result.addProperty("bandFilter", originalRequest.band());
            result.addProperty("progress", (double) cells.size() / cellCount);
            result.addProperty("generatedAt", generatedAt);
            result.addProperty("validForMs", done() ? 30000 : 1000);
            result.addProperty("maxRange", SignalPropagator.MAX_RANGE);
            JsonArray values = new JsonArray();
            for (Cell cell : cells) {
                JsonObject item = new JsonObject();
                item.addProperty("x", cell.position().getX());
                item.addProperty("y", cell.position().getY());
                item.addProperty("z", cell.position().getZ());
                cell.selection(item, originalRequest.technology(), originalRequest.band());
                JsonObject technologies = new JsonObject();
                // Technology switches only change the legacy selection; a band filter applies to every view.
                for (String technology : TECHNOLOGIES) {
                    JsonObject selected = new JsonObject();
                    cell.selection(selected, technology, originalRequest.band());
                    technologies.add(technology, selected);
                }
                item.add("technologies", technologies);
                values.add(item);
            }
            result.add("cells", values);
            return result.toString();
        }
    }

    public static String request(ServerLevel level, Request request, long deadline) {
        if (!request.height().equals("surface")) {
            int height = Integer.parseInt(request.height());
            if (height < level.getMinY() || height > level.getMaxY()) throw new IllegalArgumentException("Height outside dimension");
        }
        State state = STATES.computeIfAbsent(level, ignored -> new State());
        refreshTerrain(level, state);
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
        if (state.spectrumDirty || state.topology != graph.getTopologyRevision()) refresh(state, graph, deadline);
        prune(state);
        Request key = request.geometryKey();
        Job job = state.entries.get(key);
        if (job == null) {
            if (state.entries.values().stream().filter(entry -> !entry.done()).count() >= MAX_PENDING) {
                throw new BusyException("Coverage queue full; retry visible tiles later");
            }
            List<Source> candidates = new ArrayList<>();
            for (Source source : state.sources) {
                checkBudget(deadline);
                if (!request.antenna().equals("all") && !request.antenna().equals(Long.toString(source.position().asLong()))) continue;
                int span = request.tileSize();
                long minX = (long) request.tileX() * span, minZ = (long) request.tileZ() * span;
                double dx = Math.max(0, Math.max(minX - (double) source.position().getX(), source.position().getX() - (double) (minX + span)));
                double dz = Math.max(0, Math.max(minZ - (double) source.position().getZ(), source.position().getZ() - (double) (minZ + span)));
                if (dx * dx + dz * dz > (double) SignalPropagator.MAX_RANGE * SignalPropagator.MAX_RANGE) continue;
                if (!source.frequencies().isEmpty()) candidates.add(source);
            }
            if (candidates.size() > 64) throw new BusyException("Too many antennas; select a single antenna", false);
            candidates.sort(java.util.Comparator.comparingLong(source -> source.position().asLong()));
            if (state.entries.size() >= MAX_ENTRIES) {
                Iterator<Job> entries = state.entries.values().iterator();
                while (entries.hasNext()) {
                    if (entries.next().done()) { entries.remove(); break; }
                }
            }
            job = new Job(key, List.copyOf(candidates));
            state.entries.put(key, job);
        }
        job.requestedAt = System.nanoTime();
        return job.snapshot(request, state.identity + ":" + state.modelRevision);
    }

    private static void refresh(State state, TelecomNetworkGraph graph, long deadline) {
        invalidateModel(state);
        if (graph.getNodes().size() > 8192 || graph.getEdges().size() > 16384) throw new BusyException("Coverage topology limit exceeded", false);
        Map<BlockPos, List<BlockPos>> adjacency = new HashMap<>();
        for (NetworkEdge edge : graph.getEdges()) {
            checkBudget(deadline);
            adjacency.computeIfAbsent(edge.getNodeA(), ignored -> new ArrayList<>()).add(edge.getNodeB());
            adjacency.computeIfAbsent(edge.getNodeB(), ignored -> new ArrayList<>()).add(edge.getNodeA());
        }
        Set<BlockPos> connected = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (NetworkNode node : graph.getNodes()) {
            checkBudget(deadline);
            if (node.getType() == NetworkNode.NodeType.SERVER && connected.add(node.getPosition())) queue.add(node.getPosition());
        }
        while (!queue.isEmpty()) {
            checkBudget(deadline);
            for (BlockPos neighbor : adjacency.getOrDefault(queue.removeFirst(), List.of())) {
                if (connected.add(neighbor)) queue.addLast(neighbor);
            }
        }
        List<Source> sources = new ArrayList<>();
        for (NetworkNode node : graph.getNodes()) {
            checkBudget(deadline);
            if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                sources.add(new Source(node.getPosition().immutable(), node.getFrequenciesMask(),
                        connected.contains(node.getPosition()), node.getRadioConfig()));
            }
        }
        state.sources = List.copyOf(sources);
        state.topology = graph.getTopologyRevision();
        state.spectrumDirty = false;
    }

    private static void checkBudget(long deadline) {
        if (System.nanoTime() >= deadline) throw new BusyException("Coverage snapshot budget exceeded");
    }

    private static void prune(State state) {
        long now = System.nanoTime();
        state.entries.values().removeIf(job -> now - job.requestedAt >= (job.done() ? READY_IDLE_TTL : IDLE_TTL));
    }

    public static void tick(MinecraftServer server) {
        tickUntil(server, System.nanoTime() + TICK_BUDGET);
    }

    static void tickUntil(MinecraftServer server, long deadline) {
        for (ServerLevel level : server.getAllLevels()) {
            State state = STATES.get(level);
            if (state == null) continue;
            refreshTerrain(level, state);
            prune(state);
            if (state.spectrumDirty || state.topology != TelecomNetworkGraph.get(level).getTopologyRevision()) {
                invalidateModel(state);
                continue;
            }
            List<Job> pending = state.entries.values().stream().filter(job -> !job.done()).toList();
            boolean work;
            do {
                work = false;
                for (Job job : pending) {
                    if (System.nanoTime() >= deadline) return;
                    if (!job.done()) {
                        job.advance(level, deadline);
                        work = true;
                    }
                }
            } while (work && System.nanoTime() < deadline);
        }
    }

    public static void invalidateChunk(ServerLevel level, int chunkX, int chunkZ) {
        MicrowaveLinkService.invalidateChunk(level, chunkX, chunkZ);
        invalidateCoverageChunk(level, chunkX, chunkZ);
    }

    /** Lifecycle coverage refresh only; MicrowaveEvents handles live/cached terrain transitions. */
    public static void invalidateCoverageChunk(ServerLevel level, int chunkX, int chunkZ) {
        State state = STATES.get(level);
        if (state == null) return;
        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        if (state.entries.values().removeIf(job -> job.chunks.contains(chunk))) state.modelRevision++;
    }

    public static void invalidateArea(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        State state = STATES.get(level);
        if (state == null) return;
        boolean changed = state.entries.values().removeIf(job -> {
            long size = job.request.tileSize();
            long x = job.request.tileX() * size, z = job.request.tileZ() * size;
            return x <= maxX && z <= maxZ && x + size > minX && z + size > minZ;
        });
        if (changed) state.modelRevision++;
    }

    public static void invalidateAntennas(ServerLevel level) {
        RadioAccessService.invalidate(level);
        State state = STATES.get(level);
        if (state != null) {
            state.spectrumDirty = true;
            invalidateModel(state);
        }
    }

    private static void invalidateModel(State state) {
        if (!state.entries.isEmpty()) {
            state.entries.clear();
            state.modelRevision++;
        }
    }

    private static void refreshTerrain(ServerLevel level, State state) {
        // The disk worker only publishes cache revisions. Tile mutation stays on the server thread.
        long current = RadioTerrainCache.revision(level);
        if (state.terrainRevision != current) {
            if (!level.getServer().isSameThread()) throw new IllegalStateException("Coverage invalidation must run on server thread");
            Set<Long> changed = RadioTerrainCache.changedChunks(level, state.terrainRevision, current);
            if (changed == null) invalidateModel(state);
            else if (state.entries.values().removeIf(job -> !java.util.Collections.disjoint(job.chunks, changed))) {
                state.modelRevision++;
            }
            state.terrainRevision = current;
        }
    }

    public static String modelRevision(ServerLevel level) {
        State state = STATES.computeIfAbsent(level, ignored -> new State());
        refreshTerrain(level, state);
        if (state.topology != TelecomNetworkGraph.get(level).getTopologyRevision()) invalidateModel(state);
        return state.identity + ":" + state.modelRevision;
    }

    public static void unload(ServerLevel level) { STATES.remove(level); }

    public static void clear() { STATES.clear(); }
}
