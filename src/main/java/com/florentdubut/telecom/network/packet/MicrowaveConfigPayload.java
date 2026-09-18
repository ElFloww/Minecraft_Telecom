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

import java.util.UUID;

public record MicrowaveConfigPayload(BlockPos pos, String name, MicrowaveConfig config,
                                     String dimension, UUID viewId) implements CustomPacketPayload {
    public static final Type<MicrowaveConfigPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "microwave_config"));

    public static boolean validPosition(BlockPos pos) {
        return pos != null && Math.abs((long) pos.getX()) < 30_000_000 && Math.abs((long) pos.getZ()) < 30_000_000
                && pos.getY() >= -2048 && pos.getY() <= 2047;
    }

    public static boolean validConfig(MicrowaveConfig config) {
        return config != null && validNumbers(config.channel(), config.frequencyGhz(), config.azimuthDegrees(), config.elevationDegrees())
                && (config.peer() == null || validPosition(config.peer()));
    }

    private static boolean validNumbers(int channel, int frequency, int azimuth, int elevation) {
        return channel >= 1 && channel <= 16 && (frequency == 6 || frequency == 11 || frequency == 18 || frequency == 38)
                && azimuth >= 0 && azimuth <= 359 && elevation >= -90 && elevation <= 90;
    }

    static void writePosition(FriendlyByteBuf buf, BlockPos pos) {
        if (!validPosition(pos)) throw new EncoderException("Invalid microwave position");
        buf.writeBlockPos(pos);
    }

    static BlockPos readPosition(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        if (!validPosition(pos)) throw new DecoderException("Invalid microwave position");
        return pos;
    }

    static final StreamCodec<FriendlyByteBuf, MicrowaveConfig> CONFIG_CODEC = StreamCodec.of((buf, config) -> {
        if (!validConfig(config)) throw new EncoderException("Invalid microwave configuration");
        buf.writeBoolean(config.peer() != null);
        if (config.peer() != null) writePosition(buf, config.peer());
        buf.writeInt(config.channel());
        buf.writeInt(config.frequencyGhz());
        buf.writeInt(config.azimuthDegrees());
        buf.writeInt(config.elevationDegrees());
        buf.writeBoolean(config.enabled());
    }, buf -> {
        BlockPos peer = buf.readBoolean() ? readPosition(buf) : null;
        int channel = buf.readInt(), frequency = buf.readInt(), azimuth = buf.readInt(), elevation = buf.readInt();
        boolean enabled = buf.readBoolean();
        if (!validNumbers(channel, frequency, azimuth, elevation)) throw new DecoderException("Invalid microwave configuration");
        return new MicrowaveConfig(peer, channel, frequency, azimuth, elevation, enabled);
    });

    public static final StreamCodec<FriendlyByteBuf, MicrowaveConfigPayload> STREAM_CODEC = StreamCodec.of((buf, p) -> {
        writePosition(buf, p.pos());
        buf.writeUtf(p.name(), 32);
        CONFIG_CODEC.encode(buf, p.config());
        buf.writeUtf(p.dimension(), 128);
        buf.writeUUID(p.viewId());
    }, buf -> new MicrowaveConfigPayload(readPosition(buf), buf.readUtf(32), CONFIG_CODEC.decode(buf), buf.readUtf(128), buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
