package com.florentdubut.telecom.block;

import com.florentdubut.telecom.block.entity.TelecomHubBlockEntity;
import com.florentdubut.telecom.network.NetworkNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

import java.util.function.Supplier;

public class TelecomHubBlock extends Block implements EntityBlock, TelecomBlock {

    private final NetworkNode.NodeType hubType;
    private final Supplier<? extends BlockEntityType<?>> blockEntityTypeSupplier;

    public TelecomHubBlock(Properties properties, NetworkNode.NodeType hubType, Supplier<? extends BlockEntityType<?>> blockEntityTypeSupplier) {
        super(properties);
        this.hubType = hubType;
        this.blockEntityTypeSupplier = blockEntityTypeSupplier;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TelecomHubBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    // Default shapes for non-directional or full blocks
    private static final VoxelShape NRO_SHAPE = Shapes.block();
    private static final VoxelShape NRA_SHAPE = Shapes.block();
    
    // PM: 16 wide, 16 high, 8 deep. We need 4 orientations
    private static final VoxelShape PM_N = Block.box(0, 0, 8, 16, 16, 16);
    private static final VoxelShape PM_S = Block.box(0, 0, 0, 16, 16, 8);
    private static final VoxelShape PM_E = Block.box(0, 0, 0, 8, 16, 16);
    private static final VoxelShape PM_W = Block.box(8, 0, 0, 16, 16, 16);

    // SR: 12 wide, 12 high, 8 deep
    private static final VoxelShape SR_N = Block.box(2, 0, 8, 14, 12, 16);
    private static final VoxelShape SR_S = Block.box(2, 0, 0, 14, 12, 8);
    private static final VoxelShape SR_E = Block.box(0, 0, 2, 8, 12, 14);
    private static final VoxelShape SR_W = Block.box(8, 0, 2, 16, 12, 14);

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide()) {
            com.florentdubut.telecom.network.NetworkTracer.scheduleRecalculation((net.minecraft.server.level.ServerLevel) level);
        }
    }
}
