package com.florentdubut.telecom.client;

import net.minecraft.client.Minecraft;
import com.florentdubut.telecom.client.gui.NetworkMapScreen;
import com.florentdubut.telecom.client.gui.SmartphoneScreen;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
import com.florentdubut.telecom.network.packet.RequestNetworkMapPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class ClientHooks {
    // openAntennaScreen is now handled via AntennaGuiSyncPayload from the server.
    // No more direct client-side opening with block entity.

    public static void openSmartphoneScreen() {
        Minecraft.getInstance().setScreen(new SmartphoneScreen());
    }

    public static void openNetworkMapScreen() {
        Minecraft.getInstance().setScreen(new NetworkMapScreen());
        ClientPacketDistributor.sendToServer(new RequestNetworkMapPayload());
    }

    public static void updateSmartphoneScreen(NetworkScanResponsePayload payload) {
        com.florentdubut.telecom.client.gui.SmartphoneHUD.latestScan = payload;
        com.florentdubut.telecom.client.gui.SmartphoneHUD.lastScanTime = System.currentTimeMillis();
    }
}
