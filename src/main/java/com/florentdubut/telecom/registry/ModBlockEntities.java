package com.florentdubut.telecom.registry;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.CableBlockEntity;
import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE, TelecomMod.MODID);

    public static final java.util.function.Supplier<BlockEntityType<CableBlockEntity>> CABLE_BE = BLOCK_ENTITIES.register("cable",
            () -> BlockEntityType.Builder.of(CableBlockEntity::new, ModBlocks.COPPER_CABLE.get(), ModBlocks.FIBER_CABLE.get(), ModBlocks.MEDIUM_FIBER_CABLE.get(), ModBlocks.BIG_FIBER_CABLE.get()).build(null));

    public static final java.util.function.Supplier<BlockEntityType<RouterBlockEntity>> ROUTER_BE = BLOCK_ENTITIES.register("router",
            () -> BlockEntityType.Builder.of(RouterBlockEntity::new, ModBlocks.ROUTER.get()).build(null));

    public static final java.util.function.Supplier<BlockEntityType<AntennaBlockEntity>> ANTENNA_BE = BLOCK_ENTITIES.register("antenna",
            () -> BlockEntityType.Builder.of(AntennaBlockEntity::new, ModBlocks.ANTENNA.get()).build(null));

    public static final java.util.function.Supplier<BlockEntityType<com.florentdubut.telecom.block.entity.ServerBlockEntity>> SERVER_BE = BLOCK_ENTITIES.register("server",
            () -> BlockEntityType.Builder.of(com.florentdubut.telecom.block.entity.ServerBlockEntity::new, ModBlocks.SERVER.get()).build(null));

    public static final java.util.function.Supplier<BlockEntityType<com.florentdubut.telecom.block.entity.TelecomHubBlockEntity>> TELECOM_HUB = BLOCK_ENTITIES.register("telecom_hub",
            () -> BlockEntityType.Builder.of(com.florentdubut.telecom.block.entity.TelecomHubBlockEntity::new, ModBlocks.NRO_BLOCK.get(), ModBlocks.NRA_BLOCK.get(), ModBlocks.PM_BLOCK.get(), ModBlocks.SR_BLOCK.get()).build(null));
}
