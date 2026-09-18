package com.florentdubut.telecom.network;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Opt-in, server-thread-only measurements. Never persisted with the network. */
public final class NetworkDiagnostics {
    public static final int MAX_RECALCULATIONS = 128;
    public static final int MAX_TICKS = 1200;

    public enum Cause {
        CABLE_PLACE, CABLE_REMOVE, NODE_LOAD, NODE_REMOVE, PLAYER_LOGIN, EXPLICIT_RECALCULATION, UNSPECIFIED
    }

    public record Distribution(int samples, long p50, long p95, long p99, long max) {}

    public record Recalculation(Map<Cause, Long> causes, boolean success, long durationNanos,
                                long waitNanos, long waitTicks, long publicationNanos,
                                long allocatedBytes, long traceSteps, long blockReads,
                                long chunkRequests, long pathCopies, long copiedPathReferences,
                                int nodes, int edgesBefore, int edgesAfter) {}

    private final LongSupplier clock;
    private final LongSupplier allocatedBytes;
    private final ArrayDeque<Recalculation> recalculations = new ArrayDeque<>();
    private final long[] ticks = new long[MAX_TICKS];
    private final EnumMap<Cause, Long> requests = new EnumMap<>(Cause.class);
    private boolean enabled;
    private long tick;
    private long tickStart = -1;
    private int tickSamples;
    private int tickCursor;
    private long completed;
    private long failed;
    private long skippedTrafficTicks;
    private Batch pending;
    private Batch delayed;
    private Batch scheduled;

    public NetworkDiagnostics() {
        this(System::nanoTime, allocationCounter());
    }

    NetworkDiagnostics(LongSupplier clock, LongSupplier allocatedBytes) {
        this.clock = clock;
        this.allocatedBytes = allocatedBytes;
    }

