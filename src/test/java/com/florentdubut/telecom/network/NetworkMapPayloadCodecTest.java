package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkMapPayloadCodecTest {
    private static final UUID VIEW = new UUID(1, 2);
    private static final String DIMENSION = "minecraft:overworld";
    private static final MapNodeData NODE = new MapNodeData(new BlockPos(-29999999, -64, 29999999), "MICROWAVE_DISH", "", "CH 1");

    @Test
    void requestAndMaxResponseRoundTripIncludingNullableBlockerAndUnpaired() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request = new RequestNetworkMapPayload(VIEW, DIMENSION);
            RequestNetworkMapPayload.STREAM_CODEC.encode(buffer, request);
            assertEquals(request, RequestNetworkMapPayload.STREAM_CODEC.decode(buffer));
            for (BlockPos blocker : new BlockPos[]{null, new BlockPos(-3, 100, 4)}) {
                var link = new MapMicrowaveData(NODE.pos(), NODE.pos(), blocker == null ? "unpaired" : "blocked", 0, 600, 0.25, blocker);
                var response = new NetworkMapResponsePayload(VIEW, DIMENSION,
                        Collections.nCopies(8192, NODE), Collections.nCopies(128, link));
                NetworkMapResponsePayload.STREAM_CODEC.encode(buffer, response);
                assertEquals(response, NetworkMapResponsePayload.STREAM_CODEC.decode(buffer));
                assertFalse(buffer.isReadable());
            }
        } finally { buffer.release(); }
    }

    @Test
    void countsAreRejectedBeforeAllocatingOrReadingEntries() {
        for (boolean links : new boolean[]{false, true}) {
            for (int count : new int[]{-1, links ? 129 : 8193, Integer.MAX_VALUE}) {
                var buffer = header();
                try {
                    if (links) buffer.writeInt(0);
                    buffer.writeInt(count);
                    assertThrows(IllegalArgumentException.class, () -> NetworkMapResponsePayload.STREAM_CODEC.decode(buffer));
                } finally { buffer.release(); }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new NetworkMapResponsePayload(VIEW, DIMENSION,
                Collections.nCopies(8193, NODE), List.of()));
        var link = new MapMicrowaveData(BlockPos.ZERO, BlockPos.ZERO, "unpaired", 0, 600, 0, null);
        assertThrows(IllegalArgumentException.class, () -> new NetworkMapResponsePayload(VIEW, DIMENSION,
                List.of(), Collections.nCopies(129, link)));
    }

    @Test
    void textAndTelemetryAreBoundedOnBothDirections() {
        assertThrows(IllegalArgumentException.class, () -> new RequestNetworkMapPayload(VIEW, "x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMapResponsePayload(VIEW, DIMENSION,
                List.of(new MapNodeData(BlockPos.ZERO, "x".repeat(257), "", "")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MapMicrowaveData(BlockPos.ZERO, BlockPos.ZERO, "x".repeat(33), 0, 1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new MapMicrowaveData(BlockPos.ZERO, BlockPos.ZERO, "ready", -1, 1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new MapMicrowaveData(BlockPos.ZERO, BlockPos.ZERO, "ready", 1, 1, Double.NaN, null));
        var buffer = header();
        try {
            buffer.writeInt(1);
            buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeUtf("x".repeat(257));
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> NetworkMapResponsePayload.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test
    void handlerRejectsWrongDimensionBeforeWorkAndBoundsCorrelatedReplyAndRefreshes() throws Exception {
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        var player = mock(net.minecraft.server.level.ServerPlayer.class);
        when(player.level()).thenReturn(level);
        var context = mock(net.neoforged.neoforge.network.handling.IPayloadContext.class);
        when(context.player()).thenReturn(player);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return java.util.concurrent.CompletableFuture.completedFuture(null); })
                .when(context).enqueueWork(any(Runnable.class));
        var handler = ModNetworking.class.getDeclaredMethod("handleRequestNetworkMap", RequestNetworkMapPayload.class,
                net.neoforged.neoforge.network.handling.IPayloadContext.class);
        handler.setAccessible(true);
        var graph = mock(TelecomNetworkGraph.class);
        var node = new NetworkNode(BlockPos.ZERO, NetworkNode.NodeType.MICROWAVE_DISH);
        node.setIpAddress("x".repeat(1000));
        when(graph.getNodes()).thenReturn(Collections.nCopies(8193, node));
        var link = new MicrowaveLinkService.LinkStatus(BlockPos.ZERO, BlockPos.ZERO, "unpaired", 0, 600, 0, null);
        try (var graphs = mockStatic(TelecomNetworkGraph.class); var microwave = mockStatic(MicrowaveLinkService.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            microwave.when(() -> MicrowaveLinkService.links(level)).thenReturn(Collections.nCopies(129, link));
            handler.invoke(null, new RequestNetworkMapPayload(VIEW, "minecraft:the_nether"), context);
            graphs.verifyNoInteractions();
            handler.invoke(null, new RequestNetworkMapPayload(VIEW, DIMENSION), context);
            var response = org.mockito.ArgumentCaptor.forClass(NetworkMapResponsePayload.class);
            verify(context).reply(response.capture());
            assertEquals(VIEW, response.getValue().viewId());
            assertEquals(DIMENSION, response.getValue().dimension());
            assertEquals(8192, response.getValue().nodes().size());
            assertEquals(256, response.getValue().nodes().getFirst().ipAddress().length());
            assertEquals(128, response.getValue().microwaveLinks().size());
            handler.invoke(null, new RequestNetworkMapPayload(VIEW, DIMENSION), context);
            verify(context, times(1)).reply(any());
            microwave.verify(() -> MicrowaveLinkService.links(level), times(1));
            verify(level, never()).getBlockEntity(any());
        }
    }

    private static FriendlyByteBuf header() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUUID(VIEW);
        buffer.writeUtf(DIMENSION);
        return buffer;
    }
}
