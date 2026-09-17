package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record RequestServerRefreshPayload(UUID viewId, String dimension, BlockPos pos) implements CustomPacketPayload {
    public RequestServerRefreshPayload {
        java.util.Objects.requireNonNull(viewId);
        java.util.Objects.requireNonNull(dimension);
        pos = java.util.Objects.requireNonNull(pos).immutable();
        if (dimension.length() > 128 || Identifier.tryParse(dimension) == null) {
            throw new IllegalArgumentException("Invalid server dimension");
        }
    }

    public static final Type<RequestServerRefreshPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "server_refresh"));
    public static final StreamCodec<FriendlyByteBuf, RequestServerRefreshPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RequestServerRefreshPayload decode(FriendlyByteBuf buffer) {
            try {
                return new RequestServerRefreshPayload(buffer.readUUID(), buffer.readUtf(128), buffer.readBlockPos());
            } catch (IllegalArgumentException exception) {
                throw new io.netty.handler.codec.DecoderException(exception);
            }
        }

        @Override
        public void encode(FriendlyByteBuf buffer, RequestServerRefreshPayload payload) {
            buffer.writeUUID(payload.viewId());
            buffer.writeUtf(payload.dimension(), 128);
            buffer.writeBlockPos(payload.pos());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
