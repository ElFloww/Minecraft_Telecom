package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RouterGuiSyncPayload(net.minecraft.core.BlockPos pos, boolean isConnected, String ipAddress, int pingMs, int bandwidthMbps, int configuredMaxDown, int configuredMaxUp) implements CustomPacketPayload {
    public static final Type<RouterGuiSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "router_gui_sync"));
    
    public static final StreamCodec<FriendlyByteBuf, RouterGuiSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeBoolean(payload.isConnected());
                buf.writeUtf(payload.ipAddress());
                buf.writeInt(payload.pingMs());
                buf.writeInt(payload.bandwidthMbps());
                buf.writeInt(payload.configuredMaxDown());
                buf.writeInt(payload.configuredMaxUp());
            },
            buf -> new RouterGuiSyncPayload(
                    buf.readBlockPos(),
                    buf.readBoolean(),
                    buf.readUtf(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
