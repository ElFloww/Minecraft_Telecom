package com.florentdubut.telecom.registry;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.CableBlock;
import com.florentdubut.telecom.block.RouterBlock;
import com.florentdubut.telecom.block.AntennaBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(TelecomMod.MODID);

    public static final DeferredBlock<Block> COPPER_CABLE = BLOCKS.register("copper_cable",
            () -> new CableBlock(BlockBehaviour.Properties.of().noOcclusion().strength(1.0f)));

    public static final DeferredBlock<Block> ROUTER = BLOCKS.register("router",
            () -> new RouterBlock(BlockBehaviour.Properties.of().strength(1.5f)));

    public static final DeferredBlock<Block> ANTENNA = BLOCKS.register("antenna",
            () -> new AntennaBlock(BlockBehaviour.Properties.of().noOcclusion().strength(1.5f)));
}
