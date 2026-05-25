package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

public class SignalPropagator {

    public static class SignalResult {
        public final TelecomFrequency frequency;
        public final float powerDbm; // Signal strength in dBm, e.g. -50 is excellent, -110 is poor

        public SignalResult(TelecomFrequency frequency, float powerDbm) {
            this.frequency = frequency;
            this.powerDbm = powerDbm;
        }
    }

    public static SignalResult calculateSignal(Level level, BlockPos antennaPos, BlockPos playerPos, TelecomFrequency freq) {
        Vec3 start = Vec3.atCenterOf(antennaPos);
        Vec3 end = Vec3.atCenterOf(playerPos);
        double distance = start.distanceTo(end);
        
        // Base power at source
        float currentPower = 0f; // 0 dBm is roughly equivalent to a strong transmitter near it
        
        // Distance attenuation (Free-space path loss formula simplified)
        // 20 * log10(distance) + 20 * log10(frequency) + 32.44
        float freeSpaceLoss = (float) (20 * Math.log10(Math.max(1, distance)) + 20 * Math.log10(freq.getFrequencyMhz()) - 27.55);
        currentPower -= freeSpaceLoss;
        
        // Raycast to check for blocks between antenna and player
        // Since Minecraft doesn't easily let us get all blocks intersected by a ray,
        // we step along the vector manually to find blocks.
        Vec3 dir = end.subtract(start).normalize();
        int steps = (int) Math.ceil(distance);
        for (int i = 0; i < steps; i++) {
            BlockPos currentBlock = BlockPos.containing(start.add(dir.scale(i)));
            if (currentBlock.equals(antennaPos) || currentBlock.equals(playerPos)) {
                continue;
            }
            
            BlockState state = level.getBlockState(currentBlock);
            if (!state.isAir()) {
                // Determine material penalty
                float penalty = getMaterialPenalty(state, freq);
                currentPower -= penalty;
            } else {
                currentPower -= freq.getBaseAttenuation();
            }
        }
        
        return new SignalResult(freq, currentPower);
    }

    private static float getMaterialPenalty(BlockState state, TelecomFrequency freq) {
        // High frequency = worse penetration
        float frequencyMultiplier = freq.getFrequencyMhz() / 900f; 
        
        if (!state.getFluidState().isEmpty()) {
            return 5.0f * frequencyMultiplier; // Water blocks signal heavily
        } else if (!state.canOcclude()) {
            return 1.0f * frequencyMultiplier; // Leaves, glass, etc.
        } else {
            return 10.0f * frequencyMultiplier; // Stone, dirt, solid blocks
        }
    }
}
