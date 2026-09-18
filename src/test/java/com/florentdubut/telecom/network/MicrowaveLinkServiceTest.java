package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.florentdubut.telecom.network.MicrowaveLinkEvaluatorTest.pointing;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(30)
class MicrowaveLinkServiceTest {
    private ServerLevel level;
    private ServerChunkCache chunks;
    private LevelChunk chunk;
    private TelecomNetworkGraph graph;
    private MockedStatic<TelecomNetworkGraph> graphs;

    @BeforeEach
    void setup() {
        level = mock(ServerLevel.class);
        chunks = mock(ServerChunkCache.class);
        chunk = mock(LevelChunk.class);
        when(level.getChunkSource()).thenReturn(chunks);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
        graph = new TelecomNetworkGraph();
        graphs = mockStatic(TelecomNetworkGraph.class);
        graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
    }

    @AfterEach
    void teardown() {
        MicrowaveLinkService.clear();
        graphs.close();
    }

    private NetworkNode dish(BlockPos pos, MicrowaveConfig config) {
        var node = new NetworkNode(pos, NetworkNode.NodeType.MICROWAVE_DISH);
        node.setMicrowaveConfig(config);
        graph.addNode(node);
        return node;
    }

    private void pair(BlockPos a, BlockPos b) {
        dish(a, pointing(a, b, 11));
        dish(b, pointing(b, a, 11));
    }

    private void finish() { MicrowaveLinkService.tickUntil(level, Long.MAX_VALUE); }

