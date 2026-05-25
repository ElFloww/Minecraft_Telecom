package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.core.BlockPos;

public record NetworkToolSyncPayload(BlockPos clickedPos, String edgeType, int length, int maxBandwidth, int usageDown, int usageUp) implements CustomPacketPayload {
    public static final Type<NetworkToolSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "network_tool_sync"));

    public static final StreamCodec<FriendlyByteBuf, NetworkToolSyncPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, NetworkToolSyncPayload::clickedPos,
        ByteBufCodecs.STRING_UTF8, NetworkToolSyncPayload::edgeType,
        ByteBufCodecs.INT, NetworkToolSyncPayload::length,
        ByteBufCodecs.INT, NetworkToolSyncPayload::maxBandwidth,
        ByteBufCodecs.INT, NetworkToolSyncPayload::usageDown,
        ByteBufCodecs.INT, NetworkToolSyncPayload::usageUp,
        NetworkToolSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
