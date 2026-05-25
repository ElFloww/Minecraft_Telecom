package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record NetworkScanResponsePayload(boolean found, String name, int signalStrength, String tech) implements CustomPacketPayload {
    public static final Type<NetworkScanResponsePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "network_scan_response"));

    public static final StreamCodec<FriendlyByteBuf, NetworkScanResponsePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL, NetworkScanResponsePayload::found,
        ByteBufCodecs.STRING_UTF8, NetworkScanResponsePayload::name,
        ByteBufCodecs.INT, NetworkScanResponsePayload::signalStrength,
        ByteBufCodecs.STRING_UTF8, NetworkScanResponsePayload::tech,
        NetworkScanResponsePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
