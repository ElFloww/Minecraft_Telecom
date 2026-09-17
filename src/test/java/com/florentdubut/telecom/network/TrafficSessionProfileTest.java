package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TrafficSessionProfileTest {
    private static final UUID ID = UUID.fromString("a7417289-1713-4562-8fc3-802819864351");

    @Test
    void profileIsRepeatableForUuidPhaseAndTickAndIndependentOfPolling() {
        TrafficSession first = session(ID, false, 1000, 1000, 1000);
        TrafficSession replay = session(ID, false, 1000, 1000, 1000);
        advance(first, 60);
        advance(replay, 60);
        for (int tick = 0; tick < 2000; tick++) {
            int demand = first.getRequestedBandwidth(800);
            assertEquals(demand, first.getRequestedBandwidth(800));
            first.clearCurrentBandwidth();
            assertEquals(demand, replay.getRequestedBandwidth(800));
            assertEquals(0, first.getFinalDownBw());
            assertEquals(0, first.getFinalUpBw());
            first.tick();
            replay.tick();
        }
    }

    @Test
    void uuidAndTransferPhaseHaveDistinctPlateauProfiles() {
        TrafficSession first = session(ID, false, 1_000_000, 1_000_000, 1000);
        TrafficSession other = session(new UUID(ID.getMostSignificantBits(), ID.getLeastSignificantBits() + 1),
                false, 1_000_000, 1_000_000, 1000);
        advance(first, 80);
        advance(other, 80);
        int[] down = demands(first, 200);
        assertFalse(Arrays.equals(down, demands(other, 200)));
        advance(first, 800);
        assertEquals(TrafficSession.SessionState.UPLOAD, first.getState());
        assertFalse(Arrays.equals(down, demands(first, 200)));
    }

    @Test
    void bothPhasesWarmUpFrom35PercentThenStayWithinTheRealCeiling() {
        TrafficSession session = session(ID, false, 10_000, 500, 1000);
        advance(session, 60);
        for (int phase = 0; phase < 2; phase++) {
            int ceiling = phase == 0 ? 800 : 500;
            assertEquals((int) Math.ceil(ceiling * 0.35), session.getRequestedBandwidth(800));
            int previous = 0;
            for (int tick = 0; tick < 20; tick++) {
                int demand = session.getRequestedBandwidth(800);
                assertTrue(demand > previous);
                assertTrue(demand <= ceiling);
                previous = demand;
                session.tick();
            }
            for (int tick = 20; tick < 1000; tick++) {
                int demand = session.getRequestedBandwidth(800);
                assertTrue(demand >= Math.ceil(ceiling * 0.94));
                assertTrue(demand <= ceiling);
                assertTrue(Math.abs(demand - previous) <= ceiling * 0.04 + 1);
                previous = demand;
                session.tick();
            }
        }
    }

    @Test
    void plateauHasSlow37And83TickComponentsRatherThanHttpPollingAliasing() {
        TrafficSession session = session(ID, false, 1_000_000, 1_000_000, 12_000);
        advance(session, 80);
        int[] values = demands(session, 37 * 83 + 83);
        for (int period : new int[]{37, 83}) {
            double sin = 0;
            double cos = 0;
            for (int tick = 0; tick < 37 * 83; tick++) {
                double angle = 2 * Math.PI * tick / period;
                sin += (values[tick] - 970_000) * Math.sin(angle);
                cos += (values[tick] - 970_000) * Math.cos(angle);
            }
            assertEquals(period == 37 ? 18_000 : 12_000, 2 * Math.hypot(sin, cos) / (37 * 83), 1);
        }
        for (int offset : new int[]{37, 40, 83}) {
            assertTrue(java.util.stream.IntStream.range(0, 200)
                    .anyMatch(tick -> values[tick] != values[tick + offset]));
        }
        for (int tick = 1; tick < values.length; tick++) {
            assertTrue(Math.abs(values[tick] - values[tick - 1]) <= 4000);
            assertTrue(values[tick] >= 940_000 && values[tick] <= 1_000_000);
        }
    }

    @Test
    void oneMegabitSurvivesRoundingAndZeroNeverBecomesTraffic() {
        TrafficSession one = session(ID, false, 1, 1, 1000);
        TrafficSession zeroTarget = session(ID, false, 0, 0, 1000);
        advance(one, 60);
        advance(zeroTarget, 60);
        for (int tick = 0; tick < 2000; tick++) {
            assertEquals(1, one.getRequestedBandwidth(1000));
            assertEquals(0, one.getRequestedBandwidth(0));
            assertEquals(0, zeroTarget.getRequestedBandwidth(1000));
            one.tick();
            zeroTarget.tick();
        }
    }

    @Test
    void passiveDemandKeepsTargetsAndPathLimitsWithoutWarmupOrVariation() {
        TrafficSession passive = session(ID, true, 100, 60, 1000);
        passive.setFrequenciesMask(3);
        assertEquals(0, passive.getRequestedBandwidth(1000));
        advance(passive, 60);
        for (int tick = 0; tick < 2000; tick++) {
            int target = tick < 1000 ? 100 : 60;
            assertEquals(target, passive.getRequestedBandwidth(1000));
            assertEquals(40, passive.getRequestedBandwidth(40));
            assertEquals(0, passive.getRequestedBandwidth(0));
            passive.tick();
        }
        assertEquals(0, passive.getRequestedBandwidth(1000));
        assertEquals(3, passive.getFrequenciesMask());
    }

    @Test
    void meansIncludeZerosRoundFromCumulativeSumAndKeepPhasesSeparate() {
        TrafficSession session = session(ID, false, 100, 100, 4);
        session.setActualBandwidth(999);
        assertEquals(0, session.getActualBandwidth());
        advance(session, 4);
        int[] down = {10, 0, 0, 1};
        int[] running = {10, 5, 3, 3};
        for (int i = 0; i < down.length; i++) {
            session.setActualBandwidth(down[i]);
            assertEquals(running[i], session.getFinalDownBw());
            assertEquals(0, session.getFinalUpBw());
            session.clearCurrentBandwidth();
            assertEquals(running[i], session.getFinalDownBw());
            session.tick();
        }
        int[] up = {0, 7, 0, 3};
        for (int value : up) {
            session.setActualBandwidth(value);
            assertEquals(3, session.getFinalDownBw());
            session.tick();
        }
        assertEquals(TrafficSession.SessionState.FINISHED, session.getState());
        assertEquals(3, session.getFinalUpBw());
        session.setActualBandwidth(999);
        session.clearCurrentBandwidth();
        session.tick();
        assertEquals(0, session.getActualBandwidth());
        assertEquals(3, session.getFinalDownBw());
        assertEquals(3, session.getFinalUpBw());
    }

    @Test
    void clearingAndUnsampledTicksDoNotDiluteMeansAndFailurePreservesThem() {
        TrafficSession session = session(ID, false, 100, 100, 10);
        advance(session, 10);
        session.setActualBandwidth(100);
        for (int i = 0; i < 100; i++) session.clearCurrentBandwidth();
        advance(session, 9);
        assertEquals(100, session.getFinalDownBw());
        session.setActualBandwidth(0);
        assertEquals(50, session.getFinalDownBw());
        session.tick();
        session.setActualBandwidth(30);
        session.tick();
        session.setActualBandwidth(0);
        session.fail("route_lost");
        session.setActualBandwidth(999);
        session.tick();
        assertEquals(0, session.getActualBandwidth());
        assertEquals(50, session.getFinalDownBw());
        assertEquals(15, session.getFinalUpBw());
        assertEquals(0, session.getRequestedBandwidth(1000));
    }

    @Test
    void clearingLiveCountersPreservesTheMeasurementButDoesNotLeakItAcrossPhases() {
        TrafficSession session = session(ID, false, 100, 100, 10);
        assertEquals(0, session.getMeasuredBandwidth());
        advance(session, 10);
        session.setActualBandwidth(85);
        session.clearCurrentBandwidth();
        assertEquals(0, session.getActualBandwidth());
        assertEquals(85, session.getMeasuredBandwidth());
        assertEquals(85, session.getFinalDownBw());
        advance(session, 10);
        assertEquals(TrafficSession.SessionState.UPLOAD, session.getState());
        assertEquals(0, session.getMeasuredBandwidth());
        session.setActualBandwidth(70);
        session.clearCurrentBandwidth();
        assertEquals(70, session.getMeasuredBandwidth());
        session.setActualBandwidth(0);
        assertEquals(0, session.getMeasuredBandwidth(), "A real zero sample must replace the last nonzero sample");
        session.setActualBandwidth(50);
        session.fail("route_lost");
        assertEquals(0, session.getMeasuredBandwidth());
        assertEquals(40, session.getFinalUpBw());
    }

    @Test
    void fullLengthMillionMegabitSamplesDoNotOverflowEitherPhase() {
        TrafficSession session = session(ID, false, 1_000_000, 1_000_000, 12_000);
        advance(session, 60);
        for (int tick = 0; tick < 24_000; tick++) {
            session.setActualBandwidth(tick < 12_000 ? 1_000_000 : tick % 2 == 0 ? 1_000_000 : 0);
            session.tick();
        }
        assertEquals(1_000_000, session.getFinalDownBw());
        assertEquals(500_000, session.getFinalUpBw());
        assertEquals(TrafficSession.SessionState.FINISHED, session.getState());
    }

    private TrafficSession session(UUID id, boolean passive, int down, int up, int duration) {
        return new TrafficSession(id, BlockPos.ZERO, new BlockPos(10, 0, 0), "test", down, up, duration, passive, "test");
    }

    private void advance(TrafficSession session, int ticks) {
        for (int i = 0; i < ticks; i++) session.tick();
    }

    private int[] demands(TrafficSession session, int ticks) {
        int[] result = new int[ticks];
        for (int i = 0; i < ticks; i++) {
            result[i] = session.getRequestedBandwidth(1_000_000);
            session.tick();
        }
        return result;
    }
}
