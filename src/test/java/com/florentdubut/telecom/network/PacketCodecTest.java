package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import com.florentdubut.telecom.network.packet.StartSpeedtestPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketCodecTest {
    @Test
    void speedtestUpdatesPreserveDeviceSessionAndBothResults() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var payload = new com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload(
                    "10.0.0.2", "FINISHED", 12, 0, 0, 300,
                    "minecraft:overworld", TrafficSession.routerDeviceId(new BlockPos(-4, 64, 8)),
                    new java.util.UUID(1, 2), 950, 680);
            com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload.STREAM_CODEC.encode(buffer, payload);
            var decoded = com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload.STREAM_CODEC.decode(buffer);
            assertEquals(payload, decoded);
            assertTrue(decoded.terminal());
        } finally {
            buffer.release();
        }
    }

    @Test
    void antennaCodecAcceptsMaximumNameAndDisabledBands() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AntennaConfigPayload payload = new AntennaConfigPayload(new BlockPos(-10, 80, 4), "a".repeat(32), 0);
            AntennaConfigPayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(payload, AntennaConfigPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void antennaCodecRejectsOversizedNameBeforeHandling() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeUtf("a".repeat(33));
            buffer.writeInt(0);
            assertThrows(DecoderException.class, () -> AntennaConfigPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void speedtestCodecPreservesWireLayout() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StartSpeedtestPayload payload = new StartSpeedtestPayload(BlockPos.ZERO, "172.16.0.1", 1000, 700, 20, 1, 300);
            StartSpeedtestPayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(payload, StartSpeedtestPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void speedtestCodecRejectsOversizedClientIp() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeUtf("a".repeat(65));
            assertThrows(DecoderException.class, () -> StartSpeedtestPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
