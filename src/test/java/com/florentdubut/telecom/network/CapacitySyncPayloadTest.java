package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.NetworkToolSyncPayload;
import com.florentdubut.telecom.network.packet.NetworkToolSyncPayload.CapacityMode;
import com.florentdubut.telecom.network.packet.ServerGuiSyncPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapacitySyncPayloadTest {
    @Test
    void serverRefreshRoundTripAndDimensionBounds() {
        var view = new java.util.UUID(1, 2);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request = new com.florentdubut.telecom.network.packet.RequestServerRefreshPayload(view, "t:" + "a".repeat(126), BlockPos.ZERO);
            var codec = com.florentdubut.telecom.network.packet.RequestServerRefreshPayload.STREAM_CODEC;
            codec.encode(buffer, request);
            assertEquals(154, buffer.readableBytes());
            assertEquals(request, codec.decode(buffer));
            assertFalse(buffer.isReadable());
            buffer.clear();
            buffer.writeUUID(view);
            buffer.writeUtf("t:" + "a".repeat(127));
            assertThrows(DecoderException.class, () -> codec.decode(buffer));
            buffer.readerIndex(0);
            assertThrows(DecoderException.class, () -> ServerGuiSyncPayload.STREAM_CODEC.decode(buffer));
            assertThrows(IllegalArgumentException.class, () -> new com.florentdubut.telecom.network.packet.RequestServerRefreshPayload(view, "invalid dimension", BlockPos.ZERO));
            assertThrows(IllegalArgumentException.class, () -> ServerGuiSyncPayload.unavailable(view, "x".repeat(129), BlockPos.ZERO, false));
            buffer.clear();
            var unavailable = ServerGuiSyncPayload.unavailable(view, "minecraft:overworld", BlockPos.ZERO, false);
            ServerGuiSyncPayload.STREAM_CODEC.encode(buffer, unavailable);
            assertEquals(unavailable, ServerGuiSyncPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void toolRoundTripsBothModesAndAsymmetricCapacities() {
        for (CapacityMode mode : CapacityMode.values()) {
            var payload = new NetworkToolSyncPayload(new BlockPos(-20, 64, 30), "t".repeat(128), Integer.MAX_VALUE,
                    1000, mode == CapacityMode.SHARED ? 1000 : 700, 600, 300, mode);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                NetworkToolSyncPayload.STREAM_CODEC.encode(buffer, payload);
                assertTrue(buffer.readableBytes() <= 8 + 2 + 128 + 20 + 1);
                assertEquals(payload, NetworkToolSyncPayload.STREAM_CODEC.decode(buffer));
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void serverRoundTripsRealCapacitiesAndPosition() {
        var payload = new ServerGuiSyncPayload(new java.util.UUID(1, 2), "minecraft:overworld", false, true,
                new BlockPos(1, 60, -4), 10, 2, 4, 800000, 400000, 1000000, 900000);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerGuiSyncPayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(74, buffer.readableBytes());
            assertEquals(payload, ServerGuiSyncPayload.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsInvalidSnapshotsAndAcceptsZeroCapacity() {
        assertDoesNotThrow(() -> new NetworkToolSyncPayload(BlockPos.ZERO, "", 0, 0, 0, 0, 0, CapacityMode.SHARED));
        assertThrows(IllegalArgumentException.class, () -> new NetworkToolSyncPayload(BlockPos.ZERO, "x".repeat(129), 0, 0, 0, 0, 0, CapacityMode.SHARED));
        assertThrows(IllegalArgumentException.class, () -> new NetworkToolSyncPayload(BlockPos.ZERO, "", -1, 0, 0, 0, 0, CapacityMode.SHARED));
        assertThrows(IllegalArgumentException.class, () -> new NetworkToolSyncPayload(BlockPos.ZERO, "", 0, 1000, 700, 0, 0, CapacityMode.SHARED));
        assertThrows(IllegalArgumentException.class, () -> new NetworkToolSyncPayload(BlockPos.ZERO, "", 0, 1000, -1, 0, 0, CapacityMode.DIRECTIONAL));
        assertThrows(IllegalArgumentException.class, () -> new NetworkToolSyncPayload(BlockPos.ZERO, "", 0, 1000, 700, -1, 0, CapacityMode.DIRECTIONAL));
        assertThrows(IllegalArgumentException.class, () -> new ServerGuiSyncPayload(new java.util.UUID(1, 2), "minecraft:overworld", true, true,
                BlockPos.ZERO, 0, 0, 0, 0, 0, -1, 1000000));
    }

    @Test
    void decoderRejectsOversizedTypeNegativeFieldsAndUnknownModes() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeUtf("x".repeat(129));
            assertThrows(DecoderException.class, () -> NetworkToolSyncPayload.STREAM_CODEC.decode(buffer));
            buffer.clear();
            NetworkToolSyncPayload.STREAM_CODEC.encode(buffer,
                    new NetworkToolSyncPayload(BlockPos.ZERO, "", 0, 1000, 700, 0, 0, CapacityMode.DIRECTIONAL));
            buffer.setByte(buffer.writerIndex() - 1, 2);
            assertThrows(DecoderException.class, () -> NetworkToolSyncPayload.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeUUID(new java.util.UUID(1, 2));
            buffer.writeUtf("minecraft:overworld");
            buffer.writeBoolean(false);
            buffer.writeBoolean(true);
            buffer.writeBlockPos(BlockPos.ZERO);
            for (int i = 0; i < 7; i++) buffer.writeInt(i == 6 ? -1 : 0);
            assertThrows(DecoderException.class, () -> ServerGuiSyncPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
