package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SpeedtestUpdatePayload(String state, int pingMs, int actualBandwidth, int ticksElapsed, int totalTicksPerPhase) implements CustomPacketPayload {
    public static final Type<SpeedtestUpdatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "speedtest_update"));
    
    public static final StreamCodec<FriendlyByteBuf, SpeedtestUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SpeedtestUpdatePayload::state,
            ByteBufCodecs.INT, SpeedtestUpdatePayload::pingMs,
            ByteBufCodecs.INT, SpeedtestUpdatePayload::actualBandwidth,
            ByteBufCodecs.INT, SpeedtestUpdatePayload::ticksElapsed,
            ByteBufCodecs.INT, SpeedtestUpdatePayload::totalTicksPerPhase,
            SpeedtestUpdatePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
