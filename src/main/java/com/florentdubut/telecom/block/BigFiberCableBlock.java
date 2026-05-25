package com.florentdubut.telecom.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BigFiberCableBlock extends CableBlock {
    // 8 pixels thick (4.0 to 12.0)
    private static final VoxelShape CORE_SHAPE = Block.box(4.0D, 4.0D, 4.0D, 12.0D, 12.0D, 12.0D);
    private static final VoxelShape NORTH_SHAPE = Block.box(4.0D, 4.0D, 0.0D, 12.0D, 12.0D, 4.0D);
    private static final VoxelShape SOUTH_SHAPE = Block.box(4.0D, 4.0D, 12.0D, 12.0D, 12.0D, 16.0D);
    private static final VoxelShape EAST_SHAPE = Block.box(12.0D, 4.0D, 4.0D, 16.0D, 12.0D, 12.0D);
    private static final VoxelShape WEST_SHAPE = Block.box(0.0D, 4.0D, 4.0D, 4.0D, 12.0D, 12.0D);
    private static final VoxelShape UP_SHAPE = Block.box(4.0D, 12.0D, 4.0D, 12.0D, 16.0D, 12.0D);
    private static final VoxelShape DOWN_SHAPE = Block.box(4.0D, 0.0D, 4.0D, 12.0D, 4.0D, 12.0D);

    public BigFiberCableBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = CORE_SHAPE;
        if (state.getValue(NORTH)) shape = Shapes.or(shape, NORTH_SHAPE);
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, SOUTH_SHAPE);
        if (state.getValue(EAST)) shape = Shapes.or(shape, EAST_SHAPE);
        if (state.getValue(WEST)) shape = Shapes.or(shape, WEST_SHAPE);
        if (state.getValue(UP)) shape = Shapes.or(shape, UP_SHAPE);
        if (state.getValue(DOWN)) shape = Shapes.or(shape, DOWN_SHAPE);
        return shape;
    }
}
