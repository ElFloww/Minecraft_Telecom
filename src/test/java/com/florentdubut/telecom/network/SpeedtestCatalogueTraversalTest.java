package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeedtestCatalogueTraversalTest {
    private static final BlockPos SOURCE = BlockPos.ZERO;

    @Test
    void catalogueMatchesPathStatsAcrossMixedLinksInBothDirections() {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        graph.addNode(new NetworkNode(SOURCE, NetworkNode.NodeType.ROUTER));
        List<BlockPos> positions = new ArrayList<>(List.of(SOURCE));
        NetworkEdge.EdgeType[] types = {NetworkEdge.EdgeType.COPPER, NetworkEdge.EdgeType.FIBER,
                NetworkEdge.EdgeType.COPPER, NetworkEdge.EdgeType.MEDIUM_FIBER,
                NetworkEdge.EdgeType.BIG_FIBER, NetworkEdge.EdgeType.COPPER};
        int[] lengths = {100, 19, 125, 31, 47, 400};
        for (int i = 0; i < types.length; i++) {
            BlockPos next = new BlockPos(i + 1, 64, 0);
            graph.addNode(new NetworkNode(next, NetworkNode.NodeType.SERVER));
            graph.addEdge(edge(positions.getLast(), next, lengths[i], types[i]));
            positions.add(next);
        }
        graph.addNode(new NetworkNode(new BlockPos(100, 64, 0), NetworkNode.NodeType.SERVER));

        assertEquals(new TelecomNetworkGraph.PathStats(48, 750), graph.calculatePathStats(SOURCE, positions.get(5)));
        assertEquals(new TelecomNetworkGraph.PathStats(128, 200), graph.calculatePathStats(SOURCE, positions.getLast()));
        for (BlockPos source : positions) {
            List<SpeedtestServerOption> options = graph.getSpeedtestServers(source, 37);
            assertEquals(7, options.size());
            for (SpeedtestServerOption option : options) {
                var expected = graph.calculatePathStats(source, BlockPos.of(Long.parseLong(option.id())));
                assertEquals(expected != null, option.available());
                assertEquals(expected == null ? -1 : expected.pingMs() + 37, option.estimatedPingMs());
                assertEquals(expected == null ? 0 : expected.bandwidthMbps(), option.bandwidthMbps());
                assertEquals(expected == null ? "server_unavailable" : "", option.reason());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void equalHopPathsFollowEdgeInsertionOrderIncludingCyclesAndParallelEdges(boolean copperFirst) {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        BlockPos copper = new BlockPos(1, 0, 0);
        BlockPos fiber = new BlockPos(2, 0, 0);
        BlockPos server = new BlockPos(3, 0, 0);
        graph.addNode(new NetworkNode(SOURCE, NetworkNode.NodeType.ROUTER));
        graph.addNode(new NetworkNode(copper, NetworkNode.NodeType.PM));
        graph.addNode(new NetworkNode(fiber, NetworkNode.NodeType.PM));
        graph.addNode(new NetworkNode(server, NetworkNode.NodeType.SERVER));
        // This fixture isolates cable selection rather than router throughput.
        graph.getNode(SOURCE).setCapacityDown(10_000);
        graph.getNode(SOURCE).setCapacityUp(10_000);
        NetworkEdge copperEdge = edge(copper, SOURCE, 10, NetworkEdge.EdgeType.COPPER);
        NetworkEdge fiberEdge = edge(SOURCE, fiber, 1, NetworkEdge.EdgeType.FIBER);
        graph.addEdge(copperFirst ? copperEdge : fiberEdge);
        graph.addEdge(copperFirst ? fiberEdge : copperEdge);
        graph.addEdge(edge(SOURCE, SOURCE, 999, NetworkEdge.EdgeType.COPPER));
        graph.addEdge(edge(SOURCE, copper, 1000, NetworkEdge.EdgeType.COPPER));
        graph.addEdge(edge(server, fiber, 1, NetworkEdge.EdgeType.FIBER));
        graph.addEdge(edge(copper, server, 10, NetworkEdge.EdgeType.COPPER));
        graph.addEdge(edge(copper, fiber, 1, NetworkEdge.EdgeType.BIG_FIBER));
        var expected = graph.calculatePathStats(SOURCE, server);
        assertEquals(copperFirst ? 5 : 1, expected.pingMs());
        assertEquals(copperFirst ? 980 : 10000, expected.bandwidthMbps());
        var option = graph.getSpeedtestServers(SOURCE, 0).getFirst();
        assertEquals(expected.pingMs(), option.estimatedPingMs());
        assertEquals(expected.bandwidthMbps(), option.bandwidthMbps());
    }

    @Test
    void thousandsOfIsolatedServersUseOneTraversalAndNodeLimitIsCheckedBeforeWork() {
        TelecomNetworkGraph graph = spy(new TelecomNetworkGraph());
        graph.addNode(new NetworkNode(SOURCE, NetworkNode.NodeType.ROUTER));
        for (int i = 1; i < 8192; i++) {
            graph.addNode(new NetworkNode(new BlockPos(i, 0, 0), NetworkNode.NodeType.SERVER));
        }
        NetworkEdge connected = spy(edge(SOURCE, new BlockPos(1, 0, 0), 20, NetworkEdge.EdgeType.FIBER));
        graph.addEdge(connected);
        List<SpeedtestServerOption> options = graph.getSpeedtestServers(SOURCE, 0);
        assertEquals(128, options.size());
        assertTrue(options.getFirst().available());
        assertTrue(options.subList(1, options.size()).stream().allMatch(option -> !option.available()
                && option.estimatedPingMs() == -1 && option.bandwidthMbps() == 0 && option.reason().equals("server_unavailable")));
        verify(graph, never()).calculatePathStats(any(), any());
        verify(connected, times(1)).getLength();
        verify(connected, atMost(5)).getNodeA();
        verify(connected, atMost(4)).getNodeB();

        graph.addNode(new NetworkNode(new BlockPos(8192, 0, 0), NetworkNode.NodeType.SERVER));
        clearInvocations(connected);
        assertThrows(TelecomNetworkGraph.SpeedtestCatalogueLimitException.class, () -> graph.getSpeedtestServers(SOURCE, 0));
        assertThrows(TelecomNetworkGraph.SpeedtestCatalogueLimitException.class, () -> graph.getSpeedtestServers(null, 0));
        verifyNoInteractions(connected);
        verify(graph, never()).calculatePathStats(any(), any());
    }

    @Test
    void exactEdgeLimitIsAcceptedAndOverflowIsRejectedBeforeTraversal() {
        TelecomNetworkGraph graph = spy(new TelecomNetworkGraph());
        BlockPos server = new BlockPos(1, 0, 0);
        graph.addNode(new NetworkNode(SOURCE, NetworkNode.NodeType.ROUTER));
        graph.addNode(new NetworkNode(server, NetworkNode.NodeType.SERVER));
        NetworkEdge repeated = spy(edge(SOURCE, server, 20, NetworkEdge.EdgeType.FIBER));
        for (int i = 0; i < 16384; i++) graph.addEdge(repeated);
        var option = graph.getSpeedtestServers(SOURCE, 0).getFirst();
        assertTrue(option.available());
        assertEquals(2, option.estimatedPingMs());
        verify(repeated, times(1)).getLength();
        verify(graph, never()).calculatePathStats(any(), any());

        graph.addEdge(repeated);
        clearInvocations(repeated);
        assertThrows(TelecomNetworkGraph.SpeedtestCatalogueLimitException.class, () -> graph.getSpeedtestServers(SOURCE, 0));
        verifyNoInteractions(repeated);
        verify(graph, never()).calculatePathStats(any(), any());
    }

    @Test
    void legacyWrapperOnlyMentionsServerWhenNoDestinationIsReachable() {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        graph.addNode(new NetworkNode(SOURCE, NetworkNode.NodeType.ROUTER));
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        try (MockedStatic<PacketDistributor> packets = mockStatic(PacketDistributor.class)) {
            graph.startSpeedtest(SOURCE, "ip", 100, 100, 0, 0, 2, false, player);
            verify(player).sendSystemMessage(argThat(message -> message.getString().equals(
                    "Failed to start Speedtest: No complete path to a Server was found.")));
        }
    }

    private static NetworkEdge edge(BlockPos a, BlockPos b, int length, NetworkEdge.EdgeType type) {
        return new NetworkEdge(a, b, type.nominalBandwidthMbps(), length, type, List.of());
    }
}
