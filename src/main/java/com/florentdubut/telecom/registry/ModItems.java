package com.florentdubut.telecom.registry;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.item.SmartphoneItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TelecomMod.MODID);

    public static final DeferredItem<BlockItem> COPPER_CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.COPPER_CABLE);

    public static final DeferredItem<BlockItem> FIBER_CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.FIBER_CABLE);

    public static final DeferredItem<BlockItem> ROUTER = ITEMS.registerSimpleBlockItem(ModBlocks.ROUTER);
            
    public static final DeferredItem<BlockItem> ROUTER_LITE = ITEMS.registerSimpleBlockItem(ModBlocks.ROUTER_LITE);
            
    public static final DeferredItem<BlockItem> ROUTER_MAX = ITEMS.registerSimpleBlockItem(ModBlocks.ROUTER_MAX);
            
    public static final DeferredItem<BlockItem> ROUTER_PRO = ITEMS.registerSimpleBlockItem(ModBlocks.ROUTER_PRO);

    public static final DeferredItem<BlockItem> SERVER = ITEMS.registerSimpleBlockItem(ModBlocks.SERVER);

    public static final DeferredItem<BlockItem> ANTENNA = ITEMS.registerSimpleBlockItem(ModBlocks.ANTENNA);

    public static final DeferredItem<BlockItem> MICROWAVE_DISH = ITEMS.registerSimpleBlockItem(ModBlocks.MICROWAVE_DISH);

    public static final DeferredItem<BlockItem> MEDIUM_FIBER_CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.MEDIUM_FIBER_CABLE);

    public static final DeferredItem<BlockItem> BIG_FIBER_CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.BIG_FIBER_CABLE);

    public static final DeferredItem<BlockItem> NRO_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.NRO_BLOCK);

    public static final DeferredItem<BlockItem> NRA_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.NRA_BLOCK);

    public static final DeferredItem<BlockItem> PM_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.PM_BLOCK);

    public static final DeferredItem<BlockItem> SR_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.SR_BLOCK);

    public static final DeferredItem<SmartphoneItem> SMARTPHONE = ITEMS.registerItem("smartphone",
            SmartphoneItem::new, () -> new Item.Properties().stacksTo(1));

    public static final DeferredItem<Item> NETWORK_TOOL = ITEMS.registerItem("network_tool",
            com.florentdubut.telecom.item.NetworkToolItem::new, () -> new Item.Properties().stacksTo(1));

    public static final DeferredItem<Item> NETWORK_MAP = ITEMS.registerItem("network_map",
            com.florentdubut.telecom.item.NetworkMapItem::new, () -> new Item.Properties().stacksTo(1));
}
