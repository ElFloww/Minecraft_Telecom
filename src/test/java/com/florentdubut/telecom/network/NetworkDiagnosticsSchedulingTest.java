package com.florentdubut.telecom.network;

import com.florentdubut.telecom.event.ServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.florentdubut.telecom.network.NetworkDiagnostics.Cause.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkDiagnosticsSchedulingTest {
    private TelecomNetworkGraph graph;
    private NetworkDiagnostics diagnostics;
    private ServerLevel level;

    @BeforeEach
    void setup() {
        graph = new TelecomNetworkGraph();
        diagnostics = graph.getDiagnostics();
        diagnostics.start();
        level = mock(ServerLevel.class);
        when(level.players()).thenReturn(List.of());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
    }

    @Test
    void hundredTickLoginTimerFiresOnTick101AndRepeatedLoginDoesNotPostponeIt() {
        try (var graphs = mockStatic(TelecomNetworkGraph.class);
             var microwave = mockStatic(MicrowaveLinkService.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.level()).thenReturn(level);
            ServerEvents.onPlayerLogin(new PlayerEvent.PlayerLoggedInEvent(player));
            for (int i = 0; i < 50; i++) graph.tickTraffic(level);
            ServerEvents.onPlayerLogin(new PlayerEvent.PlayerLoggedInEvent(player));
            for (int i = 50; i < 100; i++) graph.tickTraffic(level);
            assertTrue(diagnostics.recalculations().isEmpty());
            assertEquals(0, diagnostics.skippedTrafficTicks());

            graph.tickTraffic(level);
            var result = diagnostics.recalculations().getFirst();
            assertEquals(Map.of(PLAYER_LOGIN, 2L), result.causes());
            assertEquals(101, result.waitTicks());
            assertTrue(result.success());
            assertEquals(1, diagnostics.skippedTrafficTicks());
            for (int i = 0; i < 101; i++) graph.tickTraffic(level);
            assertEquals(1, diagnostics.recalculations().size());
        }
    }

    @Test
    void directRecalculationConsumesNeitherImmediateNorDelayedRequests() {
        try (var graphs = mockStatic(TelecomNetworkGraph.class);
             var microwave = mockStatic(MicrowaveLinkService.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            NetworkTracer.scheduleRecalculation(level, CABLE_PLACE);
            NetworkTracer.scheduleRecalculation(level, CABLE_PLACE);
            graph.markForRecalculation(NODE_LOAD);
            graph.scheduleDelayedRecalculation(2, PLAYER_LOGIN);

            NetworkTracer.recalculateNetwork(level);
            assertEquals(Map.of(EXPLICIT_RECALCULATION, 1L), diagnostics.recalculations().getFirst().causes());
            assertEquals(0, diagnostics.skippedTrafficTicks());
            graph.tickTraffic(level);
            assertEquals(Map.of(CABLE_PLACE, 2L, NODE_LOAD, 1L), diagnostics.recalculations().getLast().causes());
            assertEquals(1, diagnostics.recalculations().getLast().waitTicks());
            graph.tickTraffic(level);
            assertEquals(2, diagnostics.recalculations().size());
            graph.tickTraffic(level);
            assertEquals(3, diagnostics.recalculations().size());
            assertEquals(Map.of(PLAYER_LOGIN, 1L), diagnostics.recalculations().getLast().causes());
            assertEquals(3, diagnostics.recalculations().getLast().waitTicks());
            assertEquals(2, diagnostics.skippedTrafficTicks());
            assertEquals(Map.of(EXPLICIT_RECALCULATION, 1L, CABLE_PLACE, 2L, NODE_LOAD, 1L,
                    PLAYER_LOGIN, 1L), diagnostics.requests());
        }
    }

    @Test
    void requestsRaisedByChunkLoadingDuringTraceSurviveForNextTick() {
        graph.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.NRO));
        AtomicBoolean requested = new AtomicBoolean();
        when(level.getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(true))).thenAnswer(invocation -> {
            if (requested.compareAndSet(false, true)) graph.markForRecalculation(NODE_LOAD);
            return null;
        });
        try (var graphs = mockStatic(TelecomNetworkGraph.class);
             var microwave = mockStatic(MicrowaveLinkService.class);
             var tracer = mockStatic(NetworkTracer.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            tracer.when(() -> NetworkTracer.recalculateNetwork(level)).thenCallRealMethod();
            graph.markForRecalculation(CABLE_PLACE);
            graph.tickTraffic(level);
            assertTrue(requested.get());
            assertEquals(Map.of(CABLE_PLACE, 1L), diagnostics.recalculations().getFirst().causes());

            graph.tickTraffic(level);
            assertEquals(2, diagnostics.recalculations().size());
            assertEquals(Map.of(NODE_LOAD, 1L), diagnostics.recalculations().getLast().causes());
            assertEquals(1, diagnostics.recalculations().getLast().waitTicks());
            graph.tickTraffic(level);
            tracer.verify(() -> NetworkTracer.recalculateNetwork(level), times(2));
            assertEquals(2, diagnostics.skippedTrafficTicks());
        }
    }

    @Test
    void tracerCountsTraversalReadsChunksAndCopiedReferences() {
        graph.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.NRO));
        graph.addNode(new NetworkNode(BlockPos.ZERO.east(2), NetworkNode.NodeType.SERVER));
        BlockState cable = mock(BlockState.class);
        when(level.getBlockState(BlockPos.ZERO.east())).thenReturn(cable);
        try (var graphs = mockStatic(TelecomNetworkGraph.class);
             var tracer = mockStatic(NetworkTracer.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            tracer.when(() -> NetworkTracer.recalculateNetwork(level)).thenCallRealMethod();
            tracer.when(() -> NetworkTracer.getCableType(cable)).thenReturn(NetworkEdge.EdgeType.FIBER);
            tracer.when(() -> NetworkTracer.isCableCompatibleWithNodes(eq(NetworkEdge.EdgeType.FIBER), any(), any()))
                    .thenReturn(true);
            NetworkTracer.recalculateNetwork(level);
        }
        var result = diagnostics.recalculations().getFirst();
        assertTrue(result.success());
        assertEquals(2, result.nodes());
        assertEquals(0, result.edgesBefore());
        assertEquals(1, result.edgesAfter());
        // Four wired types per origin, plus one fiber cable step in each direction.
        assertEquals(10, result.traceSteps());
        assertEquals(58, result.blockReads());
        assertEquals(60, result.chunkRequests());
        assertEquals(4, result.pathCopies());
        assertEquals(2, result.copiedPathReferences());
        assertEquals(List.of(BlockPos.ZERO.east()), graph.getEdges().getFirst().getPathBlocks());
        verify(level, times(58)).getBlockState(any(BlockPos.class));
        verify(level, times(60)).getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(true));
    }

    @Test
    void tracerRecordsFailureInFinallyWithoutPublishingNewEdges() {
        graph.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.NRO));
        NetworkEdge previous = new NetworkEdge(BlockPos.ZERO, BlockPos.ZERO.east(), 100, 1,
                NetworkEdge.EdgeType.FIBER, List.of());
        graph.addEdge(previous);
        IllegalStateException failure = new IllegalStateException("chunk unavailable");
        when(level.getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(true))).thenThrow(failure);
        try (var graphs = mockStatic(TelecomNetworkGraph.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> NetworkTracer.recalculateNetwork(level)));
        }
        var result = diagnostics.recalculations().getFirst();
        assertFalse(result.success());
        assertEquals(-1, result.publicationNanos());
        assertEquals(1, result.edgesBefore());
        assertEquals(1, result.edgesAfter());
        assertEquals(1, result.chunkRequests());
        assertEquals(0, result.blockReads());
        assertEquals(List.of(previous), graph.getEdges());
        assertTrue(diagnostics.report().getFirst().contains("completed=0; failed=1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scheduledTraceSkipsExactlyOneTrafficTickWithoutAdvancingPhase(boolean enabled) {
        if (!enabled) diagnostics.stop();
        BlockPos source = BlockPos.ZERO;
        BlockPos server = source.east(10);
        graph.addNode(new NetworkNode(source, NetworkNode.NodeType.ANTENNA));
        graph.addNode(new NetworkNode(server, NetworkNode.NodeType.SERVER));
        graph.addEdge(new NetworkEdge(source, server, 100, 10, NetworkEdge.EdgeType.FIBER, List.of()));
        var session = graph.startSpeedtest(source, "client", 100, 100, 0, 0, 300, false, null, "").session();
        assertNotNull(session);
        for (int i = 0; i < 59; i++) session.tick();
        assertEquals(TrafficSession.SessionState.PING, session.getState());
        assertEquals(59, session.getTicksElapsed());

        try (var microwave = mockStatic(MicrowaveLinkService.class);
             var tracer = mockStatic(NetworkTracer.class)) {
            graph.markForRecalculation(CABLE_REMOVE);
            graph.tickTraffic(level);
            tracer.verify(() -> NetworkTracer.recalculateNetwork(level));
            assertEquals(TrafficSession.SessionState.PING, session.getState());
            assertEquals(59, session.getTicksElapsed());
            assertEquals(0, graph.getTotalBandwidthDown());
            assertEquals(enabled ? 1 : 0, diagnostics.skippedTrafficTicks());

            graph.tickTraffic(level);
            tracer.verify(() -> NetworkTracer.recalculateNetwork(level), times(1));
            assertEquals(TrafficSession.SessionState.DOWNLOAD, session.getState());
            assertEquals(0, session.getTicksElapsed());
            graph.tickTraffic(level);
            assertEquals(1, session.getTicksElapsed());
            assertSame(session, graph.getSessionByDeviceId(session.getDeviceId()));
        }
    }
}
