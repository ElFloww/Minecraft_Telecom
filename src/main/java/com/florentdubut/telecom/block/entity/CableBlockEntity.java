package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import com.florentdubut.telecom.network.TelecomNetworkGraph;

public class CableBlockEntity extends BlockEntity {
    public CableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CABLE_BE.get(), pos, state);
    }

    public void onPlaced() {
        if (level instanceof ServerLevel serverLevel) {
            // Trigger network update nearby
            // TODO: Implement network traversal
        }
    }

    public void onRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            // Trigger network update nearby
            // TODO: Implement network traversal
        }
    }
}
