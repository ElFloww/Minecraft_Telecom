package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RadioAccessServiceTest {
    private static final BlockPos A = new BlockPos(0, 64, 0);
    private static final BlockPos B = new BlockPos(10, 64, 0);

    private RadioAccessService.Hit hit(BlockPos position, TelecomFrequency frequency, float power) {
        return new RadioAccessService.Hit(position, "Antenna", frequency, power, AntennaRadioConfig.DEFAULT);
    }

    @Test
    void nearEqualCellsDoNotPingPong() {
        var selector = new RadioAccessService.Selector();
        assertEquals(A, selector.select(List.of(hit(A, TelecomFrequency.G4_700, -70), hit(B, TelecomFrequency.G4_700, -71)), 0).position());
        for (int tick = 20; tick < 200; tick += 20) {
            assertEquals(A, selector.select(List.of(hit(A, TelecomFrequency.G4_700, -71), hit(B, TelecomFrequency.G4_700, -70)), tick).position());
        }
    }

    @Test
    void sustainedChallengerNeedsTwoSecondsOfSimulationTime() {
        var selector = new RadioAccessService.Selector();
        selector.select(List.of(hit(A, TelecomFrequency.G4_700, -70)), 0);
        var hits = List.of(hit(A, TelecomFrequency.G4_700, -70), hit(B, TelecomFrequency.G4_700, -65));
        assertEquals(A, selector.select(hits, 20).position());
        for (int i = 0; i < 100; i++) assertEquals(A, selector.select(hits, 20).position());
        assertEquals(A, selector.select(hits, 40).position());
        assertEquals(B, selector.select(hits, 60).position());
    }

    @Test
    void interruptionOrLongObservationGapResetsChallenger() {
        var selector = new RadioAccessService.Selector();
        selector.select(List.of(hit(A, TelecomFrequency.G4_700, -70)), 0);
        var stronger = List.of(hit(A, TelecomFrequency.G4_700, -70), hit(B, TelecomFrequency.G4_700, -65));
        selector.select(stronger, 20);
        selector.select(List.of(hit(A, TelecomFrequency.G4_700, -70), hit(B, TelecomFrequency.G4_700, -75)), 40);
        assertEquals(A, selector.select(stronger, 60).position());
        assertEquals(A, selector.select(stronger, 400).position());
        assertEquals(A, selector.select(stronger, 420).position());
        assertEquals(B, selector.select(stronger, 440).position());
    }

    @Test
    void lossSwitchesImmediatelyAndDoesNotRememberMissingCells() {
        var selector = new RadioAccessService.Selector();
        selector.select(List.of(hit(A, TelecomFrequency.G4_700, -70)), 0);
        assertEquals(B, selector.select(List.of(hit(B, TelecomFrequency.G4_700, -90)), 20).position());
        assertNull(selector.select(List.of(), 40));
        assertEquals(A, selector.select(List.of(hit(A, TelecomFrequency.G4_700, -100)), 60).position());
    }

    @Test
    void weakNewerGenerationDoesNotBypassHysteresis() {
        var selector = new RadioAccessService.Selector();
        selector.select(List.of(hit(A, TelecomFrequency.G4_700, -70)), 0);
        for (int tick = 20; tick < 200; tick += 20) {
            assertEquals(A, selector.select(List.of(hit(A, TelecomFrequency.G4_700, -70), hit(B, TelecomFrequency.G5_700, -108)), tick).position());
        }
        var usable = List.of(hit(A, TelecomFrequency.G4_700, -70), hit(B, TelecomFrequency.G5_700, -90));
        assertEquals(A, selector.select(usable, 200).position());
        assertEquals(A, selector.select(usable, 220).position());
        assertEquals(B, selector.select(usable, 240).position());
    }

    @Test
    void ineligibleNewerCellDoesNotHideStrongSameGenerationChallenger() {
        var selector = new RadioAccessService.Selector();
        selector.select(List.of(hit(A, TelecomFrequency.G4_700, -80)), 0);
        var hits = List.of(hit(A, TelecomFrequency.G4_700, -80), hit(B, TelecomFrequency.G4_700, -60),
                hit(new BlockPos(20, 64, 0), TelecomFrequency.G5_700, -108));
        assertEquals(A, selector.select(hits, 20).position());
        assertEquals(A, selector.select(hits, 40).position());
        assertEquals(B, selector.select(hits, 60).position());
    }

    @Test
    void overlappingTransmittersReduceEfficiencyButSeparateSpectrumDoesNot() {
        var desired = hit(A, TelecomFrequency.G4_700, -60);
        double alone = RadioAccessService.interferenceEfficiency(desired, List.of(desired));
        double overlapping = RadioAccessService.interferenceEfficiency(desired, List.of(desired, hit(B, TelecomFrequency.G4_700, -60)));
        double separate = RadioAccessService.interferenceEfficiency(desired, List.of(desired, hit(B, TelecomFrequency.G4_1800, -60)));
        assertEquals(1, alone, .0001);
        assertTrue(overlapping < .2);
        assertEquals(alone, separate, .0001);
    }

    @Test
    void interferenceIncludesOverlappingTechnologiesAndConfiguredWidths() {
        var desired = hit(A, TelecomFrequency.G4_700, -70);
        assertTrue(RadioAccessService.interferenceEfficiency(desired, List.of(desired, hit(B, TelecomFrequency.G5_700, -70))) < 1);
        assertEquals(20, RadioAccessService.channelWidthMhz(TelecomFrequency.G4_700, AntennaRadioConfig.DEFAULT));
        var narrow = new AntennaRadioConfig(0, 0, 0, 30, 25);
        assertEquals(5, RadioAccessService.channelWidthMhz(TelecomFrequency.G4_700, narrow));
        assertEquals(100, RadioAccessService.channelWidthMhz(TelecomFrequency.G5_26000, narrow));
    }
}
