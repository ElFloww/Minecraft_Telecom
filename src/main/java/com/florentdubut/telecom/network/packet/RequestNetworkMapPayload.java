package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestNetworkMapPayload() implements CustomPacketPayload {
    public static final Type<RequestNetworkMapPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "request_network_map"));
    
    public static final StreamCodec<FriendlyByteBuf, RequestNetworkMapPayload> STREAM_CODEC = StreamCodec.ofMember(
        RequestNetworkMapPayload::write,
        RequestNetworkMapPayload::new
    );

    public RequestNetworkMapPayload(FriendlyByteBuf buffer) {
        this();
    }

    public void write(FriendlyByteBuf buffer) {
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
