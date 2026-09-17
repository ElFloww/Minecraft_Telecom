package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FixedAddressesTest {
    @TempDir Path directory;

    @Test
    void allEquipmentGetsUniqueAddressesWithoutConnectivityAndReallocationIsANoOp() {
        var graph = new TelecomNetworkGraph();
        for (var type : NetworkNode.NodeType.values()) add(graph, type.ordinal(), type, null);
        graph.ensureFixedAddresses();
        Map<BlockPos, String> before = addresses(graph);
        assertEquals(graph.getNodes().size(), new HashSet<>(before.values()).size());
        for (var node : graph.getNodes()) {
            assertTrue(node.getIpAddress().startsWith("10."));
            assertEquals(node.getIpAddress() + "/32", node.getNetworkCidr());
        }
        graph.setDirty(false);
        graph.ensureFixedAddresses();
        assertEquals(before, addresses(graph));
        assertFalse(graph.isDirty());
    }

    @Test
    void newNodesCannotTakeAnExistingLeaseEvenIfTheySortFirst() {
        var graph = new TelecomNetworkGraph();
        var existing = add(graph, 10, NetworkNode.NodeType.ROUTER, "10.0.0.1");
        existing.setNetworkCidr("10.0.0.0/24");
        var added = add(graph, -10, NetworkNode.NodeType.SERVER, null);
        graph.ensureFixedAddresses();
        assertEquals("10.0.0.1", existing.getIpAddress());
        assertEquals("10.0.0.0/24", existing.getNetworkCidr());
        assertEquals("10.0.0.2", added.getIpAddress());
        assertTrue(graph.isDirty());
    }

    @Test
    void fusionDisconnectionAndRemovalOfServerPreserveRemainingLeasesAndMobileAddresses() {
        var graph = new TelecomNetworkGraph();
        var a = add(graph, 0, NetworkNode.NodeType.SERVER, null);
        var b = add(graph, 2, NetworkNode.NodeType.SERVER, null);
        var first = add(graph, 1, NetworkNode.NodeType.ROUTER, null);
        var second = add(graph, 3, NetworkNode.NodeType.ROUTER, null);
        graph.addEdge(edge(a, first));
        graph.addEdge(edge(b, second));
        graph.ensureFixedAddresses();
        var before = addresses(graph);
        UUID owner = new UUID(1, 2);
        String mobile = graph.getMobileIp(owner);
        graph.addEdge(edge(a, b));
        graph.ensureFixedAddresses();
        assertEquals(before, addresses(graph));
        graph.setEdges(List.of());
        graph.removeNode(a.getPosition());
        before.remove(a.getPosition());
        graph.ensureFixedAddresses();
        assertEquals(before, addresses(graph));
        assertEquals(mobile, graph.getMobileIp(owner));
        assertFalse(before.containsValue(mobile));
        assertEquals("no_server", graph.startSpeedtest(first.getPosition(), first.getIpAddress(), 100, 100,
                0, 0, 300, false, null, "").error());
    }

    @Test
    void diskRoundTripRetainsDisconnectedLeasesAndReservesThemForFutureAllocations() throws Exception {
        var graph = new TelecomNetworkGraph();
        for (int i = 0; i < 300; i++) add(graph, i, NetworkNode.NodeType.ROUTER, null);
        graph.ensureFixedAddresses();
        UUID owner = new UUID(4, 5);
        String mobile = graph.getMobileIp(owner);
        var before = addresses(graph);
        Path file = directory.resolve("telecom_network.dat");
        NbtIo.writeCompressed(encode(graph), file);
        var restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE,
                NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())).getOrThrow();
        assertEquals(before, addresses(restored));
        assertEquals(mobile, restored.getMobileIp(owner));
        assertFalse(restored.isDirty());
        var next = add(restored, -1, NetworkNode.NodeType.SERVER, null);
        restored.ensureFixedAddresses();
        assertFalse(before.containsValue(next.getIpAddress()));
        before.forEach((pos, ip) -> assertEquals(ip, restored.getNode(pos).getIpAddress()));
    }

    @Test
    void duplicateLegacyAddressesAreRepairedDeterministicallyOnLoad() {
        var graph = new TelecomNetworkGraph();
        var first = add(graph, -1, NetworkNode.NodeType.NRO, "10.1.0.1");
        add(graph, 5, NetworkNode.NodeType.ROUTER, "10.1.0.1");
        add(graph, 7, NetworkNode.NodeType.SERVER, "0.0.0.0");
        CompoundTag data = encode(graph);
        var restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, data).getOrThrow();
        ListTag reversed = new ListTag();
        var nodes = data.getListOrEmpty("Nodes");
        for (int i = nodes.size() - 1; i >= 0; i--) reversed.add(nodes.get(i));
        data.put("Nodes", reversed);
        var reversedLoad = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, data).getOrThrow();
        assertEquals(addresses(restored), addresses(reversedLoad));
        assertEquals("10.1.0.1", restored.getNode(first.getPosition()).getIpAddress());
        assertEquals(3, new HashSet<>(addresses(restored).values()).size());
        assertTrue(restored.isDirty());
        assertEquals(addresses(restored), addresses(TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, encode(restored)).getOrThrow()));
    }

    @Test
    void repairsMalformedReservedAndMobilePoolAddresses() {
        var graph = new TelecomNetworkGraph();
        String[] invalid = {null, "", "0.0.0.0", "10.0.0.0", "10.255.255.255", "10.1.2.256",
                "10.-1.2.3", "10.1.2.3.4", "10.01.2.3", "10.1.2.+3", " 10.1.2.3", "172.16.0.1"};
        for (int i = 0; i < invalid.length; i++) add(graph, i, NetworkNode.NodeType.SERVER, invalid[i]);
        String mobile = graph.getMobileIp(new UUID(0, 1));
        graph.ensureFixedAddresses();
        assertEquals(invalid.length, new HashSet<>(addresses(graph).values()).size());
        assertFalse(addresses(graph).containsValue(mobile));
        for (var node : graph.getNodes()) assertTrue(node.getIpAddress().startsWith("10.0.0."));
    }

    @Test
    void crossesOctetBoundariesWithoutOverflowAndWorldsHaveIndependentPools() {
        var graph = new TelecomNetworkGraph();
        for (int i = 0; i < 600; i++) add(graph, i, NetworkNode.NodeType.ROUTER, null);
        graph.ensureFixedAddresses();
        assertEquals(600, new HashSet<>(addresses(graph).values()).size());
        assertTrue(addresses(graph).containsValue("10.0.1.0"));
        assertTrue(addresses(graph).containsValue("10.0.2.88"));
        var other = new TelecomNetworkGraph();
        var first = add(other, 0, NetworkNode.NodeType.SERVER, null);
        other.ensureFixedAddresses();
        assertEquals("10.0.0.1", first.getIpAddress());
    }

    @Test
    void deletedEquipmentReleasesItsLeaseWithoutRenumberingTheOthers() {
        var graph = new TelecomNetworkGraph();
        var first = add(graph, 0, NetworkNode.NodeType.SERVER, null);
        var removed = add(graph, 1, NetworkNode.NodeType.ROUTER, null);
        var last = add(graph, 2, NetworkNode.NodeType.ANTENNA, null);
        graph.ensureFixedAddresses();
        var before = addresses(graph);
        String released = removed.getIpAddress();
        graph.removeNode(removed.getPosition());
        var next = add(graph, -1, NetworkNode.NodeType.ROUTER, null);
        graph.ensureFixedAddresses();
        assertEquals(released, next.getIpAddress());
        assertEquals(before.get(first.getPosition()), first.getIpAddress());
        assertEquals(before.get(last.getPosition()), last.getIpAddress());
        assertNull(TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, encode(graph)).getOrThrow().getNode(removed.getPosition()));
    }

    @Test
    void replacingAKnownNodePreservesItsAddressWithoutCopyingOtherConfiguration() {
        var graph = new TelecomNetworkGraph();
        var node = add(graph, 0, NetworkNode.NodeType.ANTENNA, "10.1.2.3");
        node.setNetworkCidr("10.1.2.0/24");
        node.setFrequenciesMask(1);
        var restored = add(graph, 0, NetworkNode.NodeType.ANTENNA, null);
        restored.setFrequenciesMask(2);
        assertEquals("10.1.2.3", restored.getIpAddress());
        assertEquals("10.1.2.0/24", restored.getNetworkCidr());
        assertEquals(2, restored.getFrequenciesMask());
    }

    private static NetworkNode add(TelecomNetworkGraph graph, int x, NetworkNode.NodeType type, String ip) {
        var node = new NetworkNode(new BlockPos(x, 64, 0), type);
        node.setIpAddress(ip);
        graph.addNode(node);
        return node;
    }

    private static NetworkEdge edge(NetworkNode a, NetworkNode b) {
        return new NetworkEdge(a.getPosition(), b.getPosition(), 1000, 1, NetworkEdge.EdgeType.FIBER, List.of());
    }

    private static Map<BlockPos, String> addresses(TelecomNetworkGraph graph) {
        Map<BlockPos, String> result = new HashMap<>();
        for (var node : graph.getNodes()) result.put(node.getPosition(), node.getIpAddress());
        return result;
    }

    private static CompoundTag encode(TelecomNetworkGraph graph) {
        return (CompoundTag) TelecomNetworkGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).getOrThrow();
    }
}
