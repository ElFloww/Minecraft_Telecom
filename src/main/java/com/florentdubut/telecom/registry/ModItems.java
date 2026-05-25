package com.florentdubut.telecom.registry;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.item.SmartphoneItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TelecomMod.MODID);

    public static final DeferredItem<BlockItem> COPPER_CABLE = ITEMS.registerSimpleBlockItem("copper_cable", ModBlocks.COPPER_CABLE);
    public static final DeferredItem<BlockItem> ROUTER = ITEMS.registerSimpleBlockItem("router", ModBlocks.ROUTER);
    public static final DeferredItem<BlockItem> ANTENNA = ITEMS.registerSimpleBlockItem("antenna", ModBlocks.ANTENNA);

    public static final DeferredItem<Item> SMARTPHONE = ITEMS.register("smartphone",
            () -> new SmartphoneItem(new Item.Properties().stacksTo(1)));
}
