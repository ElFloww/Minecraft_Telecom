package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.CoverageService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** One bounded tile, correlated with the current map view and dimension. */
public record RequestCoverageTilePayload(UUID viewId, String dimension, int tileX, int tileZ, int step,
                                         String height, String antenna, String technology, String band, int level)
        implements CustomPacketPayload {
    public static final Type<RequestCoverageTilePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(TelecomMod.MODID, "request_coverage_tile"));
    public static final StreamCodec<FriendlyByteBuf, RequestCoverageTilePayload> STREAM_CODEC = StreamCodec.ofMember(
            RequestCoverageTilePayload::write, RequestCoverageTilePayload::new);

    public RequestCoverageTilePayload(FriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readUtf(256), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readUtf(16), buffer.readUtf(24), buffer.readUtf(8), buffer.readUtf(64), buffer.readInt());
    }

    public CoverageService.Request request() {
        return new CoverageService.Request(tileX, tileZ, step, height, antenna, technology, band, level);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(viewId);
        buffer.writeUtf(dimension, 256);
        buffer.writeInt(tileX);
        buffer.writeInt(tileZ);
        buffer.writeInt(step);
        buffer.writeUtf(height, 16);
        buffer.writeUtf(antenna, 24);
        buffer.writeUtf(technology, 8);
        buffer.writeUtf(band, 64);
        buffer.writeInt(level);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
