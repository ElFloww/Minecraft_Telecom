package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record RequestSpeedtestServersPayload(boolean mobile, BlockPos sourcePos, UUID requestId, String dimension)
        implements CustomPacketPayload {
    public static final Type<RequestSpeedtestServersPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(TelecomMod.MODID, "request_speedtest_servers"));
    public static final StreamCodec<FriendlyByteBuf, RequestSpeedtestServersPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.mobile());
                buf.writeBlockPos(payload.sourcePos());
                buf.writeUUID(payload.requestId());
                buf.writeUtf(payload.dimension(), 256);
            }, buf -> new RequestSpeedtestServersPayload(buf.readBoolean(), buf.readBlockPos(), buf.readUUID(), buf.readUtf(256)));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
