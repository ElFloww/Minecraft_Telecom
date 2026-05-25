package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.ServerGuiSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ServerScreen extends Screen {
    private final ServerGuiSyncPayload payload;
    private int totalDown;
    private int totalUp;
    private final int imageWidth = 256;
    private final int imageHeight = 175;

    public ServerScreen(ServerGuiSyncPayload payload) {
        super(Component.literal("Telecom Server Dashboard"));
        this.payload = payload;
        this.totalDown = payload.totalBandwidthDown();
        this.totalUp = payload.totalBandwidthUp();
    }

    public void updateBandwidth(int down, int up) {
        this.totalDown = down;
        this.totalUp = up;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Do nothing to keep the world fully visible
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int startX = (this.width - this.imageWidth) / 2;
        int startY = (this.height - this.imageHeight) / 2;

        // Draw a dark datacenter-like panel (Semi-transparent)
        guiGraphics.fill(startX, startY, startX + this.imageWidth, startY + this.imageHeight, 0xDD111111);
        guiGraphics.fill(startX + 2, startY + 2, startX + this.imageWidth - 2, startY + this.imageHeight - 2, 0xDD222222);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 100);

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
        guiGraphics.drawString(this.font, "Core Load:", startX + 10, startY + 120, 0xFFAA00);
        guiGraphics.drawString(this.font, "Down: " + totalDown + " Mbps / " + maxCapacity + " Mbps", startX + 20, startY + 133, 0x00FFFF);
        guiGraphics.drawString(this.font, "Up: " + totalUp + " Mbps / " + maxCapacity + " Mbps", startX + 20, startY + 146, 0xFF8800);
        
        int percent = (int)(((float)(totalDown + totalUp) / maxCapacity) * 100);
        guiGraphics.drawString(this.font, "Network Usage: " + percent + "%", startX + 10, startY + 160, percent > 80 ? 0xFF0000 : 0x00FF00);

        guiGraphics.pose().popPose();
        
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
