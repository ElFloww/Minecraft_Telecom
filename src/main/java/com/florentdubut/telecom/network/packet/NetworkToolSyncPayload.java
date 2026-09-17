package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import net.minecraft.core.BlockPos;

public record NetworkToolSyncPayload(BlockPos clickedPos, String edgeType, int length, int maxBandwidth,
                                     int capacityUp, int usageDown, int usageUp, CapacityMode mode) implements CustomPacketPayload {
    public enum CapacityMode { SHARED, DIRECTIONAL }

    public NetworkToolSyncPayload {
        java.util.Objects.requireNonNull(clickedPos);
        java.util.Objects.requireNonNull(edgeType);
        java.util.Objects.requireNonNull(mode);
        clickedPos = clickedPos.immutable();
        if (edgeType.length() > 128 || length < 0 || maxBandwidth < 0 || capacityUp < 0 || usageDown < 0 || usageUp < 0
                || (mode == CapacityMode.SHARED && maxBandwidth != capacityUp)) {
            throw new IllegalArgumentException("Invalid network tool snapshot");
        }
    }

    public static final Type<NetworkToolSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "network_tool_sync"));

    public static final StreamCodec<FriendlyByteBuf, NetworkToolSyncPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public NetworkToolSyncPayload decode(FriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            String type = buffer.readUtf(128);
            int length = buffer.readInt();
            int down = buffer.readInt();
            int up = buffer.readInt();
            int usageDown = buffer.readInt();
            int usageUp = buffer.readInt();
            int mode = buffer.readUnsignedByte();
            if (mode >= CapacityMode.values().length) throw new io.netty.handler.codec.DecoderException("Invalid capacity mode");
            try {
                return new NetworkToolSyncPayload(pos, type, length, down, up, usageDown, usageUp, CapacityMode.values()[mode]);
            } catch (IllegalArgumentException exception) {
                throw new io.netty.handler.codec.DecoderException(exception);
            }
        }

        @Override
        public void encode(FriendlyByteBuf buffer, NetworkToolSyncPayload payload) {
            buffer.writeBlockPos(payload.clickedPos());
            buffer.writeUtf(payload.edgeType(), 128);
            buffer.writeInt(payload.length());
            buffer.writeInt(payload.maxBandwidth());
            buffer.writeInt(payload.capacityUp());
            buffer.writeInt(payload.usageDown());
            buffer.writeInt(payload.usageUp());
            buffer.writeByte(payload.mode().ordinal());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
