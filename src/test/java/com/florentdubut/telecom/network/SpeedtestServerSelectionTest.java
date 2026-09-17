package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeedtestServerSelectionTest {
    private static final BlockPos SOURCE = new BlockPos(0, 64, 0);
    private static final BlockPos FAST = new BlockPos(-10, 64, 0);
    private static final BlockPos SLOW = new BlockPos(10, 64, 0);
    private static final BlockPos UNREACHABLE = new BlockPos(20, 64, 0);
    private TelecomNetworkGraph graph;

    @BeforeEach
    void setup() {
        graph = new TelecomNetworkGraph();
        graph.addNode(new NetworkNode(SOURCE, NetworkNode.NodeType.ROUTER));
        // Keep server selection independent of the default router bottleneck.
        graph.getNode(SOURCE).setCapacityDown(10_000);
        graph.getNode(SOURCE).setCapacityUp(10_000);
        server(SLOW, 200);
        server(FAST, 20);
        graph.addNode(new NetworkNode(UNREACHABLE, NetworkNode.NodeType.SERVER));
    }

    @Test
    void catalogueUsesSourceAndExtraPingAndIncludesUnreachableServers() {
        assertEquals(List.of(
                new SpeedtestServerOption(id(FAST), "Server (-10,64,0)", 27, true, 10000, ""),
                new SpeedtestServerOption(id(SLOW), "Server (10,64,0)", 36, true, 10000, ""),
                new SpeedtestServerOption(id(UNREACHABLE), "Server (20,64,0)", -1, false, 0, "server_unavailable")
        ), graph.getSpeedtestServers(SOURCE, 25));
        BlockPos isolated = SOURCE.above();
        graph.addNode(new NetworkNode(isolated, NetworkNode.NodeType.ANTENNA));
        assertTrue(graph.getSpeedtestServers(isolated, 25).stream().noneMatch(SpeedtestServerOption::available));
        assertTrue(graph.getSpeedtestServers(null, 0).isEmpty());
        assertTrue(graph.getSpeedtestServers(SOURCE.below(), 0).isEmpty());
        assertTrue(graph.getSpeedtestServers(SOURCE, -1).isEmpty());
        assertTrue(graph.getSpeedtestServers(SOURCE, 60_001).isEmpty());
        assertNull(graph.getSessionByDeviceId(TrafficSession.routerDeviceId(SOURCE)));
    }

    @Test
    void catalogueIsBoundedAndDeterministic() {
        for (int i = 0; i < 150; i++) {
            graph.addNode(new NetworkNode(new BlockPos(100 + i, 64, 0), NetworkNode.NodeType.SERVER));
        }
        List<SpeedtestServerOption> options = graph.getSpeedtestServers(SOURCE, 0);
        assertEquals(128, TelecomNetworkGraph.MAX_SPEEDTEST_SERVERS);
        assertEquals(128, options.size());
        assertEquals(options, graph.getSpeedtestServers(SOURCE, 0));
        for (int i = 3; i < options.size(); i++) {
            assertTrue(Long.parseLong(options.get(i - 1).id()) < Long.parseLong(options.get(i).id()));
        }
        assertThrows(UnsupportedOperationException.class, options::clear);
    }

    @Test
    void autoChoosesLowestPingButExplicitChoiceCanUseAnotherServer() {
        TrafficSession automatic = start(SOURCE, "", false).session();
        assertEquals(id(FAST), automatic.getServerId());
        assertEquals("Server (-10,64,0)", automatic.getServerName());
        BlockPos other = SOURCE.above();
        graph.addNode(new NetworkNode(other, NetworkNode.NodeType.ROUTER));
        graph.addEdge(new NetworkEdge(other, SOURCE, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
        TrafficSession explicit = start(other, id(SLOW), false).session();
        assertEquals(id(SLOW), explicit.getServerId());
        assertSame(automatic, graph.getSessionByDeviceId(automatic.getDeviceId()));
        assertEquals("", explicit.getFailureReason());
    }

    @Test
    void autoBreaksPingTiesByPackedPositionRatherThanInsertionOrder() {
        graph.removeEdgeBetween(SOURCE, SLOW);
        graph.addEdge(new NetworkEdge(SOURCE, SLOW, 1000, 20, NetworkEdge.EdgeType.FIBER, List.of()));
        assertEquals(id(FAST), graph.getSpeedtestServers(SOURCE, 0).getFirst().id());
        assertEquals(id(FAST), start(SOURCE, "", false).session().getServerId());
    }

    @Test
    void disappearedSelectionDoesNotFallbackOrReplacePassiveTraffic() {
        TrafficSession passive = start(SOURCE, "", true).session();
        String selected = graph.getSpeedtestServers(SOURCE, 0).get(1).id();
        graph.removeNode(SLOW);
        assertRejected(start(SOURCE, selected, false), "server_unavailable");
        assertSame(passive, graph.getSessionByDeviceId(passive.getDeviceId()));
        assertEquals(id(FAST), passive.getServerId());
        assertTrue(start(SOURCE, id(FAST), false).accepted());
        assertNotSame(passive, graph.getSessionByDeviceId(passive.getDeviceId()));
    }

    @Test
    void malformedWrongTypeAndUnreachableSelectionsNeverFallback() {
        for (String selection : List.of("abc", "+1", "01", "9223372036854775808", id(SOURCE), id(UNREACHABLE), id(SOURCE.below()))) {
            assertRejected(start(SOURCE, selection, false), "server_unavailable");
            assertNull(graph.getSessionByDeviceId(TrafficSession.routerDeviceId(SOURCE)));
        }
        graph.addNode(new NetworkNode(SLOW, NetworkNode.NodeType.NRO));
        assertRejected(start(SOURCE, id(SLOW), false), "server_unavailable");
        graph.removeEdgeBetween(SOURCE, FAST);
        assertRejected(start(SOURCE, "", false), "no_server");
    }

    @Test
    void invalidRequestsAndDuplicateStartsPreserveExistingTrafficAndRemainSilent() {
        TrafficSession passive = start(SOURCE, "", true).session();
        assertRejected(graph.startSpeedtest(SOURCE, "ip", 100, 100, 0, 0, 0, false, null, id(SLOW)), "invalid_request");
        assertRejected(start(null, "", false), "invalid_request");
        assertRejected(start(SOURCE.below(), "", false), "invalid_request");
        assertRejected(start(SOURCE, null, false), "invalid_request");
        assertSame(passive, graph.getSessionByDeviceId(passive.getDeviceId()));
        TrafficSession manual = start(SOURCE, id(SLOW), false).session();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        try (MockedStatic<PacketDistributor> packets = mockStatic(PacketDistributor.class)) {
            assertRejected(graph.startSpeedtest(SOURCE, "changed-ip", 100, 100, 0, 0, 2, false, player, id(FAST)), "device_busy");
            verify(player, never()).sendSystemMessage(any());
            packets.verifyNoInteractions();
        }
        assertSame(manual, graph.getSessionByDeviceId(manual.getDeviceId()));
        assertEquals(id(SLOW), manual.getServerId());
    }

    @Test
    void sessionLimitStillAllowsReplacingOnlyTheValidatedPassiveDevice() {
        TrafficSession passive = start(SOURCE, "", true).session();
        BlockPos antenna = SOURCE.above();
        graph.addNode(new NetworkNode(antenna, NetworkNode.NodeType.ANTENNA));
        graph.addEdge(new NetworkEdge(antenna, SOURCE, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
        for (int i = 0; i < 255; i++) {
            assertTrue(graph.startSpeedtest(antenna, "phone-" + i, 100, 100, 0, 0, 2, true, null, "").accepted());
        }
        assertRejected(graph.startSpeedtest(antenna, "extra", 100, 100, 0, 0, 2, false, null, ""), "session_limit");
        assertRejected(start(SOURCE, id(UNREACHABLE), false), "server_unavailable");
        assertSame(passive, graph.getSessionByDeviceId(passive.getDeviceId()));
        assertTrue(start(SOURCE, id(SLOW), false).accepted());
        assertNotNull(graph.getSessionByDeviceId("mobile-ip:phone-0"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 4, 5})
    void routeLossInEveryPhaseFailsWithoutSwitchingServer(int ticksBeforeCut) {
        TrafficSession session = start(SOURCE, id(SLOW), false).session();
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.players()).thenReturn(List.of());
        for (int i = 0; i < ticksBeforeCut; i++) graph.tickTraffic(level);
        graph.removeEdgeBetween(SOURCE, SLOW);
        graph.tickTraffic(level);
        assertEquals(TrafficSession.SessionState.FAILED, session.getState());
        assertEquals("route_lost", session.getFailureReason());
        assertEquals(id(SLOW), session.getServerId());
        var result = graph.getLastResultByDeviceId(session.getDeviceId());
        assertEquals(id(SLOW), result.serverId());
        assertEquals(session.getServerName(), result.serverName());
        assertEquals("route_lost", result.errorCode());
        assertNull(graph.getSessionByDeviceId(session.getDeviceId()));
        verify(level, never()).getBlockEntity(any());
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serverRemovalOrTypeChangeFailsEvenIfAnotherServerRemains(boolean changeType) {
        TrafficSession session = start(SOURCE, id(SLOW), false).session();
        if (changeType) graph.addNode(new NetworkNode(SLOW, NetworkNode.NodeType.NRO));
        else graph.removeNode(SLOW);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.players()).thenReturn(List.of());
        graph.tickTraffic(level);
        assertEquals("route_lost", session.getFailureReason());
        assertEquals(id(SLOW), graph.getLastResultByDeviceId(session.getDeviceId()).serverId());
    }

    private void server(BlockPos pos, int length) {
        graph.addNode(new NetworkNode(pos, NetworkNode.NodeType.SERVER));
        graph.addEdge(new NetworkEdge(SOURCE, pos, 10_000, length, NetworkEdge.EdgeType.FIBER, List.of()));
    }

    private TelecomNetworkGraph.SpeedtestStartResult start(BlockPos source, String selection, boolean passive) {
        return graph.startSpeedtest(source, "ip", 100, 100, 0, 0, 2, passive, null, selection);
    }

    private static String id(BlockPos pos) { return Long.toString(pos.asLong()); }

    private static void assertRejected(TelecomNetworkGraph.SpeedtestStartResult result, String error) {
        assertFalse(result.accepted());
        assertNull(result.session());
        assertEquals(error, result.error());
    }
}
