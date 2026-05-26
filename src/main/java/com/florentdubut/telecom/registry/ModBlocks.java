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

    public static final DeferredBlock<Block> FIBER_CABLE = BLOCKS.register("fiber_cable",
            () -> new CableBlock(BlockBehaviour.Properties.of().noOcclusion().strength(1.0f)));

    public static final DeferredBlock<Block> ROUTER = BLOCKS.register("router",
            () -> new RouterBlock(BlockBehaviour.Properties.of().strength(1.5f), 2000, 1000));
            
    public static final DeferredBlock<Block> ROUTER_LITE = BLOCKS.register("router_lite",
            () -> new RouterBlock(BlockBehaviour.Properties.of().strength(1.5f), 1000, 700));
            
    public static final DeferredBlock<Block> ROUTER_MAX = BLOCKS.register("router_max",
            () -> new RouterBlock(BlockBehaviour.Properties.of().strength(1.5f), 8000, 8000));
            
    public static final DeferredBlock<Block> ROUTER_PRO = BLOCKS.register("router_pro",
            () -> new RouterBlock(BlockBehaviour.Properties.of().strength(1.5f), 10000, 10000));

    public static final DeferredBlock<Block> SERVER = BLOCKS.register("server",
            () -> new com.florentdubut.telecom.block.ServerBlock(BlockBehaviour.Properties.of().strength(2.0f)));

    public static final DeferredBlock<Block> ANTENNA = BLOCKS.register("antenna",
            () -> new AntennaBlock(BlockBehaviour.Properties.of().noOcclusion().strength(1.5f)));

    public static final DeferredBlock<Block> MEDIUM_FIBER_CABLE = BLOCKS.register("medium_fiber_cable",
            () -> new com.florentdubut.telecom.block.MediumFiberCableBlock(BlockBehaviour.Properties.of().noOcclusion().strength(1.0f)));

    public static final DeferredBlock<Block> BIG_FIBER_CABLE = BLOCKS.register("big_fiber_cable",
            () -> new com.florentdubut.telecom.block.BigFiberCableBlock(BlockBehaviour.Properties.of().noOcclusion().strength(1.0f)));

    public static final DeferredBlock<Block> NRO_BLOCK = BLOCKS.register("nro",
            () -> new com.florentdubut.telecom.block.TelecomHubBlock(BlockBehaviour.Properties.of().strength(1.5f), com.florentdubut.telecom.network.NetworkNode.NodeType.NRO, ModBlockEntities.TELECOM_HUB));

    public static final DeferredBlock<Block> NRA_BLOCK = BLOCKS.register("nra",
            () -> new com.florentdubut.telecom.block.TelecomHubBlock(BlockBehaviour.Properties.of().strength(1.5f), com.florentdubut.telecom.network.NetworkNode.NodeType.NRA, ModBlockEntities.TELECOM_HUB));

    public static final DeferredBlock<Block> PM_BLOCK = BLOCKS.register("pm",
            () -> new com.florentdubut.telecom.block.TelecomHubBlock(BlockBehaviour.Properties.of().strength(1.5f), com.florentdubut.telecom.network.NetworkNode.NodeType.PM, ModBlockEntities.TELECOM_HUB));

    public static final DeferredBlock<Block> SR_BLOCK = BLOCKS.register("sr",
            () -> new com.florentdubut.telecom.block.TelecomHubBlock(BlockBehaviour.Properties.of().strength(1.5f), com.florentdubut.telecom.network.NetworkNode.NodeType.SR, ModBlockEntities.TELECOM_HUB));
}
