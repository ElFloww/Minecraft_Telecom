package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;

class AntennaConfigHandlerTest {
    private static final BlockPos POS = new BlockPos(3, 80, 4);
    private static final AntennaRadioConfig CONFIG = new AntennaRadioConfig(3, 180, 8, 35, 50);
    private ServerPlayer player;
    private ServerLevel level;
    private ServerChunkCache chunks;
    private AntennaBlockEntity antenna;
    private IPayloadContext context;

    @BeforeEach
    void setUp() {
        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
        chunks = mock(ServerChunkCache.class);
        antenna = mock(AntennaBlockEntity.class);
        context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player);
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(true);
        when(level.mayInteract(player, POS)).thenReturn(true);
        when(level.isInWorldBounds(POS)).thenReturn(true);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(0, 0)).thenReturn(mock(LevelChunk.class));
        when(level.getBlockEntity(POS)).thenReturn(antenna);
        when(antenna.getAntennaName()).thenReturn("Old antenna");
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return CompletableFuture.completedFuture(null);
        }).when(context).enqueueWork(any(Runnable.class));
    }

    @Test
    void nearbyPlayerAppliesValidatedRadioSettings() throws Exception {
        apply();
        verify(antenna).setAntennaName("Site A");
        verify(antenna).setEnabledFrequenciesMask(1 << TelecomFrequency.G4_700.ordinal());
        verify(antenna).setRadioConfig(CONFIG);
    }

    @Test
    void remotePlayerCannotChangeRadioSettings() throws Exception {
        when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(false);
        apply();
        verify(level, never()).getBlockEntity(any());
        verify(antenna, never()).setRadioConfig(any());
    }

    @Test
    void protectedLocationCannotBeReconfigured() throws Exception {
        when(level.mayInteract(player, POS)).thenReturn(false);
        apply();
        verify(antenna, never()).setRadioConfig(any());
    }

    @Test
    void unloadedLocationIsNotForcedByConfiguration() throws Exception {
        when(chunks.getChunkNow(0, 0)).thenReturn(null);
        apply();
        verify(level, never()).getBlockEntity(any());
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void spectatorCannotChangeRadioSettings() throws Exception {
        when(player.isSpectator()).thenReturn(true);
        apply();
        verify(antenna, never()).setRadioConfig(any());
    }

    @Test
    void delayedSaveCannotConfigureSameCoordinatesInAnotherDimension() throws Exception {
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.NETHER);
        apply();
        verify(level, never()).getBlockEntity(any());
        verify(antenna, never()).setRadioConfig(any());
    }

    private void apply() throws Exception {
        var handler = ModNetworking.class.getDeclaredMethod("handleAntennaConfig", AntennaConfigPayload.class, IPayloadContext.class);
        handler.setAccessible(true);
        handler.invoke(null, new AntennaConfigPayload(POS, "Site A", 1 << TelecomFrequency.G4_700.ordinal(), CONFIG), context);
    }
}
