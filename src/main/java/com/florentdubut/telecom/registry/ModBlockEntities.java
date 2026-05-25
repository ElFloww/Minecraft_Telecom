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
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TelecomMod.MODID);

    public static final Supplier<BlockEntityType<CableBlockEntity>> CABLE_BE = BLOCK_ENTITIES.register("cable",
            () -> BlockEntityType.Builder.of(CableBlockEntity::new, ModBlocks.COPPER_CABLE.get()).build(null));

    public static final Supplier<BlockEntityType<RouterBlockEntity>> ROUTER_BE = BLOCK_ENTITIES.register("router",
            () -> BlockEntityType.Builder.of(RouterBlockEntity::new, ModBlocks.ROUTER.get()).build(null));

    public static final Supplier<BlockEntityType<AntennaBlockEntity>> ANTENNA_BE = BLOCK_ENTITIES.register("antenna",
            () -> BlockEntityType.Builder.of(AntennaBlockEntity::new, ModBlocks.ANTENNA.get()).build(null));
}
