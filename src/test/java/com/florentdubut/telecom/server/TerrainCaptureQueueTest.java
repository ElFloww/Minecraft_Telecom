package com.florentdubut.telecom.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class TerrainCaptureQueueTest {
    @TempDir Path directory;
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerLevel level = mock(ServerLevel.class);
    private final ServerChunkCache source = mock(ServerChunkCache.class);
    private final LevelChunk chunk = mock(LevelChunk.class);
    private final BlockState state = mock(BlockState.class);
    private final BlockingQueue<Runnable> jobs = new LinkedBlockingQueue<>();
    private final BlockingQueue<ChunkPos> saved = new LinkedBlockingQueue<>();
    private final AtomicLong clock = new AtomicLong();
    private TerrainTileStore store;
    private TerrainCaptureQueue queue;

    @BeforeEach
    void setUp() throws Exception {
        store = spy(new TerrainTileStore(directory));
        when(server.overworld()).thenReturn(level);
        when(level.getChunkSource()).thenReturn(source);
        when(source.getChunkNow(2, -3)).thenReturn(chunk);
        when(chunk.getHeight(eq(Heightmap.Types.WORLD_SURFACE), anyInt(), anyInt()))
                .thenAnswer(call -> 64 + (int) call.getArgument(1) % 2);
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(state);
        when(state.getMapColor(eq(chunk), any(BlockPos.class))).thenReturn(MapColor.GRASS);
        doAnswer(call -> {
            jobs.add(call.getArgument(0));
            return null;
        }).when(server).execute(any(Runnable.class));
        queue = new TerrainCaptureQueue(server, store, (cx, cz) -> saved.add(new ChunkPos(cx, cz)), clock::get);
    }

    @AfterEach
    void tearDown() throws Exception {
        queue.close();
        worker().join(2000);
        assertFalse(worker().isAlive());
    }

    @Test
    void existingPngIsReadOnWorkerWithoutMinecraftExecution() throws Exception {
        byte[] png = png();
        store.storeIfAbsent(2, -3, png);
        clearInvocations(store);
        CountDownLatch read = new CountDownLatch(1);
        doAnswer(call -> {
            assertSame(worker(), Thread.currentThread());
            try { return call.callRealMethod(); }
            finally { read.countDown(); }
        }).when(store).read(2, -3);
        queue.offer(2, -3);
        assertTrue(read.await(2, TimeUnit.SECONDS));
        finishWithMissingChunk();
        verify(server, times(1)).execute(any(Runnable.class)); // Only the barrier chunk.
        verify(source, never()).getChunkNow(2, -3);
        assertTrue(saved.isEmpty());
        assertArrayEquals(png, new TerrainTileStore(directory).read(2, -3));
    }

    @Test
    void capturesActual16By16OnceAndInvalidatesAfterWorkerPublication() throws Exception {
        CountDownLatch callback = new CountDownLatch(1);
        doAnswer(call -> {
            assertSame(worker(), Thread.currentThread());
            return call.callRealMethod();
        }).when(store).storeIfAbsent(eq(2), eq(-3), any(byte[].class));
        queue.close();
        queue = new TerrainCaptureQueue(server, store, (cx, cz) -> {
            assertSame(workerUnchecked(), Thread.currentThread());
            assertEquals(new ChunkPos(2, -3), new ChunkPos(cx, cz));
            try { assertNotNull(store.read(cx, cz)); }
            catch (IOException e) { throw new AssertionError(e); }
            callback.countDown();
        }, clock::get);
        queue.offer(2, -3);
        Runnable job = nextJob();
        for (int i = 0; i < 1000; i++) queue.offer(2, -3);
        job.run();
        job.run();
        assertTrue(callback.await(3, TimeUnit.SECONDS));
        finishWithMissingChunk();
        queue.offer(2, -3);
        finishWithMissingChunk();
        verify(chunk, times(256)).getBlockState(any(BlockPos.class));
        verify(source, times(1)).getChunkNow(2, -3);
        verify(store, times(1)).storeIfAbsent(eq(2), eq(-3), any(byte[].class));
        verify(source, never()).getChunk(anyInt(), anyInt(), any(), anyBoolean());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(store.read(2, -3)));
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        int color = MapColor.GRASS.col;
        int shaded = ((color & 0xff0000) * 9 / 10 & 0xff0000)
                | ((color & 0xff00) * 9 / 10 & 0xff00) | ((color & 0xff) * 9 / 10);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(0xff000000 | (x % 2 == 0 ? shaded : color), image.getRGB(x, z));
                verify(chunk).getBlockState(new BlockPos(32 + x, 64 + x % 2, -48 + z));
            }
        }
    }

    @Test
    void pausedServerBoundsAllPendingIncludingActiveAndDeduplicates() throws Exception {
        queue.offer(2, -3);
        Runnable paused = nextJob();
        for (int i = 0; i < 20_000; i++) {
            queue.offer(2, -3);
            queue.offer(i, 9);
        }
        assertEquals(4096, pendingCount());
        assertTrue(jobs.isEmpty());
        verify(server, times(1)).execute(any(Runnable.class));
        verify(server, never()).overworld();
        assertTrue(worker().isDaemon());
        queue.close();
        paused.run();
        verify(server, never()).overworld();
        assertEquals(0, pendingCount());
    }

    @Test
    void closeInvalidatesQueuedCallbackAndDoesNotReadNextChunk() throws Exception {
        queue.offer(2, -3);
        Runnable paused = nextJob();
        queue.offer(8, 9);
        queue.close();
        worker().join(2000);
        assertFalse(worker().isAlive());
        paused.run();
        queue.offer(10, 11);
        queue.seedLoadedChunks();
        verify(server, never()).overworld();
        verify(store, never()).read(8, 9);
        verify(store, never()).read(10, 11);
        verify(store, never()).storeIfAbsent(anyInt(), anyInt(), any());
        assertTrue(saved.isEmpty());
    }

    @Test
    void missingChunkIsNotPersistedAndCanBeOfferedAgain() throws Exception {
        when(source.getChunkNow(2, -3)).thenReturn(null);
        queue.offer(2, -3);
        nextJob().run();
        finishWithMissingChunk();
        assertNull(store.read(2, -3));
        verify(store, never()).storeIfAbsent(anyInt(), anyInt(), any());
        when(source.getChunkNow(2, -3)).thenReturn(chunk);
        queue.offer(2, -3);
        nextJob().run();
        assertEquals(new ChunkPos(2, -3), saved.poll(3, TimeUnit.SECONDS));
    }

    @Test
    void closeInterruptsDiskWorkerWithoutSchedulingWorldRead() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(store).read(2, -3);
        try {
            queue.offer(2, -3);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            queue.close();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            worker().join(2000);
            assertFalse(worker().isAlive());
            verifyNoInteractions(server);
            verify(store, never()).storeIfAbsent(anyInt(), anyInt(), any());
        } finally {
            release.countDown();
        }
    }

    @Test
    void stoppedServerCallbackNeverAcquiresWorld() throws Exception {
        queue.offer(2, -3);
        Runnable paused = nextJob();
        when(server.isStopped()).thenReturn(true);
        paused.run();
        finishWithMissingChunk();
        verify(server, never()).overworld();
        verify(store, never()).storeIfAbsent(anyInt(), anyInt(), any());
        assertTrue(saved.isEmpty());
    }

    @Test
    void firstCallbackMakesProgressEvenIfColdLookupExceedsBudget() throws Exception {
        when(source.getChunkNow(2, -3)).thenAnswer(call -> {
            clock.addAndGet(3_000_000L);
            return chunk;
        });
        queue.offer(2, -3);
        Runnable first = nextJob();
        clock.addAndGet(TimeUnit.DAYS.toNanos(1)); // Queue age is not execution budget.
        first.run();
        verify(chunk, times(16)).getBlockState(any(BlockPos.class));
        for (int i = 1; i < 16; i++) {
            nextJob().run();
        }
        assertEquals(new ChunkPos(2, -3), saved.poll(3, TimeUnit.SECONDS));
        verify(chunk, times(256)).getBlockState(any(BlockPos.class));
        verify(server, times(16)).execute(any(Runnable.class));
        assertTrue(jobs.isEmpty());
    }

    @Test
    void unloadedBetweenBudgetSlicesDoesNotPublishPartialImage() throws Exception {
        when(state.getMapColor(eq(chunk), any(BlockPos.class))).thenAnswer(call -> {
            clock.addAndGet(200_000L);
            return MapColor.GRASS;
        });
        queue.offer(2, -3);
        nextJob().run();
        verify(chunk, times(16)).getBlockState(any(BlockPos.class));
        when(source.getChunkNow(2, -3)).thenReturn(null);
        nextJob().run();
        finishWithMissingChunk();
        assertNull(store.read(2, -3));
        verify(store, never()).storeIfAbsent(anyInt(), anyInt(), any());
        assertTrue(saved.isEmpty());
    }

    @Test
    void invalidPermanentFileIsNotRetriedOrOverwritten() throws Exception {
        doThrow(new IOException("Invalid terrain PNG")).when(store).read(2, -3);
        queue.offer(2, -3);
        finishWithMissingChunk();
        for (int i = 0; i < 1000; i++) queue.offer(2, -3);
        finishWithMissingChunk();
        verify(store, times(1)).read(2, -3);
        verify(source, never()).getChunkNow(2, -3);
        verify(store, never()).storeIfAbsent(eq(2), eq(-3), any());
    }

    @Test
    void seedsReadyChunksOnlyOnceThroughPublicApi() throws Exception {
        ChunkMap map = mock(ChunkMap.class);
        var field = ServerChunkCache.class.getField("chunkMap");
        field.setAccessible(true);
        field.set(source, map);
        when(chunk.getPos()).thenReturn(new ChunkPos(2, -3));
        doAnswer(call -> {
            Consumer<LevelChunk> visitor = call.getArgument(0);
            visitor.accept(chunk);
            visitor.accept(chunk);
            return null;
        }).when(map).forEachReadyToSendChunk(any());
        queue.seedLoadedChunks();
        queue.seedLoadedChunks();
        nextJob().run();
        assertEquals(new ChunkPos(2, -3), saved.poll(3, TimeUnit.SECONDS));
        verify(map, times(1)).forEachReadyToSendChunk(any());
        verify(source, times(1)).getChunkNow(2, -3);
    }

    private int barrier;

    private void finishWithMissingChunk() throws Exception {
        queue.offer(-100 - barrier++, 100);
        nextJob().run();
    }

    private Runnable nextJob() throws InterruptedException {
        Runnable task = jobs.poll(3, TimeUnit.SECONDS);
        assertNotNull(task, "Capture worker did not schedule a snapshot");
        return task;
    }

    private int pendingCount() throws Exception {
        var field = TerrainCaptureQueue.class.getDeclaredField("pending");
        field.setAccessible(true);
        synchronized (queue) {
            return ((Set<?>) field.get(queue)).size();
        }
    }

    private Thread worker() throws Exception {
        var field = TerrainCaptureQueue.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (Thread) field.get(queue);
    }

    private Thread workerUnchecked() {
        try { return worker(); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        }
    }
}
