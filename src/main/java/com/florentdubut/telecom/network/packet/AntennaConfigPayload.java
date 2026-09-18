package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.AntennaRadioConfig;
import com.florentdubut.telecom.network.TelecomFrequency;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AntennaConfigPayload(BlockPos pos, String name, int enabledFrequenciesMask,
                                   AntennaRadioConfig radioConfig, String dimension) implements CustomPacketPayload {
    public static final Type<AntennaConfigPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "antenna_config"));

    public AntennaConfigPayload(BlockPos pos, String name, int enabledFrequenciesMask) {
        this(pos, name, enabledFrequenciesMask, AntennaRadioConfig.DEFAULT);
    }

    public AntennaConfigPayload(BlockPos pos, String name, int enabledFrequenciesMask, AntennaRadioConfig radioConfig) {
        this(pos, name, enabledFrequenciesMask, radioConfig, "minecraft:overworld");
    }

    static final StreamCodec<FriendlyByteBuf, Integer> MASK_CODEC = StreamCodec.of((buf, mask) -> {
        if ((mask & ~((1 << TelecomFrequency.values().length) - 1)) != 0) {
            throw new EncoderException("Invalid antenna frequency mask");
        }
        buf.writeInt(mask);
    }, buf -> {
        int mask = buf.readInt();
        if ((mask & ~((1 << TelecomFrequency.values().length) - 1)) != 0) {
            throw new DecoderException("Invalid antenna frequency mask");
        }
        return mask;
    });

    static final StreamCodec<FriendlyByteBuf, AntennaRadioConfig> RADIO_CODEC = StreamCodec.of((buf, config) -> {
        if (config == null || !valid(config.sectors(), config.azimuthDegrees(), config.downtiltDegrees(),
                config.powerDbm(), config.bandwidthPercent())) {
            throw new EncoderException("Invalid antenna radio configuration");
        }
        buf.writeInt(config.sectors());
        buf.writeInt(config.azimuthDegrees());
        buf.writeInt(config.downtiltDegrees());
        buf.writeInt(config.powerDbm());
        buf.writeInt(config.bandwidthPercent());
    }, buf -> {
        int sectors = buf.readInt();
        int azimuth = buf.readInt();
        int tilt = buf.readInt();
        int power = buf.readInt();
        int bandwidth = buf.readInt();
        if (!valid(sectors, azimuth, tilt, power, bandwidth)) {
            throw new DecoderException("Invalid antenna radio configuration");
        }
        return new AntennaRadioConfig(sectors, azimuth, tilt, power, bandwidth);
    });

    private static boolean valid(int sectors, int azimuth, int tilt, int power, int bandwidth) {
        return sectors >= 0 && sectors <= 3 && azimuth >= 0 && azimuth <= 359
                && tilt >= -15 && tilt <= 45 && power >= 0 && power <= 50
                && (bandwidth == 25 || bandwidth == 50 || bandwidth == 100);
    }

    public static final StreamCodec<FriendlyByteBuf, AntennaConfigPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, AntennaConfigPayload::pos,
        ByteBufCodecs.stringUtf8(32), AntennaConfigPayload::name,
        MASK_CODEC, AntennaConfigPayload::enabledFrequenciesMask,
        RADIO_CODEC, AntennaConfigPayload::radioConfig,
        ByteBufCodecs.stringUtf8(128), AntennaConfigPayload::dimension,
        AntennaConfigPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
