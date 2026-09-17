package com.florentdubut.telecom.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Observed 3D terrain only. No chunk references, generation, or disk work in radio scans. */
public final class RadioTerrainCache {
    static final int CAPACITY = 256;
    static final int DISK_CAPACITY = 4096;
    static final int QUEUE_CAPACITY = 128;
    static final int PREFETCH_LIMIT = 48;
    private static final int MAGIC = 0x52414431; // Radio material model / file format version.
    private static final SignalPropagator.Material[] MATERIALS = SignalPropagator.Material.values();
    private static final Map<ServerLevel, RadioTerrainCache> LEVELS = new HashMap<>();
    private static Worker worker;

    private final Path directory;
    private final Worker io;
    private final int minY;
    private final int height;
    private final LinkedHashMap<Long, Snapshot> snapshots = new LinkedHashMap<>(32, 0.75f, true);
    private final LinkedHashMap<Long, Long> missing = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<Long, Object> reads = new HashMap<>();
    private final Map<Long, Snapshot> writes = new HashMap<>();
    // Worker-only disk working set; eviction may make an old observed path UNKNOWN again.
    private final LinkedHashMap<Long, Boolean> persisted = new LinkedHashMap<>(32, 0.75f, true);
    // Coordinates only, bounded by the world's currently loaded chunks, not its explored area.
    private final Set<Long> loaded = new HashSet<>();
    private volatile boolean initialized;
    private volatile boolean closed;
    private volatile boolean unsafe;
    private volatile long revision;
    private final Map<Long, Long> changes = new LinkedHashMap<>();

    RadioTerrainCache(Path directory, int minY, int height, Worker io) {
        if ((minY & 15) != 0 || height <= 0 || (height & 15) != 0 || height > 4096) {
            throw new IllegalArgumentException("Unsupported radio terrain height");
        }
        this.directory = directory;
        this.minY = minY;
        this.height = height;
        this.io = io;
        io.register(this);
    }

