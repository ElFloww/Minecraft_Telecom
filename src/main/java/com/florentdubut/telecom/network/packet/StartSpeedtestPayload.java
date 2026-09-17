package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record StartSpeedtestPayload(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw, int extraPing, int frequenciesMask, int durationTicks, String serverId, String dimension) implements CustomPacketPayload {
    public StartSpeedtestPayload(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw, int extraPing, int frequenciesMask, int durationTicks, String serverId) {
        this(sourcePos, clientIp, targetDownBw, targetUpBw, extraPing, frequenciesMask, durationTicks, serverId, "");
    }

    public StartSpeedtestPayload(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw, int extraPing, int frequenciesMask, int durationTicks) {
        this(sourcePos, clientIp, targetDownBw, targetUpBw, extraPing, frequenciesMask, durationTicks, "");
    }

    public static final Type<StartSpeedtestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "start_speedtest"));
    
    public static final StreamCodec<FriendlyByteBuf, StartSpeedtestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.sourcePos());
                buf.writeUtf(payload.clientIp(), 64);
                buf.writeInt(payload.targetDownBw());
                buf.writeInt(payload.targetUpBw());
                buf.writeInt(payload.extraPing());
                buf.writeInt(payload.frequenciesMask());
                buf.writeInt(payload.durationTicks());
                buf.writeUtf(payload.serverId(), 24);
                buf.writeUtf(payload.dimension(), 128);
            },
            buf -> new StartSpeedtestPayload(
                    buf.readBlockPos(),
                    buf.readUtf(64),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf(24),
                    buf.readUtf(128)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
