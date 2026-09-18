package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrafficSessionRadioTest {
    @Test
    void attachmentUpdatesOnlySourceAndRadioCeilingsNotIdentityPhaseDestinationOrInitialTargets() {
        var band = TelecomFrequency.G4_1800;
        int mask = 1 << band.ordinal();
        var session = new TrafficSession(BlockPos.ZERO, BlockPos.ZERO.east(), "mobile", 100, 30, 100, true, "mobile:test");
        var identity = session.getSessionId();
        for (int tick = 0; tick < 60; tick++) session.tick();
        session.setActualBandwidth(60);
        session.updateRadioAttachment(BlockPos.ZERO.west(), mask, Map.of(band, 20), Map.of(band, 5));
        assertEquals(BlockPos.ZERO.west(), session.getSourcePos());
        assertEquals(BlockPos.ZERO.west(), session.getAntennaPos());
        assertEquals(BlockPos.ZERO.east(), session.getDestPos());
        assertEquals(identity, session.getSessionId());
        assertEquals(TrafficSession.SessionState.DOWNLOAD, session.getState());
        assertEquals(0, session.getTicksElapsed());
        assertEquals(60, session.getFinalDownBw());
        assertEquals(100, session.getTargetDownBw());
        assertEquals(30, session.getTargetUpBw());
        assertEquals(20, session.getRequestedBandwidth(1000));
        assertEquals(10, session.getRequestedBandwidth(10));
        session.updateRadioAttachment(BlockPos.ZERO, mask, Map.of(band, 500), Map.of(band, 5));
        assertEquals(100, session.getRequestedBandwidth(1000));
        for (int tick = 0; tick < 100; tick++) session.tick();
        assertEquals(5, session.getRequestedBandwidth(1000));
        session.updateRadioAttachment(BlockPos.ZERO, 0, Map.of(band, 500), Map.of(band, 500));
        assertEquals(0, session.getRequestedBandwidth(1000));
    }

    @Test
    void ceilingsAreDefensiveSnapshotsAndEmptyIsNotAnUnconfiguredFallback() {
        var session = new TrafficSession(BlockPos.ZERO, BlockPos.ZERO.east(), "mobile", 100, 30, 100, true, "mobile:test");
        var band = TelecomFrequency.G4_1800;
        Map<TelecomFrequency, Integer> caps = new HashMap<>(Map.of(band, 10));
        session.updateRadioAttachment(BlockPos.ZERO, 1 << band.ordinal(), caps, caps);
        caps.clear();
        assertEquals(Map.of(band, 10), session.getRadioDownCaps());
        assertThrows(UnsupportedOperationException.class, () -> session.getRadioUpCaps().clear());
        session.updateRadioAttachment(BlockPos.ZERO, 1 << band.ordinal(), Map.of(), Map.of());
        for (int tick = 0; tick < 60; tick++) session.tick();
        assertTrue(session.hasRadioCaps());
        assertEquals(0, session.getRequestedBandwidth(1000));
    }

    @Test
    void radioLatencyKeepsInitialJitterAndChangesOnlyWithTechnology() {
        var session = new TrafficSession(BlockPos.ZERO, BlockPos.ZERO.east(), "mobile", 1, 1, 100, false, "mobile:test");
        session.setExtraPing(347);
        for (var band : new TelecomFrequency[]{TelecomFrequency.G2_900, TelecomFrequency.G2_1800}) {
            session.updateRadioAttachment(BlockPos.ZERO, 1 << band.ordinal(), Map.of(band, 1), Map.of(band, 1));
            assertEquals(347, session.getExtraPing());
        }
        var bands = new TelecomFrequency[]{TelecomFrequency.G5_3500, TelecomFrequency.G4_1800,
                TelecomFrequency.G3_2100, TelecomFrequency.G2_900};
        int[] latencies = {15, 40, 95, 300};
        for (int i = 0; i < bands.length; i++) {
            var band = bands[i];
            for (int refresh = 0; refresh < 10; refresh++) {
                session.updateRadioAttachment(BlockPos.ZERO, 1 << band.ordinal(), Map.of(band, 1), Map.of(band, 1));
                assertEquals(latencies[i], session.getExtraPing());
            }
        }
    }
}
