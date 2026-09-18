package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.AntennaRadioConfig;
import com.florentdubut.telecom.network.TelecomFrequency;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

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
    Map<Integer, int[]> freqUtilization,   // int[2]: {actualMbps, maxMbps}
    AntennaRadioConfig radioConfig,
    String dimension,
    java.util.UUID viewId,
    boolean opening
) implements CustomPacketPayload {

    public AntennaGuiSyncPayload(BlockPos pos, String antennaName, int enabledFrequenciesMask,
                                 Map<Integer, int[]> freqUtilization) {
        this(pos, antennaName, enabledFrequenciesMask, freqUtilization, AntennaRadioConfig.DEFAULT);
    }

    public AntennaGuiSyncPayload(BlockPos pos, String antennaName, int enabledFrequenciesMask,
                                 Map<Integer, int[]> freqUtilization, AntennaRadioConfig radioConfig) {
        this(pos, antennaName, enabledFrequenciesMask, freqUtilization, radioConfig,
                "minecraft:overworld", new java.util.UUID(0, 0), true);
    }

    public static final Type<AntennaGuiSyncPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(TelecomMod.MODID, "antenna_gui_sync")
    );

    public static final StreamCodec<FriendlyByteBuf, AntennaGuiSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeBlockPos(payload.pos());
            buf.writeUtf(payload.antennaName(), 32);
            AntennaConfigPayload.MASK_CODEC.encode(buf, payload.enabledFrequenciesMask());
            AntennaConfigPayload.RADIO_CODEC.encode(buf, payload.radioConfig());
            buf.writeUtf(payload.dimension(), 128);
            buf.writeUUID(payload.viewId());
            buf.writeBoolean(payload.opening());
            if (payload.freqUtilization().size() > TelecomFrequency.values().length) {
                throw new EncoderException("Too many antenna utilization entries");
            }
            buf.writeInt(payload.freqUtilization().size());
            for (Map.Entry<Integer, int[]> entry : payload.freqUtilization().entrySet()) {
                if (entry.getKey() == null || entry.getKey() < 0 || entry.getKey() >= TelecomFrequency.values().length
                        || entry.getValue() == null || entry.getValue().length != 2
                        || entry.getValue()[0] < 0 || entry.getValue()[1] < 0) {
                    throw new EncoderException("Invalid antenna utilization entry");
                }
                buf.writeInt(entry.getKey());
                buf.writeInt(entry.getValue()[0]); // actualMbps
                buf.writeInt(entry.getValue()[1]); // maxMbps
            }
        },
        buf -> {
            BlockPos pos = buf.readBlockPos();
            String name = buf.readUtf(32);
            int mask = AntennaConfigPayload.MASK_CODEC.decode(buf);
            AntennaRadioConfig config = AntennaConfigPayload.RADIO_CODEC.decode(buf);
            String dimension = buf.readUtf(128);
            java.util.UUID viewId = buf.readUUID();
            boolean opening = buf.readBoolean();
            int count = buf.readInt();
            if (count < 0 || count > TelecomFrequency.values().length) {
                throw new DecoderException("Invalid antenna utilization entry count");
            }
            Map<Integer, int[]> utilMap = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int ordinal = buf.readInt();
                if (ordinal < 0 || ordinal >= TelecomFrequency.values().length || utilMap.containsKey(ordinal)) {
                    throw new DecoderException("Invalid or duplicate antenna frequency ordinal");
                }
                int actual = buf.readInt();
                int max = buf.readInt();
                if (actual < 0 || max < 0) throw new DecoderException("Negative antenna utilization");
                utilMap.put(ordinal, new int[]{actual, max});
            }
            return new AntennaGuiSyncPayload(pos, name, mask, utilMap, config, dimension, viewId, opening);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
