package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StartSpeedtestPayload(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw) implements CustomPacketPayload {
    public static final Type<StartSpeedtestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "start_speedtest"));
    
    public static final StreamCodec<FriendlyByteBuf, StartSpeedtestPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StartSpeedtestPayload::sourcePos,
            ByteBufCodecs.STRING_UTF8, StartSpeedtestPayload::clientIp,
            ByteBufCodecs.INT, StartSpeedtestPayload::targetDownBw,
            ByteBufCodecs.INT, StartSpeedtestPayload::targetUpBw,
            StartSpeedtestPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
