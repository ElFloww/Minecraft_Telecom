package com.florentdubut.telecom.block;

import com.florentdubut.telecom.block.entity.ServerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ServerBlock extends Block implements EntityBlock, TelecomBlock {

    public ServerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ServerBlockEntity(pos, state);
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                    com.florentdubut.telecom.network.ModNetworking.createServerSnapshot(serverPlayer, pos, java.util.UUID.randomUUID(), true));
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
}
