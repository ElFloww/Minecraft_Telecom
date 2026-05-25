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
    
    private EditBox downBox;
    private EditBox upBox;

    private boolean speedtestActive = false;
    private com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload currentSpeedtestData = null;

    public void updateSpeedtestProgress(com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload payload) {
        this.speedtestActive = !payload.state().equals("FINISHED");
        this.currentSpeedtestData = payload;
    }

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
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int startX = centerX - 130;
        int startY = centerY - 90;

        this.downBox = new EditBox(this.font, startX + 130, startY + 105, 60, 15, Component.literal("Downlink"));
        this.downBox.setValue(String.valueOf(payload.configuredMaxDown()));
        this.addRenderableWidget(this.downBox);

        this.upBox = new EditBox(this.font, startX + 130, startY + 125, 60, 15, Component.literal("Uplink"));
        this.upBox.setValue(String.valueOf(payload.configuredMaxUp()));
        this.addRenderableWidget(this.upBox);

        this.addRenderableWidget(Button.builder(Component.literal("Run Speedtest"), b -> {
            if (payload.isConnected()) {
                int confDown = payload.configuredMaxDown();
                try { confDown = Integer.parseInt(downBox.getValue()); } catch (NumberFormatException ignored) {}
                PacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.StartSpeedtestPayload(payload.pos(), payload.ipAddress(), confDown));
            }
        }).bounds(startX + 20, startY + 145, 100, 20).build());
    }

    @Override
    public void onClose() {
        int localMaxDown = payload.configuredMaxDown();
        int localMaxUp = payload.configuredMaxUp();
        
        try { localMaxDown = Integer.parseInt(downBox.getValue()); } catch (NumberFormatException ignored) {}
        try { localMaxUp = Integer.parseInt(upBox.getValue()); } catch (NumberFormatException ignored) {}

        if (localMaxDown != payload.configuredMaxDown() || localMaxUp != payload.configuredMaxUp()) {
            PacketDistributor.sendToServer(new RouterConfigPayload(payload.pos(), localMaxDown, localMaxUp));
        }
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
        
        // Plan Config
        guiGraphics.drawString(this.font, "Configure Plan Speed:", startX + 20, startY + 90, 0xAAAAAA);
        guiGraphics.drawString(this.font, "Max Down (Mbps):", startX + 20, startY + 108, 0x00FFFF);
        guiGraphics.drawString(this.font, "Max Up (Mbps):", startX + 20, startY + 128, 0xFF8800);

        guiGraphics.drawString(this.font, "Press ESC to save and close", startX + 130, startY + 150, 0x555555);

        // Speedtest overlay logic
        if (speedtestActive && currentSpeedtestData != null) {
            guiGraphics.fill(startX + 125, startY + 30, startX + boxWidth - 10, startY + 85, 0xFF111111);
            guiGraphics.renderOutline(startX + 125, startY + 30, boxWidth - 135, 55, 0xFF555555);
            
            guiGraphics.drawString(this.font, "SPEEDTEST: " + currentSpeedtestData.state(), startX + 130, startY + 35, 0xFFFFFF);
            
            guiGraphics.drawString(this.font, "Ping: " + currentSpeedtestData.pingMs() + " ms", startX + 130, startY + 50, 0x00FF00);
            
            if (currentSpeedtestData.state().equals("DOWNLOAD") || currentSpeedtestData.state().equals("UPLOAD") || currentSpeedtestData.state().equals("FINISHED")) {
                if (currentSpeedtestData.state().equals("DOWNLOAD")) {
                    guiGraphics.drawString(this.font, "Down: " + currentSpeedtestData.actualBandwidth() + " Mbps", startX + 130, startY + 62, 0x00FFFF);
                } else {
                    guiGraphics.drawString(this.font, "Up: " + currentSpeedtestData.actualBandwidth() + " Mbps", startX + 130, startY + 74, 0xFF8800);
                }
            }
        } else if (!speedtestActive && currentSpeedtestData != null) {
            guiGraphics.fill(startX + 125, startY + 30, startX + boxWidth - 10, startY + 85, 0xFF111111);
            guiGraphics.renderOutline(startX + 125, startY + 30, boxWidth - 135, 55, 0xFF555555);
            guiGraphics.drawString(this.font, "SPEEDTEST FINISHED", startX + 130, startY + 35, 0xFFFFFF);
            guiGraphics.drawString(this.font, "Ping: " + currentSpeedtestData.pingMs() + " ms", startX + 130, startY + 50, 0x00FF00);
        }

        guiGraphics.pose().popPose();

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
