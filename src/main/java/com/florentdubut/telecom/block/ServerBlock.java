package com.florentdubut.telecom.block;

import com.florentdubut.telecom.block.entity.ServerBlockEntity;
import com.florentdubut.telecom.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ServerBlock extends Block implements EntityBlock {

    public ServerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ServerBlockEntity(pos, state);
    }

    @Override
    protected void onPlace(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ServerBlockEntity server) {
            server.onPlaced();
        }
    }

    @Override
    protected void onRemove(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ServerBlockEntity server) {
                server.onRemoved();
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}
