package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.RouterGuiSyncPayload;
import com.florentdubut.telecom.network.packet.RouterConfigPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class RouterScreen extends Screen {

    private final RouterGuiSyncPayload payload;
    private int localMaxDown;
    private int localMaxUp;

    public RouterScreen(RouterGuiSyncPayload payload) {
        super(Component.literal("Router Interface"));
        this.payload = payload;
        this.localMaxDown = payload.configuredMaxDown();
        this.localMaxUp = payload.configuredMaxUp();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int startX = centerX - 120;
        int startY = centerY - 80;

        // Downlink buttons
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            localMaxDown = Math.max(10, localMaxDown - 100);
        }).bounds(startX + 140, startY + 95, 20, 15).build());

        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            localMaxDown = Math.min(100000, localMaxDown + 100);
        }).bounds(startX + 200, startY + 95, 20, 15).build());

        // Uplink buttons
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            localMaxUp = Math.max(10, localMaxUp - 100);
        }).bounds(startX + 140, startY + 115, 20, 15).build());

        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            localMaxUp = Math.min(100000, localMaxUp + 100);
        }).bounds(startX + 200, startY + 115, 20, 15).build());
    }

    @Override
    public void onClose() {
        if (localMaxDown != payload.configuredMaxDown() || localMaxUp != payload.configuredMaxUp()) {
            PacketDistributor.sendToServer(new RouterConfigPayload(payload.pos(), localMaxDown, localMaxUp));
        }
        super.onClose();
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
        guiGraphics.drawString(this.font, "Status: " + statusText, startX + 20, startY + 30, statusColor);

        // IP Address
        guiGraphics.drawString(this.font, "IP Address: " + (!payload.ipAddress().isEmpty() ? payload.ipAddress() : "N/A"), startX + 20, startY + 45, 0xCCCCCC);

        // Ping
        guiGraphics.drawString(this.font, "Ping: " + (payload.isConnected() ? payload.pingMs() + " ms" : "---"), startX + 20, startY + 60, 0xCCCCCC);

        // Max Hardware Bandwidth
        guiGraphics.drawString(this.font, "Hardware Max: " + (payload.isConnected() ? payload.bandwidthMbps() + " Mbps" : "---"), startX + 20, startY + 75, 0xCCCCCC);
        
        // Plan Config
        guiGraphics.drawString(this.font, "Plan Max Down:", startX + 20, startY + 100, 0x00FFFF);
        guiGraphics.drawCenteredString(this.font, localMaxDown + " Mbps", startX + 180, startY + 100, 0xFFFFFF);

        guiGraphics.drawString(this.font, "Plan Max Up:", startX + 20, startY + 120, 0xFF8800);
        guiGraphics.drawCenteredString(this.font, localMaxUp + " Mbps", startX + 180, startY + 120, 0xFFFFFF);

        guiGraphics.drawString(this.font, "Press ESC to save and close", startX + 20, startY + 145, 0x555555);

        guiGraphics.pose().popPose();

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
