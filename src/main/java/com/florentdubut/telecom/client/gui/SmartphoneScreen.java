package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.NetworkScanRequestPayload;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class SmartphoneScreen extends Screen {
    private boolean isScanning = true;
    private boolean hasNetwork = false;
    private String networkName = "";
    private int signalStrength = 0;
    private String technology = "";

    public SmartphoneScreen() {
        super(Component.literal("Smartphone"));
    }

    @Override
    protected void init() {
        super.init();
        // Send scan request when opened
        PacketDistributor.sendToServer(new NetworkScanRequestPayload());
    }

    public void updateNetworkInfo(NetworkScanResponsePayload payload) {
        this.isScanning = false;
        this.hasNetwork = payload.found();
        this.networkName = payload.name();
        this.signalStrength = payload.signalStrength();
        this.technology = payload.tech();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // Draw Phone Outline (Simple Box for now)
        guiGraphics.fill(centerX - 80, centerY - 120, centerX + 80, centerY + 120, 0xFF222222);
        guiGraphics.fill(centerX - 75, centerY - 110, centerX + 75, centerY + 110, 0xFF111111);
        
        // Top Bar (Signal, Time)
        guiGraphics.fill(centerX - 75, centerY - 110, centerX + 75, centerY - 95, 0xFF333333);
        
        if (isScanning) {
            guiGraphics.drawCenteredString(this.font, "Scanning for network...", centerX, centerY, 0xFFFFFF);
        } else if (hasNetwork) {
            String signalText = technology + " | " + signalStrength + " dBm";
            guiGraphics.drawString(this.font, signalText, centerX - 70, centerY - 105, 0x00FF00);
            
            guiGraphics.drawCenteredString(this.font, "Connected to:", centerX, centerY - 20, 0xAAAAAA);
            guiGraphics.drawCenteredString(this.font, networkName, centerX, centerY, 0xFFFFFF);
            
            // Draw Signal Bars
            int bars = 0;
            if (signalStrength > -60) bars = 4;
            else if (signalStrength > -80) bars = 3;
            else if (signalStrength > -100) bars = 2;
            else if (signalStrength > -115) bars = 1;
            
            for (int i = 0; i < 4; i++) {
                int color = i < bars ? 0xFF00FF00 : 0xFF555555;
                guiGraphics.fill(centerX + 50 + (i * 5), centerY - 105 + (12 - (i * 3)), centerX + 53 + (i * 5), centerY - 93, color);
            }
        } else {
            guiGraphics.drawString(this.font, "No Service", centerX - 70, centerY - 105, 0xFF0000);
            guiGraphics.drawCenteredString(this.font, "No Antennas in Range", centerX, centerY, 0xFF5555);
        }
    }
    
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
