package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.NetworkToolSyncPayload.CapacityMode;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkToolSnapshotTest {
    private static final BlockPos POS = new BlockPos(1, 64, 1);

    @Test
    void nodeUsesItsDirectionalCapacitiesAndUsagesWithoutReadingEdges() {
        var graph = mock(TelecomNetworkGraph.class);
        var node = new NetworkNode(POS, NetworkNode.NodeType.ROUTER);
        node.setCapacityDown(1000);
        node.setCapacityUp(700);
        node.setCurrentUsageDown(500);
        node.setCurrentUsageUp(350);
        when(graph.getNode(POS)).thenReturn(node);
        var snapshot = ModNetworking.createNetworkToolSnapshot(graph, POS);
        assertEquals(1000, snapshot.maxBandwidth());
        assertEquals(700, snapshot.capacityUp());
        assertEquals(500, snapshot.usageDown());
        assertEquals(350, snapshot.usageUp());
        assertEquals(CapacityMode.DIRECTIONAL, snapshot.mode());
        assertEquals("gui.telecom.tool.node.router", snapshot.edgeType());
        verify(graph, never()).getEdges();
        verify(graph, never()).getActualBlockCapacityMbps(any());
    }

    @Test
    void cableUsesPhysicalCapacityAndDeduplicatedUsageNotNominalOrMaximumLink() {
        var graph = mock(TelecomNetworkGraph.class);
        var shortEdge = new NetworkEdge(POS.west(), POS.east(), 1000, 20, NetworkEdge.EdgeType.COPPER, List.of(POS));
        var longEdge = new NetworkEdge(POS.north(), POS.south(), 1000, 100, NetworkEdge.EdgeType.COPPER, List.of(POS));
        when(graph.getEdges()).thenReturn(List.of(shortEdge, longEdge));
        when(graph.getActualBlockCapacityMbps(POS)).thenReturn(800);
        when(graph.getActualBlockUsageDown(POS)).thenReturn(300);
        when(graph.getActualBlockUsageUp(POS)).thenReturn(200);
        var snapshot = ModNetworking.createNetworkToolSnapshot(graph, POS);
        assertEquals(CapacityMode.SHARED, snapshot.mode());
        assertEquals(800, snapshot.maxBandwidth());
        assertEquals(800, snapshot.capacityUp());
        assertEquals(300, snapshot.usageDown());
        assertEquals(200, snapshot.usageUp());
        assertEquals(100, snapshot.length());
        when(graph.getEdges()).thenReturn(List.of(longEdge, shortEdge));
        assertEquals(snapshot, ModNetworking.createNetworkToolSnapshot(graph, POS));
        when(graph.getActualBlockCapacityMbps(POS)).thenReturn(123);
        assertEquals(123, ModNetworking.createNetworkToolSnapshot(graph, POS).maxBandwidth());
    }

    @Test
    void serverUsesTheInspectedNodeRatherThanGlobalTotalsOrAConstant() {
        var graph = mock(TelecomNetworkGraph.class);
        var node = new NetworkNode(POS, NetworkNode.NodeType.SERVER);
        node.setCapacityDown(1000000);
        node.setCapacityUp(900000);
        node.setCurrentUsageDown(400000);
        node.setCurrentUsageUp(300000);
        when(graph.getNode(POS)).thenReturn(node);
        var snapshot = ModNetworking.createServerSnapshot(graph, POS, new java.util.UUID(1, 2), "minecraft:overworld", true, 3);
        assertEquals(POS, snapshot.pos());
        assertEquals(1000000, snapshot.capacityDown());
        assertEquals(900000, snapshot.capacityUp());
        assertEquals(400000, snapshot.totalBandwidthDown());
        assertEquals(300000, snapshot.totalBandwidthUp());
        verify(graph, never()).getTotalBandwidthDown();
        verify(graph, never()).getTotalBandwidthUp();
    }

    @Test
    void missingNodeOrCableDoesNotInventCapacity() {
        var graph = mock(TelecomNetworkGraph.class);
        assertNull(ModNetworking.createNetworkToolSnapshot(graph, POS));
        var server = ModNetworking.createServerSnapshot(graph, POS, new java.util.UUID(1, 2), "minecraft:overworld", false, 0);
        assertFalse(server.valid());
        assertEquals(0, server.capacityDown());
        assertEquals(0, server.capacityUp());
    }

}
