package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ServerGuiSyncPayload(int routerCount, int antennaCount, int phoneCount, int totalBandwidthUsage) implements CustomPacketPayload {
    public static final Type<ServerGuiSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "server_gui_sync"));

    public static final StreamCodec<FriendlyByteBuf, ServerGuiSyncPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, ServerGuiSyncPayload::routerCount,
        ByteBufCodecs.INT, ServerGuiSyncPayload::antennaCount,
        ByteBufCodecs.INT, ServerGuiSyncPayload::phoneCount,
        ByteBufCodecs.INT, ServerGuiSyncPayload::totalBandwidthUsage,
        ServerGuiSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
