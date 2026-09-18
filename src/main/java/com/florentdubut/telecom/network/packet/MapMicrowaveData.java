package com.florentdubut.telecom.network.packet;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Diagnostics, not graph edges: zero-capacity and unpaired links remain visible. */
public record MapMicrowaveData(BlockPos source, BlockPos target, String state, int capacityMbps,
                               int nominalCapacityMbps, double latencyMs, BlockPos blocker) {
    public static final int MAX_STATE_LENGTH = 32;

    public MapMicrowaveData {
        source = source.immutable();
        target = target.immutable();
        if (blocker != null) blocker = blocker.immutable();
        if (state == null || state.length() > MAX_STATE_LENGTH || capacityMbps < 0
                || nominalCapacityMbps < 0 || !Double.isFinite(latencyMs) || latencyMs < 0) {
            throw new IllegalArgumentException("Invalid microwave diagnostics");
        }
    }

    public static MapMicrowaveData read(FriendlyByteBuf buffer) {
        return new MapMicrowaveData(buffer.readBlockPos(), buffer.readBlockPos(), buffer.readUtf(MAX_STATE_LENGTH),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readDouble(), buffer.readBoolean() ? buffer.readBlockPos() : null);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(source);
        buffer.writeBlockPos(target);
        buffer.writeUtf(state, MAX_STATE_LENGTH);
        buffer.writeVarInt(capacityMbps);
        buffer.writeVarInt(nominalCapacityMbps);
        buffer.writeDouble(latencyMs);
        buffer.writeBoolean(blocker != null);
        if (blocker != null) buffer.writeBlockPos(blocker);
    }
}
