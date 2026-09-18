package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AntennaPayloadCodecTest {
    private static final BlockPos POS = new BlockPos(-10, 100, 45);
    private static final int ALL_BANDS = (1 << TelecomFrequency.values().length) - 1;

    @Test
    void bothPayloadsRoundTripAllSectorAndWidthChoicesAndBoundaryValues() {
        Map<Integer, int[]> utilization = new LinkedHashMap<>();
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            utilization.put(frequency.ordinal(), new int[]{frequency.ordinal(), frequency.getMaxSpeedMb()});
        }
        for (int sectors = 0; sectors <= 3; sectors++) {
            for (int width : new int[]{25, 50, 100}) {
                for (boolean upper : new boolean[]{false, true}) {
                    var config = new AntennaRadioConfig(sectors, upper ? 359 : 0, upper ? 45 : -15, upper ? 50 : 0, width);
                    var buffer = new FriendlyByteBuf(Unpooled.buffer());
                    try {
                        var save = new AntennaConfigPayload(POS, "a".repeat(32), ALL_BANDS, config, "minecraft:the_nether");
                        AntennaConfigPayload.STREAM_CODEC.encode(buffer, save);
                        assertEquals(save, AntennaConfigPayload.STREAM_CODEC.decode(buffer));
                        var sync = new AntennaGuiSyncPayload(POS, save.name(), ALL_BANDS, utilization, config,
                                "minecraft:the_nether", new java.util.UUID(1, 2), false);
                        AntennaGuiSyncPayload.STREAM_CODEC.encode(buffer, sync);
                        var decoded = AntennaGuiSyncPayload.STREAM_CODEC.decode(buffer);
                        assertEquals(POS, decoded.pos());
                        assertEquals(save.name(), decoded.antennaName());
                        assertEquals(ALL_BANDS, decoded.enabledFrequenciesMask());
                        assertEquals(config, decoded.radioConfig());
                        assertEquals(sync.dimension(), decoded.dimension());
                        assertEquals(sync.viewId(), decoded.viewId());
                        assertFalse(decoded.opening());
                        assertEquals(utilization.size(), decoded.freqUtilization().size());
                        utilization.forEach((ordinal, values) -> assertArrayEquals(values, decoded.freqUtilization().get(ordinal)));
                        assertFalse(buffer.isReadable());
                    } finally {
                        buffer.release();
                    }
                }
            }
        }
    }

    @Test
    void legacyJavaConstructorsUseDefaultRadioConfig() {
        assertEquals(AntennaRadioConfig.DEFAULT, new AntennaConfigPayload(POS, "", 0).radioConfig());
        assertEquals(AntennaRadioConfig.DEFAULT, new AntennaGuiSyncPayload(POS, "", 0, Map.of()).radioConfig());
    }

    @Test
    void bothDecodersRejectOutOfRangeAndNonDiscreteRadioValues() {
        int[][] invalid = {{0, -1}, {0, 4}, {1, -1}, {1, 360}, {2, -16}, {2, 46},
                {3, -1}, {3, 51}, {4, 0}, {4, 24}, {4, 26}, {4, 49}, {4, 51}, {4, 99}, {4, 101}, {4, Integer.MAX_VALUE}};
        for (int[] field : invalid) {
            int[] config = {0, 0, 0, 30, 100};
            config[field[0]] = field[1];
            var buffer = header("valid", 0, config);
            try {
                buffer.writeInt(0);
                int start = buffer.readerIndex();
                assertThrows(DecoderException.class, () -> AntennaConfigPayload.STREAM_CODEC.decode(buffer));
                buffer.readerIndex(start);
                assertThrows(DecoderException.class, () -> AntennaGuiSyncPayload.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void bothDecodersBoundNamesAndRejectUnknownMaskBits() {
        for (String name : new String[]{"a".repeat(33), "valid"}) {
            for (int mask : new int[]{0, -1, 1 << TelecomFrequency.values().length}) {
                if (name.equals("valid") && mask == 0) continue;
                var buffer = header(name, mask, new int[]{0, 0, 0, 30, 100});
                try {
                    buffer.writeInt(0);
                    assertThrows(DecoderException.class, () -> AntennaConfigPayload.STREAM_CODEC.decode(buffer));
                    buffer.readerIndex(0);
                    assertThrows(DecoderException.class, () -> AntennaGuiSyncPayload.STREAM_CODEC.decode(buffer));
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void utilizationRejectsUnboundedCountInvalidOrdinalsDuplicatesAndNegativeRates() {
        int n = TelecomFrequency.values().length;
        int[][] entries = {{-1}, {n + 1}, {Integer.MAX_VALUE}, {1, -1, 0, 0}, {1, n, 0, 0},
                {2, 0, 1, 2, 0, 3, 4}, {1, 0, -1, 100}, {1, 0, 100, -1}};
        for (int[] data : entries) {
            var buffer = header("valid", 0, new int[]{0, 0, 0, 30, 100});
            try {
                buffer.writeUtf("minecraft:overworld");
                buffer.writeUUID(new java.util.UUID(0, 0));
                buffer.writeBoolean(false);
                for (int value : data) buffer.writeInt(value);
                assertThrows(DecoderException.class, () -> AntennaGuiSyncPayload.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void encoderRejectsMalformedUtilizationAndInvalidMasks() {
        for (Map<Integer, int[]> data : java.util.List.of(Map.of(-1, new int[]{0, 0}), Map.of(0, new int[]{1}),
                Map.of(0, new int[]{-1, 1}), Map.of(TelecomFrequency.values().length, new int[]{0, 0}))) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                assertThrows(EncoderException.class, () -> AntennaGuiSyncPayload.STREAM_CODEC.encode(buffer,
                        new AntennaGuiSyncPayload(POS, "valid", 0, data)));
            } finally {
                buffer.release();
            }
        }
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(EncoderException.class, () -> AntennaConfigPayload.STREAM_CODEC.encode(buffer,
                    new AntennaConfigPayload(POS, "valid", -1)));
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf header(String name, int mask, int[] config) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(POS);
        buffer.writeUtf(name);
        buffer.writeInt(mask);
        for (int value : config) buffer.writeInt(value);
        return buffer;
    }

    @Test
    void refreshRoundTripsItsDimensionAndView() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request = new com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload(
                    POS, "minecraft:the_nether", java.util.UUID.randomUUID());
            com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload.STREAM_CODEC.encode(buffer, request);
            assertEquals(request, com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
