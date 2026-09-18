package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.entity.MicrowaveDishBlockEntity;
import com.florentdubut.telecom.network.packet.MicrowaveConfigPayload;
import com.florentdubut.telecom.network.packet.MicrowaveGuiSyncPayload;
import com.florentdubut.telecom.network.packet.MicrowaveRefreshRequestPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MicrowaveNetworkingTest {
    private static final BlockPos POS = new BlockPos(3, 80, 4), PEER = POS.south(100);
    private static final MicrowaveConfig CONFIG = new MicrowaveConfig(PEER, 16, 38, 359, -90, true);
    private ServerPlayer player;
    private ServerLevel level;
    private ServerChunkCache chunks;
    private MicrowaveDishBlockEntity dish;
    private IPayloadContext context;
    private UUID view;
    private MockedStatic<PacketDistributor> packets;
    private MockedStatic<MicrowaveLinkService> links;

    @BeforeEach
    void setUp() {
        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
        chunks = mock(ServerChunkCache.class);
        dish = mock(MicrowaveDishBlockEntity.class);
        context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player);
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(true);
        when(level.mayInteract(player, POS)).thenReturn(true);
        when(level.isInWorldBounds(POS)).thenReturn(true);
        when(level.isInWorldBounds(PEER)).thenReturn(true);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(0, 0)).thenReturn(mock(LevelChunk.class));
        when(level.getBlockEntity(POS)).thenReturn(dish);
        when(dish.getBlockPos()).thenReturn(POS);
        when(dish.getLevel()).thenReturn(level);
        when(dish.getDishName()).thenReturn("Site");
        when(dish.getConfig()).thenReturn(MicrowaveConfig.DEFAULT);
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return CompletableFuture.completedFuture(null);
        }).when(context).enqueueWork(any(Runnable.class));
        packets = mockStatic(PacketDistributor.class);
        links = mockStatic(MicrowaveLinkService.class);
        links.when(() -> MicrowaveLinkService.status(level, POS)).thenReturn(
                new MicrowaveLinkService.LinkStatus(POS, POS, "disabled", 0, 600, 0, null));
        MicrowaveNetworking.openDishGuiForPlayer(player, dish);
        var capture = ArgumentCaptor.forClass(MicrowaveGuiSyncPayload.class);
        packets.verify(() -> PacketDistributor.sendToPlayer(eq(player), capture.capture()));
        assertTrue(capture.getValue().opening());
        view = capture.getValue().viewId();
        packets.clearInvocations();
        links.clearInvocations();
        clearInvocations(level, chunks, dish);
    }

    @AfterEach
    void tearDown() { packets.close(); links.close(); }

    @Test
    void validSaveOnlyMutatesLocalDishWithoutLoadingOrAccessingPeer() {
        apply(CONFIG, "Site A", "minecraft:overworld", view);
        verify(dish).setConfig(CONFIG);
        verify(dish).setDishName("Site A");
        verify(level, never()).getBlockEntity(PEER);
        verify(chunks, never()).getChunkNow(PEER.getX() >> 4, PEER.getZ() >> 4);
        verify(level, never()).getChunk(anyInt(), anyInt());
        links.verifyNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"spectator", "disconnected", "far", "protected", "unloaded", "bounds", "dimension", "view", "name", "control", "self", "range", "peerBounds", "missing"})
    void unauthorizedOrMalformedSavesAreRejected(String reason) {
        String dimension = "minecraft:overworld", name = "Site A";
        UUID id = view;
        MicrowaveConfig config = CONFIG;
        switch (reason) {
            case "spectator" -> when(player.isSpectator()).thenReturn(true);
            case "disconnected" -> when(player.hasDisconnected()).thenReturn(true);
            case "far" -> when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(false);
            case "protected" -> when(level.mayInteract(player, POS)).thenReturn(false);
            case "unloaded" -> when(chunks.getChunkNow(0, 0)).thenReturn(null);
            case "bounds" -> when(level.isInWorldBounds(POS)).thenReturn(false);
            case "dimension" -> dimension = "minecraft:the_nether";
            case "view" -> id = UUID.randomUUID();
            case "name" -> name = "x".repeat(33);
            case "control" -> name = "bad\nname";
            case "self" -> config = new MicrowaveConfig(POS, 1, 11, 0, 0, true);
            case "range" -> config = new MicrowaveConfig(POS.south(4097), 1, 11, 0, 0, true);
            case "peerBounds" -> when(level.isInWorldBounds(PEER)).thenReturn(false);
            case "missing" -> when(level.getBlockEntity(POS)).thenReturn(null);
            default -> fail(reason);
        }
        apply(config, name, dimension, id);
        verify(dish, never()).setConfig(any());
        verify(dish, never()).setDishName(anyString());
        verify(level, never()).getBlockEntity(PEER);
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void clearPeerAndDisableNeedsNoRemoteBlock() {
        apply(MicrowaveConfig.DEFAULT, "", "minecraft:overworld", view);
        verify(dish).setConfig(MicrowaveConfig.DEFAULT);
        verify(dish).setDishName("");
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "refresh"})
    void staleViewCannotSaveOrRefreshReplacementDishAtSamePosition(String request) {
        var replacement = mock(MicrowaveDishBlockEntity.class);
        when(replacement.getBlockPos()).thenReturn(POS);
        when(replacement.getLevel()).thenReturn(level);
        when(level.getBlockEntity(POS)).thenReturn(replacement);
        if (request.equals("save")) apply(CONFIG, "Site A", "minecraft:overworld", view);
        else MicrowaveNetworking.handleRefresh(new MicrowaveRefreshRequestPayload(POS, "minecraft:overworld", view), context);
        verify(level).getBlockEntity(POS);
        verify(dish, never()).setConfig(any());
        verify(dish, never()).setDishName(anyString());
        verify(replacement, never()).setConfig(any());
        verify(replacement, never()).setDishName(anyString());
        packets.verifyNoInteractions();
        links.verifyNoInteractions();
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void repeatedConfigAndRefreshAreRateLimitedIndependently() {
        apply(CONFIG, "Site A", "minecraft:overworld", view);
        apply(CONFIG, "Site A", "minecraft:overworld", view);
        verify(dish, times(1)).setConfig(CONFIG);
        var refresh = new MicrowaveRefreshRequestPayload(POS, "minecraft:overworld", view);
        MicrowaveNetworking.handleRefresh(refresh, context);
        MicrowaveNetworking.handleRefresh(refresh, context);
        links.verify(() -> MicrowaveLinkService.status(level, POS), times(1));
        var capture = ArgumentCaptor.forClass(MicrowaveGuiSyncPayload.class);
        packets.verify(() -> PacketDistributor.sendToPlayer(eq(player), capture.capture()));
        assertFalse(capture.getValue().opening());
        assertEquals(view, capture.getValue().viewId());
        assertEquals("disabled", capture.getValue().state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"view", "dimension", "spectator", "far", "protected", "unloaded"})
    void invalidRefreshNeverReadsServiceOrOpensGui(String reason) {
        UUID id = view;
        String dimension = "minecraft:overworld";
        switch (reason) {
            case "view" -> id = UUID.randomUUID();
            case "dimension" -> dimension = "minecraft:the_nether";
            case "spectator" -> when(player.isSpectator()).thenReturn(true);
            case "far" -> when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(false);
            case "protected" -> when(level.mayInteract(player, POS)).thenReturn(false);
            case "unloaded" -> when(chunks.getChunkNow(0, 0)).thenReturn(null);
            default -> fail(reason);
        }
        MicrowaveNetworking.handleRefresh(new MicrowaveRefreshRequestPayload(POS, dimension, id), context);
        packets.verifyNoInteractions();
        links.verifyNoInteractions();
    }

    @Test
    void peerRangeUsesThreeDimensionsAndWorldCoordinateBounds() {
        assertTrue(MicrowaveNetworking.validPeer(POS, null));
        assertTrue(MicrowaveNetworking.validPeer(POS, POS.south(4096)));
        assertFalse(MicrowaveNetworking.validPeer(POS, POS.south(4096).above()));
        assertFalse(MicrowaveNetworking.validPeer(POS, POS));
        assertFalse(MicrowaveNetworking.validPeer(POS, new BlockPos(Integer.MIN_VALUE, 80, 4)));
    }

    private void apply(MicrowaveConfig config, String name, String dimension, UUID id) {
        MicrowaveNetworking.handleConfig(new MicrowaveConfigPayload(POS, name, config, dimension, id), context);
    }
}
