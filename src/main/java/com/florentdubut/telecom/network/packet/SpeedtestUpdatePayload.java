package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SpeedtestUpdatePayload(String clientIp, String state, int pingMs, int actualBandwidth, int ticksElapsed, int totalTicksPerPhase) implements CustomPacketPayload {
    public static final Type<SpeedtestUpdatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "speedtest_update"));
    
    public static final StreamCodec<FriendlyByteBuf, SpeedtestUpdatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeUtf(payload.clientIp());
            buf.writeUtf(payload.state());
            buf.writeInt(payload.pingMs());
            buf.writeInt(payload.actualBandwidth());
            buf.writeInt(payload.ticksElapsed());
            buf.writeInt(payload.totalTicksPerPhase());
        },
        buf -> new SpeedtestUpdatePayload(
            buf.readUtf(),
            buf.readUtf(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt()
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
