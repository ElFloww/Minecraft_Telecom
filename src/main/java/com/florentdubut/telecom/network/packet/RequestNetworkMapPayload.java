package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

public record RequestNetworkMapPayload(UUID viewId, String dimension) implements CustomPacketPayload {
    public static final int MAX_DIMENSION_LENGTH = 256;

    public RequestNetworkMapPayload {
        java.util.Objects.requireNonNull(viewId);
        if (dimension == null || dimension.length() > MAX_DIMENSION_LENGTH) throw new IllegalArgumentException("Invalid map dimension");
    }
    public static final Type<RequestNetworkMapPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "request_network_map"));
    
    public static final StreamCodec<FriendlyByteBuf, RequestNetworkMapPayload> STREAM_CODEC = StreamCodec.ofMember(
        RequestNetworkMapPayload::write,
        RequestNetworkMapPayload::new
    );

    public RequestNetworkMapPayload(FriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readUtf(MAX_DIMENSION_LENGTH));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(viewId);
        buffer.writeUtf(dimension, MAX_DIMENSION_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
