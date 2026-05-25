package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ServerBandwidthUpdatePayload(int totalBandwidthDown, int totalBandwidthUp) implements CustomPacketPayload {
    public static final Type<ServerBandwidthUpdatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "server_bandwidth_update"));

    public static final StreamCodec<FriendlyByteBuf, ServerBandwidthUpdatePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, ServerBandwidthUpdatePayload::totalBandwidthDown,
        ByteBufCodecs.INT, ServerBandwidthUpdatePayload::totalBandwidthUp,
        ServerBandwidthUpdatePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
