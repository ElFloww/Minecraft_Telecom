package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.CoverageTilePayload;
import com.florentdubut.telecom.network.packet.RequestCoverageTilePayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoverageTilePayloadTest {
    private static final UUID VIEW = new UUID(1, 2);

    @Test
    void requestRoundTripPreservesViewDimensionAndEveryFilter() {
        var request = new RequestCoverageTilePayload(VIEW, "minecraft:the_nether", -3, 4, 1,
                "-32", "123456789", "5G", "G5_700", -3);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RequestCoverageTilePayload.STREAM_CODEC.encode(buffer, request);
            var decoded = RequestCoverageTilePayload.STREAM_CODEC.decode(buffer);
            assertEquals(request, decoded);
            assertEquals(new CoverageService.Request(-3, 4, 1, "-32", "123456789", "5G", "G5_700", -3), decoded.request());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void requestRejectsOversizedStringsBeforeHandling() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(VIEW);
            buffer.writeUtf("a".repeat(257));
            assertThrows(DecoderException.class, () -> RequestCoverageTilePayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void requestUsesTheSameBoundsAndFilterValidationAsWebCoverage() {
        for (int coordinate : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            var request = new RequestCoverageTilePayload(VIEW, "minecraft:overworld", coordinate, 0, 16,
                    "surface", "all", "all", "all", 0);
            assertThrows(IllegalArgumentException.class, request::request);
        }
        var mismatchedBand = new RequestCoverageTilePayload(VIEW, "minecraft:overworld", 0, 0, 16,
                "surface", "all", "4G", "G5_700", 0);
        assertThrows(IllegalArgumentException.class, mismatchedBand::request);
    }

    @Test
    void maximumResponseRoundTripIsBoundedAndPreservesCells() {
        var cell = new CoverageTilePayload.Cell(-24, 66, 8, "signal", -73.25f, "4G", "G4_700", "123", "unavailable");
        var response = new CoverageTilePayload(VIEW, "minecraft:overworld", "model:17", -1, 0, 8, 0,
                "ready", 1, Collections.nCopies(256, cell));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CoverageTilePayload.STREAM_CODEC.encode(buffer, response);
            assertTrue(buffer.readableBytes() < 65536);
            assertEquals(response, CoverageTilePayload.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void decoderRejectsInvalidCellCountsBeforeAllocating() {
        for (int count : new int[]{-1, 257, Integer.MAX_VALUE}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeUUID(VIEW);
                buffer.writeUtf("minecraft:overworld");
                buffer.writeUtf("model:1");
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeInt(16);
                buffer.writeInt(0);
                buffer.writeUtf("ready");
                buffer.writeFloat(1);
                buffer.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> CoverageTilePayload.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void conversionPreservesUnknownAndRadioWithoutService() {
        var request = new RequestCoverageTilePayload(VIEW, "minecraft:overworld", 0, 0, 64,
                "surface", "all", "all", "all", 0);
        var response = CoverageTilePayload.fromSnapshot(request, """
                {"modelRevision":"model:3","status":"pending","progress":0.5,"cells":[
                  {"x":32,"y":66,"z":32,"state":"unknown","powerDbm":null,
                   "technology":null,"band":null,"antenna":null,"service":"unknown"},
                  {"x":96,"y":70,"z":32,"state":"signal","powerDbm":-84.5,
                   "technology":"5G","band":"G5_700","antenna":"42","service":"unavailable"}
                ]}
                """);
        assertEquals(VIEW, response.viewId());
        assertEquals("model:3", response.modelRevision());
        assertEquals(0.5f, response.progress());
        assertEquals("unknown", response.cells().getFirst().state());
        assertEquals("", response.cells().getFirst().antenna());
        assertEquals(-84.5f, response.cells().get(1).powerDbm());
        assertEquals("unavailable", response.cells().get(1).service());
        assertEquals("5G", response.cells().get(1).technology());
        assertThrows(UnsupportedOperationException.class, () -> response.cells().clear());
    }

    @Test
    void invalidProgressIsRejectedAndFailuresCarryNoCells() {
        for (float progress : new float[]{-1, 2, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new CoverageTilePayload(VIEW, "minecraft:overworld",
                    "model:1", 0, 0, 16, 0, "pending", progress, List.of()));
        }
        var request = new RequestCoverageTilePayload(VIEW, "minecraft:overworld", 0, 0, 16,
                "surface", "all", "all", "all", 0);
        var busy = CoverageTilePayload.unavailable(request, "busy");
        assertEquals("busy", busy.status());
        assertTrue(busy.cells().isEmpty());
        assertEquals(VIEW, busy.viewId());
    }
}
