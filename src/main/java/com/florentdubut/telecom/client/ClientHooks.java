package com.florentdubut.telecom.client;

import net.minecraft.client.Minecraft;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;

public class ClientHooks {
    // openAntennaScreen is now handled via AntennaGuiSyncPayload from the server.
    // No more direct client-side opening with block entity.

    public static void openSmartphoneScreen() {
        // No longer used
    }

    public static void updateSmartphoneScreen(NetworkScanResponsePayload payload) {
        com.florentdubut.telecom.client.gui.SmartphoneHUD.latestScan = payload;
        com.florentdubut.telecom.client.gui.SmartphoneHUD.lastScanTime = System.currentTimeMillis();
    }
}
