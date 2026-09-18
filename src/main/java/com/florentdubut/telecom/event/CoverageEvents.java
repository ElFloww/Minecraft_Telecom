package com.florentdubut.telecom.event;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.CoverageService;
import com.florentdubut.telecom.network.RadioAccessService;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = TelecomMod.MODID)
public final class CoverageEvents {
    private CoverageEvents() { }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        CoverageService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void chunkLoaded(ChunkEvent.Load event) { invalidate(event); }

    @SubscribeEvent
    public static void chunkUnloaded(ChunkEvent.Unload event) { invalidate(event); }

    private static void invalidate(ChunkEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            CoverageService.invalidateCoverageChunk(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        CoverageService.clear();
        RadioAccessService.clear();
    }

    @SubscribeEvent
    public static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) RadioAccessService.unload(level);
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        RadioAccessService.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        RadioAccessService.forget(event.getEntity().getUUID());
    }
}
