package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkScanTest {
    @TempDir Path directory;
    private static final BlockPos NEAR = new BlockPos(33, 66, 32);
    private static final BlockPos FAR = new BlockPos(160, 70, 32);
    private ServerPlayer player;
    private ServerLevel level;
    private ServerChunkCache chunks;
    private TelecomNetworkGraph graph;
    private MockedStatic<TelecomNetworkGraph> graphs;
    private MinecraftServer server;

    @BeforeEach
    void setUp() {
        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
        chunks = mock(ServerChunkCache.class);
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenReturn(new BlockPos(32, 65, 32));
        when(player.getUUID()).thenReturn(new UUID(0, 1));
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
        when(level.isInWorldBounds(org.mockito.ArgumentMatchers.any(BlockPos.class))).thenReturn(true);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(2, 2)).thenReturn(mock(LevelChunk.class));
        graph = new TelecomNetworkGraph();
        graphs = mockStatic(TelecomNetworkGraph.class);
        graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
    }

    @AfterEach
    void tearDown() {
        RadioAccessService.clear();
        graphs.close();
        if (server != null) RadioTerrainCache.stop(server);
    }

    @Test
    void anUnloadedAntennaDoesNotHideAReceivedSignal() throws Exception {
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        addAntenna(FAR, TelecomFrequency.G5_700, false);
        NetworkScanResponsePayload scan = scan();
        assertTrue(scan.found());
        assertEquals(NEAR, scan.antennaPos());
        assertTrue(scan.tech().startsWith("4G"));
        assertEquals(1 << TelecomFrequency.G4_700.ordinal(), scan.frequenciesMask());
        assertTrue(scan.signalStrength() > -120);
        assertFalse(scan.ipAddress().isBlank());
        verify(level, never()).getBlockEntity(FAR);
    }

    @Test
    void anUnknownIntermediateChunkDoesNotHideAnotherReceivedSignal() throws Exception {
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        addAntenna(FAR, TelecomFrequency.G5_700, true);
        when(chunks.getChunkNow(10, 2)).thenReturn(mock(LevelChunk.class));
        NetworkScanResponsePayload scan = scan();
        assertTrue(scan.found());
        assertEquals(NEAR, scan.antennaPos());
        assertEquals(1 << TelecomFrequency.G4_700.ordinal(), scan.frequenciesMask());
        verify(chunks, atLeastOnce()).getChunkNow(9, 2);
    }

    @Test
    void unknownIsOnlyReportedWithoutAnyUsableSignal() throws Exception {
        addAntenna(FAR, TelecomFrequency.G5_700, false);
        NetworkScanResponsePayload scan = scan();
        assertFalse(scan.found());
        assertEquals("Terrain unavailable", scan.name());
        assertEquals(0, scan.maxDown());
        assertTrue(scan.ipAddress().isEmpty());
    }

    @Test
    void disconnectedMessagesAreTranslatableButAntennaNamesStayLiteral() throws Exception {
        addAntenna(FAR, TelecomFrequency.G5_700, false);
        assertInstanceOf(net.minecraft.network.chat.contents.TranslatableContents.class, scan().displayName().getContents());
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        assertEquals("Local antenna", scan().displayName().getString());
    }

    @Test
    void noAntennaMeansNoServiceNotUnknownTerrain() throws Exception {
        assertEquals("No Service", scan().name());
        verifyNoInteractions(chunks);
    }

    @Test
    void disabledOrOutOfRangeAntennasDoNotMakeTerrainUnknown() throws Exception {
        addAntenna(FAR, TelecomFrequency.G5_700, false);
        graph.getNode(FAR).setFrequenciesMask(0);
        addAntenna(FAR.offset(SignalPropagator.MAX_RANGE, 0, 0), TelecomFrequency.G5_700, false);
        assertEquals("No Service", scan().name());
        verifyNoInteractions(chunks);
    }

    @Test
    void unloadedSourceUsesNodeMaskAndObservedTerrainWithoutAnyBlockEntity() throws Exception {
        server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory);
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(level.getMinY()).thenReturn(64);
        when(level.getMaxY()).thenReturn(79);
        when(level.getHeight()).thenReturn(16);
        for (int x = 2; x <= 10; x++) RadioTerrainCache.captureUnloaded(level, RadioTerrainCacheTest.observedChunk(x, 2));
        when(chunks.getChunkNow(2, 2).getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        addAntenna(FAR, TelecomFrequency.G5_700, false);
        NetworkScanResponsePayload scan = scan();
        assertTrue(scan.found());
        assertEquals(FAR, scan.antennaPos());
        assertEquals("Antenna (160, 70, 32)", scan.name());
        assertEquals(1 << TelecomFrequency.G5_700.ordinal(), scan.frequenciesMask());
        assertTrue(scan.tech().startsWith("5G"));
        assertTrue(scan.maxDown() > 0);
        verify(level, never()).getBlockEntity(FAR);
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void unloadedAdjacentSourceDoesNotNeedCacheForEndpoint() throws Exception {
        when(player.blockPosition()).thenReturn(new BlockPos(47, 65, 32));
        BlockPos source = new BlockPos(48, 66, 32);
        addAntenna(source, TelecomFrequency.G4_700, false);

        NetworkScanResponsePayload scan = scan();

        assertTrue(scan.found());
        assertEquals(source, scan.antennaPos());
        assertTrue(scan.signalStrength() > -120);
        verify(level, never()).getBlockEntity(source);
    }

    private void addAntenna(BlockPos position, TelecomFrequency frequency, boolean loaded) {
        NetworkNode node = new NetworkNode(position, NetworkNode.NodeType.ANTENNA);
        node.setFrequenciesMask(1 << frequency.ordinal());
        graph.addNode(node);
        if (loaded) {
            AntennaBlockEntity antenna = mock(AntennaBlockEntity.class);
            when(antenna.getBlockPos()).thenReturn(position);
            when(antenna.getAntennaName()).thenReturn("Local antenna");
            when(antenna.isFrequencyEnabled(frequency)).thenReturn(true);
            when(level.getBlockEntity(position)).thenReturn(antenna);
        }
    }

    @Test
    void configurationChangesInvalidatePhoneCacheWithoutChangingTheIp() throws Exception {
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        var initial = scan();
        graph.getNode(NEAR).setRadioConfig(new AntennaRadioConfig(0, 0, 0, 20, 50));
        CoverageService.invalidateAntennas(level);
        var configured = scan();
        assertEquals(initial.signalStrength() - 10, configured.signalStrength());
        assertTrue(configured.maxDown() < initial.maxDown());
        assertEquals(initial.ipAddress(), configured.ipAddress());
        graph.getNode(NEAR).setFrequenciesMask(0);
        CoverageService.invalidateAntennas(level);
        assertFalse(scan().found());
    }

    @Test
    void configuredPhoneAndSharedTraceAgreeAtSameReceptionHeight() throws Exception {
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        var config = new AntennaRadioConfig(1, 90, 10, 35, 100);
        graph.getNode(NEAR).setRadioConfig(config);
        var trace = new SignalPropagator.MultiTrace(NEAR, player.blockPosition().above(),
                java.util.List.of(TelecomFrequency.G4_700), config);
        while (!trace.advance(level, 256, Long.MAX_VALUE)) { }
        assertEquals((int) trace.results().getFirst().powerDbm, scan().signalStrength());
    }

    @Test
    void scanCachesOnlyOneSecondAndLogoutClearsAttachment() throws Exception {
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        var initial = RadioAccessService.scan(player);
        clearInvocations(level, chunks);
        when(level.getGameTime()).thenReturn(19L);
        assertSame(initial, RadioAccessService.scan(player));
        verify(level, never()).getBlockEntity(any());
        when(level.getGameTime()).thenReturn(20L);
        assertNotSame(initial, RadioAccessService.scan(player));
        RadioAccessService.forget(player.getUUID());
        verify(level).getBlockEntity(NEAR);
        clearInvocations(level);
        RadioAccessService.scan(player);
        verify(level).getBlockEntity(NEAR);
    }

    @Test
    void sameBandInterferenceReducesCapacityWithoutChangingReceivedPower() throws Exception {
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        var alone = scan();
        addAntenna(new BlockPos(32, 66, 33), TelecomFrequency.G4_700, true);
        var sharedSpectrum = scan();
        assertEquals(alone.signalStrength(), sharedSpectrum.signalStrength());
        assertTrue(sharedSpectrum.maxDown() < alone.maxDown());
    }

    @Test
    void movingOutOfCoverageCannotReuseAnAuthoritativeOldScan() throws Exception {
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        assertTrue(scan().found());
        when(player.blockPosition()).thenReturn(NEAR.offset(SignalPropagator.MAX_RANGE + 1, 0, 0));
        assertFalse(scan().found());
    }

    @Test
    void cachedScansDoNotEnumerateTheGraphAgain() {
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        graph = spy(graph);
        graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
        var initial = RadioAccessService.scan(player);
        clearInvocations(graph);
        assertSame(initial, RadioAccessService.scan(player));
        verify(graph, never()).getNodes();
    }

    @Test
    void default2gHasUsableIntegerDownloadAndUpload() throws Exception {
        addAntenna(NEAR, TelecomFrequency.G2_900, true);
        var scan = scan();
        assertTrue(scan.found());
        assertEquals(1, scan.maxDown());
        assertEquals(1, scan.maxUp());
    }

    @Test
    void tooManySourcesReturnAnExplicitLimitInsteadOfAPartialInterferenceEstimate() throws Exception {
        addAntenna(NEAR, TelecomFrequency.G4_700, true);
        assertTrue(scan().found());
        for (int i = 0; i < 129; i++) addAntenna(new BlockPos(160 + i, 70, 32), TelecomFrequency.G4_700, false);
        var scan = scan();
        assertFalse(scan.found());
        assertEquals("Radio scan limit", scan.name());
        assertEquals(0, scan.maxDown());
    }

    private NetworkScanResponsePayload scan() throws Exception {
        var method = ModNetworking.class.getDeclaredMethod("scanNetworkForPlayer", ServerPlayer.class);
        method.setAccessible(true);
        return (NetworkScanResponsePayload) method.invoke(null, player);
    }
}
