package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Immutable radio settings; bandwidth is a percentage of each band's reference MHz.
 * Azimuth follows Minecraft yaw: 0 is south (+Z), 90 is west (-X).
 * Positive downtilt points below the horizon. Zero sectors is omnidirectional and ignores tilt.
 */
public record AntennaRadioConfig(int sectors, int azimuthDegrees, int downtiltDegrees,
                                 int powerDbm, int bandwidthPercent) {
    public static final AntennaRadioConfig DEFAULT = new AntennaRadioConfig(0, 0, 0, 30, 100);

    public AntennaRadioConfig {
        if (sectors < 0 || sectors > 3 || azimuthDegrees < 0 || azimuthDegrees > 359
                || downtiltDegrees < -15 || downtiltDegrees > 45 || powerDbm < 0 || powerDbm > 50
                || (bandwidthPercent != 25 && bandwidthPercent != 50 && bandwidthPercent != 100)) {
            throw new IllegalArgumentException("Invalid antenna radio configuration");
        }
    }

    /** Per-band capacity, rounded down with a one-Mbps minimum; sectors do not multiply it. */
    public int capacityMbps(TelecomFrequency frequency) {
        return Math.max(1, frequency.getMaxSpeedMb() * bandwidthPercent / 100);
    }

    public static double uploadRatio(TelecomFrequency frequency) {
        return switch (frequency.getTechnology()) {
            case "5G" -> 0.50;
            case "4G" -> 0.30;
            case "3G" -> 0.15;
            default -> 0.05;
        };
    }

    public static double referenceWidthMhz(TelecomFrequency frequency) {
        return switch (frequency.getTechnology()) {
            case "2G" -> .2;
            case "3G" -> 5;
            case "4G" -> 20;
            default -> frequency == TelecomFrequency.G5_26000 ? 400 : 100;
        };
    }

    public double signalAdjustmentDb(BlockPos source, BlockPos target) {
        double adjustment = powerDbm - 30.0;
        if (sectors == 0 || source.equals(target)) return adjustment;
        double dx = (double) target.getX() - source.getX();
        double dy = (double) target.getY() - source.getY();
        double dz = (double) target.getZ() - source.getZ();
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double horizontalAngle = 180;
        for (int sector = 0; sector < sectors; sector++) {
            horizontalAngle = Math.min(horizontalAngle,
                    Math.abs(Math.IEEEremainder(bearing - azimuthDegrees - sector * 360.0 / sectors, 360)));
        }
        double verticalAngle = Math.toDegrees(Math.atan2(-dy, Math.hypot(dx, dz))) - downtiltDegrees;
        return adjustment - Math.min(30, 12 * Math.pow(horizontalAngle / 65, 2))
                - Math.min(30, 12 * Math.pow(verticalAngle / 15, 2));
    }

    public static AntennaRadioConfig read(CompoundTag tag) { return read(tag::getIntOr); }
    public static AntennaRadioConfig read(ValueInput input) { return read(input::getIntOr); }

    private static AntennaRadioConfig read(BiFunction<String, Integer, Integer> input) {
        try {
            return new AntennaRadioConfig(input.apply("RadioSectors", DEFAULT.sectors),
                    input.apply("RadioAzimuth", DEFAULT.azimuthDegrees),
                    input.apply("RadioDowntilt", DEFAULT.downtiltDegrees),
                    input.apply("RadioPower", DEFAULT.powerDbm),
                    input.apply("RadioBandwidth", DEFAULT.bandwidthPercent));
        } catch (IllegalArgumentException invalid) {
            // Corrupt persisted settings must not prevent a world or block entity from loading.
            return DEFAULT;
        }
    }

    public void writeTo(CompoundTag tag) { writeTo(tag::putInt); }
    public void writeTo(ValueOutput output) { writeTo(output::putInt); }

    private void writeTo(BiConsumer<String, Integer> output) {
        output.accept("RadioSectors", sectors);
        output.accept("RadioAzimuth", azimuthDegrees);
        output.accept("RadioDowntilt", downtiltDegrees);
        output.accept("RadioPower", powerDbm);
        output.accept("RadioBandwidth", bandwidthPercent);
    }
}
