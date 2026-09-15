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

    public static final DeferredBlock<Block> COPPER_CABLE = BLOCKS.registerBlock("copper_cable",
            CableBlock::new, () -> BlockBehaviour.Properties.of().noOcclusion().strength(1.0f));

    public static final DeferredBlock<Block> FIBER_CABLE = BLOCKS.registerBlock("fiber_cable",
            CableBlock::new, () -> BlockBehaviour.Properties.of().noOcclusion().strength(1.0f));

    public static final DeferredBlock<Block> ROUTER = BLOCKS.registerBlock("router",
            properties -> new RouterBlock(properties, 10000, 10000), () -> BlockBehaviour.Properties.of().strength(1.5f));
            
    public static final DeferredBlock<Block> ROUTER_LITE = BLOCKS.registerBlock("router_lite",
            properties -> new RouterBlock(properties, 1000, 700), () -> BlockBehaviour.Properties.of().strength(1.5f));
            
    public static final DeferredBlock<Block> ROUTER_MAX = BLOCKS.registerBlock("router_max",
            properties -> new RouterBlock(properties, 8000, 8000), () -> BlockBehaviour.Properties.of().strength(1.5f));
            
    public static final DeferredBlock<Block> ROUTER_PRO = BLOCKS.registerBlock("router_pro",
            properties -> new RouterBlock(properties, 10000, 10000), () -> BlockBehaviour.Properties.of().strength(1.5f));

    public static final DeferredBlock<Block> SERVER = BLOCKS.registerBlock("server",
            com.florentdubut.telecom.block.ServerBlock::new, () -> BlockBehaviour.Properties.of().strength(2.0f));

    public static final DeferredBlock<Block> ANTENNA = BLOCKS.registerBlock("antenna",
            AntennaBlock::new, () -> BlockBehaviour.Properties.of().noOcclusion().strength(1.5f));

    public static final DeferredBlock<Block> MEDIUM_FIBER_CABLE = BLOCKS.registerBlock("medium_fiber_cable",
            com.florentdubut.telecom.block.MediumFiberCableBlock::new, () -> BlockBehaviour.Properties.of().noOcclusion().strength(1.0f));

    public static final DeferredBlock<Block> BIG_FIBER_CABLE = BLOCKS.registerBlock("big_fiber_cable",
            com.florentdubut.telecom.block.BigFiberCableBlock::new, () -> BlockBehaviour.Properties.of().noOcclusion().strength(1.0f));

    public static final DeferredBlock<Block> NRO_BLOCK = BLOCKS.registerBlock("nro",
            properties -> new com.florentdubut.telecom.block.TelecomHubBlock(properties, com.florentdubut.telecom.network.NetworkNode.NodeType.NRO, ModBlockEntities.TELECOM_HUB), () -> BlockBehaviour.Properties.of().strength(1.5f));

    public static final DeferredBlock<Block> NRA_BLOCK = BLOCKS.registerBlock("nra",
            properties -> new com.florentdubut.telecom.block.TelecomHubBlock(properties, com.florentdubut.telecom.network.NetworkNode.NodeType.NRA, ModBlockEntities.TELECOM_HUB), () -> BlockBehaviour.Properties.of().strength(1.5f));

    public static final DeferredBlock<Block> PM_BLOCK = BLOCKS.registerBlock("pm",
            properties -> new com.florentdubut.telecom.block.TelecomHubBlock(properties, com.florentdubut.telecom.network.NetworkNode.NodeType.PM, ModBlockEntities.TELECOM_HUB), () -> BlockBehaviour.Properties.of().strength(1.5f));

    public static final DeferredBlock<Block> SR_BLOCK = BLOCKS.registerBlock("sr",
            properties -> new com.florentdubut.telecom.block.TelecomHubBlock(properties, com.florentdubut.telecom.network.NetworkNode.NodeType.SR, ModBlockEntities.TELECOM_HUB), () -> BlockBehaviour.Properties.of().strength(1.5f));
}