    public static void capture(ServerLevel level, LevelChunk chunk) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Radio capture must run on server thread");
        RadioTerrainCache cache = LEVELS.get(level);
        if (cache == null) {
            if (worker == null) worker = new Worker();
            var dimension = level.dimension().identifier();
            Path path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("telecom-radio")
                    .resolve(dimension.getNamespace()).resolve(dimension.getPath());
            cache = new RadioTerrainCache(path, level.getMinY(), level.getHeight(), worker);
            LEVELS.put(level, cache);
        }
        cache.put(chunk.getPos().toLong(), Snapshot.copy(chunk));
        cache.loaded.add(chunk.getPos().toLong());
    }

    public static void captureUnloaded(ServerLevel level, LevelChunk chunk) {
        capture(level, chunk);
        LEVELS.get(level).loaded.remove(chunk.getPos().toLong());
    }

    public static SignalPropagator.Material sample(ServerLevel level, BlockPos pos) {
        RadioTerrainCache cache = LEVELS.get(level);
        return cache == null ? SignalPropagator.Material.UNKNOWN : cache.sample(pos);
    }

    /** Access only after the caller has checked getChunkNow, so live terrain always wins. */
    static Snapshot snapshot(ServerLevel level, int x, int z) {
        RadioTerrainCache cache = LEVELS.get(level);
        return cache == null ? null : cache.get(ChunkPos.asLong(x, z));
    }

    static long revision(ServerLevel level) {
        RadioTerrainCache cache = LEVELS.get(level);
        return cache == null ? 0 : cache.revision;
    }

    static java.util.Set<Long> changedChunks(ServerLevel level, long after, long through) {
        RadioTerrainCache cache = LEVELS.get(level);
        if (cache == null) return null;
        synchronized (cache) {
            if (through < after || through - after > 512 || (!cache.changes.isEmpty()
                    && after < cache.changes.keySet().iterator().next() - 1)) return null;
            java.util.Set<Long> result = new java.util.HashSet<>();
            cache.changes.forEach((version, chunk) -> {
                if (version > after && version <= through) result.add(chunk);
            });
            return result;
        }
    }

    static void prefetch(ServerLevel level, BlockPos source, BlockPos target) {
        RadioTerrainCache cache = LEVELS.get(level);
        if (cache != null) cache.prefetch(source, target);
    }

    /** Chunk-grid DDA includes short intersections, not just regularly spaced points. */
    synchronized void prefetch(BlockPos source, BlockPos target) {
        int x = source.getX() >> 4, z = source.getZ() >> 4;
        int endX = target.getX() >> 4, endZ = target.getZ() >> 4;
        double dx = (double) target.getX() - source.getX(), dz = (double) target.getZ() - source.getZ();
        int sx = (int) Math.signum(dx), sz = (int) Math.signum(dz);
        int submitted = request(ChunkPos.asLong(endX, endZ)) ? 1 : 0;
        for (int steps = 0; steps <= 2 * SignalPropagator.MAX_RANGE / 16 + 2 && submitted < PREFETCH_LIMIT; steps++) {
            if (request(ChunkPos.asLong(x, z))) submitted++;
            if (x == endX && z == endZ) break;
            double tx = sx == 0 ? Double.POSITIVE_INFINITY
                    : ((sx > 0 ? (x + 1) * 16.0 : x * 16.0) - source.getX() - 0.5) / dx;
            double tz = sz == 0 ? Double.POSITIVE_INFINITY
                    : ((sz > 0 ? (z + 1) * 16.0 : z * 16.0) - source.getZ() - 0.5) / dz;
            if (tx <= tz) x += sx;
            if (tz <= tx) z += sz;
        }
    }

    synchronized SignalPropagator.Material sample(BlockPos pos) {
        Snapshot snapshot = get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        return snapshot == null ? SignalPropagator.Material.UNKNOWN : snapshot.sample(pos);
    }

    synchronized Snapshot get(long key) {
        if (closed) return null;
        Snapshot snapshot = snapshots.get(key);
        if (snapshot == null) request(key);
        return snapshot;
    }

    synchronized void put(long key, Snapshot snapshot) {
        if (closed) return;
        reads.remove(key); // A decode started before this capture must never overwrite it.
        missing.remove(key);
        retain(key, snapshot);
        if (writes.put(key, snapshot) != null) return; // Coalesce queued load/unload captures.
        if (!io.offer(this, () -> writePending(key))) {
            writes.remove(key);
            distrustDisk();
        }
    }

    private void retain(long key, Snapshot snapshot) {
        snapshots.put(key, snapshot);
        if (snapshots.size() > CAPACITY) snapshots.remove(snapshots.keySet().iterator().next());
        revision++;
        changes.put(revision, key);
        if (changes.size() > 512) changes.remove(changes.keySet().iterator().next());
    }

    private boolean request(long key) {
        if (closed || unsafe || snapshots.containsKey(key) || reads.containsKey(key) || writes.containsKey(key)) return false;
        Long missedAt = missing.get(key);
        if (missedAt != null && System.nanoTime() - missedAt < TimeUnit.SECONDS.toNanos(30)) return false;
        Object token = new Object();
        reads.put(key, token);
        if (io.offer(this, () -> restore(key, token))) return true;
        reads.remove(key);
        return false;
    }

    private void restore(long key, Object token) {
        Snapshot snapshot = null;
        if (!unsafe) {
            try {
                snapshot = decode(key);
            } catch (IOException | RuntimeException ignored) {
                // Missing, incompatible and damaged files are all UNKNOWN, with bounded backoff.
            }
        }
        synchronized (this) {
            if (!reads.remove(key, token) || closed || unsafe) return;
            if (snapshot != null) retain(key, snapshot);
            else {
                missing.put(key, System.nanoTime());
                if (missing.size() > CAPACITY) missing.remove(missing.keySet().iterator().next());
            }
        }
    }

    private void writePending(long key) {
        Snapshot snapshot;
        synchronized (this) { snapshot = writes.remove(key); }
        if (snapshot == null) return;
        try {
            reserveDiskEntry(key);
            Snapshot compact = snapshot.compact();
            Path target = file(key);
            Path temporary = directory.resolve(Long.toUnsignedString(key) + ".tmp");
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeLong(key);
                out.writeInt(minY);
                out.writeInt(height);
                for (int y : compact.surface) out.writeInt(y);
                out.write(compact.materials);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            persisted.put(key, Boolean.TRUE);
            synchronized (this) {
                // Background conversion is not a radio access and must not promote the LRU entry.
                for (var entry : snapshots.entrySet()) {
                    if (entry.getKey() == key && entry.getValue() == snapshot) {
                        entry.setValue(compact);
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            distrustDisk();
        }
    }

    private Snapshot decode(long key) throws IOException {
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file(key))))) {
            if (in.readInt() != MAGIC || in.readLong() != key || in.readInt() != minY || in.readInt() != height) {
                throw new IOException("Incompatible radio terrain");
            }
            int[] surface = new int[256];
            for (int i = 0; i < surface.length; i++) {
                surface[i] = in.readInt();
                if (surface[i] < minY - 1 || surface[i] >= minY + height) throw new IOException("Invalid surface");
            }
            byte[] materials = new byte[height * 256];
            in.readFully(materials);
            for (byte material : materials) {
                if (material < 0 || material >= MATERIALS.length) throw new IOException("Invalid material");
            }
            if (in.read() != -1) throw new IOException("Oversized radio terrain");
            persisted.get(key);
            return new Snapshot(minY, List.of(), surface, materials);
        }
    }

    private Path file(long key) { return directory.resolve(Long.toUnsignedString(key) + ".radio.gz"); }

    private void reserveDiskEntry(long key) throws IOException {
        if (!persisted.containsKey(key) && persisted.size() >= DISK_CAPACITY) {
            long oldest = persisted.keySet().iterator().next();
            Files.deleteIfExists(file(oldest));
            persisted.remove(oldest);
        }
    }

    private synchronized void distrustDisk() {
        if (!unsafe) LogUtils.getLogger().warn("Radio terrain persistence incomplete at {}; retaining memory terrain only", directory);
        unsafe = true;
        reads.clear();
    }

    // Worker only. The marker prevents stale pre-edit files surviving a crash or queue overflow.
    private void initializeDisk() {
        try {
            Files.createDirectories(directory);
            Path marker = directory.resolve("incomplete");
            if (Files.exists(marker)) {
                try (var files = Files.list(directory)) {
                    var iterator = files.iterator();
                    while (iterator.hasNext()) {
                        Path path = iterator.next();
                        if (path.toString().endsWith(".radio.gz") || path.toString().endsWith(".tmp")) Files.deleteIfExists(path);
                    }
                }
            } else {
                // Stream existing entries, keeping startup memory and disk usage bounded too.
                // Across restarts enumeration order replaces the previous in-memory LRU order.
                try (var files = Files.list(directory)) {
                    var iterator = files.iterator();
                    while (iterator.hasNext()) {
                        String name = iterator.next().getFileName().toString();
                        if (!name.endsWith(".radio.gz")) continue;
                        long key;
                        try { key = Long.parseUnsignedLong(name.substring(0, name.length() - ".radio.gz".length())); }
                        catch (NumberFormatException ignored) { continue; }
                        reserveDiskEntry(key);
                        persisted.put(key, Boolean.TRUE);
                    }
                }
            }
            Files.writeString(marker, "Unclean shutdown or incomplete writes invalidate persisted radio terrain.");
        } catch (IOException | RuntimeException error) {
            distrustDisk();
        } finally {
            initialized = true;
        }
    }

    private void finishDisk() {
        if (!unsafe) {
            try { Files.deleteIfExists(directory.resolve("incomplete")); }
            catch (IOException | RuntimeException error) { distrustDisk(); }
        }
    }

    synchronized void close() {
        closed = true;
        snapshots.clear();
        reads.clear();
        missing.clear();
        io.wake();
    }

    public static void unload(ServerLevel level) {
        RadioTerrainCache cache = LEVELS.remove(level);
        if (cache != null) {
            // World close need not emit ChunkEvent.Unload for every still-loaded chunk.
            for (long key : cache.loaded) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (chunk != null) cache.put(key, Snapshot.copy(chunk));
            }
            cache.loaded.clear();
            cache.close();
        }
        CoverageService.unload(level);
    }

    public static void stop(MinecraftServer server) {
        for (ServerLevel level : List.copyOf(LEVELS.keySet())) unload(level);
        if (worker != null) {
            worker.stop();
            worker = null;
        }
    }

    /** Private, never-mutated copies: get() reads palette/storage without acquiring mutation locks. */
    static final class Snapshot {
        private final int minY;
        private final List<PalettedContainer<BlockState>> sections;
        private final int[] surface;
        private final byte[] materials;

        private Snapshot(int minY, List<PalettedContainer<BlockState>> sections, int[] surface, byte[] materials) {
            this.minY = minY;
            this.sections = List.copyOf(sections);
            this.surface = surface;
            this.materials = materials;
        }

        static Snapshot copy(LevelChunk chunk) {
            List<PalettedContainer<BlockState>> sections = new ArrayList<>();
            for (var section : chunk.getSections()) sections.add(section.getStates().copy());
            int[] surface = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                surface[z * 16 + x] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            }
            return new Snapshot(chunk.getMinY(), sections, surface, null);
        }

        int surfaceY(int x, int z) { return surface[(z & 15) * 16 + (x & 15)]; }

        SignalPropagator.Material sample(BlockPos pos) {
            int y = pos.getY() - minY;
            int height = materials == null ? sections.size() * 16 : materials.length / 256;
            if (y < 0 || y >= height) return SignalPropagator.Material.UNKNOWN;
            int x = pos.getX() & 15, z = pos.getZ() & 15;
            return materials == null ? SignalPropagator.classify(sections.get(y >> 4).get(x, y & 15, z))
                    : MATERIALS[materials[y * 256 + z * 16 + x]];
        }

        private Snapshot compact() {
            if (materials != null) return this;
            byte[] values = new byte[sections.size() * 4096];
            for (int y = 0; y < sections.size() * 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                values[y * 256 + z * 16 + x] = (byte) SignalPropagator.classify(sections.get(y >> 4).get(x, y & 15, z)).ordinal();
            }
            return new Snapshot(minY, List.of(), surface, values);
        }
    }

    /** One disk thread for every dimension, bounded work queue; lifecycle work cannot be rejected. */
    static final class Worker {
        private record Work(RadioTerrainCache owner, Runnable action) { }
        private final ArrayDeque<Work> queue = new ArrayDeque<>();
        private final List<RadioTerrainCache> caches = new ArrayList<>();
        private final Thread thread;
        private boolean stopping;

        Worker() {
            thread = new Thread(this::run, "telecom-radio-terrain");
            thread.setDaemon(true);
            thread.start();
        }

        synchronized void register(RadioTerrainCache cache) {
            if (stopping) throw new IllegalStateException("Radio worker stopped");
            caches.add(cache);
            notifyAll();
        }

        synchronized boolean offer(RadioTerrainCache owner, Runnable task) {
            if (stopping || queue.size() >= QUEUE_CAPACITY) return false;
            queue.addLast(new Work(owner, task));
            notifyAll();
            return true;
        }

        synchronized void wake() { notifyAll(); }

        private void run() {
            try {
                while (true) {
                    Runnable task;
                    synchronized (this) {
                        RadioTerrainCache fresh = null;
                        for (RadioTerrainCache candidate : caches) {
                            if (!candidate.initialized && caches.stream().noneMatch(c -> c != candidate
                                    && c.initialized && c.directory.equals(candidate.directory))) {
                                fresh = candidate;
                                break;
                            }
                        }
                        RadioTerrainCache finished = caches.stream().filter(c -> (c.closed || stopping)
                                && c.initialized && queue.stream().noneMatch(work -> work.owner() == c)).findFirst().orElse(null);
                        Work next = queue.stream().filter(work -> work.owner() == null || work.owner().initialized).findFirst().orElse(null);
                        if (fresh != null) task = fresh::initializeDisk;
                        else if (next != null) {
                            queue.remove(next);
                            task = next.action();
                        } else if (finished != null) {
                            caches.remove(finished);
                            task = finished::finishDisk;
                        } else if (stopping) return;
                        else { wait(); continue; }
                    }
                    task.run();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        void stop() {
            synchronized (this) { stopping = true; notifyAll(); }
            try { thread.join(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
