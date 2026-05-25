package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.RouterGuiSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RouterScreen extends Screen {

    private final RouterGuiSyncPayload payload;

    public RouterScreen(RouterGuiSyncPayload payload) {
        super(Component.literal("Router Interface"));
        this.payload = payload;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Do nothing
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Dark background overlay
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0x40101010, 0x60101010);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int boxWidth = 240;
        int boxHeight = 160;
        int startX = centerX - boxWidth / 2;
        int startY = centerY - boxHeight / 2;

        // Router Box Background
        guiGraphics.fill(startX, startY, startX + boxWidth, startY + boxHeight, 0xDD222222);
        // Border
        guiGraphics.renderOutline(startX, startY, boxWidth, boxHeight, 0xDD44AAFF);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 100);

        // Title
        guiGraphics.drawCenteredString(this.font, "ROUTER CONFIGURATION", centerX, startY + 10, 0xFFFFFF);

        // Status
        String statusText = payload.isConnected() ? "CONNECTED TO NETWORK" : "OFFLINE";
        int statusColor = payload.isConnected() ? 0x00FF00 : 0xFF0000;
        guiGraphics.drawString(this.font, "Status: " + statusText, startX + 20, startY + 40, statusColor);

        // IP Address
        guiGraphics.drawString(this.font, "IP Address: " + (!payload.ipAddress().isEmpty() ? payload.ipAddress() : "N/A"), startX + 20, startY + 60, 0xCCCCCC);

        // Ping
        guiGraphics.drawString(this.font, "Ping: " + (payload.isConnected() ? payload.pingMs() + " ms" : "---"), startX + 20, startY + 80, 0xCCCCCC);

        // Bandwidth
        guiGraphics.drawString(this.font, "Max Bandwidth: " + (payload.isConnected() ? payload.bandwidthMbps() + " Mbps" : "---"), startX + 20, startY + 100, 0xCCCCCC);
        
        // Live Traffic Simulation
        if (payload.isConnected()) {
            long time = System.currentTimeMillis();
            // Create some deterministic but fluctuating "live" traffic numbers based on time and ping
            int baseTraffic = Math.min(payload.bandwidthMbps(), 150);
            int variationDown = (int)((Math.sin(time / 500.0) * 0.5 + 0.5) * baseTraffic);
            int variationUp = (int)((Math.cos(time / 400.0) * 0.5 + 0.5) * (baseTraffic / 2));
            
            guiGraphics.drawString(this.font, "Download: " + variationDown + " Mbps", startX + 20, startY + 115, 0x00FFFF);
            guiGraphics.drawString(this.font, "Upload: " + variationUp + " Mbps", startX + 120, startY + 115, 0xFF8800);
        }

        guiGraphics.drawString(this.font, "Press ESC to close", startX + 20, startY + 140, 0x555555);

        guiGraphics.pose().popPose();

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
