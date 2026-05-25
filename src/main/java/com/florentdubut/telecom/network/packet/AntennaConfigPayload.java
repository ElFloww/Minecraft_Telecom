package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AntennaConfigPayload(BlockPos pos, String name, boolean is3G, boolean is4G, boolean is5G) implements CustomPacketPayload {
    public static final Type<AntennaConfigPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "antenna_config"));

    public static final StreamCodec<FriendlyByteBuf, AntennaConfigPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, AntennaConfigPayload::pos,
        ByteBufCodecs.STRING_UTF8, AntennaConfigPayload::name,
        ByteBufCodecs.BOOL, AntennaConfigPayload::is3G,
        ByteBufCodecs.BOOL, AntennaConfigPayload::is4G,
        ByteBufCodecs.BOOL, AntennaConfigPayload::is5G,
        AntennaConfigPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
