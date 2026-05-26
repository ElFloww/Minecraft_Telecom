package com.florentdubut.telecom.block;

import com.florentdubut.telecom.block.entity.CableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;

public class CableBlock extends Block implements EntityBlock {

    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;

    private static final VoxelShape CORE_SHAPE = Block.box(6.0D, 6.0D, 6.0D, 10.0D, 10.0D, 10.0D);
    private static final VoxelShape NORTH_SHAPE = Block.box(6.0D, 6.0D, 0.0D, 10.0D, 10.0D, 6.0D);
    private static final VoxelShape SOUTH_SHAPE = Block.box(6.0D, 6.0D, 10.0D, 10.0D, 10.0D, 16.0D);
    private static final VoxelShape EAST_SHAPE = Block.box(10.0D, 6.0D, 6.0D, 16.0D, 10.0D, 10.0D);
    private static final VoxelShape WEST_SHAPE = Block.box(0.0D, 6.0D, 6.0D, 6.0D, 10.0D, 10.0D);
    private static final VoxelShape UP_SHAPE = Block.box(6.0D, 10.0D, 6.0D, 10.0D, 16.0D, 10.0D);
    private static final VoxelShape DOWN_SHAPE = Block.box(6.0D, 0.0D, 6.0D, 10.0D, 6.0D, 10.0D);

    public CableBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(UP, false)
                .setValue(DOWN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
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

    private boolean connectsTo(BlockState myState, BlockState neighborState) {
        // Same cable type: always connects visually
        if (neighborState.getBlock() == myState.getBlock()) return true;

        // Cable → other cable type: never connects
        if (neighborState.getBlock() instanceof CableBlock) return false;

        // Cable → node: check architectural compatibility
        if (neighborState.getBlock() instanceof TelecomBlock) {
            com.florentdubut.telecom.network.NetworkEdge.EdgeType myCableType = null;
            if (myState.getBlock() == com.florentdubut.telecom.registry.ModBlocks.COPPER_CABLE.get())        myCableType = com.florentdubut.telecom.network.NetworkEdge.EdgeType.COPPER;
            else if (myState.getBlock() == com.florentdubut.telecom.registry.ModBlocks.FIBER_CABLE.get())    myCableType = com.florentdubut.telecom.network.NetworkEdge.EdgeType.FIBER;
            else if (myState.getBlock() == com.florentdubut.telecom.registry.ModBlocks.MEDIUM_FIBER_CABLE.get()) myCableType = com.florentdubut.telecom.network.NetworkEdge.EdgeType.MEDIUM_FIBER;
            else if (myState.getBlock() == com.florentdubut.telecom.registry.ModBlocks.BIG_FIBER_CABLE.get())    myCableType = com.florentdubut.telecom.network.NetworkEdge.EdgeType.BIG_FIBER;
            if (myCableType == null) return true; // Unknown cable type, allow

            com.florentdubut.telecom.network.NetworkNode.NodeType nodeType = null;
            if (neighborState.getBlock() instanceof com.florentdubut.telecom.block.RouterBlock)        nodeType = com.florentdubut.telecom.network.NetworkNode.NodeType.ROUTER;
            else if (neighborState.getBlock() instanceof com.florentdubut.telecom.block.ServerBlock)   nodeType = com.florentdubut.telecom.network.NetworkNode.NodeType.SERVER;
            else if (neighborState.getBlock() instanceof com.florentdubut.telecom.block.AntennaBlock)  nodeType = com.florentdubut.telecom.network.NetworkNode.NodeType.ANTENNA;
            else if (neighborState.getBlock() instanceof com.florentdubut.telecom.block.TelecomHubBlock hub) nodeType = hub.getHubType();
            if (nodeType == null) return true;

            return com.florentdubut.telecom.network.NetworkTracer.isCableCompatibleWithNodes(myCableType, nodeType, nodeType);
        }
        return false;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState myState = this.defaultBlockState(); // Just for type identity check
        return this.defaultBlockState()
                .setValue(NORTH, connectsTo(myState, level.getBlockState(pos.north())))
                .setValue(SOUTH, connectsTo(myState, level.getBlockState(pos.south())))
                .setValue(EAST, connectsTo(myState, level.getBlockState(pos.east())))
                .setValue(WEST, connectsTo(myState, level.getBlockState(pos.west())))
                .setValue(UP, connectsTo(myState, level.getBlockState(pos.above())))
                .setValue(DOWN, connectsTo(myState, level.getBlockState(pos.below())));
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos currentPos, BlockPos neighborPos) {
        BooleanProperty property = getPropertyForDirection(direction);
        return state.setValue(property, connectsTo(state, neighborState));
    }

    private BooleanProperty getPropertyForDirection(Direction dir) {
        return switch (dir) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CableBlockEntity(pos, state);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CableBlockEntity cable) {
                cable.onPlaced();
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock()) {
            if (!level.isClientSide()) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof CableBlockEntity cable) {
                    cable.onRemoved();
                }
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}
