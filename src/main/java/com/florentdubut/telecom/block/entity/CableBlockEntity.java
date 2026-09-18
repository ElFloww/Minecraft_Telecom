package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.network.NetworkDiagnostics;

import com.florentdubut.telecom.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

public class CableBlockEntity extends BlockEntity {
    public CableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CABLE_BE.get(), pos, state);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        onRemoved();
    }

    public void onRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            com.florentdubut.telecom.network.NetworkTracer.scheduleRecalculation(serverLevel, NetworkDiagnostics.Cause.CABLE_REMOVE);
        }
    }
}
