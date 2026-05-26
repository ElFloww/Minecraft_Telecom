package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.RouterGuiSyncPayload;
import com.florentdubut.telecom.network.packet.RouterConfigPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class RouterScreen extends Screen {

    private final RouterGuiSyncPayload payload;


    private boolean speedtestActive = false;
    private com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload currentSpeedtestData = null;
    private int lastDownBw = 0;
    private int lastUpBw = 0;
    private int durationIndex = 0;
    private final int[] DURATION_TICKS = {300, 600, 1200};
    private final String[] DURATION_LABELS = {"15s", "30s", "60s"};

    public void updateSpeedtestProgress(com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload payload) {
        if (!payload.clientIp().equals(this.payload.ipAddress())) return;
        
        this.speedtestActive = !payload.state().equals("FINISHED");
        this.currentSpeedtestData = payload;
        this.currentSpeedtestData = payload;
        
        if (payload.state().equals("DOWNLOAD")) {
            this.lastDownBw = payload.actualBandwidth();
        } else if (payload.state().equals("UPLOAD")) {
            this.lastUpBw = payload.actualBandwidth();
        }
    }

    public RouterScreen(RouterGuiSyncPayload payload) {
        super(Component.literal("Router Interface"));
        this.payload = payload;
        
        if (payload.lastPing() > 0) {
            this.lastDownBw = payload.lastDownBw();
            this.lastUpBw = payload.lastUpBw();
            this.currentSpeedtestData = new com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload(
                payload.ipAddress(), "FINISHED", payload.lastPing(), 0, 40, 40
            );
        }
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
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int startX = centerX - 260 / 2;
        int startY = centerY - 180 / 2;

        this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Durée: " + DURATION_LABELS[durationIndex]), button -> {
            durationIndex = (durationIndex + 1) % DURATION_TICKS.length;
            button.setMessage(Component.literal("Durée: " + DURATION_LABELS[durationIndex]));
        }).bounds(startX + 20, startY + 120, 100, 20).build());

        this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("START SPEEDTEST"), button -> {
            if (!this.speedtestActive) {
                int confDown = payload.configuredMaxDown();
                int confUp = payload.configuredMaxUp();
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.StartSpeedtestPayload(payload.pos(), payload.ipAddress(), confDown, confUp, 0, 0, DURATION_TICKS[durationIndex]));
                this.speedtestActive = true;
                this.currentSpeedtestData = null;
                this.lastDownBw = 0;
                this.lastUpBw = 0;
            }
        }).bounds(startX + 20, startY + 145, 100, 20).build());
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Dark background overlay
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0x40101010, 0x60101010);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int boxWidth = 260;
        int boxHeight = 180;
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

        // Max Hardware Bandwidth
        guiGraphics.drawString(this.font, "Hardware Max: " + (payload.isConnected() ? payload.bandwidthMbps() + " Mbps" : "---"), startX + 20, startY + 60, 0xCCCCCC);
        
        guiGraphics.drawString(this.font, "Plan Down: " + payload.configuredMaxDown() + " Mbps", startX + 20, startY + 90, 0x00FFFF);
        guiGraphics.drawString(this.font, "Plan Up: " + payload.configuredMaxUp() + " Mbps", startX + 20, startY + 110, 0xFF8800);

        guiGraphics.drawString(this.font, "Press ESC to save and close", startX + 130, startY + 150, 0x555555);

        // Speedtest overlay logic (shifted right)
        int stX = startX + 265;
        int stY = startY + 30;
        int stW = 120;
        int stH = 100;
        
        if (currentSpeedtestData != null) {
            guiGraphics.fill(stX, stY, stX + stW, stY + stH, 0xFF111111);
            guiGraphics.renderOutline(stX, stY, stW, stH, 0xFF555555);
            
            guiGraphics.drawString(this.font, speedtestActive ? "TESTING: " + currentSpeedtestData.state() : "FINISHED", stX + 10, stY + 10, 0xFFFFFF);
            guiGraphics.drawString(this.font, "Ping: " + currentSpeedtestData.pingMs() + " ms", stX + 10, stY + 30, 0x00FF00);
            
            if (currentSpeedtestData.state().equals("DOWNLOAD") || currentSpeedtestData.state().equals("UPLOAD") || currentSpeedtestData.state().equals("FINISHED")) {
                guiGraphics.drawString(this.font, "Down: " + this.lastDownBw + " Mbps", stX + 10, stY + 50, 0x00FFFF);
            }
            if (currentSpeedtestData.state().equals("UPLOAD") || currentSpeedtestData.state().equals("FINISHED")) {
                guiGraphics.drawString(this.font, "Up: " + this.lastUpBw + " Mbps", stX + 10, stY + 70, 0xFF8800);
            }
        }

        guiGraphics.pose().popPose();

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
