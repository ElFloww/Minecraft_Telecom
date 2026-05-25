package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.NetworkToolSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class NetworkToolScreen extends Screen {
    private final NetworkToolSyncPayload payload;
    private final int imageWidth = 200;
    private final int imageHeight = 120;

    public NetworkToolScreen(NetworkToolSyncPayload payload) {
        super(Component.literal("Network Diagnostic Tool"));
        this.payload = payload;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Draw a light dimming layer without blurring the cables
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0x80000000, 0xA0000000);

        int startX = (this.width - this.imageWidth) / 2;
        int startY = (this.height - this.imageHeight) / 2;

        // Tablet-like background
        guiGraphics.fill(startX, startY, startX + this.imageWidth, startY + this.imageHeight, 0xFF333333);
        guiGraphics.fill(startX + 2, startY + 2, startX + this.imageWidth - 2, startY + this.imageHeight - 2, 0xFF1E1E1E);

        // Header
        guiGraphics.fill(startX + 2, startY + 2, startX + this.imageWidth - 2, startY + 20, 0xFF0055AA);
        guiGraphics.drawString(this.font, "Network Diagnostics", startX + 10, startY + 6, 0xFFFFFF);

        // Content
        guiGraphics.drawString(this.font, "Cable Type: " + payload.edgeType(), startX + 10, startY + 30, payload.edgeType().contains("Fiber") ? 0x00FFFF : 0xFFAA00);
        guiGraphics.drawString(this.font, "Length: " + payload.length() + " blocks", startX + 10, startY + 45, 0xCCCCCC);
        guiGraphics.drawString(this.font, "Max Bandwidth: " + payload.maxBandwidth() + " Mbps", startX + 10, startY + 60, 0xCCCCCC);
        
        guiGraphics.drawString(this.font, "Load: " + payload.usagePercent() + "%", startX + 10, startY + 75, payload.usagePercent() > 80 ? 0xFF0000 : 0x00FF00);

        // Progress bar
        int barWidth = 180;
        guiGraphics.fill(startX + 10, startY + 90, startX + 10 + barWidth, startY + 100, 0xFF444444);
        int fillWidth = (int)(barWidth * (payload.usagePercent() / 100.0f));
        guiGraphics.fill(startX + 10, startY + 90, startX + 10 + fillWidth, startY + 100, payload.usagePercent() > 80 ? 0xFFFF0000 : 0xFF00FF00);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
