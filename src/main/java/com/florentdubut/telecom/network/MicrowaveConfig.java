package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/** FH settings only, never operational state. Minecraft yaw: 0 south, 90 west; elevation is positive up. */
public record MicrowaveConfig(BlockPos peer, int channel, int frequencyGhz, int azimuthDegrees,
                              int elevationDegrees, boolean enabled) {
    public static final MicrowaveConfig DEFAULT = new MicrowaveConfig(null, 1, 11, 0, 0, false);

    public MicrowaveConfig {
        if (channel < 1 || channel > 16 || (frequencyGhz != 6 && frequencyGhz != 11
                && frequencyGhz != 18 && frequencyGhz != 38) || azimuthDegrees < 0 || azimuthDegrees > 359
                || elevationDegrees < -90 || elevationDegrees > 90) {
            throw new IllegalArgumentException("Invalid microwave configuration");
        }
        if (peer != null) peer = peer.immutable();
    }

    public int nominalCapacityMbps() {
        return switch (frequencyGhz) {
            case 6 -> 300;
            case 11 -> 600;
            case 18 -> 1000;
            default -> 2000;
        };
    }

    public static MicrowaveConfig read(CompoundTag tag) {
        return read(tag::getIntOr, tag::getBooleanOr, tag::getInt);
    }

    public static MicrowaveConfig read(ValueInput input) {
        return read(input::getIntOr, input::getBooleanOr, input::getInt);
    }

    private static MicrowaveConfig read(BiFunction<String, Integer, Integer> ints,
                                        BiFunction<String, Boolean, Boolean> bools,
                                        Function<String, Optional<Integer>> optional) {
        try {
            BlockPos peer = null;
            if (bools.apply("MicrowaveHasPeer", false)) {
                peer = new BlockPos(optional.apply("MicrowavePeerX").orElseThrow(),
                        optional.apply("MicrowavePeerY").orElseThrow(), optional.apply("MicrowavePeerZ").orElseThrow());
                if (Math.abs((long) peer.getX()) >= 30_000_000 || Math.abs((long) peer.getZ()) >= 30_000_000
                        || peer.getY() < -2048 || peer.getY() > 2047) return DEFAULT;
            }
            return new MicrowaveConfig(peer, ints.apply("MicrowaveChannel", 1),
                    ints.apply("MicrowaveFrequency", 11), ints.apply("MicrowaveAzimuth", 0),
                    ints.apply("MicrowaveElevation", 0), bools.apply("MicrowaveEnabled", false));
        } catch (IllegalArgumentException | java.util.NoSuchElementException invalid) {
            return DEFAULT;
        }
    }

    public void writeTo(CompoundTag tag) { writeTo(tag::putInt, tag::putBoolean); }
    public void writeTo(ValueOutput output) { writeTo(output::putInt, output::putBoolean); }

    private void writeTo(BiConsumer<String, Integer> ints, BiConsumer<String, Boolean> bools) {
        bools.accept("MicrowaveHasPeer", peer != null);
        if (peer != null) {
            ints.accept("MicrowavePeerX", peer.getX());
            ints.accept("MicrowavePeerY", peer.getY());
            ints.accept("MicrowavePeerZ", peer.getZ());
        }
        ints.accept("MicrowaveChannel", channel);
        ints.accept("MicrowaveFrequency", frequencyGhz);
        ints.accept("MicrowaveAzimuth", azimuthDegrees);
        ints.accept("MicrowaveElevation", elevationDegrees);
        bools.accept("MicrowaveEnabled", enabled);
    }
}
