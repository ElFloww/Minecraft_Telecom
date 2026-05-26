package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Sent from server to client when a player opens an antenna GUI.
 * Contains antenna info + per-frequency utilization data.
 */
public record AntennaGuiSyncPayload(
    BlockPos pos,
    String antennaName,
    int enabledFrequenciesMask,
    // Map: TelecomFrequency.ordinal() → actual Mbps usage, maxMbps
    Map<Integer, int[]> freqUtilization   // int[2]: {actualMbps, maxMbps}
) implements CustomPacketPayload {

    public static final Type<AntennaGuiSyncPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "antenna_gui_sync")
    );

    public static final StreamCodec<FriendlyByteBuf, AntennaGuiSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeBlockPos(payload.pos());
            buf.writeUtf(payload.antennaName());
            buf.writeInt(payload.enabledFrequenciesMask());
            buf.writeInt(payload.freqUtilization().size());
            for (Map.Entry<Integer, int[]> entry : payload.freqUtilization().entrySet()) {
                buf.writeInt(entry.getKey());
                buf.writeInt(entry.getValue()[0]); // actualMbps
                buf.writeInt(entry.getValue()[1]); // maxMbps
            }
        },
        buf -> {
            BlockPos pos = buf.readBlockPos();
            String name = buf.readUtf();
            int mask = buf.readInt();
            int count = buf.readInt();
            Map<Integer, int[]> utilMap = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int ordinal = buf.readInt();
                int actual = buf.readInt();
                int max = buf.readInt();
                utilMap.put(ordinal, new int[]{actual, max});
            }
            return new AntennaGuiSyncPayload(pos, name, mask, utilMap);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
