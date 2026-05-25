package com.florentdubut.telecom.registry;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.item.SmartphoneItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TelecomMod.MODID);

    public static final DeferredItem<BlockItem> COPPER_CABLE = ITEMS.register("copper_cable",
            () -> new BlockItem(ModBlocks.COPPER_CABLE.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> FIBER_CABLE = ITEMS.register("fiber_cable",
            () -> new BlockItem(ModBlocks.FIBER_CABLE.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> ROUTER = ITEMS.register("router",
            () -> new BlockItem(ModBlocks.ROUTER.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> SERVER = ITEMS.register("server",
            () -> new BlockItem(ModBlocks.SERVER.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> ANTENNA = ITEMS.register("antenna",
            () -> new BlockItem(ModBlocks.ANTENNA.get(), new Item.Properties()));

    public static final DeferredItem<SmartphoneItem> SMARTPHONE = ITEMS.register("smartphone",
            () -> new SmartphoneItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> NETWORK_TOOL = ITEMS.register("network_tool",
            () -> new com.florentdubut.telecom.item.NetworkToolItem(new Item.Properties().stacksTo(1)));
}
