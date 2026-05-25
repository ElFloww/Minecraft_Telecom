package com.florentdubut.telecom.client;

import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.client.gui.AntennaScreen;
import net.minecraft.client.Minecraft;

import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;

public class ClientHooks {
    public static void openAntennaScreen(AntennaBlockEntity blockEntity) {
        Minecraft.getInstance().setScreen(new AntennaScreen(blockEntity));
    }

    public static void openSmartphoneScreen() {
        Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.SmartphoneScreen());
    }

    public static void updateSmartphoneScreen(NetworkScanResponsePayload payload) {
        if (Minecraft.getInstance().screen instanceof com.florentdubut.telecom.client.gui.SmartphoneScreen phone) {
            phone.updateNetworkInfo(payload);
        }
    }
}
