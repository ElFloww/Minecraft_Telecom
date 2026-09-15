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
