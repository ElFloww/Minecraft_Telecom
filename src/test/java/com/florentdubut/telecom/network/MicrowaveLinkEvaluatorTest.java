package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.florentdubut.telecom.network.MicrowaveLinkEvaluator.MAX_PROBES;
import static com.florentdubut.telecom.network.SignalPropagator.Material.*;
import static org.junit.jupiter.api.Assertions.*;

class MicrowaveLinkEvaluatorTest {
    static MicrowaveConfig pointing(BlockPos from, BlockPos to, int frequency) {
        double dx = (double) to.getX() - from.getX(), dy = (double) to.getY() - from.getY();
        double dz = (double) to.getZ() - from.getZ();
        int azimuth = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(-dx, dz))), 360);
        int elevation = (int) Math.round(Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        return new MicrowaveConfig(to, 1, frequency, azimuth, elevation, true);
    }

    static MicrowaveLinkEvaluator.Trace trace(BlockPos a, BlockPos b, int frequency) {
        return new MicrowaveLinkEvaluator.Trace(a, pointing(a, b, frequency), b, pointing(b, a, frequency));
    }

    @Test
    void axialDiagonalAndVerticalGeometryExemptsOnlyEndpointHardware() {
        BlockPos a = new BlockPos(0, 64, 0);
        for (BlockPos b : new BlockPos[]{a.offset(100, 0, 0), a.offset(80, 80, 80), a.offset(0, 100, 0)}) {
            var trace = trace(a, b, 11);
            while (!trace.advance(p -> p.equals(a) || p.equals(b) ? SOLID : AIR, 31, Long.MAX_VALUE)) { }
            assertEquals("ready", trace.result().state());
            assertEquals(600, trace.result().capacityMbps());
            BlockPos middle = new BlockPos((a.getX() + b.getX()) / 2, (a.getY() + b.getY()) / 2,
                    (a.getZ() + b.getZ()) / 2);
            var blocked = trace(a, b, 11);
            blocked.advance(p -> p.equals(middle) ? SOLID : AIR, MAX_PROBES, Long.MAX_VALUE);
            assertEquals("blocked", blocked.result().state());
            assertEquals(middle, blocked.result().blocker());
        }
    }

    @Test
    void fresnelOnlyObstacleAndUnknownOffAxisNeverRoute() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(1000, 64, 0);
        for (var material : new SignalPropagator.Material[]{SOLID, UNKNOWN}) {
            var trace = trace(a, b, 6);
            trace.advance(p -> p.getX() >= 490 && p.getX() <= 510 && p.getY() > 64 ? material : AIR,
                    MAX_PROBES, Long.MAX_VALUE);
            assertEquals(material == SOLID ? "fresnel_blocked" : "unknown", trace.result().state());
            assertEquals(0, trace.result().capacityMbps());
        }
    }

    @Test
    void reciprocalChannelFrequencyRangeAndAlignmentAreValidatedBeforeTerrain() {
        BlockPos a = BlockPos.ZERO, b = new BlockPos(0, 0, 100);
        MicrowaveConfig ca = pointing(a, b, 11), cb = pointing(b, a, 11);
        assertState("unpaired", a, ca, b, new MicrowaveConfig(null, 1, 11, 180, 0, true));
        assertState("disabled", a, MicrowaveConfig.DEFAULT, b, cb);
        assertState("channel_mismatch", a, ca, b, new MicrowaveConfig(a, 2, 11, 180, 0, true));
        assertState("channel_mismatch", a, ca, b, pointing(b, a, 18));
        assertState("misaligned", a, new MicrowaveConfig(b, 1, 11, 30, 0, true), b, cb);
        BlockPos far = new BlockPos(0, 0, 4097);
        assertState("out_of_range", a, pointing(a, far, 11), far, pointing(far, a, 11));
        var degraded = new MicrowaveLinkEvaluator.Trace(a, new MicrowaveConfig(b, 1, 11, 20, 0, true), b, cb);
        degraded.advance(p -> AIR, MAX_PROBES, Long.MAX_VALUE);
        assertEquals("degraded", degraded.result().state());
        assertTrue(degraded.result().capacityMbps() > 0 && degraded.result().capacityMbps() < 600);
    }

    private void assertState(String state, BlockPos a, MicrowaveConfig ca, BlockPos b, MicrowaveConfig cb) {
        var trace = new MicrowaveLinkEvaluator.Trace(a, ca, b, cb);
        assertTrue(trace.advance(p -> { fail("Invalid configuration sampled terrain"); return AIR; }, 1, Long.MAX_VALUE));
        assertEquals(state, trace.result().state());
    }

    @Test
    void deadlineAndProbeBoundsAreResumableIncludingMaximumRange() {
        BlockPos a = BlockPos.ZERO, b = new BlockPos(4096, 0, 0);
        var trace = trace(a, b, 6);
        AtomicInteger probes = new AtomicInteger();
        SignalPropagator.TerrainSampler sampler = p -> { probes.incrementAndGet(); return AIR; };
        assertFalse(trace.advance(sampler, 100, System.nanoTime() - 1));
        assertFalse(trace.advance(sampler, 0, Long.MAX_VALUE));
        assertEquals(0, probes.get());
        assertFalse(trace.advance(sampler, 7, Long.MAX_VALUE));
        assertEquals(7, probes.get());
        while (!trace.advance(sampler, 97, Long.MAX_VALUE)) { }
        assertTrue(probes.get() <= MAX_PROBES);
        assertTrue(trace.work() <= MAX_PROBES);
        assertTrue(trace.result().capacityMbps() > 0);
        assertTrue(trace.corridor().intersectsChunk(128, -1));
        assertFalse(trace.corridor().intersectsChunk(128, 10));
    }

    @Test
    void frequencyAndDistanceAffectBudgetIndependentlyOfCellularModel() {
        BlockPos a = BlockPos.ZERO;
        var near = trace(a, new BlockPos(100, 0, 0), 38);
        var far = trace(a, new BlockPos(4096, 0, 0), 38);
        near.advance(p -> AIR, MAX_PROBES, Long.MAX_VALUE);
        far.advance(p -> AIR, MAX_PROBES, Long.MAX_VALUE);
        assertEquals(2000, near.result().capacityMbps());
        assertEquals("degraded", far.result().state());
        assertTrue(far.result().capacityMbps() < near.result().capacityMbps());
    }

    @Test
    void centerDdaDoesNotSkipShortVoxelIntersectionsAndDiagonalCorridorIsNotWholeAabb() {
        var trace = trace(BlockPos.ZERO, new BlockPos(100, 99, 0), 11);
        trace.advance(p -> p.equals(new BlockPos(1, 0, 0)) ? SOLID : AIR, MAX_PROBES, Long.MAX_VALUE);
        assertEquals("blocked", trace.result().state());
        var diagonal = trace(BlockPos.ZERO, new BlockPos(1000, 0, 1000), 6);
        assertTrue(diagonal.corridor().intersectsChunk(31, 31));
        assertFalse(diagonal.corridor().intersectsChunk(0, 60));
    }

    @Test
    void verticalFresnelAndBothEndpointAlignmentUseThreeDimensions() {
        BlockPos a = BlockPos.ZERO, b = new BlockPos(0, 1000, 0);
        var vertical = trace(a, b, 6);
        vertical.advance(p -> p.getY() > 490 && p.getY() < 510 && p.getX() > 0 ? WATER : AIR, MAX_PROBES, Long.MAX_VALUE);
        assertEquals("fresnel_blocked", vertical.result().state());
        assertState("misaligned", a, pointing(a, b, 6), b, new MicrowaveConfig(a, 1, 6, 0, 90, true));
        BlockPos south = new BlockPos(0, 0, 100);
        var boundary = new MicrowaveLinkEvaluator.Trace(a, new MicrowaveConfig(south, 1, 11, 15, 0, true),
                south, pointing(south, a, 11));
        boundary.advance(p -> AIR, MAX_PROBES, Long.MAX_VALUE);
        assertEquals("ready", boundary.result().state());
    }

    @Test
    void entireFresnelInteriorIsCoveredForAxialDiagonalAndVerticalLinks() {
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos[] targets = {new BlockPos(3000, 64, 0), new BlockPos(2100, 64, 2100), new BlockPos(0, 3064, 0)};
        BlockPos[] interiors = {new BlockPos(1500, 65, 0), new BlockPos(1050, 65, 1050), new BlockPos(1, 1564, 0)};
        for (int i = 0; i < targets.length; i++) {
            BlockPos interior = interiors[i];
            for (var material : new SignalPropagator.Material[]{SOLID, UNKNOWN}) {
                var trace = trace(a, targets[i], 6);
                assertTrue(trace.advance(p -> p.equals(interior) ? material : AIR, MAX_PROBES, Long.MAX_VALUE));
                assertEquals(material == SOLID ? "fresnel_blocked" : "unknown", trace.result().state());
                assertEquals(interior, trace.result().blocker());
                assertEquals(0, trace.result().capacityMbps());
            }
        }
    }

    @Test
    void unknownResumesTheExactInteriorVoxelWithoutReplayingEarlierClearance() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(3000, 64, 0);
        BlockPos unavailable = new BlockPos(1500, 65, 0);
        var trace = trace(a, b, 6);
        trace.advance(p -> p.equals(unavailable) ? UNKNOWN : AIR, MAX_PROBES, Long.MAX_VALUE);
        assertTrue(trace.waitingForTerrain());
        int work = trace.work();
        trace.resumeUnknown();
        assertFalse(trace.advance(p -> { fail("Expired deadline sampled terrain"); return AIR; }, MAX_PROBES, System.nanoTime() - 1));
        assertEquals(work, trace.work());
        AtomicInteger samples = new AtomicInteger();
        trace.advance(p -> {
            if (samples.getAndIncrement() == 0) assertEquals(unavailable, p);
            assertTrue(p.getX() >= unavailable.getX(), "Previously clear prefix was replayed");
            return AIR;
        }, MAX_PROBES, Long.MAX_VALUE);
        assertFalse(trace.waitingForTerrain());
        assertTrue(trace.result().capacityMbps() > 0);
    }

    @Test
    void fullVolumeHasBoundedWorkEvenForLongThreeDimensionalDiagonals() {
        for (BlockPos target : new BlockPos[]{new BlockPos(4096, 0, 0), new BlockPos(2364, 2364, 2364),
                new BlockPos(-2364, 2364, -2364), new BlockPos(0, -4096, 0)}) {
            var trace = trace(BlockPos.ZERO, target, 6);
            while (!trace.done()) {
                int previous = trace.work();
                trace.advance(p -> AIR, 113, Long.MAX_VALUE);
                assertTrue(trace.work() - previous <= 113);
            }
            assertTrue(trace.result().capacityMbps() > 0, trace.result().state());
            assertTrue(trace.work() <= 4097 * 15 * 15 + 7095);
            assertTrue(trace.probes() <= trace.work());
        }
    }

    @Test
    void solidEndpointSupportsDoNotObstructHorizontalLinksAcrossBandsRangesAndBearings() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos sourceSupport = source.below();
        for (int frequency : new int[]{6, 11, 18, 38}) {
            for (BlockPos target : new BlockPos[]{source.offset(1, 0, 0), source.offset(100, 0, 0),
                    source.offset(3000, 0, 0), source.offset(4096, 0, 0), source.offset(0, 0, 100),
                    source.offset(-100, 0, 0), source.offset(2100, 0, 2100)}) {
                var supportSamples = new AtomicInteger();
                var trace = trace(source, target, frequency);
                BlockPos targetSupport = target.below();
                assertTrue(trace.advance(pos -> {
                    if (pos.equals(sourceSupport) || pos.equals(targetSupport)) {
                        supportSamples.incrementAndGet();
                        return SOLID;
                    }
                    return pos.equals(source) || pos.equals(target) ? SOLID : AIR;
                }, MAX_PROBES, Long.MAX_VALUE));
                assertTrue(trace.result().capacityMbps() > 0, frequency + " GHz to " + target + ": " + trace.result());
                assertNull(trace.result().blocker());
                assertEquals(0, supportSamples.get(), "Supports must be outside the clearance, not hardware-exempt");
            }
        }
    }

    @Test
    void supportBlocksRemainObstructionsWhenTheyActuallyIntersectTheBeam() {
        BlockPos source = new BlockPos(0, 64, 0), target = new BlockPos(1000, 64, 0);
        BlockPos intrusion = new BlockPos(100, 63, 0);
        for (var material : new SignalPropagator.Material[]{SOLID, UNKNOWN}) {
            var trace = trace(source, target, 6);
            trace.advance(pos -> {
                if (pos.equals(intrusion)) return material;
                return pos.equals(source.below()) || pos.equals(target.below()) ? SOLID : AIR;
            }, MAX_PROBES, Long.MAX_VALUE);
            assertEquals(material == SOLID ? "fresnel_blocked" : "unknown", trace.result().state());
            assertEquals(intrusion, trace.result().blocker());
        }
        var downward = trace(source, new BlockPos(0, 0, 0), 6);
        downward.advance(pos -> pos.equals(source.below()) ? SOLID : AIR, MAX_PROBES, Long.MAX_VALUE);
        assertEquals("blocked", downward.result().state());
        assertEquals(source.below(), downward.result().blocker(), "Mounting blocks have no blanket exemption");
    }
}
