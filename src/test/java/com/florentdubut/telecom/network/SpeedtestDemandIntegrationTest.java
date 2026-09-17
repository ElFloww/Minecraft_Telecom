package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeedtestDemandIntegrationTest {
    private static final BlockPos SOURCE = BlockPos.ZERO;
    private static final BlockPos OTHER = new BlockPos(0, 0, 10);
    private static final BlockPos HUB = new BlockPos(10, 0, 0);
    private static final BlockPos SERVER = new BlockPos(20, 0, 0);
    private static final BlockPos CABLE = new BlockPos(15, 0, 0);
    private TelecomNetworkGraph graph;
    private ServerLevel level;

    @BeforeEach
    void setup() {
        graph = new TelecomNetworkGraph();
        graph.addNode(new NetworkNode(SOURCE, NetworkNode.NodeType.ROUTER));
        graph.addNode(new NetworkNode(OTHER, NetworkNode.NodeType.PM));
        graph.addNode(new NetworkNode(HUB, NetworkNode.NodeType.NRO));
        graph.addNode(new NetworkNode(SERVER, NetworkNode.NodeType.SERVER));
        graph.addEdge(new NetworkEdge(SOURCE, HUB, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
        graph.addEdge(new NetworkEdge(OTHER, HUB, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
        graph.addEdge(new NetworkEdge(HUB, SERVER, 100, 1, NetworkEdge.EdgeType.FIBER, List.of(CABLE, CABLE)));
        level = mock(ServerLevel.class);
        when(level.players()).thenReturn(List.of());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
    }

    @Test
    void manualProfileDrivesRealAllocationAndFinalResultsAverageEveryObservedTick() {
        TrafficSession session = start(SOURCE, false, 1000, 80, 120);
        List<Integer> down = new ArrayList<>();
        List<Integer> up = new ArrayList<>();
        int lastDown = 0;
        int lastUp = 0;
        while (!session.isTerminal()) {
            graph.tickTraffic(level);
            assertCounters(session, null);
            if (session.getState() == TrafficSession.SessionState.DOWNLOAD) {
                lastDown = session.getActualBandwidth();
                down.add(lastDown);
                assertEquals(session.getRequestedBandwidth(100), lastDown);
                assertEquals(mean(down), session.getFinalDownBw());
            } else if (session.getState() == TrafficSession.SessionState.UPLOAD) {
                lastUp = session.getActualBandwidth();
                up.add(lastUp);
                assertEquals(session.getRequestedBandwidth(100), lastUp);
                assertEquals(mean(up), session.getFinalUpBw());
            }
        }
        assertEquals(120, down.size());
        assertEquals(120, up.size());
        assertEquals(35, down.getFirst());
        assertEquals(28, up.getFirst());
        assertTrue(down.stream().distinct().count() > 20);
        assertTrue(up.stream().distinct().count() > 15);
        assertTrue(Math.abs(mean(down) - lastDown) > 0 || Math.abs(mean(up) - lastUp) > 0);
        var result = graph.getLastResultByDeviceId(session.getDeviceId());
        assertEquals("FINISHED", result.state());
        assertEquals(0, result.actualBandwidth());
        assertEquals(mean(down), result.downloadBandwidth());
        assertEquals(mean(up), result.uploadBandwidth());
    }

    @Test
    void zeroCapacityTicksCountInBothMeansButRecalculationTicksDoNot() {
        TrafficSession session = start(SOURCE, false, 1000, 1000, 60);
        List<Integer> down = new ArrayList<>();
        List<Integer> up = new ArrayList<>();
        int tick = 0;
        while (!session.isTerminal()) {
            if (tick++ % 7 == 0) {
                int elapsed = session.getTicksElapsed();
                var phase = session.getState();
                graph.markForRecalculation();
                try (MockedStatic<NetworkTracer> tracer = mockStatic(NetworkTracer.class)) {
                    graph.tickTraffic(level);
                    tracer.verify(() -> NetworkTracer.recalculateNetwork(level));
                }
                assertEquals(elapsed, session.getTicksElapsed());
                assertEquals(phase, session.getState());
                assertEquals(mean(down), session.getFinalDownBw());
                assertEquals(mean(up), session.getFinalUpBw());
                assertCounters(session, null);
            }
            int capacity = tick % 3 == 0 ? 0 : 100;
            graph.getNode(SOURCE).setCapacityDown(capacity);
            graph.getNode(SOURCE).setCapacityUp(capacity);
            graph.tickTraffic(level);
            assertCounters(session, null);
            if (session.getState() == TrafficSession.SessionState.DOWNLOAD) down.add(session.getActualBandwidth());
            if (session.getState() == TrafficSession.SessionState.UPLOAD) up.add(session.getActualBandwidth());
            assertEquals(mean(down), session.getFinalDownBw());
            assertEquals(mean(up), session.getFinalUpBw());
        }
        assertEquals(60, down.size());
        assertEquals(60, up.size());
        assertEquals(20, down.stream().filter(value -> value == 0).count());
        assertEquals(20, up.stream().filter(value -> value == 0).count());
        var result = graph.getLastResultByDeviceId(session.getDeviceId());
        assertEquals(mean(down), result.downloadBandwidth());
        assertEquals(mean(up), result.uploadBandwidth());
    }

    @Test
    void refreshDuringRecalculationPublishesTheLastRealSampleNotTheClearedUsage() throws Exception {
        var owner = mock(net.minecraft.server.level.ServerPlayer.class);
        var server = mock(net.minecraft.server.MinecraftServer.class, RETURNS_DEEP_STUBS);
        var ownerId = new java.util.UUID(1, 2);
        when(level.getServer()).thenReturn(server);
        when(owner.level()).thenReturn(level);
        when(server.getPlayerList().getPlayer(ownerId)).thenReturn(owner);
        TrafficSession session = start(SOURCE, false, 1000, 1000, 300);
        session.setOwnerId(ownerId);
        var updates = new ArrayList<com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload>();
        try (var packets = mockStatic(net.neoforged.neoforge.network.PacketDistributor.class)) {
            packets.when(() -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(eq(owner),
                    any(com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload.class))).thenAnswer(invocation -> {
                updates.add(invocation.getArgument(1));
                return null;
            });
            for (int tick = 0; tick < 81; tick++) graph.tickTraffic(level);
            assertEquals(21, session.getTicksElapsed());
            assertEquals(20, updates.getLast().ticksElapsed());
            int measured = session.getActualBandwidth();
            int average = session.getFinalDownBw();
            assertTrue(measured > 0);
            graph.markForRecalculation();
            try (var tracer = mockStatic(NetworkTracer.class)) {
                graph.tickTraffic(level);
            }
            assertEquals(0, session.getActualBandwidth());
            assertEquals(measured, session.getMeasuredBandwidth());
            assertEquals(average, session.getFinalDownBw());
            var refresh = ModNetworking.class.getDeclaredMethod("sendSpeedtestState",
                    net.minecraft.server.level.ServerPlayer.class, TrafficSession.class, String.class, String.class);
            refresh.setAccessible(true);
            refresh.invoke(null, owner, session, session.getDeviceId(), session.getClientIp());
            assertEquals(21, updates.getLast().ticksElapsed());
            assertEquals(measured, updates.getLast().actualBandwidth());
            var snapshot = com.florentdubut.telecom.server.TelecomHttpServer.class.getDeclaredMethod("speedtestSnapshot",
                    TrafficSession.class);
            snapshot.setAccessible(true);
            var web = (com.google.gson.JsonObject) snapshot.invoke(null, session);
            assertEquals(measured, web.get("actualBandwidth").getAsInt());
            assertEquals(21, web.get("ticksElapsed").getAsInt());
            graph.getNode(SOURCE).setCapacityDown(0);
            graph.tickTraffic(level);
            assertEquals(22, updates.getLast().ticksElapsed());
            assertEquals(0, updates.getLast().actualBandwidth(), "A genuinely allocated zero must still be transmitted");
        }
    }

    @Test
    void congestionCanRemainFlatAndMixedDirectionsUseOnlyTheTrueSharedBudget() {
        TrafficSession down = start(SOURCE, false, 1000, 1000, 240);
        TrafficSession up = start(OTHER, false, 1000, 1000, 240);
        advance(down, 60);
        advance(up, 300);
        List<Integer> downloads = new ArrayList<>();
        List<Integer> uploads = new ArrayList<>();
        for (int tick = 0; tick < 140; tick++) {
            graph.tickTraffic(level);
            int downDemand = down.getRequestedBandwidth(100);
            int upDemand = up.getRequestedBandwidth(100);
            assertTrue(down.getActualBandwidth() <= downDemand);
            assertTrue(up.getActualBandwidth() <= upDemand);
            assertEquals(Math.min(100, downDemand + upDemand), down.getActualBandwidth() + up.getActualBandwidth());
            if (tick >= 20) {
                assertEquals(50, down.getActualBandwidth());
                assertEquals(50, up.getActualBandwidth());
            }
            downloads.add(down.getActualBandwidth());
            uploads.add(up.getActualBandwidth());
            assertEquals(mean(downloads), down.getFinalDownBw());
            assertEquals(mean(uploads), up.getFinalUpBw());
            assertCounters(down, up);
        }
    }

    @Test
    void allocatorZeroSamplesAtOneMegabitAreNotDroppedFromMeans() {
        graph.getNode(HUB).setCapacityDown(1);
        TrafficSession first = start(SOURCE, false, 1, 1, 120);
        TrafficSession second = start(OTHER, false, 1, 1, 120);
        advance(first, 60);
        advance(second, 60);
        List<Integer> firstSamples = new ArrayList<>();
        List<Integer> secondSamples = new ArrayList<>();
        for (int tick = 0; tick < 41; tick++) {
            graph.tickTraffic(level);
            assertEquals(1, first.getActualBandwidth() + second.getActualBandwidth());
            firstSamples.add(first.getActualBandwidth());
            secondSamples.add(second.getActualBandwidth());
            assertEquals(mean(firstSamples), first.getFinalDownBw());
            assertEquals(mean(secondSamples), second.getFinalDownBw());
            assertCounters(first, second);
        }
        assertTrue(firstSamples.contains(0));
        assertTrue(secondSamples.contains(0));
        assertNotEquals(first.getFinalDownBw(), second.getFinalDownBw());
    }

    @Test
    void passiveFinishCannotOverwriteManualRouterOrGraphResults() {
        RouterBlockEntity router = mock(RouterBlockEntity.class);
        when(level.hasChunkAt(SOURCE)).thenReturn(true);
        when(level.getBlockEntity(SOURCE)).thenReturn(router);
        TrafficSession manual = start(SOURCE, false, 1000, 80, 40);
        List<Integer> down = new ArrayList<>();
        List<Integer> up = new ArrayList<>();
        while (!manual.isTerminal()) {
            graph.tickTraffic(level);
            if (manual.getState() == TrafficSession.SessionState.DOWNLOAD) down.add(manual.getActualBandwidth());
            if (manual.getState() == TrafficSession.SessionState.UPLOAD) up.add(manual.getActualBandwidth());
        }
        var saved = graph.getLastResultByDeviceId(manual.getDeviceId());
        verify(router).setLastSpeedtestResults(mean(down), mean(up), manual.getPingMs());
        TrafficSession passive = start(SOURCE, true, 10, 7, 40);
        while (!passive.isTerminal()) {
            graph.tickTraffic(level);
            if (passive.getState() == TrafficSession.SessionState.DOWNLOAD) assertEquals(10, passive.getActualBandwidth());
            if (passive.getState() == TrafficSession.SessionState.UPLOAD) assertEquals(7, passive.getActualBandwidth());
            assertCounters(passive, null);
        }
        verifyNoMoreInteractions(router);
        assertSame(saved, graph.getLastResultByDeviceId(manual.getDeviceId()));
        assertSame(manual, graph.getLatestSessionByDeviceId(manual.getDeviceId()));
        assertEquals(10, passive.getFinalDownBw());
        assertEquals(7, passive.getFinalUpBw());
    }

    @Test
    void routeFailurePersistsTheObservedMeansWithoutAddingAFakeZero() {
        TrafficSession session = start(SOURCE, false, 1000, 1000, 60);
        List<Integer> down = new ArrayList<>();
        List<Integer> up = new ArrayList<>();
        for (int tick = 0; tick < 150; tick++) {
            graph.tickTraffic(level);
            if (session.getState() == TrafficSession.SessionState.DOWNLOAD) down.add(session.getActualBandwidth());
            if (session.getState() == TrafficSession.SessionState.UPLOAD) up.add(session.getActualBandwidth());
        }
        assertEquals(60, down.size());
        assertEquals(31, up.size());
        graph.clearEdges();
        graph.tickTraffic(level);
        var failed = graph.getLastResultByDeviceId(session.getDeviceId());
        assertEquals("FAILED", failed.state());
        assertEquals("route_lost", failed.errorCode());
        assertEquals(0, session.getActualBandwidth());
        assertEquals(mean(down), failed.downloadBandwidth());
        assertEquals(mean(up), failed.uploadBandwidth());
    }

    private TrafficSession start(BlockPos source, boolean passive, int down, int up, int duration) {
        var result = graph.startSpeedtest(source, source.toShortString(), down, up, 0, 0, duration, passive, null, "");
        assertTrue(result.accepted(), result.error());
        return result.session();
    }

    private void advance(TrafficSession session, int ticks) {
        for (int i = 0; i < ticks; i++) session.tick();
    }

    private int mean(List<Integer> samples) {
        return samples.isEmpty() ? 0 : (int) Math.round(samples.stream().mapToLong(Integer::longValue).sum() / (double) samples.size());
    }

    private void assertCounters(TrafficSession first, TrafficSession second) {
        int down = 0;
        int up = 0;
        for (TrafficSession session : second == null ? List.of(first) : List.of(first, second)) {
            if (session.getState() == TrafficSession.SessionState.DOWNLOAD) down += session.getActualBandwidth();
            if (session.getState() == TrafficSession.SessionState.UPLOAD) up += session.getActualBandwidth();
            NetworkNode source = graph.getNode(session.getSourcePos());
            assertEquals(session.getActualBandwidth(), source.getCurrentUsageDown() + source.getCurrentUsageUp());
        }
        assertEquals(down, graph.getTotalBandwidthDown());
        assertEquals(up, graph.getTotalBandwidthUp());
        assertEquals(down, graph.getActualBlockUsageDown(CABLE));
        assertEquals(up, graph.getActualBlockUsageUp(CABLE));
        assertTrue(down + up <= graph.getActualBlockCapacityMbps(CABLE));
        assertEquals(down, graph.getNode(HUB).getCurrentUsageDown());
        assertEquals(up, graph.getNode(HUB).getCurrentUsageUp());
        assertEquals(down, graph.getNode(SERVER).getCurrentUsageDown());
        assertEquals(up, graph.getNode(SERVER).getCurrentUsageUp());
        for (NetworkEdge edge : graph.getEdges()) {
            assertEquals(edge.getCurrentUsageDown() + edge.getCurrentUsageUp(), edge.getCurrentUsage());
            assertTrue(edge.getCurrentUsage() <= edge.getEffectiveBandwidthMbps());
        }
        for (NetworkNode node : graph.getNodes()) {
            assertTrue(node.getCurrentUsageDown() <= node.getCapacityDown());
            assertTrue(node.getCurrentUsageUp() <= node.getCapacityUp());
        }
    }
}
