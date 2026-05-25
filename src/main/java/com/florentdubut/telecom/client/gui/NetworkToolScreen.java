package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.NetworkToolSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class NetworkToolScreen extends Screen {
    private NetworkToolSyncPayload payload;
    private final int imageWidth = 200;
    private final int imageHeight = 120;
    private int tickCounter = 0;

    public NetworkToolScreen(NetworkToolSyncPayload payload) {
        super(Component.literal("Network Diagnostic Tool"));
        this.payload = payload;
    }

    public void updatePayload(NetworkToolSyncPayload payload) {
        this.payload = payload;
    }

    @Override
    public void tick() {
        super.tick();
        tickCounter++;
        if (tickCounter % 10 == 0) { // Refresh every 0.5s
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload(payload.clickedPos())
            );
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Do nothing to keep the world fully visible and unblurred
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int startX = (this.width - this.imageWidth) / 2;
        int startY = (this.height - this.imageHeight) / 2;

        // Tablet-like background (Semi-transparent so we can see the cables behind)
        guiGraphics.fill(startX, startY, startX + this.imageWidth, startY + this.imageHeight, 0xDD222222);
        guiGraphics.fill(startX + 2, startY + 2, startX + this.imageWidth - 2, startY + this.imageHeight - 2, 0xDD111111);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 100);

        // Header
        guiGraphics.fill(startX + 2, startY + 2, startX + this.imageWidth - 2, startY + 20, 0xDD0055AA);
        guiGraphics.drawString(this.font, "Network Diagnostics", startX + 10, startY + 6, 0xFFFFFF);

        // Content
        guiGraphics.drawString(this.font, "Cable Type: " + payload.edgeType(), startX + 10, startY + 30, payload.edgeType().contains("Fiber") ? 0x00FFFF : 0xFFAA00);
        guiGraphics.drawString(this.font, "Length: " + payload.length() + " blocks", startX + 10, startY + 45, 0xCCCCCC);
        guiGraphics.drawString(this.font, "Max Bandwidth: " + payload.maxBandwidth() + " Mbps", startX + 10, startY + 60, 0xCCCCCC);
        
        float usageDownPct = (float) payload.usageDown() / payload.maxBandwidth() * 100f;
        float usageUpPct = (float) payload.usageUp() / payload.maxBandwidth() * 100f;

        guiGraphics.drawString(this.font, "Down: " + payload.usageDown() + " Mbps (" + String.format("%.1f", usageDownPct) + "%)", startX + 10, startY + 75, 0x00FFFF);
        int barWidth = 180;
        guiGraphics.fill(startX + 10, startY + 85, startX + 10 + barWidth, startY + 90, 0xFF444444);
        int fillWidthDown = (int)(barWidth * Math.min(1.0f, (float)payload.usageDown() / payload.maxBandwidth()));
        guiGraphics.fill(startX + 10, startY + 85, startX + 10 + fillWidthDown, startY + 90, 0xFF00FFFF);

        guiGraphics.drawString(this.font, "Up: " + payload.usageUp() + " Mbps (" + String.format("%.1f", usageUpPct) + "%)", startX + 10, startY + 95, 0xFF8800);
        guiGraphics.fill(startX + 10, startY + 105, startX + 10 + barWidth, startY + 110, 0xFF444444);
        int fillWidthUp = (int)(barWidth * Math.min(1.0f, (float)payload.usageUp() / payload.maxBandwidth()));
        guiGraphics.fill(startX + 10, startY + 105, startX + 10 + fillWidthUp, startY + 110, 0xFFFF8800);

        guiGraphics.pose().popPose();
        
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
