package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static com.florentdubut.telecom.network.TelecomFrequency.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RadioSelectionTest {
    private static final BlockPos POSITION = new BlockPos(1, 64, 1);

    @Test
    void newerGenerationWinsBeforeSignalStrengthOrNominalSpeed() {
        for (var pair : List.of(List.of(G3_900, G2_1800), List.of(G4_700, G3_2100), List.of(G5_700, G4_2600))) {
            assertPreferred(pair.get(0), -119, POSITION, pair.get(1), -30, POSITION.west());
        }
    }

    @Test
    void strongerSignalWinsWithinGenerationBeforeBandwidthAndPosition() {
        assertPreferred(G4_700, -70, POSITION, G4_2600, -80, POSITION.west());
        assertPreferred(G5_700, -70, POSITION, G5_26000, -80, POSITION.west());
        assertPreferred(G2_900, -70, POSITION, G2_900, -70.001f, POSITION.west());
    }

    @Test
    void equalGenerationAndSignalPreferSmallerSignedPackedAntennaPosition() {
        for (BlockPos other : List.of(new BlockPos(-1, 64, 1), POSITION.east(), POSITION.below(), POSITION.north())) {
            BlockPos lower = POSITION.asLong() < other.asLong() ? POSITION : other;
            BlockPos higher = lower.equals(POSITION) ? other : POSITION;
            assertPreferred(G4_2600, -80, lower, G4_700, -80, higher);
        }
    }

    @Test
    void sameAntennaSignalAndGenerationPreferEarlierBandOrdinal() {
        assertPreferred(G2_900, -80, POSITION, G2_1800, -80, POSITION);
        assertPreferred(G4_700, -80, POSITION, G4_2600, -80, POSITION);
        assertPreferred(G5_700, -80, POSITION, G5_26000, -80, POSITION);
    }

    @Test
    void identicalInputsCompareEqualForEveryBand() {
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            assertEquals(0, RadioSelection.compare(frequency, -80, POSITION, frequency, -80, POSITION));
        }
    }

    @Test
    void selectionIsIndependentOfCandidateIterationOrder() {
        record Candidate(TelecomFrequency frequency, float power, BlockPos position) { }
        Candidate winner = new Candidate(G5_700, -80, POSITION);
        List<Candidate> candidates = new ArrayList<>(List.of(
                new Candidate(G2_900, -30, POSITION), new Candidate(G4_2600, -40, POSITION),
                new Candidate(G5_700, -90, POSITION.west()), new Candidate(G5_700, -80, POSITION.east()),
                new Candidate(G5_3500, -80, POSITION), winner));
        Random random = new Random(0);
        for (int iteration = 0; iteration < 100; iteration++) {
            Collections.shuffle(candidates, random);
            Candidate selected = candidates.stream().max((a, b) -> RadioSelection.compare(
                    a.frequency(), a.power(), a.position(), b.frequency(), b.power(), b.position())).orElseThrow();
            assertEquals(winner, selected);
        }
    }

    private static void assertPreferred(TelecomFrequency frequency, float power, BlockPos position,
                                        TelecomFrequency otherFrequency, float otherPower, BlockPos otherPosition) {
        assertTrue(RadioSelection.compare(frequency, power, position, otherFrequency, otherPower, otherPosition) > 0);
        assertTrue(RadioSelection.compare(otherFrequency, otherPower, otherPosition, frequency, power, position) < 0);
    }
}
