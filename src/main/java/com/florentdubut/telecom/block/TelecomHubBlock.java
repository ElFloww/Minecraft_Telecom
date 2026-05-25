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

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide()) {
            com.florentdubut.telecom.network.NetworkTracer.recalculateNetwork((net.minecraft.server.level.ServerLevel) level);
        }
    }

}
