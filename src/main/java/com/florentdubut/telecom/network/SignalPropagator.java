package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class SignalPropagator {

    /** Shared simulation limit in blocks for every caller (phone, map, client and server). */
    public static final int MAX_RANGE = 4096;
    public static final float MIN_SIGNAL = -120f;

    public static class SignalResult {
        public final TelecomFrequency frequency;
        public final float powerDbm; // Signal strength in dBm, e.g. -50 is excellent, -110 is poor
        public final boolean known;

        public SignalResult(TelecomFrequency frequency, float powerDbm) {
            this(frequency, powerDbm, true);
        }

        public SignalResult(TelecomFrequency frequency, float powerDbm, boolean known) {
            this.frequency = frequency;
            this.powerDbm = known ? Math.max(MIN_SIGNAL, powerDbm) : MIN_SIGNAL;
            this.known = known;
        }
    }

    /** UNKNOWN must be returned for unavailable terrain, never AIR. */
    public enum Material {
        UNKNOWN, AIR, TRANSPARENT, WATER, SOLID
    }

    @FunctionalInterface
    public interface TerrainSampler {
        Material sample(BlockPos pos);
    }

    /** State-only classification, shared by live terrain and detached palette copies. */
    public static Material classify(BlockState state) {
        if (state.isAir()) return Material.AIR;
        if (!state.getFluidState().isEmpty()) return Material.WATER;
        if (state.is(BlockTags.LEAVES)) return Material.TRANSPARENT;
        // Never query contextual shapes: they may load neighboring chunks.
        if (!state.blocksMotion()) return Material.AIR;
        return state.canOcclude() ? Material.SOLID : Material.TRANSPARENT;
    }

    public static SignalResult calculateSignal(Level level, BlockPos source, BlockPos target, TelecomFrequency freq) {
        return calculateSignal(level, source, target, freq, AntennaRadioConfig.DEFAULT);
    }

    public static SignalResult calculateSignal(Level level, BlockPos source, BlockPos target,
                                               TelecomFrequency freq, AntennaRadioConfig config) {
        Trace trace = new Trace(source, target, freq, config);
        while (!trace.advance(level, 256, Long.MAX_VALUE)) {
            // Synchronous callers use exactly the same bounded steps as progressive callers.
        }
        return trace.result();
    }

    public static SignalResult calculateSignal(TerrainSampler sampler, BlockPos source, BlockPos target,
                                               TelecomFrequency freq) {
        return calculateSignal(sampler, source, target, freq, AntennaRadioConfig.DEFAULT);
    }

    public static SignalResult calculateSignal(TerrainSampler sampler, BlockPos source, BlockPos target,
                                               TelecomFrequency freq, AntennaRadioConfig config) {
        Trace trace = new Trace(source, target, freq, config);
        while (!trace.advance(sampler, 256, Long.MAX_VALUE)) {
        }
        return trace.result();
    }

    /** A resumable, center-to-center DDA. Live Level access must stay on its owning thread. */
    public static final class Trace {
        private final MultiTrace trace;

        public Trace(BlockPos source, BlockPos target, TelecomFrequency frequency) {
            this(source, target, frequency, AntennaRadioConfig.DEFAULT);
        }

        public Trace(BlockPos source, BlockPos target, TelecomFrequency frequency, AntennaRadioConfig config) {
            trace = new MultiTrace(source, target, List.of(frequency), config);
        }

        public boolean advance(Level level, int maxSamples, long deadlineNanos) {
            return trace.advance(level, maxSamples, deadlineNanos);
        }

        public boolean advance(TerrainSampler sampler, int maxSamples, long deadlineNanos) {
            return trace.advance(sampler, maxSamples, deadlineNanos);
        }

        public SignalResult result() {
            return trace.results().getFirst();
        }

        public Set<Long> visitedChunks() {
            return trace.visitedChunks();
        }
    }

    /** One resumable DDA and terrain probe per voxel, with independent per-band losses and stops. */
    public static final class MultiTrace {
        private final BlockPos source;
        private final BlockPos target;
        private final List<TelecomFrequency> frequencies;
        private final double distance;
        private final double dx;
        private final double dy;
        private final double dz;
        private final int stepX;
        private final int stepY;
        private final int stepZ;
        private final Set<Long> chunks = new LinkedHashSet<>();
        private final Set<Long> chunksView = Collections.unmodifiableSet(chunks);
        private int x;
        private int y;
        private int z;
        private double entered;
        private final double[] power;
        private final double[] solidThickness;
        private final SignalResult[] bandResults;
        private int remaining;
        private int endpoints;
        private boolean endpointsKnown = true;
        private boolean prefetched;
        private List<SignalResult> results;

        public MultiTrace(BlockPos source, BlockPos target, List<TelecomFrequency> frequencies) {
            this(source, target, frequencies, AntennaRadioConfig.DEFAULT);
        }

        public MultiTrace(BlockPos source, BlockPos target, List<TelecomFrequency> frequencies,
                          AntennaRadioConfig config) {
            Objects.requireNonNull(config);
            this.source = source.immutable();
            this.target = target.immutable();
            this.frequencies = List.copyOf(frequencies);
            if (this.frequencies.isEmpty() || this.frequencies.size() > TelecomFrequency.values().length
                    || EnumSet.copyOf(this.frequencies).size() != this.frequencies.size()) {
                throw new IllegalArgumentException("Frequencies must be nonempty and unique");
            }
            remaining = this.frequencies.size();
            power = new double[remaining];
            solidThickness = new double[remaining];
            bandResults = new SignalResult[remaining];
            dx = (double) target.getX() - source.getX();
            dy = (double) target.getY() - source.getY();
            dz = (double) target.getZ() - source.getZ();
            distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            stepX = (int) Math.signum(dx);
            stepY = (int) Math.signum(dy);
            stepZ = (int) Math.signum(dz);
            x = source.getX();
            y = source.getY();
            z = source.getZ();
            double adjustment = config.signalAdjustmentDb(this.source, this.target);
            for (int i = 0; i < power.length; i++) {
                power[i] = distance > MAX_RANGE ? MIN_SIGNAL
                        : adjustment - (20 * Math.log10(Math.max(1, distance))
                        + 20 * Math.log10(this.frequencies.get(i).getFrequencyMhz()) - 27.55);
            }
            if (distance > MAX_RANGE) {
                finish(true);
            } else if (!source.equals(target)) {
                moveToNextVoxel(); // Source is checked for presence separately, without attenuation.
            }
        }

        /**
         * At most maxSamples terrain probes, including endpoints; deadline uses System.nanoTime().
         * Long.MAX_VALUE disables the deadline. Nonpositive budgets do no work.
         */
        public boolean advance(Level level, int maxSamples, long deadlineNanos) {
            if (!prefetched && results == null && maxSamples > 0
                    && (deadlineNanos == Long.MAX_VALUE || System.nanoTime() < deadlineNanos)) {
                prefetched = true;
                if (level instanceof ServerLevel server) RadioTerrainCache.prefetch(server, source, target);
            }
            return advance(pos -> sample(level, pos), maxSamples, deadlineNanos);
        }

        /** Same engine for immutable terrain snapshots or deterministic tests. */
        public boolean advance(TerrainSampler sampler, int maxSamples, long deadlineNanos) {
            for (int samples = 0; results == null && samples < maxSamples; samples++) {
                if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0) {
                    break;
                }
                if (endpoints < 2) {
                    BlockPos pos = endpoints == 0 ? source : target;
                    endpointsKnown &= requiredSample(sampler, pos) != Material.UNKNOWN;
                    endpoints++;
                    if (source.equals(target)) endpoints = 2;
                    if (endpoints == 2) {
                        if (!endpointsKnown) finish(false);
                        else {
                            for (int i = 0; i < power.length; i++) {
                                if (atTarget() || power[i] <= MIN_SIGNAL) finish(i, true);
                            }
                        }
                    }
                    continue;
                }

                BlockPos pos = new BlockPos(x, y, z);
                Material material = requiredSample(sampler, pos);
                if (material == Material.UNKNOWN) {
                    finish(false);
                    break;
                }
                double length = (nextBoundary() - entered) * distance;
                for (int i = 0; i < power.length; i++) {
                    if (bandResults[i] != null) continue;
                    TelecomFrequency frequency = frequencies.get(i);
                    double multiplier = frequency.getFrequencyMhz() / 900.0;
                    // Integrate a simple thickness-dependent solid loss: (1 + thickness) dB/m at 900 MHz.
                    if (material == Material.SOLID) {
                        power[i] -= multiplier * length * (1 + solidThickness[i] + length / 2);
                        solidThickness[i] += length;
                    } else {
                        solidThickness[i] = 0;
                        double loss = switch (material) {
                            case WATER -> multiplier;
                            case TRANSPARENT -> 0.2 * multiplier;
                            default -> frequency.getBaseAttenuation();
                        };
                        power[i] -= loss * length;
                    }
                    // Later unavailable terrain cannot invalidate a band already below the floor.
                    if (power[i] <= MIN_SIGNAL) finish(i, true);
                }
                if (results == null) {
                    moveToNextVoxel();
                    if (atTarget()) finish(true);
                }
            }
            return results != null;
        }

        /** Immutable results in input order, available only when every band has finished. */
        public List<SignalResult> results() {
            if (results == null) throw new IllegalStateException("Trace is not finished");
            return results;
        }

        /** Read-only live view of required chunks, including endpoints and unavailable chunks. */
        public Set<Long> visitedChunks() {
            return chunksView;
        }

        private Material requiredSample(TerrainSampler sampler, BlockPos pos) {
            chunks.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            return Objects.requireNonNull(sampler.sample(pos));
        }

        private Material sample(Level level, BlockPos pos) {
            if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) return Material.UNKNOWN;
            if (pos.equals(source) || pos.equals(target)) return Material.AIR;
            BlockState state;
            if (level instanceof ServerLevel server) {
                LevelChunk chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) return RadioTerrainCache.sample(server, pos);
                state = chunk.getBlockState(pos);
            } else {
                if (!level.hasChunkAt(pos)) return Material.UNKNOWN;
                state = level.getBlockState(pos);
            }

            return classify(state);
        }

        private boolean atTarget() {
            return x == target.getX() && y == target.getY() && z == target.getZ();
        }

        private double boundary(int coordinate, int origin, double delta) {
            return delta == 0 ? Double.POSITIVE_INFINITY
                    : (Math.abs((long) coordinate - origin) + 0.5) / Math.abs(delta);
        }

        private double nextBoundary() {
            return Math.min(boundary(x, source.getX(), dx),
                    Math.min(boundary(y, source.getY(), dy), boundary(z, source.getZ(), dz)));
        }

        private void moveToNextVoxel() {
            double tx = boundary(x, source.getX(), dx);
            double ty = boundary(y, source.getY(), dy);
            double tz = boundary(z, source.getZ(), dz);
            entered = Math.min(tx, Math.min(ty, tz));
            // Recompute rational boundaries instead of accumulating rounding error. Ties cross
            // all matching axes together; voxels touched only at an edge/corner have zero length.
            if (tx == entered) x += stepX;
            if (ty == entered) y += stepY;
            if (tz == entered) z += stepZ;
        }

        private void finish(boolean known) {
            for (int i = 0; i < power.length; i++) finish(i, known);
        }

        private void finish(int index, boolean known) {
            if (bandResults[index] != null) return;
            bandResults[index] = new SignalResult(frequencies.get(index), (float) power[index], known);
            if (--remaining == 0) results = List.of(bandResults);
        }
    }
}
