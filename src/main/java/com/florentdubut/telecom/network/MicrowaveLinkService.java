package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Server-thread-only derived FH links. Graph configuration, not loaded block entities, is authoritative. */
public final class MicrowaveLinkService {
    public static final int MAX_LINKS = 128;
    public static final int MAX_NODES = 8192;
    public static final int MAX_DISHES = 256;
    public static final long TICK_BUDGET_NANOS = 2_000_000;
    private static final Map<ServerLevel, State> STATES = new WeakHashMap<>();
    private MicrowaveLinkService() { }

    public record LinkStatus(BlockPos source, BlockPos target, String state, int capacityMbps,
                             int nominalCapacityMbps, int latencyMs, BlockPos blocker) {
        public LinkStatus {
            source = source.immutable(); target = target.immutable();
            if (blocker != null) blocker = blocker.immutable();
        }
    }

    private record Pair(BlockPos a, BlockPos b) {
        static Pair of(BlockPos a, BlockPos b) { return a.compareTo(b) < 0 ? new Pair(a.immutable(), b.immutable()) : new Pair(b.immutable(), a.immutable()); }
    }

    private static final class State {
        final TelecomNetworkGraph graph;
        final Map<Pair, Job> jobs = new LinkedHashMap<>();
        final List<BlockPos> dishes = new ArrayList<>();
        List<NetworkNode> scanning;
        final List<BlockPos> discovered = new ArrayList<>();
        List<NetworkEdge> published;
        int scanIndex, cursor;
        long catalogueRevision = -1, scanningRevision, terrainRevision, tick;
        boolean limited;

        State(TelecomNetworkGraph graph) { this.graph = graph; }
    }

    private static final class Job {
        final Pair pair;
        final MicrowaveConfig a, b;
        MicrowaveLinkEvaluator.Trace trace;
        NetworkEdge edge, previousEdge;
        MicrowaveLinkEvaluator.Result previousResult;
        long retryAt;
        int backoff = 20;
        boolean prefetched;

        Job(Pair pair, MicrowaveConfig a, MicrowaveConfig b) {
            this.pair = pair; this.a = a; this.b = b;
            reset();
        }

        void reset() {
            if (edge != null) { previousEdge = edge; previousResult = trace.result(); }
            edge = null;
            trace = new MicrowaveLinkEvaluator.Trace(pair.a(), a, pair.b(), b);
            prefetched = false;
        }

        boolean matches(TelecomNetworkGraph graph) {
            return a.equals(config(graph, pair.a())) && b.equals(config(graph, pair.b()));
        }

        void advance(ServerLevel level, long deadline, long tick) {
            if (trace.done()) {
                if (!trace.waitingForTerrain() || tick < retryAt) return;
                trace.resumeUnknown();
            }
            if (!prefetched && System.nanoTime() < deadline) {
                prefetched = true;
                RadioTerrainCache.prefetch(level, pair.a(), pair.b());
            }
            int before = trace.probes();
            boolean finished = trace.advance(pos -> sample(level, pos), 64, deadline);
            if (trace.probes() - before > 1 || !trace.waitingForTerrain()) backoff = 20;
            if (!finished) return;
            var result = trace.result();
            if (result.state().equals("unknown")) {
                retryAt = tick + backoff;
                backoff = Math.min(600, backoff * 2);
            } else if (result.capacityMbps() > 0 && (result.state().equals("ready") || result.state().equals("degraded"))) {
                edge = result.equals(previousResult) ? previousEdge : NetworkEdge.microwave(pair.a(), pair.b(),
                        result.nominalCapacityMbps(), result.capacityMbps(), (int) Math.ceil(trace.distance()), result.latencyMs());
                backoff = 20;
            }
        }
    }

