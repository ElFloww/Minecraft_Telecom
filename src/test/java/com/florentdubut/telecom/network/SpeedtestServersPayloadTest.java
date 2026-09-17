package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.RequestSpeedtestServersPayload;
import com.florentdubut.telecom.network.packet.SpeedtestServersPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpeedtestServersPayloadTest {
    @Test
    void discoveryAndCatalogueRoundTripWithExplicitContextAndUnavailableServer() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request = new RequestSpeedtestServersPayload(true, BlockPos.ZERO, UUID.randomUUID(), "minecraft:overworld");
            RequestSpeedtestServersPayload.STREAM_CODEC.encode(buffer, request);
            assertEquals(request, RequestSpeedtestServersPayload.STREAM_CODEC.decode(buffer));
            var response = new SpeedtestServersPayload(request.requestId(), request.dimension(), "mobile:player",
                    List.of(new SpeedtestServerOption("-9223372036854775808", "Unavailable", -1, false, 0, "server_unavailable")), true, "");
            SpeedtestServersPayload.STREAM_CODEC.encode(buffer, response);
            assertEquals(response, SpeedtestServersPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void refusesOversizedAndNegativeListCountsBeforeReadingEntries() {
        for (int count : new int[]{-1, 129, Integer.MAX_VALUE}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeUUID(UUID.randomUUID());
                buffer.writeUtf("minecraft:overworld");
                buffer.writeUtf("router:0");
                buffer.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> SpeedtestServersPayload.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
        var option = new SpeedtestServerOption("1", "Server", 1, true, 1, "");
        assertThrows(IllegalArgumentException.class, () -> new SpeedtestServersPayload(UUID.randomUUID(), "dimension", "device",
                Collections.nCopies(129, option), true, ""));
    }

    @Test
    void refusesUnboundedStrings() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request = new RequestSpeedtestServersPayload(false, BlockPos.ZERO, UUID.randomUUID(), "x".repeat(257));
            assertThrows(RuntimeException.class, () -> RequestSpeedtestServersPayload.STREAM_CODEC.encode(buffer, request));
            buffer.clear();
            var response = new SpeedtestServersPayload(UUID.randomUUID(), "dimension", "device",
                    List.of(new SpeedtestServerOption("1", "x".repeat(129), 0, true, 1, "")), false, "");
            assertThrows(RuntimeException.class, () -> SpeedtestServersPayload.STREAM_CODEC.encode(buffer, response));
        } finally {
            buffer.release();
        }
    }
}
