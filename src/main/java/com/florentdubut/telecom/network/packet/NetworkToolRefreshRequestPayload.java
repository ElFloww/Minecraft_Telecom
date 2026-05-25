package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record NetworkToolRefreshRequestPayload(BlockPos clickedPos) implements CustomPacketPayload {
    public static final Type<NetworkToolRefreshRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "network_tool_refresh_request"));

    public static final StreamCodec<FriendlyByteBuf, NetworkToolRefreshRequestPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, NetworkToolRefreshRequestPayload::clickedPos,
        NetworkToolRefreshRequestPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
