package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record NetworkToolSyncPayload(String edgeType, int length, int maxBandwidth, int usagePercent) implements CustomPacketPayload {
    public static final Type<NetworkToolSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "network_tool_sync"));

    public static final StreamCodec<FriendlyByteBuf, NetworkToolSyncPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, NetworkToolSyncPayload::edgeType,
        ByteBufCodecs.INT, NetworkToolSyncPayload::length,
        ByteBufCodecs.INT, NetworkToolSyncPayload::maxBandwidth,
        ByteBufCodecs.INT, NetworkToolSyncPayload::usagePercent,
        NetworkToolSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
