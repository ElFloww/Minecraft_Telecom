package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MultiDeviceSpeedtestTest {
    private static final BlockPos ROUTER = new BlockPos(-12, 64, 8);
    private static final BlockPos OTHER_ROUTER = ROUTER.north();
    private static final BlockPos ANTENNA = new BlockPos(0, 70, 0);
    private static final BlockPos SERVER = new BlockPos(12, 64, 8);
    private TelecomNetworkGraph graph;
    private ServerLevel level;
    private PlayerList players;

    @BeforeEach
    void setup() {
        graph = new TelecomNetworkGraph();
        graph.addNode(new NetworkNode(SERVER, NetworkNode.NodeType.SERVER));
        level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        players = mock(PlayerList.class);
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.players()).thenReturn(List.of());
    }

    @Test
    void deviceIdentitiesAreStableAndSessionIdsAreUnique() {
        assertEquals("router:" + ROUTER.asLong(), TrafficSession.routerDeviceId(ROUTER));
        UUID owner = UUID.randomUUID();
        assertEquals("mobile:" + owner, TrafficSession.mobileDeviceId(owner));
        connect(ROUTER, NetworkNode.NodeType.ROUTER);
        connect(ANTENNA, NetworkNode.NodeType.ANTENNA);
        start(ROUTER, "same-ip", false, null);
        start(ANTENNA, "same-ip", false, null);
        assertNotEquals(router(ROUTER).getSessionId(), graph.getSessionByDeviceId("mobile-ip:same-ip").getSessionId());
    }

    @Test
    void sameOwnerCanRunTwoRoutersAndPhoneEvenWithTheSameIp() {
        connect(ROUTER, NetworkNode.NodeType.ROUTER);
        connect(OTHER_ROUTER, NetworkNode.NodeType.ROUTER);
        connect(ANTENNA, NetworkNode.NodeType.ANTENNA);
        ServerPlayer owner = player();
        start(ROUTER, "10.0.0.2", false, owner);
        start(OTHER_ROUTER, "10.0.0.2", false, owner);
        start(ANTENNA, "10.0.0.2", false, owner);
        assertNotNull(router(ROUTER));
        assertNotNull(router(OTHER_ROUTER));
        assertNotNull(mobile(owner));
        assertNotSame(router(ROUTER), router(OTHER_ROUTER));
        verify(owner, never()).sendSystemMessage(any());
    }

    @Test
    void changingIpDoesNotAllowASecondTestOnTheSameRouter() {
        connect(ROUTER, NetworkNode.NodeType.ROUTER);
        start(ROUTER, "old-ip", false, null);
        TrafficSession first = router(ROUTER);
        start(ROUTER, "new-ip", false, null);
        assertSame(first, router(ROUTER));
        assertNull(graph.getSessionByIp("new-ip"));
    }

    @Test
    void differentPlayersOnOneAntennaHaveDistinctDevicesButOnePlayerCannotDuplicate() {
        connect(ANTENNA, NetworkNode.NodeType.ANTENNA);
        ServerPlayer first = player();
        ServerPlayer second = player();
        start(ANTENNA, "same-ip", false, first);
        start(ANTENNA, "same-ip", false, second);
        TrafficSession initial = mobile(first);
        assertNotNull(initial);
        assertNotNull(mobile(second));
        assertNotSame(initial, mobile(second));
        try (MockedStatic<PacketDistributor> packets = mockStatic(PacketDistributor.class)) {
            start(ANTENNA, "changed-ip", false, first);
            assertSame(initial, mobile(first));
            packets.verify(() -> PacketDistributor.sendToPlayer(eq(first), argThat(payload ->
                    payload instanceof SpeedtestUpdatePayload update && update.state().equals("REJECTED")
                            && update.deviceId().equals(initial.getDeviceId()) && update.sessionId() != null)));
        }
    }

    @Test
    void manualOnlyReplacesPassiveOnItsOwnDeviceNotOtherDevicesWithSameIp() {
        connect(ROUTER, NetworkNode.NodeType.ROUTER);
        connect(OTHER_ROUTER, NetworkNode.NodeType.ROUTER);
        start(ROUTER, "same-ip", true, null);
        start(OTHER_ROUTER, "same-ip", true, null);
        TrafficSession first = router(ROUTER);
        TrafficSession other = router(OTHER_ROUTER);
        start(ROUTER, "new-ip", false, null);
        assertNotSame(first, router(ROUTER));
        assertFalse(router(ROUTER).isPassive());
        assertSame(other, router(OTHER_ROUTER));
        TrafficSession manual = router(ROUTER);
        start(ROUTER, "passive-ip", true, null);
        assertSame(manual, router(ROUTER));
    }

    @Test
    void passiveMobileUsesOwnerIdentityAndRemainsSilentIncludingRejectionsAndCompletion() {
        connect(ANTENNA, NetworkNode.NodeType.ANTENNA);
        ServerPlayer owner = player();
        try (MockedStatic<PacketDistributor> packets = mockStatic(PacketDistributor.class)) {
            start(ANTENNA, "old-ip", true, owner);
            TrafficSession initial = mobile(owner);
            assertNull(graph.getLatestSessionByDeviceId(initial.getDeviceId()));
            start(ANTENNA, "new-ip", true, owner);
            assertSame(initial, mobile(owner));
            graph.startSpeedtest(ANTENNA, "invalid", -1, 100, 0, 1, 2, true, owner);
            tick(6);
            assertEquals(TrafficSession.SessionState.FINISHED, initial.getState());
            assertNull(graph.getLastResultByDeviceId(initial.getDeviceId()));
            assertNull(graph.getLatestSessionByDeviceId(initial.getDeviceId()));
            verify(owner, never()).sendSystemMessage(any());
            packets.verifyNoInteractions();
        }
    }

    @Test
    void manualCanReplaceItsPassiveDeviceEvenAtTheGlobalLimit() {
        connect(ANTENNA, NetworkNode.NodeType.ANTENNA);
        connect(ROUTER, NetworkNode.NodeType.ROUTER);
        start(ROUTER, "same-ip", true, null);
        for (int i = 0; i < 255; i++) start(ANTENNA, "mobile-" + i, true, null);
        TrafficSession other = graph.getSessionByDeviceId("mobile-ip:mobile-0");
        start(ROUTER, "same-ip", false, null);
        assertFalse(router(ROUTER).isPassive());
        assertSame(other, graph.getSessionByDeviceId("mobile-ip:mobile-0"));
        start(ANTENNA, "over-limit", false, null);
        assertNull(graph.getSessionByDeviceId("mobile-ip:over-limit"));
    }

    @Test
    void disconnectedOwnerDoesNotCancelRouterButDoesCancelMobile() {
        connect(ROUTER, NetworkNode.NodeType.ROUTER);
        connect(ANTENNA, NetworkNode.NodeType.ANTENNA);
        ServerPlayer owner = player();
        start(ROUTER, "router", false, owner);
        start(ANTENNA, "phone", false, owner);
        TrafficSession router = router(ROUTER);
        TrafficSession phone = mobile(owner);
        when(players.getPlayer(owner.getUUID())).thenReturn(null);
        tick(1);
        assertSame(router, router(ROUTER));
        assertNull(mobile(owner));
        assertEquals(TrafficSession.SessionState.FAILED, phone.getState());
        assertEquals("FAILED", graph.getLastResultByDeviceId(phone.getDeviceId()).state());
        assertEquals("device_unavailable", phone.getFailureReason());
        assertEquals("device_unavailable", graph.getLastResultByDeviceId(phone.getDeviceId()).errorCode());
        tick(5);
        assertEquals("FINISHED", graph.getLastResultByDeviceId(router.getDeviceId()).state());
        verify(level, never()).getBlockEntity(any());
    }

    @Test
    void closedGuiAndChangedDimensionDoNotPreventProgressOrFinalNotification() {
        connect(ROUTER, NetworkNode.NodeType.ROUTER);
        ServerPlayer owner = player();
        ServerLevel otherDimension = mock(ServerLevel.class);
        when(otherDimension.dimension()).thenReturn(Level.NETHER);
        start(ROUTER, "router", false, owner);
        TrafficSession session = router(ROUTER);
        when(owner.level()).thenReturn(otherDimension);
        List<SpeedtestUpdatePayload> updates = new ArrayList<>();
        try (MockedStatic<PacketDistributor> packets = mockStatic(PacketDistributor.class)) {
            packets.when(() -> PacketDistributor.sendToPlayer(eq(owner), any(SpeedtestUpdatePayload.class)))
                    .thenAnswer(call -> { updates.add(call.getArgument(1)); return null; });
            tick(6);
        }
        assertTrue(updates.stream().anyMatch(update -> update.state().equals("DOWNLOAD")));
        assertTrue(updates.stream().anyMatch(update -> update.state().equals("UPLOAD")));
        SpeedtestUpdatePayload finished = updates.getLast();
        assertEquals("FINISHED", finished.state());
        assertTrue(finished.downloadBandwidth() > 0);
        assertTrue(finished.uploadBandwidth() > 0);
        for (SpeedtestUpdatePayload update : updates) {
            assertEquals(session.getDeviceId(), update.deviceId());
            assertEquals(session.getSessionId(), update.sessionId());
            assertEquals("minecraft:overworld", update.dimension());
            assertEquals(session.getServerId(), update.serverId());
            assertEquals(session.getServerName(), update.serverName());
            assertEquals("", update.errorCode());
        }
        assertEquals(session.getFinalDownBw(), finished.downloadBandwidth());
        assertEquals(session.getFinalUpBw(), finished.uploadBandwidth());
        verify(level, never()).getPlayerByUUID(any());
    }

    @Test
    void routerResultsUseSourcePositionInsteadOfOldOrDuplicateIp() {
        connect(ROUTER, NetworkNode.NodeType.ROUTER);
        connect(OTHER_ROUTER, NetworkNode.NodeType.ROUTER);
        graph.getNode(ROUTER).setIpAddress("duplicate");
        graph.getNode(OTHER_ROUTER).setIpAddress("duplicate");
        RouterBlockEntity first = mock(RouterBlockEntity.class);
        RouterBlockEntity second = mock(RouterBlockEntity.class);
        when(level.hasChunkAt(ROUTER)).thenReturn(true);
        when(level.hasChunkAt(OTHER_ROUTER)).thenReturn(true);
        when(level.getBlockEntity(ROUTER)).thenReturn(first);
        when(level.getBlockEntity(OTHER_ROUTER)).thenReturn(second);
        start(ROUTER, "duplicate", false, null);
        start(OTHER_ROUTER, "duplicate", false, null);
        TrafficSession firstSession = router(ROUTER);
        TrafficSession secondSession = router(OTHER_ROUTER);
        graph.getNode(ROUTER).setIpAddress("reassigned");
        tick(6);
        verify(first).setLastSpeedtestResults(firstSession.getFinalDownBw(), firstSession.getFinalUpBw(), firstSession.getPingMs());
        verify(second).setLastSpeedtestResults(secondSession.getFinalDownBw(), secondSession.getFinalUpBw(), secondSession.getPingMs());
    }

    @Test
    void brokenPathSendsFailureEvenOnTheFinishingTickAndKeepsIdentityAndResults() {
        connect(ROUTER, NetworkNode.NodeType.ROUTER);
        ServerPlayer owner = player();
        start(ROUTER, "router", false, owner);
        TrafficSession session = router(ROUTER);
        try (MockedStatic<PacketDistributor> packets = mockStatic(PacketDistributor.class)) {
            tick(5);
            graph.removeEdgeBetween(ROUTER, SERVER);
            tick(1);
            assertNull(router(ROUTER));
            SpeedtestUpdatePayload failure = graph.getLastResultByDeviceId(session.getDeviceId());
            assertSame(session, graph.getLatestSessionByDeviceId(session.getDeviceId()));
            assertEquals("FAILED", graph.getLatestSessionByDeviceId(session.getDeviceId()).getState().name());
            assertEquals("FAILED", failure.state());
            assertEquals(session.getSessionId(), failure.sessionId());
            assertTrue(failure.downloadBandwidth() > 0);
            assertTrue(failure.uploadBandwidth() > 0);
            packets.verify(() -> PacketDistributor.sendToPlayer(owner, failure));
            session.tick();
            assertEquals(TrafficSession.SessionState.FAILED, session.getState());
        }
        verify(level, never()).getBlockEntity(any());
    }

    @Test
    void sharedLinkCapsTheSumOfBothDownloadsAndBothUploadsWithoutPathBlocks() {
        assertSharedCapacity(false);
    }

    @Test
    void sharedPhysicalBlocksCapTheSumOfBothDownloadsAndBothUploads() {
        assertSharedCapacity(true);
    }

    @Test
    void latestSessionPrefersActiveManualThenLastManualAndIgnoresPassiveTraffic() {
        connect(ANTENNA, NetworkNode.NodeType.ANTENNA);
        String deviceId = "mobile-ip:phone";
        assertNull(graph.getLatestSessionByDeviceId(deviceId));
        start(ANTENNA, "phone", false, null);
        TrafficSession manual = graph.getSessionByDeviceId(deviceId);
        assertSame(manual, graph.getLatestSessionByDeviceId(deviceId));
        tick(6);
        assertNull(graph.getSessionByDeviceId(deviceId));
        assertSame(manual, graph.getLatestSessionByDeviceId(deviceId));
        assertEquals(TrafficSession.SessionState.FINISHED, manual.getState());
        SpeedtestUpdatePayload result = graph.getLastResultByDeviceId(deviceId);

        start(ANTENNA, "phone", true, null);
        TrafficSession passive = graph.getSessionByDeviceId(deviceId);
        assertTrue(passive.isPassive());
        assertSame(manual, graph.getLatestSessionByDeviceId(deviceId));
        assertSame(result, graph.getLastResultByDeviceId(deviceId));
        tick(6);
        assertSame(manual, graph.getLatestSessionByDeviceId(deviceId));
        assertSame(result, graph.getLastResultByDeviceId(deviceId));

        start(ANTENNA, "phone", false, null);
        TrafficSession next = graph.getSessionByDeviceId(deviceId);
        assertNotSame(manual, next);
        assertSame(next, graph.getLatestSessionByDeviceId(deviceId));
        graph.removeEdgeBetween(ANTENNA, SERVER);
        tick(1);
        assertSame(next, graph.getLatestSessionByDeviceId(deviceId));
        assertEquals(TrafficSession.SessionState.FAILED, next.getState());
        assertEquals(next.getSessionId(), graph.getLastResultByDeviceId(deviceId).sessionId());
        verify(level, never()).getBlockEntity(any());
    }

    @Test
    void terminalResultCacheIsBoundedTo256DevicesWithoutChunkLoads() {
        connect(ANTENNA, NetworkNode.NodeType.ANTENNA);
        for (int i = 0; i < 257; i++) {
            graph.startSpeedtest(ANTENNA, "phone-" + i, 100, 100, 0, 1, 1, false, null);
            tick(3);
        }
        assertNull(graph.getLastResultByDeviceId("mobile-ip:phone-0"));
        assertNull(graph.getLatestSessionByDeviceId("mobile-ip:phone-0"));
        for (int i = 1; i < 257; i++) {
            assertEquals("FINISHED", graph.getLastResultByDeviceId("mobile-ip:phone-" + i).state());
            assertEquals(TrafficSession.SessionState.FINISHED,
                    graph.getLatestSessionByDeviceId("mobile-ip:phone-" + i).getState());
        }
        TelecomNetworkGraph restored = TelecomNetworkGraph.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,
                TelecomNetworkGraph.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, graph).getOrThrow()).getOrThrow();
        assertNull(restored.getLatestSessionByDeviceId("mobile-ip:phone-256"));
        assertNull(restored.getLastResultByDeviceId("mobile-ip:phone-256"));
        verify(level, never()).getBlockEntity(any());
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    private void assertSharedCapacity(boolean sharedPhysicalBlocks) {
        graph.addNode(new NetworkNode(ROUTER, NetworkNode.NodeType.ROUTER));
        graph.addNode(new NetworkNode(OTHER_ROUTER, NetworkNode.NodeType.ROUTER));
        NetworkEdge bottleneck;
        if (sharedPhysicalBlocks) {
            bottleneck = new NetworkEdge(ROUTER, SERVER, 100, 1, NetworkEdge.EdgeType.FIBER, List.of(ANTENNA));
            graph.addEdge(bottleneck);
            graph.addEdge(new NetworkEdge(OTHER_ROUTER, SERVER, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of(ANTENNA)));
        } else {
            graph.addNode(new NetworkNode(ANTENNA, NetworkNode.NodeType.ANTENNA));
            graph.addEdge(new NetworkEdge(ROUTER, ANTENNA, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
            graph.addEdge(new NetworkEdge(OTHER_ROUTER, ANTENNA, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
            bottleneck = new NetworkEdge(ANTENNA, SERVER, 100, 1, NetworkEdge.EdgeType.FIBER, List.of());
            graph.addEdge(bottleneck);
        }
        start(ROUTER, "same-ip", false, null);
        start(OTHER_ROUTER, "same-ip", false, null);
        TrafficSession first = router(ROUTER);
        TrafficSession second = router(OTHER_ROUTER);
        tick(2);
        for (TrafficSession.SessionState phase : List.of(TrafficSession.SessionState.DOWNLOAD, TrafficSession.SessionState.UPLOAD)) {
            assertEquals(phase, first.getState());
            assertEquals(phase, second.getState());
            assertTrue(first.getActualBandwidth() > 0);
            assertTrue(second.getActualBandwidth() > 0);
            assertTrue(first.getActualBandwidth() + second.getActualBandwidth() <= 100);
            int usage = phase == TrafficSession.SessionState.DOWNLOAD ? graph.getTotalBandwidthDown() : graph.getTotalBandwidthUp();
            assertEquals(first.getActualBandwidth() + second.getActualBandwidth(), usage);
            assertTrue(bottleneck.getCurrentUsageDown() + bottleneck.getCurrentUsageUp() <= 100);
            tick(2);
        }
    }

    private void connect(BlockPos pos, NetworkNode.NodeType type) {
        graph.addNode(new NetworkNode(pos, type));
        graph.addEdge(new NetworkEdge(pos, SERVER, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
    }

    private ServerPlayer player() {
        ServerPlayer player = mock(ServerPlayer.class);
        UUID id = UUID.randomUUID();
        when(player.getUUID()).thenReturn(id);
        when(player.level()).thenReturn(level);
        when(players.getPlayer(id)).thenReturn(player);
        return player;
    }

    private void start(BlockPos pos, String ip, boolean passive, ServerPlayer owner) {
        graph.startSpeedtest(pos, ip, 100, 100, 0, 0, 2, passive, owner);
    }

    private TrafficSession router(BlockPos pos) {
        return graph.getSessionByDeviceId(TrafficSession.routerDeviceId(pos));
    }

    private TrafficSession mobile(ServerPlayer player) {
        return graph.getSessionByDeviceId(TrafficSession.mobileDeviceId(player.getUUID()));
    }

    private void tick(int count) {
        for (int i = 0; i < count; i++) graph.tickTraffic(level);
    }
}
