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
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Dark background overlay
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int boxWidth = 240;
        int boxHeight = 160;
        int startX = centerX - boxWidth / 2;
        int startY = centerY - boxHeight / 2;

        // Router Box Background
        guiGraphics.fill(startX, startY, startX + boxWidth, startY + boxHeight, 0xFF222222);
        // Border
        guiGraphics.renderOutline(startX, startY, boxWidth, boxHeight, 0xFF44AAFF);

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
        guiGraphics.drawString(this.font, "Bandwidth Max: " + (payload.isConnected() ? payload.bandwidthMbps() + " Mbps" : "---"), startX + 20, startY + 100, 0xCCCCCC);
        
        guiGraphics.drawString(this.font, "Press ESC to close", startX + 20, startY + 135, 0x555555);
    }
}
