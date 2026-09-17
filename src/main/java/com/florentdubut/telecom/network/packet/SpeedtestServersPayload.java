package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.SpeedtestServerOption;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SpeedtestServersPayload(UUID requestId, String dimension, String deviceId,
                                     List<SpeedtestServerOption> servers, boolean truncated, String errorCode)
        implements CustomPacketPayload {
    public static final Type<SpeedtestServersPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(TelecomMod.MODID, "speedtest_servers"));
    public static final StreamCodec<FriendlyByteBuf, SpeedtestServersPayload> STREAM_CODEC = StreamCodec.ofMember(
            SpeedtestServersPayload::write, SpeedtestServersPayload::read);

    public SpeedtestServersPayload {
        if (servers.size() > TelecomNetworkGraph.MAX_SPEEDTEST_SERVERS) throw new IllegalArgumentException("Too many servers");
        servers = List.copyOf(servers);
    }

    private static SpeedtestServersPayload read(FriendlyByteBuf buf) {
        UUID request = buf.readUUID();
        String dimension = buf.readUtf(256);
        String device = buf.readUtf(128);
        int count = buf.readVarInt();
        if (count < 0 || count > TelecomNetworkGraph.MAX_SPEEDTEST_SERVERS) throw new IllegalArgumentException("Invalid server count");
        List<SpeedtestServerOption> servers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            servers.add(new SpeedtestServerOption(buf.readUtf(24), buf.readUtf(128), buf.readInt(),
                    buf.readBoolean(), buf.readInt(), buf.readUtf(64)));
        }
        return new SpeedtestServersPayload(request, dimension, device, servers, buf.readBoolean(), buf.readUtf(64));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeUtf(dimension, 256);
        buf.writeUtf(deviceId, 128);
        buf.writeVarInt(servers.size());
        for (SpeedtestServerOption server : servers) {
            buf.writeUtf(server.id(), 24);
            buf.writeUtf(server.name(), 128);
            buf.writeInt(server.estimatedPingMs());
            buf.writeBoolean(server.available());
            buf.writeInt(server.bandwidthMbps());
            buf.writeUtf(server.reason(), 64);
        }
        buf.writeBoolean(truncated);
        buf.writeUtf(errorCode, 64);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
