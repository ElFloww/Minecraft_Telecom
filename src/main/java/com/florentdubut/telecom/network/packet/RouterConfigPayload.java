package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RouterConfigPayload(BlockPos pos, int configuredMaxDown, int configuredMaxUp) implements CustomPacketPayload {
    public static final Type<RouterConfigPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "router_config"));
    
    public static final StreamCodec<FriendlyByteBuf, RouterConfigPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeInt(payload.configuredMaxDown());
                buf.writeInt(payload.configuredMaxUp());
            },
            buf -> new RouterConfigPayload(
                    buf.readBlockPos(),
                    buf.readInt(),
                    buf.readInt()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
