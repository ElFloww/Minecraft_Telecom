package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.ServerBlock;
import com.florentdubut.telecom.block.entity.ServerBlockEntity;
import com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload;
import com.florentdubut.telecom.network.packet.RequestServerRefreshPayload;
import com.florentdubut.telecom.network.packet.ServerGuiSyncPayload;
import com.florentdubut.telecom.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServerSyncHandlersTest {
    private static final BlockPos POS = new BlockPos(1, 64, 1);
    private static final UUID VIEW = new UUID(1, 2);
    private ServerPlayer player;
    private ServerLevel level;
    private ServerChunkCache chunks;
    private TelecomNetworkGraph graph;
    private IPayloadContext context;
    private MockedStatic<TelecomNetworkGraph> graphs;

    @BeforeEach
    void setUp() {
        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
        chunks = mock(ServerChunkCache.class);
        graph = mock(TelecomNetworkGraph.class);
        context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player);
        when(player.level()).thenReturn(level);
        when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(true);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isInWorldBounds(POS)).thenReturn(true);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(0, 0)).thenReturn(mock(LevelChunk.class));
        when(level.getBlockEntity(POS)).thenReturn(mock(ServerBlockEntity.class));
        var server = mock(MinecraftServer.class);
        var players = mock(PlayerList.class);
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());
        var node = new NetworkNode(POS, NetworkNode.NodeType.SERVER);
        node.setCurrentUsageDown(123);
        node.setCurrentUsageUp(45);
        when(graph.getNode(POS)).thenReturn(node);
        when(graph.getNodes()).thenReturn(List.of(node));
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return CompletableFuture.completedFuture(null);
        }).when(context).enqueueWork(any(Runnable.class));
        graphs = mockStatic(TelecomNetworkGraph.class);
        graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
    }

    @AfterEach
    void tearDown() { graphs.close(); }

    @Test
    void realOpeningAndRefreshUseSameSnapshotAndRefreshIsRateLimited() throws Exception {
        var packet = ArgumentCaptor.forClass(ServerGuiSyncPayload.class);
        try (var packets = mockStatic(PacketDistributor.class)) {
            var block = ModBlocks.SERVER.get();
            var open = ServerBlock.class.getDeclaredMethod("useWithoutItem", BlockState.class, Level.class, BlockPos.class, Player.class, BlockHitResult.class);
            open.setAccessible(true);
            open.invoke(block, block.defaultBlockState(), level, POS, player, null);
            packets.verify(() -> PacketDistributor.sendToPlayer(eq(player), packet.capture()));
        }
        var opening = packet.getValue();
        assertTrue(opening.open());
        assertTrue(opening.valid());
        var request = new RequestServerRefreshPayload(opening.viewId(), opening.dimension(), POS);
        handle("handleServerRefreshRequest", request);
        handle("handleServerRefreshRequest", request);
        verify(context, times(1)).reply(argThat(value -> value instanceof ServerGuiSyncPayload update
                && !update.open() && update.valid() && update.viewId().equals(opening.viewId())
                && update.pos().equals(opening.pos()) && update.dimension().equals(opening.dimension())
                && update.totalBandwidthDown() == opening.totalBandwidthDown() && update.totalBandwidthUp() == opening.totalBandwidthUp()
                && update.capacityDown() == opening.capacityDown() && update.capacityUp() == opening.capacityUp()
                && update.routerCount() == opening.routerCount() && update.antennaCount() == opening.antennaCount()));
        verify(graph, times(2)).getEdges();
        verify(graph, never()).calculatePathStats(any(), any());
        verify(graph, never()).getTotalBandwidthDown();
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void countsOnlyTargetComponentWithOneTopologicalTraversal() {
        var first = graph.getNode(POS);
        var router = new NetworkNode(POS.east(), NetworkNode.NodeType.ROUTER);
        var antenna = new NetworkNode(POS.north(), NetworkNode.NodeType.ANTENNA);
        var second = new NetworkNode(POS.west(5), NetworkNode.NodeType.SERVER);
        second.setCurrentUsageDown(999);
        var otherRouter = new NetworkNode(POS.west(6), NetworkNode.NodeType.ROUTER);
        when(graph.getNode(second.getPosition())).thenReturn(second);
        when(graph.getNodes()).thenReturn(List.of(first, router, antenna, second, otherRouter));
        when(graph.getEdges()).thenReturn(List.of(edge(first, router), edge(router, antenna), edge(antenna, first),
                edge(first, router), edge(second, otherRouter)));
        var a = ModNetworking.createServerSnapshot(graph, POS, VIEW, "minecraft:overworld", false, 7);
        var b = ModNetworking.createServerSnapshot(graph, second.getPosition(), VIEW, "minecraft:overworld", false, 7);
        assertEquals(1, a.routerCount());
        assertEquals(1, a.antennaCount());
        assertEquals(1, b.routerCount());
        assertEquals(0, b.antennaCount());
        assertEquals(123, a.totalBandwidthDown());
        assertEquals(999, b.totalBandwidthDown());
        assertEquals(7, a.phoneCount());
        verify(graph, times(2)).getEdges();
        verify(graph, times(2)).getNodes();
        verify(graph, never()).calculatePathStats(any(), any());
    }

    @Test
    void unloadedSourceReturnsInvalidWithoutLoadingChunk() throws Exception {
        when(chunks.getChunkNow(0, 0)).thenReturn(null);
        handle("handleServerRefreshRequest", request());
        assertInvalidReply();
        verify(level, never()).getBlockEntity(any());
        verify(level, never()).getChunk(anyInt(), anyInt());
        graphs.verifyNoInteractions();
    }

    @Test
    void removedBlockAndMissingNodeReturnInvalidInsteadOfStaleUsage() throws Exception {
        when(level.getBlockEntity(POS)).thenReturn(null);
        handle("handleServerRefreshRequest", request());
        assertInvalidReply();
        graphs.verifyNoInteractions();
        when(graph.getNode(POS)).thenReturn(null);
        var missing = ModNetworking.createServerSnapshot(graph, POS, VIEW, "minecraft:overworld", false, 3);
        assertFalse(missing.valid());
        assertEquals(0, missing.totalBandwidthDown());
    }

    @Test
    void wrongDimensionDoesNotReadWorldOrGraph() throws Exception {
        handle("handleServerRefreshRequest", new RequestServerRefreshPayload(VIEW, "minecraft:the_nether", POS));
        assertInvalidReply();
        verify(level, never()).getBlockEntity(any());
        verify(level, never()).getChunkSource();
        graphs.verifyNoInteractions();
    }

    @Test
    void distantSourceIsInvalidWithoutWorldRead() throws Exception {
        when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(false);
        handle("handleServerRefreshRequest", request());
        assertInvalidReply();
        verify(level, never()).getBlockEntity(any());
        graphs.verifyNoInteractions();
    }

    @Test
    void oldGenericRefreshCannotReopenServer() throws Exception {
        when(level.getBlockState(POS)).thenReturn(ModBlocks.SERVER.get().defaultBlockState());
        try (var packets = mockStatic(PacketDistributor.class)) {
            handle("handleGuiRefreshRequest", new GuiRefreshRequestPayload(POS));
            packets.verifyNoInteractions();
        }
        graphs.verifyNoInteractions();
    }

    private static NetworkEdge edge(NetworkNode a, NetworkNode b) {
        return new NetworkEdge(a.getPosition(), b.getPosition(), 10000, 1, NetworkEdge.EdgeType.FIBER, List.of());
    }

    private RequestServerRefreshPayload request() { return new RequestServerRefreshPayload(VIEW, "minecraft:overworld", POS); }

    private void assertInvalidReply() {
        verify(context).reply(argThat(value -> value instanceof ServerGuiSyncPayload packet && !packet.valid() && !packet.open()
                && packet.viewId().equals(VIEW) && packet.pos().equals(POS) && packet.totalBandwidthDown() == 0 && packet.totalBandwidthUp() == 0));
    }

    private void handle(String name, Object payload) throws Exception {
        var method = ModNetworking.class.getDeclaredMethod(name, payload.getClass(), IPayloadContext.class);
        method.setAccessible(true);
        method.invoke(null, payload, context);
    }
}
