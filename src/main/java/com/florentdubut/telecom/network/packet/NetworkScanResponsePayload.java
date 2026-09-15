package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import net.minecraft.core.BlockPos;

public record NetworkScanResponsePayload(boolean found, String name, int signalStrength, String tech, String ipAddress, BlockPos antennaPos, int maxDown, int maxUp, int frequenciesMask) implements CustomPacketPayload {
    public static final Type<NetworkScanResponsePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "network_scan_response"));

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
            buf.writeInt(payload.frequenciesMask());
        },
        buf -> new NetworkScanResponsePayload(
            buf.readBoolean(),
            buf.readUtf(),
            buf.readInt(),
            buf.readUtf(),
            buf.readUtf(),
            buf.readBlockPos(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt()
        )
    );

    public net.minecraft.network.chat.Component displayName() {
        if (!found) {
            if (name.equals("Terrain unavailable")) {
                return net.minecraft.network.chat.Component.translatable("message.telecom.terrain_unavailable");
            }
            if (name.equals("No Service")) {
                return net.minecraft.network.chat.Component.translatable("message.telecom.no_service");
            }
        }
        return net.minecraft.network.chat.Component.literal(name);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
