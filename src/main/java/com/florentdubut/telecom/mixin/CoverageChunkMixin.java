package com.florentdubut.telecom.mixin;

import com.florentdubut.telecom.network.CoverageService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class CoverageChunkMixin {
    @Shadow
    public abstract Level getLevel();

    // Observe actual mutations, including /setblock strict and updates without neighbor events.
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void telecom$invalidateCoverage(BlockPos position, BlockState state, int flags,
                                            CallbackInfoReturnable<BlockState> callback) {
        if (callback.getReturnValue() != null && callback.getReturnValue() != state
                && getLevel() instanceof ServerLevel level && level.getServer().isSameThread()) {
            CoverageService.invalidateChunk(level, position.getX() >> 4, position.getZ() >> 4);
        }
    }
}
