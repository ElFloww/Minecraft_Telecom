package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.core.BlockPos;

public record NetworkScanResponsePayload(boolean found, String name, int signalStrength, String tech, String ipAddress, BlockPos antennaPos, int maxDown, int maxUp) implements CustomPacketPayload {
    public static final Type<NetworkScanResponsePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "network_scan_response"));

    public static final StreamCodec<FriendlyByteBuf, NetworkScanResponsePayload> STREAM_CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeBoolean(payload.found());
            buf.writeUtf(payload.name());
            buf.writeInt(payload.signalStrength());
            buf.writeUtf(payload.tech());
            buf.writeUtf(payload.ipAddress());
            buf.writeBlockPos(payload.antennaPos());
            buf.writeInt(payload.maxDown());
            buf.writeInt(payload.maxUp());
        },
        buf -> new NetworkScanResponsePayload(
            buf.readBoolean(),
            buf.readUtf(),
            buf.readInt(),
            buf.readUtf(),
            buf.readUtf(),
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
