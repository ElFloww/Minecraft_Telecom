package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;

import static com.florentdubut.telecom.network.SignalPropagator.*;
import static com.florentdubut.telecom.network.SignalPropagator.Material.*;
import static com.florentdubut.telecom.network.TelecomFrequency.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class SignalPropagatorTest {
    private static final BlockPos SOURCE = new BlockPos(0, 64, 0);
    private static final TelecomFrequency FREQUENCY = G2_900;

    @Test
    void defaultConfigurationPreservesEveryBandAndSharedTraceResults() {
        for (Material material : List.of(AIR, SOLID, WATER, TRANSPARENT, UNKNOWN)) {
            BlockPos target = SOURCE.offset(12, -3, 4);
            TerrainSampler sampler = pos -> material;
            MultiTrace legacy = new MultiTrace(SOURCE, target, List.of(TelecomFrequency.values()));
            MultiTrace configured = new MultiTrace(SOURCE, target, List.of(TelecomFrequency.values()), AntennaRadioConfig.DEFAULT);
            while (!legacy.advance(sampler, 2, Long.MAX_VALUE)) { }
            while (!configured.advance(sampler, 2, Long.MAX_VALUE)) { }
            for (TelecomFrequency frequency : TelecomFrequency.values()) {
                SignalResult old = calculateSignal(sampler, SOURCE, target, frequency);
                SignalResult result = calculateSignal(sampler, SOURCE, target, frequency, AntennaRadioConfig.DEFAULT);
                assertEquals(old.powerDbm, result.powerDbm);
                assertEquals(old.known, result.known);
                assertEquals(old.powerDbm, configured.results().get(frequency.ordinal()).powerDbm);
                assertEquals(old.known, configured.results().get(frequency.ordinal()).known);
                assertEquals(legacy.results().get(frequency.ordinal()).powerDbm,
                        configured.results().get(frequency.ordinal()).powerDbm);
            }
            assertEquals(legacy.visitedChunks(), configured.visitedChunks());
        }
        Level level = client(Blocks.AIR.defaultBlockState());
        assertEquals(calculateSignal(level, SOURCE, SOURCE.south(10), G4_700).powerDbm,
                calculateSignal(level, SOURCE, SOURCE.south(10), G4_700, AntennaRadioConfig.DEFAULT).powerDbm);
    }

    @Test
    void configurationAppliesBeforeFloorAndEarlyStopsWithoutChangingUnknownOrRangeRules() {
        var boosted = new AntennaRadioConfig(0, 0, 0, 50, 100);
        BlockPos distant = SOURCE.south(4000);
        TerrainSampler unknownInterior = pos -> pos.equals(SOURCE) || pos.equals(distant) ? AIR : UNKNOWN;
        assertTrue(calculateSignal(unknownInterior, SOURCE, distant, G5_26000).known);
        assertFalse(calculateSignal(unknownInterior, SOURCE, distant, G5_26000, boosted).known,
                "Boost must be applied before a weak free-space band is stopped");

        BlockPos target = SOURCE.south(1000);
        TerrainSampler missing = pos -> pos.equals(SOURCE) || pos.equals(target) ? AIR : UNKNOWN;
        var backwards = new AntennaRadioConfig(1, 180, 0, 30, 100);
        assertFalse(calculateSignal(missing, SOURCE, target, G2_900).known);
        assertTrue(calculateSignal(missing, SOURCE, target, G2_900, backwards).known);
        assertEquals(MIN_SIGNAL, calculateSignal(missing, SOURCE, target, G2_900, backwards).powerDbm);
        assertFalse(calculateSignal(pos -> UNKNOWN, SOURCE, target, G2_900, backwards).known);
        assertEquals(MIN_SIGNAL, calculateSignal(pos -> SOLID, SOURCE, SOURCE.south(100), G2_900, boosted).powerDbm,
                "Boost cannot resurrect a floored result after tracing");
        SignalResult outside = calculateSignal(pos -> fail("Out-of-range terrain read"), SOURCE,
                SOURCE.south(MAX_RANGE + 1), G2_900, boosted);
        assertTrue(outside.known);
        assertEquals(MIN_SIGNAL, outside.powerDbm);
        float baseline = calculateSignal(pos -> AIR, SOURCE, SOURCE.south(10), G4_700).powerDbm;
        assertEquals(baseline + 20, calculateSignal(pos -> AIR, SOURCE, SOURCE.south(10), G4_700, boosted).powerDbm, 1e-5);
    }

    @Test
    void configuredMultiTraceStillSamplesTerrainOnceForAllBands() {
        var config = new AntennaRadioConfig(3, 25, 10, 45, 50);
        BlockPos target = SOURCE.south(12);
        AtomicInteger probes = new AtomicInteger();
        MultiTrace trace = new MultiTrace(SOURCE, target, List.of(TelecomFrequency.values()), config);
        while (!trace.advance(pos -> { probes.incrementAndGet(); return AIR; }, 1, Long.MAX_VALUE)) { }
        assertEquals(13, probes.get());
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            assertEquals(calculateSignal(pos -> AIR, SOURCE, target, frequency, config).powerDbm,
                    trace.results().get(frequency.ordinal()).powerDbm);
        }
    }

    @Test
    void classifiesAirStoneWaterGlassLeavesAndPermeableBlocks() {
        float air = signal(client(Blocks.AIR.defaultBlockState()), SOURCE.east(4));
        float stone = signal(client(Blocks.STONE.defaultBlockState()), SOURCE.east(4));
        float water = signal(client(Blocks.WATER.defaultBlockState()), SOURCE.east(4));
        float glass = signal(client(Blocks.GLASS.defaultBlockState()), SOURCE.east(4));
        float leaves = signal(client(Blocks.OAK_LEAVES.defaultBlockState()), SOURCE.east(4));
        float permeable = signal(client(Blocks.SHORT_GRASS.defaultBlockState()), SOURCE.east(4));

        assertTrue(air > glass);
        assertTrue(glass > water);
        assertTrue(water > stone);
        assertEquals(glass, leaves, 0.0001f);
        assertEquals(air, permeable, 0.0001f);
        assertEquals(calculateSignal(pos -> WATER, SOURCE, SOURCE.east(4), FREQUENCY).powerDbm,
                water, 0.0001f);
    }

    @Test
    void endpointsRequirePresenceButHaveNoMaterialPenalty() {
        BlockPos target = SOURCE.east(4);
        SignalResult air = calculateSignal(pos -> AIR, SOURCE, target, FREQUENCY);
        SignalResult solidEndpoints = calculateSignal(pos -> pos.equals(SOURCE) || pos.equals(target)
                ? SOLID : AIR, SOURCE, target, FREQUENCY);
        assertEquals(air.powerDbm, solidEndpoints.powerDbm);
        assertSame(FREQUENCY, solidEndpoints.frequency);
        assertTrue(solidEndpoints.known);

        Level level = client(Blocks.STONE.defaultBlockState());
        calculateSignal(level, SOURCE, target, FREQUENCY);
        verify(level, never()).getBlockState(SOURCE);
        verify(level, never()).getBlockState(target);
    }

    @Test
    void integratesWallThicknessAndResetsItAcrossAir() {
        BlockPos target = SOURCE.east(6);
        float air = calculateSignal(pos -> AIR, SOURCE, target, FREQUENCY).powerDbm;
        float thin = calculateSignal(pos -> pos.getX() == 2 ? SOLID : AIR,
                SOURCE, target, FREQUENCY).powerDbm;
        float thick = calculateSignal(pos -> pos.getX() >= 2 && pos.getX() <= 3 ? SOLID : AIR,
                SOURCE, target, FREQUENCY).powerDbm;
        float separated = calculateSignal(pos -> pos.getX() == 2 || pos.getX() == 4 ? SOLID : AIR,
                SOURCE, target, FREQUENCY).powerDbm;
        assertEquals(1.5 - FREQUENCY.getBaseAttenuation(), air - thin, 0.0001);
        assertEquals(4 - 2 * FREQUENCY.getBaseAttenuation(), air - thick, 0.0001);
        assertTrue(thick < separated);
        assertTrue(separated < thin);
    }

    @Test
    void higherFrequenciesLoseMoreInFreeSpaceAndMaterials() {
        BlockPos target = SOURCE.east(4);
        float lowAir = calculateSignal(pos -> AIR, SOURCE, target, G2_900).powerDbm;
        float highAir = calculateSignal(pos -> AIR, SOURCE, target, G2_1800).powerDbm;
        float lowStone = calculateSignal(pos -> SOLID, SOURCE, target, G2_900).powerDbm;
        float highStone = calculateSignal(pos -> SOLID, SOURCE, target, G2_1800).powerDbm;
        assertTrue(lowAir > highAir);
        assertTrue(lowStone > highStone);
        assertEquals(2 * (lowAir - lowStone), highAir - highStone, 0.0001);
    }

    @Test
    void distanceLossUsesEuclideanDistanceAndInteriorLength() {
        for (BlockPos delta : List.of(new BlockPos(1, 0, 0), new BlockPos(12, 0, 0),
                new BlockPos(4, 2, 1), new BlockPos(-7, 3, -2), new BlockPos(0, -5, 0))) {
            BlockPos target = SOURCE.offset(delta);
            double distance = Math.sqrt(SOURCE.distSqr(target));
            int longestAxis = Math.max(Math.abs(delta.getX()),
                    Math.max(Math.abs(delta.getY()), Math.abs(delta.getZ())));
            double expected = freeSpace(distance, FREQUENCY)
                    - distance * (1 - 1.0 / longestAxis) * FREQUENCY.getBaseAttenuation();
            assertEquals(expected, calculateSignal(pos -> AIR, SOURCE, target, FREQUENCY).powerDbm,
                    0.0001, delta.toString());
        }
        assertTrue(calculateSignal(pos -> AIR, SOURCE, SOURCE.east(3), FREQUENCY).powerDbm
                > calculateSignal(pos -> AIR, SOURCE, SOURCE.east(30), FREQUENCY).powerDbm);
    }

    @Test
    void maximumRangeIsInclusiveAndBeyondItNeverReadsTerrain() {
        AtomicInteger probes = new AtomicInteger();
        TerrainSampler sampler = pos -> {
            probes.incrementAndGet();
            return AIR;
        };
        SignalResult boundary = calculateSignal(sampler, SOURCE, SOURCE.east(MAX_RANGE), FREQUENCY);
        assertTrue(boundary.known);
        assertTrue(probes.get() > 0);

        for (BlockPos target : List.of(SOURCE.east(MAX_RANGE + 1), SOURCE.offset(MAX_RANGE, 1, 0),
                new BlockPos(Integer.MIN_VALUE, 64, Integer.MAX_VALUE))) {
            Trace trace = new Trace(SOURCE, target, FREQUENCY);
            assertAbsent(trace.result(), true);
            assertTrue(trace.advance((TerrainSampler) pos -> fail("Out of range terrain read"), 0, 0));
            assertTrue(trace.visitedChunks().isEmpty());
        }
        Level level = mock(Level.class);
        assertAbsent(calculateSignal(level, SOURCE, SOURCE.east(MAX_RANGE + 1), FREQUENCY), true);
        verifyNoInteractions(level);
    }

    @Test
    void coincidentEndpointsAreSampledExactlyOnceEvenWhenUnavailable() {
        for (Material material : List.of(AIR, SOLID, UNKNOWN)) {
            List<BlockPos> samples = new ArrayList<>();
            Trace trace = new Trace(SOURCE, SOURCE, FREQUENCY);
            assertTrue(trace.advance(pos -> {
                samples.add(pos);
                return material;
            }, 1, Long.MAX_VALUE));
            assertEquals(List.of(SOURCE), samples);
            assertEquals(Set.of(chunk(SOURCE)), trace.visitedChunks());
            if (material == UNKNOWN) assertAbsent(trace.result(), false);
            else assertEquals(freeSpace(0, FREQUENCY), trace.result().powerDbm, 0.0001);
        }
    }

    @Test
    void missingSourceTargetOrInteriorNeverBecomesAir() {
        BlockPos target = SOURCE.east(48);
        for (BlockPos missing : List.of(SOURCE, target, SOURCE.east(20))) {
            Trace trace = new Trace(SOURCE, target, FREQUENCY);
            List<BlockPos> samples = new ArrayList<>();
            assertTrue(trace.advance(pos -> {
                samples.add(pos);
                return pos.equals(missing) ? UNKNOWN : AIR;
            }, 100, Long.MAX_VALUE));
            assertAbsent(trace.result(), false);
            assertTrue(trace.visitedChunks().containsAll(List.of(chunk(SOURCE), chunk(target), chunk(missing))));
            if (!missing.equals(SOURCE) && !missing.equals(target)) {
                assertEquals(missing, samples.getLast());
            } else {
                assertEquals(List.of(SOURCE, target), samples);
            }
        }
    }

    @Test
    void clientChecksPresenceBeforeReadingAnyUnavailableTerrain() {
        Level level = client(Blocks.AIR.defaultBlockState());
        BlockPos missing = SOURCE.east(16);
        when(level.hasChunkAt(missing)).thenReturn(false);
        Trace trace = new Trace(SOURCE, SOURCE.east(48), FREQUENCY);
        assertTrue(trace.advance(level, 100, Long.MAX_VALUE));
        assertAbsent(trace.result(), false);
        assertTrue(trace.visitedChunks().contains(chunk(missing)));
        verify(level, never()).getBlockState(missing);
        verify(level, never()).getBlockState(SOURCE.east(17));
    }

    @Test
    void serverUsesOnlyGetChunkNowAndReadsTheReturnedChunk() {
        ServerLevel level = mock(ServerLevel.class);
        overworldBounds(level);
        ServerChunkCache cache = mock(ServerChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        when(level.getChunkSource()).thenReturn(cache);
        when(cache.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        assertTrue(calculateSignal(level, SOURCE, SOURCE.east(4), FREQUENCY).known);
        verify(chunk, times(3)).getBlockState(any(BlockPos.class));
        verify(cache, times(5)).getChunkNow(0, 0);
        verifyNoMoreInteractions(cache);
        verify(level, never()).hasChunkAt(any(BlockPos.class));
        verify(level, never()).getBlockState(any(BlockPos.class));

        when(cache.getChunkNow(1, 0)).thenReturn(null);
        Trace missing = new Trace(SOURCE, SOURCE.east(48), FREQUENCY);
        assertTrue(missing.advance(level, 100, Long.MAX_VALUE));
        assertAbsent(missing.result(), false);
        assertEquals(Set.of(ChunkPos.asLong(0, 0), ChunkPos.asLong(1, 0), ChunkPos.asLong(3, 0)),
                missing.visitedChunks());
    }

    @Test
    void stateOnlyClassificationNeverInvokesShapeThatReadsAnUnloadedNeighbor() {
        BlockPos source = SOURCE.east(15);
        BlockPos middle = source.south();
        BlockPos target = source.south(2);
        BlockPos unloadedNeighbor = middle.east();
        for (boolean blocksMotion : List.of(true, false)) {
            ServerLevel level = mock(ServerLevel.class);
            overworldBounds(level);
            ServerChunkCache cache = mock(ServerChunkCache.class);
            LevelChunk loaded = mock(LevelChunk.class);
            BlockState state = mock(BlockState.class);
            when(level.getChunkSource()).thenReturn(cache);
            when(cache.getChunkNow(0, 0)).thenReturn(loaded);
            when(cache.getChunkNow(1, 0)).thenReturn(null);
            when(loaded.getBlockState(middle)).thenReturn(state);
            when(state.getFluidState()).thenReturn(Blocks.AIR.defaultBlockState().getFluidState());
            when(state.blocksMotion()).thenReturn(blocksMotion);
            when(state.canOcclude()).thenReturn(true);
            when(state.getCollisionShape(any(BlockGetter.class), any(BlockPos.class))).thenAnswer(call -> {
                BlockGetter world = call.getArgument(0);
                world.getBlockState(unloadedNeighbor);
                return Shapes.block();
            });

            SignalResult sync = calculateSignal(level, source, target, FREQUENCY);
            Trace trace = new Trace(source, target, FREQUENCY);
            assertFalse(trace.advance(level, 1, Long.MAX_VALUE));
            assertFalse(trace.advance(level, 1, Long.MAX_VALUE));
            assertTrue(trace.advance(level, 1, Long.MAX_VALUE));
            assertTrue(sync.known);
            assertTrue(trace.result().known);
            assertEquals(calculateSignal(pos -> blocksMotion ? SOLID : AIR,
                    source, target, FREQUENCY).powerDbm, sync.powerDbm);
            assertEquals(sync.powerDbm, trace.result().powerDbm);
            assertEquals(Set.of(chunk(source)), trace.visitedChunks());
            verify(state, never()).getCollisionShape(any(BlockGetter.class), any(BlockPos.class));
            verify(level, times(6)).getMinY();
            verify(level, times(6)).getMaxY();
            verify(level, times(6)).getChunkSource();
            verify(cache, times(6)).getChunkNow(0, 0);
            verify(loaded, times(2)).getBlockState(middle);
            // Also rejects every getChunk overload and any Level.getBlockState/neighbor access.
            verifyNoMoreInteractions(level, cache, loaded);
        }
    }

    @Test
    void unavailableServerEndpointsCannotBeHiddenByFreeSpaceEarlyStop() {
        for (BlockPos missing : List.of(SOURCE, SOURCE.east(MAX_RANGE))) {
            ServerLevel level = mock(ServerLevel.class);
            overworldBounds(level);
            ServerChunkCache cache = mock(ServerChunkCache.class);
            when(level.getChunkSource()).thenReturn(cache);
            when(cache.getChunkNow(anyInt(), anyInt())).thenReturn(mock(LevelChunk.class));
            when(cache.getChunkNow(missing.getX() >> 4, missing.getZ() >> 4)).thenReturn(null);
            Trace trace = new Trace(SOURCE, SOURCE.east(MAX_RANGE), G5_26000);
            assertTrue(trace.advance(level, 2, Long.MAX_VALUE));
            assertAbsent(trace.result(), false);
            assertEquals(Set.of(chunk(SOURCE), chunk(SOURCE.east(MAX_RANGE))), trace.visitedChunks());
        }
    }

    @Test
    void ddaVisitsShortDiagonalIntersectionsExactlyOnce() {
        List<BlockPos> samples = new ArrayList<>();
        BlockPos target = SOURCE.offset(4, 2, 0);
        calculateSignal(pos -> {
            samples.add(pos);
            return AIR;
        }, SOURCE, target, FREQUENCY);
        assertEquals(List.of(SOURCE, target, SOURCE.offset(1, 0, 0), SOURCE.offset(1, 1, 0),
                SOURCE.offset(2, 1, 0), SOURCE.offset(3, 1, 0), SOURCE.offset(3, 2, 0)), samples);

        double crossedLength = Math.sqrt(20) / 8;
        float air = calculateSignal(pos -> AIR, SOURCE, target, FREQUENCY).powerDbm;
        float wall = calculateSignal(pos -> pos.equals(SOURCE.offset(1, 0, 0)) ? SOLID : AIR,
                SOURCE, target, FREQUENCY).powerDbm;
        assertEquals(crossedLength * (1 + crossedLength / 2 - FREQUENCY.getBaseAttenuation()),
                air - wall, 0.0001);
    }

    @Test
    void ddaCrossesCornersWithoutSamplingZeroLengthNeighbors() {
        for (int sign : List.of(-1, 1)) {
            List<BlockPos> samples = new ArrayList<>();
            BlockPos target = SOURCE.offset(sign * 4, sign * 4, sign * 4);
            calculateSignal(pos -> {
                samples.add(pos);
                return AIR;
            }, SOURCE, target, FREQUENCY);
            assertEquals(List.of(SOURCE, target, SOURCE.offset(sign, sign, sign),
                    SOURCE.offset(2 * sign, 2 * sign, 2 * sign),
                    SOURCE.offset(3 * sign, 3 * sign, 3 * sign)), samples);
        }
    }

    @Test
    void diagonalTraversalIsSymmetricAndNeverDuplicatesVoxels() {
        for (BlockPos delta : List.of(new BlockPos(9, 3, 6), new BlockPos(-7, 5, -3),
                new BlockPos(0, -13, 4), new BlockPos(-12, 0, 0))) {
            BlockPos target = SOURCE.offset(delta);
            List<BlockPos> forward = new ArrayList<>();
            List<BlockPos> backward = new ArrayList<>();
            float first = calculateSignal(pos -> {
                forward.add(pos);
                return WATER;
            }, SOURCE, target, FREQUENCY).powerDbm;
            float second = calculateSignal(pos -> {
                backward.add(pos);
                return WATER;
            }, target, SOURCE, FREQUENCY).powerDbm;
            assertEquals(first, second, 0.0001);
            assertEquals(forward.size(), new HashSet<>(forward).size());
            assertEquals(backward.size(), new HashSet<>(backward).size());
            assertEquals(new HashSet<>(forward), new HashSet<>(backward));
        }
    }

    @Test
    void progressiveBudgetsIncludeEndpointsAndResultRequiresCompletion() {
        Trace trace = new Trace(SOURCE, SOURCE.east(4), FREQUENCY);
        List<BlockPos> samples = new ArrayList<>();
        TerrainSampler sampler = pos -> {
            samples.add(pos);
            return AIR;
        };
        assertThrows(IllegalStateException.class, trace::result);
        assertFalse(trace.advance(sampler, 0, Long.MAX_VALUE));
        assertFalse(trace.advance(sampler, -1, Long.MAX_VALUE));
        assertTrue(samples.isEmpty());
        for (int i = 1; i <= 5; i++) {
            assertEquals(i == 5, trace.advance(sampler, 1, Long.MAX_VALUE));
            assertEquals(i, samples.size());
            if (i < 5) assertThrows(IllegalStateException.class, trace::result);
        }
        SignalResult result = trace.result();
        assertTrue(trace.advance(sampler, 100, Long.MAX_VALUE));
        assertSame(result, trace.result());
        assertEquals(5, samples.size());
        assertThrows(UnsupportedOperationException.class, () -> trace.visitedChunks().clear());
    }

    @Test
    void expiredDeadlineDoesNoWorkAndCanResume() {
        Trace trace = new Trace(SOURCE, SOURCE.east(20), FREQUENCY);
        assertFalse(trace.advance((TerrainSampler) pos -> fail("Expired trace sampled terrain"),
                100, System.nanoTime() - 1));
        assertTrue(trace.visitedChunks().isEmpty());
        assertThrows(IllegalStateException.class, trace::result);
        assertTrue(trace.advance(pos -> AIR, 100, Long.MAX_VALUE));
    }

    @Test
    void deadlineIsRecheckedBetweenSamples() {
        Trace trace = new Trace(SOURCE, SOURCE.east(20), FREQUENCY);
        AtomicInteger probes = new AtomicInteger();
        long deadline = System.nanoTime() + 10_000_000;
        assertFalse(trace.advance(pos -> {
            probes.incrementAndGet();
            while (System.nanoTime() - deadline < 0) LockSupport.parkNanos(1_000_000);
            return AIR;
        }, 100, deadline));
        assertTrue(probes.get() <= 1);
        assertTrue(trace.advance(pos -> AIR, 100, Long.MAX_VALUE));
    }

    @Test
    void progressiveAndSynchronousResultsAreIdenticalForAllFrequencies() {
        BlockPos target = SOURCE.offset(-37, 9, 21);
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            for (Material middle : List.of(AIR, SOLID, WATER, TRANSPARENT, UNKNOWN)) {
                TerrainSampler sampler = pos -> pos.getX() < -16 && pos.getX() > -20 ? middle : AIR;
                SignalResult sync = calculateSignal(sampler, SOURCE, target, frequency);
                for (int budget : List.of(1, 2, 7, 256)) {
                    Trace trace = new Trace(SOURCE, target, frequency);
                    int calls = 0;
                    while (!trace.advance(sampler, budget, Long.MAX_VALUE)) assertTrue(++calls < 100);
                    assertSame(sync.frequency, trace.result().frequency);
                    assertEquals(sync.known, trace.result().known);
                    assertEquals(sync.powerDbm, trace.result().powerDbm);
                }
            }
        }
        Level level = client(Blocks.WATER.defaultBlockState());
        Trace trace = new Trace(SOURCE, target, FREQUENCY);
        while (!trace.advance(level, 3, Long.MAX_VALUE)) {
        }
        assertEquals(calculateSignal(level, SOURCE, target, FREQUENCY).powerDbm, trace.result().powerDbm);
    }

    @Test
    void earlyStopIsKnownAbsentWithoutRequiringUnknownRemainder() {
        BlockPos target = SOURCE.east(80);
        Trace trace = new Trace(SOURCE, target, FREQUENCY);
        assertTrue(trace.advance(pos -> {
            if (pos.equals(SOURCE) || pos.equals(target)) return AIR;
            if (pos.getX() >= 16) fail("Terrain after sufficient loss must not be required");
            return SOLID;
        }, 100, Long.MAX_VALUE));
        assertAbsent(trace.result(), true);
        assertEquals(Set.of(chunk(SOURCE), chunk(target)), trace.visitedChunks());
    }

    @Test
    void unknownResultsAndKnownAbsenceUseTheSameFloor() {
        assertAbsent(new SignalResult(FREQUENCY, -60, false), false);
        assertAbsent(new SignalResult(FREQUENCY, -160), true);
    }

    @Test
    void outOfWorldSourceOrReceiverIsUnknownWithoutReadingItsLoadedChunk() {
        for (int y : List.of(-65, 320)) {
            BlockPos outside = new BlockPos(16, y, 0);
            BlockPos inside = new BlockPos(0, y == 320 ? 319 : -64, 0);
            for (boolean outsideSource : List.of(true, false)) {
                ServerLevel server = mock(ServerLevel.class);
                overworldBounds(server);
                ServerChunkCache cache = mock(ServerChunkCache.class);
                LevelChunk loaded = mock(LevelChunk.class);
                when(server.getChunkSource()).thenReturn(cache);
                when(cache.getChunkNow(anyInt(), anyInt())).thenReturn(loaded);
                Level client = client(Blocks.AIR.defaultBlockState());
                BlockPos source = outsideSource ? outside : inside;
                BlockPos target = outsideSource ? inside : outside;
                for (Level level : List.of(server, client)) {
                    assertAbsent(calculateSignal(level, source, target, FREQUENCY), false);
                    Trace trace = new Trace(source, target, FREQUENCY);
                    assertTrue(trace.advance(level, 2, Long.MAX_VALUE));
                    assertAbsent(trace.result(), false);
                    assertEquals(Set.of(chunk(inside), chunk(outside)), trace.visitedChunks());
                    verify(level, never()).getBlockState(any(BlockPos.class));
                }
                verify(cache, times(2)).getChunkNow(0, 0);
                verifyNoMoreInteractions(cache);
                verifyNoInteractions(loaded);
                verify(client, times(2)).hasChunkAt(inside);
                verify(client, never()).hasChunkAt(outside);
            }
        }
    }

    @Test
    void worldHeightBoundsAreInclusiveAndPureSamplerHasNoDimensionBounds() {
        ServerLevel server = mock(ServerLevel.class);
        overworldBounds(server);
        ServerChunkCache cache = mock(ServerChunkCache.class);
        LevelChunk loaded = mock(LevelChunk.class);
        when(server.getChunkSource()).thenReturn(cache);
        when(cache.getChunkNow(anyInt(), anyInt())).thenReturn(loaded);
        when(loaded.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        for (int y : List.of(-64, 319)) {
            BlockPos source = new BlockPos(0, y, 0);
            for (Level level : List.of(server, client(Blocks.AIR.defaultBlockState()))) {
                assertTrue(calculateSignal(level, source, source.east(2), FREQUENCY).known);
            }
        }
        for (int y : List.of(-65, 320)) {
            BlockPos source = new BlockPos(0, y, 0);
            assertTrue(calculateSignal(pos -> AIR, source, source.east(2), FREQUENCY).known);
        }
    }

    @Test
    void multiValidatesAndCopiesInputsAndReturnsImmutableOrderedResults() {
        BlockPos target = SOURCE.east(4);
        assertThrows(NullPointerException.class, () -> new MultiTrace(SOURCE, target, null));
        assertThrows(IllegalArgumentException.class, () -> new MultiTrace(SOURCE, target, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiTrace(SOURCE, target, List.of(G2_900, G2_900)));
        assertThrows(IllegalArgumentException.class, () -> new MultiTrace(SOURCE, target,
                Collections.nCopies(TelecomFrequency.values().length + 1, G2_900)));
        List<TelecomFrequency> frequencies = new ArrayList<>(List.of(G5_3500, G2_900));
        frequencies.add(null);
        assertThrows(NullPointerException.class, () -> new MultiTrace(SOURCE, target, frequencies));
        frequencies.removeLast();
        BlockPos.MutableBlockPos mutableSource = SOURCE.mutable();
        BlockPos.MutableBlockPos mutableTarget = target.mutable();
        MultiTrace trace = new MultiTrace(mutableSource, mutableTarget, frequencies);
        mutableSource.set(100, 0, 0);
        mutableTarget.set(200, 0, 0);
        frequencies.clear();
        assertTrue(trace.advance(pos -> AIR, 5, Long.MAX_VALUE));
        assertEquals(List.of(G5_3500, G2_900), trace.results().stream().map(r -> r.frequency).toList());
        assertEquals(calculateSignal(pos -> AIR, SOURCE, target, G5_3500).powerDbm,
                trace.results().getFirst().powerDbm);
        assertThrows(UnsupportedOperationException.class, () -> trace.results().clear());
        assertThrows(UnsupportedOperationException.class, () -> trace.visitedChunks().clear());
        assertEquals(Set.of(chunk(SOURCE)), trace.visitedChunks());
        List<SignalResult> results = trace.results();
        assertTrue(trace.advance((TerrainSampler) pos -> fail("Finished trace sampled terrain"), 100, 0));
        assertSame(results, trace.results());
    }

    @Test
    void multiMatchesEverySingleBandAndUsesExactlyMaximumNotSumOfTerrainProbes() {
        List<TelecomFrequency> frequencies = new ArrayList<>(List.of(TelecomFrequency.values()));
        Collections.reverse(frequencies);
        for (BlockPos delta : List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(80, 0, 0),
                new BlockPos(-80, 0, 0), new BlockPos(0, 80, 0), new BlockPos(0, -80, 0),
                new BlockPos(0, 0, 80), new BlockPos(0, 0, -80), new BlockPos(4, 2, 0),
                new BlockPos(-37, 9, 21), new BlockPos(16, 16, 16),
                new BlockPos(MAX_RANGE, 0, 0), new BlockPos(MAX_RANGE + 1, 0, 0),
                new BlockPos(MAX_RANGE, 1, 0))) {
            BlockPos target = SOURCE.offset(delta);
            List<TerrainSampler> terrains = new ArrayList<>();
            for (Material material : Material.values()) terrains.add(pos -> material);
            terrains.add(pos -> pos.equals(SOURCE) || pos.equals(target) ? AIR : UNKNOWN);
            terrains.add(pos -> pos.equals(target) ? UNKNOWN : AIR);
            terrains.add(pos -> pos.equals(SOURCE) ? UNKNOWN : AIR);
            terrains.add(pos -> switch (Math.floorMod(pos.getX() + pos.getY() + pos.getZ(), 7)) {
                case 0, 1 -> SOLID;
                case 2 -> WATER;
                case 3 -> TRANSPARENT;
                default -> AIR;
            });
            for (TerrainSampler terrain : terrains) {
                List<SignalResult> singles = new ArrayList<>();
                Set<Long> chunks = new HashSet<>();
                int maxProbes = 0;
                int sumProbes = 0;
                for (TelecomFrequency frequency : frequencies) {
                    AtomicInteger probes = new AtomicInteger();
                    Trace single = new Trace(SOURCE, target, frequency);
                    assertTrue(single.advance(pos -> {
                        probes.incrementAndGet();
                        return terrain.sample(pos);
                    }, Integer.MAX_VALUE, Long.MAX_VALUE));
                    singles.add(single.result());
                    chunks.addAll(single.visitedChunks());
                    maxProbes = Math.max(maxProbes, probes.get());
                    sumProbes += probes.get();
                }
                for (int budget : List.of(1, 7, 256)) {
                    MultiTrace multi = new MultiTrace(SOURCE, target, frequencies);
                    List<BlockPos> sampled = new ArrayList<>();
                    TerrainSampler counted = pos -> {
                        sampled.add(pos);
                        return terrain.sample(pos);
                    };
                    int calls = 0;
                    boolean finished;
                    do {
                        int before = sampled.size();
                        finished = multi.advance(counted, budget, Long.MAX_VALUE);
                        assertTrue(sampled.size() - before <= budget);
                        assertTrue(++calls <= maxProbes + 1);
                    } while (!finished);
                    for (int i = 0; i < singles.size(); i++) {
                        SignalResult expected = singles.get(i);
                        SignalResult actual = multi.results().get(i);
                        assertSame(expected.frequency, actual.frequency);
                        assertEquals(expected.known, actual.known);
                        assertEquals(expected.powerDbm, actual.powerDbm);
                    }
                    assertEquals(maxProbes, sampled.size());
                    if (maxProbes > 0) assertTrue(sampled.size() < sumProbes);
                    assertEquals(sampled.size(), new HashSet<>(sampled).size());
                    assertEquals(chunks, multi.visitedChunks());
                }
            }
        }
    }

    @Test
    void multiAlternatingMaterialsKeepExactPerBandLossAndResetSolidThickness() {
        MultiTrace trace = new MultiTrace(SOURCE, SOURCE.east(10), List.of(TelecomFrequency.values()));
        assertTrue(trace.advance(pos -> switch (pos.getX()) {
            case 1, 2, 4, 6, 8 -> SOLID;
            case 3, 9 -> TRANSPARENT;
            case 5 -> WATER;
            default -> AIR;
        }, 11, Long.MAX_VALUE));
        for (SignalResult result : trace.results()) {
            TelecomFrequency frequency = result.frequency;
            // Five solid voxels (one pair), two transparent, one water, one air.
            double expected = freeSpace(10, frequency)
                    - 9.9 * frequency.getFrequencyMhz() / 900.0 - frequency.getBaseAttenuation();
            assertTrue(result.known);
            assertEquals(Math.max(MIN_SIGNAL, expected), result.powerDbm, 0.0001);
        }
    }

    @Test
    void multiUnknownOnlyFinishesActiveBandsRegardlessOfInputOrder() {
        List<TelecomFrequency> frequencies = List.of(G5_26000, G4_700, G5_3500, G2_900, G4_2600);
        MultiTrace trace = new MultiTrace(SOURCE, SOURCE.east(80), frequencies);
        List<BlockPos> samples = new ArrayList<>();
        assertTrue(trace.advance(pos -> {
            samples.add(pos);
            if (pos.equals(SOURCE) || pos.equals(SOURCE.east(80))) return AIR;
            return pos.getX() == 6 ? UNKNOWN : SOLID;
        }, 100, Long.MAX_VALUE));
        assertEquals(SOURCE.east(6), samples.getLast());
        assertEquals(8, samples.size());
        for (int i = 0; i < frequencies.size(); i++) assertAbsent(trace.results().get(i), i % 2 == 0);

        MultiTrace allStopped = new MultiTrace(SOURCE, SOURCE.east(80), frequencies);
        assertTrue(allStopped.advance(pos -> {
            if (pos.equals(SOURCE) || pos.equals(SOURCE.east(80))) return AIR;
            if (pos.getX() >= 16) fail("All bands should stop before unavailable terrain");
            return SOLID;
        }, 100, Long.MAX_VALUE));
        for (SignalResult result : allStopped.results()) assertAbsent(result, true);
    }

    @Test
    void multiChecksEndpointsBeforeFreeSpaceStopButPreservesItOnUnknownInterior() {
        BlockPos target = SOURCE.east(MAX_RANGE);
        for (BlockPos missing : List.of(SOURCE, target, SOURCE.east(1))) {
            MultiTrace trace = new MultiTrace(SOURCE, target, List.of(G5_26000, G4_700));
            assertTrue(trace.advance(pos -> pos.equals(missing) ? UNKNOWN : AIR, 3, Long.MAX_VALUE));
            assertAbsent(trace.results().getFirst(), missing.equals(SOURCE.east(1)));
            assertAbsent(trace.results().getLast(), false);
        }
    }

    @Test
    void multiBudgetsAndDeadlinesArePerProbeAndResultsRequireEveryBandToFinish() {
        MultiTrace trace = new MultiTrace(SOURCE, SOURCE.east(20), List.of(G5_26000, G4_700));
        AtomicInteger probes = new AtomicInteger();
        TerrainSampler terrain = pos -> {
            probes.incrementAndGet();
            return WATER;
        };
        Set<Long> chunks = trace.visitedChunks();
        assertFalse(trace.advance(terrain, 0, Long.MAX_VALUE));
        assertFalse(trace.advance(terrain, -1, Long.MAX_VALUE));
        assertFalse(trace.advance(terrain, 100, System.nanoTime() - 1));
        assertEquals(0, probes.get());
        assertTrue(chunks.isEmpty());
        assertThrows(IllegalStateException.class, trace::results);
        long deadline = System.nanoTime() + 10_000_000;
        assertFalse(trace.advance(pos -> {
            while (System.nanoTime() - deadline < 0) LockSupport.parkNanos(1_000_000);
            return terrain.sample(pos);
        }, 100, deadline));
        assertTrue(probes.get() <= 1);
        while (probes.get() < 5) assertFalse(trace.advance(terrain, 1, Long.MAX_VALUE));
        // The 26 GHz band is already below the floor, but the 700 MHz band is still active.
        assertThrows(IllegalStateException.class, trace::results);
        assertEquals(Set.of(chunk(SOURCE), chunk(SOURCE.east(20))), chunks);
        while (probes.get() < 21) {
            int before = probes.get();
            assertEquals(before == 20, trace.advance(terrain, 1, Long.MAX_VALUE));
            assertEquals(before + 1, probes.get());
        }
        assertAbsent(trace.results().getFirst(), true);
        assertTrue(trace.results().getLast().powerDbm > MIN_SIGNAL);
    }

    @Test
    void multiLiveTerrainReadsAndCachePrefetchAreSharedAcrossBandsAndResumes() {
        ServerLevel server = mock(ServerLevel.class);
        overworldBounds(server);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        LevelChunk loaded = mock(LevelChunk.class);
        when(server.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(loaded);
        when(loaded.getBlockState(any(BlockPos.class))).thenReturn(Blocks.WATER.defaultBlockState());
        BlockPos target = SOURCE.east(4);
        try (var cache = mockStatic(RadioTerrainCache.class)) {
            MultiTrace trace = new MultiTrace(SOURCE, target, List.of(TelecomFrequency.values()));
            assertFalse(trace.advance(server, 0, Long.MAX_VALUE));
            assertFalse(trace.advance(server, 10, System.nanoTime() - 1));
            cache.verifyNoInteractions();
            verifyNoInteractions(server);
            for (int i = 1; i <= 5; i++) assertEquals(i == 5, trace.advance(server, 1, Long.MAX_VALUE));
            assertTrue(trace.advance(server, 100, Long.MAX_VALUE));
            cache.verify(() -> RadioTerrainCache.prefetch(server, SOURCE, target), times(1));
            cache.verifyNoMoreInteractions();
            verify(chunks, times(5)).getChunkNow(0, 0);
            verifyNoMoreInteractions(chunks);
            verify(loaded, times(3)).getBlockState(any(BlockPos.class));
            verify(loaded, never()).getBlockState(SOURCE);
            verify(loaded, never()).getBlockState(target);
            verify(server, never()).getBlockState(any(BlockPos.class));
            verify(server, never()).hasChunkAt(any(BlockPos.class));

            when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(null);
            cache.when(() -> RadioTerrainCache.sample(eq(server), any(BlockPos.class))).thenReturn(WATER);
            MultiTrace cached = new MultiTrace(SOURCE, target, List.of(TelecomFrequency.values()));
            assertTrue(cached.advance(server, 5, Long.MAX_VALUE));
            cache.verify(() -> RadioTerrainCache.sample(eq(server), any(BlockPos.class)), times(5));
            for (int i = 0; i < trace.results().size(); i++) {
                assertTrue(cached.results().get(i).known);
                assertEquals(trace.results().get(i).powerDbm, cached.results().get(i).powerDbm);
            }
        }
        Level client = client(Blocks.WATER.defaultBlockState());
        MultiTrace trace = new MultiTrace(SOURCE, target, List.of(TelecomFrequency.values()));
        assertTrue(trace.advance(client, 5, Long.MAX_VALUE));
        verify(client, times(5)).hasChunkAt(any(BlockPos.class));
        verify(client, times(3)).getBlockState(any(BlockPos.class));
    }

    private static void overworldBounds(Level level) {
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
    }

    private static Level client(BlockState state) {
        Level level = mock(Level.class);
        overworldBounds(level);
        when(level.hasChunkAt(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(state);
        return level;
    }

    private static float signal(Level level, BlockPos target) {
        SignalResult result = calculateSignal(level, SOURCE, target, FREQUENCY);
        assertTrue(result.known);
        return result.powerDbm;
    }

    private static double freeSpace(double distance, TelecomFrequency frequency) {
        return -(20 * Math.log10(Math.max(1, distance))
                + 20 * Math.log10(frequency.getFrequencyMhz()) - 27.55);
    }

    private static long chunk(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static void assertAbsent(SignalResult result, boolean known) {
        assertEquals(known, result.known);
        assertEquals(MIN_SIGNAL, result.powerDbm);
    }
}
