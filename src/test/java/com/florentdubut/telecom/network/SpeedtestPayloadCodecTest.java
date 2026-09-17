package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import com.florentdubut.telecom.network.packet.StartSpeedtestPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpeedtestPayloadCodecTest {
    @Test
    void startRoundTripSupportsAutoExplicitAndMaximumLength() {
        for (String id : new String[]{"", Long.toString(new BlockPos(-12, 64, 8).asLong()), "s".repeat(24)}) {
            roundTrip(StartSpeedtestPayload.STREAM_CODEC,
                    new StartSpeedtestPayload(new BlockPos(1, 64, -2), "10.0.0.2", 1000, 500, 25, 1, 80, id, "minecraft:the_nether"));
        }
        var legacy = new StartSpeedtestPayload(BlockPos.ZERO, "ip", 1, 1, 0, 0, 1);
        assertEquals("", legacy.serverId());
        assertEquals("", legacy.dimension());
        roundTrip(StartSpeedtestPayload.STREAM_CODEC, legacy);
    }

    @Test
    void startDimensionIsMandatoryOnWireAndBoundedInBothDirections() {
        roundTrip(StartSpeedtestPayload.STREAM_CODEC,
                new StartSpeedtestPayload(BlockPos.ZERO, "ip", 1, 1, 0, 0, 300, "42", "d".repeat(128)));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var oversized = new StartSpeedtestPayload(BlockPos.ZERO, "ip", 1, 1, 0, 0, 300, "42", "d".repeat(129));
            assertThrows(EncoderException.class, () -> StartSpeedtestPayload.STREAM_CODEC.encode(buffer, oversized));
            buffer.clear();
            buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeUtf("ip", 64);
            for (int i = 0; i < 5; i++) buffer.writeInt(1);
            buffer.writeUtf("42", 24);
            assertThrows(IndexOutOfBoundsException.class, () -> StartSpeedtestPayload.STREAM_CODEC.decode(buffer));
            buffer.readerIndex(0);
            buffer.writeUtf(oversized.dimension());
            assertThrows(DecoderException.class, () -> StartSpeedtestPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void updateRoundTripPreservesMetadataIncludingTerminalResultsAndBounds() {
        for (String state : new String[]{"PING", "DOWNLOAD", "UPLOAD", "FINISHED", "FAILED", "REJECTED"}) {
            var payload = update(state, "-123456789", "Server (-1,64,2)", state.equals("FAILED") ? "route_lost" : "");
            roundTrip(SpeedtestUpdatePayload.STREAM_CODEC, payload);
            assertEquals(state.equals("FINISHED") || state.equals("FAILED") || state.equals("REJECTED"), payload.terminal());
        }
        roundTrip(SpeedtestUpdatePayload.STREAM_CODEC, update("FAILED", "i".repeat(24), "n".repeat(128), "e".repeat(64)));
        var legacy = new SpeedtestUpdatePayload("ip", "FINISHED", 25, 0, 0, 80,
                "minecraft:overworld", "router:123", UUID.randomUUID(), 900, 450);
        assertEquals("", legacy.serverId());
        assertEquals("", legacy.serverName());
        assertEquals("", legacy.errorCode());
        roundTrip(SpeedtestUpdatePayload.STREAM_CODEC, legacy);
    }

    @Test
    void startCodecRejectsOversizedServerIdInBothDirections() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var oversized = new StartSpeedtestPayload(BlockPos.ZERO, "ip", 1, 1, 0, 0, 1, "s".repeat(25));
            assertThrows(EncoderException.class, () -> StartSpeedtestPayload.STREAM_CODEC.encode(buffer, oversized));
            buffer.clear();
            buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeUtf("ip", 64);
            for (int i = 0; i < 5; i++) buffer.writeInt(1);
            buffer.writeUtf(oversized.serverId());
            assertThrows(DecoderException.class, () -> StartSpeedtestPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void updateCodecRejectsEachOversizedMetadataFieldInBothDirections() {
        for (String[] fields : new String[][]{
                {"i".repeat(25), "", ""}, {"", "n".repeat(129), ""}, {"", "", "e".repeat(65)}
        }) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                var oversized = update("FAILED", fields[0], fields[1], fields[2]);
                assertThrows(EncoderException.class, () -> SpeedtestUpdatePayload.STREAM_CODEC.encode(buffer, oversized));
                buffer.clear();
                buffer.writeUtf(oversized.clientIp());
                buffer.writeUtf(oversized.state());
                buffer.writeInt(oversized.pingMs());
                buffer.writeInt(oversized.actualBandwidth());
                buffer.writeInt(oversized.ticksElapsed());
                buffer.writeInt(oversized.totalTicksPerPhase());
                buffer.writeUtf(oversized.dimension());
                buffer.writeUtf(oversized.deviceId());
                buffer.writeUUID(oversized.sessionId());
                buffer.writeInt(oversized.downloadBandwidth());
                buffer.writeInt(oversized.uploadBandwidth());
                for (String field : fields) buffer.writeUtf(field);
                assertThrows(DecoderException.class, () -> SpeedtestUpdatePayload.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    private static SpeedtestUpdatePayload update(String state, String id, String name, String error) {
        return new SpeedtestUpdatePayload("10.0.0.2", state, 25, 500, 20, 80,
                "minecraft:overworld", "router:123", UUID.randomUUID(), 900, 450, id, name, error);
    }

    private static <T> void roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, payload);
            assertEquals(payload, codec.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
