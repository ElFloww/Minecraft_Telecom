package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WiredBandwidthTest {
    private static final BlockPos A = new BlockPos(0, 64, 0);
    private static final BlockPos B = new BlockPos(10, 64, 0);
    private static final BlockPos C = new BlockPos(20, 64, 0);
    private static final BlockPos HUB = new BlockPos(30, 64, 0);
    private static final BlockPos SERVER = new BlockPos(40, 64, 0);
    private static final BlockPos CABLE = new BlockPos(35, 64, 0);
    private TelecomNetworkGraph graph;
    private ServerLevel level;

    @BeforeEach
    void setup() {
        graph = new TelecomNetworkGraph();
        level = mock(ServerLevel.class);
        when(level.players()).thenReturn(List.of());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        node(SERVER, NetworkNode.NodeType.SERVER);
    }

    @Test
    void asymmetricDemandsFillSharedTrunkWithoutWastingCapacity() {
        trunk(100);
        TrafficSession first = start(A, "a", 10, 10, false);
        TrafficSession second = start(B, "b", 90, 90, false);
        for (int tick = 0; tick < 10; tick++) {
            tick();
            assertEquals(10, first.getActualBandwidth());
            assertEquals(90, second.getActualBandwidth());
            assertEquals(100, graph.getTotalBandwidthDown());
            assertEquals(100, graph.getActualBlockUsageDown(CABLE));
        }
    }

    @Test
    void branchBottleneckRedistributesCapacityToOtherSessions() {
        trunk(100);
        graph.getNode(A).setCapacityDown(10);
        TrafficSession first = start(A, "a", 100, 100, false);
        TrafficSession second = start(B, "b", 100, 100, false);
        tick();
        assertEquals(10, first.getActualBandwidth());
        assertEquals(90, second.getActualBandwidth());
    }

    @Test
    void oneMegabitNeverDisappearsAndTwoSessionsAlternate() {
        trunk(1);
        TrafficSession first = start(A, "a", 1, 1, false);
        tick();
        assertEquals(1, first.getActualBandwidth());
        TrafficSession second = start(B, "b", 1, 1, false);
        int previous = -1;
        for (int tick = 0; tick < 20; tick++) {
            tick();
            assertEquals(1, first.getActualBandwidth() + second.getActualBandwidth());
            assertNotEquals(previous, first.getActualBandwidth());
            previous = first.getActualBandwidth();
        }
    }

    @Test
    void incomingLinksDoNotShareTheCapacityOfTheirCommonEndpoint() {
        node(A, NetworkNode.NodeType.PM);
        node(B, NetworkNode.NodeType.PM);
        fiber(A, SERVER, 10, List.of(A, SERVER));
        fiber(B, SERVER, 90, List.of(B, SERVER));
        TrafficSession first = start(A, "a", 100, 100, false);
        TrafficSession second = start(B, "b", 100, 100, false);
        tick();
        assertEquals(10, first.getActualBandwidth());
        assertEquals(90, second.getActualBandwidth());
        assertEquals(0, graph.getActualBlockCapacityMbps(SERVER));
        assertEquals(0, graph.getActualBlockUsageDown(SERVER));
        assertEquals(100, graph.getNode(SERVER).getCurrentUsageDown());
    }

    @Test
    void allNodesAndEvenMissingLegacyEndpointsAreExcludedFromPhysicalBudgets() {
        node(A, NetworkNode.NodeType.PM);
        node(B, NetworkNode.NodeType.PM);
        node(HUB, NetworkNode.NodeType.PM);
        fiber(A, SERVER, 100, List.of(HUB, C, CABLE));
        fiber(B, HUB, 1, List.of(HUB));
        fiber(C, SERVER, 1, List.of(C)); // Old edge whose endpoint node no longer exists.
        TrafficSession session = start(A, "a", 100, 100, false);
        tick();
        assertEquals(100, session.getActualBandwidth());
        assertEquals(0, graph.getActualBlockCapacityMbps(HUB));
        assertEquals(0, graph.getActualBlockCapacityMbps(C));
        assertEquals(0, graph.getActualBlockUsageDown(HUB));
    }

    @Test
    void duplicatePhysicalPositionAcrossSeveralPathEdgesCountsOnce() {
        node(A, NetworkNode.NodeType.PM);
        node(HUB, NetworkNode.NodeType.PM);
        NetworkEdge first = fiber(A, HUB, 100, List.of(CABLE, CABLE, HUB));
        NetworkEdge second = fiber(HUB, SERVER, 100, List.of(CABLE, SERVER));
        TrafficSession session = start(A, "a", 100, 100, false);
        tick();
        assertEquals(100, session.getActualBandwidth());
        assertEquals(100, first.getCurrentUsage());
        assertEquals(100, second.getCurrentUsage());
        assertEquals(100, graph.getActualBlockUsageDown(CABLE));
        assertEquals(100, graph.getNode(HUB).getCurrentUsageDown());
    }

    @Test
    void logicalAndPhysicalLinksShareDownloadPlusUploadRatherThanFullDuplex() {
        trunk(100);
        TrafficSession down = start(A, "a", 100, 100, false);
        TrafficSession up = start(B, "b", 100, 100, true);
        tick();
        assertEquals(50, down.getActualBandwidth());
        assertEquals(50, up.getActualBandwidth());
        assertEquals(50, graph.getActualBlockUsageDown(CABLE));
        assertEquals(50, graph.getActualBlockUsageUp(CABLE));

        graph.clearEdges();
        fiber(A, SERVER, 100, List.of(CABLE));
        fiber(B, SERVER, 100, List.of(CABLE));
        tick();
        assertEquals(50, down.getActualBandwidth());
        assertEquals(50, up.getActualBandwidth());
        assertEquals(100, graph.getActualBlockUsageDown(CABLE) + graph.getActualBlockUsageUp(CABLE));
    }

    @Test
    void nodeBudgetsAggregateSessionsButSeparateDownloadAndUpload() {
        node(A, NetworkNode.NodeType.PM);
        node(B, NetworkNode.NodeType.PM);
        node(C, NetworkNode.NodeType.PM);
        node(HUB, NetworkNode.NodeType.ROUTER);
        graph.getNode(HUB).setCapacityDown(100);
        graph.getNode(HUB).setCapacityUp(30);
        fiber(A, HUB, 1000, List.of());
        fiber(B, HUB, 1000, List.of());
        fiber(C, HUB, 1000, List.of());
        fiber(HUB, SERVER, 1000, List.of(CABLE));
        // Reserve the router device so random passive generation cannot affect this fixture.
        graph.startSpeedtest(HUB, "idle-router", 1, 1, 0, 0, 1000, false, null);
        TrafficSession first = start(A, "a", 100, 100, false);
        TrafficSession second = start(B, "b", 100, 100, false);
        TrafficSession up = start(C, "c", 100, 100, true);
        assertEquals(new TelecomNetworkGraph.PathStats(1, 100, 30), graph.calculatePathStats(A, SERVER));
        tick();
        assertEquals(50, first.getActualBandwidth());
        assertEquals(50, second.getActualBandwidth());
        assertEquals(30, up.getActualBandwidth());
        assertEquals(100, graph.getNode(HUB).getCurrentUsageDown());
        assertEquals(30, graph.getNode(HUB).getCurrentUsageUp());
    }

    @Test
    void routerAndAntennaProfilesLimitWiredCollectWithoutChangingRadioAggregation() {
        node(A, NetworkNode.NodeType.ROUTER);
        fiber(A, SERVER, 10_000, List.of());
        TrafficSession router = start(A, "router", 10_000, 10_000, false);
        tick();
        assertEquals(1000, router.getActualBandwidth());
        graph.getNode(A).setCapacityDown(0);
        tick();
        assertEquals(0, router.getActualBandwidth());
        node(B, NetworkNode.NodeType.ANTENNA);
        graph.addEdge(new NetworkEdge(B, SERVER, 1_000_000, 1, NetworkEdge.EdgeType.BIG_FIBER, List.of()));
        TrafficSession mobile = start(B, "mobile", 1_000_000, 1_000_000, false);
        tick();
        assertEquals(1_000_000, mobile.getActualBandwidth());
    }

    @Test
    void copperAttenuationIsPerSegmentAndSharedCapacityUsesMinimumEffectiveEdge() {
        node(A, NetworkNode.NodeType.PM);
        node(B, NetworkNode.NodeType.PM);
        node(HUB, NetworkNode.NodeType.SR);
        copper(A, HUB, 100, List.of(A.east()));
        copper(HUB, SERVER, 100, List.of(CABLE));
        assertEquals(new TelecomNetworkGraph.PathStats(41, 800), graph.calculatePathStats(A, SERVER));
        assertEquals(800, graph.getActualBlockCapacityMbps(CABLE));
        copper(B, SERVER, 200, List.of(CABLE));
        assertEquals(600, graph.getActualBlockCapacityMbps(CABLE));
        var stats = graph.calculatePathStats(A, SERVER);
        var option = graph.getSpeedtestServers(A, 7).getFirst();
        assertEquals(600, stats.bandwidthMbps());
        assertEquals(stats.bandwidthMbps(), option.bandwidthMbps());
        assertEquals(stats.pingMs() + 7, option.estimatedPingMs());
        TrafficSession first = start(A, "a", 1000, 1000, false);
        TrafficSession second = start(B, "b", 1000, 1000, false);
        tick();
        assertEquals(300, first.getActualBandwidth());
        assertEquals(300, second.getActualBandwidth());
        assertEquals(600, graph.getActualBlockUsageDown(CABLE));
    }

    @Test
    void customFiberNominalIsUsedInPathCatalogueAndActualTraffic() {
        node(A, NetworkNode.NodeType.PM);
        NetworkEdge edge = fiber(A, SERVER, 100, List.of(CABLE));
        assertEquals(100, graph.calculatePathStats(A, SERVER).bandwidthMbps());
        assertEquals(100, graph.getSpeedtestServers(A, 0).getFirst().bandwidthMbps());
        assertEquals(100, graph.getActualBlockCapacityMbps(CABLE));
        TrafficSession session = start(A, "a", 1000, 1000, false);
        tick();
        assertEquals(100, session.getActualBandwidth());
        assertEquals(100, edge.getCurrentUsage());
    }

    @Test
    void recalculationTickClearsAllUsageWithoutChangingSessionPhaseOrHistory() {
        trunk(100);
        TrafficSession session = start(A, "a", 100, 100, false);
        session.setFrequenciesMask(1);
        tick();
        int elapsed = session.getTicksElapsed();
        int finalDown = session.getFinalDownBw();
        assertEquals(100, graph.getAntennaUtilization(A).values().stream().mapToInt(TelecomNetworkGraph.AntennaFreqStats::actualMbps).sum());
        graph.markForRecalculation();
        try (MockedStatic<NetworkTracer> tracer = mockStatic(NetworkTracer.class)) {
            tick();
            tracer.verify(() -> NetworkTracer.recalculateNetwork(level));
        }
        assertEquals(0, graph.getTotalBandwidthDown());
        assertEquals(0, graph.getTotalBandwidthUp());
        assertEquals(0, graph.getActualBlockUsageDown(CABLE));
        assertEquals(0, graph.getActualBlockUsageUp(CABLE));
        assertEquals(0, session.getActualBandwidth());
        assertEquals(0, graph.getAntennaUtilization(A).values().stream().mapToInt(TelecomNetworkGraph.AntennaFreqStats::actualMbps).sum());
        for (NetworkEdge edge : graph.getEdges()) assertEquals(0, edge.getCurrentUsage());
        for (NetworkNode node : graph.getNodes()) assertEquals(0, node.getCurrentUsageDown());
        assertEquals(elapsed, session.getTicksElapsed());
        assertEquals(finalDown, session.getFinalDownBw());
        assertEquals(100, session.getTargetDownBw());
        assertEquals(100, session.getTargetUpBw());
    }

    @Test
    void clearInstantaneousAndFailurePreserveBothResultsAndTargets() {
        TrafficSession session = new TrafficSession(A, SERVER, "a", 100, 80, 2, false, "test");
        session.tick();
        session.tick();
        session.setActualBandwidth(90);
        session.clearCurrentBandwidth();
        assertEquals(0, session.getActualBandwidth());
        assertEquals(90, session.getFinalDownBw());
        session.tick();
        session.tick();
        session.setActualBandwidth(70);
        session.fail("route_lost");
        assertEquals(0, session.getActualBandwidth());
        assertEquals(90, session.getFinalDownBw());
        assertEquals(70, session.getFinalUpBw());
        assertEquals(100, session.getTargetDownBw());
        assertEquals(80, session.getTargetUpBw());
    }

    @Test
    void packetLatencyUsesEffectiveCapacityDoesNotMutateCountersAndHandlesZero() {
        node(A, NetworkNode.NodeType.PM);
        graph.getNode(A).setIpAddress("source");
        graph.getNode(SERVER).setIpAddress("server");
        NetworkEdge edge = copper(A, SERVER, 495, List.of(CABLE));
        edge.setCurrentUsage(5);
        edge.setCurrentUsageDown(5);
        assertEquals(99, graph.routePacket(new Packet("source", "server", 10, "test")));
        assertEquals(5, edge.getCurrentUsage());
        assertEquals(5, edge.getCurrentUsageDown());
        assertEquals(Integer.MAX_VALUE, graph.routePacket(new Packet("source", "server", Integer.MAX_VALUE, "test")));
        graph.clearEdges();
        fiber(A, SERVER, 0, List.of());
        assertEquals(-1, graph.routePacket(new Packet("source", "server", 1, "test")));
    }

    private void trunk(int capacity) {
        node(A, NetworkNode.NodeType.PM);
        node(B, NetworkNode.NodeType.PM);
        node(HUB, NetworkNode.NodeType.NRO);
        fiber(A, HUB, 1000, List.of());
        fiber(B, HUB, 1000, List.of());
        fiber(HUB, SERVER, capacity, List.of(CABLE, SERVER));
    }

    private void node(BlockPos pos, NetworkNode.NodeType type) {
        graph.addNode(new NetworkNode(pos, type));
    }

    private NetworkEdge fiber(BlockPos from, BlockPos to, int capacity, List<BlockPos> blocks) {
        NetworkEdge edge = new NetworkEdge(from, to, capacity, 1, NetworkEdge.EdgeType.FIBER, blocks);
        graph.addEdge(edge);
        return edge;
    }

    private NetworkEdge copper(BlockPos from, BlockPos to, int length, List<BlockPos> blocks) {
        NetworkEdge edge = new NetworkEdge(from, to, 1000, length, NetworkEdge.EdgeType.COPPER, blocks);
        graph.addEdge(edge);
        return edge;
    }

    private TrafficSession start(BlockPos source, String ip, int down, int up, boolean upload) {
        // These fixtures exercise exact allocator budgets, not the manual speedtest load profile.
        var result = graph.startSpeedtest(source, ip, down, up, 0, 0, 1000, true, null, Long.toString(SERVER.asLong()));
        assertTrue(result.accepted(), result.error());
        TrafficSession session = result.session();
        for (int i = 0; i < (upload ? 1060 : 60); i++) session.tick();
        return session;
    }

    private void tick() {
        graph.tickTraffic(level);
        for (NetworkEdge edge : graph.getEdges()) {
            assertEquals(edge.getCurrentUsageDown() + edge.getCurrentUsageUp(), edge.getCurrentUsage());
            assertTrue(edge.getCurrentUsage() <= edge.getEffectiveBandwidthMbps());
            for (BlockPos pos : edge.getPathBlocks()) {
                assertTrue(graph.getActualBlockUsageDown(pos) + graph.getActualBlockUsageUp(pos)
                        <= graph.getActualBlockCapacityMbps(pos));
            }
        }
        for (NetworkNode node : graph.getNodes()) {
            assertTrue(node.getCurrentUsageDown() <= node.getCapacityDown());
            assertTrue(node.getCurrentUsageUp() <= node.getCapacityUp());
        }
    }
}