    private static LongSupplier allocationCounter() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported()) {
            return () -> bean.isThreadAllocatedMemoryEnabled()
                    ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
        }
        return () -> -1;
    }

    public boolean isEnabled() { return enabled; }

    public void start() {
        recalculations.clear();
        requests.clear();
        tickSamples = tickCursor = 0;
        completed = failed = skippedTrafficTicks = 0;
        pending = delayed = scheduled = null;
        tickStart = -1;
        enabled = true;
    }

    public void stop() {
        enabled = false;
        pending = delayed = scheduled = null;
        tickStart = -1;
    }

    void nextTick() { tick++; }

    public void startServerTick() {
        if (enabled) tickStart = clock.getAsLong();
    }

    public void finishServerTick() {
        if (!enabled || tickStart == -1) return;
        ticks[tickCursor] = Math.max(0, clock.getAsLong() - tickStart);
        tickCursor = (tickCursor + 1) % MAX_TICKS;
        tickSamples = Math.min(MAX_TICKS, tickSamples + 1);
        tickStart = -1;
    }

    void request(Cause cause, boolean isDelayed) {
        if (!enabled) return;
        requests.merge(cause, 1L, Long::sum);
        Batch batch = isDelayed ? delayed : pending;
        if (batch == null) batch = new Batch(clock.getAsLong(), tick);
        batch.causes.merge(cause, 1L, Long::sum);
        if (isDelayed) delayed = batch;
        else pending = batch;
    }

    void delayedReady() {
        if (delayed == null) return;
        if (pending == null) pending = delayed;
        else {
            pending.firstNanos = Math.min(pending.firstNanos, delayed.firstNanos);
            pending.firstTick = Math.min(pending.firstTick, delayed.firstTick);
            delayed.causes.forEach((cause, count) -> pending.causes.merge(cause, count, Long::sum));
        }
        delayed = null;
    }

    void runScheduled(Runnable recalculation) {
        // Detach before tracing: chunk loads can enqueue new node discoveries during the trace.
        scheduled = pending;
        pending = null;
        if (enabled) {
            skippedTrafficTicks++;
            if (scheduled == null) {
                scheduled = new Batch(clock.getAsLong(), tick);
                scheduled.causes.put(Cause.UNSPECIFIED, 1L);
            }
        }
        try {
            recalculation.run();
        } finally {
            scheduled = null;
        }
    }

    Trace begin(int nodes, int edges) {
        if (!enabled) return null;
        long now = clock.getAsLong();
        Batch batch = scheduled;
        if (batch == null) {
            batch = new Batch(now, tick);
            batch.causes.put(Cause.EXPLICIT_RECALCULATION, 1L);
            requests.merge(Cause.EXPLICIT_RECALCULATION, 1L, Long::sum);
        }
        return new Trace(batch, now, tick, allocatedBytes.getAsLong(), nodes, edges);
    }

    void finish(Trace trace, boolean success, int edgesAfter) {
        if (trace == null) return;
        long end = clock.getAsLong();
        long bytes = allocatedBytes.getAsLong();
        Recalculation result = new Recalculation(Map.copyOf(trace.batch.causes), success,
                Math.max(0, end - trace.start), Math.max(0, trace.start - trace.batch.firstNanos),
                trace.startTick - trace.batch.firstTick,
                success ? Math.max(0, end - trace.batch.firstNanos) : -1,
                trace.startBytes >= 0 && bytes >= trace.startBytes ? bytes - trace.startBytes : -1,
                trace.steps, trace.blockReads, trace.chunkRequests, trace.pathCopies,
                trace.copiedReferences, trace.nodes, trace.edgesBefore, edgesAfter);
        if (recalculations.size() == MAX_RECALCULATIONS) recalculations.removeFirst();
        recalculations.addLast(result);
        if (success) completed++;
        else failed++;
    }

    public List<Recalculation> recalculations() { return List.copyOf(recalculations); }
    public Map<Cause, Long> requests() { return Map.copyOf(requests); }
    public long skippedTrafficTicks() { return skippedTrafficTicks; }
    public Distribution serverTicks() { return distribution(Arrays.copyOf(ticks, tickSamples)); }

    static Distribution distribution(long[] values) {
        if (values.length == 0) return new Distribution(0, 0, 0, 0, 0);
        Arrays.sort(values);
        return new Distribution(values.length, percentile(values, 50), percentile(values, 95),
                percentile(values, 99), values[values.length - 1]);
    }

    private static long percentile(long[] values, int percentile) {
        return values[(int) Math.ceil(values.length * percentile / 100.0) - 1];
    }

    public List<String> report() {
        Distribution server = serverTicks();
        Distribution traces = distribution(recalculations.stream().mapToLong(Recalculation::durationNanos).toArray());
        Recalculation last = recalculations.peekLast();
        return List.of(
                "Telecom diagnostics " + (enabled ? "ON" : "OFF") + "; completed=" + completed
                        + "; failed=" + failed + "; skippedTrafficTicks=" + skippedTrafficTicks,
                "Requests since start: " + requests,
                timing("Server ticks (Pre/Post, rolling)", server),
                timing("Recalculations (rolling)", traces),
                last == null ? "Last recalculation: none" : String.format(Locale.ROOT,
                        "Last: causes=%s; success=%s; nodes=%d; edges=%d->%d; wait=%.3f ms/%d ticks; publication=%.3f ms; allocatedBytes=%d; traceSteps=%d; blockReads=%d; chunkRequests=%d; pathCopies=%d; copiedPathReferences=%d",
                        last.causes, last.success, last.nodes, last.edgesBefore, last.edgesAfter,
                        last.waitNanos / 1e6, last.waitTicks,
                        last.publicationNanos < 0 ? -1.0 : last.publicationNanos / 1e6,
                        last.allocatedBytes, last.traceSteps, last.blockReads, last.chunkRequests,
                        last.pathCopies, last.copiedPathReferences));
    }

    private static String timing(String name, Distribution value) {
        return String.format(Locale.ROOT, "%s: n=%d; p50=%.3f; p95=%.3f; p99=%.3f; max=%.3f ms",
                name, value.samples, value.p50 / 1e6, value.p95 / 1e6, value.p99 / 1e6, value.max / 1e6);
    }

    private static final class Batch {
        final EnumMap<Cause, Long> causes = new EnumMap<>(Cause.class);
        long firstNanos;
        long firstTick;

        Batch(long firstNanos, long firstTick) {
            this.firstNanos = firstNanos;
            this.firstTick = firstTick;
        }
    }

    static final class Trace {
        final Batch batch;
        final long start;
        final long startTick;
        final long startBytes;
        final int nodes;
        final int edgesBefore;
        long steps;
        long blockReads;
        long chunkRequests;
        long pathCopies;
        long copiedReferences;

        Trace(Batch batch, long start, long startTick, long startBytes, int nodes, int edgesBefore) {
            this.batch = batch;
            this.start = start;
            this.startTick = startTick;
            this.startBytes = startBytes;
            this.nodes = nodes;
            this.edgesBefore = edgesBefore;
        }
    }
}
