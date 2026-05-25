package com.florentdubut.telecom.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SmartphoneScreen extends Screen {

    public SmartphoneScreen() {
        super(Component.literal("Smartphone OS"));
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

        // Speedtest App Button
        this.addRenderableWidget(Button.builder(Component.literal("Speedtest"), b -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(new SmartphoneSpeedtestScreen(this));
        }).bounds(startX + 15, startY + 40, 60, 60).build());

        // Web Browser App (WIP)
        this.addRenderableWidget(Button.builder(Component.literal("Web"), b -> {
            // WIP
        }).bounds(startX + 85, startY + 40, 60, 60).build());

        // SMS App (WIP)
        this.addRenderableWidget(Button.builder(Component.literal("SMS"), b -> {
            // WIP
        }).bounds(startX + 15, startY + 110, 60, 60).build());
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Overlay
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
        
        // Phone screen background (Wallpaper)
        guiGraphics.fillGradient(startX, startY, startX + screenW, startY + screenH, 0xFF1B5E20, 0xFF004D40);

        // Status bar
        guiGraphics.fill(startX, startY, startX + screenW, startY + 15, 0x88000000);
        
        com.florentdubut.telecom.network.packet.NetworkScanResponsePayload scan = SmartphoneHUD.latestScan;
        if (scan != null && scan.found() && (System.currentTimeMillis() - SmartphoneHUD.lastScanTime) < 5000) {
            guiGraphics.drawString(this.font, scan.tech(), startX + 5, startY + 4, 0xFFFFFF);
            guiGraphics.drawString(this.font, scan.signalStrength() + " dBm", startX + screenW - 45, startY + 4, 0xFFFFFF);
        } else {
            guiGraphics.drawString(this.font, "No Service", startX + 5, startY + 4, 0xFF5555);
        }

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 100);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.pose().popPose();
    }
}
