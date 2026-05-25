package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.ServerGuiSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ServerScreen extends Screen {
    private final ServerGuiSyncPayload payload;
    private final int imageWidth = 256;
    private final int imageHeight = 160;

    public ServerScreen(ServerGuiSyncPayload payload) {
        super(Component.literal("Telecom Server Dashboard"));
        this.payload = payload;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Draw a dark dimming layer without blurring the server room
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC0000000, 0xD0000000);

        int startX = (this.width - this.imageWidth) / 2;
        int startY = (this.height - this.imageHeight) / 2;

        // Draw a dark datacenter-like panel
        guiGraphics.fill(startX, startY, startX + this.imageWidth, startY + this.imageHeight, 0xFF111111);
        guiGraphics.fill(startX + 2, startY + 2, startX + this.imageWidth - 2, startY + this.imageHeight - 2, 0xFF222222);

        // Title
        guiGraphics.drawString(this.font, "Telecom Server - Datacenter Core", startX + 10, startY + 10, 0x00FF00);

        // Stats
        guiGraphics.drawString(this.font, "Connected Devices:", startX + 10, startY + 35, 0xAAAAAA);
        
        guiGraphics.drawString(this.font, "• Routers: " + payload.routerCount(), startX + 20, startY + 50, 0xFFFFFF);
        guiGraphics.drawString(this.font, "• Antennas: " + payload.antennaCount(), startX + 20, startY + 65, 0xFFFFFF);
        guiGraphics.drawString(this.font, "• Mobile Phones: " + payload.phoneCount(), startX + 20, startY + 80, 0xFFFFFF);

        int totalDevices = payload.routerCount() + payload.antennaCount() + payload.phoneCount();
        guiGraphics.drawString(this.font, "Total Endpoints: " + totalDevices, startX + 10, startY + 100, 0x00FFFF);

        // Bandwidth
        int maxCapacity = 100000; // 100 Gbps
        guiGraphics.drawString(this.font, "Core Load: " + payload.totalBandwidthUsage() + " Mbps / " + maxCapacity + " Mbps", startX + 10, startY + 120, 0xFFAA00);
        
        int percent = (int)(((float)payload.totalBandwidthUsage() / maxCapacity) * 100);
        guiGraphics.drawString(this.font, "CPU/Network Usage: " + percent + "%", startX + 10, startY + 135, percent > 80 ? 0xFF0000 : 0x00FF00);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
