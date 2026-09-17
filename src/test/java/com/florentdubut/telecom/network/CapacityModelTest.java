package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapacityModelTest {
    @Test
    void edgeNominalsAndEffectiveCopperAreBoundedWithoutOverflowOrInflation() {
        int[] nominals = {1000, 10_000, 100_000, 1_000_000};
        for (NetworkEdge.EdgeType type : NetworkEdge.EdgeType.values()) {
            assertEquals(nominals[type.ordinal()], type.nominalBandwidthMbps());
            assertEquals(100, edge(type, 100, 0).getBandwidthMax());
            assertEquals(100, edge(type, 100, 0).getEffectiveBandwidthMbps());
            assertEquals(0, edge(type, -1, 0).getEffectiveBandwidthMbps());
            assertEquals(0, edge(type, 0, Integer.MAX_VALUE).getEffectiveBandwidthMbps());
            assertEquals(1_000_000, edge(type, Integer.MAX_VALUE, 0).getBandwidthMax());
            assertThrows(IllegalArgumentException.class, () -> edge(type, 100, -1));
        }
        assertEquals(800, edge(NetworkEdge.EdgeType.COPPER, 1000, 100).getEffectiveBandwidthMbps());
        assertEquals(10, edge(NetworkEdge.EdgeType.COPPER, 1000, Integer.MAX_VALUE).getEffectiveBandwidthMbps());
        assertEquals(5, edge(NetworkEdge.EdgeType.COPPER, 5, Integer.MAX_VALUE).getEffectiveBandwidthMbps());
        assertEquals(100, edge(NetworkEdge.EdgeType.FIBER, 100, Integer.MAX_VALUE).getEffectiveBandwidthMbps());
        assertEquals(20_000, edge(NetworkEdge.EdgeType.FIBER, 20_000, 10).getEffectiveBandwidthMbps());
    }

    @Test
    void constructorsApplyProfilesAndSettersBoundBothDirections() {
        for (NetworkNode.NodeType type : NetworkNode.NodeType.values()) {
            int expected = switch (type) {
                case SERVER, NRO, ANTENNA -> 1_000_000;
                case NRA, PM -> 100_000;
                case SR -> 10_000;
                case ROUTER, PHONE -> 1000;
            };
            NetworkNode node = new NetworkNode(BlockPos.ZERO, type);
            assertEquals(expected, type.defaultCapacityMbps());
            assertEquals(expected, node.getCapacityDown());
            assertEquals(expected, node.getCapacityUp());
            assertFalse(node.requiresCapacitySync());
            node.setCapacityDown(-1);
            node.setCapacityUp(Integer.MAX_VALUE);
            assertEquals(0, node.getCapacityDown());
            assertEquals(1_000_000, node.getCapacityUp());
        }
    }

    @Test
    void legacyCapacityMigrationKeepsRouterSettingsAndIpLeasesButProfilesOtherNodes() {
        TelecomNetworkGraph graph = allNodeTypes();
        UUID mobile = new UUID(0, 123);
        String mobileIp = graph.getMobileIp(mobile);
        CompoundTag legacy = encode(graph);
        legacy.remove("CapacityModelVersion");
        TelecomNetworkGraph migrated = decode(legacy);
        assertTrue(migrated.isDirty());
        for (NetworkNode original : graph.getNodes()) {
            NetworkNode restored = migrated.getNode(original.getPosition());
            boolean router = original.getType() == NetworkNode.NodeType.ROUTER;
            assertEquals(router, restored.requiresCapacitySync());
            assertEquals(router ? 123 : original.getType().defaultCapacityMbps(), restored.getCapacityDown());
            assertEquals(router ? 45 : original.getType().defaultCapacityMbps(), restored.getCapacityUp());
            assertEquals(original.getIpAddress(), restored.getIpAddress());
            assertEquals(original.getNetworkCidr(), restored.getNetworkCidr());
        }
        assertEquals(mobileIp, migrated.getMobileIp(mobile));
        CompoundTag saved = encode(migrated);
        assertEquals(1, saved.getIntOr("SchemaVersion", -1));
        assertEquals(1, saved.getIntOr("CapacityModelVersion", -1));
        assertEquals(123, decode(saved).getNode(new BlockPos(NetworkNode.NodeType.ROUTER.ordinal(), 0, 0)).getCapacityDown());
    }

    @Test
    void versionOnePreservesCustomAndZeroCapacitiesAndNonRouterReregistration() {
        TelecomNetworkGraph original = allNodeTypes();
        original.getNode(BlockPos.ZERO).setCapacityDown(0);
        original.addEdge(edge(NetworkEdge.EdgeType.FIBER, 100, 10));
        TelecomNetworkGraph restored = decode(encode(original));
        for (NetworkNode expected : original.getNodes()) {
            assertEquals(expected.getCapacityDown(), restored.getNode(expected.getPosition()).getCapacityDown());
            assertEquals(expected.getCapacityUp(), restored.getNode(expected.getPosition()).getCapacityUp());
        }
        restored.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.SERVER));
        assertEquals(0, restored.getNode(BlockPos.ZERO).getCapacityDown());
        assertEquals(45, restored.getNode(BlockPos.ZERO).getCapacityUp());
        assertEquals(100, restored.getEdges().getFirst().getBandwidthMax());
        assertEquals(100, restored.getEdges().getFirst().getEffectiveBandwidthMbps());
    }

    @Test
    void legacyRouterSyncFlagSurvivesSaveBeforeChunkLoadAndSameTypeReplacement() {
        BlockPos pos = new BlockPos(NetworkNode.NodeType.ROUTER.ordinal(), 0, 0);
        CompoundTag legacy = encode(allNodeTypes());
        legacy.remove("CapacityModelVersion");
        TelecomNetworkGraph firstLoad = decode(legacy);
        assertTrue(firstLoad.getNode(pos).requiresCapacitySync());
        TelecomNetworkGraph beforeChunkLoad = decode(encode(firstLoad));
        assertTrue(beforeChunkLoad.getNode(pos).requiresCapacitySync());
        String ip = beforeChunkLoad.getNode(pos).getIpAddress();
        beforeChunkLoad.addNode(new NetworkNode(pos, NetworkNode.NodeType.ROUTER));
        assertTrue(beforeChunkLoad.getNode(pos).requiresCapacitySync());
        assertEquals(ip, beforeChunkLoad.getNode(pos).getIpAddress());
        assertEquals(123, beforeChunkLoad.getNode(pos).getCapacityDown());
        assertEquals(45, beforeChunkLoad.getNode(pos).getCapacityUp());
        beforeChunkLoad.getNode(pos).setCapacitySyncRequired(false);
        assertFalse(decode(encode(beforeChunkLoad)).getNode(pos).requiresCapacitySync());
        beforeChunkLoad.addNode(new NetworkNode(pos, NetworkNode.NodeType.NRO));
        assertFalse(beforeChunkLoad.getNode(pos).requiresCapacitySync());
    }

    @Test
    void versionOneRouterWithoutFlagPreservesZeroAndCustomCapacitiesWithoutSync() {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        NetworkNode router = new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.ROUTER);
        router.setCapacityDown(0);
        router.setCapacityUp(45);
        graph.addNode(router);
        CompoundTag tag = encode(graph);
        tag.getListOrEmpty("Nodes").getCompound(0).orElseThrow().remove("CapacityNeedsSync");
        TelecomNetworkGraph restored = decode(tag);
        restored.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.ROUTER));
        assertFalse(restored.getNode(BlockPos.ZERO).requiresCapacitySync());
        assertEquals(0, restored.getNode(BlockPos.ZERO).getCapacityDown());
        assertEquals(45, restored.getNode(BlockPos.ZERO).getCapacityUp());
    }

    @Test
    void rejectsFutureCapacityVersionsAndMalformedNumbersWithoutSilentTruncation() {
        CompoundTag tag = encode(allNodeTypes());
        tag.putInt("CapacityModelVersion", 2);
        assertTrue(TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, tag).error().isPresent());
        tag.putInt("CapacityModelVersion", 1);
        tag.getListOrEmpty("Nodes").getCompound(0).orElseThrow().putLong("CapDown", 1L << 32);
        assertTrue(TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, tag).error().isPresent());

        TelecomNetworkGraph graph = allNodeTypes();
        graph.addEdge(edge(NetworkEdge.EdgeType.COPPER, 1000, 10));
        tag = encode(graph);
        CompoundTag cable = tag.getListOrEmpty("Edges").getCompound(0).orElseThrow();
        cable.putInt("Length", -1);
        assertTrue(TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, tag).error().isPresent());
        cable.putLong("Length", 1L << 32);
        assertTrue(TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, tag).error().isPresent());
        cable.remove("Length");
        assertEquals(10, decode(tag).getEdges().getFirst().getEffectiveBandwidthMbps());
        cable.putInt("BandwidthMax", -10);
        assertEquals(0, decode(tag).getEdges().getFirst().getEffectiveBandwidthMbps());
    }

    @Test
    void retracingPreservesCustomNominalAndAddressesAndDoesNotStoreNodeAsCable() {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        graph.addNode(new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.NRO));
        graph.addNode(new NetworkNode(BlockPos.ZERO.east(), NetworkNode.NodeType.SERVER));
        graph.addEdge(edge(NetworkEdge.EdgeType.FIBER, 100, 1));
        graph.ensureFixedAddresses();
        String address = graph.getNode(BlockPos.ZERO).getIpAddress();
        ServerLevel level = mock(ServerLevel.class);
        try (MockedStatic<TelecomNetworkGraph> graphs = mockStatic(TelecomNetworkGraph.class);
             MockedStatic<NetworkTracer> tracer = mockStatic(NetworkTracer.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            tracer.when(() -> NetworkTracer.isCableCompatibleWithNodes(eq(NetworkEdge.EdgeType.FIBER), any(), any()))
                    .thenReturn(true);
            tracer.when(() -> NetworkTracer.recalculateNetwork(level)).thenCallRealMethod();
            NetworkTracer.recalculateNetwork(level);
        }
        assertEquals(1, graph.getEdges().size());
        assertEquals(100, graph.getEdges().getFirst().getBandwidthMax());
        assertTrue(graph.getEdges().getFirst().getPathBlocks().isEmpty());
        assertEquals(address, graph.getNode(BlockPos.ZERO).getIpAddress());
    }

    private static TelecomNetworkGraph allNodeTypes() {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        for (NetworkNode.NodeType type : NetworkNode.NodeType.values()) {
            NetworkNode node = new NetworkNode(new BlockPos(type.ordinal(), 0, 0), type);
            node.setCapacityDown(123);
            node.setCapacityUp(45);
            node.setIpAddress("10.2.0." + (type.ordinal() + 1));
            node.setNetworkCidr("10.2.0.0/24");
            graph.addNode(node);
        }
        return graph;
    }

    private static NetworkEdge edge(NetworkEdge.EdgeType type, int nominal, int length) {
        return new NetworkEdge(BlockPos.ZERO, BlockPos.ZERO.east(), nominal, length, type, List.of());
    }

    private static CompoundTag encode(TelecomNetworkGraph graph) {
        return (CompoundTag) TelecomNetworkGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).getOrThrow();
    }

    private static TelecomNetworkGraph decode(CompoundTag tag) {
        return TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }
}
