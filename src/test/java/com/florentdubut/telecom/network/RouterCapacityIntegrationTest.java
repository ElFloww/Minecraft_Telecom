package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.RouterBlock;
import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload;
import com.florentdubut.telecom.network.packet.RouterGuiSyncPayload;
import com.florentdubut.telecom.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouterCapacityIntegrationTest {
    private static final BlockPos POS = new BlockPos(1, 64, 1);

    @Test
    void legacySyncIsOneShotAndPreservesIdentityAndAddressesWithoutRecalculation() {
        var graph = mock(TelecomNetworkGraph.class);
        var level = mock(ServerLevel.class);
        var node = new NetworkNode(POS, NetworkNode.NodeType.ROUTER);
        node.setCapacityDown(999);
        node.setCapacityUp(1000);
        node.setCapacitySyncRequired(true);
        node.setIpAddress("10.20.0.2");
        node.setNetworkCidr("10.20.0.0/24");
        when(graph.getNode(POS)).thenReturn(node);
        var entity = new RouterBlockEntity(POS, ModBlocks.ROUTER_LITE.get().defaultBlockState());
        entity.setLevel(level);
        try (var graphs = mockStatic(TelecomNetworkGraph.class); var tracer = mockStatic(NetworkTracer.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            entity.onLoad();
            entity.onLoad();
            assertEquals(1000, node.getCapacityDown());
            assertEquals(700, node.getCapacityUp());
            assertFalse(node.requiresCapacitySync());
            assertSame(node, graph.getNode(POS));
            assertEquals("10.20.0.2", node.getIpAddress());
            assertEquals("10.20.0.0/24", node.getNetworkCidr());
            verify(graph, times(1)).setDirty();
            verify(graph, never()).addNode(any());
            verify(graph, never()).removeNode(any());
            tracer.verifyNoInteractions();
            verify(level, never()).getChunk(anyInt(), anyInt());
        }
    }

    @Test
    void modelOnePreservesZeroAndCustomCapsButClampsValuesAboveHardware() {
        var graph = mock(TelecomNetworkGraph.class);
        var level = mock(ServerLevel.class);
        var node = new NetworkNode(POS, NetworkNode.NodeType.ROUTER);
        node.setCapacityDown(0);
        node.setCapacityUp(45);
        when(graph.getNode(POS)).thenReturn(node);
        var entity = new RouterBlockEntity(POS, ModBlocks.ROUTER_LITE.get().defaultBlockState());
        entity.setLevel(level);
        try (var graphs = mockStatic(TelecomNetworkGraph.class); var tracer = mockStatic(NetworkTracer.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            entity.onLoad();
            entity.onLoad();
            assertEquals(0, node.getCapacityDown());
            assertEquals(45, node.getCapacityUp());
            verify(graph, never()).setDirty();
            node.setCapacityDown(5000);
            node.setCapacityUp(2000);
            entity.onLoad();
            entity.onLoad();
            assertEquals(1000, node.getCapacityDown());
            assertEquals(700, node.getCapacityUp());
            verify(graph, times(1)).setDirty();
            tracer.verifyNoInteractions();
        }
    }

    @Test
    void nativeOpeningAndGenericRefreshUseAppliedNodeCapacities() throws Exception {
        var graph = mock(TelecomNetworkGraph.class);
        var level = mock(ServerLevel.class);
        var player = mock(ServerPlayer.class);
        var chunks = mock(ServerChunkCache.class);
        var node = new NetworkNode(POS, NetworkNode.NodeType.ROUTER);
        node.setCapacityDown(0);
        node.setCapacityUp(45);
        when(graph.getNode(POS)).thenReturn(node);
        when(player.level()).thenReturn(level);
        when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(true);
        when(level.isInWorldBounds(POS)).thenReturn(true);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(0, 0)).thenReturn(mock(LevelChunk.class));
        var block = ModBlocks.ROUTER_LITE.get();
        when(level.getBlockState(POS)).thenReturn(block.defaultBlockState());
        when(level.getBlockEntity(POS)).thenReturn(new RouterBlockEntity(POS, block.defaultBlockState()));
        var context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player);
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return CompletableFuture.completedFuture(null);
        }).when(context).enqueueWork(any(Runnable.class));
        try (var graphs = mockStatic(TelecomNetworkGraph.class); var packets = mockStatic(PacketDistributor.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            var open = RouterBlock.class.getDeclaredMethod("useWithoutItem", BlockState.class, Level.class, BlockPos.class, Player.class, BlockHitResult.class);
            open.setAccessible(true);
            open.invoke(block, block.defaultBlockState(), level, POS, player, null);
            var refresh = ModNetworking.class.getDeclaredMethod("handleGuiRefreshRequest", GuiRefreshRequestPayload.class, IPayloadContext.class);
            refresh.setAccessible(true);
            refresh.invoke(null, new GuiRefreshRequestPayload(POS), context);
            packets.verify(() -> PacketDistributor.sendToPlayer(eq(player), argThat(value -> value instanceof RouterGuiSyncPayload payload
                    && payload.configuredMaxDown() == 0 && payload.configuredMaxUp() == 45)), times(2));
        }
    }
}
