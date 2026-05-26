package com.florentdubut.telecom;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.florentdubut.telecom.registry.ModBlocks;
import com.florentdubut.telecom.registry.ModItems;
import com.florentdubut.telecom.registry.ModBlockEntities;

@Mod(TelecomMod.MODID)
public class TelecomMod {
    public static final String MODID = "telecom";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TELECOM_TAB = CREATIVE_MODE_TABS.register("telecom_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.telecom"))
            .displayItems((parameters, output) -> {
                output.accept(ModItems.COPPER_CABLE.get());
                output.accept(ModItems.FIBER_CABLE.get());
                output.accept(ModItems.MEDIUM_FIBER_CABLE.get());
                output.accept(ModItems.BIG_FIBER_CABLE.get());
                output.accept(ModItems.SERVER.get());
                output.accept(ModItems.NRO_BLOCK.get());
                output.accept(ModItems.NRA_BLOCK.get());
                output.accept(ModItems.PM_BLOCK.get());
                output.accept(ModItems.SR_BLOCK.get());
                output.accept(ModItems.ROUTER.get());
                output.accept(ModItems.ANTENNA.get());
                output.accept(ModItems.SMARTPHONE.get());
                output.accept(ModItems.NETWORK_TOOL.get());
                output.accept(ModItems.NETWORK_MAP.get());
            }).build());

    public TelecomMod(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Telecom Mod Common Setup");
    }

    private com.florentdubut.telecom.server.TelecomHttpServer httpServer = new com.florentdubut.telecom.server.TelecomHttpServer();

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Telecom Mod Server Starting");
    }

    @SubscribeEvent
    public void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        httpServer.start(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        httpServer.stop();
    }

    @EventBusSubscriber(modid = TelecomMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    static class ClientModEvents {
        @SubscribeEvent
        static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("Telecom Mod Client Setup");
        }
    }
}
