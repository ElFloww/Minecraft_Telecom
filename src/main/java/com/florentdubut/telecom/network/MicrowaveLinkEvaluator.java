package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** Pure, resumable FH model. One block is one metre; no world access or cellular attenuation. */
public final class MicrowaveLinkEvaluator {
    public static final int MAX_RANGE = 4096;
    // <=4097 dominant-axis slabs * 15 * 15 candidates + <=7095 center voxels.
    // Both skipped geometry candidates and actual terrain probes consume this work ceiling.
    public static final int MAX_PROBES = 1_048_576;
    public static final double FULL_ALIGNMENT_DEGREES = 15;
    public static final double CUTOFF_DEGREES = 30;
    private MicrowaveLinkEvaluator() { }

    public record Result(String state, int capacityMbps, int nominalCapacityMbps, int latencyMs, BlockPos blocker) {
        public Result { if (blocker != null) blocker = blocker.immutable(); }
    }

    /** Conservative swept-cylinder bounds, including off-axis voxel rounding, available before any probes. */
    public record Corridor(BlockPos source, BlockPos target, double padding) {
        public Corridor { source = source.immutable(); target = target.immutable(); }

        public boolean intersectsChunk(int x, int z) {
            double low = 0, high = 1;
            for (int axis = 0; axis < 2; axis++) {
                double start = (axis == 0 ? source.getX() : source.getZ()) + .5;
                double delta = axis == 0 ? (double) target.getX() - source.getX() : (double) target.getZ() - source.getZ();
                double min = (axis == 0 ? x : z) * 16.0 - padding;
                double max = min + 16 + 2 * padding;
                if (delta == 0) {
                    if (start < min || start > max) return false;
                } else {
                    double a = (min - start) / delta;
                    double b = (max - start) / delta;
                    low = Math.max(low, Math.min(a, b));
                    high = Math.min(high, Math.max(a, b));
                    if (low > high) return false;
                }
            }
            return true;
        }

        public double minY() { return Math.min(source.getY(), target.getY()) - padding; }
        public double maxY() { return Math.max(source.getY(), target.getY()) + 1 + padding; }
    }

    public static Result evaluate(SignalPropagator.TerrainSampler sampler, BlockPos source, MicrowaveConfig config,
                                  BlockPos target, MicrowaveConfig peer) {
        Trace trace = new Trace(source, config, target, peer);
        trace.advance(sampler, MAX_PROBES, Long.MAX_VALUE);
        return trace.result();
    }

