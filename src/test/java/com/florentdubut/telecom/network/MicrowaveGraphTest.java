package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MicrowaveGraphTest {
    private static final BlockPos SOURCE = BlockPos.ZERO;
    private static final BlockPos DISH = new BlockPos(2, 0, 0);
    private static final BlockPos RELAY = new BlockPos(100, 0, 0);
    private static final BlockPos FAR_DISH = new BlockPos(200, 0, 0);
    private static final BlockPos SERVER = new BlockPos(202, 0, 0);

    @Test
    void microwaveFactoryBoundsCapacityAndHasNoCableResources() {
        NetworkEdge edge = NetworkEdge.microwave(DISH, FAR_DISH, 2000, 750, 198, 3);
        assertEquals(NetworkEdge.EdgeType.MICROWAVE, edge.getType());
        assertEquals(2000, edge.getBandwidthMax());
        assertEquals(750, edge.getEffectiveBandwidthMbps());
        assertEquals(3, edge.getLatencyMs());
        assertTrue(edge.getPathBlocks().isEmpty());
        assertEquals(2000, NetworkEdge.microwave(DISH, FAR_DISH, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 0).getEffectiveBandwidthMbps());
        assertEquals(0, NetworkEdge.microwave(DISH, FAR_DISH, -1, 10, 1, 0).getEffectiveBandwidthMbps());
        assertEquals(0, NetworkEdge.microwave(DISH, FAR_DISH, 100, -1, 1, 0).getEffectiveBandwidthMbps());
        assertThrows(IllegalArgumentException.class, () -> NetworkEdge.microwave(DISH, FAR_DISH, 100, 100, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> NetworkEdge.microwave(DISH, FAR_DISH, 100, 100, 1, -1));
    }

    @Test
    void downAndUpShareOneMicrowaveBudgetWithoutFictitiousCableUsage() {
        TelecomNetworkGraph graph = graph();
        NetworkEdge microwave = NetworkEdge.microwave(DISH, FAR_DISH, 2000, 100, 198, 3);
        graph.setMicrowaveEdges(List.of(microwave));
        TrafficSession down = start(graph, "down", false);
        TrafficSession up = start(graph, "up", true);
        tick(graph);
        assertEquals(50, down.getActualBandwidth());
        assertEquals(50, up.getActualBandwidth());
        assertEquals(100, microwave.getCurrentUsage());
        assertEquals(50, microwave.getCurrentUsageDown());
        assertEquals(50, microwave.getCurrentUsageUp());
        assertEquals(0, graph.getActualBlockCapacityMbps(RELAY));
        assertEquals(0, graph.getActualBlockUsageDown(RELAY));
        assertEquals(0, graph.getActualBlockUsageUp(RELAY));
        assertEquals(50, graph.getNode(DISH).getCurrentUsageDown());
        assertEquals(50, graph.getNode(DISH).getCurrentUsageUp());
    }

    @Test
    void twoHopRelayChargesEveryHopAndRespectsDirectionalNodeCapacity() {
        TelecomNetworkGraph graph = graph();
        graph.addNode(new NetworkNode(RELAY, NetworkNode.NodeType.MICROWAVE_DISH));
        BlockPos relayOut = RELAY.east();
        graph.addNode(new NetworkNode(relayOut, NetworkNode.NodeType.MICROWAVE_DISH));
        graph.addEdge(cable(RELAY, relayOut, 10_000, 1));
        NetworkEdge first = NetworkEdge.microwave(DISH, RELAY, 2000, 200, 98, 3);
        NetworkEdge second = NetworkEdge.microwave(relayOut, FAR_DISH, 2000, 100, 99, 5);
        graph.setMicrowaveEdges(List.of(first, second));
        graph.getNode(RELAY).setCapacityDown(30);
        TrafficSession down = start(graph, "down", false);
        TrafficSession up = start(graph, "up", true);
        tick(graph);
        assertEquals(30, down.getActualBandwidth());
        assertEquals(70, up.getActualBandwidth());
        for (NetworkEdge edge : List.of(first, second)) {
            assertEquals(100, edge.getCurrentUsage());
            assertEquals(30, edge.getCurrentUsageDown());
            assertEquals(70, edge.getCurrentUsageUp());
        }
        assertEquals(30, graph.getNode(RELAY).getCurrentUsageDown());
        assertEquals(70, graph.getNode(RELAY).getCurrentUsageUp());
        assertEquals(9, graph.calculatePathStats(SOURCE, SERVER).pingMs());
    }

    @Test
    void reconciliationRetainsIdentityAndCachesUntilCapacityLatencyOrStructureChanges() {
        TelecomNetworkGraph graph = graph();
        NetworkEdge first = NetworkEdge.microwave(DISH, FAR_DISH, 2000, 500, 198, 3);
        graph.setMicrowaveEdges(List.of(first));
        assertEquals(500, graph.calculatePathStats(SOURCE, SERVER).bandwidthMbps());
        first.setCurrentUsage(19);
        long revision = graph.getTopologyRevision();
        graph.setDirty(false);
        graph.setMicrowaveEdges(List.of(NetworkEdge.microwave(FAR_DISH, DISH, 2000, 500, 198, 3)));
        assertEquals(revision, graph.getTopologyRevision());
        assertSame(first, graph.getEdges().getLast());
        assertEquals(19, first.getCurrentUsage());
        assertFalse(graph.isDirty());

        graph.setMicrowaveEdges(List.of(NetworkEdge.microwave(DISH, FAR_DISH, 2000, 75, 198, 3)));
        assertEquals(++revision, graph.getTopologyRevision());
        assertEquals(75, graph.calculatePathStats(SOURCE, SERVER).bandwidthMbps());
        assertEquals(75, graph.getSpeedtestServers(SOURCE, 0).getFirst().bandwidthMbps());
        graph.setMicrowaveEdges(List.of(NetworkEdge.microwave(DISH, FAR_DISH, 2000, 75, 198, 9)));
        assertEquals(++revision, graph.getTopologyRevision());
        assertEquals(10, graph.calculatePathStats(SOURCE, SERVER).pingMs());
        graph.setMicrowaveEdges(List.of(NetworkEdge.microwave(DISH, FAR_DISH, 1000, 75, 198, 9)));
        assertEquals(++revision, graph.getTopologyRevision());
        graph.setMicrowaveEdges(List.of(NetworkEdge.microwave(DISH, FAR_DISH, 1000, 75, 199, 9)));
        assertEquals(++revision, graph.getTopologyRevision());
        graph.setMicrowaveEdges(List.of());
        assertEquals(++revision, graph.getTopologyRevision());
        assertNull(graph.calculatePathStats(SOURCE, SERVER));
        graph.setMicrowaveEdges(List.of());
        assertEquals(revision, graph.getTopologyRevision());
        assertFalse(graph.isDirty());
    }

    @Test
    void failedMicrowaveFallsBackToParallelWireWithoutRestartingSession() {
        TelecomNetworkGraph graph = graph();
        NetworkEdge microwave = NetworkEdge.microwave(DISH, FAR_DISH, 2000, 100, 198, 3);
        graph.setMicrowaveEdges(List.of(microwave));
        NetworkEdge fallback = cable(DISH, FAR_DISH, 60, 200);
        graph.addEdge(fallback);
        TrafficSession session = start(graph, "client", false);
        tick(graph);
        assertEquals(100, session.getActualBandwidth());
        assertEquals(0, fallback.getCurrentUsage());
        var id = session.getSessionId();
        graph.setMicrowaveEdges(List.of(NetworkEdge.microwave(DISH, FAR_DISH, 2000, 80, 198, 3)));
        tick(graph);
        assertEquals(80, session.getActualBandwidth());
        assertEquals(0, fallback.getCurrentUsage());
        graph.setMicrowaveEdges(List.of());
        tick(graph);
        assertSame(session, graph.getSessionByDeviceId(session.getDeviceId()));
        assertEquals(id, session.getSessionId());
        assertEquals(60, session.getActualBandwidth());
        assertEquals(60, fallback.getCurrentUsage());
        assertEquals(11, session.getPingMs());
        assertTrue(graph.getEdges().contains(fallback));
    }

    @Test
    void serviceRemovalHappensBeforeAllocationAndDoesNotScheduleCableTracing() {
        TelecomNetworkGraph graph = graph();
        graph.setMicrowaveEdges(List.of(NetworkEdge.microwave(DISH, FAR_DISH, 2000, 100, 198, 3)));
        TrafficSession session = start(graph, "client", false);
        tick(graph);
        ServerLevel level = level();
        try (var service = mockStatic(MicrowaveLinkService.class);
             var tracer = mockStatic(NetworkTracer.class)) {
            service.when(() -> MicrowaveLinkService.tick(level, graph)).thenAnswer(invocation -> {
                graph.setMicrowaveEdges(List.of());
                return null;
            });
            graph.tickTraffic(level);
            service.verify(() -> MicrowaveLinkService.tick(level, graph));
            tracer.verifyNoInteractions();
        }
        verify(level, never()).getChunk(anyInt(), anyInt(), any(), anyBoolean());
        assertEquals("route_lost", session.getFailureReason());
        assertEquals(0, session.getActualBandwidth());
        assertEquals(0, graph.getTotalBandwidthDown());
        assertNull(graph.calculatePathStats(SOURCE, SERVER));
    }

    @Test
    void cableReplacementPreservesMicrowaveAndGenericReplacementStillReplacesEverything() {
        TelecomNetworkGraph graph = graph();
        NetworkEdge microwave = NetworkEdge.microwave(DISH, FAR_DISH, 2000, 100, 198, 3);
        NetworkEdge parallel = cable(DISH, FAR_DISH, 300, 198);
        graph.setMicrowaveEdges(List.of(microwave));
        graph.setCableEdges(List.of(parallel));
        assertEquals(2, graph.getEdges().size());
        assertTrue(graph.getEdges().contains(microwave));
        graph.setMicrowaveEdges(List.of());
        assertEquals(List.of(parallel), graph.getEdges());
        graph.setMicrowaveEdges(List.of(microwave));
        graph.setEdges(List.of(parallel));
        assertEquals(List.of(parallel), graph.getEdges());
        assertThrows(IllegalArgumentException.class, () -> graph.setCableEdges(List.of(microwave)));
        assertThrows(IllegalArgumentException.class, () -> graph.setMicrowaveEdges(List.of(parallel)));
        assertEquals(List.of(parallel), graph.getEdges());
    }

    @Test
    void latencyUsesExplicitMicrowaveMillisecondsAndPreservesLegacyWireRounding() {
        TelecomNetworkGraph graph = graph();
        graph.setMicrowaveEdges(List.of(NetworkEdge.microwave(DISH, FAR_DISH, 2000, 100, 100_000, 51)));
        graph.ensureFixedAddresses();
        Packet packet = new Packet(graph.getNode(SOURCE).getIpAddress(), graph.getNode(SERVER).getIpAddress(), 0, "ping");
        assertEquals(52, graph.calculatePathStats(SOURCE, SERVER).pingMs());
        assertEquals(2, graph.routePacket(packet));
        graph.setEdges(List.of(cable(SOURCE, SERVER, 100, 39)));
        assertEquals(2, graph.calculatePathStats(SOURCE, SERVER).pingMs());
        assertEquals(3, graph.routePacket(packet));
        graph.setEdges(List.of(new NetworkEdge(SOURCE, DISH, 100, 13, NetworkEdge.EdgeType.COPPER, List.of()),
                new NetworkEdge(DISH, SERVER, 100, 29, NetworkEdge.EdgeType.MEDIUM_FIBER, List.of())));
        assertEquals(Math.max(1, (int) (13 * 0.2f + 29 * 0.02f + 1.0f)), graph.calculatePathStats(SOURCE, SERVER).pingMs());
        assertEquals(3, graph.routePacket(packet));
    }

    @Test
    void restartDropsDerivedLinksButKeepsEndpointConfigAndStableAddresses() {
        TelecomNetworkGraph graph = graph();
        MicrowaveConfig config = new MicrowaveConfig(FAR_DISH, 3, 18, 270, 0, true);
        graph.getNode(DISH).setMicrowaveConfig(config);
        graph.setMicrowaveEdges(List.of(NetworkEdge.microwave(DISH, FAR_DISH, 2000, 100, 198, 3)));
        graph.ensureFixedAddresses();
        var mobile = new java.util.UUID(1, 2);
        String mobileIp = graph.getMobileIp(mobile);
        CompoundTag saved = (CompoundTag) TelecomNetworkGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).getOrThrow();
        assertEquals(2, saved.getListOrEmpty("Edges").size());
        // Even a stale derived record written by another producer must never restore a live link.
        CompoundTag stale = new CompoundTag();
        stale.putString("Type", "MICROWAVE");
        saved.getListOrEmpty("Edges").add(stale);
        TelecomNetworkGraph restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(2, restored.getEdges().size());
        assertNull(restored.calculatePathStats(SOURCE, SERVER));
        assertEquals(config, restored.getNode(DISH).getMicrowaveConfig());
        assertEquals(MicrowaveConfig.DEFAULT, restored.getNode(FAR_DISH).getMicrowaveConfig());
        for (NetworkNode node : graph.getNodes()) {
            assertEquals(node.getIpAddress(), restored.getNode(node.getPosition()).getIpAddress());
        }
        assertEquals(mobileIp, restored.getMobileIp(mobile));
    }

    @Test
    void missingMicrowaveConfigDefaultsAndLocalWiringDoesNotWeakenOtherTiers() {
        CompoundTag saved = new CompoundTag();
        ListTag nodes = new ListTag();
        CompoundTag dish = new CompoundTag();
        dish.putString("Type", "MICROWAVE_DISH");
        dish.store("Pos", BlockPos.CODEC, DISH);
        nodes.add(dish);
        saved.put("Nodes", nodes);
        var restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(MicrowaveConfig.DEFAULT, restored.getNode(DISH).getMicrowaveConfig());
        assertEquals(10_000, restored.getNode(DISH).getCapacityDown());
        assertEquals(10_000, restored.getNode(DISH).getCapacityUp());
        for (var type : NetworkNode.NodeType.values()) {
            for (var cable : NetworkEdge.EdgeType.values()) {
                boolean expected = switch (cable) {
                    case FIBER -> type != NetworkNode.NodeType.PHONE;
                    case COPPER -> switch (type) {
                        case ROUTER, ANTENNA, NRA, SR, MICROWAVE_DISH -> true;
                        default -> false;
                    };
                    default -> false;
                };
                assertEquals(expected, NetworkTracer.isCableCompatibleWithNodes(cable, NetworkNode.NodeType.MICROWAVE_DISH, type), type + " via " + cable);
                assertEquals(expected, NetworkTracer.isCableCompatibleWithNodes(cable, type, NetworkNode.NodeType.MICROWAVE_DISH), type + " via " + cable);
                if (expected) {
                    assertTrue(NetworkTracer.doesNodeAcceptCable(type, cable));
                    assertTrue(NetworkTracer.doesNodeAcceptCable(NetworkNode.NodeType.MICROWAVE_DISH, cable));
                }
            }
            assertFalse(NetworkTracer.doesNodeAcceptCable(type, NetworkEdge.EdgeType.MICROWAVE));
            assertFalse(NetworkTracer.isCableCompatibleWithNodes(NetworkEdge.EdgeType.MICROWAVE, type, NetworkNode.NodeType.MICROWAVE_DISH));
        }
        assertFalse(NetworkTracer.isCableCompatibleWithNodes(NetworkEdge.EdgeType.FIBER, NetworkNode.NodeType.ROUTER, NetworkNode.NodeType.ANTENNA));
        assertFalse(NetworkTracer.isCableCompatibleWithNodes(NetworkEdge.EdgeType.FIBER, NetworkNode.NodeType.PM, NetworkNode.NodeType.PM));
        assertFalse(NetworkTracer.isCableCompatibleWithNodes(NetworkEdge.EdgeType.COPPER, NetworkNode.NodeType.SERVER, NetworkNode.NodeType.ROUTER));
        assertFalse(NetworkTracer.isCableCompatibleWithNodes(NetworkEdge.EdgeType.BIG_FIBER, NetworkNode.NodeType.MICROWAVE_DISH, NetworkNode.NodeType.SERVER));
    }

    private static TelecomNetworkGraph graph() {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        graph.addNode(new NetworkNode(SOURCE, NetworkNode.NodeType.ANTENNA));
        graph.addNode(new NetworkNode(DISH, NetworkNode.NodeType.MICROWAVE_DISH));
        graph.addNode(new NetworkNode(FAR_DISH, NetworkNode.NodeType.MICROWAVE_DISH));
        graph.addNode(new NetworkNode(SERVER, NetworkNode.NodeType.SERVER));
        graph.setCableEdges(List.of(cable(SOURCE, DISH, 10_000, 2), cable(FAR_DISH, SERVER, 10_000, 2)));
        return graph;
    }

    private static NetworkEdge cable(BlockPos a, BlockPos b, int capacity, int length) {
        return new NetworkEdge(a, b, capacity, length, NetworkEdge.EdgeType.FIBER, List.of());
    }

    private static TrafficSession start(TelecomNetworkGraph graph, String ip, boolean upload) {
        var result = graph.startSpeedtest(SOURCE, ip, 10_000, 10_000, 0, 0, 1000, true, null, "");
        assertTrue(result.accepted(), result.error());
        for (int i = 0; i < (upload ? 1060 : 60); i++) result.session().tick();
        return result.session();
    }

    private static ServerLevel level() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.players()).thenReturn(List.of());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        return level;
    }

    private static void tick(TelecomNetworkGraph graph) {
        try (var service = mockStatic(MicrowaveLinkService.class)) {
            graph.tickTraffic(level());
        }
    }
}
