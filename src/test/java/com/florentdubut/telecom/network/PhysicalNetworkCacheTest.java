package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhysicalNetworkCacheTest {
    private static final BlockPos SOURCE = BlockPos.ZERO;
    private static final BlockPos SERVER = new BlockPos(10, 0, 0);
    private static final BlockPos CABLE = new BlockPos(5, 0, 0);
    private TelecomNetworkGraph graph;
    private ServerLevel level;

    @BeforeEach
    void setup() {
        graph = new TelecomNetworkGraph();
        graph.addNode(new NetworkNode(SOURCE, NetworkNode.NodeType.ANTENNA));
        graph.addNode(new NetworkNode(SERVER, NetworkNode.NodeType.SERVER));
        level = mock(ServerLevel.class);
        when(level.players()).thenReturn(List.of());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
    }

    @Test
    void idleTicksNeverBuildPhysicalModelAndWarmPathsCatalogueAndStartsReuseIt() {
        NetworkEdge edge = spy(edge(List.of(CABLE)));
        graph.addEdge(edge);
        for (int i = 0; i < 100; i++) graph.tickTraffic(level);
        verify(edge, never()).getPathBlocks();
        assertEquals(100, graph.calculatePathStats(SOURCE, SERVER).bandwidthMbps());
        clearInvocations(edge);
        for (int i = 0; i < 100; i++) {
            assertEquals(100, graph.calculatePathStats(SOURCE, SERVER).bandwidthMbps());
            assertEquals(100, graph.getActualBlockCapacityMbps(CABLE));
            assertEquals(100, graph.getSpeedtestServers(SOURCE, 0).getFirst().bandwidthMbps());
        }
        var session = graph.startSpeedtest(SOURCE, "one", 100, 100, 0, 0, 1000, false, null, "").session();
        assertNotNull(session);
        for (int i = 0; i < 60; i++) session.tick();
        // No path-block lookup during warm stats, catalogue, best-server search or traffic.
        graph.tickTraffic(level);
        verify(edge, never()).getPathBlocks();
        assertEquals(session.getRequestedBandwidth(100), session.getActualBandwidth());
    }

    @Test
    void nodeAndEdgeTopologyMutationsInvalidateButNodeCapacityUpdatesStayLive() {
        NetworkEdge edge = edge(List.of(CABLE));
        graph.addEdge(edge);
        assertEquals(100, graph.getActualBlockCapacityMbps(CABLE));
        graph.addNode(new NetworkNode(CABLE, NetworkNode.NodeType.PM));
        assertEquals(0, graph.getActualBlockCapacityMbps(CABLE));
        graph.removeNode(CABLE);
        assertEquals(100, graph.getActualBlockCapacityMbps(CABLE));
        graph.getNode(SOURCE).setCapacityUp(45);
        assertEquals(45, graph.calculatePathStats(SOURCE, SERVER).uploadBandwidthMbps());
        graph.removeEdgeBetween(SOURCE, SERVER);
        assertEquals(0, graph.getActualBlockCapacityMbps(CABLE));
        graph.setEdges(List.of(edge));
        assertEquals(100, graph.getActualBlockCapacityMbps(CABLE));
        graph.clearEdges();
        assertEquals(0, graph.getActualBlockCapacityMbps(CABLE));
        assertThrows(UnsupportedOperationException.class, () -> graph.getEdges().add(edge));
        assertThrows(UnsupportedOperationException.class, () -> graph.getNodes().clear());
    }

    @Test
    void totalPathReferencesIncludingDuplicatesAreBoundedAndFailedCacheIsStable() {
        NetworkEdge full = spy(edge(Collections.nCopies(TelecomNetworkGraph.MAX_PHYSICAL_PATH_REFERENCES, CABLE)));
        graph.addEdge(full);
        assertEquals(100, graph.getActualBlockCapacityMbps(CABLE));
        assertEquals(100, graph.getSpeedtestServers(SOURCE, 0).getFirst().bandwidthMbps());
        NetworkEdge overflow = edge(List.of(CABLE));
        graph.addEdge(overflow);
        clearInvocations(full);
        for (int i = 0; i < 100; i++) {
            assertNull(graph.calculatePathStats(SOURCE, SERVER));
            assertEquals(0, graph.getActualBlockCapacityMbps(CABLE));
            assertThrows(TelecomNetworkGraph.SpeedtestCatalogueLimitException.class, () -> graph.getSpeedtestServers(SOURCE, 0));
            assertEquals("network_limit", graph.startSpeedtest(SOURCE, "one", 100, 100, 0, 0, 2, false, null, "").error());
            assertEquals("network_limit", graph.startSpeedtest(SOURCE, "one", 100, 100, 0, 0, 2, false, null,
                    Long.toString(SERVER.asLong())).error());
        }
        verify(full, times(1)).getPathBlocks();
        verify(full, never()).getEffectiveBandwidthMbps();
        graph.setEdges(List.of(full));
        assertEquals(100, graph.getActualBlockCapacityMbps(CABLE));
        assertNotNull(graph.calculatePathStats(SOURCE, SERVER));
    }

    @Test
    void overQuotaDuringTrafficFailsExplicitlyAndClearsInstantaneousNotResults() {
        graph.addEdge(edge(List.of(CABLE)));
        // Keep radio enabled but above the 100-Mbps wired ceiling under test.
        int mask = 1 << TelecomFrequency.G5_3500.ordinal();
        graph.getNode(SOURCE).setFrequenciesMask(mask);
        var session = graph.startSpeedtest(SOURCE, "one", 100, 100, 0, mask, 1000, false, null, "").session();
        assertNotNull(session);
        for (int i = 0; i < 60; i++) session.tick();
        graph.tickTraffic(level);
        int allocated = session.getActualBandwidth();
        assertTrue(allocated > 0);
        assertEquals(session.getRequestedBandwidth(100), allocated);
        assertEquals(allocated, graph.getAntennaUtilization(SOURCE).get(TelecomFrequency.G5_3500).actualMbps());
        graph.addEdge(edge(Collections.nCopies(TelecomNetworkGraph.MAX_PHYSICAL_PATH_REFERENCES, CABLE)));
        assertDoesNotThrow(() -> graph.tickTraffic(level));
        assertEquals("network_limit", session.getFailureReason());
        assertEquals("network_limit", graph.getLastResultByDeviceId(session.getDeviceId()).errorCode());
        assertEquals(0, session.getActualBandwidth());
        assertEquals(allocated, session.getFinalDownBw());
        assertEquals(0, graph.getTotalBandwidthDown());
        assertEquals(0, graph.getActualBlockUsageDown(CABLE));
        assertEquals(0, graph.getAntennaUtilization(SOURCE).get(TelecomFrequency.G5_3500).actualMbps());
        assertNull(graph.getSessionByDeviceId(session.getDeviceId()));
    }

    @Test
    void cachedGeometryDoesNotFollowMutablePositionsOrCallerLists() {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(5, 0, 0);
        List<BlockPos> blocks = new java.util.ArrayList<>(List.of(mutable));
        graph.addEdge(edge(blocks));
        assertEquals(100, graph.getActualBlockCapacityMbps(CABLE));
        blocks.clear();
        mutable.set(6, 0, 0);
        graph.addNode(new NetworkNode(new BlockPos(20, 0, 0), NetworkNode.NodeType.PM));
        assertEquals(100, graph.getActualBlockCapacityMbps(CABLE));
        assertEquals(0, graph.getActualBlockCapacityMbps(mutable));
    }

    @Test
    void aggregateAllocationReferencesAreDeduplicatedBoundedAndOnlyOverflowSessionFails() {
        List<BlockPos> physical = sharedLargePath();
        TrafficSession first = startTraffic("first", false);
        TrafficSession second = startTraffic("second", true);
        TrafficSession overflow = startTraffic("overflow", false);
        overflow.setActualBandwidth(37);
        // Keep manual failure persistence coverage, but exercise quota saturation after warmup.
        for (int i = 0; i < 20; i++) { first.tick(); second.tick(); }
        graph.setDirty(false);

        // Each admitted flow has one edge, two directional node budgets and the unique cable positions.
        assertEquals(TelecomNetworkGraph.MAX_ALLOCATION_RESOURCE_REFERENCES, 2 * (physical.size() + 3));
        for (int tick = 0; tick < 2; tick++) {
            graph.tickTraffic(level);
            assertSame(first, graph.getSessionByDeviceId(first.getDeviceId()));
            assertSame(second, graph.getSessionByDeviceId(second.getDeviceId()));
            assertEquals(50, first.getActualBandwidth());
            assertEquals(50, second.getActualBandwidth());
            assertEquals(50, graph.getTotalBandwidthDown());
            assertEquals(50, graph.getTotalBandwidthUp());
            NetworkEdge edge = graph.getEdges().getFirst();
            assertEquals(100, edge.getCurrentUsage());
            assertEquals(edge.getCurrentUsageDown() + edge.getCurrentUsageUp(), edge.getCurrentUsage());
            assertTrue(edge.getCurrentUsage() <= edge.getEffectiveBandwidthMbps());
            for (BlockPos pos : physical) {
                assertEquals(50, graph.getActualBlockUsageDown(pos));
                assertEquals(50, graph.getActualBlockUsageUp(pos));
                assertEquals(100, graph.getActualBlockCapacityMbps(pos));
            }
            for (NetworkNode node : graph.getNodes()) {
                assertEquals(50, node.getCurrentUsageDown());
                assertEquals(50, node.getCurrentUsageUp());
                assertTrue(node.getCurrentUsageDown() <= node.getCapacityDown());
                assertTrue(node.getCurrentUsageUp() <= node.getCapacityUp());
            }
            assertFalse(graph.isDirty());
        }
        assertNull(graph.getSessionByDeviceId(overflow.getDeviceId()));
        assertEquals(TrafficSession.SessionState.FAILED, overflow.getState());
        assertEquals("network_limit", overflow.getFailureReason());
        assertEquals(0, overflow.getActualBandwidth());
        assertEquals(37, overflow.getFinalDownBw());
        var terminal = graph.getLastResultByDeviceId(overflow.getDeviceId());
        assertNotNull(terminal);
        assertEquals("FAILED", terminal.state());
        assertEquals("network_limit", terminal.errorCode());
        assertEquals(37, terminal.downloadBandwidth());
    }

    @Test
    void zeroDemandAndPingDoNotConsumeAllocationReferenceQuotaOrFail() {
        sharedLargePath();
        graph.getNode(SOURCE).setCapacityUp(0);
        List<TrafficSession> zeroDemand = new java.util.ArrayList<>();
        List<TrafficSession> ping = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            TrafficSession zero = startTraffic("zero-" + i, true);
            zero.setActualBandwidth(17);
            zeroDemand.add(zero);
            var idle = graph.startSpeedtest(SOURCE, "ping-" + i, 100, 100, 0, 0, 1000, false, null, "").session();
            assertNotNull(idle);
            ping.add(idle);
        }
        TrafficSession first = startTraffic("first", false);
        TrafficSession second = startTraffic("second", false);
        for (int i = 0; i < 20; i++) { first.tick(); second.tick(); }
        graph.setDirty(false);
        graph.tickTraffic(level);
        assertEquals(50, first.getActualBandwidth());
        assertEquals(50, second.getActualBandwidth());
        assertEquals(100, graph.getTotalBandwidthDown());
        assertEquals(0, graph.getTotalBandwidthUp());
        for (TrafficSession zero : zeroDemand) {
            assertSame(zero, graph.getSessionByDeviceId(zero.getDeviceId()));
            assertEquals(TrafficSession.SessionState.UPLOAD, zero.getState());
            assertEquals(1, zero.getTicksElapsed());
            assertEquals(0, zero.getActualBandwidth());
            assertEquals(9, zero.getFinalUpBw()); // The injected 17 and the allocated zero average to 8.5.
        }
        for (TrafficSession idle : ping) {
            assertSame(idle, graph.getSessionByDeviceId(idle.getDeviceId()));
            assertEquals(TrafficSession.SessionState.PING, idle.getState());
            assertEquals(1, idle.getTicksElapsed());
            assertEquals(0, idle.getActualBandwidth());
        }
        assertFalse(graph.isDirty());
    }

    private List<BlockPos> sharedLargePath() {
        int positions = TelecomNetworkGraph.MAX_ALLOCATION_RESOURCE_REFERENCES / 2 - 3;
        List<BlockPos> physical = new java.util.ArrayList<>();
        for (int i = 0; i < positions; i++) physical.add(new BlockPos(i + 100, 0, 0));
        List<BlockPos> recorded = new java.util.ArrayList<>(physical);
        recorded.addAll(physical); // Duplicates must not consume the per-flow quota twice.
        recorded.add(SOURCE);
        recorded.add(SERVER);
        assertTrue(recorded.size() <= TelecomNetworkGraph.MAX_PHYSICAL_PATH_REFERENCES);
        graph.addEdge(edge(recorded));
        return physical;
    }

    private TrafficSession startTraffic(String ip, boolean upload) {
        var session = graph.startSpeedtest(SOURCE, ip, 100, 100, 0, 0, 1000, false, null, "").session();
        assertNotNull(session);
        for (int i = 0; i < (upload ? 1060 : 60); i++) session.tick();
        return session;
    }

    private NetworkEdge edge(List<BlockPos> blocks) {
        return new NetworkEdge(SOURCE, SERVER, 100, 1, NetworkEdge.EdgeType.FIBER, blocks);
    }
}
