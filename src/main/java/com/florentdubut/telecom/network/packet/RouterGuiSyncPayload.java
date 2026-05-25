package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RouterGuiSyncPayload(boolean isConnected, String ipAddress, int pingMs, int bandwidthMbps) implements CustomPacketPayload {
    public static final Type<RouterGuiSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "router_gui_sync"));
    
    public static final StreamCodec<FriendlyByteBuf, RouterGuiSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, RouterGuiSyncPayload::isConnected,
            ByteBufCodecs.STRING_UTF8, RouterGuiSyncPayload::ipAddress,
            ByteBufCodecs.INT, RouterGuiSyncPayload::pingMs,
            ByteBufCodecs.INT, RouterGuiSyncPayload::bandwidthMbps,
            RouterGuiSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