    /** Loaded terrain always wins. Never calls getChunk, getBlockState on Level, or adds chunk tickets. */
    static SignalPropagator.Material sample(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) return SignalPropagator.Material.UNKNOWN;
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? RadioTerrainCache.sample(level, pos) : SignalPropagator.classify(chunk.getBlockState(pos));
    }

    public static void tick(ServerLevel level) { tickUntil(level, System.nanoTime() + TICK_BUDGET_NANOS); }

    /** Graph-owned traffic ticks can pass their existing graph instead of looking up SavedData again. */
    public static void tick(ServerLevel level, TelecomNetworkGraph graph) {
        tickUntil(level, graph, System.nanoTime() + TICK_BUDGET_NANOS);
    }

    static void tickUntil(ServerLevel level, long deadline) {
        tickUntil(level, TelecomNetworkGraph.get(level), deadline);
    }

    private static void tickUntil(ServerLevel level, TelecomNetworkGraph graph, long deadline) {
        State state = STATES.computeIfAbsent(level, ignored -> new State(graph));
        state.tick++;
        // Safety invalidation precedes all work and traffic, even if no evaluation budget remains.
        state.jobs.values().removeIf(job -> !job.matches(graph));
        catalogue(state, graph, deadline);
        if (!state.limited && state.scanning == null) {
            for (BlockPos source : state.dishes) {
                if (System.nanoTime() >= deadline) break;
                MicrowaveConfig a = config(graph, source);
                if (a == null || a.peer() == null || source.equals(a.peer())) continue;
                MicrowaveConfig b = config(graph, a.peer());
                if (b == null || !source.equals(b.peer())) continue;
                Pair pair = Pair.of(source, a.peer());
                if (!state.jobs.containsKey(pair) && state.jobs.size() < MAX_LINKS) {
                    state.jobs.put(pair, new Job(pair, config(graph, pair.a()), config(graph, pair.b())));
                }
            }
        }
        // Wired-only graphs need no terrain services, but must still withdraw former FH edges.
        if (state.jobs.isEmpty()) {
            publish(state, graph);
            return;
        }
        refreshTerrain(level, state);
        List<Job> jobs = new ArrayList<>(state.jobs.values());
        int idle = 0;
        while (!jobs.isEmpty() && idle < jobs.size() && System.nanoTime() < deadline) {
            Job job = jobs.get(Math.floorMod(state.cursor++, jobs.size()));
            boolean runnable = !job.trace.done() || (job.trace.waitingForTerrain() && state.tick >= job.retryAt);
            if (!runnable) { idle++; continue; }
            idle = 0;
            job.advance(level, deadline, state.tick);
        }
        // Consume arrivals published during a quantum; only live changes/reliability loss revoke proof.
        refreshTerrain(level, state);
        publish(state, graph);
    }

    private static MicrowaveConfig config(TelecomNetworkGraph graph, BlockPos position) {
        NetworkNode node = graph.getNode(position);
        return node != null && node.getType() == NetworkNode.NodeType.MICROWAVE_DISH ? node.getMicrowaveConfig() : null;
    }

    private static void catalogue(State state, TelecomNetworkGraph graph, long deadline) {
        var nodes = graph.getNodes();
        if (nodes.size() > MAX_NODES) {
            state.limited = true; state.jobs.clear(); state.dishes.clear(); state.scanning = null;
            state.catalogueRevision = -1;
            return;
        }
        long revision = graph.getTopologyRevision();
        // Topology only refreshes discovery. Existing jobs are keyed by endpoint/config snapshots,
        // and must survive cable rewires as well as our own operational-edge publications.
        if (state.scanning == null && state.catalogueRevision == revision) return;
        if (System.nanoTime() >= deadline) return;
        if (state.scanning == null || state.scanningRevision != revision) {
            state.scanning = List.copyOf(nodes);
            state.scanningRevision = revision;
            state.scanIndex = 0;
            state.discovered.clear();
        }
        while (state.scanIndex < state.scanning.size()) {
            if (System.nanoTime() >= deadline) return;
            NetworkNode node = state.scanning.get(state.scanIndex++);
            if (node.getType() != NetworkNode.NodeType.MICROWAVE_DISH) continue;
            if (state.discovered.size() == MAX_DISHES) {
                state.limited = true; state.jobs.clear();
                finishCatalogue(state);
                return;
            }
            state.discovered.add(node.getPosition());
        }
        state.limited = false;
        finishCatalogue(state);
    }

    private static void finishCatalogue(State state) {
        state.dishes.clear();
        state.dishes.addAll(state.discovered);
        state.dishes.sort(Comparator.naturalOrder());
        state.discovered.clear(); state.scanning = null;
        state.catalogueRevision = state.scanningRevision;
    }

    private static void refreshTerrain(ServerLevel level, State state) {
        refreshTerrain(level, state, null);
    }

    private static void refreshTerrain(ServerLevel level, State state, Long capturedUnload) {
        if (state.jobs.isEmpty()) return;
        long current = RadioTerrainCache.revision(level);
        if (current == state.terrainRevision) return;
        Set<Long> changes = RadioTerrainCache.changedChunks(level, state.terrainRevision, current);
        Set<Long> liveChanges = new java.util.HashSet<>();
        if (changes != null) {
            for (long chunk : changes) {
                if ((capturedUnload == null || chunk != capturedUnload)
                        && level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null) {
                    liveChanges.add(chunk);
                }
            }
        }
        for (Job job : state.jobs.values()) {
            if (current < state.terrainRevision || liveChanges.stream().anyMatch(chunk ->
                    job.trace.corridor().intersectsChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)))) {
                job.reset();
            } else if (job.trace.waitingForTerrain()) {
                BlockPos waiting = job.trace.result().blocker();
                if (changes == null || changes.contains(ChunkPos.asLong(waiting.getX() >> 4, waiting.getZ() >> 4))) {
                    // An unloaded disk arrival restores availability, not a world mutation. Resume
                    // the same voxel without replaying an already-clear prefix larger than the LRU.
                    job.retryAt = Math.min(job.retryAt, state.tick + 2);
                }
            }
        }
        // A truncated availability journal is not a world change. Synchronous load/edit hooks
        // already invalidate genuine mutations, even when more than 512 disk arrivals occurred.
        state.terrainRevision = current;
    }

    /** Called after RadioTerrainEvents captured the unloading chunk's current, complete 3D terrain. */
    public static void chunkUnloaded(ServerLevel level, int x, int z) {
        State state = STATES.get(level);
        if (state == null || state.jobs.isEmpty()) return;
        if (RadioTerrainCache.snapshot(level, x, z) == null) {
            invalidateChunk(level, x, z);
            return;
        }
        state.jobs.values().removeIf(job -> !job.matches(state.graph));
        // Strict live edits already withdrew affected edges. An intact edge plus a fresh capture
        // is the same validated terrain, now detached; do not turn player movement into route_lost.
        // Live changes in other chunks and cache revision rollback still fail closed.
        refreshTerrain(level, state, ChunkPos.asLong(x, z));
        publish(state, state.graph);
    }

    public static void invalidateEndpoint(ServerLevel level, BlockPos position) {
        State state = STATES.get(level);
        if (state == null) return;
        state.jobs.values().removeIf(job -> job.pair.a().equals(position) || job.pair.b().equals(position));
        state.catalogueRevision = -1;
        state.scanning = null;
        publish(state, state.graph);
    }

    public static void invalidateChunk(ServerLevel level, int x, int z) {
        State state = STATES.get(level);
        if (state == null) return;
        for (Job job : state.jobs.values()) {
            if (job.trace.corridor().intersectsChunk(x, z)) {
                job.reset();
                job.retryAt = 0;
                job.backoff = 20;
            }
        }
        publish(state, state.graph);
    }

    private static void publish(State state, TelecomNetworkGraph graph) {
        List<NetworkEdge> edges = state.jobs.values().stream().filter(job -> job.edge != null).map(job -> job.edge).toList();
        if (!edges.equals(state.published)) {
            long before = graph.getTopologyRevision();
            graph.setMicrowaveEdges(edges);
            state.published = edges;
            // Our own edge revision does not change the source catalogue.
            if (state.catalogueRevision == before) state.catalogueRevision = graph.getTopologyRevision();
            if (state.scanning != null && state.scanningRevision == before) state.scanningRevision = graph.getTopologyRevision();
        }
    }

    /** Read-only server-thread view. Revisions are consumed by ticks and explicit invalidation hooks. */
    public static LinkStatus status(ServerLevel level, BlockPos source) {
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
        return status(graph, STATES.get(level), source, Set.copyOf(graph.getEdges()));
    }

    private static LinkStatus status(TelecomNetworkGraph graph, State state, BlockPos source, Set<NetworkEdge> routedEdges) {
        MicrowaveConfig a = config(graph, source);
        if (a == null) return diagnostic(source, source, "unpaired", 0);
        if (!a.enabled()) return diagnostic(source, a.peer() == null ? source : a.peer(), "disabled", a.nominalCapacityMbps());
        MicrowaveConfig b = a.peer() == null ? null : config(graph, a.peer());
        if (b == null || source.equals(a.peer()) || !source.equals(b.peer())) return diagnostic(source, source, "unpaired", a.nominalCapacityMbps());
        if (!b.enabled()) return diagnostic(source, a.peer(), "disabled", a.nominalCapacityMbps());
        if (state != null && state.limited) return diagnostic(source, a.peer(), "limit", a.nominalCapacityMbps());
        Pair pair = Pair.of(source, a.peer());
        Job job = state == null ? null : state.jobs.get(pair);
        if (job == null || !job.matches(graph)) return diagnostic(source, a.peer(), "pending", a.nominalCapacityMbps());
        if (!job.trace.done()) return diagnostic(source, a.peer(), "pending", a.nominalCapacityMbps());
        var result = job.trace.result();
        if (result.capacityMbps() > 0 && (job.edge == null || !routedEdges.contains(job.edge))) {
            return diagnostic(source, a.peer(), "pending", a.nominalCapacityMbps());
        }
        return new LinkStatus(source, a.peer(), result.state(), result.capacityMbps(), result.nominalCapacityMbps(), result.latencyMs(), result.blocker());
    }

    private static LinkStatus diagnostic(BlockPos a, BlockPos b, String state, int nominal) {
        return new LinkStatus(a, b, state, 0, nominal, 0, null);
    }

    /** At most 128 unique diagnostics from one read-only server-thread graph snapshot. */
    public static List<LinkStatus> links(ServerLevel level) {
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
        State state = STATES.get(level);
        var nodes = graph.getNodes();
        if (nodes.size() > MAX_NODES) return List.of();
        List<BlockPos> sources = new ArrayList<>();
        for (NetworkNode node : nodes) {
            if (node.getType() == NetworkNode.NodeType.MICROWAVE_DISH) sources.add(node.getPosition());
            if (sources.size() == MAX_DISHES) break;
        }
        sources.sort(Comparator.naturalOrder());
        Set<NetworkEdge> routedEdges = Set.copyOf(graph.getEdges());
        Map<Pair, LinkStatus> statuses = new LinkedHashMap<>();
        for (BlockPos source : sources) {
            LinkStatus status = status(graph, state, source, routedEdges);
            statuses.putIfAbsent(Pair.of(status.source(), status.target()), status);
            if (statuses.size() == MAX_LINKS) break;
        }
        return List.copyOf(statuses.values());
    }

    public static void unload(ServerLevel level) {
        State removed = STATES.remove(level);
        if (removed != null) removed.graph.setMicrowaveEdges(List.of());
    }

    public static void clear() {
        for (ServerLevel level : List.copyOf(STATES.keySet())) unload(level);
    }
}
