package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SpeedtestUpdatePayload(String clientIp, String state, int pingMs, int actualBandwidth, int ticksElapsed, int totalTicksPerPhase,
                                     String dimension, String deviceId, java.util.UUID sessionId,
                                     int downloadBandwidth, int uploadBandwidth) implements CustomPacketPayload {
    public static final Type<SpeedtestUpdatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "speedtest_update"));
    
    public static final StreamCodec<FriendlyByteBuf, SpeedtestUpdatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeUtf(payload.clientIp(), 64);
            buf.writeUtf(payload.state(), 16);
            buf.writeInt(payload.pingMs());
            buf.writeInt(payload.actualBandwidth());
            buf.writeInt(payload.ticksElapsed());
            buf.writeInt(payload.totalTicksPerPhase());
            buf.writeUtf(payload.dimension(), 128);
            buf.writeUtf(payload.deviceId(), 128);
            buf.writeUUID(payload.sessionId());
            buf.writeInt(payload.downloadBandwidth());
            buf.writeInt(payload.uploadBandwidth());
        },
        buf -> new SpeedtestUpdatePayload(
            buf.readUtf(64),
            buf.readUtf(16),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readUtf(128),
            buf.readUtf(128),
            buf.readUUID(),
            buf.readInt(),
            buf.readInt()
        )
    );

    public boolean terminal() {
        return state.equals("FINISHED") || state.equals("FAILED") || state.equals("REJECTED");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
