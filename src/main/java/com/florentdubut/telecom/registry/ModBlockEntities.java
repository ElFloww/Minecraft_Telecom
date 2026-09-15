package com.florentdubut.telecom.registry;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.CableBlockEntity;
import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE, TelecomMod.MODID);

    public static final java.util.function.Supplier<BlockEntityType<CableBlockEntity>> CABLE_BE = BLOCK_ENTITIES.register("cable",
            () -> new BlockEntityType<>(CableBlockEntity::new, ModBlocks.COPPER_CABLE.get(), ModBlocks.FIBER_CABLE.get(), ModBlocks.MEDIUM_FIBER_CABLE.get(), ModBlocks.BIG_FIBER_CABLE.get()));

    public static final java.util.function.Supplier<BlockEntityType<RouterBlockEntity>> ROUTER_BE = BLOCK_ENTITIES.register("router",
            () -> new BlockEntityType<>(RouterBlockEntity::new,
                ModBlocks.ROUTER.get(),
                ModBlocks.ROUTER_LITE.get(),
                ModBlocks.ROUTER_MAX.get(),
                ModBlocks.ROUTER_PRO.get()
            ));

    public static final java.util.function.Supplier<BlockEntityType<AntennaBlockEntity>> ANTENNA_BE = BLOCK_ENTITIES.register("antenna",
            () -> new BlockEntityType<>(AntennaBlockEntity::new, ModBlocks.ANTENNA.get()));

    public static final java.util.function.Supplier<BlockEntityType<com.florentdubut.telecom.block.entity.ServerBlockEntity>> SERVER_BE = BLOCK_ENTITIES.register("server",
            () -> new BlockEntityType<>(com.florentdubut.telecom.block.entity.ServerBlockEntity::new, ModBlocks.SERVER.get()));

    public static final java.util.function.Supplier<BlockEntityType<com.florentdubut.telecom.block.entity.TelecomHubBlockEntity>> TELECOM_HUB = BLOCK_ENTITIES.register("telecom_hub",
            () -> new BlockEntityType<>(com.florentdubut.telecom.block.entity.TelecomHubBlockEntity::new, ModBlocks.NRO_BLOCK.get(), ModBlocks.NRA_BLOCK.get(), ModBlocks.PM_BLOCK.get(), ModBlocks.SR_BLOCK.get()));
}
