package com.florentdubut.telecom.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class SmartphoneSpeedtestScreen extends Screen {

    private final Screen parentScreen;
    private boolean speedtestActive = false;
    private com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload currentSpeedtestData = null;
    
    private int lastDownBw = 0;
    private int lastUpBw = 0;

    public SmartphoneSpeedtestScreen(Screen parentScreen) {
        super(Component.literal("Speedtest"));
        this.parentScreen = parentScreen;
    }
    
    public void updateSpeedtestProgress(com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload payload) {
        if (SmartphoneHUD.latestScan == null || !payload.clientIp().equals(SmartphoneHUD.latestScan.ipAddress())) return;
        
        this.speedtestActive = !payload.state().equals("FINISHED");
        this.currentSpeedtestData = payload;
        
        if (payload.state().equals("DOWNLOAD")) {
            this.lastDownBw = payload.actualBandwidth();
        } else if (payload.state().equals("UPLOAD")) {
            this.lastUpBw = payload.actualBandwidth();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        int screenW = 160;
        int screenH = 260;
        int startX = (this.width - screenW) / 2;
        int startY = (this.height - screenH) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Start Test"), b -> {
            if (this.speedtestActive) return;
            com.florentdubut.telecom.network.packet.NetworkScanResponsePayload scan = SmartphoneHUD.latestScan;
            if (scan != null && scan.found()) {
                PacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.StartSpeedtestPayload(
                    scan.antennaPos(), 
                    scan.ipAddress(), 
                    scan.maxDown(),
                    scan.maxUp()
                ));
                this.speedtestActive = true;
                this.currentSpeedtestData = null;
                this.lastDownBw = 0;
                this.lastUpBw = 0;
            } else {
                net.minecraft.client.Minecraft.getInstance().player.sendSystemMessage(Component.literal("No Network Signal!"));
            }
        }).bounds(startX + 30, startY + 220, 100, 20).build());
        
        this.addRenderableWidget(Button.builder(Component.literal("< Back"), b -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(parentScreen);
        }).bounds(startX + 5, startY + 20, 50, 15).build());
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0x40000000, 0x60000000);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int screenW = 160;
        int screenH = 260;
        int startX = (this.width - screenW) / 2;
        int startY = (this.height - screenH) / 2;

        // Phone bezel
        guiGraphics.fill(startX - 5, startY - 5, startX + screenW + 5, startY + screenH + 5, 0xFF222222);
        // App background
        guiGraphics.fill(startX, startY, startX + screenW, startY + screenH, 0xFF111122);
        
        // Status bar
        guiGraphics.fill(startX, startY, startX + screenW, startY + 15, 0x88000000);
        com.florentdubut.telecom.network.packet.NetworkScanResponsePayload scan = SmartphoneHUD.latestScan;
        if (scan != null && scan.found()) {
            guiGraphics.drawString(this.font, scan.tech(), startX + 5, startY + 4, 0xFFFFFF);
        } else {
            guiGraphics.drawString(this.font, "No Service", startX + 5, startY + 4, 0xFF5555);
        }

        guiGraphics.drawCenteredString(this.font, "SPEEDTEST", startX + screenW / 2, startY + 45, 0xFFFFFF);
        
        if (currentSpeedtestData != null) {
            guiGraphics.drawCenteredString(this.font, speedtestActive ? "Testing: " + currentSpeedtestData.state() : "FINISHED", startX + screenW / 2, startY + 70, speedtestActive ? 0xAAAAAA : 0x00FF00);
            
            guiGraphics.drawString(this.font, "Ping: " + currentSpeedtestData.pingMs() + " ms", startX + 20, startY + 90, 0x00FF00);
            
            if (currentSpeedtestData.state().equals("DOWNLOAD") || currentSpeedtestData.state().equals("UPLOAD") || currentSpeedtestData.state().equals("FINISHED")) {
                guiGraphics.drawString(this.font, "Down: " + this.lastDownBw + " Mbps", startX + 20, startY + 110, 0x00FFFF);
            }
            if (currentSpeedtestData.state().equals("UPLOAD") || currentSpeedtestData.state().equals("FINISHED")) {
                guiGraphics.drawString(this.font, "Up: " + this.lastUpBw + " Mbps", startX + 20, startY + 130, 0xFF8800);
            }
        }

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 100);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.pose().popPose();
    }
}