    @Test
    void createsOnlyOneUndirectedLinkWithoutBackhaulAndRetainsIdentity() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        assertEquals("pending", MicrowaveLinkService.status(level, a).state());
        finish();
        assertEquals(1, graph.getEdges().size());
        NetworkEdge edge = graph.getEdges().getFirst();
        assertEquals(NetworkEdge.EdgeType.MICROWAVE, edge.getType());
        assertEquals(600, edge.getEffectiveBandwidthMbps());
        assertEquals(1, MicrowaveLinkService.links(level).size());
        assertEquals("ready", MicrowaveLinkService.status(level, b).state());
        long revision = graph.getTopologyRevision();
        edge.setCurrentUsage(57);
        finish();
        assertSame(edge, graph.getEdges().getFirst());
        assertEquals(revision, graph.getTopologyRevision());
        assertEquals(57, edge.getCurrentUsage());
        verify(level, never()).getBlockEntity(any());
        verify(level, never()).getBlockState(any());
        verify(chunks, atLeastOnce()).getChunkNow(anyInt(), anyInt());
        verifyNoMoreInteractions(chunks);
    }

    @Test
    void mountedDishesRouteButARealClearanceIntrusionStillWithdrawsTheirEdge() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(200, 64, 0);
        BlockPos intrusion = new BlockPos(100, 63, 0);
        var obstructed = new java.util.concurrent.atomic.AtomicBoolean();
        pair(a, b);
        when(chunk.getBlockState(any())).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            boolean solid = pos.equals(a) || pos.equals(b) || pos.equals(a.below()) || pos.equals(b.below())
                    || (obstructed.get() && pos.equals(intrusion));
            return solid ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });
        finish();
        assertEquals("ready", MicrowaveLinkService.status(level, a).state());
        assertEquals(600, graph.getEdges().getFirst().getEffectiveBandwidthMbps());
        obstructed.set(true);
        CoverageService.invalidateChunk(level, intrusion.getX() >> 4, intrusion.getZ() >> 4);
        assertTrue(graph.getEdges().isEmpty());
        finish();
        assertEquals("fresnel_blocked", MicrowaveLinkService.status(level, a).state());
        assertEquals(intrusion, MicrowaveLinkService.status(level, a).blocker());
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    void invalidationIncludesFutureOffAxisChunksAndPreservesUnrelatedEdges() {
        BlockPos a = new BlockPos(0, 64, 15), b = new BlockPos(1000, 64, 15);
        BlockPos c = new BlockPos(0, 64, 100), d = new BlockPos(100, 64, 100);
        pair(a, b); pair(c, d);
        finish();
        NetworkEdge unrelated = graph.getEdges().stream().filter(e -> e.getNodeA().equals(c) || e.getNodeB().equals(c)).findFirst().orElseThrow();
        CoverageService.clear();
        // z=16 is outside the centerline chunk, but inside the 60% Fresnel corridor.
        CoverageService.invalidateChunk(level, 31, 1);
        assertEquals("pending", MicrowaveLinkService.status(level, a).state());
        assertEquals(List.of(unrelated), graph.getEdges());
        clearInvocations(chunk);
        MicrowaveLinkService.tickUntil(level, System.nanoTime() - 1);
        verifyNoInteractions(chunk);
        CoverageService.invalidateChunk(level, 60, 1);
        assertEquals("pending", MicrowaveLinkService.status(level, a).state());
        finish();
        assertEquals("ready", MicrowaveLinkService.status(level, a).state());
        assertTrue(graph.getEdges().contains(unrelated));
        when(chunk.getBlockState(any())).thenAnswer(i -> {
            BlockPos p = i.getArgument(0);
            return p.equals(new BlockPos(500, 64, 15)) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });
        CoverageService.invalidateChunk(level, 31, 0);
        assertEquals(List.of(unrelated), graph.getEdges());
        finish();
        assertEquals("blocked", MicrowaveLinkService.status(level, a).state());
    }

    @Test
    void cacheRevisionInvalidatesOnlyDependentLinksAndLoadedTerrainWins() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        AtomicLong revision = new AtomicLong();
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            cache.when(() -> RadioTerrainCache.revision(level)).thenAnswer(i -> revision.get());
            cache.when(() -> RadioTerrainCache.changedChunks(eq(level), anyLong(), anyLong()))
                    .thenReturn(Set.of(ChunkPos.asLong(100, 100)));
            cache.when(() -> RadioTerrainCache.sample(eq(level), any())).thenReturn(SignalPropagator.Material.SOLID);
            finish();
            var edge = graph.getEdges().getFirst();
            revision.incrementAndGet();
            MicrowaveLinkService.tickUntil(level, System.nanoTime() - 1);
            assertSame(edge, graph.getEdges().getFirst());
            cache.verify(() -> RadioTerrainCache.sample(eq(level), any()), never());
            cache.when(() -> RadioTerrainCache.changedChunks(eq(level), anyLong(), anyLong()))
                    .thenReturn(Set.of(ChunkPos.asLong(3, 0)));
            revision.incrementAndGet();
            MicrowaveLinkService.tickUntil(level, System.nanoTime() - 1);
            assertTrue(graph.getEdges().isEmpty());
            assertEquals("pending", MicrowaveLinkService.status(level, a).state());
            finish();
            assertSame(edge, graph.getEdges().getFirst());
        }
    }

    @Test
    void unloadedObservedTerrainWorksWithoutPeerEntityAndUnknownRetriesAreDebounced() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(null);
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            cache.when(() -> RadioTerrainCache.sample(eq(level), any())).thenReturn(SignalPropagator.Material.UNKNOWN);
            finish();
            assertEquals("unknown", MicrowaveLinkService.status(level, a).state());
            assertTrue(graph.getEdges().isEmpty());
            cache.clearInvocations();
            for (int i = 0; i < 10; i++) finish();
            cache.verify(() -> RadioTerrainCache.sample(eq(level), any()), never());
            cache.when(() -> RadioTerrainCache.sample(eq(level), any())).thenReturn(SignalPropagator.Material.AIR);
            cache.when(() -> RadioTerrainCache.revision(level)).thenReturn(1L);
            cache.when(() -> RadioTerrainCache.changedChunks(eq(level), anyLong(), anyLong())).thenReturn(Set.of(ChunkPos.asLong(0, 0)));
            finish(); finish(); finish();
            assertEquals("ready", MicrowaveLinkService.status(level, a).state());
            verifyNoInteractions(chunk);
            verify(level, never()).getBlockEntity(any());
            verify(chunks, atLeastOnce()).getChunkNow(anyInt(), anyInt());
            verifyNoMoreInteractions(chunks);
        }
    }

    @Test
    void endpointConfigurationChangeAndRestartNeverRetainOperationalState() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        finish();
        graph.getNode(b).setMicrowaveConfig(new MicrowaveConfig(a, 2, 11, 90, 0, true));
        MicrowaveLinkService.invalidateEndpoint(level, b);
        assertTrue(graph.getEdges().isEmpty());
        finish();
        assertEquals("channel_mismatch", MicrowaveLinkService.status(level, a).state());
        graph.getNode(b).setMicrowaveConfig(pointing(b, a, 11));
        finish();
        assertEquals("ready", MicrowaveLinkService.status(level, a).state());
        MicrowaveLinkService.unload(level);
        assertTrue(graph.getEdges().isEmpty());
        assertEquals("pending", MicrowaveLinkService.status(level, a).state());
        finish();
        assertEquals("ready", MicrowaveLinkService.status(level, a).state());
    }

    @Test
    void disabledUnpairedAndWrongDimensionDiagnosticsNeverSampleTerrain() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        dish(a, MicrowaveConfig.DEFAULT);
        dish(b, new MicrowaveConfig(new BlockPos(300, 64, 0), 1, 11, 0, 0, true));
        finish();
        assertEquals("disabled", MicrowaveLinkService.status(level, a).state());
        assertEquals("unpaired", MicrowaveLinkService.status(level, b).state());
        assertEquals(b, MicrowaveLinkService.status(level, b).target());
        assertEquals(2, MicrowaveLinkService.links(level).size());
        assertTrue(graph.getEdges().isEmpty());
        verifyNoInteractions(chunks, chunk);
    }

    @Test
    void catalogueAndOutputLimitsAreFailClosedAndDoNotSampleTerrain() {
        for (int i = 0; i < 257; i++) dish(new BlockPos(i, 64, 0), MicrowaveConfig.DEFAULT);
        finish();
        assertEquals(128, MicrowaveLinkService.links(level).size());
        assertTrue(graph.getEdges().isEmpty());
        verifyNoInteractions(chunks);
        for (int i = 257; i < 8193; i++) graph.addNode(new NetworkNode(new BlockPos(i, 64, 0), NetworkNode.NodeType.ROUTER));
        finish();
        assertTrue(MicrowaveLinkService.links(level).isEmpty());
        verifyNoInteractions(chunks);
    }

    @Test
    void expiredSharedBudgetDoesNotProbeTerrain() {
        pair(new BlockPos(0, 64, 0), new BlockPos(4096, 64, 0));
        MicrowaveLinkService.tickUntil(level, System.nanoTime() - 1);
        assertTrue(graph.getEdges().isEmpty());
        verifyNoInteractions(chunks, chunk);
        assertEquals("pending", MicrowaveLinkService.status(level, new BlockPos(0, 64, 0)).state());
    }

    @Test
    void exactly128PairsFitAndDishOverflowRemovesAllRoutableEdges() {
        for (int i = 0; i < 128; i++) pair(new BlockPos(i * 4, 64, 0), new BlockPos(i * 4 + 1, 64, 0));
        finish();
        assertEquals(128, graph.getEdges().size());
        assertEquals(128, MicrowaveLinkService.links(level).size());
        dish(new BlockPos(1000, 64, 0), MicrowaveConfig.DEFAULT);
        finish();
        assertTrue(graph.getEdges().isEmpty());
        assertEquals("limit", MicrowaveLinkService.status(level, new BlockPos(0, 64, 0)).state());
    }

    @Test
    void tickRevalidatesCacheRollbackAndDirectConfigChangesWhileReadsStayReadOnly() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        finish();
        clearInvocations(chunk);
        graph.getNode(a).setMicrowaveConfig(MicrowaveConfig.DEFAULT);
        assertEquals("disabled", MicrowaveLinkService.status(level, a).state());
        assertFalse(graph.getEdges().isEmpty(), "Reading a changed config must not mutate the graph");
        MicrowaveLinkService.tickUntil(level, System.nanoTime() - 1);
        assertTrue(graph.getEdges().isEmpty());
        verifyNoInteractions(chunk);
        graph.getNode(a).setMicrowaveConfig(pointing(a, b, 11));
        finish();
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            cache.when(() -> RadioTerrainCache.revision(level)).thenReturn(1L);
            cache.when(() -> RadioTerrainCache.changedChunks(eq(level), anyLong(), anyLong())).thenReturn(null);
            clearInvocations(chunk);
            assertEquals("ready", MicrowaveLinkService.status(level, a).state(), "An availability journal gap is not a mutation");
            MicrowaveLinkService.tickUntil(level, System.nanoTime() - 1);
            cache.when(() -> RadioTerrainCache.revision(level)).thenReturn(0L);
            assertEquals("ready", MicrowaveLinkService.status(level, a).state(), "Reads do not consume cache rollback");
            MicrowaveLinkService.tickUntil(level, System.nanoTime() - 1);
            assertEquals("pending", MicrowaveLinkService.status(level, a).state());
            assertTrue(graph.getEdges().isEmpty());
            verifyNoInteractions(chunk);
        }
    }

    @Test
    void lifecycleEventsInvalidateAndClearDerivedLinks() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        when(chunk.getPos()).thenReturn(new ChunkPos(3, 0));
        finish();
        var load = mock(net.neoforged.neoforge.event.level.ChunkEvent.Load.class);
        when(load.getLevel()).thenReturn(level);
        when(load.getChunk()).thenReturn(chunk);
        com.florentdubut.telecom.event.MicrowaveEvents.loaded(load);
        assertTrue(graph.getEdges().isEmpty());
        finish();
        var unload = mock(net.neoforged.neoforge.event.level.ChunkEvent.Unload.class);
        when(unload.getLevel()).thenReturn(level);
        when(unload.getChunk()).thenReturn(chunk);
        com.florentdubut.telecom.event.MicrowaveEvents.unloaded(unload);
        assertTrue(graph.getEdges().isEmpty());
        finish();
        var close = mock(net.neoforged.neoforge.event.level.LevelEvent.Unload.class);
        when(close.getLevel()).thenReturn(level);
        com.florentdubut.telecom.event.MicrowaveEvents.levelUnloaded(close);
        assertTrue(graph.getEdges().isEmpty());
        assertEquals("pending", MicrowaveLinkService.status(level, a).state());
        finish();
        com.florentdubut.telecom.event.MicrowaveEvents.stopped(mock(net.neoforged.neoforge.event.server.ServerStoppedEvent.class));
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    void cacheArrivalDuringEvaluationCannotPublishMixedRevisionEdge() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        AtomicLong revision = new AtomicLong();
        when(chunk.getBlockState(any())).thenAnswer(i -> {
            BlockPos position = i.getArgument(0);
            if (position.getX() == 50) revision.compareAndSet(0, 1);
            return Blocks.AIR.defaultBlockState();
        });
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            cache.when(() -> RadioTerrainCache.revision(level)).thenAnswer(i -> revision.get());
            cache.when(() -> RadioTerrainCache.changedChunks(eq(level), anyLong(), anyLong()))
                    .thenReturn(Set.of(ChunkPos.asLong(3, 0)));
            finish();
            assertTrue(graph.getEdges().isEmpty());
            assertEquals("pending", MicrowaveLinkService.status(level, a).state());
            finish();
            assertEquals("ready", MicrowaveLinkService.status(level, a).state());
        }
    }

    @Test
    void graphOwnedTickDoesNotLookUpSavedDataAgain() {
        graphs.clearInvocations();
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            MicrowaveLinkService.tick(level, graph);
            graph.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.ROUTER));
            for (int i = 0; i < 5; i++) MicrowaveLinkService.tick(level, graph);
            MicrowaveLinkService.chunkUnloaded(level, 0, 0);
            cache.verifyNoInteractions();
        }
        graphs.verifyNoInteractions();
        verifyNoInteractions(level, chunks, chunk);
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    void removingLastDishesClearsOldFhStateWithoutTerrainOrSavedDataAccess() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        finish();
        var oldLink = graph.getEdges().getFirst();
        var wire = new NetworkEdge(new BlockPos(1000, 64, 0), new BlockPos(1001, 64, 0),
                1000, 1, NetworkEdge.EdgeType.COPPER, List.of());
        graph.setCableEdges(List.of(wire));
        graph.removeNode(a);
        graph.removeNode(b);
        // Exercise cleanup even when a derived edge survived an external graph update.
        graph.setMicrowaveEdges(List.of(oldLink));
        graphs.clearInvocations();
        clearInvocations(level, chunks, chunk);
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            MicrowaveLinkService.tick(level, graph);
            assertEquals(List.of(wire), graph.getEdges());
            MicrowaveLinkService.tick(level, graph);
            cache.verifyNoInteractions();
        }
        graphs.verifyNoInteractions();
        verifyNoInteractions(level, chunks, chunk);
        assertTrue(MicrowaveLinkService.links(level).isEmpty());
        pair(a, b);
        finish();
        assertEquals("ready", MicrowaveLinkService.status(level, a).state());
        assertTrue(graph.getEdges().contains(wire));
    }

    @Test
    void cableRewiresAndUnrelatedNodesPreserveReadyAndDegradedJobsWithoutTerrainProbes() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        BlockPos c = new BlockPos(0, 64, 100), d = new BlockPos(100, 64, 100);
        pair(a, b);
        dish(c, new MicrowaveConfig(d, 1, 11, 290, 0, true));
        dish(d, pointing(d, c, 11));
        finish();
        var initial = MicrowaveLinkService.links(level);
        assertEquals("ready", MicrowaveLinkService.status(level, a).state());
        assertEquals("degraded", MicrowaveLinkService.status(level, c).state());
        var microwaveEdges = List.copyOf(graph.getEdges());
        clearInvocations(chunks, chunk);
        for (int i = 0; i < 10; i++) {
            BlockPos router = new BlockPos(1000 + i, 64, 1000);
            graph.addNode(new NetworkNode(router, NetworkNode.NodeType.ROUTER));
            var cable = new NetworkEdge(a, router, 100 + i, 100, NetworkEdge.EdgeType.FIBER, List.of());
            graph.setCableEdges(List.of(cable));
            long revision = graph.getTopologyRevision();
            assertEquals(initial, MicrowaveLinkService.links(level));
            finish();
            assertEquals(revision, graph.getTopologyRevision(), "FH publication must not feed back into discovery");
            assertEquals(initial, MicrowaveLinkService.links(level));
            var current = graph.getEdges().stream().filter(e -> e.getType() == NetworkEdge.EdgeType.MICROWAVE).toList();
            assertEquals(microwaveEdges, current, "Both edge objects must survive unrelated topology changes");
            graph.setCableEdges(List.of());
            graph.removeNode(router);
            finish();
            assertEquals(initial, MicrowaveLinkService.links(level));
        }
        verifyNoInteractions(chunks, chunk);
    }

    @Test
    void blockedCenterlineSurvivesCableRevisionAndRepairsOnlyAfterTerrainInvalidation() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        BlockPos wall = new BlockPos(50, 64, 0);
        pair(a, b);
        when(chunk.getBlockState(any())).thenAnswer(i -> wall.equals(i.getArgument(0))
                ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        finish();
        var blocked = MicrowaveLinkService.status(level, a);
        assertEquals("blocked", blocked.state());
        assertEquals(wall, blocked.blocker());
        clearInvocations(chunks, chunk);
        var wire = new NetworkEdge(a, b, 1000, 100, NetworkEdge.EdgeType.FIBER, List.of());
        graph.setCableEdges(List.of(wire));
        finish();
        assertEquals(blocked, MicrowaveLinkService.status(level, a));
        assertEquals(List.of(wire), graph.getEdges());
        verifyNoInteractions(chunks, chunk);
        when(chunk.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
        CoverageService.invalidateChunk(level, wall.getX() >> 4, wall.getZ() >> 4);
        assertEquals("pending", MicrowaveLinkService.status(level, a).state());
        assertEquals(List.of(wire), graph.getEdges());
        finish();
        assertEquals("ready", MicrowaveLinkService.status(level, a).state());
        assertEquals(2, graph.getEdges().size());
        assertTrue(graph.getEdges().contains(wire));
    }

    @Test
    void capturedEndpointAndCorridorUnloadsRetainWorkingLinksWithoutPeerPolling() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        finish();
        var edge = graph.getEdges().getFirst();
        var ready = MicrowaveLinkService.status(level, a);
        long topology = graph.getTopologyRevision();
        AtomicLong revision = new AtomicLong();
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            cache.when(() -> RadioTerrainCache.revision(level)).thenAnswer(i -> revision.get());
            cache.when(() -> RadioTerrainCache.snapshot(eq(level), anyInt(), anyInt()))
                    .thenReturn(mock(RadioTerrainCache.Snapshot.class));
            cache.when(() -> RadioTerrainCache.sample(eq(level), any())).thenReturn(SignalPropagator.Material.AIR);
            clearInvocations(chunks, chunk);
            for (int x : new int[]{0, 3, 6}) {
                cache.when(() -> RadioTerrainCache.changedChunks(eq(level), anyLong(), anyLong()))
                        .thenReturn(Set.of(ChunkPos.asLong(x, 0)));
                revision.incrementAndGet(); // RadioTerrainEvents captured before the LOWEST-priority callback.
                var event = unloadEvent(x, 0);
                com.florentdubut.telecom.event.CoverageEvents.chunkUnloaded(event);
                com.florentdubut.telecom.event.MicrowaveEvents.unloaded(event);
                assertSame(edge, graph.getEdges().getFirst());
                assertEquals(ready, MicrowaveLinkService.status(level, a));
                finish();
                assertSame(edge, graph.getEdges().getFirst());
                assertEquals(topology, graph.getTopologyRevision());
            }
            verifyNoInteractions(chunks, chunk);
            cache.verify(() -> RadioTerrainCache.sample(eq(level), any()), never());
            cache.verify(() -> RadioTerrainCache.prefetch(eq(level), any(), any()), never());
            // A later genuine edit withdraws the retained link and re-evaluates known unloaded terrain.
            when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(null);
            CoverageService.invalidateChunk(level, 3, 0);
            assertTrue(graph.getEdges().isEmpty());
            finish();
            assertEquals("ready", MicrowaveLinkService.status(level, a).state());
            assertSame(edge, graph.getEdges().getFirst());
        }
    }

    @Test
    void unloadWithoutReliableSnapshotIsUnknownAndNeverWithdrawsUnrelatedLink() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        BlockPos c = new BlockPos(0, 64, 100), d = new BlockPos(100, 64, 100);
        pair(a, b); pair(c, d);
        finish();
        var unrelated = graph.getEdges().stream().filter(e -> e.getNodeA().equals(c) || e.getNodeB().equals(c)).findFirst().orElseThrow();
        when(chunks.getChunkNow(0, 0)).thenReturn(null);
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            cache.when(() -> RadioTerrainCache.sample(eq(level), any())).thenReturn(SignalPropagator.Material.UNKNOWN);
            com.florentdubut.telecom.event.MicrowaveEvents.unloaded(unloadEvent(0, 0));
            assertEquals(List.of(unrelated), graph.getEdges());
            finish();
            assertEquals("unknown", MicrowaveLinkService.status(level, a).state());
            assertEquals(0, MicrowaveLinkService.status(level, a).capacityMbps());
            assertEquals("ready", MicrowaveLinkService.status(level, c).state());
            assertEquals(List.of(unrelated), graph.getEdges());
        }
        verify(level, never()).getBlockEntity(any());
        verify(chunks, atLeastOnce()).getChunkNow(anyInt(), anyInt());
        verifyNoMoreInteractions(chunks);
    }

    @Test
    void freshUnloadCaptureCannotRestoreAnEdgeWithdrawnByLiveWallMutation() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        BlockPos wall = new BlockPos(50, 64, 0);
        pair(a, b);
        finish();
        CoverageService.invalidateChunk(level, 3, 0);
        when(chunks.getChunkNow(3, 0)).thenReturn(null);
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            cache.when(() -> RadioTerrainCache.snapshot(level, 3, 0)).thenReturn(mock(RadioTerrainCache.Snapshot.class));
            cache.when(() -> RadioTerrainCache.revision(level)).thenReturn(1L);
            cache.when(() -> RadioTerrainCache.changedChunks(eq(level), anyLong(), anyLong()))
                    .thenReturn(Set.of(ChunkPos.asLong(3, 0)));
            cache.when(() -> RadioTerrainCache.sample(eq(level), any())).thenAnswer(i -> wall.equals(i.getArgument(1))
                    ? SignalPropagator.Material.SOLID : SignalPropagator.Material.AIR);
            com.florentdubut.telecom.event.MicrowaveEvents.unloaded(unloadEvent(3, 0));
            assertTrue(graph.getEdges().isEmpty());
            finish();
            assertEquals("blocked", MicrowaveLinkService.status(level, a).state());
            assertEquals(wall, MicrowaveLinkService.status(level, a).blocker());
            assertTrue(graph.getEdges().isEmpty());
        }
    }

    @Test
    void availabilityJournalRolloverResumesUnknownWithoutReplayingKnownCenterPrefix() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        BlockPos waiting = new BlockPos(50, 64, 0);
        pair(a, b);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(null);
        var available = new java.util.concurrent.atomic.AtomicBoolean();
        var sourceSamples = new java.util.concurrent.atomic.AtomicInteger();
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            cache.when(() -> RadioTerrainCache.sample(eq(level), any())).thenAnswer(i -> {
                BlockPos pos = i.getArgument(1);
                if (pos.equals(a)) sourceSamples.incrementAndGet();
                return pos.equals(waiting) && !available.get() ? SignalPropagator.Material.UNKNOWN : SignalPropagator.Material.AIR;
            });
            finish();
            assertEquals("unknown", MicrowaveLinkService.status(level, a).state());
            assertEquals(waiting, MicrowaveLinkService.status(level, a).blocker());
            assertEquals(1, sourceSamples.get());
            available.set(true);
            cache.when(() -> RadioTerrainCache.revision(level)).thenReturn(1000L);
            cache.when(() -> RadioTerrainCache.changedChunks(eq(level), anyLong(), anyLong())).thenReturn(null);
            finish(); finish(); finish();
            assertEquals("ready", MicrowaveLinkService.status(level, a).state());
            assertEquals(2, sourceSamples.get(), "Exactly one center pass and one clearance pass, never a restarted prefix");
            var edge = graph.getEdges().getFirst();
            long topology = graph.getTopologyRevision();
            cache.when(() -> RadioTerrainCache.revision(level)).thenReturn(2000L);
            finish();
            assertSame(edge, graph.getEdges().getFirst());
            assertEquals(topology, graph.getTopologyRevision());
        }
    }

    @Test
    void cacheArrivalBetweenEndpointReadsCannotMixMapStatusAndGraphRoutability() {
        graph = spy(graph);
        graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        finish();
        var initial = MicrowaveLinkService.links(level);
        var edge = graph.getEdges().getFirst();
        long topology = graph.getTopologyRevision();
        var revision = new AtomicLong();
        var sourceReads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            // This falls after A's cache check in the former implementation, but before B's.
            if (sourceReads.incrementAndGet() == 3) revision.set(1);
            return call.callRealMethod();
        }).when(graph).getNode(a);
        clearInvocations(graph, chunks, chunk);
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            cache.when(() -> RadioTerrainCache.revision(level)).thenAnswer(call -> revision.get());
            cache.when(() -> RadioTerrainCache.changedChunks(eq(level), anyLong(), anyLong()))
                    .thenReturn(Set.of(ChunkPos.asLong(3, 0)));
            var snapshot = MicrowaveLinkService.links(level);
            assertEquals(1, revision.get(), "Regression must actually interleave a cache arrival");
            assertEquals(initial, snapshot);
            assertEquals("ready", MicrowaveLinkService.status(level, a).state());
            assertEquals("ready", MicrowaveLinkService.status(level, b).state());
            assertEquals(List.of(edge), graph.getEdges());
            assertEquals(topology, graph.getTopologyRevision());
            for (var link : snapshot) {
                assertEquals(edge.getEffectiveBandwidthMbps(), link.capacityMbps());
                assertTrue(graph.getEdges().contains(edge));
            }
            cache.verifyNoInteractions();
            verify(graph, never()).setMicrowaveEdges(anyList());
            verifyNoInteractions(chunks, chunk);

            // The next tick applies the live revision atomically before traffic, not within reads.
            MicrowaveLinkService.tickUntil(level, System.nanoTime() - 1);
            assertTrue(graph.getEdges().isEmpty());
            var after = MicrowaveLinkService.links(level);
            assertEquals("pending", after.getFirst().state());
            assertEquals(0, after.getFirst().capacityMbps());
            assertEquals("ready", snapshot.getFirst().state(), "Returned snapshots are immutable");
        }
    }

    @Test
    void initialDiagnosticReadsDoNotCreateJobsOrMutateGraph() {
        graph = spy(graph);
        graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        dish(new BlockPos(200, 64, 0), MicrowaveConfig.DEFAULT);
        long topology = graph.getTopologyRevision();
        clearInvocations(graph, chunks, chunk);
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            assertEquals("pending", MicrowaveLinkService.status(level, a).state());
            var snapshot = MicrowaveLinkService.links(level);
            assertEquals(2, snapshot.size());
            assertEquals("pending", snapshot.getFirst().state());
            assertEquals("disabled", snapshot.getLast().state());
            assertEquals(topology, graph.getTopologyRevision());
            assertTrue(graph.getEdges().isEmpty());
            MicrowaveLinkService.unload(level);
            verify(graph, never()).setMicrowaveEdges(anyList()); // No service state was created by reads.
            cache.verifyNoInteractions();
            verifyNoInteractions(chunks, chunk);
        }
    }

    @Test
    void diagnosticCannotAdvertisePositiveCapacityWhenItsEdgeIsAbsent() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(100, 64, 0);
        pair(a, b);
        finish();
        graph.setMicrowaveEdges(List.of());
        long topology = graph.getTopologyRevision();
        assertEquals("pending", MicrowaveLinkService.status(level, a).state());
        assertEquals(0, MicrowaveLinkService.links(level).getFirst().capacityMbps());
        assertTrue(graph.getEdges().isEmpty());
        assertEquals(topology, graph.getTopologyRevision());
    }

    private net.neoforged.neoforge.event.level.ChunkEvent.Unload unloadEvent(int x, int z) {
        var unloading = mock(LevelChunk.class);
        when(unloading.getPos()).thenReturn(new ChunkPos(x, z));
        var event = mock(net.neoforged.neoforge.event.level.ChunkEvent.Unload.class);
        when(event.getLevel()).thenReturn(level);
        when(event.getChunk()).thenReturn(unloading);
        return event;
    }
}
