package com.florentdubut.telecom.network;

import com.florentdubut.telecom.event.RadioTerrainEvents;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.florentdubut.telecom.network.SignalPropagator.Material.*;
import static com.florentdubut.telecom.network.TelecomFrequency.G2_900;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(20)
class RadioTerrainCacheTest {
    @TempDir Path directory;
    private RadioTerrainCache.Worker worker;
    private MinecraftServer server;

    @AfterEach
    void stop() {
        if (worker != null) worker.stop();
        if (server != null) RadioTerrainCache.stop(server);
        CoverageService.clear();
    }

    static LevelChunk observedChunk(int x, int z) {
        LevelChunk chunk = mock(LevelChunk.class);
        var palette = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        LevelChunkSection section = new LevelChunkSection(palette, null);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[]{section});
        when(chunk.getMinY()).thenReturn(64);
        when(chunk.getPos()).thenReturn(new ChunkPos(x, z));
        when(chunk.getHeight(any(), anyInt(), anyInt())).thenReturn(64);
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        });
        return chunk;
    }

    private RadioTerrainCache cache() {
        if (worker == null) worker = new RadioTerrainCache.Worker();
        return new RadioTerrainCache(directory, 64, 16, worker);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }

    private void drain() {
        CountDownLatch finished = new CountDownLatch(1);
        assertTrue(worker.offer(null, finished::countDown));
        await(finished);
    }

    private CountDownLatch blockWorker() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        assertTrue(worker.offer(null, () -> { entered.countDown(); await(release); }));
        await(entered);
        return release;
    }

    private static void wall(LevelChunk chunk, BlockState state) {
        chunk.getSections()[0].getStates().set(8, 2, 0, state);
    }

    private static float signal(RadioTerrainCache cache) {
        var result = SignalPropagator.calculateSignal(cache::sample, new BlockPos(0, 66, 0), new BlockPos(15, 66, 0), G2_900);
        assertTrue(result.known);
        return result.powerDbm;
    }

    @Test
    void copiedPalettesAreImmediatelyUsableAndDetachedBeforeWorkerConversion() {
        RadioTerrainCache cache = cache();
        drain();
        CountDownLatch release = blockWorker();
        try {
            LevelChunk chunk = observedChunk(0, 0);
            cache.put(0, RadioTerrainCache.Snapshot.copy(chunk));
            float air = signal(cache);
            wall(chunk, Blocks.STONE.defaultBlockState());
            assertEquals(air, signal(cache), "Changes to live storage must not mutate an earlier snapshot");
            cache.put(0, RadioTerrainCache.Snapshot.copy(chunk));
            float wall = signal(cache);
            assertTrue(wall < air);
            wall(chunk, Blocks.AIR.defaultBlockState());
            assertEquals(wall, signal(cache));
            assertEquals(UNKNOWN, cache.sample(new BlockPos(0, 63, 0)));
            assertEquals(UNKNOWN, cache.sample(new BlockPos(0, 80, 0)));
        } finally { release.countDown(); }
        drain();
    }

    @Test
    void gzipRoundTripRestoresThreeDimensionalAttenuationAndSurface() throws Exception {
        RadioTerrainCache first = cache();
        LevelChunk chunk = observedChunk(0, 0);
        wall(chunk, Blocks.STONE.defaultBlockState());
        chunk.getSections()[0].getStates().set(3, 5, 2, Blocks.WATER.defaultBlockState());
        first.put(0, RadioTerrainCache.Snapshot.copy(chunk));
        float original = signal(first);
        first.close();
        worker.stop();
        assertFalse(Files.exists(directory.resolve("incomplete")));
        assertTrue(Files.size(directory.resolve("0.radio.gz")) < 4096);

        worker = new RadioTerrainCache.Worker();
        RadioTerrainCache restored = cache();
        assertNull(restored.get(0), "A cache miss must not wait for disk");
        drain();
        assertEquals(original, signal(restored));
        assertEquals(WATER, restored.sample(new BlockPos(3, 69, 2)));
        assertEquals(AIR, restored.sample(new BlockPos(3, 68, 2)));
        assertEquals(64, restored.get(0).surfaceY(3, 2));
    }

    @Test
    void newestCaptureSupersedesQueuedDecodeAndQueuedWrite() {
        RadioTerrainCache cache = cache();
        LevelChunk chunk = observedChunk(0, 0);
        cache.put(0, RadioTerrainCache.Snapshot.copy(chunk));
        drain();
        CountDownLatch release = blockWorker();
        try {
            cache.get(1); // A queued miss must not erase a subsequent capture.
            wall(chunk, Blocks.STONE.defaultBlockState());
            cache.put(1, RadioTerrainCache.Snapshot.copy(chunk));
            cache.put(0, RadioTerrainCache.Snapshot.copy(chunk));
            wall(chunk, Blocks.WATER.defaultBlockState());
            cache.put(0, RadioTerrainCache.Snapshot.copy(chunk));
        } finally { release.countDown(); }
        drain();
        assertEquals(WATER, cache.sample(new BlockPos(8, 66, 0)));
        assertEquals(SOLID, cache.sample(new BlockPos(24, 66, 0)));
    }

    @Test
    void staleDiskDecodeCannotReplaceMoreRecentCapture() {
        RadioTerrainCache first = cache();
        LevelChunk chunk = observedChunk(0, 0);
        first.put(0, RadioTerrainCache.Snapshot.copy(chunk));
        first.close();
        worker.stop();
        worker = new RadioTerrainCache.Worker();
        RadioTerrainCache second = cache();
        drain();
        CountDownLatch release = blockWorker();
        try {
            assertNull(second.get(0));
            wall(chunk, Blocks.STONE.defaultBlockState());
            second.put(0, RadioTerrainCache.Snapshot.copy(chunk));
        } finally { release.countDown(); }
        drain();
        assertEquals(SOLID, second.sample(new BlockPos(8, 66, 0)));
    }

    @Test
    void memoryIsLruBoundedAndEvictedChunkCanBePrefetchedFromDisk() throws Exception {
        RadioTerrainCache cache = cache();
        var snapshot = RadioTerrainCache.Snapshot.copy(observedChunk(0, 0));
        for (int key = 0; key < RadioTerrainCache.CAPACITY; key++) {
            cache.put(key, snapshot);
            if (key % 32 == 0) drain();
        }
        drain();
        assertNotNull(cache.get(0));
        cache.put(RadioTerrainCache.CAPACITY, snapshot);
        drain();
        assertEquals(RadioTerrainCache.CAPACITY, map(cache, "snapshots").size());
        assertTrue(map(cache, "snapshots").containsKey(0L));
        assertFalse(map(cache, "snapshots").containsKey(1L));
        assertNull(cache.get(1));
        drain();
        assertNotNull(cache.get(1));
    }

    @Test
    void prefetchQueuesDozensAlongWholePathInsteadOfOnlyFirstMissingChunk() throws Exception {
        RadioTerrainCache cache = cache();
        drain();
        CountDownLatch release = blockWorker();
        try {
            cache.prefetch(new BlockPos(-2048, 66, -2048), new BlockPos(2048, 66, 2048));
            Map<?, ?> reads = map(cache, "reads");
            assertEquals(RadioTerrainCache.PREFETCH_LIMIT, reads.size());
            assertTrue(reads.containsKey(ChunkPos.asLong(128, 128)), "Receiver must be prefetched too");
            assertTrue(reads.containsKey(ChunkPos.asLong(-128, -128)));
        } finally { release.countDown(); }
        drain();
        assertEquals(RadioTerrainCache.PREFETCH_LIMIT, map(cache, "missing").size());
    }

    @Test
    void negativeCacheIsBoundedAndSkipsRepeatedMissingDiskReads() throws Exception {
        RadioTerrainCache cache = cache();
        for (int key = 0; key < 300; key++) {
            assertNull(cache.get(key));
            if (key % 32 == 0) drain();
        }
        drain();
        assertEquals(RadioTerrainCache.CAPACITY, map(cache, "missing").size());
        CountDownLatch release = blockWorker();
        try {
            for (int i = 0; i < 20; i++) assertNull(cache.get(299));
            assertTrue(map(cache, "reads").isEmpty());
        } finally { release.countDown(); }
    }

    @Test
    void queueOverflowNeverReintroducesOldTerrainAndStopDrainsAcceptedWrites() throws Exception {
        RadioTerrainCache cache = cache();
        LevelChunk chunk = observedChunk(0, 0);
        cache.put(0, RadioTerrainCache.Snapshot.copy(chunk));
        drain();
        CountDownLatch release = blockWorker();
        try {
            for (int i = 0; i < RadioTerrainCache.QUEUE_CAPACITY; i++) assertTrue(worker.offer(null, () -> {}));
            assertFalse(worker.offer(null, () -> fail("Overflow must not run in the caller")));
            wall(chunk, Blocks.STONE.defaultBlockState());
            cache.put(0, RadioTerrainCache.Snapshot.copy(chunk));
            assertEquals(SOLID, cache.sample(new BlockPos(8, 66, 0)));
            var snapshot = RadioTerrainCache.Snapshot.copy(chunk);
            for (int key = 1; key <= RadioTerrainCache.CAPACITY; key++) cache.put(key, snapshot);
            assertNull(cache.get(0), "An evicted, unwritten edit must not reload an older disk file");
        } finally { release.countDown(); }
        cache.close();
        worker.stop();
        assertFalse(worker.offer(null, () -> {}));
        assertNull(cache.get(1));
        assertTrue(Files.exists(directory.resolve("incomplete")));
        worker = new RadioTerrainCache.Worker();
        RadioTerrainCache reopened = cache();
        reopened.get(0);
        drain();
        assertNull(reopened.get(0), "Incomplete persistence is discarded on reopen, never treated as air");
    }

    @Test
    void closingAndReopeningDimensionOnSameWorkerKeepsWriteAndMarkerOrdering() throws Exception {
        RadioTerrainCache first = cache();
        first.put(0, RadioTerrainCache.Snapshot.copy(observedChunk(0, 0)));
        first.close();
        RadioTerrainCache second = cache();
        second.get(0);
        // stop waits for initialization, old writes, old close, new decode and final close.
        worker.stop();
        assertNotNull(second.get(0));
        assertFalse(Files.exists(directory.resolve("incomplete")));
    }

    @Test
    void corruptOrWrongDimensionHeightDataRemainsUnknown() throws Exception {
        RadioTerrainCache first = cache();
        first.put(0, RadioTerrainCache.Snapshot.copy(observedChunk(0, 0)));
        worker.stop();
        worker = new RadioTerrainCache.Worker();
        RadioTerrainCache wrongHeight = new RadioTerrainCache(directory, -64, 384, worker);
        wrongHeight.get(0);
        drain();
        assertNull(wrongHeight.get(0));
        Files.write(directory.resolve("1.radio.gz"), new byte[]{1, 2, 3});
        wrongHeight.get(1);
        drain();
        assertNull(wrongHeight.get(1));
    }

    @Test
    void dimensionDirectoriesDoNotShareTerrain() {
        RadioTerrainCache first = cache();
        RadioTerrainCache second = new RadioTerrainCache(directory.resolve("other-dimension"), 64, 16, worker);
        first.put(0, RadioTerrainCache.Snapshot.copy(observedChunk(0, 0)));
        second.get(0);
        drain();
        assertNotNull(first.get(0));
        assertNull(second.get(0));
    }

    @Test
    void persistedFilesStayBoundedAndEvictedTerrainRemainsUnknownAfterRestart() throws Exception {
        RadioTerrainCache cache = cache();
        var raw = RadioTerrainCache.Snapshot.copy(observedChunk(0, 0));
        var compact = RadioTerrainCache.Snapshot.class.getDeclaredMethod("compact");
        compact.setAccessible(true);
        var snapshot = (RadioTerrainCache.Snapshot) compact.invoke(raw);
        for (int key = 0; key <= RadioTerrainCache.DISK_CAPACITY; key++) {
            cache.put(key, snapshot);
            if (key % 32 == 0) drain();
        }
        cache.close();
        worker.stop();
        try (var files = Files.list(directory)) {
            assertEquals(RadioTerrainCache.DISK_CAPACITY, files.filter(path -> path.toString().endsWith(".radio.gz")).count());
        }
        assertFalse(Files.exists(directory.resolve("0.radio.gz")));
        worker = new RadioTerrainCache.Worker();
        RadioTerrainCache restored = cache();
        restored.get(0);
        restored.get(RadioTerrainCache.DISK_CAPACITY);
        drain();
        assertNull(restored.get(0));
        assertNotNull(restored.get(RadioTerrainCache.DISK_CAPACITY));
    }

    private ServerLevel level() {
        server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(server.isSameThread()).thenReturn(true);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory);
        when(server.getAllLevels()).thenReturn(List.of(level));
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(level.getMinY()).thenReturn(64);
        when(level.getMaxY()).thenReturn(79);
        when(level.getHeight()).thenReturn(16);
        when(level.getChunkSource()).thenReturn(chunks);
        return level;
    }

    @Test
    void unloadEventCapturesLatestObstacleReloadedLiveTerrainAlwaysWins() {
        ServerLevel level = level();
        LevelChunk chunk = observedChunk(0, 0);
        when(chunk.getLevel()).thenReturn(level);
        when(level.getChunkSource().getChunkNow(0, 0)).thenReturn(chunk);
        RadioTerrainEvents.loaded(new ChunkEvent.Load(chunk, false));
        BlockPos source = new BlockPos(0, 66, 0), target = new BlockPos(15, 66, 0);
        float air = SignalPropagator.calculateSignal(level, source, target, G2_900).powerDbm;
        wall(chunk, Blocks.STONE.defaultBlockState());
        float obstacle = SignalPropagator.calculateSignal(level, source, target, G2_900).powerDbm;
        assertTrue(obstacle < air);
        RadioTerrainEvents.unloaded(new ChunkEvent.Unload(chunk));
        when(level.getChunkSource().getChunkNow(0, 0)).thenReturn(null);
        assertEquals(obstacle, SignalPropagator.calculateSignal(level, source, target, G2_900).powerDbm);
        wall(chunk, Blocks.AIR.defaultBlockState());
        assertEquals(obstacle, SignalPropagator.calculateSignal(level, source, target, G2_900).powerDbm);
        when(level.getChunkSource().getChunkNow(0, 0)).thenReturn(chunk);
        assertEquals(air, SignalPropagator.calculateSignal(level, source, target, G2_900).powerDbm);
        verify(level, never()).getChunk(anyInt(), anyInt());
        verify(level, never()).getBlockState(any(BlockPos.class));
    }

    @Test
    void capturesMustRunOnServerThread() {
        ServerLevel level = level();
        when(server.isSameThread()).thenReturn(false);
        LevelChunk chunk = observedChunk(0, 0);
        assertThrows(IllegalStateException.class, () -> RadioTerrainCache.capture(level, chunk));
        verify(chunk, never()).getSections();
    }

    @Test
    void worldCloseRecapturesLiveEditsEvenWithoutAChunkUnloadEvent() {
        ServerLevel level = level();
        LevelChunk chunk = observedChunk(0, 0);
        when(level.getChunkSource().getChunkNow(0, 0)).thenReturn(chunk);
        RadioTerrainCache.capture(level, chunk);
        wall(chunk, Blocks.STONE.defaultBlockState());
        RadioTerrainCache.stop(server);
        assertEquals(UNKNOWN, RadioTerrainCache.sample(level, new BlockPos(8, 66, 0)));
        worker = new RadioTerrainCache.Worker();
        RadioTerrainCache restored = new RadioTerrainCache(directory.resolve("telecom-radio/minecraft/overworld"), 64, 16, worker);
        restored.get(0);
        drain();
        assertEquals(SOLID, restored.sample(new BlockPos(8, 66, 0)));
    }

    @Test
    void surfaceCoverageUsesSameUnloadedTerrainAsRadioAndReloadInvalidatesRevision() {
        ServerLevel level = level();
        LevelChunk chunk = observedChunk(4, 4);
        RadioTerrainCache.captureUnloaded(level, chunk);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        NetworkNode node = new NetworkNode(new BlockPos(65, 66, 64), NetworkNode.NodeType.ANTENNA);
        node.setFrequenciesMask(1 << G2_900.ordinal());
        graph.addNode(node);
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            var request = new CoverageService.Request(0, 0, 128, "surface", "all", "all", "all");
            CoverageService.request(level, request, Long.MAX_VALUE);
            CoverageService.tickUntil(server, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            var tile = JsonParser.parseString(CoverageService.request(level, request, Long.MAX_VALUE)).getAsJsonObject();
            var cell = tile.getAsJsonArray("cells").get(0).getAsJsonObject();
            assertEquals("signal", cell.get("state").getAsString());
            assertEquals(66, cell.get("y").getAsInt());
            assertEquals(SignalPropagator.calculateSignal(level, node.getPosition(), new BlockPos(64, 66, 64), G2_900).powerDbm,
                    cell.get("powerDbm").getAsFloat());
            String revision = CoverageService.modelRevision(level);
            RadioTerrainCache.captureUnloaded(level, observedChunk(100, 100));
            assertEquals(revision, CoverageService.modelRevision(level),
                    "Warming unrelated terrain must not restart this tile");
            assertEquals(tile.get("revision"), JsonParser.parseString(CoverageService.request(level, request, Long.MAX_VALUE))
                    .getAsJsonObject().get("revision"));
            RadioTerrainCache.captureUnloaded(level, chunk);
            assertNotEquals(revision, CoverageService.modelRevision(level));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tick", "request", "modelRevision"})
    void diskWarmupInvalidatesUnknownTilesOnlyWhenServerConsumesRevision(String entryPoint) throws Exception {
        ServerLevel level = level();
        Thread serverThread = Thread.currentThread();
        when(server.isSameThread()).thenAnswer(call -> Thread.currentThread() == serverThread);
        LevelChunk observed = observedChunk(4, 4);
        wall(observed, Blocks.STONE.defaultBlockState());
        RadioTerrainCache.captureUnloaded(level, observed);
        RadioTerrainCache.stop(server);

        // Open the same dimension by observing an unrelated chunk, leaving the radio path on disk.
        RadioTerrainCache.captureUnloaded(level, observedChunk(20, 20));
        var workerField = RadioTerrainCache.class.getDeclaredField("worker");
        workerField.setAccessible(true);
        worker = (RadioTerrainCache.Worker) workerField.get(null);
        drain();
        CountDownLatch release = blockWorker();
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        NetworkNode node = new NetworkNode(new BlockPos(76, 66, 64), NetworkNode.NodeType.ANTENNA);
        node.setFrequenciesMask(1 << G2_900.ordinal());
        graph.addNode(node);
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            var request = new CoverageService.Request(0, 0, 128, "surface", "all", "all", "all");
            CoverageService.request(level, request, Long.MAX_VALUE);
            CoverageService.tickUntil(server, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            var unknown = JsonParser.parseString(CoverageService.request(level, request, Long.MAX_VALUE)).getAsJsonObject();
            assertEquals("ready", unknown.get("status").getAsString());
            assertEquals("unknown", unknown.getAsJsonArray("cells").get(0).getAsJsonObject().get("state").getAsString());
            String model = CoverageService.modelRevision(level);
            long terrainRevision = RadioTerrainCache.revision(level);

            // Inspect raw state: using modelRevision/request here would itself consume the change.
            var statesField = CoverageService.class.getDeclaredField("STATES");
            statesField.setAccessible(true);
            Object state = ((Map<?, ?>) statesField.get(null)).get(level);
            var entriesField = state.getClass().getDeclaredField("entries");
            entriesField.setAccessible(true);
            Map<?, ?> entries = (Map<?, ?>) entriesField.get(state);
            Object originalJob = entries.get(request);
            var modelField = state.getClass().getDeclaredField("modelRevision");
            modelField.setAccessible(true);
            long originalModel = modelField.getLong(state);

            release.countDown();
            drain();
            assertTrue(RadioTerrainCache.revision(level) > terrainRevision);
            assertSame(originalJob, entries.get(request), "Disk worker must not mutate CoverageService entries");
            assertEquals(originalModel, modelField.getLong(state), "Disk worker must not invalidate coverage");

            // Every public consumption path must invalidate immediately, not wait for the tile TTL.
            switch (entryPoint) {
                case "tick" -> CoverageService.tick(server);
                case "request" -> CoverageService.request(level, request, Long.MAX_VALUE);
                case "modelRevision" -> CoverageService.modelRevision(level);
                default -> fail(entryPoint);
            }
            assertNotSame(originalJob, entries.get(request));
            assertNotEquals(model, CoverageService.modelRevision(level));
            var pending = JsonParser.parseString(CoverageService.request(level, request, Long.MAX_VALUE)).getAsJsonObject();
            assertEquals("pending", pending.get("status").getAsString());
            assertNotEquals(unknown.get("revision"), pending.get("revision"));
            CoverageService.tickUntil(server, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            var ready = JsonParser.parseString(CoverageService.request(level, request, Long.MAX_VALUE)).getAsJsonObject();
            var cell = ready.getAsJsonArray("cells").get(0).getAsJsonObject();
            assertEquals("signal", cell.get("state").getAsString());
            assertEquals(66, cell.get("y").getAsInt());
            var radio = SignalPropagator.calculateSignal(level, node.getPosition(), new BlockPos(64, 66, 64), G2_900);
            assertTrue(radio.known);
            assertEquals(radio.powerDbm, cell.get("powerDbm").getAsFloat());
            verify(level, never()).getChunk(anyInt(), anyInt());
            verify(level, never()).getBlockState(any(BlockPos.class));
        } finally { release.countDown(); }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> map(RadioTerrainCache cache, String name) throws Exception {
        var field = RadioTerrainCache.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(cache);
    }
}
