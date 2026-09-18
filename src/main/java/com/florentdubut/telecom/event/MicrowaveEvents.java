package com.florentdubut.telecom.event;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.MicrowaveLinkService;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid = TelecomMod.MODID)
public final class MicrowaveEvents {
    private MicrowaveEvents() { }

    @SubscribeEvent
    public static void loaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var pos = event.getChunk().getPos();
            MicrowaveLinkService.invalidateChunk(level, pos.x, pos.z);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void unloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var pos = event.getChunk().getPos();
            MicrowaveLinkService.chunkUnloaded(level, pos.x, pos.z);
        }
    }

    @SubscribeEvent
    public static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) MicrowaveLinkService.unload(level);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { MicrowaveLinkService.clear(); }
}
