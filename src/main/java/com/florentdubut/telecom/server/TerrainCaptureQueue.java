package com.florentdubut.telecom.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

final class TerrainCaptureQueue implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerrainCaptureQueue.class);
    private static final int CAPACITY = 4096;
    private static final long BUDGET_NANOS = 2_000_000L;
    private static final int[] MORE = new int[0];

    private final MinecraftServer server;
    private final TerrainTileStore store;
    private final BiConsumer<Integer, Integer> onSaved;
    private final LongSupplier nanoTime;
    private final ArrayBlockingQueue<ChunkPos> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final Set<ChunkPos> pending = new HashSet<>();
    private final LinkedHashSet<ChunkPos> failed = new LinkedHashSet<>();
    private final Thread worker;
    private volatile boolean closed;
    private boolean seeded;
    private FutureTask<int[]> snapshot;

    TerrainCaptureQueue(MinecraftServer server, TerrainTileStore store, BiConsumer<Integer, Integer> onSaved) {
        this(server, store, onSaved, System::nanoTime);
    }

    TerrainCaptureQueue(MinecraftServer server, TerrainTileStore store, BiConsumer<Integer, Integer> onSaved,
                        LongSupplier nanoTime) {
        this.server = Objects.requireNonNull(server);
        this.store = Objects.requireNonNull(store);
        this.onSaved = Objects.requireNonNull(onSaved);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        worker = new Thread(this::run, "telecom-terrain-capture");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void offer(int cx, int cz) {
        ChunkPos pos = new ChunkPos(cx, cz);
        if (closed || pending.size() >= CAPACITY || failed.contains(pos) || !pending.add(pos)) return;
        if (!queue.offer(pos)) pending.remove(pos);
    }

    // Called on the server thread after start, never from the capture worker.
    void seedLoadedChunks() {
        synchronized (this) {
            if (closed || seeded) return;
            seeded = true;
        }
        server.overworld().getChunkSource().chunkMap.forEachReadyToSendChunk(chunk -> {
            ChunkPos pos = chunk.getPos();
            offer(pos.x, pos.z);
        });
    }

    private void run() {
        while (!closed) {
            ChunkPos pos;
            try {
                pos = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (closed || store.read(pos.x, pos.z) != null) continue;
                int[] pixels = new int[256];
                int[] nextRow = {0};
                int[] result;
                do {
                    FutureTask<int[]> task = new FutureTask<>(() -> capture(pos, pixels, nextRow));
                    synchronized (this) {
                        if (closed) return;
                        snapshot = task;
                    }
                    // Only this worker submits snapshots, and it waits before submitting another.
                    server.execute(task);
                    result = task.get();
                    synchronized (this) {
                        if (snapshot == task) snapshot = null;
                    }
                } while (!closed && result == MORE);
                if (closed || result == null) continue;
                BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, 16, 16, result, 0, 16);
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(image, "png", output)) throw new IOException("No terrain PNG encoder");
                    if (closed) continue;
                    store.storeIfAbsent(pos.x, pos.z, output.toByteArray());
                } finally {
                    image.flush();
                }
                if (!closed) onSaved.accept(pos.x, pos.z);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (CancellationException e) {
                if (closed) return;
            } catch (IOException | ExecutionException | RuntimeException e) {
                if (!closed) {
                    synchronized (this) {
                        // Invalid permanent files are not retried on every watch event.
                        if (failed.size() == CAPACITY) failed.removeFirst();
                        failed.add(pos);
                    }
                    LOGGER.warn("Cannot capture terrain chunk {}, {}", pos.x, pos.z, e);
                }
            } finally {
                synchronized (this) {
                    if (snapshot != null) snapshot.cancel(false);
                    snapshot = null;
                    pending.remove(pos);
                }
            }
        }
    }

    private int[] capture(ChunkPos pos, int[] pixels, int[] nextRow) {
        if (closed || server.isStopped()) return null;
        long started = nanoTime.getAsLong();
        var level = server.overworld();
        if (level == null || closed) return null;
        var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) return null;
        do {
            if (closed) return null;
            int z = nextRow[0];
            for (int x = 0; x < 16; x++) {
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos position = new BlockPos(pos.x * 16 + x, y, pos.z * 16 + z);
                int color = chunk.getBlockState(position).getMapColor(chunk, position).col;
                if ((y & 1) == 0) color = ((color & 0xff0000) * 9 / 10 & 0xff0000)
                        | ((color & 0xff00) * 9 / 10 & 0xff00) | ((color & 0xff) * 9 / 10);
                pixels[z * 16 + x] = 0xff000000 | color;
            }
            nextRow[0]++;
            // Always make progress, including cold initialization: at most 16 callbacks per tile.
            if (nextRow[0] == 16) return pixels;
        } while (nanoTime.getAsLong() - started < BUDGET_NANOS);
        return MORE;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (snapshot != null) snapshot.cancel(false);
        queue.clear();
        pending.clear();
        failed.clear();
        worker.interrupt();
        // Never join here: close may run on the server thread needed by the outstanding task.
    }
}
