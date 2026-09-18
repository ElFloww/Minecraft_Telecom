package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent by the client to request a fresh AntennaGuiSyncPayload for a given antenna. */
public record AntennaRefreshRequestPayload(BlockPos pos, String dimension, java.util.UUID viewId) implements CustomPacketPayload {

    public static final Type<AntennaRefreshRequestPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(TelecomMod.MODID, "antenna_refresh_request")
    );

    public static final StreamCodec<FriendlyByteBuf, AntennaRefreshRequestPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeBlockPos(p.pos());
            buf.writeUtf(p.dimension(), 128);
            buf.writeUUID(p.viewId());
        },
        buf -> new AntennaRefreshRequestPayload(buf.readBlockPos(), buf.readUtf(128), buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
