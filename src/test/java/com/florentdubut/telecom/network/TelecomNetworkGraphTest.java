package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelecomNetworkGraphTest {
    private static final BlockPos ROUTER = new BlockPos(-12, 64, 8);
    private static final BlockPos SERVER = new BlockPos(12, 64, 8);

    @Test
    void roundTripPreservesNetworkAndCoverage() {
        TelecomNetworkGraph graph = connectedGraph();
        graph.getNode(ROUTER).setIpAddress("10.1.2.3");
        graph.getNode(ROUTER).setNetworkCidr("10.1.2.0/24");
        graph.getNode(ROUTER).setCapacityDown(8000);
        graph.getNode(ROUTER).setCapacityUp(700);
        graph.getNode(ROUTER).setFrequenciesMask(3);
        graph.addCoverageRecord(ROUTER, 4, 2);

        CompoundTag encoded = (CompoundTag) TelecomNetworkGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).getOrThrow();
        TelecomNetworkGraph restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertEquals(1, encoded.getIntOr("SchemaVersion", -1));
        assertEquals(2, restored.getNodes().size());
        assertEquals("10.1.2.3", restored.getNode(ROUTER).getIpAddress());
        assertEquals("10.1.2.0/24", restored.getNode(ROUTER).getNetworkCidr());
        assertEquals(8000, restored.getNode(ROUTER).getCapacityDown());
        assertEquals(700, restored.getNode(ROUTER).getCapacityUp());
        assertEquals(3, restored.getNode(ROUTER).getFrequenciesMask());
        assertEquals(List.of(ROUTER.east(), SERVER), restored.getEdges().getFirst().getPathBlocks());
        assertEquals(graph.getRecordedCoverage(), restored.getRecordedCoverage());
    }

    @Test
    void readsLegacyUnversionedPositionArrays() {
        CompoundTag legacy = new CompoundTag();
        CompoundTag node = new CompoundTag();
        node.putIntArray("Pos", new int[]{-12, 64, 8});
        node.putString("Type", "ROUTER");
        node.putString("IP", "10.1.0.2");
        ListTag nodes = new ListTag();
        nodes.add(node);
        legacy.put("Nodes", nodes);

        TelecomNetworkGraph restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();

        assertEquals("10.1.0.2", restored.getNode(ROUTER).getIpAddress());
        assertEquals(1000, restored.getNode(ROUTER).getCapacityDown());
        assertTrue(restored.getEdges().isEmpty());
    }

    @Test
    void codecRejectsFutureVersionsAndInvalidPositions() {
        CompoundTag future = new CompoundTag();
        future.putInt("SchemaVersion", 999);
        assertTrue(TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, future).error().isPresent());

        CompoundTag invalid = new CompoundTag();
        CompoundTag node = new CompoundTag();
        node.putString("Type", "ROUTER");
        ListTag nodes = new ListTag();
        nodes.add(node);
        invalid.put("Nodes", nodes);
        assertTrue(TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, invalid).error().isPresent());
    }

    @Test
    void rejectsInvalidAndDuplicateSessions() {
        TelecomNetworkGraph graph = connectedGraph();
        graph.startSpeedtest(ROUTER, "invalid", Integer.MAX_VALUE, 100, 0, 0, 300, false, null);
        graph.startSpeedtest(ROUTER, "negative", 100, 100, 0, 0, -1, false, null);
        graph.startSpeedtest(ROUTER, "mask", 100, 100, 0, -1, 300, false, null);
        graph.startSpeedtest(BlockPos.ZERO, "absent", 100, 100, 0, 0, 300, false, null);
        assertNull(graph.getSessionByIp("invalid"));
        assertNull(graph.getSessionByIp("negative"));
        assertNull(graph.getSessionByIp("mask"));
        assertNull(graph.getSessionByIp("absent"));

        graph.startSpeedtest(ROUTER, "10.0.0.2", 100, 100, 0, 0, 300, false, null);
        TrafficSession first = graph.getSessionByIp("10.0.0.2");
        assertNotNull(first);
        graph.startSpeedtest(ROUTER, "10.0.0.2", 200, 200, 0, 0, 300, false, null);
        assertSame(first, graph.getSessionByIp("10.0.0.2"));
    }

    @Test
    void boundsSessionsAndDoesNotPersistTransientTests() {
        TelecomNetworkGraph graph = connectedGraph();
        for (int i = 0; i < 300; i++) {
            BlockPos router = ROUTER.offset(0, 0, i + 1);
            graph.addNode(new NetworkNode(router, NetworkNode.NodeType.ROUTER));
            graph.addEdge(new NetworkEdge(router, SERVER, 10000, 24, NetworkEdge.EdgeType.FIBER, List.of()));
        }
        graph.setDirty(false);
        for (int i = 0; i < 300; i++) {
            graph.startSpeedtest(ROUTER.offset(0, 0, i + 1), "client-" + i, 100, 100, 0, 0, 300, false, null);
        }
        assertNotNull(graph.getSessionByIp("client-255"));
        assertNull(graph.getSessionByIp("client-256"));
        assertFalse(graph.isDirty());
        TelecomNetworkGraph restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE,
                TelecomNetworkGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).getOrThrow()).getOrThrow();
        assertNull(restored.getSessionByIp("client-0"));
    }

    @Test
    void removingAnEdgeInvalidatesCachedPaths() {
        TelecomNetworkGraph graph = connectedGraph();
        assertNotNull(graph.calculatePathStats(ROUTER, SERVER));
        graph.removeEdgeBetween(ROUTER, SERVER);
        assertNull(graph.calculatePathStats(ROUTER, SERVER));
    }

    @Test
    void mobileAddressesAreUniqueStableAndPersisted() {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        java.util.UUID player = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals("172.16.0.1", graph.getMobileIp(player));
        graph.setDirty(false);
        assertEquals("172.16.0.1", graph.getMobileIp(player));
        assertFalse(graph.isDirty());
        java.util.Set<String> addresses = new java.util.HashSet<>();
        for (int i = 1; i <= 1000; i++) {
            assertTrue(addresses.add(graph.getMobileIp(new java.util.UUID(0, i))));
        }
        TelecomNetworkGraph restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE,
                TelecomNetworkGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).getOrThrow()).getOrThrow();
        assertEquals("172.16.0.1", restored.getMobileIp(player));
        assertEquals("172.16.3.233", restored.getMobileIp(new java.util.UUID(0, 1001)));
    }

    private static TelecomNetworkGraph connectedGraph() {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        graph.addNode(new NetworkNode(ROUTER, NetworkNode.NodeType.ROUTER));
        graph.addNode(new NetworkNode(SERVER, NetworkNode.NodeType.SERVER));
        graph.addEdge(new NetworkEdge(ROUTER, SERVER, 10000, 24,
                NetworkEdge.EdgeType.FIBER, List.of(ROUTER.east(), SERVER)));
        return graph;
    }
}
