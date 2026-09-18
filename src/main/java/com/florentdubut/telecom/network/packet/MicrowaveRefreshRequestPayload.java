package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record MicrowaveRefreshRequestPayload(BlockPos pos, String dimension, UUID viewId) implements CustomPacketPayload {
    public static final Type<MicrowaveRefreshRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "microwave_refresh"));
    public static final StreamCodec<FriendlyByteBuf, MicrowaveRefreshRequestPayload> STREAM_CODEC = StreamCodec.of((buf, p) -> {
        MicrowaveConfigPayload.writePosition(buf, p.pos());
        buf.writeUtf(p.dimension(), 128);
        buf.writeUUID(p.viewId());
    }, buf -> new MicrowaveRefreshRequestPayload(MicrowaveConfigPayload.readPosition(buf), buf.readUtf(128), buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
