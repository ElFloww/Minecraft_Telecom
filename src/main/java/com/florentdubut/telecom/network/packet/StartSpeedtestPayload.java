package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StartSpeedtestPayload(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw, int extraPing, int frequenciesMask) implements CustomPacketPayload {
    public static final Type<StartSpeedtestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "start_speedtest"));
    
    public static final StreamCodec<FriendlyByteBuf, StartSpeedtestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.sourcePos());
                buf.writeUtf(payload.clientIp());
                buf.writeInt(payload.targetDownBw());
                buf.writeInt(payload.targetUpBw());
                buf.writeInt(payload.extraPing());
                buf.writeInt(payload.frequenciesMask());
            },
            buf -> new StartSpeedtestPayload(
                    buf.readBlockPos(),
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
