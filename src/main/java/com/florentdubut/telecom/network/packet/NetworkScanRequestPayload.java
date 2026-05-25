package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record NetworkScanRequestPayload() implements CustomPacketPayload {
    public static final Type<NetworkScanRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "network_scan_request"));

    public static final StreamCodec<FriendlyByteBuf, NetworkScanRequestPayload> STREAM_CODEC = StreamCodec.unit(new NetworkScanRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