    public static double alignmentError(BlockPos source, BlockPos target, MicrowaveConfig config) {
        double dx = (double) target.getX() - source.getX(), dy = (double) target.getY() - source.getY();
        double dz = (double) target.getZ() - source.getZ(), length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) return 180;
        double azimuth = Math.toRadians(config.azimuthDegrees()), elevation = Math.toRadians(config.elevationDegrees());
        double dot = (-Math.sin(azimuth) * Math.cos(elevation) * dx + Math.sin(elevation) * dy
                + Math.cos(azimuth) * Math.cos(elevation) * dz) / length;
        return Math.toDegrees(Math.acos(Math.clamp(dot, -1, 1)));
    }

    public static final class Trace {
        private final BlockPos source, target;
        private final double dx, dy, dz, distance, wavelength;
        private final double nx, ny, nz, projectionHalfWidth, extentU, extentV;
        private final int dominant, axisU, axisV, lastSlice, nominal, capacity, latency;
        private final Corridor corridor;
        private int x, y, z, slice, u, v, minU, maxU, minV, maxV, probes, work;
        private boolean centerDone;
        private boolean volumeStarted;
        private Result result;

        public Trace(BlockPos source, MicrowaveConfig config, BlockPos target, MicrowaveConfig peer) {
            this.source = source.immutable();
            this.target = target.immutable();
            Objects.requireNonNull(config);
            dx = (double) target.getX() - source.getX();
            dy = (double) target.getY() - source.getY();
            dz = (double) target.getZ() - source.getZ();
            distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            wavelength = .299792458 / config.frequencyGhz();
            double radius = .6 * Math.sqrt(wavelength * Math.min(distance, MAX_RANGE) / 4);
            corridor = new Corridor(source, target, radius + 1);
            nominal = config.nominalCapacityMbps();
            latency = 1 + (int) Math.ceil(Math.min(MAX_RANGE, distance) / 299792.458);
            double errorA = alignmentError(source, target, config);
            double errorB = peer == null ? 180 : alignmentError(target, source, peer);
            // 20 dBm TX + 25 dBi at each end, -70 dBm receiver threshold: 140 dB path budget.
            // FSPL = 92.45 + 20 log10(GHz) + 20 log10(km); 20 dB margin gives nominal rate.
            // Past 15 degrees: up to 12 dB loss per end and rate falls to 25% approaching 30 degrees.
            double excessA = Math.max(0, errorA - FULL_ALIGNMENT_DEGREES) / 15;
            double excessB = Math.max(0, errorB - FULL_ALIGNMENT_DEGREES) / 15;
            double margin = 140 - (92.45 + 20 * Math.log10(config.frequencyGhz())
                    + 20 * Math.log10(Math.max(1, distance) / 1000)) - 12 * (excessA * excessA + excessB * excessB);
            double factor = Math.min(Math.clamp(margin / 20, 0, 1), Math.clamp(1 - .75 * Math.max(excessA, excessB), 0, 1));
            capacity = (int) Math.floor(nominal * factor + 1e-9);
            double divisor = Math.max(1, distance);
            nx = dx / divisor; ny = dy / divisor; nz = dz / divisor;
            projectionHalfWidth = .5 * (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
            dominant = Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz) ? 0 : Math.abs(dy) >= Math.abs(dz) ? 1 : 2;
            axisU = dominant == 0 ? 1 : 0;
            axisV = dominant == 2 ? 1 : 2;
            double dominantDelta = delta(dominant) == 0 ? 1 : delta(dominant);
            double slopeU = delta(axisU) / dominantDelta, slopeV = delta(axisV) / dominantDelta;
            // Circumscribed infinite-cylinder slab bounds. abs(slope)<=1; each extent<=6.572.
            extentU = radius * Math.sqrt(1 + slopeU * slopeU) + .5 * Math.abs(slopeU);
            extentV = radius * Math.sqrt(1 + slopeV * slopeV) + .5 * Math.abs(slopeV);
            // The ellipsoid's dominant-axis overhang is <0.008 m at 6 GHz, so endpoint
            // voxel centers (+0.5) keep its extent inside these inclusive integer slabs.
            slice = Math.min(coordinate(source, dominant), coordinate(target, dominant));
            lastSlice = Math.max(coordinate(source, dominant), coordinate(target, dominant));
            x = source.getX(); y = source.getY(); z = source.getZ();
            if (!config.enabled()) finish("disabled", null);
            else if (peer == null || source.equals(target) || !target.equals(config.peer()) || !source.equals(peer.peer())) finish("unpaired", null);
            else if (!peer.enabled()) finish("disabled", null);
            else if (config.channel() != peer.channel() || config.frequencyGhz() != peer.frequencyGhz()) finish("channel_mismatch", null);
            else if (distance > MAX_RANGE) finish("out_of_range", null);
            else if (Math.max(errorA, errorB) >= CUTOFF_DEGREES - 1e-9) finish("misaligned", null);
            else if (capacity == 0) finish("out_of_range", null);
        }

        public Corridor corridor() { return corridor; }
        public int probes() { return probes; }
        public int work() { return work; }
        public double distance() { return distance; }
        public boolean done() { return result != null; }
        public boolean waitingForTerrain() { return result != null && result.state().equals("unknown"); }
        /** Keep the exact cursor and all earlier clear proof; genuine changes require a new Trace. */
        public void resumeUnknown() {
            if (!waitingForTerrain()) throw new IllegalStateException("Trace is not waiting for terrain");
            result = null;
        }
        public Result result() {
            if (result == null) throw new IllegalStateException("Microwave evaluation is pending");
            return result;
        }

        /** Quota bounds geometry candidates as well as probes, including rejected clearance voxels. */
        public boolean advance(SignalPropagator.TerrainSampler sampler, int maxProbes, long deadline) {
            for (int used = 0; result == null && used < maxProbes; used++) {
                if (deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0) break;
                if (work++ >= MAX_PROBES) { work = MAX_PROBES; finish("limit", null); break; }
                BlockPos position;
                boolean center = !centerDone;
                if (center) position = new BlockPos(x, y, z);
                else {
                    if (!volumeStarted) { startSlice(); volumeStarted = true; }
                    position = dominant == 0 ? new BlockPos(slice, u, v)
                            : dominant == 1 ? new BlockPos(u, slice, v) : new BlockPos(u, v, slice);
                    if (!inClearance(position)) {
                        nextVoxel();
                        continue;
                    }
                }
                probes++;
                SignalPropagator.Material material = Objects.requireNonNull(sampler.sample(position));
                if (material == SignalPropagator.Material.UNKNOWN) { finish("unknown", position); break; }
                if (!position.equals(source) && !position.equals(target) && material != SignalPropagator.Material.AIR) {
                    finish(center ? "blocked" : "fresnel_blocked", position);
                    break;
                }
                if (center) {
                    if (position.equals(target)) centerDone = true;
                    else {
                        double tx = boundary(x, source.getX(), dx), ty = boundary(y, source.getY(), dy), tz = boundary(z, source.getZ(), dz);
                        double next = Math.min(tx, Math.min(ty, tz));
                        if (tx == next) x += (int) Math.signum(dx);
                        if (ty == next) y += (int) Math.signum(dy);
                        if (tz == next) z += (int) Math.signum(dz);
                    }
                } else nextVoxel();
            }
            return done();
        }

        private static int coordinate(BlockPos pos, int axis) {
            return axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
        }

        private double delta(int axis) { return axis == 0 ? dx : axis == 1 ? dy : dz; }

        private void startSlice() {
            double t = ((double) slice - coordinate(source, dominant)) / delta(dominant);
            double centerU = coordinate(source, axisU) + .5 + delta(axisU) * t;
            double centerV = coordinate(source, axisV) + .5 + delta(axisV) * t;
            minU = (int) Math.floor(centerU - extentU - 1e-9); maxU = (int) Math.floor(centerU + extentU + 1e-9);
            minV = (int) Math.floor(centerV - extentV - 1e-9); maxV = (int) Math.floor(centerV + extentV + 1e-9);
            u = minU; v = minV;
        }

        private void nextVoxel() {
            if (++u <= maxU) return;
            u = minU;
            if (++v <= maxV) return;
            if (++slice > lastSlice) finish(capacity == nominal ? "ready" : "degraded", null);
            else startSlice();
        }

        /**
         * Conservative voxel/clearance overlap, not sparse point samples. Project the entire voxel
         * onto the axis, take the largest 60%-zone radius on that interval, and intersect the axis
         * with the voxel expanded by that radius (a circumscribed cube rather than a sphere).
         * Every voxel touching the true Fresnel volume is included; boundary overcoverage is safe.
         */
        private boolean inClearance(BlockPos pos) {
            double along = ((double) pos.getX() - source.getX()) * nx
                    + ((double) pos.getY() - source.getY()) * ny + ((double) pos.getZ() - source.getZ()) * nz;
            double low = Math.max(0, along - projectionHalfWidth), high = Math.min(distance, along + projectionHalfWidth);
            if (low > high) return false;
            double middle = Math.clamp(distance / 2, low, high);
            double radius = .6 * Math.sqrt(wavelength * middle * (distance - middle) / distance) + 1e-9;
            low /= distance; high /= distance;
            for (int axis = 0; axis < 3; axis++) {
                double min = (double) coordinate(pos, axis) - coordinate(source, axis) - .5 - radius;
                double max = min + 1 + 2 * radius;
                double delta = delta(axis);
                if (delta == 0) {
                    if (min > 0 || max < 0) return false;
                } else {
                    double a = min / delta, b = max / delta;
                    low = Math.max(low, Math.min(a, b));
                    high = Math.min(high, Math.max(a, b));
                    if (low > high) return false;
                }
            }
            return true;
        }

        private static double boundary(int coordinate, int origin, double delta) {
            return delta == 0 ? Double.POSITIVE_INFINITY : (Math.abs((long) coordinate - origin) + .5) / Math.abs(delta);
        }

        private void finish(String state, BlockPos blocker) {
            result = new Result(state, state.equals("ready") || state.equals("degraded") ? capacity : 0, nominal, latency, blocker);
        }
    }
}
