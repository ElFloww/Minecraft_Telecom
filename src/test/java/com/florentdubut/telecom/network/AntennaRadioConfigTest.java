package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static com.florentdubut.telecom.network.TelecomFrequency.*;
import static org.junit.jupiter.api.Assertions.*;

class AntennaRadioConfigTest {
    @Test
    void validatesEveryFieldWithoutSilentlyNormalizingUserInput() {
        for (int sectors : new int[]{-1, 4, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new AntennaRadioConfig(sectors, 0, 0, 30, 100));
        }
        for (int azimuth : new int[]{-1, 360, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new AntennaRadioConfig(1, azimuth, 0, 30, 100));
        }
        for (int tilt : new int[]{-16, 46}) {
            assertThrows(IllegalArgumentException.class, () -> new AntennaRadioConfig(1, 0, tilt, 30, 100));
        }
        for (int power : new int[]{-1, 51}) {
            assertThrows(IllegalArgumentException.class, () -> new AntennaRadioConfig(0, 0, 0, power, 100));
        }
        for (int bandwidth : new int[]{-1, 0, 1, 24, 26, 49, 51, 99, 101}) {
            assertThrows(IllegalArgumentException.class, () -> new AntennaRadioConfig(0, 0, 0, 30, bandwidth));
        }
        assertDoesNotThrow(() -> new AntennaRadioConfig(3, 359, -15, 0, 25));
        assertDoesNotThrow(() -> new AntennaRadioConfig(1, 0, 45, 50, 50));
    }

    @Test
    void bandwidthScalesReferenceCapacityWithoutSectorPowerOrTiltMultipliers() {
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            assertEquals(frequency.getMaxSpeedMb(), AntennaRadioConfig.DEFAULT.capacityMbps(frequency));
            for (int percent : new int[]{25, 50, 100}) {
                for (int sectors = 0; sectors <= 3; sectors++) {
                    var config = new AntennaRadioConfig(sectors, 359, 45, 50, percent);
                    assertEquals(Math.max(1, frequency.getMaxSpeedMb() * percent / 100), config.capacityMbps(frequency));
                }
            }
        }
        assertEquals(250, new AntennaRadioConfig(3, 0, 0, 30, 25).capacityMbps(G5_3500));
        assertEquals(0.05, AntennaRadioConfig.uploadRatio(G2_900));
        assertEquals(0.15, AntennaRadioConfig.uploadRatio(G3_900));
        assertEquals(0.30, AntennaRadioConfig.uploadRatio(G4_700));
        assertEquals(0.50, AntennaRadioConfig.uploadRatio(G5_700));
    }

    @Test
    void minecraftAzimuthAndWrapUseNearestEvenlySpacedLobe() {
        BlockPos source = BlockPos.ZERO;
        var south = new AntennaRadioConfig(1, 0, 0, 30, 100);
        assertEquals(0, south.signalAdjustmentDb(source, source.south(100)), 1e-9);
        assertEquals(-30, south.signalAdjustmentDb(source, source.north(100)), 1e-9);
        assertEquals(0, new AntennaRadioConfig(1, 90, 0, 30, 100)
                .signalAdjustmentDb(source, source.west(100)), 1e-9);
        assertEquals(new AntennaRadioConfig(1, 1, 0, 30, 100).signalAdjustmentDb(source, source.south(100)),
                new AntennaRadioConfig(1, 359, 0, 30, 100).signalAdjustmentDb(source, source.south(100)), 1e-9);
        var two = new AntennaRadioConfig(2, 0, 0, 30, 100);
        assertEquals(0, two.signalAdjustmentDb(source, source.north(100)), 1e-9);
        var three = new AntennaRadioConfig(3, 0, 0, 30, 100);
        assertEquals(0, three.signalAdjustmentDb(source, new BlockPos(-866, 0, -500)), 1e-6);
        assertEquals(0, three.signalAdjustmentDb(source, new BlockPos(866, 0, -500)), 1e-6);
        assertTrue(three.signalAdjustmentDb(source, source.north(100)) < 0);
    }

    @Test
    void downtiltIsDownwardAndPatternLossIsBoundedWhileOmniIgnoresOrientation() {
        BlockPos source = new BlockPos(0, 100, 0);
        var down = new AntennaRadioConfig(1, 0, 45, 30, 100);
        assertEquals(0, down.signalAdjustmentDb(source, source.offset(0, -100, 100)), 1e-9);
        assertEquals(-30, down.signalAdjustmentDb(source, source.offset(0, 100, 100)), 1e-9);
        assertEquals(-60, down.signalAdjustmentDb(source, source.offset(0, 100, -100)), 1e-9);
        var up = new AntennaRadioConfig(1, 0, -15, 30, 100);
        assertTrue(up.signalAdjustmentDb(source, source.offset(0, 27, 100))
                > up.signalAdjustmentDb(source, source.offset(0, -27, 100)));
        for (BlockPos target : new BlockPos[]{source, source.above(100), source.north(100), source.below(100)}) {
            assertEquals(0, AntennaRadioConfig.DEFAULT.signalAdjustmentDb(source, target));
            assertEquals(20, new AntennaRadioConfig(0, 123, 45, 50, 25).signalAdjustmentDb(source, target));
            assertEquals(-30, new AntennaRadioConfig(0, 0, -15, 0, 100).signalAdjustmentDb(source, target));
            assertTrue(Double.isFinite(down.signalAdjustmentDb(source, target)));
        }
    }

    @Test
    void compoundPersistenceDefaultsMissingFieldsAndSafelyRejectsInvalidSettings() {
        CompoundTag tag = new CompoundTag();
        assertEquals(AntennaRadioConfig.DEFAULT, AntennaRadioConfig.read(tag));
        tag.putInt("RadioPower", 45);
        assertEquals(new AntennaRadioConfig(0, 0, 0, 45, 100), AntennaRadioConfig.read(tag));
        var config = new AntennaRadioConfig(3, 359, -15, 50, 25);
        config.writeTo(tag);
        assertEquals(config, AntennaRadioConfig.read(tag));
        tag.putInt("RadioBandwidth", 75);
        assertEquals(AntennaRadioConfig.DEFAULT, AntennaRadioConfig.read(tag));
        tag = new CompoundTag();
        tag.putString("RadioPower", "broken");
        assertEquals(AntennaRadioConfig.DEFAULT, AntennaRadioConfig.read(tag));
    }
}
