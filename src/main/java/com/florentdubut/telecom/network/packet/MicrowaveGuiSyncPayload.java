package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.MicrowaveConfig;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.UUID;

public record MicrowaveGuiSyncPayload(BlockPos pos, String name, MicrowaveConfig config, String dimension,
                                      UUID viewId, boolean opening, BlockPos target, String state,
                                      int capacityMbps, int nominalCapacityMbps, double latencyMs, BlockPos blocker)
        implements CustomPacketPayload {
    public static final Set<String> STATES = Set.of("ready", "degraded", "blocked", "fresnel_blocked", "misaligned",
            "channel_mismatch", "unpaired", "disabled", "out_of_range", "unknown", "pending", "limit");
    public static final Type<MicrowaveGuiSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "microwave_gui_sync"));

    private static boolean validTelemetry(String state, int capacity, int nominal, double latency) {
        return STATES.contains(state) && capacity >= 0 && nominal >= capacity && nominal <= 1_000_000
                && Double.isFinite(latency) && latency >= 0 && latency <= 1_000_000;
    }

    public static final StreamCodec<FriendlyByteBuf, MicrowaveGuiSyncPayload> STREAM_CODEC = StreamCodec.of((buf, p) -> {
        if (!validTelemetry(p.state(), p.capacityMbps(), p.nominalCapacityMbps(), p.latencyMs())) {
            throw new EncoderException("Invalid microwave telemetry");
        }
        MicrowaveConfigPayload.writePosition(buf, p.pos());
        buf.writeUtf(p.name(), 32);
        MicrowaveConfigPayload.CONFIG_CODEC.encode(buf, p.config());
        buf.writeUtf(p.dimension(), 128);
        buf.writeUUID(p.viewId());
        buf.writeBoolean(p.opening());
        MicrowaveConfigPayload.writePosition(buf, p.target());
        buf.writeUtf(p.state(), 32);
        buf.writeInt(p.capacityMbps());
        buf.writeInt(p.nominalCapacityMbps());
        buf.writeDouble(p.latencyMs());
        buf.writeBoolean(p.blocker() != null);
        if (p.blocker() != null) MicrowaveConfigPayload.writePosition(buf, p.blocker());
    }, buf -> {
        BlockPos pos = MicrowaveConfigPayload.readPosition(buf);
        String name = buf.readUtf(32);
        MicrowaveConfig config = MicrowaveConfigPayload.CONFIG_CODEC.decode(buf);
        String dimension = buf.readUtf(128);
        UUID view = buf.readUUID();
        boolean opening = buf.readBoolean();
        BlockPos target = MicrowaveConfigPayload.readPosition(buf);
        String state = buf.readUtf(32);
        int capacity = buf.readInt(), nominal = buf.readInt();
        double latency = buf.readDouble();
        if (!validTelemetry(state, capacity, nominal, latency)) throw new DecoderException("Invalid microwave telemetry");
        BlockPos blocker = buf.readBoolean() ? MicrowaveConfigPayload.readPosition(buf) : null;
        return new MicrowaveGuiSyncPayload(pos, name, config, dimension, view, opening, target, state, capacity, nominal, latency, blocker);
    });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
