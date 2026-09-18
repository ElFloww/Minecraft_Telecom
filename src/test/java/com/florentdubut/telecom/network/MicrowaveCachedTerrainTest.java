package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.florentdubut.telecom.network.MicrowaveLinkEvaluatorTest.pointing;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(60)
class MicrowaveCachedTerrainTest {
    @TempDir Path directory;

    @Test
    void cold4096MetreLinkCompletesAcross257ChunksWithRealDrainedDiskReadsAndNoChunkLoads() throws Exception {
        var writer = new RadioTerrainCache.Worker();
        var saved = new RadioTerrainCache(directory, 64, 16, writer);
        try {
            var air = RadioTerrainCache.Snapshot.copy(RadioTerrainCacheTest.observedChunk(0, 0));
            for (int x = 0; x <= 256; x++) {
                saved.put(ChunkPos.asLong(x, 0), air);
                if (x % 32 == 0) drain(writer);
            }
        } finally {
            saved.close();
            writer.stop();
        }

        // Hundreds of thousands of volume probes should not retain Mockito invocation histories.
        AtomicInteger liveChecks = new AtomicInteger();
        ServerLevel level = mock(ServerLevel.class, withSettings().stubOnly().defaultAnswer(call -> {
            String method = call.getMethod().getName();
            if (method.equals("getChunk") || method.equals("getBlockState") || method.equals("getBlockEntity")
                    || method.equals("hasChunk") || method.equals("hasChunkAt")) fail("Forbidden world access: " + method);
            return RETURNS_DEFAULTS.answer(call);
        }));
        ServerChunkCache chunks = mock(ServerChunkCache.class, withSettings().stubOnly().defaultAnswer(call -> {
            assertEquals("getChunkNow", call.getMethod().getName(), "Only non-loading lookups are permitted");
            liveChecks.incrementAndGet();
            return null;
        }));
        when(level.getChunkSource()).thenReturn(chunks);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        when(level.getServer()).thenReturn(server);
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        BlockPos a = new BlockPos(0, 72, 8), b = new BlockPos(4096, 72, 8);
        var source = new NetworkNode(a, NetworkNode.NodeType.MICROWAVE_DISH);
        var target = new NetworkNode(b, NetworkNode.NodeType.MICROWAVE_DISH);
        source.setMicrowaveConfig(pointing(a, b, 6));
        target.setMicrowaveConfig(pointing(b, a, 6));
        graph.addNode(source); graph.addNode(target);
        var reader = new RadioTerrainCache.Worker();
        var restored = new RadioTerrainCache(directory, 64, 16, reader);
        levels().put(level, restored);
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            int ticks = 0;
            while (graph.getEdges().isEmpty() && ticks++ < 2000) {
                // No reads can finish inside a quantum: explicitly release and drain them after it.
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                assertTrue(reader.offer(null, () -> { entered.countDown(); await(release); }));
                await(entered);
                try { MicrowaveLinkService.tickUntil(level, Long.MAX_VALUE); }
                finally { release.countDown(); }
                drain(reader);
                // Only the following tick consumes the completed availability revision.
                assertTrue(snapshotCount(restored) <= 256, "Evaluation must not pin the entire corridor in the LRU");
            }
            assertFalse(graph.getEdges().isEmpty(), "Cached-only link never completed; status=" + MicrowaveLinkService.status(level, a));
            assertTrue(MicrowaveLinkService.status(level, a).capacityMbps() > 0);
            long reads = RadioTerrainCache.revision(level);
            assertTrue(reads > 256 && reads <= 600, "Center and clearance passes must not keep replaying prefixes: " + reads);

            var edge = graph.getEdges().getFirst();
            long topology = graph.getTopologyRevision();
            // Restore an evicted prefix after completion. Availability must not revoke valid proof.
            restored.sample(a);
            drain(reader);
            MicrowaveLinkService.tickUntil(level, Long.MAX_VALUE);
            assertSame(edge, graph.getEdges().getFirst());
            assertEquals(topology, graph.getTopologyRevision());

            // Exercise all three real lifecycle paths, including a fresh detached palette capture.
            var unloading = RadioTerrainCacheTest.observedChunk(0, 0);
            when(unloading.getLevel()).thenReturn(level);
            var unload = new net.neoforged.neoforge.event.level.ChunkEvent.Unload(unloading);
            com.florentdubut.telecom.event.RadioTerrainEvents.unloaded(unload);
            com.florentdubut.telecom.event.CoverageEvents.chunkUnloaded(unload);
            com.florentdubut.telecom.event.MicrowaveEvents.unloaded(unload);
            drain(reader);
            MicrowaveLinkService.tickUntil(level, Long.MAX_VALUE);
            assertSame(edge, graph.getEdges().getFirst());
            assertEquals(topology, graph.getTopologyRevision());
            assertTrue(MicrowaveLinkService.status(level, a).capacityMbps() > 0);

            // Unlike an arrival, an explicit world edit revokes the proof even in an evicted prefix.
            MicrowaveLinkService.invalidateChunk(level, 1, 0);
            assertTrue(graph.getEdges().isEmpty());
            var changed = RadioTerrainCacheTest.observedChunk(1, 0);
            changed.getSections()[0].getStates().set(0, 8, 8, Blocks.STONE.defaultBlockState());
            restored.put(ChunkPos.asLong(1, 0), RadioTerrainCache.Snapshot.copy(changed));
            drain(reader);
            MicrowaveLinkService.tickUntil(level, Long.MAX_VALUE);
            assertEquals("blocked", MicrowaveLinkService.status(level, a).state());
            assertEquals(new BlockPos(16, 72, 8), MicrowaveLinkService.status(level, a).blocker());
            assertTrue(liveChecks.get() > 257);
        } finally {
            MicrowaveLinkService.unload(level);
            levels().remove(level);
            restored.close();
            reader.stop();
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "Terrain worker did not drain"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static void drain(RadioTerrainCache.Worker worker) {
        CountDownLatch complete = new CountDownLatch(1);
        assertTrue(worker.offer(null, complete::countDown));
        await(complete);
    }

    @SuppressWarnings("unchecked")
    private static Map<ServerLevel, RadioTerrainCache> levels() throws Exception {
        var field = RadioTerrainCache.class.getDeclaredField("LEVELS");
        field.setAccessible(true);
        return (Map<ServerLevel, RadioTerrainCache>) field.get(null);
    }

    private static int snapshotCount(RadioTerrainCache cache) throws Exception {
        var field = RadioTerrainCache.class.getDeclaredField("snapshots");
        field.setAccessible(true);
        synchronized (cache) { return ((Map<?, ?>) field.get(cache)).size(); }
    }
}
