package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.MicrowaveConfigPayload;
import com.florentdubut.telecom.network.packet.MicrowaveGuiSyncPayload;
import com.florentdubut.telecom.network.packet.MicrowaveRefreshRequestPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MicrowavePayloadCodecTest {
    private static final BlockPos POS = new BlockPos(-10, 100, 45);
    private static final UUID VIEW = new UUID(1, 2);
    private static final String DIMENSION = "minecraft:the_nether";

    @Test
    void threePacketsRoundTripBoundsNullPeersAndAllStates() {
        for (int frequency : new int[]{6, 11, 18, 38}) {
            for (boolean upper : new boolean[]{false, true}) {
                var config = new MicrowaveConfig(upper ? POS.above(20) : null, upper ? 16 : 1, frequency,
                        upper ? 359 : 0, upper ? 90 : -90, upper);
                roundTrip(MicrowaveConfigPayload.STREAM_CODEC, new MicrowaveConfigPayload(POS, "a".repeat(32), config, DIMENSION, VIEW));
                for (String state : MicrowaveGuiSyncPayload.STATES) {
                    roundTrip(MicrowaveGuiSyncPayload.STREAM_CODEC, new MicrowaveGuiSyncPayload(POS, "Site", config,
                            DIMENSION, VIEW, upper, config.peer() == null ? POS : config.peer(), state,
                            upper ? 100 : 0, config.nominalCapacityMbps(), 2.25, upper ? POS.above(10) : null));
                }
            }
        }
        roundTrip(MicrowaveRefreshRequestPayload.STREAM_CODEC, new MicrowaveRefreshRequestPayload(POS, DIMENSION, VIEW));
    }

    @Test
    void configAndGuiDecodersRejectNumericBoundsBeforeConstructingConfig() {
        for (int[] invalid : new int[][]{{0, 0}, {0, 17}, {1, 7}, {1, Integer.MAX_VALUE}, {2, -1}, {2, 360}, {3, -91}, {3, 91}}) {
            int[] config = {1, 11, 0, 0};
            config[invalid[0]] = invalid[1];
            var buffer = header("Site", config, DIMENSION);
            try {
                assertThrows(DecoderException.class, () -> MicrowaveConfigPayload.STREAM_CODEC.decode(buffer));
                buffer.readerIndex(0);
                assertThrows(DecoderException.class, () -> MicrowaveGuiSyncPayload.STREAM_CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
    }

    @Test
    void boundedNamesDimensionsPositionsAndPeerEncodingPreventTruncation() {
        for (boolean name : new boolean[]{false, true}) {
            var buffer = header(name ? "x".repeat(33) : "Site", new int[]{1, 11, 0, 0}, name ? DIMENSION : "d".repeat(129));
            try {
                assertThrows(DecoderException.class, () -> MicrowaveConfigPayload.STREAM_CODEC.decode(buffer));
                buffer.readerIndex(0);
                assertThrows(DecoderException.class, () -> MicrowaveGuiSyncPayload.STREAM_CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
        for (BlockPos bad : new BlockPos[]{new BlockPos(30_000_000, 0, 0), new BlockPos(0, 2048, 0), new BlockPos(Integer.MIN_VALUE, 0, 0)}) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                assertThrows(EncoderException.class, () -> MicrowaveRefreshRequestPayload.STREAM_CODEC.encode(buffer,
                        new MicrowaveRefreshRequestPayload(bad, DIMENSION, VIEW)));
                assertThrows(EncoderException.class, () -> MicrowaveConfigPayload.STREAM_CODEC.encode(buffer,
                        new MicrowaveConfigPayload(POS, "Site", new MicrowaveConfig(bad, 1, 11, 0, 0, false), DIMENSION, VIEW)));
            } finally { buffer.release(); }
        }
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeBlockPos(new BlockPos(30_000_000, 0, 0));
            assertThrows(DecoderException.class, () -> MicrowaveRefreshRequestPayload.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test
    void guiRejectsUnknownStatesNegativeOrUnboundedRatesAndNonFiniteLatency() {
        Object[][] invalid = {{"invented", 0, 600, 1.0}, {"ready", -1, 600, 1.0}, {"ready", 601, 600, 1.0},
                {"ready", 0, 1_000_001, 1.0}, {"ready", 0, 600, Double.NaN}, {"ready", 0, 600, Double.POSITIVE_INFINITY},
                {"ready", 0, 600, -1.0}, {"ready", 0, 600, 1_000_001.0}};
        for (Object[] data : invalid) {
            var buffer = header("Site", new int[]{1, 11, 0, 0}, DIMENSION);
            try {
                buffer.writeBoolean(false);
                buffer.writeBlockPos(POS);
                buffer.writeUtf((String) data[0]);
                buffer.writeInt((int) data[1]);
                buffer.writeInt((int) data[2]);
                buffer.writeDouble((double) data[3]);
                assertThrows(DecoderException.class, () -> MicrowaveGuiSyncPayload.STREAM_CODEC.decode(buffer));
                var packet = new MicrowaveGuiSyncPayload(POS, "Site", MicrowaveConfig.DEFAULT, DIMENSION, VIEW, false,
                        POS, (String) data[0], (int) data[1], (int) data[2], (double) data[3], null);
                assertThrows(EncoderException.class, () -> MicrowaveGuiSyncPayload.STREAM_CODEC.encode(buffer, packet));
            } finally { buffer.release(); }
        }
    }

    private static FriendlyByteBuf header(String name, int[] config, String dimension) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(POS);
        buffer.writeUtf(name);
        buffer.writeBoolean(false);
        for (int value : config) buffer.writeInt(value);
        buffer.writeBoolean(false);
        buffer.writeUtf(dimension);
        buffer.writeUUID(VIEW);
        return buffer;
    }

    private static <T> void roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T value) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, value);
            assertEquals(value, codec.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }
}
