package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ServerGuiSyncPayload(java.util.UUID viewId, String dimension, boolean open, boolean valid,
                                   BlockPos pos, int routerCount, int antennaCount, int phoneCount, int totalBandwidthDown,
                                   int totalBandwidthUp, int capacityDown, int capacityUp) implements CustomPacketPayload {
    public ServerGuiSyncPayload {
        java.util.Objects.requireNonNull(viewId);
        java.util.Objects.requireNonNull(dimension);
        if (dimension.length() > 128 || Identifier.tryParse(dimension) == null) throw new IllegalArgumentException("Invalid server dimension");
        pos = java.util.Objects.requireNonNull(pos).immutable();
        if (routerCount < 0 || antennaCount < 0 || phoneCount < 0 || totalBandwidthDown < 0 || totalBandwidthUp < 0
                || capacityDown < 0 || capacityUp < 0) throw new IllegalArgumentException("Negative server snapshot value");
    }

    public static ServerGuiSyncPayload unavailable(java.util.UUID viewId, String dimension, BlockPos pos, boolean open) {
        return new ServerGuiSyncPayload(viewId, dimension, open, false, pos, 0, 0, 0, 0, 0, 0, 0);
    }

    public static final Type<ServerGuiSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "server_gui_sync"));

    public static final StreamCodec<FriendlyByteBuf, ServerGuiSyncPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerGuiSyncPayload decode(FriendlyByteBuf buffer) {
            try {
                return new ServerGuiSyncPayload(buffer.readUUID(), buffer.readUtf(128), buffer.readBoolean(), buffer.readBoolean(),
                        buffer.readBlockPos(), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                        buffer.readInt(), buffer.readInt(), buffer.readInt());
            } catch (IllegalArgumentException exception) {
                throw new io.netty.handler.codec.DecoderException(exception);
            }
        }

        @Override
        public void encode(FriendlyByteBuf buffer, ServerGuiSyncPayload payload) {
            buffer.writeUUID(payload.viewId());
            buffer.writeUtf(payload.dimension(), 128);
            buffer.writeBoolean(payload.open());
            buffer.writeBoolean(payload.valid());
            buffer.writeBlockPos(payload.pos());
            buffer.writeInt(payload.routerCount());
            buffer.writeInt(payload.antennaCount());
            buffer.writeInt(payload.phoneCount());
            buffer.writeInt(payload.totalBandwidthDown());
            buffer.writeInt(payload.totalBandwidthUp());
            buffer.writeInt(payload.capacityDown());
            buffer.writeInt(payload.capacityUp());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
