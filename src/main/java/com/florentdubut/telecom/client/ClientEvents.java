package com.florentdubut.telecom.client;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.client.gui.SmartphoneHUD;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.minecraft.resources.ResourceLocation;

@EventBusSubscriber(modid = TelecomMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "smartphone_hud"), SmartphoneHUD::render);
    }
}
