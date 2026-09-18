package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RadioAllocationTest {
    private static final BlockPos ANTENNA = new BlockPos(0, 64, 0);
    private static final BlockPos SECOND = new BlockPos(10, 64, 0);
    private static final BlockPos SERVER = new BlockPos(20, 64, 0);
    private static final TelecomFrequency LOW = TelecomFrequency.G4_900;
    private static final TelecomFrequency HIGH = TelecomFrequency.G4_1800;
    private TelecomNetworkGraph graph;
    private ServerLevel level;
    private NetworkEdge backhaul;

    @BeforeEach
    void setup() {
        graph = new TelecomNetworkGraph();
        level = mock(ServerLevel.class);
        when(level.players()).thenReturn(List.of());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        graph.addNode(new NetworkNode(SERVER, NetworkNode.NodeType.SERVER));
        antenna(ANTENNA);
        backhaul = graph.getEdges().getFirst();
    }

    @Test
    void sameBandSharesCapacityButDifferentBandsAreIndependent() {
        TrafficSession first = start(ANTENNA, "a", mask(LOW), false);
        TrafficSession second = start(ANTENNA, "b", mask(LOW), false);
        TrafficSession otherBand = start(ANTENNA, "c", mask(HIGH), false);
        tick();
        assertEquals(cap(LOW), first.getActualBandwidth() + second.getActualBandwidth());
        assertTrue(Math.abs(first.getActualBandwidth() - second.getActualBandwidth()) <= 1);
        assertEquals(cap(HIGH), otherBand.getActualBandwidth());
        assertEquals(cap(LOW) + cap(HIGH), graph.getTotalBandwidthDown());
        assertEquals(graph.getTotalBandwidthDown(), backhaul.getCurrentUsageDown());
    }

    @Test
    void sameFrequencyOnDifferentAntennasDoesNotShareRadioBudget() {
        antenna(SECOND);
        TrafficSession first = start(ANTENNA, "a", mask(LOW), false);
        TrafficSession second = start(SECOND, "b", mask(LOW), false);
        tick();
        assertEquals(cap(LOW), first.getActualBandwidth());
        assertEquals(cap(LOW), second.getActualBandwidth());
    }

    @Test
    void carrierAggregationIsAdditiveAndTelemetryTracksFixedSplitGrants() {
        TrafficSession session = start(ANTENNA, "a", mask(LOW, HIGH), false);
        tick();
        assertEquals(cap(LOW) + cap(HIGH), session.getActualBandwidth());
        var stats = graph.getAntennaUtilization(ANTENNA);
        assertEquals(cap(LOW), stats.get(LOW).actualMbps());
        assertEquals(cap(HIGH), stats.get(HIGH).actualMbps());
        assertEquals(cap(LOW), stats.get(LOW).maxMbps());
        assertEquals(1f, stats.get(HIGH).utilizationPercent());
    }

    @Test
    void receptionCeilingsDetermineSplitRatherThanNominalBandCapacities() {
        TrafficSession session = start(ANTENNA, "a", mask(LOW, HIGH), false);
        session.updateRadioAttachment(ANTENNA, mask(LOW, HIGH), Map.of(LOW, 10, HIGH, 10), Map.of(LOW, 2, HIGH, 2));
        tick();
        assertEquals(20, session.getActualBandwidth());
        assertEquals(10, graph.getAntennaUtilization(ANTENNA).get(LOW).actualMbps());
        assertEquals(10, graph.getAntennaUtilization(ANTENNA).get(HIGH).actualMbps());
    }

    @Test
    void configuredBandwidthChangeShrinksAllocationAndReportedMaximumImmediately() {
        TrafficSession session = start(ANTENNA, "a", mask(LOW, HIGH), false);
        tick();
        int original = session.getActualBandwidth();
        graph.getNode(ANTENNA).setRadioConfig(new AntennaRadioConfig(0, 0, 0, 30, 50));
        tick();
        assertEquals(original / 2, session.getActualBandwidth());
        assertEquals(cap(LOW), graph.getAntennaUtilization(ANTENNA).get(LOW).maxMbps());
        assertEquals(cap(HIGH), graph.getAntennaUtilization(ANTENNA).get(HIGH).actualMbps());
    }

    @Test
    void uploadSplitUsesUploadCeilingsNotTheDownloadSplit() {
        TrafficSession session = start(ANTENNA, "a", mask(LOW, HIGH), true);
        session.updateRadioAttachment(ANTENNA, mask(LOW, HIGH), Map.of(LOW, 10, HIGH, 30), Map.of(LOW, 3, HIGH, 3));
        tick();
        assertEquals(6, session.getActualBandwidth());
        assertEquals(10, graph.getAntennaUtilization(ANTENNA).get(LOW).actualMbps());
        assertEquals(10, graph.getAntennaUtilization(ANTENNA).get(HIGH).actualMbps());
    }

    @Test
    void fixedSplitIntentionallyDoesNotDynamicallyMoveTrafficToAnIdleCarrier() {
        TrafficSession aggregate = start(ANTENNA, "aggregate", mask(LOW, HIGH), false);
        TrafficSession single = start(ANTENNA, "single", mask(LOW), false);
        tick();
        assertEquals(40, aggregate.getActualBandwidth());
        assertEquals(40, single.getActualBandwidth());
        assertEquals(30, graph.getAntennaUtilization(ANTENNA).get(HIGH).actualMbps());
        assertEquals(50, graph.getAntennaUtilization(ANTENNA).get(LOW).actualMbps());
    }

    @Test
    void downAndUpShareOneNormalizedRadioBudget() {
        TrafficSession down = start(ANTENNA, "down", mask(HIGH), false);
        TrafficSession up = start(ANTENNA, "up", mask(HIGH), true);
        tick();
        double ratio = AntennaRadioConfig.uploadRatio(HIGH);
        double used = down.getActualBandwidth() + up.getActualBandwidth() / ratio;
        assertTrue(used <= cap(HIGH) + 1e-7);
        assertTrue(Math.abs(down.getActualBandwidth() - up.getActualBandwidth()) <= 1);
        assertTrue(down.getActualBandwidth() > 0);
        assertEquals((int) Math.floor(used + 1e-7), graph.getAntennaUtilization(ANTENNA).get(HIGH).actualMbps());
        assertEquals(up.getActualBandwidth(), graph.getTotalBandwidthUp());
        assertEquals(down.getActualBandwidth() + up.getActualBandwidth(), backhaul.getCurrentUsage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoGUploadRetainsOneMbpsAndSharesRoundedAirtimeWithoutStarvation(boolean configured) {
        TelecomFrequency band = TelecomFrequency.G2_900;
        graph.getNode(ANTENNA).setFrequenciesMask(mask(band));
        TrafficSession up = start(ANTENNA, "up", mask(band), true);
        if (configured) up.updateRadioAttachment(ANTENNA, mask(band), Map.of(band, 1), Map.of(band, 1));
        tick();
        assertEquals(1, up.getActualBandwidth());
        assertEquals(1, graph.getTotalBandwidthUp());
        assertEquals(1, graph.getAntennaUtilization(ANTENNA).get(band).actualMbps());
        TrafficSession down = start(ANTENNA, "down", mask(band), false);
        int downTotal = 0;
        int upTotal = 0;
        for (int t = 0; t < 20; t++) {
            tick();
            downTotal += down.getActualBandwidth();
            upTotal += up.getActualBandwidth();
            assertEquals(1, down.getActualBandwidth() + up.getActualBandwidth());
            assertEquals(1, graph.getAntennaUtilization(ANTENNA).get(band).actualMbps());
        }
        assertEquals(10, downTotal);
        assertEquals(10, upTotal);
    }

    @ParameterizedTest
    @EnumSource(value = TelecomFrequency.class, names = {"G2_900", "G3_2100", "G4_900"})
    void uploadUsesRoundedNominalCapacityForBothCeilingAndAirtime(TelecomFrequency band) {
        graph.getNode(ANTENNA).setFrequenciesMask(mask(band));
        graph.getNode(ANTENNA).setRadioConfig(new AntennaRadioConfig(0, 0, 0, 30, 25));
        TrafficSession up = start(ANTENNA, "up", mask(band), true);
        int nominalDown = cap(band);
        int nominalUp = Math.max(1, (int) Math.floor(nominalDown * AntennaRadioConfig.uploadRatio(band)));
        tick();
        assertEquals(nominalUp, up.getActualBandwidth());
        assertEquals(nominalDown, graph.getAntennaUtilization(ANTENNA).get(band).actualMbps());
        assertEquals(1f, graph.getAntennaUtilization(ANTENNA).get(band).utilizationPercent());
    }

    @Test
    void wiredBranchBottleneckRedistributesSharedRadioCapacity() {
        BlockPos slowServer = SERVER.east();
        graph.addNode(new NetworkNode(slowServer, NetworkNode.NodeType.SERVER));
        graph.addEdge(new NetworkEdge(ANTENNA, slowServer, 10, 1, NetworkEdge.EdgeType.FIBER, List.of()));
        TrafficSession slow = start(ANTENNA, "slow", mask(HIGH), false, slowServer);
        TrafficSession fast = start(ANTENNA, "fast", mask(HIGH), false);
        tick();
        assertEquals(10, slow.getActualBandwidth());
        assertEquals(cap(HIGH) - 10, fast.getActualBandwidth());
    }

    @Test
    void antennaNodeBudgetRemainsAnIndependentBackhaulLimit() {
        graph.getNode(ANTENNA).setCapacityDown(30);
        TrafficSession first = start(ANTENNA, "a", mask(LOW), false);
        TrafficSession second = start(ANTENNA, "b", mask(HIGH), false);
        tick();
        assertEquals(15, first.getActualBandwidth());
        assertEquals(15, second.getActualBandwidth());
        assertEquals(30, graph.getNode(ANTENNA).getCurrentUsageDown());
    }

    @Test
    void disablingBandsImmediatelyRemovesStaleCeilingsAndEventuallyFailsRadio() {
        TrafficSession session = start(ANTENNA, "a", mask(LOW, HIGH), false);
        session.updateRadioAttachment(ANTENNA, mask(LOW, HIGH), Map.of(LOW, cap(LOW), HIGH, cap(HIGH)), Map.of(LOW, 1, HIGH, 1));
        tick();
        graph.getNode(ANTENNA).setFrequenciesMask(mask(LOW));
        tick();
        assertEquals(cap(LOW), session.getActualBandwidth());
        assertEquals(mask(LOW), session.getFrequenciesMask());
        assertFalse(session.getRadioDownCaps().containsKey(HIGH));
        assertFalse(graph.getAntennaUtilization(ANTENNA).containsKey(HIGH));
        graph.getNode(ANTENNA).setFrequenciesMask(0);
        tick();
        assertEquals(TrafficSession.SessionState.FAILED, session.getState());
        assertEquals("radio_lost", session.getFailureReason());
        assertEquals(0, session.getActualBandwidth());
    }

    @Test
    void configuredEmptyCapsNeverFallBackToNominalAndZeroMaskSyntheticRemainsWired() {
        TrafficSession radio = start(ANTENNA, "radio", mask(LOW), false);
        radio.updateRadioAttachment(ANTENNA, mask(LOW), Map.of(), Map.of());
        TrafficSession wired = start(ANTENNA, "wired", 0, false);
        tick();
        assertEquals("radio_lost", radio.getFailureReason());
        assertEquals(10_000, wired.getActualBandwidth());
    }

    @Test
    void fractionalTelemetryAggregatesBeforeRoundingAndResetClearsIt() {
        for (int i = 0; i < 2; i++) {
            var result = graph.startSpeedtest(ANTENNA, "small" + i, 1, 1, 0, mask(LOW, HIGH), 1000, true, null, "");
            var session = result.session();
            session.updateRadioAttachment(ANTENNA, mask(LOW, HIGH), Map.of(LOW, 1, HIGH, 1), Map.of(LOW, 1, HIGH, 1));
            for (int t = 0; t < 60; t++) session.tick();
        }
        tick();
        assertEquals(1, graph.getAntennaUtilization(ANTENNA).get(LOW).actualMbps());
        assertEquals(1, graph.getAntennaUtilization(ANTENNA).get(HIGH).actualMbps());
        graph.markForRecalculation();
        try (var tracer = mockStatic(NetworkTracer.class)) {
            tick();
            assertEquals(0, graph.getAntennaUtilization(ANTENNA).get(LOW).actualMbps());
        }
    }

    @Test
    void full256HandsetsAggregateWithoutExceedingRequestLimitOrStarving() {
        List<TrafficSession> sessions = new java.util.ArrayList<>();
        for (int i = 0; i < 256; i++) sessions.add(start(ANTENNA, "handset" + i, mask(LOW, HIGH), false));
        for (int t = 0; t < 4; t++) tick();
        assertEquals(256, sessions.size());
        for (TrafficSession session : sessions) assertTrue(session.getFinalDownBw() > 0);
        assertEquals(cap(LOW) + cap(HIGH), graph.getTotalBandwidthDown());
    }

    @Test
    void radioReferencesCountAgainstQuotaBeforeRetainingRequests() {
        List<BlockPos> blocks = new java.util.ArrayList<>();
        int halfQuota = TelecomNetworkGraph.MAX_ALLOCATION_RESOURCE_REFERENCES / 2;
        // Two wired-only requests fit exactly, but their extra radio references do not.
        for (int i = 0; i < halfQuota - 3; i++) blocks.add(new BlockPos(i + 100, 0, 0));
        graph.clearEdges();
        graph.addEdge(new NetworkEdge(ANTENNA, SERVER, 10_000, 1, NetworkEdge.EdgeType.FIBER, blocks));
        TrafficSession first = start(ANTENNA, "a", mask(LOW, HIGH), false);
        TrafficSession overflow = start(ANTENNA, "b", mask(LOW, HIGH), false);
        tick();
        assertEquals(cap(LOW) + cap(HIGH), first.getActualBandwidth());
        assertEquals("network_limit", overflow.getFailureReason());
        assertNull(graph.getSessionByDeviceId(overflow.getDeviceId()));
        assertEquals(first.getActualBandwidth(), graph.getTotalBandwidthDown());
        assertEquals(cap(LOW), graph.getAntennaUtilization(ANTENNA).get(LOW).actualMbps());
    }

    @Test
    void actualOwnerMustExistAndStayInTheGraphsDimension() {
        TrafficSession missing = start(ANTENNA, "missing", mask(LOW), false);
        TrafficSession moved = start(ANTENNA, "moved", mask(HIGH), false);
        UUID missingId = UUID.randomUUID();
        UUID movedId = UUID.randomUUID();
        missing.setOwnerId(missingId);
        moved.setOwnerId(movedId);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel nether = mock(ServerLevel.class);
        when(nether.dimension()).thenReturn(Level.NETHER);
        when(player.level()).thenReturn(nether);
        when(players.getPlayer(movedId)).thenReturn(player);
        tick();
        assertEquals("device_unavailable", missing.getFailureReason());
        assertEquals("device_unavailable", moved.getFailureReason());
    }

    @Test
    void authoritativeScanUpdatesAttachmentBeforePathLookupWithoutReselectingDestination() {
        antenna(SECOND);
        TrafficSession session = start(ANTENNA, "mobile", mask(LOW), false);
        UUID owner = UUID.randomUUID();
        session.setOwnerId(owner);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(owner)).thenReturn(player);
        when(player.level()).thenReturn(level);
        var payload = new NetworkScanResponsePayload(true, "antenna", 4, "4G", "mobile", SECOND, 10, 2, mask(HIGH));
        var snapshot = new RadioAccessService.Snapshot(payload, Map.of(HIGH, 10), Map.of(HIGH, 2));
        UUID identity = session.getSessionId();
        int elapsed = session.getTicksElapsed();
        graph.clearEdges();
        graph.addEdge(new NetworkEdge(SECOND, SERVER, 10_000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
        try (var radio = mockStatic(RadioAccessService.class)) {
            radio.when(() -> RadioAccessService.scan(player)).thenReturn(snapshot);
            tick();
            assertEquals(SECOND, session.getSourcePos());
            assertEquals(SERVER, session.getDestPos());
            assertEquals(identity, session.getSessionId());
            assertEquals(elapsed + 1, session.getTicksElapsed());
            assertEquals(10, session.getActualBandwidth());
            var lost = new NetworkScanResponsePayload(false, "No Service", 0, "", "", BlockPos.ZERO, 0, 0, 0);
            radio.when(() -> RadioAccessService.scan(player)).thenReturn(new RadioAccessService.Snapshot(lost, Map.of(), Map.of()));
            radio.when(() -> RadioAccessService.unavailableReason(lost)).thenCallRealMethod();
            tick();
            assertEquals("radio_lost", session.getFailureReason());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void improvedCoverageRaisesManualDemandAndRefreshesLatencyWithoutRestarting(boolean passive, boolean upload) {
        TelecomFrequency low = TelecomFrequency.G2_900;
        TelecomFrequency high = TelecomFrequency.G5_3500;
        graph.getNode(ANTENNA).setFrequenciesMask(mask(low));
        antenna(SECOND);
        graph.getNode(SECOND).setFrequenciesMask(mask(high));
        UUID owner = UUID.randomUUID();
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(owner)).thenReturn(player);
        when(player.getUUID()).thenReturn(owner);
        when(player.level()).thenReturn(level);
        var result = graph.startSpeedtest(ANTENNA, "mobile", 1, 1, 347, mask(low), 1000, passive, player,
                Long.toString(SERVER.asLong()));
        assertTrue(result.accepted(), result.error());
        TrafficSession session = result.session();
        session.updateRadioAttachment(ANTENNA, mask(low), Map.of(low, 1), Map.of(low, 1));
        for (int i = 0; i < (upload ? 1060 : 60); i++) session.tick();
        UUID identity = session.getSessionId();
        var phase = session.getState();
        var initial = new NetworkScanResponsePayload(true, "low", 4, "2G", "mobile", ANTENNA, 1, 1, mask(low));
        var improved = new NetworkScanResponsePayload(true, "high", 4, "5G", "mobile", SECOND, 1000, 500, mask(high));
        try (var radio = mockStatic(RadioAccessService.class);
             var packets = mockStatic(net.neoforged.neoforge.network.PacketDistributor.class)) {
            radio.when(() -> RadioAccessService.scan(player)).thenReturn(
                    new RadioAccessService.Snapshot(initial, Map.of(low, 1), Map.of(low, 1)));
            tick();
            assertEquals(1, session.getActualBandwidth());
            assertEquals(347, session.getExtraPing());
            assertEquals(graph.calculatePathStats(ANTENNA, SERVER).pingMs() + 347, session.getPingMs());
            int elapsed = session.getTicksElapsed();
            radio.when(() -> RadioAccessService.scan(player)).thenReturn(
                    new RadioAccessService.Snapshot(improved, Map.of(high, 1000), Map.of(high, 500)));
            graph.clearEdges();
            graph.addEdge(new NetworkEdge(SECOND, SERVER, 10_000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
            for (int i = 1; i <= 3; i++) {
                tick();
                if (passive) assertEquals(1, session.getActualBandwidth());
                else assertTrue(session.getActualBandwidth() > 1);
                assertTrue(session.getActualBandwidth() <= (upload ? 500 : 1000));
                assertEquals(identity, session.getSessionId());
                assertEquals(SERVER, session.getDestPos());
                assertEquals(SECOND, session.getSourcePos());
                assertEquals(phase, session.getState());
                assertEquals(elapsed + i, session.getTicksElapsed());
                assertEquals(15, session.getExtraPing());
                assertEquals(graph.calculatePathStats(SECOND, SERVER).pingMs() + 15, session.getPingMs());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,No Service,radio_lost", "true,No Service,radio_lost",
            "false,Terrain unavailable,radio_unknown", "true,Terrain unavailable,radio_unknown",
            "false,Radio scan limit,radio_limit", "true,Radio scan limit,radio_limit"})
    void activeAndPassiveSessionsPreserveAuthoritativeScanFailureReason(boolean passive, String name, String reason) {
        UUID owner = UUID.randomUUID();
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(owner)).thenReturn(player);
        when(player.getUUID()).thenReturn(owner);
        when(player.level()).thenReturn(level);
        var session = graph.startSpeedtest(ANTENNA, "mobile", 100, 100, 0, mask(LOW), 1000, passive, player, "").session();
        assertNotNull(session);
        var lost = new NetworkScanResponsePayload(false, name, 0, "", "", BlockPos.ZERO, 0, 0, 0);
        try (var radio = mockStatic(RadioAccessService.class);
             var packets = mockStatic(net.neoforged.neoforge.network.PacketDistributor.class)) {
            radio.when(() -> RadioAccessService.scan(player)).thenReturn(new RadioAccessService.Snapshot(lost, Map.of(), Map.of()));
            radio.when(() -> RadioAccessService.unavailableReason(lost)).thenCallRealMethod();
            tick();
            assertEquals(reason, session.getFailureReason());
            assertEquals(TrafficSession.SessionState.FAILED, session.getState());
            assertEquals(0, session.getActualBandwidth());
            assertNull(graph.getSessionByDeviceId(session.getDeviceId()));
            if (passive) packets.verifyNoInteractions();
            else assertEquals(reason, graph.getLastResultByDeviceId(session.getDeviceId()).errorCode());
        }
    }

    private void antenna(BlockPos pos) {
        NetworkNode node = new NetworkNode(pos, NetworkNode.NodeType.ANTENNA);
        node.setFrequenciesMask(mask(LOW, HIGH));
        graph.addNode(node);
        graph.addEdge(new NetworkEdge(pos, SERVER, 10_000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
    }

    private static int mask(TelecomFrequency... bands) {
        int mask = 0;
        for (var band : bands) mask |= 1 << band.ordinal();
        return mask;
    }

    private int cap(TelecomFrequency band) { return graph.getNode(ANTENNA).getRadioConfig().capacityMbps(band); }

    private TrafficSession start(BlockPos source, String ip, int mask, boolean upload) {
        return start(source, ip, mask, upload, SERVER);
    }

    private TrafficSession start(BlockPos source, String ip, int mask, boolean upload, BlockPos destination) {
        var result = graph.startSpeedtest(source, ip, 10_000, 10_000, 0, mask, 1000, true, null, Long.toString(destination.asLong()));
        assertTrue(result.accepted(), result.error());
        for (int t = 0; t < (upload ? 1060 : 60); t++) result.session().tick();
        return result.session();
    }

    private void tick() { graph.tickTraffic(level); }
}
